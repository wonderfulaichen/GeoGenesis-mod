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
