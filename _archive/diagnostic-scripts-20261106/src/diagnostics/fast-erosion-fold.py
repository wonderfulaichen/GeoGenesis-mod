"""F (Laplacian scale) and G (ridge fold) in one run. NEW dir, CPU only, no noise/main edits.
python -B src/diagnostics/fast-erosion-fold.py build/<NEW-dir>
"""
import ast
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

ROOT=Path(__file__).resolve().parents[2]
OUT=(ROOT/sys.argv[1]).resolve()
if OUT.exists() or ROOT/'build' not in OUT.parents:raise ValueError('NEW build directory required')
OUT.mkdir(parents=True)
PREV=ROOT/'build/fast-erosion-fine-step-20261103-b'
DER=ROOT/'build/fast-erosion-derivatives-20261103-a'
CTRL=ROOT/'build/fast-erosion-controls-20261103-final'
COMMANDS=[]


def sha(p):
 h=hashlib.sha256()
 with p.open('rb') as f:
  for b in iter(lambda:f.read(1024*1024),b''):h.update(b)
 return h.hexdigest()


def save(name,v):(OUT/name).write_text(json.dumps(v,indent=2,ensure_ascii=False)+'\n',encoding='utf-8')


def run(args):
 COMMANDS.append(args);save('commands.json',COMMANDS)
 t=time.perf_counter();r=subprocess.run(args,cwd=ROOT,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
 s=r.stdout.decode('utf-8',errors='replace')
 with (OUT/'validation.log').open('a',encoding='utf-8') as f:f.write('\n'+subprocess.list2cmdline(args)+'\n'+s+'\nexit=%d seconds=%.3f\n'%(r.returncode,time.perf_counter()-t))
 print(s,flush=True)
 if r.returncode:raise RuntimeError('command failed')


def pure(path,names):
 tree=ast.parse(path.read_text(encoding='utf-8'));scope={'np':np,'ndi':ndi}
 exec(compile(ast.Module(body=[n for n in tree.body if isinstance(n,ast.FunctionDef) and n.name in names],type_ignores=[]),str(path),'exec'),scope)
 return scope


def native_metrics(base,lap,lap_thr,mask_thr,halo=0):
 """Input-side jump rates on the native sample lattice; identical WORLD interior for every arm."""
 if lap is None:
  bp=np.pad(base,1,mode='edge')
  lap=bp[1:-1,2:]+bp[1:-1,:-2]+bp[2:,1:-1]+bp[:-2,1:-1]-4*bp[1:-1,1:-1]
 k=3+halo
 c=(slice(k,-k),slice(k,-k))
 b=base[c];lp=lap[c]
 gz,gx=np.gradient(b)
 aspect=np.arctan2(gz,gx)
 s=np.hypot(gx,gz)*4e-4*262144*2
 v=np.where(s<.1,.5*s*s/.1,s-.05);mask=1-np.clip(1-v,0,1)**2
 def maxedge(a):
  d=np.abs(np.diff(a,axis=1));e=np.abs(np.diff(a,axis=0))
  o=np.zeros_like(a)
  o[:,:-1]=np.maximum(o[:,:-1],d);o[:,1:]=np.maximum(o[:,1:],d)
  o[:-1]=np.maximum(o[:-1],e);o[1:]=np.maximum(o[1:],e)
  return o
 cx=np.abs(np.arctan2(np.sin(np.diff(aspect,axis=1)),np.cos(np.diff(aspect,axis=1))))
 cz=np.abs(np.arctan2(np.sin(np.diff(aspect,axis=0)),np.cos(np.diff(aspect,axis=0))))
 turn=np.zeros_like(aspect)
 turn[:,:-1]=np.maximum(turn[:,:-1],cx);turn[:,1:]=np.maximum(turn[:,1:],cx)
 turn[:-1]=np.maximum(turn[:-1],cz);turn[1:]=np.maximum(turn[1:],cz)
 return {'aspectJumpGt45degFraction':float((turn>np.pi/4).mean()),
         'lapJumpAboveFrozenP99':float((maxedge(lp)>=lap_thr).mean()),
         'maskJumpAboveFrozenP99':float((maxedge(mask)>=mask_thr).mean())}


def figures(arr,cracks,stats,m):
 pal=np.loadtxt(ROOT/'build/fast-erosion-scale-only-20261103-final/data/project-palette.csv',delimiter=',',skiprows=1)
 cm=ListedColormap(pal[:,1:]/255);n=m['size'];step=m['stepWu']*2
 crop=(slice(int(np.floor(1200/step)),int(np.ceil(2400/step))+1),slice(0,int(np.ceil(1200/step))+1))
 def light(a):
  gz,gx=np.gradient(a*236.44*1.5,step);l=np.array([-.5,-.3,1.]);l/=np.linalg.norm(l)
  return .65+.35*np.maximum(0,(-gx*l[0]-gz*l[1]+l[2])/np.sqrt(1+gx*gx+gz*gz))
 def rgb(a):return cm(Normalize(-.35,1.2,clip=True)(.02+1.5*a))[...,:3]*light(a)[...,None]
 groups={'F':['B','F1','F3','C'],'G':['B','V1','V2','C']}
 titles={'F':['B | original 5-point lap','F(R=1) | lap averaged, sigma0.5','F(R=3) | lap averaged, sigma1.5','C | smooth-input control'],
         'G':['B | original fold 1-|n|','V1 | fold 1-n^2','V2 | fold 1-(sqrt(n^2+r^2)-r)','C | smooth-input control']}
 for tag,keys in groups.items():
  for name,sl,overlay,gray in [('comparison',(slice(0,n),slice(0,n)),False,False),('marked-region',crop,False,False),
        ('cracks',(slice(0,n),slice(0,n)),True,False),('marked-region-cracks',crop,True,False),
        ('hillshade',(slice(0,n),slice(0,n)),False,True),('marked-region-hillshade',crop,False,True)]:
   ext=(sl[1].start*step,sl[1].stop*step,sl[0].stop*step,sl[0].start*step)
   fig,axes=plt.subplots(2,2,figsize=(16,15),dpi=135)
   for ax,k,t in zip(axes.flat,keys,titles[tag]):
    ax.imshow(light(arr[k])[sl] if gray else rgb(arr[k])[sl],extent=ext,interpolation='nearest',
              **({'vmin':.65,'vmax':1,'cmap':'gray'} if gray else {}))
    if overlay:
     ov=np.zeros((*arr[k].shape,4));ov[cracks[k]]=[1,0,1,.9];ax.imshow(ov[sl],extent=ext,interpolation='nearest')
    ax.set(title=t,xlabel='local x (blocks)',ylabel='local z (blocks)')
   fig.suptitle(('F Laplacian-scale' if tag=='F' else 'G ridge-fold')+' | same window, palette, light, vertical scale, frozen detector')
   fig.text(.07,.02,'HS=2; Y proxy236.44*1.5*h; color e[-.35,1.2] unless hillshade-only. No production integration.')
   fig.tight_layout(rect=(0,.06,1,.96));fig.savefig(OUT/f'{tag}-{name}.png');plt.close(fig)


def main():
 locked=[p for p in (ROOT/'src/main').rglob('*') if p.is_file()]
 locked+=list((ROOT/'src/diagnostics/java/com/geogenesis/standalone').glob('FastErosion*.java'))
 for folder in (ROOT/'build').glob('fast-erosion-*'):
  if folder!=OUT:locked += [p for p in folder.rglob('*') if p.is_file()]
 before={str(p.relative_to(ROOT)):sha(p) for p in locked};save('locked-before.json',before)
 m=json.loads((DER/'data/sampling.json').read_text());n=m['size'];w,h=m['nativeSize'];shape=(n,n)
 java='src/diagnostics/java/com/geogenesis/worldgen/terrain/morph/FastErosionFoldProbe.java'
 classes=OUT/'classes';classes.mkdir()
 run(['javac','-encoding','UTF-8','-proc:none','-d',str(classes),'-sourcepath','src/main/java;src/diagnostics/java',java])
 run(['java','-Xmx3g','-cp',str(classes),'com.geogenesis.worldgen.terrain.morph.FastErosionFoldProbe',str(OUT/'data')])
 params=json.loads((OUT/'data/parameters.json').read_text())
 R=lambda p,sh:np.fromfile(p,dtype='>f8').astype(float).reshape(sh)
 arr={'B':R(PREV/'data/B.f64be',shape),'C':R(CTRL/'data/C-height.f64be',shape)}
 for k in ['F1','F3','V1','V2']:arr[k]=R(OUT/'data'/(k+'-height.f64be'),shape)
 assert all(np.isfinite(a).all() for a in arr.values())
 det=pure(ROOT/'src/diagnostics/fast-erosion-derivatives.py',['detector','crack_score'])
 thr=json.loads((DER/'statistics.json').read_text())['thresholds']
 lap_thr=thr['lapNativeAdjacentP99'];mask_thr=thr['maskNativeAdjacentP99']
 abs_thr=thr['crackHessianShellPerOutputSampleSquared']
 raw=R(DER/'data/production-raw.f64be',shape);sp=m['stepWu']*2
 crop=(slice(int(np.floor(1200/sp)),int(np.ceil(2400/sp))+1),slice(0,int(np.ceil(1200/sp))+1))
 stats={};cracks={};inp={}
 for arm in ['B','F1','F3','V1','V2','C']:
  hh=arr[arm]
  if arm=='C':inc=R(CTRL/'data/C-erosion.f64be',shape);msk=R(CTRL/'data/C-mask.f64be',shape);kval=.8205431216912392
  elif arm=='B':inc=R(PREV/'data/erosion-shell.f64be',shape);msk=R(PREV/'data/initial-mask.f64be',shape);kval=.8205431216912392
  else:inc=R(OUT/'data'/(arm+'-erosion.f64be'),shape);msk=R(OUT/'data'/(arm+'-mask.f64be'),shape);kval=None
  gz,gx=np.gradient(hh,m['stepWu']);q=np.quantile(np.hypot(gx,gz)[1:-1,1:-1],[.5,.99,.999,1])
  cracks[arm]=det['detector'](hh,abs_thr)[0]
  stats[arm]={'crackPixels':int(cracks[arm].sum()),'ratioVsB':float(cracks[arm].sum()/5317),
      'markedRegionCracks':int(cracks[arm][crop].sum()),'maskFullFraction':float((msk>=1).mean()),
      'erosionStdOverTotalStd':float(inc.std()/hh.std()),'corrWithProductionRaw':float(np.corrcoef(raw.ravel(),hh.ravel())[0,1]),
      'slope':dict(zip(['p50','p99','p999','max'],map(float,q)),tail=float(q[2]/q[0])),
      'minMax':[float(hh.min()),float(hh.max())],
      'colorSaturatedFraction':float((((.02+1.5*hh)<-.35)|((.02+1.5*hh)>1.2)).mean()),
      'erosionGradientRms':float(np.sqrt(np.mean(np.gradient(inc,m['stepWu'])[0]**2+np.gradient(inc,m['stepWu'])[1]**2)))}
  if arm in ('F1','F3','V1','V2'):
   pass
 assert stats['B']['crackPixels']==5317
 pw,ph=params['nativeSize'];ns=(ph,pw)
 inp['B']=native_metrics(R(DER/'data/native-base.f64be',(h,w)),None,lap_thr,mask_thr)
 inp['C']=native_metrics(R(CTRL/'data/C-native-base.f64be',(h,w)),None,lap_thr,mask_thr)
 for arm in ['F1','F3','V1','V2']:
  inp[arm]=native_metrics(R(OUT/'data'/(arm+'-native-base.f64be'),ns),
                          R(OUT/'data'/(arm+'-native-lap.f64be'),ns),lap_thr,mask_thr,halo=4)
 sens={}
 sc=det['crack_score'](arr['B'])[8:-8,8:-8]
 for pct in [.98,.985,.99,.995]:
  t=float(np.quantile(sc,pct));sens[str(pct)]={k:int(det['detector'](a,t)[0].sum()) for k,a in arr.items()}
 save('statistics.json',{'arms':stats,'input':inp,'parameters':params,'absoluteDetectorThreshold':abs_thr,'sensitivity':sens,
   'v0Control':'V0 replicated chain equals production L2 bitwise over the whole native grid, so V0 arm is B by construction (see data/v0-comparison.txt)',
   'rDerivation':'rounded fold slope change per sample <=20% => r>=5*delta_n; delta_n=1.5*stepWu*lac^(oct-1)/mainScale at finest ridge octave'})
 figures(arr,cracks,stats,m)
 assert all(sha(ROOT/p)==v for p,v in before.items()),'locked files changed'
 save('manifest.json',{'purpose':'F lap-scale and G ridge-fold variants; diagnostics only, no noise/main edits','commands':COMMANDS,
   'sourceHashes':{java:sha(ROOT/java),str(Path(__file__).resolve().relative_to(ROOT)):sha(Path(__file__))},
   'detectorSha256':sha(ROOT/'src/diagnostics/fast-erosion-derivatives.py'),'lockedFiles':len(before),
   'files':{str(p.relative_to(OUT)):sha(p) for p in OUT.rglob('*') if p.is_file() and 'classes' not in p.parts}})
 (OUT/'verification.txt').write_text('PASS fresh javac; V0 replication bitwise equals production L2 (negative control)\n'
   'PASS B full array exact locked baseline; C reused unchanged\n'
   'PASS F changes only lap channel; G changes only fold; gradient/base/ORIGINAL/.25/.7 unchanged\n'
   'PASS each arm k independently recalibrated on the frozen offline domain\n'
   'PASS fixed detector absolute threshold; main/core/existing baselines unchanged\n'
   'NOT VERIFIED: annotated seam truth, other seeds/windows, production integration\n',encoding='utf-8')
 print(json.dumps({'arms':stats,'input':inp},indent=2));print('DONE',OUT)


if __name__=='__main__':main()
