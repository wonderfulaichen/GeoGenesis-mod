#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Guard: every hillshade used for delivery must read a CONE as raised.

This exists because tools/cmp-render-versions.py shipped an inverted light term
(+gx + gz) and a cone read as a hollow, while the project renderer
(render-islands-big.py, LIGHT=(-0.5,-0.35,1)) was correct. Comparing screenshots
by eye did not catch it; a known-shape test does.

Run:  python -B tools/verify-light-convention.py
Exit: 0 if every checked shading function reads CONE=raised and PIT=hollow,
      non-zero otherwise (so a build/CI step can gate on it).

ASCII-only source (Windows PowerShell 5.1 safe).
"""
import sys
from pathlib import Path

import numpy as np

ROOT = Path('D:/Office software/Development Project/GeoGenesis-mod')


def fixture():
    """A cone (bump) and a pit (depression) side by side, known ground truth."""
    n, step = 241, 5.0
    yy, xx = np.mgrid[0:n, 0:n]
    h = np.zeros((n, n))
    d_cone = np.hypot(xx - 70, yy - 120)
    h = np.where(d_cone < 50, 100.0 * (1 - d_cone / 50), h)
    d_pit = np.hypot(xx - 165, yy - 120)
    h = np.where(d_pit < 45, -90.0 * (1 - d_pit / 45), h)
    h = np.maximum(h, 0.0)
    return h, xx, yy, d_cone, d_pit, step


def judge(shade_fn):
    h, xx, yy, d_cone, d_pit, step = fixture()
    g = shade_fn(h, step)
    rim_c = np.abs(d_cone - 46) < 7
    cone_ul = rim_c & (xx - 70 < 0) & (yy - 120 < 0)
    cone_lr = rim_c & (xx - 70 > 0) & (yy - 120 > 0)
    rim_p = np.abs(d_pit - 41) < 7
    pit_ul = rim_p & (xx - 165 < 0) & (yy - 120 < 0)
    pit_lr = rim_p & (xx - 165 > 0) & (yy - 120 > 0)
    cone_ok = g[cone_ul].mean() > g[cone_lr].mean()
    pit_ok = g[pit_ul].mean() < g[pit_lr].mean()
    return cone_ok, pit_ok, (g[cone_ul].mean(), g[cone_lr].mean(),
                             g[pit_ul].mean(), g[pit_lr].mean())


def project_shade(h, step):
    light = np.array([-0.5, -0.35, 1.0])
    light = light / np.linalg.norm(light)
    gz, gx = np.gradient(h, step)
    return np.clip((-gx * light[0] - gz * light[1] + light[2])
                   / np.sqrt(gx * gx + gz * gz + 1), 0, 1)


def load_cmp_shade():
    """Import shade() from tools/cmp-render-versions.py without running its main."""
    import importlib.util
    path = ROOT / 'tools/cmp-render-versions.py'
    spec = importlib.util.spec_from_file_location('cmp_render_versions', str(path))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    return lambda h, step: mod.shade(h)


def load_surrounded_shade():
    """Import hillshade() from tools/render-surrounded-continent.py without running main."""
    import importlib.util
    path = ROOT / 'tools/render-surrounded-continent.py'
    spec = importlib.util.spec_from_file_location('render_surrounded', str(path))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)

    def fn(h, step):
        rgb, _water = mod.hillshade(h, step)
        return rgb.astype(float).mean(axis=-1)
    return fn


def main():
    checks = [('render-islands-big.py (project LIGHT)', project_shade),
              ('tools/cmp-render-versions.py shade()', load_cmp_shade()),
              ('tools/render-surrounded-continent.py', load_surrounded_shade())]
    failures = 0
    for name, fn in checks:
        cone_ok, pit_ok, vals = judge(fn)
        verdict = 'PASS' if (cone_ok and pit_ok) else 'FAIL'
        if verdict == 'FAIL':
            failures += 1
        print('%-42s CONE=%s PIT=%s  coneUL=%.4f coneLR=%.4f pitUL=%.4f pitLR=%.4f  %s'
              % (name, 'raised' if cone_ok else 'HOLLOW',
                 'hollow' if pit_ok else 'RAISED', *vals, verdict))
    if failures:
        print('\nLIGHT CONVENTION FAIL: a cone must read as RAISED (light from upper-left).')
        sys.exit(1)
    print('\nLIGHT CONVENTION PASS: all shaders read CONE=raised, PIT=hollow.')


if __name__ == '__main__':
    main()
