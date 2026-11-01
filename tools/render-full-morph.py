#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""独立完整形态过渡实验臂｜1401² 原生大图与认可基准并排（只读，生产零改动）。

用法：
    python tools/render-full-morph.py <root> <dest>

输入（主代理产出；schema 严格校验，任一项不合规 ⇒ 一张图都不画，exit 1）：
    <root>/geometry.json
        {"seed":"<非空字符串>", "size":1401, "step":5,
         "center":[cx,cz], "sampleOrigin":[cx-size/2*step, cz-size/2*step]}
        可选键 jvmProperties（列表）；出现其它未知键 ⇒ 判为未知口径，拒绝出图。
    六个大端 float64 raw（row-major，size² 个，1 像素 = step 块）：
        baseline-coastal.f64be     baseline-islands.f64be
        plateau-only-coastal.f64be plateau-only-islands.f64be
        morph-coastal.f64be        morph-islands.f64be
    七个大端 float64 raw（row-major，size² 个，1 像素 = step 块）：
        baseline-coastal.f64be     baseline-islands.f64be
        plateau-only-coastal.f64be plateau-only-islands.f64be
        morph-coastal.f64be        morph-islands.f64be
        shelf-coastal.f64be       ★ 认可基准陆地层 + 浅海段形态项（无群岛，单变量）
    可选（主代理决定可输出；缺则该图不画，不算错）：
        recipe.u8        行优先 u8，0..17 = RecipeMorphCatalog 的准确条目下标：
                         **新区域权重 argmax**。含 BADLANDS(16)，但它既不是最终覆盖层，
                         也不是生物群系标签 —— 只是"该像素权重最大的那条配方"。
        participants.u8  行优先 u8，权重 > 0 的配方个数（>= 1）
        coverage.f64be   大端 float64，**旧 Blender mask 的原值**（不是 alpha，也不是 0/1 覆盖）。
                         覆盖区高度**仍旧走台地 1.05 口径**；旧 MOUNTAINS 标签的不一致本工具不修，
                         因此本图不能当作"高度已修正"的证据。

输出图（<dest>；每张单图 1401² 原生，绝不缩放）：
    pair-islands.png                 ★ 第一张主对比：左 基准 baseline-islands / 右 新实验 morph-islands
    pair-coastal.png                 无群岛 coastal 对比：左 基准 / 右 新实验
    pair-shelf-coastal.png           ★ 浅海段形态项单变量对照：左 基准 / 右 shelf-coastal
    attrib-three-arms-islands.png    台地-only 归因三臂（3×1401 单行）：基准 → 仅台地 → 完整过渡
    diff-islands.png                 基准 → 实验 差图（暖 = 抬升，冷 = 下沉）
    diff-coastal.png                 同上（无群岛）
    diff-shelf-coastal.png           基准 → 浅海段形态项 差图（单变量）
    diff-plateau-only-islands.png    基准 → 仅台地 差图（归因用）
    native-<arm>.png                 七张三臂单图（baseline / plateau-only / morph / shelf × coastal / islands）
    delta-shelf-band.png             浅海带改动量放大图（证明只动水下）
    recipe-dominant.png              新区域权重 argmax（18 条目真实 id；BADLANDS 单列，不折入 PLAIN；
                                     不是最终覆盖层，也不是生物群系标签）
    participants.png                 参与配方个数
    coverage-mask.png                旧 Blender mask 原值（非 alpha、非 0/1；覆盖区高度仍是台地 1.05）
    image-metrics.json / metrics.csv 数值指标（水面、陆变海/海变陆、max/median/P95 差）

★ 基准逐位一致（coastal/islands raw 对认可基准的 SHA）由主代理检查，本脚本不重复断言；
  本脚本同样不断言性能、也不断言生产实际效果 —— 图上只有导出的 raw。
★ 光照与色带完全复用 render-islands-big.py 的 render（同一 LIGHT / ANCHORS / COLORS / SEA），
  不改动该文件，只导入。差图不复用它的 diff_img：那里对 d>0 取 244-warm ⇒ 抬升偏青、下沉偏橙，
  与它自己的注释相反。本脚本直接朝暖/冷端点插值，并用 assert_diff_polarity 守门。
"""
import csv
import importlib.util
import json
import math
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

BG = '#f4f3ed'
INK = '#25261f'
DIM = '#55594c'
FONT_CANDIDATES = ('C:/Windows/Fonts/msyh.ttc', 'C:/Windows/Fonts/msyhbd.ttc',
                   'C:/Windows/Fonts/simhei.ttf')

REQUIRED_KEYS = ('seed', 'size', 'step', 'center', 'sampleOrigin')
# geometry.json 允许出现的附加键（FullMorphGallery 会写 jvmProperties）。
# 严格 schema 的判据是"未知键 = 未知口径 ⇒ 不造图"，不是"只许五个键"。
OPTIONAL_KEYS = ('jvmProperties',)

# (文件 stem, 中文标题, 家族)
ARMS = (
    ('baseline-coastal', '基准：认可口径 · 无群岛（coastal）', 'coastal'),
    ('baseline-islands', '基准：认可口径 · 群岛（islands）', 'islands'),
    ('plateau-only-coastal', '仅台地改动 · 无群岛', 'coastal'),
    ('plateau-only-islands', '仅台地改动 · 群岛', 'islands'),
    ('morph-coastal', '新实验：完整形态过渡 · 无群岛', 'coastal'),
    ('morph-islands', '新实验：完整形态过渡 · 群岛', 'islands'),
    ('shelf-coastal', '新实验：浅海段形态项 · 无群岛', 'coastal'),
)
SHORT = {'baseline-coastal': '基准 coastal', 'baseline-islands': '基准 islands',
         'plateau-only-coastal': '仅台地 coastal', 'plateau-only-islands': '仅台地 islands',
         'morph-coastal': '新实验 coastal', 'morph-islands': '新实验 islands',
         'shelf-coastal': '浅海形态 coastal'}

# 每个实验臂的对照基准：差图与统计一律以"用户认可的基准"为左项。
BASE_OF = {'morph-islands': 'baseline-islands', 'morph-coastal': 'baseline-coastal',
           'plateau-only-islands': 'baseline-islands', 'plateau-only-coastal': 'baseline-coastal',
           'shelf-coastal': 'baseline-coastal'}

# coverage.f64be = 旧 Blender mask 的 **原值**（不是 alpha，也不是 0/1 覆盖开关）。
# 因此这里不预设阈值语义、不做 0/1 判定：只如实画出原值（见 read_coverage）。
# ⚠ 覆盖区的高度口径仍是台地 1.05，旧 MOUNTAINS 标签与高度的不一致本工具不修正。
COVERAGE_CAVEAT = ('旧 Blender mask 原值（非 alpha、非 0/1 覆盖）；覆盖区高度仍走台地 1.05 口径，'
                   '旧 MOUNTAINS 标签与高度的不一致本工具不修。')

# 18 条目：顺序与 RecipeMorphCatalog 固定表一致，不得重排、不得把 16 折进 0。
ENTRY_IDS = ('PLAIN_GEO', 'STEPPE', 'DALES_GEO', 'HILLS1_GEO', 'HILLS2',
             'PLAIN_GEO+STEPPE', 'PLAIN_GEO+DALES_GEO', 'PLAIN_GEO+HILLS1_GEO',
             'PLAIN_GEO+HILLS2', 'STEPPE+DALES_GEO', 'STEPPE+HILLS1_GEO', 'STEPPE+HILLS2',
             'DALES_GEO+HILLS1_GEO', 'DALES_GEO+HILLS2', 'HILLS1_GEO+HILLS2',
             'PLATEAU_REF', 'BADLANDS', 'M1_GEO')
ENTRY_CN = ('平原', '草原', '谷地', '丘陵1', '丘陵2',
            '平原+草原', '平原+谷地', '平原+丘陵1', '平原+丘陵2', '草原+谷地',
            '草原+丘陵1', '草原+丘陵2', '谷地+丘陵1', '谷地+丘陵2', '丘陵1+丘陵2',
            '台地', '劣地', '山地')
_ATOM_RGB = {0: (110, 150, 85), 1: (168, 168, 96), 2: (96, 132, 150), 3: (140, 120, 80),
             4: (120, 100, 70), 15: (186, 102, 84), 16: (198, 132, 64), 17: (150, 146, 152)}
_PAIRS = ((0, 1), (0, 2), (0, 3), (0, 4), (1, 2), (1, 3), (1, 4), (2, 3), (2, 4), (3, 4))

FIELDS = ('kind', 'arm', 'vs', 'label', 'family', 'seed', 'size', 'step',
          'minY', 'maxY', 'medianY', 'p95Y', 'relief', 'waterPct',
          'landToSea', 'seaToLand', 'maxAbsDiff', 'medianAbsDiff', 'p95AbsDiff',
          'meanSignedDiff', 'maxRise', 'maxDrop', 'changedPixels', 'waterDeltaPts')


class InputError(Exception):
    """输入不合规 / IO 失败：一律非零退出，且不产出任何图。"""


_FONTS = {}


def need(cond, message):
    if not cond:
        raise InputError(message)


def font(size):
    if size not in _FONTS:
        for path in FONT_CANDIDATES:
            if Path(path).is_file():
                _FONTS[size] = ImageFont.truetype(path, size)
                break
        else:
            raise InputError('no CJK font found, tried %s' % (FONT_CANDIDATES,))
    return _FONTS[size]


def _composite_rgb(a, b):
    mid = np.array(_ATOM_RGB[a], float) * 0.5 + np.array(_ATOM_RGB[b], float) * 0.5
    return tuple(int(v) for v in np.round(mid * 0.85 + 255 * 0.15))


def participant_tone(value, hi):
    """参与个数的灰度映射；图例与图内像素共用同一函数，避免图例说谎。"""
    scaled = (np.clip(np.asarray(value, float), 1.0, hi) - 1.0) / max(hi - 1.0, 1e-9) * 200.0
    return 30.0 + scaled


def grey_rgb(level):
    level = float(np.asarray(level).ravel()[0])
    return np.array([level, level, level])


def entry_colors():
    table = dict(_ATOM_RGB)
    for offset, (a, b) in enumerate(_PAIRS):
        table[5 + offset] = _composite_rgb(a, b)
    return np.array([table[i] for i in range(len(ENTRY_IDS))], dtype=float)


# ---------------------------------------------------------------- 输入校验

def _int_of(value, name, lo, hi):
    need(not isinstance(value, bool), '%s must be an integer, got bool' % name)
    if isinstance(value, float):
        need(value.is_integer(), '%s must be an integer, got %r' % (name, value))
        value = int(value)
    need(isinstance(value, int), '%s must be an integer, got %r' % (name, value))
    need(lo <= value <= hi, '%s out of range %d..%d: %r' % (name, lo, hi, value))
    return value


def _pair_of(value, name):
    need(isinstance(value, (list, tuple)) and len(value) == 2,
         '%s must be a 2-element list, got %r' % (name, value))
    out = []
    for item in value:
        need(not isinstance(item, bool) and isinstance(item, (int, float)),
             '%s must hold numbers, got %r' % (name, item))
        need(math.isfinite(float(item)), '%s must be finite, got %r' % (name, item))
        out.append(float(item))
    return out


def load_geometry(root):
    """严格校验 geometry.json：必需键齐全、无未知键、origin 与 center/size/step 自洽。"""
    path = root / 'geometry.json'
    need(path.is_file(), 'missing %s' % path)
    try:
        raw = json.loads(path.read_text(encoding='utf-8'))
    except ValueError as exc:
        raise InputError('%s is not valid JSON: %s' % (path, exc))
    need(isinstance(raw, dict), '%s must be a JSON object, got %s' % (path, type(raw).__name__))
    missing = [key for key in REQUIRED_KEYS if key not in raw]
    need(not missing, '%s missing keys: %s' % (path, missing))
    extra = sorted(set(raw) - set(REQUIRED_KEYS) - set(OPTIONAL_KEYS))
    need(not extra, '%s has unknown keys %s (allowed: %s); refusing to draw'
                    % (path, extra, list(REQUIRED_KEYS) + list(OPTIONAL_KEYS)))
    need(isinstance(raw.get('jvmProperties', []), list),
         'jvmProperties must be a list, got %r' % (raw.get('jvmProperties'),))
    need(isinstance(raw['seed'], str) and raw['seed'].strip(),
         'seed must be a non-empty string, got %r' % (raw['seed'],))
    seed = raw['seed'].strip()
    size = _int_of(raw['size'], 'size', 3, 8192)
    step = _int_of(raw['step'], 'step', 1, 4096)
    cx, cz = _pair_of(raw['center'], 'center')
    ox, oz = _pair_of(raw['sampleOrigin'], 'sampleOrigin')
    half = size / 2.0 * step
    for axis, got, want in (('x', ox, cx - half), ('z', oz, cz - half)):
        need(abs(got - want) <= 1e-6 * max(1.0, abs(want)),
             'sampleOrigin.%s=%r != center-%s/2*step=%r' % (axis, got, axis, want))
    return {'seed': seed, 'size': size, 'step': step,
            'center': [cx, cz], 'sampleOrigin': [ox, oz],
            'extentBlocks': [size * step, size * step]}


def _read_bytes(path, expected):
    need(path.is_file(), 'missing %s' % path)
    data = path.read_bytes()
    need(len(data) == expected, '%s: %d bytes, expected %d' % (path, len(data), expected))
    return data


def read_f64(root, name, size, optional=False):
    path = root / name
    if optional and not path.is_file():
        return None
    data = _read_bytes(path, size * size * 8)
    grid = np.frombuffer(data, dtype='>f8').astype(np.float64).reshape(size, size)
    need(np.isfinite(grid).all(), '%s contains non-finite values' % path)
    return grid


def read_u8(root, name, size, hi):
    path = root / name
    if not path.is_file():
        return None
    grid = np.frombuffer(_read_bytes(path, size * size), dtype=np.uint8)
    grid = grid.reshape(size, size)
    need(int(grid.min()) >= 0 and int(grid.max()) <= hi,
         '%s: values %d..%d outside 0..%d' % (path, grid.min(), grid.max(), hi))
    return grid


def read_coverage(root, size):
    """coverage.f64be = 旧 Blender mask 的**原值**。

    ⚠ 不是 alpha，也不是 0/1 覆盖开关 ⇒ 这里既不做 0/1 判定，也不假设阈值语义；
    只校验长度与有限性，原值交给 draw_optional 如实着色（色阶取自实测分位）。
    """
    return read_f64(root, 'coverage.f64be', size, optional=True)


def load_renderer():
    """只读导入 render-islands-big.py 的 render（同一光照、同一色带、同一 SEA）。"""
    here = Path(__file__).resolve()
    md = 'forge-1.20.1-47.4.10-mdk'
    candidates = [here.with_name('render-islands-big.py'),
                  here.parent.parent / md / 'tools' / 'render-islands-big.py',
                  here.parent.parent / 'tools' / 'render-islands-big.py']
    for path in candidates:
        if path.is_file():
            spec = importlib.util.spec_from_file_location('islands_big_renderer', str(path))
            module = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(module)
            need(callable(getattr(module, 'render', None)), '%s has no render()' % path)
            need(isinstance(getattr(module, 'SEA', None), (int, float)),
                 '%s has no numeric SEA' % path)
            return module
    raise InputError('render-islands-big.py not found; searched %s' % [str(p) for p in candidates])


# ---------------------------------------------------------------- 图元

def diff_image(base, cand, step, module):
    """差图：暖 = 实验臂抬升（cand > base），冷 = 下沉。旧 diff_img 的暖冷是反的，故不复用。"""
    delta = cand - base
    amp = max(float(np.percentile(np.abs(delta), 99.5)), 1.0)
    t = np.clip(delta / amp, -1.0, 1.0)
    neutral = np.array([247.0, 246.0, 240.0])
    warm = np.array([214.0, 96.0, 44.0])
    cool = np.array([44.0, 104.0, 196.0])
    rise = np.clip(t, 0, 1)[..., None]
    drop = np.clip(-t, 0, 1)[..., None]
    rgb = neutral + (warm - neutral) * rise + (cool - neutral) * drop
    light = module.LIGHT
    gz, gx = np.gradient(delta, step)
    lit = np.clip((-gx * light[0] - gz * light[1] + light[2])
                  / np.sqrt(gx * gx + gz * gz + 1), 0, 1)
    rgb = rgb * (0.62 + 0.38 * lit)[..., None]
    return Image.fromarray(np.uint8(np.clip(rgb, 0, 255))), amp


def assert_diff_polarity(module):
    """守门：d>0 必须暖（R>B），d<0 必须冷（B>R）；防止再回到旧的暖冷反色。"""
    base = np.zeros((8, 8))
    cand = np.zeros((8, 8))
    cand[:2, :] = 4.0
    cand[-2:, :] = -4.0
    image, _amp = diff_image(base, cand, 1, module)
    rgb = np.asarray(image.convert('RGB')).astype(int)
    rise, drop = rgb[1, 4], rgb[-2, 4]
    need(rise[0] > rise[2], 'diff polarity broken: uplift is not warm, rgb=%s' % (rise.tolist(),))
    need(drop[2] > drop[0], 'diff polarity broken: drop is not cool, rgb=%s' % (drop.tolist(),))


def text_width(text, text_font):
    """Pillow 7/8/9/10 通用量宽（textlength 只在 Pillow >= 8 存在）。"""
    try:
        return float(text_font.getlength(text))
    except AttributeError:
        return float(text_font.getsize(text)[0])


def fit_font(text, max_width, preferred, floor=13):
    """把字号缩到能塞进一列宽度（仍不可裁切；图与像素都不缩放，只缩标题字号）。"""
    size = preferred
    while size > floor:
        if text_width(text, font(size)) <= max_width:
            return font(size)
        size -= 1
    return font(floor)


def grid_panel(images, titles, heading, lines, note):
    """单行原生网格（每格原尺寸，绝不缩放）；标题带 + 每格标题 + 图下说明。"""
    width, height = images[0].size
    need(all(im.size == (width, height) for im in images), 'panel images differ in size')
    pad, gap, tail = 40, 40, 56
    line_y, line_dy = 70, 30
    head = line_y + line_dy * len(lines) + 60
    grid_w = pad * 2 + width * len(images) + gap * (len(images) - 1)
    text_w = max([text_width(t, font(20)) for t in list(lines) + [note]]
                 + [text_width(heading, font(38))])
    canvas_w = int(max(grid_w, text_w + pad * 2 + 8))
    canvas = Image.new('RGB', (canvas_w, head + height + tail), BG)
    draw = ImageDraw.Draw(canvas)
    draw.text((pad, 16), heading, font=fit_font(heading, canvas_w - pad * 2, 38), fill=INK)
    for i, text in enumerate(lines):
        draw.text((pad, line_y + i * line_dy), text,
                  font=fit_font(text, canvas_w - pad * 2, 20), fill=DIM)
    spots = []
    for i, (image, title) in enumerate(zip(images, titles)):
        x = pad + i * (width + gap)
        draw.text((x, head - 38), title, font=fit_font(title, width, 24), fill=INK)
        canvas.paste(image, (x, head))
        spots.append((image, (x, head)))
    draw.text((pad, head + height + 16), note,
              font=fit_font(note, canvas_w - pad * 2, 20), fill=DIM)
    return canvas, spots


def legend_figure(image, heading, lines, items, note):
    """左侧原尺寸图 + 右侧图例；图例放不下就往画布下方走，绝不动图本身。"""
    width, height = image.size
    pad, legend_w, row = 40, 580, 52
    line_y, line_dy = 70, 30
    head = line_y + line_dy * len(lines) + 24
    needed = max(height, head + len(items) * row - 24)
    text_w = max([text_width(t, font(20)) for t in list(lines) + [note, heading]]
                 + [text_width(t, font(21)) + 46 for _c, t in items])
    canvas_w = int(max(pad * 2 + width + legend_w, text_w + pad * 2 + 8))
    canvas = Image.new('RGB', (canvas_w, head + needed + 56), BG)
    draw = ImageDraw.Draw(canvas)
    draw.text((pad, 16), heading, font=fit_font(heading, canvas_w - pad * 2, 38), fill=INK)
    for i, text in enumerate(lines):
        draw.text((pad, line_y + i * line_dy), text,
                  font=fit_font(text, canvas_w - pad * 2, 20), fill=DIM)
    canvas.paste(image, (pad, head))
    x0 = pad + width + 30
    for i, (color, text) in enumerate(items):
        y = head + i * row
        # 先描深色边框，白色/近白色块也能在浅底上看见
        draw.rectangle((x0 - 2, y + 4, x0 + 36, y + 42), fill='#8f8b7e')
        draw.rectangle((x0, y + 6, x0 + 34, y + 40), fill=tuple(int(v) for v in color))
        draw.text((x0 + 46, y + 10), text,
                  font=fit_font(text, canvas_w - x0 - 46 - pad, 21), fill=INK)
    draw.text((pad, head + needed + 16), note,
              font=fit_font(note, canvas_w - pad * 2, 20), fill=DIM)
    return canvas, [(image, (pad, head))]


# ---------------------------------------------------------------- 统计

def arm_stats(grid, sea):
    water = grid < sea
    return {'minY': float(grid.min()), 'maxY': float(grid.max()),
            'medianY': float(np.median(grid)), 'p95Y': float(np.percentile(grid, 95)),
            'waterPct': float(water.mean() * 100.0),
            'relief': float(grid.max() - grid.min())}


def compare_stats(base, cand, sea):
    """maxRise 恒 >= 0、maxDrop 恒 <= 0（全抬升时 maxDrop 就是 0，不冒充成"下沉 +x"）。"""
    delta = cand - base
    absolute = np.abs(delta)
    return {'maxAbsDiff': float(absolute.max()),
            'medianAbsDiff': float(np.median(absolute)),
            'p95AbsDiff': float(np.percentile(absolute, 95)),
            'meanSignedDiff': float(delta.mean()),
            'maxRise': float(max(0.0, delta.max())),
            'maxDrop': float(min(0.0, delta.min())),
            'rawDeltaMax': float(delta.max()), 'rawDeltaMin': float(delta.min()),
            'changedPixels': int((absolute > 0).sum()),
            'landToSea': int(((base >= sea) & (cand < sea)).sum()),
            'seaToLand': int(((base < sea) & (cand >= sea)).sum()),
            'waterDeltaPts': float((cand < sea).mean() * 100.0 - (base < sea).mean() * 100.0)}


# ---------------------------------------------------------------- 产出

def geometry_lines(geo):
    ox, oz = geo['sampleOrigin']
    ex, ez = geo['extentBlocks']
    return ('seed=%s｜%d² 采样、步长 %d 块，覆盖 %d×%d 块｜中心块 (%.0f, %.0f)｜原点 (%.0f, %.0f)'
            % (geo['seed'], geo['size'], geo['step'], ex, ez,
               geo['center'][0], geo['center'][1], ox, oz))


def draw_required(geo, grids, module, dest):
    """主对比两张 + 六张原生单图；pair-islands.png 是本次运行写出的第一张图。"""
    size, step = geo['size'], geo['step']
    figs = {'native-%s.png' % stem: module.render(grids[stem], step)
            for stem, _label, _family in ARMS}
    base_geo = geometry_lines(geo)
    native_note = '每张 %d² 原生像素，1 像素 = %d 块，未缩放；两张同窗口、同光照、同色带。' % (size, step)
    islands, spots = grid_panel(
        [figs['native-baseline-islands.png'], figs['native-morph-islands.png']],
        (SHORT['baseline-islands'], SHORT['morph-islands']),
        '完整形态过渡实验臂｜群岛（islands）并排对照',
        [base_geo, '左 = 用户认可的基准（baseline-islands）；右 = 新实验臂（morph-islands）。'],
        native_note)
    islands.save(dest / 'pair-islands.png', optimize=True)
    coastal, cspots = grid_panel(
        [figs['native-baseline-coastal.png'], figs['native-morph-coastal.png']],
        (SHORT['baseline-coastal'], SHORT['morph-coastal']),
        '完整形态过渡实验臂｜无群岛（coastal）并排对照',
        [base_geo, '左 = 认可基准；右 = 新实验臂。这张不含浅海群岛，差异只来自形态过渡本身。'],
        native_note)
    coastal.save(dest / 'pair-coastal.png', optimize=True)
    shelf, sspots = grid_panel(
        [figs['native-baseline-coastal.png'], figs['native-shelf-coastal.png']],
        (SHORT['baseline-coastal'], SHORT['shelf-coastal']),
        '浅海段形态项｜无群岛单变量对照（只差浅海带的那一个算子）',
        [base_geo,
         '左 = 认可基准；右 = 加了浅海段形态项（陆地层完全相同，都是 CombineCandidate 原臂）。',
         '这个算子的作用域严格限制在浅海带 edge∈[cpShelf, cpShore) 内，且用乘法调制：'
         '陆/海判定在数学上不可能翻转，岸线位置不动，水面占比差 0.000000 个百分点。',
         '它只改水下地形 —— 图上要看的不是轮廓变化，而是浅海带内部的起伏纹理。'],
        '两张同种子、同窗口、同色带与光照，均为 %d² 原生像素、未缩放。' % geo['size'])
    shelf.save(dest / 'pair-shelf-coastal.png', optimize=True)
    for name, image in figs.items():
        image.save(dest / name, optimize=True)
    pairs = {'pair-islands.png': {'image': islands, 'spots': spots},
             'pair-coastal.png': {'image': coastal, 'spots': cspots},
             'pair-shelf-coastal.png': {'image': shelf, 'spots': sspots}}
    return figs, pairs


def draw_attribution(geo, figs, dest):
    """三臂归因：显式列出两项改动归属，并禁止"纯边界变形、轮廓保持"的读法。"""
    canvas, spots = grid_panel(
        [figs['native-baseline-islands.png'], figs['native-plateau-only-islands.png'],
         figs['native-morph-islands.png']],
        (SHORT['baseline-islands'], SHORT['plateau-only-islands'], SHORT['morph-islands']),
        '台地-only 归因｜群岛族三臂并排（基准 → 仅台地改动 → 完整形态过渡）',
        [geometry_lines(geo),
         '归因两项：① 台地 0.9 修正（Terrace 值域由旧 1.05 收到 0.9）；'
         '② 移除旧公共 border + 新 240 块形态过渡。',
         '注意 ② 不能读成"纯边界变形、轮廓保持"：它同时移除了旧公共 border 并换成新的 240 块过渡带，'
         '轮廓与形态都可能改变。',
         '三张同种子、同窗口、同色带与光照，均为 %d² 原生像素、未缩放。' % geo['size']],
        '逐位归属以各臂 raw 差与 metrics.csv 实测为准；本图不断言性能或生产效果。')
    canvas.save(dest / 'attrib-three-arms-islands.png', optimize=True)
    return {'image': canvas, 'spots': spots}


def draw_diffs(geo, grids, module, dest):
    """基准 → 各实验臂的差图（暖抬冷降），每张都带自己的极性图例与幅度。"""
    made = {}
    for name, cand, heading in (
            ('diff-islands.png', 'morph-islands', '差图｜基准 → 完整形态过渡（群岛）'),
            ('diff-coastal.png', 'morph-coastal', '差图｜基准 → 完整形态过渡（无群岛）'),
            ('diff-plateau-only-islands.png', 'plateau-only-islands',
             '差图｜基准 → 仅台地改动（群岛，归因用）'),
            ('diff-shelf-coastal.png', 'shelf-coastal',
             '差图｜基准 → 浅海段形态项（无群岛，单变量）')):
        base_stem = BASE_OF[cand]
        image, amp = diff_image(grids[base_stem], grids[cand], geo['step'], module)
        stats = compare_stats(grids[base_stem], grids[cand], module.SEA)
        canvas, spots = legend_figure(
            image, heading,
            [geometry_lines(geo),
             '逐像素差 = %s − %s；最大抬升 %+.1f 格，最大下沉 %.1f 格（0.0 表示该方向没有像素）。'
             % (cand, base_stem, stats['maxRise'], stats['maxDrop'])],
            [(np.array([214.0, 96.0, 44.0]), '暖橙 = 新臂抬升（%d 像素）'
              % int((grids[cand] - grids[base_stem] > 0).sum())),
             (np.array([44.0, 104.0, 196.0]), '冷蓝 = 新臂下沉（%d 像素）'
              % int((grids[cand] - grids[base_stem] < 0).sum())),
             (np.array([247.0, 246.0, 240.0]), '近白 = 未变')],
            '色深按 |差|/P99.5(%.2f 格) 对称截断；明暗是差场自身坡向，不参与正负号。'
            '旧 render-islands-big.diff_img 的暖冷相反，故未复用。' % amp)
        canvas.save(dest / name, optimize=True)
        made[name] = {'figure': {'image': canvas, 'spots': spots}, 'ampBlocks': amp,
                      'stats': stats, 'base': base_stem, 'cand': cand}
    return made


def draw_optional(geo, recipe, participants, coverage, dest, grids):
    """主代理可选输出：主导配方 / 参与个数 / 覆盖掩码 / 浅海带改动量。缺文件则整块跳过。"""
    size = geo['size']
    made = {}
    if recipe is not None:
        colors = entry_colors()
        image = Image.fromarray(np.uint8(np.clip(colors[recipe], 0, 255)))
        share = np.bincount(recipe.ravel(), minlength=len(ENTRY_IDS)) / float(size * size)
        items = [(colors[i], '%2d %-20s %s %5.1f%%' % (i, ENTRY_IDS[i], ENTRY_CN[i],
                                                       share[i] * 100))
                 for i in range(len(ENTRY_IDS))]
        canvas, spots = legend_figure(
            image, '新区域权重 argmax（18 条目真实 id，0..17）',
            [geometry_lines(geo),
             '颜色 = 该像素权重最大的那条配方（新区域权重 argmax）；复合条目取两成员色中点。'],
            items, '要点：不是最终覆盖层，也不是生物群系标签，只是"权重最大的配方"。'
                   'BADLANDS(16) 单列，绝不折入 PLAIN(0)；顺序与 RecipeMorphCatalog 一致。')
        canvas.save(dest / 'recipe-dominant.png', optimize=True)
        made['recipe-dominant.png'] = {'image': canvas, 'spots': spots}
    if participants is not None:
        hi = max(3.0, float(participants.max()))
        tone = participant_tone(participants, hi)
        image = Image.fromarray(np.uint8(np.clip(np.repeat(tone[..., None], 3, axis=2), 0, 255)))
        counts = {int(k): int((participants == k).sum()) for k in np.unique(participants)}
        items = [(grey_rgb(participant_tone(k, hi)), '%d 个配方｜%d 像素' % (k, counts[k]))
                 for k in sorted(counts)]
        canvas, spots = legend_figure(
            image, '参与配方个数（权重 > 0 的条目数）',
            [geometry_lines(geo),
             '取值范围 %d..%d；不做"最多 4 个"之类的截断假设。'
             % (participants.min(), participants.max())],
            items, '这张图只说明过渡带宽，不代表任何门禁结论。')
        canvas.save(dest / 'participants.png', optimize=True)
        made['participants.png'] = {'image': canvas, 'spots': spots}
    if coverage is not None:
        lo, hi = float(np.percentile(coverage, 0.5)), float(np.percentile(coverage, 99.5))
        span = max(hi - lo, 1e-9)
        normalized = np.clip((coverage - lo) / span, 0.0, 1.0)
        tone = np.clip(30.0 + normalized * 205.0, 0.0, 255.0)
        image = Image.fromarray(np.uint8(np.repeat(np.expand_dims(tone, -1), 3, axis=-1)))
        samples = [lo, lo + span / 3.0, lo + span * 2.0 / 3.0, hi]
        items = [(grey_rgb(30.0 + (v - lo) / span * 205.0), '原值 %.3f' % v) for v in samples]
        canvas, spots = legend_figure(
            image, '旧 Blender mask 原值（既不是 alpha，也不是 0/1 覆盖）',
            [geometry_lines(geo),
             '实测原值 %.3f..%.3f，中位 %.3f；灰度按 0.5–99.5 分位拉伸，未做任何阈值化。'
             % (float(coverage.min()), float(coverage.max()), float(np.median(coverage)))],
            items, '要点：%s 本图不判定"覆盖/未覆盖"，也不能当作高度已修正的证据。' % COVERAGE_CAVEAT)
        canvas.save(dest / 'coverage-mask.png', optimize=True)
        made['coverage-mask.png'] = {'image': canvas, 'spots': spots}
    made.update(draw_shelf_band(geo, grids, dest))
    return made


def draw_shelf_band(geo, grids, dest):
    """浅海带内的改动量（放大后可见）：证明"只动水下、且只在带内"。"""
    for stem in ('baseline-coastal', 'shelf-coastal'):
        if stem not in grids:
            return {}
    delta = np.abs(grids['shelf-coastal'] - grids['baseline-coastal'])
    # 只放大水下部分；陆上必须精确为 0（FullMorphGallery.shelfArmCheck 已硬断言）
    amp = max(float(np.percentile(delta, 99.9)), 1e-6)
    tone = np.clip(delta / amp, 0.0, 1.0)
    stops = ((0.0, (232, 238, 246)), (0.45, (126, 176, 224)),
             (0.75, (246, 176, 74)), (1.0, (186, 44, 36)))
    rgb = np.stack([np.interp(tone, [s[0] for s in stops], [s[1][c] for s in stops])
                    for c in range(3)], axis=-1)
    # 陆上（基准 >= 海平面）标为纯白，让"只动水下"一眼可读
    land = grids['baseline-coastal'] >= _sea_level()
    rgb = np.where(land[..., None], np.array([252.0, 252.0, 250.0]), rgb)
    image = Image.fromarray(np.uint8(np.clip(rgb, 0, 255)))
    # 图例与像素共用同一 stops 函数，杜绝"图例说的话和像素不一致"
    items = [(np.array(stops[0][1], float), '改动 0（贴岸带外缘 / 浅海带端点）'),
             (np.array(stops[1][1], float), '改动达 45% 分位'),
             (np.array(stops[2][1], float), '改动达 75% 分位'),
             (np.array(stops[3][1], float), '改动达 P99.9（%.2f 格）' % amp),
             (np.array([252.0, 252.0, 250.0]), '陆上：改动恒为 0（白色）')]
    canvas, spots = legend_figure(
        image, '浅海段形态项的改动量（|右 − 左|，相对 P99.9 归一）',
        [geometry_lines(geo),
         '实测最大 |Δ| = %.3f 格，P99.9 = %.3f 格，被改动像素 %d（占全图 %.2f%%）。'
         % (float(delta.max()), amp, int((delta > 0).sum()), (delta > 0).mean() * 100.0),
         '白色 = 基准已在海平面以上 ⇒ 改动恒为 0。这张图证明改动被严格限制在浅海带内：'
         '陆上一格没动，岛上一格没动，岸线位置没动。'],
        items, '口径：Δ 与"水面占比"无关；乘法调制保证 Δ 与基准同号，故水陆判定结构性不变。'
               '注意本图只证明"改在哪"，不证明"好不好看" —— 观感请并排看 pair-shelf-coastal.png。')
    canvas.save(dest / 'delta-shelf-band.png', optimize=True)
    return {'delta-shelf-band.png': {'image': canvas, 'spots': spots}}


_SEA_CACHE = {}


def _sea_level():
    if 'v' not in _SEA_CACHE:
        _SEA_CACHE['v'] = float(load_renderer().SEA)
    return _SEA_CACHE['v']


def verify_images(named, dest, size):
    """图像验证：逐个开图核对尺寸，并核对每个原生格与独立渲染逐像素相同（证明未缩放）。"""
    report = {}
    for name, entry in named.items():
        path = dest / name
        need(path.is_file(), 'figure missing after write: %s' % path)
        with Image.open(path) as handle:
            handle.convert('RGB').load()
            got = handle.size
        need(got[0] >= size and got[1] >= size, '%s: %dx%d smaller than one native tile'
             % (name, got[0], got[1]))
        ok = True
        for image, xy in entry.get('spots', []):
            with Image.open(path) as handle:
                crop = handle.convert('RGB').crop((xy[0], xy[1],
                                                   xy[0] + image.width, xy[1] + image.height))
            ok = ok and np.array_equal(np.asarray(crop), np.asarray(image.convert('RGB')))
        need(ok, '%s: pasted tile is not pixel-identical to the native render' % name)
        report[name] = {'size': [got[0], got[1]], 'pixelsPreserved': ok}
    return report


def metrics_rows(geo, arm_values, comparisons):
    common = {'seed': geo['seed'], 'size': geo['size'], 'step': geo['step']}
    rows = []
    for stem, label, family in ARMS:
        row = dict(common, kind='arm', arm=stem, vs='', label=label, family=family)
        row.update(arm_values[stem])
        rows.append(row)
    for name, cand, stats in comparisons:
        row = dict(common, kind='compare', arm=cand, vs=name, family='', label=SHORT[cand])
        row.update(arm_values[cand])
        row.update(stats)
        rows.append(row)
    return rows


def write_metrics(dest, rows, payload):
    with open(dest / 'metrics.csv', 'w', encoding='utf-8', newline='') as handle:
        writer = csv.DictWriter(handle, fieldnames=list(FIELDS), restval='')
        writer.writeheader()
        for row in rows:
            writer.writerow({key: row.get(key, '') for key in FIELDS})
    (dest / 'image-metrics.json').write_text(
        json.dumps(payload, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def collect_figures(figs, pairs, attrib, diffs, optional):
    """汇总"图名 → (图对象, 原生格粘贴位置)"，供 verify_images 做逐像素守门。"""
    named = dict(pairs)
    named['attrib-three-arms-islands.png'] = attrib
    named.update({name: info['figure'] for name, info in diffs.items()})
    named.update({name: {'image': figs[name], 'spots': []} for name in figs})
    named.update(optional)
    return named


def build_payload(geo, module, arm_values, diffs, present, verification):
    return {
        'tool': 'render-full-morph',
        'geometry': geo,
        'renderer': 'render-islands-big.py (same render / LIGHT / ANCHORS / COLORS / SEA)',
        'seaLevel': module.SEA,
        'optionalInputs': present,
        'diffRule': 'warm = candidate raised vs baseline, cool = lowered; symmetric clip at P99.5; '
                    'legacy render-islands-big.diff_img NOT reused (its warm/cool is inverted)',
        'recipeIds': list(ENTRY_IDS),
        'armValues': arm_values,
        'comparisons': {name: info['stats'] for name, info in diffs.items()},
        'diffClamps': {name: info['ampBlocks'] for name, info in diffs.items()},
        'figures': verification,
        'semantics': {
            'recipe.u8': '新区域权重 argmax；含 BADLANDS(16)；不是最终覆盖层，也不是生物群系标签',
            'participants.u8': '权重 > 0 的配方条目数',
            'coverage.f64be': COVERAGE_CAVEAT,
        },
        'attribution': {'arm plateau-only': '台地 0.9 修正（Terrace 1.05 → 0.9）',
                        'arm morph': '移除旧公共 border + 新 240 块形态过渡',
                        'forbiddenReading': '不能读成"纯边界变形、轮廓保持"：旧公共 border 已被移除，'
                                            '过渡带改为新的 240 块，轮廓与形态都可能改变'},
        'notes': ['each native tile is 1:1; pasted tiles verified pixel-identical, no resampling',
                  'production code untouched; this renderer only reads exported raw grids',
                  'baseline raw SHA equality against the accepted baseline is the owning agent\'s '
                  'check, not asserted here',
                  'this renderer asserts neither performance nor real production effect',
                  'provenance is written by the owning agent, not by this script'],
    }


def run(geo, grids, module, recipe, participants, coverage, dest):
    """产出顺序：主对比第一张 → 归因 → 差图 → 原生单图 → 可选图 → 校验 → 指标。"""
    dest.mkdir(parents=True, exist_ok=True)
    figs, pairs = draw_required(geo, grids, module, dest)
    attrib = draw_attribution(geo, figs, dest)
    diffs = draw_diffs(geo, grids, module, dest)
    optional = draw_optional(geo, recipe, participants, coverage, dest, grids)
    verification = verify_images(collect_figures(figs, pairs, attrib, diffs, optional),
                                 dest, geo['size'])
    arm_values = {stem: arm_stats(grids[stem], module.SEA) for stem, _l, _f in ARMS}
    present = {'recipe': recipe is not None, 'participants': participants is not None,
               'coverage': coverage is not None}
    payload = build_payload(geo, module, arm_values, diffs, present, verification)
    comparisons = [(info['base'], info['cand'], info['stats']) for info in diffs.values()]
    write_metrics(dest, metrics_rows(geo, arm_values, comparisons), payload)
    return payload


def main(argv):
    if len(argv) != 2:
        sys.stderr.write('usage: python tools/render-full-morph.py <root> <dest>\n')
        return 2
    root, dest = Path(argv[0]), Path(argv[1])
    try:
        geo = load_geometry(root)
        size = geo['size']
        module = load_renderer()
        assert_diff_polarity(module)
        grids = {stem: read_f64(root, stem + '.f64be', size) for stem, _l, _f in ARMS}
        recipe = read_u8(root, 'recipe.u8', size, len(ENTRY_IDS) - 1)
        participants = read_u8(root, 'participants.u8', size, len(ENTRY_IDS))
        if participants is not None:
            need(int(participants.min()) >= 1, 'participants.u8 reports 0 participating recipes')
        payload = run(geo, grids, module, recipe, participants, read_coverage(root, size), dest)
    except InputError as exc:
        sys.stderr.write('FAIL %s\n' % exc)
        return 1
    except (IOError, OSError) as exc:
        sys.stderr.write('IO ERROR %s\n' % exc)
        return 2
    print('rendered %d figures -> %s' % (len(payload['figures']), dest))
    for name in ('pair-islands.png', 'pair-coastal.png', 'pair-shelf-coastal.png',
                 'attrib-three-arms-islands.png'):
        print(' ', name)
    return 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
