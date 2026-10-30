"""裁出 big-islands.png 里用户圈出的那块，1:1 特写 + 高度剖面。

判据（回答"是不是触顶"）：
  · 色带里该处的位置（相对全图最高点）
  · 剖面里有没有出现"贴顶平线"
  · 与周围地形的落差

用法：python tools/show-flat-spot.py <outDir> <size> <step> [cx0 cz0 cx1 cz1]
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
    root = Path(sys.argv[1])
    size = int(sys.argv[2])
    step = int(sys.argv[3])
    # 默认窗口：用户圈的左上那块，留足边距
    px0 = int(sys.argv[4]) if len(sys.argv) > 4 else 20
    pz0 = int(sys.argv[5]) if len(sys.argv) > 5 else 5
    px1 = int(sys.argv[6]) if len(sys.argv) > 6 else 290
    pz1 = int(sys.argv[7]) if len(sys.argv) > 7 else 175

    h = np.fromfile(root / 'compose-coastal-islands.f64be.raw', dtype='>f8').reshape(size, size)
    ox, oz = -3640.0, -3363.0
    sub = h[pz0:pz1, px0:px1]
    H, W = sub.shape

    print(f'窗口 px x[{px0}:{px1}] z[{pz0}:{pz1}]  ({W}x{H} 采样点 = {W*step}x{H*step} 块)')
    print(f'  块坐标 x[{ox+px0*step:.0f}..{ox+px1*step:.0f}]  z[{oz+pz0*step:.0f}..{oz+pz1*step:.0f}]')
    print(f'  Y {sub.min():.2f} .. {sub.max():.2f}   mean {sub.mean():.2f}  std {sub.std():.2f}')
    gz, gx = np.gradient(sub, step)
    sl = np.hypot(gx, gz)
    print(f'  坡度 P50 {np.percentile(sl,50):.4f}  P99 {np.percentile(sl,99):.4f}  max {sl.max():.4f}')
    print(f'  全图最高 {h.max():.2f}  ⇒ 本窗口最高只占 {100*sub.max()/h.max():.1f}% 的高度')
    print(f'  本窗口均值在全图的百分位: {(h < sub.mean()).mean()*100:.1f}%')

    # ---------- 图 1：1:1 特写 ----------
    tw = W
    pad, head, gap, caph, foot = 36, 130, 24, 50, 50
    Z = 4  # 标签列宽
    Wp = pad * 2 + tw + gap + 200
    Hp = head + H + caph + foot
    P = Image.new('RGB', (Wp, Hp), '#f4f3ed')
    d = ImageDraw.Draw(P)
    f1 = ImageFont.truetype(FONT, 28); f2 = ImageFont.truetype(FONT, 16); f3 = ImageFont.truetype(FONT, 18)

    d.text((pad, 16), f'圈出位置 1:1 特写（{W*step} × {H*step} 块，1 像素 = {step} 块）',
           font=f1, fill='#25261f')
    d.text((pad, 56), f'块坐标 x[{ox+px0*step:.0f}..{ox+px1*step:.0f}] z[{oz+pz0*step:.0f}..{oz+pz1*step:.0f}]'
                      f'｜Y {sub.min():.1f}–{sub.max():.1f}｜std {sub.std():.2f}',
           font=f2, fill='#5d594c')
    d.text((pad, 78), f'全图最高 {h.max():.0f} 格，此处最高 {sub.max():.0f} 格'
                      f'（仅 {100*sub.max()/h.max():.0f}%）⇒ 不是触顶',
           font=f2, fill='#3a6b3a')
    d.text((pad, 100), '色带与 big-islands.png 完全相同；右侧是同一窗口的高度色阶条',
           font=f2, fill='#5d594c')

    P.paste(Image.fromarray(shade(sub, step)), (pad, head))

    # 右侧色阶条
    cbx = pad + tw + gap
    cbh = H
    lo, hi = SEA - 10, h.max()
    grad = np.linspace(hi, lo, cbh).reshape(-1, 1)
    grad = np.repeat(grad, 46, axis=1)
    P.paste(Image.fromarray(shade(grad, 1)), (cbx, head))
    for v in (int(lo), 100, 150, 200, int(hi)):
        if lo <= v <= hi:
            yy = head + int((hi - v) / (hi - lo) * cbh)
            d.line((cbx + 46, yy, cbx + 52, yy), fill='#25261f', width=2)
            d.text((cbx + 56, yy - 8), f'{v}', font=f2, fill='#25261f')
    # 标出本窗口的高度范围
    for v, c in ((sub.min(), '#2c6e9e'), (sub.max(), '#b8654a')):
        yy = head + int((hi - v) / (hi - lo) * cbh)
        d.line((cbx - 8, yy, cbx + 46, yy), fill=c, width=2)
        d.text((cbx + 56, yy + 6), f'本窗 {"min" if c=="#2c6e9e" else "max"} {v:.0f}',
               font=f2, fill=c)

    out = root / 'flat-spot.png'
    P.save(out, optimize=True); print(out)

    # ---------- 图 2：穿过该窗口的两条剖面 ----------
    fig_w, fig_h = 1150, 400
    P2 = Image.new('RGB', (fig_w + 100, fig_h + 220), '#f4f3ed')
    d2 = ImageDraw.Draw(P2)
    d2.text((30, 12), '穿过该窗口的横切面（东—西 / 南—北）', font=ImageFont.truetype(FONT, 26),
            fill='#25261f')
    d2.text((30, 48), '纵轴 = 绝对 Y；灰虚线 = 海平面 63；红虚线 = 全图最高点。'
                      '若"触顶"，曲线会贴着某条水平线走。',
            font=f2, fill='#5d594c')

    ox2, oy2, pw, ph = 80, 90, fig_w - 60, fig_h - 30
    ylo, yhi = SEA - 15, max(h.max(), sub.max()) + 5
    d2.rectangle((ox2, oy2, ox2 + pw, oy2 + ph), outline='#c8c2ae', fill='#fbfaf6')

    def ypx(v):
        return oy2 + ph - (v - ylo) / (yhi - ylo) * ph

    for v, c, lbl in ((SEA, '#8899aa', '海平面 63'),
                      (h.max(), '#c0392b', f'全图最高 {h.max():.0f}'),
                      (sub.max(), '#b8654a', f'本窗最高 {sub.max():.0f}')):
        yy = ypx(v)
        for xx in range(ox2, ox2 + pw, 12):
            d2.line((xx, yy, xx + 6, yy), fill=c, width=1)
        d2.text((ox2 + pw - 150, yy - 14), lbl, font=f2, fill=c)

    mid_z = H // 2
    mid_x = W // 2
    for row, c, lbl in ((sub[mid_z, :], '#2c6e9e', '东西向（中间行）'),
                        (sub[:, mid_x], '#3a6b3a', '南北向（中间列）')):
        pts = [(ox2 + i * pw / max(1, len(row) - 1), ypx(v)) for i, v in enumerate(row)]
        d2.line(pts, fill=c, width=2)

    d2.text((30, fig_h + 100), '深蓝 = 东西向　深绿 = 南北向', font=f3, fill='#25261f')
    d2.text((30, fig_h + 128), f'横轴：{W*step} 块　纵轴：Y {ylo:.0f}–{yhi:.0f}',
            font=f2, fill='#5d594c')
    d2.text((30, fig_h + 152), f'两方向高差：东西 {sub[mid_z,:].max()-sub[mid_z,:].min():.1f} 格，'
                               f'南北 {sub[:,mid_x].max()-sub[:,mid_x].min():.1f} 格',
            font=f2, fill='#7a3a20')

    out2 = root / 'flat-spot-profile.png'
    P2.save(out2, optimize=True); print(out2)


if __name__ == '__main__':
    main()
