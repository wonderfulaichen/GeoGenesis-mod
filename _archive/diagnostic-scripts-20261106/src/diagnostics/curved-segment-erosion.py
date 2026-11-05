"""Straight versus curved old-distance-field geometry, isolated mountain type-space.

From the MDK directory:
    python -B src/diagnostics/curved-segment-erosion.py build/<NEW>
    python -B src/diagnostics/curved-segment-erosion.py --render-only build/<NEW>

Java owns the final functions, fixed convergence selection and direct profiles.
This renderer never selects junctions, fits fields, interpolates profiles, changes
2wu erosion domains, or infers a world inverse from erosion-domain coordinates.
Normal convergence is not a defect. No layer inheritance or production wiring.
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
import matplotlib

matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.cm import ScalarMappable
from matplotlib.colors import ListedColormap, Normalize


ROOT = Path(__file__).resolve().parents[2]
SCRIPT = Path(__file__).resolve().relative_to(ROOT).as_posix()
KIND = 'curved-segment-geometry-type-space-v1'
MORPH = 'src/diagnostics/java/com/geogenesis/worldgen/terrain/morph/'
JAVA_SOURCES = [MORPH + name + '.java' for name in
                ('CurvedSegmentErosion', 'CurvedSegmentCheck', 'CurvedSegmentProbe')]
PACKAGE = 'com.geogenesis.worldgen.terrain.morph.'
HELPER = Path('src/diagnostics/fast-erosion-adaptive.py')
BASELINE = Path('build/fast-erosion-inherited-b-20261103-final')
STRAIGHT_REFERENCE = Path('build/fast-erosion-scale-only-20261103-final/data/A.f64be')
STRAIGHT_SHA = '78e0a6fa92c365e7a44f22b1bc948696f07f76a3b9477e3f808a0935cd8b3bcb'
RAW_SHA = 'c40405a8bec5b38187752da27090e62873a32e5314505d741a9420d458d7e3f4'
PALETTE = Path('build/fast-erosion-scale-only-20261103-final/data/project-palette.csv')
ORIGIN = [-4158.0078086275535, -4279.531246014376]
STEP = 2.382812497780833
SEED = 9139912035078620160
FIELDS = ('straight-height', 'curved-height', 'base-raw')
REPORTS = ('checks.json', 'geometry.json', 'performance.json')
PROFILE_HEADER = ['profile', 's_wu', 'world_x', 'world_z', 'straight_H', 'curved_H']
ARMS = ('straight', 'curved')
TITLES = ('Straight segments | old distance field', 'Curved segments | geometry only')
E_LIMITS = (-.35, 1.2)
Y_FACTOR = 354.66
LIGHT = np.array([-.5, -.3, 1.0])
LIGHT /= np.linalg.norm(LIGHT)
SCOPE = 'Pure mountain type-space | NOT actual Minecraft | geometry-only A/B'
FOOTER = ('HS=2; Yproxy=354.66H; e=.02+1.5H, fixed e[-.35,1.2]; '
          'light[-.5,-.3,1], shade=.65+.35*positiveDot.\n'
          'No per-arm normalization; unchanged 2wu domain. Normal convergence is NOT a defect. '
          'No layer inheritance; visual assessment is external.')
REQUIRED = ('comparison.png', 'hillshade.png', 'convergence-zoom.png',
            'profiles.png', 'statistics.json', 'outcome.txt')


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


def reject_constant(value):
    raise ValueError('Non-finite JSON constant: ' + value)


def load_json(path):
    return json.loads(path.read_text(encoding='utf-8-sig'), parse_constant=reject_constant)


def save_json(path, value):
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False, allow_nan=False)
                    + '\n', encoding='utf-8')


def hashes(paths, root):
    return {p.relative_to(root).as_posix(): sha(p) for p in sorted(set(paths))}


def tree_hashes(folder, root):
    require(folder.is_dir(), 'Missing directory: ' + str(folder))
    return hashes((p for p in folder.rglob('*') if p.is_file()), root)


def same_hashes(expected, actual, label):
    changed = [key for key in sorted(expected.keys() | actual.keys())
               if expected.get(key) != actual.get(key)]
    require(not changed, label + ' changed: ' + ', '.join(changed[:20]))


def locked_snapshot():
    # Deliberately NOT A/B source-lock.json: no historical experimental source lock.
    result = tree_hashes(ROOT / 'src/main', ROOT)
    result.update(tree_hashes(ROOT / BASELINE / 'data', ROOT))
    result.update(hashes([ROOT / STRAIGHT_REFERENCE, ROOT / PALETTE], ROOT))
    require(result[STRAIGHT_REFERENCE.as_posix()] == STRAIGHT_SHA, 'Historical straight A SHA drift')
    require(result[(BASELINE / 'data/base-raw.f64be').as_posix()] == RAW_SHA, 'Historical raw SHA drift')
    return result


def source_snapshot():
    return hashes([ROOT / path for path in [SCRIPT, *JAVA_SOURCES]], ROOT)


def output_path(argument, render_only):
    out = (ROOT / argument).resolve()
    require(out.parent == (ROOT / 'build').resolve(), 'OUT must be a direct child of MDK build/')
    require(out.is_dir() if render_only else not out.exists(),
            '--render-only needs a frozen run' if render_only else 'Refusing existing OUT')
    require(out != (ROOT / BASELINE).resolve(), 'Old finalB is read-only')
    return out


def visual_helpers():
    # Reuse ONLY these function ASTs, never import/execute the old orchestrator.
    path = ROOT / HELPER
    text = path.read_text(encoding='utf-8-sig')
    tree = ast.parse(text, filename=str(path))
    names = {'light', 'palette'}
    nodes = [n for n in tree.body if isinstance(n, ast.FunctionDef) and n.name in names]
    require({n.name for n in nodes} == names and len(nodes) == 2, 'Missing light/palette helpers')
    require(all(not n.decorator_list and not n.args.defaults and not n.args.kw_defaults
                for n in nodes), 'Unexpected helper definitions')
    scope = {'np': np, 'ROOT': ROOT, 'PALETTE': PALETTE, 'Y_FACTOR': Y_FACTOR,
             'LIGHT': LIGHT, 'ListedColormap': ListedColormap, 'require': require}
    module = ast.Module(body=nodes, type_ignores=[])
    exec(compile(module, str(path), 'exec'), scope)
    provenance = {'path': HELPER.as_posix(), 'moduleSha256': sha(path),
                  'functions': {n.name: ast.get_source_segment(text, n) for n in nodes},
                  'astSha256': hashlib.sha256(ast.dump(module).encode('utf-8')).hexdigest(),
                  'policy': 'Only light/palette reused; old experimental sources are not locked'}
    return scope['light'], scope['palette'], provenance


def echo(block):
    if hasattr(sys.stdout, 'buffer'):
        sys.stdout.buffer.write(block)
        sys.stdout.buffer.flush()
    else:
        sys.stdout.write(block.decode('utf-8', errors='replace'))
        sys.stdout.flush()


class CommandFailed(RuntimeError):
    def __init__(self, stage, code):
        super().__init__('%s failed with exit %s; see validation.log and logs/' % (stage, code))
        self.code = code


def run_command(out, commands, stage, args):
    log = 'logs/%02d-%s.log' % (len(commands) + 1, stage)
    record = {'stage': stage, 'argv': args, 'cwd': str(ROOT), 'startedUtc': utc(),
              'exitCode': None, 'log': log, 'status': 'running'}
    commands.append(record)
    save_json(out / 'commands.json', commands)
    started = time.perf_counter()
    error = None
    (out / 'logs').mkdir(exist_ok=True)
    with (out / log).open('wb') as raw, (out / 'validation.log').open('ab') as combined:
        def emit(block):
            raw.write(block)
            raw.flush()
            combined.write(block)
            combined.flush()
            echo(block)
        emit(('\nCOMMAND ' + subprocess.list2cmdline(args) + '\n').encode('utf-8'))
        try:
            with subprocess.Popen(args, cwd=ROOT, stdout=subprocess.PIPE,
                                  stderr=subprocess.STDOUT) as process:
                for block in iter(lambda: process.stdout.read1(65536), b''):
                    emit(block)
                record['exitCode'] = process.wait()
        except Exception as exc:
            error = exc
            record['error'] = repr(exc)
            emit((traceback.format_exc() + '\n').encode('utf-8'))
        record.update(seconds=time.perf_counter() - started, finishedUtc=utc())
        record['status'] = 'ok' if error is None and record['exitCode'] == 0 else 'failed'
        emit(('\nexit=%s seconds=%.6f status=%s\n' %
              (record['exitCode'], record['seconds'], record['status'])).encode('utf-8'))
    record['logSha256'] = sha(out / log)
    save_json(out / 'commands.json', commands)
    if error is not None:
        raise RuntimeError('Could not execute ' + stage) from error
    if record['exitCode'] != 0:
        raise CommandFailed(stage, record['exitCode'])


def sample(out, commands):
    classes = out / 'classes'
    classes.mkdir()
    run_command(out, commands, 'javac', [
        'javac', '--release', '17', '-encoding', 'UTF-8', '-proc:none', '-Xlint:all', '-Werror',
        '-classpath', str(classes), '-d', str(classes),
        '-sourcepath', os.pathsep.join(('src/diagnostics/java', 'src/main/java')),
        *JAVA_SOURCES])
    run_command(out, commands, 'check', [
        'java', '-cp', str(classes), PACKAGE + 'CurvedSegmentCheck'])
    run_command(out, commands, 'probe', [
        'java', '-Xmx3g', '-cp', str(classes), PACKAGE + 'CurvedSegmentProbe', str(out / 'data')])


def execution_snapshot(out):
    names = ('commands.json', 'validation.log', 'locked-before.json',
             'source-lock.json', 'data-lock.json')
    result = hashes([out / name for name in names], out)
    for folder in ('classes', 'logs'):
        result.update(tree_hashes(out / folder, out))
    return result


def prepare(out, render_only):
    locked, sources = locked_snapshot(), source_snapshot()
    if not render_only:
        out.mkdir(parents=True, exist_ok=False)
        save_json(out / 'locked-before.json', locked)
        save_json(out / 'source-lock.json', sources)
        save_json(out / 'commands.json', [])
        return {'locked': locked, 'sampledSources': sources, 'activeSources': sources,
                'commands': [], 'data': None, 'record': None, 'renderOnly': False}
    record = load_json(out / 'run-record.json')
    require(record.get('kind') == KIND and record.get('state') == 'data-ready',
            'Render-only accepts only this orchestrator\'s frozen run')
    same_hashes(record['immutableOutputs'], execution_snapshot(out), 'Execution artifacts')
    same_hashes(load_json(out / 'locked-before.json'), locked, 'Main and old finalB/reference inputs')
    sampled = load_json(out / 'source-lock.json')
    same_hashes({k: v for k, v in sampled.items() if k != SCRIPT},
                {k: v for k, v in sources.items() if k != SCRIPT}, 'Sampling Java sources')
    data = load_json(out / 'data-lock.json')
    same_hashes(data, tree_hashes(out / 'data', out), 'Sampled data')
    commands = load_json(out / 'commands.json')
    require([c['stage'] for c in commands] == ['javac', 'check', 'probe'] and
            all(c['exitCode'] == 0 for c in commands), 'Java stages did not all pass')
    return {'locked': locked, 'sampledSources': sampled, 'activeSources': sources,
            'commands': commands, 'data': data, 'record': record, 'renderOnly': True}


def verify_locks(out, context, write_after=False):
    current = locked_snapshot()
    if write_after:
        save_json(out / 'locked-after.json', current)
    same_hashes(context['locked'], current, 'All main files and old finalB/reference inputs')
    same_hashes(context['activeSources'], source_snapshot(), 'Active sources')
    if context['data'] is not None:
        same_hashes(context['data'], tree_hashes(out / 'data', out), 'Sampled data')
    if context['record'] is not None:
        same_hashes(context['record']['immutableOutputs'], execution_snapshot(out), 'Execution artifacts')
        require(load_json(out / 'run-record.json') == context['record'], 'Sampling record changed')


def freeze_sampling(out, context, helper_provenance):
    verify_locks(out, context)
    context['data'] = tree_hashes(out / 'data', out)
    require(bool(context['data']), 'Probe wrote no data')
    save_json(out / 'data-lock.json', context['data'])
    record = {'kind': KIND, 'state': 'data-ready', 'createdUtc': utc(),
              'immutableOutputs': execution_snapshot(out), 'visualHelpersAtSampling': helper_provenance,
              'jvmEnvironment': {k: os.environ.get(k) for k in
                                 ('JAVA_HOME', 'JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS',
                                  'JDK_JAVA_OPTIONS', 'CLASSPATH')},
              'sourcepathPolicy': 'Only three explicit diagnostic roots; empty external classpath; JDK 17 API'}
    save_json(out / 'run-record.json', record)
    context['record'] = record


def validate_sampling(meta):
    require(meta['size'] == 769 and meta['HS'] == 2, 'Expected size=769, HS=2')
    require(type(meta['seed']) is int and meta['seed'] == SEED, 'Frozen integer seed drift')
    require(np.isclose(meta['stepWu'], STEP, rtol=0, atol=1e-12), 'Frozen stepWu drift')
    origin = np.asarray(meta['originWu'], dtype=float)
    require(origin.shape == (2,) and np.allclose(origin, ORIGIN, rtol=0, atol=1e-10),
            'Frozen originWu drift')
    require(np.isclose(meta['typeLengthWu'], 610, rtol=0, atol=1e-5), 'typeLengthWu must be ~610')
    declarations = [meta[k] for k in ('actualMinecraftWorld', 'actualMC') if k in meta]
    require(bool(declarations) and all(v is False for v in declarations),
            'Probe must explicitly declare actualMinecraftWorld/actualMC=false')
    if 'spanWu' in meta:
        require(np.isclose(meta['spanWu'], STEP * 768, rtol=0, atol=1e-9), 'spanWu mismatch')


def read_array(path, size):
    require(path.stat().st_size == size * size * 8, 'Expected exactly N*N >f8: ' + str(path))
    value = np.fromfile(path, dtype='>f8').astype(np.float64).reshape(size, size)
    require(np.isfinite(value).all(), 'Non-finite array: ' + str(path))
    return value


def point(value, label):
    result = np.asarray(value, dtype=float)
    require(result.shape == (2,) and np.isfinite(result).all(), 'Invalid [x,z]: ' + label)
    return result


def window_bounds(meta):
    x, z = meta['originWu']
    span = (meta['size'] - 1) * meta['stepWu']
    return x, x + span, z, z + span


def domain_only(value):
    # Conservative: nested helper metadata marked domainOnly disables overlays.
    if isinstance(value, dict):
        for key, child in value.items():
            if key == 'domainOnly' and child is not False and child is not None:
                return True
            if key in ('coordinateSystem', 'coordinateSpace', 'coordinates', 'routeSpace') and isinstance(child, str):
                if 'erosion-domain' in child.lower() or 'domain wu' in child.lower():
                    return True
            if domain_only(child):
                return True
    elif isinstance(value, list):
        return any(domain_only(child) for child in value)
    return False


def validate_convergences(document, meta):
    require(isinstance(document, dict), 'convergences.json must be a root object')
    require(isinstance(document.get('coordinateSystem'), str), 'Missing convergence coordinateSystem')
    require(type(document.get('domainOnly')) is bool, 'Missing convergence domainOnly flag')
    items = document['convergences']
    require(isinstance(items, list) and len(items) == 2, 'Probe must supply exactly two fixed convergences')
    ids = []
    xmin, xmax, zmin, zmax = window_bounds(meta)
    for item in items:
        ids.append(str(item['id']))
        point(item['junctionWu'], 'junctionWu')
        require(np.isclose(item['radiusWu'], 100, rtol=0, atol=1e-9), 'Expected radiusWu=100')
        x0, x1, z0, z1 = crop_for(item)
        require(xmin <= x0 < x1 <= xmax and zmin <= z0 < z1 <= zmax,
                'Fixed convergence crop outside frozen window: ' + str(item['id']))
        if 'inverseResidualWu' in item:
            residual = float(item['inverseResidualWu'])
            require(np.isfinite(residual) and residual >= 0, 'Invalid inverseResidualWu')
        require(isinstance(item['edges'], list) and bool(item['edges']), 'Missing convergence edges')
        for edge in item['edges']:
            require('sourceId' in edge and 'receiverId' in edge, 'Missing edge identities')
            for arm in ARMS:
                route = np.asarray(edge[arm], dtype=float)
                require(route.ndim == 2 and route.shape[1] == 2 and len(route) >= 2 and
                        np.isfinite(route).all(), 'Invalid route polyline: ' + arm)
    require(len(set(ids)) == 2, 'Duplicate convergence IDs')


def read_profiles(path):
    groups = {}
    with path.open('r', encoding='utf-8-sig', newline='') as stream:
        reader = csv.reader(stream)
        require(next(reader, None) == PROFILE_HEADER, 'profiles.csv header mismatch')
        for row in reader:
            require(len(row) == 6 and bool(row[0]), 'Invalid direct profile row')
            values = np.asarray([float(v) for v in row[1:]], dtype=float)
            require(np.isfinite(values).all(), 'Non-finite direct profile')
            groups.setdefault(row[0], []).append(values)
    require(len(groups) >= 3, 'Expected world x/z and convergence direct profiles')
    result = {name: np.asarray(rows) for name, rows in groups.items()}
    for name, values in result.items():
        require(len(values) >= 2 and (np.diff(values[:, 0]) > 0).all(),
                'Direct profile s_wu must increase: ' + name)
    return result


def load_dataset(out):
    data = out / 'data'
    meta = load_json(data / 'sampling.json')
    validate_sampling(meta)
    arrays = {name: read_array(data / (name + '.f64be'), meta['size']) for name in FIELDS}
    reports = {name: load_json(data / name) for name in REPORTS}
    require(all(isinstance(v, dict) for v in reports.values()), 'Expected object reports')
    convergences = load_json(data / 'convergences.json')
    validate_convergences(convergences, meta)
    profiles = read_profiles(data / 'profiles.csv')
    raw_sha = sha(data / 'base-raw.f64be')
    straight_sha = sha(data / 'straight-height.f64be')
    require(raw_sha == RAW_SHA == sha(ROOT / BASELINE / 'data/base-raw.f64be'),
            'Geometry-only A/B must keep the frozen finalB base raw bit-identical')
    require(straight_sha == STRAIGHT_SHA == sha(ROOT / STRAIGHT_REFERENCE),
            'Straight arm must reproduce historical scale-only A bit-for-bit')
    return {'sampling': meta, 'arrays': arrays, 'reports': reports,
            'convergences': convergences, 'profiles': profiles,
            'verification': {'baseRawBitIdenticalToFinalB': True, 'baseRawSha256': raw_sha,
                             'straightBitIdenticalToHistoricalA': True, 'straightSha256': straight_sha,
                             'arrayDtype': '>f8', 'arrayShape': [769, 769],
                             'arrayOrder': 'row-major [z,x]', 'normalConvergenceIsDefect': False,
                             'profileSource': 'Java probe CSV of direct final functions, not renderer interpolation',
                             'profileDirectnessIndependentlyRetested': False}}


def distribution(value):
    levels = [0, .01, .05, .5, .9, .95, .99, .999, 1]
    names = ['min', 'p01', 'p05', 'p50', 'p90', 'p95', 'p99', 'p999', 'max']
    result = dict(zip(names, map(float, np.quantile(value, levels))))
    result.update(mean=float(value.mean()), std=float(value.std()),
                  rms=float(np.sqrt(np.mean(value * value))))
    return result


def gradient_summary(value, step):
    gz, gx = np.gradient(value, step)
    result = distribution(np.hypot(gx, gz)[1:-1, 1:-1])
    result['tailP999overP50'] = result['p999'] / result['p50'] if result['p50'] > 0 else None
    return result


def statistics(out, dataset):
    arrays, meta = dataset['arrays'], dataset['sampling']
    delta = arrays['curved-height'] - arrays['straight-height']
    result = {'differenceCurvedMinusStraightH': distribution(delta),
              'differenceGradientHperWu': gradient_summary(delta, meta['stepWu']),
              'gradientsHperWu': {arm: gradient_summary(arrays[arm + '-height'], meta['stepWu'])
                                 for arm in ARMS},
              'profiles': {name: {'samples': len(v), 'differenceCurvedMinusStraightH':
                                  distribution(v[:, 4] - v[:, 3])}
                           for name, v in dataset['profiles'].items()},
              'performance': dataset['reports']['performance.json'],
              'gradientDefinition': 'np.gradient(H, stepWu), central interior; excludes one-pixel border',
              'policy': 'Measurements only: no star counts, defect classifier, visual score or morphology PASS/FAIL'}
    save_json(out / 'statistics.json', result)
    return result


def panel(ax, image, meta, bounds, title, gray=False):
    step = meta['stepWu']
    ox, oz = meta['originWu']
    x0, x1, z0, z1 = bounds
    whole = window_bounds(meta)
    require(whole[0] <= x0 < x1 <= whole[1] and whole[2] <= z0 < z1 <= whole[3],
            'Crop outside sampled window; refusing clamp/reselection')
    ix0, iz0 = max(0, int(np.floor((x0 - ox) / step))), max(0, int(np.floor((z0 - oz) / step)))
    ix1 = min(meta['size'], int(np.ceil((x1 - ox) / step)) + 1)
    iz1 = min(meta['size'], int(np.ceil((z1 - oz) / step)) + 1)
    extent = (ox + (ix0 - .5) * step, ox + (ix1 - .5) * step,
              oz + (iz1 - .5) * step, oz + (iz0 - .5) * step)
    options = {'cmap': 'gray', 'vmin': .65, 'vmax': 1} if gray else {}
    ax.imshow(image[iz0:iz1, ix0:ix1], origin='upper', extent=extent,
              interpolation='nearest', aspect='equal', **options)
    ax.set(xlim=(x0, x1), ylim=(z1, z0), title=title, xlabel='world x (wu)', ylabel='world z (wu)')


def finish(fig, out, filename, subtitle, bar=None, footer=FOOTER):
    fig.suptitle(SCOPE + '\n' + subtitle, fontsize=13)
    fig.text(.025, .015, footer, fontsize=9)
    fig.tight_layout(rect=(0, .085, .94 if bar else 1, .91))
    if bar:
        fig.colorbar(bar[0], cax=fig.add_axes([.955, .25, .012, .5]), label=bar[1])
    fig.savefig(out / filename, dpi=160, metadata={'Description': SCOPE + '; ' + subtitle})
    plt.close(fig)


def whole_figures(out, dataset, shades, colors, cmap):
    meta = dataset['sampling']
    for filename, images, gray in (('comparison.png', colors, False), ('hillshade.png', shades, True)):
        fig, axes = plt.subplots(1, 2, figsize=(16, 8))
        for ax, arm, title in zip(axes, ARMS, TITLES):
            panel(ax, images[arm], meta, window_bounds(meta), title, gray)
        bar = ((ScalarMappable(norm=Normalize(.65, 1), cmap='gray'), 'fixed light') if gray else
               (ScalarMappable(norm=Normalize(*E_LIMITS), cmap=cmap), 'e=.02+1.5H (unshaded palette)'))
        finish(fig, out, filename, 'Same frozen world window; straight versus curved geometry only', bar)


def crop_for(item):
    # Probe world inverse wins. Otherwise use the SAME fixed domain q only as an
    # approximate locator, never a world-space route or renderer-computed inverse.
    key = 'worldJunctionWu' if 'worldJunctionWu' in item else 'junctionWu'
    x, z = point(item[key], key)
    radius = item['radiusWu']
    return x - radius, x + radius, z - radius, z + radius


def overlay_routes(ax, item, arm, disabled):
    if disabled or domain_only(item):
        ax.text(.02, .03, 'Routes omitted: domainOnly; no world inverse', transform=ax.transAxes,
                fontsize=8, bbox={'facecolor': 'white', 'alpha': .85})
        return 0
    for edge in item['edges']:
        route = np.asarray(edge[arm], dtype=float)
        ax.plot(route[:, 0], route[:, 1], color='#00ffff', linewidth=1, alpha=.9,
                label='%s -> %s' % (edge['sourceId'], edge['receiverId']))
    ax.legend(fontsize=6, loc='upper right')
    return len(item['edges'])


def convergence_figures(out, dataset, shades):
    meta, document = dataset['sampling'], dataset['convergences']
    items = document['convergences']
    disabled = any(domain_only(v) for v in (meta, document, dataset['reports']['geometry.json']))
    crops = [crop_for(item) for item in items]
    route_counts = {}
    modes = [(False, 'convergence-zoom.png')]
    if not disabled:
        modes.append((True, 'convergence-routes.png'))
    for overlay, filename in modes:
        fig, axes = plt.subplots(2, 2, figsize=(14, 13), squeeze=False)
        for row, (item, bounds) in enumerate(zip(items, crops)):
            if 'worldJunctionWu' in item:
                locator = 'probe world inverse; residual=%s wu' % item.get('inverseResidualWu', 'not reported')
            elif disabled:
                locator = 'DOMAIN q approximate locator; original 2wu warp; NOT exact world alignment'
            else:
                locator = 'probe world coordinates'
            for col, (arm, title) in enumerate(zip(ARMS, TITLES)):
                ax = axes[row, col]
                panel(ax, shades[arm], meta, bounds,
                      '%s | %s | radius=100 wu\n%s' % (item['id'], title, locator), True)
                if overlay:
                    route_counts[str(item['id']) + ':' + arm] = overlay_routes(ax, item, arm, disabled)
        subtitle = ('Routes for LOCATION ONLY; not drainage proof or defect labels' if overlay else
                    'Two probe-fixed convergence crops; pure hillshade; NO auxiliary lines')
        finish(fig, out, filename, subtitle,
               (ScalarMappable(norm=Normalize(.65, 1), cmap='gray'), 'fixed light'))
    return {'fixedCropsOnWorldAxesWu': crops, 'cropOrder': '[xmin,xmax,zmin,zmax]',
            'routeOverlayGloballyDisabled': disabled, 'drawnRoutes': route_counts,
            'approximateDomainLocatorCrops': sum('worldJunctionWu' not in item and disabled for item in items),
            'worldInverseProvided': [str(item['id']) for item in items if 'worldJunctionWu' in item],
            'selection': 'Exactly the two Java probe records, unchanged order; never reselected',
            'overlayMeaning': 'Location only; domainOnly routes never drawn as world routes'}


def render_profiles(out, dataset):
    profiles = dataset['profiles']
    fig, axes = plt.subplots(len(profiles), 1, figsize=(16, max(10, 3 * len(profiles))), squeeze=False)
    lo = min(float(v[:, 3:5].min()) for v in profiles.values())
    hi = max(float(v[:, 3:5].max()) for v in profiles.values())
    pad = max((hi - lo) * .025, 1e-6)
    for ax, (name, values) in zip(axes[:, 0], profiles.items()):
        for col, title, color in ((3, TITLES[0], '#d95f02'), (4, TITLES[1], '#1b9e77')):
            ax.plot(values[:, 0], values[:, col], linewidth=1, label=title, color=color)
        first, last = values[0, 1:3], values[-1, 1:3]
        ax.set(title='%s | world (%.3f, %.3f) -> (%.3f, %.3f)' % (name, *first, *last),
               xlabel='s_wu (Java direct profile)', ylabel='final height H',
               ylim=(lo - pad, hi + pad), xlim=(values[0, 0], values[-1, 0]))
        ax.grid(alpha=.2)
        ax.legend(fontsize=9)
    finish(fig, out, 'profiles.png', 'Direct final-function samples from Java data/profiles.csv',
           footer='Shared H limits across every cut; no shifting, fitting, rescaling or image interpolation.\n'
                  'CSV stays immutable in data/. Normal convergence is not labelled as a defect; NOT production.')


def render(out, dataset, light, palette):
    meta = dataset['sampling']
    cmap = palette()
    shades = {arm: light(dataset['arrays'][arm + '-height'], meta['stepWu'] * meta['HS']) for arm in ARMS}
    colors = {arm: cmap(Normalize(*E_LIMITS, clip=True)(.02 + 1.5 * dataset['arrays'][arm + '-height']))
                   [..., :3] * shades[arm][..., None] for arm in ARMS}
    whole_figures(out, dataset, shades, colors, cmap)
    crops = convergence_figures(out, dataset, shades)
    render_profiles(out, dataset)
    return {'HS': 2, 'yProxyFactor': Y_FACTOR, 'eDefinition': '.02+1.5H', 'eLimits': E_LIMITS,
            'lightXZu': [-.5, -.3, 1], 'shade': '.65+.35*max(0,dot)', 'grayLimits': [.65, 1],
            'noPerArmNormalization': True, 'palette': PALETTE.as_posix(),
            'paletteSha256': sha(ROOT / PALETTE), 'displayInterpolation': 'nearest',
            'mapCoordinates': 'world wu; row-major [z,x], z down; light step=stepWu*HS blocks',
            'profiles': list(dataset['profiles']), 'profileData': 'data/profiles.csv', **crops}


def output_hashes(out):
    return hashes((p for p in out.rglob('*') if p.is_file() and
                   p.relative_to(out).as_posix() != 'manifest.json'), out)


def write_outcome(out, stats, settings):
    delta = stats['differenceCurvedMinusStraightH']
    text = ('Measurement facts only; pure mountain type-space, NOT actual Minecraft.\n'
            'Straight vs curved old-distance-field geometry; no layer inheritance or production integration.\n'
            'curved_H - straight_H: mean=%.17g, rms=%.17g, min=%.17g, max=%.17g.\n'
            'Approximate domain-locator crops without world inverse: %d; no domainOnly route overlays.\n'
            'Java direct profiles: data/profiles.csv. Performance: data/performance.json.\n'
            'Normal convergence is not a defect; no star-count gate is used.\n'
            'No visual quality conclusion: main agent must inspect the figures.\n')
    (out / 'outcome.txt').write_text(text % (delta['mean'], delta['rms'], delta['min'], delta['max'],
                                           settings['approximateDomainLocatorCrops']), encoding='utf-8')


def write_manifest(out, context, dataset, settings, helpers):
    verification = {'status': 'integrity-pass-not-visual-acceptance', 'allMainUnchanged': True,
                    'allOldFinalBDataAndHistoricalAAndPaletteUnchanged': True, 'allDataUnchanged': True,
                    'javaStagesPassed': True, 'requiredOutputs': list(REQUIRED),
                    'visualReviewRequired': True,
                    'approximateDomainLocatorCrops': settings['approximateDomainLocatorCrops'],
                    **dataset['verification']}
    save_json(out / 'verification.json', verification)
    manifest = {'kind': KIND, 'schemaVersion': 1, 'status': 'rendered-awaiting-visual-review',
                'completedUtc': utc(), 'scope': SCOPE, 'actualMinecraftWorld': False,
                'renderOnly': context['renderOnly'], 'samplingRun': context['record'],
                'commands': context['commands'], 'sourcesAtSampling': context['sampledSources'],
                'activeSources': context['activeSources'], 'visualHelpers': helpers,
                'lockedBeforeSha256': sha(out / 'locked-before.json'),
                'lockedAfterSha256': sha(out / 'locked-after.json'),
                'lockedFileCount': len(context['locked']), 'dataHashes': context['data'],
                'sampling': dataset['sampling'], 'probeReports': dataset['reports'],
                'convergences': dataset['convergences'], 'verification': verification,
                'render': settings, 'dataContract': {
                    'arrays': {name + '.f64be': {'dtype': '>f8', 'shape': [769, 769], 'order': '[z,x]'}
                               for name in FIELDS}, 'profilesHeader': PROFILE_HEADER,
                    'profilesEvaluation': 'Java direct final functions; no renderer interpolation',
                    'convergenceCoordinates': 'Root coordinateSystem/domainOnly retained; prefer worldJunctionWu for crops; domain q fallback is approximate, never a world route',
                    'geometryPolicy': 'Straight vs curved only; retain old distance field and 2wu domain'},
                'caveats': ['Normal convergence is not a defect; no star-count fail criterion.',
                            'No layer inheritance, production wiring, Minecraft launch or visual acceptance.',
                            'Java metadata retained verbatim; directness and geometry-only claims require core review.',
                            'domainOnly routes omitted; domain-q fallback crops are approximate locators, never reselected.'],
                'historicalStraightReference': {'path': STRAIGHT_REFERENCE.as_posix(), 'sha256': STRAIGHT_SHA},
                'lockPolicy': 'All src/main, finalB data, historical straight A and palette only; no plan or old experiment sources',
                'outputHashExcludes': ['manifest.json (self-reference)'], 'outputHashes': output_hashes(out)}
    save_json(out / 'manifest.json', manifest)


def record_failure(out, context, error):
    failure = {'kind': KIND, 'status': 'failed', 'timeUtc': utc(), 'error': repr(error),
               'traceback': traceback.format_exc(), 'commands': context['commands']}
    try:
        verify_locks(out, context, write_after=True)
        failure['lockedInputsUnchanged'] = True
    except Exception as lock_error:
        failure['lockedInputsUnchanged'] = False
        failure['lockError'] = repr(lock_error)
    save_json(out / 'failure.json', failure)
    failure['outputHashExcludes'] = ['manifest.json (self-reference)']
    failure['outputHashes'] = output_hashes(out)
    save_json(out / 'manifest.json', failure)


def execute(out, render_only):
    # Preflight errors never overwrite an existing unrelated or invalid run.
    light, palette, helpers = visual_helpers()
    context = prepare(out, render_only)
    try:
        if not render_only:
            sample(out, context['commands'])
            freeze_sampling(out, context, helpers)
        dataset = load_dataset(out)
        stats = statistics(out, dataset)
        settings = render(out, dataset, light, palette)
        write_outcome(out, stats, settings)
        verify_locks(out, context, write_after=True)
        require(all((out / name).is_file() and (out / name).stat().st_size > 0 for name in REQUIRED),
                'Missing rendered output')
        write_manifest(out, context, dataset, settings, helpers)
        print(json.dumps(stats, indent=2, allow_nan=False), flush=True)
        print('DONE', out, '| Integrity checked; visual judgement remains with main agent.', flush=True)
    except Exception as error:
        record_failure(out, context, error)
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('out', help='build/<NEW>, or this orchestrator\'s frozen run for --render-only')
    parser.add_argument('--render-only', action='store_true', help='verify frozen data/logs; never execute Java')
    args = parser.parse_args()
    try:
        execute(output_path(args.out, args.render_only), args.render_only)
        return 0
    except Exception as error:
        traceback.print_exc()
        return error.code if isinstance(error, CommandFailed) and error.code > 0 else 1


if __name__ == '__main__':
    sys.exit(main())
