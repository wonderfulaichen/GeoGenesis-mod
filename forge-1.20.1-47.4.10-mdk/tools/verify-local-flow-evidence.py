"""Read evidence and run build-only mutations. Never rewrites source, config, or plan."""
from pathlib import Path
import hashlib
import os
import re
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
BASE = ROOT / 'build/local-flow-reassessment'
PKG = 'com.geogenesis.worldgen.hydrology.model.'
SOURCE = ROOT / 'src/diagnostics/java/com/geogenesis/worldgen/hydrology/model'


def run_java(cp, klass, output, seed='9139912035078620160'):
    output.mkdir(parents=True, exist_ok=True)
    proc = subprocess.run(['java', '-Xmx6g', '-Dfile.encoding=UTF-8',
                           f'-DlocalFlow.output={output.as_posix()}', '-cp', cp, PKG + klass, seed],
                          cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    (output / 'verification-stdout.log').write_bytes(proc.stdout)
    return proc.returncode, proc.stdout.decode('utf-8', errors='replace')


def mutation_checks(cp):
    source = (SOURCE / 'LocalFlowEvents.java').read_text(encoding='utf-8')
    variants = [
        ('drop-halo', 'events(target.expand(spec.lifetime))', 'events(target)',
         'FAIL analytic plane oracle'),
        ('query-local-seed', 'long h=mix(spec.seed ^ mix(spec.configuration)',
         'long h=mix(spec.seed ^ domain.x0 ^ mix(spec.configuration)',
         'FAIL true smaller dependency domain'),
        ('first-occupied', 'field.put(p,field.getOrDefault(p,new Amount(0,0,0,0)).plus',
         'field.putIfAbsent(p,field.getOrDefault(p,new Amount(0,0,0,0)).plus',
         'FAIL analytic plane oracle'),
        ('duplicate-event', 'if(!ids.add(event.id))throw new IllegalArgumentException("duplicate event "+event.id);',
         'ids.add(event.id);', 'FAIL negative control: duplicate event rejected'),
    ]
    lines = []
    for name, old, new, expected in variants:
        assert source.count(old) == 1, (name, 'mutation anchor is not unique')
        directory = BASE / 'verification/mutants' / name
        package = directory / 'src/com/geogenesis/worldgen/hydrology/model'
        package.mkdir(parents=True, exist_ok=True)
        mutant = package / 'LocalFlowEvents.java'
        mutant.write_text(source.replace(old, new), encoding='utf-8')
        classes = directory / 'classes'
        classes.mkdir(exist_ok=True)
        proc = subprocess.run(['javac', '--release', '17', '-encoding', 'UTF-8', '-proc:none',
                               '-cp', cp, '-d', str(classes), str(mutant)], cwd=ROOT,
                              stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        (directory / 'compile.log').write_bytes(proc.stdout)
        assert proc.returncode == 0, (name, 'mutant compilation failed')
        code, text = run_java(str(classes) + os.pathsep + cp, 'LocalFlowOwnershipProbe', directory / 'run')
        assert code == 1 and expected in text, (name, code, 'required diagnostic assertion did not fail')
        lines.append(f'{name}: exit={code}; caught={expected}')
    return lines


def compare_evidence(first, second):
    lines = []
    for part in ('ownership', 'join', 'basin'):
        left, right = BASE / first / part, BASE / second / part
        files = sorted(p.name for p in left.iterdir() if p.suffix in ('.csv', '.png'))
        other = sorted(p.name for p in right.iterdir() if p.suffix in ('.csv', '.png'))
        assert files and files == other, (part, 'empty or different artifact set', files, other)
        for name in files:
            a, b = (left / name).read_bytes(), (right / name).read_bytes()
            assert a == b, (part, name, 'cold JVM data mismatch')
            lines.append(f'cold-jvm-identical {part}/{name} sha256={hashlib.sha256(a).hexdigest()}')
        name = 'result.txt' if part == 'basin' else 'metrics.txt'
        def normalize(path):
            text = path.read_text(encoding='utf-8-sig')
            text = text.replace(str(left), '<OUT>').replace(str(right), '<OUT>')
            text = text.replace(left.as_posix(), '<OUT>').replace(right.as_posix(), '<OUT>')
            return re.sub(r'(?i)(elapsedMs|ms)=\d+(?:\.\d+)?', r'\1=<TIMING>', text)
        assert normalize(left / name) == normalize(right / name), (part, 'normalized metrics mismatch')
        lines.append(f'cold-jvm-identical {part}/{name} (only output-dir/timing normalized)')
    return lines


def protected_inputs():
    lines = []
    manifest = BASE / 'current-input-sha256.txt'
    if not manifest.exists():
        manifest = BASE / 'input-sha256.txt'
    for line in manifest.read_text(encoding='utf-8-sig').splitlines():
        expected, filename = line.split(' ', 1)
        data = Path(filename).read_bytes()
        if filename.endswith('local-flow-streaming-reassessment.plan.md'):
            # TodoWrite may synchronize statuses without an agent edit. Verify exact bytes except these 5 values.
            import itertools
            chunks = re.split(rb'(?m)(?<=status: )(?:pending|in_progress|completed)(?=\r?$)', data)
            assert len(chunks) == 6, 'unexpected plan todo structure'
            matches = False
            for states in itertools.product((b'pending', b'in_progress', b'completed'), repeat=5):
                candidate = chunks[0] + b''.join(s + c for s, c in zip(states, chunks[1:]))
                matches |= hashlib.sha256(candidate).hexdigest().upper() == expected
            assert matches, 'plan changed beyond automatic todo status synchronization'
        else:
            actual = hashlib.sha256(data).hexdigest().upper()
            assert actual == expected, ('protected input changed', filename)
        lines.append(filename)
    return [f'protected-inputs-unchanged count={len(lines)} (main/resources/config; plan except automatic TodoWrite statuses)']


def main():
    cp = (BASE / 'classpath.txt').read_text(encoding='utf-8-sig').strip()
    report = protected_inputs()
    if len(sys.argv) == 3:
        report += compare_evidence(sys.argv[1], sys.argv[2])
    report += mutation_checks(cp)
    report += protected_inputs()
    out = BASE / 'verification/evidence.txt'
    out.parent.mkdir(parents=True, exist_ok=True)
    out.write_text('\n'.join(report) + '\n', encoding='utf-8')
    print('\n'.join(report))


if __name__ == '__main__':
    main()
