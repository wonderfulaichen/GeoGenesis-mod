"""B1自适应宽度：不改配方与中心的A/B原生图、剖面、进度和带宽。"""
import importlib.util
import json
import shutil
import sys
from pathlib import Path
import numpy as np
from PIL import Image, ImageDraw


def helpers():
    spec = importlib.util.spec_from_file_location('morph_helpers', Path(__file__).with_name('render-hills-plateau-morph.py'))
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def hillshade(a):
    gz, gx = np.gradient(a)
    light = np.array([-.5, -.35, 1.])
    light /= np.linalg.norm(light)
    lit = np.clip((-gx * light[0] - gz * light[1] + light[2]) / np.sqrt(1 + gx * gx + gz * gz), 0, 1)
    return Image.fromarray(np.uint8(np.repeat((40 + 205 * lit)[..., None], 3, axis=2)))


def pair(root, dest, title, helper):
    dest.mkdir(parents=True, exist_ok=True)
    font, render = helper.font, helper.load_renderer()
    w, h = 1201, 601
    a = np.fromfile(root / 'fixed.f64be', dtype='>f8').reshape(h, w)
    b = np.fromfile(root / 'adaptive.f64be', dtype='>f8').reshape(h, w)
    p = np.fromfile(root / 'progress.f64be', dtype='>f8').reshape(h, w)
    widths = np.genfromtxt(root / 'widths.csv', delimiter=',', names=True)
    assert np.isfinite(a).all() and np.isfinite(b).all() and np.isfinite(p).all()
    for name, arr in (('fixed-native', a), ('adaptive-native', b)):
        render(arr, 1).save(dest / (name + '.png'))
    canvas = Image.new('RGB', (w + 80, 2 * h + 260), '#f4f3ed')
    draw = ImageDraw.Draw(canvas)
    draw.text((40, 15), title + ' · 仅自适应宽度 A/B', font=font(30), fill='#25261f')
    draw.text((40, 60), '中心不偏移｜配方不变｜1 像素 = 1 块｜细线为实际带缘（不是地形刻线）', font=font(20), fill='#55594c')
    for arr, y, label, adaptive in ((a, 130, 'A：固定 240 块', False),
                                    (b, h + 230, f'B：自适应 {widths["width"].min():.1f}–{widths["width"].max():.1f} 块', True)):
        canvas.paste(render(arr, 1), (40, y))
        for side in (-1, 1):
            pts = [(40 + 600 + side * (widths['width'][j] if adaptive else 240) / 2, y + j) for j in range(h)]
            draw.line(pts, fill='#e2c58d', width=1)
        draw.text((40, y - 32), label, font=font(23), fill='#25261f')
    canvas.save(dest / 'transition-ab.png')
    diag = Image.new('RGB', (w + 80, h * 3 + 300), '#f4f3ed')
    dd = ImageDraw.Draw(diag)
    for i, (image, label) in enumerate(((hillshade(a), 'A：纯灰度山影'), (hillshade(b), 'B：纯灰度山影'),
                                       (Image.fromarray(np.uint8(np.repeat((p * 255)[..., None], 3, axis=2))), 'B：smoothstep 后进度；等进度线 10/25/50/75/90%'))):
        y = 80 + i * (h + 80)
        diag.paste(image, (40, y))
        dd.text((40, y - 35), title + '｜' + label, font=font(23), fill='#25261f')
        if i == 2:
            for level in (.1, .25, .5, .75, .9):
                xs = [np.interp(level, p[j], np.arange(w)) for j in range(h)]
                dd.line([(40 + x, y + j) for j, x in enumerate(xs)], fill='#c77945', width=1)
    diag.save(dest / 'hillshade-progress.png')
    profile = np.genfromtxt(root / 'profiles.csv', delimiter=',', names=True)
    canvas = Image.new('RGB', (1600, 1520), '#f4f3ed')
    draw = ImageDraw.Draw(canvas)
    draw.text((90, 15), title + ' · 固定宽度 / 自适应宽度高度剖面', font=font(29), fill='#25261f')
    draw.text((90, 65), '蓝 A固定240｜红 B自适应｜灰线 A带缘｜棕线 B带缘｜高度与梯度均未平滑', font=font(21), fill='#44483e')
    lo = min(profile['fixed_y'].min(), profile['adaptive_y'].min()) - 5
    hi = max(profile['fixed_y'].max(), profile['adaptive_y'].max()) + 5
    zs = np.unique(profile['z'])
    for i, z in enumerate(zs):
        row = profile[profile['z'] == z]
        top = 145 + i * 350
        helper.line_chart(draw, (100, top, 1530, top + 235), row['x'] + 140,
                          [(row['fixed_y'], '#337d9e', 2), (row['adaptive_y'], '#b14c31', 2)], lo, hi,
                          f'Z={z:.0f}｜B总宽 {row["width"][0]:.1f} 块｜Y（格）')
        for side in (-1, 1):
            x = 100 + (side * row['width'][0] / 2 + 600) / 1200 * 1430
            draw.line((x, top, x, top + 235), fill='#a98553', width=1)
    row = profile[profile['z'] == 137]
    ga, gb = np.gradient(row['fixed_y'], .5), np.gradient(row['adaptive_y'], .5)
    bound = max(abs(ga).max(), abs(gb).max(), .001) * 1.05
    helper.line_chart(draw, (100, 1220, 1530, 1430), row['x'] + 140,
                      [(ga, '#337d9e', 2), (gb, '#b14c31', 2)], -bound, bound, 'Z=137 中心剖面 dY/dX；峰值原样保留')
    canvas.save(dest / 'height-profiles-ab.png')
    graph = Image.new('RGB', (1100, 520), '#f4f3ed')
    d = ImageDraw.Draw(graph)
    d.text((60, 20), title + ' · 宽度沿 Z 变化；中心始终 X=-140', font=font(26), fill='#25261f')
    for v in (240, 360, 480, 600, 720):
        yy = 430 - (v - 240) / 480 * 320
        d.line((100, yy, 1040, yy), fill='#d8d7ce')
        d.text((35, yy - 10), str(v), font=font(18), fill='#44483e')
    d.line([(100 + j / 600 * 940, 430 - (v - 240) / 480 * 320) for j, v in enumerate(widths['width'])], fill='#b14c31', width=3)
    d.text((100, 455), f'Z {widths["z"][0]:.0f} → {widths["z"][-1]:.0f}；无交接偏移', font=font(20), fill='#44483e')
    graph.save(dest / 'widths.png')
    # 同窗口未缩放局部特写：中心横向480块、纵向360块。
    close = Image.new('RGB', (1040, 460), '#f4f3ed')
    d = ImageDraw.Draw(close)
    for image, x, label in ((render(a, 1), 30, 'A 固定240'), (render(b, 1), 530, 'B 自适应宽度')):
        close.paste(image.crop((360, 120, 840, 480)), (x, 70))
        d.text((x, 20), title + '｜' + label, font=font(24), fill='#25261f')
    close.save(dest / 'closeup-ab.png')
    metrics = {'width_min': float(widths['width'].min()), 'width_max': float(widths['width'].max()),
               'width_median': float(np.median(widths['width'])), 'max_y_change': float(abs(b-a).max())}
    (dest / 'image-metrics.json').write_text(json.dumps(metrics, indent=2), encoding='utf-8')
    for name in ('profiles.csv', 'widths.csv', 'convergence.csv', 'metrics.txt'):
        shutil.copyfile(root / name, dest / name)
    for path in dest.glob('*.png'):
        with Image.open(path) as image:
            image.verify()
    print(title, metrics)


def main():
    root, dest = map(Path, sys.argv[1:3])
    helper = helpers()
    for key, title in {'plain_mountains': '平原 → 山地', 'mountains_badlands': '山地 → 劣地'}.items():
        pair(root / key, dest / key, title, helper)
    for name in ('geometry.json', 'config-input.toml'):
        shutil.copyfile(root / name, dest / name)


if __name__ == '__main__':
    main()
