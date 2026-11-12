# GeoGenesis 模组结构核查（2026-11-12）

> **本文只记结构事实，不改代码。**
> 目的：在做"完整世界地形"之前，先钉住会让人改错地方、或改完没效果的结构陷阱。
> 每条结论都有 `文件:行号`；标「已核实调用点」的是逐跳确认过，不是读注释推断。
>
> 背景：此前的"完整世界地形"尝试未成功，残留状态已记录在
> [`GeoGenesis-海洋海岸需求基线-20261112.md`](GeoGenesis-海洋海岸需求基线-20261112.md)。
> 本文补的是**为什么那些残留会误导人**。

---

## 〇、五条会直接害人的结论

| # | 结论 | 后果 |
|---|---|---|
| 1 | **构造对地形高度零贡献**（唯一两条通道都是死代码） | 「构造决定整体地形」是**新建层**，不是改现有层 |
| 2 | **`CellGenerator` 上有一整套死场**（8 场 + 4 噪声 + 1 张 131072 槽缓存） | 在那里改构造/地层/气候，**改了完全没效果** |
| 3 | **每世界存在两份 `BaseWorldSampler`** | 只改一份 ⇒ 侵蚀与采样用不同地形 |
| 4 | **类型与高度是兄弟关系，不是父子** | 「构造→类型」不能复用 `typeWeights`，需新机制 |
| 5 | **海侧类型按 `e` 阈值、陆侧类型按 Voronoi 权重** | 若构造统一海陆，类型判据也必须统一，否则岸线断裂 |

---

## 一、场对象的真实归属（结论 2、3 的证据）

### 1.1 生产在用的那一份在哪

| 场类 | 生产实例位置 | 被谁采样 |
|---|---|---|
| `ContinentField` | `MorphTerrainSampler.java:112` | `:166` / `:184` / `MorphOceanAssembler:563` |
| `TectonicField` | `BaseWorldSampler.java:44` | `BaseWorldSampler.java:120` |
| `StratumField` | `BaseWorldSampler.java:45` | `:121,123,124` |
| `PrecipField` | `BaseWorldSampler.java:58` | `:139` |
| `ClimateRegion` | `BaseWorldSampler.java:56-57` | `:131` |
| 4 个气候 `Noise` | `BaseWorldSampler.java:48-51` | `:142,155,168,176-180,193,197,199` |

### 1.2 `CellGenerator` 上的死对象（构造 + 播种，但从不采样）

| 字段 | 声明 | 播种 | 判定 |
|---|---|---|---|
| `continent` | `:38` | `:248` | 唯一读点在 `landFactorAt:440`，而该方法生产零调用 |
| `tectonic` | `:56` | `:250` | 唯一读点在 `applyTectonicWeights:469`，该方法零调用 |
| `strata` | `:65` | `:251` | 全类无 `layerAt/packSequence/tiltAt` 调用 |
| `deform` | `:74` | `:252` | 只 `setSeed`，从不 `offset()` |
| `precipField` | `:86` | `:249` | 从不 `.at()`；且构造时分配约 1 MB 数组 |
| `climateRegion` | `:84` | `:258` | 除 `seed()` 外零引用 |
| `tempWarp` / `humidityNoise` / `regionWarp` / `oasisNoise` | `:81,82,102,104` | `:254-257` | 只在同样零调用的 `temperatureAt` / `regionMoisture` / `ditheredTerrain` 里被读 |
| `eqEntries`（131072 槽） | `:344` | `clearEqCache:351` | 无人读写；`eqHits/eqMisses` 恒 0 |

**证据等级**：已核实调用点。用 `grep` 逐符号确认生产路径无调用者。

### 1.3 三个"开关"从未被读

`CellGenerator.java:45` `TECTONIC_ENABLED`、`:62` `STRATA_ENABLED`、`:71` `DEFORM_ENABLED`
—— 全仓只出现在声明行。它们"保护"的代码分支早已不存在。

⇒ **读代码时看到 `if (TECTONIC_ENABLED)` 一类的痕迹，不要以为构造在参与**。

### 1.4 两份 `BaseWorldSampler`（关键）

| 实例 | 构造点 | 服务 | 判定 |
|---|---|---|---|
| A | `CellGenerator.java:244` | chunk 采样的 `Cell[]` | 生产（`:299,303,384,554,589`） |
| B | `ProcessContext.java:72` | 侵蚀 tile 的 B 输入 | 生产（`:91,98,101,104,112`） |

链式后果：**每份各带**一整套 `MorphTerrainSampler`（→ `MorphCatalog` / `MorphAssembly` / `MorphLandField`（独立 tile LRU + 独立 `tileLock`）/ `MorphOceanAssembler`）、`ContinentField`、`TectonicField`、`StratumField`、`PrecipField`、`ClimateRegion`、`HeightCurve`（另有 `ProcessContext:74` 与 `CellGenerator:133` 各一份），以及一张 `AtomicReferenceArray(262144)` 采样缓存（约 4 MB）。

⇒ 不是 bug（P tile 需要冻结上下文），但**任何"给地形加东西"的改动必须同时覆盖两份**，否则侵蚀看到的地形与最终地形不一致。

---

## 二、构造与地形高度的实际关系（结论 1）

### 2.1 生产高度里没有任何构造量

| 侧 | 高度来源 | 证据 |
|---|---|---|
| 陆地 | `e = max(GROUND_E + covered × E_SPAN, 0)`，`covered` 全部来自噪声配方 | `MorphLandField.java:246`；`MorphScale.java:25,28` |
| 海洋 | `hills` / `canyons` / `selector` 全是 Perlin 映射到 `[-63,-21]` | `MorphOceanAssembler.java:109-128` |

### 2.2 两条"构造→高度"通道都不在生产

| 通道 | 状态 | 证据 |
|---|---|---|
| `TectonicField.elevationOffset` | 仅 `TectonicProbe` 调用 | `TectonicField.java:639`；javadoc `:620-634` 自陈已退出生产 |
| `CellGenerator.applyTectonicWeights` | `private`，**零调用者** | `CellGenerator.java:466` |

### 2.3 已被否决的做法与原因（勿重犯）

`TectonicField.java:620-634` 记录：直接给高程偏置有两大问题——
1. 与权重调制重复 ⇒ 双重加深
2. **直接抬 `e` 会连锁增强地形雨**（`PrecipField:180-188` 用 `height.at()` 算抬升），实测最干桶 0.922→0.959，干旱区区分度被稀释

⇒ 构造若要主导高度，必须走"导出量"路线（厚度/密度 → 高度），不能走"加偏置"路线。

---

## 三、类型系统的真实结构（结论 4、5）

### 3.1 `TerrainClass` 17 个值，生产只产出 7 个

| 产出 | 值 |
|---|---|
| ✅ 会产出 | `OCEAN` / `DEEP_OCEAN` / `CONTINENTAL_SHELF` / `PLAIN` / `HILLS` / `PLATEAU` / `MOUNTAINS` |
| ❌ 永不产出 | `SUBMARINE_RIDGE` / `SEAMOUNT` / `LAKE` / `RIVER` / `BEACH` / `PEAK` / `BASIN` / `SNOW` / `VOLCANO` / `VOLCANIC_FIELD` |

唯一两处赋值：`MorphTerrainSampler.sampleColumn:189-207`、`CellGenerator.applyProcessDelta:568-579`。

### 3.2 `typeWeights` 与高度是兄弟，不是父子

```
MorphLandField.sample(x,z)
  ├─ weight[18] ──► MorphKernel ──► regionHeight ──► e ──► height   （高度支）
  └─ weight[18] + alpha ──► addType 投影 ──► typeWeights ──► argmax ──► terrainType  （类型支）
```

两支共享同一份 Voronoi 权重。`MorphTerrainSampler.java:179` 明写"分类只提供语义，绝不反馈高度"。

⇒ **不存在「类型→高度」的活路径**。要让类型影响高度，必须新建通道。

### 3.3 海陆两套判据（这是岸线处最危险的一处）

| 侧 | 判据 | 依据 |
|---|---|---|
| 海 | `e` 阈值 `-0.08` / `-0.18` | `MorphTerrainSampler.java:190-192` |
| 陆 | 18 条 recipe 的 Voronoi 权重 argmax（**完全不读 `e`**） | `:197-206` |

海侧类型沿等深线走，陆侧沿 Voronoi 直线段走，**两者在海岸带拼接处没有任何平滑**。

⇒ 若"构造统一海陆"，类型判据也必须统一；否则构造虽连续，类型仍在岸线处断裂。

### 3.4 第三、第四套"浅海"阈值（同族分裂）

| 来源 | 浅海/深海分界 |
|---|---|
| `MorphOceanAssembler.sea()` | 块：海岸 ≥−2、浅海 ≥−9（`MorphOceanAssembler.java:660-666`） |
| `terrainType` | e：−0.08（≈−10.2 格）/ −0.18（≈−22.9 格） |
| 落块层 | 绝对 Y：`groundY < terrain.seaLevel()`（`GeoGenesisGenerator.java:1052`） |

同一个"浅海"概念三套阈值，最大差 2 倍以上。

### 3.5 `landFeat` / `oceanFeat` 恒为 `null`

`Cell.java:60-63` 两个字段；`MorphCellAdapter.java:14-31` **从不赋值**（生产唯一适配器）。

⇒ 连带死掉：`volcanoClass`（`CellGenerator.java:695-713`）、`classify` 的 `SEAMOUNT`（`:662`）与 `SUBMARINE_RIDGE`（`:665`）。

⚠ `PreviewDisplay.java:421,438,443` 的注释声称"火山类型丢失已修复"——**实际未修复**：修复只把 `landFeat` 加进签名，生产链从未填过该字段。

---

## 四、高度与坐标空间

### 4.1 三种高度并存

| 表示 | 定义 | 值域 |
|---|---|---|
| `e` | 归一化高度，0 = 海平面 | 名义 [−1,1]；实际陆侧 ≤0.931，海侧 ≥−0.496 |
| 名义 blocks | 陆 `e×236`、海 `e×127`（**仅记账**） | ±236 / ±127 |
| 世界 Y | `HeightCurve.heightFromE(e)` | 生产 [−64, ≈299.4]；地形实占 [≈0, ≈283] |

### 4.2 陆海斜率不同（约 1.86 倍）

| 侧 | 斜率 | 是否吃 `verticalScale` |
|---|---|---|
| 海 | `seaLevelY − minY = 127` 格/e | 否 |
| 陆 | `(maxY−seaLevelY) × peakFraction × VS = 236.44 × VS` | 是 |

装配器用字面量 `236`（`MorphOceanAssembler.java:62`），与 `HeightCurve` 差 0.19%；`VS≠1` 时相差 VS 倍。

### 4.3 e→Y 有三份实现

`HeightCurve.java:70-79`（真）、`PlanePainter.java:240-244`、`TerrainPreview.java:394-399`。
⇒ 改 `HeightCurve` 不会传播到预览。

### 4.4 `_BLOCKS` 命名的常量实际是 wu

`MorphLandField.java:74` `TILE_BLOCKS=1024.0`、`:79` `QUERY_RADIUS=728.0`、`MorphBoundaryGeometry.java:61` `BAND_HALF_BLOCKS=120.0` —— 全部按 **wu** 使用。`HS=2` 时"120 块"实为 240 方块宽。

### 4.5 坐标空间归属

- **wu**：地形 / 地质 / 侵蚀缓冲 / 水文输入
- **block**：MC 落块 / 洞穴（`CaveShape`，不除 hs）/ 矿脉（`OreVeins`）/ 水文窗口（REGION=512）

⇒ `horizontalScale` 只缩放地形，不缩放洞穴与矿脉。

---

## 五、阶段边界：能改什么、绝不能改什么

### 5.1 阶段序列

```
BIOMES → NOISE(fillFromNoise) → SURFACE(空) → CARVERS → FEATURES → SPAWN
```

`NOISE` 内部四步（顺序不可换）：
1. 采样（`GeoGenesisTerrain.java:504-511`）
2. 侵蚀（`extractFromTile:515`）
3. 水文写回（`applyHydrologyValley:520`）
4. 落块（`GeoGenesisGenerator.java:988-994`）

### 5.2 逐阶段可改 / 不可改

| 阶段 | 可改 | 绝不能改 |
|---|---|---|
| 采样 | `Cell` 全部地形/气候/地质字段 | 水文字段（尚无意义） |
| 侵蚀 | `e` / `height` / `gradient` / `terrainType`（仅 e≥0） | `eClimate`（群系稳定锚） |
| 水文 | `height`（**只下切**）、水文字段 | `e`、`eClimate`、`terrainType`、岩层；任何抬升 |
| 落块 | 方块 | 任何 `Cell` 字段 |
| 洞穴 | 方块 → `AIR` | 流体、海底列、地表以上 |
| 装饰 | 追加方块 | 只能经"过滤 getter"间接删除 |

### 5.3 四条顺序铁律（破坏即出问题）

1. **水文建网不能用含侵蚀的采样**（`HydrologyChunkSampling.java:15-24`）—— 否则数千次采样触发 tile 冷生成，预览开窗即卡。
2. **`getBaseHeight`/`getBaseColumn` 绝不能触发侵蚀**（`GeoGenesisGenerator.java:1436-1449`）—— 它们跑在 `STRUCTURE_STARTS`，早于 `NOISE`，把最贵操作接到最热调用点会卡死。
3. **群系判据用 `eClimate`（侵蚀前）而非 `e`**（`Cell.java:33-42`）—— 否则预览≠游戏，且垂直带边界贴着 48wu tile 缝走成直线。
4. **矿脉必须在落块时"预测"洞穴**（`OreVeins.java:568-576`）—— 因为洞穴在 `CARVERS` 才挖。

### 5.4 水文与侵蚀的合并公式（不是简单先后）

```
rawDelta = cell.height − column.originalGroundY()      // 侵蚀增量（全量）
mask     = erosionYieldToRiver ? column.erosionMask() : 1.0
delta    = rawDelta × mask
cut      = column.erosion() + (rawDelta − delta)
if (cut < 0) cut = 0                                   // RIVER_CUT_NONNEGATIVE
cell.height −= cut
```

- 求解水面/床面用**侵蚀后**地形（`ModelHydrologyProvider.java:2458` `processHeightAtBlock`）
- 写回基准用**侵蚀前** `originalHeights`（`GeoGenesisTerrain.java:501,509`）

两者不矛盾，是同一公式的两个参照系。若把 `originalGroundY` 换成侵蚀后 ⇒ `rawDelta=0` ⇒ 失去 `mask` 软让步 ⇒ 河道两侧出现垂直断面墙。

---

## 六、其余结构陷阱（改地形时会踩）

### 6.1 缓存与"改了没生效"

| 缓存 | 位置 | 失效条件 |
|---|---|---|
| 预览磁盘缓存 | `PreviewDiskCache.java:40-48` | `CACHE_SCHEMA_VERSION` 或 `configHash` 变 |
| `CACHE_SCHEMA_VERSION` | `PreviewDisplay.java:641`（**以代码常量为准**） | 改地形/水文产出必须 +1 |
| `configFingerprint()` | `GeoGenesisConfig.java:753-769` | **只覆盖 4 项**：`erosionEnabled/erosionYieldToRiver/erosionStrength/erosionDropsMul` |

⇒ 改 `horizontalScale` / `seaLevel` / `riverEnabled` 等**不在指纹里**，靠 `params.hashCode()` 兜底；改地形产出时**必须手动升 schema 版本**，否则预览静默复用旧缓存。

### 6.2 并发缺陷（已确认，非本次任务但会影响判断）

| # | 问题 | 位置 |
|---|---|---|
| 1 | `PrecipField.node()` 非原子读 `keys[slot]` 再读 `vals[slot]`，可返回异键值 | `PrecipField.java:160-171` |
| 2 | `OreVeins.beginColumn` 静态三字段无同步，可拼出不一致三元组 | `OreVeins.java:399-401, 425-430` |
| 3 | `BiomeColumnCache` 键只有 `(x,z)` ⇒ 3D 洞穴群系塌成"一列一值" | `BiomeColumnCache.java:15-16` |
| 4 | `inletPlans` 锁内跑秒级 `buildLakeMorphology` | `ModelHydrologyProvider.java:2882-2887` |
| 5 | `BiomeColumnCache` 槽锁内调 `sample.get()`（可能触发精确 P tile） | `BiomeColumnCache.java:33` |

项目在 `CellGenerator` 里已把同型问题修成"不可变 entry 单条原子发布"（`:320-335` 的长注释），但 `PrecipField` / `OreVeins` 未同步改造。

### 6.3 文档说谎清单（`AGENTS.md` 需更正）

| 位置 | 宣称 | 实际 |
|---|---|---|
| `AGENTS.md:52,83,233` | `TerrainCharacterField` 存在，`WARP_AMP=0.0` | **该类文件已删除**，全仓只在注释里出现 |
| `AGENTS.md` 同批 | `TypeLandShape` / `TypeNoiseProvider` | 同样只在注释中 |
| `AGENTS.md:49` | 构造器注入 `TectonicField` 参与地形 | 构造对高度零贡献 |
| `TerrainClass.java:4` | "12 地形类型" | 17 个 |
| `Cell.java:68` | "5 类型………和=1" | 长度 17，实际只有 4 槽非零 |
| `CellGenerator.java:447-464` | "构造决定地形类型" | `applyTectonicWeights` 零调用 |
| `PreviewDisplay.java:421,438,443` | "火山类型丢失已修复" | 未修复，`landFeat` 恒 null |
| `GeoGenesisConfig.java:206` | `verticalScale` "morph 配方都读" | 只在 `HeightCurve:75` 生效 |
| `AGENTS.md` 水文叙述 | 降水驱动水文 | model 链用常量 `CLIMATE=(0.004,0.002,0.3)`（`ModelHydrologyProvider.java:208-209`），`Cell.precipitation` 未接 |

---

## 七、这些结构事实对"构造决定整体地形"的约束

| 约束 | 含义 |
|---|---|
| 构造必须成为**统一的 `eBase`** | 海陆同一公式，用连续 `shellS` 插值；分海陆两套 ⇒ 岸线处两来源对不上 |
| 类型判据必须同步统一 | 现海侧按 `e`、陆侧按 Voronoi；若只统一高度，类型仍在岸线断裂 |
| 两份 `BaseWorldSampler` 必须同时改 | 否则侵蚀与采样看到不同地形 |
| 不能改 `CellGenerator` 的死场 | 那里改了什么都不会发生 |
| 地形雨风险只在陆侧 | `PrecipField:180-188` 用 `height.at()`；但结论是**分步接线**（陆侧系数从 0 开始），不是海陆分家 |
| 必须升 `CACHE_SCHEMA_VERSION` | 且 `configFingerprint` 不覆盖相关键，不能指望自动失效 |
| 既有基准会失效 | `P1CellRenderProbe` 的 1401² SHA 与已认可大图需重立 |

---

## 八、建议的下一步（按风险排序）

1. **先更正 `AGENTS.md`** 的过期架构行（`TerrainCharacterField` 等）——否则后续每轮都从错误前提出发。
2. **再定 `CellGenerator` 死场的处置**（删/留），因为它直接影响"改哪里才有效"。
3. **然后才谈统一 `eBase`**：先只做海洋侧（陆侧系数 0），可严格对拍。
4. **最后处理类型判据统一**——这是岸线断裂的根因，但不做也不影响高度先跑通。

> ⚠ 未做任何代码改动。以上全部为只读核查结论。
