# 水文系统 —— 单一事实来源（2026-10-09 重建）

> **本文件替代根目录原有 12 份水文文档**（已归档至 `_archive/hydrology/`）。
> 归档原因：那些文档彼此矛盾、多数已过时（详见 `_archive/hydrology/README.md`），
> 继续放在根目录会误导接手者 —— 本项目已因此连续出错多轮。

---

## 〇、⛔ 用户口径（最高优先级，2026-10-09 用户第三次重申）

> **这三条推翻了我此前多次的错误表述。任何人（含 AI）动手前必须先读本节，
> 不得再用旧说法。**

### ① 参照图是 **model 链**跑的，不是 legacy

`canonical-1791351869529`（用户多次引用的那张"目标图"）的 `metrics.txt` 实测：

```
river model = D8 (streamSamples=600000)      ← model 链（HydrologyPipeline, RiverModel.D8）
radius 700 => 1401x1401
sea=452778 lake=71340 (3.63%) channel=25503
```

**⚠ 我此前反复说"参照图是 legacy 出的"、"legacy 的湖是对的" —— 都是错的。**
用户的判定是：**legacy 的湖泊也不完全对**。

### ② 河流必须做成 **确定性骨架**，宽度等细节**后续解决**

- **目标形态 = 骨架**（确定性、与窗口无关）
- **宽度/深度等细节是后续议题**，不要因为"宽度不对"就否定骨架方向
- **理由（用户的硬需求）**：只有确定性骨架才能**无限世界生成**

⇒ 判据首选「骨架是否正确/连续/位置对」，**不要**先纠结宽度。

### ③ **model 链是 legacy 链的升级版**

- 不是"两条平行的可选方案"，而是 **model 取代 legacy** 的关系
- 因此：**legacy 的既有优点（如跨区机制）必须被 model 继承**，而不是丢掉
- 我此前"先回退到 legacy"的做法方向错了

### ⇒ 由此推出的正确工作方向

1. **让 model 链进游戏并能跑**（`hydrologyModelPipelineEnabled=true`），
   而不是停在"legacy 是默认"的现状
2. **把 model 链的骨架改成与窗口无关的确定性生成**
   （参考 DW 的**共享锚点**：本区汇点 = 邻区源点，同公式 ⇒ 逐位相同）
3. 宽度/深度在骨架正确之后再处理

---

## 〇b、★ 系统总账：模组里到底有几套河流/湖泊（2026-10-09 盘点，以代码调用关系为准）

> **背景**：用户要求「我要确定现在模组里面有多少套河流与湖泊。不然老是被你搞乱。」
> 本节 = 逐条核实 `src/main` 生产调用关系后的权威盘点。**以后任何讨论先读本节。**

### 生产入口只有 1 个：`HydrologyChunkEngine.calculate`（二选一开关）

```
HydrologyChunkEngine.calculate()                          ← 游戏落块的唯一水文入口
  ├─ hydrologyModelPipelineEnabled = true   →  【链 B】model 链
  └─ hydrologyModelPipelineEnabled = false  →  【链 A】legacy 链（当前游戏默认）
```

### 链 A：legacy（当前默认）

| 组件 | 文件 | 状态 |
|---|---|---|
| 河网 + 湖 | `riverline/RiverLineNetwork`（~5000 行）| ✅ 生产在用 |
| 河道骨架来源 | `flow/TerrainFlowSim`（块分辨率流体模拟）| ✅ 生产在用（`flowSkeletonRouting` 默认开）|
| 汇流累积 | `flowaccum/FlowField`（D8 + 降水加权）| ✅ 生产在用 |
| 落块 | `HydrologyBlockCarver.carveChunk` | ✅ 生产在用 |
| 门面 | `HydrologyExperimentEngine` | ✅ 生产在用 |

### 链 B：model（开关 true 时）

| 组件 | 文件 | 状态 |
|---|---|---|
| 窗口管线 | `model/HydrologyPipeline` | ✅ 在用 |
| 区域适配 | `model/ModelHydrologyProvider`（region 缓存 + columnsFor）| ✅ 在用 |
| 河网 | `model/RiverNetwork`（D8，默认）/ `ContinuousRiverNetwork` | ✅ 在用（`-Dgeogenesis.modelRiverModel` 切换）|
| 湖 | `model/EquilibriumWater` + `LakeWaterBalance` | ✅ 在用 |
| 中心线 | `model/RiverCenterlines` | ✅ 在用 |

### 死代码（无生产调用点，勿再接回）

| 组件 | 说明 |
|---|---|
| `model/WaterField` | **零调用点**。闭式水位场接线三版全部实测失败后回退，注释明写"勿重走"。类保留只作历史 |
| `hydrology/sim/` 包 | **main 里不存在**（早期 sim 原型已删）。`infinite-hydrology-rebuild.plan.md` 规划的 `HydroTile*` 新核心**从未落地**，只有 `_archive` 探针残留 |
| `riverline/MidpointDisplacement.generate()` | 死方法；但 `Node`/`ElevationSampler` 是生产类型，**类不能删**（AGENTS.md 既有警告）|

### 结论（一句话）

**生产实际只有 2 套**：legacy / model（开关二选一，**当前游戏跑 legacy**）；
model 链内部有 D8/CONTINUOUS 两个模型变体；`WaterField` 是死代码；**不存在第三套**。

### 当前开关一览（生产语义）

| 开关 | 默认 | 作用 |
|---|---|---|
| `hydrologyModelPipelineEnabled`（config）| **false** | 两链二选一（`run/config/geogenesis-common.toml`）|
| `geogenesis.modelRiverModel`（-D）| d8 | model 链内部 D8 / CONTINUOUS |
| `geogenesis.modelRegion/modelHalo/modelStep`（-D）| 512/96/4 | model 窗口参数（诊断用）|
| `geogenesis.riverThresholdFrac`（-D）| 0 | model 成河阈值面积比（0=关）|
| `geogenesis.sourceSpacing`（-D）| 24 | model 中心线源头间距裁剪（0=关）|
| `geogenesis.inlandResolved` | **true**（代码默认）| model 链内陆窗口可信判据（`f6aa223` 起默认开）|
| `hydro.viaProvider`（-D，探针）| false | 正典图走生产 provider（出图必开，§三）|

---

## 一、当前状态（唯一权威）

| 项 | 值 |
|---|---|
| 工作区 | `D:\Office software\Development Project\GeoGenesis-mod`（仓库根） |
| 分支 | `fix/port-lake-p2-2` |
| HEAD | `7eb8748`（之上有少量未提交改动，见 §六） |
| 生产水文入口 | `HydrologyChunkEngine.calculate` → 二选一（见 §二 / §〇b） |
| 未推送 | 是 |

⚠ **`_model_wt/` worktree 已不再是独立代码线** —— model 链已于 `848fdc1` 移入 `src/main`，
现由开关切换。旧文档里"两条并行代码线"的描述**已失效**。

---

## 二、生产有两条链（由开关二选一）

> 组件级明细见 **§〇b 系统总账**（权威盘点）；本节只讲两链的语义对照。

```
HydrologyChunkEngine.calculate()
  ├─ hydrologyModelPipelineEnabled = true  →  model 链（ModelHydrologyProvider）
  └─ hydrologyModelPipelineEnabled = false →  legacy 链（HydrologyBlockCarver）   ← 当前默认
```

| | legacy 链 | model 链 |
|---|---|---|
| 入口 | `HydrologyBlockCarver.carveChunk` | `ModelHydrologyProvider.columnsFor` |
| 河网 | `riverline/RiverLineNetwork`（region 640wu，D8 追踪 + 跨区续流 `OutletSeed`）| `model/HydrologyPipeline`（窗口 + 多 region 拼接）|
| 湖 | `RiverLineRegion.LakeNode` + `spill` 溢出高程 | `EquilibriumWater`（priority-flood）+ `LakeWaterBalance`（水量平衡）|
| 定位 | **被 model 链取代的旧版** | ★ **升级版（目标）** —— 见 §〇③ |
| 默认 | ✅（现状，**但这是待改的**）| ❌ |

⚠ **不要把"当前默认是 legacy"误读为"legacy 是正确目标"** ——
用户的定位是 **model 取代 legacy**（§〇③），legacy 仅为现网跑着的旧版。

**model 链内部还有两个模型**（`HydrologyPipeline.RiverModel`）：

| | D8（默认）| CONTINUOUS |
|---|---|---|
| 算法 | priority-flood + D8 拓扑累积，**确定性** | 蒙特卡洛流线积分（5 万条随机起点）|
| 耗时 | 快 ~3× | 慢 |
| 开关 | 默认 | `-Dgeogenesis.modelRiverModel=continuous` |

---

## 三、★★ 铁律：预览必须等于游戏

**用户核心要求**：「渲染出图必须和游戏实机一样」。

**历史教训**：此前预览探针（`CanonicalViewProbe`）**直接调 `HydrologyPipeline.run`（单窗口）**，
而游戏走 `ModelHydrologyProvider`（**按 region 分次求解再拼接**）
⇒ 两条完全不同的执行模型 ⇒ 用户反复发现"图上有的、游戏里没有"。

**⇒ 现行做法**：预览必须走**生产 provider**：

```bash
# 正典规格图（1401×1401）走生产路径 —— 产出的图 = 游戏会落的水
gradlew runCanonicalViewProbe -Dhydro.viaProvider=true -Dgeogenesis.inlandResolved=true
```

**判据也必须与游戏落块层逐字一致**（`GeoGenesisGenerator:781`）：

```
湖 = column.lakePlan()
河 = column.fillWater() && column.waterSurfaceY() > column.carvedGroundY()
```

⚠ **不要用 `erosion() > 0` 判河道** —— `erosion()` 在 legacy 链含**整个河谷**
（河床+岸坡+谷壁），实测会虚报（9.86% vs 真实 3.06%）。

---

## 四、★ 正典规格（定稿，勿改）

| 参数 | 值 | 说明 |
|---|---|---|
| `radius` | **700** | ⇒ 1401×1401 |
| `step` | **1** | 1 像素 = 1 方块 |
| `seed` | `9139912035078620160` | |
| 中心 | `(-140, 137)` | |
| 底图 | 无光照等高线 | **hillshade 会把谷画成脊**（已误判两次）|
| 色标 | 固定（`ViewSpec.BAND_BASE/BAND_BLOCKS`）| 跨图可比 |

**唯一来源**：`diagnostics/.../model/ViewSpec.java`。
`ViewSpec.assertCanonicalRadius` 会硬断言 —— 传错半径的图**不可与正典图互相印证**。

---

## 五、参考项目：四家一致的答案（★ 重要）

`参考/river/` 下四个项目**全部不需要跨区水量传递**，做法一致：

| 项目 | 单元尺寸 | 保证"任意位置单独查询都连续"的机制 | 跨区传水 |
|---|---|---|---|
| **FTF** | ~12000 格 | 边界按构造落在**海里**（edge 函数边框恒 0）| **无** |
| **PL-RGA** | ~6667 单位 | `border_safe`/`lake_safe`：河/湖被挡在离边界 margin 外 | **无** |
| **DW** | **640 格（与我们同）** | **共享锚点**：本区汇点 = 邻区源点（同种子公式 ⇒ 逐位相同）| **无** |
| **Streams** | 8×8 区块 | 接口水位 = **海平面常数** + 固定截面表；下游**复制**上游截面 | **无** |

**共同点**：
1. **水位是【局部量】**（位置纯函数 / 出口高 + 固定比降 / 常数），**不是流域累积量**
2. **单元尺寸 ≥ 流域尺度**（否则边界落在陆地上 ⇒ 照抄会直接得到"边界断河"）

### 核实状态（2026-10-09，逐条回源码验证过）

| 断言 | 证据 |
|---|---|
| DW `REGION_SIZE=640` | `HydrologyManager.txt:3`（山地河 `:9` = 256）|
| **DW 零汇流累积** | 全仓搜 `accum\|discharge\|catchment\|watershed` ⇒ **命中 0 文件** |
| PL-RGA `border_safe`/`lake_safe` | `flat_infinite/src/terrain.cpp:319-320`，`border_margin = border_margin × plate_scale` |
| PL-RGA 河道不离 plate | `config.hpp:14` 注释原文 "so river channels never leave their plate"；`river_border_distance=100`（`:63`）、`river_lake_radius=150`（`:65`）|
| Streams 主干水位 = 海平面 | `Segment.scala:27` `lazy val surfaceLevel: BlockY = generator.minSurfaceLevel` |
| FTF 边框 edge 恒 0 | `EdgeFunction` 的 `DISTANCE_2_DIV`（`distance2 - 1.0`）⇒ 边框处 `distance==distance2` ⇒ 值 0 |

**对我们的启示**：
- **★ 骨架必须确定性、与窗口无关**（§〇②）—— 这是用户的硬需求（无限世界）。
  DW 的**共享锚点**是直接可借鉴的正解：本区汇点 = 邻区源点（同种子公式 ⇒ 逐位相同）。
- legacy 链**已有**跨区机制（`sampleAll` 3×3 邻区 + `OutletSeed`），与 DW/Streams 同源
  ⇒ 这是 **model 链（升级版）必须继承的既有优点**（§〇③），不是"legacy 的专利"
- model 链**丢了这两个机制**（只用本区、内陆窗口失效）⇒ 这是"进实机就断"的根因
- **湖水位局部化**：DW/Streams 干脆不做物理湖；我们保留物理湖（但要解决其窗口依赖）

---

## 六、2026-10-07~09 本轮改动

> 状态更新（2026-10-09 晚）：`f6aa223`/`7eb8748` 已提交；**meander 噪声实验已全部撤销**
> （`git checkout`，未提交）；未提交改动仅剩「断点归因诊断」（见 §六.3）。

### 已修的真 bug（已提交）

| # | bug | 根因 | 效果 |
|---|---|---|---|
| 1 | **内陆 region 全域失效** ⇒ 湖整块消失 | `EquilibriumWater:77` 的 `anyTrueDrain &&` ⇒ 窗口无海时 `resolved` 全 false；下游 `LakeWaterBalance:262` 要求 `resolved` | `region(0,-1)` basins **0→54**、lake **0→2699** |
| 2 | **region 缝上断半截** | `columnsFor` 只用本区，不查邻区 | 湖-竖缝断开 **44%→12%**、河-横缝 **58%→37%** |
| 3 | 我的工具判据用错 | 用 `erosion()>0` 判河（应用 `fillWater`）| 已改 |
| 4 | 湖判定方块化 | 粗格布尔按最近格点摊派 | 已改逐块连续判据 |
| 5 | 每 region 求解两次 | 实例级缓存 | 改 static 共享 + 按 seed 键控 |
| 6 | 河穿过湖面 | `RiverNetwork` 成河循环不排除湖面 | 加 `lakeMask` 排除（`f6aa223`）|

### ★ 2026-10-09 深夜：河"断流"的量化归因（**用户判据驱动，未修复**）

**用户判据**：「很多河流被截断了」「河在平坦坡上突然消失」「是真的有断的地方，不是视觉问题」。

**新增诊断** `-Dgeogenesis.riverBreakDiag=true`（`ModelHydrologyProvider`，方块级归因，
`ProductionViewProbe` 打印 `riverBreakReport()`）—— 适配层两道门分开数：

```
【小窗 r=300 实测】
onChannel=true  总格 = 31,336
 ├ 出水 √（surface > carved + 0.5）= 31,018 (99.0%)
 └ ★有河却没水 ✗                   =    318 (1.0%)  ← 断因 ①
距离场命中但 onChannel=false        =  2,499 (8%)    ← 断因 ②（粗格布尔误杀）
```

- **断因 ①「有河却没水」**：断点样本的 `surface − carved` 全部落在 **0.18~0.50**
  （刚好卡在 0.5 门槛下）⇒ `depth=1.0` 写死，平坦坡水位落差小 ⇒ 水柱不足 0.5 格
  ⇒ 不放水块 ⇒ 河断。候选修法（**未实施，待用户确认**）：河床下挖深度按水位差自适应
  （保证水面比床面高 ≥0.5）。
- **断因 ②「粗格误杀」**：`columnsFor` 的落块门控是 `p.channel()[k]`（STEP=4 粗格布尔），
  距离场（`SegmentIndex`，逐块精确）命中却被粗格判掉。粗格栅格化的像素级铁证：
  河段起点 `x mod 4 = 2` 占比 **97.7%**（参照图 step=1 均匀分布 34%）。
  候选修法（**未实施，待用户确认**）：粗格只算拓扑，落块用逐块距离场。

**⇒ 两个断因均已定量，修复方案待用户指挥后实施。**

### ⚠ meander 噪声实验（已撤销，勿重试同款参数）

移植 worldgen-master `add_meander_noise`（amp=6）实测：主干最大连通分量 **12,636 → 3,931**
（被切短 3 倍）⇒ 用户："肯定被你弄坏了"。已 `git checkout` 撤销。
**教训**：在 priority-flood 前改床面的做法在我方地形上会切碎主干；若再试必须先过
"连通性不回归"判据。

### 新增诊断旋钮（默认 = 生产原值 ⇒ 零行为变更）

```
-Dgeogenesis.modelRegion=512           region 边长
-Dgeogenesis.modelHalo=96              保护带（★ 探索中：照侵蚀比例应 ≥512）
-Dgeogenesis.modelStep=4               网格步长
-Dgeogenesis.riverThresholdFrac=0      成河阈值面积比例
-Dgeogenesis.inlandResolved=false      内陆窗口可信判据
-Dgeogenesis.localLakeLevel=false      湖水位用 sill（局部量）
-Dgeogenesis.modelRiverModel=d8        证据场模型
-Dhydro.viaProvider=true               正典图走生产 provider
```

### 新增工具

| 工具 | 用途 |
|---|---|
| `runProductionViewProbe` | 走 provider 出图（诊断 model 链，可调 region/step）|
| `runProductionChainCompareProbe` | legacy vs model **同规格并排**（走游戏同一入口）|
| `CanonicalViewProbe -Dhydro.viaProvider=true` | **正典规格图走生产路径**（= 游戏会落的水）|

### ★★ 量化验收基线（2026-10-09，正典规格 1401×1401 逐像素）

| | 参照图 | 生产（`inlandResolved=true`）| **覆盖率** | **精确率** |
|---|---|---|---|---|
| **湖** | 69,530 | 103,128 | **93%** | **63%** |
| **河** | 38,502 | 90,375 | 43% | **19%** |

**判读**：
- **湖的位置已高度吻合**（覆盖率 93%）⇒ "该有湖的地方都有湖"**已达成**；
  精确率 63% ⇒ 多出约 3.4 万格（判据偏宽）。
- **河道精确率 19%** ⇒ 生产河道有 **81% 在参照图里不是河**。

### ⚠ 河道的两类伪影（2026-10-09 出图确认，**当前最大问题**）

正典规格图显示：**北半部河道形态正确**（细、树状分叉、汇流成网），
但**南半部**有两类伪影：

| # | 伪影 | 现象 | 根因 |
|---|---|---|---|
| A | **等距平行竖直长线** | 海岸平原上，河道从内陆直插海里、间距均匀 | **D8 在平坦区只能从 8 个离散方向选** ⇒ 相邻列各自独立选向 ⇒ 平行线（与旧文档 §17.6「平坦区流向欠定」同源）|
| B | **矩形结构** | 左下角出现直角矩形 + 内部直线 | **region 边界（512 块）处判定不连续** |

**★ 两者同源**：骨架由「**窗口内** D8 汇流累积」派生
⇒ ① 汇流累积是窗口局部的 ⇒ 边界不连续；② D8 在平坦区退化 ⇒ 平行线。

**⇒ 正解 = 用户的既定方向（§〇②）**：改为**确定性骨架**（与窗口无关），
参考 **DW 的共享锚点**（本区汇点 = 邻区源点，同种子公式 ⇒ 逐位相同）
—— 既不依赖汇流累积，也不受平坦区影响。

### 已修：最小河宽与网格步长解耦（`c275394`）

```
RiverCenterlines L352: half = max(half, 0.5 * cellSize)      // cellSize = STEP
RiverCenterlines L496: max(cellSize, halfCells * cellSize)
```
⇒ `STEP=4` 时**最小全宽 4 块**（参照图 1~3 块）⇒ **河道宽度由地板而非水力几何支配**。

实测（`-Dgeogenesis.riverWidthDiag=true`）：

| step | half∈[0,1) | half∈[1,2) | half∈[2,3) |
|---|---|---|---|
| 4（修复前）| 0% | 0% | **90.3%** |
| 4（修复后）| **10.9%** | **45.6%** | 29.6% |
| 1 | 14.3% | 74.5% | 10.5% |

修法：地板改常量 `MIN_HALF_BLOCKS = 0.5`（游戏里水的最小单位恒为 1 块，与步长无关）。

## 六b、★ 2026-10-10：确定性骨架 + 三项形态修复（**用户判据驱动，已落地**）

> 本节是 `DeterministicSkeleton` 的当前事实。开关 `-Dgeogenesis.skeletonTrace=true`
> （骨架模式，model 链内二选一）+ `-Dgeogenesis.skeletonCrossPass`（双 pass 续流，默认开）。

### 用户判据（三轮迭代）

1. 「河流必须确定性骨架」（§〇②）→ `DeterministicSkeleton` 落地；
2. 「很多短碎河流 / 一小部分截断 / 反复交叉重叠」→ 三项修复（见下）；
3. 「两湖太近导致河短」→ 溢口门限覆盖大半；「多段反复缠绕是垂直精度太高导致抖动」
   → **谷底取小采样**（见下，用户的"垂直精度应对应独立单位"判断核实属实）。

### 三项修复与量化（正典 r=700 同参 A/B）

| # | 修复 | 根因 | 效果（用户截图版 → 现在） |
|---|---|---|---|
| 1 | **溢口起链加成河门限** | `basinOutlet` 无条件起链：湖占 5.24% ⇒ 每个盆地溢口都吐一条河。实测铁证 `[HYDRO-SRC] sources=0 traces=48`（源头筛光但河还在=全来自溢口）。**legacy 没有这一步**（河道全部来自流量掩码，湖只是终止条件） | 碎点≤5格 23→**6**（−74%）；短段≤20格 45→**10**（−78%） |
| 2 | **成河门限改绝对集水** | 旧 `n²×0.003` 是"占窗口比例"：legacy 窗口 1536 block ⇒ 7078 block²；我方 708 block ⇒ 1504 block² = **legacy 的 1/4.7** ⇒ 遍地成河。新口径 `chanMinCells×runoff`（discharge 单位 = 上游格数×runoff，实测 runoff=0.0192 校准） | 河格 1.16%→**0.85%**（参照 1.30%） |
| 3 | **谷底取小采样**（用户垂直精度判断） | bed 单点采样 ⇒ MC block 级垂直噪声泄漏进 D8"谁更低"判定 ⇒ 坡面流向逐格抖动=锯齿缠绕。legacy `buildSkeletonRivers` 2026-09-22 已做（4 角最小值）。实现于 `WindowOutlets.build`（`-Dgeogenesis.valleyMin=false` 退回） | **中位分量 37→111（×3）**；真断口 34→**8**（−76%） |

另补齐 legacy 三件套：`minNodes=6`（legacy `minRiverNodes=3`×尺度2）、
`selfApproach` 半径 4 豁免 12（legacy 2/6×尺度2）、**交叉守卫** `segmentCrossesAny`
（legacy `nearbyDownhillNode` 里 `_wouldCrossExistingSegments`，我此前漏掉）。

### 尺度换算表（★ 勿再推错）

| 项 | legacy（`SKELETON_CELL_BLOCKS=8`）| 我方（`STEP=4`）| 换算 |
|---|---|---|---|
| 格距 | 8 block | 4 block | 我方 1 格 = legacy 半格 ⇒ legacy 格数阈值 ×2 |
| 成河门限 | 110 格 | `chanMinCells=150` | 按实测 runoff 校准，非比例折算 |
| `minRiverNodes` | 3 格 | 6 格 | ×2 |
| `SELF_APPROACH_RADIUS` | 2 格 | 4 格 | ×2 |

### ⚠ 我在本轮犯的错（防重犯）

1. **`maxRivers=27` 亲手砍断主干**：凭"每 1000 block 折算 legacy 80"设了上限 ⇒
   河道 −38%、最大连通分量 4252→1823。单变量 A/B 证否后回退默认 0。
   **教训：legacy 的 `riverCount` 是"尝试数"且其 region 大我方 9 倍，数字不可移植。**
2. **门限单位连错两次**：先当 block² 写 7078（>maxQ 234 ⇒ `sources=0`），
   再除 STEP²（门限过激 ⇒ 多 region traces=0）。最后靠实测分布
   `[discharge] max=234 P99=57 runoff=0.0192` 校准。**教训：单位先实测再推算。**
3. **`minNodes` 丢弃链时整条释放 `covered`** ⇒ 把别的链的 JOIN 终点（别人内部节点）
   也释放 ⇒ 后继链穿过 ⇒ `SkeletonProbe [4]` 双占 FAIL。改为只释放本链新占格。
4. **跨区续流 v1 方向场接不上**（发送方窗外地形最陡下降 ≠ 接收方 minimax 树）
   ⇒ 缝上断口 5→9 反增。改为 legacy 双 pass：pass-1 各区独立产 `ExitSeed`，
   pass-2 由接收方用自己的 parent 追（`collectIncoming`）⇒ 缝上断口 5→1。

### 残余（如实，2026-10-10 用户目检"还行"后又圈出两处 → 当晚已修，见 §六c）

1. ~~陡坡紧密阶梯（梯子纹）~~ → §六c 有界粒子已修
2. ~~平地径向直线~~ → §六c 平地破平局已修（但见其"局限"）
3. **真十字交叉 11 个 / 2×2 重叠 48 个** —— 量小，未处理。
4. **最大分量 2988~3448** vs 参照 12,636 —— 主干仍偏短，待用户目检后定方向。

## 六c、★ 2026-10-10 深夜：用户圈出两处"不可能是伪影" → 定性+修复

> 用户原话：「不可能是伪影啊，我看图明显有这问题啊」（圈了梯子纹 + 两条笔直斜线）。
> 我此前把这两处记为"观感可接受"是**误判**，本节更正。

### 定性（非猜测，逐条归因）

| 现象 | 定性 | 证据 |
|---|---|---|
| **梯子纹**（缓坡河呈"横档+小竖档"） | 锚点钉死 4-block 格心 + D8 缓坡走"横 3~4 格降 1 格" ⇒ CR 重采样磨圆但档距还在 | 放大图 + 直线占比 33% |
| **笔直斜线横穿河网** | ① minimax 平地 parent=弹出序≈指向最近种子的径向直线（`EquilibriumWater:244` 自述）；② 左上角那几条**是地形冲沟**（用户已三次申明"平行线=地形"，不是河） | §17.6 + 放大图 |

### 修复（两个独立机制，均可单独开关）

| # | 机制 | 实现 | 量化（正典 r=700） |
|---|---|---|---|
| 1 | **平地破平局**（追踪侧，§17.6 既定方案） | `traceOne` 里若 `level[p]==level[k]`（等水位区内部），改选 **bed 最低的等水位邻居**（真实地形微坡），bed 也全等才退回 parent。单调性不破坏（level 非升）。`bed=null` 关闭 | 直线占比 33%→31%（**见下方"局限"**）；所有干净指标持平（断口 8、十字 12、双线 3） |
| 2 | **有界粒子锚点**（legacy `PARTICLE_POS` 同构） | `shape()` 里锚点沿 −∇bed 的正交分量偏移出格心（幅度 `PARTICLE_AMP_FRAC·step·|叉积|`，平行不偏、正交最大）。治的是"档距"本身 | **直线占比 33%→15.7%（−52%）**、水平长直段 43→20 段；梯子纹目视消失（三联图中图 vs 右图） |

### ★ 有界粒子的代价（如实，未消除只能折中）

粒子让锚点偏出格心 ⇒ 相邻河段在像素上贴近 ⇒ **贴线统计恶化**：

| 配置 | 真+字 | 2×2重叠 | 并排双线 | 直线占比 |
|---|---|---|---|---|
| 无粒子（基线） | 11 | 48 | 3 | 32.9% |
| 粒子 0.45（legacy 值） | 48 | 159 | 55 | 12.2% |
| **粒子 0.25（默认）** | 30 | 143 | 36 | **15.7%** |

- **为什么 0.45 不行**：我方格距 4 block（legacy 8），同样相对偏移对平行河视觉距离影响翻倍
  ⇒ legacy 值不可照抄（又一次刻舟求剑）。
- **为什么仍保留 0.25**：梯子纹是用户圈出的视觉缺陷；贴线统计涨但图上河线仍分离
  （三联图目检）。幅度可用 `-Dgeogenesis.particleAmp` 调（0=关）。

### ⚠ 平地破平局的局限（重要，勿误以为已修完）

破平局只在 `level[p]==level[k]`（**严格等水位**）时触发。实测直线占比只降 2 个百分点
⇒ 径向直线主要发生在**缓坡**（level 微差 > 1e-9，判据不触发，parent 仍是弹出序产物）。
真正把直线消掉的是**有界粒子**（几何层）。若日后残余直线仍可见，
候选修法 = 等水位判据放宽为 `|level[p]−level[k]| < ε_slope`（ε 待标定，可能伤单调性）。

### 验证

`runSkeletonProbe` / `runHydrologyDeterminismProbe` / `runLifecycleChainProbe` 全部 ALL PASS。
提交 `1a75aca`（三项形态修复）之上追加本轮（未提交时见工作区）。

### 新增诊断旋钮（骨架）

```
-Dgeogenesis.skeletonTrace=true         骨架模式开关（model 链内二选一）
-Dgeogenesis.skeletonCrossPass=false    双 pass 续流开关（默认开）
-Dgeogenesis.channelMinCells=150        成河门限（绝对集水，格）
-Dgeogenesis.valleyMin=false            谷底取小采样开关（默认开）
-Dgeogenesis.skeletonMinNodes=6         长度下限（格）
-Dgeogenesis.skeletonSelfRadius=4       自贴近半径（格）
-Dgeogenesis.skeletonSelfExempt=12      自贴近豁免步数
-Dgeogenesis.logSrc=true                打印 srcAccum/runoff/maxQ/traces 归因
```

### 未解决（如实）

1. **★ 河道确定性骨架**（§〇② 的核心目标）—— **已开工并落地**（本节），残余见 §六b
2. **R1 已修但未验收**：`-Dhydro.viaProvider=true` 可用，但尚未成为默认
3. **湖精确率 63%**：位置对（93%），但偏宽（多 3.4 万格）
4. **`HALO` 取值未定**：照侵蚀比例应为 `HALO ≥ REGION`，但实测湖量随之变化
5. **STEP=4 方块化**：图上是直角台阶
6. **`WaterField`（闭式水位场）已实测否决** —— 它是 legacy 链 `WaterLevelSolver`
   的"水位数据源"，而 model 链的湖是**盆地机制**（需 sill/rim/resolved）
   ⇒ 概念错位。类保留但无调用点（见 §六）
7. **门禁**：`runWorldgenGate` **ALL PASS**（12 探针）✅
   —— 但**新旋钮的默认值未变**，故门禁覆盖不到它们的效果

---

## 七、接手须知（血泪教训）

1. **修任何水文问题前，先跑 `-Dhydro.viaProvider=true` 出图** ——
   否则你看到的不是游戏会落的水。
2. **不要用"湖面积占比"对比不同窗口的图** —— 湖量随窗口大小变化（物理量本就依赖流域）。
   有意义的判据是**覆盖率/精确率**（见 §六基线表）。
3. **出图半径是硬断言** —— `ProductionViewProbe` 传非正典半径会**直接抛异常**
   （因为我在同一轮里重复用 `r=300` 与用户的 `r=700` 参照图对比，被用户两次指出）。
   确需小窗探索：`-Dhydro.allowNonCanonicalRadius=true`。
4. **判据必须与游戏落块层逐字一致**（§三）。
5. **"平行线"是地形冲沟，不是缺陷** —— 项目已多处记录
   （`CenterlineCrossSectionProbe:155`「用户已确认是地形正确形态，不得判失败」）。
6. **不要用"跨区一致性"当验收口径，除非比较窗口【边界带】**
   —— 我写过一个探针比较两窗口重叠区，但两中心只差 120 块、半径 252
   ⇒ 重叠区全在窗口深处、本来就一致 ⇒ 对照组与实验组结果无差别（0.00% vs 0.05%），
   **验收无效**。（原提交的 1456/1458 比较的是 `margin=320` 的边界带。）
7. **改地形/水文产出必须升 `PreviewDisplay.CACHE_SCHEMA_VERSION`**。
8. **`docs/` 与 `参考/` 被 `.gitignore` 忽略** ⇒ 新文档放仓库根才入库。

---

*本文为水文系统的唯一事实来源。历史文档见 `_archive/hydrology/`（只读，仅供考古）。*
