"""海岸剖面对照：同一窗口并排「旧剖面 / 新剖面」，自动选岸带变化最大的位置。

用法：python tools/show-coast-compare.py <旧outDir> <新outDir> [臂名] [窗口像素]
"""
import json
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont
from scipy import ndimage as ndi

FONT = 'C:/Windows/Fonts/msyh.ttc'
SEA = 63.0
LIGHT = np.array([-0.5, -0.35, 1.0])
LIGHT /= np.linalg.norm(LIGHT)
ANCHORS = np.array([-64, 45, 63, 70, 80, 150, 220, 320])
COLORS = np.array([[39, 57, 67], [65, 83, 76], [83, 104, 79], [120, 140, 90],
                   [150, 165, 110], [172, 161, 130], [188, 178, 154], [227, 225, 213]])


def shade(height, step):
    gz, gx = np.gradient(height, step)
    norm = np.sqrt(gx * gx + gz * gz + 1)
    s = np.clip((-gx * LIGHT[0] - gz * LIGHT[1] + LIGHT[2]) / norm, 0, 1)
    rgb = np.stack([np.interp(height, ANCHORS, COLORS[:, c]) for c in range(3)], axis=-1)
    rgb[height < SEA] = np.array([52, 78, 96])
    return Image.fromarray(np.uint8(np.clip(rgb * (0.2 + 0.8 * s)[..., None], 0, 255)))


def main():
    old_root = Path(sys.argv[1])
    new_root = Path(sys.argv[2])
    arm = sys.argv[3] if len(sys.argv) > 3 else 'compose-coastal'
    win = int(sys.argv[4]) if len(sys.argv) > 4 else 240

    geo = json.loads((new_root / 'compose-geometry.json').read_text(encoding='utf-8'))
    size, step = geo['size'], geo['stepBlocks']
    ox, oz = geo['originBlocks']

    a = np.fromfile(old_root / f'{arm}.f64be.raw', dtype='>f8').reshape(size, size)
    b = np.fromfile(new_root / f'{arm}.f64be.raw', dtype='>f8').reshape(size, size)

    d = np.abs(b - a)
    sm = ndi.uniform_filter(np.where(d > 0.5, d, 0.0), 61)
    z, x = np.unravel_index(int(np.argmax(sm)), sm.shape)
    z = int(np.clip(z, win // 2, size - win // 2))
    x = int(np.clip(x, win // 2, size - win // 2))
    print(f'window at block ({ox + x * step:.0f}, {oz + z * step:.0f})')

    y0, x0 = z - win // 2, x - win // 2
    scale = 3
    tile = win * scale
    gap = 30
    panel = Image.new('RGB', (40 * 2 + tile * 2 + gap, 110 + tile + 60), '#f4f3ed')
    draw = ImageDraw.Draw(panel)
    t1 = ImageFont.truetype(FONT, 28)
    t2 = ImageFont.truetype(FONT, 18)
    draw.text((40, 16),
              f'海岸剖面对照 1:1（{win * step} × {win * step} 块，'
              f'{ox + x * step:.0f}, {oz + z * step:.0f}）', font=t1, fill='#25261f')
    draw.text((40, 58), '左 = 旧（平台 + 中段两段拼接）／右 = 新（单条 smoothstep + 沿海下沉）',
              font=t2, fill='#5d594c')

    for i, (name, arr) in enumerate((('旧', a), ('新', b))):
        ys = arr[y0:y0 + win, x0:x0 + win]
        px = 40 + i * (tile + gap)
        panel.paste(shade(ys, step).resize((tile, tile), Image.NEAREST), (px, 110))
        land = ys[ys >= SEA]
        p99 = np.percentile(land, 99) if land.size else 0
        draw.text((px, 110 + tile + 8),
                  f'{name}  水面 {(ys < SEA).mean() * 100:.0f}%｜陆地 P99 {p99:.0f} 格'
                  f'｜起伏 {ys.max() - ys.min():.0f} 格', font=t2, fill='#25261f')

    out = new_root / 'coast-compare.png'
    panel.save(out)
    print(out)


if __name__ == '__main__':
    main()
