"""TerminalPitProbe grid-only evidence; never samples/interpolates a continuous field.

analyze(folder, *, status=None) writes basin-analysis.json (status is caller-owned).
Spill is the minimax escape to THIS WINDOW'S boundary, not a global basin claim.
Depths at identical world cells, not movement of minimum indices, drive findings.
"""
import argparse
import csv
import hashlib
import heapq
import json
import math
from pathlib import Path

import numpy as np

Y_PROXY = 354.66
HS = 2.0
E_LIMITS = (-.35, 1.2)
PALETTE = (Path(__file__).resolve().parents[2] / 'build' /
           'fast-erosion-scale-only-20261103-final/data/project-palette.csv')
REQUEST_HEADER = 'id,trace,cx,cz,half,step'.split(',')
SAMPLE_HEADER = 'id,trace,ix,iz,world_x,world_z,H0,Dold,W,Dnew,Hnew'.split(',')


def _save(path, value):
    path.write_text(json.dumps(value, indent=2, ensure_ascii=False, allow_nan=False)
                    + '\n', encoding='utf-8')


def _hash(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def _read_grids(folder):
    folder = Path(folder)
    grids = {}
    with (folder / 'requests.csv').open(encoding='utf-8-sig', newline='') as f:
        reader = csv.DictReader(f)
        if reader.fieldnames != REQUEST_HEADER:
            raise ValueError('Unexpected requests header')
        for row in reader:
            r = {k: (row[k] if k in ('id', 'trace') else float(row[k])) for k in row}
            n = round(2 * r['half'] / r['step']) if r['step'] > 0 else -1
            if (r['id'] in grids or not r['id'] or not r['trace'] or n < 0
                    or not all(math.isfinite(r[k]) for k in REQUEST_HEADER[2:])
                    or not math.isclose(n * r['step'], 2 * r['half'], abs_tol=1e-10)):
                raise ValueError('Invalid or duplicate request: ' + r['id'])
            a = {k: np.full((n + 1, n + 1), np.nan) for k in SAMPLE_HEADER[4:]}
            grids[r['id']] = dict(request=r, arrays=a, seen=np.zeros((n+1, n+1), bool))
    if not grids:
        raise ValueError('No requests')
    with (folder / 'samples.csv').open(encoding='utf-8-sig', newline='') as f:
        reader = csv.DictReader(f)
        if reader.fieldnames != SAMPLE_HEADER:
            raise ValueError('Unexpected samples header')
        for row in reader:
            g = grids[row['id']]
            x, z = int(row['ix']), int(row['iz'])
            if (row['trace'] != g['request']['trace'] or min(x, z) < 0
                    or max(x, z) >= len(g['seen']) or g['seen'][z, x]):
                raise ValueError('Duplicate/invalid grid index or trace')
            for k in g['arrays']:
                g['arrays'][k][z, x] = float(row[k])
            g['seen'][z, x] = True
    for g in grids.values():
        r, a = g['request'], g['arrays']
        if not g.pop('seen').all() or not all(np.isfinite(v).all() for v in a.values()):
            raise ValueError('Missing/nonfinite grid samples: ' + r['id'])
        zz, xx = np.indices(a['H0'].shape)
        for key, idx, center in [('world_x', xx, r['cx']), ('world_z', zz, r['cz'])]:
            expected = center + (idx * r['step'] - r['half'])
            if not np.allclose(a[key], expected, rtol=0, atol=1e-9):
                raise ValueError('World coordinates disagree with request: ' + r['id'])
        a['Hold'] = a['H0'] - a['Dold']
    return grids


def _offsets(connectivity):
    return [(z, x) for z in (-1, 0, 1) for x in (-1, 0, 1)
            if (z or x) and (connectivity == 8 or abs(z) + abs(x) == 1)]


def _neighbors(i, shape, offsets):
    z, x = divmod(int(i), shape[1])
    for dz, dx in offsets:
        zz, xx = z + dz, x + dx
        if 0 <= zz < shape[0] and 0 <= xx < shape[1]:
            yield zz * shape[1] + xx


def _boundary(shape):
    zz, xx = np.indices(shape)
    return ((zz == 0) | (xx == 0) | (zz == shape[0]-1) | (xx == shape[1]-1)).ravel()


def _flood(a, connectivity):
    """One boundary-seeded minimax Dijkstra per field; parents form grid-only paths."""
    h = a.ravel()
    level = np.full(h.size, np.inf)
    parent = np.full(h.size, -1, dtype=np.int64)
    spill = np.arange(h.size, dtype=np.int64)
    heap = []
    for i in np.flatnonzero(_boundary(a.shape)):
        level[i] = h[i]
        heapq.heappush(heap, (float(h[i]), int(i)))
    offsets = _offsets(connectivity)
    while heap:
        cost, i = heapq.heappop(heap)
        if cost != level[i]:
            continue
        for j in _neighbors(i, a.shape, offsets):
            new = max(cost, h[j])
            if new < level[j]:
                level[j], parent[j] = new, i
                spill[j] = j if h[j] > cost else spill[i]
                heapq.heappush(heap, (float(new), j))
    return dict(level=level, depth=level-h, parent=parent, spill=spill)


def _minima(a, connectivity):
    """Regional (flat-aware) minima, including boundary minima for transparency."""
    h, offsets = a.ravel(), _offsets(connectivity)
    candidate = np.ones(a.shape, bool)
    for dz, dx in offsets:
        z0, z1 = max(0, -dz), min(a.shape[0], a.shape[0]-dz)
        x0, x1 = max(0, -dx), min(a.shape[1], a.shape[1]-dx)
        candidate[z0:z1, x0:x1] &= a[z0:z1, x0:x1] <= a[z0+dz:z1+dz, x0+dx:x1+dx]
    seen, result = set(), []
    for seed in np.flatnonzero(candidate.ravel()):
        if int(seed) in seen:
            continue
        stack, plateau, lower = [int(seed)], [], False
        seen.add(int(seed))
        while stack:
            i = stack.pop()
            plateau.append(i)
            for j in _neighbors(i, a.shape, offsets):
                lower |= h[j] < h[i]
                if h[j] == h[i] and j not in seen:
                    seen.add(j)
                    stack.append(j)
        if not lower:
            result.append(dict(index=min(plateau), cells=plateau))
    return result


def _world(g, i):
    a = g['arrays']
    return [float(a['world_x'].ravel()[i]), float(a['world_z'].ravel()[i])]


def _pools(g, a, flood, original_minima, connectivity):
    """Label below-spill connected pools once, never flood the grid per minimum."""
    wet = flood['depth'] > 0
    labels = np.full(a.size, -1, dtype=np.int64)
    original = {i for m in original_minima for i in m['cells']}
    pools, offsets = [], _offsets(connectivity)
    boundary = _boundary(a.shape)
    for seed in np.flatnonzero(wet):
        if labels[seed] >= 0:
            continue
        label, stack, cells = len(pools), [int(seed)], []
        labels[seed] = label
        while stack:
            i = stack.pop()
            cells.append(i)
            for j in _neighbors(i, a.shape, offsets):
                if wet[j] and labels[j] < 0:
                    labels[j] = label
                    stack.append(j)
        xs = g['arrays']['world_x'].ravel()[cells]
        zs = g['arrays']['world_z'].ravel()[cells]
        minima = sorted(original.intersection(cells))
        rim_touches = any(boundary[j] and a.ravel()[j] <= flood['level'][i]
                          for i in cells for j in _neighbors(i, a.shape, offsets))
        pools.append(dict(cellCount=len(cells), areaWorld2=len(cells)*g['request']['step']**2,
                     worldBounds=[float(xs.min()), float(xs.max()), float(zs.min()), float(zs.max())],
                     containsH0min=bool(minima), H0minWorld=[_world(g, i) for i in minima],
                     touchesWindowBoundary=bool(boundary[cells].any()),
                     closureTouchesWindowBoundary=bool(rim_touches)))
    return labels, pools


def _field_report(g, field, floods, minima, connectivity):
    a = g['arrays'][field]
    f = floods[field]
    labels, pools = _pools(g, a, f, minima['H0'], connectivity)
    boundary, records = _boundary(a.shape), []
    for m in minima[field]:
        i = m['index']
        depth = float(f['depth'][i])
        pool = pools[labels[i]] if labels[i] >= 0 else None
        original = float(floods['H0']['depth'][i])
        old = float(floods['Hold']['depth'][i])
        delta = depth - original
        suspect = depth > 0 and pool is not None and not pool['containsH0min']
        if depth <= 0:
            finding = 'no_closed_pool_in_window'
        elif suspect:
            finding = 'suspect_no_H0min_pocket'
        elif original > 0:
            finding = 'inherited_basin_deepened' if delta > 1e-12 else 'inherited_basin'
        else:
            finding = 'H0_basin_minimum_shift_or_extension_not_newpit_by_index'
        spill = int(f['spill'][i])
        records.append(dict(index=[int(i % a.shape[1]), int(i // a.shape[1])],
                       world=_world(g, i), plateauCellCount=len(m['cells']),
                       minH=float(a.ravel()[i]), minYproxy=float(a.ravel()[i]*Y_PROXY),
                       spillH=float(f['level'][i]), spillYproxy=float(f['level'][i]*Y_PROXY),
                       depthH=depth, depthYproxy=depth*Y_PROXY,
                       spillWorld=_world(g, spill), spillIndex=[spill % a.shape[1], spill // a.shape[1]],
                       spillTouchesWindowBoundary=bool(boundary[spill]),
                       minimumTouchesWindowBoundary=bool(boundary[m['cells']].any()),
                       pool=pool, sameWorld=dict(originalDepthH=original, oldDepthH=old,
                       originalDepthYproxy=original*Y_PROXY, oldDepthYproxy=old*Y_PROXY,
                       newDepthH=float(floods['Hnew']['depth'][i]),
                       newDepthYproxy=float(floods['Hnew']['depth'][i]*Y_PROXY), fieldDepthH=depth,
                       fieldMinusOriginalDepthH=delta,
                       newMinusOriginalDepthH=float(floods['Hnew']['depth'][i]-original),
                       newMinusOldDepthH=float(floods['Hnew']['depth'][i]-old)),
                       suspect=suspect, finding=finding))
    return dict(minima=records, minimumCount=len(records), poolCount=len(pools),
                suspectCount=sum(r['suspect'] for r in records))


def _grid_report(g):
    reports = {}
    for connectivity in (8, 4):
        fields = ('H0', 'Hold', 'Hnew')
        floods = {k: _flood(g['arrays'][k], connectivity) for k in fields}
        minima = {k: _minima(g['arrays'][k], connectivity) for k in fields}
        reports[str(connectivity)] = {k: _field_report(g, k, floods, minima, connectivity)
                                      for k in fields}
    return dict(request=g['request'], shape=list(g['arrays']['H0'].shape),
                connectivity=reports)


def _stability(reports):
    """Record spatial matches, not proof of persistence, causality or acceptance."""
    matches = []
    for pos, a in enumerate(reports):
        ra = a['request']
        if not any(math.isclose(ra['step'], s) for s in (.0625, .03125)):
            continue
        for b in reports[pos+1:]:
            rb = b['request']
            if rb['trace'] != ra['trace'] or not any(math.isclose(rb['step'], s)
                                                    for s in (.0625, .03125)):
                continue
            threshold = max(ra['step'], rb['step']) * 2
            if any(abs(ra[c]-rb[c]) > ra['half']+rb['half'] for c in ('cx', 'cz')):
                continue
            for conn in ('8', '4'):
                for field in ('H0', 'Hold', 'Hnew'):
                    aa = [m for m in a['connectivity'][conn][field]['minima'] if m['depthH'] > 0]
                    bb = [m for m in b['connectivity'][conn][field]['minima'] if m['depthH'] > 0]
                    for left, right, lr, rr in ((aa, bb, ra, rb), (bb, aa, rb, ra)):
                        for ma in left:
                            near = [(math.dist(ma['world'], mb['world']), j)
                                    for j, mb in enumerate(right)]
                            near = sorted(p for p in near if p[0] <= threshold)
                            matches.append(dict(requestA=lr['id'], requestB=rr['id'],
                                connectivity=int(conn), field=field, worldA=ma['world'],
                                thresholdWorld=threshold, stableSpatialMatch=bool(near),
                                nearestWorldB=right[near[0][1]]['world'] if near else None,
                                distanceWorld=near[0][0] if near else None,
                                candidatesWithinThreshold=len(near), ambiguous=len(near) > 1))
    return matches


def analyze(folder, *, status=None):
    """Analyze ALL requests in input order. Status is copied, never inferred."""
    folder = Path(folder)
    grids = _read_grids(folder)
    reports = [_grid_report(g) for g in grids.values()]
    result = dict(schema=1, status=status, statusSource='caller; null means not supplied',
                  gridOnly=True, interpolated=False, YproxyFactor=Y_PROXY,
                  spillDefinition='minimax escape to sampled window boundary',
                  poolDefinition='connected cells strictly below boundary escape level',
                  caveats=['Window-limited depths; boundary spill can understate global depth.',
                           'No-H0min pocket is suspect, not automatically a confirmed new pit.',
                           'Moving a local-minimum index alone does not establish a new pit.',
                           'Spatial stability matches are not acceptance decisions.'],
                  sourceHashes={k: _hash(folder/k) for k in ('requests.csv', 'samples.csv')},
                  grids=reports, stability=_stability(reports))
    _save(folder / 'basin-analysis.json', result)
    return result


def _plotting():
    import matplotlib
    matplotlib.use('Agg')
    import matplotlib.pyplot as plt
    from matplotlib.colors import ListedColormap, Normalize
    palette = np.genfromtxt(PALETTE, delimiter=',', names=True, encoding='utf-8-sig')
    rgb = np.column_stack([palette[k] for k in ('r', 'g', 'b')])
    if (not np.isfinite(rgb).all() or rgb.min() < 0 or rgb.max() > 255
            or not np.allclose(palette['e'][[0, -1]], E_LIMITS)):
        raise ValueError('Invalid project palette')
    cmap = ListedColormap(rgb / 255., name='project_palette')
    return plt, cmap, Normalize(*E_LIMITS, clip=True)


def _e(height):
    return .02 + 1.5 * height


def _shade(a, step):
    light = np.array([-.5, -.3, 1.0])  # x, z, up
    light /= np.linalg.norm(light)
    if min(a.shape) < 2:
        gx = gz = np.zeros_like(a)
    else:
        gz, gx = np.gradient(a * Y_PROXY, step * HS, step * HS)
    dot = (-gx*light[0] - gz*light[1] + light[2]) / np.sqrt(1 + gx*gx + gz*gz)
    return .65 + .35 * np.clip(dot, 0, 1)


def _map(ax, g, a, label, colorful, cmap, norm):
    r, arrays = g['request'], g['arrays']
    shade = _shade(a, r['step'])
    rgb = cmap(norm(_e(a)))[..., :3] if colorful else np.ones((*a.shape, 3))
    x = (arrays['world_x'][0] - r['cx']) * HS
    z = (arrays['world_z'][:, 0] - r['cz']) * HS
    edge = r['step'] * HS / 2
    extent = [x[0]-edge, x[-1]+edge, z[-1]+edge, z[0]-edge]
    ax.imshow(rgb * shade[..., None], origin='upper', extent=extent,
              interpolation='nearest', aspect='equal')
    if len(x) > 1:
        ax.set(xlim=(x[0], x[-1]), ylim=(z[-1], z[0]))
    ax.set(xlabel='local x (blocks)', ylabel='local z (blocks)')
    ax.set_title(label, fontsize=9, loc='left', pad=7)
    ax.tick_params(labelsize=8)


def _page_name(base, page):
    return base + ('' if page == 0 else '-%02d' % (page+1)) + '.png'


def _finish(fig, out, name, title, footer, plt):
    fig.suptitle(title, fontsize=14, y=.985)
    fig.text(.06, .015, footer, fontsize=8, va='bottom')
    fig.subplots_adjust(left=.07, right=.97, top=.90, bottom=.10, hspace=.58, wspace=.32)
    fig.savefig(out/name, dpi=140, facecolor='white')
    plt.close(fig)
    return name


def _map_pages(pairs, out, base, detail=False):
    plt, cmap, norm = _plotting()
    outputs = []
    for start in range(0, len(pairs), 2):
        page = pairs[start:start+2]
        cols = 4 if detail else 3
        fig, axes = plt.subplots(2*len(page), cols, figsize=(4.1*cols, 7.2*len(page)),
                                 squeeze=False)
        for row, (sid, before, after) in enumerate(page):
            a, b = before['arrays'], after['arrays']
            panels = [('H0', a['H0']), ('before', a['Hnew']), ('repaired', b['Hnew'])]
            if detail:
                panels.insert(1, ('Hold', a['Hold']))
            for mode in (0, 1):
                for col, (label, data) in enumerate(panels):
                    r = before['request']
                    title = '%s %s | %s | s=%g' % (r['id'], label, 'color' if mode else 'HS', r['step'])
                    _map(axes[2*row+mode, col], before, data, title, bool(mode), cmap, norm)
        outputs.append(_finish(fig, out, _page_name(base, start//2),
            'Terminal grids: H0 / before / repaired' if not detail else 'Fine grids: same indices',
            'Nearest display, no interpolation. Project CSV palette: e=.02+1.5H, fixed e=[-.35,1.2]; Yproxy=354.66H; HS=2.\n'
            'Light (x,z,up)=(-.5,-.3,1); shade=.65+.35*max(dot(unitNormal,unitLight),0). Repaired is not acceptance.', plt))
    return outputs


def _profile(ax, before, after, indices, abscissa, label, limits, xrange=None):
    arrays, repaired = before['arrays'], after['arrays']
    for name, a, color, style in [('H0', arrays['H0'], '#377eb8', '-'),
                                  ('before Hnew', arrays['Hnew'], '#d66036', '--'),
                                  ('after Hnew', repaired['Hnew'], '#22824b', '-.')]:
        ax.plot(abscissa, a.ravel()[indices]*Y_PROXY, style, color=color,
                lw=1.15, marker='.', markersize=2, label=name)
    ax.set(ylim=limits, xlabel='local distance (blocks)', ylabel='Yproxy = 354.66H')
    if xrange is not None:
        ax.set_xlim(*xrange)
    ax.set_title(label, fontsize=9, loc='left', pad=7)
    ax.grid(alpha=.22)
    ax.tick_params(labelsize=8)
    ax.legend(fontsize=7, loc='best')


def _axis_indices(g, ix, iz, axis):
    n = g['arrays']['H0'].shape[1]
    if axis == 0:
        return iz*n + np.arange(n), (g['arrays']['world_x'][iz] - g['request']['cx'])*HS
    return np.arange(n)*n + ix, (g['arrays']['world_z'][:, ix] - g['request']['cz'])*HS


def _center(g):
    a, r = g['arrays'], g['request']
    i = int(np.argmin((a['world_x']-r['cx'])**2 + (a['world_z']-r['cz'])**2))
    return i % a['H0'].shape[1], i // a['H0'].shape[1]


def _terminal_profiles(pairs, out, limits):
    plt, _, _ = _plotting()
    outputs = []
    half = max((g['request']['half']*HS for _, g, _ in pairs), default=1)
    for start in range(0, len(pairs), 4):
        fig, axes = plt.subplots(4, 2, figsize=(14, 14), squeeze=False)
        for row, (sid, before, after) in enumerate(pairs[start:start+4]):
            ix, iz = _center(before)
            for axis in (0, 1):
                indices, coords = _axis_indices(before, ix, iz, axis)
                _profile(axes[row, axis], before, after, indices, coords,
                         '%s fixed center %s | ix=%d iz=%d' %
                         (before['request']['id'], 'xz'[axis], ix, iz),
                         limits, (-half, half))
        for row in range(len(pairs[start:start+4]), 4):
            for ax in axes[row]:
                ax.set_visible(False)
        outputs.append(_finish(fig, out, _page_name('terminal-profiles', start//4),
            'Terminal profiles: fixed center rows / columns',
            'Actual sampled heights; identical before/after indices. All pages share Y limits.\n'
            'Horizontal coordinates in blocks (2*world offset); no per-panel normalization.', plt))
    return outputs


def _anchors(g):
    """Nearest positive-depth before minimum to FIXED request center; no manual choice."""
    a = g['arrays']['Hnew']
    flood = _flood(a, 8)
    candidates = [m['index'] for m in _minima(a, 8) if flood['depth'][m['index']] > 0]
    cx, cz = _center(g)
    center = cz*a.shape[1] + cx
    i = min(candidates, key=lambda j: (math.dist(_world(g, j), _world(g, center)), j)) \
        if candidates else int(np.argmin(a))
    path, j = [], i
    while j >= 0:
        path.append(int(j))
        j = int(flood['parent'][j])
    spill = int(flood['spill'][i])
    return dict(minimum=i, spill=spill, center=center, path=path,
                positiveDepth=bool(flood['depth'][i] > 0), depthH=float(flood['depth'][i]))


def _track_rows(sid, before, after, anchor):
    a, b = before['arrays'], after['arrays']
    distance, previous, rows = 0., None, []
    n = a['H0'].shape[1]
    for order, i in enumerate(anchor['path']):
        world = _world(before, i)
        if previous is not None:
            distance += math.dist(previous, world) * HS
        rows.append([sid, before['request']['id'], order, i % n, i // n, *world, distance,
                     float(a['H0'].ravel()[i]), float(a['Hnew'].ravel()[i]),
                     float(b['Hnew'].ravel()[i]), i == anchor['spill']])
        previous = world
    return rows


def _fine_profiles(pairs, out, limits):
    plt, _, _ = _plotting()
    outputs, tracks, anchors = [], [], []
    half = max((g['request']['half']*HS for _, g, _ in pairs), default=1)
    prepared = [(p, _anchors(p[1])) for p in pairs]
    row_sets = [_track_rows(*p, anchor) for p, anchor in prepared]
    max_track = max((rows[-1][7] for rows in row_sets), default=1.) or 1.
    for case, (((sid, before, after), anchor), rows) in enumerate(zip(prepared, row_sets)):
        tracks.extend(rows)
        request_id = before['request']['id']
        anchors.append(dict(shortId=sid, requestId=request_id, **anchor))
        fig, axes = plt.subplots(4, 2, figsize=(14, 14))
        n = before['arrays']['H0'].shape[1]
        for row, name in enumerate(('center', 'minimum', 'spill')):
            iz, ix = divmod(anchor[name], n)
            for axis in (0, 1):
                indices, coords = _axis_indices(before, ix, iz, axis)
                title = '%s before %s %s | ix=%d iz=%d' % (request_id, name, 'xz'[axis], ix, iz)
                _profile(axes[row, axis], before, after, indices, coords, title,
                         limits, (-half, half))
        distance = [r[7] for r in rows]
        _profile(axes[3, 0], before, after, anchor['path'], distance,
                 request_id+' fixed before minimax grid track', limits, (0, max_track))
        spill_order = anchor['path'].index(anchor['spill'])
        axes[3, 0].axvline(distance[spill_order], color='#888888', lw=.8, label='spill')
        path_world = np.array([_world(before, i) for i in anchor['path']])
        r = before['request']
        axes[3, 1].plot((path_world[:, 0]-r['cx'])*HS,
                        (path_world[:, 1]-r['cz'])*HS, '.-', lw=.8, markersize=2)
        axes[3, 1].set(xlabel='local x (blocks)', ylabel='local z (blocks)',
                       xlim=(-half, half), ylim=(half, -half), aspect='equal')
        axes[3, 1].set_title(request_id+' sampled grid track (not continuous sampling)', fontsize=9)
        outputs.append(_finish(fig, out, _page_name('new-pit-profiles', case),
            request_id+' fine patch: fixed before anchors, same after indices',
            'Rows: nearest fixed center / before minimum / before spill / 8-neighbor minimax track.\n'
            'Every point is from CSV. Connecting grid samples is NOT an arbitrary continuous-path query.', plt))
    return outputs, tracks, anchors


def _local_range(arrays):
    low = min(float(np.min(a)) for a in arrays)
    high = max(float(np.max(a)) for a in arrays)
    pad = max((high-low)*.06, np.spacing(max(abs(low), abs(high), 1.))*8)
    return [low-pad, high+pad]


def _pit_track(before, after):
    """Crop for display only: full before minimax path remains in evidence/CSV."""
    anchor = _anchors(before)
    rows = _track_rows('', before, after, anchor)
    distance_wu = np.array([r[7]/HS for r in rows])
    spill_order = anchor['path'].index(anchor['spill'])
    cutoff = distance_wu[spill_order] + .5
    keep = distance_wu <= cutoff + 1e-12
    indices = np.array(anchor['path'], dtype=int)[keep]
    a, b = before['arrays'], after['arrays']
    heights = [a['H0'].ravel()[indices]*Y_PROXY, a['Hnew'].ravel()[indices]*Y_PROXY,
               b['Hnew'].ravel()[indices]*Y_PROXY]
    delta = [np.zeros(len(indices)), (a['Hnew'].ravel()[indices]-a['H0'].ravel()[indices])*Y_PROXY,
             (b['Hnew'].ravel()[indices]-a['H0'].ravel()[indices])*Y_PROXY]
    record = dict(requestId=before['request']['id'], minimumIndex=anchor['minimum'],
        spillIndex=anchor['spill'], positiveBeforeDepth=anchor['positiveDepth'],
        beforeDepthYproxy=anchor['depthH']*Y_PROXY, fullTrackCellCount=len(rows),
        displayedTrackIndices=indices.tolist(), displayedDistanceWu=distance_wu[keep].tolist(),
        spillDistanceWu=float(distance_wu[spill_order]), cropEndTargetWu=float(cutoff),
        availablePostSpillWu=float(distance_wu[-1]-distance_wu[spill_order]),
        displayedEndWu=float(distance_wu[keep][-1]), localYrange=_local_range(heights),
        localDeltaYrange=_local_range(delta), displayCropOnly=True,
        validationUsesFullGrid=True, deltaDefinition='354.66*(Hnew-H0); H0 reference=0')
    return distance_wu[keep]*HS, heights, delta, record


def _pit_panel(ax, distance, values, record, delta=False):
    labels = ('H0 ref=0', 'before Hnew-H0', 'after Hnew-H0') if delta else (
        'H0', 'before Hnew', 'after Hnew')
    for y, label, color, style in zip(values, labels, ('#377eb8', '#d66036', '#22824b'),
                                     ('-', '--', '-.')):
        ax.plot(distance, y, style, color=color, marker='.', markersize=3, lw=1.2, label=label)
    limits = record['localDeltaYrange'] if delta else record['localYrange']
    ax.set(ylim=limits, xlabel='fixed before grid-track distance (blocks)',
           ylabel='Delta Yproxy = 354.66*(Hnew-H0)' if delta else 'actual Yproxy = 354.66H')
    ax.set_xlim(0, max(float(distance[-1]), .03125*HS))
    ax.axvline(record['spillDistanceWu']*HS, color='#777777', lw=.9, ls=':')
    title = 'LOCAL DELTA Y RANGE actual units' if delta else 'LOCAL Y RANGE actual Y proxy, not normalize'
    ax.set_title(record['requestId']+'\n'+title, loc='left', fontsize=9, pad=8)
    ax.ticklabel_format(axis='y', style='plain', useOffset=False)
    ax.tick_params(labelsize=8)
    ax.grid(alpha=.22)
    ax.legend(loc='best', fontsize=8)
    ax.text(.02, .02, 'before depth=%.6g Yproxy; dotted=spill' % record['beforeDepthYproxy'],
            transform=ax.transAxes, fontsize=7, va='bottom')


def _pit_saddle_details(pairs, out):
    """A priori: ALL step .03125, non-shift requests, preserving input order."""
    selected = [p for p in pairs if math.isclose(p[1]['request']['step'], .03125)
                and 'shift' not in p[1]['request']['id'].lower()]
    plt, _, _ = _plotting()
    outputs, records = [], []
    for start in range(0, len(selected), 2):
        page = selected[start:start+2]
        fig, axes = plt.subplots(2, 2, figsize=(15, 9), squeeze=False)
        for row, (_, before, after) in enumerate(page):
            distance, heights, delta, record = _pit_track(before, after)
            records.append(record)
            _pit_panel(axes[row, 0], distance, heights, record)
            _pit_panel(axes[row, 1], distance, delta, record, delta=True)
        for row in range(len(page), 2):
            for ax in axes[row]:
                ax.set_visible(False)
        outputs.append(_finish(fig, out, _page_name('pit-saddle-detail', start//2),
            'Pit / saddle detail: all non-shift .03125 requests, fixed input order',
            'Actual CSV grid samples, same indices in all arms. Plot only: before minimum through spill +0.5 wu (1 block).\n'
            'Per-case absolute Y / signed delta axes, no normalization or mean subtraction. Full-grid validation and full tracks retained.', plt))
    return outputs, records


def _paired(before, after):
    if list(before) != list(after):
        raise ValueError('Before/after request IDs or order differ')
    pairs, inventory = [], []
    for num, (key, a) in enumerate(before.items(), 1):
        b = after[key]
        if a['request'] != b['request']:
            raise ValueError('Before/after windows differ: ' + key)
        for field in ('world_x', 'world_z', 'H0', 'Dold'):
            if not np.array_equal(a['arrays'][field], b['arrays'][field]):
                raise ValueError('Before/after original grid differs: ' + key + '/' + field)
        r = a['request']
        group = 'terminal' if key.startswith('terminalfull') and math.isclose(r['step'], .125) else 'fine'
        if group == 'fine' and r['step'] > .0625:
            group = 'context-only'
        sid = 'R%02d' % num
        pairs.append((sid, a, b))
        inventory.append(dict(shortId=sid, request=r, group=group,
                              reason='fixed request order; no minima/quality-based case selection'))
    return pairs, inventory


def render_comparison(before, after, out, *, status=None):
    """Read-only inputs; NEW output directory. Caller may pass any JSON status mapping."""
    before, after, out = Path(before), Path(after), Path(out)
    sources = {str(folder/name): _hash(folder/name) for folder in (before, after)
               for name in ('requests.csv', 'samples.csv')}
    sources[str(PALETTE)] = _hash(PALETTE)
    pairs, inventory = _paired(_read_grids(before), _read_grids(after))
    fields = [g['arrays'][k] for _, a, b in pairs for g in (a, b) for k in ('H0', 'Hnew')]
    low, high = min(float(a.min()) for a in fields), max(float(a.max()) for a in fields)
    pad = max((high-low)*.02, 1e-6)
    limits = [(low-pad)*Y_PROXY, (high+pad)*Y_PROXY]
    terminal = [p for p, r in zip(pairs, inventory) if r['group'] == 'terminal']
    fine = [p for p, r in zip(pairs, inventory) if r['group'] == 'fine']
    out.mkdir(parents=True, exist_ok=False)
    outputs = _map_pages(terminal, out, 'terminal-closeup')
    outputs += _terminal_profiles(terminal, out, limits)
    outputs += _map_pages(fine, out, 'new-pit-detail', detail=True)
    profile_outputs, tracks, anchors = _fine_profiles(fine, out, limits)
    outputs += profile_outputs
    detail_outputs, pit_details = _pit_saddle_details(fine, out)
    outputs += detail_outputs
    with (out/'minimaxspilltrack.csv').open('w', encoding='utf-8', newline='') as f:
        writer = csv.writer(f)
        writer.writerow(['shortId', 'requestId', 'order', 'ix', 'iz', 'world_x', 'world_z',
                         'distanceBlocks', 'H0', 'beforeHnew', 'afterHnew', 'isBeforeSpill'])
        writer.writerows(tracks)
    for path, digest in sources.items():
        if _hash(Path(path)) != digest:
            raise ValueError('Render input changed: ' + path)
    metadata = dict(status=status, statusSource='caller only; never inferred', gridOnly=True,
        allRequestsInInputOrder=inventory, sourceHashes=sources, inputsUnchanged=True,
        eLimits=list(E_LIMITS), colorCoordinate='e=.02+1.5H',
        palette=dict(path=str(PALETTE), sha256=sources[str(PALETTE)],
                     type='ListedColormap', colors='CSV rows r,g,b / 255, in original order'),
        YproxyFactor=Y_PROXY, HS=HS, lightXZUp=[-.5, -.3, 1],
        hillshade='fixed .65+.35*max(dot(unitNormal,unitLight),0)',
        normal='(-dYproxy/dBlockX,-dYproxy/dBlockZ,1)', perPanelNormalize=False,
        profileYlimits=limits, profileFields=['H0', 'beforeHnew', 'afterHnew'],
        fineAnchorRule='nearest closed before minimum to fixed center, else sampled global minimum',
        fineAnchors=anchors, track='8-neighbor boundary-minimax parent path; CSV grid samples only',
        pitSaddleRule='all step=.03125 requests without shift in ID, input order; no outcome selection',
        pitSaddleDetails=pit_details, pitSaddleLocalAxes='actual absolute Yproxy; not normalized',
        colorClipCoordinate='e=.02+1.5H; strictly outside eLimits',
        colorClippedSamples=[dict(shortId=sid, requestId=g['request']['id'], field=k, arm=arm,
            count=int(((_e(g['arrays'][k]) < E_LIMITS[0]) |
                       (_e(g['arrays'][k]) > E_LIMITS[1])).sum()))
            for sid, a, b in pairs for arm, g in [('before', a), ('after', b)]
            for k in ('H0', 'Hold', 'Hnew')],
        outputs={name: _hash(out/name) for name in outputs+['minimaxspilltrack.csv']})
    _save(out/'render-metadata.json', metadata)
    return metadata


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('folder', type=Path, help='Before folder (or sole analysis folder)')
    parser.add_argument('--after', type=Path)
    parser.add_argument('--out', type=Path, help='NEW render output folder; requires --after')
    parser.add_argument('--status', help='Caller-supplied label, e.g. failed or accepted')
    args = parser.parse_args()
    if args.after is not None or args.out is not None:
        if args.after is None or args.out is None:
            parser.error('--after and --out must be supplied together')
        render_comparison(args.folder, args.after, args.out, status=args.status)
        print('Rendered:', args.out)
    else:
        result = analyze(args.folder, status=args.status)
        print('Analyzed', len(result['grids']), 'requests:', args.folder/'basin-analysis.json')


if __name__ == '__main__':
    main()
