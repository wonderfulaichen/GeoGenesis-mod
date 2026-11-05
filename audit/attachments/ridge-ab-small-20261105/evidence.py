from pathlib import Path
import json,csv,hashlib,importlib.util,shutil
import numpy as np
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from PIL import Image
out=Path(__file__).resolve().parent; repo=out.parents[2]; mdk=out.parents[1]
def load(name,path):
 s=importlib.util.spec_from_file_location(name,path);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);return m
palette=load('palette',repo/'tools/cmp-render-versions.py')
light=load('light',repo/'tools/verify-light-convention.py')
# Independent analytic ramp test also covers a genuine pit: the legacy fixture clips its pit to zero.
y,x=np.mgrid[-120:121,-120:121];r=np.hypot(x,y);cone=np.maximum(0,100*(1-r/70));pit=-cone
ul=(r>20)&(r<60)&(x<0)&(y<0);lr=(r>20)&(r<60)&(x>0)&(y>0)
a=light.project_shade(cone,2);b=light.project_shade(pit,2)
assert a[ul].mean()>a[lr].mean() and b[ul].mean()<b[lr].mean()
fig,axs=plt.subplots(1,2,figsize=(7,3))
for ax,h,title in zip(axs,[cone,pit],['Known raised cone','Known hollow pit']):
 palette.WINDOW['step']=2;ax.imshow(palette.shade(h+150));ax.set_title(title);ax.axis('off')
fig.savefig(out/'light-fixture.png',dpi=140);plt.close(fig)
(out/'light-check.json').write_text(json.dumps({'coneUL':float(a[ul].mean()),'coneLR':float(a[lr].mean()),'pitUL':float(b[ul].mean()),'pitLR':float(b[lr].mean()),'pass':True,'legacyFixtureLimitation':'verify-light-convention fixture clips negative pit to zero; independent signed fixture used here'},indent=2))
summary={}
for folder in [out/n for n in ['interior','pure','transition','junction']]:
 w=json.loads((folder/'window.json').read_text());n=w['size'];step=w['stepWu'];hs=w['hs'];palette.WINDOW['step']=step*hs
 pre=[np.fromfile(folder/('pre-'+a+'.f64be'),dtype='>f8').reshape(n,n) for a in ['control','recipe','post']]
 post=[np.fromfile(folder/(a+'.f64be'),dtype='>f8').reshape(n,n) for a in ['control','recipe','post']]
 # Config's existing HeightCurve land conversion, used only for plotting the pre-coast snapshot.
 cfg=(out/'config-input.toml').read_text()
 import re
 def value(key): return float(re.search(r'^\s*'+key+r'\s*=\s*([0-9.]+)',cfg,re.M).group(1))
 vs=value('verticalScale');peak=value('peakHeightFraction')
 mapped=[63+np.clip(e*vs,0,1)*257*peak for e in pre]
 images=[palette.shade(h) for h in mapped]
 palette.panel(images,['Pre-coast control','Pre-coast A-proxy','Pre-coast B'],folder/'pre-coast-comparison.png')
 fig,axs=plt.subplots(3,2,figsize=(13,9),constrained_layout=True)
 rows=[n//4,n//2,3*n//4];dist=np.arange(n)*step
 for axrow,row in zip(axs,rows):
  for h,color,name in zip(post,['black','red','blue'],['control','A-proxy','B']): axrow[0].plot(dist,h[row],color=color,label=name,lw=1)
  for h,color,name in zip(post[1:],['red','blue'],['A-control','B-control']): axrow[1].plot(dist,h[row]-post[0][row],color=color,label=name,lw=.9)
  axrow[0].set_ylabel('MC Y (blocks)');axrow[1].set_ylabel('delta Y (blocks)')
  for ax in axrow:ax.set_title(f'{folder.name}: row {row}, z={w["originWu"][1]+row*step:.1f} wu');ax.grid(alpha=.3);ax.legend();ax.set_xlabel('distance (wu), HS=2')
 fig.savefig(folder/'profiles-detail.png',dpi=140);plt.close(fig)
 with (folder/'profiles.csv').open('w',newline='') as f:
  writer=csv.writer(f);writer.writerow(['row','xWu','zWu','controlY','AproxyY','BY','AminusControl','BminusControl'])
  for row in rows:
   for i in range(n):writer.writerow([row,w['originWu'][0]+i*step,w['originWu'][1]+row*step,*[h[row,i] for h in post],post[1][row,i]-post[0][row,i],post[2][row,i]-post[0][row,i]])
 for stage,arr in [('pre',pre),('assembled',post)]:
  for name,h in zip(['AminusControl','BminusControl','AminusB'],[arr[1]-arr[0],arr[2]-arr[0],arr[1]-arr[2]]):h.astype('>f8').tofile(folder/(stage+'-'+name+'.f64be'))
 samples=list(csv.DictReader((folder/'weights-grid.csv').open()));counts={str(k):sum(int(r['active'])==k for r in samples) for k in range(1,5)}
 stats=json.loads((folder/'metrics.json').read_text());stats['weightGrid']={'points':len(samples),'activeCounts':counts,'dominants':sorted(set(int(r['dominant']) for r in samples)),'maxCoverageAlpha':max(float(r['alpha']) for r in samples)}
 stats['preCoastMetrics']={}
 for name,h in zip(['Aproxy','B','AminusB'],[pre[1]-pre[0],pre[2]-pre[0],pre[1]-pre[2]]):stats['preCoastMetrics'][name]={'meanAbsE':float(abs(h).mean()),'maxAbsE':float(abs(h).max())}
 stats['render']={'light':[-.5,-.35,1],'horizontalSampleBlocks':step*hs,'vertical':'existing MC Y, no extra VS','colors':'fixed cmp palette -64..320','preStage':'covered land e, before ocean/coast, converted with existing land HeightCurve for display','postStage':'MorphOceanAssembler + coast -> HeightCurve, not final MC/particle/model'}
 (folder/'metrics.json').write_text(json.dumps(stats,indent=2));summary[folder.name]=stats
(out/'summary-metrics.json').write_text(json.dumps(summary,indent=2))
print(json.dumps({k:{'weights':s['weightGrid'],'A':s['recipe']['meanAbsDeltaY'],'B':s['post']['meanAbsDeltaY'],'ABmax':max(abs(s['A-vs-B']['minDeltaY']),abs(s['A-vs-B']['maxDeltaY']))} for k,s in summary.items()},indent=2))
