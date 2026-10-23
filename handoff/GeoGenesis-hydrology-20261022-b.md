# GeoGenesis 水文骨架 —— 交接文档 B（2026-10-22 晚）

> 接手方假设：有经验的 Java/Minecraft 模组工程师，**不能**随时问原作者。
> 本文承接 [GeoGenesis-hydrology-20261022.md](GeoGenesis-hydrology-20261022.md)（上一份），
> 只写**这之后发生的事**。未核实的明确标 `未验证`。

---

## 1. 一句话结论

**运动向量（重力+惯性）已实现并跑通。生产图是树状水系，`DEAD_END` 从 52 降到 2~4。**
**但有一个已知缺陷：几何仍有「阶梯感」，见 §5。**

---

## 2. 已提交 vs 未提交

| 内容 | 状态 |
|---|---|
| `3c94a90` 方向场向量化 + 河流完整性修复 | ✅ **已推送**到 `origin/backup/pre-legacy-chain-removal-20261020` |
| 本次「运动向量（重力+惯性）」改动 | ❌ **未提交**（工作区未暂存） |

工程根：`D:\Office software\Development Project\GeoGenesis-mod`
真工程：`forge-1.20.1-47.4.10-mdk\`

---

## 3. 本次改动（未提交）

### 3.1 核心：`MOTION_VECTOR` —— 位置主、格派生

文件：`worldgen/hydrology/model/DeterministicSkeleton.java`

```
vel  = vel·INERTIA − ∇bed·(1−INERTIA)      // 惯性 0.90 + 重力
spd  = sqrt(spd² + |Δh|·GRAVITY)           // GRAVITY=2.5
cur += normalize(vel)·min(spd, SPD_CAP)    // SPD_CAP=1.0 格
```

**与之前两次失败尝试的区别（关键，别重蹈）**：

| 版本 | 做法 | 结果 |
|---|---|---|
| v1 | `cur` 自由累加，格另走 D8 链 | ❌ 脱钩 ⇒ 生产图变**蜘蛛网** |
| v2 | 每步把 `cur` 硬拉回格心 0.5 格内 | ❌ `cur` 恒等格心 ⇒ **完全无效** |
| **v3（当前）** | `cur` 自由走，仅当偏离 > **1.5 格**（`MOTION_SNAP_RADIUS`）才兜底拉回 | ✅ 有效 |

### 3.2 关键实现细节（都是踩过坑才对的）

1. **`contPts` 的写入时机**：旧版在循环开头 `add(cur)`，那时 `cur` 还是上一步的值
   ⇒ 整体慢一拍 ⇒ 几何错位（实测 idx1 偏离 2.828）。
   现改为：**格步推进并更新 `cur` 之后**才 add。首点单独记录（`contAligned` 标志）。
2. **所有终止点都要补 add**：JOIN / 湖 / 海 / 溢流逃逸 各分支的 `path.add(x)` 之后
   必须跟一个 `contPts` 写入，否则 `contPath.length < cell.length*2`
   ⇒ `hasContPath()` 判假 ⇒ 整条链退回格心（无效果）。
3. **必须用扁平 `double[]`**：`ArrayList<double[]>` 会让堆爆
   （16B 数组头 + 16B 数据 + 8B 引用 ≈ 40B/点，一条 700 点的链 = 28KB，
   36 个 region 同时缓存 ⇒ `OutOfMemoryError`）。现用 `double[] contBuf` + `contN`，
   16B/点。已实测跑通（321 s，无 OOM）。

### 3.3 开关与默认值

| 开关 | 默认 | 含义 |
|---|---|---|
| `geogenesis.motionVector` | **true** | 运动向量（重力+惯性） |
| `geogenesis.motionInertia` | 0.90 | 惯性系数 |
| `geogenesis.motionGravity` | 2.5 | 重力系数 |
| `geogenesis.motionSpdCap` | 1.0 | 单步位移上限（格） |
| `geogenesis.motionLife` | 20 | 段末转世步数 |
| `geogenesis.motionSnapRadius` | 1.5 | 漂移兜底半径（格） |

---

## 4. 实测数据

### 4.1 生产图

| 项 | 值 |
|---|---|
| 命令 | `java -cp <cp> ...ProductionViewProbe 9139912035078620160 -140 137` |
| 耗时 | **321 s**（基线 251 s，+28%） |
| 输出 | `build/hydro-production/prod-1792656776998/production.png` |

### 4.2 拓扑（生产日志）

```
HYDRO-SKELETON traces=129 sources=371 droppedBySpacing=258 outletStarts=61
  ends: lake=54 sea=0 join=71 unresolved=0 DEAD_END=4
```

**`DEAD_END=4`**（删除 `spillCell` 时是 52）⇒ 短河问题已解决。

### 4.3 同步性审计

`ContPathAuditProbe`：
```
traces=55  hasContPath=55  noContPath=0
contPath 与格心偏离: max=1.2  均值约 0.6
```
**偏离 > 0 说明几何确实离开了格心**（v2 时恒为 0）；max=1.2 在兜底半径 1.5 内。

---

## 5. ⚠ 已知缺陷：几何仍有「阶梯感」

**这是我看到但未修的问题**，接手方应优先判断它是否可接受。

**现象**：生产图放大看，河线在**陡坡段**呈轻微的阶梯/齿状，而非平滑曲线。

**推测原因**（`未验证`）：`Trace.contPath` 虽然连续，但它的**步长恒为 1 格**
（`SPD_CAP=1.0`），且方向来自 `∇bed` 的离散差分 ⇒ 每步方向变化仍是离散的。
真正的「柏林噪声式连续」应该让步长也连续（<1 格），或对方向做多频叠加。

**另一个可能**：`shape()` 里首尾仍钉死格心（保 JOIN 重合），中间用 `contPath`，
导致**两端与中段不一致**。

---

## 6. 操作 runbook

### 6.1 出生产图

```powershell
cd 'D:\Office software\Development Project\GeoGenesis-mod\forge-1.20.1-47.4.10-mdk'
$cp=[IO.File]::ReadAllText((Resolve-Path 'build/local-flow-reassessment/classpath.txt')).Trim()
java -Xmx12g '-Dfile.encoding=UTF-8' -cp $cp com.geogenesis.worldgen.hydrology.model.ProductionViewProbe 9139912035078620160 -140 137
```
**预期**：`chunks=8281`，末行 `FILE=...production.png`。耗时 320~350 s。

> ⚠ 必须 `-Xmx12g`。低于 8g 有 OOM 风险（见 §3.2 第 3 条）。

### 6.2 验证同步性

```powershell
java -Xmx10g '-Dfile.encoding=UTF-8' -cp $cp com.geogenesis.worldgen.hydrology.model.ContPathAuditProbe 9139912035078620160 -140 137
```
**预期**：`hasContPath=55 noContPath=0`，`[OK] 偏离在兜底半径 1.5 内`。

### 6.3 回退

```powershell
# 回退到已提交状态（会丢弃运动向量）
git checkout -- forge-1.20.1-47.4.10-mdk/src/main/java
```
或只关开关：`-Dgeogenesis.motionVector=false`（退回格心锚点）。

---

## 7. 风险与坑（本轮新增）

| 坑 | 教训 |
|---|---|
| **`ArrayList<double[]>` OOM** | 用扁平 `double[]`。已实测爆堆一次。 |
| **`contPts` 写入慢一拍** | 必须在格步推进后才 add；首点单独处理。 |
| **终止分支漏 add** | JOIN/湖/海/溢流 每个 break 前都要补，否则整条链失效。 |
| **审计阈值写死 0.5** | 那是 v2 的遗留值，v3 应为 `motionSnapRadius`（1.5）。已修正。 |
| **`-Xmx` 不够** | 12g。 |

---

## 8. 待办与决策点

| # | 事项 | 卡在谁 |
|---|---|---|
| 1 | **几何阶梯感**（§5）是否可接受？若不可接受，需要让步长连续（<1 格）或多频方向叠加 | **用户判断** |
| 2 | 剩余 `DEAD_END=2~4` 是什么地形（封闭盆地？） | 未查 |
| 3 | 本次改动是否提交 | **用户**（上一轮指示「后续内容先不上传」） |
| 4 | `HYDROLOGY.md` 有 92 行 markdown 格式化改动（无实质内容），是否提交 | **用户** |
| 5 | `Trace.contPath` 的 `flattenCont()` 旧辅助函数现已无人调用，可删 | 低优先 |

---

## 9. 用户口径（必须遵守，来自本轮对话）

1. **不要参照物理路线了** —— 「已经不需要再参照物理路线对比了」「这个已经结束」。
2. **出图就是出生产图**，不要叠加粒子、不要自己写渲染。
3. **每次必须出图** —— 「必须每次都出」。
4. **水文全周期** —— 河流必须入湖/入海/汇流才算正常结束，不能半路断。
5. **不要人为禁环** —— 牛轭湖是真实的。
6. **汇聚由地形决定**，不是人为牵引。
7. **坡上平行是正常的**（沟壑本身平行）。
8. **单位用 wu**（`horizontalScale=1.0` ⇒ 1 block = 1 wu）。
9. **效果优先于性能** —— 「效果都不行性能优化了也是不要的」。

---

## 10. 诊断工具（当前可用）

| 工具 | 用途 |
|---|---|
| `ProductionViewProbe.java` | 出生产图（**唯一出图方式**） |
| `ContPathAuditProbe.java` | 连续路径与格链同步性审计 |
| `DeadEndAuditProbe.java` | 洼地 vs lakeMask 统计 |
| `verify-readonly-invariance.py` | 逐像素比对（判断改动是否影响产出） |
| `route-fit3.py` / `diff-rooted.py` | 历史：与物理动线对比（**当前目标已不适用**） |
