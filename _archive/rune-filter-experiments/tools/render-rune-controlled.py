#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""Read-only controlled-array renderer. Run from repository root with python -B.
Only creates overview.png, controls.png, profiles.png and render-metadata.json
in the controlled experiment directory; refuses to overwrite any existing output.
"""
import argparse
import hashlib
import json
import sys
from pathlib import Path

import numpy as np
import matplotlib
matplotlib.use('Agg')
import matplotlib.pyplot as plt
from matplotlib import font_manager
from matplotlib.colors import LinearSegmentedColormap, Normalize
from matplotlib.cm import ScalarMappable
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
MDK = ROOT / 'forge-1.20.1-47.4.10-mdk'
DEFAULT_OUT = MDK / 'build/rune-controlled-20261103-c'
Y_FACTOR = 236.44 * 1.05
HS = 2.0
LIMITS = (-125.0, 125.0)
LIGHT = np.array([-0.5, 1.0, -0.35])  # x, up, z; fixed for every panel
LIGHT /= np.linalg.norm(LIGHT)
NORM = Normalize(*LIMITS, clip=True)
CMAP = LinearSegmentedColormap.from_list(
    'fixed_relative_height', ['#344860', '#7495a6', '#c4cdc3', '#d5bf90', '#f4eddb'])
OUTPUTS = ('overview.png', 'controls.png', 'profiles.png', 'render-metadata.json')
OVERVIEW = [
    ('base-rune', '原始 Rune base｜无滤镜'),
    ('rune-author', '原作者结果｜原始 float / hash'),
    ('m1-compressed-author', '压缩 M1｜作者参数 / double / SplitMix'),
    ('base-m1-native', 'M1 native base｜无滤镜'),
    ('m1-native-candidate-1830', 'M1 native candidate1830｜局部相位'),
    ('m1-native-candidate-610', 'M1 native candidate610｜绝对世界相位'),
]
CONTROLS = [
    ('rune-double-author', '仅换基底 A｜Rune / 作者参数'),
    ('m1-compressed-author', '仅换基底 B｜压缩 M1 / 作者参数'),
    ('m1-compressed-author', '仅换参数 A｜压缩 M1 / 作者参数'),
    ('m1-compressed-candidate', '仅换参数 B｜压缩 M1 / candidate 参数'),
    ('m1-native-candidate-1830', '仅换尺度 A｜native / L1830 / 局部相位'),
    ('m1-native-candidate-610-local', '仅换尺度 B｜native / L610 / 同一局部相位'),
]


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def configure_font():
    for name in ('msyh.ttc', 'simhei.ttf', 'simsun.ttc'):
        path = Path('C:/Windows/Fonts') / name
        if path.exists():
            font_manager.fontManager.addfont(str(path))
            family = font_manager.FontProperties(fname=str(path)).get_name()
            plt.rcParams.update({'font.family': family, 'axes.unicode_minus': False,
                                 'font.size': 10, 'axes.titlesize': 12})
            return str(path), family
    raise RuntimeError('No installed Chinese font found; refusing missing-glyph plots')


def verify_and_load(out):
    """Verify all manifest hashes and statistics before producing any images."""
    manifest = json.loads((out / 'manifest.json').read_text(encoding='utf-8'))
    stats = json.loads((out / 'stats-controlled.json').read_text(encoding='utf-8'))
    findings = (out / 'causal-findings.txt').read_text(encoding='utf-8')
    if not findings.strip() or not manifest['grid']['cellCenters']:
        raise ValueError('Missing findings or unsupported sample placement')
    checked, arrays = {}, {}
    n = manifest['size']
    for key in ('arrayHashes', 'sourceHashes', 'referenceHashes'):
        for name, expected in manifest[key].items():
            path = (out if key == 'arrayHashes' else MDK) / name.replace('\\', '/')
            actual = sha(path)
            if actual != expected:
                raise ValueError('SHA256 mismatch: ' + str(path))
            checked[str(path)] = actual
            if key != 'arrayHashes':
                continue
            if path.stat().st_size != n * n * 8:
                raise ValueError('Unexpected array size: ' + name)
            a = np.fromfile(path, dtype='>f8').astype(float).reshape(n, n)
            if not np.isfinite(a).all():
                raise ValueError('Nonfinite data: ' + name)
            level = stats['levels'][path.stem]
            actual_level = [a.mean(), a.std(), a.min(), a.max()]
            expected_level = [level['mean'], level['std'], level['q']['min'], level['q']['max']]
            if not np.allclose(actual_level, expected_level, rtol=1e-12, atol=1e-14):
                raise ValueError('Statistics mismatch: ' + name)
            arrays[path.stem] = a
    copy = manifest['offlineHashCopy']
    if sha(Path(copy['path'])) != copy['sha256']:
        raise ValueError('Offline hash variant mismatch')
    checked[copy['path']] = copy['sha256']
    for name in ('manifest.json', 'causal-findings.txt', 'stats-controlled.json'):
        checked[str(out / name)] = sha(out / name)
    for pair in stats['differences']:
        delta = arrays[pair['b']] - arrays[pair['a']]
        if not np.isclose(delta.std(), pair['centeredRms'], rtol=1e-12, atol=1e-14):
            raise ValueError('Pair statistics mismatch')
    return manifest, stats, arrays, checked


def rgb_height(a, step_blocks):
    height = Y_FACTOR * a
    gz, gx = np.gradient(height, step_blocks, step_blocks)
    dot = (-gx * LIGHT[0] + LIGHT[1] - gz * LIGHT[2]) / np.sqrt(1 + gx*gx + gz*gz)
    # Fixed Lambert grayscale illumination; no percentile/min-max shading stretch.
    shade = 0.30 + 0.70 * np.clip(dot, 0, 1)
    return CMAP(NORM(height))[..., :3] * shade[..., None]


def map_panel(ax, name, title, arrays, span, step):
    a = arrays[name]
    ax.imshow(rgb_height(a, step), origin='upper', extent=(0, span, span, 0),
              interpolation='nearest', aspect='equal')
    ax.set(xlim=(0, span), ylim=(span, 0), xlabel='局部 x（格）', ylabel='局部 z（格）')
    ax.set_xticks([0, 1000, 2000, 3000, span])
    ax.set_yticks([0, 1000, 2000, 3000, span])
    ax.tick_params(labelsize=9)
    ax.set_title(title + '\n' + name, loc='left', fontsize=11, pad=9)
    x, z, length = 0.07*span, 0.91*span, 1000
    ax.plot([x, x+length], [z, z], color='white', lw=5, solid_capstyle='butt')
    ax.plot([x, x+length], [z, z], color='#202020', lw=2, solid_capstyle='butt')
    ax.text(x+length/2, z-0.025*span, '1000 格', ha='center', fontsize=9,
            bbox={'facecolor': 'white', 'alpha': .85, 'edgecolor': 'none', 'pad': 1})
    ax.text(.98, .02, 'ΔY %.1f ～ %.1f' % (a.min()*Y_FACTOR, a.max()*Y_FACTOR),
            transform=ax.transAxes, ha='right', va='bottom', fontsize=8,
            bbox={'facecolor': 'white', 'alpha': .85, 'edgecolor': 'none', 'pad': 2})


def caption(n, step):
    return (f'原生 {n}×{n} 单元中心采样；最近邻显示，无平滑 / 无重采样；步长 {step:.4f} 格\n'
            '水平：格 = wu × 2；垂直：ΔY = 236.44 × 1.05 × h；统一参考面 h=0，不是真实游戏高度\n'
            '主图坡度不夸张（×1）；固定色限 [-125, 125]；固定灰度 hillshade × 固定色带；不逐图 normalize')


def draw_maps(out, filename, panels, shape, title, manifest, stats, arrays):
    rows, cols = shape
    span = manifest['grid']['wuSpan'] * HS
    step = manifest['grid']['stepWu'] * HS
    fig, axes = plt.subplots(rows, cols, figsize=(7.0*cols, 7.1*rows), dpi=130)
    fig.subplots_adjust(left=.055, right=.90, bottom=.13, top=.88, wspace=.24, hspace=.40)
    for ax, (name, label) in zip(axes.flat, panels):
        map_panel(ax, name, label, arrays, span, step)
    fig.suptitle(title, fontsize=21, y=.97)
    fig.text(.055, .915, '同一 1830 wu 窗口 → 3660 格；所有面板的范围、光照和高度换算相同', fontsize=12)
    cax = fig.add_axes([.93, .27, .014, .48])
    bar = fig.colorbar(ScalarMappable(norm=NORM, cmap=CMAP), cax=cax,
                       ticks=[-125, -100, -50, 0, 50, 100, 125])
    bar.set_label('相对统一参考面的 ΔY（未叠光照色带）')
    fig.text(.055, .02, caption(manifest['size'], step), fontsize=11, linespacing=1.8)
    if filename == 'overview.png':
        fig.text(.055, .107, '注意：candidate1830 → 最终 candidate610 同时改变尺度和相位；纯尺度对照见 controls.png。',
                 fontsize=11, color='#9c3c20')
    else:
        for row in range(3):
            a, b = panels[2*row][0], panels[2*row+1][0]
            pair = next(p for p in stats['differences'] if p['a'] == a and p['b'] == b)
            y = axes[row, 0].get_position().y0 - .040
            fig.text(.055, y, '本行唯一变量见标题；去 DC 的 RMS = %.3f ΔY，平均偏移 = %+.3f ΔY'
                     % (pair['centeredRms']*Y_FACTOR, pair['signedMean']*Y_FACTOR), fontsize=10)
    fig.savefig(out / filename, dpi=130, facecolor='white')
    plt.close(fig)


def draw_profiles(out, manifest, arrays):
    groups = [
        [('base-rune', '原 base'), ('rune-author', '原作者结果')],
        [('rune-double-author', 'Rune 作者参数'), ('m1-compressed-author', '压缩 M1 作者参数'),
         ('m1-compressed-candidate', '压缩 M1 candidate 参数')],
        [('base-m1-native', 'native base'), ('m1-native-candidate-1830', 'candidate1830 局部'),
         ('m1-native-candidate-610-local', 'candidate610 局部'),
         ('m1-native-candidate-610', 'candidate610 世界相位')],
    ]
    n = manifest['size']
    step = manifest['grid']['stepWu'] * HS
    span = manifest['grid']['wuSpan'] * HS
    xs = (np.arange(n) + .5) * step
    mid = n // 2
    fig, axes = plt.subplots(3, 2, figsize=(21, 13), sharex=True, sharey=True, dpi=130)
    fig.subplots_adjust(left=.065, right=.97, bottom=.15, top=.87, hspace=.42, wspace=.16)
    colors = ['#205f9a', '#d26426', '#2e8a56', '#923c95']
    styles = ['-', '--', '-.', ':']
    for row, group in enumerate(groups):
        for col in range(2):
            ax = axes[row, col]
            for i, (name, label) in enumerate(group):
                cut = arrays[name][mid, :] if col == 0 else arrays[name][:, mid]
                ax.plot(xs, cut*Y_FACTOR, color=colors[i], ls=styles[i], lw=1.3, label=label)
            ax.set(xlim=(0, span), ylim=LIMITS, ylabel='相对 ΔY', xlabel='局部 %s（格）' % ('x' if col == 0 else 'z'))
            ax.set_xticks([0, 1000, 2000, 3000, span])
            ax.set_yticks([-125, -100, -50, 0, 50, 100, 125])
            ax.axhline(0, color='#888888', lw=.6)
            ax.grid(alpha=.22)
            ax.set_title(('水平剖面：固定 z' if col == 0 else '垂直剖面：固定 x')
                         + f' = {xs[mid]:.2f} 格（索引 {mid}）', loc='left')
            ax.legend(loc='lower left', fontsize=9, ncol=2, framealpha=.9)
    fig.suptitle('统一轴剖面对照｜上：原始；中：压缩基底 / 参数；下：native 尺度 / 相位', fontsize=19, y=.97)
    fig.text(.065, .91, '全部面板使用同一水平轴 0～3660 格、同一垂直轴 -125～125 ΔY；连接原生样本，不插值、不平滑。', fontsize=12)
    fig.text(.065, .025, caption(n, step) + '\n剖面纵横屏幕比例为可读性固定排版，不是等比例地形侧视；地图法线按真实换算坡度计算。',
             fontsize=11, linespacing=1.7)
    fig.savefig(out / 'profiles.png', dpi=130, facecolor='white')
    plt.close(fig)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', nargs='?', type=Path, default=DEFAULT_OUT)
    out = parser.parse_args().directory.resolve()
    if out != DEFAULT_OUT.resolve():
        raise ValueError('Output is restricted to ' + str(DEFAULT_OUT))
    for name in OUTPUTS:
        if (out / name).exists():
            raise FileExistsError('Refusing overwrite: ' + str(out / name))
    # Snapshot every pre-existing experiment input, including logs, not just arrays.
    before = {str(p): sha(p) for p in out.rglob('*') if p.is_file()}
    font_path, family = configure_font()
    manifest, stats, arrays, checked = verify_and_load(out)
    if manifest['grid']['hs'] != HS:
        raise ValueError('Unexpected horizontal mapping')
    draw_maps(out, 'overview.png', OVERVIEW, (2, 3), 'Rune / M1 完整对照｜统一线性单位，主图无坡度夸张', manifest, stats, arrays)
    draw_maps(out, 'controls.png', CONTROLS, (3, 2), '三组单变量受控对比｜每行只改变一个因素', manifest, stats, arrays)
    draw_profiles(out, manifest, arrays)
    for path, expected in {**before, **checked}.items():
        if sha(Path(path)) != expected:
            raise ValueError('Input changed while rendering: ' + path)
    images = {}
    for name in OUTPUTS[:-1]:
        with Image.open(out / name) as image:
            images[name] = {'sha256': sha(out / name), 'pixels': list(image.size)}
    heights = {k: v*Y_FACTOR for k, v in arrays.items() if not k.startswith('slope-')}
    metadata = {
        'script': str(Path(__file__).resolve()), 'scriptSha256': sha(Path(__file__)),
        'command': [sys.executable, '-B', str(Path(__file__).resolve()), str(out)],
        'font': {'path': font_path, 'family': family, 'sha256': sha(Path(font_path))},
        'versions': {'numpy': np.__version__, 'matplotlib': matplotlib.__version__},
        'hashVerification': {'allManifestHashesMatched': True, 'allInputBytesUnchanged': True,
                             'referenceHashesCount': len(manifest['referenceHashes']),
                             'referenceCaveat': 'Manifest has no reference hashes; no independent baseline reproduction claimed.',
                             'verified': checked, 'preexistingExperimentFiles': before},
        'render': {'horizontal': 'local blocks = wu * 2; origin at common comparison window',
                   'vertical': 'deltaY = 236.44 * 1.05 * h; reference h=0; NOT actual game Y',
                   'verticalFactor': Y_FACTOR, 'verticalExaggeration': 1,
                   'colorLimitsDeltaY': list(LIMITS), 'palette': ['#344860', '#7495a6', '#c4cdc3', '#d5bf90', '#f4eddb'],
                   'lightXUpZUnit': LIGHT.tolist(), 'normal': '(-dY/dBlockX, 1, -dY/dBlockZ)',
                   'shade': '0.30 + 0.70 * clip(dot(unitNormal, unitLight), 0, 1)',
                   'blend': 'fixed height RGB multiplied by grayscale shade',
                   'perPanelNormalization': False, 'baselineP90Exaggeration': False,
                   'grid': manifest['grid'], 'nativeSize': manifest['size'],
                   'interpolation': 'nearest; no smoothing or data resampling',
                   'profileAxes': {'x': [0, 3660], 'y': list(LIMITS), 'index': manifest['size']//2,
                                   'note': 'fixed plot aspect, not 1:1 side elevation'},
                   'DC': 'Use supplied centered h unchanged; no per-arm mean subtraction or original absolute DC offset'},
        'overviewPanels': OVERVIEW, 'controlPanels': CONTROLS,
        'heightRangesDeltaY': {k: [float(a.min()), float(a.max())] for k, a in heights.items()},
        'clippedSamples': {k: int(((a < LIMITS[0]) | (a > LIMITS[1])).sum()) for k, a in heights.items()},
        'notes': ['Isolated signal experiment, not production acceptance or Minecraft render.',
                  'Final native610 differs from native1830 in BOTH scale and phase.',
                  'RMS displacement is not a visual-quality score or matched-landform error.',
                  'No legacy renderer audit or exaggerated-detail figure generated.'],
        'outputs': images,
    }
    (out / 'render-metadata.json').write_text(json.dumps(metadata, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    print('PASS: all manifest hashes/statistics matched; all pre-existing experiment bytes unchanged')
    for name in OUTPUTS:
        print(out / name)


if __name__ == '__main__':
    main()
