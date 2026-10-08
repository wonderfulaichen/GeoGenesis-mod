# AGENTS.md — GeoGenesis Mod

> Minecraft Forge 1.20.1 模组，自定义 `ChunkGenerator` + `BiomeSource`，程序化生成地形，按气候驱动生物群系。
>
> **本文是项目级规则与速览**（稳定内容）。**水文系统的全部当前事实见 [`HYDROLOGY.md`](HYDROLOGY.md)**。
> 历史开发笔记（逐日「当前工作焦点」，1100+ 行）已归档到 `_archive/hydrology/AGENTS.md`（只读）。
>
> ⚠️ **历史教训**：那份旧 `AGENTS.md` 因为混入大量逐日笔记，长期落后于代码
> （曾写「`CACHE_SCHEMA_VERSION` 当前 69」而代码已到 72；写「域扭曲已启用 40」而实际回退为 0）。
> ⇒ **本文只写稳定内容；会变的数字一律写「以代码常量为准」并指路。**

## 开发场配置

- Gradle / JDK 装在 D 盘。运行 Gradle 需要 **Java 21**。
- Gradle wrapper：`gradlew.bat`（Windows）/ `gradlew`（Unix）。
- 工作区 = 仓库根 `D:\Office software\Development Project\GeoGenesis-mod`（**不是** mdk 目录）。

## 关键命令

```bash
gradlew.bat build              # 编译 + 打包 jar
gradlew.bat runClient          # 启动 Minecraft 客户端
gradlew.bat runServer          # 启动服务器
gradlew.bat runData            # 数据生成（输出到 src/generated/resources/）
gradlew.bat runPreview --args=12345   # 独立预览窗口（纯 Java，不启动 MC）
gradlew.bat runWorldgenGate    # 世界生成门禁（纳入的探针任一 FAIL 即构建失败）
```

### ★ 实机性能诊断（查"加载变慢"用这个）

```toml
# config/geogenesis-common.toml
worldgenProfilerEnabled = true      # 打开全流程诊断
worldgenProfilerEveryChunks = 200   # 每 200 块打一次滚动汇总
```

输出看 `logs/latest.log` 的 `[WGP]` 行，或运行目录下 `geogenesis-wgp.txt`（UTF-8，避免终端 GBK 乱码）。

**为什么必须用它**：既有的 `[PERF-TERRAIN]` / `[PERF] fillFromNoise` 只在**超阈值**（50/100ms）时打印
⇒ 快的块完全没有记录、看不到分布；且 `applyBiomeDecoration`（树/草/矿）与 `applyCarvers`（洞穴）
**此前零插桩**。profiler 输出 次数/总耗时/均/P50/P95/max/占比 + 最慢块 Top8。
**默认关闭、零开销、产出逐位不变**（不消费随机数）。

## 架构速览

| 文件 | 作用 |
|------|------|
| `GeoGenesisMod.java` | `@Mod("geogenesis")` 入口，注册 CODEC；`onClientSetup` 注册预览配置屏 + 颜色重载监听 |
| `GeoGenesisGenerator.java` | 主生成器。`fillFromNoise` 是地形产线入口；`ensureEngine` 注入共享地形到 BiomeSource；`applyCarvers` 调 `CaveCarver`；构造器注入 `VanillaDecorationFilter::filter`（剔除破坏岩层的原版装饰）；`spawnOriginalMobs` 委托原版 |
| `worldgen/generator/VanillaDecorationFilter.java` | 注入给 `ChunkGenerator` 的**过滤版 `generationSettingsGetter`**：① 剔除 9 个会打散 `StratumField` 岩层的原版"岩块团块"特征；② `oreOverrideVanilla=true` 时额外剔除 18 个 `minecraft` 金属矿特征（零 Mixin）。⚠ **多模组兼容**：只剔 `minecraft` 命名空间 |
| `worldgen/terrain/CellGenerator.java` | 统一连续场采样 + 实现 `HeightProvider` + 连续分类。★ 侵蚀 tile：`ERODE_TILE_CENTER=48 / BORDER=40 / SIZE=128`（大窗口跑模拟，只输出中心）|
| `worldgen/terrain/TerrainCharacterField.java` | 类型场：规则网格 Voronoi 高斯权重（400wu 格、σ=200、7×7 窗口）。⚠ `WARP_AMP = 0.0`（曾启用 40，实机反馈河流/湖泊出问题已回退）|
| `worldgen/terrain/StratumField.java` | 地层/岩性（纯数据层）|
| `worldgen/terrain/TectonicField.java` + `TectonicDeformation.java` | 构造骨架（Voronoi 板块 + 连续加权投票应力）+ 形变（褶皱/断层）|
| `worldgen/terrain/HeightCurve.java` | cubic Hermite Spline：`eFromC` / `heightFromE` |
| `worldgen/erosion/ErosionEngine.java` | 液滴水力侵蚀（SimpleHydrology 型，物质守恒）+ `cascadeLocal` 塌落 |
| `worldgen/hydrology/**` | **水文：见 [`HYDROLOGY.md`](HYDROLOGY.md)**（生产两条链 / 正典规格 / 铁律）|
| `worldgen/cave/CaveShape.java` + `CaveCarver.java` | 洞穴几何（零 MC 依赖纯函数）+ MC 适配器。三层配合：空腔 + 层调制 + 隧道 |
| `worldgen/ore/OreVeins.java` | 矿脉纯函数：成矿带 2D 门控 + 宿主岩/深度带 + 3D 等值面。⚠ per-ore 旋钮 = `Ore.richness`，**矿量 ∝ richness²** |
| `worldgen/climate/**` | 零依赖气候分类（Köppen 简版 / 纬度 / Whittaker / 成土母质）+ `BiomeClassifier`（`classify(Cell)→BiomeClass`）|
| `client/preview/**` | 预览 UI（`PreviewDisplay` 游戏内 / `TerrainPreview` Swing / `GeoPalette` 配色中枢 / `ColorMap` 连续色带）|

## ★ 原版复用边界（动笔写新世界生成内容前先读）

> **本项目已四次重复实现原版已有之物**（岩块团块 / 金属矿 / 紫水晶洞 / 化石）。
> 完整审计见 `docs/analysis/原版复用对照审计-2026-09-16.md`。

**判据（决定性）**：`applyBiomeDecoration` 委托 `super`（原版实现）
⇒ **只要某特征在该群系的 `BiomeGenerationSettings` 里，它就一定在生成**。
查证方法：读原版 datapack `data/minecraft/worldgen/biome/<biome>.json` 的 `features[]`。

### 已确认「原版免费提供」——不要再自研
- 植被：树 / 草 / 花 / 藤蔓 / 甘蔗 / 西瓜 / 南瓜 / 发光地衣
- 洞穴装饰：钟乳石 / 石笋 / 洞穴藤蔓 / 发光浆果 / 苔藓（我们只负责**选洞穴群系**）
- 岩浆湖、水/岩浆泉、`disk_sand/clay/gravel`、`underwater_magma`
- 地牢 `monster_room(_deep)`、沙漠水井 `desert_well`
- **★ 原版结构（村庄 / 要塞 / 废弃矿井 / 古城 / 传送门遗迹…）**：`createStructures` /
  `createReferences` / `createState` 在 1.20.1 **均非抽象** ⇒ 我们继承了默认实现，**结构一直在生成**
- **紫水晶洞 `amethyst_geode`**（`LOCAL_MODIFICATIONS` 槽）与 **化石 `fossil_*`**
- `freeze_top_layer`（按群系温度铺雪 / 水面结冰）—— 与本项目海拔雪线互补，二者并存

### 已确认「必须自研」
- 地形本体：`ContinentField` / `CellGenerator` / `TerrainCharacterField` / `TypeNoiseProvider` /
  `HeightCurve` / `SeaBedDetail`
- 地质系统：`StratumField` / `TectonicField` + `TectonicDeformation` / `LandFeatures` + `VolcanicShape`
- 侵蚀 `ErosionEngine` / `RidgeValleyErosion` · 水文 `hydrology/*`
- 洞穴几何 `CaveShape` / `CaveCarver`（原版 carver 需 `NoiseChunk`，我们无 `NoiseSettings`）
  · 洞穴群系 `CaveBiomeSelector`
- 气候分类 `climate/*` · 噪声原语 `noise/*`（**有意的重复**：为"零 MC 依赖"，让探针/预览脱离 MC 运行）· 预览 UI

### 复用机制（怎么减 / 怎么加 / 怎么接）
- **减**：`VanillaDecorationFilter` —— 构造器注入**过滤版** `generationSettingsGetter`，
  原版管线自动跳过，**无需重写装饰循环**
- **加**：同一注入点也能**追加**原版特征
- **接**：原版留空/默认处委托原版 API（例：`spawnOriginalMobs`；但 `applyCarvers` **接不了**）

> **用户约束（2026-09-16）**：**不新建任何方块/物品** ⇒ 一切方案只用原版内容或删自研代码。

## ⛔ 地质系统硬约束（违反会导致「平行带」伪影，已回归三次）

> 完整推导与历史见旧文档 `_archive/hydrology/AGENTS.md`。此处只列**禁令**。

1. **`dist`（到板块边界距离）只能用于衰减，绝不能作噪声坐标** ——
   其等值线平行于 Voronoi 边界且绕板闭合成同心环 ⇒ 必然产生平行波纹带。
   守门探针：`runTectonicWaveProbe`（判据 = 法向/切向各向异性比值）。
2. **禁止对噪声做值域量化**（`Math.floor/round` on noise）—— 量化把连续噪声切成阶梯，
   阶梯等值线是一族嵌套闭合曲线 ⇒ "密集波浪状平行细线 / 同心环梯田"。
   同理 `1-|x|` 这类 V 型折痕须用**圆化绝对值** `sqrt(x²+r²)−r`。
3. **禁止用「最近邻配对」定义参与地形合成的场** —— 配对不变区域内场是分片常数，
   配对切换处沿直线网阶跃 ⇒ "笔直长线段 + Y 形交汇"。
   探针铁证：分片常数场的 `P50|∇| ≈ 0`（实测 `7.9e-17`）。
   正解 = **连续加权投票** `stress = Σ w_i·(v_i·û_i) / Σ w_i`（窗口半径须 ≥2 格）。
4. **任何在 `reach` 处硬切换的模糊/混合，必须 smoothstep 渐隐** ——
   `if (v >= REACH) return v; else return blurred(v);` 两分支数值不等
   ⇒ 沿 `v = REACH` 等值线产生阶跃。
5. **原版装饰会把「岩块团块」塞进水平岩层 ⇒ 必须过滤**（见 `VanillaDecorationFilter`）。
   ★ 新增群系 / 改 `BiomeClassifier` 时，**必须复核过滤器仍覆盖新群系的团块特征**。
6. **折叠类算子的禁令**：`|2n−1|` 禁用于地形噪声（频率翻倍 + 梯度恒定 + 折痕等值线成对）。
   正解 = 去掉折叠，地貌语义交给噪声自身多频叠加。

### 可视化守门工具（★ 关键方法论）
```bash
gradlew runTerrainStripePngProbe -PprobeArgs="seed size step ox oz"
```
输出 `build/stripe/`：`hillshade.png`（人眼所见）、`eLand_highpass.png`（去趋势，弱纹理显形）、
`comp_deform.png`、`type_*.png` / `typeHP_*.png`。
- **★ [F] 逐分量梯度排查**：对每个输入分量单独求 `|∇|`，打印 `P99.9/P50` **长尾比**
  （平滑场 ~3~8；含折痕线的场极大）。**这是定位"折痕属于哪个场"的最快路径。**
- **⚠️ 看图陷阱**：`|∇|` 图上**平滑脊的两翼天然呈亮线**（极值在 `d=σ` 处）
  ⇒ "图上有线"**不等于**"有折痕"。判据须配合长尾比 + **线宽**（折痕 1~2px；平滑脊翼 ~35px）。
- **⚠️ 探针不够用**：阈值型探针全 PASS 时用户仍可能看到明显伪影 ——
  它们测"幅度/各向异性"，测不出"值域量化/配对阶跃"。**最终依据只能是渲染图。**

### 缓存版本（改地形产出必做）
**改动地形/水文产出必须升 `PreviewDisplay.CACHE_SCHEMA_VERSION`**，否则预览**静默复用旧磁盘缓存**
→ 表现为"改了没生效"。**当前值以代码常量为准**（常量上方的注释清单是唯一留痕处）。

## 调试开关与探针铁律

- **探针 = `diagnostics/**/*Probe.java` 独立 main 工具（`src/diagnostics` 共 252 个 `.java`，不启动 MC）。**
  改侵蚀/河流/水文后必须跑对应探针验证。
- **探针进程无 Forge 环境，配置恒为默认值**；用户调过参数时，探针须解析
  `run/config/geogenesis-common.toml` 才能复现游戏行为。
- **邻居 tile 连带生成极慢**：用单 tile 直取的探针专用方法，勿走会 fire-and-forget
  生成 8 邻居的常规入口。
- ⚠ **带判据的探针必须 `System.exit(非零)` 承载 FAIL**，否则 Gradle 报 `BUILD SUCCESSFUL`、
  门禁名存实亡（实测：252 个诊断类里**只有 36 个**调用了 `System.exit`）。
- ⚠ **不要用受 `.gitignore` 过滤的搜索工具判断"文件是否存在"** ——
  权威做法是 `git status --ignored` 或显式路径列目录。
- Windows 下含中文的提交信息用 `git commit -F file.txt`（UTF-8）。

## 已清理的废弃代码

**不存在**（曾存在，已删）：`CellGrid` · `CellRiverSystem` · `FlowAccumulationSystem` ·
`GlobalRiverSystem` · `GridRiverSystem` · `ParticleRiverSystem` · `RegionHydrologyCache/Data/Solver` ·
`RiverBrushSystem` · `RiverPieces` · `SimpleHydrologyEngine/System` · `UnifiedRiverSystem` ·
`ValleyTracingRiverSystem` · `MaterialMapper` · `TerrainCache` · `TerrainCellNoise` ·
`HeightmapPreview` · `GeologySystem` · `BaseRiverGenerator` · `LandForms.java` · `TerrainType.java` ·
`BiomeMapper.java` · `GeodeShape.java` · `LandShape.java` · `worldgen/river/*`（RTF 全套）。

**⚠ 但 `hydrology/riverline/MidpointDisplacement` 不能删**：其 `generate()` 全仓零调用（死方法），
但 `Node` / `ElevationSampler` 是**生产类型**，被 `RiverLineNetwork` 大量使用。
💣 隐藏耦合：`RiverTrace.nodeCount()` 仍消费 `RiverLineParams.fractalLevels`
⇒ 照注释去"清理旧参数"会**静默改变生产产出**。

历史备份见 `backups/`（若存在）。

## 测试

- 无单元测试文件（`src/test/` 为空）。`gameTestServer` run config 存在但未使用。
- 验证靠：探针（约 170 个）+ `runWorldgenGate` 门禁 + `runClient` 目检 + `runPreview` 看地形分布。

## 用户规则

1. **确认机制**：需求模糊 / 逻辑冲突 / 多种技术选型时**先问再干**；关键决策必须确认。
2. **知识沉淀**：关键想法、技术改进、重要参考 → 整理为笔记，主动询问是否需要输出文档。
   每完成一个任务/对话做记录。
3. **先案后码**：复杂功能先出技术方案，等回复确认再编码。简单具体指令直接执行。
4. **工程规范**：模块化、单一职责、单函数 ≤80 行、公共逻辑抽离、跨模块依赖接口、做好文件管理。
5. **安全与 Git**：生成项目必配 `.gitignore`（排除依赖/环境/IDE 配置）。
   禁止硬编码密钥，统一用环境变量。
6. **★ 渲染图 = 游戏实机**：预览出图必须走**生产路径**，否则图不可作为验收依据
   （水文的具体做法见 [`HYDROLOGY.md`](HYDROLOGY.md) §三）。
7. **★ 不要频繁问**：能自行判断的就自行判断并执行，不要每一步都来问。
   只在**真正的取舍**（有明确的不同后果、且需要业务偏好）时才问。
8. **★ 水文的三条用户口径**（2026-10-09 第三次重申，**最高优先级**）：
   - ① **参照图是 model 链跑的**（不是 legacy）；**legacy 的湖泊也不完全对**
   - ② **河流必须做成确定性骨架**（宽度等细节后续解决）—— 理由：只有确定性骨架才能**无限世界生成**；
     判据首选「骨架是否正确/连续/位置对」，**不要**先纠结宽度
   - ③ **model 链是 legacy 链的升级版**（取代关系，非平行方案）
     ⇒ legacy 的既有优点（如跨区机制）**必须被 model 继承**
   > 详见 [`HYDROLOGY.md`](HYDROLOGY.md) §〇。**这三条推翻了我此前多次的错误表述，不得再用旧说法。**

## 文档地图

| 位置 | 内容 |
|---|---|
| `README.md` | 项目速览、目录地图、许可 |
| `HYDROLOGY.md` | ★ **水文唯一事实来源** |
| `ARCHITECTURE.md` | 核心架构、配置表、注册流程 |
| `AGENTS.md`（本文件）| 项目规则、架构速览、硬约束、用户规则 |
| `forge-.../CHANGELOG.md` | 版本发布记录 |
| `docs/` | 设计/计划/修复/分析（⚠ 被 `.gitignore` 忽略，不进版本库）|
| `_archive/hydrology/` | 历史水文文档（只读，结论多已被推翻）|
| `参考/` | 参考项目源码与调研（⚠ 被忽略）|
