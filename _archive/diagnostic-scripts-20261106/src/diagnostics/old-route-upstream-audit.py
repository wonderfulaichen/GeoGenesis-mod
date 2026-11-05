"""Read-only NW original-route audit. Prepare, then STOP for owner's Java samples.

No terrain synthesis, extension, route edits, production wiring or Java execution.
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
SPEC = importlib.util.spec_from_file_location('upstream_t0_helpers', SCRIPT.with_name('old-route-terminal-t0.py'))
T0 = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(T0)
L0 = T0.L0
ROOT = L0.ROOT
SOURCE = T0.SOURCE_ID
OUTPUT = ROOT / 'build/old-route-upstream-audit-20261104'
PREVIOUS = ROOT / 'build/old-route-terminal-t0-20261104/current-fresh-01'


def station_membership(route):
    length = float(route['s'][-1])
    raw = [(float(t), 'fine') for t in L0.stations(-length, 0., .015625)]
    raw += [(float(t), 'shifted') for t in np.arange(-length + .0067, 0., .015625)]
    raw += [(float(s-length), 'vertex') for s in route['s']]
    raw += [(-length, 'boundary'), (0., 'boundary')]
    raw.sort()
    clusters = []
    for item in raw:
        if not clusters or item[0]-clusters[-1][0][0] > 1e-10:
            clusters.append([item])
        else:
            clusters[-1].append(item)
    priority = {'vertex': 0, 'boundary': 1, 'fine': 2, 'shifted': 3}
    chosen = np.array([min(c, key=lambda v: priority[v[1]])[0] for c in clusters])
    return chosen, clusters


def intersect_route(route, lo, hi):
    return bool(np.any(T0.intersects(np.minimum(route['p'][:-1], route['p'][1:]),
                                   np.maximum(route['p'][:-1], route['p'][1:]), lo, hi)))


def runs(mask):
    bounds = np.diff(np.r_[False, mask, False].astype(int))
    return zip(np.flatnonzero(bounds == 1), np.flatnonzero(bounds == -1))


def geometry_stations(route):
    # Include vertices, avoiding floating-point near-duplicates before projectionadvance.
    values = np.sort(np.r_[L0.stations(0., route['s'][-1], .5), route['s']])
    return values[np.r_[True, np.diff(values) > 1e-10]]


def shared_intervals(source, target, lo, hi):
    s = geometry_stations(source)
    p, tangent = L0.at(source, s)
    gap, arc, q, direction = L0.project(p, target)
    cosine = np.sum(tangent*direction, axis=1)
    valid = (gap <= 1.) & (cosine >= .8)
    edge = valid[:-1] & valid[1:] & (np.diff(arc) > 1e-10)
    found = []
    for a, stop in runs(edge):
        b = stop  # edge a..stop-1 spans point a..stop, inclusive
        if s[b]-s[a] < 20.-1e-10:
            continue
        if not np.any(T0.intersects(np.minimum(p[a:b], p[a+1:b+1]),
                                    np.maximum(p[a:b], p[a+1:b+1]), lo, hi)):
            continue
        found.append({'source': source['id'], 'target': target['id'],
            'sourceArcWu': [float(s[a]), float(s[b])],
            'targetArcWu': [float(arc[a]), float(arc[b])],
            'sourceWorldWu': p[[a, b]].tolist(), 'targetWorldWu': q[[a, b]].tolist(),
            'lengthWu': float(s[b]-s[a]), 'maxGapWu': float(gap[a:b+1].max()),
            'minCos': float(cosine[a:b+1].min()),
            'minProjectionAdvanceWu': float(np.diff(arc[a:b+1]).min()),
            'targetEndInteriorWu': float(min(arc[b], target['s'][-1]-arc[b])),
            'targetDirection': 'strictly increasing original absolute target arc; never reverse or extend',
            'boundaryEvidence': {'previousSourceArcWu': float(s[a-1]) if a else None,
                                 'nextSourceArcWu': float(s[b+1]) if b+1 < len(s) else None},
            'geometrySamples': [{'sourceArcWu': float(s[i]), 'targetArcWu': float(arc[i]),
                                 'gapWu': float(gap[i]), 'cos': float(cosine[i]),
                                 'sourceWorldWu': p[i].tolist(), 'targetWorldWu': q[i].tolist()}
                                for i in range(a, b+1)],
            'status': 'geometry-supported finite shared interval; awaiting actual heights/troughs'})
    return found


def search(routes, lo, hi):
    seglo = np.concatenate([np.minimum(r['p'][:-1], r['p'][1:]) for r in routes])
    seghi = np.concatenate([np.maximum(r['p'][:-1], r['p'][1:]) for r in routes])
    owners = np.concatenate([np.full(len(r['p'])-1, i, dtype=int) for i, r in enumerate(routes)])
    local = [i for i, r in enumerate(routes) if intersect_route(r, lo, hi)]
    pairs, tested = [], 0
    for i in local:
        source = routes[i]
        # Whole source bounds are conservative; every target segment participates.
        a, b = source['p'].min(0)-1., source['p'].max(0)+1.
        candidates = np.unique(owners[T0.intersects(seglo, seghi, a, b)])
        for j in candidates:
            if i != j:
                pairs.extend(shared_intervals(source, routes[j], lo, hi))
                tested += 1
    for i, pair in enumerate(pairs):
        pair['id'] = f'SH-{i:04d}'
    return pairs, {'localSourceIds': [routes[i]['id'] for i in local],
                   'exactDirectedPairsTested': tested, 'totalRoutes': len(routes),
                   'totalSegments': len(seglo), 'broadPhase': 'complete segmentbounds, no kNN limit',
                   'rule': 'maximal sampled runs >=20wu; gap<=1 cos>=.8 and strict projectionadvance; '
                           'source @.5 plus original vertices; only finite runs intersecting NW AABB+8',
                   'boundaryPrecision': 'geometry boundary bracket <=.5wu; not continuous certification; '
                                        'actual profile samples refine evidence, never label full target shared'}


class Requests(T0.Requests):
    def add(self, group, kind, ts, points, source, target=''):
        key = group+'/'+kind
        if key in self.counts:
            raise ValueError('Request group/kind must be unique: '+key)
        return super().add(group, kind, ts, points, source, target)


def merge_intervals(intervals):
    merged = []
    for a, b in sorted(intervals):
        if merged and a <= merged[-1][1]+1e-10:
            merged[-1][1] = max(merged[-1][1], b)
        else:
            merged.append([a, b])
    return merged


def profile_requests(requests, pairs, lookup):
    intervals = {}
    for pair in pairs:
        for role in ['source', 'target']:
            intervals.setdefault(pair[role], []).append(pair[role+'ArcWu'])
    records = []
    for i, (rid, spans) in enumerate(sorted(intervals.items())):
        route = lookup[rid]
        for j, (a, b) in enumerate(merge_intervals(spans)):
            group = f'PROFILE-{i:03d}-{j:02d}'
            ts = np.unique(np.r_[L0.stations(a, b, .03125),
                                route['s'][(route['s'] >= a) & (route['s'] <= b)]])
            requests.add(group, 'shared-profile', ts, L0.at(route, ts)[0], rid)
            cross_arcs = L0.stations(a, b, 5.)
            for k, arc in enumerate(cross_arcs):
                p, tangent = L0.at(route, arc)
                normal = np.array([-tangent[0, 1], tangent[0, 0]])
                offsets = L0.stations(-2., 2., .125)
                requests.add(group, f'cross-{k:03d}', offsets,
                             p+offsets[:, None]*normal, rid)
            records.append({'group': group, 'route': rid, 'originalArcWu': [a, b],
                            'crossOriginalArcsWu': cross_arcs.tolist(),
                            'rule': 'union request optimization only; classify each finite SH interval separately'})
    return records


def controls(lookup):
    meta = json.loads((T0.L0_INPUT/'cases.json').read_text('utf-8'))
    result = [c for c in meta['cases'] if c['category'] == 'shared']
    if len(result) != 4:
        raise ValueError('Require all four original L0 shared controls')
    nw = next(c for c in result if c['group'] == 'NW-shared')
    if not (nw['source'].endswith(':-170:-184') and nw['target'].endswith(':-171:-183')):
        raise ValueError('Original NW shared control changed')
    return result


def fresh_directory(preferred):
    if not preferred.exists():
        return preferred
    for i in range(1, 10000):
        candidate = preferred/f'prepare-fresh-{i:02d}'
        if not candidate.exists():
            return candidate
    raise FileExistsError('No fresh output directory available')


def prepare(preferred):
    output = fresh_directory(preferred)
    output.mkdir(parents=True, exist_ok=False)
    before = T0.frozen_state()
    L0.save(output/'frozen-inputs.json', {'sha256': before, 'scriptSha256': L0.digest(SCRIPT),
            'previousEvidenceSha256': L0.digest(PREVIOUS/'evidence.json')})
    routes = L0.load_routes()
    lookup = {r['id']: r for r in routes}
    source = lookup[SOURCE]
    if abs(source['s'][-1]-158.0625) > 1e-9:
        raise ValueError('Unexpected NW full length')
    lo, hi = source['p'].min(0)-8., source['p'].max(0)+8.
    pairs, searched = search(routes, lo, hi)
    original_controls = controls(lookup)
    requests = Requests(output)
    try:
        ts, clusters = station_membership(source)
        ids = requests.add('NW-full', 'full-source', ts, L0.at(source, source['s'][-1]+ts)[0], SOURCE)
        L0.save(output/'station-membership.json', [
            {'id': rid, 't': float(t), 'inputs': [{'t': v, 'kind': k} for v, k in cluster]}
            for rid, t, cluster in zip(ids, ts, clusters)])
        xs = lo[0]+.25*np.arange(int(np.floor((hi[0]-lo[0])/.25))+1)
        zs = lo[1]+.25*np.arange(int(np.floor((hi[1]-lo[1])/.25))+1)
        xx, zz = np.meshgrid(xs, zs)
        if xx.size > 250000:
            raise ValueError('Local grid exceeds explicit 250000 point budget')
        requests.add('NW-local', 'grid', np.arange(xx.size), np.c_[xx.ravel(), zz.ravel()], SOURCE)
        profiles = profile_requests(requests, pairs, lookup)
        nw = next(c for c in original_controls if c['group'] == 'NW-shared')
        s, target = lookup[nw['source']], lookup[nw['target']]
        arc = L0.stations(s['s'][-1]-20., s['s'][-1], .03125)
        p = L0.at(s, arc)[0]
        requests.add('NW-L0-control', 'source', arc, p, s['id'], target['id'])
        _, target_arc, projected, _ = L0.project(p, target)
        requests.add('NW-L0-control', 'target-projected', target_arc, projected, s['id'], target['id'])
    finally:
        requests.close()
    if requests.count > 1000000:
        raise ValueError('Explicit one-million total actual-point budget exceeded')
    record = {'status': 'PREPARED_WAITING_FOR_OWNER_SAMPLES', 'scope': 'analysis only; no candidate terrain',
        'source': SOURCE, 'sourceLengthWu': float(source['s'][-1]),
        'requestsSha256': L0.digest(output/'requests.csv'), 'requestCount': requests.count,
        'requestCounts': requests.counts, 'interface': {'requests': L0.HEADER, 'samples': L0.SAMPLES_HEADER,
            'sampler': 'OldRouteL0Probe requests.csv NEW_actual_directory (owner runs Java)',
            'actualSamplesPath': 'actual/samples.csv', 'actualChecksPath': 'actual/checks.json',
            'sourceT': 'signed arc from fixed endpoint [-158.0625,0]; no extension',
            'sharedProfileT': 'absolute original route arc, strictly increasing',
            'projectedControlT': 'absolute original target arc, never relative-to-source',
            'contributors': 'NO full-table rerun. Reuse previous tail attribution by exact coordinates; '
                            'after samples, only unresolved collision stations receive small separate requests'},
        'stationDedup': {'rawCount': sum(map(len, clusters)), 'chosenCount': len(ts),
            'mergedCount': sum(len(c)-1 for c in clusters), 'toleranceWu': 1e-10,
            'rule': 'cluster span <=1e-10, prefer vertex/boundary/fine/shifted; retain all raw membership'},
        'localGrid': {'aabbWu': [lo.tolist(), hi.tolist()], 'shapeZX': [len(zs), len(xs)],
            'originWu': lo.tolist(), 'stepWu': .25, 'actualCount': int(xx.size),
            'lastWorldWu': [float(xs[-1]), float(zs[-1])], 'noRightGrid': True},
        'sharedSearch': searched, 'sharedIntervals': pairs, 'profileUnions': profiles,
        'L0ProtectedControls': original_controls,
        'controlPolicy': 'all four L0 shared controls remain protected; NW freshly sampled, other three '
                         'retain frozen L0 evidence, no additional regional grids',
        'analysisLocked': {'endpoint': 'dN=C_N', 'eta': .9, 'negativeControlEta': 1.,
            'recurrence': 'd_i=min(C_i,d_next+eta*(H0_i-H0_next)); never clip negative H0 drop',
            'trueH0Uphill': 'report every true uphill interval and reject eta=.9 downhill-margin feasibility; '
                            'eta=1 signed-drop necessary upper bound can still yield a conditional contradiction',
            'reductionIntervals': 'all maximal affected runs, boundary brackets and coordinates; '
                                  'report most upstream boundary and whether all reconnect before original start',
            'normalEvidence': 'finite actual H0/Hstrong downhill profiles plus interior low trough sections '
                              'support sampled-normal interval; not require continuous 2D proof; '
                              'separately list uphill/insufficient evidence and unsampled domain',
            'sharedProtection': 'only the actual finite SH interval, never entire target or NW source; '
                                'clip both route arms by their own increasing original arc bounds',
            'samePointConflict': 'at identical actual NW point inside supported normal interval, compare '
                                 'protected C with eta=1 maximum allowed; C>bound proves conditional '
                                 'fixed-end/no-deepening/original-line-nonincreasing contract impossible; '
                                 'report request id, coordinates, interval and minimum Yproxy conflict',
            'nonConflict': 'nearby distance/support overlap alone is NOT same-point contradiction',
            'limitations': 'eta=.9 is not the only physically possible case; no impossibility claim about '
                           'other 2D valley-floor repairs; finite samples not global surface proof'},
        'renderContract': {'helpers': 'L0.plot_setup/terrain_rgb; map extent strictly local source AABB+8',
                           'HS': 2, 'Yproxy': 354.66, 'terrainPanels': ['actual H0', 'actual old Hstrong'],
                           'noCandidateHeight': True}}
    L0.save(output/'prepare-interface.json', record)
    passed = before == T0.frozen_state()
    L0.save(output/'prepare-checks.json', {'passed': passed, 'frozenInputsUnchanged': passed,
        'strictIncreasingSourceT': bool(np.all(np.diff(ts) > 0)),
        'sourceBoundsWu': [float(ts[0]), float(ts[-1])], 'noJavaRun': True,
        'noCandidateTerrain': True, 'sharedIntervalCount': len(pairs), 'requestCount': requests.count})
    if not passed:
        raise ValueError('Frozen inputs changed during prepare')
    print(json.dumps({'output': str(output), 'requests': requests.count, 'sourceStations': len(ts),
                      'gridPoints': int(xx.size), 'sharedIntervals': len(pairs),
                      'profileUnions': len(profiles), 'status': record['status']}, indent=2))


def load_samples(output, meta):
    from itertools import zip_longest
    checks = json.loads((output/'actual/checks.json').read_text('utf-8'))
    if not checks.get('passed') or L0.digest(output/'requests.csv') != meta['requestsSha256']:
        raise ValueError('Sampler checks or request hash failed')
    groups, ids = {}, set()
    with (output/'requests.csv').open() as rf, (output/'actual/samples.csv').open() as sf:
        reader = csv.DictReader(sf)
        if reader.fieldnames != L0.SAMPLES_HEADER:
            raise ValueError('Sample header mismatch')
        for a, b in zip_longest(csv.DictReader(rf), reader):
            if a is None or b is None:
                raise ValueError('Request/sample length mismatch')
            for k in L0.HEADER:
                if (float(a[k]) != float(b[k]) if k in ['t', 'x', 'z'] else a[k] != b[k]):
                    raise ValueError('Request echo mismatch: '+k)
            for k in ['t', 'x', 'z', 'H0', 'Dsource', 'Dtarget', 'Dcombined', 'Hstrong']:
                b[k] = float(b[k])
                if not np.isfinite(b[k]):
                    raise ValueError('Nonfinite actual sample')
            if not (0 <= b['Dsource'] <= b['Dcombined']+1e-14 and
                    0 <= b['Dtarget'] <= b['Dcombined']+1e-14 and b['Dcombined'] <= .08):
                raise ValueError('Actual cut bounds failed')
            if abs(b['H0']-b['Dcombined']-b['Hstrong']) > 1e-14 or b['id'] in ids:
                raise ValueError('Height identity or duplicate ID failed')
            ids.add(b['id'])
            groups.setdefault(b['group'], {}).setdefault(b['kind'], []).append(b)
    if len(ids) != meta['requestCount']:
        raise ValueError('Actual count mismatch')
    return groups, checks


def verified_inputs(output):
    frozen = json.loads((output/'frozen-inputs.json').read_text('utf-8'))
    if frozen['sha256'] != T0.frozen_state():
        raise ValueError('Frozen source/main/strong/L0/plan changed')
    if frozen['previousEvidenceSha256'] != L0.digest(PREVIOUS/'evidence.json'):
        raise ValueError('Previous evidence changed')
    return {'passed': True, 'preparedScriptSha256': frozen['scriptSha256'],
            'currentScriptSha256': L0.digest(SCRIPT),
            'scriptChange': 'authorized analyze/render extension only',
            'frozenState': frozen['sha256']}


def uphill_mask(h):
    ulp = 8*np.maximum(np.abs(np.spacing(h[:-1])), np.abs(np.spacing(h[1:])))
    return np.diff(h) > ulp


def envelope(b, c, eta):
    d, witness = c.copy(), np.arange(len(c))
    for i in range(len(c)-2, -1, -1):
        limit = d[i+1]+eta*(b[i]-b[i+1])
        if limit < c[i]:
            d[i], witness[i] = limit, witness[i+1]
    return d, witness


def integrate(y, x):
    fn = getattr(np, 'trapezoid', None)
    return float(fn(y, x) if fn else np.trapz(y, x))


def point_record(rows, i):
    return {k: rows[i][k] for k in ['id', 't', 'x', 'z', 'H0', 'Dcombined', 'Hstrong']}


def budget_metrics(rows, d, eta):
    t, c = L0.column(rows, 't'), L0.column(rows, 'Dcombined')
    loss = c-d
    intervals = []
    for a, stop in runs(loss > 1e-12):
        b = stop-1
        intervals.append({'first': point_record(rows, int(a)), 'last': point_record(rows, int(b)),
            'sampleExtentWu': float(t[b]-t[a]),
            'upstreamUnchangedBracketT': float(t[a-1]) if a else None,
            'downstreamUnchangedBracketT': float(t[stop]) if stop < len(t) else None,
            'maxLossY': float(loss[a:stop].max()*354.66)})
    total = integrate(c, t)
    mask = t >= -32.
    # Exact tail32 boundary is present in the full fine lattice for this frozen length.
    tail_total = integrate(c[mask], t[mask])
    first = intervals[0]['first'] if intervals else None
    return {'eta': eta, 'intervals': intervals, 'intervalCount': len(intervals),
        'mostUpstreamAffectedT': first['t'] if first else None,
        'necessaryUpstreamReachWuFromEnd': -first['t'] if first else 0.,
        'affectedSampleExtentTotalWu': sum(v['sampleExtentWu'] for v in intervals),
        'affectedEdgeCoverageWu': float(np.diff(t)[(loss[:-1]>1e-12)|(loss[1:]>1e-12)].sum()),
        'touchesOriginalStart': bool(loss[0]>1e-12),
        'upstreamUnchangedPrefixWu': float(first['t']-t[0]) if first else float(-t[0]),
        'reconnectsBeforeStart1DOnly': bool(loss[0]<=1e-12),
        'maxLossY': float(loss.max()*354.66), 'endDepthY': float(c[-1]*354.66),
        'fullIntegralOriginalHwu': total, 'fullIntegralRetainedHwu': integrate(d, t),
        'fullRetentionFraction': integrate(d, t)/total,
        'tail32SampleStartT': float(t[mask][0]),
        'tail32RetentionFraction': integrate(d[mask], t[mask])/tail_total,
        'integralLossYwu': integrate(loss, t)*354.66,
        'negativeUpperBoundCount': int((d<0).sum()),
        'minimumUpperBoundY': float(d.min()*354.66),
        'not2DReconnectionProof': True}


def cross_report(rows, station):
    h, t = L0.column(rows, 'Hstrong'), L0.column(rows, 't')
    center, minimum = int(np.argmin(abs(t))), int(np.argmin(h))
    interior = 0 < minimum < len(h)-1 and min(h[0], h[-1]) > h[minimum]+1e-12
    excess = float((h[center]-h[minimum])*354.66)
    # Explicit finite observational criterion; not a valley-surface protection band.
    supported = interior and abs(t[minimum]) <= 1. and excess <= .1
    return {'arcWu': float(station), 'sampledLowTrough': bool(supported),
        'interiorMinimum': bool(interior), 'minimumOffsetWu': float(t[minimum]),
        'centerAboveMinimumY': excess,
        'lowerFlankAboveMinimumY': float((min(h[0], h[-1])-h[minimum])*354.66),
        'centerWorldWu': [rows[center]['x'], rows[center]['z']],
        'sampleId': rows[center]['id']}


def profile_evidence(meta, groups):
    profiles = []
    for spec in meta['profileUnions']:
        data = groups[spec['group']]
        rows = data['shared-profile']
        if spec['route'] == SOURCE:
            # Finest NW full-source samples outrank the coarser shared profile.
            length = meta['sourceLengthWu']
            a, z = spec['originalArcWu']
            rows = [dict(r, t=r['t']+length) for r in groups['NW-full']['full-source']
                    if a <= r['t']+length <= z]
        t = L0.column(rows, 't')
        b, h = L0.column(rows, 'H0'), L0.column(rows, 'Hstrong')
        downhill = ~(uphill_mask(b)|uphill_mask(h))
        crosses = [cross_report(data[f'cross-{i:03d}'], arc)
                   for i, arc in enumerate(spec['crossOriginalArcsWu'])]
        trough_bands = [[a['arcWu'], z['arcWu']] for a, z in zip(crosses[:-1], crosses[1:])
                        if a['sampledLowTrough'] and z['sampledLowTrough']]
        normal = []
        for start, stop in runs(downhill):
            # Point start..stop inclusive bounds the uninterrupted no-rise edges.
            for a, z in merge_intervals(trough_bands):
                lo, hi = max(float(t[start]), a), min(float(t[stop]), z)
                if hi > lo+1e-10:
                    normal.append([lo, hi])
        profiles.append({'group': spec['group'], 'route': spec['route'],
            'arcWu': spec['originalArcWu'], 'normalSubintervals': merge_intervals(normal),
            'crosses': crosses, 'H0Audit': T0.slope_audit(rows, 'H0'),
            'HstrongAudit': T0.slope_audit(rows, 'Hstrong'),
            'evidenceGrade': 'finite measured profiles + bracketing low-trough sections; between-cross surface not certified'})
    return profiles


def complement(begin, end, spans):
    gaps, cursor = [], begin
    for a, b in merge_intervals(spans):
        if a > cursor+1e-10:
            gaps.append([cursor, a])
        cursor = max(cursor, b)
    if cursor < end-1e-10:
        gaps.append([cursor, end])
    return gaps


def classify_shared(meta, profiles):
    entries = []
    for pair in meta['sharedIntervals']:
        entry = {k: pair[k] for k in ['id', 'source', 'target', 'sourceArcWu', 'targetArcWu',
                                      'lengthWu', 'maxGapWu', 'minCos']}
        entry['arms'] = {}
        for role in ['source', 'target']:
            a, b = pair[role+'ArcWu']
            normal = []
            for profile in profiles:
                if profile['route'] == pair[role]:
                    normal.extend([[max(a, lo), min(b, hi)] for lo, hi in profile['normalSubintervals']
                                   if min(b, hi) > max(a, lo)+1e-10])
            normal = merge_intervals(normal)
            entry['arms'][role] = {'sampledNormalIntervalsOriginalArcWu': normal,
                'notSupportedIntervalsOriginalArcWu': complement(a, b, normal),
                'grade': 'finite actual arm evidence; other arm assessed independently; not whole-target protection'}
        entries.append(entry)
    return entries


def conflict_reports(rows, d09, d1, witness, entries, lookup):
    p = np.c_[L0.column(rows, 'x'), L0.column(rows, 'z')]
    source = lookup[SOURCE]
    arc = L0.column(rows, 't')+source['s'][-1]
    tangent = L0.at(source, arc)[1]
    c = L0.column(rows, 'Dcombined')
    results = []
    for entry in entries:
        if SOURCE not in [entry['source'], entry['target']]:
            continue
        role = 'source' if entry['source'] == SOURCE else 'target'
        other_role = 'target' if role == 'source' else 'source'
        other = lookup[entry[other_role]]
        for a, b in entry['arms'][role]['sampledNormalIntervalsOriginalArcWu']:
            indices = np.flatnonzero((arc>=a)&(arc<=b))
            if not len(indices):
                continue
            gap, projected_arc, q, direction = L0.project(p[indices], other)
            cosine = np.sum(tangent[indices]*direction, axis=1)
            low, high = entry[other_role+'ArcWu']
            valid = (gap<=1.)&(cosine>=.8)&(projected_arc>=low)&(projected_arc<=high)
            indices = indices[valid]
            if not len(indices):
                continue
            delta = c[indices]-d1[indices]
            local = int(np.argmax(delta))
            i = int(indices[local])
            jj = int(witness[i])
            valid_positions = np.flatnonzero(valid)
            pos = int(valid_positions[local])
            results.append({'sharedId': entry['id'], 'NWRole': role, 'otherRoute': other['id'],
                'NWNormalOriginalArcWu': [a, b], 'NWNormalRelativeEndWu':
                    [float(a-source['s'][-1]), float(b-source['s'][-1])],
                'NWNormalWorldEndpointsWu': L0.at(source, [a, b])[0].tolist(),
                'testedNWActualPoints': len(indices),
                'eta1ConflictingPointCount': int((delta>1e-12).sum()),
                'eta1MaxNecessaryLossY': float(delta[local]*354.66),
                'eta09MaxNecessaryLossY': float((c[indices]-d09[indices]).max()*354.66),
                'witnessPoint': point_record(rows, i), 'downstreamBoundPoint': point_record(rows, jj),
                'eta1MaxAllowedDepthY': float(d1[i]*354.66),
                'protectedOldDepthY': float(c[i]*354.66),
                'otherProjection': {'distanceWu': float(gap[pos]), 'arcWu': float(projected_arc[pos]),
                    'worldWu': q[pos].tolist(), 'cos': float(cosine[pos]),
                    'samePointWithin1eMinus8': bool(gap[pos]<=1e-8)},
                'conditionalConclusion': 'CONFLICT if this finite measured-normal NW interval is kept pointwise unchanged'
                    if delta[local]>1e-12 else 'no eta1 contradiction at these finite points',
                'protectionLocation': 'the actual NW sample itself, NOT the nearby other-route projection',
                'global2DClaim': False})
    return results


def control_evidence(groups, lookup, rows):
    records = {}
    for kind in ['source', 'target-projected']:
        r = groups['NW-L0-control'][kind]
        p = np.c_[L0.column(r, 'x'), L0.column(r, 'z')]
        gap, arc, q, _ = L0.project(p, lookup[SOURCE])
        records[kind] = {'H0': T0.slope_audit(r, 'H0'), 'Hstrong': T0.slope_audit(r, 'Hstrong'),
            'distanceToNWMinWu': float(gap.min()), 'distanceToNWMaxWu': float(gap.max()),
            'within1WuCount': int((gap<=1).sum()), 'samePointWithin1eMinus8Count': int((gap<=1e-8).sum()),
            'closest': {'control': point_record(r, int(np.argmin(gap))),
                'NWArcWu': float(arc[np.argmin(gap)]), 'NWWorldWu': q[np.argmin(gap)].tolist()},
            'interpretation': 'distance<=1 is proximity only, NOT pointwise protection of NW'}
    old = json.loads((PREVIOUS/'evidence.json').read_text('utf-8'))
    original = old['sharedRelations'][0]
    records['frozenNWControlCrosses'] = original['crosses']
    control_groups = {}
    with (T0.L0_INPUT/'actual/samples.csv').open() as stream:
        for r in csv.DictReader(stream):
            if r['group'].endswith('-shared') and r['kind'] != 'grid':
                for k in ['t', 'x', 'z', 'H0', 'Hstrong', 'Dcombined']:
                    r[k] = float(r[k])
                control_groups.setdefault(r['group'], {}).setdefault(r['kind'], []).append(r)
    records['fourFrozenL0Controls'] = {}
    for group, data in control_groups.items():
        records['fourFrozenL0Controls'][group] = {
            'originalSourceTail32H0': T0.slope_audit(data['long-source'], 'H0'),
            'originalSourceTail32Hstrong': T0.slope_audit(data['long-source'], 'Hstrong'),
            'crosses': {k: cross_report(data[k], float(data[k][0]['t']))
                        for k in ['cross-0', 'cross-1', 'cross-2']},
            'scope': 'frozen original L0 sampled control, not whole route/global surface certificate'}
    records['allFourL0ControlsKept'] = True
    records['otherThreeEvidence'] = 'frozen L0 actual profiles/crosses retained and audited; not freshly resampled'
    return records


def synthetic_tests():
    b, c = np.array([1., .98, .96, .95]), np.array([.08, .07, .06, .01])
    d, _ = envelope(b, c, .9)
    assert np.allclose(d, [.055, .037, .019, .01], atol=1e-15, rtol=0)
    d1, w = envelope(b, c, 1.)
    assert np.allclose(d1, [.06, .04, .02, .01], atol=1e-15, rtol=0)
    assert c[0] > d1[0] and w[0] == 3
    up = np.array([1., 1.001, .95])
    signed, _ = envelope(up, np.array([.08, .06, .01]), 1.)
    assert uphill_mask(up).sum() == 1 and signed[0] < signed[1]
    # Original uphill need not itself be impossible under less cutting; do not reject eta1 outright.
    safe, _ = envelope(np.array([1., 1.001]), np.array([0., .01]), 1.)
    assert np.all(safe >= 0) and np.all(np.diff(np.array([1., 1.001])-safe) <= 0)
    impossible, _ = envelope(np.array([1., 1.1]), np.array([.08, 0.]), 1.)
    assert impossible[0] < 0  # do NOT clip this to zero
    assert list(runs(np.array([False, True, True, False, True]))) == [(1, 3), (4, 5)]
    return {'passed': True, 'independentEta09Expected': [.055, .037, .019, .01],
        'independentEta1Expected': [.06, .04, .02, .01],
        'protectedPointContradictionY': float((c[0]-d1[0])*354.66),
        'trueUphillDetectedNotClipped': True, 'uphillNotAutomaticallyEta1Impossible': True,
        'negativeNecessaryBoundRetained': True, 'allReductionRunExtraction': True}


def write_csv(path, header, rows):
    with path.open('x', newline='', encoding='utf-8') as stream:
        writer = csv.writer(stream)
        writer.writerow(header)
        writer.writerows(rows)


def analyze(output):
    if (output/'evidence.json').exists():
        raise FileExistsError('Refuse existing analysis')
    frozen = verified_inputs(output)
    meta = json.loads((output/'prepare-interface.json').read_text('utf-8'))
    groups, checks = load_samples(output, meta)
    rows = groups['NW-full']['full-source']
    b, c = L0.column(rows, 'H0'), L0.column(rows, 'Dcombined')
    d09, w09 = envelope(b, c, .9)
    d1, w1 = envelope(b, c, 1.)
    profiles = profile_evidence(meta, groups)
    entries = classify_shared(meta, profiles)
    lookup = {r['id']: r for r in L0.load_routes()}
    conflicts = conflict_reports(rows, d09, d1, w1, entries, lookup)
    result = {'scope': 'ONE-DIMENSIONAL NECESSARY BUDGET ONLY, NOT CANDIDATE TERRAIN',
        'frozenVerification': frozen, 'actualValidation': checks,
        'syntheticChecks': synthetic_tests(), 'originalH0Audit': T0.slope_audit(rows, 'H0'),
        'oldStrongAudit': T0.slope_audit(rows, 'Hstrong'),
        'eta09': budget_metrics(rows, d09, .9), 'eta1': budget_metrics(rows, d1, 1.),
        'eta09DownhillMarginValid': bool(not uphill_mask(b).any()),
        'signedDropsNeverClipped': True, 'endpoint': 'dN=C_N actual combined',
        'normalEvidenceCriteria': 'both H0 and old Hstrong profiles no >8ULP uphill; bracketed by '
            'two actual sections with interior min, min offset<=1wu, NW/arm center<=.1Y above min. '
            'Classify separately clipped finite arm subintervals, not entire route or 2D protection band.',
        'profiles': profiles, 'sharedRelations': entries, 'NWPointwiseProtectionTests': conflicts,
        'L0ControlEvidence': control_evidence(groups, lookup, rows),
        'conditionalEta1Contradiction': any(v['eta1MaxNecessaryLossY']>354.66e-12 for v in conflicts),
        'limitations': ['Sampled normal != continuous 2D certification; cross spacing 5wu and +-2wu only',
            'No shared-surface protection band is defined; no unconditional strict 2D impossibility claim',
            'Conditional contradiction protects actual NW measured-normal points themselves',
            'Near-coincidence <=1wu never treated as same coordinate; numerical exact threshold 1e-8wu labeled',
            'Eta.9 is a chosen margin, eta1 is necessary nonincrease bound; neither proves 2D feasibility',
            'Side-trough rerouting/other valley-floor solutions untested; all original paths unchanged',
            'No full contributors table rerun; combined actual depth suffices for same-point bound'],
        'inputHashes': {str(p.relative_to(output)): L0.digest(p) for p in
            [output/'requests.csv', output/'prepare-interface.json', output/'station-membership.json',
             output/'actual/samples.csv', output/'actual/checks.json', output/'frozen-inputs.json']}}
    write_csv(output/'budget-1d.csv', ['id', 't', 'x', 'z', 'H0', 'Hstrong', 'Cold',
        'eta09Allowed', 'eta1Allowed', 'eta09LossY', 'eta1LossY', 'eta1WitnessId'],
        ([r[k] for k in ['id', 't', 'x', 'z', 'H0', 'Hstrong']]+[c[i], d09[i], d1[i],
         (c[i]-d09[i])*354.66, (c[i]-d1[i])*354.66, rows[w1[i]]['id']] for i, r in enumerate(rows)))
    write_csv(output/'shared-finite-intervals.csv', ['sharedId', 'role', 'route', 'status', 'arcStartWu', 'arcEndWu'],
        ([e['id'], role, e[role], status, a, b] for e in entries for role in ['source', 'target']
         for key, status in [('sampledNormalIntervalsOriginalArcWu', 'finite-measured-normal'),
                             ('notSupportedIntervalsOriginalArcWu', 'not-supported')]
         for a, b in e['arms'][role][key]))
    result['inputHashes'].update({str(p.relative_to(ROOT)): L0.digest(p) for p in
        [T0.L0_INPUT/'actual/samples.csv', T0.L0_INPUT/'actual/checks.json',
         PREVIOUS/'evidence.json', SCRIPT.with_name('old-route-terminal-t0.py'),
         ROOT/'src/diagnostics/java/com/geogenesis/worldgen/terrain/morph/OldRouteL0Probe.java']})
    result['externalHashBase'] = str(ROOT)
    L0.save(output/'evidence.json', result)
    write_csv(output/'NW-pointwise-conflicts.csv', ['sharedId', 'otherRoute', 'NWRole', 'normalArcStart',
        'normalArcEnd', 'witnessId', 't', 'x', 'z', 'protectedY', 'eta1AllowedY', 'conflictY', 'otherGapWu'],
        ([v['sharedId'], v['otherRoute'], v['NWRole']]+v['NWNormalOriginalArcWu']+
         [v['witnessPoint'][k] for k in ['id', 't', 'x', 'z']]+[v['protectedOldDepthY'],
          v['eta1MaxAllowedDepthY'], v['eta1MaxNecessaryLossY'], v['otherProjection']['distanceWu']]
         for v in conflicts))
    summary = {k: result[k] for k in ['scope', 'eta09DownhillMarginValid', 'conditionalEta1Contradiction']}
    summary['metrics'] = {k: {n: v for n, v in result[k].items() if n != 'intervals'} for k in ['eta09', 'eta1']}
    summary['strongestConditionalConflict'] = max(conflicts, key=lambda v: v['eta1MaxNecessaryLossY'], default=None)
    (output/'outcome.txt').write_text(json.dumps(summary, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    verified_inputs(output)
    print(json.dumps(summary, ensure_ascii=False, indent=2))


def render(output):
    evidence = json.loads((output/'evidence.json').read_text('utf-8'))
    verified_inputs(output)
    for name, sha in evidence['inputHashes'].items():
        path = output/name if (output/name).is_file() else ROOT/name
        if L0.digest(path) != sha:
            raise ValueError('Analyzed input changed '+name)
    meta = json.loads((output/'prepare-interface.json').read_text('utf-8'))
    groups, _ = load_samples(output, meta)
    figures = output/'figures'
    figures.mkdir(exist_ok=False)
    plt, cmap, norm, palette = L0.plot_setup()
    rows = groups['NW-full']['full-source']
    t, b, c = [L0.column(rows, k) for k in ['t', 'H0', 'Dcombined']]
    d09, _ = envelope(b, c, .9)
    d1, _ = envelope(b, c, 1.)
    fig, axes = plt.subplots(3, 1, figsize=(16, 12), sharex=True)
    for h, label, color in [(b, 'actual H0', 'gray'), (b-c, 'actual OLD strong', 'black'),
                             (b-d09, 'eta .9 1D upper-bound ONLY', 'purple'),
                             (b-d1, 'eta 1 necessary bound ONLY', 'orange')]:
        axes[0].plot(t, h*354.66, label=label, color=color)
    axes[1].plot(t, c*354.66, color='black', label='old combined cut')
    for d, label, color in [(d09, '.9', 'purple'), (d1, '1', 'orange')]:
        axes[1].plot(t, d*354.66, color=color, label='eta '+label+' maximum retained')
        axes[2].plot(t, (c-d)*354.66, color=color, label='eta '+label+' necessary loss')
    for v in evidence['NWPointwiseProtectionTests']:
        if v['eta1ConflictingPointCount']:
            a, z = v['NWNormalRelativeEndWu']
            axes[2].axvspan(a, z, color='cyan', alpha=.035)
    for ax in axes:
        ax.grid(alpha=.2); ax.legend(fontsize=8); ax.set_ylabel('Yproxy = 354.66 H')
        ax.set_xlim(t[0], 0)
    axes[-1].set_xlabel('original arc from fixed endpoint (wu); cyan: finite normal NW protection tests')
    L0.finish_plot(plt, fig, figures/'full-profiles-budget.png',
        'NW full original route | 1D NECESSARY BOUNDS ONLY - NOT CANDIDATE TERRAIN')
    grid = groups['NW-local']['grid']
    shape = meta['localGrid']['shapeZX']
    h0 = L0.column(grid, 'H0').reshape(shape)
    hs = L0.column(grid, 'Hstrong').reshape(shape)
    x0, z0 = meta['localGrid']['originWu']
    x1, z1 = meta['localGrid']['lastWorldWu']
    fig, axes = plt.subplots(1, 2, figsize=(16, 11))
    p = np.c_[L0.column(rows, 'x'), L0.column(rows, 'z')]
    lookup = {r['id']: r for r in L0.load_routes()}
    for ax, h, title in zip(axes, [h0, hs], ['actual H0', 'actual OLD strong']):
        ax.imshow(L0.terrain_rgb(h, .25, cmap, norm), extent=[x0-.125,x1+.125,z1+.125,z0-.125])
        ax.plot(p[:, 0], p[:, 1], color='yellow', lw=1.1, label='unchanged NW full source')
        active = c-d1 > 1e-12
        ax.scatter(p[active][::32,0],p[active][::32,1],s=3,color='magenta',label='eta1 necessary reduction')
        for v in evidence['NWPointwiseProtectionTests']:
            a, z = v['NWNormalRelativeEndWu']
            good = (t>=a)&(t<=z)
            ax.plot(p[good,0], p[good,1], color='cyan', alpha=.15, lw=2)
        for routeid, label, color in [(meta['L0ProtectedControls'][0]['source'], 'L0 NW-shared source', '#00cc88')]:
            route = lookup[routeid]; arc = L0.stations(route['s'][-1]-20,route['s'][-1],.125)
            q = L0.at(route,arc)[0]; ax.plot(q[:,0],q[:,1],color=color,label=label)
        ax.scatter(p[[0,-1],0],p[[0,-1],1],c=['orange','red'],s=35,marker='x')
        ax.set(title=title,xlim=(x0,x1),ylim=(z1,z0),xlabel='world x (wu)',ylabel='world z (wu)')
        ax.legend(fontsize=8)
    L0.finish_plot(plt,fig,figures/'local-actual-H0-old-strong.png',
        'Actual terrain only | yellow old NW; cyan finite normal NW arms; magenta 1D loss - NO candidate')
    candidates = sorted(evidence['NWPointwiseProtectionTests'],key=lambda v:v['eta1MaxNecessaryLossY'],reverse=True)
    unique = []
    for v in candidates:
        if v['NWNormalOriginalArcWu'] not in [q['NWNormalOriginalArcWu'] for q in unique]:
            unique.append(v)
    fig, axes = plt.subplots(3, 1, figsize=(16, 12))
    for v in unique[:8]:
        a,z=v['NWNormalRelativeEndWu']; mask=(t>=a)&(t<=z)
        axes[0].plot(t[mask],(b-c)[mask]*354.66,label=v['sharedId']+' '+v['otherRoute'].split(':strong-window:')[-1])
        axes[1].plot(t[mask],(c-d1)[mask]*354.66)
    for profile in evidence['profiles']:
        if profile['route']==SOURCE:
            for cross in profile['crosses']:
                k=profile['crosses'].index(cross)
                r=groups[profile['group']][f'cross-{k:03d}']
                h=L0.column(r,'Hstrong')
                axes[2].plot(L0.column(r,'t'),(h-h[len(h)//2])*354.66,alpha=.25,lw=.6)
    axes[0].set_title('Finite actual normal NW subintervals; relation IDs label other nearby route, not equality')
    axes[1].set_title('eta1 required loss at SAME NW points: positive = conditional protection conflict')
    axes[2].set_title('Actual transverse sections on shared NW profile; center-relative, NOT candidate terrain')
    for ax in axes:
        ax.grid(alpha=.2);ax.set_ylabel('Yproxy');ax.set_xlabel('NW signed original arc / transverse offset (wu)')
    axes[0].legend(fontsize=7)
    L0.finish_plot(plt,fig,figures/'shared-NW-normal-and-conflict.png',
        'Finite evidence supports normal subsegments; no defined 2D protection band, no global impossibility claim')
    paths=[output/'evidence.json',output/'outcome.txt',output/'budget-1d.csv',
           output/'shared-finite-intervals.csv',output/'NW-pointwise-conflicts.csv']+sorted(figures.glob('*.png'))
    L0.save(output/'manifest.json',{'scope':evidence['scope'],'sourceSnapshot':verified_inputs(output),
        'scriptSha256':L0.digest(SCRIPT),'paletteSha256':L0.digest(palette),
        'inputHashes':evidence['inputHashes'],'outputHashes':{str(p.relative_to(output)):L0.digest(p) for p in paths},
        'syntheticChecks':evidence['syntheticChecks'],'noCandidateTerrain':True,'noProductionChanges':True})
    print('Rendered:',figures)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['prepare', 'analyze', 'render'])
    parser.add_argument('--output', type=Path, default=OUTPUT)
    args = parser.parse_args()
    {'prepare': prepare, 'analyze': analyze, 'render': render}[args.command](args.output.resolve())


if __name__ == '__main__':
    main()
