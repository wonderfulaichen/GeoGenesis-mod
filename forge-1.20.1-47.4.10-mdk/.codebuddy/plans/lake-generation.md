# 湖泊生成：洼地湖（priority-flood 填洼）

# 湖泊生成：洼地湖（priority-flood 填洼）

> 状态：待确认（方案阶段）。确认前不动生产代码。
> 类型：洼地内流湖 / 开口洼地湖（湖面=溢出高程）；规模：按地形自动。

## 一、需求与现状

**现状（实测 3 种子 × 9 region = 27 个 region）**：湖数 **0**。

已有机制只有一条路径，且基本是死的：

- `FlowField.lowestNeighbor` 要求**严格更低**才连边 → `flowTo = -1` 即"洼地"
- `traceRiver` 遇 `down < 0`：靠边界 → `outlet`；否则 `isLake = true`
- 成湖后 `LakeNode(x, z, height)`，height 取该河节点 slope-drop 高度，**半径固定** `lakeRadius=120wu`

死因：平滑程序化地形里**闭合洼地（真内流）极少**；且真湖大多不是闭合洼地，而是
**开口洼地**——有出口，但出口坎（sill）高于盆底，水填到坎高后溢出继续流。

**已具备（不用重做）**：`RiverLineRegion.LakeNode`、`lakeRadius/lakeMargin/lakeFadeDist`
参数、`sampleRegion` 的湖命中、`HydrologyBlockCarver` 的湖面展平与水面填充——
**雕刻与灌水链路已通，缺的是"去哪放湖 + 湖怎么进河网拓扑"**。

**目标**：湖面水平、岸线自然、按洼地大小自动定尺寸、与河网连通（河入湖、湖溢流出河），
无悬空湖、不破坏跨 region 缝一致性。

## 二、技术方案

标准做法：DEM 填洼（priority-flood）。填洼后的 `e_filled` 与原始 `e` 的差集即水域：
`e_filled > e` 的格 = 被水填起来的部分，水面 = 该洼地的 `e_filled`（= 溢出高程）。
一次算法同时覆盖闭合洼地与开口洼地。

### 阶段 0：诊断定阈值（先做，决定成败）
- 统计：D8 sink（`flowTo<0`）数量、priority-flood 后洼地的**深度 / 面积分布**
- 输出：定 `lakeMinDepth`（最小水深）、`lakeMinCells`（最小格数）阈值
- 风险预判：噪声小坑可能极多 → 靠阈值 + 最小格数过滤，必要时先对 e 做轻微平滑

### 阶段 1：FlowField 填洼
- 新增 `eFilled[]`：priority-flood（最小堆，从网格边界向内，Barnes 2014 简化版）
- 新 API：`filledAt(idx)`、`isLakeCell(idx)`、`spillElevation(idx)`
- 复杂度 O(n log n)；每 region 约 (3200/24)² ≈ 1.8 万格，开销可忽略

### 阶段 2：洼地提取成湖
- 连通标记 `e_filled > e + eps` 的格 → 每个洼地：底高程、溢出高程、面积、质心
- 过滤：深度 ≥ `lakeMinDepth`、格数 ≥ `lakeMinCells`、离 region 边界 ≥ `borderDist`
  （PL-RGA `lake_safe_mask`：缝带不成湖）
- 产出 `LakeNode(中心, spill 高程, 半径(由面积换算), 深度)`
  → 半径不再用全局 `lakeRadius`

### 阶段 3：拓扑接入（湖进河网）
- 河 trace 进入湖格 → 终止为湖（复用 `isLake`），水面取**该湖 spill 高程**，
  不再用 slope-drop 高度（否则湖面与溢出坎对不上）
- 多河入同一湖共享 LakeNode（河网不变、拓扑正确）
- 湖满溢 → 从**溢出口格**继续 trace 一条下游河（复用现有续流/汇入机制），
  保证水文不断、不出现无出口的死湖

### 阶段 4：雕刻 / 灌水接入
- `LakeNode` 携带 per-lake 半径与深度；`sampleRegion` 用每湖半径取最近湖
- 湖面展平 + 岸线淡出（沿用 `lakeFadeDist`）
- 验收：灌水探针不产生 `dryChannel` / `bankOverflow`

### 阶段 5：跨 region 一致性
- 湖完全落在 `borderDist` 内；margin 区检测到的洼地邻区也会检测到 →
  按"谁拥有洼地中心格"去重
- 验收：`ValleySeamProbe` 不退化（基线 334 对 / 最大坎 9.0）

### 阶段 6：探针与验收
- 新增 `LakeProbe`：湖数、面积/深度分布、入湖河数、**悬空湖数（应 0）**、无出口湖数
- `SourceValleyProbe` 已加湖计数（`[LAKES]`）
- 回归：灌水 20 种子（dryChannel/noWaterBlock/bankOverflow 全 0）、
  瀑布 4 探针 PASS、ValleySeam、源头质量（marginAvg 2.09/6.72、insideOther 0/30）

## 三、风险与对策

| 风险 | 对策 |
|---|---|
| 噪声小坑过多 → 满地水塘 | 最小深度/格数阈值；必要时轻度平滑 DEM |
| 湖面与河面接不上（台阶） | 阶段 3 用 spill 高程统一；判据对齐灌水/边界探针 |
| 湖出水没接上（断流） | LakeProbe 计"无出口湖/悬空湖"，必须 0 |
| 跨区重复湖 | borderDist 安全区 + 中心格归属去重 |
| 探针口径与生产不一致（本项目已栽 5 次） | 新增判据逐行对齐生产实现，不凭语义近似 |

## 四、任务列表

| # | 任务 | 涉及文件 |
|---|---|---|
| 1 | 洼地分布诊断 → 定阈值 | 新：diagnostics `LakeBasinProbe` |
| 2 | FlowField priority-flood 填洼 + API | `flowaccum/FlowField.java` |
| 3 | 洼地连通标记 → 湖提取（含门槛/去重） | 新：`riverline/LakeExtractor.java` |
| 4 | 拓扑接入：入湖终止、spill 水面、溢出续流 | `RiverLineNetwork.java` |
| 5 | LakeNode 携带半径/深度；sampleRegion 用每湖半径 | `RiverLineRegion.java`、`RiverLineNetwork.java` |
| 6 | 跨区/缝带处理 | `RiverLineNetwork.java` |
| 7 | LakeProbe + 全量回归 | 新：`diagnostics/LakeProbe.java` |
| 8 | 提交 + 本方案归档 | — |

## 五、建议顺序
先做 **1→3→4** 打通"能出湖、拓扑正确"，看游戏内效果再调地形参数
（阈值、平滑、半径换算）；**5→6** 紧随；**7** 验收。

## 六、参考材料结论
Streams（Scala）、Farseek/mainStreams、dynamicwaters 三份参考**均无湖泊实现**
（纯河流），湖需按本项目 D8 流场 + 河网自研。

## 七、实施结果（2026-09-07，已完成）

| 提交 | 内容 |
|---|---|
| `33e85a2` | 洼地湖：FlowField priority-flood 填洼层（真实地形采样）、湖提取（格数≥2/水深≥0.6/海平面/中心归属/1格缝带距）、入湖终止+溢出续流拓扑、LakeNode 带 radius/depth、sampleRegion 每湖半径 |
| `ef684d5` | 河成过水湖：detectLakeReaches（坡度<0.002/wu、无跌水、长度≥48block、河头淡出带外、占比≤50%）、湖面=reach 下游端水面、宽度×3、深度收 minDepth、frozen 禁混合 |

**产量**（9 region）：洼地湖 1~3 个 + 河成湖 6~7 条河（700~1016 block 湖岸）≈ 8~10 处水域。

**关键发现**：
- 本地形闭合洼地极少（旧机制 0 湖），且高于海平面的洼地占少数（~64% 的洼地
  溢出坎低于海平面，被正确排除为海底）→ 洼地湖产量天花板低，河成湖是密度主来源
- 湖出口河的起点不是泉眼，探针已排除其源头考核（不排除会假性拉低 marginAvg）

**回归**：瀑布 4 探针 PASS；灌水 20 种子全 0；ValleySeam 435 对 / 最大坎 9.0
（湖岸台阶属正常地貌，无新陡坎）；源头质量 marginAvg 2.45/6.72、insideOther 0/30。

**未做 / 备选**：山间冰斗湖、低地构造大湖（用户未选）；DEM 预平滑提升洼地
产量（湖面需在真实地形上重算溢出坎，未做）。
