"""Persistent synthetic-only terminal-depth regressions. No real solver/export runs.

Run: python -B src/diagnostics/terminal-depth-solver-check.py
Creates a fresh build/terminal-depth-solver-check-* with stdout.log, exit-code.txt,
check.json and all synthetic artifacts. Solver logging is redirected into this NEW
folder; workspace debug history, solver/main and real outputs are never written.
Fine/shift results certify only these finite synthetic samples, not infinite terrain.
"""
import ast
import csv
import importlib.util
import json
from pathlib import Path
import shutil
import sys
import tempfile
import traceback
from types import SimpleNamespace
from unittest.mock import patch

sys.dont_write_bytecode = True

import numpy as np
from scipy import sparse

HERE = Path(__file__).resolve().parent
MDK = HERE.parents[1]


def module(name):
    spec = importlib.util.spec_from_file_location(name.replace('-', '_'), HERE/(name+'.py'))
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


T = module('terminal-depth-solver')
Q = T.QP
EVIDENCE = module('terminal-pit-evidence')


class Tee:
    def __init__(self, console, stream):
        self.console, self.stream = console, stream

    def write(self, text):
        self.console.write(text)
        self.stream.write(text)
        self.flush()
        return len(text)

    def flush(self):
        self.console.flush()
        self.stream.flush()


def weights(path):
    with Path(path).open(encoding='utf-8', newline='') as stream:
        return {(int(r['cx']), int(r['cz'])): float(r['weight']) for r in csv.DictReader(stream)}


def surface(step=.125, shift=0., mode='pocket'):
    axis = np.arange(round(2/step)+1)*step-1+shift
    x, z = np.meshgrid(axis, axis)
    if mode == 'bowl':
        h, d = .1+.001*(x*x+z*z), np.full(x.shape, .0001)
    elif mode == 'flat':
        h, d = np.full(x.shape, .1), np.full(x.shape, .0001)
    else:
        h = .1-.001*x
        d = .006*np.exp(-((x/.3)**2+((z-.5)/.3)**2))
    return dict(world_x=x, world_z=z, H0=h, Dold=d)


def sampled(a, coefficients):
    w = np.array([Q.weight_at(float(x), float(z), coefficients)
                  for x, z in zip(a['world_x'].ravel(), a['world_z'].ravel())])
    w = w.reshape(a['H0'].shape)
    d = a['Dold']*w
    return dict(a, W=w, Dnew=d, Hnew=a['H0']-d)


def grid_rows(a):
    return [(ix, iz, [float(a[k][iz, ix]) for k in T.HEADER[4:]])
            for iz in range(a['H0'].shape[0]) for ix in range(a['H0'].shape[1])]


def write_fixture(root):
    prior, direct = root/'prior', root/'direct'
    (prior/'export').mkdir(parents=True)
    (direct/'before').mkdir(parents=True)
    a = surface()
    train = [Q.Sample('train', 'route', i*.125, float(x), 0., .1-.001*float(x), .001)
             for i, x in enumerate(np.arange(-1, 1.001, .125))]
    spatial_rows = [Q.Sample('spatial', 'space', 0., float(x), float(z), float(h), float(d))
                    for x, z, h, d in zip(*(a[k].ravel()[::16] for k in
                                           ('world_x', 'world_z', 'H0', 'Dold')))]
    keys = Q.variable_keys(train+spatial_rows)
    upper = np.full(len(keys), 1.-1e-13)
    upper[-1] = 0.  # Existing rejected support, padding outside the active pocket.
    Q.write_csv(prior/'export/samples.csv', Q.FIELDS, train)
    Q.write_csv(prior/'export/spatial.csv', Q.FIELDS, spatial_rows)
    Q.write_csv(prior/'weights.csv', ('cx', 'cz', 'weight'),
                ((cx, cz, float(upper[i])) for i, (cx, cz) in enumerate(keys)))
    Q.write_csv(prior/'target.csv', ('trace', 's_wu', 'H0', 'Dold', 'target_D'),
                ((r.trace, r.s, r.h, r.d, d) for r, d in Q.targets(Q.grouped(train))))
    # A saved refinement pair absent from export: dropping prior profiles MUST fail a check.
    pair = [Q.Sample('refinement', 'route', x+1, x, 0., .1-.001*x, .001)
            for x in (.1875, .8125)]
    saved = dict(success=True, apply_allowed=True, status='synthetic_accepted_prior',
        objectiveparams=Q.PARAMS, coefficient_keys=keys, fixed_zero_keys=[keys[-1]],
        rejected_path_ids=['rejected'], rejected_paths={}, extra_samples=[],
        target_path_status={'route': 'ACTIVE', 'rejected': 'REJECTED'},
        constraint_profiles=[[list(r) for r in train], [list(r) for r in pair]],
        input_sha256={k: Q.digest(prior/('export/'+k+'.csv')) for k in ('samples', 'spatial')})
    Q.write_json(prior/'solver.json', saved)
    actual = sampled(a, dict(zip(keys, upper)))
    rows = [(f'terminalfull-{i}', 'route', ix, iz, *values)
            for i in range(4) for ix, iz, values in grid_rows(actual)]
    Q.write_csv(direct/'before/samples.csv', T.HEADER, rows)
    prepared = root/'prepared'
    prepared.mkdir()
    report = {}
    data = T.prior_data(prior, prepared, report)
    return dict(prior=prior, direct=direct, data=data, saved=saved, report=report, actual=actual)


def manual_row(left, right, index):
    """Independent 16-basis derivative, not Q.constraints used as its own oracle."""
    row = np.zeros(len(index))
    for r, sign in ((left, 1.), (right, -1.)):
        for key, value in Q.basis(r.x, r.z):
            row[index[key]] += sign*r.d/Q.CAP*value/(right.s-left.s)
    bound = Q.ETA*(left.h-right.h)/Q.CAP/(right.s-left.s)
    return row, bound


def check_profiles(root, fixture):
    data, saved = fixture['data'], fixture['saved']
    expected, bounds = [], []
    for profile in saved['constraint_profiles']:
        rows = [Q.Sample(*r) for r in profile]
        for left, right in zip(rows, rows[1:]):
            row, bound = manual_row(left, right, data['index'])
            expected.append(row)
            bounds.append(bound)
    assert data['center_a'].shape[0] == 17, 'Lost saved refinement/longitudinal profile'
    np.testing.assert_allclose(data['center_a'].toarray(), expected, atol=1e-16, rtol=0)
    np.testing.assert_allclose(data['center_b'], bounds, atol=1e-16, rtol=0)
    full_a, full_b, a, b, linear = T.system(data, [], set(), {})
    np.testing.assert_array_equal(full_a.toarray(), data['center_a'].toarray())
    np.testing.assert_array_equal(full_b, data['center_b'])
    assert a.shape[0] == 17 and linear and not linear[0].keep_feasible.any()
    # The right member of this pair is an INTERIOR point with positive depth.
    pair = [Q.Sample(*r) for r in saved['constraint_profiles'][-1]]
    group = Q.grouped(pair)
    pa, pb = Q.constraints([group], data['index'])
    assert pa.shape[0] == 1, 'Interior pair acquired an endpoint pseudozero row'
    half = .5*data['upper']
    pd = Q.CAP*(Q.design(pair, data['index']) @ half)
    assert pd[-1] > .0004 and float((pa @ half-pb).max()) < 0
    checks = T.validate(dict(data, allg=group, groups=[group]), [], half)
    assert checks['centerline_passed'] and checks['centerline']['endpoint_normalized'] > .01
    assert 'diagnostic only' in checks['centerline']['endpoint_normalized_role']
    print('PASS prior profiles: all 17 rows retained; interior pair has no pseudo-terminal zero')


def check_exterior(root):
    a = surface()
    a['world_x'] += 4.
    a['Dold'] = .002+.0002*a['world_x']+.0001*a['world_z']
    index, coeff = {(1, 0): 0}, {(1, 0): .35}
    actual = sampled(a, coeff)
    g = T.finish_grid('terminalfull-0', grid_rows(actual), dict(index=index, upper=np.array([.35])))
    assert np.any(g['outside'] > 0), 'Missing-map constants were silently zeroed'
    reconstructed = Q.CAP*(g['point'] @ np.array([.35])+g['outside'])
    np.testing.assert_allclose(reconstructed, actual['Dnew'].ravel(), atol=2e-18, rtol=0)
    assert np.max(abs(Q.CAP*(g['point'] @ np.array([.35]))-reconstructed)) > .001
    p, q = g['p'], g['q']
    raw = (actual['Dnew'].ravel()[p]-actual['Dnew'].ravel()[q]-Q.ETA*g['drop'])
    np.testing.assert_allclose(T.violations(g, np.array([.35])), raw/Q.CAP/g['distance'],
                               atol=2e-15, rtol=0)
    point, outside = T.depth_design(dict(world_x=np.array([[100.]]),
        world_z=np.array([[100.]]), Dold=np.array([[.001]])), index)
    assert point.nnz == 0 and abs(outside[0]-.001/Q.CAP) < 1e-16
    assert Q.weight_at(100, 100, coeff) == 1.
    # The affine edge constant must also survive actual constraint-list assembly.
    data = dict(center_a=sparse.csr_matrix((0, 1)), center_b=np.array([]), free=np.array([0]))
    edge = int(np.argmax(abs(g['edge_b'])))
    full_a, full_b, _, _, _ = T.system(data, [g], {(0, edge)}, {})
    np.testing.assert_allclose(full_b, g['edge_b'][[edge]], atol=0, rtol=0)
    assert abs(float((full_a @ np.array([.35])-full_b)[0])-raw[edge]/Q.CAP/g['distance'][edge]) < 2e-15
    print('PASS exterior default ONE: point and successor-edge affine constants preserved')


def check_sinks(root, fixture):
    data = fixture['data']
    coeff = {k: .5 for k in data['index']}
    x = np.full(len(coeff), .5)
    for mode in ('bowl', 'flat'):
        actual = sampled(surface(mode=mode), coeff)
        g = T.finish_grid('terminalfull-0', grid_rows(actual), dict(data, upper=x))
        checks = T.validate_grid(g, x)
        assert checks['passed'] and checks['suspect_new_no_lower_count'] == 0
        labels = checks['inherited_sink_labels']
        assert labels and all(r['still_no_lower_after'] and not r['outlet_constructed'] for r in labels)
        if mode == 'bowl':
            assert len(labels) == 1 and EVIDENCE._flood(actual['Hnew'], 8)['depth'].max() > .001
        else:
            assert checks['selected_edges'] == 0 and len(labels) == 15*15
            np.testing.assert_array_equal(actual['Hnew'], np.full((17, 17), .1-.00005))
    a = surface()
    p, q, drop, distance, sinks = T.successors(a)
    assert not len(sinks) and (drop > 0).all()
    assert np.all(q-p == 1-a['H0'].shape[1]), 'dz/dx tie order changed'
    np.testing.assert_allclose(distance, np.sqrt(2)*.125, atol=1e-16, rtol=0)
    print('PASS original bowl/flat sinks inherited without filling; deterministic diagonal successor')


def inspect_samples(a, prior_coeff, new_coeff):
    before, after = sampled(a, prior_coeff), sampled(a, new_coeff)
    assert (after['Dnew'] >= 0).all() and (after['Dnew'] <= before['Dnew']).all()
    p, q, _, _, _ = T.successors(a)
    drops = after['Hnew'].ravel()[p]-after['Hnew'].ravel()[q]
    spill_before = float(EVIDENCE._flood(before['Hnew'], 8)['depth'].max())
    spill_after = float(EVIDENCE._flood(after['Hnew'], 8)['depth'].max())
    raw = (after['Dnew'].ravel()[p]-after['Dnew'].ravel()[q]
           -Q.ETA*(a['H0'].ravel()[p]-a['H0'].ravel()[q]))
    distance = np.hypot(a['world_x'].ravel()[p]-a['world_x'].ravel()[q],
                        a['world_z'].ravel()[p]-a['world_z'].ravel()[q])
    eta_residual = max(0., float((raw/Q.CAP/distance).max()))
    # Fine grids test actual descent/spill, not an eta margin enforced only at .125.
    assert (drops > 0).all(), 'A selected sampled descent became level/uphill'
    assert spill_before > .001 and spill_after <= 1e-12
    return dict(points=int(a['H0'].size), spill_before=spill_before, spill_after=spill_after,
                eta_residual_normalized_per_wu=eta_residual,
                min_selected_drop=float(drops.min()), raw_depth_increases=0,
                scope='finite synthetic sample window only; NOT infinite/global')


def check_repair(root, fixture):
    prior, direct, data = (fixture[k] for k in ('prior', 'direct', 'data'))
    negative = EVIDENCE._flood(fixture['actual']['Hnew'], 8)['depth']
    original = EVIDENCE._flood(fixture['actual']['H0'], 8)['depth']
    assert float(negative.max()) > .001 and float(original.max()) == 0
    out = root/'repair'
    report = T.solve(out, prior, direct)
    for rr in report['rounds']:
        print('REAL SCIPY synthetic round', rr['round_index'], rr['status'],
              'iterations', rr.get('iterations'), 'box', rr.get('bound_residual_unscaled'))
    assert report['success'] and report['apply_allowed'], report
    assert 1 < len(report['rounds']) <= 3 and report['frozen_inputs_unchanged']
    assert report['constraint_profiles'] == fixture['saved']['constraint_profiles']
    coeff, previous = weights(out/'weights.csv'), weights(prior/'weights.csv')
    assert coeff.keys() == previous.keys()
    assert all(0 <= coeff[k] <= previous[k] for k in coeff)
    assert all(coeff[tuple(k)] == 0 for k in report['fixed_zero_keys'])
    for rr in report['rounds']:
        folder = out/('solve-r'+str(rr['round_index']))
        assert all((folder/n).is_file() for n in ('weights.csv', 'solver.json', 'callback.jsonl', 'added-edges.csv'))
        assert rr['counts']['longitudinal'] == 17 and rr['selection']['added'] <= 2000
    final = report['rounds'][-1]['validation']
    assert final['centerline_passed'] and final['full_grid_passed'] and final['nonzero_action']
    assert all(g['violating_edges'] == 0 and g['suspect_new_no_lower_count'] == 0
               for g in final['validation2D'])
    results = {}
    for name, step, shift in [('full', .125, 0.), ('fine', .03125, 0.),
                               ('shift', .03125, .015625)]:
        results[name] = inspect_samples(surface(step, shift), previous, coeff)
        print('PASS offaxis', name, json.dumps(results[name], sort_keys=True))
    assert not report['globalPassed'] and not report['overallEffect']
    assert not final['meaningful_morphological_effect_guaranteed']
    return results


def fake_result(x, success=True):
    """Explicit numerical fault injection, not evidence of actual optimization."""
    return SimpleNamespace(x=np.array(x, copy=True), success=success, status=1 if success else 0,
        message='SYNTHETIC injected iterate', optimality=0., constr_violation=0.,
        nit=1 if success else 250, fun=0.)


def injected_round(root, name, data, minimize):
    folder = root/name
    folder.mkdir()
    report = dict(rounds=[], success=False, apply_allowed=False)
    with patch.object(T, 'minimize', minimize):
        T.rounds(folder, data, [], report)
    return report, folder


def check_zero_and_badbox(root, fixture):
    data = fixture['data']
    report, folder = injected_round(root, 'zero-iterate', data,
        lambda *a, **kw: fake_result(np.zeros(len(data['free']))))
    assert report['status'] == 'rejected_zero_action_NOT_success'
    assert not report['success'] and not report['apply_allowed'] and len(report['rounds']) == 1
    assert all(v == 0 for v in weights(folder/'weights.csv').values())
    for name, sign in [('upper-violation', 1), ('lower-violation', -1)]:
        x = .5*data['upper'][data['free']]
        x[0] = data['upper'][data['free'][0]]+5e-12 if sign > 0 else -5e-12
        report, folder = injected_round(root, name, data, lambda *a, **kw: fake_result(x))
        rr = report['rounds'][0]
        assert rr['scipy']['success'] and rr['bound_residual_unscaled'] > 1e-12
        assert not rr['numerically_accepted'] and not report['apply_allowed']
        assert report['status'] == 'rejected_numerical_convergence_or_unscaled_residual'
        assert weights(folder/'weights.csv')[next(iter(data['index']))] == x[0]
        assert len(report['rounds']) == 1 and not (folder/'safe-zero-weights.csv').exists()
        assert rr['clipped'] is False
    report, _ = injected_round(root, 'maxiter-stop', data,
        lambda *a, **kw: fake_result(.5*data['upper'][data['free']], success=False))
    assert not report['success'] and len(report['rounds']) == 1
    print('PASS fault injection: exact-zero action rejected; raw +/-5e-12 box errors and maxiter STOP')


def check_smallupper(root, fixture):
    data = dict(fixture['data'])
    data['upper'] = data['upper'].copy()
    data['upper'][data['free'][0]] = 1e-14
    folder = root/'tiny-upper'
    folder.mkdir()
    called = []
    def capture(fun, x0, **kwargs):
        bound = kwargs['bounds']
        assert bound.ub[0] == 1e-14 and bound.ub[1] == 1.-1e-13
        assert x0[0] == .5e-14 and not bound.keep_feasible.any()
        assert kwargs['options']['maxiter'] == 250 and kwargs['options']['gtol'] == 1e-10
        assert sparse.issparse(kwargs['hess'](x0)) and callable(kwargs['jac'])
        expected = data['hess'][data['free']][:, data['free']]
        np.testing.assert_array_equal(kwargs['hess'](x0).toarray(), expected.toarray())
        full = np.zeros(len(data['index']))
        full[data['free']] = x0
        np.testing.assert_array_equal(kwargs['jac'](x0), data['jac'](full)[data['free']])
        assert fun(x0) == data['fun'](full)
        called.append(True)
        return fake_result(x0)
    rr = {}
    with patch.object(T, 'minimize', capture):
        x = T.optimize(data, [], set(), folder, rr)
    assert called and x[data['free'][0]] == .5e-14 and not rr['clipped']
    assert x[data['free'][1]] == .5*(1.-1e-13)
    assert all(x[data['index'][tuple(k)]] == 0 for k in fixture['saved']['fixed_zero_keys'])
    # Exercise prior_data too: tiny positive is a free variable, not just an optimizer bound.
    prior = root/'tiny-prior'
    shutil.copytree(fixture['prior'], prior)
    Q.write_csv(prior/'weights.csv', ('cx', 'cz', 'weight'),
        ((k[0], k[1], float(data['upper'][i])) for k, i in data['index'].items()))
    out = root/'tiny-prepared'
    out.mkdir()
    restored = T.prior_data(prior, out, {})
    assert restored['upper'][0] == 1e-14 and 0 in restored['free']
    assert len(restored['free']) == len(fixture['data']['free'])
    print('PASS tiny/near-one upper exact; no clipping/elimination; fixed-zero excluded; exact jac/hess')


def check_invalid_fixed(root, fixture):
    for name, key, value in [('outside-map', (999, 999), None),
                              ('nonzero-fixed', tuple(fixture['saved']['coefficient_keys'][0]), .2),
                              ('negative-upper', None, -1e-15), ('above-one', None, 1.+1e-15),
                              ('all-zero', None, 0.)]:
        prior = root/('invalid-'+name)
        shutil.copytree(fixture['prior'], prior)
        saved = json.loads((prior/'solver.json').read_text(encoding='utf-8'))
        ww = weights(prior/'weights.csv')
        if key is not None:
            saved['fixed_zero_keys'].append(list(key))
            if value is not None:
                ww[key] = value
        elif name == 'all-zero':
            ww = {k: 0. for k in ww}
        else:
            ww[next(iter(ww))] = value
        Q.write_json(prior/'solver.json', saved)
        Q.write_csv(prior/'weights.csv', ('cx', 'cz', 'weight'),
                    ((cx, cz, w) for (cx, cz), w in ww.items()))
        out = root/('rejected-'+name)
        out.mkdir()
        try:
            T.prior_data(prior, out, {})
        except Q.RejectedInput as error:
            message = str(error)
            assert ('fixed zeros' in message if key is not None else
                    'Zero-only action' in message if name == 'all-zero' else 'Prior weights invalid' in message)
            print('PASS invalid prior', name, ':', message)
        else:
            raise AssertionError('Invalid fixed/map/bounds/zero-only prior was accepted: '+name)


def check_cut_budget(root):
    z, x = np.mgrid[0:8.001:.125, 0:8.001:.125]
    g = dict(p=np.arange(x.size), arrays=dict(H0=x, world_x=x, world_z=z),
             edge_a=sparse.csr_matrix(np.ones((x.size, 1))), edge_b=np.zeros(x.size))
    chosen, counts = T.select_cuts([g], np.ones(1), set())
    cells = {(int(x.ravel()[edge]//4), int(z.ravel()[edge]//4)) for _, edge in chosen}
    assert counts['violating_full'] > 2000 and len(chosen) <= 2000
    assert len(cells) == len(chosen) and counts['policy'].startswith('worst one')
    assert T.select_cuts([g], np.ones(1), set(chosen))[1]['already_enforced'] == len(chosen)
    for path in (HERE/'terminal-depth-solver.py', Path(__file__).resolve()):
        for node in ast.walk(ast.parse(path.read_text(encoding='utf-8'))):
            if isinstance(node, (ast.FunctionDef, ast.AsyncFunctionDef)):
                assert node.end_lineno-node.lineno+1 <= 80, (path.name, node.name)
    print('PASS >2000 violations capped by worst world coefficient-cell; all functions <=80 lines')


def run(root):
    protected = [HERE/n for n in ('terminal-depth-solver.py', 'remaining-drop-solver.py',
                                  'terminal-pit-evidence.py', 'remaining-drop-field.py')]
    protected += [p for p in (MDK/'src/main').rglob('*') if p.is_file()]
    historical = T.DEBUG_LOG
    before = {str(p): Q.digest(p) for p in protected}
    history_hash = Q.digest(historical) if historical.exists() else None
    with patch.object(T, 'DEBUG_LOG', root/'synthetic-agent.jsonl'):
        try:
            fixture = write_fixture(root)
            check_profiles(root, fixture)
            check_exterior(root)
            check_sinks(root, fixture)
            finite = check_repair(root, fixture)
            check_zero_and_badbox(root, fixture)
            check_smallupper(root, fixture)
            check_invalid_fixed(root, fixture)
            check_cut_budget(root)
        finally:
            assert before == {str(p): Q.digest(p) for p in protected}, 'Protected sources changed'
            assert history_hash == (Q.digest(historical) if historical.exists() else None), 'Historical debug changed'
    print('PASS integrity: solver/main sources and historical debug unchanged; real outputs never accessed')
    return dict(success=True, syntheticOnly=True, finiteSamples=finite,
                realOutputsTouched=False, historicalDebugUnchanged=True, protectedSourcesUnchanged=True,
                globalOrInfiniteAcceptance=False)


def main():
    (MDK/'build').mkdir(exist_ok=True)
    root = Path(tempfile.mkdtemp(prefix='terminal-depth-solver-check-', dir=MDK/'build')).resolve()
    original_out, original_err = sys.stdout, sys.stderr
    code, result = 1, dict(success=False, syntheticOnly=True)
    with (root/'stdout.log').open('w', encoding='utf-8') as stream:
        sys.stdout, sys.stderr = Tee(original_out, stream), Tee(original_err, stream)
        try:
            print('Synthetic artifacts:', root)
            result = run(root)
            code = 0
            print('PASS terminal-depth-solver synthetic regressions; finite windows only')
        except Exception as error:
            result['error'] = repr(error)
            traceback.print_exc()
        finally:
            print('EXIT', code)
            sys.stdout, sys.stderr = original_out, original_err
    Q.write_json(root/'check.json', dict(result, exitCode=code))
    (root/'exit-code.txt').write_text(str(code)+'\n', encoding='utf-8')
    return code


if __name__ == '__main__':
    raise SystemExit(main())
