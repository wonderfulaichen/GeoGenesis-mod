---
name: 边框接力与有序溢流一体修复
overview: 先在隔离副本验证显式出窗、稳定端口和有序溢流见证组成的完整链路；端口及到水体后继全部通过后才迁入生产。不得提高溢流上限、隐藏边框几何或把搜索耗尽当封闭盆地。
todos:
  - id: freeze-contract-fixtures
    content: 固定真实TOML、失败端口与目标洼地，建立合成及真实反例
    status: completed
  - id: implement-spill-witness
    content: 实现有界有序溢流结果并验证路径、耗尽和地形屏障
    status: pending
  - id: stabilize-ports
    content: 定义显式出窗端口并保证pass1端口在最终发布几何中有对应
    status: pending
  - id: validate-receivers
    content: 验证接收方连接、共同锚点及到湖海的后继，不把端口当完成
    status: pending
  - id: migrate-and-regress
    content: 隔离关卡通过后迁移生产并升级缓存，跑反控与多区域回归
    status: pending
  - id: render-and-record
    content: 重出完整同参图并记录形态、生命周期和未实机验收项
    status: completed
---

# 边框接力与有序溢流一体修复

## 1. 范围与证据

用户已选择“一体修复”：边框接力、发送/接收端口一致、有界有序溢流见证；本方案不再另起水文路线。

- [DeterministicSkeleton.java](../../forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/hydrology/model/DeterministicSkeleton.java) 的严格D8追踪不再依赖parent，但出口仍主要从边框DEAD_END提取。普通下行被窗口裁剪后可能沿边框横走。
- [ModelHydrologyProvider.java](../../forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/hydrology/model/ModelHydrologyProvider.java) 的pass1只保存pipe/种子；pass2先消费邻区种子、再重建本区骨架。因此pass1发出的端口不保证出现在发送方最终几何。
- 已拒绝的隔离候选：真实TOML三owner共66端口，发送方最终路径缺6、接收方无路径/水体9；图上横线虽减少但不合入。
- 六条入流在接收区(-1,1)到(-362,652)后NO_RECEIVER回滚。现行spill搜索4096展开耗尽；独立见证149节点、148条step2邻边，crest64.47363773783941，抬升0.6398657374935368，到更低点(-138,654)，展开5565。此结果不证明提升预算就能通用，也不认证湖水位。
- 完整原始证据：[summary.txt](../../forge-1.20.1-47.4.10-mdk/build/boundary-handoff-20261023/candidate-ports/summary.txt)、[incoming.csv](../../forge-1.20.1-47.4.10-mdk/build/boundary-handoff-20261023/receiver-detail/incoming.csv)、[summary.txt](../../forge-1.20.1-47.4.10-mdk/build/boundary-handoff-20261023/pit-escape/summary.txt)。

## 2. 不变条件

- 保持model唯一生产链、seed/世界坐标确定性、负坐标正确、查询顺序无关。
- pass1不得读取邻区最终结果；pass2不得递归消费邻区pass2，也不发布供其它区域递归消费的新种子。
- 不改变真实地形、原TOML、源准入、河宽、惯性默认值、湖水量规则或MAX_SPILL_RISE=12。
- 不回退nearBorder、不裁掉横线冒充修复、不放宽生命周期判据、不将BOUNDARY_EXIT直接视为入湖/入海。
- 先隔离实现；现有dirty源码、图片、日志不删除。只在完整关卡通过后迁生产。

## 3. 接口与实现顺序

### A. 先修溢流查询的结果契约

引入内部只读结果：`ESCAPE(path, crest, downstream)` / `EXHAUSTED` / `DOMAIN_PENDING` / `NO_ESCAPE_WITHIN_ALLOWED_RISE`。

- 成本用沿路径累计最高床面，不是只按当前格床面排队；保存父边，返回完整有序相邻路径，不直接跳到远处格。
- ESCAPE须到达严格低于起点的接收床，完整路径满足原抬升上限；相同最高点时使用稳定世界坐标排序，结果不依赖查询顺序。
- 路径每一步经过现有湖/海/JOIN/交叉守卫；首次到水或合法接收节点就停止，不能越过目标后再返回。
- EXHAUSTED与DOMAIN_PENDING必须传播为未决，不可当干地、无出口或封闭盆地证书。
- 默认不提高现有4096单次展开量。隔离阶段评估保存frontier/父边的有界分批接续：每批、总工作集、取消/缓存身份都有限；如果目标在既有硬限制内无法得到证书，明确停止并报告，不暗增总预算。
- 可参考诊断[TraceSpillWitness.java](../../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/hydrology/model/TraceSpillWitness.java)的累计crest和完整父链，但不得原样搬入其“触域即退出”规则；需证明域边缘不会漏掉更低未探索路径。
- 先验证149节点真实见证与独立合成oracle，再接入河流追踪；见证不自动变成铺满盆地的湖面。

### B. 显式出窗与稳定端口

- 在边缘使用完整真实候选及同一向量规则；只有获选下一步确实出窗时才建立显式端口。边缘不等于出口；自然沿边走也不能一律禁止。
- 端口至少记录发送身份、共享世界锚点、真实下一步/接收区域、来向；不可仅凭落在哪条框推对角接收方。
- 接收方从同一锚点和约定来向开始；原有整数incoming API保留兼容包装，生产内部使用带身份端口，避免无关调用破坏。
- pass1输出必须同时保存端口及其最终几何对应证据。pass2允许与其它链合并，但不能使已发送端口从发送方最终图消失。
- 首选在既有两趟结构内保留pass1承诺的端口/几何，再追加接收链；不直接把两套独立几何简单取并集。共享格后继冲突、交叉、端口被合并时要能定位到同一有效下游。
- 如果“保留pass1承诺”与现有incoming优先/共享覆盖无法兼容，则本阶段失败，不擅自改为全局递归图或多趟固定点。

### C. 发布与审计闭环

- 发送末锚点与接收锚点逐位相同；shape的粒子偏移、共享锚点、CR收尾不得移动端口或造额外长弦。
- 中间相邻边和实际整块水列都须连续；不同owner不得出现相同端口却无后继的假接力。
- 扩展[ProductionDrainageAudit.java](../../forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/hydrology/model/ProductionDrainageAudit.java)：BOUNDARY_EXIT只表示待接收；跨区跟随后继须有步数/工作集限制及显式未决，不可出现未处理enum导致循环。
- sender缺口、receiver缺口、错误湖/海标签、BROKEN_JOIN、环、耗尽逐项保留。不能用过滤后没有坏链作为通过依据。
- 单级pass2继续出窗而没有对应pass1承诺时，判契约未覆盖；不能将它算成无限世界已解决。

## 4. 关卡与反向验证

1. **纯合成**：南北东西/角点/负平移；真横坡不强迫出窗；窗外更高不出窗；来向继承；两级以上连续接力；接收湖/海/已存在河；回水/平台/细小屏障；相邻路径完整。
2. **耗尽语义**：4096内成功正例、超单批需接续正例、超过总工作集负例、取消重试；负例必须非零或显式未决，绝不产出假闭盆。
3. **目标真实区**：原66端口逐一闭环（失效端口可由经验证的同身份合并替代，不能直接删掉记录）；新增端口也全量审计；六条入流须有已发布接收路径并继续到湖海，不能只走出旧边框。
4. **顺序/跨域**：独立冷JVM正逆序、上下游先查、负域、三实例并发、取消重试；多seed含12345。相同边界带比较身份、几何和逐列湿掩码。
5. **反控**：恢复窗口裁剪、漏导出端口、丢发送端几何、清接收来向、只返远处spill格、耗尽当无出口等变异：必须先编译成功，再由指定断言失败。
6. **生产迁移**：只有上述关卡通过才改main、更新所有End消费者/缓存身份并升PreviewDisplay缓存版本；运行受影响探针和WorldgenGate，已有红灯需独立基线归因，不放宽断言。
7. **完整图**：固定真实TOML/seed/中心/r700/step1，用新ProductionViewProbe出1401²图。对比基准prod-1792685034524；湖/河数量、长横段、断头和实际水列同时检查，必须人眼目检。不以横线减少或EXIT计数上升代替闭环。

## 5. 停止与交付

任一契约关卡失败：保留候选及日志，不合入生产、不把失败改成PASS。新增全局盆身份/水量或递归跨区方案属于扩大范围，另行确认。

交付包括：实际生产合成大图、端口全量表与最终后继、完整有序溢流证书、负控结果、冷逆序证据、配置/代码指纹及未实测清单。Forge实际放块/装饰/流体tick需独立验收；未运行不得称实机通过。