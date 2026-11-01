"""Joint per-cell Jacobian enclosure: (du,dv) hulls of nx and nz, paired exhaustively.

Each of the 4096 corner-gradient combinations yields a 2-D convex hull that rigorously
contains (dn/du, dn/dv) over the whole unit cell. det J = (1+ka)(1+kd) - (kb)(kc),
so pairing every x-hull with every z-hull bounds det J from below for every cell and
every seed pair -- a proof by complete cases, not sampling.
"""
import itertools
import math
import n2math as M
from rigorous import derivatives, bernstein_grid, restrict2, box, hull, GRAD

K = M.SCALE * M.FREQ


def cell_hulls(levels):
    out = []
    for combo in itertools.product(range(8), repeat=4):
        g = tuple(GRAD[i] for i in combo)
        du, dv = derivatives(*g)
        gu, gv = bernstein_grid(du), bernstein_grid(dv)
        pts = []
        for (t0, t1, s0, s1, leaf) in [(a, b, c, d, e) for (a, b, c, d, e) in _leaves(gu, levels)]:
            lv = restrict2(gv, t0, t1, s0, s1)
            umin, umax = box(leaf)
            vmin, vmax = box(lv)
            pts += [(umin, vmin), (umin, vmax), (umax, vmin), (umax, vmax)]
        out.append(hull(pts))
    return out


def _leaves(grid, levels):
    stack = [(0.0, 1.0, 0.0, 1.0, grid)]
    for _ in range(levels):
        nxt = []
        for (t0, t1, s0, s1, g) in stack:
            tm, sm = (t0 + t1) / 2, (s0 + s1) / 2
            nxt.append((t0, tm, s0, sm, restrict2(g, 0, .5, 0, .5)))
            nxt.append((t0, tm, sm, s1, restrict2(g, 0, .5, .5, 1)))
            nxt.append((tm, t1, s0, sm, restrict2(g, .5, 1, 0, .5)))
            nxt.append((tm, t1, sm, s1, restrict2(g, .5, 1, .5, 1)))
        stack = nxt
    return stack


def det_min(hulls):
    best = 9e9; arg = None
    for hx in hulls:
        for hz in hulls:
            for (a, b) in hx:
                for (c, d) in hz:
                    a2, b2, c2, d2 = a * K, b * K, c * K, d * K
                    det = (1 + a2) * (1 + d2) - b2 * c2
                    if det < best:
                        best, arg = det, (a, b, c, d)
    return best, arg


if __name__ == '__main__':
    for levels in (0, 1):
        hs = cell_hulls(levels)
        sizes = sorted({len(h) for h in hs})
        print('levels=%d hulls=%d vertex-counts=%s' % (levels, len(hs), sizes))
        best, arg = det_min(hs)
        print('  universal min det J over ALL cells and ALL seed pairs =', best)
        print('  arg (dn/du, dn/dv for x-z axes) =', arg)
        rowmax = max(max(abs(a) + abs(b) for a, b in h) for h in hs)
        print('  universal sup row-sum |a|+|b| <=', rowmax, '-> world', rowmax * K)
