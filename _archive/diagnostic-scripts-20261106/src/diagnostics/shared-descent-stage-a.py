"""Stage A ONLY: frozen H0 shared descent structure, not a carved terrain.
Run from MDK: python -B src/diagnostics/shared-descent-stage-a.py build/<NEW>
Stops before catchment-area filtering (B) and height carving (C).
"""
import argparse
import ast
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import subprocess
import sys

ROOT=Path(__file__).resolve().parents[2]
D=ROOT/'src/diagnostics'
J=D/'java/com/geogenesis'
NEW=[J/'standalone/SharedDescentGraph.java',J/'standalone/SharedDescentCheck.java',
     J/'worldgen/terrain/morph/SharedDescentProbe.java',D/'shared-descent-render.py',
     D/'shared-descent-stage-a.py']
BASE=ROOT/'build/strong-window-erosion-20261104-final'


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def save(path,value):
    path.write_text(json.dumps(value,ensure_ascii=False,indent=2,allow_nan=False)+'\n',encoding='utf-8')


def locked():
    paths=[p for p in (ROOT/'src/main').rglob('*') if p.is_file()]
    for folder in [BASE,ROOT/'build/terminal-depth-repair-20261104']:
        paths.extend(p for p in folder.rglob('*') if p.is_file())
    paths.extend(p for p in (D/'java').rglob('*.java') if p not in NEW)
    paths.append(ROOT/'build/fast-erosion-scale-only-20261103-final/data/project-palette.csv')
    return {p.relative_to(ROOT).as_posix():sha(p) for p in sorted(set(paths))}


def command(out,name,args,commands):
    print('COMMAND',name,flush=True)
    with (out/(name+'.log')).open('wb') as log:
        process=subprocess.Popen(args,cwd=ROOT,stdout=subprocess.PIPE,stderr=subprocess.STDOUT)
        for chunk in iter(lambda:process.stdout.read1(65536),b''):
            log.write(chunk);log.flush();sys.stdout.buffer.write(chunk);sys.stdout.buffer.flush()
        code=process.wait()
    commands.append(dict(name=name,argv=args,exitCode=code,logSha256=sha(out/(name+'.log'))))
    save(out/'commands.json',commands)
    if code:raise RuntimeError(name+' exited '+str(code))


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('out');args=parser.parse_args()
    out=(ROOT/args.out).resolve()
    assert out.parent==ROOT/'build' and not out.exists(),'Use fresh direct build/<NEW>'
    assert all(p.is_file() for p in NEW),'Missing implementation files'
    for p in NEW:
        if p.suffix=='.py':ast.parse(p.read_text(encoding='utf-8'))
    baseline=json.loads((BASE/'manifest.json').read_text())
    assert all(sha(BASE/p)==v for p,v in baseline['outputHashes'].items()),'Strong baseline drift'
    frozen=locked();sources={p.relative_to(ROOT).as_posix():sha(p) for p in NEW};commands=[]
    out.mkdir();save(out/'locked-inputs.json',frozen);save(out/'active-sources.json',sources)
    classes=out/'classes';classes.mkdir()
    roots=[*NEW[:3],J/'worldgen/terrain/morph/NoiseFollowingProbe.java']
    command(out,'01-compile',['javac','--release','17','-encoding','UTF-8','-proc:none','-Xlint:all',
        '-Werror','-cp',str(classes),'-d',str(classes),'-sourcepath',
        os.pathsep.join(['src/diagnostics/java','src/main/java'])]+[str(p) for p in roots],commands)
    command(out,'02-synthetic',['java','-cp',str(classes),'com.geogenesis.standalone.SharedDescentCheck'],commands)
    command(out,'03-shared-graph',['java','-Xmx3g','-cp',str(classes),
        'com.geogenesis.worldgen.terrain.morph.SharedDescentProbe',str(out/'data')],commands)
    spec=importlib.util.spec_from_file_location('shared_render',D/'shared-descent-render.py')
    renderer=importlib.util.module_from_spec(spec);spec.loader.exec_module(renderer)
    result=renderer.render(out);save(out/'render-result.json',result)
    assert frozen==locked(),'Frozen source/data changed'
    assert sources=={p.relative_to(ROOT).as_posix():sha(p) for p in NEW},'Implementation changed during run'
    outputs={p.relative_to(out).as_posix():sha(p) for p in out.rglob('*') if p.is_file() and p.name!='manifest.json'}
    save(out/'manifest.json',dict(stage='A STOP',stageBExecuted=False,stageCExecuted=False,
        carvedTerrain=False,productionWiring=False,worldDiscoveryProven=False,
        lockedInputs=frozen,activeSources=sources,commands=commands,outputs=outputs))
    print('STAGE A COMPLETE; WAIT FOR USER SKELETON REVIEW; NO B/C',out,flush=True)


if __name__=='__main__':main()
