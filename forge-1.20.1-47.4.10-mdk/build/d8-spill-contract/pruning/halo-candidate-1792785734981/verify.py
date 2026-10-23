from pathlib import Path
import ast, hashlib, json, subprocess, time
OUT=Path(__file__).resolve().parent
ROOT=OUT.parent
MDK=OUT.parents[3]
report=json.loads((OUT/'prepare.json').read_text())
cp=str(OUT/'classes')+';'+report['classpath']
tests=[ROOT/'halo-tests/HaloComponentProbe.java',ROOT/'tests/PruneDisconnectedProbe.java',ROOT/'tests/PruneLengthChainProbe.java']
protected=[OUT/'DeterministicSkeleton.java',OUT/'ModelHydrologyProvider.java',MDK/'run/config/geogenesis-common.toml']
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
before={str(p):sha(p) for p in protected}
folder=OUT/('verification-'+str(time.time_ns()))
folder.mkdir()
classes=folder/'classes'
classes.mkdir()
results=[]
def run(label,command):
    with (folder/(label+'.log')).open('wb') as log:
        code=subprocess.run(command,cwd=OUT,stdout=log,stderr=subprocess.STDOUT).returncode
    text=(folder/(label+'.log')).read_text(encoding='utf-8',errors='replace')
    results.append(dict(label=label,exit=code))
    print(label,code,flush=True)
    return code,text
code,text=run('compile',['javac','-proc:none','-encoding','UTF-8','-cp',cp,'-d',str(classes)]+list(map(str,tests)))
assert code==0,text
for name,expected in [('HaloComponentProbe','HALO_COMPONENT PASS casesPassed=113 casesFailed=0'),
                      ('PruneDisconnectedProbe','PRUNING PASS casesPassed=37 casesFailed=0'),
                      ('PruneLengthChainProbe','LENGTHCHAIN PASS casesPassed=16 casesFailed=0')]:
    code,text=run(name,['java','-Dfile.encoding=UTF-8','-cp',str(classes)+';'+cp,'com.geogenesis.worldgen.hydrology.model.'+name])
    assert code==0 and expected in text,text
source=(OUT/'DeterministicSkeleton.java').read_text(encoding='utf-8')
arms={
    'bypass':('if (!hasPort[root] || supported[root]) kept.add(traces.get(i));','if (true) kept.add(traces.get(i));','halo-isolated-source-removed'),
    'no-core-protection':('core.contains(x, z) || incomingStarts.contains(c)','false || incomingStarts.contains(c)','core'),
    'incoming-source-only':('supported[root] |= core.contains(x, z) || incomingStarts.contains(c);','supported[root] |= core.contains(x, z) || c == t.sourceCell() && incomingStarts.contains(c);','incoming'),
    'upper-inclusive':('x < maxX && z >= minZ && z < maxZ','x <= maxX && z >= minZ && z <= maxZ','FAIL negative-maxX-exclusive:'),
}
for label,(old,new,assertion) in arms.items():
    assert source.count(old)==1,label
    arm=folder/label
    arm.mkdir()
    src=arm/'DeterministicSkeleton.java'
    src.write_text(source.replace(old,new),encoding='utf-8')
    dest=arm/'classes'
    dest.mkdir()
    code,text=run(label+'-compile',['javac','-proc:none','-encoding','UTF-8','-cp',cp,'-d',str(dest),str(src)])
    assert code==0,text
    code,text=run(label+'-run',['java','-Dfile.encoding=UTF-8','-cp',str(classes)+';'+str(dest)+';'+cp,
                              'com.geogenesis.worldgen.hydrology.model.HaloComponentProbe'])
    assert code==1 and 'HALO_COMPONENT FAIL' in text and 'AssertionError' in text,text
    assert assertion in text,label
for path in [OUT/'prepare.py',OUT/'verify.py']:
    ast.parse(path.read_text(encoding='utf-8'),filename=str(path))
unchanged=before=={str(p):sha(p) for p in protected}
assert unchanged
(folder/'results.json').write_text(json.dumps(dict(runs=results,protectedBefore=before,protectedUnchanged=unchanged,
    scope='113 halo cases, existing37+16 regression, four compile-success assertion-negative controls; not world determinism.'),indent=2),encoding='utf-8')
print('FUNCTION/NEGATIVE CONTROLS PASS',flush=True)
