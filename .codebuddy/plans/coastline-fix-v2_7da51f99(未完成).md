---
name: coastline-fix-v2
overview: 修复海岸线过渡断裂和近岸陆地过度抬升问题。根因是加法模型 `e=eLand*(c+lm*(1-c))` 中 c 项直接参与乘积导致近岸高度被抬升3.5倍。方案：改回纯乘法模型 `e=eLand*lm`，但 lm 使用 smoothstep 替代旧的 smooth(clamp(c/0.25))，COAST_LAND_BAND=0.15 保持窄陡海岸。
todos:
  - id: fix-coast-formula
    content: 修改 CellGenerator.java：landCoastFactor 改 smoothstep + 三处陆地分支改纯乘法
    status: pending
  - id: verify-build
    content: 编译验证 gradlew build
    status: pending
    dependencies:
      - fix-coast-formula
  - id: test-preview
    content: 启动预览窗口检查海岸过渡效果
    status: pending
    dependencies:
      - verify-build
  - id: test-client
    content: 启动游戏实机目检海岸/侵蚀效果
    status: pending
    dependencies:
      - test-preview
---

## 问题描述

用户提交三张游戏内截图，报告两个严重地形问题：

### 问题1：海岸线过渡极其陡峭，陆地被抬升更狠

- 截图1：岛屿海岸呈阶梯状断裂，陆地几乎从海平面直接抬升到高处
- 截图2：海岸线近乎垂直的悬崖，无平滑过渡
- 用户反馈："不仅没修好海岸线的问题，陆地还被抬升得更狠了，海岸线陆地都没过渡了"

### 问题2：侵蚀区域高于海平面

- 截图3：近海水域中可见高于海平面的侵蚀痕迹
- 用户疑问："我们不是设定有侵蚀到海洋并慢慢平滑衰减吗？但图中被向下侵蚀的部分怎么是高于海平面呢？"

## 根因分析

### 问题1根因：加法模型矫枉过正

- 位置：`CellGenerator.java` 第112-115行 `computeShape()`、第142-143行 `shapeE()`、第216-217行 `landHeight()`
- 当前公式：`e = eLand * (c + lm * (1.0 - c))`，其中 `lm = clamp(c / 0.15, 0, 1)`（线性）
- 问题本质：加法模型中 `c` 项直接参与乘积，在 c=0.05 时比旧乘法模型高约 3.5 倍
- 数值对比（10块内陆处）：旧模型 e/eLand=0.063，新模型 e/eLand=0.279（陡 4.4 倍）

### 问题2根因：基底高度偏高导致侵蚀无法触及海平面

- 位置：`HydraulicErosion.java` 侵蚀系统 + `RiverField.java` computeRegion
- 根因：近海岸陆地 base 高度（eLand）因问题1偏高，侵蚀后仍高于海平面
- 连锁效应：修复问题1后，问题2应大幅缓解

## 核心功能

- 修复海岸线过渡公式，从加法模型改回纯乘法模型 + smoothstep 缓动
- 确保海岸过渡呈 S 曲线：近岸平缓起步 → 中段适度抬升 → 内陆快速到达满高
- 消除海岸悬崖/阶梯状断裂，实现自然海岸坡度
- 降低近岸基底高度，使侵蚀自然触及海平面

## 技术方案

### 核心修改：CellGenerator.java 海岸公式

**修改1：landCoastFactor 改用 smoothstep**

```java
// 旧：线性 clamp(c / 0.15)
// 新：smoothstep S 曲线
private static double landCoastFactor(double c) {
    double t = NoiseUtil.clamp(c / COAST_LAND_BAND, 0.0, 1.0);
    return t * t * (3.0 - 2.0 * t); // smoothstep: 3t² - 2t³
}
```

**修改2：陆地分支从加法改回纯乘法**

```java
// 旧：e = eLand * (c + lm * (1.0 - c))
// 新：纯乘法，S 曲线过渡
e = eLand * lm;
```

**修改3：保持 band=0.15 不变**

- 旧 smoothstep 在 band=0.25 时太平（30 块内陆仅 38% 高度）
- 新 smoothstep 在 band=0.15 时恰好（30 块内陆 85% 高度）

### 数值验证（continent 频率 0.0006，dc/dx≈0.0038/block）

| 距离海岸 | c 值 | 旧模型(band=0.25) | 当前模型(加法) | 新模型(band=0.15) |
| --- | --- | --- | --- | --- |
| 5 块 | 0.019 | 0.5% | 14.3% | 4.5% |
| 10 块 | 0.038 | 6.3% | 27.9% | 16.6% |
| 15 块 | 0.057 | 12.4% | 41.5% | 27.0% |
| 20 块 | 0.075 | 20.0% | 53.8% | 50.0% |
| 30 块 | 0.114 | 38.4% | 78.7% | 85.2% |
| 40 块 | 0.150 | 50.0% | 100% | 100% |


新模型在 5 块处仅 4.5%（平缓起步），30 块处 85%（接近满高），过渡自然。

### 影响范围

- `CellGenerator.computeShape()` 第 114 行
- `CellGenerator.shapeE()` 第 143 行
- `CellGenerator.landHeight()` 第 217 行
- `CellGenerator.landCoastFactor()` 第 173-175 行

### 连锁效应

- 修复问题1后，近岸基底高度降低，侵蚀更易触及海平面
- 问题2无需单独修改侵蚀代码，属连锁修复

### 验证步骤

1. `gradlew.bat build` 编译
2. `gradlew.bat runPreview --args=12345` 预览窗口检查海岸过渡
3. `gradlew.bat runClient` 实机目检海岸/侵蚀效果

# Agent Extensions

无扩展使用