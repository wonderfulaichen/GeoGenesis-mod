"""两目录同臂逐字节对比：确认回退后基准完全一致。

用法：python tools/compare-arms.py <dirA> <dirB> [臂名...]
"""
import sys
from pathlib import Path

import numpy as np


def load(root, arm, size):
    return np.fromfile(Path(root) / f'{arm}.f64be.raw', dtype='>f8').reshape(size, size)


def main():
    a, b = sys.argv[1], sys.argv[2]
    arms = sys.argv[3:] or ['compose-coastal', 'compose-coastal-islands', 'compose-regional']

    import json
    geo = json.loads((Path(a) / 'compose-geometry.json').read_text(encoding='utf-8'))
    size = geo['size']

    print(f'A = {a}')
    print(f'B = {b}\n')
    print(f'{"臂":<26}{"identical%":>12}{"max|Δ|":>14}{"mean|Δ|":>12}{"|Δ|>1 %":>10}')

    for arm in arms:
        pa, pb = Path(a) / f'{arm}.f64be.raw', Path(b) / f'{arm}.f64be.raw'
        if not pa.exists() or not pb.exists():
            print(f'{arm:<26}  缺文件')
            continue
        ha, hb = load(a, arm, size), load(b, arm, size)
        d = np.abs(ha - hb)
        ident = (d < 1e-9).mean() * 100
        print(f'{arm:<26}{ident:>11.4f}%{d.max():>14.6e}{d.mean():>12.6e}'
              f'{(d > 1).mean() * 100:>9.2f}%')


if __name__ == '__main__':
    main()
