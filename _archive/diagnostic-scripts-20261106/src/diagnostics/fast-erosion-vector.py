"""E: full gradient vector averaging, R=1/3 only; original scalar noise/lap/core unchanged.
Run from MDK: python -B src/diagnostics/fast-erosion-vector.py build/<NEW-dir>
The Java adapter is mechanically derived from the inspected derivative probe under build/.
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
DER=ROOT/'build/fast-erosion-derivatives-20261103-a'
CTRL=ROOT/'build/fast-erosion-controls-20261103-final'
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
 t=time.perf_counter();p=subprocess.run(args,cwd=ROOT,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
 text=p.stdout.decode('utf-8',errors='replace')
 with (OUT/'validation.log').open('a',encoding='utf-8') as f:f.write('\n'+subprocess.list2cmdline(args)+'\n'+text+'\nexit=%d seconds=%.3f\n'%(p.returncode,time.perf_counter()-t))
 print(text,flush=True)
 if p.returncode:raise RuntimeError('command failed')


def pure_functions(path,names):
 tree=ast.parse(path.read_text(encoding='utf-8'))
 scope={'np':np,'ndi':ndi}
 exec(compile(ast.Module(body=[n for n in tree.body if isinstance(n,ast.FunctionDef) and n.name in names],type_ignores=[]),str(path),'exec'),scope)
 return scope


def generate_java():
 source=ROOT/'src/diagnostics/java/com/geogenesis/worldgen/terrain/morph/FastErosionDerivativeProbe.java'
 original=source.read_text(encoding='utf-8')
 s=original.replace('FastErosionDerivativeProbe','FastErosionVectorProbe')
 begin=s.index('    /** Gaussian circular-vector mean;')
 end=s.index('\n    private void fields(',begin)
 s=s[:begin]+'''    /** Gaussian mean of FULL gradients; never normalize unit vectors or restore magnitude. */
    private void smooth(int x, int z, float[] center, float[] out, int radius) {
        double vx = 0, vz = 0, sum = 0;
        float[] d = new float[3]; double sigma = radius / 2.0;
        for (int j = -radius; j <= radius; j++) for (int i = -radius; i <= radius; i++) {
            if (grid == null) direct(x + i, z + j, false, d);
            else grid.basic(x + i, z + j, false, d);
            double weight = StrictMath.exp(-(i * i + j * j) / (2 * sigma * sigma));
            vx += weight * d[0]; vz += weight * d[1]; sum += weight;
        }
        out[0] = (float) (vx / sum); out[1] = (float) (vz / sum);
        out[2] = center[2]; // original five-point Laplacian is EXACTLY preserved.
    }
''' +s[end:]
 s=s.replace('        direct(x, z, mode == 1, dst);\n        if (mode == 2) { float[] c = dst.clone(); smooth(x, z, c, dst); }',
             '        direct(x, z, false, dst);\n        if (mode != 0) { float[] c = dst.clone(); smooth(x, z, c, dst, mode == 1 ? 1 : 3); }')
 s=s.replace('String[] arms = {"S1", "S2", "S3"}', 'String[] arms = {"B", "E1", "E3"}')
 old='''                smooth(x0 + i, z0 + j, center, dst);
                gx[2][j * w + i] = dst[0]; gz[2][j * w + i] = dst[1];'''
 new='''                smooth(x0 + i, z0 + j, center, dst, 1);
                gx[1][j * w + i] = dst[0]; gz[1][j * w + i] = dst[1];
                smooth(x0 + i, z0 + j, center, dst, 3);
                gx[2][j * w + i] = dst[0]; gz[2][j * w + i] = dst[1];'''
 assert s.count(old)==1;s=s.replace(old,new)
 s=s.replace('d[2] = lap[m == 1 ? 1 : 0][at];','d[2] = lap[0][at];')
 # Check the derived adapter never passes the nine-point flag to a call.
 assert 'direct(x, z, mode == 1' not in s
 assert 'smooth(x0 + i, z0 + j, center, dst);' not in s
 s=s.replace('/** Diagnostics-only derivative adapters. Never changes noise, ORIGINAL or the erosion core. */',
             '/** E experiment: full-vector Gaussian average R=1/R=3; original noise, lap and core. */')
 # Correct inherited metadata labels (RADIUS old constant was only descriptive).
 s=s.replace('CAL_COUNT = 8192, RADIUS = 4','CAL_COUNT = 8192, RADIUS = 3')
 s=s.replace('\\"smoothRadiusSamples\\":%d,\\"smoothSigmaSamples\\":2',
             '\\"smoothRadiusSamples\\":%d,\\"radii\\":[1,3],\\"sigmas\\":[0.5,1.5]')
 work=OUT/'generated';work.mkdir()
 path=work/'FastErosionVectorProbe.java';path.write_text(s,encoding='utf-8')
 save('adapter-provenance.json',{'source':str(source),'sourceSha256':sha(source),'generatedSha256':sha(path),
      'onlyTreatments':'Smooth full float-narrowed central gradient vectors with normalized Gaussian kernel R1/sigma.5 or R3/sigma1.5; d2 always original five-point. BASE, core, callback units, replay calibration, real kernel unchanged.',
      'notCoreModification':True,'boundary':'Grid basic() samples true procedural noise outside cache. Not edge clamp, periodic wrapping or per-window statistics.'})
 return path


def figures(arr,cracks,m):
 pal=np.loadtxt(ROOT/'build/fast-erosion-scale-only-20261103-final/data/project-palette.csv',delimiter=',',skiprows=1)
 cm=ListedColormap(pal[:,1:]/255);n=m['size'];step=m['stepWu']*2
 keys=['B','E1','E3','C'];labels=['B | original gradient','E(R=1), sigma=0.5','E(R=3), sigma=1.5','C | prototype smooth input control']
 crop=(slice(int(np.floor(1200/step)),int(np.ceil(2400/step))+1),slice(0,int(np.ceil(1200/step))+1))
 def light(a):
  gz,gx=np.gradient(a*236.44*1.5,step);l=np.array([-.5,-.3,1.]);l/=np.linalg.norm(l)
  return .65+.35*np.maximum(0,(-gx*l[0]-gz*l[1]+l[2])/np.sqrt(1+gx*gx+gz*gz))
 def rgb(a):return cm(Normalize(-.35,1.2,clip=True)(.02+1.5*a))[...,:3]*light(a)[...,None]
 for name,sl,overlay,gray in [('comparison.png',(slice(0,n),slice(0,n)),False,False),('marked-region.png',crop,False,False),
       ('cracks-full.png',(slice(0,n),slice(0,n)),True,False),('marked-region-cracks.png',crop,True,False),
       ('hillshade-full.png',(slice(0,n),slice(0,n)),False,True),('marked-region-hillshade.png',crop,False,True)]:
  ext=(sl[1].start*step,sl[1].stop*step,sl[0].stop*step,sl[0].start*step)
  fig,axes=plt.subplots(2,2,figsize=(16,15),dpi=135)
  for ax,k,title in zip(axes.flat,keys,labels):
   if gray:ax.imshow(light(arr[k])[sl],vmin=.65,vmax=1,cmap='gray',extent=ext,interpolation='nearest')
   else:ax.imshow(rgb(arr[k])[sl],extent=ext,interpolation='nearest')
   if overlay:
    ov=np.zeros((*arr[k].shape,4));ov[cracks[k]]=[1,0,1,.9]
    ax.imshow(ov[sl],extent=ext,interpolation='nearest')
   ax.set(title=title,xlabel='local x (blocks)',ylabel='local z (blocks)')
  fig.suptitle('Full-gradient estimator only | noise HEIGHT and 5-point lap unchanged | frozen detector')
  fig.text(.07,.02,'HS=2; Y proxy236.44*1.5*h; fixed light(-.5,-.3,1), no shade stretching. Color e[-.35,1.2] unless hillshade-only.\nC is the existing smooth-input CONTROL, not a terrain proposal; no production integration.')
  fig.tight_layout(rect=(0,.06,1,.96));fig.savefig(OUT/name);plt.close(fig)
 # Signed differences with a shared fixed range, no per-arm normalization.
 fig,axes=plt.subplots(1,2,figsize=(16,8),dpi=135)
 ext=(0,crop[1].stop*step,crop[0].stop*step,crop[0].start*step)
 for ax,k in zip(axes,['E1','E3']):
  im=ax.imshow((arr[k]-arr['B'])[crop],extent=ext,cmap='RdBu_r',vmin=-.1,vmax=.1,interpolation='nearest')
  ax.set(title=k+' - B',xlabel='local x (blocks)',ylabel='local z (blocks)')
 fig.colorbar(im,ax=axes,fraction=.02,label='shell delta h +/-0.1')
 fig.savefig(OUT/'marked-region-differences.png');plt.close(fig)


def main():
 locked=[p for p in (ROOT/'src/main').rglob('*') if p.is_file()]
 locked+=list((ROOT/'src/diagnostics/java/com/geogenesis/standalone').glob('FastErosion*.java'))
 for folder in (ROOT/'build').glob('fast-erosion-*'):
  if folder!=OUT:locked += [p for p in folder.rglob('*') if p.is_file()]
 before={str(p.relative_to(ROOT)):sha(p) for p in locked};save('locked-before.json',before)
 path=generate_java();classes=OUT/'classes';classes.mkdir()
 run(['javac','-encoding','UTF-8','-proc:none','-d',str(classes),'-sourcepath','src/main/java;src/diagnostics/java',str(path)])
 run(['java','-Xmx3g','-cp',str(classes),'com.geogenesis.worldgen.terrain.morph.FastErosionVectorProbe',str(OUT/'data')])
 m=json.loads((OUT/'data/sampling.json').read_text());n=m['size'];shape=(n,n)
 read=lambda p:np.fromfile(p,dtype='>f8').astype(float).reshape(shape)
 assert sha(OUT/'data/B-height.f64be')==sha(DER/'data/S1-height.f64be'),'B full replay drift'
 assert sha(OUT/'data/A.f64be')==sha(DER/'data/A.f64be'),'A production drift'
 arr={k:read(OUT/('data/'+k+'-height.f64be')) for k in ['B','E1','E3']};arr['C']=read(CTRL/'data/C-height.f64be')
 assert all(np.isfinite(a).all() for a in arr.values())
 detector_path=ROOT/'src/diagnostics/fast-erosion-derivatives.py'
 funcs=pure_functions(detector_path,['detector','crack_score'])
 threshold=json.loads((DER/'statistics.json').read_text())['thresholds']['crackHessianShellPerOutputSampleSquared']
 cracks={};stats={};raw=read(DER/'data/production-raw.f64be')
 sp=m['stepWu']*2;crop=(slice(int(np.floor(1200/sp)),int(np.ceil(2400/sp))+1),slice(0,int(np.ceil(1200/sp))+1))
 for idx,k in enumerate(['B','E1','E3','C']):
  cracks[k],score=funcs['detector'](arr[k],threshold);np.save(OUT/(k+'-cracks.npy'),cracks[k],allow_pickle=False)
  if k=='C':inc=read(CTRL/'data/C-erosion.f64be');mask=read(CTRL/'data/C-mask.f64be');replay=.8205431216912392
  else:inc=read(OUT/('data/'+k+'-erosion.f64be'));mask=read(OUT/('data/'+k+'-mask.f64be'));replay=m['k'][idx]
  gz,gx=np.gradient(arr[k],m['stepWu']);q=np.quantile(np.hypot(gx,gz)[1:-1,1:-1],[.5,.99,.999,1])
  stats[k]={'k':replay,'crackPixels':int(cracks[k].sum()),'ratioVsB':float(cracks[k].sum()/5317),
    'markedRegionCracks':int(cracks[k][crop].sum()),'maskFullFraction':float((mask>=1).mean()),
    'maskMean':float(mask.mean()),'erosionStdOverTotalStd':float(inc.std()/arr[k].std()),
    'corrWithProductionRaw':float(np.corrcoef(raw.ravel(),arr[k].ravel())[0,1]),
    'slope':dict(zip(['p50','p99','p999','max'],map(float,q)),tail=float(q[2]/q[0])),
    'minMax':[float(arr[k].min()),float(arr[k].max())],
    'colorSaturatedFraction':float((((.02+1.5*arr[k])<-.35)|((.02+1.5*arr[k])>1.2)).mean())}
  if k!='C':assert sha(OUT/('data/'+k+'-lap.f64be'))==sha(DER/'data/S1-lap.f64be'),'Laplacian changed!'
 assert stats['B']['crackPixels']==5317 and stats['C']['crackPixels']==19
 sensitivity={}
 score=funcs['crack_score'](arr['B'])[8:-8,8:-8]
 for pct in [.98,.985,.99,.995]:
  th=float(np.quantile(score,pct));sensitivity[str(pct)]={k:int(funcs['detector'](a,th)[0].sum()) for k,a in arr.items()}
 kernels={}
 for radius in [1,3]:
  sigma=radius/2;x=np.arange(-radius,radius+1);one=np.exp(-x*x/(2*sigma*sigma));one/=one.sum()
  kernels[str(radius)]={'radius':radius,'sigma':sigma,'oneDimensionalWeights':one.tolist(),'twoDimensionalWeights':np.outer(one,one).tolist(),
    'formula':'w(i,j)=exp(-(i*i+j*j)/(2*sigma*sigma))/sum(square support); stored 1D outer product equivalent to implementation',
    'radiusWu':radius*m['erosionStepWu']}
 save('kernels.json',kernels)
 save('statistics.json',{'arms':stats,'detectorAbsoluteThreshold':threshold,'sensitivity':sensitivity,
      'lapBitwiseUnchangedEveryOutputSample':True,'radiusDerivation':'R3=floor((8/1.3)/2)=3, sigma1.5; R1 is the explicitly requested minimum-neighbourhood control, sigma.5; no other radius tried',
      'boundary':'True procedural halo; +/-1 central stencil at every kernel tap. R3 requires at most radius+1=4 samples for gradient plus the filter forward neighbour:5; cache uses direct procedural fallback, no window edge replication or clamp.'})
 figures(arr,cracks,m)
 assert all(sha(ROOT/p)==v for p,v in before.items()),'locked main/core/assets changed'
 save('manifest.json',{'purpose':'E full-vector gradient estimator R1/R3 only; not production integration','commands':COMMANDS,
    'sourceHashes':{str(Path(__file__).resolve().relative_to(ROOT)):sha(Path(__file__)),str(path.relative_to(ROOT)):sha(path)},
    'detectorSha256':sha(detector_path),'BFullArrayExact':True,'AFullArrayExact':True,'lockedFiles':len(before),
    'files':{str(p.relative_to(OUT)):sha(p) for p in OUT.rglob('*') if p.is_file() and 'classes' not in p.parts}})
 (OUT/'verification.txt').write_text('PASS fresh javac; generated adapter only full-gradient estimator treatments\nPASS current B and production A full array SHA exact\nPASS five-point lap bitwise unchanged B/E1/E3\nPASS ORIGINAL unchanged; no height/noise edits; offline k calibration unchanged domain/rule\nPASS fixed detector absolute threshold; old baseline/main/core files unchanged\nNOT VERIFIED: annotated ground truth, other seeds/windows, all-octave masks, production blocks\n',encoding='utf-8')
 print(json.dumps(stats,indent=2));print('DONE',OUT)


if __name__=='__main__':main()
