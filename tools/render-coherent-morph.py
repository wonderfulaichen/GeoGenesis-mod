#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""S3 read-only evidence: python -B tools/render-coherent-morph.py <root> <new-dest>.

Read arm-before / arm-after height.f64be, type.u8, active.u8, sea-weight.f64be;
meta.json may be per-arm or shared at root. Read shared S2 winner.u8, optional
internal-edge.u8 (diagnostic vicinity only), and Java block-resolution profiles.csv
(line,x,z,a,b,distance; six full-* and three local-* horizontal lines).
Only weight semantics varies: old per-cell aggregation vs recipe-union exterior.
No generation, layout changes, engine changes, scale changes or production edits.
All inputs are validated before creating a NEW output directory. No overwrites.
"""
import csv
import hashlib
import importlib.util
import json
import sys
from pathlib import Path

# Importing the existing renderers must not create files outside this tool's scope.
sys.dont_write_bytecode = True

import numpy as np
from PIL import Image, ImageDraw

ARMS = ('arm-before', 'arm-after')
TITLES = ('前 A｜旧：逐细胞权重归并', '后 B｜新：按配方并集外边权重')
ROI = (0, 0, 620, 660)
FULL_YLIM = (-64.0, 320.0)
# Normalized weight sums may exceed 1 by one float64 ulp; anything larger is a real defect.
ULP_TOL = 4.0 * np.finfo(np.float64).eps
EXPECTED = {'size': 1401, 'step': 5, 'origin': [-3642.5, -3365.5],
            'center': [-140, 137], 'scale': 600, 'seaLevel': 63,
            'bandTotal': 240, 'coastProcessing': False}
ACTIVE_COLORS = np.array([[232, 229, 216], [50, 116, 166]], dtype='u1')


def need(condition, message):
    if not condition:
        raise ValueError(message)


def helpers():
    """Import, rather than copy, the cell-scale API and its shade/grid_panel tools."""
    path = Path(__file__).with_name('render-cell-scale.py')
    spec = importlib.util.spec_from_file_location('gg_coherent_morph_cell_scale', path)
    helper = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(helper)
    uni, mor = helper.load_tools()
    pal, names = helper.palette(uni, mor)
    return helper, uni, mor, pal, names


def sha256(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def finite_json(value, label):
    if isinstance(value, dict):
        for key, child in value.items():
            finite_json(child, '%s.%s' % (label, key))
    elif isinstance(value, list):
        for i, child in enumerate(value):
            finite_json(child, '%s[%d]' % (label, i))
    elif isinstance(value, (float, int)) and not isinstance(value, bool):
        need(np.isfinite(float(value)), '%s: non-finite number' % label)


def metadata(root):
    meta, hashes = {}, {}
    for arm in ARMS:
        path = root / arm / 'meta.json'
        if not path.exists():
            path = root / 'meta.json'
        raw = json.loads(path.read_text(encoding='utf-8-sig'))
        need(isinstance(raw, dict), '%s: metadata must be an object' % path)
        finite_json(raw, str(path))
        for key, expected in EXPECTED.items():
            need(key in raw, '%s: missing %s' % (path, key))
            need(raw[key] == expected and (key != 'coastProcessing' or raw[key] is False),
                 '%s: expected %s=%r, got %r' % (path, key, expected, raw[key]))
        for key in ('macroSpacing', 'macroScale'):
            if key in raw:
                need(raw[key] == 1200, '%s: %s must remain 1200' % (path, key))
        meta[arm] = raw
        hashes[path.relative_to(root).as_posix()] = sha256(path)
    if 'seed' in meta[ARMS[0]] or 'seed' in meta[ARMS[1]]:
        need(meta[ARMS[0]].get('seed') == meta[ARMS[1]].get('seed'), 'arm seeds differ')
    return meta, hashes


def read_arrays(root, helper):
    data, hashes = {}, {}
    fields = (('height', 'height.f64be', helper.read_f64, (1401,)),
              ('sea', 'sea-weight.f64be', helper.read_f64, (1401,)),
              ('type', 'type.u8', helper.read_u8, (1401, 0, 22)),
              ('active', 'active.u8', helper.read_u8, (1401, 1, 255)))
    for arm in ARMS:
        data[arm] = {}
        for key, name, reader, args in fields:
            grid, digest = reader(root / arm / name, *args)
            data[arm][key] = grid
            hashes['%s/%s' % (arm, name)] = digest
        sea = data[arm]['sea']
        # Normalized weights can overshoot [0,1] by 1 ulp from summation order; record, not fail.
        need(np.all((sea >= -ULP_TOL) & (sea <= 1.0 + ULP_TOL)),
             '%s: sea weights outside [0,1] beyond %g ulp tolerance' % (arm, ULP_TOL))
        data[arm]['seaOvershoot'] = int(np.count_nonzero((sea < 0.0) | (sea > 1.0)))
    winner, hashes['winner.u8'] = helper.read_u8(root / 'winner.u8', 1401, 0, 22)
    mask = None
    if (root / 'internal-edge.u8').exists():
        mask, hashes['internal-edge.u8'] = helper.read_u8(root / 'internal-edge.u8', 1401, 0, 1)
    return data, winner, mask, hashes


def read_profiles(path, helper):
    """Reject missing / non-finite values; verify actual one-block horizontal sampling."""
    raw = path.read_bytes()
    reader = csv.DictReader(raw.decode('utf-8-sig').splitlines())
    fields = ['line', 'x', 'z', 'a', 'b', 'distance']
    need(reader.fieldnames == fields, '%s: expected columns %s' % (path, fields))
    groups = {}
    for number, row in enumerate(reader, 2):
        line = (row.get('line') or '').strip()
        need(line.startswith(('full-', 'local-')), '%s:%d: invalid line id' % (path, number))
        need(None not in row, '%s:%d: extra columns' % (path, number))
        values = {key: float(row[key]) for key in fields[1:]}
        need(all(np.isfinite(v) for v in values.values()), '%s:%d: non-finite value' % (path, number))
        groups.setdefault(line, []).append(values)
    items = {kind: helper.profile_items(groups, kind) for kind in ('full', 'local')}
    need(len(items['full']) == 6 and len(items['local']) == 3, 'expected 6 full-* and 3 local-* lines')
    sampling = {}
    for line, rows in groups.items():
        x, z, distance = (np.array([r[k] for r in rows]) for k in ('x', 'z', 'distance'))
        need(len(rows) >= 2 and np.all(np.diff(x) == 1), '%s: expected increasing one-block x steps' % line)
        need(np.all(z == z[0]), '%s: expected horizontal constant-z profile' % line)
        need(np.all(distance >= 0) and np.allclose(np.diff(distance), 1, rtol=0, atol=1e-8),
             '%s: distance must increase one block per sample' % line)
        need(np.allclose(distance - distance[0], x - x[0], rtol=0, atol=1e-8),
             '%s: distance disagrees with x' % line)
        need(x[0] >= EXPECTED['origin'][0] and x[-1] <= EXPECTED['origin'][0] + 7000
             and EXPECTED['origin'][1] <= z[0] <= EXPECTED['origin'][1] + 7000,
             '%s: profile outside the fixed window' % line)
        sampling[line] = {'samples': len(rows), 'x': [float(x[0]), float(x[-1])],
                          'z': float(z[0]), 'distance': [float(distance[0]), float(distance[-1])],
                          'blockStepChecked': 1}
    for it in items['full']:
        need(all(np.all((it[k] >= FULL_YLIM[0]) & (it[k] <= FULL_YLIM[1])) for k in ('a', 'b')),
             '%s: height outside [-64,320]; refusing clipped evidence' % it['line'])
    return items, sampling, hashlib.sha256(raw).hexdigest()


def caption():
    return ['S2布局固定｜唯一变量=权重语义：前逐细胞权重归并 → 后按配方并集外边权重；不是布局或scale对照。',
            '微细胞600／宏1200／过渡带240不变；配方、统一形态引擎、默认与生产均不改；两臂均无特殊海岸处理。',
            '1401²原生1:1｜步长5块｜原点(-3642.5,-3365.5)｜中心(-140,137)｜海平面Y=63｜同光照、同色带。',
            'type为加权后的主导配方，可随权重变化；winner为两臂共用的固定S2原始赢家；浅海22是既有类型。']


def save_panel(dest, name, panels, titles, heading, helper, uni, mor, spots,
               local=False, legend=None, extra=()):
    if local:
        panels = [panel.crop(ROI) for panel in panels]
    lines = caption() + list(extra) + (['ROI [0,0,620,660)：同坐标620×660原生裁剪，不缩放。'] if local else [])
    canvas, pasted = mor.grid_panel(panels, titles, heading, lines,
                                    '只读Java导出真实高度；内部边附近mask仅为诊断区，不代表真实地形接缝。')
    if legend is not None:
        pal, names = legend
        canvas = helper.append_band(canvas, uni.BG, 286,
                                    lambda d, w, t: helper.draw_legend(d, w, t, pal, names, uni))
    canvas.save(dest / name, optimize=True)
    spots[name] = pasted


def render_maps(data, dest, helper, uni, mor, pal, names):
    spots, images = {}, {}
    for arm in ARMS:
        images[arm] = {'height': Image.fromarray(uni.shade(data[arm]['height'], 5, 63)),
                       'type': Image.fromarray(np.uint8(np.clip(pal[data[arm]['type']], 0, 255))),
                       'active': Image.fromarray(ACTIVE_COLORS[(data[arm]['active'] > 1).astype('u1')])}
        name = 'native-%s.png' % arm[4:]
        image = images[arm]['height']
        image.save(dest / name, optimize=True)
        spots[name] = [(image, (0, 0))]
    jobs = [('pair-full.png', 'height', False), ('pair-local-height.png', 'height', True),
            ('pair-types.png', 'type', False), ('pair-local-types.png', 'type', True),
            ('pair-active.png', 'active', False), ('pair-local-active.png', 'active', True)]
    labels = {'height': '真实高度｜同光照同色带', 'type': '主导配方（非原始赢家标签）',
              'active': '过渡活跃｜米白 active=1／蓝 active>1'}
    notes = {'active': ['两臂 active 实测逐像素完全相同：S3只改权重与求距，不提高纯配方比例；'
                        '计数见 metrics.json。']}
    for name, kind, local in jobs:
        save_panel(dest, name, [images[arm][kind] for arm in ARMS], TITLES,
                   '前／后权重语义对照｜' + labels[kind], helper, uni, mor, spots,
                   local, (pal, names) if kind == 'type' else None, notes.get(kind, ()))
    return spots


def sea_band(path, uni, helper):
    """Append a legend band under the reused figure: blue=A, brown=B, thin blue=sea Y=63.

    The panel itself is untouched (pasted below, never over the plot), so no curve is covered.
    """
    with Image.open(path) as source:
        canvas = source.convert('RGB')
    extra = 64
    out = Image.new('RGB', (canvas.width, canvas.height + extra), uni.BG)
    out.paste(canvas, (0, 0))
    draw = ImageDraw.Draw(out)
    top, font = canvas.height + 12, uni.font(15)
    items = ((helper.A_COLOR, '前 A（旧：逐细胞权重归并）', 3),
             (helper.B_COLOR, '后 B（新：按配方并集外边权重）', 3),
             ('#2e5ca8', '海平面 Y=63（参照线，非数据曲线）', 2))
    x = 40
    for color, text, width in items:
        draw.line((x, top + 10, x + 46, top + 10), fill=color, width=width)
        draw.text((x + 56, top + 2), text, font=font, fill=uni.INK)
        x += 66 + int(font.getlength(text) if hasattr(font, 'getlength') else font.getsize(text)[0])
    out.save(path, optimize=True)
    return {'bandPixels': extra, 'coversPlot': False}


def curve_inside(pixels, box):
    """True if any A/B curve colour falls in box (never paint over the evidence curves)."""
    x0, y0, x1, y1 = (int(v) for v in box)
    y1, x1 = min(y1, pixels.shape[0]), min(x1, pixels.shape[1])
    if x0 >= x1 or y0 >= y1:
        return False
    patch = pixels[max(0, y0):y1, max(0, x0):x1]
    return any(np.all(np.abs(patch - np.array(c)) <= 12, axis=-1).any()
               for c in ((0x2f, 0x5f, 0xa8), (0xa8, 0x54, 0x2c)))


def retick_axes(path, rows, xlim, ylim, uni):
    """Repaint only empty chrome so the reused helper's uncorrected text anchors stop colliding.

    Pillow 7.2 ignores text anchors: profile_figure draws x ticks left-aligned (the last one runs
    past the right edge) and drops the y lower bound into the first x tick. Both regions hold no
    profile sample; each is asserted curve-free before repainting. Nothing else is touched.
    """
    with Image.open(path) as source:
        canvas = source.convert('RGB')
    pixels = np.asarray(canvas).astype(int)
    pad, row_h, gap, axis_w, plot_w = 40, 150, 34, 122, 1278
    head, x0 = 70 + 30 * len(caption()) + 34, 40 + 122
    (amin, amax), (lo, hi) = xlim, ylim
    font, bg = uni.font(12), (0xf4, 0xf3, 0xed)
    curve = ((0x2f, 0x5f, 0xa8), (0xa8, 0x54, 0x2c))
    draw = ImageDraw.Draw(canvas)
    cleared_hi = skipped = 0
    for i in range(rows):
        top = head + i * (row_h + gap)
        # Helper ticks occupy +4..+16; the next row's title starts at +20, so stop before it.
        strip = pixels[top + row_h + 1:top + row_h + 19, pad:canvas.width]
        # Tight tolerance: antialiased dark tick text also lands near brown at a loose one.
        need(not any(np.all(np.abs(strip - np.array(c)) <= 12, axis=-1).any() for c in curve),
             'refusing to repaint an axis strip that holds profile colour')
        draw.rectangle((pad, top + row_h + 1, canvas.width, top + row_h + 19), fill=bg)
        for fraction in (0, 0.25, 0.5, 0.75, 1):
            text = '%.1f' % (amin + fraction * (amax - amin))
            width = int(font.getlength(text)) if hasattr(font, 'getlength') else font.getsize(text)[0]
            draw.text((int(x0 + fraction * plot_w - width / 2), top + row_h + 5), text,
                      font=font, fill=uni.DIM)
        hi_text = '%.0f' % hi
        hi_width = int(font.getlength(hi_text)) if hasattr(font, 'getlength') else font.getsize(hi_text)[0]
        stray = (x0 - 6, top + 1, x0 - 6 + hi_width + 2, top + 16)
        if not curve_inside(pixels, stray):
            draw.rectangle(stray, fill=bg)          # helper's in-plot copy of the upper bound
            cleared_hi += 1
        else:
            skipped += 1
        for value, dy in ((lo, -7), (hi, 1)):
            y = top + row_h - int((value - lo) / (hi - lo) * row_h)
            text = '%.0f' % value
            width = int(font.getlength(text)) if hasattr(font, 'getlength') else font.getsize(text)[0]
            # Gutter only (left of the frame, clear of the row title and the tick strip).
            draw.rectangle((pad, y + dy - 2, pad + axis_w - 20, y + dy + 13), fill=bg)
            draw.text((pad + axis_w - 22 - width, y + dy), text, font=font, fill=uni.DIM)
    canvas.save(path, optimize=True)
    return {'xTicksRedrawn': 5 * rows, 'yBoundsInGutter': 2 * rows, 'inPlotStrayCleared': cleared_hi,
            'inPlotStrayKeptCurveSafe': skipped, 'repaintedChromeHeldNoCurve': True}


def render_profiles(items, dest, helper, uni):
    report = {}
    for kind in ('full', 'local'):
        ylim = FULL_YLIM if kind == 'full' else helper.profile_ylim(items[kind], 63)
        path = dest / ('profiles-%s.png' % kind)
        report[kind] = helper.profile_figure(
            items[kind], '前 A（蓝）逐细胞归并／后 B（棕）配方并集外边｜唯一变量=权重语义',
            caption(), '横轴=x（块），Java逐块真实高度；海平面Y=63为另标的蓝色水平参照线；'
            '纵轴%s，所有输入点均在轴内，无高度裁切。'
            % ('固定[-64,320]' if kind == 'full' else '本图共用实测范围加边距'),
            ylim, 63, uni, path)
        need(report[kind]['clippedSamples'] == 0, '%s: clipped profile' % kind)
        xmin = min(float(it['x'].min()) for it in items[kind])
        xmax = max(float(it['x'].max()) for it in items[kind])
        report[kind]['axisRepaint'] = retick_axes(path, len(items[kind]), (xmin, xmax), ylim, uni)
        report[kind]['seaLegend'] = sea_band(path, uni, helper)
        report[kind]['allInputSamplesWithinYLimits'] = True
        report[kind]['plottedSamples'] = {
            it['line']: len(it['x'][::max(1, len(it['x']) // (1278 * 2))]) for it in items[kind]}
        report[kind]['drawing'] = 'existing profile_figure stride-only polyline; no height recomputation'
    return report


def comparison(data, mask=None):
    a, b = (data[arm] for arm in ARMS)
    take = (lambda grid: grid) if mask is None else (lambda grid: grid[mask])
    helper = data['helper']
    out = helper.diff_stats(*(take(arm[key]) for key in ('height', 'type') for arm in (a, b)), 63)
    aa, ab = take(a['active']), take(b['active'])
    out['activeChangedPixels'] = int(np.count_nonzero(aa != ab))
    out['pureToTransitionPixels'] = int(np.count_nonzero((aa == 1) & (ab > 1)))
    out['transitionToPurePixels'] = int(np.count_nonzero((aa > 1) & (ab == 1)))
    out['pixels'] = int(aa.size)
    return out


def metrics(data, winner, mask, meta, hashes, sampling, names, helper):
    # This shared label is evidence supplied by Java, not two independent geometry exports.
    fixed = {'microSpacing': 600, 'macroSpacing': 1200, 'bandTotal': 240,
             'layout': 'shared fixed S2 winner', 'engine': 'unchanged', 'recipes': 'unchanged',
             'defaults': 'unchanged', 'production': 'unchanged'}
    out = {'tool': 'render-coherent-morph', 'geometry': EXPECTED, 'roi': list(ROI), 'meta': meta,
           'singleVariable': {'variable': 'weight semantics, NOT layout or scale',
                              'A': TITLES[0], 'B': TITLES[1], 'declaredFixed': fixed},
           'arms': {arm: {**helper.stats(data[arm], 63, names),
                          'seaWeightOutside01ByUlp': data[arm]['seaOvershoot'],
                          'seaWeightTolerance': ULP_TOL} for arm in ARMS},
           'comparison': comparison(data), 'profileSampling': sampling, 'inputsSHA256': hashes,
           'types': {'names': names, 'range': [0, 22], 'shelf22': 'existing shallow-sea recipe',
                     'meaning': 'weighted dominant recipe; NOT original winner label'},
           'winner': {'sha256': hashes['winner.u8'], 'sharedByArms': True,
                      'histogram': np.bincount(winner.ravel(), minlength=23).tolist(),
                      'dominantDisagreesPixels': {arm: int(np.count_nonzero(data[arm]['type'] != winner))
                                                 for arm in ARMS},
                      'independentArmLayoutVerification': False,
                      'limit': 'one shared S2 winner map; fixed-layout semantics supplied by Java'},
           'internalEdge': {'provided': mask is not None,
                            'meaning': 'diagnostic vicinity mask only; NOT real terrain seams'},
           'scope': 'read-only Java exports; no worldgen, engine / layout / default / production edits',
           'interpretation': {
               'activeIdentical': 'MEASURED result: active.u8 is bit-identical across both arms; '
                                  'S3 changes only weighting / distance tests, it does NOT raise the pure-recipe share',
               'layoutFixed': 'layout is the frozen shared S2 winner map; winner.u8 matches the S2 pixel comparison '
                              'done by Java. Only one map exists, so per-arm layout independence is not verifiable here.',
               'dominantTypeMayMove': 'weighted dominant type may change while winner stays fixed; '
                                      'shallow-sea 22 is a pre-existing type, not something S3 introduces'},
           'validation': {'rawLengthsAndRangesChecked': True, 'allNumericInputsFinite': True,
                          'seaWeightOver01OnlyWithinUlpTolerance': True,
                          'fullProfilesNoClipping': True, 'oneBlockHorizontalProfiles': True},
           'limits': ['Metadata verifies declared window, not Java implementation semantics.',
                      'Pixel fidelity is checked against shaded native images, not a lossless encoding of float heights.',
                      'No visual-quality, production-equivalence or performance PASS is asserted.']}
    roi_mask = np.zeros(winner.shape, dtype=bool)
    roi_mask[ROI[1]:ROI[3], ROI[0]:ROI[2]] = True
    out['comparisonROI'] = comparison(data, roi_mask)
    if mask is not None:
        count = int(np.count_nonzero(mask))
        out['internalEdge']['pixels'] = count
        out['internalEdge']['comparison'] = comparison(data, mask.astype(bool)) if count else None
    return out


def write_evidence(dest, payload, figures):
    payload['figures'] = figures
    payload['outputsSHA256'] = {name: sha256(dest / name) for name in figures}
    with (dest / 'metrics.json').open('x', encoding='utf-8') as handle:
        handle.write(json.dumps(payload, ensure_ascii=False, indent=2, allow_nan=False) + '\n')
    hashes = dict(payload['outputsSHA256'])
    hashes['metrics.json'] = sha256(dest / 'metrics.json')
    with (dest / 'SHA256SUMS.txt').open('x', encoding='utf-8') as handle:
        handle.write(''.join('%s  %s\n' % (hashes[name], name) for name in sorted(hashes)))


def main(argv):
    if len(argv) != 2:
        sys.stderr.write('usage: python -B tools/render-coherent-morph.py <root> <new-dest>\n')
        return 2
    try:
        root, dest = (Path(value).resolve() for value in argv)
        need(root.is_dir(), 'input root must exist')
        need(not dest.exists() and not dest.is_symlink(), 'refusing existing output path (no evidence overwrite)')
        need(dest != root and dest not in root.parents, 'output must not replace root / ancestors')
        need(all(root / arm != dest and root / arm not in dest.parents for arm in ARMS),
             'output must not be inside an input arm')
        helper, uni, mor, pal, names = helpers()
        meta, hashes = metadata(root)
        data, winner, mask, raw_hashes = read_arrays(root, helper)
        data['helper'] = helper
        items, sampling, digest = read_profiles(root / 'profiles.csv', helper)
        hashes.update(raw_hashes)
        hashes['profiles.csv'] = digest
        payload = metrics(data, winner, mask, meta, hashes, sampling, names, helper)
        # Exclusive directory creation is the no-overwrite boundary; validate everything first.
        dest.mkdir(parents=True, exist_ok=False)
        spots = render_maps(data, dest, helper, uni, mor, pal, names)
        payload['profiles'] = render_profiles(items, dest, helper, uni)
        spots.update({'profiles-full.png': [], 'profiles-local.png': []})
        figures = helper.verify(spots, dest)
        write_evidence(dest, payload, figures)
    except Exception as exc:
        sys.stderr.write('%s: %s\n' % (type(exc).__name__, exc))
        return 1
    print('Wrote metrics, SHA256SUMS and %d verified figures -> %s' % (len(figures), dest))
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
