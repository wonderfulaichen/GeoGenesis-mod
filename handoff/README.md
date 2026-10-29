# handoff/ —— 交接与归档记录（按时间倒序）

> ⚠ **这里全部是历史记录，不是现状**。当前事实看仓库根的 [`HYDROLOGY.md`](../HYDROLOGY.md) 与 [`AGENTS.md`](../AGENTS.md)。
>
> 每份交接都是**当时那一刻**的快照。里面的 HEAD、schema 版本、探针计数、覆盖率都会过期；
> 大多数文件已在开头自行标注失效或被后续段落推翻。**引用前先读它的开头**。

## 最近一次（若你是接手的新对话，从这里开始）

| 文件 | 内容 | 状态 |
|---|---|---|
| [GeoGenesis-20261029.md](GeoGenesis-20261029.md) | 2026-10-29 冷启动交接：端口开关默认相反的真 bug、死代码清理、源头抽稀 0.7、三提交未推送 | ✅ 截至 2026-10-29 有效 |
| [GeoGenesis-handoff-20261026.md](GeoGenesis-handoff-20261026.md) | 2026-10-26 冷启动交接：不可变用户要求、半成品边界、旧机制可继承经验、runbook | ⚠ 其 §1 的 HEAD/schema 已过期；结论性纠偏仍有效 |

## 2026-10-22 ~ 10-26 专项记录

| 文件 | 主题 | 状态 |
|---|---|---|
| [GeoGenesis-hydrology-20261022.md](GeoGenesis-hydrology-20261022.md) | 分层水文评估阶段的整体状态 | ❌ 已被 §〇bf 之后的记录覆盖 |
| [GeoGenesis-hydrology-20261022-b.md](GeoGenesis-hydrology-20261022-b.md) | 同期续记（含 §4.3 的连通性审计） | ❌ §4.3 声称的审计值经复测不成立 |
| [GeoGenesis-m2-handoff-start-20261022.md](GeoGenesis-m2-handoff-start-20261022.md) | M2 接力起点容错 | 记录有效，问题本身未修 |
| [GeoGenesis-join-attribution-20261022.md](GeoGenesis-join-attribution-20261022.md) | JOIN 归因分离（15.5% 是绕回自己） | 归因有效 |
| [GeoGenesis-selfjoin-fixA-20261022.md](GeoGenesis-selfjoin-fixA-20261022.md) | 修复 A 实测：变差 ⇒ 默认关闭 | 否决记录，防重犯 |
| [GeoGenesis-fixB-frameescape-20261022.md](GeoGenesis-fixB-frameescape-20261022.md) | 修复 B：机制找到但修不动 | 未修 |
| [GeoGenesis-spill-rise-audit-20261022.md](GeoGenesis-spill-rise-audit-20261022.md) | 洼地出口三个假设全部否证 | 否证记录 |
| [GeoGenesis-legacy-vs-model-crossregion-20261022.md](GeoGenesis-legacy-vs-model-crossregion-20261022.md) | legacy 与 model 跨区行为对照 | 参考价值 |
| [GeoGenesis-multiphysics-contract-20261022.md](GeoGenesis-multiphysics-contract-20261022.md) | 多物理契约（1—7 节） | ❌ 1—7 节是历史，不是当前未修列表 |
| [GeoGenesis-cross-region-dup-20261025.md](GeoGenesis-cross-region-dup-20261025.md) | 跨 region 重复发布 | ⚠ 开头已自标失效；A1/A2/A3 均未实施 |

## 研究

| 文件 | 内容 | 状态 |
|---|---|---|
| [research/legacy-river-mechanism-20261026.md](research/legacy-river-mechanism-20261026.md) | 已移除的 legacy 河流机制研究：可继承经验与被撤回的结论 | 有效；**不整体恢复旧链** |

## 出图基准检查点

| 路径 | 内容 | 状态 |
|---|---|---|
| [migration-checkpoint-20261025/](migration-checkpoint-20261025/) | 迁移入库时的目标/基线 PNG、provenance、`TARGET.md` | 只作历史基准；参数与源码来源须重核 |

## attachments/

`attachments/` 是各次交接引用的日志、CSV、指标与一份缺陷复现探针源码。
其中 2026-10-12 的 7 份来自更早的共享湖域/预热实验，**本次整理未移动它们**，以免破坏既有相对引用；
它们不构成对当前实现的 ALL PASS 证明。

---
*索引建立于 2026-10-28 文档整理；本文件只登记，不改写任何原文档内容。*
