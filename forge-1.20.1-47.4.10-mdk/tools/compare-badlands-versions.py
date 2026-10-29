"""Side-by-side comparison of badlands candidate iterations, same scale and lighting."""
import json
from pathlib import Path
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFont

FONT = 'C:/Windows/Fonts/msyh.ttc'
LIGHT = np.array([-0.5, -0.35, 1.0]); LIGHT /= np.linalg.norm(LIGHT)
ANCHORS = np.array([-64, 45, 63, 90, 150, 220, 300, 320])
COLORS = np.array([[39, 57, 67], [65, 83, 76], [83, 104, 79], [135, 153, 99],
                   [172, 161, 130], [188, 178, 154], [227, 225, 213], [242, 241, 233]])
VERSIONS = [
    ('badlands-v1', 'v1 多频脊线直接相加', '处处等密度细脊纹 — 本项目否决过'),
    ('badlands-v2', 'v2 脊线阈值切沟', '沟断成孤立斑块，不连通'),
    ('badlands-v3', 'v3 三级方山（宽壁）', '台面平但壁不陡'),
    ('badlands-candidate', 'v4 三级方山（窄壁）', '壁略陡，仍是光滑团块'),
]


def image_for(height, step):
    gz, gx = np.gradient(height, step)
    norm = np.sqrt(gx * gx + gz * gz + 1)
    shade = np.clip((-gx * LIGHT[0] - gz * LIGHT[1] + LIGHT[2]) / norm, 0, 1)
    brightness = 0.23 + 0.77 * shade
    rgb = np.stack([np.interp(height, ANCHORS, COLORS[:, c]) for c in range(3)], axis=-1)
    return Image.fromarray(np.uint8(np.clip(rgb * brightness[..., None], 0, 255)))


def main():
    root = Path(sys.argv[1])
    out = Path(sys.argv[2])
    geometry = json.loads((root / 'badlands-candidate' / 'geometry.json').read_text(encoding='utf-8'))
    size, step = geometry['size'], geometry['stepBlocks']
    title = ImageFont.truetype(FONT, 34)
    text = ImageFont.truetype(FONT, 22)
    small = ImageFont.truetype(FONT, 18)

    tile = 500
    panel = Image.new('RGB', (tile * 2 + 75, tile * 2 + 300), '#f4f3ed')
    draw = ImageDraw.Draw(panel)
    draw.text((25, 14), '劣地候选四版对照｜同一坐标、同一色阶、同一光照', font=title, fill='#25261f')
    draw.text((25, 62), '四版都没达到参考的劣地观感；保留全过程，不把某一版说成成功。', font=text, fill='#8a3a2a')

    for i, (folder, label, verdict) in enumerate(VERSIONS):
        raw = root / folder / 'BADLANDS.f64be.raw'
        values = np.fromfile(raw, dtype='>f8')
        if len(values) != size * size:
            raise SystemExit(f'{folder}: bad length {len(values)}')
        height = values.reshape(size, size)
        gz, gx = np.gradient(height, step)
        slope = np.hypot(gx, gz)
        x = 25 + (i % 2) * (tile + 25)
        y = 110 + (i // 2) * (tile + 105)
        panel.paste(image_for(height, step).resize((tile, tile), Image.LANCZOS), (x, y))
        draw.text((x, y + tile + 8), label, font=text, fill='#25261f')
        draw.text((x, y + tile + 36),
                  f'起伏 {height.max() - height.min():.1f} 格｜平顶 {(slope < 0.02).mean() * 100:.0f}%｜陡壁 {(slope > 0.5).mean() * 100:.1f}%',
                  font=small, fill='#625e51')
        draw.text((x, y + tile + 60), verdict, font=small, fill='#8a3a2a')

    panel.save(out)
    print(out)


if __name__ == '__main__':
    main()
