# 水文系统 —— 单一事实来源（2026-10-09 重建）

> **本文件替代根目录原有 12 份水文文档**（已归档至 `_archive/hydrology/`）。
> 归档原因：那些文档彼此矛盾、多数已过时（详见 `_archive/hydrology/README.md`），
> 继续放在根目录会误导接手者 —— 本项目已因此连续出错多轮。
>
> **2026-10-26冷启动入口：** [最新交接](handoff/GeoGenesis-handoff-20261026.md)、本文件§〇bf及实施续记§〇bg—〇bj（宽度最新状态）。§〇b保留的是2026-10-09盘点，里面“两条生产链/legacy默认”等说法已过时；生产现状以§二（仅model）及当前调用点为准。§〇az—〇bd中的旧归因已由§〇bf明确撤回。

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
| --- | --- | --- |
| 河网 + 湖 | `riverline/RiverLineNetwork`（\~5000 行） | ✅ 生产在用 |
| 河道骨架来源 | `flow/TerrainFlowSim`（块分辨率流体模拟） | ✅ 生产在用（`flowSkeletonRouting` 默认开） |
| 汇流累积 | `flowaccum/FlowField`（D8 + 降水加权） | ✅ 生产在用 |
| 落块 | `HydrologyBlockCarver.carveChunk` | ✅ 生产在用 |
| 门面 | `HydrologyExperimentEngine` | ✅ 生产在用 |

### 链 B：model（开关 true 时）

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

**生产实际只有 2 套**：legacy / model（开关二选一，**当前游戏跑 legacy**）；
model 链内部有 D8/CONTINUOUS 两个模型变体；`WaterField` 是死代码；**不存在第三套**。

### 当前开关一览（生产语义）

| 开关 | 默认 | 作用 |
| --- | --- | --- |
| `hydrologyModelPipelineEnabled`（config） | **false** | 两链二选一（`run/config/geogenesis-common.toml`） |
| `geogenesis.modelRiverModel`（-D） | d8 | model 链内部 D8 / CONTINUOUS |
| `geogenesis.modelRegion/modelHalo/modelStep`（-D） | 512/96/4 | model 窗口参数（诊断用） |
| `geogenesis.riverThresholdFrac`（-D） | 0 | model 成河阈值面积比（0=关） |
| `geogenesis.sourceSpacing`（-D） | 24 | model 中心线源头间距裁剪（0=关） |
| `geogenesis.inlandResolved` | **true**（代码默认） | model 链内陆窗口可信判据（`f6aa223` 起默认开） |
| `hydro.viaProvider`（-D，探针） | false | 正典图走生产 provider（出图必开，§三） |

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

- main 新增 `DemandFlow`：显式队列展开唯一前驱树，区分 EXACT / AT\_LEAST / UNKNOWN；每盆雨源去重、逐盆扣蒸发，下界不填进精确 discharge。几何倍增检查点批量自底向上传播，避免长链逐节点回传的二次方成本；到预算边界再评估已有下界，不能把已有证据丢成 UNKNOWN。工作集/逻辑格仍上限32768，不提升生产流量预算。
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
- 实机只增观测，未修水行为：补目标邻区 FEATURES 前后读取同一列、Chunk Load、tick START/END变化、FULL与读回；增加FULL后200tick观察及独立新世界逆序加载选项。新世界1792284942973真实FAIL/exit1，36FULL/181计划湿仅173匹配（含西lip5列、(-620,612)、(-619,611)、(-588,580)）。目标列NOISE/CARVERS/九邻FEATURES/Chunk Load tick26均water(level1)，tick36 END首次air，ALL\_FULL tick38与READBACK tick239仍air。已捕获运行期转换，尚未确认具体写入调用者，禁止先改水行为。世界保存完成，报告/WGP/log保留于 `g49-forge-observe`。build-only Java agent仅对LevelChunk写入加打印栈，第一次1792286448997目标水未消失但总体仍FAIL，已保存于 `g49-forge-write-1`；扩大失败邻域y64后，新世界1792286704726再次真实FAIL并保存完成：西lip五列从level1衰减至level7、最后air，Java agent实际写入栈逐列确认调用者为原版 `FlowingFluid.tick(FlowingFluid.java:408)`；不是植被遮挡或工具错判。调用者证据 `g49-forensics/confirmed-runtime-writes.txt`，报告/WGP/log/NBT原世界保留于 `g49-forge-write-area` 及原saves。本次目标(-588,580)仍water，因此该点只有先前tick36转换证据、尚无同点调用栈，不能直接断言与西lip同因；另外新缺水(-628,712..714)y63未被本次y64 agent覆盖。逆序新世界1792287406528同样真实FAIL/exit1且保存完成：36FULL、6点/3link全过，但181计划湿只175匹配；西lip五列这次保住，缺水改为(-644,688)、(-640,692)、(-639,693)、(-637,695)、(-624,616)、(-623,615)，其中后五列有 `FlowingFluid.tick:408` 实际air写入栈（-644点未捕到）。目标(-588,580)仍water。日志/报告/WGP保留 `g49-forge-reverse`，区域y63..64取证agent未改方块或tick策略；逆序改变缺水位置，不能只抽6点判绿。运行内存采样曾heap约0.96GiB、private约3.36GiB，进程峰值工作集约2.59GiB只是所读时点峰值，不是缓存retained bytes或可接受性能结论。正式水行为尚未修改：已提出model普通水面稳定源水（legacy/瀑布不动）与保留流动并设计供水两种路线，询问超时未获用户选择；不把超时当授权，禁无差别补水/整类过滤。原配置哈希不变，无提交/推送。

### 〇f、G50 稳定水面试验与提交检查点（2026-10-18，未验收）

- 用户已明确同意试“仅model普通计划水面稳定源水，legacy和瀑布水幕不变”。落块从同一 `HydrologyChunkEngine.usesModelPipeline` 实例读取有效分支，不重新按系统属性猜配置；水柱普通段model用源水，y>普通水面的瀑布段仍流动水。schema93，几何/路由policy4不变。
- `g50-static.log`：main/diagnostics/acceptance编译、验收自检（含全水柱中空负控）、routing121项与build通过。真实Forge同时反射调用生产水状态选择，model/legacy/瀑布六种组合通过。
- 首轮新世界1792302099170、36邻块FULL、6点与3link通过、181/181计划湿列全部匹配且普通非源水0；**总体仍FAIL/exit1**：全列审计在ALL\_FULL后200tick观察期检出18个新增计划外水格（总计划外26），位于扩展chunk附近(556..557, y6..10, -2385..-2380)地下。尚未与修改前相同全列审计独立对照，不能断言18格全由本次源水试验导致；扩散风险未排除。7个其它计划外源水格在ALL\_FULL时已存在，不能据此声称无生成期扩散。报告/CSV/WGP/log保留 `build/hierarchical-evaluation/g50-forge-source-forward`，原世界保存完成，原run TOML哈希不变。
- 用户新增截图的湖岸锯齿、贴岸河、长直/直角路线正在只读生产路径归因；没有实施噪声扰动、PNG平滑或路由架构改写，也不把“有动量”当未经验证的通用修复。稳定水面正逆序/扩散归因与形态验收尚未完成。本次按用户要求保存代码检查点，不表示可发布；历史图片、日志、build和存档不纳入Git提交，不推送。

### 〇g、G51 背景水因果对照与形态归因（2026-10-18，进行中）

- 从本地检查点 `3fc780a` 续接，本轮尚未改 main 产出，schema93/policy4及原run TOML不变。G50的18个地下新增水格已通过新世界实际写入栈确认：源(557,10,-2380)来自 `SpringFeature.place:71`，后续18格由 `FlowingFluid.spread` 扩散；7个地表计划外源水来自 `StructureTemplate.placeInWorld`。build-only agent仅打印，证据 `g51-forensics/origin-writes.utf8.log`；历史G49原件先复制并SHA256核对，再在副本读到同源/同水路/7结构水，未修改存档原件。
- 验收schema5保留 `newUnplannedWater` / `rawSpreadVerdict` 原始结果，另外保存全部计划外水（含生成期）与seed、完整TOML/参数/系统开关、9216列计划哈希、加载顺序、200tick、世界高度、generator class资源哈希。候选必须与旧策略独立新世界对照的**全部**计划外水坐标和BlockState集合相同；新增、删除、状态变化、元数据/36目标chunk集合不符均不绿，不按高度/固定坐标/连通豁免。无对照不得通过，reference臂本身永不作为验收绿。对照只在build-only agent中将原 `waterState` 的model参数置false，不改生产源码/默认。class资源哈希只覆盖generator，不能冒充整个构建指纹。
- 最终 `g51-final-static-build.log` 编译main/diagnostics/acceptance、全部验收自检与build通过/exit0；生产未改，完整WorldgenGate未跑，实机整体仍红。build-only去水集合/去chunk集合/去meta比较三变异，分别在新增水、同数量换块、seed错配断言真实失败。新增自检曾错误要求reference自比较绿，实际触发红后已纠正为必须红；原失败日志保留。隔离实机一次因原版 `pauseOnLostFocus` 暂停在10块，线程/日志证实后经验收自身 `abort` 保存退出，报告ERROR及 `saveCompleted=true` 留在 `g51-reference-forward-paused-incomplete`；仅acceptance支线禁止失焦暂停，不动正常run配置。
- 正序同条件对照：旧策略新世界1792308324451，36FULL/200tick，175/181计划湿匹配、ordinaryNonSources175、offPlan26/new18；候选新世界1792308583100，181/181匹配、ordinaryNonSources0、同26个计划外水坐标与BlockState逐位相同、meta一致，**定向PASS/exit0且保存完成**（`g51-reference-forward` / `g51-candidate-forward`）。原G50 FAIL不覆盖，不升级为全世界验收。
- **逆序新增真实红灯**：旧策略1792308851311，36FULL/200tick，174/181匹配、offPlan111/new92；候选1792310884142，179/181匹配、ordinaryNonSources0、offPlan117/new95，六个额外地表水格在(553..555,y87,-2400..-2398)，全部level5..7。严格背景差分报added6、coverage报partial179/181，**FAIL/exit1并保存完成**。逆序宽范围trace新世界1792312261931曾181/181且背景MATCHED/PASS，但无agent复跑1792312666646再次179/181/FAIL（背景MATCHED），不能用trace的单次绿覆盖真实失败。两条缺列(-644,64,688)、(-643,64,689)在保存NBT副本及新增 `planned-columns.csv` 均为oak\_leaves、waterlogged=true，不是空气/水流体消失；现验收刻意排除含水固体，因此开放水体判据仍红，不能直接改成通过。
- 六格扩散已窄范围真实复现：新世界1792312885625，181/181计划湿匹配但背景added6、offPlan117/new95，FAIL/exit1且保存完成（`g51-candidate-reverse-narrow`）。(555/554/553,87,-2400)level5/6/7的实际写入栈全部是 `FlowingFluid.spreadTo:253 → spreadToSides:146 → spread:125 → tick:418`，见 `g51-forensics/confirmed-surface-spread.txt`。副本NBT显示来源邻chunk(34,-151)的model河源水（z=-2404，真实provider相应列fillWater=true/lakePlan=false），对照该河表层衰减后相邻chunk(34,-150)六格无水。因此**稳定源水仍有真实侧向扩散风险**，不能作为地下背景水豁免。诊断曾把provider无侵蚀original当最终地面、误称0.4块侵蚀差是落块错位，主线程复核完整 `terrain.sampleCell→getChunkCells→generateChunk` 后已撤回该错误归因；真实干岸地面y86并无已证错位，纠偏在 `build/g51-route/CORRECTION-2026-10-18.md`。未改生产拦水、装饰或含水判据，下一步须确认保留原版流体扩散还是在model落块计划中显式约束岸线/供水，legacy与瀑布不动。
- 形态只读归因：真实provider四窗口逐列与同 `gen.erodedHeight` 回调对照，湖岸 `floorDiv(block/4)` 的盆身份门每4×4整块切换；W1 x=-560..-556,z=-288地形179.727→172.061连续下降，但直到x=-556才获盆身份、水位177.191120。该64²窗口provider湖掩码与schema92正典图4096/4096一致；西岸mod4=0相位88.9%，同平面地形水平集27.5%。去成员门的196列仅为**反事实差值，不是安全扩湖证明**，须先证同盆真实连通，不能改成最近盆铺水。跨缝补测与纠偏数据在 `g51-shore`；早稿把lake也算river导致贴岸/相位错误结论已撤回，不能引用早稿。
- 长直归因：build-only真实回调逐查x=-224,z260..596的85格点，84步rawD8同向+z，routing.baseNext85/85与raw相同、0盆参与；这条337像素直线不是region接缝或盆内BFS造成。现存owner(-2,1)完整1027边中974与raw同后继，53处差异全部盆内；正确区分入度1转角与汇流后，本owner唯一90°转角及多个90°汇流由 `publish` 的盆内BFS改向造成。证据 `build/g51-route`。旧CSV的canonicalNext/rawNext未引号含逗号，必须按位置解析，不能用错位DictReader得结论；旧pipeline field床面不是world erodedHeight oracle。未实施16方向、惯性或曲线插值，不承诺未经A/B的收益。

### 〇h、G52 形态根因修复（2026-10-18，实施中）

- 用户明确要求优先修湖岸锯齿、贴岸河与非自然路线，复杂水机制暂缓；随后选择“允许根因修复”，允许修改D8/BFS后继及由此变化的汇水/部分湖泊，但世界坐标确定性、唯一后继和跨区一致仍为硬要求。源水侧漏、含水植被的G51红灯保留，不作为本轮形态修复前置，也不改成已通过。
- 第一批新增 `WorldLakeSurface`，仅接入世界骨架分支 `columnsFor`：固定世界对齐STEP单元的四角提供已决盆身份/单平面，采样单元内全部整数列，从真实湿角按四邻面连通判湖；低角不再代表整4×4。没有最近盆选择、跨region洪泛、不同水位插值或未知静默判干；不同盆争夺同列、同身份不同水位、缺失/UNKNOWN均明确失败。只在chunk内临时缓存cell；非世界model及legacy路径不改。湖面优先于河，盆几何/水位/水量账本本批未改；schema94、湿面策略1。
- `g52-compile.log` main/diagnostics编译通过；`WorldLakeSurfaceProbe` 10项含单块脊/对角阻断/异盆冲突/缺失UNKNOWN/负平移通过，退出0；DemandFlow19项、routing既有整套与WorldDrainage37项均0FAIL/exit0。完整门禁、全图与跨缝仍在进行，不能将局部通过当整体验收。
- 同seed/TOML实际provider W1（x\[-576,-513],z\[-304,-241]、4096列）湖3040→3236，新增196/删除0；riverOnly15→3；西岸mod4=0从88.9%→27.5%，与真实地形同水位水平集一致。这里196列实际由cell内连通证书纳入，不再援用旧nearest反事实作安全证明。六倍最近邻同底局部对比 [W1-compare.png](forge-1.20.1-47.4.10-mdk/build/hierarchical-evaluation/g52-shore/W1-compare.png)，数据在同目录W1；旧G51规则检查此时196处不符正是规则已改，不能当新规则验证器。图是生产计划代理，不是运行后Forge全验收。
- 该细化只证明固定cell内到某同盆湿角的连通，不是全盆所有逐块分量的完备证明；窄支湾如四角均干仍可能遗漏。独立真实W2湖2481→2609、HUG1046→1063、NEG河344逐位不变，接缝两owner与独立新JVM正逆序样本一致，仍不代表整体绿。
- **1401²大图真实FAIL，禁止发布/宣称完成**：`g52-shore/canonical-panel.metrics.txt` 两region报不同盆争同列(-370,-93)、(-108,-520)，仅7508成功chunk、773失败域，退出0只是既有绘图工具不传播FAIL，不能视为通过。`conflict.log` 实际worldWater和5×5整数地形复核：前者大盆177.191120与单格小盆177.546765相邻，后者单格盆185.142599与186.539335相邻；粗对角边沿crest仍阻断，但绕开的逐块面连通路径使两候选重叠。说明仅有同盆湿角的连通**不足以裁决异盆边界**，不能用最高/最低水位、最近盆、保持旧判或判干掩盖。当前main为明确抛错的未完成试验，未提交；下一步先处理这些真实冲突再跑大图，不能继续扩大接线。
- D8长直与盆内BFS直角尚未修改生产。build/g52-route原型报告与终稿摘要存在口径差异（文档仍描述FA、摘要改为连续梯度跳长L≤3），只作为待主线程复核材料；长边是否越过未汇流交叉、反向前驱闭包及盆内crest仍未完成生产验证，不能直接采用或以单线变弯宣称根治。

### 〇i、G53/G54 用户圈图精确复核与盆内修复（进行中）

- 用户继续指出串湖贴边、直角与南部平行长直；再次授权重启子代理。中断/额度错误的代理不算通过，重启结果逐项复核。旧 `g53-lifecycle` 的东盆(132,-460)不是用户图1，18/62为潜在入流而非可见河，实际published外入0；其baseNext所报回流还漏了spillChain。上述“18条可见河绕岸/生命周期自环”的表述撤回。正确圈1在x\[-730,-610],z\[-40,110]，盆(-728,16)201成员、同平面158.0646、末端lip(-632,88)→(-628,92)；图上多片蓝色不是多盆。两个实际published入流均过湿面，真正geometricNext续流218点到海、无循环。圈2大盆(-640,-236)。原图裁切亲验，见 `build/g53-lifecycle/actual-circle-0.png` / `actual-circle-1.png`。
- 真正生产缺陷：世界非湖河列 `surface=eroded` 使盆内廊道沿地形起伏，圈1实际列顺流抬水15次/max0.3754。main已修同盆published edge携单平面到SegmentIndex/Hit/columnsFor；等距endpoint优先有平面段，不同finite平面在同零距列仍明确失败。圈1实际155列、2入口，圈2 225列、11入口均surface==level、0抬升、0平面丢失，独立新JVM实测exit0；旧tie导致(-716,12)157.7253而非158.0646的FAIL保留。湖mask不变，不能把此项称贴岸几何已修，schema95；legacy/流体策略未动。证据 `g53-real-circle/surface-after-tie`。
- 新 `BasinStorageRouting` 接入publish：先蓄水低部到唯一lip主干，再从主干湿段接其它成员，干出口主干不作为额外支流的直达目标；无半格可见湿部的浅盆保持唯一溢流树，不造湖面。共享出口/净流/盆身份及预算不改；schema96/policy5。首次routing探针2红为旧精确BFS spine期望（4点）与新过底部spine（5点）不同，保留原日志；迁移为必须经过底部、仍不发布其余三成员后，routing整套与DemandFlow19项编译/运行0FAIL/exit0（`g53-storage-regression-final.log`）。实际圈图A/B与全图仍在验证，不宣称自然路线完成。
- `WorldChannelGeometry` 仍未接生产：真实5×5证明直边crest制造假微盆，但全面换minimax通道会引入同物理出口的虚假多face竞争，需全消费者一致；原单label(crest,length)最短路径有实缺陷，已修crest-only Dijkstra后固定C\* BFS，探针8项绿；build独立19683穷举/200000随机/1352真实边0长度差。不同粗边微通道可能共享列却分向，因此不可直接画通道当唯一河路。原型日志保留。
- 南部长直未修：L3长跳/局部valley-phase均因真实不汇流交叉否决；固定canonical reach可跨owner一致但下游junction距真实连续下降路径约151格，固定端点优化仍313格直段，不接生产冒充根治。规则网格地形自适应位置原型正在build限定评估。完整WorldgenGate/实机/独立最终CodeReview尚未完成；G52两异盆全图红灯不豁免。

### 〇j、G54 大图覆盖回归与 G55 修复（进行中）

- G54/schema96真实生产1401²图仍 **TRUE\_FAILURE**：成功5900/8281chunk，7失败tract、620827像素/31.63%；六tract首错 `storage tree does not cover basin`，另保留(-370,-93)异盆竞争。图和metrics在 `build/hierarchical-evaluation/g54-storage/`；绘图工具exit0不代表成功。
- G55仅helper叠层观察，在四独立新JVM owner(-2,-2)/(0,-2)/(-1,-2)/(1,-1)复现新增覆盖错误。完整lip BFS分别5/5、5/5、5/5、16/16，而湿优先树4/5、4/5、4/5、12/16；缺失节点全部邻接已占用干主干节点。主干干点已写next却不入队、被 `!next.containsKey` 当墙，非成员不连通或选错出口。主线程亲跑真实高度表JDK独立fixture复现同异常；原失败与debug日志保留，见 `build/g55-storage-capture/`（报告部分干/湿汉字颠倒，以height\<sill−.5及代码为准）。
- main修复为两阶段：湿主干优先attach完全结束后，仅对尚缺分量从原主干再attach；不覆盖第一阶段既定后继，不把有湿通路的支流提前接到干lip。浅盆保持原树；schema97/policy6。新增正/负平移fixture同时断言完整覆盖、被隔支湾唯一物理接入、湿段优先即使干lip更近、全成员无环到同出口、枚举逆序一致。main/diagnostics编译、routing全套（新增10断言）/Demand19/Drainage37 exit0，`g55-storage-regression.log`。真实四owner均noThrow/0unresolved，薄adapter直接调用main四真实fixture全覆盖/无环/同出口/crest合规、member逆序完整Map相等；旧helper叠层负控四份全红。邻居列表逆序是改变tie规则、非查询顺序测试，不援用其“另一合法树”冒充世界一致。两圈图实际columnsFor独立JVM：2/2及11/11入口平面一致、0抬水/0平面冲突；inventory冷逆序正文相同。G55/schema97全图7508/8281chunk、仅剩原两异盆冲突、288256像素/14.69%，新增覆盖回归已消失，但整体仍TRUE\_FAILURE，不可验收。
- 全量通道crest的build叠层未合入：owner(-1,-1)原prefix规则16322未知，证实33个分叉分支回升被整体拒绝、7个鞍点恰在outer无后续列。下一实验改只排除回升分支/保留并列未知，但实际provider仍14078未知，主因selected spill path EXHAUSTED；不得据此增预算或吞UNKNOWN。原型报告将850k整region采样误称共享预算，实际每sink独立new solver；尚需精确归因prefix/suffix耗尽，不能当已确认根因或直接采用缓存建议。后续splice复用已采样同sill外部连接与旧witness后缀，EX\_THROUGH/采样cap归零但仍14078未知，否证“预算是主因”；移除代理擅加的prefix4096局部cap（误拒4645成员大盆），纠正block鞍点与coarse path不一致/endpoint continuation键，再降为6009未知，仍exit1未合入。剩余分叉回升节点均低于sill，部分微坑还低于coarse floor，**只能证明当前粗图和所选通道候选不完备，不能证明物理无出口**；代理“真实地形拒绝”结论已撤回。原型到此停止，不接受6009未知/不升预算/不吞失败。生产仍使用直边crest，异盆竞争与自然路线任务未完成。
- `runWorldgenGate` 本轮真实exit1，停在 `runCenterlineCrossSectionProbe`：组件126→141、内部端点23两红，CaveShape已绿，其余门禁未执行。已用HEAD3fc780a三生产类编译叠层隔离对照：baseline/current独立JVM均exit1、12指标相同、4产物SHA256逐位相同（主线程亲验）；class-load两臂142个项目类相同，本轮四改类/三helper加载均0。因此确认为HEAD既有门禁失败，未放宽阈值/修无关路径，但不能称完整门禁绿。证据 `build/g55-gate-baseline/`，原日志 `build/g55-worldgen-gate.log`（PowerShell重定向UTF16LE，归因目录有UTF8副本）。
- G55 valley-mesh仅build评估：±1–2格重嵌入虽缩短部分长直，但真实连续下降谷仍偏离67–71块、成本约10.3倍、且未全链迁移；主线程已目检走廊图不接受小抖动冒充根治，未接生产。报告的 `e=2仍满足e<S/2` 与取整后的幅度证明有误，不能援用为全局几何证明。后续build-only G55U非规则采样+Delaunay最陡下降，主线程已读实现并目检：该单seed目标段max谷偏89.4→7.3块（J=.8），确实离开D8长直靠向连续谷；但不是生产验收。节点密度S固定不变，J阈值不能叫密度阈值/非偶然证明；ring2..5有限一致不能当无限世界完备性证明；精确点集0交叉而块取整4交叉是真gap。报告已纠正Gabriel/Delaunay定义、单射界、完备性越界，列出尚未验证的ring推导、最终block拓扑、身份/位置接口与非规则面积水量账本。因此只保留为有价值可行性证据，未接src、未改配置/预算。
- 独立只读审查在首次上游502、第二次中断后重启完成：未给出新确证P1/P2。接受并修正了provider湖面注释仍称“全部取细层”的过时表述；`List.contains`盆成员性能疑点未计时，保留为未证实，不额外做性能重构。审查未运行实机/探针，不能替代验收。注释修正后main/diagnostics编译及routing/Demand19/Drainage37再次exit0（`g55-final-regression.log`）。

### 〇k、G56 表示层可行性与补全门禁（尚未接生产）

- `runWorldgenGate --continue --offline --console=plain` 已补全运行12探针，11通过，仅CenterlineCrossSectionProbe原两判据红；Gradle最终exit1。已通过：CaveShape、ForkFeasibility、HydrologyDeterminism、LifecycleChain、OreFilterBehavior、OreOverride、OreVein、Stratum、TectonicContinuity、TectonicWave、TerrainGrain。原日志 `build/g55-worldgen-gate-continue.log`，不得说“门禁全绿”。
- G55 integer-topology代理首次交付只有一份未编译源码，却声称测量完成；主线程列目录后要求撤回，代理确认全部数字无证据作废。保留原草稿，不援用任何数字。后由独立实现代理在 `build/g56-integer-verify/` 真正完成探针/runner/日志/PNG，修正原稿按drop而非坡率选边、crest与绘制列覆盖不一致两问题。
- G56IT固定S4/J=.8 round/clamp±1后在**最终整数点**上建立精确有理Delaunay邻接。真实目标段endpoint-only与crest-gated分别测得112边、max谷偏20.5/mean3.4、z\[260,619]，同终点(-41,619)、0crest违规；同底图已目检，确实离开红D8长直靠向青色Euler路径。7215节点窗口有8个微洼/6个crest阻断，8263共享列对未见不共享节点冲突，负域4225点身份/邻接对称与冷逆序289点一致。按本轮原型严格判据真实exit1，未接生产；微洼是待盆地层处理的正常候选，不能单凭存在微洼证明方案原理无效。ring4/5有限相等非无限世界证明；亚块稠密rint覆盖不是精确supercover。面积账本/盆出口/世界identity迁移仍未做，不能称整河已修。
- `build/g56-cell-quotient/` 真地形两冲突cell精确25整数列/40四邻边局部merge tree：各25阈值与暴力图0差，邻接双cell各45阈值及496port对0差；上界49树节点、16边界port，能保留粗角图看不到的微坑。生产两异盆冲突仍可复现，差异身份在真实水平集连通。**这只证明局部连通表示可行，不提供水量/合法水位/全局盆身份**；WorldLakeSurface已经exact5×5洪泛，单换merge tree仍会看到同样冲突，必须与身份生产者一起迁移，不能只改surface掩盖。树临时id不能跨cell当世界id，49是构造上界非最小性证明。原报告25/300回调“12倍”覆盖域不同、边界共享且生产缓存dedup，已撤回实际提速说法；日志原证据保留并在报告纠正。全图/实机/多seed尚未验收。

### 〇l、G57 统一图候选触发停止条件（2026-10-19，未形成完整链路）

- 按获批计划在 `build/g57-unified/` 隔离实现契约、局部储水与整数mesh投影；**候选失败，拒绝迁入生产**。生产仍为 schema97/policy6，四个生产输入源码与原run TOML经基线SHA256核对未变；provider副本仍与生产源码逐字节相同，未编译或接入新图。`worldWaterForProbe`、`columnsFor` 和绘图消费者**没有迁移**，不能把副本存在说成完整provider已实现。计划的75/86/98行要求前置失败即停，不靠增预算、丢边、坐标选口或绘制遮盖继续接线。
- **投影失败已独立复现**：固定S4/J=.8/round/clamp±1，真实9443条选边覆盖48377块列，有227列双后继；换角桥规则仍191列。单调平面 `h=x+z` 也有119处冲突，不能归因于真实地形微坑。无自身冲突的起点中6149个最终进入环（是起点数，非独立环数）；目标段339步后在(-194,531)遇冲突。唯一高度采样133140/524288，未耗尽，不是预算不足造成。详见 [routing.log](forge-1.20.1-47.4.10-mdk/build/g57-unified/natural/routing.log)，进程exit1，两个硬断言真实红。
- **最小反例**：seed9139912035078620160，已记录选边A=(-375,629)→B=(-377,633)→C=(-375,635)。AB逐块通路在P=(-376,633)要求西行至(-377,633)，BC在同P要求南行至(-376,634)；mesh链本身无环，独立投影却不是单后继函数。主线程具体回放正反边枚举均拒绝、exit1；该回放固定已记录选边，只单独证明投影冲突，真实选边来源由完整探针核实。[反例示意图](forge-1.20.1-47.4.10-mdk/build/g57-unified/verification/projection-counterexample.png) **不是游戏/生产渲染图**，不能用作形态验收。
- **局部储水不等于完整盆地**：25列/40边cell索引与暴力水平集一致，局部微盆、负平移、冷逆序、预算拒绝等探针88 PASS/0 FAIL；两冲突cell的微盆闭包分别只有1/2成员，旧身份对应小闭包相互分离，尚未完成回水合并、下游链认证或净流账本。第一湿水平集完整分量73512列，第二在131072局部预算内明确拒绝；这不是两旧冲突盆已统一。原生产两处身份竞争仍由探针真实复现。详见 [exact-block-storage.log](forge-1.20.1-47.4.10-mdk/build/g57-unified/storage/logs/exact-block-storage.log)。
- **独立审查发现并收束**：原局部出口规则中所有rim outer高度都等于sill，“最低outer”实际退化为XY选口，且可能与见证离盆方向不同。已删除该代表选口；仅一个rim face且见证跨越同face才发布局部出口，多个face即UNKNOWN、无outlet/next，保留完整几何；这只是诚实拒绝，不是多面出口已解决。路由的未覆盖严格下降平局改抛显式未决异常，不再与真实sink一起返回null；前驱枚举返回完整四邻候选，消费者必须确认后继并传播UNKNOWN，不跳过冲突而漏雨源。地形预算改专用异常，储水层不再广捕拓扑异常当耗尽。路由memo容量/独立展开预算仍未完成，故不准接下游；删除无计数器支持的“已实测最大cell距离”注释。
- **修正后主线程最终复跑**：契约编译/探针exit0，局部储水编译/88断言exit0，自然投影编译成功但227双后继及6149起点入环两断言仍exit1，具体反例独立编译/回放exit1，生产输入核验exit0。储水日志与代理冷JVM逐字节一致，自然探针日志除elapsed外逐行相同；[final-exit-codes.txt](forge-1.20.1-47.4.10-mdk/build/g57-unified/verification/final-exit-codes.txt) 明确标为候选REJECTED，不能把验证流程如期拒绝误读成原型验收PASS。计划正文未改；Todo状态同步将exact-storage从in\_progress改为pending，因而文件总SHA256相应变化，未把未完成项标成完成。
- **未完成/未实测**：exact-storage完整回水与原冲突盆上下游闭包、合法自然路由、流量/provider迁移、新provider多seed/跨区/取消完整验证、1401²新实验生产入口大图、Forge落块、完整链路成本均未完成。没有新的G57生产大图，不援用旧G55图或局部示意图验收。既有G56补全门禁11绿/1红及HEAD两红归因保留，本轮隔离失败实验未重跑全部WorldgenGate。不提交、不推送、不替换实例，保留既有dirty源码、日志和图片；停止候选不等于完成六项计划，更不等于证明统一图方向不可行。

### 〇m、局部累积与流式接口差异实验（已完成实验，未接生产）

- 撤回“无限河流骨架必须先求任意大流域精确完整Q”的扩大前提。生产侵蚀dis确为局部反馈过程；新增诊断 `LocalFlowEvents` 则明确是冻结 `erodedHeight` 上的有限寿命输运，不冒充SH反馈、完整稳态Q或水面权威。世界出生事件含seed/config/格坐标/轮次，目标外扩寿命L枚举全部可能贡献；owner不截路径。局部有限事件的按需定义不要求枚举无限世界全部河源。
- `LocalFlowOwnershipProbe` 32项检查通过，覆盖真实不同依赖域、独立解析平面oracle、owner/相位/负坐标、冷重建、三并发及取消重试。四个build副本代码变异（漏halo、查询域种子、先到覆盖、去掉事件去重）均exit1且指定断言红；默认seed和12345局部场均通过。原dis双tile同561个wu坐标实测：默认seed8处不同/maxAbs0.09880659，12345有337处/maxAbs2.56690812；诊断阈值0.01的准入差分别0/61，未改生产源准入。
- `LocalFlowJoinProbe` 反射复用G42原积分器，69硬判据绿；五轨迹固定窗33/37/33/34/35节点，滚窗312/561/226/167/82节点，冷逆序/相位/阈值同值，终点仍局部低地pending。4个真实列碰撞候选归一位置只保留来向后共同尾部0，清动量对照最多19节点；不能将同整数列称已实现真实汇合。合成无记忆规则双/三入流后缀394/390、三臂贡献精确相加及负平移通过，仅局部候选，不称自然路线改善。G42任意世界完整源目录仍NOT\_ESTABLISHED，因此exit2；五已知源索引一致不等于无限发现已解决。
- `LocalFlowBasinProbe` 直接复用G44与生产几何/按需账本：串盆回水、零来水、蒸发耗尽、足够下界及UNKNOWN分开验证。真实block最低点(-428,-94)的非空单位四邻证书9成员/14割/26采样、escape162.74098449818717，独立strict重洪泛与全部割闭合；不是旧两竞争盆的整盆闭包。默认seed59检查/0候选断言失败/2未解决/0基础设施失败，exit1，两旧异盆竞争仍红。12345独立复跑57检查通过、exit0，仅代表该seed样本。粗step4与block单位四邻不混用，`CertifiedBasinQuery.cellSize`仅缩放容量面积。
- 两个冷JVM的13份CSV/PNG逐字节一致；指标仅归一输出目录/耗时。入口为MDK下 `powershell -NoProfile -ExecutionPolicy Bypass -File tools/run-local-flow-reassessment.ps1`，默认整套预期exit1，必须检查各探针verdict，不能把编译成功当生产验收。完整证据本地保存在 `build/local-flow-reassessment/decision.txt` 与 `verification/evidence.txt`，不纳入源码提交；独立init script导出classpath，runner现场生成输入哈希，不依赖被忽略的旧计划才能启动。
- 用户已批准将schema97依赖与实验分批本地提交；生产依赖检查点 `f0ba855` 保留现有红灯，不等于可发布。实机验收工具改动、图片/日志/配置/存档不在提交范围。实验没有改变生产产出或缓存版本，未做新的provider迁移、1401²生产图、Forge新块、完整WorldgenGate或全链路性能验收，不提交这些未实测结论。

### 〇n、G42 世界有限出生事件接线（诊断推进，未接生产）

- 已按用户确认分批本地提交：`f0ba855` 保存schema97依赖，`d0165b0` 保存〇m实验；均未推送。此前实机验收工具、图片/存档/日志未混入。runner在本地计划缺席时也可启动，并现场生成生产输入哈希；仍须用户TOML作为真实地形输入。
- 新增 `LocalFlowRollingProbe`：复用 `LocalFlowEvents.events` 的世界seed/config/出生格/轮次目录，直接调用同一G42薄适配，不再只为5个固定起点建立索引。G42的L是最多L次移动、最多L+1状态，与四邻事件L次访问不同；每次连续移动≤1，按floor映射方块列，出生halo=L+1保守覆盖浮点边界，查询框只裁输出、不截轨迹。每列按事件身份保存状态次数、质量和原terminal，不将多亚块访问算成多次雨源，不把pending变干或冒充完整河路。
- 默认seed与12345各31项检查/0失败，独立冷JVM的3CSV及metrics逐字节一致。包含独立轴向解析oracle（L次移动终点也计入）、真正更大出生域对照、owner/逆序/窗口相位/负坐标角点、下游先查、三并发、取消重试、漏窗外源与寿命off-by-one负控。默认真地形70出生事件、1750状态、26950原始高度采样、280换窗、62输出列；12345为66出生事件、1537状态、28840采样、28输出列，其中11个局部低地pending。采样为每轨迹未缓存回调次数之和，不是唯一世界地形采样或provider全成本；未采集sampler cache峰，事件目录/贡献实体化内存另计。
- 三个新增build副本代码变异（漏halo、负坐标int截断替代floor、固定工作窗）均exit1且指定失败命中；与既有四个事件变异共7例，编译失败不算检出。主线程已重编main/diagnostics，整套runner仍真实exit1：ownership0/join2/basin1/rolling0。两竞争盆和完整河网源目录红灯没有被新增局部发现正例覆盖；旧join仍测原5源契约，不偷偷删open gap。
- 证据本地 `build/local-flow-reassessment/rolling-final/`、`rolling-cold/`、`rolling-12345/`、`rolling-cold-evidence.txt`、`continued-variation.log`；原生产源码/配置哈希不变，缓存版本不变。未新出生产图/Forge验收，不把该有限过程接线称“无限河网已完成”。下一关键接口仍是世界共同接收状态/共同后缀与盆出口/回水身份，而不是再要求所有列精确全Q。

### 〇o、共同接收状态与块级盆身份交接（诊断推进，未接生产）- **定义了一个明确的共同接收状态**，而不是让每条轨迹带着自己的动量继续走。每个方块列被当作一个**声明的控制体**：同一世界里所有经过它的有限事件各贡献一次来向与质量，按**世界事件身份**（seed/config/出生格/轮次）排序后求和，再据**这一列的合并来向**决定唯一下一列。事件不读接收图，因此 `L+1` halo 仍然成立，没有隐藏的递归影响半径。⚠ 这是**新的**图语义，不是"未改动的 G42"：G42 的连续轨迹本身仍然保留各自来向、会在同一点再分叉。

- 判决规则本身都是可反向验证的：候选边必须是**连续次邻**且整段 8 采样严格下降（`descendingEdge`），打平则 `EQUAL_RECEIVER_SCORE_PENDING`，无下降边则 `BLOCK_LOCAL_MINIMUM_OR_FLAT_PENDING`——**两者都不代表"干"或"已入海"**。合成三臂汇流在同一世界列之后走**完全相同**的后缀（证明源动量未存活），而"只用第一个到的事件"的规则会在同一地形给出不同结果，作为负控。
- **真实地形实测**（默认 seed，TOML 拷贝读取，原文件哈希不变）：37 项检查 0 失败。出生事件 70 个、1750 个 G42 原始状态、26950 次原始高度回调、62 个输出列，其中 **23 列是真实多事件控制体**；0 个 pending；一条真实上游路径的后缀与"冷重建 + 下游先查"逐项相等。`12345` 独立复跑 35 项 0 失败（3 个多事件列、28 列、1 个 pending）。
- **块级盆身份**：共同接收状态从粗格点 `(-428,-92)` 走到块级最低点 `(-428,-94)@162.48945`，交接给一个**独立闭合**的块证书（9 成员、14 条边界割、`atSill=1`、`belowSill=0`、`escape=162.74098`），且与独立的 Kruskal 激活算法解一致。沿唯一首口（`(-428,-96)`）跨平台到接收床 `(-428,-97)`，再降到下游块盆 `(-428,-98)`，其首口 **升高**到 `163.35624`（+0.61526），在 `163.35624` 处的合并体（170 成员）**包含**原 9 成员盆且为**单连通分量**——这是该样本上"存在回水合并"的实测证据，**不是湖水位、也不是水量放行**。
- **生产接口缺口仍然真实存在，而且比"无法表达"更糟**（本轮 2 个 `UNRESOLVED`）：生产的合并谓词 `downstream.members().containsAll(initial.members())` 在 **step-4 格点**上求值，而块证书的 9 个成员里**只有 1 个**是格点 ⇒ 该谓词连主语都命名不了。**但没有停在这里**：实测了两种可辩护的"提升"（cover = floor-div 覆盖粗格；strict = 只保留本身是格点的列），结果是 **两种提升都让谓词返回 `true`**，而它们分别只有 2 格和 1 格、都不是那 9 列块体 ⇒ **"containment 通过"是假通过，不能认证身份**。决定性负控：`containsAll(∅)` 同样为 `true`，所以该谓词对"整盆"和"空集"给同一答案。加上 blockEscape `162.74098` 与 coarseSill `163.03260859616427` 是**不同算子的值**（差 −0.2916），⇒ 生产要接这份块证书，必须**声明一个块→粗提升 + 一个层级换算**，二者都不能靠挑坐标或放宽谓词绕过。
- **自底发现并修掉的三个新诊断缺陷**（都由独立反例驱动，不是"改到绿"）：① 平台下游用 FIFO 取第一个低于 sill 的列 ⇒ 对称双盆下换遍历顺序就换接收盆；改为**收集全部边界列 + 按严格低于 level 的连通分量去重**，0 个 / ≥2 个 / 触框一律 pending。② 等高平台会被当成汇点 ⇒ 改为"等高分量闭合且无更低边界"才算 sink，且**逐格走出连续等高路径**到排水列（`Move.PATH` 记录中途列，不瞬移），单格 pit floor 与平台分开命名。③ 几何发布处用 `EPS` 比较割高 ⇒ 会把只高 `1e-12` 的细条并进 at-sill 集、**凭空造出 `MULTIPLE_OUTLETS`**；改为**精确比较**，并用该细条作双向负控（精确判 1 个、EPS 判 ≥2 个）。
- **断言语义修正**：`strictFourMinimum` 的名字撒谎（它只表示"无更低的邻居"，平台也满足），已补 `weakNoDescent` 别名与真正的 `strictAllNeighboursHigher`；主线程交接调用改用后者。`mergeAt` 增加"合并集触框即 pending"，且不再把中断吞成几何结论。
- 证据本地 `build/local-flow-reassessment/shared-final3/`、`shared-cold3/`、`verification/shared-verify3.log`：**35 份** CSV/result 在独立冷 JVM 下**逐字节一致**（metrics 仅归一输出目录/耗时）。**16 个** build 副本变异全部 `exit=1` 且命中指定断言：既有 4 个事件变异 + 3 个滚窗变异 + 9 个新增/扩展（首事件优先、按插入序归约、漏 halo、忽略沿程 crest、允许触框闭合、平台下游先到优先、等高平台当汇、EPS 判 sill、lift 静默通过）。函数均 ≤80 行。`block-handoff` 75 检查 / 0 断言失败 / 2 未解决 / 4 差异；`receiver` 37 检查 / 0 失败。
- 整套 runner 仍**真实 `exit=1`**：`ownership 0 / join 2 / basin 1 / rolling 0 / receiver 0 / block-handoff 1`。`join=2` 仍是原 5 源契约的 open gap，`basin=1` 仍是两个真实竞争的盆；本轮**没有**用新绿覆盖它们。**未做**：生产 provider 迁移、1401² 生产大图、Forge 新块目检、完整 WorldgenGate、全链路成本、任意世界完整源目录、自然河网全生命周期。缓存版本与生产产出未变。
- ⚠ 本轮未对 `LocalFlowBlockHandoffProbe`（2127 行）做文件拆分，仍是单一诊断文件；按规定它不属于 `src/main`，不影响生产，但后续若要长期维护应先拆分。

### 〇p、legacy 线移除：骨架成唯一生产河网线（第一阶段已实施，2026-10-20）

- **背景**：model 链内部曾有三条共存代码线（旧 `RiverCenterlines` 累积→阈值→细化 / `worldRegion` 世界图两点连线 / `DeterministicSkeleton` 骨架），靠 `SKELETON_TRACE`（系统属性→配置项→false 三级回退）+ `CROSS_PASS` 两个开关组合选择。**探针进程无 Forge 配置 ⇒&#x20;**`SKELETON_TRACE`**&#x20;长期回退 false ⇒ 探针测的一直是 legacy 线，而实机按 TOML 走骨架**——"探针绿、实机不对"的第二次踩坑（第一次见 §2026-10-10 注释）。多轮叠加从未合并 ⇒ 用户判定"应该只有一条"，决定 **legacy 线移除、git 留档**。
- **本次改动**（main）：`resolveSkeletonTrace()` 删除，`SKELETON_TRACE` 恒 `true`；两处 `else` legacy 分支删除（`solveFine` 的 2418 与 `solveProduction` 的 2529 区域，删除后 main 无 `RiverCenterlines.build` 调用）；落块门控处 `onChannel = half > 0`（距离场命中即河）与水位差自适应深度无条件生效；instance 指纹不再混入 `hydrologyDeterministicSkeleton`。`GeoGenesisConfig.hydrologyDeterministicSkeleton` 配置项删除（旧 TOML 残键被 Forge 忽略，用户 TOML 已同步清理）；acceptance 横幅改为镜像常量 true；`CanonicalViewProbe.describeCodeLine()` 保留三态打印（出现 LEGACY 即异常）。`RiverCenterlines` **类型保留**——它是骨架/世界图发布折线的几何容器（Reach/Result/SegmentIndex 消费），不是 legacy 算法本身；`riverModel()` 的 D8/CONTINUOUS 证据场选择不变。
- `PreviewDisplay.CACHE_SCHEMA_VERSION`**&#x20;97→98**（清单留痕）：产出含河网几何口径变化 ⇒ 旧预览缓存必须失效。
- **验证**：main/diagnostics/acceptance 三树 Gradle 编译通过（`build/full-compile.log`）；DemandFlow19/WorldDrainage/WorldLakeSurface/WorldChannelGeometry/WorldBasinRouting 回归全 0 失败（`build/hydro-regression/post-legacy-removal/`）；1401² provider 大图两冷 JVM **逐字节一致**（SHA256 `AE0C861C…`，湖 102947/5.24%、河 25107/1.28%、8281 chunks，`build/hydro-reassessment/canonical-1792482262452/` 与 `-1792482580625/`）。图与移除前默认路径（AB-skeleton-world）**完全一致**——证明本次只删线、未改默认几何。
- **行为变化（重要）**：① 无配置环境下探针从此与实机同线（此前探针默认测 legacy）；② 旧 TOML 的 `hydrologyDeterministicSkeleton=true` 行不再有意义，删除该键即可；③ `CROSS_PASS=false` 的 `-D` 仍可切到纯 `DeterministicSkeleton` 几何（A/B 归因用），但这仍是"世界图 vs 骨架谁出几何"的待合并项，不是 legacy 复活。
- **未做/下一阶段（第二阶段，先出方案再动手）**：`worldRegion` 的两点直连废掉、corridor 几何改消费骨架形态收尾折线（世界图只管湖面/盆身份/跨区接力/水量）；正面修平行直线/动线不自然（meander amp=0 是用户 2026-10-10 指示关闭，重设计由用户拍板）与入湖贴边。本轮**不声称**平行直线已修——图上仍可见，量化见 `inspect-canonical-figure.py`（最长直段 116/118 px）。未跑完整 WorldgenGate、未做实机 Forge 验收、未提交。

### 〇q、★ 跨区接缝：边界带四侧不对称（2026-10-22 已修）

> **先纠正一个我犯过的方向错误**：看到跨区断流时，我先提出"必须重做世界路线所有权"的
> 大改造方案。用户追问后回查 git 里删除前的 `RiverLineNetwork`，确认 **legacy 早已有
> 跨区机制**（pass-1/pass-2 双 pass + `OutletSeed` 携带世界坐标/accum/surface/level +
> `sampleAll` 逐点查 **3×3 邻区实际河线**）。⇒ 当前缺陷是"继承时丢了/收窄了某处"，
> 不是"确定性水文做不到跨区"。**确定性 ≠ 跨区连续**：程序可以每次都在同一处断。

- **根因（是判据不对称，不是架构）**：`ModelHydrologyProvider.columnsFor` 的邻区惰性
  查询门控 `nearBorder` 低侧写 `<`、高侧写 `>=`。窗口可画到 `origin+(n-1)*STEP`；
  对 `owner(-1,0)`（originZ=-96、n=353、STEP=2）即 **z=608 = regLoZ+HALO**，
  恰落在旧低侧带外；而北邻能画到的最后一行**也正是 608** ⇒ 两个 region 都不声明该行
  ⇒ 出现"无人负责"的空洞。第二层同源：邻区循环的侧向过滤也用 `>=`，端点会被再剔除一次。
- **修法**：`nearBorder` 四侧统一闭区间；侧向过滤低侧改 `>`；`CACHE_SCHEMA_VERSION` 102→103。
- **验证**：`ProductionOwnershipProbe` 该接缝 **296 丢列 → 2 丢列**（余 2 列是 shape
  几何过冲到 z≈608.6，非策略错误）。1401² 前后逐像素：**变化 1409 像素，全部落在接缝行/列
  （z=-416/96/608，x=-416/96），全部为新增河色、删除 0**；湖 110182 未变；河 36913→38322
  （+1409，与像素数一致）；4 连通 204→191。`SeamBandBoundaryProbe` 把该结论做成可执行
  判据（含"旧 `<` 排除端点"的负控），与既有契约同批 ALL PASS。
- **仍未完成**：南部仍有非湖海终点；**未跑 Forge 实机、未跑完整 WorldgenGate、未提交**。

### 〇r、JOIN 自汇：绕回自己被记成汇流（2026-10-22 发现，未修）

- 审计相邻 owner 时出现大量 `BROKEN_JOIN`（owner(0,-1) 46/114、(-1,-1) 48/108）。
  先用完整 reach 格路径复核，确认**不是审计 first-wins 假象**：
  `JoinCellContainmentProbe` 显示这些终点只被**自己**包含（`holders=[20,20]`）或落在更晚链。
- 根因在 `traceOne` 的 `if (covered[p]) { … end = End.JOIN; }` —— **未排除本链自己的格**。
  河流绕回自身已走过的格时也被记为 `JOIN`。只读插桩（`-Dgeogenesis.lifecycleDiag=true`，
  reason=`SELF_JOIN`）实测 **108 次**，其中 96 次未被回滚。它同时解释 `BROKEN_JOIN`
  与图上悬空短棒。
- **未修**：改成 `DEAD_END` 会连带改变回滚与网络过滤（可能删河），属路由语义变更，
  须单变量对照 + 出图后再定。当前只保留只读日志与两个审计探针。

### 〇s、M2 接力起点容错：已实现，本样例未触发（2026-10-22）

- **移植 legacy `bestHandoffStart`**：种子落局部极小（8 邻无严格更低）时，在 1 格窗口内改选
  「自身有可下降邻格、下坡目标不是坑底自己、未被占用」的格；先取抬升最小（≤
  `geogenesis.handoffMaxRise`，默认 4.0），再按坡降率择优；救不出如实返回原格。
  仅作用于接力起点，开关 `-Dgeogenesis.handoffStart=false` 可逐位回退。
- **被自写判据抓到两次实现错误**（都对应用户可见后果）：① 要求候选低于种子 ⇒ 坑底永远选不出；
  ② 只按坡降率 ⇒ 选到坑外远处陡坡（等于让河先翻山）；③ 未排除"下坡目标就是坑底" ⇒ 原地打转。
  `runHandoffStartProbe` 8 项 ALL PASS。
- **实测未触发**：owner(-1,0) `incomingStarts=0`；全量 12 次接力的种子**全部原本就有下坡**。
  全图 A/B（仅切该开关）差异 **10 px**、河 38322→38326、湖不变、连通 191→192。
  ⇒ 它是为特定失败形态准备的容错，**不是断流主因**，不得据此宣称跨区改善。
- **同批新证据**：12 次接力里 2 条 `mouth == source`（落地即死），终结原因为 `SELF_CROSS`，
  与 §〇r 的自链判定同源，须单独处理。
- 详见 [M2 实施记录](handoff/GeoGenesis-m2-handoff-start-20261022.md)。未跑 Forge 实机、
  未跑完整 WorldgenGate、未提交。

### 〇t、"精度太高"假设的量化否证（2026-10-22）

用户提出「是不是精度太高？之前把某个 1 改成 2 就好了」。按代码与实测逐项核对：

| 候选 | 现值 | 结论 |
|---|---|---|
| `geogenesis.modelStep`（网格步长，3c94a90 由 4→2） | **2** | 用户已确认不是它；且该值确已生效（`grid=353x353`） |
| `GRADIENT_SPAN`（坡向跨格数，"1=噪声大 / 2=更稳"） | **2** | 实测**反向**：span=1 河 37397、死端 140；span=2 河 38326、死端 149；span=3 死端 151 ⇒ 加大跨度**不改善** |

⇒ 两条"精度"旋钮都**不是**当前非湖海终点的成因。

**更关键的否证**：新增只读探针 `DeadEndCauseProbe` 直接查终点格的 bed 场，
把"真局部极小"与"有更低地形却没走（守卫/跨区误杀）"分开：

| owner | 保留 DEAD_END | 真局部极小 | 有更低邻格却没走 |
|---|---|---|---|
| (-1,0) | 3 | 3 | 0 |
| (0,-1) | 6 | 6 | 0 |
| (-1,-1) | 5 | 5 | 0 |
| (-2,-2) | 4 | 2 | **2** |
| (1,-1) | 2 | 2 | 0 |
| **合计** | **20** | **18（90%）** | **2** |

⇒ 残留非湖海终点**九成是地形真有闭合洼地、水无处可走**，不是"方向选错/精度抖动"。
真正要对的是**洼地/盆接口**（要么合法成湖，要么找到鞍部溢流继续下泄），
而不是继续调追踪精度或放宽守卫。

⚠ 唯一例外 owner(-2,-2) 的 2 例确实"有更低邻格却没走"，需单独归因（自交守卫/跨区）。

### 〇u、JOIN 归因分离：15.5% 的"汇流"其实是绕回自己（2026-10-22）

`End.JOIN` 有三条产生边，此前日志一律只显示 `JOIN`，导致 54.5% 的汇流率无法判真假。
新增只读 `JoinKind`（`NEARBY` / `PARENT` / `SELF` / `OUTLET_ESCAPE`），**不改任何路由**。

owner(-1,0)、922 条去重 trace 实测：

| JoinKind | 数量 | 占 JOIN |
|---|---|---|
| `NEARBY` | 394 | 82.6% |
| **`SELF`（绕回自己）** | **74** | **15.5%** |
| `PARENT` | 7 | 1.5% |
| `OUTLET_ESCAPE` | 2 | 0.4% |

- `SELF` 形态：源→终点直线距离中位 **44.4 块**、最大 383.7；nodes 中位 27、最大 **217**
  ⇒ 「走了两百多格后回到自己」也被计成合法汇流。
- 边框上的 JOIN（101 条）：南框 67（NEARBY 44 + **SELF 21**）、东 20、北 10、西 7。
  南框 `z = oz+704 = 608` 上 nodes 中位 64、x 跨度 1726 块、23% 相邻间距 <4
  ⇒ **这就是图上 z=608 那条 403 块水平长直的来源**：几十条垂直河在同一条窗口边框上
  以 `NEARBY` 彼此相汇，几何被串成横线。数据上"合法"，物理上是边界制造的假汇流。

⇒ 两个**互相独立**的缺陷：
**A** `SELF` 当 JOIN（74/477，悬空短棒、假汇流率、掩盖真自交）；
**B** 边框处 `NEARBY` 假汇流（65 条，水平长直、河在边界被横向接住）。

两者都**未修**：A 改动 `covered` 判定会连带改变回滚/网络过滤（可能删河），
B 属跨区契约改动（与 M3 同族）；均需单变量 A/B + 出图。
详见 [JOIN 归因分离](handoff/GeoGenesis-join-attribution-20261022.md)。

### 〇v、修复 A 实测：单点删自汇反而变差 ⇒ 默认关闭（2026-10-22）

三处 `End.JOIN` 产生点都加了守卫（目标格属本链时记 `DEAD_END` 且不 `path.add`），
开关 `-Dgeogenesis.selfJoinFix=true`，**默认关**。判据 `SelfJoinFixProbe` 有反向验证
（显式设 false ⇒ exit=1），不是假通过。

**单变量 A/B（1401²，同 seed/中心）**：

| 项 | 关 | 开 |
|---|---|---|
| 湖格 | 110182 | 110182 |
| 河道格 | 38326 | 39959（+1633） |
| 4 连通 | 192 | 194 |
| **水平长直 ≥30 块** | **26** | **32** |
| 最长水平长直 | 403 块 @z=608 | **403 块 @z=608（未变）** |

开启后**新增** 141 块@`z=96`、93 块@`z=-416`、75 块@`z=96`、60 块@`z=-88` 四条横向长线。

**机理**：自汇原本会提前截短一条链；改成 `DEAD_END` 后该链继续走，
在**别处**与横向河段相汇 ⇒ 把"一处自环"转化成"多处横向串接"。
⇒ 自汇在本实现里是**与 `nearbyJoin` 相对距离判据耦合的稳定机制**，不是无害记账错误。

**结论**：必须**先修缺陷 B（边框 NEARBY 假汇流）**，再回头评估 A；顺序反了会白做。
默认产出逐位不变（关闭态 PNG SHA256 与基准一致 `AF32864E…`）。
详见 [修复 A 记录](handoff/GeoGenesis-selfjoin-fixA-20261022.md)。

### 〇w、修复 B 取证：找到了机制，但修不动（2026-10-22）

**先修正我 〇u 里的说法**：`z=608` 那条水平长直**不是"多条折线在边框相汇"**，
而是**单条折线自己横走**（`poly#0` 在该行有 412 点、跨 294.5 块；
其余 8 条折线各仅 0.1~3.3 块）。格路径层同样：`reach#0` 独占该行 128 节点、292 块。

**机制（窗口裁剪伪影）**：`z=608 = oz+(n-1)·STEP` 是窗口最后一行。`reach#0` 沿
`x=-458` 南下到该行后改为横走（`[275](-458,608) → [276](-456,608) → … → [327](-354,608)`），
而同一段**窗外确实更低**（x=-456：内 64.315 / 外 64.296；x=-386：64.131 / 64.115）。
`vectorTraceNext` 的 8 邻越界检查跳过窗外候选 ⇒ 只能在行内找更低格。

**为什么修不动**：实现了"边框处优先走窗外续流"（`FRAME_ESCAPE`），实测：

```
start=(-458,608) h=64.321 seaLevel=63.000
走 92 步后停在 (-366,653) h=63.834  reason=NO_DESCENT
```

从 `(-366,653)` 再走 **0 步**即无下降（8 邻全更高）⇒ 窗外那条路终止于**真实洼地**，
高于海平面却无处可走 ⇒ `continueOutside` 如实返回 null ⇒ **开关开/关产出一致**。

**⇒ 真实结构是两层叠加**：① 窗口裁剪（行内横走）+ ② 洼地缺出口。
只修①不够。与 `DeadEndCauseProbe` 一致：18/20 的 DEAD_END 是真实局部极小。

**下一步应由盆/溢流出口入手**（量化那 18 个真洼地与 92 步终点的鞍部抬升分布，
判断 `MAX_SPILL_RISE=12` 是否过严，或是否真无出口），而非继续在追踪层打补丁。

`FRAME_ESCAPE` 默认关闭；`FrameEscapeProbe` 输出 `[KNOWN-UNFIXED] 127 步 ≈254 块`
并断言"缺陷仍存在"，不谎报通过。详见 [修复 B 记录](handoff/GeoGenesis-fixB-frameescape-20261022.md)。

### 〇x、洼地出口量化：三个假设全部否证（2026-10-22）

用户问「以前解决过的为什么又要修」。回查 legacy `RiverLineNetwork.spillCell` 确认两处真实差异：

| 项 | legacy | 当前 |
|---|---|---|
| 出口判据 | 填洼面 ≤ spill+0.05 | `strictD8Next>=0` 且不在 `seen` |
| **抬升上限** | **无** | **`MAX_SPILL_RISE=12`（自造）** |

但实测三个假设**全部不成立**：

| 假设 | 实测 | 结论 |
|---|---|---|
| 12 上限误杀 | 113 个局部极小**全部** ∃ 更低外邻，最大抬升仅 **1.596** | ❌ |
| `!seen` 泄漏出口 | 现状 **113/113 (100%)**；放宽后同样 113/113，漏 0 | ❌ |
| 放宽上限能修断流 | 12→20：DEAD_END 3→1，但**水平长直 26 条一条未少**，`z=608` 的 403/225 块原样 | ❌ |

⇒ **`z=608` 水平长直与洼地出口无关**，我此前"洼地缺出口"的推断错误。

抬升上限确实会误杀 2 条链（(-290,-96) 抬升 17.301、(-374,-96) 14.386），
但那是**另一件事**，不影响长直。另注意 (-608,606) 抬升仅 **0.008** 却仍判死
⇒ 除上限外还有别的终止路径，待查。

**仍未解释的环节**：窗外更低、`spillCell` 也能找到出口（100%），
为什么 `traceOne` 在该行选择"行内横走"而非"南下/出窗"？
下一步需对 `reach#0` 索引 275~327 逐步插桩，打印 `vectorTraceNext` 候选集、
`nearbyJoin` 命中与 `spillCell` 结果。详见 [洼地出口审计](handoff/GeoGenesis-spill-rise-audit-20261022.md)。

### 〇y、生产图口径与边界补覆盖回归的隔离归因（2026-10-22）

用户质疑“以前生产图没有这些跨区 bug”。本轮仅在 `build/render-authority-audit-20261022/`
建立源码/配置副本与独立 classpath 对照，未改生产源码、原 TOML 或存档。

- **先纠正 〇q 的验收结论**：闭区间查询的确增加了覆盖，但不能据此称修复已验收。
  历史图 `prod-1792672633686` 的 z=608 行只有14个河色像素、最长3块；
  闭区间改动后的 `prod-1792674142987` 为629个、最长403块。
  重比两原图：1409变化像素全部新增河色且全部位于既有接缝行列，删除0。
- **单变量因果对照**：从当前 provider 副本只撤回四个边界比较符（`<=`→`<`、
  邻区过滤`>`→`>=`），其余源码/系统参数相同。默认侵蚀回退口径下，
  z=608、x[-512,-161] 的352列：改前1列、改后348列满足实际
  `GeoGenesisTerrain.getChunkCells` 的河水条件及 `floor(水面)>floor(地面)`；
  新增347、删除0。两臂南北两区完整 shaped 折线 CSV 逐字节相同。
  因而是**既有边框几何被查询放行**，不是补覆盖改动重建了不同路线。
- **不是该段PNG误着色**：上述348列中，`erosion>0`、provider湿列、合成后湿列与整块
  放水条件计数相同，绘图假湿0。全图旧绘图判据仍不正确，不能把这一个样本推广为全图无误。
- **窗内选向裁剪已复现**：reach#0在边框的126个可比较步，原函数所选全部等于实际后继；
  保留同来向、同窗内bed、调用同一 `vectorTraceNext`，仅补齐真实窗外采样后，
  125步改为z>608。另1个局部无下降点单独记录，不能把它计入向量成功样本。
  例如(-458,608)原选(-456,608)，补候选后选(-458,610)，床面64.321423→64.302253。
  当前只在无窗内后继时才尝试窗外续流（`FRAME_ESCAPE` 默认关），故有行内候选就继续横走；
  该段不是 `spillCell` 失败导致选向。
- **真实TOML复验**：复制原配置后交Forge加载，loaded=true，cascade=0.5、gully=0.65、
  lakeHi=0.0、HS=2；原文件未变。相同352列改前1、改后161，新增160、删除0；
  南北折线在各对照臂仍逐字节相同，假湿0。配置改变了路线形态，但查询回归并不只存在于
  defaults口径。该配置是“当前TOML新建世界”口径，不等于已核对任何旧存档冻结参数。
- **旧出图不是完整实机图**：`ProductionViewProbe` 使用 `TerrainParams.defaults()`，
  不加载TOML；侵蚀fallback cascade=0.8（TOML0.5）、gully=0.7（TOML0.65）。
  河色仍按`erosion>0`，湖色按`lakePlan`，绕过最终地形合成；metrics还把实际STEP2写成4。
  最近 `prod-1792681454023` 又带`maxSpillRise=20`，属于非默认参数实验，不能标默认生产验收。

证据：上述build目录的`verification.json`、`current-final`/`preband-final`、
`current-toml`/`preband-toml`（summary、逐列CSV、折线CSV、vectorCSV及日志）。
初版observer因把一个真实无下降步当不可能而exit1，保留其日志；修正为单列记录后四臂均exit0，
独立Python核对计数/几何/实际后继/原件哈希全部通过。没有用放宽生产断言或改路由取得结果。
本轮未修复、未提交/推送、未跑Forge落块/流体tick或完整门禁，也未新出1401²图。
下一决策应将“旧图同参同判据”与“边框路由/几何发布范围”分开处理；
仅删掉查询新增行会恢复旧观感，但不是窗外连续性的根因修复。

### 〇z、出图同参/放水口径修正与完整A/B基准（2026-10-23）

用户批准继续。先只改既有diagnostics `ProductionViewProbe`，未改任何main路由：
- 默认读取当前run TOML，通过Forge spec加载后用`buildParams()`；原文件只读，输出目录保留
  原始`config-input.toml`、校正后的`config-effective.toml`及完整参数。缺失/加载失败直接非零退出，
  不静默回退defaults。配置SHA、有效STEP/REGION/HALO/HIER/跨区开关/溢流上限、五类字节码SHA与来源
  和显式系统属性写入`provenance.txt`。只表示当前TOML新建世界，不读取旧存档冻结参数。
- 调用游戏共用的`GeoGenesisTerrain.getChunkCells`，灰底用合成后地面；水色镜像Generator的
  riverWater/ocean/waterTop/lipBlock与世界高度范围，必须真实整块水柱区间非空；湖标志本身不造水，
  瀑布水幕纳入。固定色标min0/band8，不再自适应；删除旧STEP4和全区湖格“保留率”的错误报告。
- 维持原seed/cx/cz/radius调用方式及1401²默认规格；第五参可指定TOML，`--self-test`为纯自检。
  当前TOML由Forge校正`corrected=true`已如实标记；原TOML SHA仍`8dc96823…c223e3`，字节未变。
- Gradle diagnostics编译、原`runProductionViewProbe -PprobeArgs=--self-test`通过：14边界用例+
  2000固定随机逐层放置oracle。两个build-only变异（去riverType门、去整块floor门）均编译成功后
  指定AssertionError真实红；缺TOML负控非零退出且无PNG。初次自检低于基岩用例忘记清除之前的瀑布lip，
  真实报红后修正fixture，并独立保留“正lip确有水幕”用例，不改生产逻辑迎合错误期望。
- 当前完整图：[production.png](forge-1.20.1-47.4.10-mdk/build/hydro-production/prod-1792685034524/production.png)，
  8281chunk/0未覆盖，dry1382651、sea452635、lake91160、river36355；4连通229是像素统计，不当拓扑验收。
  `columnsSHA256=5d60346cddcd9394f30041c46985e50af578d74c1faab98a2c11514e56d9ab6c`。
- 同一新渲染入口、同TOML/seed，classpath前置〇y的preband副本（仅撤四个查询比较符）完整图：
  [production.png](forge-1.20.1-47.4.10-mdk/build/hydro-production/prod-1792685126377/production.png)，
  8281chunk/0未覆盖，dry1383927、sea452641、lake91160、river35073。它是隔离归因对照，不是生产回退。
- 两全图实测1282像素不同，全部在x=-416/96或z=-416/96/608；当前新增河色1282，其中6列在对照中
  为海色。湖色无变化；z608当前442河色像素，对照15；长横段当前225/152/64块，对照无≥30块段。
  两图已目检：当前仍有横线，对照仍有竖河齐截断头。新口径下同样证实“补覆盖放行边框几何”，
  不能用旧图与新图的总湖/河数量差直接宣称形态改善（配置、判据、灰底都已修正）。

验证日志/反控/全图比较在`build/render-authority-audit-20261022/`的`render-*`文件；
新入口仍非Forge方块放置/洞穴/装饰/tick验收。未改生产产出或缓存schema，未提交推送。
边框追踪/接力与几何发布衔接尚未修，不把本轮渲染正确性自检的绿灯当水文整体通过。

### 〇aa、边框显式交接候选被完整端口审计拒绝（2026-10-23）

用户批准继续衔接工作。本轮生产源码、原TOML与缓存schema未改；实验全部在
`build/boundary-handoff-20261023/`，原件副本与日志保留。

- 当前真实TOML三个owner(-1,0)/(-2,0)/(0,-1)只读审计：已发布路径上249个位置，
  补齐窗外候选后同一向量函数会选出窗步，249个位置均无同格出口种子且接收方无该种子。
  225步原局部向量选择等于实际后继，其余24步不能当向量分支证据（可能JOIN/溢流等）。
  结果是“当前没有在这些位置交接”，不是应当制造249个独立端口，也不是证明无限接力完成。
- build副本候选：在nearbyJoin之前识别完整候选会出窗的边框格，显式记BOUNDARY_EXIT；
  出口收集与网络有根过滤一起识别该类型。未携动量、未换盆规则、未裁几何、未改上限。
  候选完成1401²生产合成图，8281chunk/0未覆盖：湖91160不变，河36355→38924，
  4连通229→232；z608河色442→72，仍有47块长段。候选图：
  [production.png](forge-1.20.1-47.4.10-mdk/build/hydro-production/prod-1792686623967/production.png)。
  这是**隔离候选图，不是当前默认生产图或通过验收的修复**，已亲自目检。
- 端口审计真实exit1：三owner pass1共66种子，其中6个不在发送方最终格路径中；
  9个在接收方既无格路径/后继，也不是湖海。此审计只证明66端口样本的缺口，
  已有接收格不代表全链路自然结束。没有用“横线少了”覆盖该失败，候选拒绝接生产。
- owner(-1,1)实际接收表：(-460/-428/-400/-394/-340/-304,608)六条入流分别追55/53/43/40/29/33点，
  最后同到(-362,652)，均NO_RECEIVER→ORPHAN_DEAD_END整条回滚；独立冷运行重复一致。
  该点bed=63.833772000345874，插桩确认`spillCell=-1`、strictVisited仅2或3项，非超过12抬升门。
- 独立洪泛复核该点：现行4096展开用尽，frontier320/seen4416；4093次最低下降后继已在seen，
  这只是现行判据与搜索状态的观测，不单独证明删除seen可修。另用累计max crest的搜索找到
  149节点/148相邻step2边的完整见证到(-138,654)@63.80109858502022，
  crest64.47363773783941、抬升0.6398657374935368，展开5565。Python复核无重复、全在接收窗内、
  邻接与最高点/更低终点均成立。该见证不是生产湖面/净出流裁决，也不是“预算升到5565即可通用”。
- 当前`spillCell`只返回一个远处格，trace直接跳到该格；因此有序见证、耗尽状态与窗口接力
  必须一起设计，不能仅加预算、仅发种子或把BOUNDARY_EXIT当全球合法终点。

本轮未合入候选、未提交推送、未跑Forge/完整门禁；下一步需确认是否扩大到“显式边框接力+
有界有序溢流见证”的一体修复，pass1/pass2端口一致和接收链是否到水体必须作为失败判据。




### 〇ab、获批边框/溢流一体候选触发停止条件（2026-10-23，未迁生产）

用户已批准 `.openbitfun/plans/hydro-boundary-spill-handoff.plan.md`。本轮只在
`build/boundary-integrated-20261023/` 隔离实现和验证；**候选未通过，不迁生产**。
下述更正覆盖该目录代理报告中过度的 PASS/闭环表述，原报告、源码、日志均保留不删除。

- 已冻结真实 TOML、两生产源码、旧66端口表、六入流表及149点见证，清单为该目录
  `manifest.json`。四 owner 的真实 bed 数组导出成功。两生产源码和原 TOML 复核仍与冻结
  SHA256 相同，缓存保持106，MAX_SPILL_RISE保持12；本阶段未修改生产行为。
- **溢流首版被否证**：`spill/src/.../SpillSession.java` 找到第一个候选立即认证，未结算
  同 crest 的剩余前沿，`edgeContacts=0` 不等于边界不可达。其83项绿不得当作有效出口证书。
  `spill/crest/` 第二候选增加同 crest 认证阶段；主线程独立编译并跑47项组件检查、8起点
  正逆序/重复/三线程检查以及 Python 阈值连通 oracle，均退出0。两个独立冷 JVM 的组件和顺序
  日志逐字节相同；这是搜索组件证据，**不是 provider/跨区/多seed 验收**。
- 真实坑(-362,652)@63.833772000345874：候选路径117点，终点(-178,536)@63.81323928927496，
  crest64.47363773783941、抬升0.6398657374935368，与原149点见证同crest。
  主线程独立阈值洪泛得到84813格连通分量、999个域边格，首个观测边界点(-80,416)；
  域边下界恰等于候选crest，因此按本方案认证规则为 **DOMAIN_PENDING、非ESCAPE证书**。
  这不否定已找到的域内路径，也不证明真实无出口或封闭盆地，更不能当作湖位/水量裁决。
- 第二候选单批4096、累计结算84813/实例域124609、21批；将总上限从旧4096改为域格数
  **不是生产等价预算，也未经准许迁生产**。真实接收守卫、湖/海/JOIN与交叉守卫尚未接入。
  主线程另建新输出目录重编4个源码变异，远跳、耗尽当闭盆、丢累计crest、取消域边判据均
  compile0后exit1，检查到了指定失败断言；最后一个实际只关闭边界裁决，**没有真的删掉排空阶段**。
  证据 `verification-main-1792694547570400800/results.json`，组件PASS不覆盖目标接力失败。
- **端口模块仅部分实现，撤回“84/92闭环”“发送侧100%完整”和“8/8有效反控”**：
  代理92行表的发送粗格锚点存在且为某条reach末格，但没有逐一验证真实shaped几何/湿列；
  8行接收锚点缺失（六个唯一位置，-460/-428各重复两次），均回滚在同一真实坑。
  有一条局部后继/粗lake或sea布尔并不代表到湖海闭环。旧66端口中4个owner+锚点仍无对应：
  (-1,0):(50,-96)、(-290,-96)；(0,-1):(608,-98)、(-80,-608)，尚无同身份替代证书。
- 主线程读代码确证额外接线缺陷：`collectIncomingTyped` 又降为 `List<Integer>`，来向未传入
  `traceOne`（lastDir仍从0开始）；provider调用带base但未传secondPass=true的重载，未决检测
  真实不生效；审计却读pass1本来为空的pending，故“pending=0”无证明力。合成relay只验B有
  锚点，没有要求B再出端口；部分反控无条件throw或把fixture失败当检出，不能计为有效负控。
  两代理已确认停写，不用继续堆测试PASS掩盖接线缺口，也不擅自改递归跨区架构。
- 主线程新目录独立重编端口候选：合成正例exit0，真实三owner审计**exit1**，92端口、
  8个缺接收锚点、旧66表有4项无同owner+锚点对应；原表与新表完整保留于
  `verification-ports-main-1792694494563517500/`。此处故意保留验收非零，不使用代理
  `run-gates.ps1` 将“预期失败”包装为总exit0的结果宣称通过。新真实CSV与代理CSV逐字节相同，
  SHA256为ca702172bf2263111133b466793ea7559ef12f082f94285b4987a20763d45dfa；这仍不是查询逆序证明。
- 冻结计划与现文件SHA不相同：复核时仅frontmatter任务状态不同，正文逐字节相同；
  Todo同步后状态又变化。本次没有用Edit/Write改计划，冻结副本和各时点指纹都保留，
  不能声称全文件哈希相同。组件results记录的是当时指纹，最初整文件断言失败日志保留。

- 主线程独立真实溢流验收compile0、**exit1**：总上限4096时EXHAUSTED，放到实例域上限时
  DOMAIN_PENDING；首版同时重跑仍误报ESCAPE（6821展开、frontier620、edgeContacts0）。完整117点
  候选按世界坐标和hex床面保存在组件验证目录的`candidate-path-NOT-CERTIFIED.csv`，明确非证书。
- 本轮重出当前未改生产同参大图：
  [production.png](forge-1.20.1-47.4.10-mdk/build/hydro-production/prod-1792694573844/production.png)，
  1401²、8281chunk/0未覆盖，湖91160/河36355/海452635，4连通229；PNG与基准
  prod-1792685034524 **逐字节相同**（SHA256128ee5c85be53f494a7fc97121fe801780eb569773509a6ab5d08fc579c4a81f），
  合成列SHA仍5d60346cddcd9394f30041c46985e50af578d74c1faab98a2c11514e56d9ab6c。
  已目检，z608仍442河色、下方长横线/直角未改善。这只是未改生产的复现，不是候选修复图。
  本次并行负载下观测585.8s，不作性能对比结论；真实配置horizontalScale=2.0，坐标/像素1block，
  不将其误报为1wu。原TOML SHA、有效参数和五类字节码指纹均与基准逐字节相同。

**停止结论**：目标真实出口认证、原端口同身份替代、来向消费、真实pass2未决、完整水体后继及
整数湿列尚未完成；因此不迁main、不升缓存、不提交/推送。新provider多seed/独立冷逆序/
并发取消、完整WorldgenGate、Forge放块/装饰/流体tick均未验收。`git diff --check`通过；
现有生产与历史dirty内容均保留，不能拿未改生产图当作候选修复结果。

### 〇ac、边界接力第二轮隔离修复与仍未覆盖的跨窗溢流（2026-10-23，未迁生产）

用户要求继续查因并修复。本轮写入限于 `build/boundary-repair-v2/` 及本节记录；
保留既有 dirty、旧候选、原表和失败日志。生产 main 未迁入本轮候选，缓存仍为106，
原 TOML SHA256 仍为 `8dc968236445a74a75357d84122b61e1f1a54c07500b829276f9b48f42c223e3`。

**已确证并在隔离副本修复：**

- typed 接收首步此前仍可被 PortEgress 抢走；已让发送方约定的首步优先。base 中段合并的
  incoming 也加入锚点 pin。pass2 保留 pass1 粗路径，再追加 incoming，不重追本区源。
- `OrderedSpillSearch` 使用累计 minimax crest 后，在已知最小 crest 子图内按相邻步数及世界
  坐标排序；同 crest 未知边界必须由完整 BFS 层认证距离，不能由 `boundary<=crest` 提前过拒。
  已分离 crest/BFS 父链、保存候选快照、修复 expanded 后自增越界，BFS frontier/层状态跨批保留。
  单批两阶段合计最多4096展开；总工作上限仍为隔离试验的实例域格数，**不等价旧4096总预算，
  未批准迁生产**。EXHAUSTED/DOMAIN_PENDING 均保留未知，不改作无出口。
- 真实坑(-362,652)带实际接收/交叉守卫得到95节点相邻路径，crest=64.47363773783941、
  expanded=13640；六个 z608 入流能被接受或归并。冻结床面 NONE guard 的独立组件为13710展开，
  不能混作同一守卫结果。`search-tests/out/MAIN-VERIFY-237/` 主线程复跑237 PASS/0 FAIL；
  九个源码变异均先compile0再由指定断言判红。真正 mid-BFS 预算测试另以反射确认已进入 BFS，
  不再沿用 cap5000仍处crest阶段、允许空path的无效测试。
- 锚点严比失败包含两类：CR 端点计算偏1～2 ULP，以及11个单节点 BOUNDARY_EXIT 发空折线。
  已让 CR 每段末点精确取 p2，单节点端口发布一个精确点、不造额外线段。发送侧缺点全部消除；
  接收侧四个已落湖的无河折线单列，不把 epsilon 放宽当修复。端口 key 增加 next，唯一后继
  检查纳入虚拟端口出边；五个几何/key变异compile0后指定断言红。证据在 `anchor-evidence/`。
- 旧 `BoundaryRepairAudit.wet` 用无侵蚀 raw carvedGround 比湖水面，误判真实湿湖为干。
  新观测使用同一 GeoGenesisTerrain 内置 provider、getChunkCells 合成后地面及整块水区间。
  (-74,-608)实为湖而非海；接收湖可以不追加河折线，但须真实 anchor→next 湿列连通，
  不能用粗 lake/sea 布尔豁免。
- **真实岸口断水**：湖计划优先分支会压掉已命中的河线，即使该湖岸列 flooded=false、
  放不出整块水也直接跳过河雕刻。(-26,-96)下游在(76,-142)与(82,-144)之间存在5个干格。
  新候选只在“计划湖实际不淹没且原河线半宽真实命中”时恢复原河雕刻分支，不放宽湖洪泛阈值、
  不全湖挖地、不加宽、不增加湖水量。`integrated-1792736719405609000` javac通过；
  `wet-connect/shore-1792736719405609000/` 固定半径4、真实湿列8连通BFS：7原断口及81当前端口
  全量exit0；删除shore分支的编译反控恢复上述5格断口、exit1。主线程在 `main-independent/`
  独立重跑81/81、exit0（5326chunk/390571候选列），并在 `main-negative/` 重跑指定断口1FAIL、
  exit1。此为当前81端口水列样本，**不覆盖消失的旧端口，不等于完整一体修复通过**。

**完整图（均为隔离候选，不是已迁生产的默认结果）：**

- [production.png](forge-1.20.1-47.4.10-mdk/build/hydro-production/prod-1792727491339/production.png)：
  岸口修复前，1401²、8281chunk/0未覆盖，湖90799/河40661/海452661。
- [production.png](forge-1.20.1-47.4.10-mdk/build/hydro-production/prod-1792739429814/production.png)：
  岸口修复后，同TOML/seed/中心(-140,137)/r700/step1/HS2，湖90799/河41128/海452661，
  合成列SHA256=`386304af4a14e19b8dbceb32da26cae552a57c85977b84e58b42d109a5c5489f`。
- 两图均已目检。z608河色442→33，原225/152/64块长横段消失；但图中长直河、局部断头仍可见，
  不能用横线减少替代全部端口验收。湖色相对基准91160减少，未将此宣称湖水量不变；粗湖规则
  未改并不保证整图分类不变。数据见 `render-comparison.json`，不作并行负载下性能对比。

**旧66仍然未通过，根因已进一步定位：**

- `old66-map/` 保留66行、真实旧源重建及逐owner窄插桩。56项精确端口对应；3项是前序保留链
  造成的原间距拒绝（有阻挡节点/trace证据，但尚非同身份下游替代证书）；另7项为
  SPILL_DOMAIN_PENDING（6保留、1短链回滚）。**7项不是预算耗尽，未观察到EXHAUSTED**。
- 7项去重6坑：(-582,582)、(96,6)、(-416,2)、(116,-594)、(158,-604)、(-68,-608)。
  canonical实际列为2 LAKE、4 RIVER、1 DRY（按原owner逐行计）；有水一列不等于链已闭合。
- (96,6)的5×5真实窗外bed与加宽候选逐位相同，一步8邻全部更高，所以向量NONE、不能凭窗边
  强发端口；两步路径(96,6)→(96,8)→(94,10)有低床，却被原窗边未知下界挡住认证。
- 六坑居中STEP2/353²床面窗的 NONE-guard 只读可行性测量，每坑一次batch4096、cap124609：
  均找到3～18节点相邻低床路径，累计展开615；与原窗重叠306153点床面逐位相同。
  其中若干路径需跨原窗且上坡到鞍部，现有“严格下行向量端口+接收首步不爬坡”契约表达不了。
  **未带原 blocked/receiver/交叉守卫、域外无权威水体接收条件，不是生产证书**，也不是批准
  自动扩大域或增加总预算。证据 `old66-map/spill-windows/{searches,owners,work}.csv`。

确定性组件证据 `order-check/out/FINAL/`：旧隔离候选的两seed（含12345）独立冷正逆三owner
粗路径/shape/端口纯值相同，同seed三实例并发纯值相同；实例诊断map为空/非空差异单列，
不冒充诊断完整。搜索取消重试通过；**provider/Pass1Task/几何求解中途取消未测试**。
岸口新候选尚未补多seed完整水列、冷逆序及完整图指纹顺序复核。

**当前交付边界**：本轮不是只留失败候选，已修正多项有反控支持的真实缺陷并出完整图；
但旧66替代、跨窗溢流见证/接收契约仍未完成。不得迁main、升缓存或宣称无限世界闭环。
后续若引入“洼地世界坐标有限搜索域+携crest/剩余见证的溢流端口”，须先明确有限总预算、
权威水体/交叉守卫与pass1承诺复用；不得暗改为递归pass2或全局固定点。完整WorldgenGate及
Forge方块放置/洞穴/装饰/流体tick均未运行；本轮未提交或推送。

### 〇ad、条件D8四区独立发现关卡（2026-10-23，失败；不迁生产）

用户要求实施 `.openbitfun/plans/hydro-deterministic-spill-contract.plan.md`，不修改计划文件。
本次按第一项 `prove-multi-region-discovery` 验证发现边界；**不是更换D8路线的依据**。

**复跑证据：**

- 探针：[MultiRegionDiscoveryProbe.java](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/discovery/MultiRegionDiscoveryProbe.java)；
  runner：[run.py](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/discovery/run.py)。
  在MDK目录运行 `python -u build/d8-spill-contract/discovery/run.py`。
- 最新结果：[results.json](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/discovery/verify-1792744940020789900/results.json)。
  Python语法检查通过；javac退出0；四个独立JVM均复现同一发现缺口并以exit1保留验收红灯。
  两组各自冷运行的粗路径/区域表逐字节相同；计划、TOML、两份生产源码的前后SHA256不变。
- 第一组使用最小fixture参数，第二组改用 `ModelHydrologyProvider.productionParams(1.0)`，
  并启用默认networkFilter；不是仅靠关闭过滤器或minNodes=2才出现的缺口。
- 正坐标A/B/C/D owner为(0,0)/(1,0)/(2,0)/(3,0)；负平移为(-4,0)至(-1,0)。
  只有A有一个准入源，B/C/D无本地源。每组覆盖正序、下游先查、仅查D、仅查C。
  仅查D或C时断言A未被查询、final表只有一项，不能用预热的上游目录冒充独立发现。
- 实际调用候选的 `DeterministicSkeleton.build` 与反射入口 `collectIncomingWires`；
  只将合成pass1产物预置到真实P1_CACHE，不注入pass2出口。床面为解析谷地，parent/discharge/
  lake/sea为合成输入，pipe=null。**不经过真实HydrologyPipeline、shape、columnsFor或Forge**。
  因此这些结果证明装配接口的输入覆盖缺口，不是实际湿列、真实源准入分布或全链确定性验收。

| owner | pass1河段/端口 | 最终incoming | 最终河段/端口 | 未决出口 |
| --- | --- | --- | --- | --- |
| A | 1/1 | 0 | 1/1 | 0 |
| B | 0/0 | 1 | 1/1 | 1 |
| C | 0/0 | 0 | 0/0 | 0 |
| D | 0/0 | 0 | 0/0 | 0 |

正序与下游先查一致，但一致地遗漏C/D。正控只在探针中显式转交每段输出端口，B/C/D均能
继续且D到达合成海；正控绕过了现有发现机制，**不是修复，也不是合法发布证书**。
区域表见 [regions--4.csv](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/discovery/verify-1792744940020789900/production-1/regions--4.csv)。

**调用依赖与覆盖范围（非分层typed路径）：**

```text
P(r) = 本区pipeline + 无incoming的PRIMARY骨架（独立产物）
F(r) = SECOND_PASS(base=P(r), incoming=8邻P(n)中receiver=r的端口)

P(A) → F(B) → 新端口B→C
                 ╳ 该端口不在P(B)，F(C)不读取F(B)
P(B)=空 → F(C)=空；P(C)=空 → F(D)=空

columns(chunk) → F(owner)
              → 边界带按条件借邻区F(n)的已有几何/湖
```

依据候选provider：`solvePass1` 2641—2651行独立求P；`typedIncomingPorts` 2848—2867行只读
8邻P；`solveProduction` 3182—3205行以P为base追加incoming。DS 1254—1256行在base存在时
不重新收源；没有其它入口把B的pass2出口转成C的pass1承诺。窗口halo允许几何重叠，不会新增
发现依赖。`columnsFor` 2198—2234及2256—2278行按边界带借邻区已有几何，亦不会传递新出口。

因此F(r)的源候选只可能来自本区P及8邻P所发出的首级承诺；不能覆盖任意长度接力。对区中心
列（如D的x=1792,z=256，距本区边界大于HALO），列消费者不走邻区补缝，几何候选仅来自F(D)。
这是代码分支的覆盖分析，**未在此合成fixture中实际运行落块**。即使所有区域先前都已查询，
F(C)仍不消费F(B)，所以不只是冷缓存的问题。

**按计划第3节/第6.1节，先返回最小接口修订，不继续堆溢流字段：**

需要区分“给定追踪状态的下一段怎么走”与“目标区如何独立找到所有需要的追踪状态”。
保留条件D8和两趟消费外壳；最小新增职责应为可独立重建的接力发现接口，而非第三套水文链。
建议接口草案（用户已批准隔离验证；尚未形成生产实现或通过完整性认证）：

```text
连接键 = 世界身份 + 共享锚点 + 完整入边/惯性状态 + 溢流事件/游标 + 守卫上下文身份
接续(连接键, 逻辑账本) → 不可变相邻河段 + 出口/权威接收依据 + 新账本
发现(目标core与现有几何影响域, 世界身份, 逻辑账本)
  → 完整连接集合及覆盖证据 / 求解未完成（附未覆盖依赖，不发布为干列）

目标chunk → 发现 → 独立源准入产物 + 接续（需要的前驱状态）
          → 现有两趟装配/shape/columns
禁止边：接续/发现 → 邻区final；禁止依赖运行时已访问区域目录。
```

接口形状本身不是完整性证明。仍须由构造解决：前驱候选如何完整枚举；当前trace循环保存的是
归一化D8格步来向（另有源首步零状态），不能误称已使用连续惯性；但枚举来向仍不能替代
可达性、来源与历史守卫证明。本窗covered/间距/交叉守卫的上下文如何重建；何时有证据停止
反向发现；必要依赖超逻辑预算时如何完成生成。当前源码未给出覆盖半径或跨段总长度界，不能据局部STEP_LIMIT
推导全链长度界，也不能任意扩大邻域直到样本绿。单纯添加证书ID、缓存索引或按坐标挑一个
边界源都不解决上述问题；最后一项还会改变既有源准入，未获授权。

**确认与后续实测：** 用户已选择“隔离验证接口（推荐）”，允许研究并试作独立发现/接续，
仍不改D8、源准入、水量、宽度、惯性或MAX_SPILL_RISE，不增加生产预算。此授权不等于批准
固定反向半径、递归final、世界图或任何渐进/分层完成策略。原计划文件保持不动。

- 新增隔离 [ReverseOrdinaryProbe.java](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/discovery/ReverseOrdinaryProbe.java)：
  状态为世界格+九种来向（八邻格步与源首步零态）。反向按目标入边唯一确定前驱格，枚举该格
  九种来向，调用真实 `vectorTraceNext` 验证出边；仅在**明确有限合成域**中验证普通下行可达性。
  目标跨三个以上合成8格分区，只有两个fixture已准入源；先查下游/中段所得源集合与逐源正向
  走完整域的oracle相同。负平移(-40)与冷逆序状态闭包、展开量相同；裁为本地分区丢掉两源，
  budget=1返回未完成而非无源。另加129×9合成域长依赖，仍无溢流/历史守卫，budget4096时
  返回complete=false（展开4096、transition4311、唯一样点747），不暗中继续。14214项检查
  含大量相邻性检查，不冒称14214个独立场景；此为预算反例，不是提高预算的理由。
- 最新合并runner结果：[results.json](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/discovery/verify-1792746638122821600/results.json)。
  三探针同次javac退出0；普通反向/上下文反例各两个冷JVM退出0、摘要逐字节同；原四区关卡
  四个JVM仍退出1，整体runner保持exit1。生产/计划/TOML指纹不变。两个源码变异（只查本区、
  预算耗尽谎报完成）均单独javac成功后，被指定断言捕获，不拿编译失败冒充有效反控。
- 资源如实分账：一次下游反查展开2349个状态、检查2430个transition、采样调用60750次、
  唯一样点442；中段展开1701。不能把“展开小于4096”说成全部工作预算小于4096。
  本实验含显式fixture边界与已知准入集合，不是生产覆盖范围、总预算或可独立发现的证明。
- 新增 [ContextDependencyProbe.java](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/discovery/ContextDependencyProbe.java)：
  反射实际 `nearbyJoin` 与 `collectSources`。9×9中心格40、同bed/level/来向：无占用时普通
  后继49；covered加入格58则JOIN58；加入穿越连接边的段(5,3)—(5,5)又拒绝JOIN。
  同level/parent/其它掩码仅改discharge由全0到格40=41，生产门限40下源集合[]变[40]。
  四项断言通过；该9×9人工段表仅证明谓词敏感性。另补33×33真实build反例：PRIMARY从
  discharge构建空base/有源base，两者都由同一构建器产生；SECOND_PASS使用完全相同的bed/
  parent/掩码/生产Params和typed入口(4,16)→(5,16)。共享格(16,16)在空base时后继(17,16)，
  有base时提前JOIN(18,16)，对应索引577与610。两项断言通过，补齐“真实构建能产生不同
  上下文”证据；仍为合成场，不是生产地形验收。
- 因此九状态普通选步成立，但不能跳过已有JOIN/交叉/间距/回滚/过滤或溢流守卫。窗内数组不能
  直接跨窗搬运，不代表历史影响无需传递。完整候选入口集合未知时，重放本区build仍不知道
  缺少的incoming，不能当作独立发现修复。第一关仍未通过，未接入证书或迁生产。

**暂停恢复隔离试验（后续批注12“继续”已授权，非生产发布授权）：**

- 新增 [ResumableDiscoveryProbe.java](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/discovery/ResumableDiscoveryProbe.java)，
  接入同一 [run.py](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/discovery/run.py)。保留旧探针与全部失败证据，
  未改生产、TOML或计划。最新合并结果：
  [results.json](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/discovery/verify-1792747836499643000/results.json)。
- 任务身份含fixture seed/config/策略参数/STEP/目标/有限域；恢复时身份与所有资源上限必须相同。
  这是内存中不可变record检查点，保存FIFO、seen、source、样点集合、当前前驱游标、待提交状态、
  累计展开/候选检查/逻辑采样量；**未设计持久化文件格式**。
- 分批1/2/7/4096与一次运行完全同值；每批后清掉transition缓存、恢复到新实例仍同值。
  正/负平移各检验9个前驱取消位置及重复取消：已评估的候选立即记账，保存游标与pending，
  未完成展开不提交到队列/可发布结果；恢复不重复检查也不抹掉已花工作。取消重试证据仅属于
  此单线程隔离任务，**不是provider/Pass1Task或线程并发取消验收**。
- 累计展开、候选transition检查、逻辑采样调用、唯一样点、已发现状态及队列分别限额。
  资源上限前置检查；达到总上限的LIMIT为终态，重新恢复不能重置或增额；PAUSED/CANCELLED/
  LIMIT均拒绝发布source结果。工作上限4096仅用于该fixture任务，不替换或提高生产溢流预算。
- 两个冷JVM各130断言通过、摘要逐字节同。普通样本：2349展开、17001候选检查、425025逻辑
  样点调用、442唯一样点、2349状态。这里候选检查/逻辑采样**含缓存命中**；此前2430与60750
  是旧探针实际cache-miss求值量，不是同一计数，不能直接作性能比较。长依赖4096展开保持LIMIT。
- 六个源码变异均先javac成功再被指定断言抓到：旧本区裁剪、UNKNOWN冒充完成、恢复清展开量、
  丢FIFO、缓存命中漏逻辑计数、丢前驱游标。合并runner编译0，六个组件冷运行退出0；原四个
  四区发现用例仍退出1，整个runner维持exit1。不能拿组件绿覆盖发现验收红。
- 尚缺资源项：frontier峰值、持久证书长度、依赖身份集合与缓存字节量未在该任务完整记账；
  fixture terrain的物理缓存也不是生产缓存策略。已提交逻辑计数不包含重复取消中的实际CPU/
  缓存重建成本，不能宣称现实资源开销相同。只允许在该实验声明的有限域内发布source集合；
  对任意目标的生产发现、历史JOIN/交叉守卫、溢流发现与权威水体接收仍未证明。

下一步仍是补全带上下文的候选覆盖/有限依赖论证，不能把可恢复队列当作跨窗完整发现，
也不能以“分批能跑”代替总预算完成政策。未授权渐进/分层的生产发布或提高总预算。

**未完成：** 多次跨窗溢流/负角点见证、完整反向发现/无环证明、生产证书与旧66账本、新候选
生命周期/缓存淘汰/并发/provider取消、WorldgenGate、完整生产图和Forge验收。第一项关卡仍红，
其余todo保持pending；不迁main、不升缓存、不提交推送。

### 〇ae、本地未完成检查点提交（2026-10-23，用户批注13）

用户明确要求“先提交一下……然后继续”。本次仅本地commit，不推送、不创建PR、不合入隔离候选。
保存既有main/diagnostics改动、诊断runner、10月22日水文交接，以及忽略目录中**逐文件挑选**的
最新候选8份源码、OrderedSpillSearch/搜索探针、四区发现/暂停恢复探针、runner、两份获批计划
原件和一次results摘要。使用显式 `git add -f <文件>` 保留隔离源码，**不修改.gitignore**；
构建classes、PNG、原始TOML、全量日志/缓存不入库。计划正文未修改。

这不是完整实验环境备份：旧候选、冻结bed大表、integrate.py依赖的上游生成目录及大量湿列证据
仍保留本地但未纳入；不可声称clone后全部真实实验已可复现。独立源码可编译的最新候选被保存，
未切换生产到它。早期10月12日 `handoff/attachments` 七份附件不属此次工作，保持未跟踪不删除。

提交前实际复验：
- `gradlew.bat compileJava compileDiagnosticsJava --offline --console=plain`：成功。
- 既有Gradle runner：OutsidePathOrder、HandoffStart、SeamBandBoundary、ProductionView自检：成功；
  后者14边界与2000逐层放置oracle通过。
- `python tools/verify-multiphysics-contract.py`：基线与冷JVM相同；五变异均compile0后指定断言红。
- `python -u build/d8-spill-contract/discovery/run.py`：编译0，六组件冷运行成功，六变异有效，
  四区发现仍失败，整体exit1；本次结果 `verify-1792748238789431900/results.json`已保存入库。
- `gradlew.bat runSkeletonProbe -PprobeArgs=synthetic --offline --console=plain`：**失败22项**，
  具体为flat-detour两项、basin-escape四项、blocked-flat-candidate十六项。未安全基线归因，
  不标“既有/无关”；不放宽断言。此红灯与四区红灯一起随检查点保留，后续仍须查证。
- 完整WorldgenGate、Forge实机均未运行。检查点是保存未完成工作，不是发布验收版本。

### 〇af、提交后继续：带历史JOIN的反向候选范围（2026-10-23，隔离组件）

本地检查点已完成：`3621c863b60664badf32736bd139b6ac4e76fdbd`，73文件；未推送。
提交后继续第一关，不改生产/计划/配置：

- 扩展既有 `ContextDependencyProbe`，反射同一个 `nearbyJoin`。对已知目标，枚举以目标为中心、
  半径=生产joinWindow=2的候选格，每格用给定covered/selfStamp/segments/level执行真实守卫。
  这是JOIN循环范围反演，不是为样本任意加宽D8或扩大区域发现半径。
- 全33×33逐格正查作为独立枚举范围oracle；半径2反查集合逐项相等（本fixture22候选）。
  包含真实SECOND_PASS已复现的(16,16)→(18,16)边；只枚举八邻会漏该前驱。
  给连接边加交叉段后前驱被拒；把目标标成本链selfStamp后全部拒绝。
- 最新 `verify-1792748856684593700/results.json`：编译0，两个上下文组件冷JVM各11断言通过，
  其余组件及冷摘要相同；七源码变异均编译成功并被指定断言捕获。第七变异把反查半径截回1，
  由全格oracle对照抓红。完整runner仍因四区发现缺口exit1，不将其改为PASS。
- 仅给定上下文下的**JOIN候选覆盖**得到验证，不能证明上下文本身独立可得、全体incoming完整，
  也未包含ownSegments二次守卫、PortEgress/typed首步/溢流优先级、跨区溢流或真实湿列；
  不能把候选集合直接当发布河网。下一步仍需统一这些分支的覆盖证据和历史身份。

### 〇ag、批注14后继续：上下文前缀与typed早退校验（2026-10-23，仍隔离）

- `ContextPrefixProbe` 使用65²解析床面、真实 `DS.build` 和 `productionParams`，三个已给定incoming
  按锚点排序。完整清单中的目标粗路径可由base＋较早incoming前缀回放；漏掉较早项会改变目标路径。
  上下文摘要含fixture世界标识/base/prefix/target；**不是生产稳定河身份，也不证明清单完整**。
- 原候选另复现：incoming已落在base覆盖格时，非相邻next＋零方向可绕过typed校验，仍计merged。
  原因是covered/水体早退位于 `traceOne` 中首步/方向检查之前；当趟较早incoming覆盖又直接记COVERED。
- [validate-context.py](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/discovery/validate-context.py)
  只生成新时间戳副本，未覆盖旧候选/main：在covered/水体前预校验typed；合并时actual next必须与承诺相同；
  当趟覆盖也走后继审计。后者的authority包含base＋当趟已保留traces，避免JOIN入base后误判无后缀。
- [ContextValidationProbe.java](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/discovery/ContextValidationProbe.java)：
  20个非法组合（五种payload×base/空base×水体/非水体）、合法普通/水体/base合并、当趟相同/冲突后继、
  当趟JOIN入base共50检查。最新两冷JVM摘要逐字节同；基线与四次单项撤修均先compile0，再指定AssertionError红。
  这里只认证局部payload及声明上下文中的后继，不认证跨窗共同后继或全链无环。
- 合并发现runner最新证据
  [results.json](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/discovery/verify-1792751388622267700/results.json)：
  四组件各两冷JVM通过/摘要同；九源码变异均compile0后命中指定断言（新增漏前缀、身份漏前缀）。
  四个多区反例继续exit1，runner整体exit1，`requiredDiscoveryAcceptance=false`。

### 〇ah、最终shape也依赖较晚入流，不能发布前缀结果（2026-10-23）

- 新增 [ContextPublicationProbe.java](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/discovery/ContextPublicationProbe.java)。
  在上述修复副本调用真实build＋shape：first锚点290，later锚点420；later是同一粗路径上合法相邻承诺，
  被明确合并且无reject。完整与prefix的粗路径/终点逐位相同，最终shape却由65节点变67节点。
  把完整traces配prefix pins后又与prefix shape逐位相同，单变量定位为later新增钉点，而非D8选步变化。
- 原因：`shape` 在所有trace处理前先读完整 `anchorPins`；后来合并的入口也能把较早链内部锚点钉住，
  改变粒子偏移及CR重采样。粗路径只依赖排序前缀，不代表最终几何也只依赖前缀。
  **因此前缀回放不能单独作为可发布几何/湿列接口**，必须先得到完整合法钉点依赖及覆盖证据；
  不以丢钉点、冻结首到者或禁用现有shape机制换绿。该实验是新接口候选的反例，未证明现有固定清单的运行时查询顺序不确定。
- 两冷JVM均6检查、摘要同。另在STEP2、原点(-29999000,-29999000)及(29998000,29998000)
  的合法MC范围内复现（节点128→130）；不是完整坐标/溢流/窗口转换验收。
  去掉shape钉点消费的源码反控先javac0，再命中预期反例断言。最新组件证据：
  [results.json](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/discovery/context-validation-1792752195520210900/results.json)，
  wrapper exit0只表示局部修复及反例检查符合预期，**不覆盖主发现exit1**。
- 真实provider回归：复制原run TOML再由Forge SPEC载入（Forge可能纠正副本；原件不变），seed沿正典。
  owner(-1,0)/(-2,0)/(0,-1)三处骨架分别66/78/151、端口23/24/34，修复后reject/unsatisfied均0。
  原候选与修复副本的edges/shape/汇总逐字节同；修复副本独立冷JVM正/逆owner查询的上述三表及attempts亦逐字节同。
  [results.json](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/discovery/real-comparison-1792751967522697900/results.json)。
  修复源码SHA256为 `742bb48889d602b3c1b43c49b6003db9833dd34daf494daf01303e3e84f57376`，与最终组件批使用副本逐字节同。
- 本轮生产DS/provider、原TOML、计划文件指纹不变，未迁候选、未升schema、未再提交/推送。
  新实验文件仍在被忽略的build目录，普通git status不会列出，不能当作文件不存在或已经入库。

**当前接口边界与下一步：** 已知完整incoming清单时，可按生产顺序重建追踪上下文；发布还要完整pins。
但从目标独立枚举这份清单仍未解决：普通D8九来向＋给定上下文的JOIN范围只覆盖局部前驱，
不覆盖溢流/历史守卫/全链来源；排序前缀在单窗内递减也不能充当跨owner依赖的全局进展量。
不得递归邻区final、反复pass2、靠热缓存目录或扩大固定半径代替。第一关仍in_progress，其余五关pending；
旧66全账本、跨窗溢流/无环、完整湿列、provider取消/并发/淘汰、完整WorldgenGate、大图/Forge未验收。

### 〇ai、批注16：参考机制回查、普通端口候选覆盖与预声明钉点试验（2026-10-23）

用户要求继续，并建议查看参考。并行只读研究后回源核验，沉淀至
[HYDRO-REFERENCE-RESEARCH-20261014.md §7](HYDRO-REFERENCE-RESEARCH-20261014.md#L162)。
Streams可借“邻段出口不依赖整条上游、最终几何仍须完整入流”的职责分开；PL-RGA以禁止跨区闭合，
不能照搬。未复制参考代码，未改变D8、源准入、水量、宽度或生产预算。
legacy归档 `RiverLineNetwork.region` 同样只消费8邻pass1；这能保留其跨缝经验，但不能从接口外形推出任意多跳证明。

**普通端口的必要候选集合（不是有效入流集合）：**

- 新隔离 [PortCandidateCoverageProbe.java](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/discovery/PortCandidateCoverageProbe.java)。
  发送窗轴范围 `[s·REGION−HALO, s·REGION+REGION+HALO]`；普通获选next至多再走STEP。
  与目标owner core `[r·REGION,(r+1)·REGION−1]` 求区间相交，用long乘加与floorDiv/ceilDiv推发送owner范围。
  当前REGION512/HALO96/STEP2推得每轴r−1..r+1，排除自身为8邻；**是推导，不是扩半径碰样本**。
- `PortEgress` 只有发送窗边框才可能返回端口；对边框去重的1408格×九种来向跑真实egress。
  九态适用当前普通D8/typed/相邻spill步的归一化格步来向及首步零态；不声称覆盖未来连续惯性。
  这里不消耗生产求解预算，也不把检查数量藏作“4096以内”：实验含全格oracle共18146448次谓词调用、
  405504次边框patch评估；每窗bed全采样，未实现生产稀疏采样或累计暂停记账。
- 正平移与负平移两个fixture各707条候选边，边框枚举与全353²×九态egress oracle逐项相等。
  真实DS.build从A输出显式传递给B的诊断正控产生的B→C端口在候选中；原C的pass1-only装配仍incoming=0。
  这只证明必要候选不漏该普通端口，不证明它的来源、守卫、实际存活或可发布。
- 接入既有runner：五组件各两冷JVM通过/摘要同，11源码变异均compile0后命中指定断言
  （新增漏东边框、漏西发送owner）。证据：
  [results.json](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/discovery/verify-1792753668719984700/results.json)。
  四个原多区用例依然exit1，runner整体exit1，生产DS/provider、原TOML与计划指纹不变。
- **边界：** 以上是当前普通egress的候选覆盖；跨窗认证溢流目前尚未定义出口语义，不能外推；
  也未检查每条候选的source reachability、历史covered/segments、回滚或完整pins。候选集在无准入源时也可非空，
  禁止直接把这707条候选当成河起点，否则会新增无来源河。

**实窗钉点反例与候选解耦：**

- 新隔离 [ProductionFramePinProbe.java](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/discovery/ProductionFramePinProbe.java)，
  不再用任意内部WireIn：REGION512/HALO96/STEP2，西owner(-1,0)真实egress给(96,90)→(98,90)，
  北owner(0,-1)真实egress给(116,96)→(118,98)，均指owner(0,0)。接收端build合法合并、拓扑同，
  later钉点仍改变earlier shape。发送端只是egress候选，**没有生产源可达证书**。
- 参考共享node单一权威的职责，试验把本窗与候选发送窗所有重叠边框格预声明为格心钉点，当前2808点。
  同一套declared pins用于prefix/full，反例中的earlier shape逐位相同；不根据实际incoming到达决定坐标。
  它仅传给shape，未新增河/改build，且**确实改变旧shape**；不能声称保留原形态或已消除全体拓扑依赖。
  来源/covered守卫仍未解决，跨区两侧完整shape、真实大图/湿列未验收。策略尚未采纳到候选或main。
- 最终组件批 [results.json](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/discovery/context-validation-1792754188912713800/results.json)：
  50项局部校验、65²反例和实窗反例/候选策略各两冷JVM通过且摘要同；两大坐标STEP2旧反例通过；
  忽略pins与清空declared策略两反控均先compile0，再命中预期AssertionError；四项撤修反控继续有效。
  wrapper exit0仅为组件/反例验证成功，不能覆盖发现主runner的exit1。

下一步：在不把潜在端口当真实河的前提下，验证来源与有序历史上下文的有限依赖；若跨owner回到依赖中，
须区分“任务互依”与“水流环”，不能用删依赖/判干/重复pass2修饰。预声明钉点需独立真实形态对照后再决定是否采用。
仍停第一关；证书、旧66、完整无环/湿列/生命周期、provider并发取消与生产迁移均未完成。
新实验仍位于ignored build，未暂存/再提交/推送；未动计划/TOML/缓存版本或生产源码。

### 〇aj、继续来源取证并出图：前缀存在性与真实生产路径钉点对照（2026-10-23）

**来源与前缀试验：**

- 新增 [PortPrefixEvidenceProbe.java](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/discovery/PortPrefixEvidenceProbe.java)，
  用353²实际build、productionParams、空本区base及显式提供的三条incoming。正/负平移各有一条新端口链。
  按**产生该链的incoming起点**截取排序前缀，可逐位重放目标粗路径/端口；删除较晚项不改变该端口。
  出口锚点与链起点不同，不能按出口锚点截前缀。pass1基项还包含湖outlet，不可只算collectSources。
- 新增反例：同一条第二incoming独立追时发端口，在完整清单中却JOIN且无自身端口；
  **缺少更早上下文可增加假出口，不只是漏河**。因此逐条独立追踪再取并集不能作为合法发现。
- 38项检查覆盖上述两个平移，仍为显式供给清单的局部命题，不是来源独立发现/闭包证明。
  没运行真实源枚举、跨owner历史闭包、完整pins/湿列或溢流认证。不能把前缀PASS用来宣称四区已修。
- [validate-context.py](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/discovery/validate-context.py) 最新批：
  [results.json](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/discovery/context-validation-1792756737073329600/results.json)，
  四组件各两个冷JVM摘要同、全compile0/run0；撤修/钉点反控有效，新增漏目标wire、删除上游清单反控均先compile0再指定断言红。
  过程中 `context-validation-1792756406953620200` 因并行JVM争用默认Log4j日志、系统GBK异常文本导致runner解码中断，
  **不记验收成功**；后续改为各批console-only UTF-8 Log4j配置，保留旧失败证据且复跑通过。

**按用户要求补图（生产路径，隔离候选策略对照，不是迁入游戏）：**

- [render-context.py](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/discovery/render-context.py)
  新建时间戳副本，只修改shape前置钉点：世界坐标mod512为96/416的行列预置格心。
  这是当前512/96/STEP2布局下的实验策略，不是通用参数化实现；不改D8/build/源准入/宽深。
- 两臂均重新编译DS及原ProductionViewProbe，自检14＋2000放置oracle成功；冷JVM分别走
  `GeoGenesisTerrain.getChunkCells → HydrologyChunkEngine → 候选ModelHydrologyProvider → shape/columnsFor`，
  原TOML只复制到输出目录，Forge校正副本，两臂effective配置逐字节同。真实生产入口目前model恒真，
  TOML中旧 `hydrologyModelPipelineEnabled=false` 已不是开关（HydrologyChunkEngine:12-17/35-39），不能据旧文档判图走legacy。
- seed9139912035078620160、中心(-140,137)、r700、step1，两个1401²图各8281chunks、uncovered0，固定无光照等高线色带。
  基线 [production.png](forge-1.20.1-47.4.10-mdk/build/hydro-production/prod-1792755953098/production.png)：
  river41128/lake90799/sea452661；预声明钉点
  [production.png](forge-1.20.1-47.4.10-mdk/build/hydro-production/prod-1792756330348/production.png)：
  river41113/lake90799/sea452661。变化59像素，新增河22/减少河37；湖/海着色相同，4连通分量两者233。
  这不证明逐列地面/水面不变，两个columns SHA不同，也不证明跨区流体连通。
- [全幅对照](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/discovery/render-1792755950235852400/comparison.png)、
  [原图裁切放大](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/discovery/render-1792755950235852400/detail.png)、
  [变化像素](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/discovery/render-1792755950235852400/difference.png)、
  [results.json](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/discovery/render-1792755950235852400/results.json)。
  人眼检查：全图长直/非湖海终点仍可见，钉点策略不修此现象；局部只有少量1块偏移，没有据图宣称形态改善。
- 出图的两个Java进程均exit0；初次Python后处理因本机Pillow7.2不支持Image.Resampling失败，保留日志；
  改用Image.NEAREST并只恢复既有原图后处理成功，没有重跑/修改地形产物。恢复前核对DS源码与原日志FILE/metrics。
  首次渲染的编译类路径与完整指纹在各自provenance中，非合成床面/假端口渲染。

**当前状态：** 本轮主发现runner未改语义，最近四区主验收仍为〇ai的exit1；未证明完整来源闭包。
预声明钉点仍未采纳进生产，未改main/原TOML/计划/schema，未再次提交推送。
下一步继续验证真实来源与较早incoming的依赖是否有限闭合；现有局部前缀命题不允许递归邻区final或重复pass2。
Forge方块/装饰/流体tick、旧66全账本、跨窗证书/无环及生产迁移仍未验收。

### 〇ak、按用户要求收敛回现有D8：生产输入对照、短段图与实际停止原因（2026-10-23）

用户明确要求：底座没换，不要按重建水文推进；继续现有条件D8，几格独立短河可舍，但不能切断长河的局部短连接。本轮不改main、源准入、湖水量、宽深、惯性、生产预算、原TOML、计划或schema；未提交推送。

**先纠正此前反例的适用范围，不删除原红灯：**

- 原四区fixture直接供给流量：只有A一个格超过门限、B/C/D所有格Q=0，parent亦人工设置。它有效证明“只有远端源、无本地承诺”这一接口条件下两趟不能多跳；但未证明真实管线必然产生此条件，不能据此直接授权另一套世界图或通用反向发现系统。
- 扩展既有 `MultiRegionDiscoveryProbe.java`，默认原反例原样保留；新增隔离对照：①同parent用实际RiverNetwork逐格径流累积；②解析床面最陡D8 parent＋实际累积；③现有HydrologyPipeline完整计算bed/水面/parent/流量/湖，再交既有build与真实incoming装配。第三臂仅经既有Layer接口注入解析谷地、禁止侵蚀预热，不是实际噪声地形或生产provider全链验收。
- [verify-physical-flow.py](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/discovery/verify-physical-flow.py) 最新 [results.json](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/discovery/physical-flow-1792761074467912100/results.json)：原反例两冷JVM均exit1；三个流量对照各两冷JVM均exit0，正/负四区、下游只查D、正逆查询一致。完整pipeline臂按精确共享anchor/next连接，能从A沿942个粗图访问节点到海；删除跨owner连接负控命中指定断言、exit1。粗图非逐列湿连接，不能据此宣布所有地形独立发现通过。
- 原主runner另复跑 [results.json](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/discovery/verify-1792761091189572400/results.json)：四个原反例仍exit1、五组件各两冷通过、11变异均编译成功后指定断言红，整体exit1。没有用新正控覆盖原红灯。

**真实provider与短段，只读审计：**

- [短段完整标注图](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/short-river-audit/run-20261023-204333/short-river-full-1401.png) 沿同正典seed/config，实际候选provider导出全部16个图涉owner，再叠加到既有1401²生产合成底图；不是新模拟，不是已迁生产的修复图。
- 1367条trace包含各owner halo，不等于去重河数。355条小于14节点：189湖口、166跨区；图内各自core可见86条（54湖口、32跨区）。E1只有3节点/7.13块，但粗连接图已有521个上游节点、下游还能追154节点到海。短trace不能直接剪。另有98条本地源到首汇合点短分支候选，全球来源完整性仍UNKNOWN，未据此删河。
- 16owner实际PRIMARY/FINAL端口按完整key及receiver/方向载荷直接比较：284=284、差集与载荷差异均0，不依赖pending=0推断。内部206个接收（191 exact-next、15水体元数据），其余78个UNKNOWN恰好全部是样本范围外receiver；水体元数据不是湿列证书。
- 独立冷同序重建148文件相同（summary仅排除计时）；原始trace/node/shape逐字节一致。证据 [summary.json](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/short-river-audit/ports-cold-1792760505035414600/summary.json)。seed12345三个owner额外44端口仍P1=FINAL；seed42同位置为空，不作有河正例。证据 [summary.json](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/short-river-audit/seed-ports-1792761190530183700/summary.json)。有限样本没找到second-only，不等于不存在；停止继续扩建通用发现层，优先真实断头。

**真实停止原因已取证，下一步的具体修复靶点：**

- 16owner快照有26条UNRESOLVED、3条DEAD_END（含halo重复），不是短段门限问题。本轮对五owner现有搜索调用只加观测；不改守卫/域/算法/上限。编译、provider退出0，最终15份trace/node/shape表与无插桩基线逐字节同，保护文件哈希未变。
- [audit-spill-reasons.py](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/discovery/audit-spill-reasons.py) 记录1980次调用（包含pass1依赖和重复，不是1980条河）：1779 ESCAPE、96 JOIN、103 DOMAIN_PENDING、2 NO_ESCAPE，未见EXHAUSTED。
- 五owner最终14条问题trace按origin/source/pit逐项匹配：13条DOMAIN_PENDING、1条NO_ESCAPE。后者湖口(-598,-148)走171节点到(-388,-200)，现有守卫下展开56即NO_ESCAPE；不是预算耗尽，也不能据此宣称真实闭盆。13条未决包含7次展开就停的边界样本(-420,-222)，不应加预算或当无水。
- [逐条停止原因](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/discovery/spill-reasons-1792761442680538800/final-terminal-reasons.csv)、[完整地形停止点图](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/discovery/spill-reasons-1792761442680538800/actual-stop-map-1401.png)、[插桩等价验证](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/discovery/spill-reasons-1792761442680538800/results.json)。图只标五owner本core内终点，其余位置没圈不等于无问题。
- 后续在原D8溢流分支逐个查上述真实未决/守卫死端，复用既有OrderedSpillSearch与接收机制，不另建世界图、不递归final、不凭看图删河。尚未实施行为修复；跨窗认证、旧66、完整湿列/生命周期/无环、WorldgenGate及Forge仍未验收，第一关仍in_progress。

### 〇al、继续实际断点：排除守卫误杀，修正同层边界过拒（2026-10-23，隔离局部修复）

用户批准沿〇ak继续，不加预算、不删河掩盖断头、不换底座。生产源码、原TOML、原计划、缓存schema与既有副本保持未改；本轮仅新增/扩展build隔离探针和修复副本，未提交推送。

**NO_ESCAPE真实原因，不再猜守卫：**

- [SpillGuardProbe.java](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/discovery/SpillGuardProbe.java) 捕获真实(-388,-200)的同一bed/self/receiver/边交叉守卫，逐项撤守卫只作诊断，不将重放结果送入河网。actual展开56，关闭自身占用后63；关闭own/other交叉或全关仍NO_ESCAPE，边界Infinity，无receiver命中。最终owner(-1,-1)的trace/node/shape与无插桩基线逐字节同。
- [guard-arms.csv](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/discovery/guard-capture-1792768014869631500/guard-arms.csv)；同一已捕获床面额外无守卫/无抬升截断的**诊断**minimax找到更低终点(-52,90)，crest183.87718287136818，起点bed136.26394332698206，需rise47.61323954438612（现上限12）。仅用于解释上限内为何无出口；不授权提高rise，不是全球最优出口或湖水位证书。完整路径484点、展开41177，见 [terrain-minimax-summary.json](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/discovery/guard-capture-1792768014869631500/terrain-minimax-summary.json)。不能称此死端已修。

**已落地的最小局部修复：**

- 现有OrderedSpillSearch在同crest BFS整层完成后先判`touchesUnknown`，再判`chosen`，导致同层存在内域已知目标/已知receiver也被边框遮蔽。按相邻距离，真实窗外未知终点至少还多一步；较早层或更低crest的未知边界仍拒绝。
- 最终修复副本：[OrderedSpillSearch.java](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/discovery/boundary-layer-1792768869349893800/candidate/OrderedSpillSearch.java)，SHA256=`7569a90397b3777457d61cbf851d58180baa54f38905adecb9d4966cfed60056`。只让已知内域goal/receiver参与同层chosen，再在整层结束优先接受该已知目标。未知边框lower本身不冒充认证出口，整层继续结算，不改crest优先/排序/守卫/上限/4096批次。
- **失败试验保留**：1792768498794505400首版仅换判断先后，把未知边框lower也接受，既有SearchProbe真实2FAIL，拒绝采用；1792768642646309000次版只事后检查chosen，237旧回归绿，但未知边框最小索引仍遮蔽同层已知goal，五owner逐字节不变。最终版在选chosen时排除未知目标，避免上述两缺陷，不用失败臂的真实图/计数宣称改善。
- [verify-boundary-layer.py](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/discovery/verify-boundary-layer.py) / [SpillBoundaryLayerProbe.java](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/discovery/SpillBoundaryLayerProbe.java)：同层已知目标、较早边框仍未决、未知边框lower仍未决、未知lower不能遮蔽receiver、批大小1/2/7/4096、正/负大坐标；再用独立Chebyshev距离oracle枚举1200种平场起点/receiver组合。累计crest+BFS预算不变时部分EXHAUSTED仍未决，不要求强行几何完成。共1230检查绿；基线/撤修/放行未知goal/遮蔽known-goal四臂均先compile0再命中指定AssertionError。
- 最新 [results.json](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/discovery/boundary-layer-1792771196729756200/results.json)，最终源码与886版逐字节同。既有SearchProbe完整237 PASS/0 FAIL，含原真实95点路径及预算/取消检查，见 [search-probe-report.txt](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/discovery/boundary-layer-1792768869349893800/regression/out/search-probe-report.txt)。新增oracle初跑将EXHAUSTED误当几何失败，保留1792771045285192400红日志，后修的是测试对累计预算的错误预期，不增加实现预算。

**真实效果与明确未解决项：**

- 五owner冷实际provider：搜索1980次（含重复依赖）中ESCAPE1779→1781、DOMAIN_PENDING103→101，JOIN96/NO_ESCAPE2不变。确切变更为owner(0,-1)，源(174,-562)在坑(176,-600)两次搜索从DOMAIN_PENDING→ESCAPE；该支流现在29节点JOIN到(158,-604)。最终trace由151→153；是新增支流接回未决主干，**不是主干到海**。
- 五owner原14问题终点仍13 DOMAIN_PENDING＋1NO_ESCAPE，**断头数未下降**。新窄改没有实现跨窗溢流证书/上坡交接，未直接将DOMAIN_PENDING变端口。数据 [results.json](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/discovery/spill-reasons-1792769971123113600/results.json)。另一独立冷JVM同序五owner30份trace/node/shape/port/incoming/merge表逐字节同，见 [cold-comparison.json](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/discovery/spill-reasons-1792770886742787500/cold-comparison.json)；非冷逆序/并发证明。
- 同TOML/seed/中心(-140,137)/r700/step1完整生产合成新图 [production.png](forge-1.20.1-47.4.10-mdk/build/hydro-production/prod-1792770100074/production.png)：8281chunk/0未覆盖，PNG及columns摘要与原基线逐字节同。变化位置在北界之外；不以大图不变说补丁没加载，实际类来源已核验。
- 另用同一生产入口冷JVM A/B，中心(174,-580)/r128/step1，两臂361chunk/0未覆盖，257²局部实际新增17个河水像素、删除0，湖1278不变，河2246→2263。变化x157..171/z-606..-602；原图最近邻标注仅展示，不补画河。[局部对照](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/discovery/layer-render-1792770035536/local-ab-174--580/comparison-nearest3-annotated.png)、[results.json](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/discovery/layer-render-1792770035536/local-ab-174--580/results.json)。图片不是Forge落块或流体验收。

**下一工作靶点仍是13条真实未决，特别是owner(-1,-1)核心终点(-192,-452)。** 其原因不能再混作守卫或预算；跨窗仍需消费真实已认证相邻路径，不伪造端口/改为普通河爬坡。其余旧66、完整生命周期/湿列/无环、生产缓存、门禁与Forge未完成，仍不迁main。

### 〇am、按新授权舍弃极短河与不通水支系：隔离剪枝、大图与跨区未闭合（2026-10-24）

用户进一步允许非常短的主/支河不生成、少量无法接入湖海的河系整支消失，认为当前密度偏高。这覆盖〇al此前“不删河”的范围限制；**不授权切掉长河的局部短连接**。仍沿现有model条件D8，不另建水文、不递归邻区final、不重复pass2、不加预算或rise、不改水量/宽深/惯性。生产源码、原TOML、原计划、schema106未改，未提交推送。

**当前隔离规则与范围：**

- [prepare.py](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/pruning/prepare.py) 在既有base/fresh合并后统一有向剪枝，JOIN沿真实后继判连通；不再因湖口或incoming身份而豁免死端；冲突/无终点环不保留。真实BOUNDARY_EXIT端口仍是暂保交接候选，**不等于已证明到湖海**。
- 短段按连接后的去重节点最长链判断，不按每个trace切片独立判断；保护长上游馈入、incoming与真实outsideTail。取消无上游、无incoming、无outsideTail的单节点源端口豁免；2节点及以上端口暂保。不是全局河系长度证书。
- 最新候选 [DeterministicSkeleton.java](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/pruning/candidate-1792779496954846600/DeterministicSkeleton.java)，SHA256=`a6ce20199d833838a7f3364bc465501a214177b31ff53f3b47c0dd2d208c7a2a`；依赖〇al最终搜索修复及既有integrated provider。

**函数验证与失败证据：**

- [verify.py](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/pruning/verify.py) 最新 [results.json](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/pruning/verification-1792781068561597200/results.json)：编译0，37场景/184调用与16项整链/单点检查均PASS；四个变异均编译0后命中指定断言，旧246版逐切片误删的反控亦编译0后红。源配置与候选保护哈希未变。这里只证明函数规则，不证明跨区终点闭合。
- 首次949验证的单点fixture误把port.next同时填成outsideTail，导致14 PASS/1 FAIL；生产真实反例无tail。已将fixture改为无tail，另加带4点真实world tail必须保留的正控；原红日志保留于 [lengthchain.log](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/pruning/verification-1792779840705542200/baseline/lengthchain.log)。不能把改测试说成修复生产逻辑，也不能把该失真fixture的581红灯当有效反控。

**真实收发审计：**

- 五owner候选从581的442条trace降至416：删除11个单节点源端口，级联删除15条typed中转（原9入湖/6 JOIN），没有新增。已在本样本确认7个中转锚点来自被舍弃的单点源；另8个来源在样本外，未证明其全部上游。没有据此宣称所有长河连接都已保护。
- 121个FINAL端口中，内部46个发送/接收manifest逐项一致：40有exact final边、6为水体接收元数据。目标坏承诺`-1:0@-26,-96→-26,-98`随无长度源一起消失，是删除承诺，**不是接通该河**。
- 对此前“剩6缺口”的审计表述作更正：这6个内部无final边项均为LAKE元数据，不是真实PRUNED；它们尚无实际湿列证书。另有接收方(-2,-1)两处真实PRUNED锚点`(-554,-416)`、`(-536,-416)`，每处有两个外部发送方manifest行。口径及更正见 [clarification.json](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/pruning/v4-real-1792779544329/clarification.json)。不能混合outgoing内部计数与所有incoming行计数。
- 独立冷JVM只查发送方(-2,-2)、(-1,-2)，确认两方均在PRIMARY和FINAL继续发布上述两个端口，4/4完整payload与receiver精确相同；对应17/25节点ORDINARY_SOURCE，非单点源，全部仅在发送方halo，anchor位于receiver core。receiver仍ACCEPTED后PRUNED、无final覆盖。**确有未完成交接，不是旧P1残留**；也不能据发送方halo几何推断实际core水道被切断。证据 [final-verification.json](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/pruning/v4-senders-1792782130255/final-verification.json)、[sender-vs-receiver-verdict.csv](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/pruning/v4-senders-1792782130255/sender-vs-receiver-verdict.csv)。未继续扩大region样本。

**同参完整生产入口大图：**

- [production.png](forge-1.20.1-47.4.10-mdk/build/hydro-production/prod-1792781015668/production.png)，1401²、8281chunk、0未覆盖；原样ProductionViewProbe→getChunkCells→实际provider合成，不后处理补河。JVM code source确认DS来自949、搜索来自〇al最终版，配置与地形参数同基线逐字节相同。
- 相对〇al基线：river41128→36560（河水像素覆盖下降11.1068%，不是河条数），lake90799→91336，sea452661不变。4569个RGB像素变化：4031河→干、537河→湖，另1灰底灰度变化。相对581只减少506河像素、全部河→干，湖海不变；不是路由接通收益。
- [原尺寸三栏对照](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/pruning/v4-render-1792780702450/baseline-comparison-original-size.png)、[comparison.json](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/pruning/v4-render-1792780702450/comparison.json)。差值图只表示RGB绝对差、不表示增删方向。原图已目检，南部长直仍明显，不能以减密度宣称形态或连通性已验收。

**两处缺口的只读根因补查：**

- receiver先临时接受`(-554,-416)`的73节点链，止于`(-420,-404)`、UNRESOLVED；再接受`(-536,-416)`的11节点链，JOIN到`(-522,-394)`。该JOIN格不在base/final内；根据incoming顺序与占用来源，归属前一条未决incoming。二者在有向终点可达性阶段一起被剪，**不是短链阈值误删**。旧raw未保存过滤前cell数组，不能复原完整路径/JOIN下标；spill子状态也未导出，不能猜DOMAIN_PENDING或EXHAUSTED。
- sender的42个去重路径点与receiver保留base/final零重叠，receiver对应床面未导出，不能据sender床面判断两窗一致。更重要的是，integrated provider在本区无命中、非湖、边界带且非HIER时，允许借邻区SegmentIndex；这些halo前缀满足几何方向门的候选条件，**“sender core无点所以生产绝不会出图”不成立**。未做实际segment hit/湿列检查，只能说存在借用入口，不能宣布已落水或已连续。
- [analysis.json](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/pruning/v4-rootcause-1792782638407/analysis.json) 同时列出合法halo端口带sender core前缀的反例，故不能全删halo port，也不能只删port却留可借几何。本轮只读，1937个旧输入保护文件未变。

**状态与下一靶点：** 第一关仍in_progress。下一次在现有单次solve中捕获这两条过滤前路径、spill子状态与JOIN对象，再决定整支舍弃的一致边界；同时核验provider借图，避免残留可见前缀。不以本地端口存在当湖海证明，不为少量可舍支系扩建世界图。全局来源/共同后继/湿列/无环，负大坐标全链、下游先查、冷逆序、缓存淘汰、并发取消重试、旧66、WorldgenGate与Forge仍未通过，本候选不得迁入main或升schema冒充验收完成。

### 〇an、两处incoming单次求解捕获与真实湿前缀核验（2026-10-24，仅诊断）

用户批注批准继续〇am最后的定点取证：捕获过滤前路径、具体停止原因，检查可见前缀。本轮未改剪枝/源准入/预算/rise，不递归final、不重复pass2，不迁生产、不升schema、不提交推送。

**三项假设与实际证据：**

- A（具体停止原因）：949隔离副本在原有同一次receiver(-2,-1) solve定点记录，A锚点`(-554,-416)`完整73节点。先在`(-432,-412)`发生ESCAPE（expanded9，3点真实见证已应用）；最终在`(-420,-404)`发生**DOMAIN_PENDING**，expanded12，crest与boundary同为`159.37953902145267`。返回候选路径`(-420,-404)→(-418,-404)→(-416,-402)`触到未知边框，**未应用**。坑bed159.2965231515597、rise上限仍12；不是EXHAUSTED，不支持加预算或将候选路径直接发成端口。
- C（JOIN归属）：B锚点`(-536,-416)`完整11节点，`JoinKind.NEARBY`，终点`(-522,-394)`命中A的零基index20（第21节点），前一trace source=99995、end=UNRESOLVED。已由真实cell数组交叉断言，不再仅用排除法推断。B日志原始reason仍为初始化NO_RECEIVER，不能以此否定JOIN；end/joinKind/对象命中才是归因依据。
- A/C证据：[results.json](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/pruning/target-capture-1792783602763/results.json)、[prefilter-paths.csv](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/pruning/target-capture-1792783602763/prefilter-paths.csv)、[diagnosis.json](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/pruning/target-capture-1792783602763/diagnosis.json)。84节点、91事件；13份非计时final/base/port/incoming/shape等导出与原949逐字节一致。插桩不消费随机数、不修改路径/搜索结果。
- B（实际可见前缀）：独立未插桩949，真实CellGenerator→GeoGenesisTerrain.getChunkCells，16chunk，窄采样`x=-584..-530,z=-452..-408`共2475列：**112河水列、2363干列、0湖/海**。两目标几何分别44/68湿列，每列恰1整块水柱；本地receiver和东邻SegmentIndex无命中，两sender均命中同一目标段。归因依赖实际缓存nearest与原选择代码，不冒充inline selector观测。
- 锚点`(-554,-416)`ground215.0231700453758、水面216.0231700453758，Y216有水；承诺next`(-552,-414)`无水。锚点`(-536,-416)`ground213.93612750967708、水面214.93612750967708，Y214有水；next`(-536,-414)`无水。`z=-415..-408`的440采样列全干。两条捕获路径在范围内均index0湿、index1..4干，另外74节点未采样。故**“前缀出水但续段被剪”已是实际生产列现象，不只是metadata风险**。
- 不能将断点单因归于方向门：即使忽略该门，两sender折线在后侧也无nearest覆盖。实际湿列 [actual-columns.csv](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/pruning/prefix-wetcheck-1792783649717863800/actual-columns.csv)，综合 [branch-B-report.json](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/pruning/prefix-wetcheck-1792783649717863800/branch-B-report.json)，[局部真实湿列标注图](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/pruning/prefix-wetcheck-1792783649717863800/support-actual-wet-annotated.png)。图由55×45原始湿列最近邻放大，不画预测河；标注线不是河。

**本地支撑核查及边界：** 两sender、PRIMARY/FINAL的8项反向可达核查，目标上游组均只含自身17/25节点trace，没有已导出的其他core馈入、typed incoming、湖口trace或basin outlet支撑。见 [support-summary.csv](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/pruning/prefix-wetcheck-1792783649717863800/support-summary.csv)。只证明本地已保留图无支撑，不是全球无上游或回滚前图证明。不能全删halo port（〇am已有合法反例），不能只删端口仍暴露可借几何。若后续选择整支舍弃，应在既有P1源/有向支撑组内统一几何与发布资格，同时保护core馈入和typed接力；这是产出准入语义变更，不是无损诊断修补。

**执行、保护与异常如实记录：**

- A分支compile/provider/分析exit0；插桩版重新跑37场景184调用与16项整链检查均PASS。最初PowerShell未引用`-Dfile.encoding=UTF-8`导致Java找不到主类，失败日志保留；引用参数后实际测试通过，不把启动失败算测试判据红灯。
- 原debug-agent.log先记录大小8101549字节/35581行，复制并校验SHA后才清空；新日志仅91条定点事件，完整副本保留在target-capture目录。插桩只存在本次新增副本，未清理或污染生产。
- B首次探针错误使用原文件支持的CommentedFileConfig，触发Forge自动校正写原TOML；发现后中止（0列），从保护原件逐字节恢复SHA=`8dc968236445a74a75357d84122b61e1f1a54c07500b829276f9b48f42c223e3`，随后成功运行改为内存解析。**最终哈希一致不等于原配置从未被写过**。证据 [aborted-load.json](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/pruning/prefix-wetcheck-1792783649717863800/aborted-load.json)，不得引用首次中止臂产物作验收。
- B compile/production/analysis均exit0；最终7633项保护一致，追加支撑/图片分析7705项保护一致。正常columnsFor依赖观测4个final、12个P1 owner，没有主动额外final查询。与既有同参大图2475分类点全部一致，仅作交叉核验。

**结论：** 定点诊断完成，原第一关整体仍未完成。根因链为“halo独立源仍发布并可借图→接收后到未知边框未决→连同其JOIN支流被局部剪掉→上游可见前缀残留”。修复应选择一致舍弃该类无本地支撑源组件或在既有契约内保留并解决其续流；本轮没有擅自改准入/强保未决。Forge方块、装饰、流体tick、全局湖海连接及确定性门禁仍未验证。

### 〇ao、用户选择“一致舍弃候选”：halo源组件统一准入（2026-10-24，隔离候选，未验收）

用户在两条修复路线中明确选择**一致舍弃候选（推荐）**。允许改隔离产出准入，不授权全删halo出口、硬编码两锚点或迁生产。新候选 [prepare.py](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/pruning/halo-candidate-1792785734981/prepare.py) 生成独立DS与provider；两者实际class均首命中新目录，不覆盖949或main。

**规则及影响面：**

- provider显式传入core的世界坐标半开矩形`[origin+HALO, origin+HALO+REGION)`，不在DS猜窗口margin。原build公共重载保留，无core上下文仍走旧949行为；PRIMARY与SECOND_PASS共用新增内部入口。
- 在已有有向终点/短链过滤后，按共享粗节点保守聚合组件；只有含真实BOUNDARY_EXIT+port，且整组没有core节点、incoming锚点、fromOutlet、真实outsideTail或MOUTH_LAKE/SEA的组件才整体舍弃。**几何和端口使用同一最终trace列表**，不是只藏metadata。共享节点聚组只是源准入保护，不当成到湖海证明；不改路由、预算、水量、宽深或惯性。
- DS源码SHA=`62a75dceee25aa5315886d1345353f923254520042fedafe6499e5a379f862ff`，provider源码SHA=`43a25a9a145e3c38fc0a0a825b684d9271552e9564d3e8366390b434aadd6e08`；见 [prepare.json](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/pruning/halo-candidate-1792785734981/prepare.json)。最终湿列所用DS class SHA=`46052018e25165999527942303999582eeeca3fc66766447a661fb9d3670b7d9`，provider class SHA=`dcae58e78e7da674e317871dce91d19a44b90ac89cc0f581fc14539985560fa4`。

**函数与反控：** [HaloComponentProbe.java](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/pruning/halo-tests/HaloComponentProbe.java) 独立显式预期113场景、607排列、2428次调用全部PASS，涵盖多级JOIN组、core馈入/typed/outlet/tail/水体保护、半开边界、正负大坐标、身份/数组/幂等及null上下文；旧37场景184调用和16整链检查全过。四个变异（不删、撤core保护、incoming只看源头、上界包含）均先compile0后AssertionError。最终 [results.json](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/pruning/halo-candidate-1792785734981/verification-1792787456105478600/results.json)；初次runner因变异替换不唯一、次次因断言匹配文字错误退出，旧失败目录保留，未将runner故障冒充实现反控。

**实际目标：已一致消失，不是接通：**

- 独立冷JVM同16chunk/2475列，旧A44湿列+B68湿列共112列全部变干，无新增水；其余2363列所有导出字段逐字相同。112列ground均恢复恰1格（不再下切河槽），riverType1→0、整块水柱1→0；surface metadata本身不变，不能仅看水面数值误报残留。
- 两sender×PRIMARY/FINAL×两anchor共8项：目标trace/port/pending均0，receiver目标incoming诊断0。端口和可借几何一起消失，9900次现有缓存nearest查询无命中。参数、seed与原配置一致。实际 [final-report.json](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/pruning/halo-wetcheck-1792785858963332100/final-report.json)；配置改用内存解析，本分支未写原TOML，9879个保护文件一致。

**七区审计：目标过，但精确shape保护仍红：**

- 同旧五owner加两sender共7区，FINAL619→501，删除118、新增0；95个无支撑P1组共110条trace被舍弃，另8条typed FINAL因manifest消失级联删除。三条指定合法halo主干反例原粗trace/shape/port均保留。
- 74个FINAL端口：11 exact-next、6水体metadata、57 receiver在样本外；内部无PRUNED或缺manifest。7区P1/FINAL端口差集均0。57外部未知与6水体metadata不能当完整湿连通。
- 59/59带core支撑的旧出口粗路径及端口都保留，但仅54/59 shape逐字相同；全部501存活trace里30条shape改变。审计analysis明确exit1、expected0，未改成全绿。数据 [final-report.txt](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/pruning/halo-real-1792785899989/final-report.txt)、[protected-halo-exits.csv](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/pruning/halo-real-1792785899989/protected-halo-exits.csv)。
- 只读数值取证确认30条均是**被舍弃发送方对应的已兑现incoming pin消失**：每条仅一个控制锚点从格心恢复该链自身偏移，shared表首写者变化0，29条不与本区删除trace共享节点。shape方法及加载class指令一致，并非随机数/路由改变。
- 例`-1:0@-304,608`的内部控制点`(-318,96)`移动0.058829块，port端点不动；最大同下标差1.260116来自某CR段采样2→3后的数组错位，实际双向顶点到折线约0.088787，不是全河平移。离线pin-only复现全部30项误差≤约5.7e-13，仅用于归因，不放宽原逐字判据。证据 [selected-final.json](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/pruning/shape-cause-1792786526188/selected-final.json)、[report.txt](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/pruning/shape-cause-1792786526188/report.txt)。尚未证明这些湿列变化无害，不保留失效pin来造假绿。

**有限冷逆序：** 新7owner反向查询一次冷JVM，90/90必需表逐字相同，summary排除seconds后同，DS/provider/CellGenerator来源与SHA同；原shape保护红灯继续保留。仅HaloAudit导出的trace-arrays/cache-owners与额外类provenance行在本导出器不存在，明记未比较，不计相等。见 [final-verification.json](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/pruning/halo-cold-reverse-1792787510922/final-verification.json)。这不覆盖多seed/缓存淘汰/并发取消或全世界确定性。

**补验已完成，但原shape逐字保护红灯不撤：** 最初两个独立分支因模型提供商403额度拒绝未完成、未产生有效图/列；主线程接管到 [run.py](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/pruning/halo-final-evidence-1792788299401/run.py)，compile/self-test/render/pin-old/pin-new均exit0，最后保护哈希无变化。

- 同参1401²生产入口新图 [production.png](forge-1.20.1-47.4.10-mdk/build/hydro-production/prod-1792788395219/production.png)：8281chunk/0未覆盖，river35788/lake91336/sea452661。相对949河36560→35788（净-772，-2.1116%），实际777河→干、5干→河，湖海分类均不变；不是纯删像素。相对原基线41128净减5340（约12.98%）。地形参数及校正配置与旧949逐字同，实际DS/provider来源为本候选。
- [完整原尺寸对照](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/pruning/halo-final-evidence-1792788299401/comparison-original-size.png)、[image-comparison.json](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/pruning/halo-final-evidence-1792788299401/image-comparison.json)。已目检：南部长直依然明显，不以减密度宣称形态验收。
- 5个core支持出口丢失pin的控制点分别`(-318,96),(-382,96),(-220,96),(-708,96),(-740,96)`，各25×25真生产列A/B，每臂3125列/22chunk；前4块所有导出字段逐字同，最后一块29→28湿列，减少1列、无新增。5块原湿组在4/8邻接下均保持1组、未出现原共同湿像素被分裂。证据 [pin-column-comparison.json](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/pruning/halo-final-evidence-1792788299401/pin-column-comparison.json)。只覆盖局部，不证明全河流体连续，也不覆盖其余25条变形trace。

**状态：** 局部一致舍弃与有限7区冷逆序通过；原精确shape保护仍FAIL，不能擅自改expected或迁main。生产源码、缓存schema、原计划均未改，未提交推送。下一步需要决定是否接受“失效incoming pin撤销造成的局部形态变化”，或进一步约束候选以保持旧几何；全世界确定性、57个外部端口、完整湖海/湿列/无环、门禁及Forge仍未通过。

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

### 〇ar、按全周期口径继续验收：25处局部回归与57端口接收（2026-10-24，未改产出）

**pin撤销的剩余25处：** 固定5012397中的halo候选与旧949，各一个冷JVM真实getChunkCells，25个25×25窗口、每臂15625样本/114chunk，compile/old/new均exit0；1149个旧输入保护哈希一致。初始 [comparison.json](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/pruning/remaining-pins-1792790132931/comparison.json) 用river-only四邻图发现`(-925,-168)`少一水列、旧1组→新2组，analysis真实exit1保留。逐Y复核发现该处真实接收湖参与：Y176的`(-926,-168)河→(-926,-167)湖→(-926,-166)湖→(-925,-166)湖→(-925,-167)河`面相邻连通，不能把排除合法湖面的river-only误报当河流断口。另立 [water-volume-comparison.json](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/pruning/remaining-pins-1792790132931/water-volume-comparison.json) 记录更正，不覆盖原红报告。

25处加入湖海实际整块水柱后的3D六邻图，未出现“旧共存河水体被新版本额外分裂”；19个字段变化样本、河样本980→965（重叠窗口按各patch计），其中一旧独立组整体消失，不能据此自动断言安全。**多个旧/新局部3D水组本来就不连续，故这只是未新增局部回归，不是绝对全程连续证明。** 所有30条变形存活trace的真实JOIN粗节点与shape共享顶点检查通过，见 [join-geometry-check.json](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/pruning/remaining-pins-1792790132931/join-geometry-check.json)；仍不替代JOIN湿列、全河后继与Forge。

**57个原样本外端口：有限13receiver单跳导出完成。** 新 [receiver-lifecycle目录](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/pruning/receiver-lifecycle-1792792586066/FINAL-REPORT.txt) 只求解原57条对应的13个receiver，每个FINAL一次；旧7owner从raw读取，没有递归final或重复pass2，不追新16个范围外端口。原TOML先复制再加载副本，原件哈希保持；21968项保护一致。

- 57/57发送/接收完整payload匹配；55条有exact promised next，2条LAKE元数据。单跳未出现已发布却接收方PRUNED，不等于湿列/完整全周期通过。
- 在已导出的20owner粗图内，48条沿真实边/明确handoff到SEA；9条停在尚无已保留溢口起链的湖盆。其中receiver `(-1,-3)`盆0占5条、`(-3,-2)`盆52占2条、`(-3,-3)`盆2占2条；前两盆outlet在core，最后一盆在halo。皆有真实入流、overflow=true、正outflow、outlet Q过既有成河门限，但导出保留trace无该起点。
- **原因尚UNKNOWN：** 没有过滤前trace/实际seed清单，不知是未准入、追踪后回滚、已有覆盖，还是有向/短组过滤移除。`droppedPrimary`总数不能证明具体outlet何时被删；禁止靠恢复fromOutlet豁免强保未决来补绿。halo组件过滤只删`hasPort&&!supported`，fromOutlet明确受保护，不能把缺出流归因到“halo/port-less组按设计被删”。
- 独立 [lifecycle-gate.json](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/pruning/receiver-lifecycle-1792792586066/lifecycle-gate.json)：**exit1，9/57全周期未闭合**；compile/provider/导出analysis0只证明取证完成，不算验收绿。

**本轮观察器错误不删除：** 分支原FINAL-REPORT将9条停点误归4盆，其中两条跨owner后停在(-1,-3)却按首接收方(-2,-3)同世界格查到无关小盆。更正为上述3盆，逐项权威来源见 [final-57-explicit.csv](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/pruning/receiver-lifecycle-1792792586066/final-57-explicit.csv) 和 [CORRECTIONS.json](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/pruning/receiver-lifecycle-1792792586066/CORRECTIONS.json)。原报告及原walk保留，不再引用错误4盆/halo豁免归因。旧ProductionDrainageAudit.follow无BOUNDARY_EXIT分支，会重复原segment得到伪CYCLE；不能用旧导出器结果冒充真实环，不改生产审计器来造水文修复。

**下一具体靶点：** 仅对上述两个core正出流盆，在现有同一次solve定点捕获basinOutlet准入、过滤前trace与停止原因，解释为何无保留出湖链。当前全周期关卡仍红、绝对连续仍未认证；不改候选/生产/TOML/预算/宽深/惯性、不升缓存、不提交推送，不因为57单跳有接收就越过全周期验收。

### 〇as、两个core湖盆出口定点捕获：回到原湖与短湖间连接被删（2026-10-24，仅诊断）

用户批准继续〇ar的两个具体湖盆取证。本轮仍只读固定halo候选，在新build副本观察实际PRIMARY求解与过滤阶段；两个目标FINAL各一次，未递归邻FINAL、未重复pass2。另一次PRIMARY-only导出直接读取原候选的湖盆身份及9×9邻域，不重放追踪、不试跑候选出口。未改生产、候选规则、TOML、预算/rise、宽深/惯性或schema，未提交推送。

**假设与实测：** A=未准入；B=已覆盖/追踪回滚或未决；C=过滤移除。两处均明确准入，Q分别105.04480000000953、25.568000000009263，门限0.19199999999999998；实际pass1起链、covered=false、rollback=false、无spill搜索。均通过有向过滤，但在随后短段过滤消失，halo过滤不是首次删除点。

| owner / 源盆 | 实际过滤前路径（格索引） | 真实接收身份 | 已确认问题 |
|---|---|---|---|
| (-1,-3) / 盆0，出口(-314,-1220) | 52097→51744；2节点，MOUTH_LAKE | 仍是盆0，终点(-316,-1220)，同一个出口52097 | 半径2内没有符合现有outletEscape判据的非湖下降格；逃逸失败后普通追踪返回原湖。这不是合法出湖，更不是“短段删掉所以没问题” |
| (-3,-2) / 盆52，出口(-1100,-562) | 94177→94531→94532；3节点，MOUTH_LAKE | 盆54，终点(-1098,-558)，其出口恰为中间格94531 | 真实湖间短连接被短段过滤删除；盆54也overflow且正出流，不能把这里视为最终闭湖 |

第二处盆54出口94531同在已准入outlets列表，但已被盆52路径经过并认领，过滤前没有从94531另起的trace。结合现有`covered[start]→continue`分支可归因其后续起链被覆盖跳过；**没有单独捕获94531的TRACE_BEGIN/COVERED_BY事件，不能将此控制流归因冒充额外运行日志。** 只保留盆52三点段仍不足以证明盆54之后有真实下游。

**修复边界：** 不能恢复fromOutlet总豁免强留两条短线：第一条只回到原湖，第二条之后仍缺下游。必须先让既有湖盆出口和真实出湖方向一致，并区分“出口格被经过”与“出口已具有有效后继”；随后仅保护认证的湖间连接，不将其当可删除的独立短支流。尚未选择/实施出口修法，不能提高预算或把未决改称闭盆。本轮不是全周期修复完成。

**证据与验证：** [diagnosis.json](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/pruning/outlet-capture-1792817716435/diagnosis.json)及同目录events/identity-events保存逐阶段和实际盆身份。javac/provider、身份导出javac/provider及分析断言均exit0；26份非计时owner导出与固定旧审计逐字节相同，1253项源码/候选/旧证据/计划/TOML保护哈希无变化，分析含正负整行平移校验。初次PowerShell管道Python因BOM解析失败，后改为文件脚本运行通过，失败不算判据反控。捕获中的raw `reason=NO_RECEIVER`是初始化未更新字符串，实际结束分支为MOUTH_LAKE，不能照字面误报“无接收者”。旧debug日志先备份并核SHA再清空，捕获与身份日志分别保存。

**仍未通过：** 原57端口中9条全周期未闭合的关卡保持红；本轮没有新的真实湿列/全河连续性或Forge验收，也没有解决第三个halo盆。南部长直不是bug，不纳入修复。

### 〇at、出口根因隔离修复与真实水柱红灯（2026-10-24，未迁生产）

用户批准继续修现有出口接续，不加豁免、不另建水文、不加预算。新候选位于`build/d8-spill-contract/pruning/outlet-candidate-1792821818176`，原5012397/halo候选和生产源码保持不变。

**根因再收窄：** 原LakeWaterBalance按四邻扫描最低dry rim；EquilibriumWater父树却是八邻。盆0/54各有唯一真实parent出盆边，恰为对角边，外侧格bed精确等于sill，四邻rim扫描看不到它，选中的是parent指回湖内的入流岸。现有证据的完整member/rim/跨出边和原parent指针walk见`outlet-boundary-evidence`。原parent长链局部有爬坡，不能把它整条当作合法河路；本轮只复用出口见证与第一步，后续仍走现有条件D8/spill。

**隔离实现：**
- LakeWaterBalance选口改消费成员的parent跨出边，要求目标非潜湖/非海、resolved、bed≤本盆sill；沿既有parent在下降到低于sill前不得回本盆。没有额外地形采样/新求解或更高预算。无见证返回-1，但正outflow不改称闭湖。原Result/public API不改，旧假rim不再注入。
- provider显式向内部build传同份lakeResult；旧公共build入口保持兼容。带上下文的出口首步使用parent[outlet]，回原盆/无身份/缺parent诚实未决。covered出口只归因直接后继，不重追制造共享格双后继，也不把存在后继当全程通水证书。
- 短滤仅额外保护有同份盆身份、fromOutlet且真实到海/不同盆的湖间连接；不对fromOutlet总豁免，有向过滤和既有port/incoming/tail保护保持。生产默认、TOML、宽深/惯性/rise及缓存schema未改。

**真实两盆结果（新FINAL各一次）：** 盆0出口52097→51723（-316,-1262），193节点到SEA；盆52保留94177→94531→94532三点入盆54，盆54改从94577（-1098,-468）起24节点入盆58，再6节点入盆62，再3节点到显式port。不是重复pass2或父树整链直画。两个目标盆inflow/outflow/level/area/seed与旧审计逐字相同；不是整个世界账本不变的证明。两owner无共享格双后继。

**不能验收：真实getChunkCells水柱仍红。** [report.json](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/pruning/outlet-wet-check-1792821818176/report.json)独立新旧JVM四个25×25patch、每臂2500样本，逐Y水柱六邻图：盆52河头到盆54入口已连通，但盆0/54出湖河头与实际湖面无共享面，两个原湖边member点本身实际干。盆0局部湖188列与河16列分离，盆54湖23列与全部河分量分离。走廊实测有高于湖面的逐块干脊，且近岸计划湖格深度不足0.5在生产不灌水；这不是有向出口修复已完成湿列连续。另有薄河水面高度跳变导致正交相邻水柱无共面，不能靠二维像素相邻判绿。新旧73样本变化（51干→河、22河→干），不能称本轮新造这两处缺口，也不能称已修。下一具体项是既有骨架从实际湿湖面到出湖河头的完整几何衔接，不加宽深或直接补水。

**20owner/原57端口回归：** 全部owner在新候选独立重算FINAL各一次，不拼旧7owner图，不递归追范围外final。56原端口仍发送且接收；55有真实LAND_EDGE、另2是promised格真实入湖（不是陆边失败）。沿当前实边及湖盆账本53行到SEA，4行到固定20owner外，不能当完整闭环。1个原端口`-2:-1@-1108,-608`消失，附近替身与同河身份未经认证：**变更未认证，不等于已证断口，也不等于安全平移**。两处目标独立复现；整导出无双后继。完整关卡 [gate.json](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/pruning/outlet-57-regression-1792821818176/gate.json)仍exit1（4范围外+1原承诺变化，有重叠行）。初版gate把EXTERNAL当soft、把近邻替身当安全迁移，均已撤回，原版备份保留。统计中91个正出流出口无已保留边：3截断、1低准入、9过门限无边未归因、78缺同候选门限证据；另24无出口格。不能把91个逐一叫bug或用旧候选Q替新候选门限判定。

**验证与限制：** [final-results.json](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/pruning/outlet-candidate-1792821818176/final-results.json)：最终三类源码编译0；LakeOutlet24断言、OutletContinuation16场景、既有halo113/剪枝37/整链16均exit0。3个反控（旧四邻selector、去短链认证、允许自回湖认证）全部先compile0再指定断言红。首次LakeOutlet fixture无真实来水却要求正outflow，原红保留，改显式upstreamFlow输入后通过。最终端口一致性55断言exit0，完整全周期及独立湿列gate均exit1；水列导出工具自身exit0仅表示取证完成。生产/配置/旧候选保护哈希未改。

运行大范围/湿列时DS class仍含后来删去的covered诊断早退；其余66/67方法逐指令相同，实导出该诊断分支出现0次。主线程最终重编同步源码/class并复跑全部合成与反控，未重复20owner/湿列求解；不能把此前实测说成最终class整份hash相同。未做1401²新大图、全确定性矩阵、完整WorldgenGate、Forge方块/装饰/流体tick或生产迁移；不升schema、不提交推送。南部长直不处理。

### 〇au、实际湖面到出湖河头的最小前缀（2026-10-24，隔离局部样本通过，整体仍红）

用户批准继续〇at的几何衔接。本轮原生产、TOML、debug日志、旧候选与旧证据全部保护；无提交推送、不升schema、不迁main。没有改半宽/深度参数、湖水位、水量、惯性或搜索预算，也没有直接补水。

**先臂与收敛：** 第一臂`outlet-prefix-1792837898724`从盆seed沿parent到选定出口，截取最后粗格湿点后的suffix；真实三盆都接上，但seed并不构造性保证经过所选出口，故不作为最终候选。保留原证据，不用“这三盆刚好有效”替一般契约。当前候选为 [OutletLakePrefix.java](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/pruning/outlet-prefix-nearest-1792842919611/OutletLakePrefix.java)：只在provider已有shape完成后，沿**现有parent的反向八邻关系**找最近同盆湿候选，取其原parent到既定outlet的最短跳数前缀，前插到该fromOutlet折线。无新地形搜索/新世界图/求解pass；环、异盆和缺湿点返回空，不凭空造见证。湿候选仍由同盆lake/bed<level−0.5选出，**算法本身尚未保证每种配置下首点真实生产湿**；本轮必须额外核实实际首点，不把粗格判据当湿列证明。

**前缀几何而非调宽深：** 仅provider插入一处attach调用；trace、end、source/mouth、port、原shape全部尾部保持不变，新增段交给现有SegmentIndex和雕刻管线。新增覆盖处实际地面沿用既有河道规则降低1块，不能说“地形完全未动”；这是缺失路径恢复产生的雕刻，不是额外加深参数或手工填水。没有画整湖parent树；输出只含最近湿候选到出口的必要段。没有把prefix当新ordinary source或改变准入。

**同参生产入口实测（独立JVM，真实getChunkCells）：**

| owner / 盆 | 前缀首点 → 出口 | path点数 / 新前插点数 | 实际首点 / 衔接 |
|---|---|---|---|
| (-1,-3) / 0 | (-320,-1260) → (-316,-1262) | 3 / 2 | lake，Y78，实际湖面到出口河头六邻面连通 |
| (-3,-2) / 52 | (-1104,-566) → (-1100,-562) | 3 / 2 | lake，Y210，原湖间连接继续保留且接出连通 |
| (-3,-2) / 54 | (-1102,-480) → (-1098,-468) | 7 / 6 | lake，Y210，实际湖面到出口河头六邻面连通 |

四个25×25窗口2500样本与无prefix的outlet候选对照，47样本干→河，改变仅在新增几何附近；第一臂为54列，第二臂只缩短盆0。三处prefix首点都在旧/新臂是真实湖列、水位一致；前缀全部节点实际湿，同盆/parent关系/窗口内检查通过。**STEP=2的粗节点彼此本不面邻接**，真正判据是中间逐方块水列的3D六邻洪泛，不是对两个粗节点只比较Y范围。三处每条prefix在±2块实采框中同一水分量；原2500样本patch的指定湖岸—河头锚点四处均连通，不能推广为全河连通。

**原尾部与相交核验：** 三条旧shape尾部548/12/72点以17位导出逐点一致；trace计数69/139、目标trace元数据与原候选相同。两个owner全208条折线的有限几何审计无新增跨折线非共享节点交点，前缀无自交/重复列；另4条自交trace均无prefix，原红不修、不归给本次。不声称完整世界不相交或跨owner顺序无关。证据 [features.json](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/pruning/outlet-prefix-nearest-wet-1792842919611/features.json)、[poly-tail-bitwise.json](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/pruning/outlet-prefix-nearest-wet-1792842919611/poly-tail-bitwise.json)。

**验证与红灯分开：** 编译、LakePrefixNearest24断言（含seed不在出口祖先链、异盆、环/无湿点、负坐标、attach索引对齐/原尾部bitwise/输入与trace不可变）、LakeOutlet24、OutletContinuation16、halo113、剪枝37/整链16均exit0。两个源码变异（不发prefix/穿过异盆）先编译0再指定断言exit1；独立湿列分裂负控保持红。[evidence-verification.json](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/pruning/outlet-prefix-nearest-1792842919611/evidence-verification.json)仅认证三处有限prefix，不改写原gate。

**完整连续性仍未通过：** 原7对薄河水柱Y跳1块、相邻却无共面仍逐项相同，两gate仍exit1（shore脚本也纳入这7对，因此其总verdict亦红；不把标签叫shore就掩掉）。这7处在既有无prefix参考臂独立存在，不能用“与本次无关”把全程连续要求关掉。〇at的4条范围外后继、1处端口身份变更及其它盆出口未分类缺口仍未解决，未重跑20owner/57端口全导出。下一靶点为**已有河段水柱降一级时的纵向衔接归因**，不是继续延长湖内前缀或加宽深；若需要改变水面/落块语义，须先说明取舍。未做新1401²大图、多seed/查询顺序/缓存淘汰/并发取消矩阵、完整门禁与Forge流体tick，当前候选仍不可发布。南部长直不处理。

### 〇av、7处河段纵向断接归因：逐列取整与未发布lip（2026-10-24，仅诊断，待语义决策）

用户批准继续〇au归因，并保留“涉及水面/落块规则变化先说明取舍”的边界。本轮不改任何候选或生产行为，复用既有真实getChunkCells列与有序shape逐项分析，不新增solve、不重放pass2、不新增插桩或清理旧debug。取证脚本及结果在`build/d8-spill-contract/pruning/vertical-step-1792847183851`。

**已证实而非猜测：** 7/7对均是同一有序trace、A→B沿下游，连续surface差仅0.0702916747至0.4079314589；14/14列实际lip==surface、都是river、各1整块水。独立floor后高列水Y=N/低列水Y=N−1；Y=N处低侧是空气，Y=N−1处高侧是实心床顶。25×25已有样本内无六邻水路绕行；1对跨chunk，其余6对同chunk，不支持“全是区块接缝问题”。与无prefix参考臂7对逐字相同。此处确认的是初始生产计划水体无共享面，不替代流体tick后的实机结论，也不把连续落差小于1直接当“真瀑布”。

**机制链：** 骨架provider河列发布`surface, surface`（waterSurfaceY/lipSurfaceY），普通水面来自同区双线性waterSurface。HydrologyBlockCarvedColumn已有lip通道，GeoGenesisTerrain原样传入cell.riverLipY；GeoGenesisGenerator按`max(floor(waterTop),floor(lip))`放水，超过普通waterTopBlock部分用FLOWING_WATER。因此纵向水幕消费链已存在，但骨架从未发布高于本列surface的lip。无需新水文底座，也不能把旁边任意高水面当上游。

**现有方向信息的边界：** shape/trace有源→口顺序，SegmentIndex已计算段号与投影t，但Hit不保留poly身份/弧长；sew bridge本身无天然下游身份。若修复，必须从同一有向河段消费真实上游，不读邻chunk生成结果，也不做四邻waterSurface max（会串接旁边别河）。生成顺序与跨区方向仍需专门验证。

**最小候选取舍（尚未实施）：** 复用lip，在真实同河顺流、正交相邻、普通水面floor降1且缺共享面时，仅将低列lip补到上游连接高度；这7对局部反事实每处需新增1水体素，床面/普通waterSurface/半宽/深度参数均可保持。但**额外流动水块就是出水体素语义变化**，不是“完全不改水面/不补水”；必须用户确认，并在Forge验证是否保住、是否扩散、是否受装饰/加载顺序影响。不能把7对反事实计算说成一般规则只会新增7块。另一选择是改变普通水位的链式离散化，影响更大；仅放宽六邻判据则不满足当前已确认的实际连续要求，不擅自采用。既有水tick衰减/扩散历史不能证明本7对会自动修复或必定失败。

**验证：** [report.json](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/pruning/vertical-step-1792847183851/report.json)保留7对原列值、同trace弧长、两层切片及implemented=false的lip反事实；分析退出0，15个正负高度解析通例验证floor台阶结构，源码/配置/候选/debug哈希保护不变。未跑Forge/全图/完整门禁，不改之前全连续exit1、4范围外后继及1端口身份未决，不迁生产、不升schema、不提交推送。南部长直不处理。

### 〇aw、用户纠偏：体素台阶不等于骨架中断，停止lip方向（2026-10-24）

用户明确指出MC体素地形出现台阶正常，要求停止偏离骨架目标的水方块工作、直接出当前水文效果图。**覆盖〇au/〇av的错误验收解释：** 7处已有同trace有序相邻段的水柱无六邻共享面，只能说明初始水块离散形态，不能据此判河流骨架断裂，也不能将其作为必须补lip的依据。原始测量和exit1报告保留，但这些报告不再直接充当骨架连续性失败证书；流体tick效果未实测，不猜测会或不会自行连接。

此前受限lip方案曾获同意，随后已被用户明确叫停。`lip-candidate-1792847183851`与`lip-wet-1792847183851`只保留为停止的隔离实验，不纳入本次出图、不迁生产，不声称已通过。当前出图组合为`outlet-prefix-nearest-1792842919611`及其既有出口候选依赖，沿用原seed/中心/半径700、1401×1401逐块生产`getChunkCells`入口，不改变水方块规则、宽深、惯性或预算。范围外4条后继与1处端口身份变化仍未认证；不能把本次纠偏变成全周期已完成。南部长直按用户确认是地形路线，不处理。

**本次完整出图已生成：** [production.png](forge-1.20.1-47.4.10-mdk/build/hydro-production/prod-1792855942489/production.png)，seed9139912035078620160、中心(-140,137)、1401×1401、step1。真实getChunkCells完成8281chunk、uncovered=0；湖91336、海452661、河37304。对上次halo整图：湖海掩码逐像素不变，2150列干→河、634列河→干，河净增1516；其余干地灰阶未变化，不把面积增加当全周期已通过。加载来源核实为nearest-prefix provider与outlet-candidate骨架，无lip候选；1704个源码/配置/依赖class保护哈希未变，原TOML哈希保持。编译/绘图自检/实际渲染均exit0。后处理初次读取混合编码console日志报错，未重跑求解，只修runner日志读取并对已生成原图完成核验，结果见 [results.json](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/pruning/current-view-1792855900486/results.json)。本图是隔离候选的生产路径离线效果，不是main默认、不是Forge运行后验收；未迁生产、未升schema、未提交推送。

### 〇ax、三类可见缺陷的修复（2026-10-25，隔离候选三补丁，未迁生产）

用户指出三点：① 部分河流没接到湖；② 湖边有超短河（应可剔除）；③ 有既不入湖也不入海的孤立河。
本轮只用只读取证定位，再在隔离候选改三处，**未改 src/main、未改 TOML、未升 schema、未提交**。

**根因（三处，互不相同）：**

| \# | 缺陷 | 根因（代码位置） | 证据 |
|---|---|---|---|
| ① ③ | 河没接到湖 / 孤立河 | `GeoGenesisTerrain` 湖列判据 `height < spill − 0.5` 要求水柱≥半格，而 MC 放水只要 `(floor(height), floor(spill)]` 非空 ⇒ 湖岸留一圈干缝 | 548 条名义入湖/入海终点，粗格掩码全 true、像素却干；终点到真实湖面距离 p50=1、p90=8、最大 21 |
| ② | 湖边超短河 | `traceOne` 的 `&& !fromOutlet` **无条件**豁免长度下限 ⇒ 2~5 节点溢口段全部存活；另有 11/19 的短棒根本没有 trace 覆盖，来自 `shape()` 对入湖折线**无条件延长 2 格（8 块）** | 85 条 <6 节点溢口段存活（2:16/3:38/4:8/5:23）；19 个湖边短段中 11 个反查 trace 覆盖为 0 |
| — | 拓扑层本身无断点 | 20 owner / 1441 trace：`DEAD_END=0`、`UNRESOLVED=0` | 起点不在"忘了写终点"，而在判据与几何 |

**三处修改（均带开关，可逐位回退）：**
1. 湖域判据改为 `height < spill − 1e-9`，水块能否放下交给**既有**「水柱保护」分支保证。
   ⚠ 首版直接换成 `floor(spill) > floor(height)` 被实测否证：绕过保护分支 ⇒ **−2508 格湖→干**、孤立河 17→22，已撤。
2. `traceOne` 溢口段不再无条件豁免：只有真正入海、或入湖且**离开源盆**的才保留。
3. `shape()` 末端延伸只对 `nodeCount >= MIN_TAIL_EXTEND_NODES`（默认 6）生效，短段不再凭空伸出 8 块。

**同参 1401² 实测（`GeoGenesisTerrain.getChunkCells` 生产路径，8281 chunk，uncovered=0）：**

| 指标 | 基线 | 仅湖岸 | 三补丁 |
|---|---|---|---|
| 湖格 | 91336 | 105172 | **105182** |
| 河格 | 37304 | 37304 | 37270 |
| 湖→干退化 | — | **0** | **0** |
| 孤立河分量 | 17（1818 px） | 6（311 px） | **6（311 px）** |
| 河终点距湖 >6 格 | 20 | 0 | **0** |
| 湖边短段 | 19（86 px） | 20（88 px） | **18（74 px）** |

图：[production.png](forge-1.20.1-47.4.10-mdk/build/hydro-production/prod-1792862777733/production.png)；
证据与可复跑脚本：[lake-shore-1792860057138](forge-1.20.1-47.4.10-mdk/build/d8-spill-contract/pruning/lake-shore-1792860057138)（`verify4.json`、`gate-compare.json`、`stub-diff.json`）。

**门禁等价性（有牙齿）：** `SkeletonProbe` 用基线类与补丁类各跑一遍，断言行**逐条 diff = 0**（22 PASS / 1 FAIL / exit 1 两臂相同）⇒ 补丁未改变任何既有门禁判定。该 22 PASS/1 FAIL 是 HEAD **既有**红灯，非本轮引入（`git status src/main` 为空）。另 `ShoreCriterionProbe` 240274 项通过，且两个反控有效：被否证的首版变体检出 59401 个干湖格；旧判据与方块放置有 59165 处不等价。

**仍未解决（如实）：** 剩余 6 个孤立河中，3 个 `end=JOIN` 但整簇离水 154~185 格、3 个 `end=MOUTH_LAKE` 但离湖 19~40 格 —— 与用户圈出的形态**不是同一成因**，属另一条独立缺陷，未在本轮处理。短段仅 19→18，改进有限，不宣称"已剔除"。

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

### 〇bn、宽度约束优先级表 + 过渡可行性 P4（2026-10-27；优先于〇bm；**已被〇bo 取代**）

用户第三次纠偏：**「我之前就提过判定要排好优先级，不然就会冲突」**，并确认 **「细没问题啊」**。此前所有反复（出湖 floor 顶粗、全局单调削平汇流树、起宽上限导致楔形）都源于**没有单一优先级表**，各处各自 `min`/`max`、谁后执行谁赢。本轮把它写进 `RiverWidthProfile` 类头，作为唯一仲裁依据。

**P0–P6 优先级（代码即文档，见类头 javadoc）**

| 级 | 约束 | 性质 |
|---|---|---|
| P0 | 1 ≤ 全宽 ≤ 12 格；骨架坐标不动 | 不可违反 |
| P1 | 出湖净出流/湖尺度**只作上限**；Q 是宽度来源；集水面积不参与 | 不可违反 |
| P2 | typed 端口双方一致；边界 pin 精确定位 | 硬契约 |
| P3 | 串联共用断面；汇流后接收段 ≥ 任一入流 | 硬契约 |
| **P4** | **起点必须能在本河长度内平缓收到末端；做不到就削起点** | **本轮新增，压过 P5** |
| P5 | 出湖起点按源湖横向尺度，全宽 ≤ 6 格；真源头 1 格 | 软，受 P4 约束 |
| P6 | 中间段局部浮动（LOCAL_DEVIATION） | 最软 |

**P4 的实现**：`feasibleStartHalf(wantStart, endHalf, arcLen)`
= `min(wantStart, endHalf + HALF_GROWTH_PER_BLOCK × arcLen)`，只减不增。

- 实测冲突案例：源湖横向 47 格 ⇒ P5 想要 5.88 格起点；但该河仅 32 格长、末端流入横向 6 格小湖（P3 要求末端 ≈1.0）。P4 判定"物理上收不回来"，把起点压到 2.0。
- **效果**：最陡变化率 **0.1579 → 0.0625 格/格**，正好等于设计斜率 1/32；30 条残余楔形**无一超限**。
- 残余 30 条 wedge 均为短河在斜率上限内的正常收窄（如 16 格河 2.01→1.00，rate=0.0625），**不是突变**。

**同时修正的三处语义错误**（均由此前缺优先级表导致）：
1. `enforceDownstreamMonotone` 改为**只在单条 trace 内**传播，不跨汇流——旧的"向源头全局运行最小"把整棵汇流树削到最细出口（实测 370 条剖面被压平、340 条最大宽度削掉 >1 格，`(-3,0) trace1` 由 4.73–12.00 变成全 3.24 常量）。
2. 出湖起点由 `Math.min` 铺满整条 trace 改为**只作用于起点**；`fromOutlet` 不再被排除在源头处理之外（它也是源头的一种）。
3. 起点 pin 是精确值，同步抬高节点 0 的上限；否则 `min(width, cap)` 会把起点重新压回（实测 30 处 `half > widthLimits`，最大超出 1.59）。

**验证**：宽度 172 断言（含 P4 专项 `testFeasibleStartPriority`）全通过；连接 1137、覆盖探针通过；36 区 **843/843 汇流、137/137 端口、227894 节点零越限、732 接口零越界、contractFailures=0、allGreen=true**。生产图 `prod-1793054183489`：8281 chunk、uncovered=0、河 61785。

交付：[完整 A/B](forge-1.20.1-47.4.10-mdk/build/river-width-trend/priority-production-ab.png)、[局部放大 A/B](forge-1.20.1-47.4.10-mdk/build/river-width-trend/priority-zoom-ab.png)、[沿程曲线](forge-1.20.1-47.4.10-mdk/build/river-width-trend/priority-curves.png)、[证据 JSON](forge-1.20.1-47.4.10-mdk/build/river-width-trend/priority-evidence.json)。

未测：Forge 实机、完整 worldgen 门禁、隔离性能基准。

### 〇bm、出湖宽度改为上限 + 顺流不得变窄（2026-10-27；优先于〇bl）

用户两次纠偏：①「一条河的上游怎么可能大于下游」；②「出湖 floor 是你自己加的限制，我说是只有至多」。**第②条推翻了〇bl 的语义前提**：〇bl 仍在用 `Math.max(h, outletHalf)` 把净出流当**下限**，这本身就是上游被顶粗的直接原因。本轮按用户口径改掉。

- **语义修正**：`RiverWidthProfile.rawRow` 两处 `Math.max(h, outletHalf)` → `Math.min`。出湖净出流现在只夹**上限**，不再把上游整段抬粗；下限仍由 `MIN_HALF_BLOCKS` 保证可见。
- **新增硬约束** `enforceDownstreamMonotone`：按 `chainEdges` 后序，从河口向源头做**运行最小**，保证同一有向路径上"上游半宽 ≤ 下游半宽"。这是用户明确要求的硬约束，作为最终步骤放在所有 pin/上限之后。
- **实测（36区，`mono-port-36`）**：
  - 「起>尾+0.5」河段 **19 → 0**
  - 任何局部变窄的河段 **518 → 0**
  - 用户圈的那条 `(0,-2)` trace15：`5.44→1.25` 变为 **`1.12→1.25`**（顺流变宽，楔形消失）
- **断言同步**：宽度 165 项全通过、连接 1137 项、覆盖探针通过。其中 4 项原断言"大出流必须更宽"正是被否定的语义，已改为验证"出湖只作上限、两臂出湖宽度一致"；3 项 pin 用例因"pin 值高于下游天然值会被单调压住"改为按下游契约断言，并新增 `testDownstreamNeverNarrows` 正面守门。
- 生产大图 `prod-1793040462782`：8281chunk、uncovered0，河 61702→**53501**（去掉人为抬粗后变细，符合预期）。
- **未解决（如实记录，不得当作已修）**：跨区端口出现 **21 处宽度不等**。按量级分：**12 处 <1e-4（浮点噪声）**、4 处 0.01–0.1、**4 处 ≥0.5（最大半宽差 3.52，即真实的上游宽于下游）**。正典大图范围 `x[-840,561) z[-563,838)` 内只有 3 处、最大半宽差 0.0097（≈0.02格，不可见）。
  - 成因：`boundaryWidths` 把**本区自己发出的端口**也写进 pin（发送方语义），单调压细的出口又被 pin 抬回原值。**三次尝试均无效**（`portHalf` 改 min、两次 `enforceExitPortMonotone`），21 处数值**逐位不变** ⇒ 发送方窗口根本不消费自己发出的端口值，该方向需"反向端口传播"的结构性改动，留待下一轮。
- 缓存 schema 113（〇bl 已升，本轮产出再变，故沿用并已在条目中记录）。

未测：Forge 实机、完整 worldgen 门禁、隔离性能基准；跨区反向端口传播未做。

### 〇bl、出湖宽度跨窗口一致（2026-10-27；优先于〇bk；**其"floor"语义已被〇bm 推翻**）

> ⚠ 本节的出发点是"让出湖 floor 跨窗口一致"，但〇bm 已确认 **floor 本身就是错误语义**（用户：出湖只有至多）。阅读时以〇bm 为准；本节保留的是跨窗口取小的机制（`outletHalfByWorld`）与实测数据。

用户指出少数河段"上游比下游宽、短河变化过快"，怀疑与出/入湖判定优先级冲突。定点复现确认两类原因并存，且只占少数：

- 归因证据：区域`(0,-2)` trace15 `(160,-486)→(182,-540)` 全长73.7格，全宽5.44→1.25，**79个节点全部等于湖口上限**（整条就是一根锥）。隔离重跑：只去掉出湖净出流floor ⇒ 起宽降到2.97；只去掉湖口包络 ⇒ 起宽5.86但末端5.92。⇒ **是出湖floor把上游顶粗**，湖口锥再把下游压细。
- 根因：同一世界出口格在相邻窗口被判成**不同源盆**（实测(160,-486)一侧basin 18、另一侧17/23）⇒ 净出流不同 ⇒ 同一条河两份几何起宽 5.44 vs 2.98（差45%）。
- 修法：`RiverWidthProfile`新增`outletHalfByWorld`（世界整块坐标为键），`ModelHydrologyProvider.agreedOutletHalves`只读**本区+邻区pass1**的源盆净出流，同格取**较小者**（与typed端口同口径，不放大任一侧）。**门控必须与inheritedWidths完全一致（primary && BOUNDARY_PORTS && !HIER）**：首次实现漏了primary，pass1内部再求邻区pass1 ⇒ 连锁扇出（实测侵蚀tile跑到-12576，已终止并修正）。缓存schema升113。
- 效果：目标河段起宽5.44→3.02，与邻窗2.98一致；收窄幅度4.19→1.77格，变化率0.0569→0.0241（减半）。36区契约仍全绿（843连接、137端口、732接口），有序shape逐字节不变，生产PNG与旧版仅差20像素（下游两版本就相同）。
- **未解决（如实记录）**：全图"起>尾+0.5"仍19条，其中跨窗口起宽不一致9→7组。剩余7组里4组是**同一上限但宽度不同**、3组是**湖口上限本身在窗口间不同**（最大半宽差5.5，即一处湖被判OK 6.0、另一处OK_SMALL_BODY 0.5）。⇒ 方案A只解决"出湖floor跨窗口不一致"这一支，湖口尺度测量的窗口一致性仍未统一，不能宣称该类异常已消除。

交付：[圈内河段A/B](forge-1.20.1-47.4.10-mdk/build/river-width-trend/junction-agreed-circle-ab.png)、[完整生产A/B](forge-1.20.1-47.4.10-mdk/build/river-width-trend/junction-agreed-production-ab.png)、[右侧对照](forge-1.20.1-47.4.10-mdk/build/river-width-trend/junction-agreed-right-ab.png)、[沿程曲线](forge-1.20.1-47.4.10-mdk/build/river-width-trend/junction-agreed-curves.png)、[证据JSON](forge-1.20.1-47.4.10-mdk/build/river-width-trend/junction-agreed-evidence.json)。基线`prod-1793032532930`，候选`prod-1793037002221`（8281chunk、uncovered0、河61682）。

未测：Forge实机、完整worldgen门禁、隔离性能基准；未提交或push。

### 〇bk、河湖海接点尺度匹配候选（2026-10-27；优先于〇bj）

按已批准的`river-junction-scale-matching.plan.md`实施；没有修改方案、用户TOML、骨架/D8/湖面/水位或搜索预算。不新增邻区final递归或pass2。预览缓存schema升112。仍待用户目检比例，不是水文全周期完成。

- 普通宽度采样不再把`catchmentCells × runoff`当湖面积/净出流；入湖不借整盆Q。只有明确`fromOutlet + sourceBasinOf`才注入该源盆`outflow`，未知身份不借最近湖。JOIN末格属于接收河，支流采样使用其自身末段，避免干流Q反向撑粗。
- 新`LakeInterfaceScale`只读既有湿区和盆地身份：沿明确入湖/出湖方向的有界走廊测横向湿宽；半宽上限=湿宽/16（全宽1/8），夹到0.5—6。狭长湖不按面积等效直径放宽；撞窗/身份未知记录UNKNOWN，不当小湖。比例是视觉候选，不是物理定律。该走廊测的是湿区投影跨度，并非严格局部法向切面，方向会影响尺度。
- 新`LakeWidthEnvelope`把接口上限按弧长展开，距离跨真实串联/汇流接点累加，接点位置用shape投影，不误用River.cells下标。既有同向pass1边额外携带两端上限；final仅沿相同有向格边复用，取上限min，不叠加Q。列级bank jitter不能突破最终段上限。
- 串联保留同断面，真正支流不强制等于干流宽。先承接来流，再对下游硬约束逆拓扑收束上游末段；源头/typed边界平台和湖口上限均有独立检查。没有把河头倒挂或上限例外当正常变化豁免。
- 验证：针对性Gradle编译通过；宽度163断言、连接1137断言、覆盖展宽1317/收窄1131及cap0.5/1.25实际nearest覆盖通过；尺度72与包络76断言通过。详见MDK `build/river-width-trend/junction-final-unit/`。smooth0/8通过为中间同逻辑版本证据，最终默认另测。隔离变异恢复入湖借盆Q、禁生产包络、恢复支流等宽、移除jitter上限均编译成功、基线退出0而目标断言失败退出1；`junction-negative-delivery/`保留最终源码对应的编译、基线和UTF8失败日志及SHA；不使用此前代理基线本身失败时声称PROVEN的无效结果。
- 最终36区`junction-complete-36/summary.json`：采集失败0、契约失败0；843处连接（4串联/839汇流）通过，137已消费唯一端口gap≤1e-9；227894节点未越段上限，732施加约束的湖接口未越接口上限。925接口中193未施加（详见status），不能说所有湖接口都已约束；空河区无上限数组属无样本，不当0。有序shape与`owner-current-36`逐字节一致；未消费/不唯一端口仍保留分类，不作全周期通过。
- 生成顺序复查：最终同版本正序36区与逆序9区的共同45757行宽度/上限/shape字段逐字相同，逆序契约失败0，见`junction-complete-reverse9/`与`junction-order-comparison.json`。另一种子12345单区31连接、2唯一端口及38湖接口通过（在最后仅修外圈串联上限前采集）；不冒充多种子完整36区矩阵。
- 最终正典1401²生产图`prod-1793032532930`：8281chunk、uncovered0，河61702、湖105738、海452384；列SHA `2bb77f4e1ec431c3674c348696b6a27072ed50d1490d2409113a874eddc5326c`。A图用〇bj `prod-1793015404233`，PNG差3725像素；TOML SHA仍`cc5d985f7554e4cfb316708e88373530241b9520e4ce983f745a6defcf50c16f`。中间图全部保留，不把它们叫最终版本。
- 生产大图本次实测450.3秒、旧基线493.7秒；采集存在并行竞争/缓存影响，**不是隔离性能基准，不据此宣称性能改善**。新增接口数组采样和路径属性有成本，最终36区925接口共562154次数组样本（含未知接口），逐项记录samples；未做独立冷区/峰值内存A/B。

交付：[完整生产A/B](forge-1.20.1-47.4.10-mdk/build/river-width-trend/junction-production-ab.png)、[左侧小湖A/B](forge-1.20.1-47.4.10-mdk/build/river-width-trend/junction-left-lake-ab.png)、[右侧河湖A/B](forge-1.20.1-47.4.10-mdk/build/river-width-trend/junction-right-lakes-ab.png)、[汇流A/B](forge-1.20.1-47.4.10-mdk/build/river-width-trend/junction-confluence-ab.png)、[入海A/B](forge-1.20.1-47.4.10-mdk/build/river-width-trend/junction-sea-ab.png)、[宽度曲线](forge-1.20.1-47.4.10-mdk/build/river-width-trend/junction-width-curves.png)、[证据JSON](forge-1.20.1-47.4.10-mdk/build/river-width-trend/junction-delivery-evidence.json)。JSON中的法向河色格心计数是独立生产PNG采样（含弦穿块/抖动/附近河），不是连续精确湿宽或水量证明。

未测：Forge实际放块/洞穴/装饰/流体tick、完整worldgen门禁、全域Q/正式宽深联算。未提交或push。

### 〇bj、真河源沿程线性展宽（2026-10-26；优先于〇bi）

用户认可〇bi整体改善，但指出宽度不能从1格在短距离内突然到粗河，需要线性渐进变化。定点复测确认：`(226,-478)`全长53.368格，旧固定32格smoothstep叠加继承/pin后，第13.13格全宽1.466、第26.28格4.430、第31.22格5.328，增长集中而非均匀。没有把该样本说成实测12格；12格是用户指出的通用上限情形。

本次仅改`RiverWidthProfile`真源头最终剖面：源头0.5半宽标记保留；继承、JOIN与pin计算完后，按到首个显式pin、实际来流接点或当前河口的弧长线性插值，目标接点宽度保持。这样不会被继承值在河头短距离顶粗。明确续流、湖口不执行该线性河头；其它沿程仍保留原局部变化。骨架坐标、全宽上限、宽度接力协议未改。缓存schema升111。线性化只覆盖本trace到首个接点，尚未证明拆分trace无论如何都得同一条全程直线；不宣称全局流量已正确。

- 定点新结果：第13.13/26.28/39.26/53.37格全宽2.082/3.166/4.235/5.398。原始日志`head-ramp-before.log`、`head-ramp-after.log`，曲线`linear-head-curves.png`；均在MDK `build/river-width-trend/`。
- `linear-head-verification.log`：编译通过，宽度108断言、连接1137断言、覆盖探针通过。旧pin有界渐隐测试改用明确续流夹具，避免与新增真源头全程展宽契约混测；无硬接点的继承夹具改验线性分布，另测明确续流保留中段宽度。删除`linearSource`调用的隔离变异编译成功、3项相关断言失败退出1，见`linear-head-negative/`。
- `linear-head-9/summary.json`：9区有序shape.csv与〇bi逐字节相同，171处JOIN差0、26处已消费唯一端口差0，6个未消费/拒收保留。每block半宽变化P99从0.10424降到0.06701，max从0.57314降到0.36108。这些只是段属性证据，不是全周期证明。
- 正典1401²生产图`prod-1793015404233`，8281chunk、uncovered0，河62607（上一版59525）、湖105727（不变）、海452391（上一版452384）。河像素增加说明线性重分布不只是削窄局部，也抬高了部分先细后粗长段；需用户看整图判断。列SHA `5ac230508ead9271796800d9335050188f6575c7587812194300ce20daeb9043`，TOML SHA仍为原值。

交付：[源头放大A/B](forge-1.20.1-47.4.10-mdk/build/river-width-trend/linear-source-ab.png)、[完整生产A/B](forge-1.20.1-47.4.10-mdk/build/river-width-trend/linear-production-ab.png)、[沿程宽度曲线](forge-1.20.1-47.4.10-mdk/build/river-width-trend/linear-head-curves.png)。Forge、完整门禁、最终36区及多种子矩阵、真实12格河头定位与正式断面未测；未提交或push。

### 〇bi、真源头起宽与跨段重置修正候选（2026-10-26；优先于〇bh）

用户指出〇bh候选仍有两项缺陷：真源头过粗、同一路线入海前重新收尖；要求先核对legacy，再授权试修。已复读旧普通路线的OutletSeed.accum/水面携带、续流不重复河头淡出、汇合宽深向下游传播、smoothPath同步插值。未恢复旧链，未照搬initialAccum+localAccum。

- 实际复现：`(226,-478)`非溢口源头半宽2.64774；`(518,96)`上游窗口Q=210.5056，接收窗局部Q显著较小。〇bh用接收方pass1小值钉双方，把上游反压细；只做接点渐变不能消除属性重置。原始定点证据在根目录`debug-agent.log`及MDK `build/river-width-trend/circle-diagnosis.log`。
- `RiverWidthProfile`新增真源头最小半宽0.5与平滑河头，串联后继、明确续流、湖泊溢口不重复淡出；源头约束与JOIN尾约束合并处理，防止近处继承覆盖源头。已连接上游尾宽先传到实际接收shape的后缀，再逆拓扑匹配接点。仍保留原剖面允许的局部浮动，不将全河宽度强制严格单调。
- `Pass1`保存独立格路径的有向边宽度；final仅对两端和方向完全相同的格边复用属性，沿程携带宽度到后缀，不累加不同窗口的Q。实际owner切换发生在halo端口之前，因此重叠路径也需接力，不能只钉halo末点。明确typed wire被消费后补续流标志与宽度，双方以同anchor+next的pass1端口代表约定值。端口代表查询只枚举能覆盖anchor的窗口（含边框等号），不再查询next owner任意九窗；后者曾造成额外pass1扇出，已撤销。
- 骨架、D8、shape、湖泊解算和搜索预算未改；无邻区final递归、不新增pass2。预览缓存schema已升110。新增pass1属性缓存、逐路径查表与投影扫描有开销，尚无隔离性能基准；不能宣称零性能成本。HIER实验链不启用跨区宽度继承。
- 最终编译与针对性探针：MDK `build/river-width-trend/inheritance-last-verification.log`，宽度106断言、River连接1137断言、覆盖展宽1317/收窄1131样本通过。隔离删除河头/下游传播/路径继承三种变异均编译成功、由相应断言失败退出1，见`inheritance-negative-final/`。中间4组宽度旋钮通过；未把中间运行等同最终全部矩阵。
- 最终9区取证：`typed-bounded-9/summary.json`，171处JOIN差0、26处已消费且唯一跨区接点差0；32个incoming中6个未消费/拒收保留。有序shape.csv与`owner-current-9`逐字节相同。每block半宽变化P99=0.10424，max=0.57314。默认端口属性未设的单区抽查18处JOIN差0、typed端口0；seed12345显式端口单区抽查31处JOIN、2处唯一接点差0，见`default-single/`、`seed12345-single/`。
- 中间36区`inheritance-36-v2`虽然shape完全相同、843处JOIN差0，但137处唯一端口最大差5.5，**不是最终通过证据**；该失败推动补typed属性接力。后续`typed-final-9`和`production-typed-final`旧端口范围版本被主动停止，不能引用为完成结果。最终只重测9区，未声称36区最终全绿。
- 最终完整1401²生产图：`prod-1793013690458`，8281chunk、uncovered0，河59525像素；相对本轮前`prod-1792998576834`河54553像素，PNG差6014像素。列SHA `0cc0343748e237a181e1f39dd4a3fbc8cc976bf343ab24cfa85bd9a8243eaaa8`，配置SHA仍为原值。真实源头缩细、目标入海河不再退成细线；湖/海分类可能随宽度覆盖与旧湖岸fallback略变，不宣称水体分类不变。

本轮交付：[完整生产A/B](forge-1.20.1-47.4.10-mdk/build/river-width-trend/production-final-ab.png)、[源头放大](forge-1.20.1-47.4.10-mdk/build/river-width-trend/source-final-ab.png)、[入海段放大](forge-1.20.1-47.4.10-mdk/build/river-width-trend/mouth-final-ab.png)，证据`final-ab-evidence.json`。均为生产合成离线图，不替代Forge运行后验收。

仍未验收：用户目检、Forge、完整worldgen门禁、多种子完整矩阵、最终36区/缓存淘汰/并发顺序矩阵、任意长度河流的全局流量累计与正式横断面。独立审查指出短支流两端pin可能把中段拉成渐变斜坡、重叠路径判续流仍依赖局部结构、逐点投影复杂度，未宣称这些全部解决。本轮尚未提交或push；保留ignored构建证据，禁止clean。

### 〇bh、河道宽度候选：总体趋宽、局部浮动与接点连续（2026-10-26）

用户先要求本地提交再修宽度，局部连接检查点已提交 `a9ff137`，未push。随后再次纠偏：**不是逐点严格单调，而是总体向下游趋宽，局部可适当浮动，河道变化连续平滑即可**。此前助手由“严格单调”推导必须多区递归的说法不再作为方案前提。

- 新 `RiverWidthProfile` 在河道层计算逐shape节点半宽：原Q宽度平滑、沿程趋势与局部偏离；消费骨架内部 `Result.rivers` 的真实有向接收关系。JOIN使用支流真实末端向已确认接收shape投影处宽度，不再取下游源头。连接与边界约定合并成同一份沿弧长的平滑约束；近邻约束之间插值，远处仅32block渐隐。没有修改骨架生成或shape坐标。
- 跨区宽度由**真实next owner的独立pass1**裁定，同一锚点的多个halo发送代表读取同值；不是发送/接收max，更不是附近max Q或重复累加水量。初版两窗max在9区仍有3处不等（最大半宽差2.78596），已用定点日志确认同锚点多发送代表，撤销该算法。仅final河道属性读取既有邻区pass1，pass1自身不读邻区，不递归邻区final、不新增pass2；有额外查表与路径扫描开销，未做性能基准。
- 退役 `unifyWidthAtJunctions/propagateConfluence`；nearest不再把相近河段宽度IDW混合。原bank jitter只改命中后的half、未改覆盖，已移入覆盖判断前，本区/邻区/湖岸fallback共用，并将有效半宽限制0.5—6（全宽1—12）。固定depth及正式横断面**仍未实现**，本轮不把宽度修复说成完整河道生成完成。
- 当前缓存schema **109**。TOML原件SHA仍 `CC5D985F7554E4CFB316708E88373530241B9520E4CE983F745A6DEFCF50C16F`。未统一provider与骨架的端口默认值；本轮图与跨区取证均显式 `-Dgeogenesis.boundaryPorts=true`、保留默认蜿蜒，不能冒充未设JVM属性的默认游戏证据。
- 主线程最终针对性验证：`compileJava/compileDiagnosticsJava`成功，River连接1137断言、宽度剖面94断言通过；覆盖探针验证局部展宽1317样本、收窄1131样本确实影响覆盖。日志在MDK `build/river-width-trend/final-verification.log`。代理声称的11组/10变异无持久日志，不列为本轮正式证据；主线程另在 `variants/` 保存实际运行的旋钮组及反控。
- 9区提交前完整main类副本与候选独立JVM对照：有序shape.csv逐字节相同；171处已测JOIN半宽差从max2.20481降为0；26处已接收且唯一配对端口差从max4.80937降为0。最终36区：843处已测JOIN差0，137处唯一端口差0。161个incoming中139被证明消费；**2处NOT_UNIQUE**各含一个无段宽的退化发送候选与一个差0的有效候选，另22处未消费/拒收，全部保留为缺口，不算全周期通过。曲线只证明段属性，不等于实际湿列/水面/断面验收。
- 36区227896个有序shape节点与此前用户指定后期骨架逐项相同（方向/顺序/raw double均保留），不依赖湿列猜骨架。1px图与河道图分开交付。候选每block半宽变化max0.8132，P99=0.13573；允许局部起伏，不以严格不减作为测试。
- 同参1401²生产河道A/B：提交前 `prod-1792992339398`，候选 `prod-1792998576834`；每臂8281chunk、uncovered0。河像素57832→54553，完整PNG差6885/1962801；候选列SHA `cc88a7285a81e5b1e872220fc67144c0105dd52419d48c3841610fe2d1f9b405`。湖/海像素也略变（并非骨架变动）：宽度覆盖影响既有湖岸fallback与河/海分类，未宣称水体分类逐位不变。

交付图：[生产河道A/B](forge-1.20.1-47.4.10-mdk/build/river-width-trend/production-ab.png)、[段宽曲线](forge-1.20.1-47.4.10-mdk/build/river-width-trend/width-curves.png)、[1px骨架A/B](forge-1.20.1-47.4.10-mdk/build/river-width-trend/skeleton-ab.png)。证据见同目录 `owner-current-36/summary.json`、`before-9/`、`owner-current-9/`、`visual-check.json`。这些ignored build产物必须保留，禁止clean。

补充泛化检查：默认未设端口属性的seed原样本region(0,0)采集退出0，18处JOIN差0、typed端口如实为0；显式端口seed12345的region(0,0)采集退出0，31处JOIN和2处被接收的唯一端口差0。分别在 `default-final-1/`、`seed12345-final-1/`，只是单区抽查，不代替多种子完整矩阵。主线程4组旋钮运行均退出0；“取下游头”和“跳过边界pin”两个隔离变异均编译成功、运行契约失败退出1，日志及结果在 `variants/`。

未验收：用户目检本轮效果、Forge、完整worldgen门禁（旧SkeletonProbe合成溢口及反射错误未修）、多种子完整区域/缓存淘汰/并发取消矩阵、完整每源到湖海有向路径、实际横断面。宽度修复本身尚未再提交或push。

运行（MDK，Java21）：
```powershell
.\gradlew.bat --offline --console=plain -I tools/local-flow-reassessment.gradle runRiverConnectionProbe runModelWidthProfileProbe runWidthCoverageProbe
.\gradlew.bat --offline --console=plain -I tools/local-flow-reassessment.gradle runWidthContinuityProbe -PjunctionTypedPorts=true -PprobeArgs="build/river-width-trend/new-evidence -3 2"
```

<details>
<summary>2026-10-25原始诊断旧稿（含已撤回归因，仅作历史证据；不得按其方案直接实施）</summary>

### 〇az、宽度归因：落块层"格数变宽"是**同一条链自折返叠加**，不是宽度律（2026-10-25）

用户圈出「宽块」（`(507..513, 79..96)` 一带稳定 12 px = `maxWidth`），怀疑宽度突变。
三路只读探针，结论否证了"宽度计算"这一层：

| 探针 | 测什么 | 结果 |
|---|---|---|
| `TraceProbe` | 该区域每段半宽 | 0.961 → 1.123 平滑增长，每段差 **0.002**，零突变 |
| `OverlayProbe` | 该像素被几段覆盖 | `(513,79)` 覆盖 **3 段**、合计 half 2.991；`(515,87)` 覆盖 **4 段**、合计 4.101 |
| `ConfluenceProbe` | 那几段属于谁 | 全部是**同一条 polyline 的连续 4 段** `seg[567..570]`，即**一条链在 2 格内折返 4 趟** |

⇒ 每段半宽只有 ~1.0，3~4 段叠加 ⇒ 视觉 8~12 格。
**宽度本身没坏；坏的是「同一几何被重复发布」**（见〇bc 的跨 region 重复；
本轮实测那 4 段属于同一条链自折返，但那只是一种情形，不是主因）。

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

### 〇bb、"一条河被切成多段"的量化：折线归属层只占 0.37%（2026-10-25，如实）

**建了正确的度量**（不是看颜色切换总数 —— 汇流点变色是合法的）：

> 骨架像素按 8 邻算度数；只在 **`degree == 2`（简单延续）** 的像素上看两侧邻居 id 是否相同。
> 这才是「一条河在这里被切断」，汇流点（degree ≥ 3）不计入。

在 36 个 region 并集（3269²）上，1 像素中心线：

| 口径 | `degree==2` 像素 | 两侧异 id | 占比 |
|---|---|---|---|
| trace 归属（修复前） | 94282 | **348** | 0.37% |
| River 归属（`buildRivers`） | 94282 | **270** | 0.29% |

⇒ **`buildRivers` 只减少 22%，因为它只是把 trace 事后串链（用户判定「治标不治本」）。**
但更关键的是：**0.37% 这个量级与用户看到的"明明是一条河结果被分好几段"不匹配**
⇒ 切分不发生在折线归属层，而在**落块层**：
`SegmentIndex` 按段存 half，逐段各查各的 ⇒ 同一条河在断面上呈"一节节"（§〇az）。
这与用户给的定性完全一致：「从生成管线上也不对」。

**用户已选 A**（骨架内部把 trace 重建为 River，而非事后加过滤器）：
`DeterministicSkeleton.buildRivers` 已落地（`Result.rivers`），
但**宽度/深度/水面仍按 trace 逐段求值** ⇒ **A 只做了一半**。
下一步是把 `dischargeAt → width → depth → surface` 的求值单位从 trace 改成 River。

### 〇bc、全周期几何测量：448/452 分量触水，4 个不触水都在并集外圈（2026-10-25）

判据换成几何而非 `End` 语义（用户口径：河流结束必须入湖/入海）：

```
骨架掩码 ∩ 水体掩码  →  8 连通分量 → 每个分量是否触水
```

36 region 并集（3269²，水体来自同一份求解的 `pipe.lake()` / `pipe.sea()` / `bed<63`）：

| 项 | 值 |
|---|---|
| 骨架像素 | 135420 |
| 连通分量 | 452 |
| **触水** | **448** |
| 不触水 | 4（1114 px，**0.82%**） |
| 不触水分量位置 | `x[-1632,-1534] z[-280,-30]`(642px)、`x[-756,-713] z[-1632,-1456]`(194px)、`x[-886,-844] z[-1632,-1496]`(157px)、`x[-1632,-1530] z[-588,-536]`(121px) |

⚠ **那 4 个都在并集最外圈**（不在 36 个 region 的内核覆盖里），性质是"求解窗口之外没有
可用的邻区信息"，**不是**已证明的孤立河。先前在 1401² 显示窗口内测到 13 个"孤儿"
也是同一原因（窗口硬边把跨区续流段裁断），**那个数字作废**。

**悬空折线端点**（末端既不触水、也不压在别的折线上）：25 / 1720 = 1.5%，
全部是 `end='?'`（折线的 trace 不在 `traces.csv` 里，来自 `OutletLakePrefix.attach`
新增的前缀折线），距离水体 1.4~3.6 格。

**JOIN 串链完整性**：865 条 `End.JOIN` 的 `mouthCell` **全部**被别的 trace 拥有
（`DANGLING_JOIN = 0`）⇒ `buildRivers` 的 `next[]` 没有串不上的情况。

### 〇bc、★ 跨 region 重复发布：同一条河被多个 region 各解一遍（2026-10-25，仅取证，未改代码）

**这是「明明是一条河流结果被分好几段」的真因**，此前所有归因都是错的。

以「折线全部点坐标（round 0.1）排序后的元组」为几何签名，对 36 个 region 的**全部** trace 去重：

| 项 | 值 |
|---|---|
| 几何完全重复的组数 | **239** |
| 涉及 trace 数 | **503 / 1720（29.2%）** |
| 涉及节点数 | **51741 / 227896（22.7%）** |
| 最大重复份数 | **4** |

典型 4 份重复（几何逐点相同，river id 却分别是 25/135/365/443 ⇒ 被当成四条河）：

```
trace=41  river=25   region=-3_-3  end=MOUTH_LAKE  nodes=43
trace=186 river=135  region=-3_-2  end=MOUTH_LAKE  nodes=43
trace=483 river=365  region=-2_-3  end=MOUTH_LAKE  nodes=43
trace=594 river=443  region=-2_-2  end=MOUTH_LAKE  nodes=43
```

**重复全部发生在相邻 region 之间**，且几何包围盒都止于 HALO 边界（如 `z=608 = originZ + HALO`）
⇒ 性质是 **HALO 区被多个 region 各自求解、各自发布**，**不是**接缝续流缺失
（`-Dgeogenesis.boundaryPorts` 那套接线只解决"续流方向"，没解决"重复发布"）。

**它确实导致「宽度变粗 / 宽块」：**

`columnsFor` 的 `covered = dist <= segHalf || segmentMeetsBlock(...)` 对重合段取**并集**，
2~4 段叠加（OverlayProbe：`(513,79)` 3 段合计 half 2.991，`(515,87)` 4 段合计 4.101，
而每段半宽只有 ~1.0）。

**⚠ 但它<b>不</b>导致「身份切分」—— 这是本节的否证结论，别再拿它解释"一条河分成几段"：**

重复的几何占据**相同像素**，`degree==2` 口径下「两侧 trace id 不同」的点在去重前=348、
去重后仍=348（**减少 0.0%**）。因为像素层 first-wins 只画一次，重复件本来就不显形。
⇒ 本节只解释**宽度叠加**，不解释**身份切分**。后者见〇be。

**由此作废的旧结论**：§〇az 把宽块归因于「同一条链自折返」不完整 ——
自折返只是其中一种，**跨 region 重复叠加**是更主要的来源。

**方案（待用户拍板，见 `handoff/GeoGenesis-cross-region-dup-20261025.md`）**：
A1 骨架输出层几何去重／A2 仅 `SegmentIndex` 消费时去重／A3 HALO 不发布只查询。
三者都会改变产出，必须升 `CACHE_SCHEMA_VERSION` 并重跑接缝探针。

**未做**：未改任何生产源码；未验证任一方案；未跑门禁/实机/1401² 大图。

### 〇be、★ 身份切分的真因：68% 的切断点落在 HALO 区，50% 是跨 region 交接（2026-10-25）

对 348 个「`degree==2` 处两侧 trace id 不同」的点做空间与归属归因：

**① 离 region 接缝轴（512 的整数倍）的距离**

| 距离 | 数量 | 占比 |
|---|---|---|
| 正好在接缝轴上 | 3 | 0.9% |
| 接缝 1~4 格内 | 3 | 0.9% |
| 接缝 5~16 格内 | 35 | 10.1% |
| **接缝 17~96 格内（HALO 区）** | **197** | **56.6%** |
| > 96 格（深区） | 110 | 31.6% |

⇒ **HALO 区内合计 238 / 348 = 68.4%**。切断**不是**发生在接缝轴本身，而是散在 HALO 带里。

**② 两侧 trace 的 region 归属**

| | 数量 | 占比 |
|---|---|---|
| **跨 region** | **172** | **49.4%** |
| 同 region | 176 | 50.6% |

**③ 两侧 end 语义（上游 end → 下游 end）**

| 组合 | 数量 |
|---|---|
| `JOIN → JOIN` | 70 |
| `BOUNDARY_EXIT → JOIN` | 54 |
| `JOIN → BOUNDARY_EXIT` | 52 |
| `MOUTH_LAKE → MOUTH_LAKE` | 32 |
| `JOIN → MOUTH_LAKE` | 27 |
| `BOUNDARY_EXIT → MOUTH_SEA` | 26 |
| `MOUTH_LAKE → JOIN` | 19 |
| `JOIN → MOUTH_SEA` | 17 |
| `MOUTH_SEA → JOIN` | 16 |

⇒ 跨区交接类（`BOUNDARY_EXIT` 参与）共 **157 / 348 = 45%**，
其中 `BOUNDARY_EXIT → JOIN` 与 `JOIN → BOUNDARY_EXIT` 合计 106 ——
这是 §〇q/〇w 已知的**跨区续流在接收方以 NEARBY JOIN 落地**的同族问题：
发送方发布 `BOUNDARY_EXIT`，接收方把落点当 NEARBY 汇入，两条身份不同 ⇒ 线上变色。

**结论**：「一条河被分成几段」= **跨区交接的身份不连续**（45%）+ **HALO 带内的重复求解**
（68% 的切断落在 HALO 区，但身份合并需要跨 region 的全局视角，`buildRivers` 做不到）。
`buildRivers` 的 22% 改善上限来自它只在单个 region 内串链。

**未修**。修法必须跨 region 统一身份（世界坐标键 + core 归属裁决），
属架构级改动，需先出方案并与 `boundaryPorts` 接线一并评估。

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

## 一、当前状态（历史快照；最新纠偏与交接入口见〇bf）

| 项 | 值 |
| --- | --- |
| 工作区 | `D:\Office software\Development Project\GeoGenesis-mod`（仓库根） |
| 分支 | `feat/hydrology-target`（2026-10-25 由 `backup/pre-legacy-chain-removal-20261020` 改名） |
| HEAD | 见 `git log -1`（迁移提交：目标效果入库 src/main） |
| 生产水文入口 | `HydrologyChunkEngine.calculate` → 二选一（见 §二 / §〇b） |
| 未推送 | 是 |

⚠ **`_model_wt/` worktree 已不再是独立代码线** —— model 链已于 `848fdc1` 移入 `src/main`。

---

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

## 六、2026-10-07\~09 本轮改动

> 状态更新（2026-10-09 晚）：`f6aa223`/`7eb8748` 已提交；**meander 噪声实验已全部撤销**
> （`git checkout`，未提交）；未提交改动仅剩「断点归因诊断」（见 §六.3）。

### 已修的真 bug（已提交）

| \# | bug | 根因 | 效果 |
| --- | --- | --- | --- |
| 1 | **内陆 region 全域失效** ⇒ 湖整块消失 | `EquilibriumWater:77` 的 `anyTrueDrain &&` ⇒ 窗口无海时 `resolved` 全 false；下游 `LakeWaterBalance:262` 要求 `resolved` | `region(0,-1)` basins **0→54**、lake **0→2699** |
| 2 | **region 缝上断半截** | `columnsFor` 只用本区，不查邻区 | 湖-竖缝断开 **44%→12%**、河-横缝 **58%→37%** |
| 3 | 我的工具判据用错 | 用 `erosion()>0` 判河（应用 `fillWater`） | 已改 |
| 4 | 湖判定方块化 | 粗格布尔按最近格点摊派 | 已改逐块连续判据 |
| 5 | 每 region 求解两次 | 实例级缓存 | 改 static 共享 + 按 seed 键控 |
| 6 | 河穿过湖面 | `RiverNetwork` 成河循环不排除湖面 | 加 `lakeMask` 排除（`f6aa223`） |

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

- **断因 ①「有河却没水」**：断点样本的 `surface − carved` 全部落在 **0.18\~0.50**
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
| --- | --- |
| `runProductionViewProbe` | 走 provider 出图（诊断 model 链，可调 region/step） |
| `runProductionChainCompareProbe` | legacy vs model **同规格并排**（走游戏同一入口） |
| `CanonicalViewProbe -Dhydro.viaProvider=true` | **正典规格图走生产路径**（= 游戏会落的水） |

### ★★ 量化验收基线（2026-10-09，正典规格 1401×1401 逐像素）

|  | 参照图 | 生产（`inlandResolved=true`） | **覆盖率** | **精确率** |
| --- | --- | --- | --- | --- |
| **湖** | 69,530 | 103,128 | **93%** | **63%** |
| **河** | 38,502 | 90,375 | 43% | **19%** |

**判读**：

- **湖的位置已高度吻合**（覆盖率 93%）⇒ "该有湖的地方都有湖"**已达成**；
  精确率 63% ⇒ 多出约 3.4 万格（判据偏宽）。
- **河道精确率 19%** ⇒ 生产河道有 **81% 在参照图里不是河**。

### ⚠ 河道的两类伪影（2026-10-09 出图确认，**当前最大问题**）

正典规格图显示：**北半部河道形态正确**（细、树状分叉、汇流成网），
但**南半部**有两类伪影：

| \# | 伪影 | 现象 | 根因 |
| --- | --- | --- | --- |
| A | **等距平行竖直长线** | 海岸平原上，河道从内陆直插海里、间距均匀 | **D8 在平坦区只能从 8 个离散方向选** ⇒ 相邻列各自独立选向 ⇒ 平行线（与旧文档 §17.6「平坦区流向欠定」同源） |
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

⇒ `STEP=4` 时**最小全宽 4 块**（参照图 1\~3 块）⇒ **河道宽度由地板而非水力几何支配**。

实测（`-Dgeogenesis.riverWidthDiag=true`）：

| step | half∈\[0,1) | half∈\[1,2) | half∈\[2,3) |
| --- | --- | --- | --- |
| 4（修复前） | 0% | 0% | **90.3%** |
| 4（修复后） | **10.9%** | **45.6%** | 29.6% |
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

| \# | 修复 | 根因 | 效果（用户截图版 → 现在） |
| --- | --- | --- | --- |
| 1 | **溢口起链加成河门限** | `basinOutlet` 无条件起链：湖占 5.24% ⇒ 每个盆地溢口都吐一条河。实测铁证 `[HYDRO-SRC] sources=0 traces=48`（源头筛光但河还在=全来自溢口）。**legacy 没有这一步**（河道全部来自流量掩码，湖只是终止条件） | 碎点≤5格 23→**6**（−74%）；短段≤20格 45→**10**（−78%） |
| 2 | **成河门限改绝对集水** | 旧 `n²×0.003` 是"占窗口比例"：legacy 窗口 1536 block ⇒ 7078 block²；我方 708 block ⇒ 1504 block² = **legacy 的 1/4.7** ⇒ 遍地成河。新口径 `chanMinCells×runoff`（discharge 单位 = 上游格数×runoff，实测 runoff=0.0192 校准） | 河格 1.16%→**0.85%**（参照 1.30%） |
| 3 | **谷底取小采样**（用户垂直精度判断） | bed 单点采样 ⇒ MC block 级垂直噪声泄漏进 D8"谁更低"判定 ⇒ 坡面流向逐格抖动=锯齿缠绕。legacy `buildSkeletonRivers` 2026-09-22 已做（4 角最小值）。实现于 `WindowOutlets.build`（`-Dgeogenesis.valleyMin=false` 退回） | **中位分量 37→111（×3）**；真断口 34→**8**（−76%） |

另补齐 legacy 三件套：`minNodes=6`（legacy `minRiverNodes=3`×尺度2）、
`selfApproach` 半径 4 豁免 12（legacy 2/6×尺度2）、**交叉守卫** `segmentCrossesAny`
（legacy `nearbyDownhillNode` 里 `_wouldCrossExistingSegments`，我此前漏掉）。

### 尺度换算表（★ 勿再推错）

| 项 | legacy（`SKELETON_CELL_BLOCKS=8`） | 我方（`STEP=4`） | 换算 |
| --- | --- | --- | --- |
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
4. **最大分量 2988\~3448** vs 参照 12,636 —— 主干仍偏短，待用户目检后定方向。

## 六c、★ 2026-10-10 深夜：用户圈出两处"不可能是伪影" → 定性+修复

> 用户原话：「不可能是伪影啊，我看图明显有这问题啊」（圈了梯子纹 + 两条笔直斜线）。
> 我此前把这两处记为"观感可接受"是**误判**，本节更正。

### 定性（非猜测，逐条归因）

| 现象 | 定性 | 证据 |
| --- | --- | --- |
| **梯子纹**（缓坡河呈"横档+小竖档"） | 锚点钉死 4-block 格心 + D8 缓坡走"横 3\~4 格降 1 格" ⇒ CR 重采样磨圆但档距还在 | 放大图 + 直线占比 33% |
| **笔直斜线横穿河网** | ① minimax 平地 parent=弹出序≈指向最近种子的径向直线（`EquilibriumWater:244` 自述）；② 左上角那几条**是地形冲沟**（用户已三次申明"平行线=地形"，不是河） | §17.6 + 放大图 |

### 修复（两个独立机制，均可单独开关）

| \# | 机制 | 实现 | 量化（正典 r=700） |
| --- | --- | --- | --- |
| 1 | **平地破平局**（追踪侧，§17.6 既定方案） | `traceOne` 里若 `level[p]==level[k]`（等水位区内部），改选 **bed 最低的等水位邻居**（真实地形微坡），bed 也全等才退回 parent。单调性不破坏（level 非升）。`bed=null` 关闭 | 直线占比 33%→31%（**见下方"局限"**）；所有干净指标持平（断口 8、十字 12、双线 3） |
| 2 | **有界粒子锚点**（legacy `PARTICLE_POS` 同构） | `shape()` 里锚点沿 −∇bed 的正交分量偏移出格心（幅度 \`PARTICLE\_AMP\_FRAC·step· | 叉积 | \`，平行不偏、正交最大）。治的是"档距"本身 | **直线占比 33%→15.7%（−52%）**、水平长直段 43→20 段；梯子纹目视消失（三联图中图 vs 右图） |

### ★ 有界粒子的代价（如实，未消除只能折中）

粒子让锚点偏出格心 ⇒ 相邻河段在像素上贴近 ⇒ **贴线统计恶化**：

| 配置 | 真+字 | 2×2重叠 | 并排双线 | 直线占比 |
| --- | --- | --- | --- | --- |
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
