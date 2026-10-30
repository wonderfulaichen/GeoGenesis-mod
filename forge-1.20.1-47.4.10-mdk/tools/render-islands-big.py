"""最终交付大图：1401² 全分辨率，不缩放（1 像素 = 1 采样点 = step 块）。

产出三张：
  big-coastal.png          改前基线（compose-coastal）
  big-islands.png          叠加浅海群岛 + 新海岸（compose-coastal-islands）
  big-pair.png             两者并排 + 差异图，用于 A/B 目检

用法：python tools/render-islands-big.py <outDir> <对照outDir> [副标题]
"""
import json
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

FONT = 'C:/Windows/Fonts/msyh.ttc'
SEA = 63.0
LIGHT = np.array([-0.5, -0.35, 1.0])
LIGHT /= np.linalg.norm(LIGHT)

# 高度色带：深海底 → 深海 → 浅海 → 滩 → 草 → 高地 → 岩 → 雪
ANCHORS = np.array([-64, -40, -12, 62, 66, 76, 110, 170, 240, 320])
COLORS = np.array([
    [16, 26, 40], [24, 40, 62], [40, 66, 92], [58, 92, 116],
    [214, 205, 168], [96, 122, 78], [132, 142, 88],
    [168, 160, 128], [196, 190, 172], [238, 236, 228],
])


def render(height, step):
    gz, gx = np.gradient(height, step)
    norm = np.sqrt(gx * gx + gz * gz + 1)
    lit = np.clip((-gx * LIGHT[0] - gz * LIGHT[1] + LIGHT[2]) / norm, 0, 1)
    rgb = np.stack([np.interp(height, ANCHORS, COLORS[:, c]) for c in range(3)], axis=-1)
    water = height < SEA
    # 水面：只保留很弱的明暗（水下地形读作浅/深，不参与强光照）
    shade_w = 0.72 + 0.28 * lit
    shade_l = 0.28 + 0.80 * lit
    shade = np.where(water, shade_w, shade_l)
    return Image.fromarray(np.uint8(np.clip(rgb * shade[..., None], 0, 255)))


def diff_img(a, b, step):
    d = b - a
    amp = max(float(np.percentile(np.abs(d), 99.5)), 1.0)
    t = np.clip(np.abs(d) / amp, 0, 1)
    # 抬升 = 暖色，下沉 = 冷色，未变 = 底灰
    warm = np.stack([255 * t, 150 * t, 40 * t], axis=-1)
    cool = np.stack([40 * t, 150 * t, 255 * t], axis=-1)
    base = np.full(d.shape + (3,), 244.0)
    rgb = np.where((d > 0)[..., None], 244 - warm, base)
    rgb = np.where((d < 0)[..., None], 244 - cool, rgb)
    gz, gx = np.gradient(d, step)
    lit = np.clip((-gx * LIGHT[0] - gz * LIGHT[1] + LIGHT[2])
                  / np.sqrt(gx * gx + gz * gz + 1), 0, 1)
    rgb = rgb * (0.55 + 0.45 * lit)[..., None]
    return Image.fromarray(np.uint8(np.clip(rgb, 0, 255)))


def label(draw, xy, text, size=30):
    draw.text(xy, text, font=ImageFont.truetype(FONT, size), fill='#25261f')


def main():
    new_root = Path(sys.argv[1])
    old_root = Path(sys.argv[2])
    subtitle = sys.argv[3] if len(sys.argv) > 3 else ''

    geo = json.loads((new_root / 'compose-geometry.json').read_text(encoding='utf-8'))
    size, step = geo['size'], geo['stepBlocks']
    ox, oz = geo['originBlocks']
    cover = size * step

    def load(root, arm):
        return np.fromfile(root / f'{arm}.f64be.raw', dtype='>f8').reshape(size, size)

    old = load(old_root, 'compose-coastal')
    new = load(new_root, 'compose-coastal-islands')

    for arr, name in ((old, 'big-coastal'), (new, 'big-islands')):
        out = new_root / f'{name}.png'
        render(arr, step).save(out, optimize=True)
        print(out)

    # 并排 + 差异
    pad, head, foot, gap = 40, 130, 90, 40
    width = pad * 2 + size * 2 + gap
    panel = Image.new('RGB', (width, head + size + foot), '#f4f3ed')
    draw = ImageDraw.Draw(panel)
    label(draw, (pad, 22), f'浅海群岛 · 全幅大图（{cover} × {cover} 块，1 像素 = {step} 块，未缩放）', 40)
    t2 = ImageFont.truetype(FONT, 22)
    draw.text((pad, 74),
              f'seed=9139912035078620160｜中心块 ({ox + cover / 2:.0f}, {oz + cover / 2:.0f})'
              f'｜Y {min(old.min(), new.min()):.0f}–{max(old.max(), new.max()):.0f}', font=t2, fill='#5d594c')
    draw.text((pad, 102), subtitle, font=t2, fill='#5d594c')

    panel.paste(render(old, step), (pad, head))
    panel.paste(render(new, step), (pad + size + gap, head))
    label(draw, (pad, head + size + 10),
          f'改前 compose-coastal｜水面 {(old < SEA).mean() * 100:.1f}%｜起伏 {old.max() - old.min():.0f} 格', 26)
    label(draw, (pad + size + gap, head + size + 10),
          f'叠加群岛 + 新海岸｜水面 {(new < SEA).mean() * 100:.1f}%｜起伏 {new.max() - new.min():.0f} 格', 26)
    out = new_root / 'big-pair.png'
    panel.save(out, optimize=True)
    print(out)

    # 差异图（单独一张，放大后看得清）
    d = new - old
    render_bytes = diff_img(old, new, step)
    bare = new_root / 'big-diff.png'
    render_bytes.save(bare, optimize=True)
    print(bare)
    print(f'max lift {(d.max()):.1f}  max drop {(d.min()):.1f}  '
          f'land->water {((old >= SEA) & (new < SEA)).sum()}  '
          f'water->land {((old < SEA) & (new >= SEA)).sum()}')


if __name__ == '__main__':
    main()
