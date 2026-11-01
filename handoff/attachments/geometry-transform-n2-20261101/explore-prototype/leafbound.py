"""Per-leaf rigorous bound on det J of the warp map.

For a warp axis with corner gradients g (one of 8^4 choices), the Bernstein grid of the
derivative gives a rigorous box of (dn/du, dn/dv) over that cell. Restricting to a
sub-cell (de Casteljau) shrinks the box. Taking the union over all 4096 gradient choices
gives a box valid for ANY seed inside that sub-cell; det J = (1+ka)(1+kd) - k^2 b c is
multi-affine, so its minimum over the box is attained at a corner.

Pairing x and z boxes must happen at the SAME sub-cell (same (u,v)) but with independent
seeds, so the union box is the right object: it covers every seed pair exactly.
"""
import itertools
import sys
import n2math as M
from rigorous import derivatives, bernstein_grid, restrict2, box, GRAD

K = M.SCALE * M.FREQ


def leaves(levels):
    # (t0,t1,s0,s1) parameter rectangles; restriction done per combo inside
    out = [(0.0, 1.0, 0.0, 1.0)]
    for _ in range(levels):
        nxt = []
        for (t0, t1, s0, s1) in out:
            tm, sm = (t0 + t1) / 2, (s0 + s1) / 2
            nxt += [(t0, tm, s0, sm), (t0, tm, sm, s1), (tm, t1, s0, sm), (tm, t1, sm, s1)]
        out = nxt
    return out


def combo_box(combo, rect):
    g = tuple(GRAD[i] for i in combo)
    du, dv = derivatives(*g)
    gu, gv = bernstein_grid(du), bernstein_grid(dv)
    t0, t1, s0, s1 = rect
    umin, umax = box(restrict2(gu, t0, t1, s0, s1))
    vmin, vmax = box(restrict2(gv, t0, t1, s0, s1))
    return umin, umax, vmin, vmax


def main(levels):
    worst = 9e9
    worst_rect = None
    argbox = None
    rowmax = 0.0
    comp = 0.0
    combos = list(itertools.product(range(8), repeat=4))
    for rect in leaves(levels):
        amin = bmin = cmin = dmin = 9e9
        amax = bmax = cmax = dmax = -9e9
        for combo in combos:
            u0, u1, v0, v1 = combo_box(combo, rect)
            amin, amax = min(amin, u0), max(amax, u1)
            bmin, bmax = min(bmin, v0), max(bmax, v1)
            cmin, cmax = min(cmin, u0), max(cmax, u1)
            dmin, dmax = min(dmin, v0), max(dmax, v1)
        comp = max(comp, abs(amin), abs(amax), abs(bmin), abs(bmax))
        rowmax = max(rowmax, abs(amin) + abs(bmin), abs(amax) + abs(bmax))
        lo = 9e9
        for a in (amin, amax):
            for b in (bmin, bmax):
                for c in (cmin, cmax):
                    for d in (dmin, dmax):
                        det = (1 + a * K) * (1 + d * K) - b * K * c * K
                        lo = min(lo, det)
        if lo < worst:
            worst, worst_rect, argbox = lo, rect, (amin, amax, bmin, bmax)
    print('levels=%d  leaves=%d' % (levels, len(leaves(levels))))
    print('  rigorous min det J (worst leaf, worst seed pair) = %.6f at rect %s box %s'
          % (worst, worst_rect, argbox))
    print('  rigorous sup |component| = %.6f world, sup row-sum L = %.6f world'
          % (comp * K, rowmax * K))


if __name__ == '__main__':
    main(int(sys.argv[1]) if len(sys.argv) > 1 else 0)
