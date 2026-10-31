"""局部真实边界：明确区分真实槽位旧地形与受控 PHM 原型，原生输出不缩放。"""
import importlib.util
import json
import shutil
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw


def helpers():
    path = Path(__file__).with_name('render-hills-plateau-morph.py')
    spec = importlib.util.spec_from_file_location('local_plot_helper', path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def panel(images, titles, heading, subtitle, target, helper):
    n = images[0].width
    canvas = Image.new('RGB', (n * 2 + 100, n * 2 + 260), '#f4f3ed')
    draw = ImageDraw.Draw(canvas)
    draw.text((30, 15), heading, font=helper.font(29), fill='#25261f')
    draw.text((30, 62), subtitle, font=helper.font(19), fill='#55594c')
    for i, (image, title) in enumerate(zip(images, titles)):
        x, y = 30 + (i % 2) * (n + 40), 135 + (i // 2) * (n + 70)
        draw.text((x, y - 34), title, font=helper.font(21), fill='#25261f')
        canvas.paste(image, (x, y))
    canvas.save(target)


def chart(draw, rect, x, series, lo, hi, caption, helper, markers=(0,)):
    left, top, right, bottom = rect
    draw.text((left, top - 29), caption, font=helper.font(19), fill='#25261f')
    for tick in np.linspace(lo, hi, 5):
        py = bottom - (tick - lo) / (hi - lo) * (bottom - top)
        draw.line((left, py, right, py), fill='#d8d7ce')
        draw.text((left - 62, py - 10), f'{tick:.1f}', font=helper.font(17), fill='#55594c')
    for marker in markers:
        px = left + (marker - x.min()) / np.ptp(x) * (right - left)
        draw.line((px, top, px, bottom), fill='#aaa69b')
    for values, color, width in series:
        points = list(zip(left + (x - x.min()) / np.ptp(x) * (right - left),
                          bottom - (values - lo) / (hi - lo) * (bottom - top)))
        draw.line(points, fill=color, width=width)
    for tick in np.linspace(x.min(), x.max(), 5):
        px = left + (tick - x.min()) / np.ptp(x) * (right - left)
        draw.text((px - 20, bottom + 5), f'{tick:.0f}', font=helper.font(17), fill='#55594c')


def profiles(root, dest, helper):
    p = np.genfromtxt(root / 'profiles.csv', delimiter=',', names=True)
    canvas = Image.new('RGB', (1600, 1560), '#f4f3ed')
    draw = ImageDraw.Draw(canvas)
    draw.text((80, 20), '三条穿越真实交汇的高度剖面 · 受控平原 / 丘陵1 / 山地1', font=helper.font(30), fill='#25261f')
    draw.text((80, 75), '红：参数形态过渡　蓝：成品高度平均（仅诊断）　灰：直接切换　原始值，不平滑', font=helper.font(23), fill='#55594c')
    lo = min(p[c].min() for c in ('morph_y', 'lerp_y', 'hard_y')) - 5
    hi = max(p[c].max() for c in ('morph_y', 'lerp_y', 'hard_y')) + 5
    for i, angle in enumerate(np.unique(p['angle'])):
        line = p[p['angle'] == angle]
        chart(draw, (100, 170 + i * 450, 1530, 390 + i * 450), line['distance'],
              [(line['hard_y'], '#aaa99f', 1), (line['lerp_y'], '#337d9e', 2),
               (line['morph_y'], '#b14c31', 2)], lo, hi,
              f'{angle:.0f}°｜距交汇点（块） / Y（格）；中线仅标交汇点，不把斜剖面误标为 ±120 带缘', helper)
        chart(draw, (100, 460 + i * 450, 1530, 555 + i * 450), line['distance'],
              [(line['plain'], '#9fbb77', 2), (line['hills'], '#d1a458', 2),
               (line['mountain'], '#777ea9', 2)], 0, 1,
              '参与权重：绿=平原，黄=丘陵1，紫=山地1；不是成品高度贡献百分比', helper)
    canvas.save(dest / 'height-profiles.png')


def normal_profile(root, dest, helper):
    p = np.genfromtxt(root / 'normal-height-profile.csv', delimiter=',', names=True)
    canvas = Image.new('RGB', (1500, 850), '#f4f3ed')
    draw = ImageDraw.Draw(canvas)
    draw.text((80, 20), '普通真实弯边 · 法向高度与240块带宽', font=helper.font(30), fill='#25261f')
    draw.text((80, 75), '受控平原↔丘陵1；1/2块采样；竖线为法向 ±120块；非三交汇斜剖面。', font=helper.font(22), fill='#55594c')
    lo = min(p[k].min() for k in ('morph_y', 'lerp_y', 'hard_y')) - 3
    hi = max(p[k].max() for k in ('morph_y', 'lerp_y', 'hard_y')) + 3
    chart(draw, (100, 175, 1420, 440), p['distance'],
          [(p['hard_y'], '#aaa99f', 1), (p['lerp_y'], '#337d9e', 2),
           (p['morph_y'], '#b14c31', 2)], lo, hi,
          '红=参数形态过渡，蓝=成品平均诊断，灰=直接切换；Y（格）', helper, (-120, 0, 120))
    chart(draw, (100, 545, 1420, 740), p['distance'],
          [(p['weight_plain'], '#9fbb77', 2), (p['weight_hills'], '#d1a458', 2)],
          0, 1, '绿=平原，黄=丘陵1；两侧饱和点约在 -120 / +120块', helper, (-120, 0, 120))
    canvas.save(dest / 'ordinary-boundary-profile.png')


def geometry_overlay(root, image, geo):
    draw = ImageDraw.Draw(image)
    segments = np.genfromtxt(root / 'segments.csv', delimiter=',', names=True)
    ox, oz = np.array(geo['center']) - geo['size'] // 2
    for sid in np.unique(segments['segId']):
        s = segments[segments['segId'] == sid]
        points = [(float(x - ox), float(z - oz)) for x, z in zip(s['x'], s['z'])]
        if len(points) > 1:
            draw.line(points, fill='#faf4df', width=2)
    return image


def main():
    root, dest = map(Path, sys.argv[1:3])
    dest.mkdir(parents=True, exist_ok=True)
    h = helpers()
    render = h.load_renderer()
    geo = json.loads((root / 'geometry.json').read_text(encoding='utf-8'))
    n = geo['size']
    names = ('morph', 'finished-lerp', 'controlled-hard', 'actual-selected', 'actual-region', 'actual-covered')
    arrays = {name: np.fromfile(root / (name + '.f64be'), dtype='>f8').reshape(n, n) for name in names}
    assert all(np.isfinite(a).all() for a in arrays.values())
    images = {name: render(a, geo['step']) for name, a in arrays.items()}
    for name, image in images.items():
        image.save(dest / (name + '-native.png'))
    weights = np.stack([np.fromfile(root / f'weight-{i}.f64be', dtype='>f8').reshape(n, n) for i in range(3)], axis=-1)
    assert np.allclose(weights.sum(-1), 1, atol=1e-12)
    colors = np.array([[144, 178, 101], [215, 164, 81], [116, 121, 166]])
    weight_image = Image.fromarray(np.uint8(np.clip(weights @ colors, 0, 255)))
    weight_image.save(dest / 'weights-native.png')
    weight_lines = geometry_overlay(root, weight_image.copy(), geo)
    weight_lines.save(dest / 'weights-boundaries.png')
    panel([images['controlled-hard'], images['morph'], images['finished-lerp'], weight_lines],
          ['受控三族直接切换（无过渡）', '参数形态过渡（真实边界几何）',
           '诊断对照：三份成品高度平均', '权重：绿平原 / 黄丘陵1 / 紫山地1；白线是真实边'],
          '真实三交汇几何＋受控代表配方 · 局部实验',
          '601×601；1像素=1块；单侧120块；实际谷地 DALES 在本实验中明确换成丘陵1；未接整幅。',
          dest / 'controlled-comparison.png', h)
    panel([images['actual-selected'], images['actual-region'], images['actual-covered'], images['morph']],
          ['真实槽位配方：M1 / DALES / PLAINS（直接选型）', '旧区域机制：含位置相关公共 border',
           '旧陆地层：含原覆盖层（不含海陆组装）', '受控实验：DALES→丘陵1，替换区域过渡'],
          '实际槽位旧地形与受控实验分开看 · 不是同配方 A/B',
          '原图仅在新位置采样；未改旧路径；右下更换了代表配方，不能把全部差异归因于边界算法。',
          dest / 'actual-context.png', h)
    profiles(root, dest, h)
    normal_profile(root, dest, h)
    count = (weights > 0).sum(-1)
    metrics = {'min_y': float(arrays['morph'].min()), 'max_y': float(arrays['morph'].max()),
               'participants_pixels': {str(i): int((count == i).sum()) for i in range(1, 4)},
               'center_weights': weights[n // 2, n // 2].tolist(),
               'max_y_difference_vs_finished_lerp': float(abs(arrays['morph'] - arrays['finished-lerp']).max())}
    (dest / 'image-metrics.json').write_text(json.dumps(metrics, indent=2), encoding='utf-8')
    for name in ('geometry.json', 'scene.json', 'regions.csv', 'segments.csv', 'profiles.csv',
                 'spatial-limit.csv', 'normal-height-profile.csv', 'config-input.toml', 'checks.txt'):
        shutil.copyfile(root / name, dest / name)
    for path in dest.glob('*.png'):
        with Image.open(path) as im:
            im.verify()
    print(json.dumps(metrics, indent=2))


if __name__ == '__main__':
    main()
