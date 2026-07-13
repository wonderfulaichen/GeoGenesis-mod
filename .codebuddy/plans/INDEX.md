# Plans 索引（`.codebuddy/plans/`）

> 本目录为 GeoGenesis 的**规划/方案草稿归档**。每篇含 YAML front-matter（`name` / `overview` / `todos`+`status`）。
> 2026-07-11 整理：按主题分子目录，删除唯一确认的逐字重复文件（`f4d1491d` 的「未完成」副本，与完成版一致）。
>
> 状态图例：**✅ 已完成** / **⏳ 未完成（草稿）** / **🗑 已删（重复）**
> 继承关系：`vN`=同主题迭代版本；`取代 X`=该方案被另一方案覆盖；`已落地`=对应代码已实现。
>
> **与本仓库其他计划的关系**：`docs/plans/PLAN.md` 是人工**战略主计划书**（P0–P9 阶段路线，面向整体架构）；本目录是 AI 生成的**战术级功能方案草稿**（逐功能执行计划，含 front-matter todos）。二者层级不同、互补不重叠，**不合并**。已落地方案的实现状态以 `AGENTS.md`「当前工作焦点」与 `DEV_REPORT.md` 为准。

## 目录结构

| 目录 | 主题 | 文件数 | 状态 |
|------|------|--------|------|
| `preview/` | 预览增强（图层系统 + 配色中枢） | 4 | ✅ 全完成 |
| `biome/` | 群系分类重构（BiomeClassifier 按地形×气候） | 3 | ⏳ 未完成 |
| `terrain/` | 地形重建（地质过程范式） | 6 | ⏳ 未完成 |
| `river/droplet/` | 河流 droplet 物理化（已落地方案） | 2 | 1✅ / 1⏳ |
| `river/explore/` | 河流探索/竞争方案（未落地） | 5 | ⏳ 未完成 |
| `fix/` | Bug 修复 + 侵蚀修复（含 `coast/` 子目录） | 22 | ⏳ 未完成 |
| `meta/` | 元计划（文档整理 / 气候群系接线） | 2 | ✅ 完成 |

## 文件明细

### `preview/`（预览增强，✅ 全完成）
| 文件 | 状态 | 继承 | 备注 |
|------|------|------|------|
| `preview-enhancement_feac34b5.md` | ✅ 完成 | v1 | 8 图层 + BiomeClassifier；后续被 geo-layers 取代 |
| `preview-enhancement-geo-layers_61340582.md` | ✅ 完成 | v2 | 11 图层 + ColorMap/GeoPalette |
| `preview-enhancement-geo-layers_a5b27bc5.md` | ✅ 完成 | v3 | 数据驱动配色（JSON / 用户覆盖） |
| `preview-enhancement-geo-layers_b5f820bd.md` | ✅ 完成 | v4（最终） | 最完整 11 图层方案，即落地版 |

### `biome/`（群系分类重构，⏳ 未完成）
| 文件 | 状态 | 继承 | 备注 |
|------|------|------|------|
| `biome-terrain-1to1_be81d9cf(未完成).md` | ⏳ 草稿 | v1 | 每地形独立气候表 + 去 shape 误判（基础版） |
| `biome-terrain-1to1_fcfc29e8(未完成).md` | ⏳ 草稿 | v2 | ReTerraForged 5×5 温湿分级 |
| `biome-terrain-climate-v2_d001041f(未完成).md` | ⏳ 草稿 | v3（最新） | 加海拔递减率 + 雨影；**取代**前两个 1to1 |

### `terrain/`（地形重建，⏳ 未完成）
| 文件 | 状态 | 继承 | 备注 |
|------|------|------|------|
| `terrain-shape-redesign_78b32ae8(未完成).md` | ⏳ 草稿 | 旧 | edgeFade + TerrainType 形态带；**被 real-terrain-rebuild 取代** |
| `real-terrain-rebuild_a53e50cb(未完成).md` | ⏳ 草稿 | 新（已落地） | 地质过程范式重建，即当前 `StructuralField`+`TerrainBlender` 方案 |
| `GeoGenesis_地形整体重构方案_e008ca51(未完成).md` | ⏳ 草稿 | — | 地形整体重构方案（TerraForged 0.3.x 对齐） |
| `geogenesis_terrain_bugfix_v2_d019ca3a.md` | ⏳ 草稿 | — | 地形bug修复方案v2 |
| `fix-terrain-bugs-2026-07-11_5ded1b08(未完成).md` | ⏳ 草稿 | — | 修复2026-07-11地形bug方案 |
| `fix-terrain-bugs-2026-07-11_8fa4806b(未完成).md` | ⏳ 草稿 | — | 修复2026-07-11地形bug方案（另一版本） |

### `river/droplet/`（河流 droplet 物理化）
| 文件 | 状态 | 继承 | 备注 |
|------|------|------|------|
| `河流物理化-droplet水力侵蚀刻河谷_填湖_6a8c0cc3(未完成).md` | ⏳ 草稿 | 早期 | 基础 droplet + 填湖；**被雨影版取代** |
| `河流物理化-droplet水力侵蚀刻河谷_雨影驱动_填湖_f4d1491d.md` | ✅ 完成 | 最终 | droplet + 雨影 + 填湖，即当前 `RiverField`/`HydraulicErosion` 落地方案 |
| ~~`河流物理化-...雨影驱动_填湖_f4d1491d(未完成).md`~~ | 🗑 已删 | — | 与完成版**逐字重复**，2026-07-11 删除 |

### `river/explore/`（河流探索 / 竞争方案，⏳ 未完成，均未落地）
| 文件 | 状态 | 继承 | 备注 |
|------|------|------|------|
| `地形-河流一体_方向A__816ef742(未完成).md` | ⏳ 草稿 | 路线 B（D8） | 粗格点下坡汇流；**被 droplet 取代** |
| `real-river-hydrology_ac51b65f(未完成).md` | ⏳ 草稿 | 竞争 | SimpleHydrology 忠实移植替代 D8 |
| `河流生成-SCA空间殖民成树_87a808ea(未完成).md` | ⏳ 草稿 | 竞争 | SCA 成树（预览优先） |
| `河流海岸冲刷与单点水修复_531ecea3(未完成).md` | ⏳ 草稿 | 局部修复 | 海岸截断 + 单点水过滤 |
| `迭代式水力侵蚀重写_河网自组织__1d173b9c(未完成).md` | ⏳ 草稿 | 竞争 | 迭代式侵蚀重写 + 河网自组织 |

### `fix/`（Bug / 侵蚀修复，⏳ 未完成）
| 文件 | 状态 | 继承 | 备注 |
|------|------|------|------|
| `fix-params-page-layout_480f7629(未完成).md` | ⏳ 草稿 | — | 参数页布局（WorldHeightBar / 空白 / scissor） |
| `fix-region-transpose-seam_db9e5329(未完成).md` | ⏳ 草稿 | — | X/Z 转置导致 512-block 棋盘断裂 |
| `hydraulic-erosion-fix-v2_054ae2ae(未完成).md` | ⏳ 草稿 | v1 | 5 根因 bug 修复 + 接通侵蚀 |
| **`fix/coast/`** | ⏳ 子目录 | — | 海岸/海洋相关修复（19个文件），详见子目录 |

### `meta/`（元计划，✅ 完成）
| 文件 | 状态 | 继承 | 备注 |
|------|------|------|------|
| `root-cleanup-doc-index_bfe595bd.md` | ✅ 完成 | — | 根目录 / 文档整理（本次整理的上游计划） |
| `气候_群系接线___删除侵蚀包_bbbe4d8c.md` | ✅ 完成 | — | 气候→群系接线 + 删除侵蚀包，已落地 |
