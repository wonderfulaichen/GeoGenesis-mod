"""Tight rigorous per-cell det J: convex-hull vertex pairing.

Inside one integer cell, with the ACTUAL corner gradients of each axis, the Bernstein
grids give a rigorous convex polygon containing (dn/du, dn/dv) over the whole cell.
det J = (1+ka)(1+kd) - k^2 b c is multi-affine in (a,b,c,d), hence its minimum over the
product of the two polygons is attained at a PAIR OF VERTICES. Subdividing the cell
(de Casteljau) shrinks both polygons monotonically, tightening the bound.

This yields, for a stated set of cells and a stated seed pair, a rigorous det J > 0
statement -- local injectivity of the warp on those cells (C1 + nonzero Jacobian).
It is NOT a global proof: cells outside the scan are simply not covered.
"""
import math
import sys
import n2math as M
from rigorous import derivatives, bernstein_grid, restrict2, box, hull, GRAD

K = M.SCALE * M.FREQ


def axis_hull(seed, x2, y2, levels):
    g = (GRAD[M.grad_index(seed, x2, y2)], GRAD[M.grad_index(seed, x2 + 1, y2)],
         GRAD[M.grad_index(seed, x2, y2 + 1)], GRAD[M.grad_index(seed, x2 + 1, y2 + 1)])
    du, dv = derivatives(*g)
    gu, gv = bernstein_grid(du), bernstein_grid(dv)
    pts = []
    for (t0, t1, s0, s1) in rects(levels):
        umin, umax = box(restrict2(gu, t0, t1, s0, s1))
        vmin, vmax = box(restrict2(gv, t0, t1, s0, s1))
        pts += [(umin, vmin), (umin, vmax), (umax, vmin), (umax, vmax)]
    return hull(pts)


def rects(levels):
    out = [(0.0, 1.0, 0.0, 1.0)]
    for _ in range(levels):
        nxt = []
        for (t0, t1, s0, s1) in out:
            tm, sm = (t0 + t1) / 2, (s0 + s1) / 2
            nxt += [(t0, tm, s0, sm), (t0, tm, sm, s1), (tm, t1, s0, sm), (tm, t1, sm, s1)]
        out = nxt
    return out


def cell_det_min(seed_x, seed_z, x2, y2, levels):
    hx = axis_hull(seed_x, x2, y2, levels)
    hz = axis_hull(seed_z, x2, y2, levels)
    best = 9e9
    for (a, b) in hx:
        for (c, d) in hz:
            det = (1 + a * K) * (1 + d * K) - b * K * c * K
            best = min(best, det)
    return best


def scan(world_seed, cells=40, levels=2):
    sx, sz = M.warp_seeds(world_seed)
    worst = (9e9, None)
    neg = 0
    n = 0
    for i in range(-cells, cells + 1):
        for j in range(-cells, cells + 1):
            v = cell_det_min(sx, sz, i, j, levels)
            n += 1
            if v < 0:
                neg += 1
            if v < worst[0]:
                worst = (v, (i, j))
    return n, neg, worst


if __name__ == '__main__':
    levels = int(sys.argv[1]) if len(sys.argv) > 1 else 2
    for ws in (9139912035078620160, 0, -419, 12345):
        n, neg, worst = scan(ws, 40, levels)
        print('levels=%d world seed %d cells=%d  min det J=%.6f at cell %s  cells with det<0: %d'
              % (levels, ws, n, worst[0], worst[1], neg))
