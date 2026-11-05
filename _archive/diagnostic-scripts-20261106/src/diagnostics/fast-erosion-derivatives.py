"""Frozen crack detector + S1/S2/S3 derivative-only experiment. CPU and fresh javac.
Usage from MDK: python -B src/diagnostics/fast-erosion-derivatives.py build/<NEW-dir>
Cracks here are an operational high-curvature/straight-support proxy, not labelled ground truth.
"""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import time
import numpy as np
from scipy import ndimage as ndi
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.colors import ListedColormap, Normalize

ROOT = Path(__file__).resolve().parents[2]
OUT = (ROOT/sys.argv[1]).resolve()
if OUT.exists() or ROOT/'build' not in OUT.parents:
    raise ValueError('NEW build directory required')
OUT.mkdir(parents=True)
PREV = ROOT/'build/fast-erosion-fine-step-20261103-b'
COMMANDS = []


def sha(p):
    h = hashlib.sha256()
    with p.open('rb') as f:
        for b in iter(lambda:f.read(1024*1024), b''): h.update(b)
    return h.hexdigest()


def save(name,v):
    (OUT/name).write_text(json.dumps(v,indent=2,ensure_ascii=False)+'\n',encoding='utf-8')


def run(args):
    COMMANDS.append(args);save('commands.json',COMMANDS)
    t=time.perf_counter();p=subprocess.run(args,cwd=ROOT,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
    text=p.stdout.decode('utf-8',errors='replace')
    with (OUT/'validation.log').open('a',encoding='utf-8') as f:
        f.write('\nCOMMAND '+subprocess.list2cmdline(args)+'\n'+text+'\nexit=%d seconds=%.3f\n'%(p.returncode,time.perf_counter()-t))
    print(text[-5000:],flush=True)
    if p.returncode:raise RuntimeError('command failed')


def read(p,shape):
    a=np.fromfile(p,dtype='>f8').astype(float).reshape(shape)
    assert np.isfinite(a).all(),p
    return a


def q(a):
    return dict(zip(['p50','p90','p99','p999','max'],map(float,np.quantile(a,[.5,.9,.99,.999,1]))))


def crack_score(h):
    # Native output-grid finite differences, no per-arm normalization or shading.
    xx=ndi.convolve(h,np.array([[1.,-2.,1.]]),mode='nearest')
    zz=ndi.convolve(h,np.array([[1.],[-2.],[1.]]),mode='nearest')
    xz=ndi.convolve(h,np.array([[1.,0.,-1.],[0.,0.,0.],[-1.,0.,1.]])/4,mode='nearest')
    v=np.sqrt((xx-zz)**2+4*xz*xz)
    a=(xx+zz+v)/2;b=(xx+zz-v)/2
    return np.maximum(abs(a),abs(b))


def detector(h,threshold):
    score=crack_score(h);candidate=score>threshold
    # Linear support in a 3-pixel-wide corridor, 13-pixel length, 8 orientations.
    corridor=ndi.maximum_filter(candidate,size=3).astype(float)
    support=np.zeros(h.shape)
    for angle in np.arange(8)*np.pi/8:
        kernel=np.zeros((13,13))
        for d in range(-6,7):
            kernel[6+int(round(d*np.sin(angle))),6+int(round(d*np.cos(angle)))]=1
        hits=ndi.convolve(corridor,kernel,mode='constant')/kernel.sum()
        support=np.maximum(support,hits)
    result=candidate&(support>=.80)
    result[:8]=False;result[-8:]=False;result[:,:8]=False;result[:,-8:]=False
    return result,score


def native_jumps(aspect,lap,mask):
    rawx=abs(np.diff(aspect,axis=1));rawz=abs(np.diff(aspect,axis=0))
    cx=abs(np.arctan2(np.sin(np.diff(aspect,axis=1)),np.cos(np.diff(aspect,axis=1))))
    cz=abs(np.arctan2(np.sin(np.diff(aspect,axis=0)),np.cos(np.diff(aspect,axis=0))))
    def gather(dx,dz):
        result=np.zeros(aspect.shape)
        result[:,:-1]=np.maximum(result[:,:-1],dx);result[:,1:]=np.maximum(result[:,1:],dx)
        result[:-1]=np.maximum(result[:-1],dz);result[1:]=np.maximum(result[1:],dz)
        return result
    return {'aspect-raw-jump':gather(rawx,rawz),'aspect-circular-jump':gather(cx,cz),
            'aspect-wrap':gather(rawx>np.pi,rawz>np.pi).astype(bool),
            'lap-jump':gather(abs(np.diff(lap,axis=1)),abs(np.diff(lap,axis=0))),
            'mask-jump':gather(abs(np.diff(mask,axis=1)),abs(np.diff(mask,axis=0)))}


def overlap(cracks,event):
    exact=event;tol=ndi.maximum_filter(event,size=3)
    den=int(cracks.sum())
    return {'exactOverlapFraction':float((cracks&exact).sum()/max(1,den)),
            'withinOneOutputPixelFraction':float((cracks&tol).sum()/max(1,den)),
            'backgroundWithinOnePixelFraction':float(tol[8:-8,8:-8].mean()),
            'eventPixelFraction':float(exact.mean())}


def plots(arr,diag,cracks,m):
    palette=np.loadtxt(ROOT/'build/fast-erosion-scale-only-20261103-final/data/project-palette.csv',delimiter=',',skiprows=1)
    cmap=ListedColormap(palette[:,1:]/255);step=m['stepWu']*2;n=m['size'];span=m['spanWu']*2
    def rgb(a):
        gz,gx=np.gradient(a*236.44*1.5,step)
        l=np.array([-.5,-.3,1.]);l/=np.linalg.norm(l)
        light=.65+.35*np.maximum(0,(-gx*l[0]-gz*l[1]+l[2])/np.sqrt(1+gx*gx+gz*gz))
        return cmap(Normalize(-.35,1.2,clip=True)(.02+1.5*a))[...,:3]*light[...,None]
    keys=['A','B','S1','S2','S3']
    crop=(slice(int(np.floor(1200/step)),int(np.ceil(2400/step))+1),slice(0,int(np.ceil(1200/step))+1))
    for fname,sl in [('comparison.png',(slice(0,n),slice(0,n))),('marked-region-comparison.png',crop)]:
        ext=(sl[1].start*step,sl[1].stop*step,sl[0].stop*step,sl[0].start*step)
        fig,axes=plt.subplots(2,3,figsize=(20,13),dpi=135)
        for ax,key in zip(axes.flat,keys):
            ax.imshow(rgb(arr[key])[sl],extent=ext,interpolation='nearest')
            ax.set(title=key,xlabel='local x (blocks)',ylabel='local z (blocks)')
        axes.flat[-1].axis('off')
        fig.suptitle('Derivative-only experiment | identical L2 noise, spacing, ORIGINAL, palette/light/vertical scale')
        fig.text(.07,.025,'Fixed e [-.35,1.2], HS=2, Y proxy236.44*1.5*h; B=current, S1=central +/-1 (negative control), S2=9-point lap, S3=direction-only smoothing.\nNo production integration or actual Minecraft Y. Rectangle follows requested local block coordinates, no marked image was attached in this turn.')
        fig.tight_layout(rect=(0,.065,1,.96));fig.savefig(OUT/fname);plt.close(fig)
    # Diagnostic values at exact native neighbouring samples, sampled at actual warped coordinates.
    panels=[('aspect','twilight',-np.pi,np.pi),('aspect-raw-jump','magma',0,2*np.pi),
            ('aspect-circular-jump','magma',0,np.pi),('lap-jump','magma',0,None),
            ('mask-jump','magma',0,1),('cracks','gray',0,1)]
    for fname,sl in [('diagnostics.png',(slice(0,n),slice(0,n))),('marked-region-diagnostics.png',crop)]:
        ext=(sl[1].start*step,sl[1].stop*step,sl[0].stop*step,sl[0].start*step)
        fig,axes=plt.subplots(2,3,figsize=(19,13),dpi=135)
        for ax,(key,cm,lo,hi) in zip(axes.flat,panels):
            data=cracks['B'].astype(float) if key=='cracks' else diag[key]
            vmax=float(np.quantile(data,.995)) if hi is None else hi
            im=ax.imshow(data[sl],extent=ext,cmap=cm,vmin=lo,vmax=vmax,interpolation='nearest')
            ax.set(title=key,xlabel='local x (blocks)',ylabel='local z (blocks)');fig.colorbar(im,ax=ax,fraction=.035)
        fig.suptitle('Current B: adjacent ONE-native-sample derivative diagnostics; raw wrap distinguished from circular difference')
        fig.tight_layout(rect=(0,0,1,.96));fig.savefig(OUT/fname);plt.close(fig)
    fig,axes=plt.subplots(1,4,figsize=(23,6),dpi=135)
    ext=(crop[1].start*step,crop[1].stop*step,crop[0].stop*step,crop[0].start*step)
    for ax,key in zip(axes,['B','S1','S2','S3']):
        ax.imshow(rgb(arr[key])[crop],extent=ext,interpolation='nearest')
        overlay=np.zeros((*cracks[key].shape,4));overlay[cracks[key]]=[1,0,1,.85]
        ax.imshow(overlay[crop],extent=ext,interpolation='nearest')
        ax.set(title=key+' | fixed detector overlay',xlabel='local x (blocks)',ylabel='local z (blocks)')
    fig.tight_layout();fig.savefig(OUT/'marked-region-cracks.png');plt.close(fig)
    fig,axes=plt.subplots(1,3,figsize=(19,7),dpi=135)
    for ax,key in zip(axes,['S1','S2','S3']):
        im=ax.imshow((arr[key]-arr['B'])[crop],extent=ext,cmap='RdBu_r',vmin=-.1,vmax=.1,interpolation='nearest')
        ax.set(title=key+' - current B',xlabel='local x (blocks)',ylabel='local z (blocks)')
    fig.colorbar(im,ax=axes,fraction=.02,label='fixed shell delta h +/- .1')
    fig.savefig(OUT/'marked-region-differences.png');plt.close(fig)


def main():
    locked=[p for p in (ROOT/'src/main').rglob('*') if p.is_file()]
    locked+=list((ROOT/'src/diagnostics/java/com/geogenesis/standalone').glob('FastErosion*.java'))
    for folder in (ROOT/'build').glob('fast-erosion-*'):
        if folder!=OUT:locked += [p for p in folder.rglob('*') if p.is_file()]
    before={str(p.relative_to(ROOT)):sha(p) for p in locked};save('locked-before.json',before)
    classes=OUT/'classes';classes.mkdir()
    java='src/diagnostics/java/com/geogenesis/worldgen/terrain/morph/FastErosionDerivativeProbe.java'
    run(['javac','-encoding','UTF-8','-proc:none','-d',str(classes),'-sourcepath','src/main/java;src/diagnostics/java',java])
    run(['java','-Xmx3g','-cp',str(classes),'com.geogenesis.worldgen.terrain.morph.FastErosionDerivativeProbe',str(OUT/'data')])
    m=json.loads((OUT/'data/sampling.json').read_text());n=m['size'];shape=(n,n)
    arr={'A':read(OUT/'data/A.f64be',shape),'B':read(PREV/'data/B.f64be',shape)}
    assert sha(OUT/'data/A.f64be')==sha(PREV/'data/A.f64be'),'A drift'
    for key in ['S1','S2','S3']:arr[key]=read(OUT/('data/'+key+'-height.f64be'),shape)
    assert sha(OUT/'data/S1-height.f64be')==sha(PREV/'data/B.f64be'),'S1 MUST reproduce current B bitwise'
    native_shape=tuple(reversed(m['nativeSize']))
    aspect=read(OUT/'data/native-aspect.f64be',native_shape)
    lap=read(OUT/'data/native-lap.f64be',native_shape);mask=read(OUT/'data/native-mask.f64be',native_shape)
    jumps=native_jumps(aspect,lap,mask)
    sx=read(OUT/'data/S1-sample-x.f64be',shape).astype(int)-m['nativeOrigin'][0]
    sz=read(OUT/'data/S1-sample-z.f64be',shape).astype(int)-m['nativeOrigin'][1]
    diag={key:data[sz,sx] for key,data in jumps.items()};diag['aspect']=aspect[sz,sx]
    # Freeze detector threshold from current B once, not each variant.
    threshold=float(np.quantile(crack_score(arr['B'])[8:-8,8:-8],.985))
    cracks={};scores={}
    for key in ['B','S1','S2','S3']:
        cracks[key],scores[key]=detector(arr[key],threshold)
        np.save(OUT/(key+'-cracks.npy'),cracks[key],allow_pickle=False)
    # Independent event thresholds, reported explicitly. Both absolute and top-percentile controls.
    lap_thr=float(np.quantile(jumps['lap-jump'][8:-8,8:-8],.99))
    mask_thr=float(np.quantile(jumps['mask-jump'][8:-8,8:-8],.99))
    events={'aspectRawWrap':diag['aspect-wrap'], 'aspectCircularGt45deg':diag['aspect-circular-jump']>np.pi/4,
            'lapTop1pct':diag['lap-jump']>=lap_thr,'maskTop1pct':diag['mask-jump']>=mask_thr,
            'maskAbsGt0_2':diag['mask-jump']>.2}
    attribution={key:overlap(cracks['B'],event) for key,event in events.items()}
    # Spatially shifted controls help distinguish enrichment from simply common events.
    for key,event in events.items():
        attribution[key]['shiftedControlMean']=float(np.mean([overlap(cracks['B'],np.roll(event,(j,i),(0,1)))['withinOneOutputPixelFraction'] for j,i in [(31,47),(-43,29),(61,-37)]]))
    stats={};prod=read(OUT/'data/production-raw.f64be',shape)
    for key in ['B','S1','S2','S3']:
        h=arr[key]
        if key=='B':
            inc=read(PREV/'data/erosion-shell.f64be',shape);mk=read(PREV/'data/initial-mask.f64be',shape)
            kval=json.loads((PREV/'calibration/calibration.json').read_text())['k']
        else:
            inc=read(OUT/('data/'+key+'-erosion.f64be'),shape);mk=read(OUT/('data/'+key+'-mask.f64be'),shape)
            kval=m['k'][['S1','S2','S3'].index(key)]
        gz,gx=np.gradient(h,m['stepWu']);g=np.hypot(gx,gz)[1:-1,1:-1];gq=q(g)
        gq['tailP999overP50']=gq['p999']/gq['p50']
        stats[key]={'k':kval,'crackPixels':int(cracks[key].sum()),'maskFullFraction':float((mk>=1).mean()),
                    'erosionStdOverTotalStd':float(inc.std()/h.std()),'corrWithProductionRaw':float(np.corrcoef(h.ravel(),prod.ravel())[0,1]),
                    'slopeHperWu':gq,'heightRange':[float(h.min()),float(h.max())],
                    'rmsVsB':float(np.sqrt(np.mean((h-arr['B'])**2))),
                    'detectorScore':q(scores[key][8:-8,8:-8])}
    save('statistics.json',{'arms':stats,'attribution':attribution,
        'diagnostics':{key:q(value.astype(float)) for key,value in diag.items()},
        'thresholds':{'crackHessianShellPerOutputSampleSquared':threshold,'lapNativeAdjacentP99':lap_thr,'maskNativeAdjacentP99':mask_thr},
        'detector':'max absolute Hessian eigenvalue > current B P98.5; at least80% 13-pixel straight support in 3px corridor, 8 directions; exclude8px border. Same fixed threshold all arms. Proxy may count natural sharp ridges.',
        'causality':'Overlap/enrichment is association, not proof. Raw atan2 wrap is not physical angle discontinuity; circular >45deg is separate.'})
    plots(arr,diag,cracks,m)
    assert all(sha(ROOT/p)==v for p,v in before.items()),'locked file changed'
    save('manifest.json',{'purpose':'derivative-only diagnostics, no production integration','commands':COMMANDS,'sampling':m,
        'sourceHashes':{java:sha(ROOT/java),str(Path(__file__).resolve().relative_to(ROOT)):sha(Path(__file__))},
        'baseReference':str(PREV),'sourceAndBaselineLockedCount':len(before),
        'S1ExactCurrentB':True,'AExactProduction':True,
        'files':{str(p.relative_to(OUT)):sha(p) for p in OUT.rglob('*') if p.is_file() and 'classes' not in p.parts}})
    (OUT/'verification.txt').write_text('PASS fresh javac, production A full SHA exact, S1 current B full SHA exact\n'
        'PASS same L2 noise/step/f=1/ORIGINAL/.25/.7; each k recomputed independently offline\n'
        'PASS current derivative is ALREADY central +/-1; S1 is a bitwise negative control\n'
        'PASS no main/prototype/existing baseline changes; fixed detector threshold for all arms\n'
        'NOT VERIFIED: true annotated crack ground truth, all-octave masks, other seeds/windows, production blocks\n',encoding='utf-8')
    print(json.dumps({'arms':stats,'attribution':attribution},indent=2));print('DONE',OUT)


if __name__=='__main__':main()
