import io
import os
import sys
import glob


def load(path):
    rows = []
    with io.open(path, encoding='utf-8') as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith('#'):
                continue
            p = line.split()
            rows.append((int(p[0]), int(p[1]), float(p[2]), float(p[3])))
    return rows


def main():
    d = sys.argv[1] if len(sys.argv) > 1 else r'forge-1.20.1-47.4.10-mdk/build/lod-tsv'
    files = sorted(glob.glob(os.path.join(d, 'lod_*.tsv')),
                   key=lambda p: int(os.path.basename(p)[4:-4]))
    print('%-5s %-9s %-9s %-9s %-11s %-9s' % ('LOD', 'h.min', 'h.max', 'h高差', '相邻行差均值', '不同高度数'))
    for p in files:
        lv = os.path.basename(p)[4:-4]
        rows = load(p)
        hs = [r[2] for r in rows]
        n = int(round(len(rows) ** 0.5))
        # 重建网格
        grid = {}
        for gx, gz, h, e in rows:
            grid[(gx, gz)] = h
        # 沿 gz 方向的相邻差
        diffs = []
        for gx in range(n):
            for gz in range(n - 1):
                a = grid.get((gx, gz))
                b = grid.get((gx, gz + 1))
                if a is not None and b is not None:
                    diffs.append(abs(b - a))
        diffs.sort()
        mean_d = sum(diffs) / len(diffs) if diffs else 0
        uniq = len(set(round(h, 3) for h in hs))
        print('%-5s %-9.2f %-9.2f %-9.2f %-11.4f %-9d'
              % (lv, min(hs), max(hs), max(hs) - min(hs), mean_d, uniq))


if __name__ == '__main__':
    main()
