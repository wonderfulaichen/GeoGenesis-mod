# -*- coding: utf-8 -*-
"""A/B tile-path perf summary (sandbox only).

PerfGeometry runs 8 iterations per JVM on one tile: iteration 0 is cold (geometry build included),
1..7 are warm. Reporting rules:
  * cold and warm are never mixed into one number;
  * build and sample are reported separately, because the index trades one for the other;
  * per-iteration medians are used (not a single best cell) and the raw spread is printed;
  * the per-zone checksum must be identical across arms, else the comparison is void.
"""
import csv
import statistics
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
rows = list(csv.DictReader(open(ROOT / 'ab-perf.csv', encoding='utf-8-sig')))
ZONES = ['0', '1831', '-1832', '29295']
ARMS = ['baseline', 'optimized']


def pct(v, q):
    v = sorted(v)
    if not v:
        return float('nan')
    if len(v) == 1:
        return v[0]
    idx = q * (len(v) - 1)
    lo = int(idx)
    hi = min(lo + 1, len(v) - 1)
    return v[lo] + (v[hi] - v[lo]) * (idx - lo)


def sel(zone, arm, pred):
    return [r for r in rows if r['zone'] == zone and r['arm'] == arm and pred(int(r['iter']))]


def val(rs, name):
    return [float(r[name]) for r in rs]


rc = 0
print('=== correctness gate: per-zone sample checksum must match across arms')
for z in ZONES:
    s = {a: sorted(set(r['sum'] for r in sel(z, a, lambda i: True))) for a in ARMS}
    ok = s['baseline'] == s['optimized'] and len(s['baseline']) == 1
    if not ok:
        rc = 2
    print(f'  zone {z:>6}  sums equal {ok}  n={len(s["baseline"])}/{len(s["optimized"])}')
print()

print('=== COLD (iter 0, one per JVM; build of the first tile is inside buildMs) median ms')
for z in ZONES:
    b = sel(z, 'baseline', lambda i: i == 0)
    o = sel(z, 'optimized', lambda i: i == 0)
    print('  zone {:>6}  build B={:7.2f} C={:7.2f} (x{:.2f})   sample B={:8.2f} C={:8.2f} (x{:.2f})'.format(
        z, statistics.median(val(b, 'buildMs')), statistics.median(val(o, 'buildMs')),
        statistics.median(val(o, 'buildMs')) / statistics.median(val(b, 'buildMs')),
        statistics.median(val(b, 'sampleMs')), statistics.median(val(o, 'sampleMs')),
        statistics.median(val(o, 'sampleMs')) / statistics.median(val(b, 'sampleMs'))))
print()

print('=== WARM (iter 1..7) buildMs P50/P95 ms')
for z in ZONES:
    for a in ARMS:
        v = val(sel(z, a, lambda i: i >= 1), 'buildMs')
        print('  zone {:>6} {:<10} n={:2d} P50={:7.2f} P95={:7.2f} min={:7.2f} max={:7.2f}'.format(
            z, a, len(v), pct(v, .5), pct(v, .95), min(v), max(v)))
print()

print('=== WARM (iter 1..7) sampleMs P50/P95 ms (10k land samples)')
for z in ZONES:
    for a in ARMS:
        v = val(sel(z, a, lambda i: i >= 1), 'sampleMs')
        print('  zone {:>6} {:<10} n={:2d} P50={:8.2f} P95={:8.2f} min={:8.2f} max={:8.2f}'.format(
            z, a, len(v), pct(v, .5), pct(v, .95), min(v), max(v)))
print()

print('=== WARM sample P50 ratio (optimized/baseline), <1 means the index wins')
for z in ZONES:
    b = val(sel(z, 'baseline', lambda i: i >= 1), 'sampleMs')
    o = val(sel(z, 'optimized', lambda i: i >= 1), 'sampleMs')
    print('  zone {:>6}  ratio={:.3f}  ({:+.1f}% time)'.format(z, pct(o, .5) / pct(b, .5),
                                                              (pct(o, .5) / pct(b, .5) - 1) * 100))
print()

print('=== warm sample ratioper round (paired, same round = same machine condition)')
for rd in sorted({int(r['round']) for r in rows}):
    parts = []
    for z in ZONES:
        b = [float(r['sampleMs']) for r in rows if r['round'] == str(rd) and r['zone'] == z
             and r['arm'] == 'baseline' and int(r['iter']) >= 1]
        o = [float(r['sampleMs']) for r in rows if r['round'] == str(rd) and r['zone'] == z
             and r['arm'] == 'optimized' and int(r['iter']) >= 1]
        parts.append('z{:<6} {:.3f}'.format(z, statistics.median(o) / statistics.median(b)))
    print('  round {}  {}'.format(rd, '  '.join(parts)))
sys.exit(rc)
