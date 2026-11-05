"""Window-seam renderer/runner; diagnostic mountain type-space, NOT Minecraft.

From MDK (no Gradle/Forge):
    python -B src/diagnostics/curved-window-seam.py
    python -B src/diagnostics/curved-window-seam.py --render-only

Normal runs refuse an existing build/curved-window-seam-20261103-final.
Render-only requires this runner's frozen, successful Java execution and data.
No position selection, curve editing, star-count gate or visual acceptance.
"""

import argparse
import csv
import importlib.util
import json
import os
from pathlib import Path
import re
import sys
import traceback

# Importing the previous renderer must not write __pycache__ beside old sources.
sys.dont_write_bytecode = True
ROOT = Path(__file__).resolve().parents[2]
OLD_SCRIPT = ROOT / 'src/diagnostics/curved-segment-erosion.py'
SPEC = importlib.util.spec_from_file_location('curved_segment_visuals', OLD_SCRIPT)
old = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(old)  # Its guarded main is never called.
np, plt = old.np, old.plt
require, sha = old.require, old.sha
load_json, save_json = old.load_json, old.save_json

OUT = ROOT / 'build/curved-window-seam-20261103-final'
BASE = ROOT / 'build/curved-segment-erosion-20261103-final/data'
SCRIPT = Path(__file__).resolve().relative_to(ROOT).as_posix()
MORPH = old.MORPH
NAMES = ('CurvedSegmentErosion', 'CurvedSegmentCheck', 'CurveWindowEvidence',
         'CompleteCurveQuery', 'CompleteCurveQueryCheck', 'CurvedWindowProbe')
SOURCES = [MORPH + name + '.java' for name in NAMES]
STAGES = ('javac', 'old-check', 'new-check', 'probe')
KIND = 'curved-window-seam-v1'
FIELDS = ('before-height', 'after-height', 'base-raw')
HEADER = ['profile', 'offset_wu', 'world_x', 'world_z', 'before_H', 'after_H',
          'coordinate_space']
REQUIRED_DATA = ('sampling.json', 'checks.json', 'seams.json', 'performance.json',
                 'geometry.json', 'profiles.csv', 'seam-limits.csv',
                 *(name + '.f64be' for name in FIELDS))
REQUIRED = ('hillshade.png', 'comparison.png', 'seam-zoom.png', 'profiles.png',
            'convergence-zoom.png', 'statistics.json', 'outcome.txt')
ARMS = ('before', 'after')
TITLES = ('Before / 旧窗口', 'After / 完整查询')
FOOTER = ('Pure mountain type-space; NOT actual Minecraft. Same full world window and fixed scales.\n'
          'HS=2; Yproxy=354.66H; e=.02+1.5H; e[-.35,1.2]; old light[-.5,-.3,1].\n'
          'Domain locators approximate world within 2wu, NOT exact world seam positions. '
          'Normal convergence is NOT a defect; visual judgement is external.')


def locked_snapshot():
    result = old.tree_hashes(ROOT / 'src/main', ROOT)
    result.update(old.tree_hashes(BASE, ROOT))
    files = [OLD_SCRIPT, ROOT / old.HELPER, ROOT / old.PALETTE]
    files += [ROOT / MORPH / (name + '.java') for name in
              ('CurvedSegmentErosion', 'CurvedSegmentCheck', 'CurvedSegmentProbe')]
    result.update(old.hashes(files, ROOT))
    return result


def source_snapshot():
    return old.hashes([ROOT / SCRIPT, *(ROOT / path for path in SOURCES)], ROOT)


def preflight(render_only):
    require(OUT.parent.resolve() == (ROOT / 'build').resolve(), 'Invalid output parent')
    require(not OUT.is_symlink(), 'Output must not be a symlink')
    require(OUT.is_dir() if render_only else not OUT.exists(),
            '--render-only requires an existing frozen run' if render_only else
            'Refusing existing OUT: ' + str(OUT))
    require(all((BASE / name).is_file() for name in
                ('sampling.json', 'curved-height.f64be', 'base-raw.f64be', 'convergences.json')),
            'Old final data incomplete')
    require(all((ROOT / path).is_file() for path in SOURCES),
            'Missing Java interfaces: ' + ', '.join(p for p in SOURCES if not (ROOT / p).is_file()))
    return locked_snapshot(), source_snapshot()


def execution_snapshot():
    result = old.hashes([OUT / name for name in
                         ('commands.json', 'validation.log', 'locked-before.json',
                          'source-lock.json', 'data-lock.json')], OUT)
    for folder in ('logs', 'classes'):
        result.update(old.tree_hashes(OUT / folder, OUT))
    return result


def prepare(render_only):
    locked, sources = preflight(render_only)
    if not render_only:
        OUT.mkdir(parents=True, exist_ok=False)
        save_json(OUT / 'locked-before.json', locked)
        save_json(OUT / 'source-lock.json', sources)
        save_json(OUT / 'commands.json', [])
        return dict(locked=locked, sources=sources, commands=[], data=None,
                    record=None, renderOnly=False)
    record = load_json(OUT / 'run-record.json')
    require(record.get('kind') == KIND and record.get('state') == 'data-ready',
            'Not this runner\'s data-ready run')
    old.same_hashes(load_json(OUT / 'locked-before.json'), locked, 'Frozen old data/main/old curve')
    sampled = load_json(OUT / 'source-lock.json')
    old.same_hashes({k: v for k, v in sampled.items() if k != SCRIPT},
                    {k: v for k, v in sources.items() if k != SCRIPT}, 'Java sources')
    old.same_hashes(record['immutableOutputs'], execution_snapshot(), 'Execution evidence')
    data = load_json(OUT / 'data-lock.json')
    old.same_hashes(data, old.tree_hashes(OUT / 'data', OUT), 'Probe data')
    commands = load_json(OUT / 'commands.json')
    require([c['stage'] for c in commands] == list(STAGES) and
            all(c['exitCode'] == 0 for c in commands), 'Unsuccessful Java stages')
    verify_check_logs(commands)
    return dict(locked=locked, sources=sources, commands=commands, data=data,
                record=record, renderOnly=True)


def verify_locks(context, write_after=False):
    current = locked_snapshot()
    if write_after:
        save_json(OUT / 'locked-after.json', current)
    old.same_hashes(context['locked'], current, 'Main, old curve, old complete data and visuals')
    old.same_hashes(context['sources'], source_snapshot(), 'Active runner/Java sources')
    if context['data'] is not None:
        old.same_hashes(context['data'], old.tree_hashes(OUT / 'data', OUT), 'Probe data')
    if context['record'] is not None:
        old.same_hashes(context['record']['immutableOutputs'], execution_snapshot(), 'Execution artifacts')
        require(context['record'] == load_json(OUT / 'run-record.json'), 'Run record drift')


def verify_check_logs(commands):
    for command in commands:
        if command['stage'] not in ('old-check', 'new-check'):
            continue
        text = (OUT / command['log']).read_text(encoding='utf-8', errors='replace')
        require(not re.search(r'(?im)^\s*(?:FAIL\b|Exception in thread|java\.lang\.AssertionError)', text),
                command['stage'] + ' logged failure even if its JVM returned zero')


def sample(context):
    classes = OUT / 'classes'
    classes.mkdir()
    old.run_command(OUT, context['commands'], 'javac', [
        'javac', '--release', '17', '-encoding', 'UTF-8', '-proc:none', '-Xlint:all', '-Werror',
        '-classpath', str(classes), '-d', str(classes), '-sourcepath',
        os.pathsep.join(('src/diagnostics/java', 'src/main/java')), *SOURCES])
    for stage, name in (('old-check', 'CurvedSegmentCheck'),
                        ('new-check', 'CompleteCurveQueryCheck')):
        old.run_command(OUT, context['commands'], stage,
                        ['java', '-cp', str(classes), old.PACKAGE + name])
        verify_check_logs(context['commands'])
    old.run_command(OUT, context['commands'], 'probe', [
        'java', '-Xmx3g', '-cp', str(classes), old.PACKAGE + 'CurvedWindowProbe',
        str(OUT / 'data')])
    verify_locks(context)
    context['data'] = old.tree_hashes(OUT / 'data', OUT)
    save_json(OUT / 'data-lock.json', context['data'])
    record = dict(kind=KIND, state='data-ready', createdUtc=old.utc(),
                  immutableOutputs=execution_snapshot(), sourcepathPolicy=
                  'Five explicit diagnostic roots; empty external classpath; pure JDK 17 API',
                  jvmEnvironment={key: os.environ.get(key) for key in
                                  ('JAVA_HOME', 'JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS',
                                   'JDK_JAVA_OPTIONS', 'CLASSPATH')})
    save_json(OUT / 'run-record.json', record)
    context['record'] = record


def validate_sampling(meta):
    old.validate_sampling(meta)
    baseline = load_json(BASE / 'sampling.json')
    for key in ('size', 'originWu', 'stepWu', 'HS', 'typeLengthWu', 'seed'):
        require(meta[key] == baseline[key], 'Old sampling differs: ' + key)


def bounds_for(center, radius):
    x, z = old.point(center, 'crop center')
    return x - radius, x + radius, z - radius, z + radius


def validate_crop(bounds, meta, label):
    x0, x1, z0, z1 = bounds
    xmin, xmax, zmin, zmax = old.window_bounds(meta)
    require(max(xmin, x0) < min(xmax, x1) and max(zmin, z0) < min(zmax, z1),
            'Fixed crop has no sampled overlap: ' + str(label))


def validate_seams(document, meta):
    require(isinstance(document, dict), 'seams.json must be a root object')
    require(isinstance(document.get('selectionRule'), str) and document['selectionRule'].strip(),
            'Missing seam selectionRule')
    items = document.get('seams')
    require(isinstance(items, list) and items, 'No seam records')
    ids = []
    for item in items:
        require(isinstance(item, dict), 'Invalid seam record')
        ids.append(str(item['id']))
        require(type(item['octave']) is int and item['octave'] >= 0, 'Invalid octave')
        require(item['axis'] in ('x', 'z', 0, 1), 'Invalid seam axis')
        old.point(item['domainPoint'], 'domainPoint')
        old.point(item['worldLocator'], 'worldLocator')
        require(item['locatorErrorBoundWu'] == 2, 'Expected locatorErrorBoundWu=2')
        for prefix in ('oldJumpH', 'newJumpH'):
            values = [v for k, v in item.items() if k.startswith(prefix)]
            require(values and all(isinstance(v, (float, int)) and not isinstance(v, bool)
                                   and np.isfinite(v) for v in values), 'Missing finite ' + prefix)
        validate_crop(bounds_for(item['worldLocator'], 40), meta, item['id'])
    require(len(ids) == len(set(ids)), 'Duplicate seam IDs')


def read_profiles(path):
    groups = {}
    with path.open('r', encoding='utf-8-sig', newline='') as stream:
        reader = csv.reader(stream)
        require(next(reader, None) == HEADER, 'profiles.csv header mismatch; schema must not be dropped')
        for row in reader:
            require(len(row) == len(HEADER) and row[0], 'Invalid profile row')
            require(row[-1] in ('world', 'erosion-domain'), 'Unknown coordinate_space')
            values = np.asarray([float(v) for v in row[1:-1]], dtype=float)
            require(np.isfinite(values).all(), 'Non-finite profile value')
            group = groups.setdefault(row[0], dict(space=row[-1], rows=[]))
            require(group['space'] == row[-1], 'Mixed coordinate spaces in one profile')
            group['rows'].append(values)
    require(groups, 'Empty profiles.csv')
    for name, group in groups.items():
        group['values'] = np.asarray(group.pop('rows'))
        require(len(group['values']) >= 2 and (np.diff(group['values'][:, 0]) > 0).all(),
                'offset_wu must strictly increase: ' + name)
    require(any(g['space'] == 'world' for g in groups.values()), 'Missing full-world cuts')
    return groups


def read_limits(path):
    # The probe owns this proof schema. Preserve EVERY field; never turn +/-epsilon
    # image samples into alleged same-point, forced-source-set limits.
    with path.open('r', encoding='utf-8-sig', newline='') as stream:
        reader = csv.reader(stream)
        header = next(reader, None)
        require(header and all(header) and len(header) == len(set(header)), 'Invalid seam-limits header')
        rows = list(reader)
    require(rows and all(len(row) == len(header) for row in rows), 'Invalid seam-limits rows')
    numeric = 0
    for row in rows:
        for cell in row:
            try:
                value = float(cell)
            except ValueError:
                continue
            require(np.isfinite(value), 'Non-finite seam-limits evidence')
            numeric += 1
    require(numeric > 0, 'No numeric seam-limit evidence')
    return dict(header=header, rows=rows, numericCells=numeric,
                interpretation='Probe same-point two-sided forced-source-set evidence; not raster differences',
                independentlyReevaluated=False)


def load_dataset():
    data = OUT / 'data'
    require(all((data / name).is_file() for name in REQUIRED_DATA), 'Incomplete probe data')
    meta = load_json(data / 'sampling.json')
    validate_sampling(meta)
    arrays = {name: old.read_array(data / (name + '.f64be'), meta['size']) for name in FIELDS}
    for current, reference in (('before-height.f64be', 'curved-height.f64be'),
                               ('base-raw.f64be', 'base-raw.f64be')):
        require(sha(data / current) == sha(BASE / reference), 'Old baseline SHA mismatch: ' + current)
    reports = {name: load_json(data / name) for name in
               ('checks.json', 'seams.json', 'performance.json', 'geometry.json')}
    require(all(isinstance(v, dict) for v in reports.values()), 'Expected JSON report objects')
    require(reports['checks.json'].get('passed') is True, 'Probe checks.json did not pass')
    validate_seams(reports['seams.json'], meta)
    convergences = load_json(BASE / 'convergences.json')
    old.validate_convergences(convergences, meta)
    return dict(sampling=meta, arrays=arrays, reports=reports, convergences=convergences,
                profiles=read_profiles(data / 'profiles.csv'), limits=read_limits(data / 'seam-limits.csv'))


def configure_font():
    from matplotlib import font_manager
    names = {font.name for font in font_manager.fontManager.ttflist}
    preferred = [name for name in ('Microsoft YaHei', 'SimHei', 'Noto Sans CJK SC') if name in names]
    plt.rcParams['font.sans-serif'] = preferred + ['DejaVu Sans']
    plt.rcParams['axes.unicode_minus'] = False
    return preferred


def finish(fig, filename, subtitle, cmap=None, gray=False, footer=FOOTER):
    fig.suptitle('Window seam / 窗口接缝\n' + subtitle, fontsize=12)
    fig.text(.025, .012, footer, fontsize=8)
    fig.tight_layout(rect=(0, .095, .94 if cmap is not None or gray else 1, .90), h_pad=3)
    if cmap is not None or gray:
        norm = old.Normalize(.65, 1) if gray else old.Normalize(*old.E_LIMITS)
        bar = old.ScalarMappable(norm=norm, cmap='gray' if gray else cmap)
        fig.colorbar(bar, cax=fig.add_axes([.955, .25, .012, .5]),
                     label='fixed light' if gray else 'e=.02+1.5H (unshaded palette)')
    fig.savefig(OUT / filename, dpi=160, metadata={'Description': subtitle + '; ' + FOOTER})
    plt.close(fig)


def render_whole(dataset, shades, colors, cmap):
    meta = dataset['sampling']
    for name, images, gray in (('hillshade.png', shades, True), ('comparison.png', colors, False)):
        fig, axes = plt.subplots(1, 2, figsize=(16, 8))
        for ax, arm, title in zip(axes, ARMS, TITLES):
            old.panel(ax, images[arm], meta, old.window_bounds(meta), title, gray)
        finish(fig, name, 'Same full world window / 同一完整世界窗口',
               cmap=None if gray else cmap, gray=gray)


def seam_panel(ax, image, meta, bounds, title):
    xmin, xmax, zmin, zmax = old.window_bounds(meta)
    clipped = (max(xmin, bounds[0]), min(xmax, bounds[1]),
               max(zmin, bounds[2]), min(zmax, bounds[3]))
    ax.set_facecolor('#d5d5d5')
    old.panel(ax, image, meta, clipped, title, True)
    ax.set(xlim=bounds[:2], ylim=(bounds[3], bounds[2]))
    if tuple(bounds) != clipped:
        ax.text(.02, .03, 'Gray = outside frozen raster; no extrapolation', transform=ax.transAxes,
                fontsize=7, bbox={'facecolor': 'white', 'alpha': .8})


def render_seams(dataset, shades):
    items = dataset['reports']['seams.json']['seams']
    # Paginate every supplied seam, without sorting, ranking or selecting positions.
    crops = []
    for page, first in enumerate(range(0, len(items), 4)):
        chunk = items[first:first + 4]
        fig, axes = plt.subplots(len(chunk), 2, figsize=(14, 5 * len(chunk)), squeeze=False)
        for row, item in enumerate(chunk):
            bounds = bounds_for(item['worldLocator'], 40)
            crops.append(dict(id=item['id'], boundsWu=list(bounds), radiusWu=40,
                              locatorErrorBoundWu=2, exactWorldSeam=False))
            for col, (arm, title) in enumerate(zip(ARMS, TITLES)):
                text = '%s | %s | o%s %s; r=40wu\nDomain locator approx 2wu; NOT exact world' % (
                    item['id'], title, item['octave'], item['axis'])
                seam_panel(axes[row, col], shades[arm], dataset['sampling'], bounds, text)
        filename = 'seam-zoom.png' if page == 0 else 'seam-zoom-%02d.png' % (page + 1)
        finish(fig, filename, 'Probe-fixed seam crops / 固定接缝近景; no overlays', gray=True)
    return crops


def render_convergences(dataset, shades):
    items = dataset['convergences']['convergences']
    fig, axes = plt.subplots(2, 2, figsize=(14, 12), squeeze=False)
    crops = []
    for row, item in enumerate(items):
        bounds = old.crop_for(item)
        crops.append(dict(id=item['id'], boundsWu=list(bounds),
                          source='OLD final/data/convergences.json; unchanged center/radius'))
        locator = ('Old world inverse' if 'worldJunctionWu' in item else
                   'Domain locator approx 2wu; NOT exact world')
        for col, (arm, title) in enumerate(zip(ARMS, TITLES)):
            text = '%s | %s | old r=%gwu\n%s' % (item['id'], title, item['radiusWu'], locator)
            old.panel(axes[row, col], shades[arm], dataset['sampling'], bounds, text, True)
    finish(fig, 'convergence-zoom.png',
           'Same old shared-valley centers / 原共同谷点; normal convergence is NOT a defect', gray=True)
    return crops


def profile_space_label(space):
    if space == 'world':
        return 'world cut; full H including original outer-domain composition'
    return 'erosion-domain; fixed-domain shell H; outer-domain composition REMOVED (not full world)'


def render_profiles(dataset):
    profiles = list(dataset['profiles'].items())
    low = min(float(g['values'][:, 3:5].min()) for _, g in profiles)
    high = max(float(g['values'][:, 3:5].max()) for _, g in profiles)
    pad = max((high - low) * .025, 1e-6)
    for page, first in enumerate(range(0, len(profiles), 6)):
        chunk = profiles[first:first + 6]
        fig, axes = plt.subplots(len(chunk), 1, figsize=(16, 3.5 * len(chunk) + 2), squeeze=False)
        for ax, (name, group) in zip(axes[:, 0], chunk):
            values = group['values']
            for col, title, color in ((3, TITLES[0], '#d95f02'), (4, TITLES[1], '#1b9e77')):
                ax.plot(values[:, 0], values[:, col], linewidth=1, label=title, color=color)
            start, end = values[0, 1:3], values[-1, 1:3]
            ax.set(title='%s | %s\nCSV coords (%.3f, %.3f) -> (%.3f, %.3f)' % (
                name, profile_space_label(group['space']), *start, *end),
                xlabel='offset_wu (direct Java samples)', ylabel='H', ylim=(low - pad, high + pad),
                xlim=(values[0, 0], values[-1, 0]))
            ax.grid(alpha=.2)
            ax.legend(fontsize=8)
        filename = 'profiles.png' if page == 0 else 'profiles-%02d.png' % (page + 1)
        finish(fig, filename, 'Direct cuts / 直接剖面; shared H limits; no interpolation',
               footer='CSV header and every coordinate_space retained, including legacy world_x/world_z column names.\n'
                      'Domain rows carry DOMAIN coordinates and fixed-domain shell H, NOT full-world H.\n'
                      'Exact same-point forced-source-set evidence is separate: data/seam-limits.csv.')


def render(dataset):
    fonts = configure_font()
    light, palette, helpers = old.visual_helpers()
    cmap = palette()
    meta = dataset['sampling']
    shades = {arm: light(dataset['arrays'][arm + '-height'], meta['stepWu'] * meta['HS']) for arm in ARMS}
    colors = {arm: cmap(old.Normalize(*old.E_LIMITS, clip=True)(
        .02 + 1.5 * dataset['arrays'][arm + '-height']))[..., :3] * shades[arm][..., None] for arm in ARMS}
    render_whole(dataset, shades, colors, cmap)
    seams = render_seams(dataset, shades)
    convergences = render_convergences(dataset, shades)
    render_profiles(dataset)
    return dict(seamCrops=seams, convergenceCrops=convergences, helpers=helpers, fonts=fonts,
                palettePath=old.PALETTE.as_posix(), paletteSha256=sha(ROOT / old.PALETTE),
                HS=meta['HS'], yProxyFactor=old.Y_FACTOR, lightXZu=[-.5, -.3, 1],
                shade='.65+.35*positiveDot', grayLimits=[.65, 1], eLimits=list(old.E_LIMITS),
                eDefinition='.02+1.5H', noPerArmNormalization=True, interpolation='nearest',
                coordinates='Full world [z,x], z down; fixed-domain profiles explicitly labelled')


def statistics(dataset):
    arrays = dataset['arrays']
    delta = arrays['after-height'] - arrays['before-height']
    result = dict(differenceAfterMinusBeforeH=old.distribution(delta),
                  gradientsHperWu={arm: old.gradient_summary(arrays[arm + '-height'],
                                  dataset['sampling']['stepWu']) for arm in ARMS},
                  profiles={name: dict(samples=len(g['values']), coordinate_space=g['space'],
                            meaning=profile_space_label(g['space']),
                            differenceAfterMinusBeforeH=old.distribution(g['values'][:, 4] - g['values'][:, 3]))
                            for name, g in dataset['profiles'].items()},
                  seams=dataset['reports']['seams.json'], seamLimits=dataset['limits'],
                  performance=dataset['reports']['performance.json'],
                  policy='Facts only; no star counts, morphology gate or visual judgement')
    save_json(OUT / 'statistics.json', result)
    return result


def write_reports(context, dataset, stats, settings):
    verification = dict(status='integrity-pass-not-visual-acceptance',
                        mainUnchanged=True, currentOldCurveJavaUnchanged=True,
                        completeOldFinalDataUnchanged=True, completeProbeDataUnchanged=True,
                        beforeBitIdenticalToOldCurved=True, rawBitIdenticalToOld=True,
                        beforeSha256=sha(OUT / 'data/before-height.f64be'),
                        oldCurvedSha256=sha(BASE / 'curved-height.f64be'),
                        rawSha256=sha(OUT / 'data/base-raw.f64be'),
                        javaStagesPassed=True, arrayDtype='>f8', arrayShape=[769, 769],
                        profileHeader=HEADER, domainProfileMeaning=profile_space_label('erosion-domain'),
                        exactLimitsSource='data/seam-limits.csv; original schema retained',
                        limitSemanticsIndependentlyReevaluated=False,
                        profilesIndependentlyReevaluated=False, seamRadiusWu=40,
                        domainLocatorApproximationWu=2, domainLocatorIsExactWorld=False,
                        convergenceCentersUnchanged=True, convergenceMorphologyVisuallyConfirmed=False,
                        normalConvergenceIsDefect=False, starCountGate=False, visualReviewRequired=True)
    save_json(OUT / 'verification.json', verification)
    d = stats['differenceAfterMinusBeforeH']
    text = ('Facts only; diagnostic mountain type-space, NOT actual Minecraft.\n'
            'Java compile + old/new checks + probe returned zero; logs/ and commands.json retain real exits.\n'
            'Before SHA equals old curved-height; raw SHA equals old raw; complete old data/main/old curve frozen.\n'
            'after_H-before_H: mean=%.17g rms=%.17g min=%.17g max=%.17g.\n'
            'Seam crops: every probe record, fixed worldLocator +/-40wu; domain locator approximate 2wu, NOT exact world.\n'
            'Convergence crops: unchanged two old centers/radii; shared-valley morphology shown, NOT asserted preserved.\n'
            'World profiles: full H. Erosion-domain profiles: true fixed-domain shell H, outer composition removed.\n'
            'Exact same-point forced-source-set proof: data/seam-limits.csv; renderer does not independently recompute it.\n'
            'Normal convergence is not a defect. No star-count gate. Main agent must judge figures visually.\n')
    (OUT / 'outcome.txt').write_text(text % (d['mean'], d['rms'], d['min'], d['max']), encoding='utf-8')
    manifest = dict(kind=KIND, completedUtc=old.utc(), status='rendered-awaiting-main-agent-visual-review',
                    renderOnly=context['renderOnly'], actualMinecraftWorld=False,
                    commands=context['commands'], runRecord=context['record'],
                    sourcesAtSampling=load_json(OUT / 'source-lock.json'), activeSources=context['sources'],
                    lockedInputHashes=context['locked'], dataHashes=context['data'],
                    sampling=dataset['sampling'], reports=dataset['reports'],
                    oldConvergences=dataset['convergences'], seamLimits=dataset['limits'],
                    render=settings, verification=verification, requiredOutputs=list(REQUIRED),
                    outputHashExcludes=['manifest.json (self-reference)'], outputHashes=old.output_hashes(OUT))
    save_json(OUT / 'manifest.json', manifest)


def record_failure(context, error):
    failure = dict(kind=KIND, status='failed', completedUtc=old.utc(), error=repr(error),
                   traceback=traceback.format_exc(), commands=context['commands'])
    try:
        verify_locks(context, write_after=True)
        failure['lockedInputsUnchanged'] = True
    except Exception as lock_error:
        failure.update(lockedInputsUnchanged=False, lockError=repr(lock_error))
    save_json(OUT / 'failure.json', failure)
    failure['outputHashes'] = old.output_hashes(OUT)
    save_json(OUT / 'manifest.json', failure)


def execute(render_only):
    context = prepare(render_only)
    try:
        if not render_only:
            sample(context)
        dataset = load_dataset()
        stats = statistics(dataset)
        settings = render(dataset)
        verify_locks(context, write_after=True)
        write_reports(context, dataset, stats, settings)
        require(all((OUT / name).is_file() and (OUT / name).stat().st_size > 0 for name in REQUIRED),
                'Required output missing')
        print('DONE', OUT, '| Integrity only; main agent visual review required.', flush=True)
    except Exception as error:
        record_failure(context, error)
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('out', nargs='?', default=str(OUT), help='Only the specified NEW final output is allowed')
    parser.add_argument('--render-only', action='store_true', help='Verify/re-render frozen data; never run Java')
    args = parser.parse_args()
    try:
        require((ROOT / args.out).resolve() == OUT.resolve(), 'Output fixed to ' + str(OUT))
        execute(args.render_only)
        return 0
    except Exception as error:
        traceback.print_exc()
        return error.code if isinstance(error, old.CommandFailed) and error.code > 0 else 1


if __name__ == '__main__':
    sys.exit(main())
