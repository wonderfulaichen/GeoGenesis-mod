"""Offline calibration, fixed-constant signal shaping, CPU-only production-type comparison.
Usage from MDK: python -B src/diagnostics/fast-erosion-shaping.py build/<NEW-directory>
"""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import time
import numpy as np
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.colors import ListedColormap, Normalize

ROOT = Path(__file__).resolve().parents[2]
OUT = (ROOT/sys.argv[1]).resolve()
if OUT.exists() or ROOT/'build' not in OUT.parents:
    raise ValueError('NEW build directory required')
OUT.mkdir(parents=True)
COMMANDS = []


def sha(p):
    return hashlib.sha256(p.read_bytes()).hexdigest()


def save(name, v):
    (OUT/name).write_text(json.dumps(v, indent=2, ensure_ascii=False)+'\n', encoding='utf-8')


def run(args):
    COMMANDS.append(args)
    save('commands.json', COMMANDS)
    t = time.perf_counter()
    p = subprocess.run(args, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    text = p.stdout.decode('utf-8', errors='replace')
    with (OUT/'validation.log').open('a', encoding='utf-8') as f:
        f.write('\nCOMMAND '+subprocess.list2cmdline(args)+'\n'+text+'\nexit=%d seconds=%.3f\n' % (p.returncode,time.perf_counter()-t))
    print(text, flush=True)
    if p.returncode:
        raise RuntimeError('command failed')


def level(a):
    return dict(min=float(a.min()),max=float(a.max()),mean=float(a.mean()),std=float(a.std()),range=float(np.ptp(a)))


def gradient(a,step):
    gx=(a[1:-1,2:]-a[1:-1,:-2])/(2*step)
    gz=(a[2:,1:-1]-a[:-2,1:-1])/(2*step)
    q=np.quantile(np.hypot(gx,gz),[.5,.99,.999,1])
    return dict(zip(['p50','p99','p999','max'],map(float,q)),tail=float(q[2]/q[0]))


def shade(a,step):
    gz,gx=np.gradient(a*236.44*1.5,step*2)
    l=np.array([-.5,-.3,1]);l/=np.linalg.norm(l)
    return .65+.35*np.maximum(0,(-gx*l[0]-gz*l[1]+l[2])/np.sqrt(1+gx*gx+gz*gz))


def figures(arr,m,palette):
    cmap=ListedColormap(palette[:,1:]/255)
    n=m['size'];step=m['stepWu'];span=m['spanWu']*2
    def rgb(a):
        return cmap(Normalize(-.35,1.2,clip=True)(.02+1.5*a))[...,:3]*shade(a,step)[...,None]
    for filename,sl in [('AB.png',slice(None)),('AB-center-zoom.png',slice(n//2-96,n//2+96))]:
        lo,hi=(0,n) if sl.start is None else (sl.start,sl.stop)
        ext=(lo*step*2,hi*step*2,hi*step*2,lo*step*2)
        fig,axes=plt.subplots(1,2,figsize=(16,8),dpi=140)
        for ax,key,title in zip(axes,['A','B'],['A | production original','B | full shaping k*(f*raw+.25E), NO .65']):
            ax.imshow(rgb(arr[key])[sl,sl],extent=ext,interpolation='nearest')
            ax.set(title=title,xlabel='local x (blocks)',ylabel='local z (blocks)')
        fig.colorbar(plt.cm.ScalarMappable(norm=Normalize(-.35,1.2),cmap=cmap),ax=axes,fraction=.02,label='fixed e limits; no per-image normalization')
        fig.suptitle('Production TYPE experiment only | fixed palette/light/HS=2/vertical scale')
        fig.text(.1,.025,'Y proxy=236.44*1.5*h; no HeightCurve/clamp; unchanged 1830wu window and 769x769 output grid. Not Minecraft world output.')
        fig.savefig(OUT/filename);plt.close(fig)
    fig,axes=plt.subplots(1,3,figsize=(19,7),dpi=140)
    for ax,d,title in zip(axes,[arr['B']-arr['A'],arr['base-shell'],arr['erosion-shell']],['B - A','replayed base k*f*raw*.7','replayed erosion k*.25E*.7']):
        im=ax.imshow(d,cmap='RdBu_r',vmin=-.6,vmax=.6,extent=(0,span,span,0),interpolation='nearest')
        ax.set(title=title,xlabel='local x (blocks)',ylabel='local z (blocks)')
    fig.colorbar(im,ax=axes,fraction=.02,label='shell h, fixed +/- .6')
    fig.savefig(OUT/'differences.png');plt.close(fig)
    fig,axes=plt.subplots(2,1,figsize=(16,9),dpi=140,sharex=True,sharey=True)
    x=np.arange(n)*step*2
    for ax,col in zip(axes,[False,True]):
        for key in ['A','B','raw-shell','base-shell']:
            ax.plot(x,arr[key][:,n//2] if col else arr[key][n//2,:],lw=.9,label=key)
        ax.set(ylim=(-.2,1.0),xlabel='distance (blocks)',ylabel='shell h',title='center z cut' if col else 'center x cut')
        ax.legend();ax.grid(alpha=.2)
    fig.tight_layout();fig.savefig(OUT/'profiles.png');plt.close(fig)
    np.savetxt(OUT/'profiles.csv',np.column_stack([x]+[arr[k][n//2,:] for k in ['A','B','raw-shell','base-shell']]
                 +[arr[k][:,n//2] for k in ['A','B','raw-shell','base-shell']]),delimiter=',',
                 header='distance,A_x,B_x,raw_x,base_x,A_z,B_z,raw_z,base_z',comments='')
    sl=slice(n//2-96,n//2+96);ext=(sl.start*step*2,sl.stop*step*2,sl.stop*step*2,sl.start*step*2)
    fig,ax=plt.subplots(figsize=(9,8),dpi=140)
    im=ax.imshow(arr['erosion-shell'][sl,sl],vmin=-.25*m['k']*.7,vmax=.25*m['k']*.7,cmap='RdBu_r',extent=ext,interpolation='nearest')
    ax.set(title='Erosion shaping contribution | fixed center crop',xlabel='local x (blocks)',ylabel='local z (blocks)')
    fig.colorbar(im,ax=ax,label='k*.25*E*.7 (NOT B-original raw)')
    fig.savefig(OUT/'erosion-center-zoom.png');plt.close(fig)


def main():
    locked=[p for p in (ROOT/'src/main').rglob('*') if p.is_file()]
    locked+=list((ROOT/'src/diagnostics/java/com/geogenesis/standalone').glob('FastErosion*.java'))
    for d in (ROOT/'build').glob('fast-erosion-*'):
        if d!=OUT:
            locked += [p for p in d.rglob('*') if p.is_file()]
    before={str(p.relative_to(ROOT)):sha(p) for p in locked}
    save('locked-before.json',before)
    classes=OUT/'classes';classes.mkdir()
    java='src/diagnostics/java/com/geogenesis/worldgen/terrain/morph/FastErosionShapingProbe.java'
    demo='src/diagnostics/java/com/geogenesis/standalone/FastErosionOriginalDemo.java'
    run(['javac','-encoding','UTF-8','-proc:none','-d',str(classes),'-sourcepath','src/main/java;src/diagnostics/java',java,demo])
    cp=['java','-Xmx2g','-cp',str(classes),'com.geogenesis.worldgen.terrain.morph.FastErosionShapingProbe']
    run(cp+['calibrate',str(OUT/'calibration')])
    constants=OUT/'calibration/constants.properties';locked_constant=sha(constants)
    run(cp+['render',str(OUT/'data'),str(constants)])
    assert sha(constants)==locked_constant
    run(['java','-Xmx1g','-cp',str(classes),'com.geogenesis.standalone.FastErosionOriginalDemo',str(OUT/'reference-rerun')])
    ref=ROOT/'build/fast-erosion-refactor3-20261103/run/height.rgba'
    assert sha(ref)==sha(OUT/'reference-rerun/height.rgba')
    prior=ROOT/'build/fast-erosion-scale-only-20261103-final'
    assert sha(prior/'data/A.f64be')==sha(OUT/'data/A.f64be')
    meta=json.loads((OUT/'data/sampling.json').read_text());n=meta['size']
    arr={p.stem:np.fromfile(p,dtype='>f8').astype(float).reshape(n,n) for p in (OUT/'data').glob('*.f64be')}
    assert all(np.isfinite(a).all() for a in arr.values())
    assert np.allclose(arr['B'],arr['base-shell']+arr['erosion-shell'],atol=1e-14)
    base,inc,b=arr['base-shell'],arr['erosion-shell'],arr['B']
    cov=float(np.mean((base-base.mean())*(inc-inc.mean())))
    stats={'levels':{key:level(arr[key]) for key in ['A','B','raw-shell','base-shell','erosion-shell']},
           'slopesHperWu':{key:gradient(arr[key],meta['stepWu']) for key in ['A','B','raw-shell']},
           'maskInitialFullFraction':float((arr['initial-mask']>=1).mean()),
           'inputSlope':dict(level(arr['input-slope']),p50=float(np.median(arr['input-slope']))),
           'lapSaturatedFraction':float((abs(arr['input-lap'])>=1).mean()),
           'colorSaturatedFraction':{key:float((((.02+1.5*arr[key])<-.35)|((.02+1.5*arr[key])>1.2)).mean()) for key in ['A','B']},
           'displayStdReplayRatio':float(b.std()/arr['raw-shell'].std()),
           'erosionStdOverOutputStd':float(inc.std()/b.std()),'erosionStdOverBaseStd':float(inc.std()/base.std()),
           'varianceBudget':{'baseOverTotal':float(base.var()/b.var()),'erosionOverTotal':float(inc.var()/b.var()),'twoCovOverTotal':2*cov/float(b.var())},
           'correlationBwithOriginalRaw':float(np.corrcoef(b.ravel(),arr['raw-shell'].ravel())[0,1]),
           'BminusA':level(arr['B']-arr['A'])}
    save('statistics.json',stats)
    palette=np.loadtxt(prior/'data/project-palette.csv',delimiter=',',skiprows=1)
    figures(arr,meta,palette)
    assert all(sha(ROOT/p)==v for p,v in before.items()),'locked files changed'
    save('manifest.json',{'purpose':'diagnostics full shaping; NO production integration','sampling':meta,
         'calibration':json.loads((OUT/'calibration/calibration.json').read_text()),'commands':COMMANDS,
         'constantsSha256':locked_constant,'sourceHashes':{java:sha(ROOT/java),str(Path(__file__).resolve().relative_to(ROOT)):sha(Path(__file__)),
         **{str(p.relative_to(ROOT)):sha(p) for p in (ROOT/'src/main/java/com/geogenesis/worldgen/terrain/morph').glob('*.java')}},
         'paletteInput':str(prior/'data/project-palette.csv'),'paletteSha':sha(prior/'data/project-palette.csv'),
         'render':'fixed e [-.35,1.2], TerrainPreview light(-.5,-.3,1), shade .65+.35*dot, HS2, Y proxy236.44*1.5*h, no normalize/clamp',
         'files':{str(p.relative_to(OUT)):sha(p) for p in OUT.rglob('*') if p.is_file() and 'classes' not in p.parts}})
    (OUT/'verification.txt').write_text('PASS fresh javac; full reference RGBA exact\nPASS A full SHA equal prior production A; every sample production check\n'
        'PASS B full shaping formula before .7 shell, no .65 mix\nPASS fixed offline constants unchanged during rendering\n'
        'PASS component reconstruction, all finite; main and existing baselines unchanged\n'
        'NOT VERIFIED: infinite world, mixed-type transitions, production integration, MC blocks, all-octave masks\n',encoding='utf-8')
    print(json.dumps({'calibration':json.loads((OUT/'calibration/calibration.json').read_text()),'stats':stats},indent=2))
    print('DONE',OUT)


if __name__=='__main__':
    main()
