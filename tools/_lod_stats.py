import os
import sys
import io
import glob


def load(path):
    hs, es = [], []
    with io.open(path, encoding='utf-8') as f:
        for line in f:
            line = line.strip()
            if not line or line.startswith('#'):
                continue
            p = line.split()
            hs.append(float(p[2]))
            es.append(float(p[3]))
    return hs, es


def main():
    d = sys.argv[1] if len(sys.argv) > 1 else r'forge-1.20.1-47.4.10-mdk/build/lod-tsv'
    files = sorted(glob.glob(os.path.join(d, 'lod_*.tsv')),
                   key=lambda p: int(os.path.basename(p)[4:-4]))
    print('%-6s %-8s %-9s %-9s %-9s %-9s' % ('LOD', '水格%', '水位格', '最高', '最低', '高差'))
    for p in files:
        lv = os.path.basename(p)[4:-4]
        hs, es = load(p)
        n = len(hs)
        water = sum(1 for e in es if e < 0)
        land_h = [h for h, e in zip(hs, es) if e >= 0]
        hi = max(hs) if hs else 0
        lo = min(hs) if hs else 0
        print('%-6s %-8.2f %-9d %-9.1f %-9.1f %-9.1f'
              % (lv, 100.0 * water / n, water, hi, lo, hi - lo))


if __name__ == '__main__':
    main()
