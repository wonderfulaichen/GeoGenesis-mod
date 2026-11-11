# 剩余三个外部备份的状态判定（2026-11-11）

> 本文件是删除决策前的证据留痕。**本轮未删除任何目录、未执行 `git worktree remove`**。
> 工具 `backup_audit.py`，逐文件数据在 `audit_*_v3.json`；第一版（未做 CRLF 归一化）
> 的 `audit_theseus.json` 等仅作口径对比保留。

## 工具口径修正（先读）

`full_audit.py` 只按原始字节算 git blob SHA-1。Windows checkout 是 **CRLF**、
git 对象是 **LF**，于是大量 tracked 文本文件被误报成「孤儿」。
`backup_audit.py` 增加：

- 文本文件额外计算 `CRLF→LF` 归一化后的 blob，任一形式命中即算覆盖；
- 支持把额外提交（如远端 `proposal/theseus-main` 的 `c0b610e`）的 **tree** 也纳入覆盖；
- 自检：以主仓库自身为「备份」跑一遍 → `orphan=0`、`AUDIT RESULT: PASS`（`audit_main_self.json`）。

⇒ 下面 v3 数字才是有效口径。

## 三个目录

### 1. `GeoGenesis-mod-theseus-main` — 内容可重建，但业务上仍现行

| 项 | 值 |
|---|---|
| 大小 / 文件 | 134.6 MB / 2099 |
| 身份 | 主仓库 linked worktree，`.git` 是文件（105 字节指针） |
| v3 对拍（含 `c0b610e` tree） | `both=1832  git_objects=264  workspace_only=2  orphan=1` |
| 唯一孤儿 | `.git` 指针文件本身，不是内容 |
| 远端 | `origin/proposal/theseus-main` = `c0b610e`，SHA 与本地一致 |

**内容零丢失**，删掉目录后可从远端完全重建。但业务上不能当「废弃」：

- GitHub PR **#2「迁移 Theseus 提案至 main」仍是 open draft**；PR #1 已 closed（非合并）。
- 提案内容（边界层占 89% 耗时、改用正向 warp 栅格标签 + 欧氏距离变换）
  **从未落地**：`MorphBoundaryGeometry.java:334` 仍有 `warpInverse`，`:847` 仍调用它；
  全 `src/main` 搜 `Felzenszwalb|distanceTransform|栅格边界` 零命中。
- 4 个独有文件（提案 + 3 个探针）只在 `proposal/theseus-main` 上，`main` 里没有。

**处置建议**：保留远端分支不动。若嫌 134.6 MB 占空间，用
`git worktree remove <path>` 删本地工作副本（**不要**直接删目录，会留 stale admin 条目），
需要时 `git worktree add` 重建。是否动 draft PR 的工作副本，需用户确认。

### 2. `GeoGenesis-morph-p0` — 已废弃，可删

| 项 | 值 |
|---|---|
| 大小 / 文件 | 2.7 MB / 756 |
| v3 对拍 | `both=191  git_objects=3  workspace_only=412  orphan=150`（791,854 字节） |
| 150 个孤儿 | 134 个编译产物 `.class`（0.68 MB）+ 16 个非运行文件（74,550 字节） |
| 非运行孤儿 | `SOURCES.md`、`build.ps1`、3 个 `tools/parity/java/**`、旧版 `MorphTerrainSampler.java`、`MORPH-PACKAGE-NOTICE.txt`、8 份 reports/args |

**废弃证据**：

- `SOURCES.md:1-4` 自述「拟生产包，**尚未**接入 CellGenerator / GeoGenesisGenerator」。
- 该目标已由提交 `a5d00d2`（「备份已认可P1与纯Java基础核心分离」）达成：
  `BaseWorldSampler.java:43` `new MorphTerrainSampler(...)`，
  `CellGenerator.java:244` 与 `ProcessContext.java:72` 接进生产。
- 21 个 Java 与主仓库 `worldgen/terrain/morph/` 逐文件比对：10 个逐字节相同、
  11 个是**被后续提交删掉的旧写法**（例：`synchronized sample` 被 `0639d7c` 移除），
  主仓库现为 23 个文件（多 `MorphCellAdapter` / `MorphSingleShaper`）。
- 3 个 `tools/parity` Java 未入库，但功能已由 `BaseSampleCacheProbe` /
  `P1CellRenderProbe` 以 `new MorphTerrainSampler(...)` 作真值代替。

⇒ **可删**。若想保守，把那 16 个非运行文件（74 KB）并入本抢救包再删。

### 3. `GeoGenesis-preserved-candidate-20261103` — 候选已进生产，但目录仍是唯一副本，**需用户裁定**

| 项 | 值 |
|---|---|
| 大小 / 文件 | 324.7 MB / 3720 |
| v3 对拍 | `both=1576  git_objects=18  workspace_only=1370  orphan=756` |
| 孤儿字节 | 232,288,647（221 MiB）：16 个 `.f64be` 209.74 MB、416 个 `.class` 2.28 MB、其余非运行类 324 个 / 9.97 MB |
| 非运行孤儿举例 | `manifest.json`(301 KB)、`PROVENANCE.json`、`original/compare/*.png`、`far-grid.csv` / `seams.csv` 各 4~5 份 |

**已被取代的部分**：`manifest.json` 的 BEA0 高度 SHA `bea0a47c…` 已被
`audit/GeoGenesis-R0-0-baseline-20261105.md:20-24` 认定为「**P1 固定资产 / 原认可资产**」，
`MorphLandField.java` 里 `chunkStart/chunkEnd/buildChunks/chunkOutsideBand` 已进生产。
即 `not_yet_in_repo: true` 这个前提**已经失效**。

**仍未取代的部分**：`src/candidate/DoubleFullMorphLand.java`、`DoubleBoundaryGeometry.java`、
`DoubleMorphEngine.java` 等 float/double 参考臂在**全仓 git 历史、主仓库、morph-p0 中零命中**；
`PROVENANCE.json` 记录的源 HEAD `06343b5` 已不在 main 祖先链，原分支
`feat/hydrology-target` 已删除。⇒ 若要复现「BEA0 与 pristine arm 逐位一致」的验证链，
这个目录是**唯一副本**。`UNSUPPORTED-AND-GAPS.json` 还说明大 PNG / 部分 f64be 只留了
SHA-256 + 重生成脚本，本身就不完整。

⇒ **不能自动判定可删**。用户若确认「不再需要复现参考臂」，可删；
若要保真，最小抢救集是 `src/candidate/**` + `repro/**` 脚本 + `manifest/PROVENANCE/UNSUPPORTED`
+ `verified/` 的校验结果（约 10 MB，不含 .f64be/.class）。

## 之前的处置

`GeoGenesis-offrepo-20261103` 已于上一轮按用户裁定删除（3.19 GB），
其唯一证据在本目录 `orphan-attachments/` + `backup-state-20261103/` + `evidence-flat/`。
本报告只覆盖其余三个。

## 纪律

- 删除前必须重新确认远端状态（PR / 分支 / worktree 登记），本文件是快照会过期。
- `git worktree remove` 与目录删除都需用户**逐项点名**后再执行。
