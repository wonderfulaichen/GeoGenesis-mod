import io
import os
import sys
import glob

LIGHT_GAIN = 8.0
LIGHT_MIN, LIGHT_MAX = 0.55, 1.30


def load(path):
    grid = {}
    n = 0
    with io.open(path, encoding='utf-8') as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith('#'):
                continue
            p = line.split()
            gx, gz, h, e = int(p[0]), int(p[1]), float(p[2]), float(p[3])
            grid[(gx, gz)] = (h, e)
            n = max(n, gx + 1, gz + 1)
    return grid, n


def main():
    d = sys.argv[1] if len(sys.argv) > 1 else r'forge-1.20.1-47.4.10-mdk/build/lod-tsv'
    sea = 63.0
    base = sea - 4.0
    files = sorted(glob.glob(os.path.join(d, 'lod_*.tsv')),
                   key=lambda p: int(os.path.basename(p)[4:-4]))
    print('%-5s %-8s %-10s %-10s %-10s %-10s' %
          ('LOD', 'span', 'r范围', '饱和率%', 'slope均', 'glitch%'))
    for p in files:
        lv = os.path.basename(p)[4:-4]
        grid, n = load(p)
        hi = max(v[0] for v in grid.values())
        span = max(60.0, hi - base)
        # 归一化 r（水域用海平面）
        r = {}
        for (gx, gz), (h, e) in grid.items():
            surf = sea if e < 0 else h
            r[(gx, gz)] = (surf - base) / span
        sat = 0
        slopes = []
        glitch = 0
        cnt = 0
        for gx in range(n):
            for gz in range(n):
                if (gx, gz) not in r:
                    continue
                h0 = r[(gx, gz)]
                l = r.get((gx - 1, gz), h0)
                rt = r.get((gx + 1, gz), h0)
                up = r.get((gx, gz - 1), h0)
                dn = r.get((gx, gz + 1), h0)
                slope = (rt - l) * 0.5 + (dn - up) * 0.5
                slopes.append(abs(slope))
                light = 1.0 - slope * LIGHT_GAIN
                if light <= LIGHT_MIN or light >= LIGHT_MAX:
                    sat += 1
                # 「相邻两柱一黑一白」= 台阶伪影
                if abs(slope) * LIGHT_GAIN > 0.5:
                    glitch += 1
                cnt += 1
        slopes.sort()
        rmin = min(r.values())
        rmax = max(r.values())
        print('%-5s %-8.1f %-10s %-10.1f %-10.5f %-10.1f'
              % (lv, span, '%.3f~%.3f' % (rmin, rmax),
                 100.0 * sat / max(1, cnt),
                 sum(slopes) / max(1, len(slopes)),
                 100.0 * glitch / max(1, cnt)))


if __name__ == '__main__':
    main()
