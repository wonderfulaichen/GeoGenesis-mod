# GeoGenesis 项目交接文档

**日期**: 2026-07-13（整体刷新）
**项目**: Minecraft Forge 1.20.1 地形生成模组
**路径**: `D:\Office software\Development Project\GeoGenesis-mod\forge-1.20.1-47.4.10-mdk`

> **⚠️ 2026-07-13 整体重写（地质过程范式）**：本文档其余部分已刷新为当前架构。旧架构（`SimpleContinent`/`GeoLevels`/`LakeGenerator`/`HydraulicErosion` droplet/`TileLakeSolver` 等）已彻底废弃，仅作历史参考。**权威架构以 `ARCHITECTURE.md` 为准**，地形重构方案见 `docs/01-架构设计/01-地形重建设计-terrain-rebuild.md`，开发日志见 `DEV_REPORT.md` §9。
>
> **事件（2026-07-13 末）**：一次 Agent 会话被要求「更新文档」却误解为继续写地形/河流/侵蚀代码，用户在**侵蚀编写阶段**中止、回退未果、上下文丢失。核查 git 历史干净（分支 `backup-before-rewrite`，末提交 `70cd037`），代码全在工作区未提交 → **保留代码不回退**，本次改为补文档（见文末「事件记录」）。**当前工作区状态未经重新编译验证**，`runClient`/`runPreview` 目检仍待做。

---

## 一、项目架构（当前，2026-07-13）

### 世界观：独立 ChunkGenerator + 零 MC 依赖地形引擎
- 走 **`extends ChunkGenerator`** 路线（非 Mixin 注入 NoiseBasedChunkGenerator）。`fillFromNoise()` 逐 block 填方块。
- 地形逻辑集中在 **`worldgen/terrain/GeoGenesisTerrain`**（零 MC 依赖，可在纯 Java 跑通用于预览/测试）；`GeoGenesisGenerator` 只把引擎结果写进 chunk；`GeoGenesisBiomeSource` 复用同一份引擎结果选群系。

### 核心链路
```
世界创建 → world_preset/normal.json → GeoGenesisGenerator.CODEC
    → GeoGenesisGenerator.fillFromNoise()
        → GeoGenesisTerrain.getChunkCells(cx,cz)
            → CellGenerator.sample(x,z)              # 统一连续场 e(x,z)
                → ContinentField.sample → c∈[0,1]
                → eOcean = min(HeightCurve.eFromC(cBiased)+seabed, 0)
                → eLand  = LandShape.sample(...)      # 省权重 softmax + 过程形态
                → e = lerp(eOcean, eLand, landW)      # 单一 e 场，海岸 C0 连续
            → ErosionSystem.apply(eGrid)             # 多营力局部侵蚀（先于河流）
            → RiverField.apply(cell,x,z)             # 粗格点河网 + 河谷多级刻蚀
    → 对 riverMask&&RIVER 列走 fillRiverColumn 灌水
GeoGenesisBiomeSource.getNoiseBiome → terrain.sampleCell → BiomeClassifier.pickKey → Holder<Biome>
```

### 包结构（当前）
```
com.geogenesis/
├── GeoGenesisMod.java              # @Mod 入口 + DeferredRegister 注册 generator/biomesource CODEC
├── client/                         # GeoGenesisConfigScreen / ParamSlider / preview/(11图层+调音台mixer/)
├── config/GeoGenesisConfig.java    # Forge COMMON：地质过程参数（continent*/ocean spline/coast/province*/land process/world height）
└── worldgen/
    ├── climate/   BiomeClassifier(pickKey)/Climate/ClimateZone/Latitude   # 零依赖分类，不持颜色
    ├── generator/ GeoGenesisGenerator / GeoGenesisBiomeSource
    ├── terrain/   GeoGenesisTerrain / Cell / CellGenerator(实现HeightProvider) / ContinentField / LandShape / SeaBedDetail / HeightCurve / TerrainClass(12类) / TerrainParams / Size / SplineUtil
    ├── river/     HeightProvider / RiverField(粗格点下坡汇流+多级刻蚀+LongCache) / RiverSample / RiverSettings
    ├── erosion/   ErosionSystem(编排) / ErosionAgent(接口) / Thermal / Coastal / Glacial / Wind / ErosionSettings   # 局部算子框架（2026-07-13 复活）
    └── noise/     NoiseEngine 封装 + 27 个噪声原语
```

---

## 二、已完成阶段

| 阶段 | 时间 | 交付 |
|------|------|------|
| P0 | 第一轮 | 骨架：Mod 入口 + Config + Generator + BiomeSource + world_preset JSON |
| P1 | 第一轮 | 27 个噪声模块（Simplex/Worley/fBM/Ridge 等）+ Noises 工厂 |
| P2 | 第一轮 | Cell/Tile/TileCache + ContinentField + CellGenerator + PreviewLauncher |
| P3 | 第一轮 | FastFlow 水文（未接入显示） |
| P5 | 2026-07-09 | 气候→群系接线：BiomeClassifier 零依赖分类 + GeoPalette 数据驱动配色（11 图层）+ PreviewDisplay/TerrainPreview 双预览 |
| UI | 2026-07-09 | 调音台面板（Mixer Panel）：基础因素双曲线范围图 + 14 控制点滑块 + 条件因素分类色条 + 三页分页（地形/气候/参数）+ 重置按钮 + tooltip |
| 地形重写 | 2026-07-13 | 阶段 1（统一场地形管线）+ 阶段 2（RiverField 粗格点河网 + 河谷刻蚀）+ 阶段 3（多营力局部侵蚀 ErosionSystem）编码并接线 |
| 侵蚀复活 | 2026-07-13 | `worldgen/erosion/` 重建为局部算子框架（替代 2026-07-08 删除的粒子系统） |

---

## 三、当前地形系统状态（地质过程范式）

### 统一连续高度场 `e(x,z)`
- 大陆性 `c∈[0,1]` 是单一连续噪声场（FBM Simplex + Warp），海陆仅是对同一场的条件切分：
  - `cBiased = clamp(c - continentBias, 0, 1)`（正=更多海，负=更多陆，默认 0.0）
  - `eOcean = min(HeightCurve.eFromC(cBiased) + seabed, 0)`（海洋基面，样条定深）
  - `landW = smoother(clamp((cBiased - threshold)/(coastWidth*1.5), 0, 1))`（以 threshold 为原点，C0 连续）
  - `e = lerp(eOcean, eLand, landW)`
- 实测 `e<0` 判洋（非 `c<threshold`）。

### 陆地过程形态（LandShape）
- 省权重 softmax（克拉通/造山带/高原/盆地，和为 1、无硬边界）→ 椒盐天然消失。
- 各省按地理过程生成形态后加权合成 `eLand∈[0,1]`：平原/丘陵（低幅 FBM）、造山带（脊线×域扭曲×proximity 包络）、高原（阶梯台地）、盆地（近海平面低填）。
- 河蚀刻谷：`RiverField` 注入真实 `eLand`，多级刻蚀（谷肩抬升 + 河床下切 U 形谷 + 谷壁侵蚀）把河床刻入谷底。

### 多营力局部侵蚀（ErosionSystem，2026-07-13 复活）
- `Thermal → Coastal → Glacial → Wind` 四个 `ErosionAgent`，作用于 chunk 级 e 网格（含 `ERODE_PAD=2` 邻域），原地修改后写回 `Cell` 并重分类。
- **全部局部算子**：仅访问 pad 邻域，不超越 chunk 边界 → 无 flow-accumulation 的 border 断裂。
- `GeoGenesisTerrain.generateChunk` 在采样之后、**河流刻蚀之前**调用 `erosion.apply`。

### 河流系统（RiverField 粗格点，2026-07-13）
- `CellGenerator implements HeightProvider`：`landHeight` 返回真实 e（海洋→NaN）注入 `RiverField` 下坡汇流。
- 每 ~`gridSize`(默认 40) 块取陆地高度，4 邻居下坡汇流 → 树枝状河网（±35% grid 抖动打散伪影）；`LongCache` 按粗格 tile 缓存，确定性、跨 chunk 无缝。流量门控 `flowGate` 消除平坦区密集沟壑。
- 河流特征：溪源/山泉/源湖/瀑布/湖泊。

### 群系选择（气候 → 原版群系）
- `BiomeClassifier.pickKey(cell)` 按 `TerrainClass × Climate` 直接 switch 映射到 `ResourceKey<Biome>`。
- 群系是动态注册表：必须经由 `RegistryOps.retrieveGetter(Registries.BIOME)` 在 CODEC 解码时取 `HolderGetter<Biome>`，运行时 `getOrThrow`。

---

## 四、重要陷阱（已踩过，合并 durable）

### 通用 / 早期
1. **JSON snake_case vs Codec**: `fieldOf("min_y")` 对 `"min_y"`，不是 `"minY"`
2. **Codec 静态初始化死循环**: 改成 `noiseCodec()` 懒加载
3. **ChunkAccess 1.20.1 没有 setHeight**: heightmap 从非空方块自动更新
4. **Gradle 编译 chcp/编码**: Git Bash 输出可能是 GBK，用 PowerShell 查 `.*错误`
5. **fBM 多 octave 导致 chunk 边界断裂**: 改用单 octave Simplex / 世界坐标确定性格点
6. **LakeGenerator 网格索引（非正方形区域 AIOOBE → 预览全黑）**：凡遍历 `Cell[][]` 必须显式传 `cellsX, cellsZ` 并按各自维度边界检查，绝不用 `Math.max` 当边长。

### 2026-07-13 关键陷阱（地形/Forge/侵蚀）
7. **dev 运行 srg jar 致命坑**：`build`/`reobfJar` 产出的 reobf jar 是 srg 映射，拷进 `run/mods/` 会与 official 运行时游戏不匹配 → 整条 `NoSuchFieldError`（`f_XXXX_`）。**第一反应是删 `run/mods/` 里的手放 jar**，还原标准 `DeferredRegister`+`static CODEC`。dev 走 `sourceSets.main`，绝不放 reobf jar。
8. **BUILD SUCCESSFUL ≠ 完成**：地形/视觉/玩法相关任务必须 in-game 或 runPreview 截图验收，没截图 = 没完成。
9. **海岸 landW 必须以 threshold 为原点**：`smoother(clamp((cBiased-threshold)/(coastWidth*1.5)))`；若用 `c/coastWidth` 则陆地分支恒=1 → 垂直悬崖。
10. **e→Y 映射必须非对称**：`heightFromE(e)` 以 e=0 锚定 seaLevel=63；海洋分支禁 `if(e>0) e=c`（c≈0.5 时 Y=191 异常），应 `eOcean = min(HeightCurve.eFromC(cBiased)+seabed, 0)`。
11. **biome 是动态注册表**：禁止 `BuiltInRegistries`/`ForgeRegistries.BIOMES` 静态解析 `Holder<Biome>`；必须用 `HolderGetter` 运行时 `getOrThrow`。
12. **自定义 fieldless CODEC 必须用 `RecordCodecBuilder.create`（≥1 字段）**，禁止 `Codec.unit(x).stable()` 作 dispatch 元素（导致 WorldPreset 加载崩溃）。
13. **新建噪声节点必须显式播种**：不在 `sample` 直接引用树内的 Simplex 必须在 `seed()` 里 `Noises.seedAll`，否则 compute NPE。
14. **配置同步铁律**：增删 `GeoGenesisConfig` 字段须同步 `TerrainParams`/`RiverSettings`(record+defaults) + `GeoGenesisGenerator`(configParams) + `GeoGenesisConfigScreen.buildParams` + `run/config/geogenesis-common.toml`；改默认值后必须同步已存在的 toml。

---

## 五、当前问题

### 已解决
- 侵蚀系统：2026-07-08 删除（优先气候→群系），2026-07-13 复活为局部算子框架。
- 地形断裂感：`GeoGenesisTerrain` 区域级 cell 缓存跨 chunk 共享，无 tile 边界断裂。
- 海岸线自然度：统一连续场 `e=lerp(eOcean,eLand,landW)`，海岸 C0 连续。

### 当前待解决（与权威文档一致）
- **调音台 mixer 仍绑旧 `@Deprecated` 字段**（inert，拖动不影响新引擎）：需重绑到地质过程参数（阶段 4）。
- **River / Erosion 参数仅代码 `defaults()`，未进 Forge Config**：待阶段 4 暴露到「River Network」/「Erosion」段并 6 处同步。
- **`GeoGenesisGenerator` 世界高度（`WORLD_MIN_Y/MAX_Y/SEA_LEVEL`）硬编码**，未接 `GeoGenesisConfig` 的 `World Height` 段。
- **runClient / runPreview 目检仍待做**（按铁律"BUILD SUCCESSFUL ≠ 完成"）。

---

## 六、待办（按优先级）

| 优先级 | 内容 | 状态 | 备注 |
|--------|------|------|------|
| 🔴 最高 | runClient/runPreview 目检验收 | 待做 | 地形/河流/侵蚀视觉验收，按铁律必须截图 |
| 🟡 高 | 重新 `gradlew compileJava` 验证当前工作区可编译 | 待做 | 本次中断于侵蚀编写阶段，需复验 |
| 🟡 高 | 阶段 4：mixer 重绑 + River/Erosion 进 Forge Config | 待做 | 6 处同步铁律 |
| 🟡 中 | 阶段 5：文档终校（AGENTS/ARCHITECTURE/DEV_REPORT/docs 索引已基本同步） | 进行中 | 本会话已完成事件落档 + HANDOFF 刷新 |
| 🟢 低 | P6 洞穴 + 矿石分布 | 待规划 | Worley 腔体 + 地质矿石 |
| 🟢 低 | P7 特殊地貌（火山/断崖/瀑布） | 待规划 | 可配置开关 |
| 🟢 低 | P8 自定义结构系统 | 待规划 | 村庄/废墟适配地形 |
| 🟢 低 | P9 植被/地物 + 调优 | 待规划 | Cell 驱动放置 + 全参数收敛 |

---

## 七、运行命令

```bash
cd forge-1.20.1-47.4.10-mdk

# 编译（验证当前工作区）
./gradlew.bat compileJava

# 进游戏
./gradlew.bat runClient

# 独立预览窗口（纯 Java，不进游戏）
./gradlew.bat runPreview --args=12345

# 打包发布 jar（仅产出用，勿拷进 run/mods）
./gradlew.bat build

# 清理（卡住时）
./gradlew.bat --stop
```

---

## 八、参考源

| 路径 | 作用 |
|------|------|
| `参考/sources/ReTerraForged（改版）-1.20.2/` | **架构主参考** — Cell + Tile + 噪声 + Populator |
| `参考/sources/TerraForged-0.3.x/` | **辅参考** — TerrainData + 侵蚀 + NoiseLevels |
| `参考/archived/vanilla_worldgen/` | **原版设定** — noise_settings/density_function/continentalness |
| `参考/archived/misode.github.io-master/` | **数据包编辑器** — 可视化 worldgen 参数 |

### 参考项目关键区别
- ReTerraForged: 用 **Mixin 注入 NoiseBasedChunkGenerator**，不是独立 ChunkGenerator
- TerraForged: 也用独立 ChunkGenerator，但高度归一化到 [0, 1]
- Vanilla: `xz_scale = 0.25`, 9 octaves, firstOctave = -9, 无缝 continuous 地形

---

## 九、UI 配置屏（当前）

游戏内 `GeoGenesisConfigScreen`：右侧预览上方工具栏（图层选择 + 色带 + 水文开关）；左侧三页分页（地形/气候/参数）；底部固定「保存」「重置」。

调音台（`client/preview/mixer/`）：`Factor`/`ControlPoint`/`ConfigBinding`/`FactorMixer`/`FactorCurveChart`/`MixerPanel`/`FactorCategoryBar`。

> ⚠️ **已知**：基础因素曲线/条件因素滑块当前仍绑旧 `@Deprecated` 配置字段（inert），拖动仅在内存层生效、未真正持久化到 `GeoGenesisConfig`（阶段 4 待修）。

---

## 事件记录（2026-07-13 末）

- **误写代码事件**：Agent 会话被要求「更新文档」，却误解为继续编写地形/河流/侵蚀代码，用户在**侵蚀编写阶段**中止；尝试回退未果、上下文丢失。
- **核查**：git 历史干净（分支 `backup-before-rewrite`，末提交 `70cd037`「distance field river v3 water fix」），地形/河流/侵蚀重写代码全部驻留工作区（未提交、未污染历史），无 stash、reflog 无破坏性操作。
- **处置**：保留代码、不回退（盲回退风险更高）；本次改为完成文档更新（刷新本文 + `DEV_REPORT.md` §9.6 + `AGENTS.md` 事件记录）。
- **诚实标注**：各阶段完成时记录 BUILD SUCCESSFUL，但当前工作区状态**未经重新编译验证**，runClient/runPreview 目检仍待做。
