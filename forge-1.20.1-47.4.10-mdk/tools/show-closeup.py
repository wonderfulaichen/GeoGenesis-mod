"""Render a 1:1 close-up of the badlands candidate to inspect actual terrace geometry."""
import json
from pathlib import Path
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFont

FONT = 'C:/Windows/Fonts/msyh.ttc'
LIGHT = np.array([-0.5, -0.35, 1.0]); LIGHT /= np.linalg.norm(LIGHT)
ANCHORS = np.array([-64, 45, 63, 70, 80, 150, 220, 320])
COLORS = np.array([[39, 57, 67], [65, 83, 76], [83, 104, 79], [120, 140, 90],
                   [150, 165, 110], [172, 161, 130], [188, 178, 154], [227, 225, 213]])


def image_for(height, step):
    gz, gx = np.gradient(height, step)
    norm = np.sqrt(gx * gx + gz * gz + 1)
    shade = np.clip((-gx * LIGHT[0] - gz * LIGHT[1] + LIGHT[2]) / norm, 0, 1)
    brightness = 0.15 + 0.85 * shade
    rgb = np.stack([np.interp(height, ANCHORS, COLORS[:, c]) for c in range(3)], axis=-1)
    return Image.fromarray(np.uint8(np.clip(rgb * brightness[..., None], 0, 255)))


def main():
    root = Path(sys.argv[1])
    geometry = json.loads((root / 'geometry.json').read_text(encoding='utf-8'))
    size, step = geometry['size'], geometry['stepBlocks']
    values = np.fromfile(root / 'BADLANDS.f64be.raw', dtype='>f8')
    height = values.reshape(size, size)

    title = ImageFont.truetype(FONT, 32)
    text = ImageFont.truetype(FONT, 20)
    small = ImageFont.truetype(FONT, 17)

    scale = 3
    panel = Image.new('RGB', (size * scale + 60, size * scale + 290), '#f4f3ed')
    draw = ImageDraw.Draw(panel)
    draw.text((25, 14), '劣地 v5 局部 1:1（323×323 方块）', font=title, fill='#25261f')
    draw.text((25, 60), '用参考的真实配方链：脊线网络 × 成片掩码 → 4/10 级软台阶 → 双尺度合成', font=text, fill='#625e51')
    draw.text((25, 92), f'Y {height.min():.2f}–{height.max():.2f}｜起伏 {height.max() - height.min():.2f} 格', font=text, fill='#625e51')
    panel.paste(image_for(height, step).resize((size * scale, size * scale), Image.NEAREST), (30, 130))

    # Profile showing whether steps are plateaus or noise
    py0 = size * scale + 150
    draw.rectangle((30, py0, 30 + size * scale, py0 + 110), fill='#f4f3ed')
    row = height[size // 2]
    lo, hi = float(row.min()), float(row.max())
    span = max(hi - lo, 1e-6)
    for k in range(4):
        level = lo + span * k / 3
        yy = py0 + 100 - 90 * k / 3
        draw.line((30, yy, 30 + size * scale, yy), fill='#d0cfc8')
        draw.text((30 + size * scale + 4, yy - 10), f'{level:.1f}', font=small, fill='#595950')
    pts = [(30 + i * size * scale / (size - 1), py0 + 100 - 90 * (row[i] - lo) / span)
           for i in range(size)]
    draw.line(pts, fill='#403b30', width=2)
    draw.text((30, py0 + 112), f'单行剖面（未放大纵向）：能看出平顶段与短斜坡就说明层理成立；若全是均匀锯齿则是噪声',
              font=small, fill='#625e51')
    out = root / 'BADLANDS-closeup.png'
    panel.save(out)
    print(out)


if __name__ == '__main__':
    main()
