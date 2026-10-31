"""三向配方交汇画廊的渲染器：只读复用 render-islands-big.py 的 render（不用任何 AI 素材）。

用法：
    python tools/render-recipe-junctions.py <galleryOutDir>

输入（RecipeJunctionGallery 产出）：
    <gallery>/gallery.json                     seed / size / step / 场景 id 清单
    <gallery>/index.csv                        sceneId,group,name,centerX,centerZ,realLayout,status,channels
    <gallery>/scenes/<id>/scene.json           channels（参加条目 + 精确 recipe id）/ metrics
    <gallery>/scenes/<id>/{morph,finished-lerp,hard[,hard-old],participants}.f64be
    <gallery>/scenes/<id>/weights.f64be        row-major，每像素 P 个 double（P = 该场景通道数）
    <gallery>/scenes/<id>/profiles.csv
    <gallery>/scenes/<id>/{segments.csv,regions.csv}

输出（每场景原生 601²，1 像素 = 1 块，绝不缩放）：
    <id>-<layer>-native.png        各层原生图
    <id>-weights-native.png        参与配方权重的 RGB 混色
    <id>-weights-boundaries.png    同上 + 真实边界折线（深底 + 亮线，任何底色可见）
    <id>-participants-native.png   参与配方个数（不设上限，色标按实测范围）
    <id>-comparison.png            直接切换 / 引擎 morph / 成品平均 / 权重（2×2 原生）
    <id>-plateau-legacy.png        含台地：引擎 morph vs 旧 0..1.05（仅量纲对照）
    <id>-height-profiles.png       0/60/120° 穿交点高度剖面（±300 块，step 0.5）
    <id>-weight-profiles.png       同三条剖面的实际配方权重
    overview.png                   real + controlled 缩略索引（写明图数）
    index.html                     全部原生 PNG 链接
    image-metrics.json / provenance.txt

★ 场景标签一律用 catalog 的精确 recipe id，绝不出现 Category 名。
★ 剖面不标 ±120 块：三条都是穿交点的斜切，240 块带宽只对单条边的法向剖面有定义。
★ 图里没有 Blender / 海陆组装 / 水文；唯一的"旧层"是 <id>-plateau-legacy.png
  （台地旧 0..1.05 值域），仅为量纲对照 —— 含台地场景的新旧差异不能整体算作过渡效果。
"""
import csv
import json
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

FONT = 'C:/Windows/Fonts/msyh.ttc'
BG = '#f4f3ed'
INK = '#25261f'
DIM = '#55594c'
GRID = '#d8d7ce'
MORPH_C = '#b14c31'
LERP_C = '#337d9e'
HARD_C = '#8d9aa8'
OLD_C = '#8a6b3f'

GROUP_TITLES = {
    'REAL': '真实场景（真实 45 槽配方）',
    'CATEGORY': '受控·五大类三向组合',
    'COMPOSITE': '受控·低地复合三向组合',
    'SUBRECIPE': '受控·子配方三向组合',
}


def font(size):
    return ImageFont.truetype(FONT, size)


def load_renderer():
    import importlib.util
    path = Path(__file__).with_name('render-islands-big.py')
    spec = importlib.util.spec_from_file_location('existing_terrain_renderer', path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.render


def entry_color(index):
    """固定色相：同一 catalog 条目在所有图里同色。"""
    import colorsys
    r, g, b = colorsys.hsv_to_rgb((index * 0.6180339887) % 1.0, 0.58, 0.95)
    return np.array([r * 255.0, g * 255.0, b * 255.0])


def hex_of(color):
    return '#%02x%02x%02x' % tuple(int(v) for v in np.asarray(color))


def read_field(path, shape):
    assert path.exists(), f'missing {path}'
    data = np.fromfile(path, dtype='>f8')
    assert data.size == shape[0] * shape[1], f'{path} size {data.size} != {shape[0] * shape[1]}'
    out = data.reshape(shape)
    assert np.isfinite(out).all(), f'{path} has non-finite values'
    return out


def read_weights(path, n, p):
    """weights.f64be：row-major，每像素 P 个 double。返回 (n, n, P)。"""
    data = np.fromfile(path, dtype='>f8')
    assert data.size == n * n * p, f'{path} size {data.size} != {n}x{n}x{p}'
    out = data.reshape(n, n, p)
    assert np.isfinite(out).all(), f'{path} non-finite'
    assert out.min() >= -1e-12 and out.max() <= 1 + 1e-12, f'{path} weight out of range'
    total = out.sum(-1)
    assert np.allclose(total, 1, atol=1e-9), f'{path} weights do not sum to 1 (max dev ' \
                                             f'{np.abs(total - 1).max():.3e})'
    return out


def load_scene(root, entry):
    """读一场景：通道（精确 recipe id）+ 各层 + 权重；不做任何"最多 4 个"的假设。"""
    scene_dir = root / 'scenes' / entry['id']
    meta = json.loads((scene_dir / 'scene.json').read_text(encoding='utf-8'))
    n = int(meta['size'])
    p = int(meta['channelCount'])
    channels = [c['entryIndex'] for c in meta['channels']]
    ids = [c['id'] for c in meta['channels']]
    assert len(channels) == p, f'{entry["id"]} channelCount {p} != {len(channels)}'
    cores = {name: read_field(scene_dir / f'{name}.f64be', (n, n))
             for name in ('morph', 'finished-lerp', 'hard', 'participants')}
    hard_old = read_field(scene_dir / 'hard-old.f64be', (n, n)) \
        if (scene_dir / 'hard-old.f64be').exists() else None
    weights = read_weights(scene_dir / 'weights.f64be', n, p)
    participants = cores['participants']
    assert participants.min() >= 1, f'{entry["id"]} participant count < 1'
    assert participants.max() <= p, f'{entry["id"]} participant count {participants.max()} > {p} channels'
    # 真实场景的通道是"发现"出来的（粗扫 + 全部边点），可能略大于像素上实际用到的集合：
    # 这些通道整幅权重恒 0、只是留位，不算缺陷。受控场景必须每个通道都真的出现。
    unused = [c for k, c in enumerate(channels) if weights[..., k].max() <= 0]
    if not entry['realLayout']:
        assert not unused, f'{entry["id"]} controlled channel(s) {unused} never participate'
    assert participants.max() >= 1, f'{entry["id"]} no participating entry anywhere'
    return {'n': n, 'p': p, 'channels': channels, 'ids': ids, 'cores': cores,
            'hard_old': hard_old, 'weights': weights, 'participants': participants,
            'unused': unused, 'meta': meta}


def weights_rgb(state):
    colors = np.stack([entry_color(c) for c in state['channels']])
    mixed = np.tensordot(state['weights'], colors, axes=([-1], [0]))
    return Image.fromarray(np.uint8(np.clip(mixed, 0, 255)))


def participants_rgb(count):
    hi = max(3.0, float(count.max()))
    tone = 40 + (np.clip(count, 1, hi) - 1) / (hi - 1) * 195
    return Image.fromarray(np.uint8(np.clip(np.repeat(tone[..., None], 3, axis=-1), 0, 255)))


def draw_run(draw, run):
    """两遍描边：深底宽线 + 亮色细线 ⇒ 深浅底色上都看得见。"""
    if len(run) < 2:
        return
    draw.line(run, fill='#2b2c26', width=4)
    draw.line(run, fill='#f7f1dc', width=2)


def overlay_boundaries(scene_dir, n, center, image):
    """把真实边界折线画到权重图上；出画布部分裁掉。"""
    path = scene_dir / 'segments.csv'
    if not path.exists():
        return image
    segs = np.genfromtxt(path, delimiter=',', names=True)
    if segs.size == 0:
        return image
    draw = ImageDraw.Draw(image)
    ox = center[0] - n // 2
    oz = center[1] - n // 2
    limit = n - 1
    for sid in np.unique(segs['segId']):
        part = segs[segs['segId'] == sid]
        run = []
        for x, z in zip(part['x'], part['z']):
            px, pz = float(x - ox), float(z - oz)
            if 0 <= px <= limit and 0 <= pz <= limit:
                run.append((px, pz))
            elif run:
                draw_run(draw, run)
                run = []
        draw_run(draw, run)
    return image


def panel(images, titles, heading, subtitle, target, note='', cols=2):
    """原生网格面板：每格 n² 不缩放。"""
    n = images[0].width
    rows = (len(images) + cols - 1) // cols
    margin, pad = 30, 40
    width = margin * 2 + cols * n + (cols - 1) * pad
    height = margin + 116 + rows * (n + 48) + (58 if note else 20)
    canvas = Image.new('RGB', (width, height), BG)
    draw = ImageDraw.Draw(canvas)
    draw.text((margin, 18), heading, font=font(27), fill=INK)
    draw.text((margin, 62), subtitle, font=font(17), fill=DIM)
    for i, (image, title) in enumerate(zip(images, titles)):
        x = margin + (i % cols) * (n + pad)
        y = margin + 116 + (i // cols) * (n + 48)
        draw.text((x, y), title, font=font(18), fill=INK)
        canvas.paste(image, (x, y + 28))
    if note:
        draw.text((margin, height - 42), note, font=font(16), fill=DIM)
    canvas.save(target)


def dashed(draw, points, fill, width):
    for i in range(0, len(points) - 1, 2):
        draw.line([points[i], points[i + 1]], fill=fill, width=width)


def plot(draw, box, xs, series, lo, hi, title, vlines=(0,), xticks=(-300, -150, 0, 150, 300)):
    left, top, right, bottom = box
    draw.text((left, top - 30), title, font=font(17), fill=INK)
    for val in np.linspace(lo, hi, 5):
        py = bottom - (val - lo) / (hi - lo) * (bottom - top)
        draw.line((left, py, right, py), fill=GRID)
        draw.text((left - 72, py - 10), f'{val:.1f}', font=font(14), fill=DIM)
    span = xs[-1] - xs[0]
    for marker in vlines:
        px = left + (marker - xs[0]) / span * (right - left)
        draw.line((px, top, px, bottom), fill='#aaa69b', width=2)
    for values, color, width, dash in series:
        points = [(left + (x - xs[0]) / span * (right - left),
                   bottom - (np.clip(y, lo, hi) - lo) / (hi - lo) * (bottom - top))
                  for x, y in zip(xs, values)]
        if dash:
            dashed(draw, points, color, width)
        else:
            draw.line(points, fill=color, width=width)
    for tick in xticks:
        px = left + (tick - xs[0]) / span * (right - left)
        draw.text((px - 16, bottom + 6), f'{tick:.0f}', font=font(14), fill=DIM)


def height_profiles(scene_dir, entry_id, state, dest):
    csv = np.genfromtxt(scene_dir / 'profiles.csv', delimiter=',', names=True)
    names = set(csv.dtype.names)
    has_old = state['meta']['plateauLegacyExported'] and 'hard_old_y' in names
    canvas = Image.new('RGB', (1620, 1810), BG)
    draw = ImageDraw.Draw(canvas)
    draw.text((70, 16), f'{entry_id}｜穿交点高度剖面（step 0.5 块，±300 块）', font=font(27), fill=INK)
    draw.text((70, 60), '红=节点形态过渡　蓝=成品高度平均（先换Y再加权，仅诊断）　灰=主导条目直接切换',
              font=font(17), fill=DIM)
    if has_old:
        draw.text((70, 92), '棕虚线=同一主导条目的旧台地1.05值域；仅作对照，不是过渡结果。', font=font(17), fill=DIM)
    columns = ['morph_y', 'lerp_y', 'hard_y'] + (['hard_old_y'] if has_old else [])
    lo = min(float(np.nanmin(csv[c])) for c in columns) - 4
    hi = max(float(np.nanmax(csv[c])) for c in columns) + 4
    for i, angle in enumerate(np.unique(csv['angle'])):
        line = csv[csv['angle'] == angle]
        series = [(line['hard_y'], HARD_C, 1, False),
                  (line['lerp_y'], LERP_C, 2, False),
                  (line['morph_y'], MORPH_C, 2, False)]
        if has_old and np.isfinite(line['hard_old_y']).any():
            series.append((line['hard_old_y'], OLD_C, 1, True))
        plot(draw, (115, 175 + i * 520, 1580, 470 + i * 520), line['t'], series, lo, hi,
             f'{angle:.0f}°｜Y（格）｜竖线只标交点')
    draw.text((70, 1690), '仅局部陆地形态，不含Blender、海陆组装或水文；旧台地值域对照仅在含台地场景绘制。',
              font=font(16), fill=DIM)
    draw.text((70, 1722), '±120 块带缘只对单条边的法向剖面有定义；三条剖面都是穿交点的斜切，'
                          '所以这里不标 ±120。', font=font(16), fill=DIM)
    draw.text((70, 1754), '蓝线先将各原配方换成世界Y再加权；不是先平均配方值再换Y。原始剖面不做平滑。',
              font=font(16), fill=DIM)
    canvas.save(dest / f'{entry_id}-height-profiles.png')


def weight_profiles(scene_dir, entry_id, state, dest):
    csv = np.genfromtxt(scene_dir / 'profiles.csv', delimiter=',', names=True)
    canvas = Image.new('RGB', (1620, 1810), BG)
    draw = ImageDraw.Draw(canvas)
    draw.text((70, 16), f'{entry_id}｜同一三条剖面的实际配方权重', font=font(27), fill=INK)
    draw.text((70, 60), '每条曲线一个 catalog 条目（下标即条目号），权重和恒为 1；'
                        '通道数 = 该场景实际参与条目数，<不设上限>。', font=font(17), fill=DIM)
    for i, angle in enumerate(np.unique(csv['angle'])):
        line = csv[csv['angle'] == angle]
        series = [(line[f'w{c}'], hex_of(entry_color(c)), 2, False) for c in state['channels']]
        legend = '　'.join(f'{c}={name}' for c, name in zip(state['channels'], state['ids']))
        plot(draw, (115, 180 + i * 520, 1580, 475 + i * 520), line['t'], series, 0, 1,
             f'{angle:.0f}°｜权重｜{legend}')
    draw.text((70, 1690), '真实场景按实际 45 票槽归并为条目，绝不按 Category 降级；'
                          '出现几个参与条目就画几条，不做截断。', font=font(16), fill=DIM)
    canvas.save(dest / f'{entry_id}-weight-profiles.png')


def scene_images(scene_dir, entry, state, render, dest):
    n, step = state['n'], float(state['meta']['step'])
    images = {}
    for name, arr in state['cores'].items():
        if name == 'participants':
            continue
        image = render(arr, step)
        image.save(dest / f'{entry["id"]}-{name}-native.png')
        images[name] = image
    if state['hard_old'] is not None:
        images['hard-old'] = render(state['hard_old'], step)
        images['hard-old'].save(dest / f'{entry["id"]}-hard-old-native.png')
    weight = weights_rgb(state)
    weight.save(dest / f'{entry["id"]}-weights-native.png')
    boundaries = overlay_boundaries(scene_dir, n, (entry['centerX'], entry['centerZ']), weight.copy())
    boundaries.save(dest / f'{entry["id"]}-weights-boundaries.png')
    participants_rgb(state['participants']).save(dest / f'{entry["id"]}-participants-native.png')
    return images, boundaries


def center_label(entry, state):
    cy, cx = state['n'] // 2, state['n'] // 2
    row = [(c, name, float(state['weights'][cy, cx, k]))
           for k, (c, name) in enumerate(zip(state['channels'], state['ids']))]
    row.sort(key=lambda t: -t[2])
    active = [t for t in row if t[2] > 0]
    return '　'.join(f'{name}={w:.3f}' for _, name, w in active), row


def scene_panels(entry, state, images, weight, dest, label):
    subtitle = (f'中心 ({entry["centerX"]:.1f}, {entry["centerZ"]:.1f})｜参与条目 {state["p"]} 个｜{label}')
    panel([images['hard'], images['morph'], images['finished-lerp'], weight],
          ['主导条目直接切换（无过渡）', f'引擎 {state["p"]} 路权重合成',
           '成品高度按权重平均（仅诊断）', '权重混色（亮线=真实边界）'],
          f'{entry["id"]}｜{GROUP_TITLES.get(entry["group"], entry["group"])}｜'
          f'{state["n"]}×{state["n"]} 原生，1 像素 = 1 块',
          subtitle, dest / f'{entry["id"]}-comparison.png',
          '受控场景不是真实布局；真实场景使用全部参与条目，不裁第四个、也不设参与上限。')
    if images.get('hard-old') is not None:
        panel([images['hard-old'], images['morph']],
              ['台地旧 0..1.05 口径（仅量纲对照）', '引擎合成（台地已用修正 0..0.9）'],
              f'{entry["id"]}｜含台地：新旧值域分开看',
              '这里没有 Blender / 海陆组装 / 水文；差异不能整体归因于过渡算法。',
              dest / f'{entry["id"]}-plateau-legacy.png', cols=2)


def scene_metrics(entry, state, label):
    morph = state['cores']['morph']
    count = state['participants']
    row = {
        'group': entry['group'],
        'realLayout': entry['realLayout'],
        'center': [entry['centerX'], entry['centerZ']],
        'channelCount': state['p'],
        'channels': state['ids'],
        'centerWeights': label,
        'participantPixels': {str(int(k)): int((count == k).sum()) for k in np.unique(count)},
        'maxParticipants': int(count.max()),
        'yMin': float(morph.min()),
        'yMax': float(morph.max()),
    }
    for name, arr in state['cores'].items():
        if name != 'morph':
            row[f'maxAbsDiffVs_{name}'] = float(np.abs(morph - arr).max())
    if state['hard_old'] is not None:
        row['maxAbsDiffVs_hard-old'] = float(np.abs(morph - state['hard_old']).max())
    return row


def overview(entries, dest):
    """缩略索引：real 在前，受控在后；标题写明图数，原生图链接在 index.html。"""
    thumbs = []
    for e in entries:
        path = dest / f'{e["id"]}-morph-native.png'
        if path.exists():
            thumbs.append((e, path))
    if not thumbs:
        return
    real = sum(1 for e, _ in thumbs if e['group'] == 'REAL')
    cols, cell, pad, margin = 5, 300, 30, 34
    rows = (len(thumbs) + cols - 1) // cols
    canvas = Image.new('RGB', (margin * 2 + cols * cell + (cols - 1) * pad,
                               margin + 130 + rows * (cell + 56) + 60), BG)
    draw = ImageDraw.Draw(canvas)
    draw.text((margin, 18), f'配方交汇总览｜真实 {real} 张 + 受控 {len(thumbs) - real} 张 = {len(thumbs)} 张',
              font=font(26), fill=INK)
    draw.text((margin, 58), '缩略图仅索引，原生 601² 与剖面见 index.html。'
                            '真实场景 = 真实 45 槽配方；受控场景 = 同一真实三格几何 + 指定配方。',
              font=font(16), fill=DIM)
    draw.text((margin, 82), '受控覆盖：五大类三三 10 组 + 低地复合 4 组（复合 5..14 全覆盖）+ 子配方 1 组 ⇒ 18 条目全部上镜。',
              font=font(16), fill=DIM)
    for i, (e, path) in enumerate(thumbs):
        with Image.open(path) as im:
            thumb = im.convert('RGB').resize((cell, cell), Image.LANCZOS)
        x = margin + (i % cols) * (cell + pad)
        y = margin + 130 + (i // cols) * (cell + 56)
        canvas.paste(thumb, (x, y))
        draw.text((x, y + cell + 4), e['id'], font=font(15), fill=INK)
        draw.text((x, y + cell + 22), GROUP_TITLES.get(e['group'], e['group']), font=font(14), fill=DIM)
    canvas.save(dest / 'overview.png')


def index_html(gallery, entries, metrics, dest):
    rows = []
    for e in entries:
        previews = []
        for suffix in ('comparison', 'plateau-legacy', 'old', 'weights-boundaries',
                       'height-profiles', 'weight-profiles'):
            path = dest / f'{e["id"]}-{suffix}.png'
            if path.exists():
                previews.append(f'<a href="{path.name}">{suffix}</a>')
        natives = sorted(p.name for p in dest.glob(f'{e["id"]}-*-native.png'))
        native_links = ' '.join(f'<a href="{n}">{n}</a>' for n in natives)
        label = metrics[e['id']]['centerWeights']
        rows.append(
            f'<tr><td><b>{e["id"]}</b><br>{GROUP_TITLES.get(e["group"], e["group"])}</td>'
            f'<td>{label}</td><td>{metrics[e["id"]]["channelCount"]}</td>'
            f'<td><a href="{e["id"]}-morph-native.png">'
            f'<img src="{e["id"]}-morph-native.png" width="180"></a></td>'
            f'<td>{" ".join(previews)}<br>{native_links}</td></tr>')
    real = sum(1 for e in entries if e['group'] == 'REAL')
    html = ('<!doctype html><meta charset="utf-8"><title>recipe junctions</title>'
            '<style>body{background:#f4f3ed;color:#25261f;font:14px/1.6 system-ui}'
            'td{vertical-align:top;padding:8px;border-bottom:1px solid #d8d7ce}'
            'a{color:#337d9e;display:inline-block;margin:0 8px 4px 0}</style>'
            f'<h2>三向配方交汇画廊｜seed={gallery["seed"]}｜{gallery["size"]}×{gallery["size"]} 原生（1 像素 = 1 块）</h2>'
            f'<p>{real} 张真实场景 + {len(entries) - real} 张受控场景 = {len(entries)} 张。'
            '受控组合是受控布局；只有 real 场景代表真实槽位配方。图里没有 Blender / 海陆组装 / 水文。</p>'
            '<p>总览：<a href="overview.png">overview.png</a>'
            '　剖面口径：三条都是穿交点的斜切，故不标 ±120 块。</p>'
            '<table><tr><th>scene</th><th>中心权重（精确 recipe id）</th><th>参与条目</th>'
            '<th>图</th><th>链接</th></tr>' + ''.join(rows) + '</table>')
    (dest / 'index.html').write_text(html, encoding='utf-8')
    (dest / 'image-metrics.json').write_text(
        json.dumps(metrics, ensure_ascii=False, indent=2), encoding='utf-8')


def provenance(gallery, entries, dest):
    real = sum(1 for e in entries if e['group'] == 'REAL')
    groups = {}
    for e in entries:
        groups[e['group']] = groups.get(e['group'], 0) + 1
    formula = gallery.get('finishedLerpFormula', 'n/a')
    lines = [
        f'seed={gallery["seed"]} (fixed, frozen baseline)',
        f'scenes total={len(entries)} real={real} controlled={len(entries) - real} '
        f'by group={json.dumps(groups, sort_keys=True)}',
        f'sample size={gallery["size"]}x{gallery["size"]} step={gallery["step"]} (native, no scaling)',
        f'local geometry radius={gallery["profileHalf"] * 1.5:.0f} blocks, world polyline maxSeg=2 blocks',
        'profiles=3 angles 0/60/120 deg through the junction, step 0.5 blocks, half span 300 blocks',
        'triangular profiles are NOT labelled +-120: the 240-block band edge is only defined along one '
        'edge normal, so it does not apply to oblique cuts',
        'real scenes merge weights through the real 45-slot table (catalog.slotEntry), never by Category; '
        'every participating entry is kept and there is no participant cap',
        'controlled arm covers all 18 entries: 10 five-class triples + 4 lowland-composite triples '
        '(composites 5..14) + 1 sub-recipe triple (1,2,4)',
        'scene labels use exact recipe ids; Category names appear nowhere in the figures',
        f'finished-lerp formula={formula}  (convert-then-blend; NOT y(sum w_i*reference_i), because '
        'HeightCurve.heightFromE is non-linear)',
        'layers are LAND COMPOSITION ONLY: no Blender, no sea/land assembly, no hydrology. The only '
        'legacy layer is <scene>-hard-old (plateau 0..1.05), kept purely as a scale reference: '
        'new-vs-old differences in plateau scenes cannot be attributed to the transition algorithm alone',
        'config gate: frozen TOML is verified on a throwaway copy before setConfig, so ForgeConfigSpec '
        'cannot silently auto-correct values into the figures',
        'no full-frame render; JVM terrain overrides=none',
        'production-output invariance is NOT asserted by this renderer: no baseline hash was measured '
        'here. The owning agent reports the baseline hash for this run separately.',
    ]
    (dest / 'provenance.txt').write_text('\n'.join(lines) + '\n', encoding='utf-8')


def read_index(root):
    path = root / 'index.csv'
    entries = []
    for row in csv.DictReader(path.read_text(encoding='utf-8').splitlines()):
        entries.append({'id': row['sceneId'], 'group': row['group'],
                        'name': row['name'], 'centerX': float(row['centerX']),
                        'centerZ': float(row['centerZ']),
                        'realLayout': row['realLayout'].strip().lower() == 'true'})
    return entries


def main():
    root = Path(sys.argv[1])
    dest = Path(sys.argv[2]) if len(sys.argv) > 2 else root / 'figures'
    dest.mkdir(parents=True, exist_ok=True)
    gallery = json.loads((root / 'gallery.json').read_text(encoding='utf-8'))
    entries = read_index(root)
    render = load_renderer()
    metrics = {}
    for entry in entries:
        scene_dir = root / 'scenes' / entry['id']
        state = load_scene(root, entry)
        images, weight = scene_images(scene_dir, entry, state, render, dest)
        label, _ = center_label(entry, state)
        scene_panels(entry, state, images, weight, dest, label)
        height_profiles(scene_dir, entry['id'], state, dest)
        weight_profiles(scene_dir, entry['id'], state, dest)
        metrics[entry['id']] = scene_metrics(entry, state, label)
        print('rendered', entry['id'], f'channels={state["p"]}')
    overview(entries, dest)
    index_html(gallery, entries, metrics, dest)
    provenance(gallery, entries, dest)
    for path in dest.glob('*.png'):
        with Image.open(path) as im:
            im.verify()
    print(json.dumps({'scenes': len(metrics), 'out': str(dest)}, ensure_ascii=False))


if __name__ == '__main__':
    main()
