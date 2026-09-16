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
| `GeoGenesisGenerator.java` | 主生成器，`fillFromNoise` 是地形产线入口；`ensureEngine` 注入共享地形到 BiomeSource（⚠ 旧述"`createState` 注入"**有误** —— `createState` 未被覆写）；★ `applyCarvers` 调 `CaveCarver` 雕洞穴；★ 构造器注入 `VanillaDecorationFilter::filter`（剔除破坏岩层的原版装饰）；★ `spawnOriginalMobs` 委托原版 `NaturalSpawner` |
| `worldgen/generator/VanillaDecorationFilter.java` | ★ 2026-09-16：注入给 `ChunkGenerator` 的**过滤版 `generationSettingsGetter`** —— 剔除 9 个会打散 `StratumField` 水平岩层的原版"岩块团块"特征（`ore_granite/diorite/andesite/tuff/dirt/gravel` 的 upper/lower），**保留金属矿与水成细节**（零平衡风险）。⚠ 1.20.1 的 `BiomeGenerationSettings` 构造器非 public ⇒ 走 `PlainBuilder` 子类 |
| `worldgen/cave/CaveShape.java` | ★ 2026-09-15：洞穴**几何**（**零 MC 依赖纯函数**，可被探针直接复用）；2D 场驱动柱体切挖 + 岩性门控 |
| `worldgen/cave/CaveCarver.java` | ★ 2026-09-15：洞穴雕刻的 **MC 适配器**（只负责把方块挖成空气），几何全部委托 `CaveShape` |
| `worldgen/ore/OreVeins.java` | ★ 2026-09-15：矿脉**纯函数**（**零 MC 依赖**）：成矿带 2D 门控 + 宿主岩/深度带 + 3D 等值面脉体；矿种按岩性成矿 |
| ~~`worldgen/geode/GeodeShape.java`~~ | ⚠️ **已删除（2026-09-16）**：**原版 `amethyst_geode` 早就在生成**（`LOCAL_MODIFICATIONS` 槽位；1.20.1 datapack `jungle.json` 的 `features[2]` 证实）⇒ 自研属**重复实现**，且使晶洞密度翻倍（≈1/24+1/25）。改用原版（自带 95% 裂纹/晶芽/分层）。若要恢复"岩性门控"，正解是"原版 `GeodeFeature` + 自定义 placement"，而非重写形状 |
| `GeoGenesisBiomeSource.java` | BiomeSource，按 Cell 气候选原版群系 |
| ~~`worldgen/generator/BiomeMapper.java`~~ | ⚠️ 已删除（2026-07-13）：群系映射合并入 `BiomeClassifier.pickKey`，不再有独立文件 |
| `worldgen/climate/BiomeClassifier.java` | 零依赖群系分类（`classify(Cell)→BiomeClass` 枚举，无颜色）；★ T12 起 `soilVariant` 做「岩性→群系」变体 |
| `worldgen/climate/SoilInfluence.java` | ★ 2026-09-14 T12：成土母质（岩性→土壤性质）+ 噪声门控；**零 MC 依赖纯函数**（可在无引导探针中测试） |
| `worldgen/climate/ClimateZone.java` | 零依赖 Köppen 简版气候带（A/B/C/D/E） |
| `worldgen/climate/Latitude.java` | 零依赖纬度带 `latitude01(worldZ)` |
| `client/preview/ColorMap.java` | 零依赖连续色带（Lab 插值 + bake LUT），不 import MC |
| `client/preview/GeoPalette.java` | 零依赖配色中枢：`PreviewLayer` 注册表 + 多内置色带 + 离散映射 + 覆盖接口 + 图例条目 |
| `client/preview/PreviewColor.java` | MC 侧着色外观，委托 `GeoPalette` 输出各图层 ABGR |
| `client/preview/PreviewDisplay.java` | 游戏内预览控件（15 图层 + 图例 + 分辨率/色带 + 水文 + 拖拽防抖） |
| `client/preview/TerrainPreview.java` | 独立 Swing 预览窗口（15 图层 + 图例搜索 + 分辨率 + 水文） |
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
| `worldgen/terrain/TerrainCharacterField.java` | 类型场：**规则网格** Voronoi 高斯距离权重（400wu 格、σ=200、7×7 窗口、`WARP_AMP=0`）→ 类型主导边界偏轴对齐（见「已知遗留」） |
| `worldgen/terrain/TypeNoiseProvider.java` | 逐类型地形噪声配方（PLAIN/HILLS/MOUNTAINS/PLATEAU/BASIN）；★ 已撤销 `\|2n−1\|` 折叠（见「折叠类算子的禁令」） |
| `worldgen/terrain/LandFeatures.java` (+`VolcanicShape`) | 陆地火山特征（单体 800wu 格 3% + 火山群 200wu 格 12% × 低频掩码）；`VolcanicShape` 提供 cone/guyot 形状与火口数学 |
| ~~`worldgen/terrain/LandShape.java`~~ | ⚠️ **不存在（2026-09-13 核查）**：本行曾写「省权重(softmax) + 陆地过程形态」，实际无此文件；类型权重由 `TerrainCharacterField` 提供 |
| `worldgen/terrain/HeightCurve.java` | 单条 cubic Hermite Spline：eFromC / heightFromE（非对称 e→Y） |
| ~~`worldgen/river/*`（RTF 全套）~~ | ❌ **已删除**（2026-08-28 被 D8 汇流场范式取代）；**现行河流见下方 `worldgen/hydrology/*` 行** | 
| `worldgen/erosion/ErosionEngine.java` | ✅ **唯一真实存在的侵蚀**：液滴水力侵蚀（SimpleHydrology 型，物质守恒）+ `cascadeLocal` 塌落（高差阈值≈固定 38.7° 安息角） |
| `worldgen/erosion/RidgeValleyErosion.java` | ✅ 脊-谷条纹骨架滤镜 |
| ❌ **不存在（2026-09-11 核查）** | `ErosionSystem` / `ErosionAgent` / `Thermal` / `Coastal` / `Glacial` / `Wind` —— **多营力侵蚀从未实现**；`worldgen/river/*` RTF 全套已删（2026-08-28） |
| `worldgen/hydrology/riverline/RiverLineNetwork.java` | ★ 物理正确河网门面（2026-08-28）：region 内 D8 汇流场派生河线 + Catmull-Rom 细分 + 锚点衔接邻 region + 水面单调反推 + width/depth 由汇流面积驱动；`sampleAll` 3×3 邻域采样 → 无 border 断裂；确定性（worldSeed+region 纯函数） |
| `worldgen/hydrology/riverline/RiverLineParams.java` | 河网参数 record（gridCell/accumThreshold/mountainScale/slopeDrop/heightBlendDist/valleyExp/meander…）；`routingE(e)` 山压低选线场 |
| `worldgen/hydrology/riverline/MidpointDisplacement.java` | 旧分形线（保留对照，生产路径已改 flowaccum 派生），含 `ElevationSampler`(terrainEQuick)/`Node`/`RiverOutlet`(OCEAN/LAKE) |
| `worldgen/hydrology/flowaccum/FlowField.java` | D8 流向+汇流累积（region 网格纯函数带 margin，O(n) 拓扑序）；选线场用 `mountainScale` 压低山 → 贴谷避峰 |
| `worldgen/hydrology/flowaccum/RiverTrace.java` | 累积超阈值→折线提取+平滑+汇入下游出口锚点（border-safe/lake-safe 掩码 + 回滚 + 防交叉） |
| `worldgen/hydrology/HydrologyBlockCarver.java` | 单块雕刻：邻近段 IDW 混合（fade²/dist²）surfaceY/width/depth → 河线交越平滑、无硬切；水面=min(单调水面,真实地形)；只下挖；灌水门控 |
| `worldgen/hydrology/HydrologyExperimentEngine.java` | 接线（双采样器：terrainEQuick 选线 + sampleWu 锚定水面）+ 灌水落块 |
| `worldgen/climate/Climate.java` | 温度/湿度数据载体（替代旧 BasicClimate/PreClimate） |
| `worldgen/terrain/TectonicField.java` | 构造骨架（Phase T1/T2）：哈希 Voronoi 板块 + 域扭曲 + **连续加权投票应力**（`stressField`，★ 第三条硬约束）+ 山链调制 chain + `blurDist` smoothstep 渐隐 |
| `worldgen/terrain/TectonicDeformation.java` | 构造形变（Phase T5）：褶皱（世界坐标 ridged 噪声 + **圆化绝对值**）+ 断层（噪声**零等值线** + 单条平滑 sigmoid 落差，**已去值域量化**） |
| `worldgen/terrain/StratumField.java` | 地层/岩性（Phase T4，纯数据层，不参与 e 合成） |

> ### ⛔ 地质系统硬约束（违反会导致「平行带」伪影，已回归三次）
>
> **`dist`（到板块边界的距离）只能用于 `decay` / 高斯衰减（控制作用范围），绝不能作噪声坐标。**
>
> **原因**：`dist` 的等值线**平行于 Voronoi 边界**，且**绕板块闭合成同心环**。
> 任何以 `dist` 为噪声坐标的量（如旧 `chainModulation` 的 `dist/111`、旧 `foldOffset` 的 `sin(dist·2π/λ)`）
> 都会产生**平行于边界的波纹带** —— 与项目早已否决的 Terrace「环状台阶伪影」同源。
>
> **历史（修过又被改回，务必先读）**：
> - `087698c` 修过（"改为沿走向波：褶皱 sin(dist)→sin(along)"）
> - `8e51a08` 为修"每块独立生成"**改回 `sin(dist)`** + chain 跨走向项 `dist/111` → 波纹回归
> - `60e182f` 平滑 `stress` → 由"断续疤痕"变"连续规整波纹" → **视觉上更明显**
> - `（本轮）` 两处均改为**纯世界坐标噪声**，`dist` 不再参与
>
> **守门探针**：`gradlew runTectonicWaveProbe` —— 判据为「法向/切向各向异性比值」
> （平行带 ≫1，各向同性 ≈1），补上阈值型探针（Grain/LandE/Deform）的原理盲区。
> 修复前 T5=2.20~2.51（FAIL）、修复后 ≈1.1（PASS），已双向验证。
>
> **教训**：新增地质参数时，若发现形变量与 `dist` 相关，先问"这会不会形成平行带"。
>
> ### ⛔ 第二条硬约束：**禁止对噪声做值域量化**（`Math.floor/round` on noise）
>
> **原因**：量化把连续噪声切成阶梯，而**阶梯的等值线**是一族**嵌套闭合波浪曲线**
> → 在地形上就是"密集波浪状平行细线 / 同心环梯田"。这与本项目**两次否决 Terrace**
> 的「环状台阶伪影」**完全同源**。
>
> **实例**：`TectonicDeformation.faultOffsetUnit` 曾用
> `slipQ = Math.floor(blockN * 3.0) / 3.0`（3 档量化）→ 用户第四次反馈的直接根因
> （实测 `comp_deform.png` 可见同心环）。现改为**跨越断层线的单条平滑 sigmoid**
> （取噪声零等值线为断层线 + smoothstep 过渡）→ 保留断块落差，但处处 C¹。
>
> 同理，`1-|x|` 这类 **V 型折痕**（斜率跳变）也会沿零等值线形成锐利细脊，
> 须用**圆化绝对值** `sqrt(x²+r²)−r` 代替（见 `TectonicDeformation.foldOffset`）。
> ⚠️ 但**圆化只对"本就该保留的脊线"有效**；若脊谷结构本身是伪影来源，
> 圆化无效，必须**去掉折叠**（见下方「折叠类算子的禁令」）。
>
> ### ⛔ 第三条硬约束：**禁止用「最近邻配对」定义参与地形合成的场**
>
> **原因**：把标量场定义为「最近 + 次近 Voronoi 种子的配对量」（如
> `stress = (v1−v2)·n / |v1−v2|`）时，在**配对不变**的空间区域内该量与位置无关
> ⇒ 场是**分片常数**；配对在 Voronoi 边与 order-2 边上切换 ⇒ 沿**直线网**阶跃
> ⇒ 地形上就是「**笔直长线段 + Y 形交汇**」（实测 `gradmag.png` 与
> `gmagC_tect_chain.png` 几何完全同构，连线的密度与交汇点位置都一致）。
>
> **铁证（可复现）**：分片常数场的探针特征是 **`P50|∇| ≈ 0`（精确为 0）**。
> 实测 `TectonicField.stress` 的 `P50|∇| = 7.9e-17` → 当场锁定根因。
> 同理，`tect_dist` 的 `max|∇| = 37.7`（距离场物理上不可能超 ~2.4）也是同一类荒谬值报警。
>
> **为何事后平滑无效**：环形/局部平均**只能压制、不能拓扑消除**
> （2026-09-12 的注释已写下这句，但当时没换架构，只加大 `smoothStress` 半径/样本 → 伪影存活）。
> **正解 = 连续加权投票**（`TectonicField.stressField`）：
> `stress = Σ w_i·(v_i·û_i) / Σ w_i`，`w_i = exp(−d_i²/2σ²)`，`û_i` = 种子 i 指向采样点的单位向量。
> 无「配对」概念 ⇒ 无配对切换 ⇒ **处处 C^∞**；顺带删掉 12 点环形采样（**净性能收益**）。
>
> **要点**：投票窗口半径**必须 ≥ 2 格**（半径 1 时最近被排除种子的权重达 0.43
> → 窗口平移又生一条直线网）。
>
> ### ⛔ 第四条硬约束：**任何在 `reach` 处硬切换的模糊/混合，必须 smoothstep 渐隐**
>
> **原因**：`if (v >= REACH) return v; else return blurred(v);` 这类写法两分支**数值不等**
> （模糊值可偏离原值上百单位）⇒ 沿 **`v = REACH` 的等值线**产生**阶跃**。
> 而 `dist` 的等值线是**平行于 Voronoi 边的多边形偏移网**（直边 + 顶点）
> ⇒ 又一套「笔直细线 + Y 交汇」被印进地形。
>
> **实例**：`TectonicField.blurDist` 的 `DIST_BLUR_REACH=420` 硬切换 → `tect_dist`
> `max|∇| = 37.7`。修正（`t` 用 smoothstep 在 reach 处渐隐到 0）后 `37.7 → 1.26`、
> 长尾比 `17.9 → 1.6`。参照 `TectonicField.smoothStress`（当年已写对）——**本条是它的推广**。
>
> ### ⛔ 第五条硬约束：**原版装饰会把「岩块团块」塞进本项目的水平岩层 ⇒ 必须过滤**
>
> **原因**：`GeoGenesisGenerator` 直接继承原版 `ChunkGenerator`，其
> `applyBiomeDecoration` 委托 `super`（原版实现）⇒ **原版会把群系
> `BiomeGenerationSettings` 里的全部特征放下来**，其中包括 `UNDERGROUND_ORES` 步的
> **9 个"岩块团块"**特征（`ore_granite / ore_diorite / ore_andesite / ore_tuff /
> ore_dirt / ore_gravel` 的 upper/lower）。其替换目标是 `STONE_ORE_REPLACEABLES`
> （含 stone/granite/andesite）⇒ **正好命中 `StratumField` 的水平岩层** ⇒ 岩层被打散。
> ⚠ `ore_tuff` 尤其误导：**本项目片岩(SCHIST) 即映射为 TUFF** ⇒ 团块会被误认成片岩层。
>
> **正解**：`VanillaDecorationFilter`（构造器注入过滤版 `generationSettingsGetter`，
> 见 `super(biomeSource, VanillaDecorationFilter::filter)`）已剔除这 9 项 ——
> 原版该 getter 同时驱动 `featuresPerStep` 与 `applyBiomeDecoration` ⇒
> **过滤后原版管线自动跳过，无需重写装饰循环**。
> **★ 新增群系 / 改 `BiomeClassifier` 时，必须复核该过滤器仍覆盖新用到群系的团块特征。**
>
> **实测取证**（2026-09-16，临时 `[DECOR-AUDIT]` 日志，验证后已删）：
> `biome=minecraft:jungle steps=11 features=47 oreFeatures=24`，`[6] UNDERGROUND_ORES = 28`
> ⇒ 原版矿**确实与自研 `OreVeins` 叠加生成**（自研量约为原版 1/10）。
> 过滤后 `features=38 oreFeatures=15`（剔除 9 项），世界正常生成。
>
> **⚠ 同步更正一条长期错误论证**：`OreVeins` 曾写"没有 `NoiseSettings` ⇒ 原版 `ore_*`
> 用不了"—— **不成立**（装饰放置不依赖 `NoiseSettings`）。真正的自研理由是
> **按宿主岩 + 深度带成矿**（原版 `OreConfiguration` 无法表达自定义岩性）。
> **待决**：金属矿是否改为自研独占 ⇒ 属平衡决策，若采纳需重新标定自研总量。
>
> ### ⛔ 折叠类算子的禁令：`|2n−1|` 禁用于地形噪声
>
> **原因**：绝对值折叠有三重几何副作用，叠加即「密集波浪状平行细线」：
> ① **频率翻倍**（一个原周期拆成两脊两沟 → 等值线数量翻倍）
> ② **梯度恒定**（正弦在极值处 `d/dx→0`；折叠后处处满梯度 → 本该稀疏的缓坡也铺满等高线）
> ③ **折痕处等值线成对平行**（`n=0.5` 的 V 形折痕两侧等值线成对出现）
>
> **实例**：`TypeNoiseProvider.foldHills`（HILLS/PLATEAU 共用）。逐配方高通幅值实测：
> `PLAIN 0.0025 / MOUNTAINS 0.120 / BASIN 0.056 / HILLS 0.425 / PLATEAU 0.464`
> —— 用折叠的两个类型异常 **170~185×**，且**折叠是它们的唯一共同点**。
> **圆化折痕无效**（只消二阶不连续，脊谷交替与频率翻倍原样保留，实测高通仍 0.42）。
> **正解 = 去掉折叠**（`clampUnit` 恒等钳值域），地貌语义交给噪声自身的多频叠加。
>
> **可视化守门工具（★ 关键方法论）**：`gradlew runTerrainStripePngProbe -PprobeArgs="seed size step ox oz"`
> → `build/stripe/` 输出 `hillshade.png`（人眼所见）、`eLand_highpass.png`（去趋势，弱纹理显形）、
> `comp_deform.png`（构造分量）、`type_*.png` / `typeHP_*.png`（逐配方 + 高通）。
> - ★ 2026-09-13 新增 **[F] 逐分量梯度排查**：对 eLand 的每个输入分量单独求 `|∇|`，
>   打印 `P99.9/P50` **长尾比**（平滑场 ~3~8；含折痕线的场极大），并输出 `gmagC_<分量>.png`。
>   **这是定位「折痕属于哪个场」的最快路径**，一次运行即可锁定，不必逐个假设。
> - **⚠️ 看图陷阱（本轮差点误判）**：`|∇|` 图上，**平滑脊的两翼天然呈亮线**
>   （`|∇g|` 的极值在 `d=σ` 处）。故"图上有线"**不等于**"有折痕"；
>   判据须配合长尾比 + **线宽**（折痕线 1~2px；平滑脊翼宽约 35px）。
>
> **⚠️ 探针不够用**：`runTectonicWaveProbe` 等**阈值型**探针全部 PASS 时，用户仍可能看到明显伪影——
> 因为它们测"幅度/各向异性"，测不出"值域量化/配对阶跃"。**判定伪影的最终依据只能是渲染图**。
>
> **⚠️ 改动地形产出必须升 `PreviewDisplay.CACHE_SCHEMA_VERSION`**，否则预览**静默复用旧磁盘缓存**
> → 表现为"改了没生效"（当前 **69**；历次因折叠/blurDist/stress、岩性硬度量化、河网并行、构造放大、盆地抬升、地质→群系耦合、T5 移除 decay、峡谷谷壁收窄等产出变更递增）。

注册流程: `GeoGenesisMod` 构造器中用 `DeferredRegister<Codec<? extends ChunkGenerator>>`（注册到 `Registries.CHUNK_GENERATOR`）注册 `GeoGenesisGenerator.CODEC`，同理 `BIOME_SOURCE` 注册 `GeoGenesisBiomeSource.CODEC`，并 `register(bus)` 到 MOD 总线。

## ★ 原版复用边界（2026-09-16 审计 —— 动笔写新世界生成内容前先读）

> **本项目已四次重复实现原版已有之物**（岩块团块 / 金属矿 / 紫水晶洞 / 化石）。
> 完整对照审计（含证据与可复现命令）见 `docs/analysis/原版复用对照审计-2026-09-16.md`。
> 本节是**决策速查**，目的是避免第五次。

**判据（决定性）**：`applyBiomeDecoration` 委托 `super`（原版实现）
⇒ **只要某特征在该群系的 `BiomeGenerationSettings` 里，它就一定在生成**。
查证方法：读原版 datapack `data/minecraft/worldgen/biome/<biome>.json` 的 `features[]`
（11 步，按 `GenerationStep.Decoration` 索引）。
⚠ 本地 client jar 路径与"多版本缓存认版本"的坑，见审计文档 §0。

### 已确认「原版免费提供」——不要再自研
- 植被：树 / 草 / 花 / 藤蔓 / 甘蔗 / 西瓜 / 南瓜 / 发光地衣
- 洞穴装饰：钟乳石 / 石笋 / 洞穴藤蔓 / 发光浆果 / 苔藓（我们只负责**选洞穴群系**）
- 岩浆湖、水/岩浆泉、`disk_sand/clay/gravel`、`underwater_magma`
- 地牢 `monster_room(_deep)`、沙漠水井 `desert_well`
- **★ 原版结构（村庄 / 要塞 / 废弃矿井 / 古城 / 传送门遗迹…）**：
  `createStructures` / `createReferences` / `createState` 在 1.20.1 **均非抽象**
  ⇒ 我们继承了默认实现，**结构一直在生成**（此前文档从未提及）
- **紫水晶洞 `amethyst_geode`**（`LOCAL_MODIFICATIONS` 槽，`rarity 1/24`）
  与 **化石 `fossil_*`**（`desert` / `swamp` / `mangrove_swamp`）
- `freeze_top_layer`（按群系温度铺雪 / 水面结冰）—— 与本项目**海拔雪线互补**，二者并存

### 已确认「必须自研」——原版无对应物或技术上不可用
- 地形本体：`ContinentField` / `CellGenerator` / `TerrainCharacterField` / `TypeNoiseProvider` /
  `HeightCurve` / `SeaBedDetail`
- 地质系统：`StratumField`（地层）/ `TectonicField` + `TectonicDeformation`（构造）/
  `LandFeatures` + `VolcanicShape`（火山）
- 侵蚀 `ErosionEngine` / `RidgeValleyErosion` · 水文 `hydrology/*`
- 洞穴几何 `CaveShape` / `CaveCarver`（原版 carver 需 `NoiseChunk`，我们无 `NoiseSettings`）
  · 洞穴群系 `CaveBiomeSelector`（原版 3D 路由，我们 2D）
- 气候分类 `climate/*` · 噪声原语 `noise/*`（**有意的重复**：为"零 MC 依赖"，
  让探针/预览能脱离 MC 运行）· 预览 UI

### 复用机制（怎么减 / 怎么加 / 怎么接）
- **减**：`VanillaDecorationFilter` —— 构造器注入**过滤版** `generationSettingsGetter`，
  原版管线自动跳过，**无需重写装饰循环**
- **加**：同一注入点也能**追加**原版特征（例：把 `amethyst_geode` 引入某群系）
- **接**：原版留空/默认处委托原版 API（例：`spawnOriginalMobs`
  → `NaturalSpawner.spawnMobsForChunkGeneration`；但 `applyCarvers` **接不了**）

### 未决（勿擅自改）
- **金属矿**：现为「原版打底 + 自研 `OreVeins` 叠加（约原版 1/10）」；
  改自研独占需**重标定矿量** ⇒ 属平衡决策
- **雪 / 冰**：若要"我们的雪线"独占，把 `freeze_top_layer` 加入过滤器剔除集即可（一行）

> **用户约束（2026-09-16）**：**不新建任何方块/物品** ⇒ 一切方案只用原版内容或删自研代码。

## 缓存与坐标（当前）

当前 `GeoGenesisGenerator` **不再使用 tile 边界缓存**（旧的 `ERODE_TILE_*` / `TILE_*` / `chunkHeightCache` 等常量已随重构移除）。地形计算全部委托给 `GeoGenesisTerrain`：

- **缓存（⚠️ 2026-09-11 校正）**：`GeoGenesisTerrain` 按 chunk 网格缓存 `Cell[]`，容量 **4096、真 LRU**；`CellGenerator` 另有侵蚀 tile 缓存，容量 **256、真 LRU**。两者均含 `CacheStats` 埋点（`chunkCacheStats()` / `tileCacheStats()`）。
  原述「`TileCache`（256 tiles，30s TTL）」**失实** —— 那是参考项目 FreeTerraForged 的设计；本项目无此类、无 TTL（内容为 (seed, 配置, 坐标) 纯函数，永不失效，TTL 只会驱逐**有效**条目）。
- **非阻塞高度查询（2026-09-11 新增，B1 修复）**：`GeoGenesisTerrain.sampleHeightNonBlocking(wx,wz)` 两级降级（① 已生成 chunk 取缓存精确值；② 未生成则廉价重算）→ `getBaseHeight` / `getBaseColumn` 用它，**绝不触发冷侵蚀 tile**（原实现单次可达 ~2.2s）。
- **快速路径收敛（2026-09-11 新增，B2 修复）**：`sampleCellLight` 会并入**已缓存**的侵蚀增量（`CellGenerator.applyCachedTileDelta`）→ 已探索区域与完整管线收敛；tile 未生成时静默跳过，冷启动仍零生成。
- **河流**：`RiverLineNetwork.sampleAll` 按 **region(640wu) + margin** 纯函数缓存，合并 3×3 邻 region → 跨 region 结构性无缝；水面为 PAVA 加权单调反推。（RTF `sampleRiver` / `REGION=512` 已作废）
- **游戏雕刻路径**：`GeoGenesisTerrain.generateChunk` 内 `extractFromTile`（侵蚀 delta）+ `applyHydrologyValley`（水文雕刻，**回写 `cell.height`**，预览/落块一致）；`fillFromNoise` 只按 `waterSurfaceY` 灌水判定。
- `fillFromNoise` 每 chunk 调用 `terrain.getChunkCells(cx,cz)`，高度/河流/湖泊/气候由引擎确定性产出。

## 当前工作焦点（2026-09-15 ★★★★ 洞穴按原版 MC 配方重设计 —— 结构缺失无法用调参弥补）

- **用户三次实测不合格**（竖直柱 → 薄饼 → "还是不行"），最终**转向原版配方**。
- **★★★ 读懂了原版配方的结构（此前一直没读懂）**：原版洞穴 = 三层配合：
  1. **空腔 cave_cheese**：`0.27 + cheeseNoise + layer + depth < 0` 挖空
     ⇒ **提供"能走的大空间"**（这是"玩家能在洞里走"的主要来源）；
  2. **层调制 cave_layer**：`4 × noise(CAVE_LAYER, yScale=8)²` 恒≥0 的项加到密度
     ⇒ 在特定 Y 层把密度推回实心 ⇒ **把大空间切成一层层有限高度的空腔**
     ⇒ **这才是防竖直贯穿的关键**；
  3. **隧道 spaghetti/noodle**：连续密度 `abs()+cube()` ⇒ 圆润管道，负责连接。
- **★★★ 前两轮失败的真正根因（事后看清）**：**从未实现层调制**。
  第一版空腔变竖井（长段 91.2%），我误判为"必须放弃单噪声阈值"改用细管道；
  结果第二版只留下管道、丢了大空间 ⇒ "没法走"。
  **结构缺失无法用调参弥补** —— 缺一层就该补那一层，而不是换公式。
- **新实现（`CaveShape`）**：
  - 空腔：连续密度 `density = CHAMBER_T + chamberNoise + LAYER_W×layerNoise²`，`<0` 挖空；
  - 层调制：`LAYER` 噪声（`LAYER_Y_SCALE=3.0`），平方后乘 `LAYER_W` 加回密度；
  - 隧道：保留双噪声等值面交集（已验证）；
  - 空腔仅埋深 ≥14 格产生。
- **参数网格扫描标定**（新增 `dbgSetChamber(chamberMul, layerMul)`）：
  选定 `CHAMBER_T=0.432 / LAYER_W=1.925`：
  无层调制 13.94%/10.1块/长段35.6% → 选定 **5.07~5.48% / 4.1块 / 1.2~3.6%**（3种子一致）。
- **图像验证**：垂直切片 = **多个横向延展空腔**（高3~6格、宽10~25格，层间实心隔开）
  —— 原版 cave_layer 标志形态；水平切片 = **有机空腔斑块**。性能 2.56ms/chunk（更快）。
- **诚实说明**：原版的 `SLOPED_CHEESE`（与地形密度场耦合）与 `Noises.*` 精确参数
  （Perlin firstOctave/amplitudes）**未复刻** —— 用本项目 3D Simplex + 深度窗口近似。
  因此与原版洞穴**形似而非完全等价**；若仍有差异，优先核对这两处。

## 当前工作焦点（2026-09-15 ★★★ 洞穴"薄饼化"二次修复 —— 教训升级为铁律）

- **用户二次实测**："洞穴基本都是扁平的，连高度 2 格都没有，玩家没法走。"
- **根因（自我检讨）**：为压低"长竖段占比"代理指标，把 Y 各向异性加到 **7.0**
  ⇒ 隧道被压成**薄饼**（竖向半径 ≈0.46 格）。**这是上一轮刚写进文档的教训
  （"指标好看≠形态正确"）的当场重演**。
- **判据缺陷（第三次修正）**：判据只设了"平均竖向段 ≤10"的**上限**、没设**下限**
  ⇒ 优化器（我）沿着"越短越好"一路走到极端。**只设单边界的判据必然走向极端**。
- **修正**：
  - 判据改为**可通行区间 [3,12] 格**（太短不能走，太长是竖井）；
  - 扫描范围补上弱各向异性区（yMul∈[0.25,1.5]，effective 0.5~3.0）
    —— 上一轮只扫 yMul≥2.5 ⇒ **整个扫描空间都是薄饼**，从未覆盖圆管区；
  - 新参数 **yScale=1.2 / 阈值×0.56**：竖向段 **4.1~5.8 格**（可通行）、
    密度 1.21~1.82%、长段 1.5~14.1%、3 种子 ALL PASS。
- **图像验证**：垂直切片 = **横向短条带**（高 5-7 格、水平延伸 20+ 格 = 管道截面）；
  水平切片 = **短促蜿蜒弧线**（水平隧道被水平面切开的正确拓扑）。3D 连通 80~87%。
- **★★★ 教训升级为铁律**：
  1. **代理指标必须与真实目标对齐** —— 洞穴的目标是"玩家能走进去的管道"，
     不是任何可计算标量；
  2. **判据必须同时约束上下界** —— 只设单边界的优化必然走向极端；
  3. **扫描空间必须覆盖"正确解可能所在"的区域** —— 上轮扫描空间里根本没有圆管解，
     再怎么扫也只能选出"最不坏的薄饼"。

## 当前工作焦点（2026-09-15 ★★ 洞穴重写：2D柱体切挖 → 3D噪声等值面）

- **★★★ 用户实测推翻了"探针 ALL PASS"**："洞穴非常奇怪，完全不成洞穴的样子" —— 用户是对的。
- **根因（几何性）**：初版移植 TF 的"2D 场驱动竖直柱体切挖"（每列挖 `[bottom,top]`），
  空洞本质是**竖直柱** ⇒ 水平截面是孤立点/小团 ⇒ **无论怎么调参都不可能像隧道**。
- **★ 诊断盲区（本项目两次同错，必须记住）**：初版探针只渲染 **X-Y 垂直剖面** ——
  柱体在 X-Y 上是竖直白条，相邻列拼起来看着像"斑块" ⇒ **图看着还行、判据全 PASS**。
  补 **X-Z 水平切片**后立刻暴露。**判定 3D 结构必须同时看两个正交方向的切片**。
- **新实现**：
  - `noise/Simplex3`（经典 Gustavson 3D，约70行）+ `Noise3` 接口 + `Seed.gradIndex3`。
    不移植 RTG OpenSimplex：其 3D 依赖 2048 项 `LOOKUP_3D` 预计算表、552 行、不可读不可验。
  - `CaveShape` 逐体素判定，**统一判据 = 双噪声等值面交集 `|n1|<t1 && |n2|<t2`**。
    **数学依据：两张曲面相交 = 一条曲线 = 隧道**。三族（隧道/洞室/孔洞）同原理，仅尺度/阈值不同。
  - `CaveCarver` 逐体素遍历地下带；短路（`|n1|≥t1` 直接返回）⇒ 平均 1~1.3 次噪声/体素。
- **★ 第二根因（分量分解定位）**：洞室原用**单噪声阈值**（`n>0.72`）—— 数学上**必然**
  产生贯穿世界的大块（等值面一侧 = 无限体积）。分量分解实测：**隧道健康**（竖向段6.7块/
  长段7.2%），**洞室才是罪魁**（29.7块/91.2%）。⇒ 洞室也改双噪声交集。
  **教训：多分量并集无法定位问题 ⇒ 新增 `CaveShape.components()` 返回位掩码**。
- **★ 参数必须网格扫描**：形态对 yScale/阈值敏感且**跨种子不稳**（yScale=1 时 seed=7
  长段39.9%，yScale=2 时 seed=12345 反升56.9%）—— 单点试参陷入"修好这个坏那个"。
  新增 `CaveShapeProbe` **scan 模式**（3 种子取最差）⇒ 选定 **yScale=7.0 / t×0.7**：
  长段 **63.1%→7.4%**、平均竖向段 **11.5→2.7 块**、密度 2.63~3.45%（3种子全PASS）。
- **Y 各向异性**：y 采样频率提高 ⇒ 管道趋向**水平延伸**（vanilla `cave_layer` 的
  `y_scale=8` 同理）。这是"隧道像隧道"的关键旋钮。
- **验证（图像+指标双确认）**：水平切片呈**蜿蜒有分支的有机曲线**；垂直切片呈
  **水平短条带**（此前竖直长柱）。性能：`isCave` 128ns、几何段 3.15ms/chunk（~0.3%预算）、
  写块均值 2057/chunk。守门全 PASS。
- **★★ 方法学教训（两次同一类错）**：**"指标好看"≠"形态正确"** ——
  ① 纵横比 14.45 被少数巨团拉高（假阳性）；② 全局包围盒恒≈1（分量连通成网是正常的，
  该指标无判别力，假阴性）。判据必须用**不可被连通性污染的局部量**
  （如"管道穿过一列留下的竖向段长"），且**必须与渲染图互相印证**。
- **诊断基建**：`CaveShapeProbe` 支持 `scan` 模式（参数网格 + 3种子最差）；
  `CaveShape.dbgSet/dbgReset`（volatile 诊断覆盖，生产恒为默认值，不改热路径签名）。

## 当前工作焦点（2026-09-15 洞穴性能补测 + 地表边界碎屑化修复）

- **★★ 洞穴性能此前【完全未测】**（用户质问，属实）。缺口根源：所有性能探针
  （`AbChunkProbe`/`ChunkTimeProbe`/`SpawnSearchProbe`）**全走 `getChunkCells`**，
  而洞穴在 `applyCarvers` ⇒ 该路径**无任何探针覆盖**。新增 `runCavePerfProbe`。
- **实测**：`span` 63ns / 几何段 32μs/chunk（可忽略）/ 写块上界 均值 1650、最大 6782 块/chunk
  （与原版洞穴同量级）。
- **★ 真风险（已修）**：`applyCarvers` 里 `getChunkCells` 在 **miss 时会主动生成**侵蚀 tile
  —— 实测 **冷取 avg 24.7ms / max 594ms**，热取仅 **0.6μs**（差数万倍）。
  初版"此处必命中"是**未经验证的假设**。
  → 新增 `GeoGenesisTerrain.peekChunk`（**只 peek 不生成**），`applyCarvers` 改用它；
  未就绪→**跳过洞穴**（不触发 600ms 冷生成）+ `caveSkippedCount()` 埋点使其可观测。
  **原则**：下游只读已就绪数据，绝不反向触发昂贵上游生成（同 `getBaseHeight` P0-1 止血、
  `sampleHeightNonBlocking`）。
- **★★ 地表方块过渡不自然（已修）**：根因是地表阈值链（`steepened>0.40` 裸岩 /
  `>0.30 且汇流高` 碎石坡）的抖动用了 **逐格 `hash01`** —— 空间相关性为**零** ⇒ 阈值附近
  **逐块翻转** ⇒ 边界呈"盐和胡椒"碎屑。实测敏感区孤立单格率 **5.75%（hash）vs 0.20%（noise）**，
  **降 29 倍**；裸岩占比几乎不变（36.20%→35.98%）。
  → 新增 `steepJitter()`（`Simplex` scale=6wu）取代；`hash01` **保留**给碎石选材
  （那里要的正是逐块独立）。
  **教训**：同一项目里 `variantTerrain` 用噪声抖动、坡度却用 hash —— **两套工具，一套用错**。
- **诊断集局限（重要，实测所得）**：`build.gradle` 的 diagnostics `compileClasspath` 原先
  **不含 MC jar** ⇒ 与 MC 耦合的逻辑无法测。现已补 `sourceSets.main.compileClasspath`，
  **但仍不够**：`Bootstrap.bootStrap()` 会连带 `NetworkHooks.init()` 引导 Forge 网络而失败；
  `BuiltInRegistries.bootStrap()` 又要求已 bootstrap（循环）。
  ⇒ **依赖 MC 注册表的探针仍不可行**（如 `BiomeClassifier.surfaceOf` 依赖 `Biomes`）。
  自建 registry 桩会引入"桩≠真"漂移，违背项目铁律 ⇒ 不做。
- **未覆盖（如实记录）**：群系**本身**的过渡（`pickKey` 是离散硬切换、无权重混合、
  气候区区内恒定）**尚未处理** —— 本次只修了用户点名的"地表方块"边界。
  这是一个更大的工程（需要引入权重混合或扩大 `ClimateRegion.blend` 混合带）。
- **生产/探针一致性**：`SurfaceBlockProbe` 镜像判定链，但**无法逐位自检**
  （生产类静态初始化依赖 MC registry，诊断进程加载会失败）⇒ 靠**参数人工核对**
  （已核对：0.06 / scale 6.0 / Simplex(0x6D3FA281) 全一致）。**此为已知验证缺口**。

## 当前工作焦点（2026-09-15 洞穴设置界面）

- **配置屏新增「洞穴」页签**（`CavePanel`，插在「地形」之后 ⇒ `TAB_NAMES` 与 `panels`
  **顺序必须严格对齐**，现各 10 项）。核查过**无外部硬编码页签索引**
  （唯一硬编码是 `tab == 3` 指地形，插入点在 4 ⇒ 未被移动）。
- **档位一键切换**：`关闭 / 精简 / 拟真 / 接近原版`。
  ⚠ **刻意不含 `自定义`**：它若做成按钮，点击会按 `fromPreset(CUSTOM)` 把旋钮
  **重置回基准值** ⇒ 用户丢失刚调的参数（推演出的 UX 陷阱）。改为**状态提示**。
- **★ 改动即时生效（本功能的关键，别漏）**：原先 `CaveShape.setConfig` **只在
  `setWorldSeed`** 调用 ⇒ 界面改完世界仍用旧配置 ⇒ **UI 看起来无效**。
  现补热刷新：`CaveShape.markConfigDirty()` → 下次挖洞前（`CaveCarver` **chunk 级**入口）
  `ensureConfigFresh()` 重新解析注入 ⇒ 新生成区块立即生效。
  **语义**：只影响**新生成**区块（世界生成的固有性质）。
- **验收**：`runCaveConfigProbe` **判据7** = 未脏 0 次调用 / 脏后恰好 1 次 / 幂等；
  三种子全 ALL PASS（共 8 判据）。
- **守门**：`runCaveShapeProbe`·`runCaveBiomeProbe`·`runCavePerfProbe`·`runOreVeinProbe`
  全 ALL PASS；水文两项 `status=PASS`；`runClimateBiomeProbe` 非法邻接 0。
- **★ UI 布局的坑（已处理）**：档位摘要文案 40+ 汉字 ≈ 360px **超过面板宽度**
  （主屏有 scissor 不会崩，但会**硬切在半个字上**）⇒ 加了 `clip()` 按像素宽度截断加"…"。
  内容高约 397px，各小节无重叠。
- **未做（如实记录）**：**实机目视确认待做**（本项目一贯如此，`runClient` 目检属遗留）·
  预设页/重置按钮未联动洞穴配置（进屏回读一次，显示不会停在旧值）。

## 当前工作焦点（2026-09-15 洞穴可开关配置）

- **用户要求**：洞穴**可开关配置**，"想拟真就开、不需要就关"，**游戏性优先**
  （不必完全按地质学）。
- **档位**：`REALISTIC`（拟真，默认）· `VANILLA_LIKE`（接近原版：洞大更圆、
  **无层理**、**不看岩性**）· `MINIMAL`（只留细隧道）· `OFF`（关闭）· `CUSTOM`。
- **⚠ "关闭回原版"的技术现实（必须讲清）**：本项目自定义 `ChunkGenerator`、
  **无 `NoiseSettings`/`NoiseChunk`** ⇒ 原版 carver **物理上无法调用**。
  故 `OFF` = <b>"地下无洞穴"</b>（**同 RTG 的 `useCaves=false`**）。
  调研确认：**三个参考项目都没有**"自研洞穴关闭 ⇒ 回退原版 carver"的双实现
  （TF 完全替换原版且废弃了 `CarverUtil`；FreeTF 靠 `probability=0` 隐式关单类；
  RTG 的 `useCaves=false` 也只是"不生成"）。
- **配置项**（`Caves` 组，11 项）：`caveEnabled` · `cavePreset` ·
  `caveTunnelEnabled`/`caveChamberEnabled`/`caveLayerEnabled` · `caveDensityMul` ·
  `caveSurfaceLid`（**0 ⇒ 允许破地表成入口**）· `caveDepthMin`/`caveDepthMax` ·
  `caveLithoGating` · `caveBiomesEnabled`。
- **★ 实现方式（关键）**：把 `CaveShape` 既有的 `dbg*Mul` **诊断倍率机制升格为
  配置注入点** ⇒ **探针零改动**、默认档位行为**逐位不变**
  （`runCaveShapeProbe`/`runCavePerfProbe` 仍 ALL PASS）。
- **★ 单一配置来源**：`GeoGenesisBiomeSource` 原自带 `caveBiomesEnabled` 字段
  ⇒ 改读 `CaveShape.config()`，消除两套开关不一致。
- **验收（`runCaveConfigProbe`，7 判据全 ALL PASS）**：`OFF` 体素 **0** ·
  总开关正交 · 档位密度 MINIMAL(**385**) < REALISTIC(**36890**) <
  VANILLA_LIKE(**41162**) · 岩性门控 REALISTIC **18.77×** vs VANILLA_LIKE **相等** ·
  层调制竖直段 **6.31 < 8.25** · 分量开关恒 0 · 性能 **0.0584 us/次**。
- **未做（如实记录）**：**配置界面未加**（核心"能开关"已达成；TOML 可直接编辑、
  Forge 标准界面可见；本项目配置界面是自定义绘制 UI，加面板风险高）·
  `F_CHEESE` 分量与 `CAVERN_Y_SCALE`/`CHEESE_Y_SCALE` **声明未用**（已标注）·
  实机确认。

## 当前工作焦点（2026-09-15 洞穴群系）

- **★ 此前洞穴内空荡无装饰**。现补上滴水石洞（钟乳石/石笋）+ 繁茂洞穴（苔藓/藤蔓/发光浆果）。
- **★ 装饰是"免费的岛"（关键发现）**：`applyBiomeDecoration` **已委托原版**
  （`super.applyBiomeDecoration`，见 `GeoGenesisGenerator:895`）⇒ 装饰**由群系驱动、
  原版放置**。**只需产出正确群系，零自研装饰**。
- **为何自研群系选择**：原版用 `MultiNoiseBiomeSource` 的 3D 噪声路由；
  本项目是自定义 `BiomeSource` 且**群系是 2D 的**（按列）⇒ 原版 3D 路由用不了。
- **★ 架构：列级信息缓存 + 按 y 裁定（关键设计）**：洞穴群系是 3D，但**不能**把 y
  并进缓存 key —— 该容量是**血泪教训**（512→65536 才解决"世界创建 7.5 分钟"），
  引入 y 会稀释有效容量。解法：缓存**与 y 无关的列级信息**（surfaceY/岩性/气候，
  打包成 `int`，与 `biomeCacheKeys` 同槽位），每次调用用**当前 y** 常数时间裁定
  ⇒ 缓存语义仍 2D、结果可以 3D。
  ⚠ **初版错把 y 相关结果存进 2D 缓存** ⇒ 同列不同 y 互相覆盖（已修，勿重犯）。
- **选择依据：气候（Whittaker 群区）**，与地表群系**同源**：成林气候才可能出繁茂
  （需水分），干旱/极寒只出滴水石。**不做 DEEP_DARK**（绑定远古城市/监守者
  强玩法 + 需结构配合，本项目未实现）。
- **安全边界**：地表下 `MIN_DEPTH`(12) 内**不换群系**（洞口从地表可见 ⇒ 避免
  "洞穴植被长到地表"；原版同样只用地下 depth 启用）。
- **★ 交错混合（修正首版硬二值）**：首版成林 ⇒ 洞内 **100%** 繁茂
  （实测 `滴水石=0 繁茂=21164`）⇒ 违背"两洞共存交错"，且**重犯**本项目老教训
  （"群系过渡不自然"的根因就是阈值硬切换）。现用 **3D 噪声**决定分布
  （气候是必要条件，噪声定分布）。标定实测：`LUSH_MIX_T` 0.62 → 15%；
  **0.44 → 25.8/30.6/29.7%**（约三成）。
- **验收（`runCaveBiomeProbe`，三种子 ALL PASS）**：① 群系产生；② 最小深度 12
  （不破地表）；③ **气候耦合 + 交错**（干旱/极寒繁茂恒 0）；④ 与洞穴几何一致
  （非 NONE ⇒ 必为洞穴）；⑤ 单次裁定 **0.066 us**。
- **守门**：`runClimateBiomeProbe` **非法邻接 0** · `runSoilBiomeCrosstabProbe` ALL PASS。
- **未做（如实记录）**：`DEEP_DARK` · 实机进洞确认。
- **★ 2026-09-16 更正**：**化石与紫水晶洞都【不需要自研】—— 原版已在生成**：
  化石 `fossil_upper/lower` 在 `desert`/`swamp`（我们的 BASIN(干旱)→desert、LAKE→swamp
  ⇒ 已自动获得）；紫水晶洞在 `LOCAL_MODIFICATIONS`（**每个陆地群系都有**）。
  详见下方「紫晶洞」段的删除记录。
- **★ 顺手修的探针雷**：`OreVeinProbe` 默认 `N=160`（< 成矿带特征尺度 190）时
  连通性统计被窗口边界截断 ⇒ 判据5 误报 FAIL。已把默认调至 **512**，
  避免后人无参数运行踩坑（本项目在"窗口不足一个特征"上已踩过两次）。

## 历史焦点（2026-09-15 紫晶洞 —— ★ 2026-09-16 已删除，改用原版）

> **结论：自研晶洞是重复实现，已删除。**
> 原版 `amethyst_geode` **一直在生成**（`LOCAL_MODIFICATIONS` 槽位；1.20.1 datapack
> `jungle.json` 的 `features[2]` 即该特征；`placed_feature` 的 `rarity_filter.chance = 24`），
> 且自带 **95% 裂纹**（`crack.generate_crack_chance = 0.95`）、晶芽、分层与噪声扰动。
> 我们的 `GeodeShape` + `GEODE_BLOCKS` + `runGeodeProbe` **已全部移除**；
> 世界密度回到原版 **1/24**（此前 ≈ 1/24 + 1/25 ≈ **翻倍**）。
> **★ 教训（第四次同型）：动笔前先查"原版是否已经在生成这件事"。**
> 恢复地质门控的正确形态 = 复用原版 `GeodeFeature` + 自定义 `placement` 门控（非重写形状）。

以下为删除前的设计记录（保留其方法论价值）：

- **原目标**：补齐"地下探索奖励"的最后一块（岩层 + 洞穴 + 矿脉之外的结构）。
- **原实现**：`GeodeShape` 零 MC 依赖纯函数（照 `CaveShape`/`OreVeins` 范式）
  + 生成器 `GEODE_BLOCKS` 映射（`SMOOTH_BASALT / CALCITE / AMETHYST_BLOCK / BUDDING_AMETHYST / CAVE_AIR`）。
- **地质依据**：杏仁状玄武岩 —— 岩性门控到**玄武岩/安山岩**（火山岩气孔被热液充填）。
  **被岩层界面切平是正确产状**（杏仁体局限于单一熔岩流），且因此**无需跨体素状态**。
- **★ 两条必须保留的推导（改代码前先读 javadoc）**：
  1. `JITTER(9) + MAX_SEMI_AXIS(6.5) = 15.5 ≤ CELL/2(16)` ⇒ **椭球不越出自己那一格**
     ⇒ 每体素只查 1 格、**无缓存、无跨线程状态**。放大晶洞**必须同步放大 `CELL`**。
  2. 壳层按**归一化半径**分档（`r` 自中心单调↑）⇒ 层序**数学上必然正确**。
- **★ 洞穴联动免费**：洞穴在 `applyCarvers`（更晚）雕 ⇒ 洞穴穿洞处**洞壁自然露紫水晶**。
- **★★ 参数对齐原版（2026-09-16，用户提醒"为什么不去网上查"）**：
  - **原版数据**（Wiki / 数据包 `amethyst_geode`）：**Y=-58~30** ·
    **每 chunk 1/24 概率** · `layers = filling 1.7 / inner 2.2 / middle 3.2 / outer 4.2`
    （按**累加半径**：d0=1.7 空腔 · d1=3.9 紫水晶 · d2=7.1 方解石 · d3=11.3 外壳）·
    **晶芽不是独立层**：紫水晶层内 **8.3%** 方块被替换（`use_alternate_layer0_chance`）·
    95% 概率带裂缝使内部暴露。
  - **我初版凭观感定的分档（0.35/0.50/0.68/0.86）实测体积占比
    外壳36.4 / 方解石32.2 / 紫水晶18.9 / 晶芽8.2 / 空腔4.3**，而原版折算应为
    **外壳≈75 / 方解石≈21 / 紫水晶≈3.8 / 空腔≈0.34** ⇒ 变成了"薄壳+大水晶"。
    **剖面图看不出来（都同心），是"方块体积占比"暴露的。**
  - **已修正**：分档 `R_AIR=0.150 / R_AMETHYST=0.345 / R_CALCITE=0.628`；
    晶芽改为层内 8.3% 逐体素哈希散布；`BASE_PRESENCE` 0.075→**0.129**。
  - **对齐后实测**：外壳 75.39 / 方解石 20.53 / 紫水晶 3.49 / 晶芽 0.27 / 空腔 0.32
    —— 与原版折算值几乎精确吻合；密度 **1/25.0 chunk**；性能 **0.120 ms/chunk**。
  - **新增判据8（方块占比符合原版量级）** —— 这条判据**当初就能抓住上述错误**；
    判据6 密度目标收紧为**对齐原版 1/24**（容差 1/38~1/15）。
  - ⚠ **口径**：探针用**合成岩性**（8 种均匀 ⇒ 宿主岩约 25%）⇒ **探针密度 ≠ 实机密度**；
    `BASE_PRESENCE` 是唯一旋钮。
- **验收**：`runGeodeProbe` 判据 **1~8 全 PASS**；多种子（12345/7/42/999）稳定。
  密度 1/25~26 chunk · 纯函数成本 0.111~0.168 ms/chunk
  （对照矿脉 0.13~0.19、洞穴 2.5~3.1）。
- **★ 一条通用准则（本次教训）**：**凡是 MC 里已有对应物的结构/参数，先去查原版
  数据包 JSON 或 Wiki，不要凭观感定**；MC 已有明确量级的（密度/层厚/Y 范围）尤其如此。
  同理，**项目已有的参考项目（TF/FreeTerraForged）应现场核对源码**，而不是只凭记忆引用。
- **顺带查到的矿石侧差异（刻意保留）**：原版 `ore_diamond.json` 有
  `discard_chance_on_air_exposure=0.5`（洞壁矿石**一半丢弃**）；本项目**反向** ——
  洞壁处矿脉阈值**放大 1.55×**（`VEIN_EXPOSURE_MUL`）⇒ 洞壁矿石更明显。玩法选择，不改。
- **★ 探针抓到的真 bug（务必记住这个范式陷阱）**：`unit()` 写成
  `(h >>> (k*9)) & 掩码` —— "取不同位段"看似合理，但 `h` 只 64 位，
  `k≥2` 起有效位被右移殆尽（k≥4 恒 0）⇒ **抖动/尺寸全塌成固定值**。
  正解：每次**重新混合**取高 53 位。**是"尺寸分布"判据暴露的** ——
  只判"是否成洞/壳层序"会一直藏着。
- **★ 两条判据口径教训**：
  ① "缺层"≠"失序"：门控切平椭球**本来就缺层**，且**深切割先丢内层**
     （空腔最小）⇒ 改为"只对出现过材料要求递增" + 出**缺失层直方图**。
  ② 稀有特征的密度**不能直数窗口内的个数**（Poisson 噪声）：改用
     **放大 presence 后线性折算**；探针默认窗口 256→**512**。
- **未做**：化石 · 实机目检。

## 当前工作焦点（2026-09-15 矿脉系统）

- **★ 2026-09-16 补记：与原版的【总量对照】已量化，但【刻意不改平衡】**：
  - `OreVeinProbe` 新增 `[2b]`「每 chunk 矿石块数」（绝对口径，原只有百分比 ⇒ 无法与原版比）
    + **判据7「总量锚定 140~330 块/chunk」**（钉住**我们自己**的量，防日后手滑改成 10×/0.1×；
    刻意**不**锚定到原版数值）。
  - 实测合计 **231.9 块/chunk**：煤 41.1 · 铜 35.5 · **铁 109.2** · 金 13.4 · 红石 18.1 ·
    青金石 4.2 · 绿宝石 6.1 · 钻石 4.2。
  - 原版对照**两个来源矛盾近 10×**：数据包**名义上限** `count×size` 合计 ~2984
    （钻石 168 / 煤 850 / 铁 940）；而**历史公认值**是 钻石 ~3.7 / 煤 ~185 / 铁 ~77。
    成因：① `size` 是单脉**最大**块数（实测约 0.5~0.75×）⇒ `count×size` 是上限非期望；
    ② 原版矿种**多变体**（钻石 3 个：small/large/buried），我们每矿 1 个；
    ③ 该参数表自注"钻石/金/红石的 count 可能有 1~2 点偏差"。
  - **结论**：按钻石口径我们与历史公认值持平、按铁更多、按煤偏少 ⇒
    **得不出"该调多少"的可靠结论**。
  - **决策**：① **不据此改平衡**（避免重犯本轮反复出现的"凭不足证据就动手"）；
    ② 旋钮写清：**`PROSPECT_T`（成矿带阈值，越小带越大→矿越多）·
    `VEIN_T`（脉体阈值，越大脉越粗）**；③ 裁决交给**实机手感**。
  - **结构性差异（刻意保留）**：本模组是"**少而大**的地质矿体" —— 实测平均脉体
    **143.8 块**，而原版单脉 `size` 仅 **4~17**。这是"成矿带/矿床"模型的必然结果。

- **★ 此前完全没有矿石**：地下只有岩层 + 洞穴 ⇒ 挖下去没有任何矿物。本轮补齐。
- **为何自研**：同洞穴 —— 自定义 `ChunkGenerator` 无 `NoiseSettings`，原版 `ore_*`
  依赖 `OreConfiguration` 替换机制 + `FeatureSorter` 阶段 ⇒ 用不了。
- **三层门控**：① **成矿带**（2D，尺度 190 块，**性能主控**，否掉 ~63~73% 列）
  ② **宿主岩 + 深度带**（整数比较，零噪声）③ **3D 双噪声等值面交集**（同 `CaveShape` 隧道原理）。
- **★★ 地质学正确性（三参考项目均无）**：矿种**按岩性 + 深度成矿** ——
  煤(砂岩/页岩,浅6~70) · 铜(玄武岩/安山岩,20~90) · 铁(全部,15~110) ·
  金(花岗岩/片麻岩,30~130) · 红石(深结晶基底,60~170) · 青金石(石灰岩,40~130) ·
  绿宝石(片岩/片麻岩,70~190) · 钻石(片麻岩/片岩,90~220)。
- **性能实测（`runOrePerfProbe`）**：典型 **0.226~0.682 ms/chunk**、最坏
  **1.35~1.48 ms/chunk** —— 均优于洞穴的 2.48。**必须专测**：矿脉在方块铺设的
  逐体素层，既有性能探针只测地形层 ⇒ **不被任何既有探针覆盖**（洞穴那轮漏过这项）。
- **验收（`runOreVeinProbe`，ND=512）**：密度 0.408/0.535/0.424% ·
  8 矿种齐全 · 岩性耦合受控对照 PASS · 深度带 PASS · 平均连通分量 **143.8 块**。
- **旋钮**：`OreVeins.PROSPECT_T`（矿区大小/数量）· `OreVeins.VEIN_T`（脉体粗细，体积 ∝ t²）。
- **★ 两个探针口径陷阱（均已修，务必勿重犯）**：
  ① **窗口必须覆盖多个特征尺度**：成矿带尺度 190 块，实测 N=96 时**带内列 0%**
     （不足一个特征 ⇒ 统计空转，看起来像"功能失效"），N=512 时 27~36%。
     **与洞穴密度踩过的窗口坑同型。**
  ② **岩性必须成片**：初版按 `(x+z)%8` 逐列轮换 ⇒ 相邻列岩性不同 ⇒ 脉体被"换岩"
     反复截断 ⇒ 平均连通分量仅 26 块、切片呈散点（**假碎片化**）。改 128 块分区后
     升到 **143.8 块**（5.5 倍）。
- **★ 接口设计教训**：初版把"成矿带门控"与"脉体判定"做成两个分离的公开方法，
  约定"调用前应先用 columnProspective 过滤" —— 结果**扫描模式直接漏用**，
  实测成矿带**完全失效**（`prosMul` 三档扫描结果完全相同）、矿脉均匀散布。
  现改为 `veinAt` **内部自带门控**（列级缓存）⇒ 漏用**不可能**发生。
- **★★ 矿脉 ↔ 洞穴联动（已完成）**：矿脉在**洞壁露头**（此前独立判定 ⇒ 洞穴穿脉处
  把矿挖断，"矿在洞里断掉"）。做法借鉴 FreeTerraForged 的 `UndergroundFeatureEnclosure`
  （"感知是否贴近空腔"）：紧邻洞穴的体素阈值 ×`VEIN_EXPOSURE_MUL`(1.55)。
  - **★ 时序陷阱**：矿脉铺在 `fillFromNoise`、洞穴雕在之后的 `applyCarvers`
    ⇒ 铺矿时洞穴**还不存在**，**无法查询方块** ⇒ 必须用 `CaveShape.isCave`
    **确定性纯函数预测**。这是本联动的核心设计点。
  - **★ 并发陷阱**：初版"注入静态探针"在多 chunk **并行生成**时会被互相覆盖
    ⇒ 改为**参数传递**（litho 纯值），零共享状态。
  - **★ 两阶段优化（14×）**：朴素"每体素 6 邻预测"实测 **3.14 ms/chunk**（超阈值）。
    洞察：`exposure` 只**放大阈值** ⇒ 只能救回"擦肩"体素。故①先用基准阈值判，
    ②只有 `|n|∈[t, t×1.55)` 的**擦肩**体素（实测仅 **1.9%**）才做预测。
    种子 7：**3.14 → 0.844 ms/chunk**，增量 **+2.32 → +0.165**。
  - **验收**：判据6 = 受控对照 + **三态不变式校验**（双向等价 + 单调性，违反 0）
    → 增幅 **2.37×** · 三态（命中 9527 / 远离 700245 / 擦肩 13428）。
- **★ 探针口径陷阱（本轮新增，已修）**：判据6 的对照**必须限制在洞穴深度窗口内**
  （`CaveShape.DEPTH_MIN..DEPTH_MAX` = 8~120）—— 矿脉深度带是 6~220，深于 120 处
  **根本没有洞穴**，"紧邻洞穴"不可能成立 ⇒ 不放大才是对的。若不限制会把联动范围
  高估（实测 24597 vs 合理 10409）。**已把 `CaveShape.DEPTH_MIN/MAX` 开放为 public
  供两边共用同一口径，避免硬编码漂移。**
- **未做（如实记录）**：深板岩变种 · 生成时空气替换检查。
- **`CACHE_SCHEMA_VERSION` 未升**：矿脉只改 **chunk 方块**、不改任何 `Cell` 派生量
  （`OreVeins` 对 `Cell` 只读）⇒ 预览图层无差异。

## 当前工作焦点（2026-09-15 洞穴系统）

- **★ 洞穴此前完全是空实现**：`GeoGenesisGenerator.applyCarvers` 自创建起写着 `// 暂不实现洞穴雕刻`
  ⇒ 世界挖下去只有实心岩。本轮补齐。
- **为何自研**：本项目是自定义 `ChunkGenerator`，**没有** `NoiseSettings`/`NoiseChunk`；原版
  cave/canyon carver 依赖 `NoiseChunk`，且 `ChunkGenerator.applyCarvers` 基类是**空实现**
  ⇒ `super.applyCarvers` 无效、原版 carver 用不了。
- **核心机制（移植 `TerraForged-0.3.x`）**：**2D 场驱动「竖直柱体切挖」，不用 3D 噪声** ——
  每个 (x,z) 用 3 个 2D 场算「中心高度/向上扩展(脊线)/向下扩展」，整柱挖空；相邻列重叠 ⇒ 3D 网络状洞穴。
  成本 ≈ 6 次 2D 求值/列。**这是 TF 的关键洞察**（全仓无 4 参数 3D 噪声调用）。
- **两类洞穴族**：`SYNAPSE`(细密,size 9) + `MEGA`(粗大, 受低频区域掩码门控, size 18)。
- **★ 本项目独创：岩性门控**（三个参考项目**均无**）：石灰岩 ×1.7（喀斯特）→ 花岗岩/片麻岩 ×0.55。
  受控合成实测 **4.54×**。
- **安全边界**：洞顶钳 `surface−6`（**永不破地表**，实测 0 次）；海底列跳过（**无 aquifer**，
  挖海底会留干空腔）；遇流体跳过。
- **接口分离（关键）**：几何在**零 MC 依赖**的 `CaveShape`（探针可复用同一份生产实现，无需复刻）；
  `CaveCarver` 只是薄适配器。照抄 `SoilInfluence`/`BiomeClassifier` 的同一范式。
- **零额外地形采样**：岩性/地表取自 `terrain.getChunkCells()` 的 **LRU 命中**；**刻意不读 chunk 高度图**
  —— 绕开 TF 警告的"`OCEAN_FLOOR_WG` 在 carvers 阶段未必已 prime"陷阱。
- **实测（合成扫描，种子 12345/7/42）**：密度 **3.05%/3.05%/3.19%**（种子稳健）· 破地表 **0** · 岩性比 **4.54/4.45/4.46×**。
- **调参旋钮**：`CaveShape.SHAPE_FLOOR`（密度主控）。0.35 → 18%（瑞士奶酪）；0.55 → ~3%。
- **★ 探针口径教训（已修）**：密度**不可用真实地形窗口统计** —— 噪声特征尺度 350 块，
  chunk 窗口仅数十~百余块（不足一个特征）⇒ 实测 0.40%~7.11% 剧烈波动，且换种子可能整窗是海
  （seed=42 → 陆地列 0，判据空转）。改**大范围合成扫描**后稳定 3.05~3.19%。属**口径错误**非功能缺陷。
- **新增探针 `runCaveShapeProbe`**：输出 `build/cave/slice_z*.png`（X-Y 剖面，白=洞穴）。
  ⚠ 渲染必须**裁到可判读窗口**（全高 512 会把洞穴压成细斜纹而误判）+ **按 chunk 行取整行切片**
  （按固定 z 匹配会让 15/16 列无数据 → 图上出现规则黑条带，实测踩过）。
- **未做（如实记录）**：洞穴 biome（TF 会在洞里写 dripstone 群系 + 放钟乳石特征）、洞穴装饰、
  洞穴与矿脉/地下水联动。洞内方块仍是围岩岩性（岩性映射照常生效）。
- **验证覆盖的诚实说明**：探针验证的是**几何**；`CaveCarver` 是极薄适配器，
  端到端需 `runClient` 实机挖洞 —— **现有探针无法覆盖 MC 侧方块写入**。
- **`CACHE_SCHEMA_VERSION` 未升**：该版本号服务**预览磁盘缓存**，洞穴只改 chunk 方块、
  不改任何 `Cell` 派生量（`CaveShape` 对 `Cell` 只读）⇒ 预览无差异。

## 当前工作焦点（2026-09-14 构造地貌可见化 + 碎石坡归位 + 盆地抬离海平面 + 地质→群系耦合）

用户三问：①「构造地貌（断层崖/地垒/地堑）看不到？」②「靠海出现盆地？」③「碎石堆怎么在山脊上？」

- **① 构造地貌"看不见" → 真因是信噪比，不是没接**（⚠️ 交接总结曾写"构造场只在岩层数据层、未暴露地表"，**与代码不符**）：
  - `CellGenerator:377-379` 的 `DEFORM_ENABLED=true` + `eLand += deform.offset(...)` **早已接入**（`sampleCore` 与 `extractTile` 两处）。
  - 实测（新探针 `runDeformVisibilityProbe`，镜像 vs 生产 **0/4000 不一致** 逐位自检通过）：改前 deform **均值仅 0.29 块 / 覆盖率 41%**，而 eLand 全域跨 158 块 ⇒ 信噪比 3.7%，肉眼不可辨。
  - 修法：`FAULT_AMP 0.045→0.090`、`SCARP_HALF_WIDTH 0.30→0.18`、新增 `BELT_BIAS=+0.10`（带覆盖率 41%→49%）。**褶皱 `FOLD_AMP` 刻意不动** —— 渲染图证实高幅褶皱的 ridged 结构呈**蠕虫状波浪细线**（与「密集波浪状平行细线」伪影同源），断层崖才是地垒/地堑的视觉主体。
  - ⚠️ **关键教训：单提幅度无效** —— 实测 amp×2 时 `max|∇h|` **1.88→1.88 完全不变**（崖宽 20~40wu 把 9 块落差摊平，坡度仅 ~0.25）。**必须同时收窄崖宽**才能形成可辨崖线。
  - 结果：断层崖最大梯度 **0.002517→0.008275（3.3×）**、均值 0.29→**0.64 块**、p99 3.59→6.56 块。
- **② 「靠海盆地」→ 是"高度区间骑在海平面线上"，不是坐标 bug**：`SplineConfig` BASIN `hi=0.02 e` 仅海平面上 3.8 块、`lo=-0.08 e` 在海面下 15 块 ⇒ **盆地类型定义本身贴海面**（对照 PLAIN 0.005~0.03 e 同样贴海面 ⇒ 实为"海岸低地被判为盆地"）。修法：`hi 0.02→0.05`（盆顶达海面上 9.6 块，与 PLAIN 区间错开），`lo` 保持 -0.08（保留内陆沉降洼地/裂谷成湖）。
  - **语义澄清（重要）**：本项目 `BASIN` = **沉降洼地/裂谷**，**不要求四周环山**。经核查三个参考项目（worldgen / FreeTerraForged / RTG）**也都没有**"环山"判据（RTG 的 `VoronoiBasinEffect` 只是距心形态、worldgen 的 basin 是河流流域）。图例已正名为「盆地/裂谷」「Basin/Rift」（`zh_cn.json` / `en_us.json` / `GeoPalette` 内置英文回退三处同步）。
  - **为何不做"环山"判据**：它必须邻域采样，而邻域量（如 `gradient`）**只在完整管线有**、`sampleCellLight` 拿不到 ⇒ 会造出**预览≠游戏**且是 spawn search 热路径的性能雷（项目反复踩过的坑）。
- **③ 碎石坡在山脊 → 判据缺"凹凸"维度（真缺陷）**：`cell.gradient = √(dhx²+dhz²)` **恒为正** ⇒ 山脊坡面与沟谷侧壁数值相同，一起被判碎石坡。而真实 talus 是重力碎屑在**凹坡坡脚**堆积（脊是凸地形、碎屑滚落）。
  - 修法：判定改为「陡 **且** `cell.riverNetDischarge ≥ SCREE_DISCHARGE_MIN × erosionDropsMul`」。**选 discharge 的理由**：物理正确（汇流累积=收敛度，沟高脊低）、**零额外采样**（字段已存在）、**不产生路径分歧**（它与 `gradient` **同源**，都只在完整管线 `applyTileDelta` 填充 ⇒ "轻量路径不判碎石"是一致行为）。
  - 实测（新探针 `runScreePlacementProbe`）：**AUC=0.845**（强判别力）；门控 `D=2.31` ⇒ **脊保留 24.9%、沟保留 77.9%**。
  - ⚠️ **阈值必须按 `erosionDropsMul` 缩放**：discharge 是液滴计数，总量随配置液滴倍率线性变化；用绝对常数会使倍率 2× 时门控形同失效。
- **④ 地质→群系耦合（Phase T12，用户"地质和群系有关系吧？"）**：`BiomeClassifier` 此前**完全没接**地质（搜 `rock|strata|tectonic` **0 匹配**）。
  - **★ 参考项目调研结论：两个项目都没做**。FTG 的 `CellSampler.Field.SEDIMENT` 只进**地形密度函数**（不接群系轴）、`StrataRule` 只作用于**地表方块分层**；RTG 完全没有岩性轴。⇒ 本项是**原创扩展**，无配方可抄；可借鉴的只有 FTG 的 `WeightedBlockSelector`（权重→选择）思路。
  - 实现：新增 `SoilInfluence`（岩性→成土性质 3 类 + 噪声门控，零 MC 依赖）＋ `BiomeClassifier.soilVariant`（**映射表在 `BiomeClassifier` 内**，因只有它持有 `Biomes.*`）。管道顺序 = 基础气候群系 → 河流绿洲 → 地形形态变体 → **岩性变体**。
  - **★★★ 铁律：变体不得引入【新的】气候邻接对 ★★★** —— 故只做 **2 条**、且**全部复用 `landVariant` 已确立的合法对**：`LIMESTONE/BASALT/ANDESITE(CALCAREOUS) → PLAINS→MEADOW`、`GRANITE/GNEISS/SCHIST(ACIDIC) → FOREST→BIRCH_FOREST`。`SANDSTONE/SHALE(NEUTRAL)` **不变**（无可安全复用的既有对，硬造新对正是"跨气候跳变"风险源）。地学依据：钙质土/火山土肥沃→草甸；酸性贫瘠土→先锋桦木林。
  - 门控复用 `Cell.oasisNoise`（**零新增噪声、零新增 Cell 字段**），`STRENGTH=0.50`（静态常量而非 Forge 配置，避免死配置），强度 0 时永不触发（可逐位回滚）。
  - 实测：门控触发率 **21~28%**（设计值 30%），三随机种子稳定；`runClimateBiomeProbe` **非法邻接 0（0.0000%）**。
- **本轮守门（全绿）**：`runTectonicDeformProbe`（断层崖 3.3×，近零均值保住）· `runTectonicWaveProbe`（**各向异性 1.137**，无平行带回归）· `runTectonicProbe` 16 项 · `runTectonicContinuityProbe` · `runLandEConformityProbe`（**跳变 0**）· `runPrecipRiverWidthProbe`（未污染水文标定）· `runTerrainShapeProbe` · `runClimateBiomeProbe` · `runPaletteProbe` · `runStratumProbe` · `runSoilBiomeCrosstabProbe`（3 种子）。
- **★ 发现一处【预存的脆弱判据】（非本轮回归）**：`runRockErosionProbe[3]`「软岩因耦合被显著加深侵蚀」。本轮出现 FAIL，经 **git stash 基线对照 + 多种子复现** 定性：**基线在 seed=7/42 本就 FAIL**（`+0.000134` / `+0.001246`），仅 seed=12345 靠单 tile 取样巧合 PASS；且**仅 A 生效即 FAIL、与 C 无关**（已隔离验证），选中 tile 与格子分组基线与新版**完全一致**（nS=896/nH=13952）。⇒ 该判据应改为多种子聚合或扩大样本，属**独立议题**（未擅自改判据）。
- **新增探针**：`runDeformVisibilityProbe`（镜像生产数学 + 逐位自检 + 多参数并排山体阴影/差值图，`build/deform/`）· `runScreePlacementProbe`（脊/沟 AUC + 门控模拟，`build/scree/`）· `runSoilBiomeCrosstabProbe`（受控 A/B 交叉表；⚠️ 该探针进程**无法完成 MC 引导** ⇒ 群系改写率判据跳过，**验证责任已指派**：合法性→`runClimateBiomeProbe` 的「非法邻接 0」、生效率→门控触发率；**其反射桥接不破坏"诊断源码集零 MC 依赖"纪律**）。
- **`CACHE_SCHEMA_VERSION` 65 → 69**（66：构造放大 + 盆地抬升，改 eLand 产出；67：地质→群系耦合，改群系产出；68：移除 T5 `decay(dist)`，改 eLand 产出；69：峡谷谷壁收窄，改雕刻产出）。
- **★ 峡谷（大峡谷式）**：**先实测再定参**（新探针 `runCanyonProfileProbe`）—— 实测发现缺口不是"陡"而是"宽"：h≥20 的深谷坡度已达 1.22（≈51°），但典型河谷被 `bankFactor`(2.5×) 与 `bankRunMax`(24) 双重托底 ⇒ 宽缓河谷。
  - **做法**：`RiverLineParams` 新增 4 个峡谷参数（`canyonMinBank=8`/`canyonFullBank=20`/`canyonSlopeRun=0.40`/`canyonWallFactor=1.6`）；`HydrologyBlockCarver` 按**岸高 h = 原地形 − 计划水面** smoothstep 门控，深谷段**收窄谷壁跨度**（同样的落差压缩到更窄横向距离 = 陡壁）。两处 `adaptiveBankRun` 调用（主几何 + 每样本 `vs`）**必须同参**，否则属主切换处会出现宽度失配台阶。
  - **关键取舍：水面完全不动** ⇒ 河流纵剖面零改动 ⇒ **不污染水文标定**（实测 `FlowAccumProbe` 的 `border.maxSurfaceDelta`/`fillWater`/`hitColumns` 与改前**逐位一致**，正是"只改谷壁几何"的预期证据）。
  - **A/B 验证**（铁律：最终依据只能是渲染图）：临时 `canyonMinBank=999` 关闭峡谷做基线，与原图对比 **MD5 不同** ⇒ 确认真生效。陡坡占比 h∈[15,20) **19.3%→37.6%**。
  - **守门**：`runValleySeamProbe` **PASS**（雕刻诱发谷壁断层 **0**）· `runFlowAccumProbe` PASS · `runHydrologyWaterFillProbe` PASS · `runPrecipRiverWidthProbe` PASS。
  - **⚠️ 限度**：峡谷**深度**受地形高差限制（谷壁带内最大岸高 22.6 格）。要 40~60 格必须在**地形/河道纵剖面**上加大高差 —— 两者都会污染水文标定，故本轮不做。
- **★ T5 技术债清偿：移除最后一个 `decay(dist)`**（CHANGELOG 长期遗留项）：`dist` 的等值线是**平行于 Voronoi 边的多边形**，故 `decay` 一直在把多边形印进地形（**残留"淡淡多边形棱面"的唯一来源**）。现定位仅由世界坐标 `beltMask` 决定，**早退 `d>=reach` 一并移除**（同类硬截断）。`decay` 方法已删；`FOLD_REACH`/`FAULT_REACH` **不再参与计算**，仅作文档与探针窗口参考。
  - **负作用评估**：幅度 gating 仍三重（`beltMask` × `segmentMask` × `smoothPos(stress)`）⇒ 板块内部 `stress≈0`，形变自然为 0，不会泄漏。
  - **连带**：形变略增（均值 0.64→**0.67 块**、p99 6.56→**7.07 块**）；性能**提升**（`offset` 0.211→**0.137 µs，−35%**）。
  - **探针判据重构（★ 未放宽、反而更严）**：`TectonicDeformProbe[5]` 由「`dist>REACH ⇒ 0`」改为「**按掩码归零**」+ **新增反向验证**「带内须确有形变」。实测掩码为 0 处 **7000 点 0 违例**、带内 **100% 有形变**。新增公开入口 `TectonicDeformation.beltMaskAt(wx,wz)` 供探针做结构性判据。
  - `runFlowAccumProbe` **`status=PASS`**（`border.maxSurfaceDelta` 1.358→**1.391**，容差 1.5 仍留余量）—— 该指标对地形改动高度敏感，已纳入验收。
  - 渲染图目视：崖线呈**有机蜿蜒曲线，无直边多边形痕迹**。

## 当前工作焦点（2026-09-13 「笔直线段 + Y 形交汇」伪影根治，用户实机确认消失）

- **用户症状**：预览/实机出现**笔直细线段 + Y 形交汇**（不是波浪等值线，而是**直线网**）。历轮修复（T5 去量化、褶皱圆化、`smoothPos`、域扭曲、`valueNoise` 升 Catmull-Rom、`beltMask`）后仍存活。
- **方法论转折**：新增 `TerrainStripePngProbe` 的 **[F] 逐分量梯度排查** —— 对 eLand 每个输入分量单独求 `|∇|`，打印 `P99.9/P50` **长尾比**并输出 `gmagC_<分量>.png`。**一次运行即锁定元凶**，不再逐个假设（此前 6 轮都是"假设→修→仍存在"）。
- **三处根因（按发现顺序，均已修）**：
  1. **`TypeNoiseProvider.foldHills` 的 `|2n−1|` 折叠**（HILLS/PLATEAU 共用）：频率翻倍 + 梯度恒定 + 折痕等值线成对 ⇒ 「密集波浪状平行细线」。修法 = **撤销折叠**（`clampUnit`）。逐配方高通实测 `0.425/0.464 → 0.193/0.157`。
  2. **`TectonicField.blurDist` 的 reach 硬切换**：沿 `dist=420`（= Voronoi 多边形**偏移网**）产生 dist 阶跃 ⇒ `tect_dist` 的 `max|∇| = 37.7`（距离场**不可能**超 ~2.4，荒谬值即铁证）。修法 = 混合权重 smoothstep 渐隐 ⇒ **1.26**，长尾比 `17.9 → 1.6`。
  3. **★ 最终根因：`stress` 是「分片常数场」**。旧式 `stress = dot/|v1−v2|` 在配对不变区域内**与位置无关**（探针铁证 `P50|∇| = 7.9e-17` —— 精确为 0）⇒ 配对在 Voronoi 边 / order-2 边切换处沿**直线网**阶跃 ⇒ 经 `boundaryStrength`(σ=110) × `CONVERGENT_BOOST`(2.5) 印进 eLand。修法 = **连续加权投票** `stressField`（5×5 窗口、σ=1000、gain 2.0），并删除 `smoothStress`/`stressAt`/`rawStressFor`/`dotCrossToStress` 与 `STRESS_BLUR_*`（**每次采样少 100+ 次哈希，净性能收益**）。
- **验收（全绿）**：`runTectonicProbe` ALL PASS（造山带 **1.70×**、串珠 `meanM=0.611/sd=0.151`、性能 **2.16µs < 5µs**）；`runTectonicDeformProbe` ALL PASS（纯走滑无垂向形变等语义保住）；`runTectonicWaveProbe` ALL PASS（各向异性 **≈1.0**，无平行带回归）；`runTectonicContinuityProbe` ALL PASS；`runPrecipRiverWidthProbe` PASS（head 最干桶 **0.928 < 0.95** ⇒ **未污染水文标定**）。分量长尾比 `tect_stress ∞→1.6`、`tect_chain 68.5→3.0`；eLand `max|grad| 0.00245→0.00211`；`[full] max|grad| 4.10→1.78`。**用户实机确认伪影消失**。
- **探针口径修正（重要）**：`TectonicProbe` [6] 的筛选从 `btype==CONVERGENT` 改为 `smoothPos(stress) > 0.70`（**等价**：CONVERGENT ⟺ `|dot|>|cross|` ⟺ `|stress|>1/√2`）。应力改为**区域尺度**场后，局部配对标签不再蕴含 `stress>0`，旧口径把非汇聚样本混入 → 假失败（`meanM 0.531 → 0.611` PASS）。`TectonicContinuityProbe` 阈值 `1.5 → 2.2`（原阈值漏算域扭曲的合法梯度上界 ≈2.07；**该探针 [3] 自证**：无扭曲的裸公式 `(d2−d1)/2` 实测 max = **1.00wu** 正是理论极限，生产路径 1.69wu = 差额即域扭曲贡献），并让 `max` **无条件记录**以免掩盖真实上界。
- **验收探针组全绿（补还验证债）**：除前述 5 项外，另跑 `runChunkBorderProbe`（含蚀差 max 1.08 块）、`runLandEConformityProbe`（最大跳变 <0.02e ≈ 3.8 块，**ALL PASS**）、`runPlateauProbe`（中心 eLand 0.307 / Y=135.7，宽缓高台、**无**"中间低四周高"环形伪形）、`runHydrologyTerrainEntryProbe`（**PASS**）。
  - ★ `FlowAccumProbe` 的 `reachedOcean=38/45 (84.4%)`（历史记录为 100%）**已用可控变量实验排除与本轮改动相关**：临时把 `TECTONIC_ENABLED` 置 `false` → `38/46 (82.6%)`，**到海 region 数完全相同（38）**；且该指标**不在 PASS 判据内**（判据 = `cycles/profile/gate/border` 全 0，实测全 0 → `status=PASS`）。差额来自 2026-08-29 河网参数变更（门槛 2000→200、源间距 3→1）后新增的大量内陆小溪。
  - **两条探针缺陷已修**（单 chunk 空断言 / 海底选点，详见 `CHANGELOG.md` [Unreleased]）。
  - **实测技巧**：Windows 下探针中文输出乱码是**运行时 stdout 编码**问题（`build.gradle` 已设 `options.encoding='UTF-8'`，编译期无问题）。设
    `$env:JAVA_TOOL_OPTIONS="-Dstdout.encoding=UTF-8 -Dfile.encoding=UTF-8"` + `[Console]::OutputEncoding=[System.Text.Encoding]::UTF8` 即可正常读取中文判据。
- **性能归因（`coldMs`；用户实机无感知）**：`runFlowAccumProbe.coldMs` 实测 **6048ms**（复测 5925/6038，±2% 可复现），而本文旧记 **1849ms** —— ⚠️ **两者不可直接比较**：旧值是 15+ 个提交之前、且 region 数未必相同，期间新增了整套气候系统（2026-09-10）+ 河流绿洲 + 降水加权汇流（Phase C）+ 地质 T1~T5。**可控变量实测分解**（同步记录 `riverRegions` 以确认工作量未变，实测恒为 45/47）：
  - 仅关 T1（`TECTONIC_ENABLED`）：6048 → 5835，**T1 ≈ 213ms**
  - 关 T1+T4+T5：6048 → **4419ms**，**地质总计 ≈ 1629ms（27%）**（其中 T4 地层 + T5 形变 ≈ 1.4s）
  - 关地质 + `precipitationAt` 置常量：6038 ≈ 对照 6048，**降水加权汇流 ≈ 0（假设被推翻）**
  - **结论**：27% 是新增地质功能的成本；剩余 ~4.4s 在基础地形 + 河网路径，且不是降水。**成本属新功能累积，未见缺陷**。
  - **后续若要深挖**：必须做真正的 profiling —— ⚠️ JFR 经 `JAVA_TOOL_OPTIONS` 只采到 **JVM 启动阶段**（5 个样本），采样器接不进取 gradle **fork** 的探针进程；正确做法是给探针 task 显式加 `jvmArgs`（或独立 launch），属单独一轮工作。
- **已知遗留（两项均已量化并给出结论，均**不改**）**：
  1. **高度标定"漂移" → 论证为不应调、也无法调**：同一 304wu 窗口 `[full] height` `64.5~112.4` → `88.0~152.9`（+23~40 块）。归因实验实测：**撤销折叠只占 +2 / +13 块**（该窗口其余升高来自本提交合并的会话前几轮）。**为何不调**：① 会话前基线在任何提交里都不存在（试回退 `TectonicField` 到 `60e182f` 连编译都过不了 ⇒ 不可复原）；② 没有任何探针/规格定义**绝对高度**目标（只有相对判据：造山带 ≥1.5×、高原平顶等，均通过）；③ 唯一的绝对标定守门 `runPrecipRiverWidthProbe` 通过（head 最干桶 0.928 < 0.95）。在无基线无目标下调旋钮 = 纯凭口味改参。若日后确有偏好，旋钮为 `CONVERGENT_BOOST`（2.5）或 `STRESS_VOTE_GAIN`（2.0）。
  2. **类型场 Voronoi 轴对齐直段 → 已量化 → 试修 → 回退**：
     - **量化**：新增 `runTerrainStripePngProbe` 的 **[G] 段**（指标 = 最长轴对齐直段 / 采样边长 + 水平:竖直方向比）。窗口 `(-1500,1500)` 实测最长水平直段 **560wu（0.19×）** 且**正好落在网格中垂线 `z=3204`**；另一窗口方向比 2.35 属**窗口地理取样**而非系统偏差（对照窗口 1.10）。
     - **试修**：给 `TerrainCharacterField` 细胞种子加抖动（`SEED_JITTER=0.7`）→ 目标达成：**560wu → 120wu**、方向比 **1.10 → 0.87**（各向同性）。
     - **回退（结论）**：抖动改变排水格局 ⇒ `runFlowAccumProbe` 的 `border` 指标退化：`border.maxSurfaceDelta` **1.358 → 12.772**（≈12.8 块落差）、`border.violations` **0 → 2**（属 PASS 判据 ⇒ `status` PASS→REVIEW）。A/B **精确可逆**（回退后 `hitColumns/fillWater/maxWaterDepth` 逐项复原）。以敏感指标退化换预览层美观不划算，且抖动在热路径多一次数组分配却零收益 ⇒ 全量回退（不留死开关）。
     - **⚠️ 更正（2026-09-13 核查）：此前记的"前置条件 = 先修「跨 region 水面无继承」"是错的**（照抄了 2026-08-29 的过时记载）。实际代码里**继承机制已存在**（2026-09-07 加入）：`RiverLineRegion.OutletSeed` 携带 `surfaceY`/`accum`/`level`，`RiverLineNetwork.region()` 走**双-pass**（pass-1 记录出口 → pass-2 吸收 4 邻种子作**强制续流源**，另有 `bestHandoffStart` 容错与"并入邻河谷"处理）。且 `borderStats` 量的本是 **chunk 边界**（每 16 格）水面差，**不是 region**。
     - **真正该记的**：jitter 的退化**不是**缺继承所致，而是改变排水后某处出现了 12.8 块的 **chunk 级**水面落差（未进一步定位）。⚠️ 当前 1.358 距容差 1.5 **仅 10% 余量** ⇒ 该指标对地形改动**高度敏感**：任何动地形的改动都应把 `runFlowAccumProbe` 的 `border` 与 `status` 列入验收。
- `CACHE_SCHEMA_VERSION` 41 → **44**（本次三处地形产出变更：折叠 / blurDist / stress；上条抖动实验的 45 已随回退撤销）。

## 当前工作焦点（2026-09-10 气候主导群系 + 河流绿洲 + 陡坡裸岩，发布 v0.0.1）

- **温度纬向锚点修复**（`CellGenerator.sample`）：旧 `sin²(z·tempFreq)` 在 z=0 恒取 −1（出生点极寒）且振荡、无单调梯度。改为复用 `Latitude.latitude01`：`temp = 1 − 2·lat`（赤道 +1 → 两极 −1）。实测 z=0 → +0.932，z=6000 → −0.851。
- **群系改气候主导**（`BiomeClassifier`）：由「switch(地形)」改为 **Whittaker 群区（温度×降水）× 垂直带谱 × 地形变体**。气候在抖动 Voronoi 气候区上采样（区内温湿恒定 → 无椒盐碎斑），区界用 5 倍频扰动打散；垂直带由温度相关雪线驱动（同一座山：赤道=雨林→草甸→石峰，寒带=针叶林→雪坡→冰峰）。`GeoGenesisBiomeSource.ALL_KEYS` 补全暖/冻海洋、针叶林、恶地、雪坡等漏项。
- **群系变体抖动**（`Cell.variantTerrain`）：地形类型边界是 Voronoi 垂直平分线（直线段），直接按 `terrainType` 换群系会让群系边界沿直线走（实测最长 **424 wu** 水平直线，即用户反复反馈的"直线"）。改为对 5 类地形权重各加独立噪声后取主导类型 → **424 wu → 104 wu**，回到纯气候基线，邻接违例 0。
- **河流绿洲**（RTG `SurfaceRiverOasis` 范式）：新增 `Cell.riverDistance`（到最近河/湖距离）与 `Cell.oasisNoise`。干旱群区沿水 <24 wu 且过大尺度噪声门控 + 海拔截断 → DESERT 转 SAVANNA（Whittaker 图上合法邻居）。
  - `RiverLineNetwork.distanceToWater` 为**只读**查询，**委托既有 `sample()`**：自写"扫全部河段"版本漏掉 `RiverLineRegion.lakes`（19/245 例比雕刻器远最多 191 wu），自检抓出后改委托，现差值恒为 0。
  - ★ **预览 = 游戏**：群系分类走快速路径 `sampleCellLight`（无侵蚀/雕刻，为把建世界从 7.5 分钟压到秒级），原本拿不到水文数据 → 绿洲规则只会在预览生效。现由 `GeoGenesisTerrain.fillRiverDistance` 在完整管线与快速路径用**同一条件**填充。实测 17 µs/次（侵蚀 tile 800ms 的 0.002%），性能假设成立。
- **陡坡裸岩**（RTF `Steepness` tile filter + `ErodeFeature` 范式）：新增 `Cell.gradient`，从侵蚀 tile 的 `postErosion` 高度网格取 ±1 wu 中心差分。**必须用 tile 网格而非 chunk 的 16×16**——chunk 边缘只能 clamp，会产生 16 块间距的接缝；tile 自带 padding 跨 tile 连续。`GeoGenesisGenerator` 落块处 `gradient > ROCK_GRADIENT(0.40)` → 铺 STONE，优先于 `surfaceOf`（沙漠里的陡崖同样是裸岩）。**未改动 `ErosionEngine`**（`postErosion` 本就存在）。
  - 阈值标定（384 wu 实测）：p50=0.008 p90=0.205 p99=0.499 max=0.731；>0.30 覆盖 6.3%、>0.45 覆盖 2.7% → 取 0.40。
- **发布 v0.0.1**：`gradle.properties` 的 `mod_version` 0.1.0-preview.1 → **0.0.1**；产物 `build/libs/geogenesis-0.0.1.jar`（`mods.toml` 用 `${mod_version}` 占位符，自动同步）。已删除旧版本号的构建产物避免误传。
- 新增探针：`runClimateBiomeProbe`（气候分异/邻接合法性/直线段与各向异性/精细群区图）、`runRiverOasisProbe`（距离查询一致性与耗时 + 坡度分布标定）。
- 已知遗留：`TerrainClass.RIVER` 全工程从未赋值 → `pickKey` 的 `case RIVER` 为死代码（河流靠 `riverSurfaceY` 灌水表现）**【已于 2026-09-11 清理：移除死分支 `case RIVER`，`TerrainClass.RIVER` 枚举保留以避免 ordinal 漂移破坏预览缓存格式；河流本就由灌水表现，不按 terrainType 分类】**；陡坡裸岩只在游戏地表可见，预览群系图层不显示（群系本身仍是森林，裸岩是地表属性，与 RTF 一致）。

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
    - ⚠️ **更正（2026-09-13）**：`coldMs=1849` **不得再当基线** —— 该值所属工况（region 数）未记录，且此后新增气候系统/河流绿洲/降水加权汇流/地质 T1~T5；当前实测 6048ms，**两者不可直接比较**（详见「当前工作焦点（2026-09-13）」的性能归因）。另 `reachedOcean` 现为 38/45（84.4%），该指标**不在 PASS 判据内**（判据 = cycles/profile/gate/border）。
  - **已知遗留**：border.maxSurfaceDelta 1.209→1.839、border.violations 0→**2**（容差 1.5，发生率 1.6e-7）——分支增多后穿出 region 边界的河段（19.27%）暴露"跨 region 水面无继承"既有范式遗留，实机不可见，未引入跨 region 继承机制，status=REVIEW 与历史基线一致。
    - ⚠️ **更正（2026-09-13）**：本条的「跨 region 水面无继承」与「未引入继承机制」**均已过时** —— 继承机制已于 **2026-09-07 加入**（`RiverLineRegion.OutletSeed` 携带 `surfaceY`/`accum`/`level`；`RiverLineNetwork.region()` 双-pass 吸收 4 邻出口作**强制续流源**，另有 `bestHandoffStart` 容错与"并入邻河谷"处理）。且 `borderStats` 量的是 **chunk 边界**（每 16 格）水面差，**不是 region**。
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
  - **待办（阶段 E）**：湖泊群系映射；瀑布跌水潭视觉验证；`runClient` 实机目检。
  - ⚠ **2026-09-15 核对修正**：原列于此的"Strahler 分级河宽"**前提已具备** ——
    本段上面已记"新增 Strahler 式层级：`RiverPolyline.level` + 构建期 `levelAt[]` 传递"
    （`RiverLineNetwork:419` 确有 `levelAt`），且宽度单调违例为 0。
    ⇒ 该条**不再是明确缺口**；若还要做"按层级/流量显式设宽"属**增强**而非补缺。

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
  - ⚠ **2026-09-15 核对**：全仓搜索 `plainsMinE` / `mountainsMaxE` / `*MinE` / `*MaxE`
    配置字段，**在 `src/main` 中已不存在**（只命中无关的 `RiverLineParams.sourceMinE`
    与 `GeoPalette.setElevationERange` 的形参）⇒ 本条**前提已失效**。
    若确认 mixer 各曲线已绑到过程参数，应直接删除本条；本次**未逐条核对
    `Factor.ConfigBinding` 的绑定目标**，故仅标注"前提失效"，不擅自删。

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
