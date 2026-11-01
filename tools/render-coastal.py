#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""实际沿海组装图：俯视 + 横剖面 + 3D 地形。

用法：
    python tools/render-coastal.py <root> <dest>
"""
import importlib.util
import json
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

BG = '#f4f3ed'
INK = '#25261f'
DIM = '#55594c'
FONT_CANDIDATES = ('C:/Windows/Fonts/msyh.ttc', 'C:/Windows/Fonts/msyhbd.ttc',
                   'C:/Windows/Fonts/simhei.ttf')


class InputError(Exception):
    pass


def need(cond, msg):
    if not cond:
        raise InputError(msg)


_FONTS = {}


def font(size):
    if size not in _FONTS:
        for p in FONT_CANDIDATES:
            if Path(p).is_file():
                _FONTS[size] = ImageFont.truetype(p, size)
                break
        else:
            raise InputError('no CJK font')
    return _FONTS[size]


def tw(text, f):
    try:
        return float(f.getlength(text))
    except AttributeError:
        return float(f.getsize(text)[0])


def fit(text, max_w, preferred, floor=11):
    size = preferred
    while size > floor and tw(text, font(size)) > max_w:
        size -= 1
    return font(floor if size <= floor else size)


def load_renderer():
    here = Path(__file__).resolve()
    for cand in (here.with_name('render-islands-big.py'),
                 here.parent.parent / 'forge-1.20.1-47.4.10-mdk' / 'tools' / 'render-islands-big.py'):
        if cand.is_file():
            spec = importlib.util.spec_from_file_location('r', str(cand))
            mod = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(mod)
            return mod
    raise InputError('render-islands-big.py not found')


# ---------------------------------------------------------------- 3D 渲染

def render_3d(height, step, sea, yaw=35.0, pitch=42.0, width=1300, height_px=760,
              vz=2.2, sun=None):
    """等轴测 3D 地形（纯 numpy 光栅化，无外部依赖）。

    做法（画家算法 + 四边形填充）：
      1. 降采样到 ≤ 400² 网格
      2. 高度按自身范围缩放到 Z（vz 控制夸张倍数）
      3. yaw 旋转 + pitch 倾斜 ⇒ 屏幕坐标
      4. 按深度从远到近逐四边形填充，颜色来自高度、明暗来自法向
      5. 水下叠淡蓝

    为什么不用 matplotlib：项目无该依赖，且要与其它的光照/色带保持一致。
    """
    from PIL import ImageDraw as _ID

    h, w = height.shape
    ds = max(1, int(round(min(w, h) / 400.0)))
    hh = height[::ds, ::ds]
    hh = np.where(np.isfinite(hh), hh, sea)
    gh, gw = hh.shape

    ymin, ymax = float(hh.min()), float(hh.max())
    yr = max(ymax - ymin, 1.0)

    # 投影：水平尺度 = gw（格），垂直尺度 = yr*vz
    cy, sy = np.cos(np.radians(yaw)), np.sin(np.radians(yaw))
    cp, sp = np.cos(np.radians(pitch)), np.sin(np.radians(pitch))

    xs = np.arange(gw, dtype=np.float64) - gw / 2.0
    ys = np.arange(gh, dtype=np.float64) - gh / 2.0
    XX, YY = np.meshgrid(xs, ys)
    ZZ = (hh - sea) / yr * (gw * vz / 4.0)   # 高度位移（相对水平尺度）

    XR = XX * cy - YY * sy
    YR = XX * sy + YY * cy
    SX = XR
    SY = YR * sp - ZZ * cp

    sx0, sx1 = float(SX.min()), float(SX.max())
    sy0, sy1 = float(SY.min()), float(SY.max())
    spanx, spany = max(sx1 - sx0, 1e-9), max(sy1 - sy0, 1e-9)
    k = min(width * 0.94 / spanx, height_px * 0.94 / spany)
    SXP = ((SX - sx0) * k + (width - spanx * k) / 2).astype(np.float64)
    SYP = ((SY - sy0) * k + (height_px - spany * k) / 2).astype(np.float64)

    canvas = Image.new('RGB', (width, height_px), (246, 245, 238))
    draw = _ID.Draw(canvas)

    if sun is None:
        sun = np.array([-0.55, -0.45, 0.70])
    sun = np.asarray(sun, dtype=np.float64)
    sun /= max(np.linalg.norm(sun), 1e-9)

    anchors = np.array([-64, -40, -12, 62, 66, 76, 110, 170, 240, 320], dtype=np.float64)
    colors = np.array([
        [18, 34, 78], [26, 52, 104], [38, 80, 140],
        [214, 200, 150], [196, 186, 140], [150, 168, 110],
        [96, 138, 82], [110, 140, 80], [150, 150, 145], [236, 238, 240],
    ], dtype=np.float64)
    lut_y = np.arange(-70, 330, 1.0)
    lut = np.stack([np.interp(lut_y, anchors, colors[:, c]) for c in range(3)], axis=-1)

    def color_of(yv):
        i = int(np.clip(yv + 70, 0, len(lut_y) - 1))
        return tuple(int(v) for v in lut[i])

    # 画家算法：YR 大 = 近，后画
    order = np.argsort(YR, axis=None)
    for idx in order:
        j, i = divmod(int(idx), gw)
        if i + 1 >= gw or j + 1 >= gh:
            continue
        poly = [
            (SXP[j, i], SYP[j, i]),
            (SXP[j, i + 1], SYP[j, i + 1]),
            (SXP[j + 1, i + 1], SYP[j + 1, i + 1]),
            (SXP[j + 1, i], SYP[j + 1, i]),
        ]
        ymean = (hh[j, i] + hh[j, i + 1] + hh[j + 1, i + 1] + hh[j + 1, i]) / 4.0
        base = np.array(color_of(ymean), dtype=np.float64)
        dzdx = (hh[j, i + 1] - hh[j, i]) / max(ds, 1)
        dzdy = (hh[j + 1, i] - hh[j, i]) / max(ds, 1)
        n = np.array([-dzdx, -dzdy, 1.0])
        n /= max(np.linalg.norm(n), 1e-9)
        lit = float(np.clip(np.dot(n, sun), 0.0, 1.0))
        water = ymean < sea
        shade = (0.66 + 0.34 * lit) if water else (0.32 + 0.76 * lit)
        rgb = np.clip(base * shade, 0, 255)
        draw.polygon(poly, fill=tuple(int(v) for v in rgb))
    return canvas


def main(argv):
    if len(argv) != 2:
        sys.stderr.write('usage: python tools/render-coastal.py <root> <dest>\n')
        return 2
    root, dest = Path(argv[0]), Path(argv[1])
    try:
        meta = json.loads((root / 'meta.json').read_text(encoding='utf-8'))
        size = int(meta['size']); step = int(meta['step'])
        band = float(meta['band'])
        combos = list(meta['combos'])
        mod = load_renderer()
        sea = float(mod.SEA)
        dest.mkdir(parents=True, exist_ok=True)

        grids = {}
        for nm in combos:
            p = root / ('Y-%s.f64be' % nm)
            grids[nm] = np.frombuffer(p.read_bytes(), dtype='>f8').astype(np.float64).reshape(size, size)

        lines = [
            'seed=%s｜%d² 采样、步长 %d 块｜海岸带 BAND=%.0f 块' % (meta['seed'], size, step, band),
            '★ 实际沿海组装：剖面方向由【到海的有符号距离 d】驱动，不是自噪声。',
            '   d = 到海距离（正=陆上、0=岸线、负=水下）；d 做过零线性插值 ⇒ 连续无台阶。',
            '   崖壁朝海、沙滩朝海、三角洲扇形朝海 —— 与真实岸线几何一致。',
        ]

        # ---------- 俯视 2x2
        imgs, titles = [], []
        for nm in combos:
            g = grids[nm]
            imgs.append(mod.render(g, step))
            titles.append('%s  水 %.0f%%' % (nm, 100.0 * float((g < sea).mean())))
        _grid(imgs, titles, '实际沿海组装｜俯视', lines,
              '每张 %d² 原生像素，1 像素 = %d 块；同窗口、同光照、同色带。' % (size, step),
              cols=2).save(dest / 'coastal-topview.png', optimize=True)

        # ---------- 剖面
        _profiles(grids, combos, sea, lines).save(dest / 'coastal-profile.png', optimize=True)

        # ---------- 3D（每张单独出，再拼 2x2）
        im3d, ti3d = [], []
        for nm in combos:
            g = grids[nm]
            im3d.append(render_3d(g, step, sea))
            ti3d.append(nm)
        _grid(im3d, ti3d, '实际沿海组装｜3D 等轴测（俯视 42°，方位 35°）', lines,
              '3D 为等轴测投影渲染（非游戏截图）；高度按各自范围缩放，水下叠淡蓝。',
              cols=2).save(dest / 'coastal-3d.png', optimize=True)

        # ---------- 局部特写（600×600 块、1 块/像素）
        # 两组：① 生产口径 band=80  ② 夸大带宽 band=240（专看类型差异）
        for cm_name, fig_name, cm_note in (
                ('closeup-meta.json', 'closeup', '生产口径 BAND=80 块'),
                ('closeup-meta-wide.json', 'closeup-wide', '夸大带宽 BAND=240 块（非生产口径，仅用于观察类型差异）')):
            cm_path = root / cm_name
            if not cm_path.is_file():
                continue
            cm = json.loads(cm_path.read_text(encoding='utf-8'))
            csize = int(cm['size']); cstep = int(cm['step'])
            ccombos = list(cm['combos'])
            suffix = cm.get('suffix', '')
            for sp in cm['spots']:
                tag = sp['tag']
                cimgs, cts = [], []
                for nm in ccombos:
                    p = root / ('C-%s%s-%s.f64be' % (tag, suffix, nm))
                    if not p.is_file():
                        continue
                    cg = np.frombuffer(p.read_bytes(), dtype='>f8').astype(np.float64).reshape(csize, csize)
                    cimgs.append(mod.render(cg, cstep))
                    cts.append('%s  水 %.0f%%' % (nm, 100.0 * float((cg < sea).mean())))
                if not cimgs:
                    continue
                _grid(cimgs, cts,
                      '局部特写｜%s｜%s @ (%.0f, %.0f)'
                      % (cm_note, tag, sp['center'][0], sp['center'][1]),
                      ['★ %d×%d 块、1 块/像素。海岸带 %.0f 块占幅面 %.0f%%。'
                       % (csize, csize, cm['band'], 100.0 * cm['band'] / csize),
                       '四种海岸类型的预期形态：',
                       '沙滩=宽而平的浅色带｜崖岸=窄而陡的暗色壁｜三角洲=扇形+分叉｜礁盘=碎斑'],
                      '1 像素 = %d 块，未缩放。' % cstep,
                      cols=2).save(dest / ('%s-%s.png' % (fig_name, tag)), optimize=True)

        print('rendered figures -> %s' % dest)
        return 0
    except InputError as exc:
        sys.stderr.write('FAIL %s\n' % exc)
        return 1
    except (IOError, OSError) as exc:
        sys.stderr.write('IO ERROR %s\n' % exc)
        return 2


def _grid(images, titles, heading, lines, note, cols):
    w, h = images[0].size
    pad, gap, label_h, tail = 40, 32, 30, 56
    line_y, line_dy = 70, 26
    head = line_y + line_dy * len(lines) + 46
    grid_w = pad * 2 + w * cols + gap * (cols - 1)
    text_w = max([tw(t, font(18)) for t in list(lines) + [note]] + [tw(heading, font(32))])
    cw = int(max(grid_w, text_w + pad * 2 + 8))
    rows = (len(images) + cols - 1) // cols
    ch = head + rows * (h + label_h + gap) + tail
    canvas = Image.new('RGB', (cw, ch), BG)
    d = ImageDraw.Draw(canvas)
    d.text((pad, 14), heading, font=fit(heading, cw - pad * 2, 32), fill=INK)
    for i, t in enumerate(lines):
        d.text((pad, line_y + i * line_dy), t, font=fit(t, cw - pad * 2, 18), fill=DIM)
    for k, (im, title) in enumerate(zip(images, titles)):
        r, c = divmod(k, cols)
        x = pad + c * (w + gap)
        y = head + r * (h + label_h + gap)
        d.text((x, y), title, font=fit(title, w, 20), fill=INK)
        canvas.paste(im, (x, y + label_h))
    d.text((pad, ch - tail + 12), note, font=fit(note, cw - pad * 2, 18), fill=DIM)
    return canvas


def _profiles(grids, combos, sea, lines):
    n = len(combos)
    pw, ph = 1400, 150
    pad, gap, tail = 40, 26, 60
    line_y, line_dy = 70, 26
    head = line_y + line_dy * len(lines) + 40
    cw = pad * 2 + pw
    ch = head + n * (ph + gap) + tail
    canvas = Image.new('RGB', (cw, ch), BG)
    d = ImageDraw.Draw(canvas)
    d.text((pad, 14), '实际沿海组装｜横剖面（真实离海距离驱动）',
           font=fit('实际沿海组装｜横剖面', cw - pad * 2, 30), fill=INK)
    for i, t in enumerate(lines):
        d.text((pad, line_y + i * line_dy), t, font=fit(t, cw - pad * 2, 18), fill=DIM)
    for k, nm in enumerate(combos):
        g = grids[nm]
        y0 = head + k * (ph + gap)
        row = g[g.shape[0] // 2]
        lo, hi = float(row.min()), float(row.max())
        span = max(hi - lo, 1.0)
        lo -= span * 0.12; hi += span * 0.12
        bx, bw = pad + 130, pw - 130
        d.rectangle((bx, y0, bx + bw, y0 + ph), outline='#c9c5b6')
        if lo <= sea <= hi:
            yy = y0 + ph - int((sea - lo) / (hi - lo) * ph)
            d.line((bx, yy, bx + bw, yy), fill='#2e5ca8', width=2)
            d.text((pad, yy - 8), '海平面 %d' % sea, font=font(13), fill='#2e5ca8')
        pts = []
        for i in range(0, len(row), max(1, len(row) // bw)):
            px = bx + int(i / (len(row) - 1) * bw)
            py = y0 + ph - int((row[i] - lo) / (hi - lo) * ph)
            pts.append((px, max(y0, min(y0 + ph, py))))
        if len(pts) > 1:
            d.line(pts, fill='#7a4a1e', width=2)
        d.text((pad, y0 + 2), nm, font=font(15), fill=INK)
        d.text((pad, y0 + 22), 'Y %d..%d' % (int(hi), int(lo)), font=font(12), fill=DIM)
        d.text((pad, y0 + 40), '水 %.0f%%' % (100.0 * float((row < sea).mean())),
               font=font(12), fill=DIM)
    d.text((pad, ch - tail + 14), '取 1401² 图的中间行（z=中心）。纵轴按每条自身范围缩放。',
           font=font(17), fill=DIM)
    return canvas


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
