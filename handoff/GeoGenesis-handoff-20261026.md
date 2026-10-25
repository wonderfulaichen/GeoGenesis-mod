# GeoGenesis 冷启动交接 — 2026-10-26

## 0. 接手先读：现在不是“水文全周期已完成”

用户最新指令是：**先整理、本地提交、清理工作环境，换新对话**。此前最新技术方向是**先研究移除的旧河流机制**，借经验解决当前问题，不是重建水文或立即恢复旧链。

新对话阅读顺序：本文件 → [旧机制研究](research/legacy-river-mechanism-20261026.md) → [HYDROLOGY.md](../HYDROLOGY.md) §〇bf及§二 → 当前代码。不要从旧交接中的“已完成”“真因”直接继续实现。

### 不可改变的用户要求

1. 水文全周期：每条保留的有向河最终必须入湖/海，不能非正常终止；汇流、跨区只是中间连接，必须有真实下游。
2. 骨架从头到尾连续，是中心线路线；宽度、深度、水面、断面属于后续河道生成层。骨架诊断图画同一生产shape的**1像素中心线**，不是落块湿格。
3. 维持现有条件D8底座和用户认可的路线；不另建水文、不增加搜索预算/rise、不递归邻区final、不重复pass2，不复杂化。
4. 南部长直用户明确认为是地形导致的合法路线，**不要修它**。
5. 宽度用户要求全宽1—12格、自然渐变；湖后河应结合湖泊规模。现有宽度实现未验收，不能由本次提交宣称完成。
6. 用户选A：骨架内部输出河流单元，不要骨架后另走一遍JOIN过滤器。研究建议优先核对局部连接契约；是否调整River表示需据证据解释，不擅自全局ID/去重重构。
7. 用户只看效果，形态结论需同参生产A/B；不要只报统计。旧湖岸方块精度议题曾被用户搁置，不自行重启。

## 1. 当前状态、半成品与提交边界

- 根目录：`D:\Office software\Development Project\GeoGenesis-mod`；实际Gradle工程在其子目录 `forge-1.20.1-47.4.10-mdk`。
- 分支：`feat/hydrology-target`，原HEAD `9de0e15`。本次在原分支本地分批保存，不push、不改分支、不改main、不删worktree/stash。`bfcfb6b`保存未完成River与缺陷复现，`a07c165`保存旧discovery研究源码；交接整理提交及最新HEAD以 `git log -3 --oneline` 为准。
- `2295717`保存用户认可过的连续位置/shape调整；`9de0e15`保存带已知缺陷的宽度链。**不要把9de0e15误当作纯骨架已验收基线**。
- 当前生产只有model：`HydrologyChunkEngine.calculate → ModelHydrologyProvider.columnsFor`。旧TOML里的model开关键不构成分支证据。
- `PreviewDisplay.CACHE_SCHEMA_VERSION`本次实读为**108**，整理未修改。以后只要改变实际地形/水文产出必须升级；当前宽度检查点本身已有未定稿产出，不可凭schema相同认为缓存与源码完全等价。
- 本次整理不新增功能修复。保留以下两个main文件此前的未完成实现：`DeterministicSkeleton`增加`River/buildRivers/Result.rivers`，`ModelHydrologyProvider`增加两个包内只读探针访问器。
- **半成品缺陷：** 中段JOIN后`buildRivers`把整条下游trace从源头再拼一次，而非只接汇入点后的后缀。另有共享下游/环/跨区身份等表示尚未完成。`Result.rivers`当前只有构建赋值与诊断读取；生产shape、SegmentIndex、宽深仍消费traces，因此该缺陷不能解释引入River之前的画面。
- 现有River构建仍在生产build中执行，会有聚合分配开销；未测性能。不把“没被河道消费”等同于零开销。
- 两个具体core湖出口之前单次捕获：52097准入Q105.0448、两格后MOUTH_LAKE；94177准入Q25.568、三格后MOUTH_LAKE。26份非计时输出曾与旧基准字节相同。该证据针对当时隔离候选，不是当前全周期验收；默认reason=NO_RECEIVER不能独立解释真实停止分支。

## 2. 旧机制研究的可继承经验与撤回结论

完整证据、源码行号和局限见[研究全文](research/legacy-river-mechanism-20261026.md)。本次重新核对五个legacy核心文件Git blob：当前diagnostics与移除前`b9b55ee`一致；`7e69dfc`将它们退出生产，不是丢失源码。

有效经验：

- 旧`RiverPolyline`节点同时保存几何、水面、宽深，smoothPath同步插值，采样使用同一段同一投影参数；不必为了属性连续先建立全世界唯一River对象。
- 同区JOIN按真实汇入节点`recvIdx`继承/衔接；当前`propagateConfluence`取`downSeq[0]`，与实际中段接点可能不同，需定点核对。
- 旧普通路线接力种子带位置、累积面积、水面、层级和方向；当前typed端口更严格地约束几何，但没有同样的沿程属性。不能因此倒退端口校验，也不能照搬`initialAccum + localAccum`而重复计水。
- 旧Carver独立构造横断面；当前固定depth加兜底并不等价于正式河道断面。列结构不带half不是判定断面的充分依据，要看消费公式。

**以下旧判断已撤回，不得当作后续方案前提：**

- “几段半宽相加，所以河变粗”：当前IDW是加权平均，不是求和；完全相同集合整体复制不改变平均或布尔并集。局部重复/偏移的影响需实测。
- “239组逐点完全重复”：旧签名round到0.1并排序，丢了方向/顺序，只证明签名相同，不允许据此删生产几何。
- “degree==2异色证明断河/宽度断层”“HALO占68%证明根因”：只是定位分布，不是有向连接或属性证据。
- “448/452无向分量触水证明全周期”“外圈4个必是假孤立”：不能证明每条有向源到终点，外圈仍须追查。
- “865个JOIN有owner说明River正确”：只能证明有人占格，不证明下游有序后缀正确。

[跨区重复旧稿](GeoGenesis-cross-region-dup-20261025.md)保留原始数字但已在开头标失效；HYDROLOGY §〇az—〇bd折叠为历史，§〇bf优先。A1/A2/A3去重、全局身份、HALO发布规则重写均未实施，不是当前已批准方案。

## 3. 环境与依赖

- Windows PowerShell，JDK：Temurin **21.0.11**，本轮`java -version`确认；安装于 `D:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot`。
- 启动时`JAVA_HOME`为空但PATH能找到该JDK；Gradle命令显式设置JAVA_HOME更稳妥。
- Gradle wrapper本轮报告8.8；Forge1.20.1/47.4.10。本轮离线编译依赖已齐，不需要换镜像或联网下载。
- 本机缓存 `D:\.gradle`；独立javac的已有classpath在MDK的 `build/local-flow-reassessment/classpath.txt`。这是本地生成文件，不保证fresh clone自带。
- 用户配置在MDK的 `run/config/geogenesis-common.toml`。整理前实测SHA256：`CC5D985F7554E4CFB316708E88373530241B9520E4CE983F745A6DEFCF50C16F`；**不可引用更早8dc9…哈希假装当前配置相同**。本次不编辑配置。
- 没有部署/在线服务/凭据移交；本次不发布jar、不替换运行实例、不启动游戏、不杀用户程序。

## 4. 关键文件地图（相对仓库根）

| 要看/改什么 | 入口 |
|---|---|
| 稳定约束 | [AGENTS.md](../AGENTS.md)；其中“两条链”与Midpoint旧生产类型描述也有历史漂移，当前以实际调用为准 |
| 当前水文事实与纠偏 | [HYDROLOGY.md](../HYDROLOGY.md) §〇bf、§二 |
| 本轮旧机制研究 | [legacy-river-mechanism-20261026.md](research/legacy-river-mechanism-20261026.md) |
| 路线、JOIN、River半成品 | [DeterministicSkeleton.java](../forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/hydrology/model/DeterministicSkeleton.java) |
| provider final/pass1、shape发布、宽深消费 | [ModelHydrologyProvider.java](../forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/hydrology/model/ModelHydrologyProvider.java) |
| 几何跨区承诺 | [BoundaryPort.java](../forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/hydrology/model/BoundaryPort.java)、[WireIn.java](../forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/hydrology/model/WireIn.java)、同包PortEgress |
| 原宽度律 | [RiverCenterlines.java](../forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/hydrology/model/RiverCenterlines.java) |
| 骨架现有探针 | [SkeletonProbe.java](../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/hydrology/model/SkeletonProbe.java) |
| 中段JOIN已知缺陷复现 | [RiverJoinCheckpointProbe.java](attachments/RiverJoinCheckpointProbe.java)，是缺陷特征复现，不是通过验收 |
| 旧节点属性与跨区状态 | [RiverLineRegion.java](../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/hydrology/riverline/RiverLineRegion.java) |
| 旧汇入/插值实现 | [RiverLineNetwork.java](../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/hydrology/riverline/RiverLineNetwork.java) |
| 旧横断面 | [HydrologyBlockCarver.java](../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/hydrology/HydrologyBlockCarver.java) |
| 出图基准检查点 | [TARGET.md](migration-checkpoint-20261025/TARGET.md)，参数/源码来源须重核，不能与9de0e15宽度实验混淆 |
| 本地图片/CSV/脚本位置和哈希 | [handoff-20261026-local-evidence.json](attachments/handoff-20261026-local-evidence.json)，ignored build证据留原地，不随clone分发 |
| 本轮验证明细 | [handoff-20261026-verification.json](attachments/handoff-20261026-verification.json) |

## 5. 操作runbook与实际验证

下列命令在仓库根打开PowerShell后执行。第一组本轮实际运行过；结果是**编译完成，探针失败**，不要把Gradle编译成功说成水文验收通过。

```powershell
Set-Location 'D:\Office software\Development Project\GeoGenesis-mod\forge-1.20.1-47.4.10-mdk'
$env:JAVA_HOME='D:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'
.\gradlew.bat --offline --console=plain compileJava runSkeletonProbe
```

结果：`compileJava UP-TO-DATE`、`compileDiagnosticsJava`成功。`runSkeletonProbe`先报告合成地形溢口(4,6)未覆盖，随后`runSeaClaims`反射旧`traceOne`签名抛`NoSuchMethodException`，退出1，后续断言没有执行。因此**不能再沿用“22 PASS/1 FAIL”代表当前完整探针结果**。本次没有顺手修改探针或路由。

本轮另外在隔离目录从`git show 9de0e15:...DeterministicSkeleton.java`编译baseline，置于classpath首位重跑同一SkeletonProbe，**同样复现了溢口覆盖失败及反射签名异常**。确切结果与缺陷复现结果写在验证JSON；没有checkout、stash或改用户源码做对照。入库baseline日志由原始混合编码转成UTF-8并清除行尾空格，仅供阅读；原始字节仍在本地验证目录。

中段JOIN复现命令（本轮实际执行，结果应打印`KNOWN_DEFECT_REPRODUCED`及`RIVER_CONTINUITY_ACCEPTANCE=FAIL`）：

```powershell
# 仍在MDK目录；classpath.txt须来自当前工程，不要混入隔离候选覆盖生产。
$cp=(Get-Content 'build/local-flow-reassessment/classpath.txt' -Raw).Trim()
$classes='build/handoff-check-20261026/classes'
javac -proc:none -encoding UTF-8 -cp $cp -d $classes '../handoff/attachments/RiverJoinCheckpointProbe.java'
java -Dfile.encoding=UTF-8 -cp "$classes;$cp" com.geogenesis.worldgen.hydrology.model.RiverJoinCheckpointProbe
```

若classpath不存在，仓库已有生成入口，以下恢复命令**本轮未执行**：

```powershell
.\gradlew.bat --offline --console=plain -I tools/local-flow-reassessment.gradle localFlowClasspath
```

本次补齐旧discovery runner引用但未入库的`ContextPrefixProbe.java`、`PortCandidateCoverageProbe.java`，保留在原受跟踪的discovery诊断目录。六个源码只做javac编译及Python AST校验；语义runner是针对旧隔离候选，**不是当前main的验收**，不要直接把其结果归给当前生产。

未执行：完整`runWorldgenGate`、Forge实机、任何新1401²大图、多种子/负大坐标/下游先查/淘汰/并发/取消重试矩阵。用户认可继续方向，不等于这些已完成。

## 6. 证据整理、风险与回滚

- 不运行`gradlew clean`或`git clean`：MDK的ignored `build/d8-spill-contract/pruning/`保存了候选源码、原图、CSV、日志，很多是唯一证据。只在仓库根做`git status`干净不代表这些不存在。
- `lake-edge-1792872387157/skel-out`那次日志即使打印`SKEL_COMPARE_OK`，也显示`pixelsA=0 pixelsB=0`；这批图计数失效，**不可用作验收**。后续`dump-out`有另一次导出，统计也只作历史观察，不能证明全周期。
- 本地证据清单记34个文件哈希；正文研究已从ignored计划复制到tracked handoff，不再依赖会话记忆。原计划与实验目录保留原地。
- `handoff/attachments`原有7份log/txt来自更早共享湖域/预热实验，本次只保存归档，不宣称其ALL PASS证明当前实现；不移动以免破坏旧相对引用。
- 本次整理前8个dirty tracked文件的完整patch及SHA放MDK的 `build/handoff-check-20261026`，用于对照。编译日志/隔离baseline也在其中，验证摘要另入库。
- 不碰已有 `_model_wt`、`D:/gg-portlake-wt`，不删两个既有stash。它们可能保存独有历史，不是本次清理垃圾。
- 需要撤销River检查点时，应先确认无新改动，单独反向提交相应检查点；不要`reset --hard`覆盖整个分支，也不要直接回到04bdc73丢掉后续已认可的shape工作。
- 需要保留原图和新图对照时固定seed、中心、尺度、TOML、JVM属性、classpath及源码哈希，PNG索引是`pz*width+px`；格索引是`i*nz+j`，STEP从pipe读取，不能把REGION512当STEP。

## 7. 下一轮待办与决策点（尚未执行）

| 顺序 | 要做什么 | 责任/阻塞 |
|---|---|---|
| 1 | 读旧机制研究，确认研究结论与当前代码；把“颜色身份”与“有向几何/属性连续”分开 | 新对话接手agent；无需重问用户已给约束 |
| 2 | 若用户授权继续修River，先设计中段JOIN只接真实下游后缀、共享下游/环的表示；实际Java函数级测试须包含反例，不只数River压缩率 | 需明确实施授权；本次只保存半成品 |
| 3 | 同一次生产求解中各选一个同区JOIN与跨区续流，记录实际汇入索引、Q来源、半宽、深度、水面与河头标志 | 接手agent取证；不先用附近max Q或全局ID掩盖 |
| 4 | 据证据继承必要沿程属性；不照搬旧重复计水公式、不降低typed端口严格性 | 先最小方案，再编码 |
| 5 | 独立完成正式河道断面，提交同参1px骨架与完整河道A/B供用户观察 | 连接契约确认后再实施；不把测试落块当正式河道 |
| 6 | 补查旧剩余25处、57端口的有向后继及真实湿列；多种子/顺序/淘汰/并发/取消矩阵；修复或明确处理失效探针后跑门禁与Forge | 不得凭历史记录宣布已通过；以真实样本和非零FAIL为准 |

本次提交是**可恢复检查点+研究交接**，不是“河流衔接、宽深、全周期全部修好”。
