# 外部备份与遗留分支清理记录（2026-11-11）

> 本文件是 2026-11-11 那一轮「清理外部备份」的完整留痕：做了什么、依据是什么、
> 删了什么、**保留了什么以及为什么**、以及可回退的东西在哪。
> 所有命令与数字都是当轮实测；涉及远端状态的部分已二次复核（见 §五）。

## 一、背景

用户在 `D:\Office software\Development Project`（主仓库之外）发现了几份早期备份，
问是否可以删除。核实后它们分为三类：**已被取代的旧快照**、**待评审的提案工作副本**、
**已被生产采纳但仍是唯一副本的候选**。删除不可逆，因此每一步都先立回退点再删。

## 二、处置总表

| 对象 | 位置 | 处置 | 释放 | 回退点 |
|---|---|---|---:|---|
| `GeoGenesis-offrepo-20261103` | 外部 | **已删** | 3.19 GB | 抢救包 + 审计 JSON（见 §三） |
| `GeoGenesis-morph-p0` | 外部 | **已删** | 2.7 MB | `morph-p0-rescue/` 16 文件 |
| `GeoGenesis-preserved-candidate-20261103` | 外部 | **已删** | 324.7 MB | `preserved-rescue/` 662 文件 |
| `GeoGenesis-mod-theseus-main` | 外部 worktree | **已删**（`git worktree remove`） | 134.6 MB | 4 个文件已收编进 `main` |
| `proposal/theseus-main` | 本地 + 远端分支 | **已删** | — | 同上 |
| PR #2（draft） | GitHub | **已关闭**（留言说明已收编） | — | — |
| `backup/preview-refactor-20261109` | 本地分支 | **已删** | — | 内容已在 `main`；标签亦已删 |
| 标签 `backup-refactor-20261109` | 本地 | **已删** | — | 指向 `35bf0f7`，是 `main` 祖先 |
| `backup-before-rewrite` | 本地 + 远端标签 | **保留** | — | 用户指定 |
| `local-backup-pre-attribution-rewrite` | 本地标签 | **保留** | — | 用户指定 |
| `refs/remotes/bundle/orig-main` | 本地 tracking ref | **保留** | — | 用户指定 |
| `build/git-backup-20261106-rewrite/*.bundle` | build 下 164.4 MB | **保留** | — | 用户指定；`build/` 被 gitignore |

累计释放约 **3.65 GB**。外部总目录现已只剩 `GeoGenesis-mod`。

## 三、offrepo（3.19 GB）的对拍与抢救

写了只读对拍工具 `handoff/attachments/offrepo-rescue-20261111/full_audit.py`：
对 8661 个文件逐个算 size / SHA-256 / git blob SHA-1，再**按内容**（不按文件名）
去主仓库对象库（`git cat-file --batch-all-objects`，覆盖所有 ref/历史/stash/不可达对象）
与工作区找相同 blob。

```
both=527  git_objects=252  workspace_only=3819  orphan=4063  error=0
orphan bytes = 438,200,058 (417.9 MiB)
AUDIT RESULT: PROBLEM
```

⇒ 上一轮基于抽样得出的「大文件是纯重复」被证伪并撤回。
4063 个孤儿里 4044 个 / 413.1 MB 是 MC 运行产物（`run-accept/saves/**` 的 `.mca`、
`logs/*.log.gz`、advancements/stats 等，可再生），19 个 / 4.76 MB 是证据类
（2026-11-03 时点的文档、验收报告、4 个 candidate 探针旧版本），已全部抢救入库。

**两批抢救**（都在 `handoff/attachments/offrepo-rescue-20261111/`）：

- 第一批 30 文件（21 PNG + 3 Java + 6 份 git 状态快照）
- 第二批 19 个证据文件 → `evidence-flat/`，配 `evidence-flat-manifest.json`
  （原相对路径 / size / SHA-256 / git blob）

> ⚠ **踩过的坑**：① 第一版对拍只按原始字节算 blob，Windows CRLF checkout 会把大量
> tracked 文本文件误报成孤儿 ⇒ 后续用 `backup_audit.py`（增加 CRLF→LF 归一化 +
> 远端分支 tree 覆盖），并以主仓库自身跑自检确认 `orphan=0 / PASS`。
> ② 按原目录结构抢救时最长路径 262 字符，Windows `git add` 报 `Filename too long`，
> 且那份历史 `tree-src/.gitignore` 会把 7 个抢救文件反向忽略 ⇒ 改为扁平短名
> （最长 155 字符）+ manifest 保真。

## 四、morph-p0 与 preserved-candidate

**morph-p0（已废弃）**：`SOURCES.md:1-4` 自述「拟生产包，尚未接入」，而该目标已由
提交 `a5d00d2` 达成并接进生产（`BaseWorldSampler.java:43` `new MorphTerrainSampler(...)`、
`CellGenerator.java:244`、`ProcessContext.java:72`）。21 个 Java 里 10 个逐字节相同、
11 个是被后续提交删掉的旧写法（如 `synchronized sample` 被 `0639d7c` 移除）。
抢救 `morph-p0-rescue/` 16 文件 / 74,550 字节（全部非运行孤儿）。

**preserved-candidate（候选已进生产，但目录曾是唯一副本）**：
BEA0 高度 SHA `bea0a47c…` 已被 `audit/GeoGenesis-R0-0-baseline-20261105.md:20-24`
认定为「P1 固定资产 / 原认可资产」，`MorphLandField.java` 的 chunk 索引已进生产
⇒ `not_yet_in_repo: true` 这个前提失效。但 `DoubleFullMorphLand` 等参考臂在
全仓 git 历史与 morph-p0 中零命中，源 HEAD `06343b5` 已脱离 `main` 祖先链、
原分支已删 ⇒ 用户裁定按「已废弃」处理。

抢救 `preserved-rescue/` 662 文件 / 11.28 MB（按内容去重；原 1496 条路径全部记在
manifest 的 `all_original_paths`）。**明确未抢救**：16 个 `.f64be`（209.74 MB，
`UNSUPPORTED-AND-GAPS.json` 自述可由 `repro/verify.ps1` 重生成并按 SHA-256 比对）、
`.class` 编译产物、大 CSV/PNG/log。每个孤儿的路径与 SHA-256 仍留在
`full_audit.json` / `audit_preserved_v3.json` 供事后核对。

## 五、Theseus 遗留（PR 被误关的恢复脚手架）

用户记忆正确：**PR #1 的 base 是 `feat/hydrology-target`**，该分支在 2026-11-06
分支收口时被删，GitHub 因此自动把 PR **closed（非合并、非否决）**。
为救内容而建的 `proposal/theseus-main` + 外部 worktree + draft PR #2 都是恢复脚手架。

处置（用户裁定「收编后彻底清理」）：

1. 4 个文件**逐字节**收编进 `main`：
   `handoff/theseus-tech-proposal-20261102.md` 与
   `handoff/attachments/theseus-proposal-20261102/{BoundaryApproxProbe,RasterBoundaryProbe,TileScanProbe}.java`，
   并写 [归档说明](theseus-tech-proposal-ARCHIVED-20261111.md)。
   最终 4 个文件逐一 `raw == local` 校验 `VERIFY PASS`。
   `src/main` **零改动**，不升 `CACHE_SCHEMA_VERSION`。
2. 关闭 PR #2 并留言说明；`git worktree remove` 移走外部 worktree（不用直接删目录，
   避免 `.git/worktrees/` 留 stale 条目）；删本地与远端 `proposal/theseus-main`。

> 提案本身**从未落地**：`MorphBoundaryGeometry.java:334` 仍是 `warpInverse`，
> 全 `src/main` 搜 `Felzenszwalb|distanceTransform|栅格边界` 零命中；
> 文档自述「这是建议，不是要求」且列了「未验证」缺口。归档只读，**不是规格**。

## 六、删除后的复核（网络恢复后二次确认，2026-11-11）

网络一度出现 TLS 握手失败（`git ls-remote` 与 `Invoke-WebRequest` 均不通），
恢复后重新核对远端：

```
$ git ls-remote origin
ac0da83…  HEAD
ac0da83…  refs/heads/main
e019ae9…  refs/pull/1/head
c0b610e…  refs/pull/2/head
9a7c2f4…  refs/tags/backup-before-rewrite
077c6d8…  refs/tags/v0.0.1
```

⇒ 远端只剩 `main` 一个分支、两个历史 PR 的 head ref、两个标签；
**不存在** `proposal/theseus-main`、也**不存在** `backup-refactor-20261109`。

PR 状态：#1 `CLOSED`（2026-11-06，base 被删所致）、#2 `CLOSED`（2026-11-11，本轮关闭）。

本地现状：

```
branches : * main ac0da83 [origin/main]        ← 只剩这一个
tags     : backup-before-rewrite / local-backup-pre-attribution-rewrite / v0.0.1
worktrees: 只有主仓库
refs/remotes: bundle/orig-main -> a13cf5f ; origin/main -> ac0da83
sync     : 0 / 0      worktree: clean
```

被删的本地分支 `backup/preview-refactor-20261109`（`35bf0f7`）经核实
**独有提交 0 个**（`main...分支` = 60/0）、是 `main` 的祖先、无 worktree 检出，
内容完整保留在 `main` 里；同名的本地标签也已删除（远端从未有过该标签）。

## 七、保留项的说明（万一将来要回滚旧链）

| 保留物 | 内容 | 何时用得上 |
|---|---|---|
| `build/git-backup-20261106-rewrite/all-refs.bundle`（82 MB）+ `all-refs-before-cb-strip.bundle`（82.4 MB） | 2026-11-06 两次 `filter-branch`（清除提交尾注 co-author 署名）**之前**的全 refs 打包 | 旧链提交（如 `9a7c2f4`、`b84af0b`）只剩这些 ref 可达，若要回滚历史用它们 |
| 标签 `backup-before-rewrite` → `9a7c2f4`（2026-07-19） | 同一条旧链上的提交 | 同上 |
| 标签 `local-backup-pre-attribution-rewrite` → `b84af0b`（2026-11-06） | 改署名前的 `main` 头 | 同上 |
| `refs/remotes/bundle/orig-main` → `a13cf5f` | 由 bundle 恢复出的旧 `main` 头 | 同上 |

> 这三者的**文件树**都是 `cbf16295…`，与 `main` 里的 `4e959fc` 相同 ⇒ **文件内容没丢**，
> 差别只在提交哈希与历史线（`filter-branch` 改过提交信息）。
> 远端另有 `backup-before-rewrite` 与 `v0.0.1` 两个标签。
> `build/` 被 `.gitignore` 忽略 ⇒ 这些 bundle **不在版本库里**，换机器 clone 就没了。

## 八、纪律（下次再清理时照做）

1. 删除前先做**全量按内容**对拍，不要抽样外推（本轮已因此撤回过一次结论）。
2. Windows checkout 是 CRLF ⇒ 比对 git blob 必须同时算 CRLF→LF 归一化版本。
3. 抢救进仓库时用**扁平短名**（≤160 字符），否则 Windows `git add` 会因路径过长整批失败；
   同时注意历史 `.gitignore` 会被带进抢救包并反向忽略其中的文件。
4. 保留文件内容与删除目录是两件事：先立**远端回退点**（提交并推送），再删。
5. 远端有网络抖动时**先确认状态再下结论**；本轮删本地标签时网络中断，
   事后恢复才补做了远端复核。
6. 涉及 GitHub PR / 分支的操作（关 PR、删远端分支）要走 `gh` / `git push --delete`，
   不要直接删本地目录（会留 stale worktree 条目）。
