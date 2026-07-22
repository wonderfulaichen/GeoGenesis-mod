# GeoGenesis 长期记忆

## 用户方法论偏好
- 参考模组先读透再提方案；严格先案后码；模块化/单一职责
- 知识沉淀必须做；每完成一次任务/对话做记录

## 地形类型核心原则（v6.0）
**域扭曲空间距离场 + 高斯权重 + 独立类型噪声 + 按 typeWeights 加权混合 eLand = 无断裂无网格无悬崖**

### 六层消除机制
1. 域扭曲空间距离（v4）
2. 高斯权重（v4）：`w = exp(-d²/2σ²)`，σ=CELL_SPACING/4=100
3. SEARCH_RADIUS=2（v5.2，5×5 = 25 cells）
4. 连续类型权重（v5）
5. 独立噪声配方（v5，每类型专属噪声节点树）
6. v6.0：按 typeWeights 加权混合各类型独立 eLand（非 lo/hi）

### 关键参数
- CELL_SPACING: 400 块
- WARP_AMP（Voronoi）: 250
- WARP_AMP（共享噪声）: 300
- SIGMA: 200
- SEARCH_RADIUS: **2**

### 各类型范围参数（v6.0 CENTER+RANGE 模式）
- PLAIN: [0.015, 0.06] 低平温和
- HILLS: [0.06, 0.25] 圆润起伏（双层Warp+Perlin×Billow^0.5）
- MOUNTAINS: [0.45, 0.95] 尖锐脊线
- PLATEAU: [0.20, 0.45] 边缘 smoothstep 渐变
- BASIN: [0.015, 0.08] 凹陷盆地

### 关键文件
- `VoronoiRegionField.java` — 域扭曲空间距离场 + 高斯权重
- `TypeGenerators.java` — 类型范围常量 + basinModulate
- `TypeNoiseProvider.java` — 每种类型独立噪声配方
- `TypeLandShape.java` — typeWeights 加权混合 → eLand
- `DiscontinuityProbe.java` — 梯度诊断工具

## 条件系统架构（2026-07-21）

### 三维度条件体系
- **温度**：5段（极寒/寒冷/温和/温暖/炎热），阈值字段：`tempFrozenThreshold`, `tempColdThreshold`, `tempWarmThreshold`, `tempHotThreshold`
- **湿度**：4段（干旱/半干旱/湿润/潮湿），阈值字段：`humidityDryThreshold`, `humiditySemiThreshold`, `humidityWetThreshold`
- **大陆性**：7段（深海/近海/沿海/过渡/近内陆/内陆/深内陆），阈值字段：`continentDeepOceanThreshold`~`continentInlandThreshold`

### 后端实现方式（样条化）
- `ClimateSpline` 用 Cubic Hermite 样条将原始值映射为连续区域权重
- 阈值作为样条控制点，tent 函数产生各区域权重（和=1）
- boolean 方法（`isFrozen/isCold/isHot/isDry`）保留兼容，内部委托给样条权重

### 条件在各子系统中的使用
1. **群系分类**：温度 `isFrozen/isCold/isHot` + 湿度 `isDry`
2. **地形分类**：温度样条权重参与 SNOW 类型判定（双曲线模型：`snowLine + (tNorm-0.5)×tempInf - (hNorm-0.5)×humInf`，冷+湿→雪线低易积雪）
3. **地形生成**：大陆性 `c` 用于大陆坡度 + 温度海洋性修正
4. **气候计算**：大陆性影响温度/湿度，海拔影响温度递减率
5. **雪线图表（双曲线，2026-07-22）**：X=温度[-1,1]，Y=雪线世界高度。
   干燥曲线（橙，湿度=-1）+ 湿润曲线（蓝，湿度=+1），两线之间渐变填充。
   4 个控制点（干燥冷/暖端、湿润冷/暖端），配对交互：拖干燥点调中线+温度敏感度，拖湿润点调湿度带宽。
   后端公式：`effectiveSnowElev = snowLine + (tNorm-0.5)×snowTempInfluence - (hNorm-0.5)×snowHumidityInfluence`
   CellGenerator.classify 和 classifyTerrain 均已接入双曲线模型。

### 影响权重（尚未接入后端）
- `tempInfluence`, `humidityInfluence`, `continentInfluence` 仅在气候页UI显示

### 样条统一决策（2026-07-21）
保持地形样条和气候样条分开，不统一。理由：语义不同（地形输出直接是高度值，气候输出是中间索引再转权重）、共享已通过SplineUtil实现、3层嵌套不适合气候需求。条件因素的联合是其他内容需要的。

## 配置同步铁律
增删字段须同步 **6 处**：
`GeoGenesisConfig`(定义+BUILDER+buildParams+defaultParams) + `TerrainParams`(record+defaults) + `ParameterConfigPanel.buildFromConfig`(addSpec) + `run/config/geogenesis-common.toml`
改默认值后必须同步已存在的 toml。

## 海洋深度乘数（oceanDepthFactor, 2026-07-22）
新增参数解决海陆比控制问题。根因：旧地形引擎 eLand 均值 ~0.046，新引擎 eLand 均值 ~0.45（10倍），而 eOcean 最大深度仅 -0.35，导致 continentBias 再大也无法突破 ~26% 海洋硬上限。
修复：oceanDepthFactor 直接乘到 eOcean（负值），>1=更深(海洋面积扩大)，<1=更浅(陆地扩大)。默认 1.0，范围 [0.5, 3.0]。
测试验证：factor=2.0,bias=0.4 → ~51% 海洋（50/50）；factor=1.5,bias=0.4 → ~31% 海洋；factor=0.5 → 全陆地。
影响 5 文件：TerrainParams(字段+defaults), GeoGenesisConfig(定义+builder+buildParams+defaultParams+reset), CellGenerator(sample+seed), ParameterConfigPanel(addSpec)。

## 关键工程陷阱

### Forge 1.20.1
- dev 运行 = `sourceSets.main`（官方映射），**禁止**把 reobf jar 拷进 `run/mods/`
- `f_XXXX_`/`m_XXXX_` NoSuchFieldError → 第一反应删除 `run/mods/` 里的 jar
- `NoiseColumn` 在 `net.minecraft.world.level` 包（非 levelgen）
- biome 是 dynamic(datapack) 注册表，解析须用 `ServerLifecycleHooks.getCurrentServer().registryAccess()`；**禁止缓存 Registry<Biome> 到静态字段**
- 叶子 CODEC 禁止 `.stable()`/`.withLifecycle()`（会导致 world_preset 解析崩溃）

### 地形
- `e→Y` 映射必须非对称（e=0→seaLevel=63）
- 独立预览用 `GeoGenesisConfig.defaultParams()` 而非 `buildParams()`
- 离散图层颜色数组必须与枚举同序同数
- **预览采样密度** `PreviewWorker.TARGET_SAMPLES = 144`（12×12 网格）；RIVER_NETWORK 等稀疏信号图层建议 ≥200

### 诊断经验
- 用户报"地形没变"时，先查 class 文件时间是否比源文件新 + `run/mods/` 是否被手放 jar
- 编译后必须 `gradlew compileJava --rerun-tasks` 验证全量编译
- **预览"看不到画面"调试三步**：1) 确认 widget 渲染（加可视边框/状态行） 2) 确认纹理上传（hasTexture / log pixel） 3) **对比像素值和背景色的 RGB 距离**（关键！色带最暗端 + 暗背景 = 隐形）
- 预览最暗端色带必须与背景色有 ≥30 的 RGB 距离（建议调色前算一下）
- 纹理上传直接写入构造时创建的 `NativeImage` + `texture.upload()`，不要反复创建/释放 `DynamicTexture`

## 重要重构历史

### 2026-07-16 类型系统重构
用地形类型系统取代省系统，每个类型有自己的噪声配方，类型间用样条嵌入噪声对象过渡。
- 新文件：`TypeGenerators.java`, `TypeLandShape.java`
- 删除：`LandShape.java`, `TypeMorphology.java`, `TerrainComposer.java`, `ClimateWeights.java`

### 2026-07-19 海洋特征 & OceanFeatures 重构
- 移除 per-cell eOcean gate，新增海山中心水深校验
- 海洋类型新增：CONTINENTAL_SHELF, SUBMARINE_RIDGE, SEAMOUNT
- 海洋地形类型共 7 水域类

### 2026-07-20 统一嵌套样条系统
对标 MC 原版 `offset.json`，整个地形系统是一个统一的嵌套样条树。
- 三层嵌套：外层大陆性 c → 中层地形类型分布 → 内层 lo/hi 形状控制
- 关键文件：`UnifiedSpline.java`, `SplineConfig.java`, `MidSplineConfig.java`, `OceanSplineConfig.java`
- 修复：三层线性插值、海洋类型独立控制、海洋类型跨类型影响

### 2026-07-21 气候样条系统实现
将离散阈值 boolean 判断替换为样条连续映射。
- 新增 `ClimateSpline.java`（Cubic Hermite 样条）
- 修改 `Climate.java`, `ClimateZone.java`, `CellGenerator.java`
- 纬度融入：样条操作的是最终温度值，已包含纬度基值

### 2026-07-22 双曲线雪线模型（温度×湿度）
新增 `SnowLineChart.java`（双曲线图表组件），SNOW 分类接入湿度条件。
- TerrainParams/GeoGenesisConfig 新增 `snowHumidityInfluence` 字段（默认0.15，范围[0,0.5]）
- ParameterConfigPanel 用 SnowLineChart 替换旧 SingleCurveChart
- CellGenerator.classify/classifyTerrain 加 humidity 参数，双曲线模型判定 SNOW
- **Y 轴公式**：SnowLineChart 使用 HeightCurve 海平面锚定公式 `seaLevel + ratio × (maxY - seaLevel)`，区别于旧线性 `yMin + ratio × (yMax - yMin)`。滑块反算公式 `/` 控制点配置同步全部对齐 seaLevel 锚定。
- charContentTop 对齐修复：雪线段从 104→120（差16px，对齐渲染起始 Y=panel.getY()+16+chartHeight+4）
- 编译 BUILD SUCCESSFUL

## 待办事项
- runClient 目检游戏内地形
- BASIN 尚未集成到阈值链
- 类型参数化：TypeLandShape 的阈值和 TypeGenerators 的噪声参数需从 TerrainParams 注入
- GeoGenesisConfigScreen 的省滑块需替换为类型滑块

## 配置屏标签页结构（2026-07-22）
**三页签按上游→下游排列：**
- 页签0「世界参数」：基础参数（海陆偏置/海床细节/分类阈值）+ 世界高度（柱状图+三个滑杆+尺度预览）
- 页签1「气候」：温度/湿度/大陆性 分类色条 + 影响滑块（不变）
- 页签2「地形」：类型 lo/hi 控制点图 + 雪线双曲线图（从参数页迁入）

## 确认对话框系统（2026-07-22）
- `ConfirmDialog.java`（`client/` 包）：通用覆盖层，半透明遮罩 + 深色绿边对话框
- 世界高度三个滑杆（最高/海面/底）已标记为 important：拖动松手弹确认框
- `ParamSlider` 新增 `onDragStart` 回调（拖动开始捕获旧值用于取消回滚）
- 取消→ `cfg.set(rollbackVal)` + `slider.setCurrentValue(rollbackVal)`
- 用 `ParameterConfigPanel.ShowConfirmCallback` 回调桥接到 Screen
- 确认框打开时阻断所有面板鼠标/滚轮事件

## 高度同步联动（2026-07-22）
- `TerrainConfigPanel.refreshHeightDependent()`：刷新地形图 e→Y 映射 + 雪线图 Y 范围/海平面
- `ParameterConfigPanel.refreshHeightDependent()`：刷新柱状图
- tab 切到地形页（tab=2）时自动调用 `terrainPanel.refreshHeightDependent()`
- mixer 面板重绑到新类型参数