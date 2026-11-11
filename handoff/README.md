# handoff/ —— 交接与归档记录（按时间倒序）

> ⚠ **这里全部是历史记录，不是现状**。当前事实看仓库根的 [`HYDROLOGY.md`](../HYDROLOGY.md) 与 [`AGENTS.md`](../AGENTS.md)。
>
> 每份交接都是**当时那一刻**的快照。里面的 HEAD、schema 版本、探针计数、覆盖率都会过期；
> 大多数文件已在开头自行标注失效或被后续段落推翻。**引用前先读它的开头**。
>
> ### ⚠ 分支口径变更（2026-11-06）：`main` 就是主线
>
> 2026-10-25 起主线一直是 `feat/hydrology-target`，`main` 长期停在 2026-09-30 的
> sim 线且与主线分叉。2026-11-06 已收口：`main` 强推对齐主线、本地分支改名 `main`、
> GitHub 默认分支切到 `main`，并删除远端 `feat/hydrology-target`。
>
> ⇒ **以下这类历史表述自 2026-11-06 起全部失效，不要再照做**：
> `main` 是旧的 / 不含定稿 · `git checkout feat/hydrology-target` 是必须的 ·
> 「分支领先 `origin/main` N 个提交未推送」。
> 涉及的文件：`GeoGenesis-20261103-CSharp-port.md`、`GeoGenesis-20261103-final.md`、
> `GeoGenesis-20261103-state.md`，以及 10-29～11-05 各份 HEAD/推送状态行。
> 这些文件本身**不改**（历史快照），一律以本说明为准。

> ### ⚠ 提交 SHA 全量变化（2026-11-06，第二次）
>
> 为清除提交尾注里的 co-author 署名，`main` 做了两次 `git filter-branch`：
> **只改提交信息，589 个提交的树内容逐字节不变**（前后 HEAD tree 均为
> `cbf162955933ca59d0ead019860a211f2afc2ac6`），但**所有 SHA 都变了**。
>
> - 本目录的 `*.md` 与 `research/*.md` 里引用的 SHA **已就地改成新值**；
> - `attachments/**` 是冻结证据（含 `final-head.txt`、出图清单里同时当文件名的 SHA），
>   **刻意保留旧 SHA**，旧值反查见
>   [`audit/git-sha-remap-20261106.txt`](../audit/git-sha-remap-20261106.txt)；
> - 旧 SHA（`attachments/**` 与 `_archive/**` 里那些）在本地/远端都已无法解析，
>   属预期，不是仓库损坏 —— 拿映射表比对即可。

## 最近一次（若你是接手的新对话，从这里开始）

| 文件 | 内容 | 状态 |
|---|---|---|
| **[theseus-tech-proposal-ARCHIVED-20261111.md](theseus-tech-proposal-ARCHIVED-20261111.md)** | **Theseus 提案的归档说明**：PR #1 因 base 分支删除被误关、PR #2 只是恢复脚手架；4 个文档/探针已逐字节收编进 `main`，PR #2 / 远端分支 / worktree 均已清理。提案里的栅格边界方案**从未落地** | ✅ 现行；归档只读，**不是规格**，不要当设计依据 |
| **[GeoGenesis-20261111.md](GeoGenesis-20261111.md)** | ★ **最新冷启动交接：配置面板重设计收尾（统一相机 / 两段式运镜 / 收拢遮罩 / 单滚轮 / 小窗口页签 2→11） + README 重写 + 第三方许可核查 + 世界类型翻译键**。含 6 个提交的证据表、实测门禁结果、17 条「改 X 看 Y」文件地图、10 条踩坑与回滚、10 项待办（含归属）、接手自检清单 | ✅ **现行**；交接建立时 `HEAD=654d7fb`、本地领先远端 1 且工作区有 1 处未提交口径修正 ⇒ **SHA/未推送数会过期，用前先跑 §2.1** |
| [GeoGenesis-20261105-bp.md](GeoGenesis-20261105-bp.md) | **2026-11-05 隔离 B→P 接线、验证与海岸沟纹交接**：四类职责、114文件基线/8内核用例、exactP局部图、接缝与内核来源证据、fresh复现限制及精选附件。最新裁定：**这些沟纹先保留，不用修了** | 本地未提交快照，待主代理复核；**未接 model/F/MC/preview**；骨架暂停；文档交接完成即停，不提交推送 |
| **[GeoGenesis-20261104-preeorder.md](GeoGenesis-20261104-preeorder.md)** | ★ **2026-11-04 交接：预侵蚀（低配沟壑）评估与终止**。判定预侵蚀不能作为粒子侵蚀的低配替代品（落点重叠、叠加只加深；L3 近零开销只在离线成立；生产无折线沟壑数据源）；含 9 条被证伪的形态路线清单与共同根因、诊断工具链（commit `74f4cf2`）位置、推荐改用现成开关 `erosionRidgeEnabled` / `erosionDropsMul`。`src/main` 零改动 | ✅ 现行（结论未被推翻）；分支已于 2026-11-06 推送（161 提交），`origin/main` 已对齐本分支；⚠ §7 的 `erosionRidgeEnabled` 已随骨架暂停删除，该节已加失效补记，见 `audit/GeoGenesis-旧侵蚀路径清理清单-20261106.md` §7 |
| **[GeoGenesis-20261103-state.md](GeoGenesis-20261103-state.md)** | ★ **2026-11-03 状态交接（沙箱地形冷启动首选）**：当前状态 / 半成品清单 / 环境依赖 / 关键文件地图 / runbook / 7 条踩过的坑与回滚 / 待办归属 / 术语口径 / 接手自检清单。与下面两份的关系：本文讲"现在是什么状态"，final 讲"定稿结论与否决记录"，CSharp-port 讲"怎么移植" | ✅ 现行；工作区干净、与远端一致 |
| [GeoGenesis-20261103-CSharp-port.md](GeoGenesis-20261103-CSharp-port.md) | 定稿地形的 **C# 移植说明**（给下游开发者）：数据契约与可用的 C# 读取代码、坐标映射陷阱、5 层算法拆解（7376 行）、23 处 Java 反射的替换方案、float/回绕/floor 等跨语言坑、圆锥光照验证判据。开头含重新 clone 注意事项 | ✅ 现行；面向 C# 复现 |
| [GeoGenesis-20261103-final.md](GeoGenesis-20261103-final.md) | **2026-11-03 定稿交接：`morph-coastal` 地形数据（SHA `7BE04183…`）+ 青色水面色带渲染器**。含两个"水占比"口径必须分开写（高度 35.81% vs 标签 66.93%）、光照反向事故与圆锥守卫、B1 接入连续高度链的实测失败与清理、56000 块窗口四周皆海陆块旁证、复现步骤、§7.1 历史重写说明 | ✅ 现行定稿；`src/main` 零改动；**沙箱产物，未接生产、未跑 Minecraft** |
| [GeoGenesis-20261031.md](GeoGenesis-20261031.md) | **2026-10-31 冷启动交接：地形形态过渡四层（单条双族→十组→真实边界三向→18配方+完整沙箱1401）**。8 个提交按功能分组已入库；含 7 个提交各自可独立编译的验证、runbook、7 条实际踩过的坑、接生产的 6 项前置条件 | ✅ 现行；`src/main` 自 `5996807` 逐字节未变；**未接生产、未跑 Minecraft** |
| [terrain-morph-final-20261031.md](terrain-morph-final-20261031.md) | 五大类10组独立形态过渡定稿：综合效果与A/B/C采样实测，选A固定240块；B/C仅留实验；横纹不再处理 | 独立原型定稿；未接整幅地形，基准未改 |
| [skeleton-overlay-failed-20261030.md](skeleton-overlay-failed-20261030.md) | 2026-10-30 骨架侵蚀「后置叠加」验证**失败**：delta 从生产 ±5 块放大到 ±188 格（37 倍）；局部七类窗口暴露放射星（海岸带）、山地抹平、幅度失控。根因=接入点错误（骨架应在地形流水线内，不是后处理）。**用户决定：等沙箱地形正式接入生产后再验证骨架** | ✅ 现行；`src/main` 零改动；实验代码已清理 |
| [segment-field-conclusions-20261030.md](segment-field-conclusions-20261030.md) | 2026-10-30 `RefNoise.Erosion`（线段距离场）放射星调查：**用户假说「多线段共用格心」证实**，但修掉共用格心就失去锐度（锐度与星芒同源）。八轮尝试全否决，最终改用骨架侵蚀 | ✅ 现行（结论有效） |
| [erosion-operator-conclusions-20261030.md](erosion-operator-conclusions-20261030.md) | 2026-10-30 侵蚀算子改进的**否决性结论**：方案 A/B/C、低地骨架、软化 RefNoise、DirectionWarped —— 全部实测否决 | ✅ 现行 |
| [erosion-operator-review-20261030.md](erosion-operator-review-20261030.md) | 2026-10-30 侵蚀算子实现评审：生产 2 套（`ErosionEngine` 液滴 / `RidgeValleyErosion` 条纹）结构详解、5 个已知缺陷、5 条改进提案 | ⚠ 缺陷清单有效；**提案 P1（低地骨架）已被实测否决** |
| [GeoGenesis-20261030-b.md](GeoGenesis-20261030-b.md) | 2026-10-30 下午轮：浅海群岛三步法 + 海岸剖面重构（沙箱）；丘陵 `DALES_LO` 去高频（**用户认可**）；4 轮翻转实验否决记录 | ✅ 现行；`src/main` 零改动，沙箱未提交 |
| [GeoGenesis-20261030.md](GeoGenesis-20261030.md) | 2026-10-30 冷启动交接：地形升级沙箱入库（`33a508d`），**群岛/弧岛实验全部回退**；`src/main` 零改动；用户明确叫停"自行发挥" | ✅ 现行（仅 §6.1 四个决策点待用户裁定） |
| [GeoGenesis-20261029.md](GeoGenesis-20261029.md) | 2026-10-29 冷启动交接：端口开关默认相反的真 bug、死代码清理、源头抽稀 0.7、三提交未推送。**当日晚 schema 119 已经用户实机认可见证并定为现行标准图**（口径见 `attachments/baseline-schema119-metadata-20261029.txt`） | ✅ 截至 2026-10-29 有效（仅"待推送"仍未决） |
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
| [attachments/full-morph-20261031/](attachments/full-morph-20261031/) | 完整沙箱六臂 1401² 同窗口并排大图（基准/仅台地改动/新形态过渡 × 有无群岛）+ 差图 + 三臂归因 | ✅ 现行；主对比 `pair-islands.png`；基准两臂 SHA 内置硬对拍 |
| [attachments/recipe-junctions-20261031/](attachments/recipe-junctions-20261031/) | 18 配方 20 场景局部图（5 真实 + 10 大类三向 + 4 复合 + 1 子配方），原生 601² + 三条剖面 | ✅ 现行；`index.html` 为总入口 |
| [attachments/real-boundary-local-20261031/](attachments/real-boundary-local-20261031/) | 真实 Voronoi 边界几何 + 三向局部验证（含观测普查与几何 12 项判据） | ✅ 现行；限制见其 provenance |
| [attachments/hills-plateau-reference-range-20261031/](attachments/hills-plateau-reference-range-20261031/) | 台地 Terrace 值域修正（1.05→0.9）的换档检查与空间对照 | ✅ 现行 |
| [attachments/morph-final-20261031/](attachments/morph-final-20261031/) | A/B/C 三方案性能实测（perf.csv）与十组定稿资产 manifest | ✅ 现行；B/C 未采用 |
| [attachments/revert-baseline-20261030/](attachments/revert-baseline-20261030/) | 回退群岛/弧岛后的三臂基线图 + 冻结 TOML | ✅ 现行基础地形沙箱基线 |
| [migration-checkpoint-20261025/](migration-checkpoint-20261025/) | 迁移入库时的目标/基线 PNG、provenance、`TARGET.md` | 只作历史基准；参数与源码来源须重核 |

## attachments/

`attachments/` 是各次交接引用的日志、CSV、指标与一份缺陷复现探针源码。
其中 2026-10-12 的 7 份来自更早的共享湖域/预热实验，**本次整理未移动它们**，以免破坏既有相对引用；
它们不构成对当前实现的 ALL PASS 证明。

---
*索引建立于 2026-10-28 文档整理；本文件只登记，不改写任何原文档内容。*
