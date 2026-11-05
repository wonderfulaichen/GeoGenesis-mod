"""Verify completed S1 terminal evidence and draw actual normal cross-section comparisons.
Run from MDK: python -B src/diagnostics/terminal-pit-verify.py
Does not run a solver or terrain generator. Retains original basin and failed-route labels.
"""
import ast
import csv
import hashlib
import json
from pathlib import Path

import numpy as np
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt

ROOT=Path(__file__).resolve().parents[2]
AUDIT=ROOT/'build/terminal-pit-audit-20261104'
REPAIR=ROOT/'build/terminal-depth-repair-20261104'
PRIOR=ROOT/'build/remaining-drop-field-sparse-equilibrated-20261104'
Y=354.66


def load(p):
    return json.loads(p.read_text(encoding='utf-8'))


def sha(p):
    return hashlib.sha256(p.read_bytes()).hexdigest()


def save(p,v):
    p.write_text(json.dumps(v,ensure_ascii=False,indent=2,allow_nan=False)+'\n',encoding='utf-8')


def table(p):
    with p.open(encoding='utf-8',newline='') as f:
        return list(csv.DictReader(f))


def normal_profiles():
    before=table(PRIOR/'evaluated/cross-sections.csv')
    after=table(REPAIR/'evaluated/cross-sections.csv')
    assert len(before)==len(after)
    groups={}
    for a,b in zip(before,after):
        for k in ['trace','s_wu','offset_wu','world_x','world_z','carrier_H']:
            assert a[k]==b[k],k
        key=(a['trace'],float(a['s_wu']))
        groups.setdefault(key,[]).append([float(a['offset_wu']),float(a['carrier_H']),
                                         float(a['cut_H']),float(b['cut_H'])])
    ids=load(PRIOR/'evaluated/traces.json')['traces']
    fig,axes=plt.subplots(4,2,figsize=(16,16))
    for row,t in enumerate(ids):
        pairs=[(k,v) for k,v in groups.items() if k[0]==t['id']][-2:]
        for col,((_,s),values) in enumerate(pairs):
            v=np.array(values);ax=axes[row,col]
            for i,label,c in [(1,'H0','#377eb8'),(2,'before','#d66036'),(3,'after','#22824b')]:
                ax.plot(v[:,0]*2,v[:,i]*Y,label=label,color=c,lw=1)
            ax.axvline(0,color='#888',lw=.5)
            ax.set(title=':'.join(t['id'].split(':')[-2:])+' | s=%.5g wu'%s,
                   xlabel='actual normal offset (blocks)',ylabel='Actual Yproxy (local absolute range)')
            ax.ticklabel_format(axis='y',style='plain',useOffset=False)
            ax.grid(alpha=.2);ax.legend(fontsize=8)
    fig.suptitle('Fixed final TWO normal sections per route | H0 / prior / repair\nSide slope is not a closed pit; no requirement to manufacture centered valleys')
    fig.tight_layout(rect=(0,.04,1,.94))
    fig.text(.02,.01,'All coordinates/direct H0 samples identical; no rescaling. Yproxy=354.66H, HS=2. S1 finite evidence, NOT MC.',fontsize=9)
    fig.savefig(AUDIT/'figures-final/normal-tail-profiles.png',dpi=140);plt.close(fig)


def verify_budget():
    b=np.genfromtxt(AUDIT/'before/samples.csv',delimiter=',',names=True,dtype=None,encoding='utf-8')
    a=np.genfromtxt(AUDIT/'after/samples.csv',delimiter=',',names=True,dtype=None,encoding='utf-8')
    assert len(a)==len(b)==598564
    for name in ['id','trace','ix','iz','world_x','world_z','H0','Dold']:
        assert np.array_equal(a[name],b[name]),name
    assert np.all(a['Dnew']<=b['Dnew']) and np.all(a['Dnew']>=0)
    assert np.all(a['Hnew']<=a['H0'])
    outside=a['Dold']==0
    assert np.array_equal(a['Hnew'][outside].view(np.uint64),a['H0'][outside].view(np.uint64))
    new=np.fromfile(REPAIR/'evaluated/new-depth.f64be',dtype='>f8')
    old=np.fromfile(PRIOR/'evaluated/new-depth.f64be',dtype='>f8')
    assert len(new)==591361 and np.all(new<=old)
    return dict(directSamples=len(a),sameWorldAndH0Exact=True,pointwiseNoDeepening=True,
                fullGridSamples=len(new),outsideUnchanged=True,fillOrOutletConstruction=False)


def basin_summary():
    before=load(AUDIT/'before/basin-analysis.json');after=load(AUDIT/'after/basin-analysis.json')
    result={}
    for c in ['4','8']:
        for label,doc in [('before',before),('after',after)]:
            for suffix in ['fine','shift']:
                gs=[g for g in doc['grids'] if g['request']['id'].endswith(suffix)]
                ms=[m for g in gs for m in g['connectivity'][c]['Hnew']['minima']
                    if not m['minimumTouchesWindowBoundary']]
                count=sum(m['suspect'] for m in ms)
                result['%s_%s_neighbors%s'%(label,suffix,c)]=dict(interiorMinima=len(ms),suspects=count)
                if label=='after':assert count==0
    result['scope']='Fixed .03125 and shifted patch grids; no continuous/infinite-world guarantee'
    return result


def main():
    locked=load(AUDIT/'locked.json')
    assert all(sha(ROOT/p)==v for p,v in locked.items()),'Main/prior output drift'
    solver=load(REPAIR/'solver.json');stats=load(REPAIR/'stats.json')
    assert solver['success'] and solver['frozen_inputs_unchanged']
    final=solver['rounds'][-1]['validation']
    assert final['centerline_passed'] and final['full_grid_passed']
    assert stats['integrity']['passed']
    budget=verify_budget();basins=basin_summary();normal_profiles()
    inputs=[ROOT/'src/diagnostics'/n for n in ['terminal-pit-run.py','terminal-depth-solver.py',
            'terminal-depth-solver-check.py','terminal-pit-evidence.py','terminal-pit-verify.py']]
    inputs.append(ROOT/'src/diagnostics/java/com/geogenesis/worldgen/terrain/morph/TerminalPitProbe.java')
    for p in inputs:
        if p.suffix=='.py':
            tree=ast.parse(p.read_text(encoding='utf-8'))
            assert all(n.end_lineno-n.lineno+1<=80 for n in ast.walk(tree) if isinstance(n,ast.FunctionDef))
    summary=dict(stage='S1 STOP',actualMinecraftWorld=False,productionWiring=False,S2Executed=False,
        contracts=budget,basinComparison=basins,solverStatus=solver['status'],
        originalRejectedPath='-24:-29 remains rejected; original micro-uphill unmodified',
        inheritedBasinsPreserved=True,centerlinePassedActivePaths=True,
        completeCenteredValleyAcceptance=False,finiteEvidenceOnly=True,
        routeObservations=stats['longitudinal'],performance=load(REPAIR/'evaluated/performance.json'))
    save(AUDIT/'final-verification.json',summary)
    text=['结论：已修复本轮直接采样确认的离线小闭洼，不能宣布所有末端都是居中谷槽。',
          '根因：原方案只约束中心线，空间侧向下切仍能截断原下降方向。',
          '修复：增加H0二维下降边约束；全部系数不超过修复前，仅减少计划下切。',
          '保留完整原噪声、原路径和预算，不填谷、不加深、不造出口。',
          '4/8邻域、细网格与错位网格的原疑点均未再检出新增小闭洼。',
          '原有盆地仍保留；窗口边界和连续域排水未作全局保证。',
          '两条长路径保留原始下切约87%/70%，上游前半段约97%；短路径基本无有效侵蚀。',
          '末端侧坡/最低点偏移仍如实保留；横剖不成居中谷不能直接当小坑。',
          '原先被拒绝的第二条路径维持拒绝，原场微小反坡未改。',
          '同窗近景、法向剖面和疑点最低点—溢出位置剖面均已输出。',
          '本轮为纯Java诊断，非Minecraft实机；未进S2、未接生产、未提交推送。']
    (AUDIT/'outcome.txt').write_text('\n'.join(text)+'\n',encoding='utf-8')
    outputs={}
    for prefix,folder in [('audit',AUDIT),('repair',REPAIR)]:
        for p in folder.rglob('*'):
            if p.is_file() and p.name!='terminal-manifest.json':outputs[prefix+'/'+p.relative_to(folder).as_posix()]=sha(p)
    save(AUDIT/'terminal-manifest.json',dict(lockedInputs=locked,
        sourceHashes={p.relative_to(ROOT).as_posix():sha(p) for p in inputs},outputHashes=outputs,
        result='finite sampled terminal repair; not complete valley/world acceptance'))
    print('FINAL CONTRACT PASS',len(locked),'frozen inputs;',len(outputs),'outputs; S1 STOP')


if __name__=='__main__':main()
