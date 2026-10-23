# 水文连续追踪契约与隔离验证（2026-10-22）

## 交付边界

> 下文第1—4节记录第一阶段（仅契约）的历史状态。用户随后授权继续解决接入条件并指出断流；第二阶段已修改生产，见文末“5. 断流取证与局部修复”。不能再把当前工作树称为“A逐位不变”。

执行 `.openbitfun/plans/hydro-multiphysics-plan-review.plan.md` 的四个前置项：统一高度场、路径契约、共同接收状态与验收修订。**全部实现位于 diagnostics，尚未接入生产，A 不变。** 这不是原始参考计划的完整河网实现，也不是无限世界或游戏实机验收。

没有直接编辑计划文件，没有修改 main、原 run TOML、原 ContPathAuditProbe、宽深、湖泊或缓存版本；未提交/推送。TodoWrite 使用计划既有四个 ID。宿主同步 Todo 元数据可能改变计划 frontmatter 的状态，不能把计划的文件哈希不变作为本轮证据；正文未由实现方编辑。

## 1. 床面与重力：unify-height-field

实现：[TraceBedField.java](../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/hydrology/model/TraceBedField.java)。

- `Nodes.height(ix,iz)` 是世界对齐格点的**床面高程**，不是平衡湖面、fill 或 parent。
- 点坐标与格距用 wu，高程沿生产 block 高程语义；默认 horizontalScale=2 时，生产 STEP=2 block 对应水平格距1 wu。不能把 block 格距直接当 wu。
- 双三次 Hermite 重建同时返回 `height, dx, dz`。同一多项式的解析导数作为重力梯度，不另做一套向量插值。跨格保证 C¹，不宣称 C²。
- 世界格点取 floor，负坐标不截断；求值需要周围4×4节点，未知节点显式拒绝，不判干。
- 生产采样适配只读 `pipeForProbe().bed()` 与 `windowFrameForProbe()`，不从窗口之外猜高度，不改变 VALLEY_MIN 等原策略。

**边界：** 普通 Hermite 可能过冲。两个 seed 的361样本中，超出所在四角高程范围的最大值分别约0.01465、0.04894高程单位。这不是连续真实地形安全证明，不能直接接管盆地身份或溢流判定。局部相同数据可得到同场，不证明不同生产窗口的原始 bed 本身逐位一致。

## 2. 连续路径：define-path-contract

实现：[TracePathContract.java](../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/hydrology/model/TracePathContract.java)、[TraceSpillWitness.java](../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/hydrology/model/TraceSpillWitness.java)。

- 连续折线是权威，控制体以世界格心为中心，区间为 `[center-step/2, center+step/2)`。
- DDA 对**真实线段**计算进入/离开参数；精确穿角时单独输出两侧零长度接触，不冒充穿过格内部。预算计算包含接触事件；负零和零长度段已覆盖。
- 每条经过线段枚举 lake、sea、existing-river、boundary、input-pending 格事件；中途格不可因终点不在该格而漏掉。零长度接触保守暴露给消费者，同时冲突事件拒绝。
- `EXISTING_RIVER` 只是宽阶段候选。必须通过真实线段相交认证；同格/近格不等于JOIN。取最早交点，共用其实际坐标与下游后缀，不修改已冻结下游。
- 最终隔离几何直接使用同一条折线，不再经过生产 shape 的粒子偏移或 Catmull-Rom。**未改生产 shape。**
- 溢流 helper 用有界 minimax 节点搜索，返回每个相邻见证节点而非一个远处目标。预算耗尽/触及输入域边界返回 UNKNOWN，绝不伪造内流湖证书。

**边界：** 相交计算使用 double 的叉积，不是任意精度几何谓词；极近共线和舍入退化尚未认证。minimax 只认证声明的节点图，不认证边内连续 crest，不宣称等价于现有 spillCell，故不能直接替换生产。控制体边界、湖岸及流体实际连通仍待生产接线验证。

## 3. 共同下游与跨区：define-shared-state

实现：[TraceSharedStateContract.java](../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/hydrology/model/TraceSharedStateContract.java)。

- 路线身份是 seed + 明确的策略身份 + 世界源坐标，不能用当前窗口内 trace ordinal 当全世界身份。
- 构造方必须提供**完整声明输入集**，一次性冻结。读取者不写入；同身份不同路径拒绝，不允许“后到覆盖”。输入缺失不是干。
- JOIN 沿最早真实交点接入冻结的后缀。不同接收者在最早交点并列时返回显式歧义，等待共同 junction 权威，而非按坐标挑一条。更晚处的并列不应遮挡更早的唯一交点。
- 本批**不把Q接入动力学**，不叠加旧 parent[] 流量到新几何，不宣称流量守恒或全流域精确Q。
- Port 载荷包含路线身份、原边序号、边界、发送/接收owner以及**同一连续位置和速度状态**。接收方原样复用，不重置为格心或初速。
- 当前候选仅接受明确单边界的边内交点。角点、多边界和恰好以边界为顶点的情况显式拒绝，须先定义事件拆分；不静默猜接收方。
- 缺失输入、冲突、查询取消不发布部分缓存状态。

**关键未解接口：** 谁在无限世界中发现完整声明输入集？如何得到唯一的世界下游权威？本批只把要求落实为局部不可变接口与可执行检查，未解决这两个世界级生产者问题。五个独立正/负边界载荷测试不是一条真实河连续跨五区的证明。三并发检查是不可变对象读取，不是完整provider并发验证。不得因这些测试通过就接入生产。

## 4. 物理与验收：revise-validation

实现：[TracePhysicsContract.java](../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/hydrology/model/TracePhysicsContract.java)。

### 修订后的隔离公式

- 时间步 `dt`（秒）；位置水平wu；速度wu/s；重力尺度 G；二次阻力加速度写作 `-c|v|v`，c量纲1/wu。
- 方向候选来自保留坡度模长的 `v_proposal = v_old - G*grad(H)*dt`，不是单位坡向与单位惯性的常数混合。
- 位移 `ds=min(|v_proposal|*dt,maxDistance)`。
- 初始比能 `E_old=H(old)+|v_old|²/(2G)`。
- 离散摩阻水头损失用旧速度作一阶求积：`loss=c*|v_old|²*ds/G`。
- 只有端点床面不升、`E_old-loss-H(new)>=0` 时才接受；速度模长由余下动能头反解，使离散账本一致。否则缩小时间步，重试耗尽为UNKNOWN，不把拒绝点瞬移回格心。
- 零速且零重力是盆/平台待决，不是合法海湖终点。全程不添加“摩擦保证有限步”结论，不重置速度注入能量。

该算法是**隔离的一阶离散候选**，不是完整浅水方程；目前只检查端点不爬坡，不能保证线段中间没有凸起。没有可越反坡1.5块的许可、没有Q惯性、没有旋度噪声、没有转角硬钳。必须先补连续crest与世界共同接收状态，才可评估迁入生产。

### 已完成验证

主入口：[MultiphysicsContractProbe.java](../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/hydrology/model/MultiphysicsContractProbe.java)。最终日志 `build/multiphysics-contract/final-event-verification.log`：

- main/diagnostics编译、契约任务通过，进程exit0；2815条通用断言 + 18条共享状态检查。
- 斜面/平台/弯谷/鞍部、1/2/4wu格距、负域、有限差分梯度与跨格C¹。
- 1200组随机线段与独立逐矩形slab裁切对照、逆向覆盖；角点、端点、预算负控。
- 真相交/同格不汇流/共线接触、唯一后缀、声明输入逆序、同身份冲突与seed错配、三并发、取消重试。
- 溢流完整节点见证及耗尽/触框UNKNOWN；斜面能量账本、零速/反坡拒绝。

负控入口：[verify-multiphysics-contract.py](../forge-1.20.1-47.4.10-mdk/tools/verify-multiphysics-contract.py)。复制源码到build做5个语义变异，全部先javac成功，再以AssertionError退出1且命中指定断言：漏梯度单位缩放、跳过DDA、错误JOIN、迟到冲突不拒绝、能量注入。原失败runner日志保留；第一次DDA变异实际先命中角点断言，已纠正期待的断言名称，不把编译失败算检出。独立冷JVM契约输出逐字节相同。

真实输入入口：[TraceProductionContractProbe.java](../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/hydrology/model/TraceProductionContractProbe.java)：

- 原seed与12345各361节点/梯度样本通过。当前实际窗口 `[-608,-96,2,353]`，horizontalScale=2.0；不是旧图文案声称的STEP4。
- 原seed两独立JVM的metrics逐字节相同，6400列逐原始double位模式指纹 `c2b7bc5fdaa475d05049bdbc30ae10a79b77f7d0fbcf0b9f6259234a5a4b13c6`；153湿列、0湖列。因此该列样本不覆盖湖面接缝。
- 12345为6400列、111湿列，指纹 `9bef9d93633904d0a531cfb097f5bf2187fb901cff03a48065dc359511956ff4`，不是与原seed相同的世界。
- 原run TOML与全部已跟踪main源文件SHA256未变；输入清单 `build/multiphysics-contract/input-before.json`。新diagnostics不会进入正式main产出。

### A基线不变验证

使用**原ProductionViewProbe**，未修其既有渲染口径或报告文案：

- 前图 `build/hydro-production/prod-1792665445001/production.png`。
- 后图 `build/hydro-production/prod-1792666432318/production.png`。
- 两图1401²、8281chunks、未覆盖0、工具河格31528/湖格110243，PNG SHA256均为 `1A959D5C04E1C19AB6BA134C1B5536A589950C390AD4931BD86FBC58AB36B127`。
- 既有 `verify-readonly-invariance.py` 实测0差异像素。只有不变性结论，不声称形态改善。
- 原出图工具以 `erosion()>0` 标河，报告仍打印STEP4且文案称固定色标，实际用自适应底图；这些既有工具问题本次没有扩大范围修复。上面的河格是**该工具口径**，不是新定义的开放水体数量。新列探针另按fillWater和水面高于床面计湿。
- 原出图日志保留每region ends，但现有输出不包含全部被回滚链的原因计数。因此**没有完整的未过滤生命周期账本**，不能拿保留后的DEAD_END汇总当全世界证明。

## 复跑

MDK目录（仓库根的 `forge-1.20.1-47.4.10-mdk`）：

```powershell
.\gradlew.bat -I tools/multiphysics-contract.gradle runMultiphysicsContractProbe --offline --console=plain
python tools/verify-multiphysics-contract.py
.\gradlew.bat -I tools/multiphysics-contract.gradle runTraceProductionContractProbe --offline --console=plain
# 第二seed
.\gradlew.bat -I tools/multiphysics-contract.gradle runTraceProductionContractProbe -PprobeArgs="12345 build/multiphysics-contract/real-bed-12345" --offline --console=plain
```

真实探针当前使用TerrainParams.defaults，与本次冻结的A默认出图同条件；已记录用户TOML哈希，但未宣称解析了TOML所有调参或等价用户某个现存世界配置。生产图是provider计划，不是运行后Forge方块验证。

## 第一阶段结束时的确认边界

四项前置契约已有实现与隔离证据；**不表示候选具备生产接入条件**。下一阶段仍须决定/验证：无过冲或连续crest约束、完整输入发现与世界下游权威、边界顶点/角点协议、实际跨多区全生命周期、未过滤失败账本、正式provider接线与实机。没有运行完整WorldgenGate，没有宣称既有门禁红已修；没有新物理河网生产图或Forge验收。当时A保持原样，后续用户已授权继续，以下记录第二阶段。

## 5. 断流取证与局部修复（进行中，不能宣称全周期完成）

用户要求继续解决接入条件，并指出图中非湖海终止。未启用新物理积分器、噪声或流量惯性；仅修有反例的生产缺陷。

### 已确认与修改

1. `SegmentIndex.nearest` 已返回“半宽命中或真实穿块”的并集，`columnsFor` 本区与邻区却再次按 `dist<=half` 拒绝穿块独有列。中心80²默认seed20列全部丢失；修两调用点后20列均雕刻且fill=true；seed12345另23列通过。半宽仍0.5，不另加缝合线；角点supercover会增加触碰列，不能称栅格像素宽完全不变。缓存schema101。
2. `continueOutside` 把窗内点与窗外点分组，出窗后再入窗的行进顺序被倒置。独立三点高度10→9→8反例：旧path=[23]、tail=[5,2]，实际应先(5,2)再(4,3)。现cell保留全部窗内拓扑点，新增geometryPrefixNodes限定出窗前缀，tail保存首次出窗后的完整有序世界路径。shape与交叉守卫登记均使用前缀+tail；单点尾段也保留。缓存schema102。`OutsidePathOrderProbe` 检查原终点身份、顺序、最终shape和守卫线段；HEAD独立编译叠层真实AssertionError，当前通过，不把NoSuchMethodException当检出证据。
3. 只读取证：`geogenesis.lifecycleDiag=true` 向根debug-agent.log追加每次trace的终止原因、回滚与frame；原日志先复制到 `build/lifecycle-diagnosis/debug-agent-before.log`，SHA256相同。保留临时插桩，不更改路由策略。生产drainageAudit不再被HIER限制，默认仍关闭。

### 全周期仍然真实失败

- owner(-1,0)保留55链，3个DEAD_END：(-608,606) SELF_CROSS；(-290,-96)、(-374,-96) NO_RECEIVER。5条源后缀最终为EXTERNAL，不是海湖认证。真实incoming非零、exitSeeds非零，撤回“跨区接力恒死”的静态推论。
- 多个溢口DEAD_END因fromOutlet豁免而保留，日志已捕获SELF_CROSS及SELF_CROSS_BEFORE_WATER_OR_JOIN；不能只统计最终保留链宣称健康。日志包含pass1重复构建，按ox/oz直接求和会重复计数。
- ~~南部z=608存在硬裁切~~ → **已修，见 §6**。原证据保留：z604..607可落块，z608起北区几何命中却不雕刻/不fill，窄样本296列。
- `ProductionLifecycleProbe` 默认样本exit1保留真红；南owner(-1,1)9链到海仅是本区保留集合，不能覆盖北区被裁掉的河。
- `ProductionViewProbe` 仍是既有erosion图口径；schema101图河36913/湖110182、8281chunks/0未覆盖、匹配河列fill全部真，但湖比A少61列（河命中后跳过邻湖搜索），不能说只增加河且湖逐位不变。图仍有南部齐断、不是验收绿。

### 回归与未实测

main/diagnostics编译成功；新顺序探针与既有2815+18契约通过。SkeletonProbe两处反射都漏OutsideContinuation参数，已修两处测试调用。第一次只走到六个平台红后又抛反射异常，不能称完整跑完；修第二调用后合成整套HEAD与当前均22红、失败集合完全一致（两侧非空），包含旧平台/阻断候选口径，不修无关算法或放宽断言。HydrologyDeterminismProbe ALL PASS；完整WorldgenGate、Forge新块、最终跨区/湖面确定性未验收。独立CodeReview指出尾段幽灵弦登记，已修registerSegments并补断言；尾段与既有共享锚点可能错位仍是未解接口，不据审查通过宣称整链完成。

最终生产图：`build/hydro-production/prod-1792672633686/production.png`，1401²、8281chunks/0未覆盖、河36913/湖110182、4连通204。PNG SHA256 `3BC52030DDCFCE4D3EA8C1D0BB1844A6676E39BC66376075B74B9948961AF1CC`，与仅覆盖修复的schema101图逐字节相同，说明本默认样本未触发已修的出窗再入窗重排，不能宣传第二项有全图形态收益。最终中心覆盖探针20列/丢失0、exit0；Gradle顺序+2815/18契约BUILD SUCCESSFUL。图亲验仍南部齐断。

新增诊断任务通过 `-I tools/multiphysics-contract.gradle` 注册：runOutsidePathOrderProbe、runProductionCoverageProbe、runProductionLifecycleProbe、runProductionOwnershipProbe、runSeamBandBoundaryProbe、runJoinOwnershipProbe、runJoinCellContainmentProbe。后两项与 lifecycle 按当前真实缺陷可能 exit1，必须保留红灯。全部新证据在 `build/lifecycle-diagnosis/`；本轮未提交/推送。原run TOML SHA256仍为 `8DC968236445A74A75357D84122B61E1F1A54C07500B829276F9B48F42C223E3`。

## 6. 跨区接缝根因：边界带四侧不对称（已修，2026-10-22）

### 根因（不是架构问题，是一行判据）

`columnsFor` 的邻区惰性查询由 `nearBorder` 门控，低侧原写 `<`、高侧写 `>=`：

```java
// 旧：低侧不含端点
boolean nearBorder = (blockX < regLoX + HALO) || (blockX >= regHiX - HALO)
        || (blockZ < regLoZ + HALO) || (blockZ >= regHiZ - HALO);
```

窗口可画到 `origin + (n-1)*STEP`；对 owner(-1,0)、`originZ=-96`、`n=353`、`STEP=2`
即 **z=608 = regLoZ+HALO**。该行恰好落在旧低侧带外，而北邻能画到的最后一行也正好是 608
⇒ **两个 region 都不声明这一行**，形成一个"无人负责"的空洞。

同源第二层：邻区循环的侧向过滤也用 `>=`
（`neighDz[t] < 0 && blockZ >= regLoZ + HALO` ⇒ continue），
即使带判据放行，端点仍会被这层再剔除一次。

### 修改

- `nearBorder` 四侧统一为闭区间（`<=` / `>=`）。
- 邻区侧向过滤低侧改为 `>`，与带判据同一口径。
- `PreviewDisplay.CACHE_SCHEMA_VERSION` 102→103。

### 验证（逐像素，不是"看着更像"）

`ProductionOwnershipProbe`（真实 provider）：该接缝窄样本 **296 丢列 → 2 丢列**。
剩余 2 列（-457/-456, z=609）是 shape 的几何过冲（北区折线到 z≈608.6），
不属策略错误，未强行拉伸。

1401² 全图前后逐像素：

| 项 | 值 |
|---|---|
| 变化像素 | **1409**（全部新增河色，删除 0） |
| 变化像素落在接缝行/列 | **1409 / 1409**（z=-416/96/608，x=-416/96） |
| 湖格 | 110182 → 110182（未变） |
| 河道格 | 36913 → **38322**（+1409，与像素数一致） |
| 4 连通分量 | 204 → 191 |

⇒ 改动严格局限在接缝，且只补不删；湖零影响。

`SeamBandBoundaryProbe` 把上述算成可执行判据（含"旧 `<` 排除端点"的负控），
`-I tools/multiphysics-contract.gradle runSeamBandBoundaryProbe` **ALL PASS**，
与 `OutsidePathOrderProbe`(PASS)、`MultiphysicsContractProbe`(2815+18) 同批 BUILD SUCCESSFUL。

**边界：** 修复的是"图上有几何、落块不查"，不等于河流生命周期已闭合；
南部仍有非湖海终点（见 §7）。未跑 Forge 实机、未跑完整 WorldgenGate、未提交。

## 7. 新发现：JOIN 自汇与审计 BROKEN_JOIN（未修，只加诊断）

`ProductionLifecycleProbe` 扩到相邻 owner 后出现大量 `BROKEN_JOIN`：

| owner | retained | 主要 chain 候选 |
|---|---|---|
| (0,-1) | 114 | BROKEN_JOIN 46、UNRESOLVED 37、CYCLE 25、EXTERNAL 4 |
| (-1,-1) | 108 | BROKEN_JOIN 48、UNRESOLVED 30、CYCLE 18 |

排查确认这**不是**审计 first-wins 的假象：`JoinCellContainmentProbe` 用完整 reach 格路径
判归属，8 条"无下游"的 JOIN 终点全部 `LATER_ONLY` 或只被**自己**包含（`holders=[20,20]`）。

根因找到在 `traceOne`：

```java
if (covered[p]) { path.add(p); end = End.JOIN; break; }   // 未排除本链自己的格
```

河流绕回自身已走过的格时也被记为 `JOIN`（合法汇流）。只读插桩
（`-Dgeogenesis.lifecycleDiag=true`，日志 reason=`SELF_JOIN`）实测 **108 次**，
其中 96 次未被回滚。它同时解释了审计里的 `BROKEN_JOIN` 与图中悬空短棒。

**未修**，原因：把自汇改成 `DEAD_END` 会连带改变回滚/网络过滤结果（可能删河），
属于路由语义变更，需要单变量对照与出图后再定。当前只保留只读日志与两个审计探针。

其它已量化但未处理的真实红灯：

- 溢口段 `DEAD_END` 因 `fromOutlet` 豁免而发布（局部 SELF_CROSS 亦然）。
- owner(-1,0) 仍有 3 个保留 DEAD_END、5 条后缀 EXTERNAL（非海湖认证）。
- 1401² 图有 3 段"不接水、不接边框"的孤立河（610 像素）。
