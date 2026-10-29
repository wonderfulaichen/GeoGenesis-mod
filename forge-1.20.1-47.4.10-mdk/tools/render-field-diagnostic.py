"""Render each intermediate field of the badlands candidate to locate the artifact."""
import json
from pathlib import Path
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFont

FONT = 'C:/Windows/Fonts/msyh.ttc'
LIGHT = np.array([-0.5, -0.35, 1.0]); LIGHT /= np.linalg.norm(LIGHT)
FIELDS = [
    ('mask', '① mask 成片掩码', 'perlin(270,3)→clamp(.35,.65)→map(0,1)'),
    ('hills', '② hills 脊线×掩码', 'perlinRidge(275,4)→warp×2×mask'),
    ('detail', '③ detail 双尺度层理', '(steps4×.6+mod1 + steps10×.6+mod1)×.5+.5'),
    ('shape', '④ shape 形态', '(steps4(.65,.75,CURVE3)×.6 + mod2)×.6'),
    ('height', '⑤ height 最终高度', 'shape×detail×.55 + .025'),
    ('valley', '① valley 谷地骨架', 'invert(perlinRidge(500,1)) → warp×2'),
    ('valleyBase', '② valleyBase 进 Terrace 前', 'valley×cubic(0.3~0.9) + top(≤0.15)'),
    ('terrace', '③ terrace 输出', 'ramp .9 / cliff .15 / blend .4 / 4 级'),
]


def shade_of(v, step):
    gz, gx = np.gradient(v, step)
    norm = np.sqrt(gx * gx + gz * gz + 1e-12)
    return np.clip((-gx * LIGHT[0] - gz * LIGHT[1] + LIGHT[2]) / norm, 0, 1)


def grey(v, step):
    lo, hi = float(np.percentile(v, 1)), float(np.percentile(v, 99))
    t = np.clip((v - lo) / max(hi - lo, 1e-6), 0, 1)
    s = 0.35 + 0.65 * shade_of(v, step)
    rgb = np.stack([t, t, t], axis=-1) * s[..., None]
    return Image.fromarray(np.uint8(np.clip(rgb * 255, 0, 255)))


def main():
    root = Path(sys.argv[1])
    geo = json.loads((root / 'geometry.json').read_text(encoding='utf-8'))
    size, step = geo['size'], geo['stepBlocks']
    fields = geo['fields']

    title = ImageFont.truetype(FONT, 34)
    text = ImageFont.truetype(FONT, 21)
    small = ImageFont.truetype(FONT, 17)

    tile = min(300, size)
    cols = 3
    rows = (len(fields) + cols - 1) // cols
    panel = Image.new('RGB', (tile * cols + 30 * (cols + 1), (tile + 120) * rows + 130), '#f4f3ed')
    draw = ImageDraw.Draw(panel)
    draw.text((30, 12), '劣地复刻｜分场诊断（逐段核对参考配方）', font=title, fill='#25261f')
    draw.text((30, 60), '灰度 = 该场相对高度，明暗 = 坡向光照。同坐标、同尺度。每格下方标注对应参考调用。',
              font=text, fill='#625e51')

    info = {f[0]: f for f in FIELDS}
    for i, name in enumerate(fields):
        raw = root / f'{name}.f64be.raw'
        v = np.fromfile(raw, dtype='>f8')
        if len(v) != size * size:
            raise SystemExit(f'{name}: bad length {len(v)}')
        if not np.isfinite(v).all():
            raise SystemExit(f'{name}: non-finite')
        v = v.reshape(size, size)
        label, verdict = info.get(name, (name, ''))[1], info.get(name, (name, ''))[2]
        col = i % cols
        row = i // cols
        x = 30 + col * (tile + 30)
        y = 118 + row * (tile + 120)
        panel.paste(grey(v, step).resize((tile, tile), Image.LANCZOS), (x, y))
        draw.text((x, y + tile + 8), label, font=text, fill='#25261f')
        draw.text((x, y + tile + 36), verdict, font=small, fill='#625e51')
        draw.text((x, y + tile + 60),
                  f'值域 {v.min():.3f}..{v.max():.3f}｜均值 {v.mean():.3f}', font=small, fill='#625e51')

    out = root / 'field-diagnostic.png'
    panel.save(out)
    print(out)


if __name__ == '__main__':
    main()
