---
name: 海洋深度模型重构（独立 ocean 场 + 海岸 lerp）
overview: 根除海岸「宽浅平台」：抛弃 baseE(c) 单条样条（仍让海洋深度受 continent 频率间接控制），改为对齐 TerraForged 0.3.x 的真实做法——海洋深度由独立的 ocean 噪声场决定（与 continent 频率彻底解耦），continent 仅作海岸混合 alpha + 海陆边界。参数从 4 个耦合降到 2 个（oceanFieldFrequency / oceanDepthScale）。
todos:
  - id: add-ocean-field
    content: 新建 OceanField.java：独立海洋噪声场，sample 返回 e∈[-oceanDepthScale,0]，与 continent 解耦
    status: pending
  - id: rewrite-blendE
    content: 重写 CellGenerator.blendE 为 lerp(oceanE,eLand,landW) 单条连续式，删 coastSinkE 字段/反算与类注释更新
    status: pending
    dependencies:
      - add-ocean-field
  - id: linearize-height-curve
    content: HeightCurve 删 3 海洋参数，height/heightF 海洋分支改线性 depth=-e*trenchDepth，清理 classify 中 coastSink 注释
    status: pending
    dependencies:
      - rewrite-blendE
  - id: retune-terrain-params
    content: TerrainParams 删 4 耦合字段、trenchDepth 后插 oceanFieldFrequency/oceanDepthScale、defaults 重排
    status: pending
    dependencies:
      - linearize-height-curve
  - id: sync-config-defs
    content: GeoGenesisConfig 删 4 定义与注释、World Height 段增 2 定义、previewParams 重排
    status: pending
    dependencies:
      - retune-terrain-params
  - id: sync-call-sites
    content: GeoGenesisGenerator.configParams 与 GeoGenesisConfigScreen.buildParams 的 TerrainParams 构造同步重排；HydraulicErosion 注释更新
    status: pending
    dependencies:
      - sync-config-defs
  - id: clean-toml
    content: run/config/geogenesis-common.toml 删 4 旧 key、增 2 新 key，避免孤 key 与字段错位
    status: pending
    dependencies:
      - sync-call-sites
  - id: build-verify
    content: 执行 gradlew.bat build 确认编译通过
    status: pending
    dependencies:
      - clean-toml
  - id: visual-check
    content: 运行 runPreview/runClient 目检：海洋无宽浅平台、层次自然、海岸无悬崖、陆地起伏正常
    status: pending
    dependencies:
      - build-verify
---

## 用户需求

- 根除海岸"宽浅平台"视觉 bug：禁用侵蚀后平台仍在，根因在地形层（海洋深度模型）而非侵蚀系统。
- 否决"继续修修补补"与"海陆两段衔接"旧范式：`oceanShelfFraction/oceanSlopeFraction/oceanTrenchOnset/coastSink` 四参数相互耦合，调一个崩一个，反复修过无数次悬崖/平台/台阶。
- 采用用户确认的方向**「独立 ocean 场 + continent 海岸 lerp」**，对齐 TerraForged 0.3.x 真实"海陆一体"：海洋深度来自独立噪声场，与 continent 频率彻底解耦；continent 只作海岸混合权重 + 海陆边界。参数从 4 个耦合降到 2 个。
- 海洋层次（海沟/深海/大陆坡/大陆架）由独立 ocean 噪声场自身形态自然产生，不再由 continent 控制点间接推导。
- 海岸过渡为单条连续表达式，无海陆两段 if/else 衔接，根除接缝/悬崖/平台。

## 核心特征

- 海洋深度来自**独立 `OceanField` 噪声场**（低频主体 + 中频细节），采样得 `e∈[-oceanDepthScale, 0]`，与 continent 频率完全无关；大陆架/深海/海沟层次由该场自然分布决定。
- `blendE` 重写为单条连续式 `e = lerp(oceanE, eLand, landW)`：`oceanE` 来自独立 ocean 场，`landW = smoothstep(c/COAST_WIDTH)` 为 continent 作的海岸混合权重（海洋 0、内陆 1），陆地 `eLand`（地质过程范式）作为被混合项，基底共用同一函数。
- `HeightCurve.height(e<0)` 退化为线性 `depth = -e * trenchDepth`（移除 4 段 spline），`trenchDepth` 作最大海深刻度；`shelfDepth/deepOceanDepth` 仅作分类阈值。
- 删除 `oceanShelfFraction/oceanSlopeFraction/oceanTrenchOnset/coastSink` 四耦合参数，新增 `oceanFieldFrequency/oceanDepthScale` 两参数（TerrainParams record 在 `trenchDepth` 后插入）。
- 海岸自海平面平滑升起，无下沉、无悬崖；河流/湖泊/气候/预览下游契约（e 空间语义）完全不变。

## 技术栈

- 纯 Java，零 MC 依赖核心（`CellGenerator` / `HeightCurve` / `TerrainParams` / `OceanField` 不 import MC）。
- `HeightCurve` 继续作为 e↔世界高度的唯一边界映射器（持有线性海洋映射）。
- 复用既有 `CellGenerator.fractal` / `NoiseUtil.spline` / `NoiseUtil.smoothstep` / `NoiseUtil.clamp` / `NoiseUtil.saturate` / `Noise` 包装链，不引入新依赖。
- 新建 `OceanField`（对齐既有 `SimpleContinent` 模式，单一职责：产出独立海洋深度场）。

## 实现方案（核心思路）

把"海洋 `e=c` 线性 + `HeightCurve` 4 段 spline 二次映射"的双层间接，彻底改为**海洋深度由独立 `OceanField` 噪声场决定、continent 只作海岸 lerp 权重**——对齐 TerraForged 0.3.x 的 `getOcean`/`getBlend` 真实做法（`lower = ocean.getValue → toDepthNoise`，`heightNoise = lerp(lower, upper, alpha)`）。海洋深度与 continent 频率解耦，大陆架宽度不再受 continent 低频等值线间距间接推导。

### 关键设计决策

1. **`OceanField` 独立海洋噪声场（新建 `worldgen/terrain/OceanField.java`）**：类注释明确"与 continent 解耦，海洋深度唯一来源"。构造器用 `fractal(7, oceanFieldFrequency, 4, 2.0, 0.5)`（低频主体，归一化 [-1,1]）叠加中频 simplex 细节（海山/海沟起伏），`sample(x,z)` 返回 `clamp(o*0.5+0.5,0,1) * -oceanDepthScale` → `e∈[-oceanDepthScale, 0]`（0=海平面，负值=深度）。低频主体给大尺度深海平原/大陆坡，中频细节给局部起伏，层次自然。
2. **`blendE` 重写为单条连续式**：`double landW = smoothstep(clamp(c/COAST_WIDTH,0,1)); double e = lerp(oceanE, eLand, landW); if (c<0) e += OCEAN_DETAIL * d * saturate(-c);` —— 海洋侧 `oceanE` 来自 `OceanField`，陆地 `eLand` 为地质过程；`landW` 由 continent 平滑门控（海洋 0、内陆 1），无 if/else 分段，海岸处连续。删 `coastSinkE` 字段与反算。
3. **`HeightCurve.height/heightF` 海洋分支线性化**：`depth = clamp(-e,0,1) * trenchDepth`，移除 `tKnots/dKnots` 4 段 spline 与 `oceanShelfFraction/oceanSlopeFraction/oceanTrenchOnset` 三字段依赖。`eFromHeightF` 二分反解仍有效（e∈[-1,1] 单调，e=0 处陆地/海洋同取海平面）。`classify` 删 `coastSink` 下沉注释，按真实深度 `seaLevel - y` 与 `shelfDepth/deepOceanDepth` 阈值分 `SHALLOW_OCEAN/CONTINENTAL_SHELF/DEEP_OCEAN` 不变。
4. **`TerrainParams` record 重排**：删除 `oceanShelfFraction/oceanSlopeFraction/oceanTrenchOnset/coastSink` 四字段，在 `trenchDepth`（原第 14 位）后插入 `oceanFieldFrequency`/`oceanDepthScale` 两字段；`defaults()` 同步重排与默认值（`oceanFieldFrequency=0.0009`，`oceanDepthScale=0.90`）。所有 4 处 `new TerrainParams(...)` 调用点必须按新字段顺序同步。
5. **`GeoGenesisConfig` 配置同步**：删除 `OCEAN_SHELF_FRACTION/OCEAN_SLOPE_FRACTION/OCEAN_TRENCH_ONSET/COAST_SINK` 四定义 + 构造注释，在「World Height」段 `TRENCH_DEPTH` 后新增 `OCEAN_FIELD_FREQUENCY`(0.0009) / `OCEAN_DEPTH_SCALE`(0.90)。`previewParams()` 读取与 `new TerrainParams(...)` 重排。`configParams()`（GeoGenesisGenerator）与 `buildParams()`（GeoGenesisConfigScreen）同步重排。
6. **`HydraulicErosion:338` 注释更新**：删除 `coastSink 海岸下沉` 相关描述，改为说明近岸浅带平滑保留海岸形态与河口。

### 性能与可靠性

- `OceanField.sample` 每采样点一次 4 倍频分形（O(1)），`blendE` 仅增加一次 `lerp` + 一次 `smoothstep`，无新循环/新对象分配，性能与原实现持平（原 `blendE` 也有 `detail.compute` 与分支）。
- 下游 `RiverField`（依赖 `HeightCurve.heightF(0.0)` 取 `seaNormY`、`eFromHeightF` 还原 e 空间）、`HydraulicErosion`、`landHeight`（`c<0` 仍返回 NaN）契约全部不变。
- 删除 4 参数后需同步 `TerrainParams` / `GeoGenesisConfig`（含 `previewParams`）/ `GeoGenesisGenerator` / `GeoGenesisConfigScreen` / 运行时 toml，避免 record 构造器参数错位与陈旧 key 干扰。**核心经验（多次验证）**：修改 Forge Config 字段后必须同步 `run/config/geogenesis-common.toml`（删 4 旧 key、增 2 新 key），否则用户看不到变化或 toml 残留孤 key。

### 已知调优点（v1 后 follow-up，不在本方案范围）

若 `runPreview`/`runClient` 目检出现"深水贴岸"（独立 ocean 场在近岸恰好偏深），可 follow-up 加轻微 continent 近岸浅化项或微调 `oceanFieldFrequency/oceanDepthScale`。v1 严格对齐 TerraForged 保持纯净解耦（ocean 深度完全由自身场决定）。