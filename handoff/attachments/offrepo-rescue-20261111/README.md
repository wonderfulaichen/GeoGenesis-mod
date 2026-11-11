# offrepo 抢救包（2026-11-11）

> 本目录是**只读抢救**，不是新的开发产物。原始备份
> `D:\Office software\Development Project\GeoGenesis-offrepo-20261103`
> 在本文件建立时**仍完整存在、未被删除**。

## 为什么有这个目录

外部备份 `GeoGenesis-offrepo-20261103`（约 3.19 GB）里，绝大部分
`backup-20261103-pre-handoff/untracked/**` 的大 CSV / f64be 与主仓库同名文件
**SHA256 完全一致**（抽样：`assertions.csv` 84.9 MB、`p.f64be` 14.97 MB），属纯重复。

但下列 30 个文件在主仓库的 **tracked 文件、工作区、乃至 `git log --all` 历史**中
都找不到同名项，一旦删除外部备份就永久丢失：

| 子目录 | 数量 | 内容 |
|---|---:|---|
| `orphan-attachments/` | 21 张 PNG | 2026-10-30 ~ 11-01 的实验证据图：`cell-center-20261030/`、`coastal-20261101/`、`erosion-investigation-20261030/`、`skeleton-overlay-20261030/`、`unified-world-20261101/` |
| `orphan-worktree-probes-20261103/` | 3 个 `.java` | `ProvinceDebugProbe.java` / `ProvinceSampleCheck.java` / `SkipRateProbe.java`（`package com.geogenesis.worldgen.terrain.candidate`） |
| `backup-state-20261103/` | 6 个文件 | 备份当时的 git 状态快照：`HEAD.txt`(`d47de747…`)、`git-status.txt`、`log.txt`、`stash-list.txt`、`tracked-changes.diff`、`untracked-count.txt`(`untracked_copied=593`) |

合计 **30 文件 / 15,009,655 字节（约 14.3 MB）**。

## 拷贝与校验（实测 2026-11-11）

- 用 `Copy-Item -Recurse` 从外部备份原样复制，未改名、未转换、未重新编码。
- 对全部 30 对 (源, 目标) 逐个算 SHA-256：`pairs=30 mismatches=0` → **VERIFY PASS**。
- `git check-ignore` 抽查 PNG / `.java` / `.txt` / `.diff`：均 **not ignored**，能进版本库。
- 目标目录在拷贝前不存在（脚本里有 `if (Test-Path $dst) throw`），无覆盖风险。

## 状态与边界

- ⚠ **HEAD.txt 里的 `d47de7477a4ee0908061ede4ce69b6bba49f73ba` 不在当前主仓库对象库**
  （`git cat-file -t` 实测 fatal）。这几份状态文件是历史快照，不要拿它当现行 HEAD。
- 本抢救包**不代表**外部备份可以整目录删除：`GeoGenesis-preserved-candidate-20261103`
  （BEA0 候选，324.7 MB，`not_yet_in_repo: true`）是另一个独立目录，未纳入本包，建议保留。
- `GeoGenesis-morph-p0` 的 21 个 Java 里有 11 个与主仓库同名但 SHA 不同，也未纳入本包。
- 三个 orphan `.java` 只是**源码**，未编译、未运行、未验证能否在当前代码上编译。

## 后续

原始外部备份**尚未删除**。任何 `git worktree remove` / 目录删除都需用户另行明确授权。
