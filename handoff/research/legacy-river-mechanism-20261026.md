# 旧河流机制研究与当前链路对照

> 研究快照整理入库于2026-10-26。下文“本轮不提交/未改既有文档”描述的是之前只读研究阶段；用户随后要求整理提交。研究结论未授权实施，本次只保存现有River检查点、补充纠偏与交接。原本地计划保留不删。


## 1. 研究边界及证据可信度

用户要求先研究以前移除的旧机制，本轮只读，不恢复旧链、不改生产源码、不改TOML、不提交、不启动游戏或重新跑大图。已有未提交的River实现与其它dirty文件保持原样。

基准：`b9b55ee`（旧链移除前快照）→ `7e69dfc`（退役）。旧机制不是物理删除，而是从`src/main`迁至`src/diagnostics`。主线程实际用`git rev-parse b9b55ee:<旧路径>`与`git hash-object <当前诊断路径>`逐一核验，下列五文件Git blob完全一致，因此下文可以链接现存源码，而不是引用无法打开的历史路径：

| 文件 | Git blob |
|---|---|
| HydrologyBlockCarver | `98c1178268c69f134d756ff57e5cd42127cb4954` |
| HydrologyExperimentEngine | `7fb4db7ab38725468297a1b94c8343182643bdaf` |
| RiverLineNetwork | `aec93ca2e6c5dba9c54eac610b43178ad2fe32a8` |
| RiverLineRegion | `79794e187528c9ea911ad7f9311fffcb8b59349b` |
| RiverLineParams | `6c903fefb5e5b07fd9786983eceae58024a44c17` |

研究采用两路独立只读核查，主线程复读核心实现并纠正代理中的过度结论。历史注释/提交中的性能与PASS数字仅为历史记录，非本轮复测。现存诊断文件带有不同年代的互相冲突注释，以下以实际分支和常量为准。

## 2. 真正的旧生产调用链

移除前`HydrologyChunkEngine.calculate`在旧链分支调用：

```text
GeoGenesisTerrain
  → HydrologyChunkEngine.calculate
  → HydrologyBlockCarver.carveChunk / carveColumn
  → HydrologyExperimentEngine.sampleBlockAll
  → RiverLineNetwork.sampleAll（查询3×3邻区）
  → region（本区final，依赖邻区pass1而非邻区final递归）
  → build → traceRiver → commitRiver → RiverPolyline
```

门面把路线与落块连接起来，并统一坐标换算：
[HydrologyExperimentEngine.java:178-211](../../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/hydrology/HydrologyExperimentEngine.java#L178-L211)。

必须区分两种旧路线：

- **移除前代码默认路线**：FlowField填洼/累积 → D8追踪 → 有界粒子几何 → smoothPath → 河道采样和断面。
- **旧链中的可选流体骨架路线**：`buildSkeletonRivers`调用TerrainFlowSim，成功就提前返回，部分普通路线后处理不会执行。它不是当前model链，也不能把这个分支的能力当成普通路线的能力。

`flowSkeletonRouting`实际常量为false，尽管上方旧注释仍写“默认开启”；移除前配置默认同为false。本轮未恢复当时实际TOML，不据默认值断言用户某次实机一定跑哪条：
[RiverLineNetwork.java:3986-4024](../../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/hydrology/riverline/RiverLineNetwork.java#L3986-L4024)。
分叉也有注释漂移：实际`ForkParams.defaults()`传true，不能照“默认关闭”的注释下结论：
[RiverLineParams.java:391-394](../../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/hydrology/riverline/RiverLineParams.java#L391-L394)。

## 3. 最有价值的经验：分段存储不等于属性重新起算

### 3.1 河线本身携带节点属性

旧`RiverPolyline`保存`nodes / surfaceY / width / depth / fallDrop`等逐节点数组。没有跨区全局River ID；`level`只是分支层级，局部`parentId`用于分叉随机源，也不是整条水系身份：
[RiverLineRegion.java:16-79](../../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/hydrology/riverline/RiverLineRegion.java#L16-L79)。

`smoothPath`曲线化几何时，同步插值水面/宽/深，而不是先生成新折线、再从另一张粗格Q场重新猜属性：
[RiverLineNetwork.java:3138-3191](../../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/hydrology/riverline/RiverLineNetwork.java#L3138-L3191)。
查询时始终取本段端点`i,i+1`，水面/宽/深用同一个投影`t`插值。历史提交`18ab351`修过端帽把属性错读到下一段的问题，这类经验值得保留：
[RiverLineNetwork.java:4611-4654](../../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/hydrology/riverline/RiverLineNetwork.java#L4611-L4654)。

**启示**：连续性需要连接点的几何与属性契约；并非必须把所有源到海路径强行合成一个跨区全局对象。分段可合法存在，分段属性任意重置才需要修复。

### 3.2 汇流使用承接河的实际汇入节点

普通旧路线提交支流时，河尾继承承接河交汇节点的水面；登记给后续支流的也是形态/瀑布处理后的真实水面：
[RiverLineNetwork.java:1149-1185](../../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/hydrology/riverline/RiverLineNetwork.java#L1149-L1185)。

形态收尾之后`enforceConfluenceMonotonic`找`recvIdx`，把支流尾几何吸附到该节点，并把**该节点到承接河下游**的宽深设为不小于支流尾值；最多3遍松弛：
[RiverLineNetwork.java:1466-1505](../../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/hydrology/riverline/RiverLineNetwork.java#L1466-L1505)。

这是宽深下界修正，不是严格流量守恒求解；不能把`max(width)`当成“各支流水量已正确相加”。该函数只在普通路线build结尾调用；可选流体骨架成功提前return，不能声称两个分支都完整消费它。

**当前确证差异**：`propagateConfluence`直接取`downSeq[0]`，并压小上游末段；旧版使用的是`recvIdx`，向下游传播。两者既取样位置不同、传播方向也不同，不能称“等价简化”。
[ModelHydrologyProvider.java:3809-3837](../../forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/hydrology/model/ModelHydrologyProvider.java#L3809-L3837)。

### 3.3 跨区不只交位置，还交沿程状态

旧版双pass依赖固定邻区pass1，避免递归邻区final：
[RiverLineNetwork.java:342-407](../../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/hydrology/riverline/RiverLineNetwork.java#L342-L407)。

`OutletSeed`携带世界坐标、上游累积面积、水面、层级与方向；普通路线接收后传入`traceRiver(seed.accum)`及`commitRiver(seed.surfaceY)`，且续流不重复河头淡出：
[RiverLineRegion.java:927-945](../../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/hydrology/riverline/RiverLineRegion.java#L927-L945)、
[RiverLineNetwork.java:675-699](../../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/hydrology/riverline/RiverLineNetwork.java#L675-L699)、
[RiverLineNetwork.java:1047-1050](../../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/hydrology/riverline/RiverLineNetwork.java#L1047-L1050)。

**当前确证差异**：`BoundaryPort`/`WireIn`提供更明确的锚点、下一步、方向与owner，但不带旧式累积面积/水面。当前宽度仍沿最终shaped几何回查本窗粗格Q。几何接力与河道属性接力是两件事；前者更严格，不代表后者已继承。
[BoundaryPort.java:36-41](../../forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/hydrology/model/BoundaryPort.java#L36-L41)、
[WireIn.java:26-27](../../forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/hydrology/model/WireIn.java#L26-L27)、
[ModelHydrologyProvider.java:3888-3906](../../forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/hydrology/model/ModelHydrologyProvider.java#L3888-L3906)。

不能照抄旧累积公式：旧`pushCell`的后续格用`initialAccum + field.accumAt(c)`，重叠窗可能重复计上游贡献，尚未用真实样本证明重复量：
[RiverLineNetwork.java:1703-1708](../../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/hydrology/riverline/RiverLineNetwork.java#L1703-L1708)。
可选流体骨架分支虽然读取incoming的位置，后续仍从本地`res.accum`构宽；不能因种子有字段就认定该分支消费了全部字段。

## 4. 河道断面是独立职责，而不是身份合并的附带结果

旧版从河线采样出距离、宽、深、水面后，在Carver构造：

```text
t = saturate(distance / halfWidth)
profile = 1 - t
bedTarget = carveSurface - depth × profile
cutNeeded = max(0, original - bedTarget)
```

并有独立岸坡/谷壁过渡、灌水门控及瀑布冻结规则：
[HydrologyBlockCarver.java:710-715](../../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/hydrology/HydrologyBlockCarver.java#L710-L715)、
[HydrologyBlockCarver.java:815-834](../../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/hydrology/HydrologyBlockCarver.java#L815-L834)、
[HydrologyBlockCarver.java:872-897](../../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/hydrology/HydrologyBlockCarver.java#L872-L897)。

**当前生产路径**：仍`depth=1.0`加兜底，`carved=eroded-depth`，没有随横向距离变浅的断面；这可以从公式确认，不是因为“列结构没有half字段”才能判断。列结构输出最终高度，本来可以在上游消费half后不再携带它。
[ModelHydrologyProvider.java:2463-2481](../../forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/hydrology/model/ModelHydrologyProvider.java#L2463-L2481)。

旧版宽度律的独立参考面积与成河阈值分离值得继承，但不搬旧参数：旧默认半宽1–10，对当前用户要求的**全宽1–12**并不等价；wu/block以及面积/Q的量纲也不同。
[RiverLineParams.java:440-443](../../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/hydrology/riverline/RiverLineParams.java#L440-L443)、
[RiverLineParams.java:523-530](../../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/hydrology/riverline/RiverLineParams.java#L523-L530)。

## 5. 必须撤回的上一轮判断

这些纠偏优先于旧研究稿；本轮未改写已有HYDROLOGY/交接稿，以免把只读研究扩展为项目编辑。

1. **“多个半宽相加→宽块”不成立。** 当前`nearest`按距离选段，宽度IDW为`Σw·half/Σw`，不是和；完全相同候选整体复制时，平均值不变，布尔覆盖并集也不变。只重复部分候选可以改变相对权重；几何略有偏移也可以改变并集面积，但都要具体取证。
   [ModelHydrologyProvider.java:4058-4115](../../forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/hydrology/model/ModelHydrologyProvider.java#L4058-L4115)。
2. **旧smin不是幂等集合并。** 旧`dist=smin(dist,d,k)`即使输入相同也会下降；例如`smin(3,3,4)=2`。不能把旧版描绘成“重复段绝对不影响雕刻几何”。宽度IDW不求和与距离smin会受重复次数影响是不同事实。
   [HydrologyBlockCarver.java:549-579](../../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/hydrology/HydrologyBlockCarver.java#L549-L579)、
   [HydrologyBlockCarver.java:1028-1032](../../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/hydrology/HydrologyBlockCarver.java#L1028-L1032)。
3. **之前239组不是“逐点完全重复”证书。** 当时签名将坐标round到0.1并排序，丢失方向和节点顺序；最多说明该签名相同，不能据此删生产几何。
4. **颜色切换/degree==2不证明河流断裂，更不能证明宽深错误。** 像素相邻可能来自不同河、跨区重复代表或真实接力；没有拓扑及属性证据就不能定根因。“HALO带里68%”只是分布，且未与该带面积/骨架密度作对照，不是因果证明；未定向的两侧end组合也不能当上游→下游流向证据。
5. **无向分量任一点触水≠每条有向河最终到湖海。** 前轮448/452还使用粗格水体、邻区取并集和像素邻接；不能作为用户全周期已通过的证明。外框触边也只能记待续查，不能直接宣布是假孤立。
6. **JOIN有别的owner≠整条River顺序正确。** 当前未提交`buildRivers`在JOIN落入下游中段时，仍把整个下游trace从头拼入：`[5,6,2] + [0,1,2,3,4]`产生`[5,6,2,0,1,2,3,4]`，而应接的后缀为`[5,6,2,3,4]`。主线程按当前循环做了内存公式复算（非Java集成测试）。
   [DeterministicSkeleton.java:284-326](../../forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/hydrology/model/DeterministicSkeleton.java#L284-L326)。
   但`Result.rivers`当前只有构建赋值和诊断读取，生产shape/宽深仍消费traces；这项缺陷**不能解释引入River之前的画面**。

## 6. 旧经验的局限，不做全盘回迁

- 旧普通路线出口方向按尾点所在象限给±1，不是严格真实出边owner；当前typed端口的next/owner校验应保留，不能倒退。
- 旧`HandoffPickupProbe`容差60wu、可找其它区任意附近河；最后只是打印PASS/FAIL，无非零退出。它的历史PASS不是当前严格连续/共同后缀/全周期证书。
  [HandoffPickupProbe.java:51-54](../../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/hydrology/HandoffPickupProbe.java#L51-L54)、
  [HandoffPickupProbe.java:101-168](../../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/hydrology/HandoffPickupProbe.java#L101-L168)。
- 旧湖出口有“正常追踪失败→附近河兜底”，但调用本身不证明兜底必成功，更不证明实际湖面与最终河线贯通；不直接恢复这类豁免。
  [RiverLineNetwork.java:728-753](../../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/hydrology/riverline/RiverLineNetwork.java#L728-L753)。
- 旧版也存在窗口边界依赖、局部汇流修补、不同地形/水位口径和昂贵采样问题。旧设计经验不是“整套旧实现已证明无限世界全周期正确”。

## 7. 建议的后续顺序（待实施授权）

**先不做全局ID、几何去重或HALO发布规则重写。** 延续现有条件D8骨架和已验收路线，按旧版有效经验核查连接契约：

1. 先为当前River输出明确中段JOIN后缀、共享下游、重复/环的表示；测试不能只数trace压缩率，应检查有向格序列无回跳。修与否和生产效果分开验收。
2. 只选一个同区JOIN和一个跨区续流断面，读同一次求解的真实连接点、Q来源、半宽、深度、水面、河头标志。重点查`downSeq[0]`与实际接点不符、位置接力但属性重置，不能用附近max Q掩盖。
3. 再据证据决定需要继承哪些节点属性与边界状态；不将旧`initialAccum+localAccum`直接照搬、不用全局身份代替水量衔接。
4. 路线与属性连接确认后，独立实现河道横断面，保持中心线职责单一；宽度全宽1–12按用户口径，湖水位仍归湖，河适应湖。
5. 验收分层：有向终点/共同后缀、实际连接点属性、生产1像素骨架A/B、完整河道A/B、Forge。无向触水率和着色切换仅辅助定位，不能取代全周期验收。

以上是研究后的候选验证顺序，不是已实施或已修好。当前阶段不加预算、不另建水文、不动南部长直地形路线、不提前改宽深默认值。

## 8. 本轮验证与未实测

已完成：Git移除历史核对；五份诊断源码blob与移除前快照一致；核心调用与默认开关复读；当前River生产消费者搜索；IDW重复候选、旧smin和中段JOIN拼接的低成本内存公式检查；研究前后Git状态检查。

未进行：Java函数级反控、旧链独立新JVM复跑、当前定点生产证据采集、任何新大图、Forge、门禁和性能测量。未改生产代码/配置/既有文档；只新增本研究记录。
