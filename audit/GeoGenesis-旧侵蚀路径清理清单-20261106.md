# 旧侵蚀路径清理清单（已实施，2026-11-06）

> 2026-11-06。用户裁定：「骨架侵蚀移除，待后续；现在只保留物理粒子侵蚀；
> 不要搞两套同时存在；保持唯一性」。
>
> 本文只列「删 / 迁 / 归档」三类及依赖面，**不含代码改动**。
> 所有行号以当前 HEAD `30f6b3f` 为准。

## 0. 当前生产路径（保留，不动）

```text
GeoGenesisGenerator.fillFromNoise
  → GeoGenesisTerrain.getChunkCells
    → CellGenerator.sample                      基础 B（BaseWorldSampler）
    → CellGenerator.extractFromTile
      → applyTileDelta → processErosionDeltaE → ProcessRuntime.exactP   P（唯一）
    → GeoGenesisTerrain.applyHydrologyValley
      → HydrologyChunkEngine → ModelHydrologyProvider
      → ProcessModelTerrainInput（同一份 B/P）
```

`ErosionEngine`（物理粒子内核）由 `ParticleTile` 调用，**保留原样**。

`coreApplyDelta` 虽在旧文件里，但当前生产路径仍以 `res == null` 调它，
负责 `cell.e += delta → height → 陆地重分类`。**不能删**，需迁到新边界。

## 1. 可直接删（生产不可达；仅诊断反射/直调）

| 成员 | 当前位置 | 依赖方 | 处理 |
|---|---|---|---|
| `generateErosionTile` | CellGenerator:1252 | `ErosionConcurrencyProbe:216` 反射；`getErosionTileResultForProbe` | 删；诊断探针随之归档 |
| `getErosionTile` / `getOrGenTile*` / `getErosionTileResultForProbe` / `getTileResult` | CellGenerator:1146/1181/2058/2637 附近 | `ErosionTileProbe`、`QuickGradientProbe`、`ErosionPeriodProbe`、`LocalFlowOwnershipProbe` 反射 | 删 |
| `ErosionTileResult`（内部类） | CellGenerator:1096 | 23 处诊断代码行（`SharedLakeSeamProbe` 反射锁死字段） | 删 |
| `erosionTileCache` / `erosionTilesInFlight` / `tileCacheStats` / `tileAccessClock` / `pruneErosionCache` / `tileKey` / `tileCacheGet` / `neighborTile` / `blendNeighbor` / `scheduleTilePreheat` / `prewarmErosionTiles` | CellGenerator:1066–2238 | `CellModelTerrainInput:36/38/39`、`ErosionConcurrencyProbe`、`InterruptStormProbe`、`SharedLakeSeamProbe` 反射 | 删；`CellModelTerrainInput` 需先改为不再依赖 |
| `blendTileDelta` / `sampleTileField`（旧 tile 版） | CellGenerator:1756/2257 | `SharedLakeSeamProbe` 反射、`applyCachedTileDelta` | 删。新路径的 blend 在 `ProcessRuntime.assemble` |
| `Bicubic`/`sampleBilinear`/`bspline`/`parallelRows`（仅旧 tile 用） | CellGenerator 前半 | 仅旧 `generateErosionTile` | 删 |
| `ERODE_*` / `RIDGE_SKELETON_SPACING` / `BLEND_START` / `ERODE_TILE_CACHE_SIZE` 等常量 | CellGenerator:1062–1082 | `SharedLakeSeamProbe`、`LocalFlowOwnershipProbe` 反射 | 删；新几何常量在 `TileGeometry` |
| `rockResistanceAt` / `ROCK_QUANT` / `hrEntries` / `HR_*` / `clearRockResistanceCache` | CellGenerator:851–911 | 旧 `ErosionEngine` 硬度注入；新路径用 `ProcessContext.resistance` | 删 |
| `erosion` 字段（`ErosionEngine` 实例）/ `worldSeed`/`erosionRoundCounter` | CellGenerator:113–117 | 仅旧路径与 `erosionDeltaABForProbe` | 删 |
| `erosionDeltaABForProbe` / `runErosionAtForProbe` / `hardnessProviderRef` / `sampleDischarge*` / `erosionEnabledDiag` / `dischargeDiagCount` | CellGenerator:1520–2700 | `RockErosionProbe`、`HardnessRaceProbe` | 删 |
| `PROBE_SKELETON_ONLY` / `probeRidgeConfig` / `setRidgeConfig` | CellGenerator:1555–1606 | `ChunkBorderProbe`、`ErosionPeriodProbe` | 删（骨架已暂停） |
| `prewarmErosionTiles` 的旧实现 | CellGenerator:2016 | `CellModelTerrainInput` | 删；预热已由 `ProcessRuntime.prewarmTiles` 承担 |

### 连带需删的 model 适配

| 文件 | 理由 |
|---|---|
| `CellModelTerrainInput.java` | 旧 `CellGenerator` 适配。`HydrologyChunkEngine` 现在只绑定 `ProcessModelTerrainInput`；保留它等于第二套地形输入 |
| `RidgeConfigAdapter.java` | 只服务骨架滤镜配置；骨架暂停 |
| `RidgeValleyErosion.java` | 骨架算子本体（365 行）。留作归档副本，不再进 `src/main` |

## 2. 需迁移（生产仍在用，不能删）

| 成员 | 当前调用 | 迁到 |
|---|---|---|
| `coreApplyDelta` 的纯 delta 部分 | `applyTileDelta:1843`（`res=null`） | 抽成无 `ErosionTileResult` 参数的 `applyProcessDelta(cell, delta)`；删除 `res != null` 分支（discharge/postErosion 梯度） |
| `erodedHeight` | `CellModelTerrainInput` 旧适配（随该文件删）+ 诊断 | 保留 public 入口，但只委托 `ProcessRuntime.exactP().yP()`；不再有 `morphBase` 分支 |
| `sampleHeightNonBlocking` | `GeoGenesisGenerator:1188/1197`（结构定位用） | 保留入口，明确返回 B 高度；去掉 `peekErosionDeltaE` 调用 |
| `sampleWu` | 主树零调用，但约 120 行诊断代码依赖 | 保留为薄入口：`return sample(wuX, wuZ)` + `applyTileDelta`，不碰旧 tile |
| `erosionDeltaE` | 主树生产不调（仅 `applyTileDelta` 已直接走 `processErosionDeltaE`），23 行诊断依赖 | 保留薄委托到 `processErosionDeltaE`，避免大面积诊断编译失败 |
| `peekErosionDeltaE` | 3 行诊断 | 保留薄委托；语义改为「请求即返回精确 delta」（不再有 empty 分支） |
| `applyCachedTileDelta` | `GeoGenesisTerrain:217` 的 `sampleCellLight` | 改为直接调用 `applyProcessDelta`；不再查 `erosionTileCache` |
| `erosionTileCacheSize` / `tileCacheStats` | 30/34 行诊断 | 改为返回 `ProcessRuntime.metrics()` 的对应计数，或直接归档相关探针 |

## 3. 归档（不动代码，只移出 `src/main`）

| 内容 | 去向 | 说明 |
|---|---|---|
| `RidgeValleyErosion.java` | `_archive/ridge-skeleton/` | 骨架功能「待后续」，代码保留可查，不再编译进生产 |
| `RidgeConfigAdapter.java` | 同上 | 同上 |
| 骨架 A/B 实验探针：`RidgeValleyABProbe`、`RidgePlacementProbe`、`SingleShaperProbe`、`ChunkBorderProbe` | `_archive/ridge-skeleton/diagnostics/` | 属暂停实验，不是生产验收 |
| 旧 tile 管线专用探针：`ErosionTileProbe`、`ErosionPeriodProbe`、`ErosionConcurrencyProbe`、`InterruptStormProbe`、`SharedLakeSeamProbe`、`BaseHeightProbe`、`QuickGradientProbe`、`HardnessRaceProbe`、`RockErosionProbe`、`LocalFlowOwnershipProbe` | `_archive/old-tile-pipeline/` | 它们反射锁死旧内部实现，无法迁到新路径；保留作历史证据 |
| `build.gradle` 中上述探针的 `run*Probe` 任务 | 随探针一起归档 | `runWorldgenGate` 里的 `runHydrologyDeterminismProbe` 需单独处理（见下） |

## 4. 必须单独决策的一项（门禁）

`runWorldgenGate` 依赖 `runHydrologyDeterminismProbe`，该探针在
`HydrologyDeterminismProbe.java:81/83/84` 直调 `gen.erosionDeltaE(x,z)`，
用于「侵蚀增量与邻居 tile 缓存状态无关」的判据。

- `erosionDeltaE` 若按第 2 节保留薄委托 ⇒ 探针仍能编译，但它的断言
  （tile 缓存命中/缺失）已不对应 `ProcessRuntime` 的缓存语义 ⇒ **门禁名存实亡**。
- 三个选择：
  1. 把该探针迁到 `ProcessRuntime` 的 `metrics()/eviction` 语义，重写断言（推荐）；
  2. 从门禁移除，另立新门禁；
  3. 保留旧探针但标注「历史回归，不参与门禁」。

不得直接删掉了事，也不能留着假绿。

## 5. 清理顺序（确认后执行）

1. 先改 `CellModelTerrainInput` 的消费者：`HydrologyChunkEngine` 只保留新 B/P 分支
2. 抽 `applyProcessDelta`，让 `applyTileDelta` / `applyCachedTileDelta` 不再依赖 `ErosionTileResult`
3. 归档 `RidgeValleyErosion` + `RidgeConfigAdapter` + 骨架探针
4. 删 `CellGenerator` 旧 tile 管线（第 1 节全部）
5. 归档第 3 节列出的旧诊断探针，并清理 `build.gradle` 对应任务
6. 处理 `runHydrologyDeterminismProbe`（第 4 节）
7. 验证：`gradlew build` + 现有门禁 + 实机进世界（地形/河湖/性能）

## 6. 风险与边界

- `CellGenerator` 当前约 2650 行，预计删减 1000+ 行；`src/main` 220 个文件中
  只有 5 个文件真正引用旧 tile API（其余均为注释或诊断）。
- 约 20 个诊断探针依赖旧 API，其中 10 个是**反射**依赖：改签名不会报错，
  但会在运行时失败 ⇒ 归档比「改名字」更安全。
- 清理**不改地形产出**：B/P/model 算法与参数全部不动；只删不可达分支。
  但 `CACHE_SCHEMA_VERSION` 是否升版需实机确认（预览缓存格式已因水文叠加升至 122）。
- 骨架功能按用户裁定「移除，待后续」：归档而非删除源码，后续可独立立项恢复。
- 本清单编写时核实 HEAD `30f6b3f`，编写阶段未提交任何改动。

## 7. 实施结果（2026-11-06）

用户批准后按第 5 节顺序执行，产物分四个提交入库（均在本文件所在的提交批次内）。

**代码规模**：`CellGenerator.java` 净减 1284 行（2650 → 1366 行；+36 / −1320）；
`src/main` 删除 3 个类、修改 9 个文件；`src/diagnostics` 删除 21 个探针、修改 7 个探针、
新增 `ProductionWaterKind`（从 `ProductionViewProbe` 抽出，供
`LakeColumnSeamProbe` / `RiverDepthViewProbe` 共用）；`build.gradle` 移除 15 个
`run*Probe` 任务（−166 行）。

**归档**：28 个文件落在 `_archive/ridge-skeleton/`（`RidgeValleyErosion`、
`RidgeConfigAdapter` 与骨架探针）与 `_archive/old-tile-pipeline/`（旧 tile 探针）。
归档前另存工作副本到 `backups/pre-cleanup-20261106-013945/`（含 inventory.json，
`backups/` 被 `.gitignore` 忽略，不入库）。

**生产路径**（清理后唯一）：

```text
extractFromTile → applyTileDelta → applyProcessDelta + processErosionDeltaE
                                     ↑ 逐位保留 coreApplyDelta(cell, delta, null, …)
                                       （cell.e += delta / NaN·Inf 守卫 / softCapLandE /
                                         height 重算 / classifyTerrain）
```

删掉的只有生产本就传 `null` 的 `res != null` 分支。

**验证**：

| 项 | 结果 |
|---|---|
| `gradlew build` | exit 0 |
| `runHydrologyDeterminismProbe` | exit 0，ALL PASS（144/144；另测预算 24、48 亦 PASS） |
| `runWorldgenGate` 全量 | exit 1，唯一红项 `runCenterlineCrossSectionProbe` |
| `runWorldgenGate` 排除该既有红项 | exit 0 |
| 产出不变 | 144 条数据行 `baseline-before/after/final.txt` 三份逐位一致 |
| `git diff --check` | exit 0 |

> 既有红项 `runCenterlineCrossSectionProbe` 在 `handoff/GeoGenesis-20261029.md:242-247`
> 有记录：旧默认配置下逐项相同，属 `ff3239a` 起的既存红，非本次清理引入。

**未完成**：实机 `runClient` 复验由用户执行（用户复验正常后才批准本次整理提交）；
`CACHE_SCHEMA_VERSION` 本次未升版（清理未改任何地形产出）。
