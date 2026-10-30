# 侵蚀算子改进评审 — 2026-10-30

> 范围：**只评生产侵蚀算子**（`worldgen/erosion/**`）。丘陵 `DALES_LO` 已定稿，本文不涉及。
> 每条都标「证据等级」：**[实测]** = 本轮跑过探针/出过图；**[读码]** = 只读代码推断，未实测。

---

## 0. 现状：生产侵蚀**一直在运行**（先确认这一点）

`CellGenerator` 里的实际接线（已 grep 核实）：

| 位置 | 内容 |
|---|---|
| [CellGenerator.java:195](forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/terrain/CellGenerator.java#L195) | `this.erosion = new ErosionEngine(...)` —— 构造 |
| [CellGenerator.java:1266](forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/terrain/CellGenerator.java#L1266) | `RidgeValleyErosion.evaluateCell(...)` —— 骨架层逐点 |
| [CellGenerator.java:1321](forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/terrain/CellGenerator.java#L1321) | `erosion.runErosionOnFlat(...)` —— 液滴层主入口 |
| 配置 `erosionEnabled` | **默认 `true`** |
| 配置 `erosionRidgeEnabled` | **默认 `true`** |

> **本文全部建议都是"改进"或"修注释"，没有任何一条是"移除侵蚀"。**

| 组件 | 文件 | 角色 | 类型 |
|---|---|---|---|
| `RidgeValleyErosion` | 357 行 | **骨架层**：把圆包噪声切成多峰山脊 | 梯度对齐条纹滤镜（逐点纯函数） |
| `ErosionEngine` | 980 行 | **细节层**：液滴模拟打磨沟谷 | 粒子模拟（tile 缓冲） |
| `RefNoise.Erosion` | 沙箱 95 行 | 参考复刻的第三套 | 最近邻线段距离场（**仅沙箱，不在生产**） |

管线顺序（`CellGenerator`）：粗地形 → **RidgeValleyErosion 骨架** → 填块 → **ErosionEngine 液滴** → 双平滑。
实测耗时（768 块视野，384² 采样）：骨架 **146ms** / 液滴 **1644ms** / 沙箱 RefNoise **655ms**。**[实测]**

---

## 1. 已知缺陷（按影响排序）

### 1.1 骨架层在低地不触发（**不是"低地没有侵蚀"**）**[实测]**

> ⚠ **2026-10-30 修正**：本节初稿写成"低地侵蚀完全缺失"，**表述错误**。
> 准确事实：**液滴层 `ErosionEngine` 在低地照常运行**（按坡度撒点、按高差侵蚀），
> 只是**骨架层 `RidgeValleyErosion` 不在低地切条纹**。两层是叠加的，不是替代关系。

`RidgeValleyErosion.evaluateCell`（[RidgeValleyErosion.java:144-162](forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/erosion/RidgeValleyErosion.java#L144-L162)）：

```java
float fadeTarget = 0f;
if (h > cfg.landRef) { ... }                  // landRef 默认 0.15
float hBoost = 2f + 6f * smoothstep(0.2f, 0.35f, h);   // h<0.2 → boost=2，h>0.35 → boost=8
float gxs = gx * hBoost * landMask;
```

**证据**：`erosion-operators.png` 第 ⑤ 格——输入整个是低地（e≈0.06~0.24），骨架输出**几乎与输入相同**（图上无变化）。
注释里也承认这个设计：「低地（h<0.2）boost=2 → 低地无条纹，用户"过头"修复」（2026-08-12）。

**这是有意的设计选择**（用户当时嫌低地大条纹"过头"），不是 bug。
**影响（准确版）**：低地缺少"山脊骨架"这一层的形态贡献，地形偏平；但液滴侵蚀仍在工作。

### 1.2 液滴侵蚀只有 2 轮迭代，动量场累积有限 **[读码]**

`erosionIterations` 默认 **2**（注释：「2026-08-11: 5→2, deep-gully fix；SH 原版 numIterations=1」）。
注释说"2 keeps momentum field accumulation"，但 2 轮对动量场（`momX/momY` 跨轮累积）的收敛很有限。
参数 5→2 是为了修"深沟"，说明**更多迭代会过度下切**——根因可能是 `erosionLrate`（0.1）与 `localCharge` 的组合。

### 1.3 ★★ `localCharge` **类头注释在说谎**（不是"配置说谎"，是"注释说谎"）**[实测：已 grep 全文]**

三处互相矛盾：

| 位置 | 说法 |
|---|---|
| 类头 [ErosionEngine.java:13](forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/erosion/ErosionEngine.java#L13) | 「三尺度层叠 + **localCharge 正反馈** + 局部 cascade 级联」 |
| 类头 [ErosionEngine.java:18-19](forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/erosion/ErosionEngine.java#L18-L19) | 「每粒子携带累计高差 localCharge，容量公式乘以 `(1 + α·localCharge)`，使主流深支流浅」 |
| 内部注释 [ErosionEngine.java:319](forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/erosion/ErosionEngine.java#L319) | 「erosionRiver* / erosionLocalChargeWeight **配置保留但引擎不再读取**」 |

**实际代码：全文 `localCharge` 只出现在这三处注释里，零处实现。**
`grep localCharge src/main/java/com/geogenesis/worldgen/` ⇒ 只有这 4 行注释命中。

**后果**：任何人（包括我本轮）读类头都会以为存在这个正反馈机制，从而错误推断"主流为什么比支流深"。
这是 AGENTS.md 点名的「死代码 + 声称它在生产路径上的注释」最恶劣形态。

**修法（二选一，都是零形态变化）**：
- **删**：把类头第 13/18-19 行的 localCharge 条目改写成"**已移除**，原因：…"，并保留领域知识
  （"主流深支流浅"的机理现在由 `dis` 场 momentum 偏置 + flowGate 部分承担）
- **接**：真按注释接进 `c_eq`（`ErosionEngine.java:704`），但这是**形态改动**，需 A/B 对拍

另：`erosionLocalChargeWeight` 配置项（默认 1.0）同样需要删或接。

### 1.4 `cascadeLocal` 是 O(8²) 冒泡排序，每步调用 **[读码]**

`CASCADE_INTERVAL = 1`（每步都跑），`cascadeLocal` 内部对 8 个邻居做**冒泡排序**（[ErosionEngine.java:801-807](forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/erosion/ErosionEngine.java#L801-L807)）。
每个液滴每步都调用 ⇒ 液滴总数 × 寿命 × 28 次比较。注释说"num=8 足够了"，但**没有实测过它对总耗时的占比**。
建议：① 改成插入排序或部分选择（只要最小的几个，不必全序）；② 或者 `CASCADE_INTERVAL` 允许 2。

### 1.5 `smoothErosionResult` / `smoothDepositionZones` 双平滑会抹掉细沟 **[读码]**

`radius=3, smoothingRate=0.4` 的方圆平滑作用在**所有**点上。而 XS 微侵蚀层（r1 笔刷、1–2 块级）本就是为了补细节——
两轮平滑很可能把 XS 的成果抹掉大半。注释也提到 XS「amplifies cross-chunk birth-set discreteness」而默认关闭。

### 1.6 `ErosionEngine` 与 `RidgeValleyErosion` 各自独立标定，无共享的尺度概念 **[读码]**

- 骨架：`cellWorldSize=100`（条纹细胞）、`spacing=2`、`octaves=4`
- 液滴：四尺度 R=7/4/2/1、`grid` 无（笔刷半径即尺度）
- 两者的"特征尺度"没有对齐机制：骨架给 100 块条纹，液滴的 C 尺度（R7≈15 块直径）在 20 步内走不到 100 块 ⇒ **液滴打磨不到骨架的条纹周期**，两层可能各自独立运作而非协同。

---

## 2. 改进提案（按投入产出排序）

### P0. 修掉 `localCharge` 注释说谎（**先做这个**）**[实测确认]**

类头承诺了一个不存在的机制（§1.3）。**零形态变化**：只改注释与配置，
或者反过来把它真正接进 `c_eq`（那才是形态改动，需对拍）。

**为什么排第一**：它现在正在**误导诊断**——本轮我读类头时一度以为"主流深支流浅"来自 localCharge，
差点据此错误归因。AGENTS.md 教训：「死代码本身不可怕，**死代码+声称它在生产路径上的注释**才可怕」。

### P1. 骨架层在低地分级触发（**最大形态收益**）**[实测支撑]**

现状低地只有液滴侵蚀、没有骨架条纹。做法**不是**直接放开 `hBoost`
（2026-08-12 已否：低地 ±12 块大条纹"过头"），而是**按地形类型分级**：

```
低地（h < 0.2）：cellWorldSize 100 → 40，strength 2.0 → 0.6，只切浅沟（±1 块）
山地（h > 0.35）：保持现状 100 / 2.0
过渡带：两套参数平滑插值
```

这样低地得到"细密浅沟"而不是"大条纹"。**液滴侵蚀不受影响，继续照常跑。**
风险：`cellWorldSize` 变化会改变条纹相位 → 需要重出 1401² 对拍。

### P2. 新增"侵蚀量可选输出"做 A/B **[读码]**

现在 `runErosionOnFlat` 只返回改后的 `flat`，外部无法知道"哪里被蚀了多少"。
加一个可选 `erodedDeltaOut`（与已有的 `dischargeOut` **同一模式**，签名已有三重载可扩展），
代价为零，却能让所有对拍脚本直接量化侵蚀分布——本轮写诊断图时就缺这个。

### P3. 液滴：把 `cascadeLocal` 的排序换掉 **[读码]**

`num=8` 时插入排序约 20 次比较 vs 冒泡 28 次；但更大的收益是**只找最小 3 个**（部分选择，~15 次）。
预期总耗时降 5~15%（未实测）。**逐位不变的替换不存在**（改变排序顺序会改 transfer 顺序 → 数值微变），需 A/B 对拍。

### P4. 两层尺度对齐 **[读码]**

让液滴的 C 尺度寿命/步长足以跨越骨架条纹周期的一半（50 块）：
现在 `LIFE_C=20 × spdCap` 大约走 20~40 块，差一点。把 `LIFE_C` 20→30 候选
（注释说无缝上限是"缓冲余量 47"，30 < 47 仍安全，但需重跑无缝验证）。

---

## 3. 我**不建议**的改动

| 想法 | 为什么不做 |
|---|---|
| 换成流功率侵蚀（`applyStreamPower`） | 已在 2026-07-31 删除，注释记录"流功率方案未完成"；重走老路无新证据 |
| 把沙箱 `RefNoise.Erosion` 搬进生产 | 它的最近邻线段在低起伏地形上出放射星（[实测]）；生产已有更好的液滴+条纹两件套 |
| 提高 `erosionIterations` 到 5 | 2026-08-11 从 5 降到 2 是为了修深沟；无新证据前不应回退 |
| 开 `erosionRangeX`/XS 层 | 注释实测"near chunk borders +1.6 blocks"离散伪影；默认关闭是有依据的 |

---

## 4. 建议的下一步（等用户选）

| 编号 | 动作 | 风险 | 预期收益 |
|---|---|---|---|
| **A** | 做 P0（清掉 localCharge 注释说谎） | 零 | 消除误导（本轮我自己踩了） |
| **B** | 做 P2（侵蚀量输出）再做任何形态改动 | 零 | 让后续 A/B 可量化 |
| **C** | 做 P1（低地骨架分级） | 中（改形态） | **最大**：补上低地侵蚀空白 |
| **D** | 做 P3/P4（性能/尺度对齐） | 中（逐位变） | 中 |

> 我倾向 **A → B → C**：先把零风险的做完（注释诚实 + 可观测），再做唯一会改形态、收益也最大的 P1。
> P1 会改变全部地形产出 ⇒ 必须升 `CACHE_SCHEMA_VERSION` 并重出 1401² 与"用户认可的标准图"对拍。
