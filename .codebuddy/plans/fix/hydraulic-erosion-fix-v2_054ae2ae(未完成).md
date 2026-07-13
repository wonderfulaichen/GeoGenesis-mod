---
name: hydraulic-erosion-fix-v2
overview: 修复 HydraulicErosion 水滴侵蚀的 5 个根因 bug（方向模型/动量方向/寿命/死代码/阈值），重写 RiverField 接通侵蚀到地形管线，加 1s 硬预算 + 配置对齐。消除平行沟壑与碎片化水塘。
todos:
  - id: rewrite-riversettings
    content: 重写 RiverSettings record 为 11 字段匹配 GeoGenesisConfig，修复编译
    status: pending
  - id: fix-hydraulic-erosion
    content: 重写 HydraulicErosion：flowX/flowY 方向场 + 重力+动量模型 + 修复速度死代码 + 长寿命 + carveRivers 0.06/3 + 1s 硬预算
    status: pending
    dependencies:
      - rewrite-riversettings
  - id: rewrite-riverfield
    content: 重写 RiverField：HydraulicErosion + HeightProvider，region 级 computeRegion + 缓存
    status: pending
    dependencies:
      - rewrite-riversettings
      - fix-hydraulic-erosion
  - id: wire-terrain
    content: 接入 GeoGenesisTerrain：getChunkCells 和 getRegionCells 都调 RiverField 侵蚀 + 清理 SCA
    status: pending
    dependencies:
      - rewrite-riverfield
  - id: fix-config-defaults
    content: GeoGenesisConfig.RIVER_MIN_DISCHARGE 默认 12.0→4.0
    status: pending
  - id: cleanup-and-verify
    content: 删除 RiverSCA/RiverNetwork 旧文件，compileJava 编译通过，runPreview/runClient 目检
    status: pending
    dependencies:
      - wire-terrain
      - fix-config-defaults
---

## 用户需求

修复 HydraulicErosion 水滴侵蚀的 5 个根因 bug（对齐 C++ SimpleHydrology water.h），消除平行沟壑与河流碎片化。

## 核心特征

- 重写方向模型：删除 INERTIA，改为重力下坡加速 + 沿该格既有水流方向的动量正反馈（flowX/flowY 持久方向场），实现 360 度自由蜿蜒河谷。
- 加长水滴寿命：LIFE 从 10/15 → 100/150，水滴可贯穿区域连成河。
- 修复速度死代码：`h0-h0=0` → 真正的下坡重力加速。
- 放宽 carveRivers 阈值：0.12→0.06、半径 2→3，中等流量段也刻蚀标河。
- 重写 RiverSettings record 匹配 GeoGenesisConfig 的 11 字段 schema（修复编译错误）。
- 重写 RiverField 用 HydraulicErosion + HeightProvider 替代旧 BaseRiverGenerator。
- 接入 GeoGenesisTerrain 游戏路径（getChunkCells）和预览路径（getRegionCells）。
- 修正 RIVER_MIN_DISCHARGE 默认 12.0→4.0，使河网密度与预览一致。
- 1s 硬预算自动降级（砍水滴保流畅）。

## 功能与视觉效果

- 游戏内与预览中，河流连成蜿蜒树状水系，平行直线沟壑消失，碎片水塘消失。
- 河谷真实刻入地形（U 形谷），水在谷中流。
- 任一 region 首算 ≤1s，超预算自动减少水滴数。

## 待修代码

1. `HydraulicErosion.java` — 5 个 bug 全部存在（INERTIA/动量方向/LIFE/死代码/carveRivers阈值）
2. `RiverSettings.java` — record schema 与 GeoGenesisConfig 不匹配（20字段 vs 11字段），编译失败
3. `RiverField.java` — 旧版用 BaseRiverGenerator，需重写为 HydraulicErosion + HeightProvider
4. `GeoGenesisTerrain.java` — 侵蚀未接入（getChunkCells 无河谷刻蚀，getRegionCells 用淘汰的 RiverSCA）
5. `GeoGenesisConfig.java` — RIVER_MIN_DISCHARGE 默认 12.0 过高
6. 淘汰代码（RiverSCA.java、RiverNetwork.java）需清理

## 技术栈

- Java 17 + Minecraft Forge 1.20.1（Gradle `gradlew.bat`）
- 零 MC 依赖核心：HydraulicErosion（droplet 侵蚀）、RiverField（region 级装配）、RiverSettings（参数）
- 参考：C++ SimpleHydrology water.h（方向/速度/动量/寿命参数）

## 实现方案

### A. 重写 RiverSettings record（修复编译）

当前 record 20 字段（含大量 @Deprecated D8 旧字段）改为 11 字段匹配 GeoGenesisConfig 构造：

```java
public record RiverSettings(
    boolean enableRivers,
    boolean enableLakes,
    double erosionStrength,      // 0.2-2, default 1.0
    double erosionDropsMul,      // 0.5-3, default 1.5
    double erosionErodeMul,      // 0.3-2, default 1.2
    double riverMinDischarge,    // 0.5-64, default 4.0（从12.0降低）
    double riverValleyDepth,     // 0.01-0.2, default 0.06
    double lakeMinArea,          // default 64.0
    double lakeDepthThreshold,   // default from config
    double precipStrength,       // default 0.8
    int erosionResolution        // default from config
) {
    public static RiverSettings defaults() { ... }
}
```

### B. 重写 HydraulicErosion（对齐 water.h 原理）

1. **新增 flowX[][]/flowY[][] 持久方向场**：每步 `flowX[iy][ix] += wat*dirUnitX`（仿 momentumx/y_track），由 RiverField 分配并透传。
2. **重写方向模型**：删除 INERTIA。新速度向量：

- 重力加速：`sx += gx*GRAVITY`（GRAVITY=1.0）
- 动量正反馈：`k = MOMENTUM * fmag / (fmag + dis + ε) * max(0, dot(fDir, dir))`；`sx += k*fx; sy += k*fy`（fx/fy = flowX/flowY 归一化，即该格既有水流方向）

3. **定长步进**：`posX += dirX*STEP; posY += dirY*STEP`（STEP=1.0）
4. **修复速度死代码**：L131 `h0-h0` → 用真正的 dh（h0 - nextH0）
5. **参数对齐**：GRAVITY=1.0, MOMENTUM=0.8, ENTRAINMENT=10, EVAP_RATE=0.001, LIFE_LARGE=100, LIFE_FINE=150
6. **carveRivers 放宽**：阈值 0.12→0.06，半径 2→3
7. **1s 硬预算**：`COMPUTE_BUDGET_NS = 1_000_000_000L`，每 1024 滴检查一次，超预算跳出水滴循环（降级砍水滴，carveRivers 仍全量跑）

### C. 重写 RiverField（HydraulicErosion + HeightProvider）

完全重写。核心逻辑：

- 构造器：`RiverField(long seed, HeightProvider land, RiverSettings settings)`
- `computeRegion(int ox, int oz, int sizeX, int sizeZ)`：

1. 分配 `h[gsz][gsz]`（= size+2*PAD）从 `land.landHeight()` 填入
2. 分配 `dis[gsz][gsz]`、`flowX[gsz][gsz]`、`flowY[gsz][gsz]`、`precip[gsz][gsz]`
3. 从 `land.provinceWeights()` + `land.precipWeightAt()` 填 precip（雨影调制）
4. 调用 `erosion.apply(h, dis, flowX, flowY, gsz, ox, oz, ...)` 执行水滴侵蚀
5. 调用 `erosion.carveRivers(h, dis, ...)` 刻蚀河谷
6. 调用 `TileLakeSolver.solve(h, ...)` 解湖
7. 返回结果（carved h + riverMask + lakeMask + discharge）

- `getSample(int worldX, int worldZ)` → `RiverSample`（从 region 结果提取）
- 按 region 级 tile 缓存（LongCache），确定性

### D. 接入 GeoGenesisTerrain

**getChunkCells（游戏内）**：

```
1. 逐 cell populate（地形+气候）
2. riverField.computeRegion(cx*16, cz*16, 16*hs, 16*hs) → 侵蚀结果
3. 将 carved 高度写回 cell.height（用 landToWorld 从 eCarved 换算世界 Y）
4. 写回 cell.riverMask / lakeMask 等
5. lakeGenerator.generateLakes（在侵蚀后的地形上填湖）
```

**getRegionCells（预览）**：

```
1. 逐 cell populate
2. riverField.computeRegion(obx, obz, cellsX*hs, cellsZ*hs) → 侵蚀结果
3. 写回 cell 字段
4. lakeGenerator
```

### E. 配置修正

- `GeoGenesisConfig.RIVER_MIN_DISCHARGE` 默认 12.0→4.0，defineInRange 下限 1.0→0.5
- 其余配置（erosionStrength/erosionDropsMul/erosionErodeMul/riverValleyDepth）已对齐，不改动

### F. 清理淘汰代码

删除 `RiverSCA.java`、`RiverNetwork.java`；`BaseRiverGenerator.java` 若存在也删除。清理 `GeoGenesisTerrain` 中 SCA 相关引用。

## 性能考量

- region 512 → gsz=544，interior≈528，area≈278k
- largeDrops≈area*0.015*dropsMul≈6.3k，fineDrops≈area*0.030*dropsMul≈12.5k
- 平均寿命~100，核~80 点 → 约 2 亿次浮点操作 ≈ 数百 ms
- 1s 硬预算作为极端兜底
- 降级仅减少水滴数，后续 carveRivers/LakeSolver 仍全量跑
- LongCache 按 region tile 缓存，重复访问无感

# Agent Extensions

无 Agent Extensions 需求。