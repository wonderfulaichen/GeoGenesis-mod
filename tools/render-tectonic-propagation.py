#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""R4 Java-export diagnostics; python -B render-tectonic-propagation.py ROOT NEW_OUT.

--self-test uses in-memory negative controls only. No Java execution or height synthesis.
Maps are native 701x701 samples, not interpolated; profiles draw every CSV sample.
"""
import ast
import csv
import hashlib
import io
import json
import math
import sys
from pathlib import Path

sys.dont_write_bytecode = True
import numpy as np
from PIL import Image, ImageDraw, ImageFont

CHANNELS = ('stress', 'distance', 'envelope110', 'convergent110', 'divergent130', 'offset')
ARMS = ('arm-before', 'arm-after')
SEED = '9139912035078620160'
ORIGIN = (-3642.5, -3365.5)
SIZE, STEP = 701, 10
H = 150 / (2 * math.sqrt(2))
FOCUS = {'focus-site': (408.1912317710077, 888.4415715833302, SEED),
         'focus-window': (-2010.7180242616168, -1738.660769782364, '-419'),
         'focus-bisector': (975.0845618990552, 1821.8342492225638, '12345')}
BG, INK = '#f4f3ed', '#25261f'


def need(condition, message):
    if not condition:
        raise ValueError(message)


def read_input(root, name, manifest):
    path = root / name
    raw = path.read_bytes()
    manifest[name] = {'bytes': len(raw), 'sha256': hashlib.sha256(raw).hexdigest()}
    return raw


def parse_json(raw):
    return json.loads(raw.decode('utf-8-sig'), parse_constant=lambda x: need(False, 'non-finite JSON: ' + x))


def check_meta(meta):
    expected = {'seed': SEED, 'size': 701, 'step': 10, 'origin': list(ORIGIN), 'span': 7000,
                'micro': 600, 'macro': 1200, 'bandTotal': 240, 'landThreshold': .30,
                'heightChainConnected': False, 'productionChanged': False, 'cache': 'none'}
    need(isinstance(meta, dict), 'meta must be an object')
    for key, value in expected.items():
        need(meta.get(key) == value and type(meta.get(key)) is type(value), 'metadata mismatch: ' + key)
    return meta


def read_grid(raw, channel, size=SIZE):
    need(len(raw) == size * size * 8, channel + ': incorrect f64be length')
    grid = np.frombuffer(raw, dtype='>f8').astype(np.float64).reshape(size, size)
    need(np.isfinite(grid).all(), channel + ': non-finite')
    if channel == 'stress':
        need(np.all(np.abs(grid) <= 1 + 1e-12), 'stress outside [-1,1]')
    if channel in CHANNELS[2:5]:
        need(np.all((grid >= -1e-12) & (grid <= 1 + 1e-12)), channel + ': outside [0,1]')
    if channel == 'distance':
        need(np.all(grid >= -1e-12), 'negative distance')
    return grid


def profile_specs():
    specs = {}
    for k in range(6):
        row = int((k + .5) / 6 * SIZE)
        specs['full-%d' % row] = (ORIGIN[0], ORIGIN[1] + row * STEP, 7001, SEED)
    for name, (x, z, seed) in FOCUS.items():
        specs[name] = (x - 300, z, 601, seed)
    return specs


def check_profile_meta(meta):
    specs = profile_specs()
    need(isinstance(meta, dict) and set(meta) == set(specs), 'profile metadata IDs mismatch')
    for name, (x, z, count, seed) in specs.items():
        item = meta[name]
        need(isinstance(item, dict) and str(item.get('seed')) == seed, name + ': wrong seed')
        need(item.get('count') == count and item.get('step') == 1, name + ': sampling mismatch')
        need(abs(float(item['x0']) - x) < 1e-8 and abs(float(item['z']) - z) < 1e-8,
             name + ': wrong position')
    return specs


def read_profiles(raw, specs):
    fields = ['line', 'x', 'z', 'distance'] + [p + c for c in CHANNELS for p in ('a_', 'b_')]
    reader = csv.DictReader(io.StringIO(raw.decode('utf-8-sig'), newline=''))
    need(reader.fieldnames == fields, 'profiles CSV header mismatch')
    groups = {name: [] for name in specs}
    for row in reader:
        name = row.get('line')
        need(name in groups and None not in row, 'unknown profile / extra column')
        values = [float(row[key]) for key in fields[1:]]
        need(np.isfinite(values).all(), name + ': non-finite profile')
        groups[name].append(values)
    for name, (x, z, count, seed) in specs.items():
        a = np.array(groups[name], dtype=np.float64)
        need(a.shape == (count, len(fields) - 1), name + ': incorrect sample count')
        need(np.allclose(a[:, 0], x + np.arange(count), rtol=0, atol=1e-8)
             and np.allclose(a[:, 1], z, rtol=0, atol=1e-8)
             and np.array_equal(a[:, 2], np.arange(count)), name + ': incorrect coordinates')
        for i, channel in enumerate(CHANNELS):
            values = a[:, 3 + 2 * i:5 + 2 * i]
            if channel == 'stress':
                need(np.all(np.abs(values) <= 1 + 1e-12), name + ': stress range')
            if channel in CHANNELS[2:5]:
                need(np.all((values >= -1e-12) & (values <= 1 + 1e-12)), name + ': gate range')
            if channel == 'distance':
                need(np.all(values >= -1e-12), name + ': negative distance')
        groups[name] = a
    return groups


def summary(a):
    q = np.percentile(a, [0, 1, 50, 95, 99, 99.9, 100])
    return dict(zip(('min', 'p01', 'p50', 'p95', 'p99', 'p999', 'max'), map(float, q)))


def gradient(a):
    gz, gx = np.gradient(a, STEP)
    mag = np.hypot(gx, gz)
    q = np.percentile(mag, [50, 99.9])
    return mag, {'p50': float(q[0]), 'p999': float(q[1]),
                 'p999OverP50': float(q[1] / q[0]) if q[0] else None,
                 'gxMeanSquare': float(np.mean(gx * gx)), 'gzMeanSquare': float(np.mean(gz * gz))}


def highpass(a):
    # Separable 21x21 box, edge-extended; original raster resolution and coordinates retained.
    padded = np.pad(a, ((0, 0), (10, 10)), mode='edge')
    x = np.cumsum(np.pad(padded, ((0, 0), (1, 0))), axis=1)
    x = (x[:, 21:] - x[:, :-21]) / 21
    padded = np.pad(x, ((10, 10), (0, 0)), mode='edge')
    z = np.cumsum(np.pad(padded, ((1, 0), (0, 0))), axis=0)
    return a - (z[21:, :] - z[:-21, :]) / 21


def pair_stats(a, b):
    delta = b - a
    return {'A': summary(a), 'B': summary(b), 'deltaBminusA': summary(delta),
            'absoluteDelta': summary(np.abs(delta)), 'changedPixels': int(np.count_nonzero(a != b)),
            'signFlipPixels': int(np.count_nonzero(((a < 0) & (b > 0)) | ((a > 0) & (b < 0)))),
            'zeroToOrFromNonzeroPixels': int(np.count_nonzero((a == 0) != (b == 0))),
            'pixelCount': int(a.size)}


def limits(channel, a, b):
    if channel == 'stress':
        return (-1., 1.)
    if channel in CHANNELS[2:5]:
        return (0., 1.)
    if channel == 'offset':
        v = max(float(np.max(np.abs(a))), float(np.max(np.abs(b))), 1e-30)
        return (-v, v)
    lo, hi = min(float(a.min()), float(b.min())), max(float(a.max()), float(b.max()))
    return (lo, hi if hi > lo else lo + 1.)


def colorize(a, lo, hi, diverging=False):
    t = np.clip((a - lo) / (hi - lo), 0, 1)
    if diverging:
        left = np.array([35, 83, 158.]); middle = np.array([245, 244, 235.]); right = np.array([174, 59, 43.])
        rgb = np.where((t < .5)[..., None], left + 2 * t[..., None] * (middle - left),
                       middle + (2 * t[..., None] - 1) * (right - middle))
    else:
        rgb = np.array([245, 244, 235.]) + t[..., None] * np.array([-211, -165, -80.])
    return Image.fromarray(np.uint8(np.clip(rgb, 0, 255)), 'RGB')


def font(size):
    for path in ('C:/Windows/Fonts/msyh.ttc', 'C:/Windows/Fonts/simhei.ttf',
                 '/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf'):
        if Path(path).is_file():
            return ImageFont.truetype(path, size)
    return ImageFont.load_default()


def save_verified(image, path):
    expected = np.asarray(image.convert('RGB'))
    with path.open('xb') as handle:
        image.save(handle, format='PNG')
    with Image.open(path) as loaded:
        actual = np.asarray(loaded.convert('RGB'))
        need(np.array_equal(expected, actual), path.name + ': PNG readback mismatch')
    return {'size': list(image.size), 'sha256': hashlib.sha256(path.read_bytes()).hexdigest(),
            'rgbPixelReadback': True}


def grid_overlay(image):
    # Diagnostic overlay only: nearest native pixel to world x/z = k*H, not resampling.
    out = image.copy()
    draw = ImageDraw.Draw(out)
    for axis in (0, 1):
        start = math.ceil(ORIGIN[axis] / H)
        end = math.floor((ORIGIN[axis] + (SIZE - 1) * STEP) / H)
        for k in range(start, end + 1):
            pixel = int(round((k * H - ORIGIN[axis]) / STEP))
            line = (pixel, 0, pixel, SIZE - 1) if axis == 0 else (0, pixel, SIZE - 1, pixel)
            draw.line(line, fill=(25, 25, 25))
    return out


def map_panel(images, channel, caption, dest, name):
    pad, top, gap = 24, 145, 20
    width = pad * 2 + len(images) * SIZE + (len(images) - 1) * gap
    canvas = Image.new('RGB', (width, top + SIZE + 40), BG)
    draw = ImageDraw.Draw(canvas)
    draw.text((pad, 12), channel + ' | ' + caption[0], font=font(20), fill=INK)
    for i, line in enumerate(caption[1:]):
        draw.text((pad, 40 + i * 23), line, font=font(15), fill=INK)
    for i, (label, image) in enumerate(images):
        x = pad + i * (SIZE + gap)
        draw.text((x, top - 22), label, font=font(15), fill=INK)
        canvas.paste(image, (x, top))
    return save_verified(canvas, dest / name)


def render_channel(channel, a, b, dest):
    lo, hi = limits(channel, a, b)
    d = b - a
    v = max(float(np.max(np.abs(d))), 1e-30)
    ga, sa = gradient(a); gb, sb = gradient(b)
    ha, hb = highpass(a), highpass(b)
    gv = max(float(ga.max()), float(gb.max()), 1e-30)
    hv = max(float(np.max(np.abs(ha))), float(np.max(np.abs(hb))), 1e-30)
    base = ['seed=%s | 701² step=10 span=7000; S3 same world window, different resolution' % SEED,
            'origin=(-3642.5,-3365.5); +x right, +z down; raw 1:1; no HeightCurve / terrain',
            'micro=600 macro=1200 band=240 threshold=.30; no cache; Java metadata is declaration']
    figures = {}
    jobs = [('grid', [('B + fixed-world B-spline grid', grid_overlay(colorize(b, lo, hi, lo < 0)))],
             'diagnostic overlay only: grid origin (0,0), spacing %.9g blocks; nearest pixel' % H),
            ('pair', [('A before', colorize(a, lo, hi, lo < 0)), ('B after', colorize(b, lo, hi, lo < 0))],
             'A/B shared range [%.7g, %.7g]' % (lo, hi)),
            ('delta', [('B - A', colorize(d, -v, v, True))], 'B-A symmetric +/-%.7g' % v),
            ('gradient', [('A |grad|', colorize(ga, 0, gv)), ('B |grad|', colorize(gb, 0, gv))],
             '|grad| per block; shared [0, %.7g]; finite differences' % gv),
            ('highpass', [('A highpass', colorize(ha, -hv, hv, True)), ('B highpass', colorize(hb, -hv, hv, True))],
             'input minus edge-extended 21x21 box (210 blocks); shared +/-%.7g' % hv)]
    for suffix, images, note in jobs:
        name = '%s-%s.png' % (channel, suffix)
        figures[name] = map_panel(images, channel, [note] + base, dest, name)
    return figures, {'range': [lo, hi], 'deltaLimit': v, 'gradientLimit': gv, 'highpassLimit': hv,
                     'comparison': pair_stats(a, b), 'gradient': {'A': sa, 'B': sb},
                     'highpass': {'A': summary(ha), 'B': summary(hb)}}


def draw_profile(draw, a, name, index, channel_index, ylim, top, meta):
    left, width, height = 160, 1350, 140
    lo, hi = ylim
    draw.rectangle((left, top, left + width, top + height), outline='#9a9a90')
    x = a[:, 0]
    for col, color in ((3 + 2 * channel_index, '#2357a0'), (4 + 2 * channel_index, '#b04e2e')):
        points = [(left + (xx - x[0]) / (x[-1] - x[0]) * width,
                   top + height - (y - lo) / (hi - lo) * height) for xx, y in zip(x, a[:, col])]
        draw.line(points, fill=color, width=1)
    desc = '%s | seed=%s | z=%.9f | x=[%.6f, %.6f] | n=%d' % (
        name, meta['seed'], a[0, 1], x[0], x[-1], len(x))
    draw.text((20, top - 22), desc, font=font(14), fill=INK)
    draw.text((4, top), '%.5g' % hi, font=font(13), fill=INK)
    draw.text((4, top + height - 14), '%.5g' % lo, font=font(13), fill=INK)
    draw.text((left, top + height + 3), 'x=%.4f' % x[0], font=font(12), fill=INK)
    draw.text((left + width - 135, top + height + 3), 'x=%.4f' % x[-1], font=font(12), fill=INK)


def render_profiles(groups, pmeta, dest):
    figures, scales = {}, {}
    for index, channel in enumerate(CHANNELS):
        values = np.concatenate([a[:, 3 + 2 * index:5 + 2 * index].ravel() for a in groups.values()])
        lo, hi = float(values.min()), float(values.max())
        margin = max((hi - lo) * .05, 1e-12)
        ylim = (lo - margin, hi + margin)
        scales[channel] = list(ylim)
        for kind in ('full', 'focus'):
            names = [n for n in groups if n.startswith(kind + '-')]
            canvas = Image.new('RGB', (1540, 110 + len(names) * 195), BG)
            draw = ImageDraw.Draw(canvas)
            draw.text((20, 8), '%s %s | A blue, B brown | common Y [%.6g, %.6g]' %
                      (channel, kind, *ylim), font=font(18), fill=INK)
            draw.text((20, 36), 'all Java samples; x increases right; offset=e units, NOT final e or Y', font=font(14), fill=INK)
            draw.text((20, 57), 'focus-window seed=-419; focus-bisector seed=12345: different from main seed', font=font(14), fill=INK)
            for i, name in enumerate(names):
                draw_profile(draw, groups[name], name, i, index, ylim, 100 + i * 195, pmeta[name])
            name = 'profiles-%s-%s.png' % (channel, kind)
            figures[name] = save_verified(canvas, dest / name)
    return figures, scales


def check_paths(root, dest):
    need(root.is_dir(), 'input root missing')
    need(not dest.exists() and not dest.is_symlink(), 'output exists; refusing overwrite')
    need(dest != root and root not in dest.parents and dest not in root.parents,
         'output must be separate from input and ancestors')


def function_lengths():
    tree = ast.parse(Path(__file__).read_text(encoding='utf-8'), feature_version=8)
    lengths = {n.name: n.end_lineno - n.lineno + 1 for n in ast.walk(tree) if isinstance(n, ast.FunctionDef)}
    need(max(lengths.values()) <= 80, 'function exceeds 80 lines')
    return lengths


def load_inputs(root):
    manifest = {}
    meta = check_meta(parse_json(read_input(root, 'meta.json', manifest)))
    pmeta = parse_json(read_input(root, 'profiles-meta.json', manifest))
    specs = check_profile_meta(pmeta)
    profiles = read_profiles(read_input(root, 'profiles.csv', manifest), specs)
    cost_raw = read_input(root, 'cost.json', manifest).decode('utf-8-sig')
    cost = parse_json(cost_raw.encode('utf-8'))
    need(isinstance(cost, dict) and cost.get('cache') == 'none' and cost.get('notFPS') is True,
         'cost must be uncached and not FPS')
    names = ('oldDriver', 'candidateDriver', 'oldConsumers', 'candidateConsumers', 'pairedAdapter')
    need(all(all(k in cost.get('results', {}).get(n, {}) for k in ('mean', 'p50', 'p95', 'max'))
             for n in names), 'cost results incomplete')
    data = {arm: {c: read_grid(read_input(root, arm + '/' + c + '.f64be', manifest), c)
                  for c in CHANNELS} for arm in ARMS}
    return meta, pmeta, profiles, cost_raw, data, manifest


def recheck(root, manifest):
    for name, item in manifest.items():
        raw = (root / name).read_bytes()
        need(len(raw) == item['bytes'] and hashlib.sha256(raw).hexdigest() == item['sha256'],
             'input changed during render: ' + name)


def render(root, dest):
    check_paths(root, dest)
    lengths = function_lengths()
    meta, pmeta, profiles, cost, data, manifest = load_inputs(root)
    dest.mkdir(parents=True, exist_ok=False)
    figures, channels = {}, {}
    for channel in CHANNELS:
        fig, stats = render_channel(channel, data[ARMS[0]][channel], data[ARMS[1]][channel], dest)
        figures.update(fig); channels[channel] = stats
    fig, scales = render_profiles(profiles, pmeta, dest)
    figures.update(fig)
    recheck(root, manifest)
    payload = {'meta': meta, 'profileMeta': pmeta, 'inputs': manifest, 'channels': channels,
               'costJsonVerbatim': cost, 'profileYRanges': scales, 'figures': figures,
               'functionLengths': lengths, 'sampleOrder': 'row-major z then x; +x right +z down',
               'bsplineGrid': {'spacing': H, 'origin': [0, 0],
                               'note': 'fixed world grid; inspect periodic grid risk, no PASS asserted'},
               'limitations': ['No HeightCurve, terrain, production parity or visual quality assertion.',
                               'Gradient is sampled central difference; gx/gz energy does not guarantee geological isotropy.',
                               'Same world window as S3, not the same raster resolution.',
                               'Java benchmark is not FPS; raw cost JSON preserved verbatim.']}
    with (dest / 'metrics.json').open('x', encoding='utf-8') as handle:
        json.dump(payload, handle, ensure_ascii=False, indent=2, allow_nan=False)
        handle.write('\n')
    outputs = {name: info['sha256'] for name, info in figures.items()}
    outputs['metrics.json'] = hashlib.sha256((dest / 'metrics.json').read_bytes()).hexdigest()
    with (dest / 'SHA256SUMS.txt').open('x', encoding='utf-8') as handle:
        handle.writelines('%s  %s\n' % (outputs[name], name) for name in sorted(outputs))
    print('Wrote %d verified PNGs, metrics.json and SHA256SUMS.txt' % len(figures))
    return 0


def expect_failure(label, action):
    try:
        action()
    except (ValueError, TypeError, KeyError, OSError):
        return label
    raise AssertionError('negative control accepted: ' + label)


def self_test():
    lengths = function_lengths()
    a = np.array([[0., 1.], [-1., .5]])
    raw = a.astype('>f8').tobytes()
    need(np.array_equal(read_grid(raw, 'stress', 2), a), 'big endian read failed')
    controls = [expect_failure('truncated', lambda: read_grid(raw[:-1], 'stress', 2)),
                expect_failure('stress range', lambda: read_grid(np.array([2.] * 4, dtype='>f8').tobytes(), 'stress', 2)),
                expect_failure('existing output', lambda: check_paths(Path.cwd(), Path.cwd())),
                expect_failure('output inside input', lambda: check_paths(Path.cwd(), Path.cwd() / '__gg_no_output_test__')),
                expect_failure('NaN', lambda: read_grid(np.array([np.nan] * 4, dtype='>f8').tobytes(), 'stress', 2)),
                expect_failure('gate range', lambda: read_grid(np.array([2.] * 4, dtype='>f8').tobytes(), 'envelope110', 2)),
                expect_failure('bad metadata', lambda: check_meta({'size': 701})),
                expect_failure('bad profile metadata', lambda: check_profile_meta({})),
                expect_failure('bad CSV schema', lambda: read_profiles(b'line,x\n', profile_specs()))]
    need(pair_stats(a, -a)['signFlipPixels'] == 3, 'sign flip control failed')
    need(np.max(np.abs(highpass(np.ones((30, 30))))) < 1e-12, 'highpass constant failed')
    img = colorize(a, -1, 1, True)
    stream = io.BytesIO(); img.save(stream, format='PNG'); stream.seek(0)
    need(np.array_equal(np.asarray(img), np.asarray(Image.open(stream).convert('RGB'))), 'PNG memory readback')
    print(json.dumps({'selfTest': 'PASS', 'negativeControls': controls, 'functionLengths': lengths,
                      'fixtures': 'in-memory only; no output files'}, indent=2))
    return 0


def main(argv):
    try:
        if argv == ['--self-test']:
            return self_test()
        if len(argv) != 2:
            print('usage: python -B tools/render-tectonic-propagation.py ROOT NEW_OUT | --self-test', file=sys.stderr)
            return 2
        root = Path(argv[0]).resolve()
        supplied = Path(argv[1]).absolute()
        dest = supplied.parent.resolve() / supplied.name
        return render(root, dest)
    except Exception as exc:
        print('FAIL %s: %s' % (type(exc).__name__, exc), file=sys.stderr)
        return 1


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
