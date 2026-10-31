"""类型占比 + 类型边界台阶诊断。

产出：
  1) 每个臂的五类占比（全体 / 仅陆地）
  2) 类型对共现矩阵（相邻像素属于不同类的频次）
  3) 每对类型边界处的 |ΔY| 统计 —— 定位"台阶"发生在哪一对类型之间

用法：python tools/analyze-types.py <outDir> [臂名...]
"""
import json
import sys
from pathlib import Path

import numpy as np

NAMES = ['盆地', '平原', '丘陵', '台地', '山地']
SEA = 63.0


def main():
    root = Path(sys.argv[1])
    arms = sys.argv[2:] or ['compose-coastal', 'compose-wie-s1p5']

    geo = json.loads((root / 'compose-geometry.json').read_text(encoding='utf-8'))
    size, step = geo['size'], geo['stepBlocks']
    # 每像素在世界坐标上的位移（块）
    px = step

    print(f'窗口 {size}×{size} 采样，step={step} 块，覆盖 {size * step}×{size * step} 块')
    print(f'1 像素 = {step} 块\n')

    all_types = {}
    for arm in arms:
        hp = root / f'{arm}.f64be.raw'
        tp = root / f'types-{arm}.u8'
        if not hp.exists() or not tp.exists():
            print(f'跳过 {arm}（缺文件）')
            continue
        h = np.fromfile(hp, dtype='>f8').reshape(size, size)
        t = np.fromfile(tp, dtype=np.uint8).reshape(size, size)
        all_types[arm] = (h, t)

    for arm, (h, t) in all_types.items():
        land = h >= SEA
        print('=' * 78)
        print(f'【{arm}】')
        print(f'  水面 {100 * (~land).mean():.2f}%   陆地 {100 * land.mean():.2f}%')
        print()
        print(f'  {"类型":<6}{"全体占比":>10}{"陆地占比":>10}{"该类型内陆地率":>14}{"Y P50":>9}{"Y P95":>9}')
        n_land = land.sum()
        for i, nm in enumerate(NAMES):
            m = t == i
            if m.sum() == 0:
                print(f'  {nm:<6}{0.0:>9.2f}%{0.0:>9.2f}%{0.0:>13.1f}%{"-":>9}{"-":>9}')
                continue
            # 陆地占比的分子必须也限定在陆地上，否则各类型相加 >100%
            land_of_type = (m & land).sum()
            land_frac = land[m].mean() * 100
            hl = h[m & land]
            p50 = np.percentile(hl, 50) if hl.size else float('nan')
            p95 = np.percentile(hl, 95) if hl.size else float('nan')
            print(f'  {nm:<6}{100 * m.mean():>9.2f}%{100 * land_of_type / n_land:>9.2f}%'
                  f'{land_frac:>13.1f}%{p50:>9.1f}{p95:>9.1f}')

        # ---- 类型对边界 ----
        print()
        print('  相邻像素类型不同的类型对（陆地内），及边界处的 |ΔY|：')
        print(f'  {"类型对":<14}{"边界像素":>10}{"占陆地%":>9}'
              f'{"|ΔY|P50":>9}{"P90":>8}{"P99":>8}{"max":>8}')
        pairs = []
        for di, dj in ((0, 1), (1, 0)):
            pass
        # 右邻 + 下邻
        for axis, label in ((1, '右'), (0, '下')):
            a = t
            b = np.roll(t, -1, axis=axis)
            ha = h
            hb = np.roll(h, -1, axis=axis)
            diff_t = a != b
            landboth = (ha >= SEA) & (hb >= SEA)
            m = diff_t & landboth
            if not m.any():
                continue
            ka = a[m]
            kb = b[m]
            d = np.abs(hb[m] - ha[m])
            # 汇总到无序对
            lo = np.minimum(ka, kb)
            hi = np.maximum(ka, kb)
            for i in range(5):
                for j in range(i + 1, 5):
                    sel = (lo == i) & (hi == j)
                    if sel.sum() == 0:
                        continue
                    dd = d[sel]
                    pairs.append((i, j, sel.sum(), dd))
        # 合并两个方向的计数
        agg = {}
        for i, j, n, dd in pairs:
            agg.setdefault((i, j), []).append(dd)
        rows = []
        for (i, j), arrs in agg.items():
            dd = np.concatenate(arrs)
            rows.append((i, j, dd))
        rows.sort(key=lambda r: -r[2].size)
        total_land_edges = sum(r[2].size for r in rows)
        for i, j, dd in rows:
            print(f'  {NAMES[i]}-{NAMES[j]:<10}{dd.size:>10}{100 * dd.size / total_land_edges:>8.1f}%'
                  f'{np.percentile(dd, 50):>9.2f}{np.percentile(dd, 90):>8.2f}'
                  f'{np.percentile(dd, 99):>8.2f}{dd.max():>8.2f}')
        print()


if __name__ == '__main__':
    main()
