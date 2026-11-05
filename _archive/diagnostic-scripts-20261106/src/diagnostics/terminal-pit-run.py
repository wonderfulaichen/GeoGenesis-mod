"""Bounded S1 terminal evidence, actual Java sampling; never modifies old run or main."""
import csv
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

ROOT=Path(__file__).resolve().parents[2]
BASE=ROOT/'build/remaining-drop-field-sparse-equilibrated-20261104'
JAVA=ROOT/'src/diagnostics/java/com/geogenesis/worldgen/terrain/morph/TerminalPitProbe.java'


def save(path,value):
    path.write_text(json.dumps(value,ensure_ascii=False,indent=2,allow_nan=False)+'\n',encoding='utf-8')


def snapshot():
    paths=[p for p in BASE.rglob('*') if p.is_file()]
    paths.extend(p for p in (ROOT/'src/main').rglob('*') if p.is_file())
    return {str(p.relative_to(ROOT)).replace('\\','/'):hashlib.sha256(p.read_bytes()).hexdigest() for p in paths}


def requests(out):
    stats=json.loads((BASE/'stats.json').read_text(encoding='utf-8'))
    rows=[]
    traces=json.loads((BASE/'evaluated/traces.json').read_text())['traces']
    for i,t in enumerate(traces):
        x,z=t['points'][-1]
        rows.append(['terminalfull-'+str(i),t['id'],x,z,12,.125])
        if i in (0,3):rows.append(['terminalwide-'+str(i),t['id'],x,z,24,.25])
    for i,t in enumerate(stats['endpoints']):
        for j,p in enumerate(t['pairedSameWorldIndex']):
            if not p['newOnlyRelativeH0']:continue
            x,z=p['world']
            for label,delta,step in [('coarse',0,.0625),('fine',0,.03125),('shift',.015625,.03125)]:
                rows.append(['patch-%d-%d-%s'%(i,j,label),t['trace'],x+delta,z+delta,2,step])
    with (out/'requests.csv').open('w',newline='',encoding='utf-8') as f:
        w=csv.writer(f);w.writerow(['id','trace','cx','cz','half','step']);w.writerows(rows)
    return rows


def repair(audit, repaired):
    """Optional continuation: same fixed requests, fresh solver/evaluation directories."""
    assert audit.is_dir() and not repaired.exists()
    command=[sys.executable,'-B','-u',str(ROOT/'src/diagnostics/terminal-depth-solver.py'),
             str(repaired),str(BASE),str(audit)]
    subprocess.run(command,cwd=ROOT,check=True)
    report=json.loads((repaired/'solver.json').read_text(encoding='utf-8'))
    assert report['success'] and report['apply_allowed'], 'Do not apply a rejected solver result'
    cp=str(audit/'classes')+os.pathsep+str(BASE/'classes')
    commands=[['java','-Xmx3g','-cp',cp,
              'com.geogenesis.worldgen.terrain.morph.TerminalPitProbe',str(repaired/'weights.csv'),
              str(audit/'requests.csv'),str(audit/'after')],
              ['java','-Xmx3g','-cp',cp,
              'com.geogenesis.worldgen.terrain.morph.RemainingDropProbe','evaluate',
              str(repaired/'export'),str(repaired/'weights.csv'),str(repaired/'evaluated')]]
    for args in commands:
        subprocess.run(args,cwd=ROOT,check=True)
    p=ROOT/'src/diagnostics/terminal-pit-evidence.py'
    spec=importlib.util.spec_from_file_location('terminal_evidence',p)
    evidence=importlib.util.module_from_spec(spec);spec.loader.exec_module(evidence)
    evidence.analyze(audit/'before',status='frozen prior diagnosis')
    evidence.analyze(audit/'after',status='finite-grid descent only; not production')
    evidence.render_comparison(audit/'before',audit/'after',audit/'figures-final',status='S1 STOP')
    assert snapshot()==json.loads((audit/'locked.json').read_text()),'Frozen inputs drift'
    print('REPAIR EVIDENCE COMPLETE; S1 STOP',repaired)


def main():
    if len(sys.argv)==4 and sys.argv[1]=='--repair':
        return repair(ROOT/sys.argv[2],ROOT/sys.argv[3])
    out=ROOT/sys.argv[1];out.mkdir(exist_ok=False);locked=snapshot();save(out/'locked.json',locked)
    classes=out/'classes';classes.mkdir()
    command=['javac','--release','17','-encoding','UTF-8','-proc:none','-Xlint:all','-Werror',
             '-cp',str(BASE/'classes'),'-d',str(classes),str(JAVA)]
    with (out/'javac.log').open('wb') as f:subprocess.run(command,cwd=ROOT,stdout=f,stderr=subprocess.STDOUT,check=True)
    requests(out)
    command=['java','-Xmx3g','-cp',str(classes)+';'+str(BASE/'classes'),
             'com.geogenesis.worldgen.terrain.morph.TerminalPitProbe',str(BASE/'weights.csv'),
             str(out/'requests.csv'),str(out/'before')]
    with (out/'before.log').open('wb') as f:subprocess.run(command,cwd=ROOT,stdout=f,stderr=subprocess.STDOUT,check=True)
    assert snapshot()==locked,'Original inputs drifted'
    save(out/'sampling-integrity.json',dict(passed=True,originalInputsUnchanged=True))
    print('DIRECT EVIDENCE',out,flush=True)


if __name__=='__main__':main()
