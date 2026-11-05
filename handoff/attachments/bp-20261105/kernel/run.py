import pathlib,json,subprocess,time,hashlib
O=pathlib.Path(__file__).resolve().parent;M=O.parents[1];B=O.parent/'bp-20261105-054953-3413'
cp=str(B/'main-classes')+';'+(B/'compile-classpath.txt').read_text().strip()
sources=list((M/'src/main/java/com/geogenesis/worldgen/process').glob('*.java'))+[M/'src/main/java/com/geogenesis/worldgen/erosion/ErosionEngine.java',M/'src/main/java/com/geogenesis/worldgen/erosion/ParticleErosionParams.java']+list(O.glob('*.java'))
commands=[('compile',['javac','--release','17','-encoding','UTF-8','-proc:none','-cp',cp,'-d',str(O/'classes')]+[str(s) for s in sources]),('kernel',['java','-Xmx3g','-Dfile.encoding=UTF-8','-cp',str(O/'classes')+';'+cp,'com.geogenesis.worldgen.process.KernelDiagnostic',str(O),str(B)])]
for name,cmd in commands:
 (O/(name+'-command.json')).write_text(json.dumps(cmd,indent=2));t=time.time()
 with (O/(name+'.log')).open('xb') as f:p=subprocess.run(cmd,cwd=str(O),stdout=f,stderr=subprocess.STDOUT)
 (O/(name+'-exit.json')).write_text(json.dumps({'exit':p.returncode,'seconds':time.time()-t}))
 print(name,p.returncode,time.time()-t,flush=True)
 if p.returncode:raise SystemExit(p.returncode)
