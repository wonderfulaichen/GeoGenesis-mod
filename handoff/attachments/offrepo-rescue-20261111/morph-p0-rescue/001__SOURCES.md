# P0 提取清单 — `com.geogenesis.worldgen.terrain.morph`

> 拟生产包。**尚未**接入 `CellGenerator` / `GeoGenesisGenerator` / 缓存 / 存档配置（P0 范围外）。
> 所有源码在 `src/main/java/com/geogenesis/worldgen/terrain/morph/`，共 **21 个 .java**。
> 对拍工具在 `tools/parity/java/`（**不属于**生产包）。构建/验证：`build.ps1`。

## 依赖边界（实测）

生产包对包外的<b>唯一</b>依赖：

```java
import com.geogenesis.worldgen.terrain.ContinentField;   // 大陆性 c（海岸线来源）
import com.geogenesis.worldgen.terrain.HeightCurve;      // e ↔ Y 唯一换算点
import com.geogenesis.worldgen.terrain.TerrainParams;    // 参数记录（不读配置、不持静态状态）
```

零 `java.lang.reflect` / `Class.forName` / `setAccessible`；零 `net.minecraft` / `com.mojang` /
NightConfig / Forge 客户端 / diagnostics / Gallery。其余 import 全部是 `java.util.*`。

## 文件与职责

### 入口

| 文件 | 职责 | 来源 |
|---|---|---|
| `MorphTerrainSampler.java` | **唯一入口**。`sampleE(wuX,wuZ)` → e；`height(wuX,wuZ)` → Y（`heightFromE(sampleE)`，**不做额外换算**）。持有 `ContinentField` + `HeightCurve` + 整条链。 | 新写（取代 `DoubleGalleryCandidate`） |

### 组装 / 目录

| 文件 | 职责 | 来源 |
|---|---|---|
| `MorphAssembly.java` | 从种子一次性导出四件套：分区 / 45 票槽 / 山形遮罩 / 覆盖族 + 阈值。**显式复刻参考的种子游标顺序**（20 个复合种子空转 → shuffle → warpX/Z → 分区 → 遮罩）。 | 新写（取代 `LandCompositionGallery.buildCombineRegional` 的反射调用 + `CoherentRegionDistribution.microPartition`） |
| `MorphCatalog.java` | 18 条目目录（5 低地 + 10 复合 + 台地/劣地/山地），拆 `raw`/`amp`/`bias`/`clamped`。台地对外口径 0.9，旧口径 1.05 单独命名。 | `RecipeMorphCatalog`（去 `original()`/`galleryOriginal()`/`canonicalSeed()` 反射；访问器替代 `field()`） |
| `MorphRegionContext.java` | 区域上下文 + `SlotCategory` 标签。断言"目录算的类别"与"票槽展开的类别"逐槽相同。 | `LandRegionContext`（去 `CoherentRegionDistribution` + `FullMorphLand.combineConstant` 反射） |
| `MorphScale.java` | `GROUND_E = 0.02` / `E_SPAN = 1.50` 的唯一来源。**先加基准再截断**。 | `CombineCandidate.GROUND_E/E_SPAN`（去反射读取） |

### 内核

| 文件 | 职责 | 来源 |
|---|---|---|
| `MorphKernel.java` | 唯一节点形态内核：信号层 + 线性外壳分别混合。含 `preBad`→劣地重采样→山地域扭曲的顺序。`rounded()` 恒等（禁量化）。 | `DoubleMorphEngine` |
| `MorphRecipe.java` | 条目对外契约（`raw`/`amplitude`/`bias`/`role`/`land`/`clamped`）。 | `MorphRecipe`（原文同） |
| `MorphLandRecipe.java` | 18 条目按双坐标内核重表达；复合 = 成员线性外壳的加权平均。 | `DoubleLandRecipe` |

### 地形场

| 文件 | 职责 | 来源 |
|---|---|---|
| `MorphLandField.java` | 世界固定 1024 块 tile + 局部 Voronoi 几何 → 条目权重 → `regionHeight` → 覆盖层 → `e`。tile LRU(8)、last-point 缓存、线段/格/分块 bbox 粗剔除。 | `DoubleFullMorphLand`（去全部反射：`read`/`asNode`/`combineConstant`/`slotCountOf`/`oldCoverageFamilyOf` + synthetic-lambda 拆包） |
| `MorphOceanAssembler.java` | 海陆衔接：深海噪声 → 浅陆架 → 岸段（cliffFactor + 岸宽随陆高后退）→ 陆地。`sea()` 读**实际高度**分类。 | `DoubleOceanAssemblyCandidate`（去 `ProvinceContinentField` 重载、去 `CombineCandidate` 构造器） |

### 几何

| 文件 | 职责 | 来源 |
|---|---|---|
| `MorphBoundaryGeometry.java` | 真实区域边界的局部 Voronoi 几何：u 平面精确半平面交 + 反 warp 阻尼牛顿映回 world + bbox 索引。**导出/CSV/剖面代码已删**（约 230 行）。 | `DoubleBoundaryGeometry`（去 `galleryOwner`/`atGallery`/`readGallerySeed`/`buildGalleryCombine`/`fromOwner`/`readField`/`tryReadField`；工厂改为显式入参） |
| `MorphRegionPartition.java` | 抖动 Voronoi 分区（格心 id + 边界距离）。新增 `seed()/frequency()/warpX()/warpZ()` 访问器。 | `RegionPartition` |
| `MorphGeometryTransform.java` | warp 的 double 前向 / 解析 Jacobian。 | `GeometryTransform`（去反射，`STRENGTH` 显式） |

### 噪声

| 文件 | 职责 | 来源 |
|---|---|---|
| `MorphNoise.java` | 参考算子的 float 实现（Perlin/PerlinRidge/Billow/Cubic/Worley*/Terrace/Steps/Erosion…）。每个带状态节点新增**显式访问器**（`seed()/frequency()/gain()/…`）；新增 `ScalarIdentity`、`DualNode`。 | `RefNoise` |
| `MorphNoiseEval.java` | **双坐标求值器**（double 版全算子），未知节点抛异常不兜底。`lattice()` 越界抛异常不截断。 | `DoubleTreeFixed`（去全部反射 → 访问器；`precise()` 取代 `DualCoordinateCall`） |
| `MorphCellTable.java` | 256 项抖动表（原值照录）。 | `RegionCellTable` |

### 配方

| 文件 | 职责 | 来源 |
|---|---|---|
| `PlainsRecipe.java` | `makePlains` / `makeSteppe`（含地理修正变体）。 | `PlainsCandidate`（+`heightNode()`） |
| `HillsRecipe.java` | `hills_1` / `hills_2` / `dales`（含修正变体）。 | `HillsCandidate`（+`heightNode()`） |
| `PlateauRecipe.java` | `makePlateau`。 | `PlateauCandidate`（+`heightNode()/baseNode()/terraceNode()`） |
| `BadlandsRecipe.java` | `makeBadlands`。 | `BadlandsCandidate`（+`heightNode()/hillsNode()/maskNode()`） |
| `MountainsRecipe.java` | `mountains_1/2/3`。 | `MountainsCandidate`（+`heightNode()`） |

## 明确**未**提取（按任务要求）

- 131 个 `head-terrain-candidate` 探针/诊断类（`*Probe`/`*Check`/`*Gallery`/`*Export`/`*Audit`/`*Diagnose`/`*Census`/`OceanMorphConcepts`/`ShallowSeaIslandCandidate`/`ProvinceContinentField`/`UnifiedTerrainCatalog`/`CoherentRegionDistribution`/`LocalBoundaryGeometry`/`RegionalMorphWorld`/…）
- `DoublePerlinCandidate` + `PerlinScalarCache`（缓存臂：本包节点不缓存 Perlin 包装，见下）
- `DoubleTerraceCheck` / `DoubleSeamProfile` / `DoubleNodeCensus` / `DoubleFarDiagnose` / `DoubleFarBlock` / `DoubleGalleryProbe` / `DoubleFullGallery`
- `OceanAssemblyCandidate`（float 参考臂）、`FullMorphLand`（float 参考臂）、`NodeMorphEngine`、`MorphCatalog` 家族中未用到的条目工厂
- `DualCoordinateCall` 的 `legacy()`：float 臂在本包里没有调用者

## 关键设计决定（读代码前必看）

1. **`rounded()` 必须是恒等。** 历史上的"取整"包装会把噪声值域量化，产生阶梯等值线 ⇒ 密集平行细线 / 同心环梯田。已确认为伪影来源，故保留形状但写成恒等并加注释。
2. **覆盖层用共享台地（Terrace 1.05），条目对外用修正台地（0.9）。** 两个值都显式命名（`LEGACY_TERRACE_IN_MAX` / `PLATEAU_TERRACE_IN_MAX`）。混用会让覆盖后高度整体偏低/偏高。
3. **覆盖族必须以 double 求值。** 早期版本用 `height01(float)`（"预览 float 臂"）导致正典图最大偏差 0.0075 格；改走 `MorphNoiseEval` 后逐位归零。这是对拍发现的，不是推理出来的。
4. **`MorphAssembly` 的 20 个空转种子不能删。** 它们不产出对象，但决定 shuffle/分区/遮罩的种子；删掉 ⇒ 换图且只换一点点。
5. **`shuffle` 必须用 JDK `Collections.shuffle`。** 自己写 Fisher-Yates 在相同种子下给出不同排列。
6. **线程模型是互斥，不是无锁。** `MorphLandField` 的 tile LRU / last-point 缓存 / 计数器共用一个监视器。**没有取消 API**。
7. **world 几何是近似。** 反 warp 牛顿解残差小不证明单射 ⇒ 不承诺无限世界正确（类注释已写明）。

## 验证

`build.ps1` 的三段式：编译生产包 → 编译对拍工具 → 跑 7 道门禁。

| 门禁 | 内容 | 结果 |
|---|---|---|
| g1 | 种子 12345，陆地 e，241² 网格 + 6 远场点 | 逐位相同 |
| g2 | 种子 12345，海陆 e，241² + 远场 | 逐位相同 |
| g3–g5 | 种子 0 / 2 / −1，世界 e，121² + 远场 | 逐位相同 |
| g6 | **正典种子 1401² 高度图** | SHA = `BEA0A47C…` **与锚点一致** |
| g7 | **种子 12345 1401² 高度图** | SHA = `340224…F0AC` **与锚点一致** |

`BEA0A47C14D32FA9E2654C77CC6984999004E137E9E6BDE8A05D3E2B2A393965` 是候选包
`DoubleFullGallery` 的 double 臂锚点；`340224069AB0D01CF668988436A8FDE8E9A8270EA3C938C536A5A6CFBC28F0AC`
是 `MultiSeedChain full 12345` 的锚点。两个都是整幅 1401² 数组的 SHA-256，
逐位相等意味着<b>公式、种子顺序、canonical 分支全部保持</b>。

## 后续（P1，不在本次范围）

- `CellGenerator` 接线：需要决定 morph 的 e 与既有 `TectonicField`/`StratumField`/侵蚀/水文 如何共存
- 缓存身份：接生产时必须把 morph 的产出纳入 `PreviewDisplay.CACHE_SCHEMA_VERSION`
- 存档/配置：morph 目前只吃 `TerrainParams`，不读 `run/config/geogenesis-common.toml`；是否加开关需单列
