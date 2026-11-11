# -*- coding: utf-8 -*-
"""Prove that the index is the ONLY code change (sandbox only).

Two independent views:
  1. comment/blank-stripped line multiset diff  -> what was added / removed in code;
  2. statement-level check of the *load-bearing* methods: bandDistance / signedDistance /
     cellOutsideBand / segmentOutsideBand / boxDistance / weights must contain exactly the
     original expressions, i.e. every original code line is still present verbatim.
It refuses to claim "only added the index" if any original code line disappeared.
"""
import hashlib
import re
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parent
XOPT = Path(r'C:/Users/qq274/AppData/Local/Temp/geogenesis-xopt-90834f08a3e84eb3ad44d2b0c4b986ca')
PRISTINE = XOPT / 'diagnostics/java/com/geogenesis/worldgen/terrain/candidate/DoubleFullMorphLand.java'
CURRENT = ROOT / 'diagnostics/java/com/geogenesis/worldgen/terrain/candidate/DoubleFullMorphLand.java'
GEO_OLD = XOPT / 'diagnostics/java/com/geogenesis/worldgen/terrain/candidate/DoubleBoundaryGeometry.java'
GEO_NEW = ROOT / 'diagnostics/java/com/geogenesis/worldgen/terrain/candidate/DoubleBoundaryGeometry.java'

# Lines that legitimately disappear, all explained by the chunk-index refactor:
#  * the single-line for-each became a two-level (chunk -> segment) loop with the same inner body;
#  * bandDistance gained a `site` parameter (it must index chunkStart[site]/chunkEnd[site]);
#    the segment-level loop body it wraps is unchanged verbatim;
#  * the signedDistance ternary was re-wrapped onto two lines without changing either branch.
EXPECTED_REMOVED = {
    'for (int k : segs) {',
    'break;',
    'private double bandDistance(int[] segs, double x, double z) {',
    'double best = cellOutsideBand(site, x, z) ? Double.POSITIVE_INFINITY : bandDistance(segs, x, z);',
}

CODE_STRIP = re.compile(r'//.*$')


def sha(p):
    return hashlib.sha256(p.read_bytes()).hexdigest().upper()


def code_lines(path):
    out = []
    in_block = False
    for raw in path.read_text(encoding='utf-8', errors='replace').splitlines():
        s = raw.strip()
        if in_block:
            if s.endswith('*/'):
                in_block = False
            continue
        if s.startswith('/*'):
            if not s.endswith('*/'):
                in_block = True
            continue
        if not s or s.startswith('*') or s.startswith('//'):
            continue
        out.append(CODE_STRIP.sub('', s).strip())
    return out


def main():
    print('== SHA-256')
    for p, tag in ((PRISTINE, 'pristine (no index)'), (CURRENT, 'current (sqrt chunk cull)'),
                   (GEO_OLD, 'geometry old 3A7686'), (GEO_NEW, 'geometry current')):
        print(f'  {sha(p)}  {p.name}  [{tag}]')
    geo_same = sha(GEO_OLD) == sha(GEO_NEW)
    print(f'  geometry byte-identical old vs current: {geo_same}')
    print()

    a, b = code_lines(PRISTINE), code_lines(CURRENT)
    from collections import Counter
    ca, cb = Counter(a), Counter(b)
    removed = sorted((ca - cb).elements())
    added = sorted((cb - ca).elements())
    print(f'== code lines (comments/blank stripped): pristine={len(a)} current={len(b)}')
    print(f'   removed={len(removed)}  added={len(added)}')
    unexpected = [l for l in removed if l not in EXPECTED_REMOVED]
    print('   removed lines:')
    for l in removed:
        print(f'     - {l}')
    print('   added lines:')
    for l in added:
        print(f'     + {l}')
    print()
    rc = 0
    if unexpected:
        print(f'   !! UNEXPECTED removals (not explainable by the loop refactor): {unexpected}')
        rc = 2
    if not geo_same:
        print('   !! geometry changed; the A/B claim "only the index differs" is FALSE')
        rc = 2
    print()

    # every original line of the load-bearing predicates must still be present verbatim
    must_keep = [
        'double best = cellOutsideBand(site, x, z) ? Double.POSITIVE_INFINITY',
        ': bandDistance(site, segs, x, z);',
        'return site == win ? best : -best;',
        'if (segmentOutsideBand(k, x, z)) {',
        'double d = DoubleBoundaryGeometry.segmentDistance(x, z, segX0[k], segZ0[k],',
        'segX1[k], segZ1[k]);',
        'if (d < best) {',
        'best = d;',
        'if (best <= 0) {',
        'return best;',
        'return boxDistance(x, z, segMinX[k], segMaxX[k], segMinZ[k], segMaxZ[k]) > BAND;',
        'return Math.sqrt(dx * dx + dz * dz);',
        'wt[i] = DoubleBoundaryGeometry.progress(sd);',
        'if (best < 0) {',
    ]
    cur = set(b)
    missing = [m for m in must_keep if m not in cur]
    print('== load-bearing expressions')
    for m in must_keep:
        print(('   OK   ' if m in cur else '   MISS ') + m)
    if missing:
        print('   !! expressions changed -> formulas are NOT untouched')
        rc = 2
    # the squared-vs-sqrt regression must not come back
    FORBIDDEN = ['bandSq', 'chunkAxisDistance', 'dx * dx + dz * dz > ', 'BAND * BAND']
    text = CURRENT.read_text(encoding='utf-8', errors='replace')
    bad = [t for t in FORBIDDEN if t in text and '不得改回' not in text.split(t)[0][-120:]]
    print('   forbidden squared-cull tokens present in executable code: ' + (str(bad) if bad else 'none'))
    if bad:
        rc = 2
    print()
    print('VERDICT: index-only' if rc == 0 else 'VERDICT: more than the index changed')
    return rc


if __name__ == '__main__':
    sys.exit(main())
