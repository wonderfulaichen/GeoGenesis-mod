"""重建骨架叠加证据图（代码已清理，此脚本用记录的数值直接绘图）。

用法：python tools/regen-skel-evidence.py <outDir>
说明：图中数值来自 handoff/skeleton-overlay-failed-20261030.md 的实测记录。
"""
import sys
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont

FONT = 'C:/Windows/Fonts/msyh.ttc'
BG = '#f4f3ed'
INK = '#25261f'
DIM = '#5d594c'
WARN = '#b8654a'


def main():
    out = Path(sys.argv[1])
    out.mkdir(parents=True, exist_ok=True)

    # ---- 图 1：幅度失控 + 平滑扫描（柱状对比） ----
    t1 = ImageFont.truetype(FONT, 30)
    t2 = ImageFont.truetype(FONT, 17)
    t3 = ImageFont.truetype(FONT, 15)

    rows = [
        ('r=0（不平滑）', 187.6, 29.1, 1.77, 1.62, 1.09),
        ('r=4', 135.8, 23.4, 2.84, 1.68, 1.69),
        ('r=8', 67.5, 22.4, 4.38, 1.98, 2.21),
        ('r=16', 87.3, 32.4, 7.22, 2.96, 2.44),
    ]
    # 生产参考：骨架 delta ±5 格
    PROD = 5.0

    W, H = 1180, 520
    img = Image.new('RGB', (W, H), BG)
    d = ImageDraw.Draw(img)
    d.text((34, 14), '骨架侵蚀后置叠加：delta 幅度 vs 生产量级', font=t1, fill=INK)
    d.text((34, 58), '生产里 RidgeValleyErosion 的 delta 是 ±5 块（±0.02e）；'
                     '沙箱后置叠加即使预平滑到 r=8 仍有 67 格', font=t2, fill=DIM)
    d.text((34, 82), '横轴 = max|Δ|（格）｜红色虚线 = 生产量级 ±5 格', font=t3, fill=DIM)

    x0, y0 = 200, 130
    barH, gapY = 46, 26
    maxV = 200
    plotW = 600

    # 生产参考线
    px = x0 + plotW * PROD / maxV
    d.line((px, y0 - 12, px, y0 + len(rows) * (barH + gapY)), fill=WARN, width=2)
    d.text((px + 6, y0 - 30), '±5（生产）', font=t3, fill=WARN)

    for i, (name, mx, p99, lm, wm, ratio) in enumerate(rows):
        y = y0 + i * (barH + gapY)
        d.text((34, y + 8), name, font=t2, fill=INK)
        # max 柱
        w = plotW * mx / maxV
        d.rectangle((x0, y, x0 + w, y + barH), fill='#d98b6a' if mx > 50 else '#8fae7c')
        d.text((x0 + w + 8, y + 6), f'max {mx:.0f} 格', font=t2, fill=INK)
        # P99 细条
        w2 = plotW * p99 / maxV
        d.rectangle((x0, y + barH - 10, x0 + w2, y + barH), fill='#6b8ea8')
        d.text((x0 + w2 + 8, y + barH - 20), f'P99 {p99:.1f}', font=t3, fill=DIM)
        # 右侧陆/水比
        d.text((x0 + plotW + 130, y + 8),
               f'陆 {lm:.2f} / 水 {wm:.2f}  比 {ratio:.2f}', font=t3, fill=DIM)

    d.text((34, H - 60), '结论：预平滑能把变化推向山地（陆/水比 1.09 -> 2.44），'
                         '但幅度仍超生产 13~17 倍  =>  后置叠加不可行', font=t2, fill=WARN)
    img.save(out / 'amplitude-vs-production.png', optimize=True)
    print(out / 'amplitude-vs-production.png')

    # ---- 图 2：七类地形 delta 分布 ----
    kinds = [
        ('① 深海 Y<20', 35843, 16.9, 2.40, 7.55),
        ('② 浅海 Y 20–55', 288874, 49.5, 2.21, 13.98),
        ('③ 近岸 Y 55–63', 380549, 50.0, 1.77, 17.21),
        ('④ 海岸带', 200359, 53.5, 3.69, 24.47),
        ('⑤ 平原 Y 63–80', 404698, 55.3, 3.21, 24.27),
        ('⑥ 高地 Y 120–180', 290334, 67.5, 6.14, 25.56),
        ('⑦ 山地 Y>180', 24953, 50.4, 10.30, 34.77),
    ]

    W2, H2 = 1180, 660
    img2 = Image.new('RGB', (W2, H2), BG)
    d2 = ImageDraw.Draw(img2)
    d2.text((34, 14), '七类地形：骨架 delta 分布（局部窗口统计）', font=t1, fill=INK)
    d2.text((34, 58), 'mean|Δ| 随高度单调递增（2.40  ->  10.30，4.3 倍）方向正确；'
                      '但每档 max|Δ| 都有 50~67 格', font=t2, fill=DIM)

    x0, y0 = 250, 120
    barH, gapY = 40, 22
    maxV = 70
    plotW = 520

    for i, (name, cnt, mx, mean, p99) in enumerate(kinds):
        y = y0 + i * (barH + gapY)
        d2.text((34, y + 6), name, font=t2, fill=INK)
        # max 柱（浅色）
        w = plotW * mx / maxV
        d2.rectangle((x0, y, x0 + w, y + barH), fill='#e3c3b2')
        # mean 柱（深色）
        wm = plotW * mean / maxV
        d2.rectangle((x0, y + barH - 16, x0 + wm, y + barH), fill='#b8654a')
        d2.text((x0 + w + 8, y + 2), f'max {mx:.1f}', font=t3, fill=DIM)
        d2.text((x0 + w + 8, y + 18), f'mean {mean:.2f} / P99 {p99:.1f}', font=t3, fill=DIM)
        d2.text((x0 + plotW + 160, y + 8), f'{cnt:,} px', font=t3, fill=DIM)

    noteY = y0 + len(kinds) * (barH + gapY) + 16
    d2.text((34, noteY), '图中判读要点（见 handoff 文档第 3 节）：', font=t2, fill=INK)
    d2.text((34, noteY + 26), '4 海岸带：半岛内部放射状裂纹、海岸线被切碎',
            font=t3, fill=WARN)
    d2.text((34, noteY + 48), '6/7 高地与山地：山脊变模糊失去锐度（与预期相反——骨架本该锐化山脊）',
            font=t3, fill=WARN)
    img2.save(out / 'seven-terrains-delta.png', optimize=True)
    print(out / 'seven-terrains-delta.png')


if __name__ == '__main__':
    main()
