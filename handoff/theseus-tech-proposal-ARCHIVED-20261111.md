# Theseus 提案：作为历史证据归档（2026-11-11）

> 本文件说明为什么 `handoff/theseus-tech-proposal-20261102.md` 与
> `handoff/attachments/theseus-proposal-20261102/` 会出现在 `main` 上，
> 以及它们**不是**现行设计、更不是已落地的实现。

## 来龙去脉（用户口述 + GitHub 状态核实）

- PR **#1「提案：地形链性能与架构改进（Theseus 接入实测）」**：
  base 是 `feat/hydrology-target`。该分支在 2026-11-06 分支收口时被删，
  GitHub 因此把 PR 自动 **closed（非合并）**。用户原话：
  「theseus-main 我记得是之前有个 PR 我改仓库被误关闭了，后面恢复遗留的」。
- 为把内容救回来，当时建了 `proposal/theseus-main`（基于 `main`），
  把 4 个文件 cherry-pick 过去，并开了 PR **#2** 作为 open draft。
  同时在主仓库外建了 linked worktree `GeoGenesis-mod-theseus-main`。

⇒ 这三个东西都是**恢复动作的脚手架**，不是待评审的现行提案。

## 本轮处置（用户裁定「收编后彻底清理」）

1. 把 4 个文件**逐字节**收进 `main`：
   - [theseus-tech-proposal-20261102.md](theseus-tech-proposal-20261102.md)
   - [BoundaryApproxProbe.java](attachments/theseus-proposal-20261102/BoundaryApproxProbe.java)
   - [RasterBoundaryProbe.java](attachments/theseus-proposal-20261102/RasterBoundaryProbe.java)
   - [TileScanProbe.java](attachments/theseus-proposal-20261102/TileScanProbe.java)

   来源 `origin/proposal/theseus-main:c0b610e`。前三个文件用 `git show` 原始字节
   直接写回；提案 doc 曾被 PowerShell 管道把 CRLF 改成 LF，已用
   [fix_proposal_bytes.py](attachments/theseus-proposal-20261102/fix_proposal_bytes.py)
   以二进制方式修复。最终 4 个文件逐一 `raw == local` 校验：`VERIFY PASS`。
2. 关闭 draft PR #2，删除远端 `proposal/theseus-main` 与本地同名分支，
   用 `git worktree remove` 移除本地 worktree（不直接删目录，避免 stale admin 条目）。
3. `main` 的 `src/main` **零改动**：不接生产、不改地形产出、不升缓存版本。

## 提案本身的状态（避免后人误当规格）

文档自述：「下面的数字都在本机跑过……**这是建议，不是要求，取舍由你定**」，
且「未验证」一节明确：只测了一个种子和一个窗口、栅格方案没和定稿图做观感对比、
全链 8 倍提速是估算。

它提出的「正向 warp 栅格标签 + 欧氏距离变换」替代「warp 反解 + 折线距离」
**从未落地**：生产代码 `MorphBoundaryGeometry.java:334` 仍是 `warpInverse`，
全 `src/main` 搜 `Felzenszwalb|distanceTransform|栅格边界` 零命中。

⇒ **归档只读**。若要重启这项优化，请另开方案并重新实测，不要把本文当设计依据。

## 证据与当前状态（会过期，用前重查）

- 收编提交：本轮提交（见 `git log`）
- PR #1：`CLOSED` / 非合并；PR #2：`CLOSED`（本轮关闭）
- 远端分支 `proposal/theseus-main`：本轮删除
- worktree `GeoGenesis-mod-theseus-main`：本轮 `git worktree remove`
- 原始 JFR 数据（边界层 89% 耗时、远坐标崩溃最近半径约 5.2 万块、
  栅格方案高度差 p99 约 1 块）保留在上面那份提案文档正文里
