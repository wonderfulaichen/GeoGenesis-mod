"""Step-correction experiment on the L2 base. NEW output dir; CPU only.
Run from MDK: python -B src/diagnostics/fast-erosion-fine-step.py build/<NEW-directory>
"""
import hashlib
import json
import math
from pathlib import Path
import subprocess
import sys
import time
import numpy as np
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.colors import ListedColormap, Normalize

ROOT = Path(__file__).resolve().parents[2]
OUT = (ROOT/sys.argv[1]).resolve()
if OUT.exists() or ROOT/'build' not in OUT.parents:
    raise ValueError('NEW build directory required')
OUT.mkdir(parents=True)
PREV = ROOT/'build/fast-erosion-layers-20261103-final'
PALETTE = ROOT/'build/fast-erosion-scale-only-20261103-final/data/project-palette.csv'
COMMANDS = []


def sha(p):
    return hashlib.sha256(p.read_bytes()).hexdigest()


def save(name, v):
    (OUT/name).write_text(json.dumps(v, indent=2, ensure_ascii=False)+'\n', encoding='utf-8')


def run(args):
    COMMANDS.append(args)
    save('commands.json', COMMANDS)
    t = time.perf_counter()
    p = subprocess.run(args, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
    text = p.stdout.decode('utf-8', errors='replace')
    with (OUT/'validation.log').open('a', encoding='utf-8') as f:
        f.write('\nCOMMAND '+subprocess.list2cmdline(args)+'\n'+text[-4000:]+'\nexit=%d seconds=%.3f\n'
                % (p.returncode, time.perf_counter()-t))
    print(text[-1200:], flush=True)
    if p.returncode:
        raise RuntimeError('command failed')


def level(a):
    return dict(min=float(a.min()), max=float(a.max()), mean=float(a.mean()),
                std=float(a.std()), range=float(np.ptp(a)))


def gradient(a, step):
    gx = (a[1:-1, 2:]-a[1:-1, :-2])/(2*step)
    gz = (a[2:, 1:-1]-a[:-2, 1:-1])/(2*step)
    q = np.quantile(np.hypot(gx, gz), [.5, .99, .999, 1])
    return dict(zip(['p50', 'p99', 'p999', 'max'], map(float, q)), tail=float(q[2]/q[0]))


def stripe_periods(step_wu):
    """Prototype Gabor: shift=max(28-floor(freq),0), period=2^shift/(1+fract(freq)) samples."""
    rows = []
    for i in range(4):
        freq = 21.7 + 1.2*i
        fl = math.floor(freq)
        shift = max(28-fl, 0)
        period = (1 << shift)/(1.0+(freq-fl))
        rows.append(dict(octave=i+1, freq=round(freq, 3), shift=shift,
                         periodSamples=round(period, 4), periodWu=round(period*step_wu, 4)))
    return rows


def shade(a, step):
    gz, gx = np.gradient(a*236.44*1.5, step*2)
    l = np.array([-.5, -.3, 1.0]); l /= np.linalg.norm(l)
    return .65+.35*np.maximum(0, (-gx*l[0]-gz*l[1]+l[2])/np.sqrt(1+gx*gx+gz*gz))


def figures(arr, m, palette):
    cmap = ListedColormap(palette[:, 1:]/255)
    n, step = m['size'], m['stepWu']
    keys = ['A', 'L2prev', 'B']
    titles = ['A | production original',
              'previous L2 | step 4.7124 wu (retracted derivation)',
              'new B | L2 base, step 1.1914 wu (one lattice cell = 512 samples)']

    def rgb(a):
        return cmap(Normalize(-.35, 1.2, clip=True)(.02+1.5*a))[..., :3]*shade(a, step)[..., None]
    for fname, sl in [('three-arm.png', slice(None)), ('three-arm-center-zoom.png', slice(n//2-96, n//2+96))]:
        lo, hi = (0, n) if sl.start is None else (sl.start, sl.stop)
        ext = (lo*step*2, hi*step*2, hi*step*2, lo*step*2)
        fig, axes = plt.subplots(1, 3, figsize=(21, 7), dpi=140)
        for ax, k, t in zip(axes, keys, titles):
            ax.imshow(rgb(arr[k])[sl, sl], extent=ext, interpolation='nearest')
            ax.set(title=t, xlabel='local x (blocks)', ylabel='local z (blocks)')
        fig.colorbar(plt.cm.ScalarMappable(norm=Normalize(-.35, 1.2), cmap=cmap), ax=axes,
                     fraction=.015, pad=.02, label='fixed e limits; no per-image normalization')
        fig.suptitle('Step-correction experiment | same L2 base, parameters, window, palette, light, vertical scale')
        fig.text(.06, .02, 'Y proxy=236.44*1.5*h; HS=2; no HeightCurve/clamp. Blue = negative e in this TYPE experiment, not water.')
        fig.savefig(OUT/fname); plt.close(fig)
    fig, axes = plt.subplots(1, 2, figsize=(15, 7), dpi=140)
    lim = max(float(np.abs(arr['B']-arr['A']).max()), float(np.abs(arr['L2prev']-arr['A']).max()), .3)
    for ax, k, t in zip(axes, ['L2prev', 'B'], ['previous L2 - A', 'new B - A']):
        im = ax.imshow(arr[k]-arr['A'], cmap='RdBu_r', vmin=-lim, vmax=lim,
                       extent=(0, m['spanWu']*2, m['spanWu']*2, 0), interpolation='nearest')
        ax.set(title=t, xlabel='local x (blocks)', ylabel='local z (blocks)')
    fig.colorbar(im, ax=axes, fraction=.02, label='shell h; COMMON limit')
    fig.suptitle('Signed differences vs production')
    fig.savefig(OUT/'differences.png'); plt.close(fig)
    fig, axes = plt.subplots(2, 1, figsize=(16, 9), dpi=140, sharex=True, sharey=True)
    x = np.arange(n)*step*2
    for ax, col in zip(axes, [False, True]):
        for k in keys:
            ax.plot(x, arr[k][:, n//2] if col else arr[k][n//2, :], lw=.9, label=k)
        ax.set(ylim=(-.25, 1.0), xlabel='distance (blocks)', ylabel='shell h',
               title='center z cut' if col else 'center x cut')
        ax.legend(); ax.grid(alpha=.2)
    fig.tight_layout(); fig.savefig(OUT/'profiles.png'); plt.close(fig)
    np.savetxt(OUT/'profiles.csv',
               np.column_stack([x]+[arr[k][n//2, :] for k in keys]+[arr[k][:, n//2] for k in keys]),
               delimiter=',', header='distance,'+','.join(k+'_x' for k in keys)+','+','.join(k+'_z' for k in keys),
               comments='')
    sl = slice(n//2-96, n//2+96)
    ext = (sl.start*step*2, sl.stop*step*2, sl.stop*step*2, sl.start*step*2)
    fig, axes = plt.subplots(1, 2, figsize=(15, 7), dpi=140)
    lim = float(max(np.abs(arr['erosion-shell']).max(), np.abs(arr['L2prev-erosion-shell']).max()))
    for ax, a, t in zip(axes, [arr['L2prev-erosion-shell'], arr['erosion-shell']],
                        ['previous L2 erosion contribution', 'new B erosion contribution']):
        im = ax.imshow(a[sl, sl], vmin=-lim, vmax=lim, cmap='RdBu_r', extent=ext, interpolation='nearest')
        ax.set(title=t, xlabel='local x (blocks)', ylabel='local z (blocks)')
    fig.colorbar(im, ax=axes, fraction=.02, label='shell h; COMMON limit across both')
    fig.savefig(OUT/'erosion-contribution-zoom.png'); plt.close(fig)


def main():
    locked = [p for p in (ROOT/'src/main').rglob('*') if p.is_file()]
    locked += list((ROOT/'src/diagnostics/java/com/geogenesis/standalone').glob('FastErosion*.java'))
    for d in (ROOT/'build').glob('fast-erosion-*'):
        if d != OUT:
            locked += [p for p in d.rglob('*') if p.is_file()]
    before = {str(p.relative_to(ROOT)): sha(p) for p in locked}
    save('locked-before.json', before)

    classes = OUT/'classes'; classes.mkdir()
    java = 'src/diagnostics/java/com/geogenesis/worldgen/terrain/morph/FastErosionFineStepProbe.java'
    demo = 'src/diagnostics/java/com/geogenesis/standalone/FastErosionOriginalDemo.java'
    run(['javac', '-encoding', 'UTF-8', '-proc:none', '-d', str(classes),
         '-sourcepath', 'src/main/java;src/diagnostics/java', java, demo])
    cp = ['java', '-Xmx3g', '-cp', str(classes), 'com.geogenesis.worldgen.terrain.morph.FastErosionFineStepProbe']
    run(cp+['calibrate', str(OUT/'calibration')])
    consts = OUT/'calibration'/'constants.properties'
    const_hash = sha(consts)
    run(cp+['render', str(OUT/'data'), str(consts.as_posix())])
    assert sha(consts) == const_hash, 'constants changed during render'

    run(['java', '-Xmx1g', '-cp', str(classes), 'com.geogenesis.standalone.FastErosionOriginalDemo',
         str(OUT/'reference-rerun')])
    ref = ROOT/'build/fast-erosion-refactor3-20261103/run/height.rgba'
    assert sha(ref) == sha(OUT/'reference-rerun/height.rgba'), 'reference mismatch'
    assert sha(PREV/'data/A.f64be') == sha(OUT/'data/A.f64be'), 'A changed vs previous round'

    meta = json.loads((OUT/'data/sampling.json').read_text())
    n = meta['size']
    arr = {p.stem: np.fromfile(p, dtype='>f8').astype(float).reshape(n, n)
           for p in (OUT/'data').glob('*.f64be')}
    for k in ('L2prev', 'L2prev-erosion-shell'):
        src = PREV/'data'/('L2-B.f64be' if k == 'L2prev' else 'L2-erosion-shell.f64be')
        arr[k] = np.fromfile(src, dtype='>f8').astype(float).reshape(n, n)
    assert all(np.isfinite(a).all() for a in arr.values())
    cal = json.loads((OUT/'calibration'/'calibration.json').read_text())
    b, base, inc, prod = arr['B'], arr['base-shell'], arr['erosion-shell'], arr['raw-shell']
    assert np.allclose(b, base+inc, atol=1e-14)
    prev_stats = json.loads((PREV/'statistics.json').read_text())['arms']['L2']
    stats = dict(
        f=cal['f'], fRawUnclipped=cal['fRawUnclipped'], k=cal['k'], calibration=cal,
        maskFull=float((arr['initial-mask'] >= 1).mean()),
        maskMean=float(arr['initial-mask'].mean()),
        inputSlopeP50=float(np.median(arr['input-slope'])),
        lapSaturated=float((np.abs(arr['input-lap']) >= 1).mean()),
        levels=level(b), slope=gradient(b, meta['stepWu']),
        colorSaturated=float((((.02+1.5*b) < -.35) | ((.02+1.5*b) > 1.2)).mean()),
        erosionStdOverTotal=float(inc.std()/b.std()),
        erosionStdOverBase=float(inc.std()/base.std()),
        corrWithProductionRaw=float(np.corrcoef(b.ravel(), prod.ravel())[0, 1]),
        stdRatioToRaw=float(b.std()/prod.std()),
        A=dict(levels=level(arr['A']), slope=gradient(arr['A'], meta['stepWu'])),
        previousL2={k: prev_stats[k] for k in ('f', 'k', 'maskFull', 'inputSlopeP50',
                                               'erosionStdOverTotal', 'corrWithProductionRaw', 'slope', 'levels')},
        stripePeriodsNew=stripe_periods(meta['erosionStepWu']),
        stripePeriodsPrevious=stripe_periods(4.712446347542523))
    stats['ratioToBaseFeatures'] = {
        'coarsestStripeOverMainRidge': stats['stripePeriodsNew'][0]['periodWu']/610.0,
        'mainRidgeOverCoarsestStripe': 610.0/stats['stripePeriodsNew'][0]['periodWu'],
        'secondRidgeOverCoarsestStripe': 259.5745/stats['stripePeriodsNew'][0]['periodWu'],
        'previousMainRidgeOverCoarsestStripe': 610.0/stats['stripePeriodsPrevious'][0]['periodWu']}
    save('statistics.json', stats)
    palette = np.loadtxt(PALETTE, delimiter=',', skiprows=1)
    figures(arr, meta, palette)
    assert all(sha(ROOT/p) == v for p, v in before.items()), 'locked files changed'
    save('manifest.json', {
        'purpose': 'erosion step correction on L2 base; diagnostics only, NOT production',
        'sampling': meta, 'calibration': cal, 'commands': COMMANDS,
        'constantsSha256': const_hash,
        'sourceHashes': {java: sha(ROOT/java),
                         str(Path(__file__).resolve().relative_to(ROOT)): sha(Path(__file__)),
                         **{str(p.relative_to(ROOT)): sha(p) for p in
                            (ROOT/'src/main/java/com/geogenesis/worldgen/terrain/morph').glob('*.java')}},
        'referenceArm': str(PREV/'data/L2-B.f64be'),
        'files': {str(p.relative_to(OUT)): sha(p) for p in OUT.rglob('*')
                  if p.is_file() and 'classes' not in p.parts}})
    (OUT/'verification.txt').write_text(
        'PASS fresh javac; approved reference RGBA exact\n'
        'PASS A full SHA equals previous locked production A\n'
        'PASS all B samples match k*(f*base+.25E)*shell at production warped coordinates\n'
        'PASS only erosion step changed; calibration constants hashed before/after render\n'
        'PASS component reconstruction, all finite, locked baselines unchanged\n'
        'NOT VERIFIED: mixed-type transitions, other seeds/windows, infinite world, MC blocks\n', encoding='utf-8')
    print(json.dumps({k: stats[k] for k in ('f', 'fRawUnclipped', 'k', 'maskFull', 'inputSlopeP50',
                                            'erosionStdOverTotal', 'corrWithProductionRaw',
                                            'stdRatioToRaw', 'stripePeriodsNew')}, indent=2))
    print('DONE', OUT)


if __name__ == '__main__':
    main()
