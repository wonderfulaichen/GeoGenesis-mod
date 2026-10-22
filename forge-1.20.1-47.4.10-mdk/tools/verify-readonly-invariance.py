"""验证：只读的源头埋点是否改变了生产产出（逐像素比对）。

基准图 = 加埋点【之前】的生产图；对照图 = 加埋点【之后】、同参数重跑的生产图。
若逐像素完全一致 => 埋点零影响，基线有效。
"""
import sys
import numpy as np
from PIL import Image


def main():
    a_path, b_path = sys.argv[1], sys.argv[2]
    a = np.asarray(Image.open(a_path).convert("RGB"), dtype=np.int16)
    b = np.asarray(Image.open(b_path).convert("RGB"), dtype=np.int16)
    print(f"base = {a_path}")
    print(f"new  = {b_path}")

    if a.shape != b.shape:
        print(f"[FAIL] 尺寸不同 {a.shape} vs {b.shape}")
        return 1

    diff = np.abs(a - b).sum(axis=2)
    changed = int((diff > 0).sum())
    total = a.shape[0] * a.shape[1]
    print(f"\nimage = {a.shape[1]}x{a.shape[0]}  pixels = {total}")
    print(f"different pixels = {changed}  ({100.0 * changed / total:.4f}%)")

    if changed == 0:
        print("\n[PASS] 逐像素完全一致 => 埋点零影响，基线有效")
        return 0
    print("\n[FAIL] 存在像素差异 => 埋点改变了产出，不可作为只读载荷")
    ys, xs = np.nonzero(diff > 0)
    print(f"  bbox x[{xs.min()},{xs.max()}] y[{ys.min()},{ys.max()}]")
    return 1


if __name__ == "__main__":
    sys.exit(main())
