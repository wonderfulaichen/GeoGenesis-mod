# GeoGenesis 定稿地形 · C# 移植说明

> 目标读者：要在**自己的游戏**（C#）里复现这套定稿地形的开发者。
> 本文只讲**能落地的东西**：数据契约、算法入口、必须逐位对齐的常量、以及已知的移植陷阱。
>
> 配套文档：[GeoGenesis-20261103-final.md](GeoGenesis-20261103-final.md)（本轮定稿结论与实验否决记录）。
> 所有 SHA / 行号都是 2026-11-03 检查点，引用前现测。

---

## ★ 克隆仓库前必读（2026-11-03 历史重写）

仓库在 2026-11-03 做过一次**历史重写**：把 `handoff/attachments` 下 591 个大实验图
（448.5 MB）从全部历史中移除，只保留 2026-11-03 的 14 张定稿图。
`.git` 从 407 MB 降到 **42.5 MB**。

### 如果你曾经 clone 过这个仓库

**旧的本地副本已经和远程分叉，不能直接 `git pull`。** 会出现
`fatal: refusing to merge unrelated histories` 或大量冲突。请**重新 clone**：

```bash
# 备份你本地的改动（如果有）
git diff > my-changes.patch

# 删除旧副本，重新克隆
rm -rf GeoGenesis-mod
git clone https://github.com/wonderfulaichen/GeoGenesis-mod.git
cd GeoGenesis-mod
git checkout feat/hydrology-target
```

### 克隆后的注意事项

| 项 | 说明 |
|---|---|
| **检出分支** | 必须是 `feat/hydrology-target`（`main` 是旧的，不含定稿） |
| **历史不可追溯** | 2026-11-03 之前的提交哈希**全部改变**，旧 SHA 无法解析 |
| **旧图无法找回** | 被移除的 591 张大图不在历史里了；需要时从旧克隆或备份取 |
| **定稿图仍在** | `handoff/attachments/commit-selection-20261103/` 等 4 个目录保留 |
| **不要 `git gc` 后惊讶** | 42.5 MB 是正常的，不是数据丢失 |

### 验证克隆是否完整

```bash
# 定稿数据必须存在且 SHA 正确
sha256sum forge-1.20.1-47.4.10-mdk/build/main-verify-restore/out/morph-coastal.f64be
# 期望 7be0418369d097ce107eeef58b8d02c6776782f1d7fbc6ad6c5e0e5728d7a238

# 定稿图应在（14 张）
ls handoff/attachments/commit-selection-20261103/*.png
```

---

## 〇、先看这一节：你要移植的是什么

定稿地形 = **一份 1401×1401 的高度数组** + **一套生成它的算法**。

两者都要，但优先级不同：

| 阶段 | 你需要什么 | 用途 |
|---|---|---|
| **第一步：验证环境** | 只要 `morph-coastal.f64be` | 用已知正确数据验证你的渲染/坐标系，不用碰算法 |
| **第二步：可无限生成** | 算法 + 本文的常量表 | 换成任意 seed、任意坐标都能算出高度 |

⚠ **不要**跳过第一步直接移植算法。地形算法有 7000+ 行、大量 float/double 混算和整数溢出语义，
没有基准数据你无法判断"算错了"还是"设计如此"。

---

## 一、定稿数据契约（第一步就用得上）

### 1.1 文件

```
路径    forge-1.20.1-47.4.10-mdk/build/main-verify-restore/out/morph-coastal.f64be
大小    15,702,408 字节（1401 × 1401 × 8）
SHA-256 7BE0418369D097CE107EEEF58B8D02C6776782F1D7FBC6AD6C5E0E5728D7A238
```

### 1.2 内存布局（C# 读取代码）

```csharp
const int N = 1401;
const double SeaLevel = 63.0;

// 大端 float64，行优先：行 = z，列 = x
var raw = File.ReadAllBytes("morph-coastal.f64be");
if (raw.Length != N * N * 8) throw new InvalidDataException($"size {raw.Length}");

var height = new double[N, N];
for (int j = 0; j < N; j++)          // j = z 索引（行）
for (int i = 0; i < N; i++)          // i = x 索引（列）
{
    int o = (j * N + i) * 8;
    ulong bits = ((ulong)raw[o] << 56) | ((ulong)raw[o + 1] << 48)
               | ((ulong)raw[o + 2] << 40) | ((ulong)raw[o + 3] << 32)
               | ((ulong)raw[o + 4] << 24) | ((ulong)raw[o + 5] << 16)
               | ((ulong)raw[o + 6] << 8)  |  raw[o + 7];
    height[j, i] = BitConverter.Int64BitsToDouble((long)bits);
}
```

或用 `System.Buffers.Binary.BinaryPrimitives.ReadDoubleBigEndian(raw.AsSpan(o, 8))`。

### 1.3 坐标映射（**最容易搞错的一处**）

```
x = -3642.5 + i * 5.0        // i = 列索引
z = -3365.5 + j * 5.0        // j = 行索引
```

即：

| 项 | 值 |
|---|---|
| 窗口边长 | 7005 × 7005 块 |
| 采样步长 | 5 块/像素 |
| 左下角（i=0, j=0） | `(-3642.5, -3365.5)` |
| 右上角（i=1400, j=1400） | `(3357.5, 3634.5)` |
| 几何中心 | `(-140, 137)`（落在 i=700 与 i=701 之间，见下） |
| 种子 | `9139912035078620160` |
| 海平面 | `Y = 63` |

⚠ `j` 是 **z**、`i` 是 **x**。若你的图上南北颠倒，先查这一条。

⚠ **原点不是整数、中心不在某个像素上**。生成端用的是
`x = CX + (i - N/2.0) * step`（`N/2.0 = 700.5`，浮点除法），所以：

```
i=0    -> x = -140 + (0 - 700.5)*5 = -3642.5     （左下角）
i=700  -> x = -140 + (700 - 700.5)*5 = -142.5
i=701  -> x = -140 + (701 - 700.5)*5 = -137.5
i=1400 -> x = -140 + (1400 - 700.5)*5 = 3357.5   （右上角）
```

N=1401 是**奇数**，几何中心落在第 700 与 701 列之间，不对应任何单个像素。
**照抄公式即可，不要试图"修正"成整数原点。**

### 1.4 已知统计值（用于自检）

读完后先核对这些数，不一致说明读错了：

| 指标 | 值 |
|---|---|
| 水域占比（Y < 63） | **35.8131%** |
| 陆地占比 | 64.1869% |
| 最小高度 | 5.1449 |
| 最大高度 | 277.8583 |
| 中位数 | 79.41 |
| P90 | 146.21 |
| 非零像素 | 1,962,801（全部） |

### 1.5 定稿配色（青色水面色带）

这是用户认可的视觉口径。水面在 `Y=62.999` 处有一档青色 `(90,175,182)`：

```
STOPS  = [-64, 0, 50, 62.999, 63, 95, 150, 220, 320]
COLORS = [(12,37,72), (24,64,102), (42,103,135),
          (90,175,182),          // ← 水下青色，定稿视觉签名
          (73,123,69), (119,150,85), (148,128,93),
          (153,150,140), (245,245,245)]
```

光照（**方向错了会把山看成沟**，见 §五）：

```csharp
var L = new Vector3(-0.5f, -0.35f, 1.0f);
L = Vector3.Normalize(L);
float lit = Math.Clamp(
    (-gx * L.X - gz * L.Y + L.Z) / MathF.Sqrt(gx * gx + gz * gz + 1f), 0f, 1f);
```

---

## 二、算法移植：分层拆解

完整链约 **7,376 行 Java**，按依赖自底向上分 5 层。**建议按层移植、每层单独验收**。

### 2.1 层 0：噪声原语（~1,000 行）

| Java 类 | 行数 | 说明 |
|---|---|---|
| `RefNoise` | 722 | Perlin / Worley / warpPerlin / Curve3 / Clamped / Mapped / Terrace / Steps / Erosion / Blend / PowerCurve / Inverted |
| `RegionCellTable` | 269 | 256 项抖动表，**必须逐值照抄** |

⚠ **`RefNoise` 是 FreeTerraForged 派生实现，带 MIT 许可**（详见 `audit/新版地形第三方许可核查.md`）。
移植前请先确认许可义务。

⚠ **坐标系**：`RefNoise.Perlin` 的 `ofScale(seed, scale, octaves)` **内部已做 `1/scale`**，
不要在外面再除一次（历史上犯过，见 `CombineCandidate.java:313` 的注释）。

⚠ **整数溢出**：Java `int` 是 32 位有符号、回绕。C# 默认 `int` 同样 32 位，但
**`unchecked` 必须显式写**，否则 `checked` 上下文会抛异常。哈希函数里全是 `n * n * n * 60493`，
溢出是设计的一部分。

### 2.2 层 1：区域划分与几何（~1,300 行）

| Java 类 | 行数 | 关键常量 |
|---|---|---|
| `RegionPartition` | 157 | `REGION_SCALE=1200`、`WARP_SCALE=400`、`WARP_STRENGTH=200`、抖动 `*0.7` |
| `LocalBoundaryGeometry` | 1146 | `BAND_HALF_BLOCKS=120`、`HALO_BLOCKS=120`、`invertTol=0.02` |

`LocalBoundaryGeometry` 是整个移植里**最难的一块**：它要把 Voronoi 边从 u 空间反解回 world 空间
（牛顿迭代 + 折线加密），并且**已知在远坐标会失败**（见 §六）。

如果你只需要"看起来对"的地形、不要求与定稿逐位一致，可以考虑先跳过它、用简化版区域边界。

### 2.3 层 2：配方目录（~1,400 行）

| Java 类 | 行数 | 说明 |
|---|---|---|
| `RecipeMorphCatalog` | 711 | 18 个陆地条目 + 45 票槽，**种子消费顺序不能变** |
| `CombineCandidate` | 694 | 旧覆盖层 / Blender / mask 节点 |
| `Plains/Hills/Plateau/Badlands/Mountains Candidate` | 517 | 各族形态 |
| `OceanRecipeCatalog` + `OceanMorphConcepts` + `OceanTransitionCatalog` | 891 | 5 个海洋配方 + 过渡 |

**★ 种子消费顺序是硬约束**（改顺序 = 全体地形变化）：

| 内容 | 位置 |
|---|---|
| 10 对低地复合 selector/warp | `base+0..19` |
| 45 槽洗牌 | `base+20` |
| 微分区 warpX / warpZ / cell hash | `base+21 / 22 / 23` |
| 覆盖 mask（Worley 2250 + warp 333/2/250） | `base+4241`、游标 `base+24` |
| 族种子偏移 | plain 0 / steppe 0 / dales 33 / hills1 11 / hills2 22 / plateau 44 / badlands 55 / mountain 77 |
| S1 宏分布 | `+1700000 / +1710000 / +1720000 / +1730000`，`c` 用 `+7000` |
| 海洋池 | `SEED_OFFSET = 900000` |

`base = (int)(seed ^ (seed >>> 32))`，**32 位折叠 + 溢出回绕**，不能改成 64 位。

### 2.4 层 3：形态内核（~900 行）

| Java 类 | 行数 | 关键 |
|---|---|---|
| `NodeMorphEngine` | 160 | **唯一**节点形态内核；陆地保留历史 float 路径 |
| `FullMorphLand` | 706 | 1024 块 tile、query 半径 728、弦偏差 0.02、最大段长 2 |
| `MorphRecipe` / `MorphCatalog` / `RecipeJunctionMorph` | 51 | 接口与目录组装 |

⚠ **float/double 混算是刻意设计，不是疏漏**。`NodeMorphEngine.sample()` 里：

```java
if (legacy) { x = (float) x; z = (float) z; }   // 纯陆分支强制 float
```

纯陆走 float、含海走 double。**你用 C# 时必须用 `float` 类型显式转换**，
不能用 double 全程算完再转——结果会不同。

### 2.5 层 4：上下文与装配（~500 行）

| Java 类 | 行数 | 说明 |
|---|---|---|
| `CoherentRegionDistribution` | 81 | 600 微 / 1200 宏、`+0.30` 阈值的所在 |
| `ShallowRegionMatcher` | 124 | 浅海匹配，`REACH=240` |
| `LandRegionContext` | 52 | 不可变上下文（seed + scale） |
| `RegionalMorphWorld` | 140 | **唯一** `evaluate()` 高度接线 |
| `CoherentMorphWorld` | 80 | 站点身份 → 权重 → evaluate |
| `RecipeBoundaryWeights` | 58 | 配方并集外边权重 |

`CoherentMorphGallery`（100 行）是导出入口，告诉你**按什么顺序调用**——移植时先读它。

---

## 三、跨语言移植的具体陷阱

按"踩过才知道"的顺序排列：

### 3.1 `float` 是真 float，不是"小 double"

Java `float` = IEEE 754 binary32。C# `float` 相同，但**默认字面量是 double**：

```csharp
// ✗ 错：0.1 是 double，赋值给 float 会走 double 中间精度
float a = 0.1;

// ✓ 对：显式 float 字面量（对应 Java 的 0.1f）
float a = 0.1f;

// ✓ 对：强制转换，语义等同 Java 的 (float)x
float b = (float)someDouble;
```

Java 源码里凡是 `0.31f`、`1.2f` 这类，C# 必须写 `f` 后缀。

### 3.2 整数回绕

```csharp
unchecked {
    int hash = seed;
    hash ^= 1619 * x;
    hash ^= 31337 * y;
    hash = hash * hash * hash * 60493;   // 必须溢出回绕
    hash ^= hash >> 13;                  // >> 是算术右移，负数补 1
}
```

⚠ Java `>>` 对负数补符号位，C# 的 `>>` 对 `int` 同样补符号位（一致）；
但 C# 的 `>>>`（无符号右移，C# 11+）与 Java `>>>` 一致。**`seed >>> 32` 的语义要严格对应。**

### 3.3 `Math.floor` vs 类型转换

```csharp
// Java: Math.floor(-1.5) = -2.0
// C#:   (int)(-1.5)     = -1   ← 向零截断，错了
int idx = (int)Math.Floor(-1.5);    // ✓ 与 Java 一致
```

噪声格点索引、tile 索引全部依赖 `floor`。

### 3.4 反射必须改成直接调用

定稿链里有 **23 处 Java 反射**，C# 没有对等物。位置：

| 文件 | 反射用途 | 移植做法 |
|---|---|---|
| `RecipeMorphCatalog:520-542` | `Class.forName("LandCompositionGallery")` + 取 `SEED` | **直接传常量** `9139912035078620160L` |
| `LocalBoundaryGeometry:220-239` | 同上 | 同上 |
| `FullMorphLand:434-487` | 读 `CombineCandidate` 私有字段（`slots` / `blender` / `unmixable`） | 把字段改为 `internal` 或加只读属性 |
| `RecipeMorphCatalog:545-559` | 通用字段读取 | 同上 |

⚠ 反射在这里**只是工程手段**（跨包读私有状态），不是算法的一部分。
移植时把字段直接暴露即可，**不要**试图用 C# 反射复刻。

### 3.5 求值顺序

Java 的 `double` 链式运算在 C# 中默认一致，但：

- **不要加 `--use-fast-math` / `-ffast-math` 类优化**
- C# 的 `MathF`（float）与 `Math`（double）不要混用
- 累加顺序必须与 Java 相同（`for (i=0..n)` 不能改成 `foreach` 或并行归约）

---

## 四、建议的移植验收顺序

每步都有明确的通过判据，**不要跳步**：

### 阶段 1：数据读取

- 读 `morph-coastal.f64be`
- 核对 §1.4 的六个统计值
- 用 §1.5 配色渲染
- **判据**：水域 35.8131%、minY 5.1449、maxY 277.8583 三项全中

### 阶段 2：噪声原语（最长）

- 移植 `RefNoise` 的 Perlin
- **判据**：对同一 `(seed, scale, octave, x, z)`，你的输出与 Java 输出**逐位相同**
  （可用 `BitConverter.DoubleToInt64Bits` 比对）。建议取 1000 个随机点比对。

建议**先在 Java 侧导出参考值**（写个小 main 打印 N 个点），再用 C# 对齐。
本仓库的 `src/diagnostics` 里有现成的探针模式可以照抄。

### 阶段 3：区域划分

- 移植 `RegionPartition`
- **判据**：同样导出 N 个点的 `(regionId, edge)`，逐位相同

### 阶段 4：配方目录

- 移植 `RecipeMorphCatalog` + 18 配方
- **判据**：45 槽映射表 `slotEntry(0..44)` 完全相同；10 个复合条目的
  `selector` 种子按 §2.3 的顺序消费

### 阶段 5：形态内核

- 移植 `NodeMorphEngine`
- **判据**：对纯配方输入（单个 weight=1），输出与 Java 逐位相同
- ⚠ 这一层最容易出 float/double 混用问题

### 阶段 6：完整链

- 拼起来跑 1401²
- **判据**：整体 SHA-256 = `7BE0418369D097CE…`

---

## 五、光照方向事故（必须在你的渲染里避免）

本轮曾把光照写反，导致**山脊读成沟谷**，而且肉眼看起来"也挺合理"，很难发现。

### 5.1 正确公式

```csharp
// 屏幕坐标：x 向右、z 向下（数组 j 增大）
// 太阳方向（归一化）
var L = new Vector3(-0.5f, -0.35f, 1.0f); L = Vector3.Normalize(L);

// 梯度（step 是块/像素，本项目 5.0）
float gx = (h[j, i + 1] - h[j, i - 1]) / (2f * step);
float gz = (h[j + 1, i] - h[j - 1, i]) / (2f * step);

// ★ 是 -gx*L.X - gz*L.Y，不是 +gx+gz
float lit = Math.Clamp((-gx * L.X - gz * L.Y + L.Z)
                      / MathF.Sqrt(gx * gx + gz * gz + 1f), 0f, 1f);
```

### 5.2 怎么验证方向对不对（**不要靠肉眼**）

用一个**已知形状**测试：造一个圆锥（凸起）和一个坑（凹陷），渲染后检查：

| 形状 | 左上缘亮度 | 右下缘亮度 | 正确结果 |
|---|---|---|---|
| 圆锥 | **更亮** | 更暗 | 读作凸起 |
| 坑 | 更暗 | **更亮** | 读作凹陷 |

本仓库有现成的守卫脚本可参考：[tools/verify-light-convention.py](../tools/verify-light-convention.py)，
用同样的判据在 C# 侧写一个单元测试。**每次改渲染都跑一遍。**

### 5.3 水面着色造成的"凹陷错觉"

定稿渲染器对水陆用不同明暗系数：

```
shade_water = 0.72 + 0.28 * lit     // 水面几乎不随地形变化
shade_land  = 0.28 + 0.80 * lit     // 陆地强烈跟随地形
```

大片平坦的亮色水面看起来像一块台地，会把陆地反衬成凹陷。
**这是配色效应，不是光照反向**，判断方向只能靠 §5.2 的形状测试。

---

## 六、已知限制（别踩）

| 限制 | 说明 |
|---|---|
| **远坐标会失败** | `LocalBoundaryGeometry` 的 float 反 warp 在 1e6 块处残差 7.19 > 阈值 0.02，**未修**。你的游戏若需要大坐标，需自己改用 double 几何 |
| **不是生产代码** | 定稿地形在 `src/diagnostics`，`src/main` 生产生成器**完全没变**。要接进 Minecraft 需另做接线 |
| **未实机验证** | 全部结论来自离屏渲染，**没有跑过 Minecraft** |
| **无限世界未证明** | 1401² 窗口内正确，不代表任意坐标正确 |
| **标签口径 ≠ 高度口径** | 海陆比有两个数：高度 35.8131% / 标签 66.9330%，**不可混用**（见配套文档 §一） |

---

## 七、许可（移植前务必确认）

| 组件 | 许可 | 影响 |
|---|---|---|
| `RefNoise` 及其上游 FreeTerraForged | **MIT** | 允许闭源商业使用，但**必须保留版权声明** |
| `RegionCellTable` | 同上（表照录） | 同上 |
| 本项目原创部分 | All Rights Reserved | 由权利人单独授权，不自动随仓库授权 |

详见 [audit/新版地形第三方许可核查.md](../audit/新版地形第三方许可核查.md)。
⚠ 该审计**不是完整法律意见**，跨语言移植的许可适用性需自行确认。

---

## 八、最小可跑清单

如果你只想先看到图：

```
1. 读 forge-1.20.1-47.4.10-mdk/build/main-verify-restore/out/morph-coastal.f64be
2. 按 §1.2 解析、§1.3 映射坐标
3. 按 §1.5 配色 + §5.1 光照渲染
4. 核对 §1.4 的六个统计值
```

不需要 Java、不需要 Gradle、不需要 Minecraft。

如果要**可无限生成**，才进入 §二到 §四的算法移植。
