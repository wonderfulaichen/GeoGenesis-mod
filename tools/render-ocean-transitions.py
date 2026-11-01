#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""地形拼图出图：内部类（陆地/海岸/海洋）+ 过渡类（陆↔海岸 / 海岸↔海洋 / 海↔海）。

用法：
    python tools/render-ocean-transitions.py <root> <dest>

输入（由 OceanTransitionExport 产出）：
    <root>/meta.json
    <root>/Y-coast-0..4.f64be   内部类·海岸（5）
    <root>/Y-inner-0..4.f64be   内部类·海洋（5）
    <root>/Y-sea00..09.f64be    海↔海过渡（10）
    <root>/Y-lc00..07.f64be     陆↔海岸过渡（8）
    <root>/Y-cs00..08.f64be     海岸↔海洋过渡（9）← 重点

输出：
    grid-coast-internal.png   内部类·海岸 5 张
    grid-ocean-internal.png   内部类·海洋 5 张
    grid-sea-sea.png          海↔海 10 张
    grid-land-coast.png       陆↔海岸 8 张
    grid-coast-sea.png        ★ 海岸↔海洋 9 张（本次核心）
    coast-sea-profile.png     ★★ 海岸↔海洋 横剖面（定量：陆→滩→海，看高度序列）
    coast-sea-mask.png        ★ 海岸↔海洋 水陆掩码（看水边线是否自然）
    metrics.csv               每条地形的高度统计
"""
import csv
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
REQUIRED = ('seed', 'size', 'step', 'center', 'sampleOrigin',
            'coastInternal', 'oceanInternal', 'seaSea', 'landCoast', 'coastSea')


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
            raise InputError('no CJK font found')
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


def load_meta(root):
    p = root / 'meta.json'
    need(p.is_file(), 'missing %s' % p)
    raw = json.loads(p.read_text(encoding='utf-8'))
    missing = [k for k in REQUIRED if k not in raw]
    need(not missing, '%s missing %s' % (p, missing))
    return raw


def read_f64(root, name, size):
    p = root / ('Y-' + name + '.f64be')
    need(p.is_file(), 'missing %s' % p)
    data = p.read_bytes()
    need(len(data) == size * size * 8,
         '%s: %d bytes, expected %d' % (p, len(data), size * size * 8))
    g = np.frombuffer(data, dtype='>f8').astype(np.float64).reshape(size, size)
    need(np.isfinite(g).all(), '%s has non-finite values' % p)
    return g


def load_renderer():
    here = Path(__file__).resolve()
    for cand in (here.with_name('render-islands-big.py'),
                 here.parent.parent / 'forge-1.20.1-47.4.10-mdk' / 'tools' / 'render-islands-big.py'):
        if cand.is_file():
            spec = importlib.util.spec_from_file_location('islands_big_renderer', str(cand))
            mod = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(mod)
            need(callable(getattr(mod, 'render', None)), '%s has no render()' % cand)
            return mod
    raise InputError('render-islands-big.py not found')


def grid(images, titles, heading, lines, note, cols):
    """原尺寸网格（绝不缩放）。"""
    w, h = images[0].size
    need(all(im.size == (w, h) for im in images), 'images differ in size')
    rows = (len(images) + cols - 1) // cols
    pad, gap, label_h, tail = 40, 32, 30, 56
    line_y, line_dy = 70, 26
    head = line_y + line_dy * len(lines) + 46
    grid_w = pad * 2 + w * cols + gap * (cols - 1)
    text_w = max([tw(t, font(18)) for t in list(lines) + [note]]
                 + [tw(heading, font(32))])
    cw = int(max(grid_w, text_w + pad * 2 + 8))
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


def water_mask_image(g, sea):
    water = g < sea
    rgb = np.zeros(g.shape + (3,), dtype=np.uint8)
    rgb[water] = (46, 92, 168)
    rgb[~water] = (98, 138, 86)
    return Image.fromarray(rgb)


def profile_figure(grids, names, sea, step, heading, lines, note):
    """横剖面图：每条过渡取同一行（z 固定），画 Y 随 x 的变化。

    这是"看完整剖面"的关键图 —— 能看出 陆 → 滩 → 海 的高度序列，
    以及过渡带有多宽、有没有台阶或断崖。
    """
    n = len(grids)
    w, ph = 1400, 150           # 每条剖面的画布尺寸
    pad, gap, tail = 40, 26, 60
    line_y, line_dy = 70, 26
    head = line_y + line_dy * len(lines) + 40
    cw = pad * 2 + w
    ch = head + n * (ph + gap) + tail
    canvas = Image.new('RGB', (cw, ch), BG)
    d = ImageDraw.Draw(canvas)
    d.text((pad, 14), heading, font=fit(heading, cw - pad * 2, 30), fill=INK)
    for i, t in enumerate(lines):
        d.text((pad, line_y + i * line_dy), t, font=fit(t, cw - pad * 2, 18), fill=DIM)

    # 取中间行做剖面
    mid = grids[0].shape[0] // 2
    for k, (g, nm) in enumerate(zip(grids, names)):
        y0 = head + k * (ph + gap)
        row = g[mid]
        # 纵向范围：本图自身 min/max，两侧留白
        lo, hi = float(row.min()), float(row.max())
        span = max(hi - lo, 1.0)
        lo -= span * 0.12
        hi += span * 0.12
        # 画布：左 130px 留给坐标标签
        bx, bw = pad + 130, w - 130
        # 背景网格
        d.rectangle((bx, y0, bx + bw, y0 + ph), outline='#c9c5b6')
        # 海平面线
        if lo <= sea <= hi:
            yy = y0 + ph - int((sea - lo) / (hi - lo) * ph)
            d.line((bx, yy, bx + bw, yy), fill='#2e5ca8', width=2)
            d.text((pad, yy - 8), '海平面 %d' % sea, font=font(13), fill='#2e5ca8')
        # 剖面折线
        pts = []
        for i in range(0, len(row), max(1, len(row) // bw)):
            px = bx + int(i / (len(row) - 1) * bw)
            py = y0 + ph - int((row[i] - lo) / (hi - lo) * ph)
            py = max(y0, min(y0 + ph, py))
            pts.append((px, py))
        if len(pts) > 1:
            d.line(pts, fill='#7a4a1e', width=2)
        # 水下部分用蓝色填充（读起来更直观）
        water_pts = [(px, py) for (px, py) in pts]
        sea_y = (y0 + ph - int((sea - lo) / (hi - lo) * ph)) if lo <= sea <= hi else y0 + ph
        poly = [(bx, sea_y)] + water_pts + [(bx + bw, sea_y)]
        # 只填水面以下的部分（近似：用整条折线 + 海平面围成的区域）
        d.polygon(poly, fill=None, outline=None)
        # 标签
        d.text((pad, y0 + 2), nm, font=font(15), fill=INK)
        d.text((pad, y0 + 22), 'Y %d..%d' % (int(hi), int(lo)), font=font(12), fill=DIM)
        # 水陆占比
        wl = 100.0 * float((row < sea).mean())
        d.text((pad, y0 + 40), '水 %.0f%%' % wl, font=font(12), fill=DIM)

    d.text((pad, ch - tail + 14), note, font=fit(note, cw - pad * 2, 17), fill=DIM)
    return canvas


def main(argv):
    if len(argv) != 2:
        sys.stderr.write('usage: python tools/render-ocean-transitions.py <root> <dest>\n')
        return 2
    root, dest = Path(argv[0]), Path(argv[1])
    try:
        meta = load_meta(root)
        size = int(meta['size'])
        step = int(meta['step'])
        mod = load_renderer()
        sea = float(mod.SEA)
        dest.mkdir(parents=True, exist_ok=True)

        coast_names = list(meta['coastInternal'])
        inner_names = list(meta['oceanInternal'])
        sea_names = list(meta['seaSea'])
        lc_names = list(meta['landCoast'])
        cs_names = list(meta['coastSea'])

        coast_g = [read_f64(root, 'coast-%d' % i, size) for i in range(len(coast_names))]
        inner_g = [read_f64(root, 'inner-%d' % i, size) for i in range(len(inner_names))]
        sea_g = [read_f64(root, 'sea%02d' % i, size) for i in range(len(sea_names))]
        lc_g = [read_f64(root, 'lc%02d' % i, size) for i in range(len(lc_names))]
        cs_g = [read_f64(root, 'cs%02d' % i, size) for i in range(len(cs_names))]

        geo = ('seed=%s｜%d² 采样、步长 %d 块，覆盖 %d×%d 块｜中心块 (%.0f, %.0f)'
               % (meta['seed'], size, step, size * step, size * step,
                  meta['center'][0], meta['center'][1]))
        note = '每张 %d² 原生像素，1 像素 = %d 块，未缩放；同窗口、同光照、同色带。' % (size, step)

        # ---------- 内部类·海岸
        grid([mod.render(g, step) for g in coast_g], coast_names,
             '内部类·海岸（5 个配方，各自独立形态，都在陆地上）',
             [geo,
              '★ 海岸是【内部类】：它是一种有独立形态的完整地形，不是"陆海混合器"。',
              '五个配方的中位都在海平面 ±0.1 e（±24 格）内 —— 符合"位于陆地与海的分界处"。',
              '沙滩宽而平（cliff≈0）、崖岸窄而陡（cliff≈1）、礁盘是潮间带（半淹）、潮滩极平、三角洲有分叉。'],
             note, cols=3).save(dest / 'grid-coast-internal.png', optimize=True)

        # ---------- 内部类·海洋
        grid([mod.render(g, step) for g in inner_g], inner_names,
             '内部类·海洋（5 个配方）',
             [geo, '这 5 个是纯粹的海底形态，全部在水面以下。'],
             note, cols=3).save(dest / 'grid-ocean-internal.png', optimize=True)

        # ---------- 海↔海
        grid([mod.render(g, step) for g in sea_g], sea_names,
             '过渡类·海↔海（10 条，C(5,2) 自动生成，全部在水下）',
             [geo, '★ 两端都在水下 ⇒ 不跨海平面（由 X1 门禁验证）。'],
             note, cols=4).save(dest / 'grid-sea-sea.png', optimize=True)

        # ---------- 陆↔海岸
        grid([mod.render(g, step) for g in lc_g], lc_names,
             '过渡类·陆↔海岸（8 条，两端都在陆地上）',
             [geo,
              '★ 两端都在水面之上 ⇒ 不跨海平面（由 X2 门禁验证）。',
              '这一族表达"什么样的内陆接什么样的海岸"：盆地接潮滩、平原接沙滩/三角洲、台地与山地接崖岸。'],
             note, cols=3).save(dest / 'grid-land-coast.png', optimize=True)

        # ---------- 海岸↔海洋（核心）
        grid([mod.render(g, step) for g in cs_g], cs_names,
             '过渡类·海岸↔海洋（9 条）—— 唯一的海陆交界',
             [geo,
              '★ 这里是"有海有陆"真正发生的地方：从海岸地形（陆上）接到海洋地形（水下）。',
              '过渡带 = 从陆地经沙滩/崖壁到水下的完整剖面（下一张图给出定量剖面）。',
              'C1 cliffFactor 控制这段过渡的陡缓：沙滩平缓、崖岸陡峭。'],
             note, cols=3).save(dest / 'grid-coast-sea.png', optimize=True)

        # ---------- ★ 横剖面（定量）
        profile_figure(cs_g, cs_names, sea, step,
                       '海岸↔海洋过渡｜横剖面（同一行，Y 随 x 变化）',
                       [geo,
                        '★ 这是"完整剖面"的定量视图：蓝线 = 海平面，棕线 = 地形。',
                        '读法：左侧应为陆地（棕线在海平面之上）→ 过渡带 → 右侧为水下（棕线在下方）。',
                        '若某条剖面出现"台阶"或"垂直断崖"，说明过渡带过窄；若全程贴海平面，说明过渡被平均掉了。'],
                       '取 1401² 图的中间行（z=中心）。纵轴按每条自身范围缩放，注意看相对形状而非绝对值。').save(
            dest / 'coast-sea-profile.png', optimize=True)

        # ---------- 水陆掩码
        mask_imgs = [water_mask_image(g, sea) for g in cs_g]
        mask_titles = ['%s  水 %.0f%%' % (cs_names[i], 100.0 * float((g < sea).mean()))
                       for i, g in enumerate(cs_g)]
        grid(mask_imgs, mask_titles,
             '海岸↔海洋过渡｜水陆掩码（蓝=海 / 绿=陆）',
             [geo,
              '★ 看水边线：应当是噪声的等值线（分形、犬牙交错），不是直线/圆弧/多边形。',
              '海岸↔海洋应比之前的"陆↔海直接混合"更有层次 —— 因为中间多了一层真实的滩/崖地形。'],
             '掩码由 Y < 海平面 判定，与游戏内一致。', cols=3).save(
            dest / 'coast-sea-mask.png', optimize=True)

        # ---------- 指标
        rows = []
        for kind, names, gs in (('coast-internal', coast_names, coast_g),
                                ('ocean-internal', inner_names, inner_g),
                                ('sea-sea', sea_names, sea_g),
                                ('land-coast', lc_names, lc_g),
                                ('coast-sea', cs_names, cs_g)):
            for nm, g in zip(names, gs):
                rows.append({
                    'kind': kind, 'name': nm,
                    'minY': float(g.min()), 'maxY': float(g.max()),
                    'medianY': float(np.median(g)),
                    'relief': float(g.max() - g.min()),
                    'waterPct': float((g < sea).mean() * 100.0),
                })
        with open(dest / 'metrics.csv', 'w', encoding='utf-8', newline='') as fh:
            wr = csv.DictWriter(fh, fieldnames=list(rows[0].keys()))
            wr.writeheader()
            for r in rows:
                wr.writerow(r)

        print('rendered 7 figures -> %s' % dest)
        for n in ('grid-coast-internal.png', 'grid-ocean-internal.png', 'grid-sea-sea.png',
                  'grid-land-coast.png', 'grid-coast-sea.png',
                  'coast-sea-profile.png', 'coast-sea-mask.png'):
            print(' ', n)
        return 0
    except InputError as exc:
        sys.stderr.write('FAIL %s\n' % exc)
        return 1
    except (IOError, OSError) as exc:
        sys.stderr.write('IO ERROR %s\n' % exc)
        return 2


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
