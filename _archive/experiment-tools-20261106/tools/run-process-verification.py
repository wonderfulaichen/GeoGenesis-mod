"""Scoped Java17 bytecode compilation/run using freshly captured Gradle support classpath."""
import sys, pathlib, subprocess, json, hashlib, time
ROOT = pathlib.Path(__file__).resolve().parents[1]
OUT = (ROOT / sys.argv[1]).resolve()
CP = str(OUT / 'core-classes') + ';' + (OUT / 'core-classpath.txt').read_text()
def run(name, command):
    (OUT / (name + '-command.json')).write_text(json.dumps(command, indent=2), encoding='utf-8')
    start = time.time()
    with (OUT / (name + '.log')).open('wb') as log:
        result = subprocess.run(command, cwd=str(ROOT), stdout=log, stderr=subprocess.STDOUT)
    (OUT / (name + '-exit.json')).write_text(json.dumps({'exit': result.returncode, 'seconds': time.time()-start}))
    print(name, result.returncode, time.time()-start, flush=True)
    if result.returncode:
        print((OUT / (name + '.log')).read_text(encoding='utf-8', errors='replace'))
        raise SystemExit(result.returncode)
if __name__ == '__main__':
    sources = [ROOT / 'src/diagnostics/java/com/geogenesis/worldgen/process/ProcessRuntimeCheck.java']
    if len(sys.argv) > 2 and sys.argv[2] == 'render':
        support = (OUT / 'compile-classpath.txt').read_text().strip()
        main = (OUT / 'main-sources.txt').read_text().splitlines()
        javac = ['--release', '17', '-encoding', 'UTF-8', '-proc:none', '-g', '-cp', support,
                 '-d', str(OUT / 'main-classes')] + main
        argfile = OUT / 'javac-main.args'
        argfile.write_text('\n'.join('"' + a.replace('\\', '/') + '"' for a in javac), encoding='utf-8')
        if not (OUT / 'main-classes').exists():
            run('main-compile', ['javac', '@' + str(argfile)])
        sources = [ROOT / 'src/diagnostics/java/com/geogenesis/worldgen/process/ProcessRenderProbe.java']
        render_cp = str(OUT / 'main-classes') + ';' + support
        run('render-compile-' + sys.argv[-1], ['javac', '--release', '17', '-encoding', 'UTF-8',
            '-proc:none', '-cp', render_cp, '-d', str(OUT / 'render-classes')] + [str(p) for p in sources])
        run('process-render-' + sys.argv[-1], ['java', '-Xmx2g', '-Dfile.encoding=UTF-8', '-cp',
            str(OUT / 'render-classes') + ';' + render_cp,
            'com.geogenesis.worldgen.process.ProcessRenderProbe', str(OUT)] + sys.argv[3:])
    else:
        run('process-check-compile', ['javac', '--release', '17', '-encoding', 'UTF-8', '-proc:none',
            '-cp', CP, '-d', str(OUT / 'core-classes')] + [str(p) for p in sources])
        run('process-runtime-check', ['java', '-Xmx2g', '-Dfile.encoding=UTF-8', '-cp', CP,
            'com.geogenesis.worldgen.process.ProcessRuntimeCheck'])
        old = ROOT / 'build/r0b-20261105/ParticleCoreCheck.java'
        run('old-kernel-compile', ['javac', '--release', '17', '-cp', CP, '-d', str(OUT / 'core-classes'), str(old)])
        run('old-kernel-check', ['java', '-Xmx1g', '-cp', CP, 'ParticleCoreCheck', str(ROOT / 'build/r0-0-20261105')])
    manifest = []
    for path in list((ROOT / 'src/main/java/com/geogenesis/worldgen/process').glob('*.java')) + sources:
        manifest.append({'source': str(path.relative_to(ROOT)), 'sha256': hashlib.sha256(path.read_bytes()).hexdigest()})
    (OUT / 'process-source-manifest.json').write_text(json.dumps(manifest, indent=2), encoding='utf-8')
