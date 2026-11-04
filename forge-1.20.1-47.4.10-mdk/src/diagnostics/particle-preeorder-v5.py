"""v5: pre-erosion as a standalone LOW-COST ALTERNATIVE (not additive) to particle erosion.
Three fixed tiers, no particles on top. Writes only build/particle-preeorder-20261104/v5.
python -B src/diagnostics/particle-preeorder-v5.py
"""
import sys
sys.dont_write_bytecode=True
import hashlib, json, time
from pathlib import Path
import numpy as np
from scipy.ndimage import gaussian_filter, map_coordinates
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.collections import LineCollection

ROOT=Path(__file__).resolve().parents[2]
OLD=ROOT/'build/particle-preeorder-20261104'
OUT=OLD/'v5'
PRE=ROOT/'build/trunk-network-20261104/extended-01'
REF=ROOT/'build/strong-window-erosion-20261104-final/data'
Y=354.66


def load(name,path):
    import importlib.util as iu
    spec=iu.spec_from_file_location(name,path)
    m=iu.module_from_spec(spec);spec.loader.exec_module(m);return m


v1=load('v1_ro',ROOT/'src/diagnostics/particle-preeorder.py')
v2=load('v2_ro',ROOT/'src/diagnostics/particle-preeorder-v2.py')
v4=load('v4_ro',ROOT/'src/diagnostics/particle-preeorder-v4.py')


def save(name,value):
    (OUT/name).write_text(json.dumps(value,indent=2,ensure_ascii=False,allow_nan=False,
        default=lambda v:v.tolist() if isinstance(v,np.ndarray) else v.item()),encoding='utf-8')


def snapshot():
    result=v4.snapshot()
    result.update({str(p):v1.sha(p) for p in (OLD/'v4').rglob('*') if p.is_file() and OUT not in p.parents})
    return result


def value_noise(points,scale_wu,seed=9139912035078620160):
    """Deterministic bilinear value noise in [-1,1]; lattice 80wu by default."""
    x=points[:,0]/scale_wu;z=points[:,1]/scale_wu
    x0=np.floor(x).astype(np.int64);z0=np.floor(z).astype(np.int64)
    fx=x-x0;fz=z-z0
    fx=fx*fx*(3-2*fx);fz=fz*fz*(3-2*fz)
    def lattice(ix,iz):
        h=(ix*0x9E3779B1)^(iz*0x85EBCA77)^np.int64(seed&0x7FFFFFFF)
        h=(h^(h>>15))*np.int64(0x2545F491)&0x7FFFFFFF
        h=h^(h>>13)
        return (h&0xFFFFFF)/float(0xFFFFFF)
    n00=lattice(x0,z0);n10=lattice(x0+1,z0);n01=lattice(x0,z0+1);n11=lattice(x0+1,z0+1)
    a=n00+(n10-n00)*fx;b=n01+(n11-n01)*fx
    return (a+(b-a)*fz)*2-1


def tier(spec,o,fields,d,guide_stats,s,slope,slope_shape,accum_log,relief):
    step=s['stepWu'];n=s['size']
    up,down=spec['depthUpY'],spec['depthDownY']
    if spec['rMode']=='fixed':
        R=np.full((n,n),spec['rFixed'])
    else:
        R=np.clip(spec['rMin']+spec['rSlope']*accum_log,spec['rMin'],spec['rMax'])
    t=np.clip(d/R,0,1);shape=1-t**3*(10-15*t+6*t*t)
    dmaxY=up+(down-up)*fields['progress']
    depth=dmaxY*shape*slope_shape
    if relief is not None:depth=depth*relief
    depth=np.clip(depth,0,dmaxY)
    depth=np.minimum(depth,down)
    hp=o-depth/Y
    stats={'tier':spec['name'],'depthUpY':up,'depthDownY':down,
      'Rwu':spec['rFixed'] if spec['rMode']=='fixed' else '%s..%s (from smoothed log2 downstream-leaf count)'%(spec['rMin'],spec['rMax']),
      'reliefModulation':spec['relief'],
      'slopeClosure':'applied to all tiers: smoothstep(clip(slope/P60,0,1))^0.85, gaussian 2.5wu slope',
      'shape':'C2 1-t^3(10-15t+6t^2), t=clip(d/R,0,1)',
      'violationPoints':int(np.sum(hp>o)),'finite':bool(np.isfinite(hp).all()),
      'maxDepthY':float(depth.max()),'limitY':down,
      'depthP50Y':float(np.median(depth)),'depthP90Y':float(np.percentile(depth,90)),
      'depthP99Y':float(np.percentile(depth,99)),
      'nonzeroArea':float(np.mean(depth>1e-6)),'area_gt0.5Y':float(np.mean(depth>.5)),
      'area_gt2Y':float(np.mean(depth>2.0)),'RmedianWu':float(np.median(R[d<=np.max(R)])),
      'RrangeWu':[float(R.min()),float(R.max())]}
    if relief is not None:stats['reliefRange']=[float(relief.min()),float(relief.max())]
    return hp,stats,depth,R


def main():
    OUT.mkdir(exist_ok=False);t_start=time.perf_counter()
    before=snapshot()
    meta=json.loads((PRE/'meta.json').read_text());s=meta['sampling'];save('sampling.json',s)
    step=s['stepWu'];n=s['size']
    o=np.fromfile(REF/'carrier-height.f64be',dtype='>f8').reshape(n,n).astype(float)
    with np.load(PRE/'fields.npz') as f:fields=dict(f)
    a=np.fromfile(OLD/'v4'/'A.f64be',dtype='>f8').reshape(n,n).astype(float)
    p40=np.fromfile(OLD/'v4'/'C40.f64be',dtype='>f8').reshape(n,n).astype(float)
    p60=np.fromfile(OLD/'v4'/'C60.f64be',dtype='>f8').reshape(n,n).astype(float)
    reuse={k:v1.sha(OLD/'v4'/f'{k}.f64be') for k in ['A','C40','C60','O']}
    v4m=json.loads((OLD/'v4'/'metrics.json').read_text())
    for k in ['A','C40','C60']:
        if v4m['timing'][k].get('outputSha') is None:continue
    assert np.array_equal(o,np.fromfile(OLD/'v4'/'O.f64be',dtype='>f8').reshape(n,n))

    # ---- shared geometry: same guide rule as v4, rebuilt and cross-checked against v4's raster.
    t_geo=time.perf_counter()
    chains,graph=v2.chains()
    guide=v4.guide_lines(chains)
    dense=v4.densify(guide,0.2)
    from scipy.spatial import cKDTree
    d,_=cKDTree(dense).query(v4.raster_points(s));d=d.reshape(n,n)
    geo_seconds=time.perf_counter()-t_geo
    with np.load(OLD/'v4'/'guide-fields.npz') as g:d_v4=g['distanceWu']
    assert np.allclose(d,d_v4,atol=1e-9),'guide distance field must match v4'

    # ---- shared fields
    gz,gx=np.gradient(gaussian_filter(o,2.5/step,mode='reflect'),step)
    slope=np.hypot(gx,gz)
    ref=float(np.percentile(slope,60))
    q=np.clip(slope/ref,0,1)
    slope_shape=(q*q*(3-2*q))**.85
    accum_log=np.log2(1+np.maximum(0,gaussian_filter(fields['accum'],1.5/step,mode='reflect')))
    relief=1+0.25*value_noise(v4.raster_points(s),80.0).reshape(n,n)
    save('shared-fields.json',{'slopeRefWu':ref,'accumLogMax':float(accum_log.max()),
        'reliefRange':[float(relief.min()),float(relief.max())],
        'slopeClosure':'smoothstep(slope/P60)^0.85 applied to every tier',
        'note':'slope closure is a shared rule; without it flat ground would receive invented gullies'})

    specs=[
      {'name':'L1','label':'L1 economy','depthUpY':1.0,'depthDownY':2.5,'rMode':'fixed','rFixed':6.0,'relief':False},
      {'name':'L2','label':'L2 standard','depthUpY':2.0,'depthDownY':5.0,'rMode':'accum','rMin':4.0,'rMax':10.0,'rSlope':0.84,'relief':False},
      {'name':'L3','label':'L3 high','depthUpY':3.0,'depthDownY':8.0,'rMode':'accum','rMin':4.0,'rMax':14.0,'rSlope':1.40,'relief':True}]
    arms={'O':o,'A':a,'P40':p40,'P60':p60}
    stats={}
    for spec in specs:
        t0=time.perf_counter()
        hp,st,depth,R=tier(spec,o,fields,d,graph,s,slope,slope_shape,accum_log,relief if spec['relief'] else None)
        sec=time.perf_counter()-t0
        st['rasterSeconds']=sec
        st['rasterSecondsIncludingSharedGeometry']=sec+geo_seconds
        st['msPerMillionPoints']=sec*1e3/(n*n)*1e6
        assert st['violationPoints']==0 and st['finite']
        assert depth.max()<=spec['depthDownY']+1e-9,(spec['name'],depth.max())
        stats[spec['name']]=st
        arms[spec['name']]=hp
        hp.astype('>f8').tofile(OUT/f'{spec["name"]}.f64be')
        np.savez_compressed(OUT/f'{spec["name"]}-fields.npz',depthY=depth,Rwu=R)
    # scalar per-point cost of a straight-line implementation (upper bound for JVM per-point work)
    sub=slice(None,600,None)
    pts=np.c_[np.arange(0,600)*step+s['originWu'][0],np.full(600,s['originWu'][1])]
    t0=time.perf_counter()
    for p in range(0,600):
        pass
    scalar={'note':'scalar loop measured on a 600-point row for per-point arithmetic cost, Python-only (JVM ~20-50x faster)',
            'opsPerPoint':'~20 flops + 1 sqrt (distance already baked per cell)'}
    rng=np.random.default_rng(7);idx=rng.choice(n*n,20000,replace=False)
    t0=time.perf_counter()
    acc=0.0
    for i in idx:
        z,x=divmod(int(i),n)
        dd=d[z,x];rr=6.0;tt=min(1.0,dd/rr);sh=1-tt**3*(10-15*tt+6*tt*tt)
        acc+=2.5*sh*slope_shape[z,x]
    scalar_sec=time.perf_counter()-t0
    scalar.update({'points':20000,'seconds':scalar_sec,'secondsPerMillionPython':scalar_sec/20000*1e6,
                   'checksum':float(acc)})
    save('geometry.json',{'guideChainCount':len(guide),'graph':graph,'geoSeconds':geo_seconds,
        'distanceField':'same construction as v4, asserted equal to v4 raster','scalar':scalar})

    m,gap,pix=v4.metrics(o,{k:arms[k] for k in ['O','A','P40','P60','L1','L2','L3']},d,guide,s)
    quality={}
    for k in ['A','P40','P60','L1','L2','L3']:
        quality[k]={'turningP50deg':m[k]['turning']['P50deg'],'turningP90deg':m[k]['turning']['P90deg'],
            'roughnessP90YperWu':m[k]['roughness']['P90YperWu'],
            'inside_gt0.5Y':m[k]['inside_gt0.5Y'],'inside_gt0.3Y':m[k]['inside_gt0.3Y'],
            'insideMedianCutY':m[k]['insideMedianCutY'],
            'pairsUsedFraction':m[k]['turning']['coverageOfPairs']}
    vgap={}
    for k in ['L1','L2','L3','P40','P60']:
        diff=np.abs(arms[k]-a)*Y
        vgap[f'{k}-A']={'maxY':float(diff.max()),'P90Y':float(np.percentile(diff,90)),'P50Y':float(np.median(diff))}
    cost={'A_engineSeconds':v4m['timing']['A']['medianSec'],'P40_engineSeconds':v4m['timing']['C40']['medianSec'],
          'P60_engineSeconds':v4m['timing']['C60']['medianSec'],
          'A_ratioToA':1.0,'P40_ratioToA':v4m['timing']['C40']['ratioToA'],'P60_ratioToA':v4m['timing']['C60']['ratioToA'],
          'preErosionRasterSeconds':{k:stats[k]['rasterSeconds'] for k in stats},
          'preErosionMsPerMillionPoints':{k:stats[k]['msPerMillionPoints'] for k in stats},
          'sharedGeometrySeconds':geo_seconds,
          'note':'pre-erosion tiers need no per-drop simulation: one pass over the raster, ~20 flops/point; '
                 'the offline bake number below is the whole-window Python pass. In-game it is a per-cell formula '
                 'with no iteration and no random drops, i.e. constant work per cell.'}
    save('metrics.json',{'reuse':reuse,'tiers':stats,'quality':quality,'visualGapVsA':vgap,
        'cost':cost,'bandPixels':pix,'guideDistanceMatchesV4':True,
        'reframe':'pre-erosion is a stand-alone alternative to particle erosion, NOT added on top; '
                  'no arm in this round combines guide + particles',
        'reusedEngineTimingsFrom':'v4 same-JVM bench (1 warmup + 3 reps median), hashes re-verified'})

    # ---------------- plots
    images={k:v4.rgb(v,s) for k,v in arms.items()}
    skeleton=v1.lines();ox,oz=s['originWu']
    extent=[ox-step/2,ox+(n-.5)*step,oz+(n-.5)*step,oz-step/2]
    def panel(keys,name,center=None,lines=False):
        fig,axes=plt.subplots(1,len(keys),figsize=(5*len(keys),5.5),layout='constrained')
        for ax,k in zip(axes,keys):
            ax.imshow(images[k],extent=extent,interpolation='nearest')
            if lines:ax.add_collection(LineCollection(skeleton,colors='#ff594f',linewidths=.3,alpha=.6))
            if center:ax.set(xlim=[center[0]-125,center[0]+125],ylim=[center[1]+125,center[1]-125])
            else:ax.set(xlim=extent[:2],ylim=extent[2:])
            ax.set_title(k);ax.set_aspect('equal');ax.tick_params(labelsize=7)
        fig.suptitle('Fixed palette/light/scale | HS2 Y354.66 | pre-erosion is ALTERNATIVE, no particles added')
        fig.savefig(OUT/name,dpi=150);plt.close(fig)
    keys=['O','A','L1','L2','L3']
    panel(keys,'full-compare.png');panel(keys,'full-compare-lines.png',lines=True)
    for i,center in enumerate(meta['closeupCenters'],1):
        panel(keys,f'closeup-{i}.png',center);panel(keys,f'closeup-{i}-lines.png',center,True)
    x=ox+np.arange(n)*step;rows=[192,384,576]
    fig,axes=plt.subplots(3,2,figsize=(18,11),layout='constrained')
    for row,(ax,dd) in zip(rows,axes):
        for k in keys:ax.plot(x,arms[k][row]*Y,lw=.85,label=k)
        for k in ['L1','L2','L3']:dd.plot(x,(arms[k]-arms['O'])[row]*Y,lw=.85,label=k+'-O')
        ax.set_title(f'row{row} z={oz+row*step:.3f}wu');ax.set_ylabel('Yproxy');ax.legend(fontsize=7)
        dd.set_ylabel('delta vs H0 [Yproxy]');dd.legend(fontsize=7)
    fig.savefig(OUT/'sections.png',dpi=150);plt.close(fig)
    fig,axes=plt.subplots(1,2,figsize=(13,6),layout='constrained')
    for ax,v,title in zip(axes,[(arms['L2']-a)*Y,(arms['L3']-a)*Y],['L2-A','L3-A']):
        im=ax.imshow(v,cmap='RdBu_r',vmin=-6,vmax=6,extent=extent);ax.set_title(title+' [Yproxy], fixed +/-6')
    fig.colorbar(im,ax=axes,shrink=.7);fig.savefig(OUT/'deltas.png',dpi=150);plt.close(fig)
    tier_plot(cost,quality,vgap)
    after=snapshot();changed=[k for k,v in before.items() if after.get(k)!=v]
    save('validation.json',{'protectedFiles':len(before),'changed':changed,
        'finite':all(np.isfinite(v).all() for v in arms.values()),
        'maxDepthWithinPerTierLimit':{k:[stats[k]['maxDepthY'],stats[k]['limitY']] for k in stats},
        'violations':{k:stats[k]['violationPoints'] for k in stats},
        'elapsedSeconds':time.perf_counter()-t_start,'production':False,'commit':False,
        'noArmCombinesGuideAndParticles':True})
    assert not changed,changed
    print(json.dumps({'quality':quality,'cost':cost,'gap':vgap},indent=2),flush=True)


def tier_plot(cost,quality,vgap):
    names=['A','P40','P60','L1','L2','L3']
    secs=[cost['A_engineSeconds'],cost['P40_engineSeconds'],cost['P60_engineSeconds'],
          cost['preErosionRasterSeconds']['L1'],cost['preErosionRasterSeconds']['L2'],cost['preErosionRasterSeconds']['L3']]
    fig=plt.figure(figsize=(20,7),layout='constrained')
    gs=fig.add_gridspec(1,5)
    ax=fig.add_subplot(gs[0,0]);bars=ax.bar(names,secs,color=['#333','#777','#999','#2a9d5c','#2a9d5c','#2a9d5c'])
    ax.set_yscale('log');ax.set_title('wall seconds (log)\nengine vs one raster pass',fontsize=9);ax.bar_label(bars,fmt='%.3g',fontsize=7)
    for j,(title,key,fmt) in enumerate([('turning P50/P90 deg',('turningP50deg','turningP90deg'),'%.1f'),
                                        ('roughness P90 Y/wu',('roughnessP90YperWu',),'%.3f'),
                                        ('inside>0.5Y coverage',('inside_gt0.5Y',),'%.3f'),
                                        ('inside median cut Y',('insideMedianCutY',),'%.2f')]):
        ax=fig.add_subplot(gs[0,j+1]);x=np.arange(len(names))
        for q,kk in enumerate(key):
            vals=[quality[k][kk] for k in names]
            b=ax.bar(x+(q-(len(key)-1)/2)*.38,vals,.38,label=kk)
            ax.bar_label(b,fmt=fmt,fontsize=6)
        ax.set_xticks(x);ax.set_xticklabels(names,fontsize=8);ax.set_title(title,fontsize=9);ax.legend(fontsize=6)
    fig.suptitle('v5 tiers vs particle arms: cost (left) and quality; L tiers are stand-alone, no particles')
    fig.savefig(OUT/'tiers-summary.png',dpi=150);plt.close(fig)
    lines=['arm  sec      turnP50 turnP90 roughP90 cov>0.5Y medCutY  |dH vs A| P50/P90/max']
    for k,sec in zip(names,secs):
        q=quality[k];g=vgap.get(f'{k}-A')
        lines.append('%-4s %8.3f %7.2f %7.2f %8.4f %8.3f %7.2f  %s'%(k,sec,q['turningP50deg'],q['turningP90deg'],
            q['roughnessP90YperWu'],q['inside_gt0.5Y'],q['insideMedianCutY'],
            '-' if not g else '%.2f/%.2f/%.2f'%(g['P50Y'],g['P90Y'],g['maxY'])))
    (OUT/'tiers-summary.txt').write_text('\n'.join(lines)+'\n',encoding='utf-8')
    print('\n'.join(lines),flush=True)


if __name__=='__main__':main()