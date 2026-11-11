# 最后两个外部备份的最小抢救集（2026-11-11）

> 用户裁定：「删 morph-p0（推荐）」「GeoGenesis-preserved-candidate-20261103 …
> 我记得这个也是废弃的，应该可以删除。」
>
> 本文件与 `rescue_final.py` 是删除前的回退点。**抢救完成、推送后才执行删除。**

## 一、抢救口径

`rescue_final.py`（只读外部备份）从两份 v3 审计 JSON 出发：

| 包 | 选取规则 | 结果 |
|---|---|---|
| `morph-p0-rescue/` | `audit_morph_p0_v3.json` 中**全部非运行孤儿**（排除 `.class` / `.f64be`） | **16 文件 / 74,550 字节** |
| `preserved-rescue/` | `preserved` 目录下的源码 / 构建与验证脚本 / manifest / licenses / verified 校验结果；排除 `.f64be`、`.class`、重复 CSV、>1MB PNG 与大日志 | **662 文件 / 11,283,087 字节**（约 10.8 MB） |

`preserved` 原始候选集有 1496 个文件、四份近似重复的源码树
（`original/**`、`src/**`、`verified/src/**`、`repro/**`）。按**内容**去重后
只存 662 个物理文件，但 `preserved-rescue-manifest.json` 的
`all_original_paths` 记录了全部 **1496 条原始路径**（合计映射 1496 条，无遗漏）。

命名采用扁平短名（最长路径 155 字符），避免上一轮 262 字符触发的
Windows `Filename too long`；同时避免把历史 `.gitignore` 带进抢救包。

## 二、验证（实测）

```
morph-p0 rescued: 16 files, 74550 bytes
preserved rescued: 662 files, 11283087 bytes
re-verify against originals: PASS
max path length: 155
```

`rescue_final.py` 对每一个文件做**三方** SHA-256：外部备份原件 → 扁平副本 →
manifest 记录值；并再次逐对与原始备份复核。两个 manifest 分别记录
`flat` / `original` / `size` / `sha256`，preserved 侧另记 `all_original_paths`。

关键内容存在性抽查（全在 manifest 中）：

| 关键项 | 命中路径数 |
|---|---:|
| `src/candidate/**`（Double* 参考臂所在） | 36 |
| `repro/build.ps1` / `repro/verify.ps1` | 1 / 1 |
| `PROVENANCE.json` / `UNSUPPORTED-AND-GAPS.json` / `manifest.json` | 1 / 1 / 2 |
| `DoubleFullMorphLand` / `DoubleBoundaryGeometry` / `DoubleMorphEngine` / `DoubleTreeFixed` | 6 / 5 / 3 / 4 |
| `verified/**` 校验结果 | 228 |

## 三、明确**未**抢救的内容（删除后不可恢复）

| 类别 | 量 | 说明 |
|---|---|---|
| `.f64be` 阶段数组 | 16 个 / 209.74 MB | 1401² double 派生数据；`UNSUPPORTED-AND-GAPS.json` 自述可由 `repro/verify.ps1` 重生成并按 SHA-256 比对 |
| `.class` 编译产物 | 416+134 个 | 可由源码重编译 |
| 大 CSV / PNG / log | 约 10 MB | 与 `.f64be` 同源的渲染/统计产物；`full_audit.json`、`audit_preserved_v3.json` 保留每个孤儿的路径与 SHA-256 供事后核对 |
| `workspace_only` 内容 | morph-p0 412 / preserved 1370 | 主仓库工作区当下仍有逐字节相同内容；不在版本库，若将来清理工作区会变成孤儿 |

> `preserved` 的 `UNSUPPORTED-AND-GAPS.json` 原本就声明「部分大 PNG 与 f64be
> 只留 SHA-256 + 重生成脚本」，故本抢救包**不改变其自述的完整性级别**。

## 四、删除范围

按用户点名，删除**且只删除**：

- `D:\Office software\Development Project\GeoGenesis-morph-p0`
- `D:\Office software\Development Project\GeoGenesis-preserved-candidate-20261103`

`GeoGenesis-mod-theseus-main` **不动**（open draft PR #2、远端 proposal 分支保留）。
`GeoGenesis-offrepo-20261103` 已于上一轮删除。

删除是不可逆的；工具提示工作区外目录无快照回滚能力，可回退的只有
已推送到 `origin/main` 的抢救包与审计 JSON。
