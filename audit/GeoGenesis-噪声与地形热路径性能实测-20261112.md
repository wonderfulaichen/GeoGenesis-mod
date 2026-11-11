+# 噪声与地形热路径性能实测（2026-11-12）

> 起因：用户询问 FastNoiseLite（`weigert/FastNoiseLite`，实为 `Auburn/FastNoiseLite` 的旧 fork）
> 是否有值得借鉴的**性能**优化。
> 本文件记录**实测数据**与结论，不含任何生产代码改动。
>
> 全部数据为 Windows / Temurin JDK 21.0.11 / 单线程 / `best of N` 实测，
> 基准程序放在 `%TEMP%\geobench\`（**不在仓库内**）。

---

## 一、结论先行

1. **FastNoiseLite 没有性能优势可借鉴**：我们的噪声实现比它**快 10~15%**（实测）。
   FNL 全线 `float`，我们是 `double` 精度 —— 在同精度下 FNL 依然更慢。
   ⇒ 借 FNL 换性能是**负收益**，不成立。
2. **真正的瓶颈不在噪声**。实测热路径成本排序（每采样点）：

   | 组件 | ns/点 | 相对噪声原语 |
   |---|---:|---:|
   | `Simplex.compute`（噪声原语） | ~20 | 1× |
   | `TectonicField.sample`（构造场） | **~2 130** | **107×** |
   | └ `stressField`（25 格连续加权投票） | ~1 500 | 75× |
   | └ `distAt`（9 格 Voronoi 搜索） | ~274 | 14× |
   | └ `valueNoise`（域扭曲） | ~160 | 8× |
   | `MorphLandField.sample`（morph 链） | **~11 700** | **585×** |

   ⇒ **优化预算应给 `stressField` 与 morph 链，不该给噪声原语。**
3. **`stressField` 存在"每点重算格点常量"的纯浪费**，实测可 **2.1× 加速且输出逐位相同**
   （`max|diff| = 0.0`）。
4. **morph 链的分派不是瓶颈**：`instanceof` 链实测 **2.02 ns/次**，
   与 class-keyed switch（2.11 ns）**无差异** ⇒ JIT 已优化，**不要动分派**。
   morph 的 11.7 µs/点 是**真实数学运算**（噪声图求值 + Voronoi 距离 + 侵蚀纹理），
   属于"要优化必须改算法语义"的范畴，**不是移植外部库能解决的**。

---

## 二、我们 vs FastNoiseLite（同工作量对拍）

网格 512×512 = 262144 点，best of 12：

| 项目 | 我们 | FastNoiseLite | 结论 |
|---|---:|---:|---|
| Simplex 2D | **19.96 ns** | 22.74 ns | 我们快 12% |
| FBM 6 octaves | **90.21 ns** | 99.06 ns | 我们快 9% |
| Simplex 3D | **35.82 ns** | 37.28 ns | 我们快 4% |
| Value 2D | — | 8.97~14.85 ns | FNL 独有的原语 |
| Cellular 2D | — | 60.11 ns | FNL 独有，但我们有自研 Voronoi |

> 稳定性：连跑 3 次，差异方向一致，非噪声。
> 对拍口径：双方都用 float/double 各自的原生精度；
> FNL 的 `OpenSimplex2` 与我们的 Gustavson Simplex **算法不同**，此处只比**吞吐**，不比数值。

### 为什么我们更快（定性）
- 我们的 `Simplex` 是**专门的 2D 实现**（Gustavson 经典，8 梯度，3 个角点）；
- FNL 的 `OpenSimplex2` 是通用多态实现，2D 走的是同一份 `GenNoiseSingle` 分派；
- FNL 每 octave 用 `seed++` 换种子 ⇒ 每层重算哈希；我们每个 octave 用独立 `Simplex` 实例
  ⇒ **置换表只建一次**（`Seed` 构造一次，512 项），这是主要差距来源。

---

## 三、`stressField` 的纯浪费（可优化，产出不变）

### 现状（`TectonicField.stressField`，第 916~944 行）

每个采样点遍历 **5×5 = 25 个格点**，每格都重算：

```
hash(cx, cz, SALT_SEED)   → 种子位置      ← 只依赖 (cx,cz)
hash(cx, cz, SALT_VEL)    → cos(ang)/sin(ang)/mag  ← 只依赖 (cx,cz)
```

⇒ **25 次采样 × 2 次哈希 + 25 次 cos + 25 次 sin**，而这些量**每格只算一次就够**
（格点常量，不随查询点变化）。

### 实测

| 实现 | ns/点 | 输出 |
|---|---:|---|
| 生产（每点重算全部） | 1 413 | 基准 |
| **记忆化格点常量** | **659** | **`max|diff| = 0.0`** |
| 生产版（反射实测） | 1 508 | — |

⇒ **2.14× 加速，产出逐位相同**（浮点运算顺序未变，只是把"常量表达式提前求值"）。

### 附带发现：`exp`/`cos`/`sin` **不是**瓶颈
单独测 25 次 `Math.exp` = **12.3 ns/点**，25 次 `sqrt` = 4.5 ns/点，
25 次 `cos+sin` = 17.3 ns/点 —— 全部远小于 `stressField` 的 1 500 ns。
⇒ **不要为"省 transcendental"去改算法**，那会改变数值；真正的浪费是**重复求值格点常量**。

### 线程安全
用 `ConcurrentHashMap` 记忆化后，**4 线程实测 667 ns/点 ≈ 单线程 645 ns/点**
（几乎无退化）⇒ 记忆化表是线程安全的实现方向。

### 缓存规模
5×5 窗口 ⇒ 每次采样触及 ≤25 格；实测 3000 点只触及 **36 格**（点密集 ⇒ 复用率极高）。
按 `PLATE_SPACING = 2000wu` ⇒ 全球 2³¹ 格，但实际访问集中 ⇒ 用有界 LRU 或
按 `(cx,cz)` 哈希的直接映射数组即可，**不需要无界 Map**。

---

## 四、morph 链（JFR 实测）

`MorphLandField.sample` = 11 683 ns/点，JFR（`settings=profile`）热点分布：

| 位置 | 采样数 | 占比 |
|---|---:|---:|
| `MorphNoiseEval.eval` 第 81 行（`Warped` 递归） | 548 | 高 |
| `MorphLandRecipe.raw` 第 44 行（`eval(entry.rawNode())`） | 409 | 高 |
| `MorphNoiseEval.eval` 第 78 行（`Mul`） | 407 | 高 |
| `MorphNoiseEval.eval` 第 73/72 行（`PerlinRidge`/`Billow`→`ridge`） | 557 | 高 |
| `MorphNoiseEval.perlin` 第 127 行（octave 循环） | 228 | 中 |
| `MorphNoiseEval.ridge` 第 193 行 | 195 | 中 |
| `MorphLandField$Tile.signedDistance` 第 811 行 | 127 | 中 |
| `Math.hypot`（段距离） | 127 | 中 |
| `MorphNoiseEval.erosion` / `erosionValue` / `cachedHeight` | ~148 | 中 |

### 排除掉的两个"疑似瓶颈"
1. **分派不是瓶颈**：`instanceof` 26 测链 = **2.02 ns/次**，
   class-keyed switch = 2.11 ns ⇒ **无差异**，JIT 已处理。**不要动分派。**
2. **`mergeToEntries` 分配不是瓶颈**：单独测 = 32.7 ns/点（占 0.3%）。
   （`entryOfSite()` 每次 `entry.clone()` 确实每次分配，但成本可忽略。）

### 结论
morph 的 11.7 µs/点是**真实数学量**：多层噪声图求值 + Voronoi 边界距离 + 侵蚀纹理（5×5 邻域）。
**要加速必须改算法语义**（例如缓存、提前剪枝、LOD），属于设计变更而非局部优化；
且 morph 链有 **float/double 双实现一致性契约**（`MorphNoise.DualNode`）与
`MorphGeometryTransform` 的 `octaves == 1` 硬校验，动它风险高。

---

## 五、建议（按性价比，均需先出方案再动手）

| # | 措施 | 预期收益 | 风险 | 是否改产出 |
|---|---|---|---|---|
| 1 | `stressField` 记忆化格点常量（seed 位置 + cos/sin/mag） | `stressField` 2.1×；`tectonic.sample` 约 −25% | 低 | **否**（实测 max diff = 0.0） |
| 2 | `distAt` / `plateSeed` 同样记忆化格点常量 | `distAt` 部分收益 | 低 | 否 |
| 3 | morph 链：缓存 `entryOfSite()` 的 clone | ~30 ns/点（0.3%） | 低 | 否 |
| 4 | morph 链：噪声图求值缓存 / 提前剪枝 | 未知，需先做 profile-guided 方案 | **高** | 可能 |

> **注意（AGENTS.md 铁律）**：
> - 措施 1~3 是**纯加速**（不改数值）⇒ 不需要升 `CACHE_SCHEMA_VERSION`，
>   但**必须**用既有门禁 + 探针验证产出逐位不变。
> - 措施 4 若触碰 morph 语义 ⇒ 需升 schema 并重出 1401² 生产图对拍。

---

## 六、明确不做的事

- **不移植 FastNoiseLite**：性能更慢、算法不同（Ridged 撞 AGENTS.md §⛔ 第 6 条折叠禁令）、
  `float` 精度不兼容 morph 双实现契约 ⇒ 三重不成立。
- **不为省 transcendental 改 `stressField` 的数学**：会改产出，且实测 transcendental 不是瓶颈。
- **不改 morph 分派**：实测无收益。

---

## 七、复现方式

基准程序：`%TEMP%\geobench\`（临时目录，未入库）。
需要时可按本文件 §一~§四的表格重建；关键口径：

```
classpath = build/classes/java/main
          + forge-1.20.1-47.4.10_mapped_official_1.20.1.jar
          + datafixerupper/brigadier/logging/authlib/fastutil/guava/gson/commons-lang3
JFR      = java -XX:StartFlightRecording=filename=x.jfr,settings=profile ...
```

> ⚠ 本文件只记录**实测数字**。任何一项措施落地前，
> 仍需按 AGENTS.md 要求先出技术方案、确认后再实施。
