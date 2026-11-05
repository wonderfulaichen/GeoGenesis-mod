import pathlib,json,csv
import numpy as np
from scipy.ndimage import gaussian_filter
from scipy.signal import find_peaks
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
O=pathlib.Path(__file__).resolve().parent;B=O.parent/'bp-20261105-054953-3413'
rows=list(csv.DictReader((O/'exact-samples.csv').open()));data={r['id']:r for r in rows};sites=json.loads((O/'sites.json').read_text());summary={'continuity':[]}
def val(name,eps,q,col):return float(data['%s|%s|%s'%(name,eps,q)][col])
for s in sites:
 for eps in [.1,.01,.001]:
  rec={'site':s['name'],'axis':s['axis'],'x':s['x'],'z':s['z'],'epsilon':eps}
  for col in ['yB','yP','deltaY','deltaE']:
   v=[val(s['name'],eps,q,col) for q in [-2,-1,0,1,2]];l=(v[2]-v[1])/eps;r=(v[3]-v[2])/eps
   # extrapolate from +/-eps,+/-2eps: cancels local first-order gradient
   rec[col]={'right_minus_left':v[3]-v[1],'left_derivative':l,'right_derivative':r,'derivative_mismatch':r-l,'linear_extrapolated_jump':(2*v[3]-v[4])-(2*v[1]-v[0])}
  summary['continuity'].append(rec)
a=np.loadtxt(B/'bp-coast-slope.csv',delimiter=',',skiprows=1).reshape(201,201,8)
replay=[]
for k in np.linspace(0,40400,16,dtype=int):
 orig=a.reshape(-1,8)[k];r=data['replay%d'%k];replay.append(max(abs(float(r[c])-orig[j]) for j,c in enumerate(['x','z','eB','yB','eP','yP','deltaE','deltaY'])))
summary['replay']={'n':16,'max_abs_all_columns':max(replay)}
summary['interpretation_method']='right-left shrinks with epsilon for smooth gradients too; extrapolated jump cancels first order. One-sided secants test C1 separately; controls at +16wu also integer interpolation knots. No arbitrary overall PASS. float bilinear at integer coordinates can leave persistent derivative mismatches even when C0 holds.'
con=list(csv.DictReader((O/'tile-contributions.csv').open()));summary['tile_contributions']={'n':len(con),'reconstruction_max_deltaE_error':0}
for id in set(r['id'] for r in con):
 rr=[r for r in con if r['id']==id];s=sum(float(r['weight'])*float(r['independentDeltaE']) for r in rr)
 summary['tile_contributions']['reconstruction_max_deltaE_error']=max(summary['tile_contributions']['reconstruction_max_deltaE_error'],abs(s-float(data[id]['deltaE'])))
# contribution and finite profile plots
fig,axs=plt.subplots(3,3,figsize=(18,12),constrained_layout=True)
for k in range(3):
 rr=[r for r in rows if r['id'].startswith('profile%d|'%k)];rr.sort(key=lambda r:float(r['id'].split('|')[1]));t=np.array([float(r['id'].split('|')[1]) for r in rr]);bb=np.array([float(r['yB']) for r in rr]);pp=np.array([float(r['yP']) for r in rr]);dd=pp-bb
 ax=axs[k,0];ax.plot(t,bb,label='B Y');ax.plot(t,pp,label='P Y');ax.axhline(63,c='cyan',ls=':');ax.set_ylabel('Y');ax.legend()
 ax=axs[k,1];ax.plot(t,dd,label='deltaY');ax.plot(t,np.gradient(dd,.5),label='d(deltaY)/d wu');ax.plot(t,np.gradient(bb,.5),label='B slope');ax.legend()
 cc=[r for r in con if r['id'].startswith('profile%d|'%k)];owners=sorted(set((r['ownerX'],r['ownerZ']) for r in cc));ax=axs[k,2]
 for owner in owners:
  part=[r for r in cc if (r['ownerX'],r['ownerZ'])==owner];tt=np.array([float(r['id'].split('|')[1]) for r in part]);vv=np.array([float(r['independentDeltaE']) for r in part]);ww=np.array([float(r['weight']) for r in part]);ax.plot(tt,vv,label='tile'+str(owner),lw=.8);ax.plot(tt,vv*ww,ls='--',lw=.8)
 ax.legend(fontsize=6);ax.set_ylabel('deltaE (solid raw, dashed weighted)')
 for ax in axs[k]:ax.axvline(0,c='k',ls='--');ax.set_xlabel('offset from owner boundary wu');ax.grid(alpha=.2)
 axs[k,0].set_title('exactP profile%d (0.5wu new exact samples)'%k)
fig.suptitle('Frozen exactP; owner boundary=0. Independent tiles are diagnostic only, not a changed algorithm.');fig.savefig(O/'exact-coast-profiles-contributions.png',dpi=200);plt.close(fig)
# epsilon summaries and raw CSV
with (O/'continuity-metrics.csv').open('w',newline='') as f:
 w=csv.writer(f);w.writerow(['site','axis','x','z','epsilon','field','right_minus_left','linear_extrapolated_jump','left_derivative','right_derivative','derivative_mismatch'])
 for r in summary['continuity']:
  for col in ['yB','yP','deltaY','deltaE']:
   v=r[col];w.writerow([r['site'],r['axis'],r['x'],r['z'],r['epsilon'],col]+[v[t] for t in ['right_minus_left','linear_extrapolated_jump','left_derivative','right_derivative','derivative_mismatch']])
fig,axs=plt.subplots(1,2,figsize=(15,6),constrained_layout=True)
for s in sites:
 rr=[r for r in summary['continuity'] if r['site']==s['name']];e=[r['epsilon'] for r in rr]
 for ax,key in zip(axs,['linear_extrapolated_jump','derivative_mismatch']):ax.loglog(e,[max(1e-12,abs(r['deltaY'][key])) for r in rr],alpha=.6,ls='--' if s['name'].startswith('control') else '-');ax.set_xlabel('epsilon wu');ax.set_title('deltaY '+key+' (dashed=controls)');ax.grid(alpha=.2)
fig.savefig(O/'epsilon-continuity.png',dpi=180);plt.close(fig)
# short wavelengths and coast-parallel transects: upper shelf and west shelf, off exact shore to sample actual groove zone
x,z=a[0,:,0],a[:,0,1];d=a[:,:,7];b=a[:,:,3];res=d-gaussian_filter(d,6)
transects=[('north-coast-along-x',1,int(np.argmin(abs(z+3035.5))),(-2230,-2070)),('west-coast-along-z',0,int(np.argmin(abs(x+2126.5))),(-3035,-2845)),('east-coast-along-z',0,int(np.argmin(abs(x+1890.5))),(-2970,-2800))]
fig,axs=plt.subplots(3,2,figsize=(16,12),constrained_layout=True);specs=[]
for k,(name,axis,index,lim) in enumerate(transects):
 coord=x if axis==1 else z;sel=(coord>=lim[0])&(coord<=lim[1]);coord=coord[sel];v=(res[index,:] if axis==1 else res[:,index])[sel];raw=(d[index,:] if axis==1 else d[:,index])[sel];base=(b[index,:] if axis==1 else b[:,index])[sel]
 # remove residual mean and linear trend; Hann periodogram (no sub-sample detail)
 vv=v-np.polyval(np.polyfit(coord,v,1),coord);freq=np.fft.rfftfreq(len(vv),2);power=abs(np.fft.rfft(vv*np.hanning(len(vv))))**2;period=np.divide(1,freq,out=np.full_like(freq,np.inf),where=freq>0);mask=(period>=6)&(period<=80)
 peaks,_=find_peaks(-v,prominence=max(.15,float(np.std(v)*.4)),distance=2);spacing=np.diff(coord[peaks]);order=np.argsort(power[mask])[::-1][:5]
 specs.append({'name':name,'fixed_coordinate':float(z[index] if axis==1 else x[index]),'span':lim,'n':len(v),'rms_residual':float(np.sqrt(np.mean(v*v))),'spectral_top_bins_wu':period[mask][order].tolist(),'peak_trough_positions_wu':coord[peaks].tolist(),'trough_spacing_wu':spacing.tolist(),'spacing_median':float(np.median(spacing)) if len(spacing) else None,'B_Y_minmax':[float(base.min()),float(base.max())]})
 ax=axs[k,0];ax.plot(coord,raw,label='deltaY');ax.plot(coord,v,label='residual');ax.scatter(coord[peaks],v[peaks],s=15,c='red');ax.legend();ax.set_title(name+' | actual 2wu data')
 for boundary in np.arange(np.ceil(coord[0]/48)*48,coord[-1]+1,48):ax.axvline(boundary,c='k',ls='--',alpha=.3)
 ax=axs[k,1];ax.plot(period[mask][::-1],power[mask][::-1]);ax.axvline(48,c='k',ls='--');ax.set_title('Hann power, no causal interpretation');ax.set_xlabel('wavelength wu')
fig.suptitle('Coast slope repetitive grooves: short-scale frequencies; spatially varying, NOT proof of tile lock');fig.savefig(O/'coast-short-frequency.png',dpi=180);plt.close(fig)
summary['coast_transects']={'method':'Three fixed shelf transects visually selected from overview; not the exact curved coastline, may mix land heights. Gaussian residual sigma12wu, linear detrend, Hann periodogram; 2wu Nyquist4wu, only report>=6wu; peaks are finite-window bins, not precise physical periods. Trough prominence max(.15Y,.4 std), minimum spacing4wu.','records':specs}
(O/'exact-metrics.json').write_text(json.dumps(summary,indent=2))
print('Replay',summary['replay']);print('Contributions',summary['tile_contributions'])
for typ in ['coast','corner','land','control']:
 rr=[r for r in summary['continuity'] if r['site'].startswith(typ) and r['epsilon']==.001]
 print(typ,'n',len(rr),'max deltaY extrap jump',max(abs(r['deltaY']['linear_extrapolated_jump']) for r in rr),'max derivative mismatch',max(abs(r['deltaY']['derivative_mismatch']) for r in rr))
print('transects',specs)
