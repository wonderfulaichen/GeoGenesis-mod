---
name: 海岸侵蚀平台感修复
overview: 修复海岸侵蚀平台感问题：水侵蚀往海洋走几格就结束，造成海平面平台。根因：水滴只在海平面以上生成，侵蚀强度衰减带过窄（0.08e），平滑后处理回填范围过大（0.05e）。方案：扩大可侵蚀区域、增加衰减带宽度、减小回填范围。
todos:
  - id: modify-erosion
    content: 修改 HydraulicErosion.java 四处：水滴门槛、COAST_SUB、COAST_RANGE、smoothErosionResult
    status: pending
  - id: compile-verify
    content: 编译验证无语法/类型错误
    status: pending
    dependencies:
      - modify-erosion
  - id: lint-check
    content: 检查 lint 确认无警告
    status: pending
    dependencies:
      - modify-erosion
  - id: update-doc
    content: 更新工作文档记录本次改动
    status: pending
    dependencies:
      - compile-verify
---

## 需求描述

用户截图反馈海岸侵蚀平台感问题：红线左边是陆地（有侵蚀纹理），红线右边是海洋。水侵蚀往海洋走几格就结束了，并且这几格还在海平面上，造成明显的"平台感"。用户强调"可侵蚀的海洋区域太少了"——侵蚀纹理应延伸到海洋区域更远处。

## 根因分析

HydraulicErosion 中三重限制导致侵蚀在海岸线处骤停：

1. **水滴生成门槛**：仅在 `h > seaNorm + 0.01f` 的格子上生成，海洋区域完全没有侵蚀水滴
2. **侵蚀强度衰减带** `COAST_SUB=0.08` 太窄：映射到海岸附近仅对应几格到十几格，侵蚀快速归零
3. **平滑后处理回填** `0.05` 范围抹平了已刻出的侵蚀纹理
三者叠加导致：水滴在海平面附近快速失去侵蚀力 → 后处理再抹平残余 → "几格就结束"

## 修复目标

- 侵蚀纹理能自然延伸到海洋区域更远处（淹没河口、近岸侵蚀痕迹）
- 海岸线附近无明显平台或硬过渡
- 保持与现有海岸连续样条（v5 blendE）的兼容性

## 技术方案

### 修改文件

仅修改 `HydraulicErosion.java`（`d:\Office software\Development Project\GeoGenesis-mod\forge-1.20.1-47.4.10-mdk\src\main\java\com\geogenesis\worldgen\river\HydraulicErosion.java`）

### 改动 1：降低水滴生成门槛（第 108 行）

**现状**：`if (Float.isNaN(baseH) || baseH <= seaNorm + 0.01f) continue;`
水滴仅在海平面以上 0.01e 的格子生成，海洋中零侵蚀。

**改为**：`if (Float.isNaN(baseH) || baseH <= seaNorm - 0.15f) continue;`
允许在海平面以下 0.15e 的近海区域也生成水滴，从海洋侧反向侵蚀海岸。0.15e 约对应浅海区域（HeightCurve 海洋样条中 shelf 起点约在 |c|=0.20，e≈-0.20），不会深入深海。

### 改动 2：加宽侵蚀强度衰减带（第 31 行）

**现状**：`private static final float COAST_SUB = 0.08f;`
衰减带仅 0.08e，侵蚀在海平面附近几格内归零。

**改为**：`private static final float COAST_SUB = 0.20f;`
衰减带扩大到 0.20e。在海岸处 e≈c（斜率约 0.13），0.20e 对应约 1.5 个 c 单位范围，映射到世界坐标约 30~50 格的渐进过渡带。

### 改动 3：缩小平滑后处理回填范围（第 297-302 行）

**现状**：抑制带 `seaNorm + 0.05f`，在海平面以上 0.05e 范围内回填抹平侵蚀。

**改为**：`seaNorm + 0.02f`，仅在极贴近水线处轻微回填，保留近岸侵蚀纹理。

### 改动 4：同步更新 carveRivers 的 COAST_RANGE（第 36 行）

**现状**：`private static final float COAST_RANGE = 0.40f;`
**改为**：`0.60f`，与 COAST_SUB 加宽同步，确保河口冲刷的 smoothstep 过渡带匹配新的衰减宽度。

### 性能影响评估

水滴生成从仅陆地扩展到近海 0.15e 带，水滴数量预计增加 5~15%（浅海区域面积有限）。已有 `COMPUTE_BUDGET_NS` 预算控制（第 123-125 行），超时自动停止，性能安全。

### 与现有系统的兼容性

- 海岸连续样条（CellGenerator v5 `blendE`）：独立，无耦合。侵蚀只修改最终高度 h，不改变 eLand 或 c
- 河蚀刻谷（CellGenerator `computeShape`）：河蚀在 eLand 上刻蚀，HydraulicErosion 在最终 h 上二次侵蚀，互不干扰
- 海洋深度映射（HeightCurve）：HydraulicErosion 侵蚀后的 h 会被海洋填充覆盖（水方块），视觉上只影响海底地形纹理

# Agent Extensions

无扩展需求。本次修改仅涉及 Java 代码改动。