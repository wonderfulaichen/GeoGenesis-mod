"""量化岛体边缘坡度，并检查是否因 u 饱和而产生平台。

用法：python tools/measure-island-slope.py <outDir>
"""
import sys
from pathlib import Path

import numpy as np
from scipy import ndimage as ndi

SEA = 63.0
TYPE_HILLS = 2


def main():
    root = Path(sys.argv[1])
    size, step = 1401, 5

    new = np.fromfile(root / 'compose-coastal-islands.f64be.raw', dtype='>f8').reshape(size, size)
    base = np.fromfile(root / 'compose-coastal.f64be.raw', dtype='>f8').reshape(size, size)
    only = np.fromfile(root / 'compose-islands-only.f64be.raw', dtype='>f8').reshape(size, size)
    types = np.fromfile(root / 'types-compose-coastal-islands.u8', dtype=np.uint8).reshape(size, size)

    gz, gx = np.gradient(new, step)
    slope = np.hypot(gx, gz)

    island = (new >= SEA) & (base < SEA)
    land_hill = (base >= SEA) & (types == TYPE_HILLS)

    # 岛体内部（腐蚀掉边缘）与岛缘环带
    inner = ndi.binary_erosion(island, iterations=6)
    edge = island & ~inner

    print('=== 坡度对比（|grad|，格/块）===')
    for name, m in (('岛缘环带（岛内陆侧 30 块）', edge),
                    ('岛内部（腐蚀 30 块后）', inner),
                    ('陆地丘陵（全部）', land_hill)):
        if m.sum() < 50:
            print(f'{name:32s} 样本不足')
            continue
        s = slope[m]
        print(f'{name:32s} n={m.sum():7d}  P50 {np.percentile(s,50):5.2f}'
              f'  P90 {np.percentile(s,90):5.2f}  P99 {np.percentile(s,99):5.2f}'
              f'  max {s.max():5.2f}')

    print('\n=== 平台/侧壁判据 ===')
    # 平台：岛内相邻同高（1 格内）的比例
    dz = np.abs(np.diff(new, axis=0))
    flat = (dz < 0.35)
    print(f'岛缘环带「近平」比例      : {flat[edge[:-1, :]].mean() * 100:5.1f}%')
    print(f'岛内部「近平」比例        : {flat[inner[:-1, :]].mean() * 100:5.1f}%')
    print(f'陆地丘陵「近平」比例      : {flat[land_hill[:-1, :]].mean() * 100:5.1f}%')

    # 抬升增量（islands-only 已减海平面）的分布：看是否顶到上限
    inc = only - SEA
    pos = inc[inc > 0.5]
    print(f'\n增量分布（正的部分）: P50 {np.percentile(pos,50):.1f}'
          f'  P90 {np.percentile(pos,90):.1f}  P99 {np.percentile(pos,99):.1f}'
          f'  max {pos.max():.1f} 格')
    print(f'增量 > 40 格的面积占比     : {(inc > 40).mean() * 100:.2f}%')
    print(f'增量 > 45 格的面积占比     : {(inc > 45).mean() * 100:.2f}%')

    # 出水岛的峰高分布（平顶的证据：多个岛同高）
    lab, n = ndi.label(island, structure=np.ones((3, 3)))
    sizes = np.bincount(lab.ravel())
    peaks = []
    for i in range(1, n + 1):
        if sizes[i] < 400:
            continue
        peaks.append(float(new[lab == i].max()))
    if peaks:
        peaks = np.array(peaks)
        print(f'\n面积≥400px 的岛数          : {len(peaks)}')
        print(f'峰高 P10/P50/P90/max       : {np.percentile(peaks,10):.1f} / '
              f'{np.percentile(peaks,50):.1f} / {np.percentile(peaks,90):.1f} / {peaks.max():.1f}')
        hist, edges = np.histogram(peaks, bins=10)
        print('峰高直方（若集中在少数值 = 上限截断）:')
        for h, e in zip(hist, edges[:-1]):
            print(f'  {e:6.1f} 格 : {"#" * h} {h}')


if __name__ == '__main__':
    main()
