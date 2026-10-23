from pathlib import Path
import csv,hashlib,json,re,shutil,subprocess,sys
OUT=Path(__file__).resolve().parent
MDK=OUT.parents[3]
ROOT=OUT.parent
CAND=ROOT/'halo-candidate-1792785734981'
OLD=ROOT/'candidate-1792779496954846600'
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
files=[MDK/'run/config/geogenesis-common.toml']
for tree in [MDK/'src',CAND,OLD,MDK.parent/'.openbitfun/plans',MDK/'build/hydro-production']:
    files.extend(p for p in tree.rglob('*') if p.is_file())
protected={str(p):sha(p) for p in files}
(OUT/'protected-before.json').write_text(json.dumps(protected,indent=2),encoding='utf-8')
shutil.copy2(ROOT/'v4-render-1792780702450/console-log4j2.xml',OUT/'console-log4j2.xml')
classes=OUT/'classes'
classes.mkdir()
(OUT/'tmp').mkdir()
cp=str(classes)+';'+str(CAND/'classes')+';'+json.loads((CAND/'prepare.json').read_text())['classpath']
oldcp=str(classes)+';'+str(OLD/'classes')+';'+json.loads((OLD/'prepare.json').read_text())['classpath']
commands={}
def run(label,cmd,cwd=OUT):
    commands[label]=dict(argv=cmd,cwd=str(cwd))
    (OUT/'commands.json').write_text(json.dumps(commands,indent=2),encoding='utf-8')
    with (OUT/(label+'.log')).open('wb') as f:code=subprocess.run(cmd,cwd=cwd,stdout=f,stderr=subprocess.STDOUT).returncode
    print(label,code,flush=True)
    if code:raise RuntimeError(label+' failed')
jvm=['java','-Xmx5G','-Dfile.encoding=UTF-8','-Djava.io.tmpdir='+str(OUT/'tmp'),
     '-Dlog4j.configurationFile='+(OUT/'console-log4j2.xml').as_uri(),'-Dgeogenesis.boundaryPorts=true',
     '-Dgeogenesis.lifecycleDiag=false','-Dgeogenesis.stutterDiag=false']
probe=MDK/'src/diagnostics/java/com/geogenesis/worldgen/hydrology/model/ProductionViewProbe.java'
run('compile',['javac','-proc:none','-encoding','UTF-8','-cp',cp,'-d',str(classes),str(probe),str(OUT/'PinColumns.java')])
run('self-test',jvm+['-cp',cp,'com.geogenesis.worldgen.hydrology.model.ProductionViewProbe','--self-test'])
mode=sys.argv[1] if len(sys.argv)>1 else 'all'
if mode in ['all','render']:
    run('render',jvm+['-cp',cp,'com.geogenesis.worldgen.hydrology.model.ProductionViewProbe',
         '9139912035078620160','-140','137','700',str(MDK/'run/config/geogenesis-common.toml')],MDK)
    text=(OUT/'render.log').read_text(encoding='utf-8',errors='replace')
    path=re.search(r'^FILE=(.+)$',text,re.M)
    if not path:raise RuntimeError('missing FILE')
    (OUT/'render-path.txt').write_text(path.group(1).strip(),encoding='utf-8')
if mode in ['all','pins']:
    for label,classpath in [('pin-old',oldcp),('pin-new',cp)]:
        run(label,jvm+['-cp',classpath,'com.geogenesis.worldgen.hydrology.model.PinColumns',str(OUT/label),str(MDK/'run/config/geogenesis-common.toml')])
changed=[p for p,h in protected.items() if sha(Path(p))!=h]
(OUT/'protection.json').write_text(json.dumps(dict(changed=changed,protectedCount=len(protected)),indent=2),encoding='utf-8')
assert not changed,changed
print('EXECUTION PASS',flush=True)
