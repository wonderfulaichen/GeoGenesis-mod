"""核验：从【生产骨架段】推出的源头，是否落在【生产图】的河格上。

⚠ 已修正一个致命索引错误：production.png 是 img.setRGB(x, z) 写的，
   即 **x = 列(width)、z = 行(height)**；而 numpy 是 [row, col]。
   旧代码写 `d[px, pz]`（= [x, z]）=> 实际按转置查表 => 结论完全作废。
   正确写法：`d[pz, px]`。
"""
import sys
import numpy as np
from PIL import Image
from scipy import ndimage

RIVER = (0x00, 0xE5, 0xD0)
CROP_X, CROP_Z = -840, -563

prod_path = sys.argv[1] if len(sys.argv) > 1 else \
    "build/hydro-production/prod-1792535678596/production.png"
roots_path = sys.argv[2] if len(sys.argv) > 2 else "build/rooted/roots.csv"

prod = np.asarray(Image.open(prod_path).convert("RGB"), dtype=np.int16)
river = (np.abs(prod - np.array(RIVER, dtype=np.int16)).sum(axis=2) <= 30)
print(f"image={prod.shape[:2]} (rows=z, cols=x)  riverCells={int(river.sum())}")

d = ndimage.distance_transform_edt(~river)

rows = open(roots_path, encoding="utf-8").read().strip().split("\n")[1:]
print(f"roots={len(rows)}")

b = [0] * 6
for r in rows:
    x, z = [float(v) for v in r.split(",")[:2]]
    px, pz = int(round(x)) - CROP_X, int(round(z)) - CROP_Z
    if px < 0 or pz < 0 or px >= river.shape[1] or pz >= river.shape[0]:
        b[5] += 1
        continue
    dd = d[pz, px]                      # ★ [row=z, col=x]
    b[0 if dd == 0 else 1 if dd <= 1 else 2 if dd <= 2 else 3 if dd <= 4 else 4 if dd <= 8 else 5] += 1

tot = max(1, len(rows))
for lb, c in zip(["落在河格上", "<=1块", "<=2块", "<=4块", "<=8块", ">8块/图外"], b):
    print(f"  {lb:12s}: {c:6d}  ({100.0*c/tot:5.1f}%)")
print(f"\n<=2 块合计: {100.0*(b[0]+b[1]+b[2])/tot:.1f}%")
