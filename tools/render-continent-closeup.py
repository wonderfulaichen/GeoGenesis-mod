#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Close-up of the largest INTERIOR (fully sea-surrounded) landmass.

Reads M0DistributionGallery side.u8 / macro-c.f64be (base side labels).
Finds land connected components that do NOT touch the window edge -- those are
landmasses with sea on every side *within the sampled window*. That is a
finite-window statement, not a proof for the infinite world.

ASCII-only source. New output directory; never overwrites.
"""
import json
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont
from scipy.ndimage import label as cc_label
from scipy.ndimage import binary_dilation

ROOT = Path('D:/Office software/Development Project/GeoGenesis-mod')
OUT = ROOT / 'handoff/attachments/large-area-sea-land-20261103/closeup'
FONT = 'C:/Windows/Fonts/msyh.ttc'


def font(sz):
    try:
        return ImageFont.truetype(FONT, sz)
    except Exception:
        return ImageFont.load_default()


def colorize(side, c):
    water = side != 0
    rgb = np.zeros(side.shape + (3,), dtype=float)
    t = np.clip((c + 1.0), 0.0, 1.0)
    rgb[..., 0] = np.where(water, 26 + 40 * t, 0)
    rgb[..., 1] = np.where(water, 62 + 72 * t, 0)
    rgb[..., 2] = np.where(water, 104 + 78 * t, 0)
    u = np.clip(c, 0.0, 1.0)
    rgb[..., 0] = np.where(water, rgb[..., 0], 198 - 96 * u)
    rgb[..., 1] = np.where(water, rgb[..., 1], 188 - 58 * u)
    rgb[..., 2] = np.where(water, rgb[..., 2], 150 - 92 * u)
    return np.uint8(np.clip(rgb, 0, 255))


def largest_interior(side):
    land = side == 0
    lab, num = cc_label(land)
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


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    report = {}
    n = 1401
    for name in ('m0-thr0', 'm0-thr030'):
        folder = ROOT / ('build/wt-d47de74/forge-1.20.1-47.4.10-mdk/build/cmp/' + name)
        side = np.fromfile(folder / 'side.u8', dtype='u1').reshape(n, n)
        c = np.fromfile(folder / 'macro-c.f64be', dtype='>f8').reshape(n, n)
        meta = json.loads((folder / 'meta.json').read_text(encoding='utf-8'))
        found = largest_interior(side)
        if found is None:
            print(name, 'no interior landmass'); continue
        sz, k, m = found
        ys, xs = np.where(m)
        pad = 60
        y0, y1 = max(0, ys.min() - pad), min(n, ys.max() + pad)
        x0, x1 = max(0, xs.min() - pad), min(n, xs.max() + pad)
        sub_side, sub_c = side[y0:y1, x0:x1], c[y0:y1, x0:x1]
        img = Image.fromarray(colorize(sub_side, sub_c))
        scale = max(1, 900 // max(img.width, img.height))
        img = img.resize((img.width * scale, img.height * scale), Image.NEAREST)
        canvas = Image.new('RGB', (img.width, img.height + 92), 'white')
        canvas.paste(img, (0, 92))
        d = ImageDraw.Draw(canvas)
        spanx = (x1 - x0) * meta['step']; spanz = (y1 - y0) * meta['step']
        d.text((10, 6), 'Largest landmass fully surrounded by sea - %s' % name,
               fill='black', font=font(23))
        d.text((10, 36), 'landmass %d px | %.0f x %.0f blocks | box %.0f x %.0f blocks'
               % (sz, (xs.max() - xs.min()) * meta['step'], (ys.max() - ys.min()) * meta['step'],
                  spanx, spanz), fill=(50, 50, 50), font=font(19))
        d.text((10, 62), 'window origin [%.0f, %.0f] step %.0f | BASE SIDE LABELS (not the final water line)'
               % (meta['origin'][0] + x0 * meta['step'], meta['origin'][1] + y0 * meta['step'], meta['step']),
               fill=(150, 40, 40), font=font(17))
        dest = OUT / ('continent-%s.png' % name)
        canvas.save(dest)
        report[name] = dict(image=str(dest.relative_to(ROOT)), pixels=sz,
                            size_blocks=[(xs.max() - xs.min()) * meta['step'],
                                         (ys.max() - ys.min()) * meta['step']],
                            touches_edge=False)
        print(name, json.dumps(report[name], ensure_ascii=False))
    (OUT / 'report.json').write_text(json.dumps(report, indent=2, ensure_ascii=False), encoding='utf-8')


if __name__ == '__main__':
    main()
