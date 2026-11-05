from pathlib import Path
import subprocess,json,hashlib
root=Path.cwd(); old=root/'build/r0-0-20261105'; out=root/'build/bp-20261105-054953-3413'
def run(name,args):
 (out/(name+'-command.json')).write_text(json.dumps(args,indent=2),encoding='utf-8')
 with (out/(name+'.log')).open('w',encoding='utf-8') as f: p=subprocess.run(args,stdout=f,stderr=subprocess.STDOUT)
 print(name,p.returncode,flush=True)
 if p.returncode: raise SystemExit(p.returncode)
cp=(out/'compile-classpath.txt').read_text().strip()
sources=(out/'main-sources.txt').read_text().splitlines()
args=['--release','17','-encoding','UTF-8','-proc:none','-g','-cp',cp,'-d',str(out/'main-classes')]+sources
(out/'javac-main.args').write_text('\n'.join('"'+s.replace('\\','/')+'"' for s in args),encoding='utf-8')
run('main-compile',['javac','@'+str(out/'javac-main.args')])
assert len(sources)==216, len(sources)
for name in ['p1-compile','capture-compile']:
 file=old/(name+'-command.json')
 data=json.loads(file.read_text())
 if isinstance(data,dict):
  print('command keys',name,list(data)); data=data.get('command',data.get('args'))
 if not data: raise Exception('missing command')
 cmd=[s.replace('r0-0-20261105','bp-20261105-054953-3413') for s in data]
 # config-check is compile command in the baseline.
 run(name,cmd)
probe_cp=str(out/'main-classes')+';'+cp
run('placement-compile',['javac','--release','17','-encoding','UTF-8','-proc:none','-Xlint:all','-Werror','-cp',probe_cp,'-d',str(out/'probe-classes'),str(root/'src/diagnostics/java/com/geogenesis/worldgen/terrain/morph/RidgePlacementProbe.java')])
for name in ['p1','loaded-final','fallback-final']:
 cmd=json.loads((old/(name+'-command.json')).read_text())
 cmd=[s.replace('r0-0-20261105','bp-20261105-054953-3413') for s in cmd]
 # Input snapshots stay at baseline, never overwrite/recreate them.
 cmd=[s.replace('bp-20261105-054953-3413\\configs','r0-0-20261105\\configs').replace('bp-20261105-054953-3413/configs','r0-0-20261105/configs') for s in cmd]
 run(name,cmd)
comp=[]
for arm in ['loaded-final','fallback-final','p1-replay']:
 for p in (old/arm).rglob('*'):
  if p.is_file() and (p.suffix in ['.f32be','.f64be'] or 'metadata' in p.name):
   q=out/arm/p.relative_to(old/arm)
   assert q.exists(),q
   assert p.read_bytes()==q.read_bytes(),q
   comp.append(dict(path=str(q.relative_to(out)),bytes=q.stat().st_size,sha256=hashlib.sha256(q.read_bytes()).hexdigest()))
(out/'comparison.json').write_text(json.dumps(comp,indent=2),encoding='utf-8')
print('BYTE COMPARISON PASS',len(comp),flush=True)
