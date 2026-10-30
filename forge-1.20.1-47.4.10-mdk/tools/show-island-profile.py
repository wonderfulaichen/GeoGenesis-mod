"""群岛剖面 + 岛内起伏统计。

回答两个问题：
  ① 岛的高度剖面正常吗（是不是平滑鼓包 / 平台 / 尖刺）
  ② 岛的内部起伏与陆地丘陵同量级吗（岛身用的是同一条 HILLS_1_GEO 配方）

用法：python tools/show-island-profile.py <outDir> [岛数量]
"""
import json
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont
from scipy import ndimage as ndi

FONT = 'C:/Windows/Fonts/msyh.ttc'
SEA = 63.0
TYPE_HILLS = 2


# ---------- 地形着色（与 big 图同色带） ----------
ANCHORS = np.array([-64, -40, -12, 62, 66, 76, 110, 170, 240, 320])
COLORS = np.array([
    [16, 26, 40], [24, 40, 62], [40, 66, 92], [58, 92, 116],
    [214, 205, 168], [96, 122, 78], [132, 142, 88],
    [168, 160, 128], [196, 190, 172], [238, 236, 228],
])
LIGHT = np.array([-0.5, -0.35, 1.0])
LIGHT /= np.linalg.norm(LIGHT)


def render(h, step):
    gz, gx = np.gradient(h, step)
    lit = np.clip((-gx * LIGHT[0] - gz * LIGHT[1] + LIGHT[2])
                  / np.sqrt(gx * gx + gz * gz + 1), 0, 1)
    rgb = np.stack([np.interp(h, ANCHORS, COLORS[:, c]) for c in range(3)], axis=-1)
    shade = np.where(h < SEA, 0.72 + 0.28 * lit, 0.28 + 0.80 * lit)
    return Image.fromarray(np.uint8(np.clip(rgb * shade[..., None], 0, 255)))


def main():
    root = Path(sys.argv[1])
    top_n = int(sys.argv[2]) if len(sys.argv) > 2 else 4

    geo = json.loads((root / 'compose-geometry.json').read_text(encoding='utf-8'))
    size, step = geo['size'], geo['stepBlocks']

    new = np.fromfile(root / 'compose-coastal-islands.f64be.raw', dtype='>f8').reshape(size, size)
    base = np.fromfile(root / 'compose-coastal.f64be.raw', dtype='>f8').reshape(size, size)
    types = np.fromfile(root / 'types-compose-coastal-islands.u8', dtype=np.uint8).reshape(size, size)

    # ---------- 岛体识别 ----------
    island = (new >= SEA) & (base < SEA)
    lab, n = ndi.label(island, structure=np.ones((3, 3)))
    sizes = np.bincount(lab.ravel())
    order = [i for i in range(1, n + 1) if sizes[i] > 0]
    order.sort(key=lambda i: -sizes[i])

    print('=== 岛体起伏（前 %d 大岛）===' % top_n)
    print(f'{"#":>2s} {"面积px":>7s} {"等效边长":>8s} {"峰值Y":>7s} {"P10Y":>7s} '
          f'{"起伏":>6s} {"出水高":>6s} {"峰/面积比":>9s}')
    picks = []
    for rank, i in enumerate(order[:top_n], 1):
        m = lab == i
        zz, xx = np.where(m)
        h = new[m]
        peak = float(h.max())
        p10 = float(np.percentile(h, 10))
        relief = peak - p10
        side = float(np.sqrt(sizes[i]) * step)
        picks.append((i, int(zz.mean()), int(xx.mean())))
        print(f'{rank:2d} {sizes[i]:7d} {side:8.0f} {peak:7.1f} {p10:7.1f} '
              f'{relief:6.1f} {peak - SEA:6.1f} {relief / max(side, 1):9.4f}')

    # ---------- 对照：同等面积窗口里的陆地丘陵起伏 ----------
    hill = (base >= SEA) & (types == TYPE_HILLS)
    cand = []
    for zz in range(0, size - 64, 16):
        for xx in range(0, size - 64, 16):
            w = hill[zz:zz + 64, xx:xx + 64]
            if w.mean() > 0.95:
                cand.append((zz, xx))
    if cand:
        rel = []
        for zz, xx in cand[:400]:
            w = base[zz:zz + 64, xx:xx + 64]
            rel.append(float(w.max() - np.percentile(w, 10)))
        rel = np.array(rel)
        print(f'\n=== 陆地丘陵同面积窗口（64px = {64 * step} 块，n={len(rel)}）===')
        print(f'起伏 P50 {np.percentile(rel, 50):.1f}  P90 {np.percentile(rel, 90):.1f}  '
              f'min {rel.min():.1f}  max {rel.max():.1f} 格')

    # ---------- 剖面图 ----------
    W, H = 1860, 210
    tile = 330
    pad = 30
    panel = Image.new('RGB', (pad * 2 + tile + 40 + (W - tile - 130), pad + top_n * H + 80), '#f4f3ed')
    draw = ImageDraw.Draw(panel)
    f_title = ImageFont.truetype(FONT, 34)
    f_text = ImageFont.truetype(FONT, 20)
    f_small = ImageFont.truetype(FONT, 16)

    draw.text((pad, 14), '群岛剖面（穿过每个岛最高点的横切面，纵轴为绝对 Y 格）',
              font=f_title, fill='#25261f')
    draw.text((pad, 56), '黑粗线 = 叠加群岛后的地形　灰细线 = 同位置改前地形　蓝线 = 海平面 Y=63',
              font=f_text, fill='#5d594c')

    prof_x = pad + tile + 40
    prof_w = W - prof_x - pad
    for k, (i, z0, x0) in enumerate(picks):
        y0 = 92 + k * H
        # 左：1:1 裁剪
        half = tile // 2 // 2
        zc = int(np.clip(z0, half, size - half))
        xc = int(np.clip(x0, half, size - half))
        crop = new[zc - half:zc + half, xc - half:xc + half]
        panel.paste(render(crop, step).resize((tile, tile), Image.NEAREST), (pad, y0))

        # 右：穿过岛内最高点的剖面
        m = lab == i
        hh = np.where(m, new, -1e9)
        zi, xi = np.unravel_index(int(np.argmax(hh)), hh.shape)

        # 剖面线必须完全落在这座岛内：取该行/列上属于它的最长连续区间，
        # 这样不会把同一线上经过的其它岛或邻近陆地画进来。
        def longest_run(mask1d):
            best = (0, 0)
            start = None
            for i2, v in enumerate(mask1d):
                if v and start is None:
                    start = i2
                elif not v and start is not None:
                    if i2 - start > best[1] - best[0]:
                        best = (start, i2 - 1)
                    start = None
            if start is not None and len(mask1d) - start > best[1] - best[0]:
                best = (start, len(mask1d) - 1)
            return best

        col_run = longest_run(m[:, xi])
        row_run = longest_run(m[zi])
        col_len = col_run[1] - col_run[0]
        row_len = row_run[1] - row_run[0]
        if col_len >= row_len:
            line_new = new[:, xi]
            line_old = base[:, xi]
            lo_i = max(0, col_run[0] - 20)
            hi_i = min(size - 1, col_run[1] + 20)
            axis_name = f'列（x={xi * step + geo["originBlocks"][0]:.0f}）'
        else:
            line_new = new[zi]
            line_old = base[zi]
            lo_i = max(0, row_run[0] - 20)
            hi_i = min(size - 1, row_run[1] + 20)
            axis_name = f'行（z={zi * step + geo["originBlocks"][1]:.0f}）'

        seg_new = line_new[lo_i:hi_i + 1]
        seg_old = line_old[lo_i:hi_i + 1]
        lo = min(seg_new.min(), seg_old.min()) - 6
        hi = max(seg_new.max(), seg_old.max()) + 6
        span = max(hi - lo, 1e-6)
        ph = tile
        py0 = y0
        draw.rectangle((prof_x, py0, prof_x + prof_w, py0 + ph), fill='#fbfaf5',
                       outline='#d8d6cc')

        def py(v):
            return py0 + ph - 14 - (ph - 34) * (v - lo) / span

        # 网格 + Y 轴刻度
        for frac in np.linspace(0, 1, 4):
            v = lo + span * frac
            draw.line((prof_x, py(v), prof_x + prof_w, py(v)), fill='#e8e6dd')
            draw.text((prof_x + 4, py(v) - 9), f'{v:.0f}', font=f_small, fill='#8a8676')
        # 海平面
        if lo < SEA < hi:
            draw.line((prof_x, py(SEA), prof_x + prof_w, py(SEA)), fill='#4a7fb5', width=2)

        nn = len(seg_new)
        xs = [prof_x + 40 + (prof_w - 80) * t / max(nn - 1, 1) for t in range(nn)]
        draw.line(list(zip(xs, [py(v) for v in seg_old])), fill='#a9a496', width=1)
        draw.line(list(zip(xs, [py(v) for v in seg_new])), fill='#2f2c24', width=2)

        peak = float(seg_new.max())
        draw.text((prof_x + 40, y0 + 2),
                  f'岛 #{k + 1}　{axis_name}　峰 {peak:.1f} 格（水面以上 {peak - SEA:.0f}）'
                  f'　宽 {sizes[i] ** 0.5 * step:.0f} 块', font=f_text, fill='#25261f')
        draw.text((prof_x + 40, y0 + 24),
                  f'剖面跨度 {int((hi_i - lo_i + 1)) * step} 块', font=f_small, fill='#7a7668')

    out = root / 'island-profiles.png'
    panel.save(out, optimize=True)
    print(out)


if __name__ == '__main__':
    main()
