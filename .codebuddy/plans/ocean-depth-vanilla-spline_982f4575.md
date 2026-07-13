---
name: ocean-depth-vanilla-spline
overview: 将海洋深度映射从 blendE (1-t)² 压缩 + HeightCurve 双样条，改为仿原版 MC 的 continent c 直接驱动单样条映射到 Y 深度。
todos:
  - id: modify-blende
    content: 修改 CellGenerator.blendE 海洋分支为 e=c 线性映射，更新注释
    status: completed
  - id: cleanup-heightcurve
    content: 更新 HeightCurve.java 海洋分支注释（t=-e=-c）
    status: completed
    dependencies:
      - modify-blende
  - id: remove-unused-params
    content: 移除 TerrainParams/GeoGenesisConfig 中 oceanShelfFraction/oceanSlopeFraction/oceanTrenchOnset 参数
    status: completed
    dependencies:
      - modify-blende
  - id: update-config
    content: 更新 geogenesis-common.toml 移除已删除参数
    status: completed
    dependencies:
      - remove-unused-params
  - id: compile-verify
    content: 编译验证 BUILD SUCCESSFUL
    status: completed
    dependencies:
      - update-config
---

## 需求概述

修复海洋深度阶梯状问题。当前 `blendE` 的 `(1-t)²` 公式将海洋 e 值严重压缩，导致 shelf→slope→abyss 在 c 空间被挤压到约100格宽的过渡带内，HeightCurve Catmull-Rom 样条在该段斜率极高（m1=247），形成视觉断崖。

## 核心目标

仿照原版 MC offset.json 的方式：continent c 直接作为样条输入，去掉中间 e 空间的非线性压缩。海洋侧改为 `e = c`（线性映射），HeightCurve 海洋样条无需改动（t=-e=-c 即 continent 绝对值）。

## 功能内容

- 海洋深度从海岸到深海平缓过渡，无阶梯状断崖
- 大陆架宽阔平坦（c ∈ [-0.75, 0]），大陆坡窄而陡（c ∈ [-0.87, -0.75]），深海平原辽阔
- 陆地分支完全不受影响，地质过程范式保持不变

## 技术方案

### 核心改动（仅1处实质改动）

**`CellGenerator.blendE`** 海洋分支从 `e = -OCEAN_E * (1-t)²` 改为 `e = c`（线性映射）。

原版 offset.json 的做法：continent c 直接输入 Catmull-Rom 样条，样条输出 Y 偏移，没有中间 e 空间。我们的等价做法：海洋侧 `e = c`（c ∈ [-1, 0]），HeightCurve 海洋样条以 `t = -e = -c` 为输入。

### 效果对比

| c 值 | 旧 e = -(1-t)² | 新 e = c | HeightCurve Y（新） |
| --- | --- | --- | --- |
| 0（海岸） | -0.25 | 0 | 63（海平面） |
| -0.15 | -0.60（已超 shelf） | -0.15 | ≈52（浅海 shelf 内） |
| -0.30 | -0.85（deep） | -0.30 | ≈45（shelf 内） |
| -0.50 | -0.95（deep） | -0.50 | ≈42（shelf 外缘） |
| -0.75 | -1.0（最深） | -0.75 | ≈45（shelf 边缘） |
| -0.87 | -1.0（最深） | -0.87 | ≈3（slope 底→深海） |
| -1.0 | -1.0（最深） | -1.0 | ≈-22（海沟） |


### 修改文件

1. **`CellGenerator.java`**（唯一实质改动）：

- `blendE` 方法：海洋分支 `e = c + OCEAN_DETAIL * d`；陆地分支保持 `eLand * t`
- 更新 `blendE`/`computeShape`/`shapeE`/`landHeight` 注释
- 移除不再需要的常量 `COAST_BAND`、`OCEAN_E`（海洋侧不再用 smoothstep 和 (1-t)²）

2. **`HeightCurve.java`**：海洋分支注释更新（说明 t=-e=-c 即 continent 绝对值）

3. **`TerrainParams.java`**：移除 `oceanShelfFraction`/`oceanSlopeFraction`/`oceanTrenchOnset` 三个参数（它们控制 e 空间分段，现在 e=c 不再需要）

4. **`GeoGenesisConfig.java`**：移除对应配置定义和加载代码

5. **`geogenesis-common.toml`**：移除对应配置项

### 不受影响的部分

- 陆地分支（c ≥ 0）：`e = eLand * t` 完全不变
- 河流系统（HeightProvider.landHeight）：c<0 返回 NaN，不受影响
- 雨影（RainShadow）：通过 shapeE → heightCurve.height() 工作，自然兼容
- 分类（classify）：`shape < -0.55 → DEEP_OCEAN` 在 e=c 下对应 c < -0.55，位置合理

### 性能

改动极小，blendE 海洋分支从 smoothstep + 乘法变为直接返回 c，性能微幅提升。

### 风险

- 海岸过渡：c=0 时海洋 e=0、陆地 e=0，连续但非 C1 光滑。Minecraft 原版也是这样处理的，视觉无问题。
- `COAST_BAND` 常量：陆地分支仍使用 `smoothstep(c / (2*COAST_BAND))` 做海岸淡出，COAST_BAND=0.15 保持不变。

# Agent Extensions

无 Agent 扩展需要。