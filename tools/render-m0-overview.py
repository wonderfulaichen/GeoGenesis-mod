#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""M0 只读标签总览；不求高度、不调参数、不做 P4 或世界生成。

用法：python tools/render-m0-overview.py <root> <NEW_DEST>
      python tools/render-m0-overview.py --self-test
root 是 MDK build/m0-overview/data；输出目录必须不存在，且与 root 不重叠。
主窗口严格使用批准的 1401² / 40 块协议。可选 local/meta.json 至少提供
size、step、origin，必须恰好覆盖 s3Window；其他同名元数据若提供则须一致。
local 只画自身真实 side 标签，不由总览插值。统计仅为栅格观测，不是真边拓扑。
"""
import csv
import hashlib
import io
import json
import sys
from functools import lru_cache
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont
from scipy import ndimage

BG, INK, DIM = '#f4f3ed', '#25261f', '#55594c'
ORANGE = (255, 145, 0)
SIDE_COLORS = np.array(((76, 139, 87), (49, 104, 166)), dtype=np.uint8)
SIDE_NAMES = ('陆', '海')
S3_WINDOW = [-3642.5, -3365.5, 3357.5, 3634.5]
MAIN_META = {'size': 1401, 'step': 40, 'origin': [-28140, -27863],
             'center': [-140, 137], 'spanBlocks': 56000, 'microScale': 600,
             'macroScale': 1200, 'bandTotal': 240, 'heightEvaluated': False,
             's3Window': S3_WINDOW}
# 与 render-unified-ab.py 的字体 helper 相同；不导入以免写入 __pycache__。
FONT_CANDIDATES = ('C:/Windows/Fonts/msyh.ttc', 'C:/Windows/Fonts/msyhbd.ttc',
                   'C:/Windows/Fonts/simhei.ttf')
MAIN_COLORS = (((54, 112, 65), (156, 153, 56), (39, 137, 119),
                (171, 110, 57), (117, 152, 69), (103, 88, 55)),
               ((42, 85, 154), (116, 83, 171), (37, 132, 170),
                (175, 87, 145), (87, 104, 203), (66, 153, 151)))
OTHER_COLORS = (((171, 195, 166), (211, 222, 189)),
                ((151, 180, 210), (195, 207, 233)))  # [side][触边 / 未触边]
CSV_FIELDS = ('dataset', 'connectivity', 'side', 'component', 'rank', 'pixels',
              'areaApproxBlocks2', 'fractionOfSide', 'fractionOfWindow', 'touchesBorder')


class InputError(ValueError):
    """输入或验收失败，必须以非零退出。"""


def need(condition, message):
    if not condition:
        raise InputError(message)


@lru_cache(maxsize=48)
def font(size):
    for path in FONT_CANDIDATES:
        if Path(path).is_file():
            return ImageFont.truetype(path, size)
    raise InputError('找不到中文字体（优先 C:/Windows/Fonts/msyh.ttc）')


def text(draw, xy, content, width, size=19, color=DIM, anchor=None):
    while size > 11:
        f = font(size)
        length = f.getlength(content) if hasattr(f, 'getlength') else f.getsize(content)[0]
        if length <= width:
            break
        size -= 1
    # Pillow 7 的 text(anchor=...) 不可靠；显式定位，保证坐标文字不压到原生面板。
    f = font(size)
    width_px, height_px = f.getsize(content)
    x, y = xy
    if anchor == 'mt':
        x -= width_px / 2
    elif anchor == 'rm':
        x -= width_px
        y -= height_px / 2
    draw.text((int(x), int(y)), content, font=f, fill=color)


def sha256(path):
    digest = hashlib.sha256()
    with path.open('rb') as handle:
        for block in iter(lambda: handle.read(1024 * 1024), b''):
            digest.update(block)
    return digest.hexdigest()


def finite_number(value):
    return (isinstance(value, (int, float)) and not isinstance(value, bool)
            and np.isfinite(value))


def vector(value, length):
    return (isinstance(value, list) and len(value) == length
            and all(finite_number(v) for v in value))


def validate_meta(meta, main=None):
    need(isinstance(meta, dict), 'meta.json 必须是对象')
    need(type(meta.get('size')) is int and 2 <= meta['size'] <= 1401, 'size 必须为 2..1401 整数')
    need(finite_number(meta.get('step')) and meta['step'] > 0, 'step 必须为正有限数')
    need(vector(meta.get('origin'), 2), 'origin 必须为两个有限数')
    if main is None:
        need(isinstance(meta.get('seed'), str) and bool(meta['seed'].strip()), 'seed 必须为非空字符串')
        for key, value in MAIN_META.items():
            need(meta.get(key) == value, '主窗口 %s 必须为 %r' % (key, value))
        need(meta['heightEvaluated'] is False, 'M0 禁止高度求值')
    else:
        for key in ('seed', 'microScale', 'macroScale', 'bandTotal', 's3Window'):
            need(key not in meta or meta[key] == main[key], 'local.%s 与主窗口不一致' % key)
        need('heightEvaluated' not in meta or meta['heightEvaluated'] is False,
             'local 不得声明已求高度')
        ox, oz = meta['origin']
        span = (meta['size'] - 1) * meta['step']
        bounds = [ox, oz, ox + span, oz + span]
        need(np.allclose(bounds, main['s3Window'], rtol=0, atol=1e-8),
             'local 必须恰好覆盖原 S3 窗口，不接受由总览伪造的局部')
        need('spanBlocks' not in meta or meta['spanBlocks'] == span, 'local.spanBlocks 不一致')
        # S3 的名义中心沿用主窗口；采样中心中点实际偏移(-2.5,-2.5)，不可强行重定原点。
        need('center' not in meta or meta['center'] == main['center'], 'local.center 与名义中心不一致')
    return meta


def read_grid(root, name, dtype, size, hashes, bounds=None):
    path = root / name
    expected = size * size * np.dtype(dtype).itemsize
    need(path.stat().st_size == expected, '%s: 长度应为 %d 字节' % (path, expected))
    raw = path.read_bytes()
    need(len(raw) == expected, '%s: 读取时长度变化' % path)
    hashes[name] = hashlib.sha256(raw).hexdigest()
    grid = np.frombuffer(raw, dtype=dtype).reshape(size, size)
    if bounds is not None:
        need(np.isfinite(grid).all(), '%s: 存在 NaN/Inf' % path)
        need(grid.min() >= bounds[0] and grid.max() <= bounds[1],
             '%s: 数值超出 %s' % (path, bounds))
    return grid


def validate_labels(types, side):
    need(np.all(types <= 22), 'type.u8 必须在 0..22')
    need(np.all(side <= 1), 'side.u8 必须为 0 或 1')
    need(np.array_equal(side, (types >= 18).astype(np.uint8)),
         'side/type 语义冲突：0..17 必须陆0，18..22 必须海1')


def load_dataset(root, main=None):
    meta_raw = (root / 'meta.json').read_bytes()
    meta = validate_meta(json.loads(meta_raw.decode('utf-8-sig')), main)
    hashes = {'meta.json': hashlib.sha256(meta_raw).hexdigest()}
    n = meta['size']
    types = read_grid(root, 'type.u8', 'u1', n, hashes, (0, 22))
    side = read_grid(root, 'side.u8', 'u1', n, hashes, (0, 1))
    cells = read_grid(root, 'cells.i64be', '>i8', n, hashes)
    validate_labels(types, side)
    optional = {}
    for name, bounds in (('macro-c.f64be', (-1, 1)), ('ocean-probability.f64be', (0, 1))):
        if (root / name).exists():
            grid = read_grid(root, name, '>f8', n, hashes, bounds)
            optional[name] = {'provided': True, 'min': float(grid.min()),
                              'max': float(grid.max()), 'mean': float(grid.mean())}
        else:
            optional[name] = {'provided': False}
    return {'root': root, 'meta': meta, 'types': types, 'side': side,
            'cellCount': int(np.unique(cells).size), 'inputsSha256': hashes,
            'optional': optional}


def component_labels(side, value, connectivity):
    need(connectivity in (4, 8), 'connectivity 必须为 4 或 8')
    structure = ndimage.generate_binary_structure(2, 1 if connectivity == 4 else 2)
    labels, count = ndimage.label(side == value, structure=structure)
    sizes = np.bincount(labels.ravel(), minlength=count + 1)
    sizes[0] = 0
    border = np.zeros(count + 1, dtype=bool)
    edge_ids = np.unique(np.concatenate((labels[0], labels[-1], labels[:, 0], labels[:, -1])))
    border[edge_ids] = True
    border[0] = False
    return labels, sizes, border


def fraction(numerator, denominator):
    return float(numerator / denominator) if denominator else None


def quantiles(values):
    if not values.size:
        return None
    levels = (0, 25, 50, 75, 90, 95, 100)
    return dict(zip(('P%d' % q for q in levels), map(float, np.percentile(values, levels))))


def summarize_components(sizes, border, window_pixels, step):
    area = sizes[1:]
    count, total = len(area), int(area.sum())
    largest = int(area.max()) if count else 0
    touching = int(sizes[border].sum())
    interior = sizes[(sizes > 0) & ~border]
    single = int(np.count_nonzero(area == 1))
    return {'componentCount': count, 'pixels': total, 'areaApproxBlocks2': total * step ** 2,
            'fractionOfWindow': fraction(total, window_pixels),
            'largest': {'pixels': largest, 'areaApproxBlocks2': largest * step ** 2,
                        'fractionOfSide': fraction(largest, total),
                        'fractionOfWindow': fraction(largest, window_pixels)},
            'borderTouching': {'componentCount': int(border.sum()), 'pixels': touching,
                              'areaApproxBlocks2': touching * step ** 2,
                              'fractionOfComponents': fraction(int(border.sum()), count),
                              'fractionOfSide': fraction(touching, total),
                              'fractionOfWindow': fraction(touching, window_pixels)},
            'notTouchingBorder': {'componentCount': int(interior.size),
                                 'pixels': int(interior.sum()),
                                 'areaQuantilesApproxBlocks2': quantiles(interior * step ** 2)},
            'singlePixel': {'componentCount': single, 'pixels': single,
                            'areaApproxBlocks2': single * step ** 2,
                            'fractionOfComponents': fraction(single, count),
                            'fractionOfSide': fraction(single, total),
                            'fractionOfWindow': fraction(single, window_pixels)}}


def component_rows(dataset, connectivity, value, sizes, border, step, window_pixels):
    ids = np.arange(1, len(sizes))
    order = ids[np.argsort(-sizes[1:], kind='stable')]
    total = int(sizes.sum())
    return [{'dataset': dataset, 'connectivity': connectivity, 'side': value,
             'component': int(label), 'rank': rank, 'pixels': int(sizes[label]),
             'areaApproxBlocks2': int(sizes[label]) * step ** 2,
             'fractionOfSide': fraction(int(sizes[label]), total),
             'fractionOfWindow': fraction(int(sizes[label]), window_pixels),
             'touchesBorder': bool(border[label])}
            for rank, label in enumerate(order, 1)]


def color_components(labels, sizes, border, value):
    lut = np.zeros((len(sizes), 3), dtype=np.uint8)
    lut[border] = OTHER_COLORS[value][0]
    lut[~border] = OTHER_COLORS[value][1]
    ids = np.arange(1, len(sizes))
    order = ids[np.argsort(-sizes[1:], kind='stable')][:6]
    legend = []
    for rank, label in enumerate(order, 1):
        color = MAIN_COLORS[value][rank - 1]
        lut[label] = color
        legend.append((color, '%s%d  %s  %.2f%%全窗' %
                       (SIDE_NAMES[value], rank, '触边' if border[label] else '未触边',
                        sizes[label] / labels.size * 100)))
    for touch in (True, False):
        remaining = np.flatnonzero((sizes > 0) & (border == touch))
        remaining = remaining[~np.isin(remaining, order)]
        if remaining.size:
            legend.append((OTHER_COLORS[value][0 if touch else 1],
                           '其余%s：%s（%d片）' %
                           (SIDE_NAMES[value], '触边' if touch else '未触边', len(remaining))))
    return lut[labels], legend


def analyze(data, name, make_map=False):
    side, meta = data['side'], data['meta']
    results, rows, legend = {}, [], []
    rgb = np.zeros(side.shape + (3,), dtype=np.uint8) if make_map else None
    for connectivity in (4, 8):
        result = {}
        for value in (0, 1):
            labels, sizes, border = component_labels(side, value, connectivity)
            result['land' if value == 0 else 'sea'] = summarize_components(
                sizes, border, side.size, meta['step'])
            rows.extend(component_rows(name, connectivity, value, sizes, border,
                                       meta['step'], side.size))
            if make_map and connectivity == 4:
                colored, entries = color_components(labels, sizes, border, value)
                rgb[side == value] = colored[side == value]
                legend.extend(entries)
        results[str(connectivity)] = result
    counts = np.bincount(data['types'].ravel(), minlength=23)
    metrics = {'geometry': meta, 'sampledCellCount': data['cellCount'],
               'recipePixels': {str(i): int(n) for i, n in enumerate(counts)},
               'inputsSha256': data['inputsSha256'], 'optionalFields': data['optional'],
               'connectivity': results}
    return metrics, rows, rgb, legend


def panel_frame(rgb, meta, title, lines):
    n = meta['size']
    x, y, right, tail = 132, 226, 418, 272
    canvas = Image.new('RGB', (x + n + right, y + n + tail), BG)
    draw = ImageDraw.Draw(canvas)
    text(draw, (36, 18), title, canvas.width - 72, 32, INK)
    for i, line in enumerate(lines):
        text(draw, (36, 68 + i * 29), line, canvas.width - 72, 19)
    canvas.paste(Image.fromarray(rgb, 'RGB'), (x, y))
    # 边框在原生面板外：不覆盖边缘样本，更不伪造一圈海洋。
    draw.rectangle((x - 1, y - 1, x + n, y + n), outline='#a7a69b')
    draw_axes(draw, meta, x, y)
    return canvas, (x, y)


def draw_axes(draw, meta, x, y):
    n, step = meta['size'], meta['step']
    ox, oz = meta['origin']
    for index in (0, (n - 1) // 4, (n - 1) // 2, (n - 1) * 3 // 4, n - 1):
        text(draw, (x + index, y - 27), '%g' % (ox + index * step), 130, 15, anchor='mt')
        text(draw, (x - 12, y + index), '%g' % (oz + index * step), 110, 15, anchor='rm')
        draw.line((x + index, y - 6, x + index, y - 2), fill=DIM)
        draw.line((x - 6, y + index, x - 2, y + index), fill=DIM)
    text(draw, (x + n + 80, y - 30), 'x →（块）', 180, 17)
    text(draw, (16, y - 36), 'z ↓（块）', 110, 15)
    span = (n - 1) * step
    length = 10000 if span >= 10000 else 1000
    pixels = int(round(length / step))
    base_y = y + n + 48
    draw.line((x, base_y, x + pixels, base_y), fill=INK, width=3)
    for offset in (0, pixels):
        draw.line((x + offset, base_y - 6, x + offset, base_y + 6), fill=INK, width=2)
    text(draw, (x, base_y + 12), '比例尺 %g 块（%d 像素；1 像素=%g块）' %
         (length, pixels, step), 720, 18)
    text(draw, (x, y + n + 8), '坐标为采样中心；观测窗边界不是海岸，窗口外未知。', n, 18)


def s3_overlay(canvas, xy, meta):
    x, y = xy
    n, step = meta['size'], meta['step']
    ox, oz = meta['origin']
    bounds = meta['s3Window']
    box = tuple(int(round(v)) for v in ((bounds[0] - ox) / step,
                (bounds[1] - oz) / step, (bounds[2] - ox) / step, (bounds[3] - oz) / step))
    need(0 <= box[0] < box[2] < n and 0 <= box[1] < box[3] < n, 'S3 框超出总览')
    absolute = (x + box[0], y + box[1], x + box[2], y + box[3])
    draw = ImageDraw.Draw(canvas)
    draw.rectangle(absolute, outline=(35, 35, 30), width=7)
    draw.rectangle(absolute, outline=ORANGE, width=3)
    mask = Image.new('L', (n, n), 0)
    ImageDraw.Draw(mask).rectangle(box, outline=255, width=7)
    return np.asarray(mask, dtype=bool), list(box)


def draw_legend(draw, xy, n, entries, heading):
    left, top = xy[0] + n + 28, xy[1]
    text(draw, (left, top), heading, 355, 23, INK)
    for i, (color, label) in enumerate(entries):
        yy = top + 46 + i * 32
        draw.rectangle((left, yy + 3, left + 23, yy + 23), fill=tuple(color))
        text(draw, (left + 34, yy), label, 320, 17)
    return left, top + 60 + len(entries) * 32


def footer(draw, xy, n, lines):
    for i, line in enumerate(lines):
        text(draw, (36, xy[1] + n + 130 + 29 * i), line, xy[0] + n + 346, 18)


def save_verified(canvas, rgb, xy, mask, path):
    x, y = xy
    h, w = rgb.shape[:2]
    expected = np.asarray(canvas)[y:y + h, x:x + w].copy()
    canvas.save(path, format='PNG', optimize=True)
    with Image.open(path) as handle:
        handle.load()
        need(handle.mode == 'RGB' and handle.size == canvas.size, '%s: PNG 模式/尺寸不符' % path)
        readback = np.asarray(handle)
    panel = readback[y:y + h, x:x + w]
    need(panel.shape == rgb.shape, '%s: 面板尺寸变化' % path)
    need(np.array_equal(panel, expected), '%s: PNG 面板回读不一致' % path)
    need(np.array_equal(panel[~mask], rgb[~mask]), '%s: 未标注区域被缩放或改色' % path)
    return {'canvasSize': list(canvas.size), 'panelBoxXYWH': [x, y, w, h],
            'panelScale': 1, 'annotatedPixels': int(mask.sum()),
            'nativePixelsVerified': int((~mask).sum()), 'pngReadbackVerified': True,
            'unannotatedColorsExact': True}


def common_lines(meta, seed):
    return ['仅基础配方侧标签：绿陆 / 蓝海；不是高度图，不是水面图，未求高度。',
            'seed=%s｜%d×%d 原生面板｜步长 %g 块｜1:1，无缩放、无插值' %
            (seed, meta['size'], meta['size'], meta['step']),
            '微尺度 600 / 宏尺度 1200 / 带宽 240：原参数不动；M0 不做 P4。',
            '观测窗之外未知；触边只表示观测截断，不代表大陆尽头或海洋外圈。']


def render_side(data, dest, local=False, seed=None):
    meta, side = data['meta'], data['side']
    rgb = SIDE_COLORS[side]
    name = 'local-side.png' if local else 'overview.png'
    title = 'M0｜原 S3 窗口真实陆海标签' if local else 'M0｜56,000 块采样跨度的陆海标签总览'
    canvas, xy = panel_frame(rgb, meta, title, common_lines(meta, seed or meta.get('seed')))
    draw = ImageDraw.Draw(canvas)
    entries = [(tuple(SIDE_COLORS[i]), '%s侧标签（%.2f%%）' %
                (SIDE_NAMES[i], 100 * np.mean(side == i))) for i in (0, 1)]
    left, yy = draw_legend(draw, xy, meta['size'], entries, '基础侧标签')
    mask = np.zeros(side.shape, dtype=bool)
    box = None
    if not local:
        mask, box = s3_overlay(canvas, xy, meta)
        draw.rectangle((left, yy + 3, left + 23, yy + 23), outline=ORANGE, width=3)
        text(draw, (left + 34, yy), '橙框：原 S3 观测窗', 320, 18, INK)
        text(draw, (left, yy + 44), '框线取最近像素，位置误差≤20块。', 355, 17)
    footer(draw, xy, meta['size'], [
        'S3 块坐标：x -3642.5..3357.5，z -3365.5..3634.5（采样中心范围）。',
        '绿/蓝严格来自 side.u8；type.u8 的 0..17=陆，18..22=海，已逐像素交叉校验。',
        '只读导出标签，不推算高度、海平面、湖泊或河流；没有添加海洋边框。'])
    result = save_verified(canvas, rgb, xy, mask, dest / name)
    result['s3BoxPanelXYXY'] = box
    return name, result


def render_components(data, rgb, entries, metrics, dest):
    meta = data['meta']
    lines = common_lines(meta, meta['seed'])
    lines[0] = '4邻接陆块 / 海盆：各侧最大六片独立着色；其余按该侧触边 / 未触边归色。'
    lines[2] = '不跨边界连通、不周期回绕；4邻接不连角点，8邻接对照统计见 metrics.json。'
    canvas, xy = panel_frame(rgb, meta, 'M0｜4邻接陆块与海盆（标签，不是高度 / 水面）', lines)
    draw = ImageDraw.Draw(canvas)
    left, yy = draw_legend(draw, xy, meta['size'], entries, '主要组件｜按侧内面积排名')
    for i, key in enumerate(('land', 'sea')):
        stat = metrics['connectivity']['4'][key]
        text(draw, (left, yy + i * 30), '%s：%d片，触边%d片' %
             (SIDE_NAMES[i], stat['componentCount'], stat['borderTouching']['componentCount']), 355, 17)
    for i, line in enumerate(('触边：组件含最外行/列采样点。', '未触边：仅指栅格内未见边界接触。',
                              '两者都不证明窗外的真实连通性。')):
        text(draw, (left, yy + 86 + i * 30), line, 355, 17)
    footer(draw, xy, meta['size'], [
        '面积近似=像素数×1600块²（40块采样）；采样中心跨度56000块，面积不是跨度的平方。',
        '窄海峡 / 窄地桥可能漏采；4邻接拆开角点、8邻接连接角点；均不是精确几何拓扑。',
        '触边组件面积为窗内截断面积；未触边面积分位不证明完整大陆 / 海盆面积。'])
    empty_mask = np.zeros(data['side'].shape, dtype=bool)
    return save_verified(canvas, rgb, xy, empty_mask, dest / 'components.png')


def method_notes():
    return {'scope': 'M0 只读基础配方标签观测；不调参数、不做P4、不求高度、不回写世界数据',
            'sideSemantics': {'0': '陆，基础配方0..17', '1': '海，基础配方18..22；不代表水面'},
            'connectivity': 'scipy.ndimage.label：4为上下左右，8另含角点；无周期回绕，无窗外推断',
            'area': 'pixelCount * step² 块²；主图step=40，故pixelCount*1600；仅采样近似',
            'span': '(size-1)*step 是首末采样中心跨度；size²*step² 是面积估计，两者口径不同',
            'border': '任一成员像素在最外行/列即触边；窗口边界不是海岸，未画海洋边框',
            'borderLimit': '触边面积被窗口截断；未触边也不证明几何完整或窗外不相连',
            'samplingLimit': '40块采样可能漏掉窄海峡/地桥/小片；角点连通在4/8邻接间不同',
            'quantiles': '未触边组件面积，按组件等权 NumPy linear 百分位；无组件时null',
            'fractions': 'fractionOfSide/Window 分母分别为该侧像素/全窗像素；分母0时null',
            'smallFragments': '单像素组件=该邻接规则下恰有1个样本；不是单微细胞或真实小岛',
            'componentIds': 'CSV id在每个dataset/connectivity/side内独立；rank面积降序，同面积按id',
            'local': '可选local为独立真实导出，几何须等于原S3；绝不从总览放大生成',
            'hashes': 'inputsSha256记录所有已读输入；SHA256SUMS.txt列全部交付文件（自身除外）',
            'judgement': '仅验收协议与渲染保真；不设陆海面积/组件数量/观感的优劣阈值'}


def write_csv(dest, rows):
    with (dest / 'components.csv').open('x', encoding='utf-8-sig', newline='') as handle:
        writer = csv.DictWriter(handle, fieldnames=CSV_FIELDS)
        writer.writeheader()
        writer.writerows(rows)


def verify_inputs_unchanged(datasets):
    for data in datasets:
        for name, digest in data['inputsSha256'].items():
            need(sha256(data['root'] / name) == digest, '渲染期间输入变化：%s' % (data['root'] / name))
    return True


def write_manifest(dest):
    paths = sorted(p for p in dest.iterdir() if p.name != 'SHA256SUMS.txt')
    content = ''.join('%s  %s\n' % (sha256(p), p.name) for p in paths)
    with (dest / 'SHA256SUMS.txt').open('x', encoding='utf-8', newline='\n') as handle:
        handle.write(content)


def run(root, dest):
    root, dest = root.resolve(), dest.resolve()
    need(root.is_dir(), '输入目录不存在：%s' % root)
    need(not dest.exists(), 'NEW_DEST 必须不存在，拒绝覆盖：%s' % dest)
    need(root != dest and root not in dest.parents and dest not in root.parents,
         '输出目录必须与输入目录不重叠（不能是其父目录或子目录）')
    data = load_dataset(root)
    local = load_dataset(root / 'local', data['meta']) if (root / 'local').exists() else None
    main_metrics, rows, rgb, entries = analyze(data, 'main', True)
    local_metrics, local_rows = None, []
    if local is not None:
        local_metrics, local_rows, _, _ = analyze(local, 'local')
    font(19)  # 字体验收也先于创建输出目录。
    dest.mkdir(parents=True, exist_ok=False)
    payload = {'tool': 'render-m0-overview', 'method': method_notes(), 'main': main_metrics,
               'local': {'status': 'provided', **local_metrics} if local is not None else
               {'status': 'not provided', 'note': '未生成local-side.png，不以总览放大冒充真实局部'},
               'figures': {}}
    name, result = render_side(data, dest)
    payload['figures'][name] = result
    payload['figures']['components.png'] = render_components(data, rgb, entries, main_metrics, dest)
    if local is not None:
        name, result = render_side(local, dest, True, data['meta']['seed'])
        payload['figures'][name] = result
    write_csv(dest, rows + local_rows)
    payload['validation'] = {'rawByteCounts': True, 'finiteRanges': True, 'sideTypeAgreement': True,
                             'inputsUnchanged': verify_inputs_unchanged([data] + ([local] if local else []))}
    with (dest / 'metrics.json').open('x', encoding='utf-8') as handle:
        handle.write(json.dumps(payload, ensure_ascii=False, indent=2, allow_nan=False) + '\n')
    write_manifest(dest)
    return payload


def self_test_connectivity():
    diagonal = np.array(((0, 1), (1, 0)), dtype=np.uint8)
    for value in (0, 1):
        for connectivity, count in ((4, 2), (8, 1)):
            _, sizes, border = component_labels(diagonal, value, connectivity)
            need(len(sizes) - 1 == count and border.sum() == count, '角点4/8邻接或触边测试失败')
    ring = np.zeros((7, 7), dtype=np.uint8)
    ring[1:6, 1:6] = 1
    ring[3, 3] = 0
    for connectivity in (4, 8):
        _, sizes, border = component_labels(ring, 0, connectivity)
        stat = summarize_components(sizes, border, 49, 40)
        need(stat['componentCount'] == 2 and stat['borderTouching']['pixels'] == 24,
             '触边陆环 / 内部单像素测试失败')
        need(stat['largest']['fractionOfSide'] == 24 / 25, '最大组件该侧分母错误')
        need(stat['largest']['fractionOfWindow'] == 24 / 49, '最大组件全窗分母错误')
        need(stat['singlePixel']['componentCount'] == 1, '单像素统计错误')
        need(stat['notTouchingBorder']['areaQuantilesApproxBlocks2']['P50'] == 1600,
             '内部面积分位错误')
        _, sizes, border = component_labels(ring, 1, connectivity)
        need(len(sizes) == 2 and not border.any() and sizes[1] == 24, '未触边海盆测试失败')
    for value in (0, 1):
        solid = np.full((5, 5), value, dtype=np.uint8)
        for connectivity in (4, 8):
            _, sizes, border = component_labels(solid, value, connectivity)
            need(len(sizes) == 2 and sizes[1] == 25 and border[1], '全侧触边测试失败')
            _, sizes, border = component_labels(solid, 1 - value, connectivity)
            stat = summarize_components(sizes, border, 25, 40)
            need(stat['componentCount'] == 0 and stat['largest']['fractionOfSide'] is None,
                 '空侧统计测试失败')
            need(stat['notTouchingBorder']['areaQuantilesApproxBlocks2'] is None, '空分位应为null')


def self_test_validation():
    meta = {'seed': 'test', **MAIN_META}
    validate_meta(meta)
    validate_meta({'size': 1401, 'step': 5, 'origin': S3_WINDOW[:2],
                   'center': [-140, 137], 'spanBlocks': 7000}, meta)
    tests = [lambda: validate_meta({**meta, 'step': float('nan')}),
             lambda: validate_meta({**meta, 'heightEvaluated': True}),
             lambda: validate_meta({'size': 1401, 'step': 40, 'origin': S3_WINDOW[:2]}, meta),
             lambda: validate_labels(np.array([[23]], dtype='u1'), np.array([[1]], dtype='u1')),
             lambda: validate_labels(np.array([[18]], dtype='u1'), np.array([[0]], dtype='u1')),
             lambda: validate_labels(np.array([[0]], dtype='u1'), np.array([[2]], dtype='u1'))]
    for test in tests:
        try:
            test()
        except InputError:
            continue
        raise InputError('应拒绝的非法输入未被拒绝')
    types = np.array([[0, 17, 18, 22]], dtype='u1')
    validate_labels(types, np.array([[0, 0, 1, 1]], dtype='u1'))
    original = np.array([0, -1, -(2 ** 63), 2 ** 63 - 1], dtype=np.int64)
    need(np.array_equal(np.frombuffer(original.astype('>i8').tobytes(), dtype='>i8'), original),
         '大端int64测试失败')


def self_test_png():
    # 内存PNG验证不需要字体，也不创建输出目录或临时文件。
    side = np.indices((19, 23)).sum(axis=0).astype(np.uint8) % 2
    rgb = SIDE_COLORS[side]
    image = Image.new('RGB', (41, 37), BG)
    image.paste(Image.fromarray(rgb), (7, 9))
    stream = io.BytesIO()
    image.save(stream, format='PNG')
    stream.seek(0)
    with Image.open(stream) as handle:
        handle.load()
        need(np.array_equal(np.asarray(handle)[9:28, 7:30], rgb), '原生颜色PNG回读测试失败')


def main(argv):
    try:
        if argv == ['--self-test']:
            self_test_connectivity()
            self_test_validation()
            self_test_png()
            print('PASS: 4/8 adjacency, both sides, border/interior, empty side, fractions, '
                  'quantiles, singleton, metadata, side/type, endian, native PNG (no files written)')
            return 0
        if len(argv) != 2 or any(arg.startswith('--') for arg in argv):
            sys.stderr.write('usage: python tools/render-m0-overview.py <root> <NEW_DEST>\n'
                             '       python tools/render-m0-overview.py --self-test\n')
            return 2
        payload = run(Path(argv[0]), Path(argv[1]))
        print('PASS: %d native PNG(s), metrics.json, components.csv, SHA256SUMS.txt -> %s' %
              (len(payload['figures']), argv[1]))
        return 0
    except (InputError, OSError, ValueError, TypeError, OverflowError, KeyError) as exc:
        sys.stderr.write('FAIL %s: %s\n' % (type(exc).__name__, exc))
        return 1


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
