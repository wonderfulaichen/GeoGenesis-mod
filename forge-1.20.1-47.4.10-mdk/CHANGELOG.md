# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

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
