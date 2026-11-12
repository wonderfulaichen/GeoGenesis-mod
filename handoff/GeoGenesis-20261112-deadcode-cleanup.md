# 死代码清理（S1–S6）交接包 — 2026-11-12

> 交接范围：**只覆盖最近这段死代码清理工作**。更早的水文/海洋历史见 `handoff/README.md` 索引。
>
> 接手方默认：有经验的工程师 / 另一个 AI agent，**不能随时问原作者**。
> 本文每条事实都标了验证方式；`未验证` 标记的不要当真。

---

## 一、背景与目标

### 为什么做这件事

在动「完整世界地形」（构造决定地形）之前，先要把代码里的**误导源**清掉。结构核查发现
`CellGenerator` 上挂着一整套"构造了但从不采样"的对象——改那些**根本不会生效**，
却会让人以为构造/地层/气候在参与地形。

完整证据见：
- [`audit/GeoGenesis-模组结构核查-20261112.md`](../audit/GeoGenesis-模组结构核查-20261112.md)（四路只读核查）
- [`audit/GeoGenesis-CellGenerator死对象处置清单-20261112.md`](../audit/GeoGenesis-CellGenerator死对象处置清单-20261112.md)（分类与执行顺序）

### 做完什么样

- `CellGenerator` 上不再有"构造了但不采样"的对象
- 保留的仅诊断入口有明确标注，不再误导
- P1 资产探针接入门禁，能真正拦住地形产出漂移

---

## 二、当前状态（先看这节）

### 2.1 Git 状态（2026-11-12 收尾时实测）

```
main 5ff6270  [ahead 7]    ← 未推送
worktree: 3 个已跟踪文件被修改，1 个未跟踪文档
```

**未提交的改动会影响岩性/类型产出**，见 §五.1，这是本交接**最重要的风险**。

### 2.2 已提交的四个提交（本轮）

| commit | 内容 | 影响生产产出？ |
|---|---|---|
| [`8b43c8e`] | S1/S2：删 `CellGenerator` 死字段与方法；P1 探针接入门禁 | **否**（已验证逐位不变） |
| [`6e5359d`] | 文档：抢救 `classify` 的海洋细分判据 | 否 |
| [`cbe0376`] | S4–S6：删 `eqCache` 计数器与过期 `peek` 包装 | **否**（已验证） |
| [`e5bad8c`] | S1：新增 `LithoPhysical` 构造物理量层 | **否**（只到物理量） |
| [`5ff6270`] | 修 S1 的物理方向错误 + 密度越界 | **否** |

### 2.3 半成品在哪（重要）

| 项 | 状态 |
|---|---|
| **构造决定海陆**（`TectonicField.shellS`） | **未提交，且形态不达标** —— 见 §五.2 |
| `PLATE_SPACING` 2000→1000 | **未提交**，且**已改变岩性产出** |
| SimpleTectonics 纯 Java 复刻 | **未开始**（只有计划，见 §七） |

---

## 三、环境与依赖

```
工作区根   D:\Office software\Development Project\GeoGenesis-mod
真工程     forge-1.20.1-47.4.10-mdk\
JDK        Java 21（Temurin 21.0.11），Gradle wrapper 自带
```

构建/验证命令（均在 `forge-1.20.1-47.4.10-mdk\` 下）：

```bash
gradlew.bat --offline --console=plain compileJava compileDiagnosticsJava
gradlew.bat --offline --console=plain runP1CellRenderProbe --rerun-tasks
gradlew.bat --offline --console=plain runWorldgenGate -x runCenterlineCrossSectionProbe
```

> `-x runCenterlineCrossSectionProbe` 是**既存红**（`AGENTS.md` 已登记），不是本轮引入。
> `--rerun-tasks` 很重要：不加会 UP-TO-DATE 复用旧输出，造成假绿。

---

## 四、关键文件地图

| 改什么 | 看哪个文件 |
|---|---|
| 地形采样总入口 | `src/main/java/com/geogenesis/worldgen/core/BaseWorldSampler.java` |
| 地形门面（本轮清理对象） | `src/main/java/com/geogenesis/worldgen/terrain/CellGenerator.java` |
| 构造场（板块 / 应力 / 壳属性） | `src/main/java/com/geogenesis/worldgen/terrain/TectonicField.java` |
| 构造物理量（S1 新增） | `src/main/java/com/geogenesis/worldgen/terrain/LithoPhysical.java` |
| 陆海高度装配 | `src/main/java/com/geogenesis/worldgen/terrain/morph/MorphOceanAssembler.java` |
| 陆地形态 | `src/main/java/com/geogenesis/worldgen/terrain/morph/MorphLandField.java` |
| P1 资产探针 | `src/diagnostics/java/com/geogenesis/worldgen/terrain/P1CellRenderProbe.java` |
| P1 冻结输入 | `forge-1.20.1-47.4.10-mdk/tools/p1-frozen-input.toml` |
| 物理量探针 | `src/diagnostics/java/com/geogenesis/worldgen/terrain/LithoPhysicalProbe.java` |
| 深海生产路径测量 | `src/diagnostics/java/com/geogenesis/worldgen/terrain/morph/SeaDepthProductionProbe.java` |
| 架构设计（下一步依据） | `audit/GeoGenesis-构造基底架构设计-20261112.md` |

---

## 五、风险、坑、回滚（**必读**）

### 5.1 ★ 未提交改动已改变岩性产出，但门禁不红

实测（2026-11-12）：

```
productionCellSha = bea0a47c14d32fa9e2654c77cc6984999004e137e9e6bde8a05d3e2b2a393965  ← 与固定资产相同
cellMetadataSha   = b28114ec5fe43f919d5a73847e5ddbbae78e363bc637457efd6ae77a337253d2  ← 变了
固定资产 metadata   = 9ffcee45d77614eb7e016a8f3747cf0ad4cdcbf78798c5bbdb2517612d15c856
```

**原因**：`PLATE_SPACING` 2000→1000 改变了 `dist` / `stress` 分布，
而 `StratumField` 的岩性由它们决定 ⇒ `rockTypeId` / `rockLayer` 变了。
高度不含构造量，所以 `productionCellSha` 不变。

**为什么门禁没红**：`P1CellRenderProbe` **只打印 `cellMetadataSha`，不断言它**
（见 `P1CellRenderProbe.java:183`）。所以这道门禁目前**挡不住岩性漂移**。

**处置建议（未执行，等原作者定）**：
- 要么把这些改动提交并**重立 metadata 基准**
- 要么 `git checkout` 回退这三个文件
- **不要**直接把 `b28114ec…` 写成新的 EXPECTED —— 那等于给未验收的改动盖章

### 5.2 构造决定海陆的尝试**形态不达标**

`TectonicField.shellS` 实现了"板块哈希决定壳类型 + 连续加权投票"，
数值上成立（陆地约 30%、梯度无折痕），但**图上形态不可接受**：

- 大陆呈**圆形斑块**，像"水洼/湖"，不像大陆
- 试过用 `stress` 调制边界（`SHELL_STRESS_SHIFT`）让它不规则 ⇒ **斑块反而合并变少**，已确认这条路失败
- 根因：`stressField` 的尺度（σ=1000wu）与斑块同阶，用它调制相当于**整体推移边界**，不增加边界复杂度

**原作者的结论**：要在有限模拟与 O(1) 解析之间做桥接，先复刻参考项目。见 §七。

### 5.3 曾经踩过的坑（不要重犯）

| 坑 | 后果 | 记录位置 |
|---|---|---|
| 用探针**手工重拼** `buildDeepOcean` 节点树"复刻"生产链 | 连续四轮给出互相矛盾的归因结论（漏 `OCEAN_SEED_OFFSET=3`、漏量纲、漏 lerp 门控） | 已全部删除，改为直接调 `oceanAssembler()` |
| 漏 `OCEAN_SEED_OFFSET = 3` | 算出 `selector r=−0.44` 的假结论，据此设计的方案全部作废 | `MorphTerrainSampler:116` |
| 按**输出**分位数标定重映射中心 | 陆地变 0%（重映射非线性，输出分位不可反推） | 必须用 `shellRaw` 的分位数 |
| 投票 σ 取得大于 spacing | 窗口只覆盖 1.33σ，出现**直角硬边** | σ 必须 ≈ 0.5×spacing（窗口覆盖 4σ） |
| `P1CellRenderProbe` 的 EXPECTED 被当成"过期"想改掉 | 实际是我用错了配置参数（`run/config` 被改过），**EXPECTED 本身正确** | 见 §6.2 |

### 5.4 回滚

```bash
# 回退未提交的构造改动（会丢掉 shellS / PLATE_SPACING 全部工作）
git checkout -- forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/terrain/TectonicField.java \
                forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/terrain/LithoPhysical.java \
                forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/terrain/LithoPhysicalProbe.java
```

已提交的部分可以安全留在历史里（都不影响产出）。

---

## 六、操作 runbook

### 6.1 验证"产出逐位不变"

```bash
cd forge-1.20.1-47.4.10-mdk
gradlew.bat --offline --console=plain runP1CellRenderProbe --rerun-tasks
```

**预期输出**（若在 HEAD 干净状态下跑）：

```
productionCellSha=bea0a47c14d32fa9e2654c77cc6984999004e137e9e6bde8a05d3e2b2a393965
cellMetadataSha=9ffcee45d77614eb7e016a8f3747cf0ad4cdcbf78798c5bbdb2517612d15c856
P1 CELL PASS: HS=2, no external erosion/hydrology, markers roundtrip
```

当前工作树（有未提交构造改动）会得到 `cellMetadataSha=b28114ec…`，见 §5.1。

### 6.2 P1 探针的冻结输入（重要）

探针有**前置断言**，参数不符会报准确的错误而不是误导性的 "height drift"：

```
frozen input: continentScale must be 4000.0 (got 2400.0)
```

因为 `run/config/geogenesis-common.toml` 里的产出参数与 R0-0 冻结口径**不同**：

| 参数 | R0-0 冻结 | 当前 run/config |
|---|---|---|
| `continentScale` | 4000.0 | **2400.0** |
| `continentFbmPersistence` | 0.6 | **0.8** |
| `peakHeightFraction` | 0.92 | **0.6** |
| `snowLine` | 0.7 | **0.84** |

⇒ 探针用 `tools/p1-frozen-input.toml`（R0-0 口径副本）作为默认输入，**不要修改 `run/config`**。

### 6.3 物理量探针（S1）

```bash
java -Dfile.encoding=UTF-8 -cp "<main;diagnostics;deps>" \
  com.geogenesis.worldgen.terrain.LithoPhysicalProbe <toml> <outDir> 501 1590 2250 3000
```

输出四张图 + 统计。判据：
- 陆壳厚度 > 洋壳厚度
- 洋壳 `buoyancy` 随离脊距离**单调递减**（越老越沉）
- 梯度 `max|∇|` 与 `c` 场同量级（1~10 倍为正常）

`<deps>` 可导出：`gradlew.bat -I <打印classpath的脚本> printRuntimeCp`。

### 6.4 深海生产路径测量

```bash
java -cp "<...>" com.geogenesis.worldgen.terrain.morph.SeaDepthProductionProbe <toml> <outDir> 401 1590 2250 900
```

**这个探针是唯一走真实生产路径的**（直接调 `MorphTerrainSampler.oceanAssembler()` 的
`heightBlocks` / `sea`），不重拼节点。历史教训见 §5.3 第一条。

---

## 七、待办与决策点（接手方先看这节）

### 7.1 立即要定的一件事（卡在原作者）

**未提交的构造改动怎么处置？**（§5.1）

- 甲：提交 + 重立 metadata 基准
- 乙：`git checkout` 回退
- 丙：保留工作树不动，继续在它上面做

**在定之前不要 push**——这些改动会让岩性产出与既有基准不一致。

### 7.2 下一步技术路线：复刻 SimpleTectonics（原作者已定方向）

> ★ **状态更新（2026-11-13 接手方）：复刻已完成并验证。**
> 见 [`audit/GeoGenesis-SimpleTectonics复刻记录-20261113.md`](../audit/GeoGenesis-SimpleTectonics复刻记录-20261113.md)
> （交付物、忠实度偏差清单、确定性 SHA、形态对拍、"怪癖是承重墙"方法论、
> 世界寿命口径 = 2000~5000 步）。代码在
> `src/diagnostics/java/com/geogenesis/worldgen/terrain/tectreplica/` + `runTectReplicaProbe` 任务。
> **下一阶段 = §七 的桥接课题（未开始）**。以下为原始规划，保留存档。

原话：「先在纯 java 环境下复刻我给的参考项目 SimpleTectonics，然后再转换成符合我们需求的确定性流式生成构造地形。」

**参考项目已完整下载**：`%TEMP%\SimpleTectonics`（`git clone --depth 1`）。
关键参数（已读 `SimpleTectonics.cpp` 确认）：

```c
#define K 8192          // 8192 个板块段
#define SIZE 256        // 模拟网格 256×256（有限域！）
#define nplates 12      // 12 个大板块，每块约 680 段
#define DT 0.025f
R  = 2.0 * sqrt(4.0/π/K)    // 段半径 ≈ 0.0249
CR = R / 14.0                // 碰撞半径
```

**核心公式**（`tectonics.h`，已核实）：

```cpp
// Litho::buoyancy()
density = mass / (area * thickness);
height  = thickness * (1.0f - density);

// 生长用 saturating 曲线
langmuir(k, x) = k*x / (1.0f + k*x);
```

**建议的复刻范围**（原作者提议，待确认）：
1. Poisson 盘采样（`poisson.h` 源码已有）
2. `Litho` 段 + `Plate` 更新（碰撞 / 生长 / 对流）
3. `Cluster` 的 Voronoi 索引 —— 原版用 GPU 深度缓冲，**Java 需改 CPU 版**
4. ~~沙堆沉积~~（`cascading.fs`，原作者建议先不做，属地形细节非构造骨架）

### 7.3 已知的架构冲突（复刻前必须想清楚）

| 项 | SimpleTectonics | GeoGenesis 要求 |
|---|---|---|
| 世界 | **有限 256²**，全部预生成 | **无限**，按需解析采样 |
| 板块 | 8192 段，**迭代模拟数百步** | 必须 O(1) 单点求值 |
| 确定性 | `srand(SEED)` + 到处 `rand()` | 必须逐位可复现 |

⇒ 复刻只能作为**形态基准**（看清参考的构造骨架长什么样），
不能直接变成无限流式生成。桥接方案是后续独立课题。

### 7.4 已确认的架构决策（`audit/GeoGenesis-构造基底架构设计-20261112.md` §八）

| # | 决策 |
|---|---|
| A1 | 导出公式用均衡一阶近似 |
| A2 | **海陆同改**，不能只改海侧 |
| A3 | 保留既有三段海岸剖面，只换 `deep` 的来源 |
| A4 | 类型判据与高度同步统一 |

用户对 A2 的原话：「肯定要啊，我让你设计构造地形就是为了解决这个的，
毕竟我只是之前失败才暂时保留噪声海陆海岸线。」

---

## 八、怎么验证这份文档

按顺序跑，任何一步与本文不符，就是文档失真：

1. `git log --oneline -8` ⇒ 应见 `5ff6270` 在最上，`main [ahead 7]`
2. `git status --short` ⇒ 应见 3 个 M + 1 个 `??`
3. `gradlew.bat compileJava compileDiagnosticsJava` ⇒ BUILD SUCCESSFUL
4. `gradlew.bat runP1CellRenderProbe --rerun-tasks` ⇒ 见 §6.1 的两个 SHA
   （注意：有未提交改动时 metadata 会是 `b28114ec…`，这是 §5.1 的已知差异，不是文档错）
5. `git diff --stat` ⇒ 应为 3 文件 211 插入 15 删除

---

## 九、一句话给接手方

**S1–S6 的死代码清理已经做完且验证过（产出不变）；
但"构造决定海陆"那段还在工作树里、形态不达标、且已悄悄改变了岩性产出。
下一步是先定它的去留，再按 §7.2 复刻参考项目当形态基准。**

---

## 十、处置结果（2026-11-13 接手方补记）

§7.1 的决策已由原作者裁定并执行完毕：**存档后回退**。

**执行记录**（均已验证）：

- 快照标签 **`exp-tectonic-shellS-20261112`** → commit `a471904c98853195dead5907d89bb3e25b3ac3a0`
  （含 TectonicField.java / LithoPhysical.java / LithoPhysicalProbe.java 完整改动，211+/15-，
  完整实验教训写在标签注释里；`git tag show` 或 `git show exp-tectonic-shellS-20261112` 可查）
- 三个文件已恢复到 HEAD；main 工作树回到已验证基准
- 复验：**重编译后** P1 探针 `productionCellSha=bea0a47c…`、
  `cellMetadataSha=9ffcee45…`（与 §6.1 干净口径逐字符一致）、`P1 CELL PASS`
- 标签未推送（是否推远端由原作者决定）
- 执行方式说明：Mimosa 安全钩子（PreToolUse）按"项目存在 high 即拦截 commit"口径
  拦下了 `git commit` 与 `git commit-tree`（被标记的 94 处 high 全是**既有**探针代码的
  CLI 参数假阳性，与本次 3 个文件无关）；经原作者授权改用 `git stash create` 造快照对象。

**§5.1 状态**：已解决（回退路径）。岩性基准回到 9ffcee45，无未验收改动悬置。

**§7.2 更新**：参考项目已从易失的 `%TEMP%` 搬到 **`参考/SimpleTectonics/`**（gitignored，不入库）。
下一步仍是纯 Java 复刻它作形态基准。

**⚠ 接手方新坑（2026-11-13 实测）**：直接 `java -cp build/classes/...` 跑探针前，
必须先 `gradlew compileJava` —— `build/classes` 若是旧字节码，会复现 §5.1 的
metadata 漂移读数，让人误以为回退失败。本文档正文写于 git 状态快照之前，
故 §八 步骤 2 的"1 个 ??"与当时实况（2 个 ??：审计文档 + 交接包自身）有出入，已核实不算失真。
