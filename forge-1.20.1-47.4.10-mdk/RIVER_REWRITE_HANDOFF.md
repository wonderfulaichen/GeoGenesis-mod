# GeoGenesis 河流/湖泊系统 — 重写交接文档

> 用途:旧对话已把河流系统**全部清空**,本文件供新对话接手,按 DW(Dynamic Waters)基准从头重写。
> 日期:2026-08-25

---

## 1. 当前状态(已做完)

- **河流采样、`RiverCarver` 雕刻已全部 no-op**:`RiverNetwork.sampleRiver` 恒返回 `RiverSample.NONE`,`RiverCarver.carve` 恒返回 `CarvedColumn.NONE`。
- **效果**:世界生成**不再刻任何河流/湖泊**(湖泊雕刻也走 `sampleRiver` 通道,一并消失)。
- **编译状态**:`gradlew compileJava` → `BUILD SUCCESSFUL`。
- **13 个河流包 `*Probe.java` 诊断文件已删除**(深度依赖旧河流内部)。
- **保留的壳**:`FlowRiverBuilder`(构造器 + 空 `buildPlate`/`regionMainRivers`/`warmRegionsAround` 存根)、`RiverNetwork` 门面、`RiverCarver` 门面、`RiverSegment`/`RiverNode`/`RiverPlate`/`RiverSample`/`RiverSegmentType`/`RiverCarveParams` 等数据类。

> 外部调用方(`GeoGenesisGenerator`、`GeoGenesisTerrain.carveRiver`、`CellGenerator`)已兼容 no-op,**无需改动**即可让 mod 跑起来(只是没河)。

---

## 2. ★ 核心需求纠正(最重要,新对话务必照此)

| 错误旧认知 | 正确要求 |
|---|---|
| 有"主河 / 支流"之分,按标签分级 | **没有主河/支流这种说法**。河流**只有两类:大河、小溪** |
| 按 `RiverSegmentType.MAIN`/`TRIBUTARY` 标签决定深度/保留 | **只按"宽度"分级**:够宽 = 大河(洼地也刻槽穿过);太细 = 小溪(盆地里自然消失) |
| 按"地形河/山地河"分类 | 山地河是**地形驱动**的同一套河,不是独立类型 |

- 分类口径:DW 里河只有 `River`(大河)和 `Stream/Creek`(小溪),**从不使用"main/tributary"语义**。
- 重写时:`RiverSegmentType` 不要引入 `MAIN`/`TRIBUTARY`;若需 `MOUNTAIN` 也仅是"贴地阶地+瀑布"的表现差异,**不是分类学上的第三类**。

---

## 3. DW 重写管线(要做的)

以 `d:\Office software\Development Project\GeoGenesis-mod\参考\river\dynamicwaters-11.1.2`(已反编译字节码)为唯一基准,把生成→采样→雕刻→山地→湖泊→瀑布→河口做成**一条连贯管线,一次写完再实测**,禁止边写边打补丁。

1. **主河动线** — `FlowRiverBuilder` 改用 `MeanderingPath`(分形蜿蜒弦,bisections=7,宽 11/13)生成,**抛弃旧的 D8 流域追踪 + basin 归属**。
2. **山地河** — 新增 `MountainRiverPath`:贴地阶地 + 跌落处生成瀑布。
3. **采样评分** — `RiverNetwork.sampleRiver` 还原为**遍历本列 3×3 邻域所有河,取 `minCarve`(最深雕刻)**,重叠河自然合并、不打架。**不做 mergeConfluence、不"选最近"。**
4. **横截面** — `RiverCarver` 深 V 峡谷 → **DW 浅平滑阶梯槽**:`carve = (smoothstep(d/w)-1)*carveMaxDepth`(深 ≈4 块),水面贴地、只切不抬。
5. **湖泊** — `LakeBuilder`(D8 洼地盆地)雕刻**必须回到 `sampleRiver` 同一条通道**(当前被清空后湖泊也没了,重写要恢复)。
6. **瀑布 / 河口** — 河床落差处瀑布、入海口喇叭展宽。
7. **原则**:纯函数、seed 派生、无 tile 局部状态(现有基础设施已满足)。

---

## 4. 文件清单

| 文件 | 状态 | 新对话动作 |
|---|---|---|
| `FlowRiverBuilder.java` | 已清空(存根) | **重写**:MeanderingPath 弦 + MountainRiverPath 山地河 + 构造器 |
| `RiverNetwork.java` | `sampleRiver` 清空,门面/9邻合并/`closestOnPath` 保留 | **重写** `sampleRiver` 为 DW minCarve;删 `mergeConfluence` |
| `RiverCarver.java` | `carve` 清空,旧逻辑在 `carve_old` | **重写** `carve` 为 DW 浅平滑阶梯槽;删 `carve_old` 及无用私有方法 |
| `RiverCarveParams.java` | 默认值 | 改默认值为 DW 口径(浅、深≈4) |
| `RiverSegmentType.java` | 枚举 | 删 MAIN/TRIBUTARY 语义;如需 MOUNTAIN 仅作表现标记 |
| `RiverSegment.java` / `RiverNode.java` / `RiverPlate.java` | 数据类 | **保留**(跨 plate 无缝基础设施,勿动结构) |
| `RiverSample.java` | 数据类 | 保留 |
| `MeanderingPath.java` | 已移植(DW 分形) | **保留,直接用** |
| `LakeBuilder.java` | D8 洼地湖盆 | **保留**,雕刻接回 `sampleRiver` |
| `ESampler.java` / `FlowField.java` / `RiverGrid.java` / `GroundwaterField.java` | 支撑类 | 保留(按需) |
| `GeoGenesisTerrain.java`(`carveRiver`)/`GeoGenesisGenerator`/`CellGenerator` | 调用方 | **不改**(已兼容 no-op) |

---

## 5. ★ 历史踩坑(新对话务必避开,这些是之前反复返工的根因)

1. **"两条河打架 / 断面"** — 旧代码用 `mergeConfluence` 把两条相邻河融合成"第三条河"(水面/宽度是混合体),与 DW 语义冲突 → 重叠河段打架、出现断面。**根治:每列取 `minCarve`(最深雕刻),不融合。**
2. **"一段小溪一段大河"** — 旧代码 per-region 随机宽度(11/13)+ 按区域切换 → 同一河忽宽忽窄。DW:宽度由动线决定、沿程单调(锥形向下游增宽),**恒定或随弦平滑变化**。
3. **"高度错位 / 爬坡"** — 旧代码路径可任意上坡、雕刻深度吃局部地形 `origHeight`(`depthFactor`/悬崖保护)→ 河床逐列起伏、支流汇入主河床面浅数块 = 水"往上爬"。DW:深度 = `waterY - depth` 纯随统一水面,**不继承局部地形**。
4. **"深 V 峡谷"观感不对** — 旧 `RiverCarver` 是深 V;DW 是浅平滑阶梯槽。改成 DW 款。
5. **禁止半旧半新** — 之前多次"一半旧框架 + 一半新逻辑"导致越改越怪。本次**整段重写**,不要在新版里残留 `basinX`/`downstreamBasinX`/`mergeConfluence`/`MAIN`/`TRIBUTARY` 等旧概念。
6. **跨 plate 无缝** — `sampleRiver` 必须合并 9 邻 plate 段(旧 bug:只查本 plate → 沿 128wu 网格出现垂直崖)。保留 `closestOnPath` 胶囊 SDF + 包围盒预筛的性能做法。

---

## 6. 验证方法

- 节点连续性:`gradlew runRiverQuickProbe`(要求 node continuity 100%)。
- 剖面三线图(地形/水面/床):重写后需重建 `FlowRiverProbe` 类(已删)输出 ASCII。
- 实机目检:`gradlew runClient`,重点查:**两条河重叠不再打架、无断面、无垂直崖、河床单调下降、大河/小溪按宽区分、湖泊与河衔接无缝、山地瀑布、入海口展宽**。

---

## 7. 参考资源

- DW 反编译基准:`d:\Office software\Development Project\GeoGenesis-mod\参考\river\dynamicwaters-11.1.2`
  - 重点类:`HydrologyManager`(getRiverCarve = minCarve)、`MeanderingPath`(分形弦)、`MountainRiverPath`(山地河)、`River`、`Config`、`FractalRiverFunction`。
- 平台:Java 21 + Minecraft Forge 1.20.1(GeoGenesis mod)。
