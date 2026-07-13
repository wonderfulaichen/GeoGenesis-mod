---
name: fix-coastal-cliff-and-ocean-depth
overview: 修复海洋深度不足和海岸硬阈值问题：1) 调整海洋样条参数使深海更陡；2) 重写 blendE 为线性混合消除垂直悬崖
todos:
  - id: fix-blende
    content: 修改 CellGenerator.blendE 海陆分支：陆地用 coastFade 线性淡出到 0，海洋保持 e=c
    status: completed
  - id: update-depth-params
    content: 更新 TerrainParams + GeoGenesisConfig 海洋深度参数默认值
    status: completed
    dependencies:
      - fix-blende
  - id: sync-config
    content: 同步更新 geogenesis-common.toml 配置缓存
    status: completed
    dependencies:
      - update-depth-params
  - id: compile
    content: 编译验证 BUILD SUCCESSFUL
    status: completed
    dependencies:
      - sync-config
---

## 需求概述

修复 GeoGenesis 模组海洋地形的两个严重问题：

1. **海陆垂直悬崖**：海岸处从海平面直接跳到高地（60+格落差），没有平缓过渡（海滩/缓坡）。如截图所示，水面上方是陡峭的灰色方块悬崖。
2. **海洋深度不足**：大部分海洋只有十多格深，应该有合理的大陆架（~18格）→大陆坡（渐深）→深海平原（~60格）→海沟（~85格）的层次。

## 核心特征

- 海岸处从海平面平缓抬升到陆地高度，过渡宽度约 30-40 格
- 海洋深度随离岸距离平缓加深，近岸浅海（<20格）→ 远海深海（50-60格）
- 陆地内陆地形不受影响

## 技术栈

- Java 21 + Minecraft Forge 1.20.1
- Gradle 构建系统

## 实现方案

### 根因分析

**悬崖根因**：`CellGenerator.blendE` 陆地分支 `e = eLand * t`，其中 `t = smoothstep((c+0.4)/0.8)`。在 c=0（海岸线）处 t=0.5。如果海岸处恰好是山地（eLand=0.8）：e=0.4 → landShape(0.4)≈0.38 → Y≈126，而海洋侧 e=0 → Y=63。**1格内63格落差=垂直悬崖**。

**深度不足根因**：`oceanShelfFraction=0.75` 意味着 t∈[0,0.75]（|c|<0.75）全是大陆架（深度≤18格）。当 c=-0.5（典型深海）：t=0.5 < 0.75 → 还在大陆架 → 深度≈12格。

### 修改文件清单

**1. `CellGenerator.java`（核心改动）**

- 修改 `blendE` 方法：陆地分支从 `e = eLand * t` 改为 `e = eLand * coastFade`
- `coastFade = saturate(c / COAST_LAND_BAND)`，其中 `COAST_LAND_BAND = 0.15`
- c=0 时 coastFade=0 → e=0 → Y=海平面（无悬崖）
- c=0.15 时 coastFade=1 → e=eLand → 满高
- 海洋分支保持 `e = c`（线性映射）不变
- 新增常量 `COAST_LAND_BAND = 0.15`
- 更新注释

**2. `HeightCurve.java`（海洋深度重分配）**

- 更新海洋分支注释
- 样条控制点由参数驱动，无需改代码逻辑，只需改参数默认值

**3. `TerrainParams.java`（参数默认值）**

- `oceanShelfFraction`: 0.75 → 0.20
- `oceanSlopeFraction`: 0.15 → 0.30
- `oceanTrenchOnset`: 0.92 → 0.90

**4. `GeoGenesisConfig.java`（Forge Config 定义）**

- 同步更新 `OCEAN_SHELF_FRACTION`/`OCEAN_SLOPE_FRACTION`/`OCEAN_TRENCH_ONSET` 默认值

**5. `geogenesis-common.toml`（运行期配置缓存）**

- **必须同步修改**，否则用户看不到变化（已知陷阱）
- 更新三个海洋参数值

### 数值验证

**海岸过渡**（eLand=0.8 山地）：

| c | coastFade | e | Y | 距海面 |
| --- | --- | --- | --- | --- |
| 0 | 0 | 0 | 63 | +0 |
| 0.05 | 0.33 | 0.27 | 101 | +38 |
| 0.10 | 0.67 | 0.54 | 148 | +85 |
| 0.15 | 1.0 | 0.80 | 188 | 满高 |


**海洋深度**（t=|c|）：

| c | t | 区域 | 深度 | Y |
| --- | --- | --- | --- | --- |
| 0 | 0 | 海岸 | 0 | 63 |
| -0.10 | 0.10 | 大陆架 | ~9 | 54 |
| -0.20 | 0.20 | 大陆架边缘 | 18 | 45 |
| -0.40 | 0.40 | 大陆坡 | ~45 | 18 |
| -0.60 | 0.60 | 深海平原 | 60 | 3 |
| -1.00 | 1.00 | 海沟 | 85 | -22 |


### 影响范围

- 仅影响地形高度映射（e → Y）
- 不影响气候/生物群系分类（classify 在 height 之后调用）
- 侵蚀场通过 `landHeight()` 调用 `blendE`，改动自动传播
- 河流只刻蚀 `eLand ≥ 0` 的陆地区域，不受海洋分支影响

### 注意事项

- 修改代码默认值后**必须同步修改** `geogenesis-common.toml`
- 用户需**新建世界**验证（旧区块不重算）

## Agent Extensions

无需使用 Agent Extensions。