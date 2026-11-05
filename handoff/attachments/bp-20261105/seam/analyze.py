import pathlib,json,sys
import numpy as np
from scipy.ndimage import gaussian_filter,distance_transform_edt
from scipy.spatial import cKDTree
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.colors import Normalize,LightSource
O=pathlib.Path(__file__).resolve().parent; BASE=O.parent/'bp-20261105-054953-3413'
a=np.loadtxt(BASE/'bp-coast-slope.csv',delimiter=',',skiprows=1).reshape(201,201,8)
x,z=a[0,:,0],a[:,0,1]; X,Z=np.meshgrid(x,z); b,p,d=a[:,:,3],a[:,:,5],a[:,:,7]
bz,bx=np.gradient(b,2); dz,dx=np.gradient(d,2); slope=np.hypot(bx,bz); dg=np.hypot(dx,dz)
land=b>=63; shoreDist=np.where(land,distance_transform_edt(land),distance_transform_edt(~land))*2
coast=(shoreDist<=16); valid=np.ones(b.shape,bool);valid[[0,-1],:]=False;valid[:,[0,-1]]=False
xd=np.minimum(X%48,48-X%48); zd=np.minimum(Z%48,48-Z%48); gd=np.minimum(xd,zd)
def stats(v):
    v=np.asarray(v);v=v[np.isfinite(v)]
    return {'n':int(v.size),'mean':float(np.mean(v)),'rms':float(np.sqrt(np.mean(v*v))),'p50':float(np.percentile(v,50)),'p95':float(np.percentile(v,95)),'max':float(np.max(v))} if v.size else {'n':0}
def corr(u,v):
    return float(np.corrcoef(u,v)[0,1]) if len(u)>3 and np.std(u)>1e-12 and np.std(v)>1e-12 else None
metrics={'window':{'size':201,'step_wu':2,'extent':[float(x[0]),float(x[-1]),float(z[0]),float(z[-1])],'coast_definition':'B Y=63 sign-change distance transform <=16wu, both sides; raster distance approximation','coast_n':int(coast.sum()),'coast_land_n':int((coast&land).sum())},'method':{'matching':'boundary d<=2wu vs interior d>=12wu. Nearest neighbor WITH replacement on B Y/5 and |grad B|/0.15, caliper Euclidean<=0.5; report overlap and B covariate balance. Bootstrap resamples boundary owner tiles (not independent pixels), 1000 repeats fixed seed. Descriptive, not causal or universal significance.','lag':'residual deltaY=deltaY-gaussian(sigma=6 samples=12wu); coast pairs require both endpoints coast; Pearson within overlap. Also raw delta and B residual controls. Only ~8 periods, irregular mask, no causal inference.','gradients':'central differences at 2wu; cannot resolve sub-2wu seams.','grid_distance':'min distance to x or z multiples of48; exact boundaries absent (coordinates half-integer).'}}
metrics['coast']={k:stats(v[coast&valid]) for k,v in [('deltaY',d),('abs_deltaY',abs(d)),('delta_grad',dg),('B_slope',slope)]}
metrics['distance_bins']=[]
for lo,hi in [(0,2),(2,4),(4,6),(6,10),(10,14),(14,18),(18,24.01)]:
    m=coast&valid&(gd>=lo)&(gd<hi)
    metrics['distance_bins'].append({'range':[lo,hi],'deltaY':stats(d[m]),'delta_grad':stats(dg[m]),'B_slope':stats(slope[m]),'B_Y':stats(b[m])})
features=np.stack([b/5,slope/.15],axis=-1)
rng=np.random.default_rng(913991)
metrics['matched']={}
for label,mask in [('coast',coast),('land',land&~coast)]:
    ib=np.flatnonzero(mask&valid&(gd<=2));ii=np.flatnonzero(mask&valid&(gd>=12))
    dist,near=cKDTree(features.reshape(-1,2)[ii]).query(features.reshape(-1,2)[ib]);keep=dist<=.5
    ib=ib[keep]; ic=ii[near[keep]]
    group=(np.floor(X.ravel()[ib]/48)*10000+np.floor(Z.ravel()[ib]/48)).astype(int); unique=np.unique(group)
    res={'accepted_pairs':len(ib),'candidate_boundary':len(keep),'unique_controls':len(np.unique(ic)),'tile_clusters':len(unique),'caliper_distance':stats(dist[keep])}
    for key,arr in [('B_Y',b),('B_slope',slope),('abs_deltaY',abs(d)),('delta_grad',dg)]:
        left,right=arr.ravel()[ib],arr.ravel()[ic];diff=left-right
        boots=[]
        for q in range(1000):
            picks=rng.choice(unique,len(unique));v=np.concatenate([diff[group==k] for k in picks]);boots.append(v.mean())
        res[key]={'boundary':stats(left),'matched_interior':stats(right),'paired_mean_difference':float(diff.mean()),'tile_bootstrap95':np.percentile(boots,[2.5,97.5]).tolist()}
    metrics['matched'][label]=res
resD=d-gaussian_filter(d,6); resB=b-gaussian_filter(b,6)
rows=[]
for axis in [0,1]:
    for lagwu in range(24,73,2):
        lag=lagwu//2;s1=[slice(None),slice(None)];s2=s1.copy();s1[axis]=slice(None,-lag);s2[axis]=slice(lag,None);s1=tuple(s1);s2=tuple(s2)
        for label,mask in [('coast',coast),('land',land)]:
            m=mask[s1]&mask[s2]&valid[s1]&valid[s2]
            rows.append({'axis':'z' if axis==0 else 'x','lag_wu':lagwu,'mask':label,'n':int(m.sum()),'r_residual_delta':corr(resD[s1][m],resD[s2][m]),'r_raw_delta':corr(d[s1][m],d[s2][m]),'r_residual_B':corr(resB[s1][m],resB[s2][m])})
metrics['lags']=rows
# gradient directional agreement descriptive, not particle trajectories
m=coast&valid&(slope>0.05)&(dg>0.01)
cos=(bx*dx+bz*dz)/(slope*dg+1e-20)
metrics['gradient_alignment']={'mask':'coast, B slope>.05, delta slope>.01','abs_cos':stats(abs(cos[m])),'signed_cos':stats(cos[m]),'corr_abs_delta_B_slope':corr(abs(d[coast]),slope[coast])}
# phase regressions controlling height/slope + low-order spatial trend
m=coast&valid; xx=(X[m]-X.mean())/200;zz=(Z[m]-Z.mean())/200
F=np.column_stack([np.ones(m.sum()),xx,zz,xx*xx,xx*zz,zz*zz,b[m]/20,slope[m]])
y=d[m];r=y-F@np.linalg.lstsq(F,y,rcond=None)[0]
periods=[]
for period in range(24,73,2):
    H=np.column_stack([np.sin(2*np.pi*X[m]/period),np.cos(2*np.pi*X[m]/period),np.sin(2*np.pi*Z[m]/period),np.cos(2*np.pi*Z[m]/period)])
    H=H-F@np.linalg.lstsq(F,H,rcond=None)[0];fit=H@np.linalg.lstsq(H,r,rcond=None)[0]
    periods.append({'period_wu':period,'partial_variance_fraction':float(np.sum(fit**2)/np.sum(r*r))})
metrics['phase_regression']={'method':'joint x/z sin/cos controlling quadratic spatial trend, B height and B slope; descriptive finite-window fit; no p-value','n':int(m.sum()),'periods':periods}
(O/'metrics.json').write_text(json.dumps(metrics,indent=2))
# choose separated coast details using largest delta gradient, deterministic and recorded (not representative random sample)
candidates=np.argwhere(coast&valid&(np.indices(b.shape)[0]>24)&(np.indices(b.shape)[0]<176)&(np.indices(b.shape)[1]>24)&(np.indices(b.shape)[1]<176))
ordered=sorted(candidates,key=lambda ij:-dg[tuple(ij)]);centers=[]
for ij in ordered:
    if all(np.linalg.norm(ij-v)>=48 for v in centers): centers.append(ij)
    if len(centers)==3: break
norm=Normalize(min(b.min(),p.min()),max(b.max(),p.max()));light=LightSource(315,45)
rgbs=[light.shade(v,cmap=plt.get_cmap('terrain'),norm=norm,vert_exag=2,dx=4,dy=4,blend_mode='soft') for v in [b,p]]
limit=max(abs(d.min()),abs(d.max()))
def grids(ax):
    for q in np.arange(np.ceil(x[0]/48)*48,x[-1]+1,48):ax.axvline(q,color='black',alpha=.55,lw=.65,ls='--')
    for q in np.arange(np.ceil(z[0]/48)*48,z[-1]+1,48):ax.axhline(q,color='black',alpha=.55,lw=.65,ls='--')
def panel(name,bounds):
    fig,axs=plt.subplots(1,3,figsize=(19,6.8),constrained_layout=True)
    for i,ax in enumerate(axs):
        data=rgbs[i] if i<2 else d
        im=ax.imshow(data,origin='upper',extent=[x[0]-1,x[-1]+1,z[-1]+1,z[0]-1],interpolation='nearest',cmap='RdBu_r' if i==2 else None,vmin=-limit if i==2 else None,vmax=limit if i==2 else None)
        ax.contour(x,z,b,levels=[63],colors='cyan',linewidths=.8)
        grids(ax);ax.set_xlim(bounds[:2]);ax.set_ylim(bounds[3],bounds[2]);ax.set_title(['B frozen input','P exactP','deltaY=P-B'][i]);ax.set_xlabel('x (wu)');ax.set_ylabel('z (wu)')
        if i==2:fig.colorbar(im,ax=ax,label='Y')
        if name=='overview':
            for j,(iz,ix) in enumerate(centers):
                from matplotlib.patches import Rectangle
                ax.add_patch(Rectangle((x[ix]-48,z[iz]-48),96,96,fill=False,edgecolor='magenta',lw=1));ax.text(x[ix],z[iz],str(j+1),color='magenta')
    fig.suptitle('Original 2wu samples enlarged with NEAREST pixels; no invented fine detail\nShared B/P height scale and light315/45; cyan=B coast63; dashed=48wu tiles')
    fig.savefig(O/(name+'.png'),dpi=220);plt.close(fig)
panel('overview',[x[0],x[-1],z[0],z[-1]])
for k,(iz,ix) in enumerate(centers):panel('coast-detail-'+str(k+1),[x[ix]-48,x[ix]+48,z[iz]-48,z[iz]+48])
fig,axs=plt.subplots(2,2,figsize=(14,9),constrained_layout=True)
for ax,axis in zip(axs[0],['x','z']):
    for key in ['r_residual_delta','r_raw_delta','r_residual_B']:
        rr=[r for r in rows if r['axis']==axis and r['mask']=='coast'];ax.plot([r['lag_wu'] for r in rr],[r[key] for r in rr],label=key)
    ax.axvline(48,c='k',ls='--');ax.set_title(axis+' coast pairs lag correlation');ax.legend();ax.set_xlabel('lag wu')
axs[1,0].plot([q['period_wu'] for q in periods],[q['partial_variance_fraction'] for q in periods]);axs[1,0].axvline(48,c='k',ls='--');axs[1,0].set_title('coast phase fit (controlled), not causality')
for key in ['delta_grad','B_slope']:
    axs[1,1].plot([np.mean(q['range']) for q in metrics['distance_bins']],[q[key]['mean'] for q in metrics['distance_bins']],label=key)
axs[1,1].set_title('coast mean gradients vs tile distance');axs[1,1].legend();axs[1,1].set_xlabel('distance wu')
fig.savefig(O/'frequency-grid-metrics.png',dpi=180);plt.close(fig)
# selected boundary points: strongest coastal locations nearest each axis, corners, plus land slopes and matched non-grid locations
sites=[]
for axis,distmap in [('x',xd),('z',zd)]:
    ixes=np.argwhere(coast&valid&(distmap<1))
    chosen=[]
    for ij in sorted(ixes,key=lambda ij:-dg[tuple(ij)]):
        if all(np.linalg.norm(ij-v)>40 for v in chosen):chosen.append(ij)
        if len(chosen)==3:break
    for k,ij in enumerate(chosen):
        zz,xx=Z[tuple(ij)],X[tuple(ij)]
        if axis=='x':xx=round(xx/48)*48
        else:zz=round(zz/48)*48
        sites.append({'name':'coast-'+axis+str(k),'axis':axis,'x':float(xx),'z':float(zz)})
for iz,ix in sorted(np.argwhere(valid&(xd<1)&(zd<1)),key=lambda ij:shoreDist[tuple(ij)])[:3]:
    for axis in ['x','z']:sites.append({'name':'corner-'+str(ix)+'-'+str(iz)+'-'+axis,'axis':axis,'x':float(round(x[ix]/48)*48),'z':float(round(z[iz]/48)*48)})
for axis,distmap in [('x',xd),('z',zd)]:
    ij=max(np.argwhere(land&~coast&valid&(distmap<1)),key=lambda ij:slope[tuple(ij)]);zz,xx=Z[tuple(ij)],X[tuple(ij)]
    if axis=='x':xx=round(xx/48)*48
    else:zz=round(zz/48)*48
    sites.append({'name':'land-'+axis,'axis':axis,'x':float(xx),'z':float(zz)})
for s in list(sites):
    t=s.copy();t['name']='control-'+s['name'];t[s['axis']]+=16;sites.append(t)
(O/'sites.json').write_text(json.dumps(sites,indent=2))
with (O/'queries.csv').open('w') as f:
    f.write('id,x,z\n')
    for s in sites:
        for eps in [.1,.01,.001]:
            for q in [-2,-1,0,1,2]:
                xx=s['x']+(q*eps if s['axis']=='x' else 0);zz=s['z']+(q*eps if s['axis']=='z' else 0)
                f.write('%s|%s|%s,%.12f,%.12f\n'%(s['name'],eps,q,xx,zz))
    for k,s in enumerate(sites[:2]+[sites[3]]):
        for t in np.arange(-24,24.001,.5):
            f.write('profile%d|%.1f,%.12f,%.12f\n'%(k,t,s['x']+(t if s['axis']=='x' else 0),s['z']+(t if s['axis']=='z' else 0)))
    for k in np.linspace(0,40400,16,dtype=int):
        v=a.reshape(-1,8)[k];f.write('replay%d,%.12f,%.12f\n'%(k,v[0],v[1]))
print('metrics and plots ready; centers',[(float(x[ix]),float(z[iz])) for iz,ix in centers],'; sites',len(sites))
