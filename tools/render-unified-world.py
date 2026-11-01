#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""统一世界地形图：1401² 全图 + 剖面 + 3D。

用法：
    python tools/render-unified-world.py <root> <dest>
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


def render_better(height, step, sea):
    """改版渲染：色带在 170 以上不再突变成白，改用渐进灰阶。

    <h3>★ 为什么要改（2026-11-01）</h3>
    <p>共用渲染器 {@code render-islands-big.py} 的色带是</p>
    <pre>
      ANCHORS = [-64, -40, -12, 62, 66, 76, 110, 170, 240, 320]
      COLORS  = [深海…, 滩, 草, 高, 岩(168,160,128), 雪白(196,190,172), 纯白(238,236,228)]
    </pre>
    <p>问题：{@code 170} 之后颜色突然从橄榄绿跳到浅灰/白，而且 {@code 240} 就已经接近纯白。
    实测本图陆地 P90 达 141 格、最高 257 格 ⇒ <b>大片 170+ 的区域被染成亮白</b>，
    沿岸形成一圈"白色环"，看起来像雪线，实际高度只是丘陵/低山
    （高度分层图证实：白色环落在 20–100 带，不是 220+ 带）。</p>

    <h3>改法</h3>
    <p>把 170 以上改成<b>缓慢加深的岩灰</b>，只有真正接近峰顶（240+）才转白，
    且白是纯色而非接近纯白：</p>
    <pre>
      ... 110(橄榄) → 170(岩石棕灰) → 240(浅岩灰) → 320(雪)
    </pre>
    <p>同时把 {@code 170} 的色从"浅灰"改成"岩石棕灰"，与山体纹理更协调。</p>
    """
    gz, gx = np.gradient(height, step)
    norm = np.sqrt(gx * gx + gz * gz + 1)
    light = np.array([-0.5, -0.35, 1.0])
    light /= np.linalg.norm(light)
    lit = np.clip((-gx * light[0] - gz * light[1] + light[2]) / norm, 0, 1)

    anchors = np.array([-64, -40, -12, 62, 66, 76, 110, 170, 210, 250, 300, 360],
                       dtype=np.float64)
    colors = np.array([
        [16, 26, 40],      # -64  深海底
        [24, 40, 62],      # -40  深海
        [40, 66, 92],      # -12  浅海
        [58, 92, 116],     #  62  岸线（水下侧）
        [214, 205, 168],   #  66  滩
        [96, 122, 78],     #  76  草
        [132, 142, 88],    # 110  高地（橄榄）
        [150, 136, 108],   # 170  岩石棕灰 ← 原为浅灰 (168,160,128)
        [170, 162, 146],   # 210  中岩灰 ← 新增，缓冲
        [196, 192, 184],   # 250  浅岩灰 ← 原为近白
        [226, 226, 224],   # 300  雪（真正的雪线）
        [244, 244, 246],   # 360  峰顶纯白
    ], dtype=np.float64)

    rgb = np.stack([np.interp(height, anchors, colors[:, c]) for c in range(3)], axis=-1)
    water = height < sea
    shade_w = 0.72 + 0.28 * lit
    shade_l = 0.28 + 0.80 * lit
    shade = np.where(water, shade_w, shade_l)
    return Image.fromarray(np.uint8(np.clip(rgb * shade[..., None], 0, 255)))


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


def render_3d(height, sea, yaw=35.0, pitch=42.0, width=1400, height_px=800, vz=1.4):
    """等轴测 3D（同 render-coastal.py 的实现，参数针对全图调整）。"""
    from PIL import ImageDraw as _ID

    h, w = height.shape
    ds = max(1, int(round(min(w, h) / 420.0)))
    hh = height[::ds, ::ds]
    hh = np.where(np.isfinite(hh), hh, sea)
    gh, gw = hh.shape

    ymin, ymax = float(hh.min()), float(hh.max())
    yr = max(ymax - ymin, 1.0)
    cy, sy = np.cos(np.radians(yaw)), np.sin(np.radians(yaw))
    cp, sp = np.cos(np.radians(pitch)), np.sin(np.radians(pitch))

    xs = np.arange(gw, dtype=np.float64) - gw / 2.0
    ys = np.arange(gh, dtype=np.float64) - gh / 2.0
    XX, YY = np.meshgrid(xs, ys)
    ZZ = (hh - sea) / yr * (gw * vz / 4.0)

    XR = XX * cy - YY * sy
    YR = XX * sy + YY * cy
    SX = XR
    SY = YR * sp - ZZ * cp

    sx0, sx1 = float(SX.min()), float(SX.max())
    sy0, sy1 = float(SY.min()), float(SY.max())
    spanx, spany = max(sx1 - sx0, 1e-9), max(sy1 - sy0, 1e-9)
    k = min(width * 0.96 / spanx, height_px * 0.96 / spany)
    SXP = ((SX - sx0) * k + (width - spanx * k) / 2).astype(np.float64)
    SYP = ((SY - sy0) * k + (height_px - spany * k) / 2).astype(np.float64)

    canvas = Image.new('RGB', (width, height_px), (246, 245, 238))
    draw = _ID.Draw(canvas)

    sun = np.array([-0.55, -0.45, 0.70])
    sun /= max(np.linalg.norm(sun), 1e-9)
    anchors = np.array([-64, -40, -12, 62, 66, 76, 110, 170, 240, 320], dtype=np.float64)
    colors = np.array([
        [18, 34, 78], [26, 52, 104], [38, 80, 140],
        [214, 200, 150], [196, 186, 140], [150, 168, 110],
        [96, 138, 82], [110, 140, 80], [150, 150, 145], [236, 238, 240],
    ], dtype=np.float64)
    lut_y = np.arange(-70, 330, 1.0)
    lut = np.stack([np.interp(lut_y, anchors, colors[:, c]) for c in range(3)], axis=-1)

    order = np.argsort(YR, axis=None)
    for idx in order:
        j, i = divmod(int(idx), gw)
        if i + 1 >= gw or j + 1 >= gh:
            continue
        poly = [
            (SXP[j, i], SYP[j, i]), (SXP[j, i + 1], SYP[j, i + 1]),
            (SXP[j + 1, i + 1], SYP[j + 1, i + 1]), (SXP[j + 1, i], SYP[j + 1, i]),
        ]
        ymean = (hh[j, i] + hh[j, i + 1] + hh[j + 1, i + 1] + hh[j + 1, i]) / 4.0
        yi = int(np.clip(ymean + 70, 0, len(lut_y) - 1))
        base = lut[yi]
        dzdx = (hh[j, i + 1] - hh[j, i]) / max(ds, 1)
        dzdy = (hh[j + 1, i] - hh[j, i]) / max(ds, 1)
        n = np.array([-dzdx * 2.0, -dzdy * 2.0, 1.0])
        n /= max(np.linalg.norm(n), 1e-9)
        lit = float(np.clip(np.dot(n, sun), 0.0, 1.0))
        water = ymean < sea
        shade = (0.66 + 0.34 * lit) if water else (0.32 + 0.76 * lit)
        rgb = np.clip(base * shade, 0, 255)
        draw.polygon(poly, fill=tuple(int(v) for v in rgb))
    return canvas


def main(argv):
    if len(argv) != 2:
        sys.stderr.write('usage: python tools/render-unified-world.py <root> <dest>\n')
        return 2
    root, dest = Path(argv[0]), Path(argv[1])
    try:
        meta = json.loads((root / 'meta.json').read_text(encoding='utf-8'))
        size = int(meta['size']); step = int(meta['step'])
        mod = load_renderer()
        sea = float(mod.SEA)
        dest.mkdir(parents=True, exist_ok=True)

        p = root / 'Y-world.f64be'
        need(p.is_file(), 'missing %s' % p)
        g = np.frombuffer(p.read_bytes(), dtype='>f8').astype(np.float64).reshape(size, size)
        need(np.isfinite(g).all(), 'Y-world has non-finite')

        water = 100.0 * float((g < sea).mean())
        lines = [
            'seed=%s｜%d² 采样、步长 %d 块，覆盖 %d×%d 块｜中心 (%.0f, %.0f)'
            % (meta['seed'], size, step, size * step, size * step,
               meta['center'][0], meta['center'][1]),
            '★ 统一世界地形：内部类（陆地 8 / 海岸 5 / 海洋 5）+ 过渡类，拼图式组合。',
            '  选族由 eLand（陆/海）与 d（离岸距离）决定；区内由 regionId 选具体类型；',
            '  edge 控制"纯类型 ↔ 边界过渡"的权重。',
            '实测：水面 %.1f%%，海拔 %.0f ~ %.0f 格。' % (water, g.min(), g.max()),
        ]

        # ---------- 全图
        img = render_better(g, step, sea)
        pad, tail = 40, 56
        line_y, line_dy = 70, 26
        head = line_y + line_dy * len(lines) + 24
        cw = max(img.width + pad * 2, 1400)
        canvas = Image.new('RGB', (cw, head + img.height + tail), BG)
        d = ImageDraw.Draw(canvas)
        d.text((pad, 14), '统一世界地形｜1401² 全图',
               font=fit('统一世界地形｜1401² 全图', cw - pad * 2, 34), fill=INK)
        for i, t in enumerate(lines):
            d.text((pad, line_y + i * line_dy), t, font=fit(t, cw - pad * 2, 18), fill=DIM)
        canvas.paste(img, (pad, head))
        d.text((pad, head + img.height + 14),
               '原生 1401² 像素，1 像素 = %d 块，未缩放；同光照、同色带。' % step,
               font=font(18), fill=DIM)
        canvas.save(dest / 'world-full.png', optimize=True)

        # ---------- 3D
        im3 = render_3d(g, sea)
        cw2 = max(im3.width + pad * 2, 1400)
        canvas2 = Image.new('RGB', (cw2, head + im3.height + tail), BG)
        d2 = ImageDraw.Draw(canvas2)
        d2.text((pad, 14), '统一世界地形｜3D 等轴测（俯视 42°，方位 35°）',
                font=fit('统一世界地形｜3D 等轴测', cw2 - pad * 2, 34), fill=INK)
        for i, t in enumerate(lines):
            d2.text((pad, line_y + i * line_dy), t, font=fit(t, cw2 - pad * 2, 18), fill=DIM)
        canvas2.paste(im3, (pad, head))
        d2.text((pad, head + im3.height + 14),
                '等轴测投影（非游戏截图）；垂直按全图范围缩放（vz=1.4），水下叠淡蓝。',
                font=font(18), fill=DIM)
        canvas2.save(dest / 'world-3d.png', optimize=True)

        # ---------- 剖面（几条横线）
        nrows = 6
        pw, ph = 1400, 120
        gap, tail2 = 24, 56
        head2 = line_y + line_dy * len(lines) + 40
        cw3 = pad * 2 + pw
        ch3 = head2 + nrows * (ph + gap) + tail2
        canvas3 = Image.new('RGB', (cw3, ch3), BG)
        d3 = ImageDraw.Draw(canvas3)
        d3.text((pad, 14), '统一世界地形｜横剖面（6 条，等距取样）',
                font=fit('统一世界地形｜横剖面', cw3 - pad * 2, 30), fill=INK)
        for i, t in enumerate(lines):
            d3.text((pad, line_y + i * line_dy), t, font=fit(t, cw3 - pad * 2, 18), fill=DIM)
        for k in range(nrows):
            j = int((k + 0.5) / nrows * size)
            row = g[j]
            y0 = head2 + k * (ph + gap)
            lo, hi = float(row.min()), float(row.max())
            span = max(hi - lo, 1.0)
            lo -= span * 0.10
            hi += span * 0.10
            bx, bw = pad + 120, pw - 120
            d3.rectangle((bx, y0, bx + bw, y0 + ph), outline='#c9c5b6')
            if lo <= sea <= hi:
                yy = y0 + ph - int((sea - lo) / (hi - lo) * ph)
                d3.line((bx, yy, bx + bw, yy), fill='#2e5ca8', width=2)
            pts = []
            for i in range(0, len(row), max(1, len(row) // bw)):
                px = bx + int(i / (len(row) - 1) * bw)
                py = y0 + ph - int((row[i] - lo) / (hi - lo) * ph)
                pts.append((px, max(y0, min(y0 + ph, py))))
            if len(pts) > 1:
                d3.line(pts, fill='#7a4a1e', width=2)
            zc = meta['center'][1] + (j - size / 2.0) * step
            d3.text((pad, y0 + 2), 'z=%.0f' % zc, font=font(14), fill=INK)
            d3.text((pad, y0 + 22), 'Y %d..%d' % (int(hi), int(lo)), font=font(12), fill=DIM)
            d3.text((pad, y0 + 40), '水 %.0f%%' % (100.0 * float((row < sea).mean())),
                    font=font(12), fill=DIM)
        d3.text((pad, ch3 - tail2 + 14),
                '蓝线 = 海平面；棕线 = 地形。看的是"陆架→海岸→内陆"的衔接是否连续。',
                font=font(17), fill=DIM)
        canvas3.save(dest / 'world-profile.png', optimize=True)

        # ---------- 高度分层图（判断"白色区域"是雪线还是地形问题）
        #
        # ★ 分带必须【以海平面为基准】（2026-11-01 修正）
        #   首版写 (-64, 0) 当"水下"，但本项目 HeightCurve 的映射是
        #     e ∈ [-1,0] → Y ∈ [minY=-64, seaLevel=63]
        #   即 <b>Y &lt; 63 才是水下</b>，Y=0 在水下 63 格深处。
        #   用 0 当分界 ⇒ "水下"只剩 Y&lt;0 那一小片（实测 0.04%），
        #   与 UnifiedWorld 报的 water=30% 自相矛盾 —— 是分带定义错，不是地形错。
        BANDS = [
            (-64, sea, (40, 70, 130), '< %.0f（水下）' % sea),
            (sea, sea + 20, (222, 208, 158), '%.0f–%.0f 滩/低地' % (sea, sea + 20)),
            (sea + 20, sea + 60, (176, 196, 130), '%.0f–%.0f 平原' % (sea + 20, sea + 60)),
            (sea + 60, sea + 100, (120, 160, 96), '%.0f–%.0f 丘陵' % (sea + 60, sea + 100)),
            (sea + 100, sea + 140, (100, 120, 80), '%.0f–%.0f 低山' % (sea + 100, sea + 140)),
            (sea + 140, sea + 180, (140, 130, 116), '%.0f–%.0f 中山' % (sea + 140, sea + 180)),
            (sea + 180, sea + 220, (170, 165, 158), '%.0f–%.0f 高山' % (sea + 180, sea + 220)),
            (sea + 220, sea + 260, (215, 215, 212), '%.0f–%.0f 雪线附近' % (sea + 220, sea + 260)),
            (sea + 260, 400, (250, 250, 252), '> %.0f 峰顶' % (sea + 260)),
        ]
        band_img = np.zeros(g.shape + (3,), dtype=np.uint8)
        counts = []
        for lo, hi, rgb, label in BANDS:
            m = (g >= lo) & (g < hi)
            band_img[m] = rgb
            counts.append((label, int(m.sum()), 100.0 * float(m.mean())))
        band_img[g >= 400] = (255, 255, 255)
        band_image = Image.fromarray(band_img)

        items = [(np.array(rgb, float),
                  '%s｜%d px (%.2f%%)' % (label, cnt, pct))
                 for (lo, hi, rgb, label), (lb, cnt, pct) in zip(BANDS, counts)]
        items.append((np.array([255, 255, 255], float), '> 400｜兜底'))

        legend_pad, legend_w, row = 40, 760, 44
        line_y, line_dy = 70, 26
        water_px = counts[0][1]
        lg_lines = [
            '★ 分带以【海平面 Y=%.0f】为基准（本项目 HeightCurve: e∈[-1,0] → Y∈[-64,%.0f]）。'
            % (sea, sea),
            '  用途：判断"白色环"是地形问题还是渲染色带问题。',
            '  · 白色落在 %.0f+ 带 ⇒ 真雪线（地形本身高）' % (sea + 220),
            '  · 白色散落在 %.0f–%.0f 带 ⇒ 色带把低地染白了（渲染问题）'
            % (sea, sea + 100),
            '实测：水下列 %d px (%.2f%%)，Y 范围 %.0f ~ %.0f 格。'
            % (water_px, 100.0 * water_px / g.size, g.min(), g.max()),
        ]
        lg_head = line_y + line_dy * len(lg_lines) + 24
        lg_w = legend_pad * 2 + band_image.width + legend_w
        lg_h = lg_head + max(band_image.height, len(items) * row) + 60
        lg = Image.new('RGB', (lg_w, lg_h), BG)
        dl = ImageDraw.Draw(lg)
        dl.text((legend_pad, 14), '统一世界地形｜高度分层（每 20~40 格一带）',
                font=fit('统一世界地形｜高度分层', lg_w - legend_pad * 2, 32), fill=INK)
        for i, t in enumerate(lg_lines):
            dl.text((legend_pad, line_y + i * line_dy), t,
                    font=fit(t, lg_w - legend_pad * 2, 18), fill=DIM)
        lg.paste(band_image, (legend_pad, lg_head))
        x0 = legend_pad + band_image.width + 30
        for i, (color, text) in enumerate(items):
            y = lg_head + i * row
            dl.rectangle((x0 - 2, y + 4, x0 + 36, y + 38), fill='#8f8b7e')
            dl.rectangle((x0, y + 6, x0 + 34, y + 36),
                         fill=tuple(int(v) for v in color))
            dl.text((x0 + 46, y + 10), text,
                    font=fit(text, lg_w - x0 - 46 - legend_pad, 19), fill=INK)
        dl.text((legend_pad, lg_h - 44),
                '分带按高度硬切（不是地形问题诊断本身），只为了让"白色区域属于哪一带"一眼可读。',
                font=font(17), fill=DIM)
        lg.save(dest / 'world-height-bands.png', optimize=True)

        print('rendered 4 figures -> %s' % dest)
        return 0
    except InputError as exc:
        sys.stderr.write('FAIL %s\n' % exc)
        return 1
    except (IOError, OSError) as exc:
        sys.stderr.write('IO ERROR %s\n' % exc)
        return 2


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
