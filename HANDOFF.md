# 交接说明（2026-09-18）—— 湖岸贴合 / 确定性 / 性能

> 面向**下一个对话**。开始前请先读 `AGENTS.md` 的「当前工作焦点（2026-09-17/18）」章节（本轮成果已完整留痕在那里）；本文是它的**导航与待办清单**。

## 0. 仓库状态（接手时）

```
分支 main，已推送到 origin/main（HEAD = b04beb2 之后可能有文档提交）
工作区：干净（无未提交改动）
未推送：无
```

本轮共 **10 个提交**（`07f0a10` → `b04beb2`），连同上一批会话共 57 个已推送。

## 1. 本轮解决了什么（用户报的两件事）

| 用户反馈 | 结论 |
|---|---|
| 「湖岸不贴合（有水的地方却干 / 直边）」 | **已基本解决**：真·干墙 **451 → 12**（占水体 0.04%）；12wu 网格伪影确认消失（mod 12 分布回到均匀） |
| 「区块加载变慢了不少」 | **量化归因**：主因是**既有热点**（逃逸水位求解占 hydro 54%），不是本轮改动引入；顺带把单块水文峰值 **5210 → 2486ms** |

**⚠️ 用户已明确："性能目前不是我们的目的"** ⇒ 不要再继续做性能优化（除非用户重新提出）。

## 2. 根因（最有价值的两条）

1. **直边 = 网格限，不是精度不足**：干墙格 `wu.x ≡ 0 (mod 12)` 占比 21.5%（均匀应 8.3%，**2.6×**）⇒ 直边就是 `computeFlood` 的 12wu 粗格网。而**注释早就写了"加密到 6wu"，代码却还是 12wu**（第一处"注释说改了、代码没改"）。
2. **生成顺序依赖**：`blendTileDelta` 只读窥探邻居 tile、缺失就退化 `d00` ⇒ height 依赖"邻居在不在缓存"。已修（缺失即同步生成），门禁 FAIL → **ALL PASS**。

同类问题还有一处**未修**：`computeEscape` 的 `coarseStep` 参数**至今未被使用**（文档称"由粗到细、快 13×"，代码只有细阶段）。这是已知的下一个性能候选。

## 3. 残余 / 已知边界（不要重复去修）

- **12 个真·干墙 = 结构性上限**：
  - `A无命中=4`：在流域之外，物理上不该有水；
  - `C被拒=8`：紧邻的水**本身就是块级精修造出来的** ⇒ `node.inFlood` 必为 false ⇒ 与分组、与抽样步长都无关（**两项实测阴性**：同水位分组、pad 步长 4→2 —— 均已回退并记录）。要接它们必须跨 chunk 记忆精修状态，**会破坏确定性** ⇒ 已判定：接受。
- **409 个『浅水墙』不是伪影**：深度 < 0.5 块 = 地面与水面同一格、放不下任何一个水块 ⇒ 物理上本就该干。
- **`违反 +7.088`**（水位 vs 应有水位）是 **±96wu 窗口假象**，不是缺陷（窗口放大到 96~288 块时"应有水位"收敛到 165.498，与 166.627 差约 1.1 块）。

## 4. 用户的规则（务必遵守）

- **「填充范围内无法闭合的湖泊 ⇒ 就不生成这片湖」**：= `computeFlood == true`（越界/淹不满）走**弃湖**分支，该分支**刻意不回传湖节点** ⇒ 块级精修不会去救它。改这条前先问。
- **不新建任何方块/物品**（只用原版内容或删自研代码）。
- 动水系前先跑第 5 节的四把尺子。

## 5. 「四把尺子」—— ⚠ 2026-09-18 **重新分类**（原说法不准确）

> **关键更正**：这 4 个里**只有 1 个是真尺子**。判据是硬的 —— Gradle `JavaExec`
> **只在退出码非零时才算失败**；核查发现 128 个探针中仅 18 个调用 `System.exit`，
> 而这"四把尺子"**一把都没有** ⇒ 即使 FAIL 也报 `BUILD SUCCESSFUL` ⇒ 门禁名存实亡。

| 探针 | 真实类别 | 说明 |
|---|---|---|
| `runHydrologyDeterminismProbe` | ✅ **真尺子** | `TOL = 0.0`（逐位相等）。2026-09-18 补上退出码；实测 **ALL PASS**（`max\|A−B\| = 0`、`max\|Δheight\| = 0`） |
| `runWaterPhysicsProbe` | ⚠ **判据过时** | 判据 `viol<=0.5 && walls==0`；实测 `viol = +7.088`（±96wu **窗口假象**）、`walls = 13`（已接受的**结构性上限**）⇒ **永远不可能 PASS**，需先修判据才能当门禁 |
| `runWallAttributionProbe` | 🔧 **仪表** | 逐格归因（A/B/C/D + 浅水墙），**无自动判据**，结论靠人读 |
| `runChunkLoadPerfProbe` | 🔧 **仪表** | 性能基准（v2 比 min），**无自动判据** |

**常用命令不变**（仪表仍有用，只是别指望它们拦你）：

```bash
gradlew runWaterPhysicsProbe      -PprobeArgs="5436529513624899584 12 316 96"        # 水位 vs 应有水位 + 干墙数
gradlew runWallAttributionProbe   -PprobeArgs="5436529513624899584 12 316 96 0.5"    # 逐格归因（默认排除浅水墙）
gradlew runHydrologyDeterminismProbe -PprobeArgs="5436529513624899584"               # 纯函数铁律门禁（diff == 0）
gradlew runChunkLoadPerfProbe     -PprobeArgs="5436529513624899584 32 4"             # 性能（v2，**A/B 比 min**）
```

> ⚠️ 性能教训：v1 基准**同一份代码连跑两次差 18%** ⇒ 只能判定大信号。v2 用"多区域冷启动 + **min** 作判据"，离散降到 ±8%。

## 6. 本轮新增/改动的关键位置

- `GeoGenesisTerrain.lakeFineFlood`（开关 `LAKE_FINE_FLOOD=true`）：湖岸 1 块精度洪泛 + pad 侧种子。
- `CellGenerator.neighborTile / blendNeighbor / allowGen`：确定性修复；`ERODE_TILE_CACHE_SIZE = 512`。
- `RiverLineRegion`：粗格 `claimGrid*0.25`；弃湖阈值改物理面积；`runFloodCore` / `computeEscape` 高度缓存 + 逃逸**上界剪枝**；`FloodIndex`（`inFlood` O(1)）；`FLOOD_COARSE_REUSE=false`（实测净亏，存档勿盲开）。
- `HydrologyBlockCarvedColumn.lakeLevelY`：被拒列也回传水位（本轮修法的枢纽）。

## 7. 下一步候选（按我建议的优先级）

1. **实机验收后续**：用户已确认"实测正常"。若还要看效果，注意**所有产出都烘在区块数据里 ⇒ 旧区块不会变**，必须新生成区块/新世界。
2. **（性能，用户已暂停）**：若重启，最大杠杆是 `computeEscape` 的**真正由粗到细**（`coarseStep` 未使用），以及**地形本体**（冷启动占比约 89%，水文只占 ~5-6%）。
3. **文档**：`ARCHITECTURE.md` 未见与湖判水相关的过时描述（本轮已核查：无 `inFlood`/粗格/水位求解/弃湖 条目）⇒ 无需改动；如需补"关键陷阱"，可加 blend 确定性与"注释≠代码"两条。

## 8. 2026-09-18 追加：文档 ↔ 代码一致性核查（**纯注释/文档改动，产出逐位不变**）

**起因**：上一轮在同一模块发现 3 处「注释说改了、代码没改」⇒ 判定留痕已从"事实记录"退化为"意图记录"，而下个会话会基于错误前提决策。本轮做小范围核查：`RiverLineRegion` / `HydrologyBlockCarver` / `GeoGenesisTerrain`(湖) / `CellGenerator` / `PreviewDisplay` / `build.gradle` / 4 个探针。共改 **8 个文件**（未提交）。

| # | 位置 | 处理 |
|---|---|---|
| A1 | `RiverLineRegion` `escapeWaterLevel` / `computeEscape` javadoc | 「由粗到细、快 13×」→ 标明 **未实现**（`coarseStep` 体内零引用），并注明与 `FLOOD_COARSE_REUSE` 是**同名不同物**（后者已实现、净亏、默认关） |
| A2 | `LakeSurveyProbe` / `LakeGatingControlledProbe` | 加 ⚠ **口径差异**：探针传 `claimGrid*0.5`，生产 `HydrologyBlockCarver` 是 `*0.25` ⇒ 探针结论只能历史自比（原"与生产同口径"说法不成立）。**刻保持 0.5**（改则历史断档 + 慢 4 倍） |
| A3 | `CellGenerator` `blendTileDelta` | 门禁证据 ✅ → **❌**（判据 `TOL=0.0`，非零即 FAIL；那两个值是修复前 FAIL 实证），并补修复后实测 |
| B1 | `AGENTS.md` | 「`CACHE_SCHEMA_VERSION` 当前 69」→ 改**指路代码常量**（不再抄数字，抄数字本身即漂移源） |
| B2 | `PreviewDisplay` 版本清单 | 补 **71** 条目；**70 原注释"已撤销、不再回收"与 git 不符**（70 实际用过两次）⇒ 如实改写，确立"今后一律 max+1" |
| B3 | `build.gradle` `runChunkLoadPerfProbe` | 补第 3 参 `regions`（v2 冷启动区域数，比 min） |
| B4 | `build.gradle` + `WallAttributionProbe` javadoc | 补第 5 参 `minDepth`（默认 0.5，排除浅水墙） |
| — | `RiverLineRegion` `@param gridCell` | 「调用方传 claimGrid/2」→ 生产实为 `*0.25`，并注明两探针仍 `*0.5` |

**两个一手事实（本轮实测/考古得出，勿再当"不可考"）**：
- 确定性门禁**实测 ALL PASS**（seed `5436529513624899584`）：`[1] max|A−B| = 0`、`[2] max|Δheight| = 0`（`ΔriverSurfaceY/Δgradient` 均 0、type 差异 0）⇒ `CellGenerator` 里的 `1.15e-5` / `0.0515` 确为**修复前**值。
- `CACHE_SCHEMA_VERSION` 考古：**71 = blendTileDelta 确定性修复**（`b465f9d`，原 `-` 行注释明写）；**72 = 湖岸 BFS 12wu→6wu**（`8313d35`）；70 先后被"域扭曲 WARP_AMP"（撤销）与"湖岸 1 块精度精修"（`07f0a10`，后被 71 取代）用过。

**刻意未改（留给未来决策）**：
- 两个湖探针的 `*0.5` —— 见 A2，改之前先确认能否接受历史数据断档。
- `computeEscape` 的粗阶段 —— 只标注未实现，**未补代码**；属性能议题，而用户已明确"性能目前不是目的"。

**核查通过、无需改动（放心清单）**：`ERODE_TILE_CACHE_SIZE=512` · `neighborTile`/`blendNeighbor`/`allowGen` 闸门 · 弃湖阈值物理面积 `5.76e6 wu²` · `LAKE_FINE_FLOOD=true` · `lakeLevelY` 被拒列回传 · `FLOOD_COARSE_REUSE=false` · 四个探针 task 均存在 · `province*` 确为零消费。

**⚠️ 本轮最大的方法论收获**：**文档里抄数值 = 必然漂移**（69 vs 72）。凡是会变的数字，一律写"以代码常量 X 为准"+ 指路，不要在文档里复述。

## 9. 2026-09-18 追加：项目整理（门禁机制化 + 全仓文档体检）

### 9.1 门禁：让「尺子」名副其实

- **事实**：128 个探针中只有 **18 个**调用 `System.exit`；而 §5 原称的「四把尺子」**一把都没有**。
  Gradle `JavaExec` **只在退出码非零时才算失败** ⇒ 即使打印 FAILURES 也报 `BUILD SUCCESSFUL` ⇒ 门禁名存实亡。
- **已做**：`HydrologyDeterminismProbe` 补 `System.exit`；新建 **`gradlew runWorldgenGate`**，
  纳入 6 个实测 ALL PASS 的探针：`runHydrologyDeterminismProbe` / `runTectonicWaveProbe` /
  `runTerrainGrainProbe` / `runStratumProbe` / `runCaveShapeProbe` / `runTectonicContinuityProbe`（约 27s）。
- **反向验证**：临时挂入已知 FAIL 的 `runLandEConformityProbe` ⇒ `BUILD FAILED` / exit=1（验完即撤）
  ⇒ 门禁**真的会拦**，不只是"能过"。
- **排除（原因已写进 `build.gradle` 注释，勿无声加回）**：
  - `runLandEConformityProbe`：实测 FAIL（T5 形变量差值 < 0.02e）—— **当前唯一红着的门禁，待归因**。
  - `runWaterPhysicsProbe`：**判据过时**（`viol<=0.5 && walls==0`；实测 `viol=+7.088`（±96wu 窗口假象）、
    `walls=13`（已接受的结构性上限）⇒ **永远不可能 PASS**，需先修判据才能当门禁）。
- **归类更正**：「四把尺子」里 `WallAttributionProbe` / `ChunkLoadPerfProbe` 实为**仪表**
  （无自动判据，靠人眼），已在 §5 重新分类。

### 9.2 文档体检（全仓，不只水文）

方法：提取文档中所有**可验证声明**（文件路径 / 类名 / 方法名 / gradle task）逐条核验。

- **第四例「文档说改了、代码没有」（最严重一例）**：`AGENTS.md` 与 `README` 均称
  「域扭曲已启用 `WARP_AMP = 40`、修掉轴向对齐缺陷（944→272 块）」，实际
  **`TerrainCharacterField.WARP_AMP = 0.0`** —— 40 曾启用过，但**实机反馈河流/湖泊出问题已回退**
  （与 `PreviewDisplay` 缓存版本 70 作废记录完全吻合）；`setWarpAmp()/warpAmp()` 也不存在。已改。
- **`client` 包文档整段过时**：架构速览的 preview/mixer 段 **7 项错 6 项**
  （`PreviewColor` / `BasicParamsPanel` / `Factor` / `FactorCurveChart` / `FactorMixer` /
  `ConfigBinding` / `FactorCategoryBar` 均不存在；`WorldHeightBar` / `SnowLineChart` / `ScalePreview`
  实际在 `mixer/` 下）。已按实际 49 个文件重写。
- **`ARCHITECTURE.md`**：`LandShape.java` 原标 `[ACTIVE]` 但不存在（实际 `TypeLandShape`）；
  `fillRiverColumn` 不存在（实际 `fillTerrainColumn`）；`PreviewColor` 不存在；`TerrainConfigPanel` 路径错。已改。
- **`README` 目录结构段**：⚠️ **本轮在这里犯了一个错误并已回滚**。我用**受 `.gitignore` 过滤的
  搜索工具**判断"目录是否存在"，据此把 `docs/`、`参考/`、`logs/`、`river_check/`、`sca_smoke/`
  改写成"不存在" —— **全错**。权威核实（`git status --ignored`）：这些目录**真实存在**，
  只是被忽略；真正不存在的只有 `backups/` / `net/minecraftforge/` / `erosion-test-tool*/`。
  ⇒ **教训（已写进 `README` 注意事项）：判断文件/目录是否存在，绝不能用受 ignore 规则过滤的工具；
  必须用 `git status --ignored` 或显式路径列目录。**
- **★ 由此捞回一份关键资产**：`docs/` 内有
  `docs/analysis/守门门禁清单-2026-09-16.md` —— **115 个探针的「改什么 ⇒ 必跑什么」矩阵 +
  实测基线 + 使用纪律**（含"渲染图才是伪影最终依据""口径错误的判据比没有判据更糟"等）。
  它与本轮的 `runWorldgenGate` **互补**：清单管"该跑哪些"，门禁管"跑了会不会拦"。
  ⚠ **本轮的 `runWorldgenGate` 可能纳入不全** —— 清单 §B 里 ✅ 实测 ALL PASS 的探针有 12+ 个
  （`runFlowAccumProbe` / `runOreVeinProbe` / `runCaveBiomeProbe` / `runHandoffPickupProbe` /
  `runTectonicProbe` / `runTectonicDeformProbe` / `runShapeProbe` / `runTypeAxisProbe` …），
  本轮只纳入了 6 个。**待办：按清单 §B 补齐门禁**（注意这些探针多数**尚未挂退出码**，
  需先补 `System.exit` 才是真门禁）。
- **僵尸探针假说被证伪**：0 处引用已删除模块（`worldgen.river` / `GeodeShape` / `BiomeMapper`）
  ⇒ **不需要清理任何探针**。

### 9.3 死代码盘点（**只报告，未动任何代码**）

| 项 | 结论 |
|---|---|
| `province*` 配置 | 确认**零消费**（`AGENTS.md` 已标注） |
| `worldgen/geode/` | **空目录**（`GeodeShape` 删除后的遗留）⇒ 可安全删除，**待你确认** |
| `ClimateZone` | 仍有消费（预览配色图层 / `ConfigSafe`）⇒ **不是死代码**，只是不再主导群系 |
| `MidpointDisplacement` | ✅ **已查清（2026-09-18）**：`generate()` **全仓零调用**＝死方法；但**类不能删**（`Node` / `ElevationSampler` 是生产类型，`RiverLineNetwork` 大量使用）。💣 并挖出隐藏耦合：`RiverTrace.nodeCount()` 仍消费 `fractalLevels` 决定**新范式 D8 河线的节点数** ⇒ 已在 `RiverLineParams` / `RiverTrace` 加警告，**勿当死参数清理**（会静默改变产出） |
| `runLandEConformityProbe` | ✅ **已查清（2026-09-18）：不是回归，是刻意保留的靶子** —— 判据 `maxJump < 0.02e`，当前 0.056e（已由 0.414e 改善 7.4×）；探针注释明写"保持严格判据并如实报 FAIL，**不为了让 CI 变绿而放宽阈值**"，`README` 已知限制亦有记录 ⇒ **勿为它放宽阈值，也勿纳入门禁** |
| `WaterPhysicsProbe` | ⏳ 仍待决：判据 `viol<=0.5 && walls==0` 已过时（永远红）。可选：改判据为「基线 + 容忍」并显式打印基线 vs 当前，或承认它是仪表。**未动** |
| `worldgen/geode/` | ⏳ 空目录，删不删都无影响（git 不跟踪空目录，实际未入库）。**未动** |

---
*本文为交接用，随后续工作更新。*
