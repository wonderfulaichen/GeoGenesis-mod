# -*- coding: utf-8 -*-
"""Element-wise bit comparison of the 1401x1401 height grids (sandbox only).

A matching file SHA is already proof, but the master asked for a value-by-value comparison, so this
walks all 1,962,801 cells and reports, per pair:
  * count of cells whose 64-bit pattern differs (NaN-aware: same NaN payload = same bits),
  * the largest ULP gap and the largest absolute difference, plus where it happens,
  * the signed-difference histogram at ULP granularity, so "identical" is not a slogan.

Pairs:
  pristine vs indexed  -> the index claim (must be 0 differing cells)
  indexed  vs 3A7686   -> different candidate (whole-geometry change), for scale only
"""
import hashlib
import struct
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
N = 1401 * 1401
# DataOutputStream.writeDouble emits BIG-endian, so the grid is stored as >f8.
END = '>'


def load(p):
    raw = Path(p).read_bytes()
    if len(raw) != N * 8:
        raise ValueError(f'{p}: {len(raw)} bytes, expected {N * 8}')
    return raw, hashlib.sha256(raw).hexdigest().upper()


def ulp_gap(a, b):
    """Signed ULP distance between two finite doubles.

    Order-preserving map of the IEEE-754 bit pattern onto the integers: negatives are mirrored so
    that adjacent representable values differ by exactly 1. (The earlier version subtracted with a
    signed-int offset and produced nonsense gaps of 1e19; unsigned keys make the gap exact.)
    """
    ua = struct.unpack(END + 'Q', struct.pack(END + 'd', a))[0]
    ub = struct.unpack(END + 'Q', struct.pack(END + 'd', b))[0]
    ka = ua ^ 0x8000000000000000 if ua < 0x8000000000000000 else (0xFFFFFFFFFFFFFFFF - ua)
    kb = ub ^ 0x8000000000000000 if ub < 0x8000000000000000 else (0xFFFFFFFFFFFFFFFF - ub)
    return kb - ka


def compare(left, right, label):
    ra, sa = load(ROOT / left)
    rb, sb = load(ROOT / right)
    same_sha = sa == sb
    differ = 0
    max_ulp = 0
    max_abs = 0.0
    where_abs = None
    hist = {}
    for i in range(N):
        pa = ra[i * 8:(i + 1) * 8]
        pb = rb[i * 8:(i + 1) * 8]
        if pa == pb:
            continue
        differ += 1
        a = struct.unpack(END + 'd', pa)[0]
        b = struct.unpack(END + 'd', pb)[0]
        if a != a and b != b:      # both NaN but different payload
            hist['nan-payload'] = hist.get('nan-payload', 0) + 1
            continue
        d = b - a
        ad = abs(d)
        if ad > max_abs:
            max_abs = ad
            where_abs = (i % 1401, i // 1401)
        g = ulp_gap(a, b)
        if abs(g) > max_ulp:
            max_ulp = abs(g)
        hist[g] = hist.get(g, 0) + 1
    print(f'== {label}')
    print(f'   left  {left}  sha={sa}')
    print(f'   right {right}  sha={sb}')
    print(f'   file SHA equal          : {same_sha}')
    print(f'   cells compared          : {N}')
    print(f'   cells with differing bits: {differ}'
          + ('' if differ else '   <-- every cell bit-identical'))
    if differ:
        print(f'   max |delta| = {max_abs:.6g} at grid (x={where_abs[0]}, z={where_abs[1]})')
        print(f'   max ULP gap = {max_ulp}')
        top = sorted(hist.items(), key=lambda kv: -kv[1])[:6]
        print('   ULP histogram (top): ' + ', '.join(f'{k}:{v}' for k, v in top))
    print()
    return 0 if differ == 0 else 2


def main():
    rc = compare('indexed/double-heights.f64be', 'pristine/double-heights.f64be',
                 'INDEX CLAIM: chunk-index land vs pristine land (same geometry 8C26D068)')
    rc2 = compare('pristine/double-heights.f64be', 'refs/merged-3A7686C5-double-heights.f64be',
                  'CONTEXT ONLY: new candidate vs merged 3A7686 candidate (geometry differs)')
    # rc2 is expected non-zero (different candidate); it must not fail the run, but it must not be
    # silently dropped either: print it.
    print(f'exit codes: index-claim={rc}  context={rc2} (context is expected non-zero)')
    return rc


if __name__ == '__main__':
    sys.exit(main())
