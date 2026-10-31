"""固定240 / 仅宽度 / 宽度+偏移 三版原生对照；不优化生成路径。"""
import importlib.util
import json
import shutil
import sys
from pathlib import Path
import numpy as np
from PIL import Image, ImageDraw


def helpers():
    spec = importlib.util.spec_from_file_location('width_helpers', Path(__file__).with_name('render-adaptive-width.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module, module.helpers()


def render_pair(root, dest, title, width_helper, helper):
    dest.mkdir(parents=True, exist_ok=True)
    render, font = helper.load_renderer(), helper.font
    fields = [np.fromfile(root / (key + '.f64be'), dtype='>f8').reshape(601, 1201) for key in ('fixed', 'width', 'offset')]
    guide = np.genfromtxt(root / 'guides.csv', delimiter=',', names=True)
    assert all(np.isfinite(a).all() for a in fields)
    labels = ['A：固定 240 块', 'B：自适应宽度（中心固定）', 'C：同B宽度 + 地形引导偏移']
    canvas = Image.new('RGB', (1281, 3 * 701 + 130), '#f4f3ed')
    d = ImageDraw.Draw(canvas)
    d.text((40, 15), title + ' · 三版对照（1 像素 = 1 块）', font=font(30), fill='#25261f')
    d.text((40, 60), '配方/种子/窗口不变｜金线仅标带缘，C棕线标50%进度｜无性能优化', font=font(21), fill='#55594c')
    for i, a in enumerate(fields):
        y = 130 + i * 701
        image = render(a, 1)
        image.save(dest / ('abc'[i] + '-native.png'))
        canvas.paste(image, (40, y))
        d.text((40, y - 32), labels[i], font=font(24), fill='#25261f')
        for side in (-1, 1):
            points = [(640 + (guide['offset'][j] if i == 2 else 0)
                       + side * (240 if i == 0 else guide['width'][j]) / 2, y + j) for j in range(601)]
            d.line(points, fill='#e2c58d', width=1)
        if i == 2:
            d.line([(640 + v, y + j) for j, v in enumerate(guide['offset'])], fill='#c77945', width=1)
    canvas.save(dest / 'transition-abc.png')
    # 原生局部图不叠线，便于区分标注线和真实纹理。
    close = Image.new('RGB', (1570, 460), '#f4f3ed')
    cd = ImageDraw.Draw(close)
    for i, a in enumerate(fields):
        x = 30 + i * 510
        close.paste(render(a, 1).crop((360, 120, 840, 480)), (x, 70))
        cd.text((x, 20), labels[i], font=font(21), fill='#25261f')
    close.save(dest / 'closeup-abc.png')
    profile = np.genfromtxt(root / 'profiles.csv', delimiter=',', names=True)
    chart = Image.new('RGB', (1600, 1520), '#f4f3ed')
    pd = ImageDraw.Draw(chart)
    pd.text((90, 15), title + ' · 三版高度剖面（未平滑）', font=font(30), fill='#25261f')
    pd.text((90, 65), '蓝A固定240｜灰B仅宽度｜红C宽度+偏移｜细棕线C带缘及中心；横轴相对原中心', font=font(20), fill='#44483e')
    cols = ('fixed_y', 'width_y', 'offset_y')
    colors = ('#337d9e', '#8b8c80', '#b14c31')
    lo = min(profile[c].min() for c in cols) - 5
    hi = max(profile[c].max() for c in cols) + 5
    for i, z in enumerate(np.unique(profile['z'])):
        row = profile[profile['z'] == z]
        top = 145 + i * 350
        helper.line_chart(pd, (100, top, 1530, top + 235), row['x'] + 140,
                          [(row[c], color, 2) for c, color in zip(cols, colors)], lo, hi,
                          f'Z={z:.0f}｜宽{row["width"][0]:.1f}块｜偏移{row["offset"][0]:+.1f}块')
        for dx in (row['offset'][0] - row['width'][0] / 2, row['offset'][0], row['offset'][0] + row['width'][0] / 2):
            px = 100 + (dx + 600) / 1200 * 1430
            pd.line((px, top, px, top + 235), fill='#a98553', width=1)
    row = profile[profile['z'] == 137]
    gradients = [np.gradient(row[c], .5) for c in cols]
    bound = max(max(abs(g).max() for g in gradients), .001) * 1.05
    helper.line_chart(pd, (100, 1220, 1530, 1430), row['x'] + 140,
                      [(g, color, 2) for g, color in zip(gradients, colors)], -bound, bound, 'Z=137 dY/dX；所有尖峰原样保留')
    chart.save(dest / 'height-profiles-abc.png')
    progress = np.fromfile(root / 'progress.f64be', dtype='>f8').reshape(601, 1201)
    diag = Image.new('RGB', (1281, 1450), '#f4f3ed')
    dd = ImageDraw.Draw(diag)
    dd.text((40, 20), title + '｜C灰度山影 / 参数等进度线', font=font(28), fill='#25261f')
    diag.paste(width_helper.hillshade(fields[2]), (40, 80))
    diag.paste(Image.fromarray(np.uint8(np.repeat((progress * 255)[..., None], 3, axis=2))), (40, 780))
    dd.text((40, 735), '等进度线10/25/50/75/90%；50%不再限定为直线', font=font(23), fill='#25261f')
    for level in (.1, .25, .5, .75, .9):
        xs = [np.interp(level, progress[j], np.arange(1201)) for j in range(601)]
        dd.line([(40 + x, 780 + j) for j, x in enumerate(xs)], fill='#c77945', width=1)
    diag.save(dest / 'offset-progress.png')
    curve = Image.new('RGB', (1100, 650), '#f4f3ed')
    gd = ImageDraw.Draw(curve)
    gd.text((60, 20), title + '｜交接偏移沿Z变化', font=font(28), fill='#25261f')
    for v in (-160, -80, 0, 80, 160):
        yy = 540 - (v + 160) / 320 * 420
        gd.line((100, yy, 1040, yy), fill='#d8d7ce')
        gd.text((35, yy - 10), str(v), font=font(18), fill='#44483e')
    gd.line([(100 + j / 600 * 940, 540 - (v + 160) / 320 * 420) for j, v in enumerate(guide['offset'])], fill='#b14c31', width=3)
    gd.text((100, 575), f'Z=-163→437；偏移{guide["offset"].min():+.1f}～{guide["offset"].max():+.1f}块；不是地形平移', font=font(21), fill='#44483e')
    curve.save(dest / 'offsets.png')
    metrics = {'width_min': float(guide['width'].min()), 'width_max': float(guide['width'].max()),
               'offset_min': float(guide['offset'].min()), 'offset_max': float(guide['offset'].max()),
               'max_y_change_C_vs_B': float(abs(fields[2] - fields[1]).max())}
    (dest / 'image-metrics.json').write_text(json.dumps(metrics, indent=2), encoding='utf-8')
    for name in ('profiles.csv', 'guides.csv', 'convergence.csv', 'metrics.txt'):
        shutil.copyfile(root / name, dest / name)
    for path in dest.glob('*.png'):
        with Image.open(path) as img:
            img.verify()
    print(title, metrics)


def main():
    root, dest = map(Path, sys.argv[1:3])
    width_helper, helper = helpers()
    for key, title in {'plain_mountains': '平原 → 山地', 'mountains_badlands': '山地 → 劣地'}.items():
        render_pair(root / key, dest / key, title, width_helper, helper)
    for name in ('geometry.json', 'config-input.toml'):
        shutil.copyfile(root / name, dest / name)


if __name__ == '__main__':
    main()
