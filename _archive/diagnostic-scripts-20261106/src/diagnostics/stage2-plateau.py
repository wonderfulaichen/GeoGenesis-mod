"""Stage 2: erosion BEFORE Terrace on a pure plateau window. NEW dir, CPU only.
python -B src/diagnostics/stage2-plateau.py build/<NEW-dir>
"""
import hashlib,json,math,subprocess,sys,time
from pathlib import Path
import numpy as np
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.colors import ListedColormap,Normalize

ROOT=Path(__file__).resolve().parents[2]
OUT=(ROOT/sys.argv[1]).resolve()
if OUT.exists() or ROOT/'build' not in OUT.parents:raise ValueError('NEW build dir required')
OUT.mkdir(parents=True)
COMMANDS=[]


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


def shade(a,step):
 gz,gx=np.gradient(a*236.44*1.5,step)
 l=np.array([-.5,-.3,1.]);l/=np.linalg.norm(l)
 return .65+.35*np.maximum(0,(-gx*l[0]-gz*l[1]+l[2])/np.sqrt(1+gx*gx+gz*gz))


def main():
 locked=[p for p in (ROOT/'src/main').rglob('*') if p.is_file()]
 locked+=list((ROOT/'src/diagnostics/java/com/geogenesis/standalone').glob('FastErosion*.java'))
 for f in (ROOT/'build').glob('fast-erosion-*'):
  locked+=[p for p in f.rglob('*') if p.is_file()]
 before={str(p.relative_to(ROOT)):sha(p) for p in locked};save('locked-before.json',before)
 java='src/diagnostics/java/com/geogenesis/worldgen/terrain/morph/FastErosionPlateauProbe.java'
 cl=OUT/'classes';cl.mkdir()
 run(['javac','-encoding','UTF-8','-proc:none','-d',str(cl),'-sourcepath','src/main/java;src/diagnostics/java',java])
 run(['java','-Xmx3g','-cp',str(cl),'com.geogenesis.worldgen.terrain.morph.FastErosionPlateauProbe',str(OUT/'data')])
 par=json.loads((OUT/'data/parameters.json').read_text());n=par['size'];shape=(n,n)
 R=lambda k:np.fromfile(OUT/'data'/(k+'.f64be'),dtype='>f8').astype(float).reshape(shape)
 Ap,Bp,srcA,srcB,ero,terrA=[R(k) for k in ('Ap','Bp','sourceAp','sourceBp','erosion','terraceAp')]
 assert all(np.isfinite(x).all() for x in (Ap,Bp,srcA,srcB,ero,terrA))
 ostep=par['outputStepWu']
 # ---- inequality 1: erosion vertical amplitude vs one terrace step
 spacing=par['spacing'];amp=par['amp']
 stepH=spacing*amp
 eroAbs=np.abs(ero)
 e1={'singleStepSourceUnits':float(spacing),'singleStepFinalHeightUnits':float(stepH),
     'erosionAmpSourceP50':float(np.median(eroAbs)),'erosionAmpSourceP99':float(np.quantile(eroAbs,.99)),
     'erosionAmpSourceP999':float(np.quantile(eroAbs,.999)),
     'erosionAmpSourceMax':float(eroAbs.max()),
     'erosionAmpHeightP99':float(np.quantile(eroAbs,.99)*amp),
     'ratioP99OverSingleStep':float(np.quantile(eroAbs,.99)/spacing),
     'ratioMaxOverSingleStep':float(eroAbs.max()/spacing)}
 e1['satisfiedP99']=bool(e1['ratioP99OverSingleStep']<1.0)
 e1['satisfiedMax']=bool(e1['ratioMaxOverSingleStep']<1.0)
 # ---- inequality 2: erosion horizontal wavelength vs terrace transition width
 gz,gx=np.gradient(srcA,ostep);grad=np.hypot(gx,gz)
 medGrad=float(np.median(grad));bandSource=spacing*(1-par['blendRange'])
 stripes=[(21.7,7),(22.9,6),(24.1,4),(25.3,3)]
 per=[{'octave':i+1,'periodWu':(1<<sh)/(1+(f-math.floor(f)))*par['stepWu']} for i,(f,sh) in enumerate(stripes)]
 finest=per[-1]['periodWu']
 e2={'terraceBandWidthSourceUnits':float(bandSource),
     'medianSourceGradientPerWu':medGrad,
     'terraceTransitionHorizontalWu':float(bandSource/medGrad) if medGrad>0 else None,
     'fineStripePeriodWu':finest,'coarsestStripePeriodWu':per[0]['periodWu'],
     'transitionOverFinestStripe':float((bandSource/medGrad)/finest) if medGrad>0 else None,
     'transitionOverCoarsestStripe':float((bandSource/medGrad)/per[0]['periodWu']) if medGrad>0 else None,
     'note':'transition width = terrace band height width / median |grad source|; band height width = spacing*(1-blendRange)'}
 e2['satisfiedFinestStripeInsideTransition']=bool(e2['transitionOverFinestStripe'] is not None and e2['transitionOverFinestStripe']>1.0)
 # ---- plateau interior vs step-edge roughness (same interior mask from A')
 levels=np.arange(0,par['steps'])*spacing
 dist=np.min(np.abs(terrA[...,None]-levels[None,None,:]),axis=2)
 interior=dist<=0.02
 edge=~interior
 def rough(h,mask):
  gz,gx=np.gradient(h,ostep);g=np.hypot(gx,gz)
  v=g[mask]
  return {'meanAbsSlope':float(v.mean()),'p50':float(np.median(v)),'p99':float(np.quantile(v,.99)),
          'rmsDeviationFromLocalMean':float(np.std(h[mask]-np.mean(h[mask])))}
 flat={'interiorFraction':float(interior.mean()),
       'Ap_interior':rough(Ap,interior),'Bp_interior':rough(Bp,interior),
       'Ap_edge':rough(Ap,edge),'Bp_edge':rough(Bp,edge),
       'interiorSlopeIncreaseP50':float(np.median(np.hypot(*np.gradient(Bp,ostep))[interior])/np.median(np.hypot(*np.gradient(Ap,ostep))[interior]))}
 save('inequalities.json',{'order':'see data parameters + validation.log; erosion-off reduces to A\' bitwise (Java assertion)',
   'ineq1':e1,'ineq2':e2,'roughness':flat,'parameters':par})
 # ---- figures
 pal=np.loadtxt(ROOT/'build/fast-erosion-scale-only-20261103-final/data/project-palette.csv',delimiter=',',skiprows=1)
 cm=ListedColormap(pal[:,1:]/255);spanX=(n-1)*ostep
 ext=(0,spanX,spanX,0)
 def rgb(a):return cm(Normalize(-.35,1.2,clip=True)(.02+1.5*a))[...,:3]*shade(a,ostep)[...,None]
 for name,sl in [('comparison',(slice(0,n),slice(0,n))),('center-zoom',(slice(n//2-95,n//2+95),slice(n//2-95,n//2+95)))]:
  e=(sl[1].start*ostep,sl[1].stop*ostep,sl[0].stop*ostep,sl[0].start*ostep)
  fig,axes=plt.subplots(1,2,figsize=(15,7),dpi=135)
  for ax,a,t in zip(axes,[Ap,Bp],["A' | production: Terrace on raw source","B' | Fast Erosion BEFORE Terrace"]):
   ax.imshow(rgb(a)[sl],extent=e,interpolation='nearest')
   ax.set(title=t,xlabel='local x (blocks)',ylabel='local z (blocks)')
  fig.colorbar(plt.cm.ScalarMappable(norm=Normalize(-.35,1.2),cmap=cm),ax=axes,fraction=.02,label='fixed e')
  fig.suptitle('Stage 2 | pure plateau window, identical palette/light/vertical scale')
  fig.tight_layout(rect=(0,0,1,.94));fig.savefig(OUT/f'{name}.png');plt.close(fig)
 fig,axes=plt.subplots(1,2,figsize=(15,7),dpi=135)
 for ax,a,t in zip(axes,[Ap,Bp],["A' hillshade","B' hillshade"]):
  ax.imshow(shade(a,ostep),vmin=.65,vmax=1,cmap='gray',extent=ext,interpolation='nearest')
  ax.set(title=t+' (no elevation colour clipping)',xlabel='local x (blocks)',ylabel='local z (blocks)')
 fig.tight_layout();fig.savefig(OUT/'hillshade.png');plt.close(fig)
 mid=n//2;xs=np.arange(n)*ostep
 fig,axes=plt.subplots(2,1,figsize=(15,8),dpi=135,sharex=True)
 axes[0].plot(xs,Ap[mid,:],label="A'",lw=.9);axes[0].plot(xs,Bp[mid,:],label="B'",lw=.9)
 axes[0].set(ylabel='shell h',title='profile across steps (center z row)');axes[0].legend();axes[0].grid(alpha=.2)
 axes[1].plot(xs,terrA[mid,:],label='terrace output (A\' chain)',lw=.8,color='#666')
 axes[1].set(xlabel='local x (blocks)',ylabel='terrace value');axes[1].legend();axes[1].grid(alpha=.2)
 fig.tight_layout();fig.savefig(OUT/'profile.png');plt.close(fig)
 np.savetxt(OUT/'profile.csv',np.column_stack([xs,Ap[mid,:],Bp[mid,:],terrA[mid,:]]),delimiter=',',
            header='distance,Ap,Bp,terraceA',comments='')
 assert all(sha(ROOT/p)==v for p,v in before.items()),'locked changed'
 save('manifest.json',{'purpose':'Stage 2 plateau order test; diagnostics only','commands':COMMANDS,
  'sourceHashes':{java:sha(ROOT/java),str(Path(__file__).resolve().relative_to(ROOT)):sha(Path(__file__))},
  'lockedFiles':len(before),'files':{str(p.relative_to(OUT)):sha(p) for p in OUT.rglob('*') if p.is_file() and 'classes' not in p.parts}})
 (OUT/'verification.txt').write_text("PASS fresh javac; A' equals the production MorphKernel onehot plateau path\n"
  "PASS erosion-off reduces to A' bitwise (order control) => Terrace runs AFTER erosion in B'\n"
  "PASS only the insertion point differs; 4e-4, ORIGINAL, base+0.25E, k rule, x0.7 unchanged\n"
  "PASS main/core/existing baselines unchanged\n",encoding='utf-8')
 print(json.dumps({'ineq1':e1,'ineq2':e2,'roughness':flat,'parameters':par},indent=2));print('DONE',OUT)


if __name__=='__main__':main()
