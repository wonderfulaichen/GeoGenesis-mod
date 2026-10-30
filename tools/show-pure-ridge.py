"""侵蚀滤镜作用在真实噪声山体上：RefNoise 三档 vs RidgeValley 对照。

用法：python tools/show-mtn-erosion.py <outDir>
"""
import json
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

FONT = 'C:/Windows/Fonts/msyh.ttc'
LIGHT = np.array([-0.5, -0.35, 1.0])
LIGHT /= np.linalg.norm(LIGHT)
ANCHORS = np.array([0.20, 0.28, 0.36, 0.45, 0.55, 0.68, 0.82])
COLORS = np.array([
    [34, 62, 48], [76, 104, 66], [116, 140, 82],
    [158, 164, 108], [192, 180, 140], [218, 210, 192], [242, 240, 232],
])


def shade(h, step):
    gz, gx = np.gradient(h, step)
    lit = np.clip((-gx * LIGHT[0] - gz * LIGHT[1] + LIGHT[2])
                  / np.sqrt(gx * gx + gz * gz + 1), 0, 1)
    rgb = np.stack([np.interp(h, ANCHORS, COLORS[:, c]) for c in range(3)], axis=-1)
    return np.uint8(np.clip(rgb * (0.28 + 0.85 * lit)[..., None], 0, 255))


def main():
    root = Path(sys.argv[1])
    geo = json.loads((root / 'PR-geometry.json').read_text(encoding='utf-8'))
    size, step = geo['size'], geo['stepBlocks']
    arms = geo['arms']

    hs = {a: np.fromfile(root / f'{a}.f64be.raw', dtype='>f8').reshape(size, size) for a in arms}
    lo = min(float(h.min()) for h in hs.values())
    hi = max(float(h.max()) for h in hs.values())

    labels = {
        'PR-IN': '① 朴素 PerlinRidge（基准，零污染）',
        'PR-REF30': '② + RefNoise 0.30\n形态保留多',
        'PR-REF55': '③ + RefNoise 0.55',
        'PR-REF85': '④ + RefNoise 0.85\n形态被重塑最多',
        'PR-RIDGE': '⑤ + RidgeValleyErosion\n（现状骨架口径）',
    }
    stats = {
        'MTN-IN': 'corr 1.0000（基准） relief 0.4583',
        'PR-REF30': 'corr 0.9461  mean|Δ| 0.0508  aniso 0.057',
        'PR-REF55': 'corr 0.7631  mean|Δ| 0.0932  aniso 0.021',
        'PR-REF85': 'corr 0.4059  mean|Δ| 0.1440  aniso 0.041',
        'PR-RIDGE': 'corr 0.9848  mean|Δ| 0.0115  aniso 0.026',
    }

    cols = 5
    tile = min(size, 300)
    pad, gap, head = 26, 16, 140
    width = pad * 2 + tile * cols + gap * (cols - 1)
    panel = Image.new('RGB', (width, head + tile + 96), '#f4f3ed')
    draw = ImageDraw.Draw(panel)
    t1 = ImageFont.truetype(FONT, 29)
    t2 = ImageFont.truetype(FONT, 16)
    t3 = ImageFont.truetype(FONT, 14)

    draw.text((pad, 12), '隔离 RefNoise 的真实贡献（基底 = 朴素 PerlinRidge 610块/4倍频，零滤镜）', font=t1, fill='#25261f')
    draw.text((pad, 50), f'{size}²×{step}块/像素（{size * step} 块视野）｜共同色阶 e {lo:.3f}–{hi:.3f}'
                         f'（≈{(hi - lo) * 236:.0f} 块）', font=t2, fill='#5d594c')
    draw.text((pad, 74), 'corr = 与原始山体的相关系数（越低 ⇒ 形态被重塑越多）；'
                         'aniso = 梯度方向一致性（越高 ⇒ 越像单向条纹）', font=t3, fill='#5d594c')
    draw.text((pad, 96), '★ 上一轮"平坦基底"实验的结论作废——那是测错了对象：'
                         '滤镜需要一个山体才有东西可重塑', font=t3, fill='#b8654a')

    for i, a in enumerate(arms):
        x = pad + i * (tile + gap)
        y = head
        panel.paste(Image.fromarray(shade(hs[a], step)).resize((tile, tile), Image.LANCZOS), (x, y))
        draw.multiline_text((x, y + tile + 6), labels.get(a, a), font=t2, fill='#25261f', spacing=3)
        draw.text((x, y + tile + 52), stats.get(a, ''), font=t3, fill='#5d594c')

    out = root / 'pure-ridge-erosion.png'
    panel.save(out, optimize=True)
    print(out)


if __name__ == '__main__':
    main()
