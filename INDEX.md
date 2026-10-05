# INDEX — 当前状态与导航（2026-10-05 重写）

> ⚠️ **本文件此前指向的一批水文文档已全部归档**（它们描述一条已被废弃的线）。
> 重写原因：那些指向会让人（含 AI）打开一份过期文档并当成现行规格 ——
> 本轮已因此连续多次误判。

## 0. 当前唯一有效的事实来源

| 想知道什么 | 去哪 |
|---|---|
| **当前代码 = 哪条线** | 仓库根 [`DOCS-STATUS.md`](../DOCS-STATUS.md)（台账，含取证表） |
| 生产代码布局 | 本目录 `forge-1.20.1-47.4.10-mdk/src/main/` |
| 实验链（未接生产） | 本目录 `src/diagnostics/.../hydrology/model/`（80 文件） |
| 已归档文档 | `_handover/archive/`（**读前先确认它描述哪条线**） |

## 1. 两条线的关系（不要再混淆）

| | 仓库根（主工作区） | 本 worktree |
|---|---|---|
| 分支 | `fix/port-lake-p2-2` | `fix/continuous-river-coherence` |
| 生产水文 | `riverline/RiverLineNetwork` | `sim/`（19 类） |
| 实验链 | 无 | `model/`（80 文件，53 task，**main 引用 0 次**） |

⚠ **用户裁定（2026-10-05）**：「早就统一开发一条线了」——但两条线的**生产代码不同**，
这是历史遗留分叉，**取舍待用户决定**。本文件不裁决。

## 2. 已移除/归档（2026-10-05，消除干扰）

| 项 | 处理 | 依据 |
|---|---|---|
| `HydroRefProbe.java` + `runHydroRefProbe` task | **已删** | 用户：「我从来就没说过这个参考图是对的…早就废掉参考了」 |
| `_shots/ref_*.png`（52 张） | 归档至 `_shots/archive-ref-0925/` | 留证不删（渲染图是形态的最终依据） |
| `_handover/2026-09-29_*` `2026-09-30_*` `LAKE-HANDOFF` | 归档至 `_handover/archive/` | 描述已废弃的线 |
| `COMMITMSG-*.txt`（9 个） | 已删 | 临时文件 |

安全网：两工作区已打 tag `safety/docs-cleanup-2026-10-05`。

## 3. 未解决（待用户裁决，勿擅自处理）

1. **两条线取舍**（见 §1）。
2. **`model/` 实验链的去留**：80 文件 / 53 task，`main` 引用 0 次。
   它是"连续河网"路线（`ContinuousRiverNetwork` + `EquilibriumWater` + `HydrologyPipeline`），
   含本会话的平行线根修（`39ba0e8`，实测仅减轻 15%，**不构成"根修"**）。
3. **水文全周期目标**：用户要求「源头→汇流→蓄水（湖泊）→溢口→下泄→入海」。
   现状：`sim/` 有该阶段枚举（`FlowState`）但**仅在合成地形上通过门禁**；
   真实地形未验证（且其地形取数 `erodedHeightForRouting` 实测偏 27.5 块，存疑）。

## 4. 阅读顺序

1. 先读 [`../DOCS-STATUS.md`](../DOCS-STATUS.md) 确认代码线。
2. 再读本文件。
3. 需要历史时读 `_handover/archive/`，**但先确认它描述哪条线**。

*本文件重写于 2026-10-05；旧版内容见 tag `safety/docs-cleanup-2026-10-05`。*