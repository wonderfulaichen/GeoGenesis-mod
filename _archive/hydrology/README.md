# 水文文档归档（2026-10-09）

> **这些文档已不再维护**，仅作考古用。
> 现行事实来源是仓库根的 **[`HYDROLOGY.md`](../../HYDROLOGY.md)**。

## 为什么归档

用户原话：「**这些文件都被你弄乱了。我记得有好多水文文件。可以删除或移动或整理这些文件。
我印象中一直在加。搞得文件乱乱的。好像有10多份水文的md**」

问题不是"文件多"，而是**它们互相矛盾且多数已过时**，继续放根目录会误导接手者。
本项目已因此连续出错（见 `HYDROLOGY.md` §七 的血泪教训）。

## 归档清单与失效原因

| 文件 | 内容 | 为什么失效 |
|---|---|---|
| `AGENTS.md`（原根目录版）| 项目总纲，145 KB，含大量水文历史 | 水文部分已过时（见下）；有效部分已提炼进 `HYDROLOGY.md` |
| `HANDOFF.md` | 2026-09-18 交接（湖岸贴合/确定性/性能）| 描述 **legacy 链** 的工作，与 model 链无关；§17.6 的"建议方案"已被实测证伪 |
| `HANDOFF-ENV.md` | 环境速查 | **worktree / 两条代码线**的描述已失效（model 链已移入 `src/main`）|
| `DOCS-STATUS.md` | 文档状态台账 | **它自己也过时了**：仍写"model 链在 worktree 的 `sim/`" |
| `PLAN-hydrology.md` | 100 KB 水文重构计划 | 历史实施记录；多处结论已被后续实测推翻 |
| `水文统一水面方案-2026-10-05.md` | 五次失败后的方案 | 未获批准、未实施 |
| `hydrology-code-inventory-2026-10-07.md` | 全水文代码盘点（26 文件）| 盘点当时正确；其后代码已大改 |
| `model-pipeline-findings-2026-10-07.md` | model 链实机验收 | 结论（成本不可接受）仍有效，但细节已变 |
| `model-pipeline-regions-raw.txt` | 上述的原始日志 | 证据存档 |
| `hydro-game-vs-render-2026-10-08.md` | 游戏 vs 渲染图差异诊断 | 已被后续更精确的诊断取代 |
| `hydro-regression-rootcause-2026-10-08.md` | 「以前正常、现在不正常」git 定位 | 结论仍有效（是 model 链启用导致），已并入 `HYDROLOGY.md` §六 |
| `hydro-step-rootcause-2026-10-08.md` | `step=4` 根因 | **结论部分被推翻**（当时误把"湖格 ∝ 1/step²"当主因）|
| `hydrology-B-plan-progress-2026-10-09.md` | B 方案进展 | 中间过程；最终根因见 `hydrology-inland-lake-rootcause-2026-10-09.md` |
| `hydrology-architecture-findings-2026-10-09.md` | 单窗口 vs 多 region | **有效**，已并入 `HYDROLOGY.md` §三/§五 |
| `hydrology-inland-lake-rootcause-2026-10-09.md` | ★ 内陆 region `resolved` 根因定案 | **最有价值的一份**，已并入 `HYDROLOGY.md` §六 |

## 归档时提炼进 `HYDROLOGY.md` 的有效内容

1. **生产有两条链**（legacy / model），由开关二选一，各自内部还有 D8/CONTINUOUS
2. **★ 铁律：预览必须走生产 provider**（`-Dhydro.viaProvider=true`）
3. **判据必须与游戏落块层逐字一致**（`fillWater` 而非 `erosion`）
4. **正典规格**（r=700/step=1/固定色标/无光照等高线）
5. **四家参考项目的一致答案**（水位局部量 + 单元 ≥ 流域）
6. **内陆 region `resolved` 全域失效**这个真根因
7. **"平行线 = 地形冲沟，不是缺陷"**

---

## ⚠️ 整理时的核实记录（2026-10-09）

**本项目铁律"文档里抄数值 = 必然漂移"在本次整理中当场应验** ——
新写的 `AGENTS.md` 从旧文档抄了三个数字，**两个是错的**：

| 抄来的说法 | 实测 |
|---|---|
| 探针"约 170 个" | **252 个** `.java` |
| "128 个探针里只有 18 个用 `System.exit`" | **36 个**（分母 252）|
| `WARP_AMP = 0.0` | ✅ 正确（`TerrainCharacterField.java:90`）|

⇒ 已修正。**归档不删错**：本记录保留，提醒后人"抄数值"这一动作本身不可靠。

### 其余核实项（全部通过）

- `hydrologyModelPipelineEnabled` 默认 `false`（`GeoGenesisConfig`）✅
- `HydrologyChunkEngine:66` 二选一分支 ✅
- `ViewSpec`：`STEP=1 / RADIUS=700 / SEED=9139912035078620160 / CENTER=(-140,137) / BAND_BLOCKS=8.0 / BAND_BASE=0.0` ✅
- 9 个诊断旋钮的系统属性名全部命中代码 ✅
- 新文档声称存在的 8 个类文件全部存在 ✅
- 门禁 `runWorldgenGate` 在本轮代码改动后 **ALL PASS**（12 探针 / 3m54s）✅


## 本轮（2026-10-07~09）我犯的错误清单

留在 `HYDROLOGY.md` 与 `hydrology-inland-lake-rootcause-2026-10-09.md` 里 ——
**归档不删错**，因为"同一个坑不踩第二次"依赖知道踩过哪些。

---
*归档于 2026-10-09。若要恢复某份文档，从 git 历史取（归档前均已入库者保留历史）。*
