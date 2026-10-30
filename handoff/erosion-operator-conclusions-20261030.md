# 侵蚀算子结论 — 2026-10-30

> **这一轮把"改进侵蚀算子"的可行路径全部验证了一遍，结论是否决性的。**
> 每条都带实测数据与图；推翻的假设也保留（避免下次重犯）。
> **`src/main` 全程零改动**；本轮所有实验代码已清理。

---

## 0. 结论速览

| 提案 | 结论 | 关键证据 |
|---|---|---|
| 方案 A：线段下坡方向当条纹方向源 | **否决**（未实施，读码否决） | 线段方向本身分段，会变成条纹错位 |
| 方案 B：RefNoise 造山体 + RidgeValley 刻细节 | **否决**（三轮实测） | RefNoise 输出放射星，无法消除 |
| 方案 C：梯度取样半径 ±2/±3 | **否决**（实测） | 三档形态几乎逐像素相同（mean\|Δ\| 差 1.2%） |
| P1：低地骨架分级切浅沟 | **否决**（实测） | 低地无稳定梯度 ⇒ 出放射星而非浅沟 |
| 软化 RefNoise（softmin + 软加权重心） | **否决**（实测） | 长尾比只从 12.9 降到 10.3，未根治 |

**唯一没有否决的**：现状拓扑（`RidgeValleyErosion` 骨架 + `ErosionEngine` 液滴）已经是三层方案里最优的组合。

---

## 1. 生产侵蚀的真实结构（先纠正我之前的错误说法）

**生产只有 2 套侵蚀**，不是 4 套：

```
src/main/java/com/geogenesis/worldgen/erosion/
  ErosionEngine.java        980 行   粒子液滴（细节层）
  RidgeValleyErosion.java   357 行   条纹骨架（细节层）
```

调用顺序（[CellGenerator.java:1266-1322](forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/terrain/CellGenerator.java#L1266-L1322)）：

```
第2步  RidgeValleyErosion.evaluateCell  → deltaLR（粗分辨率条纹增量）
第4步  flat = base + bilinear(deltaLR)  → erosion.runErosionOnFlat(flat)
```

配置 `erosionEnabled` / `erosionRidgeEnabled` **默认均为 true**，两层都在跑。

`RefNoise.Erosion` 是**沙箱里的参考复刻**（`src/diagnostics/.../candidate/RefNoise.java`），**从未进生产**。

### ★ 一个重要发现：生产山地已含一层 RefNoise

`MountainsCandidate.fancy()`（[MountainsCandidate.java:140-145](forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/terrain/candidate/MountainsCandidate.java#L140-L145)）：

```java
private static RefNoise.Node fancy(RefNoise.Node input, IntSupplier next) {
    RefNoise.Node direction = RefNoise.Perlin.ofScale(next.getAsInt(), 10f, 1);
    RefNoise.Node erosion = new RefNoise.Erosion(input, erosionSeed, 2, 0.65f, 128f, 0.15f, 3.1f, 0.8f);
    return new RefNoise.DirectionWarped(erosion, direction, 2.0f);
}
```

`M1/M2/M3` 全族都调 `fancy` ⇒ 山地族**出厂就带 RefNoise.Erosion 0.65 + DirectionWarp 2.0**。
这是参考项目 `makeFancy` 的原样移植。

---

## 2. 方案 A：线段下坡方向当方向源 —— 否决（读码）

**设想**：`RidgeValleyErosion` 的条纹方向取自 `norm(gx,gz)`（局部梯度）。若改用
`RefNoise.Erosion` 的线段方向（`normalize(b−a)`，真正的下坡方向），是否更"汇流"？

**否决理由**：

1. **方向本身分段**。`RefNoise` 的线段方向来自 9 个格点各自的 `argmin`，
   `argmin` 切换时方向**突变**。作为条纹相位输入 ⇒ `Phacelle` 的 cos/sin 跳变 ⇒
   **条纹在格点边界错位**。这与放射星是同一病根，只是外化成条纹断裂。
2. **奇点**：中垂面上到两条线段距离相等，方向无定义。`Phacelle` 的
   `ngsx/ngsz` 在梯度零处有明确回退值 `{1,0}`，线段方向没有。
3. **侵入面大**：`singleErosion` 返回 `float`（距离），要输出方向得改成 `float[]`，
   影响调用点与返回值约定。

---

## 3. 方案 C：梯度取样半径 —— 否决（实测）

**设想**：把 `gradX/gradZ` 的差分半径从 ±1 格放大到 ±2/±3，让方向取更大邻域 ⇒ 更"汇流"。

**实测**（同一输入场，384²，骨架真正工作的地形 e 0.30–0.55）：

| 档 | max\|Δ\| | P99\|Δ\| | mean\|Δ\| |
|---|---|---|---|
| ±1（现状） | 0.08557e | 0.06111e | **0.00908e** |
| ±2 | 0.08539e | 0.06066e | **0.00905e** |
| ±3 | 0.08511e | 0.06066e | **0.00901e** |

三档自身幅值差 **< 0.6%**；三档之间逐像素差 `mean|diff|` 仅 0.000112e（1.2%）与 0.000201e（2.2%）。

**根因（读码）**：`assumedSlope = {0.7, 1.0}`，`lerp` 第三参数是 **1.0**
（[RidgeValleyErosion.java:241-242](forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/erosion/RidgeValleyErosion.java#L241-L242)）：

```java
float gsx = lerp(gx, ngsx * cfg.assumedSlope[0], cfg.assumedSlope[1]);   // t=1.0 ⇒ 全用 ngsx
```

**完全取归一化方向，梯度幅值不参与**。而 `Perlin` 是 C¹ 场，±1 与 ±3 差分的方向几乎相同
⇒ 结构上不可能有差异。

---

## 4. P1：低地骨架分级 —— 否决（实测）

**设想**：低地（`h<0.2`）`hBoost=2` 达不到 `combiMask` 触发阈值 ⇒ 低地无骨架条纹。
给低地一套独立参数（`cellWorldSize 100→70`、`strength ×4.0`、`onsets ×0.30`、`slopeBoost 8.0`）切浅沟。

**做了什么**：`RidgeConfig` 加 7 个 `lowland*` 字段，按 `h` 在低地档与山地档间平滑插值。

**过程中修掉的真 bug**：原写法
`hBoost = lerp(boostLowland, 8f, smoothstep(0.2, 0.35, h))`
在 `boostLowland ≠ 2` 时会把整个 `h∈[0.2,0.35)` 过渡带一起改
（实测污染 0.061e）。改成"山地档保持原公式、低地用 `wLow` 单独插值"后：

```
h≥0.22 区域 max|开−关| = 0.000000000e   PASS（高地形逐位不变）
```

**为什么最终否决**：低地档确实让 `mean|Δ|` 从 0.000098 升到 0.001600e（放大 16 倍），
**但形态是放射状星芒，不是浅沟**（见 `3-lowland-ridge-ab-failed.png` 右下格）。

根因：低地坡度接近 0 ⇒ 梯度方向由噪声级微扰决定 ⇒ `norm(gx,gz)` 处处乱变
⇒ `Phacelle` 条纹跟着乱转。**只要方向来自真实梯度，低地就不可能有干净的方向性浅沟。**

---

## 5. 方案 B：RefNoise 造山体 + RidgeValley 刻细节 —— 否决（三轮实测）

### 5.1 第一轮：平坦基底（**实验设计错误，作废**）

用平坦基底 e=0.12 + 单算子，结论"RefNoise 只有规则网纹、aniso 0.55–0.72"。

⚠ **这个实验是错的**：`RefNoise.Erosion` 是滤镜 `lerp(erosion, value, 1−strength)`，
平坦输入上没有"形态"可重塑，看到的只是它自己的网纹。**结论作废。**

### 5.2 第二轮：真实山体（**基准被污染，作废**）

用 `MountainsCandidate.M1_GEO` 当基准——但它含 `fancy()` ⇒ **基准本身已带 RefNoise 0.65**
（见 §1）。测的是"叠第二层"的效果，不是 RefNoise 的贡献。**结论作废。**

### 5.3 第三轮：朴素 PerlinRidge 基底（**有效实验**）

基底 = 单条 `PerlinRidge(610块, 4倍频)`，零滤镜。

| 臂 | corr | mean\|Δ\| | aniso | 形态 |
|---|---|---|---|---|
| ① 纯 PerlinRidge | 1.0000 | — | 0.080 | 圆钝，几乎无结构 |
| ② RefNoise 0.30 | 0.9461 | 0.0508 | 0.057 | 细纹，形态保留 |
| ③ RefNoise 0.55 | 0.7631 | 0.0932 | 0.021 | **出现星芒** |
| ④ RefNoise 0.85 | **0.4059** | **0.1440** | 0.041 | **三朵明显放射星** |
| ⑤ RidgeValley | 0.9848 | 0.0115 | 0.026 | 细密均匀纹理，形态完整 |

**结论**：RefNoise **确实有重塑形态的能力**（0.85 时 corr 0.41，变化量是 RidgeValley 的 12 倍），
但**输出的具体形态是放射星**，不是山脊山谷。

### 5.4 DirectionWarped 能否打散星芒 —— **不能**

参考 `makeFancy` 用 `Domains.direction(perlin(10块,1), constant(2.0))` 接在 Erosion 后。

| 臂 | corr | aniso | 形态 |
|---|---|---|---|
| ② RefNoise 0.85（未打散） | 0.4059 | 0.041 | 三朵星 |
| ③ +DirectionWarp **2.0**（参考原值） | 0.4047 | 0.055 | **星芒完全没变** |
| ④ +DirectionWarp 8.0 | 0.4007 | 0.054 | 星芒仍在，边缘毛糙 |
| ⑤ +DirectionWarp 24.0 | 0.3853 | 0.024 | **星芒依然清晰**，整图被抖糊 |

**根因：尺度不匹配**

```
星芒尺度  = Erosion 的 gridSize = 200 块
warp 幅度 = 2 / 8 / 24 块           ← 相差 8~100 倍
方向噪声波长 = 10 块                 ← 高频抖动，不是大尺度扭曲
```

要打散 200 块的星芒，warp 幅度需 100 块量级，但那样会把整个地形揉烂。

### 5.5 为什么参考项目里看着自然

参考用法是**在山地上轻叠一层**（`strength 0.65` + `grid 128`），
星芒被原山体挡住大半，只剩"细纹理"。**本质与 `RidgeValleyErosion` 同层级——
都是细节层，都不是山体层。**

---

## 6. 软化 RefNoise（SoftErosion）—— 否决

**设想**：放射星来自 `singleErosion` 的两处离散：
① `min over 9 segments`（中垂面分段）② `argmin` 硬选（端点跳变）。
用 softmin 替代 ①、软加权重心替代 ②。

**实测**：距离图二阶差分长尾比 **12.9 → 10.3**（−20%），尖峰 P99.9 降 14%，耗时 667→1174ms（1.8×）。

**为什么否决**：长尾比 10.3 仍偏高（平滑场通常 3–8）。**底层的 9 条线段结构还在**，
softmin 只是让邻域过渡变缓，线段数量与交汇几何没变。要彻底解决得换拓扑，不是软化。

---

## 7. 三轮实验的正确性教训（方法论）

| 轮次 | 错在哪 | 教训 |
|---|---|---|
| 平坦基底 | 用平坦输入测"重塑型滤镜" | **滤镜必须先有输入形态**，否则测的是它自己的纹理 |
| M1_GEO 当基准 | 基准含 `fancy()` = 已带 RefNoise | **测之前先读输入配方**，确认基准里没有待测对象 |
| 判据只看数值 | 第一轮"aniso 0.55–0.72"曾被我读成"结构条纹"，实际是规则网纹 | 数值要配图；`aniso` 高不等于"有条纹"，要看是什么结构 |

**通用规则**：改/测某个算子前，先 `grep` 它的调用链，确认基准里没有它。

---

## 8. 现状与遗留

**`src/main` 零改动**（本轮所有改动都是先加后撤，`git status --short src/main` 为空）。

**已清理**（结论已入本文 + 图已归档，代码全部删除）：
```
src/diagnostics/.../candidate/
  SoftErosion.java                （软化的 RefNoise，否决）
  RefNoiseTerrainProbe.java       （平坦基底实验，作废）
  ErosionOnMountainProbe.java     （M1_GEO 实验，基准污染）
  PureRidgeErosionProbe.java      （有效实验，结论已入档）
  LowlandRidgeProbe.java          （P1 实验，否决）
  ErosionOperatorVisualProbe.java （三算子对照）
  RidgedHillsCandidate.java       （丘陵翻转实验，已被 DALES_LO 取代）
  RollingHillsCandidate.java      （RTG 复刻，未达标）
tools/
  show-erosion-operators.py / show-refterrain.py / show-mtn-erosion.py
  show-pure-ridge.py / show-directwarp.py / show-lowland-ab.py
```

**图档**（`handoff/attachments/erosion-investigation-20261030/`）：
| 文件 | 内容 |
|---|---|
| `1-directwarp-cannot-break-stars.png` | DirectionWarp 三档 vs 未打散 —— 星芒不消失 |
| `2-pure-ridge-vs-refnoise.png` | 朴素 PerlinRidge + RefNoise 三档 + RidgeValley 对照 |
| `3-lowland-ridge-ab-failed.png` | P1 低地 A/B（高地形 / 低地 × 关/开）—— 低地出星芒 |
| `4-three-operators.png` | 三算子距离场/输出对照（含放射星根因） |
| `5-flat-base-invalid-test.png` | 平坦基底实验（**作废记录**，说明为什么无效） |

**`RidgeValleyErosion` 的 P1 代码已完整回滚**（`lowland*` 7 个字段 + `strengthScale/onsetScale`
两参数 + `gradRadius`（方案 C）全部删除，`git status` 确认零改动）。

---

## 9. 如果将来还想改进侵蚀，可走的方向

**已被否决的不要再试**：方案 A / B / C、低地骨架、软化 RefNoise、`DirectionWarped` 打散星芒。

**尚未验证、可能有价值的**（都需先读码确认，不要直接动手）：

1. **给 `RefNoise.Erosion` 的格点抖动加输入场相关偏移**
   （平坦基底实验证明问题是"线段端点都在格点上"）。几行改动，但需重做评测链。
2. **`ErosionEngine` 液滴层**（评审见 [`erosion-operator-review-20261030.md`](erosion-operator-review-20261030.md)）：
   - `INERTIA=0.005` 与 3×3 Sobel 存在**双重防抖**，提高惯性可能让沟更长
   - `heightDrop = max(0, −dh)` 上坡时容量归零 ⇒ 沟到洼地就停（配合 `tracking` 补丁）
   - `cascadeLocal` 每步 8 元素冒泡排序，可换部分选择
3. **`ridgeFade` 已算出但未输出**（`RidgeValleyErosion.java:236`）——
   零风险，可作为植被/着色/河道的信号源。

---

## 10. 待办

| # | 事项 | 卡在谁 |
|---|---|---|
| D1 | 是否继续 §9 的某个方向 | 用户 |
| D2 | 本轮沙箱改动（丘陵 `DALES_LO` + 群岛/海岸）是否 commit | 用户 |
