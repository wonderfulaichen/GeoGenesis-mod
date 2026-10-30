"""1:1 局部特写 + 修正配色的差异图。

产出：
  closeups.png   6 个 1:1 窗口（每格 = step 块，无缩放）
  diff-fixed.png 抬升=暖色 / 下沉=冷色（修正 render-islands-big.py 的配色反向）

用法：python tools/show-islands-closeup.py <outDir> <winBlocks> [副标题]
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
    shade = np.where(water, 0.72 + 0.28 * lit, 0.28 + 0.80 * lit)
    return Image.fromarray(np.uint8(np.clip(rgb * shade[..., None], 0, 255)))


def diff_img(a, b, step):
    d = b - a
    amp = max(float(np.percentile(np.abs(d), 99.5)), 1.0)
    t = np.clip(np.abs(d) / amp, 0, 1)
    base = np.full(d.shape + (3,), 244.0)
    # 抬升 = 暖色（红橙），下沉 = 冷色（蓝）
    warm = np.stack([255 * t, 150 * t, 40 * t], axis=-1)
    cool = np.stack([40 * t, 150 * t, 255 * t], axis=-1)
    rgb = np.where((d > 0)[..., None], base - warm, base)
    rgb = np.where((d < 0)[..., None], base - cool, rgb)
    gz, gx = np.gradient(d, step)
    lit = np.clip((-gx * LIGHT[0] - gz * LIGHT[1] + LIGHT[2])
                  / np.sqrt(gx * gx + gz * gz + 1), 0, 1)
    return Image.fromarray(np.uint8(np.clip(rgb * (0.55 + 0.45 * lit)[..., None], 0, 255)))


def main():
    root = Path(sys.argv[1])
    win = int(sys.argv[2]) if len(sys.argv) > 2 else 240
    subtitle = sys.argv[3] if len(sys.argv) > 3 else ''

    geo = json.loads((root / 'compose-geometry.json').read_text(encoding='utf-8'))
    size, step = geo['size'], geo['stepBlocks']
    ox, oz = geo['originBlocks']

    old = np.fromfile(root / 'compose-coastal.f64be.raw', dtype='>f8').reshape(size, size)
    new = np.fromfile(root / 'compose-coastal-islands.f64be.raw', dtype='>f8').reshape(size, size)

    diff_img(old, new, step).save(root / 'diff-fixed.png', optimize=True)
    print(root / 'diff-fixed.png')

    # 找"新增陆地"最集中的窗口：滑窗统计，取互不重叠的 6 个
    gain = (old < SEA) & (new >= SEA)
    from numpy.lib.stride_tricks import sliding_window_view
    if win < size:
        cnt = sliding_window_view(gain, (win, win)).sum(axis=(2, 3))
    else:
        cnt = np.array([[int(gain.sum())]])

    picks = []
    work = cnt.copy()
    for _ in range(6):
        z0, x0 = np.unravel_index(np.argmax(work), work.shape)
        if work[z0, x0] < 20:
            break
        picks.append((int(z0), int(x0), int(work[z0, x0])))
        # 剔除已选窗口的邻域，避免 6 张图几乎重复
        zh = min(work.shape[0], z0 + win // 2)
        zl = max(0, z0 - win // 2)
        xh = min(work.shape[1], x0 + win // 2)
        xl = max(0, x0 - win // 2)
        work[zl:zh, xl:xh] = 0

    cols = 3
    rows = (len(picks) + cols - 1) // cols
    pad, head, gap, foot = 36, 132, 26, 40
    tile = win
    cap_h = 52
    W = pad * 2 + cols * tile + (cols - 1) * gap
    H = head + rows * (tile + cap_h) + (rows - 1) * gap + foot
    panel = Image.new('RGB', (W, H), '#f4f3ed')
    draw = ImageDraw.Draw(panel)
    d2 = ImageDraw.Draw(panel)
    d2.text((pad, 22), f'浅海群岛 · 1:1 局部特写（每窗口 {win * step} × {win * step} 块，未缩放）',
            font=ImageFont.truetype(FONT, 30), fill='#25261f')
    t2 = ImageFont.truetype(FONT, 17)
    d2.text((pad, 72), f'seed=9139912035078620160｜{size}² 采样｜1 像素 = {step} 块｜'
                       f'选窗依据：新增陆地像素最多的窗口', font=t2, fill='#5d594c')
    d2.text((pad, 98), subtitle, font=t2, fill='#5d594c')

    for i, (z0, x0, c) in enumerate(picks):
        r, cc = divmod(i, cols)
        x = pad + cc * (tile + gap)
        y = head + r * (tile + cap_h + gap)
        blkz = oz + z0 * step
        blkx = ox + x0 * step
        panel.paste(render(new[z0:z0 + tile, x0:x0 + tile], step), (x, y))
        d2.text((x, y + tile + 6), f'块 ({blkz:.0f}, {blkx:.0f})', font=t2, fill='#25261f')
        d2.text((x, y + tile + 26), f'+{c} px 新增陆地', font=t2, fill='#5d594c')

    out = root / 'closeups.png'
    panel.save(out, optimize=True)
    print(out)


if __name__ == '__main__':
    main()
