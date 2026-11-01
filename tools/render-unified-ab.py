#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""统一世界地形：选型修复的 A/B 四臂对照。

用法：
    python tools/render-unified-ab.py <armRootParent> <dest>

armRootParent 下应有 BASELINE / OCEAN_DIR / COAST_DIST / BOTH 四个子目录，
每个含 UnifiedWorld 导出的 Y-world.f64be + ocean-recipe.u8 + coast-recipe.u8 + meta.json。
"""
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

ARMS = ['BASELINE', 'OCEAN_DIR', 'COAST_DIST', 'BOTH']
TITLES = {
    'BASELINE': 'A 现状（两处都未修，= 此前那张 30.1% 水面的图）',
    'OCEAN_DIR': 'B 只修海型方向（c 越负越深）',
    'COAST_DIST': 'C 只修海岸分布（按声明权重切分）',
    'BOTH': 'D 两处都修（本轮目标）',
}
SHORT = {'BASELINE': 'A 现状', 'OCEAN_DIR': 'B 修方向',
         'COAST_DIST': 'C 修分布', 'BOTH': 'D 全修'}

OCEAN_NAMES = ['深海平原', '洋中脊', '海山群', '海底峡谷', '陆架沙脊']
COAST_NAMES = ['沙滩', '崖岸', '礁盘', '潮滩', '三角洲']

# 类型图配色（区分海型配方 / 海岸配方）
OCEAN_COLORS = np.array([
    [38, 62, 104],    # 深海平原
    [66, 108, 150],   # 洋中脊
    [96, 142, 122],   # 海山群
    [24, 36, 66],     # 海底峡谷
    [122, 168, 190],  # 陆架沙脊
], dtype=np.float64)
COAST_COLORS = np.array([
    [222, 205, 150],  # 沙滩
    [140, 116, 96],   # 崖岸
    [176, 128, 152],  # 礁盘
    [150, 170, 130],  # 潮滩
    [96, 150, 108],   # 三角洲
], dtype=np.float64)

_FONTS = {}


class InputError(Exception):
    pass


def need(cond, msg):
    if not cond:
        raise InputError(msg)


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


def shade(height, step, sea):
    """同 render-unified-world.py 的 render_better：色带 170+ 用渐进岩灰。"""
    gz, gx = np.gradient(height, step)
    norm = np.sqrt(gx * gx + gz * gz + 1)
    light = np.array([-0.5, -0.35, 1.0])
    light /= np.linalg.norm(light)
    lit = np.clip((-gx * light[0] - gz * light[1] + light[2]) / norm, 0, 1)

    anchors = np.array([-64, -40, -12, 62, 66, 76, 110, 170, 210, 250, 300, 360],
                       dtype=np.float64)
    colors = np.array([
        [16, 26, 40], [24, 40, 62], [40, 66, 92], [58, 92, 116],
        [214, 205, 168], [96, 122, 78], [132, 142, 88], [150, 136, 108],
        [170, 162, 146], [196, 192, 184], [226, 226, 224], [244, 244, 246],
    ], dtype=np.float64)
    rgb = np.stack([np.interp(height, anchors, colors[:, c]) for c in range(3)], axis=-1)
    water = height < sea
    sh = np.where(water, 0.72 + 0.28 * lit, 0.28 + 0.80 * lit)
    return np.uint8(np.clip(rgb * sh[..., None], 0, 255))


def delta_img(base_h, other_h, vmax):
    """差值图：红=更高，蓝=更低，灰=无变化。"""
    d = other_h - base_h
    t = np.clip(d / vmax, -1, 1)
    r = np.where(t > 0, 255.0, 128.0 + 127.0 * (1.0 + t))
    g = 128.0 + 127.0 * (1.0 - np.abs(t))
    b = np.where(t < 0, 255.0, 128.0 + 127.0 * (1.0 - t))
    return np.uint8(np.clip(np.stack([r, g, b], axis=-1), 0, 255))


def type_img(height, oi, ci, sea):
    """类型图：水域按海型配方上色，陆地按海岸配方上色。"""
    out = np.zeros(height.shape + (3,), dtype=np.uint8)
    land = height >= sea
    out[land] = COAST_COLORS[np.clip(ci[land], 0, 4)].astype(np.uint8)
    out[~land] = OCEAN_COLORS[np.clip(oi[~land], 0, 4)].astype(np.uint8)
    return out


def load_arm(root):
    meta = json.loads((root / 'meta.json').read_text(encoding='utf-8'))
    size = int(meta['size'])
    need((root / 'Y-world.f64be').is_file(), 'missing Y-world in %s' % root)
    h = np.frombuffer((root / 'Y-world.f64be').read_bytes(),
                      dtype='>f8').astype(np.float64).reshape(size, size)
    oi = np.frombuffer((root / 'ocean-recipe.u8').read_bytes(),
                       dtype=np.uint8).reshape(size, size)
    ci = np.frombuffer((root / 'coast-recipe.u8').read_bytes(),
                       dtype=np.uint8).reshape(size, size)
    need(np.isfinite(h).all(), 'non-finite in %s' % root)
    return meta, h, oi, ci


def share_table(meta, h, oi, ci, sea):
    """与 UnifiedWorld 控制台同口径：海域内海型占比赛。"""
    lines = []
    water = h < sea
    nsea = int(water.sum())
    lines.append('水面 %.2f%%（%d px）' % (100.0 * float(water.mean()), nsea))
    lines.append('')
    lines.append('海域内海型占比：')
    for k in range(5):
        c = int((water & (oi == k)).sum())
        lines.append('  %s %5.1f%%' % (OCEAN_NAMES[k], 100.0 * c / max(nsea, 1)))
    lines.append('')
    lines.append('海岸配方占比（全窗口）：')
    for k in range(5):
        c = int((ci == k).sum())
        lines.append('  %s %5.1f%%' % (COAST_NAMES[k], 100.0 * c / h.size))
    return lines


def main(argv):
    if len(argv) != 2:
        sys.stderr.write('usage: python tools/render-unified-ab.py <armRootParent> <dest>\n')
        return 2
    parent, dest = Path(argv[0]), Path(argv[1])
    try:
        dest.mkdir(parents=True, exist_ok=True)
        arms = {}
        prefix = ''
        for cand in ('arm-', ''):
            if (parent / (cand + ARMS[0])).is_dir():
                prefix = cand
                break
        need(prefix or (parent / ARMS[0]).is_dir(), 'no arm dirs under %s' % parent)
        for a in ARMS:
            r = parent / (prefix + a)
            need(r.is_dir(), 'missing arm dir %s' % r)
            arms[a] = load_arm(r)

        meta0, h0, oi0, ci0 = arms['BASELINE']
        size, step = int(meta0['size']), int(meta0['step'])
        sea = 63.0  # HeightCurve: e∈[-1,0] → Y∈[-64,63]

        # ---------- 1) 四臂并排俯视大图（原生分辨率）
        imgs = {a: shade(arms[a][1], step, sea) for a in ARMS}
        pad, gap = 44, 28
        head = 150
        w = pad * 2 + imgs['BASELINE'].shape[1] * 2 + gap
        hgt = head + (imgs['BASELINE'].shape[0] + 78) * 2 + gap + 64
        canvas = Image.new('RGB', (w, hgt), BG)
        d = ImageDraw.Draw(canvas)
        title = '统一世界地形｜海侧选型修复 A/B｜1401² 全图（原生，1 像素 = %d 块）' % step
        d.text((pad, 16), title, font=fit(title, w - pad * 2, 34), fill=INK)
        sub = ('A 现状 → D 全修：只剩海陆配比未变（%.1f%% → %.1f%%），'
               '但海型与海岸的分布完全改变。同光照、同色带、同窗口。'
               % (100.0 * float((arms['BASELINE'][1] < sea).mean()),
                  100.0 * float((arms['BOTH'][1] < sea).mean())))
        d.text((pad, 60), sub, font=fit(sub, w - pad * 2, 19), fill=DIM)
        for k, a in enumerate(ARMS):
            cx = pad + (k % 2) * (imgs[a].shape[1] + gap)
            cy = head + (k // 2) * (imgs[a].shape[0] + 78)
            d.text((cx, cy - 26), TITLES[a], font=fit(TITLES[a], imgs[a].shape[1], 21), fill=INK)
            canvas.paste(Image.fromarray(imgs[a]), (cx, cy))
            wt = 100.0 * float((arms[a][1] < sea).mean())
            d.text((cx, cy + imgs[a].shape[0] + 6),
                   '水 %.2f%% ｜ Y %.0f..%.0f' % (wt, arms[a][1].min(), arms[a][1].max()),
                   font=font(17), fill=DIM)
        d.text((pad, hgt - 46),
               '判据：先看 D 的浅海是否贴着海岸（A 里浅水被灌成 40 格深的深海平原）、'
               '再看沙滩/三角洲是否真的出现（A 里只有 0.07% / 0.15%）。',
               font=font(17), fill=DIM)
        canvas.save(dest / 'ab-full-4arms.png', optimize=True)

        # ---------- 2) 类型图四臂（海型配方 / 海岸配方）
        limgs = {a: type_img(arms[a][1], arms[a][2], arms[a][3], sea) for a in ARMS}
        canvas2 = Image.new('RGB', (w, hgt), BG)
        d2 = ImageDraw.Draw(canvas2)
        t2 = '海侧选型类型图｜上：海型配方　下：海岸配方（陆地着色）'
        d2.text((pad, 16), t2, font=fit(t2, w - pad * 2, 34), fill=INK)
        s2 = '海型着色的位置即"该点若被判为海，会走哪个配方"；海岸着色即"该点若在水边带，会走哪个海岸地貌"。'
        d2.text((pad, 60), s2, font=fit(s2, w - pad * 2, 19), fill=DIM)
        for k, a in enumerate(ARMS):
            cx = pad + (k % 2) * (limgs[a].shape[1] + gap)
            cy = head + (k // 2) * (limgs[a].shape[0] + 78)
            d2.text((cx, cy - 26), SHORT[a], font=font(21), fill=INK)
            canvas2.paste(Image.fromarray(limgs[a]), (cx, cy))
        # 图例
        ly = head + (imgs['BASELINE'].shape[0] + 78) * 2 + gap + 8
        lx = pad
        d2.text((lx, ly), '海型：', font=font(17), fill=INK)
        lx += 62
        for k in range(5):
            d2.rectangle((lx, ly + 2, lx + 22, ly + 20),
                         fill=tuple(int(v) for v in OCEAN_COLORS[k]))
            d2.text((lx + 28, ly), OCEAN_NAMES[k], font=font(16), fill=DIM)
            lx += 28 + int(tw(OCEAN_NAMES[k], font(16))) + 22
        lx = pad
        ly += 30
        d2.text((lx, ly), '海岸：', font=font(17), fill=INK)
        lx += 62
        for k in range(5):
            d2.rectangle((lx, ly + 2, lx + 22, ly + 20),
                         fill=tuple(int(v) for v in COAST_COLORS[k]))
            d2.text((lx + 28, ly), COAST_NAMES[k], font=font(16), fill=DIM)
            lx += 28 + int(tw(COAST_NAMES[k], font(16))) + 22
        canvas2.save(dest / 'ab-types-4arms.png', optimize=True)

        # ---------- 3) 差值图（相对 A）：B / C / D
        dh = {a: arms[a][1] - h0 for a in ARMS}
        vmax = max(1.0, float(np.abs(dh['BOTH']).max()))
        d3w = pad * 2 + imgs['BASELINE'].shape[1] * 3 + gap * 2
        d3h = head + imgs['BASELINE'].shape[0] + 110
        canvas3 = Image.new('RGB', (d3w, d3h), BG)
        d3d = ImageDraw.Draw(canvas3)
        t3 = '与 A（现状）的高度差｜红 = 变高，蓝 = 变低，灰 = 不变'
        d3d.text((pad, 16), t3, font=fit(t3, d3w - pad * 2, 34), fill=INK)
        for k, a in enumerate(['OCEAN_DIR', 'COAST_DIST', 'BOTH']):
            cx = pad + k * (imgs['BASELINE'].shape[1] + gap)
            im = delta_img(h0, arms[a][1], vmax)
            d3d.text((cx, head - 28), TITLES[a], font=fit(TITLES[a], imgs[a].shape[1], 19),
                     fill=INK)
            canvas3.paste(Image.fromarray(im), (cx, head))
            dd = dh[a]
            d3d.text((cx, head + im.shape[0] + 8),
                     '|Δ| max %.0f 格｜P99 %.1f｜变化点 %.1f%%'
                     % (float(np.abs(dd).max()), float(np.percentile(np.abs(dd), 99)),
                        100.0 * float((np.abs(dd) > 0.5).mean())),
                     font=font(16), fill=DIM)
        d3d.text((pad, d3h - 44),
                 '色标满量程 ±%.0f 格（三种修改共用，可直接比较幅度）。' % vmax,
                 font=font(17), fill=DIM)
        canvas3.save(dest / 'ab-delta.png', optimize=True)

        # ---------- 4) 统计表（A / B / C / D 各一列）
        tables = {a: share_table(*arms[a], sea) for a in ARMS}
        rowh = 26
        nrow = max(len(tables[a]) for a in ARMS)
        twidth = 520
        tw4 = pad * 2 + twidth * 4
        th = head + nrow * rowh + 90
        canvas4 = Image.new('RGB', (tw4, th), BG)
        d4 = ImageDraw.Draw(canvas4)
        t4 = '选型分布统计｜同一扇 1401² 窗口'
        d4.text((pad, 16), t4, font=fit(t4, tw4 - pad * 2, 34), fill=INK)
        for k, a in enumerate(ARMS):
            cx = pad + k * twidth
            d4.text((cx, head - 34), SHORT[a], font=font(22), fill=INK)
            for i, line in enumerate(tables[a]):
                col = INK if line.endswith('：') else DIM
                d4.text((cx, head + i * rowh), line, font=font(17), fill=col)
        d4.text((pad, th - 62),
                '注意："海域内海型占比"只在真的判为海的点上统计 —— 全窗口占比会被陆地面积稀释，'
                '两处修复后全窗口水面几乎不变（%.1f%% → %.1f%%），'
                '这正说明它们改的是【分布】而不是【总量】。'
                % (100.0 * float((arms['BASELINE'][1] < sea).mean()),
                   100.0 * float((arms['BOTH'][1] < sea).mean())),
                font=fit('x', tw4 - pad * 2, 17), fill=DIM)
        canvas4.save(dest / 'ab-stats.png', optimize=True)

        # ---------- 5) 剖面（D vs A，6 条等距横切）
        nrows = 6
        pw, ph = 1400, 130
        gap2, tail2 = 22, 60
        head2 = 110
        cw5 = pad * 2 + pw
        ch5 = head2 + nrows * (ph * 2 + gap2 * 2) + tail2
        canvas5 = Image.new('RGB', (cw5, ch5), BG)
        d5 = ImageDraw.Draw(canvas5)
        t5 = '横剖面 A（灰）vs D（棕）｜6 条等距取样，看海床深度与岸坡'
        d5.text((pad, 16), t5, font=fit(t5, cw5 - pad * 2, 32), fill=INK)
        for k in range(nrows):
            j = int((k + 0.5) / nrows * size)
            ra, rd = h0[j], arms['BOTH'][1][j]
            lo = min(float(ra.min()), float(rd.min())) - 8
            hi = max(float(ra.max()), float(rd.max())) + 8
            for m, (row, color, tag) in enumerate(((ra, '#8a8f9c', 'A'), (rd, '#7a4a1e', 'D'))):
                y0 = head2 + k * (ph * 2 + gap2 * 2) + m * (ph + 2)
                bx, bw = pad + 110, pw - 110
                d5.rectangle((bx, y0, bx + bw, y0 + ph), outline='#c9c5b6')
                if lo <= sea <= hi:
                    yy = y0 + ph - int((sea - lo) / (hi - lo) * ph)
                    d5.line((bx, yy, bx + bw, yy), fill='#2e5ca8', width=2)
                pts = []
                stride = max(1, len(row) // bw)
                for i in range(0, len(row), stride):
                    px = bx + int(i / (len(row) - 1) * bw)
                    py = y0 + ph - int((row[i] - lo) / (hi - lo) * ph)
                    pts.append((px, max(y0, min(y0 + ph, py))))
                if len(pts) > 1:
                    d5.line(pts, fill=color, width=2)
                if m == 0:
                    zc = meta0['center'][1] + (j - size / 2.0) * step
                    d5.text((pad, y0 + 2), 'z=%.0f' % zc, font=font(14), fill=INK)
                    d5.text((pad, y0 + 30), '水 A %.0f%% / D %.0f%%'
                            % (100.0 * float((ra < sea).mean()),
                               100.0 * float((rd < sea).mean())),
                            font=font(12), fill=DIM)
                d5.text((pad, y0 + ph - 18), tag, font=font(14), fill=color)
        d5.text((pad, ch5 - 44),
                '蓝线 = 海平面 Y=%.0f。重点：A 的海底几乎是一条直线（深海平原 55.9%%），'
                'D 才有近岸浅、远海深的梯级。' % sea,
                font=font(17), fill=DIM)
        canvas5.save(dest / 'ab-profile.png', optimize=True)

        print('rendered 5 figures -> %s' % dest)
        return 0
    except InputError as exc:
        sys.stderr.write('FAIL %s\n' % exc)
        return 1
    except (IOError, OSError) as exc:
        sys.stderr.write('IO ERROR %s\n' % exc)
        return 2


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
