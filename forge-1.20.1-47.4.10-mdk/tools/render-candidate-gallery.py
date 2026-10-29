"""Render candidate terrain variants: shaded relief + 1:1 close-up + cross-section profile.

Layout is measured, not hard-coded: the panel is sized from the actual text widths so no
label or statistic gets clipped. Every card carries a cross-section profile, matching the
single-image output that was requested earlier.
"""
import json
from pathlib import Path
import sys

import numpy as np
from PIL import Image, ImageDraw, ImageFont

FONT = 'C:/Windows/Fonts/msyh.ttc'
TITLE = ImageFont.truetype(FONT, 32)
TEXT = ImageFont.truetype(FONT, 19)
SMALL = ImageFont.truetype(FONT, 16)

LIGHT = np.array([-0.5, -0.35, 1.0]); LIGHT /= np.linalg.norm(LIGHT)
LOCAL_COLORS = np.array([[38, 44, 40], [72, 84, 66], [120, 132, 92],
                         [162, 158, 120], [186, 176, 148], [214, 206, 182], [236, 231, 216]])

LABELS = {
    'BADLANDS-ref': ('劣地（参考复刻）', 'makeBadlands 逐行照搬'),
    'PLAINS-ref': ('平原 plains（参考复刻）', '起伏0.15 / warp 3倍频'),
    'STEPPE-ref': ('草原 steppe（参考复刻）', '起伏0.08 / 末道warp200'),
    'PLAINS-geo': ('平原 plains（地理修正）', 'warp 收 1 倍频 + 末道 64'),
    'HILLS-hills1': ('丘陵 hills_1（参考复刻）', 'perlin(200,3) × billow(400,3) 包络'),
    'HILLS-hills1-geo': ('丘陵 hills_1（地理修正）', '包络 400→650 格，丘群连片'),
    'HILLS-hills2': ('丘陵 hills_2（参考复刻）', 'cubic(128,2) × ridged(512,2)'),
    'HILLS-dales': ('谷地 dales（参考复刻）', '双 billow(lac 4.0) + selector 混合'),
    'HILLS-dales-geo': ('谷地 dales（地理修正）', 'billow lac 4.0→2.5，谷底更宽、坡更缓'),
    'HILLS-region': ('丘陵分区（三种并存）', 'Voronoi 区域选一种 + 边缘混合回平原'),
    'MTN-m1': ('山地 m1（参考复刻）', '振幅调制 24 格'),
    'MTN-m1-geo': ('山地 m1（地理修正）', '调制 180 格 + 幅度 0.30（峰与鞍部）'),
    'MTN-m2': ('山地 m2（参考复刻）', '细胞 360 格 / 脊线 0.37'),
    'MTN-m2-geo': ('山地 m2（地理修正）', '细胞 520 格 / 脊线 0.50（连续山系）'),
    'MTN-m3': ('山地 m3（参考复刻）', '600 格 + 24 级阶地'),
    'MTN-m3-geo': ('山地 m3（地理修正）', '600 格 + 6 级阶地（宽平台）'),
    'MTN-region': ('山地分区（三种并存）', 'Voronoi 区域选一种 + 边缘混合回平原'),
    'PLATEAU-ref': ('台地（参考复刻）', '谷为线状（valley 原样）'),
    'PLATEAU-geo': ('台地（地理修正 p=1.1）', 'valley^1.1：略宽于参考'),
    'BASIN-b1': ('盆地 B1 内流盆地', '盐滩洼地 + 盆缘冲积扇，底最平'),
    'BASIN-b2': ('盆地 B2 山间盆地', '平坦底 + 稀疏残余低山脊'),
    'BASIN-b3': ('盆地 B3 构造盆地', '1500 格宽缓碗形，无中尺度特征'),
}

TILE = 300          # shaded-relief tile
CLOSE = 190         # 1:1 close-up tile
PROFILE_H = 96      # cross-section strip height
GAP = 26
MARGIN = 30


def shade(height, step):
    gz, gx = np.gradient(height, step)
    norm = np.sqrt(gx * gx + gz * gz + 1)
    return np.clip((-gx * LIGHT[0] - gz * LIGHT[1] + LIGHT[2]) / norm, 0, 1)


def relief_image(height, step, lo, hi):
    t = np.clip((height - lo) / max(hi - lo, 1e-6), 0, 1)
    pos = t * (len(LOCAL_COLORS) - 1)
    base = np.stack([np.interp(pos, np.arange(len(LOCAL_COLORS)), LOCAL_COLORS[:, c])
                     for c in range(3)], axis=-1)
    brightness = 0.30 + 0.70 * shade(height, step)
    return Image.fromarray(np.uint8(np.clip(base * brightness[..., None], 0, 255)))


def text_w(draw, text, font):
    """Width of text; Pillow <8 has no textlength (this machine ships 7.x)."""
    if hasattr(draw, 'textlength'):
        return draw.textlength(text, font=font)
    return draw.textsize(text, font=font)[0]


def wrap(text, font, max_w, draw):
    """Split text into lines that fit max_w."""
    if text_w(draw, text, font) <= max_w:
        return [text]
    lines, cur = [], ''
    for ch in text:
        if text_w(draw, cur + ch, font) > max_w and cur:
            lines.append(cur)
            cur = ch
        else:
            cur += ch
    if cur:
        lines.append(cur)
    return lines


def draw_profile(draw, height, x0, y0, w, h):
    """Cross-section along the middle row, with absolute Y gridlines."""
    draw.rectangle((x0, y0, x0 + w, y0 + h), fill='#f4f3ed')
    row = height[height.shape[0] // 2]
    lo, hi = float(row.min()), float(row.max())
    span = max(hi - lo, 1e-6)
    # nice gridlines at absolute Y
    for frac in (0.0, 0.5, 1.0):
        level = lo + span * frac
        yy = y0 + h - 8 - (h - 18) * frac
        draw.line((x0, yy, x0 + w, yy), fill='#d8d7cf')
        draw.text((x0 + w + 3, yy - 8), f'{level:.0f}', font=SMALL, fill='#595950')
    pts = [(x0 + i * (w - 1) / (len(row) - 1), y0 + h - 8 - (h - 18) * (row[i] - lo) / span)
           for i in range(len(row))]
    draw.line(pts, fill='#403b30', width=2)


def main():
    root = Path(sys.argv[1])
    geo = json.loads((root / 'geometry.json').read_text(encoding='utf-8'))
    size, step = geo['size'], geo['stepBlocks']
    variants = geo['variants']
    if not variants:
        raise SystemExit('geometry.json has no variants')

    # Measure first: every card is as wide as its widest line, so nothing clips.
    probe = ImageDraw.Draw(Image.new('RGB', (10, 10)))
    cards = []
    for vid in variants:
        raw = root / f'{vid}.f64be.raw'
        values = np.fromfile(raw, dtype='>f8')
        if len(values) != size * size:
            raise SystemExit(f'{vid}: bad raw length {len(values)}')
        if not np.isfinite(values).all():
            raise SystemExit(f'{vid}: non-finite height')
        height = values.reshape(size, size)
        gz, gx = np.gradient(height, step)
        slope = np.hypot(gx, gz)
        lo, hi = float(np.percentile(height, 1)), float(np.percentile(height, 99))
        name, detail = LABELS.get(vid, (vid, ''))
        stat = (f'起伏 {height.max() - height.min():.1f} 格｜平顶 {(slope < 0.02).mean() * 100:.0f}%'
                f'｜中位坡 {np.percentile(slope, 50):.2f}｜P99 {np.percentile(slope, 99):.2f}')
        head_w = text_w(probe, f'{name}（{vid}）', TEXT)
        detail_lines = wrap(detail, SMALL, TILE + CLOSE + GAP, probe)
        stat_lines = wrap(stat, SMALL, TILE + CLOSE + GAP, probe)
        text_w_max = max([head_w] + [text_w(probe, l, SMALL) for l in detail_lines + stat_lines])
        card_w = max(TILE + CLOSE + GAP, int(text_w_max) + 4)
        cards.append(dict(vid=vid, height=height, lo=lo, hi=hi,
                          name=name, detail_lines=detail_lines, stat_lines=stat_lines,
                          card_w=card_w, slope=slope))

    card_w = max(c['card_w'] for c in cards)
    cols = 2 if len(cards) > 1 else 1
    rows = (len(cards) + cols - 1) // cols
    card_h = 30 + TILE + PROFILE_H + 12 + 24 + 22 * max(len(c['detail_lines']) for c in cards) \
             + 22 * max(len(c['stat_lines']) for c in cards) + 18
    width = MARGIN * 2 + cols * card_w + (cols - 1) * GAP * 2
    height_px = 120 + rows * (card_h + GAP * 2)

    panel = Image.new('RGB', (width, height_px), '#f4f3ed')
    draw = ImageDraw.Draw(panel)
    draw.text((MARGIN, 14), '地貌族候选｜参考原版复刻', font=TITLE, fill='#25261f')
    draw.text((MARGIN, 60), '每张：上为着色地形，右上为 1:1 局部，下为中央横切面（纵轴为绝对 Y）。',
              font=TEXT, fill='#625e51')
    draw.text((MARGIN, 86), '同坐标、同光照、同色阶；剖面纵轴按该地形自身高度范围，标尺已注明绝对值。',
              font=SMALL, fill='#625e51')

    for i, c in enumerate(cards):
        x = MARGIN + (i % cols) * (card_w + GAP * 2)
        y = 120 + (i // cols) * (card_h + GAP * 2)
        panel.paste(relief_image(c['height'], step, c['lo'], c['hi']).resize((TILE, TILE), Image.LANCZOS),
                    (x, y + 30))
        half = CLOSE // 2
        crop = c['height'][size // 2 - half: size // 2 + half, size // 2 - half: size // 2 + half]
        panel.paste(relief_image(crop, step, c['lo'], c['hi']), (x + TILE + GAP, y + 30))
        draw.text((x + TILE + GAP, y + 30 + CLOSE + 4), f'1:1 {CLOSE}² 方块', font=SMALL, fill='#625e51')

        ty = y + 30 + TILE + 6
        draw_profile(draw, c['height'], x, ty, TILE, PROFILE_H)
        draw.text((x, ty + PROFILE_H + 2), f'中央横切面｜Y {c["height"].min():.1f}–{c["height"].max():.1f}',
                  font=SMALL, fill='#625e51')

        ly = ty + PROFILE_H + 24
        draw.text((x, ly), f'{c["name"]}（{c["vid"]}）', font=TEXT, fill='#25261f')
        ly += 24
        for line in c['detail_lines']:
            draw.text((x, ly), line, font=SMALL, fill='#625e51')
            ly += 22
        for line in c['stat_lines']:
            draw.text((x, ly), line, font=SMALL, fill='#625e51')
            ly += 22

        relief_image(c['height'], step, c['lo'], c['hi']).save(root / f'{c["vid"]}-local.png')
        relief_image(crop, step, c['lo'], c['hi']).save(root / f'{c["vid"]}-closeup.png')

    out = root / 'candidates.png'
    panel.save(out)
    print(out)


if __name__ == '__main__':
    main()