"""Rigorous enclosures for the N2 continuous Perlin primitive (exploration prototype).

One-octave RefNoise.Perlin.sample inside one integer cell is
    v(t,s) = (1-ys)*[(1-xs)d00 + xs*d10] + ys*[(1-xs)d01 + xs*d11]
with xs = 3t^2-2t^3, ys = 3s^2-2s^3 and d.. affine in (t,s).
Hence both partial derivatives are polynomials of degree <= 4 in each variable.

Bernstein coefficients of a polynomial give a rigorous enclosure of its range over the
cell (convex hull of the coefficients). Enumerating all 8^4 corner-gradient choices
covers every point of the plane: proof by complete cases. de Casteljau subdivision
tightens the enclosure monotonically, so we can also report how tight the proof is.
"""
import itertools
import math
import n2math as M

DEG = 4
GRAD = M.GRAD
BINOM = [[math.comb(p, i) for i in range(p + 1)] for p in range(DEG + 1)]


def p_const(c):
    return {(0, 0): float(c)} if c else {}


def p_add(a, b, f=1.0):
    out = dict(a)
    for k, v in b.items():
        out[k] = out.get(k, 0.0) + f * v
    return {k: v for k, v in out.items() if v}


def p_mul(a, b):
    out = {}
    for (i1, j1), v1 in a.items():
        for (i2, j2), v2 in b.items():
            k = (i1 + i2, j1 + j2)
            out[k] = out.get(k, 0.0) + v1 * v2
    return {k: v for k, v in out.items() if v}


def p_at(a, t, s):
    return sum(v * t ** i * s ** j for (i, j), v in a.items())


ONE = p_const(1)
XS = {(2, 0): 3.0, (3, 0): -2.0}
DXS = {(1, 0): 6.0, (2, 0): -6.0}
YS = {(0, 2): 3.0, (0, 3): -2.0}
DYS = {(0, 1): 6.0, (0, 2): -6.0}


def dots(g00, g10, g01, g11):
    return ({(1, 0): g00[0], (0, 1): g00[1]},
            {(1, 0): g10[0], (0, 1): g10[1], (0, 0): -g10[0]},
            {(1, 0): g01[0], (0, 1): g01[1], (0, 0): -g01[1]},
            {(1, 0): g11[0], (0, 1): g11[1], (0, 0): -g11[0] - g11[1]})


def derivatives(g00, g10, g01, g11):
    d00, d10, d01, d11 = dots(g00, g10, g01, g11)
    df0, df2 = p_add(d10, d00, -1.0), p_add(d11, d01, -1.0)
    # d/dt of the x-lerps: tangent term (dxs * Δ) + weight term (xs * Δtangent)
    a = p_add(p_add(p_const(g00[0]), p_mul(DXS, df0)), p_mul(XS, p_const(g10[0] - g00[0])))
    b = p_add(p_add(p_const(g01[0]), p_mul(DXS, df2)), p_mul(XS, p_const(g11[0] - g01[0])))
    du = p_add(p_mul(p_add(ONE, YS, -1.0), a), p_mul(YS, b))
    # d/ds
    s0 = p_add(p_const(g00[1]), p_mul(XS, p_const(g10[1] - g00[1])))
    s2 = p_add(p_const(g01[1]), p_mul(XS, p_const(g11[1] - g01[1])))
    dv = p_add(p_mul(p_add(ONE, YS, -1.0), s0), p_mul(YS, s2))
    f0, f2 = p_add(d00, p_mul(XS, df0)), p_add(d01, p_mul(XS, df2))
    dv = p_add(dv, p_mul(DYS, p_add(f2, f0, -1.0)))
    return du, dv


def bernstein_grid(coeff):
    g = [[0.0] * (DEG + 1) for _ in range(DEG + 1)]
    for p in range(DEG + 1):
        for q in range(DEG + 1):
            total = 0.0
            for i in range(p + 1):
                for j in range(q + 1):
                    c = coeff.get((i, j), 0.0)
                    if c:
                        total += BINOM[p][i] / BINOM[DEG][i] * BINOM[q][j] / BINOM[DEG][j] * c
            g[p][q] = total
    return g


def split1(b, u):
    row = list(b)
    left, right = [row[0]], [row[-1]]
    while len(row) > 1:
        row = [row[i] + u * (row[i + 1] - row[i]) for i in range(len(row) - 1)]
        left.append(row[0])
        right.append(row[-1])
    return left, right[::-1]


def restrict1(b, lo, hi):
    if lo <= 0.0 and hi >= 1.0:
        return list(b)
    seg = split1(b, hi)[0] if hi < 1.0 else list(b)
    if lo <= 0.0:
        return seg
    return split1(seg, lo / hi)[1] if hi > 0 else seg


def restrict2(grid, t0, t1, s0, s1):
    rows = [restrict1(grid[p], s0, s1) for p in range(len(grid))]
    out = [[0.0] * len(grid) for _ in range(len(grid))]
    for q in range(len(grid)):
        col = restrict1([rows[p][q] for p in range(len(grid))], t0, t1)
        for p in range(len(grid)):
            out[p][q] = col[p]
    return out


def leaves(grid, levels):
    stack = [(0.0, 1.0, 0.0, 1.0, grid)]
    for _ in range(levels):
        nxt = []
        for (t0, t1, s0, s1, g) in stack:
            tm, sm = (t0 + t1) / 2.0, (s0 + s1) / 2.0
            nxt.append((t0, tm, s0, sm, restrict2(g, 0.0, 0.5, 0.0, 0.5)))
            nxt.append((t0, tm, sm, s1, restrict2(g, 0.0, 0.5, 0.5, 1.0)))
            nxt.append((tm, t1, s0, sm, restrict2(g, 0.5, 1.0, 0.0, 0.5)))
            nxt.append((tm, t1, sm, s1, restrict2(g, 0.5, 1.0, 0.5, 1.0)))
        stack = nxt
    return stack


def box(g):
    return min(map(min, g)), max(map(max, g))


def hull(points):
    pts = sorted(set(points))

    def cross(o, a, b):
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])

    lo, up = [], []
    for p in pts:
        while len(lo) >= 2 and cross(lo[-2], lo[-1], p) <= 0:
            lo.pop()
        lo.append(p)
    for p in reversed(pts):
        while len(up) >= 2 and cross(up[-2], up[-1], p) <= 0:
            up.pop()
        up.append(p)
    return lo[:-1] + up[:-1]


if __name__ == '__main__':
    ts = [0.0, 0.0625, 0.25, 0.5, 0.75, 0.9375, 1.0]
    # 1) polynomial identity check against the analytic derivative
    worst = 0.0
    for combo in itertools.product(range(8), repeat=4):
        g = tuple(GRAD[i] for i in combo)
        du, dv = derivatives(*g)
        for t in ts:
            for s in ts:
                d = M.sample_dot(*g, t, s)
                adu, adv = M.derivative(*d, *g, t, s)
                worst = max(worst, abs(p_at(du, t, s) - adu), abs(p_at(dv, t, s) - adv))
    print('max |polynomial - analytic derivative| over all combos =', worst)

    # 2) rigorous enclosure, tightening with subdivision
    for levels in (0, 1, 2, 3, 4):
        comp = 0.0
        norm = 0.0
        who = None
        for combo in itertools.product(range(8), repeat=4):
            g = tuple(GRAD[i] for i in combo)
            du, dv = derivatives(*g)
            bu, bv = bernstein_grid(du), bernstein_grid(dv)
            for (t0, t1, s0, s1, gu) in leaves(bu, levels):
                gv = restrict2(bv, t0, t1, s0, s1)
                umin, umax = box(gu)
                vmin, vmax = box(gv)
                comp = max(comp, abs(umin), abs(umax), abs(vmin), abs(vmax))
                n = max(math.hypot(u, v) for u in (umin, umax) for v in (vmin, vmax))
                if n > norm:
                    norm, who = n, (combo, t0, s0)
        k = M.SCALE * M.FREQ
        print('levels=%d  |component|<=%.6f (world %.6f)  ||grad||<=%.6f (world %.6f)  at %s'
              % (levels, comp, comp * k, norm, norm * k, who))
