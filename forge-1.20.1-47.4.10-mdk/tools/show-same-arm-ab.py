"""同一臂、两个目录的严格 A/B（单一变量）。

`render-islands-big.py` 固定对比 left/compose-coastal vs right/compose-coastal-islands，
会把"臂差异"和"目录差异"混在一起。本脚本只比同一个臂名在两目录间的差别。

用法：python tools/show-same-arm-ab.py <dirA> <dirB> <arm> <size> <step> <labelA> <labelB> [副标题]
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


def diff_img(a, b, step):
    d = b - a
    amp = max(float(np.percentile(np.abs(d), 99)), 1.0)
    t = np.clip(np.abs(d) / amp, 0, 1)
    base = np.full(d.shape + (3,), 244.0)
    # 抬高 = 暖色（红橙），压低 = 冷色（蓝）
    # 注意：从 244 的白底【减去】颜色才是"染上颜色"，
    #   减去 warm(255,150,40) ⇒ (0,94,204) 是蓝；
    #   减去 cool(40,150,255) ⇒ (204,94,0) 是橙。
    # 所以这里用加法混合，避免命名与显示相反。
    warm = np.stack([255 * t, 130 * t, 30 * t], axis=-1)     # 红橙
    cool = np.stack([40 * t, 140 * t, 255 * t], axis=-1)     # 蓝
    rgb = np.where((d > 0)[..., None], base - 255 + warm, base)   # 抬高：白底压向暖色
    rgb = np.where((d < 0)[..., None], base - 255 + cool, rgb)   # 压低：白底压向冷色
    # 未变区保持浅灰
    rgb = np.where((np.abs(d) <= 1e-6)[..., None], base, rgb)
    gz, gx = np.gradient(d, step)
    lit = np.clip((-gx * LIGHT[0] - gz * LIGHT[1] + LIGHT[2])
                  / np.sqrt(gx * gx + gz * gz + 1), 0, 1)
    return np.uint8(np.clip(rgb * (0.6 + 0.4 * lit)[..., None], 0, 255))


def main():
    da, db = Path(sys.argv[1]), Path(sys.argv[2])
    arm = sys.argv[3]
    size, step = int(sys.argv[4]), int(sys.argv[5])
    la, lb = sys.argv[6], sys.argv[7]
    sub = sys.argv[8] if len(sys.argv) > 8 else ''

    a = np.fromfile(da / f'{arm}.f64be.raw', dtype='>f8').reshape(size, size)
    b = np.fromfile(db / f'{arm}.f64be.raw', dtype='>f8').reshape(size, size)
    d = b - a

    print(f'arm = {arm}   size={size} step={step}  cover={size*step}')
    print(f'  {la}: Y {a.min():.2f}..{a.max():.2f}  relief {a.max()-a.min():.2f}')
    print(f'  {lb}: Y {b.min():.2f}..{b.max():.2f}  relief {b.max()-b.min():.2f}')
    print(f'  identical {100*(a==b).mean():.2f}%   mean|Δ| {np.abs(d).mean():.2f}   '
          f'max|Δ| {np.abs(d).max():.2f}')

    tw = 660
    pad, head, gap, caph, foot = 40, 150, 26, 56, 56
    W = pad * 2 + tw * 3 + gap * 2
    H = head + tw + caph + foot
    P = Image.new('RGB', (W, H), '#f4f3ed')
    dd = ImageDraw.Draw(P)
    f1 = ImageFont.truetype(FONT, 34)
    f2 = ImageFont.truetype(FONT, 18)
    f3 = ImageFont.truetype(FONT, 20)
    dd.text((pad, 18), f'单一变量 A/B：{arm}（{size*step}×{size*step} 块，1 像素 = {step} 块）',
            font=f1, fill='#25261f')
    dd.text((pad, 66), f'{la}  →  {lb}', font=f2, fill='#5d594c')
    dd.text((pad, 90), f'逐像素相同 {100*(a==b).mean():.1f}%｜mean|Δ| {np.abs(d).mean():.2f} 格'
                       f'｜max|Δ| {np.abs(d).max():.2f} 格', font=f2, fill='#7a3a20')
    dd.text((pad, 114), sub, font=f2, fill='#5d594c')

    for i, (arr, title, note) in enumerate([
        (a, la, f'Y {a.min():.0f}–{a.max():.0f}｜relief {a.max()-a.min():.0f}'),
        (b, lb, f'Y {b.min():.0f}–{b.max():.0f}｜relief {b.max()-b.min():.0f}'),
        (None, '差值（右 − 左）', f'暖=抬高 冷=压低｜色标 ±{max(float(np.percentile(np.abs(d),99)),1):.0f} 格'),
    ]):
        x = pad + i * (tw + gap)
        img = shade(arr, step) if arr is not None else diff_img(a, b, step)
        P.paste(Image.fromarray(img).resize((tw, tw), Image.LANCZOS), (x, head))
        dd.text((x, head + tw + 6), title, font=f3, fill='#25261f')
        dd.text((x, head + tw + 30), note, font=f2, fill='#5d594c')

    out = db / f'same-arm-ab-{arm}.png'
    P.save(out, optimize=True)
    print(out)


if __name__ == '__main__':
    main()
