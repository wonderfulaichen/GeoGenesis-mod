# R0 核心参数边界与架构方案复核（2026-11-05）

> 承接 2026-11-04 的讨论裁定；本轮仅归档、核实与修订方案，**没有源码实施、删除、提交或推送授权**。
> 本文是 R0 的版本化入口；本地总方案见 [streaming-world-core-alignment.plan.md](../.openbitfun/plans/streaming-world-core-alignment.plan.md)。后者被 Git 忽略，新克隆不保证存在；R0 界定冲突时以本文为准。
> 本文核实的是当前工作区源码，不等于已运行构建、探针或 Minecraft 验收。
> **后续状态更新**：用户仅授权的 R0-0 基线与影响面确认已完成，构建/采样结果见 [R0-0基线记录](GeoGenesis-R0-0-baseline-20261105.md)；R0a 随后获明确授权并完成，见 [R0a 迁移与验证](GeoGenesis-R0a-ridge-boundary-20261105.md)；R0b 后续获收窄授权并完成，见 [R0b 粒子边界](GeoGenesis-R0b-particle-boundary-20261105.md)：只迁六值/必要门面，缓存失效修复、预览/static 缺口均保留。本文下方原缓存身份/static隔离等建议不属于该次实施范围。本文原“本轮仅文档”描述保留为方案制定时的授权边界，不覆盖后续明确授权。此次授权收窄为配置工厂迁移，保留 RidgeConfig 公开可变面，不实施深不可变快照/static 隔离。

## 后续骨架候选实验引用（2026-11-05）

R0a/R0b 之后用户另行授权隔离 A/B 小样本，已完成真 raw 节点 A；用户纠偏明确“阶地移动、重塑、局部抬升平台不算代价，这正是侵蚀带来的效果”。继续保留 A 候选，补纯山地、劣地、覆盖层及海岸，不接生产、不提交推送。新证据与覆盖层漏接负控见 [A 跨地貌覆盖验证](GeoGenesis-ridge-a-coverage-20261105.md)，前序见 [真A小样本](GeoGenesis-ridge-true-ab-20261105.md)。该补充不扩大 R0 重构范围，也不代表生产或用户验收。

## 1. 用户已确认的要求

- **先做 R0 行为保持重构，再增加地貌/侵蚀功能**；不是等整个世界引擎重构完才允许加功能。
- **骨架侵蚀就是粒子侵蚀的独立可选低配替代品**，目的是降低计算消耗。不能改成“减少粒子液滴数”，也不能把这一用途重新列为待定。
- 骨架算子属于地貌塑形职责；这与其低配用途不矛盾。仅“配方内塑形 A / 形态过渡后塑形 B”的接点待少量代表性图检决定。
- P1 已认可的基础效果保留；结构整理和改变地貌分开验收。不启用原来未启用的外部过程、不改默认参数、不顺手删除旧基础分支。
- 本轮只讨论与文档更新。R0 实施、骨架接点、配置屏、真实存档操作与 Git 提交推送均不因此获得授权。

## 2. 对总体设计的判断

**保留“纯 Java 世界核心 → 流式运行层 → Minecraft / 预览 / 导出”三层方向。应补齐契约，不推倒重写。**

- 核心定义地形、气候、材料与区域过程的数学；运行层负责世界对齐区域、依赖、缓存、调度与完成发布；应用端负责 MC 坐标/方块/群系/存档及原版委托。
- 当前已存在 [BaseWorldSampler.java:8-39](../forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/core/BaseWorldSampler.java#L8-L39)、[BaseWorldSettings.java](../forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/core/BaseWorldSettings.java) 和 [BaseWorldConfigAdapter.java](../forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/config/BaseWorldConfigAdapter.java)，不是从零分离核心。
- [verify-base-core.gradle:5-51](../forge-1.20.1-47.4.10-mdk/tools/verify-base-core.gradle#L5-L51) 已定义独立源码闭包与无 MC/Forge 的编译检查，允许 DFU、fastutil 这类普通 Java 库。后续复用并按变更扩展，不能仅因与 MC 类同包就断言核心无法独立编译，也不必立即拆 Gradle 多模块。
- 核心可以保留不影响结果的局部记忆缓存；跨阶段任务、等待、背压与发布属于运行层。缓存命中不得决定世界内容，不能为了形式纯洁在 R0 搬走全部缓存。

## 3. R0 的做与不做

**目标：把本轮地形/侵蚀计算实际读取的 Forge 参数变成显式、不可变的输入，保持固定上下文的产出逐位不变。**

| 做 | 不做 |
|---|---|
| 盘点有效配置读取、调用方、默认/未加载回退与缓存失效 | 不全仓重写，不借机清理历史实验 |
| 配置适配端创建参数快照，核心计算只消费快照 | 不新增配置屏，不改玩家默认档位 |
| 分离骨架配置解析、粒子内核参数读取及必要门面接线 | 不替换骨架算法，不把 Rune 新候选接到默认生成器 |
| 明确现有单位来源并保持原换算顺序 | 不在 R0 改 HS/VS、e↔Y 公式、float/double 或采样密度 |
| 保持现有请求路径，增加针对性独立编译与等价检查 | 不迁移线程池/区域调度，不恢复 P1 外部侵蚀/水文 |

**R0a/R0b 完成不等于 CellGenerator 或整个世界引擎已经完全纯 Java。** 气候、分类、诊断和其他尚留在迁移门面的依赖必须列明去向；不把只搬两处开关称为“全部配置出核心”。

### 3.1 已核实的候选影响面

| 候选位置 | 当前事实 | R0 应处理的边界 |
|---|---|---|
| [RidgeValleyErosion.java:37-81](../forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/erosion/RidgeValleyErosion.java#L37-L81) | `RidgeConfig.fromConfig` 与底部辅助函数依赖 Forge；配置含公开可变数组 | 解析留适配端，核心只收参数；快照必须复制数组或以值字段表达，不能只把引用标 final |
| [CellGenerator.java:202-207](../forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/terrain/CellGenerator.java#L202-L207) | 构造时读取侵蚀配置与 XS 开关 | 工厂/适配端接线，不让构造阶段继续隐读全局配置 |
| [CellGenerator.java:1045-1058](../forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/terrain/CellGenerator.java#L1045-L1058) | 查询入口读取开关与 `configFingerprint` 并清缓存 | 与新快照及缓存身份一致，不能只改生成入口而留下旧门控 |
| [CellGenerator.java:1169-1181](../forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/terrain/CellGenerator.java#L1169-L1181) | tile 生成读取开关、强度、骨架配置；再用 params 的 HS 覆盖 | 与查询入口同源；保持各路径的有效参数和数值换算 |
| [ErosionEngine.java:121-124](../forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/erosion/ErosionEngine.java#L121-L124)、[304-323](../forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/erosion/ErosionEngine.java#L304-L323) | 构造参数 `dropsMul/seed` 不保存；运行时仍读 6 项 Forge 参数 | 迁出液滴倍率、侵蚀倍率、塌落、动量、迭代数、lrate 的真实读取；传构造参数不代表完成注入 |
| [CellGenerator.java:1448-1451](../forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/terrain/CellGenerator.java#L1448-L1451)、[1497-1499](../forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/terrain/CellGenerator.java#L1497-L1499) | 探针开关和骨架覆盖为 static，实例 setter 也写 static | 迁移相关调用方到实例输入，验双实例不串参数；不能未经盘点直接删公开入口 |
| [CellGenerator.java:534-549](../forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/terrain/CellGenerator.java#L534-L549)、[2319-2332](../forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/terrain/CellGenerator.java#L2319-L2332) | 旧基础路径仍有气候配置读取；后一方法检索未见调用方 | 盘点并标明暂留迁移门面；不因含配置读取就认定都在生产执行或扩大 R0 成气候重写 |

以上是候选清单，不是已实施清单。当前 `RidgeConfig.fromConfig` 的源码调用方可见于 CellGenerator 和诊断 RidgePlacementProbe；实施前仍须重新全仓检索（含完全限定名和反射），不能把一次搜索数量当长期契约。

### 3.2 参数与单位的建议

- **优先用小型、按职责分组的独立不可变参数载体**，复用已有 `BaseWorldSettings` 模式；不默认把所有侵蚀开关塞进巨大 `TerrainParams`，也不建通用配置框架。具体命名和是否复用现有载体由实施前盘点决定。
- 保留值、默认/未加载回退、float 转换和运算顺序。骨架主路径的 HS 现由 `params.horizontalScale()` 覆盖配置值；不能据此删掉其他直接调用方所需的显式尺度输入。
- 一个生成任务只能看见一份参数快照。预览编辑由适配端生成新快照并隔离旧任务/缓存；核心不逐点读取可变 Forge 全局状态。该切换协议必须验收，不能只验证固定参数的高度。
- R0 只明确单位所有权，不重新标定物理尺度。单位规范化另开行为变化步骤，避免用“搬换算位置”掩盖浮点顺序变化。
- 不在 R0 更改存档格式或新建旧存档兼容产品线。未来低配档会改变高度乃至河网，是否按世界固定/持久化需在功能落地前明确；不要承诺已生成世界任意切档仍无缝。

### 3.3 停点

1. **R0-0：事实与基线**。冻结认可的 P1 输入资产；另记录本轮触达的骨架/粒子现状样本、源码与配置清单、全量 JVM 属性及 classpath 来源。未通过编译的探针不能用于基线。
2. **R0a：骨架参数边界**。适配端解析，核心只接显式值；保留算法/接点/默认值，完成相关调用方接线、独立编译和骨架 delta 对拍后停。
3. **R0b：粒子参数与门面接线**。迁出内核真实配置读取，统一查询/生成门控、构造参数和缓存身份；检查固定参数输出与显式快照切换后停。

每个停点提供独立证据，不自动提交。R1 的调度迁移、骨架 A/B 形态试验与新增功能仍需后续授权。

## 4. R0 的验收，不能只看一张高度图

**已核实的盲区**：P1 直接走基础世界，`erosionDeltaE` 在 morph 分支恒 0；[P1CellRenderProbe.java:32-46](../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/terrain/P1CellRenderProbe.java#L32-L46)、[78-106](../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/terrain/P1CellRenderProbe.java#L78-L106) 也明确断言没有外部侵蚀/水文。因此 P1 SHA 相同只能证明 P1 没坏，不能证明侵蚀参数迁移正确。

| 检查 | 要回答的问题 |
|---|---|
| P1 同输入高度与必要 Cell 元数据 | 已认可地形、高度、气候、类型、材料没有漂移；不是只验 height |
| 固定真实地形样本的骨架 delta | R0a 实际执行到变更路径；不借 P1 旁路获得假绿 |
| 粒子内核 flat/discharge 与相关 tile delta | R0b 实际参数相同；岩性开启/关闭、HS=1/2、正负区域各覆盖必要小样本 |
| 双实例不同参数、顺序/并发与重建 | 不受 static 覆盖、缓存淘汰与任务顺序串扰；确定性仅针对相同阶段和版本 |
| 配置适配与显式新快照 | 默认、已加载配置、未加载回退和预览编辑有一致来源，不出现旧缓存/新参数混用 |
| 独立核心编译 + 对应适配层编译 | 测过的核心源码闭包不含 Forge/MC；没有靠旧 classpath 中的预编译类假通过 |

- 同一 Java 工具链、输入、阶段和导出协议下比较**原始位模式/字节**，差异为零；SHA-256 用于已固定协议的数组，不比较改名/重编后的 class 文件哈希来推断行为。
- `.f64be` 必须真的以大端 float64、逐行 z/逐列 x 导出；记录维度、步长、原点、单位、seed、有效参数、版本和 JVM 属性，不能先压成 float 再声称保留了 double 逐位性。
- 保留用户认可基准，不拿候选自产新图替代基准。R0 前现状样本只承担“本次结构移动前后等价”的补充证明。
- 至少一个低成本负控：改变一项确实生效的输入，相关输出/快照身份应变化，证明对拍不是两边都走错同一条未执行路径。
- 现有独立入口可参考 `gradlew.bat -I tools/verify-base-core.gradle verifyBaseCore`；这是已存在任务，不是本轮已运行的 PASS。扩展闭包后再验迁出的算子；不重复跑无关历史大实验。

## 5. 总方案需要的调整（设计建议，不是实现授权）

### 5.1 显式写出低配路径

```text
地貌生成（配方/形态过渡 + 可选骨架塑形，A/B接点未定）→ 基础地形B
C = 气候(C0, B)
粒子开启：P = Particle(B, C, G)；粒子关闭：目标契约P = B
H = Model(B的必要快照, P, C, G, 跨区边界)
F = 最终地表、水体、材料与实际坡度
```

骨架单开必须不执行粒子，model 也只能向当前生成上下文请求选定的 P/B，不能通过别的入口再启动粒子。不能从“低配替代品”推导必须把两算子互斥，也不能默认高配永远叠加；高配组合后续单独图检，R0 不改现状。

### 5.2 model 不是只有 P 一个输入

[GeoGenesisTerrain.java:473-493](../forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/terrain/GeoGenesisTerrain.java#L473-L493) 先保存侵蚀前高度，再施加侵蚀；[ModelHydrologyProvider.java:2250-2260](../forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/hydrology/model/ModelHydrologyProvider.java#L2250-L2260) 同时使用原始高度和侵蚀后高度；[HydrologyPipeline.java:437-443](../forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/hydrology/model/HydrologyPipeline.java#L437-L443) 默认按侵蚀后地形求解。最终回写还依赖前后差值（[GeoGenesisTerrain.java:624-657](../forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/terrain/GeoGenesisTerrain.java#L624-L657)）。

**建议**：保留 B/P 的明确阶段语义及既有补偿逻辑，不用一个不断变义的 height 覆盖全部过程。是否以后简化算式另开等价证明；不能在结构迁移时丢弃原始基准或重复叠加侵蚀。

### 5.3 区分精确阶段查询与非阻塞近似

当前 [GeoGenesisTerrain.java:205-216](../forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/terrain/GeoGenesisTerrain.java#L205-L216)、[240-252](../forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/terrain/GeoGenesisTerrain.java#L240-L252) 会因缓存就绪程度返回不同阶段高度。

**建议**：契约标明请求阶段、是否允许计算、完成/未就绪/取消及近似身份。精确阶段结果不能随缓存改变；近似结果不能冒充 F，也不能用于权威水文或最终验收。R0 不重命名所有公共 API；R1 再迁移入口和调度。

### 5.4 流式边界须在新增区域过程之前写实

每阶段只约定必要字段：输入版本、区域归属、halo/依赖足迹、输出所有权、失败/取消、缓存键与发布条件。水文可向运行层请求上游过程，这是正常依赖，不是禁止一切按需计算；禁止的是下游反调自身、基础查询暗启全链及无界邻区扩散。

[HYDROLOGY.md:28-47](../HYDROLOGY.md#L28-L47) 的无限世界与跨区口径不能被局部 padding 取代。局部有限影响算子与跨区汇流分别给证据；并行和缓存重建测试不能证明反 warp 全世界单射。P1 当前互斥缓存与取消限制见 [MorphTerrainSampler.java:55-66](../forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/terrain/morph/MorphTerrainSampler.java#L55-L66)，不在 R0 顺手优化。

## 6. 对前版记录的纠正与已知阻塞

1. **“骨架是地貌层塑形还是低配档尚未定”不成立**：职责与用途可以同时成立，用户只保留 A/B 接点未定。
2. **“TerrainParams 扩字段必然使反射少一个参数并崩溃”不成立**：[ErosionPeriodProbe.java:95-103](../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/terrain/ErosionPeriodProbe.java#L95-L103) 等会按当前 `comps.length` 逐项复制；真正需要复核的是新增字段默认、构造器选择、直接构造点与 [RecordSerializer.java:35-48](../forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/terrain/RecordSerializer.java#L35-L48) 的序列化类型。无需为了不存在的必崩问题设计兼容层。
3. **“SkeletonOnlyTierProbe 已编译通过”不成立**：本会话前面两次编译均失败，当前文本仍使用未定义 `TILE_CENTER` 并直接访问 private `heightCurve`。它是先前新增的未完成探针，不是可用验证入口；本轮不修复、不删除、不运行构建掩盖此事，需在后续获源码授权时先处理。
4. “双关侵蚀必然回到输入”目前只是目标：`getErosionTile` 有零增量早退，但 `erosionDeltaE → getOrGenTile → generateErosionTile` 的双关路径仍可能留下低通/插值残差（[CellGenerator.java:1234-1253](../forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/terrain/CellGenerator.java#L1234-L1253)、[1365-1404](../forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/terrain/CellGenerator.java#L1365-L1404)）。这是源码推导，**本轮未实测**；R0 先记录当前行为，不顺手修正。
5. 当前还有未跟踪的新骨架实现 [RuneErosionFilter.java](../forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/erosion/RuneErosionFilter.java) 与隔离候选 [MorphSingleShaper.java](../forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/terrain/morph/MorphSingleShaper.java)。存在于主源码目录不等于默认接线，不能混同旧 `RidgeValleyErosion`、`MorphNoise.Erosion` 或水文骨架，更不能因快照载体相似就在 R0 替换算法。

## 7. 下一轮冷启动清单

1. 重新核对工作区、本文候选影响面及 P1 当前入口；不 stash、回退或顺带纳入其他会话的改动。
2. 获得源码实施授权后，先处理确认阻塞对应验证的未完成探针，再完成 R0-0 基线与完整有效参数表。
3. 先 R0a 后 R0b，分别给实际触达路径的编译/逐位证据与阶段不变检查；不自动启用骨架、粒子或 model。
4. R0 通过后才讨论骨架 A/B 小样本及低配功能；区域粒子、model、最终查询与 MC/预览适配按停点继续，不等待全系统“大重构”。
5. 本文和审计索引已落到可跟踪目录；**没有暂存/提交前仍不是已提交的 Git 历史**。提交范围与推送另行授权。
