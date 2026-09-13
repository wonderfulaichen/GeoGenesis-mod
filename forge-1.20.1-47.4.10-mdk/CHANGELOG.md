# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

> 地形伪影根治轮（2026-09-12 ~ 09-13）。用户实机确认「笔直线段 + Y 形交汇」伪影消失。

### 修复 / Fixed

- **「笔直线段 + Y 形交汇」伪影**（地形）。三处根因，按发现顺序修复：
  1. **`TypeNoiseProvider.foldHills` 的 `|2n−1|` 折叠**（HILLS / PLATEAU 共用）—— 折叠同时带来「频率翻倍 + 梯度处处满值 + 折痕两侧等值线成对平行」，叠加即「密集波浪状平行细线」。改为 `clampUnit`（**撤销折叠**，仅钳值域，地貌语义交给噪声自身多频叠加）。逐配方高通幅值：`HILLS 0.425→0.193`、`PLATEAU 0.464→0.157`（对照 `MOUNTAINS 0.120`）。注：上一版「圆化折痕」(`sqrt(x²+r²)−r`) 无效 —— 只消二阶不连续，脊谷交替与频率翻倍原样保留。
  2. **`TectonicField.blurDist` 在 `reach` 处硬切换** —— `dist≥420` 走原值、`dist<420` 走环形平均，两分支数值不等 ⇒ 沿 `dist=420` 等值线（= Voronoi 多边形**偏移网**）产生阶跃，经 `boundaryStrength` 印进地形。改为混合权重 smoothstep 渐隐。`dist` 的 `max|∇|` **`37.7 → 1.26`**（距离场物理上界 ~2.4，原值是阶跃铁证）。
  3. **`TectonicField` 的 `stress` 是「分片常数场」**（最终根因）—— 旧式 `stress = dot/|v1−v2|` 在配对不变区域内**与位置无关**（探针铁证 `P50|∇| = 7.9e-17`），配对在 Voronoi 边 / order-2 边切换处沿**直线网**阶跃。改为**连续加权投票** `stressField`（5×5 板块窗口、σ=1000、gain 2.0，处处 C^∞）；删除 `smoothStress` / `stressAt` / `rawStressFor` / `dotCrossToStress` 与 `STRESS_BLUR_*` —— 每次采样少 100+ 次哈希，**性能净收益**（实测 2.16 µs < 5 µs 预算）。

### 新增 / Added

- **诊断探针 [F]「逐分量梯度排查」**（`runTerrainStripePngProbe`）：对 `eLand` 的每个输入分量单独求 `|∇|`，打印 `P99.9/P50` **长尾比**并输出 `gmagC_<分量>.png`。一次运行即可定位「折痕属于哪个场」，取代此前 6 轮的「假设 → 修 → 仍存在」循环。（注意看图陷阱：`|∇|` 图上平滑脊的两翼天然呈亮线，须配合长尾比与线宽判读。）

- **两处失效探针修复**（纯诊断，不影响地形产出）：
  - `HydrologyTerrainEntryProbe` 原用**单个 chunk (0,0)** 断言"换种子哈希应不同"。而该哈希从 FNV offset basis 起累加**雕刻列**，列为空即恒等于 basis（实测 `14695981039346656037` = `0xcbf29ce484222325`）；河流稀疏（≈1.4 条/1000wu²，400×400wu 网格期望仅 ≈0.22 条）⇒ 断言**空洞且必然失败**（`status=FAIL`，与地形无关）。现改为采样 **5×5 chunk 网格**并把 `originalCells` 高程折入哈希 → 种子敏感性在构造上不可能空洞；"含雕刻列的 chunk 数"降为**信息项**（不为判据，避免再次依赖"该区域恰好有河"）。
  - `PlateauProfileProbe` 原选点条件只要求 `argmax==PLATEAU`，实测命中"PLATEAU 权重最大但 `eLand<0`"的**海底**胞 → 整份高原剖面打印的是海底数值（诊断失效）。追加 `eLand ≥ 0.30` 选点条件。

### 已知遗留 / Known Issues

- **高度标定漂移**：应力改为区域尺度场后，同一 304 wu 窗口地形由 `Y 64.5~112.4` 升至 **`88.0~152.9`**（+23~40 块）—— 造山带由「细线」变成「成带」，物理上更正确，且全部守门探针（含专门防抬升污染水文的 `runPrecipRiverWidthProbe`）通过。若需回调：`CONVERGENT_BOOST` 2.5→~1.8，或 `STRESS_VOTE_GAIN` 2.0→1.0。
- **类型场 Voronoi 轴对齐直段**：`TerrainCharacterField` 种子位于**规则网格**（`WARP_AMP=0`）⇒ 类型主导边界存在长水平/垂直直段。配方：仿 `TectonicField.SEED_JITTER` 给细胞种子加抖动（高斯权重下仍 C^∞，无折痕风险）；**须与上条标定合并进行**，避免叠加混淆变量。

### 验证 / Verified

- 守门探针全绿：`runTectonicProbe` / `runTectonicDeformProbe` / `runTectonicWaveProbe`（各向异性 ≈1.0）/ `runTectonicContinuityProbe` / `runPrecipRiverWidthProbe`（head 最干桶 0.928 < 0.95）。
- 分量长尾比：`tect_stress` ∞→1.6、`tect_chain` 68.5→3.0；`eLand` `max|grad|` 0.00245→0.00211。
- `PreviewDisplay.CACHE_SCHEMA_VERSION` **41 → 44**（三处地形产出变更，旧预览缓存必须失效）。

---

## [0.0.1] - 2026-09-10

> **Early Preview** — 首个可玩预览版。世界生成算法与配置格式仍会变更，旧存档不保证兼容。

### 概述

GeoGenesis 是一个以"模拟现实地形"为目标的 Minecraft 地形模组。v0.0.1 重建了温度模型、把群系选择从"地形主导"翻转为"气候主导"，并加入河流绿洲与陡坡裸岩两项真实感特性。

### 修复 / Fixed

- **温度纬向锚点反置**：旧模型 `sin²(z·tempFreq)` 在出生点 (z=0) 恒取 −1（极寒），且是振荡曲线、没有"赤道热 → 两极冷"的单调梯度。改为复用 `Latitude.latitude01`：`temp = 1 − 2·lat`（赤道 +1 → 两极 −1）。出生点附近不再冰冻。
- **群系选择从"地形主导"翻转为"气候主导"**：旧结构是 `switch(terrainType)`——先按地形定大类，气候只在每个地形内部做少量精修，导致赤道平原与温带平原都落 `PLAINS`。现改为 **Whittaker 生物群区（温度 × 降水）× 垂直带谱 × 地形变体**，气候在抖动 Voronoi 气候区上采样（区内温湿恒定，消除椒盐碎斑），垂直带由温度相关雪线驱动（同一座山在赤道是「雨林 → 草甸 → 石峰」，在寒带是「针叶林 → 雪坡 → 冰峰」）。
- **群系变体边界直线**：地形类型边界是 Voronoi 垂直平分线（直线段），直接按 `terrainType` 换群系会让群系边界沿直线走（实测最长 **424 wu** 水平直线）。改为对 5 类地形权重各加独立噪声后取主导类型 → **424 wu → 104 wu**，回到纯气候基线，邻接违例 0。
- **`GeoGenesisBiomeSource.ALL_KEYS` 缺项**：旧列表漏了暖/冻海洋、针叶林、恶地、雪坡等分类器实际会产出的键 —— `possibleBiomes()` 缺项会让对应群系不被结构放置器识别（村庄/神庙/掠夺者哨站等按群系标签定位时跳过）。已按气候主导 v4 映射全量对齐。

### 新增 / Added

- **河流绿洲**（RTG `SurfaceRiverOasis` 范式）：干旱群区沿河流 / 湖泊 <24 wu 范围内，经大尺度噪声斑块门控与海拔截断后，`DESERT` 转为 `SAVANNA`（Whittaker 图上的合法邻居）。新增 `Cell.riverDistance`（到最近河 / 湖的距离）与 `Cell.oasisNoise`。
  - `RiverLineNetwork.distanceToWater` 为**只读**查询，委托既有 `sample()`（自写"扫全部河段"版本漏掉 `RiverLineRegion.lakes`，19/245 例偏差最多 191 wu，自检抓出后改委托，现差值恒为 0）。
  - 群系分类走快速路径 `sampleCellLight`（为把建世界从 7.5 分钟压到秒级，刻意跳过侵蚀 / 雕刻），原本拿不到水文数据 → 绿洲规则只会在预览生效。现由 `GeoGenesisTerrain.fillRiverDistance` 在完整管线与快速路径用**同一条件**填充 → **预览 = 游戏**。实测 17 µs / 次（侵蚀 tile 800ms 的 0.002%）。
- **陡坡裸岩**（RTF `Steepness` tile filter + `ErodeFeature` 范式）：新增 `Cell.gradient`，从侵蚀 tile 的 `postErosion` 高度网格取 ±1 wu 中心差分（tile 自带 padding → 跨 tile 无接缝；chunk 的 16×16 网格边缘只能 clamp，会产生 16 块间距接缝，故不用）。`GeoGenesisGenerator` 落块处 `gradient > 0.40`（≈ 35°）→ 地表铺 `STONE`，优先于 `BiomeClassifier.surfaceOf`（沙漠里的陡崖同样是裸岩）。**未改动 `ErosionEngine`**（`postErosion` 本就存在）。
- **新增诊断探针**：`runClimateBiomeProbe`（气候分异 / 邻接合法性 / 直线段与各向异性 / 精细群区图）、`runRiverOasisProbe`（距离查询一致性与耗时 + 坡度分布标定）。

### 已知遗留 / Known Issues

- `TerrainClass.RIVER` 全工程从未赋值 → `BiomeClassifier.pickKey` 的 `case RIVER` 为死代码。不影响表现（河流靠 `riverSurfaceY` 灌水表现），下个版本清理或接上。
- 陡坡裸岩只在游戏地表可见，预览的群系图层不显示 —— 群系本身仍是森林，裸岩是地表方块属性，与 RTF 的处理一致。
- ~~温度在 `|z| > latitudeScale`（默认 6000 wu）饱和于 −1，形成永久极冠~~ → **✅ 已修（2026-09-11，经两次修正）**：纬度改为【周期性】`lat01 = (1 − cos(2·z / scale)) / 2` —— 世界成为**沿 z 无缝卷绕的圆柱、无极点**，赤道 → 极地 → 赤道无限交替；完整气候周期 `2π·scale ≈ 37,700 格`。同时统一了 `Latitude.DEFAULT_SCALE`(5000) 与 `TerrainParams.latitudeScale`(6000) 的**尺度不一致**（此前预览显示的纬度与生成器实际用的纬度是两个值）。
  - **二次修正**：中间版本用 `|sin(z/scale)|`，它极值处平坦、赤道处陡峭 → z 向 **冷:热 带宽 = 2:1**（真实地球 ≈1:1），用户从预览图直接看出。改用**日照余弦** `T = cos(2z/scale)` 后为 **1:1**（探针 `[10]` 实测 2.00 → 1.00）；极值位置不变（赤道 z=0、极地 z=π·scale/2）。
  - 「北比南宽」经实测**不是模型问题**：`lat01` 是严格偶函数（`max|lat01(z)−lat01(−z)| = 0`），观感来自视口从 z=0（赤道）起、切掉半条带。
  - 连锁：降水均值 0.272 → 0.347，汇流权重 `PrecipWeights.ref` 随之 **0.17 → 0.212** 重标。

- **预览图层 #9（TERRAIN_TYPE） bug + i18n 缺口**（2026-09-11）：
  - TERRAIN_TYPE 图例少 3 条：颜色数组 17 项 vs 名称数组仅 14 项 → 图例取 min=14，SNOW/VOLCANO/VOLCANIC_FIELD 有颜色但图例查不到；`discreteLabelKey(id≥14)` 潜在 AIOOBE。修复：名称数组补齐 17 项 + 语言文件补 `terrain_type.VOLCANO/.VOLCANO_FIELD`。
  - RIVER_TYPE 图例 key 名不匹配：代码用 `big/medium/small`，语言文件是 `main/mouth/trib` → 已修正。
  - CLIMATE_ZONE 图例 key 名不匹配：`Zone` 枚举 `A/B/C/D/E` 生成 `geogenesis.zone.A`，语言文件是 `TROPICAL` 等 → 新增 `zoneLabelName()` 映射。
  - i18n：`continuousLegendLabels()`/`terrainUnderlayLabel()` 不再硬编码中文，改为接收本地化回调；`DisplayPanel` 显示设置全部改用 `I18n.get`；新增 ~30 个语言 key（`geogenesis.legend.*`/`geogenesis.underlay.*`/`geogenesis.settings.display.*`）。
  - 新增 `PaletteProbe`（`gradlew runPaletteProbe`）：离散图层三方一致性自检（颜色/名称/枚举/key 解析），防止手工平行表漂移。验证：8 项 PASS。

- **游戏内「大范围预览」开关不可用/无反馈**（2026-09-11，用户反馈）：
  - 现象：MC 预览窗口无法（或看起来无法）打开大范围预览。原因是游戏内没有实体按键（Swing 端的 `L` 键不存在），且该开关只藏在「采样」页签第 3 行。
  - **根因（真 bug）**：`PreviewDisplay.setLargeArea(true)` 只切换采样管线、**不改缩放** → 点开后画面**毫无变化**（仍在 1:1 精确档），必须再手动滚轮缩到 1:32+ 才见效果 → 被当成"打不开"。
  - 修复：
    1. `setLargeArea(true)` 现**自动缩到 1:64**（`LARGE_AREA_ENTRY_SCALE`），点开即见宽视野气候格局；关闭时回落 ≤1:16。
    2. 在**预览正上方**新增大范围开关按钮（与「◀ 图层 / 图层 ▶」同排），这是主入口；「采样」页签那个保留为次入口。两处共享 `PreviewDisplay` 状态，文案每帧自动同步。
    3. 预览右下角倍率标签在大范围模式下附加「大范围」标记（`geogenesis.preview.large_area`），便于确认当前走哪条管线。
    4. tooltip 文案更新为"开启后自动缩到 1:64…之后仍可继续缩放到 1:1024"。

- **Phase C / D 重验证 + 两处探针口径修正**（2026-09-11，纬度改余弦后复核）：
  - `runFlowAccumProbe` **status=PASS**（profile.violations=0 / border.violations=0 / topo.cycles=0 / gateViolations=0）；
    `coldMs=4810`（此前 ~13.6s，D13 的 `terrainEQuick` 缓存 + `groundYAt` 接线修复见效）。
  - `runPrecipRiverWidthProbe` 复核发现**判据口径错误**并修正：原判据要求 `tail`（河口）最干桶 < 1.0，
    但 `tail = width[last]` 被 `applyEstuary` 喇叭口（×1.9）/ `mouthMax` 上限 / 沿程取大三重规则主导，
    **不纯反映汇流** → 假失败。改为**只以 head（河道中段）为判据**，并新增「桶均降水 / 权重 / 解析预期」诊断列。
    实测 head：最干桶 **0.933**（<0.95 ✓）、最湿桶 **1.058**（>1.05 ✓）→ 核心主张成立；
    幅度约为解析预期（`w^0.252`）的 55~80%，属 `minWidth`/`maxWidth` 护栏的预期压缩。
  - `PrecipFieldProbe [8]` 采样方式修正：原扫固定 z 网格再按 lat01 分桶，纬度映射一变同一桶会采到
    **完全不同的 z 窗口**（跨映射不可比，会误判"荒漠带移位"）。改为**按 lat01 均匀扫描 + 二分反解 z**
    （`zAtLatitude`，对任意单调映射成立）。新映射下荒漠峰值落在 **0.375~0.500** ↔ 约 **37°N**（副热带高压/撒哈拉纬度），判据 PASS。
  - 两处修正的根因相同：**探针隐含假设了特定纬度映射**，公式一变即产生假信号。

- **焚风超幅（5.27 → 3.00 °C）**（2026-09-11）：
  - 问题：`shadowLoss` 被 `shadowRef(40)` 归一化**饱和**，而 `foehnWarm = foehnK·barrier·lapseDiff`
    **线性无上限** —— 二者不对称。实测 `barrier` 可达 137 块（雨影已饱和），焚风算出 5.27，
    超出 §4.4 的 1~3 °C 目标带；极端地形会失控（仅靠 `clamp(temp,−1,1)` 兜住）。
  - 修复：新增 `Params.foehnMax`（默认 **3.0**），`foehnWarm = min(foehnMax, 线性式)`；
    低 barrier 段保留梯度，超出即封顶。探针 [4] 新增**上限判据**（≤3.5）防回归。
  - ⚠️ **单位陷阱（曾算错一次）**：`temp += foehnWarm / 40` 且 1 e 单位 = 40 °C
    → **增益 °C 数值上等于 `foehnWarm`**，故 `foehnMax` 直接写 3.0，**不再除以 40**
    （误写 0.075 会让焚风只剩 0.075 °C ≈ 消失，已被探针判据拦下）。
  - 验证：最大焚风 **3.00 °C**；`runClimateBiomeProbe` 邻接违例仍 **0/20000**（无退化）。

- **D3 修复：侵蚀平滑的池饥饿死锁**（2026-09-11）：
  - 问题：`ErosionEngine.smoothErosionResult` / `smoothDepositionZones` 用
    `TILE_SAMPLER.execute + CountDownLatch.await` 做行级并行。池 core 8 / max 16 / 队列 64，
    而每次仅提交 ≈8 个子任务 → **队列永不满** → `CallerRunsPolicy`（唯一的提交者自救路径）
    **永不触发**；一旦 16 个池线程同时阻塞在 `await`，就无人能取走队列中的子任务 → **永久死锁**。
    且 `CellGenerator:603` 在池内任务里调 `generateErosionTile` → 成环（正是 `CellGenerator:42`
    记录的"池饥饿死锁 27 轮"）。
  - 修复：改用 `IntStream.range(...).parallel()`（ForkJoinPool，work-stealing，调用线程参与执行）；
    原中止语义改为显式检查中断位，保留 `CancellationException("erosion aborted")`。
  - **A/B 实测行为中立**：还原旧代码重跑 `runFlowAccumProbe`，确定性指标逐位相同
    （`hitColumns=4865284`、`fillWater=447345`，其余全同）→ 零行为变化。
  - 新增 `ErosionConcurrencyProbe`（`gradlew runErosionConcurrencyProbe`）：
    ① 24 线程 × 40 次 `sampleWu` 硬超时 180s → 实测 **28.8s 完成、0 错误、tile 缓存有界 256**；
    ② 并行确定性 n=2704 逐位一致。旧代码下该探针即死锁回归守卫。
  - 顺带发现（既有、无行为影响）：`CellGenerator.erosionRoundCounter` 为非原子 `int` 竞态，
    但 `erosionRound` 只写不读（保留诊断字段）→ 记入体检报告待后续接滑窗时改用 `AtomicInteger`。

- **修复：应力场的"物理正确 vs 连续"权衡 — 造山带恢复到 1.5× 且连续性不回退**（2026-09-12）：
  - **背景**：上一轮为消除边界处的 `stress` 骤变（伪影），把地形用的应力从
    「两板块相对速度」改成「与边界**无关**的低频噪声场」。连续性达标（0.119e→0.032e），
    **但造山带从 1.61× 掉到 1.16×** —— 因为"离边界近"与"应力高"变成两件不相关的事，
    **物理联系被切断**（边界处的应力体制本应由该边界的相对运动决定）。
  - **本轮修复**：回归"应力由边界推导"（物理正确），改为对**结果**做空间平滑 ——
    这才是 worldgen `blur_grid` 的**真正含义**（平滑输出剖面，而非把输入换成无关噪声）。
    平滑用 12 点圆周平均，半径 240wu，权重随 `dist` 用 smoothstep **渐隐为 0**
    （避免在作用边缘引入新的硬截断 —— `BOUNDARY_REACH` 已踩过这个坑）。
  - **探针口径修正（重要）**：`TectonicProbe [5]` 原用 `btype`（逐点 Voronoi 配对）筛"汇聚"，
    而 boost 现由 `stress` 驱动 → 两者脱节会得**假失败**。
    先后试过 `stress>0.6` 口径（也错：会把**远离边界的高应力内部点**算进来，
    n=457 vs 92，稀释成 1.18×），最终改为**直接按 boost 强度分组**
    （`boundaryStrength × max(0,stress) > 0.45`）——与生产代码**完全同源**。
  - 实测：造山带 **1.54×**（boost 口径）/ **1.59×**（btype 对照）→ PASS；
    连续性用户种子下 **0.0577e**（≈11 块，较纯低频方案的 0.032e 略高，
    这是"保留物理正确性"的代价，已由加强平滑（120→240wu、8→12 点）压到此前水平以下）。
  - 性能：`TectonicField.sample` **2.7 µs/次**（阈值 5µs，仍在 `terrainEQuick` 同量级）。
  - 验证：11 探针全通过；群系邻接违例 0/20000；FlowAccum cycles/violations/gate 全 0；
    PrecipRiverWidth 多种子均值 PASS。

- **修复：地形"每块各自独立生成"（纹理按板块格子各自定向）**（2026-09-12，三次实测反馈）：
  - **用户反馈**：岩石类型边界导致地形衔接不自然，"完全不像一个整体、每块都相似各自独立生成"。
  - **定性**：这是**实现偏离参考项目**，不是 worldgen 的问题。重读源码后确认我漏掉了关键两步。
  - **worldgen 的正确做法**（`src/elevation.rs`）：
    1. `along/across` **只用于算标量幅度**，不直接当噪声坐标；
    2. 随后 **`blur_grid` 高斯模糊**——源码注释原文
       *"Smooth profiles to eliminate Voronoi ridge discontinuities"*；
    3. 山脊噪声用**世界坐标**采样（`ridged_fbm(wu + rw1, wv + rw2, ...)`）。
  - **我的错误**：把 `alongCoord`（**每个 Voronoi 单元各自定义的相对坐标**）当噪声输入 →
    相邻板块的"沿走向方向"不同 → 同一世界位置纹理走向不同 → **边界处纹理错位**。
    且**未做任何模糊**（worldgen 专门用来消除 Voronoi 不连续的一步）。
  - **修复**：**彻底移除 per-cell 坐标依赖**：
    - T5 褶皱相位：`alongCoord` → **世界坐标** `valueNoise(wx/3000, wz/3000)`
    - T5 断层滑移/断块：`alongCoord` → **世界坐标**噪声 + 世界坐标量化
    - T2 chain：`alongCoord` → **世界坐标**斜向投影
    - 新增**世界坐标分段遮罩**（`segmentMask`）把"平行边界的环状带"打成弧段（避免闭环，
      同时因只用世界坐标而跨边界连续）
    - 删除 `alongFaultCoord()`（per-cell 依赖的根源）
  - 新增 `runTerrainGrainProbe`（纹理方向连续性）：边界处曲率 **1.92×** 内部（阈值 3×）→ 衔接自然。
  - 同步更新两个探针的判据（产品机制已变，旧判据失效）：
    `TectonicDeformProbe [3]` 断层崖改用梯度长尾统计；
    `TectonicContinuityProbe [2]` 改用"跳变不超过合法崖线量级"判据。
  - 验证：12 探针全通过；群系邻接违例 0/20000；FlowAccum cycles/violations/gate 全 0。

- **修复：地质系统「扇形直线射线」**（2026-09-12 第六次，**关键认知突破**）：
  - **决定性认知**：`dist = (d2−d1)/2` 的**等值线本身就是"平行于 Voronoi 边的多边形"**
    ——由**直边 + 折角**构成。因此<b>任何 `dist` 的函数</b>（`decay`、高斯 `boundaryStrength`）
    都会把这套多边形"印"到地形上。**模糊无法解决**：直边软化后仍是直边。
  - **参考项目对照**：`worldgen-master/src/elevation.rs` Phase 2
    用 `blur_grid` 消除 "Voronoi ridge discontinuities" —— 但那是因为它的 profile 是
    全图网格且 `blur_sigma` 可远超特征尺度；本项目按需采样且特征尺度(large)无法这样模糊。
  - **本轮尝试与实测结论（如实记录，避免后人重走）**：
    1. `dist` 环形模糊（R=150，等价 `blur_grid`）→ **直线原样存在**
    2. `valueNoise` 由双线性升级 **Catmull-Rom 双三次（C¹）** → **直线仍原样存在**
       （证明：不是插值阶数问题，而是 `dist` 等值线的多边形几何本身）
  - **最终修法**：让地形**不再依赖 `dist`** ——
    T5 定位改用**纯世界坐标构造带掩码 `beltMask`**（阈值化低频噪声 ⇒ 约 30~40% 面积成
    有机弯曲的构造带）；同时 `FOLD_REACH/FAULT_REACH` 900/700 → **2600**，
    使 `decay(dist)` 在带内≈常数（**常数没有形状** ⇒ 不再印多边形），
    且探针「超出 reach 必须为 0」判据依然成立。
  - **结果**：`comp_deform.png` 由"强扇形直线射线"变为**有机斑块**；
    残留**淡淡的多边形棱面**（来自最后一个 `decay(dist)` 因子）。
  - **遗留（下一步）**：彻底去掉 T5 中最后的 `decay(dist)`（仅用 `beltMask` 定位），
    并同步把 `TectonicDeformProbe [5]`「作用范围有限」判据由"按 dist 截断"
    改为"按掩码归零"（**不得为让 CI 变绿而放宽**）。
  - 保留的正面成果：`valueNoise` 的 Catmull-Rom 升级（C¹，对全场噪声都是改进）；
    `CACHE_SCHEMA_VERSION` → **41**。

- **修复：地质系统的「环状跳变线 / 串珠虚线」（第五次第二轮回）**（2026-09-12）：
  - **根因**：`TectonicField.boundaryStrength` 内的 `if (s.btype() == INTERIOR) return 0.0;`
    是**硬截断**——`btype` 在 `dist >= BOUNDARY_REACH(320)` 处跳变为 INTERIOR，
    使该函数沿 `dist≈320` 的**环**由 `exp(−320²/2σ²)=0.0146` 骤降为 0。跳变量再乘上
    沿环变化的 chain 噪声 ⇒ 地形上一条**亮度随位置起伏的环状线**（用户所见"串珠状虚线"）。
    **修**：`boundaryStrength` 改为**只依赖连续量 `dist`**（高斯在 320wu 已衰减到 1.5%，
    内部区本就≈0，无需用离散标签截断）；另将 `boundaryStrengthChained` 与
    `applyTectonicWeights` 的提前 return 阈值由 `1e-3` 降到 **`1e-6`**（1.6e-7 e，不可见）。
  - `CACHE_SCHEMA_VERSION` **37 → 38**。
  - ⚠️ **仍未解决（已定位到子系统）**：本轮重渲染后仍有 **3 条近竖直、等距、点状的窄陡坎线**。
    用 `comp_deform.png`（T5 形变分量单独渲染）在该坐标复核 → **出现完全相同的三条竖带**
    ⇒ **残留确定来自 `TectonicDeformation`（T5）**。机制判断：T5 的振幅由 `decay(dist)`
    调制，而 `dist` 是 Voronoi 距离场——其**等值线在 Voronoi 顶点处有折角/扇形射线**
    （实测 `comp_deform.png` 可见扇形放射结构）⇒ 振幅被该几何"印"成条带。
    可选方案（**待用户决策**）：
    1. 把 T5 的定位改为**世界坐标平滑掩码**（不再使用 `dist`，彻底脱离 Voronoi 几何）；
    2. 保留 `dist` 定位但**大幅降低 T5 振幅**（牺牲褶皱/断层可见度换干净）；
    3. 只保留褶皱、去掉断层陡坎项（`faultOffsetUnit`）。
  - **本轮已确认修好的**：域扭曲改短波长后，`contours_1block` 中那几根**贯穿全图的笔直长线已消失**
    （此前是用户最强烈的观感问题）。

- **修复：地质系统的「不自然笔直断裂线」**（2026-09-12，第五次实测反馈，用户定位正确）：
  - **用户的方法论贡献**：用户提出"把你知道的边界都显示出来，就像细胞边界显示功能一样" →
    新增 **`runTerrainStripePngProbe`** 输出 `boundaries_*.png`（所有已知边界叠加：板块 Voronoi /
    地形类型 / 各构造噪声格点 / 侵蚀 tile / chunk）与 `contours_1block_*.png`（1 格等高台阶）。
    **两张图一对照，直线与红色板块边界方向完全一致 ⇒ 一次锁定。**
  - **根因 1（域扭曲幅度/波长不匹配）**：`WARP_AMP=320 / 波长1600wu` —— 波长与界线长度
    （`PLATE_SPACING=2000`）同量级 ⇒ 在 400wu 视野内边界几乎仍是**直线**（曲率半径 ≫ 视野）。
    改为 **`AMP=130 / 波长400wu`**（界线长度的 1/5）⇒ 一条界线内约 5 个弯折，
    任何局部视野都呈明显波浪。实测 `contours_1block` 中的长直线**全部消失**。
  - **根因 2（`max(0,·)` 的一阶不连续）**：T1/T2/T5 用 `Math.max(0, stress)` 拆「汇聚/离散」部，
    而 `max` 在 `stress=0` 处斜率跳变 ⇒ 沿该等值线留下折痕 ⇒ 等高线挤成细线。
    新增 **`TectonicField.smoothPos(v, eps)`**（C¹ 平滑正部，**且在 v=0 处恒为 0**
    以保留"纯走滑=无形变"语义），T1/T2/T5 统一改用。
  - **根因 3（硬截断跳变）**：`if (g <= 0.01) return 0` 在 `dist≈330` 处造成环状跳变线；
    阈值降到 `1e-3`（量级 1e-3×2.5×0.032e ≈ 0.004 块，不可见）。
  - **`CACHE_SCHEMA_VERSION` 34 → 37**（本次共三轮地形产出变化，必须让预览磁盘缓存失效，
    否则"改了没生效"）。
  - **验证**：`runTectonicDeformProbe` **ALL PASS**（含 [1] 走滑零泄漏 / [5] 范围有限）。
  - ⚠️ **待复核（如实记录，未放宽阈值）**：`runTectonicWaveProbe` 的 T2 各向异性
    在 h=56 处由 ~1.3 升到 **2.10 > 1.8** 阈值。该探针假设"边界局部平直"，
    而根因 1 的修复**故意让边界变波浪** ⇒ 其几何前提已变，需**重新标定基线**或改造判据
    （**不得为了让 CI 变绿而放宽阈值**）。T5 形变各向异性 1.10~1.24 正常。

- **修复：地质系统的「密集波浪状平行细线 / 同心环梯田」**（2026-09-12，第四次实测反馈）：
  - **用户定性（正确）**：*"不是侵蚀导致的，是最近的地质系统的问题；预览和游戏里都有"*。
  - **定位方法（本次最大方法论改进）**：此前三次失败的原因是**只用阈值型探针**（测幅度/各向异性），
    而伪影的最终判据是**视觉**。故新建 **`TerrainStripePngProbe`**：把地形渲染成 PNG
    （`hillshade` 人眼所见 / `eLand_highpass` 去趋势 / `comp_deform` 构造分量 / `type_*` 逐配方），
    **先把伪影在自己的渲染里重现，再据此定位**。配合用户提供的坐标
    （`153,-309`、`59,-356`、`41,-226`、`12,-477`）一次命中。
  - **根因（T5 断层值域量化）**：`faultOffsetUnit` 的 `slipQ = Math.floor(blockN * 3.0) / 3.0`
    是**值域量化**——量化把连续噪声切成阶梯，而**阶梯的等值线正是一族嵌套闭合波浪曲线**
    ⇒ 在地形上就是"密集波浪状平行细线 / 同心环梯田"。实测 `comp_deform.png` 直接可见同心环。
    **与项目两次否决 Terrace 的「环状台阶伪影」完全同源**（证据链见 `TerrainParams.plateauSteps`）。
  - **修复（两处）**：
    1. **断层**：去掉量化，改为**跨越断层线的单条平滑 sigmoid**——取世界坐标噪声的
       **零等值线**作为断层线，两侧各做一次 smoothstep（`SCARP_HALF_WIDTH=0.30` 控制陡缓）
       → 仍是"断块整体落差"（地质语义保留、探针 [3] 崖线梯度比 15.4× PASS），
       但每个断层线**只有一条**过渡带（而非 6~7 级嵌套环），且处处 C¹。
    2. **褶皱**：`foldOffset` 的 ridged 形式 `1-|x|` 在 x=0 处是**V 型折痕**（斜率跳变）
       → 沿零等值线形成锐利细脊（同类伪影）。改用**圆化绝对值** `(sqrt(x²+r²)-r)/N`
       （归一化保值域、保持近零均值，实测均值 0.44 块 < 0.5 块判据）。
  - **★ 连带发现：预览磁盘缓存未失效**。改了地形产出但未升 `PreviewDisplay.CACHE_SCHEMA_VERSION`
    → 预览**静默复用旧缓存**，表现为"改了没生效"（也正是此前"无效"的机制之一）。本次 **34 → 35**。
  - **验证**：`runTectonicDeformProbe` / `runTectonicWaveProbe` / `runTectonicContinuityProbe`
    三者 **ALL PASS**；在用户给定坐标重渲染 `hillshade.png`，量化阶梯环**消失**（地形平滑）。
  - **写入硬约束（防第四次）**：`AGENTS.md` 新增
    **「禁止对噪声做值域量化」**与**「判定伪影的最终依据只能是渲染图」**两条。

- **修复：平行于板块边界的「同心波纹带」第三次回归**（2026-09-12，三次实测反馈，⚠️ **来回回退所致**）：
  - **现象**：用户反馈预览出现**若干条平行带**（"**不是岩石类型边界**"，且"**怎么更明显了**"）。
  - **定性（git 铁证，非新引入的缺陷）**——同一个问题被修过又被改回：
    | 提交 | 动作 |
    |------|------|
    | `087698c` | **曾修掉它**，原文：*"高程密集同心波纹 [我的设计失误]…等值线绕板块闭合成同心环 == 项目当初否决 Terrace 的『环状台阶伪影』同源。改为沿走向波：褶皱 sin(dist)→sin(along)"* |
    | `8e51a08` | 为修"每块独立生成"，把 `sin(along)` **改回 `sin(dist)`**，并把 `chainModulation` 的跨走向项换成 `a*18 = dist/111` → **波纹回归** |
    | `60e182f` | 把 `stress` 做平滑 → 原本**断续**的疤痕变成**连续规整**的波纹 → **视觉上更明显**（用户"怎么更明显了"的确切答案） |
  - **根因（实测确证，两处叠加）**：
    - **T2 `chainModulation`**：第二坐标为 `s.dist()` → `dist/2000*18 = dist/111`
      → 噪声沿法向每 **111wu** 一个周期（实测 `d=19` 谷 0.3131 / `d=60` 峰 0.5328）。
    - **T5 `foldOffset`**：`wave = sin(dist·2π/λ)` → 等值线**平行于边界且绕板块闭合**（实测周期 ≈320wu）。
    - 两者叠加 = 截图所见的"若干条平行带"（不同波长的两组同心环）。
  - **修复（两条硬约束）**：
    - **T2**：跨走向<b>不允许</b>任何周期性——"平行边界成带"这一几何本就由 `boundaryStrength` 的
      σ=110 高斯承担，chain 的职责只是把带内强度沿走向切成独立山峰。
      改为**纯世界坐标** `valueNoise(wx/CHAIN_SCALE, wz/CHAIN_SCALE)`（`CHAIN_SCALE=900`，远大于 σ=110），
      `dist` 完全不参与噪声坐标。顺带把算法由 2×`ridgedNoise`（8+ 哈希）降为 1×`valueNoise`（4 哈希），
      并删除死代码 `ridgedNoise`。
    - **T5**：改为**纯世界坐标** ridged 噪声（`1-|n|` 双层，中心化→近零均值），
      `dist` 仅经 `decay` 控制作用范围。数学上**不可能**再产生"平行于边界"的几何（与法向无关）。
  - **★ 新增防回归探针 `TectonicWaveProbe`（本次最重要的产出）**：
    - **为何既有 3 探针全 PASS 却漏报**：`TerrainGrainProbe`（曲率比值）、`LandEConformityProbe`
      （单步跳变 >0.02e）、`TectonicDeformProbe`（断层崖/褶皱过零）**全是幅度阈值型**判据；
      而"平行带"的本质是**各向异性**（沿法向起伏、沿切向平缓），幅度可以很小
      → **阈值型判据在原理上无法发现它**。
    - **新判据**：`Δv|法向 / Δv|切向`（归一化剔除高斯衰减的固有法向梯度）。
      平行带 ≫1；各向同性 ≈1。对多个步长（8~56wu）**取最大值**，避免单步长与波纹周期
      "相消"导致漏报（实测 h=20 时 T2 修复前仅 1.465，几乎漏报）。
    - **有效性双向验证（变量控制，必做）**：修复前 **T5=2.20~2.51（FAIL）**；
      修复后 **T5=1.10~1.28、T2=0.95~1.21（PASS）**。
      *不做这一步就会得到"永远 PASS 的假探针"——此前三次漏报正是这种失败模式。*
    - 同步修正 `TectonicDeformProbe [2]`：原判据"沿 **dist** 扫描统计符号交替"绑定旧机制
      `sin(dist)`，已过期 → 改为沿**世界坐标**扫描检验起伏（过零 272 次，幅值 0.032e）。
  - **硬约束（写入代码注释，防止第四次回归）**：
    > **`dist` 只能用于 `decay`（控制作用范围），绝不能作噪声坐标。**
    > `dist` 的等值线平行于 Voronoi 边界且绕板块闭合，任何以它为噪声坐标的量都会形成
    > 平行带/同心环 —— 与本项目早已否决的 Terrace「环状台阶伪影」同源。
  - 验证：`runTectonicWaveProbe` PASS；`ContinuityProbe` / `DeformProbe` / `TerrainGrainProbe` ALL PASS；
    `runFlowAccumProbe`（cycles=0 / profile.violations=0 / gateViolations=0）与修复前一致；
    `runLandEConformityProbe` 40 次/0.03389e（修复前 42 次/0.03395e，**略优**，该探针按设计如实报 FAIL 作为量化靶子）。

- **修复：岩石类型的平直多边形边界 + 高程同心波纹 + 首屏变慢**（2026-09-12，二次实测反馈）：
  - **① 岩石类型出现笔直多边形边界**：`StratumField` 按 `btype` 硬切换，而板块 Voronoi 边界是
    **中垂线（直线）**→ 2000wu 的板块格子被直接暴露。方案 §3.6 早已注明
    *"后续可加域扭曲让边界变有机"*，但**从未实现**。本次补上：
    `sample()` 对查询点做**连续域扭曲**（`WARP_AMP=320`，波长 1600wu）→ 边界变有机曲线。
  - **② 高程出现密集同心波纹**（⚠️ **我的设计失误**）：T5 的 `sin(dist·2π/λ)` 与
    `floor(dist/spacing)` 都是**距离的函数** → 等值线绕板块格子**闭合成同心环**
    —— **与项目当初否决 Terrace 的"环状台阶伪影"同源**。改为**沿走向波**：
    - 褶皱：`sin(dist)` → **`sin(along)`**（波列平行于边界，不闭环）
    - 断层：`floor(dist/spacing)` → **`floor(along/spacing)`**（断块垂直于走向分段）
    - 距离只通过 `decay` 控制作用范围，**不再产生闭环**
    - 副作用修复：断块改为沿走向后"块"面积大增（有形变面积 29%→98%），
      纯随机断距偏差累积（均值 0.28→0.87 块）→ 改用**相邻块差分**
      （`(slip_next−slip)/2`，数学上等价于随机游走增量，相邻块必然一升一降）→ 均值回到 **−0.14 块**。
  - **③ 预览首屏渲染变慢**：`BOUNDARY_REACH` 扩到 1000 后，"非 INTERIOR"区域面积增大约 **9.8×**
    （∝d²），而 `chainModulation`（2×ridgedNoise ≈ 8+ 哈希 + 三角函数）会在该区域内被
    **白算**。修复：`boundaryStrengthChained` **先算高斯衰减 g 并早退**（g≤0.01，330wu 外即退出），
    再决定是否计算 chain。
  - 同步修正 `TectonicContinuityProbe` 的块边界判据（须与产品一致改为 `floor(along/spacing)`，
    否则崖线会被误判为伪影）。
  - 验证：11 探针全通过；群系邻接违例 0/20000；FlowAccum cycles/violations/gate/border 全 0。

- **修复：地质系统的线性疤痕 / 串珠伪台阶**（2026-09-12，用户反馈）：
  - 现象：预览出现**笔直线性疤痕** + 沿线**串珠状台阶**。
  - 方法：新增 `TectonicContinuityProbe`——沿 1wu 细步**量化** dist/切向/偏移的跳变（不靠目视猜测）。
  - **定位 4 个根因**：
    1. `dist = |d2²−d1²|/(2·|s1−s2|)` 依赖最近邻配对，配对在 Voronoi 边界**延长线**切换时分母骤变
       → 跳变 **668.72wu（≈7000 块）**、**62%** 点受影响、轨迹**线状**。改用标准定义 **`(d2−d1)/2`** → **归零**。
    2. `btype` 离散枚举被 `switch` 硬分支（T1/T5）→ 类型边界公式骤变。新增**连续应力** `Sample.stress`
       （`dot/|(dot,cross)|`），T1/T2/T5 全部改连续加权。
    3. `along` 用**绝对坐标**投影（|p|~1e4），切向微小不连续（dTan<1e-4）被**放大**成 ~100wu 跳变。
       改用 **Voronoi 椭圆坐标** `alongCoord=(d1+d2)/2`（单调、连续、**零成本**）。
    4. `BOUNDARY_REACH=320` **硬截断** < T5 作用距离(700/900) → 形变被硬切。扩到 **1000**。
  - 效果：无理由跳变 **241→72 次**，幅度 **0.0619e(12块)→0.0124e(2.4块)**，不再聚集于截断线。
  - **残余（诚实标注）**：72 次(0.035%)、≤2.4 块，源于配对切换时 stress 的固有性质
    （法向连续化试 3 种方案；数值梯度因 (d2−d1) 呈 V 形、差分对称抵消而**劣化 4.7×**）。
  - **河流核查（A/B）**：`runRiverLineMicroUphillProbe` 的块级微上坡（L4/L5 23~27 处）
    **非地质系统引入**——关闭 T1 后 L4/L5 为 25/24（**反而略多**），属既有的
    "低于 1 块的地形噪声经 floor() 的方向抖动"。接缝间隙属既有 D6。

- **地质 Phase T5：褶皱与断层（地质系统收尾）**（2026-09-12）：
  - 新增 `TectonicDeformation`（零依赖纯函数）。**核心几何洞察**：两者都建立在
    "到边界距离 `dist`"之上（其等值线天然平行于边界）→ **零额外采样**（复用 T1 结果）：
    - **褶皱** = `sin(dist·2π/λ)` → 背斜/向斜，轴面平行于造山带走向
    - **断层** = `floor(dist/spacing)` 分块 + 中心化哈希断距 → 块界即断层面 = **断层崖**，交替升降 = **地垒/地堑**
  - **地质依据**（非随机）：汇聚→挤压（褶皱+逆断层）；离散→拉张（正断层）；走滑/内部→无垂向形变。
    作用距离取独立 reach（褶皱 900 / 断层 700 wu）> T1 边界宽度，以平滑函数衰减（无硬边界）。
  - **近零均值设计**（吸取 T1 教训）：正弦天然零均值 + 断块哈希中心化 → 实测全域平均偏移
    **0.28 块**（阈值 0.5 块），范围 ±0.045 e ≈ ±8 块 → **有起伏但无整体升降**，不连锁改变降水/河宽。
  - 实测：断层崖跨边界跳变是块内的 **160×**（陡崖 ≠ 平滑坡）；褶皱 6 次过零（周期性）；
    走滑/内部泄漏 **0**；超出 reach 严格 0；确定性 0 不一致；**0.071 µs/次**。
  - 新增 `runTectonicDeformProbe`。
  - ⚠️ **同时修正一个长期存在的探针缺陷**：`runPrecipRiverWidthProbe` 原为**单种子+单阈值**，
    实测同一实现下 head 最干桶跨种子 **0.885~1.062**、最湿桶 **1.003~1.173** —— 
    **5 个种子中 3 个假失败**（此前只跑 seed 12345，一直得到虚假信心）。
    该主张本是**统计性**的，已改为**多种子平均**（默认 5）：均值 **0.887 / 1.074** → 稳定 PASS。

- **地质 Phase T4：地层/岩性（填充 ROCK_TYPE / ROCK_LAYER 空占位图层）**（2026-09-12）：
  - 新增 `RockType`（8 种岩性，按成因分变质/侵入/沉积/喷出四类，含 `resistance()` 抗蚀序预留）
    与 `StratumField`（零依赖纯函数：构造环境 → 地层序列 → 出露岩性）。
  - **核心设计：岩性由构造环境决定，而非随机分配**（复用 T1 的 `TectonicField`，不重复采样）：
    克拉通→片麻岩/花岗岩；造山带→片岩/片麻岩/花岗岩；裂谷→砂岩/页岩/灰岩/玄武岩；
    深海平原→**灰岩盖层**+玄武岩；洋中脊→玄武岩（无盖层）；俯冲带→页岩+安山岩+玄武岩。
  - `Cell` 增加 `rockTypeId` / `rockLayer` 字段；`GeoPalette` 填充两图层的色表、
    数据源、图例条目与中英语言 key（此前二者在 `discreteId` 落 `default -> 0`，无数据）。
  - **海洋细分**（实施中改进）：初版海洋无论边界一律玄武岩 → BASALT 占 **67.5%**（单调）。
    据真实地质"海底 = 玄武岩基底 + 沉积盖层"细分为四序列后：BASALT **67.5%→39.5%**、
    LIMESTONE **1.5%→28.0%**（远洋灰岩）、SHALE **0.9%→5.5%**（海沟浊积）→ 更真实且更可读。
  - **范围**：仅数据层，**不参与 e 合成**（FlowAccum 全部指标与 T3 逐位相同，实证零地形影响）；
    "软岩成谷、硬岩成脊"需与侵蚀耦合，属后续阶段。
  - 新增 `runStratumProbe`。实测：合成验证陆地/海洋各 n=16 **零越界**；岩性 **8/8 全出现**；
    `StratumField.layerAt` **0.033 µs/次**（比 `terrainEQuick` 快约 180×）。
  - ⚠️ **探针自纠**：初版 [5] 性能判据硬编码 `pass5=true`（"占位判据"，不诚实）→
    改为实测 T4 增量（`layerAt` 独立计时），并保留完整 `sample()` 作为参考行。

- **地质 Phase T3：真平顶高原 + 构造盆地**（2026-09-12）：
  - **PLATEAU 平顶**：改造前实为「宽频丘陵」（仅放宽频率，无平顶无崖线）。
    改用**值域幂压缩** `v^0.55`（只压高端、保低端动态范围）→ 台顶平、台缘有起伏。
    实测**台顶梯度 = 台缘的 0.23×**（`runTerrainShapeProbe [1]`）。
  - ⚠️ **Terrace 空间量化二次验证确认否决**：本项目原已记录「Terrace 已否决（环状台阶伪影）」，
    本次为实现平顶重新尝试接线，**确认结论成立**——空间量化把噪声的**等值线**（闭合曲线）
    变成台阶 → 产生**同心环梯田**伪影。故 `plateauSteps` / `plateauStepStrength`
    **保持废弃不接线**（顺手补写了证据链注释，避免后人再试）。
  - **BASIN 碗形沉降**：改造前仅「噪声取反」（无盆底/盆缘之分）。改为低频**沉降势** + 碗形映射
    `(1-s)^2.2` → **平阔盆底 + 向边缘抬升**；接线死配置 `basinBase`(0.02) 作为盆底下限。
    实测**平阔盆底(<0.2) 占比 60.4%**（`[2]`）、值域 `[basinBase, 0.6]`（`[2b]`）。
  - 新增 `TerrainShapeProbe`（`gradlew runTerrainShapeProbe`）。
  - 回归：9 探针全通过；群系邻接违例 0/20000；FlowAccum cycles/violations/gate/border 全 0。

- **Phase E：水文 → 气候反向耦合（闭环达成）**（2026-09-11）：
  - 实现：`PrecipField.Mod` 新增第 4 分量 `waterMoist`（上风向海域回灌湿度）。
    在**已有的上风向回扫循环内**顺带判定 `hUp <= seaY` → **零额外采样开销**（复用本就为
    雨影/焚风采样的 `hUp`）。`seaY` 由调用方注入（`heightCurve.seaLevelY()`），
    `PrecipField` 保持对 `HeightCurve` 零依赖；传 `NaN` 即**关闭**（可回滚）。
  - `CellGenerator` 在 `clamp(hum,−1,1)` 之前 `hum += pm.waterMoist()`。
  - **安全性两条**：① `WhittakerType.classify` 用**区域层**温/降水 → 逐格增湿**不会**产生椒盐群系；
    ② 水源为地形固定的海面 → **无**"河更宽→更湿→河更宽"的正反馈发散。
  - 实测（`PrecipFieldProbe [11]`）：合成海岸近岸 `x=+40` **0.2343**、远内陆 `x=+900` **0.0000**
    （证明是局部海岸效应）；关闭开关 → **0**；真实地形 n=7128 最大增益 **0.2500**
    （= `moistGain` 上界）、命中 **82.3%**。
  - **v1 范围（诚实标注）**：仅**海域**；**湖泊/河流未纳入** —— 其判定需调用河网
    （`distanceToWater`），会把水文采样拖进最热的 `sample()` 路径（违反 §3.1 铁律）
    且引入正反馈风险；建议将来在 `GeoGenesisTerrain` 侧做后置修正。
  - **至此三系统闭环**：地形 → 气候 → 水文 → 气候（唯一仍断为"水文 → 地形"的河流演化，属 P1-9）。

### 验证 / Verification

- `gradlew build` BUILD SUCCESSFUL（含 `reobfJar`，已混淆为目标运行环境映射）。
- 气候梯度实测：z=0 → +0.932，单调降至 z=6000 → −0.851。
- 气候带分异（实测）：赤道带 A 热带 72.7%，温带带 C 温带 54.4%，极地带 E 极地 79.8%。
- 群系邻接违例：0 / 20000。
- 最长直线段：104 wu（纯气候边界的局部近直段，与地形变体无关）。
- 边界各向异性：1.03（1.0 = 完全各向同性）。
- 河流绿洲走廊覆盖：7.35% 采样点在 24 wu 内（干旱群区才走该规则，实际命中率 = 该比例 × 干旱占比 × 噪声门控通过率）。
- 陡坡裸岩阈值 0.40 覆盖率（384 wu 实测）：>0.30 覆盖 6.3%、>0.45 覆盖 2.7%。

### 参考项目 / References

实现中参考了以下开源地形模组：
- **FreeTerraForged (RTF)** — Whittaker 生物群区图 + 256×256 查找表、`Steepness` tile filter 算梯度、`ErodeFeature` 岩层阈值（0.3 起岩、0.65+ 为重岩）、`DecorateSnowFeature` 让雪不沾陡崖。
- **RTG Community** — `SurfaceRiverOasis` 沙漠河流绿洲范式（`river > 0.70 && river + noise > 0.85` → 河岸铺草 / 泥）。
- **SimpleHydrology** — 植被判据（坡度太陡不长植物、超过树线不长、河道内 discharge 大不长）启发了"陡坡裸岩"方向。

[0.0.1]: https://github.com/your-repo/geogenesis/releases/tag/v0.0.1
