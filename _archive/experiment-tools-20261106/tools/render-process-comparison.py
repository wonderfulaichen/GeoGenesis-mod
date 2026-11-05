"""Render only authoritative exactP export data, with shared B/P scale/light."""
import sys, pathlib, json, hashlib
import numpy as np
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.colors import LightSource, Normalize
out = pathlib.Path(sys.argv[1]); name = sys.argv[2]
a = np.genfromtxt(str(out / (name + '.csv')), delimiter=',', skip_header=1)
n = int(round(np.sqrt(len(a)))); a = a.reshape(n, n, 8)
x, z = a[0, :, 0], a[:, 0, 1]; step = x[1]-x[0]
b, p, delta = a[:, :, 3], a[:, :, 5], a[:, :, 7]
light = LightSource(azdeg=315, altdeg=45)
norm = Normalize(vmin=min(b.min(),p.min()), vmax=max(b.max(),p.max()))
fig, axes = plt.subplots(1, 3, figsize=(18, 6), constrained_layout=True)
for ax, y, title in zip(axes[:2], [b,p], ['B: frozen P1 base','P: exactP physical particle']):
    rgb = light.shade(y, cmap=plt.get_cmap('terrain'), norm=norm, vert_exag=2,
                      dx=step*2, dy=step*2, blend_mode='soft')
    ax.imshow(rgb, extent=[x[0],x[-1],z[-1],z[0]])
    ax.contour(x,z,y,levels=[63],colors='cyan',linewidths=.6)
    ax.set_title(title); ax.set_xlabel('x (wu)'); ax.set_ylabel('z (wu)')
limit = max(abs(delta.min()),abs(delta.max()))
im = axes[2].imshow(delta, cmap='RdBu_r',vmin=-limit,vmax=limit,extent=[x[0],x[-1],z[-1],z[0]])
axes[2].set_title('deltaY = h(eP) - h(eB)');fig.colorbar(im,ax=axes[2],label='continuous Y')
fig.suptitle('seed 9139912035078620160 | HS=2 | same light 315/45 | same Y color scale')
fig.savefig(str(out/(name+'-comparison.png')),dpi=160);plt.close(fig)
# Fixed three meaningful transects from this accepted B window, not P parameter tuning.
slope = int(np.argmax(np.ptp(b,axis=1)))
coast = n//2  # actual below/above-sea crossing in the center transect
columns = [slope,coast,n//2]
fig, axes = plt.subplots(3,1,figsize=(14,10),constrained_layout=True)
for ax,row,label in zip(axes,columns,['Mountain slope','Coast','Cross-tile']):
    ax.plot(x,b[row],label='B');ax.plot(x,p[row],label='P',alpha=.85)
    ax.axhline(63,color='cyan',linestyle=':',label='sea Y=63')
    for boundary in range(int(np.ceil(x[0]/48))*48,int(x[-1])+1,48): ax.axvline(boundary,color='gray',alpha=.3)
    ax.set_title(label+' | z='+str(z[row])+' wu | gray = 48wu owner boundary')
    ax.set_xlabel('x (wu)');ax.set_ylabel('continuous Y');ax.legend()
    np.savetxt(str(out/(name+'-profile-'+label.replace(' ','-')+'.csv')),a[row],delimiter=',',header='x,z,eB,yB,eP,yP,deltaE,deltaY')
fig.savefig(str(out/(name+'-profiles.png')),dpi=160);plt.close(fig)
print(json.dumps({'size':n,'land':int((a[:,:,2]>=0).sum()),'deltaY_min':float(delta.min()),'deltaY_max':float(delta.max()),'rms':float(np.sqrt((delta**2).mean()))}))
