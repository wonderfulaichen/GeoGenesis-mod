#!/usr/bin/env python3
"""Verify exact EDT independently and render frozen-old vs repaired-world terrain.

python -B tools/render-distance-fix.py <build/distance-fix-root> <dest>
"""
import hashlib
import importlib.util
import json
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont
from scipy.ndimage import distance_transform_edt, map_coordinates


def array(path, n):
    v = np.fromfile(path, dtype='>f8').astype(float).reshape(n, n)
    assert np.isfinite(v).all(), path
    return v


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def verify(root):
    before, after = root / 'before-evidence', root / 'after'
    meta = json.loads((after / 'grid-meta.json').read_text())
    n, cell = meta['n'], meta['cell']
    g = array(after / 'base-grid.f64be', n)
    assert sha(before / 'base-grid.f64be') == sha(after / 'base-grid.f64be')
    assert sha(before / 'Y-base.f64be') == sha(after / 'Y-base.f64be')
    assert sha(before / 'band.f64be') == sha(after / 'band.f64be')
    assert sha(root / 'before/Y-world.f64be') == sha(before / 'Y-world.f64be')
    refs = [distance_transform_edt(g > 0), distance_transform_edt(g <= 0)]
    errors = []
    for name, ref in zip(['to-sea', 'to-land'], refs):
        error = np.abs(array(after / (name + '.f64be'), n) - ref).max()
        assert error < 1e-9, (name, error)
        errors.append(float(error))
    size = 1401
    zz, xx = np.mgrid[:size, :size]
    coords = np.array([(-3365.5 + zz * 5 - meta['z0']) / cell,
                       (-3642.5 + xx * 5 - meta['x0']) / cell])
    land = map_coordinates(g, coords, order=1) > 0
    true_d = np.where(land, map_coordinates(refs[0], coords, order=1),
                      -map_coordinates(refs[1], coords, order=1)) * cell
    d = array(after / 'distance.f64be', size)
    assert np.abs(true_d - d).max() < 1e-9
    a = array(before / 'Y-world.f64be', size)
    b = array(after / 'Y-world.f64be', size)
    base = array(after / 'Y-base.f64be', size)
    widths = array(after / 'band.f64be', size)
    outside = np.abs(true_d) >= widths
    wrong = (b.view('u8') != base.view('u8')) & outside
    assert outside.any() and (~outside).any() and not wrong.any()
    # No unauthorized source/parameter changes: only the distance implementation changed.
    source = Path('forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/terrain/candidate')
    frozen = json.loads((root / 'source-before.json').read_text())
    unchanged = []
    for name, expected in frozen.items():
        if name != 'CoastalAssembler.java':
            assert sha(source / name) == expected, name
            unchanged.append(name)
    metrics = {
        'reference': 'SciPy exact Euclidean EDT on both classes of unchanged 8-block grid',
        'grid_max_error_cells': errors,
        'pixel_max_error_blocks': float(np.abs(true_d - d).max()),
        'outside_pixels': int(outside.sum()), 'outside_base_height_differences': int(wrong.sum()),
        'old_coast_pixels': int((np.abs(array(before / 'distance.f64be', size)) < widths).sum()),
        'new_coast_pixels': int((~outside).sum()),
        'water_A_pct': float((a < 63).mean() * 100), 'water_B_pct': float((b < 63).mean() * 100),
        'height_A_range': [float(a.min()), float(a.max())],
        'height_B_range': [float(b.min()), float(b.max())],
        'before_sha256': sha(before / 'Y-world.f64be'), 'after_sha256': sha(after / 'Y-world.f64be'),
        'unchanged_source_files': unchanged,
        'base_grid_unchanged': True, 'base_height_unchanged': True, 'band_unchanged': True,
        'distance_definition': 'nearest opposite-class grid node; continuous coastline has grid/interpolation error',
    }
    return a, b, base, widths, d, metrics


def font(size):
    return ImageFont.truetype('C:/Windows/Fonts/msyh.ttc', size)


def render(root, dest, a, b, base, widths, distance, metrics):
    spec = importlib.util.spec_from_file_location('world_renderer', Path(__file__).with_name('render-unified-world.py'))
    mod = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(mod)
    dest.mkdir(parents=True, exist_ok=True)
    ims = [mod.render_better(h, 5, 63) for h in (a, b)]
    for tag, im in zip(['before', 'after'], ims):
        im.save(dest / ('native-' + tag + '.png'))
    canvas = Image.new('RGB', (2918, 1690), '#f4f3ed')
    dr = ImageDraw.Draw(canvas)
    dr.text((40, 18), '距离算法修复 A/B｜左：修复前　右：精确欧氏距离修复后', font=font(32), fill='#25261f')
    dr.text((40, 67), '相同 BOTH 选型、seed=9139912035078620160、1401²、步长5块、中心(-140,137)；只变距离算法。', font=font(21), fill='#55594c')
    dr.text((40, 102), '每幅原生1401×1401，无缩放；覆盖7005×7005块。带宽仍80～116块，配方/分布/高度参数未动。', font=font(21), fill='#55594c')
    for k, (im, tag, value) in enumerate(zip(ims, ['A 修复前', 'B 距离修复后'],
                                            [metrics['water_A_pct'], metrics['water_B_pct']])):
        x = 40 + k * 1437
        dr.text((x, 150), '%s｜水面 %.3f%%' % (tag, value), font=font(24), fill='#25261f')
        canvas.paste(im, (x, 195))
    dr.text((40, 1620), '海岸带覆盖：%d → %d px；修复后真实网格距离判定的带外点与原始类型高度逐位一致：%d / %d。'
            % (metrics['old_coast_pixels'], metrics['new_coast_pixels'], metrics['outside_pixels'], metrics['outside_pixels']),
            font=font(20), fill='#55594c')
    canvas.save(dest / 'pair-full.png')
    # Fixed rows, shared axes and no independent vertical normalization.
    canvas = Image.new('RGB', (1550, 1700), '#f4f3ed'); dr = ImageDraw.Draw(canvas)
    dr.text((40, 18), '同位置横剖面｜棕：修复前 A　蓝：修复后 B　灰：未加工的类型高度', font=font(26), fill='#25261f')
    dr.text((40, 65), '六条固定横线；共用Y=-10..290纵轴。横轴为世界x坐标，蓝色水平线为海平面Y=63。', font=font(20), fill='#55594c')
    rows = [116, 350, 583, 816, 1050, 1283]
    for k, j in enumerate(rows):
        y0 = 135 + k * 245
        dr.text((40, y0), 'z=%.1f' % (-3365.5 + j * 5), font=font(19), fill='#25261f')
        bx, by, bw, bh = 130, y0 + 26, 1360, 180
        axes(dr, bx, by, bw, bh, -10, 290)
        for values, col in [(base[j], '#aaaaaa'), (a[j], '#92652f'), (b[j], '#236ab0')]:
            points = [(bx + i / 1400 * bw, by + bh - (v + 10) / 300 * bh) for i, v in enumerate(values)]
            dr.line(points, fill=col, width=2)
        for i in [0, 400, 800, 1200, 1400]:
            dr.text((bx + i / 1400 * bw - 25, by + bh + 4), '%.0f' % (-3642.5 + i * 5), font=font(13), fill='#55594c')
    canvas.save(dest / 'profiles-full.png')
    local_a = np.genfromtxt(root / 'before-evidence/local-profile.csv', delimiter=',', names=True)
    local_b = np.genfromtxt(root / 'after/local-profile.csv', delimiter=',', names=True)
    canvas = Image.new('RGB', (1550, 710), '#f4f3ed'); dr = ImageDraw.Draw(canvas)
    dr.text((40, 18), '原错误最大点局部剖面｜z=-1305.5｜1块采样，不改变距离网格精度', font=font(27), fill='#25261f')
    dr.text((40, 65), 'x=2522.5：A Y=77.82 → B Y=243.08；最近海网格点约1057.65块，已恢复为纯内部地形。', font=font(20), fill='#55594c')
    bx, by, bw, bh = 100, 130, 1380, 430
    axes(dr, bx, by, bw, bh, 50, 280)
    for values, col in [(local_b['Y_base'], '#aaaaaa'), (local_a['Y'], '#92652f'), (local_b['Y'], '#236ab0')]:
        dr.line([(bx + i / 480 * bw, by + bh - (v - 50) / 230 * bh) for i, v in enumerate(values)], fill=col, width=3)
    for i in [0, 120, 240, 360, 480]:
        dr.text((bx + i / 480 * bw - 28, by + bh + 8), str(local_b['x'][i]), font=font(16), fill='#55594c')
    dr.text((40, 625), '棕 A　蓝 B（与灰色内部类型高度重合）；没有增幅、没有调海岸封顶、没有改变分布。', font=font(21), fill='#25261f')
    canvas.save(dest / 'profile-recovered-inland.png')
    # Exact coast-mask visualization, no photometric differences can hide the footprint.
    rgb = np.full((1401, 1401, 3), [225, 227, 215], dtype=np.uint8)
    rgb[base < 63] = [45, 75, 110]
    rgb[np.abs(distance) < widths] = [225, 172, 62]
    Image.fromarray(rgb).save(dest / 'coast-footprint.png')


def axes(dr, x, y, w, h, lo, hi):
    dr.rectangle((x, y, x + w, y + h), outline='#b8b7aa')
    for value in [0, 63, 100, 150, 200, 250]:
        if lo <= value <= hi:
            py = y + h - (value - lo) / (hi - lo) * h
            dr.line((x, py, x + w, py), fill='#9cbad3' if value == 63 else '#d9d8ce')
            dr.text((x - 48, py - 9), str(value), font=font(14), fill='#55594c')


if __name__ == '__main__':
    root, dest = map(Path, sys.argv[1:])
    a, b, base, widths, d, metrics = verify(root)
    render(root, dest, a, b, base, widths, d, metrics)
    (dest / 'verification.json').write_text(json.dumps(metrics, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print(json.dumps(metrics, ensure_ascii=False, indent=2))
    print('DISTANCE_FIX_RENDER_AND_REFERENCE_PASS')
