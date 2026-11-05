import pathlib,json,hashlib,subprocess,sys
O=pathlib.Path(__file__).resolve().parent; M=O.parents[1]; R=M.parent; B=O.parent/'bp-20261105-054953-3413'; PREV=O.parent/'bp-seam-audit-20261105-060952'
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
paths=set()
for name in subprocess.check_output(['git','ls-files','-co','--exclude-standard','-z'],cwd=str(R)).decode().split('\0'):
 p=R/name
 if name and p.is_file() and O not in p.parents:paths.add(p)
for folder in [M/'src',M/'run/config',M/'config',M/'tools',B,PREV]:
 if folder.exists():paths.update(p for p in folder.rglob('*') if p.is_file())
paths.add(R/'debug-agent.log')
(O/'hashes-before.json').write_text(json.dumps({str(p.relative_to(R)):sha(p) for p in sorted(paths)},indent=2),encoding='utf8')
(O/'git-status-before.txt').write_bytes(subprocess.check_output(['git','status','--short'],cwd=str(R)))
(O/'environment.txt').write_text('debug-agent.log existed, bytes='+str((R/'debug-agent.log').stat().st_size)+'; not touched; no production logging\n'+subprocess.check_output(['java','-version'],stderr=subprocess.STDOUT).decode()+subprocess.check_output(['python','--version']).decode(),encoding='utf8')
# Every source in original final manifest remains byte-identical; evidence is not inferred from git cleanliness.
checks=[]
for e in json.loads((B/'final-source-manifest.json').read_text()):
 p=pathlib.Path(e['path']); checks.append({'path':str(p),'expected':e['sha256'],'actual':sha(p),'equal':sha(p)==e['sha256']})
(O/'original-source-check.json').write_text(json.dumps(checks,indent=2))
assert all(x['equal'] for x in checks),'original source drift'
s=(M/'src/main/java/com/geogenesis/worldgen/erosion/ErosionEngine.java').read_text(encoding='utf8')
s=s.replace('ErosionEngine','ObservedErosionEngine')
def rep(a,b):
 global s
 assert s.count(a)==1,(a,s.count(a));s=s.replace(a,b)
rep('    private final Supplier<ParticleErosionParams> runParams;','    public Observation observation;\n    private final Supplier<ParticleErosionParams> runParams;')
rep('        for (int it = 0; it < iterations; it++) {','        observation.save("input", flat);\n        for (int it = 0; it < iterations; it++) {\n            observation.iteration = it;\n            observation.save("it"+it+"-before", flat);')
rep('            // ===== SH 场平滑（world.h:80-86）：(1-lrate)·old + lrate·track =====','            observation.save("it"+it+"-drops",flat);\n            observation.saveScales("it"+it);\n            // ===== SH 场平滑（world.h:80-86）：(1-lrate)·old + lrate·track =====')
rep('                }\n            }\n\n            // ===== SH 场平滑','                }\n            }\n\n            // ===== SH 场平滑') if False else None
# z loop end: only add snapshot after completing an original row; do not reorder scale calls.
rep('                }\n            }\n\n            observation.save("it"+it+"-drops",flat);','                }\n                if ((gz-oz+1)%8==0) observation.save("it"+it+"-row"+(gz-oz+1),flat);\n            }\n\n            observation.save("it"+it+"-drops",flat);')
rep('        smoothErosionResult(flat, bufSize, pad, sz, seaNorm);\n        smoothDepositionZones(flat, flatPre, bufSize, pad, sz);','        observation.save("before-smooth",flat);\n        smoothErosionResult(flat, bufSize, pad, sz, seaNorm);\n        observation.save("after-smooth",flat);\n        smoothDepositionZones(flat, flatPre, bufSize, pad, sz);\n        observation.save("after-deposition-smooth",flat);')
rep('        if (px < 1 || px >= bufSize - 1 || py < 1 || py >= bufSize - 1) return;','        observation.begin(px,py,gx,gz,bn);\n        if (px < 1 || px >= bufSize - 1 || py < 1 || py >= bufSize - 1) { observation.reason(1); return; }')
rep('        if (startH < seaNorm) return;','        observation.birth[7]=startH;\n        if (startH < seaNorm) { observation.reason(2); return; }')
rep('        float slopeE = slope * hs;','        observation.birth[8]=slope;\n        float slopeE = slope * hs;')
rep('            if (((sh >>> 32) & 0xFFFF) / 65536f >= prob) return;','            if (((sh >>> 32) & 0xFFFF) / 65536f >= prob) { observation.reason(3); return; }')
rep('        simulateDrop(flat, bufSize, px + 0.5f, py + 0.5f,','        observation.accept();\n        simulateDrop(flat, bufSize, px + 0.5f, py + 0.5f,')
rep('                     cascadeStrength, hs, spdCap, stallSpeed, stallDelta);\n    }','                     cascadeStrength, hs, spdCap, stallSpeed, stallDelta);\n        observation.completed(flat);\n    }')
rep('            if (ix < 1 || ix >= bufSize - 2 || iy < 1 || iy >= bufSize - 2) return;','            if (ix < 1 || ix >= bufSize - 2 || iy < 1 || iy >= bufSize - 2) { observation.reason(4); return; }')
rep('                } else if (dirX == 0 && dirY == 0) return;','                } else if (dirX == 0 && dirY == 0) { observation.reason(5); return; }')
rep('            if (dlen < 1e-12f) return;','            if (dlen < 1e-12f) { observation.reason(6); return; }')
rep('            if (posX < 1 || posX >= bufSize - 2 || posY < 1 || posY >= bufSize - 2) return;','            if (posX < 1 || posX >= bufSize - 2 || posY < 1 || posY >= bufSize - 2) { observation.reason(7); return; }')
rep('            float brushDelta = delta;','            observation.step(st,idx,ix+fx,iy+fy,posX,posY,dirX,dirY,h0,dh,delta,ld,spd,tracking);\n            float brushDelta = delta;')
rep('                    flat[bi] -= weigh;\n                    sed += weigh;','                    float observedBefore=flat[bi];\n                    flat[bi] -= weigh;\n                    observation.brush(bi, observedBefore, flat[bi]);\n                    sed += weigh;')
rep('            if (spd <= 0) return;','            if (spd <= 0) { observation.reason(8); return; }')
# Observe each exact float write in cascade; no shadow physics or change of evaluation order.
for statement,index in [('flat[cz * bufSize + cx] -= transfer;','cz * bufSize + cx'),('flat[ni[i]] += transfer;','ni[i]'),('flat[cz * bufSize + cx] += transfer;','cz * bufSize + cx'),('flat[ni[i]] -= transfer;','ni[i]')]:
 rep('                '+statement,'                { float observedBefore=flat['+index+']; '+statement+' observation.cascade('+index+',observedBefore,flat['+index+']); }')
(O/'ObservedErosionEngine.java').write_text(s,encoding='utf8')
(O/'instrumentation-method.txt').write_text('Renamed exact source; inserted only observers. No constants, expressions, branching decisions or call ordering replaced. Brush/cascade measured from actual float writes (not intended weigh). Iteration and row snapshots; scale attribution respects interleaving. Output deferred arrays, no random calls. All source equality and bit tests mandatory.\n',encoding='utf8')
print('before hashes',len(paths),'source equality',len(checks))
