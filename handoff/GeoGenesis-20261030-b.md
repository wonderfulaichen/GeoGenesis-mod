# GeoGenesis 交接 — 2026-10-30（下午轮：群岛 / 海岸 / 丘陵 / 侵蚀算子整理）

> 本文是**当时快照**。HEAD / 数值会过期，引用前先 `git log` 核对。
> 所有改动在沙箱（`src/diagnostics` + `tools/`），**`src/main` 生产代码零改动**。

---

## 0. 一句话现状

上午轮回退了群岛坏实验（见 [GeoGenesis-20261030.md](GeoGenesis-20261030.md)）。下午轮推进了三件事并全部停在"等用户下一步指令"：

1. **浅海群岛**：三步法（平方 FBM 地基 / 丘陵类型地形 / 加法叠加）+ 海岸剖面重构，多版定稿
2. **海岸段落感**：单公式剖面 + 沿海下沉 + 宽度随高度增长（刀切修复）
3. **丘陵第 3 种地形**：`dales` 去高频变体 `DALES_LO`（**用户认可"差不多这样"**）；
   另有 4 轮翻转实验被反复否决，最终结论记录在 §3

**用户下一步意向（原话）**：「我打算看看我们的侵蚀算子是怎么实现的，看看能不能改进」→ 见 §4。

---

## 1. 浅海群岛（沙箱，未接生产）

### 1.1 最终配方（`ShallowSeaIslandCandidate`，参数为当前代码值）

```
① 地基   u = n²，n 为 FBM（基频 900 块 / 5 倍频 / lac 2 / persistence 0.6）
② 类型   gate: islandWeight(u)，KNEE=0.30；岛身 = HILLS_2（128 块丘体）
         BODY=1.8，HILLS_FLOOR=0.142（减底，因为丘陵输出全为正）
③ 叠加   Δ = seaWindow · offshoreWeight(c) · gate · BODY · max(0, hills−FLOOR)
         seaWindow: base∈[−0.35, +0.02]e
         offshore: edge ∈ [0.331, 0.50]（吃满整段浅海带）
```

**关键数据**（1401² 采样、5 块/像素、seed 9139912035078620160）：

| 指标 | 值 |
|---|---|
| 水面 | 35.10% → 32.16% |
| Y 范围 | 5.14–269.14（**与改前完全一致**） |
| 最大抬升 / 最大压低 | +67.1 / −140.6 格 |
| 岛缘坡度 \|grad\| P50 | 0.39（陆地丘陵 0.29） |

### 1.2 三个被否决的方向（防再犯）

| 方向 | 否决原话/数据 | 正解 |
|---|---|---|
| 常数 `LIFT` 抬升 | 岛缘比陆地丘陵**陡 83%**（0.53 vs 0.29），"平台+竖墙" | 只用丘陵族振幅（用户第三条路） |
| 地基 9 倍频 / 基频 3600 | 「地基 FBM 太碎了」「环状潟湖比群岛地基还大」 | 900 / 5 倍频（`BaseGeomProbe` 标定） |
| 群岛+海岸接壤的刀切 | 岸坡陡度被 landB 绑定 | §2 |

---

## 2. 海岸剖面（沙箱，未接生产）

`OceanAssemblyCandidate` 三处改动（当前代码值）：

```java
w     = 0.085 · (1 + 0.45 · sat(landB / 110))   // 岸段宽度随高度增长
H(t)  = landB·[lerp(1,0.55,cliff)·smoothstep(t) + (1−slope)·t²] − 1格·4t(1−t)
COAST_SINK_BLOCKS = 1.0                          // 沿海下沉，两端渐隐
```

**实测**（对照改前 compose-coastal）：

| | 旧 | 新 |
|---|---|---|
| 水面 | 35.10% | 35.59%（下沉 1 格版）/ 最终口径 35.62% |
| 陆地→水（岸线内移） | — | 0.493% |
| 岸带 \|grad\| P99（150–400 格高地） | **9.010** | **1.881** |

**三条死路（已删除，勿重试）**：软封顶+回填坡（岸后更陡）、固定交接带（山越高越陡）、
岸段终点外推+回填错位（P99 梯度 29 尖峰、峰顶被顶到 299）。病根：两个高度来源拼接。

---

## 3. 丘陵第 3 种地形（用户："全是起伏包"）

### 3.1 ★ 定稿：`DALES_LO`（用户认可"差不多这样"）

`HillsCandidate.Variant.DALES_LO`：两套 billow 倍频 4/3 → **2/2**，其余配方完全不动（单变量）。

| | dales-geo | **dales-lo** |
|---|---|---|
| 起伏 | 91.1 | 91.9 |
| 坡 P50 | 0.32 | 0.29 |
| **40–100 块能量** | 3.3% | **2.3%（−30%）** |
| **<40 块能量** | 0.6% | **0.4%（−33%）** |

出图：`handoff/attachments/dales-hifreq-ab.png`
入口：`TypeCandidateGallery hills` 臂组的 `HILLS-dales-lo`。

### 3.2 四轮翻转实验（全被否决，结论保留）

用户给了一张有"谷网+圆山"的地图截图要求复刻。四次尝试：

| 版本 | 我做的 | 用户批注 |
|---|---|---|
| v1 | 反转 `1−dist`（侵蚀距离图） | 「不是反转侵蚀」 |
| v2 | 反转 `base+mounds`（整场） | 「反转最大倍频的那个」 |
| v3 | 反转高频层 + Erosion | 「谷反转成山、山还是山、0 值为谷；然后叠加侵蚀」 |
| v4 | 圆化绝对值（三条断言 PASS）+ 无 Erosion | 「拿现在的高频层算就很好」 |

**数值断言工具**：`tools/verify-crest.py`（三断言）、`tools/verify-inversion.py`（相关系数）。
最终 v4 形态合格但偏平（起伏 42 vs dales 91、平坦 42% vs 17%），**未接进 buildCombine**。

### 3.3 `RefNoise.Erosion` 的放射星（已在多轮验证，改进点见 §4.1）

0.35/0.55 强度下渲染出**以格点为中心的放射扇**；各向异性指标**测不出它**（P99 仅 0.011），
是分层目检+频带分析抓到的。

---

## 4. 侵蚀算子现状（用户下一步要看的）

### 4.1 `RefNoise.Erosion`（参考复刻，`candidate/RefNoise.java:597-691`）

**出处**：参考项目 `makeFancy`（`Populators.java:401-409`），山地 `makeMountains1/2/3` 每个峰值后调用。

**算法**：
```
erosionValue = Σᵢ singleErosion(x·3.1ⁱ, y·3.1ⁱ, grid·0.8ⁱ)·0.15ⁱ  / Σ权重   (octaves=2)
singleErosion:
  布抖动点（grid=90 块）→ 对每个格点，在 3×3 环里的**每个抖动点**上，
  再扫 3×3 邻居找「height 最低的那个」连成线段 → 取该点到 9 条线段的最小距离
  → clamp(dist/grid, 0, 1)
compute = lerp(erosion, input, 1−strength)     // 近线段 dist≈0 → 被拉低成沟
```

**缺陷**（本轮实测证据）：
1. **放射扇根因**：`min over 9 segments` 是分段函数，边界 = 线段间中垂面 ⇒ 2D 上从线段端点向外发散的扇；`grid=90` 时在 701² 图上肉眼可见。强度越高越明显（0/0.15 无、0.35 起、0.55 重）。
2. **属"最近邻配对"类**：AGENTS.md 铁律 3 禁止——分片常数 + 配对切换处阶梯；阈值型探针测不出，本轮靠目检+频带能量（40–100 块 3.3%→14.2% 单调）定位。
3. `ThreadLocal` scratch 缓存 25 槽（`cachedHeight`），纯局部无跨块状态，流式合规。
4. `RefNoise` 类注释声称 `PerlinRidge` 有 `roundCrest` 开关——**代码里不存在**（注释说谎，`RefNoise.java:20`）。

### 4.2 生产 `ErosionEngine`（`erosion/ErosionEngine.java`，980 行）

**类型**：本地确定性**液滴侵蚀**（SimpleHydrology 型），非距离场滤镜。

```
四尺度层叠：C(R7,120滴,20步,ero 0.100) / M(R4,60,30,0.120) / F(R2,40,20,0.150) / XS(R1,40,14,0.030 可选)
每滴：3×3 Sobel 法线 → 惯性混合(INERTIA 0.005) → 动量偏置(场 discharge)
      → SH 平衡浓度 c_eq = (1+10·ld/(ld+4))·(max(0,−dh)+0.03·水深)
      → delta = 0.1·(c_eq − sed)，>0 侵蚀 <0 沉积（双向）
后处理：cascadeLocal（8 邻居 settling，CASCADE_INTERVAL=1）+ 双平滑
岩性耦合：hardnessProvider 注入（软岩成谷硬岩成脊，倍率 [0.46,1]，只调制侵蚀侧）
优化：SLOPE_MIN_SKIP=0.002 撒点前剪枝、STALL 平衡态早退（转 tracking 模式续流）
无缝：笔刷邻域 R_MAX=7 ⊂ tile border 9、液滴按 chunk 哈希确定性播种（世界坐标）
```

### 4.3 生产 `RidgeValleyErosion`（`erosion/RidgeValleyErosion.java`，357 行）

**类型**：**梯度对齐条纹滤镜**（移植自 Fast and Gorgeous Erosion Filter / Burst，独立重写）。
- 作用：低分辨率网格上把圆包噪声**切成多峰山脊骨架**，再交给 `ErosionEngine` 液滴打磨
- 无缝：每点独立 evaluate + 确定性 pivot 旋转 + 邻细胞钟形混合
- 关键参数（用户多轮标定）：strength 2.0、cellWorldSize 100、onset[0]=0.9（0.7 时低地也触发被否）、rounding[1]=0.12（U 形谷）

### 4.4 三算子对照

| | 类型 | 形态来源 | 尺度 | 项目合规性 |
|---|---|---|---|---|
| `RefNoise.Erosion` | 线段距离场 | 最低邻居线段网络 | grid 90 块（可调） | ⚠ 最近邻配对嫌疑，放射扇实测 |
| `ErosionEngine` | 液滴粒子模拟 | 轨迹+平衡浓度+cascade | 四尺度 1–7 块笔刷、20–30 步寿命 | 合规（已验证无缝） |
| `RidgeValleyErosion` | 梯度条纹滤镜 | 梯度对齐条纹 + 细胞混合 | cell 100 块、4 倍频 | 合规（逐点确定） |

### 4.5 改进方向（**提案，均未实测**，等用户选）

1. **直接复用生产 `ErosionEngine`**：沙箱出图走 tile 版液滴侵蚀（现有探针可复用）。
   优点：合规、真实粒子质感；缺点：要 tile 缓冲，非点式纯函数，接入 `buildCombine` 需要装配层。
2. **改造 `RidgeValleyErosion` 为丘陵骨架**：它本来就是"把圆包切成脊谷"的算子，
   与本轮要的"谷网+圆山"同构，且已在生产验证无缝。把 `cellWorldSize` 100 → 260~400 即丘陵尺度。
3. **给 `RefNoise.Erosion` 换掉最近邻**：线段端点用**梯度积分**（沿输入场梯度下行一段）而非
   "找最低邻居"，配对切换变连续。风险：仍是距离场，可能残留扇状边界。

---

## 5. 文件清单（本轮）

**改动/新增（全部沙箱）**：
```
src/diagnostics/.../candidate/
  HillsCandidate.java            M  DALES_LO 变体
  ShallowSeaIslandCandidate.java +  浅海群岛三步法
  OceanAssemblyCandidate.java    M  岸段单公式+下沉+宽度随高度增长
  LandCompositionGallery.java    M  两群岛臂
  TypeCandidateGallery.java      M  诊断臂（L1-L5/MOUND-*/RIDGED-*/MOUND-RAW 等）
  RidgedHillsCandidate.java      +  v4 翻转高频层版（未定稿）
  RollingHillsCandidate.java     +  RTG 复刻（未达标，弃用待删?）
tools/
  render-islands-big.py / show-coast-compare.py / show-island-closeup.py
  show-island-profile.py / measure-island-slope.py / diagnose-hills.py
  show-hills-compare.py / verify-crest.py / verify-inversion.py
  render-sharp.py                M  keep 列表
handoff/attachments/             +  群岛/海岸/丘陵各版大图（19 张）
```

**状态**：`git status` 干净于 `src/main`；沙箱改动**未提交**（等用户裁定后再 commit）。
被否决但仍在文件里的：`RidgedHillsCandidate`（4 轮迭代，供 §4 参考）、`RollingHillsCandidate`（RTG 复刻，未达标）。
是否删除等用户裁定——AGENTS.md 教训："死代码+声称它在生产路径上的注释才可怕"，这两个类注释已如实标注状态。

---

## 6. 待办（等用户指令）

| # | 事项 | 卡在谁 |
|---|---|---|
| D1 | 丘陵改动（DALES_LO）是否接进 `buildCombine` 出陆地合成大图 | 用户 |
| D2 | 群岛/海岸是否接进生产（需升 CACHE_SCHEMA_VERSION + 重出正典大图） | 用户 |
| D3 | §4.5 三个侵蚀算子改进方向选一个 | 用户（"我打算看看侵蚀算子"） |
| D4 | `RidgedHillsCandidate` / `RollingHillsCandidate` 删除还是留 | 用户 |
| D5 | 本轮沙箱改动 commit（中文信息用 `git commit -F`） | 用户 |
