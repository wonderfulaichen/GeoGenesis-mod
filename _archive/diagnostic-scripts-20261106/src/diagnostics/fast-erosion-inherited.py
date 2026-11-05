"""Stage B orchestrator/renderer: isolated type-space shaping, NOT production.

MDK cwd: python -B src/diagnostics/fast-erosion-inherited.py build/<NEWdir>
Optional: python -B src/diagnostics/fast-erosion-inherited.py --render-only build/<Bdir>

The Java probe takes one NEW data-directory argument. It writes sampling.json,
checks.json, performance.json, settings.json, big-endian float64 fields listed in
FIELDS, and holdout/{sampling.json,<HOLDOUT_FIELDS>.f64be}. Settings must expose
V, onset (or t_on), eps (or epsilon), frozen to A calibration; no renderer fitting.
Import is safe: no main execution, data reads, Java processes or output creation.
"""

import argparse
import csv
import importlib.util
import os
from pathlib import Path
import sys
import traceback
from types import FunctionType


def load_adaptive():
    path = Path(__file__).resolve().with_name('fast-erosion-adaptive.py')
    spec = importlib.util.spec_from_file_location('_geogenesis_adaptive_for_b', path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)  # A has an explicit __main__ guard.
    return module


A = load_adaptive()
np, plt = A.np, A.plt
ROOT = Path(__file__).resolve().parents[2]
SCRIPT = Path(__file__).resolve().relative_to(ROOT).as_posix()
KIND = 'inherited-skeleton-type-space-v1'
BASELINE = Path('build/fast-erosion-adaptive-a-20261103-final')
JAVA_SOURCES = [*A.JAVA_SOURCES[:2], A.STANDALONE + 'InheritedSkeletonErosion.java',
                A.STANDALONE + 'InheritedSkeletonCheck.java',
                A.MORPH + 'InheritedSkeletonMountainProbe.java']
ARMS = ('carrier', 'independent', 'inherited')
TITLES = ('Carrier: complete raw, no E', 'Independent A: no layer feedback',
          'Inherited B: beta=1 guide feedback')
ERODED = ('independent', 'inherited', 'zero')
FIELDS = ('base-raw', 'carrier-height', 'independent-height', 'inherited-height',
          'zero-height', 'independent-unitE', 'inherited-unitE', 'zero-unitE')
FIELDS += tuple(arm + '-' + field for arm in ERODED[:2]
                for field in ('activity', 'confidence', 'denominator'))
FIELDS += tuple('%s-layer%d-unitE' % (arm, j) for arm in ERODED[:2] for j in range(4))
HOLDOUT_FIELDS = tuple(arm + '-height' for arm in ARMS) + (
    'independent-unitE', 'inherited-unitE')
REFERENCE_FIELDS = {'base-raw': 'base-raw', 'carrier-height': 'carrier-height',
                    **{'independent-' + field: 'adaptive-' + field for field in
                       ('height', 'unitE', 'activity', 'confidence', 'denominator')}}
REPORTS = ('checks.json', 'performance.json', 'settings.json')
REQUIRED = ('comparison.png', 'hillshade.png', 'star-zoom.png', 'profiles.png',
            'profiles.csv', 'erosion-only.png', 'layers.png', 'holdout.png', 'statistics.json')
FOOTER = ('HS=2; local blocks=2 wu; Yproxy=236.44*1.5*H; e=.02+1.5H, fixed e[-.35,1.2].\n'
          'A light[-.5,-.3,1], shade=.65+.35*positiveDot; all k=1, amplitude=.25; no normalization.\n'
          'B guide inheritance is bounded shaping, NOT evidence of connected drainage; morphology awaits review.')
PROXY_CAVEAT = ('Candidate counts and numerical field changes are operational proxies only. '
                'Neither counts nor correlation establish main/tributary valley morphology or connected drainage.')


def locked_snapshot():
    result = A.locked_snapshot()  # src/main, corrected old data, FastErosion, palette/light inputs.
    baseline = ROOT / BASELINE
    A.require(baseline.is_dir(), 'Missing frozen A directory: ' + str(baseline))
    paths = [p for p in baseline.rglob('*') if p.is_file() and
             p.relative_to(baseline).parts[0] != 'classes']
    paths += [ROOT / path for path in A.JAVA_SOURCES + [A.SCRIPT]]
    result.update(A.file_hashes(paths, ROOT))
    reference = A.load_json(baseline / 'source-lock.json')
    # prepareLayer may be added BEFORE this run; the current kernel is locked in full thereafter.
    for key, value in reference.items():
        if key != A.JAVA_SOURCES[0]:
            A.require(result.get(key) == value, 'Frozen A source drift: ' + key)
    expected = A.load_json(baseline / 'data-lock.json')
    A.same_hashes(expected, A.tree_hashes(baseline / 'data', baseline), 'Frozen A data')
    return result


def source_snapshot(locked):
    result = {key: value for key, value in locked.items() if key.startswith('src/')}
    result.update(A.file_hashes([ROOT / p for p in JAVA_SOURCES + [SCRIPT]], ROOT))
    return result


def prepare(out, render_only):
    if not render_only:
        locked = locked_snapshot()
        sources = source_snapshot(locked)  # Missing parallel Java work fails before creating OUT.
        out.mkdir(parents=True)
        A.save_json(out / 'locked-before.json', locked)
        A.save_json(out / 'source-lock.json', sources)
        A.save_json(out / 'commands.json', [])
        return {'locked': locked, 'sourcesAtSampling': sources, 'activeSources': sources,
                'commands': [], 'data': None, 'record': None, 'renderOnly': False}
    record = A.load_json(out / 'run-record.json')
    A.require(record.get('kind') == KIND and record.get('state') == 'data-ready',
              'Render-only accepts only this B orchestrator\'s frozen sampling run')
    A.same_hashes(record['immutableOutputs'], A.execution_snapshot(out), 'Execution artifacts')
    locked = A.load_json(out / 'locked-before.json')
    A.same_hashes(locked, locked_snapshot(), 'Locked inputs')
    sampled = A.load_json(out / 'source-lock.json')
    current = source_snapshot(locked)
    A.same_hashes({k: v for k, v in sampled.items() if k != SCRIPT},
                  {k: v for k, v in current.items() if k != SCRIPT}, 'Sampling sources')
    data = A.load_json(out / 'data-lock.json')
    A.same_hashes(data, A.tree_hashes(out / 'data', out), 'Sampled data')
    commands = A.load_json(out / 'commands.json')
    A.require([c['stage'] for c in commands] == ['javac', 'check-A', 'check-B', 'probe'] and
              all(c['exitCode'] == 0 for c in commands), 'Java stages did not all pass')
    return {'locked': locked, 'sourcesAtSampling': sampled, 'activeSources': current,
            'commands': commands, 'data': data, 'record': record, 'renderOnly': True}


def verify_locks(out, context, write_after=False):
    locked = locked_snapshot()
    if write_after:
        A.save_json(out / 'locked-after.json', locked)
    A.same_hashes(context['locked'], locked, 'Locked inputs including complete A output (except classes)')
    A.same_hashes(context['activeSources'], source_snapshot(locked), 'Active sources')
    if context['data'] is not None:
        A.same_hashes(context['data'], A.tree_hashes(out / 'data', out), 'Sampled main/holdout data')
    if context['record'] is not None:
        A.same_hashes(context['record']['immutableOutputs'], A.execution_snapshot(out), 'Execution artifacts')


def sample(out, commands):
    classes = out / 'classes'
    classes.mkdir()
    A.run_command(out, commands, 'javac',
                  ['javac', '--release', '17', '-encoding', 'UTF-8', '-proc:none',
                   '-d', str(classes), '-sourcepath', os.pathsep.join(
                       ('src/main/java', 'src/diagnostics/java')), *JAVA_SOURCES])
    for stage, name in (('check-A', 'AdaptiveSkeletonCheck'), ('check-B', 'InheritedSkeletonCheck')):
        A.run_command(out, commands, stage,
                      ['java', '-cp', str(classes), 'com.geogenesis.standalone.' + name])
    A.run_command(out, commands, 'probe',
                  ['java', '-Xmx3g', '-cp', str(classes),
                   'com.geogenesis.worldgen.terrain.morph.InheritedSkeletonMountainProbe',
                   str(out / 'data')])


def freeze_sampling(out, context):
    verify_locks(out, context)
    context['data'] = A.tree_hashes(out / 'data', out)
    A.require(bool(context['data']), 'Probe wrote no data')
    A.save_json(out / 'data-lock.json', context['data'])
    record = {'kind': KIND, 'state': 'data-ready', 'createdUtc': A.utc(),
              'immutableOutputs': A.execution_snapshot(out),
              'jvmEnvironment': {key: os.environ.get(key) for key in
                                 ('JAVA_HOME', 'JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS',
                                  'JDK_JAVA_OPTIONS', 'CLASSPATH')}}
    A.save_json(out / 'run-record.json', record)
    context['record'] = record


def validate_meta(meta, reference, holdout=False):
    keys = ('stepWu', 'HS', 'amplitude', 'shellAmplitude', 'k')
    if not holdout:
        A.validate_sampling(meta)
        keys += ('size', 'originWu', 'nativeStepWu', 'spanWu', 'wavelengthsWu', 'seed')
        A.require(meta['beta'] == 1, 'B must use beta=1')
    else:
        A.require(meta['size'] == 257 and meta['seed'] == 12345, 'Expected 257-square holdout seed 12345')
        for key in ('nativeStepWu', 'wavelengthsWu'):
            if key in meta:
                A.require(meta[key] == reference[key], 'Holdout parameter drift: ' + key)
        if 'beta' in meta:
            A.require(meta['beta'] == 1, 'Holdout must use beta=1')
    for key in keys:
        A.require(meta[key] == reference[key], 'Frozen sampling drift: ' + key)
    origin = np.asarray(meta['originWu'], dtype=float)
    A.require(origin.shape == (2,) and np.isfinite(origin).all(), 'Invalid originWu')
    if 'spanWu' in meta:
        A.require(meta['spanWu'] == (meta['size'] - 1) * meta['stepWu'], 'spanWu mismatch')
    A.require(meta.get('actualMinecraftWorld', False) is False, 'Only diagnostic type-space data accepted')


def setting_value(settings, *names):
    found = [settings[key] for key in names if key in settings]
    A.require(bool(found) and all(value == found[0] for value in found),
              'Missing/conflicting setting: ' + '/'.join(names))
    return found[0]


def validate_settings(settings, meta):
    reference = A.load_json(ROOT / BASELINE / 'data/calibration.json')
    actual = {'V': setting_value(settings, 'V'),
              't_on': setting_value(settings, 'onset', 't_on'),
              'epsilon': setting_value(settings, 'eps', 'epsilon')}
    for key, value in actual.items():
        A.require(value == reference[key], 'Settings must exactly preserve frozen A calibration: ' + key)
    for key in ('amplitude', 'shellAmplitude', 'k', 'beta', 'wavelengthsWu'):
        if key in settings:
            A.require(settings[key] == meta[key], 'Settings/sampling disagreement: ' + key)
    for key, expected in (('chi', reference['chi']), ('gain', .5), ('gainSum', 1.875)):
        if key in settings:
            A.require(settings[key] == expected, 'Frozen setting changed: ' + key)
    A.require(settings.get('rendererFitsParameters', False) is False, 'Renderer parameter fitting forbidden')
    return {'matchesFrozenA': True, 'values': actual, 'rendererFitsParameters': False,
            'calibrationSha256': A.sha(ROOT / BASELINE / 'data/calibration.json')}


def check_residual(actual, expected, label):
    residual = float(np.max(np.abs(actual - expected)))
    tolerance = float(8 * np.finfo(float).eps * max(1.0, np.max(np.abs(expected))))
    A.require(residual <= tolerance, 'Composition failed: ' + label)
    return {'maxAbsResidual': residual, 'absoluteTolerance': tolerance}


def verify_arrays(data, arrays, meta):
    identities, composition = {}, {}
    for target, source in REFERENCE_FIELDS.items():
        before = ROOT / BASELINE / 'data' / (source + '.f64be')
        current = data / (target + '.f64be')
        expected, actual = A.sha(before), A.sha(current)
        A.require(expected == actual, 'Bit identity with frozen A failed: ' + target)
        identities[target] = {'reference': before.relative_to(ROOT).as_posix(),
                              'referenceSha256': expected, 'sha256': actual, 'bitIdentical': True}
    A.require(identities['base-raw']['sha256'] == A.RAW_SHA, 'Frozen base SHA changed')
    base = arrays['base-raw']
    for arm in ('carrier', *ERODED):
        unit = np.zeros_like(base) if arm == 'carrier' else arrays[arm + '-unitE']
        A.require(np.max(np.abs(unit)) <= 1 + 1e-12, 'Unbounded unitE: ' + arm)
        expected = (base + meta['amplitude'] * unit) * meta['shellAmplitude']
        composition[arm] = check_residual(arrays[arm + '-height'], expected, arm)
    zero = {}
    for path in sorted(data.glob('zero-*.f64be')):
        other = data / path.name.replace('zero-', 'independent-', 1)
        actual = A.sha(path)
        A.require(actual == A.sha(other), 'beta=0 must be bit-identical: ' + path.name)
        zero[path.name] = {'sha256': actual, 'independentSha256': A.sha(other), 'bitIdentical': True}
    A.require(len(zero) >= 2, 'Missing beta=0 exported fields')
    first = [A.sha(data / (arm + '-layer0-unitE.f64be')) for arm in ERODED[:2]]
    A.require(first[0] == first[1], 'Layer0 must be bit-identical in A and B')
    geometry = [A.sha(data / (arm + '-denominator.f64be')) for arm in ERODED[:2]]
    A.require(geometry[0] == geometry[1], 'Inheritance must not change geometric denominators')
    layers = {}
    for arm in ERODED[:2]:
        for name in ('activity', 'confidence'):
            value = arrays[arm + '-' + name]
            A.require(value.min() >= -1e-12 and value.max() <= 1 + 1e-12, 'Expected [0,1]: ' + arm + name)
        A.require((arrays[arm + '-denominator'] > 0).all(), 'Non-positive denominator: ' + arm)
        combined = np.zeros_like(base)
        for j in range(4):
            layer = arrays['%s-layer%d-unitE' % (arm, j)]
            A.require(np.max(np.abs(layer)) <= 1 + 1e-12, 'Unbounded layer: ' + arm + str(j))
            combined += (0.5 ** j) * layer
        layers[arm] = check_residual(arrays[arm + '-unitE'], combined / 1.875, arm + ' layer sum')
    return {'frozenAIdentity': identities, 'betaZeroAllExportedArrays': zero,
            'layer0': {'bitIdentical': True, 'sha256': first[0]}, 'composition': composition,
            'denominatorGeometry': {'bitIdentical': True, 'sha256': geometry[0]},
            'formula': 'H=(baseRaw+.25*unitE)*shell; k=1', 'gainHalfNormalizedLayerSum': layers}


def validate_probe_checks(value, path='checks'):
    if isinstance(value, dict):
        for key, item in value.items():
            name = key.lower()
            label = path + '.' + key
            if name.endswith('differentbits'):
                A.require(item == 0, 'Probe bit check failed: ' + label)
            if name in ('passed', 'allpassed'):
                A.require(item is True, 'Probe failed: ' + label)
            if name == 'status':
                A.require(str(item).lower() not in ('fail', 'failed', 'error'), 'Probe failed: ' + label)
            validate_probe_checks(item, label)
    elif isinstance(value, list):
        for i, item in enumerate(value):
            validate_probe_checks(item, path + '[%d]' % i)


def load_dataset(out):
    data = out / 'data'
    reference = A.load_json(ROOT / BASELINE / 'data/sampling.json')
    meta = A.load_json(data / 'sampling.json')
    validate_meta(meta, reference)
    arrays = {name: A.read_array(data / (name + '.f64be'), meta['size']) for name in FIELDS}
    reports = {name: A.load_json(data / name) for name in REPORTS}
    A.require(all(isinstance(value, dict) and value for value in reports.values()), 'Empty probe report')
    validate_probe_checks(reports['checks.json'])
    verification = verify_arrays(data, arrays, meta)
    verification['settings'] = validate_settings(reports['settings.json'], meta)
    folder = data / 'holdout'
    hold_meta = A.load_json(folder / 'sampling.json')
    validate_meta(hold_meta, reference, holdout=True)
    hold_arrays = {name: A.read_array(folder / (name + '.f64be'), hold_meta['size'])
                   for name in HOLDOUT_FIELDS}
    checks = {}
    for arm in ERODED[:2]:
        unit = hold_arrays[arm + '-unitE']
        A.require(np.max(np.abs(unit)) <= 1 + 1e-12, 'Unbounded holdout E: ' + arm)
        expected = hold_arrays['carrier-height'] + hold_meta['amplitude'] * hold_meta['shellAmplitude'] * unit
        checks[arm] = check_residual(hold_arrays[arm + '-height'], expected, 'holdout ' + arm)
    hold_reports = {name: A.load_json(folder / name) for name in ('checks.json', 'settings.json')
                    if (folder / name).is_file()}
    validate_probe_checks(hold_reports.get('checks.json', {}), 'holdout.checks')
    hold_settings = (validate_settings(hold_reports['settings.json'], dict(meta, **hold_meta))
                     if 'settings.json' in hold_reports else None)
    verification['holdout'] = {'seed': 12345, 'composition': checks, 'settings': hold_settings,
                                'requiresFrozenABaselineSha': False}
    return {'sampling': meta, 'arrays': arrays, 'reports': reports, 'verification': verification,
            'holdout': {'sampling': hold_meta, 'arrays': hold_arrays, 'reports': hold_reports}}


def arm_statistics(arrays, meta, arm, detector, crops=False):
    height = arrays[arm + '-height']
    unit = np.zeros_like(height) if arm == 'carrier' else arrays[arm + '-unitE']
    delta = height - arrays['carrier-height']
    candidates, score = detector(height, A.THRESHOLD)
    erosion = A.distribution(unit)
    gradient = A.gradient_summary(unit, meta['stepWu'])
    proxy = {'candidatePixels': int(candidates.sum()), 'candidateFraction': float(candidates.mean()),
             'threshold': A.THRESHOLD, 'meaning': PROXY_CAVEAT,
             'hessianPerOutputPixelSquared': A.distribution(score[8:-8, 8:-8])}
    if crops:
        step = meta['stepWu'] * meta['HS']
        proxy['candidatePixelsByCrop'] = {name: int(candidates[A.crop_slice(
            height.shape, step, (0, 0), bounds)].sum()) for name, bounds in A.LONG_CROPS + A.STAR_CROPS}
    result = {'height': A.distribution(height), 'heightMinMax': [float(height.min()), float(height.max())],
              'signedMeanDeltaVsCarrierH': float(delta.mean()), 'deltaVsCarrierH': A.distribution(delta),
              'slopeHperWu': A.gradient_summary(height, meta['stepWu']), 'unitE': erosion,
              'E_rms': erosion['rms'], 'E_gradientRmsPerWu': gradient['gradientRms'],
              'unitEgradientPerWu': gradient, 'candidatesProxy': proxy}
    color = .02 + 1.5 * height
    result['colorSaturatedFraction'] = float(((color < A.E_LIMITS[0]) | (color > A.E_LIMITS[1])).mean())
    if arm != 'carrier' and arm + '-activity' in arrays:
        result.update(activity=A.bounded_distribution(arrays[arm + '-activity']),
                      confidence=A.bounded_distribution(arrays[arm + '-confidence']),
                      geometricDenominator=A.distribution(arrays[arm + '-denominator']))
    return result


def difference_statistics(arrays, step):
    result = {}
    for field in ('height', 'unitE', 'activity', 'confidence', 'denominator'):
        if 'inherited-' + field not in arrays:
            continue
        delta = arrays['inherited-' + field] - arrays['independent-' + field]
        result[field] = {'signedDifferenceBminusA': A.distribution(delta),
                         'gradientPerWu': A.gradient_summary(delta, step)}
        if field == 'unitE':
            result[field]['outsideSharedPlotLimitsFraction'] = float((np.abs(delta) > 1).mean())
    return result


def statistics(out, dataset):
    arrays, meta = dataset['arrays'], dataset['sampling']
    detector = A.detector_functions()['detector']
    arms = {arm: arm_statistics(arrays, meta, arm, detector, crops=True) for arm in ARMS}
    layers = {}
    for j in range(4):
        a = arrays['independent-layer%d-unitE' % j]
        b = arrays['inherited-layer%d-unitE' % j]
        ar, br = A.distribution(a)['rms'], A.distribution(b)['rms']
        layers[str(j)] = {'independentRms': ar, 'inheritedRms': br, 'rmsChangeBminusA': br - ar,
                           'fieldDifferenceBminusA': A.distribution(b - a),
                           'independentGradient': A.gradient_summary(a, meta['stepWu']),
                           'inheritedGradient': A.gradient_summary(b, meta['stepWu'])}
    holdout = dataset['holdout']
    report = {'arms': arms, 'BminusA': difference_statistics(arrays, meta['stepWu']), 'layers': layers,
              'holdout': {'sampling': holdout['sampling'], 'arms': {arm: arm_statistics(
                  holdout['arrays'], holdout['sampling'], arm, detector) for arm in ARMS},
                  'BminusA': difference_statistics(holdout['arrays'], holdout['sampling']['stepWu'])},
              'threshold': A.THRESHOLD, 'thresholdRefitted': False,
              'thresholdUnits': 'shell H per OUTPUT PIXEL squared; same absolute legacy threshold as A',
              'gradientDomain': 'np.gradient(value, stepWu); interior excludes 1-pixel boundary',
              'candidateCaveat': PROXY_CAVEAT,
              'activityCaveat': 'm=t/(t+onset) is naturally <1; zero fully-open fraction is not shutdown.',
              'morphologyAccepted': False, 'productionWiring': False}
    A.save_json(out / 'statistics.json', report)
    return report


def b_finish_figure(fig, out, filename, subtitle, footer=FOOTER, colorbar=None):
    # Reuse A's layout implementation with a PRIVATE globals dict, never patch A.
    scope = dict(A.finish_figure.__globals__)
    scope['FIGURE_SCOPE'] = 'Mountain type-space B | NOT PRODUCTION | all k=1, beta=1'
    finish = FunctionType(A.finish_figure.__code__, scope, 'finish_B', A.finish_figure.__defaults__)
    finish(fig, out, filename, subtitle, footer, colorbar)


def render_grid(out, filename, images, meta, crops, gray, cmap, subtitle):
    rows = len(crops)
    step = meta['stepWu'] * meta['HS']
    fig, axes = plt.subplots(rows, 3, figsize=(22, 8 if rows == 1 else 13), squeeze=False)
    for row, (name, bounds) in enumerate(crops):
        for column, (arm, title) in enumerate(zip(ARMS, TITLES)):
            A.image_panel(axes[row, column], images[arm], step, (0, 0), bounds, name + '\n' + title, gray=gray)
    limits, colors = ((.65, 1), 'gray') if gray else (A.E_LIMITS, cmap)
    bar = (A.ScalarMappable(norm=A.Normalize(*limits), cmap=colors),
           'fixed light' if gray else 'e=.02+1.5H (unshaded palette)')
    b_finish_figure(fig, out, filename, subtitle, colorbar=bar)


def height_images(dataset, cmap):
    step = dataset['sampling']['stepWu'] * dataset['sampling']['HS']
    heights = {arm: dataset['arrays'][arm + '-height'] for arm in ARMS}
    shades = {arm: A.light(height, step) for arm, height in heights.items()}
    colors = {arm: cmap(A.Normalize(*A.E_LIMITS, clip=True)(.02 + 1.5 * height))[..., :3]
              * shades[arm][..., None] for arm, height in heights.items()}
    return shades, colors


def whole_crop(meta, name):
    span = (meta['size'] - 1) * meta['stepWu'] * meta['HS']
    return [(name, (0, span, 0, span))]


def render_heights(out, dataset, cmap):
    meta = dataset['sampling']
    shades, colors = height_images(dataset, cmap)
    whole = whole_crop(meta, 'Whole frozen window')
    subtitle = 'Carrier / independent A / inherited B | H=(complete raw+.25E)*shell'
    render_grid(out, 'comparison.png', colors, meta, whole, False, cmap, subtitle)
    render_grid(out, 'hillshade.png', shades, meta, whole, True, cmap, subtitle)
    render_grid(out, 'star-zoom.png', shades, meta, A.STAR_CROPS, True, cmap,
                'Same two A star crops | shared light, no per-crop scaling')
    holdout = dataset['holdout']
    _, colors = height_images(holdout, cmap)
    render_grid(out, 'holdout.png', colors, holdout['sampling'],
                whole_crop(holdout['sampling'], 'Holdout seed 12345'), False, cmap,
                '257-square holdout | frozen main-window parameters; same palette/light')


def render_erosion(out, dataset):
    meta, arrays = dataset['sampling'], dataset['arrays']
    bounds = whole_crop(meta, 'Whole')[0][1]
    step = meta['stepWu'] * meta['HS']
    a, b = [arrays[arm + '-unitE'] for arm in ERODED[:2]]
    fig, axes = plt.subplots(1, 3, figsize=(22, 8))
    for ax, values, title in zip(axes, (a, b, b - a),
                                 ('Independent A: unit E', 'Inherited B: unit E', 'B - A: unit E difference')):
        A.image_panel(ax, values, step, (0, 0), bounds, title, limits=(-1, 1))
    bar = (A.ScalarMappable(norm=A.Normalize(-1, 1), cmap='RdBu_r'), 'unit E / signed difference')
    footer = ('All three panels use identical fixed [-1,1], including B-A; no per-panel normalization.\n'
              'Diagnostic unscaled E, not final H; |B-A|>1 clips (fraction recorded in statistics).\n' + PROXY_CAVEAT)
    b_finish_figure(fig, out, 'erosion-only.png', 'Independent E / inherited E / signed B-A', footer, bar)


def render_layers(out, dataset):
    meta, arrays = dataset['sampling'], dataset['arrays']
    bounds = whole_crop(meta, 'Whole')[0][1]
    step = meta['stepWu'] * meta['HS']
    fig, axes = plt.subplots(2, 4, figsize=(24, 13))
    for row, arm in enumerate(ERODED[:2]):
        for j in range(4):
            A.image_panel(axes[row, j], arrays['%s-layer%d-unitE' % (arm, j)], step,
                          (0, 0), bounds, '%s layer%d | lambda=%.6g wu' %
                          (arm, j, meta['wavelengthsWu'][j]), limits=(-1, 1))
    bar = (A.ScalarMappable(norm=A.Normalize(-1, 1), cmap='RdBu_r'), 'unscaled layer E [-1,1]')
    footer = ('Independent A above; inherited B below. All eight panels fixed [-1,1]; layer0 bit-identical.\n'
              'Layer RMS and signed B-A field changes are recorded, not morphological acceptance.\n' + PROXY_CAVEAT)
    b_finish_figure(fig, out, 'layers.png', 'Four layers, coarse to fine | fixed limits, no normalization', footer, bar)


def render_profiles(out, dataset):
    meta, arrays = dataset['sampling'], dataset['arrays']
    step = meta['stepWu'] * meta['HS']
    position = np.arange(meta['size']) * step
    fig, axes = plt.subplots(3, 1, figsize=(18, 13), sharex=True, sharey=True)
    colors = ('#555555', '#d95f02', '#1b9e77')
    rows = []
    for ax, (name, fixed_axis, fixed_blocks) in zip(axes, A.PROFILE_LINES):
        values = {arm: A.profile_values(arrays[arm + '-height'], fixed_axis, fixed_blocks, step) for arm in ARMS}
        for arm, title, color in zip(ARMS, TITLES, colors):
            ax.plot(position, values[arm], label=title, color=color, linewidth=1)
        varying = 'x' if fixed_axis == 'z' else 'z'
        ax.set(title='%s | %s=%g local blocks' % (name, fixed_axis, fixed_blocks),
               ylabel='shell height H', xlabel='local %s (blocks)' % varying)
        ax.grid(alpha=.25)
        ax.legend(loc='best', fontsize=9)
        for i, value in enumerate(position):
            x, z = (value, fixed_blocks) if fixed_axis == 'z' else (fixed_blocks, value)
            rows.append([name, varying, fixed_axis, fixed_blocks, value, x, z] +
                        [values[arm][i] for arm in ARMS] +
                        [values[arm][i] - values['carrier'][i] for arm in ERODED[:2]] +
                        [values['inherited'][i] - values['independent'][i]])
    low = min(float(arrays[arm + '-height'].min()) for arm in ARMS)
    high = max(float(arrays[arm + '-height'].max()) for arm in ARMS)
    pad = max((high - low) * .025, 1e-6)
    axes[0].set(xlim=(0, position[-1]), ylim=(low - pad, high + pad))
    footer = ('Same A cuts; all three plots share x/y axes. Exact cuts by A linear interpolation; no shifts.\n'
              'Carrier H=raw*shell, not unshelled raw. CSV includes signed B-A; all k=1.\n' + PROXY_CAVEAT)
    b_finish_figure(fig, out, 'profiles.png', 'Three fixed cuts: carrier / independent / inherited', footer)
    header = ['profile', 'varying_axis', 'fixed_axis', 'fixed_local_blocks', 'position_local_blocks',
              'local_x_blocks', 'local_z_blocks', 'carrier_H', 'independent_H', 'inherited_H',
              'independent_delta_vs_carrier_H', 'inherited_delta_vs_carrier_H', 'B_minus_A_H']
    with (out / 'profiles.csv').open('w', encoding='utf-8', newline='') as stream:
        writer = csv.writer(stream)
        writer.writerow(header)
        for row in rows:
            writer.writerow([format(v, '.17g') if isinstance(v, (float, np.floating)) else v for v in row])


def render_settings():
    settings = dict(A.render_settings(False))
    for key in ('erosionOnlyHillshadeInput', 'longSeamCropsLocalBlocks', 'optionalColorCrops'):
        settings.pop(key, None)
    settings.update(arms=list(ARMS), titles=list(TITLES), beta=1, amplitude=.25,
                    erosionPanels=['independent E', 'inherited E', 'signed B-A E'],
                    erosionAndDifferenceLimits=[-1, 1], layerLimits=[-1, 1],
                    layerGrid='2 rows (A then B), 4 columns coarse to fine',
                    holdout='257-square seed 12345; identical palette, limits and light')
    return settings


def write_manifest(out, context, dataset):
    kernel = A.JAVA_SOURCES[0]
    reference_sources = A.load_json(ROOT / BASELINE / 'source-lock.json')
    manifest = {'kind': KIND, 'status': 'complete', 'completedUtc': A.utc(),
                'purpose': 'Diagnostic full-input mountain B guide inheritance; NOT production',
                'renderOnly': context['renderOnly'], 'samplingRun': context['record'],
                'commands': context['commands'], 'sourceHashes': context['activeSources'],
                'sourcesAtSampling': context['sourcesAtSampling'],
                'rendererChangedSinceSampling': context['activeSources'][SCRIPT] !=
                                               context['sourcesAtSampling'][SCRIPT],
                'aBaseline': BASELINE.as_posix(), 'aOutputLockExcludes': ['classes/'],
                'aKernelExtension': {'path': kernel, 'frozenASha256': reference_sources[kernel],
                                     'activeSha256': context['activeSources'][kernel],
                                     'policy': 'prepareLayer extension permitted before B; whole file locked during B'},
                'lockedFileCount': len(context['locked']), 'allLockedUnchanged': True,
                'lockedBeforeSha256': A.sha(out / 'locked-before.json'),
                'lockedAfterSha256': A.sha(out / 'locked-after.json'),
                'allDataUnchanged': True, 'dataHashes': context['data'],
                'sampling': dataset['sampling'], 'holdoutSampling': dataset['holdout']['sampling'],
                'probeReports': dataset['reports'], 'holdoutReports': dataset['holdout']['reports'],
                'verification': dataset['verification'], 'render': render_settings(),
                'candidateCaveat': PROXY_CAVEAT,
                'morphologyAccepted': False, 'productionWiring': False,
                'outputHashExcludes': ['manifest.json (self-reference)'],
                'outputHashes': A.output_hashes(out)}
    A.save_json(out / 'manifest.json', manifest)


def record_failure(out, context, error):
    failure = {'kind': KIND, 'status': 'failed', 'timeUtc': A.utc(), 'error': repr(error),
               'traceback': traceback.format_exc(), 'commands': context['commands']}
    try:
        verify_locks(out, context, write_after=True)
        failure['allLockedUnchanged'] = True
    except Exception as lock_error:
        failure['allLockedUnchanged'] = False
        failure['lockError'] = repr(lock_error)
    A.save_json(out / 'failure.json', failure)
    A.save_json(out / 'verification.json', failure)
    failure.update(outputHashExcludes=['manifest.json (self-reference)'], outputHashes=A.output_hashes(out))
    A.save_json(out / 'manifest.json', failure)


def execute(out, render_only=False):
    context = prepare(out, render_only)  # Preflight failure never overwrites an existing run.
    try:
        if not render_only:
            sample(out, context['commands'])
            freeze_sampling(out, context)
        dataset = load_dataset(out)
        stats = statistics(out, dataset)
        render_heights(out, dataset, A.palette())
        render_erosion(out, dataset)
        render_layers(out, dataset)
        render_profiles(out, dataset)
        verify_locks(out, context, write_after=True)
        A.require(all((out / name).is_file() and (out / name).stat().st_size > 0 for name in REQUIRED),
                  'Missing required B outputs')
        A.save_json(out / 'verification.json', {
            'status': 'pass', 'scope': 'data contracts and reproducibility, NOT morphology acceptance',
            'allLockedUnchanged': True, 'allDataUnchanged': True, 'javaStagesAllExitZero': True,
            'sourceLockMatchesExceptExplicitBRendererEdit': True,
            'requiredOutputs': list(REQUIRED), 'morphologyReviewRequired': True, **dataset['verification']})
        write_manifest(out, context, dataset)
        print(A.json.dumps({arm: {key: value[key] for key in
                                 ('E_rms', 'E_gradientRmsPerWu', 'signedMeanDeltaVsCarrierH', 'heightMinMax')}
                            for arm, value in stats['arms'].items()}, indent=2), flush=True)
        print('DONE', out, '| B diagnostic only; main/tributary morphology awaits visual review', flush=True)
    except Exception as error:
        record_failure(out, context, error)
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('out', help='NEW direct build child, or this B script\'s frozen run for --render-only')
    parser.add_argument('--render-only', action='store_true', help='verify locks and render; never execute Java')
    args = parser.parse_args()
    try:
        execute(A.output_path(args.out, args.render_only), args.render_only)
        return 0
    except Exception:
        traceback.print_exc()
        return 1


if __name__ == '__main__':
    sys.exit(main())
