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

## 一、当前状态（历史快照；新实验状态见〇c—〇p）

| 项 | 值 |
| --- | --- |
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

|  | legacy 链 | model 链 |
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
