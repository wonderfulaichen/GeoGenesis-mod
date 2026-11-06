# GeoGenesis 地形链：性能与架构改进提案

来自 Theseus 接入时的实测。基于 `feat/hydrology-target` 提交 `2f825305`、`morph-coastal` 臂，种子 `9139912035078620160`，日期 2026-11-02。

下面的数字都在本机跑过。没验证过的部分单独写在“未验证”一节。这是建议，不是要求，取舍由你定。

## 结论

- 全链 **89% 的时间花在区域边界上**，算地形配方只占 8.5%。
- 同一层也是远坐标崩溃的来源，而且崩溃点比文档写的近得多，最近半径约 5.2 万块。
- 把“warp 反解 + 折线距离”换成“正向 warp 栅格标签 + 欧氏距离变换”，边界层约快 100 倍，没有反解，也就没有这条失败路径；代价是高度差 p99 约 1 块。

## 一、时间花在哪

JFR 采样 `MorphCoastalExport`（全图 1401²，3,220 个样本，JDK 17）：

| 调用 | 包含占比 |
|---|---:|
| `FullMorphLand$Tile.weights`（点到折线距离） | 55.1% |
| `LocalBoundaryGeometry.warpInverse`（建瓦片） | 33.2% |
| `FullMorphLand.heightForMerged`（配方与形态内核） | 8.5% |
| `OceanAssemblyCandidate.coastalProfile` | 1.1% |
| `ContinentField.sample` | 0.4% |
| `FullMorphLand.maskValue` | 0.4% |

自身时间前三是 `Tile.segmentOutsideBand` 25.7%、`Tile.bandDistance` 15.6%、`Tile.boxDistance` 9.5%。配方层已经很便宜，优化空间几乎都在边界层。

## 二、远坐标崩溃的实际位置

`LocalBoundaryGeometry` 的反解残差超过 `invertTol` 时直接抛 `IllegalStateException`。

| 测试 | 结果 |
|---|---|
| ±65,536 内 16,384 个瓦片全量扫描 | 27 个抛异常，零星分布 |
| 最近失败瓦片 | 中心 `(25088, 45568)`，半径 52,017，残差 2.6e-2，阈值 2e-2 |
| 16 条射线首次失败 | 半径 66,624—95,296，随方向变化 |
| 半径 128,000 及以外 | 随机 120 点全部失败 |

交接文档 §六写的是 1e6 处失败，实际早一个多量级。残差只略超阈值，说明这是 float 反解精度和固定阈值的问题，不是几何错误。扫描工具见附件 `TileScanProbe.java`。

## 三、边界层替代方案

思路：边界距离本来就是世界空间量，没必要先在 u 空间求出边再反解回世界。直接在世界空间栅格上算：

1. 逐格只做**正向** warp，取最近站点，得到区域标签。
2. 对每个区域，在其包围盒外扩过渡带后做两次精确欧氏距离变换（Felzenszwalb），得到每格到该区域边界的带符号世界距离。
3. 距离代入现有 `progress`，归一化后得到站点权重，形态内核不用改。

定稿窗口内与精确版对比，权重走真实高度链：

| 方案 | 权重 L1 p99 | 高度差 p50 | p99 | 最大 | 超 1 块占全图 | 耗时 |
|---|---:|---:|---:|---:|---:|---:|
| 现有精确版 | 基准 | 基准 | 基准 | 基准 | 基准 | 约 18 μs/点 |
| 局部一阶近似（位势差除以梯度） | 0.128 | 0.15 | 3.82 | 29.7 | 4.7% | 1.6 μs/点 |
| **栅格 4 块/格** | **0.023** | **0.10** | **1.05** | **8.0** | **0.41%** | **0.155 μs/格** |
| 栅格 8 块/格 | 0.046 | 0.17 | 2.00 | 16.3 | 2.6% | 0.22 μs/格 |

高度差单位是块，统计范围是过渡带内的点。方法见附件 `RasterBoundaryProbe.java`、`BoundaryApproxProbe.java`。局部一阶近似不推荐：位势里的绝对值项让梯度在坐标轴线上跳变，误差较大。

带来的变化：

- 没有反解，也就没有 `warp inverse residual` 这条崩溃路径，也不存在文档里担心的折叠多根问题。
- 不再需要瓦片、折线、线段索引和 LRU 缓存。
- 按区域成批求值，天然适合多线程。
- 全链约 2.5 μs/格，比现在快约 8 倍，这是由上面的占比估算的，不是整链实测。

## 四、其他实测问题

1. **种子锁**：`LandCompositionGallery.buildCombine` 里写死了 `long seed = SEED`，`FullMorphLand` 因此拒绝其他种子。把种子作为参数传入即可解除。
2. **`Math` 不跨实现确定**：`Math.pow/sin/cos` 走 HotSpot 本机数学库。同一份代码在 Zulu 17 上重跑，与定稿差 4 个像素，差 1 ulp；关掉本机数学库（`-XX:-UseLibmIntrinsic`）后差 12,838 个像素，最多 2 ulp。若要 SHA 能在别的机器复现，全链改 `StrictMath`。改后的严格基准 SHA 是 `fb4d0e16098fd6eaf5dc8022bac0156ee79174a7cb30b419533fcf3635555bc9`，相对定稿最大差 1.42e-14 块，水陆无翻转。
3. **基线可独立复现**：不需要 Forge 和 baseline classpath。用替身替掉 `ForgeConfigSpec`、NightConfig，剥掉 `Codec` 外壳，纯 `javac` 就能编出 `morph-coastal` 单臂，40 秒出图。需要的话我们可以把这套工具给你。

## 五、移植说明勘误

`GeoGenesis-20261103-CSharp-port.md`：

| 位置 | 现状 | 实际 |
|---|---|---|
| §2.5 | 让移植者走 `CoherentMorphWorld`、`RegionalMorphWorld` | 定稿入口 `FullMorphGallery` 不经过它们；实际链是 `FullMorphLand` + `OceanAssemblyCandidate` + `ContinentField` + `HeightCurve` |
| §2 | 完整链约 7,376 行 | 漏列 `OceanAssemblyCandidate`（711 行）、`ContinentField`、`HeightCurve`；另一方面 `TectonicField`、`TypeLandShape`、`RegionCompositionCandidate`、`ShallowSeaIslandCandidate` 都不在定稿路径上 |
| §四 阶段 6 | 判据是整体 SHA 相等 | 换 JVM 或换语言都做不到，见第四节第 2 条 |
| §六 | 1e6 处失败 | 最近半径 52,017 |
| §〇 | 可换任意 seed | 定稿链当前只认正典种子 |

C# 侧已逐位复现：全图 SHA 在 .NET 9 与 Unity 自带 Mono 上都等于严格基准。C# 移植有两个额外的坑，供后续参考：Mono 不收窄 float 中间值，必须显式 `(float)(a*b)`；浮点转整数要手写 Java 的饱和语义。

## 六、附件

在 `handoff/attachments/theseus-proposal-20261102/`：`TileScanProbe.java`、`RasterBoundaryProbe.java`、`BoundaryApproxProbe.java`，都放在 `candidate` 包下运行。后两个调用了我们替身里的 `ForgeConfigSpec.loadFrozen`，在你的环境里换成 `FullMorphGallery` 构造函数里的配置加载即可。热点数据用 JFR：`-XX:StartFlightRecording=settings=profile`。

## 七、未验证

- 只测了这一个种子和定稿窗口，其他种子未测。
- 栅格方案目前只比了数值，还没有和定稿图做观感对比。
- 4 块/格是我们项目的区域分辨率。Minecraft 若要逐块精度，需要更细的栅格，成本和精度未测。
- 全链 8 倍提速是估算值，以实测为准。

## 八、我们这边的做法

配方层保持与你的代码同构，方便继续跟进你的更新；边界和求值驱动换成上面的栅格方案，再按我们的区域预生成接线。你后续改配方、改形态，我们能较低成本地跟上；改了边界算法或接口的话，告诉我们一声就好。
