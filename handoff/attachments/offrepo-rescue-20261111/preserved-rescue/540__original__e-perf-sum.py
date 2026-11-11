# -*- coding: utf-8 -*-
"""Three-arm full e-chain summary (sandbox only).

Honesty rules encoded here:
  * every metric is reported as a distribution (P50/P95) over rounds, never a single best shot;
  * the arms are identified by their checksum, not by their label: B and C must have identical
    land-chain sums (same geometry, only the index differs), otherwise the timing comparison is
    void and it says so;
  * the cold number (firstEms, which includes one tile build) is reported separately from the warm
    ones, because the index makes tile build MORE expensive and sampling cheaper.
"""
import csv
import statistics
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
rows = list(csv.DictReader(open(ROOT / 'e-perf.csv', encoding='utf-8-sig')))
ZONES = ['0', '1831', '-1832', '29295']
ARMS = {'A': 'A 3A7686 (old geom CF4FCA7E + old land)',
        'B': 'B Xfix geom 8C26D068 + pristine land (no index)',
        'C': 'C Xfix geom 8C26D068 + chunk-index land (sqrt cull)'}


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


def col(arm, zone, name):
    return [float(x[name]) for x in rows if x['arm'] == arm and x['zone'] == zone]


def line(label, fmt, arm, zone, name, q):
    v = col(arm, zone, name)
    return fmt.format(pct(v, q))


print('rounds per arm/zone:', len(col('A', '0', 'full10kEms')))
print()
print('=== arm identity by checksum (B and C must agree: only the index differs)')
rc = 0
for z in ZONES:
    sb = sorted(set(x['sumLand'] for x in rows if x['arm'] == 'B' and x['zone'] == z))
    sc = sorted(set(x['sumLand'] for x in rows if x['arm'] == 'C' and x['zone'] == z))
    sa = sorted(set(x['sumLand'] for x in rows if x['arm'] == 'A' and x['zone'] == z))
    fb = sorted(set(x['sumFull'] for x in rows if x['arm'] == 'B' and x['zone'] == z))
    fc = sorted(set(x['sumFull'] for x in rows if x['arm'] == 'C' and x['zone'] == z))
    okLand, okFull = sb == sc, fb == fc
    if not (okLand and okFull):
        rc = 2
    print(f'  zone {z:>6}  B.land==C.land {okLand}  B.full==C.full {okFull}'
          f'  A.land differs {sa != sb}  (n_land={len(sb)}/{len(sc)})')
    if len(sb) != 1 or len(sc) != 1:
        print('    NOTE: more than one distinct checksum within an arm/zone -> run not deterministic')
        rc = 2
print()
for name, label in (('full10kEms', 'full e() 10k samples'), ('land10kEms', 'land-only 10k samples')):
    for q, qn in ((.5, 'P50'), (.95, 'P95')):
        print(f'=== {label}  {qn} (ms)')
        for z in ZONES:
            vals = {a: pct(col(a, z, name), q) for a in 'ABC'}
            print('  zone {:>6}  A={:8.2f}  B={:8.2f}  C={:8.2f}   C-vs-B {:+.1f}%  C-vs-A {:+.1f}%'.format(
                z, vals['A'], vals['B'], vals['C'],
                (vals['C'] / vals['B'] - 1) * 100 if vals['B'] else float('nan'),
                (vals['C'] / vals['A'] - 1) * 100 if vals['A'] else float('nan')))
    print()
print('=== firstE (cold call: includes one tile build) P50 / min (ms)')
for z in ZONES:
    parts = []
    for a in 'ABC':
        v = col(a, z, 'firstEms')
        parts.append('{}={:.2f}/{:.2f}'.format(a, pct(v, .5), min(v)))
    print('  zone {:>6}  {}'.format(z, '  '.join(parts)))
print()
print('=== per-round spread of land-only P50-equivalent (raw per-JVM land10kEms, all rounds)')
for z in ZONES:
    for a in 'BC':
        v = col(a, z, 'land10kEms')
        print('  zone {:>6} arm {}  n={} min={:8.2f} med={:8.2f} max={:8.2f} stdev={:7.2f}'.format(
            z, a, len(v), min(v), statistics.median(v), max(v), statistics.stdev(v) if len(v) > 1 else 0))
print()
print('=== paired per-round C/B ratio (same round = same machine state; <1 means C faster)')
print('    full e() is the end-to-end number the game actually pays; land-only isolates the index.')
for metric, col_name in (('land10kEms', 'land-only'), ('full10kEms', 'full e()')):
    print(f'  -- {col_name}')
    worse = 0
    for rd in sorted({r['round'] for r in rows}):
        cells = []
        for z in ZONES:
            b = [float(r[metric]) for r in rows if r['round'] == rd and r['zone'] == z and r['arm'] == 'B']
            c = [float(r[metric]) for r in rows if r['round'] == rd and r['zone'] == z and r['arm'] == 'C']
            if not b or not c:
                cells.append('z{:<6} n/a'.format(z))
                continue
            ratio = c[0] / b[0]
            if ratio > 1.0:
                worse += 1
            cells.append('z{:<6} {:.3f}'.format(z, ratio))
        print('    round {}  {}'.format(rd, '  '.join(cells)))
    print(f'    rounds*cells where C was slower: {worse}/{len(ZONES) * 5}')
sys.exit(rc)
