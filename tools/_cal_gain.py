"""用真实地形数据反推 calibratedGain 的正确标定系数。"""
import sys
import numpy as np

sys.path.insert(0, 'tools')
import render_sandbox_offline as R

print('%-6s %-6s %-10s %-10s %-12s %-12s' %
      ('step', 'grid', 'p995', 'p9999', 'theorMax', 'span'))

for lv in range(5):
    h, e, b, t, sn, f = R.load('forge-1.20.1-47.4.10-mdk/build/lod-tsv/lod_%d.tsv' % lv)
    gx = h.shape[0]
    sea = 63.0
    base = sea - 4.0
    surface = np.where(e < 0.0, sea, h)
    span = max(60.0, float(surface.max()) - base)
    r = (surface - base) / span

    left = np.roll(r, 1, axis=0); left[0] = r[0]
    right = np.roll(r, -1, axis=0); right[-1] = r[-1]
    up = np.roll(r, 1, axis=1); up[:, 0] = r[:, 0]
    down = np.roll(r, -1, axis=1); down[:, -1] = r[:, -1]
    slope = np.abs((right - left) * 0.5 + (down - up) * 0.5)

    p995 = float(np.quantile(slope, 0.995))
    p9999 = float(np.quantile(slope, 0.9999))
    print('%-6d %-6d %-10.5f %-10.5f %-12.5f %-12.1f'
          % (lv, gx, p995, p9999, 1.0 / gx, span))
