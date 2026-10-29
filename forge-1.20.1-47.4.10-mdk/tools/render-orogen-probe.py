"""Render the orogen / rift tectonic profile probe fields.

Rows: orogen, rift. Columns: sigma 35 / 55 / 80. Each tile is at native sample
resolution (no downscaling), with a shared colour ramp per row so the sigmas are
directly comparable.
"""
import json
from pathlib import Path
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFont

FONT = 'C:/Windows/Fonts/msyh.ttc'
TITLE = ImageFont.truetype(FONT, 30)
TEXT = ImageFont.truetype(FONT, 18)
SMALL = ImageFont.truetype(FONT, 15)

# 地形色带：0 = 透明底，高值 = 亮橙/白（造山）；裂谷用青蓝
RAMP_OROGEN = np.array([[236, 236, 230], [212, 196, 150], [196, 158, 96],
                        [178, 118, 64], [170, 86, 60], [190, 150, 140], [246, 246, 246]])
RAMP_RIFT = np.array([[236, 236, 230], [176, 200, 206], [124, 172, 186],
                      [82, 138, 162], [58, 104, 134], [44, 76, 108], [30, 52, 82]])


def colour(field, ramp, peak):
    t = np.clip(field / max(peak, 1e-9), 0, 1)
    pos = t * (len(ramp) - 1)
    out = np.stack([np.interp(pos, np.arange(len(ramp)), ramp[:, c]) for c in range(3)], axis=-1)
    return Image.fromarray(np.uint8(np.clip(out, 0, 255)))


def main():
    root = Path(sys.argv[1])
    geo = json.loads((root / 'probe-geometry.json').read_text(encoding='utf-8'))
    size, step = geo['size'], geo['stepBlocks']
    sigmas = geo['sigmas']

    tile = min(400, size)
    gap, margin = 22, 28
    cols = len(sigmas)
    width = margin * 2 + cols * tile + (cols - 1) * gap
    row_h = tile + 66
    panel = Image.new('RGB', (width, 128 + 2 * row_h + gap * 2), '#f4f3ed')
    draw = ImageDraw.Draw(panel)
    draw.text((margin, 14), '构造边界剖面探针：orogen / rift', font=TITLE, fill='#25261f')
    draw.text((margin, 54),
              f'seed=9139912035078620160｜{size}² 采样、步长 {step} 块 ⇒ 覆盖 {size * step} 块'
              f'｜orogen = smoothPos(stress)·gaussian(dist,σ)｜rift = smoothPos(−stress)·gaussian(dist,σ)',
              font=SMALL, fill='#5d594c')
    draw.text((margin, 78),
              '判据：① 有无平行直线/同心环（看长尾比）② 带状伸长比（各向同性圆≈1，现实造山带 5–20）'
              '③ 方向各向异性（梯度是否一致跨带）',
              font=SMALL, fill='#5d594c')

    for row, (name, ramp) in enumerate([('orogen', RAMP_OROGEN), ('rift', RAMP_RIFT)]):
        for col, s in enumerate(sigmas):
            tag = f's{int(round(s)):02d}'
            f = np.fromfile(root / f'{name}-{tag}.f64be.raw', dtype='>f8').reshape(size, size)
            peak = float(f.max())
            img = colour(f, ramp, peak).resize((tile, tile), Image.LANCZOS)
            x = margin + col * (tile + gap)
            y = 122 + row * (row_h + gap)
            panel.paste(img, (x, y))
            cover = 100.0 * (f >= peak * 0.01).mean()
            draw.text((x, y + tile + 4), f'{name}  σ={int(s)}  峰值 {peak:.2f}  有效区 {cover:.0f}%',
                      font=TEXT, fill='#25261f')
            if col == 0:
                draw.text((x, y - 20), name.upper(), font=TEXT, fill='#25261f')

    out = root / 'orogen-probe.png'
    panel.save(out)
    print(out)


if __name__ == '__main__':
    main()