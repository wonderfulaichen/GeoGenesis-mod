from pathlib import Path
from collections import Counter,deque
import csv,hashlib,importlib.util,json,sys
from PIL import Image,ImageChops,ImageDraw,ImageFont
OUT=Path(__file__).resolve().parent
ROOT=OUT.parent
MDK=OUT.parents[3]
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
def save(name,obj):
    (OUT/name).write_text(json.dumps(obj,indent=2,ensure_ascii=False),encoding='utf-8')
if (OUT/'render-path.txt').exists():
    current=Path((OUT/'render-path.txt').read_text(encoding='utf-8'))
    if not current.is_absolute(): current=MDK/current
    spec=importlib.util.spec_from_file_location('existing_compare',ROOT/'v4-render-1792780702450/compare.py')
    module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
    report,a,b,changed=module.compare(MDK/'build/hydro-production/prod-1792781015668/production.png',current)
    original,*_=module.compare(MDK/'build/hydro-production/prod-1792770100074/production.png',current)
    save('image-comparison.json',dict(vs949=report,vsOriginal=original))
    diff=ImageChops.difference(a,b)
    diff.save(OUT/'difference.png')
    module.panel([(a,'旧949候选','同seed/config生产入口；保留旧图',None),
                  (b,'halo组件一致舍弃候选','隔离候选；shape保护红灯仍在，未迁生产',None),
                  (diff,'RGB绝对差','颜色不代表增删；没有补画河',None)],
                 [None,None,None],OUT/'comparison-original-size.png')
    assert b.size==(1401,1401)
    assert report['effectiveConfigByteIdentical'] and report['terrainParamsByteIdentical']
    print('RENDER',json.dumps(report['categories']),flush=True)

def components(points,diagonal):
    todo=set(points); groups=[]
    while todo:
        root=next(iter(todo));todo.remove(root);q=deque([root]);found={root}
        while q:
            x,z=q.popleft()
            for dx in [-1,0,1]:
                for dz in [-1,0,1]:
                    if dx==dz==0 or not diagonal and abs(dx)+abs(dz)!=1:continue
                    n=(x+dx,z+dz)
                    if n in todo:todo.remove(n);q.append(n);found.add(n)
        groups.append(found)
    return groups
if (OUT/'pin-new/columns.csv').exists():
    before=list(csv.DictReader((OUT/'pin-old/columns.csv').open(encoding='utf-8')))
    after=list(csv.DictReader((OUT/'pin-new/columns.csv').open(encoding='utf-8')))
    assert len(before)==len(after)==3125
    assert (OUT/'pin-old/params.txt').read_bytes()==(OUT/'pin-new/params.txt').read_bytes()
    patch=[]
    deltas=[]
    for center in [-318,-382,-220,-708,-740]:
        a={(int(r['x']),int(r['z'])):r for r in before if int(r['patch'])==center}
        b={(int(r['x']),int(r['z'])):r for r in after if int(r['patch'])==center}
        assert set(a)==set(b) and len(a)==625
        aw={p for p in a if a[p]['kind']=='3'}
        bw={p for p in b if b[p]['kind']=='3'}
        for p in a:
            if a[p]!=b[p]:deltas.append(dict(patch=center,x=p[0],z=p[1],old=a[p],new=b[p]))
        pair_checks=[]
        for diagonal in [False,True]:
            old_groups=components(aw,diagonal);new_groups=components(bw,diagonal)
            labels={p:i for i,g in enumerate(new_groups) for p in g}
            split=[]
            for i,g in enumerate(old_groups):
                common=g&bw
                if len({labels[p] for p in common})>1:split.append(i)
            pair_checks.append(dict(neighbors=8 if diagonal else 4,oldComponents=len(old_groups),newComponents=len(new_groups),
                                    oldGroupsSplitAmongSurvivingWetPixels=split))
        patch.append(dict(center=[center,96],bounds=[center-12,84,center+12,108],oldWet=len(aw),newWet=len(bw),
                          added=len(bw-aw),removed=len(aw-bw),allFieldChanged=sum(a[p]!=b[p] for p in a),connectivity=pair_checks))
    save('pin-column-comparison.json',dict(patches=patch,columnDeltas=deltas,
        scope='Five actual-production 25x25 patches around changed pin controls. Pixel connectivity only, not hydraulic or whole-river proof. Strict shape identity still FAIL.'))
    print('PIN COMPARISON',json.dumps(patch),flush=True)
print('ANALYSIS PASS',flush=True)
