"""Two-arm base-spectrum comparison (L1/L2), CPU only. NEW output dir required.
Run from MDK: python -B src/diagnostics/fast-erosion-layers.py build/<NEW-directory>
"""
import hashlib
import json
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
        f.write('\nCOMMAND '+subprocess.list2cmdline(args)+'\n'+text+'\nexit=%d seconds=%.3f\n' % (p.returncode, time.perf_counter()-t))
    print(text, flush=True)
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


def shade(a, step):
    gz, gx = np.gradient(a*236.44*1.5, step*2)
    l = np.array([-.5, -.3, 1.0]); l /= np.linalg.norm(l)
    return .65+.35*np.maximum(0, (-gx*l[0]-gz*l[1]+l[2])/np.sqrt(1+gx*gx+gz*gz))


def figures(arr, m, palette):
    cmap = ListedColormap(palette[:, 1:]/255)
    n, step = m['size'], m['stepWu']
    keys, titles = ['A', 'L1', 'L2'], ['A | production original',
                                      'B = L1 | 1 ridge octave, no scaler',
                                      'C = L2 | 2 ridge + 2 scaler octaves']

    def rgb(a):
        return cmap(Normalize(-.35, 1.2, clip=True)(.02+1.5*a))[..., :3]*shade(a, step)[..., None]
    for fname, sl in [('three-arm.png', slice(None)), ('three-arm-center-zoom.png', slice(n//2-96, n//2+96))]:
        lo, hi = (0, n) if sl.start is None else (sl.start, sl.stop)
        ext = (lo*step*2, hi*step*2, hi*step*2, lo*step*2)
        fig, axes = plt.subplots(1, 3, figsize=(21, 7), dpi=140)
        for ax, key, title in zip(axes, keys, titles):
            ax.imshow(rgb(arr[key])[sl, sl], extent=ext, interpolation='nearest')
            ax.set(title=title, xlabel='local x (blocks)', ylabel='local z (blocks)')
        fig.colorbar(plt.cm.ScalarMappable(norm=Normalize(-.35, 1.2), cmap=cmap), ax=axes,
                     fraction=.015, pad=.02, label='fixed e limits; no per-image normalization')
        fig.suptitle('Base-spectrum experiment | identical erosion, parameters, window, palette, light, vertical scale')
        fig.text(.06, .02, 'Y proxy=236.44*1.5*h; HS=2; no HeightCurve/clamp. Blue = negative e in this TYPE experiment, not generated water.')
        fig.savefig(OUT/fname); plt.close(fig)
    fig, axes = plt.subplots(1, 3, figsize=(21, 7), dpi=140)
    for ax, key, title in zip(axes, ['L1', 'L2', 'L2'], ['L1 - A', 'L2 - A', 'L2 - L1']):
        d = arr[key]-arr['L1'] if title == 'L2 - L1' else arr[key]-arr['A']
        im = ax.imshow(d, cmap='RdBu_r', vmin=-.6, vmax=.6, extent=(0, m['spanWu']*2, m['spanWu']*2, 0),
                       interpolation='nearest')
        ax.set(title=title, xlabel='local x (blocks)', ylabel='local z (blocks)')
    fig.colorbar(im, ax=axes, fraction=.015, label='shell h, fixed +/- .6')
    fig.suptitle('Signed differences at identical coordinates')
    fig.savefig(OUT/'three-arm-differences.png'); plt.close(fig)
    fig, axes = plt.subplots(2, 1, figsize=(16, 9), dpi=140, sharex=True, sharey=True)
    x = np.arange(n)*step*2
    for ax, col in zip(axes, [False, True]):
        for key in keys:
            ax.plot(x, arr[key][:, n//2] if col else arr[key][n//2, :], lw=.9, label=key)
        ax.set(ylim=(-.25, 1.0), xlabel='distance (blocks)', ylabel='shell h',
               title='center z cut' if col else 'center x cut')
        ax.legend(); ax.grid(alpha=.2)
    fig.tight_layout(); fig.savefig(OUT/'three-arm-profiles.png'); plt.close(fig)
    np.savetxt(OUT/'three-arm-profiles.csv',
               np.column_stack([x]+[arr[k][n//2, :] for k in keys]+[arr[k][:, n//2] for k in keys]),
               delimiter=',', header='distance,'+','.join(k+'_x' for k in keys)+','+','.join(k+'_z' for k in keys), comments='')
    sl = slice(n//2-96, n//2+96)
    ext = (sl.start*step*2, sl.stop*step*2, sl.stop*step*2, sl.start*step*2)
    fig, axes = plt.subplots(1, 2, figsize=(15, 7), dpi=140)
    l1, l2 = arr['L1-erosion-shell'], arr['L2-erosion-shell']
    lim = max(float(np.abs(l1).max()), float(np.abs(l2).max()))
    for ax, a, t in zip(axes, [l1, l2], ['L1 erosion contribution', 'L2 erosion contribution']):
        im = ax.imshow(a[sl, sl], vmin=-lim, vmax=lim, cmap='RdBu_r', extent=ext, interpolation='nearest')
        ax.set(title=t, xlabel='local x (blocks)', ylabel='local z (blocks)')
    fig.colorbar(im, ax=axes, fraction=.02, label='shell h; COMMON limit across both arms')
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
    java = 'src/diagnostics/java/com/geogenesis/worldgen/terrain/morph/FastErosionLayerProbe.java'
    demo = 'src/diagnostics/java/com/geogenesis/standalone/FastErosionOriginalDemo.java'
    run(['javac', '-encoding', 'UTF-8', '-proc:none', '-d', str(classes),
         '-sourcepath', 'src/main/java;src/diagnostics/java', java, demo])
    cp = ['java', '-Xmx2g', '-cp', str(classes), 'com.geogenesis.worldgen.terrain.morph.FastErosionLayerProbe']
    consts = {}
    for arm in ('L1', 'L2'):
        run(cp+['calibrate', str(OUT/('calibration-'+arm)), arm])
        consts[arm] = OUT/('calibration-'+arm)/'constants.properties'
    locked_consts = {a: sha(p) for a, p in consts.items()}
    mapping = OUT/'constants-map.properties'
    mapping.write_text(''.join('%s=%s\n' % (a, p.as_posix()) for a, p in consts.items()), encoding='utf-8')
    run(cp+['render', str(OUT/'data'), str(mapping)])
    for a, p in consts.items():
        assert sha(p) == locked_consts[a], 'constants changed during render'

    run(['java', '-Xmx1g', '-cp', str(classes), 'com.geogenesis.standalone.FastErosionOriginalDemo',
         str(OUT/'reference-rerun')])
    ref = ROOT/'build/fast-erosion-refactor3-20261103/run/height.rgba'
    assert sha(ref) == sha(OUT/'reference-rerun/height.rgba'), 'reference mismatch'
    prior = ROOT/'build/fast-erosion-shaping-20261103-final'
    assert sha(prior/'data/A.f64be') == sha(OUT/'data/A.f64be'), 'A changed'

    meta = json.loads((OUT/'data/sampling.json').read_text())
    n = meta['size']
    arr = {p.stem: np.fromfile(p, dtype='>f8').astype(float).reshape(n, n)
           for p in (OUT/'data').glob('*.f64be')}
    for a in ('L1', 'L2'):
        arr[a] = arr[a+'-B']
    assert all(np.isfinite(a).all() for a in arr.values())
    prod = arr['raw-shell']
    cal = {a: json.loads((OUT/('calibration-'+a)/'calibration.json').read_text()) for a in ('L1', 'L2')}
    stats = {}
    for a in ('L1', 'L2'):
        b = arr[a]
        base, inc = arr[a+'-base-shell'], arr[a+'-erosion-shell']
        assert np.allclose(b, base+inc, atol=1e-14), a+' reconstruction'
        stats[a] = dict(
            f=cal[a]['f'], k=cal[a]['k'],
            calibration=cal[a],
            maskFull=float((arr[a+'-initial-mask'] >= 1).mean()),
            inputSlopeP50=float(np.median(arr[a+'-input-slope'])),
            lapSaturated=float((np.abs(arr[a+'-input-lap']) >= 1).mean()),
            levels=level(b), slope=gradient(b, meta['stepWu']),
            colorSaturated=float((((.02+1.5*b) < -.35) | ((.02+1.5*b) > 1.2)).mean()),
            erosionStdOverTotal=float(inc.std()/b.std()),
            erosionStdOverBase=float(inc.std()/base.std()),
            corrWithProductionRaw=float(np.corrcoef(b.ravel(), prod.ravel())[0, 1]),
            stdRatioToRaw=float(b.std()/prod.std()),
            meanShiftVsA=float((b-arr['A']).mean()))
    save('statistics.json', {'arms': stats, 'A': dict(levels=level(arr['A']), slope=gradient(arr['A'], meta['stepWu']))})
    palette = np.loadtxt(ROOT/'build/fast-erosion-scale-only-20261103-final/data/project-palette.csv',
                         delimiter=',', skiprows=1)
    figures(arr, meta, palette)
    assert all(sha(ROOT/p) == v for p, v in before.items()), 'locked files changed'
    save('manifest.json', {
        'purpose': 'base-spectrum L1/L2 comparison; diagnostics only, NOT production',
        'sampling': meta, 'calibration': cal, 'commands': COMMANDS,
        'constantsSha256': locked_consts,
        'sourceHashes': {java: sha(ROOT/java),
                         str(Path(__file__).resolve().relative_to(ROOT)): sha(Path(__file__)),
                         **{str(p.relative_to(ROOT)): sha(p) for p in
                            (ROOT/'src/main/java/com/geogenesis/worldgen/terrain/morph').glob('*.java')}},
        'render': 'fixed e[-.35,1.2]; light(-.5,-.3,1); shade .65+.35dot; HS2; Y proxy 236.44*1.5*h',
        'files': {str(p.relative_to(OUT)): sha(p) for p in OUT.rglob('*')
                  if p.is_file() and 'classes' not in p.parts}})
    (OUT/'verification.txt').write_text(
        'PASS fresh javac; approved reference RGBA exact\n'
        'PASS A full SHA equals previous locked production A\n'
        'PASS both arms match k*(f*base+.25E)*shell at production warped coordinates\n'
        'PASS calibration constants hashed before/after render (unchanged)\n'
        'PASS arm reconstruction, all finite, locked baselines unchanged\n'
        'NOT VERIFIED: mixed-type transitions, infinite world, MC blocks, all-octave masks\n', encoding='utf-8')
    print(json.dumps(stats, indent=2))
    print('DONE', OUT)


if __name__ == '__main__':
    main()
