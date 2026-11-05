import pathlib,json
import numpy as np
from scipy.ndimage import gaussian_filter,map_coordinates,distance_transform_edt
from scipy.spatial import cKDTree
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
O=pathlib.Path(__file__).resolve().parent;metrics=json.loads((O/'metrics.json').read_text());out=[]
def corr(a,b):return float(np.corrcoef(np.ravel(a),np.ravel(b))[0,1]) if np.std(a)>1e-15 and np.std(b)>1e-15 else None
def st(a):return {'min':float(np.min(a)),'max':float(np.max(a)),'rms':float(np.sqrt(np.mean(a*a))),'mean':float(np.mean(a))}
def hp(a):return a-gaussian_filter(a,4)
for t,r in enumerate(metrics):
 D=O/('tile'+str(t));a=np.loadtxt(D/'base.csv',delimiter=',',skiprows=1).reshape(146,146,11);x=a[0,:,0];z=a[:,0,1];X,Z=np.meshgrid(x,z);cx,cz=r['center'];roi=(abs(X-cx)<=40)&(abs(Z-cz)<=40);land=a[:,:,3]>=63;m=roi&((distance_transform_edt(land)+distance_transform_edt(~land))<=16)
 names=(D/'snapshot-names.txt').read_text().splitlines();S=dict(zip(names,np.fromfile(D/'snapshots.bin',dtype='>f4').reshape(-1,146,146).astype(float)));fn=(D/'field-names.txt').read_text().splitlines();F=dict(zip(fn,np.fromfile(D/'fields.bin',dtype='>f8').reshape(-1,4,146,146)))
 steps=np.fromfile(D/'steps.bin',dtype='>f4').reshape(-1,16).astype(float);birth=np.fromfile(D/'births.bin',dtype='>f4').reshape(-1,11).astype(float);hard=np.fromfile(D/'sampled-hardness.bin',dtype='>f4').reshape(146,146).astype(float)
 onset=r['onset'];onrow=int(onset.split('row')[1]);iteration=int(onset[2]);originz=z[0]+9
 # row snapshots include exact original cells through row count. Real trajectories only through same checkpoint.
 spawnGZ=birth[:,6];ids=np.flatnonzero((birth[:,1]<iteration)|((birth[:,1]==iteration)&(spawnGZ<originz+onrow)));tr=steps[np.isin(steps[:,0].astype(int),ids)]
 current=F[onset];e,d,c,v=current;net=-e+d+c;end=F['before-smooth'];final=S['after-deposition-smooth']-S['input']
 sc=[]
 for i in range(3):
  bm=(birth[:,1]==0)&(birth[:,2]==i)&(birth[:,10]==1);pts=np.column_stack([birth[bm,3]+x[0]+.5,birth[bm,4]+z[0]+.5]);near=(abs(pts[:,0]-cx)<=40)&(abs(pts[:,1]-cz)<=40);p=pts[near]
  nn=cKDTree(p).query(p,k=2)[0][:,1]
  frac=pts-np.floor(pts);phase={}
  for per in [1,2,4,7,8,14,16,48]:phase[str(per)]=[float(abs(np.exp(2j*np.pi*pts[:,k]/per).mean())) for k in range(2)]
  sc.append({'scale':['C','M','F'][i],'n_local':len(p),'nearest_neighbor_wu':st(nn),'nearest_neighbor_CV':float(np.std(nn)/np.mean(nn)),'fractional_xy_range':[frac.min(axis=0).tolist(),frac.max(axis=0).tolist()],'world_xy_phase_resultant':phase})
 # Local high-frequency B/H/input controls at same pixels, not a replacement input.
 q={'tile':t,'first_detected_checkpoint':onset,'first_cutting_vs_final_hp':corr(hp(e)[m],hp(final)[m]),'first_net_vs_final_hp':corr(hp(net)[m],hp(final)[m]),'onset_cascade_max':float(abs(c[m]).max()),'final_cascade_max':float(abs(end[2][m]).max()),'hardness_sampled_actual':st(hard[m]),'hardness_hp_vs_cutting_hp':corr(hp(hard)[m],hp(end[0])[m]),'hardness_hp_vs_final_hp':corr(hp(hard)[m],hp(final)[m]),'hardness_hp_vs_onset_cutting_hp':corr(hp(hard)[m],hp(e)[m]),'births':sc,'input_float_minus_B':st(S['input'][m]-a[:,:,2][m]),'onset_erosion':st(e[m]),'onset_deposition':st(d[m]),'onset_net':st(net[m]),'final_erosion':st(end[0][m]),'final_deposition':st(end[1][m]),'final_cutting_to_first_ratio':float(np.sqrt(np.mean(end[0][m]**2))/np.sqrt(np.mean(e[m]**2)))}
 # first FULL iteration is independent of arbitrary sweep-front onset threshold.
 it0=hp(S['it0-drops']-S['input']);it1=hp(S['it1-drops']-S['input']);q['it1_to_it0_hp_rms_ratio']=float(np.linalg.norm(it1[m])/np.linalg.norm(it0[m]));q['it0_final_hp_corr']=corr(it0[m],hp(final)[m]);q['it0_actual_erosion_to_it1_erosion_rms_ratio']=float(np.linalg.norm(F['it0-drops'][0][m])/np.linalg.norm(end[0][m]))
 # Quantify floor away from coast/interaction; sampling flat at actual step old index.
 ix=np.clip(steps[:,4].astype(int),0,145);iz=np.clip(steps[:,5].astype(int),0,145);sm=roi[iz,ix]&m[iz,ix];floor=a[:,:,2]<-.05
 q['all_steps_on_floor_original_cells']=int(np.sum(floor[iz,ix]));q['local_coast_steps_on_floor_original_cells']=int(np.sum(sm&floor[iz,ix]));q['coast_steps_total']=int(sm.sum());q['coast_steps_belowsea']=int(np.sum(sm&(steps[:,10]<0)));q['coast_steps_actual_H_range']=st(steps[sm,10])
 # Show first actual cuts together with same-stage genuine trajectories; normalized heatmaps explicitly labeled.
 fig,axs=plt.subplots(2,3,figsize=(19,12),constrained_layout=True);extent=[x[0]-.5,x[-1]+.5,z[-1]+.5,z[0]-.5]
 panels=[(hp(a[:,:,2]),'Original B HP(sigma4wu) E','RdBu_r'),(e,onset+' actual cutting E','magma'),(net,onset+' actual net E','RdBu_r'),(hard,'Actual bilinear sampled hardness','viridis'),(end[0],'Final actual cutting E','magma'),(hp(final),'Final kernel delta HP E','RdBu_r')]
 for ax,(v,title,cm) in zip(axs.flat,panels):
  im=ax.imshow(v,extent=extent,cmap=cm,interpolation='nearest');fig.colorbar(im,ax=ax);ax.contour(x,z,a[:,:,3],levels=[63],colors='cyan',linewidths=.8);ax.set_xlim(cx-40,cx+40);ax.set_ylim(cz+40,cz-40);ax.set_title(title);ax.set_xlabel('x wu');ax.set_ylabel('z wu')
 tr=tr[tr[:,0]%19==0];axs[0,1].quiver(tr[:,4]+x[0],tr[:,5]+z[0],tr[:,6]-tr[:,4],tr[:,7]-tr[:,5],angles='xy',scale_units='xy',scale=1,width=.0015,color='white',alpha=.7)
 # identical final HP trough contours across actual cutting and hardness panels locate same slots, not arbitrary lines.
 contourlevel=float(np.percentile(hp(final)[m],15))
 for ax in [axs[0,0],axs[0,1],axs[0,2],axs[1,0],axs[1,1]]:ax.contour(x,z,hp(final),levels=[contourlevel],colors='lime',linewidths=.65)
 fig.suptitle('tile%d | first-checkpoint cutting + genuine trajectories up to THAT checkpoint (birthID mod19)\nGreen=identical final trough slots (15th percentile HP); cyan=B coast63. 1wu measured pixels; independent scales explicitly colorbarred.'%t)
 fig.savefig(O/('tile%d-onset-cutting-overlay.png'%t),dpi=170);plt.close(fig);out.append(q)
 print(t,'it ratio',q['it1_to_it0_hp_rms_ratio'],'onset cutcorr',q['first_cutting_vs_final_hp'],'hardcorr',q['hardness_hp_vs_final_hp'],flush=True)
(O/'followup-metrics.json').write_text(json.dumps(out,indent=2))
