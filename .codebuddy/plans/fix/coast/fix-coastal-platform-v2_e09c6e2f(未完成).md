---
name: fix-coastal-platform-v2
overview: 修复海岸平台感：调整 CellGenerator 海岸样条使海洋侧陡降 + 缩窄 HeightCurve 大陆架 + 提深浅海深度 + 调和 HydraulicErosion COAST_SUB
todos:
  - id: fix-height-curve
    content: 修改 CellGenerator HEIGHT_C/HEIGHT_E 样条（近岸斜率×2.2）
    status: pending
  - id: fix-config-defaults
    content: 修改 GeoGenesisConfig + TerrainParams 海洋参数默认值
    status: pending
  - id: fix-coast-sub
    content: 回调 HydraulicErosion COAST_SUB 0.20→0.12
    status: pending
  - id: compile-verify
    content: 编译验证全量重编译通过
    status: pending
    dependencies:
      - fix-height-curve
      - fix-config-defaults
      - fix-coast-sub
  - id: update-doc
    content: 更新工作文档记录本次改动
    status: pending
    dependencies:
      - compile-verify
---

## 需求描述

用户截图反馈海岸平台感问题：海洋与陆地交界处存在明显的浅水平台，近岸数百格范围内海洋深度仅3-8格，形成宽阔的平底区域。之前尝试的 HydraulicErosion v2 改动（水滴门槛/COAST_SUB/回填抑制带）完全无效。

## 根因分析

平台感来自**两层海洋深度映射的叠加压缩**，而非侵蚀参数问题：

### 根因1：CellGenerator.HEIGHT_E 海岸侧斜率过缓

- 当前 `HEIGHT_C = {-1.0, -0.5, -0.16, 0.0, 0.30}` / `HEIGHT_E = {-0.6, -0.3, -0.12, 0.0, 0.04}`
- 在 c=-0.16 处 e 仅 -0.12，斜率 ≈ 0.75（e/c）
- 结果：近岸 e 值极小（c=-0.1 时 e≈-0.08）

### 根因2：HeightCurve 海洋 shelf 映射进一步压缩

- `oceanShelfFraction=0.20`, `shallowOceanDepth=18`
- t=-e=0.08 → depth=(0.08/0.20)×18 ≈ 7.2 格
- 大陆架噪声频率 0.0006 cycles/block → c=-0.1 对应约 167 格离岸距离
- **167格离岸处深度仅7格** → 宽达数百格的浅水平台

### 根因3：HydraulicErosion v2 方向错误

- v2 改的是陆地侧 `h[][]`（eLand）侵蚀参数
- 海洋格在 `h[][]` 中为 NaN，水滴遇到即停止
- COAST_SUB/COAST_RANGE 影响河口冲刷深度，与海洋平台无关
- **平台感是海洋深度 profile 本身的数学结果，不是侵蚀不足**

## 修复目标

- 近岸 100 格内海洋深度 ≥ 15 格（当前仅 ~5 格）
- 消除海岸线处的视觉平台感
- 保持陆地侧海岸过渡平滑不变
- 保持与河流/湖泊系统的兼容性

## 技术方案

### 修改策略

调整两层数学映射：(1) HEIGHT_C/HEIGHT_E 样条让 c→e 在近岸下降更快；(2) Config 海洋 shelf 默认值让 e→Y 映射更深。HydraulicErosion v2 改动保留但回调 COAST_SUB 到合理值。

### 改动1：CellGenerator.java — HEIGHT_C/HEIGHT_E 样条（核心）

**文件**：`forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/terrain/CellGenerator.java`
**位置**：第157-158行

当前：

```java
HEIGHT_C = {-1.0, -0.5, -0.16, 0.0, 0.30};
HEIGHT_E = {-0.6, -0.3, -0.12, 0.0, 0.04};
```

改为：

```java
HEIGHT_C = {-1.0, -0.5, -0.12, 0.0, 0.30};
HEIGHT_E = {-0.6, -0.3, -0.20, 0.0, 0.04};
```

**效果**：第三控制点从(-0.16,-0.12) → (-0.12,-0.20)，近岸斜率从0.75→1.67（提升2.2倍）。c=-0.1处 e 从 -0.08 → -0.17。

### 改动2：GeoGenesisConfig.java — 海洋参数默认值

**文件**：`forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/config/GeoGenesisConfig.java`
**位置**：第160-171行

- `SHALLOW_OCEAN_DEPTH` 默认值：18 → 25
- `OCEAN_SHELF_FRACTION` 默认值：0.20 → 0.12

**效果**：大陆架更窄(12%)、更深(25格)，近岸深度梯度大幅增加。

### 改动3：TerrainParams.java — defaults() 同步

**文件**：`forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/terrain/TerrainParams.java`
**位置**：第93-96行

同步修改：

- `shallowOceanDepth`: 18 → 25
- `oceanShelfFraction`: 0.20 → 0.12

### 改动4：HydraulicErosion.java — 回调 COAST_SUB

**文件**：`forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/river/HydraulicErosion.java`
**位置**：第33行

`COAST_SUB`: 0.20 → 0.12（原值0.08太小，v2的0.20太大导致河口冲刷过深）

### 改动5：geogenesis-common.toml — 提醒用户重置

现有 toml 文件中的 `oceanShelfFraction=0.2` 和 `shallowOceanDepth=18` 会覆盖代码默认值。用户需删除或手动更新 toml 中的这两个字段，否则改动不生效。

### 预期深度对比（c=-0.1, 约167格离岸）

| 参数 | 修改前 | 修改后 |
| --- | --- | --- |
| e 值 | -0.08 | -0.17 |
| t=-e | 0.08 | 0.17 |
| sf | 0.20 | 0.12 |
| shelfDepth | 18 | 25 |
| **深度** | **7.2格** | **35.4格** |


### 性能影响

零。仅修改常量值和配置默认值，无新增计算。

### 兼容性

- 陆地侧（c>0.30）完全不受影响（landW=1.0，e=eLand）
- 海岸过渡带（c∈[0,0.30]）不受影响（landW 由 c/COAST_WIDTH 驱动，HEIGHT_E 仅在 c=0 处衔接）
- 河流/湖泊系统不受影响（landHeight 对 c<0 返回 NaN，与 HEIGHT_E 无关）
- 海洋分类（SHALLOW_OCEAN/CONTINENTAL_SHELF/DEEP_OCEAN）自动适配新深度