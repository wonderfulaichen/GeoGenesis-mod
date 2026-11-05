import pathlib,json,numpy as np
from scipy.ndimage import gaussian_filter,distance_transform_edt
O=pathlib.Path(__file__).resolve().parent;mm=json.loads((O/'metrics.json').read_text());out=[]
def corr(a,b):return float(np.corrcoef(a,b)[0,1]) if len(a)>3 and np.std(a)>1e-15 and np.std(b)>1e-15 else None
def stats(a):return dict(n=len(a),min=float(np.min(a)),max=float(np.max(a)),mean=float(np.mean(a)),p50=float(np.median(a))) if len(a) else dict(n=0)
for t,r in enumerate(mm):
 D=O/('tile'+str(t));a=np.loadtxt(D/'base.csv',delimiter=',',skiprows=1).reshape(146,146,11);x=a[0,:,0];z=a[:,0,1];X,Z=np.meshgrid(x,z);cx,cz=r['center'];roi=(abs(X-cx)<=40)&(abs(Z-cz)<=40);land=a[:,:,3]>=63;m=roi&((distance_transform_edt(land)+distance_transform_edt(~land))<=16)
 names=(D/'snapshot-names.txt').read_text().splitlines();S=dict(zip(names,np.fromfile(D/'snapshots.bin',dtype='>f4').reshape(-1,146,146).astype(float)));F=dict(zip((D/'field-names.txt').read_text().splitlines(),np.fromfile(D/'fields.bin',dtype='>f8').reshape(-1,4,146,146)))
 birth=np.fromfile(D/'births.bin',dtype='>f4').reshape(-1,11).astype(float);steps=np.fromfile(D/'steps.bin',dtype='>f4').reshape(-1,16).astype(float);ix=np.clip(steps[:,4].astype(int),0,145);iz=np.clip(steps[:,5].astype(int),0,145);sm=m[iz,ix]
 bz,bx=np.gradient(S['input']);den=np.hypot(bx[iz,ix],bz[iz,ix]);cos=-(steps[:,8]*bx[iz,ix]+steps[:,9]*bz[iz,ix])/np.maximum(den,1e-30)
 aligned=[]
 for it in range(2):
  localids=np.unique(steps[sm&(steps[:,1]==it),0]);
  for lo,hi in [(0,32),(32,128),(128,512),(512,len(localids))]:
   ids=localids[lo:hi];sel=sm&np.isin(steps[:,0],ids)&(den>1e-10);aligned.append(dict(iteration=it,local_drop_rank=[lo,hi],cos_B_downslope=stats(cos[sel])))
 onset=r['onset'];e=F[onset][0];bHP=a[:,:,2]-gaussian_filter(a[:,:,2],4);eHP=e-gaussian_filter(e,4)
 # Birth cross-scale spatial-cell occupancy, no inference of independent streams: hM=31h+17, hF=97h+23.
 maps=[];offset=[]
 for sc in range(3):
  rows=birth[(birth[:,1]==0)&(birth[:,2]==sc)];grid=np.zeros((128,128));grid[(rows[:,6]-(z[0]+9)).astype(int),(rows[:,5]-(x[0]+9)).astype(int)]=1;maps.append(grid);offset.append(rows)
 cross=[]
 for i,j in [(0,1),(0,2),(1,2)]:
  common,ii,jj=np.intersect1d(offset[i][:,5]*100000+offset[i][:,6],offset[j][:,5]*100000+offset[j][:,6],return_indices=True)
  cross.append(dict(scales=[i,j],birthcell_occupancy_corr=corr(maps[i].ravel(),maps[j].ravel()),n_common=len(common),offset_x_corr=corr(offset[i][ii,3]-(offset[i][ii,5]-x[0]),offset[j][jj,3]-(offset[j][jj,5]-x[0])),offset_z_corr=corr(offset[i][ii,4]-(offset[i][ii,6]-z[0]),offset[j][jj,4]-(offset[j][jj,6]-z[0]))))
 cut=F['before-smooth'][0];d=F['before-smooth'][1];cas=F['before-smooth'][2];net=-cut+d+cas;limited=a[:,:,8];
 r2=dict(tile=t,earliest_local_trajectory_alignment=aligned,onset_Bhp_cut_hp_corr=corr(bHP[m],eHP[m]),cross_scale_births=cross,limited_clipping_coast_cells=int(np.sum(m&(abs(net)>.22))),limited_clipping_buffer_cells=int(np.sum(abs(net)>.22)),max_cut_to_cascade=float(abs(cas[m]).max()),coast_step_tracking=int(np.sum(sm&(steps[:,15]>0))),coast_step_positivedelta=int(np.sum(sm&(steps[:,12]>0))),coast_step_negativedelta=int(np.sum(sm&(steps[:,12]<0))),coast_step_ld_it0=stats(steps[sm&(steps[:,1]==0),13]),coast_step_ld_it1=stats(steps[sm&(steps[:,1]==1),13]))
 out.append(r2)
(O/'extra-metrics.json').write_text(json.dumps(out,indent=2))
print(json.dumps(out,indent=2))
