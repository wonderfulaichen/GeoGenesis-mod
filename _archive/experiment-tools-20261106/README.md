# 实验工具归档：`tools/` 下 5 个未跟踪脚本（2026-11-06）

> 只读归档。要到原位置才能跑（见末节「恢复」）。

原位置：`forge-1.20.1-47.4.10-mdk/tools/`

## 逐件结论

| 文件 | 行数 | 性质 | 数据来源 / 目标 | 判定 |
|---|---:|---|---|---|
| `render-process-comparison.py` | 40 | 纯 numpy + matplotlib | 读 `ProcessRenderProbe` 导出的 8 列 CSV → `-comparison.png` / `-profiles.png` / 3 份剖面 CSV | 归档：生成器已归档 |
| `run-process-verification.py` | 45 | 编排器（javac + java 子进程） | 编译并运行 `ProcessRuntimeCheck`、`ProcessRenderProbe`（**均已归档**），另加 `build/r0b-20261105/ParticleCoreCheck.java`（**仓库里没有这个文件**） | 归档：两处断 |
| `verify-process-core.gradle` | 20 | `-I` 注入式 gradle | 把 `src/main/.../worldgen/process/*.java` 并入 `compileBaseCore`，捕获 classpath / 源清单到 `build/bp-verification` | 归档：见下 |
| `render-ridge-placement.py` | 98 | 纯 numpy + PIL | 读 `RidgePlacementProbe` 的 run 目录（探针已归档到 `_archive/ridge-skeleton/diagnostics/`） | 归档 |
| `render-single-shaper.py` | 445 | 纯 numpy + PIL（自带 `--selftest`） | 读 `SingleShaperProbe` 的 run 目录（同上，已归档） | 归档 |

**0 个删除。**

## 为什么 `verify-process-core.gradle` 也归档

它**不是独立工具**。`captureProcessEnvironment` 写出的
`core-classpath.txt` / `compile-classpath.txt` / `main-sources.txt`，
唯一的消费者就是 `run-process-verification.py`（第 5、27、29 行直接读这三个文件）。
编排器走了，捕获端留着就是死件；两者是一对，必须同去同留。

它本身对 `src/main` 仍是有效的（`worldgen/process` 没被删），所以如果哪天要把
B/P 核心的编译 provenance 再抓一次，恢复这两个文件即可。

## 为什么 `run-process-verification.py` 不能只是"移走"

它有两处硬断，不是"暂时没数据"：

1. `src/diagnostics/java/com/geogenesis/worldgen/process/ProcessRuntimeCheck.java`
   与 `ProcessRenderProbe.java` —— 2026-11-06 已归档；
2. `ROOT / 'build/r0b-20261105/ParticleCoreCheck.java'` —— 这个文件**从来没有入过库**，
   只存在于本机被 `.gitignore` 忽略的 `build/` 下。干净 clone 上必然 `FileNotFoundError`。

第 2 条是既有缺陷，不是本次归档造成的。改它等于改写实验记录，所以不修、只归档。

## 文档引用（已同步改指）

| 文件 | 被谁引用 | 处理 |
|---|---|---|
| `render-process-comparison.py` · `run-process-verification.py` · `verify-process-core.gradle` | `handoff/GeoGenesis-20261105-bp.md`（文件表，第 39 行） | 该行后加了一行说明指向本目录 |
| `render-ridge-placement.py` | `audit/GeoGenesis-ridge-ab-small-20261105.md`（第 9 行正文、第 74 行命令） | 第 74 行命令路径改指本目录 |
| `render-single-shaper.py` | **无**（全仓零引用、零文档引用） | —— |

`render-single-shaper.py` 是这 5 个里唯一没有任何引用的，但它 445 行、
是已归档 `SingleShaperProbe` 的**唯一出图侧**。删掉它，那个探针的归档就只剩裸数据，
所以归档而非删除。

## 恢复

两个脚本用 `Path(__file__).resolve().parents[N]` 定位仓库根：

```
render-ridge-placement.py   parents[2]   # 期望 <repo>/forge-1.20.1-47.4.10-mdk/tools/
render-single-shaper.py     parents[2]   # 同上
run-process-verification.py parents[1]   # 期望 <repo>/forge-1.20.1-47.4.10-mdk/
```

归档后的目录深度与原来不同，**就地跑会算错根**。恢复 = 把整个 `tools/` 拷回
`forge-1.20.1-47.4.10-mdk/tools/`。

另外 `render-ridge-placement.py` 还依赖仓库根 `tools/cmp-render-versions.py`（**仍跟踪**），
恢复后无需额外处理。

## 关联档案

- `_archive/diagnostic-probes-20261106/`：`ProcessRuntimeCheck` / `ProcessRenderProbe` 等 60 个探针
- `_archive/diagnostic-scripts-20261106/`：42 个 `.py` 驱动与依赖清单
- `_archive/ridge-skeleton/`：`RidgePlacementProbe` / `SingleShaperProbe`（本目录两个渲染器的数据来源）