"""Synthetic checks only. Writes solely to build/remaining-drop-solver-check-*.
Uses already installed numpy/scipy; does not install packages or run real exports.
"""
import ast
import csv
import importlib.util
import json
import math
from pathlib import Path
import sys
import tempfile

# Keep the explicitly allowed source outputs limited to the two .py files.
sys.dont_write_bytecode = True

import numpy as np
from scipy import sparse

HERE = Path(__file__).resolve().parent
SPEC = importlib.util.spec_from_file_location('remaining_drop_solver', HERE/'remaining-drop-solver.py')
SOLVER = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(SOLVER)
S, CAP = SOLVER, SOLVER.CAP


def export(out, samples, spatial_rows):
    (out/'export').mkdir(parents=True)
    S.write_csv(out/'export/samples.csv', S.FIELDS, samples)
    S.write_csv(out/'export/spatial.csv', S.FIELDS, spatial_rows)


def load_weights(out):
    with (out/'weights.csv').open(newline='', encoding='utf-8') as stream:
        return {(int(r['cx']), int(r['cz'])): float(r['weight']) for r in csv.DictReader(stream)}


def assert_basis():
    for x in (-12.001, -8, -.001, 0, 3.999, 4, 81.234):
        for z in (-9.24, -.01, 0, 4.001):
            terms = S.basis(x, z)
            assert len(terms) == 16 and min(v for _, v in terms) >= 0
            assert abs(math.fsum(v for _, v in terms)-1) < 4e-16
            assert abs(S.weight_at(x, z, {})-1) < 4e-16
    assert {k[0] for k, _ in S.basis(-.1, 0)} == {-2, -1, 0, 1}
    weights = {(x, z): .13+((x+z)%3)*.21 for x in range(-2, 3) for z in range(-2, 3)}
    for x in np.linspace(-24, 24, 121):
        w = S.weight_at(x, -.3, weights)
        assert 0 <= w <= 1
    # First and second one-sided finite differences across knots AND map exterior.
    h = 1e-3
    for knot in range(-20, 25, 4):
        for transpose in (False, True):
            def value(t):
                return S.weight_at(.71, t, weights) if transpose else S.weight_at(t, .71, weights)
            center = value(knot)
            left1 = (3*center-4*value(knot-h)+value(knot-2*h))/(2*h)
            right1 = (-3*center+4*value(knot+h)-value(knot+2*h))/(2*h)
            left2 = (center-2*value(knot-h)+value(knot-2*h))/h**2
            right2 = (center-2*value(knot+h)+value(knot+2*h))/h**2
            assert abs(left1-right1) < 2e-6
            assert abs(left2-right2) < 2e-4
    assert S.weight_at(-100, 100, weights) == 1


def assert_targets_and_controls():
    rows = [S.Sample('train', 'one', i, -2+i, 0, h, d)
            for i, (h, d) in enumerate(zip((.03, .02, .015, .01), (.01, .01, .004, 0)))]
    pairs = S.targets(S.grouped(rows))
    expected = np.array([.01, .0085, .004, 0])
    assert np.allclose([d for _, d in pairs], expected, atol=1e-17, rtol=0)
    assert abs(sum(d for _, d in pairs)-.0225) < 1e-17
    for (a, da), (b, db) in zip(pairs, pairs[1:]):
        assert da-db <= S.ETA*(a.h-b.h)+1e-17
    # Original fixed endpoint fade fails: no modified terrain/alternative lambda.
    negative = [S.Sample('train', 'fade', i, i, 0, .1-i*.0001, d)
                for i, d in enumerate((0, .01, .01, 0))]
    keys = S.variable_keys(negative)
    index = {key: i for i, key in enumerate(keys)}
    residual = S.profile_residuals(S.grouped(negative), index, np.ones(len(index)))
    assert residual['eta_normalized_per_wu'] > .5
    assert residual['final_height_uphill_raw'] > .009
    groups = S.grouped(rows+[rows[1]._replace(s=1+1e-9, x=-1+1e-9)])
    assert len(groups['one']) == 4
    ulp = [rows[0]._replace(h=.1), rows[1]._replace(h=np.nextafter(.1, np.inf))]
    drops = S.inspect_drops(S.grouped(ulp))
    assert len(drops['ulp_only']) == 1 and not drops['true_uphill']
    assert S.targets(S.grouped(ulp))[0][1] < 0  # no positive-drop clipping


def synthetic_paths(flat=False):
    samples, spatial_rows = [], []
    for trace in range(4):
        for i in range(5):
            h = .1 if flat else .1-.003*i
            d = .01 if i in (1, 2, 3) else 0
            samples.append(S.Sample('train', str(trace), i, i-2, trace*.3, h, d))
        for i in (0.5, 1.5, 2.5, 3.5):
            h = .1 if flat else .1-.003*i
            # Continuous piecewise-linear synthetic frozen combined Dold.
            d = .01*min(i, 4-i, 1)
            samples.append(S.Sample('validation', str(trace), i, i-2, trace*.3, h, d))
    for x in (-2, -.5, 0, .5, 2):
        for z in (-.2, .5, 1.1):
            spatial_rows.append(S.Sample('spatial', 'grid', 0, x, z, .1, .008))
    return samples, spatial_rows


def assert_objective(samples, spatial_rows):
    keys = S.variable_keys(samples+spatial_rows)
    index = {key: i for i, key in enumerate(keys)}
    target = S.targets(S.grouped([r for r in samples if r.kind == 'train']))
    fun, jac, hess, terms = S.objective(target, spatial_rows, index)
    assert sparse.issparse(hess)
    x, direction = np.full(len(keys), .4), np.linspace(-1, 1, len(keys))
    step = 1e-5
    diff = (fun(x+step*direction)-fun(x-step*direction))/(2*step)
    assert abs(diff-jac(x) @ direction) < 1e-9
    assert np.max(np.abs((jac(x+step*direction)-jac(x-step*direction))/(2*step)
                         -hess @ direction)) < 1e-9
    direct = sum(p*np.mean((m @ x-y)**2) for m, y, p in terms) + 1e-8*np.sum((x-1)**2)
    assert abs(fun(x)-direct) < 1e-14
    a, upper = S.constraints([S.grouped([r for r in samples if r.kind == 'train'])], index)
    assert sparse.issparse(a) and np.max(a @ np.zeros(len(keys))-upper) <= 0


def check_solve(root, name, flat=False):
    out = root/name
    samples, spatial_rows = synthetic_paths(flat)
    if not flat:
        samples.append(next(r for r in samples if r.kind == 'train' and r.s == 1))
    export(out, samples, spatial_rows)
    report = S.solve(out)
    print(name, report['status'], 'iterations=', report['iterations'], report.get('error', ''))
    assert report['scipy']['success'], report
    assert report['scipy']['status'] in (1, 2) and report['iterations'] <= 250
    assert report['residuals']['box'] <= 1e-12
    assert report['residuals']['enforced_linear'] <= S.RESIDUAL_TOL
    original_hash = S.digest(out/'target.csv')
    frozen = report['input_sha256']
    for round_index in (1, 2):
        if report['success']:
            break
        assert report['retry_allowed'], report
        report = S.solve(out, round_index)
        print(name, 'round', round_index, report['status'], report['iterations'])
        assert S.digest(out/'target.csv') == original_hash
        assert report['input_sha256'] == frozen
    assert report['success'] and report['apply_allowed'], report
    weights = load_weights(out)
    assert list(weights) == sorted(weights, key=lambda k: (k[1], k[0]))
    assert all(0 <= value <= 1 for value in weights.values())
    assert report['meaningfuleffectNOTguaranteed'] is True
    if flat:
        assert max(r['actual_max'] for r in report['upperbounds'].values()) < 1e-8
    else:
        assert report['counts']['train_raw'] == 21
        assert report['counts']['train_unique'] == 20
        assert max(r['actual_max'] for r in report['upperbounds'].values()) > 1e-4
    return report


def check_rejections(root):
    samples, spatial_rows = synthetic_paths()
    for name, replacement in [('negative', dict(d=-1e-15))]:
        altered = list(samples)
        altered[1] = altered[1]._replace(**replacement)
        out = root/name
        export(out, altered, spatial_rows)
        report = S.solve(out)
        assert not report['success'] and report['status'] == 'rejected_input'
        assert not (out/'weights.csv').exists()
        if name == 'uphill':
            assert report['input_drop_report']['true_uphill']
    # Enforce maxiter failure: output is raw rejected, NEVER marked converged.
    # This is a synthetic fault injection, not a parameter change in the solver.
    out = root/'maxiter-fault-injection'
    export(out, samples, spatial_rows)
    real_minimize = S.minimize
    def limited(*args, **kwargs):
        kwargs['options'] = dict(kwargs['options'], maxiter=1)
        return real_minimize(*args, **kwargs)
    S.minimize = limited
    try:
        report = S.solve(out)
    finally:
        S.minimize = real_minimize
    assert report['status'] == 'rejected_scipy_not_converged'
    assert not report['apply_allowed'] and not report['converged']
    assert (out/'weights.csv').exists()


def check_extra_and_failures(root):
    samples, spatial_rows = synthetic_paths()
    out = root/'append-only-refinement'
    export(out, samples, spatial_rows)
    first = S.solve(out)
    assert first['scipy']['success'] and first['retry_allowed']
    objective_hash = S.digest(out/'target.csv')
    extra = [S.Sample('refinement', '0', .25, -1.75, 0, .1-.003*.25, .0025)]
    S.write_csv(out/'constraints-extra.csv', S.FIELDS, extra)
    second = S.solve(out, 1)
    assert second['success'], second
    old_count = second['counts']['constraints']
    old_profiles = second['constraint_profiles'][:]
    extra.append(S.Sample('refinement', '0', .375, -1.625, 0, .1-.003*.375, .00375))
    S.write_csv(out/'constraints-extra.csv', S.FIELDS, extra)
    third = S.solve(out, 2)
    # A finite-budget trust-constr failure is acceptable ONLY as an honest reject.
    assert third['success'] or third['status'] == 'rejected_scipy_not_converged', third
    if not third['success']:
        assert not third['apply_allowed'] and third['iterations'] == S.MAXITER
    print('append-only third solve:', third['status'], third['iterations'])
    assert third['counts']['constraints'] > old_count
    assert all(profile in third['constraint_profiles'] for profile in old_profiles)
    assert S.digest(out/'target.csv') == objective_hash
    assert third['retry_allowed'] is False
    real_minimize = S.minimize
    def bad_box(*args, **kwargs):
        result = real_minimize(*args, **kwargs)
        result.x[0] = 1+1e-6
        return result
    def singular(*args, **kwargs):
        import warnings
        warnings.warn('Singular Jacobian matrix. Using dense SVD decomposition.')
    try:
        S.minimize = bad_box
        bad = root/'box-fault-injection'
        export(bad, samples, spatial_rows)
        report = S.solve(bad)
        assert report['status'] == 'rejected_box_do_not_apply'
        assert load_weights(bad)[tuple(report['coefficient_keys'][0])] == 1+1e-6
        assert (bad/'weights-raw-candidate.csv').exists()
        assert (bad/'safe-zero-weights.csv').exists() and not report['apply_allowed']
        S.minimize = singular
        bad = root/'dense-svd-blocked'
        export(bad, samples, spatial_rows)
        report = S.solve(bad)
        assert report['status'] == 'rejected_solver_error' and report['warnings']
        assert not (bad/'weights.csv').exists()
    finally:
        S.minimize = real_minimize


def check_per_path_rejection(root, overlap=False):
    samples, spatial_rows = synthetic_paths()
    # Exercise both shared-support suppression and three separated nonzero cuts.
    samples = [r._replace(z=0 if r.trace == '0' else 10+int(r.trace)*.3)
               if not overlap else r for r in samples]
    samples = [r._replace(h=.2) if r.trace == '0' and r.s == 1 else r for r in samples]
    if overlap:
        check_rejected_overlap(root, samples, spatial_rows)
        return
    out = root/'one-uphill-three-active'
    export(out, samples, spatial_rows)
    hashes = [S.digest(out/'export'/name) for name in ('samples.csv', 'spatial.csv')]
    report = S.solve(out)
    target_hash = S.digest(out/'target.csv')
    for round_index in (1, 2):
        if report['success'] or not report['retry_allowed']:
            break
        report = S.solve(out, round_index)
        assert S.digest(out/'target.csv') == target_hash
    print('one uphill / three active:', report['status'], report['iterations'])
    assert report['success'] and report['scipy']['success'], report
    assert report['status'] == 'converged_active_paths_only_REJECTED_paths_zeroed'
    assert report['rejected_path_ids'] == ['0']
    assert not report['overallEffect'] and not report['all_paths_accepted']
    assert report['rejected_paths']['0']['final_monotone'] is False
    assert report['rejected_paths']['0']['residuals_NOT_acceptance']['final_height_uphill_raw'] > .09
    assert report['rejected_paths']['0']['actual_max_depth_at_samples'] == 0
    assert set(report['upperbounds']) == {'1', '2', '3'}
    assert all(v['actual_max'] > 1e-4 for v in report['upperbounds'].values())
    assert all(r[1] != '0' for profile in report['constraint_profiles'] for r in profile)
    weights = load_weights(out)
    fixed = {tuple(k) for k in report['fixed_zero_keys']}
    assert fixed and all(weights[k] == 0 for k in fixed)
    for row in samples:
        if row.trace == '0' and row.d > 0:
            assert all(weights[k] == 0 for k, _ in S.basis(row.x, row.z))
            assert S.weight_at(row.x, row.z, weights) == 0
    with (out/'target.csv').open(newline='') as stream:
        rejected_targets = [r for r in csv.DictReader(stream) if r['trace'] == '0']
    assert len(rejected_targets) == 5 and all(float(r['target_D']) == 0 for r in rejected_targets)
    assert report['target_path_status']['0'] == 'REJECTED'
    assert hashes == [S.digest(out/'export'/name) for name in ('samples.csv', 'spatial.csv')]
    # Verify exact elimination against the full objective including spatial,
    # curvature exterior=1 and anchor constants; no absent-fixed/exterior confusion.
    index = {tuple(k): i for i, k in enumerate(report['coefficient_keys'])}
    target = S.targets(S.grouped([r for r in samples if r.kind == 'train' and r.trace != '0']))
    fun, jac, hess, _ = S.objective(target, spatial_rows, index)
    full = np.array([weights[k] for k in index])
    assert abs(fun(full)-report['objective_value']) < 1e-12
    assert abs(sum(report['objective_terms'].values())-fun(full)) < 1e-12
    assert S.weight_at(100, 100, weights) == 1
    free = np.array([i for k, i in index.items() if k not in fixed])
    direction = np.zeros(len(index))
    direction[free] = np.linspace(-1, 1, len(free))
    step = 1e-5
    assert abs((fun(full+step*direction)-fun(full-step*direction))/(2*step)
               -jac(full) @ direction) < 1e-9
    assert sparse.issparse(hess[free][:, free])


def check_rejected_overlap(root, samples, spatial_rows):
    out = root/'rejected-shared-support-suppresses-neighbors'
    export(out, samples, spatial_rows)
    report = S.solve(out)
    assert report['success'] and report['scipy']['success'], report
    assert report['rejected_path_ids'] == ['0'] and not report['overallEffect']
    assert all(v['actual_max'] == 0 for v in report['upperbounds'].values())
    assert all(load_weights(out)[tuple(k)] == 0 for k in report['fixed_zero_keys'])


def check_function_lengths():
    for filename in ('remaining-drop-solver.py', 'remaining-drop-solver-check.py'):
        tree = ast.parse((HERE/filename).read_text(encoding='utf-8'))
        for node in ast.walk(tree):
            if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
                assert node.end_lineno-node.lineno+1 <= 80, (filename, node.name)


def main():
    (HERE.parents[1]/'build').mkdir(exist_ok=True)
    root = Path(tempfile.mkdtemp(prefix='remaining-drop-solver-check-', dir=str(HERE.parents[1]/'build')))
    assert root.name.startswith('remaining-drop-solver-check-')
    assert_basis()
    assert_targets_and_controls()
    assert_objective(*synthetic_paths())
    check_function_lengths()
    monotone = check_solve(root, 'monotone-four-shared')
    flat = check_solve(root, 'flat')
    check_rejections(root)
    check_per_path_rejection(root)
    check_per_path_rejection(root, overlap=True)
    check_extra_and_failures(root)
    (root/'check.json').write_text(json.dumps(dict(success=True, monotone=monotone['status'],
                                                flat=flat['status']), indent=2), encoding='utf-8')
    print('PASS: basis convex/C2/exterior, signed drops, targets, old-fade negative control,')
    print('      sparse exact QP, real SciPy convergence, rejection contracts, <=80 lines/function.')
    print('Synthetic artifacts:', root)


if __name__ == '__main__':
    main()
