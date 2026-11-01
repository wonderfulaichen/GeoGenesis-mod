#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Large-area sea/land distribution maps from M0DistributionGallery output.

ASCII-only source. Reads side.u8 / macro-c.f64be / ocean-probability.f64be.
These are BASE SIDE LABELS (not the final height water line, not S2 ocean
subtypes) -- every image is labelled as such. No height is evaluated here.

Output dir is created fresh; existing files are never overwritten silently.
"""
import json
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont
from scipy.ndimage import label as cc_label

ROOT = Path('D:/Office software/Development Project/GeoGenesis-mod')
OUT = ROOT / 'handoff/attachments/large-area-sea-land-20261103'
SEA = 63.0
FONT_PATH = 'C:/Windows/Fonts/msyh.ttc'


def font(size):
    try:
        return ImageFont.truetype(FONT_PATH, size)
    except Exception:
        return ImageFont.load_default()


def load(folder, n):
    side = np.fromfile(folder / 'side.u8', dtype='u1').reshape(n, n)
    c = np.fromfile(folder / 'macro-c.f64be', dtype='>f8').reshape(n, n)
    meta = json.loads((folder / 'meta.json').read_text(encoding='utf-8'))
    return side, c, meta


def colorize(side, c):
    """Water: blue ramp driven by continentality. Land: green->tan ramp."""
    water = side != 0
    rgb = np.zeros(side.shape + (3,), dtype=float)
    # water: deeper (more negative c) = darker blue
    t = np.clip((c + 1.0) / 1.0, 0.0, 1.0)
    rgb[..., 0] = np.where(water, 24 + 46 * t, 0)
    rgb[..., 1] = np.where(water, 58 + 78 * t, 0)
    rgb[..., 2] = np.where(water, 96 + 80 * t, 0)
    # land: low c = tan coast, high c = darker green inland
    u = np.clip(c / 1.0, 0.0, 1.0)
    lr = 196 - 92 * u
    lg = 186 - 56 * u
    lb = 148 - 88 * u
    rgb[..., 0] = np.where(water, rgb[..., 0], lr)
    rgb[..., 1] = np.where(water, rgb[..., 1], lg)
    rgb[..., 2] = np.where(water, rgb[..., 2], lb)
    return np.uint8(np.clip(rgb, 0, 255))


def components(side):
    land = side == 0
    lab, num = cc_label(land)
    if num == 0:
        return dict(land_fraction=float(land.mean()), components=0, largest_share=None,
                    top5=[], largest_touches_edge=None)
    sizes = np.bincount(lab.ravel())[1:]
    order = np.argsort(sizes)[::-1]
    total = int(sizes.sum())
    big = lab == (order[0] + 1)
    edge = np.zeros_like(land)
    edge[0, :] = edge[-1, :] = edge[:, 0] = edge[:, -1] = True
    return dict(
        land_fraction=float(land.mean()),
        components=int(num),
        largest_share=float(sizes[order[0]] / total),
        top5=[float(sizes[i] / total) for i in order[:5]],
        largest_touches_edge=bool((big & edge).any()),
    )


def compose(side, c, meta, title, dest):
    n = side.shape[0]
    img = Image.fromarray(colorize(side, c))
    scale = max(1, 1100 // n)
    img = img.resize((n * scale, n * scale), Image.NEAREST)
    canvas = Image.new('RGB', (img.width, img.height + 78), 'white')
    canvas.paste(img, (0, 78))
    d = ImageDraw.Draw(canvas)
    d.text((10, 6), title, fill='black', font=font(24))
    span = (n - 1) * meta['step']
    stats = components(side)
    sub = ('window %.0f blocks (step %.0f, %d^2) | land %.4f%% | land components %d | largest %.2f%% of land'
           % (span, meta['step'], n, 100 * stats['land_fraction'], stats['components'],
              100 * (stats['largest_share'] or 0)))
    d.text((10, 38), sub, fill=(60, 60, 60), font=font(19))
    d.text((10, canvas.height - 26),
           'BASE SIDE LABELS - not the final height water line; no height evaluated',
           fill=(150, 40, 40), font=font(17))
    canvas.save(dest)
    return stats


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    report = {}
    for name in ('m0-thr0', 'm0-thr030'):
        folder = ROOT / ('build/wt-d47de74/forge-1.20.1-47.4.10-mdk/build/cmp/' + name)
        if not (folder / 'side.u8').exists():
            print('skip missing', name)
            continue
        side, c, meta = load(folder, 1401)
        n = side.shape[0]
        label = {'m0-thr0': 'zero offset (no bias)', 'm0-thr030': 'landThreshold +0.30'}[name]
        dest = OUT / ('sea-land-%s.png' % name)
        stats = compose(side, c, meta, 'Sea/land distribution - %s' % label, dest)
        report[name] = {'image': str(dest.relative_to(ROOT)), 'meta': meta, 'stats': stats}
        print(name, json.dumps(stats, ensure_ascii=False))
    (OUT / 'report.json').write_text(
        json.dumps(report, indent=2, ensure_ascii=False), encoding='utf-8')
    print('written to', OUT)


if __name__ == '__main__':
    main()
