import math, random
import n2math as M

def fd(seed, u, v, h=1e-6):
    return ((M.sample(seed, u + h, v) - M.sample(seed, u - h, v)) / (2 * h),
            (M.sample(seed, u, v + h) - M.sample(seed, u, v - h)) / (2 * h))

def check_derivative(trials=200000, seed=12345):
    rng = random.Random(20261101)
    worst = 0.0; arg = None
    for _ in range(trials):
        u = rng.uniform(-40, 40); v = rng.uniform(-40, 40)
        du, dv = M.sample_deriv(seed, u, v)[1]
        nu, nv = fd(seed, u, v)
        e = max(abs(du - nu), abs(dv - nv))
        if e > worst:
            worst, arg = e, (u, v, du, nu, dv, nv)
    return worst, arg

def cell_derivative_bound(step=40):
    """Pessimistic bound: max |d/dt| and |d/ds| over ALL 8^4 gradient choices."""
    best_t = 0.0; best_s = 0.0; arg_t = arg_s = None
    ts = [i / step for i in range(step + 1)]
    for i in range(8):
        for j in range(8):
            for k in range(8):
                for l in range(8):
                    g = (M.GRAD[i], M.GRAD[j], M.GRAD[k], M.GRAD[l])
                    for t in ts:
                        for s in ts:
                            d00, d10, d01, d11 = M.sample_dot(*g, t, s)
                            du, dv = M.derivative(d00, d10, d01, d11, *g, t, s)
                            if abs(du) > best_t: best_t, arg_t = abs(du), (i, j, k, l, t, s)
                            if abs(dv) > best_s: best_s, arg_s = abs(dv), (i, j, k, l, t, s)
    return best_t, arg_t, best_s, arg_s

def det_stats(seeds, span=1e6, samples=200000):
    rng = random.Random(4242)
    out = {}
    for ws in seeds:
        sx, sz = M.warp_seeds(ws)
        mn = 9e9; neg = 0; arg = None
        for _ in range(samples):
            x = rng.uniform(-span, span); z = rng.uniform(-span, span)
            d = M.det(sx, sz, x, z)
            if d < 0: neg += 1
            if d < mn: mn, arg = d, (x, z)
        out[ws] = (mn, neg, arg)
    return out

if __name__ == '__main__':
    print('--- derivative vs central difference ---')
    print('worst abs error:', check_derivative())
    print('--- per-cell gradient bound (8^4 x grid) ---')
    bt, at, bs, as_ = cell_derivative_bound(20)
    print('max |d/dt| = %.6f at %s' % (bt, at))
    print('max |d/ds| = %.6f at %s' % (bs, as_))
    print('--- world derivative scale ---')
    print('SCALE*FREQ =', M.SCALE * M.FREQ, ' per-axis |do/dx| bound =', bt * M.SCALE * M.FREQ)
    print('--- Jacobian determinant (uniform range +-1e6) ---')
    for ws, (mn, neg, arg) in det_stats([9139912035078620160, 0, -419, 12345]).items():
        print('seed %d  min det=%.6f  det<0=%d  arg=%s' % (ws, mn, neg, arg))
