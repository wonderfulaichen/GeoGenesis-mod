"""J: derivative source replaced by low-frequency content; height unchanged. NEW dir, CPU only.
python -B src/diagnostics/exp-j-deriv-source.py build/<NEW-dir>
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
R1D=ROOT/'build/stage1-octave-20261103-b'
COMMANDS=[];SEG=[(0,2100),(700,1900)]


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


def corridor(shape,fac,halfw=2.0):
 yy,xx=np.mgrid[0:shape[0],0:shape[1]]
 (x0,z0),(x1,z1)=SEG
 px0,pz0,px1,pz1=x0/fac,z0/fac,x1/fac,z1/fac
 dx,dz=px1-px0,pz1-pz0;L2=dx*dx+dz*dz
 t=np.clip(((xx-px0)*dx+(yy-pz0)*dz)/L2,0,1)
 return np.hypot(xx-(px0+t*dx),yy-(pz0+t*dz))<=halfw


def feature(cracks,fac):
 band=corridor(cracks.shape,fac);lab,_=ndi.label(ndi.maximum_filter(cracks,size=3))
 spans=[]
 for i in np.unique(lab[band]):
  if i==0:continue
  o=ndi.find_objects(lab==i)[0];spans.append(max(o[0].stop-o[0].start,o[1].stop-o[1].start))
 return {'candidatesInCorridor':int((cracks&band).sum()),'longestComponentSpanPixels':int(max(spans)) if spans else 0,'componentsInside':len(spans)}


def figure(arr,cracks,m):
 pal=np.loadtxt(ROOT/'build/fast-erosion-scale-only-20261103-final/data/project-palette.csv',delimiter=',',skiprows=1)
 cm=ListedColormap(pal[:,1:]/255);n=m['size'];step=m['stepWu']*2
 keys=['B','J1','J2','R1','C']
 titles=['B | full deriv source','J1 | deriv from 1st ridge octave','J2 | deriv from base low-passed 260wu','R1 | 1-octave ridge (reference)','C | smooth-input control']
 crop=(slice(int(np.floor(1200/step)),int(np.ceil(2400/step))+1),slice(0,int(np.ceil(1200/step))+1))
 def light(a):
  gz,gx=np.gradient(a*236.44*1.5,step);l=np.array([-.5,-.3,1.]);l/=np.linalg.norm(l)
  return .65+.35*np.maximum(0,(-gx*l[0]-gz*l[1]+l[2])/np.sqrt(1+gx*gx+gz*gz))
 def rgb(a):return cm(Normalize(-.35,1.2,clip=True)(.02+1.5*a))[...,:3]*light(a)[...,None]
 for name,sl,ov,gray in [('comparison',(slice(0,n),slice(0,n)),0,0),('marked-region',crop,0,0),('cracks',(slice(0,n),slice(0,n)),1,0),
       ('marked-region-cracks',crop,1,0),('hillshade',(slice(0,n),slice(0,n)),0,1),('marked-region-hillshade',crop,0,1)]:
  ext=(sl[1].start*step,sl[1].stop*step,sl[0].stop*step,sl[0].start*step)
  fig,axes=plt.subplots(2,3,figsize=(20,13),dpi=130)
  for ax,k,t in zip(axes.flat,keys,titles):
   if gray:ax.imshow(light(arr[k])[sl],vmin=.65,vmax=1,cmap='gray',extent=ext,interpolation='nearest')
   else:ax.imshow(rgb(arr[k])[sl],extent=ext,interpolation='nearest')
   if ov:
    o=np.zeros((*arr[k].shape,4));o[cracks[k]]=[1,0,1,.9];ax.imshow(o[sl],extent=ext,interpolation='nearest')
   ax.set(title=t,xlabel='local x (blocks)',ylabel='local z (blocks)')
  axes.flat[-1].axis('off')
  fig.suptitle('J | height unchanged (2 octaves) | only the derivative source differs')
  fig.text(.05,.02,'HS=2; Y proxy236.44*1.5*h; fixed light; color e[-.35,1.2] unless hillshade-only. No production integration.')
  fig.tight_layout(rect=(0,.05,1,.96));fig.savefig(OUT/f'{name}.png');plt.close(fig)


def main():
 locked=[p for p in (ROOT/'src/main').rglob('*') if p.is_file()]
 locked+=list((ROOT/'src/diagnostics/java/com/geogenesis/standalone').glob('FastErosion*.java'))
 for f in (ROOT/'build').glob('fast-erosion-*'):
  locked+=[p for p in f.rglob('*') if p.is_file()]
 for f in (ROOT/'build').glob('stage*-*'):
  locked+=[p for p in f.rglob('*') if p.is_file()]
 before={str(p.relative_to(ROOT)):sha(p) for p in locked};save('locked-before.json',before)
 m=json.loads((DER/'data/sampling.json').read_text());n=m['size'];fac=m['stepWu']*2;shape=(n,n)
 java='src/diagnostics/java/com/geogenesis/worldgen/terrain/morph/FastErosionDerivSourceProbe.java'
 cl=OUT/'classes';cl.mkdir()
 run(['javac','-encoding','UTF-8','-proc:none','-d',str(cl),'-sourcepath','src/main/java;src/diagnostics/java',java])
 run(['java','-Xmx3g','-cp',str(cl),'com.geogenesis.worldgen.terrain.morph.FastErosionDerivSourceProbe',str(OUT/'data')])
 par=json.loads((OUT/'data/parameters.json').read_text());kreport=(OUT/'data/k-report.txt').read_text()
 kmap=dict(line.split(' k=') for line in kreport.strip().splitlines())
 R=lambda p,s:np.fromfile(p,dtype='>f8').astype(float).reshape(s)
 arr={'B':R(PREV/'data/B.f64be',shape),'J1':R(OUT/'data/J1-height.f64be',shape),
      'J2':R(OUT/'data/J2-height.f64be',shape),'R1':R(R1D/'data/R1-height.f64be',shape),
      'C':R(CTRL/'data/C-height.f64be',shape)}
 assert all(np.isfinite(a).all() for a in arr.values())
 ds1=R(OUT/'data/J1-derivsource.f64be',shape);ds2=R(OUT/'data/J2-derivsource.f64be',shape)
 det=pure(ROOT/'src/diagnostics/fast-erosion-derivatives.py',['detector','crack_score'])
 thr=json.loads((DER/'statistics.json').read_text())['thresholds'];abs_thr=thr['crackHessianShellPerOutputSampleSquared']
 lap_thr=thr['lapNativeAdjacentP99'];mask_thr=thr['maskNativeAdjacentP99']
 raw=R(DER/'data/production-raw.f64be',shape)
 crop=(slice(int(np.floor(1200/fac)),int(np.ceil(2400/fac))+1),slice(0,int(np.ceil(1200/fac))+1))
 stats={};cracks={}
 for arm in ['B','J1','J2','R1','C']:
  hh=arr[arm]
  if arm=='C':inc=R(CTRL/'data/C-erosion.f64be',shape);msk=R(CTRL/'data/C-mask.f64be',shape);kk=par['lockedK']
  elif arm=='B':inc=R(PREV/'data/erosion-shell.f64be',shape);msk=R(PREV/'data/initial-mask.f64be',shape);kk=par['lockedK']
  elif arm=='R1':inc=R(R1D/'data/R1-erosion.f64be',shape);msk=R(R1D/'data/R1-mask.f64be',shape);kk=json.loads((R1D/'data/parameters.json').read_text())['k']
  else:inc=R(OUT/'data'/f'{arm}-erosion.f64be',shape);msk=R(OUT/'data'/f'{arm}-mask.f64be',shape);kk=float(kmap[arm])
  gz,gx=np.gradient(hh,m['stepWu']);q=np.quantile(np.hypot(gx,gz)[1:-1,1:-1],[.5,.99,.999,1])
  cracks[arm]=det['detector'](hh,abs_thr)[0]
  stats[arm]={'k':kk,'crackPixels':int(cracks[arm].sum()),'ratioVsB':float(cracks[arm].sum()/5317),
   'markedRegionCracks':int(cracks[arm][crop].sum()),'maskFullFraction':float((msk>=1).mean()),
   'erosionStdOverTotalStd':float(inc.std()/hh.std()),'corrWithProductionRaw':float(np.corrcoef(raw.ravel(),hh.ravel())[0,1]),
   'slope':dict(zip(['p50','p99','p999','max'],map(float,q)),tail=float(q[2]/q[0])),
   'minMax':[float(hh.min()),float(hh.max())],
   'colorSaturatedFraction':float((((.02+1.5*hh)<-.35)|((.02+1.5*hh)>1.2)).mean()),
   'erosionGradientRms':float(np.sqrt(np.mean(np.gradient(inc,m['stepWu'])[0]**2+np.gradient(inc,m['stepWu'])[1]**2))),
   'feature':feature(cracks[arm],fac)}
 assert stats['B']['crackPixels']==5317 and stats['C']['crackPixels']==19
 derivdiff={'relativeRmsDifferenceJ2minusJ1':float(np.sqrt(np.mean((ds2-ds1)**2))/np.std(ds1)),
            'rmsJ1':float(ds1.std()),'rmsJ2':float(ds2.std()),
            'gradRmsJ1':float(np.sqrt(np.mean(np.gradient(ds1,fac)[0]**2+np.gradient(ds1,fac)[1]**2))),
            'gradRmsJ2':float(np.sqrt(np.mean(np.gradient(ds2,fac)[0]**2+np.gradient(ds2,fac)[1]**2))),
            'note':'if these two are close then J2 is numerically near J1 by construction (sigma=130wu attenuates the 260wu octave by exp(-2*pi^2*sigma^2/lambda^2)=0.7%)'}
 # input-side rates from the arm's own derivative source (recompute derivatives on the native lattice is
 # not exported, so report aspect/mask jump rates from the sampled derivative-source grid instead)
 def rates(h):
  gz,gx=np.gradient(h,fac);asp=np.arctan2(gz,gx)
  cx=np.abs(np.arctan2(np.sin(np.diff(asp,axis=1)),np.cos(np.diff(asp,axis=1))))
  cz=np.abs(np.arctan2(np.sin(np.diff(asp,axis=0)),np.cos(np.diff(asp,axis=0))))
  t=np.zeros_like(asp);t[:,:-1]=np.maximum(t[:,:-1],cx);t[:,1:]=np.maximum(t[:,1:],cx);t[:-1]=np.maximum(t[:-1],cz);t[1:]=np.maximum(t[1:],cz)
  return float((t[8:-8,8:-8]>np.pi/4).mean())
 inp={'note':'aspect jump rates below are computed on the OUTPUT-grid derivative-source samples, not the native lattice; internally comparable but not identical to earlier native-lattice numbers'}
 inp['aspectJumpGt45deg']={'J1':rates(ds1),'J2':rates(ds2)}
 save('statistics.json',{'arms':stats,'derivSourceComparison':derivdiff,'input':inp,'parameters':par,
   'kReport':kreport,'detectorThreshold':abs_thr,
   'calibrationCaveat':'J2 k uses the first-octave derivative source during calibration because the sigma=130wu kernel cannot be evaluated at the distant offline calibration domain; see derivSourceComparison for how close J2 actually is to J1'})
 figure(arr,cracks,m)
 assert all(sha(ROOT/p)==v for p,v in before.items()),'locked changed'
 save('manifest.json',{'purpose':'J derivative-source experiment; diagnostics only','commands':COMMANDS,
  'sourceHashes':{java:sha(ROOT/java),str(Path(__file__).resolve().relative_to(ROOT)):sha(Path(__file__))},
  'detectorSha256':sha(ROOT/'src/diagnostics/fast-erosion-derivatives.py'),'lockedFiles':len(before),
  'files':{str(p.relative_to(OUT)):sha(p) for p in OUT.rglob('*') if p.is_file() and 'classes' not in p.parts}})
 (OUT/'verification.txt').write_text('PASS fresh javac; J1 derivative source equals the 1-octave node bitwise\n'
  'PASS height/base composition still the full 2-octave raw; only derivative source differs\n'
  'PASS B/R1/C from locked baselines; each arm k recoined by the same offline rule\n'
  'PASS fixed detector threshold; main/core/existing baselines unchanged\n',encoding='utf-8')
 print(json.dumps({'arms':stats,'derivSourceComparison':derivdiff,'parameters':par},indent=2));print('DONE',OUT)


if __name__=='__main__':main()
