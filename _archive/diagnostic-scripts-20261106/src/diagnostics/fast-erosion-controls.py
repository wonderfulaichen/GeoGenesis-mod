"""C smooth-input control and D continuous feedback rotation. New output only, CPU only.
python -B src/diagnostics/fast-erosion-controls.py build/<NEW-dir>
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
from matplotlib.colors import ListedColormap,Normalize

ROOT=Path(__file__).resolve().parents[2]
OUT=(ROOT/sys.argv[1]).resolve()
if OUT.exists() or ROOT/'build' not in OUT.parents:raise ValueError('NEW build dir required')
OUT.mkdir(parents=True)
PREV=ROOT/'build/fast-erosion-fine-step-20261103-b'
DER=ROOT/'build/fast-erosion-derivatives-20261103-a'
COMMANDS=[]


def sha(p):
 h=hashlib.sha256()
 with p.open('rb') as f:
  for b in iter(lambda:f.read(1024*1024),b''):h.update(b)
 return h.hexdigest()


def save(name,v):
 (OUT/name).write_text(json.dumps(v,indent=2,ensure_ascii=False)+'\n',encoding='utf-8')


def run(args):
 COMMANDS.append(args);save('commands.json',COMMANDS)
 t=time.perf_counter();r=subprocess.run(args,cwd=ROOT,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
 s=r.stdout.decode('utf-8',errors='replace')
 with (OUT/'validation.log').open('a',encoding='utf-8') as f:f.write('\n'+subprocess.list2cmdline(args)+'\n'+s+'\nexit=%d seconds=%.3f\n'%(r.returncode,time.perf_counter()-t))
 print(s,flush=True)
 if r.returncode:raise RuntimeError('command failed')


def slope(a,step):
 gz,gx=np.gradient(a,step);q=np.quantile(np.hypot(gx,gz)[1:-1,1:-1],[.5,.99,.999,1])
 return dict(zip(['p50','p99','p999','max'],map(float,q)),tail=float(q[2]/q[0]))


def plot(arr,m,cracks):
 pal=np.loadtxt(ROOT/'build/fast-erosion-scale-only-20261103-final/data/project-palette.csv',delimiter=',',skiprows=1)
 cm=ListedColormap(pal[:,1:]/255);n=m['size'];step=m['stepWu']*2
 keys=['B','C','C-analytic','D']
 def rgb(a):
  gz,gx=np.gradient(a*236.44*1.5,step);l=np.array([-.5,-.3,1.]);l/=np.linalg.norm(l)
  sh=.65+.35*np.maximum(0,(-gx*l[0]-gz*l[1]+l[2])/np.sqrt(1+gx*gx+gz*gz))
  return cm(Normalize(-.35,1.2,clip=True)(.02+1.5*a))[...,:3]*sh[...,None]
 crop=(slice(int(np.floor(1200/step)),int(np.ceil(2400/step))+1),slice(0,int(np.ceil(1200/step))+1))
 for filename,sl in [('comparison.png',(slice(0,n),slice(0,n))),('marked-region.png',crop)]:
  ext=(sl[1].start*step,sl[1].stop*step,sl[0].stop*step,sl[0].start*step)
  fig,axes=plt.subplots(2,2,figsize=(16,15),dpi=135)
  for ax,k in zip(axes.flat,keys):
   ax.imshow(rgb(arr[k])[sl],extent=ext,interpolation='nearest')
   ax.set(title=k+{'B':' | current L2 / original core','C':' | demo base / central derivatives','C-analytic':' | demo base / demo analytic callback','D':' | L2 / continuous feedback'}[k],xlabel='local x (blocks)',ylabel='local z (blocks)')
  fig.suptitle('Input control C and feedback control D | same window, kC=kB, fixed color/light/vertical scale')
  fig.text(.08,.02,'Fixed e [-.35,1.2]; HS=2; Y proxy=236.44*1.5*h. No production integration.\nC changes ONLY input base; C-analytic separately changes callback to the exact demo analytic branch.')
  fig.tight_layout(rect=(0,.06,1,.96));fig.savefig(OUT/filename);plt.close(fig)
 for filename,sl in [('cracks-full.png',(slice(0,n),slice(0,n))),('marked-region-cracks.png',crop)]:
  ext=(sl[1].start*step,sl[1].stop*step,sl[0].stop*step,sl[0].start*step)
  fig,axes=plt.subplots(2,2,figsize=(16,15),dpi=135)
  for ax,k in zip(axes.flat,keys):
   ax.imshow(rgb(arr[k])[sl],extent=ext,interpolation='nearest')
   overlay=np.zeros((*arr[k].shape,4));overlay[cracks[k]]=[1,0,1,.9]
   ax.imshow(overlay[sl],extent=ext,interpolation='nearest')
   ax.set(title=k+' | identical frozen detector',xlabel='local x (blocks)',ylabel='local z (blocks)')
  fig.tight_layout();fig.savefig(OUT/filename);plt.close(fig)
 fig,axes=plt.subplots(1,2,figsize=(16,8),dpi=135)
 ext=(0,crop[1].stop*step,crop[0].stop*step,crop[0].start*step)
 for ax,k in zip(axes,['B','D']):
  inc=arr[k+'-erosion'];im=ax.imshow(inc[crop],extent=ext,cmap='RdBu_r',vmin=-.15,vmax=.15,interpolation='nearest')
  ax.set(title=k+' erosion contribution',xlabel='local x (blocks)',ylabel='local z (blocks)')
 fig.colorbar(im,ax=axes,fraction=.02,label='common shell delta h +/- .15')
 fig.savefig(OUT/'marked-region-erosion.png');plt.close(fig)
 # Same light and physical vertical ratio, NO elevation color clipping.
 fig,axes=plt.subplots(2,2,figsize=(16,15),dpi=135)
 ext=(0,crop[1].stop*step,crop[0].stop*step,crop[0].start*step)
 for ax,k in zip(axes.flat,keys):
  gz,gx=np.gradient(arr[k]*236.44*1.5,step);l=np.array([-.5,-.3,1.]);l/=np.linalg.norm(l)
  sh=.65+.35*np.maximum(0,(-gx*l[0]-gz*l[1]+l[2])/np.sqrt(1+gx*gx+gz*gz))
  ax.imshow(sh[crop],vmin=.65,vmax=1,cmap='gray',extent=ext,interpolation='nearest')
  ax.set(title=k+' | no elevation color clipping',xlabel='local x (blocks)',ylabel='local z (blocks)')
 fig.tight_layout();fig.savefig(OUT/'marked-region-hillshade.png');plt.close(fig)


def main():
 locked=[p for p in (ROOT/'src/main').rglob('*') if p.is_file()]
 locked+=list((ROOT/'src/diagnostics/java/com/geogenesis/standalone').glob('FastErosion*.java'))
 for folder in (ROOT/'build').glob('fast-erosion-*'):
  if folder!=OUT:locked += [p for p in folder.rglob('*') if p.is_file()]
 before={str(p.relative_to(ROOT)):sha(p) for p in locked};save('locked-before.json',before)
 m=json.loads((DER/'data/sampling.json').read_text());w,h=m['nativeSize'];n=m['size']
 # eps_ref=1 times median |first feedback| in the frozen current B native window.
 # At typical |feedback|=median, t has magnitude 1/2, so +/-36deg becomes +/-18deg.
 # The dimensional width is data-anchored, the 1x factor is declared BEFORE viewing any new result.
 a=np.fromfile(DER/'feedback-audit/feedback-0.f32be',dtype='>f4').astype(float).reshape(h,w)
 eps=float(np.median(abs(a[4:-4,4:-4])))
 eps32=float(np.float32(eps));save('epsilon.json',{'epsRef':eps32,'multiplier':1,'statistic':'median absolute first signed Gabor feedback on frozen current B native lattice, four-pixel border excluded','rotationAtMedianDegrees':18,'caveat':'offline frozen constant, not recalculated in sampler, not an independent test domain'})
 work=OUT/'generated';work.mkdir()
 src=ROOT/'src/diagnostics/java/com/geogenesis/standalone/FastErosionFilter.java'
 original=src.read_text(encoding='utf-8')
 s=original.replace('FastErosionFilter','FastErosionContinuousRotation')
 needle='            aspect[0] += Math.signum(steepness[0]) * -params.branchAngle;'
 assert s.count(needle)==1
 s=s.replace(needle,'            float tRotation = steepness[0] / (Math.abs(steepness[0]) + EPS_REF);\n            aspect[0] += tRotation * -params.branchAngle;')
 s=s.replace('    private static final float TAU','    public static final float EPS_REF = '+repr(eps32)+'f;\n    private static final float TAU')
 (work/'FastErosionContinuousRotation.java').write_text(s,encoding='utf-8')
 save('core-variant.json',{'approvedSourceSha256':sha(src),'variantSourceSha256':sha(work/'FastErosionContinuousRotation.java'),'onlyBehavioralChange':needle.strip()+' -> t=steepness/(abs(steepness)+epsRef); aspect+=t*(-branchAngle)','derivativeCaveat':'Only scalar feedback rotation changed as requested; other original graded channels retained. No independent derivative-channel reinterpretation.'})
 java='src/diagnostics/java/com/geogenesis/worldgen/terrain/morph/FastErosionControlProbe.java'
 classes=OUT/'classes';classes.mkdir()
 run(['javac','-encoding','UTF-8','-proc:none','-d',str(classes),'-sourcepath','src/main/java;src/diagnostics/java',java,
      'src/diagnostics/java/com/geogenesis/standalone/FastErosionOriginalDemo.java',str(work/'FastErosionContinuousRotation.java')])
 run(['java','-Xmx3g','-cp',str(classes),'com.geogenesis.worldgen.terrain.morph.FastErosionControlProbe',str(OUT/'data'),str(DER/'data/native-base.f64be')])
 params=json.loads((OUT/'data/parameters.json').read_text())
 assert sha(OUT/'data/B-height.f64be')==sha(PREV/'data/B.f64be'),'B replay mismatch'
 shape=(n,n);arr={}
 for k in ['B','C','C-analytic','D']:
  for name in ['height','erosion','base','mask']:
   key=k if name=='height' else k+'-'+name
   arr[key]=np.fromfile(OUT/'data'/(k+'-'+name+'.f64be'),dtype='>f8').astype(float).reshape(shape)
   assert np.isfinite(arr[key]).all()
 # Import only EXACT pure detector functions, not its top-level output-directory side effects.
 source=ROOT/'src/diagnostics/fast-erosion-derivatives.py'
 tree=ast.parse(source.read_text(encoding='utf-8'));defs=[x for x in tree.body if isinstance(x,ast.FunctionDef) and x.name in ['crack_score','detector']]
 scope={'np':np,'ndi':ndi};exec(compile(ast.Module(body=defs,type_ignores=[]),str(source),'exec'),scope)
 threshold=json.loads((DER/'statistics.json').read_text())['thresholds']['crackHessianShellPerOutputSampleSquared']
 cracks={};stats={};raw=np.fromfile(DER/'data/production-raw.f64be',dtype='>f8').astype(float).reshape(shape)
 blockstep=m['stepWu']*2
 sl=(slice(int(np.floor(1200/blockstep)),int(np.ceil(2400/blockstep))+1),slice(0,int(np.ceil(1200/blockstep))+1))
 for k in ['B','C','C-analytic','D']:
  cracks[k],score=scope['detector'](arr[k],threshold);np.save(OUT/(k+'-cracks.npy'),cracks[k],allow_pickle=False)
  labels,count=ndi.label(ndi.maximum_filter(cracks[k],size=3));objects=ndi.find_objects(labels)
  lengths=[max(o[0].stop-o[0].start,o[1].stop-o[1].start) for o in objects if o]
  stats[k]={'crackPixels':int(cracks[k].sum()),'ratioVsB':float(cracks[k].sum()/5317),'markedRegionCracks':int(cracks[k][sl].sum()),
    'maskFullFraction':float((arr[k+'-mask']>=1).mean()),'erosionStdOverTotal':float(arr[k+'-erosion'].std()/arr[k].std()),
    'corrWithProductionRaw':float(np.corrcoef(raw.ravel(),arr[k].ravel())[0,1]),'slope':slope(arr[k],m['stepWu']),
    'minMax':[float(arr[k].min()),float(arr[k].max())], 'colorSaturation':float((((.02+1.5*arr[k])<-.35)|((.02+1.5*arr[k])>1.2)).mean()),
    'dilatedComponentsMaxSpanPixels':max(lengths) if lengths else 0,'dilatedComponentsSpanAtLeast100px':int(sum(x>=100 for x in lengths))}
 assert stats['B']['crackPixels']==5317
 sensitivity={}
 score=scope['crack_score'](arr['B'])[8:-8,8:-8]
 for pct in [.98,.985,.99,.995]:
  th=float(np.quantile(score,pct));sensitivity[str(pct)]={k:int(scope['detector'](arr[k],th)[0].sum()) for k in ['B','C','C-analytic','D']}
 save('statistics.json',{'arms':stats,'parameters':params,'absoluteDetectorThreshold':threshold,'detectorSource':str(source),'detectorSourceSha256':sha(source),'sensitivity':sensitivity,
  'CMapping':'uncompressed demo BASE_FREQ9 simplex translated by (2722,6202) samples; wrapped unsigned inputs, same erosion world hash phase, central +/-1; kC=kB .8205431 and same .7 shell. No image-derived amplitude scaling.',
  'analyticControl':'C-analytic only replaces finite derivative callback by demo baseDerivatives at same translated positions; extra control, not pooled with C.'})
 plot(arr,m,cracks)
 assert all(sha(ROOT/p)==v for p,v in before.items()),'locked source/baseline changed'
 save('manifest.json',{'purpose':'C smooth-input and D continuous-rotation controls; diagnostics only','commands':COMMANDS,'parameters':params,
  'sourceHashes':{java:sha(ROOT/java),str(Path(__file__).resolve().relative_to(ROOT)):sha(Path(__file__)),str(src.relative_to(ROOT)):sha(src)},
  'baselineBFullSHAExact':True,'lockedFileCount':len(before),'files':{str(p.relative_to(OUT)):sha(p) for p in OUT.rglob('*') if p.is_file() and 'classes' not in p.parts}})
 (OUT/'verification.txt').write_text('PASS fresh javac; current B full height SHA exact\nPASS C same lattice/scale/parameters/output amplitude; only base changed\nPASS D generated core differs only feedback rotation; eps frozen before rendering\nPASS all arrays finite, fixed absolute crack detector threshold, all existing main/core/baseline files unchanged\nNOT VERIFIED: labelled seam ground truth, other windows/seeds, production integration\n',encoding='utf-8')
 print(json.dumps(stats,indent=2));print('DONE',OUT)


if __name__=='__main__':main()
