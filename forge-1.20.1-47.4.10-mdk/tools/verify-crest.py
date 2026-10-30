"""断言「把谷翻成山、山还是山、0 值为谷」三条。

输入：L2-HIFREQ（原层）、L3-HIFREQ-INV（翻转后），均为 heightFromE 映射后的 Y。
由于 heightFromE 单调，断言在 Y 空间做等价判断。
"""
import sys
from pathlib import Path

import numpy as np

def main():
    root = Path(sys.argv[1])
    size = int(sys.argv[2]) if len(sys.argv) > 2 else 701

    def load(n):
        return np.fromfile(root / f'{n}.f64be.raw', dtype='>f8').reshape(size, size)

    raw = load('L2-HIFREQ')
    inv = load('L3-HIFREQ-INV')

    raw_lo, raw_hi = float(raw.min()), float(raw.max())
    inv_lo, inv_hi = float(inv.min()), float(inv.max())

    print('=== 原层（L2）vs 翻转层（L3）===')
    print(f'  原层 Y {raw_lo:.1f}..{raw_hi:.1f}    翻转层 Y {inv_lo:.1f}..{inv_hi:.1f}')

    # 断言 1：0 值为谷 —— 翻转层的最低点应对应原层的中位（零线 n=0 ↔ u=0.5）
    i_min = np.unravel_index(int(np.argmin(inv)), inv.shape)
    raw_at_inv_min = float(raw[i_min])
    raw_med = float(np.median(raw))
    ok1 = abs(raw_at_inv_min - raw_med) < (raw_hi - raw_lo) * 0.06
    print(f'  [1] 翻转层谷底所在原层值 = {raw_at_inv_min:.1f}（原层中位 {raw_med:.1f}，零线附近 = PASS）'
          if ok1 else f'  [1] FAIL：翻转层谷底在原层 {raw_at_inv_min:.1f}，偏离中位 {raw_med:.1f}')

    # 断言 2：谷（原层最低 10%）被抬高 —— 在翻转层里应高于翻转层中位
    lo_mask = raw < np.percentile(raw, 10)
    hi_mask = raw > np.percentile(raw, 90)
    ok2 = float((inv[lo_mask] > np.median(inv)).mean()) > 0.9
    print(f'  [2] 原谷区(最低10%) 变成高于翻转层中位的比例 = '
          f'{(inv[lo_mask] > np.median(inv)).mean()*100:.1f}%   {"PASS" if ok2 else "FAIL"}')

    # 断言 3：山（原层最高 10%）仍是山 —— 高于翻转层中位
    ok3 = float((inv[hi_mask] > np.median(inv)).mean()) > 0.9
    print(f'  [3] 原山区(最高10%) 仍是高地的比例 = '
          f'{(inv[hi_mask] > np.median(inv)).mean()*100:.1f}%   {"PASS" if ok3 else "FAIL"}')

    all_ok = ok1 and ok2 and ok3
    print('\n=== 结论：', '全部 PASS' if all_ok else '存在 FAIL', '===')
    return 0 if all_ok else 1

if __name__ == '__main__':
    sys.exit(main())
