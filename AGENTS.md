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
| `worldgen/river/RiverNetwork.java` | ★ RTF 范式河网门面（2026-08-26）：region(512wu) plate 缓存 + 采样合并 3×3 邻 region（结构性无缝）；`sampleRiver` 取「雕刻后地面最低」段（RTF min）；`waterTable(cAt 9 点模糊)` 纯函数水面 → 汇口零落差、无局部锯齿 | 
| `worldgen/river/RTFRiverGenerator.java` | ★ 几何河网生成（RTF 移植）：root 沿随机角度走 e 场到海岸（`distanceToOcean`）→ 主河必到海；fork depth≤2 树状分叉 + 源点高于汇合点门控（100% 下坡）+ 下坡偏置；确定性（region 坐标+seed id） |
| `worldgen/river/River.java` | 几何线段（RTF 移植）：法向/bbox/投影/相交 |
| `worldgen/river/RiverWarp.java` | 确定性蜿蜒（种子化 simplex 噪声，端点淡出→段衔接连续） |
| `worldgen/river/Network.java` / `Rivermap.java` | 河网树 + region 容器 |
| `worldgen/river/RiverConfig.java` | RTF 式每级河参数 record（bedWidth/bedDepth/bankWidth/bankHeight/fade/valleySize + createFork） |
| `worldgen/river/ValleyRiverCarver.java` | ★ RTF Zone1-4 平滑河谷雕刻：床/岸阶/谷底/淡出四段连续函数 + 只下挖不抬升（根治垂直崖/断床/悬河）；湖/河口按 waterTable 展宽 |
| `worldgen/river/RiverCarver.java` | 雕刻薄门面：`carve(origHeight, rs, wx, wz)` 委托 ValleyRiverCarver；`CarvedColumn` 契约保留 |
| `worldgen/river/RiverSample.java` | 采样 record（zone 半径 + waterTable 水面 + t + type） |
| `worldgen/erosion/ErosionSystem.java` | 多营力局部侵蚀编排（Thermal/Coastal/Glacial/Wind） |
| `worldgen/hydrology/riverline/RiverLineNetwork.java` | ★ 物理正确河网门面（2026-08-28）：region 内 D8 汇流场派生河线 + Catmull-Rom 细分 + 锚点衔接邻 region + 水面单调反推 + width/depth 由汇流面积驱动；`sampleAll` 3×3 邻域采样 → 无 border 断裂；确定性（worldSeed+region 纯函数） |
| `worldgen/hydrology/riverline/RiverLineParams.java` | 河网参数 record（gridCell/accumThreshold/mountainScale/slopeDrop/heightBlendDist/valleyExp/meander…）；`routingE(e)` 山压低选线场 |
| `worldgen/hydrology/riverline/MidpointDisplacement.java` | 旧分形线（保留对照，生产路径已改 flowaccum 派生），含 `ElevationSampler`(terrainEQuick)/`Node`/`RiverOutlet`(OCEAN/LAKE) |
| `worldgen/hydrology/flowaccum/FlowField.java` | D8 流向+汇流累积（region 网格纯函数带 margin，O(n) 拓扑序）；选线场用 `mountainScale` 压低山 → 贴谷避峰 |
| `worldgen/hydrology/flowaccum/RiverTrace.java` | 累积超阈值→折线提取+平滑+汇入下游出口锚点（border-safe/lake-safe 掩码 + 回滚 + 防交叉） |
| `worldgen/hydrology/HydrologyBlockCarver.java` | 单块雕刻：邻近段 IDW 混合（fade²/dist²）surfaceY/width/depth → 河线交越平滑、无硬切；水面=min(单调水面,真实地形)；只下挖；灌水门控 |
| `worldgen/hydrology/HydrologyExperimentEngine.java` | 接线（双采样器：terrainEQuick 选线 + sampleWu 锚定水面）+ 灌水落块 |
| `worldgen/climate/Climate.java` | 温度/湿度数据载体（替代旧 BasicClimate/PreClimate） |

注册流程: `GeoGenesisMod` 构造器中用 `DeferredRegister<Codec<? extends ChunkGenerator>>`（注册到 `Registries.CHUNK_GENERATOR`）注册 `GeoGenesisGenerator.CODEC`，同理 `BIOME_SOURCE` 注册 `GeoGenesisBiomeSource.CODEC`，并 `register(bus)` 到 MOD 总线。

## 缓存与坐标（当前）

当前 `GeoGenesisGenerator` **不再使用 tile 边界缓存**（旧的 `ERODE_TILE_*` / `TILE_*` / `chunkHeightCache` 等常量已随重构移除）。地形计算全部委托给 `GeoGenesisTerrain`：

- `GeoGenesisTerrain` 内部 `TileCache`（256 tiles，30s TTL）缓存区域级 cell 网格，跨 chunk 共享，无 tile 边界断裂。
- `RiverNetwork.sampleRiver(wx,wz)` 按世界坐标纯函数采样，**按 REGION=512wu 分 plate 缓存（`RTFRiverGenerator.generateRivers` 确定性；`REGION_SIZE=512`）**；采样合并本 region + 8 邻 region 段集合 → 跨 region 结构性无缝。
- **游戏雕刻路径（RTF 范式，2026-08-26）**：`GeoGenesisTerrain.generateChunk` 内 `applyRiverValley` 把河谷雕刻**回写 `cell.height`**（Zone1-4 平滑谷，预览/后续采样/落块一致）；`fillFromNoise` 不再二次雕刻，仅按 `rs.waterSurfaceY()` 灌水判定（`groundY < waterTop − 0.5`，Streams `isStreamBed`）。
- `fillFromNoise` 每 chunk 调用 `terrain.getChunkCells(cx,cz)` + `terrain.sampleRiverAtBlock(wx,wz,cell.height)`，高度/河流/湖泊/气候由引擎确定性产出。

## 当前工作焦点（2026-08-29 河网小溪/宽深/分支，提交 72d74eb + 006d0a6）

- **★ 小溪生成 + 宽深沿程连续变化（72d74eb）**：用户反馈"河流都是大河、没有小溪、宽深无变化"。
  - **三个初始假设全被实测推翻**（新探针 `runRiverLineWidthProbe`）：宽度饱和仅 7.93%（非主因）；河长均值 226wu 远小于 regionSize 640wu（河未被 region 切断，"单元尺度对齐 PL-RGA 板块"方案取消）；防交叉仅 606 次比较/region（"河段空间索引"方案取消）。
  - **真因 1（宽度）**：宽度映射与成河门槛耦合——旧式 `t=(logA−logA0)/logRange` 的 A0 直接取 `riverAccumThreshold`，降门槛调密度会让全河宽度整体平移（实测 t 平移 +0.553）。
  - **真因 2（密度）**：追踪成功的河被 `riverAccumThreshold=2000`（≈3.5 格汇流）裁掉源头后不足 `minRiverNodes=3` 则 `continue` **静默丢弃，连 rolledBack 都不计**——45 候选源/region 只产出 1 条河。
  - **修复**：宽深改 Leopold-Maddock 下游水力几何幂律（`widthFromAccum`/`depthFromAccum`，bW=0.42/bD=0.40，幂律无中段饱和）；新增 `widthAreaRef`（=gridCell²）独立宽度原点与门槛解耦 + `maxDepthRatio` 宽深比护栏；移除死参数 `areaLogRange`。参数重标定：minWidth 3.0→1.75（全宽 3.5 block 小溪）、maxWidth 8→10（全宽 20 block）、minDepth 2.5→1.6（保小溪水面 3 列灌水）、maxDepth 7→8、riverAccumThreshold 2000→200、sourceSpacingCells 3→1、sourceMinE 0.20→0.12。
  - **效果**：半宽 1.75~10.0 连续铺满 10 档（旧 31% 挤最高档），head→tail 1.96→6.07，河数 26→85。
- **★ 分支层级修复（006d0a6）**：用户澄清"没有小溪"真义 = 树状水系缺高阶分支（分支的分支）。
  - **根因**：`build()` 发源循环中 `accepted.add(s)` 在 `traceRiver` **之前**执行，回滚的源仍占据 `sourceSpacingCells` 间距槽位，连带过滤掉周围全部候选——一次失败追踪杀死一片潜在支流。修复：仅成功成为河的源才占槽。
  - **新增 Strahler 式层级**：`RiverPolyline.level`（1=干流，n+1=汇入 n 级河）+ 构建期 `levelAt[]` 传递；探针输出层级直方图。
  - **效果**：河数 85→109，层级 **level1=55 / level2=44 / level3=10**（二级支流成型），joined 293→453。
  - **验收**：`runFlowAccumProbe` reachedOcean 48/48 (100%)、profile/gate/border violations 0（border 除外）、coldMs 1849 不升。
  - **已知遗留**：border.maxSurfaceDelta 1.209→1.839、border.violations 0→**2**（容差 1.5，发生率 1.6e-7）——分支增多后穿出 region 边界的河段（19.27%）暴露"跨 region 水面无继承"既有范式遗留，实机不可见，未引入跨 region 继承机制，status=REVIEW 与历史基线一致。
  - **待做**：用户 runClient 实机目检（小溪可见/有水、宽深渐变、分支的分支）；可选打磨：源头渐入（headwater taper）、宽度沿程单调化、蜿蜒振幅/波长挂钩河宽。

## 当前工作焦点（2026-08-29 旧格点水文清理）

- **★ 旧格点水文整体移除（2026-08-29）**：用户实机确认河系正常后，清理与当前生产路径（`GeoGenesisTerrain → HydrologyChunkEngine → HydrologyExperimentEngine + HydrologyBlockCarver`，河网由 `riverline/`+`flowaccum/` 的 D8 汇流场派生）无关的旧格点水文集群。
  - **删除 38 文件**：核心 8（`HydrologySimulator`/`HydrologyRiverAdapter`/`HydrologyCarver`/`RiverNetworkExtractor`/`HydrologyCarvedCell`/`HydrologyGrid`/`HydrologyContinuityAnalyzer`/`HydrologyResult`）+ 旧格点基础设施 7（`HydrologyAscii`/`HydrologyDiagnostics`/`DrainageResolver`/`FlowDirectionSolver`/`RunoffAccumulator`/`RiverWaterSolver`/`RiverProfileSolver`）+ 旧值类型 7（`HydrologyMetrics`/`RiverWaterProfile`/`RiverProfile`/`RiverCrossSection`/`HydrologyContinuityMetrics`/`RiverNetworkSummary`/`RiverSegment`）+ 旧探针 16（`HydrologyProbe`/`HydrologyContinuityProbe`/`HydrologyConfluenceProbe`/`HydrologyTerrainFitProbe`/`HydrologyLongitudinalProbe`/`HydrologyMultiSeedProbe`/`HydrologyWorstCaseProbe`/`HydrologyCrossSectionProbe`/`HydrologyMultiscaleFitProbe`/`HydrologyWaterDiagProbe`/`HydrologyRegionProbe`/`HydrologyAdapterProbe`/`HydrologyCarverProbe`/`RiverWaterProbe`/`RiverProfileProbe`/`HydrologyAcceptanceReport`）。
  - **移除 16 个失效 gradle 任务**（`build.gradle` 为 GBK 编码，用 `replace_in_file` 保留编码）；同步修正 `HydrologyExperimentEngine` 顶部注释（旧类已非"保留仅供诊断"，而是整体移除）。
  - **验证**：`gradlew compileJava` BUILD SUCCESSFUL（无悬空引用）；`runFlowAccumProbe`（profile.violations=0 / border.maxSurfaceDelta=1.05 / fillWater=247723，status=REVIEW 仅因 15/7.9M 边缘溢出门控，属既有现象）；`runHydrologyTerrainEntryProbe`（deterministic=true, status=PASS）；用户 `runClient` 实机正常。
  - **注**：`RiverSegment` 在 2026-08-26「清理」条中被记为已删，实则遗留至本日才随旧格点集群彻底移除；`riverline/MidpointDisplacement` 仍保留为对照，未动。

## 当前工作焦点（2026-08-26）

- ⚠️ **【已退役】河流 RTF 范式（2026-08-26）**：以下描述已被 **2026-08-28 的 `worldgen/hydrology` 物理正确河网范式整体取代**（D8 汇流场派生 + 邻近段 IDW 雕刻）；RTF 代码（`worldgen/river/*`）保留为回退路径不删。详见下方「当前工作焦点（2026-08-28）」。
  - **拓扑**（`RTFRiverGenerator`）：root 沿随机角度走 e 场到海岸 → **主河必到海**；fork depth≤2 树状分叉（起点钉父河中心线 → **支流必汇主河**）+ 源点高于汇合点门控 + 下坡偏置 → **100% 下坡、100% 树状汇流**。
  - **雕刻**（`ValleyRiverCarver`）：**Zone1 河床 / Zone2 岸阶 / Zone3 谷底 / Zone4 淡出** 四段连续函数，`finalHeight = min(carved, origHeight)` 只下挖 → **根治垂直崖/河床断裂/悬河/破坏性切地形**。
  - **水面**（`RiverNetwork.waterLevel`）：`waterTable = clamp(1 − cAt/COAST_C, 0, 1)` 纯位置函数 → **汇口零落差、无逐节点 jitter**；内陆略高于海、海岸贴海平面。
  - **回写**：`GeoGenesisTerrain.generateChunk` 内 `applyRiverValley` 把河谷写回 `cell.height`（预览/采样/落块一致）；`fillFromNoise` 只按水面灌水。
  - **验收**（`runRiverTopologyProbe`，双 seed 12345/777）：root 到海率 **100%**、fork 下坡率 **100%**、maxBedStep/maxWaterStep **<1.5**、bedJump>1.5 **=0**、dryRatio **0~3.5%**（仅浅支流源头）、冒烟 `runChunkBorderProbe` 通过。
  - **清理**：删除 D8 全套（`FlowField`/`FlowRiverBuilder`/`RiverGrid`/`RiverNode`/`GroundwaterField`/`LakeBuilder`/`ProfileSmoother`/`FractalParams`/`RiverSegment`/`RiverCarveParams`）与 16 个失效探针任务；`RiverSample`/`RiverCarver`/`RiverPlate` 重写；配置段改 RTF 参数（rootCount/bedWidth/bedDepth/bankWidth/bankHeight/valleySize/fade）。
  - **★ 整块漏雕（柱子）根治（2026-08-27）**：用户实测「河上出现一整块原地形柱子、两侧有河」。根因链：① `getChunkCells` 的 5×5 河网预热放在 `generateChunk`（含 `applyRiverValley`）**之后** → 首生成/远离河源 chunk 的 `sampleRiver` 在 carver 入缓存前调用，长河（可达 8000wu）carver 位于 chunk region 的 ±2~3，未预热 → 全列 NONE → 整块漏刻；② `prunePlates` 原在 `plateForRegion` 内、每次 `sampleRiver` 调 2304 次，迭代 `plates.values()` 前就把刚预热的 plate 驱逐 → 同理漏刻。修复：`getChunkCells` 预热**移到 `generateChunk` 前** + 半径提到 3（7×7 region，`PLATE_WARM_MAIN/TRIB=3`，RTF REGION=512wu 即 ±3×64=±192 chunk，覆盖跨区长河）；`prunePlates` 从 `plateForRegion` 移除、改 `sampleRiver` 遍历结束统一驱逐；`PLATE_CACHE_MAX` 512→4096 防长河多 plate 被挤。验收：用户 `runClient` 实测**无柱子**；诊断 `UNCARVED(real)`（收紧后 `riverCrossesChunk` 用点到河中心线真实距离 ~90wu 判断，取代原 550+120wu 粗 bbox）全部 `centerInChannel=false`（河岸外侧正常 chunk，非漏雕）→ 确证零真漏雕。
  - **已知遗留**：浅支流源头 1~2 块干槽（dryRatio≈3%，物理自然）；`Network.overlaps`/`RiverConfig.length` 等少量未用 API；`runClient` 实机目检（河口喇叭/谷壁平滑/无悬水）待做；**整块漏雕（柱子）已根治（2026-08-27）**。

- **★★ 河网范式定案：回归 D8 全追踪（2026-08-26 第三轮，已被第四轮 RTF 范式整体取代）**：用户质询「为什么每次都说按 DW 写、每次都不一样」→ 复盘承认根本错误：**DW 平原主河敢用纯几何线的前提是流体模拟自填水**（河道只挖槽）；本项目是预计算水面+灌水柱架构，几何河道不贴真实低地则水面悬空/位置荒谬——8-25 分形重写与 8-26 链式拓扑两度照搬不适合本架构的拓扑形式。8-16~24 的 D8 全追踪（实测贴谷 100%、树状汇流 63-72%）才是本架构唯一正确拓扑。本轮 = git HEAD 8-24 追踪核心全量移植 + 8-26 修复成果保留：
  - **FlowRiverBuilder 重写**：主河层（REGION 512wu 内 16wu 栅格 e 最高 top3 候选 → D8 到海；≥80wu 门槛；候选路径相遇即汇入本 region 已生成主河——D8 谷线汇聚使平行重合段水面互斥，实锤 water 167↔124）；支流层（32wu 栅格源头池 + 湿度径流门槛 + e 降序 + 间距 ≥48 + 上限 60 → D8 追踪，joinTarget 距离 ≤8wu+不上坡+只向下游汇入 → 树状水系）。追踪核心含全部血泪修复：绕行多尺度/漫流锁定/入海冲刺/停滞检测（60 步净位移 <60wu 才算打转）/网格锚定防振荡。
  - **水面语义回归旧版 PL-RGA**：`min(线性基线 surf0→surfN, 追踪 min 累积面)`——线性掩盖 D8 FAR 大步（一次跨 64wu）的 e 断崖（倾斜补偿方案实测失效 65 块），min 保证单调与贴谷下潜；再经 `ProfileSmoother.refine`（±2 平滑两遍 + 固定坡度上限端点保护夹逼——端点恒不动，入海口=海平面）。
  - **宽度语义保留**：主河 baseW×0.75→×1.2 smoothstep 单调增；支流 2.2→min(6, 主河×0.85)——用户「大河宽小溪窄沿下游只增不减」。
  - **blendConfluence 相容阈值 4→14**：同分水岭 top3 主河 surf0 因源头 e 差异天然差 10~15 块（heightFromE 样条陡峭段），±4 判互斥 → Voronoi 翻转断面（water 88↔101 实锤）；±14 覆盖源头高差组内软混合。
  - **清理**：删除 `FractalRiverGenerator.java`/`TributaryTracer.java`/`RiverTopologyProbe.java`；`FractalParams` 提取为独立 record（配置层兼容）；RiverNetwork 移除 fractals/tributaries 字段。
  - **验收**：dryRatio 4.33%/maxDryRun=3（历史最优）；纵剖面双 seed 大部分河段 maxDWater ≤3.5。已知遗留：①低源支流与高源主河路径重合被上坡门控阻止汇入 → 源头区个别断面（每 seed 1~2 处，均为细溪）；②支流纵剖面陡（山地急流本性）。

- **★ 河网拓扑重构：DW 链式主河 + D8 支流追踪（2026-08-26 第二轮，已被第三轮取代）**：用户实测「路线完全不合理 / 各生成各的看不到其他河流」→ 深读 DW 双轨制后重构几何链式主河 + TributaryTracer 支流追踪；主河仍为几何线 → 与支流真实谷地系统空间错开（汇入率仅 17%），第三轮整体取代。
  - **主河链（`FractalRiverGenerator` 重写）**：废弃边界锚点制（0~4 随机锚 + hash 序串联 = 路线折返乱走根源）。改为 DW 式「每 region 一个 hash 内点 + 一条出边连邻居同源点」→ 全网连通长链；链方向按两端 e 定向（低 e=下游）；**region 内点 = hash 5 候选取 e 最低**（贴谷选点，治「位置不合理」+ 提升支流汇入）；宽度起点 ×0.75→终点 ×1.2 smoothstep 单调增（用户语义：大河宽沿下游只增不减）。
  - **支流追踪（新 `TributaryTracer`）**：hash 候选源点过滤（高地 e≥0.10、距主河 ≥24wu）→ `FlowField` D8 下坡步进（≤240 步×4wu）→ 命中主河（≤半宽+10wu，末端吸附中心线）/入海/洼地终止；步数耗尽时距主河 ≤160wu 直线延伸兜底吸附。宽度 2.2→min(6, 主河×0.85) 单调增（小溪语义）；水面与主河同源统一场（汇口零落差），经 `ProfileSmoother` 同一管线。
  - **公共逻辑抽离**：水面剖面管线（全分辨率采样+湖钳制+±2 平滑两遍+坡度上限）提取为 `ProfileSmoother`，主河/支流共用；`clipToSegments/emitSegment` 泛化为裸折线数据，主支共用裁剪组装。
  - **验收**（新探针 `runRiverTopologyProbe`）：主河端点衔接 65.7%（其余=链头源头端，函数图结构正确）；支流 0.5 条/region、17% 直接汇入主河、其余入海/入湖（水文正确）；宽度单调违例 0；ContinuityProbe dryRatio 3.0%（历史最优）。已知遗留：支流纵剖面陡（山地急流本性，endNeed 主导）；`mainChainSegments` 每次重建列表（可缓存）；支流间互汇未实现（需跨 region 缓存一致性方案）。

- **★ 河流纵剖面断面根治（2026-08-26 第一轮）**：用户实测"又出现断面了"→ 深读 DW 11.1.2 反编译字节码（`参考/river/dynamicwaters-11.1.2/HydrologyManager.txt` getRiverCarve L3805-3964）后四根因修复：
  - **DW 本质还原**：getRiverCarve = 密度场负偏移 `(smoothstep(d/w)−1)×4×fade(y)` 纯位置函数；jitter 仅作用水平距离；fade 作用在密度采样空间 Y（防切山），**不是地表高度**；无预计算水面（流体后处理填水）。本项目保留"预计算水面+灌水柱"架构，对齐其本质=雕刻量必须是水面的平滑纯位置函数。
  - **根因1 水面锯齿**：节点水面用 4wu 低分辨率网格 `eAt` 插值 → 山地样条误差 10+ 块（实锤 maxWaterStep=12.56）。改 `FlowRiverBuilder` **region 级路径水面剖面缓存**：`rawE` 全分辨率采样 → 湖钳制（预载盆地列表，不触发懒建）→ ±2 节点对称平滑两遍 → 坡度上限夹逼；`clipToSegments` 仅索引切片，同一节点跨 tile 取值一致。
  - **根因2 截面公式两份矛盾**：`rsFor` 抛物线 `1−(d/w)²`+噪声 vs `carve` smoothstep 并存。统一为 `channelTarget`=min(地形, waterY−bedDepth·(1−smoothstep(dJ/w)))，bedDepth=min(carveMaxDepth,0.9w)；rsFor 与 carve 同式（单一真相）；heightFade（语义错位）与 BED_FILL_CAP 回填硬切换（两分支最大 6 块跳变）整体删除。
  - **根因3/4 选择翻转**：`blendConfluence` 旧版「最低水面组无条件霸占」丢弃不相容河心段 → 交叉区高低水面随机翻转（seed12345 实锤 109↔46 块跳）。改**组级稳定选择**：水面 ±4 聚类成层级组，terrainY 有效选最贴地形组（防悬河）/NaN 选最近组（稳定 Voronoi）。坡度上限按弧长归一 `max(0.06×spacing, |端点差|/(m−1))`——局部防锯齿 + 全局保端点落差可衔接（分形细分后节点间距仅 ~4.4wu，绝对上限会随细分漂移）。
  - **验收**（新探针 `runRiverProfileProbe [-PprobeArgs=seed]`，恢复已删 QuickProbe 核心指标）：双 seed 最长 3 河 maxDWater≤0.50 / maxDBed≤1.35 / >1.5 跳变=0（修复前 12.56/12.44/7~14 次）；ContinuityProbe dryRatio 3.6% 不回退。已知遗留：淹没段 groundY=min(orig,bed)=orig 透出自然 V 谷陡坡（水柱连续，属地形形态非断面）；交叉区 Voronoi 边界单次水面阶跃（根治需构建期交叉消解，未来工作）。

## 当前工作焦点（2026-08-24）

- **⚠️ 工作区有大量未提交改动（动手前先看 `git status`）**：2026-08-22~24 河流雕刻/参数化/水位统一场/河口改造尚未提交。
- **★ 河床连续性 + 入海修复（2026-08-24 第二轮，未提交）**：用户实测三问题（河底视角纵向断裂 / 该入海的河停在陆地 / 无入海口地形）→ 依据 RTF/DW/Streams 三参考深读结论修复：
  - **河床深度与局部地形解耦**（`RiverCarver`，RTF carveZone1Riverbed 铁律）：旧 depthFactor/悬崖保护吃 relH(每列 origHeight) → 河床继承地表噪声逐列起伏 = 河底断裂根因。改为「绝对深度 × 流量因子 + 高岸平滑帽」（relH 从 carveMaxCliff 起 24 块 smoothstep 渐降到 25%），深度只依赖统一水面。carveSlopeFactor 不再驱动增益（字段保留兼容配置）。
  - **滨海追踪不提前终止**（`trace()`）：60wu 停滞检查近海豁免；FAR_SCALES 近海改纯 argmin（内陆仍要求 −0.03 降深）；大尺度移动验收近海豁免。效果：主河最长 895→2411wu。
  - **入海口喇叭 + 不收尖**：`taperedWidth` 加 estuary 因子（e→0⁺ 渐宽至 3×，纯 e 场确定性）；sink==0 的主河远端不做端点收口。⚠️ 喇叭使节点宽达段基准 3×——`sampleRiver` 两处段预筛余量已从 9× 提到 **20×**（不同步会剔掉喇叭岸坡 → inChannel 缺口，实锤过）。
  - **支流汇口深度继承**（RTF createForkConfig 思想）：支流前 15% 弧长从父河当地实深（p.bedY−p.waterSurfaceY）渐变到自身深度，消除汇口跨段床面台阶。
  - 参考结论备忘：RTF=几何线段网+二分钉海岸+waterTable 阶梯+地形抬升同函数+4 zone 谷底压平；DW=主河几何弦线+山地河地形追踪（阶梯阶地+瀑布≤4）、无河口逻辑海洋列跳过；Streams 新版=干流域恒西延+边界通海泄漏检测收口 Mouth、贝塞尔纵剖面+出口横断面复制接缝。
- **★ 跨区断河根因修复：plate 自适应收集（2026-08-24 第三轮）**：旧 `buildPlate` 固定半径（支流 3×3/主河 5×5 region）下，长段远端节点落在收集半径外 → 该 plate 无此段 → sampleRiver NONE → 河道中段凭空消失（probe miss 连续 405 块历史实锤；滨海支流加入后 miss 3371 列）。改为「主河 ±6 / 支流 ±4 region + 段 bbox∩tile 过滤」（半径由最大迹长推导：600 步×4wu、400 步×4wu），内容=seed 纯函数保持确定性。**分层控成本**：外环只生成主河骨架（支流够不到远处 plate）；无差别 ±6 实测构建 154s→21 分钟不可接受，分层后单 tile 冷构建 ~210s。**预热对齐**：`warmRegionsAround(mainR,tribR)` 两半径重载，`GeoGenesisTerrain` 两处调用改 (6,4)，冷成本移入后台守护线程。**验证**：新探针 `RiverQuickProbe`（`gradlew runRiverQuickProbe [-PprobeArgs="seed tx tz"]`，~4min 冷）node continuity **17881/17881=100%**；全量 FlowRiverProbe 3.6 节已加 miss 海陆归因+坐标 dump。
- **已知遗留（待实机目检决策）**：①长河纵向剖面仍有离散跳变（QuickProbe profile：maxBedStep≈12 块、bedJumps>3blk 7-14 次/河）——疑似穿湖盆进出（湖水面=溢出口 vs 河道统一场）与汇口融合翻转，若目检难看，候选修复=湖岸 shoreT 渐变湖面而非细胞集硬边界。②`t.surf[]` 追踪记录已无人消费待清理。
- **★ 性能崩塌→定案（2026-08-24 第五轮，用户实测"远比之前差"+质询"确定性为何不等价于便宜"）**：根因链=滨海爬行拉长段→可见性需大半径→生成足迹爆炸（±6/±4 时冷 plate 210s、足迹 ~10×）。**教训：段长决定可见性半径决定生成成本，迹长是第一杠杆；参考项目（RTF/DW/Streams）又确定又便宜的本质是结构区域有界**。定案五件套：①迹长封顶 主河 `maxSteps 600→200`（≤800wu）/ 支流 `TRACE_TRIB_MAX 400→160`（≤640wu）；②收集半径 **主河 ±3 / 支流 ±2**（分层保留）；③**父河池 7×7→3×3**——父河池每扩一环主河有效半径跟扩一环（实测 STAT_MAIN_REGIONS 169→81→49）；④**节点构造期湖查询整体移除**（`lks[]` 钳制删除）：sampleRiver 湖优先已接管盆内水面语义，逐点 lakeAt 懒建占冷构建 68%（QuickProbe 分段计时实锤 98s/144.5s），删后归零；⑤预热对齐 (3,2)。**验收：单 tile 冷构建 210s→20.8s、continuity 100%、lakeAt 0ms**。支流密度随段短而降（tile 内 120→27 条），属预期。诊断工具：QuickProbe 分段计时 + 纵剖面 ASCII 三线图（'.'地形 '~'水面 '*'床）+ 贴谷度量。**第三步基线**：贴谷 fit 78.6%/86.4%（下一步追踪加谷线偏好项）；穿湖剖面跳变 ~12 块待 shoreT 渐变；discharge 驱动尺寸分级待汇流后处理设计。
- **★ 方案 A：水位统一场（2026-08-24 第一轮）**：节点水面废除「追踪 min 单调 + surf0→surfN 线性插值 + 穿湖特判」三套来源，统一为纯位置函数 `fieldWaterY(x,z)=heightFromE(max(0,e))`（`FlowRiverBuilder`，主/支流共用）。同点同值 ⇒ 汇口零落差；湖面 = heightFromE(溢出口) 同源 ⇒ 湖口零落差；e→0⁺ 自动落海平面。**路径走向不变（连续方向 360° 追踪保留，D8 仅存于湖盆 BFS 与平坦逃生搜索）**；湖内仍钳 `max(surf, waterY)`（lks[] 数组在节点构造期一次查询，禁止放追踪步内——步内查湖触发 tile 懒建风暴是性能回归根因）。`t.surf[]` 追踪记录已无人消费（留待清理）。
- **河流雕刻/支流参数化重构（2026-08-22~23，未提交）**：新增 `RiverCarveParams` record 收敛散落硬编码——支流分叉锚点间隔/RTF 式夹角窗口、DW MountainRiverPath 上坡容忍、最小支流长度、蛇曲幅度、谷深/谷宽系数、局部地形增益、深上限、悬崖保护、宽度锥形+沿程噪声；13 个参数入 `GeoGenesisConfig`「River Network」段（由 `RiverNetwork` 构造注入），独立预览无 Forge 配置走 `defaults()`。游戏雕刻改经 `terrain.carveRiver`；水体判定收紧为 `groundY < waterTop−0.5`。涉及 `FlowRiverBuilder`(±810 行)/`RiverNetwork`/`RiverCarver` 大改。
- **湖优先采样 + 形态跟随真实洼地（2026-08-23）**：`sampleRiver` 开头湖优先短路（`LakeHit`=Basin+岸距 t）；湖形态 = D8 洼地 BFS 细胞集 + 多源 BFS shoreT smoothstep 剖面（圆盘半径判定已废除）；`RiverCarver` LAKE 分支置于低洼保持检查之前（深洼列干坑根因）；`LakeBuilder` 负坐标解包符号 bug 修复（`&0xFFFFFFFFL` 必须转回 int）。

## 当前工作焦点（2026-08-28）

- **★★★ 河流重构为物理正确范式（D8 汇流场派生，取代 RTF 几何线）**：河网不再由几何/分形线"画"出再贴地形，而是从地形汇流场（D8 流向 + 汇流累积）"流"出来。拓扑/纵剖面/水面/宽度/深度/雕刻全部同源于同一地形场。对照参考 `参考/river/plate-local-river-generation-main`（Davis 2026, PL-RGA）完善细节：
  - **（1）选线场山压低（mountainScale=0.5）**：`FlowField` 选线改用 `routingE(e)`（e 高于中段按 `mountainScale` 压低），使河线在"压低地形"上走（贴谷、避峰），水面仍锚定真实地形（`groundYAt`）（PL-RGA `firstHeightField` / `BASE_TERRAIN_MOUNTAIN_SCALE`）。低地不变，仅压低山脊。
  - **（2）邻近段 IDW 混合雕刻**：`HydrologyBlockCarver.carveColumn` 取 `dist ≤ heightBlendDist` 的全部命中，按 `(1−fade)²/dist²` 反距离平方混合 surfaceY/width/depth（PL-RGA `riverHeightField`），根治"单属主硬切"在河线交越处的接缝；blendDist 仅 ~valley 量级，远处河线不入场 → 不复发 DW 式跨线劫持。湖面同样走 IDW（连续湖-河过渡）。
  - **未采用项**：参考的上游"次低邻居兜底"（避免整条 rollback）本次未落地——本项目内流盆地即湖的物理语义已正确，且兜底易引入上坡河，权衡后保留整条回滚。
  - **验收**（`runFlowAccumProbe` 双 seed 12345/777，均 PASS）：topo.reachedOcean 88.9%/90.9%（其余入内流湖，物理正确）、cycles=0、profile.violations=0 / maxRise=0、overflow.gateViolations=0 / maxWaterDepth≈5.7~6.0 / deepAbnormal=0、border.violations=0（maxSurfaceDelta≤0.31）、rollbackRate≈1.0%、lakes 17/11 regions；冷构建 ~1.8s/region。

- **河流系统汇水分析驱动重构（2026-08-16，阶段 A–D 完成）**：废弃几何折线范式（R12–R22 的 `RiverBuilder2` 已标 @Deprecated），整体切换为**源头驱动 D8 追踪 + 树状汇入**：
  - `FlowField`（D8 流向场：4wu 局部算子、tile 缓存、确定性，无 border 断裂）
  - `FlowRiverBuilder`（主河 = 每 REGION 640wu 最高 3 候选 ≥80wu 门槛；支流 = REGION 源头池 + 径流门槛；汇入窗口 18wu 成树；入海/洼地终止）
  - `LakeBuilder`（D8 洼地中心 BFS 盆地 + 溢出口封闭判定 + 湖盆雕刻）
  - `GroundwaterField`（地下水位场：泉眼判定 + 暗河下潜段，喀斯特"河消失又出现"）
  - **2026-08-23 plate 边界断裂修复**：`RiverNetwork.sampleRiver` 采样时合并本 plate + 8 邻 plate 段集合（原只查本 plate → 跨 plate 边界河段不被命中 → 沿 128wu 网格线出现整河深垂直崖）；对齐 Streams/SimpleHydrology 的跨块无缝思路。用户实测通过。
  - 水量平衡（湿度场 → 源头径流门槛 0.08~0.25 → 干带河稀湿带河密，实测 10% vs 30%）
  - 探针验证：主河 avg 165-196wu、贴谷 100%、单调 96-97%、湖 1-4、暗河 8-9%；BUILD SUCCESSFUL；`runPreview` 稳定 45s。详见 `DEV_REPORT.md` §10。
  - **用户实测三轮修复（同日 §10.5）**：join 38-46% → **63-72%**（水面 Y 语义修正 + 高度条件 + 40wu 窗口）；sink 27-47% → **7-8%**（多尺度/远尺度绕行 + 纯几何停滞检测 + 网格锚定振荡根治）；100% 段有落差（avg 49 块）；主河 avg 493-576wu。
  - **待办（阶段 E）**：Strahler 分级河宽；湖泊群系映射；瀑布跌水潭视觉验证；`runClient` 实机目检。

- **地形整体重写为地质过程范式（2026-07-13，阶段 1–3 完成）**：单一连续场 `e(x,z)`，大陆性 `c∈[0,1]` 单一连续噪声，海陆仅条件切分；海岸 `landW = smoother(clamp((cBiased-threshold)/(coastWidth*1.5)))` 做 C0 连续过渡；海洋深度由 `HeightCurve.eFromC` 样条控制点决定。阶段 1（统一场地形管线）+ 阶段 2（RiverField 粗格点河网 + 河谷刻蚀）+ 阶段 3（多营力局部侵蚀 ErosionSystem）均已编码并接线，BUILD SUCCESSFUL。详见 `ARCHITECTURE.md` / `docs/01-架构设计/01-地形重建设计-terrain-rebuild.md`。

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

> ⚠️ **本段为历史记录**：所述旧 `RiverField` 粗格点下坡汇流方案已废弃删除，现行河流系统是 `RiverNetwork`/`FlowRiverBuilder`/`RiverCarver`（见「架构速览」与「当前工作焦点」）。仅特征类型语义（溪源/山泉/源湖/瀑布/湖泊）仍沿用。

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

## 调试开关与探针铁律

`GeoGenesisGenerator` 无 tile 诊断开关（旧架构已移除）。预览相关开关见 `client/preview/PreviewDisplay`（真实进度 `renderProgress` / `done`）。

探针 = `worldgen/**/*Probe.java` 独立 main 诊断工具（约 34 个，不启 MC）。改侵蚀/河流后必须跑对应探针验证，且：

- **探针进程无 Forge 环境，配置恒为默认值**；用户在配置屏调过参数时，探针须解析 `run/config/geogenesis-common.toml` 才能复现游戏行为。
- **邻居 tile 连带生成极慢**：用单 tile 直取的探针专用方法（如 `CellGenerator.getErosionTileResultForProbe`），勿走会 fire-and-forget 生成 8 邻居的常规入口。
- Windows 下含中文的提交信息用 `git commit -F file.txt`（UTF-8），避免命令行转码问题。

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
