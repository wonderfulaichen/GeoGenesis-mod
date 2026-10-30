"""晕斑位置 A/B 特写 + 高度剖面。

用户问题：
  1. 平原上那片晕斑还有残留吗？
  2. 高度好像变高了？一般平原不是应该在接近海平面吗？

用法：python tools/show-blotch-ab.py <oldDir> <newDir> <size> <step> <bx0 bz0 bx1 bz1>
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
    olddir, newdir = Path(sys.argv[1]), Path(sys.argv[2])
    size, step = int(sys.argv[3]), int(sys.argv[4])
    bx0, bz0, bx1, bz1 = (float(v) for v in sys.argv[5:9])
    ox, oz = -3640.0, -3363.0

    ld = lambda d: np.fromfile(d / 'compose-coastal-islands.f64be.raw', dtype='>f8').reshape(size, size)
    old, new = ld(olddir), ld(newdir)

    x0, x1 = max(0, int((bx0 - ox) / step)), min(size, int((bx1 - ox) / step))
    z0, z1 = max(0, int((bz0 - oz) / step)), min(size, int((bz1 - oz) / step))
    o, n = old[z0:z1, x0:x1], new[z0:z1, x0:x1]
    H, W = o.shape

    print(f'窗口 px x[{x0}:{x1}] z[{z0}:{z1}]  ({W}x{H} = {W*step}x{H*step} 块)')
    for nm, a in (('修前', o), ('修后', n)):
        print(f'  {nm}: Y {a.min():.2f}..{a.max():.2f}  std {a.std():.3f}  高差 {a.max()-a.min():.2f}')

    pad, head, gap, caph, foot = 36, 150, 24, 54, 56
    Wp = pad * 2 + W * 2 + gap
    Hp = head + H + caph + foot
    P = Image.new('RGB', (Wp, Hp), '#f4f3ed')
    d = ImageDraw.Draw(P)
    f1 = ImageFont.truetype(FONT, 30); f2 = ImageFont.truetype(FONT, 17)
    d.text((pad, 16), f'晕斑位置 A/B 特写（{W*step}×{H*step} 块，1 像素 = {step} 块）',
           font=f1, fill='#25261f')
    d.text((pad, 60), f'块 x[{bx0:.0f}..{bx1:.0f}] z[{bz0:.0f}..{bz1:.0f}]｜海平面 Y=63',
           font=f2, fill='#5d594c')
    d.text((pad, 82), f'修前 Y {o.min():.1f}–{o.max():.1f}（高差 {o.max()-o.min():.1f}）'
                      f'｜修后 Y {n.min():.1f}–{n.max():.1f}（高差 {n.max()-n.min():.1f}）',
           font=f2, fill='#7a3a20')
    d.text((pad, 108), '判读：修前若整片近乎同色 = 假平台；修后应出现连续深浅层次',
           font=f2, fill='#5d594c')

    P.paste(Image.fromarray(shade(o, step)).resize((W, H), Image.NEAREST), (pad, head))
    P.paste(Image.fromarray(shade(n, step)).resize((W, H), Image.NEAREST), (pad + W + gap, head))
    d.text((pad, head + H + 6), f'修前（std {o.std():.2f}）', font=ImageFont.truetype(FONT, 19), fill='#25261f')
    d.text((pad + W + gap, head + H + 6), f'修后（std {n.std():.2f}）',
           font=ImageFont.truetype(FONT, 19), fill='#25261f')

    out = newdir / 'blotch-ab.png'
    P.save(out, optimize=True); print(out)

    # ---------- 剖面 + 全局陆高分布 ----------
    fig_w, fig_h = 1150, 330
    P2 = Image.new('RGB', (fig_w + 90, fig_h * 2 + 220), '#f4f3ed')
    d2 = ImageDraw.Draw(P2)
    d2.text((30, 12), '左：穿晕斑的剖面（同一行）　右：全图陆地高度累积分布',
            font=ImageFont.truetype(FONT, 24), fill='#25261f')
    d2.text((30, 46), f'灰虚线 = 海平面 63 格。修前的平台应是一条完全平直的线',
            font=f2, fill='#5d594c')

    # 剖面
    ox2, oy2, pw, ph = 80, 90, 520, 300
    d2.rectangle((ox2, oy2, ox2 + pw, oy2 + ph), outline='#c8c2ae', fill='#fbfaf6')
    row = H // 2
    lo, hi = min(o[row].min(), n[row].min()) - 3, max(o[row].max(), n[row].max()) + 3
    ypx = lambda v: oy2 + ph - (v - lo) / (hi - lo) * ph
    for v, c, lbl in ((SEA, '#8899aa', '海平面 63'),):
        yy = ypx(v)
        for xx in range(ox2, ox2 + pw, 12):
            d2.line((xx, yy, xx + 6, yy), fill=c, width=1)
        d2.text((ox2 + 6, yy - 15), lbl, font=f2, fill=c)
    for arr, c in ((o[row], '#b8654a'), (n[row], '#2c6e9e')):
        pts = [(ox2 + i * pw / max(1, W - 1), ypx(v)) for i, v in enumerate(arr)]
        d2.line(pts, fill=c, width=2)
    d2.text((30, fig_h + 100), '砖红 = 修前　深蓝 = 修后', font=ImageFont.truetype(FONT, 16), fill='#25261f')

    # 累积分布
    ox3, oy3 = 700, 90
    d2.rectangle((ox3, oy3, ox3 + 440, oy3 + ph), outline='#c8c2ae', fill='#fbfaf6')
    for arr, c in ((old[old >= SEA], '#b8654a'), (new[new >= SEA], '#2c6e9e')):
        v = np.sort(arr)
        qs = np.linspace(0, 1, 200)
        ys = np.percentile(v, qs * 100)
        pts = [(ox3 + q * 440, oy3 + ph - (y - 60) / 300 * ph) for q, y in zip(qs, ys)]
        d2.line(pts, fill=c, width=2)
    yy = oy3 + ph - (SEA - 60) / 300 * ph
    d2.line((ox3, yy, ox3 + 440, yy), fill='#8899aa', width=1)
    d2.text((ox3 + 6, yy - 15), '海平面', font=f2, fill='#8899aa')
    for frac, lbl in ((0.25, 'P25'), (0.5, 'P50'), (0.75, 'P75')):
        xx = ox3 + frac * 440
        d2.line((xx, oy3 + ph, xx, oy3 + ph + 5), fill='#25261f', width=1)
        d2.text((xx - 12, oy3 + ph + 8), lbl, font=f2, fill='#5d594c')
    d2.text((ox3, fig_h + 100),
            f'修前 P50={np.percentile(old[old>=SEA],50):.0f}　修后 P50={np.percentile(new[new>=SEA],50):.0f}',
            font=ImageFont.truetype(FONT, 16), fill='#25261f')
    d2.text((ox3, fig_h + 124), '（0–300 Y 范围）', font=f2, fill='#5d594c')

    out2 = newdir / 'blotch-profile.png'
    P2.save(out2, optimize=True); print(out2)


if __name__ == '__main__':
    main()
