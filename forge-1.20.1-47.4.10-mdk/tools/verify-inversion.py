"""反转验证：同窗口三联图 + 数值断言。

1) MOUND-RAW   原始圆包场
2) MOUND-INV   反转后圆包场
3) RIDGED-NOGROOVE  实际出图臂（strength=0）

判据：
  corr(RAW, INV) == -1（严格互补）
  raw 的最高点在 INV 里必须是最低点
  三联图人眼对照：raw 的 tan 高斑在 inv / NOGROOVE 里应变成绿色低斑
"""
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

FONT = 'C:/Windows/Fonts/msyh.ttc'
STEP = 5


def main():
    root = Path(sys.argv[1])
    size = int(sys.argv[2]) if len(sys.argv) > 2 else 701

    def load(name):
        return np.fromfile(root / f'{name}.f64be.raw', dtype='>f8').reshape(size, size)

    raw = load('MOUND-RAW')
    inv = load('MOUND-INV')
    nog = load('RIDGED-NOGROOVE')

    # ---- 数值断言 ----
    c = float(np.corrcoef(raw.ravel(), inv.ravel())[0, 1])
    hi = np.unravel_index(int(np.argmax(raw)), raw.shape)
    lo = np.unravel_index(int(np.argmin(raw)), raw.shape)
    ok_corr = abs(c + 1.0) < 1e-6
    ok_flip = inv[hi] < inv[lo]
    print(f'corr(RAW, INV)   = {c:.6f}   {"PASS" if ok_corr else "FAIL"}（应 -1.0）')
    print(f'RAW 最高点 {raw[hi]:.2f} → INV {inv[hi]:.2f}；'
          f'RAW 最低点 {raw[lo]:.2f} → INV {inv[lo]:.2f}   {"PASS" if ok_flip else "FAIL"}（高变低、低变高）')
    print(f'NOGROOVE 与 INV 相同? max|diff| = '
          f'{np.abs(nog - inv).max():.6f}（出图链用的就是 INV 时应≈0，但出图过了 heightFromE，量纲不同只看形态相关）')
    c2 = float(np.corrcoef(nog.ravel(), inv.ravel())[0, 1])
    print(f'corr(NOOG, INV)  = {c2:.6f}   {"PASS" if abs(abs(c2) - 1) < 1e-6 else "FAIL"}（应 ±1，出图臂单调映射 INV）')

    # ---- 三联图（同色阶同窗口）----
    win = size
    x0 = z0 = 0
    tiles = [('RAW 原始圆包场', raw), ('INV 反转后圆包场', inv), ('NOGROOVE 出图臂', nog)]
    lo = min(float(t[1][z0:z0 + win, x0:x0 + win].min()) for t in tiles)
    hi2 = max(float(t[1][z0:z0 + win, x0:x0 + win].max()) for t in tiles)

    scale = 1
    tile = win * scale
    pad, gap, head = 30, 26, 110
    panel = Image.new('RGB', (pad * 2 + tile * 3 + gap * 2, head + tile + 70), '#f4f3ed')
    draw = ImageDraw.Draw(panel)
    t1 = ImageFont.truetype(FONT, 30)
    t2 = ImageFont.truetype(FONT, 17)

    draw.text((pad, 14), '反转验证：同窗口三联（同色阶 Y %.0f–%.0f，暗=低 亮=高）' % (lo, hi2),
              font=t1, fill='#25261f')
    draw.text((pad, 54),
              '判据：RAW 的亮斑在 INV / NOGROOVE 里必须变成暗斑；corr 应为 -1.000000',
              font=t2, fill='#5d594c')

    ANCHORS = np.array([-64, 45, 63, 70, 80, 110, 150, 200, 260, 320])
    COLORS = np.array([
        [39, 57, 67], [65, 83, 76], [83, 104, 79], [120, 140, 90],
        [150, 165, 110], [172, 161, 130], [180, 168, 138],
        [196, 188, 166], [216, 210, 196], [238, 236, 228]])

    for i, (label, h) in enumerate(tiles):
        crop = h[z0:z0 + win, x0:x0 + win]
        gz, gx = np.gradient(crop, STEP)
        lit = np.clip((-gx * -0.4472 - gz * -0.3131 + 0.8321)
                      / np.sqrt(gx * gx + gz * gz + 1), 0, 1)
        t = np.clip((crop - lo) / max(hi2 - lo, 1e-6), 0, 1)
        pos = t * (len(COLORS) - 1)
        rgb = np.stack([np.interp(pos, np.arange(len(COLORS)), COLORS[:, c]) for c in range(3)], axis=-1)
        img = Image.fromarray(np.uint8(np.clip(rgb * (0.3 + 0.8 * lit)[..., None], 0, 255)))
        px = pad + i * (tile + gap)
        panel.paste(img.resize((tile, tile), Image.NEAREST), (px, head))
        draw.text((px, head + tile + 8), label, font=t2, fill='#25261f')

    out = root / 'verify-inversion.png'
    panel.save(out, optimize=True)
    print(out)


if __name__ == '__main__':
    main()
