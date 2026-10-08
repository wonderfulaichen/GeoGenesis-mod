# B 方案（湖水位局部化）进展与诊断（2026-10-09）

## 一、已实施

**`LakeWaterBalance.LOCAL_LAKE_LEVEL`**（`-Dgeogenesis.localLakeLevel=true`）
—— 湖水位从「水量平衡 `solveLevel`（依赖全流域 inflow）」改为「溢出口高程 `sill`」。

**参考依据**（四家全部用局部量定湖水位，子代理已取证）：

| 项目 | 湖水位来源 | 是否局部 |
|---|---|---|
| FTF | `ContinentalHydrology.getComplexWaterHeight:131-136` | ✅ 位置纯函数 |
| PL-RGA | `_applyRiverHeightSlopeDrop`（platewisenetwork.py:479-529）| ✅ 出口高 + 固定比降 |
| DW | **无湖**（湖 = 河道加宽，`zone1Radius *= lakeMultiplier`）| — |
| Streams | **无湖**（接口水位恒 = 海平面常数）| — |

`sill` 是 `EquilibriumWater` 的 priority-flood **早已算出**的量（= 盆地 rim 最低点
= 水刚好能溢出的高度），只依赖【盆地自身 + rim】⇒ **局部**。

**改动风格**：开关默认 `false` = 原行为（零变更）；`inflow`/`evap`/`outflow`
仍按原公式计算（供下游河网与账本使用），只把【水位】换成 sill。

## 二、实测：湖并没有回来

| 配置 | 湖 | 备注 |
|---|---|---|
| 参照图（正典单窗口 r=700/step=1）| **3.63%** | 用户参照物 |
| 生产 REGION=512/step=4（游戏实际）| 0.21% | |
| 生产 REGION=512/step=4 **+ LOCAL_LAKE_LEVEL** | **0.21%** | ← **几乎无变化** |
| 生产 REGION=512/step=1 + LOCAL_LAKE_LEVEL | 0.21% | |

⇒ **湖水位局部化没有解决"小窗口丢湖"** ⇒ 说明**水位不是主因**。

## 三、诊断：模型算出了湖，但归因口径一直有问题

我加了"去重口径"诊断（region 累加 ÷ 覆盖重数，因为多 region 窗口重叠）：

| step | region 累加湖格 | 去重后 | ×step² = 应为全图湖面积 | 实测湖列 | 保留率 |
|---|---|---|---|---|---|
| 4 | 17,783 | 4,445 | 71,120 | 4,108 | **6%** |
| 1 | 103,994 | 25,998 | 25,998 | 4,418 | **17%** |

**step=1 时摊派完全无损（1 格 = 1 方块），保留率仍只有 17%**
⇒ **摊派/step 不是主因**。

## 四、根因候选（按证据强度）

### 候选 1：`RADIUS` 不同（**最可能**）

- 正典探针：`radius = ViewSpec.RADIUS = 700`
- 生产 provider：`RADIUS = REGION/2 + HALO`
  - REGION=512 ⇒ 352
  - REGION=1408 ⇒ **704 + 96 = 800**

**即使 REGION=1408（1 个 region 覆盖全图），RADIUS=800 ≠ 700** ⇒
窗口不同 ⇒ `EquilibriumWater` 的洪泛范围不同 ⇒ **湖判定必然不同**。

实测对照（`loc` = LOCAL_LAKE_LEVEL）：

| 配置 | 湖 | region 数 |
|---|---|---|
| 参照图 r=700 | **3.63%** | 1（探针）|
| 生产 REGION=1408/step=1 | **1.97%** | 1（窗口 800）|
| 生产 REGION=512/step=4 | 0.21% | 9+ |

⇒ REGION=1408 时只有 1 个 region，湖恢复到 1.97%（正典的 54%），
剩余差距来自 **RADIUS 800 vs 700** 以及 `p.lake()` 摊派。

### 候选 2：`columnsFor` 的 region 选择

`regionFor(centerBlockX, centerBlockZ)` 按 **chunk 中心**入区。
湖是跨 region 的 ⇒ 相邻 region 各自求解，**同一边界方块可能得到不同答案**。
（未取证：需要"同一方块被两个 region 覆盖时结果是否一致"的实测。）

### 候选 3：`p.lake()` 的摊派（step>1 时）

`Math.round((block-origin)/STEP)` ⇒ 1 格摊到 STEP² 方块。
step=4 时湖格 4,108 vs step=1 时 4,418 ⇒ **影响很小**（7%）。

## 五、诚实结论

**B 方案的第一步（湖水位局部化）已实现但不足以解决问题。**
反复实测表明差异主要来自**窗口参数（RADIUS）与执行模型（单窗口 vs 多 region）**，
而不是水位公式。

**要真正对齐"预览 = 游戏"，最小充分条件是**：
1. 预览与生产**用同一个 provider**（同一个 RADIUS、同一个 step）—— 这是 R1，仍未修；
2. 生产侧的 `RADIUS` 必须**与参照图的 700 一致**（即 `REGION/2 + HALO = 700`）。

⇒ 若 `RADIUS=700`、`REGION=1408/HALO=0` → RADIUS=704（接近 700，差 0.6%）。
但 `HALO=0` 会破坏"区域契约"（跨中心一致性）。

## 六、待办

1. ★ **修 R1**：预览改走 provider（用户的核心要求"渲染图必须和游戏一样"，至今未修）。
2. 用 R1 修好后的工具重做对照 —— 届时"预览 = 游戏"由构造保证，
   任何参数改动都能被立即验证。
3. 再评估 B 方案第二步（河宽局部化：`discharge` → 局部量）。

## 七、我这一轮的错（继续记）

- 在**没有修 R1**（预览走 provider）的情况下继续调参数 ⇒ 每次"验证"都不可信。
  这是本轮所有反复的根源：**我看的图和游戏不是一回事**。
