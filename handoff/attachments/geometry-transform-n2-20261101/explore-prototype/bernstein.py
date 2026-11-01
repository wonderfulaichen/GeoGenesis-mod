"""Rigorous per-cell enclosure of (dn/du, dn/dv) for one-octave RefNoise.Perlin.sample.

Inside one integer cell both partial derivatives are polynomials of degree <= 3 in each
of (t, s). Their Bernstein coefficients give a rigorous enclosure over the whole cell
(convex-hull property). Enumerating all 8^4 corner-gradient combos therefore covers
every point of the plane: proof by complete cases, not a sample survey.
"""
import itertools
import math
import n2math as M

DEG = 3
GRAD = M.GRAD
BINOM = [[math.comb(p, i) for i in range(p + 1)] for p in range(DEG + 1)]

Poly = dict          # (i, j) -> monomial coefficient


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
T = {(1, 0): 1.0}
S = {(0, 1): 1.0}
XS = {(2, 0): 3.0, (3, 0): -2.0}          # 3t^2 - 2t^3
DXS = {(1, 0): 6.0, (2, 0): -6.0}         # 6t - 6t^2
YS = {(0, 2): 3.0, (0, 3): -2.0}
DYS = {(0, 1): 6.0, (0, 2): -6.0}


def dots(g00, g10, g01, g11):
    """(d00, d10, d01, d11) as polynomials."""
    d00 = {(1, 0): g00[0], (0, 1): g00[1]}
    d10 = {(1, 0): g10[0], (0, 1): g10[1], (0, 0): -g10[0]}
    d01 = {(1, 0): g01[0], (0, 1): g01[1], (0, 0): -g01[1]}
    d11 = {(1, 0): g11[0], (0, 1): g11[1], (0, 0): -g11[0] - g11[1]}
    return d00, d10, d01, d11


def derivatives(g00, g10, g01, g11):
    """(d/dt, d/ds) polynomials of the interpolated value."""
    d00, d10, d01, d11 = dots(g00, g10, g01, g11)
    df0 = p_add(d10, d00, -1.0)
    df2 = p_add(d11, d01, -1.0)
    # A = g00x + dxs*(d10-d00) + xs*(g10x-g00x)
    a = p_add(p_const(g00[0]), p_mul(DXS, df0))
    a = p_add(a, p_mul(XS, p_const(g10[0] - g00[0])))
    b = p_add(p_const(g01[0]), p_mul(DXS, df2))
    b = p_add(b, p_mul(XS, p_const(g11[0] - g01[0])))
    du = p_add(p_mul(p_add(ONE, YS, -1.0), a), p_mul(YS, b))
    # dv = (1-ys)*[g00y + xs*(g10y-g00y)] + ys*[g01y + xs*(g11y-g01y)] + dys*(f2-f0)
    s0 = p_add(p_const(g00[1]), p_mul(XS, p_const(g10[1] - g00[1])))
    s2 = p_add(p_const(g01[1]), p_mul(XS, p_const(g11[1] - g01[1])))
    dv = p_add(p_mul(p_add(ONE, YS, -1.0), s0), p_mul(YS, s2))
    # f0 = d00 + xs*(d10-d00) ; f2 = d01 + xs*(d11-d01)
    f0 = p_add(d00, p_mul(XS, df0))
    f2 = p_add(d01, p_mul(XS, df2))
    dv = p_add(dv, p_mul(DYS, p_add(f2, f0, -1.0)))
    return du, dv


def bernstein_grid(coeff):
    grid = [[0.0] * (DEG + 1) for _ in range(DEG + 1)]
    for p in range(DEG + 1):
        for q in range(DEG + 1):
            total = 0.0
            for i in range(p + 1):
                for j in range(q + 1):
                    c = coeff.get((i, j), 0.0)
                    if c:
                        total += BINOM[p][i] / BINOM[DEG][i] * BINOM[q][j] / BINOM[DEG][j] * c
            grid[p][q] = total
    return grid


def convex_hull(points):
    pts = sorted(set(points))
    if len(pts) <= 2:
        return pts

    def cross(o, a, b):
        return (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])

    lower, upper = [], []
    for p in pts:
        while len(lower) >= 2 and cross(lower[-2], lower[-1], p) <= 0:
            lower.pop()
        lower.append(p)
    for p in reversed(pts):
        while len(upper) >= 2 and cross(upper[-2], upper[-1], p) <= 0:
            upper.pop()
        upper.append(p)
    return lower[:-1] + upper[:-1]


def main():
    ts = [0.0, 0.0625, 0.25, 0.5, 0.75, 0.9375, 1.0]
    corners = []
    worst = (0.0, None)
    for combo in itertools.product(range(8), repeat=4):
        g = tuple(GRAD[i] for i in combo)
        du, dv = derivatives(*g)
        for t in ts:
            for s in ts:
                d = M.sample_dot(*g, t, s)
                adu, adv = M.derivative(*d, *g, t, s)
                assert abs(p_at(du, t, s) - adu) < 1e-12, ('du', combo, t, s, p_at(du, t, s), adu)
                assert abs(p_at(dv, t, s) - adv) < 1e-12, ('dv', combo, t, s, p_at(dv, t, s), adv)
        bu, bv = bernstein_grid(du), bernstein_grid(dv)
        umin, umax = min(map(min, bu)), max(map(max, bu))
        vmin, vmax = min(map(min, bv)), max(map(max, bv))
        corners += [(umin, vmin), (umin, vmax), (umax, vmin), (umax, vmax)]
        m = max(abs(umin), abs(umax), abs(vmin), abs(vmax))
        if m > worst[0]:
            worst = (m, combo, (umin, umax, vmin, vmax))
        for t in ts:
            for s in ts:
                d = M.sample_dot(*g, t, s)
                adu, adv = M.derivative(*d, *g, t, s)
                assert umin - 1e-12 <= adu <= umax + 1e-12
                assert vmin - 1e-12 <= adv <= vmax + 1e-12
    hull = convex_hull(corners)
    k = M.SCALE * M.FREQ
    print('all 4096 gradient combos: polynomial == analytic, and inside Bernstein box')
    print('worst component enclosure', worst[0], 'combo', worst[1])
    print('hull vertices', len(hull))
    print('hull', [(round(x, 5), round(y, 5)) for x, y in hull])
    print('world scale k =', k)
    best, arg, row = 9e9, None, 0.0
    for a1, b1 in hull:
        for c1, d1 in hull:
            a, b, c, d = a1 * k, b1 * k, c1 * k, d1 * k
            det = (1 + a) * (1 + d) - b * c
            if det < best:
                best, arg = det, (a, b, c, d)
            row = max(row, abs(a) + abs(b))
    print('RIGOROUS min det over all hull vertex pairs =', best, 'at', arg)
    print('RIGOROUS sup-row-sum Lipschitz L =', row)
    print('det lower bound consistency 1-2L:', 1 - 2 * row)


if __name__ == '__main__':
    main()
