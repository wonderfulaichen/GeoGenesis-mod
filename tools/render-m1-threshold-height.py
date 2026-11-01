#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""M1a read-only Java height evidence (Python 3.8; numpy and Pillow already used here).

    python -B tools/render-m1-threshold-height.py oldS3Data newM1Raw newOutputDirectory
    python -B tools/render-m1-threshold-height.py --self-test

A: oldS3Data/arm-after/{height.f64be,type.u8,active.u8,sea-weight.f64be,meta.json}
   (shared oldS3Data/meta.json is also supported), oldS3Data/profiles.csv: use b.
B: newM1Raw/{height.f64be,e.f64be,type.u8,active.u8,sea-weight.f64be,winner.u8,
   side.u8,cells.i64be,meta.json}, profiles.csv: use y, validate e but never convert it.
A is historical S3 zero offset; B is threshold .30 without tectonics. Winner labels
may move. This is NOT a weight-semantics comparison or fixed-winner experiment.

Both arms: original 1401-square / step 5 window, micro 600 / macro 1200 / band 240.
Outputs: native height/seafloor PNGs, full and ROI pairs (height, seafloor, type,
active), signed-height difference and water-footprint change maps, six full and
three local Java profiles ALL on Y=[-64,320], metrics.json and SHA256SUMS.txt.
ROI=[0,0,620,660), raw-map pixels stay 1:1. Seafloor shade receives unmodified Y;
only its water-specific shadow branch is disabled, with NO water-plane overlay.
Water means exported height < 63, not a claim about placed Minecraft water blocks.

Validates all inputs before exclusively creating a NEW output directory. Never
replaces an existing output directory, including an empty one or a symlink. A late
IO/verification failure can leave an incomplete directory; do not treat it as PASS.
No generation, Java invocation, height synthesis, production equivalence or visual
quality certification. --self-test uses tiny temporary fixtures ONLY, not M1 data.
"""
import ast
import csv
import hashlib
import importlib.util
import json
import sys
import tempfile
from pathlib import Path

# Set before importing any existing renderer, including its transitive imports.
sys.dont_write_bytecode = True

import numpy as np
from PIL import Image, ImageDraw

ROI = (0, 0, 620, 660)
YLIM = (-64.0, 320.0)
SEA = 63.0
TITLES = ('A｜旧 S3 零偏移（取 arm-after）', 'B｜M1a 阈值 .30／无构造')
EXPECTED = {'size': 1401, 'step': 5, 'origin': [-3642.5, -3365.5],
            'center': [-140, 137], 'scale': 600, 'seaLevel': 63,
            'bandTotal': 240, 'coastProcessing': False}
ULP_TOL = 4.0 * np.finfo(np.float64).eps
WATER_COLORS = np.array([[169, 154, 117], [36, 66, 94],
                         [42, 193, 218], [226, 141, 58]], dtype='u1')
ACTIVE_COLORS = np.array([[232, 229, 216], [50, 116, 166]], dtype='u1')


def need(condition, message):
    if not condition:
        raise ValueError(message)


def helpers():
    path = Path(__file__).with_name('render-coherent-morph.py')
    spec = importlib.util.spec_from_file_location('gg_m1_coherent_helpers', str(path))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module, module.helpers()


def digest(path):
    hasher = hashlib.sha256()
    with path.open('rb') as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b''):
            hasher.update(chunk)
    return hasher.hexdigest()


def record(manifest, label, path, sha):
    manifest[label] = {'path': str(path.resolve()), 'bytes': path.stat().st_size, 'sha256': sha}


def check_meta(raw, arm, finite_json):
    need(isinstance(raw, dict), '%s: metadata must be an object' % arm)
    finite_json(raw, arm)
    for key, expected in EXPECTED.items():
        need(raw.get(key) == expected, '%s: expected %s=%r, got %r'
             % (arm, key, expected, raw.get(key)))
    need(raw.get('coastProcessing') is False, '%s: coastProcessing must be false' % arm)
    macros = [raw[k] for k in ('macroScale', 'macroSpacing') if k in raw]
    need(macros and all(v == 1200 for v in macros), '%s: macro spacing must declare 1200' % arm)
    need(isinstance(raw.get('seed'), str) and raw['seed'], '%s: missing string seed' % arm)
    if arm == 'B':
        need(raw.get('landThreshold') == .30, 'B: expected landThreshold=.30')
        need(raw.get('tectonics') is False, 'B: expected explicit tectonics=false')
    else:
        # Historical S3 omits these keys; do not invent independent verification.
        need(raw.get('landThreshold', 0) == 0, 'A: baseline is not zero-offset S3')
        need('tectonics' not in raw or raw['tectonics'] is False, 'A: unexpected tectonics')


def metadata(old, new, coherent, manifest):
    old_path = old / 'arm-after' / 'meta.json'
    if not old_path.exists():
        old_path = old / 'meta.json'
    meta = {}
    for arm, path in (('A', old_path), ('B', new / 'meta.json')):
        content = path.read_bytes()
        raw = json.loads(content.decode('utf-8-sig'))
        check_meta(raw, arm, coherent.finite_json)
        meta[arm] = raw
        record(manifest, arm + '/meta.json', path, hashlib.sha256(content).hexdigest())
    need(meta['A']['seed'] == meta['B']['seed'], 'A/B seeds differ')
    return meta


def read_common(folder, size, helper, manifest, arm):
    data = {}
    fields = (('height', 'height.f64be', helper.read_f64, (size,)),
              ('sea', 'sea-weight.f64be', helper.read_f64, (size,)),
              ('type', 'type.u8', helper.read_u8, (size, 0, 22)),
              ('active', 'active.u8', helper.read_u8, (size, 1, 23)))
    for key, name, reader, args in fields:
        data[key], sha = reader(folder / name, *args)
        record(manifest, arm + '/' + name, folder / name, sha)
    h, sea = data['height'], data['sea']
    need(np.all((h >= YLIM[0]) & (h <= YLIM[1])), '%s: height outside [-64,320]' % arm)
    need(np.all((sea >= -ULP_TOL) & (sea <= 1 + ULP_TOL)), '%s: sea weight outside [0,1]' % arm)
    return data


def read_new_extra(folder, size, helper, data, manifest):
    for key, name, reader, args in (
            ('e', 'e.f64be', helper.read_f64, (size,)),
            ('winner', 'winner.u8', helper.read_u8, (size, 0, 22)),
            ('side', 'side.u8', helper.read_u8, (size, 0, 1))):
        data[key], sha = reader(folder / name, *args)
        record(manifest, 'B/' + name, folder / name, sha)
    path = folder / 'cells.i64be'
    raw = helper.read_bytes(path, size * size * 8)
    data['cells'] = np.frombuffer(raw, dtype='>i8').reshape(size, size)
    record(manifest, 'B/cells.i64be', path, hashlib.sha256(raw).hexdigest())
    need(np.array_equal(data['side'], (data['winner'] >= 18).astype('u1')),
         'B: side.u8 disagrees with winner land/ocean side')
    # e, cells and winner are evidence, NEVER inputs to a reconstructed height.


def read_profile_csv(path, new):
    raw = path.read_bytes()
    fields = ['line', 'x', 'z', 'e', 'y', 'distance'] if new else ['line', 'x', 'z', 'a', 'b', 'distance']
    reader = csv.DictReader(raw.decode('utf-8-sig').splitlines())
    need(reader.fieldnames == fields, '%s: expected columns %s' % (path, fields))
    groups = {}
    for number, row in enumerate(reader, 2):
        line = (row.get('line') or '').strip()
        need(line.startswith(('full-', 'local-')) and None not in row,
             '%s:%d: invalid line / extra columns' % (path, number))
        values = [float(row[k]) for k in fields[1:]]
        need(np.isfinite(values).all(), '%s:%d: non-finite profile data' % (path, number))
        # Both formats store the requested Java Y in numeric column index 3.
        groups.setdefault(line, []).append([values[0], values[1], values[4], values[3]])
    need(groups, '%s: empty profiles' % path)
    return {line: np.array(rows, dtype=float) for line, rows in groups.items()}, hashlib.sha256(raw).hexdigest()


def profile_specs():
    out = {}
    for k in range(6):
        row = int((k + .5) / 6 * 1401)
        out['full-%d' % row] = (-3642.5, 3357.5, -3365.5 + row * 5, 7001)
    for z in (-2200, -1800, -1300):
        out['local-%d' % z] = (-3500, -800, z, 2701)
    return out


def pair_profiles(old, new, specs):
    need(set(old) == set(new) == set(specs), 'expected the same six full and three local line IDs')
    items, sampling = {'full': [], 'local': []}, {}
    for line, spec in specs.items():
        a, b = old[line], new[line]
        xmin, xmax, z, count = spec
        need(a.shape == b.shape == (count, 4), '%s: wrong profile sample count' % line)
        need(np.array_equal(a[:, :3], b[:, :3]), '%s: A/B coordinates / distance differ' % line)
        need(np.all(np.diff(a[:, 0]) == 1) and a[0, 0] == xmin and a[-1, 0] == xmax,
             '%s: expected original one-block x window' % line)
        need(np.all(a[:, 1] == z), '%s: unexpected z' % line)
        need(np.allclose(a[:, 2], a[:, 0] - xmin, rtol=0, atol=1e-8),
             '%s: distance differs from x offset' % line)
        need(np.all((a[:, 3] >= YLIM[0]) & (a[:, 3] <= YLIM[1]))
             and np.all((b[:, 3] >= YLIM[0]) & (b[:, 3] <= YLIM[1])),
             '%s: refusing clipped profile evidence' % line)
        entry = {'line': line, 'x': a[:, 0], 'a': a[:, 3], 'b': b[:, 3], 'z': z,
                 'label': '%s  z=%g' % (line, z), 'maxDev': float(np.max(np.abs(b[:, 3] - a[:, 3])))}
        items[line.split('-')[0]].append(entry)
        sampling[line] = {'samplesPerArm': count, 'x': [xmin, xmax], 'z': z, 'step': 1}
    return items, sampling


def load_profiles(old, new, manifest):
    groups = []
    for arm, root, is_new in (('A', old, False), ('B', new, True)):
        path = root / 'profiles.csv'
        group, sha = read_profile_csv(path, is_new)
        record(manifest, arm + '/profiles.csv', path, sha)
        groups.append(group)
    return pair_profiles(groups[0], groups[1], profile_specs())


def paths_checked(old, new, dest):
    need(old.is_dir() and new.is_dir(), 'both input roots must exist')
    need(old != new and old not in new.parents and new not in old.parents,
         'input roots must be distinct and non-nested')
    need(not dest.exists() and not dest.is_symlink(), 'refusing existing output path (no overwrite)')
    for root in (old, new):
        need(dest != root and dest not in root.parents and root not in dest.parents,
             'output must be separate from input roots and their ancestors')


def caption():
    return ['A=旧 S3 零偏移（arm-after）｜B=M1a landThreshold=.30、tectonics=false；不是权重语义对照。',
            '两臂微600／宏1200／过渡带240；阈值改变可移动陆海与赢家，不声称固定赢家或固定类型。',
            '1401²原生1:1｜步长5块｜原点(-3642.5,-3365.5)｜中心(-140,137)｜同光照同色带｜海平面63。',
            '只读 Java 真实Y；无高度合成、无水面遮挡；水域统计=Y<63，非实机放置水方块证明。']


def save_png(image, path):
    with path.open('xb') as handle:
        image.save(handle, format='PNG', optimize=True)


def save_panel(dest, name, panels, titles, heading, mor, spots, local=False, extra=()):
    panels = [im.crop(ROI) for im in panels] if local else panels
    lines = caption() + list(extra)
    if local:
        lines.append('ROI [0,0,620,660)：同坐标620×660原生裁剪；从全幅shade裁剪，不重算边缘梯度。')
    canvas, pasted = mor.grid_panel(panels, titles, heading, lines,
                                    '原生格逐像素回读断言；Java接线、生产等价性与最终观感由主代理验证。')
    save_png(canvas, dest / name)
    spots[name] = pasted


def map_images(data, helper, uni, pal):
    out = {}
    for arm in ('A', 'B'):
        raw = data[arm]
        out[arm] = {'height': Image.fromarray(uni.shade(raw['height'], 5, SEA)),
                    'seafloor': Image.fromarray(uni.shade(raw['height'], 5, -np.inf)),
                    'types': Image.fromarray(helper.type_image(raw['type'], pal)),
                    'active': Image.fromarray(ACTIVE_COLORS[(raw['active'] > 1).astype('u1')])}
    return out


def render_maps(data, dest, helper, uni, mor, pal):
    images, spots = map_images(data, helper, uni, pal), {}
    labels = {'height': '真实高度｜同光照同色带（无水面平面覆盖）',
              'seafloor': '海底地形｜原始Y、同色带光照，取消水域弱阴影，不抬高海床',
              'types': '加权主导配方 id：0..17陆地／18..22海洋（非赢家）',
              'active': '参与配方数｜米白=1、蓝>1；不预设两臂相同'}
    for arm in ('A', 'B'):
        for kind in ('height', 'seafloor'):
            name = 'native-%s-%s.png' % (arm.lower(), kind)
            image = images[arm][kind]
            save_png(image, dest / name)
            spots[name] = [(image, (0, 0))]
    for kind, text in labels.items():
        for local in (False, True):
            name = 'pair-%s-%s.png' % (kind, 'local' if local else 'full')
            save_panel(dest, name, [images[a][kind] for a in ('A', 'B')], TITLES,
                       'M1a 对旧 S3｜' + text, mor, spots, local)
    return spots


def water_codes(a, b):
    wa, wb = a < SEA, b < SEA
    return np.where(wa & wb, 1, np.where(~wa & wb, 2, np.where(wa & ~wb, 3, 0))).astype('u1')


def render_differences(data, dest, uni, mor, spots):
    a, b = data['A']['height'], data['B']['height']
    vmax = max(1.0, float(np.max(np.abs(b - a))))
    difference = Image.fromarray(uni.delta_img(a, b, vmax))
    water = Image.fromarray(WATER_COLORS[water_codes(a, b)])
    jobs = [('difference', difference, '真实高度差 B−A',
             ['统一对称色标：蓝=降低 −%.3f／白=0／红=抬升 +%.3f（块）；ROI沿用全幅色标。' % (vmax, vmax)]),
            ('water-change', water, '水面足迹变化（真实Y<63）',
             ['米褐=仍陆地／深蓝=仍水下／青=陆→水／橙=水→陆；仅高度阈值，不由sea-weight替代。'])]
    for kind, image, heading, lines in jobs:
        for local in (False, True):
            save_panel(dest, '%s-%s.png' % (kind, 'local' if local else 'full'),
                       [image], [heading], heading, mor, spots, local, lines)
    return vmax


def text_width(uni, text, size):
    font = uni.font(size)
    return int(font.getlength(text) if hasattr(font, 'getlength') else font.getsize(text)[0])


def draw_profile_row(draw, it, index, top, xlim, helper, uni):
    left, width, height = 162, 1278, 180
    xmin, xmax = xlim
    y_at = lambda y: top + height - (y - YLIM[0]) / (YLIM[1] - YLIM[0]) * height
    draw.rectangle((left, top, left + width, top + height), outline='#c6c2b3')
    for level in (-64, 0, 100, 200, 300, 320):
        y = y_at(level)
        if YLIM[0] < level < YLIM[1]:
            draw.line((left, y, left + width, y), fill='#e4e0d2')
        text = str(level)
        draw.text((left - 12 - text_width(uni, text, 12), y - 7), text,
                  font=uni.font(12), fill=uni.DIM)
    draw.line((left, y_at(SEA), left + width, y_at(SEA)), fill='#4c9ec4', width=1)
    for field, color in (('a', helper.A_COLOR), ('b', helper.B_COLOR)):
        # Every exported sample is drawn; no height interpolation / grid resampling here.
        points = [(left + (x - xmin) / (xmax - xmin) * width, y_at(y))
                  for x, y in zip(it['x'], it[field])]
        draw.line(points, fill=color, width=2)
    draw.text((40, top - 24), '%d. %s | max abs delta=%.3f' % (index + 1, it['label'], it['maxDev']),
              font=uni.font(14), fill=uni.INK)
    for fraction in (0, .25, .5, .75, 1):
        text = '%.1f' % (xmin + fraction * (xmax - xmin))
        x = left + fraction * width - text_width(uni, text, 12) / 2
        draw.text((int(x), top + height + 5), text, font=uni.font(12), fill=uni.DIM)


def render_profiles(items, dest, helper, uni, spots):
    report = {}
    for kind in ('full', 'local'):
        entries = items[kind]
        xlim = (min(float(it['x'][0]) for it in entries), max(float(it['x'][-1]) for it in entries))
        head, row_pitch = 286, 240
        canvas = Image.new('RGB', (1504, head + len(entries) * row_pitch + 60), uni.BG)
        draw = ImageDraw.Draw(canvas)
        heading = 'Java真实高度剖面｜%s｜全部统一Y ∈ [-64,320]' % ('六条全幅' if kind == 'full' else '三条局部')
        draw.text((40, 18), heading, font=uni.fit(heading, 1424, 30), fill=uni.INK)
        for i, line in enumerate(caption()):
            draw.text((40, 66 + i * 30), line, font=uni.fit(line, 1424, 17), fill=uni.DIM)
        for i, (color, label) in enumerate(((helper.A_COLOR, TITLES[0]),
                                           (helper.B_COLOR, TITLES[1]), ('#4c9ec4', '海平面 Y=63'))):
            x = 40 + i * 485
            draw.line((x, 218, x + 35, 218), fill=color, width=3 if i < 2 else 1)
            draw.text((x + 44, 208), label, font=uni.fit(label, 420, 17), fill=uni.INK)
        for i, it in enumerate(entries):
            draw_profile_row(draw, it, i, head + i * row_pitch, xlim, helper, uni)
        note = '横轴=世界x（块）；A取旧CSV的b，B取新CSV的y；所有Java逐块样点均绘制，无高度裁切。'
        draw.text((40, canvas.height - 40), note, font=uni.fit(note, 1424, 17), fill=uni.DIM)
        name = 'profiles-%s.png' % kind
        save_png(canvas, dest / name)
        spots[name] = [(canvas, (0, 0))]
        report[kind] = {'lines': [it['line'] for it in entries], 'ylim': list(YLIM), 'xlim': list(xlim),
                        'clippedSamples': 0, 'samplesDrawnPerArm': sum(len(it['x']) for it in entries),
                        'maxAbsDeltaByLine': {it['line']: it['maxDev'] for it in entries},
                        'sourceColumns': {'A': 'b', 'B': 'y'}, 'allInputSamplesDrawn': True}
    return report


def comparison(a, b, helper):
    out = helper.diff_stats(a['height'], b['height'], a['type'], b['type'], SEA)
    aa, ab = a['active'], b['active']
    out.update({'pixels': int(aa.size), 'activeChangedPixels': int(np.count_nonzero(aa != ab)),
                'pureToTransitionPixels': int(np.count_nonzero((aa == 1) & (ab > 1))),
                'transitionToPurePixels': int(np.count_nonzero((aa > 1) & (ab == 1)))})
    counts = np.bincount(water_codes(a['height'], b['height']).ravel(), minlength=4)
    water = {key: int(n) for key, n in zip(('unchangedLand', 'unchangedWater', 'landToWater', 'waterToLand'), counts)}
    water['changedPixels'] = water['landToWater'] + water['waterToLand']
    water['netWaterPixels'] = water['landToWater'] - water['waterToLand']
    water['changedPct'] = 100.0 * water['changedPixels'] / aa.size
    water['sampleAreaApproxBlocks2'] = {k: int(n) * 25 for k, n in water.items()
                                        if k not in ('changedPct', 'netWaterPixels')}
    out['waterFootprint'] = water
    return out


def metrics(data, meta, manifest, sampling, helper, names):
    roi = {arm: {k: data[arm][k][ROI[1]:ROI[3], ROI[0]:ROI[2]]
                 for k in ('height', 'type', 'active', 'sea')} for arm in ('A', 'B')}
    b = data['B']
    return {'tool': 'render-m1-threshold-height', 'geometry': EXPECTED, 'roi': list(ROI), 'meta': meta,
            'comparisonMeaning': {'A': TITLES[0], 'B': TITLES[1],
                                  'notWeightSemanticsComparison': True, 'fixedWinnerClaim': False,
                                  'oldZeroOffsetProvenance': 'historical S3 export; absent meta keys are not independent proof',
                                  'spacing': {'micro': 600, 'macro': 1200, 'bandTotal': 240}},
            'arms': {arm: helper.stats(data[arm], SEA, names) for arm in ('A', 'B')},
            'comparison': comparison(data['A'], data['B'], helper),
            'comparisonROI': comparison(roi['A'], roi['B'], helper),
            'profileSampling': sampling, 'inputs': manifest,
            'inputsSHA256': {k: v['sha256'] for k, v in manifest.items()},
            'newRawEvidence': {'winnerHistogram': np.bincount(b['winner'].ravel(), minlength=23).tolist(),
                               'sideHistogram': np.bincount(b['side'].ravel(), minlength=2).tolist(),
                               'winnerSideAgreementChecked': True, 'cellsShape': list(b['cells'].shape),
                               'eRange': [float(b['e'].min()), float(b['e'].max())],
                               'eUsedToSynthesizeY': False, 'oldWinnerProvidedOrCompared': False},
            'types': {'names': names, 'meaning': 'weighted dominant recipe, NOT original winner'},
            'seafloor': {'unmodifiedJavaHeight': True, 'waterPlaneOverlay': False,
                         'shader': 'same shade(height,5,-inf): disable water shadow branch only; same anchors/light'},
            'limits': ['Y<63 is potential water footprint, not placed water blocks.',
                       'Raw maps are native 1:1; profiles are charts of all original Java CSV samples.',
                       'SHA and pixel readback do not certify Java wiring, production parity or visual quality.',
                       'No baseline substitution, fixed-winner assumption or height generation is performed.']}


def inputs_unchanged(manifest):
    for label, info in manifest.items():
        path = Path(info['path'])
        need(path.stat().st_size == info['bytes'] and digest(path) == info['sha256'],
             '%s: input changed during render; evidence is incomplete' % label)


def write_evidence(dest, payload, figures):
    payload['figures'] = figures
    payload['outputsSHA256'] = {name: digest(dest / name) for name in figures}
    with (dest / 'metrics.json').open('x', encoding='utf-8') as handle:
        handle.write(json.dumps(payload, ensure_ascii=False, indent=2, allow_nan=False) + '\n')
    hashes = dict(payload['outputsSHA256'])
    hashes['metrics.json'] = digest(dest / 'metrics.json')
    with (dest / 'SHA256SUMS.txt').open('x', encoding='utf-8') as handle:
        handle.write(''.join('%s  %s\n' % (hashes[name], name) for name in sorted(hashes)))


def function_lengths():
    source = Path(__file__).read_text(encoding='utf-8')
    tree, out = ast.parse(source, feature_version=8), {}
    for node in ast.walk(tree):
        if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
            length = node.end_lineno - node.lineno + 1
            need(length <= 80, '%s exceeds 80 lines (%d)' % (node.name, length))
            out[node.name] = length
    return out


def expect_failure(label, action):
    try:
        action()
    except Exception:
        return label
    raise AssertionError('negative control was accepted: ' + label)


def self_test_arrays(root, helper):
    folder = root / 'tiny'
    folder.mkdir()
    size = 4
    for name in ('height.f64be', 'sea-weight.f64be', 'e.f64be'):
        (folder / name).write_bytes(np.zeros((size, size), dtype='>f8').tobytes())
    for name, value in (('type.u8', 0), ('active.u8', 1), ('winner.u8', 0), ('side.u8', 0)):
        (folder / name).write_bytes(bytes([value]) * size ** 2)
    (folder / 'cells.i64be').write_bytes(np.zeros((size, size), dtype='>i8').tobytes())
    manifest = {}
    data = read_common(folder, size, helper, manifest, 'B')
    read_new_extra(folder, size, helper, data, manifest)
    cases = [expect_failure('wrong byte length', lambda: helper.read_f64(folder / 'height.f64be', 5))]
    (folder / 'height.f64be').write_bytes(np.full((size, size), np.nan, dtype='>f8').tobytes())
    cases.append(expect_failure('non-finite height', lambda: helper.read_f64(folder / 'height.f64be', size)))
    (folder / 'side.u8').write_bytes(bytes([1]) * size ** 2)
    cases.append(expect_failure('winner/side mismatch', lambda: read_new_extra(folder, size, helper, data, {})))
    (folder / 'active.u8').write_bytes(bytes(size ** 2))
    cases.append(expect_failure('zero active', lambda: helper.read_u8(folder / 'active.u8', size, 1, 23)))
    return cases


def self_test_profiles(root):
    old_path, new_path = root / 'old.csv', root / 'new.csv'
    old_path.write_text('line,x,z,a,b,distance\nfull-test,0,7,91,10,0\nfull-test,1,7,92,11,1\n', encoding='utf-8')
    new_path.write_text('line,x,z,e,y,distance\nfull-test,0,7,.1,20,0\nfull-test,1,7,.2,21,1\n', encoding='utf-8')
    old, _ = read_profile_csv(old_path, False)
    new, _ = read_profile_csv(new_path, True)
    specs = {'full-test': (0, 1, 7, 2)}
    paired, _ = pair_profiles(old, new, specs)
    need(paired['full'][0]['a'].tolist() == [10, 11], 'old profiles must select b, not a')
    need(paired['full'][0]['b'].tolist() == [20, 21], 'new profiles must select y, not e')
    cases = [expect_failure('CSV schema swap', lambda: read_profile_csv(new_path, False))]
    shifted = {'full-test': new['full-test'].copy()}
    shifted['full-test'][0, 0] += .5
    cases.append(expect_failure('profile coordinate shift', lambda: pair_profiles(old, shifted, specs)))
    clipped = {'full-test': new['full-test'].copy()}
    clipped['full-test'][0, 3] = 321
    cases.append(expect_failure('profile clipping', lambda: pair_profiles(old, clipped, specs)))
    return cases


def self_test_pixels(root, helper, uni, mor):
    # All fixtures remain tiny and temporary; none can be mistaken for a 1401² export.
    pixels = np.arange(7 * 9 * 3, dtype='u1').reshape(7, 9, 3)
    image = Image.fromarray(pixels)
    canvas, pasted = mor.grid_panel([image, image], ['test A', 'test B'], 'SELF TEST', [], 'temporary')
    name = 'pixels.png'
    save_png(canvas, root / name)
    need(helper.verify({name: pasted}, root)[name]['pixelsPreserved'], 'native readback failed')
    cases = [expect_failure('existing PNG overwrite', lambda: save_png(canvas, root / name))]
    changed = canvas.copy()
    x, y = pasted[0][1]
    changed.putpixel((x, y), (255, 0, 255))
    # The separate corrupted fixture is deliberately different; do not overwrite pixels.png.
    save_png(changed, root / 'corrupt.png')
    cases.append(expect_failure('corrupted native pixel', lambda: helper.verify({'corrupt.png': pasted}, root)))
    reversed_image = Image.fromarray(pixels[:, ::-1].copy())
    cases.append(expect_failure('wrong native orientation',
                                lambda: helper.verify({name: [(reversed_image, pasted[0][1])]}, root)))
    h = np.array([[0., 10., 20.], [5., 15., 25.], [10., 20., 30.]])
    need(not np.array_equal(uni.shade(h, 5, SEA), uni.shade(h, 5, -np.inf)), 'seafloor control has no signal')
    need(np.array_equal(water_codes(np.array([[70., 0., 70., 0.]]), np.array([[70., 0., 0., 70.]])),
                        np.array([[0, 1, 2, 3]], dtype='u1')), 'water transition mapping failed')
    return cases


def self_test():
    coherent, loaded = helpers()
    helper, uni, mor, _, _ = loaded
    lengths = function_lengths()
    valid = dict(EXPECTED, seed='test', macroScale=1200, landThreshold=.30, tectonics=False)
    check_meta(valid, 'B', coherent.finite_json)
    cases = []
    for key, value in (('landThreshold', 0), ('tectonics', True), ('origin', [0, 0]),
                       ('size', 1400), ('macroScale', 600), ('step', float('nan'))):
        bad = dict(valid)
        bad[key] = value
        cases.append(expect_failure('metadata ' + key, lambda: check_meta(bad, 'B', coherent.finite_json)))
    with tempfile.TemporaryDirectory(prefix='gg-m1-render-self-test-') as temporary:
        root = Path(temporary)
        cases.extend(self_test_arrays(root, helper))
        cases.extend(self_test_profiles(root))
        cases.extend(self_test_pixels(root, helper, uni, mor))
        old, new, dest = root / 'old', root / 'new', root / 'dest'
        old.mkdir()
        new.mkdir()
        paths_checked(old, new, dest)
        dest.mkdir()
        cases.append(expect_failure('existing empty output directory', lambda: paths_checked(old, new, dest)))
        cases.append(expect_failure('output inside input', lambda: paths_checked(old, new, old / 'child')))
    print(json.dumps({'selfTest': 'PASS', 'tinyTemporaryFixturesOnly': True,
                      'negativeControlsRejected': cases, 'functionLengths': lengths,
                      'maxFunctionLines': max(lengths.values()), 'noM1GenerationOrEvidence': True}, indent=2))
    return 0


def render(old, new, dest):
    paths_checked(old, new, dest)
    coherent, loaded = helpers()
    helper, uni, mor, pal, names = loaded
    manifest = {}
    meta = metadata(old, new, coherent, manifest)
    data = {'A': read_common(old / 'arm-after', 1401, helper, manifest, 'A'),
            'B': read_common(new, 1401, helper, manifest, 'B')}
    read_new_extra(new, 1401, helper, data['B'], manifest)
    items, sampling = load_profiles(old, new, manifest)
    payload = metrics(data, meta, manifest, sampling, helper, names)
    payload['functionLengths'] = function_lengths()
    # Exclusive mkdir is the no-overwrite boundary; all raw validation precedes it.
    dest.mkdir(parents=True, exist_ok=False)
    spots = render_maps(data, dest, helper, uni, mor, pal)
    payload['differenceColorLimitBlocks'] = render_differences(data, dest, uni, mor, spots)
    payload['profiles'] = render_profiles(items, dest, helper, uni, spots)
    figures = helper.verify(spots, dest)
    inputs_unchanged(manifest)
    payload['validation'] = {'rawLengthsRangesAndFiniteValues': True, 'nativePixelReadback': True,
                             'inputSHARecheckedAfterRendering': True, 'profilesAllWithinFixedYLimits': True,
                             'oldSourceColumn': 'b', 'newSourceColumn': 'y', 'heightSynthesis': False}
    write_evidence(dest, payload, figures)
    print('Wrote %d pixel-verified figures, metrics and SHA256SUMS -> %s' % (len(figures), dest))
    return 0


def main(argv):
    if argv == ['--self-test']:
        action = self_test
    elif len(argv) == 3:
        # Keep the final component unresolved so even a dangling output symlink is rejected.
        old, new = (Path(p).resolve() for p in argv[:2])
        supplied = Path(argv[2]).absolute()
        dest = supplied.parent.resolve() / supplied.name
        action = lambda: render(old, new, dest)
    else:
        sys.stderr.write('usage: python -B tools/render-m1-threshold-height.py oldS3Data newM1Raw newOutputDirectory\n'
                         '       python -B tools/render-m1-threshold-height.py --self-test\n')
        return 2
    try:
        return action()
    except Exception as exc:
        sys.stderr.write('FAIL %s: %s\n' % (type(exc).__name__, exc))
        return 1


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
