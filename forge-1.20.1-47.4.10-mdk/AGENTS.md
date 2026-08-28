# AGENTS.md — GeoGenesis 工作焦点

## 河流系统范式（2026-08-27 切换）

河流已从「稀疏格点判河 + 最近邻吸附」重构为**拓扑驱动河网**（`hydrology/riverline/` 包）：

- **锚点图 D8 下坡选路**：每 region(640wu) 一个确定性锚点（hash 位置 → 局部 e 最低点吸附 = 贴谷），向锚点 e 最低的邻 region 连边（严格下坡 + 防环 → 无环、树状汇流、终达海）。
- **分形蜿蜒线**：`MidpointDisplacement` 生成贴谷偏置折线（多步梯度下降拉进谷底，总位移 ≤ `valleyBiasAmp`）。
- **距离场雕刻**：`HydrologyBlockCarver` 用点到河线距离（smoothstep 横断面 + e 空间高度淡出 + 只下挖），chunk 边界天然无缝。
- **纵剖面反推**：从下游（海口锚定海平面 / 陆地锚点沿岸地形−SINK）向上游逐节点反推，`surface ≤ groundY − SINK` 杜绝悬水。
- **海洋处理**：① 锚点不吸附深海（`snapToValley` 用 `e < oceanE` 跳过）；② 河线进入海洋即截断（`buildRegion` 海岸线截断）；③ 雕刻器 `original < seaLevel` 不雕（海床保护）。

### 关键约束（纯噪声地形系统）
- 一切由 `(worldSeed, 坐标)` 确定性导出；region 惰性构建 + `ConcurrentHashMap` 缓存。
- 选线/贴谷用 `CellGenerator.terrainEQuick`（连续 e 场）；**剖面锚定用 `sampleWu`（含侵蚀 delta 的真实地表）**——曾因锚定基础场导致平原被切出 40 格深峡谷（bug 已修）。
- 复用 `HeightCurve.heightFromE`（非对称映射）。

### 验收状态
- ✅ 确定性（多种子 hash 一致）、chunk 边界无缝（maxInconsistency=0.0）、陆地河道下挖+灌水正确（carved < surface < ground）。
- ⚠️ 河网汇口/region 边界水面台阶未强制同源（约 0.4% 配对有 ≤5.5 块台阶，原型可接受）。
- ⚠️ 种子间地形差异大：部分种子河网偏沿海（陆地河段短），属地形特性非系统 bug。

### 探针任务（`build.gradle` run*Probe）
- `runRiverLinePreviewProbe <seed> <radius> <step>` — ASCII 河网形态目检。
- `runRiverLineBoundaryProbe <seed> <scale>` — chunk 边界/水面台阶一致性。
- `runRiverLineMultiSeedProbe` — 多种子确定性 + 贴谷 + 纵向单调验收。
- `runRiverLineCarveDiagProbe <seed>` — 横穿断面（下挖/灌水/悬水检查）。
- `runHydrologyChunkResultProbe` / `runHydrologyChunkResultBoundaryProbe` — chunk 结果契约。

### 配置面板接线
`GeoGenesisGenerator`：`hydrologyOn = HydrologyExperimentSwitch.hydrologyEnabled() && terrain.riversEnabled()`。面板「河流开关」真实门控新水文路径；旧 RTF 河仅在 `!hydrologyOn && riversEnabled` 时采样（关闭即 NONE）。
