---
name: 海岸连续样条重构方案
overview: 将 CellGenerator.blendE 的 if/else 海陆分段改为单一连续 Hermite 样条 c→e，消除海岸断裂，同时确保海洋深度渐进和陆地地形起伏。
todos:
  - id: design-spline
    content: 设计连续样条控制点（c → baseE），确保斜率匹配和过渡自然
    status: completed
  - id: implement-blende
    content: 重写 CellGenerator.blendE：用连续样条 + lerp 替代 if/else，实现连续过渡
    status: completed
    dependencies:
      - design-spline
  - id: fix-landshape
    content: 调整 HeightCurve.landShape 控制点，消除 e
    status: completed
---

## 核心需求

修复海岸地形的垂直悬崖和海洋深度不足问题，实现平缓自然的海岸过渡。关键要求：**必须使用连续的整体样条控制，不能有 if/else 分段**（分段会导致断裂）。

## 具体目标

1. **海岸平缓过渡**：从海平面到陆地高度需要自然渐变，消除垂直悬崖和台阶
2. **海洋深度渐进**：大陆架 → 斜坡 → 深渊 → 海沟需要逐渐加深
3. **连续整体样条**：像原版 Minecraft 的 offset.json 一样，用连续样条控制整个 c ∈ [-1, 1] 范围的高度映射，避免 if/else 分支
4. **保留地形细节**：在保持连续过渡的同时，需要与 eLand（地形起伏）混合以保留平原/丘陵/山地等地形特征

## 视觉效果

- 海岸线处从海平面平缓抬升，形成自然的海滩/缓坡
- 海洋深度随离岸距离逐渐加深，有明显的大陆架、斜坡、深渊层次
- 陆地地形起伏自然，无视觉断裂或台阶

## 技术方案：连续整体样条高度映射

### 核心思路

用一个**连续的 Cubic Hermite 样条**定义从 c=-1 到 c=+1 的高度映射（c → baseE），然后通过 **lerp 混合**与 eLand 结合，保留地形细节。**完全避免 if/else 分支**，实现真正的连续过渡。

### 设计原理

1. **连续样条**：用 `NoiseUtil.spline(c, HEIGHT_C, HEIGHT_E)` 定义基础高度映射，样条本身 C1 连续，无断裂
2. **lerp 混合**：用连续的权重函数 `landWeight = saturate(c / 0.50)` 混合样条输出与 eLand，权重函数也是连续的
3. **细节保留**：海洋细节用 `(1 - landWeight)` 自然衰减，无需分支

### 样条控制点设计

参考原版 offset.json 设计，但适配 GeoGenesis 的 eLand 系统：

| c 值 | baseE | 说明 |
| --- | --- | --- |
| -1.0 | -1.0 | 深海沟 |
| -0.5 | -0.5 | 深海平原 |
| -0.2 | -0.2 | 大陆架 |
| 0.0 | 0.0 | 海岸线（海平面） |
| 0.15 | 0.30 | 近岸过渡（斜率匹配） |
| 0.5 | 0.85 | 内陆（eLand 主导区） |
| 1.0 | 1.0 | 高山 |


### 混合逻辑

```java
// 连续样条输出（C1 连续）
double baseE = NoiseUtil.spline(c, HEIGHT_C, HEIGHT_E);

// 连续混合权重（无 if/else）
double landWeight = NoiseUtil.saturate(c / 0.50);

// 连续混合（lerp 不是分支）
double e = NoiseUtil.lerp(baseE, eLand, landWeight);

// 海洋细节自然衰减
e += OCEAN_DETAIL * d * (1.0 - landWeight);
```

### 斜率匹配

在 c=0 处，海洋侧斜率 de/dc ≈ 1（因为 e=c）。样条在 c=0 处的斜率需要接近 1 以避免视觉折角。通过调整 c=0.15 处的控制点值（0.30）来控制斜率。

### LandShape 死区问题

当前 landShape 在 e=0.04 时输出仅 0.012，形成死区。需要提高 YS[1] 的值（从 0.012 到 0.06），使 e=0.04 时输出约 0.06，避免海岸平台。

### 性能考虑

- 样条计算：O(1) 时间，通过二分查找最近段
- lerp 计算：O(1) 时间
- 无额外内存分配
- 与现有系统完全兼容，仅修改 blendE 方法

### 修改文件

1. `CellGenerator.java`：重写 blendE 方法，用连续样条 + lerp 替代 if/else
2. `HeightCurve.java`：调整 landShape 控制点，消除死区
3. `NoiseUtil.java`：无需修改（已有 spline 和 lerp 实现）