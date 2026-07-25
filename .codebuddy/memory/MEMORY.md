# GeoGenesis 长期记忆

## 用户方法论偏好
- 参考模组先读透再提方案；严格先案后码（复杂功能先出方案待确认再码）；模块化/单一职责
- 知识沉淀必须做；每完成一次任务/对话做记录
- 关键决策点用提问确认（不打断则默认授权"你决定"）

## v14 修复核心原则（2026-07-25 钉死）
**TypeLandShape 使用共享噪声公式（非 per-type 独立噪声）**
- 公式：`eLand = blendLo + (blendHi-blendLo)·sharedNoise` 其中
  - `blendLo = Σ_t w_t(c)·lo_t(c)`，`blendHi = Σ_t w_t(c)·hi_t(c)`
  - `sharedNoise` 是单条连续 FBM（域扭曲+4 频率混合）
- **数学保证连续**：连续权重 Σ 连续值 × 单条连续噪声 = 连续，per-block Δe ~0.001（<1 块）
- **断裂根因**：v7-v13 的 per-type 独立噪声加权 `eLand = Σ w_t·H_t(noise_t)`，5 类独立噪声随机梯度叠加 → Δe 可达 0.04-0.07 e（15-27 块跳变）
- **cAffinityStrength 默认 0.0**：关闭 c→权重放大（消除权重被压制到 0.01 的突跳风险；可在 UI 调高）
- **类型差异**：HILLS 圆润/MOUNTAINS 脊线由 sampleByType 内层样条 lo/hi 边界 + c 亲和度权重表达；地表纹理由 BiomeClassifier + 装饰噪声负责
- 关键参数：CELL_SPACING=400, WARP_AMP(Voronoi)=250, SIGMA=150, SEARCH_RADIUS=2
- 类型范围：PLAIN[0.015,0.06] HILLS[0.06,0.25] MOUNTAINS[0.45,0.95] PLATEAU[0.50,0.66] BASIN[0.015,0.08]

## 条件系统（2026-07-21）
- 温度(5段)/湿度(4段)/大陆性(7段)，后端用 `ClimateSpline`(Cubic Hermite) 连续映射，boolean 方法委托样条权重
- 大陆性 7 段阈值字段：`continentDeepOceanThreshold`~`continentInlandThreshold`
- 雪线双曲线（2026-07-22）：`effectiveSnowElev = snowLine + (tNorm-0.5)×snowTempInfluence - (hNorm-0.5)×snowHumidityInfluence`，Y 轴用 `seaLevel + ratio×(maxY-seaLevel)` 锚定

## 类型过渡原则（2026-07-25 v10 钉死）
- **无细胞格子，无高斯窗口，无 smoothstep**：`VoronoiRegionField` v10 彻底删除了 CELL_SPACING/SEARCH_RADIUS/SIGMA/TYPEOV
- **类型中心线性内插**：在每个 (wx,wz) 直接采样 typeField，然后在相邻两个类型中心之间线性内插权重
  - 中心位置：BASIN=0.03, PLAIN=0.12, HILLS=0.35, PLATEAU=0.58, MOUNTAINS=0.72
  - 过渡完全由 typeField 自身的梯度自然决定，无任何人为过渡带参数
- xz 坐标（域扭曲）只用来让类型边界蜿蜒，**不产生跳变**
- 各类型的高度由各自的密度函数（高度 e）自然决定如何衔接——两边的连续运动轨迹自然衔接
- 公式：`eLand = (1-t)·H_i(noise_i) + t·H_{i+1}(noise_{i+1})`，t 为 typeField 在两个中心间的线性位置
- 效果：最差情况 Δe/block < 0.013 e（≈5 blocks），远低于旧 smoothstep 的 0.06/block（≈18 blocks）

## 配置同步铁律
增删字段须同步 6 处：`GeoGenesisConfig`(定义+BUILDER+buildParams+defaultParams) + `TerrainParams`(record+defaults) + `ParameterConfigPanel.buildFromConfig`(addSpec) + `run/config/geogenesis-common.toml`；改默认值后必须同步已存在 toml。
- **`resetToDefault()` 已反射自动化（2026-07-24）**：改为 `getFields()` 遍历所有 `ForgeConfigSpec.ConfigValue` 字段统一 `set(getDefault())`，新增字段**自动覆盖**，不再需要手工在 reset 里逐字段补 `.set()`。因此"增删字段须同步 resetToDefault"这一约束已废弃；但 `defaultParams()`（预览默认）与 `buildParams()`（引擎注入）仍需随字段同步。`SPEC`/`INSTANCE`(非 ConfigValue) 与 private `midSplineConfig` 会被自动跳过。
- **滑块 reset 默认标记铁律（2026-07-24）**：`ParamSlider.setDefaultValue(...)` 必须填 `cfg.getDefault()`（代码默认值），**严禁填 `cfg.get()`**（config 当前/持久化值）。`resetToDefault()` 把滑块归位到该标记；若误用 `get()`，一旦当前值被拖动或 toml 持久化为低位，点重置就会把滑块拉到最低而非代码默认。诊断同类 bug 的方法是：枚举全仓所有 `setDefaultValue` 调用，核对每个是否用 `getDefault()`。

## 关键参数/陷阱
- **【铁律·大陆性 c 只作条件因素，绝不控制地形高度】**（2026-07-25 用户反复纠正后钉死）：
  - 大陆性 `c` **只做两件事**：(1) 决定 (x,z) 出现什么类型（Voronoi 区域→typeWeights）；(2) 决定海陆位置关系（c<0=海洋域、c>0=陆地域，即海陆 mask）。
  - **地形高度由类型各自的独立噪声全权决定**：陆地 `eLand = Σ w_t·H_t(noise_t)`（TypeLandShape），海洋深度由"离海岸距离的位置函数"单调推导（对齐旧范式 `computeOceanDepth`，非 c 直射）。
  - **海岸线 = e 场自然穿过 0 的等值线**，由 blend mask 平滑过渡，不绑定 c 的硬阈值锚点。
  - **反复踩的坑（已修）**：① `eBase = heightCurve.eFromC(cBiased)` 的海洋样条 `shallowDeriv=0.0` 局部水平→浅蓝平涂平台（CONTINENTAL_SHELF），已改默认 0.5 消除；② `continentalSlope = clamp(cEdge*0.08,0,0.15)` 把 c 直接加进陆地高度（"大陆按 c 斜升"），已整体删除。
  - **引入任何新功能时自查**：若逻辑是 `if c > X then e = f(c)` 或 `e += g(c)`，即违规。c 只能进海陆 mask（smoothstep 阈值）与类型选择，绝不进高度。
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
- **按钮语义（2026-07-24 续10）**：三按钮 [应用][保存][重置]。**应用**=`SPEC.save()`(全局 toml 持久化)+`setScreen(parent)` 返回创建界面（世界即用此配置）；**保存**=弹 `NameInputDialog` 把当前完整配置存为命名自定义预设到 `config/geogenesis/user_presets.json`(`UserPresetsStore` 单例 Gson)；**重置**=`resetToDefault()`。**onClose 不再困住用户**（直接返回父屏，未提交改动丢弃，契合"应用才提交"）。`create(Minecraft,parent)` 已改为传 parent 使 Mods 菜单配置也能返回。自定义预设与内建 `PresetLibrary` 并存；PresetsPanel 第三节"我的预设"可加载(→`applyUserPreset`:reset+applyNamedValues+重建面板+rebuildPreview+save)/删除。
- `GeoGenesisConfig` 反射三件套：`resetToDefault()`(set 默认) / `captureAllValues()`(字段名→当前值) / `applyNamedValues(Map<String,String>)`(按名字 set，parseByType 按运行时类型解析)。`SPEC`/`INSTANCE` 与 private `midSplineConfig` 自动跳过。

## 待办
- **[DONE] v14 修复**：TypeLandShape 回归 v6.5 共享噪声公式 + cAffinityStrength 默认 0.0。用户目测无断裂，诊断日志 maxFe=0.010-0.021（4-8 块）。已提交 33bae42。
- [PENDING] 恢复侵蚀/河流刻蚀代码注释（GeoGenesisTerrain.generateChunk 注释块内），待用户确认进入侵蚀/河流阶段。
- [PENDING] 测试 cAffinityStrength 设回非零值是否安全。
- [PENDING] BASIN 类型曲线检查；mixer 面板重绑新类型参数；toml→JSON 迁移规划（阶段3）。
