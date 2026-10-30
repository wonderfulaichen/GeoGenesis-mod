"""丘陵变体对照：多臂并排（同一窗口、同一色阶）。

用法：python tools/show-hills-compare.py <outDir> [窗口px] [臂:标签,...]
"""
import json
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

FONT = 'C:/Windows/Fonts/msyh.ttc'
LIGHT = np.array([-0.5, -0.35, 1.0])
LIGHT /= np.linalg.norm(LIGHT)
ANCHORS = np.array([-64, 45, 63, 70, 80, 110, 150, 200, 260, 320])
COLORS = np.array([
    [39, 57, 67], [65, 83, 76], [83, 104, 79], [120, 140, 90],
    [150, 165, 110], [172, 161, 130], [180, 168, 138],
    [196, 188, 166], [216, 210, 196], [238, 236, 228],
])


def render(h, step, lo, hi):
    gz, gx = np.gradient(h, step)
    lit = np.clip((-gx * LIGHT[0] - gz * LIGHT[1] + LIGHT[2])
                  / np.sqrt(gx * gx + gz * gz + 1), 0, 1)
    t = np.clip((h - lo) / max(hi - lo, 1e-6), 0, 1)
    pos = t * (len(COLORS) - 1)
    rgb = np.stack([np.interp(pos, np.arange(len(COLORS)), COLORS[:, c]) for c in range(3)], axis=-1)
    return Image.fromarray(np.uint8(np.clip(rgb * (0.25 + 0.85 * lit)[..., None], 0, 255)))


def main():
    root = Path(sys.argv[1])
    win = int(sys.argv[2]) if len(sys.argv) > 2 else 240
    spec = sys.argv[3] if len(sys.argv) > 3 else 'HILLS-dales-geo:dales-geo 现状,RIDGED-NOGROOVE:圆包 无侵蚀,RIDGED-G15:反转侵蚀 0.15,RIDGED-G35:反转侵蚀 0.35,RIDGED:反转侵蚀 0.55'
    step = 5

    geo = json.loads((root / 'geometry.json').read_text(encoding='utf-8'))
    size = geo['size']

    x0 = 60
    z0 = 60
    arms = []
    for item in spec.split(','):
        arm, _, label = item.partition(':')
        p = root / f'{arm}.f64be.raw'
        if not p.exists():
            print(f'WARN missing {p}')
            continue
        h = np.fromfile(p, dtype='>f8').reshape(size, size)
        arms.append((label or arm, h[z0:z0 + win, x0:x0 + win], h))

    # 共用色阶
    lo = min(float(np.min(a[1])) for a in arms)
    hi = max(float(np.max(a[1])) for a in arms)

    scale = 2
    tile = win * scale
    gap, pad = 24, 30
    head = 120
    panel = Image.new('RGB', (pad * 2 + tile * len(arms) + gap * (len(arms) - 1),
                              head + tile + 90), '#f4f3ed')
    draw = ImageDraw.Draw(panel)
    t1 = ImageFont.truetype(FONT, 30)
    t2 = ImageFont.truetype(FONT, 17)
    t3 = ImageFont.truetype(FONT, 15)

    draw.text((pad, 14), '丘陵变体对照（同 seed、同窗口、同色阶）', font=t1, fill='#25261f')
    draw.text((pad, 52), f'窗口 {win * step} × {win * step} 块，1 像素 = {step} 块，色阶 Y {lo:.0f}–{hi:.0f}',
              font=t2, fill='#5d594c')
    draw.text((pad, 78), '同 seed 同窗口：对比各变体的形态（碎纹 / 谷网 / 圆包）',
              font=t3, fill='#5d594c')

    for i, (label, crop, full) in enumerate(arms):
        px = pad + i * (tile + gap)
        panel.paste(render(crop, step, lo, hi).resize((tile, tile), Image.NEAREST), (px, head))
        draw.text((px, head + tile + 8), label, font=t2, fill='#25261f')
        gz, gx = np.gradient(crop, step)
        slope = np.hypot(gx, gz)
        flat = (np.abs(np.diff(crop, axis=0)) < 0.3).mean() * 100
        draw.text((px, head + tile + 32),
                  f'起伏 {crop.max() - crop.min():.0f} 格｜坡 P50 {np.percentile(slope, 50):.2f}｜平坦 {flat:.0f}%',
                  font=t3, fill='#5d594c')

    out = root / 'hills-compare.png'
    panel.save(out, optimize=True)
    print(out)


if __name__ == '__main__':
    main()
