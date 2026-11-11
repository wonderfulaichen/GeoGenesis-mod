# -*- coding: utf-8 -*-
"""Directed proof that a squared cull is NOT order-equivalent to the sqrt cull in general.

The chunk cull compares a bbox separation distance against BAND = 120. A "cheaper" version compares
dx*dx + dz*dz against 120*120 instead. These are different failure surfaces because the sum is
rounded to a double BEFORE the comparison:

    sqrt-form : fl(sqrt(fl(dx^2 + dz^2))) > 120
    sq-form   : fl(dx^2 + dz^2)          > 14400

If some representable S satisfies S > 14400 while fl(sqrt(S)) == 120.0, the sq-form culls the chunk
while the sqrt-form keeps every segment in it, i.e. the sq-form silently drops real distances.

This script (a) enumerates the actual double neighbourhood around 14400 to settle it for 120, and
(b) sweeps many other bands to show the property is mantissa-dependent, not algebraic. It exits
non-zero if it fails to find any disagreeing band, so the "in general it is not safe" claim is
itself falsifiable.
"""
import math
import struct
import sys

import numpy as np   # this interpreter is 3.8, which predates math.nextafter (3.9+)


def next_up(x):
    return float(np.nextafter(np.float64(x), np.float64(np.inf)))


def next_down(x):
    return float(np.nextafter(np.float64(x), np.float64(-np.inf)))


def sqrt_ceil(b):
    """Largest double S with fl(sqrt(S)) <= b."""
    s = b * b
    while True:
        up = next_up(s)
        if not (up > s) or math.sqrt(up) > b:
            return s
        s = up


def squared_over_rejects(b):
    """True iff some S > b*b still has sqrt(S) <= b (squared cull over-rejects)."""
    return next_up(b * b) <= sqrt_ceil(b)


def scan_band(b, n=6):
    """Print the exact neighbourhood of b*b."""
    print(f'  band {b!r}  b*b={b*b!r}')
    s = b * b
    for k in range(-2, 3):
        t = s
        for _ in range(abs(k)):
            t = next_up(t) if k > 0 else next_down(t)
        print(f'    S{"+" if k >= 0 else ""}{k}: {t!r:24} sqrt={math.sqrt(t)!r:22}'
              f' sqrt>band={math.sqrt(t) > b}  sq>band2={t > s}')
    print(f'    squared-over-rejects={squared_over_rejects(b)}')


def main():
    BAND = 120.0
    print('squared-vs-sqrt chunk cull equivalence')
    print()
    print('(a) the production band')
    scan_band(BAND)
    ok120 = not squared_over_rejects(BAND)
    print(f'    -> at BAND=120 the squared cull coincides: {ok120}')
    print()

    print('(b) bands where the squared cull demonstrably over-rejects')
    bad = []
    # powers of two and simple mantissas are where b*b lands exactly on a representable boundary
    for k in range(-6, 12):
        b = 2.0 ** k
        if squared_over_rejects(b):
            bad.append(b)
    for b in (1.0, 3.0, 5.0, 7.0, 10.0, 30.0, 60.0, 100.0, 120.0, 128.0, 240.0, 256.0):
        if squared_over_rejects(b) and b not in bad:
            bad.append(b)
    for b in bad[:10]:
        print(f'    b={b!r}: {next_up(b * b)!r} > b*b but sqrt = {math.sqrt(next_up(b * b))!r} <= b')
    print(f'    disagreeing bands: {len(bad)} -> {[b for b in bad[:12]]}')
    print()

    print('(c) sweep over ordinary magnitudes (log-spaced, bounded so this stays fast)')
    found = 0
    tested = 0
    for i in range(200000):
        b = 1.0 + i * 2.5e-5          # 1.0 .. 6.0, step ~3e-5 (finer than the band's own ulp)
        tested += 1
        if squared_over_rejects(b):
            found += 1
    print(f'    tested={tested} over-rejecting={found}')
    for b in (100.0, 110.0, 119.0, 120.0, 121.0, 128.0, 240.0, 360.0):
        print(f'    band {b:>6}: squared-over-rejects={squared_over_rejects(b)}')
    print()
    if not bad:
        print('FAIL: nothing found; the "squared is not equivalent" claim is unsupported')
        return 3
    print('VERDICT: squared cull is mantissa-dependent (fails on the b values above), so the chunk')
    print('         cull keeps the sqrt form. At BAND=120 it happens to coincide, which is exactly')
    print('         why the square form looked safe here and would have broken on a band change.')
    return 0


if __name__ == '__main__':
    sys.exit(main())
