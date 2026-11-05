"""S0/S1 noise-following subtraction evidence; pure mountain type-space, NOT MC.

From MDK, with numpy/matplotlib and a JDK on PATH (no Gradle/Forge):
    python -B src/diagnostics/noise-following-subtractive.py build/<NEW>
    python -B src/diagnostics/noise-following-subtractive.py build/<NEW> --render-only

Java owns tracing, direct final-function CSVs, zero-depth tests and bed verdicts.
All traces, including failed/stalled/truncated ones, retain their supplied order.
Contract integrity is NOT morphological acceptance. Stop after S1, even on failure.
"""

import argparse
import csv
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys
import time
import traceback
from datetime import datetime, timezone

sys.dont_write_bytecode = True
import numpy as np
import matplotlib

matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.cm import ScalarMappable
from matplotlib.colors import ListedColormap, Normalize

ROOT = Path(__file__).resolve().parents[2]
SCRIPT = Path(__file__).resolve().relative_to(ROOT).as_posix()
KIND = 'noise-following-subtractive-s0-s1-v1'
DIAG = Path('src/diagnostics/java')
STANDALONE = DIAG / 'com/geogenesis/standalone'
MORPH = DIAG / 'com/geogenesis/worldgen/terrain/morph'
SOURCES = (STANDALONE / 'NoiseFollowingErosion.java',
           STANDALONE / 'NoiseFollowingCheck.java', MORPH / 'NoiseFollowingProbe.java')
OLD_SOURCES = tuple(MORPH / (name + '.java') for name in (
    'CurvedSegmentErosion', 'CompleteCurveQuery', 'CurvedSegmentCheck',
    'CompleteCurveQueryCheck'))
BASE = ROOT / 'build/curved-window-seam-20261103-final/data'
PALETTE = Path('build/fast-erosion-scale-only-20261103-final/data/project-palette.csv')
RAW_SHA = 'c40405a8bec5b38187752da27090e62873a32e5314505d741a9420d458d7e3f4'
OLD_SHA = 'eeb48f6582476b2843a8e7e65fdff9fdda217c5c8a06285b3bc03dcdac55822f'
ORIGIN = [-4158.0078086275535, -4279.531246014376]
STEP = 2.382812497780833
SEED = 9139912035078620160
CAP, WIDTH, AMP = .02 / 1.5, 8.0, .699999988079071
FIELDS = ('carrier-height', 'old-height', 'cut-height', 'base-raw', 'cut-depth')
REPORTS = ('sampling.json', 'checks.json', 'traces.json', 'bed-validation.json',
           'performance.json', 'discovery-assessment.json')
PROFILE_HEADER = ['profile', 's_wu', 'world_x', 'world_z', 'carrier_H', 'old_H', 'cut_H']
CROSS_HEADER = ['trace', 's_wu', 'offset_wu', 'world_x', 'world_z',
                'carrier_H', 'old_H', 'cut_H']
ARMS = ('carrier', 'old', 'cut')
TITLES = ('Carrier H0 | complete raw + original shell',
          'Old | complete curved-distance replacement', 'Cut | H0 - bounded local depth')
LINE_COLORS = ('#333333', '#d95f02', '#1b9e77')
E_LIMITS = (-.35, 1.2)
LIGHT = np.array([-.5, -.3, 1.0])
LIGHT /= np.linalg.norm(LIGHT)
STAGES = ('javac', 'check', 'probe')
REQUIRED = ('comparison.png', 'hillshade.png', 'paths.png', 'cut-only.png',
            'path-zoom.png', 'profiles.png', 'bed-detail.png', 'cross-sections.png',
            'statistics.json', 'verification.json', 'outcome.txt')
FOOTER = ('Diagnostic mountain type-space; NOT Minecraft. HS=2; Yproxy=354.66H; '
          'e=.02+1.5H, fixed e[-.35,1.2].\n'
          'Light[-.5,-.3,1], shade=.65+.35*positiveDot; no per-arm normalization. '
          'All supplied traces retained; contract PASS is NOT effect PASS. S1 STOP; no S2/world coverage claim.')


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
    changed = [k for k in sorted(expected.keys() | actual.keys())
               if expected.get(k) != actual.get(k)]
    require(not changed, label + ' drift: ' + ', '.join(changed[:20]))


def locked_snapshot():
    result = tree_hashes(ROOT / 'src/main', ROOT)
    result.update(tree_hashes(BASE, ROOT))
    result.update(hashes([ROOT / p for p in (*OLD_SOURCES, PALETTE)], ROOT))
    require(sha(BASE / 'base-raw.f64be') == RAW_SHA, 'Old raw SHA drift')
    require(sha(BASE / 'after-height.f64be') == OLD_SHA, 'Old complete-query SHA drift')
    return result


def source_snapshot():
    # Conservative sourcepath dependency superset, including implicit javac roots.
    # No plan lock: TodoWrite may update its frontmatter; main agent owns its truth.
    paths = [ROOT / SCRIPT]
    for folder in (MORPH, STANDALONE):
        paths.extend((ROOT / folder).rglob('*.java'))
    return hashes(paths, ROOT)


def output_path(argument, render_only):
    candidate = ROOT / argument
    require(not candidate.is_symlink(), 'OUT must not be a symlink')
    out = candidate.resolve()
    require(out.parent == (ROOT / 'build').resolve(), 'OUT must be a direct build/<NEW> child')
    require(out.is_dir() if render_only else not out.exists(),
            '--render-only needs this runner\'s frozen run' if render_only else 'Refusing existing OUT')
    require(out != BASE.parent, 'Old final output is read-only')
    return out


def preflight():
    missing = [p.as_posix() for p in (*SOURCES, *OLD_SOURCES) if not (ROOT / p).is_file()]
    require(not missing, 'Missing Java sources; nothing executed: ' + ', '.join(missing))
    return locked_snapshot(), source_snapshot()


def execution_snapshot(out):
    names = ('commands.json', 'validation.log', 'locked-before.json',
             'source-lock.json', 'data-lock.json')
    result = hashes([out / name for name in names], out)
    for folder in ('classes', 'logs'):
        result.update(tree_hashes(out / folder, out))
    return result


def validate_commands(out, commands):
    require([c['stage'] for c in commands] == list(STAGES) and
            all(c['exitCode'] == 0 for c in commands), 'Java stages did not all return zero')
    for command in commands:
        require(sha(out / command['log']) == command['logSha256'], 'Command log SHA drift')
        if command['stage'] == 'check':
            text = (out / command['log']).read_text(encoding='utf-8', errors='replace')
            require(not re.search(r'(?im)^\s*(?:FAIL\b|Exception in thread|java\.lang\.AssertionError)',
                                  text), 'Check logged a failure despite exit zero')


def prepare(out, render_only):
    locked, sources = preflight()
    context = dict(locked=locked, sources=sources, sampledSources=sources, commands=[],
                   data=None, record=None, renderOnly=render_only)
    if not render_only:
        out.mkdir(parents=True, exist_ok=False)
        save_json(out / 'locked-before.json', locked)
        save_json(out / 'source-lock.json', sources)
        save_json(out / 'commands.json', [])
        return context
    record = load_json(out / 'run-record.json')
    require(record.get('kind') == KIND and record.get('state') == 'data-ready',
            'Not this runner\'s frozen data-ready run; refusing writes')
    same_hashes(load_json(out / 'locked-before.json'), locked, 'Main/old sources/data/palette')
    sampled = load_json(out / 'source-lock.json')
    same_hashes({k: v for k, v in sampled.items() if k != SCRIPT},
                {k: v for k, v in sources.items() if k != SCRIPT}, 'Java sourcepath')
    same_hashes(record['immutableOutputs'], execution_snapshot(out), 'Execution evidence')
    data = load_json(out / 'data-lock.json')
    same_hashes(data, tree_hashes(out / 'data', out), 'Probe data')
    commands = load_json(out / 'commands.json')
    validate_commands(out, commands)
    context.update(data=data, record=record, commands=commands, sampledSources=sampled)
    return context


def verify_locks(out, context, write_after=False):
    current = locked_snapshot()
    if write_after:
        save_json(out / 'locked-after.json', current)
    same_hashes(context['locked'], current, 'All main/old sources/data/palette')
    same_hashes(context['sources'], source_snapshot(), 'Active sourcepath/renderer')
    if context['data'] is not None:
        same_hashes(context['data'], tree_hashes(out / 'data', out), 'Probe data')
    if context['record'] is not None:
        same_hashes(context['record']['immutableOutputs'], execution_snapshot(out), 'Execution')
        require(context['record'] == load_json(out / 'run-record.json'), 'Run record drift')


class CommandFailed(RuntimeError):
    def __init__(self, stage, code):
        super().__init__('%s exit=%s; see logs/ and validation.log' % (stage, code))
        self.code = code


def echo(block):
    if hasattr(sys.stdout, 'buffer'):
        sys.stdout.buffer.write(block)
        sys.stdout.buffer.flush()
    else:
        sys.stdout.write(block.decode('utf-8', errors='replace'))
        sys.stdout.flush()


def run_command(out, commands, stage, args):
    log = 'logs/%02d-%s.log' % (len(commands) + 1, stage)
    record = dict(stage=stage, argv=args, cwd=str(ROOT), startedUtc=utc(),
                  exitCode=None, status='running', log=log)
    commands.append(record)
    save_json(out / 'commands.json', commands)
    (out / 'logs').mkdir(exist_ok=True)
    started, error = time.perf_counter(), None
    with (out / log).open('wb') as raw, (out / 'validation.log').open('ab') as combined:
        def emit(block):
            for stream in (raw, combined):
                stream.write(block)
                stream.flush()
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
            emit(traceback.format_exc().encode('utf-8'))
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


def sample(out, context):
    classes = out / 'classes'
    classes.mkdir()
    run_command(out, context['commands'], 'javac', [
        'javac', '--release', '17', '-encoding', 'UTF-8', '-proc:none', '-Xlint:all', '-Werror',
        '-classpath', str(classes), '-d', str(classes), '-sourcepath',
        os.pathsep.join(('src/diagnostics/java', 'src/main/java')),
        *(p.as_posix() for p in SOURCES)])
    run_command(out, context['commands'], 'check', [
        'java', '-cp', str(classes), 'com.geogenesis.standalone.NoiseFollowingCheck'])
    # Catch a badly wired synthetic Check before spending time on the real probe.
    check_text = (out / context['commands'][-1]['log']).read_text(encoding='utf-8', errors='replace')
    require(not re.search(r'(?im)^\s*(?:FAIL\b|Exception in thread|java\.lang\.AssertionError)',
                          check_text), 'Synthetic Check logged failure')
    run_command(out, context['commands'], 'probe', [
        'java', '-Xmx3g', '-cp', str(classes),
        'com.geogenesis.worldgen.terrain.morph.NoiseFollowingProbe', str(out / 'data')])
    validate_commands(out, context['commands'])
    verify_locks(out, context)
    context['data'] = tree_hashes(out / 'data', out)
    require(context['data'], 'Probe wrote no data')
    save_json(out / 'data-lock.json', context['data'])
    record = dict(kind=KIND, state='data-ready', createdUtc=utc(),
                  immutableOutputs=execution_snapshot(out),
                  sourcepathPolicy='Three explicit roots; old curves implicit sourcepath dependencies; '
                                   'empty external classpath; javac --release 17 -Xlint:all -Werror',
                  jvmEnvironment={k: os.environ.get(k) for k in (
                      'JAVA_HOME', 'JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS', 'JDK_JAVA_OPTIONS', 'CLASSPATH')})
    save_json(out / 'run-record.json', record)
    context['record'] = record


def validate_sampling(meta):
    require(type(meta['size']) is int and meta['size'] == 769 and meta['HS'] == 2,
            'Expected size769 / HS2')
    require(type(meta['seed']) is int and meta['seed'] == SEED, 'Frozen integer seed drift')
    require(meta['originWu'] == ORIGIN and meta['stepWu'] == STEP, 'Frozen window drift')
    reference = load_json(BASE / 'sampling.json')
    for key in ('size', 'originWu', 'stepWu', 'HS', 'seed'):
        require(meta[key] == reference[key], 'Old window differs: ' + key)
    declarations = [meta[k] for k in ('actualMinecraftWorld', 'actualMC') if k in meta]
    require(declarations and all(v is False for v in declarations), 'Must declare NOT actual MC')


def read_array(path, size):
    require(path.stat().st_size == size * size * 8, 'Wrong >f8 array size: ' + str(path))
    values = np.fromfile(path, dtype='>f8').astype(np.float64).reshape(size, size)
    require(np.isfinite(values).all(), 'Non-finite array: ' + str(path))
    return values


def point(value, label):
    values = np.asarray(value, dtype=float)
    require(values.shape == (2,) and np.isfinite(values).all(), 'Invalid world [x,z]: ' + label)
    return values


def validate_traces(document):
    require(isinstance(document, dict) and isinstance(document.get('traces'), list),
            'traces.json requires root {traces:[...]}')
    items, ids = document['traces'], []
    require(items, 'No trace candidates; cannot silently omit all failed traces')
    for item in items:
        require(isinstance(item, dict), 'Invalid trace record')
        ids.append(str(item['id']))
        require(isinstance(item['status'], str) and item['status'], 'Missing trace status')
        point(item['seedWu'], 'seedWu')
        points = np.asarray(item['points'], dtype=float)
        require(points.ndim == 2 and points.shape[1] == 2 and len(points) >= 1 and
                np.isfinite(points).all(), 'Trace points must be WORLD [x,z] including failures')
        length = item['lengthWu']
        require(isinstance(length, (float, int)) and not isinstance(length, bool) and
                np.isfinite(length) and length >= 0, 'Invalid lengthWu')
        require('traceSamples' in item, 'Missing traceSamples evidence')
    require(len(ids) == len(set(ids)), 'Duplicate trace IDs')
    return items


def read_csv_groups(path, cross=False):
    header = CROSS_HEADER if cross else PROFILE_HEADER
    groups = {}
    with path.open('r', encoding='utf-8-sig', newline='') as stream:
        reader = csv.reader(stream)
        require(next(reader, None) == header, path.name + ' exact header mismatch')
        for row in reader:
            require(len(row) == len(header) and row[0], 'Invalid CSV row: ' + path.name)
            values = np.asarray([float(v) for v in row[1:]], dtype=float)
            require(np.isfinite(values).all(), 'Non-finite direct final-function CSV')
            key = (row[0], float(values[0])) if cross else row[0]
            groups.setdefault(key, []).append(values)
    require(groups, 'Empty direct CSV: ' + path.name)
    result = {key: np.asarray(rows) for key, rows in groups.items()}
    axis = 1 if cross else 0
    for key, values in result.items():
        require(len(values) >= 2 and (np.diff(values[:, axis]) > 0).all(),
                'CSV sample coordinate must strictly increase: ' + str(key))
    return result


def equal_bits(a, b):
    return np.asarray(a, dtype=np.float64).view(np.uint64) == np.asarray(b, dtype=np.float64).view(np.uint64)


def contract_counts(arrays):
    carrier, cut, depth = (arrays[k] for k in ('carrier-height', 'cut-height', 'cut-depth'))
    delta = carrier - cut
    rounding = 2 * np.spacing(np.maximum(1.0, np.abs(carrier)))
    zero = depth == 0
    return dict(totalPixels=int(depth.size), cutAboveCarrier=int(np.count_nonzero(cut > carrier)),
                negativeDepth=int(np.count_nonzero(depth < 0)),
                depthAboveCap=int(np.count_nonzero(depth > CAP)),
                deltaAboveCapExact=int(np.count_nonzero(delta > CAP)),
                deltaAboveCapBeyondRounding=int(np.count_nonzero(delta > CAP + rounding)),
                depthVsSubtractionBeyondRounding=int(np.count_nonzero(np.abs(delta - depth) > rounding)),
                zeroDepthPixels=int(np.count_nonzero(zero)),
                zeroDepthChangedBits=int(np.count_nonzero(~equal_bits(cut[zero], carrier[zero]))),
                changedPixels=int(np.count_nonzero(~equal_bits(cut, carrier))),
                depthCapH=CAP, deltaRoundingPolicy='2 ULP at max(1,abs(H0)); exact counts retained')


def validate_arrays(out, arrays):
    require(sha(out / 'data/base-raw.f64be') == RAW_SHA, 'Raw SHA mismatch')
    require(sha(out / 'data/old-height.f64be') == OLD_SHA, 'Old complete-query SHA mismatch')
    # Deliberately no rescaling, normalization, f32 round trip or fused multiply-add.
    expected = arrays['base-raw'] * AMP + 0.0
    require(equal_bits(expected, arrays['carrier-height']).all(), 'H0 != raw*original amplitude+zero bias EXACT')
    counts = contract_counts(arrays)
    for key in ('cutAboveCarrier', 'negativeDepth', 'depthAboveCap', 'deltaAboveCapBeyondRounding',
                'depthVsSubtractionBeyondRounding', 'zeroDepthChangedBits'):
        require(counts[key] == 0, 'Array contract failure %s=%s' % (key, counts[key]))
    return counts


def load_dataset(out):
    data = out / 'data'
    names = (*REPORTS, 'profiles.csv', 'cross-sections.csv',
             *(name + '.f64be' for name in FIELDS))
    require(all((data / name).is_file() for name in names), 'Incomplete probe data contract')
    reports = {name: load_json(data / name) for name in REPORTS}
    require(all(isinstance(v, dict) for v in reports.values()), 'JSON reports must be root objects')
    meta = reports['sampling.json']
    validate_sampling(meta)
    arrays = {name: read_array(data / (name + '.f64be'), 769) for name in FIELDS}
    counts = validate_arrays(out, arrays)
    traces = validate_traces(reports['traces.json'])
    # Bed verdicts and trace statuses are NOT preconditions for rendering.
    profiles = read_csv_groups(data / 'profiles.csv')
    sections = read_csv_groups(data / 'cross-sections.csv', cross=True)
    ids = {str(t['id']) for t in traces}
    require(all(key[0] in ids for key in sections), 'Cross-section references unknown trace')
    return dict(sampling=meta, arrays=arrays, reports=reports, traces=traces,
                profiles=profiles, sections=sections, pixelContract=counts)


def window_bounds(meta):
    x, z = meta['originWu']
    span = (meta['size'] - 1) * meta['stepWu']
    return x, x + span, z, z + span


def raster_panel(ax, image, meta, bounds, title, mode='gray'):
    x, z = meta['originWu']
    step, n = meta['stepWu'], meta['size']
    extent = (x - step / 2, x + (n - .5) * step,
              z + (n - .5) * step, z - step / 2)
    options = dict(cmap='gray', vmin=.65, vmax=1) if mode == 'gray' else {}
    if mode == 'depth':
        options = dict(cmap='magma', vmin=0, vmax=CAP)
    ax.set_facecolor('#d5d5d5')
    ax.imshow(image, origin='upper', extent=extent, interpolation='nearest', **options)
    ax.set(xlim=bounds[:2], ylim=(bounds[3], bounds[2]), title=title,
           xlabel='world x (wu)', ylabel='world z (wu)', aspect='equal')


def finish(fig, out, name, title, bar=None):
    fig.suptitle('Noise-following subtraction | S0/S1 STOP\n' + title, fontsize=12)
    fig.text(.02, .012, FOOTER, fontsize=8)
    fig.tight_layout(rect=(0, .10, .94 if bar else 1, .90), h_pad=3)
    if bar:
        fig.colorbar(bar[0], cax=fig.add_axes([.955, .25, .012, .5]), label=bar[1])
    fig.savefig(out / name, dpi=150, metadata={'Description': title + '; ' + FOOTER})
    plt.close(fig)


def light(values, step_blocks):
    gz, gx = np.gradient(values * 354.66, step_blocks)
    dot = (-gx * LIGHT[0] - gz * LIGHT[1] + LIGHT[2]) / np.sqrt(1 + gx * gx + gz * gz)
    return .65 + .35 * np.maximum(0, dot)


def palette():
    colors = np.loadtxt(ROOT / PALETTE, delimiter=',', skiprows=1)
    require(colors.ndim == 2 and colors.shape[1] == 4 and np.isfinite(colors).all(), 'Invalid palette')
    require((colors[:, 1:] >= 0).all() and (colors[:, 1:] <= 255).all(), 'Palette RGB range')
    return ListedColormap(colors[:, 1:] / 255)


def render_whole(out, dataset, shades, colors, cmap):
    meta = dataset['sampling']
    for filename, images, gray in (('comparison.png', colors, False), ('hillshade.png', shades, True)):
        fig, axes = plt.subplots(1, 3, figsize=(21, 8))
        for ax, arm, title in zip(axes, ARMS, TITLES):
            raster_panel(ax, images[arm], meta, window_bounds(meta), title, 'gray' if gray else 'rgb')
        bar = (ScalarMappable(norm=Normalize(.65, 1), cmap='gray'), 'fixed light') if gray else (
            ScalarMappable(norm=Normalize(*E_LIMITS), cmap=cmap), 'e=.02+1.5H (unshaded palette)')
        finish(fig, out, filename, 'Same complete frozen window; carrier / old / cut', bar)


def render_paths(out, dataset, shades):
    meta, items = dataset['sampling'], dataset['traces']
    fig, ax = plt.subplots(figsize=(13, 12))
    raster_panel(ax, shades['carrier'], meta, window_bounds(meta), 'All WORLD routes on H0 | location only')
    for index, item in enumerate(items):
        points = np.asarray(item['points'])
        color = plt.get_cmap('tab20')(index % 20)
        ax.plot(points[:, 0], points[:, 1], color=color, linewidth=1,
                label='%s | %s' % (item['id'], item['status']))
        seed = point(item['seedWu'], 'seedWu')
        ax.plot(seed[0], seed[1], 'o', color=color, markersize=3)
        ax.annotate(str(item['id']), seed, fontsize=6, color=color)
    # Large trace sets remain in the report/zoom pages; no success-only legend filter.
    if len(items) <= 24:
        ax.legend(fontsize=6, loc='upper right')
    finish(fig, out, 'paths.png', 'All %d candidates in probe order; statuses are NOT acceptance' % len(items))
    fig, ax = plt.subplots(figsize=(12, 11))
    raster_panel(ax, dataset['arrays']['cut-depth'], meta, window_bounds(meta),
                 'Depth only | fixed 0 .. %.17g H' % CAP, 'depth')
    finish(fig, out, 'cut-only.png', 'No adaptive color scaling; single width/depth budget',
           (ScalarMappable(norm=Normalize(0, CAP), cmap='magma'), 'cut depth H; cap=.02/1.5'))


def short_label(identifier):
    return ':'.join(str(identifier).split(':')[-2:])


def render_zooms(out, dataset, shades):
    items, meta, crops = dataset['traces'], dataset['sampling'], []
    for page, first in enumerate(range(0, len(items), 3)):
        chunk = items[first:first + 3]
        fig, axes = plt.subplots(len(chunk), 3, figsize=(19, 5 * len(chunk)), squeeze=False)
        for row, item in enumerate(chunk):
            x, z = item['seedWu']
            bounds = (x - 100, x + 100, z - 100, z + 100)
            crops.append(dict(id=item['id'], seedWu=item['seedWu'], boundsWu=list(bounds),
                              radiusWu=100, status=item['status']))
            for col, arm in enumerate(ARMS):
                ax = axes[row, col]
                raster_panel(ax, shades[arm], meta, bounds, '')
                ax.set_title('%s\n%s\n%s\nseed +/-100wu; gray outside raster' %
                             (arm, short_label(item['id']), item['status']), fontsize=8)
        filename = 'path-zoom.png' if page == 0 else 'path-zoom-%02d.png' % (page + 1)
        finish(fig, out, filename, 'Every fixed seed crop; NO overlays / reselection / failure filtering',
               (ScalarMappable(norm=Normalize(.65, 1), cmap='gray'), 'fixed light'))
    return crops


def render_csv(out, dataset, cross=False):
    groups = dataset['sections'] if cross else dataset['profiles']
    entries = list(groups.items())
    # One shared H range across every page and all three arms, including failures.
    lo = min(float(v[:, -3:].min()) for v in groups.values())
    hi = max(float(v[:, -3:].max()) for v in groups.values())
    pad = max((hi - lo) * .025, 1e-6)
    statuses = {str(t['id']): t['status'] for t in dataset['traces']}
    stem = 'cross-sections' if cross else 'profiles'
    for page, first in enumerate(range(0, len(entries), 6)):
        chunk = entries[first:first + 6]
        fig, axes = plt.subplots(len(chunk), 1, figsize=(16, 3.5 * len(chunk) + 2), squeeze=False)
        for ax, (key, values) in zip(axes[:, 0], chunk):
            axis = 1 if cross else 0
            for col, (arm, color) in enumerate(zip(ARMS, LINE_COLORS)):
                ax.plot(values[:, axis], values[:, -3 + col], label=arm, color=color, linewidth=1)
            label = ('%s | %s | s=%.9g wu' % (key[0], statuses[key[0]], key[1])) if cross else str(key)
            coord = values[:, 2:4] if cross else values[:, 1:3]
            ax.set(title='%s | WORLD (%.4f, %.4f) -> (%.4f, %.4f)' % (label, *coord[0], *coord[-1]),
                   xlabel=('offset_wu' if cross else 's_wu') + ' | direct Java final-function samples',
                   ylabel='H (shared range)', ylim=(lo - pad, hi + pad),
                   xlim=(values[0, axis], values[-1, axis]))
            ax.legend(fontsize=8)
            ax.grid(alpha=.2)
        filename = stem + ('' if page == 0 else '-%02d' % (page + 1)) + '.png'
        finish(fig, out, filename, 'All supplied direct CSV groups in first-appearance order; no image interpolation')


def render_bed_detail(out, dataset):
    # Exact trace-ID lookup excludes fixed-center cuts without selecting on status.
    items, records = dataset['traces'], []
    for page, first in enumerate(range(0, len(items), 4)):
        chunk = items[first:first + 4]
        fig, axes = plt.subplots(len(chunk), 2, figsize=(16, 3.8 * len(chunk) + 2), squeeze=False)
        for row, item in enumerate(chunk):
            values = dataset['profiles'][str(item['id'])]
            length = float(item['lengthWu'])
            require(np.isclose(values[-1, 0], length, rtol=0, atol=1e-9),
                    'Bed-detail profile must retain the actual route endpoint')
            tail_start = max(0.0, length - min(16.0, length))
            tail = values[values[:, 0] >= tail_start]
            require(len(tail) > 0, 'Missing tail samples: ' + str(item['id']))
            panels = []
            for col, (part, label) in enumerate(((values, 'Full route'), (tail, 'Tail min(16,L) wu'))):
                ax = axes[row, col]
                for index, name, color in ((3, 'H0', LINE_COLORS[0]), (5, 'cut', LINE_COLORS[2])):
                    ax.plot(part[:, 0], part[:, index], label=name, color=color, linewidth=1,
                            marker='.', markersize=1.5)
                lo, hi = float(part[:, [3, 5]].min()), float(part[:, [3, 5]].max())
                pad = max((hi - lo) * .05, 1e-7)
                limits = (lo - pad, hi + pad)
                ax.set(xlabel='s_wu | all direct CSV samples', ylabel='Actual H (not normalized)',
                       ylim=limits)
                ax.set_title('%s | %s\n%s' % (label, short_label(item['id']), item['status']), fontsize=8)
                if col == 1 and length > tail_start:
                    ax.set_xlim(tail_start, length)
                ax.ticklabel_format(axis='y', style='plain', useOffset=False)
                ax.grid(alpha=.2)
                ax.legend(fontsize=8)
                panels.append(dict(panel=label, samples=len(part), hLimits=list(limits)))
            records.append(dict(id=item['id'], status=item['status'], lengthWu=length,
                                tailStartWu=tail_start, endpointRetained=True, panels=panels))
        filename = 'bed-detail.png' if page == 0 else 'bed-detail-%02d.png' % (page + 1)
        finish(fig, out, filename, 'H0 / cut only; independent actual-H axes per panel; ALL tail points, no failure filter')
    return dict(source='data/profiles.csv; exact trace IDs, fixed-center cuts excluded',
                oldArmDrawn=False, normalized=False, yAxes='Independent actual H per route/panel; no offset',
                tailRule='s_wu >= max(0,L-min(16,L)); every supplied tail point including endpoint',
                traces=records)


def render(out, dataset):
    meta, cmap = dataset['sampling'], palette()
    shades = {arm: light(dataset['arrays'][arm + '-height'], meta['stepWu'] * 2) for arm in ARMS}
    colors = {arm: cmap(Normalize(*E_LIMITS, clip=True)(
        .02 + 1.5 * dataset['arrays'][arm + '-height']))[..., :3] * shades[arm][..., None] for arm in ARMS}
    render_whole(out, dataset, shades, colors, cmap)
    render_paths(out, dataset, shades)
    crops = render_zooms(out, dataset, shades)
    render_csv(out, dataset)
    render_csv(out, dataset, cross=True)
    bed_detail = render_bed_detail(out, dataset)
    return dict(bedDetail=bed_detail, HS=2, yProxyFactor=354.66, lightXZu=[-.5, -.3, 1], shade='.65+.35*positiveDot',
                eDefinition='.02+1.5H', eLimits=list(E_LIMITS), grayLimits=[.65, 1],
                depthLimitsH=[0, CAP], noPerArmNormalization=True, interpolation='nearest',
                palette=PALETTE.as_posix(), paletteSha256=sha(ROOT / PALETTE),
                seedCrops=crops, cropReselection=False, traceOrder=[t['id'] for t in dataset['traces']],
                profilesOrder=list(dataset['profiles']), crossSectionsOrder=[list(k) for k in dataset['sections']])


def distribution(values):
    values = np.asarray(values)
    quantiles = [0, .01, .05, .5, .9, .95, .99, .999, 1]
    names = ('min', 'p01', 'p05', 'p50', 'p90', 'p95', 'p99', 'p999', 'max')
    result = dict(zip(names, map(float, np.quantile(values, quantiles))))
    result.update(mean=float(values.mean()), std=float(values.std()),
                  rms=float(np.sqrt(np.mean(values * values))))
    return result


def correlation(a, b):
    if a.std() == 0 or b.std() == 0:
        return None
    return float(np.corrcoef(a.ravel(), b.ravel())[0, 1])


def block_means(values, width):
    # Partial edge blocks retained and reported: no dropping the last raster row/col.
    rows = np.arange(0, values.shape[0], width)
    cols = np.arange(0, values.shape[1], width)
    sums = np.add.reduceat(np.add.reduceat(values, rows, axis=0), cols, axis=1)
    counts = np.diff(np.append(rows, values.shape[0]))[:, None] * np.diff(
        np.append(cols, values.shape[1]))[None, :]
    return (sums / counts).ravel()


def geometry_support(dataset):
    # Independent conservative tube-union check, not a reimplementation of end fades.
    # Every supplied route participates, even if its final bed is FAIL or no cut was used.
    meta = dataset['sampling']
    axis = np.arange(meta['size']) * meta['stepWu']
    xx, zz = np.meshgrid(axis + meta['originWu'][0], axis + meta['originWu'][1])
    inside = np.zeros(xx.shape, dtype=bool)
    for item in dataset['traces']:
        points = np.asarray(item['points'], dtype=float)
        pairs = zip(points[:-1], points[1:]) if len(points) > 1 else [(points[0], points[0])]
        for a, b in pairs:
            xmin, xmax = min(a[0], b[0]) - WIDTH, max(a[0], b[0]) + WIDTH
            zmin, zmax = min(a[1], b[1]) - WIDTH, max(a[1], b[1]) + WIDTH
            mask = (xx >= xmin) & (xx <= xmax) & (zz >= zmin) & (zz <= zmax)
            if not mask.any():
                continue
            dx, dz = b - a
            norm = dx * dx + dz * dz
            t = np.clip(((xx[mask] - a[0]) * dx + (zz[mask] - a[1]) * dz) / norm, 0, 1) if norm else 0
            distance2 = (xx[mask] - a[0] - t * dx) ** 2 + (zz[mask] - a[1] - t * dz) ** 2
            inside[mask] |= distance2 < WIDTH * WIDTH
    arrays = dataset['arrays']
    same = equal_bits(arrays['carrier-height'], arrays['cut-height'])
    return dict(radiusWu=WIDTH, tubePixels=int(np.count_nonzero(inside)),
                outsidePixels=int(np.count_nonzero(~inside)),
                outsideChangedBits=int(np.count_nonzero(~inside & ~same)),
                outsideNonzeroDepth=int(np.count_nonzero(~inside & (arrays['cut-depth'] != 0))),
                interpretation='Conservative WORLD polyline tube union, ALL candidates; end-fade support may be smaller')


def raw_peaks(dataset):
    raw, meta = dataset['arrays']['base-raw'], dataset['sampling']
    local = np.ones(raw.shape, dtype=bool)
    for dz in (-1, 0, 1):
        for dx in (-1, 0, 1):
            if dx or dz:
                local &= raw >= np.roll(raw, (dz, dx), axis=(0, 1))
    local[[0, -1], :] = False
    local[:, [0, -1]] = False
    coords = np.argwhere(local)
    # Report ALL raster local maxima in row-major order, not a pretty-location selector.
    arrays, records = dataset['arrays'], []
    for z, x in coords:
        records.append(dict(row=int(z), col=int(x),
                            worldWu=[meta['originWu'][0] + int(x) * STEP,
                                     meta['originWu'][1] + int(z) * STEP],
                            raw=float(raw[z, x]), carrierH=float(arrays['carrier-height'][z, x]),
                            oldH=float(arrays['old-height'][z, x]), cutH=float(arrays['cut-height'][z, x]),
                            cutDepthH=float(arrays['cut-depth'][z, x])))
    at = np.unravel_index(int(np.argmax(raw)), raw.shape)
    return dict(definition='Interior 8-neighbor >= raster maxima; not analytic/continuous peaks',
                count=len(records), globalMaximumRowCol=list(map(int, at)),
                globalMaximumRaw=float(raw[at]), allPeaks=records)


def statistics(out, dataset):
    arrays, meta = dataset['arrays'], dataset['sampling']
    carrier = arrays['carrier-height']
    differences = {}
    for arm in ('old', 'cut'):
        values = arrays[arm + '-height']
        delta = values - carrier
        scales = []
        for width in (1, 8, 32, 128, 256, 769):
            a, b, d = (block_means(v, width) for v in (values, carrier, delta))
            scales.append(dict(blockWidthPixels=width, nominalWidthWu=width * meta['stepWu'],
                               blocks=len(d), partialEdgesIncluded=True,
                               armCarrierCorrelation=correlation(a, b), differenceH=distribution(d)))
        differences[arm + 'MinusCarrier'] = dict(correlation=correlation(values, carrier),
                                                differenceH=distribution(delta), blockMeans=scales)
    support = geometry_support(dataset)
    result = dict(differences=differences, pixelBudget=dataset['pixelContract'], support=support,
                  cutDepthH=distribution(arrays['cut-depth']), rawPeaks=raw_peaks(dataset),
                  traceStatuses=[dict(id=t['id'], status=t['status'], lengthWu=t['lengthWu'])
                                 for t in dataset['traces']],
                  probeReports=dataset['reports'],
                  policy='Facts only. No star-count/visual gate; no world coverage or S2 claim.')
    save_json(out / 'statistics.json', result)
    require(support['outsideChangedBits'] == 0 and support['outsideNonzeroDepth'] == 0,
            'Nonzero erosion outside all fixed R8 WORLD route tubes')
    return result


def verdict_facts(dataset):
    # Preserve the entire main-probe verdict, never infer success from Check/exit/plots.
    bed = dataset['reports']['bed-validation.json']
    checks = dataset['reports']['checks.json']
    return dict(contractPassedAsReported=checks.get('passed'), bedValidation=bed,
                finalBedVerdictSource='data/bed-validation.json (probe-owned, retained verbatim)',
                directCsvSource='Java probe direct final functions; never renderer interpolation',
                directnessIndependentlyReevaluated=False,
                zeroDepthFunctionEvidenceSource='data/checks.json + check log (probe/Check-owned)',
                zeroDepthFunctionIndependentlyReevaluated=False)


def output_hashes(out):
    return hashes((p for p in out.rglob('*') if p.is_file() and p.name != 'manifest.json'), out)


def effect_gate(dataset, require_bed):
    passed = dataset['reports']['bed-validation.json'].get('passed')
    require(type(passed) is bool, 'bed-validation.json passed must be a boolean')
    return dict(required=require_bed, passed=passed, source='data/bed-validation.json:passed',
                status='passed' if passed else 'failed', independentOfIntegrity=True,
                exitCodeAfterEvidence=2 if require_bed and not passed else 0)


def write_outcome(out, context, dataset, stats, gate):
    bed = dataset['reports']['bed-validation.json']
    rises = sum(t.get('finalRises', 0) for t in bed.get('traces', []))
    tail_rises = sum(t.get('finalTailRises', 0) for t in bed.get('traces', []))
    lines = [
        'S1 已停止，等待主代理终审；本轮不调参、不进入 S2。',
        '范围：纯山地诊断类型空间，不是游戏实机验收。',
        '本次仅重绘冻结数据，未运行 Java。' if context['renderOnly'] else '编译、Check、Probe 已完成，详见原始日志。',
        '完整性通过：输入锁、数据锁、数组契约与必需图件均已核验。',
        '原始 raw 和旧高度 SHA 匹配；H0 为原 raw 乘原幅度加零偏置。',
        'Java、main、旧基准数据与配色未改动。',
        '固定半宽 8wu、深度上限 0.02/1.5 H，未归一化或调参。',
        '全部 %d 条路径及其尾部点保留，失败状态不筛除。' % len(dataset['traces']),
        '床面验证 passed=%s；与完整性结果独立。' % str(gate['passed']).lower(),
        '最终反坡采样 %d 处，其中尾部反坡 %d 处；不是成功效果。' % (rises, tail_rises),
        '床面明细见 bed-detail.png；完整统计与判据保留在 JSON。',
        '要求床面通过=%s；留证与 manifest 完成后退出码为 %d。' %
        (str(gate['required']).lower(), gate['exitCodeAfterEvidence']),
    ]
    require(len(lines) == 12, 'Outcome must remain a concise 12-line report')
    (out / 'outcome.txt').write_text('\n'.join(lines) + '\n', encoding='utf-8')


def write_reports(out, context, dataset, stats, settings, gate):
    verdicts = verdict_facts(dataset)
    verification = dict(status='array-and-lock-integrity-only-NOT-effect-PASS',
                        allMainUnchanged=True, oldCurveAndChecksUnchanged=True,
                        oldFinalDataUnchanged=True, paletteUnchanged=True, probeDataUnchanged=True,
                        javaStagesReturnedZero=True, rawSha256=sha(out / 'data/base-raw.f64be'),
                        oldSha256=sha(out / 'data/old-height.f64be'),
                        carrierDefinition='EXACT raw*.699999988079071+0.0; no rescaling',
                        carrierBitExact=True, arrayDtype='>f8', shape=[769, 769], order='[z,x]',
                        pixelContract=stats['pixelBudget'], outsideSupport=stats['support'],
                        profilesHeader=PROFILE_HEADER, crossSectionsHeader=CROSS_HEADER,
                        starCountGate=False, morphologicalAcceptance=False, visualReviewRequired=True,
                        infiniteWorldCoverageProven=False, stageStop='S1; await main-agent final review; no S2',
                        effectGate=gate, integrityPassed=True, **verdicts)
    save_json(out / 'verification.json', verification)
    write_outcome(out, context, dataset, stats, gate)
    manifest = dict(kind=KIND, completedUtc=utc(), status='S1-stopped-awaiting-review-not-effect-pass',
                    renderOnly=context['renderOnly'], actualMinecraftWorld=False,
                    commands=context['commands'], runRecord=context['record'],
                    sourcesAtSampling=context['sampledSources'], activeSources=context['sources'],
                    lockedInputHashes=context['locked'], probeDataHashes=context['data'],
                    sampling=dataset['sampling'], reports=dataset['reports'], render=settings,
                    verification=verification, integrity=dict(passed=True, scope='render/locks/array contract'),
                    effectGate=gate, stageStop='S1; await main-agent final review; no S2',
                    requiredOutputs=list(REQUIRED),
                    planPolicy='Not locked/edited; source-of-truth confirmation remains with main agent',
                    outputHashExcludes=['manifest.json (self-reference)'], outputHashes=output_hashes(out))
    save_json(out / 'manifest.json', manifest)


def record_failure(out, context, error):
    failure = dict(kind=KIND, status='failed-S1-STOP', completedUtc=utc(), error=repr(error),
                   traceback=traceback.format_exc(), commands=context['commands'])
    try:
        verify_locks(out, context, write_after=True)
        failure['lockedInputsUnchanged'] = True
    except Exception as lock_error:
        failure.update(lockedInputsUnchanged=False, lockError=repr(lock_error))
    save_json(out / 'failure.json', failure)
    save_json(out / 'verification.json', failure)
    (out / 'outcome.txt').write_text(
        'FAILED; STOP. No retuning, fallback, S2 or acceptance claim.\n' +
        repr(error) + '\nSee failure.json, commands.json and logs/.\n', encoding='utf-8')
    failure['outputHashExcludes'] = ['manifest.json (self-reference)']
    failure['outputHashes'] = output_hashes(out)
    save_json(out / 'manifest.json', failure)


def execute(out, render_only, require_bed=False):
    # Preflight never creates output or overwrites evidence on missing sources/drift.
    context = prepare(out, render_only)
    try:
        if not render_only:
            sample(out, context)
        dataset = load_dataset(out)
        stats = statistics(out, dataset)
        settings = render(out, dataset)
        verify_locks(out, context, write_after=True)
        require(all((out / p).is_file() and (out / p).stat().st_size for p in REQUIRED
                    if p not in ('verification.json', 'outcome.txt')),
                'Missing required rendered evidence output')
        gate = effect_gate(dataset, require_bed)
        write_reports(out, context, dataset, stats, settings, gate)
        require(all((out / p).is_file() and (out / p).stat().st_size for p in (*REQUIRED, 'manifest.json')),
                'Missing required evidence output')
        manifest = load_json(out / 'manifest.json')
        same_hashes(manifest['outputHashes'], output_hashes(out), 'Completed output manifest')
        print('S1 STOP:', out, '| Integrity PASS; bed.passed=%s; exit=%d' %
              (gate['passed'], gate['exitCodeAfterEvidence']), flush=True)
    except Exception as error:
        record_failure(out, context, error)
        raise
    # Effect failure is a result AFTER complete evidence, never an integrity exception.
    return gate['exitCodeAfterEvidence']


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('out', help='build/<NEW>; frozen run only with --render-only')
    parser.add_argument('--render-only', action='store_true', help='Verify frozen evidence; never run Java')
    parser.add_argument('--require-bed', action='store_true',
                        help='After complete evidence/manifest, exit 2 if bed.passed is false')
    args = parser.parse_args()
    try:
        return execute(output_path(args.out, args.render_only), args.render_only, args.require_bed)
    except Exception as error:
        traceback.print_exc()
        return error.code if isinstance(error, CommandFailed) and error.code > 0 else 1


if __name__ == '__main__':
    sys.exit(main())
