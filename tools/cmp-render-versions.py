#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Render same-window comparison of candidate commits (ASCII-only source).

Inputs (each optional, missing arms are skipped):
  build/wt-<sha>/.../build/cmp/fullmorph/<arm>.f64be : 1401^2 float64 big-endian heights (Y)
  build/wt-<sha>/.../build/cmp/m0-thr*/type.u8      : 1401^2 base recipe id (0..22)
  build/wt-<sha>/.../build/cmp/m0-thr*/side.u8      : 1401^2 water label

Output: handoff/attachments/commit-selection-20261103/ with a labelled panel per arm,
per-arm PNGs, metrics.json and SHA256SUMS. Read-only for inputs; new output dir only.
No height synthesis: pixel = sample. Sea line drawn at Y=63 (project sea level).
"""
import hashlib
import json
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

ROOT = Path('D:/Office software/Development Project/GeoGenesis-mod')
WINDOW = {'size': 1401, 'step': 5, 'origin': [-3642.5, -3365.5], 'center': [-140, 137]}
YLIM = (-64.0, 320.0)
SEA = 63.0
STOPS = np.array([-64, 0, 50, 62.999, 63, 95, 150, 220, 320], dtype=float)
COLORS = np.array([[12, 37, 72], [24, 64, 102], [42, 103, 135], [90, 175, 182], [73, 123, 69],
                   [119, 150, 85], [148, 128, 93], [153, 150, 140], [245, 245, 245]], dtype=float)
TYPE_COLORS = np.array([
    [120, 150, 100], [140, 170, 120], [170, 190, 130], [200, 200, 140], [150, 150, 90],
    [110, 140, 95], [130, 160, 105], [160, 185, 125], [190, 195, 135], [145, 145, 85],
    [100, 130, 90], [125, 155, 100], [155, 180, 120], [185, 190, 130], [140, 140, 80],
    [205, 170, 120], [150, 120, 95], [125, 100, 80],
    [30, 60, 100], [45, 80, 120], [60, 100, 140], [75, 120, 160], [90, 140, 175],
], dtype=float)


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def read_f64(path, size):
    raw = path.read_bytes()
    need = size * size * 8
    if len(raw) != need:
        raise ValueError('%s: %d bytes, expected %d' % (path, len(raw), need))
    return np.frombuffer(raw, dtype='>f8').reshape(size, size)


def read_u8(path, size):
    raw = path.read_bytes()
    if len(raw) != size * size:
        raise ValueError('%s: %d bytes, expected %d' % (path, len(raw), size * size))
    return np.frombuffer(raw, dtype='u1').reshape(size, size)


# Light direction must match the project renderer (render-islands-big.py:19):
# LIGHT=(-0.5,-0.35,1.0) normalized, lit = dot(n, LIGHT) with n=(-gx,-gz,1)/norm.
# An earlier version of this file used (+gx + gz), which inverted the relief:
# a cone read as a hollow. The cone-vs-pit test in tools/verify-light-convention.py
# fails the build if this sign flips back.
LIGHT = np.array([-0.5, -0.35, 1.0])
LIGHT = LIGHT / np.linalg.norm(LIGHT)


def shade(h):
    gz, gx = np.gradient(h, WINDOW['step'])
    norm = np.sqrt(gx * gx + gz * gz + 1.0)
    lit = np.clip((-gx * LIGHT[0] - gz * LIGHT[1] + LIGHT[2]) / norm, 0.0, 1.0)
    rgb = np.stack([np.interp(h, STOPS, COLORS[:, k]) for k in range(3)], axis=-1)
    return np.uint8(np.clip(rgb * (0.42 + 0.72 * lit)[..., None], 0, 255))


def type_image(t):
    rgb = TYPE_COLORS[np.clip(t, 0, len(TYPE_COLORS) - 1)]
    return np.uint8(np.clip(rgb, 0, 255))


def panel(images, titles, out, scale=1):
    n = len(images)
    w, h = images[0].shape[1], images[0].shape[0]
    pad, head = 8, 26
    canvas = Image.new('RGB', (n * (w + pad) + pad, h + head + pad), 'white')
    draw = ImageDraw.Draw(canvas)
    for i, (img, title) in enumerate(zip(images, titles)):
        canvas.paste(Image.fromarray(img), (pad + i * (w + pad), head))
        draw.text((pad + i * (w + pad) + 4, 8), title, fill='black')
    canvas.save(out)
    return out


def main():
    outdir = ROOT / 'handoff/attachments/commit-selection-20261103'
    outdir.mkdir(parents=True, exist_ok=True)
    size = WINDOW['size']
    manifest, metrics, made = {}, {'window': WINDOW, 'seaLevel': SEA, 'arms': {}}, []

    shas = sorted(p.name[3:] for p in (ROOT / 'build').glob('wt-*') if p.is_dir())
    morph, m0 = {}, {}
    for sha_id in shas:
        mdk = ROOT / 'build' / ('wt-' + sha_id) / 'forge-1.20.1-47.4.10-mdk'
        fm = mdk / 'build/cmp/fullmorph'
        if (fm / 'morph-coastal.f64be').exists():
            morph[sha_id] = fm
        for d in sorted((mdk / 'build/cmp').glob('m0-thr*')):
            if (d / 'type.u8').exists():
                m0['%s/%s' % (sha_id, d.name)] = d

    if morph:
        images, titles = [], []
        for sha_id in sorted(morph):
            p = morph[sha_id] / 'morph-coastal.f64be'
            h = read_f64(p, size)
            manifest[str(p.relative_to(ROOT))] = sha(p)
            wet = int((h < SEA).sum())
            metrics['arms'].setdefault('morph-coastal', {})[sha_id] = {
                'wet_below_63': wet, 'wet_fraction': wet / (size * size),
                'minY': float(h.min()), 'maxY': float(h.max())}
            images.append(shade(h))
            titles.append('%s morph-coastal' % sha_id)
            f = outdir / ('height-morph-coastal-%s.png' % sha_id)
            Image.fromarray(images[-1]).save(f)
            made.append(f)
        made.append(panel(images, titles, outdir / 'height-morph-coastal-panel.png'))

    if m0:
        images, titles = [], []
        for key in sorted(m0):
            d = m0[key]
            t = read_u8(d / 'type.u8', size)
            s = read_u8(d / 'side.u8', size)
            for n in ('type.u8', 'side.u8'):
                manifest[str((d / n).relative_to(ROOT))] = sha(d / n)
            water = int((s != 0).sum())
            metrics['arms'].setdefault('m0-distribution', {})[key] = {
                'water_labels': water, 'water_fraction': water / (size * size),
                'distinct_types': int(len(np.unique(t)))}
            images.append(type_image(t))
            titles.append('%s %s' % (key.split('/')[0], d.name))
            f = outdir / ('types-%s-%s.png' % (key.split('/')[0], d.name))
            Image.fromarray(images[-1]).save(f)
            made.append(f)
        made.append(panel(images, titles, outdir / 'types-m0-panel.png'))

    for f in made:
        manifest[str(f.relative_to(ROOT))] = sha(f)
    (outdir / 'metrics.json').write_text(json.dumps(metrics, indent=2), encoding='utf-8')
    (outdir / 'SHA256SUMS.json').write_text(json.dumps(manifest, indent=2), encoding='utf-8')
    print(json.dumps(metrics, indent=2))
    print('files=%d' % len(made))


if __name__ == '__main__':
    main()
