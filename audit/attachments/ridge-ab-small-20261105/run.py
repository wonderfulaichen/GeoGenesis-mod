from pathlib import Path
import subprocess,json,hashlib,shutil,sys
root=Path(__file__).resolve().parents[2]; out=Path(__file__).resolve().parent; old=root/'build/r0b-20261105'
def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()
def run(name,args,cwd=None):
 (out/(name+'-command.json')).write_text(json.dumps(args,indent=2),encoding='utf-8')
 with (out/(name+'.log')).open('w',encoding='utf-8') as f: p=subprocess.run(args,cwd=cwd or out,stdout=f,stderr=subprocess.STDOUT)
 print(name,p.returncode,flush=True)
 if p.returncode: raise SystemExit(p.returncode)
cp=(old/'compile-classpath.txt').read_text().strip()
if len(sys.argv)==1:
 sources=sorted((root/'src/main/java').rglob('*.java'))
 (out/'source-hashes.json').write_text(json.dumps({str(p.relative_to(root)):sha(p) for p in sources},indent=2))
 args=['--release','17','-encoding','UTF-8','-proc:none','-g','-cp',cp,'-d',str(out/'main-classes')]+list(map(str,sources))
 (out/'javac-main.args').write_text('\n'.join('"'+s.replace('\\','/')+'"' for s in args))
 run('main-compile',['javac','@'+str(out/'javac-main.args')])
 probe=root/'src/diagnostics/java/com/geogenesis/worldgen/terrain/morph/RidgePlacementProbe.java'
 run('probe-compile',['javac','--release','17','-encoding','UTF-8','-proc:none','-Xlint:all','-Werror','-cp',str(out/'main-classes')+';'+cp,'-d',str(out/'probe-classes'),str(probe)])
 shutil.copy2(probe,out/probe.name)
 (out/'class-hashes.json').write_text(json.dumps({str(p.relative_to(out)):sha(p) for folder in ['main-classes','probe-classes'] for p in (out/folder).rglob('*.class')},indent=2))
run_cp=str(out/'probe-classes')+';'+str(out/'main-classes')+';'+(old/'runtime-classpath.txt').read_text().strip()
# Avoid any older production directory accidentally present in the resolved runtime CP.
run_cp=';'.join(p for p in run_cp.split(';') if 'baselineclasses' not in p and 'build\\classes' not in p and 'build/classes' not in p and ('main-classes' not in p or str(out/'main-classes')==p) and ('probe-classes' not in p or str(out/'probe-classes')==p))
config=root/'run/config/geogenesis-common.toml'
base=['java','-Dfile.encoding=UTF-8','-cp',run_cp,'com.geogenesis.worldgen.terrain.morph.RidgePlacementProbe',str(config),str(out)]
if len(sys.argv)==1: run('select',base+['select'])
else: run(sys.argv[1],base+['small']+sys.argv[1:])
