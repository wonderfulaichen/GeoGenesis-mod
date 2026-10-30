# 线段距离场（RefNoise.Erosion）放射星调查 — 2026-10-30

> **一句话**：用户提出「放射星是因为多线段共用格心」——**假说证实**；
> 但修掉共用格心的代价是**距离场失去锐度**，因此该算子在"无星芒"约束下走不通。
> 最终决定改用生产骨架侵蚀 `RidgeValleyErosion`。
> `src/main` 全程零改动；本轮实验代码已清理。

---

## 0. 用户假说与结论

> 用户 2026-10-30：「线段距离场出现星芒基本是因为有多线段共有的格心导致的」

**证实**。原版 `RefNoise.Erosion.singleErosion`（`RefNoise.java:636-668`）：

```java
for (dy1=-1..1) for (dx1=-1..1) {          // 9 个格点
    起点 a = 该格点的抖动位置
    终点 b = 邻居中最低格点的抖动位置       // ★ 多条线段共享同一批端点
    if (d2 < minDist2) minDist2 = d2;       // ★ 对 9 条距离取 min
}
```

- **单调坡面上 9 格点的最低邻居是同一个点** ⇒ 9 条线段共享终点 ⇒ 汇聚成星
- `min` 让每条线段占一片 Voronoi 区域 ⇒ 边界是**直线** ⇒ 星芒射线

### grid 扫描（直接证据）

同一线状谷输入、strength 固定 0.85、只扫 `gridSize`：

| grid | 孤立极值（星心） | aniso | 图形判读 |
|---|---|---|---|
| 30 块 | 115 | 0.103 | 每格一星，密集星海 |
| 90 块 | 113 | 0.109 | 星变少变细 |
| 200 块 | 100 | 0.265 | 大星芒（参考默认量级） |
| 400 块 | 74 | 0.248 | 星减少 |
| 800 块（> 地形 610） | **66** | **0.364** | **星基本消失**（9 点几乎选同一邻居 ⇒ 线段重合） |

极值数**单调下降 43%**、aniso 单调上升 3.5 倍，与假说预测完全一致。

---

## 1. 关键发现：锐度与星芒同源

| 构造 | 星芒 | 锐度（坡P50） | 机制 |
|---|---|---|---|
| `min(dist_i)`（原版） | **有** | **0.0022** | 极值选择保留突变；分段边界即星芒射线 |
| `Σwᵢdᵢ/Σwᵢ`（加权） | 无 | 0.0011 | 钟形加权平均 = 低通 ⇒ 抹平 |

**原版的"纹理感"和"星芒"是同一份东西**：距离场里的锐线。去掉星芒 = 去掉锐线 = 变糊。
（坡P50 是输出 `|∇|` 中位数；原版 0.0022、所有改造版 0.0009–0.0011。）

---

## 2. 八轮尝试与结果

| # | 尝试 | 结果 | 证据 |
|---|---|---|---|
| 1 | `grid` 30→800 扫描 | 星芒变大/变小但不消失 | `gridscan.png` |
| 2 | 换谷形状（点 / 线 / 反脊） | 星芒都有 | `valley-shape.png`（单噪声版：点谷 maxBasin 27.6%、线谷 12.5%，两者都出星） |
| 3 | **fadeTarget**（blog 正解：坡度→0 处淡出条纹） | 只压掉星心那一小点，**辐射臂压不住**（臂在正常坡面，mask=1）。副产品：过度改造问题被修好（corr 0.43→0.96） | `fade-compare.png` |
| 4 | softmin + 软加权重心 | 长尾比 12.9→10.3（−20%），线段拓扑没动 | （评审文档 §5） |
| 5 | 每格 1 线段 + 钟形加权（你的假说的直接实现） | **星消失**（aniso 0.265→0.081），但**糊**（0.0022→0.0011）；首版还有 3 个我自己的 bug（见 §3） | `segfield-three-way.png` |
| 6 | 梯度终点（终点沿 −∇h，不在格点） | 星消失，更糊（0.0009），方向最弱 | 同上 |
| 7 | 线段长度随坡度自适应 | **无效**（lenMin 0.15→0.35、glenRef 0.5→6.0，四档几乎全同）⇒ 长度不是杠杆 | `seglen-adaptive-failed.png` |
| 8 | **梯度主导方向 + 各格随机偏转**（你的批注） | 方向救回一半（aniso 0.076→0.162，偏转 0→90° 单调），星不回来（极值 80–86），**锐度仍卡在 0.0009** ⇒ 低通平均的本质未变 | `rotscan-gradient-rot.png` |

---

## 3. 我在第 5 轮修掉的三个自己的 bug

它们曾污染第 5 轮的结论（当时看起来"方块接缝"是构造本身的问题）：

1. **`cachedHeight` 缓存越界**：窗口 5×5 + 邻居 ±1 ⇒ 索引 −3..3，但 scratch 只有 25 槽，
   `index<0` 被**静默改写成 12** → 返回错误高度。改 49 槽（7×7）。
2. **权重方向反**：`pxr − i` 应为 `i − pxr`（格心相对采样点），与邻域不一致 ⇒ 格边界不连续。
3. **`w − 0.01111` / `if (w < eps) continue` 截断**：在窗口角落随机丢弃贡献 ⇒ 方块接缝。
   改为纯 `exp(-2r²)` 无截断，窗口 −2..2 角落权重 `exp(-32)≈1e-14` 已可忽略。

修完后**方块接缝从图上完全消失**。

---

## 4. 谷形状假设（另外两轮，作废记录）

> 用户：「放射星的原因是输入地形的谷是一个中心低谷」/「你这地形还是不对，你就不能单噪声测试吗」

- 第一版（PerlinRidge 复合场）**作废**：谷被多倍频混合，没控制住
- 第二版（单倍频 Perlin，只改谷取在哪）：`h = p` 谷点状（maxBasin 27.6%）、
  `h = 圆化|2p−1|` 谷线状（12.5%）—— **谷形态确实控制住了，但两者叠 RefNoise 后都出星**
  ⇒ 谷形状不是根因

另：先发现 `MountainsCandidate.M1_GEO` 当基准是错的——**它含 `fancy()` = 已带 RefNoise 0.65**
（`MountainsCandidate.java:140-145`），测"叠第二层"不等于测"它的贡献"。

---

## 5. 最终决定：改用生产骨架侵蚀

`RidgeValleyErosion` 的做法与线段距离场完全不同，**从构造上没有星芒**：

```
Phacelle 条纹：每格 cos/sin 相位 + 4×4 钟形混合（不取 min、不找最低邻居）
方向来源：真实梯度归一化 + assumedSlope
压制：fadeTarget + combiMask（blog §The fade approach 正解，已实现）
幅度：±0.02e（±5 块），是细节层
耗时：146ms / 768 块视野（原版 RefNoise 655ms）
```

**注意**：它在峰/谷（坡度=0）处也会有方向混乱，但 `fadeTarget` 把那里的条纹淡出，
且幅度只有 ±5 块被山地起伏掩盖 —— 这正是 blog 文章推荐的完整方案。

---

## 6. 遗留与待办

| # | 事项 | 状态 |
|---|---|---|
| 1 | 用 `RidgeValleyErosion` 替换现有侵蚀做渲染出图（用户指令） | 本轮进行中 |
| 2 | 实验代码清理 | 已执行 |

**本轮已清理**（结论入本文，代码删除）：
```
WeightedSegmentErosion / GradientSegmentErosion / RotatedGradientErosion
FadedErosion（带 fadeTarget 的变体）
SoftErosion（更早一轮，已删）
探针：SegmentFieldCompareProbe / SegLenScanProbe / RotScanProbe / GridScanProbe
      ValleyShapeProbe / FadeCompareProbe / RidgeStarProbe / SoftErosion 相关
脚本：show-segfield.py / show-seglen.py / show-rotscan.py / show-gridscan.py
      show-valley-shape.py / show-fade.py / show-ridgestar.py
```

**图档**：`handoff/attachments/` 下 `segfield-three-way.png`、`seglen-adaptive-failed.png`、
`rotscan-gradient-rot.png`、`valley-shape-test.png`、`gridscan*.png`、`fade-compare.png`、
`ridgestar*.png`（如有）

**未验证项**：所有 A/B 都在 384² 的单噪声底上测，从未在 1401² 生产口径复验
（因为结论是"走不通"，不必进入下一阶段）。
