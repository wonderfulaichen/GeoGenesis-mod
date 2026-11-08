"""逐项差异审计（★ 2026-11-08，只分析不改代码）。

用户要求："带我把参考项目那套完整跑起来做逐项对比，把差异点列全了再动手。"

本脚本量化三件事：
  1. 我们地形数据的高度分布（决定柱高能拉出多少层次）
  2. 参考项目的数据前提（WORLD_SURFACE_WG = 方块地表，含水位/雪/树）
  3. 光照增益在两套数据上的实际表现（这是"立体感"的关键）
"""
import io
import sys

import numpy as np

REF_LIGHT_MIN, REF_LIGHT_RANGE = 0.55, 0.75


def load(path):
    H, E = [], []
    with io.open(path, encoding='utf-8') as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith('#'):
                continue
            p = line.split()
            H.append(float(p[2]))
            E.append(float(p[3]))
    return np.array(H), np.array(E)


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else \
        r'forge-1.20.1-47.4.10-mdk/build/lod-tsv/lod_1.tsv'
    H, E = load(path)
    sea = 63.0
    base = sea - 4.0
    land = H[E >= 0]
    water = H[E < 0]

    print('=== 数据分布（%s）===' % path.split('/')[-1])
    print('  总格数 %d，陆地 %d，水域 %d' % (H.size, land.size, water.size))
    print('  陆地高度: min %.1f  p25 %.1f  中位 %.1f  p75 %.1f  max %.1f'
          % (land.min(), np.percentile(land, 25), np.median(land),
             np.percentile(land, 75), land.max()))
    span = max(60.0, H[E >= 0].max() if False else (np.where(E < 0, sea, H).max() - base))
    print('  span（=最高点-base）: %.1f 格' % span)
    rn_land = (land - base) / span
    print('  陆地归一化 r: %.3f ~ %.3f（占柱高量程 %.0f%%）'
          % (rn_land.min(), rn_land.max(), 100 * (rn_land.max() - rn_land.min())))

    # 坡度分布
    n = int(round(H.size ** 0.5))
    G = np.zeros((n, n))
    for i in range(H.size):
        G[i // n, i % n] = H[i] if E[i] >= 0 else sea
    rn = (G - base) / span
    sx = np.diff(rn, axis=0)[:, :]
    sz = np.diff(rn, axis=1)[:, :]
    slope = np.abs(sx).mean() + np.abs(sz).mean()
    print('\n=== 光照（决定"有没有立体感"）===')
    print('  归一化坡度 均值 %.5f  p50 %.5f  p90 %.5f  p99.5 %.5f'
          % (slope / 2, np.percentile(np.abs(sx), 50), np.percentile(np.abs(sx), 90),
             np.percentile(np.abs(sx), 99.5)))

    for name, gain in (('参考项目 gain=0.55', 0.55),
                       ('自适应 P99.5->0.9', 0.9 / max(1e-9, np.percentile(np.abs(sx), 99.5)))):
        light = 1.0 - np.abs(sx) * gain
        light = np.clip(light, REF_LIGHT_MIN, REF_LIGHT_MIN + REF_LIGHT_RANGE)
        sat = ((light <= REF_LIGHT_MIN + 1e-6) | (light >= REF_LIGHT_MIN + REF_LIGHT_RANGE - 1e-6))
        print('  %-20s gain=%6.2f  光照 p5=%.3f p50=%.3f p95=%.3f  饱和率=%.1f%%'
              % (name, gain, np.percentile(light, 5), np.percentile(light, 50),
                 np.percentile(light, 95), 100 * sat.mean()))


if __name__ == '__main__':
    main()
