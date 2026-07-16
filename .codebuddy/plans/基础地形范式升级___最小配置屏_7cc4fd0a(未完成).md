---
name: 基础地形范式升级 + 最小配置屏
overview: 两阶段：A) 按地理学重构"侵蚀前基础地形"——LandShape 由混合各省高度改为产出 elevation+relief 双场，classify 改海拔×起伏二维矩阵，SNOW 改海拔×温度×纬度气候耦合覆盖层，同步 toml 过期振幅与新增 relief/分类阈值配置；B) 最小可用配置屏——把现行 stub（GeoGenesisConfigScreen 仅 2.4KB、mixer 整包缺失）重写为带"地形/参数"分页 + ParamSlider 滑块 + PreviewDisplay 实时预览 + 保存到 toml 的功能屏，暴露新模型全部关键参数（省海拔剖面、relief 振幅、省权重、雪线、分类矩阵阈值）。验证：runPreview 地形类型图层 + runClient 飞行目检。
design:
  styleKeywords:
    - Minecraft Forge 原生配置屏
    - 深色半透明面板
    - 分区主题色
    - 实时预览防抖
    - 扁平圆角按钮
  fontSystem:
    fontFamily: Minecraft 默认字体（拉丁）/ 思源黑体（中文回退）
    heading:
      size: 18px
      weight: 700
    subheading:
      size: 14px
      weight: 600
    body:
      size: 12px
      weight: 400
  colorSystem:
    primary:
      - "#00c896"
      - "#141820"
      - "#555555"
    background:
      - "#000000"
      - "#141820"
      - "#1e2430"
    text:
      - "#FFFFFF"
      - "#AAAAAA"
    functional:
      - "#66cc66"
      - "#996633"
      - "#cc9966"
      - "#9988aa"
todos:
  - id: landshape-relief-fields
    content: LandShape 改为产出 elevation+relief 两场，新增每省 relief 振幅与 detail 噪声
    status: completed
  - id: cellgen-classify-snow
    content: CellGenerator 装配 e=eOcean+eLand，classify 改海拔×起伏矩阵+PEAK子型，新增 applySnowLine 写 isSnow
    status: completed
    dependencies:
      - landshape-relief-fields
  - id: config-sync-relief
    content: 同步 toml/TerrainParams.defaults()/GeoGenesisConfig 的振幅+省权重+新增 relief4/分类阈值3/snowLatInf1
    status: completed
    dependencies:
      - cellgen-classify-snow
  - id: biome-snow-overlay
    content: BiomeClassifier 雪/冷判据改用 cell.isSnow 覆盖层
    status: completed
    dependencies:
      - cellgen-classify-snow
  - id: config-screen-build
    content: 重写 GeoGenesisConfigScreen 为功能屏（分页+ParamSlider+PreviewDisplay+种子+保存/重置）
    status: completed
    dependencies:
      - config-sync-relief
      - biome-snow-overlay
  - id: compile-verify
    content: 运行 gradlew compileJava 全量编译验证
    status: pending
    dependencies:
      - config-screen-build
  - id: preview-type-check
    content: runPreview 切地形类型图层目检 12 类分布并迭代微调数值
    status: pending
    dependencies:
      - compile-verify
  - id: client-flythrough
    content: runClient 飞行目检地形多样性、山峰高度、雪线随纬度与配置屏预览保存
    status: pending
    dependencies:
      - preview-type-check
---

## 用户需求

玩家在游戏中观察到陆地地形类型"都差不多、类型单一"，预期应有平原/丘陵/高原/山脉/山峰/雪峰等多种地形。经多轮诊断与联网调研，根因不是单纯数值问题，而是当前"侵蚀前基础地形"用**单一高度轴**建模：

- `LandShape` 把各省"高度"做 softmax 凸组合，抹平了起伏信息；单一 `eLand` 上限被压到约 0.6，导致山脉/峰/雪峰分类分支数学上不可达。
- `classify` 用单一 `eLand` 阈值分级；`SNOW` 用纯海拔+造山带阈值，而 `Cell.isSnow` 字段存在却从未赋值，`BiomeClassifier` 用 `isCold()` 而非 `isSnow`（文档要求的"海拔×温度×纬度雪线覆盖层"从未实现）。
- `run/config/geogenesis-common.toml` 振幅是旧低值，覆盖了 `GeoGenesisConfig` 已设好的高值。
- 配置 UI 整包（`client/preview/mixer/*`）在现行工作区**全部缺失**，`GeoGenesisConfigScreen` 仅为占位 stub。

用户明确要求：按**地理学**做**更好的**侵蚀前基础地形（参考旧文档但超越它，并参考联网调研结论），且**新模型的参数必须接入配置 UI**（不能只做裸 toml 字段）。

## 核心特性

- **双场合成**：`LandShape` 由"混合高度"改为产出 `elevation`（各省海拔基面 softmax 混合）与 `relief`（各省起伏幅度 × 细节噪声 softmax 混合）两个独立场；`eLand = elevation + relief` 作为侵蚀前基础高度。
- **海拔×起伏二维分类**：`classify` 按"高/低海拔 × 平/崎岖"判定——高且崎岖=山脉/峰、高且平=高原、低且崎岖=丘陵、低且平=平原、盆地省主导且低洼=盆地；山脉与高原靠 relief 在同海拔区分，彻底摆脱单一高度轴。
- **气候耦合雪线覆盖层**：新增 `applySnowLine`，由海拔 × 温度（温度含纬度）写入 `Cell.isSnow`，`BiomeClassifier` 改用 `isSnow` 判雪。
- **配置修复与扩展**：同步 toml 过期振幅到 builder 高值；新增每省 relief 振幅、分类矩阵阈值、雪线纬度耦合等配置项（六处同步铁律）。
- **最小可用配置屏**：从零重写 `GeoGenesisConfigScreen` 为功能屏（分页 + ParamSlider 滑块组 + PreviewDisplay 实时预览 + 保存/重置），暴露新模型全部关键参数，复用既有 `ParamSlider` 与 `PreviewDisplay`，不重建已遗失的 Factor/控制点曲线编辑器架构。
- **验证**：`runPreview` 地形类型图层目检 12 类分布；`runClient` 飞行目检地形多样性、山峰高度与雪线随纬度变化，并验证配置屏实时预览与保存。

## 技术栈

- 语言：Java 17（Forge 1.20.1 / Minecraft 1.20.1）
- 配置：`ForgeConfigSpec`（COMMON 配置 + `run/config/geogenesis-common.toml` 覆盖）
- 地形引擎：纯 Java 零 MC 依赖（`LandShape` / `CellGenerator` / `TerrainParams` / `Cell`）
- 配置 UI：Minecraft `Screen` + `AbstractWidget` + 既有 `ParamSlider` / `PreviewDisplay`
- 验证：`gradlew compileJava` + `runPreview`（地形类型图层）+ `runClient`

## 实现思路

把"侵蚀前基础地形"从单一连续高度场，重构为**地质省特征连续合成**的两场模型，遵循联网调研结论：

1. **建模地质特征而非最终海拔**（对齐 Tectonic Uplift 论文"model uplift, not elevation"）：每省 `i∈{craton,belt,plateau,basin}` 产出两个独立量——特征海拔基面 `baseEᵢ`（沿用现有过程形态区间）与特征起伏幅度 `reliefAmpᵢ`（新增：craton~0.12、belt~0.30、plateau~0.03、basin~0.05，即 danbgray 的 variation），并各带细节 FBM 噪声 `detailᵢ(x,z)∈[-1,1]`（新增每省高频噪声或复用现有 beltHigh/platMid 等）。
2. **softmax 混合"特征"而非"高度"**：`elevation = Σ wᵢ·baseEᵢ`，`relief = Σ wᵢ·(reliefAmpᵢ·detailᵢ)`，`eLand = elevation + relief`。belt 省区域自然得"高海拔+高起伏"，plateau 省得"高海拔+低起伏"，craton 得"低海拔+中低起伏"，起伏信息不再被平均掉（否定当前抹平 relief 的凸组合高度做法）。
3. **保持既有海陆统一加法模型**：`e = eOcean + eLand`，海岸线由 `e` 场自然过零涌现（符合 terrain-rebuild §1.4），不引入硬边界。
4. **海拔×起伏二维分类**（classify）：

- 前置：`e<0`→OCEAN/DEEP_OCEAN；`0<e<0.03`→BEACH（不变）。
- `high = elevation > ELEV_HIGH(0.45)`，`rugged = |relief| > RELIEF_HIGH(0.10)`。
- `wBasin>0.40 && elevation<0.08`→BASIN；`high && rugged`→(elevation>PEAK_E(0.82)?PEAK:MOUNTAINS)；`high && !rugged`→PLATEAU；`!high && rugged`→HILLS；否则 PLAIN。PEAK 仅作 MOUNTAINS 子型。

5. **气候耦合雪线覆盖层**（applySnowLine）：`effSnow = seaLevel + (snowTop - seaLevel)·(0.5 - 0.5·temperature)`；`Cell.isSnow = isLand && height >= effSnow`。温度已含纬度信息（`CellGenerator.sample` 用 `tempLatitudeScale` 由世界 Z 算温度）。

## 配置同步（六处铁律）

- 现有 land 振幅：toml 同步 builder 高值（`beltPeak=0.95`、`plateauBase=0.55`、`plateauTop=0.72`、`hillsHigh=0.25`、`beltFoothill=0.15`、`hillsLow=0.10`）。
- 省权重：craton/belt/plateau 适度 sharpen（候选 1.5/2.5/1.2，basin 0.8）让各省区域更分明。
- 新增 relief 4 项 + 分类阈值 3 项（`elevHigh`/`reliefHigh`/`peakE`）+ `snowLatitudeInfluence` 1 项：同步 `GeoGenesisConfig`(BUILDER 默认 + `defaultParams()`) + `TerrainParams.defaults()` + `geogenesis-common.toml` 三处。

## 最小可用配置屏（重写 GeoGenesisConfigScreen）

- **结构**：左侧分页 [地形][参数] + 右侧 `PreviewDisplay`（默认地形类型图层）+ 底部固定 [保存][重置] + 顶部种子输入（随机，沿用"预览种子随机化"记忆）。
- **地形页滑块**：省海拔剖面（craton: plainBase/hillsHigh；belt: beltFoothill/beltPeak；plateau: plateauBase/plateauTop；basin: basinBase）+ 起伏振幅（cratonRelief/beltRelief/plateauRelief/basinRelief）。
- **参数页滑块**：省权重（cratonWeight/beltWeight/plateauWeight/basinWeight）、雪线（snowLine/snowLatitudeInfluence）、分类矩阵阈值（elevHigh/reliefHigh/peakE）、可选 continentScale/provinceScale/continentBias/seabedDetail。
- **实时预览**：滑块 `onChange`→`markDirty`；`tick()` 防抖（6 tick）后用本地参数副本 `buildParams()` 重建 `GeoGenesisTerrain`→`PreviewDisplay.setTerrain`→`requestResample`（数据流参考 docs/02-功能设计/03-预览界面）。
- **保存**：本地副本 → `GeoGenesisConfig.INSTANCE.<field>.set(v)` 全量写入 + `GeoGenesisConfig.SPEC.save()` 落盘 toml；Reset 从 `GeoGenesisConfig` 当前值重载。`buildParams()` 用本地副本（与记忆铁律一致：预览用 `defaultParams()`，游戏用 `buildParams()`）。
- **复用**：`ParamSlider`（滑块+重置+tooltip）、`PreviewDisplay`（地图+图层切换+防抖），不引入 Factor/ControlPoint 曲线编辑器架构。

## 实施备注

- **性能**：`LandShape.sample` 每格新增 4 次细节噪声 `compute`（与现有 4 次省权重噪声同量级），仍为 O(1)/格；`relief` 为简单算术，产线复杂度不变。
- **回归控制**：`OCEAN/DEEP_OCEAN/BEACH` 前置与 `e<0` 判洋逻辑不动；海岸线仍由 `e` 自然过零涌现；`eLand` 连续 + 省权重连续 → 类型平滑过渡，无硬边、无 tile 接缝；`height` 映射不变，方块填充链路不受影响。
- **日志**：不改日志；辅助标定时可在 `classify` 临时打 histogram 统计各 `TerrainClass` 占比，确认后删除。
- **不恢复植被装饰**（`applyBiomeDecoration` 空操作保留，后续项）；**不接线 mountainCap**（`e` 已 `clamp(e,-1,1)`，`beltPeak=0.95`→Y≈307<320）。

## 架构设计

侵蚀前基础地形由"地质省特征"连续合成两场（海拔 + 起伏），分类与雪线解耦；配置屏实时预览并落盘：

```mermaid
flowchart LR
  TOML[geogenesis-common.toml] --> SPEC[ForgeConfigSpec BUILDER 默认]
  SPEC --> BP[buildParams / defaultParams]
  BP --> TP[TerrainParams 含 relief 振幅/分类阈值]
  TP --> LS[LandShape: provinceWeights + 每省 baseE/reliefAmp/detail]
  LS -->|elevation, relief| CG[CellGenerator.sample]
  CG -->|eLand=elevation+relief| E[e=eOcean+eLand]
  CG --> CL[classify: 海拔×起伏 矩阵 -> TerrainClass]
  CG --> SN[applySnowLine: 海拔×温度 -> isSnow]
  CL --> BC[BiomeClassifier: switch(terrainType) + isSnow 覆盖]
  E --> GEN[GeoGenesisGenerator.fillFromNoise]
  BP -->|本地副本| SCR[GeoGenesisConfigScreen]
  SCR -->|实时预览| PV[PreviewDisplay]
  SCR -->|保存| SPEC
```

## 目录结构与修改清单

```
forge-1.20.1-47.4.10-mdk/
├── src/main/java/com/geogenesis/worldgen/terrain/LandShape.java        # [MODIFY] 新增每省 reliefAmp(常量或配置) + 每省 detail 噪声；sample() 改为返回 elevation 与 relief 两场（保留 provinceWeights 与 baseE 形态），加权混合"特征"而非"高度"。
├── src/main/java/com/geogenesis/worldgen/terrain/CellGenerator.java    # [MODIFY] sample(): 装配 e=eOcean+eLand(eLand=elevation+relief)，climate 后调 applySnowLine 写 isSnow；classify(): 改海拔×起伏二维矩阵 + PEAK 子型 + BASIN，删原 e>0.7/0.8/0.9 单高度阈值；新增 applySnowLine()。
├── src/main/java/com/geogenesis/worldgen/terrain/TerrainParams.java    # [MODIFY] defaults(): land 振幅同步高值；新增 reliefAmp 4 项 + 分类阈值 3 项 + snowLatInf 1 项与构造参数，消除与 builder/toml 的漂移副本。
├── src/main/java/com/geogenesis/config/GeoGenesisConfig.java           # [MODIFY] 省权重 craton/belt/plateau 适度 sharpen；新增 reliefAmp 4 项 / 分类阈值 3 项 / snowLatitudeInfluence 1 项 BUILDER 默认；defaultParams() 同步。
├── run/config/geogenesis-common.toml                                   # [MODIFY] "Land Process Parameters" 振幅同步高值；"Province Weights" 省权重；新增 "Relief Amplitudes" / "Classification Bands" / "Snow" 段。运行时实际生效来源。
├── src/main/java/com/geogenesis/worldgen/climate/BiomeClassifier.java  # [MODIFY] switch(terrainType) 保持；雪/冷相关改用 cell.isSnow 覆盖层(PEAK->FROZEN/STONY, MOUNTAINS->SNOWY_SLOPES/..., 低起伏雪->SNOWY_*) 替代 isCold()。
├── src/main/java/com/geogenesis/client/GeoGenesisConfigScreen.java     # [MODIFY] 由 2.43KB stub 重写为功能屏：分页[地形][参数] + ParamSlider 滑块组 + PreviewDisplay + 种子输入 + 保存/重置；buildParams() 用本地副本；tick() 防抖重采样。
├── src/main/java/com/geogenesis/client/ParamSlider.java               # [复用] 通用滑块（含重置+tooltip），不改动。
├── src/main/java/com/geogenesis/client/preview/PreviewDisplay.java     # [复用] 纹理式地图控件（requestResample/setTerrain/图层切换），不改动。
└── src/main/java/com/geogenesis/worldgen/terrain/Cell.java            # [无需改] 已有 terrainType/climate/temperature/isSnow 字段，isSnow 由 applySnowLine 赋值。
```

## 关键代码结构（核心改动示意）

`LandShape.sample` 双场产出：

```java
// 每省特征：baseEᵢ（海拔基面）与 reliefAmpᵢ（起伏幅度，新增配置）；detailᵢ(x,z) 细节 FBM∈[-1,1]
double elevation = 0, relief = 0;
for (int i = 0; i < 4; i++) {
    elevation += w[i] * baseE[i];
    relief    += w[i] * (reliefAmp[i] * detail[i].compute(wx, wz));
}
// 返回 elevation 与 relief（CellGenerator 求和得 eLand = elevation + relief）
```

`CellGenerator.classify` 海拔×起伏矩阵：

```java
// 前置: e<0 -> OCEAN/DEEP_OCEAN; 0<e<0.03 -> BEACH（不变）
boolean high   = elevation > ELEV_HIGH;        // 候选 0.45
boolean rugged = Math.abs(relief) > RELIEF_HIGH; // 候选 0.10
if (wBasin > 0.40 && elevation < 0.08) return BASIN;
if (high && rugged)  return (elevation > PEAK_E) ? PEAK : MOUNTAINS; // PEAK=山脉子型
if (high && !rugged) return PLATEAU;
if (!high && rugged) return HILLS;
return PLAIN;
```

## 设计风格

按 Minecraft Forge 原生配置屏美学（深色半透明面板 + 浅色文字 + 圆角按钮），重写为功能化「GeoGenesis 配置屏」。左侧分页 [地形][参数] 承载滑块组，右侧内嵌 `PreviewDisplay` 实时地图（默认地形类型图层），底部固定 [保存][重置]，顶部种子输入。整体遵循既有 `docs/02-功能设计/03-预览界面` 的数据流与防抖策略，不引入额外前端框架。

## 页面布局（单屏，自上而下）

- **顶部栏**：标题「GeoGenesis 配置」+ 种子输入框（随机生成，可手动改）。
- **左栏（可滚动，scissor 裁剪防溢出）**：
- 分页标签：[地形] [参数]。
- 地形页：省海拔剖面（craton/belt/plateau/basin 各一对 min/max 滑块）+ 起伏振幅（4 个滑块）。
- 参数页：省权重（4 滑块）+ 雪线（snowLine / snowLatitudeInfluence）+ 分类矩阵阈值（elevHigh/reliefHigh/peakE）+ 可选 continentScale/provinceScale/continentBias/seabedDetail。
- 每个滑块含标签 + 数值 + 重置按钮 + 悬停 tooltip（沿用 `ParamSlider`）。
- **右栏**：`PreviewDisplay` 地图控件 + 右侧工具栏（图层切换按钮：切到地形类型图层便于目检本次修复；色带/分辨率/水文）。
- **底部固定栏**：[保存] [重置]（不随左栏滚动）。

## 交互

- 滑块拖动 → `markDirty()`；`tick()` 防抖（6 tick≈300ms）→ 本地副本 `buildParams()` 重建地形 → `PreviewDisplay.setTerrain` + `requestResample` 后台重采样上色。
- [保存]：本地副本全量写回 `GeoGenesisConfig.INSTANCE.<field>.set(v)` + `SPEC.save()` 落盘 toml；[重置]：从 `GeoGenesisConfig` 当前值重载本地副本并刷新预览。
- 图层切换按钮切到地形类型图层，验证 12 类分布。

## 视觉规范

- 背景：Minecraft 标准深色半透明（0x000000 + alpha），面板 0x141820。
- 文字：主文字白 0xFFFFFF，次级灰 0xAAAAAA，强调/分区标题用省主题色（craton 绿、belt 棕、plateau 赭、basin 紫灰）。
- 滑块/按钮：边框 0x555555，悬停 0x888888，按下/选中 0x00c896（青绿强调）。
- 平滑过渡、无硬边；滑块 tooltip 固定位置防溢出。