"""参考项目算法 vs 我们算法：同一份地形数据的并排出图（★ 2026-11-08）。

目的
----
用户要求"带我把参考项目那套完整跑起来做逐项对比，把差异点列全了再动手"。
本脚本的作用是<b>把差异可视化并可量化</b>，而不是靠肉眼印象。

做法
----
读取同一份 LOD 采样数据（真实地形），分别用：
  ① 参考项目算法（SiteTerrainRenderer 逐行复刻）
  ② 我们的算法（SandboxRenderer 现状复刻）
渲染同一视角、同一尺寸的图，并排输出，并用像素差异统计指出差别在哪。

两套算法的差异点（脚本内逐项开关，便于隔离定位）
  ref  vs  ours
  - span:      highest-base  vs  highest-base        （已一致）
  - 柱聚类:     多数群系      vs  多数群系             （已一致）
  - 侧墙判据:   top>low       vs  top-low>min_wall    （★ 我们多了像素门槛）
  - 光照:      yardsite公式   vs  SandboxShade 自适应  （★ 不同）
  - 边缘渐隐:   恒 1.0        vs  smoothstep 3 格     （★ 我们多了）
  - 地板:      有              vs  有                  （一致）
  - 水面:      BiomePalette   vs  粗层水位 + 水色     （★ 我们多了粗层水位）

用法
----
    python tools/compare_reference_vs_ours.py <lod_tsv> <out_prefix>
"""
import io
import math
import os
import sys

import numpy as np
from PIL import Image

# ---- 参考项目常数（SiteTerrainRenderer 第 47-77 行）----
REF_RELIEF = 0.40
REF_WATER_SINK = 0.05
REF_Z_BASE = 200.0
REF_DEPTH_SCALE = 110.0
REF_FADE_MS = 520
REF_FADE_FLOOR = 0.25
REF_BUCKETS = 4096
REF_PITCH_MIN, REF_PITCH_MAX = 0.22, 1.40
REF_DEFAULT_PITCH, REF_DEFAULT_YAW = 0.95, 0.65
REF_PLATE = 0xF2171A22
REF_WATER_SHALLOW = 0x6FA8D6
REF_WATER_DEEP = 0x1C3F6B
REF_LIGHT_MIN, REF_LIGHT_RANGE = 0.55, 0.75

PER_COLUMN = 2


def load(path):
    rows = []
    with io.open(path, encoding='utf-8') as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith('#'):
                continue
            p = line.split()
            rows.append({
                'gx': int(p[0]), 'gz': int(p[1]),
                'h': float(p[2]), 'e': float(p[3]),
                'biome': int(p[4]), 'terr': int(p[5]),
                'snow': p[6] == '1',
                'disch': float(p[12]) if len(p) > 12 else 0.0,
                'water': (float('nan') if len(p) <= 13 or p[13] == 'nan'
                          else float(p[13])),
            })
    if not rows:
        raise SystemExit('no data')
    n = max(max(r['gx'], r['gz']) for r in rows) + 1
    H = np.zeros((n, n))
    E = np.zeros((n, n))
    B = np.zeros((n, n), dtype=int)
    T = np.zeros((n, n), dtype=int)
    S = np.zeros((n, n), dtype=bool)
    W = np.full((n, n), np.nan)
    for r in rows:
        i, j = r['gx'], r['gz']
        H[i, j] = r['h']
        E[i, j] = r['e']
        B[i, j] = r['biome']
        T[i, j] = r['terr']
        S[i, j] = r['snow']
        W[i, j] = r['water']
    return H, E, B, T, S, W


def build_rotation(yaw, pitch):
    def rz(a):
        c, s = math.cos(a), math.sin(a)
        return np.array([[c, -s, 0.0], [s, c, 0.0], [0.0, 0.0, 1.0]])

    def rx(a):
        c, s = math.cos(a), math.sin(a)
        return np.array([[1.0, 0.0, 0.0], [0.0, c, -s], [0.0, s, c]])

    m = rz(0.0) @ rx(pitch) @ rz(yaw)
    raw = m.T.reshape(-1)
    r = np.empty(9)
    r[0], r[1], r[2] = raw[0], raw[3], raw[6]
    r[3], r[4], r[5] = raw[1], raw[4], raw[7]
    r[6], r[7], r[8] = raw[2], raw[5], raw[8]
    return r


def model(fine, cell):
    return 0.0 if fine <= 1 else cell / (fine - 1) * 2.0 - 1.0


# ---- 参考项目 BiomePalette.baseColor 的语义（按 WhittakerType 映射）----
WHITTAKER_RGB = [0xB8C8C0, 0xB8C8C0, 0x3D6B32, 0x6FAE4A, 0x3D6B32,
                 0x2E7A35, 0xD9C08A, 0xB39B4C, 0x3D6B32, 0x2E7A35]
SNOW_RGB = 0xE7F0F5
BEACH_RGB = 0xE2D3A0
TERRAIN_BEACH = 7


def int2rgb(v):
    return ((v >> 16) & 255, (v >> 8) & 255, v & 255)


def lerp3(a, b, t):
    return tuple((1 - t) * x + t * y for x, y in zip(int2rgb(a), int2rgb(b)))


def scale3(rgb, f):
    return tuple(min(255.0, max(0.0, c * f)) for c in rgb)


def render(H, E, B, T, S, W, *, variant, size=760, zoom=2.0,
           yaw=REF_DEFAULT_YAW, pitch=REF_DEFAULT_PITCH, sea=63.0):
    """variant='ref' = 参考项目算法；'ours' = 我们现状算法。"""
    n = H.shape[0]
    base = sea - 4.0

    # span：两边都用 highest-base（已对齐）
    surface_hi = np.where(E < 0.0, sea, H)
    span = max(60.0, float(surface_hi.max()) - base)

    # ---- 归一化高度 r ----
    rn = (np.where(E < 0.0, sea, H) - base) / span

    # ---- 光照 ----
    left = np.roll(rn, 1, axis=0); left[0] = rn[0]
    right = np.roll(rn, -1, axis=0); right[-1] = rn[-1]
    up = np.roll(rn, 1, axis=1); up[:, 0] = rn[:, 0]
    down = np.roll(rn, -1, axis=1); down[:, -1] = rn[:, -1]
    slope = (right - left) * 0.5 + (down - up) * 0.5
    water = E < 0.0

    if variant == 'ref':
        # 参考项目 paintBlock 第 553-568 行：分母含 step*HEIGHT_STRIDE
        # step = 窗口/细格；这里直接用 1（细格间距归一），gain=0.55
        light = np.clip(1.0 - slope * 0.55, REF_LIGHT_MIN, REF_LIGHT_MIN + REF_LIGHT_RANGE)
    else:
        p995 = float(np.quantile(np.abs(slope), 0.995))
        gain = 0.9 / p995 if p995 > 1e-9 else 0.5
        gain = min(60.0, max(0.5, gain))
        light = np.clip(1.0 - slope * gain, REF_LIGHT_MIN, REF_LIGHT_MIN + REF_LIGHT_RANGE)
    light = np.where(water, 1.0 + (light - 1.0) * 0.35, light)

    # ---- 柱 ----
    cc = (n + PER_COLUMN - 1) // PER_COLUMN
    tops = np.zeros(cc * cc)
    colc = np.zeros(cc * cc, dtype=object)
    colwater = np.zeros(cc * cc, dtype=bool)

    for cx in range(cc):
        for cz in range(cc):
            col = cx * cc + cz
            fx0, fz0 = cx * PER_COLUMN, cz * PER_COLUMN
            fx1 = min(n - 1, fx0 + PER_COLUMN - 1)
            fz1 = min(n - 1, fz0 + PER_COLUMN - 1)
            sy = H[fx0, fz0]

            has_w = False
            topy = sy
            if variant == 'ours':
                wy = W[fx0, fz0]
                if not np.isnan(wy) and wy > sy + 0.01:
                    topy = wy
                    has_w = True
            colwater[col] = has_w
            tops[col] = max((topy - base) / span, REF_WATER_SINK) * REF_RELIEF

            # 多数群系
            ids, cnts = [], []
            for dx in range(fx1 - fx0 + 1):
                for dz in range(fz1 - fz0 + 1):
                    b = B[fx0 + dx, fz0 + dz]
                    if b in ids:
                        cnts[ids.index(b)] += 1
                    else:
                        ids.append(b); cnts.append(1)
            maj = ids[cnts.index(max(cnts))]
            mfx = mfz = None
            for dx in range(fx1 - fx0 + 1):
                for dz in range(fz1 - fz0 + 1):
                    if B[fx0 + dx, fz0 + dz] == maj:
                        mfx, mfz = fx0 + dx, fz0 + dz
                        break
                if mfx is not None:
                    break

            i, j = mfx, mfz
            if E[i, j] < 0.0:
                depth = min(1.0, (sea - H[i, j]) / 30.0) if H[i, j] < sea else 0.0
                rgbv = lerp3(REF_WATER_SHALLOW, REF_WATER_DEEP, depth)
            elif has_w:
                d2 = min(1.0, (sea - topy) / 30.0)
                rgbv = lerp3(REF_WATER_SHALLOW, REF_WATER_DEEP, max(0.0, d2))
            elif S[i, j] or B[i, j] == 0:
                rgbv = int2rgb(SNOW_RGB)
            elif T[i, j] == TERRAIN_BEACH:
                rgbv = int2rgb(BEACH_RGB)
            else:
                rgbv = int2rgb(WHITTAKER_RGB[B[i, j] % len(WHITTAKER_RGB)])
            colc[col] = scale3(rgbv, light[i, j])

    r9 = build_rotation(yaw, pitch)
    scale = size * 0.46 * zoom
    ox = oy = size / 2.0

    def proj(mx, my, mz):
        sx = ox + scale * (r9[0] * mx + r9[1] * my + r9[2] * mz)
        sy = oy + scale * (r9[3] * mx + r9[4] * my + r9[5] * mz)
        dep = r9[6] * mx + r9[7] * my + r9[8] * mz
        return sx, sy, dep

    see_e, see_w = r9[6] > 0, r9[6] < 0
    see_s, see_n = r9[7] > 0, r9[7] < 0
    see_top = r9[8] > 0

    order = []
    for col in range(cc * cc):
        mx = model(cc, col // cc)
        my = model(cc, col % cc)
        order.append((r9[6] * mx + r9[7] * my + r9[8] * max(tops[col], 0.0), col))
    order.sort()

    img = np.zeros((size, size, 3), dtype=np.float64)
    img[:, :] = (0.09, 0.10, 0.13)

    if variant == 'ref':
        min_wall = 0.0            # 参考项目没有像素门槛
    else:
        min_wall = 1.0 / max(1e-3, scale)

    def quad(pts, color, alpha=1.0):
        xs = [p[0] for p in pts]; ys = [p[1] for p in pts]
        x0, x1 = max(0, int(min(xs))), min(size, int(max(xs)) + 1)
        y0, y1 = max(0, int(min(ys))), min(size, int(max(ys)) + 1)
        if x1 <= x0 or y1 <= y0:
            return
        px, py = np.meshgrid(np.arange(x0, x1) + 0.5, np.arange(y0, y1) + 0.5)
        for tri in ((pts[0], pts[1], pts[2]), (pts[0], pts[2], pts[3])):
            a, b, c = tri
            den = (b[1] - c[1]) * (a[0] - c[0]) + (c[0] - b[0]) * (a[1] - c[1])
            if abs(den) < 1e-12:
                continue
            l1 = ((b[1] - c[1]) * (px - c[0]) + (c[0] - b[0]) * (py - c[1])) / den
            l2 = ((c[1] - a[1]) * (px - c[0]) + (a[0] - c[0]) * (py - c[1])) / den
            l3 = 1.0 - l1 - l2
            ins = (l1 >= -0.001) & (l2 >= -0.001) & (l3 >= -0.001)
            if not ins.any():
                continue
            col01 = tuple(cn / 255.0 for cn in color)
            for k in range(3):
                if alpha >= 0.999:
                    img[y0:y1, x0:x1, k][ins] = col01[k]
                else:
                    img[y0:y1, x0:x1, k][ins] = (
                        img[y0:y1, x0:x1, k][ins] * (1 - alpha) + col01[k] * alpha)

    def mul(rgb01, m):
        return tuple(min(1.0, max(0.0, c * m)) for c in rgb01)

    for _, col in order:
        cx, cz = col // cc, col % cc
        top = tops[col]
        rgb01 = tuple(c / 255.0 for c in colc[col])

        alpha = 1.0
        if variant == 'ours':
            d = min(cx, cc - 1 - cx, cz, cc - 1 - cz)
            if d < 3:
                t = d / 3.0
                alpha = t * t * (3 - 2 * t)

        fx0, fz0 = cx * PER_COLUMN, cz * PER_COLUMN
        fx1 = min(n - 1, fx0 + PER_COLUMN - 1)
        fz1 = min(n - 1, fz0 + PER_COLUMN - 1)
        ccx, ccy = (fx0 + fx1) * 0.5, (fz0 + fz1) * 0.5
        hx, hy = (fx1 - fx0 + 1) * 0.5, (fz1 - fz0 + 1) * 0.5
        x0, x1 = model(n, ccx - hx), model(n, ccx + hx)
        y0, y1 = model(n, ccy - hy), model(n, ccy + hy)

        if see_top:
            quad([proj(x0, y0, top), proj(x1, y0, top),
                  proj(x1, y1, top), proj(x0, y1, top)], colc[col], alpha)
        if see_e:
            low = max(tops[col + cc], 0.0) if cx + 1 < cc else 0.0
            if top - low > min_wall:
                quad([proj(x1, y0, low), proj(x1, y1, low),
                      proj(x1, y1, top), proj(x1, y0, top)], colc[col], alpha)
        if see_w:
            low = max(tops[col - cc], 0.0) if cx > 0 else 0.0
            if top - low > min_wall:
                quad([proj(x0, y1, low), proj(x0, y0, low),
                      proj(x0, y0, top), proj(x0, y1, top)], colc[col], alpha)
        if see_s:
            low = max(tops[col + 1], 0.0) if cz + 1 < cc else 0.0
            if top - low > min_wall:
                quad([proj(x0, y1, low), proj(x1, y1, low),
                      proj(x1, y1, top), proj(x0, y1, top)], colc[col], alpha)
        if see_n:
            low = max(tops[col - 1], 0.0) if cz > 0 else 0.0
            if top - low > min_wall:
                quad([proj(x1, y0, low), proj(x0, y0, low),
                      proj(x0, y0, top), proj(x1, y0, top)], colc[col], alpha)

    return (img * 255).astype(np.uint8)


def main():
    if len(sys.argv) < 3:
        raise SystemExit(__doc__)
    src, prefix = sys.argv[1], sys.argv[2]
    H, E, B, T, S, W = load(src)

    a = render(H, E, B, T, S, W, variant='ref')
    b = render(H, E, B, T, S, W, variant='ours')
    side = np.concatenate([a, b], axis=1)

    os.makedirs(os.path.dirname(prefix) or '.', exist_ok=True)
    Image.fromarray(a).save(prefix + '-ref.png')
    Image.fromarray(b).save(prefix + '-ours.png')
    Image.fromarray(side).save(prefix + '-side.png')

    # 差异统计
    diff = np.abs(a.astype(int) - b.astype(int)).mean(axis=2)
    print('参考 vs 我们（同一数据、同视角）')
    print('  平均像素差 %.2f / 255' % diff.mean())
    print('  差异>16 的像素占比 %.1f%%' % (100.0 * (diff > 16).mean()))
    print('  差异>64 的像素占比 %.1f%%' % (100.0 * (diff > 64).mean()))
    print('  输出: %s-ref.png / %s-ours.png / %s-side.png' % (prefix, prefix, prefix))


if __name__ == '__main__':
    main()
