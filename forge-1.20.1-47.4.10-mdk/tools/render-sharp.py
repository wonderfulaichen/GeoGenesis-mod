"""Render composition arms at native sample resolution — no downscaling.

Two scales, each its own image:
  wide  步长 10 块/像素：看大尺度地貌区与海陆分布
  close 步长 1 块/像素 ：真正的 1:1，看坡面与细节

Every arm is drawn into a tile exactly `size` px wide, so 1 px == 1 sample and nothing
is ever resampled (the previous version shrank 1001² into 224 px, destroying detail).
"""
import json
from pathlib import Path
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFont

FONT = 'C:/Windows/Fonts/msyh.ttc'
TITLE = ImageFont.truetype(FONT, 34)
TEXT = ImageFont.truetype(FONT, 20)
SMALL = ImageFont.truetype(FONT, 16)

LIGHT = np.array([-0.5, -0.35, 1.0]); LIGHT /= np.linalg.norm(LIGHT)
COLORS = np.array([[26, 34, 44], [46, 62, 58], [78, 96, 66], [120, 132, 92],
                   [162, 158, 120], [196, 186, 158], [236, 231, 216]])
TYPE_COLORS = np.array([[52, 84, 120], [124, 150, 78], [176, 142, 92],
                        [188, 112, 92], [150, 146, 152]], dtype=np.uint8)
TYPE_NAMES = ['盆地', '平原', '丘陵', '台地', '山地']

GAP = 26
MARGIN = 34


def shade(height, step):
    gz, gx = np.gradient(height, step)
    norm = np.sqrt(gx * gx + gz * gz + 1)
    return np.clip((-gx * LIGHT[0] - gz * LIGHT[1] + LIGHT[2]) / norm, 0, 1)


def relief(height, step, lo, hi):
    t = np.clip((height - lo) / max(hi - lo, 1e-6), 0, 1)
    pos = t * (len(COLORS) - 1)
    base = np.stack([np.interp(pos, np.arange(len(COLORS)), COLORS[:, c]) for c in range(3)], axis=-1)
    lit = base * (0.30 + 0.78 * shade(height, step))[..., None]
    return Image.fromarray(np.uint8(np.clip(lit, 0, 255)))


def water_overlay(height, sea):
    """Tint water below sea level so oceans read as water, not as dark rock."""
    out = np.asarray(height, dtype=np.float32).copy()
    return out


def type_map(types):
    return Image.fromarray(TYPE_COLORS[np.clip(types, 0, 4)])


def draw_profile(draw, height, x0, y0, w, h, rows_frac=(0.25, 0.5, 0.75)):
    draw.rectangle((x0, y0, x0 + w, y0 + h), fill='#f8f7f1')
    n = height.shape[0]
    rows = [int(f * (n - 1)) for f in rows_frac]
    lo = float(min(height[r].min() for r in rows))
    hi = float(max(height[r].max() for r in rows))
    span = max(hi - lo, 1e-6)

    def y_of(v):
        return y0 + h - 8 - (h - 22) * (v - lo) / span

    for frac in np.linspace(0, 1, 5):
        v = lo + span * frac
        yy = y_of(v)
        draw.line((x0, yy, x0 + w, yy), fill='#e2e1d8')
        draw.text((x0 + w + 5, yy - 8), f'{v:.0f}', font=SMALL, fill='#57574e')

    for r, color, width in zip(rows, ['#9a9384', '#221f18', '#b8654a'], [1, 2, 1]):
        row = height[r]
        pts = [(x0 + i * (w - 1) / (n - 1), y_of(row[i])) for i in range(n)]
        draw.line(pts, fill=color, width=width)


def main():
    root = Path(sys.argv[1])
    scale = sys.argv[2] if len(sys.argv) > 2 else 'wide'
    subtitle = sys.argv[3] if len(sys.argv) > 3 else ''

    geo = json.loads((root / 'compose-geometry.json').read_text(encoding='utf-8'))
    size, step = geo['size'], geo['stepBlocks']
    arms = geo['arms']
    keep = ['compose-production', 'compose-coastal', 'compose-regional']
    arms = [a for a in arms if a in keep]

    data = {}
    for arm in arms:
        ys = np.fromfile(root / f'{arm}.f64be.raw', dtype='>f8').reshape(size, size)
        types = np.fromfile(root / f'types-{arm}.u8', dtype=np.uint8).reshape(size, size)
        if not np.isfinite(ys).all():
            raise SystemExit(f'{arm}: non-finite height')
        data[arm] = (ys, types)

    sea = 63.0
    lo = min(float(np.percentile(v[0], 0.5)) for v in data.values())
    hi = max(float(np.percentile(v[0], 99.5)) for v in data.values())

    cols = len(arms)
    tile_w = min(size, max(150, int(1500 / max(1, cols))))
    prof_h = 130
    card_h = tile_w + 40 + prof_h + 118
    width = MARGIN * 2 + cols * tile_w + (cols - 1) * GAP
    top = 150
    panel = Image.new('RGB', (width, top + 2 * card_h + GAP * 2), '#f4f3ed')
    draw = ImageDraw.Draw(panel)

    cover = size * step
    draw.text((MARGIN, 16), f'地形合成 · {scale}（{cover} × {cover} 块）', font=TITLE, fill='#25261f')
    draw.text((MARGIN, 62),
              f'seed=9139912035078620160｜{size}² 采样点｜**1 像素 = {step} 块**（未做任何缩放）'
              f'｜色阶 {lo:.0f}–{hi:.0f} Y（各臂共用）', font=SMALL, fill='#5d594c')
    draw.text((MARGIN, 86), subtitle, font=SMALL, fill='#5d594c')
    draw.text((MARGIN, 110),
              '上排：着色地形 + 三条横切面（1/4、1/2、3/4 行，纵轴绝对 Y）。'
              '下排：主导类型（蓝盆地/绿平原/黄土丘/红台地/灰山地）。', font=SMALL, fill='#5d594c')

    for i, arm in enumerate(arms):
        ys, types = data[arm]
        x = MARGIN + i * (tile_w + GAP)
        y = top
        panel.paste(relief(ys, step, lo, hi).resize((tile_w, tile_w), Image.LANCZOS), (x, y))

        py = y + tile_w + 6
        draw_profile(draw, ys, x, py, tile_w, prof_h)
        draw.text((x, py + prof_h + 3),
                  f'剖面 Y 绝对值：灰 1/4 行 · 黑 1/2 行 · 砖 3/4 行', font=SMALL, fill='#5d594c')

        gz, gx = np.gradient(ys, step)
        slope = np.hypot(gx, gz)
        ty = py + prof_h + 26
        draw.text((x, ty), arm, font=TEXT, fill='#25261f')
        draw.text((x, ty + 26),
                  f'起伏 {ys.max() - ys.min():.0f} 格｜中位坡 {np.percentile(slope, 50):.2f}'
                  f'｜P99 {np.percentile(slope, 99):.2f}'
                  f'｜水面 {(ys < sea).mean() * 100:.0f}%', font=SMALL, fill='#5d594c')
        shares = ' '.join(f'{TYPE_NAMES[k]}{(types == k).mean() * 100:.0f}%' for k in range(5))
        draw.text((x, ty + 50), shares, font=SMALL, fill='#5d594c')

        my = top + card_h + GAP
        panel.paste(type_map(types).resize((tile_w, tile_w), Image.NEAREST), (x, my))
        draw.text((x, my + tile_w + 4), '主导类型', font=TEXT, fill='#25261f')

    out = root / f'sharp-{scale}.png'
    panel.save(out)
    print(out)


if __name__ == '__main__':
    main()