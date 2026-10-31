"""四组独立过渡的原生图、五阶段图、剖面与证据归档。"""
import importlib.util
import json
import shutil
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw


def load_helpers():
    path = Path(__file__).with_name('render-hills-plateau-morph.py')
    spec = importlib.util.spec_from_file_location('pair_render_helpers', path)
    helper = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(helper)
    return helper


def render_pair(root, dest, title, geo, helper):
    dest.mkdir(parents=True, exist_ok=True)
    render, font = helper.load_renderer(), helper.font
    n, w, h = geo['stageSize'], geo['width'], geo['height']
    stages = [np.fromfile(root / f'stage-{i}.f64be', dtype='>f8').reshape(n, n) for i in range(5)]
    if not all(np.isfinite(a).all() for a in stages):
        raise ValueError('invalid stage')
    canvas = Image.new('RGB', (60 + 5 * (n + 20), n + 210), '#f4f3ed')
    draw = ImageDraw.Draw(canvas)
    draw.text((30, 15), title + ' · 五阶段（同坐标、同色带、未缩放）', font=font(30), fill='#25261f')
    draw.text((30, 65), '固定种子；原始信号、算子强度与振幅渐变；1 像素 = 2 块；不接整幅地形。', font=font(22), fill='#55594c')
    stats = []
    for i, a in enumerate(stages):
        image = render(a, 2)
        image.save(dest / f'stage-{i}.png')
        x = 30 + i * (n + 20)
        canvas.paste(image, (x, 110))
        draw.text((x, n + 120), f'{i*25}%  Y={a.min():.1f}–{a.max():.1f}', font=font(22), fill='#25261f')
        stats.append({'position': i / 4, 'min_y': float(a.min()), 'max_y': float(a.max()), 'median_y': float(np.median(a))})
    canvas.save(dest / 'stages-panel.png')
    morph = np.fromfile(root / 'spatial.f64be', dtype='>f8').reshape(h, w)
    lerp = np.fromfile(root / 'finished-lerp.f64be', dtype='>f8').reshape(h, w)
    assert np.isfinite(morph).all() and np.isfinite(lerp).all()
    native = render(morph, 1)
    native.save(dest / 'spatial-native.png')
    canvas = Image.new('RGB', (w + 80, h * 2 + 260), '#f4f3ed')
    draw = ImageDraw.Draw(canvas)
    draw.text((40, 15), title + ' · 受控直线交界', font=font(30), fill='#25261f')
    draw.text((40, 60), '左 → 右；两线之间为 240 块过渡带；1 像素 = 1 块；中心 (-140,137)', font=font(21), fill='#55594c')
    for a, y, label in ((morph, 130, '形态原型'), (lerp, h + 230, '诊断对照：两份成品高度混合')):
        canvas.paste(render(a, 1), (40, y))
        for bx in (w // 2 - 120, w // 2 + 120):
            draw.line((40 + bx, y, 40 + bx, y + h), fill='#e2c58d')
        draw.text((40, y - 32), label, font=font(23), fill='#25261f')
    canvas.save(dest / 'spatial-comparison.png')
    profiles = np.genfromtxt(root / 'profiles.csv', delimiter=',', names=True)
    canvas = Image.new('RGB', (1600, 1520), '#f4f3ed')
    draw = ImageDraw.Draw(canvas)
    draw.text((90, 15), title + ' · 高度剖面（原始值，不平滑）', font=font(30), fill='#25261f')
    draw.text((90, 65), '红：形态原型  蓝：成品混合  浅灰：两端原族（台地为参考值域）  竖线：带缘', font=font(21), fill='#44483e')
    lo = min(profiles[c].min() for c in ('morph_y', 'lerp_y', 'left_y', 'right_y')) - 5
    hi = max(profiles[c].max() for c in ('morph_y', 'lerp_y', 'left_y', 'right_y')) + 5
    zs = np.unique(profiles['z'])
    for i, z in enumerate(zs):
        p = profiles[profiles['z'] == z]
        series = [(p['left_y'], '#c2c2b8', 1), (p['right_y'], '#b8b6ad', 1),
                  (p['lerp_y'], '#337d9e', 2), (p['morph_y'], '#b14c31', 2)]
        helper.line_chart(draw, (100, 145 + i * 350, 1530, 380 + i * 350),
                          p['x'] - geo['center'][0], series, lo, hi, f'Z={z:.0f}｜Y（格）')
    p = profiles[profiles['z'] == zs[1]]
    sm, sl = np.gradient(p['morph_y'], .5), np.gradient(p['lerp_y'], .5)
    bound = max(abs(sm).max(), abs(sl).max(), .001) * 1.05
    helper.line_chart(draw, (100, 1220, 1530, 1430), p['x'] - geo['center'][0],
                      [(sl, '#337d9e', 2), (sm, '#b14c31', 2)], -bound, bound, '中心剖面 dY/dX；尖峰原样保留，不代表已通过平滑验收')
    canvas.save(dest / 'height-profiles.png')
    for name in ('profiles.csv', 'seams.csv', 'metrics.txt', 'interior-scan.csv'):
        shutil.copyfile(root / name, dest / name)
    (dest / 'image-metrics.json').write_text(json.dumps(stats, indent=2), encoding='utf-8')
    for path in dest.glob('*.png'):
        with Image.open(path) as image:
            image.verify()
    print(title, json.dumps(stats))
    return native


def main():
    root, dest = map(Path, sys.argv[1:3])
    geo = json.loads((root / 'geometry.json').read_text(encoding='utf-8'))
    helper = load_helpers()
    names = {'plain_hills': '平原 → 丘陵', 'hills_mountains': '丘陵 → 山地',
             'plateau_mountains': '台地 → 山地', 'plateau_badlands': '台地 → 劣地'}
    remaining = len(sys.argv) > 3 and sys.argv[3] == 'remaining'
    if remaining:
        names = {'plain_plateau': '平原 → 台地', 'plain_mountains': '平原 → 山地',
                 'plain_badlands': '平原 → 劣地', 'hills_badlands': '丘陵 → 劣地',
                 'mountains_badlands': '山地 → 劣地'}
    canvas = Image.new('RGB', (1281, len(names) * 711 + 30), '#f4f3ed')
    draw = ImageDraw.Draw(canvas)
    for i, (key, title) in enumerate(names.items()):
        native = render_pair(root / key, dest / key, title, geo, helper)
        y = 30 + i * 711
        draw.text((40, y), title + '｜1 像素 = 1 块，线间 240 块', font=helper.font(28), fill='#25261f')
        canvas.paste(native, (40, y + 65))
        for bx in (480, 720):
            draw.line((40 + bx, y + 65, 40 + bx, y + 666), fill='#e2c58d')
    canvas.save(dest / ('remaining-five-native.png' if remaining else 'four-pairs-native.png'))
    for name in ('geometry.json', 'config-input.toml'):
        shutil.copyfile(root / name, dest / name)


if __name__ == '__main__':
    main()
