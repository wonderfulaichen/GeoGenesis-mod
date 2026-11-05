from pathlib import Path
import importlib.util,json,hashlib
import numpy as np
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from PIL import Image
out=Path(__file__).resolve().parent;root=out.parents[2]
s=importlib.util.spec_from_file_location('renderer',root/'forge-1.20.1-47.4.10-mdk/tools/render-ridge-placement.py');r=importlib.util.module_from_spec(s);s.loader.exec_module(r)
r.LABELS=['Control: current base','True A: raw node','B: post morph']
summary={}
for name in ['true-transition','true-junction']:
 folder=out/name;arr,images=r.render(folder);w=json.loads((folder/'window.json').read_text());n=w['size'];step=w['stepWu']
 old=root/'forge-1.20.1-47.4.10-mdk/build/ridge-ab-small-20261105'/name[5:]
 for k in [0,2]:
  baseline=np.fromfile(old/(r.NAMES[k]+'.f64be'),dtype='>f8').reshape(257,257)[::2,::2]
  assert np.array_equal(baseline,arr[k]),'B or control changed'
 fig,axes=plt.subplots(3,2,figsize=(13,9),constrained_layout=True)
 rows=[32,64,96]
 for axrow,row in zip(axes,rows):
  for h,c,label in zip(arr,['black','red','blue'],r.LABELS):axrow[0].plot(np.arange(n)*step,h[row],color=c,label=label,lw=.9)
  for h,c,label in zip(arr[1:],['red','blue'],['A-control','B-control']):axrow[1].plot(np.arange(n)*step,h[row]-arr[0][row],color=c,label=label,lw=.9)
  for ax in axrow:ax.set_title(f'{name}, row {row}, z={w["originWu"][1]+row*step} wu');ax.grid(alpha=.3);ax.legend();ax.set_xlabel('distance wu; HS=2');ax.set_ylabel('MC Y blocks')
 fig.savefig(folder/'profiles-detail.png',dpi=140);plt.close(fig)
 pre=[np.fromfile(folder/('pre-'+a+'.f64be'),dtype='>f8').reshape(n,n) for a in r.NAMES]
 import re
 cfg=(out/'config-input.toml').read_text();value=lambda key:float(re.search(r'^\s*'+key+r'\s*=\s*([0-9.]+)',cfg,re.M).group(1))
 mapped=[63+np.clip(e*value('verticalScale'),0,1)*257*value('peakHeightFraction') for e in pre]
 r.palette.panel([r.palette.shade(h) for h in mapped],['Pre-coast control','Pre-coast true A','Pre-coast B'],folder/'pre-coast-comparison.png')
 stats=json.loads((folder/'metrics.json').read_text());stats['controlAndBExactSubsetOfProxyRun']=True
 stats['stageA']='raw signal after one domain evaluation, before plateau/surface/mountain-texture/badlands/shell/clamp'
 for stage,arrays in [('assembled',arr),('pre',pre)]:
  for label,diff in [('A-control',arrays[1]-arrays[0]),('B-control',arrays[2]-arrays[0]),('A-B',arrays[1]-arrays[2])]:diff.astype('>f8').tofile(folder/(stage+'-'+label+'.f64be'))
 np.savetxt(folder/'profiles.csv',np.array([[row,w['originWu'][0]+i*step,w['originWu'][1]+row*step,*[h[row,i] for h in arr]] for row in rows for i in range(n)]),delimiter=',',header='row,xWu,zWu,controlY,trueAY,BY',comments='')
 (folder/'metrics.json').write_text(json.dumps(stats,indent=2));summary[name]=stats
(out/'summary-metrics.json').write_text(json.dumps(summary,indent=2))
print(json.dumps({k:{a:s[a] for a in ['recipe','post','A-vs-B']} for k,s in summary.items()},indent=2))
