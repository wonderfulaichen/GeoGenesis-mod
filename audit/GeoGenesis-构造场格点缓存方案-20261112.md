+# 构造场格点常量缓存 — 技术方案（2026-11-12）

> 对应实测报告：`audit/GeoGenesis-噪声与地形热路径性能实测-20261112.md` 措施 1。
> 本文是**方案**，不是实施记录。实施与验证结果回填在 §七。

---

## 一、要解决的问题

`TectonicField.stressField` 每个采样点遍历 5×5 = 25 个格点，每格都重算：

```java
long hs = hash(cx, cz, SALT_SEED);          // 种子位置
double sx = (cx + 0.5 + (unit(hs) - 0.5) * SEED_JITTER) * PLATE_SPACING;
double sz = (cz + 0.5 + (unit(hs >>> 24) - 0.5) * SEED_JITTER) * PLATE_SPACING;
long hv = hash(cx, cz, SALT_VEL);           // 速度
double ang = unit(hv) * Math.PI * 2.0;
double mag = 0.3 + unit(hv >>> 24) * 0.7;
// 用于: Math.cos(ang), Math.sin(ang)
```

这 7 个量**只依赖 `(cx, cz)`**，与查询点 `(wx, wz)` 无关 ⇒ 是**格点常量**。

同一模式还出现在 `distAt` / `edgeDistRaw` 里的 `plateSeed(cx, cz, sp)`（只用到 `sx, sz`）。

### 实测成本（`audit/...-性能实测-20261112.md`）

| 组件 | ns/点 |
|---|---:|
| `stressField` | ~1 500 |
| `TectonicField.sample` 合计 | ~2 130 |
| 25× `Math.exp` 单独测 | 12.3 |
| 25× `cos+sin` 单独测 | 17.3 |

⇒ **不是 transcendental 贵，是同一批常量被重复求值 25 次。**

---

## 二、设计

### 2.1 缓存结构：有界直接映射数组

**用户裁定：用有界直接映射数组，不用无界 Map。**

```java
/** 每个格点的常量。可变字段 = 槽位复用（direct-mapped 覆盖写）。 */
private static final class CellConst {
    int cx, cz;                       // 身份标记（判命中）
    double sx, sz, cosA, sinA, mag;
}

private static final int CELL_BITS = 8;          // 256 槽（见 §2.3 实测选型）
private static final int CELL_SLOTS = 1 << CELL_BITS;
private static final int CELL_MASK = CELL_SLOTS - 1;
private final CellConst[] cellSlots = new CellConst[CELL_SLOTS];   // 槽位对象复用，不反复 new
```

**为什么不是无界 `Map<Long,Cell>`**：
1. 世界无限 ⇒ 长期运行 Map 无界增长（内存泄漏风险）；
2. `HashMap` 每次查要装箱 `Long` + 链表/树遍历，实测比数组直接索引慢；
3. 直接映射的**冲突代价**在实测中可忽略（§2.3）。

**槽位为什么预分配**：`cellSlots` 数组里放**常驻对象**，覆盖写时只改字段
⇒ 稳态**零分配**。与 `BaseWorldSampler.sampleCache` 的
`AtomicReferenceArray<Entry>` 思路一致（那里每次 `new Entry`，这里进一步省掉）。

### 2.2 命中判定与索引

```java
private CellConst cellConst(int cx, int cz) {
    int slot = ((cx * 0x9E3779B1) ^ (cz * 0x85EBCA77)) & CELL_MASK;
    CellConst c = cellSlots[slot];
    if (c != null && c.cx == cx && c.cz == cz) {
        return c;                                  // 命中
    }
    if (c == null) { c = new CellConst(); cellSlots[slot] = c; }
    c.cx = cx; c.cz = cz;                          // 覆盖写
    // ... 填 sx,sz,cosA,sinA,mag（与现实现逐字等价）
    return c;
}
```

- 索引用 **Knuth 乘数异或**（两素数分离映射 `cx`/`cz`），避免 `cx==cz` 时自映射。
- 命中判定靠 `(cx, cz)` 双字段比对 ⇒ **冲突只会 miss（重算），绝不会返回错误格点值**。
  这是直接映射能保产出不变的根本原因。

### 2.3 槽位数选型（实测）

`PLATE_SPACING = 2000` wu，5×5 窗口 ⇒ 一个采样点触及 ≤25 格。
跨 20 个格宽（40000 块）、step 8 扫描：

| 槽位 | 命中率 | ns/点 |
|---:|---:|---:|
| 16 | 24.02% | 571.9 |
| 32 | 76.76% | 393.3 |
| 64 | 87.34% | 329.7 |
| 128 | 94.36% | 272.6 |
| **256** | **99.17%** | **244.8** |
| 512 | 99.39% | 244.9 |
| 1024 | 99.39% | 244.6 |
| 4096 | 99.40% | 241.3 |

⇒ **取 256 槽**：越过 256 只多 0.23% 命中，收益已在噪声内。
内存 256 × 56B ≈ 14 KB，可忽略。

> 参考：**无缓存 = 1306 ns/点**（同一扫描口径）。
> ⚠ 早先测的 1 413 ns 是随机坐标（跨格更散）；40000 跨度的顺序扫描是 **1306**，
> 两者一致，差异只来自坐标分布。

### 2.4 并发

`TectonicField` 是**共享单例**（`GeoGenesisGenerator.WORLD_LIFECYCLE` 构建一次，
多 worker 线程共用；`CellGenerator.tectonic` 同理）。⇒ 缓存**必须并发安全**。

**方案：单一 `AtomicReferenceArray<CellConst>` 做容器，槽内对象不可变（immutable snapshot）。**

关键设计取舍：**不采用"槽位对象可变 + 字段覆盖写"**（§2.1 提到的零分配版），
因为多线程下读者可能看到 `cx/cz` 已更新而 `sx/sz` 还是旧值的**撕裂状态**
⇒ 返回错误格点常量 ⇒ 地形错误（比崩溃更糟，且难复现）。

正确做法（与 `BaseWorldSampler.sampleCache` 同一范式）：

```java
private final AtomicReferenceArray<CellConst> cellSlots;   // null = 空槽

private CellConst cellConst(int cx, int cz) {
    int slot = ((cx * 0x9E3779B1) ^ (cz * 0x85EBCA77)) & CELL_MASK;
    CellConst c = cellSlots.get(slot);              // volatile read
    if (c != null && c.cx == cx && c.cz == cz) return c;
    CellConst fresh = new CellConst(cx, cz);        // 不可变 record，一次构造
    cellSlots.set(slot, fresh);                     // 原子发布
    return fresh;
}
```

- `CellConst` 改为 **`record`（全 final）** ⇒ 一次原子发布完整条目，**无撕裂**。
- 冲突语义：两个线程同时写同槽 → 后写者覆盖，先写者已返回自己的对象（正确值）。
  **最坏情况只是下次 miss 重算，绝不返回错值。**
- 稳态下同一格被反复命中，`record` 不重建 ⇒ 分配压力 ≈ 256 个对象封顶。
- `AtomicReferenceArray.get/set` 比 `Accumulator`/锁便宜；无写竞争时接近普通数组读。

> **为什么不用 `ConcurrentHashMap`**：无界（违反用户裁定）+ 装箱 `Long` 键 + 树化开销。
> 实测直接映射 99.17% 命中已足够，不需要 Map 的精确淘汰。

### 2.5 种子切换必须清空

`setSeed(long)` 会让所有格点常量失效：

```java
public void setSeed(long worldSeed) {
    this.seed = worldSeed;
    for (int i = 0; i < CELL_SLOTS; i++) cellSlots.set(i, null);   // 清空
}
```

⚠ **这是本方案最容易出错的地方**。若漏清空，换种子后部分格点返回**旧种子的常量**
⇒ 构造格局与种子不符（且只影响"恰好命中的格"，表现为顽固的局部错误）。
`CellGenerator.seed()` 会调 `tectonic.setSeed(worldSeed)`，必须有此清空。

> `AtomicReferenceArray` 的 `set` 是 volatile 写，跨线程可见；清空后旧条目立即失效。

### 2.6 `-D` 开关（按 AGENTS.md 四条铁律）

```java
/** 默认开；-Dgeogenesis.tectonicCellCache=false 关闭（回退到逐点重算）。 */
private static final boolean CELL_CACHE =
        !"false".equals(System.getProperty("geogenesis.tectonicCellCache"));
```

- 属性名全仓**仅此一处读取**，默认值口径唯一（默认开）。
- 关闭时走原实现分支 ⇒ 可用于 A/B 对拍证明"产出逐位一致"。
- 新增键会被 `runConfigKeyGuardProbe` 检查 —— 它是 `System.getProperty` 而非
  `GeoGenesisConfig` 键，**不进该门禁范围**（该门禁扫的是 config 键）。

### 2.7 改动范围（最小化）

只改 `TectonicField.java` 一个文件：

| 位置 | 改动 |
|---|---|
| 文件头常量区 | 加 `CELL_BITS/SLOTS/MASK`、`CELL_CACHE` 开关 |
| 字段区 | 加 `cellSlots`、`CellConst` record |
| 构造器 | 初始化 `cellSlots` |
| `setSeed` | **加清空** |
| 新增 `cellConst(cx, cz)` | 取/建格点常量 |
| `stressField` | 25 格循环改用 `cellConst` |
| `distAt` | 9 格循环改用 `cellConst`（只用 sx/sz） |
| `edgeDistRaw` | 同上 |
| `plateSeed` / `plateVelocity` | **保留不动**（诊断探针反射调用它们） |

> ★ **`plateSeed` / `plateVelocity` 必须保留原样**：
> `M1TectonicDriverCheck` 与 `DiagnosticTectonicDriverCheck` 用反射直接调这两个方法
> 做独立公式校验（`FORMULA_TOL = 1e-12`）。把 `stressField` 改成走缓存后，
> 这两个方法仍是"权威参考实现"，探针据此对拍**正好成为产出不变的外部证据**。

---

## 三、为什么不改数学

实测 25× `Math.exp` = 12.3 ns、`cos+sin` = 17.3 ns，合计仅占 `stressField` 的 ~2%。
⇒ **不为省 transcendental 去替换 exp/cos/sin**：
1. 收益微不足道（< 2%）；
2. 任何替换（如 `1/(1+x²)` 替代 `exp`）**都会改变数值** ⇒ 改产出、须升 schema、重出基准图；
3. 本方案的核心是"**不重算已算过的**"，不是"把算得贵的东西算便宜"。

---

## 四、产出不变性论证

缓存返回的 `(sx, sz, cosA, sinA, mag)` 与现场重算**逐位相同**，因为：

1. 计算式**逐字未改**（同样的 `hash`、`unit`、`SEED_JITTER`、`PLATE_SPACING`）；
2. `Math.cos(ang)` / `Math.sin(ang)` 是**纯函数**，同一 `ang` 恒得同一结果；
3. `stressField` 的累加**顺序未变**（仍是 `dx` 外、`dz` 内的 5×5 遍历）；
4. 命中/冲突只影响"是否重算"，**不影响参与运算的数值**。

⇒ **浮点运算序列逐位不变** ⇒ 不需要升 `CACHE_SCHEMA_VERSION`。

⚠ 唯一需要保证的是 **§2.5 的清空**（换种子）；以及**不得**把 `mag`/`cosA` 等改成
`float` 或改变求值顺序。

---

## 五、预期收益

| 项 | 实测 |
|---|---:|
| `stressField` | 1 413 → 660 ns（**2.14×**） |
| `stressField`（含直接映射 256 槽） | 1 306 → 245 ns（**5.3×**，注 ①） |
| `TectonicField.sample` 合计 | 2 130 → 约 **900~1 100** ns（−50% 左右） |

> 注 ①：5.3× 是**纯 `stressField` 循环**在顺序扫描下的数字（含 `distAt` 未优化时的对比）。
> `TectonicField.sample` 的端到端收益低于此，因为 `distAt`/`valueNoise`/`blurDist` 未被
> 本次改动覆盖（它们属于后续措施 2）。**端到端实测数字见 §七。**

**绝对量口径**：`TectonicField.sample` 只在 `sampleTerrain` 里调用（**每列一次**，
不是每方块一次）⇒ 对整体区块生成的影响应按"列"折算，不能按"方块"夸大。
节省约 1.1 µs/列 × 256 列/区块 ≈ **0.28 ms/区块**（上限估计，未扣并行）。

---

## 六、验证计划

| # | 验证 | 判据 |
|---|---|---|
| 1 | **逐位一致**：同一坐标域，`-Dgeogenesis.tectonicCellCache=false` vs 默认 | `max\|diff\| == 0.0`，覆盖 ≥10⁵ 点 + 多种子 |
| 2 | **换种子正确**：`setSeed(A)` → 采样 → `setSeed(B)` → 采样，再对照全新实例 | `max\|diff\| == 0.0` |
| 3 | **并发正确**：8 线程并发采样 vs 单线程 | 逐位相同 |
| 4 | `runTectonicContinuityProbe`（门禁内） | ALL PASS |
| 5 | `runTectonicWaveProbe`（门禁内，平行带判据） | ALL PASS |
| 6 | `M1TectonicDriverCheck`（独立公式校验，`FORMULA_TOL=1e-12`） | **改前改后输出逐字节相同**（该检查本身既有 897 FAIL，见 §7.4） |
| 7 | 端到端加速比 | 记录实测 ns/点 |

> 验证 1~3 是本方案"不改产出"的**直接证据**；4~6 是既有门禁的回归。
> 若 1 失败，**立即回退**，不进入 4~7。

> ⚠ **对验证 6 的修正**：本方案初稿把 `M1TectonicDriverCheck` 的判据写成 "ALL PASS"，
> 这是错的 —— 该检查**本身就有 897 项既有 FAIL**（`audit/新版地形对接入口.md:23` 已记录）。
> 正确判据是 **"改前改后 8 份输出 CSV 的 SHA-256 完全相同"**，已按此执行并通过（§7.4）。

---

## 七、实施与验证结果（2026-11-12 实施完成）

### 7.1 实际改动

只改 `TectonicField.java` 一个文件：

| 位置 | 改动 |
|---|---|
| 常量区 | `CELL_BITS=8 / CELL_SLOTS=256 / CELL_MASK`、`CELL_CACHE` 开关 |
| 字段区 | `AtomicReferenceArray<CellConst> cellSlots`、`record CellConst(...)` |
| 构造器 | 初始化 `cellSlots` |
| `setSeed` | **加清空**（换种子必须失效） |
| 新增 | `cellConst(cx,cz)`、`buildCellConst(cx,cz)` |
| `sample` | 9 格种子位置查找改用 `cellConst`（原用 `plateSeed`） |
| `stressField` | 25 格循环改用 `cellConst` |
| `distAt` | 9 格改用 `cellConst` |
| `edgeDistRaw` | 9 格改用 `cellConst` |
| `plateSeed` / `plateVelocity` | **原样保留**（诊断探针反射依赖） |

> 实施中比方案多覆盖了一处：**`sample()` 自身的 9 格 `plateSeed` 查找**也改走缓存。
> 它是主入口，收益最大；且同样只用 `(sx,sz)`，语义完全一致。

### 7.2 逐位一致性（决定性证据）

同一坐标域、6 个种子（含 `0` / `-1` / `Long.MIN_VALUE`）× 4 678 618 点，
再加 20 000 散点，对 `dist/btype/rate/tangentX/tangentZ/stress/alongCoord` 全部字段求摘要：

| 臂 | 摘要 |
|---|---|
| `-Dgeogenesis.tectonicCellCache=false` | `d5973df182c631c7` …（6 seed 全列） |
| 默认（缓存开） | **完全相同** |

**`IDENTICAL - PASS`** ⇒ 产出逐位不变，**不需要升 `CACHE_SCHEMA_VERSION`**。

### 7.3 换种子与并发（两个最高风险项）

| 检查 | 结果 |
|---|---|
| `setSeed(42→999→-5→42)` 复用同一实例 vs 全新实例 | 4/4 **PASS**（摘要逐一相等） |
| 8 线程并发采样 vs 全新实例逐点比对 | **0 mismatch，PASS**（3 轮） |

⇒ §2.5 的清空与 §2.4 的原子发布都成立。

### 7.4 门禁回归

| 探针 | 结果 |
|---|---|
| `runTectonicContinuityProbe` | **ALL PASS** |
| `runTectonicWaveProbe` | **ALL PASS**（各向异性比 1.023 / 1.142，阈值 1.80） |
| `runWorldgenGate`（`-x runCenterlineCrossSectionProbe`） | **BUILD SUCCESSFUL** |
| `runConfigKeyGuardProbe` | **PASS**（新键是 `System.getProperty`，不进该门禁） |

**既有红项（非本次引入，已用对照证明）**：

1. `runCenterlineCrossSectionProbe` —— AGENTS.md 已登记为"既存红"。
   缓存开/关两臂**都 FAIL**，与本改动无关。
2. `M1TectonicDriverCheck` —— 59720 断言中 **897 FAIL**。
   - 缓存开/关两臂都恰好 **897**；
   - 8 份输出 CSV（`assertions/raw/limits/cutoff/sites/world/shrink/smooth-pos`）
     **SHA-256 逐一相同**；
   - `audit/新版地形对接入口.md:23` 早已记录「M1b FAIL，59720 项断言中 897 项失败，
     并非 897 个独立缺陷」，状态 `STOPPED_AT_M1B_FAIL`。
   ⇒ **既有基线，与本次改动无关。**

### 7.5 实测加速比（**修正 §五的预期**）

300 000 点、跨 40000 块、best of 10，直接跑**已发布的实现**：

| 臂 | `sample`（取 dist） | 全字段 |
|---|---:|---:|
| 缓存关（基线） | 2 757.8 ns/点 | 2 791.3 ns/点 |
| **缓存开（发布默认）** | **1 346.2 ns/点** | **1 369.4 ns/点** |
| 加速比 | **2.05×** | **2.04×** |

> ⚠ **修正 §五的估计**：原写 `TectonicField.sample` 合计 2 130 → 900~1 100 ns。
> 实测基线是 **2 758**（不是 2 130 —— 2 130 是早先随机点口径，与本次跨 40000 块的口径不同），
> 优化后 **1 346**。**方向正确，绝对值按本表为准。**

**绝对量口径（不变）**：`TectonicField.sample` 只在 `sampleTerrain` 里调用
（**每列一次**，不是每方块一次）。节省约 **1.41 µs/列** × 256 列/区块
≈ **0.36 ms/区块**（上限估计，未扣并行）。

### 7.6 回退方式

```bash
# 单次运行对比（不改代码）
gradlew runClient -Dgeogenesis.tectonicCellCache=false
```

或直接删除 `cellConst` 的三处调用点、恢复 `plateSeed` 内联式。
`CELL_CACHE=false` 时 `cellSlots == null`，走 `buildCellConst` 现场重算，
与改动前逐位等价（§7.2 已证）。

---

## 八、不做的事

- **不用无界 `Map`**（用户明确裁定）。
- **不改 `plateSeed` / `plateVelocity` 签名或行为**（诊断探针反射依赖）。
- **不改浮点求值顺序 / 不降精度**（会改产出）。
- **不动 `distAt` 的 `valueNoise` 域扭曲**（属措施 2 范畴，本方案只做 `plateSeed` 部分）。
- **不动 morph 链**（措施 3/4，风险与收益另议）。
