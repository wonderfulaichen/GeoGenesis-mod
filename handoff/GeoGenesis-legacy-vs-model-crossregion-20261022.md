# legacy ↔ 当前 model 跨区机制逐项对照（2026-10-22）

> 用户口径：legacy 旧方案**已经解决过跨区无限世界**，现在这套**仍是确定性水文**。
> 因此本轮不做架构重写假设，只做「继承/丢失/收窄/不兼容」四类判定，优先恢复丢失能力。
>
> 证据来源：删除前快照 `b9b55ee`（只读 `git show`，不 checkout、不改工作树）
> 的 `riverline/RiverLineNetwork.java`、`riverline/RiverLineRegion.java`，
> 与当前 `model/ModelHydrologyProvider.java` + `model/DeterministicSkeleton.java`。

## 0. 结论先行

跨区断流的**直接原因已定位并修复**：不是"确定性做不到跨区"，是
`columnsFor` 的边界带判据四侧不对称（低侧 `<`、高侧 `>=`），
接缝那一行两侧 region 都不声明。详见 [HYDROLOGY.md 〇q](../HYDROLOGY.md)。

但对照发现 **model 相对 legacy 仍有三处真实收窄**（下表 M1–M3）。
它们目前**不是**这次断流的原因，却是"跨区看起来不如 legacy"的候选来源，
应在继续修生命周前先评估是否需要恢复。

## 1. 逐项对照表

| # | 能力 | legacy（`b9b55ee`） | 当前 model | 判定 |
|---|---|---|---|---|
| L1 | 区域独立重建 | pass-1 由 `(seed, rx, rz)` 唯一确定，`ConcurrentHashMap` 缓存 | 同：`regionPass1` 纯 `(rx,rz)` | ✅ 已继承 |
| L2 | 双 pass 结构 | `region()` 先收 8 邻 pass-1 的 `outlets`，再建本区 pass-2 | 同：`CROSS_PASS` + `collectIncoming` | ✅ 已继承 |
| L3 | 接力载荷 | `OutletSeed(dRX,dRZ, wx,wz, accum, surfaceY, level)` —— 世界坐标 + 汇流量 + 水面 + 层级 | `ExitSeed(cell, dRX, dRZ)` —— **仅窗口格索引 + 方向** | ⚠ **收窄（M1）** |
| L4 | 接力起点容错 | `bestHandoffStart`：种子格若为局部极小，1 格窗口内改选有真实下坡者，避免"续流即死" | `build` 里若 `covered[start]` 或湖/海即 `continue`；**无下坡容错** | ⚠ **丢失（M2）** |
| L5 | 逐点跨区查询 | `sampleAll(wx,wz)` **无条件**遍历 3×3 邻区 `sampleRegion` | `columnsFor` 只在 `nearBorder` 带内且本区无命中时才查邻区 | ⚠ **收窄（M3）** |
| L6 | 湖优先跨区裁决 | `sampleAll` 用 `inLakeDomainAt` 在 3×3 上做**真实湖域**判定后才剥河命中 | `p.lake()[k]` 只取本区；邻区仅 `rr.pipe().lake()[ti*nn+tj]` | ⚠ 部分收窄 |
| L7 | 缝上不重复画 | `slack=0`：两区各画到边界为止 | `SEW` 端点缝合（3 格桥）+ 邻区并集只加不减 | ⚠ 策略不同（非等价） |
| L8 | 湖面/出口权威 | `LakeNode` + `erodedWaterLevel` + `LAKE_ANCHOR_EXT` | `EquilibriumWater` + `LakeWaterBalance` + `basinOutlet` | 🔁 重写（语义换代） |
| L9 | 入湖即止（不切湖） | 用户 2026-09-22 裁定：折线不进湖，改把末节点水面抬到湖面 | 同：`MOUTH_LAKE` 只 `path.add(p)` 不 `covered` | ✅ 已继承 |

## 2. 三处收窄的具体影响

### M1 接力载荷只剩格索引 + 方向

legacy 的 `OutletSeed` 携带 `accum / surfaceY / level`，下游续流**继承上游水面与汇流量**
（注释：`使河流跨缝连续、宽度不重置（无颈缩）`）。当前 `ExitSeed` 只有 `cell/dRX/dRZ`，
下游用本窗 `p.discharge()` 与 `bilinear(p.waterSurface())` 重新起算。

- 影响：缝两侧水面/宽度**不保证连续**（本批已量化：河列 surface 恒取本窗双线性值）。
- 不是本次断流原因（几何已接上），但会让"接上了却错位/错宽"。
- 恢复代价：中。需把 `ExitSeed` 扩成携带世界坐标 + 水面 + 准入量，并在接收侧作为首节点约束。

### M2 接力起点没有"局部极小"容错

legacy 注释与代码都明写：邻区栅格相对上游偏移最多 16wu，种子格可能恰落局部极小
（无下坡 ⇒ 续流即死），故 `bestHandoffStart` 在 1 格窗口内改选有真实下坡的格。

当前 `build()` 对 `incoming` 只做 `covered` / 湖海 / 越界过滤，**没有下坡容错**；
`traceOne` 落到无下坡时走 `spillCell`，抬升超 `MAX_SPILL_RISE` 才 `DEAD_END`。
实测 evidence：owner(-1,0) 的 `incomingStarts=0~3`，`NO_RECEIVER` 与 `SELF_CROSS` 占失败主体。

- 这是**最可能被误认为"跨区机制不行"的一条**：接力种子到了，但起链即死。
- 恢复代价：低。属局部改选起点，不改拓扑语义。

### M3 邻区查询退化为边带条件

legacy `sampleAll` 对每个查询点都看 3×3 区域的实际河线。
当前只在"本区无命中 + 靠近边界带"时才查邻区（性能考虑，注释已写明"内部方块无需邻区"）。

- 这次修复只是把**带端点**补上（差一行），没有把 M3 变成全 3×3。
- 风险：若某条邻区折线**深入本区内部**（跨区接力段被画到内部），现行判据不会去查。
- 但这是**有意的性能取舍**，不是 bug；扩大前须先证明存在这种内部穿透样本。

## 3. 与"确定性"的关系（回答用户疑问）

- **确定性已成立**：`regionPass1`/`solve` 均由 `(seed, rx, rz)` 纯函数导出，
  `ExitSeed` 按 `Integer::compare` 排序，`incoming` 去重后确定性遍历。
  探针 `HydrologyDeterminismProbe` ALL PASS。
- **确定性 ≠ 跨区连续**。程序完全可以每次都在同一行断——这次就是这样：
  判据不对称是**确定地**把 z=608 那行排除掉。
- 所以"我们这还是确定性水文吗"→ **是**；问题在**接线判据**，不在确定性模型本身。

## 4. 建议顺序（不新增架构）

1. **先做 M2**（恢复 `bestHandoffStart` 等价容错）：代价最低，直接对应 `NO_RECEIVER`/接力即死，
   且不改拓扑语义，可用单变量 A/B 判据。
   → **已实施，见 [M2 实施记录](GeoGenesis-m2-handoff-start-20261022.md)。**
   结论：代码与判据全绿，但在当前默认样例上 **12 次接力全部原本就有下坡 ⇒ 0 次触发**；
   全图仅 10 px 差异。它是为特定失败形态准备的容错，**不是断流主因**，
   不能因此宣称跨区改善。同批数据还暴露"接力落地即被自交守卫判死"（2 条 `mouth==source`）。
2. **再评估 M1**（接力载荷带水面/准入）：解决"接上但错位"；需先有跨缝水面连续性判据。
3. **M3 暂不动**：现有边带是有意取舍；先取证是否存在"邻区折线深入内部"的真实样本。
4. 之后才回到未解决的 JOIN 自汇（HYDROLOGY.md 〇r）与湖/海生命周期闭合。

## 5. 未实测与边界

- 本对照是**静态代码比对**（删除前快照 vs 当前工作树），未运行 legacy 代码，
  也未做同 seed 的 legacy/model 图对照；"legacy 解决了跨区"依据是用户陈述 + 代码机制存在。
- 三处收窄的实际影响量级**未量化**（除 M1 的水面口径已在 §〇q 旁证）。
- 未跑 Forge 实机、未跑完整 WorldgenGate。
