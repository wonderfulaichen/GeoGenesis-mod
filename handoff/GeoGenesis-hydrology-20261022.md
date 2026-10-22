# GeoGenesis 水文骨架 —— 交接文档（2026-10-22）

> 接手方假设：有经验的 Java/Minecraft 模组工程师，**不能**随时问原作者。
> 本文只写**核实过**的事实；未核实的明确标 `未验证`。
> 本轮的完整技术分析见 [`.openbitfun/plans/river-skeleton-direction-field.plan.md`](../.openbitfun/plans/river-skeleton-direction-field.plan.md)。

---

## 1. 背景与目标

**为什么做这件事**：项目的河流由自定义 `ChunkGenerator`（`GeoGenesisGenerator`）+ `DeterministicSkeleton`（确定性骨架）生成。用户长期反馈「河流形态不像自然河流」。

**本轮的目标（用户原话）**：
1. 以「物理粒子动线」为**参照**，让骨架「大致相似」（不要求完全一致）。
2. **汇聚由地形决定**，不许人为牵引。
3. 坡上平行是正常的（沟壑本身平行）。
4. 改变方向必须有物理理由（惯性等机制）。
5. 不要人为禁环（牛轭湖是真实的）。
6. **河流必须走完水文全周期**：入湖/入海才算正常结束。

**结果**：参照物理路线的路线**已结束**（用户：「不需要再参考物理路线了」）。当前目标是**河流完整性**。

---

## 2. 当前状态（截至 2026-10-22 05:15）

### 2.1 已经能跑

生产图命令（**约 251 秒**，8281 chunks，1401×1401）：

```
gradlew runProductionViewProbe
```
输出：`build/hydro-production/prod-<epochMs>/production.png`

最新一张：`build/hydro-production/prod-1792617702893/production.png`

### 2.2 当前生效的默认值

| 项 | 值 | 位置 |
|---|---|---|
| `modelStep` | **2** | `ModelHydrologyProvider.STEP` |
| `valleyMin` | **false** | `WindowOutlets.VALLEY_MIN` |
| `strictD8` | true（默认） | `DeterministicSkeleton.STRICT_D8` |
| `vectorTrace` | **true**（默认） | `DeterministicSkeleton.VECTOR_TRACE` |
| `riverSpill` | **true**（默认） | `DeterministicSkeleton.RIVER_SPILL` |
| `continuousAnchor` | false | `DeterministicSkeleton.CONTINUOUS_ANCHOR` |
| `inertialD8` / `gradientDir` | false | 同上 |

### 2.3 半成品 / 未验证（**重要**）

| 项 | 状态 |
|---|---|
| **让落点连续（非 8 方向）** | ❌ **尚未实现**。我尝试过用 `contPath`（向量积分坐标）当锚点，**失败**：格索引 `k` 按 D8 跳、连续坐标独立累加，两者无同步 ⇒ 脱钩 ⇒ 生产图变成**满屏交叉直线的蜘蛛网**。已全部回退。正确做法见 §7。 |
| `vectorTraceNext` 的实际增益 | `未验证`。它现在是默认开启，但我没做过「开/关」的干净 A/B（因为同时改了别的）。 |
| `VALLEY_MIN=false` 是否导致「河翻山脊」 | `未验证`。原注释说四角取最小是为了「河贴谷走」。 |
| `spillCell` 恢复后的爬坡量 | `未验证`。删除它时格层爬升 7.6%，恢复后未重测。 |

### 2.4 已验证的数字

**生产日志（最新，`build/prod-restore.log`）**：
```
HYDRO-SKELETON traces=129 sources=371 droppedBySpacing=258 outletStarts=61
  ends: lake=54 sea=0 join=71 unresolved=0 DEAD_END=4 exitSeeds=1
```

`DEAD_END=4`（此前删除 `spillCell` 时是 52）⇒ 短河问题已解决。

**历史对比（`route-fit3.py`，已不适用当前目标，仅存档）**：

| 指标 | STEP=4 | STEP=2 | STEP=1 |
|---|---|---|---|
| 骨架→物理中位距离 | 10.5 wu | 0.6 wu | — |
| 贴合率中位 | 41.9% | 97.8% | 100.0% |
| 方向夹角中位 | — | 3.4° | 3.5° |
| >60° 夹角 | — | 18.5% | 15.5% |
| 生产图耗时 | 290 s | 300 s | 568 s |

---

## 3. 环境与依赖

| 项 | 值 |
|---|---|
| 工作区 | `D:\Office software\Development Project\GeoGenesis-mod`（**不是** mdk 子目录） |
| Gradle 工程 | `forge-1.20.1-47.4.10-mdk/` |
| JDK | **Java 21**（必须，装在 D 盘） |
| MC / Forge | 1.20.1 / 47.4.10 |
| 诊断工程 | `src/diagnostics`（252 个 `.java`，独立 main，不启动 MC） |

**跑诊断探针的固定套路**（本轮一直用）：

```powershell
cd forge-1.20.1-47.4.10-mdk
./gradlew.bat compileJava compileDiagnosticsJava --offline --console=plain
# classpath 文件是本项目预先导出的：
$cp=[IO.File]::ReadAllText((Resolve-Path 'build/local-flow-reassessment/classpath.txt')).Trim()
java -Xmx12g '-Dfile.encoding=UTF-8' -cp $cp com.geogenesis.worldgen.hydrology.model.<ProbeName> <seed> <cx> <cz>
```
标准参数：`seed=9139912035078620160  cx=-140  cz=137`

**⚠ 中文输出在 PowerShell 会用 GBK 破坏**。要看中文就重定向到文件再 `Get-Content -Encoding UTF8`；或在探针里改用英文标签。

---

## 4. 关键文件地图

| 改什么 | 看哪个文件 |
|---|---|
| 骨架追踪（方向、终止、拓扑） | `worldgen/hydrology/model/DeterministicSkeleton.java`（2300+ 行，核心） |
| 格距 `STEP`、region 网格、参数派生 | `worldgen/hydrology/model/ModelHydrologyProvider.java` |
| `bed` 的采样方式（`VALLEY_MIN`） | `worldgen/hydrology/model/WindowOutlets.java` |
| 湖/海判定、水量平衡 | `worldgen/hydrology/model/LakeWaterBalance.java` |
| 地形与侵蚀（`erodedHeight`） | `worldgen/terrain/CellGenerator.java` |
| 缓存版本（改产出必升） | `client/preview/PreviewDisplay.java` 的 `CACHE_SCHEMA_VERSION`（当前 **100**） |
| 生产图渲染 | `diagnostics/.../model/ProductionViewProbe.java` |
| 逐条路线度量（历史） | `forge-1.20.1-47.4.10-mdk/tools/route-fit3.py` |

### 4.1 `DeterministicSkeleton` 内的重要符号

| 符号 | 作用 |
|---|---|
| `traceOne()` | 单条链的追踪主循环（**要改落点必须动这里**） |
| `strictD8Next()` | 8 邻取严格最低（旧行为） |
| `vectorTraceNext()` | **当前默认**：8 方向加权向量求和 → `StepResult{cell, dirX, dirZ}` |
| `spillCell()` | 洼地 priority-flood 找鞍部（`RIVER_SPILL` 控制） |
| `shape()` | 几何层：格心锚点 + CR 重采样 + meander |
| `Trace.contPath` | 连续路径（**当前未被 `shape()` 使用**，回退后遗留） |

---

## 5. 操作 runbook

### 5.1 出生产图（最常用）

```powershell
cd forge-1.20.1-47.4.10-mdk
./gradlew.bat runProductionViewProbe
```
或直接 java（约 250 s）：
```powershell
$cp=[IO.File]::ReadAllText((Resolve-Path 'build/local-flow-reassessment/classpath.txt')).Trim()
java -Xmx12g '-Dfile.encoding=UTF-8' -cp $cp com.geogenesis.worldgen.hydrology.model.ProductionViewProbe 9139912035078620160 -140 137
```
**预期输出末行**：`FILE=...\build\hydro-production\prod-<epoch>\production.png`

### 5.2 验证「改动不影响产出」（回归底线）

```powershell
python tools/verify-readonly-invariance.py <基线.png> <新.png>
```
**预期**：`different pixels = 0` + `[PASS]`。非 0 就说明改动改变了产出（这未必是错，但必须知道）。

### 5.3 常见故障

| 现象 | 原因 | 处理 |
|---|---|---|
| 生产图是全蓝/空白 | 缓存复用旧数据 | 升 `CACHE_SCHEMA_VERSION` |
| 探针 `Can't read input file` | 没传 `-Dxx.source=` | 见各探针的 `System.getProperty` 默认值 |
| 中文乱码 | PowerShell GBK | 重定向到文件后用 `Get-Content -Encoding UTF8` |
| Gradle 报找不到 JDK | 没用 Java 21 | 检查 `JAVA_HOME` |

---

## 6. 风险、坑、回滚

### 6.1 不可逆操作

**没有**。全部改动都是源码 + 运行时开关。

### 6.2 本轮踩过的坑（**别重复**）

| 坑 | 教训 |
|---|---|
| **坐标换算错了 4 次** | `production.png` 是 `setRGB(x,z)` ⇒ numpy 下标 `[row=z,col=x]`；`bed[]` 是**窗口格索引**，必须用 `windowFrameForProbe()` 的 origin/step，**不许自己反推** |
| **用叠加图做度量** | 橙色线会盖住底下的河格 ⇒ 距离统计全 0（假绿）。必须用**独立掩码** |
| **距离变换实现错误** | 只沿行 accumulate ⇒ 同行互相传染为 0。用 `scipy.ndimage.distance_transform_edt`，并做**平移对照**（平移 30 块后覆盖率必须崩） |
| **配对错河流** | 「起点最近的骨架」会配错。必须按生产 `shaped.get(i)` ↔ `traces.get(i)` 的**同一下标**配对 |
| **`Params.defaults()` 自建骨架** | 漏掉 REGION/HALO/跨区接力 ⇒ 画出来不是生产骨架。必须读生产接口 |
| **`contPath` 当锚点** | 与格索引脱钩 ⇒ 蜘蛛网。见 §7 |
| **删 `spillCell`** | 真洼地 6398 个、lakeMask 认成湖的 **0 个** ⇒ 河全断 ⇒ 短河遍地。已恢复 |
| **加「防回绕」约束** | 违反用户口径（牛轭湖需要弯回自己身边）。已撤销 |

### 6.3 回滚

所有新机制都有 `-D` 开关，默认值见 §2.2。回退整体：
```powershell
git checkout -- forge-1.20.1-47.4.10-mdk/src/main/java
```

---

## 7. 待办与决策点

### 7.1 ★ 最高优先级：让落点连续（用户明确要求，未实现）

**用户原话**：
> 「骨架落地时还是 D8 落点？我让你做多条件物理运行向量就是让你落地不再是那八个方向，
> 而是拿那个算出来的结果向量的方向。并且我也说过路线应该像柏林噪声的生成原理那样，
> 进行向量运动线。」

**必须重写 `traceOne()` 的主循环**，改成**位置驱动格**：

```
pos += dir * step                    // 位置先走（连续浮点）
int cell = round(pos) 转格索引        // 格由位置决定
if (cell 不可走/已覆盖) 沿 dir 微调    // 格只作为约束
```

**为什么不能在现有循环里打补丁**：现有循环是 `k = 下一个格索引`，`curX/curZ` 是附加变量 —— 两者没有同步机制。我试过，几百步后脱钩 ⇒ 蜘蛛网。

**卡在谁身上**：需要用户确认是否接受这个改动量（重写 200+ 行的主循环）。

### 7.2 河流完整性（用户当前主诉）

用户：「现在非常多短河并且是非正常结束。不是按我说的水文全周期的。」

**已修**：恢复 `spillCell` 后 `DEAD_END` 从 52 降到 4。

**仍需确认**：
- 这些 `DEAD_END=4` 是什么地形？（`spillCell` 抬升超 `MAX_SPILL_RISE=12` 的封闭盆地）
- 平原上那 20 条**笔直竖线**是否可接受（用户尚未回答）

### 7.3 其他

| 项 | 卡在谁 |
|---|---|
| `HYDROLOGY.md` 有 92 行 markdown 格式化改动（无实质内容），是否要提交 | 用户 |
| `Trace.contPath` 字段现在无人使用（回退遗留），是否删除 | 用户 |
| `continuousAnchor` / `inertialD8` / `gradientDir` 三个失败开关的代码是否删除 | 用户 |

---

## 8. 本轮的诊断工具（保留）

| 工具 | 用途 | 状态 |
|---|---|---|
| `RootedParticleProbe.java` | 从生产源头放粒子，出 A/B/C 三图 + `routes.csv` | 可用（但用户已**不再需要**物理对照） |
| `route-fit3.py` | 逐条路线贴合度 + 方向夹角 | 可用（历史） |
| `diff-rooted.py` | 双向距离（含平移对照） | 可用 |
| `verify-readonly-invariance.py` | 埋点/开关是否改变产出 | **常用** |
| `verify-source-alignment.py` | 源头是否落在生产河格上 | 可用 |
| `verify-node-quantization.py` | 节点是否被量化到网格 | 可用 |
| `DeadEndAuditProbe.java` | 洼地 vs lakeMask 的统计 | 可用 |

---

## 9. 一句话交接

**河网现在能正常出图（251 s）、`DEAD_END` 已降到 4、但落点仍是 8 方向的格心。**
**下一件事是重写 `traceOne()` 让位置驱动格 —— 这是用户最明确、也是唯一没做到的诉求。**
