"""Frozen S1 QP diagnosis only; bounded callback capture, never production or baseline edits."""
import importlib.util
import json
from pathlib import Path
import shutil
import sys
import time

import numpy as np
from scipy import sparse
from scipy.optimize import linprog

ROOT = Path(__file__).resolve().parents[2]
LOG = ROOT.parent / 'debug-agent.log'
BASE = ROOT / 'build/remaining-drop-field-20261104-final'


def log(hypothesis, location, **data):
    # region agent log: keep until user confirms the fix.
    with LOG.open('a', encoding='utf-8') as stream:
        stream.write(json.dumps(dict(hypothesis=hypothesis, location=location, **data), allow_nan=False)+'\n')
    # endregion


def main():
    out = ROOT / sys.argv[1]
    out.mkdir(exist_ok=False)
    shutil.copytree(BASE/'export', out/'export')
    spec = importlib.util.spec_from_file_location('rdsolver', ROOT/'src/diagnostics/remaining-drop-solver.py')
    solver = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(solver)
    original = solver.minimize
    mode = sys.argv[2] if len(sys.argv)>2 else 'capture'
    def capture(fun, initial, **kwargs):
        constraint = kwargs['constraints'][0]
        a, ub = constraint.A.tocsr(), constraint.ub
        sparse.save_npz(out/'A.npz', a)
        np.savez(out/'inputs.npz', upper=ub, initial=initial, gradient=kwargs['jac'](initial))
        norm = np.asarray(abs(a).max(axis=1).toarray()).ravel()
        slack = ub-a@initial
        log('A', 'initial', rows=a.shape[0], cols=a.shape[1], violated=int((slack<0).sum()),
            row_norm_quantiles=np.quantile(norm,[0,.01,.5,.99,1]).tolist(),
            slack_quantiles=np.quantile(slack,[0,.01,.5,.99,1]).tolist(),
            objective=float(fun(initial)), gradient_inf=float(abs(kwargs['jac'](initial)).max()))
        lp = linprog(kwargs['jac'](initial), A_ub=a, b_ub=ub, bounds=(0,1), method='highs')
        if lp.success:
            np.save(out/'lp-direction.npy',lp.x)
            log('B','feasible_nonzero_witness',success=True,objective=float(fun(lp.x)),
                coefficient_quantiles=np.quantile(lp.x,[0,.5,.9,1]).tolist(),
                residual=float(max(0,(a@lp.x-ub).max())))
        else:
            log('B','feasible_nonzero_witness',success=False,message=str(lp.message))
        if mode == 'scaled':
            from scipy.optimize import LinearConstraint
            scale=1/np.maximum(norm,1e-15)
            kwargs['constraints']=[LinearConstraint(sparse.diags(scale)@a,-np.inf,ub*scale)]
        def callback(x,state):
            if state.nit<=5 or state.nit%10==0:
                log('A' if mode=='capture' else 'C','iteration', mode=mode,nit=int(state.nit),
                    f=float(state.fun),optimality=float(state.optimality),
                    violation=float(state.constr_violation),radius=float(state.tr_radius),
                    barrier=float(state.barrier_parameter),cg=int(state.cg_niter),
                    coefficient_quantiles=np.quantile(x,[0,.5,.9,1]).tolist(),
                    raw_residual=float(max(0,(a@x-ub).max())))
            return mode=='capture' and state.nit>=60
        kwargs['callback']=callback
        started=time.perf_counter()
        result=original(fun,initial,**kwargs)
        log('A' if mode=='capture' else 'C','result',mode=mode,success=bool(result.success),
            nit=int(result.nit),f=float(result.fun),seconds=time.perf_counter()-started,
            optimality=float(result.optimality),violation=float(result.constr_violation))
        return result
    solver.minimize=capture
    report=solver.solve(out)
    print(json.dumps({k:report[k] for k in ['status','iterations','converged']},ensure_ascii=False))


if __name__=='__main__':
    main()
