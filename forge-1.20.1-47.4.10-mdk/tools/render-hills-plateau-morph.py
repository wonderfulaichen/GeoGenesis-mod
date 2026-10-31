"""独立双族受控原型：原生分辨率阶段图、直线边界过渡和数值剖面。"""
import importlib.util
import json
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

FONT = 'C:/Windows/Fonts/msyh.ttc'


def font(size):
    return ImageFont.truetype(FONT, size)


def load_renderer():
    path = Path(__file__).with_name('render-islands-big.py')
    spec = importlib.util.spec_from_file_location('existing_terrain_renderer', path)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module.render


def line_chart(draw, rect, x, series, ymin, ymax, caption):
    left, top, right, bottom = rect
    for val in np.linspace(ymin, ymax, 5):
        py = bottom - (val - ymin) / (ymax - ymin) * (bottom - top)
        draw.line((left, py, right, py), fill='#d8d7ce')
        draw.text((left - 65, py - 10), f'{val:.1f}', font=font(16), fill='#44483e')
    for boundary in (-120, 120):
        px = left + (boundary - x[0]) / (x[-1] - x[0]) * (right - left)
        draw.line((px, top, px, bottom), fill='#8c8279', width=2)
    for values, color, width in series:
        points = [(left + (v - x[0]) / (x[-1] - x[0]) * (right - left),
                   bottom - (y - ymin) / (ymax - ymin) * (bottom - top)) for v, y in zip(x, values)]
        draw.line(points, fill=color, width=width)
    for tick in (-600, -300, -120, 0, 120, 300, 600):
        px = left + (tick - x[0]) / (x[-1] - x[0]) * (right - left)
        draw.text((px - 20, bottom + 6), str(tick), font=font(16), fill='#44483e')
    draw.text((left, top - 32), caption, font=font(20), fill='#25261f')


def render_correction(root, dest, old_root, render, geo):
    values = np.genfromtxt(root / 'terrace-transfer.csv', delimiter=',', names=True)
    canvas = Image.new('RGB', (1600, 850), '#f4f3ed')
    d = ImageDraw.Draw(canvas)
    d.text((90, 15), 'Terrace 0.75 换档核验（直接输入扫描，不是世界坐标剖面）', font=font(28), fill='#25261f')
    d.text((90, 60), '红：旧值域 0–1.05   蓝：修正值域 0–0.9；下图换算高度不含表面细节。', font=font(20), fill='#44483e')
    for top, columns, limits, title in (
            (145, ('old_terrace', 'corrected_terrace'), (0.55, 1.1), 'Terrace 输出'),
            (515, ('old_y', 'corrected_y'),
             (min(values['old_y'].min(), values['corrected_y'].min()) - 2,
              max(values['old_y'].max(), values['corrected_y'].max()) + 2), '对应高度 Y（格）')):
        left, right, bottom = 100, 1530, top + 235
        lo, hi = limits
        d.text((left, top - 32), title, font=font(22), fill='#25261f')
        for v in np.linspace(lo, hi, 5):
            y = bottom - (v - lo) / (hi - lo) * (bottom - top)
            d.line((left, y, right, y), fill='#d8d7ce')
            d.text((left - 80, y - 10), f'{v:.3f}', font=font(17), fill='#44483e')
        for v in (0.65, 0.70, 0.75, 0.80, 0.85):
            x = left + (v - 0.65) / 0.20 * (right - left)
            d.line((x, top, x, bottom), fill='#b8b6ad')
            d.text((x - 20, bottom + 10), f'{v:.2f}', font=font(18), fill='#44483e')
        for col, color in zip(columns, ('#b14c31', '#337d9e')):
            pts = [(left + (v - 0.65) / 0.20 * (right - left),
                    bottom - (y - lo) / (hi - lo) * (bottom - top))
                   for v, y in zip(values['input'], values[col])]
            d.line(pts, fill=color, width=3)
    canvas.save(dest / 'terrace-075-check.png')
    w, h = geo['width'], geo['height']
    canvas = Image.new('RGB', (w + 80, 2 * h + 260), '#f4f3ed')
    d = ImageDraw.Draw(canvas)
    d.text((40, 18), '仅修正独立原型 Terrace 值域 · 同窗口前后对照', font=font(28), fill='#25261f')
    for directory, y, label in ((old_root, 130, '修正前：0–1.05'), (root, h + 230, '修正后：参考声明值域 0–0.9')):
        field = np.fromfile(directory / 'spatial.f64be', dtype='>f8').reshape(h, w)
        canvas.paste(render(field, 1), (40, y))
        for bx in (w // 2 - 120, w // 2 + 120):
            d.line((40 + bx, y, 40 + bx, y + h), fill='#e2c58d')
        d.text((40, y - 32), label + '｜1 像素 = 1 块，线间 240 块', font=font(22), fill='#25261f')
    canvas.save(dest / 'correction-before-after.png')


def main():
    root = Path(sys.argv[1])
    dest = Path(sys.argv[2])
    dest.mkdir(parents=True, exist_ok=True)
    geo = json.loads((root / 'geometry.json').read_text(encoding='utf-8'))
    render = load_renderer()
    n, step = geo['stageSize'], geo['stageStep']
    stages = [np.fromfile(root / f'stage-{i}.f64be', dtype='>f8').reshape(n, n) for i in range(5)]
    if not all(np.isfinite(h).all() for h in stages):
        raise ValueError('nonfinite stage')
    panel = Image.new('RGB', (60 + 5 * (n + 20), n + 220), '#f4f3ed')
    d = ImageDraw.Draw(panel)
    d.text((30, 18), '丘陵 → 台地 · 五阶段（同坐标、同色带、未缩放）', font=font(32), fill='#25261f')
    d.text((30, 65), '只过渡生成链参数与台面算子强度；不接细胞、不接海陆、不改高度包络。1 像素 = 2 块。',
           font=font(22), fill='#55594c')
    stats = []
    for i, h in enumerate(stages):
        x = 30 + i * (n + 20)
        image = render(h, step)
        image.save(dest / f'stage-{i}.png')
        panel.paste(image, (x, 110))
        slope = np.hypot(*np.gradient(h, step))
        row = {'position': i / 4, 'y_min': float(h.min()), 'y_max': float(h.max()),
               'y_p50': float(np.median(h)), 'slope_p50': float(np.median(slope))}
        stats.append(row)
        d.text((x, n + 120), f'{i * 25}%  Y {h.min():.1f}–{h.max():.1f}  中位 {np.median(h):.1f}',
               font=font(21), fill='#25261f')
        d.text((x, n + 154), f'坡度中位 {np.median(slope):.3f}', font=font(19), fill='#55594c')
    panel.save(dest / 'stages-panel.png')
    w, h = geo['width'], geo['height']
    morph = np.fromfile(root / 'spatial.f64be', dtype='>f8').reshape(h, w)
    lerp = np.fromfile(root / 'finished-lerp.f64be', dtype='>f8').reshape(h, w)
    render(morph, 1).save(dest / 'spatial-native.png')
    panel = Image.new('RGB', (w + 80, h * 2 + 260), '#f4f3ed')
    d = ImageDraw.Draw(panel)
    d.text((40, 18), '受控直线交界：左丘陵，右台地', font=font(30), fill='#25261f')
    d.text((40, 62), '两线之间为 240 块过渡带｜1 像素 = 1 块｜中心 (-140,137)', font=font(20), fill='#55594c')
    for a, y, label in ((morph, 130, '本原型：塑形前信号与算子参数渐变'),
                        (lerp, h + 230, '仅作诊断对照：两份最终高度的交叉混合')):
        panel.paste(render(a, 1), (40, y))
        for bx in (w // 2 - 120, w // 2 + 120):
            d.line((40 + bx, y, 40 + bx, y + h), fill='#e2c58d', width=1)
        d.text((40, y - 32), label, font=font(23), fill='#25261f')
    panel.save(dest / 'spatial-comparison.png')
    profiles = np.genfromtxt(root / 'profiles.csv', delimiter=',', names=True)
    zs = np.unique(profiles['z'])
    canvas = Image.new('RGB', (1600, 1520), '#f4f3ed')
    d = ImageDraw.Draw(canvas)
    d.text((90, 18), '高度剖面（原始数值，不平滑）', font=font(32), fill='#25261f')
    d.text((90, 65), '红：修正后原型   蓝：成品高度混合   浅灰：丘陵 / 修正台地端点   竖线：过渡带边界',
           font=font(21), fill='#44483e')
    lo = float(min(profiles['morph_y'].min(), profiles['lerp_y'].min(), profiles['hills_y'].min(),
                   profiles['plateau_y'].min())) - 5
    hi = float(max(profiles['morph_y'].max(), profiles['lerp_y'].max(), profiles['hills_y'].max(),
                   profiles['plateau_y'].max())) + 5
    for i, z in enumerate(zs):
        p = profiles[profiles['z'] == z]
        x = p['x'] - geo['center'][0]
        top = 145 + i * 350
        series = [(p['hills_y'], '#c2c2b8', 1), (p['plateau_y'], '#b8b6ad', 1),
                  (p['lerp_y'], '#337d9e', 2), (p['morph_y'], '#b14c31', 2)]
        line_chart(d, (100, top, 1530, top + 235), x, series, lo, hi, f'Z={z:.0f}｜Y（格）')
    p = profiles[profiles['z'] == zs[1]]
    slope_m, slope_l = np.gradient(p['morph_y'], 0.5), np.gradient(p['lerp_y'], 0.5)
    bound = float(max(abs(slope_m).max(), abs(slope_l).max())) * 1.05
    line_chart(d, (100, 1220, 1530, 1430), p['x'] - geo['center'][0],
               [(slope_l, '#337d9e', 2), (slope_m, '#b14c31', 2)], -bound, bound,
               '中心剖面梯度 dY/dX（格/格）；尖峰保留，不当成已解决')
    canvas.save(dest / 'height-profiles.png')
    render_correction(root, dest, Path(sys.argv[3]), render, geo)
    delta = morph - lerp
    result = {'stages': stats, 'max_abs_difference_vs_finished_lerp': float(abs(delta).max()),
              'mean_abs_difference_vs_finished_lerp': float(abs(delta).mean())}
    (dest / 'image-metrics.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
    (dest / 'verification.txt').write_text((root / 'metrics.txt').read_text(encoding='utf-8'), encoding='utf-8')
    print(json.dumps(result, ensure_ascii=False, indent=2))
    for p in sorted(dest.glob('*.png')):
        with Image.open(p) as image:
            image.verify()
        print(p)


if __name__ == '__main__':
    main()
