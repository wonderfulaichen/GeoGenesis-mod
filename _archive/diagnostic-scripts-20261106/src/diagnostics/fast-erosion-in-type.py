"""CPU-only build/run/render for FastErosionInTypeProbe. Refuses existing output.
Run from MDK: python -B src/diagnostics/fast-erosion-in-type.py build/<NEW-directory>
"""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import time

import numpy as np
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.colors import ListedColormap, Normalize
from PIL import Image

ROOT = Path(__file__).resolve().parents[2]
OUT = (ROOT / sys.argv[1]).resolve()
if ROOT / 'build' not in OUT.parents or OUT.exists():
    raise ValueError('Output must be a NEW directory below MDK/build')
OUT.mkdir(parents=True)
DATA = OUT / 'data'
COMMANDS = []


def sha(p):
    return hashlib.sha256(p.read_bytes()).hexdigest()


def save(name, value):
    (OUT / name).write_text(json.dumps(value, indent=2, ensure_ascii=False) + '\n', encoding='utf-8')


def locked_files():
    files = [p for p in (ROOT / 'src/main').rglob('*') if p.is_file()]
    for folder in (ROOT / 'build').glob('fast-erosion-*'):
        if folder != OUT:
            files += [p for p in folder.rglob('*') if p.is_file()]
    files += list((ROOT / 'src/diagnostics/java/com/geogenesis/standalone').glob('FastErosion*.java'))
    return {str(p.relative_to(ROOT)): sha(p) for p in files}


def run(args):
    COMMANDS.append(args)
    save('commands.json', COMMANDS)
    start = time.perf_counter()
    result = subprocess.run(args, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    text = result.stdout.decode('utf-8', errors='replace')
    with (OUT / 'validation.log').open('a', encoding='utf-8') as f:
        f.write('\nCOMMAND ' + subprocess.list2cmdline(args) + '\n' + text
                + '\nexit=%d elapsedSeconds=%.6f\n' % (result.returncode, time.perf_counter()-start))
    print(text, flush=True)
    if result.returncode:
        raise RuntimeError('Command failed: ' + str(args))


def level(a):
    q = np.quantile(a, [0, .01, .5, .99, 1])
    return dict(zip(('min', 'p01', 'p50', 'p99', 'max'), map(float, q)),
                mean=float(a.mean()), std=float(a.std()), range=float(np.ptp(a)))


def difference(a, base):
    d = a - base
    return dict(mean=float(d.mean()), rms=float(np.sqrt(np.mean(d*d))), std=float(d.std()),
                p99Abs=float(np.quantile(abs(d), .99)), maxAbs=float(abs(d).max()),
                detailStdOverBaseStd=float(d.std()/base.std()),
                detailRangeOverBaseRange=float(np.ptp(d)/np.ptp(base)))


def slopes(a, step):
    gx = (a[1:-1, 2:] - a[1:-1, :-2]) / (2*step)
    gz = (a[2:, 1:-1] - a[:-2, 1:-1]) / (2*step)
    g = np.hypot(gx, gz)
    p = np.quantile(g, [.5, .9, .99, .999, 1])
    return dict(zip(('p50', 'p90', 'p99', 'p999', 'max'), map(float, p)),
                tailRatio=float(p[3]/p[0]))


def shade(y, step):
    gz, gx = np.gradient(y, step)
    light = np.array([-.5, -.3, 1.0])  # x,z,up: TerrainPreview.shadeFactors
    light /= np.linalg.norm(light)
    dot = (-gx*light[0] - gz*light[1] + light[2])/np.sqrt(1+gx*gx+gz*gz)
    return .65 + .35*np.maximum(0, dot)


def light_check():
    z, x = np.mgrid[-40:41, -40:41]
    cone = np.maximum(0, 30-np.hypot(x, z))
    upper = (x < -5) & (z < -5) & (np.hypot(x, z) < 25)
    lower = (x > 5) & (z > 5) & (np.hypot(x, z) < 25)
    c, p = shade(cone, 1), shade(-cone, 1)
    assert c[upper].mean() > c[lower].mean()
    assert p[upper].mean() < p[lower].mean()


def maps(arrays, meta, name, neutral=False):
    step, n = meta['stepWu']*2, meta['size']
    origin = np.array(meta['originWu'])*2
    span = meta['spanWu']*2
    extent = (origin[0]-step/2, origin[0]+span+step/2,
              origin[1]+span+step/2, origin[1]-step/2)
    palette = np.loadtxt(DATA/'project-palette.csv', delimiter=',', skiprows=1)
    cmap = ListedColormap(palette[:, 1:]/255)
    fig, axes = plt.subplots(1, 2, figsize=(16, 8), dpi=140)
    for ax, key in zip(axes, ('A', 'B')):
        a = arrays[key]
        e = meta['groundE']+meta['eSpan']*a
        illum = shade(236.44*meta['eSpan']*a, step)
        if neutral:
            rgb = np.repeat(illum[..., None], 3, axis=2)
        else:
            rgb = cmap(Normalize(-.35, 1.2, clip=True)(e))[..., :3]*illum[..., None]
        ax.imshow(rgb, extent=extent, interpolation='nearest', origin='upper')
        ax.axhline((origin[1]+span/2), color='#cc3377', lw=.5, alpha=.6)
        ax.axvline((origin[0]+span/2), color='#cc3377', lw=.5, alpha=.6)
        ax.set_title(key + (' | production old erosion branch x 0.65' if key == 'A' else ' | Fast branch x 0.65; original domain warp'))
        ax.set(xlabel='world x (blocks = 2 wu)', ylabel='world z (blocks = 2 wu)')
    if not neutral:
        fig.colorbar(plt.cm.ScalarMappable(norm=Normalize(-.35, 1.2), cmap=cmap), ax=axes,
                     fraction=.02, pad=.025, label='e = 0.02 + 1.5 h (fixed, NOT per-arm normalized)')
    fig.suptitle('Isolated M1 TYPE | same window / vertical scale / NW light | CPU only', fontsize=15)
    fig.text(.10, .025, '769 x 769 native samples, nearest display; 3 ridge scales. Y proxy = 236.44 x 1.5 h; HS=2; vertical exaggeration=1.\n'
             'No HeightCurve/clamp: out-of-range B is retained. Color saturation is reported, not hidden. Pink cuts = profiles. Not a Minecraft world render.', fontsize=10)
    fig.savefig(OUT/name, dpi=140)
    plt.close(fig)


def differences(arrays, meta):
    raw = arrays['raw-shell']
    changes = [arrays['A']-raw, arrays['B']-raw, arrays['B']-arrays['A']]
    titles = ['A - carrier (old erosion x .65)', 'B - carrier (Fast branch x .65)', 'B - A (replacement total)']
    lim = .4*236.44*meta['eSpan']  # fixed shell-unit delta limit, NOT derived from either image
    span = meta['spanWu']*2
    fig, axes = plt.subplots(1, 3, figsize=(19, 7), dpi=140)
    for ax, d, title in zip(axes, changes, titles):
        image = ax.imshow(d*236.44*meta['eSpan'], cmap='RdBu_r', vmin=-lim, vmax=lim,
                          extent=(0, span, span, 0), interpolation='nearest')
        ax.set(title=title, xlabel='local x (blocks)', ylabel='local z (blocks)')
    fig.colorbar(image, ax=axes, fraction=.02, label='signed delta Y proxy; common fixed +/-0.4 shell units')
    fig.suptitle('Signed differences | identical fixed symmetric limits; no data normalization')
    fig.savefig(OUT/'differences.png')
    plt.close(fig)
    # Unit erosion shows small-scale geometry without distorting B's actual amplitude.
    fig, ax = plt.subplots(figsize=(10, 9), dpi=140)
    im = ax.imshow(arrays['erosion-unit'], vmin=-1, vmax=1, cmap='RdBu_r', interpolation='nearest',
                   extent=(0, span, span, 0))
    ax.set(title='Fast erosion E only [-1,1] | inspect stars / lines / rings', xlabel='local x (blocks)', ylabel='local z (blocks)')
    fig.colorbar(im, ax=ax, label='E on common output grid; B uses bilinear(E) at domain(p) x .25 x .65 x .7')
    fig.savefig(OUT/'erosion-unit.png')
    plt.close(fig)
    # Fixed center crop, NOT selected for visual quality and no new normalization.
    n = meta['size']
    lo, hi = n//2-96, n//2+96
    fig, ax = plt.subplots(figsize=(9, 8), dpi=140)
    im = ax.imshow(arrays['erosion-unit'][lo:hi,lo:hi], vmin=-1, vmax=1,
                   cmap='RdBu_r', interpolation='nearest',
                   extent=(lo*meta['stepWu']*2,hi*meta['stepWu']*2,
                           hi*meta['stepWu']*2,lo*meta['stepWu']*2))
    ax.set(title='Fixed center crop: E [-1,1], 192 x 192 native samples',
           xlabel='local x (blocks)', ylabel='local z (blocks)')
    fig.colorbar(im, ax=ax)
    fig.savefig(OUT/'erosion-center-zoom.png')
    plt.close(fig)
    palette = np.loadtxt(DATA/'project-palette.csv', delimiter=',', skiprows=1)
    cmap = ListedColormap(palette[:, 1:]/255)
    extent = (lo*meta['stepWu']*2,hi*meta['stepWu']*2,
              hi*meta['stepWu']*2,lo*meta['stepWu']*2)
    fig, axes = plt.subplots(1, 3, figsize=(18, 7), dpi=140)
    for ax, key in zip(axes[:2], ('A', 'B')):
        h = arrays[key]
        e = meta['groundE']+meta['eSpan']*h
        rgb = cmap(Normalize(-.35,1.2,clip=True)(e))[...,:3]*shade(
            236.44*meta['eSpan']*h,meta['stepWu']*2)[...,None]
        ax.imshow(rgb[lo:hi,lo:hi], extent=extent, interpolation='nearest')
        ax.set_title(key+' | same color / light / scale')
    delta = (arrays['B']-arrays['raw-shell'])[lo:hi,lo:hi]
    im = axes[2].imshow(delta,vmin=-meta['amplitudeShell'],vmax=meta['amplitudeShell'],
                        cmap='RdBu_r',extent=extent,interpolation='nearest')
    axes[2].set_title('Actual B - carrier (after warp + .65)')
    for ax in axes:
        ax.set(xlabel='local x (blocks)',ylabel='local z (blocks)')
    fig.colorbar(im,ax=axes,fraction=.018,label='shell delta h; fixed +/- .65*.25*.7')
    fig.suptitle('Fixed center crop | actual A/B and applied Fast branch detail')
    fig.savefig(OUT/'AB-center-zoom.png')
    plt.close(fig)


def profiles(arrays, meta):
    mid = meta['size']//2
    x = np.arange(meta['size'])*meta['stepWu']*2
    fig, axes = plt.subplots(2, 2, figsize=(17, 9), dpi=140, sharex=True)
    for col in range(2):
        for key, color in [('A', '#205f9a'), ('B', '#d26426'), ('raw-shell', '#666666')]:
            a = arrays[key]
            cut = a[mid, :] if col == 0 else a[:, mid]
            for row in range(2):
                axes[row, col].plot(x, cut, label=key, color=color, lw=.9)
        axes[0, col].set_ylim(-.15, .85)
        axes[1, col].set_ylim(-.1, .8)
        axes[0, col].set_title(('x cut at middle z' if col == 0 else 'z cut at middle x')+' | common Y axes')
        for row in range(2):
            axes[row, col].set(xlabel='distance (blocks)', ylabel='type shell h (reference units)')
            axes[row, col].grid(alpha=.2)
            axes[row, col].legend()
    fig.suptitle('Same-position height profiles | upper: full amplitude | lower: fixed A-scale zoom (B may leave frame)')
    fig.text(.1, .015, 'No mean subtraction / normalization; fixed axes in both columns. Profile screen aspect is for readability, not 1:1 physical elevation.')
    fig.tight_layout(rect=(0, .04, 1, .96))
    fig.savefig(OUT/'profiles.png')
    plt.close(fig)
    cuts = np.column_stack([x]+[arrays[k][mid, :] for k in ('A','B','raw-shell')]
                           +[arrays[k][:, mid] for k in ('A','B','raw-shell')])
    np.savetxt(OUT/'profiles.csv', cuts, delimiter=',', header='distanceBlocks,A_x,B_x,raw_x,A_z,B_z,raw_z', comments='')


def scale_comparison(arrays,meta,previous):
    n=meta['size']; span=meta['spanWu']*2
    old=np.fromfile(previous/'B.f64be',dtype='>f8').astype(float).reshape(n,n)
    palette=np.loadtxt(DATA/'project-palette.csv',delimiter=',',skiprows=1)
    cmap=ListedColormap(palette[:,1:]/255)
    def rgb(a):
        return cmap(Normalize(-.35,1.2,clip=True)(meta['groundE']+meta['eSpan']*a))[...,:3]*shade(
            a*236.44*meta['eSpan'],meta['stepWu']*2)[...,None]
    for filename,crop in [('scale-comparison.png',slice(None)),('scale-center-zoom.png',slice(n//2-96,n//2+96))]:
        fig,axes=plt.subplots(1,3,figsize=(19,7),dpi=140)
        start,stop=(0,n) if crop.start is None else (crop.start,crop.stop)
        ext=(start*meta['stepWu']*2,stop*meta['stepWu']*2,stop*meta['stepWu']*2,start*meta['stepWu']*2)
        for ax,a,title in zip(axes,[arrays['A'],old,arrays['B']],['A unchanged','B prior step 610/256 wu','B scale-only step 610/(1165/9) wu']):
            ax.imshow(rgb(a)[crop,crop],extent=ext,interpolation='nearest')
            ax.set(title=title,xlabel='local x (blocks)',ylabel='local z (blocks)')
        fig.suptitle('Scale-only experiment | same world grid / palette / light / vertical ratio; no parameter compensation')
        fig.savefig(OUT/filename); plt.close(fig)
    fig,axes=plt.subplots(2,1,figsize=(16,8),dpi=140,sharex=True,sharey=True)
    xs=np.arange(n)*meta['stepWu']*2
    for ax,col in zip(axes,[False,True]):
        for a,name in [(arrays['A'],'A'),(old,'B prior'),(arrays['B'],'B new scale')]:
            ax.plot(xs,a[:,n//2] if col else a[n//2,:],label=name,lw=.9)
        ax.set(ylim=(-.15,.85),xlabel='distance (blocks)',ylabel='shell h',title='z cut' if col else 'x cut')
        ax.legend(); ax.grid(alpha=.2)
    fig.tight_layout(); fig.savefig(OUT/'scale-profiles.png'); plt.close(fig)


def main():
    before = locked_files()
    save('locked-before.json', before)
    classes = OUT/'classes'
    classes.mkdir()
    probe = 'src/diagnostics/java/com/geogenesis/worldgen/terrain/morph/FastErosionInTypeProbe.java'
    standalone = 'src/diagnostics/java/com/geogenesis/standalone/'
    run(['javac', '-encoding', 'UTF-8', '-proc:none', '-implicit:class', '-d', str(classes),
         '-sourcepath', os.pathsep.join(['src/main/java', 'src/diagnostics/java']),
         probe, standalone+'FastErosionFilter.java', standalone+'FastErosionOriginalDemo.java'])
    run(['java', '-Xmx2g', '-cp', str(classes), 'com.geogenesis.worldgen.terrain.morph.FastErosionInTypeProbe', str(OUT/'control-rerun')])
    run(['java', '-Xmx2g', '-cp', str(classes), 'com.geogenesis.worldgen.terrain.morph.FastErosionInTypeProbe', str(DATA), 'scale'])
    previous = ROOT/'build/fast-erosion-replacement-20261103-final/data'
    for name in ('A.f64be','B.f64be','raw-shell.f64be'):
        assert sha(previous/name) == sha(OUT/'control-rerun'/name), 'Control not reproduced: '+name
    assert sha(previous/'A.f64be') == sha(DATA/'A.f64be'), 'A changed during scale experiment'
    # Recheck the approved reference with the freshly built untouched core.
    reference = ROOT/'build/fast-erosion-refactor3-20261103/run/height.rgba'
    run(['java', '-Xmx1g', '-cp', str(classes), 'com.geogenesis.standalone.FastErosionOriginalDemo', str(OUT/'reference-rerun')])
    assert sha(reference) == sha(OUT/'reference-rerun/height.rgba'), 'Approved reference mismatch'
    meta = json.loads((DATA/'sampling.json').read_text())
    n = meta['size']
    arrays = {p.stem: np.fromfile(p, dtype='>f8').astype(float).reshape(n,n)
              for p in DATA.glob('*.f64be') if p.stem != 'prototype-input-slope' and not p.stem.startswith('native-')}
    assert all(np.isfinite(a).all() for a in arrays.values())
    raw = arrays['raw-shell']
    applied = arrays['B'] - raw
    assert abs(applied).max() <= meta['amplitudeShell'] + 1e-12
    assert meta['amplitudeRaw'] == .25 and meta['blendWeight'] == .65
    prototype_slope = np.fromfile(DATA/'prototype-input-slope.f64be', dtype='>f8').astype(float)
    assert np.median(prototype_slope) == meta['prototypeMedian']
    assert np.median(arrays['input-slope']) == meta['typeMedian']
    ps = prototype_slope.astype(np.float32)
    pe = np.where(ps < np.float32(.1),np.float32(.5)*ps*ps/np.float32(.1),ps-np.float32(.05))
    pm = np.float32(1)-np.clip(np.float32(1)-pe,0,1)**2
    stats = {'prototypeInitialMaskFullFraction':float((pm>=1).mean()),
             'levelsShell': {k:level(arrays[k]) for k in ('A','B','raw-shell')}, 
             'slopeShellPerWu': {k:slopes(arrays[k], meta['stepWu']) for k in ('A','B','raw-shell')},
             'slopesYperBlock': {k:slopes(arrays[k]*236.44*meta['eSpan'], meta['stepWu']*2) for k in ('A','B','raw-shell')},
             'vsRaw': {k:difference(arrays[k],raw) for k in ('A','B')},
             'BminusA':difference(arrays['B'],arrays['A']),
             'colorClippedSamples':{k:int(((meta['groundE']+meta['eSpan']*arrays[k]<-.35)
                                          | (meta['groundE']+meta['eSpan']*arrays[k]>1.2)).sum()) for k in ('A','B')},
             'colorSaturatedFraction':{key:float(((meta['groundE']+meta['eSpan']*arrays[key]<-.35)
                                          | (meta['groundE']+meta['eSpan']*arrays[key]>1.2)).mean()) for key in ('A','B')},
             'inputSlope':level(arrays['input-slope']),
             'prototypeInputSlope':level(prototype_slope),
             'initialSlopeMask':level(arrays['initial-slope-mask']),
             'initialSlopeMaskFullFraction':float((arrays['initial-slope-mask']>=1).mean()),
             'initialSlopeMaskZeroFraction':float((arrays['initial-slope-mask']==0).mean()),
             'inputLaplacian':level(arrays['input-lap']),
             'inputLapSaturatedFraction':float((abs(arrays['input-lap'])>=1).mean()),
             'rawBranchIncrementStdOverRawStd':float((.25*arrays['erosion-unit']).std()/arrays['raw-signal'].std()),
             'appliedIncrementStdOverCarrierStd':float(applied.std()/raw.std()),
             'masksCaveat':'Initial first-octave slope mask only; later overwritten Gabor-feedback octave masks not instrumented',
             'erosionUnit':level(arrays['erosion-unit'])}
    native_s = np.fromfile(DATA/'native-input-slope.f64be',dtype='>f8').astype(np.float32)
    native_l = np.fromfile(DATA/'native-input-lap.f64be',dtype='>f8').astype(float)
    ns = np.where(native_s < np.float32(.1),np.float32(.5)*native_s*native_s/np.float32(.1),native_s-np.float32(.05))
    nm = np.float32(1)-np.clip(np.float32(1)-ns,0,1)**2
    stats['nativeGrid'] = {'inputSlope':level(native_s), 'medianVsPrototype':float(np.median(native_s)/meta['prototypeMedian']),
                           'initialMaskFullFraction':float((nm>=1).mean()),
                           'lapSaturatedFraction':float((abs(native_l)>=1).mean())}
    old_stats = json.loads((previous.parent/'statistics.json').read_text())
    stats['previous'] = {key:old_stats[key] for key in ('inputSlope','initialSlopeMaskFullFraction','inputLapSaturatedFraction','slopeShellPerWu','appliedIncrementStdOverCarrierStd')}
    stats['proposedNotAppliedCompensation'] = {'stepRatio':meta['erosionStepWu']/meta['stepWu'],
        'steepnessScaleToPreservePriorLocalSlope':2*meta['stepWu']/meta['erosionStepWu'],
        'laplacianDivToPreservePriorLocalCurvature':25*(meta['erosionStepWu']/meta['stepWu'])**2,
        'note':'Local Taylor approximation; not run, cannot predict exact mask percentages; spectrum/phase changes remain.'}
    save('statistics.json', stats)
    light_check()
    maps(arrays,meta,'AB.png')
    maps(arrays,meta,'AB-hillshade.png',neutral=True)
    differences(arrays,meta)
    profiles(arrays,meta)
    scale_comparison(arrays,meta,previous)
    after = locked_files()
    assert before == after, 'Locked production or baseline files changed'
    sources = {str(p.relative_to(ROOT)):sha(p) for p in (ROOT/'src/main/java/com/geogenesis/worldgen/terrain/morph').glob('*.java')}
    sources.update({str(Path(probe)):sha(ROOT/probe), str(Path(__file__).resolve().relative_to(ROOT)):sha(Path(__file__))})
    save('manifest.json', {'purpose':'Isolated M1 type only; NOT integrated into production', 'sampling':meta,
         'commands':COMMANDS, 'javaProperties':'No -D flags, no geogenesis overrides; defaults inherited; no Forge config used for standalone type',
         'arrays':'data/*.f64be: BE float64 row=z col=x; height is type shell h, NOT MC Y',
         'render':{'palette':'actual GeoPalette S_ELEVATION_E + production ColorMap Lab; exported LUT 4097 samples',
                   'fixedERange':[-.35,1.2], 'lightXzUp':[-.5,-.3,1],
                   'shade':'TerrainPreview 0.65+0.35*max(dot,0), no water mask on isolated type',
                   'horizontalBlocksPerWu':2,'linearYProxyPerShellH':236.44*meta['eSpan'], 'verticalExaggeration':1,
                   'perArmNormalization':False, 'notGameHeight':'Linear Y proxy only; HeightCurve/clamp intentionally excluded'},
         'reference':{'path':str(reference), 'rgbaSha256':sha(reference),'freshRerunExact':True},
         'sourceHashes':sources, 'environment':{'python':sys.version,'numpy':np.__version__,'matplotlib':matplotlib.__version__},
         'outputs':{str(p.relative_to(OUT)):sha(p) for p in OUT.rglob('*') if p.is_file() and 'classes' not in p.parts}})
    (OUT/'verification.txt').write_text('PASS javac fresh source closure (no stale classes)\n'
          'PASS all A samples bit-identical to production M1 onehot\n'
          'PASS fresh control rerun full A/B/carrier arrays equal previous locked baseline\n'
          'PASS treatment A full array equal previous baseline; fixed output grid and world window\n'
          'PASS only erosionStep treatment; ORIGINAL and mapping unchanged\n'
          'PASS actual kernel erosion node replacement, same .65 blend and warp, amplitude bound\n'
          'PASS finite arrays, reverse query order, measured input median\n'
          'PASS approved prototype full RGBA SHA256 reproduced exactly\nPASS cone raised / pit hollow with actual shade function\n'
          'PASS all %d locked main/prototype/baseline files unchanged\n'
          'NOT VERIFIED: other seeds, transitions, Minecraft placement, infinite-world wrap, hydrology, runtime performance\n' % len(before), encoding='utf-8')
    print(json.dumps({'sampling':meta,'stats':stats},indent=2))
    print('DONE',OUT)


if __name__ == '__main__':
    main()
