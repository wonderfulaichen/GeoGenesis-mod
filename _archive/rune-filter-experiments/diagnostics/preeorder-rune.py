"""One isolated, fixed-parameter Rune pre-erosion trial; never sample/rebuild terrain/routes."""
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
from scipy.ndimage import gaussian_filter, map_coordinates, maximum_filter, minimum_filter

ROOT = Path(__file__).resolve().parents[2]
REPO = ROOT.parent
FROZEN = ROOT / 'build/strong-window-erosion-20261104-final/data'
PREVIOUS = ROOT / 'build/full-network-valley-20261104'
PALETTE = ROOT / 'build/fast-erosion-scale-only-20261103-final/data/project-palette.csv'
JAVA = ROOT / 'src/diagnostics/java/com/geogenesis/worldgen/erosion/PreErosionGuideProbe.java'
CORE = ROOT / 'src/main/java/com/geogenesis/worldgen/erosion/RuneErosionFilter.java'
BASE_OUT = ROOT / 'build/preeorder-rune-20261104'
YPROXY, HS = 354.66, 2.
LIGHT = np.array([-.5, -.3, 1.])
LIGHT /= np.linalg.norm(LIGHT)
TITLES = ['原始 H0', '旧强下切', 'Rune 预侵蚀（固定一版）']
PARAMS = dict(scale=.15, strength=.22, cellScale=.7, octaves=4, gain=.5,
              lacunarity=2., normalization=.5, gullyWeight=.5, detail=1.5,
              ridgeRounding=.1, creaseRounding=0., inputRounding=.1,
              octaveRounding=2., inputOnset=1.25, octaveOnset=1.25,
              assumedSlope=.7, assumedSlopeMix=1., HU=260., VU=120.,
              guideDepthY=4., gradStepWu=3., Yproxy=YPROXY, seed=9139912035078620160)
FOOTER = ('预侵蚀：Hguide=H0−4Y×g；Rune振幅=120Y×delta×(.25+.75g)；不模糊输出、不裁到旧强。\n'
          '固定色带 e=.02+1.5H，范围[-.35,1.2]；HS=2，Yproxy=354.66；同光照，无逐图归一化。')


def sha(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def save_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + '\n', encoding='utf-8')


def snapshot():
    names = subprocess.check_output(['git', 'ls-files', '-c', '-o', '--exclude-standard', '-z'], cwd=REPO)
    paths = {REPO / name for name in names.decode('utf-8').split('\0') if name and (REPO / name).is_file()}
    for folder in (ROOT / 'src', FROZEN, PREVIOUS, PALETTE.parent):
        paths.update(p for p in folder.rglob('*') if p.is_file())
    return {p.relative_to(REPO).as_posix(): sha(p) for p in sorted(paths)}


def fresh_output():
    if not BASE_OUT.exists():
        BASE_OUT.mkdir(parents=True)
        return BASE_OUT
    for index in range(1, 10000):
        out = BASE_OUT / f'fresh-{index:02d}'
        if not out.exists():
            out.mkdir()
            return out
    raise FileExistsError('No fresh directory available')


def load_routes():
    routes, current, points = [], None, []
    with (FROZEN / 'routes.csv').open(newline='', encoding='utf-8') as stream:
        for row in csv.DictReader(stream):
            if row['trace'] != current:
                if current is not None:
                    routes.append(np.asarray(points))
                current, points = row['trace'], []
            assert int(row['vertex']) == len(points)
            points.append([float(row['x']), float(row['z'])])
    routes.append(np.asarray(points))
    assert len(routes) == 8100
    assert sum(len(route) - 1 for route in routes) == 471997
    assert all(np.isfinite(route).all() for route in routes)
    return routes


def splat(routes, lower, shape):
    """1wu cells, .5wu route arclength; final endpoint once, no duplicate vertices."""
    weights = np.zeros(shape)
    total, deposited = 0, 0
    for route in routes:
        vectors = np.diff(route, axis=0)
        lengths = np.linalg.norm(vectors, axis=1)
        assert (lengths > 0).all()
        arc = np.r_[0., np.cumsum(lengths)]
        targets = np.r_[np.arange(0., arc[-1], .5), arc[-1]]
        indices = np.clip(np.searchsorted(arc, targets, side='right') - 1, 0, len(lengths) - 1)
        t = (targets - arc[indices]) / lengths[indices]
        points = route[indices] + t[:, None] * vectors[indices]
        cells = np.floor(points - lower).astype(np.int64)
        inside = ((cells >= 0).all(axis=1) & (cells[:, 0] < shape[1]) & (cells[:, 1] < shape[0]))
        np.add.at(weights, (cells[inside, 1], cells[inside, 0]), 1.)
        total += len(targets)
        deposited += int(inside.sum())
    assert weights.sum() == deposited
    return weights, dict(routes=len(routes), segments=sum(len(r)-1 for r in routes),
                         generatedSamples=total, depositedSamples=deposited,
                         samplesOutsideGrid=total-deposited)


def smooth(weights):
    return gaussian_filter(weights, sigma=4., mode='constant', cval=0., truncate=4.)


def raster_spec(path, origin, step, shape, key='path'):
    return {key: str(path), 'originX': float(origin[0]), 'originZ': float(origin[1]),
            'step': float(step), 'rows': int(shape[0]), 'cols': int(shape[1])}


def save_raster(path, data):
    assert np.isfinite(data).all()
    np.asarray(data, dtype='>f8').tofile(path)


def bilinear(array, origin, step, points):
    xy = (np.asarray(points).reshape(-1, 2) - origin) / step
    assert (xy >= -1e-8).all() and (xy[:, 0] <= array.shape[1]-1+1e-8).all()
    assert (xy[:, 1] <= array.shape[0]-1+1e-8).all()
    return map_coordinates(array, [xy[:, 1], xy[:, 0]], order=1, prefilter=False, mode='nearest')


def prepare_density(out, routes, origin, span):
    lower = np.floor(origin - 30.)
    upper = np.ceil(origin + span + 30.)
    shape = (int(upper[1]-lower[1])+1, int(upper[0]-lower[0])+1)
    weights, record = splat(routes, lower, shape)
    s = smooth(weights)
    line_weights, line_record = splat([np.array([[-100., 0.], [100., 0.]])], np.array([-130., -30.]), (61, 261))
    line_s = smooth(line_weights)
    s_line = float(line_s[30].max())
    assert s_line > 0 and line_record['depositedSamples'] == 401
    g = -np.expm1(-s / s_line)
    np.savez_compressed(out / 'data/weight-field.npz', Wg=weights, S=s, g=g,
                        lowerEdgesXZ=lower, cellCenterOriginXZ=lower+.5, stepWu=1.)
    np.savez_compressed(out / 'data/isolated-line.npz', Wg=line_weights, S=line_s)
    path = out / 'data/density.f64be'
    save_raster(path, g)
    record.update(gridStepWu=1., splatSpacingWu=.5, sigmaWu=4., truncate=4., paddingWu=30.,
                  weightsRebuiltFromUnmodifiedRoutes=True, routesRebuilt=False,
                  lowerEdgesXZ=lower.tolist(), shapeZX=list(shape), S_line=s_line,
                  isolatedLineLengthWu=200., isolatedLineCenterG=float(-np.expm1(-1.)),
                  splatRule='floor cells; unit sample weights; continuous arclength phase over vertices; endpoint once')
    return g, lower+.5, raster_spec(path, lower+.5, 1., shape), record


def prepare_fade(out, h0, origin, step):
    high = maximum_filter(h0, size=51, mode='nearest')
    low = minimum_filter(h0, size=51, mode='nearest')
    relative = np.divide(h0-low, high-low, out=np.full_like(h0, .5), where=high != low)
    ft = np.clip(2.*relative-1., -1., 1.)
    for name, array in [('local-high', high), ('local-low', low), ('fade-target', ft)]:
        save_raster(out / f'data/{name}.f64be', array)
    return raster_spec(out / 'data/fade-target.f64be', origin, step, h0.shape)


def load_fine():
    for folder in (PREVIOUS / 'fresh-01', PREVIOUS):
        if not (folder / 'meta.json').is_file():
            continue
        previous = json.loads((folder / 'meta.json').read_text(encoding='utf-8'))
        result = []
        for item in previous['fineGridsAndSections']:
            definition = dict(item['definition'])
            path = folder / 'data' / (definition['group'] + '.npz')
            if not path.is_file():
                break
            assert sha(path) == previous['outputHashes'][path.relative_to(folder).as_posix()]
            with np.load(path) as arrays:
                definition.update(xz=arrays['xz'].copy(), H0=arrays['H0'].copy(), strong=arrays['strong'].copy())
            definition['sourceFile'] = str(path.relative_to(ROOT))
            definition['sourceSha256'] = sha(path)
            result.append(definition)
        if len(result) == 5:
            assert sum(len(d['H0']) for d in result) == 206565
            return result[:2], result[2:]
    raise FileNotFoundError('Expected saved fine samples missing; no implicit terrain resampling permitted')


def configure_java(out, origin, step, n, grids, sections, density, fade):
    full = raster_spec(FROZEN / 'carrier-height.f64be', origin, step, (n, n), 'in')
    full['out'] = str(out / 'data/candidate-height.f64be')
    specs = [full]
    for grid in grids:
        name = grid['group']
        shape, spacing = tuple(grid['shapeZX']), grid['stepWu']
        assert shape == (321, 321) and spacing == .25
        lower = np.array(grid['centerWu']) - 40.
        x, z = np.meshgrid(lower[0]+np.arange(321)*spacing, lower[1]+np.arange(321)*spacing)
        assert np.allclose(grid['xz'], np.column_stack((x.ravel(), z.ravel())), rtol=0., atol=2e-12)
        src = out / f'data/{name}-H0.f64be'
        save_raster(src, grid['H0'])
        spec = raster_spec(src, lower, spacing, shape, 'in')
        spec['out'] = str(out / f'data/{name}-candidate.f64be')
        specs.append(spec)
    points = []
    for index, section in enumerate(sections):
        name = section['group']
        src, xz = out / f'data/{name}-H0.f64be', out / f'data/{name}-xz.f64be'
        save_raster(src, section['H0'])
        save_raster(xz, section['xz'])
        gradient_index = [1, 0, 2][index]
        points.append({'in': str(src), 'xz': str(xz), 'count': len(section['H0']),
                       'out': str(out / f'data/{name}-candidate.f64be'),
                       'gradientRasterOut': specs[gradient_index]['out']})
        section['gradientSource'] = ['NW 0.25wu', 'full 2.382812497780833wu', 'SE 0.25wu'][index]
    config = dict(rasters=specs, pointSets=points, density=density, ftRaster=fade, params=PARAMS)
    save_json(out / 'config.json', config)
    return config


def run_java(out):
    classes = out / 'classes'
    classes.mkdir()
    commands = [
        ['javac', '--release', '17', '-encoding', 'UTF-8', '-Xlint:all', '-Werror', '-d',
         str(classes), str(CORE), str(JAVA)],
        ['java', '-cp', str(classes), 'com.geogenesis.worldgen.erosion.PreErosionGuideProbe', str(out / 'config.json')]]
    with (out / 'java.log').open('w', encoding='utf-8') as log:
        for command in commands:
            log.write(subprocess.list2cmdline(command) + '\n')
            log.flush()
            subprocess.run(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT, check=True)
    return commands


def quantiles(values):
    return dict(zip(('min', 'P1', 'P50', 'P99', 'max'),
                    map(float, np.quantile(values, [0., .01, .5, .99, 1.]))))


def statistics(h0, strong, candidate, g):
    assert all(np.isfinite(a).all() for a in (h0, strong, candidate, g))
    d = (candidate-h0)*YPROXY
    old = (h0-strong)*YPROXY
    residual = d + PARAMS['guideDepthY']*g
    return {'pointCount': h0.size, 'finite': True, 'D_Yproxy': quantiles(d),
            'positiveIncrementFraction': float((d > 0).mean()),
            'D_gt_0_coverageFraction': float((d > 0).mean()),
            'negativeIncrementFraction': float((d < 0).mean()),
            'nonzeroChangeFraction': float((d != 0).mean()),
            'absD_gt_01Y_fraction': float((np.abs(d) > .1).mean()),
            'absD_gt_1Y_fraction': float((np.abs(d) > 1.).mean()),
            'lowering_gt_2Y_fraction': float((d < -2.).mean()),
            'lowering_gt_4Y_fraction': float((d < -4.).mean()),
            'uplift_gt_2Y_fraction': float((d > 2.).mean()),
            'maximumUpliftY': float(max(0., d.max())), 'maximumLoweringY': float(max(0., -d.min())),
            'meanAbsoluteChangeY': float(np.abs(d).mean()),
            'RuneFilterOnlyDeltaY': quantiles(residual),
            'g': quantiles(g), 'g_gt_09_fraction': float((g > .9).mean()),
            'oldStrongDepthY': quantiles(old),
            'maximumLoweringToOldMaximumRatio': float(max(0., -d.min())/old.max()),
            'exceedsOldPointwiseDepthFraction': float((-d > old).mean()),
            'warningMaximumUpliftAbove2Y': bool(d.max() > 2.),
            'note': 'No pointwise old-depth cap, no no-deepening constraint; D positive means uplift.'}


def collect_outputs(out, h0, strong, origin, step, g, g_origin, grids, sections):
    candidate = np.fromfile(out / 'data/candidate-height.f64be', dtype='>f8').astype(float).reshape(h0.shape)
    x, z = np.meshgrid(origin[0]+np.arange(h0.shape[1])*step, origin[1]+np.arange(h0.shape[0])*step)
    sampled_g = bilinear(g, g_origin, 1., np.column_stack((x.ravel(), z.ravel()))).reshape(h0.shape)
    save_raster(out / 'data/candidate-delta.f64be', candidate-h0)
    save_raster(out / 'data/sampled-g.f64be', sampled_g)
    records = {'full': statistics(h0, strong, candidate, sampled_g)}
    for item in grids + sections:
        name = item['group']
        item['candidate'] = np.fromfile(out / f'data/{name}-candidate.f64be', dtype='>f8').astype(float)
        local_g = bilinear(g, g_origin, 1., item['xz'])
        records[name] = statistics(item['H0'], item['strong'], item['candidate'], local_g)
        records[name]['definition'] = {k: v for k, v in item.items()
                                        if k not in ('xz', 'H0', 'strong', 'candidate')}
        np.savez_compressed(out / f'data/{name}.npz', xz=item['xz'], H0=item['H0'],
                            strong=item['strong'], candidate=item['candidate'], g=local_g)
    return candidate, records


def terrain_rgb(h, step, cmap):
    gz, gx = np.gradient(h*YPROXY, step*HS)
    dot = (-gx*LIGHT[0]-gz*LIGHT[1]+LIGHT[2])/np.sqrt(1.+gx*gx+gz*gz)
    base = cmap(Normalize(-.35, 1.2, clip=True)(.02+1.5*h))[..., :3]
    return base*(.65+.35*np.maximum(0., dot))[..., None]


def draw_panel(ax, rgb, extent, title, routes, lines):
    ax.imshow(rgb, extent=extent, origin='upper', interpolation='nearest')
    if lines:
        ax.add_collection(LineCollection(routes, colors='white', linewidths=.35, alpha=.30, antialiaseds=True))
    ax.set_xlim(extent[:2])
    ax.set_ylim(extent[2:])
    ax.set_title(title, fontsize=17)
    ax.set_xlabel('世界 x（wu）')
    ax.set_ylabel('世界 z（wu）')
    ax.tick_params(labelsize=9)


def finish(fig, out, name, title, note):
    fig.suptitle(title, fontsize=20)
    fig.text(.018, .013, FOOTER+'\n'+note, fontsize=10)
    fig.tight_layout(rect=(0., .09, 1., .94))
    fig.savefig(out / name, dpi=160)
    plt.close(fig)


def render_triplet(out, prefix, surfaces, step, origin, routes, cmap, singles=False):
    rows, cols = surfaces[0].shape
    extent = [origin[0]-step/2, origin[0]+(cols-.5)*step,
              origin[1]+(rows-.5)*step, origin[1]-step/2]
    visible = [r for r in routes if r[:, 0].max() >= extent[0] and r[:, 0].min() <= extent[1]
               and r[:, 1].max() >= extent[3] and r[:, 1].min() <= extent[2]]
    images = [terrain_rgb(h, step, cmap) for h in surfaces]
    for lines in (False, True):
        fig, axes = plt.subplots(1, 3, figsize=(27, 10.5))
        for ax, rgb, title in zip(axes, images, TITLES):
            draw_panel(ax, rgb, extent, title, visible, lines)
        suffix = '-lines' if lines else ''
        finish(fig, out, f'{prefix}-3arms{suffix}.png', prefix+'｜H0 / 旧强 / 预侵蚀',
               '白线（若有）为原8100路线；固定窗，不重建路线；边缘裁切非自然收口。')
    if singles:
        for name, rgb, title in zip(('original', 'strong', 'candidate'), images, TITLES):
            fig, ax = plt.subplots(figsize=(11, 12))
            draw_panel(ax, rgb, extent, title, visible, False)
            finish(fig, out, f'{name}.png', title+'｜完整同窗', '离线视觉实验，不接生产。')


def render_details(out, grids, sections, routes, cmap):
    for index, grid in enumerate(grids, 1):
        surfaces = [grid[k].reshape(321, 321) for k in ('H0', 'strong', 'candidate')]
        render_triplet(out, f'closeup-{index}', surfaces, .25, np.array(grid['centerWu'])-40., routes, cmap)
    fig, axes = plt.subplots(3, 1, figsize=(14, 13))
    for ax, section in zip(axes, sections):
        offsets = np.arange(-80, 81)*.25
        for key, label, color in zip(('H0', 'strong', 'candidate'), TITLES, ['#435669', '#bf633d', '#168d83']):
            ax.plot(offsets, section[key]*YPROXY, label=label, color=color, lw=1.6)
        ax.axvline(0, color='gray', lw=.7, ls=':')
        ax.set(title=section['anchorLabel']+'｜'+section['gradientSource'], xlim=(-20, 20),
               xlabel='沿原骨架法线距离（wu）', ylabel='高度（Yproxy）')
        ax.grid(alpha=.2)
        ax.legend(loc='best')
    finish(fig, out, 'sections.png', '三条原位置横剖｜实际已存H0/旧强，无重采',
           '候选逐点用原横剖H0；梯度用NW/全窗/SE二维栅格，不将法线导数冒充二维梯度。')
    grid = grids[0]
    center = np.array(grid['centerWu'])
    origin = center-40.
    extent = [origin[0]-.125, origin[0]+80.125, origin[1]+80.125, origin[1]-.125]
    fig, axes = plt.subplots(1, 3, figsize=(27, 10.5))
    for ax, key, title in zip(axes, ('H0', 'strong', 'candidate'), TITLES):
        draw_panel(ax, terrain_rgb(grid[key].reshape(321, 321), .25, cmap), extent, title, routes, True)
        ax.set_xlim(center[0]-25., center[0]+25.)
        ax.set_ylim(center[1]+25., center[1]-25.)
    finish(fig, out, 'fixed-zoom-junction.png', '固定NW ±25wu｜三臂白线',
           f'中心({center[0]:.6f},{center[1]:.6f})wu；0.25wu原细采，不按效果移动观察点。')


def build_record(out, sampling, commands, stats, splat_record, locked, elapsed):
    return {'scope': 'One fixed offline visual pre-erosion experiment; not production; no tuning sweep',
            'sampling': sampling, 'params': PARAMS, 'compileAndRun': commands, 'statistics': stats,
            'splat': splat_record,
            'units': {'inputAndOutput': 'normalized H; hY=H*354.66; world coordinates wu',
                      'internal': 'x_norm=x/260; h_norm=hY/120; gradient=(dHguideY/dwu)*(260/120)',
                      'formula': 'HnewY=H0Y-4*clamp01(g)+120*Rune.delta*(.25+.75*clamp01(g))'},
            'fadeTarget': {'sizePixels': 51, 'mode': 'nearest', 'footprintWu': 51*sampling['stepWu'],
                           'formula': 'clip(2*(H0-min)/(max-min)-1,-1,1); flat=0',
                           'fine': 'bilinear from full-window ftRaster'},
            'render': {'e': '.02+1.5H', 'range': [-.35, 1.2], 'HS': HS, 'Yproxy': YPROXY,
                       'light': [-.5, -.3, 1.], 'shade': '.65+.35*max(0,dot)',
                       'perImageNormalization': False, 'paletteSha256': sha(PALETTE)},
            'preservation': {'allProtectedHashesUnchanged': True, 'protectedCount': len(locked),
                             'mainFileCount': 222, 'mainHashesUnchanged': True,
                             'routesRebuilt': False, 'terrainResampled': False,
                             'oldDataModified': False, 'productionWiring': False},
            'limitations': ['Local 51-square min/max is an altitude proxy, not physical erosion.',
                            'g can saturate across a dense network, reducing proximity contrast.',
                            'Rune is allowed to uplift; no positivity clipping or old-depth clipping.',
                            'HU/VU are filter units; shade separately uses requested HS=2/Yproxy.',
                            'Full and fine gradients use their own saved H0 grids, so resolutions need not agree exactly.',
                            'Gradients use +/-3wu, clipped at raster boundary with actual denominator; fine edge has one-sided differences.',
                            'Center section uses full-grid 2D gradient; NW/SE sections use saved fine-grid gradients.',
                            'Old strong is a comparison arm, not a newly executed particle-erosion stage; later particle dominance is not proven.'],
            'elapsedSeconds': elapsed,
            'outputHashes': {p.relative_to(out).as_posix(): sha(p) for p in out.rglob('*') if p.is_file()}}


def main():
    started = time.perf_counter()
    out = fresh_output()
    (out / 'data').mkdir()
    print('Fresh output:', out, flush=True)
    locked = snapshot()
    save_json(out / 'locked-inputs.json', locked)
    assert sum(p.is_file() for p in (ROOT / 'src/main').rglob('*')) == 222
    sampling = json.loads((FROZEN / 'sampling.json').read_text(encoding='utf-8'))
    n, step, origin = sampling['size'], sampling['stepWu'], np.array(sampling['originWu'])
    assert n == 769 and sampling['HS'] == 2
    h0, strong = [np.fromfile(FROZEN / name, dtype='>f8').astype(float).reshape(n, n)
                  for name in ('carrier-height.f64be', 'strong-height.f64be')]
    routes = load_routes()
    grids, sections = load_fine()
    print('8100 unchanged routes; reuse 206565 fine samples; splat density and calculate altitude fade', flush=True)
    g, g_origin, density, splat_record = prepare_density(out, routes, origin, sampling['spanWu'])
    fade = prepare_fade(out, h0, origin, step)
    configure_java(out, origin, step, n, grids, sections, density, fade)
    commands = run_java(out)
    candidate, stats = collect_outputs(out, h0, strong, origin, step, g, g_origin, grids, sections)
    print(json.dumps(stats['full'], indent=2), flush=True)
    print('Render all fixed-scale panels, fine closeups, existing sections and NW junction', flush=True)
    plt.rcParams['font.sans-serif'] = ['Microsoft YaHei', 'SimHei', 'DejaVu Sans']
    plt.rcParams['axes.unicode_minus'] = False
    palette = np.loadtxt(PALETTE, delimiter=',', skiprows=1)
    cmap = ListedColormap(palette[:, 1:] / 255.)
    render_triplet(out, 'full', (h0, strong, candidate), step, origin, routes, cmap, True)
    render_details(out, grids, sections, routes, cmap)
    after = snapshot()
    changes = [name for name in set(locked) | set(after) if locked.get(name) != after.get(name)]
    save_json(out / 'checks.json', {'finite': True, 'protectedHashesUnchanged': not changes,
                                   'changedProtectedFiles': changes, 'mainFileCount': 222})
    assert not changes, changes
    record = build_record(out, sampling, commands, stats, splat_record, locked, time.perf_counter()-started)
    save_json(out / 'meta.json', record)
    print('DONE:', out, 'seconds=', record['elapsedSeconds'], flush=True)


if __name__ == '__main__':
    main()
