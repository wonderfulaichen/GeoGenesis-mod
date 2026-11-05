from pathlib import Path
import subprocess,json,hashlib,shutil
out=Path(__file__).resolve().parent;root=out.parents[2];old=out.parent/'ridge-ab-small-20261105'
cp=str(old/'main-classes')+';'+(out.parent/'r0b-20261105/compile-classpath.txt').read_text().strip()
def run(name,cmd):
 (out/(name+'-command.json')).write_text(json.dumps(cmd,indent=2))
 p=subprocess.run(cmd,cwd=out,stdout=subprocess.PIPE,stderr=subprocess.STDOUT);(out/(name+'.log')).write_bytes(p.stdout);print(name,p.returncode,flush=True);p.check_returncode()
run('final-compile',['javac','--release','17','-encoding','UTF-8','-proc:none','-Xlint:all','-Werror','-cp',cp,'-d',str(out/'probe-classes'),str(out/'DiagnosticRidgeKernel.java'),str(out/'TrueRidgeProbe.java')])
cmd=json.loads((out/'run-command.json').read_text());run('final-check',cmd+['checks'])
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
manifest=json.loads((old/'source-hashes.json').read_text());assert all(sha(root/'forge-1.20.1-47.4.10-mdk'/p)==v for p,v in manifest.items())
classes=json.loads((old/'class-hashes.json').read_text());assert all(sha(old/p)==v for p,v in classes.items())
(out/'current-main-source-class-verification.json').write_text(json.dumps({'allSourceHashesMatch':True,'allClassHashesMatch':True,'sources':len(manifest),'classManifestEntries':len(classes),'origin':str(old)},indent=2))
(out/'class-hashes.json').write_text(json.dumps({str(p.relative_to(out)):sha(p) for p in (out/'probe-classes').rglob('*.class')},indent=2))
b=json.loads((out/'before.json').read_text());changed=[p for p,v in b['files'].items() if not (root/p).exists() or sha(root/p)!=v];assert not changed,changed
assert sha(root/'.git/index')==b['index'];assert subprocess.check_output(['git','rev-parse','HEAD']).decode().strip()==b['head']
(out/'boundary-check.json').write_text(json.dumps({'pass':True,'startingFilesChecked':len(b['files']),'changedStartingFiles':changed,'HEADAndIndexUnchanged':True,'productionRunConfigProxyEvidenceAndBaselineUnchanged':True},indent=2))
