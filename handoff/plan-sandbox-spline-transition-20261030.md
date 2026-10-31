# 方案：沙箱地形类型过渡改用样条机制（待裁定，不动代码）

> **依据的是设计文档**，不是当前生产代码的实现细节：
> `.codebuddy/plans/unified-nested-spline-system.md`（三层嵌套样条系统设计）
>
> **前两份方案已删除**，因为它们把「生产当前的 WIE 实现」误当成了「你要的样条机制」。

---

## 〇、先说清楚：之前错在哪

| 我此前所说的 | 你要的（设计文档原文） |
|---|---|
| 过渡驱动量是 `edge`（Voronoi 距边界） | 过渡在**样条坐标系**里完成，`edge` 根本不参与 |
| `typePosition` 已废弃 | 中层样条就是 `typePosition` 轴，是 **Phase 2 的核心** |
| `lo/hi` 是常量 | `InnerSpline` 是 `loSpline/hiSpline`，**`lo`/`hi` 是噪声的函数** |
| 要引入 `TerrainCharacterField` 的 5×5 高斯空间加权 | 设计里**没有这层**——类型由 `c` + `typePosition` 决定，不靠空间加权 |
| 现在的 `lerp(border, selected, edge)` 就是"值插值"，已经够用 | 值插值**不是**形态过渡 |

**核心区分**（这次我抓准了）：

```
值插值（沙箱现在）：  h = lerp(h_平原, h_丘陵, t)     ← 两个【成品高度】混合
                                                      → 丘陵的形态被平均掉
样条过渡（文档）：    h = lo(t) + (hi(t) − lo(t))·n   ← n 是【共用噪声】
                      lo/hi 在相邻类型间样条插值        → 形态保留，只是包络变形
```

**`n` 是共用的**，所以过渡时山丘仍是山丘，只是被压进变形后的包络里。这就是"形态自然过渡"。

---

## 一、三层嵌套的机制（文档 §3.1 / §3.2）

### 1.1 坐标系逐层切换

```
外层样条：坐标 = 大陆性 c      ∈ [-1, 1]
          控制点 = {-0.80, -0.50, -0.16, -0.04, 0.20, 0.50, 0.80}
          输出   = 一个中层样条（该 c 位置上的类型分布）

中层样条：坐标 = 类型轴 typePosition ∈ [0, 1]
          控制点 = {0.0, 0.25, 0.5, 0.75, 1.0}   ← 5 个类型的位置
          输出   = 一个内层样条（该类型位置上的形状）

内层样条：坐标 = 噪声值 n      ∈ [0, 1]
          控制点 = {0.0, 1.0}
          输出   = lo(n) 与 hi(n)
```

**每一层都换坐标系**——这是文档 §3.2 对标 MC `offset.json` 的关键：

```json
{ "coordinate": "continents",     ← 外层
  "points": [{ "location": -1.0,
    "value": { "coordinate": "erosion",   ← 中层换坐标系
      "points": [{ "location": -1.0,
        "value": { "coordinate": "ridges", ← 内层再换
          "points": [
            {"location": -1.0, "value": -0.15, "derivative": 0.0},
            {"location":  1.0, "value":  0.2,  "derivative": 0.0}
          ] } }] } }] }
```

### 1.2 插值算法本身：Cubic Hermite（不是线性）

`SplineUtil.splint`（`:37-78`）逐字节对齐 MC `Spline#splint`：

```java
h00(t) = 2t³ − 3t² + 1        // 起点值
h10(t) = t³ − 2t² + t         // 起点导数
h01(t) = −2t³ + 3t²           // 终点值
h11(t) = t³ − t²              // 终点导数

return h00(t)·v0 + h10(t)·(d0·dx) + h01(t)·v1 + h11(t)·(d1·dx);
//                                              ↑ 导数乘以段宽（MC 特性）
```

**关键在导数**：每个控制点有 `derivative`，所以过渡是 **C1 连续**（斜率也连续），不是折线。

**这正是"样条"相对"线性插值"的意义**：

| | 线性插值 | 样条（Cubic Hermite） |
|---|---|---|
| 连续性 | C0（值连续，斜率突变） | **C1（值 + 斜率都连续）** |
| 过渡带观感 | 到控制点处出现折痕 | 平滑弯曲，无折痕 |
| 控制手段 | 只有 location / value | 多一个 **derivative** 可调 |

文档 §10.4 提到的旧系统用 `Catmull-Rom`，`SplineUtil` 现在是 `Cubic Hermite`——两者都是 C1，但 Hermite 的导数**显式给定**（可调），Catmull-Rom 是自动算的。

---

## 二、沙箱现在与新方案的差距（只列事实）

| 当前沙箱 `CombineCandidate` | 样条方案 |
|---|---|
| `RegionPartition.sample()` → `(regionId, edge)` | 需要 **`c`** 与 **`typePosition`** 两个坐标 |
| `selectIndex(regionId, N)` 取**单个**族 | `typePosition` 在类型轴上，输出**内层样条**（lo/hi） |
| `family.height01(x,z)` 直接是成品高度 | 各族只提供 **`lo`/`hi`**（形状包络），不是成品 |
| `lerp(border, selected, edge)` | 三层样条插值 |
| 无导数概念 | 每个控制点有 `derivative` |

**最根本的一条**：沙箱的族是"成品高度函数"，样条方案的族是"包络 `lo/hi`"。**族要重新表述**。

---

## 三、方案（三个 Phase，对应文档 §9）

### Phase 1：2 层嵌套（c → 形状）

**目标**：先不碰类型分布，验证"样条过渡"本身比"值插值"自然。

```
外层样条（c）→ 直接输出内层样条（lo/hi）
                 ↑ 暂时只有一种"类型"，即所有 c 都指向同一套 lo/hi 配方
```

**要新建**：
- `SplineAxisCandidate`：单条 Cubic Hermite 样条（复用 `SplineUtil.splint` 的算法，但沙箱零 MC 依赖）

**验收**：
- 单点对照：给定 `c`、`n`，输出与手算 Hermite 一致
- 导数生效验证：把 `derivative` 从 0 改到某个值，过渡带形状应变化
- **出图**：同一 `n` 场，用"值插值"vs"样条插值"两版地形对比

### Phase 2：3 层嵌套（c → 类型 → 形状）

**目标**：加入中层样条，类型在 `typePosition` 轴上过渡。

**要新建**：
- `MidSplineCandidate`：`typePosition` → `InnerSpline` 的映射
- 各族的 `lo`/`hi` 参数（先取参考 `SplineConfig.defaults()` 的值，见 §4）

**验收**：
- `typePosition = 0.0/0.25/0.5/0.75/1.0` 时，输出应精确等于对应族的 `lo/hi`
- `typePosition` 在两类型之间时，`lo/hi` 应为样条插值（不是线性的中点）
- **出图**：`typePosition` 从 0 扫到 1，画 `lo(typePosition)` 与 `hi(typePosition)` 曲线

### Phase 3：接进沙箱海陆

**目标**：`OceanAssemblyCandidate` 与样条输出衔接。

> 生产做法（`TypeLandShape:214,223-226`）：海洋类型 `lo = hi = oceanE`（深度），只拉低不参与调制。这个可以借鉴。

---

## 四、各类型的 `lo`/`hi` 初值（直接抄参考）

`SplineConfig.defaults()`（`:60-83`）的原文值，**不需要从我方值域反推**：

| 类型 | `lo` | `hi` | 换算 Y 区间（陆侧 `Y = 63 + e·236.44`） |
|---|---|---|---|
| PLAIN | 0.005 | 0.03 | 64 – 70 格 |
| HILLS | 0.06 | 0.368 | 77 – 150 格 |
| MOUNTAINS | 0.45 | 0.95 | 169 – 288 格 |
| PLATEAU | 0.41 | 0.706 | 160 – 230 格 |
| BASIN | -0.08 | 0.05 | 44 – 75 格（可低于海平面） |

> 注：`SplineConfig` 的 `lo/hi` 用两个控制点 `loc = {0.0, 1.0}`、`val` 两处相同 ⇒ **数学上是常量**。
> 所以内层样条在当前配置下是**常数包络**。文档 §3.1 设计的"`lo(noise)` 随噪声变"是**能力**，当前配置没用满。
> 沙箱要不要启用这个能力，是后续可选项。

---

## 五、本轮要裁定的（4 个问题）

| # | 问题 | 我的建议 |
|---|---|---|
| **Q1** | 沙箱的 `typePosition` 从哪来？<br>(a) 复用 `RegionPartition.regionId`<br>(b) 新建一条连续噪声<br>(c) 复用大陆性 `c` 的另一路映射 | **(b) 连续噪声** —— 这样 `typePosition` 天然连续，且可与 `c` 解耦 |
| **Q2** | 外层样条的坐标用 `ContinentField` 的 `c`，还是沙箱自己的？ | **用 `ContinentField.sample()`** —— 已是现成的 `c ∈ [-1,1]` |
| **Q3** | `derivative` 初值取 0（=Catmull-Rom 式的平滑）还是手工调？ | **先取 0** —— 参考 `SplineConfig` 的导数全是 0.0 |
| **Q4** | Phase 1 只做"值插值 vs 样条插值"的最小对照，还是直接做 Phase 2？ | **先 Phase 1** —— 先证明样条确实更自然，再上三层 |

---

## 六、风险（对照 `AGENTS.md` 硬约束逐条）

| 硬约束 | 本方案是否会犯 |
|---|---|
| 1. `dist`（到边界距离）不能作噪声坐标 | **不会**——样条坐标是 `c` 与 `typePosition`，不是距离 |
| 2. 禁止对噪声做值域量化 | **不会**——样条是连续插值 |
| 3. 禁止"最近邻配对"定义场 | **不会**——`typePosition` 是连续量，不做 argmin |
| 4. `reach` 处硬切换必须 smoothstep | **不会**——C1 连续样条本身无阶跃 |
| 5. 原版装饰会打散岩层 | 与地形类型无关 |
| 6. 折叠类算子禁令 | 不涉及 |

**本方案在结构上不触碰任何一条硬约束**，因为它不用距离、不用 argmin、不做量化。

---

## 七、未验证项（诚实标注）

- 文档 §3.2 的 MC `offset.json` 示例里，中层用 `erosion`、内层用 `ridges` 作为坐标名。
  我们的中层是"类型轴"、内层是"噪声值"——**名称不同但结构同构**，这个类比是否成立**未验证**。
- `SplineConfig` 的 `lo/hi` 实际是常量（两控制点同值）。所以"内层样条随噪声变"这个能力
  **生产没用**。沙箱是否启用，会影响形态丰富度，**未实测**。
- Phase 1 的"值插值 vs 样条插值"对比图**尚未出**，不能预设结论。
- `ContinentField.sample()` 的 `c` 分布范围（是否真铺满 [-1,1]）**未实测**。
