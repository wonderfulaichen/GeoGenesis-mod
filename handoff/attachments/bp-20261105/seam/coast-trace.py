import pathlib,json
import numpy as np
from scipy.ndimage import map_coordinates,gaussian_filter1d
from scipy.signal import find_peaks
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
O=pathlib.Path(__file__).resolve().parent;a=np.loadtxt(O.parent/'bp-20261105-054953-3413/bp-coast-slope.csv',delimiter=',',skiprows=1).reshape(201,201,8)
x,z=a[0,:,0],a[:,0,1];b=a[:,:,3];d=a[:,:,7];bz,bx=np.gradient(b,2)
fig,ax=plt.subplots();cs=ax.contour(x,z,b,levels=[63]);paths=cs.allsegs[0];plt.close(fig)
records=[];fig,axs=plt.subplots(3,2,figsize=(16,12),constrained_layout=True)
# Longest contour, sample every2wu in arclength; offset 8wu inland using B gradient. Derived interpolation, NOT new exact samples.
path=max(paths,key=lambda v:np.sum(np.hypot(*np.diff(v,axis=0).T)))
s=np.r_[0,np.cumsum(np.hypot(*np.diff(path,axis=0).T))];ss=np.arange(0,s[-1],2);xx=np.interp(ss,s,path[:,0]);zz=np.interp(ss,s,path[:,1]);coords=np.array([(zz-z[0])/2,(xx-x[0])/2]);gx=map_coordinates(bx,coords,order=1);gz=map_coordinates(bz,coords,order=1);g=np.hypot(gx,gz)
xx+=8*gx/np.maximum(g,1e-9);zz+=8*gz/np.maximum(g,1e-9);coords=np.array([(zz-z[0])/2,(xx-x[0])/2]);vv=map_coordinates(d,coords,order=1);bb=map_coordinates(b,coords,order=1)
# Divide same ring into contiguous thirds; report selection extent, avoid outer-grid clipping
ok=(xx>x[1])&(xx<x[-2])&(zz>z[1])&(zz<z[-2]);indices=np.flatnonzero(ok)
for k,ind in enumerate(np.array_split(indices,3)):
 t=ss[ind];v=vv[ind];r=v-gaussian_filter1d(v,6);r-=np.polyval(np.polyfit(t,r,1),t);freq=np.fft.rfftfreq(len(r),2);power=abs(np.fft.rfft(r*np.hanning(len(r))))**2;period=np.divide(1,freq,out=np.full_like(freq,np.inf),where=freq>0);mask=(period>=6)&(period<=80);peaks,_=find_peaks(-r,prominence=max(.15,float(r.std()*.4)),distance=2)
 order=np.argsort(power[mask])[::-1][:5];sp=np.diff(t[peaks]);rec={'segment':k+1,'n':len(r),'arclength_span':[float(t[0]),float(t[-1])],'B_Y_minmax':[float(bb[ind].min()),float(bb[ind].max())],'top_period_bins_wu':period[mask][order].tolist(),'trough_spacing_wu':sp.tolist(),'median_spacing':float(np.median(sp)) if len(sp) else None,'rms_residual':float(np.sqrt(np.mean(r*r)))};records.append(rec)
 axs[k,0].plot(t,v,label='deltaY');axs[k,0].plot(t,r,label='residual');axs[k,0].scatter(t[peaks],r[peaks],s=12,c='red');axs[k,0].legend();axs[k,0].set_xlabel('coast arclength wu');axs[k,0].set_title('coast ring segment%d, 8wu landward'% (k+1))
 axs[k,1].plot(period[mask][::-1],power[mask][::-1]);axs[k,1].axvline(48,c='k',ls='--');axs[k,1].set_xlabel('wavelength wu');axs[k,1].set_title('Hann power; broad peaks are not exact periodicity')
fig.suptitle('B coast63 contour + 8wu gradient offset; bilinear analysis of ORIGINAL 2wu raster, no new detail');fig.savefig(O/'coast-ring-frequency.png',dpi=180);plt.close(fig)
fig,ax=plt.subplots(figsize=(10,10));ax.imshow(d,extent=[x[0]-1,x[-1]+1,z[-1]+1,z[0]-1],cmap='RdBu_r',vmin=-27,vmax=27,interpolation='nearest');ax.contour(x,z,b,levels=[63],colors='cyan')
for k,ind in enumerate(np.array_split(indices,3)):ax.plot(xx[ind],zz[ind],lw=1,label='segment%d'%(k+1))
for v in np.arange(np.ceil(x[0]/48)*48,x[-1]+1,48):ax.axvline(v,c='k',alpha=.3,ls='--')
for v in np.arange(np.ceil(z[0]/48)*48,z[-1]+1,48):ax.axhline(v,c='k',alpha=.3,ls='--')
ax.legend();ax.set_title('Coast trace location, deltaY, tile48 dashed; original2wu');fig.savefig(O/'coast-ring-location.png',dpi=180);plt.close(fig)
(O/'coast-ring-metrics.json').write_text(json.dumps({'method':'Longest B63 contour, arclength resampled2wu, displaced8wu up B gradient, bilinear raster interpolation. Three contiguous thirds. Gaussian residual sigma12wu, linear trend removed, Hann power. Coast geometry bends trace and offset is approximate; only this ring, only original2wu data. Not proof of particle causality.','records':records},indent=2));print(records)
