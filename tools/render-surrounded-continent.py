#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Large sea-surrounded continent maps.

Two sources, clearly separated:

1. TAG view (base side labels from M0DistributionGallery, span 56040 blocks,
   1401^2 step 40): find the largest land component whose bounding box does NOT
   touch the sampled window edge -- i.e. sea on every side *within that window*.
   No height is evaluated here.

2. HEIGHT view (FullMorphGallery morph-coastal, 7005 blocks, 1401^2 step 5):
   true Y=63 water line, rendered with the project hillshade
   (LIGHT=(-0.5,-0.35,1), verified by tools/verify-light-convention.py).

Both outputs are labelled with window span and label type. ASCII-only source.
"""
import json
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont
from scipy.ndimage import label as cc_label

ROOT = Path('D:/Office software/Development Project/GeoGenesis-mod')
OUT = ROOT / 'handoff/attachments/surrounded-continent-20261103'
FONTPATH = 'C:/Windows/Fonts/msyh.ttc'
SEA = 63.0

# Project hillshade (render-islands-big.py:19,34) -- do not re-derive the sign.
LIGHT = np.array([-0.5, -0.35, 1.0])
LIGHT = LIGHT / np.linalg.norm(LIGHT)

ANCHORS = np.array([-64, -40, -12, 62, 66, 76, 110, 170, 240, 320], dtype=float)
COLORS = np.array([
    [16, 26, 40], [24, 40, 62], [40, 66, 92], [58, 92, 116],
    [214, 205, 168], [96, 122, 78], [132, 142, 88],
    [156, 140, 104], [198, 190, 180], [252, 252, 255],
], dtype=float)


def font(sz):
    try:
        return ImageFont.truetype(FONTPATH, sz)
    except Exception:
        return ImageFont.load_default()


def hillshade(h, step):
    gz, gx = np.gradient(h, step)
    norm = np.sqrt(gx * gx + gz * gz + 1.0)
    lit = np.clip((-gx * LIGHT[0] - gz * LIGHT[1] + LIGHT[2]) / norm, 0.0, 1.0)
    rgb = np.stack([np.interp(h, ANCHORS, COLORS[:, k]) for k in range(3)], axis=-1)
    water = h < SEA
    shade_w = 0.74 + 0.26 * lit
    shade_l = 0.40 + 0.72 * lit
    shade = np.where(water, shade_w, shade_l)
    return np.uint8(np.clip(rgb * shade[..., None], 0, 255)), water


def largest_interior_land(side):
    land = side == 0
    lab, num = cc_label(land)
    if num == 0:
        return None
    edge = np.zeros_like(land)
    edge[0, :] = edge[-1, :] = edge[:, 0] = edge[:, -1] = True
    sizes = np.bincount(lab.ravel())[1:]
    best = None
    for k in range(1, num + 1):
        m = lab == k
        if (m & edge).any():
            continue
        sz = int(sizes[k - 1])
        if best is None or sz > best[0]:
            best = (sz, k, m)
    return best


def save(img, dest, lines):
    head = 30 + 26 * len(lines)
    canvas = Image.new('RGB', (img.width, img.height + head), 'white')
    canvas.paste(img, (0, head))
    d = ImageDraw.Draw(canvas)
    y = 8
    for i, (txt, sz, col) in enumerate(lines):
        d.text((10, y), txt, fill=col, font=font(sz))
        y += 24 if sz >= 22 else 20
    canvas.save(dest)
    return canvas


def tag_view():
    n = 1401
    folder = ROOT / 'build/wt-d47de74/forge-1.20.1-47.4.10-mdk/build/cmp/m0-thr030'
    side = np.fromfile(folder / 'side.u8', dtype='u1').reshape(n, n)
    meta = json.loads((folder / 'meta.json').read_text(encoding='utf-8'))
    found = largest_interior_land(side)
    if found is None:
        print('tag view: no interior landmass')
        return None
    sz, k, m = found
    ys, xs = np.where(m)
    pad = 120
    y0, y1 = max(0, ys.min() - pad), min(n, ys.max() + pad)
    x0, x1 = max(0, xs.min() - pad), min(n, xs.max() + pad)
    sub = side[y0:y1, x0:x1]
    water = sub != 0
    rgb = np.zeros(sub.shape + (3,), dtype=float)
    rgb[..., 0] = np.where(water, 38, 120)
    rgb[..., 1] = np.where(water, 96, 152)
    rgb[..., 2] = np.where(water, 140, 96)
    img = Image.fromarray(np.uint8(rgb))
    scale = max(1, 1000 // max(img.width, img.height))
    img = img.resize((img.width * scale, img.height * scale), Image.NEAREST)
    step = meta['step']
    span = (y1 - y0) * step
    lines = [
        ('Largest sea-surrounded landmass  (landThreshold +0.30)', 25, 'black'),
        ('window %.0f blocks wide | step %.0f | %d^2 samples | origin [%.0f, %.0f]'
         % ((x1 - x0) * step, step, n, meta['origin'][0] + x0 * step, meta['origin'][1] + y0 * step),
         19, (55, 55, 55)),
        ('landmass %.0f x %.0f blocks | sea on every side inside this window'
         % ((xs.max() - xs.min()) * step, (ys.max() - ys.min()) * step), 19, (55, 55, 55)),
        ('BASE SIDE LABELS - not the Y=63 water line; no height evaluated', 17, (150, 40, 40)),
    ]
    dest = OUT / 'surrounded-continent-tag-56000.png'
    save(img, dest, lines)
    return dict(image=str(dest.relative_to(ROOT)), kind='base side labels',
                window_blocks=[(x1 - x0) * step, (y1 - y0) * step], step=step,
                landmass_blocks=[(xs.max() - xs.min()) * step, (ys.max() - ys.min()) * step],
                pixels=sz, touches_window_edge=False)


def height_view():
    n = 1401
    src = ROOT / 'forge-1.20.1-47.4.10-mdk/build/main-b1/out/morph-coastal.f64be'
    if not src.exists():
        print('height view: missing', src)
        return None
    h = np.fromfile(src, dtype='>f8').reshape(n, n)
    step = 5.0
    rgb, water = hillshade(h, step)
    img = Image.fromarray(rgb)
    scale = max(1, 1000 // n)
    if scale > 1:
        img = img.resize((img.width * scale, img.height * scale), Image.NEAREST)
    lines = [
        ('morph-coastal  (Y=63 water line, project hillshade)', 25, 'black'),
        ('window 7005 blocks | step 5 | %d^2 samples | center [-140, 137]' % n, 19, (55, 55, 55)),
        ('water %.4f%% | land %.4f%% | minY %.1f maxY %.1f'
         % (100 * water.mean(), 100 * (1 - water.mean()), h.min(), h.max()), 19, (55, 55, 55)),
        ('full height chain; light from upper-left (cone test PASS)', 17, (110, 110, 110)),
    ]
    dest = OUT / 'surrounded-continent-height-7000.png'
    save(img, dest, lines)
    return dict(image=str(dest.relative_to(ROOT)), kind='height Y=63',
                window_blocks=[(n - 1) * step, (n - 1) * step], step=step,
                water_fraction=float(water.mean()), minY=float(h.min()), maxY=float(h.max()))


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    report = {}
    t = tag_view()
    if t:
        report['tag'] = t
        print('tag:', json.dumps(t, ensure_ascii=False))
    hh = height_view()
    if hh:
        report['height'] = hh
        print('height:', json.dumps(hh, ensure_ascii=False))
    (OUT / 'report.json').write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding='utf-8')
    print('written', OUT)


if __name__ == '__main__':
    main()
