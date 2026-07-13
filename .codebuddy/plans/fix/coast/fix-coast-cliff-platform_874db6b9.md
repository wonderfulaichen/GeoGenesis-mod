---
name: fix-coast-cliff-platform
overview: 修复 runClient 游戏内海岸垂直悬崖与宽浅海洋平台：根因是 fillFromNoise 走 RiverField 时陆地高度用 landHeight 原始 eLand（无海岸淡出），而海洋用 baseShape=blendE（有淡出），两路径不一致导致悬崖；加之大路频率过低使海岸过渡带过宽形成平台。
---

用户目标：根除 runClient 新建世界后出现的「海岸垂直悬崖 + 海洋宽浅平台」。
验收标准：

1. 海岸处陆地从海平面平滑升起，不再出现海洋（e≈0）到陆地（满高 eLand）的垂直断面。
2. 大陆架/海岸过渡带物理宽度明显收窄，不再呈现数百格宽的浅水平台。
3. 预览（runPreview）与游戏（runClient）两套路径高度一致， cliffs 在两者中均消失。

根因（已通过读源码定位确认）：

- 游戏地形产线 `GeoGenesisGenerator.fillFromNoise` 走 `RiverField`：
- 海洋 `baseE = land.baseShape() = blendE(c,eLand)`（第75行，含海岸淡出）。
- 陆地 `carvedE = land.landHeight()`，而 `CellGenerator.landHeight()`（第216-226行）直接返回**原始 eLand，未做海岸淡出**。
- 两路径不一致 → c=0 处海洋 e≈0、陆地 e=满高 → 垂直悬崖。
- 之前 blendE/HeightCurve 的改动只改到了海洋侧 baseShape 与预览路径（populate→blendE），未触及游戏陆地路径 landHeight，故 runClient 无效。
- 平台根因：`continentFrequency=0.0006`（特征波长≈1667格），海岸过渡带 c∈[-0.3,0.3] 物理上横跨约 500 格 → 宽浅大陆架观感。

修复方案（两步）：

1. 让 `landHeight()` 返回海岸淡出的 `blendE(c, eLand)`，与海洋 baseShape 共用同一函数 → 陆/海高度场在 c=0 处连续，悬崖消失。内陆（c>COAST_WIDTH）landW=1，blendE=eLand（满高不变），不影响内陆地形与河流刻蚀。
2. 提高 `continentFrequency`（0.0006→0.0011）收窄海岸过渡带物理宽度，消除宽浅平台。需同步 TerrainParams 默认值、GeoGenesisConfig 默认值与 run/config/geogenesis-common.toml。

设计约束遵循用户规则：单函数≤80行；改动集中、可追溯；Forge Config 默认值与已存在 toml 同步；无硬编码。