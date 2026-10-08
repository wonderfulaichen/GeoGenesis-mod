# 文档状态台账

> **为什么有这份文件（2026-10-05 建立）**
>
> 本项目文档量极大（`docs/` 146 个 + `参考/` 约 90 个 + 根目录 8 个核心 md），
> 且**存在两条并行的代码线**，各自的文档会对同一问题给出**相反**的答案。
>
> **实测事故（勿重演）**：2026-10-05 那一轮，我读了主工作区的 `AGENTS.md`
> 当作现行规格，据此判断"水文骨架 `sim/` 是正确核心"，于是连续 5 次给出错误结论
> （把另一条线的文档当成权威、把诊断层代码当成生产路径）。
> **根因不是文档写错，而是文档没有说明"自己描述的是哪条代码线"。**
>
> ⇒ **本文件的作用：让"该信哪份"变成一眼可见的机械判断，而不是靠推理。**
> 任何开工前先读本表。

---

## 0. 当前代码（唯一权威基准）

**用户口径（2026-10-05 明示）**：「当前的代码才是我需要的正在一步步改进的代码。」

| 项 | 值 |
|---|---|
| **工作区** | `D:\Office software\Development Project\GeoGenesis-mod\`（仓库根） |
| **分支** | `fix/port-lake-p2-2` |
| **HEAD** | `ad4e7ad` |
| **水文架构** | `hydrology/flowaccum/FlowField` + `hydrology/riverline/RiverLineNetwork` + `hydrology/flow/TerrainFlowSim` |
| **未推送** | 是（`fix/port-lake-p2-2` 尚未推 origin） |

⚠ **判断任何文档是否适用，先确认它描述的是这条线**（本项目两条线的文档互相矛盾，见 §1）。

---

## 1. ⛔ 两条代码线（最常见的错误来源）

⚠ **2026-10-05 重大更正**：本节此前把 worktree 那条线描述为"实验线"，
**这是错的，且它正是把局面搞乱的原因之一**。两条线不是同级的两个实验，
而是**两种不同性质的工作**：

| | **A 线 = 当前代码** | **B 线 = worktree** |
|---|---|---|
| 性质 | **在现有水文上继续改进** | **一次全量重写（含删除旧链）** |
| 分支 | `fix/port-lake-p2-2` | `fix/continuous-river-coherence` |
| 分叉点 | `c12f76e`（两条线的共同祖先） | 同 |
| 分叉后提交数 | **13** | **8**（其中 2026-10-05 的 8 个由 AI 产出） |
| 改动规模 | **5 个生产文件** | **189 文件、+20552 / −20434** |
| 水文实现 | `flowaccum/FlowField` + `riverline/RiverLineNetwork` + `flow/TerrainFlowSim` | 新增 `hydrology/sim/` 整包（14 类），**并删除** `flowaccum/` `flow/` |
| HEAD | `ad4e7ad` | `6808a22` |

**取证**（`git diff --name-only c12f76e <branch> -- .../src/main`）：

A 线改动的生产文件（5 个）：
```
hydrology/HydrologyBlockCarver.java
hydrology/HydrologyExperimentEngine.java
hydrology/riverline/RiverLineNetwork.java
hydrology/riverline/RiverLineRegion.java
terrain/GeoGenesisTerrain.java
```
提交主题：湖泊 P2-2 修复 → 河尾统一湖水位 → **河道只下挖铁律** → 骨架折线复用 D8 收尾 →
isLake 口径 → 各种探针。

B 线改动的生产文件（**36 个**）：含 `hydrology/sim/*` 全新增（14 类）、
`flowaccum/*`、`flow/*`、`riverline/*` 重写、`terrain/CellGenerator.java`、
`terrain/GeoGenesisTerrain.java`、`config/GeoGenesisConfig.java`、
`client/preview/PreviewDisplay.java`。

### ⚠ 为什么 B 线的文档会误导人

`_model_wt/AGENTS.md` 写着「`TerrainFlowSim` / `FlowField` / `RiverLineNetwork` /
`RiverLineRegion` **已被删除**」——**这句话对 B 线是真的**（那条分支确实删了），
但**对 A 线完全无关**（A 线磁盘上这四个类都在，且正在被修改）。

⇒ **误读路径**：打开 `_model_wt/AGENTS.md` → 看到"水文结论整体废止"＋语气权威的 ⛔ 警告
→ 以为那是全项目现状 → **实际它只描述 B 线**。
（2026-10-05 AI 因此连续 5 次给出错误结论，用户反馈"被你之前弄得乱了"。）

### 3.2 环境实测对照（2026-10-05，同 seed / 同窗口实测）

| 维度 | **A 线 = 当前代码** | **B 线 = worktree** |
|---|---|---|
| 编译 | ✅ BUILD SUCCESSFUL | ✅ BUILD SUCCESSFUL |
| **门禁 `runWorldgenGate`** | ❌ **FAIL** | ✅ **ALL PASS** |
| 正典图出图耗时 | 8m41s（`RiverPlainViewProbe`） | 1m45s（`CanonicalViewProbe`） |
| 图上现象 | 河**几乎不成网**，5 条孤线直穿高地入海 | 河**碎成渣**：10,775 分量 / 83% ≤2 格 |
| 改动规模 | 12 提交 / 27 文件 / +2714 −45 | 22 提交 / 189 文件 / +20552 −20434 |

⚠ **A 线门禁 FAIL 的原因已定位（不是水文代码缺陷）**：
`build.gradle` 的 `runWorldgenGate` 用 `dependsOn` 让 10 个探针**并行**，
共享同一 `CellGenerator` 静态状态 ⇒ **探针间相互污染**。
证据：单独跑 `runHydrologyDeterminismProbe` ⇒ **ALL PASS**（`max|A−B|=0`、
`max|Δheight|=0`）；放进门禁就 FAIL。
⇒ **这是 A 线的构建配置缺陷**，修法是让门禁探针串行（`mustRunAfter`）或隔离静态状态。
**在修好之前，A 线的门禁不可作为回归依据。**

⇒ 两线各有硬伤：**A 线**门禁配置有缺陷 + 河不成网；**B 线**河碎成渣。
**都不适合直接作为"河网该长什么样"的答案。**

---

## 2. 根目录核心文档

| 文档 | 最后更新 | 描述哪条线 | 状态 |
|---|---|---|---|
| `AGENTS.md`（根） | 2026-10-02 | **A 线**（架构表含 `RiverLineNetwork`/`TerrainFlowSim`） | ✅ **适用当前代码** |
| `HANDOFF.md`（根） | 2026-10-04 | 混合：§1–17.5 是 A 线历史，**§17.6 末尾的"建议方案"已被实测证伪** | ⚠ **部分过期，见 §4.1** |
| `HANDOFF-ENV.md`（根） | 2026-10-04 | A 线 | ✅ **适用**（正典半径 700 = 1401×1401，**已定稿，勿改**） |
| `ARCHITECTURE.md`（根） | 2026-09-18 | A 线 | ✅ 适用，但较旧 |
| `README.md`（根） | 2026-09-18 | A 线 | ✅ 适用 |
| `PLAN-hydrology.md` | 2026-09-21 | A 线 | ⚠ 历史实施记录 |
| `PLAN-hydrology-flow.md` | 2026-09-21 | A 线 | ⚠ 历史实施记录 |

---

## 3. `_model_wt/` 内的文档（**B 线，通常不适用**）

| 文档 | 状态 |
|---|---|
| `_model_wt/AGENTS.md` | ⛔ **含"本文水文结论已整体废止"警告** —— 但该警告针对 B 线的 `sim/` 取代关系，**不适用于 A 线** |
| `_model_wt/INDEX.md` | B 线导航入口 |
| `_model_wt/HANDOFF.md` | B 线交接（止于 2026-09-20，比根目录那份旧） |
| `_model_wt/_handover/2026-09-29_*.md` | B 线交接（含"唯一水文管线已定型"等**对 A 线无效**的裁定） |

⚠ **`_model_wt/AGENTS.md` 是本项目最大的误导源**：它语气权威、含 ⛔ 警告，
容易被当成"最新总规"，但它描述的是另一条线。

---

## 4. 已实测的冲突点（只记录，不裁决）

### 4.1 "河流断开 / 折线 / 平行线"的归因

| 断言 | 出处 | 状态 |
|---|---|---|
| "ε 阶梯导致平原直线" | `_model_wt/_handover/2026-09-29` §4 | ❌ 该文自己写明"已被实验反驳" |
| "区域坡向集中说明直线不是 bug" | 同上 | ❌ 同上写明"不成立" |
| "等水位区改用真实地形坡度定方向" | `HANDOFF.md` §17.6 | ❌ **2026-10-05 实测证伪**：河道格 ∇bed P50=0.42，14,304 格 ≥0.1，仅 7 格 <0.01 ⇒ 河不在平地上 |
| "平行线 = recover() 沿 parent 链重复注入" | 2026-10-05 `model/` 诊断层 | ⚠ **在 `model/` 链内成立**；该链是否属于目标形态**待裁决** |

### 4.2 正典渲染规格

| 断言 | 出处 | 状态 |
|---|---|---|
| 半径 700 ⇒ 1401×1401 | `HANDOFF-ENV.md` §2 | ✅ **定稿**。2026-10-05 曾被误改为 1000，已回退（commit `6808a22`） |
| 参照图必须与游戏**同一渲染口径**（山体阴影） | `_model_wt/_handover/2026-09-29` §0 铁律 #4 | ✅ 仍有效（但与上面的"无光照等高线"是两种口径，见 4.4） |

### 4.3 成河阈值

| 值 | 出处 | 状态 |
|---|---|---|
| `channelThreshold = 852` | 09-30 实测判定为根因 → 改 120 → 再改 3000 | ⚠ 三次变更，A 线当前值需查 `HydroConfig` |

### 4.4 渲染口径：山体阴影 vs 无光照等高线

| 主张 | 出处 | 状态 |
|---|---|---|
| 必须用山体阴影（与游戏一致） | 09-29 交接 §0 #4 | 与下条**直接冲突** |
| hillshade 会把谷画成脊 ⇒ 改用无光照等高线 | `ViewSpec.contour` javadoc（2026-10） | ⚠ **两者不可兼得**；需明确"哪张图用于什么判定" |

---

## 5. 不被 git 追踪的目录

以下目录**内容不进版本库**（`.gitignore`），重排/删除无历史包袱，但也意味着**无版本保护**：

| 目录 | 规模 | 说明 |
|---|---|---|
| `docs/` | 146 个 md | 含 `06-历史归档/archive/chat/` 下约 1.5 MB 聊天记录（易被误当规格）。已有 [`docs/README.md`](docs/README.md) |
| `参考/` | 约 90 项 | 参考项目源码与调研。已有 [`参考/README.md`](参考/README.md)（含"可借鉴/不可照搬"结论） |
| `_shots/` | 62 PNG / 39.7 MB | 历史对照图，**有证据价值不可删**。已有 [`_shots/README.md`](_shots/README.md)（含三类误判来源警示） |
| `_model_wt/` | worktree | 另一条代码线，由 git worktree 管理 |
| `.openbitfun/` | 3 个 plan | 本地决策记录，**是否采纳待用户裁决** |

以上均已加入 `.gitignore`（2026-10-05），故 `git status` 不再被它们淹没。

### 5.1 2026-10-05 已清理

| 项 | 处理 | 理由 |
|---|---|---|
| `_tmp_final1.txt` / `_tmp_final2.txt` | **已删** | `runWorldgenGate` 输出重定向残渣（79 KB），零文档引用 |
| `.dbg/`（1 文件，2026-06-13） | **已删** | 空置 4 个月，仅一个失效的调试端口配置 |
| `_shots/` | **保留 + 加 README** | 渲染图是形态判定的最终依据，删则无法回溯 |

---

## 5.2 未解决（需要用户裁决）

1. **两条代码线**是否合并/取舍（见 §1）。
2. `worktree` 分支 `fix/continuous-river-coherence` 上 2026-10-05 产生的 8 个提交
   （全在 `model/` 诊断层，改的是当前代码线**不走**的路径）：
   `1c1cb14` `91644a3` `37d2283` `01c9a3d` `6b8aaf7` `39ba0e8` `6808a22`。
   其中**建议保留**的两项（真问题，与代码线归属无关）：
   - `91644a3` 溢流注入全局预算（单出口上限形同虚设 ⇒ 45 分钟不收敛）
   - `6b8aaf7` 的 `contourFixed` / `assertCanonicalRadius`（固定色标，防跨图误判）

---

## 6. 使用纪律（写给任何接手的人，包括 AI）

1. **先查本表**确认文档适用哪条线，再读文档。
2. **改任何渲染规格前先读 `HANDOFF-ENV.md` §2** —— 那里是定稿值，且历史上改过两次都被用户纠正。
3. **文档说"已删除/已废止"时，先用 `Test-Path` / `git ls-tree` 核实磁盘**，不要直接采信（本项目有 4 处以上"文档说改了代码没改"的记录）。
4. **不要凭文档推断"哪个是对的"** —— 两条线都自称正确，裁决权在用户。
5. 新增/废弃架构时，**在同一提交里更新本表**。

---

*建立于 2026-10-05。事实部分可用 `git ls-tree` / `Test-Path` 复现；裁决部分待用户填写。*