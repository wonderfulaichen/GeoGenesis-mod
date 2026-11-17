# morph 链「重复计算优化」— 否定结论（2026-11-12）

> **本文是"不要做"的记录。** 两项看起来很有性价比的优化，实测收益为 0，甚至为负。
> 写下它是为了**避免后来人重走一遍**——尤其避免基于"调用次数"这类表面指标做判断。
>
> 全部数字为本机实测（Windows / Temurin JDK 21.0.11 / 单线程 / best of N），
> 生产列采样用 160×160 扫描（40 000 列，8wu 步长）。

---

## 一、结论速览

| 优化项 | 表面动机 | 实测生产列收益 | 结论 |
|---|---|---:|---|
| **A**：`heightBlocks` 延迟求值 `landB` | `landE` 在陆地分支被算两次 | `sampleE` −7.2%，生产列 **−0.4%** | **不做** |
| **A'**：A + `sampleColumn` 复用已算的 `landE` | 同 A，且把 `sampleColumn` 那次也算进去 | **0 ~ −1.3%（略慢）** | **不做** |
| **B**：`alpha()` 单点记忆化 | 复合条目 selector 被重复求值 | **−0.3% ~ −1.5%，且不与 A 叠加** | **不做** |

**三项全部不做。** 根因见 §三。

---

## 二、被否掉的三项，各自错在哪

### 2.1 症状：为什么"看起来"有收益

`landE`（morph 陆地场，单次约 **12 800 ns**）确实被调用了不止一次：

| 位置 | 代码 | 说明 |
|---|---|---|
| [MorphOceanAssembler.java:620](../forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/terrain/morph/MorphOceanAssembler.java#L620) | `double landB = landE(x, z) * BLOCKS_PER_E_LAND;` | `heightBlocks` 开头**无条件**求值 |
| [MorphOceanAssembler.java:303](../forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/terrain/morph/MorphOceanAssembler.java#L303) | `double landB = landE(x, z) * BLOCKS_PER_E_LAND;` | `coastalProfile` 陆地分支**再算一次** |
| [MorphTerrainSampler.java:185](../forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/terrain/morph/MorphTerrainSampler.java#L185) | `c.landE = landE(x, z);` | `sampleColumn` 又**无条件**算一次 |

⇒ 实测统计：**每生产列 `e()` 调用 3.77 次**（`sampleE` 内部陆地 2 次 / 海洋 1 次，加 `sampleColumn` 1 次）。

到这里一切看起来都合理，也确实和 Theseus 提案（PR #1/#2）的论断一致。

### 2.2 真相：`MorphLandField` 的 last-point 缓存把重复吸收掉了

关键在 [MorphLandField.java:236](../forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/terrain/morph/MorphLandField.java#L236)：

```java
if (cached != null && x == lastX && z == lastZ) {
    cachedPoints++;      // 缓存命中
    return cached;
}
```

同一坐标的连续调用**直接返回上次算好的 `Sample`**。实测计数（14 000 列）：

| 指标 | 实测 |
|---|---:|
| `e()` 调用 | **3.77 次/列** |
| 其中**完整计算** | **1.00 次/列** |
| 其中缓存命中 | **2.77 次/列** |

⇒ **重复调用是真的，但重复计算几乎不存在。**

### 2.3 缓存命中到底多便宜

| 项 | 实测 |
|---|---:|
| 缓存命中（同点） | **14.4 ns** |
| 完整计算 | ~12 800 ns |

消除 2.77 次命中最多省 `2.77 × 14.4 ≈ 40 ns`，
占 15 760 ns/列预算的 **0.3%**——已在测量噪声内，且会被新增开销吃回去（§2.4）。

### 2.4 A' 为什么反而略慢

正确的 A' 需要把 `e()` 内部已算的 `landE` 带出来。带出方式必然引入额外开销：

- 用 `double[]` 返回 ⇒ **每列一次数组分配**
- 用实例字段记录 ⇒ 共享单例下**多线程互相覆盖**（不可用）
- 用哨兵值 + 事后补算 ⇒ 增加分支判断

而且**海洋点在 `sampleColumn` 里本来也要 `landE` 的真实值**（不是懒惰可省的），
所以海洋分支仍需补算 ⇒ 净收益被完全抵消。

实测四轮，A' **没有一轮快于基线**：

| 轮次 | 基线 | A' |
|---|---:|---:|
| 1 | 15 086 | 15 282 |
| 2 | 14 971 | 15 163 |
| 3 | 15 073 | 15 088 |
| 4 | 14 887 | 14 929 |

### 2.5 B 为什么也没用

`MorphLandRecipe` 的 `alpha(x,z)` 确实被重复求值，实测单复合条目：

| 项 | 实测 |
|---|---:|
| 1 次 selector 求值 | 127 ns |
| `raw + amplitude + bias` | 599 ns |
| 比值 | **4.70×** |

但这个 4.70× 是**局部**比值。`alpha` 的重复只发生在**那 1 次完整计算内部**，
而 selector 又只是完整计算的一小部分 ⇒ 端到端被稀释到 −0.3% ~ −1.5%。

而引入 last-point 记忆需要**新增可变字段**，在共享单例下要额外考虑并发（虽然 `(x,z)` 双字段比对
可保证不返回错值，但收益不值这个复杂度）。

---

## 三、根因（这条最有价值）

> **"调用次数"不是"计算次数"。判断性能收益必须测 `MorphLandField.stats()` 的
> `points`（= 完整计算次数），不能用调用计数或代码阅读外推。**

这条教训的直接来源：本轮的 A/A'/B 三项都是**基于代码阅读**提出的，
看起来无懈可击（重复求值、纯函数、逐位不变），
但真正决定收益的 `stats()` 一测就否掉了全部。

### 附：`MorphLandField.stats()` 的字段语义（容易读反）

```
points        = 完整计算的次数（写在未命中分支，MorphLandField.java:253）
cachedPoints  = 缓存命中的次数（写在命中分支，MorphLandField.java:237）
```

⚠ **`points` 不是"总调用次数"**。本轮曾因读反这两个字段，算出 `-1.77 次/列` 的负数，
得到一次无效测量。正确读法是 `points` = 完整计算、`points + cachedPoints` = 总调用。

---

## 四、两个实际踩过的坑（复现时务必避开）

### 坑 1：第一版 A' 会静默改变产出

用「哨兵 `hold[0] = 0`」带出 `landE` 时，**海洋点走海洋分支，`hold[0]` 从未被赋值**，
于是 `c.landE` 变成 `0`。

逐字段对拍（每列 9 个 double + typeWeights）发现：**198 行 `landE` 从真实值变为 0**。

这个 bug 的危险性在于：

- `c.e`（高度）**完全不变** ⇒ 地形、渲染、水文全部正常
- `c.landE` 只喂 `c.shape` 和分类 ⇒ **形态图/分类图会错，但所有只查高度的门禁都会放行**
- `sampleE` 的 digest 也**不变**（因为 bug 只在 `sampleColumn`）

**如果只对 `sampleE` 做 digest 对拍，这个 bug 会被漏掉。**
必须对 `sampleColumn` 的**全部字段**对拍。

### 坑 2：跨 tile 的点会让"完整计算"成本虚高

测"完整计算成本"时若让采样点每次移动 37 格，会不断触发新 tile 构建，
测出 **376 715 ns/点**（而真实单次约 12 800 ns）。

⇒ 测单次计算成本必须**在同一个 tile 内**移动坐标，否则测的是 tile 构建成本。

---

## 五、这些结论与 Theseus 提案（PR #1 / #2）的关系

提案（已归档于 [handoff/theseus-tech-proposal-20261102.md](../handoff/theseus-tech-proposal-20261102.md)）称：

> 「`OceanAssemblyCandidate.heightBlocks` 开头算的 `landE`，在启用海岸细节的路径上没有用到，
> `coastalProfile` 的陆地分支里还会再算一次。陆地点算了两遍，海洋点白算一遍。
> 我们的 C# 版去掉后全图 SHA 不变，.NET 9 单格耗时从 7.05 降到 2.83 微秒。」

**逐条核对**：

| PR 说法 | Java 侧实测 |
|---|---|
| `heightBlocks` 白算 `landE` | ✅ 成立（L620） |
| `coastalProfile` 再算一次 | ✅ 成立（L303） |
| 海洋点白算一遍 | ✅ 成立 |
| **去掉后单格 2.5× 提升** | ❌ **Java 侧为 0%** |

**差异根源**：PR 的 2.5× 来自 **C# 版实测，而 C# 版没有 `MorphLandField` 的 last-point 缓存**
（其 `sample()` 的 last-point 缓存结构见 [MorphLandField.java:236](../forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/terrain/morph/MorphLandField.java#L236)）。
提案作者自己在讨论串里标注了「Java 有逃逸分析，效果可能不同，这点没测」——
这个自我标注是准确的，本轮实测证实了它。

⇒ **该提案的定性观察正确，定量结论不可移植到 Java 侧。**

---

## 六、那性能应该往哪看（基于实测的分布）

`sampleTerrain` ≈ **15 000 ns/列**，其中：

| 组件 | ns/列 | 占比 |
|---|---:|---:|
| `sampleE`（morph 链） | ~13 000 | **87%** |
| `TectonicField.sample` | ~1 100 | 7% |
| `StratumField.layerAt` | ~19 | 0.1% |

而 JFR（`settings=profile`）在 morph 链内部的热点分布：

| 位置 | 采样命中 |
|---|---:|
| `MorphNoiseEval.eval` 的 `Warped` 分支 | 527 |
| `MorphLandRecipe.raw` | 373 |
| `MorphNoiseEval.eval` 的 `Mul` 分支 | 365 |
| `MorphNoiseEval.eval` 的 `PerlinRidge/Billow` 分支 | 301 |
| `MorphNoiseEval.perlin` | 220 |

⇒ **约 7 600 ns/列花在噪声图递归求值上**，且这是**真实计算**（不是重复调用）。

若要继续优化，只有两条路：

1. **减少求值次数**——算法级改动（如提案第三条的"栅格替代 warp 反解"），
   会改变架构、需重出基准图对拍。
2. **降低单次求值成本**——改公式/降精度，**会改产出**，须升 `CACHE_SCHEMA_VERSION`。

两条都不是"顺手优化"，需单独立项。

---

## 七、附：本轮用到的探针（未入库，`%TEMP%\geobench\`）

| 探针 | 作用 |
|---|---|
| `GeoExactColumn` | 对 `sampleColumn` **全部字段**求 digest（关键：不能只拍 `sampleE`） |
| `GeoFullComputations` | 用 `stats()` 区分"完整计算"与"缓存命中"次数 |
| `GeoCacheCost2` | 缓存命中 vs 完整计算的单次成本 |
| `GeoABBench` / `GeoColumnAB` | 各臂 ns/pt 与 digest |

> ⚠ **不要只对 `sampleE` 做 digest 对拍**——§四坑 1 的 bug 只会在 `sampleColumn` 的其他字段上暴露。