#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Local-window renderer for the single-shaper A/B (plan: morph-rune-single-shaper).

Reads DIR/manifest.json (produced by the main agent) plus per-window arm arrays:
  DIR/<window>/<arm>.f64be            row-major z, col x, big-endian float64 Y
  DIR/<window>/mountain-weight.f64be  m in [0,1], same shape
  DIR/<window>/coverage-alpha.f64be   overlay alpha in [0,1], same shape
Arm count is taken from manifest['arms']; a 4th arm (e.g. 'rounded') renders
automatically with labels derived from the window name.

Outputs per window: <arm>.png, comparison.png (native pixels, no resampling),
profiles.png (MC vertical scale -64..320, wu ticks, hs), profiles.csv,
metrics.json (diff stats vs control, rune-vs-carrier, level quantiles, gradient
quantiles with tail ratio, slope-direction turns, negative-control agreement).
At the DIR root: render-info.json (manifest SHA, seed/hs/arms/settings, versions)
and SHA256SUMS.json over every input and output. The input manifest.json is read
only and never rewritten; this tool writes nothing outside DIR, so pre-existing
baselines elsewhere stay untouched.

Optional inputs are tolerated but not consumed: land-<arm>.f64be (post-coverage
landE) is recorded by name/SHA only, and manifest window['profileRows'] (if any)
is used to pick the two representative profile rows.

Colors/light convention are NOT copied here: the approved renderer
tools/cmp-render-versions.py is imported at runtime and its shade() is called
with WINDOW['step'] set to the window stepWu, so there is a single source for
palette and light direction (verified by tools/verify-light-convention.py).
ASCII-only source; Python 3.8; numpy + Pillow.

Usage:
  python -B render-single-shaper.py DIR           # render all windows
  python -B render-single-shaper.py --selftest    # synthetic data in system temp
"""
import csv
import hashlib
import importlib.util
import json
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parents[2]
_spec = importlib.util.spec_from_file_location('palette', ROOT / 'tools/cmp-render-versions.py')
palette = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(palette)

YLIM = (-64.0, 320.0)
SEA = 63.0
ARM_LABELS = {'control': 'O0 / P1', 'carrier': 'O1 / remove texture',
              'rune': 'O2 / single Rune', 'rounded': 'O3 / rounded carrier + Rune',
              'guided': 'Macro guide + retained residual + Rune'}
EXTRA_ARM_COLORS = [(30, 130, 30), (160, 90, 20), (120, 40, 160)]


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read_f64(path, size):
    if not path.exists():
        raise SystemExit('FAIL missing input %s' % path)
    raw = path.read_bytes()
    need = size * size * 8
    if len(raw) != need:
        raise SystemExit('FAIL %s: %d bytes, expected %d' % (path, len(raw), need))
    a = np.frombuffer(raw, dtype='>f8').reshape(size, size)
    if not np.isfinite(a).all():
        raise SystemExit('FAIL %s: non-finite values' % path)
    return a


def shade(h, step, out_png=None):
    """Approved palette/hillshade via the shared renderer module (single source)."""
    palette.WINDOW['step'] = step
    rgb = palette.shade(h)
    if out_png is not None:
        Image.fromarray(rgb).save(out_png)
    return rgb


def panel(images, titles, out):
    w, h = images[0].shape[1], images[0].shape[0]
    pad, head = 8, 26
    canvas = Image.new('RGB', (len(images) * (w + pad) + pad, h + head + pad), 'white')
    draw = ImageDraw.Draw(canvas)
    limit = max(8, w // 6)   # keep labels inside their own panel
    for i, (img, title) in enumerate(zip(images, titles)):
        if len(title) > limit:
            title = title[:limit - 1] + '~'
        canvas.paste(Image.fromarray(img), (pad + i * (w + pad), head))
        draw.text((pad + i * (w + pad) + 4, 8), title, fill='black')
    canvas.save(out)


PROFILE_COLORS = {'control': 'black', 'carrier': (200, 40, 40), 'rune': (30, 70, 220)}


def arm_color(idx):
    """Line colors for profile plots only (shading comes from palette.shade)."""
    if idx < 3:
        return PROFILE_COLORS[('control', 'carrier', 'rune')[idx]]
    return EXTRA_ARM_COLORS[(idx - 3) % len(EXTRA_ARM_COLORS)]


def pick_profile_rows(carrier, rune, size, candidates):
    """Row through the actual max|rune-carrier| pixel, plus two representative rows.

    Row 0 passes through the single largest pointwise |rune-carrier| sample
    (unravel_index argmax), not the largest row sum. The other two come from
    manifest window['profileRows'] when given, else evenly spaced thirds,
    skipping duplicates of row 0.
    """
    peak_row, peak_col = np.unravel_index(np.argmax(np.abs(rune - carrier)), carrier.shape)
    rows = [int(peak_row)]
    pool = [int(r) for r in candidates] if candidates else [size // 4, size // 2, 3 * size // 4]
    for r in pool:
        if 0 <= r < size and r not in rows:
            rows.append(r)
        if len(rows) == 3:
            break
    for r in (size // 4, size // 2, 3 * size // 4):
        if len(rows) == 3:
            break
        if r not in rows:
            rows.append(r)
    return rows, (int(peak_row), int(peak_col))


def arm_color_name(idx):
    return ('black', 'red', 'blue', 'green', 'brown', 'purple')[
        idx if idx < 3 else 3 + (idx - 3) % len(EXTRA_ARM_COLORS)]


def profile_png(arrays, names, rows, step, origin, out, hs=2.0, peak=None):
    n = arrays[0].shape[0]
    canvas = Image.new('RGB', (1500, 310 * len(rows) + 60), 'white')
    d = ImageDraw.Draw(canvas)
    d.text((60, 8), 'Vertical scale fixed MC -64..320; horizontal %.0f wu = %.0f MC blocks (hs=%.2f);'
           ' colors: %s.' % ((n - 1) * step, (n - 1) * step * hs, hs,
                             ', '.join('%s=%s' % (nm, arm_color_name(i)) for i, nm in enumerate(names))),
           fill='black')
    for k, row in enumerate(rows):
        top, bottom = 55 + k * 310, 275 + k * 310
        wz = origin[1] + row * step
        d.text((65, top - 16), 'Section z=%.1f wu (row %d); x spans %.1f..%.1f wu (%.0f wu total)'
               % (wz, row, origin[0], origin[0] + (n - 1) * step, (n - 1) * step), fill='black')
        for y in [0, 63, 150, 250, 320]:
            py = bottom - (y - YLIM[0]) / (YLIM[1] - YLIM[0]) * 220
            d.line((65, py, 1465, py), fill=(180, 205, 220) if y == 63 else (225, 225, 225))
            d.text((25, py - 5), str(y), fill='black')
        if k == 0 and peak is not None:
            px = 65 + peak[1] / (n - 1) * 1400
            d.line((px, top, px, bottom), fill=(120, 120, 120))
            d.text((px + 3, top + 2), 'max |rune-carrier| at col %d' % peak[1], fill=(60, 60, 60))
        for a, color in zip(arrays, map(arm_color, range(len(arrays)))):
            d.line([(65 + i / (n - 1) * 1400, bottom - (float(v) - YLIM[0]) / 384 * 220)
                    for i, v in enumerate(a[row])], fill=color)
        for j in range(5):
            px = 65 + j / 4 * 1400
            d.line((px, bottom, px, bottom + 5), fill='black')
            d.text((px - 18, bottom + 8), '%.0f wu' % (origin[0] + j / 4 * (n - 1) * step), fill='black')
    canvas.save(out)


def profile_csv(arrays, names, rows, step, origin, out):
    n = arrays[0].shape[0]
    with open(out, 'w', newline='') as f:
        wr = csv.writer(f)
        wr.writerow(['row_z', 'col_x', 'worldX_wu', 'worldZ_wu'] + ['%s_Y' % nm for nm in names])
        for row in rows:
            for i in range(n):
                wr.writerow([row, i, origin[0] + i * step, origin[1] + row * step]
                            + ['%.6f' % float(a[row][i]) for a in arrays])


def diff_stats(base, a, sea=SEA):
    d = a - base
    ad = np.abs(d)
    return {'meanAbsDeltaY': float(ad.mean()), 'p99AbsDeltaY': float(np.quantile(ad, .99)),
            'maxAbsDeltaY': float(ad.max()), 'seaLandFlips': int(((a < sea) != (base < sea)).sum())}


def level_stats(a):
    return {'minY': float(a.min()), 'maxY': float(a.max()),
            'p50Y': float(np.quantile(a, .5)), 'p99Y': float(np.quantile(a, .99))}


def grad_stats(a, step, hs=2.0):
    """Gradient magnitudes: Y/wu and Y/MC block (block = wu * hs, hs=2 default)."""
    gy, gx = np.gradient(a, step)
    g = np.sqrt(gx * gx + gy * gy)
    p50, p99 = float(np.quantile(g, .5)), float(np.quantile(g, .999))
    return {'gradP50_Y_per_wu': p50, 'gradP99_Y_per_wu': float(np.quantile(g, .99)),
            'gradP999_Y_per_wu': p99, 'gradTailRatio': (p99 / p50) if p50 > 0 else None,
            'gradP50_Y_per_MCblock': p50 / hs, 'gradP99_Y_per_MCblock': float(np.quantile(g, .99)) / hs,
            'gradP999_Y_per_MCblock': p99 / hs, 'mcBlocksPerWu': hs}


def slope_flip_stats(a, step, eps=1e-9):
    """Adjacent-sample slope-direction change rate for the rune arm.

    Descriptive only: a low turn rate does not prove drainage continuity, and a
    high one does not by itself prove a defect. A pair counts only when BOTH
    adjacent gradients are longer than eps (AND, not a summed squared length:
    opposite directions can sum to zero while the aspect still flips >90 deg).
    """
    gy, gx = np.gradient(a, step)
    ang = np.arctan2(gy, gx)
    mag = np.hypot(gx, gy)
    # Wrap each adjacent turn into [0, pi] so 359.7 deg reads as 0.3 deg.
    tx = np.abs(np.arctan2(np.sin(np.diff(ang, axis=1)), np.cos(np.diff(ang, axis=1))))
    tz = np.abs(np.arctan2(np.sin(np.diff(ang, axis=0)), np.cos(np.diff(ang, axis=0))))
    turns = np.concatenate([tx.ravel(), tz.ravel()])
    sx = (mag[:, 1:] > eps) & (mag[:, :-1] > eps)
    sz = (mag[1:, :] > eps) & (mag[:-1, :] > eps)
    sig = np.concatenate([sx.ravel(), sz.ravel()])
    if not sig.any():
        return {'note': 'no pair with both adjacent gradients > eps; slope-direction stats not applicable',
                'epsY_per_wu': eps}
    t = turns[sig]
    deg = np.degrees(t)
    return {'slopeFlipFrac_gt90deg': float(np.mean(t > np.pi / 2)),
            'turnCount': int(sig.sum()),
            'turnDegMin': float(deg.min()), 'turnDegP50': float(np.quantile(deg, .5)),
            'turnDegP99': float(np.quantile(deg, .99)), 'turnDegMax': float(deg.max()),
            'epsY_per_wu': eps,
            'note': 'descriptive adjacent-cell aspect change (both-neighbour gradient AND); '
                    'not a hydrology-connectivity proof'}


def negcontrol_stats(arrays, names, weight, alpha):
    """Bit-identity of each arm vs control where weight==0 or alpha==1.

    Compared on the raw big-endian bit patterns (a.view('>u8')), not on ==, so
    +0.0 vs -0.0 counts as a difference. This is a hard contract of the plan:
    any differing sample under either mask fails the run (non-zero exit).
    """
    wm, am = (weight == 0), (alpha == 1)
    mask = wm | am
    base_bits = arrays[0].view('>u8')
    out = {'negControlWeight0Count': int(wm.sum()), 'negControlAlpha1Count': int(am.sum()),
           'negControlCount': int(mask.sum()),
           'contract': 'bit-identical to control where weight==0 or alpha==1 (hard requirement)',
           'comparedAs': 'big-endian uint64 bit patterns, so +0.0/-0.0 differ'}
    for nm, a in zip(names[1:], arrays[1:]):
        same = (a.view('>u8') == base_bits)
        out[nm] = {'weight0BitSame': int((same & wm).sum()), 'alpha1BitSame': int((same & am).sum()),
                   'negControlBitSame': int((same & mask).sum()),
                   'negControlAllSame': bool(same[mask].all()) if mask.any() else None}
        bad = int((~same & mask).sum())
        if bad:
            raise SystemExit('FAIL %s: %d negative-control samples differ from control '
                             '(weight==0 or alpha==1 must be bit-identical)' % (nm, bad))
    return out


def optional_land_inputs(folder, arms):
    """land-<arm>.f64be may exist (post-coverage landE, later mapped to Y).

    Not consumed in this round: recorded by name and SHA only so the main agent
    can tell whether the files were present, without this tool drawing them.
    """
    present = {}
    for nm in arms:
        p = folder / ('land-%s.f64be' % nm)
        if p.exists():
            present['land-%s' % nm] = {'bytes': p.stat().st_size, 'sha256': sha(p)}
    return present


def render_window(folder, meta):
    arms = meta['arms']
    size = int(meta['size'])
    step = float(meta['stepWu'])
    origin = [float(v) for v in meta['originWu']]
    arrays = [read_f64(folder / (nm + '.f64be'), size) for nm in arms]
    weight = read_f64(folder / 'mountain-weight.f64be', size)
    alpha = read_f64(folder / 'coverage-alpha.f64be', size)
    labels = ['%s: %s' % (ARM_LABELS.get(nm, nm), meta.get('name', folder.name)) for nm in arms]
    images = [shade(a, step, folder / (nm + '.png')) for nm, a in zip(arms, arrays)]
    panel(images, labels, folder / 'comparison.png')

    active = 'guided' if 'guided' in arms else 'rune'
    control, rune = arrays[0], arrays[arms.index(active)] if active in arms else arrays[-1]
    carrier = arrays[arms.index('carrier')] if 'carrier' in arms else arrays[0]
    hs = float(meta.get('hs') or 2.0)
    rows, peak = pick_profile_rows(carrier, rune, size, meta.get('profileRows', []))
    profile_png(arrays, arms, rows, step, origin, folder / 'profiles.png', hs, peak)
    profile_csv(arrays, arms, rows, step, origin, folder / 'profiles.csv')

    grad = {}
    for nm, a in zip(arms, arrays):
        grad[nm] = grad_stats(a, step, hs)
    metrics = {'window': meta, 'activeShaperArm': active, 'profileRows': rows,
               'peakAbsRuneCarrier': {'row': peak[0], 'col': peak[1],
                                      'worldX_wu': origin[0] + peak[1] * step,
                                      'worldZ_wu': origin[1] + peak[0] * step,
                                      'valueY': float(abs(rune[peak] - carrier[peak]))},
               'profileSelection':
               'row0 passes through the argmax |rune-carrier| sample; others from manifest profileRows or thirds',
               'units': 'heights Y in MC blocks; gradient Y/wu and Y/MC block; block = wu * hs (hs=%.2f)' % hs,
               'seaLevel': SEA,
               'levels': {nm: level_stats(a) for nm, a in zip(arms, arrays)},
               'vsControl': {nm: diff_stats(control, a) for nm, a in zip(arms[1:], arrays[1:])},
               'runeVsCarrier': diff_stats(carrier, rune),
               'gradient': grad,
               'slopeFlipRune': slope_flip_stats(rune, step),
               'negativeControl': negcontrol_stats(arrays, arms, weight, alpha),
               'optionalLandInputsIgnored': optional_land_inputs(folder, arms),
               'sha256': {nm: sha(folder / (nm + '.f64be')) for nm in arms},
               'sha256aux': {nm: sha(folder / (nm + '.f64be'))
                             for nm in ('mountain-weight', 'coverage-alpha')}}
    (folder / 'metrics.json').write_text(json.dumps(metrics, indent=2), encoding='utf-8')
    vc, rc = metrics['vsControl'], metrics['runeVsCarrier']
    print('[%s] size=%d step=%.3f rows=%s | rune-carrier mean|d|=%.4f p99=%.4f max=%.4f flips=%d'
          ' | negctl=%d allSame=%s'
          % (folder.name, size, step, rows, rc['meanAbsDeltaY'], rc['p99AbsDeltaY'],
             rc['maxAbsDeltaY'], rc['seaLandFlips'], metrics['negativeControl']['negControlCount'],
             (all(v['negControlAllSame'] for v in metrics['negativeControl'].values()
                  if isinstance(v, dict)) if metrics['negativeControl']['negControlCount'] else None)))
    for nm in arms[1:]:
        print('    %s vs control mean|d|=%.4f p99=%.4f max=%.4f flips=%d'
              % (nm, vc[nm]['meanAbsDeltaY'], vc[nm]['p99AbsDeltaY'],
                 vc[nm]['maxAbsDeltaY'], vc[nm]['seaLandFlips']))
    return metrics


def selftest():
    """Synthetic-data smoke test in an auto-cleaned system temp dir."""
    import tempfile
    with tempfile.TemporaryDirectory(prefix='geogenesis-single-shaper-') as tmp:
        d = Path(tmp) / 'mountain'
        d.mkdir(parents=True)
        size, step = 65, 2.0
        yy, xx = np.mgrid[0:size, 0:size].astype(float)
        base = 100 + 30 * np.sin(xx / 9.0) + 20 * np.cos(yy / 7.0)
        weight = np.where((xx > 10) & (xx < 20), 0.0, 0.7)
        alpha = np.where((yy > 40) & (yy < 50), 1.0, 0.0)
        live = (weight > 0) & (alpha < 1)
        # np.where keeps masked samples bit-identical (adding 0.0 to -0.0 does not).
        carrier = np.where(live, base + 5 * np.sin(xx / 3.0) * np.cos(yy / 4.0), base)
        rune = np.where(live, carrier + 8 * np.sin(xx / 2.0)
                        * np.exp(-((xx - 32) ** 2 + (yy - 32) ** 2) / 200.0), carrier)
        meta = {'seed': 1, 'hs': 2.0, 'arms': ['control', 'carrier', 'rune'],
                'name': 'mountain', 'size': size, 'stepWu': step,
                'originWu': [-64.0, -64.0], 'settings': {}}
        (d / 'manifest.json').write_text(json.dumps(meta), encoding='utf-8')
        for nm, a in [('control', base), ('carrier', carrier), ('rune', rune),
                      ('mountain-weight', weight), ('coverage-alpha', alpha)]:
            (d / (nm + '.f64be')).write_bytes(a.astype('>f8').tobytes())
        render_window(d, meta)
        (Path(tmp) / 'manifest.json').write_text(json.dumps(meta), encoding='utf-8')
        render_info(Path(tmp), meta, [meta])
        need = ['control.png', 'carrier.png', 'rune.png', 'comparison.png', 'profiles.png',
                'profiles.csv', 'metrics.json']
        missing = [nm for nm in need if not (d / nm).exists()]
        if missing:
            raise SystemExit('FAIL selftest missing %s' % missing)
        m = json.loads((d / 'metrics.json').read_text(encoding='utf-8'))
        nc = m['negativeControl']
        assert nc['negControlWeight0Count'] > 0 and nc['negControlAlpha1Count'] > 0
        assert 'control' not in nc
        for nm in ('carrier', 'rune'):
            assert nc[nm]['negControlBitSame'] == nc['negControlCount']
            assert nc[nm]['negControlAllSame'] is True
        pk = m['peakAbsRuneCarrier']
        assert (pk['row'], pk['col']) == tuple(int(v) for v in
                                               np.unravel_index(np.argmax(np.abs(rune - carrier)), rune.shape))
        assert 'turnDegP50' in m['slopeFlipRune']
        selftest_signed_zero()
    print('selftest OK (temp dir auto-removed)')


def selftest_signed_zero():
    """+0.0 vs -0.0 is invisible to == but must fail the hard negative-control contract."""
    n = 5
    control = np.zeros((n, n))
    control[2, 2] = -0.0
    rune = control.copy()
    rune[2, 2] = 0.0
    assert (rune == control).all(), 'sanity: == cannot see signed zero'
    zeros = np.zeros((n, n))   # weight==0 everywhere, so the whole window is negative control
    try:
        negcontrol_stats([control, rune], ['control', 'rune'], zeros, zeros)
    except SystemExit as exc:
        assert 'negative-control' in str(exc), exc
        return
    raise SystemExit('FAIL signed-zero case slipped through the bit-identity contract')


def window_meta(manifest, win):
    """Per-window meta = manifest defaults overlaid by the window entry."""
    meta = {k: manifest[k] for k in ('seed', 'hs', 'arms', 'settings') if k in manifest}
    meta.update(win)
    meta.setdefault('arms', manifest.get('arms'))
    if not meta['arms']:
        raise SystemExit('FAIL manifest has no arms')
    return meta


def render_info(root, manifest, windows):
    import numpy
    import PIL
    info = {'tool': 'forge-1.20.1-47.4.10-mdk/tools/render-single-shaper.py',
            'manifest': {'file': 'manifest.json', 'sha256': sha(root / 'manifest.json'),
                         'seed': manifest.get('seed'), 'hs': manifest.get('hs'),
                         'arms': manifest.get('arms'), 'settings': manifest.get('settings'),
                         'preservedVerbatim': True},
            'windows': [{'name': w['name'], 'size': w['size'], 'stepWu': w['stepWu'],
                         'originWu': w.get('originWu'), 'selection': w.get('selection')}
                        for w in windows],
            'units': 'height Y in MC blocks; gradient Y/wu and Y/MC block; block = wu * hs',
            'versions': {'python': sys.version.split()[0], 'numpy': numpy.__version__,
                         'pillow': PIL.__version__},
            'lightConvention': 'palette/light imported at runtime from tools/cmp-render-versions.py '
                               '(shade() with WINDOW[step]=stepWu); not copied into this tool'}
    (root / 'render-info.json').write_text(json.dumps(info, indent=2), encoding='utf-8')


def main():
    if len(sys.argv) < 2:
        raise SystemExit('usage: python -B render-single-shaper.py DIR [--selftest]')
    if '--selftest' in sys.argv:
        selftest()
        return
    root = Path(sys.argv[1])
    manifest = json.loads((root / 'manifest.json').read_text(encoding='utf-8'))
    windows = [window_meta(manifest, w) for w in manifest['windows']]
    for win in windows:
        render_window(root / win['name'], win)
    render_info(root, manifest, windows)
    # Exclude SHA256SUMS.json itself so repeated renders do not record a stale
    # hash of the previous run's own output.
    sums = {str(p.relative_to(root)): sha(p) for p in sorted(root.rglob('*'))
            if p.is_file() and p.name != 'SHA256SUMS.json'}
    (root / 'SHA256SUMS.json').write_text(json.dumps(sums, indent=2), encoding='utf-8')
    print('windows=%d arms=%s inputs_sha256=%s'
          % (len(windows), manifest.get('arms'), sums.get('manifest.json')))


if __name__ == '__main__':
    main()
