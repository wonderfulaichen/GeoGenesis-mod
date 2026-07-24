# GeoGenesis 长期记忆

## 用户方法论偏好
- 参考模组先读透再提方案；严格先案后码（复杂功能先出方案待确认再码）；模块化/单一职责
- 知识沉淀必须做；每完成一次任务/对话做记录
- 关键决策点用提问确认（不打断则默认授权"你决定"）

## 地形类型核心原则（v6.0，WIE）
**域扭曲空间距离场 + 高斯权重 + 独立类型噪声 + 按 typeWeights 加权混合各类型独立 eLand = 无断裂无网格无悬崖**
- 公式：`eLand = Σ_t w_t·H_t(noise_t) / Σw_t`，`H_t` = 类型 t **自己的**内层样条 lo/hi
- **绝不经"类型轴位置 typePosition"插值高度**（那是尖环根因）
- 六层消除机制：域扭曲 / 高斯权重 σ=CELL_SPACING/4 / SEARCH_RADIUS=2(5×5) / 连续权重 / 独立噪声 / 加权混合
- 关键参数：CELL_SPACING=400, WARP_AMP(Voronoi)=250, WARP_AMP(共享)=300, SIGMA=200, SEARCH_RADIUS=2
- 类型范围：PLAIN[0.015,0.06] HILLS[0.06,0.25] MOUNTAINS[0.45,0.95] PLATEAU[0.50,0.66] BASIN[0.015,0.08]
- `LAND_TYPES=[PLAIN,HILLS,MOUNTAINS,PLATEAU,BASIN]` 顺序 == `midSpline.nodes` 构建顺序（对齐 sampleByType 索引）

## 条件系统（2026-07-21）
- 温度(5段)/湿度(4段)/大陆性(7段)，后端用 `ClimateSpline`(Cubic Hermite) 连续映射，boolean 方法委托样条权重
- 大陆性 7 段阈值字段：`continentDeepOceanThreshold`~`continentInlandThreshold`
- 雪线双曲线（2026-07-22）：`effectiveSnowElev = snowLine + (tNorm-0.5)×snowTempInfluence - (hNorm-0.5)×snowHumidityInfluence`，Y 轴用 `seaLevel + ratio×(maxY-seaLevel)` 锚定

## 配置同步铁律
增删字段须同步 6 处：`GeoGenesisConfig`(定义+BUILDER+buildParams+defaultParams) + `TerrainParams`(record+defaults) + `ParameterConfigPanel.buildFromConfig`(addSpec) + `run/config/geogenesis-common.toml`；改默认值后必须同步已存在 toml。
- **`resetToDefault()` 已反射自动化（2026-07-24）**：改为 `getFields()` 遍历所有 `ForgeConfigSpec.ConfigValue` 字段统一 `set(getDefault())`，新增字段**自动覆盖**，不再需要手工在 reset 里逐字段补 `.set()`。因此"增删字段须同步 resetToDefault"这一约束已废弃；但 `defaultParams()`（预览默认）与 `buildParams()`（引擎注入）仍需随字段同步。`SPEC`/`INSTANCE`(非 ConfigValue) 与 private `midSplineConfig` 会被自动跳过。
- **滑块 reset 默认标记铁律（2026-07-24）**：`ParamSlider.setDefaultValue(...)` 必须填 `cfg.getDefault()`（代码默认值），**严禁填 `cfg.get()`**（config 当前/持久化值）。`resetToDefault()` 把滑块归位到该标记；若误用 `get()`，一旦当前值被拖动或 toml 持久化为低位，点重置就会把滑块拉到最低而非代码默认。诊断同类 bug 的方法是：枚举全仓所有 `setDefaultValue` 调用，核对每个是否用 `getDefault()`。

## 关键参数/陷阱
- **oceanDepthFactor**（2026-07-22）：乘到 eOcean，>1 更深(海洋扩大) <1 更浅。默认1.0 [0.5,3.0]。解决海陆比硬上限。
- `e→Y` 必须非对称（e=0→seaLevel=63）；独立预览用 `defaultParams()` 非 `buildParams()`
- Forge 1.20.1：`sourceSets.main` 运行，**禁止**把 reobf jar 拷进 `run/mods/`；`f_/m_` NoSuchFieldError → 删 `run/mods/` jar；biome 注册表用 `registryAccess()` 禁止缓存到静态；叶子 CODEC 禁 `.stable()`
- `Screen.mouseMoved` 不向子 widget 转发 → hover 应在 `renderWidget` 内每帧用 `mx,my` 反算
- 预览最暗端色带与背景 RGB 距离 ≥30；纹理上传写构造时 NativeImage+`texture.upload()` 勿反复创建
- 编译后 `gradlew compileJava --rerun-tasks` 全量验证

## 重要重构历史
- 2026-07-16 类型系统取代省系统（TypeGenerators/TypeLandShape）
- 2026-07-20 统一嵌套样条（UnifiedSpline/SplineConfig/MidSplineConfig/OceanSplineConfig，3 层 c→类型→lo/hi）
- 2026-07-21 气候样条化；2026-07-22 双曲线雪线

## 完全自定义架构（层次 B，2026-07-24 决策）
**目标**：类型集合/噪声配方/lo-hi/位置/权重全 JSON 描述，增删类型零代码。
**中层建模决策 = 语义亲和度**：每类型声明 c 响应（用现有 MidSplineConfig 每类型 7 点 c 曲线表达，非单高斯——BASIN 双峰证明单高斯不够）+ 后期 relativeWeight。
**尖环根因（已钉死）**：`TypeLandShape.sampleFromUnifiedSpline` 用 typePosition 轴插值 lo/hi，高原↔丘陵过渡经 MOUNTAINS 节点(0.5)污染出 [0.45,0.95]。且 `UnifiedSpline` 内层样条跨外层节点共享 → c 当前对类型高度**无作用**，中层权重是死字段。
**修正方案（WIE 还原 v6.0）**：`eLand = Σ_t w_t·H_t(noise_t)/Σw_t`，`H_t` 取类型 t 自己内层样条，删 typePosition 轴 → 尖环根治且行为近似现状（纯类型 cell 完全一致）。
**阶段划分**：
- 阶段1（即时、低风险、行为保持）：TypeLandShape 改 WIE + UnifiedSpline 加 `sampleByType(c,typeIndex,noise)`；仅修复尖环，不引入 c 亲和度。
- 阶段2（语义亲和度接入）：空间权重 × `cAffinity_t(c)`（来自 MidSplineConfig 每类型 c 曲线）→ 山脉偏内陆/盆地深海+内陆；暴露 cPref/cSigma 或曲线到 UI；WARP_AMP 提为 TerrainParams 可配(默认~180)；消除双轨(TerrainParams *Center/*HalfRange 删，预览色阶从 spec lo/hi 派生)【**2026-07-24 已完成**】。
- 阶段3（类型增删零代码）：TerrainTypeSpec(JSON)+Registry 加载 config/geogenesis/terrain_types.json（资源包可覆盖）；引擎 LAND_TYPES/buildXxxInner/cellType 硬编码阈值改遍历 registry；relativeWeight 取代 TYPE_THRESH_*（保留地理约束：PLATEAU 需高地邻/MOUNTAINS 需 HILLS 缓冲/BASIN 非高地，重实现为抽样偏置）；BiomeClassifier 也数据驱动。
**死代码清理（阶段2/3 明确删除）**：UnifiedSpline 3 层 + SplineConfig.build + MidSplineConfig 整条（fromTerrainParams 已是桩 line311-315）；TypeLandShape `dominant.isWater()` 死分支删（typeWeights 只含陆地）。
- **【2026-07-24 完成】双轨死链删除**：TerrainParams 删 10 个 `*Center/*HalfRange` 字段 + defaults 实参 + `elevationERange()` 改读 `SplineConfig.maxLandHi()`；TypeGenerators 删 `lo/hi` 数组/`setRange`/`getTypeLo/Hi`；VoronoiRegionField 删 `generators` 字段+参数+lo/hi 计算（保留 `BlendResult.lo/hi` 字段=0 供 DiscontinuityProbe 兼容）；TypeLandShape 删 `sampleFromTypeWeights` + 简化 `sample()`；GeoGenesisConfig 删 10 字段/builder/buildParams/defaultParams/reset；TerrainConfigPanel 陆地 5 slot 实为**可编辑**（`landSplineSlot` lo+hi 带 `loCfg::set` setter，非只读）；其 reset 默认标记此前误用 `get()`（见滑块 reset 铁律），2026-07-24 已改为 `getDefault()` 与海洋一致；toml 删 10 key。**发现 PresetLibrary.java 5 个预设大量引用已删的 `*Center/*HalfRange`（这些预设项本是死链，从未影响地形），已改为仅保留仍生效字段（continentBias/oceanDepthFactor/*ReliefAmp/elevHigh/reliefHigh/snowLine/peakE）**。`compileJava --rerun-tasks` BUILD SUCCESSFUL。

## 配置屏（2026-07-23 单屏 8 标签，常驻预览）
页签0世界参数 / 1气候 / 2地形(lo/hi图+雪线双曲线) / 3显示 / 4采样 / 5色带 / 6缓存 / 7群系。标签条手写仿制（深绿主题）。ConfirmDialog 用于世界高度三滑杆松手确认。

## 待办
- runClient 目检地形；BASIN 未集成阈值链；TypeLandShape 阈值/TypeGenerators 噪声参数需从 TerrainParams 注入；配置屏省滑块→类型滑块；mixer 面板重绑新类型参数；toml→JSON 迁移规划（阶段3）。
