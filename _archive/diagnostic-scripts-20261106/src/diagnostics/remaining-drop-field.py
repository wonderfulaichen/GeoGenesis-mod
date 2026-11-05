"""S1-only reproducible local-depth diagnostic. Run from MDK:
python -B src/diagnostics/remaining-drop-field.py build/<NEW>
Exit 2 retains evidence but rejects solver/bed/shape acceptance. Never installs packages.
"""
import argparse
import ast
import csv
import hashlib
import importlib.util
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[2]
DIAG = ROOT / 'src/diagnostics'
BASE = ROOT / 'build/noise-following-subtractive-20261103-final'
NEW_JAVA = ('standalone/SharedDepthField.java', 'standalone/SharedDepthCheck.java',
            'worldgen/terrain/morph/RemainingDropProbe.java')
JAVA = ROOT / 'src/diagnostics/java/com/geogenesis'
NEW = [JAVA / n for n in NEW_JAVA] + [DIAG / n for n in (
    'remaining-drop-field.py', 'remaining-drop-solver.py', 'remaining-drop-solver-check.py',
    'remaining-drop-render.py', 'requirements-remaining-drop.txt')]


def require(ok, message):
    if not ok:
        raise ValueError(message)


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def save(path, value):
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False, allow_nan=False) + '\n', encoding='utf-8')


def load(path):
    return json.loads(path.read_text(encoding='utf-8'))


def snapshot():
    paths = [p for p in (ROOT / 'src/main').rglob('*') if p.is_file()]
    for folder in (BASE, ROOT / 'build/curved-window-seam-20261103-final/data'):
        paths.extend(p for p in folder.rglob('*') if p.is_file())
    paths.extend(p for p in JAVA.rglob('*.java') if p not in NEW)
    paths += [DIAG / 'noise-following-subtractive.py', ROOT / 'build/fast-erosion-scale-only-20261103-final/data/project-palette.csv']
    return {str(p.relative_to(ROOT)).replace('\\', '/'): sha(p) for p in sorted(set(paths))}


def sources():
    return {str(p.relative_to(ROOT)).replace('\\', '/'): sha(p) for p in NEW}


def module(name):
    spec = importlib.util.spec_from_file_location(name.replace('-', '_'), DIAG / (name + '.py'))
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


def command(out, name, args, commands):
    log = out / 'logs' / (name + '.log')
    start = time.perf_counter()
    print('COMMAND', name, subprocess.list2cmdline(args), flush=True)
    with log.open('wb') as stream:
        process = subprocess.Popen(args, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        for chunk in iter(lambda: process.stdout.read1(65536), b''):
            stream.write(chunk)
            stream.flush()
            sys.stdout.buffer.write(chunk)
            sys.stdout.buffer.flush()
        code = process.wait()
    commands.append(dict(name=name, argv=args, exitCode=code, seconds=time.perf_counter()-start,
                         log=str(log.relative_to(out)), logSha256=sha(log)))
    save(out / 'commands.json', commands)
    require(code == 0, name + ' failed exit=' + str(code))


def verify_export(out, solver):
    export = out / 'export'
    require(sha(export / 'traces.json') == sha(BASE / 'data/traces.json'), 'Frozen traces mismatch')
    for new, old in [('base-raw','base-raw'), ('carrier-height','carrier-height'),
                     ('old-height','cut-height'), ('old-depth','cut-depth')]:
        require(sha(export / (new+'.f64be')) == sha(BASE / ('data/'+old+'.f64be')), 'Frozen '+new)
    for row in csv.DictReader((export / 'basis-check.csv').open(encoding='utf-8')):
        values = dict(solver.basis(float(row['world_x']), float(row['world_z'])))
        key = (int(row['cx']), int(row['cz']))
        require(abs(values[key]-float(row['basis'])) < 1e-14, 'Java/Python basis mismatch')


def compile_and_export(out, commands):
    classes = out / 'classes'
    classes.mkdir()
    old_probe = JAVA / 'worldgen/terrain/morph/NoiseFollowingProbe.java'
    args = ['javac','--release','17','-encoding','UTF-8','-proc:none','-Xlint:all','-Werror',
            '-classpath',str(classes),'-d',str(classes),'-sourcepath',
            os.pathsep.join(('src/diagnostics/java','src/main/java'))]
    command(out,'01-javac',args + [str(p) for p in [*NEW[:3], old_probe]],commands)
    command(out,'02-java-check',['java','-cp',str(classes),'com.geogenesis.standalone.SharedDepthCheck'],commands)
    command(out,'03-python-check',[sys.executable,'-B',str(DIAG/'remaining-drop-solver-check.py')],commands)
    command(out,'04-export',['java','-Xmx3g','-cp',str(classes),
            'com.geogenesis.worldgen.terrain.morph.RemainingDropProbe','export',str(out/'export')],commands)


def solve_and_evaluate(out, commands, solver):
    for round_index in range(3):
        start = time.perf_counter()
        report = solver.solve(out, round_index)
        report['wallSeconds'] = time.perf_counter()-start
        save(out/'solver.json',report)
        round_dir = out / ('solve-r'+str(round_index))
        round_dir.mkdir()
        for name in ('solver.json','weights.csv','target.csv'):
            if (out/name).exists():
                shutil.copy2(out/name,round_dir/name)
        print('SOLVER', report['status'], 'iterations',report['iterations'],flush=True)
        if report.get('retry_allowed') and not report.get('success'):
            continue
        break
    require((out/'weights.csv').exists(), 'No solver coefficients; explicit rejection in solver.json')
    import numpy as np
    weights = np.genfromtxt(out/'weights.csv',delimiter=',',names=True)
    require(np.isfinite(weights['weight']).all() and (weights['weight']>=0).all()
            and (weights['weight']<=1).all(), 'Cannot evaluate out-of-box rejected coefficients')
    # A rejected in-box raw iterate is sampled ONLY as failure evidence, never a fallback candidate.
    save(out/'candidate-role.json',dict(acceptedForApplication=report['apply_allowed'],
         role='candidate' if report['apply_allowed'] else 'REJECTED_RAW_ITERATE_EVIDENCE_ONLY',
         noFallback=True, noProduction=True))
    command(out,'05-evaluate',['java','-Xmx3g','-cp',str(out/'classes'),
            'com.geogenesis.worldgen.terrain.morph.RemainingDropProbe','evaluate',str(out/'export'),
            str(out/'weights.csv'),str(out/'evaluated')],commands)
    return report


def manifest(out, locked, initial_sources, commands, result):
    require(snapshot() == locked, 'Frozen main/old evidence/source drift')
    require(sources() == initial_sources, 'Active implementation drift during run')
    save(out/'verification.json',dict(integrityPassed=True, oldInputsUnchanged=True,
         productionWiring=False, stage='S1 STOP', S2Executed=False, result=result))
    hashes = {str(p.relative_to(out)).replace('\\','/'):sha(p) for p in out.rglob('*')
              if p.is_file() and p.name!='manifest.json'}
    save(out/'manifest.json',dict(lockedInputs=locked,activeSources=initial_sources,
         commands=commands, outputHashes=hashes,actualMinecraftWorld=False,stage='S1 STOP',result=result))


def execute(out):
    require(out.parent == ROOT/'build' and not out.exists(), 'Use direct build/<NEW>, refuse overwrite')
    for path in NEW:
        require(path.is_file(), 'Missing source '+str(path))
        if path.suffix == '.py':
            ast.parse(path.read_text(encoding='utf-8'))
    locked, initial_sources, commands = snapshot(), sources(), []
    out.mkdir(); (out/'logs').mkdir()
    save(out/'locked-before.json',locked);save(out/'source-lock.json',initial_sources)
    try:
        compile_and_export(out,commands)
        solver=module('remaining-drop-solver')
        verify_export(out,solver)
        report=solve_and_evaluate(out,commands,solver)
        render_result=module('remaining-drop-render').render(out)
        save(out/'render-result.json',render_result)
        stats=load(out/'stats.json')
        result=dict(solverAccepted=report['success'], solverStatus=report['status'], stats=stats,
                    morphologicalAcceptance=False, zeroIsNotSuccess=True)
        manifest(out,locked,initial_sources,commands,result)
        print('S1 STOP',out,'solverAccepted',report['success'],flush=True)
        return 0 if report['success'] and stats.get('effectPassed',False) else 2
    except Exception as failure:
        save(out/'failure.json',dict(error=repr(failure),frozenUnchanged=snapshot()==locked,commands=commands))
        raise


def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('out')
    args=parser.parse_args()
    return execute((ROOT/args.out).resolve())


if __name__=='__main__':
    sys.exit(main())
