"""Stage A only: render caller-provided finite shared descent graphs, never carve.

API: render(out) -> dict, reading out/data. No Java, tracing, area accumulation,
threshold selection, or production entry point is executed by this module.
"""
import argparse
from collections import Counter, defaultdict
import csv
import hashlib
import json
from pathlib import Path

import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.collections import LineCollection
from matplotlib.colors import ListedColormap, Normalize
import numpy as np

ROOT = Path(__file__).resolve().parents[2]
FROZEN = ROOT / 'build/strong-window-erosion-20261104-final/data'
PALETTE = ROOT / 'build/fast-erosion-scale-only-20261103-final/data/project-palette.csv'
FOOTER = ('仅共享下降关系，未刻槽 | Stage A: all edges, no area/threshold/hierarchy. '
          'All components included; full graph may be dense (不筛选阶段A).\n'
          'Offline non-MC finite window; no S2. Window boundary is NOT an outlet. '
          'Uniform diagnostic line width; root colors are NOT catchment/flow.\n'
          'Original H0 only; HS=2; e=.02+1.5H, palette [-.35,1.2]; '
          'shade=.65+.35*positiveDot, light=[-.5,-.3,1].')
SCHEMAS = {
    'graph-nodes.csv': 'id,ix,iz,x,z,H0,next_id,state,indegree,root_id,downstream_length_wu',
    'edges.csv': 'source,target,x0,z0,x1,z1,H0_source,H0_target,samples,min_drop',
    'junctions.csv': 'region,node,x,z,indegree,next,root_id,downstream_length_wu',
    'stop-audit.csv': ('id,cell_x,cell_z,old_status,x,z,old_length_wu,classification,'
                       'witness_length_wu,witness_final_rises,graph_nearest_id,graph_state'),
}


def sha(path):
    digest = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            digest.update(chunk)
    return digest.hexdigest()


def read_csv(path, required):
    with path.open(encoding='utf-8-sig', newline='') as stream:
        reader = csv.DictReader(stream)
        missing = set(required.split(',')) - set(reader.fieldnames or [])
        if missing:
            raise ValueError(f'{path}: missing columns {sorted(missing)}')
        return list(reader)


def number(row, key):
    value = float(row[key])
    if not np.isfinite(value):
        raise ValueError(f'Non-finite {key}: {row}')
    return value


def xy(row):
    return number(row, 'x'), number(row, 'z')


def root_color(key):
    # Stable per root, independent of row order or component selection.
    value = int(hashlib.sha256(str(key).encode()).hexdigest()[:8], 16)
    return plt.get_cmap('turbo')((value % 1000003) / 1000003)


def load_carrier(data):
    meta = json.loads((data / 'sampling.json').read_text(encoding='utf-8-sig'))
    n, step = int(meta['size']), float(meta['stepWu'])
    if n < 2 or not np.isfinite(step) or step <= 0 or meta.get('HS', 2) != 2:
        raise ValueError('Expected finite positive sampling, size >=2, HS=2')
    x, z = map(float, meta['originWu'])
    h = np.fromfile(data / 'carrier-height.f64be', dtype='>f8').reshape(n, n)
    if not np.isfinite(h).all() or not np.isfinite([x, z]).all():
        raise ValueError('Invalid original carrier H0 or origin')
    palette = data / 'project-palette.csv'
    if not palette.exists():
        palette = PALETTE
    colors = np.loadtxt(palette, delimiter=',', skiprows=1)
    cmap = ListedColormap(colors[:, 1:4] / 255)
    gz, gx = np.gradient(h * 354.66, step * 2)
    light = np.array([-.5, -.3, 1.])
    light /= np.linalg.norm(light)
    dot = (-gx * light[0] - gz * light[1] + light[2]) / np.sqrt(1 + gx*gx + gz*gz)
    rgb = cmap(Normalize(-.35, 1.2, clip=True)(.02 + 1.5*h))[..., :3]
    rgb *= (.65 + .35 * np.maximum(dot, 0))[..., None]
    span = (n - 1) * step
    extent = (x-step/2, x+span+step/2, z+span+step/2, z-step/2)
    bounds = (x, x+span, z, z+span)
    return meta, rgb, extent, bounds, palette


def load_old():
    routes = read_csv(FROZEN / 'routes.csv', 'trace,vertex,x,z')
    traces = read_csv(FROZEN / 'traces.csv', 'id,start_x,start_z,status,length_wu,vertices')
    groups = defaultdict(list)
    for row in routes:
        groups[row['trace']].append((int(row['vertex']), xy(row)))
    paths = []
    for vertices in groups.values():
        vertices.sort(key=lambda item: item[0])
        points = np.asarray([point for _, point in vertices])
        if len(points) > 1:
            paths.append(points)
    return paths, routes, traces


def load_graph(data):
    tables = {name: read_csv(data / name, schema) for name, schema in SCHEMAS.items()}
    nodes = {row['id']: row for row in tables['graph-nodes.csv']}
    if len(nodes) != len(tables['graph-nodes.csv']):
        raise ValueError('Duplicate graph node IDs')
    incoming, outgoing = defaultdict(list), {}
    segments, colors = [], []
    for row in nodes.values():
        xy(row)
        for key in ('H0', 'indegree', 'downstream_length_wu'):
            number(row, key)
    for edge in tables['edges.csv']:
        src, dst = edge['source'], edge['target']
        if src not in nodes or dst not in nodes or src in outgoing:
            raise ValueError(f'Invalid single-receiver graph edge {src} -> {dst}')
        if nodes[src]['next_id'] != dst:
            raise ValueError(f'Edge and next_id disagree: {src} -> {dst}')
        incoming[dst].append(src)
        outgoing[src] = dst
        segment = [(number(edge, 'x0'), number(edge, 'z0')),
                   (number(edge, 'x1'), number(edge, 'z1'))]
        if not np.allclose(segment, [xy(nodes[src]), xy(nodes[dst])], rtol=0, atol=1e-7):
            raise ValueError('Edge endpoints do not match graph node coordinates')
        segments.append(segment)
        colors.append(root_color(nodes[src]['root_id']))
    junctions = tables['junctions.csv']
    if len(junctions) != 4 or len({r['region'] for r in junctions}) != 4:
        raise ValueError('Caller must supply exactly four fixed, distinct junction regions')
    return tables, nodes, incoming, outgoing, segments, colors


def canvas(ax, context, title, bounds=None, background=True):
    if background:
        ax.imshow(context['rgb'], extent=context['extent'], origin='upper',
                  interpolation='nearest')
    ax.set_facecolor('white')
    x0, x1, z0, z1 = bounds or context['bounds']
    ax.set(title=title, xlim=(x0, x1), ylim=(z1, z0),
           xlabel='World x (wu)', ylabel='World z (wu)', aspect='equal')
    ax.ticklabel_format(style='plain', useOffset=False)


def graph_lines(ax, context, alpha=.38, width=.35):
    collection = LineCollection(context['segments'], colors=context['colors'],
                                linewidths=width, alpha=alpha, rasterized=True)
    ax.add_collection(collection)


def old_lines(ax, context):
    ax.add_collection(LineCollection(context['old_paths'], colors='#072a4a',
                                    linewidths=.28, alpha=.3, rasterized=True))


def finish(fig, out, name, title):
    fig.suptitle(title, fontsize=15)
    fig.text(.02, .012, FOOTER, fontsize=8)
    fig.tight_layout(rect=(0, .105, 1, .95))
    fig.savefig(out / name, dpi=150, facecolor='white')
    plt.close(fig)


def overview(out, context):
    fig, axes = plt.subplots(1, 2, figsize=(20, 11))
    canvas(axes[0], context, 'Old independent routes: ALL frozen paths, thin uniform lines')
    old_lines(axes[0], context)
    canvas(axes[1], context, 'Shared graph: ALL edges, colored by root component')
    graph_lines(axes[1], context)
    finish(fig, out, 'graph-comparison.png', '仅共享下降关系，未刻槽 | Old vs shared, same original H0')
    for name, background in [('graph-overview.png', True), ('puregraph.png', False)]:
        fig, ax = plt.subplots(figsize=(13, 13))
        canvas(ax, context, 'ALL root components; uniform width, not hierarchy',
               background=background)
        graph_lines(ax, context, alpha=.6 if not background else .38)
        nodes = list(context['nodes'].values())
        if nodes:
            points = np.array([xy(row) for row in nodes])
            ax.scatter(*points.T, c=[root_color(r['root_id']) for r in nodes],
                       s=.3, alpha=.4, linewidths=0, rasterized=True)
        finish(fig, out, name, '仅共享下降关系，未刻槽 | Unfiltered Stage A graph')


def stop_map(out, context, stops):
    fig, ax = plt.subplots(figsize=(15, 13))
    canvas(ax, context, 'Caller classifications (verbatim); graph terminal states separately marked')
    groups = defaultdict(list)
    for row in stops:
        groups[row['classification']].append(xy(row))
    for index, (label, points) in enumerate(sorted(groups.items())):
        points = np.array(points)
        ax.scatter(*points.T, s=9, color=plt.get_cmap('tab20')(index % 20),
                   marker='o', alpha=.8, linewidths=0, label=f'old: {label} ({len(points)})')
    states = defaultdict(list)
    for node_id, row in context['nodes'].items():
        if node_id not in context['outgoing']:
            states[row['state']].append(xy(row))
    for index, (label, points) in enumerate(sorted(states.items())):
        points = np.array(points)
        ax.scatter(*points.T, s=15, marker=['x', '+', 's', '^'][index % 4],
                   color=plt.get_cmap('Dark2')(index % 8), alpha=.75,
                   label=f'graph terminal: {label} ({len(points)})')
    ax.legend(loc='upper left', bbox_to_anchor=(1.01, 1), fontsize=7)
    finish(fig, out, 'stop-map.png', '仅共享下降关系，未刻槽 | Stops are not invented basin/outlet evidence')


def suffix(context, start, limit=80.):
    nodes, outgoing = context['nodes'], context['outgoing']
    ids, lengths, seen = [start], [0.], {start}
    while ids[-1] in outgoing:
        nxt = outgoing[ids[-1]]
        if nxt in seen:
            raise ValueError(f'Cycle in downstream suffix at node {nxt}')
        distance = float(np.linalg.norm(np.subtract(xy(nodes[nxt]), xy(nodes[ids[-1]]))))
        if lengths[-1] + distance > limit:
            break  # No invented clipped H0 node or interpolated profile sample.
        seen.add(nxt)
        ids.append(nxt)
        lengths.append(lengths[-1] + distance)
    return ids, lengths


def directed_edge(ax, a, b, color):
    ax.plot([a[0], b[0]], [a[1], b[1]], color=color, lw=1.15)
    tail = np.array(b) - .28 * np.subtract(b, a)
    ax.annotate('', xy=b, xytext=tail,
                arrowprops=dict(arrowstyle='->', color=color, lw=1.2))


def junction_detail(fig, axes, context, junction):
    q = junction['node']
    cx, cz = xy(junction)
    bounds = (cx-60, cx+60, cz-60, cz+60)
    canvas(axes[0], context, 'Shared: immediate incoming + <=80wu node suffix', bounds)
    graph_lines(axes[0], context, alpha=.14, width=.3)
    canvas(axes[1], context, 'Old: ALL frozen routes, same fixed q +/-60wu window', bounds)
    old_lines(axes[1], context)
    if q not in context['nodes']:
        axes[0].text(.02, .95, f'Caller region {junction["region"]}: no graph junction ({q})',
                     transform=axes[0].transAxes, va='top')
        axes[2].text(.1, .5, 'No graph node; no fabricated profile')
        return dict(region=junction['region'], node=q, available=False, boundsWu=bounds)
    nodes = context['nodes']
    if not np.allclose((cx, cz), xy(nodes[q]), rtol=0, atol=1e-7):
        raise ValueError(f'Junction coordinates do not match node {q}')
    ids, lengths = suffix(context, q)
    incoming = context['incoming'].get(q, [])
    for index, src in enumerate(incoming):
        color = plt.get_cmap('tab10')(index % 10)
        directed_edge(axes[0], xy(nodes[src]), xy(nodes[q]), color)
        distance = float(np.linalg.norm(np.subtract(xy(nodes[q]), xy(nodes[src]))))
        # Node H0 samples only: dots, NOT a claimed actual midline profile.
        axes[2].scatter([-distance, 0], [number(nodes[src], 'H0'), number(nodes[q], 'H0')],
                        color=color, s=22, label=f'incoming node {src} -> {q}')
        axes[0].annotate(src, xy(nodes[src]), fontsize=7)
    for a, b in zip(ids, ids[1:]):
        directed_edge(axes[0], xy(nodes[a]), xy(nodes[b]), '#001a70')
    axes[0].scatter([cx], [cz], s=35, facecolors='white', edgecolors='black', zorder=5)
    axes[0].annotate(f'q / shared target ID={q}\nnext={nodes[q]["next_id"]}', (cx, cz),
                     xytext=(8, 10), textcoords='offset points', fontsize=8)
    axes[2].scatter(lengths, [number(nodes[i], 'H0') for i in ids], s=16,
                    color='#001a70', label='shared suffix: graph-node H0 samples')
    axes[2].axvline(0, color='gray', lw=.6)
    axes[2].set(title='Actual graph-node H0 only; NOT continuous/midline samples',
                xlabel='Distance from q (wu); incoming negative', ylabel='Original H0')
    axes[2].legend(fontsize=6, loc='best')
    return dict(region=junction['region'], node=q, available=True, boundsWu=bounds,
                immediateIncoming=incoming, suffixIds=ids, suffixLengthWu=lengths[-1],
                profileEvidence='actual graph-node H0 only, no interpolated midline',
                suppliedJunction=junction)


def junction_pages(out, context, junctions):
    reports = []
    for index, junction in enumerate(junctions, 1):
        fig, axes = plt.subplots(1, 3, figsize=(23, 9))
        reports.append(junction_detail(fig, axes, context, junction))
        finish(fig, out, f'junction-{index}.png',
               f'仅共享下降关系，未刻槽 | Fixed caller region {junction["region"]}')
    return reports


def distribution(values):
    values = list(values)
    return dict(count=len(values), longest=max(values) if values else None,
                median=float(np.median(values)) if values else None)


def evidence_json(data):
    result = {}
    # Preserve Java evidence verbatim, including failures; never convert it to a pass.
    for path in sorted(data.glob('*.json')):
        if path.name == 'sampling.json':
            continue
        result[path.name] = dict(sha256=sha(path),
                                content=json.loads(path.read_text(encoding='utf-8-sig')))
    return result


def statistics(context, tables, meta, junctions):
    nodes = list(context['nodes'].values())
    roots = Counter(row['root_id'] for row in nodes)
    terminals = [row for key, row in context['nodes'].items() if key not in context['outgoing']]
    mismatch = [key for key, row in context['nodes'].items()
                if int(row['indegree']) != len(context['incoming'].get(key, []))]
    x0, x1, z0, z1 = context['bounds']
    boundary = [r for r in nodes if min(abs(float(r['x'])-x0), abs(float(r['x'])-x1),
                                       abs(float(r['z'])-z0), abs(float(r['z'])-z1)) <= 1e-6]
    return dict(stage='A only; no B area/threshold; no C carving', sampling=meta,
                actualMinecraftWorld=False, S2Executed=False, productionWiring=False,
                allEdgesRetained=True, edgeCount=len(context['segments']), nodeCount=len(nodes),
                graphStateCounts=dict(Counter(r['state'] for r in nodes)),
                terminalStateCounts=dict(Counter(r['state'] for r in terminals)),
                boundaryNodeCount=len(boundary), boundaryDefinition='exact original raster bounds +/-1e-6wu',
                boundaryIsOutlet=False, oldTraceCount=len(context['old_traces']),
                oldRoutePointCount=context['old_point_count'],
                oldStatusCounts=dict(Counter(r['status'] for r in context['old_traces'])),
                oldLengthWu=distribution(number(r, 'length_wu') for r in context['old_traces']),
                downstreamLengthWu=distribution(number(r, 'downstream_length_wu') for r in nodes),
                rootComponentSizes=dict(sorted(roots.items())), componentSize=distribution(roots.values()),
                callerStopClassificationCounts=dict(Counter(r['classification'] for r in tables['stop-audit.csv'])),
                callerStopGraphStateCounts=dict(Counter(r['graph_state'] for r in tables['stop-audit.csv'])),
                indegreeMismatchNodeIds=mismatch, fixedJunctions=junctions,
                profileLimitation='Node H0 dots only; Java edge checks are not sampled midline profiles.',
                graphDensityNote='All components/edges included: 不筛选阶段A; density is not fixed by pruning.',
                lineWidthMeaning='uniform diagnostic only, not hierarchy, area, or flow',
                acceptance=False, carrier='original frozen H0, not strong-height')


def render(out):
    """Read existing artifacts, write Stage-A PNGs and renderer-statistics.json."""
    out = Path(out).resolve()
    data = out / 'data'
    plt.rcParams['font.sans-serif'] = ['Microsoft YaHei', 'SimHei', 'DejaVu Sans']
    plt.rcParams['axes.unicode_minus'] = False
    meta, rgb, extent, bounds, palette = load_carrier(data)
    tables, nodes, incoming, outgoing, segments, colors = load_graph(data)
    old_paths, routes, traces = load_old()
    context = dict(rgb=rgb, extent=extent, bounds=bounds, nodes=nodes, incoming=incoming,
                   outgoing=outgoing, segments=segments, colors=colors, old_paths=old_paths,
                   old_traces=traces, old_point_count=len(routes))
    frozen_carrier = FROZEN / 'carrier-height.f64be'
    carrier_matches = sha(data / 'carrier-height.f64be') == sha(frozen_carrier)
    if not carrier_matches:
        raise ValueError('carrier-height differs from frozen original H0; refusing mislabeled background')
    if meta['size'] != 769 or not np.isclose(meta['stepWu'], 2.382812497780833, rtol=0, atol=1e-12):
        raise ValueError('Expected frozen 769-square original sampling')
    if not np.allclose(meta['originWu'], [-4158.0078086275535, -4279.531246014376], rtol=0, atol=1e-9):
        raise ValueError('Unexpected original window origin')
    evidence = evidence_json(data)
    overview(out, context)
    stop_map(out, context, tables['stop-audit.csv'])
    junctions = junction_pages(out, context, tables['junctions.csv'])
    stats = statistics(context, tables, meta, junctions)
    stats['javaEvidenceFiles'] = evidence
    stats['javaChecksPresent'] = any('check' in name.lower() for name in evidence)
    stats['frozenCarrierSha256Matches'] = carrier_matches
    inputs = list(data.glob('*.csv')) + list(data.glob('*.json'))
    inputs += [data / 'carrier-height.f64be', palette, frozen_carrier,
               FROZEN / 'routes.csv', FROZEN / 'traces.csv']
    stats['inputSha256'] = {str(p): sha(p) for p in inputs}
    stats['outputs'] = ['graph-comparison.png', 'graph-overview.png', 'puregraph.png',
                        'stop-map.png'] + [f'junction-{i}.png' for i in range(1, 5)]
    (out / 'renderer-statistics.json').write_text(
        json.dumps(stats, ensure_ascii=False, indent=2, allow_nan=False) + '\n', encoding='utf-8')
    return stats


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('out', type=Path, help='Existing output directory containing data/')
    args = parser.parse_args()
    print(json.dumps(render(args.out), ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
