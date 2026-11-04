"""Four frozen-raster arms using the actual, independently compiled ErosionEngine.
Run: python -B src/diagnostics/particle-preeorder.py [--size 385]
Only new diagnostics and build/particle-preeorder-20261104 are written.
"""
import sys
sys.dont_write_bytecode = True
import argparse, csv, hashlib, json, os, shutil, subprocess, time
from pathlib import Path
import numpy as np
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.collections import LineCollection

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'build/particle-preeorder-20261104'
REF = ROOT / 'build/strong-window-erosion-20261104-final/data'
PRE = ROOT / 'build/trunk-network-20261104/extended-01'
Y = 354.66


def sha(p):
    return hashlib.sha256(p.read_bytes()).hexdigest()


def save(name, data):
    (OUT / name).write_text(json.dumps(data, indent=2, ensure_ascii=False, allow_nan=False), encoding='utf-8')


def protected():
    paths = set()
    for base in [ROOT/'src', ROOT/'run/config', REF.parent,
                 ROOT/'build/trunk-network-20261104',
                 ROOT/'build/fast-erosion-scale-only-20261103-final']:
        paths.update(p for p in base.rglob('*') if p.is_file())
    tracked = subprocess.check_output(['git', 'ls-files', '-z'], cwd=ROOT.parent).decode().split('\0')
    paths.update(ROOT.parent/p for p in tracked if p and (ROOT.parent/p).is_file())
    return {str(p): sha(p) for p in sorted(paths)}


def compile_probe():
    jars = (ROOT/'build/terrain-upgrade/baseline/classpath-jars.txt').read_text().strip()
    cp = str(ROOT/'build/classes/java/main') + os.pathsep + jars
    classes = OUT/'probe-classes'
    classes.mkdir(exist_ok=True)
    erosion = ROOT/'src/diagnostics/java/com/geogenesis/worldgen/erosion'
    opts = ['--release', '17', '-encoding', 'UTF-8', '-g', '-nowarn', '-proc:none', '-implicit:none', '-classpath', cp]
    commands = [ ['javac', *opts, '-d', str(classes), str(erosion/'ErosionScratchProbe.java'),
                 str(erosion/'ParticleErosionProbe.java')],
                ['javac', *opts, '-d', str(OUT/'engine-classes'),
                 str(ROOT/'src/main/java/com/geogenesis/worldgen/erosion/ErosionEngine.java')] ]
    for i, cmd in enumerate(commands):
        result = subprocess.run(cmd, cwd=OUT, capture_output=True, text=True)
        (OUT/f'compile-{i}.log').write_text(result.stdout+result.stderr, encoding='utf-8')
        result.check_returncode()
    save('compile.json', {'commands': commands, 'classpath': cp,
         'convention': 'ErosionScratchProbe.Compile.engine javac flags; existing main classes + existing classpath-jars.txt; no Gradle, no dependency added',
         'python': 'existing numpy/matplotlib only', 'java': subprocess.check_output(['java','-version'],stderr=subprocess.STDOUT).decode()})
    return str(classes) + os.pathsep + cp


def run_arm(cp, name, source, s):
    config = OUT/f'{name}-config.toml'
    shutil.copyfile(OUT/'config-input.toml', config)
    cmd = ['java', '-Xmx3g', '-Dlog4j.configurationFile='+str(OUT/'log4j2.xml'), '-cp', cp,
           'com.geogenesis.worldgen.erosion.ParticleErosionProbe', str(source), str(OUT/f'{name}.f64be'),
           str(s['size']), *map(str,s['originWu']), str(s['stepWu']), '2', str(config)]
    start = time.perf_counter()
    with (OUT/f'{name}.log').open('w',encoding='utf-8') as log:
        subprocess.run(cmd, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, check=True, timeout=1200)
    wall = time.perf_counter()-start
    text = (OUT/f'{name}.f64be.provenance.txt').read_text()
    seconds = float(text.split('engineSeconds=')[1].splitlines()[0])
    print(name, 'engine', seconds, 'wall', wall, flush=True)
    return {'engineSeconds': seconds, 'processWallSeconds': wall, 'command': cmd}


def magnitude(a):
    v = np.abs(a)*Y
    return {'maxY': float(v.max()), 'P90Y': float(np.percentile(v,90)),
            'P50Y': float(np.median(v)), 'rmsY': float(np.sqrt(np.mean(v*v)))}


def change(a):
    return {**magnitude(a), 'meanY': float(a.mean()*Y),
            'cutCoverage_gt0.01Y': float(np.mean(a*Y < -.01)),
            'raiseCoverage_gt0.01Y': float(np.mean(a*Y > .01)),
            'cutCoverage_gt0.1Y': float(np.mean(a*Y < -.1)),
            'raiseCoverage_gt0.1Y': float(np.mean(a*Y > .1))}


def corr(a,b):
    return float(np.corrcoef(a.ravel(),b.ravel())[0,1])


def lines():
    routes = {}
    with (REF/'routes.csv').open() as f:
        for r in csv.DictReader(f):
            routes.setdefault(r['trace'], []).append([float(r['x']),float(r['z'])])
    ext = json.loads((PRE/'extended-routes.json').read_text())
    result = [np.array(routes[r['originalId']] + r['extension']) for r in ext['routes']]
    net = json.loads((PRE/'connected-network.json').read_text())
    result += [np.array(c['vertices']) for c in net['connectors']]
    return result


def render(arms, s, skeleton, meta):
    palette = np.genfromtxt(ROOT/'build/fast-erosion-scale-only-20261103-final/data/project-palette.csv',delimiter=',',skip_header=1)
    light = np.array([-.5,-.3,1.]); light /= np.linalg.norm(light)
    images = []
    for h in arms.values():
        gz,gx = np.gradient(h*Y, s['stepWu']*2)
        normal = np.stack([-gx,-gz,np.ones_like(h)],axis=-1)
        normal /= np.linalg.norm(normal,axis=-1)[...,None]
        shade = .65+.35*np.maximum(0,normal@light)
        e = np.clip(.02+1.5*h,-.35,1.2)
        rgb = np.stack([np.interp(e,palette[:,0],palette[:,i])/255 for i in (1,2,3)],axis=-1)
        images.append(np.clip(rgb*shade[...,None],0,1))
    ox,oz = s['originWu']; span = (s['size']-1)*s['stepWu']
    extent = [ox,ox+span,oz+span,oz]
    centers = meta['closeupCenters'] if s['size']==769 else [[ox+span*.35,oz+span*.35],[ox+span*.65,oz+span*.65]]
    rows = [s['size']//4,s['size']//2,3*s['size']//4]
    def panel(name, center=None, overlay=False):
        fig,axes = plt.subplots(1,4,figsize=(20,5.5),layout='constrained')
        for ax,img,label in zip(axes,images,arms):
            ax.imshow(img,extent=extent,origin='upper',interpolation='nearest')
            if overlay:
                ax.add_collection(LineCollection(skeleton,colors='#ff594f',linewidths=.32,alpha=.65))
            if center:
                radius = 125
                ax.set_xlim(center[0]-radius,center[0]+radius)
                ax.set_ylim(center[1]+radius,center[1]-radius)
            else:
                ax.set_xlim(ox,ox+span);ax.set_ylim(oz+span,oz)
                for row in rows: ax.axhline(oz+row*s['stepWu'],color='white',lw=.35,alpha=.6)
            ax.set_title(label);ax.set_aspect('equal');ax.tick_params(labelsize=7)
        fig.suptitle('Fixed palette/light/height scale | O / A / D / B | raw particle engine, edge-clamp pad')
        fig.savefig(OUT/name,dpi=160);plt.close(fig)
    panel('full-4arms.png');panel('full-4arms-lines.png',overlay=True)
    for i,c in enumerate(centers,1):
        panel(f'closeup-{i}.png',c);panel(f'closeup-{i}-lines.png',c,True)
    fig,axes = plt.subplots(3,2,figsize=(16,10),layout='constrained')
    profiles = []
    for row,(ax,delta) in zip(rows,axes):
        x = ox+np.arange(s['size'])*s['stepWu']
        for (label,h),color in zip(arms.items(),['black','#2388d1','#cc8b00','#db2d45']):
            ax.plot(x,h[row]*Y,label=label,color=color,lw=.9)
        o,a,d,b = arms.values()
        delta.plot(x,(a-o)[row]*Y,label='A-O');delta.plot(x,(b-d)[row]*Y,label='B-D')
        delta.plot(x,(b-a)[row]*Y,label='B-A',alpha=.7)
        ax.set_title(f'row={row}, z={oz+row*s["stepWu"]:.4f}wu');ax.set_ylabel('H * 354.66 (Yproxy)')
        ax.legend(fontsize=7);delta.legend(fontsize=7);delta.set_ylabel('delta Yproxy')
        profiles.append({'row':row,'zWu':oz+row*s['stepWu'],'A-O':change((a-o)[row]),
                         'B-D':change((b-d)[row]),'B-A':magnitude((b-a)[row])})
    fig.savefig(OUT/'sections.png',dpi=160);plt.close(fig)
    save('sections.json',profiles)
    np.savez(OUT/'sections-data.npz',xWu=x,rows=rows,**{k[0]:v[rows] for k,v in arms.items()})
    o,a,d,b = arms.values()
    fig,axes=plt.subplots(1,3,figsize=(17,6),layout='constrained')
    for ax,v,title in zip(axes,[(a-o)*Y,(b-d)*Y,((b-d)-(a-o))*Y],['A-O','B-D','(B-D)-(A-O)']):
        im=ax.imshow(v,vmin=-6,vmax=6,cmap='RdBu_r',extent=extent);ax.set_title(title+' [Yproxy], fixed +/-6')
    fig.colorbar(im,ax=axes,shrink=.7);fig.savefig(OUT/'deltas.png',dpi=160);plt.close(fig)
    return profiles


def main():
    parser=argparse.ArgumentParser();parser.add_argument('--size',type=int,choices=[385,769],default=769)
    n=parser.parse_args().size
    OUT.mkdir(exist_ok=True)
    if (OUT/'metrics.json').exists():
        raise RuntimeError('Completed experiment already exists; do not overwrite evidence')
    before=protected();save('hashes-before.json',before)
    meta=json.loads((PRE/'meta.json').read_text());s=dict(meta['sampling']);s['size']=n
    s.update(scope='full frozen window' if n==769 else 'fixed NW 385 subwindow ONLY',seedCtor=12345,
             engineIntegerOrigin=[round(v) for v in s['originWu']],padding='9 edge-clamped',
             scaleCaveat='stepWu is NOT accepted by engine: grid-space comparison, not production-scale equivalence')
    save('sampling.json',s)
    shutil.copyfile(ROOT/'run/config/geogenesis-common.toml',OUT/'config-input.toml')
    (OUT/'log4j2.xml').write_text('<Configuration status="OFF"><Appenders><Console name="console" target="SYSTEM_ERR"><PatternLayout pattern="%m%n"/></Console></Appenders><Loggers><Root level="warn"><AppenderRef ref="console"/></Root></Loggers></Configuration>')
    o=np.fromfile(REF/'carrier-height.f64be',dtype='>f8').reshape(769,769)[:n,:n].astype(float)
    d=np.fromfile(PRE/'candidate-height.f64be',dtype='>f8').reshape(769,769)[:n,:n].astype(float)
    with np.load(PRE/'fields.npz') as fields:
        assert np.array_equal(o,fields['H0'][:n,:n])
    for name,h in [('O',o),('D',d)]:h.astype('>f8').tofile(OUT/f'{name}.f64be')
    cp=compile_probe()
    timings={name:run_arm(cp,name,OUT/f'{source}.f64be',s) for name,source in [('A','O'),('B','D')]}
    a=np.fromfile(OUT/'A.f64be',dtype='>f8').reshape(n,n)
    b=np.fromfile(OUT/'B.f64be',dtype='>f8').reshape(n,n)
    da=a-o;db=b-d;p=d-o;post=b-a
    qa=np.fromfile(OUT/'A.f64be.discharge.f64be',dtype='>f8').reshape(n,n)
    qb=np.fromfile(OUT/'B.f64be.discharge.f64be',dtype='>f8').reshape(n,n)
    cut=p*Y < -.1
    stats={'timings':timings,'A-O':change(da),'B-D':change(db),'B-A':magnitude(post),
           'D-O':magnitude(p),'particleResponseDifference':magnitude(db-da),
           'correlations':{'particleDeltas':corr(da,db),'pre_vs_post':corr(p,post),
                           'discharge':corr(qa,qb),'heightA_B':corr(a,b)},
           'preRetentionRegression':float(np.sum(p*post)/np.sum(p*p)),
           'preCutArea':float(cut.mean()),
           'withinPreCutArea':{'pre_vs_post':corr(p[cut],post[cut]),'B-A':magnitude(post[cut]),
                              'A-O':change(da[cut]),'B-D':change(db[cut])},
           'discharge':{'A_coverage_gt0':float(np.mean(qa>0)),'B_coverage_gt0':float(np.mean(qb>0)),
                        'meanA':float(qa.mean()),'meanB':float(qb.mean()),
                        'changedCoverage_gt0.1':float(np.mean(np.abs(qb-qa)>.1)),
                        'highFlow_gt2_jaccard':float(np.sum((qa>2)&(qb>2))/np.sum((qa>2)|(qb>2)))},
           'floatConversionMaxY':float(max(np.max(np.abs(o-o.astype('f4'))),np.max(np.abs(d-d.astype('f4'))))*Y),
           'interiorExclude40':{'A-O':change(da[40:-40,40:-40]),'B-D':change(db[40:-40,40:-40]),
                                 'pre_vs_post':corr(p[40:-40,40:-40],post[40:-40,40:-40])}}
    save('metrics.json',stats)
    profiles=render({'O: H0':o,'A: H0 + particles':a,'D: pre only':d,'B: pre + particles':b},s,lines(),meta)
    after=protected();save('hashes-after.json',after)
    changed=[k for k,v in before.items() if after.get(k)!=v]
    save('validation.json',{'protectedFiles':len(before),'changed':changed,'allOutputsFinite':bool(np.isfinite(a).all() and np.isfinite(b).all()),'size':n,'productionWiring':False,'gitCommit':False})
    assert not changed
    report=('Offline raw ErosionEngine four-arm experiment\n'+json.dumps(stats,indent=2)+'\n\n'
            'Units: H differences * 354.66 = Yproxy. Percentiles over ALL pixels; cut/raise threshold explicitly stated.\n'
            'Pre retention compares B-A with D-O; particle response compares B-D with A-O (not the same quantity).\n'
            'Discharge is accumulated passage intensity, not an exact spawn-count or trajectory log.\n'
            'Boundary: pad9 clamped; production uses neighbours. Interior40 stats included.\n'
            'Engine assumes one cell per internal unit; step2.3828125 is render/metadata only. No production-scale or natural-quality certification.\n'
            'Only current ErosionEngine source independently compiled; no main edit; loaders reused from ErosionScratchProbe.\n'
            'Three sections: '+json.dumps(profiles,indent=2)+'\n')
    (OUT/'report.txt').write_text(report,encoding='utf-8')
    print(json.dumps(stats,indent=2),flush=True)


if __name__=='__main__':main()
