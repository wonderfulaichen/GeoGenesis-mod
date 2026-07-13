---
name: 海岸平台修复-精简方案
overview: 放宽 HydraulicErosion 水滴生成门槛，让近岸海洋（大陆架）也生成水滴参与侵蚀，形成自然入海口/河口纹理，消除海岸裸平台。保持 carveRivers 陆地限定 + smoothErosionResult 海底全平滑以杜绝海底树枝状河痕。不采用归一化空间大重构（当前 RiverField 已在真实方块高度场运行，归一化非根因）。
todos:
  - id: relax-spawn-threshold
    content: 修改 HydraulicErosion.apply() 水滴生成门槛 baseH<=seaNorm 放宽到 seaNorm-COAST_SUB，让近岸浅海参与侵蚀并更新注释
    status: completed
  - id: build-verify
    content: 执行 gradlew.bat build 确认编译通过
    status: completed
    dependencies:
      - relax-spawn-threshold
  - id: visual-check
    content: runPreview/runClient 目检海岸 shelf 侵蚀纹理与无海底河痕
    status: completed
    dependencies:
      - build-verify
---

## 用户需求

1. 修复海岸"平台"bug：海洋中存在平缓台阶状裸平台，侵蚀（河流沟壑/水滴纹理）到此戛然而止，缺少自然入海口。
2. 用户实测备份版本（物理侵蚀-基本无断裂-河流还在开发中，ErosionEngine.java）无此问题：侵蚀延伸入海、有自然河口。
3. 强约束：地形核心必须脱离 MC，纯计算核心（RiverField / HydraulicErosion / TileLakeSolver）不得 import MC。

## 核心特征

- 近岸大陆架（浅海）参与水力侵蚀，生成自然河口与侵蚀纹理，消除裸平台。
- 深海（abyss）仍保持平静、不被侵蚀。
- 海底不出现树枝状河道刻痕（对齐之前"海底侵蚀痕迹"修复的成果）。
- 下游 e-space 契约（carvedE / baseE / landMask / dis / riverMask / lakeMask / lakeLevel）完全不变。

## 技术栈

- 纯 Java，零 MC 依赖核心（RiverField / HydraulicErosion / TileLakeSolver 保持不 import MC）。
- HeightCurve 作为 e ↔ 方块高度 的唯一边界映射器（其内部持有 seaLevel / mountainCap 等，属允许的边界换算）。

## 实现方案

### 关键结论（已读实际代码确认）

- 当前 `RiverField.computeRegion` 已在**真实方块高度场**运行：`h = hc.heightF(e)`（line 91-92）、`seaNormY = hc.heightF(0.0)`（line 36）、`heightScale = mountainCap - seaLevel`（line 37）。坡度梯度真实存在，故无需"归一化高度空间大重构"。
- 平台真正根因在 `HydraulicErosion.apply()` line 127 的水滴生成门槛：
`if (Float.isNaN(baseH) || baseH <= seaNorm) continue;`
只有方块高度 > seaNorm(≈63, 纯陆地) 才生成水滴。整片海洋（<63）不生成；近岸大陆架虽被 `RiverField` 填入 `h`（line 84，base>-NEAR_SHORE_LIMIT=-0.35），但自身无粒子、且大陆架平缓导致陆地粒子顺坡流不进来 → 裸平台。
- `carveRivers`（line 272/293 跳过 `h<=seaNorm-estuaryMax`）只刻陆地+近岸河口带、`smoothErosionResult`（line 334-338 海平面以下全平滑）已能避免海底树枝状河痕，无需保留 NaN 截断。

### 对齐备份的精简修法（方案 A）

仅改 `HydraulicErosion.apply()` 一行生成门槛，让近岸浅海也能生成水滴参与侵蚀：

- 生成门槛：`baseH <= seaNorm` → `baseH <= seaNorm - COAST_SUB`
- `COAST_SUB` 为实例字段（line 60，=0.15*heightScale 方块单位），`seaNorm - COAST_SUB ≈ 34` 方块高度，对应近岸浅海 e∈[-0.12, 0]。
- 近岸浅海生成水滴 → 自然河口/纹理，消除平台。
- 深海（<34 方块高度）不生成、保持平静（对齐备份"只排除最深 abyss"）。
- 流动停止 `h0 < seaNorm - COAST_SUB`（line 169）保持不变：水滴顺大陆架流入近岸、到深海停。
- `aboveSeaFactor`（line 171）用 COAST_SUB 计算，近岸浅海侵蚀力 0→1 自然衰减。
- `carveRivers` / `smoothErosionResult` 保持不变：海底无树枝状河道 + 海底沟壑全平滑消除。

### 实现要点

- 利用现有 `RiverField.NEAR_SHORE_LIMIT=0.35` 与生成门槛带对齐：其高度范围 `heightF(-0.35)≈-9` 到 `heightF(0)=63` 已覆盖 `seaNorm-COAST_SUB≈34`，h 填充无缺口，故 NEAR_SHORE_LIMIT 不改。
- 同步更新 line 124-127 注释，说明近岸浅海（含大陆架）参与侵蚀、深海不生成。
- 下游 e-space 契约不变，`carvedE` 仍经 `eFromHeightF` 逆映射。

## 架构设计

- 不引入新架构/新模块。改动仅限 `HydraulicErosion.apply()` 一处门槛常量，侵蚀核心仍是"世界坐标确定性撒滴 + 动量正反馈 + carveRivers 流量刻蚀 + priority-flood 解湖"既有管线。
- 零 MC 依赖、世界坐标纯函数（跨 chunk/tile 无缝）等既有正确性约束全部保留。

## 目录结构

```
forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/river/
└── HydraulicErosion.java   # [MODIFY] apply() line 127 水滴生成门槛由 baseH <= seaNorm 放宽到 baseH <= seaNorm - COAST_SUB；更新对应注释。其余（simulateDrop 停止条件、carveRivers、smoothErosionResult）不改。
```

（RiverField.java / HeightCurve.java / CellGenerator.java / TileLakeSolver.java 均无需修改）

## 验证

- 编译：`gradlew.bat build` 通过。
- 目检：`runPreview --args=种子` 看海岸 shelf 全段是否有侵蚀纹理、入海口是否自然连续；`runClient` 实机确认无裸平台、无海底树枝状侵蚀伪影。