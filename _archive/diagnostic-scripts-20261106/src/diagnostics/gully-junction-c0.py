"""C0 single-junction runner/renderer; isolated type space, NOT production.

MDK cwd: python -B src/diagnostics/gully-junction-c0.py build/<NEW>
Optional: python -B src/diagnostics/gully-junction-c0.py --render-only build/<C0>
Import does not sample, compile, read baseline data, or create run outputs.

Java interface (implemented separately): GullyJunctionProbe <NEW OUT/data>.
Main sampling.json preserves every frozen B metadata key (size 769); holdout
sampling.json preserves every B holdout key (size 257). Both folders export
FIELDS as row-major [z,x], big-endian float64. Frozen B files are copy/read-ONLY.
Runtime B baseline must retain B's exact accumulation/division/multiply order;
this renderer verifies file SHA and never reconstructs, fits, or writes arrays.
Geometry height equals baseline: helper routes do not change a surface.
Main data also exports REPORTS, candidates.csv, profiles.csv, cross-sections.csv
with exact PROFILE_COLUMNS / CROSS_COLUMNS headers. Heights must sample the
final continuous functions in Java, NOT an interpolated image. Empty CSV bodies
are allowed for an explicit rejection. target_H may be empty when infeasible.
Optional holdout reports use the same names; synthetic Check needs no arrays.

junction.json: status/reason strings; junction=[world_x,world_z] in wu;
tangent=[tx,tz]; geometry={upstream,downstream,spread,width} in wu;
routes=[{id,parentId,points:[[world_x,world_z],...]}]; candidateCount=int.
Additional Probe fields are retained, not interpreted as acceptance. Numerical
bed/budget conclusions come from bed-validation.json, not helper geometry or
accepted/buildAccepted flags. Its baseline/constrained objects each contain
longitudinal/cross evidence; explicit passed/allPassed/finalPassed or PASS/FAIL
statuses describe numerical checks only. Human morphology review stays pending.
All candidates.csv rows stay in Probe order; the renderer never discovers,
reselects, fits, or retries a Y. Discovery assessment remains separately gated.
"""

import argparse
import csv
import importlib.util
import os
from pathlib import Path
import sys
import traceback
import hashlib
import re
import textwrap


def load_inherited():
    path = Path(__file__).resolve().with_name('fast-erosion-inherited.py')
    spec = importlib.util.spec_from_file_location('_geogenesis_inherited_for_c0', path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


B = load_inherited()
A = B.A
np, plt = A.np, A.plt
ROOT = Path(__file__).resolve().parents[2]
SCRIPT = Path(__file__).resolve().relative_to(ROOT).as_posix()
PLAN = ROOT.parent / '.openbitfun/plans/gully-parent-connection.plan.md'
PLAN_KEY = Path(os.path.relpath(PLAN, ROOT)).as_posix()
BASELINE = Path('build/fast-erosion-inherited-b-20261103-final')
KIND = 'gully-junction-c0-type-space-v1'
JAVA_SOURCES = [*A.JAVA_SOURCES[:2],
                A.STANDALONE + 'InheritedSkeletonErosion.java',
                A.STANDALONE + 'InheritedSkeletonCheck.java',
                A.STANDALONE + 'GullyJunctionConstraint.java',
                A.STANDALONE + 'GullyJunctionCheck.java',
                A.STANDALONE + 'GullyJunctionEvidence.java',
                A.MORPH + 'GullyJunctionProbe.java']
STAGES = ('javac', 'check-A', 'check-B', 'check-C0', 'probe')
ARMS = ('baseline', 'geometry', 'constrained')
TITLES = ('Frozen B: original height', 'Geometry only: height identical to B',
          'C0: geometry + same-layer height constraint')
FIELDS = tuple(arm + '-height' for arm in ARMS) + ('base-raw', 'connection-weight')
REPORTS = ('junction.json', 'checks.json', 'bed-validation.json', 'performance.json',
           'discovery-assessment.json')
PROFILE_COLUMNS = ('route', 's_wu', 'world_x', 'world_z', 'baseline_H', 'geometry_H',
                   'constrained_H', 'target_H')
CROSS_COLUMNS = ('route', 's_wu', 'offset_wu', 'world_x', 'world_z', 'baseline_H',
                 'geometry_H', 'constrained_H')
REQUIRED_PLOTS = ('comparison.png', 'hillshade.png', 'star-zoom.png',
                  'junction-zoom.png', 'junction-routes.png', 'profiles.png',
                  'cross-sections.png', 'holdout.png', 'synthetic-height.png',
                  'synthetic-profiles.png', 'synthetic-cross-sections.png')
FOOTER = ('HS=2; local blocks=2 wu; Yproxy=236.44*1.5*H; e=.02+1.5H, fixed e[-.35,1.2].\n'
          'A light[-.5,-.3,1], shade=.65+.35*positiveDot; no normalization or height fitting.\n'
          'Routes are locators, NOT valley evidence. Bed checks and human morphology review are separate.')
COLORS = ('#555555', '#d95f02', '#1b9e77')


def hash_comparison(expected, actual):
    keys = expected.keys() | actual.keys()
    return {'unchanged': expected == actual, 'expectedFileCount': len(expected),
            'actualFileCount': len(actual),
            'missing': sorted(expected.keys() - actual.keys()),
            'added': sorted(actual.keys() - expected.keys()),
            'changed': {key: {'before': expected.get(key), 'after': actual.get(key)}
                        for key in sorted(keys) if expected.get(key) != actual.get(key)}}


def require_hashes(expected, actual, label):
    report = hash_comparison(expected, actual)
    A.require(report['unchanged'], label + ': ' + A.json.dumps(report, ensure_ascii=False))
    return report


def plan_contract_sha():
    # TodoWrite synchronizes status fields into frontmatter in this host. They are presentation,
    # not algorithmic authority; every other character, including body and todo content, is locked.
    text = PLAN.read_text(encoding='utf-8')
    parts = text.split('---', 2)
    A.require(len(parts) == 3, 'Invalid plan frontmatter')
    parts[1] = re.sub(r'(?m)^    status: (pending|in_progress|completed)$', '    status: TRACKED', parts[1])
    return hashlib.sha256('---'.join(parts).encode('utf-8')).hexdigest()


def locked_snapshot():
    # Snapshot FROM THIS RUN'S START. B.locked_snapshot() enforces old kernel
    # hashes predating the permitted additive layerCapacity methods in A/B.
    result = A.locked_snapshot()
    for baseline in (ROOT / B.BASELINE, ROOT / BASELINE):
        result.update(A.tree_hashes(baseline, ROOT))  # ALL outputs, including classes/logs.
    paths = [ROOT / path for path in set(A.JAVA_SOURCES + B.JAVA_SOURCES + [A.SCRIPT, B.SCRIPT])]
    result.update(A.file_hashes(paths, ROOT))
    result[PLAN_KEY] = plan_contract_sha()
    frozen = ROOT / BASELINE
    require_hashes(A.load_json(frozen / 'data-lock.json'),
                   A.tree_hashes(frozen / 'data', frozen), 'Frozen B data-lock')
    return result


def source_snapshot(locked):
    result = {key: value for key, value in locked.items() if key.startswith('src/')}
    result.update(A.file_hashes([ROOT / path for path in JAVA_SOURCES + [SCRIPT]], ROOT))
    return result


def output_path(argument, render_only=False):
    requested = ROOT / argument
    A.require(not requested.is_symlink(), 'OUT must not be a symlink')
    out = requested.resolve()
    A.require(out.parent == (ROOT / 'build').resolve(), 'OUT must be a direct MDK build child')
    if render_only:
        A.require(out.is_dir(), '--render-only needs an existing C0 run')
    else:
        A.require(not out.exists(), 'NEW build directory required; refusing existing OUT')
    return out


def prepare(out, render_only=False):
    locked = locked_snapshot()
    sources = source_snapshot(locked)  # Missing parallel Java files fail BEFORE OUT creation.
    if not render_only:
        out.mkdir(parents=True, exist_ok=False)
        A.save_json(out / 'locked-before.json', locked)
        A.save_json(out / 'source-lock.json', sources)
        A.save_json(out / 'commands.json', [])
        return {'locked': locked, 'sampledSources': sources, 'activeSources': sources,
                'commands': [], 'data': None, 'record': None, 'renderOnly': False}
    record = A.load_json(out / 'run-record.json')
    A.require(record.get('kind') == KIND and record.get('state') == 'data-ready',
              'Render-only accepts only this runner\'s frozen C0 sampling run')
    require_hashes(record['immutableOutputs'], A.execution_snapshot(out), 'Execution artifacts')
    before = A.load_json(out / 'locked-before.json')
    require_hashes(before, locked, 'Locked inputs')
    sampled = A.load_json(out / 'source-lock.json')
    require_hashes({k: v for k, v in sampled.items() if k != SCRIPT},
                   {k: v for k, v in sources.items() if k != SCRIPT}, 'Sampling sources')
    data = A.load_json(out / 'data-lock.json')
    require_hashes(data, A.tree_hashes(out / 'data', out), 'Main/holdout sampled data')
    commands = A.load_json(out / 'commands.json')
    validate_commands(out, commands)
    return {'locked': before, 'sampledSources': sampled, 'activeSources': sources,
            'commands': commands, 'data': data, 'record': record,
            'recordSha256': A.sha(out / 'run-record.json'), 'renderOnly': True}


def validate_commands(out, commands):
    A.require(tuple(c['stage'] for c in commands) == STAGES and
              all(c['exitCode'] == 0 and c['status'] == 'ok' for c in commands),
              'Compilation, A Check, B Check, C0 Check and Probe must all exit zero')
    for command in commands:
        A.require(A.sha(out / command['log']) == command['logSha256'],
                  'Command log changed: ' + command['log'])


def sample(out, commands):
    classes = out / 'classes'
    classes.mkdir()
    # Explicit diagnostics + sourcepath resolve only the required main closure.
    # --release 17 targets JDK17 even when the installed compiler is newer.
    A.run_command(out, commands, 'javac',
                  ['javac', '--release', '17', '-encoding', 'UTF-8', '-proc:none', '-Xlint:all', '-Werror',
                   '-classpath', str(classes), '-d', str(classes), '-sourcepath',
                   os.pathsep.join(('src/main/java', 'src/diagnostics/java')), *JAVA_SOURCES])
    for stage, name in (('check-A', 'AdaptiveSkeletonCheck'),
                        ('check-B', 'InheritedSkeletonCheck'), ('check-C0', 'GullyJunctionCheck')):
        A.run_command(out, commands, stage,
                      ['java', '-cp', str(classes), 'com.geogenesis.standalone.' + name])
    A.run_command(out, commands, 'probe',
                  ['java', '-Xmx3g', '-cp', str(classes),
                   'com.geogenesis.worldgen.terrain.morph.GullyJunctionProbe', str(out / 'data')])
    validate_commands(out, commands)  # A streams ALL stdout+stderr with real exit codes.


def verify_locks(out, context, write_after=False):
    locked = locked_snapshot()
    if write_after:
        A.save_json(out / 'locked-after.json', locked)
    reports = {'lockedInputs': hash_comparison(context['locked'], locked),
               'activeSources': hash_comparison(context['activeSources'], source_snapshot(locked))}
    if context['data'] is not None:
        reports['sampledData'] = hash_comparison(context['data'], A.tree_hashes(out / 'data', out))
    if context['record'] is not None:
        reports['executionArtifacts'] = hash_comparison(
            context['record']['immutableOutputs'], A.execution_snapshot(out))
        reports['runRecord'] = hash_comparison(
            {'run-record.json': context['recordSha256']},
            {'run-record.json': A.sha(out / 'run-record.json')})
    A.require(all(value['unchanged'] for value in reports.values()),
              'Immutable drift: ' + A.json.dumps(reports, ensure_ascii=False))
    return reports


def freeze_sampling(out, context):
    verify_locks(out, context)
    context['data'] = A.tree_hashes(out / 'data', out)
    A.require(bool(context['data']), 'Probe wrote no data')
    A.save_json(out / 'data-lock.json', context['data'])
    record = {'kind': KIND, 'state': 'data-ready', 'createdUtc': A.utc(),
              'javaTargetRelease': 17, 'immutableOutputs': A.execution_snapshot(out),
              'jvmEnvironment': {key: os.environ.get(key) for key in
                                 ('JAVA_HOME', 'JAVA_TOOL_OPTIONS', '_JAVA_OPTIONS',
                                  'JDK_JAVA_OPTIONS', 'CLASSPATH')}}
    A.save_json(out / 'run-record.json', record)
    context.update(record=record, recordSha256=A.sha(out / 'run-record.json'))


def validate_meta(meta, reference, holdout=False):
    A.require(isinstance(meta, dict), 'sampling.json must be an object')
    for key, expected in reference.items():
        A.require(key in meta and meta[key] == expected, 'Frozen B sampling drift: ' + key)
    A.require(meta['size'] == (257 if holdout else 769), 'Unexpected sample size')
    if not holdout:
        A.validate_sampling(meta)
    A.require(meta['HS'] == 2 and meta['amplitude'] == .25 and meta['k'] == 1 and
              meta['beta'] == 1 and meta['actualMinecraftWorld'] is False,
              'C0 must preserve frozen diagnostic B parameters')
    return {'everyFrozenBKeyMatches': True, 'referenceKeys': sorted(reference),
            'extraProbeKeys': sorted(meta.keys() - reference.keys())}


def verify_arrays(folder, frozen, arrays):
    identities = {}
    for name, reference in (('baseline-height', 'inherited-height'), ('base-raw', 'base-raw'),
                            ('geometry-height', 'inherited-height')):
        path, before = folder / (name + '.f64be'), frozen / (reference + '.f64be')
        expected, actual = A.sha(before), A.sha(path)
        A.require(expected == actual, 'Frozen B bit identity failed: ' + str(path))
        identities[name] = {'reference': before.relative_to(ROOT).as_posix(),
                            'referenceSha256': expected, 'sha256': actual, 'bitIdentical': True}
    weight = arrays['connection-weight']
    A.require((weight >= 0).all() and (weight <= 1).all(), 'Connection weight must be in [0,1]')
    base, constrained = arrays['baseline-height'], arrays['constrained-height']
    changed = base.view(np.uint64) != constrained.view(np.uint64)
    outside = int(np.count_nonzero(changed & (weight == 0)))
    A.require(outside == 0, 'Zero connection weight must return B bit-for-bit')
    return {'identities': identities, 'geometryIsLinesOnly': True,
            'zeroWeightDifferentBits': outside, 'changedHeightPixels': int(changed.sum()),
            'heightBitIdenticalToB': bool(not changed.any()),
            'nonzeroWeightPixels': int(np.count_nonzero(weight)),
            'weightRange': [float(weight.min()), float(weight.max())]}


def finite_pair(value, label):
    pair = np.asarray(value, dtype=float)
    A.require(pair.shape == (2,) and np.isfinite(pair).all(), 'Invalid world-wu pair: ' + label)
    return pair


def route_id(value):
    A.require(isinstance(value, (str, int)) and not isinstance(value, bool), 'Invalid route ID')
    result = str(value)
    A.require(bool(result), 'Empty route ID')
    return result


def validate_junction(value):
    A.require(isinstance(value, dict), 'junction.json must be an object')
    for key in ('status', 'reason'):
        A.require(isinstance(value.get(key), str), 'junction.json requires string ' + key)
    A.require(bool(value['status'].strip()), 'Empty junction status')
    finite_pair(value['junction'], 'junction')
    finite_pair(value['tangent'], 'tangent')  # A rejected discovery may have no direction.
    geometry = value['geometry']
    for key in ('upstream', 'downstream', 'spread', 'width'):
        number = geometry[key]
        A.require(isinstance(number, (float, int)) and not isinstance(number, bool) and
                  np.isfinite(number) and number > 0, 'Invalid geometry size in wu: ' + key)
    A.require(type(value['candidateCount']) is int and value['candidateCount'] >= 0,
              'candidateCount must be a nonnegative integer')
    A.require(isinstance(value['routes'], list), 'routes must be a list')
    ids = set()
    for route in value['routes']:
        rid = route_id(route['id'])
        A.require(rid not in ids and 'parentId' in route, 'Duplicate ID or missing parentId: ' + rid)
        ids.add(rid)
        points = np.asarray(route['points'], dtype=float)
        A.require(points.ndim == 2 and points.shape[1] == 2 and len(points) >= 2 and
                  np.isfinite(points).all(), 'Invalid world-wu route points: ' + rid)
    # Graph/geometry validity is Probe evidence, not a renderer valley test.
    return ids


def read_table(path, columns=None, allow_empty=False):
    with path.open('r', encoding='utf-8-sig', newline='') as stream:
        reader = csv.DictReader(stream)
        header = reader.fieldnames
        A.require(header and len(set(header)) == len(header), 'Missing/duplicate CSV header: ' + str(path))
        if columns is not None:
            A.require(tuple(header) == columns, 'CSV header must be ' + ','.join(columns) + ': ' + str(path))
        rows = list(reader)  # No candidate/profile truncation.
    A.require(all(None not in row and all(v is not None for v in row.values()) for row in rows),
              'CSV row width mismatch: ' + str(path))
    A.require(allow_empty or bool(rows), 'Empty CSV: ' + str(path))
    return {'header': header, 'rows': rows, 'sha256': A.sha(path)}


def read_profiles(folder, routes):
    result = {}
    for name, columns in (('profiles.csv', PROFILE_COLUMNS), ('cross-sections.csv', CROSS_COLUMNS)):
        table = read_table(folder / name, columns, allow_empty=True)
        for row in table['rows']:
            A.require(row['route'] in routes, 'CSV references unknown route: ' + row['route'])
            for key in columns[1:]:
                if key == 'target_H' and row[key] == '':
                    row[key] = None  # An infeasible target is not fabricated.
                    continue
                row[key] = float(row[key])
                A.require(np.isfinite(row[key]), 'Non-finite continuous profile value: ' + key)
            A.require(row['geometry_H'] == row['baseline_H'], 'Geometry-only CSV changed height')
        table['routeIds'] = sorted({row['route'] for row in table['rows']})
        result[name] = table
    return result


def rejected_status(value):
    status = str(value).strip().lower()
    return any(word in status for word in ('reject', 'fail', 'error', 'infeasible', 'invalid', 'missing', 'no_'))


def load_window(folder, frozen, holdout=False):
    meta = A.load_json(folder / 'sampling.json')
    metadata = validate_meta(meta, A.load_json(frozen / 'sampling.json'), holdout)
    arrays = {name: A.read_array(folder / (name + '.f64be'), meta['size']) for name in FIELDS}
    checks = verify_arrays(folder, frozen, arrays)
    reports = {name: A.load_json(folder / name) for name in REPORTS if (folder / name).is_file()}
    A.require(all(isinstance(value, dict) and value for value in reports.values()), 'Empty Probe report')
    if not holdout:
        A.require(set(reports) == set(REPORTS), 'Missing main C0 Probe JSON reports')
    rejected = rejected_status(reports.get('junction.json', {}).get('status', 'not-reported'))
    checks['rejectedJunctionUsesExactB'] = checks['heightBitIdenticalToB'] if rejected else None
    A.require(not rejected or checks['heightBitIdenticalToB'], 'Rejected junction must return exact B: ' + str(folder))
    return {'sampling': meta, 'arrays': arrays, 'reports': reports,
            'verification': {'metadata': metadata, **checks}}


def explicit_check_result(value):
    # ACCEPTED/buildAccepted is geometry/build state, never a numerical bed result.
    if isinstance(value, list):
        results = [explicit_check_result(item) for item in value]
        return False if False in results else (True if results and all(v is True for v in results) else None)
    if not isinstance(value, dict):
        return None
    flags = [value[key] for key in ('passed', 'allPassed', 'finalPassed') if type(value.get(key)) is bool]
    status = str(value.get('status', '')).strip().lower()
    if False in flags or any(word in status for word in ('fail', 'error', 'reject', 'infeasible')):
        return False
    return True if True in flags or status in ('pass', 'passed', 'valid', 'ok') else None


def section_check_result(value):
    direct = explicit_check_result(value)
    children = value.values() if isinstance(value, dict) else (value if isinstance(value, list) else ())
    nested = [section_check_result(item) for item in children if isinstance(item, (dict, list))]
    if direct is False or False in nested:
        return False
    if direct is True or (nested and all(item is True for item in nested)):
        return True
    return None


def bed_arm_summary(evidence, fallback=None):
    evidence = evidence if isinstance(evidence, dict) else {}
    sections = {key: evidence.get(key) for key in ('longitudinal', 'cross')}
    present = all(isinstance(value, (dict, list)) and bool(value) for value in sections.values())
    results = {key: section_check_result(value) for key, value in sections.items()}
    reported = explicit_check_result(evidence)
    if reported is None and present:
        reported = fallback
    if fallback is False or reported is False or False in results.values():
        passed = False
    elif present and (reported is True or all(value is True for value in results.values())):
        passed = True
    else:
        passed = None
    return {'explicitPassed': passed, 'statusFromProbe': evidence.get('status'),
            'hasLongitudinalAndCrossEvidence': present, 'sectionResults': results}


def bed_check_summary(report, folder='data'):
    baseline = bed_arm_summary(report.get('baseline'))
    constrained = bed_arm_summary(report.get('constrained'), explicit_check_result(report))
    return {'source': folder + '/bed-validation.json', 'explicitPassed': constrained['explicitPassed'],
            'statusFromProbe': report.get('status'), 'baseline': baseline, 'constrained': constrained,
            'humanReviewed': False, 'morphologyAccepted': False,
            'automaticMorphologyAcceptance': False}


def outcome(dataset, folder='data'):
    junction = dataset['reports'].get('junction.json', {})
    bed = bed_check_summary(dataset['reports'].get('bed-validation.json', {}), folder)
    status = str(junction.get('status', 'not-reported')).strip().lower()
    rejected = rejected_status(status)
    unchanged = dataset['verification']['heightBitIdenticalToB']
    reasons = [junction.get('reason') or 'Probe junction status: ' + status]
    if unchanged:
        reasons.append('NO_HEIGHT_CHANGE: C0/B bit-identical; not repair evidence')
    if bed['explicitPassed'] is False:
        reasons.append('Final continuous-bed check did not pass')
    elif bed['explicitPassed'] is None:
        reasons.append('No explicit final-bed pass; numerical bed review still required')
    if bed['baseline']['explicitPassed'] is True:
        reasons.append('BASELINE_ALREADY_PASSES: not evidence of repairing a disconnected bed')
    probe_checks = explicit_check_result(dataset['reports'].get('checks.json', {}))
    if probe_checks is False:
        reasons.append('Probe checks report failure; evidence retained for review')
        rejected = True
    no_routes = bool(junction) and not junction['routes']
    if no_routes:
        reasons.append('No candidate routes; no local valley proof')
    rejected = rejected or unchanged or bed['explicitPassed'] is False or no_routes
    pending = 'awaiting-visual-review' if bed['explicitPassed'] is True else 'awaiting-bed-check-and-visual-review'
    return {'status': 'rejected' if rejected else pending,
            'reason': '; '.join(reasons), 'junctionStatusFromProbe': junction.get('status'),
            'noSilentNoChange': True, 'bedCheck': bed, 'morphologyAccepted': False,
            'productionWiring': False, 'infiniteWorldProven': False}


def discovery_gate(window, folder='data'):
    return {'source': folder + '/discovery-assessment.json', 'status': 'gated-at-C0',
            'numericalBedPreconditionPassed': window['outcome']['bedCheck']['explicitPassed'],
            'humanMorphologyReviewRequired': True, 'humanReviewed': False,
            'automaticParentDiscoveryProven': False, 'infiniteWorldProven': False,
            'reason': 'One fixed Y only; no renderer discovery, reselection or retry; stop for human review',
            'probeAssessment': window['reports'].get('discovery-assessment.json')}


def load_dataset(out):
    frozen = ROOT / BASELINE / 'data'
    dataset = load_window(out / 'data', frozen)
    routes = validate_junction(dataset['reports']['junction.json'])
    dataset['tables'] = read_profiles(out / 'data', routes)
    dataset['candidates'] = read_table(out / 'data/candidates.csv', allow_empty=True)
    A.require(len(dataset['candidates']['rows']) == dataset['reports']['junction.json']['candidateCount'],
              'candidates.csv must retain every declared candidate, including all rejections')
    dataset['holdout'] = load_window(out / 'data/holdout', frozen / 'holdout', holdout=True)
    holdout = dataset['holdout']
    if 'junction.json' in holdout['reports']:
        validate_junction(holdout['reports']['junction.json'])
    holdout['outcome'] = outcome(holdout, 'data/holdout')
    dataset['outcome'] = outcome(dataset)
    missing = sorted(routes - set(dataset['tables']['profiles.csv']['routeIds']))
    dataset['verification']['missingLongitudinalRouteIds'] = missing
    if missing or not dataset['tables']['cross-sections.csv']['rows']:
        result = dataset['outcome']
        result.update(status='rejected', reason=result['reason'] + '; MISSING_CONTINUOUS_PROFILE_EVIDENCE')
    dataset['discoveryGate'] = discovery_gate(dataset)
    holdout['discoveryGate'] = discovery_gate(holdout, 'data/holdout')
    # Failed bed/check/discovery reports are retained and rendered, not filtered out.
    return dataset


def window_statistics(window):
    arrays, meta = window['arrays'], window['sampling']
    baseline = arrays['baseline-height']
    diff = arrays['constrained-height'] - baseline
    arms = {arm: {'height': A.distribution(arrays[arm + '-height']),
                  'slopeHperWu': A.gradient_summary(arrays[arm + '-height'], meta['stepWu'])}
            for arm in ARMS}
    return {'arms': arms, 'signedDifferenceCminusB': A.distribution(diff),
            'maxAbsDifferenceCminusB_H': float(np.max(np.abs(diff))),
            'maxAbsDifferenceCminusB_Yproxy': float(np.max(np.abs(diff)) * A.Y_FACTOR),
            'changedHeightPixels': window['verification']['changedHeightPixels'],
            'connectionWeight': A.bounded_distribution(arrays['connection-weight']),
            'differencesAreMeasurementsNotValleyAcceptance': True}


def statistics(out, dataset):
    report = {'main': window_statistics(dataset), 'holdout': window_statistics(dataset['holdout']),
              'outcome': dataset['outcome'], 'holdoutOutcome': dataset['holdout']['outcome'],
              'candidateCount': dataset['reports']['junction.json']['candidateCount'],
              'candidateCsvRows': len(dataset['candidates']['rows']),
              'signedDifferenceDefinition': 'constrained_H - frozen B inherited_H; no recentering',
              'bedAndSameLayerEBudgetSource': 'data/bed-validation.json',
              'bedValidationAndEBudgetReport': dataset['reports']['bed-validation.json'],
              'holdoutBedValidationAndEBudgetReport': dataset['holdout']['reports'].get('bed-validation.json'),
              'budgetPolicy': 'Final-field Java bed validation; renderer neither fits nor infers capacity',
              'discoveryGate': dataset['discoveryGate'], 'holdoutDiscoveryGate': dataset['holdout']['discoveryGate'],
              'helperRouteOverlapIsNotValleyEvidence': True, 'morphologyAccepted': False,
              'humanMorphologyReviewRequired': True, 'productionWiring': False}
    A.save_json(out / 'statistics.json', report)
    return report


def whole_crop(meta, name='Whole frozen B window'):
    span = (meta['size'] - 1) * meta['stepWu'] * meta['HS']
    return [(name, (0, span, 0, span))]


def junction_bounds(dataset):
    meta, junction = dataset['sampling'], dataset['reports']['junction.json']
    q = (finite_pair(junction['junction'], 'junction') - np.asarray(meta['originWu'])) * meta['HS']
    radius = 3 * max(junction['geometry'][key] for key in ('upstream', 'downstream', 'spread', 'width'))
    radius *= meta['HS']
    return (float(q[0] - radius), float(q[0] + radius),
            float(q[1] - radius), float(q[1] + radius))


def finish_figure(fig, out, filename, subtitle, result, colorbar=None):
    scope = 'Mountain C0 | NOT PRODUCTION | ' + result['status'].upper()
    reason = textwrap.fill(result['reason'], width=180)
    fig.suptitle(scope + '\n' + subtitle + '\n' + reason, fontsize=11)
    fig.text(.035, .016, FOOTER, fontsize=9)
    fig.tight_layout(rect=(0, .12, .94 if colorbar is not None else .995, .83))
    if colorbar is not None:
        mappable, label = colorbar
        fig.colorbar(mappable, cax=fig.add_axes([.955, .26, .012, .46]), label=label)
    fig.savefig(out / filename, dpi=160, metadata={'Description': scope + '; ' + subtitle})
    plt.close(fig)


def image_panel(ax, image, meta, bounds, title, gray=False):
    step = meta['stepWu'] * meta['HS']
    span = (meta['size'] - 1) * step
    clipped = (max(0, bounds[0]), min(span, bounds[1]), max(0, bounds[2]), min(span, bounds[3]))
    ax.set_facecolor('#e3e3e3')
    if clipped[0] < clipped[1] and clipped[2] < clipped[3]:
        A.image_panel(ax, image, step, (0, 0), clipped, title, gray=gray)
    else:
        ax.text(.5, .5, 'Outside sampled window', transform=ax.transAxes, ha='center')
    # Preserve declared q +/- radius, including any unsampled area; never fit to C.
    ax.set(xlim=bounds[:2], ylim=(bounds[3], bounds[2]), title=title,
           xlabel='local x (blocks)', ylabel='local z (blocks)', aspect='equal')


def height_images(window, cmap):
    meta, arrays = window['sampling'], window['arrays']
    step = meta['stepWu'] * meta['HS']
    shades = {arm: A.light(arrays[arm + '-height'], step) for arm in ARMS}
    colors = {arm: cmap(A.Normalize(*A.E_LIMITS, clip=True)(
        .02 + 1.5 * arrays[arm + '-height']))[..., :3] * shades[arm][..., None] for arm in ARMS}
    return shades, colors


def overlay_routes(ax, dataset):
    meta, junction = dataset['sampling'], dataset['reports']['junction.json']
    origin = np.asarray(meta['originWu'])
    for index, route in enumerate(junction['routes']):
        points = (np.asarray(route['points']) - origin) * meta['HS']
        ax.plot(points[:, 0], points[:, 1], linewidth=1.3,
                color=COLORS[index % len(COLORS)], label='%s -> %s (helper)' % (route['id'], route['parentId']))
    q = (np.asarray(junction['junction']) - origin) * meta['HS']
    ax.plot(q[0], q[1], marker='+', markersize=9, color='#b2182b', label='q (locator only)')
    ax.legend(fontsize=8, loc='best')
    ax.text(.02, .02, 'HELPERS ONLY - NOT BED PROOF', transform=ax.transAxes, fontsize=8,
            bbox={'facecolor': 'white', 'alpha': .8, 'edgecolor': 'none'})


def render_grid(out, filename, window, images, crops, cmap, result, gray=False, routes=None):
    fig, axes = plt.subplots(len(crops), 3, figsize=(22, 8 if len(crops) == 1 else 13), squeeze=False)
    for row, (name, bounds) in enumerate(crops):
        for col, (arm, title) in enumerate(zip(ARMS, TITLES)):
            image_panel(axes[row, col], images[arm], window['sampling'], bounds, name + '\n' + title, gray)
            if routes is not None:
                overlay_routes(axes[row, col], routes)
    limits, colors = ((.65, 1), 'gray') if gray else (A.E_LIMITS, cmap)
    bar = (A.ScalarMappable(norm=A.Normalize(*limits), cmap=colors),
           'fixed light' if gray else 'e=.02+1.5H (unshaded palette)')
    finish_figure(fig, out, filename, 'B / geometry only / same-layer constrained H', result, bar)


def render_heights(out, dataset):
    cmap = A.palette()
    result = dataset['outcome']
    shades, colors = height_images(dataset, cmap)
    whole = whole_crop(dataset['sampling'])
    render_grid(out, 'comparison.png', dataset, colors, whole, cmap, result)
    render_grid(out, 'hillshade.png', dataset, shades, whole, cmap, result, gray=True)
    render_grid(out, 'star-zoom.png', dataset, shades, A.STAR_CROPS, cmap, result, gray=True)
    zoom = [('Fixed q +/- 3*max(route geometry sizes), no helper lines', junction_bounds(dataset))]
    render_grid(out, 'junction-zoom.png', dataset, shades, zoom, cmap, result, gray=True)
    routes_zoom = [('Same fixed extent; helper routes only, NOT height proof', junction_bounds(dataset))]
    render_grid(out, 'junction-routes.png', dataset, shades, routes_zoom, cmap, result, gray=True, routes=dataset)
    holdout = dataset['holdout']
    _, hold_colors = height_images(holdout, cmap)
    render_grid(out, 'holdout.png', holdout, hold_colors,
                whole_crop(holdout['sampling'], 'Frozen B holdout, seed 12345'), cmap, holdout['outcome'])


def table_groups(rows, keys):
    groups = {}
    for row in rows:
        key = tuple(row[name] for name in keys)
        groups.setdefault(key, []).append(row)
    return groups


def plot_height_curves(ax, rows, coordinate, target=False):
    rows = sorted(rows, key=lambda row: row[coordinate])
    for arm, title, color in zip(ARMS, TITLES, COLORS):
        ax.plot([row[coordinate] for row in rows], [row[arm + '_H'] for row in rows],
                color=color, label=title, linewidth=1, linestyle='--' if arm == 'geometry' else '-')
    if target:
        values = [np.nan if row['target_H'] is None else row['target_H'] for row in rows]
        ax.plot([row[coordinate] for row in rows], values,
                color='#7570b3', linestyle=':', label='Target (NOT actual H)', linewidth=1)
    ax.set_ylabel('final shell height H (unscaled)')
    ax.grid(alpha=.25)
    ax.legend(loc='best', fontsize=8)


def empty_profiles(out, filename, dataset, message):
    fig, ax = plt.subplots(figsize=(16, 7))
    ax.axis('off')
    ax.text(.5, .5, message + '\n' + dataset['outcome']['reason'], ha='center', va='center',
            wrap=True, transform=ax.transAxes)
    finish_figure(fig, out, filename, 'No fabricated route or interpolated bed evidence', dataset['outcome'])


def render_profiles(out, dataset, filename='profiles.png'):
    groups = table_groups(dataset['tables']['profiles.csv']['rows'], ('route',))
    if not groups:
        empty_profiles(out, filename, dataset, 'Rejected candidate: no continuous longitudinal samples exported')
        return
    fig, axes = plt.subplots(1, len(groups), figsize=(max(12, 7 * len(groups)), 8), sharey=True, squeeze=False)
    for ax, ((rid,), rows) in zip(axes[0], groups.items()):
        plot_height_curves(ax, rows, 's_wu', target=True)
        ax.set(title='Actual route ' + rid, xlabel='downstream chart coordinate s (wu; not arc length)')
    finish_figure(fig, out, filename,
                  'Continuous-function longitudinal CSV; all routes, shared y axis; target is dotted', dataset['outcome'])


def cross_station(rows, dataset):
    junction = dataset['reports']['junction.json']
    q = np.asarray(junction['junction'])
    center = min(rows, key=lambda row: abs(row['offset_wu']))
    position = np.array([center['world_x'], center['world_z']])
    tangent = np.asarray(junction['tangent'])
    norm = np.linalg.norm(tangent)
    if norm == 0:
        return 'Direction unavailable; see rejected junction evidence'
    tangent = tangent / norm
    along = float(np.dot(position - q, tangent))
    label = 'at q' if np.linalg.norm(position - q) < 1e-6 else ('before q' if along < 0 else 'after q')
    return '%s; projected distance %.6g wu' % (label, along)


def render_cross_sections(out, dataset, filename='cross-sections.png'):
    groups = table_groups(dataset['tables']['cross-sections.csv']['rows'], ('route', 's_wu'))
    if not groups:
        empty_profiles(out, filename, dataset, 'Rejected candidate: no transverse samples exported')
        return
    columns = min(3, len(groups))
    rows_count = (len(groups) + columns - 1) // columns
    fig, axes = plt.subplots(rows_count, columns, figsize=(7 * columns, 5 * rows_count + 2),
                             sharey=True, squeeze=False)
    for ax, ((rid, s), rows) in zip(axes.flat, groups.items()):
        plot_height_curves(ax, rows, 'offset_wu')
        ax.set(title='Route %s; s=%.6g wu\n%s' % (rid, s, cross_station(rows, dataset)),
               xlabel='signed transverse offset (wu)')
    for ax in list(axes.flat)[len(groups):]:
        ax.axis('off')
    finish_figure(fig, out, filename,
                  'All exported before/at/after junction sections; no station filtering; shared y axis', dataset['outcome'])


def render_synthetic(out):
    folder = out / 'data/synthetic'
    report = A.load_json(folder / 'bed-validation.json')
    result = {'status': 'rejected' if not report['passed'] else 'awaiting-visual-review',
              'reason': 'SYNTHETIC ONLY: build accepted is not bed acceptance; final normal failures=%d/%d; '
                        'along=%s, junction=%s' % (report['constrained']['cross']['lowPointFailures'],
                        report['constrained']['cross']['sections'],
                        report['constrained']['longitudinal']['passed'], report['constrained']['junction']['passed'])}
    data = np.genfromtxt(folder / 'height-grid.csv', delimiter=',', names=True)
    A.require(data.shape == (193 * 257,), 'Synthetic fixed grid size drift')
    fig, axes = plt.subplots(1, 3, figsize=(22, 8))
    for ax, field, title in zip(axes, ('baseline_H', 'baseline_H', 'constrained_H'), TITLES):
        h = data[field].reshape(193, 257)
        gz, gx = np.gradient(h * A.Y_FACTOR, 12 / 192, 24 / 256)
        dot = (-gx * A.LIGHT[0] - gz * A.LIGHT[1] + A.LIGHT[2]) / np.sqrt(1 + gx*gx + gz*gz)
        image = .65 + .35 * np.maximum(0, dot)
        ax.imshow(image, origin='upper', extent=(-14, 10, 6, -6), interpolation='nearest',
                  cmap='gray', vmin=.65, vmax=1)
        ax.set(title='Synthetic: ' + title, xlabel='world x (wu)', ylabel='world z (wu)')
    finish_figure(fig, out, 'synthetic-height.png', 'Synthetic slope, NO helpers; NOT real mountain', result)
    dataset = {'outcome': result, 'tables': read_profiles(folder, {'parent', 'child-1', 'child-2'}),
               'reports': {'junction.json': {'junction': [0, 0], 'tangent': [1, 0]}}}
    render_profiles(out, dataset, 'synthetic-profiles.png')
    render_cross_sections(out, dataset, 'synthetic-cross-sections.png')


def write_outcome(out, dataset):
    j = dataset['reports']['junction.json']
    h = dataset['holdout']['reports']['junction.json']
    syn = A.load_json(out / 'data/synthetic/bed-validation.json')
    text = [
        'C0 单 Y 隔离实验：未通过，停在 C0；不是生产世界。',
        '固定完整 ridge4×scaler4×warp、四层波长、门限、A=.25、beta=1、k=1。',
        '正典：' + j['status'] + '；' + j['reason'],
        '留出 seed=12345：' + h['status'] + '；' + h['reason'],
        '只验证各自预定的首个 B 谷段，没有按 C0 成功与否重选地点。',
        '两个真实窗口拒绝后均逐位返回 B；三臂图相同不是修复成功。',
        '此处预算拒绝只证明当前共同床面/截面目标越界，不证明所有走线或所有合法床面不可行。',
        '合成例：沿程和汇点连通通过，但严格横剖失败 %d/%d，不能称高度首证成立。' % (
            syn['constrained']['cross']['lowPointFailures'], syn['constrained']['cross']['sections']),
        '当前公共目标床面会出现长平台；有限支撑端部和近分叉的法向低槽没有完整保证。',
        '未扩大幅度、删层或加第五层；未接生产；未实施 C1 自动世界父沟发现。',
        '性能仅是纯 Java 局部开销，真实候选被拒绝，不能作为成功接沟或 Minecraft 性能。',
        '采样完成与效果通过分开；--require-bed 的退出码承载 C0 失败。',
    ]
    (out / 'outcome.txt').write_text('\n'.join(text) + '\n', encoding='utf-8')


def render_settings(dataset):
    settings = dict(A.render_settings(False))
    for key in ('erosionOnlyHillshadeInput', 'longSeamCropsLocalBlocks', 'profiles', 'optionalColorCrops'):
        settings.pop(key, None)
    settings.update(arms=list(ARMS), titles=list(TITLES), starCropsLocalBlocks=A.STAR_CROPS,
                    junctionBoundsLocalBlocks=junction_bounds(dataset),
                    junctionExtentPolicy='q +/- 3*max(upstream,downstream,spread,width) wu, then HS=2; never fit to C',
                    outOfWindowPolicy='Fixed extent preserved; unsampled region gray, never extrapolated',
                    helperLinesOnlyIn='junction-routes.png', geometryHeightPolicy='exact frozen B',
                    profileSampling='Probe continuous final H from data CSV; no image interpolation',
                    profileAxes='shared y across real routes; s_wu/offset_wu unchanged',
                    syntheticControlArraysRequired=False, humanMorphologyReviewRequired=True)
    return settings


def required_plots(out):
    report = {}
    for name in REQUIRED_PLOTS:
        path = out / name
        A.require(path.is_file() and path.stat().st_size > 8, 'Missing required plot: ' + name)
        with path.open('rb') as stream:
            A.require(stream.read(8) == b'\x89PNG\r\n\x1a\n', 'Not a PNG: ' + name)
        report[name] = {'bytes': path.stat().st_size, 'sha256': A.sha(path)}
    return report


def write_reports(out, context, dataset, stats):
    locks = verify_locks(out, context, write_after=True)
    plots = required_plots(out)
    # Verification cannot hash itself. Manifest then hashes verification too.
    payload = {k: v for k, v in A.output_hashes(out).items() if k != 'verification.json'}
    verification = {'status': 'pass', 'scope': 'Output/data/immutable contracts ONLY; not valley acceptance',
                    'experiment': dataset['outcome'], 'holdoutExperiment': dataset['holdout']['outcome'],
                    'javaStagesAllExitZero': True, 'allLockedUnchanged': True, 'allSampledDataUnchanged': True,
                    'immutableComparisons': locks, 'lockedInputHashes': context['locked'],
                    'dataHashes': context['data'], 'executionHashes': context['record']['immutableOutputs'],
                    'plan': {'path': PLAN_KEY, 'contractSha256Before': context['locked'][PLAN_KEY],
                             'contractSha256After': plan_contract_sha(), 'contractUnchanged': True,
                             'rawSha256AtVerification': A.sha(PLAN),
                             'ignoredFields': 'TodoWrite-synchronized status values only'},
                    'syntheticBed': A.load_json(out / 'data/synthetic/bed-validation.json'),
                    'main': dataset['verification'], 'holdout': dataset['holdout']['verification'],
                    'requiredPlots': plots, 'probeReports': dataset['reports'],
                    'holdoutReports': dataset['holdout']['reports'],
                    'discoveryGate': dataset['discoveryGate'],
                    'holdoutDiscoveryGate': dataset['holdout']['discoveryGate'],
                    'csv': {name: {'sha256': value['sha256'], 'header': value['header'],
                                   'rowCount': len(value['rows']), 'routeIds': value['routeIds']}
                            for name, value in dataset['tables'].items()},
                    'candidatesCsv': {'sha256': dataset['candidates']['sha256'],
                                      'rowCount': len(dataset['candidates']['rows'])},
                    'helperRouteOverlapIsNotValleyEvidence': True, 'morphologyAccepted': False,
                    'humanMorphologyReviewRequired': True,
                    'outputHashExcludes': ['verification.json (self)', 'manifest.json (hashes verification)'],
                    'outputHashes': payload}
    A.save_json(out / 'verification.json', verification)
    manifest = {'kind': KIND, 'status': 'complete', 'completedUtc': A.utc(),
                'experimentStatus': dataset['outcome']['status'], 'outcome': dataset['outcome'],
                'holdoutOutcome': dataset['holdout']['outcome'],
                'renderOnly': context['renderOnly'], 'samplingRun': context['record'],
                'commands': context['commands'], 'sourceHashes': context['activeSources'],
                'sourcesAtSampling': context['sampledSources'],
                'rendererChangedSinceSampling': context['activeSources'][SCRIPT] != context['sampledSources'][SCRIPT],
                'bBaseline': BASELINE.as_posix(), 'frozenOutputExclusions': [],
                'snapshotPolicy': 'Current src/main, complete A/B outputs, A/B code and plan locked from run start; '
                                  'no obsolete A/B kernel SHA requirement; pre-run additive capacity methods permitted',
                'lockedBeforeSha256': A.sha(out / 'locked-before.json'),
                'lockedAfterSha256': A.sha(out / 'locked-after.json'),
                'planContractSha256': context['locked'][PLAN_KEY], 'allLockedUnchanged': True,
                'allDataUnchanged': True, 'sampling': dataset['sampling'],
                'holdoutSampling': dataset['holdout']['sampling'], 'render': render_settings(dataset),
                'statisticsSha256': A.sha(out / 'statistics.json'),
                'verificationSha256': A.sha(out / 'verification.json'),
                'bedCheck': stats['outcome']['bedCheck'], 'morphologyAccepted': False,
                'discoveryGate': dataset['discoveryGate'],
                'productionWiring': False, 'infiniteWorldProven': False,
                'outputHashExcludes': ['manifest.json (self-reference)'],
                'outputHashes': A.output_hashes(out)}
    A.save_json(out / 'manifest.json', manifest)
    require_hashes(manifest['outputHashes'], A.output_hashes(out), 'Full output hash verification')
    validate_commands(out, context['commands'])
    verify_locks(out, context)
    return verification


def record_failure(out, context, error):
    failure = {'kind': KIND, 'status': 'failed', 'timeUtc': A.utc(), 'error': repr(error),
               'traceback': traceback.format_exc(), 'commands': context['commands'],
               'morphologyAccepted': False, 'productionWiring': False}
    try:
        failure['immutableComparisons'] = verify_locks(out, context, write_after=True)
        failure['allLockedUnchanged'] = True
    except Exception as lock_error:
        failure.update(allLockedUnchanged=False, lockError=repr(lock_error))
    failure['requiredPlotPresence'] = {name: (out / name).is_file() for name in REQUIRED_PLOTS}
    A.save_json(out / 'failure.json', failure)
    failure['outputHashExcludes'] = ['verification.json (self)', 'manifest.json (hashes verification)']
    failure['outputHashes'] = {k: v for k, v in A.output_hashes(out).items() if k != 'verification.json'}
    A.save_json(out / 'verification.json', failure)
    failure.update(outputHashExcludes=['manifest.json (self-reference)'], outputHashes=A.output_hashes(out))
    A.save_json(out / 'manifest.json', failure)


def execute(out, render_only=False):
    context = prepare(out, render_only)  # Preflight failure never overwrites an old run.
    try:
        if not render_only:
            sample(out, context['commands'])
            freeze_sampling(out, context)
        dataset = load_dataset(out)
        stats = statistics(out, dataset)
        # No passed/accepted gate here: rejected and unchanged samples MUST be plotted.
        render_heights(out, dataset)
        render_profiles(out, dataset)
        render_cross_sections(out, dataset)
        render_synthetic(out)
        write_outcome(out, dataset)
        write_reports(out, context, dataset, stats)
        print(A.json.dumps({'outcome': dataset['outcome'],
                            'signedDifferenceCminusB': stats['main']['signedDifferenceCminusB'],
                            'maxAbsDifferenceCminusB_H': stats['main']['maxAbsDifferenceCminusB_H'],
                            'bedValidationAndEBudgetReport': dataset['reports']['bed-validation.json']},
                           indent=2, ensure_ascii=False, allow_nan=False), flush=True)
        print('DONE', out, '|', dataset['outcome']['status'],
              '| bed evidence is not human morphology acceptance; NOT production', flush=True)
        return dataset['outcome']
    except Exception as error:
        record_failure(out, context, error)
        raise


def main():
    parser = argparse.ArgumentParser(description=__doc__,
                                     formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('out', help='NEW direct build child, or this runner\'s frozen C0 directory')
    parser.add_argument('--render-only', action='store_true', help='verify frozen run and render, no Java')
    parser.add_argument('--require-bed', action='store_true',
                        help='after preserving every plot/report, exit 2 unless main/holdout/synthetic final beds pass')
    args = parser.parse_args()
    try:
        out = output_path(args.out, args.render_only)
        execute(out, args.render_only)
        if args.require_bed:
            reports = [A.load_json(out / 'data' / sub / 'bed-validation.json')
                       for sub in ('', 'holdout', 'synthetic')]
            if not all(report['passed'] for report in reports):
                print('C0 BED GATE FAIL: complete evidence retained; not accepted', flush=True)
                return 2
        return 0  # Evidence command only; --require-bed carries the separate numerical gate.
    except Exception:
        traceback.print_exc()
        return 1


if __name__ == '__main__':
    sys.exit(main())
