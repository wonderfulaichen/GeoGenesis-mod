# 水文系统 —— 单一事实来源（2026-10-09 重建）

> **本文件替代根目录原有 12 份水文文档**（已归档至 `_archive/hydrology/`）。
> 归档原因：那些文档彼此矛盾、多数已过时（详见 `_archive/hydrology/README.md`），
> 继续放在根目录会误导接手者 —— 本项目已因此连续出错多轮。

---

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

## 〇b、★ 系统总账：模组里到底有几套河流/湖泊（2026-10-09 盘点，以代码调用关系为准）

> **背景**：用户要求「我要确定现在模组里面有多少套河流与湖泊。不然老是被你搞乱。」
> 本节 = 逐条核实 `src/main` 生产调用关系后的权威盘点。**以后任何讨论先读本节。**

### 生产入口只有 1 个：`HydrologyChunkEngine.calculate`（二选一开关）

```
HydrologyChunkEngine.calculate()                          ← 游戏落块的唯一水文入口
  ├─ hydrologyModelPipelineEnabled = true   →  【链 B】model 链
  └─ hydrologyModelPipelineEnabled = false  →  【链 A】legacy 链（当前游戏默认）
```

### 链 A：legacy（当前默认）

| 组件 | 文件 | 状态 |
|---|---|---|
| 河网 + 湖 | `riverline/RiverLineNetwork`（~5000 行）| ✅ 生产在用 |
| 河道骨架来源 | `flow/TerrainFlowSim`（块分辨率流体模拟）| ✅ 生产在用（`flowSkeletonRouting` 默认开）|
| 汇流累积 | `flowaccum/FlowField`（D8 + 降水加权）| ✅ 生产在用 |
| 落块 | `HydrologyBlockCarver.carveChunk` | ✅ 生产在用 |
| 门面 | `HydrologyExperimentEngine` | ✅ 生产在用 |

### 链 B：model（开关 true 时）

| 组件 | 文件 | 状态 |
|---|---|---|
| 窗口管线 | `model/HydrologyPipeline` | ✅ 在用 |
| 区域适配 | `model/ModelHydrologyProvider`（region 缓存 + columnsFor）| ✅ 在用 |
| 河网 | `model/RiverNetwork`（D8，默认）/ `ContinuousRiverNetwork` | ✅ 在用（`-Dgeogenesis.modelRiverModel` 切换）|
| 湖 | `model/EquilibriumWater` + `LakeWaterBalance` | ✅ 在用 |
| 中心线 | `model/RiverCenterlines` | ✅ 在用 |

### 死代码（无生产调用点，勿再接回）

| 组件 | 说明 |
|---|---|
| `model/WaterField` | **零调用点**。闭式水位场接线三版全部实测失败后回退，注释明写"勿重走"。类保留只作历史 |
| `hydrology/sim/` 包 | **main 里不存在**（早期 sim 原型已删）。`infinite-hydrology-rebuild.plan.md` 规划的 `HydroTile*` 新核心**从未落地**，只有 `_archive` 探针残留 |
| `riverline/MidpointDisplacement.generate()` | 死方法；但 `Node`/`ElevationSampler` 是生产类型，**类不能删**（AGENTS.md 既有警告）|

### 结论（一句话）

**生产实际只有 2 套**：legacy / model（开关二选一，**当前游戏跑 legacy**）；
model 链内部有 D8/CONTINUOUS 两个模型变体；`WaterField` 是死代码；**不存在第三套**。

### 当前开关一览（生产语义）

| 开关 | 默认 | 作用 |
|---|---|---|
| `hydrologyModelPipelineEnabled`（config）| **false** | 两链二选一（`run/config/geogenesis-common.toml`）|
| `geogenesis.modelRiverModel`（-D）| d8 | model 链内部 D8 / CONTINUOUS |
| `geogenesis.modelRegion/modelHalo/modelStep`（-D）| 512/96/4 | model 窗口参数（诊断用）|
| `geogenesis.riverThresholdFrac`（-D）| 0 | model 成河阈值面积比（0=关）|
| `geogenesis.sourceSpacing`（-D）| 24 | model 中心线源头间距裁剪（0=关）|
| `geogenesis.inlandResolved` | **true**（代码默认）| model 链内陆窗口可信判据（`f6aa223` 起默认开）|
| `hydro.viaProvider`（-D，探针）| false | 正典图走生产 provider（出图必开，§三）|

---

## 〇c、2026-10-17 世界骨架实验续接状态（覆盖下文过时的当前数字）

- 用户已允许调整源准入与河网分布。`HIER && SKELETON_TRACE && CROSS_PASS` 现消费世界盆体净流：`WorldDrainage` 提供真实侵蚀格心 D8，`WorldBasinRouting` 处理完整平台、采样首割/共同鞍点与下游回水合并，`WorldBasinFlow` 将整盆作为单个扣损节点。三者位于 main，生产实验分支有调用；不是第三条水文链。非分层/其它分支未迁移，游戏配置与实验默认开关未改。
- 水量沿用稳态：唯一格雨源 `P×runoff×面积`，盆蒸发 `E×成员面积`，净出流向下游传一次；不另加直接湖雨、不搬旧窗口 Q。盆内仅发布已准入外部入流到 lip 的支持路径及溢口，不把全盆储水路由树画成鱼骨。世界分支湖面消费同一盆体水量结果，不再由旧 fine/shared 湖面裁决；本区旧 fine 暂仍运行供既有结构/观测使用。
- 窗口仅裁世界边，反对角内部整数列穿 core 也保留。几何采样、盆链、反向水量和缓存均有硬上限，以代码常量为准；耗尽保持 UNKNOWN，provider 拒绝未决区域，不静默判干。几何上限提高有真实大盆/长链证据，但不是无限流域有限查询证明。预览缓存版本以 `PreviewDisplay.CACHE_SCHEMA_VERSION` 为准。
- 当前实际 provider 样本：两个显式 owner 各解一份 fine、shared fine 增量0；跨 z512 的19条共享边两侧同身份，95次整数列检查全部真湿。三个历史原点自身仍均未准入；从新路线达到门限后，真实后继穿两浅盆到海平面以下终点，97/159/91节点、460/460、790/790、440/440次边内整数列真湿。冷独立 gen/provider 逆序两区的完整边集、水量及6个代表列逐位一致。**这是有限两盆样本闭环，不是保留原三源或全球连通证明；SEA 仍采用低于海平面的现行约定。**
- 最新生产正典图：[canonical-panel.png](forge-1.20.1-47.4.10-mdk/build/hierarchical-evaluation/world-water-schema89-final/canonical-panel.png)；seed9139912035078620160、中心(-140,137)、1401²、step1、实际 provider，8281块无失败/无未覆盖。湖155456、河18667、171个河分量；单侧湿代理183/8406，水位跳>2块40、最大5.97块。历史schema87为湖175668/河15792、单侧湿126、水跳121；不能把减少跳变数量宣传为全部改善：单侧湿代理和最大跳变反而增加，南部长直纹仍明显。图仍为 **EXPLORATORY，非整体验收**。
- 同底整体与3处局部对比见 [overall.png](forge-1.20.1-47.4.10-mdk/build/hierarchical-evaluation/g47-final-compare/overall.png)，原图/失败图全部保留。去除顶部50行横幅的像素代理：长竖段≥24块的河像素比例，旧model194838为8.17%、schema87为44.56%、最新37.87%；最长竖段仍337块。湖泊细节/自然形态未验收，不能用无求解失败掩盖退化。
- 本批已验证：编译main/diagnostics，WorldDrainageProbe37、WorldBasinProbe70、SkeletonProbe88、WorldBasinRoutingProbe87、WorldDrainageProductionProbe61条PASS，退出码0。包含平台冷接续、分离双口拒绝、回水合并、闭盆零出流、合成3并发/取消重试/冷热预算、40盆正例与129盆上限负例；完整大图经历8→5→1→0失败区域后才记录无失败。未运行完整WorldgenGate或Minecraft新块验收；旧shape/trace按raw严格下降的审计已不适用于盆内蓄水路由，尚待迁移，不援用G46旧绿作本轮通过证据。
- 可复跑既有任务（MDK目录）：`gradlew.bat runWorldDrainageProbe runWorldBasinProbe runWorldDrainageProductionProbe --offline --console=plain`；新增routing探针目前从diagnostics classpath直跑。原配置SHA256保持 `CE9B6F2CCBC91CCD4B1C769298139F55E6BBA7C0B7806A9E8FF82B69A73B4C6B`。
- **扩展回归仍红**：`g47-provider-det-fine.log` 在既有第3个 owner `(1,-5)` 抛 `world water unresolved nodes=198`，exit1、未产生完整suite摘要；不能据此前两盆/正典图通过宣布完整回归绿。独立 `g47-extra-region-budget-kind.log` 确认198项全部为上游计账格数超32768（主要38328/38340），非深度/鞍点；仅诊断参数改65536时同区824边、unknown0、exit0（`g47-extra-region-65536.log`）。生产预算仍32768未改，预算提升与根本有界求解的取舍待决定；没有静默判干或放宽断言。
- 下一步先解决上述扩展区未决，再做南部长直/平行路由归因、残余水列接缝及湖边分型/连续几何验证、成本与更广世界样本。当前 `terrainSamples=0` 是未采集占位，不是零采样成本；首次冷区块观测61.5秒，未做可接受性能结论。无提交/推送，原dirty源码/图片/debug保留。详细过程见既有build检查点G47。

### 〇d、G48 按需门限接线（2026-10-18，实施中，未完成实机验收）

- main 新增 `DemandFlow`：显式队列展开唯一前驱树，区分 EXACT / AT_LEAST / UNKNOWN；每盆雨源去重、逐盆扣蒸发，下界不填进精确 discharge。几何倍增检查点批量自底向上传播，避免长链逐节点回传的二次方成本；到预算边界再评估已有下界，不能把已有证据丢成 UNKNOWN。工作集/逻辑格仍上限32768，不提升生产流量预算。
- 用户明确选择修统一物理边：普通下降/平坡/盆成员采用同一整数列 crest。不能只检查 raw 最陡边被挡后就停；要在全部8邻里选可通下降。保留 raw 诊断对照与稳态公式。真实隐藏 lip 的 raw 成员双重身份已复现并修复，属于获批的局部拓扑变化，不声称纯性能等价。预览 schema91，策略身份含 demand policy。
- `g48-integrated-regression.log` 编译 main/diagnostics/acceptance、DemandFlow19项、routing92项、自检及正式 build 通过；`det,fine,shape-audit,trace-loops,rolling-window` 完整96项PASS、0FAIL。原〇c的扩展区198未决红灯被本轮覆盖：同一 `(1,-5)` 在生产32768预算下833边、无未决异常。五owner世界审计各16900条Water全量known、独立盆身份未决0，低于T的EXACT允许干/低流量，仅非exact证书和发布边要求达T；另有8条审计负控。
- `g48-final-production.log` / `world-drainage-production/1792271369529` 两盆61项PASS：1559/1027边、19共享边、95整数列全湿；完整当前生命周期仍97/159/91节点、460/790/440次水列全湿；冷独立逆序边/水量/6代表列逐位相同。不是全球连通证明或生产三并发证明。
- 新正典图：[canonical-panel.png](forge-1.20.1-47.4.10-mdk/build/hierarchical-evaluation/world-water-schema91/canonical-panel.png)，1401²、step1、同seed/中心/TOML、实际provider，8281块0失败0未覆盖，strict保持默认true。湖155737、河18631、179河分量；单侧湿193（前版183，变差），水跳>2为36/max5.97。南部长直纹仍明显，EXPLORATORY而非整体验收。总212.7s/最慢冷chunk54.36s只是本次观测，不能与不同负载G47直接推导提速。
- 正式jar已构建：新main核心类在包内、acceptance/diagnostics未混入，字节码major61（Java17）。隔离 `run-accept` TOML副本显式开model/确定性骨架/WGP，原run配置SHA256仍为〇c所列原哈希，旧存档未改。自定义Forge run需在userdev元数据填充后merge客户端启动信息；已真实启动两次新世界，均36目标邻块FULL且保存完成。第二次6点水柱/干列匹配，西/东lip各5列及跨z512的6列均计划窗口内真水8连通；原首次假红由水面树叶遮住湿掩码早退，第二次报告有逐块oak/jungle leaves证据。第二次总报告仍FAIL：覆盖扫描解反ChunkPos打包坐标，跑了未加载的转置块，正在修验收工具；原失败报告/WGP保存在 `g48-forge-first` / `g48-forge-second`，不冒充总验收通过。
- 第三实机（新世界1792279830741）修正ChunkPos覆盖坐标后仍**真实FAIL/exit1**：36邻块FULL、6点匹配、3条显式link通过；9216列中181计划湿列只匹配180，唯一缺水(-588,580)、计划y64。NBT副本位级解析与跨region正控确认y63沙/y64空气/y65树叶，不是含水植物误排。前两新世界同点分别水(level1)/空气，说明实际落块存在跨次差异；不能把之前6点相同的格式化三位小数报告当逐位全世界确定性。原因尚未定位，不先猜改流动水或过滤装饰。第四次仅加验收开关下该列NOISE/CARVERS/FEATURES观察，新世界1792281371588局部验收PASS/exit0，181/181匹配；各阶段该点均water(level1)，仅上方出现树叶，未复现缺水。**一次新绿不能推翻前两次真实缺水**，没有行为修复，不得称缺水已解决。阶段观察保留；原件/副本哈希见 `g48-block-forensics`，四份世界保留且已保存。
- WGP第三实机1293块：HYCALC均197.83ms/P50 10.26/P95 19.17/max48009.69；最慢fillFromNoise29.52s；首轮39.40s。各阶段有包含关系，不能把占比简单相加。运行中采集heap约1.46GiB used、进程private约3.37GiB只是时点而非峰值，更不等于region缓存实际保留字节；性能仍未达成可接受结论。截图/逆序实机仍缺。
- **多seed新红灯，不能宣称完整生产回归完成**：seed12345、中心(-140,137)、r64，owner(-1,0)1508项UNKNOWN（两独立运行同图）。有界归因全部 `downstream MULTIPLE_EXITS`，0预算原因；追到窗外(44,416)十成员盆，两首割face共享整数crest(38,418)、sill精确相同218.05494223960642，但外侧terminal不同，现行singleOutlet拒绝并传播未决。此为现行鞍点/唯一后继契约未覆盖反例，不证明连续真实地形必须双分流，不可任意按坐标挑口或提高预算掩盖。扩展原seed小图121块0失败，但单侧湿28/129仍高；重复PNG逐字节相同。证据见 `g48-samples/rootcause-A.txt`，旧归因中间结论保留并以后续证据修订。计划完成边界尚不满足。
- 同底像素对比：[g48-overall.png](forge-1.20.1-47.4.10-mdk/build/hierarchical-evaluation/g48-compare/g48-overall.png)。去横幅后1038像素变动均在水体，灰底/海逐字节不变；两盆与南部长直窗口差异0，最长竖段仍337，长竖河比例37.87%→38.05%，不称形态改善。无自动提交推送。

### 〇e、G49 共享整数鞍点契约（实施中，尚未完成）

- `WorldBasinRouting` 用不可变 Outlet 裁决替代布尔单口：全部首割 Face 的最高整数列全交集必须唯一，再按首个外向整数邻列的高差/实际距离严格唯一最大选 Face；严格平局、分离鞍点仍 UNKNOWN，同 outer 保留既有语义。不依赖远处 terminal 高低、不加 epsilon 或坐标破平局。获选 Face 与旧 minimax 不同时，在原 WorldBasin 采样预算内重建完整见证；publish 与首接收盆消费同一裁决。policy4 / schema92，原默认及 legacy 未改。
- 首轮 main/diagnostics 编译与 DemandFlow19、WorldDrainage37、WorldBasin70、routing（含新增共享鞍点/旧path反选/负平移/三实例并发）全部通过，见 `g49-rule-regression.log`。补测全交集/多列平台/首邻零下降/中间屏障、同Unit扣损与选定outer仅接净流一次、非选outer无分流、support闭包及更低见证不得跳过首接收盆。独立javac/java整份探针0FAIL；四个build-only变异（删全交集、重用旧path、跳首邻、跳首接收盆）均exit1，且对应断言明确红。变异不改生产文件，原始“删交集未检出”日志保留。完整Gradle最终main/diagnostics/acceptance编译、Demand/Drainage/Basin/routing与验收自检及build均exit0（`g49-final-static-build.log`）；正式jar SHA256 `73cc8bb74d03e6794b1e853e3f3f3204df984bfdb3f54c82c0e4abc07d8ec356`、1271635bytes，新main在包、major61、acceptance/Probe/取证agent不在包。实机仍红，不能据此关闭原两项任务。
- 真实 seed12345、十格盆(44,416)：共同列(38,418)，所选 Face=(40,420)→(36,416)，局部下降率0.19152424335356996；首接收盆(16,420)。原 flow32768 / geometry131072 下 owner(-1,0)全16900 Water 无 UNKNOWN、669边。独立新 JVM 实际 provider 的同位置 r64 小图121块0失败/0未覆盖、湖60/河57（EXPLORATORY）。证据 `g49-real-saddle.log` / `g49-saddle/panel-A.metrics.txt`，历史1508未知证据保留。
- 既有两盆生产套件本轮通过：1559/1027边、19共享边、95整数列全湿、三生命周期460/790/440全湿，冷逆序边/Water/6代表列逐位相同，见 `world-drainage-production/1792284358773/metrics.txt`。扩展原seed小图湖1259/河256，与前版PNG逐字节同，单侧湿28/129仍未改善。完整det/fine/shape/trace/rolling全部通过（96 PASS/0 FAIL，`g49-provider-regression.log`）。schema92正典图8281块0失败/0未覆盖，湖155737/河18631、193单侧湿、36处>2块水跳/max5.97均与schema91相同；去顶部50px横幅后0个RGB像素改变，南部长直纹完全保留，不称形态改善。并行重负载下321.1s/max66.27s不是可比成本收益。seed12345两新JVM PNG逐字节相同。
- 实机只增观测，未修水行为：补目标邻区 FEATURES 前后读取同一列、Chunk Load、tick START/END变化、FULL与读回；增加FULL后200tick观察及独立新世界逆序加载选项。新世界1792284942973真实FAIL/exit1，36FULL/181计划湿仅173匹配（含西lip5列、(-620,612)、(-619,611)、(-588,580)）。目标列NOISE/CARVERS/九邻FEATURES/Chunk Load tick26均water(level1)，tick36 END首次air，ALL_FULL tick38与READBACK tick239仍air。已捕获运行期转换，尚未确认具体写入调用者，禁止先改水行为。世界保存完成，报告/WGP/log保留于 `g49-forge-observe`。build-only Java agent仅对LevelChunk写入加打印栈，第一次1792286448997目标水未消失但总体仍FAIL，已保存于 `g49-forge-write-1`；扩大失败邻域y64后，新世界1792286704726再次真实FAIL并保存完成：西lip五列从level1衰减至level7、最后air，Java agent实际写入栈逐列确认调用者为原版 `FlowingFluid.tick(FlowingFluid.java:408)`；不是植被遮挡或工具错判。调用者证据 `g49-forensics/confirmed-runtime-writes.txt`，报告/WGP/log/NBT原世界保留于 `g49-forge-write-area` 及原saves。本次目标(-588,580)仍water，因此该点只有先前tick36转换证据、尚无同点调用栈，不能直接断言与西lip同因；另外新缺水(-628,712..714)y63未被本次y64 agent覆盖。逆序新世界1792287406528同样真实FAIL/exit1且保存完成：36FULL、6点/3link全过，但181计划湿只175匹配；西lip五列这次保住，缺水改为(-644,688)、(-640,692)、(-639,693)、(-637,695)、(-624,616)、(-623,615)，其中后五列有 `FlowingFluid.tick:408` 实际air写入栈（-644点未捕到）。目标(-588,580)仍water。日志/报告/WGP保留 `g49-forge-reverse`，区域y63..64取证agent未改方块或tick策略；逆序改变缺水位置，不能只抽6点判绿。运行内存采样曾heap约0.96GiB、private约3.36GiB，进程峰值工作集约2.59GiB只是所读时点峰值，不是缓存retained bytes或可接受性能结论。正式水行为尚未修改：已提出model普通水面稳定源水（legacy/瀑布不动）与保留流动并设计供水两种路线，询问超时未获用户选择；不把超时当授权，禁无差别补水/整类过滤。原配置哈希不变，无提交/推送。

### 〇f、G50 稳定水面试验与提交检查点（2026-10-18，未验收）

- 用户已明确同意试“仅model普通计划水面稳定源水，legacy和瀑布水幕不变”。落块从同一 `HydrologyChunkEngine.usesModelPipeline` 实例读取有效分支，不重新按系统属性猜配置；水柱普通段model用源水，y>普通水面的瀑布段仍流动水。schema93，几何/路由policy4不变。
- `g50-static.log`：main/diagnostics/acceptance编译、验收自检（含全水柱中空负控）、routing121项与build通过。真实Forge同时反射调用生产水状态选择，model/legacy/瀑布六种组合通过。
- 首轮新世界1792302099170、36邻块FULL、6点与3link通过、181/181计划湿列全部匹配且普通非源水0；**总体仍FAIL/exit1**：全列审计在ALL_FULL后200tick观察期检出18个新增计划外水格（总计划外26），位于扩展chunk附近(556..557, y6..10, -2385..-2380)地下。尚未与修改前相同全列审计独立对照，不能断言18格全由本次源水试验导致；扩散风险未排除。7个其它计划外源水格在ALL_FULL时已存在，不能据此声称无生成期扩散。报告/CSV/WGP/log保留 `build/hierarchical-evaluation/g50-forge-source-forward`，原世界保存完成，原run TOML哈希不变。
- 用户新增截图的湖岸锯齿、贴岸河、长直/直角路线正在只读生产路径归因；没有实施噪声扰动、PNG平滑或路由架构改写，也不把“有动量”当未经验证的通用修复。稳定水面正逆序/扩散归因与形态验收尚未完成。本次按用户要求保存代码检查点，不表示可发布；历史图片、日志、build和存档不纳入Git提交，不推送。

## 一、当前状态（历史快照；新实验状态见〇c/〇d/〇e/〇f）

| 项 | 值 |
|---|---|
| 工作区 | `D:\Office software\Development Project\GeoGenesis-mod`（仓库根） |
| 分支 | `fix/port-lake-p2-2` |
| HEAD | `7eb8748`（之上有少量未提交改动，见 §六） |
| 生产水文入口 | `HydrologyChunkEngine.calculate` → 二选一（见 §二 / §〇b） |
| 未推送 | 是 |

⚠ **`_model_wt/` worktree 已不再是独立代码线** —— model 链已于 `848fdc1` 移入 `src/main`，
现由开关切换。旧文档里"两条并行代码线"的描述**已失效**。

---

## 二、生产有两条链（由开关二选一）

> 组件级明细见 **§〇b 系统总账**（权威盘点）；本节只讲两链的语义对照。

```
HydrologyChunkEngine.calculate()
  ├─ hydrologyModelPipelineEnabled = true  →  model 链（ModelHydrologyProvider）
  └─ hydrologyModelPipelineEnabled = false →  legacy 链（HydrologyBlockCarver）   ← 当前默认
```

| | legacy 链 | model 链 |
|---|---|---|
| 入口 | `HydrologyBlockCarver.carveChunk` | `ModelHydrologyProvider.columnsFor` |
| 河网 | `riverline/RiverLineNetwork`（region 640wu，D8 追踪 + 跨区续流 `OutletSeed`）| `model/HydrologyPipeline`（窗口 + 多 region 拼接）|
| 湖 | `RiverLineRegion.LakeNode` + `spill` 溢出高程 | `EquilibriumWater`（priority-flood）+ `LakeWaterBalance`（水量平衡）|
| 定位 | **被 model 链取代的旧版** | ★ **升级版（目标）** —— 见 §〇③ |
| 默认 | ✅（现状，**但这是待改的**）| ❌ |

⚠ **不要把"当前默认是 legacy"误读为"legacy 是正确目标"** ——
用户的定位是 **model 取代 legacy**（§〇③），legacy 仅为现网跑着的旧版。

**model 链内部还有两个模型**（`HydrologyPipeline.RiverModel`）：

| | D8（默认）| CONTINUOUS |
|---|---|---|
| 算法 | priority-flood + D8 拓扑累积，**确定性** | 蒙特卡洛流线积分（5 万条随机起点）|
| 耗时 | 快 ~3× | 慢 |
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
|---|---|---|
| `radius` | **700** | ⇒ 1401×1401 |
| `step` | **1** | 1 像素 = 1 方块 |
| `seed` | `9139912035078620160` | |
| 中心 | `(-140, 137)` | |
| 底图 | 无光照等高线 | **hillshade 会把谷画成脊**（已误判两次）|
| 色标 | 固定（`ViewSpec.BAND_BASE/BAND_BLOCKS`）| 跨图可比 |

**唯一来源**：`diagnostics/.../model/ViewSpec.java`。
`ViewSpec.assertCanonicalRadius` 会硬断言 —— 传错半径的图**不可与正典图互相印证**。

---

## 五、参考项目：四家一致的答案（★ 重要）

`参考/river/` 下四个项目**全部不需要跨区水量传递**，做法一致：

| 项目 | 单元尺寸 | 保证"任意位置单独查询都连续"的机制 | 跨区传水 |
|---|---|---|---|
| **FTF** | ~12000 格 | 边界按构造落在**海里**（edge 函数边框恒 0）| **无** |
| **PL-RGA** | ~6667 单位 | `border_safe`/`lake_safe`：河/湖被挡在离边界 margin 外 | **无** |
| **DW** | **640 格（与我们同）** | **共享锚点**：本区汇点 = 邻区源点（同种子公式 ⇒ 逐位相同）| **无** |
| **Streams** | 8×8 区块 | 接口水位 = **海平面常数** + 固定截面表；下游**复制**上游截面 | **无** |

**共同点**：
1. **水位是【局部量】**（位置纯函数 / 出口高 + 固定比降 / 常数），**不是流域累积量**
2. **单元尺寸 ≥ 流域尺度**（否则边界落在陆地上 ⇒ 照抄会直接得到"边界断河"）

### 核实状态（2026-10-09，逐条回源码验证过）

| 断言 | 证据 |
|---|---|
| DW `REGION_SIZE=640` | `HydrologyManager.txt:3`（山地河 `:9` = 256）|
| **DW 零汇流累积** | 全仓搜 `accum\|discharge\|catchment\|watershed` ⇒ **命中 0 文件** |
| PL-RGA `border_safe`/`lake_safe` | `flat_infinite/src/terrain.cpp:319-320`，`border_margin = border_margin × plate_scale` |
| PL-RGA 河道不离 plate | `config.hpp:14` 注释原文 "so river channels never leave their plate"；`river_border_distance=100`（`:63`）、`river_lake_radius=150`（`:65`）|
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

## 六、2026-10-07~09 本轮改动

> 状态更新（2026-10-09 晚）：`f6aa223`/`7eb8748` 已提交；**meander 噪声实验已全部撤销**
> （`git checkout`，未提交）；未提交改动仅剩「断点归因诊断」（见 §六.3）。

### 已修的真 bug（已提交）

| # | bug | 根因 | 效果 |
|---|---|---|---|
| 1 | **内陆 region 全域失效** ⇒ 湖整块消失 | `EquilibriumWater:77` 的 `anyTrueDrain &&` ⇒ 窗口无海时 `resolved` 全 false；下游 `LakeWaterBalance:262` 要求 `resolved` | `region(0,-1)` basins **0→54**、lake **0→2699** |
| 2 | **region 缝上断半截** | `columnsFor` 只用本区，不查邻区 | 湖-竖缝断开 **44%→12%**、河-横缝 **58%→37%** |
| 3 | 我的工具判据用错 | 用 `erosion()>0` 判河（应用 `fillWater`）| 已改 |
| 4 | 湖判定方块化 | 粗格布尔按最近格点摊派 | 已改逐块连续判据 |
| 5 | 每 region 求解两次 | 实例级缓存 | 改 static 共享 + 按 seed 键控 |
| 6 | 河穿过湖面 | `RiverNetwork` 成河循环不排除湖面 | 加 `lakeMask` 排除（`f6aa223`）|

### ★ 2026-10-09 深夜：河"断流"的量化归因（**用户判据驱动，未修复**）

**用户判据**：「很多河流被截断了」「河在平坦坡上突然消失」「是真的有断的地方，不是视觉问题」。

**新增诊断** `-Dgeogenesis.riverBreakDiag=true`（`ModelHydrologyProvider`，方块级归因，
`ProductionViewProbe` 打印 `riverBreakReport()`）—— 适配层两道门分开数：

```
【小窗 r=300 实测】
onChannel=true  总格 = 31,336
 ├ 出水 √（surface > carved + 0.5）= 31,018 (99.0%)
 └ ★有河却没水 ✗                   =    318 (1.0%)  ← 断因 ①
距离场命中但 onChannel=false        =  2,499 (8%)    ← 断因 ②（粗格布尔误杀）
```

- **断因 ①「有河却没水」**：断点样本的 `surface − carved` 全部落在 **0.18~0.50**
  （刚好卡在 0.5 门槛下）⇒ `depth=1.0` 写死，平坦坡水位落差小 ⇒ 水柱不足 0.5 格
  ⇒ 不放水块 ⇒ 河断。候选修法（**未实施，待用户确认**）：河床下挖深度按水位差自适应
  （保证水面比床面高 ≥0.5）。
- **断因 ②「粗格误杀」**：`columnsFor` 的落块门控是 `p.channel()[k]`（STEP=4 粗格布尔），
  距离场（`SegmentIndex`，逐块精确）命中却被粗格判掉。粗格栅格化的像素级铁证：
  河段起点 `x mod 4 = 2` 占比 **97.7%**（参照图 step=1 均匀分布 34%）。
  候选修法（**未实施，待用户确认**）：粗格只算拓扑，落块用逐块距离场。

**⇒ 两个断因均已定量，修复方案待用户指挥后实施。**

### ⚠ meander 噪声实验（已撤销，勿重试同款参数）

移植 worldgen-master `add_meander_noise`（amp=6）实测：主干最大连通分量 **12,636 → 3,931**
（被切短 3 倍）⇒ 用户："肯定被你弄坏了"。已 `git checkout` 撤销。
**教训**：在 priority-flood 前改床面的做法在我方地形上会切碎主干；若再试必须先过
"连通性不回归"判据。

### 新增诊断旋钮（默认 = 生产原值 ⇒ 零行为变更）

```
-Dgeogenesis.modelRegion=512           region 边长
-Dgeogenesis.modelHalo=96              保护带（★ 探索中：照侵蚀比例应 ≥512）
-Dgeogenesis.modelStep=4               网格步长
-Dgeogenesis.riverThresholdFrac=0      成河阈值面积比例
-Dgeogenesis.inlandResolved=false      内陆窗口可信判据
-Dgeogenesis.localLakeLevel=false      湖水位用 sill（局部量）
-Dgeogenesis.modelRiverModel=d8        证据场模型
-Dhydro.viaProvider=true               正典图走生产 provider
```

### 新增工具

| 工具 | 用途 |
|---|---|
| `runProductionViewProbe` | 走 provider 出图（诊断 model 链，可调 region/step）|
| `runProductionChainCompareProbe` | legacy vs model **同规格并排**（走游戏同一入口）|
| `CanonicalViewProbe -Dhydro.viaProvider=true` | **正典规格图走生产路径**（= 游戏会落的水）|

### ★★ 量化验收基线（2026-10-09，正典规格 1401×1401 逐像素）

| | 参照图 | 生产（`inlandResolved=true`）| **覆盖率** | **精确率** |
|---|---|---|---|---|
| **湖** | 69,530 | 103,128 | **93%** | **63%** |
| **河** | 38,502 | 90,375 | 43% | **19%** |

**判读**：
- **湖的位置已高度吻合**（覆盖率 93%）⇒ "该有湖的地方都有湖"**已达成**；
  精确率 63% ⇒ 多出约 3.4 万格（判据偏宽）。
- **河道精确率 19%** ⇒ 生产河道有 **81% 在参照图里不是河**。

### ⚠ 河道的两类伪影（2026-10-09 出图确认，**当前最大问题**）

正典规格图显示：**北半部河道形态正确**（细、树状分叉、汇流成网），
但**南半部**有两类伪影：

| # | 伪影 | 现象 | 根因 |
|---|---|---|---|
| A | **等距平行竖直长线** | 海岸平原上，河道从内陆直插海里、间距均匀 | **D8 在平坦区只能从 8 个离散方向选** ⇒ 相邻列各自独立选向 ⇒ 平行线（与旧文档 §17.6「平坦区流向欠定」同源）|
| B | **矩形结构** | 左下角出现直角矩形 + 内部直线 | **region 边界（512 块）处判定不连续** |

**★ 两者同源**：骨架由「**窗口内** D8 汇流累积」派生
⇒ ① 汇流累积是窗口局部的 ⇒ 边界不连续；② D8 在平坦区退化 ⇒ 平行线。

**⇒ 正解 = 用户的既定方向（§〇②）**：改为**确定性骨架**（与窗口无关），
参考 **DW 的共享锚点**（本区汇点 = 邻区源点，同种子公式 ⇒ 逐位相同）
—— 既不依赖汇流累积，也不受平坦区影响。

### 已修：最小河宽与网格步长解耦（`c275394`）

```
RiverCenterlines L352: half = max(half, 0.5 * cellSize)      // cellSize = STEP
RiverCenterlines L496: max(cellSize, halfCells * cellSize)
```
⇒ `STEP=4` 时**最小全宽 4 块**（参照图 1~3 块）⇒ **河道宽度由地板而非水力几何支配**。

实测（`-Dgeogenesis.riverWidthDiag=true`）：

| step | half∈[0,1) | half∈[1,2) | half∈[2,3) |
|---|---|---|---|
| 4（修复前）| 0% | 0% | **90.3%** |
| 4（修复后）| **10.9%** | **45.6%** | 29.6% |
| 1 | 14.3% | 74.5% | 10.5% |

修法：地板改常量 `MIN_HALF_BLOCKS = 0.5`（游戏里水的最小单位恒为 1 块，与步长无关）。

## 六b、★ 2026-10-10：确定性骨架 + 三项形态修复（**用户判据驱动，已落地**）

> 本节是 `DeterministicSkeleton` 的当前事实。开关 `-Dgeogenesis.skeletonTrace=true`
> （骨架模式，model 链内二选一）+ `-Dgeogenesis.skeletonCrossPass`（双 pass 续流，默认开）。

### 用户判据（三轮迭代）

1. 「河流必须确定性骨架」（§〇②）→ `DeterministicSkeleton` 落地；
2. 「很多短碎河流 / 一小部分截断 / 反复交叉重叠」→ 三项修复（见下）；
3. 「两湖太近导致河短」→ 溢口门限覆盖大半；「多段反复缠绕是垂直精度太高导致抖动」
   → **谷底取小采样**（见下，用户的"垂直精度应对应独立单位"判断核实属实）。

### 三项修复与量化（正典 r=700 同参 A/B）

| # | 修复 | 根因 | 效果（用户截图版 → 现在） |
|---|---|---|---|
| 1 | **溢口起链加成河门限** | `basinOutlet` 无条件起链：湖占 5.24% ⇒ 每个盆地溢口都吐一条河。实测铁证 `[HYDRO-SRC] sources=0 traces=48`（源头筛光但河还在=全来自溢口）。**legacy 没有这一步**（河道全部来自流量掩码，湖只是终止条件） | 碎点≤5格 23→**6**（−74%）；短段≤20格 45→**10**（−78%） |
| 2 | **成河门限改绝对集水** | 旧 `n²×0.003` 是"占窗口比例"：legacy 窗口 1536 block ⇒ 7078 block²；我方 708 block ⇒ 1504 block² = **legacy 的 1/4.7** ⇒ 遍地成河。新口径 `chanMinCells×runoff`（discharge 单位 = 上游格数×runoff，实测 runoff=0.0192 校准） | 河格 1.16%→**0.85%**（参照 1.30%） |
| 3 | **谷底取小采样**（用户垂直精度判断） | bed 单点采样 ⇒ MC block 级垂直噪声泄漏进 D8"谁更低"判定 ⇒ 坡面流向逐格抖动=锯齿缠绕。legacy `buildSkeletonRivers` 2026-09-22 已做（4 角最小值）。实现于 `WindowOutlets.build`（`-Dgeogenesis.valleyMin=false` 退回） | **中位分量 37→111（×3）**；真断口 34→**8**（−76%） |

另补齐 legacy 三件套：`minNodes=6`（legacy `minRiverNodes=3`×尺度2）、
`selfApproach` 半径 4 豁免 12（legacy 2/6×尺度2）、**交叉守卫** `segmentCrossesAny`
（legacy `nearbyDownhillNode` 里 `_wouldCrossExistingSegments`，我此前漏掉）。

### 尺度换算表（★ 勿再推错）

| 项 | legacy（`SKELETON_CELL_BLOCKS=8`）| 我方（`STEP=4`）| 换算 |
|---|---|---|---|
| 格距 | 8 block | 4 block | 我方 1 格 = legacy 半格 ⇒ legacy 格数阈值 ×2 |
| 成河门限 | 110 格 | `chanMinCells=150` | 按实测 runoff 校准，非比例折算 |
| `minRiverNodes` | 3 格 | 6 格 | ×2 |
| `SELF_APPROACH_RADIUS` | 2 格 | 4 格 | ×2 |

### ⚠ 我在本轮犯的错（防重犯）

1. **`maxRivers=27` 亲手砍断主干**：凭"每 1000 block 折算 legacy 80"设了上限 ⇒
   河道 −38%、最大连通分量 4252→1823。单变量 A/B 证否后回退默认 0。
   **教训：legacy 的 `riverCount` 是"尝试数"且其 region 大我方 9 倍，数字不可移植。**
2. **门限单位连错两次**：先当 block² 写 7078（>maxQ 234 ⇒ `sources=0`），
   再除 STEP²（门限过激 ⇒ 多 region traces=0）。最后靠实测分布
   `[discharge] max=234 P99=57 runoff=0.0192` 校准。**教训：单位先实测再推算。**
3. **`minNodes` 丢弃链时整条释放 `covered`** ⇒ 把别的链的 JOIN 终点（别人内部节点）
   也释放 ⇒ 后继链穿过 ⇒ `SkeletonProbe [4]` 双占 FAIL。改为只释放本链新占格。
4. **跨区续流 v1 方向场接不上**（发送方窗外地形最陡下降 ≠ 接收方 minimax 树）
   ⇒ 缝上断口 5→9 反增。改为 legacy 双 pass：pass-1 各区独立产 `ExitSeed`，
   pass-2 由接收方用自己的 parent 追（`collectIncoming`）⇒ 缝上断口 5→1。

### 残余（如实，2026-10-10 用户目检"还行"后又圈出两处 → 当晚已修，见 §六c）

1. ~~陡坡紧密阶梯（梯子纹）~~ → §六c 有界粒子已修
2. ~~平地径向直线~~ → §六c 平地破平局已修（但见其"局限"）
3. **真十字交叉 11 个 / 2×2 重叠 48 个** —— 量小，未处理。
4. **最大分量 2988~3448** vs 参照 12,636 —— 主干仍偏短，待用户目检后定方向。

## 六c、★ 2026-10-10 深夜：用户圈出两处"不可能是伪影" → 定性+修复

> 用户原话：「不可能是伪影啊，我看图明显有这问题啊」（圈了梯子纹 + 两条笔直斜线）。
> 我此前把这两处记为"观感可接受"是**误判**，本节更正。

### 定性（非猜测，逐条归因）

| 现象 | 定性 | 证据 |
|---|---|---|
| **梯子纹**（缓坡河呈"横档+小竖档"） | 锚点钉死 4-block 格心 + D8 缓坡走"横 3~4 格降 1 格" ⇒ CR 重采样磨圆但档距还在 | 放大图 + 直线占比 33% |
| **笔直斜线横穿河网** | ① minimax 平地 parent=弹出序≈指向最近种子的径向直线（`EquilibriumWater:244` 自述）；② 左上角那几条**是地形冲沟**（用户已三次申明"平行线=地形"，不是河） | §17.6 + 放大图 |

### 修复（两个独立机制，均可单独开关）

| # | 机制 | 实现 | 量化（正典 r=700） |
|---|---|---|---|
| 1 | **平地破平局**（追踪侧，§17.6 既定方案） | `traceOne` 里若 `level[p]==level[k]`（等水位区内部），改选 **bed 最低的等水位邻居**（真实地形微坡），bed 也全等才退回 parent。单调性不破坏（level 非升）。`bed=null` 关闭 | 直线占比 33%→31%（**见下方"局限"**）；所有干净指标持平（断口 8、十字 12、双线 3） |
| 2 | **有界粒子锚点**（legacy `PARTICLE_POS` 同构） | `shape()` 里锚点沿 −∇bed 的正交分量偏移出格心（幅度 `PARTICLE_AMP_FRAC·step·|叉积|`，平行不偏、正交最大）。治的是"档距"本身 | **直线占比 33%→15.7%（−52%）**、水平长直段 43→20 段；梯子纹目视消失（三联图中图 vs 右图） |

### ★ 有界粒子的代价（如实，未消除只能折中）

粒子让锚点偏出格心 ⇒ 相邻河段在像素上贴近 ⇒ **贴线统计恶化**：

| 配置 | 真+字 | 2×2重叠 | 并排双线 | 直线占比 |
|---|---|---|---|---|
| 无粒子（基线） | 11 | 48 | 3 | 32.9% |
| 粒子 0.45（legacy 值） | 48 | 159 | 55 | 12.2% |
| **粒子 0.25（默认）** | 30 | 143 | 36 | **15.7%** |

- **为什么 0.45 不行**：我方格距 4 block（legacy 8），同样相对偏移对平行河视觉距离影响翻倍
  ⇒ legacy 值不可照抄（又一次刻舟求剑）。
- **为什么仍保留 0.25**：梯子纹是用户圈出的视觉缺陷；贴线统计涨但图上河线仍分离
  （三联图目检）。幅度可用 `-Dgeogenesis.particleAmp` 调（0=关）。

### ⚠ 平地破平局的局限（重要，勿误以为已修完）

破平局只在 `level[p]==level[k]`（**严格等水位**）时触发。实测直线占比只降 2 个百分点
⇒ 径向直线主要发生在**缓坡**（level 微差 > 1e-9，判据不触发，parent 仍是弹出序产物）。
真正把直线消掉的是**有界粒子**（几何层）。若日后残余直线仍可见，
候选修法 = 等水位判据放宽为 `|level[p]−level[k]| < ε_slope`（ε 待标定，可能伤单调性）。

### 验证

`runSkeletonProbe` / `runHydrologyDeterminismProbe` / `runLifecycleChainProbe` 全部 ALL PASS。
提交 `1a75aca`（三项形态修复）之上追加本轮（未提交时见工作区）。

### 新增诊断旋钮（骨架）

```
-Dgeogenesis.skeletonTrace=true         骨架模式开关（model 链内二选一）
-Dgeogenesis.skeletonCrossPass=false    双 pass 续流开关（默认开）
-Dgeogenesis.channelMinCells=150        成河门限（绝对集水，格）
-Dgeogenesis.valleyMin=false            谷底取小采样开关（默认开）
-Dgeogenesis.skeletonMinNodes=6         长度下限（格）
-Dgeogenesis.skeletonSelfRadius=4       自贴近半径（格）
-Dgeogenesis.skeletonSelfExempt=12      自贴近豁免步数
-Dgeogenesis.logSrc=true                打印 srcAccum/runoff/maxQ/traces 归因
```

### 未解决（如实）

1. **★ 河道确定性骨架**（§〇② 的核心目标）—— **已开工并落地**（本节），残余见 §六b
2. **R1 已修但未验收**：`-Dhydro.viaProvider=true` 可用，但尚未成为默认
3. **湖精确率 63%**：位置对（93%），但偏宽（多 3.4 万格）
4. **`HALO` 取值未定**：照侵蚀比例应为 `HALO ≥ REGION`，但实测湖量随之变化
5. **STEP=4 方块化**：图上是直角台阶
6. **`WaterField`（闭式水位场）已实测否决** —— 它是 legacy 链 `WaterLevelSolver`
   的"水位数据源"，而 model 链的湖是**盆地机制**（需 sill/rim/resolved）
   ⇒ 概念错位。类保留但无调用点（见 §六）
7. **门禁**：`runWorldgenGate` **ALL PASS**（12 探针）✅
   —— 但**新旋钮的默认值未变**，故门禁覆盖不到它们的效果

---

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
