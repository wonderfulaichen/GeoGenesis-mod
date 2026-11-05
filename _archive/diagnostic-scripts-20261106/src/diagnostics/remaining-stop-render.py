"""Read-only renderer: NEWout evidenceRun baselineRun [--self-test].

Reads evidenceRun/data/{classifications,trace-paths,connector-paths,ring-samples}.csv
and checks.json; closeups/{fixed-typical,typical-grids}.csv and metadata.json live
at the run root (not data). No solver, H0 callback, or production invocation.
Self-test reads evidence and exercises non-map plot helpers without saving images.
"""
import argparse
import ast
from collections import Counter, defaultdict
import csv
import importlib.util
import json
from pathlib import Path


def load_shared():
    path = Path(__file__).with_name('descent-fragment-render.py')
    spec = importlib.util.spec_from_file_location('remaining_shared_renderer', path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    module.plt.rcParams['font.family'] = 'sans-serif'
    module.plt.rcParams['font.sans-serif'] = ['Microsoft YaHei', 'SimHei', 'DejaVu Sans']
    module.FOOTER = ('Stage A only; experimental H0, not production. No carving.\n'
                     'Uniform skeleton lines; candidate != applied. Finite samples are not closure proof.\n'
                     'Full scope, flags and limitations: report.')
    return module


def validate_functions():
    tree = ast.parse(Path(__file__).read_text(encoding='utf-8'))
    sizes = [(n.name, n.end_lineno-n.lineno+1) for n in ast.walk(tree)
             if isinstance(n, (ast.FunctionDef, ast.AsyncFunctionDef))]
    if any(size > 80 for _, size in sizes):
        raise ValueError(f'Functions exceed 80 lines: {sizes}')
    return dict(functions=len(sizes), maximumLines=max(size for _, size in sizes))


def display_label(label):
    return ('FINITE_TERMINAL_RING_BARRIER_CANDIDATE'
            if label == 'FINITE_RING_BARRIER_CANDIDATE' else label)


def selected_table(shared, path, schema, sources):
    with path.open(encoding='utf-8-sig', newline='') as stream:
        reader = csv.DictReader(stream)
        if not set(schema.split(',')) <= set(reader.fieldnames or []):
            raise ValueError(f'{path}: missing required columns {schema}')
        return [r for r in reader if r['source'] in sources]


def read_evidence(shared, path):
    path = Path(path).resolve()
    root = path.parent if path.name == 'data' else path
    data, close = root/'data', root/'closeups'
    classes = shared.table(data/'classifications.csv',
                           'source,x,z,sourceH0,classification,status,target,first_failed_segment,'
                           'prefix_descended,fineAllVerified,terminal_reachable_verified')[1]
    cases = shared.table(close/'fixed-typical.csv', 'case,source,x,z,classification,selection')[1]
    sources = {r['source'] for r in cases}
    specs = {'trace': ('trace-paths.csv', 'source,vertex,x,z,H0,s_wu,prefix_verified'),
             'connectors': ('connector-paths.csv', 'source,target,vertex,x,z,H0,kind'),
             'rings': ('ring-samples.csv', 'source,terminal_x,terminal_z,endpointH0,radius,angle_index,x,z,H0')}
    result = {key: selected_table(shared, data/name, schema, sources)
              for key, (name, schema) in specs.items()}
    result['connectors'] = shared.table(data/'connector-paths.csv', specs['connectors'][1])[1]
    result.update(root=root, classes=classes, cases=cases,
                  grids=shared.table(close/'typical-grids.csv', 'case,source,ix,iz,x,z,H0')[1],
                  checks=json.loads((data/'checks.json').read_text('utf-8-sig')),
                  metadata=json.loads((close/'metadata.json').read_text('utf-8-sig')))
    return result


def validate_evidence(shared, ev, before=None):
    rows, cases, checks = ev['classes'], ev['cases'], ev['checks']
    lookup = {r['source']: r for r in rows}
    if len(lookup) != len(rows) or len(rows) != int(checks['sources']):
        raise ValueError('Duplicate sources or checks source count mismatch')
    if dict(Counter(r['classification'] for r in rows)) != checks['counts']:
        raise ValueError('Classification counts disagree with checks')
    failed = sum(int(r['first_failed_segment']) >= 0 for r in rows)
    if 'failedTraceFineValidation' in checks and failed != int(checks['failedTraceFineValidation']):
        raise ValueError('Failed fine-validation count disagrees with classifications')
    if len(cases) != int(ev['metadata']['typicalCases']):
        raise ValueError('Case count disagrees with current closeup metadata')
    if len({r['case'] for r in cases}) != len(cases):
        raise ValueError('Duplicate case IDs')
    for quadrant in ('WN', 'EN', 'WS', 'ES'):
        if sum('quadrant_'+quadrant in r['selection'].split(';') for r in cases) != 1:
            raise ValueError('Expected one preselected representative per quadrant')
    for row in rows:
        for flag in ('prefix_descended', 'fineAllVerified', 'terminal_reachable_verified'):
            if row[flag] not in ('true', 'false'):
                raise ValueError(f'Invalid fresh evidence flag {flag}')
        if row['classification'] == 'VERIFIED_PREFIX_ONLY_TERMINAL_UNKNOWN':
            if row['prefix_descended'] != 'true' or row['terminal_reachable_verified'] != 'false':
                raise ValueError('Prefix-only classification disagrees with reachability flags')
    for case in cases:
        if not case['case'] or any(c not in 'abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789-_' for c in case['case']):
            raise ValueError('Unsafe case filename')
        if display_label(lookup[case['source']]['classification']) != display_label(case['classification']):
            raise ValueError('Case classification differs from complete inventory')
        if not shared.np.allclose(shared.xy(case), shared.xy(lookup[case['source']]), rtol=0, atol=1e-7):
            raise ValueError('Case original stop position differs')
    if before is not None and set(lookup) != shared.internal_roots(before['nodes']):
        raise ValueError('Evidence must cover exactly all baseline internal stops')
    if not ev['metadata'].get('full769RawH0Bitwise') or not ev['metadata'].get('sourceScope'):
        raise ValueError('Direct grid sampling scope/provenance missing')
    return dict(sources=len(rows), failedTraceFineValidation=failed,
                classificationCounts=dict(Counter(display_label(r['classification']) for r in rows)))


def grid_array(shared, ev, case):
    n, pitch = int(ev['metadata']['gridSize']), float(ev['metadata']['gridPitchWu'])
    rows = [r for r in ev['grids'] if r['case'] == case['case']]
    if len(rows) != n*n or pitch <= 0:
        raise ValueError('Direct grid is incomplete or pitch invalid')
    h, seen = shared.np.empty((n, n)), set()
    x0, z0 = min(float(r['x']) for r in rows), min(float(r['z']) for r in rows)
    for row in rows:
        ix, iz = int(row['ix']), int(row['iz'])
        if not (0 <= ix < n and 0 <= iz < n) or (ix, iz) in seen or row['source'] != case['source']:
            raise ValueError('Grid index/source mismatch')
        seen.add((ix, iz))
        if not shared.np.allclose(shared.xy(row), (x0+ix*pitch, z0+iz*pitch), rtol=0, atol=1e-7):
            raise ValueError('Direct grid coordinates do not match declared pitch')
        h[iz, ix] = shared.finite(row, 'H0')
    return h, dict(size=n, stepWu=pitch, originWu=[x0, z0], HS=2,
                   directH0Samples=True, source=ev['metadata']['sourceScope'])


def tile_context(shared, ev, data, case):
    h, meta = grid_array(shared, ev, case)
    folder = data/'remaining-render-tiles'/case['case']
    if folder.exists():
        raise ValueError(f'Refusing to overwrite tile: {folder}')
    folder.mkdir(parents=True)
    h.astype('>f8').tofile(folder/'carrier-height.f64be')
    (folder/'sampling.json').write_text(json.dumps(meta)+'\n', encoding='utf-8')
    shared.copy_exact(data/'project-palette.csv', folder/'project-palette.csv')
    return shared.carrier(folder, folder)


def load_graphs(shared, data, baseline):
    old, new = shared.nodes(baseline/'graph-nodes.csv'), shared.nodes(data/'graph-nodes.csv')
    if not old.keys() <= new.keys():
        raise ValueError('Original nodes removed')
    for key, row in old.items():
        if any(row[f] != new[key][f] for f in ('x', 'z', 'H0')):
            raise ValueError(f'Original position/H0 changed: {key}')
    rows = shared.table(data/'polyline-segments.csv', shared.SEGMENT_SCHEMA)[1]
    return shared.baseline_graph(baseline, old), shared.graph(new, rows, True)


def case_rows(ev, name, case):
    return [r for r in ev[name] if r['source'] == case['source']]


def trace_parts(shared, rows):
    rows = sorted(rows, key=lambda r: int(r['vertex']))
    seen_unverified = False
    for i, row in enumerate(rows):
        if int(row['vertex']) != i or row['prefix_verified'] not in ('true', 'false'):
            raise ValueError('Trace vertex order/verification flag invalid')
        for field in ('x', 'z', 'H0', 's_wu'):
            shared.finite(row, field)
        if i and float(row['s_wu']) <= float(rows[i-1]['s_wu']):
            raise ValueError('Non-increasing trace arclength')
        if seen_unverified and row['prefix_verified'] == 'true':
            raise ValueError('Verified prefix resumes after unverified suffix')
        seen_unverified |= row['prefix_verified'] == 'false'
    verified = [r for r in rows if r['prefix_verified'] == 'true']
    start = max(0, len(verified)-1)
    suffix = rows[start:] if len(verified) < len(rows) else []
    return verified, suffix


def connectors(shared, rows):
    groups = defaultdict(list)
    for row in rows:
        if row['kind'] not in ('VERIFIED_TRACE_PREFIX', 'CANONICAL_NODE'):
            raise ValueError(f'Unrecognized connector kind: {row["kind"]}')
        for field in ('x', 'z', 'H0'):
            shared.finite(row, field)
        groups[row['target']].append(row)
    for samples in groups.values():
        samples.sort(key=lambda r: int(r['vertex']))
        if [int(r['vertex']) for r in samples] != list(range(len(samples))):
            raise ValueError('Connector vertex order invalid')
    return groups


def accepted_routes(shared, data, after, candidate_rows):
    """Action + actual next_id + exact WHOLE candidate polyline; never class-based."""
    _, actions = shared.table(data/'repair-actions.csv', 'id,new_next,kind')
    accepted, seen = {}, set()
    grouped = defaultdict(list)
    for row in candidate_rows:
        grouped[row['source'], row['target']].append(row)
    for action in actions:
        if action['kind'] != 'CONTINUOUS_DESCENT_CONNECTOR':
            continue
        key = action['id'], action['new_next']
        if key in seen:
            raise ValueError(f'Duplicate accepted connector action: {key}')
        seen.add(key)
        source, target = key
        if source not in after['nodes'] or after['nodes'][source]['next_id'] != target:
            raise ValueError(f'Accepted action disagrees with actual graph: {key}')
        if key not in grouped:
            raise ValueError(f'Accepted action has no exact candidate wholepath: {key}')
        samples = connectors(shared, grouped[key])[target]
        segments = sorted(after['groups'].get(key, []), key=lambda r: int(r['segment']))
        actual = [(float(r['x0']), float(r['z0'])) for r in segments]
        if segments:
            actual.append((float(segments[-1]['x1']), float(segments[-1]['z1'])))
        expected = [shared.xy(r) for r in samples]
        if actual != expected:
            raise ValueError(f'Accepted geometry is not exact whole candidate path: {key}')
        accepted[key] = samples
    return accepted


def plot_path(shared, ax, rows, label, color, style='-'):
    if not rows:
        return
    points = shared.np.asarray([shared.xy(r) for r in rows])
    ax.plot(*points.T, color=color, linewidth=1, linestyle=style, label=label, zorder=4)


def mark_points(shared, ax, row, after):
    roles = [('original stop', 'x', 'z', 's', '#111111'),
             ('trace terminal (not stop)', 'terminal_x', 'terminal_z', 'x', '#d62521'),
             ('verified prefix terminal', 'verified_terminal_x', 'verified_terminal_z', '+', '#007766')]
    for label, xkey, zkey, marker, color in roles:
        x, z = shared.finite(row, xkey), shared.finite(row, zkey)
        ax.scatter([x], [z], s=32, marker=marker, color=color, zorder=6, label=label)
    target = row['target']
    if after and target in after['nodes']:
        x, z = shared.xy(after['nodes'][target])
        ax.scatter([x], [z], s=34, facecolors='none', edgecolors='#1265b0', label='target (not proof of merge)')


def path_profile(shared, ax, verified, suffix, groups, accepted=None):
    for samples, label, color, style in ((verified, 'verified prefix samples', '#007766', '-'),
                                         (suffix, 'UNVERIFIED suffix; not valid route', '#c55555', '--')):
        if samples:
            ax.plot([float(r['s_wu']) for r in samples], [float(r['H0']) for r in samples],
                    color=color, linestyle=style, linewidth=.9, label=label)
    for target, samples in groups.items():
        xy = shared.np.asarray([shared.xy(r) for r in samples])
        s = shared.np.r_[0, shared.np.cumsum(shared.np.linalg.norm(shared.np.diff(xy, axis=0), axis=1))]
        applied = target in (accepted or {})
        ax.plot(s, [float(r['H0']) for r in samples], lw=.9,
                linestyle='-' if applied else ':', color='#1265b0' if applied else '#aa7733',
                label=f'{"ACCEPTED exact wholepath" if applied else "CANDIDATE only, NOT applied"} to {target}; own arclength')
    ax.set(title='Actual original H0 samples / no interpolated H0', xlabel='Own path arclength (wu)', ylabel='H0')
    ax.legend(fontsize=6)
    return dict(verifiedSamples=len(verified), unverifiedSuffixSamples=max(0, len(suffix)-(1 if verified else 0)),
                connectorTargets=list(groups), continuousDescentProven=False)


def ring_profile(shared, ax, rows):
    groups, result = defaultdict(list), []
    for row in rows:
        groups[shared.finite(row, 'radius')].append(row)
    for radius, samples in sorted(groups.items()):
        finite = [r for r in samples if shared.np.isfinite(float(r['H0']))]
        lowest = min(finite, key=lambda r: float(r['H0'])) if finite else None
        result.append(dict(radius=radius, samples=len(samples), finiteSamples=len(finite),
                           minH0=float(lowest['H0']) if lowest else None,
                           minAboveTraceTerminal=float(lowest['H0'])-float(lowest['endpointH0']) if lowest else None))
    valid = [r for r in result if r['finiteSamples']]
    ax.scatter([r['radius'] for r in valid], [r['minAboveTraceTerminal'] for r in valid], s=20)
    ax.axhline(0, color='gray', lw=.6)
    ax.set(title='Finite sampled ring minimum relative to TRACE TERMINAL H0',
           xlabel='Radius around actual trace terminal (wu)', ylabel='min sampled H0 - terminal H0')
    ax.text(.02, .98, 'Not original stop closure; finite samples only.\nNo continuous closure / no-route / true-basin proof.',
            transform=ax.transAxes, va='top', fontsize=7)
    return result


def representative(shared, out, ev, before, after, case):
    ctx = tile_context(shared, ev, out/'data', case)
    row = next(r for r in ev['classes'] if r['source'] == case['source'])
    verified, suffix = trace_parts(shared, case_rows(ev, 'trace', case))
    groups = connectors(shared, case_rows(ev, 'connectors', case))
    fig, axes = shared.plt.subplots(2, 2, figsize=(18, 15))
    for ax, label in zip(axes[0], ('BEFORE', 'AFTER')):
        shared.canvas(ax, ctx, label+' / '+case['case']+' / '+display_label(case['classification']))
        shared.lines(ax, before if label == 'BEFORE' else after, monochrome=True)
        plot_path(shared, ax, verified, 'verified prefix (not graph merge)', '#007766')
        plot_path(shared, ax, suffix, 'UNVERIFIED suffix; NOT valid route', '#c55555', '--')
        for target, samples in groups.items():
            applied = label == 'AFTER' and (case['source'], target) in ev.get('accepted', {})
            status = 'ACCEPTED exact wholepath' if applied else 'CANDIDATE only, NOT applied'
            plot_path(shared, ax, samples, f'{status} to {target}',
                      '#1265b0' if applied else '#aa7733', '-' if applied else ':')
        mark_points(shared, ax, row, after)
        ax.legend(fontsize=6)
    applied = {target: samples for (source, target), samples in ev.get('accepted', {}).items()
               if source == case['source']}
    profiles = path_profile(shared, axes[1, 0], verified, suffix, groups, applied)
    rings = ring_profile(shared, axes[1, 1], case_rows(ev, 'rings', case))
    if row['terminal_reachable_verified'] != 'true':
        axes[1, 1].set_title('UNVERIFIED terminal reachability / finite samples only')
    fig._root_note = ('Representative only. Both skeletons: same width/alpha, single layer. '
                      'Dotted=candidate; blue solid=accepted exact route. '
                      'Red dashed=unverified. Ring center=trace terminal, not stop.')
    name = shared.finish(fig, out, f'remaining-case-{case["case"]}.png', 'Same direct H0 / fixed representative ONLY')
    return dict(case=case['case'], source=case['source'], selection=case['selection'],
                output=name, sampling=ctx['sampling'], profiles=profiles, rings=rings,
                acceptedTargets=list(applied), classification=display_label(row['classification']),
                evidenceFlags={k: row[k] for k in ('prefix_descended', 'fineAllVerified', 'terminal_reachable_verified')})


def overview(shared, out, ctx, before, after, ev):
    fig, axes = shared.plt.subplots(1, 2, figsize=(20, 11))
    for ax, name, net in zip(axes, ('BEFORE', 'AFTER'), (before, after)):
        shared.canvas(ax, ctx, name+' / ALL edges / uniform thin lines')
        shared.lines(ax, net, monochrome=True)
    fig._root_note = ('All edges; identical skeleton width/alpha. Fewer roots != full drainage. '
                      'No carving or production wiring. Boundary reach, apply counts and full limitations: report/outcome.')
    return shared.finish(fig, out, 'remaining-all-edges-before-after.png', 'Same H0 / ALL edges / before and after together')


def classification_map(shared, out, ctx, rows):
    fig, ax = shared.plt.subplots(figsize=(16, 13))
    shared.canvas(ax, ctx, f'ALL {len(rows)} baseline internal stops / display-only label mapping')
    groups = defaultdict(list)
    for row in rows:
        groups[display_label(row['classification'])].append(shared.xy(row))
    for index, (label, points) in enumerate(sorted(groups.items())):
        ax.scatter(*shared.np.asarray(points).T, s=7, linewidths=0, alpha=.8,
                   color=shared.plt.get_cmap('tab20')(index % 20), label=f'{label}: {len(points)}')
    ax.legend(loc='upper left', bbox_to_anchor=(1, 1), fontsize=7)
    return shared.finish(fig, out, 'remaining-classifications.png', 'All-source counts; closeups are representatives ONLY')


def self_test(shared, ev):
    result = validate_evidence(shared, ev)
    details = []
    for case in ev['cases']:
        h, meta = grid_array(shared, ev, case)
        verified, suffix = trace_parts(shared, case_rows(ev, 'trace', case))
        groups = connectors(shared, case_rows(ev, 'connectors', case))
        fig, axes = shared.plt.subplots(1, 2)
        try:
            profile = path_profile(shared, axes[0], verified, suffix, groups)
            rings = ring_profile(shared, axes[1], case_rows(ev, 'rings', case))
        finally:
            shared.plt.close(fig)
        details.append(dict(case=case['case'], directPoints=h.size, sampling=meta,
                            profile=profile, ringRadii=len(rings)))
    result.update(cases=details, totalDirectPoints=sum(d['directPoints'] for d in details),
                  ast=validate_functions(), actualRenderExecuted=False, outputFilesWritten=False)
    synthetic = [dict(vertex=str(i), x=str(i), z='0', H0=str(3-i), s_wu=str(i),
                      prefix_verified='true' if i < 2 else 'false') for i in range(4)]
    verified, suffix = trace_parts(shared, synthetic)
    assert len(verified) == 2 and len(suffix) == 3 and suffix[0] is verified[-1]
    assert all(r['prefix_verified'] == 'true' for r in verified)
    synthetic[-1]['prefix_verified'] = 'true'
    try:
        trace_parts(shared, synthetic)
    except ValueError:
        result['suffixNegativeTest'] = 'PASS: resumed verification rejected'
    else:
        raise AssertionError('Unverified suffix accepted as verified route')
    return result


def apply_outcome(checks):
    return (f'Applied connectors: {int(checks["applied"])}; residual internal stops: '
            f'{int(checks["residual"])}; nodes reaching domain boundary: '
            f'{int(checks["nodesReachingBoundaryBefore"])} -> '
            f'{int(checks["nodesReachingBoundaryAfter"])}. '
            'Fewer roots does NOT mean all drainage reaches a boundary. '
            'No whole-world drainage/connectivity proof; no continuous monotonicity proof; '
            'whole downstream suffix independent fine validation is NOT established. '
            'Experimental Stage A only; no carving or production wiring.')


def render(shared, out, ev, baseline):
    out, baseline = Path(out).resolve(), shared.data_dir(baseline)
    for protected in (ev['root'], baseline, baseline.parent):
        if out == protected or protected in out.parents or out in protected.parents:
            raise ValueError('NEWout must be separate from evidence and baseline trees')
    if not (out/'data').is_dir() or list(out.glob('remaining-*')) or (out/'data/remaining-render-tiles').exists():
        raise ValueError('NEWout/data must be supplied by main; existing renderer outputs forbidden')
    before, after = load_graphs(shared, out/'data', baseline)
    stats = validate_evidence(shared, ev, before)
    ev['accepted'] = accepted_routes(shared, out/'data', after, ev['connectors'])
    apply_checks = json.loads((out/'data/checks.json').read_text('utf-8-sig'))
    outcome = apply_outcome(apply_checks)
    if int(apply_checks['applied']) != len(ev['accepted']):
        raise ValueError('Apply checks applied count differs from exact accepted wholepaths')
    if int(apply_checks['residual']) != len(shared.internal_roots(after['nodes'])):
        raise ValueError('Apply checks residual count differs from actual graph')
    ev['applyOutcome'] = outcome
    ctx = shared.carrier(baseline, out/'data')
    outputs = [overview(shared, out, ctx, before, after, ev),
               classification_map(shared, out, ctx, ev['classes'])]
    details = [representative(shared, out, ev, before, after, case) for case in ev['cases']]
    report = dict(statistics=stats, cases=details, evidenceChecksVerbatim=ev['checks'],
                  applyChecksVerbatim=apply_checks, outcome=outcome,
                  acceptedExactWholepaths=len(ev['accepted']),
                  evidenceRun=str(ev['root']), metadataVerbatim=ev['metadata'],
                  visualConvention={'skeleton': 'Single monochrome layer per panel; same width and alpha.',
                                    'candidate': 'Dotted; not applied, regardless of CONNECTABLE classification.',
                                    'accepted': 'AFTER only; action + next_id + exact whole candidate path.',
                                    'suffix': 'Red dashed, unverified; not an effective route.',
                                    'ringCenter': 'Actual trace terminal, not original stop.',
                                    'scope': ev['metadata']['sourceScope']},
                  beforeEdges=len(before['groups']), afterEdges=len(after['groups']), allEdges=True,
                  beforeInternalRoots=len(shared.internal_roots(before['nodes'])),
                  afterInternalRoots=len(shared.internal_roots(after['nodes'])),
                  productionExecuted=False, carveExecuted=False, continuousClosureProven=False,
                  outputs=outputs+[d['output'] for d in details],
                  labelMapping={'FINITE_RING_BARRIER_CANDIDATE': display_label('FINITE_RING_BARRIER_CANDIDATE')},
                  limitations=['Representatives ONLY; not all-quality proof.',
                               'Sampled verified prefix does not prove continuous descent between samples.',
                               'Unverified suffix NOT effective route; supplied connector NOT graph merge proof.',
                               'Finite trace-terminal rings NOT original stop closure or true-basin proof.'])
    (out/'remaining-outcome.txt').write_text(outcome+'\n', encoding='utf-8')
    shared.write_rows(out/'remaining-type-counts.csv', ['classification', 'count'],
                      [dict(classification=k, count=v) for k, v in sorted(stats['classificationCounts'].items())])
    (out/'remaining-renderer-report.json').write_text(
        json.dumps(report, ensure_ascii=False, indent=2, allow_nan=False)+'\n', encoding='utf-8')
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    for name in ('NEWout', 'evidenceRun', 'baselineRun'):
        parser.add_argument(name)
    parser.add_argument('--self-test', action='store_true', help='Evidence/AST/non-saving plot checks only')
    args = parser.parse_args()
    validate_functions()
    shared = load_shared()
    ev = read_evidence(shared, args.evidenceRun)
    result = self_test(shared, ev) if args.self_test else render(shared, args.NEWout, ev, args.baselineRun)
    print(json.dumps(result, ensure_ascii=False, indent=2, allow_nan=False))


if __name__ == '__main__':
    main()
