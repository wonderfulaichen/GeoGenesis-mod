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
PER_COLUMN = 2


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
            rows.append((int(p[0]), int(p[1]), float(p[2]), float(p[3])))
    if not rows:
        raise SystemExit('no data in ' + path)
    gx = max(r[0] for r in rows) + 1
    gz = max(r[1] for r in rows) + 1
    h = np.zeros((gx, gz))
    e = np.zeros((gx, gz))
    for a, b, hh, ee in rows:
        h[a, b] = hh
        e[a, b] = ee
    return h, e


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


def render(h, e, gain, size=900, zoom=2.0, yaw=DEFAULT_YAW, pitch=DEFAULT_PITCH,
           sea=63.0, out='out.png'):
    gx, gz = h.shape
    base = sea - 4.0
    hi = max(base, h.max())
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
    light = np.clip(1.0 - slope * gain, LIGHT_MIN, LIGHT_MAX)
    water = e < 0.0
    light = np.where(water, 1.0 + (light - 1.0) * WATER_FACTOR, light)

    # 柱
    col_count = (gx + PER_COLUMN - 1) // PER_COLUMN
    col_rows = (gz + PER_COLUMN - 1) // PER_COLUMN
    tops = np.zeros(col_count * col_rows)
    lightc = np.zeros(col_count * col_rows)
    for cx in range(col_count):
        for cz in range(col_rows):
            col = cx * col_rows + cz
            fx0, fz0 = cx * PER_COLUMN, cz * PER_COLUMN
            fx1 = min(gx - 1, fx0 + PER_COLUMN - 1)
            fz1 = min(gz - 1, fz0 + PER_COLUMN - 1)
            sy = h[fx0, fz0]
            tops[col] = max((sy - base) / span, WATER_SINK) * RELIEF
            mfx, mfz = (fx0 + fx1) // 2, (fz0 + fz1) // 2
            lightc[col] = light[mfx, mfz]

    r9 = build_rotation(yaw, pitch)
    # 2026-11-08 新口径：zoom=1.0 时整图恰好铺满视口
    # 复刻 SandboxRenderer.fitScale()：4 个平面角 × 2 个高度 -> 包围盒
    max_top = float(tops.max()) if len(tops) else 0.0
    corners = []
    for c in range(4):
        mx = -1.0 if (c & 1) == 0 else 1.0
        my = -1.0 if (c & 2) == 0 else 1.0
        for hz in (0.0, max_top):
            corners.append((r9[0] * mx + r9[1] * my + r9[2] * hz,
                            r9[3] * mx + r9[4] * my + r9[5] * hz))
    xs = [p[0] for p in corners]; ys = [p[1] for p in corners]
    fit = max(max(xs) - min(xs), max(ys) - min(ys))
    fit = fit if fit > 1e-3 else 1.0
    fit_cx = (min(xs) + max(xs)) * 0.5
    fit_cy = (min(ys) + max(ys)) * 0.5
    scale = size * 0.98 * zoom / fit
    ox = oy = size / 2.0

    def proj(mx, my, mz):
        gx_ = mx
        gy_ = my
        sx = ox + scale * (r9[0] * gx_ + r9[1] * gy_ + r9[2] * mz - fit_cx)
        sy = oy + scale * (r9[3] * gx_ + r9[4] * gy_ + r9[5] * mz - fit_cy)
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

    for _, col in order:
        cx, cz = col // col_rows, col % col_rows
        top = tops[col]
        lt = lightc[col]
        fx0, fz0 = cx * PER_COLUMN, cz * PER_COLUMN
        fx1 = min(gx - 1, fx0 + PER_COLUMN - 1)
        fz1 = min(gz - 1, fz0 + PER_COLUMN - 1)
        ccx, ccy = (fx0 + fx1) * 0.5, (fz0 + fz1) * 0.5
        hx, hy = (fx1 - fx0 + 1) * 0.5, (fz1 - fz0 + 1) * 0.5
        x0, x1 = model(gx, ccx - hx), model(gx, ccx + hx)
        y0, y1 = model(gz, ccy - hy), model(gz, ccy + hy)
        base_rgb = (0.55, 0.60, 0.45)
        if see_top:
            quad([proj(x0, y0, top), proj(x1, y0, top),
                  proj(x1, y1, top), proj(x0, y1, top)], shade(base_rgb, lt))
        if see_e:
            low = max(tops[col + col_rows], 0.0) if cx + 1 < col_count else 0.0
            if top > low:
                quad([proj(x1, y0, low), proj(x1, y1, low),
                      proj(x1, y1, top), proj(x1, y0, top)], shade(base_rgb, lt * 0.82))
        if see_w:
            low = max(tops[col - col_rows], 0.0) if cx > 0 else 0.0
            if top > low:
                quad([proj(x0, y1, low), proj(x0, y0, low),
                      proj(x0, y0, top), proj(x0, y1, top)], shade(base_rgb, lt * 0.56))
        if see_s:
            low = max(tops[col + 1], 0.0) if cz + 1 < col_rows else 0.0
            if top > low:
                quad([proj(x0, y1, low), proj(x1, y1, low),
                      proj(x1, y1, top), proj(x0, y1, top)], shade(base_rgb, lt * 0.70))
        if see_n:
            low = max(tops[col - 1], 0.0) if cz > 0 else 0.0
            if top > low:
                quad([proj(x1, y0, low), proj(x0, y0, low),
                      proj(x0, y0, top), proj(x1, y0, top)], shade(base_rgb, lt * 0.46))

    Image.fromarray((img * 255).astype(np.uint8)).save(out)
    print('wrote', out, 'size', size, 'gain', gain)
    print('light stats: min %.3f max %.3f mean %.3f' % (light.min(), light.max(), light.mean()))


if __name__ == '__main__':
    if len(sys.argv) < 3:
        raise SystemExit(__doc__)
    h, e = load(sys.argv[1])
    gain = float(sys.argv[3]) if len(sys.argv) > 3 else 8.0
    render(h, e, gain, out=sys.argv[2])
