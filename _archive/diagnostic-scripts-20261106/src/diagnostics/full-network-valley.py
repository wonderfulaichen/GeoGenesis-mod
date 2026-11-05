"""V4: one offline valley trial; reuse Wg, V3 adaptation fields and all Java samples."""
import csv
import hashlib
import json
from pathlib import Path
import subprocess
import time

import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.collections import LineCollection
from matplotlib.colors import ListedColormap, Normalize
import numpy as np
from scipy.ndimage import gaussian_filter, maximum_filter, minimum_filter, map_coordinates

ROOT = Path(__file__).resolve().parents[2]
REPO = ROOT.parent
FROZEN = ROOT / 'build/strong-window-erosion-20261104-final/data'
PREVIOUS = ROOT / 'build/full-network-valley-20261104'
V2 = PREVIOUS / 'fresh-01'
V3 = PREVIOUS / 'fresh-02'
PALETTE = ROOT / 'build/fast-erosion-scale-only-20261103-final/data/project-palette.csv'
YPROXY, GRID_STEP, SPLAT_STEP, SIGMA, TRUNCATE, PADDING = 354.66, 1., .5, 4., 4., 30.
DEPTH = 24. / YPROXY
HEIGHT_SIZE, HEIGHT_POWER = 51, .8
RELIEF_RADIUS, ZERO_RELATIVE = 40., 1e-12
STEP_FINE, N_FINE = .25, 321
LIGHT = np.array([-.5, -.3, 1.])
LIGHT /= np.linalg.norm(LIGHT)
TITLES = ['原始地形 H0', '旧强下切', '全网谷坡 v4（试版）']
FOOTER = ('高斯σ=4wu→饱和；基准24Y×局部落差因子×起伏尺度，绝对上限0.08H；仅减高度、不改路线。\n'
          '1wu场双线性采样；HS=2；Yproxy=354.66；固定色带[-.35,1.2]，同光照、无逐图归一化。')


def sha(path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


def save_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + '\n',
                    encoding='utf-8')


def snapshot():
    names = subprocess.check_output(['git', 'ls-files', '-c', '-o', '--exclude-standard', '-z'],
                                    cwd=REPO).decode('utf-8').split('\0')
    paths = {REPO / name for name in names if name and (REPO / name).is_file()}
    paths.update(p for p in (ROOT / 'src').rglob('*') if p.is_file())
    paths.update(p for p in FROZEN.rglob('*') if p.is_file())
    # Lock all v1 outputs and all older fresh outputs, but not this new run's directory.
    paths.update(p for p in PREVIOUS.rglob('*') if p.is_file() and OUTPUT not in p.parents)
    paths.add(PALETTE)
    paths.discard(Path(__file__).resolve())
    return {p.relative_to(REPO).as_posix(): sha(p) for p in sorted(paths)}


def fresh_output():
    assert (PREVIOUS / 'meta.json').is_file(), 'V1 fine samples required; no Java resampling'
    for index in range(1, 10000):
        out = PREVIOUS / f'fresh-{index:02d}'
        if not out.exists():
            out.mkdir()
            return out
    raise FileExistsError('No fresh output directory')


def load_network():
    with (FROZEN / 'traces.csv').open(newline='', encoding='utf-8') as stream:
        statuses = {row['id']: row['status'] for row in csv.DictReader(stream)}
    routes, ids, points = [], [], []
    current = None
    with (FROZEN / 'routes.csv').open(newline='', encoding='utf-8') as stream:
        for row in csv.DictReader(stream):
            if row['trace'] != current:
                if current is not None:
                    routes.append(np.asarray(points))
                current, points = row['trace'], []
                ids.append(current)
            assert int(row['vertex']) == len(points)
            points.append([float(row['x']), float(row['z'])])
    routes.append(np.asarray(points))
    assert len(routes) == len(ids) == len(statuses) == 8100
    assert list(statuses) == ids and all(len(route) >= 2 for route in routes)
    segments = sum(len(route)-1 for route in routes)
    assert segments == 471997 and all(np.isfinite(route).all() for route in routes)
    return routes, ids, statuses, segments


def splat(routes, lower, shape):
    """Visit every segment; uniform .5wu arclength spacing with phase carried over vertices."""
    weights = np.zeros(shape, dtype=np.float64)
    total, deposited, processed_segments = 0, 0, 0
    for route in routes:
        vectors = np.diff(route, axis=0)
        lengths = np.linalg.norm(vectors, axis=1)
        arc = np.r_[0., np.cumsum(lengths)]
        assert (lengths > 0).all()
        processed_segments += len(lengths)
        # Include final endpoint exactly once, avoiding segment-boundary double-counting.
        targets = np.r_[np.arange(0., arc[-1], SPLAT_STEP), arc[-1]]
        indices = np.clip(np.searchsorted(arc, targets, side='right')-1, 0, len(lengths)-1)
        t = (targets - arc[indices]) / lengths[indices]
        samples = route[indices] + t[:, None]*vectors[indices]
        cells = np.floor((samples - lower)/GRID_STEP).astype(np.int64)
        inside = ((cells >= 0).all(axis=1) & (cells[:, 0] < shape[1]) &
                  (cells[:, 1] < shape[0]))
        np.add.at(weights, (cells[inside, 1], cells[inside, 0]), 1.)
        total += len(samples)
        deposited += int(inside.sum())
    assert float(weights.sum()) == deposited
    return weights, {'allRoutesProcessed': len(routes), 'allSegmentsProcessed': processed_segments,
                     'generatedSamples': total, 'depositedSamples': deposited,
                     'samplesOutsideExpandedGrid': total-deposited,
                     'splatRule': 'Uniform route arclength 0,.5,1,... plus final endpoint once; '
                                  'phase carried across every original segment; no vertex duplicates; '
                                  'cell=floor((world-lower)/1); each sample adds exactly 1.'}


def bilinear(array, lower_center, spacing, points):
    xy = (np.asarray(points).reshape(-1, 2) - lower_center) / spacing
    assert ((xy >= -1e-10).all() and (xy[:, 0] <= array.shape[1]-1+1e-10).all() and
            (xy[:, 1] <= array.shape[0]-1+1e-10).all()), 'No extrapolation allowed'
    xy[:, 0] = np.clip(xy[:, 0], 0, array.shape[1]-1)
    xy[:, 1] = np.clip(xy[:, 1], 0, array.shape[0]-1)
    return map_coordinates(array, [xy[:, 1], xy[:, 0]], order=1, prefilter=False, mode='nearest')


def blur(weights):
    return gaussian_filter(weights, sigma=SIGMA/GRID_STEP, truncate=TRUNCATE,
                           mode='constant', cval=0.)


def line_reference(out):
    lower = np.array([-130., -30.])
    path = V2 / 'data/isolated-line.npz'
    with np.load(path) as previous:
        weights = previous['weights'].copy()
    record = {'weightSource': str(path.relative_to(ROOT)), 'weightSourceSha256': sha(path),
              'generatedSamples': 401, 'depositedSamples': 401}
    smooth = blur(weights)
    s_line = float(smooth[30].max())
    assert s_line > 0
    # The line falls into cell z=[0,1), represented by center z=.5.
    distances = np.arange(0, 30.0001, .01)
    centerline = np.array([.5, .5])
    positive = np.column_stack((np.full(len(distances), .5), .5+distances))
    negative = np.column_stack((np.full(len(distances), .5), .5-distances))
    ratios = [-np.expm1(-bilinear(smooth, lower+.5, 1., p)/s_line) for p in (negative, positive)]
    widths = []
    for ratio in ratios:
        at = int(np.flatnonzero(ratio <= .1)[0])
        fraction = (.1-ratio[at-1])/(ratio[at]-ratio[at-1])
        widths.append(float(distances[at-1]+fraction*.01))
    np.savez_compressed(out / 'data/isolated-line.npz', weights=weights, S=smooth,
                        distanceWu=distances, negativeRatio=ratios[0], positiveRatio=ratios[1])
    record.update({'lengthWu': 200., 'orientation': 'x axis, z=0; cell-center reference z=.5',
                   'shapeZX': [61, 261], 'S_line': s_line,
                   'definition': 'maximum on the splatted line row after identical splat and blur',
                   'isolatedCenterDepthFractionOfLocalCap': float(-np.expm1(-1.)),
                   'effectiveHalfWidthAt01LocalCapWu': widths,
                   'untruncatedContinuousGaussianApproxHalfWidthWu':
                       float(SIGMA*np.sqrt(-2*np.log(-np.log(.9)))),
                   'measurement': 'bilinear isolated-line profile, .01wu increments, interpolated .1 crossing',
                   'cellCenterXZ': centerline.tolist()})
    return s_line, record


def quantiles(values):
    return dict(zip(('min', 'P10', 'P50', 'P60', 'P90', 'P99', 'max'),
                    map(float, np.quantile(values, [0, .1, .5, .6, .9, .99, 1]))))


def relief_field(h0, step):
    previous = json.loads((V2 / 'meta.json').read_text(encoding='utf-8'))
    path = V2 / 'data/relief.f64be'
    assert sha(path) == previous['outputHashes']['data/relief.f64be']
    relief = np.fromfile(path, dtype='>f8').astype(float).reshape(h0.shape)
    record = dict(previous['relief'])
    reference = record['relief_refH']
    assert np.quantile(relief, .6) == reference and np.isfinite(relief).all()
    record.update({'reusedFile': str(path.relative_to(ROOT)), 'reusedSha256': sha(path)})
    return relief, reference, record


def height_field(h0, step):
    previous = json.loads((V3 / 'meta.json').read_text(encoding='utf-8'))
    arrays = []
    for name in ('local-high', 'Lh', 'f-height'):
        path = V3 / 'data' / (name+'.f64be')
        assert sha(path) == previous['outputHashes']['data/'+name+'.f64be']
        arrays.append(np.fromfile(path, dtype='>f8').astype(float).reshape(h0.shape))
    local_high, lh, factor = arrays
    reference = float(np.quantile(lh, .7))
    assert reference == previous['heightAdaptation']['Lh_refH']
    assert np.array_equal(local_high-h0, lh)
    assert np.array_equal(factor, np.clip(lh/reference, 0., 1.)**HEIGHT_POWER)
    assert reference > 0 and (lh >= 0).all() and np.isfinite(lh).all()
    record = {'formula': 'Lh=maximum_filter(H0,size=51,mode=nearest)-H0; '
                         'f_height=clip(Lh/Lh_ref,0,1)**.8',
              'referenceRule': 'full 769-square Lh P70, numpy linear quantile',
              'Lh_refH': reference, 'Lh_refYproxy': reference*YPROXY,
              'sizePixels': HEIGHT_SIZE, 'footprint': 'square, same nearest edge policy as relief',
              'halfWidthWu': (HEIGHT_SIZE//2)*step, 'power': HEIGHT_POWER,
              'LhH': quantiles(lh), 'fHeight': quantiles(factor),
              'zeroFactorFraction': float((factor == 0).mean()),
              'factorAtOneFraction': float((factor == 1).mean()),
              'fineSampling': 'bilinear global local-high envelope, subtract actual fine H0; '
                              'clamp negative residual to zero, same global Lh_ref and exponent',
              'interpretation': 'Local elevation-below-maximum proxy, not routed downstream distance.',
              'V3FieldsReusedBitwise': True, 'source': str(V3.relative_to(ROOT))}
    return local_high, lh, reference, factor, record


def summit_statistics(h0, depth, lh, reference):
    local_max = lh == 0
    near_summit = lh <= .1*reference
    highest = h0 >= np.quantile(h0, .9)
    total = float(depth.sum())
    result = {}
    for name, mask in [('exactLocalMaximum51', local_max),
                       ('within01LhRefOfLocalMaximum', near_summit), ('globalH0Top10Percent', highest)]:
        result[name] = {'definition': name, 'pointCount': int(mask.sum()),
                        'sampleFraction': float(mask.mean()),
                        'positiveDepthFractionWithinRegion': float((depth[mask] > 0).mean()),
                        'shareOfWholeWindowDepthSum': float(depth[mask].sum()/total),
                        'depthYproxy': quantiles(depth[mask]*YPROXY)}
    assert (depth[local_max] == 0).all()
    return result


def valley(h0, smooth, relief, reference, s_line, f_height):
    scale = .45 + .55*np.clip(relief/reference, 0., 1.)
    local_cap = np.minimum(.08, DEPTH*f_height*scale)
    ratio = -np.expm1(-smooth/s_line)
    # Explicit numerically-zero rule, not an unreported support or depth blur.
    ratio = np.where(smooth <= ZERO_RELATIVE*s_line, 0., ratio)
    depth = local_cap*ratio
    candidate = h0.copy()
    active = depth > 0
    candidate[active] = h0[active] - depth[active]
    return depth, candidate, local_cap, scale


def check_surfaces(h0, strong, old_depth, smooth, depth, candidate, local_cap, s_line, before):
    zero = smooth <= ZERO_RELATIVE*s_line
    checks = {'finite': all(np.isfinite(a).all() for a in
                           (h0, strong, old_depth, smooth, depth, candidate, local_cap)),
              'H0BitwiseUnchanged': h0.tobytes() == before,
              'DWithinZeroTo008H': bool(((depth >= 0) & (depth <= .08)).all()),
              'DNotAboveLocalCap': bool((depth <= local_cap).all()),
              'candidateNotAboveH0': bool((candidate <= h0).all()),
              'nearZeroSBitwiseH0': candidate[zero].tobytes() == h0[zero].tobytes()}
    assert all(checks.values()), checks
    active = depth > 0
    statistics = {'pointCount': int(h0.size), 'activePointCount': int(active.sum()),
                  'coverageFraction': float(active.mean()),
                  'bitwiseChangedFraction': float((candidate != h0).mean()),
                  'depthH': quantiles(depth), 'depthYproxy': quantiles(depth*YPROXY),
                  'activeMedianDepthYproxy': float(np.median(depth[active])*YPROXY) if active.any() else 0.,
                  'nearZeroSPointCount': int(zero.sum()),
                  'newPointsOutsideOldPositiveSupport': int((active & (old_depth == 0)).sum()),
                  'zeroLocalCapCount': int((local_cap == 0).sum()),
                  'depthFractionOfLocalCap': quantiles(np.divide(
                      depth, local_cap, out=np.zeros_like(depth), where=local_cap > 0))}
    return {'checks': checks, 'statistics': statistics}


def load_fine():
    previous = json.loads((PREVIOUS / 'meta.json').read_text(encoding='utf-8'))
    definitions = []
    for item in previous['fineGridsAndSections']:
        definition = dict(item['definition'])
        path = PREVIOUS / 'data' / (definition['group']+'.npz')
        assert sha(path) == previous['outputHashes'][path.relative_to(PREVIOUS).as_posix()]
        with np.load(path) as stored:
            definition.update({name: stored[key].copy() for name, key in
                               [('points', 'xz'), ('H0', 'H0'), ('strong', 'strong'), ('oldDepth', 'oldDepth')]})
        definition['reusedFile'] = str(path.relative_to(ROOT))
        definition['reusedSha256'] = sha(path)
        definitions.append(definition)
    assert sum(len(d['H0']) for d in definitions) == 206565
    return definitions[:2], definitions[2:]


def terrain_rgb(h, step, cmap):
    gz, gx = np.gradient(h * YPROXY, step * 2.)
    dot = (-gx * LIGHT[0] - gz * LIGHT[1] + LIGHT[2]) / np.sqrt(1. + gx*gx + gz*gz)
    base = cmap(Normalize(-.35, 1.2, clip=True)(.02 + 1.5*h))[..., :3]
    return base * (.65 + .35*np.maximum(dot, 0.))[..., None]


def draw_panel(ax, rgb, extent, title, routes, lines):
    ax.imshow(rgb, extent=extent, origin='upper', interpolation='nearest')
    if lines:
        ax.add_collection(LineCollection(routes, colors='white', linewidths=.35, alpha=.30,
                                         antialiaseds=True, zorder=3))
    ax.set_xlim(extent[:2])
    ax.set_ylim(extent[2:])
    ax.set_title(title, fontsize=17)
    ax.set_xlabel('世界 x（wu）')
    ax.set_ylabel('世界 z（wu）')
    ax.tick_params(labelsize=9)


def finish(fig, out, name, title, edge_note):
    fig.suptitle(title, fontsize=20)
    fig.text(.018, .013, FOOTER + '\n' + edge_note, fontsize=10)
    fig.tight_layout(rect=(0., .09, 1., .94))
    fig.savefig(out / name, dpi=160)
    plt.close(fig)


def render_triplet(out, prefix, surfaces, step, origin, routes, cmap, edge_note, singles=False):
    n = surfaces[0].shape[0]
    extent = [origin[0]-step/2, origin[0]+(n-.5)*step,
              origin[1]+(n-.5)*step, origin[1]-step/2]
    visible = [route for route in routes if
               route[:, 0].max() >= extent[0] and route[:, 0].min() <= extent[1] and
               route[:, 1].max() >= extent[3] and route[:, 1].min() <= extent[2]]
    images = [terrain_rgb(h, step, cmap) for h in surfaces]
    for lines in (False, True):
        fig, axes = plt.subplots(1, 3, figsize=(27, 10.5))
        for ax, rgb, title in zip(axes, images, TITLES):
            draw_panel(ax, rgb, extent, title, visible, lines)
        suffix = '-lines' if lines else ''
        title = '全窗网络：原始 / 旧强 / 谷坡v4' if prefix == 'full' else prefix.replace('closeup-', '固定近景 ')
        finish(fig, out, f'{prefix}-3arms{suffix}.png',
               title + ('｜白线为完整旧骨架' if lines else ''), edge_note)
    if singles:
        for name, rgb, title in zip(('original', 'strong', 'candidate'), images, TITLES):
            fig, ax = plt.subplots(figsize=(11, 12))
            draw_panel(ax, rgb, extent, title, visible, False)
            finish(fig, out, f'{name}.png', title + '｜完整同窗', edge_note)


def section_measurements(definition, depth, candidate):
    center = len(depth)//2
    y = depth*YPROXY
    half = center*STEP_FINE
    record = {'anchorLabel': definition['anchorLabel'], 'centerXZwu': definition['centerWu'],
              'centerActualDepthYproxy': float((definition['H0'][center]-candidate[center])*YPROXY),
              'sectionDepthYproxy': quantiles(y), 'sampledHalfWidthWu': half,
              'endpointDepthYproxyLeftRight': [float(y[0]), float(y[-1])],
              'returnDefinition': 'First outward crossing from section center; other branches may '
                                  'cause later recutting, so this is NOT an isolated-valley width.'}
    for threshold, name in [(0., 'exactReturnToH0'), (.1, 'practicalReturnWithin01Y')]:
        sides = []
        for values in (y[center::-1], y[center:]):
            hits = np.flatnonzero(values <= threshold)
            if not len(hits):
                sides.append({'distanceWu': None, 'beyondSampledHalfWidthWu': half})
                continue
            at = int(hits[0])
            distance = at*STEP_FINE
            if at > 0 and threshold > 0:
                distance = (at-1+(values[at-1]-threshold)/(values[at-1]-values[at]))*STEP_FINE
            sides.append({'distanceWu': float(distance), 'beyondSampledHalfWidthWu': None})
        record[name+'LeftRight'] = sides
    return record


def render_sections(out, sections, edge_note):
    fig, axes = plt.subplots(3, 1, figsize=(14, 13))
    colors = ['#435669', '#bf633d', '#168d83']
    for ax, section in zip(axes, sections):
        offsets = np.arange(-80, 81) * STEP_FINE
        for key, label, color in zip(('H0', 'strong', 'candidate'), TITLES, colors):
            ax.plot(offsets, section[key]*YPROXY, label=label, color=color, lw=1.6)
        cx, cz = section['centerWu']
        ax.axvline(0, color='gray', lw=.7, ls=':')
        ax.set(title=f"{section['anchorLabel']}锚点最近骨架横剖｜中心 ({cx:.3f}, {cz:.3f}) wu",
               xlim=(-20, 20), xlabel='沿骨架法线的距离（wu，左右各20）', ylabel='高度（Yproxy）')
        ax.grid(alpha=.2)
        ax.legend(loc='best')
    finish(fig, out, 'sections.png', '三条同位置横剖｜复用 Java 实际细采，不重采', edge_note)


def render_junction(out, grid, routes, cmap):
    fig, axes = plt.subplots(1, 3, figsize=(27, 10.5))
    center = np.array(grid['centerWu'])
    fine_origin = center-40.
    extent = [fine_origin[0]-.125, fine_origin[0]+80.125,
              fine_origin[1]+80.125, fine_origin[1]-.125]
    for ax, key, title in zip(axes, ('H0', 'strong', 'candidate'), TITLES):
        rgb = terrain_rgb(grid[key].reshape(N_FINE, N_FINE), STEP_FINE, cmap)
        draw_panel(ax, rgb, extent, title, routes, True)
        ax.set_xlim(center[0]-25, center[0]+25)
        ax.set_ylim(center[1]+25, center[1]-25)
    finish(fig, out, 'fixed-zoom-junction.png', '固定 NW 中心±25wu｜检视折面与支沟融合',
           f'中心({center[0]:.6f}, {center[1]:.6f})wu；复用0.25wu原细采，未按效果选点；白线为旧骨架。')


def boundary_info(routes, origin, span):
    crossing = 0
    for route in routes:
        inside = ((route >= origin) & (route <= origin+span)).all(axis=1)
        crossing += int(np.count_nonzero(inside[1:] != inside[:-1]))
    return {'segmentsCrossingWindowBoundary': crossing,
            'note': '旧网络穿越显示边界、被裁切，非自然收口；外扩30wu仅使用已有路线，无路线/地形外推。'}


def evaluate_fine(out, definition, smooth, lower_center, relief, reference, origin, step,
                  s_line, local_high, lh_ref):
    before = definition['H0'].tobytes()
    sampled_s = bilinear(smooth, lower_center, GRID_STEP, definition['points'])
    sampled_relief = bilinear(relief, origin, step, definition['points'])
    sampled_high = bilinear(local_high, origin, step, definition['points'])
    raw_lh = sampled_high-definition['H0']
    sampled_lh = np.maximum(raw_lh, 0.)
    f_height = np.clip(sampled_lh/lh_ref, 0., 1.)**HEIGHT_POWER
    with np.load(V3 / 'data' / (definition['group']+'.npz')) as previous:
        assert previous['fHeight'].tobytes() == f_height.tobytes()
        assert previous['relief'].tobytes() == sampled_relief.tobytes()
        assert previous['H0'].tobytes() == before
        previous_scale = previous['scale'].copy()
    depth, candidate, local_cap, scale = valley(definition['H0'], sampled_s, sampled_relief,
                                               reference, s_line, f_height)
    assert previous_scale.tobytes() == scale.tobytes()
    definition['candidate'] = candidate
    result = check_surfaces(definition['H0'], definition['strong'], definition['oldDepth'],
                            sampled_s, depth, candidate, local_cap, s_line, before)
    result['definition'] = {key: value for key, value in definition.items()
                            if key not in ('points', 'H0', 'strong', 'oldDepth', 'candidate')}
    result['scale'] = quantiles(scale)
    result['fHeight'] = quantiles(f_height)
    result['V3FineHeightAndReliefScaleBitwiseIdentical'] = True
    if definition['group'].startswith('section-'):
        result['sectionMeasurements'] = section_measurements(definition, depth, candidate)
    result['negativeLhFromFineEnvelopeInterpolationClampedCount'] = int((raw_lh < 0).sum())
    np.savez_compressed(out / 'data' / (definition['group'] + '.npz'),
                        xz=definition['points'], H0=definition['H0'], strong=definition['strong'],
                        oldDepth=definition['oldDepth'], S=sampled_s, relief=sampled_relief,
                        Lh=sampled_lh, fHeight=f_height, scale=scale,
                        localCap=local_cap, D=depth, candidate=candidate)
    with np.load(out / 'data' / (definition['group'] + '.npz')) as saved:
        assert saved['H0'].tobytes() == before
    return result


def synthetic_zero_check(s_line, reference):
    h0 = np.array([0., -.0, .2, -.3])
    s = np.array([0., 0., ZERO_RELATIVE*s_line*.5, ZERO_RELATIVE*s_line])
    depth, candidate, _, _ = valley(h0, s, np.zeros(4), reference, s_line, np.ones(4))
    assert (depth == 0).all() and candidate.tobytes() == h0.tobytes()
    return {'points': 4, 'includesSignedZero': True, 'bitwiseH0': True,
            'reason': 'Real dense-network window may contain no near-zero S samples.'}


def prepare_fields(out, routes, origin, span, h0, step):
    previous = json.loads((V2 / 'meta.json').read_text(encoding='utf-8'))
    path = V2 / 'data/weight-field.npz'
    assert sha(path) == previous['outputHashes']['data/weight-field.npz']
    with np.load(path) as stored:
        weights = stored['Wg'].copy()
        lower, center = stored['lowerEdgesXZ'].copy(), stored['cellCenterOriginXZ'].copy()
    splat_record = dict(previous['splat'])
    assert splat_record['allSegmentsProcessed'] == 471997
    assert weights.sum() == splat_record['depositedSamples']
    smooth = blur(weights)
    s_line, line_record = line_reference(out)
    relief, reference, relief_record = relief_field(h0, step)
    np.savez_compressed(out / 'data/weight-field.npz', Wg=weights, S=smooth,
                        lowerEdgesXZ=lower, cellCenterOriginXZ=center, stepWu=GRID_STEP)
    splat_record.update({'reusedWeightFile': str(path.relative_to(ROOT)),
                         'reusedWeightSha256': sha(path), 'weightsRebuilt': False,
                         'previousSigmaWu': 5., 'kernelSupportWu': SIGMA*TRUNCATE})
    upper = np.array(splat_record['upperEdgesXZ'])
    assert ((origin-center) > SIGMA*TRUNCATE+1).all()
    assert (((upper-.5)-(origin+span)) > SIGMA*TRUNCATE+1).all()
    return smooth, center, s_line, relief, reference, splat_record, line_record, relief_record


def render_all(out, h0, strong, candidate, step, origin, routes, grids, sections, edge):
    plt.rcParams['font.sans-serif'] = ['Microsoft YaHei', 'SimHei', 'DejaVu Sans']
    plt.rcParams['axes.unicode_minus'] = False
    colors = np.loadtxt(PALETTE, delimiter=',', skiprows=1)
    cmap = ListedColormap(colors[:, 1:] / 255.)
    render_triplet(out, 'full', (h0, strong, candidate), step, origin, routes, cmap, edge['note'], True)
    for index, grid in enumerate(grids, 1):
        surfaces = [grid[key].reshape(N_FINE, N_FINE) for key in ('H0', 'strong', 'candidate')]
        render_triplet(out, f'closeup-{index}', surfaces, STEP_FINE, np.array(grid['centerWu'])-40.,
                       routes, cmap, f"固定{grid['group']}中心±40wu；321²复用Java细采；同一个S/全窗起伏参考。")
    render_sections(out, sections, edge['note'])
    render_junction(out, grids[0], routes, cmap)


def build_record(out, sampling, full, fine, splat_record, line, relief, edge, locked):
    return {'version': 4, 'scope': 'One diagnostic visual trial, NOT production; no tuning sweep',
            'scriptSha256': sha(Path(__file__).resolve()), 'sampling': sampling,
            'parameters': {'gridStepWu': GRID_STEP, 'splatSpacingWu': SPLAT_STEP,
                           'sigmaWu': SIGMA, 'truncate': TRUNCATE, 'paddingWu': PADDING,
                           'baseDepthH': DEPTH, 'baseDepthYproxy': 24., 'absoluteDepthCapH': .08,
                           'formula': 'Lh=maximum_filter(H0,size=51)-H0; Lh_ref=P70(Lh); '
                                      'f_height=clip(Lh/Lh_ref,0,1)**.8; '
                                      'Dmax_local=min(.08,(24/354.66)*f_height*'
                                      '(.45+.55*clip(relief/relief_ref,0,1))); '
                                      'D=Dmax_local*(-expm1(-bilinear(S)/S_line)); Hsoft=H0-D',
                           'nearZeroSRule': f'S<=S_line*{ZERO_RELATIVE}: D=0, copy H0 bits',
                           'softmin': False, 'nearestDistanceUsedForD': False, 'perTraceFade': False,
                           'blurH0': False, 'blurD': False, 'routesRebuilt': False,
                           'commonBedOrTerrainFilling': False,
                           'experimentalOverride': 'Wider than old 8wu support; not capped by pointwise old C; '
                                                   'visual parameters, not production defaults.'},
            'splat': splat_record, 'isolatedLineReference': line, 'relief': relief,
            'fullWindow': full, 'fineGridsAndSections': fine, 'boundary': edge,
            'syntheticNearZeroCheck': synthetic_zero_check(line['S_line'], relief['relief_refH']),
            'sampleReuse': {'javaInvoked': False, 'source': str(PREVIOUS.relative_to(ROOT)),
                            'actualFineSampleCount': 206565, 'allFineH0PreservedBitwise': True},
            'render': {'e': '.02+1.5H', 'fixedERange': [-.35, 1.2], 'HS': 2, 'Yproxy': YPROXY,
                       'light': [-.5, -.3, 1], 'shade': '.65+.35*max(0,dot)',
                       'perImageNormalization': False, 'whiteLineWidthPt': .35, 'whiteLineAlpha': .30,
                       'paletteSha256': sha(PALETTE), 'junctionCenterRule': 'NW v1 center, +/-25wu'},
            'preservation': {'oldProtectedFilesUnchanged': True, 'protectedFileCount': len(locked),
                             'mainFilesUnchanged': 222, 'snapshot': 'locked-inputs.json',
                             'previousV1V2V3OutputsUnchanged': True},
            'limitations': ['Max-min square relief is a range proxy, not exact disk P90-P10; '
                            'local extrema selection and bilinear interpolation are not globally C-infinity.',
                            'Truncated discrete Gaussian and bilinear sampling do not constitute a '
                            'mathematical guarantee of no derivative discontinuities or no round endpoints.',
                            'Finite route ends have smooth Gaussian falloff; no explicit per-trace end caps/fades.',
                            'S accumulates route density and overlap; dense junctions/parallel routes may '
                            'still lower interfluves even though D is bounded by the local cap.',
                            'Isolated line center is 1-exp(-1)=.6321 of local cap, not the full local cap.',
                            'No extrapolated H0; only stored H0 samples are used as terrain. '
                            'Relief and local-high envelope alone are bilinearly sampled for fine points.',
                            'Lh uses a fixed square local maximum, not actual catchment head/downstream '
                            'distance. Sublinear .8 exponent can sharpen the near-zero transition; '
                            'extremum switches are not guaranteed smooth. Ridge flanks can still be cut.',
                            'Window border cuts remain, no claim of natural terrain or drainage correctness.'],
            'outputHashes': {p.relative_to(out).as_posix(): sha(p) for p in out.rglob('*') if p.is_file()}}


def main():
    global OUTPUT
    started = time.perf_counter()
    OUTPUT = out = fresh_output()
    (out / 'data').mkdir()
    print('Fresh V4 output:', out, flush=True)
    locked = snapshot()
    save_json(out / 'locked-inputs.json', locked)
    sampling = json.loads((FROZEN / 'sampling.json').read_text(encoding='utf-8'))
    n, step = sampling['size'], sampling['stepWu']
    origin, span = np.array(sampling['originWu']), sampling['spanWu']
    assert n == 769 and sampling['HS'] == 2
    assert sum(p.is_file() for p in (ROOT / 'src/main').rglob('*')) == 222
    routes, _, _, _ = load_network()
    grids, sections = load_fine()
    h0, strong, old_depth = [np.fromfile(FROZEN / name, dtype='>f8').astype(float).reshape(n, n)
                             for name in ('carrier-height.f64be', 'strong-height.f64be', 'strong-depth.f64be')]
    before = h0.tobytes()
    print('Reuse Wg/fine/V3 factors; gaussian sigma=4, base depth=24Y, one fixed version', flush=True)
    smooth, lower, s_line, relief, ref, splat_record, line, relief_record = prepare_fields(
        out, routes, origin, span, h0, step)
    ix, iz = sampling['gridIntegerOrigin']
    x, z = np.meshgrid((ix+np.arange(n))*step, (iz+np.arange(n))*step)
    points = np.column_stack((x.ravel(), z.ravel()))
    sampled_s = bilinear(smooth, lower, GRID_STEP, points).reshape(n, n)
    local_high, lh, lh_ref, f_height, height_record = height_field(h0, step)
    depth, candidate, cap, scale = valley(h0, sampled_s, relief, ref, s_line, f_height)
    full = check_surfaces(h0, strong, old_depth, sampled_s, depth, candidate, cap, s_line, before)
    full['summitDepth'] = summit_statistics(h0, depth, lh, lh_ref)
    previous_scale = np.fromfile(V3 / 'data/scale.f64be', dtype='>f8').astype(float).reshape(n, n)
    assert previous_scale.tobytes() == scale.tobytes()
    full['V3HeightAndReliefScaleBitwiseIdentical'] = True
    # Supplement isolated-line width with network samples near the .1 local-cap contour.
    distance = np.fromfile(PREVIOUS / 'data/distance-wu.f64be', dtype='>f8').reshape(n, n)
    ratio = np.divide(depth, cap, out=np.zeros_like(depth), where=cap > 0)
    contour = (cap > 0) & (np.abs(ratio-.1) <= .005)
    full['networkNear01Contour'] = {'fractionBand': [.095, .105], 'pointCount': int(contour.sum()),
                                   'nearestSkeletonDistanceWu': quantiles(distance[contour]) if contour.any() else None,
                                   'note': 'Network density changes width; this is a sampled contour band, not an exact root.'}
    for name, data in [('sampled-S', sampled_s), ('relief', relief), ('scale', scale),
                       ('local-high', local_high), ('Lh', lh), ('f-height', f_height),
                       ('local-depth-cap', cap), ('candidate-depth', depth), ('candidate-height', candidate)]:
        data.astype('>f8').tofile(out / 'data' / (name+'.f64be'))
    fine = [evaluate_fine(out, d, smooth, lower, relief, ref, origin, step, s_line,
                          local_high, lh_ref) for d in grids+sections]
    edge = boundary_info(routes, origin, span)
    print('Rendering full map, reused fine closeups, profiles and fixed NW junction zoom', flush=True)
    render_all(out, h0, strong, candidate, step, origin, routes, grids, sections, edge)
    assert h0.tobytes() == before
    assert locked == snapshot(), 'Protected old files changed'
    record = build_record(out, sampling, full, fine, splat_record, line, relief_record, edge, locked)
    record['heightAdaptation'] = height_record
    record['elapsedSeconds'] = time.perf_counter()-started
    save_json(out / 'meta.json', record)
    print(json.dumps({'output': str(out), 'fullWindow': full, 'isolatedLine': line,
                      'relief': relief_record, 'elapsedSeconds': record['elapsedSeconds']},
                     ensure_ascii=False, indent=2), flush=True)


if __name__ == '__main__':
    main()
