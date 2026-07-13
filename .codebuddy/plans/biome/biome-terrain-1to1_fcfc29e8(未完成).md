---
name: biome-terrain-1to1
overview: 重构 BiomeClassifier：地形类型(TerrainClass)作群系主键，借鉴 ReTerraForged 的「温度/湿度 5 档分级(LEVEL_0–4)」做细腻气候细分；每种地形(PLAIN/HILLS/MOUNTAINS/PLATEAU)各持一张 [温档][湿档]→群系 独立表；修复平原被 shape>0.35 误判高原 bug；热带半干旱丘陵/山地出 SAVANNA。
todos:
  - id: rewrite-biome-classifier
    content: 重写 BiomeClassifier：加 tempLevel/humLevel 分级 helper，landClass 改名 plainClass 去 shape 误判，新增 hills/mountains/plateau 独立 5×5 气候表
    status: pending
  - id: verify-build
    content: gradlew build 编译验证通过
    status: pending
    dependencies:
      - rewrite-biome-classifier
  - id: runclient-inspect
    content: runClient 目检群系：热带丘陵/山地出 SAVANNA、高原出台地、平原不再误显高原、温带丘陵出 WINDSWEPT_*
    status: pending
    dependencies:
      - verify-build
  - id: update-scheme-doc
    content: 更新 TERRAIN_TYPE_SCHEME.md §4.7 反映每地形 5×5 温湿分级气候表
    status: pending
    dependencies:
      - rewrite-biome-classifier
---

## 用户需求

1. **群系与地形一一对应匹配**：地形类型（TerrainClass）作为群系选择的主键，每种地形各自拥有一张独立的气候表，气候只在地形内部做细分（保留气候耦合）。
2. **采纳 ReTerraForged「温湿分级(LEVEL_0–4)」增强**：用 5×5 分级（温度 5 档 × 湿度 5 档）替代现有 3 档阈值，让气候细分更真实细腻。
3. **修复 bug**：平原（PLAIN）因综合高度 `shape>0.35` 被误判为「热带草原高原」（`SAVANNA_PLATEAU`）群系——该群系应只属 `PLATEAU` 地形。
4. **扩展热带分支**：热带 + 半干旱的丘陵（HILLS）/ 山地（MOUNTAINS）输出稀树草原（`SAVANNA`），更贴真实稀树草原景观。

## 核心功能

- 每种地形独立 5×5 气候表：`PLAIN`/`HILLS`/`MOUNTAINS`/`PLATEAU` 各持一张「温度档 × 湿度档 → 群系」映射，互不混用。
- 新增两个分级 helper `tempLevel`/`humLevel`（边界采用 ReTerraForged 精确值），把 [-1,1] 温湿映射为 0..4 档。
- 删除 `landClass` 的 `c.shape>0.35` 误判分支，重命名为 `plainClass`。
- `HILLS`/`MOUNTAINS` 新增完整气候细分，热带半干旱（t3/h0-2）出 `SAVANNA`。
- `PLATEAU` 热带统一出 `SAVANNA_PLATEAU`（台地身份），不降级为普通 `SAVANNA`。
- 海洋/海滩/雪峰逻辑保持不变；`RIVER`/`LAKE`/`SNOW`/`BASIN`/default 回退 `plainClass`。

## 视觉效果

群系图层与 `TERRAIN_TYPE` 地形图层逐格协调：平原不再误显高原群系；热带半干旱丘陵/山地显示为稀树草原；高原显示为台地；温带丘陵出被风林木、山地出针叶/云林。预览与游戏内群系分布一致。

## 技术栈

- Java 17 / Minecraft Forge 1.20.1
- 零依赖纯 Java 分类器（`BiomeClassifier` 不 import `net.minecraft`，仅产出 `BiomeClass` 枚举），Swing 预览与 MC 游戏共用同一分类逻辑。
- 无新依赖，纯分类逻辑重构。

## 实现方案

### 策略

重写 `BiomeClassifier.classify(Cell)`，把 `switch(c.terrainType)` 的每个陆地分支分发到**独立的 5×5 气候表方法**。地形作群系大类主键，气候（温湿分级）在地形内部细分。借鉴 ReTerraForged 的 `Temperature`/`Humidity` 分级边界，使气候细分更真实细腻。

### 温湿分级 helper（采用 ReTerraForged 精确边界）

```java
// 温度 [-1,1] → 0..4；边界与 ReTerraForged Temperature.java 一致
private static int tempLevel(float t) {
    if (t < -0.45) return 0;   // 最冷
    if (t < -0.15) return 1;
    if (t <  0.20) return 2;
    if (t <  0.55) return 3;   // 热
    return 4;                  // 最热
}
// 湿度 [-1,1] → 0..4；边界与 ReTerraForged Humidity.java 一致
private static int humLevel(float h) {
    if (h < -0.35) return 0;   // 干
    if (h < -0.10) return 1;
    if (h <  0.10) return 2;
    if (h <  0.30) return 3;
    return 4;                  // 极湿
}
```

### classify() 分发结构

```java
public static BiomeClass classify(Cell c) {
    switch (c.terrainType) {
        case OCEAN: case DEEP_OCEAN: return oceanClass(c);          // 不变
        case BEACH: return (c.isSnow || c.temperature < -0.4) ? SNOWY_BEACH : BEACH; // 不变
        case PLAIN: case BASIN: return plainClass(c);
        case HILLS: return hillsClass(c);
        case MOUNTAINS: return mountainsClass(c);
        case PLATEAU: return plateauClass(c);
        case PEAK: return c.isSnow ? FROZEN_PEAKS : STONY_PEAKS;   // 不变
        case RIVER: case LAKE: case SNOW: default: return plainClass(c);
    }
}
```

### 各地形 5×5 映射表（行=温档 t0..t4，列=湿档 h0..h4）

基于 ReTerraForged 11 气候带语义映射到本项目 `BiomeClass`：

**PLAIN**（基础陆地）

| t\h | h0 | h1 | h2 | h3 | h4 |
| --- | --- | --- | --- | --- | --- |
| t0 | TAIGA | TAIGA | TAIGA | OLD_GROWTH_PINE_TAIGA | OLD_GROWTH_PINE_TAIGA |
| t1 | PLAINS | PLAINS | PLAINS | TAIGA | OLD_GROWTH_PINE_TAIGA |
| t2 | PLAINS | PLAINS | FOREST | BIRCH_FOREST | BIRCH_FOREST |
| t3 | SAVANNA | SAVANNA | SAVANNA | SPARSE_JUNGLE | JUNGLE |
| t4 | DESERT | DESERT | DESERT | DESERT | DESERT |


**HILLS**（开头 `if (c.isSnow) return SNOWY_SLOPES;`）

| t\h | h0 | h1 | h2 | h3 | h4 |
| --- | --- | --- | --- | --- | --- |
| t0 | TAIGA | TAIGA | TAIGA | OLD_GROWTH_PINE_TAIGA | OLD_GROWTH_PINE_TAIGA |
| t1 | WINDSWEPT_HILLS | WINDSWEPT_HILLS | WINDSWEPT_HILLS | TAIGA | OLD_GROWTH_PINE_TAIGA |
| t2 | WINDSWEPT_HILLS | WINDSWEPT_HILLS | WINDSWEPT_FOREST | BIRCH_FOREST | BIRCH_FOREST |
| t3 | SAVANNA | SAVANNA | SAVANNA | SPARSE_JUNGLE | JUNGLE |
| t4 | DESERT | DESERT | DESERT | DESERT | DESERT |


**MOUNTAINS**（开头 `if (c.isSnow) return SNOWY_SLOPES;`）

| t\h | h0 | h1 | h2 | h3 | h4 |
| --- | --- | --- | --- | --- | --- |
| t0 | 同 HILLS t0 | 同 | 同 | 同 | 同 |
| t1 | WINDSWEPT_HILLS | WINDSWEPT_HILLS | WINDSWEPT_HILLS | TAIGA | OLD_GROWTH_PINE_TAIGA |
| t2 | WINDSWEPT_HILLS | WINDSWEPT_HILLS | WINDSWEPT_FOREST | WINDSWEPT_FOREST | OLD_GROWTH_PINE_TAIGA |
| t3 | SAVANNA | SAVANNA | SAVANNA | SPARSE_JUNGLE | JUNGLE |
| t4 | DESERT | DESERT | DESERT | DESERT | DESERT |


**PLATEAU**（台地身份，不强制 snow 分支——冷湿自然落到针叶林）

| t\h | h0 | h1 | h2 | h3 | h4 |
| --- | --- | --- | --- | --- | --- |
| t0 | TAIGA | TAIGA | TAIGA | OLD_GROWTH_PINE_TAIGA | OLD_GROWTH_PINE_TAIGA |
| t1 | PLAINS | PLAINS | PLAINS | TAIGA | OLD_GROWTH_PINE_TAIGA |
| t2 | PLAINS | PLAINS | FOREST | BIRCH_FOREST | BIRCH_FOREST |
| t3 | SAVANNA_PLATEAU | SAVANNA_PLATEAU | SAVANNA_PLATEAU | JUNGLE | JUNGLE |
| t4 | DESERT | DESERT | DESERT | DESERT | DESERT |


### 关键决策与权衡

- **每地形独立方法 + 5×5 嵌套 switch**：可读性高、每种地形可独立调参，符合单一职责与后续扩展（如未来给 BASIN 单独建表）；每函数 ≤80 行。
- **分级边界采用 ReTerraForged 实测值**：温度 `[-0.45,-0.15,0.2,0.55]`、湿度 `[-0.35,-0.1,0.1,0.3]`，使气候带划分贴合成熟实现的 11 气候带语义（更真实）。
- **不动 `oceanClass`/`beachClass`/`PEAK` 阈值与逻辑**：避免引入非预期回归。
- **`RIVER`/`LAKE`/`SNOW` 回退 `plainClass`**：水体在 MC 中本身是水方块，biome 仅影响岸缘植被，用平原群系作陆地回退合理且消除原 `landClass` 的 shape 误判。
- **PLAIN/PLATEAU 不强制 snow 分支**：其冷湿档已自然映射到 `TAIGA`/`OLD_GROWTH_PINE_TAIGA`（针叶林雪原感），避免与 `SNOWY_SLOPES` 语义冲突；仅 `HILLS`/`MOUNTAINS` 用 `SNOWY_SLOPES` 保留地形特征雪坡。

### 性能

分类为 O(1) 两次边界比较 + 二级 switch 查表，无对象分配、无集合遍历，相对现有实现零开销、无回归。

## 架构设计

`BiomeClassifier` 为零依赖分类器，是「地形类型 → 群系」的唯一决策点。`Cell` 已携带 `terrainType`（由 `HeightCurve.classify` 写入）与 `temperature`/`humidity`/`shape`/`isSnow`（由 `climate.apply` 写入）。本次仅重写 `classify` 内部的地形→群系映射结构，输入/输出契约不变，`BiomeMapper.pickKey` 与所有预览/游戏调用方无需改动。

## 目录结构

```
forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/climate/
└── BiomeClassifier.java  # [MODIFY] 重写 classify：landClass→plainClass(去 shape 误判)；新增 tempLevel/humLevel 分级 helper；新增 hillsClass/mountainsClass/plateauClass 独立 5×5 气候表；HILLS/MOUNTAINS 热带半干旱出 SAVANNA；PLATEAU 热带出 SAVANNA_PLATEAU；RIVER/LAKE/SNOW/BASIN 回退 plainClass。
TERRAIN_TYPE_SCHEME.md   # [MODIFY] 更新 §4.7 BiomeClassifier 段落，用「每地形 5×5 温湿分级气候表」替换旧描述，标注 SAVANNA_PLATEAU 仅属 PLATEAU、HILLS/MOUNTAINS 热带出 SAVANNA、温湿用 LEVEL_0–4 分级。
```

## 实现要点

- `landClass` 重命名为 `plainClass`，删除 `c.shape > 0.35` 判断。
- 新增 `tempLevel(float)`/`humLevel(float)` 两个静态 helper（返回 0..4）。
- 新增 `hillsClass(Cell)`/`mountainsClass(Cell)`/`plateauClass(Cell)`，各自以 `switch (tempLevel(c.temperature))` 外层、`switch (humLevel(c.humidity))` 内层实现 5×5 映射；`hillsClass`/`mountainsClass` 开头以 `if (c.isSnow) return SNOWY_SLOPES;` 处理雪坡。
- `classify()` 的 `switch(c.terrainType)` 按上表分发。
- 不改变 `BiomeClass` 枚举、`BiomeMapper`、预览渲染等其它模块。
- 任务完成后同步更新 `TERRAIN_TYPE_SCHEME.md` §4.7 反映新表（知识沉淀）。