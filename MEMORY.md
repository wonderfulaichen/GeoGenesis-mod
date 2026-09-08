# GeoGenesis-mod 项目记录

> 本文档为项目记忆快照，跟随 `git HEAD` 同步更新。
> 权威架构说明见 `AGENTS.md`；会话交接速览见 `HANDOFF.md`（注：HANDOFF 停留在 2026-08-27 RTF 范式，已落后于当前 D8 河网范式，以本文与 AGENTS.md 为准）。

## 项目概述
Minecraft 1.20.1 Forge 模组，自定义 `ChunkGenerator` + `BiomeSource`，程序化生成地形，并以**物理正确河网**（D8 汇流场派生）驱动气候与水体。

## 当前范式（2026-08-28 定案）
河网已从"几何 RTF 范式"整体切换为 **D8 汇流场派生 + 邻近段 IDW 雕刻**，生产路径：
`GeoGenesisTerrain` → `HydrologyChunkEngine` → `HydrologyExperimentEngine` + `HydrologyBlockCarver`，河网由 `riverline/` + `flowaccum/` 的 D8 汇流场派生。

- `FlowField`：D8 流向 + 汇流累积（region 网格纯函数带 margin，O(n) 拓扑序）；选线场用 `mountainScale` 压低山 → 贴谷避峰。
- `RiverTrace`：累积超阈值 → 折线提取 + 平滑（`Catmull-Rom`）+ 汇入下游出口锚点（border-safe / lake-safe 掩码 + 回滚 + 防交叉）。
- `RiverLineNetwork`：region 内 D8 汇流场派生河线 + Catmull-Rom 细分 + 锚点衔接邻 region（3×3 采样 → 无 border 断裂）；水面单调反推；width/depth 由汇流面积驱动；确定性（worldSeed + region 纯函数）。
- `HydrologyBlockCarver`：单块雕刻，邻近段 IDW 混合（fade²/dist²）surfaceY/width/depth → 河线交越平滑、无硬切；水面 = min(单调水面, 真实地形)；只下挖；灌水门控。
- `HydrologyExperimentEngine`：接线（双采样器：terrainEQuick 选线 + sampleWu 锚定水面）+ 灌水落块。

## 当前进展（已完成）

### 1. 河网与水面构建（D8 范式，已完成）
- 汇流面积（D8）计算、河流种子检测（accumThreshold）、河流折线提取 + Catmull-Rom 平滑。
- 水面采样：单调反推（PAVA 风格），下游严格不升；真实水面与雕刻高程解耦。
- 跨 region 连续河：双-pass 出口续流（ee474ed），消除跨 region 接缝缺口。
- 水体/河床方块雕刻：邻近段 IDW 混合，只下挖不抬升。

### 2. 小溪生成 + 宽深沿程连续变化（已完成，72d74eb）
- 宽度/深度改 Leopold-Maddock 下游水力几何幂律（`widthFromAccum` / `depthFromAccum`，bW=0.42 / bD=0.40，幂律无中段饱和）。
- 独立 `widthAreaRef`（= gridCell²）与成河门槛解耦，消除"降门槛→全河变宽"耦合。
- 参数重标定：minWidth 1.75 / maxWidth 10 / minDepth 1.6 / maxDepth 8 / riverAccumThreshold 200 / sourceSpacingCells 1 / sourceMinE 0.12。
- 效果：半宽 1.75~10 连续铺满，head→tail 1.96→6.07，河数 26→85。

### 3. 分支层级（Strahler 式，已完成，006d0a6）
- `RiverPolyline.level`（1=干流，n+1=汇入 n 级河）+ 构建期 `levelAt[]` 传递。
- 修复：发源循环中 `accepted.add(s)` 在 `traceRiver` 之前执行 → 回滚源占槽杀死潜在支流；改为仅成功成河的源才占槽。
- 效果：河数 85→109，层级 level1=55 / level2=44 / level3=10（二级支流成型）。

### 4. 入海口 / 河口 / 源头收窄（已完成，HEAD 前最后一批提交）
- 入海口海平面统一（4e215dc / 133ca8f / 388212e），重采样海域节点锁定海平面，消除河海交界缺角与海水被填没。
- 河口喇叭口展宽（e27767a / 26fe81d / c373db2）：独立于河道宽度上限，河口河床向海延伸，最小水深保障。
- 源头最小宽度收窄（af9364d，当前 HEAD）：1.75 → 1.0 block，窄化山泉/溪流。

### 5. 诊断探针系统（已完成迁移，4f396d3 / fe7b2f0）
- 49 个 Probe 类迁移至 `src/diagnostics/java`。
- Gradle 任务 `run*Probe` 正常工作（含 `runFlowAccumProbe` / `runRiverLineWidthProbe` / `runRiverLineSeamProbe` / `runHydrologyTerrainEntryProbe` 等）。

## 当前代码状态
- 构建通过（`gradlew compileJava` BUILD SUCCESSFUL）。
- `runFlowAccumProbe`：reachedOcean 48/48 (100%)、profile/gate/border violations 0。
- 河数 ~109/region，层级 1/2/3 成型；宽深连续无饱和。
- 旧格点水文集群（38 文件）已整体移除，无悬空引用。

## 当前验证状态
- **实机目检 PASS（用户 runClient 实测）**：小溪可见且有水、宽深沿下游渐变、分支的分支（level2/level3）成型、河口喇叭展宽、入海口海平面统一无缺角、无悬水/无漏雕柱，均确认正常。
- 已知遗留经实测确认：跨 region 水面无继承（border 边缘 1.6e-7 发生率）实机不可见；浅支流源头 1~2 块干槽属物理自然、可接受。

## 可选打磨（非阻塞）
- 源头渐入（headwater taper）、宽度沿程单调化、蜿蜒振幅/波长挂钩河宽。

## 关键文件（当前有效）
- `worldgen/hydrology/riverline/RiverLineNetwork.java` — D8 河网门面（选线 + 锚点衔接邻 region + 水面反推）。
- `worldgen/hydrology/riverline/RiverLineParams.java` — 河网参数（gridCell / accumThreshold / mountainScale / valleyExp / meander 等）。
- `worldgen/hydrology/flowaccum/FlowField.java` — D8 流向 + 汇流累积。
- `worldgen/hydrology/flowaccum/RiverTrace.java` — 折线提取 + 平滑 + 汇入下游。
- `worldgen/hydrology/HydrologyBlockCarver.java` — 单块 IDW 雕刻 + 灌水门控。
- `worldgen/hydrology/HydrologyExperimentEngine.java` — 接线（双采样器）+ 灌水落块。
- `worldgen/hydrology/HydrologyChunkEngine.java` — 落块引擎。
- `worldgen/terrain/GeoGenesisTerrain.java` — 地形引擎门面（缓存 Cell + generateChunk 装配河网）。
- `src/diagnostics/java/.../probes/` — 49 个诊断探针。

## 参考项目
- `参考/river/Streams` — Farseek 河流模组（两代实现）。
- `参考/river/Farseek-Mods` — 同上。
- `参考/river/plate-local-river-generation-main` — 地形驱动河流生成。

## Git 状态
- 最新提交：`af9364d` tweak(hydrology): 源头最小宽度 1.75→1.0 block，窄化山泉/溪流（已推送）。
- 工作区：干净（仅本 MEMORY.md 未跟踪）。
