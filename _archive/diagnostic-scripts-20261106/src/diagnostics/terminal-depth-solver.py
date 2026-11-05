"""Finite-grid terminal depth QP; never queries terrain or changes the prior run.

API: solve(out, priorRun, directFolder) -> JSON-compatible report.
CLI: python terminal-depth-solver.py out priorRun directFolder
Inputs: accepted prior solver.json/weights.csv/export and TerminalPitProbe
        directFolder/before/samples.csv (terminalfull-0..3, step .125).
Only this explicit call solves anything. No main/plan/Java wiring, new dependencies,
coefficient clipping, outlet construction, filling, lambda or zero-field fallback.
Success is limited to frozen longitudinal constraints and sampled 2D descent edges;
it is NEVER global drainage, basin, morphological or Minecraft acceptance.
"""
import argparse
import csv
import importlib.util
import json
import math
from pathlib import Path
import re
import shutil
import time
import warnings

import numpy as np
from scipy import sparse
from scipy.optimize import Bounds, LinearConstraint, minimize

_SPEC = importlib.util.spec_from_file_location(
    'terminal_remaining_drop_qp', Path(__file__).with_name('remaining-drop-solver.py'))
QP = importlib.util.module_from_spec(_SPEC)
_SPEC.loader.exec_module(QP)
WORKSPACE = Path(__file__).resolve().parents[3]
DEBUG_LOG = WORKSPACE / 'debug-agent.log'
FULL_STEP, START_STRIDE, MAX_ROUNDS, MAX_ADDED = .125, .5, 3, 2000
HEADER = 'id,trace,ix,iz,world_x,world_z,H0,Dold,W,Dnew,Hnew'.split(',')
OFFSETS = [(dz, dx) for dz in (-1, 0, 1) for dx in (-1, 0, 1) if dz or dx]


#region agentlog
def agent_log(hypothesis, event, **data):
    """Append only, at the absolute workspace path; never truncate previous evidence."""
    record = dict(timestamp=time.time(), hypothesisId=hypothesis,
                  location='terminal-depth-solver.py', event=event, **data)
    with DEBUG_LOG.open('a', encoding='utf-8') as stream:
        stream.write(json.dumps(record, ensure_ascii=False, allow_nan=False) + '\n')
#endregion agentlog


def require(condition, message):
    if not condition:
        raise QP.RejectedInput(message)


def load(path):
    return json.loads(Path(path).read_text(encoding='utf-8'))


def frozen_inputs(prior, direct):
    paths = [prior / name for name in ('solver.json', 'weights.csv', 'target.csv')]
    paths += sorted(p for p in (prior / 'export').rglob('*') if p.is_file())
    paths.append(direct / 'before/samples.csv')
    return {str(p): QP.digest(p) for p in paths}


def prior_data(prior, out, report):
    """Reuse the original normalized objective and EVERY saved longitudinal profile."""
    saved = load(prior / 'solver.json')
    require(saved.get('success') and saved.get('apply_allowed'), 'Prior must be accepted')
    require(saved['objectiveparams'] == QP.PARAMS, 'Prior objective/parameters changed')
    keys = [tuple(k) for k in saved['coefficient_keys']]
    require(len(set(keys)) == len(keys), 'Duplicate prior coefficient keys')
    index = {k: i for i, k in enumerate(keys)}
    with (prior / 'weights.csv').open(encoding='utf-8-sig', newline='') as stream:
        reader = csv.DictReader(stream)
        require(reader.fieldnames == ['cx', 'cz', 'weight'], 'Unexpected weights header')
        pairs = [((int(r['cx']), int(r['cz'])), float(r['weight'])) for r in reader]
    require(len(pairs) == len(keys) and {k for k, _ in pairs} == set(keys),
            'Weights must match the exact frozen coefficient map')
    weights = dict(pairs)
    upper = np.array([weights[k] for k in keys])
    require(np.isfinite(upper).all() and (upper >= 0).all() and (upper <= 1).all(),
            'Prior weights invalid; tiny values are not clipped')
    fixed = {tuple(k) for k in saved['fixed_zero_keys']}
    require(fixed.issubset(index) and all(upper[index[k]] == 0 for k in fixed),
            'Rejected path coefficients must already be exact fixed zeros')
    shutil.copytree(prior / 'export', out / 'export')
    shutil.copy2(prior / 'target.csv', out / 'target.csv')
    hashes = {n: QP.digest(out / ('export/' + n + '.csv')) for n in ('samples', 'spatial')}
    require(hashes == saved['input_sha256'], 'Prior export differs from frozen objective')
    rows = QP.read_samples(out / 'export/samples.csv', {'train', 'validation'})
    spatial_rows = QP.read_samples(out / 'export/spatial.csv', {'spatial'})
    rejected = set(saved['rejected_path_ids'])
    train = [r for r in rows if r.kind == 'train' and r.trace not in rejected]
    extra = [QP.Sample(*r) for r in saved['extra_samples'] if r[1] not in rejected]
    allg = QP.grouped([r for r in rows if r.trace not in rejected] + extra)
    target = QP.targets(QP.grouped(train))
    profiles = saved['constraint_profiles']
    require(profiles and target and spatial_rows, 'Frozen objective/profiles must be nonempty')
    groups = [QP.grouped([QP.Sample(*r) for r in p]) for p in profiles]
    require(not any(t in rejected for g in groups for t in g), 'Rejected path in active profiles')
    fun, jac, hess, terms = QP.objective(target, spatial_rows, index)
    center_a, center_b = QP.constraints(groups, index)
    free = np.array([i for k, i in index.items() if k not in fixed and upper[i] != 0], int)
    require(len(free) and any(d > 0 for _, d in target), 'Zero-only action is not success')
    report.update(coefficient_keys=keys, fixed_zero_keys=saved['fixed_zero_keys'],
                  rejected_path_ids=saved['rejected_path_ids'], rejected_paths=saved['rejected_paths'],
                  target_path_status=saved['target_path_status'], constraint_profiles=profiles,
                  input_sha256=hashes, prior_status=saved['status'],
                  counts=dict(coefficients=len(keys), unknown_coefficients=len(free),
                              fixed_zero=len(fixed), exact_zero_upper=int((upper == 0).sum()),
                              longitudinal_profiles=len(profiles), longitudinal_rows=len(center_b)))
    return dict(index=index, upper=upper, fixed=fixed, free=free, target=target,
                allg=allg, groups=groups, fun=fun, jac=jac, hess=hess, terms=terms,
                center_a=center_a, center_b=center_b)


def depth_design(a, index):
    """16 existing basis terms per point; exterior coefficients stay ONE, not zero."""
    rr, cc, vv = [], [], []
    outside = np.zeros(a['Dold'].size)
    for i in np.flatnonzero(a['Dold'].ravel() > 0):
        depth = a['Dold'].ravel()[i] / QP.CAP
        x, z = a['world_x'].ravel()[i], a['world_z'].ravel()[i]
        for key, value in QP.basis(x, z):
            v = depth * value
            if not v:
                continue
            if key in index:
                rr.append(i)
                cc.append(index[key])
                vv.append(v)
            else:
                outside[i] += v
    matrix = sparse.coo_matrix((vv, (rr, cc)),
                               shape=(len(outside), len(index))).tocsr()
    return matrix, outside


def successors(a):
    """H0-only max raw drop, ties keep the FIRST fixed dz/dx offset; no fake outlet."""
    h = a['H0']
    ids = np.arange(h.size).reshape(h.shape)
    p = ids[1:-1, 1:-1].ravel()
    best, q = np.zeros(len(p)), np.full(len(p), -1, dtype=int)
    for dz, dx in OFFSETS:
        neighbor = ids[1+dz:h.shape[0]-1+dz, 1+dx:h.shape[1]-1+dx].ravel()
        drop = h.ravel()[p] - h.ravel()[neighbor]
        take = drop > best
        best[take], q[take] = drop[take], neighbor[take]
    sinks = p[q < 0]
    active = (q >= 0) & (a['Dold'].ravel()[p] > 0)
    p, q, drop = p[active], q[active], best[active]
    distance = np.hypot(a['world_x'].ravel()[p] - a['world_x'].ravel()[q],
                        a['world_z'].ravel()[p] - a['world_z'].ravel()[q])
    return p, q, drop, distance, sinks


def finish_grid(gid, rows, data):
    require(rows, 'Empty terminal grid ' + gid)
    nx, nz = max(r[0] for r in rows) + 1, max(r[1] for r in rows) + 1
    require(nx >= 3 and nz >= 3 and len(rows) == nx*nz, 'Incomplete grid ' + gid)
    arrays = {k: np.full((nz, nx), np.nan) for k in HEADER[4:]}
    seen = set()
    for ix, iz, values in rows:
        require(min(ix, iz) >= 0 and (ix, iz) not in seen, 'Duplicate grid cell ' + gid)
        seen.add((ix, iz))
        for k, v in zip(HEADER[4:], values):
            arrays[k][iz, ix] = v
    require(all(np.isfinite(v).all() for v in arrays.values()), 'Nonfinite grid ' + gid)
    x, z = arrays['world_x'], arrays['world_z']
    require(np.allclose(x, x[0, 0]+np.arange(nx)[None, :]*FULL_STEP, atol=1e-9, rtol=0)
            and np.allclose(z, z[0, 0]+np.arange(nz)[:, None]*FULL_STEP, atol=1e-9, rtol=0),
            'terminalfull must be a regular .125 world grid: ' + gid)
    d, w, dn, hn, h = [arrays[k] for k in ('Dold', 'W', 'Dnew', 'Hnew', 'H0')]
    require((d >= 0).all() and (d <= QP.CAP+1e-15).all()
            and (w >= 0).all() and (w <= 1).all(), 'Invalid actual prior budget ' + gid)
    require(np.allclose(dn, d*w, atol=1e-14, rtol=0)
            and np.allclose(hn, h-dn, atol=1e-14, rtol=0), 'Actual composition mismatch ' + gid)
    matrix, outside = depth_design(arrays, data['index'])
    calculated = QP.CAP * (matrix @ data['upper'] + outside)
    require(np.max(abs(calculated-dn.ravel())) <= 1e-12, 'Direct sample/prior weights mismatch ' + gid)
    # Check W even at Dold=0, where a depth-only comparison cannot identify a stale field.
    weights = {k: float(data['upper'][i]) for k, i in data['index'].items()}
    error = max(abs(QP.weight_at(xx, zz, weights)-ww)
                for xx, zz, ww in zip(x.ravel(), z.ravel(), w.ravel()))
    require(error <= 1e-12, 'Direct W/prior mismatch ' + gid)
    p, q, drop, distance, sinks = successors(arrays)
    edge_a = sparse.diags(1/distance) @ (matrix[p]-matrix[q])
    constant = (outside[p]-outside[q])/distance
    edge_b = QP.ETA*drop/QP.CAP/distance - constant
    return dict(id=gid, arrays=arrays, point=matrix, outside=outside,
                p=p, q=q, drop=drop, distance=distance, sinks=sinks,
                edge_a=edge_a.tocsr(), edge_b=edge_b)


def read_grids(direct, data, report):
    rows, traces, ignored = {}, {}, {}
    with (direct / 'before/samples.csv').open(encoding='utf-8-sig', newline='') as stream:
        reader = csv.DictReader(stream)
        require(reader.fieldnames == HEADER, 'Exact TerminalPitProbe samples header required')
        for r in reader:
            gid = r['id']
            if not re.fullmatch(r'terminalfull-?[0-3]', gid):
                ignored[gid] = ignored.get(gid, 0) + 1
                continue
            require(gid not in traces or traces[gid] == r['trace'], 'Grid trace changed')
            traces[gid] = r['trace']
            values = [float(r[k]) for k in HEADER[4:]]
            rows.setdefault(gid, []).append((int(r['ix']), int(r['iz']), values))
    require(len(rows) == 4 and {int(k[-1]) for k in rows} == set(range(4)),
            'Require all four terminalfull0..3 grids, not selected good windows')
    grids = [finish_grid(gid, rows[gid], data) for gid in sorted(rows)]
    report['ignored_context_grids'] = ignored
    report['direct_traces'] = traces
    report['counts'].update(full_grid_points=sum(g['point'].shape[0] for g in grids),
                            selected_descent_edges=sum(len(g['p']) for g in grids),
                            inherited_no_lower_cells=sum(len(g['sinks']) for g in grids))
    return grids


def violations(g, x):
    return np.asarray(g['edge_a'] @ x - g['edge_b']).ravel()


def select_cuts(grids, x, enforced, initial=False):
    """At most 2000 additions: worst violating edge per WORLD coefficient cell."""
    candidates, counts = [], dict(violating_full=0, eligible_stride=0, already_enforced=0)
    stride = round(START_STRIDE / FULL_STEP)
    for gi, g in enumerate(grids):
        residual = violations(g, x)
        bad = np.flatnonzero(residual > QP.RESIDUAL_TOL)
        counts['violating_full'] += len(bad)
        nx = g['arrays']['H0'].shape[1]
        for edge in bad:
            p = int(g['p'][edge])
            iz, ix = divmod(p, nx)
            if initial and (ix % stride or iz % stride):
                continue
            counts['eligible_stride'] += 1
            if (gi, int(edge)) in enforced:
                counts['already_enforced'] += 1
                continue
            a = g['arrays']
            cell = (math.floor(a['world_x'].ravel()[p]/QP.SPACING),
                    math.floor(a['world_z'].ravel()[p]/QP.SPACING))
            candidates.append((float(residual[edge]), gi, int(edge), cell))
    candidates.sort(key=lambda v: (-v[0], v[1], v[2]))
    if len(candidates) <= MAX_ADDED:
        chosen = candidates
        policy = 'all eligible violations'
    else:
        cells, chosen = set(), []
        for item in candidates:
            if item[3] not in cells:
                chosen.append(item)
                cells.add(item[3])
                if len(chosen) == MAX_ADDED:
                    break
        policy = 'worst one per world coefficient cell, capped at 2000'
    result = [(gi, edge) for _, gi, edge, _ in chosen]
    counts.update(added=len(result), policy=policy, initial_stride_wu=START_STRIDE if initial else None)
    return result, counts


def system(data, grids, enforced, report):
    blocks, bounds = [data['center_a']], [data['center_b']]
    for gi, g in enumerate(grids):
        edges = sorted(e for j, e in enforced if j == gi)
        if edges:
            blocks.append(g['edge_a'][edges])
            bounds.append(g['edge_b'][edges])
    full_a, b = sparse.vstack(blocks, format='csr'), np.concatenate(bounds)
    a = full_a[:, data['free']].tocsr()
    a.eliminate_zeros()
    empty = np.diff(a.indptr) == 0
    require(not np.any(empty & (b < 0)), 'Impossible constant row on frozen coefficient map')
    a, b = a[~empty], b[~empty]
    norms = np.asarray(abs(a).max(axis=1).toarray()).ravel() if len(b) else np.array([])
    scales = 1 / np.maximum(norms, 1e-15)
    linear = ([LinearConstraint(sparse.diags(scales) @ a, -np.inf, b*scales,
                                keep_feasible=False)] if len(b) else [])
    report['constraint_scaling'] = dict(method='positive max-abs row scaling', floor=1e-15,
        acceptance='original unscaled (D/cap)/actual-edge-distance residual',
        norm_range=[float(norms.min()), float(norms.max())] if len(norms) else [])
    report['counts'] = dict(longitudinal=len(data['center_b']), terminal=len(enforced),
                           rows=len(b), nnz=a.nnz, free=len(data['free']))
    report['zero_map_linear_residual'] = max(0., float((-b).max())) if len(b) else 0.
    return full_a, np.concatenate(bounds), a, b, linear


def callback_log(path, started):
    def callback(x, state):
        names = ('nit', 'fun', 'optimality', 'constr_violation', 'tr_radius',
                 'barrier_parameter', 'cg_niter', 'execution_time')
        record = {'wallSeconds': time.perf_counter()-started}
        for name in names:
            value = getattr(state, name, None)
            record[name] = float(value) if value is not None and np.isfinite(value) else None
        record['raw_free_min'] = float(x.min())
        record['raw_free_max'] = float(x.max())
        with path.open('a', encoding='utf-8') as stream:
            stream.write(json.dumps(record, allow_nan=False) + '\n')
        return False
    return callback


def optimize(data, grids, enforced, folder, report):
    started = time.perf_counter()
    full_a, full_b, a, b, linear = system(data, grids, enforced, report)
    free = data['free']
    def expand(x):
        full = np.zeros(len(data['index']))
        full[free] = x
        return full
    fun = lambda x: data['fun'](expand(x))
    jac = lambda x: data['jac'](expand(x))[free]
    hess = data['hess'][free][:, free].tocsr()
    options = {k: QP.PARAMS[k] for k in ('maxiter', 'gtol', 'xtol', 'barrier_tol',
                                      'initial_barrier_parameter', 'initial_barrier_tolerance')}
    options.update(sparse_jacobian=True, factorization_method='AugmentedSystem')
    with warnings.catch_warnings(record=True) as caught:
        warnings.simplefilter('always')
        warnings.filterwarnings('error', message='Singular Jacobian matrix.*')
        try:
            result = minimize(fun, .5*data['upper'][free], method='trust-constr',
                jac=jac, hess=lambda _: hess,
                bounds=Bounds(0, data['upper'][free], keep_feasible=False),
                constraints=linear, options=options,
                callback=callback_log(folder/'callback.jsonl', started))
        finally:
            report['warnings'] = [str(w.message) for w in caught]
            report['wallSeconds'] = time.perf_counter()-started
    x = expand(result.x)
    require(np.isfinite(x).all(), 'Nonfinite optimizer coefficients')
    box = max(0., float((-x).max()), float((x-data['upper']).max()))
    enforced_residual = max(0., float((full_a @ x-full_b).max())) if len(full_b) else 0.
    report.update(scipy=dict(success=bool(result.success), status=int(result.status),
        message=str(result.message), optimality=float(result.optimality),
        constraint_violation=float(result.constr_violation)), iterations=int(result.nit),
        objective_value=float(result.fun), bound_residual_unscaled=box,
        enforced_residual_normalized_per_wu=enforced_residual,
        raw_coefficient_range=[float(x.min()), float(x.max())], clipped=False)
    report['objective_terms'] = {name: priority*float(np.mean((m @ x-y)**2))
        for name, (m, y, priority) in zip(('train', 'spatial', 'curvature'), data['terms'])}
    report['objective_terms']['anchor'] = float(1e-8*np.sum((x-1)**2))
    report['numerically_accepted'] = bool(result.success and result.status in (1, 2)
        and result.optimality <= QP.PARAMS['gtol'] and box <= QP.BOX_TOL
        and enforced_residual <= QP.RESIDUAL_TOL)
    QP.write_csv(folder/'weights.csv', ('cx', 'cz', 'weight'),
                 ((k[0], k[1], float(x[i])) for k, i in data['index'].items()))
    agent_log('TD-QP', 'round_optimizer_end', **report)
    return x


def validate_grid(g, x):
    a = g['arrays']
    depth = QP.CAP * (g['point'] @ x + g['outside'])
    h = a['H0'].ravel()-depth
    actual_drop = h[g['p']]-h[g['q']]
    raw = depth[g['p']]-depth[g['q']]-QP.ETA*g['drop']
    normalized = raw/QP.CAP/g['distance']
    no_lower = np.ones(a['H0'].shape, bool)
    center = h.reshape(no_lower.shape)[1:-1, 1:-1]
    for dz, dx in OFFSETS:
        neighbor = h.reshape(no_lower.shape)[1+dz:no_lower.shape[0]-1+dz,
                                             1+dx:no_lower.shape[1]-1+dx]
        no_lower[1:-1, 1:-1] &= center <= neighbor
    no_lower[[0, -1], :] = False
    no_lower[:, [0, -1]] = False
    minima = np.flatnonzero(no_lower.ravel())
    original_sinks = set(map(int, g['sinks']))
    suspect = [int(i) for i in minima if int(i) not in original_sinks]
    sink_labels = [dict(index=[int(i % no_lower.shape[1]), int(i // no_lower.shape[1])],
        world=[float(a['world_x'].ravel()[i]), float(a['world_z'].ravel()[i])],
        label='inherited_H0_no_lower_neighbor_allowed', outlet_constructed=False,
        still_no_lower_after=bool(no_lower.ravel()[i])) for i in g['sinks']]
    residual = max(0., float(normalized.max())) if len(raw) else 0.
    budget = max(0., float((-depth).max()), float((depth-a['Dnew'].ravel()).max()))
    return dict(id=g['id'], shape=list(no_lower.shape), step_wu=FULL_STEP,
        selected_edges=len(raw), eta_residual_normalized_per_wu=residual,
        violating_edges=int((normalized > QP.RESIDUAL_TOL).sum()),
        eta_residual_depth_raw=max(0., float(raw.max())) if len(raw) else 0.,
        final_height_uphill_raw=max(0., float((-actual_drop).max())) if len(raw) else 0.,
        final_height_non_descent_edges=int((actual_drop <= 0).sum()),
        min_actual_height_drop_raw=float(actual_drop.min()) if len(raw) else None,
        depth_budget_residual_raw=budget, inherited_sink_labels=sink_labels,
        local_minimum_test='sampled no-lower-neighbor cells only; NOT basin/spill classification',
        suspect_new_no_lower_count=len(suspect), suspect_indices=suspect,
        world_bounds=[float(a['world_x'].min()), float(a['world_x'].max()),
                      float(a['world_z'].min()), float(a['world_z'].max())],
        passed=bool(residual <= QP.RESIDUAL_TOL and not (actual_drop <= 0).any()
                    and budget <= QP.BOX_TOL and not suspect),
        scope='interior sampled 8-neighbor H0 successor edges; NOT global drainage')


def validate(data, grids, x):
    center = QP.profile_residuals(data['allg'], data['index'], x)
    # Pair profiles are inequalities, not paths with invented zero-depth endpoints.
    center['endpoint_normalized_role'] = 'diagnostic only; no endpoint pseudozero'
    enforced = [QP.profile_residuals(g, data['index'], x) for g in data['groups']]
    max_eta = max([center['eta_normalized_per_wu']]
                  + [r['eta_normalized_per_wu'] for r in enforced])
    max_uphill = max([center['final_height_uphill_raw']]
                     + [r['final_height_uphill_raw'] for r in enforced])
    reports = [validate_grid(g, x) for g in grids]
    upperbounds = QP.retention(data['target'], data['index'], x)
    integral = sum(r['actual_integral'] for r in upperbounds.values())
    return dict(centerline=center, enforced_profiles_max_eta_normalized_per_wu=max_eta,
        enforced_profiles_max_height_uphill_raw=max_uphill,
        centerline_passed=bool(max_eta <= QP.RESIDUAL_TOL and max_uphill <= QP.BOX_TOL),
        validation2D=reports,
        full_grid_passed=all(r['passed'] for r in reports), upperbounds=upperbounds,
        actual_train_integral=integral, nonzero_action=bool(integral > 0),
        meaningful_morphological_effect_guaranteed=False, globalPassed=False)


def write_cuts(folder, grids, additions):
    rows = []
    for gi, edge in additions:
        g = grids[gi]
        a, p, q = g['arrays'], g['p'][edge], g['q'][edge]
        rows.append((g['id'], int(p), int(q), float(a['world_x'].ravel()[p]),
                     float(a['world_z'].ravel()[p]), float(a['world_x'].ravel()[q]),
                     float(a['world_z'].ravel()[q]), float(g['distance'][edge]),
                     float(g['drop'][edge])))
    QP.write_csv(folder/'added-edges.csv',
        ('id', 'p_index', 'q_index', 'px', 'pz', 'qx', 'qz', 'distance_wu', 'H0_drop'), rows)


def rounds(out, data, grids, report):
    enforced, current = set(), data['upper'].copy()
    for ri in range(MAX_ROUNDS):
        folder = out / ('solve-r' + str(ri))
        folder.mkdir()
        additions, selection = select_cuts(grids, current, enforced, initial=(ri == 0))
        enforced.update(additions)
        rr = dict(round_index=ri, selection=selection, success=False,
                  apply_allowed=False, status='rejected_solver_error')
        write_cuts(folder, grids, additions)
        agent_log('TD-CUTS', 'round_start', round_index=ri, selection=selection)
        try:
            current = optimize(data, grids, enforced, folder, rr)
            checks = validate(data, grids, current)
            rr['validation'] = checks
            if not rr['numerically_accepted']:
                rr['status'] = 'rejected_numerical_convergence_or_unscaled_residual'
            elif not checks['centerline_passed']:
                rr['status'] = 'rejected_prior_longitudinal_validation'
            elif not checks['nonzero_action']:
                rr['status'] = 'rejected_zero_action_NOT_success'
            elif checks['full_grid_passed']:
                rr.update(status='converged_finite_fullgrid_edges_only', success=True,
                          apply_allowed=True)
            else:
                rr['status'] = ('rejected_fullgrid_budget_exhausted' if ri == MAX_ROUNDS-1
                                else 'fullgrid_violations_refine')
        except (ValueError, RuntimeError, Warning, KeyError, TypeError) as exc:
            rr.update(error='{}: {}'.format(type(exc).__name__, exc),
                      status='rejected_solver_error')
        QP.write_json(folder/'solver.json', rr)
        report['rounds'].append(rr)
        report.update(status=rr['status'], success=rr['success'], apply_allowed=rr['apply_allowed'])
        if (folder/'weights.csv').exists():
            shutil.copy2(folder/'weights.csv', out/'weights.csv')
        agent_log('TD-VALIDATE', 'round_end', round_index=ri, status=rr['status'],
                  wallSeconds=rr.get('wallSeconds'), validation=rr.get('validation'))
        QP.write_json(out/'solver.json', report)
        if rr['status'] != 'fullgrid_violations_refine':
            break
        remaining, _ = select_cuts(grids, current, enforced)
        if not remaining:
            report.update(status='rejected_no_new_cut_progress', success=False, apply_allowed=False)
            break
    report['weights_role'] = ('finite_grid_candidate_NOT_morphological_acceptance'
        if report['success'] else 'rejected_raw_candidate_DO_NOT_APPLY')


def solve(out, priorRun, directFolder):
    """Explicit bounded call; fresh output only. Never runs on import or via old main."""
    out, prior, direct = (Path(p).resolve() for p in (out, priorRun, directFolder))
    started, created, locked = time.perf_counter(), False, None
    report = dict(status='rejected_input', success=False, apply_allowed=False,
        overallEffect=False, globalPassed=False, zeroIsNotSuccess=True, rounds=[],
        objectiveparams=dict(QP.PARAMS), priorRun=str(prior), directFolder=str(direct),
        policy=dict(eta=QP.ETA, spacing_wu=QP.SPACING, cap=QP.CAP,
            start_stride_wu=START_STRIDE, full_validation_step_wu=FULL_STEP,
            max_rounds=MAX_ROUNDS, max_extra_edges_per_round=MAX_ADDED,
            box='0 <= coefficient <= exact prior coefficient; fixed zeros eliminated',
            initial='.5 * upper is numerical initial only, never final lambda',
            successors='strict H0 lower, max raw drop, first fixed dz/dx tie',
            edge_units='(D(p)-D(q)-eta*(H0p-H0q))/cap/actual_edge_distance',
            no_endpoint_pseudozero=True, no_coefficient_clipping=True),
        limitations=['Finite .125 terminal interiors only; boundaries do not certify an outlet.',
            'Original no-lower H0 cells/plateaus are inherited, allowed, and never filled.',
            'Ignored wider/context grids are not validation; larger area/boundaries need review.',
            'No basin-depth, continuous-field, global-zero or morphological success claim.',
            'Original spatial target is unchanged, not a new fit to failed old erosion.',
            'No new dependencies, main/plan edits, automatic real run, or solver fallback.'])
    agent_log('TD-ENTRY', 'solve_start', out=str(out), priorRun=str(prior), directFolder=str(direct))
    try:
        require(not out.exists(), 'Refuse existing output directory')
        require(not any(p == out or p in out.parents or out in p.parents for p in (prior, direct)),
                'Output and frozen inputs must be disjoint')
        locked = frozen_inputs(prior, direct)
        out.mkdir(parents=True, exist_ok=False)
        created = True
        data = prior_data(prior, out, report)
        grids = read_grids(direct, data, report)
        report['prior_validation2D'] = [validate_grid(g, data['upper']) for g in grids]
        agent_log('TD-BASELINE', 'frozen_inputs_ready', counts=report['counts'],
                  wallSeconds=time.perf_counter()-started)
        rounds(out, data, grids, report)
    except (OSError, ValueError, RuntimeError, Warning, KeyError, TypeError) as exc:
        report.update(status='rejected_input_or_solver_error', success=False, apply_allowed=False,
                      error='{}: {}'.format(type(exc).__name__, exc))
    finally:
        if locked is not None:
            try:
                unchanged = frozen_inputs(prior, direct) == locked
            except OSError:
                unchanged = False
            report['frozen_inputs_unchanged'] = unchanged
            report['frozen_input_sha256'] = locked
            if not unchanged:
                report.update(status='rejected_frozen_input_drift', success=False, apply_allowed=False)
        report.update(wallSeconds=time.perf_counter()-started, do_not_apply=not report['apply_allowed'])
        if created:
            QP.write_json(out/'solver.json', report)
        agent_log('TD-EXIT', 'solve_end', status=report['status'], success=report['success'],
                  wallSeconds=report['wallSeconds'])
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('out', type=Path)
    parser.add_argument('priorRun', type=Path)
    parser.add_argument('directFolder', type=Path)
    args = parser.parse_args()
    report = solve(args.out, args.priorRun, args.directFolder)
    print(json.dumps(dict(status=report['status'], success=report['success'],
                          wallSeconds=report['wallSeconds'])))
    raise SystemExit(0 if report['success'] else 2)


if __name__ == '__main__':
    main()
