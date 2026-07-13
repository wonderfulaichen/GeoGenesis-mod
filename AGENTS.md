# AGENTS.md — GeoGenesis Mod

> Minecraft Forge 1.20.1 模组，自定义 `ChunkGenerator` + `BiomeSource`，程序化生成地形，并按气候驱动生物群系。

## 开发场配置

- Gradle / JDK 等装在 D 盘。运行 Gradle 需要 Java 21。
- Gradle wrapper: `gradlew.bat` (Windows) / `gradlew` (Unix)

## 关键命令

```bash
gradlew.bat build              # 编译 + 打包 jar
gradlew.bat runClient          # 启动 Minecraft 客户端
gradlew.bat runServer          # 启动服务器
gradlew.bat runData            # 运行数据生成（输出到 src/generated/resources/）
gradlew.bat runPreview --args=12345   # 独立预览窗口（纯 Java，不启动 MC）
```

## 架构速览

| 文件 | 作用 |
|------|------|
| `GeoGenesisMod.java` | `@Mod("geogenesis")` 入口，注册 CODEC；`onClientSetup` 注册预览配置屏 + `GeoGenesisColorReloadListener` |
| `GeoGenesisGenerator.java` | 主生成器，`fillFromNoise` 是地形产线入口；`createState` 注入共享地形到 BiomeSource |
| `GeoGenesisBiomeSource.java` | BiomeSource，按 Cell 气候选原版群系 |
| ~~`worldgen/generator/BiomeMapper.java`~~ | ⚠️ 已删除（2026-07-13）：群系映射合并入 `BiomeClassifier.pickKey`，不再有独立文件 |
| `worldgen/climate/BiomeClassifier.java` | 零依赖群系分类（`classify(Cell)→BiomeClass` 枚举，无颜色） |
| `worldgen/climate/ClimateZone.java` | 零依赖 Köppen 简版气候带（A/B/C/D/E） |
| `worldgen/climate/Latitude.java` | 零依赖纬度带 `latitude01(worldZ)` |
| `client/preview/ColorMap.java` | 零依赖连续色带（Lab 插值 + bake LUT），不 import MC |
| `client/preview/GeoPalette.java` | 零依赖配色中枢：`PreviewLayer` 注册表 + 多内置色带 + 离散映射 + 覆盖接口 + 图例条目 |
| `client/preview/PreviewColor.java` | MC 侧着色外观，委托 `GeoPalette` 输出各图层 ABGR |
| `client/preview/PreviewDisplay.java` | 游戏内预览控件（11 图层 + 图例 + 分辨率/色带 + 水文 + 拖拽防抖） |
| `client/preview/TerrainPreview.java` | 独立 Swing 预览窗口（11 图层 + 图例搜索 + 分辨率 + 水文） |
| `client/preview/GeoGenesisColorReloadListener.java` | MC 资源重载监听器，JSON 资源包覆盖 `GeoPalette` 默认 |
| `client/GeoGenesisConfigScreen.java` | 游戏内预览/配置屏（三页标签：地形/气候/参数 + 右侧工具栏 + 预览） |
| `client/ParamSlider.java` | 通用参数滑块（含重置按钮 + tooltip） |
| `client/preview/mixer/Factor.java` | 调音台因素数据模型（双曲线/单曲线 + 分类色条 + ConfigBinding） |
| `client/preview/mixer/FactorCurveChart.java` | 因素曲线可视化（双曲线范围图 + 单曲线 + 控制点拖拽） |
| `client/preview/mixer/FactorMixer.java` | 多因素协调管理器（loadFromConfig/applyToConfig） |
| `client/preview/mixer/MixerPanel.java` | 调音台面板 UI 容器（可折叠，集成曲线图+分类色条+滑块） |
| `client/preview/mixer/ControlPoint.java` | 可拖拽控制点（X/Y 坐标 + 选中/悬停状态） |
| `client/preview/mixer/ConfigBinding.java` | 控制点→GeoGenesisConfig 参数绑定 |
| `client/preview/mixer/FactorCategoryBar.java` | 条件因素分类色条（温度/湿度/大陆性，可拖拽边界） |
| `client/preview/TerrainConfigPanel.java` | 地形页：基础因素曲线图 + 14 个控制点滑块（可折叠） |
| `client/preview/BasicParamsPanel.java` | 参数页：噪声/尺度等基础参数滑块 + 承载三个可视化组件（可滚动/scissor 裁剪） |
| `client/preview/WorldHeightBar.java` | 参数页：世界高度柱状图（柱图 + 横排滑块 maxY/山脊上限/海平面/世界底 + 色点标记连线 + 内嵌高度预设按钮，可折叠） |
| `client/preview/SnowLineChart.java` | 参数页：雪线双曲线（温度/纬度对雪线影响，可折叠） |
| `client/preview/ScalePreview.java` | 参数页：尺度预览（垂直尺度柱对比 + 水平尺度采样密度，水平尺度滑块置于图右侧，可折叠） |
| `GeoGenesisConfig.java` | Forge COMMON 配置（地质过程参数：continent*/ocean spline 控制点/coast/seabed/province*/land process/world height，详见 `ARCHITECTURE.md` 配置表） |
| `worldgen/terrain/GeoGenesisTerrain.java` | 零 MC 依赖地形引擎门面（缓存 Cell + generateChunk 装配侵蚀/河流） |
| `worldgen/terrain/CellGenerator.java` | 统一连续场采样 + 实现 HeightProvider + 连续分类 |
| `worldgen/terrain/LandShape.java` | 省权重(softmax) + 陆地过程形态（替代旧 StructuralField） |
| `worldgen/terrain/HeightCurve.java` | 单条 cubic Hermite Spline：eFromC / heightFromE（非对称 e→Y） |
| `worldgen/river/RiverField.java` | 世界坐标粗格点河网 + 河谷多级刻蚀 + LongCache |
| `worldgen/erosion/ErosionSystem.java` | 多营力局部侵蚀编排（Thermal/Coastal/Glacial/Wind） |
| `worldgen/climate/Climate.java` | 温度/湿度数据载体（替代旧 BasicClimate/PreClimate） |

注册流程: `GeoGenesisMod` 构造器中用 `DeferredRegister<Codec<? extends ChunkGenerator>>`（注册到 `Registries.CHUNK_GENERATOR`）注册 `GeoGenesisGenerator.CODEC`，同理 `BIOME_SOURCE` 注册 `GeoGenesisBiomeSource.CODEC`，并 `register(bus)` 到 MOD 总线。

## 缓存与坐标（当前）

当前 `GeoGenesisGenerator` **不再使用 tile 边界缓存**（旧的 `ERODE_TILE_*` / `TILE_*` / `chunkHeightCache` 等常量已随重构移除）。地形计算全部委托给 `GeoGenesisTerrain`：

- `GeoGenesisTerrain` 内部 `TileCache`（256 tiles，30s TTL）缓存区域级 cell 网格，跨 chunk 共享，无 tile 边界断裂。
- `RiverField` 内部按**世界坐标**语义工作（粗格点 `GRID` 间距取真实 `eLand`），`LongCache` 按粗格 tile 缓存河网，跨 block 无缝无 border 断点。
- `fillFromNoise` 每 chunk 调用 `terrain.getChunkCells(cx,cz)` + `terrain.sampleHeight(wx,wz)`，高度/河流/湖泊/气候由引擎确定性产出。

## 当前工作焦点（2026-07-13）

- **地形整体重写为地质过程范式（2026-07-13，阶段 1–3 完成）**：单一连续场 `e(x,z)`，大陆性 `c∈[0,1]` 单一连续噪声，海陆仅条件切分；海岸 `landW = smoother(clamp((cBiased-threshold)/(coastWidth*1.5)))` 做 C0 连续过渡；海洋深度由 `HeightCurve.eFromC` 样条控制点决定。阶段 1（统一场地形管线）+ 阶段 2（RiverField 粗格点河网 + 河谷刻蚀）+ 阶段 3（多营力局部侵蚀 ErosionSystem）均已编码并接线，BUILD SUCCESSFUL，`runClient`/`runPreview` 目检待做。详见 `ARCHITECTURE.md` / `docs/01-架构设计/01-地形重建设计-terrain-rebuild.md`。

- **气候 → 群系已接游戏**：世界按纬度温度 × 大陆性湿度 × 海洋/山峰标记分布多种原版群系（海洋/海滩/沙漠/森林/针叶林/雪原/雪峰等），不再单一 plains。已由 `runClient` 实机目检 OK。
- **侵蚀系统已复活**：`worldgen/erosion/` 于 2026-07-13 重建为**局部算子框架**（`ErosionSystem` 编排 `Thermal`/`Coastal`/`Glacial`/`Wind`），已接入 `GeoGenesisTerrain.generateChunk`（侵蚀先于河流），无 flow-accumulation 的 border 断裂。旧粒子系统（2026-07-08 删除）的教训见下「侵蚀系统」段。
- **预览升级为 11 图层 + 数据驱动配色**（对齐 `PLAN.md` §2/§7 包结构）：
  - 两套预览（MC `PreviewDisplay` / Swing `TerrainPreview`）共用零依赖 `GeoPalette` 的 11 个地理标准图层：高程/温度/湿度/大陆性/地形起伏/水文场/纬度/气候带/群系/地形类型/海陆 + 水文叠加。
  - 配色数据驱动：`ColorMap` 色带 + `GeoPalette` 多内置调色板，`assets/geogenesis/colormap_preview/geogenesis.json` + `biome_colors.json` 资源与 `config/geogenesis/preview-overrides.json` 用户文件可覆盖（对齐 TFC 范式）。
  - 分类与配色解耦：`worldgen/climate/` 的 `BiomeClassifier`/`ClimateZone`/`Latitude` 仅分类、不持颜色；`BiomeMapper.pickKey` 委托 `BiomeClassifier`，游戏群系行为不变。
  - 交互：MC 屏数字键 1..9/0 切图层、`R` 水文、`[`/`]`（ConfigScreen）切图层、分辨率/高程色带/水文控件；Swing 数字键 1..9/0 + `[`/`]` 切图层、`R` 水文、`X` 分辨率、`C` 清空图例搜索。MC 拖拽经防抖（暂停 150ms 才重算）消除卡顿。
- **调音台面板（Mixer Panel）**：已实现并集成到 `GeoGenesisConfigScreen`。左侧面板分三页标签：
  - **地形页**：基础因素曲线图（双曲线范围图，显示各地形类型在 e 轴上的分布，控制点可拖拽）+ 14 个控制点滑块（可折叠）
  - **气候页**：三个条件因素（温度/湿度/大陆性）可折叠调音台面板，每个因素含分类色条（可拖拽边界）+ 影响程度滑块
  - **参数页**：噪声/尺度基础参数滑块 + 三个可折叠可视化组件（`WorldHeightBar` 世界高度柱状图含 maxY/山脊/海平面/世界底滑块与内嵌高度预设、`SnowLineChart` 雪线双曲线、`ScalePreview` 尺度预览）；含 tooltip 说明 + 单参数重置
- **参数页 UI 系列修复（2026-07-09）**：滑块拖动焦点修复（`ParamSlider` 重写 mouseClicked/Dragged/Released，点击即 setFocused）、重置按钮生效（reset 触发 onChange + 独立检测 isHoveringReset）、`FactorCategoryBar` 加重置按钮、标签页文字重影/柱状图映射错位/滚动 scissor 溢出/底部空白均已修复。详见 `DEV_REPORT.md` §7.8。
- **配置屏预览种子随机化（2026-07-09）**：`GeoGenesisConfigScreen` 每次打开随机生成种子（原硬编码 12345L），用户仍可手动改种子输入框。详见 `DEV_REPORT.md` §7.9。
- **包结构对齐 PLAN.md**：删除 `client/screen/`，预览/配置类迁入 `client/preview/`（PreviewColor/PreviewDisplay/ColorMap/GeoPalette/GeoGenesisColorReloadListener/TerrainPreview）与 `client/`（GeoGenesisConfigScreen/ParamSlider）；分类器迁入 `worldgen/climate/`；调音台类迁入 `client/preview/mixer/`。
- **地形类型重定义（12 类枚举）已完成编码**：新增 `TerrainClass` 枚举（OCEAN, DEEP_OCEAN, LAKE, RIVER, BEACH, PLAIN, HILLS, PLATEAU, MOUNTAINS, PEAK, BASIN, SNOW），`Cell` 加 `dominantTerrain`/`terrainType`，`HeightCurve.classify` 改主导地形类型+e，`BiomeClassifier` 改 `switch(terrainType)` + 高原×气候表，`GeoPalette` 重定义 12 类配色/名称/terrainTypeId。编译通过，isMountain/isPeak 全仓无残留。
  - **实际产出（2026-07-13 末）**：`CellGenerator.classify` 现产出 **BEACH**（`e>0 && e<0.03`，陆地侧薄环岸线）+ **SNOW**（`e>0.90 && wBelt>0.45`，最高雪峰核）；`LAKE` 暂不产生（待源湖填水 #1）。`GeoPalette.T_TERRAIN_TYPE` 已重排为 12 项严格对齐 `TerrainClass.ordinal()`（移除了 phantom SHALLOW_OCEAN/CONTINENTAL_SHELF，旧错位使 LAKE 起全错、SNOW 永不显色）。

## 地形引擎范式重建（2026-07-10）

用户否决"小修小补"，要求**依据地理学、融合 MC 特点的真实地形**。旧 `TerrainBlender` 用"区域网格 + hash 随机选地形 + 混合高度带鼓包"范式，数学上只能产出"不同振幅的平滑鼓包"（山是圆包、高原是圆丘、平原长小丘、山脉海岸骤升），且 `dominantTerrain` 用 `Math.round` 取最近格点导致单格椒盐。该范式已整体替换为**地质过程范式**：

- **`StructuralField`（地质背景场，新建）**：4 个低频 simplex 得克拉通/造山带/高原/盆地原始信号，softmax 软归一化得连续省权重（和为 1、无硬边界）→ 椒盐天然消失。`PreClimate` 仅做气候轻偏置（沿用旧 `climateInfluence` 思路，不决定类型）。
- **`TerrainBlender`（重写）**：对各省分量按地理过程生成形态后加权合成陆地形态坐标 eLand∈[0,1]：
  - 克拉通（平原/丘陵）= 低幅 FBM 滚动；平原 `plainBase` 近恒定、**去随机小丘**；丘陵 `hillsLow→hillsHigh` 温和起伏。
  - 造山带（山脉）= `Ridge` 脊线 × `Warp` 域扭曲成延伸山岭 × **proximity 包络**（边缘 foothill→核心成峰，解决骤升）。
  - 高原/方山 = `Terrace` 阶梯（平顶 + 崖阶，真正台地）。
  - 盆地 = 近海平面低填。
  - **河蚀刻谷**：`CellGenerator` 经 `HeightProvider` 注入真实 `eLand` 给 `RiverField`，`computeShape` 用多级刻蚀（**谷肩抬升 + 河床下切 U 形谷 + 谷壁侵蚀扰动**）把河床刻入谷底（河在谷中），并按源头分型雕 **源湖盆/山泉小潭**、按 `isWaterfall` 雕 **跌水潭**。
  - **连续形态分类**：`dominantTerrain` 改为从结果形态（e + 省权重）判定，群系沿等值线平滑过渡，彻底消除量化椒盐。
- **talus 坡积软化被移除**：单一高度场 `h(x,z)` 填实天然无悬空崖/可建造，无需邻居 5× 采样的 talus（避免无谓开销与伪需求）。
- **配置 schema 变更**：`TerrainParams`/`GeoGenesisConfig` 追加地质过程参数（`provinceScale`/`provCratonW`/`provBeltW`/`provPlateauW`/`provBasinW`/`beltRidgePower`/`beltFoothill`/`beltPeak`/`plateauBase`/`plateauTop`/`plateauSteps`/`plateauStepStrength`/`plainBase`/`plainRough`/`hillsLow`/`hillsHigh`/`basinBase` 与河蚀几何 `riverBedDepth`/`riverBankDepth`/`riverBedWidthFrac`/`riverErosion`/`sourceLakeDepth`/`springPoolDepth`/`plungeDepth`；`RiverSettings` 追加河网几何 `riverGridSize`/`riverMinWidth`/`riverMaxWidth`/`riverBedWidth`/`riverMinE`/`waterfallDrop`/`sourceLakeChance`/`sourceRadius`）。旧"地形类型 e 高度带"参数（`plainsMinE..mountainsMaxE`/`*Weight`/`regionScale`/`regionBlending`/`regionJitter`/`mountainMaskFrequency`/`riverIncise`）**已移除或保留为 @Deprecated 且引擎不再读取**，待调音台重绑后移除（见下）。
- **删除 `LandForms.java` / `TerrainType.java`**（角色被 StructuralField + 过程形态取代）；**删除 `BaseRiverGenerator.java`**（旧 per-tile BFS 河网，被 `RiverField` 粗格点下坡汇流取代）。
- `CellGenerator` 现自行创建 `RiverField` 并**实现 `HeightProvider`**（注入真实 `eLand` 与省权重）；`computeShape` 用 `sampleLand(x,z,out)` 一次算出 eLand+省权重并做多级河蚀 + 源/瀑盆雕琢；对外 `sample`/`dominantTerrain` 签名不变，`HeightCurve` 海洋/陆地映射与 `BiomeClassifier`/雨影/预览契约全部不变。
- **编译通过（BUILD SUCCESSFUL）**。`runClient`/`runPreview` 目检待做。

**待办（follow-up，不阻塞首版地形）**：调音台 `mixer` 的基础因素曲线仍绑定旧 `@Deprecated` 的 `*MinE/*MaxE` 配置字段（现为 inert，拖动不影响新引擎）。需把 mixer 重绑到新的地质过程参数（或改造成"省权重/山脉形态/高原形态"曲线）。`GeoGenesisConfigScreen` 参数页的 `regionScale` 等滑块同理待替换为过程参数滑块。

## 河流系统重写 + 河流生命史特征（2026-07-10）

把"地形与河流一体"落到实处：河流不再用旧 `Continent` 假高度 per-tile BFS，而是**由新地质过程地形 `eLand` 真实高度场驱动**（粗格点 `GRID` 下坡汇流 → 树枝状河网，天然顺地形排水入海，与海陆同源）。河床相对本地地形下切，刻蚀在 `CellGenerator.computeShape` 内、与海陆同一趟采样完成。

**关键设计**
- **`HeightProvider` 解耦**：`CellGenerator` 实现 `double landHeight(int,int)`（陆地 e，海洋→NaN，兼容保留）+ `double terrainE(int,int)`（**统一 e，含海洋海床负值**）+ `void provinceWeights(...)`；`RiverField` 不再依赖 `Continent`。河网路由改用 `terrainE`，使河流顺陆架海床连续汇入海洋（海陆一体）。
- **粗格点下坡汇流（含 jitter）**：每 ~`GRID`(默认 40) 块取一处陆地高度，对节点 A 找 4 邻居最低者连河 A→B（树枝状、无环）；`LongCache` 按粗格 tile 缓存，确定性、跨块无缝。**粗格点位置加 ±35% grid 抖动**（`jitterX`/`jitterZ` 确定性 hash），打散原始网格对齐伪影，河段不再严格水平/垂直。
- **流量累积门控 + `landUp` 真河门控**：按高度降序累加每节点上游汇入数 `flowCount`；刻蚀深度 × `flowGate(flowCount)`（flowMin=3, flowFull=12 平滑过渡），消除平坦区密集平行沟壑。**`computeLandUp` 自陆地节点沿 down 正向 BFS 标记「上游含陆地」节点**，仅这些参与流量累积与刻蚀，避免整片海床被误刻成峡谷（纯海洋 drainage 不显式为河）。
- **坡度 + density 门控**：`computeTile` 中只当 `aH - best > 0.03` 且 `hash01 < density * slope * 20` 时才连河，避免平坦地区每个格点都连河形成密集网格。
- **海陆一体河口（2026-07-13 末修复 #4）**：移除「河口裁剪」——河网路由用统一 e 场（含海洋海床），河流从陆地连续刻入陆架成水下河谷/`estuary`；`apply` 门控 `refE > riverMinE()`（`riverMinE` 默认 **-0.35=陆架下限**，非海陆开关），深海盆不刻；仅陆地河道（refE≥0）标 RIVER 群系，水下峡谷保持 OCEAN 群系、地形连续下切，无岸边阶梯断崖。
- **线段距离场**：`RiverSample` 含 `riverDistance`(0=河心,1=谷缘) / `valleyWidth` / `riverMask`(布尔) / `riverWetness`(连续蓝边) / `flowCount`(上游汇入数)。
- **多级刻蚀**（取代旧 `riverIncise` 一刀切）：谷肩抬升 + 河床下切（U 形谷）+ 谷壁侵蚀扰动（确定性 `detail` 噪声），**深度受流量门控调制**。

**河流该有的特征类型（v1 已实现）**
- **溪源(1) / 山泉(2)**：源头（局部最小）按省权重分型——造山带/高原占优且海拔较高 → 山泉（坡脚小潭），其余 → 溪源。由 `LakeGenerator` 随后填水成小水洼。
- **源湖(3)**：源头按概率 `sourceLakeChance` 附湖盆，`computeShape` 雕浅平湖盆（源湖盆），`LakeGenerator` 填水成**源头湖**；浅盆带溢出口时河自溢出口流出，使源湖**供水**而非死水。
- **瀑布**：相邻河段端点 `eLand` 落差 > `waterfallDrop`(默认 0.10 e) → 标记 `isWaterfall` 并在下游端雕**跌水潭**。MC 1.20.1 原版水无垂直下落机制，瀑布靠地形台阶让水自然一级级落；`isWaterfall` 主要作生物群系/装饰标记。
- **湖泊**：闭流洼地由 `LakeGenerator` 填至**溢出口高度**（内流盆填至盆缘），与河网在源头/河口相接。
- **河曲/急流**：河曲随河宽 + `Warp` emergent（v1）；急流（rapids）留作 follow-up。

**配置**：`TerrainParams` 河蚀参数 `riverBedDepth(0.08)/riverBankDepth(0.02)/riverBedWidthFrac/riverErosion/sourceLakeDepth/springPoolDepth/plungeDepth`；`RiverSettings` 河网几何 `riverGridSize(40)/riverMinWidth(2)/riverMaxWidth(10)/riverBedWidth/riverMinE/waterfallDrop/sourceLakeChance/sourceRadius`（运行期由 `GeoGenesisConfig`「River Network」段注入；独立预览暂用默认值）。详见 `DEV_REPORT.md` §7.10。

## 调试开关

`GeoGenesisGenerator` 无 tile 诊断开关（旧架构已移除）。预览相关开关见 `client/preview/PreviewDisplay`（真实进度 `renderProgress` / `done`）。

## 已清理的废弃代码

以下废弃文件已删除：`CellGrid`, `CellRiverSystem`, `FlowAccumulationSystem`, `GlobalRiverSystem`, `GridRiverSystem`, `ParticleRiverSystem`, `RegionHydrologyCache/Data/Solver`, `RiverBrushSystem`, `RiverPieces`, `RiverNode`, `SimpleHydrologyEngine`, `SimpleHydrologySystem`, `UnifiedRiverSystem`, `ValleyTracingRiverSystem`, `MaterialMapper`, `TerrainCache`, `TerrainCellNoise`, `HeightmapPreview`, `GeologySystem`, `BaseRiverGenerator`（旧 per-tile BFS 河网，2026-07-10 被粗格点下坡汇流取代，详见下节），以及整套侵蚀包 `ErosionEngine` / `ErosionSettings` / `ErosionWeights` / `SlopeCalculator` / `CoastlineHandler` / `types/*`（含 Hydraulic/Wind/Thermal/Glacial/params）。另：`LandForms.java` / `TerrainType.java`（旧"鼓包范式"的高度函数工厂与地形类型枚举，2026-07-10 被地质过程范式取代，详见上节）。

历史备份见项目根目录下的 `backups/`。

> 注：2026-07-13 新建同名 `ErosionSettings`（局部算子版 record）与 `ErosionAgent`/`Thermal`/`Coastal`/`Glacial`/`Wind`（局部算子），与上文旧粒子版无关，勿混淆。

## 侵蚀系统（2026-07-08 删除，2026-07-13 复活为局部算子）

> ⚠️ 本段仅描述 2026-07-08 的状态。**2026-07-13 侵蚀已复活为局部算子框架**（`ErosionSystem` 编排 `Thermal`/`Coastal`/`Glacial`/`Wind`，全部局部算子、无 border 断裂），已接入地形生成。详见 `ARCHITECTURE.md` 与 `DEV_REPORT.md` §9。

`worldgen/erosion/` 整包**曾于 2026-07-08 删除**——用户决定优先做气候→群系接线。侵蚀原本只接进预览窗口的 `cell.height`，**未进入游戏可见地形**（`fillFromNoise` 用 `terrain.sampleHeight`，不经侵蚀），删除无功能回归。

> 历史技术结论（避免重蹈覆辙）：所有 flow-accumulation 方案（MFD、D8、URS 粒子）**本质非局部**，流量依赖整个上游流域 → tile 边界断裂**不可根治**。若未来重做侵蚀，应优先评估**局部算子**方案（如粗网格 Laplacian delta + 全分辨率地形），而非 flow-accumulation。详细演进见 `DEV_REPORT.md` / `EROSION_HISTORY.md`（如仍存在）。

## 事件记录（2026-07-13）

- **误写代码事件**：本日 Agent 会话被要求「更新文档」，却误解为继续编写地形/河流/侵蚀代码，用户在**侵蚀编写阶段**中止；尝试回退未果、上下文丢失。核查 git 历史干净、代码全部驻留工作区（未提交）。**处置：保留代码、不回退**，本次会话改为完成文档更新（详见 `DEV_REPORT.md` §9.6）。
- **文档现状**：地形重写 / 气候→群系 / 侵蚀复活 / 预览升级 / 调音台 / 包结构对齐均已写入本文与 `ARCHITECTURE.md` / `docs/*`。**当前工作区已重新编译验证**（`gradlew compileJava --rerun-tasks` → BUILD SUCCESSFUL）；`runClient`/`runPreview` 目检待做。

## 测试

- 无单元测试文件（`src/test/` 为空）。`gameTestServer` run config 存在但未使用。
- 验证靠 `runClient` 目检世界 + `runPreview` 看地形分布。

## 用户规则

1. **确认机制**: 需求模糊/逻辑冲突/多种技术选型时先问再干；关键决策必须确认。
2. **知识沉淀**: 关键想法、技术改进、重要参考 → 整理为笔记，主动询问是否需要输出文档。每完成一个任务/对话做记录。
3. **先案后码**: 复杂功能先出技术方案，等回复确认再编码。简单具体指令直接执行。
4. **工程规范**: 模块化、单一职责、单函数 ≤80 行、公共逻辑抽离、跨模块依赖接口、做好文件管理。
5. **安全与 Git**: 生成项目必配 `.gitignore`（排除依赖/环境/IDE 配置）。禁止硬编码密钥，统一用环境变量。
