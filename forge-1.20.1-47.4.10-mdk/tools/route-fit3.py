"""逐条路线贴合度（第三版，修正「100% 贴合率」的度量缺陷）。

★ 前两版的缺陷（用户：「你这个贴合率中位 100% 明显就是 bug」）：
  容差 = max(4.0, 骨架长 × 5%)。骨架短（如 40 块）时容差 = 4 块，
  而骨架点间距 2 块 ⇒ 只要整条骨架都在物理线 4 块内，就是 100%。
  ⇒ 「100%」只能说明"没有大幅跑偏"，不能说明"贴合好"。
  且 len(S)/len(P) 很小（骨架提前终止）时，采样点全落在物理线的前段，
  天然容易"贴"。

★ 本版改为：
  1. **固定容差**（默认 4 wu），不随骨架长度缩放 —— 让不同长度的路线可比；
  2. 同时报告 **覆盖率**（物理线被骨架覆盖的比例），
     避免「骨架只走 1/5 就 100% 贴合」被当成好结果；
  3. 报告 **方向夹角**（沿弧长 16 wu 窗口的中位夹角）—— 用户指出
     「一部分路线有明显的夹角」，那才是真问题，距离度量测不出来。
"""
import sys
import math
from collections import defaultdict

path = sys.argv[1] if len(sys.argv) > 1 else "build/abc-ca2/routes.csv"
TOL = float(sys.argv[2]) if len(sys.argv) > 2 else 4.0
RESAMPLE = 2.0
DIR_WIN = 16.0     # 方向夹角窗口（wu）

routes = defaultdict(dict)
with open(path, encoding="utf-8") as f:
    next(f)
    for line in f:
        line = line.strip()
        if not line:
            continue
        rid, rx, rz, kind, order, x, z = line.split(",")
        routes[int(rid)].setdefault(kind, []).append((float(x), float(z), int(order)))


def seg_dist(p, a, b):
    px, pz = p; ax, az = a; bx, bz = b
    dx, dz = bx - ax, bz - az
    l2 = dx * dx + dz * dz
    t = 0.0 if l2 <= 0 else ((px - ax) * dx + (pz - az) * dz) / l2
    t = max(0.0, min(1.0, t))
    return math.hypot(px - (ax + t * dx), pz - (az + t * dz))


def d2poly(p, poly):
    if len(poly) == 1:
        return math.hypot(p[0] - poly[0][0], p[1] - poly[0][1])
    return min(seg_dist(p, poly[i-1], poly[i]) for i in range(1, len(poly)))


def resample(pts, sp):
    if len(pts) < 2:
        return list(pts)
    out = [pts[0]]
    acc = 0.0
    for i in range(1, len(pts)):
        ax, az = pts[i-1]; bx, bz = pts[i]
        sl = math.hypot(bx-ax, bz-az)
        if sl <= 1e-12:
            continue
        t = 0.0
        while acc + (sl - t) >= sp:
            need = sp - acc
            t += need
            acc = 0.0
            r = t / sl
            out.append((ax + (bx-ax)*r, az + (bz-az)*r))
        acc += sl - t
    if out[-1] != pts[-1]:
        out.append(pts[-1])
    return out


def arc(pts):
    return sum(math.hypot(pts[i][0]-pts[i-1][0], pts[i][1]-pts[i-1][1]) for i in range(1, len(pts)))


def heading(pts, i, j):
    return math.atan2(pts[j][1]-pts[i][1], pts[j][0]-pts[i][0])


def angdiff(a, b):
    d = math.degrees(a - b)
    while d > 180: d -= 360
    while d < -180: d += 360
    return abs(d)


def median_heading_angle(S, P):
    """沿骨架每 DIR_WIN 弧长，比较骨架与物理在该处的局部方向夹角。"""
    Sr = resample(S, 1.0)
    Pr = resample(P, 1.0)
    if len(Sr) < 4 or len(Pr) < 4:
        return None
    step = max(1, int(DIR_WIN))
    angles = []
    for i in range(0, len(Sr) - step, step):
        sp = Sr[i]; sq = Sr[i+step]
        # 物理线上离 sp 最近的点，取该处往后 step 的方向
        best = min(range(len(Pr)), key=lambda k: math.hypot(Pr[k][0]-sp[0], Pr[k][1]-sp[1]))
        if best + step >= len(Pr):
            continue
        if math.hypot(Pr[best][0]-sp[0], Pr[best][1]-sp[1]) > 40:
            continue
        a1 = heading(Sr, i, i+step)
        a2 = heading(Pr, best, best+step)
        angles.append(angdiff(a1, a2))
    if not angles:
        return None
    angles.sort()
    return angles[len(angles)//2]


rows = []
for rid, d in sorted(routes.items()):
    if "P" not in d or "S" not in d:
        continue
    P = [p[:2] for p in sorted(d["P"], key=lambda t: t[2])]
    S = [p[:2] for p in sorted(d["S"], key=lambda t: t[2])]
    if len(P) < 2 or len(S) < 2:
        continue

    Sr = resample(S, RESAMPLE)
    Pr = resample(P, RESAMPLE)
    ds = [d2poly(p, P) for p in Sr]
    fit = sum(1 for v in ds if v <= TOL) / len(ds)
    med = sorted(ds)[len(ds)//2]

    # 覆盖率：物理线采样点中，落在骨架 TOL 内的比例
    dp = [d2poly(p, S) for p in Pr]
    cov = sum(1 for v in dp if v <= TOL) / len(dp)

    ang = median_heading_angle(S, P)
    rows.append({"rid": rid, "fit": fit, "med": med, "cov": cov, "ang": ang,
                 "ls": arc(S), "lp": arc(P)})

n = len(rows)


def q(vals, p):
    v = sorted(vals)
    return v[min(len(v)-1, int(p*len(v)))]


print(f"配对路线 = {n}   固定容差 = {TOL} wu   方向窗口 = {DIR_WIN} wu\n")

print("=== ① 贴合率（骨架采样点中距物理 ≤ 4 wu 的占比，固定容差）===")
fits = [r["fit"] for r in rows]
print(f"  中位 {q(fits,.5)*100:5.1f}%   均值 {sum(fits)/n*100:5.1f}%")
for lo, hi, name in [(0.95,1.01,"≈100%"),(0.8,0.95,"80~95%"),(0.5,0.8,"50~80%"),(0.0,0.5,"<50%")]:
    c = sum(1 for v in fits if lo <= v < hi)
    print(f"  {name:10s} {c:3d} 条 ({100.0*c/n:5.1f}%)")

print("\n=== ② 覆盖率（物理线被骨架覆盖的比例）—— 防「短骨架假贴合」===")
covs = [r["cov"] for r in rows]
print(f"  中位 {q(covs,.5)*100:5.1f}%   均值 {sum(covs)/n*100:5.1f}%")

print("\n=== ③ 方向夹角（沿弧长 16 wu 窗口的中位夹角）===")
angs = [r["ang"] for r in rows if r["ang"] is not None]
if angs:
    print(f"  可比路线 {len(angs)}   中位 {q(angs,.5):5.1f}°   均值 {sum(angs)/len(angs):5.1f}°")
    for lo, hi, name in [(0,10,"≤10° 几乎同向"),(10,30,"10~30° 略偏"),
                         (30,60,"30~60° 明显夹角"),(60,180,">60° 方向不同")]:
        c = sum(1 for v in angs if lo <= v < hi)
        print(f"  {name:18s} {c:3d} 条 ({100.0*c/len(angs):5.1f}%)")

print("\n=== ④ 最差 15 条（按方向夹角排序）===")
print(f"{'route':>6} {'夹角':>7} {'贴合率':>8} {'覆盖率':>8} {'S长':>7} {'P长':>7}")
for r in sorted([x for x in rows if x["ang"] is not None], key=lambda x: -x["ang"])[:15]:
    print(f"{r['rid']:>6} {r['ang']:>6.1f}° {r['fit']*100:>7.1f}% "
          f"{r['cov']*100:>7.1f}% {r['ls']:>7.0f} {r['lp']:>7.0f}")
