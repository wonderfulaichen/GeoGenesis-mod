# -*- coding: utf-8 -*-
"""Stage-vs-stage product comparison (sandbox only).

Compares the same artifact across stages by SHA-256 and, for the text logs that carry log4j
timestamps, by timestamp-stripped content. Reports byte identity honestly: a text artifact that
only differs by a timestamp is NOT counted as a data difference, and a binary artifact that differs
is reported with the first differing byte offset so it cannot be waved away.
"""
import hashlib
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
BINARY = ['.f64be', '.csv']
LOG_TS = re.compile(r'\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}:\d{2}[,.]\d{3}')

# Artifacts whose whole content is wall-clock measurement, not terrain output. They are reported
# separately so a timing delta is never mistaken for a product delta (and vice versa).
TIMING = {'perf.csv', 'perf-geometry.log', 'perf-geometry-1831.log', 'perf-geometry-neg.log',
          'perf-geometry-far.log'}

PAIRS = [
    ('pristine', 'indexed'),      # the real A/B: no index vs chunk index, SAME geometry
    ('baseline', 'optimized'),    # the earlier pair (both were classes-cand: self-consistency only)
    ('optimized', 'indexed'),     # old square cull vs new sqrt cull, same index
]


def sha(p):
    return hashlib.sha256(p.read_bytes()).hexdigest().upper()


def strip_ts(p):
    return LOG_TS.sub('<TS>', p.read_text(encoding='utf-8', errors='replace'))


def first_diff(a, b):
    x, y = a.read_bytes(), b.read_bytes()
    for i, (p, q) in enumerate(zip(x, y)):
        if p != q:
            return i
    return min(len(x), len(y)) if len(x) != len(y) else -1


def main():
    names = sorted({q.name for s in ('pristine', 'indexed', 'baseline', 'optimized')
                    for q in (ROOT / s).glob('*') if q.is_file()}) if all(
        (ROOT / s).is_dir() for s in ('pristine', 'indexed', 'baseline', 'optimized')) else []
    if not names:
        print('no stages found')
        return 2
    rc = 0
    for left, right in PAIRS:
        la, lb = ROOT / left, ROOT / right
        if not la.is_dir() or not lb.is_dir():
            print(f'== {left} vs {right}: MISSING stage, skipped')
            continue
        same, ts_only, differ, missing, timing = [], [], [], [], []
        for n in names:
            pa, pb = la / n, lb / n
            if not pa.is_file() or not pb.is_file():
                missing.append(n)
                continue
            if sha(pa) == sha(pb):
                (timing if n in TIMING else same).append(n)
                continue
            if n in TIMING:
                timing.append(n + ' (differ)')
            elif pa.suffix in BINARY:
                differ.append(f'{n} (first byte diff @ {first_diff(pa, pb)})')
            else:
                differ.append(n) if strip_ts(pa) != strip_ts(pb) else ts_only.append(n)
        print(f'== {left} vs {right}')
        print(f'   identical bytes        : {len(same)} {same}')
        print(f'   differ only by timestamp: {len(ts_only)} {ts_only}')
        print(f'   timing-only artifacts  : {len(timing)} {sorted(timing)}')
        print(f'   REAL differences       : {len(differ)} {differ}')
        print(f'   only in one stage      : {missing}')
        if differ:
            rc = 2
        if left == 'pristine' and right == 'indexed':
            verdict = ('index changed terrain output -> bit-exactness is FALSE' if differ
                       else 'index is product-neutral on every terrain artifact (bit-exact)')
            print('   VERDICT: ' + verdict)
    return rc


if __name__ == '__main__':
    sys.exit(main())
