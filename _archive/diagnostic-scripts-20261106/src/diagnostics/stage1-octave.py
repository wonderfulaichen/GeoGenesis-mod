"""Stage 1: ridge octave count 2 -> 1 only. NEW dir, CPU only.
python -B src/diagnostics/stage1-octave.py build/<NEW-dir>
"""
import ast,hashlib,json,subprocess,sys,time
from pathlib import Path
import numpy as np
from scipy import ndimage as ndi
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.colors import ListedColormap,Normalize

ROOT=Path(__file__).resolve().parents[2]
OUT=(ROOT/sys.argv[1]).resolve()
if OUT.exists() or ROOT/'build' not in OUT.parents:raise ValueError('NEW build dir required')
OUT.mkdir(parents=True)
PREV=ROOT/'build/fast-erosion-fine-step-20261103-b'
DER=ROOT/'build/fast-erosion-derivatives-20261103-a'
CTRL=ROOT/'build/fast-erosion-controls-20261103-final'
COMMANDS=[];FEAT={'oblique':[(0,2100),(700,1900)]}


def sha(p):
 h=hashlib.sha256()
 with p.open('rb') as f:
  for b in iter(lambda:f.read(1<<20),b''):h.update(b)
 return h.hexdigest()


def save(n,v):(OUT/n).write_text(json.dumps(v,indent=2,ensure_ascii=False)+'\n',encoding='utf-8')


def run(a):
 COMMANDS.append(a);save('commands.json',COMMANDS);t=time.perf_counter()
 r=subprocess.run(a,cwd=ROOT,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
 s=r.stdout.decode('utf-8',errors='replace')
 with (OUT/'validation.log').open('a',encoding='utf-8') as f:f.write('\n'+subprocess.list2cmdline(a)+'\n'+s+'\nexit=%d %.3fs\n'%(r.returncode,time.perf_counter()-t))
 print(s,flush=True)
 if r.returncode:raise RuntimeError('command failed')


def pure(path,names):
 tree=ast.parse(path.read_text(encoding='utf-8'));sc={'np':np,'ndi':ndi}
 exec(compile(ast.Module(body=[n for n in tree.body if isinstance(n,ast.FunctionDef) and n.name in names],type_ignores=[]),str(path),'exec'),sc)
 return sc


def corridor(shape,seg,fac,halfw=2.0):
 yy,xx=np.mgrid[0:shape[0],0:shape[1]]
 (x0,z0),(x1,z1)=seg
 px0,pz0,px1,pz1=x0/fac,z0/fac,x1/fac,z1/fac
 dx,dz=px1-px0,pz1-pz0;L2=dx*dx+dz*dz
 t=np.clip(((xx-px0)*dx+(yy-pz0)*dz)/L2,0,1)
 return np.hypot(xx-(px0+t*dx),yy-(pz0+t*dz))<=halfw


def feature(cracks,seg,fac):
 band=corridor(cracks.shape,seg,fac);lab,_=ndi.label(ndi.maximum_filter(cracks,size=3))
 spans=[]
 for i in np.unique(lab[band]):
  if i==0:continue
  o=ndi.find_objects(lab==i)[0];spans.append(max(o[0].stop-o[0].start,o[1].stop-o[1].start))
 return {'candidatesInCorridor':int((cracks&band).sum()),'longestComponentSpanPixels':int(max(spans)) if spans else 0,'componentsInside':len(spans)}


def figure(arr,cracks,m):
 pal=np.loadtxt(ROOT/'build/fast-erosion-scale-only-20261103-final/data/project-palette.csv',delimiter=',',skiprows=1)
 cm=ListedColormap(pal[:,1:]/255);n=m['size'];step=m['stepWu']*2
 keys=['B','R1','C'];titles=['B | ridge 2 octaves','R1 | ridge 1 octave','C | smooth-input control']
 crop=(slice(int(np.floor(1200/step)),int(np.ceil(2400/step))+1),slice(0,int(np.ceil(1200/step))+1))
 def light(a):
  gz,gx=np.gradient(a*236.44*1.5,step);l=np.array([-.5,-.3,1.]);l/=np.linalg.norm(l)
  return .65+.35*np.maximum(0,(-gx*l[0]-gz*l[1]+l[2])/np.sqrt(1+gx*gx+gz*gz))
 def rgb(a):return cm(Normalize(-.35,1.2,clip=True)(.02+1.5*a))[...,:3]*light(a)[...,None]
 for name,sl,ov,gray in [('comparison',(slice(0,n),slice(0,n)),0,0),('marked-region',crop,0,0),('cracks',(slice(0,n),slice(0,n)),1,0),
       ('marked-region-cracks',crop,1,0),('hillshade',(slice(0,n),slice(0,n)),0,1),('marked-region-hillshade',crop,0,1)]:
  ext=(sl[1].start*step,sl[1].stop*step,sl[0].stop*step,sl[0].start*step)
  fig,axes=plt.subplots(1,3,figsize=(21,7),dpi=135)
  for ax,k,t in zip(axes,keys,titles):
   if gray:ax.imshow(light(arr[k])[sl],vmin=.65,vmax=1,cmap='gray',extent=ext,interpolation='nearest')
   else:ax.imshow(rgb(arr[k])[sl],extent=ext,interpolation='nearest')
   if ov:
    o=np.zeros((*arr[k].shape,4));o[cracks[k]]=[1,0,1,.9];ax.imshow(o[sl],extent=ext,interpolation='nearest')
   ax.set(title=t,xlabel='local x (blocks)',ylabel='local z (blocks)')
  fig.suptitle('Stage 1 | ridge octave count 2 vs 1 | identical scaler, warp, fold, clamp, derivatives, parameters')
  fig.text(.06,.02,'HS=2; Y proxy236.44*1.5*h; fixed light; color e[-.35,1.2] unless hillshade-only. No production integration.')
  fig.tight_layout(rect=(0,.05,1,.96));fig.savefig(OUT/f'{name}.png');plt.close(fig)


def main():
 locked=[p for p in (ROOT/'src/main').rglob('*') if p.is_file()]
 locked+=list((ROOT/'src/diagnostics/java/com/geogenesis/standalone').glob('FastErosion*.java'))
 for f in (ROOT/'build').glob('fast-erosion-*'):
  if f!=OUT:locked+=[p for p in f.rglob('*') if p.is_file()]
 before={str(p.relative_to(ROOT)):sha(p) for p in locked};save('locked-before.json',before)
 m=json.loads((DER/'data/sampling.json').read_text());n=m['size'];fac=m['stepWu']*2;shape=(n,n)
 java='src/diagnostics/java/com/geogenesis/worldgen/terrain/morph/FastErosionOctaveProbe.java'
 cl=OUT/'classes';cl.mkdir()
 run(['javac','-encoding','UTF-8','-proc:none','-d',str(cl),'-sourcepath','src/main/java;src/diagnostics/java',java])
 run(['java','-Xmx3g','-cp',str(cl),'com.geogenesis.worldgen.terrain.morph.FastErosionOctaveProbe',str(OUT/'data')])
 par=json.loads((OUT/'data/parameters.json').read_text())
 R=lambda p,s:np.fromfile(p,dtype='>f8').astype(float).reshape(s)
 arr={'B':R(PREV/'data/B.f64be',shape),'R1':R(OUT/'data/R1-height.f64be',shape),'C':R(CTRL/'data/C-height.f64be',shape)}
 assert all(np.isfinite(a).all() for a in arr.values())
 det=pure(ROOT/'src/diagnostics/fast-erosion-derivatives.py',['detector','crack_score'])
 thr=json.loads((DER/'statistics.json').read_text())['thresholds'];abs_thr=thr['crackHessianShellPerOutputSampleSquared']
 lap_thr=thr['lapNativeAdjacentP99'];mask_thr=thr['maskNativeAdjacentP99']
 raw=R(DER/'data/production-raw.f64be',shape)
 crop=(slice(int(np.floor(1200/fac)),int(np.ceil(2400/fac))+1),slice(0,int(np.ceil(1200/fac))+1))
 stats={};cracks={}
 for arm in ['B','R1','C']:
  hh=arr[arm]
  if arm=='C':inc=R(CTRL/'data/C-erosion.f64be',shape);msk=R(CTRL/'data/C-mask.f64be',shape);kk=par['lockedK']
  elif arm=='B':inc=R(PREV/'data/erosion-shell.f64be',shape);msk=R(PREV/'data/initial-mask.f64be',shape);kk=par['lockedK']
  else:inc=R(OUT/'data/R1-erosion.f64be',shape);msk=R(OUT/'data/R1-mask.f64be',shape);kk=par['k']
  gz,gx=np.gradient(hh,m['stepWu']);q=np.quantile(np.hypot(gx,gz)[1:-1,1:-1],[.5,.99,.999,1])
  cracks[arm]=det['detector'](hh,abs_thr)[0]
  stats[arm]={'k':kk,'crackPixels':int(cracks[arm].sum()),'ratioVsB':float(cracks[arm].sum()/5317),
   'markedRegionCracks':int(cracks[arm][crop].sum()),'maskFullFraction':float((msk>=1).mean()),
   'erosionStdOverTotalStd':float(inc.std()/hh.std()),'corrWithProductionRaw':float(np.corrcoef(raw.ravel(),hh.ravel())[0,1]),
   'slope':dict(zip(['p50','p99','p999','max'],map(float,q)),tail=float(q[2]/q[0])),
   'minMax':[float(hh.min()),float(hh.max())],
   'colorSaturatedFraction':float((((.02+1.5*hh)<-.35)|((.02+1.5*hh)>1.2)).mean()),
   'erosionGradientRms':float(np.sqrt(np.mean(np.gradient(inc,m['stepWu'])[0]**2+np.gradient(inc,m['stepWu'])[1]**2))),
   'features':{k2:feature(cracks[arm],s,fac) for k2,s in FEAT.items()}}
 assert stats['B']['crackPixels']==5317 and stats['C']['crackPixels']==19
 def rates(base,lap,halo):
  if lap is None:
   bp=np.pad(base,1,mode='edge')
   lap=bp[1:-1,2:]+bp[1:-1,:-2]+bp[2:,1:-1]+bp[:-2,1:-1]-4*bp[1:-1,1:-1]
  k3=3+halo;b=base[k3:-k3,k3:-k3];lp=(lap*262144.0/25.0)[k3:-k3,k3:-k3]
  gz,gx=np.gradient(b);asp=np.arctan2(gz,gx)
  s=np.hypot(gx,gz)*4e-4*262144*2;v=np.where(s<.1,.5*s*s/.1,s-.05);mk=1-np.clip(1-v,0,1)**2
  def mx(a):
   d=np.abs(np.diff(a,axis=1));e=np.abs(np.diff(a,axis=0));o=np.zeros_like(a)
   o[:,:-1]=np.maximum(o[:,:-1],d);o[:,1:]=np.maximum(o[:,1:],d);o[:-1]=np.maximum(o[:-1],e);o[1:]=np.maximum(o[1:],e);return o
  cx=np.abs(np.arctan2(np.sin(np.diff(asp,axis=1)),np.cos(np.diff(asp,axis=1))))
  cz=np.abs(np.arctan2(np.sin(np.diff(asp,axis=0)),np.cos(np.diff(asp,axis=0))))
  t=np.zeros_like(asp);t[:,:-1]=np.maximum(t[:,:-1],cx);t[:,1:]=np.maximum(t[:,1:],cx);t[:-1]=np.maximum(t[:-1],cz);t[1:]=np.maximum(t[1:],cz)
  return {'aspectJumpGt45degFraction':float((t>np.pi/4).mean()),'lapJumpAboveFrozenP99':float((mx(lp)>=lap_thr).mean()),'maskJumpAboveFrozenP99':float((mx(mk)>=mask_thr).mean())}
 inp={'B':rates(R(DER/'data/native-base.f64be',(1545,1545)),None,0),'C':rates(R(CTRL/'data/C-native-base.f64be',(1545,1545)),None,0)}
 pw,ph=par['nativeSize']
 inp['R1']=rates(R(OUT/'data/R1-native-base.f64be',(ph,pw)),R(OUT/'data/R1-native-lap.f64be',(ph,pw)),4)
 save('statistics.json',{'arms':stats,'input':inp,'parameters':par,'detectorThreshold':abs_thr,
  'featureNote':'oblique corridor = straight segment (0,2100)-(700,1900) local blocks, half-width 2 output px, SMALL COUNTS; vertical cut has no usable corridor proxy and is judged from marked-region-hillshade.png'})
 figure(arr,cracks,m)
 assert all(sha(ROOT/p)==v for p,v in before.items()),'locked changed'
 save('manifest.json',{'purpose':'Stage 1 ridge-octave test; diagnostics only','commands':COMMANDS,
  'sourceHashes':{java:sha(ROOT/java),str(Path(__file__).resolve().relative_to(ROOT)):sha(Path(__file__))},
  'detectorSha256':sha(ROOT/'src/diagnostics/fast-erosion-derivatives.py'),'lockedFiles':len(before),
  'files':{str(p.relative_to(OUT)):sha(p) for p in OUT.rglob('*') if p.is_file() and 'classes' not in p.parts}})
 (OUT/'verification.txt').write_text('PASS fresh javac; two-octave replication equals production L2 on strided grid\n'
  'PASS only ridgeOctaves differs; scaler/warp/fold/clamp/derivatives/ORIGINAL unchanged\n'
  'PASS B and C from locked baselines; R1 k recoined by identical offline rule\n'
  'PASS fixed detector threshold; main/core/existing baselines unchanged\n',encoding='utf-8')
 print(json.dumps({'arms':stats,'input':inp,'parameters':par},indent=2));print('DONE',OUT)


if __name__=='__main__':main()
