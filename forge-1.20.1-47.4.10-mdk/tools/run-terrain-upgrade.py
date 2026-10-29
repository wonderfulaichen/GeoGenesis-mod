"""Isolated baseline/candidate build and probe runner; never modifies run/config or build/classes."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys

MDK = Path(__file__).resolve().parents[1]
REPO = MDK.parent
OUT = MDK / 'build/terrain-upgrade'
BASE = OUT / 'baseline'
CANDIDATE = REPO / '.openbitfun/worktrees/terrain-upgrade' / MDK.name
JAVA_HOME = Path(os.environ.get('TERRAIN_JAVA_HOME', str(Path.home() / '.jdks/ms-17.0.17')))


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def jars():
    return (BASE / 'classpath-jars.txt').read_text(encoding='utf-8').strip()


def compile_arm(arm, target):
    source = BASE / 'src-main' if arm == 'baseline' else CANDIDATE / 'src/main'
    target.mkdir(parents=True, exist_ok=False)
    classes = target / 'classes'
    sources = sorted((source / 'java').rglob('*.java'))
    sources += sorted((MDK / 'src/diagnostics/java/com/geogenesis/worldgen/terrain').glob('TerrainUpgrade*.java'))
    if arm != 'baseline':
        sources += sorted((CANDIDATE / 'src/diagnostics/java/com/geogenesis/worldgen/terrain').glob('TerrainCenterCacheProbe.java'))
    argfile = target / 'sources.txt'
    argfile.write_text('\n'.join('"' + p.as_posix() + '"' for p in sources), encoding='utf-8')
    cp = jars() + ';' + str(BASE / 'diagnostics')
    cmd = [str(JAVA_HOME / 'bin/javac.exe'), '--release', '17', '-encoding', 'UTF-8',
           '-proc:none', '-cp', cp, '-d', str(classes), '@' + str(argfile)]
    run_logged(cmd, target / 'compile.log')
    manifest = {'arm': arm, 'source_root': str(source), 'config_sha256': digest(BASE / 'config-input.toml'),
                'plan_sha256': digest(REPO / '.openbitfun/plans/base-terrain-upgrade-research.plan.md'),
                'sources': {str(p): digest(p) for p in sources},
                'classes': {str(p.relative_to(classes)): digest(p) for p in classes.rglob('*.class')}}
    (target / 'manifest.json').write_text(json.dumps(manifest, indent=2), encoding='utf-8')
    return classes, source / 'resources'


def run_logged(command, log):
    print('RUN', Path(command[0]).name, 'log=', log, flush=True)
    with log.open('w', encoding='utf-8') as stream:
        result = subprocess.run(command, cwd=MDK, stdout=stream, stderr=subprocess.STDOUT)
    text = log.read_text(encoding='utf-8', errors='replace')
    print(text, flush=True)
    if result.returncode:
        raise SystemExit(result.returncode)
    return text


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('arm', choices=['baseline', 'candidate'])
    parser.add_argument('label', help='Unused output directory name')
    parser.add_argument('--main', default='com.geogenesis.worldgen.terrain.TerrainUpgradeProbe')
    parser.add_argument('--compile-only', action='store_true')
    parser.add_argument('probe_args', nargs=argparse.REMAINDER)
    args = parser.parse_args()
    if not args.label or Path(args.label).name != args.label or args.label in ('.', '..'):
        parser.error('label must be one directory name')
    target = OUT / args.label
    classes, resources = compile_arm(args.arm, target)
    if args.compile_only:
        return
    cp = ';'.join([str(classes), str(BASE / 'diagnostics'), str(resources), jars()])
    forwarded = args.probe_args
    if forwarded and forwarded[0] == '--':
        forwarded = forwarded[1:]
    command = [str(JAVA_HOME / 'bin/java.exe'), '-Xms512m', '-Xmx3g', '-Dfile.encoding=UTF-8',
               '-cp', cp, args.main, *forwarded]
    (target / 'command.json').write_text(json.dumps(command, indent=2), encoding='utf-8')
    run_logged(command, target / 'probe.log')


if __name__ == '__main__':
    main()
