"""Render the land-composition comparison: production vs reference region assembly.

Three arms share one window and one colour ramp:
  compose-production  现行生产（TypeLandShape 加性混合）
  compose-region-ref  参考组装复刻（1200 格区域划分 + 公共边界过渡）
  compose-recipe      完整配方加权（本项目自创，作对照）

Each card carries a shaded-relief tile, a 1:1 close-up, and a **cross-section profile**
with absolute-Y gridlines, plus the dominant-type map on the second row.
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

LIGHT = np.array([-0.5, -0.35, 1.0]); LIGHT /= np.linalg.norm(LIGHT)
COLORS = np.array([[38, 44, 40], [72, 84, 66], [120, 132, 92],
                   [162, 158, 120], [186, 176, 148], [214, 206, 182], [236, 231, 216]])
TYPE_COLORS = np.array([[52, 84, 120],    # BASIN     深蓝
                        [124, 150, 78],   # PLAIN     草绿
                        [176, 142, 92],   # HILLS     土黄
                        [188, 112, 92],   # PLATEAU   砖红
                        [150, 146, 152]], dtype=np.uint8)  # MOUNTAINS 灰
TYPE_NAMES = ['盆地', '平原', '丘陵', '台地', '山地']

TILE = 224
CLOSE = 112
PROFILE_H = 110
GAP = 22
MARGIN = 26


def shade(height, step):
    gz, gx = np.gradient(height, step)
    norm = np.sqrt(gx * gx + gz * gz + 1)
    return np.clip((-gx * LIGHT[0] - gz * LIGHT[1] + LIGHT[2]) / norm, 0, 1)


def relief(height, step, lo, hi):
    t = np.clip((height - lo) / max(hi - lo, 1e-6), 0, 1)
    pos = t * (len(COLORS) - 1)
    base = np.stack([np.interp(pos, np.arange(len(COLORS)), COLORS[:, c]) for c in range(3)], axis=-1)
    return Image.fromarray(np.uint8(np.clip(base * (0.30 + 0.70 * shade(height, step))[..., None], 0, 255)))


def type_map(types):
    return Image.fromarray(TYPE_COLORS[np.clip(types, 0, 4)])


def draw_profile(draw, height, x0, y0, w, h, step):
    """Three cross-sections (rows 1/4, 1/2, 3/4) over a shared absolute-Y axis."""
    draw.rectangle((x0, y0, x0 + w, y0 + h), fill='#f7f6f0')
    rows = [height.shape[0] // 4, height.shape[0] // 2, 3 * height.shape[0] // 4]
    lo = float(min(height[r].min() for r in rows))
    hi = float(max(height[r].max() for r in rows))
    span = max(hi - lo, 1e-6)

    def y_of(v):
        return y0 + h - 6 - (h - 20) * (v - lo) / span

    for frac in (0.0, 0.5, 1.0):
        v = lo + span * frac
        yy = y_of(v)
        draw.line((x0, yy, x0 + w, yy), fill='#dcdbd2')
        draw.text((x0 + w + 4, yy - 7), f'{v:.0f}', font=SMALL, fill='#595950')

    styles = [('#8a8375', 1), ('#2c2a22', 2), ('#b06a4a', 1)]
    for r, (color, width) in zip(rows, styles):
        row = height[r]
        pts = [(x0 + i * (w - 1) / (len(row) - 1), y_of(row[i])) for i in range(len(row))]
        draw.line(pts, fill=color, width=width)


def main():
    root = Path(sys.argv[1])
    geo = json.loads((root / 'compose-geometry.json').read_text(encoding='utf-8'))
    size, step = geo['size'], geo['stepBlocks']
    arms = geo['arms']

    data = {}
    for arm in arms:
        ys = np.fromfile(root / f'{arm}.f64be.raw', dtype='>f8').reshape(size, size)
        types = np.fromfile(root / f'types-{arm}.u8', dtype=np.uint8).reshape(size, size)
        if not np.isfinite(ys).all():
            raise SystemExit(f'{arm}: non-finite height')
        data[arm] = (ys, types)

    # 自创的加权混合臂不再入图（用户判定顺序错误：应先复刻再对比）
    arms = [a for a in arms if a != 'compose-recipe']
    data = {k: v for k, v in data.items() if k in arms}

    lo = min(float(np.percentile(v[0], 1)) for v in data.values())
    hi = max(float(np.percentile(v[0], 99)) for v in data.values())

    cols = len(arms)
    card_w = TILE + CLOSE + GAP
    width = MARGIN * 2 + cols * card_w + (cols - 1) * GAP * 2
    top_h = TILE + 34 + PROFILE_H + 78
    row_h = top_h
    panel = Image.new('RGB', (width, 132 + 2 * row_h + GAP * 3), '#f4f3ed')
    draw = ImageDraw.Draw(panel)
    draw.text((MARGIN, 14), '地形合成｜参考组装复刻的六个阶段 vs 现行生产', font=TITLE, fill='#25261f')
    draw.text((MARGIN, 54),
              f'seed=9139912035078620160｜{size}² 采样、步长 {step} 块 ⇒ 覆盖 {size * step} 块'
              f'｜色阶 {lo:.0f}–{hi:.0f} Y（三臂共用）', font=SMALL, fill='#625e51')
    draw.text((MARGIN, 78),
              '每张：上为着色地形，右上为 1:1 局部，下为三条横切面（1/4、1/2、3/4 行；纵轴绝对 Y）。',
              font=SMALL, fill='#625e51')
    draw.text((MARGIN, 100),
              '下排：主导类型（蓝盆地 / 绿平原 / 黄土丘 / 红台地 / 灰山地）。四臂同种子、同窗口、同色阶。',
              font=SMALL, fill='#625e51')

    for i, arm in enumerate(arms):
        ys, types = data[arm]
        x = MARGIN + i * (card_w + GAP * 2)
        y = 126
        panel.paste(relief(ys, step, lo, hi).resize((TILE, TILE), Image.LANCZOS), (x, y))
        half = CLOSE // 2
        crop = ys[size // 2 - half: size // 2 + half, size // 2 - half: size // 2 + half]
        panel.paste(relief(crop, step, lo, hi).resize((CLOSE, CLOSE), Image.LANCZOS),
                    (x + TILE + GAP, y))
        draw.text((x + TILE + GAP, y + CLOSE + 3), '1:1 局部', font=SMALL, fill='#625e51')

        py = y + TILE + 6
        draw_profile(draw, ys, x, py, TILE, PROFILE_H, step)
        draw.text((x, py + PROFILE_H + 2), '剖面：Y 绝对值（灰 1/4 行 / 黑 1/2 行 / 砖 3/4 行）',
                  font=SMALL, fill='#625e51')

        gz, gx = np.gradient(ys, step)
        slope = np.hypot(gx, gz)
        draw.text((x, py + PROFILE_H + 24), arm, font=TEXT, fill='#25261f')
        draw.text((x, py + PROFILE_H + 48),
                  f'起伏 {ys.max() - ys.min():.0f} 格｜中位坡 {np.percentile(slope, 50):.2f}'
                  f'｜P99 {np.percentile(slope, 99):.2f}', font=SMALL, fill='#625e51')
        shares = ' '.join(f'{TYPE_NAMES[k]}{(types == k).mean() * 100:.0f}%' for k in range(5))
        draw.text((x, py + PROFILE_H + 66), shares, font=SMALL, fill='#625e51')

        ty = 126 + row_h + GAP
        panel.paste(type_map(types).resize((TILE, TILE), Image.NEAREST), (x, ty))
        draw.text((x, ty + TILE + 6), '主导类型', font=TEXT, fill='#25261f')

    out = root / 'composition.png'
    panel.save(out)
    print(out)


if __name__ == '__main__':
    main()