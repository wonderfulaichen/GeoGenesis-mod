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
