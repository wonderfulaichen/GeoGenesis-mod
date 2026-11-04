"""v4: extremely shallow large-scale guide + reduced-droplet engine, fixed full window.
python -B src/diagnostics/particle-preeorder-v4.py
Writes only build/particle-preeorder-20261104/v4.
"""
import sys
sys.dont_write_bytecode=True
import hashlib, json, os, shutil, subprocess, time
from pathlib import Path
import numpy as np
from scipy.spatial import cKDTree
from scipy.ndimage import gaussian_filter, map_coordinates
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.collections import LineCollection

ROOT=Path(__file__).resolve().parents[2]
OLD=ROOT/'build/particle-preeorder-20261104'
OUT=OLD/'v4'
PRE=ROOT/'build/trunk-network-20261104/extended-01'
REF=ROOT/'build/strong-window-erosion-20261104-final/data'
Y=354.66


def load(name,path):
    import importlib.util as iu
    spec=iu.spec_from_file_location(name,path)
    m=iu.module_from_spec(spec);spec.loader.exec_module(m);return m


v1=load('v1_ro',ROOT/'src/diagnostics/particle-preeorder.py')
v2=load('v2_ro',ROOT/'src/diagnostics/particle-preeorder-v2.py')
v3=load('v3_ro',ROOT/'src/diagnostics/particle-preeorder-v3.py')


def save(name,value):
    (OUT/name).write_text(json.dumps(value,indent=2,ensure_ascii=False,allow_nan=False,
        default=lambda v:v.tolist() if isinstance(v,np.ndarray) else v.item()),encoding='utf-8')


def snapshot():
    result=v3.snapshot()
    result.update({str(p):v1.sha(p) for p in (OLD/'v3').rglob('*') if p.is_file() and OUT not in p.parents})
    return result


# ---------------------------------------------------------------- guide construction

def guide_lines(chains,step_wu=20.0,smooth_wu=20.0):
    """Resample each maximal non-branching chain every step_wu, then one +-smooth_wu
    moving average with fixed endpoints. Removes sub-20wu wiggle but keeps the trend."""
    out=[]
    for q in chains:
        arc=np.r_[0,np.cumsum(np.linalg.norm(np.diff(q,axis=0),axis=1))]
        total=float(arc[-1])
        if total<2*step_wu:continue
        count=int(np.floor(total/step_wu))+1
        ss=np.linspace(0,total,count)
        p=np.c_[np.interp(ss,arc,q[:,0]),np.interp(ss,arc,q[:,1])]
        sm=p.copy()
        for i in range(1,len(p)-1):
            m=(ss>=ss[i]-smooth_wu)&(ss<=ss[i]+smooth_wu)
            sm[i]=p[m].mean(axis=0)
        out.append(sm)
    return out


def densify(lines,spacing=0.2):
    pts=[]
    for p in lines:
        seg=np.linalg.norm(np.diff(p,axis=0),axis=1);arc=np.r_[0,np.cumsum(seg)]
        if arc[-1]<=0:continue
        k=max(2,int(np.ceil(arc[-1]/spacing))+1);s=np.linspace(0,arc[-1],k)
        pts.append(np.c_[np.interp(s,arc,p[:,0]),np.interp(s,arc,p[:,1])])
    return np.concatenate(pts)


def raster_points(s):
    step=s['stepWu'];ix,iz=s['gridIntegerOrigin'];n=s['size']
    xx,zz=np.meshgrid((ix+np.arange(n))*step,(iz+np.arange(n))*step)
    return np.c_[xx.ravel(),zz.ravel()]


def build_guide(o,fields,s):
    chains,graph=v2.chains()
    guide=guide_lines(chains);step=s['stepWu'];n=s['size']
    dense=densify(guide,0.2)
    d,idx=cKDTree(dense).query(raster_points(s));d=d.reshape(n,n)
    progress=fields['progress']
    R=np.full((n,n),6.0)
    t=np.clip(d/R,0,1);shape=1-t**3*(10-15*t+6*t*t)
    dmaxY=0.5+0.7*progress
    capY=0.08*np.maximum(0,o)*Y
    depthY=np.minimum(dmaxY,capY)*shape
    depthY=np.maximum(depthY,0)
    hp=o-depthY/Y
    stats={'graph':graph,'guideChainCount':len(guide),'guideVertexSpacingWu':20.0,
      'guideSmoothing':'single +-20wu moving average along arc, endpoints fixed',
      'distanceField':'nearest densified guide point spacing0.2wu => max distance error <=0.1wu (1.7% of R=6)',
      'Rwu':'fixed 6.0, no relief modulation','shape':'C2 window 1-t^3(10-15t+6t^2), t=clip(d/R,0,1)',
      'depthEnvelopeY':'min(0.5+0.7*progress, 0.08*H0*Y) * shape',
      'violationPoints':int(np.sum(hp>o)),'maxDepthY':float(depthY.max()),
      'depthP50Y':float(np.median(depthY)),'depthP90Y':float(np.percentile(depthY,90)),
      'depthP99Y':float(np.percentile(depthY,99)),
      'nonzeroArea':float(np.mean(depthY>1e-6)),'area_gt0.3Y':float(np.mean(depthY>.3)),
      'area_gt0.6Y':float(np.mean(depthY>.6)),'capBindsArea':float(np.mean(capY<dmaxY)),
      'compareToV1Cut':'v1 cut max 5.955784Y, deep area>0.5Y 30.6824%; this guide is far shallower by design'}
    stacked=np.concatenate([np.vstack([p,[np.nan,np.nan]]) for p in guide])
    np.savez_compressed(OUT/'guide-fields.npz',depthY=depthY,shape=shape,distanceWu=d,
                        guideStacked=stacked)
    save('guide.json',stats)
    return hp,stats,d,guide


# ---------------------------------------------------------------- reduced engine copies

ALLOWED=("public class ErosionEngineReduced","public ErosionEngineReduced(",
         "DROPS_C = ","DROPS_M = ","DROPS_F = ")

def engine_copies(reduced):
    src=(ROOT/'src/main/java/com/geogenesis/worldgen/erosion/ErosionEngine.java').read_text(encoding='utf-8')
    out={}
    for pct,(dc,dm,df) in reduced.items():
        t=src.replace('public class ErosionEngine {','public class ErosionEngineReduced {')
        t=t.replace('public ErosionEngine(double dropsMul, int seed)','public ErosionEngineReduced(double dropsMul, int seed)')
        t=t.replace('public ErosionEngine(double dropsMul, int seed, boolean xsEnabled)',
                    'public ErosionEngineReduced(double dropsMul, int seed, boolean xsEnabled)')
        t=t.replace('DROPS_C = 120,','DROPS_C = %d,'%dc).replace('DROPS_M = 60,','DROPS_M = %d,'%dm)
        t=t.replace('DROPS_F = 40,','DROPS_F = %d,'%df)
        a=src.splitlines();b=t.splitlines()
        if len(a)!=len(b):raise SystemExit('copy changed line count')
        diff=[(x,y) for x,y in zip(a,b) if x!=y]
        bad=[p for p in diff if not any(k in p[1] for k in ALLOWED)]
        if bad or len(diff)!=6:raise SystemExit('unexpected diff: %r'%diff)
        d=OUT/f'engine-red{pct}';d.mkdir()
        (d/'ErosionEngineReduced.java').write_text(t,encoding='utf-8')
        out[pct]={'dir':d,'constantDiff':[p[1].strip() for p in diff],
                  'drops':{'C':dc,'M':dm,'F':df},'sourceLines':len(b)}
    return src,out


def compile_all(copies):
    jars=(ROOT/'build/terrain-upgrade/baseline/classpath-jars.txt').read_text().strip()
    cp=str(ROOT/'build/classes/java/main')+os.pathsep+jars
    opts=['--release','17','-encoding','UTF-8','-g','-nowarn','-proc:none','-implicit:none','-classpath',cp]
    jobs=[('engine-full',[ROOT/'src/main/java/com/geogenesis/worldgen/erosion/ErosionEngine.java'])]
    jobs+= [(f'engine-red{k}',[v['dir']/'ErosionEngineReduced.java']) for k,v in copies.items()]
    commands=[]
    for name,files in jobs:
        dest=OUT/name/'classes';dest.mkdir(parents=True,exist_ok=True)
        cmd=['javac',*opts,'-d',str(dest),*map(str,files)]
        r=subprocess.run(cmd,cwd=OUT,capture_output=True,text=True)
        (OUT/f'{name}-compile.log').write_text(r.stdout+r.stderr,encoding='utf-8');r.check_returncode()
        commands.append(cmd)
    bench=OUT/'bench-classes';bench.mkdir()
    cmd=['javac',*opts,'-d',str(bench),str(ROOT/'src/diagnostics/java/com/geogenesis/worldgen/erosion/GuideErosionBench.java')]
    r=subprocess.run(cmd,cwd=OUT,capture_output=True,text=True)
    (OUT/'bench-compile.log').write_text(r.stdout+r.stderr,encoding='utf-8');r.check_returncode()
    commands.append(cmd)
    save('compile.json',{'commands':commands,'classpath':cp,
        'convention':'javac flags copied from ErosionScratchProbe.Compile.engine; existing main classes + existing classpath-jars.txt; no dependency added'})
    return str(bench)+os.pathsep+cp


def run_bench(cp,arms,s,copies):
    spec=OUT/'arms.txt'
    spec.write_text('\n'.join('%s|%s|%s|%s|%s'%(n,str(OUT/eng/'classes'),
        'com.geogenesis.worldgen.erosion.ErosionEngine' if cls=='full' else 'com.geogenesis.worldgen.erosion.ErosionEngineReduced',
        str(OUT/f'{src}.f64be'),n) for n,eng,cls,src in arms)+'\n',encoding='utf-8')
    cmd=['java','-Xmx3g','-Dlog4j.configurationFile='+str(OUT/'log4j2.xml'),'-cp',cp,
         'com.geogenesis.worldgen.erosion.GuideErosionBench',str(OUT/'config-input.toml'),
         str(s['size']),str(round(s['originWu'][0])),str(round(s['originWu'][1])),'2','3','1',
         str(spec),str(OUT)]
    start=time.perf_counter()
    r=subprocess.run(cmd,cwd=ROOT,capture_output=True,text=True,timeout=2400)
    (OUT/'bench.log').write_text(r.stdout+r.stderr,encoding='utf-8')
    r.check_returncode()
    wall=time.perf_counter()-start
    rows={}
    for line in r.stdout.splitlines():
        if line.startswith('arm|'):
            f=line.split('|');rows[f[1]]={'engineClass':f[2],'dir':f[3],'medianSec':float(f[9].split('=')[1]),
                'allSec':f[10].split('=')[1],'inputSha':f[11].split('=')[1],'outputSha':f[12].split('=')[1]}
        elif line.startswith('config|'):
            rows['config']=line.split('|')[1:]
    rows['processWallSeconds']=wall
    return rows


# ---------------------------------------------------------------- metrics

def metrics(o,arms,d,guide,s):
    step=s['stepWu'];n=s['size']
    def smoothen(h,sigma_wu):
        return gaussian_filter(h,sigma_wu/step,mode='reflect')
    def gradient(h,sigma_wu):
        gz,gx=np.gradient(smoothen(h,sigma_wu),step)
        return gx,gz
    # Coverage inside the guide band.
    inside=d<=6.0
    # Continuity: gradient (valley tangent) sampled on guide vertices 20wu apart.
    gx,gy=gradient(arms['A'],2.5)
    slope_all=np.hypot(gx,gy)[inside]
    floor=float(np.percentile(slope_all,25))
    pairs=guide
    def turning(h):
        gx,gy=gradient(h,2.5)
        mag=np.hypot(gx,gy)
        angles=[];used=0;total=0
        for p in pairs:
            xy=(p-s['originWu'])/step
            u=map_coordinates(gx,xy.T[::-1],order=1,mode='nearest',prefilter=False)
            v=map_coordinates(gy,xy.T[::-1],order=1,mode='nearest',prefilter=False)
            m=np.hypot(u,v)
            if len(u)<2:continue
            total+=len(u)-1
            ok=(m[:-1]>floor)&(m[1:]>floor)
            ok &= (np.hypot(*np.diff(p,axis=0).T)>1e-9)
            if not ok.any():continue
            dot=(u[:-1]*u[1:]+v[:-1]*v[1:])/np.maximum(m[:-1]*m[1:],1e-30)
            ang=np.degrees(np.arccos(np.clip(np.abs(dot[ok]),0,1)))
            angles.append(ang);used+=int(ok.sum())
        a=np.concatenate(angles)
        return {'P50deg':float(np.median(a)),'P90deg':float(np.percentile(a,90)),
                'pairsUsed':used,'pairsTotal':total,'coverageOfPairs':used/total,
                'slopeFloorYperWu':floor*Y}
    def roughness(h):
        hp=h-smoothen(h,4.0)
        gz,gx=np.gradient(hp,step)
        r=np.hypot(gx,gz)*Y
        return {'P50YperWu':float(np.median(r)),'P90YperWu':float(np.percentile(r,90)),
                'meanYperWu':float(r.mean()),'note':'|grad(H - gaussian(H,4wu))|'}
    out={}
    for k,h in arms.items():
        cut=(o-h)*Y
        out[k]={'turning':turning(h),'roughness':roughness(h),
                'inside_gt0.3Y':float(np.mean(cut[inside]>.3)),
                'inside_gt0.5Y':float(np.mean(cut[inside]>.5)),
                'insideMedianCutY':float(np.median(np.maximum(0,cut[inside]))),
                'globalRoughnessP90':roughness(h)['P90YperWu']}
    gap={}
    for a,b in [('B','A'),('C40','A'),('C60','A'),('D40','B'),('D60','B'),('D40','C40'),('B','C40')]:
        if a in arms and b in arms:
            diff=np.abs(arms[a]-arms[b])*Y
            gap[f'{a}-{b}']={'maxY':float(diff.max()),'P90Y':float(np.percentile(diff,90)),
                             'P50Y':float(np.median(diff))}
    return out,gap,{'insidePixels':int(inside.sum()),'slopeFloorYperWu':floor*Y}


# ---------------------------------------------------------------- plots

def rgb(h,s):
    pal=np.genfromtxt(ROOT/'build/fast-erosion-scale-only-20261103-final/data/project-palette.csv',delimiter=',',skip_header=1)
    gz,gx=np.gradient(h*Y,s['stepWu']*2)
    normal=np.stack([-gx,-gz,np.ones_like(h)],axis=-1);normal/=np.linalg.norm(normal,axis=-1)[...,None]
    light=np.array([-.5,-.3,1.]);light/=np.linalg.norm(light)
    shade=.65+.35*np.maximum(0,normal@light)
    e=np.clip(.02+1.5*h,-.35,1.2)
    return np.clip(np.stack([np.interp(e,pal[:,0],pal[:,i])/255 for i in (1,2,3)],axis=-1)*shade[...,None],0,1)


def plots(arms,shown,s,meta,guide):
    step=s['stepWu'];n=s['size'];ox,oz=s['originWu']
    extent=[ox-step/2,ox+(n-.5)*step,oz+(n-.5)*step,oz-step/2]
    images={k:rgb(v,s) for k,v in arms.items()};skeleton=v1.lines()
    def panel(keys,name,center=None,lines=False,guides=False):
        fig,axes=plt.subplots(1,len(keys),figsize=(5*len(keys),5.5),layout='constrained')
        for ax,k in zip(axes,keys):
            ax.imshow(images[k],extent=extent,interpolation='nearest')
            if lines:ax.add_collection(LineCollection(skeleton,colors='#ff594f',linewidths=.3,alpha=.6))
            if guides:ax.add_collection(LineCollection(guide,colors='#25d0ff',linewidths=.5,alpha=.85))
            if center:ax.set(xlim=[center[0]-125,center[0]+125],ylim=[center[1]+125,center[1]-125])
            else:ax.set(xlim=extent[:2],ylim=extent[2:])
            ax.set_title(k);ax.set_aspect('equal');ax.tick_params(labelsize=7)
        fig.suptitle('Fixed palette/light/scale | HS2 Y354.66 | offline raster, not production')
        fig.savefig(OUT/name,dpi=150);plt.close(fig)
    for lines in [False,True]:
        suffix='-lines' if lines else ''
        panel(shown,f'full-4arms{suffix}.png',lines=lines)
        panel(['O','B','C40','D40'],f'full-4arms-cost{suffix}.png',lines=lines)
        panel(['B','D40'],f'guide-overlay{suffix}.png',lines=lines,guides=True)
        for i,center in enumerate(meta['closeupCenters'],1):
            panel(shown,f'closeup-{i}{suffix}.png',center,lines)
            panel(['O','B','C40','D40'],f'closeup-cost-{i}{suffix}.png',center,lines)
    x=ox+np.arange(n)*step;rows=[192,384,576]
    fig,axes=plt.subplots(3,2,figsize=(18,11),layout='constrained')
    for row,(ax,dd) in zip(rows,axes):
        for k in shown:ax.plot(x,arms[k][row]*Y,lw=.85,label=k)
        for k in ['A','B','C40','D40']:dd.plot(x,(arms[k]-arms['O'])[row]*Y,lw=.8,label=k+'-O')
        ax.set_title(f'row{row} z={oz+row*step:.3f}wu');ax.set_ylabel('Yproxy');ax.legend(fontsize=7)
        dd.set_ylabel('delta Yproxy');dd.legend(fontsize=7)
    fig.savefig(OUT/'sections.png',dpi=150);plt.close(fig)
    fig,axes=plt.subplots(1,4,figsize=(21,6),layout='constrained')
    for ax,k in zip(axes,['A','B','C40','D40']):
        im=ax.imshow((arms[k]-arms['O'])*Y,cmap='RdBu_r',vmin=-6,vmax=6,extent=extent)
        ax.set_title(k+'-O [Yproxy]')
    fig.colorbar(im,ax=axes,shrink=.7);fig.savefig(OUT/'deltas.png',dpi=150);plt.close(fig)


def summary_plot(m,gap,timing,names):
    fig,axes=plt.subplots(1,4,figsize=(22,6),layout='constrained')
    series=[(axes[0],'turning P50/P90 deg',[[m[k]['turning']['P50deg'] for k in names],[m[k]['turning']['P90deg'] for k in names]]),
            (axes[1],'roughness |grad high-pass| P90 Y/wu',[[m[k]['roughness']['P90YperWu'] for k in names]]),
            (axes[2],'inside d<=R cut>0.3Y coverage',[[m[k]['inside_gt0.3Y'] for k in names]]),
            (axes[3],'engine seconds (median of 3)',[[timing[k]['medianSec'] for k in names]])]
    labels={'turning P50/P90 deg':['P50','P90'],'roughness |grad high-pass| P90 Y/wu':['P90'],
            'inside d<=R cut>0.3Y coverage':['coverage'],'engine seconds (median of 3)':['median s']}
    for ax,title,vals in series:
        width=.8/max(1,len(vals));x=np.arange(len(names))
        for j,v in enumerate(vals):
            bars=ax.bar(x+(j-(len(vals)-1)/2)*width,v,width,label=labels[title][j])
            ax.bar_label(bars,fmt='%.3g',fontsize=7)
        ax.set_xticks(x);ax.set_xticklabels(names,rotation=0,fontsize=8);ax.set_title(title,fontsize=9);ax.legend(fontsize=7)
    fig.suptitle('v4 metrics: continuity / fragmentation / coverage / cost (fixed window, offline)')
    fig.savefig(OUT/'metrics-summary.png',dpi=150);plt.close(fig)


# ---------------------------------------------------------------- main

def main():
    OUT.mkdir(exist_ok=False);t0=time.perf_counter()
    before=snapshot()
    meta=json.loads((PRE/'meta.json').read_text());s=meta['sampling'];save('sampling.json',s)
    o=np.fromfile(REF/'carrier-height.f64be',dtype='>f8').reshape(769,769).astype(float)
    with np.load(PRE/'fields.npz') as f:fields=dict(f)
    assert np.array_equal(o,fields['H0'])
    hp2,guide_stats,d,guide=build_guide(o,fields,s)
    assert guide_stats['violationPoints']==0 and guide_stats['maxDepthY']<=1.2+1e-9
    save('guide-verification.json',{'H_pre2_le_H0':True,'violationPoints':0,
        'maxDepthY':guide_stats['maxDepthY'],'limitY':1.2,'capEXACT':'min(0.5+0.7*progress, 0.08*H0*Y)'})
    o.astype('>f8').tofile(OUT/'O.f64be');hp2.astype('>f8').tofile(OUT/'Hpre2.f64be')
    shutil.copyfile(ROOT/'run/config/geogenesis-common.toml',OUT/'config-input.toml')
    shutil.copyfile(OLD/'log4j2.xml',OUT/'log4j2.xml')
    src,copies=engine_copies({40:(48,24,16),60:(72,36,24)})
    copy_report={pct:{'drops':v['drops'],'constantDiff':v['constantDiff']} for pct,v in copies.items()}
    cp=compile_all(copies)
    arms_of_run=[('A','engine-full','full','O'),('B','engine-full','full','Hpre2'),
                 ('C40','engine-red40','red40','O'),('D40','engine-red40','red40','Hpre2'),
                 ('C60','engine-red60','red60','O'),('D60','engine-red60','red60','Hpre2')]
    bench=run_bench(cp,arms_of_run,s,copies)
    timing={k:{'medianSec':bench[k]['medianSec'],'allSec':bench[k]['allSec'],
               'ratioToA':bench[k]['medianSec']/bench['A']['medianSec'],
               'engineClass':bench[k]['engineClass']} for k in ['A','B','C40','D40','C60','D60']}
    arms={k:np.fromfile(OUT/f'{k}.f64be',dtype='>f8').reshape(769,769).astype(float) for k in timing}
    arms['O']=o
    m,gap,pix=metrics(o,arms,d,guide,s)
    save('metrics.json',{'timing':timing,'perArm':m,'visualGap':gap,'guide':guide_stats,
        'engineCopy':copy_report,'bandPixels':pix,'benchProcessWallSeconds':bench['processWallSeconds'],
        'benchConfig':bench['config'],
        'comparison':'same JVM, same raster, 1 warmup +3 measured reps, median of measured; deterministic per rep (identical output hash)',
        'notProductionBenchmark':'single machine, cold cache, no GC tuning; ratios are indicative only'})
    plots(arms,['O','A','B','C40'],s,meta,guide)
    summary_plot(m,gap,timing,['A','B','C40','D40','C60','D60'])
    after=snapshot();changed=[k for k,v in before.items() if after.get(k)!=v]
    save('validation.json',{'protectedFiles':len(before),'changed':changed,
        'finite':all(np.isfinite(v).all() for v in arms.values()),'size':769,
        'elapsedSeconds':time.perf_counter()-t0,'production':False,'commit':False})
    assert not changed,changed
    print(json.dumps({'timing':timing,'gap':gap,'guide':guide_stats},indent=2),flush=True)


if __name__=='__main__':main()