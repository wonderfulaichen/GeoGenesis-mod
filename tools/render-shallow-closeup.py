#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""浅海段形态项｜1:1（step=1）特写出图：判断"读起来像不像自然海底"。

用法：
    python tools/render-shallow-closeup.py <root> <dest>

为什么需要它：1401² 大图是 step=5，带内形态只剩几个像素，无法区分"自然起伏"与
"紧贴岸线的等值线包络"。本项目三次栽在平行带伪影上，判据只能是人眼看 1:1 图。

输入（schema 严格校验，不合规一张不画、exit 1）：
    <root>/meta.json     {"seed","size","step"=1,"center":[cx,cz],"sampleOrigin":[ox,oz]}
    <root>/base.f64be    baseline 臂高度（格）
    <root>/shelf.f64be   shelf 臂高度（格）
    <root>/delta.f64be   shelf − base（格）

输出（全部 1:1，绝不缩放）：
    closeup-pair.png          左 基准 / 右 浅海段形态项
    closeup-delta.png         改动量放大 + 与岸线平行性的目视判断
    closeup-shaded.png        只保留带内、把改动 ×6 放大后的形态（"如果看不清就放大"）
"""
import importlib.util
import json
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw, ImageFont

BG = '#f4f3ed'
INK = '#25261f'
DIM = '#55594c'
FONT_CANDIDATES = ('C:/Windows/Fonts/msyh.ttc', 'C:/Windows/Fonts/msyhbd.ttc',
                   'C:/Windows/Fonts/simhei.ttf')
REQUIRED = ('seed', 'size', 'step', 'center', 'sampleOrigin')


class InputError(Exception):
    pass


def need(cond, msg):
    if not cond:
        raise InputError(msg)


_FONTS = {}


def font(size):
    if size not in _FONTS:
        for p in FONT_CANDIDATES:
            if Path(p).is_file():
                _FONTS[size] = ImageFont.truetype(p, size)
                break
        else:
            raise InputError('no CJK font')
    return _FONTS[size]


def text_width(text, f):
    try:
        return float(f.getlength(text))
    except AttributeError:
        return float(f.getsize(text)[0])


def fit(text, max_w, preferred, floor=12):
    size = preferred
    while size > floor and text_width(text, font(size)) > max_w:
        size -= 1
    return font(size)


def load_module():
    here = Path(__file__).resolve()
    for cand in (here.with_name('render-islands-big.py'),
                 here.parent.parent / 'forge-1.20.1-47.4.10-mdk' / 'tools' / 'render-islands-big.py'):
        if cand.is_file():
            spec = importlib.util.spec_from_file_location('islands_big_renderer', str(cand))
            mod = importlib.util.module_from_spec(spec)
            spec.loader.exec_module(mod)
            return mod
    raise InputError('render-islands-big.py not found')


def read_geo(root):
    path = root / 'meta.json'
    need(path.is_file(), 'missing %s' % path)
    try:
        raw = json.loads(path.read_text(encoding='utf-8'))
    except ValueError as exc:
        raise InputError('%s not JSON: %s' % (path, exc))
    need(isinstance(raw, dict), '%s must be an object' % path)
    missing = [k for k in REQUIRED if k not in raw]
    need(not missing, '%s missing %s' % (path, missing))
    extra = sorted(set(raw) - set(REQUIRED) - {'jvmProperties'})
    need(not extra, '%s has unknown keys %s' % (path, extra))
    need(int(raw['step']) == 1, 'this tool only handles step=1, got %r' % (raw['step'],))
    size = int(raw['size'])
    need(3 <= size <= 4096, 'size out of range: %d' % size)
    cx, cz = (float(v) for v in raw['center'])
    ox, oz = (float(v) for v in raw['sampleOrigin'])
    need(abs(ox - (cx - size / 2.0)) <= 1e-6, 'sampleOrigin.x inconsistent with center/size')
    need(abs(oz - (cz - size / 2.0)) <= 1e-6, 'sampleOrigin.z inconsistent with center/size')
    return {'seed': str(raw['seed']), 'size': size, 'center': (cx, cz), 'origin': (ox, oz)}


def read_grid(root, name, size):
    path = root / name
    need(path.is_file(), 'missing %s' % path)
    data = path.read_bytes()
    need(len(data) == size * size * 8,
         '%s: %d bytes, expected %d' % (path, len(data), size * size * 8))
    grid = np.frombuffer(data, dtype='>f8').astype(np.float64).reshape(size, size)
    need(np.isfinite(grid).all(), '%s has non-finite values' % path)
    return grid


def panel(images, titles, heading, lines, note):
    w, h = images[0].size
    need(all(im.size == (w, h) for im in images), 'panel images differ in size')
    pad, gap, tail = 40, 40, 52
    line_y, line_dy = 70, 28
    head = line_y + line_dy * len(lines) + 56
    grid_w = pad * 2 + w * len(images) + gap * (len(images) - 1)
    text_w = max([text_width(t, font(19)) for t in list(lines) + [note]]
                 + [text_width(heading, font(34))])
    cw = int(max(grid_w, text_w + pad * 2 + 8))
    canvas = Image.new('RGB', (cw, head + h + tail), BG)
    draw = ImageDraw.Draw(canvas)
    draw.text((pad, 14), heading, font=fit(heading, cw - pad * 2, 34), fill=INK)
    for i, text in enumerate(lines):
        draw.text((pad, line_y + i * line_dy), text,
                  font=fit(text, cw - pad * 2, 19), fill=DIM)
    spots = []
    for i, (im, title) in enumerate(zip(images, titles)):
        x = pad + i * (w + gap)
        draw.text((x, head - 34), title, font=fit(title, w, 22), fill=INK)
        canvas.paste(im, (x, head))
        spots.append((im, (x, head)))
    draw.text((pad, head + h + 14), note, font=fit(note, cw - pad * 2, 19), fill=DIM)
    return canvas, spots


def to_image(rgb):
    return Image.fromarray(np.uint8(np.clip(rgb, 0, 255)))


def main(argv):
    if len(argv) != 2:
        sys.stderr.write('usage: python tools/render-shallow-closeup.py <root> <dest>\n')
        return 2
    root, dest = Path(argv[0]), Path(argv[1])
    try:
        geo = read_geo(root)
        size = geo['size']
        mod = load_module()
        base = read_grid(root, 'base.f64be', size)
        shelf = read_grid(root, 'shelf.f64be', size)
        delta = read_grid(root, 'delta.f64be', size)

        # 一致性：delta 必须等于 shelf − base（防止三份 raw 不同源）
        need(np.array_equal(delta, shelf - base), 'delta.f64be != shelf − base')

        sea = float(mod.SEA)
        land = base >= sea
        # 硬门禁：陆上一格都不能动
        need(int((delta[land] != 0).sum()) == 0,
             'shallow detail touched %d land pixels' % int((delta[land] != 0).sum()))

        dest.mkdir(parents=True, exist_ok=True)
        geo_line = ('seed=%s｜%d² 逐块（step=1），覆盖 %d×%d 块｜中心块 (%.0f, %.0f)｜原点 (%.0f, %.0f)'
                    % (geo['seed'], size, size, size, geo['center'][0], geo['center'][1],
                       geo['origin'][0], geo['origin'][1]))

        img_base = mod.render(base, 1)
        img_shelf = mod.render(shelf, 1)
        pair, spots = panel(
            [img_base, img_shelf], ['基准（认可口径）', '浅海段形态项'],
            '浅海段形态项｜1:1 逐块特写（未缩放）',
            [geo_line,
             '这一档是 %d×%d 块、逐块采样，比 1401² 大图（1 像素 = 5 块）密 5 倍。' % (size, size),
             '看的不是轮廓 —— 岸线位置、岛屿、陆上一格都没动。要看的是水下浅海带里的起伏纹理是否自然。'],
            '同种子、同窗口、同光照、同色带；两张都是 %d² 原生像素。' % size)
        pair.save(dest / 'closeup-pair.png', optimize=True)

        # 改动量：按"是否与岸线平行"来读
        amp = max(float(np.percentile(np.abs(delta), 99.9)), 1e-6)
        tone = np.clip(np.abs(delta) / amp, 0.0, 1.0)
        stops = ((0.0, (232, 238, 246)), (0.45, (126, 176, 224)),
                 (0.75, (246, 176, 74)), (1.0, (186, 44, 36)))
        rgb = np.stack([np.interp(tone, [s[0] for s in stops], [s[1][c] for s in stops])
                        for c in range(3)], axis=-1)
        rgb = np.where(land[..., None], np.array([252.0, 252.0, 250.0]), rgb)
        img_delta = to_image(rgb)
        d2, _ = panel(
            [img_delta], ['|浅海 − 基准|'],
            '浅海段形态项｜改动量（1:1）',
            [geo_line,
             '最大 |Δ| = %.3f 格，P99.9 = %.3f 格，改动像素 %d（%.1f%% of 全图）。'
             % (float(np.abs(delta).max()), amp, int((delta != 0).sum()),
                (delta != 0).mean() * 100.0),
             '★ 判读要点：若亮带**紧贴岸线、宽度均匀、沿等值线绕行**，那就是"值域等值线包络"伪影；'
             '若是成片的斑块状、边界不对应任何等值线，才是自然起伏。'],
            '白色 = 陆上（改动恒为 0）。色阶与像素共用同一 stops，不存在"图例说谎"。')
        d2.save(dest / 'closeup-delta.png', optimize=True)

        # 放大形态：把带内改动 ×GAIN 后叠加到基准，只用于"看清形态长什么样"
        gain = 6.0
        boosted = np.where(land, base, base + delta * gain)
        img_boost = mod.render(boosted, 1)
        b2, _ = panel(
            [img_boost], ['带内改动 ×%.0f 放大' % gain],
            '浅海段形态项｜形态放大（×%.0f，仍只在水下）' % gain,
            [geo_line,
             '把很小的改动放大 %.0f 倍，只是为了让形状看得清 —— 这张图**不是**要交付的效果，'
             '实际改动幅度是 %.2f 格量级（见上两图）。' % (gain, float(np.percentile(np.abs(delta), 99.9))),
             '用途：判断起伏是"成片的圆丘/沟槽"（自然）还是"沿等值线的平行细线"（伪影）。'],
            '放大只作用于水下像素；陆上一格未动。')
        b2.save(dest / 'closeup-boosted.png', optimize=True)

        # 逐像素守门：粘贴块必须与原图一致（证明未缩放）
        for name, image, sp in (('closeup-pair.png', pair, spots),):
            with Image.open(dest / name) as handle:
                for im, (x, y) in sp:
                    crop = handle.convert('RGB').crop((x, y, x + im.width, y + im.height))
                    need(np.array_equal(np.asarray(crop), np.asarray(im.convert('RGB'))),
                         '%s: pasted tile not pixel-identical (resampled?)' % name)

        print('rendered 3 closeup figures -> %s' % dest)
        return 0
    except InputError as exc:
        sys.stderr.write('FAIL %s\n' % exc)
        return 1


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
