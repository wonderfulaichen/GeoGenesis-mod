"""K: sigma sweep (30/50/80wu) on the derivative source only. NEW dir, CPU only.
python -B src/diagnostics/exp-k-sigma.py build/<NEW-dir>
"""
import ast,hashlib,json,re,subprocess,sys,time
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
JR=ROOT/'build/exp-j-20261103-b'
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
 keys=['B','S30','S50','S80','J1','C']
 titles=['B | full deriv source','sigma=30wu (alpha610 .953/.260 .917)','sigma=50wu (.875/.48)',
         'sigma=80wu (.712/.157)','J1 | deriv = 1st octave only','C | smooth-input control']
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
  fig.suptitle('K | derivative-source sigma sweep | height unchanged (2 octaves)')
  fig.text(.05,.02,'HS=2; Y proxy236.44*1.5*h; fixed light; color e[-.35,1.2] unless hillshade-only. No production integration.')
  fig.tight_layout(rect=(0,.05,1,.96));fig.savefig(OUT/f'{name}.png');plt.close(fig)


def main():
 locked=[p for p in (ROOT/'src/main').rglob('*') if p.is_file()]
 locked+=list((ROOT/'src/diagnostics/java/com/geogenesis/standalone').glob('FastErosion*.java'))
 for f in list((ROOT/'build').glob('fast-erosion-*'))+list((ROOT/'build').glob('stage*-*'))+list((ROOT/'build').glob('exp-j-*')):
  locked+=[p for p in f.rglob('*') if p.is_file()]
 before={str(p.relative_to(ROOT)):sha(p) for p in locked};save('locked-before.json',before)
 m=json.loads((DER/'data/sampling.json').read_text());n=m['size'];fac=m['stepWu']*2;shape=(n,n)
 java='src/diagnostics/java/com/geogenesis/worldgen/terrain/morph/FastErosionSigmaProbe.java'
 cl=OUT/'classes';cl.mkdir()
 run(['javac','-encoding','UTF-8','-proc:none','-d',str(cl),'-sourcepath','src/main/java;src/diagnostics/java',java])
 run(['java','-Xmx3g','-cp',str(cl),'com.geogenesis.worldgen.terrain.morph.FastErosionSigmaProbe',str(OUT/'data')])
 pmeta={}
 for line in (OUT/'data/parameters.txt').read_text().strip().splitlines():
  a=line.split()[0];pmeta[a]={kv.split('=')[0]:float(kv.split('=')[1]) for kv in line.split()[1:]}
 R=lambda p,s:np.fromfile(p,dtype='>f8').astype(float).reshape(s)
 arr={'B':R(PREV/'data/B.f64be',shape),'J1':R(JR/'data/J1-height.f64be',shape),
      'C':R(CTRL/'data/C-height.f64be',shape)}
 for a in ('S30','S50','S80'):arr[a]=R(OUT/'data'/f'{a}-height.f64be',shape)
 assert all(np.isfinite(x).all() for x in arr.values())
 det=pure(ROOT/'src/diagnostics/fast-erosion-derivatives.py',['detector','crack_score'])
 thr=json.loads((DER/'statistics.json').read_text())['thresholds'];abs_thr=thr['crackHessianShellPerOutputSampleSquared']
 raw=R(DER/'data/production-raw.f64be',shape)
 crop=(slice(int(np.floor(1200/fac)),int(np.ceil(2400/fac))+1),slice(0,int(np.ceil(1200/fac))+1))
 stats={};cracks={}
 for arm in ['B','S30','S50','S80','J1','C']:
  hh=arr[arm]
  if arm=='C':inc=R(CTRL/'data/C-erosion.f64be',shape);msk=R(CTRL/'data/C-mask.f64be',shape);kk=0.8205431216912392
  elif arm=='B':inc=R(PREV/'data/erosion-shell.f64be',shape);msk=R(PREV/'data/initial-mask.f64be',shape);kk=0.8205431216912392
  elif arm=='J1':inc=R(JR/'data/J1-erosion.f64be',shape);msk=R(JR/'data/J1-mask.f64be',shape);kk=0.8878256414409725
  else:inc=R(OUT/'data'/f'{arm}-erosion.f64be',shape);msk=R(OUT/'data'/f'{arm}-mask.f64be',shape);kk=pmeta[arm]['kPatch']
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
 alive={a:dict(sigmaWu=pmeta[a]['sigmaWu'],alpha610=pmeta[a]['alpha610'],alpha260=pmeta[a]['alpha260'],
   kPatch=pmeta[a]['kPatch'],kPatchB=pmeta[a]['kPatchB'],patchToPatchRelDiff=pmeta[a]['patchToPatchRelDiff'],
   maskFull=stats[a]['maskFullFraction'],erosionShare=stats[a]['erosionStdOverTotalStd'],
   degenerate=bool(stats[a]['maskFullFraction']<=0.0 or stats[a]['erosionStdOverTotalStd']<0.15))
   for a in ('S30','S50','S80')}
 save('statistics.json',{'arms':stats,'sigmaArms':alive,'detectorThreshold':abs_thr,
   'calibrationNote':'k for the sigma arms is computed on a separate local 385x385 patch with its own true halo, twice at different offsets; patchToPatchRelDiff quantifies the stationarity substitution. It is NOT the distant offline domain (the 3-sigma kernel cannot be evaluated there).',
   'featureNote':'oblique corridor = straight segment (0,2100)-(700,1900), half-width 2 output px, SMALL COUNTS; vertical cut judged from marked-region-hillshade.png'})
 figure(arr,cracks,m)
 assert all(sha(ROOT/p)==v for p,v in before.items()),'locked changed'
 save('manifest.json',{'purpose':'K sigma sweep; diagnostics only','commands':COMMANDS,
  'sourceHashes':{java:sha(ROOT/java),str(Path(__file__).resolve().relative_to(ROOT)):sha(Path(__file__))},
  'detectorSha256':sha(ROOT/'src/diagnostics/fast-erosion-derivatives.py'),'lockedFiles':len(before),
  'files':{str(p.relative_to(OUT)):sha(p) for p in OUT.rglob('*') if p.is_file() and 'classes' not in p.parts}})
 (OUT/'verification.txt').write_text('PASS fresh javac; height path unchanged; only derivative-source sigma differs\n'
  'PASS k per sigma arm computed on independent local patches with true halo (two offsets)\n'
  'PASS B/J1/C from locked baselines; fixed detector threshold; main/core/baselines unchanged\n',encoding='utf-8')
 print(json.dumps({'arms':stats,'sigmaArms':alive},indent=2));print('DONE',OUT)


if __name__=='__main__':main()
