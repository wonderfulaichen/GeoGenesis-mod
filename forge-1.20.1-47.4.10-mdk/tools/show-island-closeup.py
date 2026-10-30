"""1:1 局部放大：浅海群岛 vs 叠加前，自动挑「新增陆地最多」的窗口。

用法：python tools/show-island-closeup.py <outDir> [窗口采样数] [步长块]
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
ANCHORS = np.array([-64, 45, 63, 70, 80, 150, 220, 320])
COLORS = np.array([[39, 57, 67], [65, 83, 76], [83, 104, 79], [120, 140, 90],
                   [150, 165, 110], [172, 161, 130], [188, 178, 154], [227, 225, 213]])

ARMS = ['compose-coastal', 'compose-coastal-islands', 'compose-islands-only']


def shade(height, step):
    gz, gx = np.gradient(height, step)
    norm = np.sqrt(gx * gx + gz * gz + 1)
    s = np.clip((-gx * LIGHT[0] - gz * LIGHT[1] + LIGHT[2]) / norm, 0, 1)
    rgb = np.stack([np.interp(height, ANCHORS, COLORS[:, c]) for c in range(3)], axis=-1)
    water = height < SEA
    rgb[water] = np.array([52, 78, 96])
    return Image.fromarray(np.uint8(np.clip(rgb * (0.2 + 0.8 * s)[..., None], 0, 255)))


def best_window(new_land, win):
    """在新增陆地掩码上做盒式积分，返回新增陆地最多的窗口左上角。"""
    integral = new_land.astype(np.int64)
    integral = np.pad(integral, ((1, 0), (1, 0))).cumsum(0).cumsum(1)
    sums = (integral[win:, win:] - integral[:-win, win:]
            - integral[win:, :-win] + integral[:-win, :-win])
    z, x = np.unravel_index(int(np.argmax(sums)), sums.shape)
    return int(x), int(z)


def main():
    root = Path(sys.argv[1])
    win = int(sys.argv[2]) if len(sys.argv) > 2 else 260
    step = int(sys.argv[3]) if len(sys.argv) > 3 else 2

    geo = json.loads((root / 'compose-geometry.json').read_text(encoding='utf-8'))
    size = geo['size']

    def load(arm):
        return np.fromfile(root / f'{arm}.f64be.raw', dtype='>f8').reshape(size, size)

    base = load('compose-coastal')
    isl = load('compose-coastal-islands')
    new_land = (base < SEA) & (isl >= SEA)
    x0, z0 = best_window(new_land, win)

    ox, oz = geo['originBlocks']
    bx, bz = ox + x0 * step, oz + z0 * step
    print(f'window at block ({bx:.0f}, {bz:.0f})  {win * step} x {win * step} 块')

    pads = []
    for arm in ARMS:
        ys = load(arm)[z0:z0 + win, x0:x0 + win]
        pads.append((arm, ys))

    scale = 2
    tile = win * scale
    gap = 24
    title_h, foot_h = 96, 60
    width = 40 * 2 + len(pads) * tile + gap * (len(pads) - 1)
    panel = Image.new('RGB', (width, title_h + tile + foot_h), '#f4f3ed')
    draw = ImageDraw.Draw(panel)
    t1 = ImageFont.truetype(FONT, 30)
    t2 = ImageFont.truetype(FONT, 17)

    draw.text((40, 16), f'浅海群岛局部 1:1（{win * step} × {win * step} 块，中心块 {bx:.0f}, {bz:.0f}）',
              font=t1, fill='#25261f')
    draw.text((40, 58),
              '窗口自动选在「新增陆地最多」处。深蓝 = 水面（Y<63）。',
              font=t2, fill='#5d594c')

    for i, (arm, ys) in enumerate(pads):
        px = 40 + i * (tile + gap)
        panel.paste(shade(ys, step).resize((tile, tile), Image.NEAREST), (px, title_h))
        land = ys[ys >= SEA]
        info = (f'{arm}' if land.size == 0
                else f'{arm}  陆地 {land.size / ys.size * 100:.0f}%  P99 {np.percentile(land, 99):.0f} 格')
        draw.text((px, title_h + tile + 8), info, font=t2, fill='#25261f')
        draw.text((px, title_h + tile + 30),
                  f'水面 {(ys < SEA).mean() * 100:.0f}%｜起伏 {ys.max() - ys.min():.0f} 格',
                  font=t2, fill='#5d594c')

    out = root / 'island-closeup.png'
    panel.save(out)
    print(out)


if __name__ == '__main__':
    main()
