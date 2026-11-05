"""Finite causal experiment runner; creates only a NEW build output directory.
No src/main writes. Original demo and accepted arrays are hash-locked before/after.
Usage from mdk: python -B tools/run-rune-controlled.py build/rune-controlled-20261103 501
"""
import hashlib
import json
from pathlib import Path
import subprocess
import sys
import time
import numpy as np

ROOT = Path(__file__).resolve().parents[1]
OUT = (ROOT / sys.argv[1]).resolve()
N = int(sys.argv[2])
if OUT.exists():
    raise SystemExit('refusing overwrite ' + str(OUT))
WORK = OUT.with_name(OUT.name + '-work')
if WORK.exists():
    raise SystemExit('refusing overwrite ' + str(WORK))
WORK.mkdir(parents=True)

def sha(p):
    return hashlib.sha256(p.read_bytes()).hexdigest()

source = ROOT / 'src/diagnostics/java/com/geogenesis/standalone/RuneOriginalDemo.java'
production = sorted((ROOT / 'src/main').rglob('*'))
locked = [p for p in production if p.is_file()] + [source]
for d in ('rune-original-static-20261103-final', 'rune-signal-stage1-20261103'):
    locked += [p for p in (ROOT / 'build' / d).rglob('*') if p.is_file()]
before = {str(p.relative_to(ROOT)): sha(p) for p in locked}
(WORK / 'locked-before.json').write_text(json.dumps(before, indent=2))
commands = []

def run(args):
    commands.append(args)
    with (WORK / 'commands.json').open('w') as f:
        json.dump(commands, f, indent=2)
    t = time.perf_counter()
    p = subprocess.run(args, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    text = p.stdout.decode('utf-8', errors='replace')
    with (WORK / 'validation.log').open('a', encoding='utf-8') as f:
        f.write('\nCOMMAND ' + subprocess.list2cmdline(args) + '\n' + text
                + '\nexit=%d seconds=%.6f\n' % (p.returncode, time.perf_counter()-t))
    print(text[-2500:])
    if p.returncode:
        raise SystemExit(p.returncode)

# Build diagnostics via the established Gradle source set without changing build.gradle.
run(['cmd.exe', '/c', 'gradlew.bat', '--offline', 'compileDiagnosticsJava'])
s = source.read_text(encoding='utf-8').replace('RuneOriginalDemo', 'RuneHashVariant')
start = s.index('    private static float[] hash(float xIn, float yIn) {')
end = s.index('\n    // =========================================================================', start)
# Exactly the production hash, narrowing only at the function return to original float32.
# Base is always sampled with ORIGINAL class, so this copy only supplies erosion hash.
s = s[:start] + '''    private static float[] hash(float xIn, float yIn) {
        long gx=(long)xIn, gz=(long)yIn;
        long h=sm(gx * 0x9E3779B97F4A7C15L ^ gz);
        h=sm(h ^ 9139912035078620160L);
        double u=(h >>> 11)*0x1.0p-53;
        h=sm(h + 0x9E3779B97F4A7C15L);
        double v=(h >>> 11)*0x1.0p-53;
        return new float[]{(float)(u*2-1),(float)(v*2-1)};
    }
    private static long sm(long z) {
        z+=0x9E3779B97F4A7C15L;
        z=(z^(z>>>30))*0xBF58476D1CE4E5B9L;
        z=(z^(z>>>27))*0x94D049BB133111EBL;
        return z^(z>>>31);
    }
''' + s[end:]
variant = WORK / 'RuneHashVariant.java'
variant.write_text(s, encoding='utf-8')
classes = WORK / 'classes'
classes.mkdir()
run(['javac', '-encoding', 'UTF-8', '-d', str(classes), str(variant)])
cp = ';'.join([str(classes), str(ROOT/'build/classes/java/diagnostics'), str(ROOT/'build/classes/java/main')])
run(['java', '-cp', cp, 'com.geogenesis.standalone.RuneOriginalDemo', '--selftest'])
# Reproduce original accepted arrays at full resolution, not a subset or tolerance.
ref = ROOT/'build/rune-original-static-20261103-final'
run(['java', '-cp', cp, 'com.geogenesis.standalone.RuneOriginalDemo', str(WORK/'original-rerun'),
     '1001', '0', '0', '1'])
for name in ('base.f32be', 'eroded.f32be'):
    assert sha(ref/name) == sha(WORK/'original-rerun'/name), name
run(['java', '-Xmx2g', '-cp', cp,
     'com.geogenesis.worldgen.terrain.morph.RuneControlledComparisonProbe', str(OUT), str(N)])

def read(name):
    a = np.fromfile(OUT/(name+'.f64be'), dtype='>f8').reshape(N,N).astype(float)
    assert np.isfinite(a).all()
    return a

def summary(a):
    return dict(mean=float(a.mean()), std=float(a.std()),
                q=dict(zip(['min','p01','p50','p99','max'],map(float,np.quantile(a,[0,.01,.5,.99,1])))))

def difference(a,b):
    d=b-a
    return dict(signedMean=float(d.mean()), meanAbs=float(abs(d).mean()),
                rms=float(np.sqrt((d*d).mean())), centeredRms=float(d.std()),
                p99Abs=float(np.quantile(abs(d),.99)), maxAbs=float(abs(d).max()))

arrays={p.stem:read(p.stem) for p in OUT.glob('*.f64be')}
pairs=[('rune-author','rune-fd','gradient only: analytic vs +/-2wu'),
       ('rune-author','rune-hash-only','hash only, float32 retained'),
       ('rune-hash-only','rune-settings-only','settings only, same float32 SplitMix hash'),
       ('rune-settings-only','rune-double-candidate','arithmetic port only, same settings/hash'),
       ('rune-double-author','rune-double-candidate','settings only in double core'),
       ('rune-double-author','m1-compressed-author','base only, same AUTHOR/filter/units/fade'),
       ('m1-compressed-author','m1-compressed-candidate','settings only on compressed M1'),
       ('m1-compressed-candidate','m1-native-common-fade','vertical amplitude only, fade denominator fixed'),
       ('m1-native-common-fade','m1-native-candidate-1830','fade only .0375 to .3'),
       ('m1-native-candidate-1830','m1-native-candidate-610-local','scale only, same local origin'),
       ('m1-native-candidate-610-local','m1-native-candidate-610','phase only, local to absolute world origin')]
# Local 610 bridge isolates scale from the frozen candidate absolute-world phase.
stats={'levels':{k:summary(v) for k,v in arrays.items()},
       'differences':[{ 'a':a,'b':b,'factor':f,**difference(arrays[a],arrays[b])} for a,b,f in pairs],
       'gradientsPerWu':{},'increments':{},'inputMasks':{}}
for name,a in arrays.items():
    if name.startswith('slope-'):
        continue
    gz,gx=np.gradient(a,1830/N)
    stats['gradientsPerWu'][name]=summary(np.hypot(gx,gz)[1:-1,1:-1])
for name in arrays:
    if name.startswith(('base-','slope-')):
        continue
    base='base-rune' if name.startswith('rune-') else ('base-m1-compressed' if name.startswith('m1-compressed') else 'base-m1-native')
    stats['increments'][name]=difference(arrays[base],arrays[name])
for base,slope,fade in [('base-rune','slope-rune-analytic',.0375),
                         ('base-m1-compressed','slope-m1-compressed',.0375),
                         ('base-m1-native','slope-m1-native',.0375),
                         ('base-m1-native','slope-m1-native',.3)]:
    sl=arrays[slope]; ft=np.clip(arrays[base]/fade,-1,1)
    # Actual input rounding follows author/candidate settings, not a new regularizer.
    masks={}
    for style,crease in [('author',0),('candidate',.12)]:
        rounding=(crease+(.1-crease)*np.clip(ft+.5,0,1))*.1
        t=sl*1.25; smooth=rounding*1.25
        r=np.where(t>=smooth,t-.5*smooth,.5*t*t/np.maximum(smooth,1e-30))
        mask=1-(1-np.clip(r,0,1))**2
        masks[style]=summary(mask)
    stats['inputMasks'][base+'-fade'+str(fade)]={'fadeSaturatedFraction':float((abs(ft)>=1).mean()),
                                                          'mask':masks}
(OUT/'stats-controlled.json').write_text(json.dumps(stats,indent=2),encoding='utf-8')
after={str(p.relative_to(ROOT)):sha(p) for p in locked}
assert before==after, 'LOCKED SOURCE/BASELINE CHANGED'
(OUT/'verification.txt').write_text('PASS source main hashes unchanged (%d files)\n'
    'PASS original source unchanged\nPASS accepted original 1001x1001 base/eroded reproduced SHA256 exact\n'
    'PASS accepted signal directory all files unchanged\nPASS all arrays finite\n' % len(production),encoding='utf-8')
manifest={
 'purpose':'diagnostics only; no production integration; final drawing/visual check by parent agent',
 'size':N,'dtype':'big-endian float64, row=z col=x, all heights CENTERED about reference, no offset',
 'grid':{'pOrigin':[0,0],'pSpan':1,'cellCenters':True,'wuSpan':1830,'stepWu':1830/N,
         'm1OriginWu':[-4157.5,-4280.5], 'm1CenterWu':[-3242.5,-3365.5], 'hs':2},
 'mapping':{'runeCentered':'FractalNoise(p*3, octaves=3, lac=2, gain=.1) * .125 * .5',
            'M1Compressed':'.125 * (rawM1 - .5); declared-range match, NOT empirical fit',
            'M1Native':'rawM1 - .5',
            'verticalDisplaySuggestion':'e=.545+1.05*h; linear Y proxy=63+236*(e-.02), NOT HeightCurve or game output',
            'cannotMatchAll':'L=1830 aligns original 1/3 p main lattice scale to 610wu, NOT full spectra; M1 ridge gain1.15 lac2.35 and warp remain. Compression changes original candidate vertical magnitude; native arm deliberately retained.',
            'originalOffset':'-.65 * sum(.22*.15*.5^i,i=0..4) = -.041559375 (float32 actual approx); DC excluded from shape arms, add ONLY for original absolute height',
            'originalAbsoluteBase':'.5 + centered rune', 'noPerWindowNormalization':True},
 'arms':list(arrays), 'pairs':pairs, 'settingsSource':'verification-java.txt and hashed source files',
 'gradient':'Rune analytic or centered +/-2wu on original float32 samples; M1 +/-2wu; derivative converted to dheight/dp',
 'hashExperiment':'rune-author -> rune-hash-only ONLY hash replaced, float32 retained; -> rune-settings-only ONLY settings; -> rune-double-candidate arithmetic/core port',
 'renderAudit':{'original':'per-pixel gradient divided by baseline P90; shared BOTH-arm min/max color range; Y-up light (-1,.4,.05); no water',
                'signal':'fixed MC Y palette; gradients Y/wu; light in X,Z,UP (-.5,-.35,1); water-color palette; no baseline P90 normalization',
                'conclusion':'Existing images are NOT common physical hillshade or common palette; parent must replot exported arrays with fixed shared scale/light/range.'},
 'jvmProperties':'no geogenesis -D; Java default; direct java -cp; see commands.json',
 'offsetWarning':'Do not treat DC as shape; compare centeredRms as well as signedMean',
 'performanceWarning':'per-sample timed arms in fixed order, warmup 512 author; reflective float arms incur overhead; NOT MC throughput, NOT robust microbenchmark',
 'sourceHashes':{str(p.relative_to(ROOT)):sha(p) for p in [source,ROOT/'src/main/java/com/geogenesis/worldgen/erosion/RuneErosionFilter.java',ROOT/'src/main/java/com/geogenesis/worldgen/terrain/morph/MountainsRecipe.java',ROOT/'src/main/java/com/geogenesis/worldgen/terrain/morph/MorphLandRecipe.java',ROOT/'src/main/java/com/geogenesis/worldgen/terrain/morph/MorphNoiseEval.java',ROOT/'src/main/java/com/geogenesis/worldgen/terrain/morph/MorphCatalog.java',ROOT/'src/diagnostics/java/com/geogenesis/worldgen/terrain/morph/RuneControlledComparisonProbe.java',ROOT/'src/diagnostics/java/com/geogenesis/worldgen/terrain/morph/SignalSideRuneProbe.java',Path(__file__).resolve()]}, 
 'offlineHashCopy':{'path':str(variant),'sha256':sha(variant)},
 'referenceHashes':{k:v for k,v in before.items() if '/build/' in '/'+k},
}
for name in ('commands.json','validation.log','locked-before.json'):
    (OUT/name).write_bytes((WORK/name).read_bytes())
manifest['arrayHashes']={p.name:sha(p) for p in OUT.glob('*.f64be')}
(OUT/'manifest.json').write_text(json.dumps(manifest,indent=2),encoding='utf-8')
print('DONE',OUT)
for d in stats['differences']:
    print(d['factor'], 'centeredRMS=',d['centeredRms'], 'mean=',d['signedMean'])
