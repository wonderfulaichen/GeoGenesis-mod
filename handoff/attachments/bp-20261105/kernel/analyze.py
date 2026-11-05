import pathlib,json
import numpy as np
from scipy.ndimage import gaussian_filter,map_coordinates,distance_transform_edt
from scipy.signal import find_peaks
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.colors import LightSource,Normalize
O=pathlib.Path(__file__).resolve().parent;B=O.parent/'bp-20261105-054953-3413'
def stats(v):
 v=np.asarray(v);return dict(n=int(v.size),min=float(v.min()),max=float(v.max()),mean=float(v.mean()),rms=float(np.sqrt(np.mean(v*v))),p50=float(np.median(v)),p95=float(np.percentile(v,95))) if v.size else dict(n=0)
def corr(a,b):
 a=np.ravel(a);b=np.ravel(b);return float(np.corrcoef(a,b)[0,1]) if a.size>3 and np.std(a)>1e-14 and np.std(b)>1e-14 else None
def hp(a):return a-gaussian_filter(a,4)
def Y(e):return 63+np.where(e<=0,127*np.maximum(e,-1),236.44*np.minimum(e,1))
def interp(a,xx,zz,x,z):return map_coordinates(a,[zz-z[0],xx-x[0]],order=1,mode='nearest')
def spectrum(v,step):
 v=v-gaussian_filter(v,8/step);p=np.abs(np.fft.rfft((v-v.mean())*np.hanning(len(v))))**2;f=np.fft.rfftfreq(len(v),step);keep=(f>0)&(f<=.5);ii=np.flatnonzero(keep);ii=ii[np.argsort(p[ii])[-5:][::-1]]
 return {'top_period_bins_wu':(1/f[ii]).tolist(),'power_fraction':(p[ii]/max(p[keep].sum(),1e-30)).tolist(),'rms_residual':float(np.sqrt(np.mean(v*v)))}
exact=np.loadtxt(O/'exact-local.csv',delimiter=',',skiprows=1).reshape(3,49,49,9)
original=np.loadtxt(B/'bp-coast-slope.csv',delimiter=',',skiprows=1).reshape(201,201,8)
summary=[]
for t in range(3):
 D=O/('tile'+str(t));a=np.loadtxt(D/'base.csv',delimiter=',',skiprows=1).reshape(146,146,11);x=a[0,:,0];z=a[:,0,1];X,Z=np.meshgrid(x,z)
 names=(D/'snapshot-names.txt').read_text().splitlines();sn=np.fromfile(D/'snapshots.bin',dtype='>f4').reshape(-1,146,146).astype(float);S=dict(zip(names,sn))
 fn=(D/'field-names.txt').read_text().splitlines();fields=np.fromfile(D/'fields.bin',dtype='>f8').reshape(-1,4,146,146);F=dict(zip(fn,fields))
 births=np.fromfile(D/'births.bin',dtype='>f4').reshape(-1,11).astype(float);steps=np.fromfile(D/'steps.bin',dtype='>f4').reshape(-1,16).astype(float)
 center=exact[t,24,24,1:3];cx,cz=center;roi=(abs(X-cx)<=40)&(abs(Z-cz)<=40);land=a[:,:,3]>=63;dist=distance_transform_edt(land)+distance_transform_edt(~land);coast=roi&(dist<=16);m=coast
 de=S['after-deposition-smooth']-S['input'];finalHP=hp(de);raw=F['it1'] if 'it1' in F else F['it1-drops'];eros,dep,cas,vis=raw
 rowmetrics=[]
 for name in names:
  delta=S[name]-S['input'];res=hp(delta)
  rowmetrics.append(dict(stage=name,deltaE=stats(delta[m]),highpassE=stats(res[m]),corr_final_highpass=corr(res[m],finalHP[m]),erosion_sum=float(F[name][0][m].sum()),deposition_sum=float(F[name][1][m].sum()),cascade=stats(F[name][2][m])))
 eligible=[r for r in rowmetrics if r['stage'].startswith('it') and '-row' in r['stage'] and r['corr_final_highpass'] is not None and r['corr_final_highpass']>=.5 and r['highpassE']['rms']>=.1*stats(finalHP[m])['rms']]
 onset=eligible[0]['stage'] if eligible else 'it0-drops'
 total=-eros+dep+cas;tel=total-(S['before-smooth']-S['input'])
 contributions=[]
 for it in range(2):
  for sc in range(3):
   f=F['it%d-scale%d'%(it,sc)];v=-f[0]+f[1]+f[2]
   contributions.append(dict(iteration=it,scale=['C-r7','M-r4','F-r2'][sc],erosion=stats(f[0][m]),deposition=stats(f[1][m]),cascade=stats(f[2][m]),visits=stats(f[3][m]),net_highpass_rms=stats(hp(v)[m])['rms'],net_corr_final=corr(hp(v)[m],finalHP[m])))
 smooth=[]
 for before,after in [('before-smooth','after-smooth'),('after-smooth','after-deposition-smooth')]:
  inc=S[after]-S[before];bh=hp(S[before]-S['input']);ah=hp(S[after]-S['input'])
  smooth.append(dict(before=before,after=after,incrementE=stats(inc[m]),changed=int(np.count_nonzero(inc[m])),highpass_before_rms=stats(bh[m])['rms'],highpass_after_rms=stats(ah[m])['rms'],ratio=float(np.linalg.norm(ah[m])/np.linalg.norm(bh[m])),corr_before_after=corr(bh[m],ah[m]),inc_corr_final=corr(hp(inc)[m],finalHP[m])))
 # Birth position is actual simulator coordinate = hash offset + .5; gate reads the pre-.5 cell.
 birthX=births[:,3]+x[0]+.5;birthZ=births[:,4]+z[0]+.5
 birthmask=(abs(birthX-cx)<=40)&(abs(birthZ-cz)<=40)
 spawnmaps=[]
 for sc in range(3):
  bm=(births[:,2]==sc)&(births[:,1]==0)&(births[:,10]==1)
  hist=np.zeros((146,146));ix=np.clip((births[bm,3]+.5).astype(int),0,145);iz=np.clip((births[bm,4]+.5).astype(int),0,145);np.add.at(hist,(iz,ix),1);spawnmaps.append(hist)
 # Exact source bookkeeping: real buffer writes sum to kernel delta, not trajectory proxy.
 stepX=steps[:,4]+x[0];stepZ=steps[:,5]+z[0];sm=(abs(stepX-cx)<=40)&(abs(stepZ-cz)<=40)
 kz,kx=np.gradient(S['input']);sx=np.clip(steps[:,4].astype(int),0,145);sz=np.clip(steps[:,5].astype(int),0,145)
 dot=-(steps[:,8]*kx[sz,sx]+steps[:,9]*kz[sz,sx]);den=np.hypot(kx[sz,sx],kz[sz,sx]);cos=dot/np.maximum(den,1e-30)
 alignment=[]
 for it in range(2):
  for lo,hi in [(0,128),(128,512),(512,100000000)]:
   # IDs are deterministic original spawn order, no random sampling.
   mm=sm&(steps[:,1]==it)&(steps[:,0]>=lo)&(steps[:,0]<hi)&(den>1e-10)
   alignment.append(dict(iteration=it,spawn_id_range=[lo,hi],n=int(mm.sum()),cos_original_input_downslope=stats(cos[mm])))
 reasonCounts={str(k):int(np.sum(births[:,9]==k)) for k in np.unique(births[:,9])}
 # Local near-shore contour trace using immutable B, not a new simulation input.
 fig0,ax0=plt.subplots();cont=ax0.contour(x,z,a[:,:,3],levels=[63]);segs=[q for q in cont.allsegs[0] if len(q)>3];plt.close(fig0)
 near=[q for q in segs if np.min(np.linalg.norm(q-center,axis=1))<30];q=max(near or segs,key=lambda q:len(q))
 distq=np.r_[0,np.cumsum(np.linalg.norm(np.diff(q,axis=0),axis=1))];ss=np.arange(0,distq[-1],1.0);qx=np.interp(ss,distq,q[:,0]);qz=np.interp(ss,distq,q[:,1]);gz,gx=np.gradient(a[:,:,3]);gqx=interp(gx,qx,qz,x,z);gqz=interp(gz,qx,qz,x,z);gl=np.hypot(gqx,gqz);qx+=8*gqx/np.maximum(gl,1e-20);qz+=8*gqz/np.maximum(gl,1e-20)
 ok=(abs(qx-cx)<=40)&(abs(qz-cz)<=40);runs=np.split(np.flatnonzero(ok),np.where(np.diff(np.flatnonzero(ok))>1)[0]+1);ix=max(runs,key=len);qx=qx[ix];qz=qz[ix];ss=ss[ix]-ss[ix][0]
 trace={'B':interp(a[:,:,2],qx,qz,x,z),'final':interp(de,qx,qz,x,z),'onset':interp(S[onset]-S['input'],qx,qz,x,z),'erosion':interp(eros,qx,qz,x,z),'deposition':interp(dep,qx,qz,x,z),'cascade':interp(cas,qx,qz,x,z),'visits':interp(vis,qx,qz,x,z)}
 for sc in range(3):trace['birth'+str(sc)]=interp(spawnmaps[sc],qx,qz,x,z)
 spectra={k:spectrum(v,1) for k,v in trace.items()}
 tv=trace['final']-gaussian_filter(trace['final'],8);peaks,pr=find_peaks(-tv,prominence=max(np.std(tv)*.25,1e-5),distance=3)
 peakspacing=np.diff(ss[peaks]);phase=[]
 for period in [1,2,4,7,8,14,16,20,30,48]:
  h=np.column_stack([np.ones(len(ss)),ss,ss*ss,np.sin(2*np.pi*ss/period),np.cos(2*np.pi*ss/period)]);fit=h@np.linalg.lstsq(h,tv,rcond=None)[0];h0=h[:,:3];fit0=h0@np.linalg.lstsq(h0,tv,rcond=None)[0];den0=np.sum((tv-fit0)**2)
  phase.append({'period':period,'incremental_R2':float((np.sum((tv-fit0)**2)-np.sum((tv-fit)**2))/max(den0,1e-30))})
 np.savetxt(D/'coast-trace.csv',np.column_stack([ss,qx,qz]+list(trace.values())),delimiter=',',header='s,x,z,'+','.join(trace),comments='')
 # Original-raster replay only where half-integer exact queries intersect original2wu grid.
 ex=exact[t];matched=[]
 for row in ex.reshape(-1,9):
  ix0=(row[1]-original[0,0,0])/2;iz0=(row[2]-original[0,0,1])/2
  if ix0==int(ix0) and iz0==int(iz0) and 0<=ix0<201 and 0<=iz0<201:
   old=original[int(iz0),int(ix0)];matched.append(np.max(np.abs(row[1:]-old)))
 r=dict(tile=t,center=center.tolist(),mask='80x80wu box intersect B63 coast distance<=16wu; integer1wu grid',coast_n=int(m.sum()),inputE=stats(S['input'][m]),originalBE=stats(a[:,:,2][m]),floor_cells=int(np.sum(a[:,:,2][m]<-.05)),floor_allbuffer=int(np.sum(a[:,:,2]<-.05)),hardness=stats(a[:,:,5][m]),actual_sampled_hardness_note='provider values shown; kernel grid is sampled at inputOrigin+16k and bilinearly interpolated',telescoping_max_error=float(abs(tel).max()),bitwise_required=True,stage_metrics=rowmetrics,onset_rule='first original eight-row checkpoint with final HP correlation>=.5 and HP rms>=10% final; detection criterion not mathematical onset',onset=onset,contributions=contributions,smoothing=smooth,birth_reason_counts=reasonCounts,reason_codes={'1':'spawn boundary','2':'spawn below sea','3':'slope probability gate','4':'step start boundary','5':'zero initial gradient','6':'zero direction','7':'moved boundary','8':'nonpositive speed','9':'lifetime exhausted'},local_steps_h0=stats(steps[sm,10]),local_steps_belowsea=int(np.sum(steps[sm,10]<0)),local_steps_speed=stats(steps[sm,14]),local_alignment=alignment,corr_erosion_final_hp=corr(hp(eros)[m],finalHP[m]),corr_visits_erosion_hp=corr(hp(vis)[m],hp(eros)[m]),corr_B_hp_erosion_hp=corr(hp(a[:,:,2])[m],hp(eros)[m]),trace_length_wu=float(ss[-1]),trace_Be=stats(trace['B']),trace_spectra=spectra,trace_trough_s=ss[peaks].tolist(),trace_trough_spacings=peakspacing.tolist(),trace_phase_fits=phase,original_raster_replay_n=len(matched),original_raster_replay_max_error=max(matched) if matched else None)
 summary.append(r)
 # Main same-slot comparison, 1wu new physical samples except explicit exact-P 1wu output.
 extent=[x[0]-.5,x[-1]+.5,z[-1]+.5,z[0]-.5];lim=max(abs(de[roi]).max(),1e-5)
 fig,axs=plt.subplots(2,4,figsize=(22,11),constrained_layout=True);light=LightSource(315,45);norm=Normalize(min(a[:,:,3][roi].min(),Y(S['after-deposition-smooth'])[roi].min()),max(a[:,:,3][roi].max(),Y(S['after-deposition-smooth'])[roi].max()))
 panels=[(a[:,:,3],'B original Y','height'),(a[:,:,6],'kernel input Y','height'),(Y(S[onset]),onset+' Y','height'),(ex[:,:,8],'exact P-B deltaY','exact'),(S[onset]-S['input'],'first detected checkpoint deltaE','delta'),(de,'kernel final deltaE','delta'),(eros,'actual cumulative cutting E','cut'),(cas,'actual cumulative cascade E','cascade')]
 for ax,(v,title,kind) in zip(axs.flat,panels):
  if kind=='height':im=ax.imshow(light.shade(v,cmap=plt.get_cmap('terrain'),norm=norm,vert_exag=2,dx=2,dy=2,blend_mode='soft'),extent=extent,interpolation='nearest')
  elif kind=='exact':im=ax.imshow(v,extent=[ex[0,0,1]-.5,ex[0,-1,1]+.5,ex[-1,0,2]+.5,ex[0,0,2]-.5],cmap='RdBu_r',interpolation='nearest');fig.colorbar(im,ax=ax)
  else:im=ax.imshow(v,extent=extent,cmap='magma' if kind=='cut' else 'RdBu_r',vmin=0 if kind=='cut' else -lim,vmax=float(eros[roi].max()) if kind=='cut' else lim,interpolation='nearest');fig.colorbar(im,ax=ax)
  ax.contour(x,z,a[:,:,3],levels=[63],colors='cyan',linewidths=.8);ax.plot(qx,qz,c='lime',lw=.8)
  for vline in range(int(np.ceil((cx-40)/48))*48,int(cx+40)+1,48):ax.axvline(vline,c='black',ls='--',lw=.5)
  for vline in range(int(np.ceil((cz-40)/48))*48,int(cz+40)+1,48):ax.axhline(vline,c='black',ls='--',lw=.5)
  ax.set_xlim(cx-40,cx+40);ax.set_ylim(cz+40,cz-40);ax.set_title(title);ax.set_xlabel('x wu');ax.set_ylabel('z wu')
 # Trajectories overlay actual cutting, not a replacement for it. Subsample by fixed original birth ID modulus.
 ax=axs[1,2];tr=steps[(steps[:,0]%31==0)&(steps[:,1]==0)]
 ax.quiver(tr[:,4]+x[0],tr[:,5]+z[0],tr[:,6]-tr[:,4],tr[:,7]-tr[:,5],angles='xy',scale_units='xy',scale=1,width=.0017,color='white',alpha=.65)
 fig.suptitle('tile%d center %.1f,%.1f | 1wu actual data; HS2 | cyan B coast Y63; dashed owner48; green trace inland8wu\nB/input/checkpoint shared Y scale/light315/45. Exact P panel only inner49wu; blank outside, no extrapolation.'%(t,cx,cz));fig.savefig(O/('tile%d-stages.png'%t),dpi=170);plt.close(fig)
 # Every stage's highpass field/time metric and attribution.
 fig,axs=plt.subplots(2,2,figsize=(17,10),constrained_layout=True)
 rr=[q for q in rowmetrics if '-row' in q['stage'] or q['stage'] in ['input','before-smooth','after-smooth','after-deposition-smooth']];ii=np.arange(len(rr));axs[0,0].plot(ii,[q['highpassE']['rms'] for q in rr],label='HP rms E');axs[0,0].set_xticks(ii[::3]);axs[0,0].set_xticklabels([q['stage'] for q in rr][::3],rotation=65,ha='right');axr=axs[0,0].twinx();axr.plot(ii,[q['corr_final_highpass'] for q in rr],c='orange',label='corr final');axr.set_ylim(-.1,1.05);axs[0,0].set_title('Original ordered row snapshots, no stage disabled')
 for key in ['final','onset','erosion','cascade']:axs[0,1].plot(ss,trace[key]-gaussian_filter(trace[key],8),label=key)
 axs[0,1].scatter(ss[peaks],tv[peaks],c='black',s=12);axs[0,1].legend();axs[0,1].set_title('Same near-coast slots: detrended actual changes (E)');axs[0,1].set_xlabel('coast arclength wu')
 for it in range(2):
  for sc in range(3):
   f=F['it%d-scale%d'%(it,sc)];v=interp(-f[0]+f[1]+f[2],qx,qz,x,z);axs[1,0].plot(ss,v-gaussian_filter(v,8),label='it%d %s'%(it,['C7','M4','F2'][sc]))
 axs[1,0].legend();axs[1,0].set_title('Actual net write attribution: interleaved scales')
 for key in ['B','birth0','birth1','birth2','erosion','visits']:
  v=trace[key]-gaussian_filter(trace[key],8);axs[1,1].plot(ss,v/max(np.std(v),1e-20),label=key,alpha=.7)
 axs[1,1].legend();axs[1,1].set_title('Normalized descriptive controls; density is NOT erosion')
 fig.savefig(O/('tile%d-mechanisms.png'%t),dpi=170);plt.close(fig)
 # Archive early/late cutting maps at actual snapshots and all row metric tables.
 with (D/'stage-metrics.json').open('w') as w:json.dump(rowmetrics,w,indent=2)
 print(t,'onset',onset,'floor',r['floor_cells'],'telescope',r['telescoping_max_error'],'smooth',[(q['ratio'],q['changed']) for q in smooth],flush=True)
(O/'metrics.json').write_text(json.dumps(summary,indent=2))
