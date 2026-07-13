---
name: coastal-erosion-extend
overview: 延长海岸侵蚀区：让河流刻蚀的河谷/侵蚀纹理从内陆一路延伸到水线，河口刻得更深更长（淹没河口观感），消除"离海岸线几格就结束"的短促感。仅改 HydraulicErosion.java 两处衰减带，硬编码合适值，不进 Config。
todos:
  - id: widen-estuary
    content: 调整 HydraulicErosion 河口收口常数 COAST_SUB(0.05→0.08) 与 COAST_RANGE(0.15→0.40)
    status: completed
  - id: narrow-suppress
    content: 收窄 smoothErosionResult 近岸回填抑制带(0.15f→0.05f)
    status: completed
  - id: verify-build
    content: compileJava 编译验证并 runPreview 目检海岸侵蚀延伸到水线
    status: completed
    dependencies:
      - widen-estuary
      - narrow-suppress
---

## 用户需求

海岸附近的侵蚀（河流刻蚀 + 河谷纹理）区域太短，基本在离海岸线几格处就结束了，没有延伸到水线，河口冲刷段也不连贯。

## 核心诉求

- 让海岸附近的侵蚀观感**延伸到水线**：河谷/侵蚀纹理不再在离岸近处被抹平，河口有更长、更连贯的淹没冲刷段。
- 按已确认方案**先硬编码合适值**（不提到 Config 手调）。

## 功能边界

- 仅调整陆侧侵蚀在近岸的衰减/收口参数，使侵蚀区向水线延长；
- 不把侵蚀真正刻入海洋（NaN）格——经核实海床由 `baseShape`/大陆性 `c` 决定，河流场刻海洋格对实际地形无效，故方案聚焦陆侧延伸到水线；
- 不破坏内陆河谷（floorC 仅向下钳制，内陆 `floorC=seaNorm`，不会把内陆河床压到海平面以下造成内涝）；
- 不引入 tile 边界接缝回归（仍受 `RIVER_FIELD_PAD` 约束）。

## 技术栈

- 沿用现有纯 Java 水力侵蚀实现（Forge 1.20.1 模组 `HydraulicErosion`），零 MC 依赖，无需新增依赖。

## 实现方案

### 根因（已核实）

`HydraulicErosion` 在两处用 `0.15·e` 的近岸抑制带提前收掉侵蚀：

1. `smoothErosionResult`（line 296-298）：在离海平面 `seaNorm+0.15f` 的整条带状区内，把刻蚀出的河谷/纹理平滑回填到局部均值 —— 海岸低地侵蚀观感被抹平，河谷在到达水线前就"消失"。
2. `carveRivers` 河口收口（line 256-257，`COAST_RANGE=0.15f`）：河谷 floor 仅在离海 `0.15·e` 内才允许下切到略低于海面 —— 河口冲刷段短、不连贯。

两处叠加 → 海岸附近侵蚀区短促，河流"离水线几格就结束"。

### 关键改动（均硬编码常量，符合用户选择）

1. **加宽河口收口带**：`COAST_RANGE` `0.15f → 0.40f`，`COAST_SUB` `0.05f → 0.08f`。

- 效果：河谷 `floorC` 在更宽的近岸 elevation 带内渐进下切到海面以下（`floorC = seaNorm - COAST_SUB*(1 - smoothstep(0, COAST_RANGE, abvC))`），河口冲刷段更长、更连贯，淹没河口观感更明显。

2. **收窄近岸回填抑制带**：`smoothErosionResult` 中 `seaNorm + 0.15f` 与除数 `0.15f` 均改为 `0.05f`。

- 效果：仅在紧贴水线处轻微回填，让河谷/侵蚀纹理延伸到水线，不再在离岸近处被抹平。

### 安全性与不变项

- `floorC` 仅在 `abvC < COAST_RANGE` 时 `< seaNorm`，内陆 `floorC = seaNorm` → 不会把内陆河谷压到海平面以下，无内涝。
- `aboveSeaFactor`（line 147）陆侧恒为 1.0，非根因，不改。
- 水滴 spawn 门控 `seaNorm + 0.01f`（line 106）保持，侵蚀仍只在陆上发生，海洋判定不受影响。
- 改动全部在 `HydraulicErosion` 常量与后处理阈值，复用既有 `smoothstep` 连续收口，无硬切换、无 tile 接缝回归。

## 实现注意

- 仅改 `HydraulicErosion.java` 一个文件，改动为常量与两处阈值，单函数内修改、风险小；
- 改后必须 `compileJava` 验证编译，再 `runPreview` 目检海岸河谷是否延伸到水线；
- 若 0.40/0.08 仍偏短或过头，属同一对常量微调，不触及架构。

## 目录结构

```
forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/river/
└── HydraulicErosion.java   # [MODIFY] 河口收口常数 COAST_SUB/COAST_RANGE + smoothErosionResult 近岸回填抑制带（0.15f→0.05f）
```

## 架构设计

- 不涉及架构变更，仅调参。侵蚀生产链路 `RiverField.computeRegion → HydraulicErosion.apply/carveRivers/smoothErosionResult` 保持不变，下游 `carvedE`/`baseE`/`riverMask` 契约不变。