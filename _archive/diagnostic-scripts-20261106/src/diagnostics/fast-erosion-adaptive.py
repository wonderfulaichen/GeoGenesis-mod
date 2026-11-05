"""Isolated mountain type-space comparison; NOT production world generation.

From the MDK directory:
    python -B src/diagnostics/fast-erosion-adaptive.py build/<NEWdir>
    python -B src/diagnostics/fast-erosion-adaptive.py --render-only build/<NEWdir>

Only a new, direct child of build/ may be sampled. Render-only accepts this
orchestrator's frozen run, never executes Java, and may refresh derived figures,
profiles and statistics, but not sampled data, execution logs or old baselines.
"""

import argparse
import ast
import csv
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import time
import traceback
from datetime import datetime, timezone

import numpy as np
from scipy import ndimage as ndi
import matplotlib

matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.cm import ScalarMappable
from matplotlib.colors import ListedColormap, Normalize


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = Path(__file__).resolve().relative_to(ROOT).as_posix()
KIND = 'adaptive-skeleton-type-space-v1'
STANDALONE = 'src/diagnostics/java/com/geogenesis/standalone/'
MORPH = 'src/diagnostics/java/com/geogenesis/worldgen/terrain/morph/'
JAVA_SOURCES = [STANDALONE + 'AdaptiveSkeletonErosion.java',
                STANDALONE + 'AdaptiveSkeletonCheck.java',
                MORPH + 'AdaptiveSkeletonMountainProbe.java']
OLD_DATA = Path('build/fast-erosion-corrected-20261103-final/data')
OLD_RAW = OLD_DATA / 'common-production-raw.f64be'
APPROVED = Path('build/fast-erosion-height-20261103/run/height.png')
PALETTE = Path('build/fast-erosion-scale-only-20261103-final/data/project-palette.csv')
DETECTOR = Path('src/diagnostics/fast-erosion-derivatives.py')
CORRECTED = Path('src/diagnostics/fast-erosion-corrected.py')
RAW_SHA = 'c40405a8bec5b38187752da27090e62873a32e5314505d741a9420d458d7e3f4'
APPROVED_SHA = '05339759d3cd631f8d11df700d5ef0f680680010d39efcd4bcdebf7a34e49ad2'
THRESHOLD = 0.06684482083366819
NATIVE_STEP = 1.1914062488904165
WINDOW_ORIGIN = [-4158.0078086275535, -4279.531246014376]
ARMS = ('carrier', 'original', 'adaptive')
ARM_TITLES = ('Base / carrier: complete raw, E=0',
              'Original: Fast Erosion, complete input',
              'Adaptive: fixed-center kernels, complete input')
FIELDS = ('base-raw', 'carrier-height', 'original-height', 'adaptive-height',
          'original-unitE', 'adaptive-unitE', 'original-mask',
          'adaptive-activity', 'adaptive-confidence', 'adaptive-denominator')
REPORTS = ('calibration.json', 'checks.json', 'performance.json', 'refinement.json')
LONG_CROPS = [('Oblique seam crop', (0, 750, 1850, 2150)),
              ('Vertical seam crop', (780, 1150, 1320, 1850))]
STAR_CROPS = [('Star crop 1', (1700, 2100, 1650, 2050)),
              ('Star crop 2', (200, 650, 1200, 1700))]
CORRIDORS = {'oblique': ((0, 2100), (700, 1900)),
             'vertical_x1000': ((1000, 1320), (1000, 1850))}
PROFILE_LINES = [('oblique_z2000', 'z', 2000),
                 ('vertical_x1000', 'x', 1000), ('star_z1855', 'z', 1855)]
E_LIMITS = (-0.35, 1.2)
Y_FACTOR = 236.44 * 1.5
LIGHT = np.array([-0.5, -0.3, 1.0])
LIGHT /= np.linalg.norm(LIGHT)
FIGURE_SCOPE = 'Mountain type-space | NOT PRODUCTION | all arms k=1'
FIGURE_FOOTER = ('HS=2; local blocks=2 wu; Yproxy=236.44*1.5*H; '
                  'e=.02+1.5H, fixed e[-.35,1.2]; light[-.5,-.3,1].\n'
                  'Shade=.65+.35*positiveDot; no per-arm normalization. '
                  'Common carrier, not identical final heights.')
CALIBRATION_POLICY = {
    'V': 'std of complete raw at 8192 fixed independent-domain samples',
    't_on': 'per-layer median Jtrace at 1024 independent calibration centers',
    'epsilon': '.001*t_on', 'chi': 0.25,
    'rendererFitsParameters': False, 'amplitude': 0.25, 'kAllArms': 1,
}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def utc():
    return datetime.now(timezone.utc).isoformat()


def sha(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1 << 20), b''):
            digest.update(block)
    return digest.hexdigest()


def reject_json_constant(value):
    raise ValueError('Non-finite JSON constant: ' + value)


def load_json(path):
    return json.loads(path.read_text(encoding='utf-8-sig'),
                      parse_constant=reject_json_constant)


def save_json(path, value):
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False,
                               allow_nan=False) + '\n', encoding='utf-8')


def file_hashes(paths, relative_to):
    return {p.relative_to(relative_to).as_posix(): sha(p)
            for p in sorted(set(paths))}


def tree_hashes(folder, relative_to):
    require(folder.is_dir(), 'Missing directory: ' + str(folder))
    return file_hashes((p for p in folder.rglob('*') if p.is_file()), relative_to)


def locked_snapshot():
    main = ROOT / 'src/main'
    require(main.is_dir(), 'Missing src/main')
    require((ROOT / OLD_DATA).is_dir(), 'Missing historical corrected data')
    paths = [p for folder in (main, ROOT / OLD_DATA)
             for p in folder.rglob('*') if p.is_file()]
    paths += [ROOT / (STANDALONE + name) for name in
              ('FastErosionFilter.java', 'FastErosionOriginalDemo.java')]
    paths += [ROOT / APPROVED, ROOT / PALETTE, ROOT / DETECTOR, ROOT / CORRECTED]
    snapshot = file_hashes(paths, ROOT)
    require(snapshot[OLD_RAW.as_posix()] == RAW_SHA, 'Historical raw SHA drift')
    require(snapshot[APPROVED.as_posix()] == APPROVED_SHA, 'Approved height SHA drift')
    return snapshot


def source_snapshot(locked):
    result = {key: value for key, value in locked.items() if key.startswith('src/')}
    result.update(file_hashes([ROOT / path for path in JAVA_SOURCES + [SCRIPT]], ROOT))
    return result


def same_hashes(expected, actual, label):
    changed = [key for key in sorted(expected.keys() | actual.keys())
               if expected.get(key) != actual.get(key)]
    require(not changed, label + ' changed: ' + ', '.join(changed[:20]))


def output_path(argument, render_only):
    out = (ROOT / argument).resolve()
    require(out.parent == (ROOT / 'build').resolve(),
            'OUT must be a direct child of the MDK build directory')
    if render_only:
        require(out.is_dir(), '--render-only requires an existing adaptive run')
    else:
        require(not out.exists(), 'NEW build directory required; refusing existing OUT')
    return out


def echo_bytes(block):
    if hasattr(sys.stdout, 'buffer'):
        sys.stdout.buffer.write(block)
        sys.stdout.buffer.flush()
    else:
        sys.stdout.write(block.decode('utf-8', errors='replace'))
        sys.stdout.flush()


def run_command(out, commands, stage, args):
    relative_log = 'logs/%02d-%s.log' % (len(commands) + 1, stage)
    record = {'stage': stage, 'argv': args, 'cwd': str(ROOT), 'startedUtc': utc(),
              'exitCode': None, 'log': relative_log, 'status': 'running'}
    commands.append(record)
    save_json(out / 'commands.json', commands)
    started = time.perf_counter()
    log = out / relative_log
    log.parent.mkdir(exist_ok=True)
    error = None
    with log.open('wb') as raw, (out / 'validation.log').open('ab') as combined:
        header = ('\nCOMMAND ' + subprocess.list2cmdline(args) + '\n').encode('utf-8')
        combined.write(header)
        combined.flush()
        echo_bytes(header)
        try:
            with subprocess.Popen(args, cwd=ROOT, stdout=subprocess.PIPE,
                                  stderr=subprocess.STDOUT) as process:
                for block in iter(lambda: process.stdout.read1(65536), b''):
                    raw.write(block)
                    raw.flush()
                    combined.write(block)
                    combined.flush()
                    echo_bytes(block)
                record['exitCode'] = process.wait()
        except Exception as exc:
            error = exc
            record['error'] = repr(exc)
        record.update(seconds=time.perf_counter() - started, finishedUtc=utc())
        record['status'] = 'ok' if error is None and record['exitCode'] == 0 else 'failed'
        footer = ('\nexit=%s seconds=%.6f status=%s\n' %
                  (record['exitCode'], record['seconds'], record['status'])).encode('utf-8')
        combined.write(footer)
        echo_bytes(footer)
    record['logSha256'] = sha(log)
    save_json(out / 'commands.json', commands)
    if error is not None:
        raise RuntimeError('Could not execute ' + stage) from error
    require(record['exitCode'] == 0, '%s failed: exit %s; see %s' %
            (stage, record['exitCode'], log))


def sample(out, commands):
    classes = out / 'classes'
    classes.mkdir()
    run_command(out, commands, 'javac',
                ['javac', '--release', '17', '-encoding', 'UTF-8', '-proc:none',
                 '-d', str(classes), '-sourcepath', 'src/main/java;src/diagnostics/java',
                 *JAVA_SOURCES])
    run_command(out, commands, 'check',
                ['java', '-cp', str(classes), 'com.geogenesis.standalone.AdaptiveSkeletonCheck'])
    run_command(out, commands, 'probe',
                ['java', '-Xmx3g', '-cp', str(classes),
                 'com.geogenesis.worldgen.terrain.morph.AdaptiveSkeletonMountainProbe',
                 str(out / 'data')])


def execution_snapshot(out):
    names = ('commands.json', 'validation.log', 'locked-before.json',
             'source-lock.json', 'data-lock.json')
    result = file_hashes([out / name for name in names], out)
    for folder in ('classes', 'logs'):
        result.update(tree_hashes(out / folder, out))
    return result


def prepare(out, render_only):
    if not render_only:
        locked = locked_snapshot()
        sources = source_snapshot(locked)
        out.mkdir(parents=True)
        save_json(out / 'locked-before.json', locked)
        save_json(out / 'source-lock.json', sources)
        save_json(out / 'commands.json', [])
        return {'locked': locked, 'sourcesAtSampling': sources, 'activeSources': sources,
                'commands': [], 'data': None, 'record': None, 'renderOnly': False}
    record = load_json(out / 'run-record.json')
    require(record.get('kind') == KIND and record.get('state') == 'data-ready',
            'Not a completed adaptive sampling run; refusing render-only')
    same_hashes(record['immutableOutputs'], execution_snapshot(out), 'Execution artifacts')
    locked = load_json(out / 'locked-before.json')
    current_locked = locked_snapshot()
    same_hashes(locked, current_locked, 'Locked sources and old baselines')
    sampled = load_json(out / 'source-lock.json')
    current = source_snapshot(current_locked)
    # A renderer layout edit is allowed, but Java, main and legacy code stay locked.
    same_hashes({k: v for k, v in sampled.items() if k != SCRIPT},
                {k: v for k, v in current.items() if k != SCRIPT}, 'Sampling sources')
    data = load_json(out / 'data-lock.json')
    same_hashes(data, tree_hashes(out / 'data', out), 'Sampled data')
    commands = load_json(out / 'commands.json')
    require([c['stage'] for c in commands] == ['javac', 'check', 'probe'] and
            all(c['exitCode'] == 0 for c in commands), 'Sampling commands did not all pass')
    return {'locked': locked, 'sourcesAtSampling': sampled, 'activeSources': current,
            'commands': commands, 'data': data, 'record': record, 'renderOnly': True}


def verify_inputs(out, context, write_after=False):
    locked = locked_snapshot()
    if write_after:
        save_json(out / 'locked-after.json', locked)
    same_hashes(context['locked'], locked, 'Locked sources and old baselines')
    sources = source_snapshot(locked)
    same_hashes(context['activeSources'], sources, 'Active sources')
    if context['data'] is not None:
        same_hashes(context['data'], tree_hashes(out / 'data', out), 'Sampled data')
    if context['record'] is not None:
        same_hashes(context['record']['immutableOutputs'], execution_snapshot(out),
                    'Execution artifacts')
    return sources


def freeze_sampling(out, context):
    verify_inputs(out, context)
    context['data'] = tree_hashes(out / 'data', out)
    require(bool(context['data']), 'Probe wrote no data')
    save_json(out / 'data-lock.json', context['data'])
    record = {'kind': KIND, 'state': 'data-ready', 'createdUtc': utc(),
              'immutableOutputs': execution_snapshot(out),
              'jvmEnvironment': {key: os.environ.get(key) for key in
                                 ('JAVA_HOME', 'JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS',
                                  'JDK_JAVA_OPTIONS', 'CLASSPATH')}}
    save_json(out / 'run-record.json', record)
    context['record'] = record


def read_array(path, size):
    require(path.stat().st_size == size * size * 8,
            'Expected exactly N*N big-endian float64 values: ' + str(path))
    array = np.fromfile(path, dtype='>f8').astype(np.float64).reshape(size, size)
    require(np.isfinite(array).all(), 'Non-finite array: ' + str(path))
    return array


def validate_sampling(meta):
    require(meta['size'] == 769, 'Expected the frozen 769-square window')
    for key, expected in (('HS', 2), ('amplitude', 0.25), ('k', 1)):
        require(meta[key] == expected, 'Unexpected ' + key)
    require(np.isclose(meta['shellAmplitude'], 0.7, rtol=0, atol=2e-8),
            'Mountain shell must remain .7 (including the original float representation)')
    for key, expected in (('stepWu', NATIVE_STEP * 2), ('nativeStepWu', NATIVE_STEP),
                          ('spanWu', NATIVE_STEP * 2 * 768)):
        require(np.isclose(meta[key], expected, rtol=0, atol=1e-10),
                'Frozen sampling drift: ' + key)
    origin = np.asarray(meta['originWu'], dtype=float)
    require(origin.shape == (2,) and np.allclose(origin, WINDOW_ORIGIN, rtol=0, atol=1e-10),
            'Frozen window origin drift')
    waves = np.asarray(meta['wavelengthsWu'], dtype=float)
    require(waves.shape == (4,) and np.isfinite(waves).all() and
            (waves > 0).all() and (np.diff(waves) < 0).all(), 'Invalid four-layer wavelengths')
    if 'oldNativeStepWu' in meta:
        require(np.isclose(meta['oldNativeStepWu'], NATIVE_STEP, rtol=0, atol=1e-12),
                'Old erosion native step changed')


def verify_composition(arrays, meta, data):
    actual_sha = sha(data / 'base-raw.f64be')
    require(actual_sha == RAW_SHA == sha(ROOT / OLD_RAW),
            'base-raw must reproduce old common-production-raw bit-for-bit')
    base = arrays['base-raw']
    results = {}
    for arm in ARMS:
        unit = np.zeros_like(base) if arm == 'carrier' else arrays[arm + '-unitE']
        expected = (base + meta['amplitude'] * unit) * meta['shellAmplitude']
        actual = arrays[arm + '-height']
        residual = float(np.max(np.abs(actual - expected)))
        tolerance = float(8 * np.finfo(float).eps * max(1.0, np.max(np.abs(expected))))
        require(residual <= tolerance, 'H=(base+.25E)*shell, k=1 failed: ' + arm)
        results[arm] = {'k': 1, 'maxAbsCompositionResidual': residual,
                        'absoluteTolerance': tolerance}
    for name in ('original-mask', 'adaptive-activity', 'adaptive-confidence'):
        value = arrays[name]
        require(value.min() >= -1e-12 and value.max() <= 1 + 1e-12,
                'Expected [0,1] quantity: ' + name)
    require((arrays['adaptive-denominator'] > 0).all(), 'Non-positive geometric denominator')
    require(np.max(np.abs(arrays['adaptive-unitE'])) <= 1 + 1e-12, 'Adaptive |E| exceeds 1')
    return {'rawSha256': actual_sha, 'historicalRawSha256': RAW_SHA,
            'rawBitIdentity': True, 'formula': 'H=(completeRaw+.25*unitE)*shell; k=1',
            'shellAmplitudeUsed': meta['shellAmplitude'], 'arms': results}


def refinement_data(data, arrays, meta, refinement):
    require(refinement['size'] == 129, 'Expected a 129-square refinement')
    require(np.isclose(refinement['stepWu'], meta['nativeStepWu'], rtol=0, atol=1e-12),
            'Refinement must sample at the unchanged nativeStepWu')
    require(refinement['coarseVertexDifferentBits'] == 0, 'Probe reports refinement drift')
    indices = np.asarray(refinement['mainGridIndices'], dtype=float)
    require(indices.shape == (2,) and np.isfinite(indices).all() and
            (indices == np.floor(indices)).all() and (indices >= 0).all(),
            'Invalid refinement [ix0,iz0] indices')
    ix0, iz0 = map(int, indices)
    count = (refinement['size'] + 1) // 2
    require(ix0 + count <= meta['size'] and iz0 + count <= meta['size'],
            'Refinement anchors outside main window')
    origin = np.asarray(refinement['originLocalBlocks'], dtype=float)
    require(origin.shape == (2,) and np.isfinite(origin).all() and
            np.allclose(origin, indices * meta['stepWu'] * meta['HS'], rtol=0, atol=1e-8),
            'Refinement origin must coincide with its main-grid anchor')
    fine_span = (refinement['size'] - 1) * refinement['stepWu'] * meta['HS']
    require((origin <= [1900, 1855]).all() and
            (origin + fine_span >= [1900, 1855]).all(), 'Fine window misses star (1900,1855)')
    if 'oldNativeStepWu' in refinement:
        require(np.isclose(refinement['oldNativeStepWu'], NATIVE_STEP, rtol=0, atol=1e-12),
                'Refinement changed oldNativeStepWu')
    fine, checks = {}, {}
    for arm in ARMS:
        fine[arm] = read_array(data / ('refinement-' + arm + '-height.f64be'), 129)
        anchors = np.ascontiguousarray(fine[arm][::2, ::2])
        main = np.ascontiguousarray(arrays[arm + '-height'][iz0:iz0 + count, ix0:ix0 + count])
        different = int(np.count_nonzero(anchors.view(np.uint64) != main.view(np.uint64)))
        checks[arm] = {'comparedVertices': int(main.size), 'differentBits': different,
                       'maxAbsDifference': float(np.max(np.abs(anchors - main)))}
        require(different == 0, 'Refinement main-grid anchors differ: ' + arm)
    return fine, {'arms': checks, 'coarseVertexDifferentBits': 0,
                  'oldNativeStepWu': meta['nativeStepWu'],
                  'note': 'Every other fine vertex is bit-identical; renderer changes no algorithm parameters.'}


def load_dataset(out):
    data = out / 'data'
    meta = load_json(data / 'sampling.json')
    validate_sampling(meta)
    arrays = {name: read_array(data / (name + '.f64be'), meta['size']) for name in FIELDS}
    reports = {name: load_json(data / name) for name in REPORTS}
    composition = verify_composition(arrays, meta, data)
    fine, refined = refinement_data(data, arrays, meta, reports['refinement.json'])
    return {'sampling': meta, 'arrays': arrays, 'reports': reports, 'fine': fine,
            'verification': {'composition': composition, 'refinement': refined}}


def detector_functions():
    # Compile ONLY these two function ASTs. Never import/run legacy module globals.
    tree = ast.parse((ROOT / DETECTOR).read_text(encoding='utf-8-sig'))
    names = {'crack_score', 'detector'}
    nodes = [node for node in tree.body if isinstance(node, ast.FunctionDef)
             and node.name in names]
    require(len(nodes) == 2 and {node.name for node in nodes} == names,
            'Missing frozen detector functions')
    require(all(not node.decorator_list and not node.args.defaults and
                not node.args.kw_defaults for node in nodes), 'Unexpected detector definition')
    scope = {'np': np, 'ndi': ndi,
             '__builtins__': {'abs': abs, 'range': range, 'round': round, 'int': int,
                              'float': float, '__import__': __import__}}
    module = ast.Module(body=nodes, type_ignores=[])
    exec(compile(module, str(ROOT / DETECTOR), 'exec'), scope)
    return {name: scope[name] for name in names}


def distribution(values):
    values = np.asarray(values)
    levels = [0, .01, .05, .5, .9, .95, .99, .999, 1]
    names = ['min', 'p01', 'p05', 'p50', 'p90', 'p95', 'p99', 'p999', 'max']
    result = dict(zip(names, map(float, np.quantile(values, levels))))
    result.update(mean=float(values.mean()), std=float(values.std()),
                  rms=float(np.sqrt(np.mean(values * values))))
    return result


def bounded_distribution(values):
    result = distribution(values)
    edges = np.array([0, 1e-6, .01, .1, .25, .5, .75, .9, .99, 1.000000000002])
    result.update(zeroFraction=float((values == 0).mean()),
                  nearZeroLe1eMinus6Fraction=float((values <= 1e-6).mean()),
                  fullGe1Fraction=float((values >= 1).mean()),
                  nearOneGe0_99Fraction=float((values >= .99).mean()),
                  binEdges=edges.tolist(), binCounts=np.histogram(values, edges)[0].tolist())
    return result


def gradient_summary(values, step):
    gz, gx = np.gradient(values, step)
    magnitude = np.hypot(gx, gz)[1:-1, 1:-1]
    result = distribution(magnitude)
    result['gradientRms'] = float(np.sqrt(np.mean(magnitude * magnitude)))
    result['tailP999overP50'] = result['p999'] / result['p50'] if result['p50'] > 0 else None
    return result


def corridor_mask(shape, step_blocks, segment, half_width_pixels=2):
    x = np.arange(shape[1], dtype=float)[None, :] * step_blocks
    z = np.arange(shape[0], dtype=float)[:, None] * step_blocks
    (x0, z0), (x1, z1) = segment
    dx, dz = x1 - x0, z1 - z0
    t = np.clip(((x - x0) * dx + (z - z0) * dz) / (dx * dx + dz * dz), 0, 1)
    return np.hypot(x - x0 - t * dx, z - z0 - t * dz) <= half_width_pixels * step_blocks


def corridor_counts(candidates, band):
    labels, count = ndi.label(ndi.maximum_filter(candidates, size=3) & band)
    objects = ndi.find_objects(labels)
    spans = [max(sl[0].stop - sl[0].start, sl[1].stop - sl[1].start)
             for sl in objects if sl is not None]
    return {'candidatePixels': int((candidates & band).sum()), 'corridorPixels': int(band.sum()),
            'dilatedComponentsInBand': int(count),
            'maxDilatedComponentBboxSpanOutputPixels': int(max(spans, default=0))}


def crop_slice(shape, step_blocks, origin, bounds):
    x0, x1, z0, z1 = bounds
    ox, oz = origin
    span_x, span_z = (shape[1] - 1) * step_blocks, (shape[0] - 1) * step_blocks
    require(ox <= x0 < x1 <= ox + span_x + 1e-8 and
            oz <= z0 < z1 <= oz + span_z + 1e-8, 'Crop outside sampled domain')
    ix0 = max(0, int(np.floor((x0 - ox) / step_blocks)))
    ix1 = min(shape[1], int(np.ceil((x1 - ox) / step_blocks)) + 1)
    iz0 = max(0, int(np.floor((z0 - oz) / step_blocks)))
    iz1 = min(shape[0], int(np.ceil((z1 - oz) / step_blocks)) + 1)
    return slice(iz0, iz1), slice(ix0, ix1)


def statistics(out, dataset):
    meta, arrays = dataset['sampling'], dataset['arrays']
    funcs = detector_functions()
    step = meta['stepWu']
    blocks = step * meta['HS']
    carrier = arrays['carrier-height']
    bands = {name: corridor_mask(carrier.shape, blocks, segment)
             for name, segment in CORRIDORS.items()}
    regions = LONG_CROPS + STAR_CROPS
    arms = {}
    for arm in ARMS:
        height = arrays[arm + '-height']
        unit = np.zeros_like(height) if arm == 'carrier' else arrays[arm + '-unitE']
        candidates, score = funcs['detector'](height, THRESHOLD)
        np.save(out / (arm + '-candidates.npy'), candidates, allow_pickle=False)
        color = .02 + 1.5 * height
        delta = height - carrier
        arms[arm] = {
            'k': 1, 'height': distribution(height),
            'signedMeanDeltaVsCarrier': float(delta.mean()), 'deltaVsCarrier': distribution(delta),
            'slopeHperWu': gradient_summary(height, step),
            'unitE': distribution(unit), 'unitEgradientPerWu': gradient_summary(unit, step),
            'unitEpeakAbs': float(np.max(np.abs(unit))),
            'unitEabsGe1Minus1e12Fraction': float((np.abs(unit) >= 1 - 1e-12).mean()),
            'colorBelowLimitFraction': float((color < E_LIMITS[0]).mean()),
            'colorAboveLimitFraction': float((color > E_LIMITS[1]).mean()),
            'colorSaturatedFraction': float(((color < E_LIMITS[0]) | (color > E_LIMITS[1])).mean()),
            'candidatePixels': int(candidates.sum()),
            'candidatePixelsByCrop': {name: int(candidates[crop_slice(height.shape, blocks,
                                                  (0, 0), bounds)].sum()) for name, bounds in regions},
            'corridors': {name: corridor_counts(candidates, band) for name, band in bands.items()},
            'legacyHessianPerOutputPixelSquared': distribution(score[8:-8, 8:-8]),
            'physicalHessianHperWuSquared': distribution(score[8:-8, 8:-8] / step ** 2),
            'activity': None, 'confidence': None, 'geometricDenominator': None,
        }
    arms['original']['activity'] = bounded_distribution(arrays['original-mask'])
    arms['original']['activityMeaning'] = 'Exported legacy mask; not adaptive tensor activity'
    arms['adaptive']['activity'] = bounded_distribution(arrays['adaptive-activity'])
    arms['adaptive']['confidence'] = bounded_distribution(arrays['adaptive-confidence'])
    arms['adaptive']['geometricDenominator'] = distribution(arrays['adaptive-denominator'])
    report = {'arms': arms, 'threshold': THRESHOLD,
              'thresholdUnits': 'shell H per OUTPUT PIXEL squared, NOT per wu squared; never refitted',
              'physicalHessianDefinition': 'same stencil max abs eigenvalue / stepWu**2',
              'gradientDomain': 'np.gradient(value, stepWu); central interior, excludes 1 pixel border',
              'detector': 'Frozen AST crack_score + detector: 13px support, 3px corridor, 8 directions, 8px border excluded',
              'candidateCaveat': 'Operational proxy only; candidates may be natural ridges, not labelled seam truth',
              'corridorDefinition': {'segmentsLocalBlocks': CORRIDORS, 'halfWidthOutputPixels': 2,
                                     'spanMeaning': '3px-dilated component bbox span INSIDE corridor, not seam length'},
              'activityCaveat': 'm=t/(t+t_on) is naturally <1. A zero full-open rate is NOT shutdown; inspect E RMS/std/gradient and activity/confidence distributions.',
              'carrierMeaning': 'Forced E=0 negative control; activity and confidence are not applicable',
              'calibrationPolicy': CALIBRATION_POLICY}
    save_json(out / 'statistics.json', report)
    return report


def light(values, step_blocks):
    gz, gx = np.gradient(values * Y_FACTOR, step_blocks)
    dot = (-gx * LIGHT[0] - gz * LIGHT[1] + LIGHT[2]) / np.sqrt(1 + gx * gx + gz * gz)
    return .65 + .35 * np.maximum(0, dot)


def palette():
    colors = np.loadtxt(ROOT / PALETTE, delimiter=',', skiprows=1)
    require(colors.ndim == 2 and colors.shape[1] == 4 and np.isfinite(colors).all(),
            'Invalid locked project palette')
    require((colors[:, 1:] >= 0).all() and (colors[:, 1:] <= 255).all(), 'Palette RGB outside [0,255]')
    return ListedColormap(colors[:, 1:] / 255)


def image_panel(ax, image, step_blocks, origin, bounds, title, gray=False, limits=None):
    sl = crop_slice(image.shape[:2], step_blocks, origin, bounds)
    ox, oz = origin
    extent = (ox + (sl[1].start - .5) * step_blocks,
              ox + (sl[1].stop - .5) * step_blocks,
              oz + (sl[0].stop - .5) * step_blocks,
              oz + (sl[0].start - .5) * step_blocks)
    options = {'extent': extent, 'interpolation': 'nearest', 'origin': 'upper', 'aspect': 'equal'}
    if gray:
        options.update(cmap='gray', vmin=.65, vmax=1)
    elif limits is not None:
        options.update(cmap='RdBu_r', vmin=limits[0], vmax=limits[1])
    ax.imshow(image[sl], **options)
    ax.set(xlim=(bounds[0], bounds[1]), ylim=(bounds[3], bounds[2]), title=title,
           xlabel='local x (blocks)', ylabel='local z (blocks)')


def finish_figure(fig, out, filename, subtitle, footer=FIGURE_FOOTER, colorbar=None):
    fig.suptitle(FIGURE_SCOPE + '\n' + subtitle, fontsize=14)
    fig.text(.035, .018, footer, fontsize=10)
    right = .94 if colorbar is not None else .995
    fig.tight_layout(rect=(0, .075, right, .91))
    if colorbar is not None:
        mappable, label = colorbar
        fig.colorbar(mappable, cax=fig.add_axes([.955, .25, .012, .5]), label=label)
    fig.savefig(out / filename, dpi=160,
                metadata={'Description': FIGURE_SCOPE + '; ' + subtitle})
    plt.close(fig)


def render_grid(out, filename, images, step_blocks, origin, crops, gray, cmap,
                subtitle, footer=FIGURE_FOOTER):
    rows = len(crops)
    fig, axes = plt.subplots(rows, 3, figsize=(22, 8 if rows == 1 else 13), squeeze=False)
    for row, (name, bounds) in enumerate(crops):
        for column, (arm, title) in enumerate(zip(ARMS, ARM_TITLES)):
            image_panel(axes[row, column], images[arm], step_blocks, origin, bounds,
                        name + '\n' + title + ' | k=1', gray=gray)
    if gray:
        bar = (ScalarMappable(norm=Normalize(.65, 1), cmap='gray'), 'fixed light')
    else:
        bar = (ScalarMappable(norm=Normalize(*E_LIMITS), cmap=cmap), 'e=.02+1.5H (unshaded palette)')
    finish_figure(fig, out, filename, subtitle, footer, bar)


def render_heights(out, dataset, cmap, color_crops):
    meta, arrays = dataset['sampling'], dataset['arrays']
    step = meta['stepWu'] * meta['HS']
    span = (meta['size'] - 1) * step
    whole = [('Whole frozen window', (0, span, 0, span))]
    shades = {arm: light(arrays[arm + '-height'], step) for arm in ARMS}
    colors = {arm: cmap(Normalize(*E_LIMITS, clip=True)(.02 + 1.5 * arrays[arm + '-height']))[..., :3]
              * shades[arm][..., None] for arm in ARMS}
    subtitle = 'H=(complete ridge4 x scaler4 x warp raw + .25E)*.7; no arm rescaling'
    render_grid(out, 'comparison.png', colors, step, (0, 0), whole, False, cmap, subtitle)
    render_grid(out, 'hillshade.png', shades, step, (0, 0), whole, True, cmap, subtitle)
    for filename, crops in (('long-seams', LONG_CROPS), ('star-zoom', STAR_CROPS)):
        render_grid(out, filename + '.png', shades, step, (0, 0), crops, True, cmap, subtitle)
        if color_crops:
            render_grid(out, filename + '-color.png', colors, step, (0, 0), crops, False, cmap, subtitle)


def render_erosion(out, dataset):
    meta, arrays = dataset['sampling'], dataset['arrays']
    step = meta['stepWu'] * meta['HS']
    span = (meta['size'] - 1) * step
    bounds = (0, span, 0, span)
    fig, axes = plt.subplots(2, 2, figsize=(16, 14))
    for column, arm in enumerate(('original', 'adaptive')):
        unit = arrays[arm + '-unitE']
        image_panel(axes[0, column], unit, step, (0, 0), bounds,
                    arm + ': unscaled unit E | k=1', limits=(-1, 1))
        image_panel(axes[1, column], light(unit, step), step, (0, 0), bounds,
                    arm + ': unit E hillshade (NOT final H) | k=1', gray=True)
    footer = ('Top: unit E, common fixed [-1,1], no amplitude fitting. Bottom: pure shade of unscaled E, '
              'same Yproxy=236.44*1.5*E and light, fixed gray [.65,1].\n'
              'These are diagnostic erosion fields, NOT final heights. HS=2; no per-arm normalization.')
    bar = (ScalarMappable(norm=Normalize(-1, 1), cmap='RdBu_r'), 'unit E [-1,1] (top row)')
    finish_figure(fig, out, 'erosion-only.png', 'Unscaled E and its pure hillshade', footer, bar)


def profile_values(height, fixed_axis, fixed_blocks, step_blocks):
    coordinate = fixed_blocks / step_blocks
    low = int(np.floor(coordinate))
    high = min(low + 1, height.shape[0] - 1)
    require(0 <= low < height.shape[0], 'Profile outside window')
    weight = coordinate - low
    if fixed_axis == 'z':
        return (1 - weight) * height[low, :] + weight * height[high, :]
    return (1 - weight) * height[:, low] + weight * height[:, high]


def render_profiles(out, dataset):
    meta, arrays = dataset['sampling'], dataset['arrays']
    step = meta['stepWu'] * meta['HS']
    position = np.arange(meta['size']) * step
    fig, axes = plt.subplots(3, 1, figsize=(18, 13), sharex=True, sharey=True)
    colors = ('#555555', '#d95f02', '#1b9e77')
    rows = []
    for ax, (name, fixed_axis, fixed_blocks) in zip(axes, PROFILE_LINES):
        values = {arm: profile_values(arrays[arm + '-height'], fixed_axis, fixed_blocks, step)
                  for arm in ARMS}
        for arm, title, color in zip(ARMS, ARM_TITLES, colors):
            ax.plot(position, values[arm], label=title + ' | k=1', color=color, linewidth=1)
        varying_axis = 'x' if fixed_axis == 'z' else 'z'
        ax.set(title='%s | %s=%g local blocks; vary %s' % (name, fixed_axis, fixed_blocks, varying_axis),
               ylabel='shell height H', xlabel='local %s along profile (blocks)' % varying_axis)
        ax.grid(alpha=.25)
        ax.legend(loc='best', fontsize=9)
        for i, value in enumerate(position):
            x, z = (value, fixed_blocks) if fixed_axis == 'z' else (fixed_blocks, value)
            row = [name, varying_axis, fixed_axis, fixed_blocks, value, x, z]
            row += [values[arm][i] for arm in ARMS]
            row += [values['original'][i] - values['carrier'][i],
                    values['adaptive'][i] - values['carrier'][i]]
            rows.append(row)
    low = min(float(arrays[arm + '-height'].min()) for arm in ARMS)
    high = max(float(arrays[arm + '-height'].max()) for arm in ARMS)
    pad = max((high - low) * .025, 1e-6)
    axes[0].set(xlim=(0, position[-1]), ylim=(low - pad, high + pad))
    footer = ('Shared x/y axes; base means un-eroded carrier H=raw*shell, NOT unshelled raw. '
              'No curve shifting or rescaling; all k=1.\n'
              'Exact requested cuts by linear interpolation between neighboring main-grid rows/columns; '
              'CSV contains local coordinates and shell heights.')
    finish_figure(fig, out, 'profiles.png', 'Three fixed cuts: base / original / adaptive', footer)
    header = ['profile', 'varying_axis', 'fixed_axis', 'fixed_local_blocks', 'position_local_blocks',
              'local_x_blocks', 'local_z_blocks', 'carrier_H', 'original_H', 'adaptive_H',
              'original_delta_vs_carrier_H', 'adaptive_delta_vs_carrier_H']
    with (out / 'profiles.csv').open('w', encoding='utf-8', newline='') as stream:
        writer = csv.writer(stream)
        writer.writerow(header)
        for row in rows:
            writer.writerow([format(v, '.17g') if isinstance(v, (float, np.floating)) else v for v in row])


def render_refinement(out, dataset, cmap):
    report = dataset['reports']['refinement.json']
    step = report['stepWu'] * dataset['sampling']['HS']
    ox, oz = report['originLocalBlocks']
    span = (report['size'] - 1) * step
    images = {arm: light(dataset['fine'][arm], step) for arm in ARMS}
    footer = ('129x129 refinement; stepWu=nativeStepWu; main-grid anchors every 2 fine samples are '
              'bit-identical for all three arms.\n'
              'Sampling-only refinement; no renderer retuning. Same HS=2, Yproxy, fixed light and gray [.65,1].')
    render_grid(out, 'refinement.png', images, step, (ox, oz),
                [('Refined star neighborhood (1900,1855)', (ox, ox + span, oz, oz + span))],
                True, cmap, 'Fine sampling only | old erosion native step unchanged', footer)


def render_settings(color_crops):
    return {'HS': 2, 'horizontalBlocksPerWu': 2, 'yProxyFactor': Y_FACTOR,
            'eDefinition': '.02+1.5*H', 'eLimits': E_LIMITS,
            'lightXZu': [-.5, -.3, 1], 'shade': '.65+.35*max(0,dot)',
            'grayLimits': [.65, 1], 'unitELimits': [-1, 1],
            'erosionOnlyHillshadeInput': 'unscaled unit E, not final H; same fixed Yproxy',
            'noPerArmNormalization': True, 'allK': 1,
            'palette': PALETTE.as_posix(), 'paletteSha256': sha(ROOT / PALETTE),
            'longSeamCropsLocalBlocks': LONG_CROPS, 'starCropsLocalBlocks': STAR_CROPS,
            'cropOrder': '[xmin,xmax,zmin,zmax]', 'optionalColorCrops': color_crops,
            'profiles': PROFILE_LINES, 'profileSampling': 'linear interpolation at exact requested cut',
            'axes': 'local block coordinates, z down in maps; arrays row-major [z,x]',
            'displayInterpolation': 'nearest; sample centers have exact local coordinates'}


def output_hashes(out):
    return file_hashes((p for p in out.rglob('*') if p.is_file() and
                        p.relative_to(out).as_posix() != 'manifest.json'), out)


def write_manifest(out, context, dataset, color_crops):
    manifest = {'kind': KIND, 'status': 'complete', 'completedUtc': utc(),
                'purpose': 'Isolated complete-input mountain type-space comparison; NOT production',
                'renderOnly': context['renderOnly'], 'samplingRun': context['record'],
                'commands': context['commands'], 'sourceHashes': context['activeSources'],
                'sourcesAtSampling': context['sourcesAtSampling'],
                'rendererChangedSinceSampling': context['activeSources'][SCRIPT] !=
                                               context['sourcesAtSampling'][SCRIPT],
                'lockedFileCount': len(context['locked']), 'allLockedUnchanged': True,
                'lockedBeforeSha256': sha(out / 'locked-before.json'),
                'lockedAfterSha256': sha(out / 'locked-after.json'),
                'allDataUnchanged': True, 'dataHashes': context['data'],
                'sampling': dataset['sampling'], 'probeReports': dataset['reports'],
                'calibrationPolicy': CALIBRATION_POLICY, 'verification': dataset['verification'],
                'render': render_settings(color_crops),
                'caveats': ['Candidates are a frozen per-pixel proxy, not seam ground truth.',
                            'm<1 is natural; full-open fraction zero is not evidence of shutdown.',
                            'No labelled morphology acceptance, Java world launch or production wiring.',
                            'Fine anchors prove sample agreement, not morphology quality.'],
                'outputHashExcludes': ['manifest.json (self-reference)'],
                'outputHashes': output_hashes(out)}
    save_json(out / 'manifest.json', manifest)


def record_failure(out, context, error):
    failure = {'kind': KIND, 'status': 'failed', 'timeUtc': utc(), 'error': repr(error),
               'traceback': traceback.format_exc(), 'commands': context['commands']}
    try:
        verify_inputs(out, context, write_after=True)
        failure['lockedInputsUnchanged'] = True
    except Exception as lock_error:
        failure['lockedInputsUnchanged'] = False
        failure['lockError'] = repr(lock_error)
    save_json(out / 'failure.json', failure)
    failure['outputHashExcludes'] = ['manifest.json (self-reference)']
    failure['outputHashes'] = output_hashes(out)
    save_json(out / 'manifest.json', failure)


def execute(out, render_only, color_crops):
    # Preflight errors in render-only do not overwrite anything in the existing run.
    context = prepare(out, render_only)
    try:
        if not render_only:
            sample(out, context['commands'])
            freeze_sampling(out, context)
        dataset = load_dataset(out)
        stats = statistics(out, dataset)
        cmap = palette()
        render_heights(out, dataset, cmap, color_crops)
        render_erosion(out, dataset)
        render_profiles(out, dataset)
        render_refinement(out, dataset, cmap)
        verify_inputs(out, context, write_after=True)
        required = ('comparison.png', 'hillshade.png', 'long-seams.png', 'star-zoom.png',
                    'erosion-only.png', 'profiles.png', 'profiles.csv', 'refinement.png')
        require(all((out / name).is_file() and (out / name).stat().st_size > 0 for name in required),
                'Missing required rendered outputs')
        save_json(out / 'verification.json', {
            'status': 'pass', 'allLockedUnchanged': True, 'allDataUnchanged': True,
            'sourceLockMatchesExceptExplicitRendererEdit': True,
            'requiredOutputs': list(required), **dataset['verification']})
        write_manifest(out, context, dataset, color_crops)
        print(json.dumps({arm: {'candidatePixels': value['candidatePixels'],
                               'E_rms': value['unitE']['rms'],
                               'E_gradientRmsPerWu': value['unitEgradientPerWu']['gradientRms'],
                               'signedMeanDeltaVsCarrier': value['signedMeanDeltaVsCarrier']}
                          for arm, value in stats['arms'].items()}, indent=2), flush=True)
        print('DONE', out, '| type-space, NOT production; morphology awaits review', flush=True)
    except Exception as error:
        record_failure(out, context, error)
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('out', help='build/<NEWdir>, or this script\'s existing run for --render-only')
    parser.add_argument('--render-only', action='store_true', help='verify frozen inputs; never rerun Java')
    parser.add_argument('--color-crops', action='store_true', help='also render fixed-color versions of seam/star crops')
    args = parser.parse_args()
    try:
        execute(output_path(args.out, args.render_only), args.render_only, args.color_crops)
        return 0
    except Exception:
        traceback.print_exc()
        return 1


if __name__ == '__main__':
    sys.exit(main())
