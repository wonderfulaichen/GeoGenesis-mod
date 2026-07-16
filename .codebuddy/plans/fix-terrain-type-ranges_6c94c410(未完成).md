---
name: fix-terrain-type-ranges
overview: 根据地貌学原理和 MC 模组高度（Y 63-320），重新校准各地形类型的 eLand 输出范围，修复高原公式中的同轴台阶问题，调整类型间边界的平滑过渡。
todos:
  - id: fix-type-constants
    content: 更新 TypeGenerators.java 中 8 个类型的范围常量为新值
    status: pending
  - id: fix-plateau-formula
    content: 重写 samplePlateau 方法，删除 saturate(|r|/0.5) 阶跃函数，改用偏置平滑单噪声
    status: pending
    dependencies:
      - fix-type-constants
  - id: fix-thresholds
    content: 检查 TypeLandShape 阈值与新范围兼容性，必要时微调
    status: pending
    dependencies:
      - fix-type-constants
  - id: compile-and-verify
    content: 编译验证（gradlew compileJava --rerun-tasks）并运行预览（gradlew runPreview --args=12345）
    status: pending
    dependencies:
      - fix-plateau-formula
      - fix-thresholds
  - id: update-memory
    content: 更新工作日志和 MEMORY.md 记录范围调整
    status: pending
    dependencies:
      - compile-and-verify
---

## 产品概述

修复各地形类型的 eLand 输出范围和噪声公式，使其符合地理学特征。核心问题：高原噪声公式 `saturate(|r|/0.5)` 产生大面积内部谷地（shape=0 → eLand=PLAT_LO=0.22），且 HILLS max=0.18 与 PLATEAU min=0.22 差距太小导致过渡区骤降到海平面。

## 核心功能

1. **类型范围合理化**：按 MC 模组高度 Y=63+255*eLand 设定，各类型层层递进、相邻类型范围兼容
2. **高原公式重写**：删除 `saturate(|r|/0.5)` 阶跃函数，改用偏置平滑噪声使高原始终在特征范围内
3. **SNOW 类型修复**：从海平面级 0.01 修正为雪线级 0.88（雪线海拔对应高海拔）

## 技术栈

- Java 17+ (Minecraft Forge 1.20.1)
- 零外部依赖，仅修改 TypeGenerators.java 和 TypeLandShape.java

## 实现方案

### 新范围设计（Y = 63 + 255*eLand）

| 类型 | 旧范围 | 新范围 | 对应 MC 高度 Ymin | Ymax |
| --- | --- | --- | --- | --- |
| BEACH | [0.002, 0.025] | [0.001, 0.015] | 63 | 67 |
| PLAIN | [0.01, 0.05] | [0.015, 0.06] | 67 | 78 |
| HILLS | [0.06, 0.25] | [0.06, 0.25] (不变) | 78 | 127 |
| BASIN | [0.01, 0.10] | [0.015, 0.08] | 67 | 83 |
| PLATEAU | [0.22, 0.58] | [0.32, 0.55] | 145 | 203 |
| MOUNTAINS | [0.25, 0.82] | [0.30, 0.85] | 140 | 280 |
| PEAK | [0.65, 0.92] | [0.75, 0.95] | 254 | 305 |
| SNOW | [0.01, 0.12] | [0.88, 1.0] | 287 | 320 |


**设计原则**：

- PLAIN 与 HILLS 重叠区间 [0.06, 0.06]：边界处高度兼容
- HILLS max=0.25 与 PLATEAU min=0.32 差 0.07（Y18 块）：高原明显高于丘陵
- PLATEAU [0.32, 0.55] 与 MOUNTAINS [0.30, 0.85] 重叠：高原在低山范围内持续高平
- SNOW 从 0.01 提到 0.88：雪线以上高海拔

### 高原公式修复

**旧公式**（问题所在）：

```java
double rl = 2*saturate((platLow+1)*0.5)-1;  // centering
double rm = 2*saturate((platMid+1)*0.5)-1;
double r = 0.7*rl + 0.3*rm;
double shape = saturate(|r|/0.5);  // 阶跃：|r|<0.5时shape=0 → PLAT_LO
return lerp(PLAT_LO, PLAT_HI, shape);
```

**新公式**（偏置平滑噪声）：

```java
double n = platLow.compute(wx, wz);  // 只用单噪声
double t = saturate((n+1)*0.5);      // → [0,1]
// 偏置到上半范围 [0.35, 1.0]，高原永远在 PLAT_LO 以上
double biased = 0.35 + t * 0.65;
return lerp(PLAT_LO, PLAT_HI, biased);
```

解释：`biased ∈ [0.35, 1.0]` 确保高原的 eLand 永远在 `lerp(PLAT_LO, PLAT_HI, 0.35)` 以上。当 PLAT_LO=0.32, PLAT_HI=0.55 时，高原最低 eLand = 0.32+0.35*(0.55-0.32) = 0.4005，最高 = 0.55。高原不会有内部谷地。

### 类型分布阈值的微调

当前阈值 `{0, 0.03, 0.10, 0.28, 0.60, 0.80, 0.90, 1.0}` 与新范围兼容，无需调整。

- BEACH 段 [0, 0.03] → eLand [0.001, 0.015] 兼容
- PLAIN 段 [0.03, 0.10] → eLand [0.015, 0.06] 兼容
- HILLS 段 [0.10, 0.28] → eLand [0.06, 0.25] 兼容
- PLATEAU 段 [0.28, 0.60] → eLand [0.32, 0.55] 兼容
- MOUNTAINS 段 [0.60, 0.80] → eLand [0.30, 0.85] 兼容
- PEAK 段 [0.80, 0.90] → eLand [0.75, 0.95] 兼容
- SNOW 段 [0.90, 1.0] → eLand [0.88, 1.0] 兼容

### 修改文件

**TypeGenerators.java**（修改）：

- 所有 8 个类型的范围常量更新
- `samplePlateau` 公式重写：删除 `platMid` + `|r|/0.5`，改用单噪声偏置
- `sampleSnow` 范围从低海拔改为高海拔

**TypeLandShape.java**（不变）：

- 阈值数组与新版范围兼容，无需修改

**编译验证**：`gradlew compileJava --rerun-tasks`

**预览验证**：`gradlew runPreview --args=12345`

## 实现注意事项

- 修改后需确保 `dominantType()` 与 `typeAtThreshold()` 类型映射一致（不变）
- `dominantType` 中的硬编码阈值函数 `if (baseElev < 0.03)` 等与 ELEV_THRESHOLDS 一致
- SNOW 的噪声节点不变（seed offset 310），仅映射范围从 [0.01, 0.12] 改为 [0.88, 1.0]
- 高原删除 `platMid` 噪声节点引用（但保留字段和 seed 播种，避免浪费；实际可注释掉播种行）