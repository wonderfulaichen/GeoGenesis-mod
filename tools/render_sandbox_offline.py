"""沙盘离线出图自检（★ 2026-11-08）。

为什么需要它
------------
交接文档 §七 明确写：前两版沙盘都靠实机试错，浪费了两轮。
本脚本用 numpy 复刻 SandboxRenderer 的投影/排序/着色算法，
**不启动 Minecraft** 就能出图 —— 改动先在这里自检，再上实机。

用法
----
    python tools/render_sandbox_offline.py <height_tsv> <out_png> [gain]

height_tsv: 每行 "gx gz height e"，由 Java 侧导出
out_png   : 输出 PNG（可用 PIL）
gain      : 可选，覆盖 SandboxShade.LIGHT_GAIN

本脚本只做**几何与明暗自检**（柱高比例、可见面、遮挡顺序、明暗层次），
不替代实机验收。
"""
import sys
import math
import numpy as np
from PIL import Image

RELIEF = 0.40
WATER_SINK = 0.05
Z_BASE = 200.0
DEPTH_SCALE = 110.0
LIGHT_MIN = 0.55
LIGHT_MAX = 1.30
WATER_FACTOR = 0.35
PITCH_MIN, PITCH_MAX = 0.22, 1.40
DEFAULT_PITCH, DEFAULT_YAW = 0.95, 0.65
PILLAR_PX = 13.0
CELL_PX = 2.0


def per_column_for(viewport_px):
    """与 SandboxLod.perColumnFor 同式：柱子保持约 13px。"""
    grid = max(64, min(512, int(round(viewport_px / CELL_PX))))
    target = max(8.0, viewport_px / PILLAR_PX)
    return max(2, min(16, int(round(grid / target))))

# ---- SandboxPalette 群系本色（★ 2026-11-08 P0-1，与 Java 侧 SandboxPalette 对齐）----
# WhittakerType.ordinal() 顺序：ICE TUNDRA TAIGA GRASSLAND TEMPERATE_FOREST
#                               TEMPERATE_RAINFOREST DESERT SAVANNA
#                               SEASONAL_FOREST TROPICAL_RAINFOREST
# ★ 我们的 35 项群系本色（GeoPalette.T_BIOME，第 452-488 行）
#   与 BiomeClassifier.BiomeClass 逐项对齐；沙盘直接复用它，不再另造色表。
T_BIOME = [
    0x2E6FD6, 0x1B3F8F, 0x43D5EE, 0x2E9BD6, 0x1B6E9E,   # 0-4   OCEAN 系
    0x2B5BA8, 0x1B3A78, 0x7BA7D9, 0x4A6FA5,             # 5-8
    0x34B4D6, 0x9AC7E8,                                 # 9-10  RIVER
    0x4E6B2E, 0xE6D29A, 0xEAEAEA,                       # 11-13 SWAMP/BEACH/SNOWY_BEACH
    0x7CB342, 0x83BB6C,                                 # 14-15 PLAINS/MEADOW
    0x3F7A2E, 0x6E9B4E,                                 # 16-17 FOREST/BIRCH
    0x8A7A66, 0x5E7A52, 0xB59A5B,                       # 18-20 WINDSWEPT
    0x1E7A1E, 0x4E9A3E,                                 # 21-22 JUNGLE/SPARSE
    0xBFA83A, 0xC2A04A,                                 # 23-24 SAVANNA
    0xD9C072, 0xA9552F,                                 # 25-26 DESERT/BADLANDS
    0x2F6B4F, 0x4E8C7A, 0xDDE6EC,                       # 27-29 TAIGA/SNOWY_TAIGA/SNOWY_PLAINS
    0x5C8A6E, 0xC9D6DE,                                 # 30-31 GROVE/SNOWY_SLOPES
    0x8A8A8A, 0xB0B0B0, 0xFFFFFF,                       # 32-34 PEAKS
]


def biome_color(bid):
    """GeoPalette.colorForBiome 的等价实现。"""
    return T_BIOME[bid] if 0 <= bid < len(T_BIOME) else 0x888888


def jitter(rgb, bid):
    """参考项目 BiomePalette.jitter 同式：同色群系边界可读化。"""
    h = (bid * 0x9E3779B1) & 0xFFFFFFFF
    h ^= h >> 16
    return scale3(rgb, 0.94 + ((h & 0xFF) / 255.0) * 0.12)
SNOW_RGB = 0xE7F0F5
BEACH_RGB = 0xE2D3A0
WATER_SHALLOW = 0x6FA8D6
WATER_DEEP = 0x1C3F6B
# TerrainClass.ordinal()：BEACH=7
TERRAIN_BEACH = 7


def load(path):
    rows = []
    with open(path) as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith('#'):
                continue
            p = line.split()
            if len(p) < 4:
                continue
            rows.append((int(p[0]), int(p[1]), float(p[2]), float(p[3]),
                         int(float(p[4])) if len(p) > 4 else 0,
                         int(float(p[5])) if len(p) > 5 else 0,
                         1 if len(p) > 6 and float(p[6]) > 0 else 0,
                         *[float(p[k]) if len(p) > k else 0.0 for k in range(7, 13)],
                         (float('nan') if len(p) <= 13 or p[13] == 'nan'
                          else float(p[13]))))
    if not rows:
        raise SystemExit('no data in ' + path)
    gx = max(r[0] for r in rows) + 1
    gz = max(r[1] for r in rows) + 1
    n = gx * gz
    h = np.zeros((gx, gz))
    e = np.zeros((gx, gz))
    biome = np.zeros((gx, gz), dtype=int)
    terr = np.zeros((gx, gz), dtype=int)
    snow = np.zeros((gx, gz), dtype=bool)
    fields = {k: np.zeros((gx, gz)) for k in
              ('temp', 'humid', 'precip', 'cont', 'shape', 'disch')}
    water_y = np.full((gx, gz), np.nan)
    for row in rows:
        a, b, hh, ee, bb, tt, ss = row[:7]
        h[a, b] = hh
        e[a, b] = ee
        biome[a, b] = bb
        terr[a, b] = tt
        snow[a, b] = bool(ss)
        vals = row[7:13]
        for k, v in zip(fields, vals):
            fields[k][a, b] = v
        water_y[a, b] = row[13]
    fields['waterY'] = water_y
    return h, e, biome, terr, snow, fields


def build_rotation(yaw, pitch):
    """复刻 SandboxRenderer.buildRotation(): rotateZ(0) -> rotateX(pitch) -> rotateZ(yaw)。

    JOML Matrix3f.rotateZ(a) 是右乘，故 M = Rz(0)*Rx(pitch)*Rz(yaw)，
    再按 column-major 摊成 r[]（r[0..2]=第一行）。
    """
    def rz(a):
        c, s = np.cos(a), np.sin(a)
        return np.array([[c, -s, 0.0], [s, c, 0.0], [0.0, 0.0, 1.0]])

    def rx(a):
        c, s = np.cos(a), np.sin(a)
        return np.array([[1.0, 0.0, 0.0], [0.0, c, -s], [0.0, s, c]])

    m = rz(0.0) @ rx(pitch) @ rz(yaw)
    # JOML get() 是 column-major：raw[0..2]=第一列
    raw = m.T.reshape(-1)
    r = np.empty(9)
    r[0], r[1], r[2] = raw[0], raw[3], raw[6]
    r[3], r[4], r[5] = raw[1], raw[4], raw[7]
    r[6], r[7], r[8] = raw[2], raw[5], raw[8]
    return r


def model(fine, cell):
    return 0.0 if fine <= 1 else cell / (fine - 1) * 2.0 - 1.0


def render(h, e, biome, terr, snow, fields, gain=None, size=900, zoom=2.0, yaw=DEFAULT_YAW,
           pitch=DEFAULT_PITCH, sea=63.0, out='out.png', layer='BIOME'):
    gx, gz = h.shape
    PER_COLUMN = per_column_for(size)
    base = sea - 4.0
    # ★ 竖向比例照抄参考项目（SiteTerrainRenderer.collect 第 661/677-678 行）：
    #     span = max(60, 本图最高点 - base)
    #   用绝对量程(320)会把柱子压扁——本图高差只有 60~150 格，
    #   柱子只用到 20%~45% 高度，立体感远不如参考项目。
    surface_for_hi = np.where(e < 0.0, sea, h)
    hi = max(base, float(surface_for_hi.max()))
    span = max(60.0, hi - base)

    # 归一化高度 r（水域用海平面）
    surface = np.where(e < 0.0, sea, h)
    r = (surface - base) / span

    # 坡度光照（复刻 SandboxShade.lightFor）
    left = np.roll(r, 1, axis=0); left[0] = r[0]
    right = np.roll(r, -1, axis=0); right[-1] = r[-1]
    up = np.roll(r, 1, axis=1); up[:, 0] = r[:, 0]
    down = np.roll(r, -1, axis=1); down[:, -1] = r[:, -1]
    slope = (right - left) * 0.5 + (down - up) * 0.5
    # ★ 自适应增益（与 SandboxShade.adaptiveGain 同式）：
    #   LOD 各档窗口不同，固定增益会让细档相邻柱差被放大成棋盘格纹。
    if gain is None:
        p = float(np.quantile(np.abs(slope), 0.995))
        gain = 0.9 / p if p > 1e-9 else 0.5
        gain = min(60.0, max(0.5, gain))
    light = np.clip(1.0 - slope * gain, LIGHT_MIN, LIGHT_MAX)
    water = e < 0.0
    light = np.where(water, 1.0 + (light - 1.0) * WATER_FACTOR, light)

    # 连续图层色带（复刻 GeoPalette.continuous 的语义：蓝→绿→黄→红 等）
    def ramp(v, stops):
        v = min(1.0, max(0.0, v))
        n = len(stops) - 1
        x = v * n
        i0 = min(n - 1, int(x))
        t = x - i0
        return lerp3(stops[i0], stops[i0 + 1], t)

    CLIMATE_RAMP = [0x2E5FA8, 0x3FA88E, 0x8FC24A, 0xE8C24A, 0xD85A3A]
    ELEV_RAMP = [0x1B3F8F, 0x2E6FD6, 0x4FB3D9, 0x9AC7E8, 0xE2D3A0, 0x6FAE4A,
                 0x3D6B32, 0x8A8A85, 0xB0B0B0, 0xFFFFFF]

    def layer_rgb(i, j):
        """复刻 SandboxPalette.colorForLayer 的分流。"""
        # 水面一律深度渐变
        if e[i, j] < 0.0:
            depth = min(1.0, (sea - h[i, j]) / 30.0) if h[i, j] < sea else 0.0
            rgb = lerp3(WATER_SHALLOW, WATER_DEEP, depth)
            return tuple(c / 255.0 for c in scale3(rgb, light[i, j]))

        if layer == 'BIOME':
            if snow[i, j]:
                rgb = int2rgb(SNOW_RGB)
            else:
                rgb = jitter(int2rgb(biome_color(int(biome[i, j]))), int(biome[i, j]))
        elif layer == 'ELEVATION':
            v = (e[i, j] + 1.0) * 0.5
            rgb = ramp(v, ELEV_RAMP)
        elif layer == 'TEMPERATURE':
            rgb = ramp((fields['temp'][i, j] + 1.0) * 0.5, CLIMATE_RAMP)
        elif layer == 'HUMIDITY':
            rgb = ramp((fields['humid'][i, j] + 1.0) * 0.5, CLIMATE_RAMP)
        elif layer == 'PRECIPITATION':
            rgb = ramp(math.sqrt(max(0.0, min(1.0, fields['precip'][i, j] / 1.2))), CLIMATE_RAMP)
        elif layer == 'CONTINENTALITY':
            rgb = ramp((fields['cont'][i, j] + 1.0) * 0.5, CLIMATE_RAMP)
        elif layer == 'RELIEF':
            rgb = ramp((fields['shape'][i, j] + 1.0) * 0.5, CLIMATE_RAMP)
        elif layer == 'RIVER_NETWORK':
            dis = fields['disch'][i, j]
            v = min(1.0, math.log1p(dis) / 6.0) if dis > 0 else 0.0
            rgb = ramp(v, [0x1B3A78, 0x2E6FD6, 0x43D5EE])
        else:
            # 其余离散图层离线不精确复刻，用灰阶占位
            rgb = int2rgb(biome_color(int(biome[i, j])))
        return tuple(c / 255.0 for c in scale3(rgb, light[i, j]))
    # 柱
    col_count = (gx + PER_COLUMN - 1) // PER_COLUMN
    col_rows = (gz + PER_COLUMN - 1) // PER_COLUMN
    tops = np.zeros(col_count * col_rows)
    water_top = np.full(col_count * col_rows, np.nan)
    colc = np.zeros(col_count * col_rows, dtype=object)
    for cx in range(col_count):
        for cz in range(col_rows):
            col = cx * col_rows + cz
            fx0, fz0 = cx * PER_COLUMN, cz * PER_COLUMN
            fx1 = min(gx - 1, fx0 + PER_COLUMN - 1)
            fz1 = min(gz - 1, fz0 + PER_COLUMN - 1)
            sy = h[fx0, fz0]
            wy = fields['waterY'][fx0, fz0]
            has_w = (not np.isnan(wy)) and wy > sy + 0.01
            top_y = wy if has_w else sy
            tops[col] = max((top_y - base) / span, WATER_SINK) * RELIEF
            water_top[col] = top_y if has_w else float('nan')
            # 多数群系（复刻 majorityCell 语义）
            ids, cnts = [], []
            for dx in range(fx1 - fx0 + 1):
                for dz in range(fz1 - fz0 + 1):
                    b = biome[fx0 + dx, fz0 + dz]
                    if b in ids:
                        cnts[ids.index(b)] += 1
                    else:
                        ids.append(b); cnts.append(1)
            majority = ids[cnts.index(max(cnts))]
            mfx = mfz = None
            for dx in range(fx1 - fx0 + 1):
                for dz in range(fz1 - fz0 + 1):
                    if biome[fx0 + dx, fz0 + dz] == majority:
                        mfx, mfz = fx0 + dx, fz0 + dz
                        break
                if mfx is not None:
                    break
            if not np.isnan(water_top[col]):
                d = min(1.0, max(0.0, (sea - water_top[col]) / 30.0))
                wc = lerp3(WATER_SHALLOW, WATER_DEEP, d)
                colc[col] = tuple(c / 255.0 for c in wc)
            else:
                colc[col] = layer_rgb(mfx, mfz)

    r9 = build_rotation(yaw, pitch)
    # yardsite 口径（用户裁定恢复）：scale = areaSize * 0.46 * zoom
    # ★ 2026-11-08 修正：scale 不随 zoom（与 SandboxRenderer.render 同一口径）
    #   旧写法 scale = size*0.46*zoom 与 window 缩小同比例抵消
    #   => 构图不变、只看到柱子变大（用户实机反馈）。
    #   现在 scale 固定 => 缩放只改窗口（世界范围），柱子屏幕尺寸恒定，
    #   画面内容"推近/拉远"，且每柱代表的方块变少 => 更精细。
    scale = size * 0.46 * 2.0
    ox = oy = size / 2.0

    def proj(mx, my, mz):
        sx = ox + scale * (r9[0] * mx + r9[1] * my + r9[2] * mz)
        sy = oy + scale * (r9[3] * mx + r9[4] * my + r9[5] * mz)
        depth = r9[6] * mx + r9[7] * my + r9[8] * mz
        return sx, sy, depth

    see_e, see_w = r9[6] > 0, r9[6] < 0
    see_s, see_n = r9[7] > 0, r9[7] < 0
    see_top = r9[8] > 0

    # 深度排序（远 -> 近）
    order = []
    for col in range(col_count * col_rows):
        mx = model(col_count, col // col_rows)
        my = model(col_rows, col % col_rows)
        depth = r9[6] * mx + r9[7] * my + r9[8] * max(tops[col], 0.0)
        order.append((depth, col))
    order.sort()

    img = np.zeros((size, size, 3), dtype=np.float64)
    img[:, :] = (0.09, 0.10, 0.13)

    def quad(pts, color):
        """四边形填充：拆成两个三角形（重心坐标判定），painter's algorithm 逐块绘制。"""
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
            inside = (l1 >= -0.001) & (l2 >= -0.001) & (l3 >= -0.001)
            if not inside.any():
                continue
            for k in range(3):
                img[y0:y1, x0:x1, k][inside] = color[k]

    def shade(rgb, mul):
        """rgb 是 0..1 的分量；mul 是光照倍率。"""
        return tuple(min(1.0, max(0.0, c * mul)) for c in rgb)

    # 侧墙最小屏幕高度（像素）：低于它不画。亚像素墙 = 每柱一条亮线 = 密集条纹
    min_wall = 1.0 / max(1e-3, scale)
    for _, col in order:
        cx, cz = col // col_rows, col % col_rows
        top = tops[col]
        rgb = colc[col]  # 已经乘过光照的 0..1 三元组
        fx0, fz0 = cx * PER_COLUMN, cz * PER_COLUMN
        fx1 = min(gx - 1, fx0 + PER_COLUMN - 1)
        fz1 = min(gz - 1, fz0 + PER_COLUMN - 1)
        ccx, ccy = (fx0 + fx1) * 0.5, (fz0 + fz1) * 0.5
        hx, hy = (fx1 - fx0 + 1) * 0.5, (fz1 - fz0 + 1) * 0.5
        x0, x1 = model(gx, ccx - hx), model(gx, ccx + hx)
        y0, y1 = model(gz, ccy - hy), model(gz, ccy + hy)
        def mul(rgb01, m):
            return tuple(min(1.0, max(0.0, c * m)) for c in rgb01)
        if see_top:
            quad([proj(x0, y0, top), proj(x1, y0, top),
                  proj(x1, y1, top), proj(x0, y1, top)], rgb)
        if see_e:
            low = max(tops[col + col_rows], 0.0) if cx + 1 < col_count else 0.0
            if top - low > min_wall:
                quad([proj(x1, y0, low), proj(x1, y1, low),
                      proj(x1, y1, top), proj(x1, y0, top)], mul(rgb, 0.82))
        if see_w:
            low = max(tops[col - col_rows], 0.0) if cx > 0 else 0.0
            if top - low > min_wall:
                quad([proj(x0, y1, low), proj(x0, y0, low),
                      proj(x0, y0, top), proj(x0, y1, top)], mul(rgb, 0.56))
        if see_s:
            low = max(tops[col + 1], 0.0) if cz + 1 < col_rows else 0.0
            if top - low > min_wall:
                quad([proj(x0, y1, low), proj(x1, y1, low),
                      proj(x1, y1, top), proj(x0, y1, top)], mul(rgb, 0.70))
        if see_n:
            low = max(tops[col - 1], 0.0) if cz > 0 else 0.0
            if top - low > min_wall:
                quad([proj(x1, y0, low), proj(x0, y0, low),
                      proj(x0, y0, top), proj(x1, y0, top)], mul(rgb, 0.46))

    Image.fromarray((img * 255).astype(np.uint8)).save(out)
    print('wrote', out, 'size', size, 'gain', gain, 'zoom', zoom)
    print('light stats: min %.3f max %.3f mean %.3f' % (light.min(), light.max(), light.mean()))


def int2rgb(v):
    return ((v >> 16) & 255, (v >> 8) & 255, v & 255)


def lerp3(from_rgb, to_rgb, t):
    fr = ((from_rgb >> 16) & 255, (from_rgb >> 8) & 255, from_rgb & 255)
    to = ((to_rgb >> 16) & 255, (to_rgb >> 8) & 255, to_rgb & 255)
    return tuple((1 - t) * f + t * o for f, o in zip(fr, to))


def scale3(rgb, factor):
    return tuple(min(255.0, max(0.0, c * factor)) for c in rgb)


if __name__ == '__main__':
    if len(sys.argv) < 3:
        raise SystemExit(__doc__)
    h, e, biome, terr, snow, fields = load(sys.argv[1])
    gain = float(sys.argv[3]) if len(sys.argv) > 3 and sys.argv[3] != 'auto' else None
    layer = sys.argv[4] if len(sys.argv) > 4 else 'BIOME'
    render(h, e, biome, terr, snow, fields, gain, out=sys.argv[2], layer=layer)
