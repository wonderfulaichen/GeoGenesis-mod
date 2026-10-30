"""诊断 hills 变体：各频带能量、各向异性、平坦度。定位"发软/条纹"由哪个频带造成。

用法：python tools/diagnose-hills.py <outDir> arms...
"""
import json
import sys
from pathlib import Path

import numpy as np

STEP = 5.0
SEA = 63.0


def banda(arr, step):
    """按波长分带统计 |grad| 能量占比。"""
    F = np.fft.fftshift(np.abs(np.fft.fft2(arr - arr.mean()))) ** 2
    n = arr.shape[0]
    fy = np.fft.fftshift(np.fft.fftfreq(n, d=step))
    fx = np.fft.fftshift(np.fft.fftfreq(n, d=step))
    FX, FY = np.meshgrid(fx, fy)
    f = np.hypot(FX, FY)
    with np.errstate(divide='ignore'):
        wl = np.where(f > 0, 1.0 / f, np.inf)
    bands = [('>1000 块', 1000, 1e9), ('300-1000', 300, 1000),
             ('100-300', 100, 300), ('40-100', 40, 100), ('<40 块', 0, 40)]
    total = F.sum()
    out = []
    for name, lo, hi in bands:
        m = (wl >= lo) & (wl < hi)
        out.append((name, F[m].sum() / total * 100))
    return out


def anisotropy(arr, step):
    """梯度的方向一致性：0 = 各向同性，1 = 完全单向（条纹）。"""
    gz, gx = np.gradient(arr, step)
    mag = np.hypot(gx, gz)
    keep = mag > np.percentile(mag, 50)
    gx, gz = gx[keep], gz[keep]
    # 归一化后的平均方向向量的模长（结构张量的简化版）
    ang = np.arctan2(gz, gx)
    return float(np.abs(np.exp(2j * ang).mean()))


def main():
    root = Path(sys.argv[1])
    geo = json.loads((root / 'geometry.json').read_text(encoding='utf-8'))
    size = geo['size']

    print(f'{"arm":22s} {"relief":>7s} {"坡P50":>6s} {"坡P90":>6s} {"平坦%":>6s} {"各向异性":>8s}')
    rows = []
    for arm in sys.argv[2:]:
        p = root / f'{arm}.f64be.raw'
        if not p.exists():
            print(f'{arm}: MISSING')
            continue
        h = np.fromfile(p, dtype='>f8').reshape(size, size)
        gz, gx = np.gradient(h, STEP)
        slope = np.hypot(gx, gz)
        flat = (np.abs(np.diff(h, axis=0)) < 0.3).mean() * 100
        rows.append((arm, h, slope))
        print(f'{arm:22s} {h.max() - h.min():7.1f} {np.percentile(slope, 50):6.2f}'
              f' {np.percentile(slope, 90):6.2f} {flat:6.0f} {anisotropy(h, STEP):8.3f}')

    print()
    print('频带能量占比（波长）：')
    hdr = '  '.join(f'{n:>11s}' for n, *_ in banda(rows[0][1], STEP))
    print(f'{"arm":22s} {hdr}')
    for arm, h, _ in rows:
        cells = '  '.join(f'{v:10.1f}%' for _, v in banda(h, STEP))
        print(f'{arm:22s} {cells}')


if __name__ == '__main__':
    main()
