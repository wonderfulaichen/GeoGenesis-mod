"""NW T0 only: freeze old evidence and prepare actual-sampler requests; no terrain.

prepare is the only enabled phase. Analysis waits for the owner's Java samples
and complete contributor table. Never extend the selected NW source past -32wu.
"""
import argparse
import csv
import importlib.util
import json
import sys
from pathlib import Path

import numpy as np

sys.dont_write_bytecode = True
SCRIPT = Path(__file__).resolve()
SPEC = importlib.util.spec_from_file_location('old_route_l0_t0', SCRIPT.with_name('old-route-l0.py'))
L0 = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(L0)
ROOT = L0.ROOT
REFERENCE = L0.REFERENCE
L0_INPUT = ROOT / 'build/old-route-l0-20261104/prepare-fresh-01'
OUTPUT = ROOT / 'build/old-route-terminal-t0-20261104'
SOURCE_ID = '9139912035078620160:strong-window:-170:-183'
HEADER = L0.HEADER


def snapshot(base):
    return {p.relative_to(base).as_posix(): L0.digest(p)
            for p in sorted(base.rglob('*')) if p.is_file()}


def frozen_state():
    palette = ROOT / 'build/fast-erosion-scale-only-20261103-final/data/project-palette.csv'
    return {'main': snapshot(ROOT / 'src/main'),
            'strongOutputs': snapshot(REFERENCE), 'L0Inputs': snapshot(L0_INPUT),
            'dependencies': {str(p.relative_to(ROOT)): L0.digest(p) for p in
                             [SCRIPT.with_name('old-route-l0.py'), palette]},
            'plan': L0.digest(ROOT.parent / '.openbitfun/plans/old-route-terminal-uphill-local.plan.md')}


def freeze(output):
    state = frozen_state()
    manifest = json.loads((REFERENCE / 'manifest.json').read_text('utf-8'))
    errors = []
    for category, base in [('outputHashes', REFERENCE), ('sourceHashes', ROOT)]:
        for name, expected in manifest[category].items():
            p = base / name
            if not p.is_file() or L0.digest(p) != expected:
                errors.append(category + ':' + name)
    L0.save(output / 'frozen-inputs.json', {
        'sha256': state, 'scriptSha256': L0.digest(SCRIPT),
        'strongManifestMismatches': errors,
        'scope': 'current main snapshot, complete strong output tree and complete L0 input tree; read only'})
    if errors:
        raise ValueError('Old strong baseline differs from manifest: ' + repr(errors))
    return state


def tail_stations(route):
    length = float(route['s'][-1])
    if length < 32:
        raise ValueError('Selected source is shorter than approved tail32')
    raw = [(float(t), 'fine') for t in L0.stations(-32., 0., .015625)]
    raw += [(float(t), 'shifted') for t in np.arange(-32. + .0067, 0., .015625)]
    raw += [(float(s - length), 'vertex') for s in route['s'] if s >= length - 32.]
    raw += [(-32., 'boundary'), (0., 'boundary')]
    raw.sort(key=lambda a: a[0])
    clusters = []
    for value in raw:
        if not clusters or value[0] - clusters[-1][0][0] > 1e-10:
            clusters.append([value])
        else:
            clusters[-1].append(value)
    priority = {'vertex': 0, 'boundary': 1, 'fine': 2, 'shifted': 3}
    chosen = [min(c, key=lambda a: priority[a[1]])[0] for c in clusters]
    points, _ = L0.at(route, length + np.asarray(chosen))
    return np.asarray(chosen), points, clusters


def intersects(lo, hi, a, b):
    return np.all(hi >= a, axis=-1) & np.all(lo <= b, axis=-1)


def route_tail(route, distance=20.):
    offsets = L0.stations(-min(distance, route['s'][-1]), 0., .5)
    return L0.at(route, route['s'][-1] + offsets)[0]


def complete_shared_search(routes, lo, hi):
    """Complete segment-AABB broad phase; exact L0 project for every survivor."""
    segment_lo = [np.minimum(r['p'][:-1], r['p'][1:]) for r in routes]
    segment_hi = [np.maximum(r['p'][:-1], r['p'][1:]) for r in routes]
    all_lo, all_hi = np.concatenate(segment_lo), np.concatenate(segment_hi)
    owners = np.concatenate([np.full(len(v), i, dtype=int) for i, v in enumerate(segment_lo)])
    sources, pairs, tested = [], [], 0
    for i, route in enumerate(routes):
        if route['s'][-1] < 20:
            continue
        # Include actual original vertices: the .5 stations alone do not bound bends.
        s = route['s']
        p = np.vstack([L0.at(route, s[-1] - 20)[0], route['p'][s >= s[-1] - 20]])
        if not np.any(intersects(np.minimum(p[:-1], p[1:]),
                                 np.maximum(p[:-1], p[1:]), lo, hi)):
            continue
        sources.append(route['id'])
        a, b = p.min(axis=0) - 1., p.max(axis=0) + 1.
        candidates = np.unique(owners[intersects(all_lo, all_hi, a, b)])
        for j in candidates:
            if j == i:
                continue
            evidence = L0.pair_evidence(route, routes[j])
            tested += 1
            if evidence['shared']:
                pairs.append({'source': route['id'], 'target': routes[j]['id'],
                              'geometry': evidence,
                              'classification': 'unknown; protected provisionally, geometry alone insufficient'})
    return pairs, {'sourceIds': sources, 'exactPairsTested': tested,
                   'totalFrozenRoutes': len(routes),
                   'selection': 'all source final20 original segment bounds intersect NW tail32 AABB+8; '
                                'all 8100 target segments tested by expanded source-tail AABB, then exact project; '
                                'necessary conservative broad phase, no k16 limit',
                   'sharedRule': 'tail20 @.5; max gap<=1, min cos>=.8, strict target arc advance, end interior>=2'}


class Requests:
    def __init__(self, output):
        self.stream = (output / 'requests.csv').open('x', newline='', encoding='utf-8')
        self.writer = csv.writer(self.stream)
        self.writer.writerow(HEADER)
        self.counts = {}
        self.count = 0

    def add(self, group, kind, ts, points, source, target=''):
        ids = []
        for t, (x, z) in zip(ts, points):
            rid = f'T0-{self.count:08d}'
            self.writer.writerow([rid, group, kind, format(float(t), '.17g'),
                                  format(float(x), '.17g'), format(float(z), '.17g'), source, target])
            self.count += 1
            ids.append(rid)
        self.counts[group + '/' + kind] = len(ids)
        return ids

    def close(self):
        self.stream.close()


def reuse_grid(output, source):
    checks = json.loads((L0_INPUT / 'actual/checks.json').read_text('utf-8'))
    if not checks.get('passed'):
        raise ValueError('L0 sampler checks failed')
    rows = []
    with (L0_INPUT / 'actual/samples.csv').open(encoding='utf-8') as stream:
        reader = csv.DictReader(stream)
        if reader.fieldnames != L0.SAMPLES_HEADER:
            raise ValueError('Unexpected L0 actual header')
        for row in reader:
            if row['group'] == 'NW-near' and row['kind'] == 'grid':
                if row['source'] != source['id']:
                    raise ValueError('Wrong L0 NW source')
                rows.append(row)
    if len(rows) != 129 ** 2:
        raise ValueError('Incomplete L0 NW-near actual grid')
    with (output / 'reused-L0-NW-near-grid.csv').open('x', newline='', encoding='utf-8') as stream:
        writer = csv.DictWriter(stream, fieldnames=L0.SAMPLES_HEADER)
        writer.writeheader()
        writer.writerows(rows)
    return rows


def supplemental_grid(requests, old, lo, hi, source):
    origin = np.array([float(old[0]['x']), float(old[0]['z'])])
    start = np.ceil((lo - origin) / .25).astype(int)
    stop = np.floor((hi - origin) / .25).astype(int)
    indices = [(x, z) for z in range(start[1], stop[1] + 1)
               for x in range(start[0], stop[0] + 1) if not (0 <= x <= 128 and 0 <= z <= 128)]
    if indices:
        points = origin + .25 * np.array(indices)
        requests.add('NW-T0', 'grid-supplement', np.arange(len(points)), points, source['id'])
    return {'originWu': origin.tolist(), 'stepWu': .25, 'indexMin': start.tolist(),
            'indexMax': stop.tolist(), 'newCount': len(indices), 'reusedCount': len(old),
            'rule': 'same L0 lattice; actual samples only, no interpolation; new points inside tail32 AABB+8'}


def shared_requests(requests, pairs, lookup):
    for index, pair in enumerate(pairs):
        source, target = lookup[pair['source']], lookup[pair['target']]
        group = f'NW-shared-{index:04d}'
        pair['group'] = group
        # Actual longitudinal profiles of BOTH existing paths, never a joining segment.
        offsets = L0.stations(-20., 0., .03125)
        points, tangent = L0.at(source, source['s'][-1] + offsets)
        requests.add(group, 'shared-source', offsets, points, source['id'], target['id'])
        gap, arc, projected, direction = L0.project(points, target)
        requests.add(group, 'shared-target', arc, projected, source['id'], target['id'])
        # Transverse actual samples for low-trough evidence, not synthesized heights.
        for k, back in enumerate([20., 10., 0.]):
            point, direction = L0.at(source, source['s'][-1] - back)
            normal = np.array([-direction[0, 1], direction[0, 0]])
            ts = L0.stations(-2., 2., .125)
            requests.add(group, f'shared-cross-{k}', ts, point + ts[:, None] * normal,
                         source['id'], target['id'])


def contract(source, counts, pairs, search, grid):
    return {'scope': 'NW T0 prepare only; no 2D candidate, no new heights, no production',
            'source': SOURCE_ID, 'sourceLengthWu': float(source['s'][-1]),
            'interface': {'requests': HEADER, 'samples': L0.SAMPLES_HEADER,
                          'actualSamplesPath': 'actual/samples.csv',
                          'actualChecksPath': 'actual/checks.json',
                          'requireChecksPassed': True,
                          'coords': 'world wu, same L0 Java original sampler and Cut; HS=2',
                          'tail': 'kind=tail-source; t signed original arc from frozen endpoint, [-32,0]',
                          'shift': 'along original arc +.0067wu from -32 then step .015625, not lateral',
                          'sharedTargetT': 'absolute original target arc from exact projection; no path bridge',
                          'contributors': 'owner supplies complete contributor IDs/depth table keyed by request id; count is insufficient'},
            'requestCount': sum(counts.values()), 'requestCounts': counts,
            'deduplication': 'within NW tail only: sorted t clusters span<=1e-10wu from cluster first; '
                             'priority original vertex > boundary > fine > shifted; no coordinate rounding; '
                             'full raw membership retained; other profiles intentionally not deduplicated across IDs',
            'sharedPairs': pairs, 'sharedSearch': search, 'localGrid': grid,
            'renderContract': {'reuse': 'L0.plot_setup / terrain_rgb / map_panel',
                               'palette': 'project palette', 'e': '.02+1.5H', 'range': [-.35, 1.2],
                               'light': [-.5, -.3, 1], 'HS': 2, 'Yproxy': '354.66H',
                               'budgetLabel': 'ONE-DIMENSIONAL BUDGET ONLY, NOT CANDIDATE TERRAIN'},
            'analysisLocked': {'eta': .9, 'end': 'dN=C_N (actual combined depth)',
                               'recurrence': 'd_i=min(C_i,d_next+.9*(b_i-b_next))',
                               'uphill': 'true negative original drop rejects; never clip to zero',
                               'touchesMinus32Conclusion': '需要>32wu，精确接回长度本授权未测，32内不可行',
                               'metrics': ['affected length / touches -32 vs local reconnection',
                                           'depth integral loss and retention (H*wu and Yproxy*wu)',
                                           'maximum depth loss, maximum depth retained, affected length',
                                           'protected intersections: geometric distance and actual world positions'],
                               'protection': 'geometry only or missing low-trough/profile evidence remains unknown and conservatively protected; '
                                             'unknown overlap is NOT a confirmed conflict',
                               'limit': 'do not extend NW beyond -32; 1D match is not 2D feasibility'},
            'status': 'PREPARED: awaiting owner Java samples and contributor detail; no analysis yet'}


def prepare(output):
    output.mkdir(parents=True, exist_ok=False)
    before = freeze(output)
    routes = L0.load_routes()
    lookup = {r['id']: r for r in routes}
    source = lookup[SOURCE_ID]
    ts, points, memberships = tail_stations(source)
    lo, hi = points.min(axis=0) - 8., points.max(axis=0) + 8.
    pairs, search = complete_shared_search(routes, lo, hi)
    old = reuse_grid(output, source)
    requests = Requests(output)
    try:
        ids = requests.add('NW-T0', 'tail-source', ts, points, SOURCE_ID)
        L0.save(output / 'tail-station-membership.json', [
            {'id': rid, 't': float(t), 'inputs': [{'t': raw, 'kind': kind} for raw, kind in members]}
            for rid, t, members in zip(ids, ts, memberships)])
        grid = supplemental_grid(requests, old, lo, hi, source)
        grid['allowedAabbWu'] = [lo.tolist(), hi.tolist()]
        shared_requests(requests, pairs, lookup)
    finally:
        requests.close()
    record = contract(source, requests.counts, pairs, search, grid)
    record['requestsSha256'] = L0.digest(output / 'requests.csv')
    record['reusedGridSha256'] = L0.digest(output / 'reused-L0-NW-near-grid.csv')
    L0.save(output / 'prepare-interface.json', record)
    after = frozen_state()
    L0.save(output / 'prepare-checks.json', {
        'passed': before == after, 'frozenInputsUnchanged': before == after,
        'tailStationCount': len(ts), 'tailMinMaxWu': [float(ts[0]), float(ts[-1])],
        'strictIncreasingTail': bool(np.all(np.diff(ts) > 0)),
        'sharedPairCount': len(pairs), 'newRequestCount': requests.count,
        'noTerrainCreated': True, 'phase': 'prepare only; WAITING_FOR_SAMPLES'})
    if before != after:
        raise ValueError('Frozen input changed during preparation')
    print(json.dumps({'output': str(output), 'requests': requests.count,
                      'tailStations': len(ts), 'sharedPairs': len(pairs),
                      'status': record['status']}, indent=2))


def verify_frozen(output):
    frozen = json.loads((output / 'frozen-inputs.json').read_text('utf-8'))
    current = frozen_state()
    if frozen['sha256'] != current:
        raise ValueError('Complete frozen-input verification failed')
    return {'passed': True, 'counts': {k: len(v) for k, v in current.items()
                                      if isinstance(v, dict)},
            'strongTreeIncludesManifest': True,
            'strongNonManifestFiles': len(current['strongOutputs']) - 1,
            'preparedScriptSha256': frozen['scriptSha256'],
            'analysisScriptSha256': L0.digest(SCRIPT),
            'scriptChange': 'analysis/render added after prepare; inputs unchanged'}


def actual_rows(output, meta):
    from itertools import zip_longest
    for name in ['checks.json', 'contributors-checks.json']:
        if not json.loads((output / 'actual' / name).read_text('utf-8')).get('passed'):
            raise ValueError('Sampler check failed: ' + name)
    if L0.digest(output / 'requests.csv') != meta['requestsSha256']:
        raise ValueError('Requests changed')
    groups, by_id = {}, {}
    with (output / 'requests.csv').open() as rf, (output / 'actual/samples.csv').open() as sf:
        req, samples = csv.DictReader(rf), csv.DictReader(sf)
        if samples.fieldnames != L0.SAMPLES_HEADER:
            raise ValueError('Sample header mismatch')
        for a, b in zip_longest(req, samples):
            if a is None or b is None:
                raise ValueError('Sample length mismatch')
            for k in HEADER:
                if (float(a[k]) != float(b[k]) if k in ['t', 'x', 'z'] else a[k] != b[k]):
                    raise ValueError('Sample echo mismatch ' + k)
            for k in ['t', 'x', 'z', 'H0', 'Dsource', 'Dtarget', 'Dcombined', 'Hstrong']:
                b[k] = float(b[k])
                if not np.isfinite(b[k]):
                    raise ValueError('Nonfinite actual sample')
            if not (0 <= b['Dsource'] <= b['Dcombined'] + 1e-14 and
                    0 <= b['Dtarget'] <= b['Dcombined'] + 1e-14 and b['Dcombined'] <= .08):
                raise ValueError('Depth bounds failed')
            if abs(b['H0'] - b['Dcombined'] - b['Hstrong']) > 1e-14:
                raise ValueError('Height identity failed')
            if b['id'] in by_id:
                raise ValueError('Duplicate actual ID')
            by_id[b['id']] = b
            groups.setdefault(b['group'], {}).setdefault(b['kind'], []).append(b)
    if len(by_id) != meta['requestCount']:
        raise ValueError('Actual row count mismatch')
    return groups, by_id


def verify_contributors(output, by_id):
    counts, maxima, winners, seen = {}, {}, {}, set()
    positive = 0
    # Streaming full 2.9M-row audit: O(request count) memory, no contributor truncation.
    with (output / 'actual/contributors.csv').open() as stream:
        for r in csv.DictReader(stream):
            rid, depth = r['id'], float(r['depth'])
            if rid not in by_id or not 0 < depth <= .08:
                raise ValueError('Invalid contributor')
            counts[rid] = counts.get(rid, 0) + 1
            if depth > maxima.get(rid, -1):
                maxima[rid], winners[rid] = depth, r['trace']
            positive += 1
    for rid, row in by_id.items():
        if counts.get(rid, 0) != int(row['contributors']):
            raise ValueError('Full contributor count mismatch ' + rid)
        if maxima.get(rid, 0.) != row['Dcombined']:
            raise ValueError('Full contributor max mismatch ' + rid)
    with (output / 'actual/attribution-checks.csv').open() as stream:
        for r in csv.DictReader(stream):
            if r['id'] in seen or r['id'] not in by_id or r['passed'].lower() != 'true':
                raise ValueError('Attribution row failed')
            seen.add(r['id'])
    if len(seen) != len(by_id):
        raise ValueError('Incomplete attribution checks')
    return {'passed': True, 'positiveRows': positive, 'points': len(by_id),
            'allCountsAndMaximaExact': True, 'attributionRows': len(seen),
            'winnerTie': 'sampler first-route tie policy; maxima audited independently'}


def slope_audit(rows, field):
    t, h = L0.column(rows, 't'), L0.column(rows, field)
    delta = np.diff(h)
    ulp = 8 * np.maximum(np.abs(np.spacing(h[:-1])), np.abs(np.spacing(h[1:])))
    near = np.diff(t) <= 1e-10
    genuine = delta > ulp
    return {'maxRiseH': float(max(0., delta.max(initial=0.))),
            'maxSignedDeltaH': float(delta.max(initial=-np.inf)),
            'trueUphillSteps': int(genuine.sum()),
            'ulpOnlyPositiveSteps': int(((delta > 0) & ~genuine).sum()),
            'nearDuplicateSteps': int(near.sum()),
            'trueUphillNearDuplicateSteps': int((genuine & near).sum()),
            'ulpRule': 'positive delta > 8*max(abs(spacing(adjacent heights))) is true uphill',
            'nearDuplicateRule': 'arc separation <=1e-10wu; separately counted, never excuses true uphill',
            'trueUphillIntervals': [{'t0': float(t[i]), 't1': float(t[i+1]),
                                     'riseH': float(delta[i]), 'ulpAllowanceH': float(ulp[i])}
                                    for i in np.flatnonzero(genuine)]}


def backward_budget(b, c):
    b, c = np.asarray(b), np.asarray(c)
    rise = np.diff(b)
    ulp = 8*np.maximum(np.abs(np.spacing(b[:-1])), np.abs(np.spacing(b[1:])))
    if np.any(rise > ulp):
        raise ValueError('REJECT genuine original uphill; no clipping')
    d = c.copy()
    for i in range(len(d)-2, -1, -1):
        d[i] = min(c[i], d[i+1] + .9*(b[i]-b[i+1]))
    if np.any(d < 0) or np.any(d > c):
        raise ValueError('Budget outside original cut envelope')
    return d


def synthetic_checks():
    b = np.array([1., .98, .96, .95])
    c = np.array([.08, .07, .06, .01])
    expected = np.array([.055, .037, .019, .01])
    actual = backward_budget(b, c)
    assert np.allclose(actual, expected, rtol=0, atol=1e-15)
    assert np.all(np.diff(b-actual) <= 1e-15)
    assert actual[-1] == c[-1]
    rejected = False
    try:
        backward_budget([1., 1.001, .95], [.08, .06, .01])
    except ValueError:
        rejected = True
    assert rejected
    return {'passed': True, 'independentExpected': expected.tolist(),
            'computed': actual.tolist(), 'genuineUphillNegativeControlRejected': rejected,
            'noDropClipping': True}


def shared_evidence(meta, groups):
    entries = []
    for pair in meta['sharedPairs']:
        data = groups[pair['group']]
        profiles = {kind: {field: slope_audit(data[kind], field)
                           for field in ['H0', 'Hstrong']}
                    for kind in ['shared-source', 'shared-target']}
        crosses = {}
        for kind in ['shared-cross-0', 'shared-cross-1', 'shared-cross-2']:
            rows = data[kind]
            t, h = L0.column(rows, 't'), L0.column(rows, 'Hstrong')
            center = int(np.argmin(np.abs(t)))
            minimum = int(np.argmin(h))
            flank = min(h[0], h[-1])
            crosses[kind] = {'minimumOffsetWu': float(t[minimum]),
                'minimumHstrongH': float(h[minimum]),
                'centerHstrongH': float(h[center]), 'bothFlanksAboveMinimumY': float((flank-h[minimum])*354.66),
                'interiorLowTrough': bool(0 < minimum < len(h)-1 and flank > h[minimum] + 1e-12),
                'H0': L0.cross_evidence(rows)['H0'],
                'Hstrong': L0.cross_evidence(rows)['Hstrong']}
        downhill = all(v['trueUphillSteps'] == 0 for p in profiles.values() for v in p.values())
        low = all(c['interiorLowTrough'] for c in crosses.values())
        # Three crosses are evidence at stations, not an exhaustive 2D valley-surface proof.
        entries.append({'source': pair['source'], 'target': pair['target'], 'group': pair['group'],
            'profiles': profiles, 'crosses': crosses, 'sampledNoUphill': downhill,
            'sampledThreeCrossLowTrough': low,
            'classification': 'unknown', 'protected': True,
            'reason': 'sampled profiles/crosses only; full continuous shared low-trough surface not certified'
                      if downhill and low else 'uphill or insufficient low-trough evidence; not certified normal'})
    return entries


def polyroute(rows):
    p = np.c_[L0.column(rows, 'x'), L0.column(rows, 'z')]
    keep = np.r_[True, np.linalg.norm(np.diff(p, axis=0), axis=1) > 0]
    p = p[keep]
    return {'p': p, 's': np.r_[0., np.cumsum(np.linalg.norm(np.diff(p, axis=0), axis=1))]}


def overlap_evidence(tail, d, entries, groups):
    active = L0.column(tail, 'Dcombined') - d > 1e-12
    points = np.c_[L0.column(tail, 'x'), L0.column(tail, 'z')][active]
    tail_t = L0.column(tail, 't')[active]
    reports = []
    lookup = {r['id']: r for r in L0.load_routes()}
    for entry in entries:
        arms = []
        for kind in ['shared-source', 'shared-target']:
            rows = groups[entry['group']][kind]
            original = lookup[entry['source'] if kind == 'shared-source' else entry['target']]
            offset = original['s'][-1] if kind == 'shared-source' else 0.
            begin, end = rows[0]['t'] + offset, rows[-1]['t'] + offset
            stations = np.r_[begin, original['s'][(original['s'] > begin) &
                                                (original['s'] < end)], end]
            route = {'p': L0.at(original, stations)[0], 's': stations-begin}
            gap, arc, q, direction = L0.project(points, route)
            i = int(np.argmin(gap))
            original_t = arc[i] + begin - offset
            arms.append({'kind': kind, 'minimumDistanceWu': float(gap[i]),
                'tailStationWu': float(tail_t[i]), 'tailWorldWu': points[i].tolist(),
                'sharedWorldWu': q[i].tolist(), 'sharedStation': float(original_t),
                'centerlineIntersectionWithin1eMinus8': bool(gap[i] <= 1e-8),
                'withinCutSupport8Wu': bool(gap[i] <= 8),
                'withinCutPlusBasisEnvelope16Wu': bool(gap[i] <= 16),
                'tailStationsInside16Wu': int((gap <= 16).sum())})
        reports.append({'group': entry['group'], 'classification': entry['classification'], 'arms': arms,
                        'interpretation': 'unknown overlap is conservative protection, NOT confirmed conflict'})
    return {'relations': reports, 'basisSpacingWu': 4, 'cubicBasisSupportRadiusWu': 8,
        'oldCutSupportRadiusWu': 8,
        'envelope': 'distance<=16 is conservative old-cut radius8 plus cubic support radius8; '
                    'not exact valley-surface footprint or coefficient feasibility; no coefficients constructed',
        'normalCertifiedCount': sum(e['classification'] == 'normal' for e in entries),
        'unknownProtectedCount': len(entries)}


def metrics(tail, d):
    t, c, b = [L0.column(tail, k) for k in ['t', 'Dcombined', 'H0']]
    loss = c-d
    active = loss > 1e-12
    integral = getattr(np, 'trapezoid', np.trapz)
    total, kept = float(integral(c, t)), float(integral(d, t))
    touched = bool(active[0])
    return {'endDepthY': float(c[-1]*354.66), 'startOldDepthY': float(c[0]*354.66),
        'startAllowedDepthY': float(d[0]*354.66), 'maxDepthLossY': float(loss.max()*354.66),
        'maxRetainedDepthY': float(d.max()*354.66), 'integralOriginalHwu': total,
        'integralRetainedHwu': kept, 'integralLossHwu': total-kept,
        'integralLossYwu': (total-kept)*354.66, 'integralRetentionFraction': kept/total,
        'touchesMinus32': touched, 'measuredLengthWu': float(t[-1]-t[0]),
        'affectedSampleExtentWu': [float(t[active][0]), float(t[active][-1])],
        'affectedSegmentLengthWu': float(np.sum(np.diff(t)[active[:-1] | active[1:]])),
        'oldMaxRiseY': float(np.max((b-c)-np.minimum.accumulate(b-c))*354.66),
        'conclusion': '需要>32wu，精确接回长度本授权未测，32内不可行' if touched else
                      '1D sampled local reconnection only; NOT 2D feasibility',
        'confirmedGeometryLengthWu': 32., 'exactUpstreamRejoinLengthWu': None,
        'noExtensionBeyondMinus32': True}


def analyze(output):
    if (output / 'evidence.json').exists():
        raise FileExistsError('Refusing to overwrite evidence')
    frozen = verify_frozen(output)
    meta = json.loads((output / 'prepare-interface.json').read_text('utf-8'))
    groups, by_id = actual_rows(output, meta)
    contributors = verify_contributors(output, by_id)
    tests = synthetic_checks()
    tail = groups['NW-T0']['tail-source']
    audit = slope_audit(tail, 'H0')
    d = backward_budget(L0.column(tail, 'H0'), L0.column(tail, 'Dcombined'))
    entries = shared_evidence(meta, groups)
    protection = overlap_evidence(tail, d, entries, groups)
    result = {'scope': 'NW T0 only; ONE-DIMENSIONAL BUDGET, NOT CANDIDATE TERRAIN',
        'frozenVerification': frozen, 'contributorsValidation': contributors,
        'syntheticChecks': tests, 'originalSlopeAudit': audit,
        'eta': .9, 'endpoint': 'dN=C_N', 'noDropClipping': True,
        'metrics': metrics(tail, d), 'sharedRelations': entries, 'protection': protection,
        'inputHashes': {p.relative_to(output).as_posix(): L0.digest(p)
                        for p in sorted((output / 'actual').iterdir()) if p.is_file()},
        'limitations': ['No 2D candidate, no coefficient construction, no upstream extension',
                       'Three transverse sections cannot certify an entire shared low-trough surface',
                       'Unknown shared overlap is NOT a confirmed normal-surface conflict',
                       'Cubic basis support envelopes indicate restrictions, not feasibility']}
    with (output / 'budget-1d.csv').open('x', newline='', encoding='utf-8') as stream:
        writer = csv.writer(stream)
        writer.writerow(['id', 't', 'x', 'z', 'H0', 'Hstrong', 'Cold', 'dBudget', 'Hbudget', 'loss'])
        for row, depth in zip(tail, d):
            writer.writerow([row[k] for k in ['id', 't', 'x', 'z', 'H0', 'Hstrong']] +
                            [row['Dcombined'], depth, row['H0']-depth, row['Dcombined']-depth])
    result['postAnalysisFrozenVerification'] = verify_frozen(output)
    L0.save(output / 'evidence.json', result)
    lines = [result['scope'], result['metrics']['conclusion'],
             json.dumps(result['metrics'], ensure_ascii=False, indent=2),
             f'H0 true uphill steps={audit["trueUphillSteps"]}; ULP-only={audit["ulpOnlyPositiveSteps"]}; '
             f'near duplicate steps={audit["nearDuplicateSteps"]}; no drops clipped.',
             f'{len(entries)} shared relations: unknown/protected; no certified normal surface conflict claimed.',
             '4wu cubic basis has radius8wu; cut+basis16wu envelope only, no coefficients or 2D candidate.',
             'Full input hashes and contributor count/max audit PASS; synthetic recurrence and uphill rejection PASS.']
    (output / 'outcome.txt').write_text('\n'.join(lines)+'\n', encoding='utf-8')
    print('\n'.join(lines))


def render_maps(output, groups, evidence, plt, cmap, norm):
    with (output / 'reused-L0-NW-near-grid.csv').open() as stream:
        old = list(csv.DictReader(stream))
    rows = old + groups['NW-T0']['grid-supplement']
    xs = sorted(set(float(r['x']) for r in rows))
    zs = sorted(set(float(r['z']) for r in rows))
    xi, zi = {v: i for i, v in enumerate(xs)}, {v: i for i, v in enumerate(zs)}
    fig, axes = plt.subplots(1, 2, figsize=(17, 9))
    tail = groups['NW-T0']['tail-source']
    d = backward_budget(L0.column(tail, 'H0'), L0.column(tail, 'Dcombined'))
    p = np.c_[L0.column(tail, 'x'), L0.column(tail, 'z')]
    active = L0.column(tail, 'Dcombined')-d > 1e-12
    for ax, field in zip(axes, ['H0', 'Hstrong']):
        h = np.full((len(zs), len(xs)), np.nan)
        for row in rows:
            h[zi[float(row['z'])], xi[float(row['x'])]] = float(row[field])
        rgb = L0.terrain_rgb(h, .25, cmap, norm)
        rgb[~np.isfinite(rgb).all(axis=2)] = 1.
        ax.imshow(rgb, extent=[xs[0]-.125, xs[-1]+.125, zs[-1]+.125, zs[0]-.125])
        for index, entry in enumerate(evidence['sharedRelations']):
            r = groups[entry['group']]['shared-source']
            ax.plot(L0.column(r, 'x'), L0.column(r, 'z'), color='#00cfff', alpha=.35, lw=.7,
                    label='unknown shared (protected)' if index == 0 else None)
        ax.plot(p[:, 0], p[:, 1], color='yellow', lw=2.5, label='original tail32')
        ax.scatter(p[active][::8, 0], p[active][::8, 1], color='#ff3377', s=12,
                   zorder=5, label='necessary 1D reduction (every 8th point shown)')
        ax.scatter(p[[0, -1], 0], p[[0, -1], 1], c=['orange', 'red'], marker='x', s=45)
        ax.annotate('-32wu', p[0], color='black')
        ax.annotate('fixed end', p[-1], color='black')
        ax.set(xlim=(xs[0], xs[-1]), ylim=(zs[-1], zs[0]), title=field+' actual samples',
               xlabel='world x (wu)', ylabel='world z (wu)')
        ax.legend(fontsize=8)
    fig.suptitle('NW T0 | H0 / OLD strong only | 1D budget influence, NOT candidate terrain')
    fig.text(.01, .01, 'Fixed L0 palette/light | HS=2 Yproxy=354.66H | cyan=unknown protected; no certified normal surfaces | white=unsampled', fontsize=9)
    fig.tight_layout(rect=(0, .03, 1, .95))
    fig.savefig(output / 'figures/local-H0-old-strong.png', dpi=160)
    plt.close(fig)


def render_profiles_t0(output, groups, evidence, plt):
    tail = groups['NW-T0']['tail-source']
    t, b, c = [L0.column(tail, k) for k in ['t', 'H0', 'Dcombined']]
    d = backward_budget(b, c)
    fig, axes = plt.subplots(3, 1, figsize=(14, 12), sharex=True)
    for values, label, color in [(b, 'H0 actual', 'gray'), (b-c, 'OLD strong actual', 'black'),
                                 (b-d, '1D budget ONLY (not candidate)', '#b000cc')]:
        axes[0].plot(t, values*354.66, color=color, label=label)
    axes[1].plot(t, c*354.66, 'k', label='OLD combined depth')
    axes[1].plot(t, d*354.66, color='#b000cc', label='maximum retained 1D depth')
    axes[1].fill_between(t, d*354.66, c*354.66, alpha=.25, color='red', label='necessary depth loss')
    axes[2].plot(t, (c-d)*354.66, color='red', label='1D depth loss')
    for ax in axes:
        ax.legend(); ax.grid(alpha=.2); ax.set_ylabel('Yproxy'); ax.set_xlim(-32, 0)
    axes[-1].set_xlabel('original source arc from fixed endpoint (wu)')
    fig.suptitle('NW T0: fixed endpoint, eta=.9 | ONE-DIMENSIONAL BUDGET, NOT CANDIDATE TERRAIN')
    fig.tight_layout(rect=(0, 0, 1, .96))
    fig.savefig(output / 'figures/actual-profiles-1d-budget.png', dpi=160)
    plt.close(fig)
    fig, axes = plt.subplots(3, 1, figsize=(15, 12))
    for entry in evidence['sharedRelations']:
        data = groups[entry['group']]
        for field, ax in zip(['H0', 'Hstrong'], axes[:2]):
            r = data['shared-source']
            values = L0.column(r, field)
            ax.plot(L0.column(r, 't'), (values-values[-1])*354.66, alpha=.3, lw=.6)
            ax.set_title('59 actual shared-source '+field+' profiles (each relative to own end)')
        for kind in ['shared-cross-0', 'shared-cross-1', 'shared-cross-2']:
            r = data[kind]; h = L0.column(r, 'Hstrong')
            axes[2].plot(L0.column(r, 't'), (h-h[len(h)//2])*354.66, alpha=.12, lw=.5)
    axes[2].set_title('Actual Hstrong transverse sections; center-relative (not proof of normal surface)')
    for ax in axes:
        ax.set_ylabel('relative Yproxy'); ax.set_xlabel('original arc / signed transverse wu'); ax.grid(alpha=.2)
    fig.suptitle('Shared evidence: unknown conservatively protected; uphill and trough details in evidence.json')
    fig.tight_layout(rect=(0, 0, 1, .96))
    fig.savefig(output / 'figures/shared-actual-profiles.png', dpi=160)
    plt.close(fig)


def render(output):
    evidence = json.loads((output / 'evidence.json').read_text('utf-8'))
    for name, expected in evidence['inputHashes'].items():
        if L0.digest(output / name) != expected:
            raise ValueError('Actual input changed since analyze')
    frozen = verify_frozen(output)
    meta = json.loads((output / 'prepare-interface.json').read_text('utf-8'))
    groups, _ = actual_rows(output, meta)
    (output / 'figures').mkdir(exist_ok=False)
    plt, cmap, norm, palette = L0.plot_setup()
    render_maps(output, groups, evidence, plt, cmap, norm)
    render_profiles_t0(output, groups, evidence, plt)
    L0.save(output / 'manifest.json', {'scope': evidence['scope'], 'frozenVerification': frozen,
        'paletteSha256': L0.digest(palette), 'scriptSha256': L0.digest(SCRIPT),
        'inputHashes': evidence['inputHashes'],
        'outputs': {p.relative_to(output).as_posix(): L0.digest(p)
                    for p in [output / 'evidence.json', output / 'outcome.txt', output / 'budget-1d.csv']
                    + sorted((output / 'figures').glob('*.png'))},
        'noCandidateTerrain': True, 'noExtensionBeyond32': True})
    print('Rendered figures and manifest:', output)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['prepare', 'analyze', 'render'])
    parser.add_argument('--output', type=Path, default=OUTPUT,
                        help='Fresh output directory; existing directories are never overwritten')
    args = parser.parse_args()
    {'prepare': prepare, 'analyze': analyze, 'render': render}[args.command](args.output.resolve())


if __name__ == '__main__':
    main()
