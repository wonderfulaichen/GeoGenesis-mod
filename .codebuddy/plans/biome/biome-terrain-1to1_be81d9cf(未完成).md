---
name: biome-terrain-1to1
overview: 重构 BiomeClassifier：地形类型(TerrainClass)作群系主键，每种地形各自一张 温度×湿度→群系 独立气候表；修复平原被 shape>0.35 误判为高原的 bug；让热带半干旱的丘陵/山地出 SAVANNA。
todos:
  - id: rewrite-biome-classifier
    content: 重写 BiomeClassifier：拆分 plainClass 并新增 hills/mountains/plateau 独立气候表，去 shape 误判
    status: pending
  - id: verify-build
    content: gradlew build 编译验证通过
    status: pending
    dependencies:
      - rewrite-biome-classifier
  - id: runclient-inspect
    content: runClient 目检群系：热带丘陵/山地出 SAVANNA、高原出台地、平原不再误显高原
    status: pending
    dependencies:
      - verify-build
  - id: update-scheme-doc
    content: 更新 TERRAIN_TYPE_SCHEME.md §4.7 反映每地形独立气候表
    status: pending
    dependencies:
      - rewrite-biome-classifier
---

## 用户需求

- 群系与地形**一一对应匹配**：地形类型（TerrainClass）作为群系选择的主键，每种地形各自拥有一张独立的「温度×湿度→群系」气候表，气候只在地形内部做细分（保留气候耦合，贴合原方案精神）。
- 修复现有 bug：平原（`PLAIN`）因综合高度 `shape>0.35` 被误判为「热带草原高原」（`SAVANNA_PLATEAU`）群系，违反方案（该群系应只属 `PLATEAU` 地形）。
- 扩展热带分支：热带 + 半干旱的丘陵（`HILLS`）/ 山地（`MOUNTAINS`）应输出稀树草原（`SAVANNA`），更贴真实稀树草原景观。

## 产品概述

重构 `BiomeClassifier`，建立「地形类型 × 气候 → 群系」的一一对应映射体系，消除地形身份与群系断裂，让每种地形（平原/丘陵/高原/山地）的群系由其自身气候表决定，预览 `TERRAIN_TYPE` 图层与群系图层可逐格对应验证。

## 核心功能

- 每种地形独立气候表：`PLAIN`/`HILLS`/`MOUNTAINS`/`PLATEAU` 各持一张温度×湿度→群系映射，互不混用。
- 删除 `landClass` 中 `shape>0.35→SAVANNA_PLATEAU` 误判分支，平原只产出 `SAVANNA`（非高原后缀）。
- `HILLS`/`MOUNTAINS` 新增完整气候细分（原仅有固定规则），热带半干旱出 `SAVANNA`。
- `PLATEAU` 热带统一出 `SAVANNA_PLATEAU`（台地），`OCEAN`/`BEACH`/`PEAK` 行为保持不变。

## 技术栈

- Java 17 / Minecraft Forge 1.20.1
- 零依赖纯 Java 分类器（`BiomeClassifier` 不 import `net.minecraft`，仅产出 `BiomeClass` 枚举），Swing 预览与 MC 游戏共用同一分类逻辑。

## 实现方案

### 策略

重写 `BiomeClassifier.classify(Cell)`，把 `switch(c.terrainType)` 的每一个陆地分支分发到**独立的气候表方法**。每种地形（平原/丘陵/高原/山地）各自实现一张「温度×湿度→群系」映射，地形作群系大类主键，气候仅做地形内细分。删除 `landClass` 中对 `c.shape>0.35` 的误判（该分支把平原当高原，违反方案 §4.7），并重命名为 `plainClass`。

### 统一分段（所有地形共用）

- 温度 `t`：冷 `t < -0.34` / 温 `-0.34 ≤ t < 0.32` / 热 `t ≥ 0.32`
- 湿度 `h`：干 `h < -0.34` / 中 `-0.34 ≤ h < 0.32` / 湿 `h ≥ 0.32`；热带湿再细分 `h > 0.6`

### 各地形映射表

| 地形 | 冷(t<-0.34) | 温(-0.34≤t<0.32) | 热(t≥0.32) |
| --- | --- | --- | --- |
| **PLAIN** | 干→TAIGA / 湿→OLD_GROWTH_PINE_TAIGA | 干→PLAINS / 中→FOREST / 湿→BIRCH_FOREST | 干→DESERT / 中→SAVANNA / 湿(h>0.6)→JUNGLE 否则 SPARSE_JUNGLE |
| **HILLS** | snow→SNOWY_SLOPES；干→TAIGA / 湿→OLD_GROWTH_PINE_TAIGA | 干→WINDSWEPT_HILLS / 中→WINDSWEPT_FOREST / 湿→BIRCH_FOREST | 干→DESERT / 中→SAVANNA / 湿→JUNGLE 否则 SPARSE_JUNGLE |
| **MOUNTAINS** | snow→SNOWY_SLOPES；干→TAIGA / 湿→OLD_GROWTH_PINE_TAIGA | 干→WINDSWEPT_HILLS / 中→WINDSWEPT_FOREST / 湿→OLD_GROWTH_PINE_TAIGA | 干→DESERT / 中→SAVANNA / 湿→JUNGLE 否则 SPARSE_JUNGLE |
| **PLATEAU** | 干→TAIGA / 湿→OLD_GROWTH_PINE_TAIGA | 干→PLAINS / 中→FOREST / 湿→BIRCH_FOREST | 干→SAVANNA_PLATEAU / 中→SAVANNA_PLATEAU / 湿→JUNGLE |
| **PEAK** | snow→FROZEN_PEAKS 否则 STONY_PEAKS（不变） | — | — |
| **OCEAN/DEEP_OCEAN** | `oceanClass`（现有阈值不动） | — | — |
| **BEACH** | `c.isSnow \ | \ | c.temperature < -0.4 ? SNOWY_BEACH : BEACH`（不动） | — | — |
| **RIVER/LAKE/SNOW/BASIN/default** | 回退 `plainClass`（陆地岸缘用平原群系，去掉 shape 误判） | — | — |


### 关键决策与权衡

- **各地形独立方法而非单一通用 helper**：虽然可抽 9 参数 helper 减少重复，但独立方法可读性更高、每种地形可独立调参，符合单一职责与后续扩展（如未来给 BASIN 单独建表）。
- **不动 `oceanClass`/`beachClass` 实测阈值**：避免引入非预期回归；`BEACH` 阈值保持代码现状 `-0.4`（非方案文档的 `0.3`，属前期实测调参，本次不回退）。
- **`RIVER`/`LAKE`/`SNOW` 回退 `plainClass`**：水体在 MC 中本身是水方块，biome 仅影响岸缘植被，用平原群系作陆地回退合理且消除原 `landClass` 的 shape 误判。

### 性能

分类为 O(1) 分支查表，无对象分配、无集合遍历，相对现有实现零开销、无回归。

## 实现要点

- `landClass` 重命名为 `plainClass`，删除 `c.shape > 0.35` 判断。
- 新增 `hillsClass(Cell)` / `mountainsClass(Cell)` / `plateauClass(Cell)`，内部各用统一分段逻辑。
- `classify()` 的 `switch(c.terrainType)` 分发：`PLAIN`→plainClass；`HILLS`→hillsClass；`MOUNTAINS`→mountainsClass；`PLATEAU`→plateauClass；`PEAK`→固定；`BASIN`→plainClass；`RIVER/LAKE/SNOW/default`→plainClass。
- 不改变 `BiomeClass` 枚举、`BiomeMapper`、预览渲染等其他模块。
- 任务完成后同步更新 `TERRAIN_TYPE_SCHEME.md` §4.7 反映新表（知识沉淀）。

## 架构设计

`BiomeClassifier` 为零依赖分类器，是「地形类型 → 群系」的唯一决策点。`Cell` 已携带 `terrainType`（由 `HeightCurve.classify` 写入）与 `temperature`/`humidity`/`shape`/`isSnow`（由 `climate.apply` 写入）。本次仅修改 `classify` 内部的地形→群系映射结构，输入/输出契约不变，`BiomeMapper.pickKey` 与所有预览/游戏调用方无需改动。

## 目录结构

```
forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/climate/
└── BiomeClassifier.java  # [MODIFY] 重写 classify 的地形分发：landClass→plainClass(去 shape 误判)，新增 hillsClass/mountainsClass/plateauClass 独立气候表；HILLS/MOUNTAINS 热带半干旱出 SAVANNA；PLATEAU 热带出 SAVANNA_PLATEAU；RIVER/LAKE/SNOW/BASIN 回退 plainClass。
TERRAIN_TYPE_SCHEME.md   # [MODIFY] 更新 §4.7 BiomeClassifier 段落，用新「每地形独立气候表」替换旧描述，标注 SAVANNA_PLATEAU 仅属 PLATEAU、HILLS/MOUNTAINS 热带出 SAVANNA。
```

## 关键代码结构

```java
public static BiomeClass classify(Cell c) {
    switch (c.terrainType) {
        case OCEAN: case DEEP_OCEAN: return oceanClass(c);
        case BEACH: return beachClass(c);
        case PLAIN: case BASIN: return plainClass(c);
        case HILLS: return hillsClass(c);
        case MOUNTAINS: return mountainsClass(c);
        case PLATEAU: return plateauClass(c);
        case PEAK: return c.isSnow ? FROZEN_PEAKS : STONY_PEAKS;
        case RIVER: case LAKE: case SNOW: default: return plainClass(c);
    }
}

private static BiomeClass plainClass(Cell c)     { /* 冷/温/热 × 干/中/湿，无 shape 误判 */ }
private static BiomeClass hillsClass(Cell c)     { /* snow→SNOWY_SLOPES; 冷/温/热 × 干/中/湿; 热中→SAVANNA */ }
private static BiomeClass mountainsClass(Cell c) { /* snow→SNOWY_SLOPES; 冷/温/热 × 干/中/湿; 热中→SAVANNA */ }
private static BiomeClass plateauClass(Cell c)   { /* 冷/温/热 × 干/中/湿; 热→SAVANNA_PLATEAU / JUNGLE */ }
```