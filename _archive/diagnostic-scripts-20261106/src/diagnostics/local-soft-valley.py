"""One fixed local cut-field visual experiment; not a production terrain design."""
import csv
import hashlib
import json
from pathlib import Path

import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.colors import ListedColormap, Normalize
import numpy as np
from scipy.ndimage import gaussian_filter

ROOT = Path(__file__).resolve().parents[2]
INPUT = ROOT / 'build/old-route-upstream-audit-20261104/prepare-fresh-03'
PALETTE = ROOT / 'build/fast-erosion-scale-only-20261103-final/data/project-palette.csv'
SOURCE = '9139912035078620160:strong-window:-170:-183'


def digest(path):
    h = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            h.update(block)
    return h.hexdigest()


def fresh_output():
    preferred = ROOT / 'build/local-soft-valley-visual-20261104'
    if not preferred.exists():
        preferred.mkdir()
        return preferred
    for i in range(1, 10000):
        candidate = preferred / f'fresh-{i:02d}'
        if not candidate.exists():
            candidate.mkdir()
            return candidate
    raise FileExistsError('No fresh output directory')


def load_samples(meta):
    grid, route = [], []
    with (INPUT / 'actual/samples.csv').open(encoding='utf-8', newline='') as stream:
        for row in csv.DictReader(stream):
            if row['group'] == 'NW-local' and row['kind'] == 'grid':
                assert row['source'] == SOURCE
                grid.append([float(row[k]) for k in ('t', 'x', 'z', 'H0', 'Hstrong')])
            elif row['group'] == 'NW-full' and row['kind'] == 'full-source':
                assert row['source'] == SOURCE
                route.append([float(row[k]) for k in ('t', 'x', 'z')])
    grid = np.asarray(grid)
    grid = grid[np.argsort(grid[:, 0])]
    shape = tuple(meta['localGrid']['shapeZX'])
    assert grid.shape[0] == np.prod(shape) == 202752
    assert np.array_equal(grid[:, 0], np.arange(grid.shape[0]))
    x, z, h0, strong = [grid[:, i].reshape(shape) for i in range(1, 5)]
    origin = meta['localGrid']['originWu']
    step = meta['localGrid']['stepWu']
    assert step == .25
    assert np.allclose(x, origin[0] + step * np.arange(shape[1]), atol=1e-10, rtol=0)
    assert np.allclose(z, origin[1] + step * np.arange(shape[0])[:, None], atol=1e-10, rtol=0)
    route = np.asarray(route)
    route = route[np.argsort(route[:, 0])]
    center = route[np.argmin(np.abs(route[:, 0] + 24))]
    assert abs(center[0] + 24) < .02
    return x, z, h0, strong, route, center


def terrain_rgb(h, step, cmap):
    # Identical fixed L0 rendering; no per-panel height/color normalization.
    gz, gx = np.gradient(h * 354.66, step * 2)
    light = np.array([-.5, -.3, 1.])
    light /= np.linalg.norm(light)
    dot = (-gx * light[0] - gz * light[1] + light[2]) / np.sqrt(1 + gx*gx + gz*gz)
    base = cmap(Normalize(-.35, 1.2, clip=True)(.02 + 1.5*h))[..., :3]
    return base * (.65 + .35 * np.maximum(dot, 0))[..., None]


def panel(ax, rgb, extent, bounds, title):
    ax.imshow(rgb, extent=extent, origin='upper', interpolation='nearest')
    ax.set_xlim(bounds[:2])
    ax.set_ylim(bounds[3], bounds[2])
    ax.set_title(title, fontsize=16)
    ax.set_axis_off()


def render(output, surfaces, step, x, z, bounds, route):
    plt.rcParams['font.sans-serif'] = ['Microsoft YaHei', 'SimHei', 'DejaVu Sans']
    plt.rcParams['axes.unicode_minus'] = False
    colors = np.loadtxt(PALETTE, delimiter=',', skiprows=1)
    cmap = ListedColormap(colors[:, 1:] / 255.)
    extent = [x[0, 0] - step/2, x[0, -1] + step/2,
              z[-1, 0] + step/2, z[0, 0] - step/2]
    images = [terrain_rgb(h, step, cmap) for h in surfaces]
    titles = ['原始地形', '旧强下切', '下切场柔化试版']
    for name, title, rgb in zip(('original', 'strong', 'soft'), titles, images):
        fig, ax = plt.subplots(figsize=(7, 7))
        panel(ax, rgb, extent, bounds, title)
        fig.tight_layout(pad=.6)
        fig.savefig(output / f'{name}.png', dpi=160)
        plt.close(fig)
    fig, axes = plt.subplots(1, 3, figsize=(18, 6))
    for ax, title, rgb in zip(axes, titles, images):
        panel(ax, rgb, extent, bounds, title)
    fig.tight_layout(pad=.7)
    fig.savefig(output / 'comparison.png', dpi=160)
    plt.close(fig)
    fig, ax = plt.subplots(figsize=(7, 7))
    panel(ax, images[1], extent, bounds, '旧路线局部定位')
    ax.plot(route[:, 1], route[:, 2], color='#ffde59', lw=1)
    fig.tight_layout(pad=.6)
    fig.savefig(output / 'location.png', dpi=160)
    plt.close(fig)


def main():
    inputs = [INPUT / 'actual/samples.csv', INPUT / 'prepare-interface.json', PALETTE]
    hashes = {str(p.relative_to(ROOT)): digest(p) for p in inputs}
    meta = json.loads(inputs[1].read_text(encoding='utf-8'))
    assert meta['source'] == SOURCE
    x, z, h0, strong, route, center = load_samples(meta)
    h0_before = h0.copy()
    c = h0 - strong
    assert np.isfinite(c).all() and (c >= 0).all()
    blur = gaussian_filter(c, sigma=2/.25, mode='reflect', truncate=4.)
    edge = np.clip(c / .01, 0, 1)
    fade = edge * edge * (3 - 2 * edge)
    d = np.minimum(c, blur * .65) * fade
    soft = h0 - d
    bounds = [float(center[1]-20), float(center[1]+20),
              float(center[2]-20), float(center[2]+20)]
    # Keep the requested exact +/-20 view; never invent missing edge samples.
    # The supplied grid stops slightly short of this window's positive-z edge.
    margin = [bounds[0]-float(x.min()), float(x.max())-bounds[1],
              bounds[2]-float(z.min()), float(z.max())-bounds[3]]
    ix = np.flatnonzero((x[0] >= bounds[0]) & (x[0] <= bounds[1]))
    iz = np.flatnonzero((z[:, 0] >= bounds[2]) & (z[:, 0] <= bounds[3]))
    crop = np.ix_(iz, ix)
    old, new = c[crop], d[crop]
    diff = old - new
    changed = diff > 1e-12
    support = old > 0
    checks = {'finite': bool(all(np.isfinite(a).all() for a in (h0, strong, d, soft))),
              'H0BitwiseUnchanged': h0.tobytes() == h0_before.tobytes(),
              'DnewInZeroToC': bool(((d >= 0) & (d <= c)).all()),
              'zeroOutsideOldSupport': bool((d[c == 0] == 0).all()),
              'notAboveOriginalCap': bool(d.max() <= c.max())}
    assert all(checks.values()), checks
    stats = {'depthdiffThresholdH': 1e-12, 'localSampleCount': int(old.size),
             'oldSupportCount': int(support.sum()), 'depthdiffCount': int(changed.sum()),
             'depthdiffCoverageAll': float(changed.mean()),
             'depthdiffCoverageOldSupport': float(changed.sum()/support.sum()),
             'oldSupportCoverageAll': float(support.mean()),
             'maxDepthdiffH': float(diff.max()), 'maxDepthdiffYproxy': float(diff.max()*354.66),
             'localOldCapH': float(old.max()), 'localNewMaxDepthH': float(new.max()),
             'localDepthSumRetention': float(new.sum()/old.sum())}
    output = fresh_output()
    (output / 'data').mkdir()
    np.savez_compressed(output / 'data/local.npz', x=x[crop], z=z[crop], H0=h0[crop],
                        Hstrong=strong[crop], C=old, Dnew=new, Hsoft=soft[crop], depthdiff=diff)
    render(output, (h0, strong, soft), .25, x, z, bounds, route)
    record = {'scope': 'Local cut-field softening visual trial, NOT final terrain algorithm; user judges images.',
              'source': SOURCE, 'inputSha256': hashes, 'scriptSha256': digest(Path(__file__)),
              'grid': meta['localGrid'], 'centerSampleTWorldXZ': center.tolist(),
              'windowBoundsXminXmaxZminZmaxWu': bounds, 'cropShapeZX': list(old.shape),
              'parameters': {'sigmaWu': 2, 'sigmaPixels': 8, 'multiplier': .65,
                             'edgeFadeH': .01, 'gaussianMode': 'reflect', 'truncate': 4,
                             'formula': 'Dnew=min(C,gaussian_filter(C,8)*.65)*smoothstep(clip(C/.01,0,1)); Hsoft=H0-Dnew',
                             'H0Filtered': False, 'fullGridBeforeCrop': True,
                             'gridBoundaryMarginWu': margin, 'terrainInterpolation': 'none; actual .25wu samples'},
              'render': {'e': '.02+1.5H', 'fixedERange': [-.35, 1.2], 'light': [-.5, -.3, 1],
                         'shade': '.65+.35*max(dot,0), identical old renderer', 'HS': 2,
                         'Yproxy': 354.66, 'baseNormalization': False},
              'checks': checks, 'depthdiff': stats,
              'limitations': ['Requested positive-z edge exceeds supplied last sample center by about .435wu; missing strip stays white, no extrapolation. Full-grid reflect boundary may affect cuts within 8wu of grid edges.',
                              'Pointwise min and old support may preserve narrow original shape.',
                              'No route rebuild, no drainage/connectivity claim, no production change.',
                              'Gaussian applied only to cut C, never H0; no claim of naturalness achieved.']}
    assert hashes == {str(p.relative_to(ROOT)): digest(p) for p in inputs}
    (output / 'meta.json').write_text(json.dumps(record, ensure_ascii=False, indent=2), encoding='utf-8')
    print(json.dumps({'output': str(output), 'checks': checks, 'depthdiff': stats}, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
