# 实验脚本归档：42 个 Python 驱动 + 1 份依赖清单（2026-11-06）

> 只读归档。不在构建里、不被任何 task 引用。

## 为什么在这

这 42 个脚本是 `_archive/diagnostic-probes-20261106/` 那 60 个 Java 探针的**驱动与出图侧**。
探针已归档，脚本留在 `src/diagnostics/` 只会让人以为它们还能直接跑。

平铺保留在 `src/diagnostics/` 下，是为了**恢复语义正确**：
其中多个脚本用「同目录兄弟文件」定位彼此（`SCRIPT.with_name('old-route-l0.py')`、
`Path(__file__).with_name(...)`）与兄弟 CSV（`requirements-remaining-drop.txt`），
按主题拆子目录会让恢复后互相找不到。

```text
_archive/diagnostic-scripts-20261106/src/diagnostics/
  → 整体拷回 forge-1.20.1-47.4.10-mdk/src/diagnostics/
```

## 判定依据（逐脚本核查）

三道判据，全仓扫描后逐条落实：

| 判据 | 结果 |
|---|---|
| 是否引用**仍在 `src/` 生产/诊断集里**的类 | **0 个**（这是「能安全移走」的硬条件） |
| 是否驱动**已归档**的探针 | **31 个** |
| 是否为那 31 个所依赖的渲染器/求解器/自检 | 11 个（其中 7 个完全不含 Java 调用，是读 CSV / 绘图 / 纯合成的 Python） |
| 是否被**提交过的文档**按文件名引用 | **0 个** |

> ⚠ 上一版统计里那 44 个「被文档引用」是**误报**：命中全部来自
> `handoff/attachments/bp-20261105/{start-status,final-status,start-hashes,
> documentation-start-status}.txt` —— 那些是 `git status` 快照，里面自然列着每个
> 未跟踪文件名。排除快照后，正文文档对这批脚本的引用数是 0。

## 两簇（互相有依赖，恢复时整目录拷回即可）

**A. 快侵蚀 / 骨架算子 / 曲线沟壑（25 个）**
`fast-erosion-*.py`（13）· `exp-j-deriv-source` `exp-k-sigma` · `stage1-octave` `stage2-plateau` ·
`gully-junction-c0` · `noise-following-subtractive` · `curved-segment-erosion` `curved-window-seam` ·
`decomposed-carve` · `full-network-valley` · `local-soft-valley` · `strong-window-erosion`

**B. drop / pit / 旧路线 / 共享下降（17 个归档 + `old-route-l0.py` 留活，见下节）**
`remaining-drop-{debug,field,render,solver,solver-check}` · `remaining-stop-render` ·
`terminal-depth-solver{,-check}` · `terminal-pit-{evidence,run,verify}` ·
`old-route-{terminal-t0,upstream-audit}` · `valley-slope-envelope` ·
`shared-descent-{render,stage-a}` · `descent-fragment-render`

> 25 + 17 = **42**。另加 `requirements-remaining-drop.txt`（B 簇的依赖清单，被
> `remaining-drop-field.py` 按兄弟路径读取），一并归档。

> A、B 之间有依赖（`remaining-drop-field.py` 用 A 里的 `noise-following-subtractive.py`），
> 所以两簇必须一起恢复。

## 特意**没有**移走的东西

| 文件 | 为什么留在 `src/diagnostics/` |
|---|---|
| `particle-preeorder.py` … `-v5.py`（5 个） | **已入库**（`fadf368`）；且 `-v3` 被 `handoff/GeoGenesis-20261104-preeorder.md` §6 的 runbook 直接引用（`python -B src/diagnostics/particle-preeorder-v3.py`）。它们与归档的预侵蚀线不同——是**粒子侵蚀**那条链 |
| `old-route-l0.py` | 上面那 5 个里的 `particle-preeorder-v2.py` **硬依赖**它（`load_module('old_route_readonly', ROOT/'src/diagnostics/old-route-l0.py')`）。它此前一直未入库 ⇒ 已提交的 runbook 在干净 clone 上跑不起来。本次一并**补提交**，不是归档 |

归档的 `old-route-terminal-t0.py` / `valley-slope-envelope.py` 也 `import` 它，但走兄弟路径
（`with_name('old-route-l0.py')`）⇒ 恢复后同目录即成立，因为 `old-route-l0.py` 现在**已经在** `src/diagnostics/` 里。

## 「无用」与「已证伪」的区别

这 42 个里有几个的输出是**已记录的负面结果**的出处，例如
`local-soft-valley.py` → `build/local-soft-valley-visual-20261104/`，
正是 `handoff/GeoGenesis-20261104-preeorder.md` §3.5 表格里「高斯柔化旧下切场：只变浅不改形状」那一行。
脚本本身没被正文按名引用，但**删掉它，那条被记录的否定结论就没有复现手段了**。
所以 42 个全部归档、**0 个删除** —— 归档已经把 `src/diagnostics/` 清空到只剩 6 个在役脚本，
删除只损失证据。

## 关联档案

- `_archive/diagnostic-probes-20261106/`：这 42 个脚本驱动的 60 个 Java 探针
- `_archive/old-tile-pipeline/` · `_archive/ridge-skeleton/` · `_archive/rune-filter-experiments/` · `_archive/morph-unused/`
- `audit/GeoGenesis-旧侵蚀路径清理清单-20261106.md` §7：本轮清理规模与验证