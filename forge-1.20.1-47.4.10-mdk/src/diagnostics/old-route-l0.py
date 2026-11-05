"""Read-only old-route L0 preparation. No height synthesis or route editing.

prepare freezes requests; analyze/render consume actual Java samples only.
All x,z are world wu; t semantics are recorded in cases.json.
"""
import argparse
import heapq
from itertools import zip_longest
import csv
import hashlib
import json
from pathlib import Path

import numpy as np
from scipy.spatial import cKDTree

ROOT = Path(__file__).resolve().parents[2]
REFERENCE = ROOT / 'build/strong-window-erosion-20261104-final'
OUTPUT = ROOT / 'build/old-route-l0-20261104'
HEADER = ['id', 'group', 'kind', 't', 'x', 'z', 'source', 'target']
SAMPLES_HEADER = HEADER + ['H0', 'Dsource', 'Dtarget', 'Dcombined',
                           'Hstrong', 'winner', 'contributors']


def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


def save(path, value):
    with path.open('x', encoding='utf-8') as stream:
        json.dump(value, stream, indent=2, allow_nan=False)
        stream.write('\n')


def freeze():
    manifest = json.loads((REFERENCE / 'manifest.json').read_text('utf-8'))
    actual = {'manifest': {'manifest.json': digest(REFERENCE / 'manifest.json')}}
    comparisons = {}
    for key, base in [('outputHashes', REFERENCE), ('sourceHashes', ROOT),
                      ('lockedInputs', ROOT)]:
        actual[key] = {}
        comparisons[key] = []
        for name, expected in manifest[key].items():
            path = base / name
            found = digest(path) if path.is_file() else None
            actual[key][name] = found
            if found != expected:
                comparisons[key].append({'path': name, 'expected': expected,
                                         'actual': found})
    actual['mainCurrent'] = {p.relative_to(ROOT).as_posix(): digest(p)
                             for p in sorted((ROOT / 'src/main').rglob('*'))
                             if p.is_file()}
    result = {'reference': str(REFERENCE), 'sha256': actual,
              'historicalMismatches': comparisons,
              'scope': 'L0 only; current main snapshot is not a modification',
              'scriptSha256': digest(Path(__file__))}
    save(OUTPUT / 'frozen-inputs.json', result)
    if comparisons['outputHashes'] or comparisons['sourceHashes']:
        raise ValueError('Strong outputs/source differ from manifest; see frozen-inputs.json')
    return result


def load_routes():
    traces = list(csv.DictReader((REFERENCE / 'data/traces.csv').open()))
    traces.sort(key=lambda r: (float(r['start_z']), float(r['start_x'])))
    routes = {r['id']: [] for r in traces}
    with (REFERENCE / 'data/routes.csv').open() as stream:
        for row in csv.DictReader(stream):
            points = routes[row['trace']]
            if int(row['vertex']) != len(points):
                raise ValueError('Nonsequential frozen route vertices')
            points.append((float(row['x']), float(row['z'])))
    if len(routes) != 8100:
        raise ValueError('Expected exactly 8100 frozen routes')
    result = []
    for row in traces:
        p = np.asarray(routes[row['id']], dtype=float)
        lengths = np.linalg.norm(np.diff(p, axis=0), axis=1)
        if np.any(lengths <= 0):
            raise ValueError('Degenerate segment in frozen route')
        result.append({'id': row['id'], 'status': row['status'], 'p': p,
                       's': np.r_[0., np.cumsum(lengths)]})
    return result


def at(route, s):
    s = np.atleast_1d(s)
    idx = np.clip(np.searchsorted(route['s'], s, side='right') - 1,
                  0, len(route['p']) - 2)
    d = route['p'][idx + 1] - route['p'][idx]
    length = route['s'][idx + 1] - route['s'][idx]
    return (route['p'][idx] + d * ((s - route['s'][idx]) / length)[:, None],
            d / length[:, None])


def project(points, route):
    a, d = route['p'][:-1], np.diff(route['p'], axis=0)
    l2 = np.sum(d * d, axis=1)
    u = np.clip(np.sum((points[:, None, :] - a) * d, axis=2) / l2, 0, 1)
    q = a + u[:, :, None] * d
    dist2 = np.sum((points[:, None, :] - q) ** 2, axis=2)
    idx = np.argmin(dist2, axis=1)
    rows = np.arange(len(points))
    length = np.sqrt(l2[idx])
    return (np.sqrt(dist2[rows, idx]), route['s'][idx] + u[rows, idx] * length,
            q[rows, idx], d[idx] / length[:, None])


def pair_evidence(source, target):
    length = float(source['s'][-1])
    offsets = np.linspace(-20, 0, 41) if length >= 20 else np.array([0.])
    p, tangent = at(source, length + offsets)
    gap, arc, projected, direction = project(p, target)
    cosine = np.sum(tangent * direction, axis=1)
    interior = min(float(arc[-1]), float(target['s'][-1] - arc[-1]))
    passed = bool(length >= 20 and np.max(gap) <= 1 and
                  np.min(cosine) >= .8 and np.all(np.diff(arc) > 0) and
                  interior >= 2)
    return {'shared': passed, 'endGapWu': float(gap[-1]),
            'maxGapWu': float(np.max(gap)), 'minCos': float(np.min(cosine)),
            'endTargetArcWu': float(arc[-1]), 'endInteriorWu': interior,
            'projectionWorldWu': projected[-1].tolist(),
            'offsetWu': offsets.tolist(), 'gapWu': gap.tolist(),
            'targetArcWu': arc.tolist(), 'cos': cosine.tolist()}


def quadrant(point, sampling):
    origin = np.array(sampling['originWu'])
    span = sampling['stepWu'] * (sampling['size'] - 1)
    xy = (point - origin) / span
    if np.any(xy < 0) or np.any(xy > 1):
        return None
    return ('N' if xy[1] < .5 else 'S') + ('W' if xy[0] < .5 else 'E')


def make_case(q, category, source, target=None, evidence=None):
    return {'group': q + '-' + category, 'quadrant': q, 'category': category,
            'source': source['id'], 'target': target['id'] if target else '',
            'sourceStatus': source['status'],
            'sourceLengthWu': float(source['s'][-1]),
            'centerWorldWu': source['p'][-1].tolist(), 'geometry': evidence,
            'classification': 'unknown; geometry only, awaiting actual heights'}


def select_cases(routes, sampling):
    points = np.concatenate([r['p'] for r in routes])
    owners = np.concatenate([np.full(len(r['p']), i) for i, r in enumerate(routes)])
    tree = cKDTree(points)
    selected = {}
    for i, source in enumerate(routes):
        q = quadrant(source['p'][-1], sampling)
        if q is None:
            continue
        key = (q, 'terminal')
        if key not in selected and source['status'] == 'NO_DESCENT_AT_RESOLUTION':
            selected[key] = make_case(q, 'terminal', source)
        if all((q, k) in selected for k in ['shared', 'near']):
            continue
        _, nearest = tree.query(source['p'][-1], k=16)
        candidates = sorted(set(int(owners[n]) for n in nearest) - {i})
        pairs = [(j, pair_evidence(source, routes[j])) for j in candidates]
        shared = [(j, e) for j, e in pairs if e['shared']]
        if shared and (q, 'shared') not in selected:
            j, evidence = shared[0]
            selected[q, 'shared'] = make_case(q, 'shared', source, routes[j], evidence)
        near = [(j, e) for j, e in pairs if e['endGapWu'] <= 1 and not e['shared']]
        if not shared and near and (q, 'near') not in selected:
            j, evidence = near[0]
            selected[q, 'near'] = make_case(q, 'near', source, routes[j], evidence)
        if len(selected) == 12:
            break
    return [selected[q, k] for q in ['NW', 'NE', 'SW', 'SE']
            for k in ['shared', 'near', 'terminal'] if (q, k) in selected]


def stations(start, end, step):
    values = start + np.arange(int(np.floor((end - start) / step)) + 1) * step
    return np.r_[values, end] if end - values[-1] > 1e-10 else values


def case_requests(case, lookup):
    center = np.array(case['centerWorldWu'])
    source = lookup[case['source']]
    n = np.arange(129) * .25 - 16
    xx, zz = np.meshgrid(n + center[0], n + center[1])
    yield 'grid', np.arange(129 * 129), np.c_[xx.ravel(), zz.ravel()]
    length = source['s'][-1]
    offsets = stations(-min(32, length), 0, .03125)
    yield 'long-source', offsets, at(source, length + offsets)[0]
    if case['target']:
        target = lookup[case['target']]
        arc = case['geometry']['endTargetArcWu']
        suffix = stations(0, min(12, target['s'][-1] - arc), .03125)
        yield 'long-target', suffix, at(target, arc + suffix)[0]
    for i, back in enumerate([8, 4, 0]):
        p, tangent = at(source, max(0, length - back))
        normal = np.array([-tangent[0, 1], tangent[0, 0]])
        offsets = stations(-12, 12, .03125)
        yield f'cross-{i}', offsets, p + offsets[:, None] * normal


def write_requests(cases, routes):
    lookup = {r['id']: r for r in routes}
    count = 0
    with (OUTPUT / 'requests.csv').open('x', newline='', encoding='utf-8') as stream:
        writer = csv.writer(stream)
        writer.writerow(HEADER)
        for case in cases:
            case['requestCounts'] = {}
            for kind, ts, points in case_requests(case, lookup):
                case['requestCounts'][kind] = len(ts)
                for t, (x, z) in zip(ts, points):
                    rid = f"{case['group']}-{sum(case['requestCounts'].values()) - len(ts):06d}-{kind}-{count:06d}"
                    writer.writerow([rid, case['group'], kind, format(float(t), '.17g'),
                                     format(x, '.17g'), format(z, '.17g'),
                                     case['source'], case['target']])
                    count += 1
    return count


def prepare():
    OUTPUT.mkdir(parents=True, exist_ok=False)
    frozen = freeze()
    sampling = json.loads((REFERENCE / 'data/sampling.json').read_text('utf-8'))
    # Read and validate the frozen full H0/strong arrays; never interpolate requests.
    for name in ['carrier-height.f64be', 'strong-height.f64be']:
        h = np.fromfile(REFERENCE / 'data' / name, dtype='>f8')
        if h.size != sampling['size'] ** 2 or not np.isfinite(h).all():
            raise ValueError('Invalid frozen height raster: ' + name)
    routes = load_routes()
    cases = select_cases(routes, sampling)
    count = write_requests(cases, routes)
    record = {'scope': 'L0 H0/old strong only; no repair, new skeleton or production',
              'sampling': sampling, 'cases': cases, 'requestCount': count,
              'selection': {'order': 'source seed z,x row-major; target same order',
                            'quadrants': 'endpoint in original window, split at midpoint',
                            'candidates': 'endpoint k16 original vertices; NOT complete',
                            'projection': 'exact closest segment, not closest vertex',
                            'shared': 'last20 @.5, gap<=1 cos>=.8 strict arc advance; end >=2 from both target ends',
                            'near': 'first source with no shared k16 receiver, first endgap<=1 target'},
              'interface': {'requests': HEADER, 'samples': SAMPLES_HEADER,
                            'coords': 'world wu, HS=2; no raster interpolation as sampler',
                            'grid': '129x129 +-16wu @.25; t=row*129+col, row increases z',
                            'long-source': 'original final min(32,length) wu; t relative to endpoint',
                            'long-target': 'original target projection + min(12,remaining); t from projection',
                            'join': 'source endpoint to target projection is measurement only, NOT original path; never bridge in path statistics',
                            'cross-0/1/2': '8/4/0wu before source end; left normal(-tz,tx), t signed wu',
                            'emptyTarget': 'terminal: target empty, Dtarget=0',
                            'contributions': 'old Cut combination at arbitrary world point, full trace IDs'},
              'renderContract': {'e': '.02+1.5H', 'range': [-.35, 1.2],
                                 'palette': 'project palette', 'HS': 2, 'Yproxy': '354.66H',
                                 'shade': '.65+.35*max(0,dot(normal,normalized(light)))',
                                 'light': [-.5, -.3, 1]},
              'analysisGuardrails': [
                  'source fade0 is not a gap; attribute Dcombined winner/contributors',
                  'source Hstrong uphill alone is NOT a junction sill',
                  'compare 2D minimax to candidate receiver low trough against H0, both 4/8 neighbors',
                  'window boundary is NOT an outlet',
                  'double trough: actual curve local minima and intervening ridge only, not max operator',
                  'unknown allowed; no forced classification'],
              'mainHistoricalMismatchCount': len(frozen['historicalMismatches']['lockedInputs']),
              'requestsSha256': digest(OUTPUT / 'requests.csv')}
    save(OUTPUT / 'cases.json', record)
    print(json.dumps({'output': str(OUTPUT), 'requests': count, 'cases': [
        {k: c[k] for k in ['group', 'source', 'target']} for c in cases]}, indent=2))


def load_actual():
    meta = json.loads((OUTPUT / 'cases.json').read_text('utf-8'))
    checks = json.loads((OUTPUT / 'actual/checks.json').read_text('utf-8'))
    if not checks.get('passed'):
        raise ValueError('Sampler checks did not pass')
    if digest(OUTPUT / 'requests.csv') != meta['requestsSha256']:
        raise ValueError('Requests hash changed')
    groups, count, error = {}, 0, 0.
    with (OUTPUT / 'requests.csv').open() as rf, (OUTPUT / 'actual/samples.csv').open() as sf:
        requests, samples = csv.DictReader(rf), csv.DictReader(sf)
        if samples.fieldnames != SAMPLES_HEADER:
            raise ValueError('Unexpected samples header')
        for request, row in zip_longest(requests, samples):
            if request is None or row is None:
                raise ValueError('Request/sample length mismatch')
            for key in HEADER:
                same = (float(request[key]) == float(row[key]) if key in ['t', 'x', 'z']
                        else request[key] == row[key])
                if not same:
                    raise ValueError('Request echo mismatch: ' + key)
            for key in ['t', 'x', 'z'] + SAMPLES_HEADER[8:13]:
                row[key] = float(row[key])
                if not np.isfinite(row[key]):
                    raise ValueError('Nonfinite sample')
            d = row['Dcombined']
            if not (0 <= row['Dsource'] <= d + 1e-14 and
                    0 <= row['Dtarget'] <= d + 1e-14 and d <= .08 + 1e-14):
                raise ValueError('Cut envelope violation')
            error = max(error, abs(row['H0'] - d - row['Hstrong']))
            groups.setdefault(row['group'], {}).setdefault(row['kind'], []).append(row)
            count += 1
    if count != meta['requestCount'] or error > 1e-14:
        raise ValueError('Count/height identity mismatch')
    for case in meta['cases']:
        for kind, n in case['requestCounts'].items():
            if len(groups[case['group']][kind]) != n:
                raise ValueError('Case sample count mismatch')
    return meta, groups, {'rows': count, 'maxHeightIdentityErrorH': error,
                          'samplerChecks': checks}


def column(rows, name):
    return np.array([r[name] for r in rows])


def minimax(h, start, target, neighbors):
    n = h.shape[0]
    costs = np.full(h.shape, np.inf)
    previous = {}
    costs[start] = h[start]
    queue = [(float(h[start]), start)]
    moves = [(0, 1), (0, -1), (1, 0), (-1, 0)]
    if neighbors == 8:
        moves += [(1, 1), (1, -1), (-1, 1), (-1, -1)]
    while queue:
        cost, node = heapq.heappop(queue)
        if cost != costs[node]:
            continue
        if node == target:
            break
        for dr, dc in moves:
            nxt = node[0] + dr, node[1] + dc
            if not (0 <= nxt[0] < n and 0 <= nxt[1] < n):
                continue
            value = max(cost, float(h[nxt]))
            if value < costs[nxt]:
                costs[nxt] = value
                previous[nxt] = node
                heapq.heappush(queue, (value, nxt))
    path, node = [target], target
    while node != start:
        node = previous[node]
        path.append(node)
    spill = float(costs[target])
    return {'spillH': spill, 'barrierAboveStartY': (spill - float(h[start])) * 354.66,
            'barrierAboveBothY': max(0., spill - max(float(h[start]), float(h[target]))) * 354.66,
            'startH': float(h[start]), 'targetH': float(h[target]),
            'pathTouchesBoundary': any(0 in p or n - 1 in p for p in path),
            'pathRowCol': [list(p) for p in path[::-1]]}


def low_node(grid, anchor, height):
    xy = np.c_[column(grid, 'x'), column(grid, 'z')]
    distance = np.linalg.norm(xy - np.asarray(anchor), axis=1)
    valid = np.flatnonzero(distance <= 2.)
    distances = distance.reshape(129, 129)
    if (not len(valid) or np.any(distances[[0, -1], :] <= 2.)
            or np.any(distances[:, [0, -1]] <= 2.)):
        return None
    i = min(valid, key=lambda k: (height[k], distance[k], k))
    row, col = divmod(int(i), 129)
    if row in [0, 128] or col in [0, 128]:
        return None
    return row, col


def connection_evidence(case, data):
    if not case['target']:
        return {'status': 'unknown: terminal has no candidate receiver; no outlet inferred'}
    grid = data['grid']
    source_anchor = data['long-source'][-1]
    target_rows = data['long-target']
    target_anchor = min(target_rows, key=lambda r: abs(r['t'] - min(4., target_rows[-1]['t'])))
    anchors = [[r['x'], r['z']] for r in [source_anchor, target_anchor]]
    result = {'anchorsWorldWu': anchors, 'selectedBySurface': {}}
    for selection in ['H0', 'Hstrong']:
        h = column(grid, selection)
        start, target = [low_node(grid, p, h) for p in anchors]
        if start is None or target is None:
            result['selectedBySurface'][selection] = {'status': 'unknown: insufficient local grid'}
            continue
        entry = {'startRowCol': list(start), 'targetRowCol': list(target),
                 'sameNode': start == target, 'surfaces': {}}
        for surface in ['H0', 'Hstrong']:
            a = column(grid, surface).reshape(129, 129)
            entry['surfaces'][surface] = {str(k): minimax(a, start, target, k) for k in [4, 8]}
        result['selectedBySurface'][selection] = entry
    result['status'] = 'local sampled connectivity only; not a proven junction sill'
    return result


def cross_evidence(rows):
    from scipy.signal import find_peaks
    result = {}
    t = column(rows, 't')
    for field in ['H0', 'Hstrong']:
        y = column(rows, field) * 354.66
        minima = find_peaks(-y)[0]
        pairs = []
        for left, right in zip(minima[:-1], minima[1:]):
            ridge = int(left + np.argmax(y[left:right + 1]))
            pairs.append({'leftT': float(t[left]), 'rightT': float(t[right]),
                          'ridgeT': float(t[ridge]), 'ridgeY': float(y[ridge]),
                          'aboveLeftY': float(y[ridge] - y[left]),
                          'aboveRightY': float(y[ridge] - y[right])})
        result[field] = {'localMinima': [{'t': float(t[i]), 'Y': float(y[i])} for i in minima],
                         'adjacentMinimaRidges': pairs,
                         'note': 'raw sampled curve extrema, no prominence threshold; not automatically a double-slot defect'}
    return result


def attribution(rows):
    counts = {'source': 0, 'target': 0, 'others': 0, 'none': 0}
    winners = {}
    for r in rows:
        winner = r['winner']
        category = ('none' if not winner else 'source' if winner == r['source']
                    else 'target' if winner == r['target'] else 'others')
        counts[category] += 1
        winners[winner] = winners.get(winner, 0) + 1
    return {'winnerCounts': counts, 'winnerTraceCounts': winners,
            'contributorCountRange': [min(int(r['contributors']) for r in rows),
                                      max(int(r['contributors']) for r in rows)],
            'othersDepth': 'Dcombined only where winner is others; not independently known where source/target wins'}


def analyze():
    if (OUTPUT / 'evidence.json').exists() or (OUTPUT / 'outcome.txt').exists():
        raise FileExistsError('Refusing to overwrite analysis')
    meta, groups, validation = load_actual()
    entries = []
    for case in meta['cases']:
        data = groups[case['group']]
        longitudinal = data['long-source']
        y = column(longitudinal, 'Hstrong') * 354.66
        end = longitudinal[-1]
        entries.append({'group': case['group'], 'source': case['source'], 'target': case['target'],
                        'classification': 'unknown: L0 observations, no repair decision',
                        'sourceUphill': {'positiveStepCount': int(np.sum(np.diff(y) > 1e-9)),
                                         'maxStepRiseY': float(max(0., np.diff(y).max())),
                                         'maxRiseFromPreviousLowY': float(np.max(y - np.minimum.accumulate(y))),
                                         'interpretation': 'uphill evidence only, NOT a junction sill'},
                        'endpoint': {k: end[k] for k in SAMPLES_HEADER[8:]},
                        'attribution': {kind: attribution(rows) for kind, rows in data.items()},
                        'connection': connection_evidence(case, data),
                        'crossSections': {k: cross_evidence(data[k]) for k in ['cross-0', 'cross-1', 'cross-2']}})
    report = {'scope': 'read-only L0; all routes retained; not Minecraft', 'validation': validation,
              'hashes': {'samples': digest(OUTPUT / 'actual/samples.csv'),
                         'checks': digest(OUTPUT / 'actual/checks.json'), 'cases': digest(OUTPUT / 'cases.json')},
              'minimaxRule': 'source anchor=source endpoint; receiver anchor=target original suffix at min(4,remaining)wu. For EACH H0/strong independently choose minimum sampled node within radius2wu of each anchor (tie: distance,rowmajor). Compare BOTH fields using the SAME selected nodes, report both selection arms. Local minima are trough candidates, not verified valley beds. No boundary node is an outlet; paths confined to 129x129 grid. barrierAboveBoth excludes mere endpoint height difference; barrierAboveStart also includes uphill endpoint. 4/8 retained; no unseen exit inferred.',
              'limitations': ['.25wu grid only; no shifted/finer validation or global drainage',
                              'k16 candidate search is incomplete',
                              'contributors is a count, not IDs; others maximum unknown where it loses',
                              'long-source and long-target are separate original paths; no artificial bridge',
                              'NO_DESCENT is not proof of natural termination; all final classifications may remain unknown'],
              'cases': entries}
    save(OUTPUT / 'evidence.json', report)
    lines = ['L0 ONLY: 12 fixed cases; all old routes retained; no repair.',
             f"Validated {validation['rows']} actual rows; request echoes and H0-Dcombined=Hstrong checked.",
             'Sampler checks are spot bit/Cut/attribution checks, NOT exhaustive bit tests on 241021 points.',
             'Uphill is not a sill; source fade0 is not a gap; window boundary is not an outlet.',
             'Minimax selection and 4/8-neighbor paths are recorded in evidence.json.']
    for e in entries:
        end = e['endpoint']
        lines.append(f"{e['group']}: endpoint Dcombined={end['Dcombined']*354.66:.6f}Y; winner={end['winner']}; uphill={e['sourceUphill']['maxRiseFromPreviousLowY']:.6f}Y; classification=unknown")
    lines += connection_summary(entries)
    with (OUTPUT / 'outcome.txt').open('x', encoding='utf-8') as stream:
        stream.write('\n'.join(lines) + '\n')
    print('\n'.join(lines))


def connection_summary(entries):
    lines = ['Minimax: SAME selected endpoints across H0/strong; barriers above BOTH endpoints in Yproxy.',
             'Same-node selections are DEGENERATE and do not establish a trough connection.',
             'All endpoints have Dsource=0; combined winner, not own fade, determines coverage.']
    for entry in entries:
        for selection, arm in entry['connection'].get('selectedBySurface', {}).items():
            if 'surfaces' not in arm:
                lines.append(entry['group']+' '+selection+': insufficient grid; unknown')
                continue
            values = {surface: {n: a['barrierAboveBothY'] for n, a in neighbors.items()}
                      for surface, neighbors in arm['surfaces'].items()}
            lines.append(f"{entry['group']} selectedBy={selection} sameNode={arm['sameNode']} barriers={values}")
    lines += ['Terminal cases have no receiver: unknown, not a natural outlet.',
              'Cross extrema are raw actual minima and intervening ridges; no automatic double-slot classification.',
              'Contributors column is a count; losing others maximum and individual contributor IDs are unavailable.']
    return lines


def plot_setup():
    import matplotlib
    matplotlib.use('Agg')
    import matplotlib.pyplot as plt
    from matplotlib.colors import ListedColormap, Normalize
    palette = ROOT / 'build/fast-erosion-scale-only-20261103-final/data/project-palette.csv'
    colors = np.loadtxt(palette, delimiter=',', skiprows=1)
    cmap = ListedColormap(colors[:, 1:] / 255.)
    return plt, cmap, Normalize(-.35, 1.2, clip=True), palette


def terrain_rgb(h, step, cmap, norm):
    gz, gx = np.gradient(h * 354.66, step * 2)
    light = np.array([-.5, -.3, 1.])
    light /= np.linalg.norm(light)
    dot = (-gx * light[0] - gz * light[1] + light[2]) / np.sqrt(1 + gx*gx + gz*gz)
    return cmap(norm(.02 + 1.5*h))[..., :3] * (.65 + .35*np.maximum(dot, 0))[..., None]


def finish_plot(plt, fig, path, title):
    fig.suptitle(title, fontsize=13)
    fig.text(.01, .008, 'L0 only | H0 / OLD strong | fixed palette e=.02+1.5H [-.35,1.2] | HS=2 Y=354.66H | no route edits', fontsize=8)
    fig.tight_layout(rect=(0, .025, 1, .95))
    fig.savefig(path, dpi=150)
    plt.close(fig)


def map_panel(ax, grid, field, cmap, norm):
    h = column(grid, field).reshape(129, 129)
    x, z = grid[0]['x'], grid[0]['z']
    extent = [x-.125, x+32.125, z+32.125, z-.125]
    ax.imshow(terrain_rgb(h, .25, cmap, norm), extent=extent, interpolation='nearest')
    ax.set(xlabel='world x (wu)', ylabel='world z (wu)', title=field)


def overlay_routes(ax, data):
    for kind, color in [('long-source', '#ffde59'), ('long-target', '#00f5ff')]:
        if kind in data:
            rows = data[kind]
            ax.plot(column(rows, 'x'), column(rows, 'z'), color=color, lw=1, label=kind)
    end = data['long-source'][-1]
    ax.scatter([end['x']], [end['z']], c='red', s=18, marker='x')
    # Preserve the local map extent even when source tail leaves this window.
    grid = data['grid']
    ax.set_xlim(grid[0]['x']-.125, grid[-1]['x']+.125)
    ax.set_ylim(grid[-1]['z']+.125, grid[0]['z']-.125)


def render_profiles(plt, case, data, figures):
    fig, axes = plt.subplots(3, 2, figsize=(15, 12))
    for kind, style in [('long-source', '-'), ('long-target', '--')]:
        if kind not in data:
            continue
        rows = data[kind]
        t = column(rows, 't')
        for field, color in [('H0', 'gray'), ('Hstrong', 'black')]:
            axes[0, 0].plot(t, column(rows, field)*354.66, style, color=color, label=kind+' '+field)
        for field, color in [('Dsource', '#e6a000'), ('Dtarget', '#00aaba'), ('Dcombined', 'black')]:
            axes[0, 1].plot(t, column(rows, field)*354.66, style, color=color, label=kind+' '+field)
        others = [r['Dcombined']*354.66 if r['winner'] and r['winner'] not in [r['source'], r['target']] else np.nan for r in rows]
        axes[0, 1].scatter(t, others, color='#bb44bb', s=3, label=kind+' others WINNING depth')
    for i, kind in enumerate(['cross-0', 'cross-1', 'cross-2']):
        ax = axes[1+i//2, i%2]
        rows = data[kind]
        for field, color in [('H0', 'gray'), ('Hstrong', 'black')]:
            ax.plot(column(rows, 't'), column(rows, field)*354.66, color=color, label=field)
        ax.set_title(kind+' (8/4/0wu before endpoint)')
    ax = axes[2, 1]
    rows = data['long-source']
    codes = [0 if not r['winner'] else 1 if r['winner']==r['source'] else 2 if r['winner']==r['target'] else 3 for r in rows]
    ax.scatter(column(rows, 't'), codes, s=5, c=codes, cmap='tab10', vmin=0, vmax=3)
    ax.set_yticks([0, 1, 2, 3], ['none', 'source', 'target', 'others'])
    ax.set_title('Actual winner on original source; contributors count on right')
    twin = ax.twinx()
    twin.plot(column(rows, 't'), [int(r['contributors']) for r in rows], color='gray', alpha=.4)
    for ax in axes.ravel():
        ax.set_xlabel('signed offset / relative original arc (wu)')
        ax.grid(alpha=.2)
        if ax is not axes[2, 1]:
            ax.set_ylabel('Yproxy')
            ax.legend(fontsize=7)
    finish_plot(plt, fig, figures/(case['group']+'-profiles.png'), case['group']+' actual profiles; separate source/target, NO bridge')


def render_local(plt, cmap, norm, case, data, evidence, figures):
    fig, axes = plt.subplots(2, 3, figsize=(16, 10))
    for col, field in enumerate(['H0', 'Hstrong']):
        for row in [0, 1]:
            map_panel(axes[row, col], data['grid'], field, cmap, norm)
            if row:
                overlay_routes(axes[row, col], data)
        selection = evidence['connection'].get('selectedBySurface', {}).get('Hstrong', {})
        for neighbors, color in [('4', '#ff66cc'), ('8', '#66ff66')]:
            arm = selection.get('surfaces', {}).get(field, {}).get(neighbors)
            if arm:
                path = np.array(arm['pathRowCol'])
                grid = data['grid']
                axes[1, col].plot(grid[0]['x']+path[:,1]*.25, grid[0]['z']+path[:,0]*.25,
                                  color=color, lw=.8, label='minimax '+neighbors)
    grid = data['grid']
    extent = [grid[0]['x']-.125, grid[-1]['x']+.125, grid[-1]['z']+.125, grid[0]['z']-.125]
    axes[0, 2].imshow(column(grid, 'Dcombined').reshape(129,129)*354.66,
                       extent=extent, cmap='magma', vmin=0, vmax=.08*354.66)
    axes[0, 2].set_title('Actual combined cut (Yproxy), fixed 0..28.37')
    codes = [0 if not r['winner'] else 1 if r['winner']==r['source'] else 2 if r['winner']==r['target'] else 3 for r in grid]
    from matplotlib.colors import ListedColormap
    axes[1, 2].imshow(np.array(codes).reshape(129,129), extent=extent,
                       cmap=ListedColormap(['white','#e6a000','#00aaba','#bb44bb']), vmin=0, vmax=3)
    axes[1, 2].set_title('Winner: white none / gold source / cyan target / purple others')
    for ax in axes[1, :2]:
        ax.legend(fontsize=7)
    finish_plot(plt, fig, figures/(case['group']+'-closeup.png'), case['group']+' | unmarked terrain top, measured paths below | unknown, not a diagnosed sill')


def render_overview(plt, cmap, norm, meta, figures):
    sampling = meta['sampling']
    n, step = sampling['size'], sampling['stepWu']
    x, z = sampling['originWu']
    span = (n-1)*step
    fig, axes = plt.subplots(1, 2, figsize=(17, 9))
    for ax, name, title in zip(axes, ['carrier-height', 'strong-height'], ['H0 frozen raster', 'OLD strong frozen raster']):
        h = np.fromfile(REFERENCE/('data/'+name+'.f64be'), dtype='>f8').reshape(n,n)
        ax.imshow(terrain_rgb(h, step, cmap, norm), extent=[x-step/2,x+span+step/2,z+span+step/2,z-step/2])
        for i, case in enumerate(meta['cases']):
            cx, cz = case['centerWorldWu']
            ax.scatter([cx],[cz],s=24,facecolors='none',edgecolors='red')
            ax.annotate(str(i+1)+' '+case['group'], (cx,cz), xytext=(5, 8+(i%3)*13),
                         textcoords='offset points', fontsize=6, arrowprops={'arrowstyle':'-', 'lw':.4})
        ax.set(title=title, xlabel='world x wu', ylabel='world z wu')
    finish_plot(plt, fig, figures/'full-window-locations.png', '12 frozen L0 cases | raster is location context, profiles use actual continuous samples')


def render_summary(plt, cmap, norm, meta, groups, entries, figures):
    fig, axes = plt.subplots(4, 6, figsize=(20, 13))
    for index, case in enumerate(meta['cases']):
        row, pair = divmod(index, 3)
        data = groups[case['group']]
        for side, field in enumerate(['H0', 'Hstrong']):
            ax = axes[row, pair*2+side]
            map_panel(ax, data['grid'], field, cmap, norm)
            end = data['long-source'][-1]
            ax.scatter([end['x']], [end['z']], color='red', marker='x', s=15)
            ax.set_title(case['group']+' '+field, fontsize=9)
            ax.set_xlabel(''); ax.set_ylabel('')
            ax.tick_params(labelsize=6)
    finish_plot(plt, fig, figures/'summary-12-cases.png', 'L0: fixed 12 cases | original H0 vs OLD strong | red X source endpoint only | no repairs; classifications unknown')


def render():
    figures = OUTPUT / 'figures'
    if figures.exists():
        raise FileExistsError('Refusing to overwrite figures')
    report = json.loads((OUTPUT / 'evidence.json').read_text('utf-8'))
    meta, groups, validation = load_actual()
    if digest(OUTPUT/'actual/samples.csv') != report['hashes']['samples']:
        raise ValueError('Samples changed after analysis')
    plt, cmap, norm, palette = plot_setup()
    figures.mkdir()
    entries = {e['group']: e for e in report['cases']}
    render_overview(plt, cmap, norm, meta, figures)
    render_summary(plt, cmap, norm, meta, groups, entries, figures)
    for case in meta['cases']:
        print('Rendering', case['group'], flush=True)
        data = groups[case['group']]
        render_local(plt, cmap, norm, case, data, entries[case['group']], figures)
        render_profiles(plt, case, data, figures)
    save(figures/'render-manifest.json', {'paletteSha256': digest(palette),
         'evidenceSha256': digest(OUTPUT/'evidence.json'), 'validation': validation,
         'images': {p.name: digest(p) for p in figures.glob('*.png')}})
    print('Figures:', figures)


def main():
    global OUTPUT
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['prepare', 'analyze', 'render'])
    parser.add_argument('--output', type=Path, default=OUTPUT,
                        help='Prepare requires fresh directory; analyze/render use prepared directory')
    args = parser.parse_args()
    OUTPUT = args.output.resolve()
    {'prepare': prepare, 'analyze': analyze, 'render': render}[args.command]()


if __name__ == '__main__':
    main()
