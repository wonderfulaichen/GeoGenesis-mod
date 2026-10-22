"""骨架 vs 粒子（源头取自生产骨架段的上游端点）—— 度量下游路线差异。

索引约定（★ 曾在此出错，务必保留）：
  production.png / particle-mask.png 都是 drawLine(x, z) 写的
  ⇒ numpy 数组下标必须是 [row=z, col=x]，不是 [x, z]。
"""
import sys
import numpy as np
from PIL import Image
from scipy import ndimage

RIVER = (0x00, 0xE5, 0xD0)
CROP_X, CROP_Z = -840, -563


def main():
    prod = np.asarray(Image.open(sys.argv[1]).convert("RGB"), dtype=np.int16)
    pm = np.asarray(Image.open(sys.argv[2]).convert("RGB"), dtype=np.int16)

    river = (np.abs(prod - np.array(RIVER, dtype=np.int16)).sum(axis=2) <= 30)
    part = pm[:, :, 0] > 100          # 掩码是灰度图，三通道相同，取 R 即可

    print(f"riverCells={int(river.sum())}  particlePixels={int(part.sum())}")
    if not river.any() or not part.any():
        return 1

    # 对照实验：平移粒子掩码后覆盖率必须崩掉
    print("\n=== 对照实验（平移粒子掩码）===")
    for sh in (0, 5, 15, 30):
        s = np.zeros_like(part)
        if sh == 0:
            s = part
        else:
            s[:, sh:] = part[:, :-sh]
        dd = ndimage.distance_transform_edt(~s)
        dr = dd[river]
        print(f"   shift={sh:3d}  河格->粒子 <=8块 = {100.0*(dr<=8).mean():5.1f}%")

    d2p = ndimage.distance_transform_edt(~part)
    d2r = ndimage.distance_transform_edt(~river)
    dr = d2p[river]
    dp = d2r[part]

    print("\n[河格 -> 粒子] 骨架偏离参照")
    for q in (50, 75, 90, 95):
        print(f"   P{q} = {np.percentile(dr, q):6.2f} 块")
    print(f"   mean = {dr.mean():6.2f}")

    print("\n[粒子 -> 河格] 参照被覆盖")
    for q in (50, 75, 90, 95):
        print(f"   P{q} = {np.percentile(dp, q):6.2f} 块")
    print(f"   mean = {dp.mean():6.2f}")

    print("\n双向贴合率")
    for tol in (2, 4, 8, 16):
        print(f"   ±{tol:>2}: 河贴粒子 {100.0*(dr<=tol).mean():5.1f}%   "
              f"粒子被河覆盖 {100.0*(dp<=tol).mean():5.1f}%")

    n = river.shape[0]
    print("\n按 z 八等分的「粒子被覆盖率(±8块)」（0=上/北）")
    for b in range(8):
        lo, hi = b * n // 8, (b + 1) * n // 8
        sl = dp[lo:hi]
        print(f"   band {b}: {100.0*(sl<=8).mean():5.1f}%   (粒子像素 {sl.size})")
    return 0


if __name__ == "__main__":
    sys.exit(main())
