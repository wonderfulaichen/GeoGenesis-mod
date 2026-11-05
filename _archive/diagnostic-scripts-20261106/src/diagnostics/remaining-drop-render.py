"""Read-only S1 evaluator renderer. Import render(out) -> dict, or python -B FILE OUT.

Consumes solver/Java evidence; never solves, runs Java, edits inputs, or writes locks.
The old arm is the FAILED SUBTRACTIVE CUT, not the old distance replacement.
Malformed/missing evidence raises; a nonconverged solver still gets every figure.
"""

import argparse
import csv
import hashlib
import json
from pathlib import Path
import sys

import numpy as np
import matplotlib

matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.cm import ScalarMappable
from matplotlib.colors import ListedColormap, Normalize
from scipy.ndimage import minimum_filter

ROOT = Path(__file__).resolve().parents[2]
BASE = ROOT / 'build/noise-following-subtractive-20261103-final/data'
PALETTE = ROOT / 'build/fast-erosion-scale-only-20261103-final/data/project-palette.csv'
CAP, ETA, HS, YPROXY, RADIUS = .02 / 1.5, .9, 2, 354.66, 8.0
ELIMITS = (-.35, 1.2)
LIGHT = np.array([-.5, -.3, 1.0]) / np.linalg.norm([-.5, -.3, 1.0])
ARMS = ('H0', 'failed cut', 'new')
LINE_COLORS = ('#333333', '#d95f02', '#1b9e77')
FIELDS = ('carrier-height', 'old-height', 'new-height', 'new-depth', 'weight')
SAMPLE_HEADER = 'kind,trace,s_wu,world_x,world_z,H0,Dold'.split(',')
TARGET_HEADER = 'trace,s_wu,H0,Dold,target_D'.split(',')
PROFILE_HEADER = 'profile,s_wu,world_x,world_z,carrier_H,old_H,cut_H'.split(',')
CROSS_HEADER = 'trace,s_wu,offset_wu,world_x,world_z,carrier_H,old_H,cut_H'.split(',')
END_HEADER = 'trace,world_x,world_z,H0,Dold,W,Dnew,Hnew'.split(',')
FOOTER = ('S1 STOP | diagnostic type-space, NOT Minecraft | old = FAILED CUT, not distance replacement\n'
          'e=.02+1.5H; fixed e[-.35,1.2]; Yproxy=354.66H; HS=2; light[-.5,-.3,1]; '
          'shade=.65+.35*positiveDot; no per-arm normalization.\n'
          'Integrity is NOT effect acceptance; all four frozen routes retained; no S2 claim.')
TEXT_COLUMNS = {'kind', 'trace', 'profile'}


def require(condition, message):
    if not condition:
        raise ValueError(message)


def read_json(path):
    def reject(value):
        raise ValueError('Nonfinite JSON: ' + value)
    return json.loads(path.read_text(encoding='utf-8-sig'), parse_constant=reject)


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False) + '\n',
                    encoding='utf-8')


def read_table(path, header):
    with path.open(encoding='utf-8-sig', newline='') as stream:
        reader = csv.reader(stream)
        require(next(reader, None) == header, 'Exact CSV header mismatch: ' + str(path))
        rows = list(reader)
    require(rows and all(len(row) == len(header) for row in rows), 'Empty/ragged CSV: ' + str(path))
    table = {}
    for index, key in enumerate(header):
        column = [row[index] for row in rows]
        table[key] = np.asarray(column, dtype=str if key in TEXT_COLUMNS else float)
        require(all(column), 'Empty CSV cell: ' + str(path))
        if key not in TEXT_COLUMNS:
            require(np.isfinite(table[key]).all(), 'Nonfinite CSV: ' + str(path))
    return table


def read_normals(path):
    header = 'trace,s_wu,normal_kind,normal_x,normal_z,defined,eligible,turn_radians,secant_delta_wu'.split(',')
    with path.open(encoding='utf-8-sig', newline='') as stream:
        reader = csv.DictReader(stream)
        require(reader.fieldnames == header, 'Exact normal-checks header mismatch')
        rows = list(reader)
    result = {}
    for row in rows:
        for field in ('defined', 'eligible'):
            require(row[field] in ('true', 'false'), 'Normal flags must be true/false strings')
        key = (row['trace'], float(row['s_wu']))
        require(np.isfinite(key[1]) and key not in result, 'Invalid/duplicate normal station')
        numbers = {field: float(row[field]) for field in ('normal_x', 'normal_z', 'turn_radians', 'secant_delta_wu')}
        require(all(np.isfinite(value) for value in numbers.values()), 'Nonfinite normal check')
        result[key] = dict(**numbers, normal_kind=row['normal_kind'],
                           defined=row['defined'] == 'true', eligible=row['eligible'] == 'true')
    return result


def rejected_paths(solver):
    raw = solver.get('rejected_paths', [])
    if isinstance(raw, dict):
        raw = [dict(value, trace=key) if isinstance(value, dict) else dict(trace=key, drops=value)
               for key, value in raw.items()]
    require(isinstance(raw, list) and all(isinstance(row, dict) and 'trace' in row for row in raw),
            'rejected_paths requires list of {trace,drops} or trace-keyed dict')
    result = {str(row['trace']): row for row in raw}
    for drop in solver.get('input_drop_report', {}).get('true_uphill', []):
        result.setdefault(str(drop['trace']), dict(trace=drop['trace'], drops=[drop]))
    return result


def annotate_routes(traces, samples, solver):
    rejected = rejected_paths(solver)
    for trace in traces:
        key = str(trace['id'])
        observed = []
        for kind in ('train', 'validation'):
            table = ordered(subset(samples, (samples['trace'] == key) & (samples['kind'] == kind)), 's_wu', key)
            rise = np.diff(table['H0'])
            tolerance = np.maximum(rounding(table['H0'][:-1]), rounding(table['H0'][1:]))
            for index in np.flatnonzero(rise > tolerance):
                observed.append(dict(kind=kind, s_left=float(table['s_wu'][index]),
                                     s_right=float(table['s_wu'][index + 1]), riseH=float(rise[index])))
        trace['originalUphillEvidence'] = observed
        trace['solverPathRejection'] = rejected.get(key)
        trace['candidateStatus'] = ('REJECTED_ORIGINAL_UPHILL' if key in rejected or observed
                                    else 'LOCAL_VALIDITY_PENDING')


def route_label(trace):
    return trace.get('candidateStatus', 'LOCAL_VALIDITY_PENDING') + '\nbaseline: ' + trace['status']


def subset(table, mask):
    return {key: value[mask] for key, value in table.items()}


def groups(table, keys):
    result = {}
    for index, values in enumerate(zip(*(table[key] for key in keys))):
        key = values[0] if len(keys) == 1 else tuple(values)
        result.setdefault(key, []).append(index)
    return {key: subset(table, indices) for key, indices in result.items()}


def ordered(table, axis, label):
    require(len(table[axis]) >= 2 and np.all(np.diff(table[axis]) > 0),
            'Samples must strictly increase without sorting/dropping: ' + str(label))
    return table


def read_grid(path, size):
    require(path.stat().st_size == size * size * 8, 'Wrong >f8 size: ' + str(path))
    values = np.fromfile(path, dtype='>f8').astype(np.float64).reshape(size, size)
    require(np.isfinite(values).all(), 'Nonfinite raster: ' + str(path))
    return values


def load_dataset(out):
    sampling_path = out / 'export/sampling.json'
    meta = read_json(sampling_path if sampling_path.is_file() else BASE / 'sampling.json')
    reference = read_json(BASE / 'sampling.json')
    for key in ('size', 'originWu', 'stepWu', 'HS', 'seed'):
        require(meta[key] == reference[key], 'Frozen sampling drift: ' + key)
    require(meta['size'] == 769 and meta['HS'] == HS, 'Expected 769 squared / HS2')
    traces = read_json(BASE / 'traces.json')['traces']
    ids = [str(trace['id']) for trace in traces]
    require(len(ids) == 4 and len(set(ids)) == 4, 'Expected original four trace IDs')
    evaluated = out / 'evaluated'
    arrays = {key: read_grid(evaluated / (key + '.f64be'), 769) for key in FIELDS}
    tables = dict(samples=read_table(out / 'export/samples.csv', SAMPLE_HEADER),
                  target=read_table(out / 'target.csv', TARGET_HEADER),
                  evaluated=read_table(evaluated / 'eval-samples.csv', SAMPLE_HEADER + ['W', 'Dnew', 'Hnew']),
                  endpoints=read_table(evaluated / 'endpoint-grid.csv', END_HEADER))
    tables['profiles'] = read_table(evaluated / 'profiles.csv', PROFILE_HEADER)
    tables['sections'] = read_table(evaluated / 'cross-sections.csv', CROSS_HEADER)
    for key in ('samples', 'evaluated'):
        require(set(tables[key]['kind']) == {'train', 'validation'}, 'Need train AND validation: ' + key)
        require(set(tables[key]['trace']) == set(ids), 'Trace coverage mismatch: ' + key)
    for key in ('target', 'endpoints', 'sections'):
        require(set(tables[key]['trace']) == set(ids), 'All four routes required: ' + key)
    profiles = groups(tables['profiles'], ['profile'])
    require(set(ids).issubset(profiles), 'Missing actual-H route profiles')
    sections = groups(tables['sections'], ['trace', 's_wu'])
    for key, table in profiles.items():
        ordered(table, 's_wu', key)
    normals = read_normals(evaluated / 'normal-checks.csv')
    require(set(normals) == set(sections), 'Normal checks must cover every cross-section station')
    for key, table in sections.items():
        if normals[key]['defined']:
            ordered(table, 'offset_wu', key)
        else:
            require(len(table['offset_wu']) == 1, 'Undefined normal must retain its single center sample')
    solver = read_json(out / 'solver.json')
    annotate_routes(traces, tables['samples'], solver)
    return dict(meta=meta, traces=traces, ids=ids, arrays=arrays, tables=tables,
                profiles=profiles, sections=sections, normals=normals, solver=solver)


def palette():
    rows = np.loadtxt(PALETTE, delimiter=',', skiprows=1)
    require(rows.ndim == 2 and rows.shape[1] == 4 and np.isfinite(rows).all(), 'Invalid palette')
    require(np.all(np.diff(rows[:, 0]) > 0) and np.all((rows[:, 1:] >= 0) & (rows[:, 1:] <= 255)),
            'Invalid palette coordinates/RGB')
    return ListedColormap(rows[:, 1:] / 255)


def shade(height, step_wu):
    gz, gx = np.gradient(height * YPROXY, step_wu * HS)
    dot = (-gx * LIGHT[0] - gz * LIGHT[1] + LIGHT[2]) / np.sqrt(1 + gx * gx + gz * gz)
    return .65 + .35 * np.maximum(0, dot)


def terrain_rgb(height, illumination, cmap):
    return cmap(Normalize(*ELIMITS, clip=True)(.02 + 1.5 * height))[..., :3] * illumination[..., None]


def grid_meta(xs, zs):
    require(len(xs) >= 2 and len(zs) >= 2, 'Endpoint grid requires two dimensions')
    dx, dz = np.diff(xs), np.diff(zs)
    require(np.allclose(dx, dx[0], rtol=1e-9, atol=1e-10) and
            np.allclose(dz, dz[0], rtol=1e-9, atol=1e-10), 'Endpoint grid must be uniform')
    return dict(originWu=[float(xs[0]), float(zs[0])], stepWu=float(dx[0]),
                zStepWu=float(dz[0]), size=len(xs), rows=len(zs))


def bounds(meta):
    x, z = meta['originWu']
    return (x, x + (meta['size'] - 1) * meta['stepWu'],
            z, z + (meta.get('rows', meta['size']) - 1) * meta.get('zStepWu', meta['stepWu']))


def raster(ax, image, meta, title, limits=None, mode='rgb'):
    x, z = meta['originWu']
    dx, dz = meta['stepWu'], meta.get('zStepWu', meta['stepWu'])
    rows, cols = image.shape[:2]
    extent = (x - dx / 2, x + (cols - .5) * dx, z + (rows - .5) * dz, z - dz / 2)
    options = {}
    if mode == 'gray':
        options = dict(cmap='gray', vmin=.65, vmax=1)
    elif mode in ('depth', 'weight'):
        options = dict(cmap='magma' if mode == 'depth' else 'viridis',
                       vmin=0, vmax=CAP if mode == 'depth' else 1)
    ax.imshow(image, origin='upper', extent=extent, interpolation='nearest', **options)
    limit = limits if limits is not None else bounds(meta)
    ax.set(xlim=limit[:2], ylim=(limit[3], limit[2]), xlabel='world x (wu)',
           ylabel='world z (wu)', title=title, aspect='equal', facecolor='#d5d5d5')


def finish(fig, out, name, title, files, bar=None):
    fig.suptitle('Remaining-drop field | S1 STOP\n' + title, fontsize=12)
    fig.text(.015, .012, FOOTER, fontsize=8)
    fig.tight_layout(rect=(0, .12, .94 if bar else 1, .91), h_pad=3)
    if bar:
        fig.colorbar(bar[0], cax=fig.add_axes([.955, .25, .012, .5]), label=bar[1])
    try:
        fig.savefig(out / name, dpi=140, metadata={'Description': title + '; ' + FOOTER})
        files.append(name)
    finally:
        plt.close(fig)


def colorbar(cmap):
    return ScalarMappable(norm=Normalize(*ELIMITS), cmap=cmap), 'e=.02+1.5H (unshaded palette)'


def page_name(stem, page):
    return stem + ('' if page == 0 else '-%02d' % (page + 1)) + '.png'


def short_id(trace):
    return ':'.join(str(trace['id']).split(':')[-2:])


def render_whole(out, data, shades, colors, cmap, files):
    for name, images, mode in (('comparison.png', colors, 'rgb'), ('hillshade.png', shades, 'gray')):
        fig, axes = plt.subplots(1, 3, figsize=(21, 8))
        for ax, image, arm in zip(axes, images, ARMS):
            raster(ax, image, data['meta'], arm, mode=mode)
        bar = colorbar(cmap) if mode == 'rgb' else (
            ScalarMappable(norm=Normalize(.65, 1), cmap='gray'), 'fixed illumination')
        finish(fig, out, name, 'Same frozen 769 x 769 window; H0 / failed cut / new', files, bar)
    a = data['arrays']
    old_depth = a['carrier-height'] - a['old-height']
    fig, axes = plt.subplots(2, 2, figsize=(16, 15))
    for ax, image, title, mode in (
            (axes[0, 0], old_depth, 'Old failed-cut D | 0..cap', 'depth'),
            (axes[0, 1], a['new-depth'], 'New D | 0..cap', 'depth'),
            (axes[1, 0], a['weight'], 'Shared W | 0..1', 'weight')):
        raster(ax, image, data['meta'], title, mode=mode)
        fig.colorbar(ScalarMappable(norm=Normalize(0, CAP if mode == 'depth' else 1),
                                   cmap='magma' if mode == 'depth' else 'viridis'), ax=ax, shrink=.65)
    axes[1, 1].axis('off')
    axes[1, 1].text(.02, .7, 'Old D = H0 - FAILED CUT\nNew D = Java final-field depth\n'
                           'W is not a terrain height.\nFixed ranges; no autoscaling.', fontsize=12)
    finish(fig, out, 'depth-and-weight.png', 'Depth budget and shared spatial weight', files)


def render_routes(out, data, shades, files):
    fig, ax = plt.subplots(figsize=(13, 12))
    raster(ax, shades[0], data['meta'], 'All four WORLD routes; location ONLY', mode='gray')
    for index, trace in enumerate(data['traces']):
        points = np.asarray(trace['points'], dtype=float)
        color = plt.get_cmap('tab10')(index)
        ax.plot(points[:, 0], points[:, 1], color=color, linewidth=1,
                label=short_id(trace) + '\n' + route_label(trace))
        ax.plot(*trace['seedWu'], 'o', color=color, markersize=3)
        ax.annotate(short_id(trace), trace['seedWu'], color=color, fontsize=8)
    ax.legend(fontsize=8)
    finish(fig, out, 'paths.png', 'Original baseline order; route location is NOT acceptance', files)
    crops = []
    for page, first in enumerate(range(0, 4, 2)):
        chunk = data['traces'][first:first + 2]
        fig, axes = plt.subplots(len(chunk), 3, figsize=(20, 6 * len(chunk)), squeeze=False)
        for row, trace in enumerate(chunk):
            x, z = trace['seedWu']
            limits = (x - 100, x + 100, z - 100, z + 100)
            crops.append(dict(trace=trace['id'], status=route_label(trace), boundsWu=list(limits)))
            for col, arm in enumerate(ARMS):
                raster(axes[row, col], shades[col], data['meta'],
                       '%s\n%s\n%s' % (arm, short_id(trace), route_label(trace)), limits, 'gray')
        finish(fig, out, page_name('no-overlay-zoom', page),
               'Every frozen seed +/-100wu; no overlay/reselection; gray outside raster', files)
    return crops


def plot_heights(ax, table, axis):
    for name, key, color in zip(ARMS, ('carrier_H', 'old_H', 'cut_H'), LINE_COLORS):
        ax.plot(table[axis], table[key], label=name, color=color, linewidth=1, marker='.', markersize=1)
    ax.set(xlabel=axis + ' | direct Java samples', ylabel='Actual H (not normalized)')
    ax.ticklabel_format(axis='y', style='plain', useOffset=False)
    ax.grid(alpha=.2)
    ax.legend(fontsize=8)


def render_beds(out, data, files):
    fig, axes = plt.subplots(4, 2, figsize=(18, 18), squeeze=False)
    records = []
    for row, trace in enumerate(data['traces']):
        table = data['profiles'][str(trace['id'])]
        length = float(trace['lengthWu'])
        require(abs(table['s_wu'][-1] - length) <= 1e-9, 'Profile must retain actual endpoint')
        start = max(float(table['s_wu'][0]), length - 16)
        mask = table['s_wu'] >= start
        # Include the preceding actual point to avoid hiding the start of the tail interval.
        before = np.flatnonzero(~mask)
        if len(before):
            mask[before[-1]] = True
        tail = subset(table, mask)
        for col, part in enumerate((table, tail)):
            plot_heights(axes[row, col], part, 's_wu')
            axes[row, col].set_title('%s\n%s\n%s' % (
                'Full route' if col == 0 else 'Tail: last min(16,L) wu', short_id(trace), route_label(trace)), fontsize=9)
            if col == 1 and length > start:
                axes[row, col].set_xlim(start, length)
        records.append(dict(trace=trace['id'], tailStartWu=start, endpointRetained=True,
                            fullSamples=len(table['s_wu']), tailSamples=len(tail['s_wu'])))
    finish(fig, out, 'bed-detail.png', 'Three ACTUAL height curves; target is NOT a terrain', files)
    return records


def render_sections(out, data, files):
    entries = list(data['sections'].items())
    low = min(float(np.min(t[k])) for _, t in entries for k in ('carrier_H', 'old_H', 'cut_H'))
    high = max(float(np.max(t[k])) for _, t in entries for k in ('carrier_H', 'old_H', 'cut_H'))
    pad = max((high - low) * .025, 1e-7)
    statuses = {str(t['id']): route_label(t) for t in data['traces']}
    for page, first in enumerate(range(0, len(entries), 6)):
        chunk = entries[first:first + 6]
        fig, axes = plt.subplots(len(chunk), 1, figsize=(17, 3.3 * len(chunk) + 2), squeeze=False)
        for ax, ((identifier, distance), table) in zip(axes[:, 0], chunk):
            plot_heights(ax, table, 'offset_wu')
            normal = data['normals'][(identifier, distance)]
            valleys = [valley_record(table, field, normal)['passed'] for field in ('carrier_H', 'old_H', 'cut_H')]
            ax.set(ylim=(low - pad, high + pad), title='%s\n%s | s=%.9g wu\nnormal eligible=%s; valley H0/old/new=%s' % (
                ':'.join(identifier.split(':')[-2:]), statuses[identifier], distance, normal['eligible'], valleys))
        finish(fig, out, page_name('cross-sections', page),
               'ALL supplied groups, six/page; actual H, shared vertical range across pages', files)


def render_depth_profiles(out, data, files):
    validation = validation_groups(data)
    targets = groups(data['tables']['target'], ['trace'])
    fig, axes = plt.subplots(4, 1, figsize=(17, 17), squeeze=False)
    for ax, trace in zip(axes[:, 0], data['traces']):
        key = str(trace['id'])
        table, target = validation[key], ordered(targets[key], 's_wu', key)
        ax.plot(table['s_wu'], table['Dold'], color=LINE_COLORS[1], label='old failed-cut D (validation)')
        ax.plot(target['s_wu'], target['target_D'], '--', color='#7570b3', label='1D train target ONLY; NOT terrain')
        ax.plot(table['s_wu'], table['Dnew'], color=LINE_COLORS[2], label='actual shared 2D D (validation)')
        ax.set(title=short_id(trace) + '\n' + route_label(trace), xlabel='s_wu', ylabel='Depth H', ylim=(0, CAP))
        ax.grid(alpha=.2)
        ax.legend(fontsize=8)
    finish(fig, out, 'depth-profiles.png', 'One-dimensional target is NOT final terrain; no target-bed curve', files)


def endpoint_grids(data):
    result = {}
    for key, table in groups(data['tables']['endpoints'], ['trace']).items():
        xs, zs = np.unique(table['world_x']), np.unique(table['world_z'])
        require(len(xs) * len(zs) == len(table['H0']), 'Incomplete/duplicate endpoint rectangle: ' + key)
        xi, zi = np.searchsorted(xs, table['world_x']), np.searchsorted(zs, table['world_z'])
        require(len(np.unique(zi * len(xs) + xi)) == len(table['H0']), 'Duplicate endpoint world index')
        arrays = {}
        for field in ('H0', 'Dold', 'W', 'Dnew', 'Hnew'):
            array = np.empty((len(zs), len(xs)))
            array[zi, xi] = table[field]
            arrays[field] = array
        arrays['old_H'] = arrays['H0'] - arrays['Dold']
        result[key] = dict(meta=grid_meta(xs, zs), arrays=arrays, xs=xs, zs=zs)
    return result


def render_endpoints(out, data, endpoints, cmap, files):
    for page, first in enumerate(range(0, 4, 2)):
        chunk = data['traces'][first:first + 2]
        fig, axes = plt.subplots(len(chunk), 3, figsize=(20, 6 * len(chunk)), squeeze=False)
        for row, trace in enumerate(chunk):
            item = endpoints[str(trace['id'])]
            meta, a = item['meta'], item['arrays']
            # Fixed palette WITHOUT local shading/range changes: local relief cannot amplify color.
            for col, field in enumerate(('H0', 'old_H', 'Hnew')):
                rgb = cmap(Normalize(*ELIMITS, clip=True)(.02 + 1.5 * a[field]))[..., :3]
                raster(axes[row, col], rgb, meta,
                       '%s\n%s\n%s' % (ARMS[col], short_id(trace), route_label(trace)))
        finish(fig, out, page_name('endpoint-detail', page),
               'Direct Java endpoint 2D samples; same absolute color range for EVERY endpoint/arm', files, colorbar(cmap))


def bits_equal(a, b):
    return np.asarray(a, dtype=np.float64).view(np.uint64) == np.asarray(b, dtype=np.float64).view(np.uint64)


def rounding(h):
    return 2 * np.spacing(np.maximum(1.0, np.abs(h)))


def budget_counts(h0, old_d, weight, new_d, new_h):
    tolerance = rounding(h0)
    outside = old_d == 0
    counts = dict(points=int(h0.size), negativeOldDepth=int(np.count_nonzero(old_d < 0)),
                  oldAboveCapExact=int(np.count_nonzero(old_d > CAP)),
                  oldAboveCapBeyondRounding=int(np.count_nonzero(old_d > CAP + tolerance)),
                  weightBelowZero=int(np.count_nonzero(weight < 0)),
                  weightAboveOne=int(np.count_nonzero(weight > 1)),
                  negativeNewDepth=int(np.count_nonzero(new_d < 0)),
                  newAboveOldExact=int(np.count_nonzero(new_d > old_d)),
                  newAboveOldBeyondRounding=int(np.count_nonzero(new_d > old_d + tolerance)),
                  newAboveCapExact=int(np.count_nonzero(new_d > CAP)),
                  newAboveCapBeyondRounding=int(np.count_nonzero(new_d > CAP + tolerance)),
                  newAboveCarrierExact=int(np.count_nonzero(new_h > h0)),
                  depthProductMismatch=int(np.count_nonzero(np.abs(new_d - old_d * weight) > tolerance)),
                  heightMismatch=int(np.count_nonzero(np.abs(new_h - (h0 - new_d)) > tolerance)),
                  heightSubtractionBitMismatch=int(np.count_nonzero(~bits_equal(new_h, h0 - new_d))),
                  outsideOldZeroPoints=int(np.count_nonzero(outside)),
                  outsideNonzeroNewDepth=int(np.count_nonzero(outside & (new_d != 0))),
                  outsideHeightChangedBits=int(np.count_nonzero(outside & ~bits_equal(new_h, h0))),
                  zeroNewDepthChangedBits=int(np.count_nonzero((new_d == 0) & ~bits_equal(new_h, h0))))
    gates = ('negativeOldDepth', 'oldAboveCapBeyondRounding', 'weightBelowZero', 'weightAboveOne',
             'negativeNewDepth', 'newAboveOldBeyondRounding', 'newAboveCapBeyondRounding',
             'newAboveCarrierExact', 'depthProductMismatch', 'heightMismatch',
             'outsideNonzeroNewDepth', 'outsideHeightChangedBits', 'zeroNewDepthChangedBits')
    counts.update(passed=all(counts[key] == 0 for key in gates), depthCapH=CAP,
                  tolerance='2 ULP at max(1,abs(H0)); exact counts also retained; outside uses BIT equality')
    return counts


def geometry_support(data):
    meta, a = data['meta'], data['arrays']
    coordinates = np.arange(meta['size']) * meta['stepWu']
    xx, zz = np.meshgrid(coordinates + meta['originWu'][0], coordinates + meta['originWu'][1])
    inside = np.zeros(xx.shape, dtype=bool)
    for trace in data['traces']:
        points = np.asarray(trace['points'], dtype=float)
        pairs = zip(points[:-1], points[1:]) if len(points) > 1 else [(points[0], points[0])]
        for start, end in pairs:
            mask = ((xx >= min(start[0], end[0]) - RADIUS) & (xx <= max(start[0], end[0]) + RADIUS) &
                    (zz >= min(start[1], end[1]) - RADIUS) & (zz <= max(start[1], end[1]) + RADIUS))
            if not mask.any():
                continue
            dx, dz = end - start
            norm = dx * dx + dz * dz
            t = np.clip(((xx[mask] - start[0]) * dx + (zz[mask] - start[1]) * dz) / norm, 0, 1) if norm else 0
            distance2 = (xx[mask] - start[0] - t * dx) ** 2 + (zz[mask] - start[1] - t * dz) ** 2
            inside[mask] |= distance2 < RADIUS * RADIUS
    outside = ~inside
    changed = ~bits_equal(a['carrier-height'], a['new-height'])
    nonzero = a['new-depth'] != 0
    old_changed = ~bits_equal(a['carrier-height'], a['old-height'])
    return dict(radiusWu=RADIUS, outsidePixels=int(np.count_nonzero(outside)),
                outsideNewChangedBits=int(np.count_nonzero(outside & changed)),
                outsideNewNonzeroDepth=int(np.count_nonzero(outside & nonzero)),
                outsideOldChangedBits=int(np.count_nonzero(outside & old_changed)),
                interpretation='Conservative WORLD R8 tube union of ALL frozen routes; independent of W')


def integrate(y, x):
    function = getattr(np, 'trapezoid', None)
    return float(function(y, x) if function is not None else np.trapz(y, x))


def ratio(a, b):
    return float(a / b) if b != 0 else None


def rises(height):
    differences = np.diff(height)
    tolerance = np.maximum(rounding(height[:-1]), rounding(height[1:]))
    return dict(samples=len(height), risesExact=int(np.count_nonzero(differences > 0)),
                risesBeyondRounding=int(np.count_nonzero(differences > tolerance)),
                max_rise=float(max(0, np.max(differences))) if len(differences) else 0.0,
                maxCumulativeRise=float(np.max(height - np.minimum.accumulate(height))) if len(height) else 0.0)


def validation_groups(data):
    table = data['tables']['evaluated']
    result = groups(subset(table, table['kind'] == 'validation'), ['trace'])
    require(set(result) == set(data['ids']), 'Every trace needs independent validation')
    return {key: ordered(value, 's_wu', key) for key, value in result.items()}


def longitudinal_stats(data):
    validations = validation_groups(data)
    targets = groups(data['tables']['target'], ['trace'])
    records = []
    for trace in data['traces']:
        key = str(trace['id'])
        table, target = validations[key], ordered(targets[key], 's_wu', key)
        s, h0, d = table['s_wu'], table['H0'], table['Dnew']
        residual = d[:-1] - d[1:] - ETA * (h0[:-1] - h0[1:])
        tolerance = rounding(h0[:-1]) + rounding(h0[1:])
        require(target['s_wu'][0] <= s[0] and target['s_wu'][-1] >= s[-1],
                '1D target must cover validation arc length (no extrapolation)')
        target_on_validation = np.interp(s, target['s_wu'], target['target_D'])
        old_area, new_area = integrate(table['Dold'], s), integrate(d, s)
        target_area = integrate(target['target_D'], target['s_wu'])
        target_resampled = integrate(target_on_validation, s)
        upstream = s <= s[0] + (s[-1] - s[0]) * .5
        upstream_old = integrate(table['Dold'][upstream], s[upstream])
        upstream_new = integrate(d[upstream], s[upstream])
        records.append(dict(trace=key, status=route_label(trace), source='evaluated/eval-samples.csv kind=validation',
                            validationIncludesFrozenEndpoints=bool(abs(s[0]) <= 1e-9 and abs(s[-1] - trace['lengthWu']) <= 1e-9),
                            samples=len(s), original=rises(h0), oldFailedCut=rises(h0 - table['Dold']),
                            final=rises(table['Hnew']), eta=ETA,
                            etaResidual=dict(maxSignedH=float(np.max(residual)),
                                             maxViolationH=float(max(0, np.max(residual))),
                                             exactViolations=int(np.count_nonzero(residual > 0)),
                                             beyondRounding=int(np.count_nonzero(residual > tolerance)),
                                             worstSegmentWorld=[table['world_x'][int(np.argmax(residual))].item(),
                                                                table['world_z'][int(np.argmax(residual))].item()]),
                            depthAreaHwu=dict(oldValidation=old_area, actual2DValidation=new_area,
                                              target1DTrain=target_area, target1DInterpolatedValidation=target_resampled,
                                              retentionVsOld=ratio(new_area, old_area),
                                              retentionVsTargetTrain=ratio(new_area, target_area),
                                              retentionVsTargetResampled=ratio(new_area, target_resampled)),
                            upstreamFirstHalf=dict(oldArea=upstream_old, newArea=upstream_new,
                                                   retention=ratio(upstream_new, upstream_old)),
                            maxDepthH=float(d.max()), maxDepthYproxy=float(d.max() * YPROXY),
                            endpointDepthH=float(d[-1]), endpointWorld=[float(table['world_x'][-1]),
                                                                                     float(table['world_z'][-1])],
                            tail=rises(table['Hnew'][s >= max(s[0], s[-1] - 16)])))
    update_local_validity(data, records, targets)
    return records


def update_local_validity(data, records, targets):
    for trace, record in zip(data['traces'], records):
        original_rejected = (trace.get('candidateStatus') == 'REJECTED_ORIGINAL_UPHILL' or
                             record['original']['risesBeyondRounding'] > 0)
        finite = bool(not original_rejected and record['final']['risesBeyondRounding'] == 0 and
                      record['etaResidual']['beyondRounding'] == 0 and
                      abs(record['endpointDepthH']) <= rounding(1.0) and record['validationIncludesFrozenEndpoints'])
        status = ('REJECTED_ORIGINAL_UPHILL' if original_rejected else
                  'LOCAL_FINITE_VALIDATION_PASS' if finite else 'REJECTED_LOCAL_VALIDATION')
        trace['candidateStatus'] = status
        record.update(candidateStatus=status, baselineStatus=trace['status'], status=status,
                      localFiniteValidationPassed=finite,
                      originalUphillEvidence=trace.get('originalUphillEvidence', []),
                      solverPathRejection=trace.get('solverPathRejection'),
                      targetExactlyZero=bool(np.all(targets[str(trace['id'])]['target_D'] == 0)),
                      interpretation='Per-route longitudinal validity only; no cross-section/visibility acceptance')


def sample_at_zero(table, key):
    offset = table['offset_wu']
    return float(np.interp(0, offset, table[key])) if offset[0] <= 0 <= offset[-1] else None


def sampled_offset_height(table, key, offset):
    matches = np.flatnonzero(table['offset_wu'] == offset)
    return float(table[key][matches[0]]) if len(matches) == 1 else None


def valley_record(table, key, normal):
    center = sampled_offset_height(table, key, 0.0)
    offsets = (-12., -.5, -.25, .25, .5, 12.)
    heights = {str(offset): sampled_offset_height(table, key, offset) for offset in offsets}
    rises_ = {offset: value - center if value is not None and center is not None else None
              for offset, value in heights.items()}
    minimum_offset = float(table['offset_wu'][int(np.argmin(table[key]))])
    available = center is not None and all(value is not None for value in rises_.values())
    near = available and all(rises_[str(offset)] > 0 for offset in (-.5, -.25, .25, .5))
    banks = available and all(rises_[str(offset)] > 0 for offset in (-12., 12.))
    centered = abs(minimum_offset) <= .25
    eligible = normal['defined'] and normal['eligible']
    return dict(passed=bool(eligible and near and banks and centered), normalEligible=bool(eligible),
                directRequiredOffsetsAvailable=available, riseAboveCenterH=rises_,
                bothSidesRiseAt025And05=bool(near), banksAtPlusMinus12AboveCenter=bool(banks),
                absMinimumOffsetAtMost025=centered, minimumOffsetWu=minimum_offset,
                criterion='Direct samples only: H(+/-0.25), H(+/-0.5), H(+/-12) > H(0); abs(minoffset)<=0.25; Java normal eligible',
                interpolationUsed=False, interpretation='A descending cross-slope is NOT a valley pass')


def section_stats(data):
    records = []
    for (identifier, distance), table in data['sections'].items():
        offsets = table['offset_wu']
        normal = data['normals'][(identifier, distance)]
        arms = {}
        for name, key in zip(ARMS, ('carrier_H', 'old_H', 'cut_H')):
            h = table[key]
            index = int(np.argmin(h))
            center = sample_at_zero(table, key)
            near = (np.abs(offsets) > 0) & (np.abs(offsets) < RADIUS)
            far = np.abs(offsets) >= RADIUS
            pair = {}
            for label, mask in (('leftNear', near & (offsets < 0)), ('rightNear', near & (offsets > 0)),
                                ('leftFar', far & (offsets < 0)), ('rightFar', far & (offsets > 0))):
                pair[label] = dict(samples=int(np.count_nonzero(mask)),
                                   minH=float(h[mask].min()) if mask.any() else None,
                                   maxH=float(h[mask].max()) if mask.any() else None,
                                   minMinusCenterH=float(h[mask].min() - center) if mask.any() and center is not None else None)
            arms[name] = dict(centerH=center, minimumH=float(h[index]), minimumOffsetWu=float(offsets[index]),
                              minimumWorld=[float(table['world_x'][index]), float(table['world_z'][index])],
                              pairedAtMinimumWorld=dict(H0=float(table['carrier_H'][index]),
                                                        oldH=float(table['old_H'][index]), newH=float(table['cut_H'][index])),
                              banks=pair, valley=valley_record(table, key, normal))
        old_delta = table['old_H'] - table['carrier_H']
        new_delta = table['cut_H'] - table['carrier_H']
        records.append(dict(trace=identifier, s_wu=float(distance), arms=arms,
                            normalCheck=normal, normalSource='evaluated/normal-checks.csv',
                            farbanksDefinition='abs(offset)>=8wu; near side: 0<abs(offset)<8wu',
                            farBankMaxAbsChangeH=float(np.max(np.abs(new_delta[far]))) if far.any() else None,
                            centerInterpolation='Only offset=0 H diagnostic if absent; all plotted samples are direct Java',
                            oldMinusH0Min=float(old_delta.min()), newMinusH0Min=float(new_delta.min()),
                            interpretation='Offsets/minima are observations, not a universal new-pit classifier'))
    return records


def minima_mask(height):
    # Plateaus require at least one strictly higher neighbor, not a completely flat patch.
    neighbor_min = minimum_filter(height, size=3, mode='constant', cval=np.inf)
    local = height <= neighbor_min
    higher = np.zeros(height.shape, dtype=bool)
    for dz in (-1, 0, 1):
        for dx in (-1, 0, 1):
            if dx or dz:
                higher |= height < np.roll(height, (dz, dx), axis=(0, 1))
    local &= higher
    local[[0, -1], :] = False
    local[:, [0, -1]] = False
    return local


def endpoint_stats(data, endpoints):
    records = []
    for trace in data['traces']:
        key = str(trace['id'])
        item, points = endpoints[key], np.asarray(trace['points'], dtype=float)
        a, xs, zs = item['arrays'], item['xs'], item['zs']
        masks = {arm: minima_mask(a[field]) for arm, field in zip(ARMS, ('H0', 'old_H', 'Hnew'))}
        paired = []
        for row, col in np.argwhere(masks['H0'] | masks['failed cut'] | masks['new']):
            flags = {arm: bool(mask[row, col]) for arm, mask in masks.items()}
            paired.append(dict(row=int(row), col=int(col), world=[float(xs[col]), float(zs[row])],
                               H0=float(a['H0'][row, col]), oldH=float(a['old_H'][row, col]),
                               Hnew=float(a['Hnew'][row, col]), minima=flags,
                               newOnlyRelativeH0=flags['new'] and not flags['H0'],
                               newOnlyRelativeOld=flags['new'] and not flags['failed cut'],
                               newDepthH=float(a['Dnew'][row, col])))
        globals_ = {}
        for arm, field in zip(ARMS, ('H0', 'old_H', 'Hnew')):
            row, col = np.unravel_index(int(np.argmin(a[field])), a[field].shape)
            world = np.array([xs[col], zs[row]])
            globals_[arm] = dict(H=float(a[field][row, col]), world=world.tolist(),
                                 offsetFromFrozenEndpointWu=float(np.linalg.norm(world - points[-1])),
                                 H0AtSameWorld=float(a['H0'][row, col]))
        counts = {arm: int(np.count_nonzero(mask)) for arm, mask in masks.items()}
        records.append(dict(trace=key, assessment='observed-not-final', finalPitVerdict=None,
                            localMinimaCounts=counts, pairedSameWorldIndex=paired,
                            newOnlyVsH0=int(np.count_nonzero(masks['new'] & ~masks['H0'])),
                            newOnlyVsOld=int(np.count_nonzero(masks['new'] & ~masks['failed cut'])),
                            globalMinima=globals_, shape=list(a['H0'].shape),
                            boundaryExcludedFromLocalMinima=True,
                            budget=budget_counts(a['H0'], a['Dold'], a['W'], a['Dnew'], a['Hnew']),
                            interpretation='Interior 8-neighbor sampled minima, paired with H0 at IDENTICAL world indices; '
                                           'a minimum alone is NOT evidence of a new isolated pit or continuous basin'))
    return records


def table_alignment(data):
    exported, evaluated = data['tables']['samples'], data['tables']['evaluated']
    same = all(np.array_equal(exported[key], evaluated[key]) for key in SAMPLE_HEADER)
    target_groups = groups(data['tables']['target'], ['trace'])
    train = groups(subset(exported, exported['kind'] == 'train'), ['trace'])
    validation = validation_groups(data)
    matches, independent = {}, {}
    for key in data['ids']:
        target = ordered(target_groups[key], 's_wu', key)
        source = ordered(train[key], 's_wu', key)
        matches[key] = all(np.array_equal(target[field], source[field]) for field in ('s_wu', 'H0', 'Dold'))
        train_world = set(zip(source['world_x'], source['world_z']))
        worlds = list(zip(validation[key]['world_x'], validation[key]['world_z']))
        independent[key] = sum(world not in train_world for world in worlds)
    a = data['arrays']
    frozen = dict(carrierMatchesBaselineBits=bool(bits_equal(a['carrier-height'], read_grid(BASE / 'carrier-height.f64be', 769)).all()),
                  oldMatchesFailedCutBits=bool(bits_equal(a['old-height'], read_grid(BASE / 'cut-height.f64be', 769)).all()))
    return dict(exportVsEvaluationSameRows=same, targetMatchesTrainPerTrace=matches,
                independentValidationWorldPoints=independent, frozenArms=frozen,
                passed=same and all(matches.values()) and all(independent.values()) and all(frozen.values()),
                noSortingOrDropping=True)


def solver_verdict(solver):
    # Convergence is distinct from permission to apply; ANY explicit veto wins.
    value = solver.get('converged', solver.get('success'))
    status = str(solver.get('status', '')).strip().lower()
    if value is None and status in ('converged', 'optimal', 'solved', 'success'):
        value = True
    vetoes = [key + '=false' for key in ('converged', 'success', 'apply_allowed', 'apply')
              if solver.get(key) is False]
    if solver.get('do_not_apply') is True:
        vetoes.append('do_not_apply=true')
    if status.startswith('rejected') or status in ('failed', 'infeasible', 'unconverged', 'not_converged', 'iteration_limit'):
        vetoes.append('status=' + status)
    return dict(converged=value if type(value) is bool else None,
                rejected=bool(vetoes) or value is not True, rejectionReasons=vetoes,
                rejectedPaths=rejected_paths(solver),
                policy='Every explicit success/apply veto overrides convergence; path rejection does not imply solve failure',
                reportedSolver=solver)


def performance_reports(out):
    reports = {}
    for name in ('performance.json', 'evaluated/performance.json', 'export/performance.json'):
        path = out / name
        if path.is_file():
            reports[name] = read_json(path)
    return dict(javaReportsVerbatim=reports, note='Java-owned files untouched; renderer does not invent Java timing')


def statistics(out, data, endpoints):
    a, table = data['arrays'], data['tables']['evaluated']
    old_d = a['carrier-height'] - a['old-height']
    budget = budget_counts(a['carrier-height'], old_d, a['weight'], a['new-depth'], a['new-height'])
    direct_budget = budget_counts(table['H0'], table['Dold'], table['W'], table['Dnew'], table['Hnew'])
    support, alignment = geometry_support(data), table_alignment(data)
    longitudinal, cross, ends = longitudinal_stats(data), section_stats(data), endpoint_stats(data, endpoints)
    solver = solver_verdict(data['solver'])
    integrity = (budget['passed'] and direct_budget['passed'] and alignment['passed'] and
                 all(item['budget']['passed'] for item in ends) and
                 all(support[key] == 0 for key in ('outsideNewChangedBits', 'outsideNewNonzeroDepth', 'outsideOldChangedBits')))
    finite_valid = all(t['localFiniteValidationPassed'] for t in longitudinal)
    zero_effect = bool(np.all(a['new-depth'] == 0))
    rejected = solver['rejected'] or not integrity or not finite_valid or zero_effect
    pixel_area = data['meta']['stepWu'] ** 2
    volume = dict(oldDepthSumHwu2=float(old_d.sum() * pixel_area),
                  newDepthSumHwu2=float(a['new-depth'].sum() * pixel_area),
                  retention=ratio(float(a['new-depth'].sum()), float(old_d.sum())),
                  integration='ALL grid samples * stepWu^2, full edge cells; not longitudinal trapz')
    return dict(stageStop='S1', actualMinecraftWorld=False, traceOrder=data['ids'], solver=solver,
                integrity=dict(passed=bool(integrity), scope='Input alignment, sampled field budgets and exact outside only; no locks'),
                effectPass=False,
                effectPassPolicy='Renderer cannot approve visual effect; final runner receives false, never null/true',
                candidateStatus='rejected' if rejected else 'unproven-awaiting-visual-review',
                localValidity={t['trace']: dict(candidateStatus=t['candidateStatus'],
                                              finiteValidationPassed=t['localFiniteValidationPassed'],
                                              scope='Longitudinal ONLY; valley counts and visual acceptance are separate',
                                              valleyByArm={arm: dict(passed=sum(c['arms'][arm]['valley']['passed'] for c in cross if c['trace'] == t['trace']),
                                                                     total=sum(c['trace'] == t['trace'] for c in cross)) for arm in ARMS})
                               for t in longitudinal},
                finiteValidationPassed=bool(finite_valid), exactlyZeroEffect=zero_effect,
                visibility=dict(passed=None, threshold=None, reviewRequired=True,
                                note='Depth/retention reported without a secret visibility threshold; zero is not success'),
                twoDimensionalPitAcceptance=None, budgetFullGrid=budget, budgetDirectSamples=direct_budget,
                outsideSupport=support, alignment=alignment, longitudinal=longitudinal,
                crossSections=cross, endpoints=ends, spatialDepthBudget=volume,
                targetPolicy='Train-only 1D depth target; interpolated ONLY for depth integral comparison on validation. '
                             'Never interpolated as terrain. Original and final rises/eta use independent validation.',
                performance=performance_reports(out),
                tolerancePolicy='Exact rise counts plus rounding-aware counts; 2 ULP per H endpoint, no visual threshold',
                limitations=['Finite samples only; no continuous no-pit proof', 'Route status is NOT acceptance',
                             'No renderer lock/manifest verification; main runner owns that',
                             'S1 only; no new discovery, production change, retuning or S2'])


def write_outcome(out, stats):
    records = stats['longitudinal']
    original = sum(t['original']['risesExact'] for t in records)
    final = sum(t['final']['risesExact'] for t in records)
    violations = sum(t['etaResidual']['beyondRounding'] for t in records)
    lines = [
        'S1 已停止；本模块只读求解与 Java 数据，不运行主流程，不进入 S2。',
        '范围为诊断类型空间，不是 Minecraft 实机验收。',
        '固定保留原四条路径、原顺序和全部失败状态，未按结果反选。',
        '三臂依次为 H0、旧失败下切、新候选；旧臂不是距离替换。',
        '固定配色、光照、HS=2 与高度换算；图像未逐臂归一化。',
        '求解收敛记录为 %s；候选状态为 %s。' % (stats['solver']['converged'], stats['candidateStatus']),
        '渲染侧采样完整性 passed=%s；输入锁与 manifest 由主 runner 负责。' % stats['integrity']['passed'],
        '独立 validation 原场反坡 %d 处，最终反坡 %d 处，均为精确正增量计数。' % (original, final),
        'η=0.9 约束超出舍入余量 %d 处；详细残差与 max_rise 见 stats.json。' % violations,
        '逐路线局部状态：' + '；'.join(':'.join(t['trace'].split(':')[-2:]) + '=' + t['candidateStatus'] for t in records),
        '横剖与端点极小值按同世界索引对照原场；局部极小本身不等于新增坑。',
        '效果通过=%s；可见性及二维坑需终审，Java 性能记录保留，不作 S2 声明。' % stats['effectPass'],
    ]
    require(len(lines) == 12, 'Expected exactly twelve outcome lines')
    (out / 'outcome.txt').write_text('\n'.join(lines) + '\n', encoding='utf-8')


def render(out):
    """Render existing NEWout evidence and return JSON-serializable facts for the main runner."""
    out = Path(out).resolve()
    require(out.is_dir(), 'OUT must be an existing solver/Java-evaluated directory')
    require(out != BASE.parent.resolve() and BASE.parent.resolve() not in out.parents,
            'Refusing old baseline output or its descendants')
    data, files, cmap = load_dataset(out), [], palette()
    a = data['arrays']
    heights = [a[field] for field in ('carrier-height', 'old-height', 'new-height')]
    shades = [shade(h, data['meta']['stepWu']) for h in heights]
    colors = [terrain_rgb(h, light, cmap) for h, light in zip(heights, shades)]
    endpoints = endpoint_grids(data)
    stats = statistics(out, data, endpoints)
    render_whole(out, data, shades, colors, cmap, files)
    crops = render_routes(out, data, shades, files)
    beds = render_beds(out, data, files)
    render_depth_profiles(out, data, files)
    render_sections(out, data, files)
    render_endpoints(out, data, endpoints, cmap, files)
    settings = dict(HS=HS, yProxyFactor=YPROXY, light=[-.5, -.3, 1],
                    eDefinition='.02+1.5H', eLimits=list(ELIMITS), depthLimitsH=[0, CAP], weightLimits=[0, 1],
                    shade='.65+.35*positiveDot', grayLimits=[.65, 1], noPerArmNormalization=True,
                    palette=str(PALETTE.relative_to(ROOT)),
                    paletteSha256=hashlib.sha256(PALETTE.read_bytes()).hexdigest(),
                    seedCrops=crops, cropReselection=False, bedDetail=beds,
                    endpointColors='Unshaded absolute project palette; identical e range on every endpoint',
                    crossSectionsOrder=[[key[0], float(key[1])] for key in data['sections']],
                    files=files, manifestAndLocksWritten=False)
    stats['render'] = settings
    write_json(out / 'stats.json', stats)
    write_outcome(out, stats)
    require(all((out / name).stat().st_size > 0 for name in files), 'Empty output figure')
    return dict(stats=stats, render=settings, integrity=stats['integrity'], effectPass=stats['effectPass'],
                candidateStatus=stats['candidateStatus'], stageStop='S1',
                files=files + ['stats.json', 'outcome.txt'])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('out', help='Existing NEWout after solver and Java evaluation; no Java is run')
    args = parser.parse_args()
    result = render(args.out)
    print(json.dumps({key: result[key] for key in ('candidateStatus', 'integrity', 'effectPass', 'stageStop')},
                     ensure_ascii=False))
    return 2 if result['candidateStatus'] == 'rejected' else 0


if __name__ == '__main__':
    sys.exit(main())
