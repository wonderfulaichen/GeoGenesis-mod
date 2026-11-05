"""One fixed offline decomposed carve, never production. Run: python -B this-file.py.
Only stored H0/strong samples and frozen routes are read; no Rune or terrain resampling.
"""
import csv
import hashlib
import json
import os
from pathlib import Path
import subprocess
import time

import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.collections import LineCollection
from matplotlib.colors import ListedColormap, Normalize
import numpy as np
import scipy
from scipy.ndimage import gaussian_filter, maximum_filter, minimum_filter, map_coordinates

ROOT = Path(__file__).resolve().parents[2]
REPO = ROOT.parent
FROZEN = ROOT / 'build/strong-window-erosion-20261104-final/data'
PREVIOUS = ROOT / 'build/full-network-valley-20261104'
PALETTE = ROOT / 'build/fast-erosion-scale-only-20261103-final/data/project-palette.csv'
JAVA = ROOT / 'src/diagnostics/java/com/geogenesis/worldgen/terrain/morph/DecomposedCarveProbe.java'
OUTPUT_BASE = ROOT / 'build/decomposed-carve-20261104'
Y, BASE_SIGMA, SIGMAS = 354.66, 6., (3., 7.)
DEPTH, SPLAT_STEP, TRUNCATE = min(.08, 16. / Y), .5, 4.
LIGHT = np.array([-.5, -.3, 1.])
LIGHT /= np.linalg.norm(LIGHT)
LABELS = ['原始 H0', '旧强下切', '分解预侵蚀（固定候选）']
FOOTER = ('base=Gaussian(H0,6wu)；D≤16Y；双尺度3/7wu密度；detail保留1−0.70g。\n'
          'e=.02+1.5H；固定色带[-.35,1.2]；光照[-.5,-.3,1]；HS=2；Yproxy=354.66；无逐图归一化。')


def sha(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024*1024), b''):
            digest.update(block)
    return digest.hexdigest()


def save_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False)+'\n',
                    encoding='utf-8')


def fresh_output():
    if not OUTPUT_BASE.exists():
        OUTPUT_BASE.mkdir()
        return OUTPUT_BASE
    for index in range(1, 10000):
        out = OUTPUT_BASE / f'fresh-{index:02d}'
        if not out.exists():
            out.mkdir()
            return out
    raise FileExistsError('No unused fresh directory')


def snapshot(out):
    names = subprocess.check_output(['git', 'ls-files', '-c', '-o', '--exclude-standard', '-z'],
                                    cwd=REPO).decode('utf-8').split('\0')
    paths = {REPO / name for name in names if name and (REPO / name).is_file()}
    directories = [ROOT/'src', FROZEN.parent, PREVIOUS, OUTPUT_BASE,
                   ROOT/'build/terminal-depth-repair-20261104',
                   ROOT/'build/remaining-drop-field-sparse-equilibrated-20261104',
                   ROOT/'build/old-route-l0-sampler-20261104']
    for directory in directories:
        paths.update(p for p in directory.rglob('*') if p.is_file() and out not in p.parents)
    paths.add(PALETTE)
    paths.difference_update([JAVA, Path(__file__).resolve()])
    return {p.relative_to(REPO).as_posix(): sha(p) for p in sorted(paths)
            if out not in p.parents}


def distribution(values):
    values = np.asarray(values)
    if not values.size:
        return None
    levels = [0, .1, .3, .5, .6, .7, .9, .99, 1]
    keys = ['min', 'P10', 'P30', 'P50', 'P60', 'P70', 'P90', 'P99', 'max']
    return dict(zip(keys, map(float, np.quantile(values, levels))))


def load_routes():
    routes, points, current = [], [], None
    with (FROZEN/'routes.csv').open(encoding='utf-8', newline='') as stream:
        reader = csv.DictReader(stream)
        assert reader.fieldnames == ['trace', 'vertex', 'x', 'z']
        for row in reader:
            if row['trace'] != current:
                if current is not None:
                    routes.append(np.asarray(points))
                current, points = row['trace'], []
            assert int(row['vertex']) == len(points)
            points.append([float(row['x']), float(row['z'])])
    routes.append(np.asarray(points))
    assert len(routes) == 8100 and sum(len(r)-1 for r in routes) == 471997
    assert all(np.isfinite(r).all() for r in routes)
    return routes


def splat(routes, lower, shape):
    """Identical .5wu arclength rule at both scales; no re-routing or vertex jitter."""
    weights = np.zeros(shape, dtype=float)
    generated, deposited = 0, 0
    for route in routes:
        vectors = np.diff(route, axis=0)
        lengths = np.linalg.norm(vectors, axis=1)
        assert (lengths > 0).all()
        arc = np.r_[0., np.cumsum(lengths)]
        targets = np.r_[np.arange(0., arc[-1], SPLAT_STEP), arc[-1]]
        indices = np.clip(np.searchsorted(arc, targets, side='right')-1, 0, len(lengths)-1)
        samples = route[indices] + ((targets-arc[indices])/lengths[indices])[:, None]*vectors[indices]
        cells = np.floor(samples-lower).astype(np.int64)
        inside = ((cells >= 0).all(axis=1) & (cells[:, 0] < shape[1]) & (cells[:, 1] < shape[0]))
        np.add.at(weights, (cells[inside, 1], cells[inside, 0]), 1.)
        generated += len(samples)
        deposited += int(inside.sum())
    assert weights.sum() == deposited
    return weights, {'routes': len(routes), 'generatedSamples': generated, 'depositedSamples': deposited,
                     'gridStepWu': 1., 'sampleStepWu': SPLAT_STEP,
                     'rule': 'floor cell; every .5wu arclength plus final endpoint once; phase across vertices'}


def bilinear(array, lower, step, points):
    xy = (np.asarray(points).reshape(-1, 2)-lower)/step
    assert (xy >= -1e-9).all() and (xy[:, 0] <= array.shape[1]-1+1e-9).all()
    assert (xy[:, 1] <= array.shape[0]-1+1e-9).all(), 'No extrapolation'
    return map_coordinates(array, [xy[:, 1], xy[:, 0]], order=1, prefilter=False, mode='nearest')


def blur_weights(weights, sigma):
    return gaussian_filter(weights, sigma=sigma, mode='constant', cval=0., truncate=TRUNCATE)


def isolated_reference(out):
    lower = np.array([-140., -40.])
    weights, record = splat([np.array([[-100., 0.], [100., 0.]])], lower, (81, 281))
    distances = np.arange(0., 35.0001, .01)
    points = np.column_stack((np.full(len(distances), .5), .5+distances))
    references, profiles = [], []
    for sigma in SIGMAS:
        smooth = blur_weights(weights, sigma)
        reference = float(smooth[40, 140])
        assert reference > 0
        references.append(reference)
        # g is made on the 1wu lattice before bilinear sampling, as in the real network.
        g = -np.expm1(-smooth/reference)
        profiles.append(bilinear(g, lower+.5, 1., points))
    np.savez_compressed(out/'data/isolated-line.npz', weights=weights, distanceWu=distances,
                        gSmall=profiles[0], gLarge=profiles[1], S_line=references)
    record.update({'lengthWu': 200., 'sigmaWu': SIGMAS, 'S_line': references,
                   'definition': 'center of same-sigma blurred 200wu isolated straight line',
                   'centerXZwu': [.5, .5], 'gAtCenter': float(1-np.exp(-1.))})
    return references, distances, np.asarray(profiles), record


def network_fields(out, routes, origin, span, references):
    lower = np.floor(origin-32.)
    upper = np.ceil(origin+span+32.)
    shape = tuple((upper-lower).astype(int)[::-1])
    weights, record = splat(routes, lower, shape)
    gs = [-np.expm1(-blur_weights(weights, sigma)/reference)
          for sigma, reference in zip(SIGMAS, references)]
    np.savez_compressed(out/'data/network.npz', weights=weights, gSmall=gs[0], gLarge=gs[1],
                        cellCenterOriginXZ=lower+.5, stepWu=1.)
    record.update({'lowerEdgesXZ': lower.tolist(), 'shapeZX': list(map(int, shape)),
                   'gaussianBoundary': 'constant zero; >=32wu guard; truncate=4',
                   'order': 'splat -> gaussian -> g=1-exp(-S/S_line) -> bilinear to each H0 grid'})
    return gs, lower+.5, record


def load_surfaces(sampling):
    n, step = sampling['size'], sampling['stepWu']
    ix, iz = sampling['gridIntegerOrigin']
    x, z = np.meshgrid((ix+np.arange(n))*step, (iz+np.arange(n))*step)
    full = {'name': 'full', 'step': step, 'points': np.stack((x, z), axis=-1)}
    for key, name in [('H0', 'carrier-height'), ('strong', 'strong-height')]:
        full[key] = np.fromfile(FROZEN/(name+'.f64be'), dtype='>f8').astype(float).reshape(n, n)
    result = [full]
    for group in ['NW', 'SE']:
        choices = [PREVIOUS/fresh/'data'/(group+'.npz') for fresh in ['fresh-01', 'fresh-02']]
        path = next((p for p in choices if p.is_file()), None)
        assert path is not None, 'Stored fine grid missing; use approved OldRouteL0Probe fallback explicitly'
        with np.load(path) as data:
            grid = {'name': group, 'step': .25, 'points': data['xz'].reshape(321, 321, 2).copy(),
                    'H0': data['H0'].reshape(321, 321).copy(),
                    'strong': data['strong'].reshape(321, 321).copy(),
                    'source': str(path.relative_to(ROOT)), 'sourceSha256': sha(path)}
        assert np.allclose(np.diff(grid['points'][:, :, 0], axis=1), .25, atol=1e-12, rtol=0)
        assert np.allclose(np.diff(grid['points'][:, :, 1], axis=0), .25, atol=1e-12, rtol=0)
        result.append(grid)
    return result


def decompose(grid, gs, lower):
    h0, step, points = grid['H0'], grid['step'], grid['points']
    before = h0.tobytes()
    base = gaussian_filter(h0, sigma=BASE_SIGMA/step, mode='nearest', truncate=TRUNCATE)
    detail = h0-base
    radius = int(np.ceil(40./step))
    relief = maximum_filter(h0, size=2*radius+1, mode='nearest')
    relief -= minimum_filter(h0, size=2*radius+1, mode='nearest')
    p30, relief_ref, p70 = np.quantile(relief, [.3, .6, .7])
    lh = maximum_filter(h0, size=51, mode='nearest')-h0
    lh_ref = float(np.quantile(lh, .7))
    assert p70 > p30 and relief_ref > 0 and lh_ref > 0
    width = np.clip((relief-p30)/(p70-p30), 0., 1.)
    f_height = np.clip(lh/lh_ref, 0., 1.)**.8
    scale = .45+.55*np.clip(relief/relief_ref, 0., 1.)
    small, large = [bilinear(g, lower, 1., points).reshape(h0.shape) for g in gs]
    g = (1-width)*small+width*large
    d = DEPTH*f_height*scale*g
    retention = 1-.70*g
    candidate = base-d+detail*retention
    reconstruction_error = float(np.max(np.abs(base+detail-h0)))
    # Preserve signed zero and exact bits outside the network; formula still verified below.
    zero = g == 0
    raw_zero_error = float(np.max(np.abs(candidate[zero]-h0[zero]))) if zero.any() else 0.
    candidate[zero] = h0[zero]
    assert reconstruction_error < 1e-12 and raw_zero_error < 1e-12
    assert candidate[zero].tobytes() == h0[zero].tobytes() and h0.tobytes() == before
    assert np.isfinite(candidate).all() and (d >= 0).all() and (d <= DEPTH).all()
    grid.update({'base': base, 'detail': detail, 'relief': relief, 'Lh': lh,
                 'w_width': width, 'f_height': f_height, 'relief_scale': scale,
                 'g_small': small, 'g_large': large, 'g': g, 'D': d,
                 'retention': retention, 'candidate': candidate})
    return {'baseSigmaPixels': BASE_SIGMA/step, 'reliefSizePixels': 2*radius+1,
            'reliefActualRadiusWu': radius*step, 'reliefP30H': float(p30), 'reliefP70H': float(p70),
            'reliefRefP60H': float(relief_ref), 'LhSizePixels': 51, 'LhHalfWidthWu': 25*step,
            'LhRefP70H': lh_ref, 'referenceDomain': 'each original grid independently',
            'reconstructionMaxErrorH': reconstruction_error,
            'reconstructionBitwise': (base+detail).tobytes() == before,
            'zeroGCount': int(zero.sum()), 'rawZeroGMaxErrorH': raw_zero_error,
            'zeroGOutputBitwiseH0': True, 'H0UnchangedBitwise': True,
            'baseBoundary': 'nearest; finite original grid only; no coarse interpolation or resampling',
            'baseFullKernelInteriorHalfWidthWu': (h0.shape[0]-1)*step/2-24.,
            'reliefCaveat': 'square max-min range proxy, NOT literal disk P90-P10'}


def equivalent_widths(grid, distances, profiles):
    """Isolated reference width at g=.1; do not mislabel density overlap as channel width."""
    ws = np.linspace(0., 1., 1001)
    widths = []
    for w in ws:
        profile = (1-w)*profiles[0]+w*profiles[1]
        at = int(np.flatnonzero(profile <= .1)[0])
        frac = (profile[at-1]-.1)/(profile[at-1]-profile[at])
        widths.append(float(distances[at-1]+frac*(distances[at]-distances[at-1])))
    actual = np.interp(grid['w_width'], ws, widths)
    bins = {}
    for name, low, high in [('low', 0., 1/3), ('middle', 1/3, 2/3), ('high', 2/3, 1.00001)]:
        mask = (grid['w_width'] >= low) & (grid['w_width'] < high)
        bins[name] = {'count': int(mask.sum()), 'fraction': float(mask.mean()),
                      'wWidth': distribution(grid['w_width'][mask]),
                      'equivalentHalfWidthWu': distribution(actual[mask])}
    return {'definition': 'isolated 200wu line first g_blend=.1 crossing, frozen local w_width; '
                          'NOT observed network half-width (density and junctions broaden it)',
            'thresholdOfLocalDepthCap': .1, 'bins': bins,
            'w0HalfWidthWu': widths[0], 'w1HalfWidthWu': widths[-1]}


def statistics(grid, parameters, distances, profiles):
    d = grid['D']*Y
    change = (grid['H0']-grid['candidate'])*Y
    detail_change = .7*grid['g']*grid['detail']*Y
    old = (grid['H0']-grid['strong'])*Y
    return {'parameters': parameters, 'pointCount': int(d.size), 'D_Y': distribution(d),
            'D_H': distribution(grid['D']), 'DPositiveCoverage': float((d > 0).mean()),
            'activeD_Y': distribution(d[d > 0]), 'actualLoweringY': distribution(change),
            'detailSuppressionContributionY': distribution(detail_change),
            'raisedFraction': float((change < -1e-12).mean()),
            'detailRetention': distribution(grid['retention']),
            'exact100PercentRetentionFraction': float((grid['retention'] == 1.).mean()),
            'fHeight': distribution(grid['f_height']), 'wWidth': distribution(grid['w_width']),
            'g': distribution(grid['g']), 'equivalentWidths': equivalent_widths(grid, distances, profiles),
            'intensity': {'DMaxOverOld28_37Y': float(d.max()/28.37),
                          'actualMaxLoweringOverOld28_37Y': float(change.max()/28.37),
                          'meanAbsoluteChangeOverOld': float(np.abs(change).mean()/old.mean()),
                          'oldStrongDepthY': distribution(old)},
            'summits': {'LhZeroCount': int((grid['Lh'] == 0).sum()),
                        'actualChangeAtLhZeroY': distribution(change[grid['Lh'] == 0]),
                        'note': 'f_height suppresses D only, not the detail attenuation; ridge preservation '
                                'is guaranteed only where g=0, NOT everywhere Lh=0.'}}


def save_grid(out, grid):
    directory = out/'data'/grid['name']
    directory.mkdir()
    arrays = {key: value for key, value in grid.items() if isinstance(value, np.ndarray)}
    for key, array in arrays.items():
        if key != 'points':
            np.asarray(array, dtype='>f8').tofile(directory/(key+'.f64be'))
    np.savez_compressed(directory/'fields.npz', **arrays, stepWu=grid['step'])
    return directory


def synthetic_check(out):
    directory = out/'data/synthetic-zero'
    directory.mkdir()
    h0 = np.array([0., -.0, .2, -.3, 1e-10, 1.23456789])
    base = np.array([.01, -.01, .17, -.29, .001, 1.2])
    detail = h0-base
    g = np.zeros_like(h0)
    candidate = base+detail
    error = float(np.max(np.abs(candidate-h0)))
    assert error < 1e-12
    candidate[g == 0] = h0[g == 0]
    for name, data in dict(H0=h0, base=base, detail=detail, g=g, D=g, candidate=candidate,
                           f_height=np.ones_like(g), relief_scale=np.ones_like(g)).items():
        data.astype('>f8').tofile(directory/(name+'.f64be'))
    return directory, {'count': len(h0), 'includesSignedZero': True, 'maxRawErrorH': error,
                       'exactOutputBits': candidate.tobytes() == h0.tobytes()}


def java_verify(out, directories):
    classes = out/'classes'
    classes.mkdir()
    commands = [['javac', '--release', '17', '-encoding', 'UTF-8', '-proc:none', '-Xlint:all',
                 '-Werror', '-d', str(classes), str(JAVA)],
                ['java', '-cp', str(classes),
                 'com.geogenesis.worldgen.terrain.morph.DecomposedCarveProbe',
                 *[str(p) for p in directories]]]
    records = []
    for index, args in enumerate(commands):
        result = subprocess.run(args, cwd=ROOT, stdout=subprocess.PIPE, stderr=subprocess.STDOUT)
        (out/f'java-{index}.log').write_bytes(result.stdout)
        print(result.stdout.decode('utf-8', errors='replace'), flush=True)
        assert result.returncode == 0, 'Independent Java verification failed'
        records.append({'argv': args, 'exitCode': result.returncode})
    return records


def rgb(h, step, cmap):
    gz, gx = np.gradient(h*Y, step*2.)
    dot = (-gx*LIGHT[0]-gz*LIGHT[1]+LIGHT[2])/np.sqrt(1+gx*gx+gz*gz)
    return cmap(Normalize(-.35, 1.2, clip=True)(.02+1.5*h))[..., :3]*(.65+.35*np.maximum(0, dot))[..., None]


def visible_routes(routes, extent):
    return [r for r in routes if r[:, 0].max() >= extent[0] and r[:, 0].min() <= extent[1]
            and r[:, 1].max() >= extent[3] and r[:, 1].min() <= extent[2]]


def extent_of(grid):
    x0, z0 = grid['points'][0, 0]
    x1, z1 = grid['points'][-1, -1]
    half = grid['step']/2
    return [x0-half, x1+half, z1+half, z0-half]


def panel(ax, image, extent, title, routes, lines):
    ax.imshow(image, extent=extent, origin='upper', interpolation='nearest')
    if lines:
        ax.add_collection(LineCollection(routes, colors='white', linewidths=.35, alpha=.30))
    ax.set(xlim=extent[:2], ylim=extent[2:], title=title, xlabel='世界 x（wu）', ylabel='世界 z（wu）')


def finish(fig, out, name, title, note):
    fig.suptitle(title, fontsize=19)
    fig.text(.018, .014, FOOTER+'\n'+note, fontsize=10)
    fig.tight_layout(rect=(0., .095, 1., .94))
    fig.savefig(out/name, dpi=160)
    plt.close(fig)


def render_grid(out, grid, routes, cmap, prefix):
    extent = extent_of(grid)
    visible = visible_routes(routes, extent)
    images = [rgb(grid[key], grid['step'], cmap) for key in ['H0', 'strong', 'candidate']]
    note = ('原采样网格；未重采/重建路线；边界为有限窗裁切。' if prefix == 'full' else
            '0.25wu原细采独立分解；边缘24wu内base受nearest边界影响；40wu起伏窗口在近景大部被截断。')
    for lines in [False, True]:
        fig, axes = plt.subplots(1, 3, figsize=(27, 10.5))
        for ax, image, title in zip(axes, images, LABELS):
            panel(ax, image, extent, title, visible, lines)
        suffix = '-lines' if lines else ''
        finish(fig, out, f'{prefix}-3arms{suffix}.png', f'{grid["name"]} 同窗三臂'+suffix, note)
    if prefix == 'full':
        for name, image, title in zip(['original', 'strong', 'candidate'], images, LABELS):
            fig, ax = plt.subplots(figsize=(11, 12))
            panel(ax, image, extent, title, visible, False)
            finish(fig, out, name+'.png', title, note)
    return images


def render_junction(out, grid, routes, cmap):
    extent = extent_of(grid)
    center = grid['points'][160, 160]
    visible = visible_routes(routes, extent)
    fig, axes = plt.subplots(1, 3, figsize=(27, 10.5))
    for ax, key, title in zip(axes, ['H0', 'strong', 'candidate'], LABELS):
        panel(ax, rgb(grid[key], grid['step'], cmap), extent, title, visible, True)
        ax.set(xlim=(center[0]-25, center[0]+25), ylim=(center[1]+25, center[1]-25))
    finish(fig, out, 'fixed-zoom-junction.png', '固定 NW ±25wu｜白线=未改旧骨架',
           '固定旧NW中心，不按结果选点；本图外圈base仍有有限网格边界影响，未补采halo。')


def crossing_width(profile, center, step, threshold):
    sides = []
    for values in [profile[center::-1], profile[center:]]:
        hits = np.flatnonzero(values <= threshold)
        if not len(hits):
            sides.append({'distanceWu': None, 'censoredBeyondWu': (len(values)-1)*step})
        else:
            at = int(hits[0])
            distance = 0. if at == 0 else (at-1+(values[at-1]-threshold)/
                                           (values[at-1]-values[at]))*step
            sides.append({'distanceWu': float(distance), 'censoredBeyondWu': None})
    return sides


def section_on_row(grid, routes, anchor):
    """Original horizontal grid row through a fixed anchor; nearest x route crossing is the center."""
    points, step = grid['points'], grid['step']
    row = int(np.argmin(np.abs(points[:, 0, 1]-anchor[1])))
    z = points[row, 0, 1]
    crossings = []
    for route in routes:
        a, b = route[:-1], route[1:]
        mask = ((a[:, 1] <= z) & (b[:, 1] > z)) | ((b[:, 1] <= z) & (a[:, 1] > z))
        a, b = a[mask], b[mask]
        if len(a):
            xs = a[:, 0]+(z-a[:, 1])/(b[:, 1]-a[:, 1])*(b[:, 0]-a[:, 0])
            crossings.extend(xs[(xs >= points[row, 0, 0]) & (xs <= points[row, -1, 0])])
    assert crossings
    route_x = min(crossings, key=lambda x: abs(x-anchor[0]))
    center = int(np.argmin(np.abs(points[row, :, 0]-route_x)))
    half = int(np.floor(20/step))
    left, right = max(0, center-half), min(points.shape[1], center+half+1)
    data = {k: grid[k][row, left:right] for k in ['H0', 'strong', 'candidate', 'D', 'g']}
    at = center-left
    offsets = (np.arange(left, right)-center)*step
    actual = (data['H0']-data['candidate'])*Y
    record = {'grid': grid['name'], 'stepWu': step, 'orientation': 'native x row, not route-normal',
              'anchorXZwu': list(map(float, anchor)), 'centerXZwu': points[row, center].tolist(),
              'skeletonCrossingXwu': float(route_x), 'centerOffsetFromSkeletonWu': float(points[row, center, 0]-route_x),
              'centerD_Y': float(data['D'][at]*Y), 'centerActualLoweringY': float(actual[at]),
              'centerOldStrongLoweringY': float((data['H0'][at]-data['strong'][at])*Y),
              'leftRightG01HalfWidth': crossing_width(data['g'], at, step, .1),
              'leftRightActualHalfMaximumWidth': crossing_width(actual, at, step, actual[at]/2),
              'note': 'First outward crossing; branches may recut. Null means no crossing in sampled span. '
                      'Horizontal obliquity can overestimate route-normal width; middle row is coarse, not fake fine.'}
    return offsets, data, record


def render_sections(out, grids, routes):
    fig, axes = plt.subplots(3, 1, figsize=(15, 14))
    records = []
    for ax, grid in zip(axes, [grids[1], grids[0], grids[2]]):
        n = grid['H0'].shape[0]
        offsets, data, record = section_on_row(grid, routes, grid['points'][n//2, n//2])
        records.append(record)
        for key, title, color in zip(['H0', 'strong', 'candidate'], LABELS, ['#435669', '#bf633d', '#168d83']):
            ax.plot(offsets, data[key]*Y, label=title, color=color, lw=1.6)
        ax.axvline(0, color='gray', lw=.7, ls=':')
        ax.set(title=f'{grid["name"]} 固定锚点最近骨架过线｜原网格横剖 step={grid["step"]:.4f}wu',
               xlabel='横向距离（wu）', ylabel='高度（Yproxy）')
        ax.grid(alpha=.2)
        ax.legend()
        np.savez_compressed(out/'data'/('section-'+grid['name']+'.npz'), offsetsWu=offsets, **data)
    finish(fig, out, 'sections.png', '三条原网格横剖｜NW / 全窗中心 / SE',
           '未插值H0、未补采；不是旧路线法线剖面；全窗中心步长2.3828125wu，其余0.25wu。')
    return records


def limitations():
    return ['Diagnostic bounded candidate, NOT production or Minecraft acceptance; no route changes.',
            '6wu means 2.518 pixels on full grid and 24 pixels on fine grid, not 12 pixels.',
            'Lh size=51 pixels literally on each grid: radius 59.57wu full versus 6.25wu fine; '
            'same stated discrete rule is not physically scale-invariant.',
            'P30/P70/P60 and Lh P70 recomputed per original grid; fine views are local experiments, '
            'not crops of one globally identical candidate field.',
            'Only 80wu fine windows exist. Base support radius=24wu; relief radius=40wu; '
            'nearest boundary truncates neighborhoods. No halo was sampled or borrowed from coarse H0.',
            'max-min relief is only the prior range proxy, not exact disk P90-P10; extrema switches, '
            'clipping, sublinear .8 power and bilinear density interpolation can still introduce creases.',
            'Detail removal can raise low residuals or deepen positive residuals beyond D; D<=16Y '
            'does NOT guarantee total lowering<=16Y or preserve all summits.',
            'Only g=0 guarantees unchanged terrain. Dense networks attenuate detail on intervening slopes '
            'and ridges; no extra ridge mask is in the prescribed formula.',
            'Equivalent widths isolate w_width effect; real network overlap/junction width differs.',
            'Three native horizontal sections retain original H0 exactly; not old arbitrary-angle '
            '161-point sections, which cannot supply the required 2D local Gaussian neighborhood.']


def write_report(out, stats, sections, preservation):
    lines = ['Fixed decomposed carve: offline trial, no tuning sweep, no Rune, no production wiring.',
             'All statistics and caveats: meta.json; hash manifest: locked-inputs.json.', '']
    for name, item in stats.items():
        lines += [name, 'D Y: '+json.dumps(item['D_Y']),
                  'D>0 coverage: '+str(item['DPositiveCoverage']),
                  'Actual H0-Hnew Y: '+json.dumps(item['actualLoweringY']),
                  'Detail retention: '+json.dumps(item['detailRetention']),
                  'f_height: '+json.dumps(item['fHeight']), 'w_width: '+json.dumps(item['wWidth']),
                  'Width bins: '+json.dumps(item['equivalentWidths']['bins']),
                  'Intensity: '+json.dumps(item['intensity']),
                  'Reconstruction: '+json.dumps(item['parameters']), '']
    lines += ['Sections: '+json.dumps(sections, ensure_ascii=False),
              'Preservation: '+json.dumps(preservation), '', 'Limitations:', *limitations(),
              '', 'Visual acceptance: NOT claimed. Review fixed PNGs separately.']
    (out/'report.txt').write_text('\n'.join(lines)+'\n', encoding='utf-8')


def main():
    started = time.perf_counter()
    out = fresh_output()
    (out/'data').mkdir()
    print('Fresh output:', out, flush=True)
    locked = snapshot(out)
    save_json(out/'locked-inputs.json', locked)
    main_count = sum(p.is_file() for p in (ROOT/'src/main').rglob('*'))
    assert main_count == 222
    sampling = json.loads((FROZEN/'sampling.json').read_text(encoding='utf-8'))
    assert sampling['size'] == 769 and sampling['HS'] == 2
    routes, grids = load_routes(), load_surfaces(sampling)
    references, distances, profiles, line_record = isolated_reference(out)
    gs, lower, network_record = network_fields(out, routes, np.array(sampling['originWu']),
                                               sampling['spanWu'], references)
    stats, directories = {}, []
    for grid in grids:
        print('Decompose original grid:', grid['name'], flush=True)
        parameters = decompose(grid, gs, lower)
        stats[grid['name']] = statistics(grid, parameters, distances, profiles)
        directories.append(save_grid(out, grid))
    synthetic_directory, zero_check = synthetic_check(out)
    commands = java_verify(out, directories+[synthetic_directory])
    plt.rcParams['font.sans-serif'] = ['Microsoft YaHei', 'SimHei', 'DejaVu Sans']
    plt.rcParams['axes.unicode_minus'] = False
    colors = np.loadtxt(PALETTE, delimiter=',', skiprows=1)
    cmap = ListedColormap(colors[:, 1:]/255.)
    for grid, prefix in zip(grids, ['full', 'closeup-1', 'closeup-2']):
        print('Render:', prefix, flush=True)
        render_grid(out, grid, routes, cmap, prefix)
    render_junction(out, grids[1], routes, cmap)
    sections = render_sections(out, grids, routes)
    after = snapshot(out)
    assert locked == after, 'Protected old files changed; do not report preservation PASS'
    preservation = {'protectedOldFileCount': len(locked), 'allProtectedHashesUnchanged': True,
                    'mainFilesUnchanged': main_count, 'oldRoutesSha256': sha(FROZEN/'routes.csv'),
                    'terrainResampled': False, 'routesRebuilt': False, 'productionWiring': False}
    record = {'scope': 'fixed diagnostic decomposition only; no tuning, no production, no acceptance claim',
              'sampling': sampling, 'sigmaBaseWu': BASE_SIGMA, 'sigmaNetworkWu': SIGMAS,
              'depthCapH': DEPTH, 'depthCapY': 16., 'absoluteSafetyCapH': .08,
              'formula': 'base-D+detail*(1-.70*g_blend)', 'splat': network_record,
              'isolatedLineReference': line_record, 'grids': stats, 'sections': sections,
              'syntheticZeroGCheck': zero_check, 'preservation': preservation, 'javaChecks': commands,
              'fineSources': [{k: g[k] for k in ['name', 'source', 'sourceSha256']} for g in grids[1:]],
              'render': {'e': '.02+1.5H', 'colorRange': [-.35, 1.2], 'light': [-.5, -.3, 1.],
                         'shade': '.65+.35*max(0,dot)', 'HS': 2, 'Yproxy': Y,
                         'normalization': False, 'paletteSha256': sha(PALETTE)},
              'versions': {'numpy': np.__version__, 'scipy': scipy.__version__},
              'sourceHashes': {str(p.relative_to(ROOT)): sha(p) for p in [JAVA, Path(__file__).resolve()]},
              'limitations': limitations(), 'morphologicalAcceptance': False,
              'elapsedSeconds': time.perf_counter()-started}
    write_report(out, stats, sections, preservation)
    record['outputHashes'] = {p.relative_to(out).as_posix(): sha(p) for p in out.rglob('*') if p.is_file()}
    save_json(out/'meta.json', record)
    print(json.dumps({'output': str(out), 'fullD_Y': stats['full']['D_Y'],
                      'actualLoweringY': stats['full']['actualLoweringY'],
                      'preservation': preservation}, ensure_ascii=False, indent=2), flush=True)


if __name__ == '__main__':
    main()
