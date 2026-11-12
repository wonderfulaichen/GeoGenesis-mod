# 海洋与海岸地形 —— 需求、结构梳理与规划（2026-11-12）

> **本文只做梳理与规划，不含任何代码改动。**
> 目的：把"海洋/海岸到底想要什么、现在是什么、差距在哪"一次说清，
> 让后续每一步都有据可依，而不是凭印象开工。
>
> 全部结论都有 `文件:行号` 出处；实测数字标"实测"。

---

## 一、先纠正一个我上一轮的错误

上一轮我说「最深处只有 60 格，而海侧量程 127 格，**一半量程用不上**」。
**这个判断是错的**，实测后更正：

| 量 | 实测 | 说明 |
|---|---:|---|
| 最深 e | −0.4751 | |
| **最深实际深度** | **60.3 格** | |
| 设计目标 `OCEAN_DEPTH` | **63 格** | `MorphOceanAssembler.java:53` |
| 达成率 | **95.8%** | **已达标，不是"浪费"** |
| 理论容量 `e=−1` | 127 格 | **代码从设计上就不打算用满** |
| **平均海深** | **14.1 格** | ← **真正的问题在这** |

⇒ 海洋**不是"不够深"，而是"普遍太浅"**：最深能到 60 格，但平均只有 14 格，
54.7% 的海面落在 0–9 格。真实地球的大洋是"广而深"，我们是"浅滩为主"。

`127` 格是 `HeightCurve` 的 `seaLevelY − minY = 63 − (−64)`，
`OCEAN_DEPTH=63` 是刻意的"MC 游戏性不宜太深"设定
（`TerrainParams.java:163` 有原文：「deepOcean depth 从 −0.85 减至 −0.35，**适配 MC 游戏性不宜太深**」）。

---

## 二、需求侧：用户到底要什么

### 2.1 ⚠ 先暴露一个结构性问题

**权威需求记录里没有海洋。**

- `audit/用户需求记录-2026-07-15.md`：全文 8 255 字节，`海洋|海岸|海底|大陆架|深海|海山|洋中脊|海滩` **零命中**。
- `HYDROLOGY.md`：海洋只作为"河流终点 + 固定水面"，无地貌需求。
- `AGENTS.md` / `ARCHITECTURE.md`：无海洋地貌需求。

真正的海洋/海岸需求，**几乎全部躺在被 gitignore 的 `.openbitfun/plans/` 里**：

| 文件 | 大小 | git 状态 |
|---|---:|---|
| `.openbitfun/plans/ocean-unified-terrain.plan.md` | 42 077 B | **未追踪（被忽略）** |
| `.openbitfun/plans/ocean-type-unification.plan.md` | 22 894 B | **未追踪（被忽略）** |
| `.openbitfun/plans/base-terrain-upgrade-research.plan.md` | 61 257 B | **未追踪（被忽略）** |

> 注：`.openbitfun/` 整体在 `.gitignore:121`，但这个目录下**有 8 个 plan 是被强制追踪的**
> （`git ls-files` 可见，如 `world-shape-engine-unification.plan.md`）。
> 也就是说"哪些进了版本库"是随机的，取决于当时有没有 `git add -f`。
>
> ⇒ **风险评估**：这三份（尤其 42 KB 那份）是海洋需求的唯一载体。
> 一旦工作区丢失/clone 新副本，海洋需求的用户裁定就**只剩代码注释里的碎片**。
>
> 这是本次梳理发现的**最该优先处理的一件事**，且与写代码无关——见 §五 P0。

### 2.2 已确认的用户裁定（按重要性）

#### ① 类型的定义 = 现实地貌学（总纲）

> 「模组的地形类型等于现实里面的地质学地形地貌定义。」
> 「地形类型就等于现实里面地质学的地形地貌，海岸类型就是海岸地貌。」
> —— 2026-11-01，`ocean-unified-terrain.plan.md:53,158`

这条决定了**加类型必须有地貌学依据**，不能"我想多加几种"。

#### ② 海岸线由高度涌现，不由 `c` 划分

> 「不要把 c 当成分界线，而是像陆地一样，用来做类型选择的，海陆实际看地形高度变化，
> c 不决定海陆分界线，由实际地形高度来确定哪里是陆地哪里是海洋，现实世界也是这样的。」
> —— 2026-11-01，`ocean-unified-terrain.plan.md:200-205`

**当前代码符合这条**：`MorphOceanAssembler` 用 `edge`（由 `c` 映射）只决定**剖面位置**，
水线是 `e` 穿 0（`e()` 在 `blocks>=0` 处切分陆/海换算）。
⇒ 这条是**已成立的既有资产，不要动**。

#### ③ 海岸是"特殊过渡类"，不是第三种内部类

> 「海岸类型我想了下这个应该是过渡地形类型，但比较特殊。因为它有海有陆。」
> 「海岸既有内部类型的特点（形态各异）又有过渡类型的特点（海陆交界）」
> 「这些海蚀崖、海蚀柱、海蚀栈道我们用纯函数能实现？」
> —— 2026-11-01，`ocean-unified-terrain.plan.md:87-94`

且 **海岸地形主体在陆地上**（用户纠正过一版）：
> 「海岸地形还在陆地上，只是位置会生成在内部陆地与海洋之间。」
> —— `400__candidate__CoastRecipeCatalog.java:10-21`

#### ④ 海洋内部类型必须有自己的形态，不能只是深度标签

> 「**不把海洋仅当"负高度平原"**」
> 「**海洋也有地貌结构。深海盆、脊、海山、陆坡和陆架应有独立尺度；海岸不必处处是一条同宽沙滩**」
> —— `base-terrain-upgrade-research.plan.md:152,245`

目标分类（5 内部海洋类型 + 5 内部海岸类型）：
```
海洋 5：ABYSSAL_PLAIN / MID_OCEAN_RIDGE / SEAMOUNT_FIELD / CANYON / SHELF_RIDGE
海岸 5：SANDY_BEACH / SEA_CLIFF / REEF_FLAT / TIDAL_FLAT / DELTA
```
（`ocean-unified-terrain.plan.md:185-186`）

#### ⑤ 海岸不许"刀切"，且高度按地貌学给

> 「陆地层的岸坡也一并处理，不要刀切感。不然旁边是很高的高山直接切这样不自然。」
> 「不过那阈值过渡还是不太好，也许可以改成变化曲线。」
> —— 2026-10-30，`MorphOceanAssembler.java:313-317`

高度带（`ocean-unified-terrain.plan.md:502-506`）：
`SANDY_BEACH −3..+17` / `SEA_CLIFF +5..+71` / `REEF_FLAT −7..+19` / `TIDAL_FLAT −6..+8` / `DELTA −4..+14`

#### ⑥ 明确推迟（**不是遗漏**）

> 「目前来说我们的粒子侵蚀是模拟的降雨对地形的横向水平的 2d 侵蚀，没有竖向影响，
> 更多像海蚀、风蚀等这些，我们还没实现。**不过我也不打算现在实现，后续再实现。**」
> —— 2026-11-01，`ocean-unified-terrain.plan.md:142-146`

⇒ 海蚀崖/海蚀柱/海蚀洞属于**用户明确推迟**，不是设计缺陷。不要擅自开工。

#### ⑦ 海山"要，但要稀疏"

> 「2026-08-06 调稀：**用户反馈海山太多**」→ 概率 40%→20%
> —— `OceanFeatures.java:28`

#### ⑧ 岛弧：被否决的是形态，不是出水（2026-11-12 更正）

原文 `GeoGenesis-20261030.md:173-174` 的否决表两行：

| 第一列（**当年助手写的路线名**） | 第二列（**用户原话**） |
|---|---|
| Worley / 噪声自由撒岛做**群岛** | 「不行，你这群岛类型的地形完全不对，你这个完全像一个台地一样。」 |
| **弧峰出水**做**岛弧** | 「不行你这样太费劲了。完全破坏了原本的地形。」 |

⇒ **「弧峰出水」是助手写的路线名，不是用户的话。** 用户否决的是**那版形态**
（做成台地状、破坏原地形），**从未讨论能不能出水**。

所以「D4 岛弧是否允许出水」是一个**虚构的决策项**，已从两份 audit 文档删除。

事实是：**岛弧按地貌学定义本就会出水成岛**（日本列岛、阿留申群岛、马里亚纳群岛）。
`OceanFeatures.java:200-225` 的 `cap = −eOcean − MARGIN` 是代码作者
防浅海随机露头加的安全限制，**不是用户要求**。

⇒ 岛弧应按正常地貌设计：弧形排列、沿走向离散成链、锥状火山而非台地、允许峰顶出水。

---

## 三、结构现状：生产路径真的在跑什么

### 3.1 生产调用链（已逐跳核实）

```
ChunkGenerator.fillFromNoise            GeoGenesisGenerator.java:940
  → terrain.getChunkCells               GeoGenesisTerrain.java:333
    → generator.sample(toWu(...))       GeoGenesisTerrain.java:507
      → morph.MorphCellAdapter.from     CellGenerator.java:299
        → baseWorld.sample              BaseWorldSampler.java:129
          → terrain.sampleColumn        BaseWorldSampler.java:118
            → ocean.e(x,z)              MorphTerrainSampler.java:136
              → heightBlocks            MorphOceanAssembler.java:618
                → coastalProfile(629) ← 生产恒走这条（coastalDetail 无条件 true）
```

`coastalDetail` 在 `MorphTerrainSampler.java:116-117` 构造时**无条件启用**：
```java
this.ocean = new MorphOceanAssembler(land::e, continent::sample, seed + OCEAN_SEED_OFFSET)
        .enableCoastalDetail(seed);
```

### 3.2 海洋高度 = 三段剖面（这是全部）

`MorphOceanAssembler.coastalProfile`（`:287-308`）三段：

| 段 | 判据 | 高度来源 |
|---|---|---|
| 深海 | `edge < cpShelf()≈0.331` | `deepBlocksRaw` ∈ [−63, −21]，Perlin 深浅 + canyon |
| 浅海/陆架 | `cpShelf ≤ edge < cpShore()≈0.444` | `lerp(−7, 0, smooth(t))` + 细节噪声 |
| 海岸/陆地 | `edge ≥ cpShore` | `shoreShape`：`landB(236/e)` × 形状 − 下沉 1 格 |

**海岸的两个调节量**（这是"不许刀切"的既有实现）：
- `shoreWidth = 0.085 × (1 + 0.45·min(1, landB/110))` — 山越高岸坡越长
- `cliffFactor` ∈ [−0.45, +0.45] 决定沙岸/崖岸（`:244-246` 注释目标：沙岸≈30–40%、岩岸≈50–60%）

### 3.3 `OceanFeatures` 的位置：**未接线的半成品**（不是 bug）

> **前置事实（用户已说明）**：完整世界地形**做过、没成功**。
> `OceanFeatures` 就是那次迁移的中间产物——几何写好了，但**从未接进生产链**。
> 所以下面这些"零命中"不是新发现，是那次停下的**残留状态**。

四条证据（用于确认它确实没在跑）：

1. `new OceanFeatures(` 在 `src/main` **零命中**（只在 `:20` 类声明与 `:49` 构造器）。
2. `CellGenerator.classify()` 声明于 `:626-680`，**全仓零调用者**（连 `src/diagnostics` 都没有）。
3. `Cell.oceanFeat` 声明于 `Cell.java:61`，`src/main` **从未赋值**；
   唯一赋值是诊断探针 `HydrologyHeightSnapshotProbe.java:774`。
4. `Cell.landFeat` 同样从未赋值 ⇒ `volcanoClass(null)` 恒 `null` ⇒ `VOLCANO` 永不产出。

⇒ 洋中脊/海山/火山弧/海沟的几何**存在于代码里，但不在生产路径上**。

**处置方式只有两个**：接上（D3=接线）或删掉（D3=删除）。
**不存在"修一下"这个选项**——它没有坏，它只是没接。

### 3.4 `TerrainClass` 的产出真相

| 类型 | 生产会产出？ | 证据 |
|---|---|---|
| `OCEAN` / `DEEP_OCEAN` / `CONTINENTAL_SHELF` | ✅ | `MorphTerrainSampler.java:190-192` |
| `SUBMARINE_RIDGE` / `SEAMOUNT` | ❌ | 只在死的 `classify()` 里赋值 |
| `BEACH` | ❌ | 无任何赋值 ⇒ `GeoGenesisGenerator.java:1060` 的 `beach` 恒 false |
| `LAKE` / `RIVER` / `VOLCANO` / `VOLCANIC_FIELD` / `PEAK` / `SNOW` | ❌ | 同源 |

⚠ **`BEACH` 恒 false 是一个实际后果**：`GeoGenesisGenerator.java:1085-1087` 那个
「BEACH → SAND」分支是死的，沙滩色实际由 `water` 分支（`:1082-1084`）给出。
这不一定是 bug（结果可能一样），但**枚举宣称与实际不符**。

而 `MorphTerrainSampler.java:190-192` 的分类判据是**纯深度阈值**
（`−0.08` / `−0.18`）⇒ **和既有检查器的结论一致：
"海洋类型"目前只是 `eLand` 轴上的分带，不是独立形态。**

### 3.5 配置面：海洋参数基本是死的

- `TerrainParams` 的 10 个海洋数值（`deepOceanDepth` 等）→ `HeightCurve.eFromC`
  → **唯一调用者是 `seaE()`**（`HeightCurve.java:83`）⇒ 只影响海平面 e，不影响海底形态。
- `OceanSplineConfig` 84 字段同理。
- `MorphOceanAssembler` 里 **12 个常量无人读取**：
  `CP_COAST`、`FLAT_END_*`、`STEEP_END_*`、`FLAT_BLOCKS_*`、`BEACH_TOP_BLOCKS`、
  `OCEAN_DETAIL_SCALE`、`COAST_HANDOFF`。
- **没有任何配置能调**：海深、陆架宽度、沙滩宽度、崖/沙比例、海洋细节。
  这些全硬编码在 `MorphOceanAssembler`。

### 3.6 原版免费提供（不要重造）

`applyBiomeDecoration` 委托 `super`（`GeoGenesisGenerator.java:1323`），
且 `VanillaDecorationFilter` **不筛任何海洋特征**（只筛 9 个岩块团块 + 18 个金属矿）。
`BiomeClassifier` 会产出 8 种海洋群系 ⇒ **海带/海草/珊瑚/海泡菜/海底神殿/沉船/海底废墟
一直在生成**。深海/浅海/暖海/冷海的差异，目前**主要靠原版装饰体现**。

---

## 四、差距汇总（需求 vs 现状）

| 用户要的 | 现状 | 差距 |
|---|---|---|
| 海洋 5 种内部地貌 | 只有 depth 分带（3 种标签） | **全缺**（`OceanFeatures` 死代码） |
| 深海要有结构（盆/脊/山/坡/架独立尺度） | 深海有 Perlin 起伏，但**无命名的地貌单元** | 部分有，未成型 |
| 海岸 5 种地貌 | 只有沙岸/崖岸两档（cliffFactor 连续） | 有雏形，缺 5 类 |
| 海岸不要刀切 | ✅ 已实现（`shoreWidth` 随高度增长） | **已达标** |
| 海岸高度按地貌学 | 有 `landB` 自适应，但无 5 类高度带 | 部分 |
| 火山弧/海沟 | 有几何（`oceanProfile`），**生产未接** | 全缺 |
| 海洋普遍深度合理 | **平均 14 格、54.7% 在 0–9 格** | **偏浅** |
| 岛弧 | 有弧几何但被 `cap` 压在水下；否决的是**形态**不是出水（见 §2.2⑧） | 按地貌重设计 |
| 海蚀崖/柱/洞 | 用户明确推迟 | **勿动** |

---

## 五、分阶段规划（**建议顺序**，每阶段独立可验收）

### P0 · 抢救需求记录（纯文档，**建议最先做**）

**理由**：海洋需求的唯一载体在被 gitignore 的目录里。这比任何代码改动都优先。

- 把 `ocean-unified-terrain.plan.md` 的**决策部分**（§〇 用户裁定 + §〇.2 分类 + §〇.3 海岸线规则 + 5+5 高度带）
  摘录入 `audit/`（入库），原文件保留为过程记录。
- 同法处理 `ocean-type-unification.plan.md` 的"海洋类型=分带"证据。
- 产出：`audit/GeoGenesis-海洋海岸需求基线.md`
- **判据**：`audit/` 下任一文件即可回答"海洋要几种类型、海岸要几种、海岸线怎么定、哪些已推迟"。
- 风险：0（不动代码）。

### P1 · 决策澄清（**必须用户裁定，不能由我代定**）

三个悬而未决的路线选择，做任何一项之前必须定：

| # | 问题 | 选项 | 影响 |
|---|---|---|---|
| D1 | 海洋类型要不要**独立形态**？ | 甲：承认就是深度分带，删死配置<br>乙′：要独立形态，改 `e` 构成<br>丙：先补形态不动拓扑 | 甲=删代码；乙′=大工程+改产出 |
| D2 | 「平均海深 14 格」要不要加深？ | 加深 / 保持（游戏性） | 改变海洋观感与可玩性 |
| D3 | `OceanFeatures` 接线还是删除？ | 接线（洋中脊/海山回归）<br>删除（承认只有深度梯度） | 接线显著改观感；删除要清 `TerrainClass` |

> 既有检查器 `ocean-type-unification.plan.md:256-273` 早就把 D1 列为"必须由用户裁定"，
> 至今无人回答。**这是整条线卡住的真正原因。**

### P2 · 清理死接线（D3 定"删除"时）

- 删 `CellGenerator.classify()`（`classifyTerrain` 保留，它仍被 `applyProcessDelta` 用）。
- 删 `Cell.oceanFeat` / `landFeat`。
- 删 `TerrainClass.SUBMARINE_RIDGE` / `SEAMOUNT`（若 P3 不做）⇒ **注意枚举序号变化会改配色表**。
- 删 `MorphOceanAssembler` 的 12 个死常量。
- **判据**：`runWorldgenGate` 全绿 + 产出逐位不变（digest 对拍）。

### P3 · 海洋地貌（D1/D3 定"要做"时）

- 深海结构：把 `deepBlocksRaw` 的 Perlin 提升为**命名的地貌单元**
  （深海平原/洋中脊/海山群/海底峡谷）。
- 关键约束（既有铁律）：不能"最近邻配对"（`AGENTS.md` §⛔ 3），
  必须连续加权；不能改海陆比；不能把海山抬出海面
  （`OceanFeatures.java:200-202` 的安全性证明要保留）。
- **判据**：海洋类型在**剖面图**上可区分（不是俯视图），
  且海洋类型交界**无阶跃**（`world-shape-engine-unification.plan.md:57-69` 记录过 5 块跨 15.8 格的硬切缝）。

### P4 · 海岸 5 类（最大工程，最后做）

- 从 `cliffFactor` 单轴扩展为 5 类海岸地貌，按 §2.2⑤ 的高度带。
- 必须保留：`shoreWidth` 随高度增长（不许刀切）、岸线位置不漂移（`H(0)=0`）、
  海滩带无浅海细节噪声（`MorphOceanAssembler.java:462-464`）。
- **判据**：剖面图 + 海陆翻转像素 = 0 + 岸边 |grad| P99 不劣化。

---

## 六、明确不做

- ❌ 海蚀崖/海蚀柱/海蚀洞（用户明确推迟，`ocean-unified-terrain.plan.md:142-146`）
- ❌ 把岛弧做成**台地/连续脊**（这才是当年被否的形态，见 §2.2⑧）
- ❌ 动"海岸线由高度涌现"这条规则（既有资产，动它会退回 v1 错误）
- ❌ 动"海岸不要刀切"的 `shoreWidth` 机制（已达标）
- ❌ 把 `CONTINENTAL_SLOPE` 当内部类手写（既有检查器已证明它是复合族自动涌现的）
- ❌ 把海底峡谷冒充俯冲海沟（`world-distribution-geometry-coast.plan.md:248` 明令禁止）

---

## 七、需要你拍板的三件事

1. **P0 要不要现在做？**（把海洋需求从被忽略目录抢救进 `audit/`）
2. **D1/D2/D3 怎么定？**（海洋类型是否要独立形态 / 海深是否加深 / `OceanFeatures` 接线还是删）
3. **P2 清理死接线是否独立于 P3？**（即：即使暂时不做海洋地貌，也先把"宣称有 5 种实际只有 3 种"的不一致修掉）