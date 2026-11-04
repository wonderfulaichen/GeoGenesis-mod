"""Frozen full-window half-depth + minimal chain deposition; writes v2 only.
python -B src/diagnostics/particle-preeorder-v2.py
"""
import sys
sys.dont_write_bytecode = True
import csv, hashlib, importlib.util, json, os, shutil, subprocess, time
from pathlib import Path
from collections import defaultdict
import numpy as np
from scipy.ndimage import gaussian_filter, map_coordinates
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.collections import LineCollection

ROOT = Path(__file__).resolve().parents[2]
OLD = ROOT/'build/particle-preeorder-20261104'
OUT = OLD/'v2'
PRE = ROOT/'build/trunk-network-20261104/extended-01'
REF = ROOT/'build/strong-window-erosion-20261104-final/data'
Y = 354.66


def load_module(name, path):
    spec = importlib.util.spec_from_file_location(name, path)
    module = importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
    return module


base = load_module('particle_v1_readonly', ROOT/'src/diagnostics/particle-preeorder.py')


def save(name, data):
    (OUT/name).write_text(json.dumps(data, indent=2, ensure_ascii=False, allow_nan=False,
        default=lambda v:v.tolist() if isinstance(v,np.ndarray) else v.item()), encoding='utf-8')


def snapshot():
    result = base.protected()
    result.update({str(p):base.sha(p) for p in OLD.rglob('*') if p.is_file() and OUT not in p.parents})
    return result


def sample(field, points, sampling):
    xy=(points-sampling['originWu'])/sampling['stepWu']
    return map_coordinates(field,xy.T[::-1],order=1,mode='nearest',prefilter=False)


def chains():
    """Split original polylines at exact parent joins, then form maximal directed chains.
    Every segment/connector appears once; junctions end chains to avoid duplicated sediment.
    """
    helper=load_module('old_route_readonly', ROOT/'src/diagnostics/old-route-l0.py')
    original=helper.load_routes()
    ext=json.loads((PRE/'extended-routes.json').read_text())['routes']
    net=json.loads((PRE/'connected-network.json').read_text())
    routes=[]
    for r,e in zip(original,ext):
        p=np.vstack([r['p'],np.asarray(e['extension']).reshape(-1,2)])
        arc=np.r_[0,np.cumsum(np.linalg.norm(np.diff(p,axis=0),axis=1))]
        routes.append((p,arc))
    cuts=[{0.,float(r[1][-1])} for r in routes]
    for r in net['routes']:
        if r['parent']>=0: cuts[r['parent']].add(float(r['parentArcWu']))
    cuts=[sorted(c) for c in cuts]
    incoming=defaultdict(list);outgoing=defaultdict(list);edges=[]
    def edge(u,v,p):
        i=len(edges);edges.append((u,v,p));outgoing[u].append(i);incoming[v].append(i)
    for i,((p,arc),c) in enumerate(zip(routes,cuts)):
        for lo,hi in zip(c[:-1],c[1:]):
            ss=np.r_[lo,arc[(arc>lo)&(arc<hi)],hi]
            q=np.c_[np.interp(ss,arc,p[:,0]),np.interp(ss,arc,p[:,1])]
            edge((i,lo),(i,hi),q)
    for c in net['connectors']:
        i,j=c['child'],c['parent'];r=net['routes'][i]
        edge((i,float(routes[i][1][-1])),(j,float(r['parentArcWu'])),np.array(c['vertices']))
    used=set();result=[]
    starts=[i for i,(u,v,p) in enumerate(edges) if len(incoming[u])!=1 or len(outgoing[u])!=1]
    for start in starts:
        if start in used:continue
        i=start;parts=[]
        while i not in used:
            used.add(i);u,v,p=edges[i];parts.append(p if not parts else p[1:])
            if len(incoming[v])!=1 or len(outgoing[v])!=1:break
            i=outgoing[v][0]
        q=np.concatenate(parts);keep=np.r_[True,np.linalg.norm(np.diff(q,axis=0),axis=1)>1e-10]
        q=q[keep]
        if len(q)>1:result.append(q)
    assert len(used)==len(edges),(len(used),len(edges))
    return result,{'chainCount':len(result),'uniqueEdges':len(edges),'edgesVisitedOnce':len(used),
                   'rule':'maximal nonbranching directed chains split at confluences; no route/connector edits; capacity resets at chain starts'}


def splat(points, amounts, radii, sampling):
    n=sampling['size'];step=sampling['stepWu'];out=np.zeros((n,n))
    grid=(points-sampling['originWu'])/step
    for (x,z),a,r in zip(grid,amounts,radii):
        if a<=0:continue
        rp=r/step
        x0=max(0,int(np.ceil(x-rp)));x1=min(n-1,int(np.floor(x+rp)))
        z0=max(0,int(np.ceil(z-rp)));z1=min(n-1,int(np.floor(z+rp)))
        if x0>x1 or z0>z1:continue
        zz,xx=np.ogrid[z0:z1+1,x0:x1+1]
        t=np.clip(np.hypot(xx-x,zz-z)/rp,0,1)
        shape=1-t**3*(10-15*t+6*t*t)
        out[z0:z1+1,x0:x1+1]+=a*shape
    return out


def deposition(o,d,fields,s):
    chain_list,graph=chains();step=s['stepWu']
    gz,gx=np.gradient(gaussian_filter(d,2.5/step,mode='reflect'),step)
    slope=np.hypot(gx,gz);points=[];raw=[];slopes=[];progress=[];descending=[]
    for q in chain_list:
        arc=np.r_[0,np.cumsum(np.linalg.norm(np.diff(q,axis=0),axis=1))]
        count=max(2,int(np.ceil(arc[-1]))+1)
        ss=np.linspace(0,arc[-1],count)
        p=np.c_[np.interp(ss,arc,q[:,0]),np.interp(ss,arc,q[:,1])]
        sl=sample(slope,p,s);score=ss/arc[-1]
        cap=sl*(1+score)
        drop=np.r_[0,np.maximum(0,-np.diff(cap))]
        valid=((p>=s['originWu']) & (p<=np.array(s['originWu'])+(s['size']-1)*step)).all(axis=1)
        # Outside samples never contribute or establish an invented upstream capacity.
        valid[1:] &= ((p[:-1]>=s['originWu']) & (p[:-1]<=np.array(s['originWu'])+(s['size']-1)*step)).all(axis=1)
        points.append(p[valid]);raw.append(drop[valid]);slopes.append(sl[valid]);progress.append(score[valid])
        descending.append(np.r_[False,np.diff(sl)<0][valid])
    p=np.concatenate(points);raw=np.concatenate(raw);sl=np.concatenate(slopes);score=np.concatenate(progress)
    radii=sample(fields['Rwu'],p,s)
    unit=splat(p,raw,radii,s)
    cut=(o-d)*Y
    # A single reference-only analytic calibration, not per-image tuning or iterative fitting.
    # Full-window medians are zero, so explicitly calibrate nonzero support medians.
    k=.3*np.median(cut[cut>1e-6])/np.median(unit[unit>1e-6])
    amount=np.clip(k*raw,0,2.)
    deposit=np.clip(splat(p,amount,radii,s),0,2.)
    stats={'kY_per_HperWu':float(k),'coef':1.,'sigmaWu':2.5,'arcSampleStepMaxWu':1.,
           'calibration':'once on D1 reference, target nonzero deposition/cut median ratio0.30 BEFORE clamps; no visual fit/retry; same k whole window',
           'fullWindowMedianRatio':'undefined: cut and deposit full-window medians can be zero',
           'graph':graph,'chainSampleCount':len(p),'depositionSamples':int(np.sum(amount>0)),
           'coverage_gt0':float(np.mean(deposit>1e-10)),'coverage_gt0.01Y':float(np.mean(deposit>.01)),
           'coverage_gt0.1Y':float(np.mean(deposit>.1)),
           'allPixelsY':distribution(deposit),'nonzeroY':distribution(deposit[deposit>1e-6]),
           'cutNonzeroY':distribution(cut[cut>1e-6]),
           'actualNonzeroMedianRatio':float(np.median(deposit[deposit>1e-6])/np.median(cut[cut>1e-6])),
           'volumeRatioToCut':float(deposit.sum()/cut.sum()),
           'slope_deposit_corr_allPixels':base.corr(slope,deposit),
           'slope_deposit_corr_nonzero':base.corr(slope[deposit>1e-6],deposit[deposit>1e-6]),
           'sampleSlope_amount_corr':base.corr(sl,amount),
           'positiveAmountAtDecreasingSlopeFraction':float(np.mean(np.concatenate(descending)[amount>0])),
           'progress_amount_corr':base.corr(score,amount),
           'clippedPixelFraction':float(np.mean(deposit>=2)),
           'fillAboveH0Coverage':float(np.mean(deposit>cut+.01)),
           'cutBandFullyFilledFraction':float(np.mean((deposit[cut>.5]>=cut[cut>.5]))),
           'limitations':['no sediment budget or mass conservation','capacity resets at confluences; score is chain arc proxy not measured discharge',
                         'same R support cannot generate a fan wider than the original cut','no dedicated downstream depth envelope']}
    np.savez_compressed(OUT/'deposition-fields.npz',depositY=deposit,slope=slope,points=p,
                        sampleAmountY=amount,score=score,rawCapDrop=raw,radiiWu=radii)
    save('deposition.json',stats)
    return d+deposit/Y,stats


def distribution(v):
    return {'P50':float(np.median(v)),'P90':float(np.percentile(v,90)),'max':float(np.max(v))}


def setup_engine():
    # Never call old ParticleErosionProbe: it recompiles into the previous output directory.
    source=(ROOT/'src/diagnostics/java/com/geogenesis/worldgen/erosion/ParticleErosionProbe.java').read_text()
    source=source.replace('ParticleErosionProbe','ParticleErosionV2Probe')
    source=source.replace('build/particle-preeorder-20261104"','build/particle-preeorder-20261104/v2"')
    lo=source.index('            String error = ErosionScratchProbe.Compile.engine(')
    hi=source.index('            var first =',lo)
    source=source[:lo]+source[hi:]
    java=OUT/'ParticleErosionV2Probe.java';java.write_text(source,encoding='utf-8')
    cp=str(OLD/'probe-classes')+os.pathsep+str(ROOT/'build/classes/java/main')+os.pathsep+(ROOT/'build/terrain-upgrade/baseline/classpath-jars.txt').read_text().strip()
    classes=OUT/'probe-classes';classes.mkdir()
    opts=['-encoding','UTF-8','-g','-nowarn','-proc:none','-implicit:none','-classpath',cp]
    commands=[['javac',*opts,'-d',str(classes),str(java)],
              ['javac',*opts,'-d',str(OUT/'engine-classes'),str(ROOT/'src/main/java/com/geogenesis/worldgen/erosion/ErosionEngine.java')]]
    for i,cmd in enumerate(commands):
        run=subprocess.run(cmd,cwd=OUT,capture_output=True,text=True)
        (OUT/f'compile-{i}.log').write_text(run.stdout+run.stderr,encoding='utf-8');run.check_returncode()
    save('compile.json',{'commands':commands,'convention':'existing ErosionScratchProbe.EngineRef dual child-first loaders; shared parent config; javac Compile.engine flags; no new dependencies'})
    return str(classes)+os.pathsep+cp


def run_arm(cp,name,source,s):
    shutil.copyfile(OUT/'config-input.toml',OUT/f'{name}-config.toml')
    cmd=['java','-Xmx3g','-Dlog4j.configurationFile='+str(OUT/'log4j2.xml'),'-cp',cp,
         'com.geogenesis.worldgen.erosion.ParticleErosionV2Probe',str(OUT/f'{source}.f64be'),str(OUT/f'{name}.f64be'),
         str(s['size']),*map(str,s['originWu']),str(s['stepWu']),'2',str(OUT/f'{name}-config.toml')]
    start=time.perf_counter()
    with (OUT/f'{name}.log').open('w') as log:subprocess.run(cmd,cwd=ROOT,stdout=log,stderr=subprocess.STDOUT,check=True,timeout=1200)
    text=(OUT/f'{name}.f64be.provenance.txt').read_text()
    result={'engineSeconds':float(text.split('engineSeconds=')[1].splitlines()[0]),'wallSeconds':time.perf_counter()-start}
    print(name,result,flush=True);return result


def band_metrics(o,arms,inputs,fields):
    inside=fields['distanceWu']<=fields['Rwu'];outside=fields['distanceWu']>fields['Rwu']+6
    result={'definition':'primary final incision=(H0-final)*Y; ratios conditional on specified band; also report fraction of entire window and particle-only increments',
            'insidePixels':int(inside.sum()),'outsidePixels':int(outside.sum()),'arms':{}}
    for name in ['A','B1','B2','B3']:
        cut=(o-arms[name])*Y;particle=(inputs[name]-arms[name])*Y
        entry={}
        for label,mask in [('inside',inside),('outside',outside)]:
            entry[label]={'cut_gt0.5Y_fractionOfBand':float(np.mean(cut[mask]>.5)),
                          'cut_gt0.5Y_fractionOfWindow':float(np.mean(mask&(cut>.5))),
                          'medianPositiveCutY':float(np.median(cut[mask&(cut>0)])),
                          'medianCutAbove0.5Y':float(np.median(cut[mask&(cut>.5)])),
                          'medianClampedCutAllBandY':float(np.median(np.maximum(0,cut[mask]))),
                          'particleOnlyCoverage_gt0.5Y':float(np.mean(particle[mask]>.5))}
        result['arms'][name]=entry
    save('band-metrics.json',result);return result


def rgb(h,s):
    pal=np.genfromtxt(ROOT/'build/fast-erosion-scale-only-20261103-final/data/project-palette.csv',delimiter=',',skip_header=1)
    gz,gx=np.gradient(h*Y,s['stepWu']*2)
    normal=np.stack([-gx,-gz,np.ones_like(h)],axis=-1);normal/=np.linalg.norm(normal,axis=-1)[...,None]
    light=np.array([-.5,-.3,1.]);light/=np.linalg.norm(light)
    shade=.65+.35*np.maximum(0,normal@light)
    e=np.clip(.02+1.5*h,-.35,1.2)
    return np.clip(np.stack([np.interp(e,pal[:,0],pal[:,i])/255 for i in (1,2,3)],axis=-1)*shade[...,None],0,1)


def plots(arms,fields,s,meta):
    step=s['stepWu'];n=s['size'];ox,oz=s['originWu']
    extent=[ox-step/2,ox+(n-.5)*step,oz+(n-.5)*step,oz-step/2]
    images={k:rgb(v,s) for k,v in arms.items()};skeleton=base.lines()
    def panel(keys,name,center=None,lines=False):
        fig,axes=plt.subplots(1,len(keys),figsize=(5*len(keys),5.5),layout='constrained')
        for ax,k in zip(axes,keys):
            ax.imshow(images[k],extent=extent,interpolation='nearest')
            if lines:ax.add_collection(LineCollection(skeleton,colors='#ff594f',linewidths=.3,alpha=.65))
            if center:ax.set(xlim=[center[0]-125,center[0]+125],ylim=[center[1]+125,center[1]-125])
            else:ax.set(xlim=extent[:2],ylim=extent[2:])
            ax.set_title(k);ax.set_aspect('equal');ax.tick_params(labelsize=7)
        fig.suptitle('Fixed project palette / light / scale | HS2 Y354.66 | offline raster only')
        fig.savefig(OUT/name,dpi=150);plt.close(fig)
    keys=['O','A','D2','B1','B2']
    panel(keys,'full-5arms.png');panel(keys,'full-5arms-lines.png',lines=True)
    panel(['O','D1','D3'],'full-deposition.png')
    panel(['A','B1','B3'],'full-deposition-particles.png')
    for i,center in enumerate(meta['closeupCenters'],1):
        for lines in [False,True]:
            suffix='-lines' if lines else ''
            panel(keys,f'closeup-{i}{suffix}.png',center,lines)
            panel(['O','D1','D3'],f'deposition-closeup-{i}{suffix}.png',center,lines)
            panel(['A','B1','B3'],f'deposition-particles-closeup-{i}{suffix}.png',center,lines)
    def delta_plot(values,labels,name,limit):
        fig,axes=plt.subplots(1,len(values),figsize=(6*len(values),6),layout='constrained')
        for ax,a,label in zip(axes,values,labels):
            im=ax.imshow(a,cmap='RdBu_r',vmin=-limit,vmax=limit,extent=extent);ax.set_title(label+' [Yproxy]')
        fig.colorbar(im,ax=axes,shrink=.7);fig.savefig(OUT/name,dpi=150);plt.close(fig)
    delta_plot([(arms['A']-arms['O'])*Y,(arms['B1']-arms['D1'])*Y,(arms['B2']-arms['D2'])*Y],
               ['A-O','B1-D1','B2-D2'],'deltas.png',6)
    delta_plot([(arms['D1']-arms['O'])*Y,(arms['D3']-arms['D1'])*Y,(arms['D3']-arms['O'])*Y],
               ['D1-O (cut)','D3-D1 (deposit)','D3-O (net)'],'deposition-deltas.png',6)
    horizontal_sections(arms,s)
    cross_sections(arms,fields,s)


def horizontal_sections(arms,s):
    rows=[192,384,576];x=s['originWu'][0]+np.arange(s['size'])*s['stepWu']
    fig,axes=plt.subplots(3,2,figsize=(18,11),layout='constrained');records=[]
    for row,(ax,dd) in zip(rows,axes):
        for k in ['O','A','D2','B1','B2']:ax.plot(x,arms[k][row]*Y,lw=.8,label=k)
        for k,src in [('A','O'),('B1','D1'),('B2','D2')]:dd.plot(x,(arms[k]-arms[src])[row]*Y,lw=.8,label=k+'-'+src)
        ax.set_title(f'row{row} z={s["originWu"][1]+row*s["stepWu"]:.3f}wu');ax.legend();dd.legend()
        records.append({'row':row,**{k:arms[k][row] for k in arms}})
    fig.savefig(OUT/'sections.png',dpi=150);plt.close(fig);save('sections.json',records)


def cross_sections(arms,fields,s):
    prior=json.loads((ROOT/'build/trunk-network-20261104/sections.json').read_text())['profiles']
    skeleton=base.lines();vertices=np.concatenate(skeleton)
    # Frozen three section centers; normal from nearest original segment tangent.
    aa=np.concatenate([p[:-1] for p in skeleton]);bb=np.concatenate([p[1:] for p in skeleton]);v=bb-aa
    ll=np.sum(v*v,axis=1);valid=ll>1e-16;aa=aa[valid];v=v[valid];ll=ll[valid]
    fig,axes=plt.subplots(1,3,figsize=(19,5.5),layout='constrained');records=[]
    for ax,rec in zip(axes,prior):
        center=np.array(rec['center']);t=np.clip(np.sum((center-aa)*v,axis=1)/ll,0,1)
        i=np.argmin(np.sum((center-aa-t[:,None]*v)**2,axis=1));normal=np.array([-v[i,1],v[i,0]])/np.sqrt(ll[i])
        offset=np.linspace(-30,30,601);points=center+offset[:,None]*normal
        profiles={k:sample(arms[k],points,s)*Y for k in ['O','D1','D3','B1','B3']}
        for k,value in profiles.items():ax.plot(offset,value,label=k,lw=1)
        ax.set_title(str(center.round(2)));ax.legend();ax.set_xlabel('cross offset wu');ax.set_ylabel('Yproxy')
        records.append({'center':center,'offsetWu':offset,**profiles})
    fig.savefig(OUT/'deposition-sections.png',dpi=150);plt.close(fig);save('deposition-sections.json',records)


def main():
    OUT.mkdir(exist_ok=False);start=time.perf_counter();before=snapshot();save('hashes-before.json',before)
    meta=json.loads((PRE/'meta.json').read_text());s=meta['sampling'];save('sampling.json',s)
    arms={k:np.fromfile(p,dtype='>f8').reshape(769,769).astype(float) for k,p in
          [('O',REF/'carrier-height.f64be'),('D1',PRE/'candidate-height.f64be'),('A',OLD/'A.f64be'),('B1',OLD/'B.f64be')]}
    for name,previous in [('A','O'),('B','D')]:
        text=(OLD/f'{name}.f64be.provenance.txt').read_text()
        assert 'inputSha256='+base.sha(OLD/f'{previous}.f64be') in text
        assert 'tomlSha256='+base.sha(ROOT/'run/config/geogenesis-common.toml') in text
    assert np.array_equal(arms['O'],np.fromfile(OLD/'O.f64be',dtype='>f8').reshape(769,769))
    assert np.array_equal(arms['D1'],np.fromfile(OLD/'D.f64be',dtype='>f8').reshape(769,769))
    frozen={p.name:base.sha(p) for p in [OLD/'A.f64be',OLD/'B.f64be',OLD/'O.f64be',OLD/'D.f64be',OLD/'config-input.toml']}
    save('reused-hashes.json',frozen)
    fields=dict(np.load(PRE/'fields.npz'));assert np.array_equal(fields['H0'],arms['O'])
    arms['D2']=arms['O']-.5*(arms['O']-arms['D1'])
    arms['D3'],depstats=deposition(arms['O'],arms['D1'],fields,s)
    for k,h in arms.items():h.astype('>f8').tofile(OUT/f'{k}.f64be')
    shutil.copyfile(ROOT/'run/config/geogenesis-common.toml',OUT/'config-input.toml')
    shutil.copyfile(OLD/'log4j2.xml',OUT/'log4j2.xml')
    cp=setup_engine()
    current=base.sha(OUT/'engine-classes/com/geogenesis/worldgen/erosion/ErosionEngine.class')
    assert 'engineClassSha256='+current in (OLD/'A.f64be.provenance.txt').read_text(),'engine differs from v1'
    timings={k:run_arm(cp,k,src,s) for k,src in [('B2','D2'),('B3','D3')]}
    for k in ['B2','B3']:arms[k]=np.fromfile(OUT/f'{k}.f64be',dtype='>f8').reshape(769,769).astype(float)
    bands=band_metrics(arms['O'],arms,{'A':arms['O'],'B1':arms['D1'],'B2':arms['D2'],'B3':arms['D3']},fields)
    save('metrics.json',{'timings':timings,'reuseA_B1':frozen,'engineClassSha256':current,
        'halfDepthMaxErrorY':float(np.max(np.abs((arms['O']-arms['D2'])-.5*(arms['O']-arms['D1'])))*Y),
        'particleChanges':{k:base.change(arms[k]-arms[src]) for k,src in [('A','O'),('B1','D1'),('B2','D2'),('B3','D3')]},
        'depositionParticleComparison':{'B3-B1':base.magnitude(arms['B3']-arms['B1']),
          'depositVsFinalDifferenceCorrelation':base.corr(arms['D3']-arms['D1'],arms['B3']-arms['B1']),
          'depositProjectionRetention':float(np.sum((arms['D3']-arms['D1'])*(arms['B3']-arms['B1']))/np.sum((arms['D3']-arms['D1'])**2))}})
    plots(arms,fields,s,meta)
    after=snapshot();changed=[k for k,v in before.items() if after.get(k)!=v]
    save('validation.json',{'protectedFiles':len(before),'changed':changed,'finite':all(np.isfinite(v).all() for v in arms.values()),
                           'size':769,'elapsedSeconds':time.perf_counter()-start,'production':False,'commit':False})
    assert not changed,changed
    print('BANDS',json.dumps(bands,indent=2),flush=True)
    print('DEPOSITION',json.dumps(depstats,indent=2),flush=True)


if __name__=='__main__':main()
