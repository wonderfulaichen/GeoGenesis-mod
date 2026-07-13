---
name: fix-ocean-shelf-erosion-platform
overview: 修复大陆架“平台”bug：将 RiverField 侵蚀高度场从无梯度的e空间转换为真实Y高度空间，使水滴能感知大陆架坡度，自然流经整个 shelf，消除侵蚀戛然而止的裸平台。
todos:
  - id: add-height-curve-inverse
    content: "HeightCurve.java: 新增 yToEInv(double y) 二分反查方法"
    status: pending
  - id: update-riverfield-construct
    content: "RiverField.java: 构造器新增 minY/maxY 参数，computeRegion 中实现 e↔Y 空间转换"
    status: pending
    dependencies:
      - add-height-curve-inverse
  - id: adapt-erosion-thresholds
    content: "HydraulicErosion.java: 将 seaNorm/COAST_SUB/SMOOTH_DEPTH_BAND 适配为动态 Y-space 参数"
    status: pending
    dependencies:
      - update-riverfield-construct
  - id: update-terrain-wiring
    content: "GeoGenesisTerrain.java: 传递 minY/maxY 给 RiverField; 更新 writeRiverFields 适配"
    status: pending
    dependencies:
      - update-riverfield-construct
  - id: fix-check-compat
    content: "river_check/Check.java: 适配 RiverField 构造器签名变更"
    status: pending
    dependencies:
      - update-riverfield-construct
---

## 根因分析

"海岸平台"bug 的根本原因：**当前侵蚀系统在 e-space（大陆性标量空间）上运作，海洋格 h=base~=c 近乎平坦，水滴感知不到重力梯度，加上 COAST_SUB=0.15 截断在 shelf 中部，导致 shelf 外半段（e∈[-0.20, -0.15]）完全没有侵蚀纹理，形成光秃"平台"。**

### 备份版本 vs 当前版本对比

| 维度 | 备份版本（正常工作） | 当前版本（有 bug） |
| --- | --- | --- |
| 工作高度空间 | 真实 Y 高度 [0,1] | e-space [-1,1]（~=c，平坦） |
| 水滴生成门槛 | h > 0.02（几乎所有地方，含浅海） | h > seaNorm=0（仅陆地） |
| 水滴停止门槛 | h <= 0.01（近 bedrock） | h < -COAST_SUB=-0.15 |
| shelf 覆盖 | 全 shelf 有侵蚀纹理 | 仅 e∈[-0.15,0] 有纹理，外半段光秃 |
| 重力梯度 | 有（真实深度变化） | 无（ocean e~=c 平坦） |


### 核心修复思路

**在传入侵蚀前，将 h[][] 从 e-space 转换为归一化 Y 高度空间**[0,1]（与备份一致）。让水滴感知 shelf 的真实重力梯度。侵蚀完成后通过 HeightCurve 逆查表还原回 e-space，下游代码完全不变。

```mermaid
flowchart LR
    A[e-space] -->|heightCurve.height| B[绝对Y]
    B -->|(Y-minY)/range| C[归一化Y 0..1]
    C -->|有真实梯度| D[侵蚀+河谷雕刻]
    D -->|minY+h*range| E[绝对Y]
    E -->|yToEInv 二分反查| F[e-space carvedE]
    F -->|保持不变| G[下游全兼容]
```

## 技术方案

### 一、修改文件清单

| # | 文件路径 | 改动类型 | 说明 |
| --- | --- | --- | --- |
| 1 | `src/main/java/.../terrain/HeightCurve.java` | [MODIFY] | 新增 yToEInv 二分反查 |
| 2 | `src/main/java/.../river/RiverField.java` | [MODIFY] | 构造器新增minY/maxY；computeRegion做e↔Y转换 |
| 3 | `src/main/java/.../river/HydraulicErosion.java` | [MODIFY] | 阈值适配Y空间，seaNorm为动态归一化海平面 |
| 4 | `src/main/java/.../terrain/GeoGenesisTerrain.java` | [MODIFY] | 传递minY/maxY给RiverField |
| 5 | `river_check/Check.java` | [MODIFY] | 适配构造器签名变更 |


### 二、详细设计

#### 1. HeightCurve.java — 新增 `yToEInv(double y)` 逆查

- 原理：`height(e)` 在 e∈[-1,1] 单调递增（e↑→Y↑），可二分反查
- 40次迭代达到 2^-40 ~9e-13 精度（远优于 block 级整数精度）
- 利用 `height(-0.20)=seaLevel-shelfDepth` 等已知特性优化初始区间

```java
/** 世界Y→e 二分反查（height(e)单调递增） */
public double yToEInv(double worldY) {
    // 边界检查
    if (worldY <= height(-1.0)) return -1.0;
    if (worldY >= height(1.0)) return 1.0;
    double lo = -1.0, hi = 1.0;
    for (int i = 0; i < 40; i++) {
        double mid = (lo + hi) * 0.5;
        double yMid = height(mid);
        if (yMid < worldY) lo = mid;
        else hi = mid;
    }
    return (lo + hi) * 0.5;
}
```

#### 2. RiverField.java — computeRegion 双空间转换

**构造器新增参数**：`int minY`, `int maxY`

**computeRegion 转换逻辑**（修改第69-92行循环内）：

```
1. 计算 normSeaLevel = (seaLevel - minY) / (maxY - minY)   // 约0.331
2. 对于每格：
   a. worldY = land.landToWorld(eValue)     // e→绝对Y
   b. h[j][i] = (worldY - minY) / heightRange  // Y→归一化[0,1]
3. 传入 HydraulicErosion 的 seaNorm = normSeaLevel
4. 侵蚀 + carveRivers 完成后：
   a. worldY = minY + h[j][i] * heightRange    // 归一化→绝对Y
   b. h[j][i] = heightCurve.yToEInv(worldY)   // 绝对Y→e-space
```

**baseE[][] 不动**：保持 e-space 值，供生物群落/气候分类使用。

**lakeSolver**：接收 Y-space h[][]，其 worldHeightBlocks 参数直接对应 heightRange。

#### 3. HydraulicErosion.java — 阈值动态适配 Y 空间

**seaNorm 参数**：由调用方传入正确的归一化海平面高度（`~0.331`），不再假设为 0。

**COAST_SUB 常量**：从 `0.15`（e-space）改为由调用方传入 `coastSubY = (float)((heightCurve.height(-0.15f) - minY) / (double)(maxY - minY))`。

等价关系：

- e-space 的 COAST_SUB=0.15 对应 shelf 上 ~18.75 blocks 深度
- Y-space 中 `coastSubY = normSeaLevel - 18.75/384 ~= normSeaLevel - 0.049`

**SMOOTH_DEPTH_BAND 常量**：改为由调用方传入 Y-space 归一化值：

- e-space 的 SMOOTH_DEPTH_BAND=0.10 对应 ~12.5 blocks
- Y-space 中 `smoothBandY = 12.5 / 384 ~= 0.0326`

**carveRivers 的 estuaryMax 参数**：调用方传入 Y-space 归一化值：

- e-space 的 estuaryMax=0.02 对应 ~2.5 blocks
- Y-space 中 `estuaryMaxY = 2.5 / 384 ~= 0.0065`

**simulateDrop 第161行**：`if (h0 < seaNorm - coastSubY) return;` — 在 Y-space 中正常运作。

**simulateDrop 第163行**：`float aboveSeaFactor = Math.max(0f, Math.min(1f, (h0 - seaNorm + coastSubY) / coastSubY));` — Y-space。

**apply 方法签名**：新增 `float coastSubY, float smoothBandY` 参数，或通过现有参数传递。

#### 4. GeoGenesisTerrain.java — 传递新参数

```java
// 构造器 (第51行改动)
this.riverField = new RiverField(seed, cellGen, riverSettings, 
    params.minY(), params.maxY(), params.maxY() - params.minY());
```

**writeRiverFields**（第193-204行）：`carvedE`已转换回 e-space，`cellGen.landToWorld(carvedE)` 调用不变。

#### 5. 调用链整合

```
RiverField.computeRegion:
  loop(每格):
    e = blendE(c, eLand, ...)          // CellGenerator output
    worldY = heightCurve.height(e)     // e→Y (int精度够)
    hNorm = (worldY - minY) / heightRange  // Y→[0,1]
  end
  HydraulicErosion.apply(h, ..., seaNorm=normSeaLevel, coastSubY, ...)
  HydraulicErosion.carveRivers(h, ..., seaNorm=normSeaLevel, estuaryMaxY)
  TileLakeSolver.solve(h, ..., worldHeightBlocks=heightRange)
  loop(每格):
    worldY = minY + h[j][i] * heightRange  // [0,1]→Y
    h[j][i] = heightCurve.yToEInv(worldY)  // Y→e
  end
  return RiverRegion(carvedE=h, baseE保持不变, ...)
```

### 三、兼容性评价

| 下游字段 | 改动前状态 | 改动后状态 | 是否兼容 |
| --- | --- | --- | --- |
| carvedE | e-space | e-space（已转换回） | 完全兼容 |
| baseE | e-space | e-space（未动） | 完全兼容 |
| landMask | boolean | boolean（未动） | 完全兼容 |
| dis | 流量场 | 流量场（未动） | 完全兼容 |
| riverMask | boolean | boolean（未动） | 完全兼容 |
| lakeMask/level | 湖掩码/水位 | 已转回 e-space | 完全兼容 |
| lakeSolver | e-space高度+worldHeightBlocks | Y-space高度+heightRange | 兼容（算法不依赖绝对原点） |


### 四、性能分析

- 额外开销：每格 1 次 `heightCurve.height()` + 每格 1 次 `heightCurve.yToEInv()`（40次二分迭代）
- 对 256×256 region（含PAD）约 65536 格，每格 41 次 height() 调用
- 每次 height() 是 ~20 次浮点操作 + 1次样条插值
- 总开销 ~5300万次浮点操作，< 5ms 在现代 CPU 上

### 五、不采用方案说明

- **不改 HydraulicErosion 算法**：只改 RiverField 输入输出转换层，保留其零 MC 依赖纯计算特性
- **不在 e-space 中添加人工梯度**：e≈c 的平坦是设计选择（c 是深度样条坐标），强行加梯度会破坏样条一致性
- **不在 HydraulicErosion 中新增接口方法**：通过现有参数传递 seaNorm/coastSubY 等阈值，最小化算法改动

## Agent Extensions

### SubAgent

- **bmad-architect**：对架构方案进行质量评分，确保 e↔Y 空间转换的正确性和完整性
- **bmad-dev**：在方案确认后自动化执行多文件的修改实现，管理文件间的依赖关系和编译验证