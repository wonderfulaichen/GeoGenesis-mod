# 水文系统重构计划（可见版）

> **本文件放在仓库根目录，被 git 跟踪 ⇒ 你在 GitHub / 本地都能看到。**
> （此前两版方案分别写在 `docs/plans/` 与 `.codebuddy/plans/`，
>   而 `.gitignore:52 docs/` 与 `.gitignore:83 .codebuddy/` 把它们**全部排除**
>   ⇒ 你看不到任何一行。**这是本计划存在的第一个原因。**）
>
> 状态：**待你确认后实施**（不再未经确认改生产代码）。

---

## 0. 你的原话（本计划的唯一依据）

> **「水文系统是一个整体」+「与侵蚀相关」**
> —— `HANDOFF.md:560`

**并附带一条要求：适应【无限世界】**（分区块生成，不能有全局遍历）。

---

## 1. 现状核查：哪些已经做了，哪些是空话

### 1.1 已做到（有实测）

| 项 | 位置 | 状态 |
|---|---|---|
| 降水 → 河宽 | `FlowField.PrecipWeights`（Phase C） | ✅ 已接线 |
| 水量衰减（蒸发） | `FlowField.decayPerWu` | ⚠️ **已实现，默认 0 ⇒ 未启用** |
| 气候驱动衰减 | M2-C | ⚠️ **已接线，默认关闭** |
| 跨区水位一致 | `RiverLineNetwork` 网格原点对齐 | ✅ 已修（99.6% → 0%） |
| 湖岸直线 | `sampleRegion` 湖认领 | ✅ 已修（本日） |

### 1.2 ★ 侵蚀 → 水文的现状（**我先前判断错了两次，此处是查实结果**）

**`dischargeOut` 确实已接线**（我先前的"从未接线"是错的）：

```java
// CellGenerator.java:1228-1233  主路径确实传了数组
float[] dischargeBuf = new float[bufSize * bufSize];
erosion.runErosionOnFlat(flat, flatPre, bufSize, N, originX, originZ,
        (float) seaE, (float) erosionStr, dischargeBuf, (float) params.horizontalScale());

// CellGenerator.java:1239-1242  拷成 N×N
// CellGenerator.java:1287       res.discharge = dischargeNxN;
// CellGenerator.java:1684       cell.riverNetDischarge = sampleTileField(res.discharge, ...);
```

**`riverNetDischarge` 的消费方（全仓 3 处）：**

| 位置 | 用途 |
|---|---|
| `GeoGenesisGenerator:840` | **碎石坡材质门控**（`riverNetDischarge ≥ 阈值` ⇒ 出碎石，AUC 0.845） |
| `GeoPalette:563` | 预览「流量图」图层 |
| `Cell.java:110` | 字段定义 |

**⇒ 侵蚀的放电量场【已导出、已进生产】，但只用于【材质】与【预览】。**

**★ 真正的缺口（这才是"整体"缺的那块）**：

```
侵蚀 dis  ──→  材质门控 / 预览          ✅ 已接
河网 accum（D8 累积） ──→ 河宽 / 河深 / threshold   ← 仍是【另一套】水量
```

**⇒ 两套水量并行：侵蚀的 `dis`（液滴路径重叠计数）与河网的 `accum`（D8 面积累积）。**
**⇒ 河网从未使用侵蚀算出的水量 ⇒ "水文与侵蚀是一个整体"未兑现。**
**⇒ 且 `ErosionEngine:273` 的注释自己写着"河网层复用 discharge 场做 riverMask，取代 D8 流量累积"——目标写明了，没做。**

**★ 我的错误记录**：我连续两次用 grep 只看方法名就下结论（先说"接口不存在"，再说"接口从未接线"），**两次都错**。教训：**判断接线，必须看【参数】与【消费方】，不能只看方法名。**

### 1.3 三套水位基准（重构的核心对象）

| 水 | 水位来源 | 位置 |
|---|---|---|
| 海 | `seaLevel` 常数 | `HeightCurve.seaLevelY()` |
| 湖 | `LakeNode.spill`（**每湖一个**） | `RiverLineRegion` |
| 河 | `RiverPolyline` **逐节点包络** | `RiverLineNetwork` |

**⇒ 三套，互不同源。** 这是"河湖脱节 / 接缝 / 湖岸直线"等一串症状的共同根。

---

## 1.5 ★★★★★ 新增：`Farseek-Mods/mainStreams`（Streams）—— **本仓库同路线的"正确形态"**

> 这是本仓库代码注释反复引用的 "old-Streams 语义" 的来源，我此前**从未读过**。
> 位置：`参考/river/Farseek-Mods/src/mainStreams/scala/streams/`

### 它的架构（与我们的对照）

| | **Farseek Streams** | **我们的 `RiverLineNetwork`** |
|---|---|---|
| 水位 | **`Segment.surfaceLevel`（每段一个字段）** | 河线逐节点包络 + 湖 `spill` + 海平面 = **三套** |
| 判水判据 | **`floorLevel vs surfaceLevel`（绝对等高线）** | 河：`dist ≤ width`（**几何**）；湖：`height < spill`（**等高线**）⇒ **两套混用** |
| 河床/河岸 | `isStreamBed = maxFloorLevel < surfaceLevel` | 无显式区分（靠 `dist`/`width`） |
| 湖 | **没有湖的概念** | `LakeNode` 独立系统 |
| 河网 | **图上最短路径**（节点=chunk，边权=边界最低高度差） | D8 流量累积 + 阈值 |
| 落差 | `outletIsFall = downstreamSurfaceLevel < surfaceLevel`；`MinFallHeight = 3` | `fallDrop` + `frozen`（**另有整套复杂逻辑**） |
| 宽度 | **横断面 = 列序列 `slopesLeftToRight`** | `width` 标量 + `dist` 距离场 |
| 水位递推 | `TributaryUpstreamNode.surfaceLevel = downstreamSurfaceLevel :+ fallHeight` | PAVA 单调回归 + 岸线 cap |
| 落地 | `preCarve` 只放"防雕刻遮罩" + 放水；`build()` 换土 | `HydrologyBlockCarver` + `GeoGenesisTerrain.applyHydrologyValley` |

### ★★★ 决定性三条

**① `splitChannel` —— 一个横断面，按同一水位切成 河岸/河床/河岸：**

```scala
// Segment.scala:107-109
val (leftBank, streamBedAndRightBank) = slopesLeftToRight.span(_.maxFloorLevel >= surfaceLevel)
val (rightBank, streamBed)            = streamBedAndRightBank.reverse.span(_.maxFloorLevel >= surfaceLevel)

// StreamsColumn.scala:20-22
def isStreamBed  = maxFloorLevel <  segment.surfaceLevel    // ★ 河床 = 低于水面
def isStreamBank = maxFloorLevel >= segment.surfaceLevel    // ★ 河岸 = 高于水面
```

**⇒ 这正是用户说的【绝对等高线判据】。没有"湖/河分支竞争"——
只有同一个剖面，用同一个水位切开。**
**⇒ 我们的"湖分支 vs 河分支"（`carveColumn` 里两套判据）在 Farseek 里【不存在】。**

**② `outletSlopes` —— 河谷横断面是【显式的列高度表】，不是靠 `dist` 算出来的：**

```scala
// Reach.scala:18-21（主流，35 列）
override protected lazy val outletSlopes = NonEmptySeq(
  (5,7), (3,5), (1,3), (0,1), (-1,-1), (-2,-3), ... (5,12))
//          ↑ 左岸（正数=高于水面）  ↑ 河床（负数）        ↑ 右岸

// TributaryNode.scala:29-33（支流，按 streamSize 分档）
if isSpring then NonEmptySeq(2,1,-1,-1,0,1,2)
else streamSize match
  case 1 => NonEmptySeq(3,1,0,-1,-1,-1,0,0,1,1,3)
  case 2 => NonEmptySeq(3,1,0,-1,-2,-2,-1,0,0,1,1,3)
  case _ => NonEmptySeq(3,1,0,-1,-2,-3,-3,-2,-1,0,0,1,1,3)
```

**⇒ 断面形状是【数据】，不是算法。** 支流按 `streamSize`（上游汇入数）选断面。
**⇒ 我们的 `depth`/`width` 是从流量算的标量，没有"断面形状"这个概念。**

**③ 水位递推 —— 落差由【上游高度】决定，不是硬编码：**

```scala
// TributaryNode.scala:57-59
override lazy val surfaceLevel =
  val fallHeight = (downstreamSurfaceLevel delta maxSurfaceLevelFromUpstream) / 2
  downstreamSurfaceLevel :+ (if fallHeight >= MinFallHeight then fallHeight else 0)
```

**⇒ 水位 = 下游水位 + (上游最高 - 下游)/2（超过 3 格才成瀑布）。**
**⇒ 这就是"水位场"的递推形式 —— 单一字段、逐段可算、无需全局量。**

**④ 它如何适应【无限世界】**：

```scala
// StreamsGenerator.scala:104-116  newSegment(p) 按 basin 懒建
// basins.scala:27-34  BasinChunkSize = 8 chunk（1 basin = 8×8 chunk）
```

**⇒ 以 basin（8×8 chunk）为局部求解单元，河网在 basin 图上求最短路径
⇒ 完全不需要全局遍历 ⇒ 天然适应无限世界。**

### 1.5.1 判据分歧的度量（2026-09-19，含口径缺陷说明）

`WaterViewProbe` 新增「判据统一度量」段，实测（seed 9139912035078620160 @ 块(-377,-335)±128）：

```
河分支列 33588
两判据一致：湿 0 / 干 19645
几何湿·等高线干（多灌）= 0
几何干·等高线湿（漏灌）= 13980
分歧合计 = 13980（41.6% of 河列）
```

**⚠️ 本度量有口径缺陷（诚实交代）**：
`湿 0` 是**过滤条件造成的假象** —— 探针写了 `if (c.isLake) continue`，
而内陆所有湿列都满足 `spill >= seaLevel ⇒ isLake = true` ⇒ **全被排除**
⇒ 13980 与先前"漏灌"数**完全相同** ⇒ **没能独立度量两套判据的分歧**。

**但仍能得出的结论**：**41.6% 带水位的列「低于自己的水位却是干的」**
⇒ 两套判据的实际分歧**很大** ⇒ **统一判据会大幅改变地形 ⇒ 必须先标定**。

**⇒ 下一步（若要统一判据）**：
1. 修探针口径（按 `column.fillWater()` 而非 `cell.isLake` 区分分支，需在 `Cell` 上
   或探针内记录分支来源）；
2. 标定：统一到等高线后，水体面积变化多少、河宽变化多少。

### 对我们重构的直接含义

| 我们现在 | Farseek 的做法 | 该改的 |
|---|---|---|
| 湖/河**两套判据**（`dist` vs `height`） | **一套**（`floorLevel vs surfaceLevel`） | ★ 线 A 的核心 |
| `depth`/`width` **标量** | **断面列序列**（`outletSlopes`） | 断面形状化 |
| 水位 = 河线包络 + 湖 spill + 海面 | **`surfaceLevel` 单一字段 + 递推** | ★ 线 A 的核心 |
| D8 accum + 阈值 | **图上最短路径** | 可选（影响河网拓扑） |
| `fallDrop` + `frozen` 整套复杂逻辑 | `downstreamSurfaceLevel < surfaceLevel` | 大幅简化 |

---

## 1.6 ★★ 参考文档的【有效性判定】（哪些过时、哪些仍有效）

> 用户提示："有不少文档是已经过时的"。本节的判定方法 = **看它引用的类是否还存在**
> （对已删除代码的分析，结论必然过时）。

### 1.6.1 判定证据：文档引用的 8 个类全部已不存在

```
★已不存在: SimplifiedRiverSystem / GridRiverSystem / RegionHydrologySolver
★已不存在: TileLakeSolver / HydrologySystem / ParticleRiverSystem
★已不存在: MaterialMapper / ClimateBiomeMapper
```

**⇒ 2026-06 那一整代架构（RegionHydrology / TileLake / GridRiver / SimplifiedRiver）
已被完全重写为当前架构（`RiverLineNetwork` + `FlowField` + `HydrologyBlockCarver`）。**

### 1.6.2 逐文档判定

| 文档 | 引用架构 | 判定 |
|---|---|---|
| `2026-06-13-河流系统重写方案.md` | Simplified/GridRiver | ❌ **过时**（代码已删） |
| `2026-06-13-诊断与修复方向.md` | Grid/RegionHydrology/TileLake | ⚠️ **代码过时，但【根因分析仍有效】**（见下） |
| `2026-06-13-边界侵蚀对齐与混合水文方案.md` | RegionHydrology/TileLake | ⚠️ 同上 |
| `2026-06-14-河流系统重写方案-v2.md` | Simplified/Grid/TileLake | ❌ **过时** |
| `2026-06-14-河流系统重写方案-v2.1.md` | SimplifiedRiver | ❌ **过时** |
| `2026-06-15-河流系统重写方案-v2.2.md` | SimplifiedRiver | ❌ **过时** |
| `2026-06-15-河流系统重写方案-v2.3.md` | SimplifiedRiver | ❌ **过时** |
| `2026-06-15-河流系统重写方案-v5.md` | SimplifiedRiver | ❌ **过时** |
| `方案_v7.0_粒子侵蚀河流系统.md` | ParticleRiver/ErosionEngine | ⚠️ 部分（`ErosionEngine` 仍在） |
| `ReTerraForged河流系统深度分析.md` | SimplifiedRiver | ✅ **仍有效**（分析的是 RTF 本体，非我们的代码） |
| `Streams-完整架构分析-2026-08-15.md` | — | ✅ **仍有效**（分析 Farseek，且给出路线判断） |
| `四大参考项目技术提炼.md` | — | ✅ **仍有效**（参考项目提炼） |
| `侵蚀噪声对比分析报告.md` | — | ✅ **仍有效**（Shadertoy 算法对比） |
| `Epic_Terrain_*.md`（2 份） | — | ✅ 有效（分析 Epic Terrain 数据包） |
| `群系分布分析报告.md` | — | ✅ 有效（群系，非水文） |
| `项目需求.md` | — | ✅ **仍有效（★ 用户原始需求，验收依据）** |

### 1.6.3 ★★★ 过时文档里【仍然有效】的根因分析（这才是最该看的）

`2026-06-13-诊断与修复方向.md` 的**代码已删，但根因分析今天依然成立**：

> **根因 A：Priority-Flood 的边界假设不可回避**
> "Priority-Flood 的数学前提：**求解域的边界是排水出口**。……
> 如果盆地位于 Tile 中央，真实出口在远方（超出 152 格），Tile 边界处地形比真实出口高
> ⇒ 水位被抬高 ⇒ 水面高于岸边。"
>
> **"关键认识：任何有限大小的求解域都有这个问题。344×344 patch 有，152×152 tile 也有。
> 缩小/扩大求解域只是改变裁剪大小，不解决根本矛盾。"**
>
> "MC 原版用**连续噪声场**（aquifer）定义地下水，不依赖局部填洼。
> TerraForged 用**全局地形噪声**确保无缝。
> 只有 ReTerraForged 用了 Priority-Flood，但它的求解域是**整个 Region（数千格）**。"

**⇒ 这条诊断与我 2026-09-19 用 `runLakeEdgeProbe` 实测出的结论【完全一致】**
（跨 region 不一致 99.6%、加 halo 无效）。**而它写在 3 个月前。**

**★ 更重要：它已给出解法方向**

> **方向 1：连续噪声场定义湖泊（推荐）**
> 在 NoiseEngine 中新增 lakeBasinNoise + lakeLevelNoise，天然无边界断裂。

**⇒ 这正是 RTF 的 `waterTable` 噪声场路线 —— 与我后来"改走闭式 W"的结论同向。**

### 1.6.4 我该吸取的教训

1. **`analysis/` 里有 3 个月前的同款诊断** ⇒ 我这几周是在**重复踩已记录过的坑**；
2. **过时文档不能整体丢弃** —— 要按"**代码引用**（过时）"与"**根因分析**（常青）"分开看；
3. **`项目需求.md` 是验收依据**（大/中/小三级河、自然源头、瀑布、湖泊），
   我从未按它验收过。

---

## 1.7 ★★★★★ 新增：`plate-local-river-generation`（PL-RGA, 2026-06）—— 无限世界的确定性答案

> `参考/river/plate-local-river-generation-main`（含 `davis2026_...pdf` 论文）。
> **它专解我们的核心难题：无限世界 + 确定性河网 + 无跨区接缝。**

### 1.7.1 两级分工（★ 这是"确定性"的架构基础）

```
platewise/  （板块局部，可缓存）：
  platewisegrid     → 该板块的粗网格（高度、valid/border_safe/lake_safe 掩膜）
  platewisenetwork  → 河网几何（nodes + segments + paths）
  platewiseregions  → 把线段包成【区域多边形】（供点查询加速）

pointwise/  （逐点，无缓存，纯函数）：
  pointwiseheight   → 地形高度（纯噪声）
  pointwiseplatefields.riverFields(x,y) → ★ 查表得 (river_height, river_distance)
```

**⇒ 关键：河网几何**只在板块内算一次并缓存**；而**任意世界点**的水位/距离
通过 `riverFields(x,y)` 用【板块归属 → 最近区域 → 候选线段】三级索引查到
⇒ **逐点可算、无全局遍历、与"当前加载了哪些区块"无关**。

### 1.7.2 ★★★ 河高与地形的 blend（逐点闭式）

```python
# pointwiseheight.finalHeightField
if river_distance >= 1.0:
    return height                                   # 河影响区之外 → 纯地形
terrain_weight = river_distance ** RIVER_BLEND_EXP  # ★ 距离的幂
return height * terrain_weight + river_height * (1.0 - terrain_weight)
```

**⇒ `最终地形 = 地形 × w + 河高 × (1−w)`，`w = river_distance^exp`。**
**⇒ 这正是"河床 = 水位 − 深度"的另一种写法，而且【逐点闭式、天然连续】。**
**⇒ 我们的 `HydrologyBlockCarver` 用的是"距离场 + 平滑 min + IDW 混合"一整套
（为此写了大量注释处理折痕/断层）；PL-RGA 只用【一个幂函数 blend】。**

**★ 而河高本身也是节点插值**（与我们相同）：
```python
river_height = height_from + (height_to - height_from) * t
```
⇒ 与我们的 `lerp(pl.surfaceY[i0], pl.surfaceY[i1], t)` **完全同构**。

### 1.7.3 ★★★★ 水位如何跟随地形（`_applyRiverHeightSlopeDrop`）—— **解"水面像爬坡"**

```python
RIVR_HGHT_SLOPE_DROP = 0.005

source_raw   = nodes[source].height       # 源头【地形】高度
outlet_raw   = nodes[outlet].height       # 出口【地形】高度
outlet_height= nodes[outlet].river_height # 出口【水面】高度
raw_span     = source_raw - outlet_raw

if raw_span <= RIVER_MIN_DROP:
    source_height = outlet_height                       # 平坦 ⇒ 整条河水平
else:
    source_height = source_raw - drop                   # ★ 源头水位 = 地形 − 0.005
adjusted_span = source_height - outlet_height

for node in path:
    t = (raw_height - outlet_raw) / raw_span            # ★ 用【地形高度】定参数
    river_height = outlet_height + adjusted_span * t    # ★ 按地形比例插值水位
```

**⇒ 两条同时成立：**
1. **水位跟随地形起伏**（`t` 由该节点的**地形高度**决定）；
2. **落差被压缩**（`span` 被 `drop=0.005` 压扁）⇒ **水面不会"贴着 45° 山坡爬"**。

**⇒ 这正是前人诊断里那条"陡坡处水面贴着地形缓抬 → 河水像爬坡"的正解。**
**⇒ 我们当前是"缓抬 max+2/4wu"（手工常数），PL-RGA 是"按地形比例 + 压缩落差"（有据）。**

### 1.7.4 ★★ 三种出口 + 回滚（保证"不生成半截河"）

```python
# 出口类型（node.type）
"source"                # 源头
"outlet_sea"            # 入海（heights[pixel] <= SEA_LEVEL_FRACTION）
"outlet_local_minimum"  # 内流（终点是局部最低点）★ 仅当 lake_safe_mask 为真

# ★ 回滚条件（任一命中 ⇒ 整条河【删除】，不生成）
if _hasUnsafeDownhillNeighbor(...):   _rollbackRiver(...)   # 下坡指向 border 安全区之外
if not lake_safe_mask[pixel]:         _rollbackRiver(...)   # 内流终点不在安全湖点
```

**⇒ 这是"跨板块一致性"的关键：宁可【不生成】，也不生成一条会跨板块断裂的河。**
**⇒ 而我们的做法是"尽力生成 + 事后修补"（大量 fallback / 容差 / 特判）。**

### 1.7.5 其他可搬的细节

| 机制 | 代码 | 价值 |
|---|---|---|
| **河线不交叉** | `_wouldCrossExistingSegments` / `_segmentsCross`（方向叉积判定） | 几何约束，防自交 |
| **汇入已有河** | `_nearbyDownhillRiverNode`（找 step_size 内更低的已有节点） | 天然生成树状汇流 |
| **源头选取确定性** | `_sourcePixels`（按高度降序 + `min_source_spacing` 去重，取前 N） | 可复现 |
| **距离场** | `_riverDistanceFieldFromDistance`：`(dist/width)^power`，≥1 即区外 | 与我们 `dist/width` 同构 |
| **湖节点处理** | `_lakeAdjustedSegmentFields`（端点标为 lake 时，半径内水位取湖面） | 湖 = 节点属性，非独立系统 |

### 1.7.6 对我们的直接含义

| 我们现在 | PL-RGA | 该搬的 |
|---|---|---|
| 平滑 min + IDW 混合（防折痕） | **一个幂函数 blend** | ★ 简化雕刻 |
| 水位"缓抬 max+2/4wu"（手工常数） | **按地形比例 + 压缩落差** | ★★ 解"水面爬坡" |
| 尽力生成 + 事后修补 | **不安全就整条回滚** | ★★ 确定性 |
| 湖 = 独立系统（`LakeNode` + BFS） | **湖 = 节点属性（`outlet_local_minimum`）** | 湖的定位 |
| 跨区靠 halo + 容差 | **`border_safe_mask` 硬约束** | 接缝 |

---

## 1.8 ★★★ TerraForged-0.3.x（RTF 前代）—— 与 RTF 1.21.1 同源，但**更简洁**

> `参考/sources/TerraForged-0.3.x`（5 个类：`RiverConfig/RiverCarver/RiverGenerator/RiverNode/RiverPieces`）。
> **对照价值：看 RTF 这套设计在 4 年演进中哪些是核心（保留）、哪些是补丁（后加）。**

### 1.8.1 河网生成（`RiverGenerator`）—— Voronoi 细胞图 + 双向连接

```java
// 对细胞 A 的 4 邻居 B：
//   ① 记录【最低邻居】min
//   ② 若 B 更高、且 A 是 B 的最低邻居（connects）⇒ A→B 加河
for (var dir : DIRS) {
    var b = continent.getCell(seed, bx, by);
    if (value <= minValue) { min = b; minValue = value; continue; }   // 最低邻居
    if (value <= 0) continue;                                          // 海洋
    if (connects(seed, ax, ay, bx, by, value)) { addRiverNodes(a, b, ...); isSource = false; }
}
// ③ 若 A 是源头（无上游）且无更低邻居 ⇒ 连到最低邻居
```

**⇒ `connects` = 双向检查（A 是 B 的最低邻居）⇒ 保证树状、无环、无虚假连接。**
**⇒ 与我们 D8 追踪的区别：它在【细胞图】上做，天然全覆盖、天然无环。**

### 1.8.2 节点几何（`RiverNode`）—— **中点细分 + 垂直位移 + 曲线投影**

```java
record RiverNode(ax,ay, bx,by, ah,bh, ar,br, displacement) {
    getHeight(t) = ah + t*(bh-ah)          // ★ 水位沿段线性插值
    getRadius(t) = ar + t*(br-ar)          // ★ 半径沿段线性插值
    getDistance2(x,y,t) {
        alpha = ...(t 的曲线，CURVE3) * displacement
        px = tx - (by-ay)*alpha            // ★ 把直线段【垂直位移】成曲线
        py = ty + (bx-ax)*alpha
        return Line.dist2(x,y,px,py)
    }
}
```

**⇒ 关键：`displacement` 让"直线段"在距离场里变成**曲线**——
即"弯道"是**距离场的一部分**，不是另加的东西。**
**⇒ 我们的 `RiverLineNetwork` 用折线节点 + `smin` 平滑，`TF-0.3.x` 用"直线段 + 垂直位移"。**

### 1.8.3 雕刻（`RiverCarver`）—— 三级剖面，与 RTF 1.21.1 同构

```java
float bedLevel   = baseLevel - bedDepth * levels.unit;      // ★ 河床 = 水位 − 深度
float bankLevel  = baseLevel + bankDepth * levels.unit;     // ★ 河岸 = 水位 + 岸高

// ① 河谷（valley）：距离 ≥ valleyWidth 直接返回；bank~valley 之间 lerp
float valleyAlpha = getValleyAlpha(distance, bankWidth, valleyWidth, sample.baseNoise);
if (valleyAlpha < 1.0f) {
    float level = Math.min(bankLevel, height);              // ★ 不超过岸高
    height = lerp(level, height, valleyAlpha * modifier);   // ★ 侵蚀调制
    sample.riverNoise *= getValleyNoise(...);
}
// ② 河床（bed）：distance ≤ bedWidth
float riverAlpha = getAlpha(distance, bedWidth, bankWidth);
if (riverAlpha < 1.0f) {
    float level = Math.min(bedLevel, height);               // ★ 不超过河床
    height = lerp(level, height, riverAlpha);
    sample.terrainType = nodeSample.type;
}
```

**⇒ 两个 `Math.min` 是关键：河谷不高于岸、河床不高于床
⇒ **天然满足"水在河道里"**（不需要事后短板检查）。**
**⇒ 而 `riverNoise`（[0,1] 河影响场）与 `terrainType` 都在这里产出 —— 供下游群系/材质用。**

### 1.8.4 ★ 与 RTF 1.21.1 的演进对照（哪些是核心、哪些是后加）

| 机制 | TF-0.3.x（2021） | RTF 1.21.1（2025） | 判定 |
|---|---|---|---|
| 河网 | Voronoi 细胞图 + `connects` | 保留（`RiverGenerator`） | **核心** |
| 水位 | `levels.water`（海平面）+ 段插值 | `getComplexWaterHeight(waterTable)` | **核心（水位统一）** |
| 雕刻 | 三级：valley / bank / bed | 四区：河床/河岸/谷底/渐隐 | **核心** |
| 河床 | `baseLevel - bedDepth` | `targetWaterLevel - finalizedDepth` | **同一式** |
| 湖 | `lakeDensity` 概率 + `addLakeNodes` | `lakeMultiplier`（半径乘数） | **演进：从"概率撒点"到"平坦度驱动"** |
| 弯道 | `displacement`（距离场位移） | 8 种噪声（宽度/深度/阶地/不对称…） | **演进：从 1 个到 8 个** |
| 侵蚀调制 | `getErosionModifier(erosion * config.erosion, valleyAlpha)` | 同 | **核心** |
| 入海 | `b.noise < threshold` ⇒ 延伸到海 | `isAboveOcean` 判断 | **核心** |

**⇒ 4 年演进中【不变的核心】= ① 细胞图河网 ② 统一水位 ③ 三级/四区径向剖面
④ `河床 = 水位 − 深度` ⑤ `riverNoise` 影响场。**
**⇒ 变化的只是【参数化程度】（湖从概率→平坦度；弯道从 1→8 个噪声）。**

### 1.8.5 对我们的直接含义

**我们缺的，正是那 5 条核心里的第 2、3、5 条：**

| # | 核心 | 我们有吗 |
|---|---|---|
| ① | 细胞图/D8 河网 | ✅ 有（`RiverLineNetwork`） |
| ② | **统一水位** | ❌ **三套**（海/湖/河） |
| ③ | **三级径向剖面** | ⚠️ 有雏形（`bedTarget = carveSurfaceY − depth*profile`），但无"岸/谷"分层 |
| ④ | `河床 = 水位 − 深度` | ✅ 有 |
| ⑤ | **`riverNoise` 影响场** | ⚠️ 有 `riverDistance`，但无 [0,1] 河谷影响场 |

---

## 1.9 ★★★★★ geotransport（`sample.hpp` + `path.cu`）—— **"水文与侵蚀是一个整体"的数学形式**

> `参考/sources/geotransport-main/source/geotransport/{sample.hpp, path.cu}`。
> **这是 SimpleHydrology 作者的后续项目**（`HANDOFF.md` 已记录其可行性实测）。

### 1.9.1 核心方程：**线性守恒律的蒙特卡洛解**

```cuda
// path.cu :: __solve_uniform
flux  : 通量积分估计 [X·m^D/s]
flow  : 流场         [m/s]
source: 源项（= 降水）[X/s]
decay : 衰减项（= 蒸发）[1/s]

const float L = length(scale);                  // 格长
const float A = scale.x * scale.y;              // 格面积
const float P = 1.0 / (A * shape.elem());       // 采样概率
const vecK S = sourceView[ind] / P;             // ★ 采样源率
if (length(S) < epsilon) return;                // 源太小 ⇒ 提前终止

// ★★★ 沿流线积分（随机游走）
float att = 1.0f;                               // 累积衰减
while (!oob(pos) && epsilon < abs(att) && ++step < maxstep) {
    if (nind != ind) {                          // 离开本格 ⇒ 把通量累加到该格
        atomicAdd(&fluxView[ind].x, S.x * att); // ★ flux[格] += 源 × 累积衰减
    }
    v = gather(flowView, pos);                  // 双线性插值取速度
    pos += stepsize(pos, normalize(v)) * normalize(v);   // ★ 走一步
    float dlambda = step * L / v_len;           // 真实时间
    att *= exp(-dlambda * decay[ind]);          // ★ 衰减累积（沿流线指数衰减）
}
```

**⇒ 一句话：`flux[i] = Σ_样本  S/(Δx·Δy) · exp(−∫ decay dt)`，沿流线积分。**
**⇒ 它把「降水(source) / 河流(flow) / 蒸发(decay)」统一成【同一个场 φ】。**

### 1.9.2 ★★★ 为什么这就是我们要的"整体"

| 水文要素 | geotransport 里的身份 |
|---|---|
| **降水** | `source` 项 |
| **蒸发** | `decay` 项 |
| **河流 / 流向** | `flow` 速度场 |
| **汇流量** | `flux`（= 我们要的 `accum`） |
| **侵蚀输运量** | **同一个 `flux`**（K 维张量 ⇒ 可同时输运多种量） |

**⇒ "水文是一个整体" = 它们都是【同一个守恒律的项】，不是三套系统。**
**⇒ 而我们：`FlowField.accum`（D8）、`riverNetDischarge`（液滴）、`decayPerWu`（蒸发）
是【三个独立实现】—— 这正是"不整体"的技术事实。**

### 1.9.3 ★ 与 D8 的关系（作者自己的注释）

```cuda
// the flow evolution rule?
//  if we don't have any type of momentum, then pits basically
//  don't really go away. We rely on the well-structuredness of
//  the velocity field, which we cannot always do.
```

**⇒ 作者明说：没有动量项时，洼地（pits）不会消失；依赖速度场的良好结构，
而那并不总能保证。** ⇒ **这解释了为何 `[4] 湖泊自然涌现` 实测 FAIL。**

### 1.9.4 ★★ 归一化（`__normalize`）—— 关键工程细节

```cuda
const float A    = scale.x * scale.y;
const float norm = abs(v.x*scale.y) + abs(v.y*scale.x);   // ★ 迎风面积
// ★ 加上 source 项，使【零采样的格也有正值】（对应本地源项），且不除以样本数
fluxView[n] = (sourceView[n] * A + fluxView[n] / count) / norm;
```

**⇒ 两个要点：**
1. **`norm` = 迎风面积**（不是格面积）⇒ 通量对速度方向各向异性正确；
2. **零采样格也补上本地源项** ⇒ **不会出现"没采样到就为 0"的洞**。

### 1.9.5 对我们的直接含义

| 我们 | geotransport | 该学的 |
|---|---|---|
| `accum`（D8 面积累积） | `flux`（守恒律解） | **统一的水量定义** |
| `riverNetDischarge`（液滴计数） | **同一个 `flux`** | 两套合一套 |
| `decayPerWu`（常量/气候衰减） | `decay` 项（沿流线积分） | 已在，形式一致 |
| 无"源项"概念 | `source` = 降水 | 已有 `precipSampler` |
| 采样为零 ⇒ 该格为 0 | **补本地源项** | 防"洞" |

**⚠ 但 `HANDOFF.md` 已记录实测限制**：`[4] 湖泊自然涌现` FAIL（洼地/全局 = 1.01×）。
根因三条（D8 单下游拓扑保证汇聚、连续梯度场不保证、示例地形是严格单调的圆锥）。
**⇒ 结论仍是：`flux` 可用于【河流汇流量统一求解】，但【湖泊仍须现有洪泛机制】。**

---

## 1.10 ★★★ MOBIDIC（`river_network.py` + `routing.py`）—— 河宽 = 等级幂律

> `参考/sources/MOBIDICpy-main/mobidic/{preprocessing/river_network.py, core/routing.py}`。

### 1.10.1 ★★★ 河宽的物理公式（直接可搬）

```python
# _compute_strahler_order —— Strahler 分级
#   ① 无支流 ⇒ order = 1
#   ② 两条 order=i 汇合 ⇒ order = i+1
#   ③ 不同级汇合 ⇒ 取较高者
def _recursive_strahler(idx):
    if 无上游: return 1
    orders = [upstream_1 的 order, upstream_2 的 order]
    ...

# _calculate_routing_parameters
network["width_m"]   = Br0 * (strahler_order ** NBr)    # ★★ B = Br0 · order^NBr
#   Br0 = 1.0（一级河宽 m），NBr = 1.5（幂指数）⇒ 默认
network["lag_time_s"] = length_m / wcel                  # 滞时 = 河长 / 波速（wcel=5 m/s）
network["n_manning"]  = n_Man                            # 曼宁系数（0.03）
```

**⇒ 河宽 = 基准宽 × 等级^1.5。** 一级 1m、二级 2.83m、三级 5.2m、四级 8m…
**⇒ 这正是前人 `Streams-完整架构分析` 里"宽度 = 汇流数量（streamSize）"的同一条，
但 MOBIDIC 给了【幂律指数 1.5】这个具体值。**

**⇒ 我们当前是"线性 taper 0.55→1.0"（与汇流无关）—— 这是明确的缺口。**

### 1.10.2 网络拓扑：强制二叉树 + 合并单支流

```python
# _enforce_binary_tree：每段最多 2 条上游支流（超出则拆分）
# _join_single_tributaries：只有 1 条上游的段 ⇒ 与上游合并（简化线性链）
# _compute_calculation_order：拓扑排序（上游先算）
```

**⇒ "每段最多 2 上游" 让网络成为【二叉树】⇒ 递归计算稳定、无环。
⇒ 而我们的 `RiverLineNetwork` 是 D8 + graft 吸附，汇合点数任意。**

### 1.10.3 坡面路由（`routing.py`）—— 显式区分"一步"与"累积"

```python
def hillslope_routing(lateral_flow, flow_direction):
    """Route lateral flow ONE STEP from upslope cells to immediate downstream neighbors.
    CRITICAL: This is NOT cumulative routing! To move water from headwaters to outlets
    requires calling this function once per timestep for many timesteps."""
    for i, j:
        down_i = i + _DIR_OFFSETS_I[flow_dir-1]      # D8 偏移
        down_j = j + _DIR_OFFSETS_J[flow_dir-1]
        upstream_contribution[down_i, down_j] += lateral_flow[i, j]
```

**⇒ 关键区分：`hillslope_routing` = **一步**转移（时间推进）；
而 `flow_accumulation` = **累积**（拓扑一次算完）。**
**⇒ 我们只有"累积"这一种（`accum[down] += accum[cur]`）—— 没有时间维。**
**（对地形生成，累积足够；时间维是"动态水"才需要。）**

### 1.10.4 对我们的直接含义

| 我们 | MOBIDIC | 该搬的 |
|---|---|---|
| 线性 taper 0.55→1.0 | **`B = Br0 · order^1.5`** | ★★ 河宽公式 |
| D8 + graft（汇合数任意） | **二叉树（≤2 上游）** | 拓扑简化 |
| 只有累积 | 一步 + 累积（显式区分） | 概念澄清 |
| 无滞时/曼宁 | `lag_time = L/wcel`、`n_Manning` | 若做动态水 |

---

## 1.11 ★★★ SimpleHydrology（`README` + `vegetation.h`）—— 三方耦合与"洪泛已删"

### 1.11.1 ★★★★ 作者自述：**洪泛系统已被移除**（原文）

> **Update January 2023**
> *"The flooding system has been removed for now, because of **buggyness and slowness**.
> A better system has been proposed [here](SoilMachine)."*
> *"Momentum and discharge maps are now explicit and interact physically with the water
> particles, giving **river meandering** behavior."*

**⇒ 这是本项目【最有价值的一条外部证据】：
`SimpleHydrology` 的作者**主动删除了洪泛系统**（正是我们 `computeFill`/`computeFlood`/
`LakeNode` 那一整套），理由是**"多 bug 且慢"**；改用**动量 + 流量图**显式耦合。**

**⇒ 而我们当前：洪泛（priority-flood + BFS 连通区）是湖泊的**唯一**机制。
⇒ 参考里两处都指向"洪泛不该做重"：**
- `HANDOFF.md:222`（前人已记）：*"SimpleHydrology 作者因『又慢又多 bug』已移除其洪泛 ⇒ **不宜为整体化而加重洪泛**"*
- 本条原文再次确认。

**⇒ 结论：湖泊机制应向【流量图 + 局部水位】演化，而不是继续加固洪泛。**

### 1.11.2 `vegetation.h` —— 植被 ↔ 水文 ↔ 侵蚀 三方耦合（我们完全没有）

```cpp
struct Plant {
  static float maxSize = 1.5f, growRate = 0.05f;
  static float maxSteep = 0.8f;        // ★ 坡度上限
  static float maxDischarge = 0.3f;    // ★ 流量上限（河道里不长树）
  static float maxTreeHeight = 0.8f;

  bool die() {
    if (World::map.discharge(pos) >= maxDischarge) return true;   // ★ 被水冲走
    if (World::map.height(pos)   >= maxTreeHeight) return true;   // ★ 太高
    if (rand()%1000 == 0) return true;                            // 随机死亡
  }
  static bool spawn(vec2 pos) {
    if (World::map.discharge(pos) >= maxDischarge) return false;  // ★ 河道内不生成
    if (World::map.normal(pos).y < maxSteep)       return false;  // ★ 太陡不长
    ...
  }
  void root(float f) {                  // ★★ 根系写入 rootdensity（9 格加权：中心1.0/十字0.6/对角0.4）
    c->rootdensity += f * 1.0f;  // 中心
    c->rootdensity += f * 0.6f;  // 4 邻
    c->rootdensity += f * 0.4f;  // 4 对角
  }
};
```

**⇒ 三方耦合的闭环：**
```
水（discharge）  → 决定 植被能否存活（河道内死）
植被（rootdensity）→ 决定 侵蚀强度（根系固土，water.h 里读 rootdensity）
侵蚀（地形变化）  → 改变 坡度/高度 → 又影响植被
```

**⇒ 我们：植被是群系层（`BiomeClassifier`），与水文/侵蚀**无双向耦合**。
⇒ 而"水文是一个整体"若要彻底，这一环是缺失的。**（优先级低于统一水位，但应记入。）

### 1.11.3 其他要点

| 项 | 内容 |
|---|---|
| 主循环 | 侵蚀 + 植被生长（`README`："main game loop that calls the erosion and vegetation growth functions"） |
| 渲染 | `model.h` 只有渲染参数（无关水文） |
| 后续项目 | `SoilMachine`（作者推荐的新一代） |
| 我们已读 | `water.h`（液滴）、`world.h`（cascade）、`cellpool.h`（内存池，无关算法） |

---

## 1.12 ★★★★★ 五参考共同核心 vs 我们的缺口（本方案的最终依据）

> 范围：`FreeTerraForged-1.21.1` / `TerraForged-0.3.x` / `Farseek-Mods` / `PL-RGA` /
> `geotransport` / `MOBIDIC` / `SimpleHydrology` / `worldgen-master`（8 个项目，含 4 个河系专门项目）。
> **只列【多个参考都这么做】的条目 —— 那才是"核心"，不是某个项目的偏好。**

### 1.12.1 共同核心（≥3 个参考一致）

| # | 共同核心 | 谁这么做 | **我们** | 缺口 |
|---|---|---|---|---|
| **C1** | **水位是单一基准**（海/湖/河同源） | RTF(`Levels.water`)、TF-0.3.x、PL-RGA(海平面)、worldgen-master、Farseek | ❌ **三套**（海常数/湖 spill/河包络） | ★★★ |
| **C2** | **`河床 = 水位 − 深度`** | RTF、TF-0.3.x、Farseek(`maxFloorLevel`)、PL-RGA | ✅ 有（`bedTarget`） | — |
| **C3** | **径向/分层剖面**（河床/河岸/谷底/渐隐） | RTF(四区)、TF-0.3.x(三级)、Farseek(`outletSlopes`)、PL-RGA(dist 幂) | ⚠️ 雏形（`profile` 单一 V 形） | ★★ |
| **C4** | **判水 = 绝对等高线**（`floorLevel vs surfaceLevel`） | Farseek、RTF(`finalHeight < targetWaterLevel`)、TF-0.3.x(`Math.min`) | ❌ **河用几何、湖用等高线（两套）** | ★★★ |
| **C5** | **河网 = 图上的树**（无环、全覆盖） | TF-0.3.x(`connects` 双向)、Farseek(最短路径)、PL-RGA(下坡追踪+汇入)、RTF(Voronoi 最低邻居) | ⚠️ D8 + graft 吸附 | ★ |
| **C6** | **逐点闭式可算**（无限世界前提） | PL-RGA(`pointwise`)、RTF(`waterTable`)、Farseek(basin 局部)、TF-0.3.x(细胞图) | ⚠️ 部分（`WaterField` 已验证但未接线） | ★★ |
| **C7** | **湖不是独立系统** | worldgen-master(**无 lake 模块**)、RTF(`lakeMultiplier` 一个乘数)、PL-RGA(`outlet_local_minimum` 节点属性)、Farseek(**无湖**) | ❌ **独立 `LakeNode` + BFS + 洪泛** | ★★★ |
| **C8** | **`riverNoise`/影响场供下游用** | RTF(`cell.riverMask`)、TF-0.3.x(`sample.riverNoise`)、PL-RGA(`river_distance`) | ⚠️ 有 `riverDistance`，无 [0,1] 场 | ★ |
| **C9** | **降采样取 MAX**（保细河） | worldgen-master（原文注释） | ❓ 未核实 | ? |
| **C10** | **蛇曲/扭曲在填洼之前，且不推下水位** | worldgen-master（clamp 注释） | ❌ `WARP_AMP` 加在全局高度场 | ★★ |

### 1.12.2 关键反证（参考告诉我们【不要做什么】）

| # | 反面教训 | 出处 | 对我们的意义 |
|---|---|---|---|
| **N1** | **洪泛系统"多 bug 且慢"，已被作者删除** | SimpleHydrology README（2023-01） | ★★★ **不要再加固洪泛** |
| **N2** | **无动量时 pits 不会消失，依赖速度场结构，而那不总能保证** | geotransport `path.cu` 注释 | 解释了湖泊涌现 FAIL |
| **N3** | **Priority-Flood 的求解域边界=排水出口，任何有限域都有此问题** | `analysis/2026-06-13-诊断与修复方向.md` + 我们实测 99.6% | ★★★ 加 halo 治不了 |
| **N4** | **RTF 从不拿 `W` 与 `height` 比较来决定"是不是水"** | RTF `UpliftRiverCarver` | `W` 只管【水面高度】 |
| **N5** | **不安全就整条回滚，不生成半截河** | PL-RGA `_rollbackRiver` | 我们的"尽力生成+事后修补"是反模式 |
| **N6** | **Streams 哲学 ≠ 我们的哲学；用户认可现有贴谷路线** | `analysis/Streams-完整架构分析-2026-08-15.md` | 不整体改路线 |

### 1.12.3 ★★★ 按缺口排的优先级（= 重构顺序）

```
P0-1  C1 统一水位（海/湖/河 → 一个场）        ← 症状的共同根（接缝/河湖脱节/湖岸）
P0-2  C4 判据统一（绝对等高线一套）           ← 我这两周反复踩的坑
P0-3  C7 湖去对象化（乘数/节点属性，非独立系统）← N1 反证支持
P1-1  C3 分层剖面（河床/岸/谷/渐隐）          ← 视觉质量
P1-2  C6 逐点闭式（无限世界确定性）           ← `WaterField` 已验证，待接线
P1-3  C10 扭曲定位（填洼前 + clamp）
P2-1  C5 河网拓扑（可选，现状已可用）
P2-2  C8 riverNoise 影响场
P2-3  植被三方耦合（SimpleHydrology 范式）
```

**⇒ 与我们此前方案（线 A/B/C）的对应**：C1+C4+C7 = 线 A；C6 = 线 A 的落地；
线 B（水文↔侵蚀）经测量**不能简单取代**，须走 geotransport 的守恒律统一（更大工程）。

---

## 1.13 ★★★★★ 实测判决（2026-09-19）：**顺序必须改 —— C3 → C1 → C4**

> 探针：`runUnifiedCriterionProbe`（新增，零生产改动）。
> 目的：P0-2（C4 判据统一）前的"先量后改"。

### 1.13.1 实测（seed 9139912035078620160 @ 块(-377,-335)±256，步长 4，n=16641）

```
[1] 河分支（判据 = 几何 fillWater）列数 = 3944
    一致：湿 174 / 干 2476
    ★ 几何湿·等高线干（多灌）= 0
    ★ 几何干·等高线湿（漏灌）= 1294（32.81%）最大欠灌 46.214 块
      样本：块(-289,-591) h=182.759 surf=185.310 差=2.552 fillWater=false lakePlan=false
           块(-285,-591) h=173.813 surf=181.723 差=7.910 fillWater=false lakePlan=false
           块(-281,-591) h=166.689 surf=179.253 差=12.564 fillWater=false lakePlan=false

[2] 湖分支（判据 = 等高线 spill）列数 = 10846
    一致：湿 3082 / 干 4258    多灌 0 / 漏灌 3506（32.33%）

[3] 无计划（carveColumnAt=null）列数 = 1851

[4] ★ 水体面积对照（采样 16641 列）
    现状实际有水     = 3256（19.57%）
    统一判据后将有水 = 8056（48.41%）
    净变化           = +4800 列（+28.84 个百分点，≈ 2.5 倍）
```

### 1.13.2 判决：**不能直接统一判据**，且**我原定的顺序（先 C4）是错的**

**⇒ 水体翻 2.5 倍不可接受。** 而根因在数据里写得很清楚：

**漏灌列的特征 = `fillWater=false`（不在河道内）但 `h < surf`（地形低于河线水面）。**
**⇒ 它们是【河谷两侧的原地形】，而河线水面 `surf` 高于它们。**

### 1.13.3 ★★★ 为什么 Farseek 能统一判据，而我们不能

**Farseek 的河谷是【雕刻出来的】，剖面是显式数据：**

```scala
// Reach.scala:18-21  outletSlopes（35 列，(河谷底高度, 隧道底高度)）
(5,7), (3,5), (1,3), (0,1), (-1,-1), (-2,-3), ... (5,12)
 ↑ 左岸：高于水面 5/3/1 块        ↑ 河床：负数        ↑ 右岸：+12（高地隧道）

// Segment.scala:107-109  splitChannel 用同一水位切分
val (leftBank, streamBedAndRightBank) = slopesLeftToRight.span(_.maxFloorLevel >= surfaceLevel)
val (rightBank, streamBed)            = streamBedAndRightBank.reverse.span(_.maxFloorLevel >= surfaceLevel)
```

**⇒ 关键：`outletSlopes` 的正数部分【保证河谷两侧高于水面】**
**⇒ 所以 `isStreamBed = maxFloorLevel < surfaceLevel` 才成立、才能统一判据。**

**⇒ 我们：河谷两侧是【原地形】（未雕刻），可能低于河线水面
⇒ 于是"统一判据"会把它们全判成水 ⇒ 水体 ×2.5。**

### 1.13.4 ⇒ 修正后的依赖顺序（重要）

```
原定：  P0-1 C1 统一水位  →  P0-2 C4 判据统一
实测后：C3 河谷剖面  →  C1 统一水位  →  C4 判据统一
        ↑ 必须先做：把河谷两侧雕到水面之上，判据统一才安全
```

**⇒ C3（分层剖面）不是"视觉优化"（P1），而是 C4 的【前置条件】（P0）。**

**具体含义**：给 `carveColumn` 的河谷带（`width < dist ≤ valley`）加约束：
**雕刻目标不低于 `waterSurfaceY + minBankHeight`** ⇒ 河谷两侧被抬到水面之上
⇒ 与 Farseek `outletSlopes` 的正数部分同义 ⇒ 判据统一才成立。

**⚠ 这会改变地形（抬高河谷两侧）⇒ 必须再次"先量后改"：量抬高后的地形变化与水体面积。**

### 1.13.5a ★★★★★ 判据修正（2026-09-19 晚）—— **河分支本来就是对的，1.13 的结论作废**

**★ 我犯了一个判据错误，必须更正 1.13：**

**RTF 的 `isSubMerged` 是【三条件缺一不可】：**
```java
boolean isSubMerged = currentLinearDist < zone1Radius   // ① 设计剖面（在河道内）
        && carvedThisPass                               // ② 确实切低了
        && finalHeight < targetWaterLevel;              // ③ 结果低于水位
```

**⇒ "① 设计剖面"是【必要条件】。而我 1.13 用的判据只有 ③（`height < surf`）
⇒ 把【河谷两侧本就不该有水的原地形】误判成"漏灌"。**

**修正后重测（判据 = `fillWater`（设计剖面）vs 实际 `riverType`）：**

```
[1] 河分支 3944 列
    一致：湿 174 / 干 3770
    ★ 真多灌 = 0（0.00%）   最大超出 0.000 块
    ★ 真漏灌 = 0（0.00%）   最大欠灌 0.000 块
    ⇒ 分歧合计 = 0（0.00%）
```

**⇒ ★★★ 河分支的判据【本来就是对的】（与 RTF 三条件同构，见 `carveColumn` 的 `anyFill`）。
⇒ 1.13 的"漏灌 32.81%"是**判据错误**产生的假象，不是缺陷。
⇒ **C4（河分支判据统一）【不需要做】。** 1.13.4 的"C3 前置"推论随之作废。**

**★ 而真正的病在【湖分支】：**

```
[2] 湖分支 10846 列
    一致：湿 3082 / 干 4258
    多灌 0 / ★漏灌 3506（32.33%）
```

**⇒ 湖分支用 `spill`（湖域水位）判水，而湖域覆盖过广（10846 列 vs 河 3944 列），
其中 7764 列（71.6%）"在湖域内但无水" ⇒ 与 `RiverLakeStealProbe` 的发现一致。
⇒ **真问题是【C7 湖去对象化】+【湖域过大】，不是 C4。**

**★ 湖分支也用正确判据复测（`inFlood` 连通 + `height < spill`）：**

```
[7] 湖分支正确判据  列数 = 10846
    该有水 = 2959：实际有水 2959 / ★真漏灌 0（0.00%）最大欠灌 0.000 块
    该无水 = 7887：实际无水 7764 / ★真多灌 123（1.56%）最大超出 8.940 块
```

**⇒ 湖分支真漏灌 = 0、真多灌仅 1.56% ⇒ 判水逻辑【基本正确】。**

**★★★★ 最终判决：**
| 项 | 1.13 的错误结论 | 修正后 |
|---|---|---|
| 河分支 | 漏灌 32.81% | **分歧 0.00%** ⇒ 已正确 |
| 湖分支 | 漏灌 32.33% | **真漏灌 0%、真多灌 1.56%** ⇒ 基本正确 |
| 水体面积 | 19.57% → 48.41% | **不适用**（该数字建立在错误判据上） |

**⇒ 判决：`C4`（判据统一）【不需要做】—— 现有判水已与参考实现同构（RTF 三条件）。
⇒ `C3`（河谷剖面）不再有"C4 前置"的紧迫性，回到 P1（视觉质量）。
⇒ 真正的缺口回到 `C1`（统一水位）与 `C7`（湖去对象化）。**

**★★ 方法论教训（同类第三次，务必记住）**：
**"判据"本身必须先用参考实现校验，否则量出来的数字全是假的。**
RTF 的 `isSubMerged` 三条件我早先读过、还写进了本计划 §1.2，
**但测量时却只用第三条当判据** ⇒ 量出 32% 的假缺陷，并据此错误地改了任务顺序。
**⇒ 规则：任何"缺陷率"测量，必须先声明【判据来自哪个参考实现】，再动手量。**

### 1.13.5b ★★★ C3 抬升量实测（同探针 [5] 段）

```
[5] C3 河谷抬升量（目标 = surf + 1.0，即把低于水面的列抬到水面之上 1 块）
    需抬升列数 = 7533
    抬升量分位：p10=0.59  p50=2.33  p90=13.43  max=47.21 块
```

**⇒ 中位数 2.33 块（温和，说明多数漏灌列确实只差一点）**
**⇒ 但 p90 = 13.43、max = 47.21 块 —— 有一批列要抬十几到四十几块。**

**⚠ 这批"大抬升"列的性质必须查清，两种可能截然不同：**

| 可能 | 含义 | 处置 |
|---|---|---|
| **(a) 河谷带** | 河线水面略高于河谷侧壁 ⇒ 抬到水面之上是**正确**的（Farseek 语义） | C3 直接做 |
| **(b) 河线本身有问题** | 河线水面远高于当地地形（"河悬在空中"）⇒ **不该抬地形，该降水面** | 先修水位 |

**★ 判别实测（同探针 [6] 段，判据 = 大抬升列是否"成带"）：**

```
[6] 大抬升列（>5 块）性质判别
    大抬升列数 = 2333（占需抬升列的 31.0%）
    其中【邻列也是大抬升】= 2320（99.4%）        ← ★★★ 成带
```

**⇒ 99.4% 成带 ⇒ 它们确实是【河谷带】，不是散布的水位求解错误。**
**⇒ 判决：C3（河谷剖面）方向正确、该做。**
**（若比例 <40% 才是"河悬空"，那时须先修水位。实测排除了该可能。）**

**★ 这一步的方法论教训**：`p50 = 2.33` 温和，容易让人以为"直接做就行"；
**而 `p90 = 13.43` / `max = 47.21` 才暴露真问题** ⇒ 加做 [6] 段才判明性质。
**⇒ 分位数必须看尾部；且"大值"必须判别【性质】（成带 vs 散布），不能只看数值。**

### 1.13.5 附带发现：湖域列数远超河列（10846 vs 3944）

**⇒ 湖域覆盖过广**（与 `RiverLakeStealProbe` 的发现一致）。
湖分支 10846 列中仅 3082 有水面（28.4%），其余 7764 列（71.6%）是"湖域但无水"。
**⇒ 湖域的判定（`inDomain`）远大于实际淹水区 ⇒ 这也是 C7（湖去对象化）的论据。**

---

## 1.15 ★★★★ 实测（2026-09-19）：`W` 不能替代 `spill`（T1.3 否决）

> 探针：`runWaterFieldUnifyProbe`（新增，零生产改动）。

### 1.15.1 实测（seed 9139912035078620160 @ 块(-377,-335)±128，步长 2）

```
[1] 列数：湖 14338 / 河 2014 / 无计划 289

[2] W − spill 差值（n=14338）
    p10=−9.334  p50=−3.266  p90=+2.794   最小=−14.625  最大=+18.104
    均值=−3.133   标准差=4.603

[3] |差| 分布
    p50=3.879  p90=9.498  max=18.104
    |差|<1 块：1721（12.0%）
    |差|<3 块：5356（37.4%）
    |差|≥10 块：397（2.8%）

[4] 方向：W 高于 spill 20.0%（最大 +18.104）；W 低于 spill 80.0%（最大 −14.625）
```

### 1.15.2 判决

**⇒ 散布大（σ=4.60）、无系统性偏移（均值 −3.13 与 σ 同量级）
⇒ `W` 与 `spill` 是【语义不同的两个量】，不能替换。**

| 量 | 语义 | 能否逐格算 |
|---|---|---|
| `spill`（`LakeNode`） | **盆地溢出口**（该湖的水最终从哪溢出） | ❌ 需局部搜索 |
| `W`（`WaterField`） | **区域平均地面 − offset** | ✅ 闭式 |

**⇒ 与计划 §8.2 早已记录的结论一致**（"`W` 的职责是【水面高度】，不是【水在哪里】"）。

### 1.15.3 ⇒ T1 的正确形态（修正）

**不是"用 W 替换 spill"，而是：**

1. **跨区一致性** —— 已由 **【网格原点对齐】** 修复
   （`RiverLineNetwork` 网格原点取整到 `cell` 倍数 ⇒ 接缝线不一致 **100% → 0%**）；
2. **`W` 的正确定位** = **湖域/洼地掩膜**（跨区一致的 `depthBelowSmooth() > 0`），
   用于 T2（湖域收窄）——**不是替换水位**。

**★ 这解释了为何 `WaterLevelSolver` 用 `filledAt` 会失败、而换成 `W` 也未必更好：
"水位"必须由**局部溢出口**定，那是物理；`W` 只能提供**跨区一致的基准/掩膜**。**

---

## 1.16 ★★★ T2 可行性实测：`W` 洼地掩膜（命中 81.3%、误检 19.6%）

> 探针：`runWaterFieldUnifyProbe` [5] 段（同一次运行）。

```
[5] W 洼地掩膜（depthBelowSmooth > 0）vs 实际湖水   湖列 = 14338
    掩膜为真 = 7749（54.0%）
    湖列中：有水且掩膜 6230 / 有水但掩膜假 1434 / 无水且掩膜 1519 / 无水掩膜假 5155
    ★ 命中率（有水且掩膜 / 有水）= 6230/7664 = 81.3%
    ★ 误检率（无水且掩膜 / 掩膜真）= 1519/7749 = 19.6%
    ★ 若用掩膜预筛：湖列 14338 → 7749（省 46.0%）
    （河列中掩膜为真 = 566，占河列 28.1%）
```

**⇒ 判决：可用，但【不能单独用】。**

| 指标 | 值 | 含义 |
|---|---|---|
| 命中率 | 81.3% | 81% 的真湖在掩膜内 |
| **漏检率** | **18.7%** | **18.7% 的真湖在掩膜外 ⇒ 单独用会漏湖** |
| 误检率 | 19.6% | 掩膜内 20% 实际无水（可接受，后续连通性会筛掉） |
| 省列数 | 46.0% | 预筛可省近半列的昂贵处理 |

**⇒ 正确用法 = 【预筛 + 保守回退】**：
掩膜为真 ⇒ 正常处理；掩膜为假 ⇒ **不立即丢弃，而是降级处理**（如只做廉价判定），
以免漏掉那 18.7% 的真湖。

**★ 顺带发现**：河列中 28.1% 也在掩膜内 ⇒ 掩膜不是"湖专属"，
它标的是**地形洼地**，河与湖都可能落在其中（符合物理：河谷也是洼地）。

---

## 1.14 任务树定稿（Goal Decomposer，2026-09-19）

> **目标（用户原话）**：实现**模拟现实的水文系统**，**适应无限世界**，**性能不错**。
> **实测校正后的结论**：判水逻辑已正确（§1.13.5a）⇒ 真缺口 = **C1 统一水位** + **C7 湖去对象化**。

```
T1 [P0] 统一水位场 —— ★ 实测修正：**不是"用 W 替换 spill"**
  T1.1  ✅ 已有 WaterField（逐格闭式，跨区不一致 0 vs filledAt 99.9%）
  T1.2  ✅ 参数已标定：smoothWu=24 / offset=−0.5（水格 2.5%、15 个湖）
  T1.3  ❌ **实测否决"W 作湖水位"**（见 §1.15）：
        实测 W−spill：p50=−3.27、σ=4.60、|差|p50=3.88、仅 12% 列 |差|<1 块
        ⇒ 散布大、无系统性偏移 ⇒ 两者【语义不同】（W=区域平均，spill=盆地出口）
  T1.4  ✅ **跨区一致性已由【格点对齐】修复**（非 W）：
        RiverLineNetwork 网格原点取整到 cell 倍数 ⇒ 接缝线不一致 100% → 0%
  T1.5  ⏳ ⇒ W 的正确定位 = 【湖域/洼地掩膜】（跨区一致），供 T2 使用
  T1.6  ⏳ 验收：runLakeEdgeProbe 接缝 0 不一致（已达成）+ W 掩膜与湖域重合度

T2 [P0] 湖去对象化（C7）—— 从"独立系统"到"节点属性/乘数"
  T2.1  ✅ 已量：湖域 14338 列 vs 实际有水 7664 列（46.5% 空转）
  T2.2  ✅ **已量 W 掩膜可行性**（见 §1.16）：命中 81.3% / 误检 19.6% / 省 46% 列
        ⚠ 但 18.7% 真湖被漏 ⇒ **不能单独用**，只能作【预筛 + 保守回退】
  T2.3  ⏳ 湖 = 河床半径乘数（RTF 范式）替代独立 LakeNode 分支
  T2.4  ⏳ 验收：真多灌 1.56% → 更低；湖域空转率下降且不漏真湖

T3 [P1] 河谷剖面（C3）—— 分层：河床/河岸/谷底/渐隐
  T3.1  ✅ 已量：需抬升 7533 列，p50=2.33、p90=13.43、max=47.21 块；99.4% 成带
  T3.2  ⏳ 实现：谷壁带雕刻目标 ≥ waterSurface + minBankHeight（Farseek outletSlopes 语义）
  T3.3  ⏳ 验收：河谷两侧高于水面；实机看"河谷像河谷"

T4 [P2] 性能（用户要求"性能不错"）—— ★ 实测：**当前不是瓶颈，降级**
  T4.1  ✅ 已量（runChunkLoadPerfProbe，seed 9139912035078620160 span=16 regions=3）：
        判据 min = **7.92 ms/chunk**（区域间 7.92 / 8.08 / 8.66）
        水文总 1403ms；最坏单 chunk 200ms、P95 1196ms = **冷启动尖峰**（tile 未缓存）
  T4.2  ⇒ 稳态 7.92 ms/chunk 已属良好 ⇒ **性能不是当前瓶颈**
  T4.3  ⏳ 湖域收窄后【复测】即可（不作为驱动目标）
  T4.4  ⏳ 洪泛加固【停止】（N1 反证：SimpleHydrology 作者已删）

T5 [P2] 水量统一（线 B，geotransport 守恒律）
  T5.1  ✅ 已量：dis vs accum 秩相关 0.1286、量纲差 2 个数量级 ⇒ 不能简单取代
  T5.2  ⏳ 若做：走守恒律统一（source/decay/flow/flux），非换数据源

T6 [P2] riverNoise 影响场 / 植被三方耦合（C8）
```

**执行顺序：`T1` → `T2` → `T3/T4` → 其余。**（`T2` 与 `T1` 有耦合：湖域收窄需先有统一水位基准。）

---

## 2. 参考实现给出的答案（四套，逐条对应）

| 参考 | 关键机制 | 对我们 |
|---|---|---|
| **RTF** | `Levels.water` = 海平面（**单一基准**）；`bedHeight = W − depth`；湖 = `zone1Radius × lakeMultiplier` | **统一水位**的范式 |
| **RTF** | `ContinentalHydrology`：15 级台阶 + 台阶内 `flatnessFactor` | **平坦 = 湖**（不是缺陷） |
| **worldgen-master** | `priority_flood` → `flow_dir` → `accum` → `downsample_max` → `carve` | **顺序**：先填洼、再在填洼后地形算流向 |
| **worldgen-master** | **没有 lake 模块** | 湖 = 填洼副产品 |
| **SimpleHydrology** | `Drop.descend()`：`c_eq ∝ 坡降×流量`；`discharge_track` 累积 | **水量与侵蚀同源**（= "整体"） |
| **MOBIDIC** | 湖 = 体积 + `stage_storage` / `stage_discharge` 两条曲线 | 水量守恒 |
| **FastFlow 论文** | 洼地"填满后溢出"，**不是断流** | 修"填水中断" |

**★ 最关键的一条（RTF）**：
```java
float targetWaterLevel = getComplexWaterHeight(cell.waterTable, ...) + levels.water;  // 只给【水面高度】
float bedHeight        = targetWaterLevel - finalizedDepth;                          // 河床 = 水位 − 深度
boolean isSubMerged    = dist < zone1Radius && finalHeight < targetWaterLevel;
```
**⇒ RTF 从不拿 `W` 与 `height` 比较来决定"是不是水"。`W` 只管【水面高度】，"水在哪里"由河网 + 河谷拓宽决定。**

---

## 3. 重构方案（三条线，按依赖排序）

### 线 A【P0】统一水位：三套基准 → 一套

**目标**：海 / 湖 / 河的水面锚定到**同一个函数**，且**逐格可算**（无限世界要求）。

**做法（对齐 RTF）**：

```
W(x,z) = seaLevel + 抬升(x,z)          // 水面【高度】的唯一来源
河床   = W − depth                     // 河道下切
湖     = W − depth，但 zone1Radius × lakeMultiplier（平坦区摊开）
海     = W 的基准面（抬升 = 0）
```

**⚠️ 关键口径（我已用数据纠正过两次，务必守住）**：

- `W` **只给水面高度**，**不判"水在哪里"**（§8.2 实测：逐格闭式的 `W` 只能造"等高带"，造不出盆地湖——盆地水位由溢出口决定，那是非局部信息）；
- 水的位置仍由 `RiverLineNetwork`（河网）+ 平坦度拓宽（湖）给出；
- `W` 的价值 = **跨区一致的绝对高度**（实测不一致 0 vs `filledAt` 99.9%）。

**落地清单**：

| # | 动作 | 文件 |
|---|---|---|
| A1 | `W` 逐格闭式场（**低通地形 + offset**，非 e 的函数） | `WaterField.java`（已有，需定稿） |
| A2 | 湖水位 = `W` 锚定；溢出口仍**局部**求（物理正确） | `HydrologyBlockCarver` 湖分支 |
| A3 | 河线水面 = `W` 锚定（**测量说不该做，见 §11**） | ❌ 已否决 |
| A4 | 接缝 gasket：邻块水位取 max（闭式 `W` 使其可行） | `GeoGenesisTerrain` |

### 线 B【P0】水文 ↔ 侵蚀 —— ★ 已测量：**不能直接用 `dis` 取代 `accum`**

**测量（`runTwoWaterBudgetProbe`，seed 9139912035078620160 @ 块(-377,-335)±64，步长 2，n=4225）：**

```
[2] 量纲
    侵蚀 dis  : p50=2.14   p90=6.60    max=35.47
    河网 accum: p50=192.00 p90=640.00  max=10560.00     ← 差约 2 个数量级

[1] 秩相关（Spearman）= 0.1286                            ← ★ 几乎不相关
[3] "哪里是河"（各取 top-20%）：
    两者皆是河 = 250   仅 dis = 596   仅 accum = 886
    交集率 = 14.4%                                        ← ★ 分歧极大
```

**⇒ 判决：两套水量是【两套不同的物理】，不能直接互换。**

| 场 | 物理含义 | 为何不同 |
|---|---|---|
| `riverNetDischarge` | **液滴路径重叠计数**（随机粒子，局部随机性大） | 受液滴数/步数/随机种子支配，是"采样量" |
| `FlowField.accumAt` | **D8 面积累积**（按 `e` 降序确定性累加） | 是"地形面积积分"，确定性 |

**⇒ `ErosionEngine:273` 注释里"河网层复用 discharge 场做 riverMask，取代 D8 流量累积"
这个目标【经测量不可直接实现】。**（注释是意图，不是事实。）

**⚠️ 本测量的局限（诚实交代）**：
`accum` 取自**探针自建的 `FlowField`**（cellSize=8wu、`routingE` 口径），
与生产河网用的 `FlowField`（`regionSize=640`、`cell` 见 `RiverLineParams`）**参数不同**
⇒ 秩相关 0.1286 可能被"网格参数不一致"污染。
**但 [2] 的量纲差（2 个数量级）与 [3] 的分歧（交集 14.4%）是稳健的** ——
即使网格对齐，两者也不是同一量。

**⇒ 线 B 修正后的做法（若要"整体"）**：
1. **不做"取代"**，改为**双场并存 + 各自明确的角色**：
   - `accum`（D8 面积）→ 继续管**河宽/河深/threshold**（已标定，不动）；
   - `dis`（侵蚀放电量）→ 用于**"侵蚀强度"相关**（如河谷下切深度、碎屑输运）；
2. **若要真正统一**，须走**物理统一**而非"换个数据源"：
   让河网的 `accum` **带上侵蚀的输沙能力**（`c_eq ∝ 坡降 × 流量`，SimpleHydrology `Drop.descend` 的范式）
   ⇒ 这需要**重新设计**，不是接线。
3. **须先复测**：用**与生产同参数**的 `FlowField` 重跑本探针，排除网格污染的干扰。

### 线 C【P1】水量平衡：让"沙漠里河会消失"

**机制已在（`decayPerWu` + M2-C 气候驱动），只是默认关闭。**

| 选项 | 效果 | 代价 |
|---|---|---|
| A. 保持关闭 | 零变更 | "整体"未达成 |
| **C. 气候驱动 decay** | **干旱区内流河、湿润区穿流到海** | 需标定 |

**⇒ 建议 C（这正是"整体"的含义：空间上不同的水文行为）。**

---

## 4. 执行顺序与验收

```
第 1 步  线 B 的【测量】（不改生产）：导出 dis，与 accum 对比
         ⇒ 验收：给出差异分布；决定是否切换
第 2 步  线 A（W 定稿 + 湖水位锚定 + gasket）
         ⇒ 验收：runLakeEdgeProbe 接缝 0 不一致；湖岸无直线
第 3 步  线 C（气候驱动 decay 标定）
         ⇒ 验收：沙漠出现内流河；runFlowAccumProbe 哨兵不劣化
第 4 步  回归：runWorldgenGate 全 PASS + 实机
```

**★ 每一步都先量、后改、再验；不许再"哪行挡路改哪行"。**

---

## 5. 我的错误记录（防重犯）

| # | 错误 | 后果 |
|---|---|---|
| 1 | 方案写在 `.codebuddy/plans/`（gitignore） | **你看不到，等于没写** |
| 2 | 更早方案写在 `docs/plans/`（gitignore） | 同上 |
| 3 | 读了参考 → 写注释 → **没接线** | `dischargeOut` 空置至今 |
| 4 | 打补丁而非重构（改单行判据） | **引入河流回归**（湖分支抢河道，203 列不下切） |
| 5 | 判"水够不够"用形态量（宽度/跨度） | 误判；应用**绝对量**（`height` vs 水位） |
| 6 | 连续三次猜 `c → e` 链路 | 全错；应直接用生产 `e` 场 |

---

## 6. 待你决策

1. **线 B（水文↔侵蚀接线）是否先做测量？** —— 我建议是，因为它是"整体"的核心且接口现成；
2. **线 A 的 `W` 是否采用"低通地形"形态？** —— §9 实测给出 `smoothWu=24, offset=−0.5`；
3. **线 C 是否启用气候驱动 decay？**
