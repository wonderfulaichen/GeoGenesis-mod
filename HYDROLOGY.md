# 水文系统 —— 单一事实来源（2026-10-09 重建）

> **本文件替代根目录原有 12 份水文文档**（已归档至 `_archive/hydrology/`）。
> 归档原因：那些文档彼此矛盾、多数已过时（详见 `_archive/hydrology/README.md`），
> 继续放在根目录会误导接手者 —— 本项目已因此连续出错多轮。

---

## 一、当前状态（唯一权威）

| 项 | 值 |
|---|---|
| 工作区 | `D:\Office software\Development Project\GeoGenesis-mod`（仓库根） |
| 分支 | `fix/port-lake-p2-2` |
| HEAD | `e9f08ac`（**之上有未提交改动**，见 §六） |
| 生产水文入口 | `HydrologyChunkEngine.calculate` → 二选一（见 §二） |
| 未推送 | 是 |

⚠ **`_model_wt/` worktree 已不再是独立代码线** —— model 链已于 `848fdc1` 移入 `src/main`，
现由开关切换。旧文档里"两条并行代码线"的描述**已失效**。

---

## 二、生产有两条链（由开关二选一）

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
| 状态 | **用户确认：湖基本正常，河位置奇怪** | 开发中（修"河位置"问题的重写）|
| 默认 | ✅ | ❌ |

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
- legacy 链的跨区机制（`sampleAll` 3×3 邻区 + `OutletSeed`）**方向正确**，与 DW/Streams 同源
- model 链**丢了这两个机制**（只用本区、内陆窗口失效）⇒ 这是"进实机就断"的根因
- **湖水位局部化**是可选路线（DW/Streams 干脆不做物理湖）；我们保留了物理湖

---

## 六、2026-10-07~09 本轮改动（**全部未提交**）

### 已修的真 bug

| # | bug | 根因 | 效果 |
|---|---|---|---|
| 1 | **内陆 region 全域失效** ⇒ 湖整块消失 | `EquilibriumWater:77` 的 `anyTrueDrain &&` ⇒ 窗口无海时 `resolved` 全 false；下游 `LakeWaterBalance:262` 要求 `resolved` | `region(0,-1)` basins **0→54**、lake **0→2699** |
| 2 | **region 缝上断半截** | `columnsFor` 只用本区，不查邻区 | 湖-竖缝断开 **44%→12%**、河-横缝 **58%→37%** |
| 3 | 我的工具判据用错 | 用 `erosion()>0` 判河（应用 `fillWater`）| 已改 |
| 4 | 湖判定方块化 | 粗格布尔按最近格点摊派 | 已改逐块连续判据 |
| 5 | 每 region 求解两次 | 实例级缓存 | 改 static 共享 + 按 seed 键控 |

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

### 未解决（如实）

1. **R1 已修但未验收**：`-Dhydro.viaProvider=true` 可用，但尚未成为默认
2. **湖偏多**：5.58% vs 正典 3.63%（位置一致度 93%，多出的落在"参照的陆地"上）
3. **河道掩码口径**：距离场（5.58%）vs 流量阈值（1.96%）
4. **`HALO` 取值未定**：照侵蚀比例应为 `HALO ≥ REGION`，但实测湖量随之变化
5. **STEP=4 方块化**：图上是直角台阶；STEP=1 正典规格单张图 >40 分钟（实测未跑完）
6. **门禁**：本轮改动后 `runWorldgenGate` 实测 **ALL PASS**（12 探针 / 3m54s）✅
   —— 但**新旋钮的默认值未变**，故门禁覆盖不到它们的效果

---

## 七、接手须知（血泪教训）

1. **修任何水文问题前，先跑 `-Dhydro.viaProvider=true` 出图** ——
   否则你看到的不是游戏会落的水。
2. **不要用"湖面积占比"对比不同窗口的图** —— 湖量随窗口大小变化（物理量本就依赖流域）。
   有意义的判据是**位置一致度**。
3. **判据必须与游戏落块层逐字一致**（§三）。
4. **"平行线"是地形冲沟，不是缺陷** —— 项目已多处记录
   （`CenterlineCrossSectionProbe:155`「用户已确认是地形正确形态，不得判失败」）。
5. **改地形/水文产出必须升 `PreviewDisplay.CACHE_SCHEMA_VERSION`**。
6. **`docs/` 与 `参考/` 被 `.gitignore` 忽略** ⇒ 新文档放仓库根才入库。

---

*本文为水文系统的唯一事实来源。历史文档见 `_archive/hydrology/`（只读，仅供考古）。*
