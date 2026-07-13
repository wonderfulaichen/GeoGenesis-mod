# GeoGenesis Architecture

> **本文档与当前代码同步（2026-07-13 更新）**。描述「鼓包范式 + 旧 droplet 侵蚀」的旧版（LandForms / TerrainType / Continent / TerrainBlender / HydraulicErosion / TileLakeSolver）已彻底废弃，请以本版为准。
>
> **✅ 当前进度（2026-07-13）**：地形引擎已整体重写为**地质过程范式**（单一连续场 `e(x,z)`，大陆性 `c` 是单一连续噪声，海陆仅是对同一场的条件切分）。阶段 1（统一场地形管线）、阶段 2（RiverField 粗格点河网 + 河谷刻蚀）、阶段 3（多营力局部侵蚀 ErosionSystem）**均已编码并接线**，BUILD SUCCESSFUL。气候→群系已接游戏（BiomeClassifier 按 `TerrainClass × Climate` 选原版群系）。`runClient`/`runPreview` 目检待做。
>
> **⚠️ 已知不一致**：`GeoGenesisGenerator` 的世界高度（`WORLD_MIN_Y/MIN_Y`、`WORLD_MAX_Y/MAX_Y`、`SEA_LEVEL`）目前**硬编码**，`GeoGenesisConfig` 的 `World Height` 段（seaLevel/minY/maxY）**暂未驱动 generator**（仅注入 HeightCurve 做 e→Y 映射）。River / Erosion 参数当前仅代码 `defaults()`，**未暴露到 Forge Config**。

## Overview

Minecraft Forge 1.20.1 地形生成模组。自定义 `ChunkGenerator` + `BiomeSource`，程序化生成大陆 / 地形 / 河流 / 湖泊 / 气候，并按气候驱动生物群系。

**核心设计**：地形逻辑集中在 `worldgen/terrain/` 下的**零 MC 依赖引擎** `GeoGenesisTerrain`（可在纯 Java 跑通，便于预览 / 测试），`GeoGenesisGenerator` 仅负责把引擎结果写进 chunk block，`GeoGenesisBiomeSource` 复用同一份引擎结果选群系。

## Package Structure（当前）

```
com.geogenesis
├── GeoGenesisMod.java              # @Mod 入口：DeferredRegister 注册 generator/biomesource CODEC [ACTIVE]
├── client/                         # 客户端：配置屏、预览、Forge 事件
│   ├── GeoGenesisClient.java       # MOD 总线：Config 工厂 + RegisterPresetEditorsEvent + 注册配色重载监听器
│   ├── GeoGenesisForgeEvents.java  # FORGE 总线：游戏内按 G 打开预览屏
│   ├── GeoGenesisConfigScreen.java # 游戏内预览/配置屏（三页标签：地形/气候/参数 + 右侧工具栏）
│   ├── ParamSlider.java            # 通用参数滑块（含重置按钮 + tooltip）
│   ├── TerrainConfigPanel.java     # 地形页：基础因素曲线图 + 控制点滑块（可折叠）
│   ├── BasicParamsPanel.java       # 参数页：噪声/尺度/海平面/雪线等基础参数滑块
│   └── preview/                    # 渲染/热力图/叠加/控制面板（11 图层 + 图例）
│       ├── TerrainPreview.java      # 独立 Swing 预览窗口
│       ├── PreviewDisplay.java      # 游戏内预览控件
│       ├── PreviewColor.java        # MC 侧着色外观，委托 GeoPalette
│       ├── ColorMap.java            # 零依赖连续色带（Lab 插值 + bake LUT）
│       ├── GeoPalette.java          # 零依赖配色中枢
│       ├── GeoGenesisColorReloadListener.java # MC 资源重载：JSON 覆盖 GeoPalette
│       └── mixer/                   # 调音台式多因素控制系统（Factor/MixerPanel/...）
├── config/
│   └── GeoGenesisConfig.java       # Forge COMMON 配置（地质过程参数，见下方配置表）[ACTIVE]
└── worldgen/
    ├── climate/                    # 零依赖分类（不持颜色，不 import MC）
    │   ├── BiomeClassifier.java     # pickKey(Cell)→ResourceKey<Biome>（TerrainClass×Climate switch）[ACTIVE]
    │   ├── Climate.java             # 温度/湿度数据载体（替代旧 BasicClimate/PreClimate）[ACTIVE]
    │   ├── ClimateZone.java         # Köppen 简版 classify(Cell)→Zone{A/B/C/D/E}
    │   └── Latitude.java            # latitude01(worldZ) 纬度带
    ├── generator/                  # MC 侧接入层
    │   ├── GeoGenesisGenerator.java  # ChunkGenerator：fillFromNoise 写 block + fillRiverColumn；createState 注入 [ACTIVE]
    │   └── GeoGenesisBiomeSource.java# BiomeSource：按 Cell 气候选原版群系（接 terrain）[ACTIVE]
    ├── terrain/                    # ★ 零 MC 依赖地形引擎（核心，地质过程范式）
    │   ├── GeoGenesisTerrain.java    # 引擎门面：缓存 Cell 网格 + generateChunk（侵蚀→河流装配）[ACTIVE]
    │   ├── Cell.java                 # 单格数据（c/e/eLand/terrainType/provinceWeights/climate/river*）[ACTIVE]
    │   ├── CellGenerator.java        # 统一连续场采样 + 实现 HeightProvider + 连续分类 [ACTIVE]
    │   ├── ContinentField.java       # 大陆场 c∈[0,1]：FBM Simplex + Warp [ACTIVE]
    │   ├── LandShape.java            # 省权重(softmax) + 陆地过程形态（克拉通/造山带/高原/盆地）[ACTIVE]
    │   ├── SeaBedDetail.java         # 零均值海床细节（替代旧 OceanField 平台）[ACTIVE]
    │   ├── HeightCurve.java          # 单条 cubic Hermite Spline：eFromC / heightFromE（非对称）/ eFromHeightF [ACTIVE]
    │   ├── TerrainClass.java         # 12 类地形枚举（OCEAN/DEEP_OCEAN/LAKE/RIVER/BEACH/PLAIN/HILLS/PLATEAU/MOUNTAINS/PEAK/BASIN/SNOW）[ACTIVE]
    │   ├── TerrainParams.java        # record：全部地质过程参数载体 [ACTIVE]
    │   ├── Size.java                 # 尺度载体（horizontalScale）
    │   └── SplineUtil.java           # 样条工具（cubic Hermite）
    ├── river/                      # 河流网（2026-07-13 粗格点下坡汇流，替代旧 droplet/HydraulicErosion）
    │   ├── HeightProvider.java       # 接口：landHeight(陆地 e / 海洋 NaN) + provinceWeights [ACTIVE]
    │   ├── RiverField.java           # 世界坐标确定性河网：粗格点下坡汇流 + 流量门控 + 多级刻蚀 + LongCache [ACTIVE]
    │   ├── RiverSample.java          # 运行期采样载体（riverMask/riverDistance/riverWetness/flowCount/...）[ACTIVE]
    │   └── RiverSettings.java        # record：gridSize/width/minE/waterfallDrop/sourceLakeChance/... [ACTIVE]
    ├── erosion/                    # 多营力局部侵蚀（2026-07-13 复活，全新局部算子框架）
    │   ├── ErosionSystem.java        # 编排器：按固定顺序组合各 agent 作用于统一 e 场 [ACTIVE]
    │   ├── ErosionAgent.java         # 局部算子接口（仅访问 pad 邻域，无 border 断裂）[ACTIVE]
    │   ├── Thermal.java              # 坡积软化（v1 主效）[ACTIVE]
    │   ├── Coastal.java              # 海岸冲刷（v1 主效）[ACTIVE]
    │   ├── Glacial.java              # 冰川（已实现，气候门控留桩）[ACTIVE]
    │   ├── Wind.java                 # 风蚀（已实现，气候门控留桩）[ACTIVE]
    │   └── ErosionSettings.java      # record：各 agent 强度/气候权重 [ACTIVE]
    └── noise/                      # 噪声原语（27 个文件：NoiseEngine 封装 + 各类噪声）
```

> **已清理的废弃代码**：`LandForms`/`TerrainType`（旧鼓包范式高度工厂）、`Continent`/`SimpleContinent`（旧大陆分类）、`TerrainBlender`（旧区域混合）、`BasicClimate`/`PreClimate`（合并入 `Climate`）、`BiomeMapper`/`GeoLevels`（合并入 `BiomeClassifier`/`GeoGenesisGenerator`）、`HydraulicErosion`/`TileLakeSolver`（旧 droplet 河流，被 `RiverField` 取代）、整套旧 `worldgen/erosion/` 多类型粒子系统（被局部算子框架取代）。详见 `docs/06-历史归档/`。

## Core Pipeline

### 地形引擎（`GeoGenesisTerrain`，零 MC 依赖）

```
new GeoGenesisTerrain(CellGenerator)        // GeoGenesisGenerator.ensureEngine
  ├─ ContinentField.sample(x,z)  →  c ∈ [0,1]   （单一连续噪声场，FBM Simplex + Warp）
  ├─ CellGenerator.sample(x,z):                          ← 统一连续场中枢
  │     cBiased    = clamp(c - continentBias, 0, 1)      （正=更多海，负=更多陆）
  │     eOcean     = min(HeightCurve.eFromC(cBiased) + seabed, 0)   （海洋基面，样条定深）
  │     eLand      = LandShape.sample(...)               （省权重 softmax + 过程形态 + 海岸衰减）
  │     landW      = smoother(clamp((cBiased - threshold)/(coastWidth*1.5), 0, 1))
  │     e          = lerp(eOcean, eLand, landW)          （单一 e 场，海岸 C0 连续，无硬切）
  │     terrainType= classify(e, eLand, provinceWeights) （实测 e<0 → 海洋）
  ├─ ErosionSystem.apply(eGrid)    ← 多营力局部侵蚀（Thermal/Coastal/Glacial/Wind），侵蚀先于河流
  └─ RiverField.apply(cell, x, z)  ← 粗格点河网 + 河谷多级刻蚀（U 形谷 + 源/瀑盆雕琢）
对外 API:
  double   sampleHeight(blockX, blockZ)     // 任意 block 高度（= cell.height）
  Cell     sampleCell(blockX, blockZ)       // 单格完整数据（带 chunk 级缓存）
  Cell[]   getChunkCells(cx, cz)            // 单 chunk 的 Cell 数组
  Cell[][] getRegionCells(bx, bz, w, h)     // 预览区域（逐格采样）
```

**海洋深度模型**：深度完全由 `HeightCurve.eFromC(c)` 样条控制点决定（x=大陆性 c，y=深度），之上叠加 `SeaBedDetail` 零均值微起伏（`eOcean = min(...,0)` 保证细节不过海平面）。**严禁** `e = c` 线性映射或 `if(e>0) e=c`（c≈0.5 时 e=0.5→Y=191 异常）。详见 `docs/01-架构设计/02-海洋河流架构-ocean-river-architecture.md`（其 §2.1 旧 `e=c` 写法已作废，以本段为准）。

### 世界生成（`GeoGenesisGenerator.fillFromNoise`）

```
fillFromNoise(executor, blender, randomState, structureManager, chunk)
  ├─ ensureEngine(worldSeed)   # 首次调用构建 GeoGenesisTerrain 并 setTerrain 注入共享 BiomeSource
  ├─ Cell[] cells = terrain.getChunkCells(chunkX, chunkZ)
  └─ for each (x,z) in 16×16:
        Cell cell = cells[cx][cz]
        if (cell.riverMask && cell.terrainType == RIVER)
            → fillRiverColumn: 按 riverFloorY..riverSurfaceY 灌水（水面≈谷壁高度，使高山河谷也可见水）
        else:
            top = cell.height
            ├─ top <= SEA_LEVEL 且 cell.isWater()  → 海洋/湖：沙/砾石水底 + 注水
            ├─ top >  SEA_LEVEL + 1                → 地表：BEACH→SAND 否则 GRASS；其下 DIRT + STONE
            └─ 地下 → STONE/DEEPSLATE（y<0），基岩封底
  └─ return CompletableFuture.completedFuture(chunk)
```

设计要点：高度 / 河流 / 气候全部由 `GeoGenesisTerrain` 计算，`GeoGenesisGenerator` 只做「按高度填 block」。地形引擎与 MC 解耦，故预览屏可直接调用 `sampleCell`/`getRegionCells` 复用同一套逻辑。

### 群系选择（气候 → 原版群系）

`GeoGenesisBiomeSource` 复用 Generator 注入的同一份 `GeoGenesisTerrain`，在 `getNoiseBiome` 中把 biome 坐标转 block 坐标后 `sampleCell`，经 `BiomeClassifier.pickKey(cell)` 映射到 `ResourceKey<Biome>`：

```
GeoGenesisGenerator.ensureEngine
  └─ 构建 GeoGenesisTerrain 单例 → setTerrain 注入共享 GeoGenesisBiomeSource
GeoGenesisBiomeSource.getNoiseBiome(x, y, z, sampler)
  └─ terrain.sampleCell(blockX, blockZ) → Cell（terrainType + climate）
  └─ BiomeClassifier.pickKey(cell) → ResourceKey<Biome>
  └─ HolderGetter<Biome>.getOrThrow(key) → Holder<Biome>   # 动态注册表，运行时解析
```

`BiomeClassifier.pickKey` 按 `TerrainClass × Climate` 直接 switch：

| TerrainClass | 群系（按气候） |
|------|------|
| `DEEP_OCEAN` | `deep_cold_ocean` |
| `OCEAN` | 冷区 `cold_ocean`，否则 `ocean` |
| `LAKE` | `swamp` |
| `RIVER` | `river` |
| `BEACH` | 冷区 `snowy_beach`，否则 `beach` |
| `PLAIN` | 冷区 `snowy_plains`；干旱 `savanna`；否则 `plains` |
| `HILLS` | 冷区 `windswept_hills`；干旱 `windswept_savanna`；否则 `forest` |
| `PLATEAU` | 冷区 `snowy_plains`；干旱 `savanna_plateau`；否则 `birch_forest` |
| `MOUNTAINS` | 冷区 `stony_peaks`；否则 `jungle` |
| `PEAK` | 冷区 `frozen_peaks`；否则 `jagged_peaks` |
| `BASIN` | 干旱 `desert`；否则 `meadow` |
| `SNOW` | `snowy_plains` |

> ⚠️ **群系解析铁律（曾崩溃）**：1.20.1 群系是动态注册表，禁止用 `ForgeRegistries.BIOMES.getValue` / `BuiltInRegistries` 静态解析 `Holder<Biome>`。必须经由 `RegistryOps.retrieveGetter(Registries.BIOME)` 在 BiomeSource CODEC 解码时取 `HolderGetter<Biome>`，运行时再 `getOrThrow(Biomes.XXX)`。详见 `docs/06-历史归档/00-设计陷阱与经验教训-design-pitfalls.md`。

## 多营力侵蚀（局部算子，2026-07-13）

`worldgen/erosion/` 复活为**全新局部算子框架**（非旧粒子系统）：

- `ErosionSystem` 按固定顺序组合 `Thermal → Coastal → Glacial → Wind` 四个 `ErosionAgent`，作用于 chunk 级 e 网格（含 `ERODE_PAD=2` 邻域），原地修改后写回 `Cell` 并重分类。
- **全部局部算子**：每个 agent 仅访问 `(i,j)` 的 pad 邻域，不超越 chunk 边界 → 跨 chunk 无缝、无 flow-accumulation 的 border 断裂（旧粒子系统的根本缺陷）。
- `Thermal`（坡积软化）/ `Coastal`（海岸冲刷）为 v1 主效；`Glacial`/`Wind` 已实现，留作气候门控 follow-up。
- 调用约定：`GeoGenesisTerrain.generateChunk` 在采样之后、**河流刻蚀之前**调用 `erosion.apply`，避免侵蚀回填河谷。
- v1 默认强度（见 `ErosionSettings.defaults()`）：Thermal/Coastal 生效，Glacial/Wind 弱或留桩。

## 河流系统（RiverField 粗格点，2026-07-13）

`worldgen/river/` 重写，**由真实 `eLand` 高度场驱动**（与海陆同源，非旧假高度 per-tile BFS）：

- `CellGenerator implements HeightProvider`：`landHeight` 返回真实 e（海洋→NaN），注入 `RiverField` 下坡汇流。
- `RiverField`：每 ~`gridSize`(默认 40) 块取一处陆地高度，对粗格点做 4 邻居下坡汇流 → 树枝状河网（±35% grid 抖动打散网格对齐伪影）；`LongCache` 按粗格 tile 缓存，确定性、跨 chunk 无缝。流量累积门控（`flowGate`）消除平坦区密集沟壑。
- 多级刻蚀（`computeShape` 内，与海陆同一趟采样）：谷肩抬升 + 河床下切（U 形谷）+ 谷壁侵蚀扰动；源头分型雕源湖盆/山泉小潭，按 `isWaterfall` 雕跌水潭。
- `apply(cell,x,z)`：刻蚀后 `terrainType=RIVER`、`riverFloorY/riverSurfaceY` 供 MC 灌水；海洋处保持 `OCEAN` 不走 `fillRiverColumn`（防破坏海洋水柱）。
- 河流特征类型：溪源 / 山泉 / 源湖 / 瀑布 / 湖泊（闭流洼地由 lake 填充逻辑处理）。

## Configuration（`GeoGenesisConfig.COMMON`）

> River / Erosion 参数当前仅代码 `defaults()`，**未暴露到本配置**。

| 段 | Key | 默认 | 说明 |
|----|-----|------|------|
| Continent Field | `continentScale` | 1500.0 | 大陆噪声尺度（blocks），频率=1/scale |
| | `continentWarp` | 0.2 | 域扭曲强度 |
| | `continentThreshold` | 0.5 | 海陆阈值 c（landW 原点） |
| | `continentBias` | 0.0 | 海陆比偏置，**正=更多海，负=更多陆**（范围[-0.5,0.5]） |
| Ocean Spline | `deepOceanLoc`/`shelfLoc`/`shallowLoc`/`coastLoc` | 0.10/0.25/0.42/0.48 | 海洋样条控制点 x（c 空间位置） |
| | `deepOceanDepth`/`shelfDepth`/`shallowDepth` | -0.85/-0.25/-0.06 | 海洋样条控制点 y（深度 e） |
| | `deepOceanDeriv`/`shelfDeriv`/`shallowDeriv`/`coastDeriv` | 0.0 | 控制点导数（样条切线） |
| Coast & Seabed | `coastWidth` | 0.30 | 海岸过渡宽度（c-space，实际带宽=coastWidth*1.5） |
| | `seabedDetail` | 0.03 | 海床细节振幅（e 单位，范围[0,0.2]） |
| Province Weights | `provinceScale` | 2000.0 | 省权重噪声尺度 |
| | `cratonWeight`/`beltWeight`/`plateauWeight`/`basinWeight` | 1.0/1.0/1.0/0.8 | 克拉通/造山带/高原/盆地 省权重 |
| Land Process | `plainBase`/`plainRough` | 0.01/0.04 | 平原基准/起伏 |
| | `hillsLow`/`hillsHigh` | 0.10/0.25 | 丘陵起伏范围 |
| | `beltRidgePower`/`beltFoothill`/`beltPeak` | 1.5/0.15/0.95 | 造山带脊幂/山麓/峰高 |
| | `plateauBase`/`plateauTop`/`plateauSteps`/`plateauStepStrength` | 0.55/0.72/3/0.6 | 高原台地基面/顶/阶梯数/阶强 |
| | `basinBase` | 0.02 | 盆地基准 |
| World Height | `seaLevel` | 63 | 海平面（Y） |
| | `minY` | -64 | 世界底（Y） |
| | `maxY` | 320 | 最大地形高度（Y） |

> **配置同步铁律**：增删 `GeoGenesisConfig` 字段，必须同步 `TerrainParams(defaults)` + `GeoGenesisGenerator(configParams)` + `GeoGenesisConfigScreen.buildParams` + `run/config/geogenesis-common.toml`（改默认值后必须同步 toml，否则用户看不到变化）。

## 关键陷阱（速查）

| # | 陷阱 | 对策 |
|---|------|------|
| 1 | 新建噪声节点未 `seed()` → NPE | 每 field 的 `seed()` 遍历所有内部节点（`Noises.seedAll`） |
| 2 | CODEC 用 `Codec.unit(x).stable()` 作 dispatch 元素 → WorldPreset 崩溃 | 必须用 `RecordCodecBuilder.create`（≥1 字段） |
| 3 | e→Y 映射用均匀线性（e=0→Y=128）→ 海岸巨型悬崖 | `heightFromE(e)` 以 e=0 锚定 seaLevel，非对称映射 |
| 4 | 海洋分支 `if(e>0) e=c` → c≈0.5 时 Y=191 | `eOcean = min(HeightCurve.eFromC(cBiased)+seabed, 0)` |
| 5 | landW 以 `c/coastWidth` 为原点 → 陆地分支恒=1 垂直悬崖 | `smoother(clamp((cBiased-threshold)/(coastWidth*1.5),0,1))` |
| 6 | 群系 `BuiltInRegistries` 静态解析 → 运行期 null | `HolderGetter<Biome>` 运行时 `getOrThrow` |
| 7 | `getBaseColumn` 未 override → 编译错 | Forge backport 抽象方法，必须实现 |
| 8 | 改 Config 默认值未同步 toml | 否则用户看不到变化 |

完整陷阱与历史教训见 `docs/06-历史归档/00-设计陷阱与经验教训-design-pitfalls.md`。

## Performance Notes

- 地形引擎 chunk 级缓存：`GeoGenesisTerrain` 按 chunk 网格（16×16）缓存 `Cell[]`（256 上限，LRU 半清），跨 chunk 共享，无 tile 边界断裂。
- `ErosionSystem`（pad=2 邻域）与 `RiverField`（world 坐标 LongCache）均为确定性、跨 chunk 无缝，热路径无注册表查找。
- 预览 `getRegionCells` 直接采样 `CellGenerator`（纯 Java，不启动 MC），复用与游戏同一套地形逻辑。

## 参考项目（World-Preview-TFC）

地形预览参考实现见 `参考/archived/World-Preview-TFC-main`：它用后台独立服务器真实调用 `ChunkGenerator`/`BiomeSource` 采样，逐 chunk 段渐进显现（计算期间仅显示 "loading"）。我们的预览（`PreviewDisplay` + `TerrainPreview`）采用 `GeoGenesisTerrain.sampleCell` 直接采样 + 真实进度回调，实现更细的进度反馈。
