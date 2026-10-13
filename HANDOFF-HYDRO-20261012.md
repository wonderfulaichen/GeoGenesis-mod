# 水文性能与共享湖域交接 — 2026-10-12

## 1. 新对话先读这里

用户最后批准的方向：**先降低共享湖域求解的冷启动成本，再处理河流出区接口；不要直接打开目前的共享湖域原型。**

用户明确口径：效果不必与旧版完全一致，旧版也有不合理之处；当前主要解决性能，形态后续调整。仍必须保持世界生成确定性、生产路径出图，不能靠删孤立河或画桥掩盖问题。

- 仓库根：`D:/Office software/Development Project/GeoGenesis-mod`
- Gradle工程：`forge-1.20.1-47.4.10-mdk`，PowerShell，Gradle运行Java21。
- 分支：`fix/port-lake-p2-2`；当前HEAD **`c353690`**。仅本地提交，未推送；upstream显示 `[gone]`，不要自动推送/重建远端。
- 已跟踪源码干净；根目录PNG、`debug-agent.log`、孤立河清单为未跟踪过程证据，不要清理或广泛git add。
- 原计划 `.openbitfun/plans/hierarchical-hydrology-evaluation.plan.md` 保持未修改。原有AGENTS规则仍有效；HYDROLOGY.md若写“当前跑legacy”/旧HEAD等，已落后于本轮代码和实际配置，须核查代码/日志，不能照抄变动数字。

## 2. 回退点

| 提交 | 用途 |
|---|---|
| `7a1a45a` | 用户指定的历史生产形态参考，不要误替换图 |
| `a70e87b` | 预览漏水文播种修复、卡顿诊断联动、pass1刚需接管；用户曾主观实测约40%改善，非严格A/B百分比 |
| `377f6e0` | 分层水文实验 + 原子键值缓存修复 + 等价height-only优化，**共享湖域之前**的性能回退点 |
| `c353690` | 默认关闭的跨区共享湖域原型 + 接缝探针 + cache76，本次交接提交；**性能未验收** |

不要为了基线验证git stash/restore覆盖用户树。隔离基线可用git archive输出到build内，原固定图不覆盖。

## 3. 生效开关与实际能力

`ModelHydrologyProvider`中的两个静态进程开关：

```text
-Dgeogenesis.hierarchicalHydrology=true   # 默认false：启用粗/细分层实验
-Dgeogenesis.sharedLakeDomains=true      # 默认false：仅HIER=true时参与共享湖域
```

现有游戏默认/实际是否model由 `run/config/geogenesis-common.toml` 的 `hydrologyModelPipelineEnabled` 等决定；探针不带Forge生命周期，必须**加载配置工作副本**，不要仅TerrainParams.defaults宣称实机同参。确定性骨架离线测试使用 `-Dgeogenesis.skeletonTrace=true`。

两个实验开关**都没有改成默认开启**。普通runClient不会自动跑本轮分层/共享实验。用户不喜欢自己找诊断参数：卡顿诊断已由 `worldgenProfilerEnabled=true` 自动联动，不再单独要求 `stutterDiag`。

PreviewDisplay缓存schema现在 **76**，策略指纹混入磁盘缓存键。更改地形/水文产出仍须升级。

## 4. 已实现内容

### 分层与height-only（377f6e0）

- 粗层：`terrainEQuick→heightFromE`，不sampleWu、不预热侵蚀tile；生成主干候选、真正向下游出区的端口。
- 细层：本region固定halo内用真实侵蚀地形；八邻只借粗层。粗主干源头按owner筛选，细支流沿侵蚀parent。
- 形态对比不是硬约束：粗候选消失、湖盆分裂/移动/水位变化仅诊断；非有限值、越域等基本异常仍判失败。
- EQ/LF/HR原先键和值分别AtomicLongArray，单次读键再读值会被其他线程槽替换，造成错坐标值；已改不可变entry整体原子发布。探针逆序侵蚀高度差约0.016块→0，不能把这说成修了侵蚀数学。
- `CellGenerator.erodedHeight(wuX,wuZ)` = `terrainEQuick + erosionDeltaE → nonfinite guard → 第二次softCapLandE → heightCurve`。**第二次softCap不可省**。与sampleWu().height逐位等价，仅跳过气候/分类/坡度等非高度数据。
- model窗口床面、columnsFor及margin只读高度消费者接入erodedHeight。legacy其他消费者未扩大改动。

### 共享湖域（c353690）

核心文件：
- `src/main/java/com/geogenesis/worldgen/hydrology/model/SharedLakeDomain.java`
- 同目录 `ModelHydrologyProvider.java`、`HierarchicalHydrology.java`
- 探针 `src/diagnostics/java/com/geogenesis/worldgen/hydrology/model/SharedLakeSeamProbe.java`

机制：固定对称缝域Key(axis,boundary,alongTile)，廉价粗候选筛选后才真实侵蚀湖域求解。基本半径352，上限512，两档有界尝试；不会无限扩域。共享控制选中的**整个细层湖体和外干环**，不能裁剪到粗层足迹（此前裁剪确实只是移墙，已删除）。干环只影响湖旗标，不剔除河道。

- canonical目前基于粗候选bbox及anchor规则，**不能声称全局盆地身份或细盆地合并/分裂已证明一致**。
- 一个Key有多无关跨缝盆地时只控制首个，其余 `extraEligibleBasins/MULTI` 留痕，尚未全覆盖。
- DOMAIN_CACHE上限24；DOMAIN_INFLIGHT单飞；先发布缓存后完成future再撤占位；失败不缓存。每chunk预解析域，逐列读只读产物。
- 粗细网格半径可能不同，`projectBasinId(gc→gf)`已按世界坐标投影，避免细索引访问粗数组OOB；fine半径对齐step。合成探针覆盖352→512。
- 截断、过大、竞争、漏水/非单平面拒绝留痕，未控制部分仍本区细窗裁决，不能把拒绝当“接缝已修”。

## 5. 证据与实际结果

### 接缝根因

`build/hierarchical-evaluation/scratch/FINDINGS.md`及 `side_diag*.txt`（忽略目录，显式Read/列路径）：

同绝对点 `(-513,-250)`：west窗口lake=true、水面176.89；east窗口lake=false、水面175.31；**bed同为164.05**。是窗口湖域/水位裁决差异，不是侵蚀不同。

原489单侧湿格并非489断河：约377是湖墙，110河格中72属于正常斜向过缝，约29是真断河候选。数字只是该图扫描，不是全部孤立河保证。

### 完整生产路径图（seed9139912035078620160，中心(-140,137)，r700，1401²，1px1block）

| 版本 | 全8281区块耗时 | P50/P95 ms | 最慢块 | 湖格 | 河格 | 单侧湿格代理 |
|---|---:|---:|---:|---:|---:|---:|
| 原提交a70e87b重跑 | 318.8s | 未记录 | 未记录 | 102881 | 22848 | 113 |
| 分层高度优化377f6e0 | 125.7s | 7.4/10.5 | 10.02s | 100796 | 19413 | 489(5.82%) |
| 共享湖域中间版本（粗足迹裁剪，已过时） | 220.9s | 10.0/18.1 | 49.92s | 164988 | 18099 | 161 |
| **最新整细湖体共享** | **150.1s** | **6.7/12.9** | **26.61s** | **176916(9.01%)** | **17551** | **112(1.33%)** |

最新相邻水位跳变>2块142对（原242）；最大2.92块。湖面积增长为真实求解产出，用户允许形态变化，但**未目检验收合理性**。单轮机器负载没有控制，不能宣传准确收益百分比。当前共享原型比125.7s性能回退，尤其冷区长尾；**不要启用**。

最新图：根 `HYDRO-HIER-3way-20261012-153007.png`（sha256 `B5D83A71A6438BE6AB43DED19FFAF6023424F70DDAF7AC817787254C8959DB83`）。左7a1a45a，中a70e87b，右共享实验。
- 前两张来自 `build/hierarchical-evaluation/baselines/pinned/`，本轮字节不变。
- 图和metrics：`build/hierarchical-evaluation/panels-20261012-153007/`
- 最新height-only无共享图：`HYDRO-HIER-3way-20261012-052546.png`，对应panels同后缀。
- `...150819.png` 为中间裁剪版本，勿作为最新。
- 原用户 `AB-3way.png`、A-before、B-now都未覆盖。新的固定ref重跑图**不等于**用户原来那张AB模板；不要自行互换面板。
- 图配置实际sha256 `CE9B6F2CCBC91CCD4B1C769298139F55E6BBA7C0B7806A9E8FF82B69A73B4C6B`；配置指纹 `-9049748980588029035`。配置hash与旧图不同须核实差异，不能无条件称跨轮同配置。加载器用工作副本避免Forge修正写原件。

图manifest props未自动写继承JAVA_TOOL_OPTIONS，本轮已补 `inheritedJAVA_TOOL_OPTIONS=...sharedLakeDomains=true`；新轮要从真实进程日志确认开关。source-diff-fingerprint脚本仅列tracked差异，**遗漏新增untracked类**，不把它单独当完整源码证明；当前已commit可用HEAD完整定位。

## 6. 已运行验证及边界

已确认真实exit0/summary：
- compileJava / compileDiagnosticsJava
- height-only（369点含71真实softcap点、负对照、冷热/逆序/8线程）及singleflight
- runHierarchicalHydrologyProbe real（四region，形态诊断不判fail，真实基础判据通过；边框未验证仍登记）
- runSkeletonProbe / runHydrologyDeterminismProbe / runModelProviderConcurrencyProbe
- runSharedLakeSeamProbe全段；最后整湖修正后repro,det,fine子集；最后网格投影修正后synth。

共享接缝病例两侧都CONTROLLED、lake=true、水位逐位同值。缝带±40扫描仍有1条**非缝线处未归因残余分段**，不得说整湖完全修好。截断负例真实窗口没有触发，NOT VERIFIED，不能因0样本宣称验证完毕。

主要日志：`build/hierarchical-evaluation/shared-lake-verification.log`、`shared-lake-final.log`、`shared-lake-whole-render.log`；探针结果 `build/shared-lake-seam/metrics.txt` 每次覆盖，只最后一次模式生效（目前synth）。PowerShell `*>` 日志可能UTF16LE，Read报非UTF8可用 `Get-Content -Encoding Unicode`；控制台完整查看真实exit，不pipe head/tail。

## 7. 下一步工作（用户已批准，不需重新问是否开始）

### 优先：共享求解冷启动成本

1. 从c353690追完整chunk首次调用：域候选数、廉价筛选、真正共享fine次数/范围、tile生成/缓存淘汰、逐列时间；区分本区fine与共享fine，不能只看FINE_SOLVES=1就声称重工作没有增加。
2. 比较同配置/同坐标、fresh引擎 matched runs（关闭共享 vs开启）和冷/热两段；保留150.1s、26.61s回退证据。不直接加线程、扩大缓存或掩盖预算超限。
3. 考察是否多个轴/邻缝Key反复筛同盆地，是否每chunk枚举过多域，以及新生共享窗口是否迫使远处tile冷生成。避免“缓存恰好存在则采用”的顺序依赖。
4. 只能在确定性正确的前提下减少候选/重算；不能以任意seamBand±96裁剪整个共享湖体，那只会把墙移到带边缘。
5. 更新同生产renderer第三面板，固定一二面板绝不替换；比较冷区尾部与湖岸，不仅总水体像素。

### 然后：河流出区接口（尚未实施）

病例 `(-513,194)`、`(-1,-169)`等：细层新增出区河流在粗层没有对应端口；接收方只收粗incoming，`solveHier`丢弃fine exitSeeds。`Port.level`目前仍没有实际权威消费（确认当前调用点）。incoming可能covered/湖海/短段被吃掉。

不可盲桥；不可直接fine互传出口造成递归/顺序依赖或八邻重解。需要独立的有界边界带路由与共同锚点契约，先解释消费/发射/偏差计数，再改几何。不能声称legacy双pass已经证明无限跨区连续。

**2026-10-13 实测更正**：先前将 `(-512,194)` 的干列 `carvedGroundY=113.37` 当作真实侵蚀高度并据此判上坡，证据不成立。干列返回原始未侵蚀高度；新探针直接 `erodedHeight` 得 110.66、水面109.57，差1.10，并未触发4块下挖上限。`(0,-169)` 实际侵蚀高度199.32、水面198.73，差0.59，也非深度上限。粗端口存在但偏到 `(-516,200)`、`(-4,-184)`；是否这些端口接住对应细河仍未证，不能写成已证明“完全无端口”。

## 8. 可复制验证命令（在MDK目录）

```powershell
.\gradlew.bat compileJava compileDiagnosticsJava --offline --console=plain
.\gradlew.bat runHierarchicalHydrologyProbe '-PprobeArgs=real' --offline --console=plain
.\gradlew.bat runSharedLakeSeamProbe --offline --console=plain
.\gradlew.bat runSharedLakeSeamProbe '-PprobeArgs=9139912035078620160 repro,det,fine' --offline --console=plain
.\gradlew.bat runErosionConcurrencyProbe '-PprobeArgs=height-only' --offline --console=plain
```

runSharedLakeSeamProbe自行在main设置HIER/skeleton/shared开关；普通其他探针不是自动同配置，先查看加载口径。

全图（build-only脚本在磁盘，不入库）：

```powershell
$env:JAVA_TOOL_OPTIONS='-Dstdout.encoding=UTF-8 -Dgeogenesis.sharedLakeDomains=true'
powershell -NoProfile -ExecutionPolicy Bypass -File build/hierarchical-evaluation/run-all.ps1 -ExperimentOnly -SkipExperimentBuild -ExpLabel 'EXPERIMENT LABEL'
# SkipExperimentBuild只能在本轮源码已编译后用，否则会用旧classes。
# 跑完清除当前shell环境继承，避免后续测试无意开启：
Remove-Item Env:JAVA_TOOL_OPTIONS
```

不要让用户重新找诊断启动参数：准备好后按原方式runClient即可采集profiler；真正要测试分层/共享则需明确告诉用户其启用状态，不能把默认链与实验混说。

## 9. 2026-10-13 本轮继续记录（未提交，HEAD仍c353690）

- 先复现首块 `(-54,-37)`：关闭共享11.70s/64 tile/本区fine1；原共享31.61s/208 tile/本区fine1+共享fine5。候选按maxHalf枚举会提前细解实际窗口不覆盖本块的域，其中 `H@-512/t-3` 在本块之外。
- 当前改成两阶段：共享域先粗准备确定实际fine窗口；chunk/单点与实际窗口相交才求解。相交边界按 `Domain.cellAt` 最近格舍入口径（包括半格扩展）计算；整细湖体+外干环仍完整、不按粗足迹或缝带裁剪。fine单飞另按DomKey登记，避免准备对象被LRU淘汰时并发重建；完成结果回存原24项缓存，没有扩大容量或加线程。
- 对照复跑：当前首块27.12s（另轮28.09s）/181 tile/共享fine4，热块约6–8ms；仍高于共享关闭版本，不能宣称性能验收完成。粗筛及其邻粗窗口仍有代价。
- 已出新生产图 `HYDRO-HIER-3way-20261012-162951.png`，对应 `build/hierarchical-evaluation/panels-20261012-162951/`。8281块142.8s、P50/P95=7.3/11.6ms、max21.96s、tile淘汰128（历史共享192）。跨轮负载未控制，不给准确收益百分比。
- 图中湖176916/河17551/单侧湿112/>2块水位跳142均不变；与153007实验面板比较除标题条（y<48）外，全部RGB像素完全相同。固定7a1a45a/a70e87b面板SHA256仍分别 E732B4F2AEEDFAC214BECC808B0E3371DE1135024AA72D016FF269E953F8C922 / 2AF80B6C041D2249C925A28495566B9263C393EB9976D9ABD4AC175433C3F0AC。配置SHA256仍CE9B6F2CCBC91CCD4B1C769298139F55E6BBA7C0B7806A9E8FF82B69A73B4C6B。两个实验默认开关未动，纯成本优化暂未升cache76。
- 新探针段 `cost-on/cost-off`（后接chunkX chunkZ）、`lazy`、`river`。lazy测粗准备零fine、实际窗外零fine、舍入边界、4线程冷fine只解一次。指纹增强包含坐标及carvedGroundY；synth/repro/det/fine/lazy已ALL PASS，原非缝残余分段1仍在。SkeletonProbe、HydrologyDeterminismProbe通过；并发探针应单独无probeArgs跑全段，不能把synth/repro等传给它（它会空转ALL PASS）。
- 河流仅新增R1–R4诊断，几何未改。四个相关区的incoming请求/接受：(-2,0)=1/1、(-1,0)=2/1、(-1,-1)=8/4、(0,-1)=9/4。fine窗口exitSeeds仍未消费（16/0/11/18），但窗口边框出口不等于tract出区点，不能直接互传。
- 日志在 `build/hierarchical-evaluation/shared-lazy-*.log` 和 `river-interface-diagnosis.log`；历史debug-agent.log已复制备份到build，未清空原证据，只追加L/R标识。当前还需完成性能/并发验证、实际incoming拒绝归因以及有界边界路由共同锚点方案，禁止声称河流接口已修。
- 第一次全图在探针仍运行时误启动，已中断，残留 `panels-20261012-162846/` 不作为证据；最终162951是在探针结束后独立运行，exit0。源码指纹/manifest不应认为涵盖后续缓存单飞补强和river诊断；最终交付需另验证最后源码。

## 10. 2026-10-13 续接核验与病例更正（未提交）

- HEAD仍c353690；保留原4个文件改动。本轮额外修改DeterministicSkeleton/SkeletonProbe与provider诊断，**未改河流追踪/路由/宽度/水位决策**；两个实验开关仍默认关闭、cache76未变。原配置SHA256仍CE9B6F2CCBC91CCD4B1C769298139F55E6BBA7C0B7806A9E8FF82B69A73B4C6B。
- fresh进程同配置、同首块(-54,-37)对照：共享关闭冷13.262s/热7.733ms/64 tile/本区fine1；共享开启冷29.837s/热7.157ms/181 tile/本区fine1+共享fine4。粗筛5次，另3域廉价屏幕跳过，H@-512/t-3保持PENDING。各自冷/热列逐位相同。**冷区成本仍未验收，不启用共享原型，不以单轮负载宣称准确收益。** 文件在build/shared-lake-seam/cost-{off,on}-*-<timestamp>.txt。
- R5诊断逐入口记录OUT_OF_WINDOW/COVERED/LAKE/SEA/SHORT/ORPHAN_DEAD_END/ACCEPTED及nodes/end/mouth；R4记录邻端口DIR_MISMATCH/OUT_OF_WINDOW/TAKEN；R6区分本地主干候选来源。只在profiler/stutter诊断开启时收集，不改几何。SkeletonProbe新增合成七类分支覆盖与诊断开/关trace、exitSeeds逐位相同判据，ALL PASS。
- **incoming总数不能等同跨缝端口数**：hierIncomingSeeds将本地粗主干源头和邻粗端口合并去重。本轮四区共20个混合入口：ACCEPTED10、LAKE7、SHORT3（这些数字不是全世界统计）。(-1,-1)真跨缝端口(-404,-516)仅2节点JOIN后因SHORT回滚，其他SHORT2例是本地主干候选。证据抽取在build/hierarchical-evaluation/resume-incoming-evidence.txt。
- **推翻两例“端口未接收/真断河”推断**：(-516,200)被接收并起133节点链到海(-144,580)；(-4,-184)被接收并起27节点链到湖(76,-144)。原同行干列深度上限均未触发，干列carvedGroundY不是侵蚀高度。现有gapWatch/skelDump日志证实河线斜穿/转弯，不能以相邻同z干湿判断断口。
- 两病例进一步按生产columnsFor的真实河判据扫描33×33缝带并BFS 8邻连通（共享关闭）：(-513,194)湿98/起点分量98/东侧30/跨缝连接3；(-1,-169)湿53/分量53/东侧24/跨缝连接2，均connectedAcross=true。**只证明本病例局部连通，不证明全河网连续。** 掩码在build/shared-lake-seam/river-mask-*.txt；完整列/折线日志build/hierarchical-evaluation/resume-river-geometry.log为UTF16LE。下一步必须重新筛真正断河病例，不在这两例上盲桥或强制重路由。
- 最终生产源码验证：compileJava/compileDiagnosticsJava；runSkeletonProbe（含新诊断合成检查）、runHydrologyDeterminismProbe、runModelProviderConcurrencyProbe（无probeArgs，4段全跑）真实exit0/ALL PASS；runSharedLakeSeamProbe synth,repro,det,fine,lazy真实exit0/ALL PASS，最后探针新增缝带BFS后river再编译运行exit0。共享探针结果已留存build/hierarchical-evaluation/resume-shared-final-metrics.txt。HydrologyDeterminismProbe使用defaults且日志effective model=false，只是地形/legacy顺序回归，**不能当model同参验证**；model列确定性由SharedLakeSeamProbe det承载。
- 共享湖病例仍两侧CONTROLLED、水面逐位同值、缝上切开0；非缝残余分段1仍在，截断真实负例未补证。lazy四线程冷域fine只解一次；**未额外强制LRU淘汰中竞争**，不能说该竞态已压力证明。
- 重新核对153007与162951图：固定前两面板字节/SHA256不变，实验面板y≥48全部RGB相同。本轮没有重新出全图（新生产改动仅诊断）；162951图不能声称是本轮最终源码的独立完整渲染验收。未commit/push、未清理原PNG/log，历史debug已另备份到build。
- 后续方案候选：共同锚点必须用绝对坐标、固定有界地形域和明确方向/水位语义；不能让邻区fine互传导致递归或缓存决定产出，不能把Port.level（minimax逃逸高程、当前无权威消费者）直接当水面。只有重新确认真断点后才确定最小修复面；共享成本仍是优先未完成项。

## 11. 2026-10-13 批注续做：反向预热与真断口重筛（未提交）

- 本轮生产改动仅CellGenerator.prewarmErosionTiles：**固定覆盖窗内反向遍历**（高坐标→低坐标），刚需WindowOutlets仍低→高扫描，使后台与刚需从两端推进，不先占住同一批tile。线程池仍2线程/队列32，tile缓存仍512，共享域缓存仍24；不扩大范围、不裁湖、不改侵蚀数学。只影响调度，因此cache仍76、分层/共享实验默认仍关。
- 实际证据：原向预热fresh cost-on首块(-54,-37)冷27.306s（另一轮28.432s），181个真实tile生成，blend累计19.824s（**含生成+等待，不是净阻塞**）；L5主需求线程真实join等待122次/12.109s。改反向首轮17.597s、复跑18.042s，仍181 tile、localFine1+sharedFine4；L5首轮主线程join4次/62.6ms。反向共享关闭冷7.708s。机器负载未严格配平，不给准确收益百分比，**共享额外成本仍约10s，尚非验收**。
- 首块完整列序列保存并对照：原向与反向cold的SHA256均E10E45976D4A2274E954A699B94E67FD70B0EB3FC467E2E6A3E4BAED81A049A6，含坐标/湖/水/水面/床面。证据build/hierarchical-evaluation/cost-preheat-observed.log、cost-preheat-reverse.log、cost-preheat-reverse-repeat.log、cost-preheat-reverse-off.log（UTF16LE）；baseline列在cost-preheat-baseline-columns.txt。
- L4测试screenOut“低侧已命中仍取高侧”：首块所有高侧coarseSolves=0（已缓存），不是本病例主要机会，**未做该短路修复**，仅保留诊断。L5诊断每tile记录等待；历史debug先另备份，不删除原证据。
- 重新从162951生产面板筛候选，新增river-candidates段（默认all不跑它），BFS拓展支持横/竖缝；保持原river二病例负对照。真实生产columnsFor（shared开启）6候选全是起点湿、局部分量不跨缝：(-710,511)、(-650,511)、(-582,511)、(-78,511)、(-513,359)、(-513,447)。前4湿34/分量34/far0/cross0；(-513,359)湿42/起点分量28/far0/cross0；(-513,447)湿34/分量34/far0/cross0。诊断ALL PASS只表示取证不空转，**不表示连通通过**。
- 横缝前三例已具较强根因证据：上游(-2,0)细折线明确越过z512继续到halo，(-710,511)的poly4 source(-776,376)→mouth(-644,608)，(-650,511)poly28 source(-712,128)→JOIN(-588,572)；接收方(-2,1)在该位置无折线（gapWatch PLAIN_COL no hit），发送区粗端口列表只有朝东(-516,200)，**没有朝南端口**。因此是细层新增跨tract路径无粗接口表达，非同行斜穿误判。(-78,511)邻海仍待自然河口排除；竖缝两例是窗口路径差异，未直接归因“无端口”。
- 真病例日志build/hierarchical-evaluation/river-candidates-final.log（UTF16LE）/river-candidates-metrics.txt；33×33掩码build/shared-lake-seam/river-mask-*.txt。**尚未改河流几何**。下一步有界共同锚点方案须能表达细层新增跨区路径，而不是只把现有粗端口钉死；仍禁止邻fine递归互传或画桥。
- 本轮真实exit0验证：compileJava/compileDiagnosticsJava；runErosionConcurrencyProbe singleflight（含-Dprobe.saturation=true，池满不在调用者运行、12并发同产物、失败/陈旧miss路径）、height-only（369点/71softcap、负对照、冷热/逆序/8线程）；runSharedLakeSeamProbe synth,repro,det,fine,lazy（结果已存preheat-reverse-shared-tests.txt）。非缝残余湖分段1仍在；强制LRU中竞争、真实截断负例未补证。
- **已出最终源码生产全图**：根HYDRO-HIER-3way-20261012-194838.png；panels-20261012-194838，label REVERSE PREHEAT (NOT ACCEPTED)。独立运行exit0，8281块95.6s，P50/P95=4.9/6.3ms，max16.486s，cold18块46.4s、hot8263块49.2s。与上一轮142.8s/21.96s只作实测对照，不宣称稳定百分比收益。
- 最终图湖176916/河17551/单侧湿112/>2水位跳142均不变；与162951图**标题条以下y≥50所有RGB逐像素完全相同**；固定前两面板字节/SHA256不变。标题实际50行（barH=max(48,n/28)），遮住z≤-514，不能拿图上z=-513以北作候选证据。manifest补真实继承JAVA_TOOL_OPTIONS sharedLakeDomains=true；源码diff包含7个tracked文件，无新增生产类。配置SHA256仍CE9B6F2CCBC91CCD4B1C769298139F55E6BBA7C0B7806A9E8FF82B69A73B4C6B。
- 未提交/推送，未启用实验，未清理原PNG/log。总体状态：等价调度优化验证通过，但共享湖域及河流接口整体仍未验收。
