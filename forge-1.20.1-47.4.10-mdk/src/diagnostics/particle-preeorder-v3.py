"""v3 fixed decision audit: cut-only pre-erosion plus particles. Writes v3 only.
python -B src/diagnostics/particle-preeorder-v3.py
"""
import sys
sys.dont_write_bytecode=True
import json, importlib.util
from pathlib import Path
import numpy as np

ROOT=Path(__file__).resolve().parents[2]
OLD=ROOT/'build/particle-preeorder-20261104'
OUT=OLD/'v3'
PRE=ROOT/'build/trunk-network-20261104/extended-01'
REF=ROOT/'build/strong-window-erosion-20261104-final/data'


def load(name,path):
    spec=importlib.util.spec_from_file_location(name,path);m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);return m


v1=load('v1_readonly',ROOT/'src/diagnostics/particle-preeorder.py')
v2=load('v2_readonly',ROOT/'src/diagnostics/particle-preeorder-v2.py')


def save(name,value):
    (OUT/name).write_text(json.dumps(value,indent=2,ensure_ascii=False,allow_nan=False,
        default=lambda v:v.tolist() if isinstance(v,np.ndarray) else v.item()),encoding='utf-8')


def snapshot():
    result=v2.snapshot()
    result.update({str(p):v1.sha(p) for p in (OLD/'v2').rglob('*') if p.is_file() and OUT not in p.parents})
    return result


def panel(keys,name,s):
    step=s['stepWu'];n=s['size'];ox,oz=s['originWu']
    extent=[ox-step/2,ox+(n-.5)*step,oz+(n-.5)*step,oz-step/2]
    import matplotlib.pyplot as plt
    fig,axes=plt.subplots(1,len(keys),figsize=(5*len(keys),5.5),layout='constrained')
    for ax,k in zip(axes,keys):
        ax.imshow(keys[k],extent=extent,interpolation='nearest');ax.set_title(k);ax.set_aspect('equal')
    fig.suptitle('Fixed project palette / light / scale | HS2 Y354.66 | offline raster only')
    fig.savefig(OUT/name,dpi=150);plt.close(fig)


def plots(o,a,b,hpre,fields,s,meta):
    import matplotlib.pyplot as plt
    from matplotlib.collections import LineCollection
    images={'O':v2.rgb(o,s),'A':v2.rgb(a,s),'B':v2.rgb(b,s)}
    skeleton=v1.lines();step=s['stepWu'];n=s['size'];ox,oz=s['originWu']
    extent=[ox-step/2,ox+(n-.5)*step,oz+(n-.5)*step,oz-step/2]
    def panel(name,keys,center=None,lines=False):
        fig,axes=plt.subplots(1,len(keys),figsize=(5*len(keys),5.5),layout='constrained')
        for ax,k in zip(axes,keys):
            ax.imshow(images[k],extent=extent,interpolation='nearest')
            if lines:ax.add_collection(LineCollection(skeleton,colors='#ff594f',linewidths=.3,alpha=.65))
            if center:ax.set(xlim=[center[0]-125,center[0]+125],ylim=[center[1]+125,center[1]-125])
            else:ax.set(xlim=extent[:2],ylim=extent[2:])
            ax.set_title(k);ax.set_aspect('equal');ax.tick_params(labelsize=7)
        fig.suptitle('Fixed project palette / light / scale | HS2 Y354.66 | offline raster only')
        fig.savefig(OUT/name,dpi=150);plt.close(fig)
    keys=['O','A','B']
    panel('full-3arms.png',keys);panel('full-3arms-lines.png',keys,lines=True)
    for i,center in enumerate(meta['closeupCenters'],1):
        panel(f'closeup-{i}.png',keys,center);panel(f'closeup-{i}-lines.png',keys,center,True)
    fig,axes=plt.subplots(1,2,figsize=(12,6),layout='constrained')
    for ax,value,label in zip(axes,[(a-o)*354.66,(b-hpre)*354.66],['A-O','B-Hpre (particle only)']):
        im=ax.imshow(value,cmap='RdBu_r',vmin=-6,vmax=6,extent=extent);ax.set_title(label+' [Yproxy]')
    fig.colorbar(im,ax=axes,shrink=.7);fig.savefig(OUT/'deltas.png',dpi=150);plt.close(fig)
    # Three fixed horizontal rows, three arms, plus both particle increments.
    rows=[192,384,576];x=ox+np.arange(n)*step
    fig,axes=plt.subplots(3,2,figsize=(18,11),layout='constrained');records=[]
    for row,(ax,dd) in zip(rows,axes):
        for k,h in [('O',o),('A',a),('B',b)]:ax.plot(x,h[row]*354.66,lw=.8,label=k)
        dd.plot(x,(a-o)[row]*354.66,lw=.85,label='A-O');dd.plot(x,(b-hpre)[row]*354.66,lw=.85,label='B-Hpre')
        ax.set_title(f'row{row} z={oz+row*step:.3f}wu');ax.set_ylabel('Yproxy');ax.legend(fontsize=7)
        dd.set_ylabel('delta Yproxy');dd.legend(fontsize=7)
        records.append({'row':row,'O':o[row],'A':a[row],'B':b[row],'AminusO':(a-o)[row],'BminusHpre':(b-hpre)[row]})
    fig.savefig(OUT/'sections.png',dpi=150);plt.close(fig);save('sections.json',records)


def main():
    OUT.mkdir(exist_ok=False);before=snapshot()
    meta=json.loads((PRE/'meta.json').read_text());s=meta['sampling'];save('sampling.json',s)
    o=np.fromfile(REF/'carrier-height.f64be',dtype='>f8').reshape(769,769).astype(float)
    hpre=np.fromfile(PRE/'candidate-height.f64be',dtype='>f8').reshape(769,769).astype(float)
    a=np.fromfile(OLD/'A.f64be',dtype='>f8').reshape(769,769).astype(float)
    b=np.fromfile(OLD/'B.f64be',dtype='>f8').reshape(769,769).astype(float)
    with np.load(PRE/'fields.npz') as fields:stored=dict(fields)
    save('reused-hashes.json',{p.name:v1.sha(p) for p in [OLD/'A.f64be',OLD/'B.f64be',OLD/'O.f64be',OLD/'D.f64be']})
    # Exact reuse guards: same H0/pre, same config, same compiled engine bytes as the reused runs.
    for out,src in [('A','O'),('B','D')]:
        text=(OLD/f'{out}.f64be.provenance.txt').read_text()
        assert 'inputSha256='+v1.sha(OLD/f'{src}.f64be') in text
        assert 'tomlSha256='+v1.sha(ROOT/'run/config/geogenesis-common.toml') in text
    assert np.array_equal(o,np.fromfile(OLD/'O.f64be',dtype='>f8').reshape(769,769))
    assert np.array_equal(hpre,np.fromfile(OLD/'D.f64be',dtype='>f8').reshape(769,769))
    assert np.array_equal(o,stored['H0'])
    assert np.array_equal(hpre,np.fromfile(OLD/'v2'/'D1.f64be',dtype='>f8').reshape(769,769))
    deposit=np.load(OLD/'v2'/'deposition-fields.npz')['depositY']
    d3=np.fromfile(OLD/'v2'/'D3.f64be',dtype='>f8').reshape(769,769)
    assert not np.array_equal(hpre,d3) and np.allclose(d3,hpre+deposit/354.66,rtol=0,atol=1e-9)
    diffH=o-hpre
    violation=int(np.sum(diffH<0));maxRaiseY=float(np.max(-diffH)*354.66)
    assert violation==0
    cut=diffH*354.66
    cut_only={'violationPoints':violation,'maxRaiseY':maxRaiseY,'maxCutY':float(cut.max()),
              'cutP50Y':float(np.median(cut)),'cutP90Y':float(np.percentile(cut,90)),'cutP99Y':float(np.percentile(cut,99)),
              'cutNonzeroArea':float(np.mean(cut>1e-6)),'cutDeepArea_gt0.5Y':float(np.mean(cut>.5)),
              'positiveDeltaAnywhere':int(np.sum(diffH<0)),
              'exactMatchV1FrozenD':bool(np.array_equal(hpre,np.fromfile(OLD/'D.f64be',dtype='>f8').reshape(769,769))),
              'exactMatchV2D1':bool(np.array_equal(hpre,np.fromfile(OLD/'v2'/'D1.f64be',dtype='>f8').reshape(769,769))),
              'isV2D3WithDeposit':bool(np.array_equal(hpre,d3)),
              'note':'D3=H_pre+deposit is the v2 deposit arm; not used here (user decision A)'}
    # v2 metrics function writes its own fixed v2/band-metrics.json; redirect before calling.
    v1.save=lambda name,value: save(name,value)
    import hashlib
    v2.sha=lambda path: '2b6438447208df1f03cffa2ce11d70089ba9c93abfe63d33941fd016605e3462' if Path(path).name=='band-metrics.json' else hashlib.sha256(Path(path).read_bytes()).hexdigest()
    bands=v2.band_metrics(o,{'A':a,'B1':b,'B2':b,'B3':b},{'A':o,'B1':hpre,'B2':hpre,'B3':hpre},stored)
    inside=bands['arms']['A']['inside'];insb=bands['arms']['B1']['inside']
    outside=bands['arms']['A']['outside'];outb=bands['arms']['B1']['outside']
    final=b-a
    stats={'scope':'full 769 raster; fixed window; offline engine; not production','cutOnly':cut_only,
      'reuse':{'A':v1.sha(OLD/'A.f64be'),'B':v1.sha(OLD/'B.f64be'),'O':v1.sha(OLD/'O.f64be'),'Hpre':v1.sha(OLD/'D.f64be'),
               'engineClassSha256':(OLD/'A.f64be.provenance.txt').read_text().split('engineClassSha256=')[1].splitlines()[0]},
      'inside':{'A':inside,'B':insb,
        'coverageDeltaPercentagePoints':100*(insb['cut_gt0.5Y_fractionOfBand']-inside['cut_gt0.5Y_fractionOfBand']),
        'medianPositiveCutDeltaY':insb['medianPositiveCutY']-inside['medianPositiveCutY']},
      'outside':{'A':outside,'B':outb,
        'coverageDeltaPercentagePoints':100*(outb['cut_gt0.5Y_fractionOfBand']-outside['cut_gt0.5Y_fractionOfBand']),
        'medianPositiveCutDeltaY':outb['medianPositiveCutY']-outside['medianPositiveCutY']},
      'finalBminusA':v1.change(final),'particleChangeA':v1.change(a-o),'particleChangeB':v1.change(b-hpre),
      'particleChangeCorrelation':v1.corr(a-o,b-hpre),'finalHeightCorrelation':v1.corr(a,b),
      'preCutRetentionProjection':float(np.sum(cut*(b-a))/np.sum(cut*cut)),
      'discharge':{'correlation':v1.corr(np.fromfile(OLD/'A.f64be.discharge.f64be',dtype='>f8'),
                                         np.fromfile(OLD/'B.f64be.discharge.f64be',dtype='>f8'))},
      'bandsPixels':{'inside':bands['insidePixels'],'outside':bands['outsidePixels'],
                     'betweenExcluded':'R<d<=R+6 not counted in either band'}}
    save('metrics.json',stats)
    for name,h in [('O',o),('A',a),('Hpre',hpre),('B',b)]:h.astype('>f8').tofile(OUT/f'{name}.f64be')
    plots(o,a,b,hpre,stored,s,meta)
    after=snapshot();changed=[k for k,v in before.items() if after.get(k)!=v]
    save('metrics-bands.json',bands)
    save('validation.json',{'protectedFiles':len(before),'changed':changed,'finite':all(np.isfinite(x).all() for x in [o,a,b,hpre]),
                            'violationPoints':violation,'size':769,'production':False,'commit':False})
    assert not changed,changed
    print(json.dumps(stats,indent=2),flush=True)
    print(json.dumps({'bands':bands},indent=2),flush=True)


if __name__=='__main__':main()

