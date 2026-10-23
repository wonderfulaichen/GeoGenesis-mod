from pathlib import Path
import hashlib
import json
import subprocess
import time

root = Path('build/d8-spill-contract/discovery')
candidate = Path('build/boundary-repair-v2/integrated-1792736719405609000')
cp = str(candidate / 'classes') + ';' + Path('build/local-flow-reassessment/classpath.txt').read_text(encoding='utf-8').strip()
out = root / ('verify-' + str(time.time_ns()))
classes = out / 'classes'
classes.mkdir(parents=True)
protected = [Path('../.openbitfun/plans/hydro-deterministic-spill-contract.plan.md'),
             Path('run/config/geogenesis-common.toml'),
             Path('src/main/java/com/geogenesis/worldgen/hydrology/model/DeterministicSkeleton.java'),
             Path('src/main/java/com/geogenesis/worldgen/hydrology/model/ModelHydrologyProvider.java')]
sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
before = {str(p): sha(p) for p in protected}
with (out / 'compile.log').open('wb') as f:
    result = subprocess.run(['javac', '-proc:none', '-encoding', 'UTF-8', '-cp', cp,
                             '-d', str(classes), str(root / 'MultiRegionDiscoveryProbe.java'),
                             str(root / 'ReverseOrdinaryProbe.java'), str(root / 'ContextDependencyProbe.java'),
                             str(root / 'ResumableDiscoveryProbe.java')],
                            stdout=f, stderr=subprocess.STDOUT)
if result.returncode:
    print((out / 'compile.log').read_text(errors='replace'))
    raise SystemExit(result.returncode)
results = []
for arm in ['cold-1', 'cold-2', 'production-1', 'production-2']:
    dest = out / arm
    with (out / (arm + '.log')).open('wb') as f:
        result = subprocess.run(['java', '-Xmx2G', '-Dgeogenesis.boundaryPorts=true',
                                 '-Dprobe.productionParams=' + str(arm.startswith('production')).lower(),
                                 '-Dfile.encoding=UTF-8', '-cp', str(classes) + ';' + cp,
                                 'com.geogenesis.worldgen.hydrology.model.MultiRegionDiscoveryProbe', str(dest)],
                                stdout=f, stderr=subprocess.STDOUT)
    log = (out / (arm + '.log')).read_text(encoding='utf-8', errors='replace')
    reproduced = result.returncode == 1 and 'REPRODUCTION CHECKS PASS; REQUIRED DISCOVERY CONTRACT FAIL' in log
    results.append({'arm': arm, 'exit': result.returncode, 'reproduced': reproduced,
                    'summary': (dest / 'summary.txt').read_text() if (dest / 'summary.txt').exists() else ''})
    print(arm, 'exit', result.returncode, 'reproduced', reproduced)
components = []
for name in ['ReverseOrdinaryProbe', 'ContextDependencyProbe', 'ResumableDiscoveryProbe']:
    for cold in [1, 2]:
        summary = out / (name + '-' + str(cold) + '.txt')
        with (out / (name + '-' + str(cold) + '.log')).open('wb') as f:
            result = subprocess.run(['java', '-Dfile.encoding=UTF-8', '-cp', str(classes) + ';' + cp,
                                     'com.geogenesis.worldgen.hydrology.model.' + name, str(summary)],
                                    stdout=f, stderr=subprocess.STDOUT)
        components.append({'name': name, 'cold': cold, 'exit': result.returncode,
                           'summary': summary.read_text(encoding='utf-8') if summary.exists() else ''})
        print(name, cold, 'exit', result.returncode)
component_same = all((out / (name + '-1.txt')).exists() and (out / (name + '-2.txt')).exists()
                     and (out / (name + '-1.txt')).read_bytes() == (out / (name + '-2.txt')).read_bytes()
                     for name in ['ReverseOrdinaryProbe', 'ContextDependencyProbe', 'ResumableDiscoveryProbe'])
compared = ['regions-0.csv', 'regions--4.csv', 'geometry-0.txt', 'geometry--4.txt']
same = all((out / (prefix + '1') / p).exists() and (out / (prefix + '2') / p).exists()
           and (out / (prefix + '1') / p).read_bytes() == (out / (prefix + '2') / p).read_bytes()
           for prefix in ['cold-', 'production-'] for p in compared)
mutations = []
for filename, name, old, new, assertion in [
        ('ReverseOrdinaryProbe', 'local-only', 'if(clipToLocalRegion && Math.floorDiv', 'if(true && Math.floorDiv',
         'downstream-only must discover remote admitted source'),
        ('ReverseOrdinaryProbe', 'unknown-as-complete', 'Set.copyOf(found),work,false);', 'Set.copyOf(found),work,true);',
         'budget exhaustion cannot certify absence'),
        ('ResumableDiscoveryProbe', 'reset-checkpoint-budget', 'expanded=saved.expanded;', 'expanded=0;',
         'checkpoint restores queue and logical counters exactly'),
        ('ResumableDiscoveryProbe', 'drop-checkpoint-queue', 'queue=new ArrayDeque<>(saved.queue);',
         'queue=new ArrayDeque<>();', 'checkpoint restores queue and logical counters exactly'),
        ('ResumableDiscoveryProbe', 'cache-discounts-logical-work', 'transitions++; calls+=',
         'if(!terrain.transitions.containsKey(candidate))transitions++; calls+=',
         'batch/resume/cache eviction must preserve all logical evidence'),
        ('ResumableDiscoveryProbe', 'drop-predecessor-cursor', 'cursor=saved.cursor;', 'cursor=0;',
         'cancel cold restore equals uninterrupted result')]:
    original = (root / (filename + '.java')).read_text(encoding='utf-8')
    assert original.count(old) == 1
    folder = out / ('mutation-' + name)
    folder.mkdir()
    source = folder / (filename + '.java')
    source.write_text(original.replace(old, new), encoding='utf-8')
    with (folder / 'compile.log').open('wb') as f:
        compiled = subprocess.run(['javac', '-proc:none', '-encoding', 'UTF-8', '-cp', str(classes) + ';' + cp,
                                   '-d', str(folder), str(source)], stdout=f, stderr=subprocess.STDOUT)
    run_exit = None
    log = ''
    if compiled.returncode == 0:
        with (folder / 'run.log').open('wb') as f:
            failed = subprocess.run(['java', '-Dfile.encoding=UTF-8', '-cp', str(folder) + ';' + str(classes) + ';' + cp,
                                     'com.geogenesis.worldgen.hydrology.model.' + filename,
                                     str(folder / 'summary.txt')], stdout=f, stderr=subprocess.STDOUT)
        run_exit = failed.returncode
        log = (folder / 'run.log').read_text(encoding='utf-8', errors='replace')
    caught = compiled.returncode == 0 and run_exit != 0 and ('AssertionError: ' + assertion) in log
    mutations.append({'name': name, 'compileExit': compiled.returncode, 'runExit': run_exit,
                      'expectedAssertion': assertion, 'caught': caught})
    print('mutation', name, 'compile', compiled.returncode, 'caught', caught)
unchanged = all(sha(p) == before[str(p)] for p in protected)
report = {'compileExit': 0, 'results': results, 'coldByteIdentical': same,
          'components': components, 'componentColdByteIdentical': component_same, 'mutations': mutations,
          'protectedUnchanged': unchanged, 'protectedBefore': before,
          'candidateClassSha': {p.name: sha(p) for p in (candidate / 'classes/com/geogenesis/worldgen/hydrology/model').glob('*.class')},
          'requiredDiscoveryAcceptance': False,
          'scope': 'Synthetic flow fixture; actual DS.build and actual provider incoming assembler; no real pipeline or wet-column acceptance.'}
(out / 'results.json').write_text(json.dumps(report, indent=2), encoding='utf-8')
print('OUT=' + str(out), 'coldByteIdentical=' + str(same), 'protectedUnchanged=' + str(unchanged))
if not (all(r['reproduced'] for r in results) and same and unchanged
        and all(c['exit'] == 0 for c in components) and component_same
        and all(m['caught'] for m in mutations)):
    raise SystemExit(2)
# Preserve the actual acceptance failure, not an overall green wrapper.
raise SystemExit(1)
