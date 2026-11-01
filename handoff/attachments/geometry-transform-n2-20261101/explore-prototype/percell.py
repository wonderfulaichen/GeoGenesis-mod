"""Per-cell rigorous det J enclosure with the ACTUAL seed pair of each warp axis.

Inside one integer cell of the u-plane the corner gradients are fixed, so a single
gradient combo per axis applies. Their Bernstein boxes rigorously enclose (dn/du,dn/dv)
over that whole cell. det J = (1+ka)(1+kd) - k^2 bc is multi-affine in (a,b,c,d), so its
minimum over the boxes is attained at a box corner: we get a rigorous per-cell lower
bound, and scanning cells gives a rigorous statement on the scanned region.
"""
import itertools
import math
import n2math as M
from rigorous import derivatives, bernstein_grid, box, GRAD

K = M.SCALE * M.FREQ


def cell_box(seed, x2, y2):
    g = (GRAD[M.grad_index(seed, x2, y2)], GRAD[M.grad_index(seed, x2 + 1, y2)],
         GRAD[M.grad_index(seed, x2, y2 + 1)], GRAD[M.grad_index(seed, x2 + 1, y2 + 1)])
    du, dv = derivatives(*g)
    bu, bv = bernstein_grid(du), bernstein_grid(dv)
    return box(bu), box(bv)


def cell_det_bounds(seed_x, seed_z, x2, y2):
    (a0, a1), (b0, b1) = cell_box(seed_x, x2, y2)
    (c0, c1), (d0, d1) = cell_box(seed_z, x2, y2)
    lo, hi = 9e9, -9e9
    for a in (a0, a1):
        for b in (b0, b1):
            for c in (c0, c1):
                for d in (d0, d1):
                    det = (1 + a * K) * (1 + d * K) - b * K * c * K
                    lo, hi = min(lo, det), max(hi, det)
    return lo, hi


def scan(world_seed, radius_cells=60, stride=1):
    sx, sz = M.warp_seeds(world_seed)
    worst = (9e9, None)
    rowmax = 0.0
    n = 0
    for i in range(-radius_cells, radius_cells + 1, stride):
        for j in range(-radius_cells, radius_cells + 1, stride):
            lo, hi = cell_det_bounds(sx, sz, i, j)
            n += 1
            if lo < worst[0]:
                worst = (lo, (i, j, hi))
            for seed in (sx, sz):
                (a0, a1), (b0, b1) = cell_box(seed, i, j)
                rowmax = max(rowmax, abs(a0) + abs(b0), abs(a1) + abs(b1))
    return n, worst, rowmax


if __name__ == '__main__':
    for ws in (9139912035078620160, 0, -419, 12345):
        n, worst, rowmax = scan(ws)
        print('world seed %d  cells=%d  min rigorous det J=%.6f at %s  sup row-sum L=%.6f'
              % (ws, n, worst[0], worst[1], rowmax * K))
