# HYDRO 参考项目调研笔记（2026-10-14）

> **文档性质：调研快照，不是事实来源。**
> 本文只记录"读了哪些参考源码、逐条核实到什么"，**不覆盖、不改写 [`HYDROLOGY.md`](HYDROLOGY.md)**
> —— 后者仍是水文唯一事实来源。本文**不宣称**任何进行中的"方向层 / 湖节点权威"工作已完成。

## 0. 核实基线

- 仓库 `D:\Office software\Development Project\GeoGenesis-mod`，分支 `fix/port-lake-p2-2`，
  **HEAD = `16dbc4342fa821ec8698d647463f1d3551a1d8d9`（`16dbc43`）**（`git rev-parse HEAD` 与 `git log --oneline` 双证）。
- 所依据的"最后调研段"：`build/hierarchical-evaluation/lifecycle-implementation-checkpoint.txt` 的
  [第 94–98 行](forge-1.20.1-47.4.10-mdk/build/hierarchical-evaluation/lifecycle-implementation-checkpoint.txt#L94-L98)
  （标题 2026-10-14 local commit and reference research）。⚠ 该文件在 `build/` 下，被
  [`forge-1.20.1-47.4.10-mdk/.gitignore:17`](forge-1.20.1-47.4.10-mdk/.gitignore#L17)（`build`）忽略，**不入版本库**；
  其开头段落仍写 `HEAD de78019`，**状态只以第 94–98 行为准**。
- `参考/` 整体被根 [`.gitignore:39`](.gitignore#L39) 忽略（`git check-ignore -v` 可复核）。
- **未运行、未导入任何参考代码**，全程只读阅读。
- 补注（不修改 HYDROLOGY.md）：其 §一 仍记 `HEAD 7eb8748`，**已落后于当前 HEAD**。

## 1. 设计结论：容量 / 方向 / 水面的职责划分（**我方提炼**）

> 本节是**我们从多家参考提炼出的设计结论**，**不代表**任一参考项目同时实现了这三层，
> 也不代表三家"做法一致"。

- **容量（connectivity）**：相邻单元"可否连通"的**对称**门槛值，与水的去向无关。
- **方向（authority）**：有向的父子 / 出口关系。**在连续地形上，最陡下降可以真实决定方向**；
  只有**平地、盆地、台地**等局部地形无法唯一决定处，才必须引入**共同出口 / 权威关系**来消解
  —— ⇒ 不是"不能靠局部地形"，而是"局部无解处需外部裁定"。
- **水面（water surface）**：物理上**可以依赖流域水量**（湖 / 水库水量平衡）。
  **禁区不是"用流域量"，而是"某个独立窗口未获认证的结果冒充完整上游"**（窗口局部累积被当成全局）。

对应我方现状：`SharedBoundaryDrainage` / `SharedBoundaryTransfer` 的对称容量树只实现**第 1 层**，
**不能**替代第 2 层方向 —— checkpoint 第 71/91 行已如实记录"容量树 tie 几何产生长轴向直线、不是天然中心线"。

## 2. 逐项目：借什么 / 不借什么（证据见 §4 索引）

### 2.1 FreeTerraForged（FTF，MIT）——借"布局先行"，不借闭式水面与折痕（§4 #1–#5）

- **布局先行**：河网由**大陆布局**生成（根河 + 递归分叉），并用 `getEdgeValue` 早退把河挡在大陆内，
  **非**任意地形排水自我涌现。
- **水位是闭式阶梯水台面**：`waterTable` + `getComplexWaterHeight`；台阶由"平坦高原 + **smoothstep** 斜坡"构成；
  河谷阶地用 `Math.floor` 取步号 + smoothstep + `lerp` 回混 —— **不是纯硬量化**。
  ⇒ 不借的理由是它属**布局 / 风格函数**，不是从地形排水涌现的水面。
- **折痕**：`gullyShape / rivuletShape` 用 `1-|raw|`。这与禁令 §6 的 `|2n−1|` **不是同一式子**，
  但同属"绝对值折痕 ⇒ 等值线成对"的**风险类**，故**不采用**。
- **"只降不升"守卫**（`carvedThisPass` / `isSubMerged`）只保证**单次雕刻不抬升**，
  **不证明**多场 / 雕刻顺序无关，也不解决拓扑。

### 2.2 MOBIDIC（Apache-2.0）——借"水量恰好一次注入"，不借"代表点粗化"（§4 #6–#10）

- **外部预设**：栅格 `flow_dir`/`flow_acc` 由外部提供（示例用 GRASS `r.watershed`）；
  河网拓扑从 shapefile 建并预计算拓扑序 `calc_order`。
- **值得借（恰好一次的水量归属）**：见 §4 #8 —— 水库在**侧向入流累积之前**做路由，
  把**库区格**的 `flr/fld` 归零，并把**出口河段的上游连接**清零（注释对应 MATLAB `glob_route_day.m:48-50`），
  再于累积之后对出口河段**一次性注入**下泄。
  ⚠ **精确语义**：[reservoir.py:372–378](参考/sources/MOBIDICpy-main/mobidic/core/reservoir.py#L372-L378)
  清零**库区格** `flr/fld` 与**入口河段** `Q`；[simulation.py:1922–1936](参考/sources/MOBIDICpy-main/mobidic/core/simulation.py#L1922-L1936)
  清除路由副本中**出口河段的上游连接**。不是清零出口的 `flr/fld` 或删除物理河网。
- **不借**：粗化（§4 #10）—— 代表点取格内**累积最大单点**；
  若其下泄仍落**本粗格**，`offset_to_dir` 无 `(0,0)` 键 ⇒ `-999`，随后以**首有效邻居**修补，无水力 / 拓扑证明。
- **依赖性质**：河道演算内核逐段循环（§4 #9），沿 `calc_order` 为每段累加**全部上游**贡献
  ⇒ **直接上游方程传递依赖全上游**，非"无限世界有限查询"。

### 2.3 PL-RGA（CC-BY-4.0）——不借"挡河 / 回滚删河"，借"最终采样判据"（§4 #11–#14）

- **安全边界 = 主动回避**（`border_safe` / `lake_safe`，前提注释 "so river channels never leave their plate"）；
  **回滚删河**（§4 #12：清 `segments`/`nodes`、置 `rolled_back`）。
  ⇒ 二者前提是"**从不让河跨边界**"，与"修复**已发布**的跨缝断河"**目标不相容**，**不能移植作断河修复**。
- **`applySlopeDrop` 是产出加工、不是验证**（§4 #13，把节点河面**重排**为单调下降，属**生产者**）
  —— **不得**借它"改水位"充当**验收证据**。
- 要借的是**最终采样判据**（§4 #14）：单测断言 `from_height + 1e-5 >= to_height`（"river does not rise downstream"），
  以及 metrics 说明中的"存储段下降校验 + **末态地形沿每段 16 点采样**、任何 `>1e-9` 下游抬升计数"。

### 2.4 Streams / Farseek（MIT）——借"接口契约"，不借"复制 / 强行西流"（§4 #15–#19）

- **入口从出口派生，带位移与瀑布调整，不是字节复制**：对列做位置位移 `pos :+ streamDirection`，
  `isFallRim` 时 `maxFloorLevel = surfaceLevel :- plungePoolDepth`。
- **主干水位 = 海平面常数**；支流落差 `fallHeight` 单独处理。
- **最短路是累加边权，不是 minimax**：Dijkstra `pathCostTo(here) ++ edgeCostToThere`（**求和**）
  ⇒ 与我方 minimax 屏障是**不同算子**，不可互替 / 互证。
- [**`StreamsGenerator.scala:122-127`**](参考/river/Farseek-Mods/src/mainStreams/scala/streams/world/gen/StreamsGenerator.scala#L122-L127)**（§4 #18）不是"向东无限递归"**：`isOrWillBeUpstreamSegment` 自带注释
  "don't auto-create on segment lookup, or will recurse forever"，只读**已缓存**的 `basinSegments` ⇒ 局部缓存命中判定。
- **`likelyInSea` 不是全球海证书**：注释自述 "can be a false positive for a sealed flooded cave near surface"
  ⇒ 承认封闭洞穴假阳性。

### 2.5 Dynamic Waters（DW）——不复制实现；"共享锚点"概念可独立借鉴（§4 #20–#21）

- DW 许可 = All Rights Reserved（§4 #21）⇒ **不复制任何实现代码**。
- 但**共享锚点这一设计思想可以独立借鉴**：本区汇点 = 邻区源点，**同种子公式 ⇒ 逐位相同**
  —— [`HYDROLOGY.md` §五](HYDROLOGY.md) 已记此思路（与 Streams 的跨区续流同源）；其区域尺度可作对照
  （`REGION_SIZE = 640`，山地 = 256）。

## 3. 我方当前状态（不得说成已完成）

HEAD `16dbc43`（`feat(hydro): 保存边界容量摘要与河湖生命周期诊断检查点`，7 文件 / +3075 / −9，
仅诊断类与 `SharedLakeDomain` / `ModelHydrologyProvider` 微调）。checkpoint 明确：

- **canonical-boundary 仍 `in_progress`**；basin-outlet-authority / terrain-routing / production-acceptance **均 pending**（第 92 行）。
- **原 3 个横向接缝断河仍复现，未修复；`production` 水柱未变**（第 56/62/74/90/92 行）。
- 最后真实验收仍 **FAIL / `SEA_CANDIDATE`**（第 90 行）；3 场景"海见证"是**容量见证，非生成水面**（第 91 行）。
- 2 个正控连通、3 个横缝负例仍断（第 95 行）。

⇒ 故本文**未**把方向层或湖节点权威写成已完成。

## 4. 证据索引（可点击，全路径 + 行号）

| # | 论断 | 证据（可点击） |
|---|---|---|
| 1 | FTF 布局先行、非任意地形排水 | [`参考/sources/FreeTerraForged-1.21.1/.../rivermap/river/BaseRiverGenerator.java:48-71,53-56,77-119`](参考/sources/FreeTerraForged-1.21.1/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/rivermap/river/BaseRiverGenerator.java#L48-L119) |
| 2 | FTF 闭式阶梯水台面（smoothstep 斜坡） | [`参考/sources/FreeTerraForged-1.21.1/.../cell/rivermap/ContinentalHydrology.java:96-125`](参考/sources/FreeTerraForged-1.21.1/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/rivermap/ContinentalHydrology.java#L96-L125)；[`参考/sources/FreeTerraForged-1.21.1/.../rivermap/river/UpliftRiverCarver.java:137-142`](参考/sources/FreeTerraForged-1.21.1/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/rivermap/river/UpliftRiverCarver.java#L137-L142) |
| 3 | FTF 阶地 `Math.floor`+smoothstep+lerp（非纯硬量化） | [`参考/sources/FreeTerraForged-1.21.1/.../rivermap/river/UpliftRiverCarver.java:376-422`](参考/sources/FreeTerraForged-1.21.1/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/rivermap/river/UpliftRiverCarver.java#L376-L422) |
| 4 | FTF 折痕 `1-\|raw\|`（风险类，不采用） | [`参考/sources/FreeTerraForged-1.21.1/.../rivermap/river/UpliftRiverCarver.java:157-160`](参考/sources/FreeTerraForged-1.21.1/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/rivermap/river/UpliftRiverCarver.java#L157-L160) |
| 5 | FTF "只降不升"守卫的确切位置 | [`参考/sources/FreeTerraForged-1.21.1/.../rivermap/river/UpliftRiverCarver.java:184-193`](参考/sources/FreeTerraForged-1.21.1/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/rivermap/river/UpliftRiverCarver.java#L184-L193) |
| 6 | MOBIDIC 外部预设流向 / 累积 | [`参考/sources/MOBIDICpy-main/mobidic/config/schema.py:88-93`](参考/sources/MOBIDICpy-main/mobidic/config/schema.py#L88-L93)；[`参考/sources/MOBIDICpy-main/examples/README.md:259`](参考/sources/MOBIDICpy-main/examples/README.md#L259) |
| 7 | MOBIDIC 预设拓扑 + 拓扑序 | [`参考/sources/MOBIDICpy-main/mobidic/preprocessing/river_network.py:94-96,103-154`](参考/sources/MOBIDICpy-main/mobidic/preprocessing/river_network.py#L94-L154)；[`参考/sources/MOBIDICpy-main/mobidic/core/routing.py:366`](参考/sources/MOBIDICpy-main/mobidic/core/routing.py#L366) |
| 8 | MOBIDIC 水库截获 + 出口一次注入（值得借） | [`参考/sources/MOBIDICpy-main/mobidic/core/simulation.py:1893-1953`](参考/sources/MOBIDICpy-main/mobidic/core/simulation.py#L1893-L1953)（重点 :1922-1936、:1948-1953） |
| 9 | MOBIDIC 逐段内核仍依赖全上游 | [`参考/sources/MOBIDICpy-main/mobidic/core/routing.py:159-239`](参考/sources/MOBIDICpy-main/mobidic/core/routing.py#L159-L239)（累加 :199-212）；[`参考/sources/MOBIDICpy-main/mobidic/core/simulation.py:1168`](参考/sources/MOBIDICpy-main/mobidic/core/simulation.py#L1168) |
| 10 | MOBIDIC 粗代表点可落本格 + 首有效邻居修补 | [`参考/sources/MOBIDICpy-main/mobidic/preprocessing/grid_operations.py:174-202,224-241`](参考/sources/MOBIDICpy-main/mobidic/preprocessing/grid_operations.py#L174-L241) |
| 11 | PL-RGA 安全边界 = 主动回避（不借作修复） | [`参考/river/plate-local-river-generation-main/flat_infinite/src/terrain.cpp:304-320`](参考/river/plate-local-river-generation-main/flat_infinite/src/terrain.cpp#L304-L320)；[`参考/river/plate-local-river-generation-main/flat_infinite/include/flat_infinite/config.hpp:12-14,63,65`](参考/river/plate-local-river-generation-main/flat_infinite/include/flat_infinite/config.hpp#L12-L65) |
| 12 | PL-RGA 回滚删河（不借作修复） | [`参考/river/plate-local-river-generation-main/flat_infinite/src/rivers.cpp:405-463`](参考/river/plate-local-river-generation-main/flat_infinite/src/rivers.cpp#L405-L463) |
| 13 | PL-RGA `applySlopeDrop` 是加工（非证据） | [`参考/river/plate-local-river-generation-main/flat_infinite/src/rivers.cpp:243-280`](参考/river/plate-local-river-generation-main/flat_infinite/src/rivers.cpp#L243-L280) |
| 14 | PL-RGA 最终采样判据（应借） | [`参考/river/plate-local-river-generation-main/flat_infinite/tests/test_flat_infinite.cpp:102,118`](参考/river/plate-local-river-generation-main/flat_infinite/tests/test_flat_infinite.cpp#L102-L118)；[`参考/river/plate-local-river-generation-main/flat_infinite/metrics/README.md:45-52`](参考/river/plate-local-river-generation-main/flat_infinite/metrics/README.md#L45-L52) |
| 15 | Streams 入口派生出口：位移 + 瀑布调整 | [`参考/river/Farseek-Mods/src/mainStreams/scala/streams/world/gen/segments/Segment.scala:42-56`](参考/river/Farseek-Mods/src/mainStreams/scala/streams/world/gen/segments/Segment.scala#L42-L56)；[`参考/river/Farseek-Mods/src/mainStreams/scala/streams/world/gen/segments/UpstreamSegment.scala:19-44`](参考/river/Farseek-Mods/src/mainStreams/scala/streams/world/gen/segments/UpstreamSegment.scala#L19-L44) |
| 16 | Streams 主干水位 = 海平面；支流落差 | [`参考/river/Farseek-Mods/src/mainStreams/scala/streams/world/gen/segments/Segment.scala:27`](参考/river/Farseek-Mods/src/mainStreams/scala/streams/world/gen/segments/Segment.scala#L27)；[`参考/river/Farseek-Mods/src/mainStreams/scala/streams/world/gen/segments/TributaryNode.scala:57-59`](参考/river/Farseek-Mods/src/mainStreams/scala/streams/world/gen/segments/TributaryNode.scala#L57-L59) |
| 17 | Streams Dijkstra 累加边权（非 minimax） | [`参考/river/Farseek-Mods/src/mainStreams/scala/streams/world/gen/segments/TributaryBasin.scala:29-34`](参考/river/Farseek-Mods/src/mainStreams/scala/streams/world/gen/segments/TributaryBasin.scala#L29-L34)；[`参考/river/Farseek-Mods/src/main/scala/farseek/util/graphs.scala:12-29`](参考/river/Farseek-Mods/src/main/scala/farseek/util/graphs.scala#L12-L29) |
| 18 | `StreamsGenerator:122-127` 非无界东递归 | [`参考/river/Farseek-Mods/src/mainStreams/scala/streams/world/gen/StreamsGenerator.scala:104-105,122-127`](参考/river/Farseek-Mods/src/mainStreams/scala/streams/world/gen/StreamsGenerator.scala#L104-L127) |
| 19 | `likelyInSea` 自认封闭洞穴假阳性 | [`参考/river/Farseek-Mods/src/mainStreams/scala/streams/world/gen/StreamsGenerator.scala:77-82`](参考/river/Farseek-Mods/src/mainStreams/scala/streams/world/gen/StreamsGenerator.scala#L77-L82) |
| 20 | DW 共享锚点思路（概念可借）+ 尺度 | [`HYDROLOGY.md` §五](HYDROLOGY.md)；[`参考/river/dynamicwaters-11.1.2/HydrologyManager.txt:3,9`](参考/river/dynamicwaters-11.1.2/HydrologyManager.txt#L3) |
| 21 | DW 许可 = All Rights Reserved | [`参考/river/dynamicwaters-11.1.2/META-INF/mods.toml`](参考/river/dynamicwaters-11.1.2/META-INF/mods.toml) |
| 22 | HEAD / 分支 | `git rev-parse HEAD` → `16dbc4342fa821ec8698d647463f1d3551a1d8d9`，分支 `fix/port-lake-p2-2` |
| 23 | checkpoint 来源与忽略状态 | [`forge-1.20.1-47.4.10-mdk/build/hierarchical-evaluation/lifecycle-implementation-checkpoint.txt:94-98`](forge-1.20.1-47.4.10-mdk/build/hierarchical-evaluation/lifecycle-implementation-checkpoint.txt#L94-L98)；`git check-ignore -v` → [`forge-1.20.1-47.4.10-mdk/.gitignore:17`](forge-1.20.1-47.4.10-mdk/.gitignore#L17) |

## 5. 许可核实

| 项目 | 许可 | 证据 |
|---|---|---|
| FreeTerraForged | **MIT** | [`参考/sources/FreeTerraForged-1.21.1/LICENSE:1-3`](参考/sources/FreeTerraForged-1.21.1/LICENSE) |
| Farseek / Streams | **MIT** | [`参考/river/Farseek-Mods/LICENSE:1-3`](参考/river/Farseek-Mods/LICENSE) |
| MOBIDIC | **Apache-2.0** | [`参考/sources/MOBIDICpy-main/LICENSE:1-3`](参考/sources/MOBIDICpy-main/LICENSE) |
| PL-RGA | **CC-BY-4.0** | [`参考/river/plate-local-river-generation-main/LICENSE:1-3`](参考/river/plate-local-river-generation-main/LICENSE) |
| Dynamic Waters | **All Rights Reserved**（仅元数据） | [`参考/river/dynamicwaters-11.1.2/META-INF/mods.toml`](参考/river/dynamicwaters-11.1.2/META-INF/mods.toml) |

⇒ 除 DW 外均可借鉴，但**须署名并保留许可文本**；DW **不得复制实现**。本文**未运行、未导入**任何参考代码。

## 6. 后续门槛（借用前必须过）

1. **三层职责分开**：方向层（authority）与对称容量分离，再谈水面；
   **禁止**把对称容量树直接当最终中心线或水面。平地 / 盆地须由**共同出口关系**裁定，连续地形可用最陡下降。
2. **出口原子性**：按 MOBIDIC 语义为每个湖 / 盆地定**唯一合法出口**与**恰好一次**水量注入；
   未能裁定则保持 `UNKNOWN`，不得用坐标排序伪造方向。
3. **禁止"窗口冒充全局"**：可用流域水量，但**不得**把未被认证的独立窗口结果当作完整上游。
4. **边界容量只是局部证据**：`SharedBoundaryTransfer` 的已知海洋代价是**局部候选**，须保留未知项；
   不得因"海代价 == 未知代价"就断言世界范围内无海路。
5. **借验证，不借加工**：纵向单调性以**最终采样判据**验收（见 §2.3）；不得用产出加工步骤充当验收；
   亦不得照搬 PL-RGA 的挡河或回滚删河作断河修复。
6. **回归口径**：原 3 横缝断河与 2 正控必须**逐位可复现**；实验 checkpoint **不得**表述为"断河已修"。

---

*上文为 2026-10-14 调研快照；[HYDROLOGY.md](HYDROLOGY.md) 仍是水文唯一事实来源。*

## 7. 2026-10-23补查：独立入流发现与几何权威（批注16）

本次只读研究、未运行参考程序、未复制实现。两条研究分支并行核查，关键结论再回源确认。
不要把参考项目的不同前提合称为“都已解决当前条件D8的多区问题”。

### 7.1 Streams真正值得借的是“出口不依赖整条上游”

- MIT已重读 [LICENSE](参考/river/Farseek-Mods/LICENSE#L1-L13)。实际源码在Farseek-Mods；单独Streams目录没有生成源码。
- [StreamsGenerator.scala:107-138](参考/river/Farseek-Mods/src/mainStreams/scala/streams/world/gen/StreamsGenerator.scala#L107-L138)：
  未创建的东邻不递归创建，而以 `mouthOutlets.isEmpty` 预测分类；已缓存时读既有分类。
  这**不是只查热缓存**，也不是通用地形排水发现证书。支流分类另会创建其主干下游盆，不能笼统说全部依赖只有一个盆。
- [Reach.scala:14-21](参考/river/Farseek-Mods/src/mainStreams/scala/streams/world/gen/segments/Reach.scala#L14-L21)
  给主干下游方向、offset和截面常量；
  [UpstreamSegment.scala:19-53](参考/river/Farseek-Mods/src/mainStreams/scala/streams/world/gen/segments/UpstreamSegment.scala#L19-L53)
  的outlet只读本盆边界、截面、局部地形和水位，不读inlets。inlets再取最多三个配向邻段的outlet。
  因而查询主干入口截面不需要沿全部主干递归上溯。hashCode种子是否全部为稳定内容哈希本次未证明。
- [Segment.scala:42-75](参考/river/Farseek-Mods/src/mainStreams/scala/streams/world/gen/segments/Segment.scala#L42-L75)：
  下游入口派生自上游截面（有一格平移/瀑布调整），本盆几何最后仍依赖完整inlets＋outlets。
  不能把“出口独立”误写成“最终几何完全不依赖入流”。
- **不能照搬**：西流拓扑、海平面常数、固定截面表和它的源布局。改宽度到拓扑阶数或对列做min/max合并，
  既不解决我方源存在/covered上下文，也不是本次授权修复；研究中提出的这种推论不采纳。

### 7.2 PL-RGA的闭合来自“不跨区”，不是更强的发现

- CC-BY-4.0已重读 [LICENSE](参考/river/plate-local-river-generation-main/LICENSE#L1-L9)。
- [terrain.cpp:304-320](参考/river/plate-local-river-generation-main/flat_infinite/src/terrain.cpp#L304-L320)
  以border_safe/lake_safe限制规划域；
  [rivers.cpp:384-463](参考/river/plate-local-river-generation-main/flat_infinite/src/rivers.cpp#L384-L463)
  从全域源序列重放守卫，不能过边界时整条回滚。
- [rivers.cpp:84-151](参考/river/plate-local-river-generation-main/flat_infinite/src/rivers.cpp#L84-L151)
  的源全枚举和整数node主键可借鉴其证据形式；不能借删跨区河、挡河或把自环改湖来消除当前红灯。
- 我方legacy归档源码也只读邻区pass1：
  [RiverLineNetwork.java:371-407](forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/hydrology/riverline/RiverLineNetwork.java#L371-L407)。
  这不是否定legacy已有跨缝优点，但不能由旧图连续或该接口外形推出“任意长度无本地源接力已证明”。

### 7.3 本轮借鉴的落点与未决

将“几何上可能的普通端口”与“源/历史上下文证明后的有效入流”分开。新隔离候选枚举不读邻区final：
由REGION/HALO/STEP推发送owner范围，在发送窗边框枚举九种来向，复用真实PortEgress。
它已包含旧pass1-only遗漏的第二跳，但候选不等于实际河。

进一步隔离测试：为所有潜在边框锚点预先声明世界格心，可在实窗反例中消除后来入流对较早shape的钉点影响。
**这是改变亚格几何的候选策略，不是零变化修复，未被采纳进生产。** 未证明源可达、JOIN/溢流守卫闭包、
完整发现/总预算完成，未做真实形态大图/湿列。新事实及复跑路径见 [HYDROLOGY.md](HYDROLOGY.md) §〇ai。
