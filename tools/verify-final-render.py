#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Verify the re-rendered final map matches the delivered final image.

Reads the freshly generated morph-coastal.f64be, renders it with the exact
delivery shader (tools/cmp-render-versions.py: shade()), and compares the
result against the delivered PNG pixel by pixel.

ASCII-only source (Windows PowerShell 5.1 safe).

Exit 0 only if:
  - the f64be SHA-256 matches the recorded baseline
  - the re-rendered image equals the delivered PNG exactly
"""
import hashlib
import importlib.util
import sys
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path('D:/Office software/Development Project/GeoGenesis-mod')
FINAL_SHA = '7BE0418369D097CE107EEEF58B8D02C6776782F1D7FBC6AD6C5E0E5728D7A238'
DELIVERED = ROOT / 'handoff/attachments/commit-selection-20261103/height-morph-coastal-d47de74.png'
NEW_DATA = ROOT / 'forge-1.20.1-47.4.10-mdk/build/main-clean-check/out/morph-coastal.f64be'


def load_shader():
    path = ROOT / 'tools/cmp-render-versions.py'
    spec = importlib.util.spec_from_file_location('cmp_render_versions', str(path))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return mod


def main():
    print('=== 1. data hash ===')
    raw = NEW_DATA.read_bytes()
    sha = hashlib.sha256(raw).hexdigest()
    print('  new      :', sha)
    print('  baseline :', FINAL_SHA)
    if sha.upper() != FINAL_SHA.upper():
        print('  FAIL: data hash mismatch')
        return 1
    print('  PASS: data identical')

    print()
    print('=== 2. re-render with delivery shader ===')
    mod = load_shader()
    n = 1401
    h = np.frombuffer(raw, dtype='>f8').reshape(n, n)
    rgb = mod.shade(h)
    fresh = Image.fromarray(rgb)
    print('  rendered:', fresh.size, fresh.mode)

    print()
    print('=== 3. compare with delivered PNG ===')
    if not DELIVERED.exists():
        print('  FAIL: delivered image missing:', DELIVERED)
        return 1
    old = Image.open(DELIVERED).convert('RGB')
    print('  delivered:', old.size, old.mode)
    if old.size != fresh.size:
        print('  FAIL: size mismatch %r vs %r' % (old.size, fresh.size))
        return 1

    a = np.asarray(old, dtype=np.int16)
    b = np.asarray(fresh, dtype=np.int16)
    diff = np.abs(a - b)
    changed = int((diff.max(axis=2) != 0).sum())
    maxdiff = int(diff.max())

    print('  pixels        :', a.shape[0] * a.shape[1])
    print('  changed px    :', changed)
    print('  max channel d :', maxdiff)

    # statistics recomputed from data, for the record
    water = float((h < 63.0).mean())
    print()
    print('=== 4. statistics ===')
    print('  water  %.4f%%' % (100 * water))
    print('  land   %.4f%%' % (100 * (1 - water)))
    print('  minY   %.4f' % h.min())
    print('  maxY   %.4f' % h.max())

    print()
    if changed == 0:
        print('RESULT: FINAL RENDER IDENTICAL - final map NOT broken')
        return 0
    print('RESULT: FINAL RENDER DIFFERS (%d px, max %d) - investigate' % (changed, maxdiff))
    return 1


if __name__ == '__main__':
    sys.exit(main())
