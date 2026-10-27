# 水文系统 —— 单一事实来源（2026-10-09 重建）

> **本文件替代根目录原有 12 份水文文档**（已归档至 `_archive/hydrology/`）。
> 归档原因：那些文档彼此矛盾、多数已过时，继续放在根目录会误导接手者 —— 本项目已因此连续出错多轮。
>
> **怎么读**：§〇 用户口径（最高优先级）→ §二 生产唯一链 → §三 铁律 → §四 正典规格 → §〇b* 最近三轮实施记录 → §七 接手须知。
> **逐日实验记录**（2026-10-17 ~ 10-25 的候选链、被取代方案、失败关卡）已移出正文，只在本文件保留「编号 + 一句话结论」，全文见 [`_archive/hydrology/HYDROLOGY-history-20261028.md`](_archive/hydrology/HYDROLOGY-history-20261028.md)。
> 那些段落大多以「未迁生产 / 未验收 / 失败 / 已撤回」收尾，**不要把过程当现状**。

## 〇、⛔ 用户口径（最高优先级，2026-10-09 用户第三次重申）

> **这三条推翻了我此前多次的错误表述。任何人（含 AI）动手前必须先读本节，
> 不得再用旧说法。**

### ① 参照图是 **model 链**跑的，不是 legacy

`canonical-1791351869529`（用户多次引用的那张"目标图"）的 `metrics.txt` 实测：

```
river model = D8 (streamSamples=600000)      ← model 链（HydrologyPipeline, RiverModel.D8）
radius 700 => 1401x1401
sea=452778 lake=71340 (3.63%) channel=25503
```

**⚠ 我此前反复说"参照图是 legacy 出的"、"legacy 的湖是对的" —— 都是错的。**
用户的判定是：**legacy 的湖泊也不完全对**。

### ② 河流必须做成 **确定性骨架**，宽度等细节**后续解决**

- **目标形态 = 骨架**（确定性、与窗口无关）
- **宽度/深度等细节是后续议题**，不要因为"宽度不对"就否定骨架方向
- **理由（用户的硬需求）**：只有确定性骨架才能**无限世界生成**

⇒ 判据首选「骨架是否正确/连续/位置对」，**不要**先纠结宽度。

### ③ **model 链是 legacy 链的升级版**

- 不是"两条平行的可选方案"，而是 **model 取代 legacy** 的关系
- 因此：**legacy 的既有优点（如跨区机制）必须被 model 继承**，而不是丢掉
- 我此前"先回退到 legacy"的做法方向错了

### ⇒ 由此推出的正确工作方向

1. **让 model 链进游戏并能跑**（`hydrologyModelPipelineEnabled=true`），
   而不是停在"legacy 是默认"的现状
2. **把 model 链的骨架改成与窗口无关的确定性生成**
   （参考 DW 的**共享锚点**：本区汇点 = 邻区源点，同公式 ⇒ 逐位相同）
3. 宽度/深度在骨架正确之后再处理

---

## 〇b、★ 系统总账：模组里到底有几套河流/湖泊（2026-10-09 盘点；2026-10-28 按代码复核更新）

> **背景**：用户要求「我要确定现在模组里面有多少套河流与湖泊。不然老是被你搞乱。」
> 本节 = 逐条核实 `src/main` 生产调用关系后的权威盘点。**以后任何讨论先读本节。**
>
> ⚠ **2026-10-28 更新**：本节 2026-10-09 的原稿写「两条链由开关二选一、当前游戏跑 legacy」，**那与代码相反**。legacy 链已于 2026-10-20 整体删除（git 留档）：
> `HydrologyChunkEngine` 现在是无分支薄壳，`usesModelPipeline()` 恒 `return true`，
> `calculate()` 无条件调用 `modelProvider.columnsFor(...)`；TOML 残留的
> `hydrologyModelPipelineEnabled` 键**已无代码读取**，改它没有任何效果（详见 §二）。
> 下面的 legacy 表仅作考古，不表示仍在生产。

### 生产入口只有 1 个：`HydrologyChunkEngine.calculate`（无分支）

```
HydrologyChunkEngine.calculate()   ← 游戏落块的唯一水文入口
  └─ ModelHydrologyProvider.columnsFor()   （model 链，无分支）
```

### 链 A：legacy（❌ 已于 2026-10-20 删除，下表仅考古）

| 组件 | 文件 | 状态 |
| --- | --- | --- |
| 河网 + 湖 | `riverline/RiverLineNetwork`（\~5000 行） | ❌ 已删除 |
| 河道骨架来源 | `flow/TerrainFlowSim`（块分辨率流体模拟） | ❌ 已删除 |
| 汇流累积 | `flowaccum/FlowField`（D8 + 降水加权） | ❌ 已删除 |
| 落块 | `HydrologyBlockCarver.carveChunk` | ❌ 已删除 |
| 门面 | `HydrologyExperimentEngine` | ❌ 已删除 |

### 链 B：model（唯一生产实现）

| 组件 | 文件 | 状态 |
| --- | --- | --- |
| 窗口管线 | `model/HydrologyPipeline` | ✅ 在用 |
| 区域适配 | `model/ModelHydrologyProvider`（region 缓存 + columnsFor） | ✅ 在用 |
| 河网 | `model/RiverNetwork`（D8，默认）/ `ContinuousRiverNetwork` | ✅ 在用（`-Dgeogenesis.modelRiverModel` 切换） |
| 湖 | `model/EquilibriumWater` + `LakeWaterBalance` | ✅ 在用 |
| 中心线 | `model/RiverCenterlines` | ✅ 在用 |

### 死代码（无生产调用点，勿再接回）

| 组件 | 说明 |
| --- | --- |
| `model/WaterField` | **零调用点**。闭式水位场接线三版全部实测失败后回退，注释明写"勿重走"。类保留只作历史 |
| `hydrology/sim/` 包 | **main 里不存在**（早期 sim 原型已删）。`infinite-hydrology-rebuild.plan.md` 规划的 `HydroTile*` 新核心**从未落地**，只有 `_archive` 探针残留 |
| `riverline/MidpointDisplacement.generate()` | 死方法；但 `Node`/`ElevationSampler` 是生产类型，**类不能删**（AGENTS.md 既有警告） |

### 结论（一句话）

**生产实际只有 1 套**：model 链（无分支）。legacy 已删除；
model 链内部有 D8/CONTINUOUS 两个模型变体；`WaterField` 是死代码；**不存在第二套**。

### 当前开关一览（生产语义）

| 开关 | 默认 | 作用 |
| --- | --- | --- |
| `hydrologyModelPipelineEnabled`（config） | — | ⚠ **已无代码读取**，改它无效果（legacy 已删） |
| `geogenesis.modelRiverModel`（-D） | d8 | model 链内部 D8 / CONTINUOUS |
| `geogenesis.boundaryPorts`（-D） | true | 跨区端口/typed 接力/双 pass；`false` 退回旧行为 |
| `geogenesis.riverCrossSection`（-D） | **true**（2026-10-28 起） | 按河宽定深；`false` 退回旧断面 A/B |
| `geogenesis.riverBankHeight`（-D） | false | 坡度岸水高差候选（下移河面 0—1 格） |
| `geogenesis.modelRegion/modelHalo/modelStep`（-D） | 512/96/4 | model 窗口参数（诊断用） |
| `geogenesis.riverThresholdFrac`（-D） | 0 | model 成河阈值面积比（0=关） |
| `geogenesis.sourceSpacing`（-D） | 24 | model 中心线源头间距裁剪（0=关） |
| `geogenesis.inlandResolved` | **true**（代码默认） | model 链内陆窗口可信判据（`f6aa223` 起默认开） |
| `hydro.viaProvider`（-D，探针） | false | 正典图走生产 provider（出图必开，§三） |

---

> **以下为已归档的逐日记录**（2026-10-17 ~ 2026-10-25）。每行保留原编号与一句话结论，全文见 `_archive/hydrology/HYDROLOGY-history-20261028.md`（同名 §编号）。
> 这些段落里**绝大多数**以「未迁生产 / 未验收 / 失败 / 已撤回」收尾，个别标注「已落地 / 已修」的其结论已并入 §二 等正文。**一律不作为当前生产事实引用**；引用前先确认结论是否已被后续段落推翻。

- **§〇c** · 2026-10-17 实验 —— 世界盆体净流接进 HIER 骨架分支；WorldDrainage / WorldBasinRouting / WorldBasinFlow 不是第三条链，非分层分支未迁移 → `_archive/hydrology/HYDROLOGY-history-20261028.md` §〇c
- **§〇d** · G48 —— DemandFlow 显式队列展开唯一前驱树，区分 EXACT / AT_LEAST / UNKNOWN；工作集仍 32768，不提升流量预算 → `_archive/hydrology/HYDROLOGY-history-20261028.md` §〇d
- **§〇e** · G49 —— 共享整数鞍点：不可变 Outlet 裁决替代布尔单口；严格平局与分离鞍点仍 UNKNOWN → `_archive/hydrology/HYDROLOGY-history-20261028.md` §〇e
- **§〇f** · G50 —— 仅 model 普通计划水面用源水，legacy 与瀑布段不动；schema93 → `_archive/hydrology/HYDROLOGY-history-20261028.md` §〇f
- **§〇g** · G51 归因 —— 18 个地下新增水格来自 SpringFeature + FlowingFluid.spread，7 个地表水来自 StructureTemplate → `_archive/hydrology/HYDROLOGY-history-20261028.md` §〇g
- **§〇h** · G52 —— 新增 WorldLakeSurface 做逐块湖面；大图随后暴露异盆争同列冲突，未收口 → `_archive/hydrology/HYDROLOGY-history-20261028.md` §〇h
- **§〇i** · G53/G54 —— 旧「18 条可见河绕岸」表述撤回；盆内廊道实测抬水 15 次 → 0 → `_archive/hydrology/HYDROLOGY-history-20261028.md` §〇i
- **§〇j** · G54/G55 —— 生产大图 TRUE_FAILURE（620827 像素 / 31.63%）；修 storage tree 后 schema97 → `_archive/hydrology/HYDROLOGY-history-20261028.md` §〇j
- **§〇k** · G56 —— 门禁 12 探针 11 绿 1 红；整数拓扑原型只到 build 评估 → `_archive/hydrology/HYDROLOGY-history-20261028.md` §〇k
- **§〇l** · G57 —— 统一图候选拒绝迁入生产：227 处双后继、6149 个起点入环 → `_archive/hydrology/HYDROLOGY-history-20261028.md` §〇l
- **§〇m** · 局部流实验 —— LocalFlow* 系列证明有限寿命输运可行，未接生产 → `_archive/hydrology/HYDROLOGY-history-20261028.md` §〇m
- **§〇n** · G42 —— 世界有限出生事件接线，诊断推进 → `_archive/hydrology/HYDROLOGY-history-20261028.md` §〇n
- **§〇o** · 接口交接 —— 块证书与粗格谓词层级不同，containment 对空集也返回 true，生产接口缺口未闭合 → `_archive/hydrology/HYDROLOGY-history-20261028.md` §〇o
- **§〇p** · 已落地 —— legacy 线移除第一阶段：骨架成为唯一生产河网线；结论已并入 §二 → `_archive/hydrology/HYDROLOGY-history-20261028.md` §〇p
- **§〇q** · 已修 —— 跨区接缝边界带四侧不对称已修 → `_archive/hydrology/HYDROLOGY-history-20261028.md` §〇q
- **§〇r** · 未修 —— JOIN 自汇：绕回自己被记成汇流 → `_archive/hydrology/HYDROLOGY-history-20261028.md` §〇r
- **§〇s** · 已实现 —— M2 接力起点容错，本样例未触发 → `_archive/hydrology/HYDROLOGY-history-20261028.md` §〇s
- **§〇t** · 否证 —— 「精度太高」假设逐项核对后不成立，未据此改参数 → `_archive/hydrology/HYDROLOGY-history-20261028.md` §〇t
- **§〇u** · 归因 —— 15.5% 的「汇流」其实是绕回自己 → `_archive/hydrology/HYDROLOGY-history-20261028.md` §〇u
- **§〇v** · 否决 —— 单点删自汇反而变差 ⇒ 默认关闭 → `_archive/hydrology/HYDROLOGY-history-20261028.md` §〇v
- **§〇w** · 未修 —— 找回机制但修不动，如实记录 → `_archive/hydrology/HYDROLOGY-history-20261028.md` §〇w
- **§〇x** · 否证 —— 洼地出口三个假设全部被否证 → `_archive/hydrology/HYDROLOGY-history-20261028.md` §〇x
- **§〇y** · 口径审计 —— 生产图口径与边界补覆盖回归的隔离归因 → `_archive/hydrology/HYDROLOGY-history-20261028.md` §〇y
- **§〇z** · 口径修正 —— 出图同参与放水口径修正，建立完整 A/B 基准 → `_archive/hydrology/HYDROLOGY-history-20261028.md` §〇z
- **§〇aa** · 被拒 —— 边框显式交接候选被完整端口审计拒绝 → `_archive/hydrology/HYDROLOGY-history-20261028.md` §〇aa
- **§〇ab** · 停止的候选 —— 边框/溢流一体候选按计划触发停止条件 → `_archive/hydrology/HYDROLOGY-history-20261028.md` §〇ab
- **§〇ac** · 停止的候选 —— 边界接力第二轮隔离修复；跨窗溢流仍未覆盖 → `_archive/hydrology/HYDROLOGY-history-20261028.md` §〇ac
- **§〇ad** · 失败关卡 —— 条件 D8 四区独立发现关卡实测失败，不迁生产（本轮投入最大的一段） → `_archive/hydrology/HYDROLOGY-history-20261028.md` §〇ad
- **§〇ae** · 检查点 —— 本地未完成检查点提交（用户批注13） → `_archive/hydrology/HYDROLOGY-history-20261028.md` §〇ae
- **§〇af** · 隔离组件 —— 带历史 JOIN 的反向候选范围：半径 2 反查覆盖已验，仍不能证明清单完整 → `_archive/hydrology/HYDROLOGY-history-20261028.md` §〇af
- **§〇ag** · 隔离组件 —— 上下文前缀与 typed 早退校验：前缀可回放但不足以发布几何 → `_archive/hydrology/HYDROLOGY-history-20261028.md` §〇ag
- **§〇ah** · 反例 —— 最终 shape 也依赖较晚入流，前缀回放不能单独作为可发布几何 → `_archive/hydrology/HYDROLOGY-history-20261028.md` §〇ah
- **§〇ai** · 隔离试验 —— 参考机制回查与普通端口候选覆盖 → `_archive/hydrology/HYDROLOGY-history-20261028.md` §〇ai
- **§〇aj** · 取证 —— 前缀存在性与真实生产路径钉点对照 → `_archive/hydrology/HYDROLOGY-history-20261028.md` §〇aj
- **§〇ak** · 收敛 —— 按用户要求收敛回现有 D8，核对生产输入与短段图 → `_archive/hydrology/HYDROLOGY-history-20261028.md` §〇ak
- **§〇al** · 隔离修复 —— 排除守卫误杀、修正同层边界过拒 → `_archive/hydrology/HYDROLOGY-history-20261028.md` §〇al
- **§〇am** · 隔离剪枝 —— 用户允许舍弃极短河与不通水支系（覆盖此前「不删河」限制） → `_archive/hydrology/HYDROLOGY-history-20261028.md` §〇am
- **§〇an** · 仅诊断 —— 两处 incoming 单次求解捕获与真实湿前缀核验 → `_archive/hydrology/HYDROLOGY-history-20261028.md` §〇an
- **§〇ao** · 隔离候选 —— halo 源组件统一准入；用户选定「一致舍弃」，未验收 → `_archive/hydrology/HYDROLOGY-history-20261028.md` §〇ao

### 〇ap、用户裁定南部长直为地形路线；保存隔离候选检查点（2026-10-24）

用户明确说明：**南部长直不需要解决，之前已测试物理路线，同样如此，是地形导致的河流路线，不是bug。** 此结论覆盖〇am—〇ao中将该外观作为待改善或未验收现象的措辞；历史图与观察保留，但不再据此安排修复、调弯曲度或改变地形。该物理路线对照为用户既有验证，本轮未重新运行。

本次按用户“先提交”保存当前隔离halo一致舍弃候选、949对照源码、独立测试与关键验证摘要；不迁生产、不升缓存、不推送。已有其他研究/发现探针/旧交接改动保留，不混入本次候选提交。大图、批量raw、class、运行缓存仍保留本地，不整目录强制入库。

下一步只围绕保留河系的正确性：先补足其余25条受pin撤销影响的河线湿列/真实JOIN保护，区分合理局部重采样与实际断流；再核验保留端口的接收、共同后继与湖海终点。随后补多seed/负大坐标/下游先查/缓存淘汰/并发取消重试，关卡通过后才迁生产、升缓存、跑门禁与Forge验收。原shape逐字比较的失败证据保留，不把它自动视为断流，也不为凑逐字一致而保留失效交接pin。南部长直不在后续修复清单。

### 〇aq、用户再次确认全周期、连续终点与简单性（2026-10-24，后续验收硬条件）

用户认可〇ap后续顺序，并再次强调：**水文全周期；河流结束必须入湖/入海，不能非正常结束；河流骨架从头到尾连续不中断；不能过度复杂化。**

执行口径：

1. 每条保留河系沿真实有向后继核验源头、支流JOIN、跨区接力直到湖/海；JOIN、BOUNDARY_EXIT、临时ACCEPTED、pending=0和本地有向过滤PASS都不单独充当完整终点证书。UNRESOLVED、EXHAUSTED或DOMAIN_PENDING不能当闭盆、干涸或合法终止。
2. 入湖是河段合法终点，不等于水文生命周期全部完成。复核既有湖泊来水、蓄水/蒸发、水量平衡和达到既定条件后的溢口出流，继续核验出湖河直到下一湖/海。真实无溢出的终端湖可以是合法终点，不能为了“最终到海”强造出流；未认证边界不冒充真实终端湖。
3. 保留骨架连续且真实生产水列不在源—JOIN—接力—河口间残留缺口；像素局部4/8连通只作定位，不替代全链/流体验收。允许舍弃的极短/孤立支系必须几何、端口及可借前缀整体一致消失，不以删除长河局部短连接换绿。
4. 沿现有model D8与已有湖/接力机制收尾，不另建水文/世界图，不递归邻final、不重复pass2、不加预算、不改宽深或惯性掩盖断头；南部长直按〇ap非缺陷处理。

当前固定检查点为`50123978596fa56eeda9f3b19c35f2668822b3b8`，未推送；上述硬条件尚未整体通过。本轮先启动其余25处pin变化的真实生产列A/B，只读取固定候选并新增隔离探针，不改产出规则。端口后继及湖泊全周期核验接着进行，不混用早期world分支历史绿灯。

- **§〇ar** · 未改产出 —— 按全周期口径继续验收：25 处局部回归与 57 端口接收 → `_archive/hydrology/HYDROLOGY-history-20261028.md` §〇ar
- **§〇as** · 仅诊断 —— 两个 core 湖盆出口定点捕获：回到原湖与短湖间连接被删 → `_archive/hydrology/HYDROLOGY-history-20261028.md` §〇as
- **§〇at** · 未通过 —— 出口根因隔离修复，但真实水柱仍红 → `_archive/hydrology/HYDROLOGY-history-20261028.md` §〇at
- **§〇au** · 未通过 —— 实际湖面到出湖河头的最小前缀：隔离样本通过，整体仍红 → `_archive/hydrology/HYDROLOGY-history-20261028.md` §〇au
- **§〇av** · 归因 —— 7 处河段纵向断接归因到逐列取整与未发布 lip；该方向随后被 §〇aw 叫停 → `_archive/hydrology/HYDROLOGY-history-20261028.md` §〇av

### 〇aw、用户纠偏：体素台阶不等于骨架中断，停止lip方向（2026-10-24）

用户明确指出MC体素地形出现台阶正常，要求停止偏离骨架目标的水方块工作、直接出当前水文效果图。**覆盖〇au/〇av的错误验收解释：** 7处已有同trace有序相邻段的水柱无六邻共享面，只能说明初始水块离散形态，不能据此判河流骨架断裂，也不能将其作为必须补lip的依据。原始测量和exit1报告保留，但这些报告不再直接充当骨架连续性失败证书；流体tick效果未实测，不猜测会或不会自行连接。

此前受限lip方案曾获同意，随后已被用户明确叫停。`lip-candidate-1792847183851`与`lip-wet-1792847183851`只保留为停止的隔离实验，不纳入本次出图、不迁生产，不声称已通过。当前出图组合为`outlet-prefix-nearest-1792842919611`及其既有出口候选依赖，沿用原seed/中心/半径700、1401×1401逐块生产`getChunkCells`入口，不改变水方块规则、宽深、惯性或预算。范围外4条后继与1处端口身份变化仍未认证；不能把本次纠偏变成全周期已完成。南部长直按用户确认是地形路线，不处理。

**本次完整出图已生成：** [production.png](forge-1.20.1-47.4.10-mdk/build/hydro-production/prod-1792855942489/production.png)，seed9139912035078620160、中心(-140,137)、1401×1401、step1。真实getChunkCells完成8281chunk、uncovered=0；湖91336、海452661、河37304。对上次halo整图：湖海掩码逐像素不变，2150列干→河、634列河→干，河净增1516；其余干地灰阶未变化，不把面积增加当全周期已通过。加载来源核实为nearest-prefix provider与outlet-candidate骨架，无lip候选；1704个源码/配置/依赖class保护哈希未变，原TOML哈希保持。编译/绘图自检/实际渲染均exit0。后处理初次读取混合编码console日志报错，未重跑求解，只修runner日志读取并对已生成原图完成核验，结果见 [results.json](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/pruning/current-view-1792855900486/results.json)。本图是隔离候选的生产路径离线效果，不是main默认、不是Forge运行后验收；未迁生产、未升schema、未提交推送。

- **§〇ax** · 隔离候选 —— 三类可见缺陷的三补丁，未迁生产（后继 §〇ay 才迁入 src/main） → `_archive/hydrology/HYDROLOGY-history-20261028.md` §〇ax

### 〇ay、★ 迁移入库（2026-10-25，**已进 src/main，用户实机确认一致**）

〇ax 的三处修复与之前多轮隔离实验，此前只存在于 `build/`（被 `.gitignore` 忽略，git 无法保护）。
用户确认目标效果后，本轮把它们迁入 `src/main` 并验证。

**迁移清单（10 个生产类 + 1 个缓存版本）：**

| 文件 | 变化 |
|---|---|
| `DeterministicSkeleton` | 覆盖（含补丁②溢口短段下限、③末端延伸限制） |
| `ModelHydrologyProvider` | 覆盖（含湖岸前缀 attach）；`BOUNDARY_PORTS` 默认由关改为**开** |
| `LakeWaterBalance` | 覆盖 —— 出口算法由「边界最低 bed」改为 `drainageOutlet`（八邻 parent 见证） |
| `GeoGenesisTerrain` | 覆盖（含补丁①湖岸出水判据） |
| `OutletLakePrefix` / `OrderedSpillSearch` / `BoundaryPort` / `WireIn` / `WireReject` / `PortEgress` | **新增**（此前 src/main 缺失的依赖类） |
| `PreviewDisplay.CACHE_SCHEMA_VERSION` | 106 → **107** |

**迁移是真的等价，不是"编译过了"：**

- 10 个生产类逐个用 `javac` 默认选项重编 ⇒ class 字节与目标图所用版本**逐字节相同**。
- 用同一方式重建后出图：`production.png` SHA256 = `74e9dc00…b456e3`、
  `columnsSHA256` = `7f21a5cf…f61879e`，与用户确认的目标图**两者皆逐位相同**。
- 湖 105182 / 河 37270 / 海 452661 / 8281 chunk / uncovered=0。
- 用户已在 `runClient` 新建世界实机目检：效果与图一致。

**⚠ 迁移过程中我踩的两个坑（防重犯）：**

1. **class 哈希不同 ≠ 行为不同。** Gradle 默认带 `-g`（全调试信息），目标图用 javac 默认选项
   ⇒ 同一份源码产物字节也不同。判据应是「**用相同编译选项能否逐字节复现**」，不是直接比哈希。
   实测：默认选项 → 82304 B（与目标一致）；`-g` → 102857 B。
2. **`provenance.txt` 只记录 5 个类，其余同包类会被候选目录静默覆盖。**
   首轮迁移漏了 `LakeWaterBalance`（出口算法不同），出图河 37270→24868。
   必须**按 classpath 顺序全量比对**，不能只看 provenance 里列出的那几个。

**分支与标签：** 本分支已由 `backup/pre-legacy-chain-removal-20261020` 改名为
**`feat/hydrology-target`**（作为事实主线）；安全标签 `archive/pre-hydrology-target-20261025`、
`backup-main-20260930` 保留回退点。

**仍未做：** 完整 `runWorldgenGate`；多 seed；负坐标 / 下游先查 / 缓存淘汰 / 并发取消矩阵。
用户已指出仍有细节待后续调整 —— 不声称"全周期完成"。

### 〇bf、换对话前的状态与纠偏（2026-10-26；优先于下方〇az—〇bd旧稿）

冷启动入口：[GeoGenesis-handoff-20261026.md](handoff/GeoGenesis-handoff-20261026.md)。
旧机制研究全文：[legacy-river-mechanism-20261026.md](handoff/research/legacy-river-mechanism-20261026.md)。

- 用户要求水文全周期：**每条保留的有向河最终到湖/海，骨架从头到尾连续**。JOIN/端口不是最终终点；无向分量任一点触水不是证明。南部长直是地形路线，不作为bug修改。不另建水文、不加预算、不动宽深默认值、不复杂化。
- 当前生产只有model链。`9de0e15`是宽度链检查点，**不是完整河道已验收版本**。此后新增`River`与两个只读访问器将按未完成功能保存，shape/宽深仍消费traces；当前`buildRivers`在中段JOIN时误拼下游前缀，尚未修复或接入生产消费。不得从River数量压缩率宣布连续性完成。
- 旧机制实质从main移到diagnostics。五个核心文件与`b9b55ee`旧快照blob一致；可借鉴节点属性随几何插值、真实汇入索引、跨区沿程状态，以及独立断面职责；**不整体恢复旧链**。
- **撤回“覆盖段数×半宽=河宽”的归因**：当前nearest/IDW不是半宽求和，完全相同集合整体复制不会改变平均值或布尔并集。局部重复/偏移的实际影响仍需定点证明；旧smin也并非幂等。
- **撤回“239组逐点完全重复”的表述**：旧签名round到0.1并排序，丢失节点顺序与方向，只证明该签名相同，不能用来删除生产几何。
- **撤回颜色切换与HALO分布推导的断流根因**：`degree==2`异色只是定位线索，未证明有向断裂或宽深重置；68%分布不是因果证据。A1/A2/A3去重方案不再作为已选修复，未获实施授权。
- **撤回448/452作为全周期证明、外圈4分量必为假孤立的说法**：粗格水体+无向并集不能证明每条源到湖海，外框样本仍待追查。有JOIN owner也不证明接到正确下游后缀。
- **更正断面归因**：half不在最终列结构不等于断面必未计算；应看实际公式。当前生产仍为固定depth加兜底，没有随横向距离变浅的断面；这是未完成功能，但尚未证明它是所有“一节节”现象的唯一原因。
- 当前优先级是先继承**局部连接契约**，不是强制全局River ID。下一轮先验证中段JOIN有序后缀，再选同区JOIN与跨区接力各一处，核对真实接点两侧的Q/半宽/深度/水面。仅整理提交不授权继续实现。
- 本次不改路由、配置或缓存schema（代码当前108）。旧25处/57端口及确定性矩阵、全门禁与Forge全周期验收不能凭历史PASS或本次编译绿宣告完成。

### 〇bg、River局部连接修正与定点取证（2026-10-26；未接河道消费）

用户批准局部连接方案后实施；本节更新〇bf中“中段JOIN未修”的检查点状态，不改变其它纠偏。

- `River`改为局部有向单元：唯一首尾串联才合并；中段/多支流共享下游用`downstreamRiver + downstreamNode`指向真实后缀，保留下游前缀，不由`used`抢走共同后继。缺失、歧义、环和空路径单列状态，不伪装入湖/海。索引仅在当前`Result.rivers`内有效，不是全局ID。
- 生产shape、SegmentIndex、宽深仍消费traces；本次没有接入新的属性策略、修改D8路线/预算/默认宽深。缓存schema保持108，因为未改变实际地形/水文列产出。新聚合仍有分配开销，未做性能基准。
- `RiverConnectionProbe`实际Java断言1137项通过，覆盖中段/首点JOIN、多支流共享、32次输入顺序变化、环/缺失/歧义、相邻重复尾节点及1万段串联。隔离编译本次修改前HEAD `baf5bb5`的骨架类覆盖classpath，反向测试在中段JOIN断言失败（不是编译失败）。
- 同seed `9139912035078620160`、同TOML副本，region(0,0)，默认与显式`-Dgeogenesis.boundaryPorts=true`分组取证。每组分别与隔离旧骨架对照：pipe/traces/shaped/segIndex/boundary/采样列的64位指纹逐项相同。默认合计`-2639223944564017712`，显式组`-2789750574405632679`。这是局部非改产出检查，不是全世界逐块证明或完整出图验收。
- 默认组同区JOIN：trace2→trace0，第16个粗格节点，格坐标(132,-94)；支流实际shaped尾(131.472803467,-93.320006528)到已确认接收折线投影距离0.028761；支流尾段半宽0.878390、接收投影半宽0.879058，而生产`propagateConfluence`仍取承接链首0.878279。只能证明引用位置不同，不能由最终值反推压缩量。接点前/接点/后所采列均有水；不由三列宣布整河连续。
- **开关不一致新证据（未修）**：provider的`BOUNDARY_PORTS`默认true，骨架`PORT_EGRESS=Boolean.getBoolean(...)`默认false。未显式设属性的本次默认组无typed端口；显式组有7个邻区pass1承诺被`mergeEvidence`以同anchor、同next审计接入base。显式组不是当前游戏启动参数的证明。
- 显式组样本：发送(-1,-1)→接收(0,0)，anchor(2,96)、next(2,98)，接收base trace8第26格，`mergeNext==port.next`、审计`RESOLVED:WATER_SEA`。发送pass1 Q10.6512、接收Q11.0736；发送/接收粗水面均131.390213。anchor列fill=true且水柱1，next列fill=false、erosion=0。这证明该采样下拓扑审计与实际湿列不等价；**不等于骨架中心线中断，不据此改路线**。发送侧最终半宽/深度尚未采，跨区属性连续仍未验收。
- `RiverJunctionEvidenceProbe`只读现有缓存与生产列；按目标trace的Shaped坐标定位段，**粗格节点序号不能当重采样节点序号**。本轮早期`evidence/`及`v2-*`半宽取证已作废，不能使用；有效结果是MDK `build/river-local-contract/final-default/`、`final-typed/`及对应`baseline-*`。列采样允许既有边缘惰性邻区求解，JSON记录新增final键，未改依赖架构。
- TOML原件SHA256仍`CC5D985F7554E4CFB316708E88373530241B9520E4CE983F745A6DEFCF50C16F`。`SkeletonProbe synthetic`本次与隔离旧骨架均复现溢口(4,6)未覆盖及`traceOne`反射签名异常，后续断言未执行；不称全绿。未跑全门禁/Forge/新大图/多seed和并发矩阵，未提交或push。

运行新探针（MDK目录，Java21）：

```powershell
.\gradlew.bat --offline --console=plain -I tools/local-flow-reassessment.gradle runRiverConnectionProbe
.\gradlew.bat --offline --console=plain -I tools/local-flow-reassessment.gradle runRiverJunctionEvidenceProbe -PprobeArgs="9139912035078620160 0 0 build/river-local-contract/new-default"
# 显式端口对照，非默认实机证据：
.\gradlew.bat --offline --console=plain -I tools/local-flow-reassessment.gradle runRiverJunctionEvidenceProbe -PjunctionTypedPorts=true -PprobeArgs="9139912035078620160 0 0 build/river-local-contract/new-typed"
```

后续：先核实游戏/JVM实际端口开关，再决定是否统一默认；以现有接点证据设计沿程属性衔接，不能直接照搬旧累积公式或用附近max Q掩盖；正式断面及改变河道产出的A/B仍在后续范围。

**用户再次明确的出图契约（同日，优先）**：骨架仅是路线，中心线必须固定1像素；截面形态、宽度、深度属于河道落地生成，不能混入骨架图。用户指的是后期骨架、关闭宽度但保留其它内容，不是早期迁移版本，也不是同时关蜿蜒的`OFF_all`。因此下述默认开关的带宽度生产图**撤回作为骨架对照**，它只能证明该组条件下非改产出，不能代表用户指定基线。新骨架对照显式保留`boundaryPorts=true`、不覆盖`skeletonMeanderAmp`，关闭`widthFromAnchors/widthBlend/bankJitter/widthMonotone/widthSmooth`；渲染只消费生产`shapedPolys`的坐标，不读湿列/half/depth，不用宽度缩成细河冒充中心线。原生图无抗锯齿、无膨胀，湖海底色来自同次pipe、不是方块放水验收。

**正确口径骨架图已补齐**：MDK `build/river-local-contract/skeleton-width-off/`下`before/after`各跑36区，均1720条trace，未关闭蜿蜒；直接对生产shape作无抗锯齿Bresenham单像素栅格化。全幅3269²、正典裁图1401²；全幅中心线135426像素，两臂掩码及着色/底色差异均0，shape.csv SHA256均`eb1841d6ae88c282b67c71a0e38a8d1a08cdcf9436b2e5967d08813b5987cb13`。与保留的后期`dump-out/polys.csv`按原存储3位小数、保留每条折线节点顺序及方向逐条比对一致（28个非空区/1720折线；其余8区无线），不是早期版本。图见[骨架A/B](forge-1.20.1-47.4.10-mdk/build/river-local-contract/skeleton-width-off/skeleton-ab.png)、[修复前全幅](forge-1.20.1-47.4.10-mdk/build/river-local-contract/skeleton-width-off/before/skeleton-full.png)、[修复后全幅](forge-1.20.1-47.4.10-mdk/build/river-local-contract/skeleton-width-off/after/skeleton-full.png)。结果证明本轮连接表示修正未改变这批shape路线，不代表全周期已通过。没有改生产默认值或TOML。

**先前后补图记录（非骨架验收）**：已用现有`ProductionViewProbe`生产`GeoGenesisTerrain.getChunkCells`入口重跑修改前/后各1401²正典大图（同seed/中心/当前TOML、默认JVM开关）。每臂8281 chunk、uncovered=0；PNG逐像素差异`0 / 1962801`，两臂PNG SHA256均`1226674f61bd31681eb535ac7cbfd4083f33cc1af2cdb3766747a9ba51a52257`，列SHA256均`ae92afd33d6a6fef30979f13032576ef6d7746335d07c7901ba599cc46a626e2`。没有复用旧目标图。结果见[生产A/B](forge-1.20.1-47.4.10-mdk/build/river-local-contract/visual-20261026/production-ab.png)、[Java合成反例连接A/B](forge-1.20.1-47.4.10-mdk/build/river-local-contract/visual-20261026/connection-ab.png)及同目录metrics/provenance；后者不是游戏效果图。修复后首轮出图被后台线程`Comparison method violates its general contract`异常经PowerShell Stop中断，没有有效PNG；保留日志，未修改生产代码，同参数重跑退出0并完成对照。异常根因及归属未定，不宣称已修。

### 〇bs、普通河水越地形、陡坡补挖与窄河深度（2026-10-28；局部生产验证完成，待修后实机）

用户已明确实机认可前两项（跨区湖面、岸水高差）解决；保留该目检结论，不因开关取证倒推撤销。新范围只有：① 普通河水不得填到原地形上方；② 陡坡河道额外下挖1格；③ 全宽3—4格的普通河心约2格深。骨架、宽度、湖盆水位与预算不改，不恢复Level2或流速模型。

**实际启动与根因证据**：本次测试世界的终端命令是普通 `runClient --offline`，未发现深度/岸高差的显式JVM属性；旧代码两项默认关闭。存档副本附近290个可比普通河列，默认生产水顶/床顶floor全部与真实方块相符，而显式两项ON只41列相符。因此本节A/B均以普通启动默认路径为主，不用ON图冒充该实机状态。世界种子 `9139912035078620160`、冻结参数与当前TOML相同；原存档与正式配置未改。

- 水幕样本 `(276,-513)` 为普通河、非湖：雕刻前 `E=193.1952`，旧河面 `S=208.7713`，存档连续16格源水柱；邻干岸约192.61。列生成只保证床面不抬高，却未限制普通河水面，非湖格的逃逸高程因此被当成悬空填水高度。不是正常瀑布的既定语义。
- 陡坡窗 `center=(-955,-345), radius=48`：原314河列中4对相邻列之间空1整层水（高侧床floor高于低侧水顶floor）；不是所有竖直面接触都已认证。

**窄修实现**（schema117）：
- `columnsFor`本区、惰性借河及湖岸退回河道最终共用出口，先令普通河 `S=min(S,E)`，再算床面；真湖在上方提前返回，海水仍由落块层独立补灌。
- 从既有侵蚀网格中央差分得到四角梯度并双线性插值，不用最近格坡度制造阶跃，也不新增地形采样。坡率≤0.5不补挖、≥1补1格，中间smoothstep。补挖放在湿床整数保护之后，只降床、不抬水，深度ON/OFF两个分支均覆盖。
- 宽深指数由0.75校准为0.64444259751338：W1→1、W3→2、W4→2.2986、W12→4；仍是连续U形断面，不量化噪声或河宽。`riverCrossSection`改默认开启，否则普通启动根本不消费宽深标定；显式false保留旧断面作A/B。`riverBankHeight`仍默认关，未顺带切换；两个键原已纳入策略指纹。

**实际验证**（MDK `build/river-water-fix/`）：
- main/diagnostics编译成功；断面2150、profile370、宽度176、连接1137、岸高差15259项通过，覆盖查询、汇流断面、生产放水自检亦退出0。深度集成新增DEFAULT无属性子JVM，与ON/OFF及jitter0/0.25共六组通过；平面/二次面梯度独立期望覆盖step1/2/4。
- 水幕窗4225列：209个原河列全部保留，超地形河列145→0；目标列16→1块水，非河地形/湖海分类不变。陡坡窗9409列：314河全部保留，4处整层空隙→0；显式旧深度OFF分支亦无整层空隙、无超地形河水。
- 窄河窗9409列：765河全部保留、非河地形不变。真实名义全宽3.056格的 `(247,92)` 河心，连续水深1.0013→2.0082，整数水柱1→2。小数水面相位已加合成验证；W4的2.2986连续目标可能落成2或3块，不承诺每列固定2块。
- 湖接缝探针725检查通过；55282列patch中22689个湖列的所有CSV字段、湖掩码与前版完全相同。入海窗1367海列床/水/水柱完全相同；248河保留。岸高差显式ON回归4435原河列无丢失，因本次水面cap和深度标定不声称水面/深度逐位不变。
- 同参region(0,0)默认组及显式boundaryPorts组各自前后 `shape.csv/widths.csv`逐字节相同。两组彼此不是同一几何：provider默认BOUNDARY_PORTS=true与骨架PORT_EGRESS默认false的旧差异在〇bg已记，本次未改，不能把显式组当默认启动。新seed12345局部2401列含16河，超地形与整层空隙均0，只是补充样本。
- 三个build副本变异（去水面cap、去陡坡补挖、恢复旧指数）均先编译成功，再被对应独立断言抓红退出1；不是编译失败冒充检出。
- [方块剖面A/B](forge-1.20.1-47.4.10-mdk/build/river-water-fix/river-water-sections-ab.png)、[生产俯视A/B](forge-1.20.1-47.4.10-mdk/build/river-water-fix/river-water-production-ab.png)均从本节默认生产CSV绘制；前者按真实整数放水公式还原，非游戏截图、非认证法向。完整判据与哈希在 `comparison-final.json` / `image-manifest.json`。

**必须保留的边界**：131对河湖邻列中，`(-555,-144)`普通河因原地形仅166.9078，不能保留旧167.3589河面；邻湖167.3533保持权威，水顶因此新增1层差（连续差0.4455格）。这是满足“不超地形”后的明确接口变化，不标成接口全不变，也不为消除它抬高普通河水。额外1格仅修已测整层空隙，不证明任意悬崖四邻水面接触、无反坡或全周期。未运行新Forge世界/流体tick/全门禁或全图；未提交、未push，旧已生成区块不会自动重雕。默认新增逐河列梯度算术及深度剖面成本，未做隔离性能基准，不称零开销。

### 〇br、坡度岸水高差候选（2026-10-28；历史实施记录，用户后续实机认可见〇bs）

用户批准只处理水面贴岸顶，并选择“随坡度0—1格”，不做流速模型、路线/牛轭湖或Level2。基线包含上一节已修湖面，未提交。原测试存档附近 `center=(-1270,295), radius=96, seed=9139912035078620160` 生产37249列中4435河列：水面减雕刻前地面中位0，3143对河/干岸相邻样本有3043对顶块Y相同（不是唯一列数或精确液面网格高差）。

**规则与实现**：新增包内 `RiverBankHeight`，从既有侵蚀网格以±8block中心差分量地形梯度；坡度≤0.02不降、≥0.10下降1格，中间smoothstep。这是游戏尺度候选，不是流速或水力学公式；梯度含横坡，未单独重建沿河纵坡。邻近湖海16格范围平滑减为0，水格及其两格保护带不下移；海平面不得被降低。`columnsFor`仅在已有河道命中且湖列分支结束后应用，沿用既有湿宽和深度断面，用新水面同步确定河床。深度候选关闭时沿用原基线床面下挖量后加同样下移量。候选开关 `-Dgeogenesis.riverBankHeight=true`，默认关；纳入策略指纹，缓存schema116。原TOML、运行客户端与存档未动。

**接点回归与修正**：第一版只看本区湖海掩码，遇邻区借湖时131对紧邻样本中7对新增整数水顶错开，已否决。final仅在已有双pass生产路径读取固定8邻pass1掩码并转换同一世界格，合并作渐隐保护；不读邻区final、不新增pass2，不改湖域。这增加网格合并/距离计算及一个逐区double场，尚无隔离性能基准，不能称零开销或任意生成顺序下免费命中缓存。关闭boundaryPorts/crossPass或HIER实验未纳入生产泛化。

**final实测**（MDK `build/river-bank/`）：
- `compile-v2.log`编译通过；`RiverBankHeightProbe`15259检查/0失败，覆盖step1/2/4、平地/坡地、借湖世界坐标、渐隐和只降水面。隔离将MAX_LOWERING改0：编译0、探针退出1（10096断言失败），反控有牙齿。断面2092、深度profile370、宽度176、连接1137及距离查询/覆盖探针均退出0。
- 原位置A/B2：4435河列全部保留、非河列变化0，采集连续水深不变；水面下移P50=0.8361格。相邻岸地面减水面中位0.0042→0.8848格；最大水面下移1，不等于所有岸水高差≤1（原横坡仍在）。横切中心(-1290,323)下移0.8466格、深度3.1926不变；(-1297,350)下移约0.27格。
- 新挖量必然增加：maxCut3.2697→4.1756，161列cut>4；目标水深上限未改，不将旧cut≤4误当新水深约束。
- `lake-final`725检查/0失败；与〇bq的verified对照，22689湖列及湖标记逐位一致，969原河列无退水，131对河湖紧邻河水面原样保留，无新增整数错层。
- `coast-A/B`含1367原海列，床面/水面/水柱逐列一致；248河列保留，原湿列无退水。额外两处519/96、-670/660样本虽绿但实际不含海，不能称入海口验收。
- `bank-sections-ab.png` / `bank-production-ab.png`来自final B2生产CSV，非游戏截图；横切为X向采样非认证法向。无需全图重跑。

**未验收**：修后Forge流体tick、全世界/多seed/跨窗口连续性及方向性水面不反坡。湖边保护属于空间渐隐，不等同于认证的沿河回水纵剖面。默认尚未开启、不提交或push；游戏须在用户决定后用新进程/新世界观察，旧区块不会自动修复。

### 〇bq、跨区湖列的水位来源修正（2026-10-28；历史实施记录，用户后续实机认可见〇bs）

用户实机报告湖面沿直线整齐错层；深度候选已先本地提交为 `8310b71`。本轮只修湖泊填水，不处理河岸高差，不改骨架/宽度/深度或湖盆求解，不重启运行中的客户端。

**取证**：对测试存档副本读取真实水块，并用同种子 `9139912035078620160`、冻结参数（与当前TOML一致）复算生产列。`(-300,-513)/(-300,-512)` 在 `z=-512` 边界水面为 `175.472792/167.569432`，存档源水顶块 Y 为175/167；`(-513,-170)/(-512,-170)` 在 `x=-512` 边界为 `164.872942/168.816483`，源水顶块Y为164/168。深度OFF/ON原数据的这些湖列相同，故不是新深度公式导致的回归。

**两处同类错误与修法**：
- 邻区命中只提升 `isLakeBlock=true`，却仍用本区非湖格的逃逸水位填湖。改为同时保留命中湖格的 `waterSurface`，湖岸兜底判定与湖列输出都使用它。
- 本区湖岸使用双线性采样时会混入非湖格的逃逸高程。湖列改取所在湖格的单平面水位；河列仍使用原来的插值结果，不因湖岸退回河道而泄漏借来的水位。
- 原世界/共享湖权威规则不变，没有开启旧实验或新建跨区求解；缓存schema升115。

**验证（只限已测范围）**：
- `compileJava/compileDiagnosticsJava`通过；断面纯函数、深度剖面、距离查询集成、宽度剖面、连接、覆盖及放水口径自检均退出0。
- 新 `LakeColumnSeamProbe` 先在旧版四处接缝12检查中失败8项、退出1；修后含整行与独立湖/干地对照共725检查、0失败、退出0。
- 两片同参 `GeoGenesisTerrain.getChunkCells` 生产patch合计55282列：254对相邻湿湖列水位差均0；原8格/4格台阶消失。四个独立湖水位保留，943个前后均判为河道的列床面/水面/水柱一致。
- 湖岸归属不是逐位不变：1213列湖标记变化（288湖→干、889干→湖、10河→湖、26湖→河），源于使用正确湖平面后重新沿用既有岸线/出水判据，不可宣传为“整个图零副作用”。
- 证据在MDK `build/lake-step-fix/`：`verified/`、`verification-manifest.json`、`patch-comparison-final.json`及 `lake-level-ab-1/2.png`、`lake-surface-lines-ab.png`。图表对应最终验证CSV逐字节一致。

**未验证**：全世界所有跨区盆地、多窗口都已判湖却水位各异的其它情形、Forge修后流体tick与装饰。旧区块不会自动修水；尚未提交本轮修复，未修改原存档或TOML。第2项河岸高差保持搁置。

### 〇bp、骨架软约束平滑 + 连续惯性（2026-10-27；骨架折线定稿，用户验收）

**用户判据（决定性）**：「让节点不再是骨架线段的格心，而是变成一个牵引力引导。这样只有起点和终点是确定重叠在节点上。」「**只要节点不动必然出现折线**。」

上一轮把折线当成"平滑算法没跑"，方向错了。真因是**约束类型**：`catmullRomResample` 的控制点是邻居节点的凸组合，**必然落在折线上**；三节点共线 ⇒ 4 个控制点全共线 ⇒ 三次贝塞尔退化成直线，任何参数化都救不回来。

**三次被实测否决的路线（均"节点不动"）**

| 方案 | 结果 |
|---|---|
| meander 纯几何偏移 `skeletonMeanderAmp=3.0` | ≥28° 折角 6.3%→8.7%（更差）；0.6 时肉眼不可见 |
| 矢量钢笔贝塞尔手柄（`4e6012b`，端点=节点） | P99 54.6°→45.0°、≥28%→3.7%，**仍是"直线段+折角"**；叠加软约束后 P90 5.0°→15.3°，已删代码 |
| 落格滞回 `landingHysteresis` 0.12 / 0.35 | 横向往返仅 260→251，受影响 trace 不变（仍 30.1%），且节点少 920（路线被明显改变）；已删代码 |

**定稿方案（`DeterministicSkeleton`）**

① **软约束平滑** `softenAnchors`（默认开）：节点降级为牵引力目标，求解

```
minimize  Σ w·|p_i − t_i|²  +  Σ |p_{i+1} − 2p_i + p_{i−1}|²
迭代      p_i ← ( w·t_i + p_{i−1} + p_{i+1} ) / ( w + 2 )      固定 16 轮 Jacobi
硬钉      p_0 , p_{m−1} , 以及所有【跨链共享锚点】
```

台阶的相邻点均值落在对角线上 ⇒ 迭代把它拉过去；本来就是直线的一段均值等于自身 ⇒ 完全不动。这正是硬插值做不到的。共享锚点（`anchorTable` 已登记）必须硬钉并回写，否则两条链各自平滑出不同坐标 ⇒ 新缝隙。

② **连续惯性** `CONTINUOUS_INERTIA`（默认开）：`lastDir` 原来被赋成**整数格差**归一化值 ⇒ 只能是 8 个方向之一 ⇒ `keep=max(0,u·dir)` 只有 3 档，惯性退化成开关量。改为使用 `vectorTraceNext` 已算好的**连续合成方向** `stepDirOut`（与 `−∇bed` 同量纲），落点仍由 nearest-8-neighbour 决定 ⇒ 落实「条件连续、命中 D8」。wire/spill/溢口无向量结果时退回格步方向（循环顶部先清零，避免沿用上一步残留）。

> ⚠ 连续惯性首测（当时无软约束）折角反而变多（P90 22.8°→24.8°）已回退；软约束上线后重测转正。**两者必须同开**。

**数据（9 区 45757 节点）**

| | P50 | P90 | P99 | ≥28° | 横向往返 | 受影响 trace |
|---|---|---|---|---|---|---|
| 基线 | 2.02° | 22.8° | 54.6° | 6.3% | 475 | 41.2% |
| 软约束 0.45 + 连续惯性 | 1.30° | **5.1°** | **13.9°** | **0.3%** | **260** | **30.1%** |

全图生产 `prod-1793114874402`：河 56914→59250 像素，`degree=1` 悬空端点 609→485。
36 区契约 `EXIT=0`：端口 124 项 gap=0、224755 节点零越限、729 接口零越界。
`-Dgeogenesis.softAnchor=false` 时 `(-1,-1)` 单区 3718 行节点数据逐行相同（逐位可回退）。

**剩余（如实）**：30% 的 trace 仍有横向往返，形态上表现为小钩子。已定位为 D8 格心路由的下限——落点被限在 8 邻格里，接近垂直且带侧偏的流向必然在相邻两列间跳（实测 `(-1,1) poly1` node125–133 在格 (74,·)/(75,·) 间来回）。滞回只能局部修补，**要根治须「位置驱动格」**（`pos += dir`、`cell = round(pos)`，见 `shape()` 中注释"正确做法（未实现）"），那会改路线本身，未开工。

**未验证**：Forge 实机、正式河道断面、多种子矩阵。

### 〇bo、河宽规则链定稿：终点反推起点（2026-10-27；优先于〇bn，用户验收通过）

用户指出「**明明我说的很多内容都是描述同一个要求**」，并把规则收敛为**一条链**。此前〇bm/〇bn 的 P0–P6 表把同一要求拆成多条、彼此冲突（出湖量被当成整条河上限、逐节点单调与汇流契约互相打回），本轮全部推翻并删除。

**唯一规则链（`RiverWidthProfile` 类头即文档）**

```
① 定终点   接收水体在该接口的尺度
           入湖 = 接收湖横向尺度（LakeWidthEnvelope 的 LAKE_MOUTH 测量）
           入海 = 本河末端天然宽度（用户确认的 A 方案，不加额外约束）
② 定起点候选  真源头 = 1 格全宽
             出湖   = 湖泊尺度（随湖大小变化），全宽 ≤ 6 格
③ 反推起点  起点 = min(候选, 终点)      ← 任何情况都由终点反推，起点不得超过终点
④ 连线     中间节点 = 起点↔终点 按弧长线性插值，不逐节点压制
⑤ 钳制     全宽 ∈ [1,12]
```

用户原话：「**是任何时候都是终点反推起点，不是在什么情况下。我之前就说了，起点除非是真起点才1格。**」「**起点的宽度不能超过终点的宽度啊。**」「**这个是看湖泊大小变化的。但不会超过6格的最大宽度。**」

**接缝契约**（与宽度走向无关，只在端点生效）：跨区 typed 端口双方一致；串联共用断面；汇流后 ≥ 任一入流。

**已删除的三处错误实现**
1. `feasibleStartHalf` 的"斜率预算" `终点 + 1/32×弧长` —— 它让长河起点合法地粗于终点（poly16 即 6.00→1.38）。改为纯 `min(候选, 终点)`，**结果与弧长无关**。
2. `enforceDownstreamMonotone` 及 `isPinned` —— 逐节点单调扫描。三次实错：全局运行最小压平 370 条汇流树；单 trace 逐节点封顶造成 106–114 处汇流违约；而禁用后同区契约全绿。
3. 出湖量 `Math.min` 铺满整条 trace —— 压平 101 条河（如 `(0,-1) trace24` 11.19→2.20 恒定）。出湖量只是**起点取值**。

**实测结果**
- `(-1,-1) poly16`（用户点名的 (-198,-257) 河段）：`6.00 → 1.38`（倒挂）变为 **`1.38 → 1.31`**（不倒挂）。
- 全图「起 > 尾 + 0.5」：30 → **9**（1597 条 ≥20 节点河道）。
- 36 区 `final-rule-36`：**843/843 汇流、137/137 端口、227894 节点零越限、732 接口零越界、contractFailures=0、allGreen=true**。
- 探针：宽度 **176 断言**全通过（`testFeasibleStartPriority` 重写为端点反推契约）；连接 1137、覆盖探针通过。
- 生产图 `prod-1793064504828`：8281 chunk、uncovered=0、河 57983（上一版 61702；整条变细符合"起点被终点压低"的预期）。

交付：[全图 A/B](forge-1.20.1-47.4.10-mdk/build/river-width-trend/rule-production-ab.png)、[点名区域 A/B](forge-1.20.1-47.4.10-mdk/build/river-width-trend/rule-point-ab.png)。用户已确认「很好，就这样，达到我要求了」。

未测：Forge 实机、完整 worldgen 门禁、隔离性能基准。

- **§〇bn** · 已被取代 —— 宽度约束优先级表 + 过渡可行性 P4 —— 已被 §〇bo 取代 → `_archive/hydrology/HYDROLOGY-history-20261028.md` §〇bn
- **§〇bm** · 被取代 —— 出湖宽度改为上限 + 顺流不得变窄 → `_archive/hydrology/HYDROLOGY-history-20261028.md` §〇bm
- **§〇bl** · 已被推翻 —— 出湖宽度跨窗口一致；其 "floor" 语义已被 §〇bm 推翻 → `_archive/hydrology/HYDROLOGY-history-20261028.md` §〇bl
- **§〇bk** · 被取代 —— 河湖海接点尺度匹配候选 → `_archive/hydrology/HYDROLOGY-history-20261028.md` §〇bk
- **§〇bj** · 被取代 —— 真河源沿程线性展宽 → `_archive/hydrology/HYDROLOGY-history-20261028.md` §〇bj
- **§〇bi** · 被取代 —— 真源头起宽与跨段重置修正候选 → `_archive/hydrology/HYDROLOGY-history-20261028.md` §〇bi
- **§〇bh** · 被取代 —— 河道宽度候选：总体趋宽、局部浮动与接点连续 → `_archive/hydrology/HYDROLOGY-history-20261028.md` §〇bh
- **§〇az** · 归因（不完整） —— 「宽度变宽 = 同一条链自折返叠加」；后被 §〇bc-2 补正，跨区重复叠加是更主要来源 → `_archive/hydrology/HYDROLOGY-history-20261028.md` §〇az

### 〇ba、★ 分层纠正：骨架只负责路线，宽度/深度属于「河道生成」层（2026-10-25，长期有效）

用户原话（必须长期遵守）：

> 「我记得我设计的骨架是作为河流的路线使用的，最终是不负责落块河道生成。测试中落块是方便
> 游戏观察实际路线正常不正常。但现在已经完成骨架了，骨架路线完成后的下一步河道生成
> （最终落块），河道就是实际游戏看到的东西，有宽度深度等信息。」

**这解释了为什么「骨架有没有宽度」这个问题本身是错的**：

- 骨架 = 中心线折线。判据只能是**位置/连续/终点**，不该有宽度。
- 我在诊断图里画 `Cell`（落块河格）⇒ `Cell.fillWater` 依赖 `half` ⇒ **宽度混进"骨架图"**，
  这是用户三次点名的同一个错误（"为什么这骨架还是有宽度的？"）。
  **诊断出图必须画 `shape()` 的浮点中心线，1 像素**。
- 旧版（legacy `HydrologyBlockCarver`）也是这个分层：骨架选线，落块层做断面
  （`t = dist/width`、`profile = 1-t`、`bedTarget = carveSurfaceY - depth*profile`）。

**当前代码与这个分层的差距（事实，未修）：**

`ModelHydrologyProvider` 落块时
`out.add(new HydrologyBlockCarvedColumn(x, z, original, carved, surface, surface, depth, 1.0, fill, false, null, NaN))`
—— **`half` 根本没进落块列**，只用于判 `fill`（布尔）。即"河道断面层"从未落地，
宽度只影响"这块算不算河"，不影响"河有多深"。⇒ 这正是用户说的「宽度变成一节节」的上游原因。

- **§〇bb** · 量化 —— 「一条河被切成多段」中折线归属层只占 0.37% → `_archive/hydrology/HYDROLOGY-history-20261028.md` §〇bb
- **§〇bc** · 已作废数字 —— 448/452 分量触水；1401² 窗口测到的 13 个「孤儿」因窗口硬边作废 → `_archive/hydrology/HYDROLOGY-history-20261028.md` §〇bc
- **§〇bc-2** · 仅取证 —— 跨 region 重复发布：29.2% 的 trace 几何重复，只解释宽度叠加、不解释身份切分 → `_archive/hydrology/HYDROLOGY-history-20261028.md` §〇bc-2
- **§〇be** · 归因 —— 身份切分真因：68% 切点落在 HALO 区、50% 是跨 region 交接；A1/A2/A3 均未实施 → `_archive/hydrology/HYDROLOGY-history-20261028.md` §〇be

### 〇bd、诊断出图与测量的正确做法（本轮踩的坑，防重犯）

| 坑 | 后果 | 正解 |
|---|---|---|
| 用 `Cell.riverType` 画"骨架图" | 宽度混进图里，用户三次纠正 | 画 `shape()` 的浮点折线，`Math.rint` 到 1 px |
| `getRGB()==0x202020` 比较背景 | `TYPE_INT_RGB` 的 `getRGB` 返回 **ARGB**，判据恒 false ⇒ `pixelsA=0` 两张图全废 | 改用 `getRGB(...) & 0xFFFFFF` 或按颜色分量判 |
| 反射读 `STEP` 时误写成 `step = rf.getInt(null)` | 读成 `REGION=512`，水体坐标放大 256 倍 ⇒ 掩码只剩 4 个像素 | `step` 从 `pipe.cellBlocks()` 取 |
| `polys.csv` 写全局 trace id，`trace-river-map.csv` 写局部 `ti` | 两表对不上，B 图只有第一个 region 上色 | 两表统一写**全局**下标 |
| 用"颜色切换总数"判"河被切断" | 把汇流点（合法变色）算进去 ⇒ 数字虚高 | 只在 `degree==2` 的像素上判 |
| 每次改判据都重跑求解（6~10 分钟） | 迭代极慢 | 一次导出 `polys/rivers/traces/join-downstream/water` 五张 CSV，之后全在 Python 里做 |

证据目录：`forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/pruning/lake-edge-1792872387157/dump-out/`
（`polys.csv`、`rivers.csv`、`traces.csv`、`trace-river-map.csv`、`join-downstream.csv`、
`water.csv`、`A-trace.png`、`B-river.png`、`AB-skeleton.png`、`AB-skeleton-zoom.png`、
`cutpoints.png`、`verdict-full.png`）。

**未做**：A 的后半段（宽度/深度/水面按 River 求值）；把上面五张 CSV 的导出固化成可复跑探针。

⚠ 本节原把「宽度变粗」全归给自折返，**已被〇bc 修正** —— 更主要来源是跨 region 重复发布。

</details>

- **§一** · 旧快照 —— 当前状态表（仍写「未推送 是」），现状看 §二 与 §〇b* → `_archive/hydrology/HYDROLOGY-history-20261028.md` §一

## 二、生产只有一条链（legacy 已于 2026-10-20 删除）

> ⚠ **本节曾写「两条链由开关二选一」，那是过时描述。**
> legacy 链（`HydrologyBlockCarver` / `riverline/*`）已整体删除（git 留档），
> `HydrologyChunkEngine` 现在只有 60 行，`usesModelPipeline()` 恒 `return true`，
> `calculate()` 无条件调用 `modelProvider.columnsFor(...)`。
> TOML 里残留的 `hydrologyModelPipelineEnabled` 键**已无代码读取**，改它不会有任何效果。

```
HydrologyChunkEngine.calculate()   ← 唯一水文入口
  └─ ModelHydrologyProvider.columnsFor()   （model 链，无分支）
```

下表是 legacy 与 model 的**历史语义对照**，仅供考古，不表示 legacy 仍存在：

|  | legacy 链（已删除） | model 链（唯一实现） |
| --- | --- | --- |
| 入口 | `HydrologyBlockCarver.carveChunk` | `ModelHydrologyProvider.columnsFor` |
| 河网 | `riverline/RiverLineNetwork`（region 640wu，D8 追踪 + 跨区续流 `OutletSeed`） | `model/HydrologyPipeline`（窗口 + 多 region 拼接） |
| 湖 | `RiverLineRegion.LakeNode` + `spill` 溢出高程 | `EquilibriumWater`（priority-flood）+ `LakeWaterBalance`（水量平衡） |
| 定位 | **被 model 链取代的旧版** | ★ **升级版（目标）** —— 见 §〇③ |
| 默认 | ✅（现状，**但这是待改的**） | ❌ |

⚠ **不要把"当前默认是 legacy"误读为"legacy 是正确目标"** ——
用户的定位是 **model 取代 legacy**（§〇③），legacy 仅为现网跑着的旧版。

**model 链内部还有两个模型**（`HydrologyPipeline.RiverModel`）：

|  | D8（默认） | CONTINUOUS |
| --- | --- | --- |
| 算法 | priority-flood + D8 拓扑累积，**确定性** | 蒙特卡洛流线积分（5 万条随机起点） |
| 耗时 | 快 \~3× | 慢 |
| 开关 | 默认 | `-Dgeogenesis.modelRiverModel=continuous` |

---

## 三、★★ 铁律：预览必须等于游戏

**用户核心要求**：「渲染出图必须和游戏实机一样」。

**历史教训**：此前预览探针（`CanonicalViewProbe`）**直接调 `HydrologyPipeline.run`（单窗口）**，
而游戏走 `ModelHydrologyProvider`（**按 region 分次求解再拼接**）
⇒ 两条完全不同的执行模型 ⇒ 用户反复发现"图上有的、游戏里没有"。

**⇒ 现行做法**：预览必须走**生产 provider**：

```bash
# 正典规格图（1401×1401）走生产路径 —— 产出的图 = 游戏会落的水
gradlew runCanonicalViewProbe -Dhydro.viaProvider=true -Dgeogenesis.inlandResolved=true
```

**判据也必须与游戏落块层逐字一致**（`GeoGenesisGenerator:781`）：

```
湖 = column.lakePlan()
河 = column.fillWater() && column.waterSurfaceY() > column.carvedGroundY()
```

⚠ **不要用 `erosion() > 0` 判河道** —— `erosion()` 在 legacy 链含**整个河谷**
（河床+岸坡+谷壁），实测会虚报（9.86% vs 真实 3.06%）。

---

## 四、★ 正典规格（定稿，勿改）

| 参数 | 值 | 说明 |
| --- | --- | --- |
| `radius` | **700** | ⇒ 1401×1401 |
| `step` | **1** | 1 像素 = 1 方块 |
| `seed` | `9139912035078620160` |  |
| 中心 | `(-140, 137)` |  |
| 底图 | 无光照等高线 | **hillshade 会把谷画成脊**（已误判两次） |
| 色标 | 固定（`ViewSpec.BAND_BASE/BAND_BLOCKS`） | 跨图可比 |

**唯一来源**：`diagnostics/.../model/ViewSpec.java`。
`ViewSpec.assertCanonicalRadius` 会硬断言 —— 传错半径的图**不可与正典图互相印证**。

---

## 五、参考项目：四家一致的答案（★ 重要）

`参考/river/` 下四个项目**全部不需要跨区水量传递**，做法一致：

| 项目 | 单元尺寸 | 保证"任意位置单独查询都连续"的机制 | 跨区传水 |
| --- | --- | --- | --- |
| **FTF** | \~12000 格 | 边界按构造落在**海里**（edge 函数边框恒 0） | **无** |
| **PL-RGA** | \~6667 单位 | `border_safe`/`lake_safe`：河/湖被挡在离边界 margin 外 | **无** |
| **DW** | **640 格（与我们同）** | **共享锚点**：本区汇点 = 邻区源点（同种子公式 ⇒ 逐位相同） | **无** |
| **Streams** | 8×8 区块 | 接口水位 = **海平面常数** + 固定截面表；下游**复制**上游截面 | **无** |

**共同点**：

1. **水位是【局部量】**（位置纯函数 / 出口高 + 固定比降 / 常数），**不是流域累积量**
2. **单元尺寸 ≥ 流域尺度**（否则边界落在陆地上 ⇒ 照抄会直接得到"边界断河"）

### 核实状态（2026-10-09，逐条回源码验证过）

| 断言 | 证据 |
| --- | --- |
| DW `REGION_SIZE=640` | `HydrologyManager.txt:3`（山地河 `:9` = 256） |
| **DW 零汇流累积** | 全仓搜 `accum\|discharge\|catchment\|watershed` ⇒ **命中 0 文件** |
| PL-RGA `border_safe`/`lake_safe` | `flat_infinite/src/terrain.cpp:319-320`，`border_margin = border_margin × plate_scale` |
| PL-RGA 河道不离 plate | `config.hpp:14` 注释原文 "so river channels never leave their plate"；`river_border_distance=100`（`:63`）、`river_lake_radius=150`（`:65`） |
| Streams 主干水位 = 海平面 | `Segment.scala:27` `lazy val surfaceLevel: BlockY = generator.minSurfaceLevel` |
| FTF 边框 edge 恒 0 | `EdgeFunction` 的 `DISTANCE_2_DIV`（`distance2 - 1.0`）⇒ 边框处 `distance==distance2` ⇒ 值 0 |

**对我们的启示**：

- **★ 骨架必须确定性、与窗口无关**（§〇②）—— 这是用户的硬需求（无限世界）。
  DW 的**共享锚点**是直接可借鉴的正解：本区汇点 = 邻区源点（同种子公式 ⇒ 逐位相同）。
- legacy 链**已有**跨区机制（`sampleAll` 3×3 邻区 + `OutletSeed`），与 DW/Streams 同源
  ⇒ 这是 **model 链（升级版）必须继承的既有优点**（§〇③），不是"legacy 的专利"
- model 链**丢了这两个机制**（只用本区、内陆窗口失效）⇒ 这是"进实机就断"的根因
- **湖水位局部化**：DW/Streams 干脆不做物理湖；我们保留物理湖（但要解决其窗口依赖）

---

- **§六** · 历史 —— 2026-10-07~09 本轮改动：已修真 bug、断流归因、诊断旋钮、验收基线 → `_archive/hydrology/HYDROLOGY-history-20261028.md` §六
- **§六b** · 历史 —— 2026-10-10 确定性骨架 + 三项形态修复的量化记录 → `_archive/hydrology/HYDROLOGY-history-20261028.md` §六b
- **§六c** · 历史 —— 2026-10-10 深夜两处伪影的定性与修复，含有界粒子代价 → `_archive/hydrology/HYDROLOGY-history-20261028.md` §六c

## 七、接手须知（血泪教训）

1. **修任何水文问题前，先跑 `-Dhydro.viaProvider=true` 出图** ——
   否则你看到的不是游戏会落的水。
2. **不要用"湖面积占比"对比不同窗口的图** —— 湖量随窗口大小变化（物理量本就依赖流域）。
   有意义的判据是**覆盖率/精确率**（见 §六基线表）。
3. **出图半径是硬断言** —— `ProductionViewProbe` 传非正典半径会**直接抛异常**
   （因为我在同一轮里重复用 `r=300` 与用户的 `r=700` 参照图对比，被用户两次指出）。
   确需小窗探索：`-Dhydro.allowNonCanonicalRadius=true`。
4. **判据必须与游戏落块层逐字一致**（§三）。
5. **"平行线"是地形冲沟，不是缺陷** —— 项目已多处记录
   （`CenterlineCrossSectionProbe:155`「用户已确认是地形正确形态，不得判失败」）。
6. **不要用"跨区一致性"当验收口径，除非比较窗口【边界带】**
   —— 我写过一个探针比较两窗口重叠区，但两中心只差 120 块、半径 252
   ⇒ 重叠区全在窗口深处、本来就一致 ⇒ 对照组与实验组结果无差别（0.00% vs 0.05%），
   **验收无效**。（原提交的 1456/1458 比较的是 `margin=320` 的边界带。）
7. **改地形/水文产出必须升 `PreviewDisplay.CACHE_SCHEMA_VERSION`**。
8. **`docs/` 与 `参考/` 被 `.gitignore` 忽略** ⇒ 新文档放仓库根才入库。

---

*本文为水文系统的唯一事实来源。历史文档见 `_archive/hydrology/`（只读，仅供考古）。*
