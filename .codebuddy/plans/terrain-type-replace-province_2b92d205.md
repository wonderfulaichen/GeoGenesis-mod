---
name: terrain-type-replace-province
overview: 用地形类型系统取代省系统（craton/belt/plateau/basin），每个类型有自己的噪声配方，类型间用样条嵌入噪声对象过渡，类型分布参考 procedural-island-generator 的 elevation+moisture 阈值方案。
todos:
  - id: create-type-generator
    content: 创建 TypeGenerator 接口和每个地形类型的专属噪声配方实现（复用 TypeMorphology 思路但使用独立噪声节点），新建 TypeGenerator.java 文件
    status: completed
  - id: create-type-land-shape
    content: 创建 TypeLandShape 类，包含 baseElev + moisture 独立 FBM 噪声场、类型分布阈值逻辑、样条嵌入噪声对象的过渡机制，新建 TypeLandShape.java
    status: completed
    dependencies:
      - create-type-generator
  - id: update-cell-generator
    content: 修改 CellGenerator 使用 TypeLandShape 取代 LandShape，更新 classify 方法，移除省权重依赖，更新 Cell.java 移除 provinceWeights
    status: completed
    dependencies:
      - create-type-land-shape
  - id: update-config-and-params
    content: 更新 TerrainParams、GeoGenesisConfig 和 GeoGenesisConfigScreen，用类型参数取代省参数，更新 toml 配置
    status: completed
    dependencies:
      - update-cell-generator
  - id: cleanup-deprecated
    content: 删除废弃文件：LandShape.java、TerrainComposer.java、TypeMorphology.java、ClimateWeights.java，清理 import 引用
    status: completed
    dependencies:
      - update-cell-generator
  - id: update-downstream
    content: 更新 BiomeClassifier、GeoPalette、PreviewDisplay、GeoGenesisTerrain 等下游文件适配新类型系统，编译验证
    status: completed
    dependencies:
      - update-config-and-params
      - cleanup-deprecated
---

## 产品概述

将 GeoGenesis 的地形生成从"省权重混合"范式重构为"地形类型驱动"范式。每个地形类型（PLAIN/HILLS/MOUNTAINS/PLATEAU/BASIN/PEAK/BEACH/SNOW）拥有独立的噪声配方，类型决定地形形状，而非仅作为分类标签。

## 核心功能

1. **类型分布**：用独立 FBM 噪声（baseElevation）+ 独立 FBM 噪声（moisture）两个连续场，通过阈值确定每个位置的地形类型（参考 procedural-island-generator 的 elevation + moisture 分类方式）
2. **类型专属地形**：每个地形类型拥有独立的噪声配方（取代旧的省系统 craton/belt/plateau/basin），类型直接决定地形高度和形状
3. **样条嵌入噪声对象**：类型间的过渡使用样条机制（类似 HeightCurve），样条控制点嵌入噪声对象而非固定值，实现 C1 连续过渡
4. **保留海洋样条**：海洋部分保持现有 HeightCurve.eFromC(c) 机制不变，陆地部分替换为 TypeLandShape
5. **保持加法模型**：e = eOcean + eLand 不变，仅替换 eLand 的计算方式

## 技术栈

- **语言**: Java 17+ (Minecraft Forge 1.20.1)
- **构建**: Gradle (ForgeGradle)
- **依赖**: 零外部依赖，仅使用项目内已有的 Noise/Simplex/Frequency 噪声库

## 实现方案

### 核心思路

用"类型专属噪声配方 + 样条过渡"取代旧的"省权重混合"范式。每个地形类型拥有独立的噪声配方（类似旧 LandShape 中各省的配方），类型分布由独立 FBM 噪声（baseElevation）+ 独立 FBM 噪声（moisture）两个连续场通过阈值确定，类型间的过渡使用样条机制（类似 HeightCurve，但控制点嵌入噪声对象而非固定值）。

### 架构概览

```
wx, wz
  |
  ├─ ContinentField.sample(wx, wz) ──→ c ∈ [-1,1]
  │     └─ HeightCurve.eFromC(c) ──→ eOcean ∈ [-1,0]  (海洋样条，不变)
  │
  ├─ TypeLandShape.sample(wx, wz) ──→ eLand ∈ [0,1]  (新：取代 LandShape)
  │     ├─ baseElevNoise ──→ baseElev ∈ [0,1]  (独立 FBM)
  │     ├─ moistureNoise ──→ moisture ∈ [0,1]  (独立 FBM)
  │     ├─ baseElev + moisture → typeWeights (参考项目阈值)
  │     ├─ 各类型专属噪声配方 → typeE[i]
  │     └─ 样条嵌入噪声对象 → 插值混合 → eLand
  │
  └─ e = clamp(eOcean + eLand, -1, 1)  (加法模型不变)
       └─ heightCurve.heightFromE(e) → 世界高度 Y
       └─ classify(e, typeWeights) → TerrainClass (类型与地形一致)
```

Now I have all the information I need. Let me output the plan in XML format.</think>Now I have a complete understanding of the codebase. Let me output the plan.

<plan_result>
<req>

## 产品概述

将 GeoGenesis 的地形生成从"省权重混合"范式重构为"地形类型驱动"范式。每个地形类型（PLAIN/HILLS/MOUNTAINS/PLATEAU/BASIN/PEAK/BEACH/SNOW）拥有独立的噪声配方，类型决定地形形状，而非仅作为分类标签。

## 核心功能

1. **类型分布**：用独立 FBM 噪声（baseElevation）+ 独立 FBM 噪声（moisture）两个连续场，通过阈值确定每个位置的地形类型（参考 procedural-island-generator 的 elevation + moisture 分类方式）
2. **类型专属地形**：每个地形类型拥有独立的噪声配方（取代旧的省系统 craton/belt/plateau/basin），类型直接决定地形高度和形状
3. **样条嵌入噪声对象**：类型间的过渡使用样条机制（类似 HeightCurve），样条控制点嵌入噪声对象而非固定值，实现 C1 连续过渡
4. **保留海洋样条**：海洋部分保持现有 HeightCurve.eFromC(c) 机制不变，陆地部分替换为 TypeLandShape
5. **保持加法模型**：e = eOcean + eLand 不变，仅替换 eLand 的计算方式
</req>

<tech>

## 技术栈

- **语言**: Java 17+ (Minecraft Forge 1.20.1)
- **构建**: Gradle (ForgeGradle)
- **依赖**: 零外部依赖，仅使用项目内已有的 Noise/Simplex/Frequency 噪声库

## 实现方案

### 核心思路

用"类型专属噪声配方 + 样条过渡"取代旧的"省权重混合"范式。每个地形类型拥有独立的噪声配方（类似旧 LandShape 中各省的配方），类型分布由独立 FBM 噪声（baseElevation）+ 独立 FBM 噪声（moisture）两个连续场通过阈值确定，类型间的过渡使用样条机制（类似 HeightCurve，但控制点嵌入噪声对象而非固定值）。

### 架构概览

```
wx, wz
  |
  ├─ ContinentField.sample(wx, wz) ──→ c ∈ [-1,1]
  │     └─ HeightCurve.eFromC(c) ──→ eOcean ∈ [-1,0]  (海洋样条，不变)
  │
  ├─ TypeLandShape.sample(wx, wz) ──→ eLand ∈ [0,1]  (新：取代 LandShape)
  │     ├─ baseElevNoise ──→ baseElev ∈ [0,1]  (独立 FBM)
  │     ├─ moistureNoise ──→ moisture ∈ [0,1]  (独立 FBM)
  │     ├─ baseElev + moisture → typeWeights (参考项目阈值)
  │     ├─ 各类型专属噪声配方 → typeE[i]
  │     └─ 样条嵌入噪声对象 → 插值混合 → eLand
  │
  └─ e = clamp(eOcean + eLand, -1, 1)  (加法模型不变)
       └─ heightCurve.heightFromE(e) → 世界高度 Y
       └─ classify(e, typeWeights) → TerrainClass (类型与地形一致)
```

### 关键设计决策

1. **类型分布**：用独立 FBM 噪声（baseElevation）+ 独立 FBM 噪声（moisture）两个连续场，通过阈值确定类型权重。阈值参考 procedural-island-generator 的 elevation + moisture 分类体系。

2. **类型专属噪声配方**：每个地形类型（PLAIN/HILLS/MOUNTAINS/PLATEAU/BASIN/PEAK/BEACH/SNOW）拥有独立的噪声配方，类似旧 LandShape 中各省的配方。类型决定地形形状。

3. **样条嵌入噪声对象**：类型间的过渡使用样条机制。样条的 x 轴是"类型索引"（由 baseElev + moisture 确定），每个控制点嵌入一个噪声对象（TypeGenerator）。样条插值在相邻类型噪声对象的输出之间进行。

4. **海洋保持原样**：HeightCurve.eFromC(c) 不变，海洋样条控制点 depth 仍是固定值。

5. **加法模型不变**：e = eOcean + eLand，仅替换 eLand 的计算方式。

### 类型分布阈值（参考 procedural-island-generator）

| baseElev 范围 | moisture 条件 | 地形类型 |
| --- | --- | --- |
| < 0.03 | - | BEACH |
| 0.03~0.10 | - | PLAIN |
| 0.10~0.28 | - | HILLS |
| 0.28~0.60 | < 0.33 | HILLS (dry) |
| 0.28~0.60 | 0.33~0.66 | PLATEAU |
| 0.28~0.60 | > 0.66 | PLATEAU (wet) |
| 0.60~0.80 | < 0.20 | MOUNTAINS (dry) |
| 0.60~0.80 | 0.20~0.50 | MOUNTAINS |
| 0.60~0.80 | > 0.50 | MOUNTAINS (wet) |
| > 0.80 | < 0.20 | PEAK |
| > 0.80 | 0.20~0.50 | PEAK |
| > 0.80 | > 0.50 | SNOW |


### 类型专属噪声配方

每个类型有自己的噪声配方（复用 TypeMorphology 中已有的配方思路，但使用独立噪声节点而非依赖 LandShape）：

| 类型 | 噪声配方 | 输出范围 |
| --- | --- | --- |
| PLAIN | 极低幅 FBM，近恒定 base + 微小起伏 | [0.01, 0.05] |
| HILLS | 中低幅 FBM，温和起伏 | [0.04, 0.18] |
| MOUNTAINS | 高幅 FBM + warp 蜿蜒 + mask 团块，脊线形态 | [0.25, 0.82] |
| PLATEAU | 阶梯噪声 + 平顶台地形态 | [0.22, 0.58] |
| BASIN | 凹盆形态（中心低、边缘高） | [0.01, 0.10] |
| PEAK | 极高峰值（pow( | r | , sharpness)） | [0.65, 0.92] |
| BEACH | 极低幅噪声，近海平面 | [0.002, 0.025] |
| SNOW | 高海拔噪声 + 雪线调制 | [0.01, 0.12] |


### 样条过渡机制

使用类似 HeightCurve 的 cubic Hermite 样条，但控制点嵌入噪声对象而非固定值：

- x 轴：类型索引（由 baseElev + moisture 确定）
- 每个控制点嵌入一个 TypeGenerator（噪声对象）
- 样条在相邻类型噪声对象的输出之间插值
- 导数控制过渡形状（C1 连续）

### 类型分布阈值（参考 procedural-island-generator）

| baseElev 范围 | moisture 条件 | 地形类型 |
| --- | --- | --- |
| < 0.03 | - | BEACH |
| 0.03~0.10 | - | PLAIN |
| 0.10~0.28 | - | HILLS |
| 0.28~0.60 | < 0.33 | HILLS (dry) |
| 0.28~0.60 | 0.33~0.66 | PLATEAU |
| 0.28~0.60 | > 0.66 | PLATEAU (wet) |
| 0.60~0.80 | < 0.20 | MOUNTAINS (dry) |
| 0.60~0.80 | 0.20~0.50 | MOUNTAINS |
| 0.60~0.80 | > 0.50 | MOUNTAINS (wet) |
| > 0.80 | < 0.20 | PEAK |
| > 0.80 | 0.20~0.50 | PEAK |
| > 0.80 | > 0.50 | SNOW |


### 性能考虑

- 每个位置需要采样所有活跃类型的噪声对象（约 2-3 个相邻类型），比旧 LandShape 的 4 省略多但可接受
- baseElev 和 moisture 是独立 FBM 噪声，与大陆性 c 无关，计算量增加约 2 个 FBM
- 缓存机制（GeoGenesisTerrain 的 chunk 缓存）不变，避免重复计算

### 实现注意事项

- TypeMorphology 已存在（@Deprecated），其类型专属噪声配方可直接复用思路，但需使用独立噪声节点（不再依赖 LandShape）
- TerrainComposer 已存在（@Deprecated），其类型分布 + 样条过渡逻辑可参考
- ClimateWeights 已存在，其气候空间高斯邻近度思路可参考，但需替换为 baseElev + moisture 阈值
- 旧 LandShape.java 将被删除，其噪声节点不再需要
- 旧 TerrainComposer.java / TypeMorphology.java / ClimateWeights.java 将被删除
- Cell.java 的 provinceWeights 字段将被移除，替换为 typeWeights
- TerrainParams.java 的省参数将被替换为类型参数
- GeoGenesisConfig.java 的省配置将被替换为类型配置

## Agent Extensions

### SubAgent

- **code-explorer**: 用于在实现过程中探索相关文件的结构和依赖关系，确保修改的完整性和正确性
- **bmad-architect**: 用于在实现前对关键设计决策进行技术评审，确保架构质量