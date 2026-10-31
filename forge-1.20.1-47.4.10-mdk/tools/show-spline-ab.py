"""类型过渡：线性 vs Cubic Hermite 样条 的多臂对照。

同一类型分布（RegionPartition + slots 完全不变），只换 edge 上的插值曲线。

用法：python tools/show-spline-ab.py <outDir> <size> <step> <arm1> <arm2> ... -- <标签1> <标签2> ...
"""
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

FONT = 'C:/Windows/Fonts/msyh.ttc'
SEA = 63.0
LIGHT = np.array([-0.5, -0.35, 1.0]); LIGHT /= np.linalg.norm(LIGHT)
ANCH = np.array([-64, -40, -12, 62, 66, 76, 110, 170, 240, 320])
COL = np.array([
    [16, 26, 40], [24, 40, 62], [40, 66, 92], [58, 92, 116],
    [214, 205, 168], [96, 122, 78], [132, 142, 88],
    [168, 160, 128], [196, 190, 172], [238, 236, 228]], float)


def shade(h, step):
    gz, gx = np.gradient(h, step)
    n = np.sqrt(gx * gx + gz * gz + 1)
    lit = np.clip((-gx * LIGHT[0] - gz * LIGHT[1] + LIGHT[2]) / n, 0, 1)
    rgb = np.stack([np.interp(h, ANCH, COL[:, c]) for c in range(3)], axis=-1)
    w = h < SEA
    s = np.where(w, 0.72 + 0.28 * lit, 0.28 + 0.80 * lit)
    return np.uint8(np.clip(rgb * s[..., None], 0, 255))


def main():
    outDir = Path(sys.argv[1])
    size, step = int(sys.argv[2]), int(sys.argv[3])
    sep = sys.argv.index('--')
    arms = sys.argv[4:sep]
    labels = sys.argv[sep + 1:]

    data = {}
    for a, l in zip(arms, labels):
        p = outDir / f'{a}.f64be.raw'
        if not p.exists():
            print(f'  MISSING {a}'); continue
        h = np.fromfile(p, dtype='>f8').reshape(size, size)
        data[a] = (h, l)
        gz, gx = np.gradient(h, step)
        sl = np.hypot(gx, gz)
        print(f'  {l:22s} Y {h.min():6.2f}..{h.max():6.2f}  relief {h.max()-h.min():6.2f}  '
              f'坡P50 {np.percentile(sl,50):.3f}  P99 {np.percentile(sl,99):.2f}')

    n = len(data)
    tw = min(size, 1400 // max(1, n))
    pad, head, gap, caph, foot = 36, 140, 22, 66, 50
    W = pad * 2 + n * tw + (n - 1) * gap
    H = head + tw + caph + foot
    P = Image.new('RGB', (W, H), '#f4f3ed')
    d = ImageDraw.Draw(P)
    f1 = ImageFont.truetype(FONT, 30); f2 = ImageFont.truetype(FONT, 16)
    d.text((pad, 16), f'类型过渡 A/B：线性 vs Cubic Hermite 样条'
                      f'（{size*step}×{size*step} 块，1 像素 = {step} 块）',
           font=f1, fill='#25261f')
    d.text((pad, 58), '类型分布（RegionPartition + slots）完全不变，只换 edge 上的插值曲线',
           font=f2, fill='#5d594c')
    d.text((pad, 80), 'd = 两端导数值（MC 语义，×段宽1）；d=0 时退化为 smoothstep-like，'
                      'd 越大两端越"收坡"', font=f2, fill='#5d594c')

    for i, (a, (h, l)) in enumerate(data.items()):
        x = pad + i * (tw + gap)
        P.paste(Image.fromarray(shade(h, step)).resize((tw, tw), Image.LANCZOS), (x, head))
        d.text((x, head + tw + 6), l, font=ImageFont.truetype(FONT, 18), fill='#25261f')
        gz, gx = np.gradient(h, step)
        sl = np.hypot(gx, gz)
        d.text((x, head + tw + 30),
               f'Y {h.min():.0f}–{h.max():.0f}｜relief {h.max()-h.min():.0f}',
               font=f2, fill='#5d594c')
        d.text((x, head + tw + 48),
               f'坡 P50 {np.percentile(sl,50):.2f}｜P99 {np.percentile(sl,99):.1f}',
               font=f2, fill='#5d594c')

    out = outDir / 'spline-ab.png'
    P.save(out, optimize=True)
    print(out)


if __name__ == '__main__':
    main()
