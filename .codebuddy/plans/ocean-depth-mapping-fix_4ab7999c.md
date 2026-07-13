---
name: ocean-depth-mapping-fix
overview: 修复海洋深度映射：blendE 的 (1-t)² 导致近岸 e 值快速跌到 -0.6，HeightCurve 的 shelf 范围（0.48）无法覆盖"视觉浅海"区域。方案A：调整 HeightCurve 海洋分段参数，让 shelf 覆盖更大 e 范围。
todos:
  - id: update-terrain-params
    content: 修改 TerrainParams.java 海洋分段参数默认值
    status: completed
  - id: update-config
    content: 修改 GeoGenesisConfig.java 配置定义默认值和注释
    status: completed
    dependencies:
      - update-terrain-params
  - id: verify-build
    content: 编译验证 gradlew compileJava
    status: completed
    dependencies:
      - update-config
---

## 问题描述

用户反馈海洋深度过渡异常：大陆架在游戏里 y 坐标显示约 20（海平面 63 减去 43 格深度），远超预期的 shallowOceanDepth=18。根因是 blendE 的 (1-t)² 压缩导致 e 值在近岸（~39格）就跌到 -0.6，超出 oceanShelfFraction=0.48 范围直接跳入大陆坡区域。

## 核心功能

调整 HeightCurve 海洋分段参数，让大陆架覆盖更大 e 范围，使视觉上的浅海区域正确映射为浅海深度。

## 预期效果

- 大陆架（浅海）从 e∈[0,0.48] 扩展到 e∈[0,0.75]，覆盖更宽的近岸区域
- 大陆坡收窄到 0.15，过渡更紧凑
- 海沟仅最深 8% 区域
- 近岸 39 格处 e=-0.6 仍在大陆架范围 → depth≈14 → y≈49（合理浅海）

## 修改方案

调整三个海洋分段参数，不改 blendE 混合逻辑：

| 参数 | 当前值 | 新值 | 理由 |
| --- | --- | --- | --- |
| `oceanShelfFraction` | 0.48 | 0.75 | shelf 覆盖 e∈[0,0.75]，近岸 39 格 e=-0.6 仍在 shelf 内 |
| `oceanSlopeFraction` | 0.22 | 0.15 | 坡段收窄，shelf→深海过渡更紧凑 |
| `oceanTrenchOnset` | 0.85 | 0.92 | 海沟仅最深 8%，深海平原更辽阔 |


### 数值验证

blendE 输出 e 与 HeightCurve 深度（新参数）：

| 距海岸 | c值 | e值 | 分类 | depth | y |
| --- | --- | --- | --- | --- | --- |
| 0格 | 0 | -0.25 | shelf | ~6 | 57 |
| 13格 | -0.05 | -0.31 | shelf | ~8 | 55 |
| 26格 | -0.10 | -0.44 | shelf | ~11 | 52 |
| 39格 | -0.15 | -0.60 | shelf | ~14 | 49 |
| 52格 | -0.20 | -0.71 | shelf | ~17 | 46 |
| 78格 | -0.30 | -0.84 | slope | ~36 | 27 |
| 130格+ | -0.50 | -0.94 | deep | ~60 | 3 |


### 修改文件

1. **TerrainParams.java** 第96-98行：默认值更新
2. **GeoGenesisConfig.java**：配置定义默认值和注释更新

### 验证

1. `gradlew.bat compileJava` 编译验证
2. `runPreview --args=12345` 预览窗口检查海洋深度过渡