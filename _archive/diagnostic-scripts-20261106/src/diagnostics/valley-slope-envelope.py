"""Single-valley preparation plus explicitly authorized one-shot local candidate.
No route edits, production edits, drainage gate, Gaussian, or pointwise cut clipping.
"""
import argparse
import ast
import csv
import importlib.util
import json
from pathlib import Path

import numpy as np
from scipy.ndimage import map_coordinates

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / 'build/valley-slope-envelope-20261104'
SPEC = importlib.util.spec_from_file_location('l0', Path(__file__).with_name('old-route-l0.py'))
L0 = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(L0)
Y = 354.66
CLASS_DIRS = ['build/old-route-l0-sampler-20261104/classes',
              'build/remaining-drop-field-20261104-final/classes']


def snapshot():
    paths = list((ROOT / 'src').rglob('*'))
    paths += list((ROOT.parent / '.openbitfun/plans').rglob('*'))
    paths += list(L0.REFERENCE.rglob('*'))
    for directory in CLASS_DIRS:
        paths += list((ROOT / directory).rglob('*.class'))
    return {str(p.relative_to(ROOT.parent)): L0.digest(p)
            for p in sorted(set(paths)) if p.is_file() and '__pycache__' not in p.parts}


def coarse_section(route, arc, h, sampling):
    p, tangent = L0.at(route, arc)
    normal = np.array([-tangent[0, 1], tangent[0, 0]])
    offsets = np.arange(-32., 32.01, 1.)
    xy = p + offsets[:, None] * normal
    uv = (xy - sampling['originWu']) / sampling['stepWu']
    values = map_coordinates(h, [uv[:, 1], uv[:, 0]], order=1, mode='constant', cval=np.nan)
    low = int(np.argmin(values))
    rise = (values[[0, -1]] - values[low]) * Y
    return low, rise, offsets


def select(routes, sampling, h):
    center = np.array(sampling['originWu']) + sampling['spanWu'] / 4
    visited = 0
    for route in routes:
        # One deterministic station per original row-major route, not window ranking.
        _, arcs, _, _ = L0.project(center[None, :], route)
        arc = float(arcs[0])
        p, direction = L0.at(route, arc)
        if np.any(np.abs(p[0] - center) > 150) or min(arc, route['s'][-1]-arc) < 20:
            continue
        _, tangents = L0.at(route, arc + np.array([-12., 0., 12.]))
        if np.min(tangents @ direction[0]) < .95:
            continue
        visited += 1
        low, rise, offsets = coarse_section(route, arc, h, sampling)
        if 0 < low < len(offsets)-1 and np.all(rise >= 3):
            return route, arc, {'NWcenterWu': center.tolist(), 'halfExtentWu': 150,
                'eligibleRoutesVisited': visited, 'coarseLowOffsetWu': float(offsets[low]),
                'coarseBankRiseY': rise.tolist(), 'minimumTangentCos': float(np.min(tangents @ direction[0])),
                'order': 'original seed z,x row-major; one nearest-center station per route; first pass',
                'coarseRule': '+/-32wu @1 bilinear LOCATION ONLY; interior minimum, both endpoint rises >=3Y'}
    raise ValueError('No first-pass valley in fixed NW neighborhood; do not expand search')


def requests(route, arc):
    center = L0.at(route, arc)[0][0]
    axis = np.arange(385)*.25-48
    xx, zz = np.meshgrid(axis+center[0], axis+center[1])
    yield 'grid', np.arange(385**2), np.c_[xx.ravel(), zz.ravel()]
    for i, along in enumerate([-12., 0., 12.]):
        p, tangent = L0.at(route, arc+along)
        normal = np.array([-tangent[0, 1], tangent[0, 0]])
        offsets = np.arange(1281)*.0625-40
        yield 'cross-'+str(i), offsets, p+offsets[:, None]*normal


def prepare():
    OUT.mkdir(exist_ok=False)
    frozen = snapshot()
    sampling = json.loads((L0.REFERENCE/'data/sampling.json').read_text('utf-8'))
    manifest = json.loads((L0.REFERENCE/'manifest.json').read_text('utf-8'))
    for key, base in [('outputHashes', L0.REFERENCE), ('sourceHashes', ROOT)]:
        for name, expected in manifest[key].items():
            assert L0.digest(base/name) == expected, name
    for name in ['carrier-height', 'strong-height']:
        h = np.fromfile(L0.REFERENCE/('data/'+name+'.f64be'), dtype='>f8')
        assert h.size == 769**2 and np.isfinite(h).all()
    h = np.fromfile(L0.REFERENCE/'data/carrier-height.f64be', dtype='>f8').reshape(769, 769)
    routes = L0.load_routes()
    route, arc, selection = select(routes, sampling, h)
    count = 0
    counts = {}
    with (OUT/'requests.csv').open('x', newline='', encoding='utf-8') as stream:
        writer = csv.writer(stream)
        writer.writerow(L0.HEADER)
        for kind, ts, points in requests(route, arc):
            counts[kind] = len(ts)
            for t, (x, z) in zip(ts, points):
                writer.writerow([f'valley-{count:06d}', 'NW-valley', kind,
                                 format(float(t), '.17g'), format(x, '.17g'), format(z, '.17g'), route['id'], ''])
                count += 1
    case = {'group': 'NW-valley', 'source': route['id'], 'target': '', 'requestCounts': counts}
    meta = {'scope': 'preparation only; no new heights; stop for slope-envelope authorization',
            'cases': [case], 'requestCount': count, 'requestsSha256': L0.digest(OUT/'requests.csv'),
            'arcWu': arc, 'centerWorldWu': L0.at(route, arc)[0][0].tolist(), 'selection': selection,
            'grid': 'center +/-48wu @.25, 385x385; row z then x',
            'sections': 'arc -12/0/+12wu; left normal (-tz,tx); +/-40wu @.0625'}
    L0.save(OUT/'cases.json', meta)
    L0.save(OUT/'frozen-inputs.json', frozen)
    assert frozen == snapshot()
    print(json.dumps(meta, indent=2))


def section_evidence(rows):
    t = L0.column(rows, 't')
    y = L0.column(rows, 'H0')*Y
    inside = np.flatnonzero(np.abs(t) <= 32)
    low = int(inside[np.argmin(y[inside])])
    slope = np.gradient(y, .0625)
    # First outward maximum uphill slope = provisional foot-to-slope transition.
    # Not an asserted shoulder: no cut or synthesized surface uses these points.
    bounds = []
    for side in [-1, 1]:
        ids = np.arange(low+side, len(t) if side > 0 else -1, side)
        ids = ids[np.abs(t[ids]) <= 32]
        uphill = slope[ids]*side
        turns = np.flatnonzero((uphill[1:-1] >= uphill[:-2]) &
                               (uphill[1:-1] > uphill[2:]) & (uphill[1:-1] > 0)) + 1
        turns = [j for j in turns if y[ids[j]]-y[low] >= 1 and abs(t[ids[j]]-t[low]) >= 2]
        k = int(ids[turns[0]]) if turns else int(ids[-1])
        bounds.append(k)
    left, right = bounds
    c = L0.column(rows, 'Dcombined')*Y
    supported = np.flatnonzero(c > 0)
    valid = bool(0 < low < len(t)-1 and y[low] <= y[low-1] and y[low] <= y[low+1]
                 and min(y[inside[0]]-y[low], y[inside[-1]]-y[low]) >= 3)
    return {'actualValley': valid, 'authorizationEligible': valid,
            'warning': '' if valid else 'NOT a verified trough; constrained search low is not a natural valley. No shaping envelope proposed.',
            'lowOffsetWu': float(t[low]),
            'lowWorldWu': [rows[low]['x'], rows[low]['z']], 'lowYproxy': float(y[low]),
            'bankRiseAt32Y': [float(y[inside[0]]-y[low]), float(y[inside[-1]]-y[low])],
            'bankRiseAt40Y': [float(y[0]-y[low]), float(y[-1]-y[low])],
            'returnOffsetsWu': [float(t[left]), float(t[right])],
            'widthFromLowWu': [float(t[low]-t[left]), float(t[right]-t[low])],
            'returnRiseY': [float(y[left]-y[low]), float(y[right]-y[low])],
            'returnMeaning': 'provisional foot-to-slope maximum-gradient turning points, NOT clear shoulders; fallback +/-32 if no turn',
            'oldCombinedSupportExtentWu': [float(t[supported[0]]), float(t[supported[-1]])] if len(supported) else None,
            'zeroOldCutWithinProposedRangeWu': intervals(t, (t > t[left]) & (t < t[right]) & (c == 0)),
            'oldCombinedMaxY': float(c.max()), 'oldCutAtNaturalLowY': float(c[low]),
            'sourceContributorCountRange': [min(int(r['contributors']) for r in rows), max(int(r['contributors']) for r in rows)]}


def intervals(t, mask):
    ids = np.flatnonzero(mask)
    if not len(ids):
        return []
    groups = np.split(ids, np.flatnonzero(np.diff(ids) > 1)+1)
    return [[float(t[g[0]]), float(t[g[-1]])] for g in groups]


def render(meta, groups, entries):
    plt, cmap, norm, palette = L0.plot_setup()
    data = groups['NW-valley']
    center = np.array(meta['centerWorldWu'])
    routes = L0.load_routes()
    route = next(r for r in routes if r['id'] == meta['cases'][0]['source'])
    fig, ax = plt.subplots(figsize=(10, 9))
    extent = [center[0]-48.125, center[0]+48.125, center[1]+48.125, center[1]-48.125]
    h = L0.column(data['grid'], 'H0').reshape(385, 385)
    ax.imshow(L0.terrain_rgb(h, .25, cmap, norm), extent=extent, interpolation='nearest')
    for r in routes:
        p = r['p']
        if np.any(np.all(np.abs(p-center) <= 50, axis=1)):
            ax.plot(p[:, 0], p[:, 1], color='black', lw=.35, alpha=.3)
    ax.plot(route['p'][:, 0], route['p'][:, 1], 'k-', lw=1.5, label='selected frozen route')
    for i, (kind, e) in enumerate(entries.items()):
        rows = data[kind]
        color = ['#ffde59', '#00e5ff', '#ff66cc'][i]
        ax.plot(L0.column(rows, 'x'), L0.column(rows, 'z'), color=color, lw=1, label=kind)
        if e['actualValley']:
            for offset in e['returnOffsetsWu']:
                row = min(rows, key=lambda r: abs(r['t']-offset))
                ax.scatter(row['x'], row['z'], marker='x', s=60, color=color)
            ax.scatter(*e['lowWorldWu'], s=24, color=color, edgecolor='black')
        else:
            ax.text(rows[640]['x'], rows[640]['z'], 'cross-2: NOT trough', color=color, fontsize=8)
    ax.set(xlim=extent[:2], ylim=extent[2:], xlabel='world x wu', ylabel='world z wu',
           title='Actual H0 | dots: natural lows | X: provisional return points (not shoulders)')
    ax.legend(fontsize=8)
    fig.tight_layout()
    fig.savefig(OUT/'original-location.png', dpi=160)
    plt.close(fig)
    fig, axes = plt.subplots(3, 2, figsize=(15, 12), sharex=True)
    for i, (kind, e) in enumerate(entries.items()):
        rows = data[kind]
        t = L0.column(rows, 't')
        y = L0.column(rows, 'H0')*Y
        old = L0.column(rows, 'Hstrong')*Y
        c = L0.column(rows, 'Dcombined')*Y
        left, right = e['returnOffsetsWu']
        ax, budget = axes[i]
        ax.plot(t, y, color='gray', label='actual H0')
        ax.plot(t, old, color='black', label='actual OLD strong')
        ax.axvline(0, color='gray', ls=':', label='old route n=0')
        if e['actualValley']:
            ax.axvline(e['lowOffsetWu'], color='teal', ls=':', label='actual natural low')
            ax.annotate('8-12Y depth INTENT only', xy=(e['lowOffsetWu'], e['lowYproxy']-10),
                        xytext=(e['lowOffsetWu'], e['lowYproxy']), arrowprops={'arrowstyle': '->'}, fontsize=8)
        else:
            ax.text(.02, .92, 'NOT a trough: no envelope / depth intent here', transform=ax.transAxes, color='red')
        ax.set(title=kind+' actual profiles (no candidate curve)', ylabel='Yproxy')
        budget.plot(t, c, color='black', label='old C=Dcombined, actual pointwise limit')
        budget.axhspan(8, 12, color='orange', alpha=.15, label='center intent 8-12Y (NOT D(n))')
        budget.axhline(.08*Y, color='red', ls='--', label='unchanged .08H cap =28.3728Y')
        budget.set(title='Budget / space intent ONLY; no candidate D computed', ylabel='cut Yproxy')
        for axis in [ax, budget]:
            if e['actualValley']:
                axis.axvspan(left, right, color='teal', alpha=.08, label='proposed side-foot envelope; needs approval')
                axis.axvline(left, color='teal', ls='--')
                axis.axvline(right, color='teal', ls='--')
            axis.grid(alpha=.2)
            axis.legend(fontsize=7)
            axis.set_xlabel('signed normal offset wu; left=(-tz,tx)')
    fig.tight_layout()
    fig.savefig(OUT/'original-sections.png', dpi=160)
    plt.close(fig)
    return L0.digest(palette)


def analyze():
    L0.OUTPUT = OUT
    meta, groups, validation = L0.load_actual()
    entries = {k: section_evidence(v) for k, v in groups['NW-valley'].items() if k.startswith('cross-')}
    palette_hash = render(meta, groups, entries)
    frozen = json.loads((OUT/'frozen-inputs.json').read_text('utf-8'))
    current = snapshot()
    own = str(Path(__file__).resolve().relative_to(ROOT.parent))
    assert {k: v for k, v in frozen.items() if k != own} == {k: v for k, v in current.items() if k != own}, 'Frozen old files changed'
    ast.parse(Path(__file__).read_text('utf-8'))
    report = {'scope': 'preparation + authorization request ONLY; no synthesized candidate D or height',
              'route': meta['cases'][0]['source'], 'centerWorldWu': meta['centerWorldWu'],
              'sections': entries, 'budget': {'centerDepthIntentY': [8, 12], 'sideDepthIntentY': '0..12, unspecified; no solved profile',
              'globalCapH': .08, 'globalCapY': .08*Y, 'alongSegmentWu': [-12, 12],
              'alongFade': 'not designed; must stay inside sampled +/-48 window; no authorization assumed',
              'oldConstraint': 'authorized old C: 0<=D<=C pointwise. Proposed foot envelope may require D>C and D>0 at C=0.',
              'decision': 'NEEDS USER CONFIRMATION before any additional side cutting / widening. No feasibility claimed; no hard min.',
              'centerCWarning': 'If natural low has C<8Y, even 8Y center intent breaches old pointwise cap; report, do not clip.'},
              'validation': validation, 'AST': True, 'frozenHashUnchanged': True,
              'paletteSha256': palette_hash, 'samplesSha256': L0.digest(OUT/'actual/samples.csv'),
              'limitations': ['Offline actual original Java H0/C sampler, not Minecraft screenshot.',
              'Other old routes retained: selected short segment straight but contributor count may exceed one; no junction-free claim.',
              'Provisional return markers are gradient-turn foot transitions, not verified shoulders or a solved 2D envelope.',
              'No drainage gate, no candidate evaluation or production changes.'],
              'renderContract': {'e': '.02+1.5H', 'fixedERange': [-.35, 1.2], 'light': [-.5, -.3, 1], 'HS': 2, 'Yproxy': Y, 'perPanelNormalize': False}}
    report['scriptSha256'] = L0.digest(Path(__file__))
    report['stopReason'] = 'cross-0/1 have local troughs; cross-2 is monotone toward another low area, NOT valley. Do not force or connect envelope across cross-2.'
    L0.save(OUT/'report-final.json', report)
    print(json.dumps({k: report[k] for k in ['route', 'centerWorldWu', 'sections', 'budget', 'AST', 'frozenHashUnchanged']}, indent=2))


def smooth5(t):
    return t*t*t*(10+t*(-15+6*t))


def candidate_geometry(meta, data):
    lows, boundaries = [], [[], []]
    for i in [0, 1]:
        rows = data['cross-'+str(i)]
        offsets = [-6.6875, .1875]
        bounds = [[-15.8125, 4.5], [-10.3125, 4.5625]]
        for dest, t in [(lows, offsets[i]), (boundaries[0], bounds[i][0]),
                        (boundaries[1], bounds[i][1])]:
            row = min(rows, key=lambda r: abs(r['t']-t))
            dest.append([row['x'], row['z']])
    lows = np.array(lows)
    axis = lows[1]-lows[0]
    length = float(np.linalg.norm(axis))
    axis /= length
    normal = np.array([-axis[1], axis[0]])
    coefficients = []
    for boundary in boundaries:
        uv = (np.array(boundary)-lows[0]) @ np.array([axis, normal]).T
        coefficients.append(np.polyfit(uv[:, 0], uv[:, 1], 1).tolist())
    assert np.prod([np.polyval(c, length/2) for c in coefficients]) < 0
    return {'origin': lows[0].tolist(), 'axis': axis.tolist(), 'normal': normal.tolist(),
            'lengthWu': length, 'boundaryLinearCoefficients': coefficients,
            'actualLowAnchorsWu': lows.tolist(), 'actualBoundaryAnchorsWu': boundaries,
            'coordinates': 'fixed low-anchor chord world coordinates; no nearest-segment projection',
            'support': '0<u<length, between independently fitted real boundary lines; end fade within first/last quarter'}


def evaluate_cut(xy, geometry):
    uv = (xy-np.array(geometry['origin'])) @ np.array([geometry['axis'], geometry['normal']]).T
    u, v = uv.T
    length = geometry['lengthWu']
    sides = np.array([np.polyval(c, u) for c in geometry['boundaryLinearCoefficients']])
    left, right = np.min(sides, axis=0), np.max(sides, axis=0)
    inside = (u > 0) & (u < length) & (v > left) & (v < right) & (left < 0) & (right > 0)
    cut = np.zeros(len(xy))
    a, b = u[inside]/length, v[inside]
    q = np.where(b < 0, b/left[inside], b/right[inside])
    # Piecewise polynomials share value, first and second derivatives at center.
    # Each boundary has D=D'=D''=0; retain H0 detail without filtering it.
    transverse = 1-smooth5(q)
    fade = np.ones(len(a))
    start, end = a < .25, a > .75
    fade[start] = smooth5(a[start]/.25)
    fade[end] = smooth5((1-a[end])/.25)
    cut[inside] = (10/Y)*transverse*fade
    assert np.isfinite(cut).all() and np.all(cut >= 0) and cut.max() <= .08
    return cut, inside, uv


def actual_candidate_sections(dest, geometry, source):
    import subprocess
    dest.mkdir(exist_ok=False)
    count = 0
    counts = {}
    with (dest/'requests.csv').open('x', newline='', encoding='utf-8') as stream:
        writer = csv.writer(stream)
        writer.writerow(L0.HEADER)
        for i, fraction in enumerate([.25, .5, .75]):
            kind = 'cross-'+str(i)
            counts[kind] = 1281
            center = np.array(geometry['origin'])+fraction*geometry['lengthWu']*np.array(geometry['axis'])
            for t in np.arange(1281)*.0625-40:
                x, z = center+t*np.array(geometry['normal'])
                writer.writerow([f'candidate-section-{count}', 'candidate-sections', kind,
                                 format(t, '.17g'), format(x, '.17g'), format(z, '.17g'), source, ''])
                count += 1
    L0.save(dest/'cases.json', {'requestCount': count, 'requestsSha256': L0.digest(dest/'requests.csv'),
        'cases': [{'group': 'candidate-sections', 'requestCounts': counts}]})
    command = ['java', '-Xmx3g', '-cp', ';'.join(CLASS_DIRS),
               'com.geogenesis.worldgen.terrain.morph.OldRouteL0Probe',
               str(dest/'requests.csv'), str(dest/'actual')]
    subprocess.run(command, cwd=str(ROOT), check=True)
    L0.OUTPUT = dest
    _, groups, validation = L0.load_actual()
    return groups['candidate-sections'], validation, command


def candidate_plots(dest, meta, data, geometry, heights, section_data):
    plt, cmap, norm, _ = L0.plot_setup()
    origin = np.array(meta['centerWorldWu'])
    extent = [origin[0]-48.125, origin[0]+48.125, origin[1]+48.125, origin[1]-48.125]
    midpoint = np.array(geometry['origin'])+.5*geometry['lengthWu']*np.array(geometry['axis'])
    titles = ['Original H0', 'OLD strong: ALL old cuts', 'Local valley: H0 minus THIS cut only']
    names = ['original', 'strong', 'candidate']
    images = [L0.terrain_rgb(h, .25, cmap, norm) for h in heights]
    fig, axes = plt.subplots(1, 3, figsize=(18, 6))
    for ax, name, title, rgb in zip(axes, names, titles, images):
        ax.imshow(rgb, extent=extent, interpolation='nearest')
        ax.set(xlim=(midpoint[0]-36, midpoint[0]+36), ylim=(midpoint[1]+36, midpoint[1]-36), title=title)
        ax.set_axis_off()
        single, panel = plt.subplots(figsize=(8, 8))
        panel.imshow(rgb, extent=extent, interpolation='nearest')
        panel.set(xlim=ax.get_xlim(), ylim=ax.get_ylim(), title=title)
        panel.set_axis_off()
        single.tight_layout()
        single.savefig(dest/(name+'.png'), dpi=180)
        plt.close(single)
    fig.tight_layout()
    fig.savefig(dest/'comparison.png', dpi=180)
    plt.close(fig)
    fig, ax = plt.subplots(figsize=(9, 9))
    ax.imshow(images[2], extent=extent, interpolation='nearest')
    route = next(r for r in L0.load_routes() if r['id'] == meta['cases'][0]['source'])
    ax.plot(route['p'][:, 0], route['p'][:, 1], 'k-', lw=1, label='unchanged OLD route')
    u = np.linspace(0, geometry['lengthWu'], 201)
    for c in geometry['boundaryLinearCoefficients']:
        xy = np.array(geometry['origin'])+u[:, None]*geometry['axis']+np.polyval(c, u)[:, None]*geometry['normal']
        ax.plot(xy[:, 0], xy[:, 1], '--', color='cyan', lw=1)
    low = np.array(geometry['actualLowAnchorsWu'])
    ax.plot(low[:, 0], low[:, 1], color='orange', lw=1, label='H0 low descriptor (NOT moved route)')
    for i, rows in enumerate(section_data.values()):
        ax.plot(L0.column(rows, 'x'), L0.column(rows, 'z'), lw=.7, label='actual section '+str(i))
    ax.set(xlim=(midpoint[0]-36, midpoint[0]+36), ylim=(midpoint[1]+36, midpoint[1]-36),
           title='Local candidate location; other OLD cuts NOT included', xlabel='world x wu', ylabel='world z wu')
    ax.legend(fontsize=8)
    fig.tight_layout()
    fig.savefig(dest/'location.png', dpi=160)
    plt.close(fig)
    return section_plots(plt, dest, geometry, section_data)


def section_plots(plt, dest, geometry, data):
    fig, axes = plt.subplots(3, 2, figsize=(15, 11))
    evidence = {}
    for i, (kind, rows) in enumerate(data.items()):
        xy = np.c_[L0.column(rows, 'x'), L0.column(rows, 'z')]
        cut, inside, uv = evaluate_cut(xy, geometry)
        t = L0.column(rows, 't')
        h = L0.column(rows, 'H0')
        new = h-cut
        for field, values, color in [('H0', h, 'gray'), ('OLD all cuts', L0.column(rows, 'Hstrong'), 'black'),
                                      ('local candidate', new, '#197b45')]:
            axes[i, 0].plot(t, values*Y, color=color, label=field)
        for field, values, color in [('H0', h, 'gray'), ('candidate', new, '#197b45')]:
            axes[i, 1].plot(t, np.gradient(values*Y, .0625), color=color, label=field+' dY/dwu')
        for ax in axes[i]:
            ax.set_xlim(-24, 24)
            ax.grid(alpha=.2)
            ax.legend(fontsize=8)
            ax.set_xlabel('fixed chord normal offset wu')
        axes[i, 0].set(ylabel='absolute Yproxy', title=f'Actual Java section at u/L={[.25,.5,.75][i]}')
        axes[i, 1].set(ylabel='slope Yproxy/wu', title='Actual H0 / designed H0-D slopes; no centering constraint')
        local = np.flatnonzero(inside)
        k = int(local[np.argmin(new[local])])
        slopes = np.gradient(new*Y, .0625)
        evidence[kind] = {'centerDepthY': float(cut[640]*Y), 'maxDepthY': float(cut.max()*Y),
                          'actualCandidateLowOffsetWu': float(t[k]),
                          'candidateCenterSlopeYperWu': float(slopes[640]),
                          'cutSupportOffsetWu': intervals(t, cut > 0),
                          'newCutOutsideOldSupportWu': intervals(t, (cut > 0) & (L0.column(rows, 'Dcombined') == 0)),
                          'newCutExceedsOldCWu': intervals(t, cut > L0.column(rows, 'Dcombined')+1e-14)}
    fig.tight_layout()
    fig.savefig(dest/'sections.png', dpi=170)
    plt.close(fig)
    return evidence


def candidate():
    dest = OUT/'candidate-v1'
    assert not dest.exists(), 'One-shot output exists; do not silently iterate'
    before = snapshot()
    L0.OUTPUT = OUT
    meta, groups, validation = L0.load_actual()
    data = groups['NW-valley']
    geometry = candidate_geometry(meta, data)
    sections, section_validation, command = actual_candidate_sections(dest, geometry, meta['cases'][0]['source'])
    grid = data['grid']
    xy = np.c_[L0.column(grid, 'x'), L0.column(grid, 'z')]
    cut, inside, uv = evaluate_cut(xy, geometry)
    h0 = L0.column(grid, 'H0')
    old = L0.column(grid, 'Hstrong')
    new = h0-cut
    checks = {'finite': bool(np.isfinite(new).all()), 'nonnegativeCut': bool(np.all(cut >= 0)),
              'oldMax08H': bool(cut.max() <= .08), 'noRaiseAboveH0': bool(np.all(new <= h0)),
              'outsideDomainBitIdentity': new[~inside].tobytes() == h0[~inside].tobytes(),
              'gridEdgesUnchanged': bool(np.array_equal(new.reshape(385,385)[[0,-1], :], h0.reshape(385,385)[[0,-1], :])
                  and np.array_equal(new.reshape(385,385)[:, [0,-1]], h0.reshape(385,385)[:, [0,-1]]))}
    heights = [a.reshape(385,385) for a in [h0, old, new]]
    evidence = candidate_plots(dest, meta, data, geometry, heights, sections)
    after = snapshot()
    checks['frozenOldSourceMainRoutesUnchanged'] = before == after
    ast.parse(Path(__file__).read_text('utf-8'))
    checks['AST'] = True
    assert all(checks.values()), checks
    changed = cut > 0
    combined = L0.column(grid, 'Dcombined')
    main_hashes = {k.replace('\\', '/'): v for k, v in before.items() if '/src/main/' in k.replace('\\', '/')}
    np.savez_compressed(dest/'candidate-data.npz', xy=xy, H0=h0, Hstrong=old, Hcandidate=new, D=cut)
    record = {'scope': 'one authorized local visual candidate; other OLD cuts excluded from new arm',
              'authorization': 'User allowed local redistribution beyond old pointwise C/support ONLY here, center approx 8-12Y, max .08H; three arms then stop.',
              'geometry': geometry, 'design': 'D=(10/354.66)*(1-smooth5(abs(v)/sideWidth))*endFade(u). Independent real boundary lines; C2 at center/boundaries and both ends; no Gaussian, nearest segments, max composition, or output clipping.',
              'detail': 'Subtract smooth cut from actual unfiltered H0; center has zero first/second D derivatives; final low NOT forced to descriptor.',
              'checks': checks, 'gridValidation': validation, 'sectionsValidation': section_validation,
              'samplerCommand': command, 'maxDepthH': float(cut.max()), 'maxDepthY': float(cut.max()*Y),
              'changedWorldBoundsWu': [xy[changed].min(axis=0).tolist(), xy[changed].max(axis=0).tolist()],
              'changedGridSamples': int(changed.sum()), 'newSupportGridSamples': int(np.sum(changed & (combined == 0))),
              'exceedsOldCGridSamples': int(np.sum(cut > combined+1e-14)), 'sections': evidence,
              'mainFileCount': len(main_hashes), 'mainSha256': main_hashes,
              'routesSha256': L0.digest(L0.REFERENCE/'data/routes.csv'),
              'frozenAllBeforeAfterSha256': before,
              'scriptSha256': L0.digest(Path(__file__)),
              'render': {'e': '.02+1.5H', 'fixedRange': [-.35,1.2], 'light': [-.5,-.3,1], 'HS': 2, 'Yproxy': Y, 'perPanelNormalize': False},
              'caveats': ['C2 refers to added cut, not to original H0 regularity.',
                          'Short authorized reach requires compact end transitions; an artificial local depression / capsule may remain visible.',
                          'No drainage or naturalness acceptance claimed. Original third no-valley cross-section not shaped.',
                          'Three new actual sections use fixed chord normals, not the two original route normals; endpoints fade to H0.'],
              'imagesSha256': {p.name: L0.digest(p) for p in dest.glob('*.png')}}
    L0.save(dest/'meta.json', record)
    print(json.dumps({k: record[k] for k in ['checks', 'maxDepthY', 'changedWorldBoundsWu', 'newSupportGridSamples', 'exceedsOldCGridSamples', 'mainFileCount', 'sections']}, indent=2))


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('command', choices=['prepare', 'analyze', 'candidate'])
    args = parser.parse_args()
    {'prepare': prepare, 'analyze': analyze, 'candidate': candidate}[args.command]()
