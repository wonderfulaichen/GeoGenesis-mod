import pathlib,json,subprocess,time,hashlib
O=pathlib.Path(__file__).resolve().parent;B=O.parent/'bp-20261105-054953-3413';R=O.parents[1]
support=(B/'compile-classpath.txt').read_text().strip();cp=str(B/'main-classes')+';'+support
# Fresh compile ONLY four existing process sources plus build-only entry, original other frozen bytecode.
sources=list((R/'src/main/java/com/geogenesis/worldgen/process').glob('*.java'))+[O/'ExactDiagnostic.java']
for entry in json.loads((B/'process-source-manifest.json').read_text()):
    p=R/entry['source']
    if '/process/' in str(p).replace('\\','/') and 'src/main' in str(p).replace('\\','/'):
        if hashlib.sha256(p.read_bytes()).hexdigest()!=entry['sha256']:raise RuntimeError('original process source mismatch: '+str(p))
commands=[('compile',['javac','--release','17','-encoding','UTF-8','-proc:none','-cp',cp,'-d',str(O/'classes')]+[str(s) for s in sources]),('exact',['java','-Xmx2g','-Dfile.encoding=UTF-8','-cp',str(O/'classes')+';'+cp,'com.geogenesis.worldgen.process.ExactDiagnostic',str(O),str(B)])]
for name,cmd in commands:
    (O/(name+'-command.json')).write_text(json.dumps(cmd,indent=2));t=time.time()
    with (O/(name+'.log')).open('wb') as f: p=subprocess.run(cmd,cwd=str(O),stdout=f,stderr=subprocess.STDOUT)
    (O/(name+'-exit.json')).write_text(json.dumps({'exit':p.returncode,'seconds':time.time()-t}))
    print(name,p.returncode,time.time()-t,flush=True)
    if p.returncode:raise SystemExit(p.returncode)
