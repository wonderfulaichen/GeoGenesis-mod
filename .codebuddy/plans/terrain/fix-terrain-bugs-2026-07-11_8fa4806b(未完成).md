---
name: fix-terrain-bugs-2026-07-11
overview: 修复三个地形系统bug：(1)Warp域扭曲X/Z同频致地形同方向伪影 (2)HydraulicErosion belowSea拦截致河岸硬路基 (3)TileLakeSolver独立chunk解湖致湖泊区块错位+wetCount>8000截断致大型湖未填水
todos:
  - id: fix-warp-frequency
    content: 修复 TerrainBlender.java 中 Warp 的 X/Z 频率对称性（种子 802→803，频率 0.7→1.3）
    status: pending
  - id: fix-belowsea-erosion
    content: 修复 HydraulicErosion.java 中 belowSea 硬拦截为渐进衰减（aboveSeaFactor）
    status: pending
  - id: fix-coast-constants
    content: 调整 HydraulicErosion.java 中 COAST_SUB/COAST_RANGE 常量匹配新衰减逻辑
    status: pending
    dependencies:
      - fix-belowsea-erosion
  - id: remove-lake-area-limit
    content: 移除 TileLakeSolver.java 中 wetCount
    status: pending
---

## 需求概述

修复三个 Minecraft 自定义地形生成器 (GeoGenesis) 的游戏内 bug：

### Bug 1：地形同方向伪影

山脉和河谷沿同一对角线方向排列，缺乏自然随机性。根因是 `TerrainBlender` 中 Warp 域扭曲的 X/Z 分量使用了相同频率，导致合成位移产生系统性方向偏移。

### Bug 2：河岸硬路基（不侵蚀至海平面）

大陆地形不会被侵蚀低于海平面，河谷在海平面处被截断，海岸过渡区显得过于陡峭。根因是 `HydraulicErosion.simulateDrop` 中 `belowSea = h0 <= seaNorm` 完全拦截了侵蚀/沉积逻辑。

### Bug 3：湖泊区块错位 + 未填满水

- 湖泊水面在区块边界处有 1 格上下错位
- 部分湖泊没有填上水（被 `wetCount <= 8000` 截断）
- 根因是每个 chunk 独立计算 RiverRegion + TileLakeSolver 的面积上限过低

## 技术方案

### Bug 1 修复：打破 Warp 频率对称性

**文件**: `worldgen/terrain/TerrainBlender.java` (行 46-48)

**当前代码**:

```java
Noise warpX = new Frequency(new Simplex((int) (s + 801L)), beltFreq * 0.7);
Noise warpZ = new Frequency(new Simplex((int) (s + 802L)), beltFreq * 0.7);
this.beltWarp = new Warp(beltRidge, warpX, warpZ, 350.0);
```

**修复方案**: 给 warpX 和 warpZ 使用**不同的频率比**，打破 X/Z 合成位移的对角线对称性：

```java
Noise warpX = new Frequency(new Simplex((int) (s + 801L)), beltFreq * 0.7);
Noise warpZ = new Frequency(new Simplex((int) (s + 803L)), beltFreq * 1.3); // 不同频率 + 不同种子
this.beltWarp = new Warp(beltRidge, warpX, warpZ, 350.0);
```

**原理**: 当 warpX 和 warpZ 频率不同时，域扭曲在 X 和 Z 方向上的空间周期性不同 → 合成位移不再沿固定对角线排列 → 山脉/河谷方向被打散。使用种子 803（而非 802）确保与 warpX 完全独立。

**影响范围**: 仅影响造山带（山脉）的域扭曲，不影响克拉通/高原/盆地的形态。旧存档地形会变化（种子兼容性不变，但 warp 频率变了）。

---

### Bug 2 修复：允许河谷在海平面附近渐进侵蚀

**文件**: `worldgen/river/HydraulicErosion.java` (行 140, 182)

**当前代码**:

```java
boolean belowSea = h0 <= seaNorm;   // 行 140
...
if (!belowSea) {                     // 行 182
    // 侵蚀/沉积逻辑（被完全跳过）
}
```

**修复方案**: 将 `belowSea` 的硬拦截改为**渐进衰减**——当 `h0` 接近 `seaNorm` 时，侵蚀强度平滑衰减到 0，允许河谷在海平面附近轻微下切（河口冲刷）：

```java
// 行 140 改为：
float aboveSeaFactor = NoiseUtil.clamp((h0 - seaNorm + COAST_SUB) / (COAST_SUB + 0.02f), 0.0f, 1.0f);
// aboveSeaFactor: h0 >= seaNorm+0.02 → 1.0（全强度侵蚀）
//                 h0 <= seaNorm-COAST_SUB → 0.0（完全停止）
//                 中间 → 平滑过渡

// 行 182 改为：
if (aboveSeaFactor > 0.001f) {
    float scaledErode = erodeSpeed * aboveSeaFactor;
    float scaledDeposit = depositSpeed * aboveSeaFactor;
    // 侵蚀/沉积逻辑（使用 scaledErode/scaledDeposit 替代 erodeSpeed/depositSpeed）
}
```

同时需要调整 `carveRivers` 中的 `COAST_SUB` 和 `COAST_RANGE` 常量，使河口冲刷深度与 droplet 侵蚀的衰减范围匹配：

```java
private static final float COAST_SUB = 0.04f;    // 从 0.02 增大到 0.04，允许更深的河口冲刷
private static final float COAST_RANGE = 0.15f;   // 从 0.10 增大到 0.15，过渡区更宽
```

**影响范围**: 河谷在海平面附近可以轻微下切，河口自然过渡到海洋，消除"硬路基"效果。不影响内陆侵蚀。

---

### Bug 3 修复：移除湖泊面积上限 + 改进区块一致性

**文件**: `worldgen/river/TileLakeSolver.java` (行 88)

**当前代码**:

```java
if (wetCount >= lakeMinArea && wetCount <= 8000) {
```

**修复方案 1（立即修复 - 移除面积上限）**:

```java
if (wetCount >= lakeMinArea) {
```

**修复方案 2（改进区块一致性 - 可选）**: 为了减少湖泊水面在区块边界的 1 格错位，将 `lakeLevel` 从 `float` 改为 `double`，并在 `GeoGenesisTerrain.writeRiverFields` 中使用 `Math.round` 统一取整：

在 `RiverField.RiverRegion` 中：

```java
public final double[][] lakeLevel;   // 从 float[][] 改为 double[][]
```

在 `TileLakeSolver.solve` 中：

```java
public record LakeResult(boolean[][] mask, double[][] level) {}
```

在 `GeoGenesisTerrain.writeRiverFields` 中确保：

```java
cell.lakeLevel = reg.lakeMask[lj][li] ? cellGen.landToWorld(reg.lakeLevel[lj][li]) : 0.0;
```

其中 `landToWorld` 内部已有 `Math.round`，double 精度足够消除 1 格错位。

**影响范围**: 移除面积上限后，大型湖泊可以正常填水。double 精度减少区块边界处的水面错位。旧存档湖泊会变化。

---

## 性能影响评估

| 修改 | 性能影响 | 说明 |
| --- | --- | --- |
| Bug 1 Warp 频率 | 无 | 仅改常量，噪声计算量不变 |
| Bug 2 侵蚀衰减 | 微增 | `aboveSeaFactor` 计算增加 1 次 clamp + 1 次除法/水滴，可忽略 |
| Bug 3 移除面积上限 | 可能增加 | 大型湖泊的 BFS 填充范围增大，但 `wetList` 数组已预分配 `n` 大小，无额外分配 |
| Bug 3 double 精度 | 微增 | float→double 内存增加 8 bytes/cell，region 尺寸 144×144 = 20736 cells，增加 ~160KB，可忽略 |


## 代码质量保证

- 遵循单一职责原则：每处修改仅解决一个 bug
- 保持确定性：相同种子 + 坐标 → 相同结果（Bug 1 的种子从 802 改为 803 是确定性的）
- 保持跨 chunk 无缝：Bug 3 的 double 精度改进减少边界不连续
- 不破坏现有气候/群系/预览系统：修改仅限于地形生成和侵蚀核心

## Agent Extensions

本任务为纯代码修改，不需要使用 Agent Extensions。