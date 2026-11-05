"""S1-only, fixed-objective sparse bounded QP; no terrain queries or image tuning.

API: solve(out, round_index=0) -> the JSON-compatible solver report.
Inputs: out/export/{samples,spatial}.csv and optional out/constraints-extra.csv.
All CSV inputs have: kind,trace,s_wu,world_x,world_z,H0,Dold. samples kinds
are train/validation; spatial kind is spatial; extra accepts non-spatial kinds.
H0 and Dold MUST be the frozen original height and COMBINED old cut, not per-path.
Outputs: out/{weights.csv,target.csv,solver.json}. cx/cz are INTEGER lattice
indices (world knot = 4*index), sorted (cz,cx); omitted coefficients equal ONE.
Only apply weights when success AND apply_allowed are true. No silent clipping.
True-uphill paths are explicitly REJECTED, excluded from recurrence/fit/descent;
their sampled positive-Dold basis keys are eliminated at exact zero. Partial
success means ACTIVE paths only, never rejected-path monotonicity/overall effect.
The target.csv header is unchanged; solver.json target_path_status marks REJECTED.
Rounds 1/2 add constraints, never objective samples; caller owns independent Java
validation and the decision to refine. No automatic loops or real-world runs.
Existing dependencies: numpy, scipy (tested 1.10.1, BSD-3-Clause); no new QP package.
"""
import argparse
import csv
import hashlib
import json
import math
import platform
import warnings
from collections import defaultdict
from pathlib import Path
from typing import NamedTuple

import numpy as np
import scipy
from scipy import sparse
from scipy.optimize import Bounds, LinearConstraint, minimize

ETA, SPACING, CAP = .9, 4.0, .02 / 1.5
S_EPS, BOX_TOL, RESIDUAL_TOL = 1e-8, 1e-12, 1e-8
MAXITER, MAX_ROUND = 250, 2
FIELDS = ('kind', 'trace', 's_wu', 'world_x', 'world_z', 'H0', 'Dold')
PARAMS = dict(eta=ETA, spacing_wu=SPACING, cap=CAP, train=1.0, spatial=.02,
              curvature=1e-4, anchor=1e-8, maxiter=MAXITER, max_round_index=MAX_ROUND,
              gtol=1e-10, xtol=1e-12, barrier_tol=1e-12,
              initial_barrier_parameter=1e-5, initial_barrier_tolerance=1e-5,
              box_tolerance=BOX_TOL, residual_tolerance=RESIDUAL_TOL,
              residual_units='(depth/cap)/wu; endpoints depth/cap', padding_cells=2)


class Sample(NamedTuple):
    kind: str
    trace: str
    s: float
    x: float
    z: float
    h: float
    d: float


class RejectedInput(ValueError):
    pass


def basis(x, z):
    """16 world-cardinal cubic tensor terms, including zero terms at knots."""
    def axis(value):
        cell = math.floor(value / SPACING)
        t = value / SPACING - cell
        values = ((1-t)**3/6, (3*t**3-6*t*t+4)/6,
                  (-3*t**3+3*t*t+3*t+1)/6, t**3/6)
        return [(cell-1+i, v) for i, v in enumerate(values)]
    return [((ix, iz), bx*bz) for iz, bz in axis(z) for ix, bx in axis(x)]


def weight_at(x, z, coefficients):
    """Outside is coefficient-default ONE, not a query-window cutoff."""
    return math.fsum(v * coefficients.get(key, 1.0) for key, v in basis(x, z))


def read_samples(path, allowed=None):
    rows = []
    with Path(path).open(newline='', encoding='utf-8-sig') as stream:
        reader = csv.DictReader(stream)
        if reader.fieldnames != list(FIELDS):
            raise RejectedInput('Exact CSV header required at ' + str(path))
        for number, row in enumerate(reader, 2):
            if None in row or any(row[k] is None for k in FIELDS):
                raise RejectedInput('Malformed CSV row {}:{}'.format(path, number))
            kind, trace = row['kind'].strip(), row['trace'].strip()
            if not kind or not trace or (allowed is not None and kind not in allowed):
                raise RejectedInput('Invalid kind/trace at {}:{}'.format(path, number))
            try:
                values = [float(row[k]) for k in FIELDS[2:]]
            except ValueError as exc:
                raise RejectedInput('Non-numeric CSV row') from exc
            if not all(math.isfinite(v) for v in values):
                raise RejectedInput('Nonfinite input at {}:{}'.format(path, number))
            s, x, z, h, d = values
            if s < 0 or d < 0 or d > CAP + 8 * abs(np.spacing(CAP)):
                raise RejectedInput('Negative s/Dold or Dold exceeds frozen cap')
            rows.append(Sample(kind, trace, s, x, z, h, d))
    return rows


def grouped(rows):
    """Deduplicate nearly equal arc positions, never silently conflicting samples."""
    traces = defaultdict(list)
    for row in rows:
        traces[row.trace].append(row)
    result = {}
    for trace in sorted(traces):
        kept = []
        for row in sorted(traces[trace], key=lambda r: (r.s, r.x, r.z, r.kind)):
            if kept and row.s - kept[-1].s <= S_EPS:
                old = kept[-1]
                htol = 8 * max(abs(np.spacing(old.h)), abs(np.spacing(row.h)))
                dtol = 8 * max(abs(np.spacing(old.d)), abs(np.spacing(row.d)))
                if (math.hypot(row.x-old.x, row.z-old.z) > S_EPS
                        or abs(row.h-old.h) > htol or abs(row.d-old.d) > dtol):
                    raise RejectedInput('Conflicting duplicate arc sample: ' + trace)
            else:
                kept.append(row)
        result[trace] = kept
    return result


def inspect_drops(groups):
    """Report ULP-scale negatives separately; NEVER clamp a signed H0 drop."""
    ulp_only, uphill = [], []
    for trace, rows in groups.items():
        for a, b in zip(rows, rows[1:]):
            drop = a.h-b.h
            if drop < 0:
                limit = 8 * max(abs(np.spacing(a.h)), abs(np.spacing(b.h)))
                item = dict(trace=trace, s_left=a.s, s_right=b.s,
                            raw_drop=drop, ulp_limit=limit)
                (ulp_only if -drop <= limit else uphill).append(item)
    return dict(ulp_only=ulp_only, true_uphill=uphill,
                policy='Signed raw drops preserved; true-uphill paths rejected/excluded; '
                       'active ULP-only drops retained without clipping or terrain repair.')


def targets(groups):
    result = []
    for trace, rows in groups.items():
        depths = np.zeros(len(rows))
        for i in range(len(rows)-2, -1, -1):
            depths[i] = min(rows[i].d, depths[i+1] + ETA*(rows[i].h-rows[i+1].h))
        result.extend((r, float(d)) for r, d in zip(rows, depths))
    return result


def variable_keys(rows):
    active = {key for r in rows if r.d > 0 for key, _ in basis(r.x, r.z)}
    padded = {(x+dx, z+dz) for x, z in active
              for dx in range(-2, 3) for dz in range(-2, 3)}
    return sorted(padded, key=lambda k: (k[1], k[0]))


def design(rows, index):
    """Normalized actual COMBINED depth; positive samples must be in fixed map."""
    rr, cc, vv = [], [], []
    for i, row in enumerate(rows):
        if row.d == 0:
            continue
        for key, value in basis(row.x, row.z):
            if key not in index:
                raise RejectedInput('Refinement outside frozen coefficient map; pre-export '
                                    'coverage in spatial.csv. No objective/map expansion.')
            rr.append(i)
            cc.append(index[key])
            vv.append(row.d/CAP * value)
    matrix = sparse.coo_matrix((vv, (rr, cc)), shape=(len(rows), len(index))).tocsr()
    matrix.eliminate_zeros()
    return matrix


def curvature(index):
    """Every axial second-difference stencil touching the map; exterior a=1."""
    rr, cc, vv, constants = [], [], [], []
    for dx, dz in ((1, 0), (0, 1)):
        centers = {(x+k*dx, z+k*dz) for x, z in index for k in (-1, 0, 1)}
        for x, z in sorted(centers, key=lambda k: (k[1], k[0])):
            i, constant = len(constants), 0.0
            for shift, v in ((-1, 1.0), (0, -2.0), (1, 1.0)):
                key = (x+shift*dx, z+shift*dz)
                if key in index:
                    rr.append(i)
                    cc.append(index[key])
                    vv.append(v)
                else:
                    constant += v
            constants.append(constant)
    matrix = sparse.coo_matrix((vv, (rr, cc)),
                               shape=(len(constants), len(index))).tocsr()
    return matrix, np.asarray(constants)


def objective(train_targets, spatial_rows, index):
    """F = mean(train error^2)+.02 mean(spatial error^2)
    +1e-4 mean(axial second differences^2)+1e-8 sum((a-1)^2).
    All Hessians exact, constant and sparse; no finite differences/dense SLSQP.
    """
    train_rows = [r for r, _ in train_targets]
    train_y = np.array([d/CAP for _, d in train_targets])
    spatial_y = np.array([r.d/CAP for r in spatial_rows])
    cm, constants = curvature(index)
    terms = [(design(train_rows, index), train_y, 1.0),
             (design(spatial_rows, index), spatial_y, .02),
             (cm, -constants, 1e-4)]
    n = len(index)
    hess = 2e-8 * sparse.eye(n, format='csr')
    linear, constant = np.full(n, -2e-8), 1e-8*n
    for matrix, y, priority in terms:
        if not len(y):
            continue
        factor = priority / len(y)
        hess = hess + 2*factor*(matrix.T @ matrix)
        linear -= 2*factor*np.asarray(matrix.T @ y).ravel()
        constant += factor*float(y @ y)
    hess = hess.tocsr()
    def fun(a):
        return float(.5*a @ (hess @ a) + linear @ a + constant)
    def jac(a):
        return hess @ a + linear
    return fun, jac, hess, terms


def constraints(group_sets, index):
    blocks, bounds, seen = [], [], set()
    for groups in group_sets:
        for trace, rows in groups.items():
            matrix = design(rows, index)
            for i, (a, b) in enumerate(zip(rows, rows[1:])):
                signature = (trace, a, b)
                if signature in seen:
                    continue
                seen.add(signature)
                ds = b.s-a.s
                row = (matrix.getrow(i)-matrix.getrow(i+1))/ds
                upper = ETA*(a.h-b.h)/CAP/ds
                if row.nnz or upper < 0:
                    blocks.append(row)
                    bounds.append(upper)
            signature = ('end', trace, rows[-1])
            if (rows[-1].d == 0 and signature not in seen
                    and matrix.getrow(len(rows)-1).nnz):
                blocks.append(matrix.getrow(len(rows)-1))
                bounds.append(0.0)  # with a>=0, D_end<=0 enforces D_end=0
                seen.add(signature)
    a = sparse.vstack(blocks, format='csr') if blocks else sparse.csr_matrix((0, len(index)))
    return a, np.asarray(bounds)


def profile_residuals(groups, index, coefficients):
    eta_per_wu, eta_raw, uphill, end = 0.0, 0.0, 0.0, 0.0
    for rows in groups.values():
        d = CAP * (design(rows, index) @ coefficients)
        end = max(end, abs(float(d[-1]))/CAP)
        for i, (a, b) in enumerate(zip(rows, rows[1:])):
            signed = float(d[i]-d[i+1]-ETA*(a.h-b.h))
            eta_per_wu = max(eta_per_wu, signed/CAP/(b.s-a.s))
            eta_raw = max(eta_raw, signed)
            uphill = max(uphill, float(b.h-d[i+1]-(a.h-d[i])))
    return dict(eta_normalized_per_wu=eta_per_wu, eta_depth_raw=eta_raw,
                final_height_uphill_raw=uphill, endpoint_normalized=end)


def retention(train_targets, index, coefficients):
    by_trace = defaultdict(list)
    for row, target in train_targets:
        by_trace[row.trace].append((row, target))
    result = {}
    for trace, pairs in by_trace.items():
        rows = [r for r, _ in pairs]
        s = np.array([r.s for r in rows])
        old = np.array([r.d for r in rows])
        target = np.array([d for _, d in pairs])
        actual = CAP * (design(rows, index) @ coefficients)
        old_int = float(np.trapz(old, s))
        target_int = float(np.trapz(target, s))
        actual_int = float(np.trapz(actual, s))
        result[trace] = dict(old_integral=old_int, target_integral=target_int,
                             actual_integral=actual_int, target_sum=float(target.sum()),
                             old_sum=float(old.sum()), actual_sum=float(actual.sum()),
                             one_dimensional_retention=target_int/old_int if old_int else None,
                             actual_retention=actual_int/old_int if old_int else None,
                             target_max=float(target.max()), actual_max=float(actual.max()),
                             normalized_rmse=float(np.sqrt(np.mean(((actual-target)/CAP)**2))))
    return result


def write_csv(path, header, rows):
    with Path(path).open('w', encoding='utf-8', newline='') as stream:
        writer = csv.writer(stream)
        writer.writerow(header)
        writer.writerows(rows)


def write_json(path, report):
    with Path(path).open('w', encoding='utf-8', newline='\n') as stream:
        json.dump(report, stream, ensure_ascii=False, indent=2, allow_nan=False)
        stream.write('\n')


def digest(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def exclude_rejected_paths(train, validation, extra, allg, report, previous):
    """Reject WHOLE uphill paths; preserve raw rows and signed drop evidence."""
    drops = inspect_drops(allg)
    rejected = sorted({item['trace'] for item in drops['true_uphill']})
    if previous and rejected != previous.get('rejected_path_ids', []):
        raise RejectedInput('Rejected path set changed: fresh out required; objective frozen')
    report['input_drop_report'] = drops
    report['rejected_path_ids'] = rejected
    report['rejected_paths'] = {
        trace: dict(status='REJECTED', reason='True original H0 uphill; no repair/clipping',
                    final_monotone=False, target_D=0, target_is_feasible_envelope=False,
                    evidence=[item for item in drops['true_uphill'] if item['trace'] == trace],
                    samples=[list(r) for r in train+validation+extra if r.trace == trace])
        for trace in rejected}
    fixed = {key for r in train+validation+extra if r.trace in rejected and r.d > 0
             for key, _ in basis(r.x, r.z)}
    if previous:
        fixed.update(tuple(k) for k in previous['fixed_zero_keys'])
    report['fixed_zero_keys'] = sorted(fixed, key=lambda k: (k[1], k[0]))
    active_train = [r for r in train if r.trace not in rejected]
    active_target = targets(grouped(active_train))
    target_lookup = {(r.trace, r.s): d for r, d in active_target}
    report['target_path_status'] = {trace: 'REJECTED' if trace in rejected else 'ACTIVE'
                                    for trace in grouped(train)}
    return active_target, target_lookup, rejected


def prepare(out, round_index, report, previous):
    sample_path, spatial_path = out/'export/samples.csv', out/'export/spatial.csv'
    hashes = dict(samples=digest(sample_path), spatial=digest(spatial_path))
    if previous and (previous['input_sha256'] != hashes or previous['objectiveparams'] != PARAMS):
        raise RejectedInput('Frozen objective inputs/parameters changed between rounds')
    report['input_sha256'] = hashes
    rows = read_samples(sample_path, {'train', 'validation'})
    spatial_rows = read_samples(spatial_path, {'spatial'})
    train = [r for r in rows if r.kind == 'train']
    validation = [r for r in rows if r.kind == 'validation']
    extra_path = out/'constraints-extra.csv'
    extra = read_samples(extra_path) if extra_path.exists() else []
    if any(r.kind == 'spatial' for r in extra):
        raise RejectedInput('Extra constraints must be ordered path samples, not spatial rows')
    if previous and not set(map(tuple, previous['extra_samples'])).issubset(set(extra)):
        raise RejectedInput('Extra constraint samples must be append-only between rounds')
    report['extra_samples'] = [list(r) for r in extra]
    tg, allg = grouped(train), grouped(train+validation+extra)
    if not tg or not spatial_rows:
        raise RejectedInput('Nonempty initial training and spatial samples required')
    for trace, path in allg.items():
        if (trace not in tg or path[0].s < tg[trace][0].s-S_EPS
                or path[-1].s > tg[trace][-1].s+S_EPS):
            raise RejectedInput('Validation/extra must lie within initial training trace extents')
    report['counts'] = dict(train_raw=len(train), validation_raw=len(validation),
                            spatial_raw=len(spatial_rows), extra_raw=len(extra),
                            train_unique=sum(map(len, tg.values())),
                            combined_unique=sum(map(len, allg.values())))
    target, lookup, rejected = exclude_rejected_paths(
        train, validation, extra, allg, report, previous)
    # Keep every original training sample in the artifact, including rejected paths.
    # Their zero target is a rejection marker, NOT a backward feasible envelope.
    write_csv(out/'target.csv', ('trace', 's_wu', 'H0', 'Dold', 'target_D'),
              ((r.trace, r.s, r.h, r.d, lookup.get((r.trace, r.s), 0.0))
               for path in tg.values() for r in path))
    train, validation, extra = ([r for r in subset if r.trace not in rejected]
                                for subset in (train, validation, extra))
    tg = grouped(train)
    allg = grouped(train+validation+extra)
    keys = ([tuple(k) for k in previous['coefficient_keys']] if previous
            else variable_keys(rows+spatial_rows+[Sample(*r) for r in report['extra_samples']]))
    report['coefficient_keys'] = keys
    report['counts']['coefficients'] = len(keys)
    index = {key: i for i, key in enumerate(keys)}
    # Validate coverage even for validation points not yet enforced as constraints.
    design(rows+extra, index)
    group_sets = [tg, grouped(train+extra)]
    if round_index >= 1:
        with (out/'weights.csv').open(newline='', encoding='utf-8') as stream:
            saved = {(int(r['cx']), int(r['cz'])): float(r['weight']) for r in csv.DictReader(stream)}
        current = np.array([saved[key] for key in keys])
        added = 0
        for trace, path in allg.items():
            depth = CAP * (design(path, index) @ current)
            for i, (left, right) in enumerate(zip(path, path[1:])):
                residual = (depth[i]-depth[i+1]-ETA*(left.h-right.h))/CAP/(right.s-left.s)
                if residual > RESIDUAL_TOL:
                    group_sets.append({trace: [left, right]})
                    added += 1
        report['counts']['added_violating_intervals'] = added
    # Inserting refinement points must NOT remove any earlier coarse inequality.
    profiles = previous['constraint_profiles'][:] if previous else []
    for groups in group_sets:
        profile = [list(r) for path in groups.values() for r in path]
        if profile not in profiles:
            profiles.append(profile)
    report['constraint_profiles'] = profiles
    group_sets = [grouped([Sample(*r) for r in profile]) for profile in profiles]
    return target, spatial_rows, index, group_sets, allg, grouped(train+validation)


def run_qp(target, spatial_rows, index, group_sets, report):
    full_fun, full_jac, full_hess, terms = objective(target, spatial_rows, index)
    fixed = {tuple(k) for k in report['fixed_zero_keys']}
    if not fixed.issubset(index):
        raise RejectedInput('Rejected support outside frozen map; fresh out required')
    free = np.array([i for key, i in index.items() if key not in fixed], dtype=int)
    if not len(free) or not target:
        raise RejectedInput('No active paths/free coefficients; zero is NOT a solved candidate')
    def expand(a):
        full = np.zeros(len(index))
        full[free] = a
        return full
    fun = lambda a: full_fun(expand(a))
    jac = lambda a: full_jac(expand(a))[free]
    hess = full_hess[free][:, free].tocsr()
    a, upper = constraints(group_sets, index)
    a = a[:, free].tocsr()
    a.eliminate_zeros()
    # Elimination can leave 0<=nonnegative bounds: redundant, not equalities.
    keep = (np.diff(a.indptr) != 0) | (upper < 0)
    a, upper = a[keep], upper[keep]
    report['counts'].update(fixed_zero=len(fixed), unknown_coefficients=len(free),
                            constraints=a.shape[0], constraint_nnz=a.nnz,
                            hessian_nnz=hess.nnz, curvature_stencils=terms[2][0].shape[0])
    options = {k: PARAMS[k] for k in ('maxiter', 'gtol', 'xtol', 'barrier_tol',
                                    'initial_barrier_parameter', 'initial_barrier_tolerance')}
    options.update(sparse_jacobian=True, factorization_method='AugmentedSystem')
    # Equivalent positive row scaling prevents tiny derivative rows from stalling
    # the interior-point iterations. Acceptance below still uses ORIGINAL units.
    norms = np.asarray(abs(a).max(axis=1).toarray()).ravel() if a.shape[0] else np.array([])
    scales = 1 / np.maximum(norms, 1e-15)
    linear = [LinearConstraint(sparse.diags(scales) @ a, -np.inf, upper * scales)] if a.shape[0] else []
    report['constraint_scaling'] = dict(method='positive max-abs row equilibration',
        floor=1e-15, acceptance='original unscaled residual',
        norm_range=[float(norms.min()), float(norms.max())] if len(norms) else [])
    # Permit temporary interior-point box infeasibility: forcing every iterate
    # inside the box stalls near .001. Final coefficients remain strictly checked.
    with warnings.catch_warnings(record=True) as caught:
        warnings.simplefilter('always')
        # SciPy otherwise falls back to DENSE SVD for a singular sparse Jacobian.
        # Abort that fallback, preserving memory and reporting the actual warning.
        warnings.filterwarnings('error', message='Singular Jacobian matrix.*')
        try:
            result = minimize(fun, np.full(len(free), .001), method='trust-constr',
                              jac=jac, hess=lambda _: hess,
                              bounds=Bounds(0, 1, keep_feasible=False),
                              constraints=linear, options=options)
        finally:
            report['warnings'] = [str(w.message) for w in caught]
            for item in caught:
                warnings.showwarning(item.message, item.category, item.filename, item.lineno)
    report['scipy'] = dict(success=bool(result.success), status=int(result.status),
                           message=str(result.message), optimality=float(result.optimality),
                           constraint_violation=float(result.constr_violation))
    report['iterations'] = int(result.nit)
    report['objective_value'] = float(result.fun)
    report['residuals']['enforced_linear'] = (
        max(0.0, float(np.max(a @ result.x-upper))) if len(upper) else 0.0)
    result.x = expand(result.x)  # fixed zeros restored; exterior remains default ONE
    report['objective_terms'] = {name: priority*float(np.mean((m @ result.x-y)**2))
                                 for name, (m, y, priority) in zip(
                                     ('train', 'spatial', 'curvature'), terms)}
    report['objective_terms']['anchor'] = float(1e-8*np.sum((result.x-1)**2))
    return result


def finalize(out, data, result, report):
    target, _, index, group_sets, allg, validation = data
    x = result.x
    if not np.all(np.isfinite(x)):
        raise RejectedInput('Nonfinite optimizer coefficients')
    box = max(0.0, float(-x.min()), float(x.max()-1))
    report['residuals'].update(box=box, training=profile_residuals(group_sets[0], index, x),
                               validation=profile_residuals(validation, index, x),
                               all_samples=profile_residuals(allg, index, x))
    report['upperbounds'] = retention(target, index, x)
    for trace, details in report['rejected_paths'].items():
        rows = [Sample(*r) for r in details['samples']]
        details['residuals_NOT_acceptance'] = profile_residuals(grouped(rows), index, x)
        details['actual_max_depth_at_samples'] = float(np.max(CAP*(design(rows, index) @ x)))
    report['raw_coefficient_range'] = [float(x.min()), float(x.max())]
    report['effect'] = dict(
        actual_train_integral=sum(r['actual_integral'] for r in report['upperbounds'].values()),
        zero_target=all(d == 0 for _, d in target),
        zero_is_NOT_morphological_success=True)
    converged = bool(result.success and result.status in (1, 2))
    residual = report['residuals']
    all_check = residual['all_samples']
    valid = max(all_check['eta_normalized_per_wu'], all_check['endpoint_normalized']) <= RESIDUAL_TOL
    if box > BOX_TOL:
        status = 'rejected_box_do_not_apply'
    elif not converged:
        status = 'rejected_scipy_not_converged'
    elif residual['enforced_linear'] > RESIDUAL_TOL:
        status = 'rejected_enforced_residual'
    elif not valid:
        status = 'rejected_validation_budget_exhausted' if report['round_index'] == MAX_ROUND else 'rejected_validation_refine'
    else:
        status = ('converged_active_paths_only_REJECTED_paths_zeroed' if report['rejected_paths']
                  else 'converged_finite_samples_only')
    active_success = status in ('converged_finite_samples_only',
                               'converged_active_paths_only_REJECTED_paths_zeroed')
    report.update(status=status, success=active_success,
                  overallEffect=False, active_paths_success=active_success,
                  all_paths_accepted=active_success and not report['rejected_paths'],
                  residuals_scope='ACTIVE paths only; rejected residuals are separately NOT acceptance',
                  converged=converged, apply_allowed=active_success,
                  retry_allowed=converged and box <= BOX_TOL and report['round_index'] < MAX_ROUND
                  and residual['enforced_linear'] <= RESIDUAL_TOL,
                  weights_role=('partial_candidate_REJECTED_paths_zeroed' if report['rejected_paths']
                                else 'candidate') if active_success
                  else 'rejected_raw_candidate_DO_NOT_APPLY')
    exported = [(cx, cz, float(x[i])) for (cx, cz), i in index.items()]
    write_csv(out/'weights.csv', ('cx', 'cz', 'weight'), exported)
    if box > BOX_TOL:
        write_csv(out/'weights-raw-candidate.csv', ('cx', 'cz', 'weight'), exported)
        write_csv(out/'safe-zero-weights.csv', ('cx', 'cz', 'weight'),
                  ((cx, cz, 0.0) for cx, cz in index))
        report['safe_zero'] = ('Diagnostic zero on saved map ONLY; NOT a candidate/fallback; '
                               'outside map remains 1. Main must STOP, do not apply raw weights.')
    return report


def solve(out, round_index=0):
    """One bounded solve per call; indices 0,1,2 only. Failure is explicit JSON."""
    out = Path(out)
    out.mkdir(parents=True, exist_ok=True)
    report = dict(success=False, status='rejected_input', apply_allowed=False,
                  converged=False, retry_allowed=False, round_index=round_index,
                  objectiveparams=dict(PARAMS), residuals={}, versions=dict(
                      python=platform.python_version(), numpy=np.__version__, scipy=scipy.__version__),
                  iterations=0, upperbounds={}, meaningfuleffectNOTguaranteed=True,
                  warnings=[], history=[], weights_role='none', overallEffect=False,
                  active_paths_success=False, all_paths_accepted=False,
                  rejected_paths={}, rejected_path_ids=[], fixed_zero_keys=[],
                  schema=dict(weights='cx,cz are lattice indices; world knot=(4*cx,4*cz)',
                              exterior_coefficient=1, order='sorted(cz,cx)',
                              target='initial deduplicated training; REJECTED target=0 marker '
                                     'via target_path_status, never fit/recurrence',
                              success='active paths only; never overallEffect or rejected monotonicity',
                              samples='Dold is the combined frozen old cut; never per-path'),
                  objective_definition=('mean(train normalized error^2) + .02*mean(spatial '
                      'normalized error^2) + 1e-4*mean(axial coefficient second difference^2) '
                      '+ 1e-8*sum((a-1)^2); normalized depth=D/cap'),
                  limitations=['Finite S1 sampling only, not S2 or a terrain/visual acceptance.',
                               'C2 weight does not make combined Dold C2.',
                               'ULP-only negative raw drops reported, never clamped.',
                               'Sparse singular-Jacobian dense-SVD fallback is blocked and rejected.',
                               'Refinement outside fixed map is rejected; pre-export spatial coverage.',
                               'No lambda/linear-solver fallback; zero is not meaningful erosion.'])
    try:
        if type(round_index) is not int or not 0 <= round_index <= MAX_ROUND:
            raise RejectedInput('round_index must be 0, 1 or 2 (at most three solves)')
        previous = None
        if (out/'solver.json').exists():
            previous = json.loads((out/'solver.json').read_text(encoding='utf-8'))
            if round_index == 0 or previous['round_index'] != round_index-1:
                raise RejectedInput('No repeated/reset rounds; use a fresh out for a new experiment')
            if not previous['retry_allowed']:
                raise RejectedInput('Previous solve requires STOP, not refinement')
            report['history'] = previous['history'] + [dict(
                round_index=previous['round_index'], status=previous['status'],
                scipy=previous.get('scipy'), residuals=previous['residuals'])]
        elif round_index != 0:
            raise RejectedInput('Round 1/2 requires previous solver.json with frozen objective')
        data = prepare(out, round_index, report, previous)
        if not data[2]:
            report.update(status='rejected_zero_action_no_variables', weights_role='none')
            write_csv(out/'weights.csv', ('cx', 'cz', 'weight'), [])
        else:
            result = run_qp(*data[:4], report)
            finalize(out, data, result, report)
    except (RejectedInput, OSError, ValueError, KeyError, TypeError, Warning, RuntimeError) as exc:
        report.update(success=False, apply_allowed=False, retry_allowed=False,
                      error='{}: {}'.format(type(exc).__name__, exc))
        report['status'] = ('rejected_input' if isinstance(exc, (RejectedInput, OSError))
                            else 'rejected_solver_error')
        if isinstance(exc, Warning):
            report['warnings'].append(str(exc))
            warnings.showwarning(exc, type(exc), __file__, 0)
    report['do_not_apply'] = not report['apply_allowed']
    write_json(out/'solver.json', report)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('out', type=Path)
    parser.add_argument('--round-index', type=int, default=0)
    args = parser.parse_args()
    report = solve(args.out, args.round_index)
    print(json.dumps(dict(status=report['status'], success=report['success'],
                          iterations=report['iterations'], retry_allowed=report['retry_allowed'])))
    raise SystemExit(0 if report['success'] else 2)


if __name__ == '__main__':
    main()
