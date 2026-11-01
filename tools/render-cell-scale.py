#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""区域格间距单变量对照（1200 vs 600）｜1401² 原生大图 + ROI 裁剪 + 剖面（只读）。

用法：python tools/render-cell-scale.py <root> <dest>

<root>/arm-1200/、<root>/arm-600/（导出方产出，本工具只读、不做世界生成）：
    height.f64be     大端 float64 size²（HeightCurve 的 Y）
    type.u8          主导配方 id 0..22（0..17 陆地 RecipeMorphCatalog，18..22 海洋 OceanRecipeCatalog）
    active.u8        参与配方数（1 = 纯条目，>1 = 过渡）
    sea-weight.f64be 大端 float64 size²（本工具只校验长度/有限性并记录统计，不出图）
    meta.json        size/step/origin(或 sampleOrigin)/center/scale；可选 seaLevel/bandTotal/coastProcessing/jvmProperties
<root>/profiles.csv  列 line,x,z,a,b[,distance]；full-* = 6 条横剖面，local-* = 固定 z 的 x 扫描（步长 1 块）

输出（<dest>，全部 1:1 原生，绝不缩放）：
    native-a1200.png / native-b600.png              两臂高度原生图
    native-types-a1200.png / native-types-b600.png  两臂类型原生图（23 类配色）
    pair-full.png / pair-types.png                  两臂并排（原生格 + 类型图例 + ROI 位置示意）
    pair-local-height.png / pair-local-types.png    ROI 像素框 (0,0,620,660) 同坐标同比例裁剪
    profiles-full.png                                6 条 full 剖面共用 Y ∈ [-64, 320]
    profiles-local.png                               local 三线共用 Y（本图内实测范围）
    metrics.json                                     水面 / 类型占比 / 纯条目 vs 过渡 / active 计数 / 绝对差

★ 复用（不复制）：render-unified-ab.py 的 shade（同光照同色带）、render-full-morph.py 的
  entry_colors/ENTRY_CN/grid_panel（同陆地配色与版式），海洋 5 类取自 render-unified-ab.py。
★ 判据：本工具只画导出 raw，不断言"好不好看"，也不断言任何生产性能；逐位基准由主代理校验。
"""
import csv
import hashlib
import importlib.util
import json
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

STEMS = ('arm-1200', 'arm-600')
SHORT = {'arm-1200': 'a1200', 'arm-600': 'b600'}
TITLE = {'arm-1200': 'A 区域格 %g（当前 P3）', 'arm-600': 'B 区域格 %g（只改区域格间距）'}
TYPE_HI = 22
ROI = (0, 0, 620, 660)
YLIM_FULL = (-64.0, 320.0)
A_COLOR, B_COLOR = '#2f5fa8', '#a8542c'


class InputError(Exception):
    """输入不合规 / IO 失败：非零退出，且不产出任何图。"""


def need(cond, msg):
    if not cond:
        raise InputError(msg)


def load_tools():
    """importlib 导入同目录两个既有渲染工具，只取其函数与常量（不改动、不复制）。"""
    here = Path(__file__).resolve().parent
    mods = {}
    for key, name in (('u', 'render-unified-ab.py'), ('m', 'render-full-morph.py')):
        path = here / name
        need(path.is_file(), 'missing %s' % path)
        spec = importlib.util.spec_from_file_location('gg_' + key, str(path))
        module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
        mods[key] = module
    uni, mor = mods['u'], mods['m']
    for attr in ('shade', 'font', 'fit', 'OCEAN_COLORS', 'OCEAN_NAMES'):
        need(getattr(uni, attr, None) is not None, 'render-unified-ab.py lacks %s' % attr)
    for attr in ('entry_colors', 'ENTRY_CN', 'grid_panel'):
        need(getattr(mor, attr, None) is not None, 'render-full-morph.py lacks %s' % attr)
    need(len(mor.ENTRY_CN) + len(uni.OCEAN_NAMES) == TYPE_HI + 1,
         'type name table does not cover 0..%d' % TYPE_HI)
    return uni, mor


def palette(uni, mor):
    """23 类配色：18 陆地条目（复用 entry_colors）+ 5 海洋条目（复用 OCEAN_COLORS）。"""
    land = np.asarray(mor.entry_colors(), dtype=float)
    ocean = np.asarray(uni.OCEAN_COLORS, dtype=float)
    need(land.shape == (18, 3) and ocean.shape == (5, 3), 'palette shape %r %r' % (land.shape, ocean.shape))
    return np.vstack([land, ocean]), list(mor.ENTRY_CN) + list(uni.OCEAN_NAMES)


def meta_of(path):
    need(path.is_file(), 'missing %s' % path)
    try:
        raw = json.loads(path.read_text(encoding='utf-8'))
    except ValueError as exc:
        raise InputError('%s is not valid JSON: %s' % (path, exc))
    need(isinstance(raw, dict), '%s must be a JSON object' % path)
    for key in ('size', 'step', 'center', 'scale'):
        need(key in raw, '%s missing %r' % (path, key))
    origin = raw.get('origin', raw.get('sampleOrigin'))
    need(origin is not None, '%s missing origin/sampleOrigin' % path)
    out = {'size': int(raw['size']), 'step': float(raw['step']), 'scale': float(raw['scale']),
           'center': [float(v) for v in raw['center']], 'origin': [float(v) for v in origin],
           'sea': float(raw.get('seaLevel', 63.0)), 'band': float(raw.get('bandTotal', 240.0)),
           'coast': raw.get('coastProcessing', False), 'keys': sorted(raw)}
    need(len(out['center']) == 2 and len(out['origin']) == 2, '%s center/origin not 2-D' % path)
    need(out['size'] > 0 and out['step'] > 0 and out['scale'] > 0, '%s has non-positive size/step/scale' % path)
    return out


def geometry(root):
    """两臂 meta 必须同窗口（size/step/origin/center/sea/band），只允许 scale 不同。"""
    meta = {s: meta_of(root / s / 'meta.json') for s in STEMS}
    base = meta[STEMS[0]]
    for stem in STEMS[1:]:
        for key in ('size', 'step', 'origin', 'center', 'sea', 'band'):
            need(meta[stem][key] == base[key], '%s.%s=%r != %s.%s=%r（不是单变量）'
                 % (stem, key, meta[stem][key], STEMS[0], key, base[key]))
    need(meta[STEMS[0]]['scale'] > meta[STEMS[1]]['scale'],
         'scale 顺序反常：%r' % {s: meta[s]['scale'] for s in STEMS})
    need(all(not meta[s]['coast'] for s in STEMS),
         '某臂 meta 声明 coastProcessing=true，与"两臂均无特殊海岸"口径冲突')
    return base, meta


def read_bytes(path, expected):
    need(path.is_file(), 'missing %s' % path)
    data = path.read_bytes()
    need(len(data) == expected, '%s: %d bytes, expected %d' % (path, len(data), expected))
    return data


def read_f64(path, size):
    raw = read_bytes(path, size * size * 8)
    grid = np.frombuffer(raw, dtype='>f8').astype(np.float64).reshape(size, size)
    need(np.isfinite(grid).all(), '%s has %d non-finite values' % (path, int((~np.isfinite(grid)).sum())))
    return grid, hashlib.sha256(raw).hexdigest()


def read_u8(path, size, lo, hi):
    raw = read_bytes(path, size * size)
    grid = np.frombuffer(raw, dtype=np.uint8).reshape(size, size)
    need(int(grid.min()) >= lo and int(grid.max()) <= hi,
         '%s: ids %d..%d outside %d..%d' % (path, grid.min(), grid.max(), lo, hi))
    return grid, hashlib.sha256(raw).hexdigest()


def load_arm(folder, size):
    out, sha = {}, {}
    for name, reader, args in (('height.f64be', read_f64, (size,)),
                               ('sea-weight.f64be', read_f64, (size,)),
                               ('type.u8', read_u8, (size, 0, TYPE_HI)),
                               ('active.u8', read_u8, (size, 1, 255))):
        out[name] = reader(folder / name, *args)
        sha[name] = out[name][1]
    return {'height': out['height.f64be'][0], 'sea': out['sea-weight.f64be'][0],
            'type': out['type.u8'][0], 'active': out['active.u8'][0], 'sha256': sha}


def type_image(types, pal):
    return np.uint8(np.clip(pal[np.clip(types, 0, TYPE_HI)], 0, 255))


def stats(arm, sea, names):
    h, t, a, w = arm['height'], arm['type'], arm['active'], arm['sea']
    n = float(h.size)
    counts = np.bincount(t.ravel(), minlength=TYPE_HI + 1)
    hist = np.bincount(a.ravel())
    return {'waterPct': 100.0 * float((h < sea).mean()),
            'minY': float(h.min()), 'maxY': float(h.max()), 'medianY': float(np.median(h)),
            'typeSharesPct': {names[i]: round(100.0 * float(c) / n, 4) for i, c in enumerate(counts)},
            'typeDistinct': int((counts > 0).sum()),
            'absentTypeIds': [int(i) for i in np.nonzero(counts == 0)[0]],
            'pureEntryPct': 100.0 * float((a == 1).mean()),
            'transitionPct': 100.0 * float((a > 1).mean()),
            'active': {'min': int(a.min()), 'max': int(a.max()), 'distinct': int((hist > 0).sum()),
                       'histogram': [[int(i), int(c)] for i, c in enumerate(hist) if c]},
            'seaWeight': {'min': float(w.min()), 'max': float(w.max()), 'mean': float(w.mean())}}


def diff_stats(a, b, ta, tb, sea):
    d = b - a
    ab = np.abs(d)
    return {'maxAbsDiff': float(ab.max()), 'medianAbsDiff': float(np.median(ab)),
            'p95AbsDiff': float(np.percentile(ab, 95)), 'p99AbsDiff': float(np.percentile(ab, 99)),
            'meanSignedDiff': float(d.mean()),
            'maxRise': float(max(0.0, d.max())), 'maxDrop': float(min(0.0, d.min())),
            'changedPixels': int((ab > 0).sum()), 'changedOver0p5Pct': 100.0 * float((ab > 0.5).mean()),
            'landToSea': int(((a >= sea) & (b < sea)).sum()), 'seaToLand': int(((a < sea) & (b >= sea)).sum()),
            'waterDeltaPts': 100.0 * float((b < sea).mean() - (a < sea).mean()),
            'typeChangedPixels': int((ta != tb).sum()),
            'typeChangedPct': 100.0 * float((ta != tb).mean())}


def caption(geo, meta):
    a, b = STEMS
    return ['臂 A = 区域格 %g（当前 P3）｜臂 B = 区域格 %g：两臂只有"区域格间距"一个变量，'
            '分布、地形参数、%g 块过渡带宽度全部固定。'
            % (meta[a]['scale'], meta[b]['scale'], geo['band']),
            '%d² 原生、步长 %g 块（1 像素 = %g 块）、不缩放｜中心块 (%.0f, %.0f)｜原点 (%.0f, %.0f)｜两臂均无特殊海岸。'
            % (geo['size'], geo['step'], geo['step'], geo['center'][0], geo['center'][1],
               geo['origin'][0], geo['origin'][1])]


def append_band(canvas, bg, extra, painter):
    """在原生图下方追加一条说明带（绝不覆盖、绝不缩放已贴的原生格）。"""
    out = Image.new('RGB', (canvas.width, canvas.height + extra), bg)
    out.paste(canvas, (0, 0))
    painter(ImageDraw.Draw(out), canvas.width, canvas.height)
    return out


def draw_roi(draw, width, top, geo, uni):
    side, left = 96, 40
    x0, y0, x1, y1 = ROI
    scale = side / float(geo['size'])
    ox, oz, step = geo['origin'][0], geo['origin'][1], geo['step']
    draw.rectangle((left, top + 10, left + side, top + 10 + side), outline='#8f8b7e')
    draw.rectangle((left, top + 10, left + side, top + 10 + side), fill='#efece2')
    draw.rectangle((left + x0 * scale, top + 10 + y0 * scale,
                    left + x1 * scale, top + 10 + y1 * scale), fill='#25261f')
    text = ('ROI = 像素框 x %d..%d、z %d..%d（左上闭区间）= 块 x %.1f..%.1f / z %.1f..%.1f'
            '（%d×%d 像素 × %g 块）｜左侧示意：浅框 = 全幅 %d²，黑块 = ROI。'
            % (x0, x1 - 1, y0, y1 - 1, ox + x0 * step, ox + (x1 - 1) * step,
               oz + y0 * step, oz + (y1 - 1) * step, x1 - x0, y1 - y0, step, geo['size']))
    draw.text((left + side + 24, top + 18), text, font=uni.fit(text, width - left - side - 64, 17),
              fill=uni.INK)


def draw_legend(draw, width, top, pal, names, uni):
    cols, row_h, left = 3, 30, 40
    rows = (len(names) + cols - 1) // cols
    col_w = max(320, (width - left * 2) // cols)
    for i, name in enumerate(names):
        x = left + (i // rows) * col_w
        y = top + 10 + (i % rows) * row_h
        draw.rectangle((x, y + 2, x + 22, y + 20), fill=tuple(int(v) for v in pal[i]), outline='#8f8b7e')
        draw.text((x + 30, y), '%2d %s' % (i, name), font=uni.font(15), fill=uni.INK)
    text = ('类型 id = 该像素的主导配方（0..17 陆地 RecipeMorphCatalog 顺序 + 18..22 海洋 '
            'OceanRecipeCatalog 顺序）；只表示主导，不代表全部参与候选。')
    draw.text((left, top + 10 + rows * row_h + 4), text,
              font=uni.fit(text, width - left * 2, 16), fill=uni.DIM)


def read_profiles(path):
    need(path.is_file(), 'missing %s' % path)
    with open(path, 'r', encoding='utf-8-sig', newline='') as handle:
        reader = csv.DictReader(handle)
        fields = [(f or '').strip() for f in (reader.fieldnames or [])]
        for col in ('line', 'x', 'z', 'a', 'b'):
            need(col in fields, '%s missing column %r (got %s)' % (path, col, fields))
        out = {}
        for raw in reader:
            line = (raw.get('line') or '').strip()
            if not line:
                continue
            row = {}
            for key in ('x', 'z', 'a', 'b'):
                text = (raw.get(key) or '').strip()
                try:
                    row[key] = float(text) if text else None
                except ValueError:
                    raise InputError('%s: line=%s %s=%r not numeric' % (path, line, key, text))
            out.setdefault(line, []).append(row)
    need(out, '%s has no data rows' % path)
    return out


def profile_items(profiles, kind):
    items = []
    for line, rows in profiles.items():
        if not line.lower().startswith(kind):
            continue
        items.append({'line': line,
                      'x': np.array([r['x'] for r in rows], dtype=float),
                      'a': np.array([np.nan if r['a'] is None else r['a'] for r in rows]),
                      'b': np.array([np.nan if r['b'] is None else r['b'] for r in rows]),
                      'z': next((r['z'] for r in rows if r['z'] is not None), None)})
    need(items, 'profiles.csv has no %s-* lines' % kind)
    for it in items:
        need(np.isfinite(it['x']).all(), '%s: non-finite x' % it['line'])
        need(np.isfinite(it['a']).any() and np.isfinite(it['b']).any(), '%s: no finite a/b' % it['line'])
        it['label'] = '%s  z=%.0f' % (it['line'], it['z']) if it['z'] is not None else it['line']
        it['maxDev'] = float(np.nanmax(np.abs(it['a'] - it['b'])))
    items.sort(key=lambda it: (np.inf if it['z'] is None else it['z'], it['line']))
    return items


def profile_figure(items, heading, lines, note, ylim, sea, uni, path):
    """每行一条剖面，同图共用 Y；1:1 像素绘制，仅按画布宽度抽稀取点（不改数值尺度）。"""
    pad, row_h, gap, axis_w, plot_w = 40, 150, 34, 122, 1278
    head = 70 + 30 * len(lines) + 34
    canvas = Image.new('RGB', (pad * 2 + axis_w + plot_w, head + len(items) * (row_h + gap) + 54), uni.BG)
    draw = ImageDraw.Draw(canvas)
    draw.text((pad, 18), heading, font=uni.fit(heading, canvas.width - pad * 2, 36), fill=uni.INK)
    for i, text in enumerate(lines):
        draw.text((pad, 66 + i * 30), text, font=uni.fit(text, canvas.width - pad * 2, 19), fill=uni.DIM)
    lo, hi = ylim
    x0 = pad + axis_w
    amin = min(float(it['x'].min()) for it in items)
    amax = max(float(it['x'].max()) for it in items)
    clipped = 0
    for k, it in enumerate(items):
        top = head + k * (row_h + gap)
        draw.rectangle((x0, top, x0 + plot_w, top + row_h), outline='#c6c2b3')
        for level in range(int(np.ceil(lo / 100.0)), int(hi // 100) + 1, 1):
            y = top + row_h - int((level * 100 - lo) / (hi - lo) * row_h)
            if top < y < top + row_h:
                draw.line((x0, y, x0 + plot_w, y), fill='#e4e0d2')
                draw.text((pad + 6, y - 8), '%d' % (level * 100), font=uni.font(13), fill=uni.DIM)
        if lo <= sea <= hi:
            y = top + row_h - int((sea - lo) / (hi - lo) * row_h)
            draw.line((x0, y, x0 + plot_w, y), fill='#2e5ca8', width=2)
        for series, color, tag in ((it['a'], A_COLOR, 'A'), (it['b'], B_COLOR, 'B')):
            stride = max(1, series.size // (plot_w * 2))
            xs, ys = it['x'][::stride], series[::stride]
            clipped += int(np.sum(np.isfinite(ys) & ((ys < lo) | (ys > hi))))
            pts = [(x0 + int((x - amin) / (amax - amin) * plot_w),
                    top + row_h - int((min(max(y, lo), hi) - lo) / (hi - lo) * row_h))
                   for x, y in zip(xs, ys) if np.isfinite(y)]
            if len(pts) > 1:
                draw.line(pts, fill=color, width=2)
        draw.text((pad, top - 18), it['label'], font=uni.font(13), fill=uni.INK)
        draw.text((pad, top + row_h - 20), '|Δ|max %.1f' % it['maxDev'], font=uni.font(12), fill=uni.DIM)
        for fraction in (0, 0.25, 0.5, 0.75, 1):
            value = amin + fraction * (amax - amin)
            px = x0 + fraction * plot_w
            draw.text((px, top + row_h + 4), '%.1f' % value, anchor='mt', font=uni.font(12), fill=uni.DIM)
        for value in (lo, hi):
            py = top + row_h - (value - lo) / (hi - lo) * row_h
            draw.text((x0 - 6, py), '%.0f' % value, anchor='rm', font=uni.font(12), fill=uni.DIM)
    draw.text((pad, canvas.height - 40), note, font=uni.fit(note, canvas.width - pad * 2, 18),
              fill=uni.DIM)
    canvas.save(path, optimize=True)
    return {'lines': [it['line'] for it in items], 'ylim': [lo, hi],
            'xRange': [amin, amax], 'maxDevByLine': {it['line']: it['maxDev'] for it in items},
            'samplesDrawn': int(sum(it['x'].size for it in items)), 'clippedSamples': clipped}


def verify(named, dest):
    """守门：每张贴入的原生格必须与内存中那张图逐像素相同（证明未被缩放）。"""
    report = {}
    for name, spots in named.items():
        path = dest / name
        need(path.is_file(), 'figure missing after write: %s' % path)
        with Image.open(path) as handle:
            handle.load()
            size = handle.size
            canvas = np.asarray(handle.convert('RGB'))
        for image, (x, y) in spots:
            tile = np.asarray(image.convert('RGB'))
            need(np.array_equal(canvas[y:y + tile.shape[0], x:x + tile.shape[1]], tile),
                 '%s: panel at (%d,%d) is not pixel-identical — rescaled?' % (name, x, y))
        report[name] = {'size': [size[0], size[1]], 'panels': len(spots), 'pixelsPreserved': True}
    return report


def render(geo, meta, data, uni, mor, pal, names, dest):
    """产线：原生单图 → 并排图（+图例/ROI 示意）→ ROI 裁剪 → 剖面 → 校验 → metrics。"""
    dest.mkdir(parents=True, exist_ok=True)
    sea = geo['sea']
    sh = {s: uni.shade(data[s]['height'], geo['step'], sea) for s in STEMS}
    tp = {s: type_image(data[s]['type'], pal) for s in STEMS}
    img = {('sh', s): Image.fromarray(sh[s]) for s in STEMS}
    img.update({('tp', s): Image.fromarray(tp[s]) for s in STEMS})
    spots, lines = {}, caption(geo, meta)
    save_native(img, meta, mor, lines, dest, spots)
    box = (ROI[0], ROI[1], ROI[2], ROI[3])
    titles = [TITLE[s] % meta[s]['scale'] for s in STEMS]
    canvas, pasted = mor.grid_panel([img[('sh', s)] for s in STEMS], titles,
                                    '两臂高度并排｜原生 1401²，同一光照 / 色带 / 窗口',
                                    lines, '唯一变量 = 区域格间距；两臂均无特殊海岸，%g 块过渡带固定。'
                                           % geo['band'])
    canvas = append_band(canvas, uni.BG, 132, lambda d, w, t: draw_roi(d, w, t, geo, uni))
    canvas.save(dest / 'pair-full.png', optimize=True)
    spots['pair-full.png'] = pasted
    canvas, pasted = mor.grid_panel([img[('tp', s)] for s in STEMS], titles,
                                    '两臂类型并排｜主导配方 id（23 类）',
                                    lines, '类型布局的差异即"区域格间距"对主导选择的影响。')
    canvas = append_band(canvas, uni.BG, 30 * 8 + 46, lambda d, w, t: draw_legend(d, w, t, pal, names, uni))
    canvas.save(dest / 'pair-types.png', optimize=True)
    spots['pair-types.png'] = pasted
    for kind, tag, label in (('sh', 'height', '高度'), ('tp', 'types', '类型')):
        crop = [img[(kind, s)].crop(box) for s in STEMS]
        canvas, pasted = mor.grid_panel(
            crop, ['%s｜像素框 (0,0)-(620,660) 同坐标裁剪' % (TITLE[s] % meta[s]['scale']) for s in STEMS],
            'ROI 裁剪｜左上部海域（像素框 (0,0)-(620,660) 两臂同坐标、同 1:1 比例，未缩放）',
            lines + ['裁剪框块坐标：x %.1f..%.1f / z %.1f..%.1f（620×660 像素 × %g 块）'
                     % (geo['origin'][0] + ROI[0] * geo['step'], geo['origin'][0] + (ROI[2] - 1) * geo['step'],
                        geo['origin'][1] + ROI[1] * geo['step'], geo['origin'][1] + (ROI[3] - 1) * geo['step'],
                        geo['step'])],
            '两臂同框同比例并排（%s）：差异只能来自区域格间距，不是裁剪口径。' % label)
        canvas.save(dest / ('pair-local-%s.png' % tag), optimize=True)
        spots['pair-local-%s.png' % tag] = pasted
    prof = {}
    for kind in ('full', 'local'):
        items = profile_items(data['profiles'], kind)
        ylim = YLIM_FULL if kind == 'full' else profile_ylim(items, sea)
        name = 'profiles-%s.png' % kind
        note = ('横轴 = 采样 x（块）；纵轴%s；蓝线 = 海平面 Y=%.0f。数据直接来自 profiles.csv，'
                '不由本工具重采样栅格。' % ('固定 Y ∈ [%.0f, %.0f]（各线共用，可直接比较）' % ylim
                                            if kind == 'full' else '在本图内共用（实测范围加边距）', sea))
        prof[kind] = profile_figure(items, '剖面对照｜A 区域格 %g（蓝）vs B 区域格 %g（棕）'
                                    % (meta[STEMS[0]]['scale'], meta[STEMS[1]]['scale']),
                                    lines, note, ylim, sea, uni, dest / name)
        spots[name] = []
    figures = verify(spots, dest)
    arm_stats = {s: stats(data[s], sea, names) for s in STEMS}
    payload = {
        'tool': 'render-cell-scale',
        'geometry': geo,
        'singleVariable': {
            'variable': 'region cell spacing (meta scale)',
            'A': {'stem': STEMS[0], 'scale': meta[STEMS[0]]['scale'], 'role': '当前 P3'},
            'B': {'stem': STEMS[1], 'scale': meta[STEMS[1]]['scale'], 'role': '只改区域格间距'},
            'heldFixed': ['分布', '地形参数', '过渡带 %g 块' % geo['band'],
                          '窗口/原点/步长/尺寸一致', '两臂均无特殊海岸']},
        'seaLevel': sea,
        'arms': arm_stats,
        'comparison': diff_stats(data[STEMS[0]]['height'], data[STEMS[1]]['height'],
                                 data[STEMS[0]]['type'], data[STEMS[1]]['type'], sea),
        'profiles': prof,
        'types': {'idSpace': '0..17 陆地（RecipeMorphCatalog 顺序）+ 18..22 海洋（OceanRecipeCatalog 顺序）',
                  'names': names,
                  'rule': 'id = 该像素的主导配方；缺失 id 只表示该臂没选中它，不表示配方不存在'},
        'inputs': {s: data[s]['sha256'] for s in STEMS},
        'figures': figures,
        'notes': ['全部原生格 1:1 粘贴并逐像素校验，未做任何缩放',
                  '只读导出 raw：不做世界生成、不跑探针、不改生产代码',
                  '逐位基准一致性与真实观感由主代理 / 用户实机判定，本工具不断言']}
    (dest / 'metrics.json').write_text(json.dumps(payload, ensure_ascii=False, indent=2) + '\n',
                                       encoding='utf-8')
    return payload


def save_native(img, meta, mor, lines, dest, spots):
    for tag, kind in (('', 'sh'), ('types-', 'tp')):
        for s in STEMS:
            name = 'native-%s%s.png' % (tag, SHORT[s])
            canvas, pasted = mor.grid_panel([img[(kind, s)]], [TITLE[s] % meta[s]['scale']],
                                            '单臂原生 %s图（%s）' % ('高度' if kind == 'sh' else '类型', name),
                                            lines, '1:1 原生，未缩放；数据只来自导出的 raw，'
                                                   '没有做任何重采样或世界生成。')
            canvas.save(dest / name, optimize=True)
            spots[name] = pasted


def profile_ylim(items, sea):
    vals = np.concatenate([np.concatenate([it['a'], it['b']]) for it in items])
    vals = vals[np.isfinite(vals)]
    lo = float(min(vals.min(), sea)) - 8.0
    hi = float(max(vals.max(), sea)) + 8.0
    return [lo, hi]


def main(argv):
    if len(argv) != 2:
        sys.stderr.write('usage: python tools/render-cell-scale.py <root> <dest>\n')
        return 2
    uni, mor = None, None
    try:
        root, dest = Path(argv[0]), Path(argv[1])
        uni, mor = load_tools()
        geo, meta = geometry(root)
        pal, names = palette(uni, mor)
        data = {s: load_arm(root / s, geo['size']) for s in STEMS}
        data['profiles'] = read_profiles(root / 'profiles.csv')
        payload = render(geo, meta, data, uni, mor, pal, names, dest)
    except InputError as exc:
        sys.stderr.write('FAIL %s\n' % exc)
        return 1
    except (IOError, OSError) as exc:
        sys.stderr.write('IO ERROR %s\n' % exc)
        return 2
    except (uni.InputError, mor.InputError) as exc:
        sys.stderr.write('FAIL %s\n' % exc)
        return 1
    print('rendered %d figures -> %s' % (len(payload['figures']), dest))
    print('A scale %g vs B scale %g | water %.2f%% vs %.2f%% | maxAbsDiff %.1f'
          % (payload['singleVariable']['A']['scale'], payload['singleVariable']['B']['scale'],
             payload['arms'][STEMS[0]]['waterPct'], payload['arms'][STEMS[1]]['waterPct'],
             payload['comparison']['maxAbsDiff']))
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
