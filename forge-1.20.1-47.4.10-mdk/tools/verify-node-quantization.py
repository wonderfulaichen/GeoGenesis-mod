"""核验节点几何（用户：根据这图应该能判断清楚问题）。

数据源必须是 shape() 输出的【亚格锚点】nodes.csv（带小数），
不是 Reach.cell 的格路径（那恒为 STEP 整数倍，几何改动在上面看不出来）。

三项判据：
  ① x%STEP / z%STEP 的分布      -> 节点是否仍被量化到网格
  ② 段内相邻节点的方向角种类数   -> 方向是否连续
  ③ 端点与他段共享/悬空比例      -> 汇流结构是否成立
"""
import sys
import math
from collections import Counter, defaultdict

STEP = 4
path = sys.argv[1] if len(sys.argv) > 1 else "build/skeleton-nodes/nodes.csv"

segs = defaultdict(list)
with open(path, encoding="utf-8") as f:
    next(f)
    for line in f:
        line = line.strip()
        if not line:
            continue
        sid, x, z, order = line.split(",")
        segs[int(sid)].append((float(x), float(z), int(order)))

total_nodes = sum(len(v) for v in segs.values())
print(f"段数 = {len(segs)}  节点总数 = {total_nodes}")

# ① 量化程度
on_grid = 0
for pts in segs.values():
    for (x, z, _) in pts:
        if abs(x % STEP) < 0.01 and abs(z % STEP) < 0.01:
            on_grid += 1
print(f"\n① 落在 STEP 网格上的节点: {on_grid}/{total_nodes} "
      f"= {100.0*on_grid/max(1,total_nodes):.1f}%")
print(f"   => {'仍全部量化' if on_grid == total_nodes else '已可离开网格'}")

# ② 方向角种类（按 5° 分箱）
bins = Counter()
steps = 0
for pts in segs.values():
    pts = sorted(pts, key=lambda t: t[2])
    for i in range(1, len(pts)):
        dx = pts[i][0] - pts[i - 1][0]
        dz = pts[i][1] - pts[i - 1][1]
        if abs(dx) < 1e-6 and abs(dz) < 1e-6:
            continue
        ang = math.degrees(math.atan2(dz, dx)) % 360
        bins[int(ang // 5)] += 1
        steps += 1
print(f"\n② 段内步进方向（{steps} 步，按 5° 分箱）")
print(f"   占用的角度箱数 = {len(bins)} / 72")
top = bins.most_common(8)
print("   最常见角度:")
for b, c in top:
    print(f"     {b*5:3d}-{b*5+5:3d}°  {c:6d}  ({100.0*c/max(1,steps):5.1f}%)")
print(f"   => {'方向仍集中在少数几档（离散）' if len(bins) <= 12 else '方向已铺开（连续化）'}")

# ③ 端点连接（用容差匹配：连续坐标不会精确相等）
tol = float(sys.argv[2]) if len(sys.argv) > 2 else 1.0
ends_total = 0
shared = 0
dangling = 0
by_seg = []
for sid, pts in segs.items():
    pts = sorted(pts, key=lambda t: t[2])
    if len(pts) < 2:
        continue
    by_seg.append((sid, pts[0], pts[-1]))

for sid, p0, p1 in by_seg:
    for p in (p0, p1):
        ends_total += 1
        hit = False
        for osid, o in segs.items():
            if osid == sid:
                continue
            for (ox2, oz2, _) in o:
                if abs(ox2 - p[0]) <= tol and abs(oz2 - p[1]) <= tol:
                    hit = True
                    break
            if hit:
                break
        if hit:
            shared += 1
        else:
            dangling += 1

print(f"\n③ 段端点连接（容差 {tol} 块）")
print(f"   端点总数 = {ends_total}")
print(f"   接上他段 = {shared} ({100.0*shared/max(1,ends_total):.1f}%)")
print(f"   悬空     = {dangling} ({100.0*dangling/max(1,ends_total):.1f}%)")
