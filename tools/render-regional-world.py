#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""P3｜海陆统一区域权重：只读渲染器（独立工具，生产零改动、不启动 MC）。

用法：
    python tools/render-regional-world.py <root> <dest>

<root>（由导出方产出，本工具只读、不改、不重算）：
    meta.json              必需键 size / step / center，以及 origin（或 sampleOrigin）；
                           可选 seed / jvmProperties；其它键原样记入 metrics。未知键不拒绝。
    baseline-a.f64be       旧链 · **含旧海岸**（A 臂）= 此前的"距离修复后 B"
    baseline-base.f64be    旧链 · **无海岸**（同一条旧链只去掉海岸）
    regional-hard.f64be    新站点类型（P3）· **无常态过渡**（硬切换；布局=新）
    regional-morph.f64be   新公共节点输出（P3B）· **无海岸**（布局=新，含常态过渡）
    type-a.u8              旧链**海岸处理之前**的内部配方 id 0..22（海侧分支 / 陆地 dominant 口径）；
                           既**不是**最终的"实际海岸配方"，也**不是**全部潜在候选
                           某 id 像素为 0 = 这一臂从未选中它，**不表示该配方不存在**
    type-b.u8              新链**加权 DOMINANT 主导**配方 id 0..22（加权后的最终主导，同样不等于全部候选）
    type-hard.u8           新链**胜出站点**配方 id 0..22（硬选、未加权）
    sea-weight.f64be       海侧权重场原值；**不假设已归一化**，1.0 附近的舍入超出照原样标注
    active.u8              活跃标记/计数（**语义由导出方定义，本工具不断言**，按实测取值上色）
    profiles.csv           列 line,x,z,a,base,hard,morph（local-* 行另有 distance）

输出（<dest>；每张地形/类型/差图面板都是 1401² 原生像素，**绝不缩放、绝不重采样**）：
    native-a.png / native-b.png / native-base.png / native-hard.png   裸单图
    native-types-a.png / native-types-b.png                          裸类型图
    pair-full.png       A（含旧海岸）vs B（P3，无海岸）
    pair-ordinary.png   base（旧链无海岸）vs B —— 隔离"无海岸 OLD vs 新"，但**布局仍在变**
    pair-smoothing.png  hard vs B —— **同一布局**，只差"硬切换 → 常态过渡"
    pair-types.png      type-a vs type-b + 23 条图例（下标 18 陆 + 5 海；0 像素标"未出现"）
    profiles-full.png   6 条固定横剖面，**共用 Y 轴 -64..320**
    profiles-local.png  local-* 法向剖面（法向步长 1 块，与栅格步长不同口径；缺 local 行则不产出）
    transition-map.png  sea-weight 原值 + active 实测取值（1-ULP 舍入与真越界分文别判）
    diff.png            A → B 差图（暖 = B 更高 / 冷 = B 更低）
    diff-smoothing.png  hard → B 差图（同布局，只差过渡）
    metrics.json        水面占比 / 翻水面 / 逐位差 / 类型面积 / 剖面与栅格互校 等

<h3>★ 配色与光照只导入不改写</h3>
<p>{@code shade()} / 海色 / 光照向量来自 {@code tools/render-unified-ab.py}（与统一世界图逐位同源）；
陆地 18 条类型配色与中文名来自 {@code tools/render-full-morph.py}（复合条目 = 两成员色中点再提亮）。
两者都只 import，不复制；任一缺失**直接失败**（宁可不画，也不要悄悄换一套配色让图例说谎）。</p>

<h3>★ 类型 id 口径（0..22）</h3>
<p>0..17 = 陆地 18 条（{@code RecipeMorphCatalog} 固定顺序），18..22 = 海洋 5 条
（{@code OceanRecipeCatalog.Recipe} 顺序）——这是**目录的下标空间**，不是"实际出现了哪些"。
拼接顺序与 {@code MorphCatalog.world()} "先 land 后 ocean" 一致。
**复合条目是两条配方的混合，不是原子**，图例按成员色中点着色，标签如实写"平原+草原"，
不折进原子。图例对某臂计数为 0 的条目**明确标成"0 像素（未出现）"**：
0 只说明该臂没选中它，不说明该配方不存在，更不能反推"只有出现的这几条存在"。</p>

<h3>★ 三条读图铁律（写进图里，不只写在这里）</h3>
<ul>
  <li>A vs B <b>不同源</b>：海岸层被整体移除、布局也换了 → 不能归因到任何单条改动。</li>
  <li>base vs B 只隔离了「无海岸」，<b>布局仍不同</b> → 差异 = 布局 + 常态过渡。</li>
  <li>hard vs B <b>布局完全相同</b> → 差异只来自「硬切换 → 常态过渡」，这是唯一的单变量对照。</li>
</ul>

<h3>★ 面积口径</h3>
<p>类型面积/占比一律<b>由 u8 配方 id 直方图统计</b>，绝不由后验高度反推；每个 id 表示
"该像素被<b>选中 / 加权主导 / 胜出</b>的那一条配方"，<b>不代表</b>所有参与过选择的候选
（把候选都当"实际"会让每格变成全 23 类）。三臂的口径互不相同（type-a 是海岸处理**之前**的
内部配方，type-b 是加权主导，type-hard 是胜出站点），因此三张面积表**不可直接当作
"同一件事的三个版本"**横向比较。</p>
"""
import csv
import hashlib
import importlib.util
import json
import math
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

# 导入 helper 模块时不要往仓库里写 __pycache__（本工具只读，不留任何副作用）
sys.dont_write_bytecode = True

HERE = Path(__file__).resolve().parent

BG = '#f4f3ed'
INK = '#25261f'
DIM = '#55594c'
FONT_CANDIDATES = ('C:/Windows/Fonts/msyh.ttc', 'C:/Windows/Fonts/msyhbd.ttc',
                   'C:/Windows/Fonts/simhei.ttf')

SEA = 63.0                        # HeightCurve: e∈[-1,0] → Y∈[-64,63]
HEIGHT_LO, HEIGHT_HI = -64.0, 320.0

LAND_TYPES, OCEAN_TYPES = 18, 5
TYPE_COUNT = LAND_TYPES + OCEAN_TYPES

HEIGHT_ARMS = ('baseline-a', 'baseline-base', 'regional-hard', 'regional-morph')
TYPE_ARMS = ('type-a', 'type-b', 'type-hard')
NATIVE_NAME = {'baseline-a': 'native-a.png', 'baseline-base': 'native-base.png',
               'regional-hard': 'native-hard.png', 'regional-morph': 'native-b.png'}

# OceanRecipeCatalog.Recipe 的 5 个 id（0..4），拼在陆地 18 条之后 → 18..22
OCEAN_SLUGS = ('ABYSSAL_PLAIN', 'MID_OCEAN_RIDGE', 'SEAMOUNT_FIELD', 'CANYON',
               'SHELF_RIDGE')

PROFILE_COLUMNS = ('line', 'x', 'z', 'a', 'base', 'hard', 'morph')
PROFILE_SERIES = ('base', 'a', 'hard', 'morph')
SERIES_TO_ARM = {'a': 'baseline-a', 'base': 'baseline-base',
                 'hard': 'regional-hard', 'morph': 'regional-morph'}
SERIES_STYLE = (('base', '旧链无海岸 base', '#8f8f8a'),
                ('a', '旧 A（含旧海岸）', '#b0742a'),
                ('hard', '新 hard（同布局·硬切换）', '#7b4fb0'),
                ('morph', '新 P3B（common node）', '#1f70b0'))
MAX_LOCAL = 24
LABEL_STRIP = 26          # 剖面每行标签的独立横条高度（标签不压图、不压刻度）
TICK_STRIP = 28           # 每行图带下方 x 轴刻度数字的独立横条高度

LEG_ROW = 40
LEG_SW = 30

# 归一化权重场在 1.0 附近的浮点舍入容差：**实测 1 + 2.22e-16（3 像素）属于此范围**
# → 不当作分布缺陷；只有 |偏差| > 本阈值的像素才归为 outOfRangeClass = 'defect'。
ULP_TOL = 1e-12


class InputError(Exception):
    """输入不合规 / IO 失败：一律非零退出。"""


def need(cond, msg):
    if not cond:
        raise InputError(msg)


# ------------------------------------------------------------------ 文本

_FONTS = {}


def font(size):
    if size not in _FONTS:
        for path in FONT_CANDIDATES:
            if Path(path).is_file():
                _FONTS[size] = ImageFont.truetype(path, size)
                break
        else:
            raise InputError('no CJK font found, tried %s' % (FONT_CANDIDATES,))
    return _FONTS[size]


def tw(text, text_font):
    try:
        return float(text_font.getlength(text))
    except AttributeError:
        return float(text_font.getsize(text)[0])


def fit(text, max_w, preferred, floor=12):
    size = preferred
    while size > floor and tw(text, font(size)) > max_w:
        size -= 1
    return font(floor if size <= floor else size)


# ------------------------------------------------------------------ 既有配色（只导入）

def _load_module(path, name):
    need(path.is_file(), 'missing helper %s' % path)
    spec = importlib.util.spec_from_file_location(name, str(path))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def load_palette():
    """只读导入 shade/光照（render-unified-ab.py）与 18 条陆地类型配色（render-full-morph.py）。"""
    shade_mod = _load_module(HERE / 'render-unified-ab.py', 'geogenesis_unified_ab')
    type_mod = _load_module(HERE / 'render-full-morph.py', 'geogenesis_full_morph')
    need(callable(getattr(shade_mod, 'shade', None)),
         'render-unified-ab.py has no shade()')
    need(callable(getattr(type_mod, 'entry_colors', None)),
         'render-full-morph.py has no entry_colors()')
    land_colors = np.asarray(type_mod.entry_colors(), dtype=float)
    ocean_colors = np.asarray(shade_mod.OCEAN_COLORS, dtype=float)
    need(land_colors.shape == (LAND_TYPES, 3),
         'land palette shape %r, expected (%d, 3)' % (land_colors.shape, LAND_TYPES))
    need(ocean_colors.shape == (OCEAN_TYPES, 3),
         'ocean palette shape %r, expected (%d, 3)' % (ocean_colors.shape, OCEAN_TYPES))
    names = list(type_mod.ENTRY_CN) + list(shade_mod.OCEAN_NAMES)
    slugs = list(type_mod.ENTRY_IDS) + list(OCEAN_SLUGS)
    need(len(names) == TYPE_COUNT, 'type name table has %d entries, expected %d'
         % (len(names), TYPE_COUNT))
    need(len(slugs) == TYPE_COUNT, 'type slug table has %d entries, expected %d'
         % (len(slugs), TYPE_COUNT))
    return {'shade': shade_mod.shade,
            'colors': np.vstack([land_colors, ocean_colors]),
            'names': names, 'slugs': slugs}


# ------------------------------------------------------------------ 读输入

def _int(value, name):
    need(not isinstance(value, bool), '%s must be an integer, got bool' % name)
    try:
        out = int(value)
    except (TypeError, ValueError):
        raise InputError('%s must be an integer, got %r' % (name, value))
    need(float(out) == float(value), '%s must be an exact integer, got %r' % (name, value))
    return out


def _pair(value, name):
    need(isinstance(value, (list, tuple)) and len(value) == 2,
         '%s must be a 2-element list, got %r' % (name, value))
    out = []
    for item in value:
        need(not isinstance(item, bool) and isinstance(item, (int, float)),
             '%s must hold numbers, got %r' % (name, item))
        need(math.isfinite(float(item)), '%s must be finite, got %r' % (name, item))
        out.append(float(item))
    return out


def read_meta(root):
    """读 meta.json：必需 size/step/center + origin(或 sampleOrigin)，并校验三者自洽。"""
    path = root / 'meta.json'
    need(path.is_file(), 'missing %s' % path)
    try:
        raw = json.loads(path.read_text(encoding='utf-8-sig'))
    except ValueError as exc:
        raise InputError('%s is not valid JSON: %s' % (path, exc))
    need(isinstance(raw, dict), '%s must be a JSON object' % path)
    for key in ('size', 'step', 'center'):
        need(key in raw, '%s missing key %s' % (path, key))
    origin = raw.get('origin', raw.get('sampleOrigin'))
    need(origin is not None, '%s missing origin (or sampleOrigin)' % path)
    size = _int(raw['size'], 'size')
    step = _int(raw['step'], 'step')
    need(size > 4, 'size must be > 4, got %d' % size)
    need(step >= 1, 'step must be >= 1, got %d' % step)
    cx, cz = _pair(raw['center'], 'center')
    ox, oz = _pair(origin, 'origin')
    half = size / 2.0 * step
    for axis, got, want in (('x', ox, cx - half), ('z', oz, cz - half)):
        need(abs(got - want) <= 1e-6 * max(1.0, abs(want)),
             'origin.%s=%r != center.%s - size/2*step = %r' % (axis, got, axis, want))
    return {'seed': raw.get('seed'), 'size': size, 'step': step,
            'center': [cx, cz], 'origin': [ox, oz],
            'extent': [size * step, size * step],
            'jvmProperties': raw.get('jvmProperties'),
            'metaKeys': sorted(raw)}


def read_f64(root, name, size):
    path = root / name
    need(path.is_file(), 'missing %s' % path)
    data = path.read_bytes()
    expect = size * size * 8
    need(len(data) == expect, '%s: %d bytes, expected %d (big-endian float64, %d²)'
         % (path, len(data), expect, size))
    grid = np.frombuffer(data, dtype='>f8').astype(np.float64).reshape(size, size)
    need(np.isfinite(grid).all(), '%s contains non-finite values' % path)
    return grid, hashlib.sha256(data).hexdigest()


def read_u8(root, name, size, hi=None):
    path = root / name
    need(path.is_file(), 'missing %s' % path)
    data = path.read_bytes()
    expect = size * size
    need(len(data) == expect, '%s: %d bytes, expected %d (%d²)' % (path, len(data), expect, size))
    grid = np.frombuffer(data, dtype=np.uint8).reshape(size, size)
    if hi is not None:
        hint = ''
        if int(grid.max()) == 255:
            hint = '（最大值 255 像"无选择"哨兵；本工具按契约只接受 0..%d）' % hi
        need(int(grid.min()) >= 0 and int(grid.max()) <= hi,
             '%s: ids %d..%d outside 0..%d%s' % (path, grid.min(), grid.max(), hi, hint))
    return grid, hashlib.sha256(data).hexdigest()


def read_profiles(path):
    """读 profiles.csv：line,x,z,a,base,hard,morph（+ distance）。空单元格记 None。"""
    need(path.is_file(), 'missing %s' % path)
    with open(path, 'r', encoding='utf-8-sig', newline='') as handle:
        reader = csv.DictReader(handle)
        fields = [(f or '').strip() for f in (reader.fieldnames or [])]
        missing = [c for c in PROFILE_COLUMNS if c not in fields]
        need(not missing, '%s missing columns %s' % (path, missing))
        numeric = ('x', 'z', 'distance', 'a', 'base', 'hard', 'morph')
        out, order = {}, []
        for raw in reader:
            line = (raw.get('line') or '').strip()
            if not line:
                continue
            row = {}
            for key in numeric:
                text = (raw.get(key) or '').strip() if key in fields else ''
                if not text:
                    row[key] = None
                    continue
                try:
                    row[key] = float(text)
                except ValueError:
                    raise InputError('%s: line=%s 的 %s=%r 不是数字'
                                     % (path, line, key, text))
            if line not in out:
                out[line] = []
                order.append(line)
            out[line].append(row)
    need(out, '%s has no data rows' % path)
    return {line: out[line] for line in order}


def load_inputs(root, geo):
    size = geo['size']
    heights, types, shas = {}, {}, {}
    for arm in HEIGHT_ARMS:
        name = arm + '.f64be'
        heights[arm], shas[name] = read_f64(root, name, size)
    for arm in TYPE_ARMS:
        name = arm + '.u8'
        types[arm], shas[name] = read_u8(root, name, size, TYPE_COUNT - 1)
    sea_weight, shas['sea-weight.f64be'] = read_f64(root, 'sea-weight.f64be', size)
    active, shas['active.u8'] = read_u8(root, 'active.u8', size)
    return {'heights': heights, 'types': types, 'seaWeight': sea_weight,
            'active': active, 'profiles': read_profiles(root / 'profiles.csv'),
            'sha256': shas}


# ------------------------------------------------------------------ 统计

def column(rows, key):
    values = []
    for row in rows:
        value = row.get(key)
        values.append(float('nan') if value is None else float(value))
    return np.asarray(values, dtype=np.float64)


def height_stats(grid):
    water = grid < SEA
    return {'minY': float(grid.min()), 'maxY': float(grid.max()),
            'medianY': float(np.median(grid)),
            'p95Y': float(np.percentile(grid, 95)),
            'relief': float(grid.max() - grid.min()),
            'waterPct': float(water.mean() * 100.0)}


def compare(base, cand):
    """翻水面按"像素等级"数，不由后验高度推断任何选型。"""
    delta = cand - base
    absolute = np.abs(delta)
    land_to_sea = int(((base >= SEA) & (cand < SEA)).sum())
    sea_to_land = int(((base < SEA) & (cand >= SEA)).sum())
    return {'waterPctBase': float((base < SEA).mean() * 100.0),
            'waterPctCand': float((cand < SEA).mean() * 100.0),
            'waterDeltaPts': float((cand < SEA).mean() * 100.0 - (base < SEA).mean() * 100.0),
            'waterFlipLandToSea': land_to_sea, 'waterFlipSeaToLand': sea_to_land,
            'waterFlipTotal': land_to_sea + sea_to_land,
            'maxAbsDiff': float(absolute.max()),
            'medianAbsDiff': float(np.median(absolute)),
            'p99AbsDiff': float(np.percentile(absolute, 99.0)),
            'meanSignedDiff': float(delta.mean()),
            'maxRise': float(max(0.0, delta.max())),
            'maxDrop': float(min(0.0, delta.min())),
            'changedPixels': int((absolute > 0.0).sum()),
            'changedPixelsOver0p5': int((absolute > 0.5).sum())}


def id_ranges(ids):
    """把 id 列表压成「5-17、18-22」这样的区间串（避免在图上刷一整行数字）。"""
    if not ids:
        return '无'
    parts, start, prev = [], ids[0], ids[0]
    for value in ids[1:]:
        if value == prev + 1:
            prev = value
            continue
        parts.append(str(start) if start == prev else '%d-%d' % (start, prev))
        start = prev = value
    parts.append(str(start) if start == prev else '%d-%d' % (start, prev))
    return '、'.join(parts)


def share_text(pixels, share_pct):
    """图例用：0 像素的条目**明写"未出现"**；非零但极小的写 "<0.01%"（别让 0.00% 冒充"没有"）。"""
    if pixels <= 0:
        return '未出现'
    return '<0.01%' if 0.0 < share_pct < 0.01 else '%.2f%%' % share_pct


def type_stats(ids, palette):
    """面积表：**只由 u8 配方 id 直方图**统计，不取后验高度反推。"""
    counts = np.bincount(ids.ravel().astype(np.int64), minlength=TYPE_COUNT)
    total = float(ids.size)
    rows = []
    for index in range(TYPE_COUNT):
        rows.append({'id': index, 'slug': palette['slugs'][index],
                     'name': palette['names'][index],
                     'family': 'land' if index < LAND_TYPES else 'ocean',
                     'pixels': int(counts[index]),
                     'sharePct': round(100.0 * float(counts[index]) / total, 4),
                     'absent': bool(counts[index] == 0)})
    return {'pixels': int(ids.size), 'families': {'land': rows[:LAND_TYPES],
                                                  'ocean': rows[LAND_TYPES:]},
            'entries': rows,
            'distinctIds': int((counts > 0).sum()),
            'absentIds': [row['id'] for row in rows if row['absent']],
            'absenceRule': '某臂计数为 0 = 该臂没有选中这条配方，'
                           '不表示配方不存在，也不表示"只有出现的这些存在"'}


def weight_stats(field):
    """权重场统计：区分「真越界」与「归一化 1-ULP 舍入」。

    ★ 实测：max = 1 + 2.22e-16（3 像素）是归一化的 1-ULP 舍入，**不是**分布缺陷。
    本函数因此同时给出：
      outside0to1        —— 任何 <0 或 >1 的像素数（含 ULP）
      outsideBeyondUlp   —— |偏差| > ULP_TOL(1e-12) 的像素数（才是真越界/缺陷候选）
      outOfRangeMaxAmount / MinAmount —— 越界量（非负幅度）的最大/最小值
      outOfRangeClass    —— 'none' / 'float-roundoff'（≤ 容差）/ 'defect'（> 容差）
    原始数据**不裁剪、不替换**：越界像素照原值参与其余统计。
    """
    flat = field.ravel()
    below = flat < 0.0
    above = flat > 1.0
    outside = below | above
    deviation = np.where(above, flat - 1.0, np.where(below, -flat, 0.0))
    beyond = outside & (deviation > ULP_TOL)
    stats = {'min': float(flat.min()), 'max': float(flat.max()),
             'mean': float(flat.mean()), 'median': float(np.median(flat)),
             'p01': float(np.percentile(flat, 1)), 'p99': float(np.percentile(flat, 99)),
             'fractionEq0': float((flat == 0.0).mean()),
             'fractionEq1': float((flat == 1.0).mean()),
             'fractionOver0': float((flat > 0.0).mean()),
             'fractionOverHalf': float((flat > 0.5).mean()),
             'outside0to1': int(outside.sum()),
             'outsideBeyondUlp': int(beyond.sum()),
             'ulpTolerance': ULP_TOL,
             'outOfRangeMaxAmount': float(deviation.max()),
             'outOfRangeMinAmount': float(deviation[outside].min()) if outside.any() else 0.0,
             'outOfRangeClass': 'none'}
    if int(beyond.sum()) > 0:
        stats['outOfRangeClass'] = 'defect'      # 超出 1-ULP 容差：按真越界报，不得当成舍入
        stats['outOfRangeBeyondUlpMaxAmount'] = float(deviation[beyond].max())
    elif int(outside.sum()) > 0:
        stats['outOfRangeClass'] = 'float-roundoff'
    if int(outside.sum()) > 0:
        stats['outOfRangePixels'] = int(outside.sum())
        stats['outOfRangeAmounts'] = [float(v) for v in
                                      np.unique(deviation[outside])[:8]]
    return stats


def active_stats(field):
    values, counts = np.unique(field, return_counts=True)
    pairs = [{'value': int(v), 'pixels': int(c)} for v, c in zip(values, counts)]
    pairs.sort(key=lambda item: -item['pixels'])
    return {'distinct': int(values.size), 'min': int(field.min()), 'max': int(field.max()),
            'histogram': pairs[:16], 'histogramTruncated': len(pairs) > 16}


def profile_check(profiles, heights, geo):
    """CSV ↔ 栅格互校（非致命）：全幅行应当正好落在像素行上并与 raw 逐点相符。"""
    size, step = geo['size'], geo['step']
    ox, oz = geo['origin']
    report = {'localLines': 0, 'fullLines': 0, 'gridCrossCheck': {},
              'maxDeviationUntilNow': 0.0}
    dev_max = 0.0
    for line, rows in profiles.items():
        if line.startswith('local-'):
            report['localLines'] += 1
            continue
        report['fullLines'] += 1
        xs, zs = column(rows, 'x'), column(rows, 'z')
        finite = np.isfinite(xs) & np.isfinite(zs)
        if finite.sum() < 8:
            report['gridCrossCheck'][line] = 'evaluable: false (数值不足)'
            continue
        if not np.allclose(zs[finite], zs[finite][0], atol=1e-6):
            report['gridCrossCheck'][line] = 'evaluable: false (z 不是常数)'
            continue
        row_pos = (zs[finite][0] - oz) / step
        row = int(round(row_pos))
        if abs(row_pos - row) > 1e-6 or not 0 <= row < size:
            report['gridCrossCheck'][line] = 'evaluable: false (z 不在像素行上)'
            continue
        col_pos = (xs - ox) / step
        cols = np.round(col_pos).astype(np.int64)
        if np.abs(col_pos[finite] - cols[finite]).max() > 1e-6:
            report['gridCrossCheck'][line] = 'evaluable: false (x 不在像素列上)'
            continue
        inside = finite & (cols >= 0) & (cols < size)
        deviation = {}
        for key in PROFILE_SERIES:
            got = column(rows, key)
            mask = inside & np.isfinite(got)
            if not mask.any():
                deviation[key] = None
                continue
            want = heights[SERIES_TO_ARM[key]][row, cols[mask]]
            value = float(np.abs(got[mask] - want).max())
            deviation[key] = value
            dev_max = max(dev_max, value)
        report['gridCrossCheck'][line] = {'pixelRow': row,
                                          'samples': int(inside.sum()),
                                          'maxAbsDeviationBySeries': deviation}
    report['maxDeviationUntilNow'] = dev_max
    report['contractRows'] = [int(math.floor((k + 0.5) / 6.0 * size)) for k in range(6)]
    observed = [entry['pixelRow'] for entry in report['gridCrossCheck'].values()
                if isinstance(entry, dict)]
    # 没有可比的全幅行时写 null（"未评估"），不写成 False —— 后者会被误读成"行位置不合契约"
    report['rowsMatchContract'] = (sorted(observed) == report['contractRows']
                                   if observed else None)
    return report


# ------------------------------------------------------------------ 图元

def shade_rgb(height, step, palette):
    return palette['shade'](height, step, SEA)


def type_image(ids, palette):
    return Image.fromarray(np.uint8(np.clip(palette['colors'][ids], 0, 255)))


def ramp(values, stops):
    tones = np.clip(np.asarray(values, dtype=np.float64), 0.0, 1.0)
    keys = [stop[0] for stop in stops]
    return np.stack([np.interp(tones, keys, [stop[1][c] for stop in stops])
                     for c in range(3)], axis=-1)


def legend_col_width(items):
    label_w = max(tw(text, font(20)) for _color, text in items)
    return LEG_SW + 12 + label_w + 24


def legend_size(blocks):
    """legend = [(标题, [(色, 文字), ...], 列数), ...]；返回整块图例的 (宽, 高)。"""
    width = height = 0
    for title, items, cols in blocks:
        col_w = legend_col_width(items)
        rows = int(math.ceil(len(items) / float(cols)))
        width = max(width, cols * col_w)
        height += (30 if title else 0) + rows * LEG_ROW + 10
    return width, height


def legend_draw(draw, x0, y0, blocks):
    """图例分块画：每块自带标题，绝不把两个场的条目混成一列（否则图例会撒谎）。"""
    y = y0
    for title, items, cols in blocks:
        if title:
            draw.text((x0, y), title, font=font(19), fill=INK)
            y += 30
        col_w = legend_col_width(items)
        rows = int(math.ceil(len(items) / float(cols)))
        for index, (color, text) in enumerate(items):
            col, row = divmod(index, rows)      # 列优先：id 顺着第一列读下去
            x = x0 + col * col_w
            ty = y + row * LEG_ROW
            draw.rectangle((x - 2, ty + 4, x + LEG_SW + 2, ty + LEG_SW + 12), fill='#8f8b7e')
            draw.rectangle((x, ty + 6, x + LEG_SW, ty + LEG_SW + 10),
                           fill=tuple(int(v) for v in color))
            draw.text((x + LEG_SW + 12, ty + 6), text, font=font(20), fill=INK)
        y += rows * LEG_ROW + 10


def figure(images, titles, heading, lines, note, legend=None):
    """原生 1:1 网格：面板绝不缩放；返回 (canvas, spots) 供逐像素守门。"""
    width, height = images[0].size
    need(all(im.size == (width, height) for im in images), 'panel images differ in size')
    pad, gap, label_h, tail = 40, 40, 40, 60
    line_y, line_dy = 78, 30
    head = line_y + line_dy * len(lines) + 44
    grid_w = pad * 2 + width * len(images) + gap * (len(images) - 1)
    text_w = max([tw(text, font(20)) for text in list(lines) + [note]]
                 + [tw(heading, font(38))])
    legend_w, legend_h = legend_size(legend) if legend else (0, 0)
    canvas_w = int(max(grid_w, text_w + pad * 2 + 8, legend_w + pad * 2))
    canvas_h = head + label_h + height + legend_h + 20 + tail
    canvas = Image.new('RGB', (canvas_w, canvas_h), BG)
    draw = ImageDraw.Draw(canvas)
    draw.text((pad, 16), heading, font=fit(heading, canvas_w - pad * 2, 38), fill=INK)
    for index, text in enumerate(lines):
        draw.text((pad, line_y + index * line_dy), text,
                  font=fit(text, canvas_w - pad * 2, 20), fill=DIM)
    spots = []
    for index, (image, title) in enumerate(zip(images, titles)):
        x = pad + index * (width + gap)
        draw.text((x, head - 30), title, font=fit(title, width, 24), fill=INK)
        canvas.paste(image, (x, head + label_h))
        spots.append((image, (x, head + label_h)))
    if legend:
        legend_draw(draw, pad, head + label_h + height + 14, legend)
    draw.text((pad, canvas_h - tail + 12), note,
              font=fit(note, canvas_w - pad * 2, 20), fill=DIM)
    return canvas, spots


def register(canvas, spots, name, dest, entries):
    canvas.save(dest / name, optimize=True)
    entries[name] = (canvas, spots)


def verify_paste(entries, dest):
    """逐像素守门：画布上每块面板必须与其原生图逐位相同（证明没有缩放/重采样）。

    在**落盘后**读回文件再比 —— 直接在内存画布上比会漏掉存盘路径的缩放/量化；
    不符合契约时把所有已写出的图删掉，避免残留一张"看着像结论"的图。
    """
    report = {}
    try:
        for name, (canvas, spots) in entries.items():
            path = dest / name
            need(path.is_file(), 'figure missing after write: %s' % path)
            with Image.open(path) as handle:
                rgb = handle.convert('RGB')
                size = rgb.size
                flat = np.asarray(rgb)
            for image, (px, py) in spots:
                want = np.asarray(image.convert('RGB'))
                got = flat[py:py + want.shape[0], px:px + want.shape[1]]
                need(got.shape == want.shape,
                     '%s: 面板 (%d,%d) 落在画布外' % (name, px, py))
                need(np.array_equal(got, want),
                     '%s: 面板 (%d,%d) 与原生图不逐位相同（被缩放/重采样了？）' % (name, px, py))
            report[name] = {'size': [size[0], size[1]], 'panels': len(spots),
                            'panelsPixelIdentical': True}
    except InputError:
        for name in entries:
            (dest / name).unlink(missing_ok=True)
        (dest / 'metrics.json').unlink(missing_ok=True)
        raise
    return report


# ------------------------------------------------------------------ 剖面

def nice_ticks(lo, hi):
    span = max(hi - lo, 1e-9)
    step = 100.0
    for candidate in (1, 2, 5, 10, 20, 25, 50, 100):
        step = float(candidate)
        if span / candidate <= 6:
            break
    start = math.ceil(lo / step) * step
    return [start + index * step for index in range(int((hi - start) / step) + 1)]

def band(draw, box, lo, hi):
    x, y, width, height = box
    span = max(hi - lo, 1e-9)
    draw.rectangle((x, y, x + width, y + height), outline='#c9c5b6')
    # 固定轴的刻度：320 与 300 只差 20 格（约 10px）会撞在一起，故不印 320（轴顶已由标题写明）
    ticks = (-64.0, 0.0, SEA, 100.0, 200.0, 300.0) if (lo, hi) == (
        HEIGHT_LO, HEIGHT_HI) else nice_ticks(lo, hi)
    for value in ticks:
        if not lo <= value <= hi:
            continue
        py = y + height - (value - lo) / span * height
        draw.line((x, py, x + width, py),
                  fill='#9cbad3' if abs(value - SEA) < 1e-6 else '#dedbd0', width=1)
        label = '%d' % int(round(value))
        # 刻度数字右对齐贴在图带左侧，且钳在图带内 —— 绝不伸进上方的说明条
        ty = min(max(py - 9, y + 2), y + height - 18)
        draw.text((x - 12 - tw(label, font(14)), ty), label, font=font(14), fill=DIM)


def polyline(draw, box, xs, ys, lo, hi, color):
    x, y, width, height = box
    span = max(hi - lo, 1e-9)
    x0, x1 = float(xs[0]), float(xs[-1])
    dx = max(x1 - x0, 1e-9)
    points = []
    for index in range(len(xs)):
        value = float(min(max(ys[index], lo), hi))
        points.append((x + (float(xs[index]) - x0) / dx * width,
                       y + height - (value - lo) / span * height))
    if len(points) > 1:
        draw.line(points, fill=color, width=2)


def profile_items(profiles, kind):
    """把 CSV 行整理成可画的条目；kind='full' 全幅横剖面，kind='local' 法向剖面。

    返回 (items, total)：total 是该类行在 CSV 里的**真实**条数（画不下时用来如实写"只画了前 N 条"）。
    """
    wanted = [line for line in profiles
              if (line.startswith('local-') if kind == 'local'
                  else not line.startswith('local-'))]
    total = len(wanted)
    if kind == 'local':
        wanted = wanted[:MAX_LOCAL]
    items = []
    for line in wanted:
        rows = profiles[line]
        xs = column(rows, 'x')
        axis = column(rows, 'distance') if kind == 'local' else xs
        if kind == 'local' and not np.isfinite(axis).any():
            axis = np.arange(len(rows), dtype=np.float64)
        order = np.argsort(np.where(np.isfinite(axis), axis, 0.0), kind='stable')
        axis = axis[order]
        data = {key: column(rows, key)[order] for key in PROFILE_SERIES}
        zs = column(rows, 'z')[order]
        z_value = float(np.nanmedian(zs)) if np.isfinite(zs).any() else float('nan')
        items.append({'line': line, 'axis': axis, 'columns': data, 'z': z_value})
    return items, total


def profiles_figure(items, heading, sub_lines, note, fixed_axis, dest, name, entries):
    pad, tail = 40, 66
    ph = 200 if fixed_axis else 150
    gap = 30
    line_y, line_dy = 78, 28
    head = line_y + line_dy * len(sub_lines) + 16
    pw = 1400
    canvas_w = pad * 2 + pw
    canvas_h = head + len(items) * (ph + LABEL_STRIP + TICK_STRIP + gap) + tail
    canvas = Image.new('RGB', (canvas_w, canvas_h), BG)
    draw = ImageDraw.Draw(canvas)
    draw.text((pad, 16), heading, font=fit(heading, canvas_w - pad * 2, 34), fill=INK)
    for index, text in enumerate(sub_lines):
        draw.text((pad, line_y + index * line_dy), text,
                  font=fit(text, canvas_w - pad * 2, 20), fill=DIM)
    box = (pad + 200, 0, pw - 200 - 20, ph)
    for index, item in enumerate(items):
        # 每行 = 标签横条 + 图带 + 刻度横条：三者互不重叠，标签绝不压图、也不压刻度
        y0 = head + index * (ph + LABEL_STRIP + TICK_STRIP + gap) + LABEL_STRIP
        keep = np.isfinite(item['axis'])
        if int(keep.sum()) < 2:
            continue
        axis = item['axis'][keep]
        values = {key: arr[keep] for key, arr in item['columns'].items()}
        if fixed_axis:
            lo, hi = HEIGHT_LO, HEIGHT_HI
        else:
            stack = np.concatenate([arr[np.isfinite(arr)] for arr in values.values()]
                                   or [np.zeros(1)])
            lo, hi = float(stack.min()) - 6.0, float(stack.max()) + 6.0
            lo, hi = min(lo, SEA), max(hi, SEA)
        current = (box[0], y0, box[2], box[3])
        band(draw, current, lo, hi)
        for key, label, color in SERIES_STYLE:
            series = values[key]
            mask = np.isfinite(series)
            if mask.sum() < 2:
                continue
            polyline(draw, current, axis[mask], series[mask], lo, hi, color)
        water = values['morph']
        water_pct = 100.0 * float((water[np.isfinite(water)] < SEA).mean()) \
            if np.isfinite(water).any() else float('nan')
        captions = [item['line'],
                    'z=%.1f' % item['z'] if np.isfinite(item['z']) else 'z=?',
                    '本行 B 水 %.0f%%' % water_pct]
        if not fixed_axis:
            captions.append('Y %d..%d' % (int(lo), int(hi)))
        tx = pad
        for caption in captions:
            draw.text((tx, y0 - LABEL_STRIP + 3), caption, font=font(15), fill=INK)
            tx += tw(caption, font(15)) + 22
        for fraction in (0.0, 0.5, 1.0):
            value = axis[0] + (axis[-1] - axis[0]) * fraction
            # 采样点常落在 *.5 块上；整数与半块分别格式化，避免 -3642.5 被印成 -3642
            label = '%.0f' % value if abs(value - round(value)) < 1e-9 else '%.1f' % value
            label_w = tw(label, font(13))
            px = current[0] + fraction * current[2] - label_w / 2.0
            px = min(max(px, current[0]), current[0] + current[2] - label_w)
            draw.text((px, y0 + ph + 6), label, font=font(13), fill=DIM)
    draw.text((pad, canvas_h - tail + 12), note,
              font=fit(note, canvas_w - pad * 2, 18), fill=DIM)
    register(canvas, [], name, dest, entries)


# ------------------------------------------------------------------ 各图

def coord_lines(geo, with_seed=True, with_sea=False):
    """坐标行：中心/原点按"块坐标最小值 0.5"显示，避免 -3642.5 被印成 -3642 造成误读。"""
    cx, cz = geo['center']
    ox, oz = geo['origin']
    parts = []
    if with_seed:
        parts.append('seed=%s' % geo['seed'])
    parts.append('%d² 采样、步长 %d 块，覆盖 %d×%d 块（像素中心跨度 %d 块）'
                 % (geo['size'], geo['step'], geo['extent'][0], geo['extent'][1],
                    (geo['size'] - 1) * geo['step']))
    parts.append('中心块 (%.1f, %.1f)' % (cx, cz))
    parts.append('原点 (%.1f, %.1f)' % (ox, oz))
    if with_sea:
        parts.append('海平面 Y=%.0f' % SEA)
    return '｜'.join(parts)


def draw_natives(geo, data, palette, dest, entries):
    natives = {}
    for arm in HEIGHT_ARMS:
        image = Image.fromarray(shade_rgb(data['heights'][arm], geo['step'], palette))
        name = NATIVE_NAME[arm]
        register(image, [], name, dest, entries)
        natives[arm] = image
    for arm in ('type-a', 'type-b'):
        image = type_image(data['types'][arm], palette)
        name = 'native-types-%s.png' % arm[-1]
        register(image, [], name, dest, entries)
    return natives


def draw_pairs(geo, natives, dest, entries):
    geometry = coord_lines(geo)
    native_note = ('两幅均为 %d² 原生像素（1 像素 = %d 块），未缩放；图写完后本工具会逐像素复核'
                   '粘贴处与原图是否完全一致，不一致则非零退出（见 metrics.json 的 figures）。'
                   % (geo['size'], geo['step']))
    full, full_spots = figure(
        [natives['baseline-a'], natives['regional-morph']],
        ['A 旧链·含旧海岸', 'B P3·公共节点·无海岸'],
        'P3 海陆统一区域权重｜A（含旧海岸）vs B（无海岸）',
        [geometry,
         '左 = A：旧链 + 旧海岸（= 此前"距离修复后"那条 B）。右 = B：新站点类型 + 公共节点输出，'
         '「海岸层被整体移除」。',
         '★ 两臂「不同源」：海岸没了、布局也换了 → 这张图只回答"整体观感是否还在"，'
         '「不能」归因到任何单条改动。'],
        native_note)
    register(full, full_spots, 'pair-full.png', dest, entries)
    ordinary, ordinary_spots = figure(
        [natives['baseline-base'], natives['regional-morph']],
        ['base 旧链·无海岸', 'B P3·无海岸'],
        '无海岸隔离对照｜旧链去海岸（base）vs 新链（B）',
        [geometry,
         '左 = base：「旧链去掉海岸」。右 = B：新链无海岸。两臂都在"无海岸"口径上。',
         '★ 这隔离了"旧 vs 新"，但「布局仍然不同」（站点集合/权重都换了）'
         '→ 差异 = 布局变化 + 常态过渡，仍不能只看图就归因到过渡。'],
        native_note)
    register(ordinary, ordinary_spots, 'pair-ordinary.png', dest, entries)
    smoothing, smoothing_spots = figure(
        [natives['regional-hard'], natives['regional-morph']],
        ['hard 硬切换（无过渡）', 'B 公共节点（常态过渡）'],
        '单变量对照｜同一布局：硬切换 vs 常态过渡',
        [geometry,
         '左 = hard：新站点类型 + 「硬切换」（站点/权重/布局与右图完全相同）。'
         '右 = B：同一布局走公共节点常态过渡。',
         '★ 两幅「布局完全相同」→ 差异「只」来自"硬切换 → 常态过渡"这一条。'
         '要把"布局变了"和"过渡生效了"分开看，只能看这一张。'],
        native_note)
    register(smoothing, smoothing_spots, 'pair-smoothing.png', dest, entries)


def draw_types(geo, data, palette, dest, entries):
    names = palette['names']
    palette_arr = palette['colors']
    images = [type_image(data['types']['type-a'], palette),
              type_image(data['types']['type-b'], palette)]
    shares, counts = {}, {}
    for arm in ('type-a', 'type-b'):
        counts[arm] = np.bincount(data['types'][arm].ravel().astype(np.int64),
                                  minlength=TYPE_COUNT)
        shares[arm] = counts[arm] / float(data['types'][arm].size) * 100.0
    legend = []
    absent = {'type-a': [], 'type-b': []}
    for index in range(TYPE_COUNT):
        tag = '陆' if index < LAND_TYPES else '海'
        parts = []
        for arm, label in (('type-a', 'A'), ('type-b', 'B')):
            if int(counts[arm][index]) == 0:
                absent[arm].append(index)
            parts.append('%s %s' % (label, share_text(int(counts[arm][index]),
                                                      float(shares[arm][index]))))
        legend.append((palette_arr[index], '%2d %s %s  %s'
                       % (index, tag, names[index], ' / '.join(parts))))
    geometry = coord_lines(geo, with_seed=False)
    absent_a = id_ranges(absent['type-a'])
    absent_b = id_ranges(absent['type-b'])
    canvas, spots = figure(
        images, ['type-a 旧链·海岸处理之前的内部配方',
                 'type-b 新链·加权 DOMINANT 主导配方'],
        '类型图｜下标空间 0..22 = 18 条陆地 + 5 条海洋（复合条目是两条的混合，不是原子）',
        [geometry,
         '左 = type-a：「旧链海岸处理之前」的内部配方（海侧分支 / 陆地 dominant 口径），'
         '既不是最终的"实际海岸配方"，也不是全部潜在候选。'
         '右 = type-b：新链「加权 DOMINANT 主导」配方（加权后的最终主导）。',
         '占比「由 u8 配方 id 直方图统计」，不由后验高度反推；每个 id 只是'
         '"该像素被选中 / 加权主导的那一条"，「不是」所有参与过选择的候选。',
         '某臂某 id 计 0 → 图例明写"未出现"：这只说明该臂没选中它，「不表示配方不存在」，'
         '也不能反推"只有出现的这几条存在"（本例 A 未出现 %s；B 未出现 %s）。'
         % (absent_a, absent_b),
         '复合条目（如 6 平原+谷地）取两成员色中点再提亮 —— 它确实就是两条配方的混合地形，'
         '不折进任何一个原子。18..22 是海洋 5 条在目录里的「下标」，不保证本臂都出现。'],
        '色块与像素同源（同一 palette 数组）；两幅面板均 %d² 原生像素，未缩放。' % geo['size'],
        legend=[('配方（下标 0..22；"未出现"= 该臂没选中，不等于配方不存在）', legend, 3)])
    register(canvas, spots, 'pair-types.png', dest, entries)


def draw_transition(geo, data, dest, entries):
    weight = data['seaWeight']
    stats = weight_stats(weight)
    stops = ((0.0, (242, 240, 232)), (0.35, (178, 206, 226)),
             (0.7, (84, 140, 190)), (1.0, (20, 58, 110)))
    weight_rgb = ramp(weight, stops)
    outside = (weight < 0.0) | (weight > 1.0)
    # 越界像素标成品红，绝不截断/裁剪数据；但**文案必须区分**"1-ULP 舍入"与"真越界"
    weight_rgb[outside] = np.array([214.0, 40.0, 140.0])
    weight_image = Image.fromarray(np.uint8(np.clip(weight_rgb, 0, 255)))

    active = data['active']
    values = np.unique(active)
    weight_legend = [(np.array(stops[0][1], float), 'weight = 0'),
                     (np.array(stops[1][1], float), 'weight ≈ 0.35'),
                     (np.array(stops[2][1], float), 'weight ≈ 0.7'),
                     (np.array(stops[3][1], float), 'weight = 1（%d 像素，精确等于 1.0）'
                      % int((weight == 1.0).sum()))]
    if stats['outside0to1']:
        weight_legend.append(
            (np.array([214.0, 40.0, 140.0], float),
             '原值 ≠ [0,1]：%d 像素｜最大越界量 %+.2e（未截断）'
             % (stats['outside0to1'], stats['outOfRangeMaxAmount'])))
    if values.size <= 16:
        active_stops = ((0.0, (232, 232, 226)), (0.45, (236, 190, 110)),
                        (1.0, (150, 58, 34)))
        span = max(float(values.max()), 1.0)
        tone = ramp(active.astype(float) / span, active_stops)
        active_image = Image.fromarray(np.uint8(np.clip(tone, 0, 255)))
        active_legend = [(ramp(np.array([float(value) / span]), active_stops)[0],
                          '值 %d：%d 像素' % (int(value), int((active == value).sum())))
                         for value in values]
        active_title = 'active.u8 实测取值（%d 个离散值，口径由导出方定义）' % values.size
    else:
        tone = ramp(active.astype(float) / max(float(active.max()), 1.0),
                    ((0.0, (232, 232, 226)), (1.0, (150, 58, 34))))
        active_image = Image.fromarray(np.uint8(np.clip(tone, 0, 255)))
        active_legend = [(np.array([150.0, 58.0, 34.0], float),
                          '最大值 %d（共 %d 个离散值）' % (int(active.max()), values.size))]
        active_title = 'active.u8 连续取值（%d 个离散值，按最大值归一）' % values.size

    canvas, spots = figure(
        [weight_image, active_image],
        ['sea-weight.f64be 原值', active_title],
        '选择器侧的两个场｜sea-weight（连续）与 active（离散）',
        ['%d² 原生像素、步长 %d 块｜海平面 Y=%.0f｜高度轴 %.0f..%.0f'
         % (geo['size'], geo['step'], SEA, HEIGHT_LO, HEIGHT_HI)]
        + weight_range_note(stats)
        + ['sea-weight 按「原值」上色：不假设归一化、不裁剪数据；'
           'active 按「实测取值」上色，本工具不解释其业务语义。',
           '★ 这两个场只说明"哪些站点参与了加权"，「不等于」"哪些配方被选中"：'
           'weight > 0 只表示候选存在，实际选中看 type-b 的 id。'],
        '面板均为 %d² 原生像素，未缩放；两幅同窗口、同尺寸，可直接叠图比对。' % geo['size'],
        legend=[('左图：sea-weight 原值', weight_legend, 2),
                ('右图：active 实测取值', active_legend, 3)])
    register(canvas, spots, 'transition-map.png', dest, entries)


def weight_range_note(stats):
    """把"1-ULP 舍入"与"真越界"分开说 —— 实测 1+2.22e-16（3 px）属前者，不是分布缺陷。

    返回若干**短**行：一行超长句会把画布撑宽、或被自动缩到看不清，这里按行拆开写。
    """
    if stats['outside0to1'] == 0:
        return ['实测范围 [%.6f, %.6f]：全部落在 [0,1] 内，无任何越界像素。'
                % (stats['min'], stats['max'])]
    amounts = stats.get('outOfRangeAmounts', [])
    amount_text = '、'.join('%.2e' % value for value in amounts) or 'n/a'
    head = ('实测范围 [%.10g, %.17g]｜%d 像素不等于 [0,1]，越界幅度 %s。'
            % (stats['min'], stats['max'], stats['outside0to1'], amount_text))
    scale = ('outOfRangeMaxAmount = %.3e 格（原值不裁剪、不替换）。'
             % stats['outOfRangeMaxAmount'])
    if stats['outOfRangeClass'] == 'float-roundoff':
        return [head + scale,
                '★ 越界量全部 ≤ %.0e 容差 → 这是归一化在 1.0 附近的 1-ULP 浮点舍入，'
                '「不是」海侧权重的分布缺陷，也不改变任何读图结论。' % ULP_TOL,
                '品红只标出这些像素的位置（1+2.22e-16 量级、仅个位数像素），数据未被裁剪或改写。']
    return [head + scale,
            '★ 其中 %d 像素越界幅度 > %.0e 容差（最大 %.3e）→ 已超出浮点舍入，'
            '按"真越界"报（outOfRangeClass = defect），请回导出端核对。'
            % (stats['outsideBeyondUlp'], ULP_TOL,
               stats.get('outOfRangeBeyondUlpMaxAmount', stats['outOfRangeMaxAmount']))]


def diff_panel(base, cand):
    """暖 = cand 更高、冷 = cand 更低（与 render-unified-ab.delta_img 同族，极性自检）。"""
    delta = cand - base
    vmax = max(1.0, float(np.percentile(np.abs(delta), 99.5)))
    tone = np.clip(delta / vmax, -1.0, 1.0)
    red = np.where(tone > 0, 255.0, 128.0 + 127.0 * (1.0 + tone))
    green = 128.0 + 127.0 * (1.0 - np.abs(tone))
    blue = np.where(tone < 0, 255.0, 128.0 + 127.0 * (1.0 - tone))
    rgb = np.uint8(np.clip(np.stack([red, green, blue], axis=-1), 0, 255))
    return Image.fromarray(rgb), vmax


def assert_diff_polarity():
    probe_base = np.zeros((8, 8))
    probe_cand = np.zeros((8, 8))
    probe_cand[:2, :] = 4.0
    probe_cand[-2:, :] = -4.0
    image, _vmax = diff_panel(probe_base, probe_cand)
    rgb = np.asarray(image.convert('RGB')).astype(int)
    rise, drop = rgb[1, 4], rgb[-2, 4]
    need(rise[0] > rise[2], 'diff polarity broken: 抬升不是暖色 rgb=%s' % rise.tolist())
    need(drop[2] > drop[0], 'diff polarity broken: 下沉不是冷色 rgb=%s' % drop.tolist())


def draw_diffs(geo, data, dest, entries):
    assert_diff_polarity()
    clamps = {}
    for name, base_arm, cand_arm, heading, extra in (
            ('diff.png', 'baseline-a', 'regional-morph',
             '差图｜A（含旧海岸）→ B（P3 无海岸）',
             '两臂不同源（海岸被移除 + 布局更换）→ 大块色斑是"布局换了"，不能读成某条算子。'),
            ('diff-smoothing.png', 'regional-hard', 'regional-morph',
             '差图｜hard（硬切换）→ B（常态过渡）｜「同一布局」',
             '同布局单变量：色斑只出现在站点交界处，说明过渡只改交界、不改站点内部。')):
        base = data['heights'][base_arm]
        cand = data['heights'][cand_arm]
        image, vmax = diff_panel(base, cand)
        clamps[name] = vmax
        stats = compare(base, cand)
        canvas, spots = figure(
            [image], ['%s → %s' % (base_arm, cand_arm)], heading,
            ['%d² 原生像素、步长 %d 块｜色标满量程 ±%.1f 格（|Δ| 的 P99.5）｜翻水面 %d 像素'
             % (geo['size'], geo['step'], vmax, stats['waterFlipTotal']),
             '逐像素差 = 右 − 左；最大抬升 %+.1f 格，最大下沉 %+.1f 格；'
             '|Δ| > 0.5 的像素占 %.2f%%。'
             % (stats['maxRise'], stats['maxDrop'],
                100.0 * float((np.abs(cand - base) > 0.5).mean())),
             extra],
            '色标按 |Δ| P99.5 对称截断（超出部分饱和，不改变正负号）；面板 %d² 原生像素，未缩放。'
            % geo['size'],
            legend=[('差图色标（暖升冷降）',
                     [(np.array([255.0, 128.0, 128.0]), '暖红 = 右臂更高'),
                      (np.array([255.0, 255.0, 255.0]), '白 = 差值为 0'),
                      (np.array([128.0, 128.0, 255.0]), '冷蓝 = 右臂更低')], 3)])
        register(canvas, spots, name, dest, entries)
    return clamps


def draw_profiles(geo, data, dest, entries):
    style_note = '；'.join('%s = %s' % (key, label) for key, label, _color in SERIES_STYLE)
    items, total_full = profile_items(data['profiles'], 'full')
    if items:
        profiles_figure(
            items, '全幅横剖面｜6 条固定行，「共用 Y 轴 %.0f..%.0f」（未各自归一）'
            % (HEIGHT_LO, HEIGHT_HI),
            [coord_lines(geo, with_seed=False),
             '采样：横轴步长 %d 块（取自像素行），行位置 = floor((k+0.5)/6 × %d)；'
             '横轴 = 世界 x（块）；蓝线 = 海平面 Y=%.0f。' % (geo['step'], geo['size'], SEA),
             '线色：%s。' % style_note,
             '共用纵轴是重点：A 与 B 的高低差直接可比，不会因为各自缩放而看起来"一样"。'],
            '数据来自 profiles.csv（导出方产出），不是本工具重采样栅格；'
            '面板按 1 点 1 列的折线绘制，不做平滑。共 %d 条全幅行，全部画出。' % total_full,
            True, dest, 'profiles-full.png', entries)
    items, total_local = profile_items(data['profiles'], 'local')
    if items:
        profiles_figure(
            items, 'local 法向剖面｜沿站点交界法向取样（每条按自身范围缩放）',
            [local_geometry_note(geo, items, grid_step=geo['step']),
             '横轴 = 法向距离（distance 列，单位块；缺该列时退化为采样序号）；'
             '蓝线 = 海平面 Y=%.0f。' % SEA,
             '线色：%s。' % style_note,
             '注意：本图每行按自身 min/max 缩放（「不是」共用轴），'
             '因此不能据此比较各行、也不能与全幅剖面比绝对高度。'],
            ('只画前 %d 条 local-*（CSV 共 %d 条）。' % (len(items), total_local))
            if total_local > len(items) else 'CSV 共 %d 条 local-*，全部画出，无截断。' % total_local,
            False, dest, 'profiles-local.png', entries)


def local_geometry_note(geo, items, grid_step):
    """local 剖面的采样口径行。

    ★ 必须点明两套步长不同：local 沿法向是 1 块采样，而地形栅格是 grid_step 块/像素。
    否则标题里的"1401² 采样、步长 5 块"会被误读成本图也是 5 块步长。
    """
    distances = np.concatenate([item['axis'] for item in items]) if items else np.zeros(0)
    finite = distances[np.isfinite(distances)]
    if finite.size:
        half = max(abs(float(finite.min())), abs(float(finite.max())))
        offset = (float(finite.min()) + float(finite.max())) / 2.0
        span = ('local 法向步长 1 块，法向范围约 ±%.0f 块（实测 %.0f .. %.0f 块，中心偏移 %+.1f）'
                % (half, float(finite.min()), float(finite.max()), offset))
    else:
        span = 'local 法向步长 1 块，法向范围约 ±240 块（distance 列缺失，按采样序号绘制）'
    return ('%s；原图（地形栅格）步长 %d 块。两者步长不同，本图「不是」%d² 栅格的截面。'
            % (span, grid_step, geo['size']))


# ------------------------------------------------------------------ 指标

def build_metrics(geo, data, palette, verification, clamps):
    heights = data['heights']
    arms = {arm: height_stats(heights[arm]) for arm in HEIGHT_ARMS}
    comparisons = {'full': compare(heights['baseline-a'], heights['regional-morph']),
                   'ordinary': compare(heights['baseline-base'], heights['regional-morph']),
                   'smoothing': compare(heights['regional-hard'], heights['regional-morph'])}
    types = {arm: type_stats(data['types'][arm], palette) for arm in TYPE_ARMS}
    a_ids, b_ids = data['types']['type-a'], data['types']['type-b']
    h_ids = data['types']['type-hard']
    selection = {'changedPixels': int((a_ids != b_ids).sum()),
                 'changedPct': float((a_ids != b_ids).mean() * 100.0),
                 'samePixels': int((a_ids == b_ids).sum())}
    hard_vs_b = {'changedPixels': int((h_ids != b_ids).sum()),
                 'changedPct': float((h_ids != b_ids).mean() * 100.0),
                 'note': 'type-hard（胜出站点配方）vs type-b（DOMINANT 主导配方）：'
                         '两者布局相同，差异来自"胜出 vs 加权主导"的口径差，不是过渡'}
    return {
        'tool': 'render-regional-world',
        'status': 'rendered',
        'geometry': geo,
        'seaLevel': SEA,
        'heightAxis': [HEIGHT_LO, HEIGHT_HI],
        'inputs': {'sha256': data['sha256']},
        'arms': arms,
        'comparisons': comparisons,
        'waterFlipRule': '按"像素等级"数 (>=63 与 <63 互换)；不由高度差推断任何选型',
        'types': {'rule': '面积/占比只由 u8 配方 id 直方图统计；id = 该像素被选中 / 加权主导 / 胜出的'
                          '那一条，不代表所有参与过选择的候选（把候选都当"实际"会让每格变成全 23 类）',
                  'idSpace': '0..17 = 陆地 18 条（RecipeMorphCatalog 顺序）+ '
                             '18..22 = 海洋 5 条（OceanRecipeCatalog.Recipe 顺序），'
                             '拼接顺序同 MorphCatalog.world()；这里是「下标空间」，'
                             '不表示每条都会在某臂出现',
                  'absenceRule': '某臂某 id 计数为 0 → 只说明该臂没选中它，'
                                 '不表示该配方不存在，也不能反推"只有出现的这几条存在"',
                  'arms': types,
                  'selectionChanged': selection,
                  'hardVsDominant': hard_vs_b},
        'seaWeight': weight_stats(data['seaWeight']),
        'active': active_stats(data['active']),
        'diffClamps': clamps,
        'profiles': profile_check(data['profiles'], heights, geo),
        'figures': verification,
        'semantics': {
            'baseline-a': '旧链 + 旧海岸；= 此前"距离修复后"那条 B',
            'baseline-base': '旧链去掉海岸',
            'regional-hard': '新站点类型 + 硬切换（无常态过渡）；布局 = 新',
            'regional-morph': '新公共节点输出（P3B）+ 无海岸；布局 = 新',
            'pair-full': 'A 含旧海岸 vs B 无海岸 → 不同源，不能归因单条改动',
            'pair-ordinary': '无海岸 OLD(base) vs 新(B)，但布局仍不同 → 布局 + 过渡',
            'pair-smoothing': 'hard vs B，布局完全相同 → 唯一单变量对照',
            'type-a': '旧链「海岸处理之前」的内部配方 id（海侧分支 / 陆地 dominant 口径）；'
                      '既不是最终的"实际海岸配方"，也不是全部潜在候选',
            'type-b': '新链「加权 DOMINANT 主导」配方 id（加权后的最终主导，同样不等于全部候选）',
            'type-hard': '新链「胜出站点」配方 id（硬选、未加权）',
            'typeArmsComparable': '三臂口径互不相同（海岸前内部 / 加权主导 / 胜出站点）→ '
                                  '面积表不可当成"同一件事的三个版本"直接横向比较',
            'sea-weight': '原值直出，不假设归一化、不裁剪数据；越界像素标品红，但文字区分'
                          '"1-ULP 归一化舍入"（≤%.0e）与"真越界"（>该容差）' % ULP_TOL,
            'active': '语义由导出方定义，本工具只按实测取值上色，不解释业务含义'},
        'notes': [
            '每块面板都是 1401²（或本图 size²）原生像素，粘贴处已逐像素校验，无缩放/重采样',
            'local-* 剖面沿法向按 1 块采样，与地形栅格步长（meta.step）不是同一口径',
            '本工具只读导出 raw；不改生产代码、不加 -D 开关、不运行生成器',
            '标准图 SHA 与生产口径由导出方负责，本工具不断言',
            '本工具不断言性能、也不断言实机效果',
        ],
    }


def print_summary(metrics, dest):
    comparisons = metrics['comparisons']
    print('rendered %d figures -> %s' % (len(metrics['figures']), dest))
    for name in ('pair-full.png', 'pair-ordinary.png', 'pair-smoothing.png',
                 'pair-types.png', 'transition-map.png', 'profiles-full.png'):
        if name in metrics['figures']:
            print(' ', name)
    print('  水面：A(含旧海岸) %.3f%% → B %.3f%%｜base(无海岸) %.3f%%｜hard %.3f%%'
          % (comparisons['full']['waterPctBase'], comparisons['full']['waterPctCand'],
             comparisons['ordinary']['waterPctBase'], comparisons['smoothing']['waterPctBase']))
    print('  翻水面（A→B）陆变海 %d / 海变陆 %d'
          % (comparisons['full']['waterFlipLandToSea'],
             comparisons['full']['waterFlipSeaToLand']))
    print('  类型：type-a 有 %d 个 id、type-b 有 %d 个 id；A/B 不同的像素 %d（%.2f%%）'
          % (metrics['types']['arms']['type-a']['distinctIds'],
             metrics['types']['arms']['type-b']['distinctIds'],
             metrics['types']['selectionChanged']['changedPixels'],
             metrics['types']['selectionChanged']['changedPct']))
    for arm in ('type-a', 'type-b'):
        absent_ids = metrics['types']['arms'][arm]['absentIds']
        print('    %s 未出现的 id（%d 个）：%s —— 只表示该臂没选中，不是"配方不存在"'
              % (arm, len(absent_ids), id_ranges(absent_ids)))
    weight = metrics['seaWeight']
    if weight['outside0to1']:
        print('  sea-weight：%d 像素不等于 [0,1]，最大越界量 %+.3e，判定 = %s'
              % (weight['outside0to1'], weight['outOfRangeMaxAmount'],
                 '归一化 1-ULP 舍入（非缺陷）' if weight['outOfRangeClass'] == 'float-roundoff'
                 else '超出浮点舍入（需回导出端核对）'))
    else:
        print('  sea-weight：全部落在 [0,1] 内（min %.6f / max %.6f）' % (weight['min'],
                                                                        weight['max']))
    profiles = metrics['profiles']
    print('  剖面：全幅 %d 条 / local %d 条；CSV 与栅格最大偏差 %.3g 格，行位置合契约：%s'
          % (profiles['fullLines'], profiles['localLines'],
             profiles['maxDeviationUntilNow'],
             '未评估（没有全幅行）' if profiles['rowsMatchContract'] is None
             else profiles['rowsMatchContract']))
    if profiles['rowsMatchContract'] is False:
        print('  ⚠ 全幅剖面的像素行位置与契约 rows %s 不一致，但各点数值仍与栅格自洽（见 metrics.json）'
              % profiles['contractRows'])


def run(root, dest):
    need(root.is_dir(), 'input root is not a directory: %s' % root)
    geo = read_meta(root)
    palette = load_palette()
    data = load_inputs(root, geo)
    dest.mkdir(parents=True, exist_ok=True)
    entries = {}
    natives = draw_natives(geo, data, palette, dest, entries)
    draw_pairs(geo, natives, dest, entries)
    draw_types(geo, data, palette, dest, entries)
    draw_transition(geo, data, dest, entries)
    clamps = draw_diffs(geo, data, dest, entries)
    draw_profiles(geo, data, dest, entries)
    verification = verify_paste(entries, dest)
    metrics = build_metrics(geo, data, palette, verification, clamps)
    (dest / 'metrics.json').write_text(
        json.dumps(metrics, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print_summary(metrics, dest)
    return 0


def main(argv):
    if len(argv) != 2:
        sys.stderr.write('usage: python tools/render-regional-world.py <root> <dest>\n')
        return 2
    try:
        return run(Path(argv[0]), Path(argv[1]))
    except InputError as exc:
        sys.stderr.write('FAIL %s\n' % exc)
        return 1
    except (IOError, OSError) as exc:
        sys.stderr.write('IO ERROR %s\n' % exc)
        return 2


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
