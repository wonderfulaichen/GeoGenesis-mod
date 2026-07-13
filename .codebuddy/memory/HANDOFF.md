# GeoGenesis 项目交接文档

> 最后更新：2026-07-09 07:10
> 接收人：新对话的 AI 助手
> 交接人：当前会话的 AI 助手

## 项目概述

GeoGenesis 是一个 Minecraft Forge 1.20.1 模组，实现自定义 `ChunkGenerator` + `BiomeSource`，程序化生成地形。项目处于 **P5 阶段**（气候→群系已接游戏，预览 11 图层+数据驱动配色+调音台面板已完成）。

## 当前状态

### 核心完成项
1. **P2 零依赖地形引擎**（2026-07-07 完成）
   - 包 `worldgen/terrain/`：Cell、Size、Continent、HeightCurve、CellGenerator、GeoGenesisTerrain
   - 支持 `sampleHeight`/`getChunkCells`/`getRegionCells` API
   - 已修复地形断裂问题（TerrainBlender IDW 权重缺陷）

2. **独立预览窗口** `TerrainPreview.java`（已升级为 11 图层 + 数据驱动配色）
   - **11 个地理标准图层**：高程/温度/湿度/大陆性/地形起伏/水文场/纬度/气候带/群系/地形类型/海陆 + 水文叠加
   - **数据驱动配色**：`ColorMap` 色带 + `GeoPalette` 多内置调色板 + JSON 资源覆盖
   - **图例面板**：离散图层图例从 `GeoPalette` 离散映射生成，支持名称搜索/过滤
   - **悬停 tooltip**：坐标+类型+高度+水+气候+纬度+大陆性
   - **分辨率切换**：X 键切换采样分辨率

3. **侵蚀系统（已删除，2026-07-08）**
   - 用户决定：**优先气候系统，侵蚀直接删除**。
   - 包 `worldgen/erosion/`（11 个文件）已整包删除。
   - **未进游戏地形**：`fillFromNoise` 本就不经侵蚀，删除无功能回归。

4. **气候 → 群系接线（2026-07-08 完成）**
   - `BiomeMapper.pickKey` 委托 `BiomeClassifier.classify`，游戏行为不变。
   - `GeoGenesisBiomeSource` 通过 `HolderGetter<Biome>` 运行时解析群系。

5. **零依赖分类器（2026-07-08 完成）**
   - `BiomeClassifier`：`classify(Cell)→BiomeClass` 枚举，规则迁自 BiomeMapper
   - `ClimateZone`：Köppen 简版 `classify(Cell)→Zone{A/B/C/D/E}`
   - `Latitude`：`latitude01(worldZ)=clamp(|z|*0.0016,0,1)`

6. **数据驱动配色中枢（2026-07-08 完成）**
   - `ColorMap`：零依赖色带（停靠点 + Lab 插值 + bake LUT）
   - `GeoPalette`：PreviewLayer 注册表 + 多内置色带 + 离散映射 + 覆盖接口
   - `PreviewColor`：委托 GeoPalette 输出 11 图层 ABGR

7. **游戏内配置屏（2026-07-09 完成）**
   - `GeoGenesisConfigScreen`：左侧分页（地形/气候/参数）+ 右侧预览工具栏
   - `PreviewDisplay`：视图缓存 + early-abort 消拖拽卡顿 + 离散图层图例

8. **调音台面板（Mixer Panel，2026-07-09 完成）**
   - 包 `client/preview/mixer/`：Factor、ControlPoint、ConfigBinding、FactorMixer、FactorCurveChart、MixerPanel、FactorCategoryBar
   - 地形页：基础因素双曲线范围图（控制点可拖拽）+ 14 个控制点滑块
   - 气候页：温度/湿度/大陆性三个条件因素（分类色条 + 影响程度滑块，可折叠）
   - 参数页：噪声/尺度/海平面/雪线等基础参数滑块（含 tooltip + 单参数重置）
   - 条件因素范围已修正：温度 [0,1]、湿度 [0,1]、大陆性 [-1,1] 与实际数据对齐

> ⚠️ **群系解析铁律（曾崩溃）**：1.20.1 群系是动态注册表，禁止用 `ForgeRegistries.BIOMES.getValue` / `BuiltInRegistries` 静态解析 `Holder<Biome>`（运行时返回 null → `ExceptionInInitializerError`）。必须经由 `RegistryOps.retrieveGetter(Registries.BIOME)` 在 BiomeSource CODEC 解码时取 `HolderGetter<Biome>`，运行时再 `getOrThrow(Biomes.XXX)`。详见 MEMORY.md。

### 当前工作焦点
- **调音台面板**：基础因素曲线图持久化待接通（ConfigBinding setter 当前为 no-op，未写入 GeoGenesisConfig 真实参数如 PLAINS_MIN_E 等）
- **文档同步**：本交接文档已同步至 2026-07-09（调音台面板 + 条件因素范围修正）

## 本次会话完成的工作

### 1. 调音台面板实现（2026-07-09）
- 实现 `client/preview/mixer/` 包（Factor、ControlPoint、ConfigBinding、FactorMixer、FactorCurveChart、MixerPanel、FactorCategoryBar）
- 实现 `TerrainConfigPanel`（地形页：基础因素曲线图 + 控制点滑块）
- 实现 `BasicParamsPanel`（参数页：噪声/尺度/海平面/雪线等滑块）
- 集成到 `GeoGenesisConfigScreen`（三页标签布局）
- 修复：控制点拖拽乱跑、鼠标滚轮内容错乱、展开/收回失效、滑块悬停说明、重置按钮与滑块重叠等

### 2. 条件因素范围修正（2026-07-09）
- 温度：实际范围 [0,1]（BasicClimate clamp），分类色条对齐
- 湿度：实际范围 [0,1]（BasicClimate clamp），分类色条对齐
- 大陆性：实际范围 [-1,1]（Cell.continentNoise），分类色条对齐
- 修改文件：`FactorCategoryBar.java`（initRange）、`Factor.java`（分类定义）

### 3. 文档同步（2026-07-09）
- AGENTS.md：更新架构速览表（调音台面板文件）+ 工作焦点日期 + 包结构
- ARCHITECTURE.md：更新包结构（调音台/配置页）+ 进度说明（调音台面板 + 条件因素范围）
- HANDOFF.md：全面更新（8 项核心完成件、快捷键、工作焦点）

## 待办事项

### 高优先级
1. ~~**侵蚀去留确认**~~：已确认 **删除**（2026-07-08）
2. ~~**文档同步**~~：AGENTS.md / ARCHITECTURE.md / HANDOFF.md 已同步（2026-07-09）
3. ~~**群系验证**~~：`runClient` 已确认世界按气候分布多种群系（2026-07-08）
4. ~~**调音台面板**~~：已实现并集成（2026-07-09）

### 中优先级
1. **Config 持久化**：`ConfigBinding` setter 当前为 `v -> {}`（no-op），需接通到 `GeoGenesisConfig` 真实参数（如 `PLAINS_MIN_E`/`SHALLOW_OCEAN_DEPTH` 等），控制点拖拽后配置同步
2. **条件因素双向绑定**：条件因素滑块值与 ConfigBinding 双向同步
3. **地形类型重定义**：`TERRAIN_TYPE_SCHEME.md` 方案待编码（地形类型 = 海拔 × 起伏二维矩阵）

### 低优先级
1. **P3 阶段准备**：rivermap 程序化河流网络规划
2. **气候系统完善**：当前 BasicClimate 为雏形

## 重要技术细节

### 编译环境
- **JDK**：Java 21.0.11（Eclipse Adoptium）
- **Gradle**：8.8，使用 wrapper `gradlew.bat`
- **Forge**：1.20.1-47.4.10-mdk
- **特殊配置**：`build.gradle` 添加 `-Xlint:-removal` 抑制弃用警告

### 关键文件
1. **配色中枢**：`client/preview/GeoPalette.java`（PreviewLayer 注册表 + 多内置色带 + 离散映射 + 覆盖接口）
2. **色带**：`client/preview/ColorMap.java`（Lab 插值 + bake LUT）
3. **着色外观**：`client/preview/PreviewColor.java`（委托 GeoPalette，输出 11 图层 ABGR）
4. **MC 预览**：`client/preview/PreviewDisplay.java`（视图缓存 + early-abort + 离散图层图例）
5. **Swing 预览**：`client/preview/TerrainPreview.java`（11 图层 + 图例搜索 + 分辨率）
6. **配置屏**：`client/GeoGenesisConfigScreen.java`（三页标签 + 右侧工具栏）
7. **调音台**：`client/preview/mixer/` 包（FactorMixer + MixerPanel + FactorCurveChart）
8. **分类器**：`worldgen/climate/` 包（BiomeClassifier + ClimateZone + Latitude，零依赖）
9. **地形引擎**：`worldgen/terrain/GeoGenesisTerrain.java`（`getRegionCells`/`sampleHeight` API）
10. **群系映射**：`worldgen/generator/BiomeMapper.java`（委托 BiomeClassifier，游戏行为不变）
11. **资源重载**：`client/preview/GeoGenesisColorReloadListener.java`（JSON 资源包覆盖 GeoPalette 默认）

### 运行命令
```bash
# 编译
cd forge-1.20.1-47.4.10-mdk
.\gradlew.bat compileJava

# 预览窗口（种子 12345）
.\gradlew.bat runPreview --args=12345

# 完整构建
.\gradlew.bat build
```

### 键盘快捷键

#### 独立预览窗口（TerrainPreview）
- `1`~`9`/`0`：切图层（1=高程, 2=温度, 3=湿度, 4=大陆性, 5=地形起伏, 6=水文场, 7=纬度, 8=气候带, 9=群系, 0=地形类型，`-`=海陆）
- `[`/`]`：前后切换图层
- `R`：水文叠加开/关
- `X`：分辨率切换
- `C`：清空图例搜索
- `H`/`T`/`B`/`C`/`L` 等字母别名快捷键

#### 游戏内配置屏（GeoGenesisConfigScreen）
- `[`/`]`：切图层
- `R`：水文叠加开/关
- 右侧工具栏：图层切换 `[◀] [当前图层名] [▶]`、色带、水文开关

## 下一步建议

### 立即行动
1. ~~**验证群系**~~：已完成，`runClient` 实机确认世界按气候分布多种群系
2. ~~**文档同步**~~：AGENTS.md / ARCHITECTURE.md / HANDOFF.md 已同步（2026-07-09）

### 短期计划
1. **Config 持久化**：`ConfigBinding` setter 接通到 `GeoGenesisConfig` 真实参数，控制点拖拽后配置同步到 saveConfig
2. **条件因素双向绑定**：条件因素滑块值与 ConfigBinding 双向同步
3. **地形类型重定义**：编码 `TERRAIN_TYPE_SCHEME.md` 方案（地形类型 = 海拔 × 起伏二维矩阵）

### 长期计划
1. **P3 阶段**：rivermap 程序化河流网络
2. **气候系统完善**：完善 BasicClimate 温度/湿度计算

## 用户偏好与纪律

### 方法论偏好
1. **参考项目优先看原版/鼻祖**：TerraForged 0.3.x 是 ReTerraForged 的鼻祖
2. **引用参考模组时先完整读透再提方案/写代码**
3. **知识沉淀必须做**：关键技术改进/重要参考/决策，主动整理笔记

### 工作规范
1. **确认机制**：需求模糊/逻辑冲突/多种技术选型时先问再干
2. **先案后码**：复杂功能先出技术方案，等回复确认再编码
3. **工程规范**：模块化、单一职责、单函数 ≤80 行

### 安全与 Git
1. **生成项目必配 `.gitignore`**
2. **禁止硬编码密钥**，统一用环境变量
3. **不要擅自提交**，除非用户明确要求

## 文件清单

### 关键代码文件
- `TerrainPreview.java`：独立预览窗口（本次会话主要修改）
- `GeoGenesisTerrain.java`：地形引擎（添加 applyErosion 参数）
- `GeoGenesisGenerator.java`：生成器（fillFromNoise）
- `build.gradle`：添加 `-Xlint:-removal`

### 文档文件
- `PREVIEW_LEGEND.md`：图例机制文档（本次会话创建）
- `ARCHITECTURE.md`：架构文档（需核对更新）
- `AGENTS.md`：项目指南（需核对更新）
- `PLAN.md`：重构计划（需核对更新）

### 工作记忆文件
- `MEMORY.md`：长期记忆（已更新 TerrainPreview 实现状态）
- `2026-07-08.md`：今日日志（已记录所有修复）
- `HANDOFF.md`：本交接文档

## 注意事项

1. **编译警告**：`ResourceLocation(String)` 等 forRemoval 警告已用 `-Xlint:-removal` 抑制，发布前需统一迁移
2. **侵蚀状态**：`worldgen/erosion/` 包已于 2026-07-08 **删除**（用户决定优先气候系统）
3. **文档漂移**：AGENTS.md 等文档不会自动更新，重构后需人工核对
4. **预览窗口**：`gradlew runPreview` 是 JavaExec，不启动 Minecraft，纯 Java 预览

## 联系方式

如有问题，请参考：
- `MEMORY.md`：长期记忆和用户偏好
- `2026-07-08.md`：今日详细工作记录
- `PREVIEW_LEGEND.md`：图例系统详细文档