# INDEX — 仓库导航（2026-09-30 盘点）

> **本文件是文档入口的唯一导航**。历史教训：交接信息按轮次追加导致入口分裂成 4 处
> （AGENTS 焦点段 / HANDOFF / PLAN-hydrology / _handover），且 AGENTS 与 _handover
> 曾互相矛盾地自称"先读我"。**阅读顺序以本文件为准。**
> ⚠️ 文中文件计数为盘点快照（2026-09-30），会漂移；**结构以目录与代码为准**，勿把数字当依据。

## 0. 新对话阅读顺序

1. **[AGENTS.md](AGENTS.md)** — 总规：架构速览表、五条地质硬约束、折叠算子禁令、探针铁律、各轮工作焦点。
2. **[`_handover/`](_handover/)** 里**日期最新**的一份交接 —— 当前进度与待办
   （截至盘点：`2026-09-29_水文物理参照与骨架修复_交接.md`，含唯一管线裁定、sim 新核心、5 次实机复验状态）。
   **规则：`_handover/` 按日期命名，永远读日期最新的；旧的留档不删。**
3. 按需下钻：
   - [PLAN-hydrology.md](PLAN-hydrology.md) — 水文重构方案细节（§9 支流分叉、§9.9 形态缺陷根因链）
   - [HANDOFF.md](HANDOFF.md) — 09-18/09-20 交接（湖岸贴合/确定性/矿物系统/水文 M2 等历史导航）
   - [LAKE-HANDOFF-2026-09-23.md](LAKE-HANDOFF-2026-09-23.md) — 湖泊专项（09-23）
   - [ARCHITECTURE.md](ARCHITECTURE.md) — 架构说明 + 配置表
   - [PLAN-hydrology-flow.md](PLAN-hydrology-flow.md) — 水文流程子方案
   - [README.md](README.md) — 自述

## 1. 顶层文件

| 文件 | 作用 |
|---|---|
| `AGENTS.md` | 总规（148 KB，最大；含"当前工作焦点"编年体记录）|
| `INDEX.md` | 本文件：导航 + 目录盘点 |
| `HANDOFF.md` / `_handover/*.md` | 交接（根级=09-18/20；_handover=09-29 起的最新格式）|
| `PLAN-hydrology*.md` | 水文方案 |
| `ARCHITECTURE.md` / `README.md` | 架构 / 自述 |
| `.gitignore` | 覆盖根 + MDK；`docs/`、`参考/`、`_shots/` 等均被忽略 |

## 2. `docs/`（⚠ 被 `.gitignore` 忽略，不入库，仅本地）

60 个文件。**关键资产**：`docs/analysis/守门门禁清单-2026-09-16.md`（全部探针的"改什么⇒跑什么"矩阵 + 基线）·
`docs/analysis/原版复用对照审计-2026-09-16.md` · `docs/plans/`（3 份预研）。
目录：`01-架构设计` / `02-功能设计` / `03-实施计划` / `04-修复记录` / `05-分析诊断` / `06-历史归档`。

## 3. 源码（`forge-1.20.1-47.4.10-mdk/`，git 跟踪 ~322 文件）

**325 个 Java = main 134 + diagnostics 191（其中 153 个 \*Probe.java）**

### `src/main/java/com/geogenesis/`（134）
| 包 | 数 | 要点 |
|---|---|---|
| 根 | 2 | `GeoGenesisMod`（入口/注册）· `GeoGenesisServerEvents` |
| `client` | 10 | 配置屏、事件、叠加层、种子/预设 |
| `client/preview` (+`chunk` 7 +`mixer` 9) | 40 | 预览控件/面板/异步计算/调音台 |
| `config` | 2 | `GeoGenesisConfig` · `ConfigSafe` |
| `diagnostics` | 1 | `WorldGenProfiler`（实机 [WGP] 诊断）|
| `worldgen/generator` | 3 | **主生成器** · BiomeSource · `VanillaDecorationFilter` |
| `worldgen/terrain` | 31 | 地形引擎（Cell/侵蚀/构造/地层/样条）|
| `worldgen/climate` | 11 | 气候/降水/风/群系分类 |
| `worldgen/cave` · `erosion` · `ore` | 4·2·1 | 洞穴 · 侵蚀 · `OreVeins` |
| `worldgen/noise` | 29 | 零 MC 依赖噪声原语 |
| `worldgen/hydrology`（根） | 8 | 雕刻器/采样/引擎/ChunkEngine |
| `hydrology/flow` · `flowaccum` · `riverline` | 1·1·5 | `TerrainFlowSim` · `FlowField` · 旧链（生产不可达）|
| **`hydrology/sim`** | **19** | **新水文核心（契约/tile/无限世界 solver）— 2026-09-29 起的生产核心** |

### `src/diagnostics/java/`（191）
| 模块 | 数 |
|---|---|
| `hydrology`（+`sim` 3） | **88**（最大头）|
| `terrain` | 48 |
| `climate` 5 · `cave` 4 · `ore` 4 · `erosion` 2 | 15 |
| `diagnostics` 2 · `client/preview` 1 | 3 |

### 资源与构建
- `src/main/resources`：`mods.toml` · `pack.mcmeta` · lang×2 · `biome_colors.json` · `colormap_preview/` · world_preset×2
- `build.gradle`（84 KB）：**156 个 `run*` 任务**；门禁入口 **`gradlew runWorldgenGate`**
- 空源集：`src/test` · `src/generated` · `src/diagnostics/resources`

## 4. 忽略目录/杂项（本地存在、不入库）

`docs/`(60) · `参考/`(4857，参考项目源码) · `_shots/`(54 图) · `logs/` · `river_check/` · `sca_smoke/` ·
`.codebuddy/` · `.prism/` · `.vscode/` · `.openbitfun/` · `.gradle/` · `_tmp_*.txt`（本文件的临时提交信息即在此列）

## 5. 已知遗留（盘点发现，待决策）

1. **空目录 3 处**（git 不跟踪，仅本地）：`worldgen/geode`（main+diagnostics，`GeodeShape` 删除遗留）·
   `worldgen/terrain/river`（2026-08-28 删 RTF 遗留）—— 删不删无实际影响。
2. **`docs/` 不入库**：关键资产 `守门门禁清单` 只存在于本地。若担心丢失，可将其拷到仓库根（规则：根级才入库）。
3. **AGENTS.md 148 KB 编年体**：只增不减，长期会难以导航 —— 是否做分卷/归档待决策。
4. 旧交接（`HANDOFF.md` / `LAKE-HANDOFF`）保留为历史，**不再更新**；新交接一律进 `_handover/`。
