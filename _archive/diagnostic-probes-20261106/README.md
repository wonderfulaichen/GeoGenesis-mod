# 诊断探针归档：60 个未跟踪 Java 探针（2026-11-06）

> 只读归档。**不参与编译、不在任何 gradle task 里、不进 jar。**

## 归档了什么

三个**从未入库**的诊断包，归档前一直是工作树里的 `??`：

| 包 | 文件数 | 内容 |
|---|---:|---|
| `com.geogenesis.standalone` | 21 | 纯 JDK 独立算子（`FastErosionFilter` / `NoiseFollowingErosion` / `SharedDescentGraph` / `GullyJunction*` / `AdaptiveSkeletonErosion` 等），有 `main`，可脱离 MC 跑 |
| `com.geogenesis.worldgen.process` | 2 | `ProcessRuntimeCheck`（断言型隔离验证）· `ProcessRenderProbe`（逐点 `exactP` 出 CSV） |
| `com.geogenesis.worldgen.terrain.morph` | 37 | 形态/沟壑/栅格实验探针（`FastErosion*` 12 个、`RemainingDrop*`、`TerminalPitProbe`、`SharedDescentProbe`、`CurvedSegment*`、`OldRouteL0Probe` 等） |

目录保留了完整包路径，恢复是机械拷贝：

```text
_archive/diagnostic-probes-20261106/src/
  → forge-1.20.1-47.4.10-mdk/src/
```

## 归档前的引用审计（逐类核查，60/60）

扫描范围：全仓 `*.java / *.gradle / *.py / *.ps1 / *.md / *.txt / *.json / *.toml`，
排除 `build/`、`.git/`、`backups/`、`_archive/`、`run/`、`attachments/`，并排除这三个包自身。

**结论：0 个被跟踪代码引用。** 归档不会破坏 `gradlew build`、
`runWorldgenGate` 或 `verify-base-core.gradle` 的任何源集。

细分类：

| 类别 | 数量 | 说明 |
|---|---:|---|
| 被**跟踪文档**引用 | 2 | `ProcessRuntimeCheck` / `ProcessRenderProbe` ← `handoff/GeoGenesis-20261105-bp.md`（第 38、67-68 行）。**只更新了文档里的路径**，文档本身是历史快照，未改写结论 |
| 只被未跟踪 `.py` / `.openbitfun/plans` 引用 | 46 | 那 43 个 `.py` 与 plans 本轮按用户口径**没动**，仍在原位；它们引用的类已在本归档里 |
| 任何地方都无引用 | 12 | `DescentGraphRepairProbe` · `DescentTerminalAudit` · `DescentTerminalAuditProbe` · `ErosionLatticeCheck` · `NoiseFollowingEvidence` · `OldRouteT0Contributors` · `RemainingRouteApplyProbe` · `RemainingStopEvidence` · `RemainingStopEvidenceProbe` · `SharedDownstreamSearch` · `SharedDownstreamSearchCheck` · `SharedDownstreamSearchProbe` |

> ⚠ **「无引用」≠「死代码」**：这 12 个里大部分是自带 `main` 的**入口探针**
> （`*Probe` / `*Check` 后缀），没有人 `import` 它们是正常的——它们是被命令行跑的。
> 判断死活用「还有没有人跑它」，不是用 grep 引用数。

## 恢复时注意：两个包必须一起恢复

28 个 `morph` 探针 `import` 了 `standalone` 里的类（`FastErosionFilter`、
`NoiseFollowingErosion`、`SharedDescentGraph`、`GullyJunctionConstraint`、
`AdaptiveSkeletonErosion`、`SharedDepthField` 等）。只恢复 `morph/` 会直接编译失败。

`worldgen/process/` 那 2 个是独立的，可单独恢复。配套的未跟踪工具
`forge-.../tools/verify-process-core.gradle` 与 `run-process-verification.py` 仍留在原位，
但**它们指向的源文件已在此归档** —— 要再跑那两个工具，得先按上面的路径恢复。

## 关联档案

- `_archive/old-tile-pipeline/`：旧 tile 管线专用探针（跟踪过，2026-11-06 删除并归档）
- `_archive/ridge-skeleton/`：骨架滤镜本体 + 骨架探针
- `_archive/rune-filter-experiments/`：RUNE 滤镜形态实验件
- `_archive/morph-unused/`：`MorphRoundedMountain`（未接线的 M1 圆化变体）
- `audit/GeoGenesis-旧侵蚀路径清理清单-20261106.md` §7：本轮清理规模与验证