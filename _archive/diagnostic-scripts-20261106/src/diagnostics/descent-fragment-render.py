"""Read-only Stage A diagnostics: render(out, audit, baseline) -> dict.

out/data must already contain repaired graph-nodes.csv, polyline-segments.csv
(source,target,segment,x0,z0,x1,z1,H0_source,H0_target), repair-actions.csv.
Paths audit/baseline accept either a run directory or its data directory.
Actions use id,old_next,new_next,kind,reason,vertexcount,length,finervalid;
bridge kinds are SHARED_DESCENT_SEARCH and legacy AUDIT_WITNESS_POLYLINE.
No success/length filter. Both graphs prefer authoritative polyline-segments.csv;
edges.csv is a legacy fallback only when baseline has no segment geometry.
No solver, H0 evaluation, B/C, production invocation, or CLI is provided.
"""
from collections import Counter, defaultdict
import csv
import hashlib
import json
from pathlib import Path
import shutil

import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib.collections import LineCollection
from matplotlib.colors import ListedColormap, Normalize
import numpy as np

NODE_SCHEMA = 'id,ix,iz,x,z,H0,next_id,state,indegree,root_id,downstream_length_wu'
SEGMENT_SCHEMA = 'source,target,segment,x0,z0,x1,z1,H0_source,H0_target'
ACTION_SCHEMA = 'id,old_next,new_next,kind,reason,vertexcount,length,finervalid'
BRIDGE_KINDS = {'SHARED_DESCENT_SEARCH', 'AUDIT_WITNESS_POLYLINE'}
PALETTE = (Path(__file__).resolve().parents[2] /
           'build/fast-erosion-scale-only-20261103-final/data/project-palette.csv')
FOOTER = ('Stage A only / 仅连通；H0不改、未刻槽、不生产；全部拓扑边，无阈值、无等级。'
          '全图统一细线；局部粗线仅定位真实path/target/下游，不代表等级。'
          '颜色仅为 root 标识，颜色变化不代表修复强度。\n'
          '相同原始 H0；HS=2；e=.02+1.5H；palette [-.35,1.2]；'
          'shade=.65+.35*positiveDot；light=[-.5,-.3,1]。\n'
          '有限窗口诊断，不是形态验收；下降见证不等于已合并；不执行 B/C。')


def digest(path):
    value = hashlib.sha256()
    with path.open('rb') as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b''):
            value.update(chunk)
    return value.hexdigest()


def table(path, required=''):
    with path.open(encoding='utf-8-sig', newline='') as stream:
        reader = csv.DictReader(stream)
        fields = reader.fieldnames or []
        missing = set(filter(None, required.split(','))) - set(fields)
        if missing:
            raise ValueError(f'{path}: missing columns {sorted(missing)}')
        return fields, list(reader)


def write_rows(path, fields, rows):
    with path.open('w', encoding='utf-8-sig', newline='') as stream:
        writer = csv.DictWriter(stream, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)


def data_dir(path):
    path = Path(path).resolve()
    return path / 'data' if (path / 'data').is_dir() else path


def finite(row, key):
    value = float(row[key])
    if not np.isfinite(value):
        raise ValueError(f'Nonfinite {key}: {row}')
    return value


def xy(row):
    return finite(row, 'x'), finite(row, 'z')


def color(root):
    value = int(hashlib.sha256(str(root).encode()).hexdigest()[:8], 16)
    return plt.get_cmap('turbo')((value % 1000003) / 1000003)


def copy_exact(source, target):
    if source.resolve() == target.resolve():
        return
    if target.exists() and digest(source) != digest(target):
        raise ValueError(f'Refusing to overwrite different evidence: {target}')
    target.parent.mkdir(parents=True, exist_ok=True)
    if not target.exists():
        shutil.copyfile(source, target)


def carrier(base, data):
    for name in ('sampling.json', 'carrier-height.f64be'):
        copy_exact(base / name, data / name)
    palette_source = base / 'project-palette.csv'
    if not palette_source.is_file():
        palette_source = PALETTE
    copy_exact(palette_source, data / 'project-palette.csv')
    meta = json.loads((data / 'sampling.json').read_text(encoding='utf-8-sig'))
    n, step = int(meta['size']), float(meta['stepWu'])
    x, z = map(float, meta['originWu'])
    if n < 2 or step <= 0 or not np.isfinite([step, x, z]).all() or meta.get('HS', 2) != 2:
        raise ValueError('Invalid sampling or HS != 2')
    h = np.fromfile(data / 'carrier-height.f64be', dtype='>f8').reshape(n, n)
    if not np.isfinite(h).all():
        raise ValueError('Nonfinite original H0')
    palette = np.loadtxt(data / 'project-palette.csv', delimiter=',', skiprows=1)
    cmap = ListedColormap(palette[:, 1:4] / 255)
    # Same e mapping, gradient, light, and shade as shared-descent-render.py.
    gz, gx = np.gradient(h * 354.66, step * 2)
    light = np.array([-.5, -.3, 1.])
    light /= np.linalg.norm(light)
    dot = (-gx*light[0] - gz*light[1] + light[2]) / np.sqrt(1+gx*gx+gz*gz)
    rgb = cmap(Normalize(-.35, 1.2, clip=True)(.02+1.5*h))[..., :3]
    rgb *= (.65+.35*np.maximum(dot, 0))[..., None]
    span = (n-1)*step
    return dict(rgb=rgb, bounds=(x, x+span, z, z+span), sampling=meta,
                extent=(x-step/2, x+span+step/2, z+span+step/2, z-step/2))


def nodes(path):
    fields, rows = table(path, NODE_SCHEMA)
    if fields != NODE_SCHEMA.split(','):
        raise ValueError(f'{path}: graph-node schema/order differs from original')
    result = {row['id']: row for row in rows}
    if len(result) != len(rows):
        raise ValueError('Duplicate node IDs')
    for row in rows:
        xy(row)
        finite(row, 'H0')
    return result


def graph(node_map, rows, repaired=False):
    groups = defaultdict(list)
    for row in rows:
        key = row['source'], row['target']
        if any(item not in node_map for item in key):
            raise ValueError(f'Unknown graph endpoint {key}')
        groups[key].append(row)
    expected = {(key, row['next_id']) for key, row in node_map.items()
                if row['next_id'] in node_map}
    if set(groups) != expected:
        raise ValueError('Geometry must cover exactly all next_id edges, without threshold')
    segments, colors = [], []
    for (src, dst), pieces in groups.items():
        if repaired:
            pieces.sort(key=lambda row: int(row['segment']))
            ids = [int(row['segment']) for row in pieces]
            if len(set(ids)) != len(ids) or any(b-a != 1 for a, b in zip(ids, ids[1:])):
                raise ValueError(f'Duplicate/gapped polyline segments: {src}:{dst}')
        previous = xy(node_map[src])
        for row in pieces:
            a = finite(row, 'x0'), finite(row, 'z0')
            b = finite(row, 'x1'), finite(row, 'z1')
            finite(row, 'H0_source'); finite(row, 'H0_target')
            if not np.allclose(a, previous, rtol=0, atol=1e-7):
                raise ValueError(f'Discontinuous geometry: {src}:{dst}')
            segments.append((a, b))
            colors.append(color(node_map[src]['root_id']))
            previous = b
        if not np.allclose(previous, xy(node_map[dst]), rtol=0, atol=1e-7):
            raise ValueError(f'Polyline target mismatch: {src}:{dst}')
    return dict(nodes=node_map, segments=segments, colors=colors, groups=groups)


def canvas(ax, ctx, title, bounds=None):
    ax.imshow(ctx['rgb'], extent=ctx['extent'], origin='upper', interpolation='nearest')
    x0, x1, z0, z1 = bounds or ctx['bounds']
    ax.set(title=title, xlim=(x0, x1), ylim=(z1, z0), aspect='equal',
           xlabel='World x (wu)', ylabel='World z (wu)')
    ax.ticklabel_format(style='plain', useOffset=False)


def lines(ax, graph_data, monochrome=False, muted=False):
    colors = '#888888' if muted else '#102d46' if monochrome else graph_data['colors']
    ax.add_collection(LineCollection(graph_data['segments'], colors=colors,
                      linewidths=.35, alpha=.30 if muted else .45, rasterized=True))


def edge_segments(net, key):
    return [((finite(r, 'x0'), finite(r, 'z0')),
             (finite(r, 'x1'), finite(r, 'z1'))) for r in net['groups'].get(key, [])]


def highlight_bridge(ax, net, source, target, downstream=12):
    """Locate supplied geometry only; never substitute endpoint chords."""
    path = edge_segments(net, (source, target))
    if path:
        ax.add_collection(LineCollection(path, colors='#d62521', linewidths=1.8, zorder=4))
    current, seen, tail = target, {source}, []
    for _ in range(min(downstream, 12)):
        if current in seen or current not in net['nodes']:
            break
        seen.add(current)
        nxt = net['nodes'][current]['next_id']
        pieces = edge_segments(net, (current, nxt))
        if not pieces:
            break
        tail.extend(pieces)
        current = nxt
    if tail:
        ax.add_collection(LineCollection(tail, colors='#1265b0', linewidths=1.3, zorder=3))
    if target in net['nodes']:
        x, z = xy(net['nodes'][target])
        ax.scatter([x], [z], s=36, facecolors='none', edgecolors='#1265b0', zorder=5)
        row = net['nodes'][target]
        if edge_segments(net, (target, row['next_id'])):
            status = 'target: existing downstream'
        elif int(row['next_id']) < 0:
            status = ('target: domain boundary' if row['state'] == 'DOMAIN_BOUNDARY'
                      else 'target is unresolved stop')
        else:
            status = 'target: downstream geometry unavailable'
        ax.annotate(status, (x, z), xytext=(4, -12), textcoords='offset points', fontsize=7)


def bridge_bounds(ctx, net, source, target):
    """Source-centred +/-16wu crop only; preserve shared world coordinates/H0."""
    x, z = xy(net['nodes'][source])
    x0, x1, z0, z1 = ctx['bounds']
    return (max(x0, x-16), min(x1, x+16),
            max(z0, z-16), min(z1, z+16))


def finish(fig, out, name, title):
    fig.suptitle(title, fontsize=13)
    root_note = getattr(fig, '_root_note', '')
    fig.text(.02, .015, FOOTER + ('\n' + root_note if root_note else ''), fontsize=8)
    fig.tight_layout(rect=(0, .115, 1, .95))
    fig.savefig(out / name, dpi=150, facecolor='white')
    plt.close(fig)
    return name


def comparisons(out, ctx, before, after):
    outputs = []
    for mono, name in [(False, 'graph-before-after.png'),
                       (True, 'graph-before-after-monochrome.png')]:
        fig, axes = plt.subplots(1, 2, figsize=(20, 11))
        fig._root_note = ctx['rootStatistics']['summary']
        for ax, label, net in zip(axes, ('BEFORE: original roots', 'AFTER: repaired roots'),
                                  (before, after)):
            canvas(ax, ctx, label + (' / same monochrome' if mono else ' / root IDs only'))
            lines(ax, net, mono)
        outputs.append(finish(fig, out, name, 'All edges / same H0 / uniform thin width'))
    return outputs


def fixed_pages(out, ctx, before, after, junctions):
    if len(junctions) != 4 or len({r['region'] for r in junctions}) != 4:
        raise ValueError('Expected original four distinct fixed junctions')
    outputs, details = [], []
    for page in range(2):
        fig, axes = plt.subplots(2, 2, figsize=(15, 15))
        for pair, row in zip(axes, junctions[page*2:page*2+2]):
            x, z = xy(row)
            bounds = (x-60, x+60, z-60, z+60)
            for ax, label, net in zip(pair, ('BEFORE', 'AFTER'), (before, after)):
                canvas(ax, ctx, f'{label}: fixed region {row["region"]}, node {row["node"]}', bounds)
                lines(ax, net)
                ax.scatter([x], [z], s=24, facecolors='none', edgecolors='black')
            details.append(dict(row, xmin=x-60, xmax=x+60, zmin=z-60, zmax=z+60))
        name = 'fixed-four-closeups.png' if page == 0 else 'fixed-four-closeups-page2.png'
        outputs.append(finish(fig, out, name, f'Original four junctions, fixed +/-60wu / page {page+1}/2'))
    write_rows(out/'data/fixed-junction-windows.csv', list(details[0]), details)
    return outputs


def bridge_cases(out, ctx, before, after, fields, actions):
    if not {'id', 'new_next', 'kind'}.issubset(fields):
        raise ValueError('repair-actions.csv requires id,new_next,kind')
    candidates = [dict(r, source=r['id'], target=r['new_next']) for r in actions
                  if r['kind'] in BRIDGE_KINDS]
    for row in candidates:
        if (row['source'], row['target']) not in after['groups']:
            raise ValueError(f'Bridge action has no actual polyline geometry: {row}')
    x0, x1, z0, z1 = ctx['bounds']
    mx, mz = (x0+x1)/2, (z0+z1)/2
    selected = []
    for quadrant in range(4):
        pool = []
        for index, row in enumerate(candidates):
            src = after['nodes'].get(row['source'], before['nodes'].get(row['source']))
            if src is None:
                raise ValueError(f'Bridge action unknown source: {row}')
            x, z = xy(src)
            if (x0 <= x <= x1 and z0 <= z <= z1
                    and int(x >= mx)+2*int(z >= mz) == quadrant):
                pool.append((z, x, index, row))
        choice = min(pool, key=lambda item: item[:3]) if pool else None
        selected.append((quadrant, choice))
    fig, axes = plt.subplots(4, 2, figsize=(15, 25))
    details = []
    for pair, (region, choice) in zip(axes, selected):
        if choice is None:
            for ax in pair:
                canvas(ax, ctx, f'Quadrant {region}: NoCase')
                ax.text(.5, .5, 'NoCase', transform=ax.transAxes, ha='center')
            details.append(dict(region=region, source='', target='', x='', z='', selection='NoCase'))
            continue
        z, x, _, row = choice
        bounds = bridge_bounds(ctx, after, row['source'], row['target'])
        for ax, label, net in zip(pair, ('BEFORE', 'AFTER'), (before, after)):
            canvas(ax, ctx, f'{label} Q{region}: {row["source"]}:{row["target"]}', bounds)
            lines(ax, net, muted=True)
            highlight_bridge(ax, net, row['source'], row['target'])
        details.append(dict(region=region, source=row['source'], target=row['target'], x=x, z=z,
                            selection='first in-viewport row-major bridge; no success/length filter'))
    write_rows(out/'data/repair-case-selection.csv', list(details[0]), details)
    return finish(fig, out, 'repair-cases.png', 'Four geometry representatives ONLY, not all-quality evidence')


def internal_roots(node_map):
    return {key for key, row in node_map.items()
            if int(row['next_id']) < 0 and row['state'] != 'DOMAIN_BOUNDARY'}


def root_statistics(data, audited, actions, old_nodes, new_nodes):
    checks_path = data / 'checks.json'
    checks = json.loads(checks_path.read_text(encoding='utf-8-sig')) if checks_path.exists() else {}
    before, after = len(internal_roots(old_nodes)), len(internal_roots(new_nodes))
    historical = len(audited)
    witness = sum(r['classification'] == 'DESCENT_WITNESS_GRID_STOP' for r in audited)
    bridges = [r for r in actions if r['kind'] in BRIDGE_KINDS]
    for row in bridges:
        source, target = row['id'], row['new_next']
        if source not in new_nodes or target not in new_nodes or new_nodes[source]['next_id'] != target:
            raise ValueError(f'Bridge action disagrees with actual graph next_id: {row}')
    connected = len(bridges)
    disconnected = sum(r['kind'] == 'DISCONNECT' for r in actions)
    if 'residualInternalRoots' in checks and int(checks['residualInternalRoots']) != after:
        raise ValueError('checks residualInternalRoots differs from actual graph state/next_id')
    pct = 100*witness/historical if historical else 0
    summary = (f'本轮内部根 {before}→{after}；本轮merge {connected}；'
               f'本轮DISCONNECT actions {disconnected}。历史audit {historical} / '
               f'witness {witness} ({pct:.1f}%) ≠ 本轮merge。')
    return dict(beforeInternalRoots=before, afterInternalRoots=after,
                historicalAuditRoots=historical, historicalDescentWitnesses=witness,
                historicalWitnessPercent=pct, connected=connected,
                connectedActionKinds=dict(Counter(r['kind'] for r in bridges)),
                newlyDisconnected=disconnected, residualRoots=after, summary=summary,
                caveat='仅阶段A连通诊断；历史见证不是本轮merge；无形态/无限世界验收')


def disconnections(out, ctx, audit_rows):
    fig, ax = plt.subplots(figsize=(16, 13))
    canvas(ax, ctx, f'Historical audited roots ONLY: {len(audit_rows)} (not current baseline roots)')
    groups = defaultdict(list)
    for row in audit_rows:
        groups[row['classification']].append(xy(row))
    for index, (label, points) in enumerate(sorted(groups.items())):
        points = np.asarray(points)
        ax.scatter(*points.T, s=7, color=plt.get_cmap('tab20')(index % 20),
                   alpha=.8, linewidths=0, label=f'{label}: {len(points)}')
    stats = ctx['rootStatistics']
    fig._root_note = stats['summary']
    ax.legend(title=(f'{stats["historicalDescentWitnesses"]}/{stats["historicalAuditRoots"]} '
                     f'({stats["historicalWitnessPercent"]:.1f}%)历史见证 != 本轮merge {stats["connected"]}\n'
                     '分类原样来自旧 root audit'),
              loc='upper left', bbox_to_anchor=(1, 1), fontsize=7)
    return finish(fig, out, 'disconnection-map.png', 'Old root classifications, NOT repaired merge evidence')


def profile_groups(rows):
    groups = defaultdict(list)
    for row in rows:
        groups[row['edge']].append(row)
    return groups


def profile_line(ax, rows, label, style='-'):
    rows = sorted(rows, key=lambda row: finite(row, 's_wu'))
    s = [finite(row, 's_wu') for row in rows]
    h = [finite(row, 'H0') for row in rows]
    ax.plot(s, h, style, lw=.9, marker='.' if len(rows) < 50 else None, label=label)
    return max(h)-h[0] if h else None


def pitch_rows(rows, pitch):
    if rows and 'pitch' in rows[0]:
        return [r for r in rows if np.isclose(float(r['pitch']), pitch, rtol=0, atol=1e-12)]
    # Only actual matching positions: never interpolate the offset .137 sample grid.
    if pitch == .137:
        return []
    return [r for r in rows if abs(float(r['s_wu'])/pitch-round(float(r['s_wu'])/pitch)) < 1e-8]


def profiles(out, audit, data):
    _, old = table(audit/'failure-edge-profiles.csv', 'edge,s_wu,x,z,H0,rise')
    _, checks = table(audit/'failure-edges.csv', 'source,target,pitch,maxRise,totalRise')
    old_groups = profile_groups(old)
    if len(old_groups) != 5:
        raise ValueError('Expected all five original failed edges, not selected successes')
    replacement = data/'failure-edge-profiles.csv'
    new_groups = profile_groups(table(replacement, 'edge,s_wu,x,z,H0,rise')[1]) if replacement.exists() else {}
    fig, axes = plt.subplots(5, 1, figsize=(15, 23))
    details = []
    for ax, (edge, rows) in zip(axes, old_groups.items()):
        notes = []
        for pitch in (.25, .137, .0078125):
            samples = pitch_rows(rows, pitch)
            if samples:
                peak = profile_line(ax, samples, f'OLD actual H0 pitch {pitch}')
                notes.append(f'{pitch}: peak-start={peak:.9g}')
            else:
                peak = ''
                notes.append(f'{pitch}: actual H0 samples unavailable; NOT interpolated')
            details.append(dict(edge=edge, route='old', pitch=pitch, samples=len(samples), peak_above_start=peak))
        if edge in new_groups:
            peak = profile_line(ax, new_groups[edge], 'REPLACEMENT: different route, own arclength', '--')
            details.append(dict(edge=edge, route='replacement', pitch='supplied',
                                samples=len(new_groups[edge]), peak_above_start=peak))
        else:
            notes.append('Replacement profile not supplied; no fabricated H0')
        evidence = [r for r in checks if r['source']+':'+r['target'] == edge
                    and any(np.isclose(float(r['pitch']), p) for p in (.25, .137, .0078125))]
        notes += [f'check pitch={r["pitch"]}: maxRise={r["maxRise"]}, totalRise={r["totalRise"]}' for r in evidence]
        ax.set(title=f'OLD edge {edge}: original geometry vs replacement (if supplied)',
               xlabel='Each route own arclength (wu); NOT same world positions', ylabel='Actual original H0')
        ax.text(1.01, .98, '\n'.join(notes), transform=ax.transAxes, va='top', fontsize=7)
        ax.legend(fontsize=7, loc='best')
    write_rows(data/'profile-summary.csv', list(details[0]), details)
    return finish(fig, out, 'fine-edge-profiles.png', 'Five old failed edges / actual H0 only / routes are not interchangeable')


def preserve_evidence(out, sources):
    records = []
    for label, folder in sources.items():
        for path in sorted(folder.iterdir()):
            if not path.is_file() or path.suffix.lower() not in ('.json', '.csv', '.txt'):
                continue
            if label == 'repaired' and not any(k in path.name.lower() for k in ('metadata', 'check')):
                continue
            if label == 'audit' and path.name not in ('audit.csv', 'counts.csv', 'metadata.json',
                                                     'failure-edges.csv', 'failure-edge-profiles.csv'):
                continue
            target = out/'evidence'/label/path.name
            copy_exact(path, target)
            records.append(dict(source=str(path), copy=str(target.relative_to(out)), sha256=digest(path)))
    write_rows(out/'data/evidence-files.csv', ['source', 'copy', 'sha256'], records)
    return records


def baseline_graph(baseline, node_map):
    path = baseline/'polyline-segments.csv'
    if path.is_file():
        _, rows = table(path, SEGMENT_SCHEMA)
        return graph(node_map, rows, True)
    # Legacy straight-only exports have no polyline file. Never prefer endpoint
    # inventory over supplied polylines (including the original 154 bridges).
    _, rows = table(baseline/'edges.csv', SEGMENT_SCHEMA.replace(',segment', ''))
    return graph(node_map, rows)


def render(out, audit, baseline):
    """Render supplied repaired Stage A artifacts only; never run on import."""
    out = Path(out).resolve()
    data, audit, baseline = out/'data', data_dir(audit), data_dir(baseline)
    if data.resolve() in (audit, baseline) or audit == baseline:
        raise ValueError('Repaired, audit, baseline data directories must be distinct')
    plt.rcParams['font.sans-serif'] = ['Microsoft YaHei', 'SimHei', 'DejaVu Sans']
    plt.rcParams['axes.unicode_minus'] = False
    old_nodes, new_nodes = nodes(baseline/'graph-nodes.csv'), nodes(data/'graph-nodes.csv')
    _, segments = table(data/'polyline-segments.csv', SEGMENT_SCHEMA)
    fields, actions = table(data/'repair-actions.csv', 'id,new_next,kind')
    before, after = baseline_graph(baseline, old_nodes), graph(new_nodes, segments, True)
    for key in old_nodes.keys() & new_nodes.keys():
        for field in ('x', 'z', 'H0'):
            if old_nodes[key][field] != new_nodes[key][field]:
                raise ValueError(f'Original node {key} {field} changed; H0/positions must remain frozen')
    _, junctions = table(baseline/'junctions.csv', 'region,node,x,z')
    _, audited = table(audit/'audit.csv', 'root,x,z,classification')
    if len({r['root'] for r in audited}) != len(audited):
        raise ValueError('Duplicate audited original roots')
    ctx = carrier(baseline, data)
    ctx['rootStatistics'] = root_statistics(data, audited, actions, old_nodes, new_nodes)
    evidence = preserve_evidence(out, dict(repaired=data, audit=audit))
    outputs = comparisons(out, ctx, before, after)
    outputs += fixed_pages(out, ctx, before, after, junctions)
    outputs.append(bridge_cases(out, ctx, before, after, fields, actions))
    outputs.append(disconnections(out, ctx, audited))
    outputs.append(profiles(out, audit, data))
    report = dict(stage='A only', stageBExecuted=False, stageCExecuted=False,
                  shapeAcceptance=False, productionExecuted=False, sampling=ctx['sampling'],
                  beforeEdges=len(before['groups']), afterEdges=len(after['groups']),
                  baselineGeometry=('polyline-segments.csv' if (baseline/'polyline-segments.csv').is_file()
                                    else 'legacy edges.csv'),
                  baselinePolylineSegments=len(before['segments']),
                  actualPolylineSegments=len(segments), allEdgesRetained=True,
                  historicalAuditRootCount=len(audited),
                  historicalClassificationCounts=dict(Counter(r['classification'] for r in audited)),
                  repairedEvidence=evidence, outputs=outputs, rootStatistics=ctx['rootStatistics'],
                  evidenceMeaning='Verbatim source evidence, not renderer acceptance or promotion of checks',
                  profiles='Actual supplied H0 only; .137 missing samples explicitly unavailable; different routes own distances',
                  bridgeSelection='Four quadrant first in-viewport row-major bridges; no success/length filter')
    (out/'renderer-report.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    outcome = ['仅阶段A诊断，不执行B/C。', '未修改算法、H0、主执行或计划。',
               '背景与sampling从baseline逐字节复制。', '前后展示全部拓扑边，无阈值筛选。',
               '全图线宽统一；局部真实path/target/下游突出仅为定位，不是等级。',
               '前后优先实际polyline，保留baseline已有桥的每段几何，不替换为端点直线。',
               '原四junction固定中心±60wu，分两页。',
               '四象限仅ctx bounds内按行优先选bridge，不筛成功/长度；空区NoCase。',
               ctx['rootStatistics']['summary'], '历史audit/witness仅独立说明，不参与本轮root减法。',
               '五旧失败边仅用实际H0；缺失采样明示，不插值。', 'metadata/checks原样留存，未作形态验收；详见CSV。']
    (out/'outcome.txt').write_text('\n'.join(outcome)+'\n', encoding='utf-8')
    return report
