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
- 温度在 `|z| > latitudeScale`（默认 6000 wu）饱和于 −1，形成永久极冠。若希望无限世界保持气候多样，可加纬度回卷或调大默认值。

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
