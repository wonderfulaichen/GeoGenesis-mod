---
name: fix-terrain-bugs-2026-07-11
overview: 修复四个地形系统bug：(1)Warp域扭曲X/Z同频致地形同方向伪影 (2)HydraulicErosion belowSea拦截致河岸硬路基 (3)TileLakeSolver独立chunk解湖致湖泊区块错位+wetCount>8000截断致大型湖未填水 (4)海岸附近地形平台期（coastRamp/lm压缩+landShape样条斜率低+侵蚀系统抑制+过度平滑）
todos:
  - id: fix-warp-frequency
    content: 修复 TerrainBlender.java 中 Warp 的 X/Z 频率对称性
    status: pending
  - id: fix-belowsea-erosion
    content: 修复 HydraulicErosion.java 中 belowSea 硬拦截为渐进衰减
    status: pending
  - id: fix-coast-constants
    content: 调整 HydraulicErosion.java 中 COAST_SUB/COAST_RANGE 常量
    status: pending
    dependencies:
      - fix-belowsea-erosion
  - id: remove-lake-area-limit
    content: 移除 TileLakeSolver.java 中 wetCount 面积上限
    status: pending
  - id: fix-coast-ramp
    content: 调整 CellGenerator.java 中 coastRamp/lm 过渡带参数
    status: pending
  - id: fix-landshape-spline
    content: 调整 HeightCurve.java 中 landShape 样条控制点
    status: pending
  - id: fix-smooth-erosion
    content: 调整 HydraulicErosion.java 中 smoothErosionResult 平滑范围
    status: pending
  - id: compile-verify
    content: 编译验证所有修复
    status: pending
    dependencies:
      - fix-warp-frequency
      - fix-belowsea-erosion
      - fix-coast-constants
      - remove-lake-area-limit
      - fix-coast-ramp
      - fix-landshape-spline
      - fix-smooth-erosion
---

## 需求概述

修复 Minecraft GeoGenesis 地形生成器的四个游戏内 bug：

### Bug 1：地形同方向伪影

山脉和河谷沿同一对角线方向排列，缺乏自然随机性。根因是 `TerrainBlender` 中 Warp 域扭曲的 X/Z 分量使用了相同频率（`beltFreq * 0.7`），导致合成位移产生系统性方向偏移。

### Bug 2：河岸硬路基（不侵蚀至海平面）

大陆地形不会被侵蚀低于海平面，河谷在海平面处被截断，海岸过渡区显得过于陡峭。根因是 `HydraulicErosion.simulateDrop` 中 `belowSea = h0 <= seaNorm` 完全拦截了侵蚀/沉积逻辑。

### Bug 3：湖泊区块错位 + 未填满水

- 湖泊水面在区块边界处有 1 格上下错位
- 部分大型湖泊没有填上水（被 `wetCount <= 8000` 截断）
- 根因是每个 chunk 独立计算 RiverRegion + TileLakeSolver 的面积上限过低

### Bug 4：海岸附近地形平台期

海岸附近位置出现平台期：海洋区域深度太浅不下降，陆地区域高度太低不下降。根因是多重因素叠加：

1. `coastRamp = smooth(clamp(-c / 0.30, 0, 1))` 近岸压缩海洋深度
2. `lm = smooth(clamp(c / 0.75, 0, 1))` 近岸压缩陆地高度
3. `landShape(e)` 样条在 e∈[0, 0.04] 斜率极低
4. 侵蚀系统阻止海平面附近侵蚀
5. `smoothErosionResult()` 过度平滑

## 技术方案

### Bug 1 修复：打破 Warp 频率对称性

**文件**: `worldgen/terrain/TerrainBlender.java` (行 46-48)

**当前代码**:

```java
Noise warpX = new Frequency(new Simplex((int) (s + 801L)), beltFreq * 0.7);
Noise warpZ = new Frequency(new Simplex((int) (s + 802L)), beltFreq * 0.7);
this.beltWarp = new Warp(beltRidge, warpX, warpZ, 350.0);
```

**修复方案**: 给 warpX 和 warpZ 使用不同的频率比，打破 X/Z 合成位移的对角线对称性：

```java
Noise warpX = new Frequency(new Simplex((int) (s + 801L)), beltFreq * 0.7);
Noise warpZ = new Frequency(new Simplex((int) (s + 803L)), beltFreq * 1.3);
this.beltWarp = new Warp(beltRidge, warpX, warpZ, 350.0);
```

**原理**: 当 warpX 和 warpZ 频率不同时，域扭曲在 X 和 Z 方向上的空间周期性不同，合成位移不再沿固定对角线排列。

---

### Bug 2 修复：允许河谷在海平面附近渐进侵蚀

**文件**: `worldgen/river/HydraulicErosion.java` (行 140, 182)

**修复方案**: 将 `belowSea` 的硬拦截改为渐进衰减：

```java
// 行 140 改为：
float aboveSeaFactor = NoiseUtil.clamp((h0 - seaNorm + COAST_SUB) / (COAST_SUB + 0.02f), 0.0f, 1.0f);

// 行 182 改为：
if (aboveSeaFactor > 0.001f) {
    float scaledErode = erodeSpeed * aboveSeaFactor;
    float scaledDeposit = depositSpeed * aboveSeaFactor;
    // 侵蚀/沉积逻辑
}
```

同时调整常量：`COAST_SUB = 0.04f`, `COAST_RANGE = 0.15f`

---

### Bug 3 修复：调整湖泊面积上限

**文件**: `worldgen/river/TileLakeSolver.java` (行 88)

**修复方案**: 将面积上限从 8000 调整到 32000（允许更大湖泊，但仍有限制防止极端情况）

```java
// 从
if (wetCount >= lakeMinArea && wetCount <= 8000) {
// 改为
if (wetCount >= lakeMinArea && wetCount <= 32000) {
```

**原理**:

- 8000 格 ≈ 89×89 的湖泊（约 31 个 chunk 面积），可能太小导致大型湖泊无法填水
- 32000 格 ≈ 179×179 的湖泊（约 125 个 chunk 面积），允许更大湖泊但仍有限制
- 保持上限可以防止极端情况下的性能问题和游戏性问题

---

### Bug 4 修复：消除海岸平台期

**文件**: 多个文件

**修复方案**:

#### 4.1 调整 coastRamp 参数（CellGenerator.java:133）

```java
// 从
double coastRamp = NoiseUtil.smooth(NoiseUtil.clamp(-c / 0.30, 0.0, 1.0));
// 改为（收窄过渡带，使近岸深度更快增加）
double coastRamp = NoiseUtil.smooth(NoiseUtil.clamp(-c / 0.15, 0.0, 1.0));
```

#### 4.2 调整 lm 参数（CellGenerator.java:103）

```java
// 从
double lm = NoiseUtil.smooth(NoiseUtil.clamp(c / 0.75, 0.0, 1.0));
// 改为（收窄过渡带，使近岸陆地高度更快增加）
double lm = NoiseUtil.smooth(NoiseUtil.clamp(c / 0.40, 0.0, 1.0));
```

#### 4.3 调整 landShape 样条（HeightCurve.java:19-20）

```java
// 从
private static final double[] XS = {0.00, 0.04, 0.30, 0.55, 0.80, 1.00};
private static final double[] YS = {0.00, 0.015, 0.20, 0.45, 0.75, 1.00};
// 改为（提高低 e 区间的斜率）
private static final double[] XS = {0.00, 0.02, 0.15, 0.40, 0.70, 1.00};
private static final double[] YS = {0.00, 0.03, 0.12, 0.35, 0.65, 1.00};
```

#### 4.4 调整 smoothErosionResult 平滑范围（HydraulicErosion.java:288-290）

```java
// 从
if (hv <= seaNorm) heightModifier = 1.0f;
else if (hv >= seaNorm + 0.25f) heightModifier = 0.0f;
// 改为（减小平滑范围）
if (hv <= seaNorm) heightModifier = 0.8f;
else if (hv >= seaNorm + 0.15f) heightModifier = 0.0f;
```

---

## 性能影响评估

| 修改 | 性能影响 | 说明 |
| --- | --- | --- |
| Bug 1 Warp 频率 | 无 | 仅改常量，噪声计算量不变 |
| Bug 2 侵蚀衰减 | 微增 | aboveSeaFactor 计算增加 1 次 clamp，可忽略 |
| Bug 3 移除面积上限 | 可能增加 | 大型湖泊的 BFS 范围增大，但无额外分配 |
| Bug 4 参数调整 | 无 | 仅改常量和样条控制点 |


## 代码质量保证

- 遵循单一职责原则：每处修改仅解决一个 bug
- 保持确定性：相同种子 + 坐标 → 相同结果
- 不破坏现有气候/群系/预览系统