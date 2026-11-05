"""Common production catalog raw / three derivative sources. NEW build directory only, CPU."""
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
if OUT.exists() or ROOT/'build' not in OUT.parents:raise ValueError('NEW build directory required')
OUT.mkdir(parents=True)
DER=ROOT/'build/fast-erosion-derivatives-20261103-a'
PREV=ROOT/'build/fast-erosion-fine-step-20261103-b'
HIST=ROOT/'build/exp-j-20261103-b'
CTRL=ROOT/'build/fast-erosion-controls-20261103-final'
COMMANDS=[]
N=769
AMP=float(np.float32(.7))


def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def save(n,v):(OUT/n).write_text(json.dumps(v,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
def read(p):
 a=np.fromfile(p,dtype='>f8').astype(float).reshape(N,N)
 assert np.isfinite(a).all(),p
 return a

def run(args):
 COMMANDS.append(args);save('commands.json',COMMANDS);t=time.perf_counter()
 p=subprocess.run(args,cwd=ROOT,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
 text=p.stdout.decode('utf-8',errors='replace');print(text,flush=True)
 with (OUT/'validation.log').open('a',encoding='utf-8') as f:
  f.write(subprocess.list2cmdline(args)+'\n'+text+'\nexit=%d seconds=%.3f\n'%(p.returncode,time.perf_counter()-t))
 if p.returncode:raise RuntimeError('command failed')

def pure(path,names,scope):
 t=ast.parse(path.read_text(encoding='utf-8-sig'))
 exec(compile(ast.Module(body=[v for v in t.body if isinstance(v,ast.FunctionDef) and v.name in names],type_ignores=[]),str(path),'exec'),scope)
 return scope

def figure(arr,cracks,m):
 pal=np.loadtxt(ROOT/'build/fast-erosion-scale-only-20261103-final/data/project-palette.csv',delimiter=',',skiprows=1)
 cm=ListedColormap(pal[:,1:]/255);step=m['stepWu']*2
 keys=['J0','J1c','V2','C'];titles=['J0 | deriv ridge4 x scaler2','J1c | deriv ridge1 x scaler1','J1c+V2 | rounded fold ONLY','C | historical smooth-input control']
 crop=(slice(int(np.floor(1200/step)),int(np.ceil(2400/step))+1),slice(0,int(np.ceil(1200/step))+1))
 def light(a):
  gz,gx=np.gradient(a*236.44*1.5,step);l=np.array([-.5,-.3,1.]);l/=np.linalg.norm(l)
  return .65+.35*np.maximum(0,(-gx*l[0]-gz*l[1]+l[2])/np.sqrt(1+gx*gx+gz*gz))
 def rgb(a):return cm(Normalize(-.35,1.2,clip=True)(.02+1.5*a))[...,:3]*light(a)[...,None]
 specs=[('comparison',(slice(0,N),slice(0,N)),False,False),('marked-region',crop,False,False),
        ('cracks',(slice(0,N),slice(0,N)),True,False),('marked-region-cracks',crop,True,False),
        ('hillshade',(slice(0,N),slice(0,N)),False,True),('marked-region-hillshade',crop,False,True),
        ('vertical-hillshade',(slice(int(1320/step),int(1850/step)),slice(int(780/step),int(1150/step))),False,True)]
 for name,sl,ov,gray in specs:
  ext=(sl[1].start*step,sl[1].stop*step,sl[0].stop*step,sl[0].start*step)
  fig,axes=plt.subplots(2,2,figsize=(15,14),dpi=120)
  for ax,key,title in zip(axes.flat,keys,titles):
   if gray:ax.imshow(light(arr[key])[sl],vmin=.65,vmax=1,cmap='gray',extent=ext,interpolation='nearest')
   else:ax.imshow(rgb(arr[key])[sl],extent=ext,interpolation='nearest')
   if ov:
    o=np.zeros((*arr[key].shape,4));o[cracks[key]]=[1,0,1,.9]
    ax.imshow(o[sl],extent=ext,interpolation='nearest')
   ax.set(title=title,xlabel='local x (blocks)',ylabel='local z (blocks)')
  fig.suptitle('Common production catalog raw (ridge4 x scaler4 x warp) | only derivative source changes\nC is historical reference, NOT part of common-height experiment',fontsize=13)
  fig.text(.04,.02,'HS=2; Y proxy236.44*1.5*h; fixed light and e[-.35,1.2]; no per-arm normalization; no production wiring.')
  fig.tight_layout(rect=(0,.04,1,.95));fig.savefig(OUT/(name+'.png'));plt.close(fig)


def main():
 locked=[p for p in (ROOT/'src/main').rglob('*') if p.is_file()]
 locked+=list((ROOT/'src/diagnostics/java/com/geogenesis/standalone').glob('FastErosion*.java'))
 for d in list((ROOT/'build').glob('fast-erosion-*'))+list((ROOT/'build').glob('exp-*-*'))+list((ROOT/'build').glob('stage*-*')):
  if d!=OUT:locked.extend(p for p in d.rglob('*') if p.is_file())
 before={str(p.relative_to(ROOT)):sha(p) for p in locked};save('locked-before.json',before)
 java='src/diagnostics/java/com/geogenesis/worldgen/terrain/morph/FastErosionCorrectedProbe.java'
 cl=OUT/'classes';cl.mkdir()
 run(['javac','-encoding','UTF-8','-proc:none','-d',str(cl),'-sourcepath','src/main/java;src/diagnostics/java',java])
 run(['java','-Xmx3g','-cp',str(cl),'com.geogenesis.worldgen.terrain.morph.FastErosionCorrectedProbe',str(OUT/'data')])
 m=json.loads((DER/'data/sampling.json').read_text());step=m['stepWu'];fac=step*2
 cal={}
 for line in (OUT/'data/calibration.txt').read_text().splitlines():
  a,*v=line.split();cal[a]=list(map(float,v))
 arr={a:read(OUT/'data'/f'{a}-height.f64be') for a in ['J0','J1c','V0','V2','D2']}
 arr['C']=read(CTRL/'data/C-height.f64be')
 base=read(OUT/'data/common-production-raw.f64be')
 negative={}
 for suffix in ['height','erosion','mask','unitE']:
  a=read(OUT/'data'/f'J1c-{suffix}.f64be');b=read(OUT/'data'/f'V0-{suffix}.f64be')
  negative[suffix]={'compared':a.size,'differentBits':int((a.view(np.uint64)!=b.view(np.uint64)).sum()),'maxAbs':float(abs(a-b).max())}
  assert negative[suffix]['differentBits']==0,'r=0 negative control failed'
 assert cal['J1c']==cal['V0'],'r=0 calibration drift'
 common={}
 for a in ['J0','J1c','V2']:
  k=cal[a][0];inc=read(OUT/'data'/f'{a}-erosion.f64be')
  recovered=(arr[a]-inc)/(k*AMP)
  common[a]={'maxReconstructionResidual':float(abs(recovered-base).max()),'baseSha256':sha(OUT/'data/common-production-raw.f64be')}
  assert np.allclose(recovered,base,rtol=0,atol=5e-16)
 det=pure(ROOT/'src/diagnostics/fast-erosion-derivatives.py',['crack_score','detector'],{'np':np,'ndi':ndi})
 feats=pure(ROOT/'src/diagnostics/exp-j-deriv-source.py',['corridor','feature'],{'np':np,'ndi':ndi,'SEG':[(0,2100),(700,1900)]})
 thr=json.loads((DER/'statistics.json').read_text())['thresholds']['crackHessianShellPerOutputSampleSquared']
 crop=(slice(int(np.floor(1200/fac)),int(np.ceil(2400/fac))+1),slice(0,int(np.ceil(1200/fac))+1))
 raw=read(DER/'data/production-raw.f64be');cracks={};stats={}
 for a in ['J0','J1c','V2','C']:
  h=arr[a]
  if a=='C':inc=read(CTRL/'data/C-erosion.f64be');mask=read(CTRL/'data/C-mask.f64be');k=.8205431216912392
  else:inc=read(OUT/'data'/f'{a}-erosion.f64be');mask=read(OUT/'data'/f'{a}-mask.f64be');k=cal[a][0]
  gz,gx=np.gradient(h,step);q=np.quantile(np.hypot(gx,gz)[1:-1,1:-1],[.5,.99,.999,1]);ez,ex=np.gradient(inc,step)
  cracks[a]=det['detector'](h,thr)[0];np.save(OUT/(a+'-cracks.npy'),cracks[a],allow_pickle=False)
  stats[a]={'k':k,'crackPixels':int(cracks[a].sum()),'markedRegionCracks':int(cracks[a][crop].sum()),
   'maskFullFraction':float((mask>=1).mean()),'maskMean':float(mask.mean()),
   'erosionStdOverTotalStd':float(inc.std()/h.std()),'corrWithProductionRaw':float(np.corrcoef(base.ravel(),h.ravel())[0,1]),
   'corrWithHistoricalUnwarpedRawReference':float(np.corrcoef(raw.ravel(),h.ravel())[0,1]),
   'slope':dict(zip(['p50','p99','p999','max'],map(float,q)),tail=float(q[2]/q[0])),
   'minMax':[float(h.min()),float(h.max())],'std':float(h.std()),
   'colorSaturatedFraction':float((((.02+1.5*h)<-.35)|((.02+1.5*h)>1.2)).mean()),
   'erosionGradientRms':float(np.sqrt(np.mean(ez*ez+ex*ex))),'feature':feats['feature'](cracks[a],fac)}
 # Factorial historical bridge: use the SAME E for each height alternative, recalibrate k by SAME rule.
 hb=read(OUT/'data/historical-4x2-raw.f64be');h2=read(OUT/'data/historical-2x2-raw.f64be')
 factorial={};history={}
 for a in ['D2','J0','J1c']:
  e=read(OUT/'data'/f'{a}-unitE.f64be')
  for b,k,bb in [('H44',cal[a][0],base),('H42',cal[a][1],hb),('H22',cal[a][2],h2)]:
   hh=k*(bb+.25*e)*AMP;cc=det['detector'](hh,thr)[0]
   factorial[a+'_'+b]={'k':k,'count':int(cc.sum()),'crop':int(cc[crop].sum())}
   if (a,b) in [('D2','H22'),('J1c','H42')]:
    ref=read(PREV/'data/B.f64be') if a=='D2' else read(HIST/'data/J1-height.f64be')
    history[a+'_'+b]={'differentBits':int((ref.view(np.uint64)!=hh.view(np.uint64)).sum()),'maxAbs':float(abs(ref-hh).max()),
                       'frozenCount':int(det['detector'](ref,thr)[0].sum()),'reconstructedCount':int(cc.sum())}
    assert history[a+'_'+b]['differentBits']==0,'historical bridge not exact'
 save('statistics.json',{'arms':stats,'threshold':thr,'calibration':cal,'negativeControl':negative,
  'commonHeightVerification':common,'historicalFactorial':factorial,'historicalReplay':history,
  'heightDefinition':'all NEW arms: catalog raw ridge4/scaler4/warp; J0 derivative ridge4/scaler2/warp per request',
  'correlationDefinition':'corrWithProductionRaw uses the exact COMMON catalog raw at the SAME domain-warped coordinates; historical unwarped correlation separately labelled',
  'slopeUnits':'shell height per wu; E-gradient same units; render x blocks=2wu',
  'featureCaveat':'oblique segment 0,2100 to700,1900; halfwidth2 output pixels; spans are dilated component bbox, not seam length'})
 figure(arr,cracks,m)
 assert all(sha(ROOT/p)==v for p,v in before.items()),'locked inputs changed'
 (OUT/'verification.txt').write_text('PASS fresh javac; all arrays finite; source/core/main/old outputs unchanged\n'
  'PASS common production catalog raw identity 591361 samples; common base reconstruction <=5e-16\n'
  'PASS r=0 vs J1c: all height/erosion/mask/unitE bits and calibration equal\n'
  'PASS historical B and old J1 reconstructed bit-exact, no history used as new-control expected value\n',encoding='utf-8')
 save('manifest.json',{'purpose':'corrected common-production-height experiment; diagnostics only','commands':COMMANDS,
  'sourceHashes':{java:sha(ROOT/java),str(Path(__file__).resolve().relative_to(ROOT)):sha(Path(__file__)),
   'src/diagnostics/fast-erosion-derivatives.py':sha(ROOT/'src/diagnostics/fast-erosion-derivatives.py')},
  'lockedFiles':len(before),'allLockedUnchanged':True,'sampling':m,'render':{'eLimits':[-.35,1.2],'lightXZu':[-.5,-.3,1],
    'yProxyFactor':236.44*1.5,'horizontalBlocksPerWu':2,'shade':'.65+.35*max(0,dot)','noPerArmNormalization':True},
  'outputs':{str(p.relative_to(OUT)):sha(p) for p in OUT.rglob('*') if p.is_file() and 'classes' not in p.parts}})
 print(json.dumps({'arms':stats,'historicalFactorial':factorial,'negativeControl':negative},indent=2));print('DONE',OUT)

if __name__=='__main__':main()
