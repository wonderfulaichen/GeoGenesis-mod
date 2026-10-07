# 全部水文代码盘点（2026-10-07，26 文件 ≈12400 行，3 条链）

> 触发：用户要求「先看清楚现在的全部水文代码」再谈骨架化方案。
> 本文只做事实盘点（文件/表示/算法/成本/状态），方案另立。

## 链 1：生产链（游戏内实际跑的）—— ✅ 骨架式，确定性，便宜

**调用链**（全部有码可查）：
```
GeoGenesisTerrain.generateChunk (GeoGenesisTerrain.java:654)
  → HydrologyChunkEngine.calculate (HydrologyChunkEngine.java:66, legacy 分支)
    → HydrologyBlockCarver.carveChunk (:184)
      → HydrologyExperimentEngine.sampleBlockAll (:194)
        → RiverLineNetwork.sampleAll (:4417)   ← 逐块查折线段距离场
```

**河网怎么来**（RiverLineNetwork 内部，两条确定性骨架路线）：
- `TerrainFlowSim.simulate`（flow/TerrainFlowSim.java:222 调用处）：
  **priority-flood 填洼 + D8 最陡下降 + 拓扑汇流累积**（Barnes 2014，零随机数）
  → `extractChannelSkeletons` 抽骨架 → `traceSkeleton` 追折线 → `RiverPolyline` 节点折线
- `FlowField`（flowaccum/FlowField.java，903 行）：region 级 D8 汇流累积，
  供选线/流量（`hydrologySkeletonRouting` 默认 true 控制此路线，toml 可关）

**逐块查询成本**：`sampleRegion`（RiverLineNetwork.java:4592）对 3×3 邻 region 的
**全部折线段线性扫描**（无空间索引，无 bbox 早退）。段数受 region 尺寸约束，
实测生产成本 **6.8~8.8 ms/chunk**（HANDOFF 基线：块均 30ms 中水文 ~13ms）。

**参考对标**：与 FreeTerraForged 同范式（折线 + 索引查距离，零蒙特卡洛），
唯一差别是 FTF 用大陆格哈希+bbox 早退，我们是线性扫段（当前段数下够快）。

## 链 2：model 链（实验，默认关）—— ⚠️ 证据场是蒙特卡洛，几何输出是骨架

**调用链**（hydrologyModelPipelineEnabled=true 时替代 legacy 分支）：
```
HydrologyChunkEngine.calculate (:64, model 分支)
  → ModelHydrologyProvider.columnsFor (:138, 区域缓存+邻区预热)
    → HydrologyPipeline.run (:289 耦合循环, RiverModel.CONTINUOUS)
      → ContinuousRiverNetwork.build (:212) ← ★ 蒙特卡洛：50k 随机起点流线
      → EquilibriumWater (minimax 平衡水面, 确定性)
      → LakeWaterBalance (水量平衡, 确定性)
      → RiverCenterlines (细化→切段→定向→横断面距离场, 确定性骨架)
```

**★ 关键事实：model 链是"蒙特卡洛证据 + 骨架几何"的混合体**：
- `ContinuousRiverNetwork.build` 第 212 行：`for (s < samples)` 撒随机流线沿速度场积分
  —— 这是**唯一的蒙特卡洛环节**，也是 14~18 秒/region 的主要成本；
  且湖↔河耦合每轮（最多 6 轮）**全部重跑** ⇒ 实际 6 × 50k 流线。
- 其余全部确定性：minimax 平衡水面、水量平衡、`RiverCenterlines` 骨架+距离场、
  `ModelHydrologyProvider.SegmentIndex`（tile→段索引，与 FTF 同型）。
- 模型自带确定性模式 `RiverModel.D8`（model/RiverNetwork.java，O(n) 拓扑累积，
  130 行）——**已存在但未验收**（见下节"今日实测"）。

**成本**：
| 口径 | 数值 |
|---|---|
| 离线探针（r=512, step=4, 50k） | 2216 ms/region（PipelinePerfProbe: 1.58 ms/chunk 核心区）|
| **实机**（同配置） | **14~18 秒/region**（争 CPU + 冷 tile；WGP: 水文 31% 占比）|
| CONTINUOUS 正典 r=700/400k | ~150 s |

## 链 3：配套/诊断（不直接决定形态）

| 文件 | 行数 | 作用 |
|---|---|---|
| riverline/RiverLineParams + RiverWarp + MidpointDisplacement | 678 | 河线参数/扭曲/旧分形线（generate() 已无调用）|
| HydrologyChunkSampling/Result/Sample/CarvedColumn | 165 | 列采样与雕刻计划数据结构 |
| model/WindowOutlets + DirectionRule + FlatSurfaceJitter | 187 | 窗口边界出口 / 破平局开关（默认 baseline）|
| HydrologyExperimentEngine | 226 | 生产门面（委托 RiverLineNetwork）|

## 今日决定性实验：model 链 D8 模式（同参正典 r=700/400k）

> **★ 后续（2026-10-08）：本节的"待修"已全部修复并采纳为 model 链默认**（见文末）。

| 指标 | CONTINUOUS（蒙特卡洛）| D8（确定性骨架）|
|---|---|---|
| 耗时 | ~150 s | **53 s**（快 ~3×，且无随机采样）|
| 河道格 | 70998 (3.62%) | 25700 (1.31%，阈值口径待标定) |
| **水量账本** | 收敛 residual=0（first-capture 已修）| **灾难性发散：ΣIn 6 轮 → 1.3e14**（见下）|

**★ 新发现（推翻 2026-10-07 的一个决定）**：此前以"对照基线"为由**刻意不动 D8 语义**
（solve 仍收 Σdischarge over 组）。实测证明 **D8 有同一类放大环且更严重**：
- round#1 无注入时 ΣIn=120984 = **坡面径流的 73×**（纯粹"Σ内部格"重复计数：
  一条河穿过 m 个组格就被计 m 份）；
- 溢流注入再叠一份（D8 的穿过流本来就已把上游水带到下游，注入 = 双份）；
- 6 轮后 ΣIn=1.3e14、residual 恒 0.996。

## 参考项目的范式归属（子代理取证，路径:行号可查）

| 项目 | 河网表示 | 蒙特卡洛 | 性能证据 |
|---|---|---|---|
| **FreeTerraForged** | 折线段 + Network 树 + 大陆格哈希索引（River.java:18-57, RiverCache.java:14）| **无** | 无源码数字 |
| **PL-RGA** | 确定性 D8 贪心下坡 + Voronoi 区域索引（platewisenetwork.py:217-267, platewiseregions.py:4-10）| **无**（生产路径全仓零 random）| **9.6~53 ms/板块**（benchmark JSON 原始数据）|
| **FastFlow（论文）** | 确定性 D8 + 拓扑累积 | 无 | **<55 ms @ 4096²**（_fastflow_text.txt:1188）|
| **geotransport** | **就是蒙特卡洛**（path.hpp:9 "Monte-Carlo Flux Integrator"，示例 524288 条流线）| **是 —— 我们 model 链 CONTINUOUS 模式的出处** | 它自己的 demo 对照组就是 FastFlow 确定性（accumulate_dem.py:11-20）|
| **SimpleHydrology** | 粒子液滴（water.h:12 Drop）| 半是（粒子随机）| README 自述删 priority-flood 因 "bugginess and slowness" |

**结论**：四个骨架派项目全部**零蒙特卡洛**，且都有成本证据；
蒙特卡洛唯一出处是 geotransport —— 而它自己都拿确定性 FastFlow 做性能对照。
⇒ 「骨架不吃性能」有跨项目证据支持。

## 关键可借鉴点（按可落地性排序）

1. **边界通量记账**（修复 D8 账本，今日实测的直接解法）：
   inflow(G) = Σ_{k∈G} 本地径流 + Σ_{c∉G, parent(c)∈G} discharge[c]
   —— 只记【跨边界净流入】，不记内部格（与 CONTINUOUS 已落地的 first-capture
   是同一原则的确定性版；geotransport 的 boundary integral 同型）。
2. **model 链切换到 D8 证据**：RiverModel.D8 已内置，几何/湖/中心线全共享；
   只欠账本修复 + 阈值标定 ⇒ 预期 3× 提速 + 完全确定性。
3. FTF 的 bbox 早退（Network.java:14-36）：我们生产链 sampleRegion 是线性扫段，
   段数增长时可借（当前 6.8ms 基线下非瓶颈，勿提前优化）。
4. PL-RGA 的 Voronoi 区域索引（284 字节/段、构建期一次）：若未来要 O(1) 查询可借。

## NOT VERIFIED（如实）

- ~~D8 模式的河道阈值口径未标定~~（channel 1.31% vs CONTINUOUS 3.62%，二者模型不同不可直比；
  切换后 channel=25377 微降 0.02pp，形态以渲染图终审为准，仍待用户目检）；
- ~~生产形态（177² 网格）下 D8 的耗时未测~~ ✅ 已测（见下节）；
- 实机基线 legacy 对照（11:42 那次）世界未生成成功，无 WGP 数据。

---

# 骨架化落地记录（2026-10-08，用户选定路线 A：model 链切 D8 骨架）

## 改动（3 处 + 1 个根因修复）

1. **`RiverNetwork.build` 新增 basinId 重载 = 湖即汇**：Kahn 累积中组内→组外的边
   不传递水量（旧口径穿过流与溢流注入**双份**）；只以 `overflowAt` 注入
   `outflow = inflow − evap` 在指定出口下泄。`basinId=null` ⇒ 旧行为逐位一致
   （直接调用本类的对照探针零影响）。
2. **`HydrologyPipeline` D8 分支记账 = 边界净入流**：`capture[k] = 本地径流 +
   Σ组外→组内跨边界水量`，取代旧的 `Σ discharge over 组`（73× 重复计数的根）。
   `hydro.ledgerOff=true` 保留为反向验证入口。
3. **`ModelHydrologyProvider` 默认 `RiverModel.D8`**，逃生口
   `-Dgeogenesis.modelRiverModel=continuous`；region 日志加 `model=` 字段。
4. **★ 自环回灌根因修复（本日最有价值的取证）**：修完 1~3 后 ΣIn 仍**线性增长**
   +192.6/轮（90 轮不止，与几何发散性质不同）。逐盆地诊断 `[top basin]` +
   新增 `[outlet?]` 打印 ⇒ **增长的 2 盆地 `selfLoop=true`（出口格的 parent 指回
   自家组内）、收敛的 false，相关性完美** ⇒ 注入的 outflow 下一轮经
   `o→parent[o]` 成为本家 boundary inflow，同一份水记两次 ⇒
   `I_{n+1} = I_n + c` 恒定步长。修法：跨边界水量中**扣除本家出口格的注入分量**
   （他家注入流经此处属合法级联，照常计）。

## 验收（全绿）

| 项 | 结果 |
|---|---|
| 正典 d8 耦合 | **rounds=6 converged=true residual=0.000e+00**（ΣIn 稳定 1062.4）|
| 反向验证 `ledgerOff` | ΣIn=5.7e13 发散 ⇒ 判据能区分对错 ✅ |
| `runWorldgenGate`（12 探针）| ALL PASS |
| `runModelPipelineAdapterProbe`（7 判据）| ALL PASS（跨实例缓存 5.24ms）|
| `runRegionContractProbe` | ALL PASS（湖失配 0.0000%）|
| **provider region 求解耗时** | **413 ms（model=D8, 177²）** vs 改前 2216ms 离线 / 14~18s 实机 ⇒ **离线 5.4×、实机应 30×+** |
| 正典 r=700 出图 | sea/lake/channel 与改前几乎不变（channel 25381→25377）|

## v1 边界（如实）

- 出口回退路径（窗口截断 ⇒ outlet 落在组内）的注入会被湖即汇拦截挡住（稀有）；
- D8 尚未在出口扣蒸发的替身问题已随 `outflow=inflow−evap` 注入解决（v1 无此缺口）；
- 河道阈值未按 D8 口径重新标定（默认 `Params.defaults()`）；
- **实机 WGP 对照未重跑**（需新建世界）——离线 413ms 已足够支撑"接线可行"结论，
  但 31% 水文占比的实机改善量待实测。
