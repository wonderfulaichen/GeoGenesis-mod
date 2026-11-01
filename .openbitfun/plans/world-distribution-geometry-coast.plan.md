---
name: 大陆分布、可靠几何与P4海岸统一主线
overview: 独立诊断驱动R3传播655047项通过；R4同窗六通道A/B、43809测线与成本已交付，包络差及无缓存约8.3倍驱动成本须用户验收。未接构造地貌/P4，728份旧资产与生产不变；最新见§13。
todos:
  - id: wide-distribution-baseline
    content: 已完成56000×56000块基础海陆标签与连通形态总览，标出原S3窗口；参数与高度链不动，证据见§7
    status: completed
  - id: confirm-distribution-target
    content: 已定稿海陆判定偏移 +0.30（沿原大陆场收窄陆地），废弃大洲组织路线；见§8
    status: completed
  - id: threshold-height-baseline
    content: M1a已完成显式0.30同源接线/全幅真实高度/50109剖面及5923734断言验证，旧S3逐字节一致
    status: completed
  - id: tectonic-driver-preflight
    content: R3数值传播通过，R4 A/B/剖面/成本已交付；包络变化与成本待用户验收，不得自动进入M1c
    status: pending
  - id: tectonic-shape-candidate
    content: M1c以连续陆地权重、既有褶皱断层及真实洋中脊门控形成分量可归因B臂，不重抽基础海陆
    status: pending
  - id: tectonic-acceptance
    content: M1d完成真实高度A/B、分量图、剖面、成本和确定性验收，满足条件后进入P4
    status: pending
  - id: verify-geometry-foundation
    content: 按实际支持域失败插入数值几何修复；N3～N5仍须独立授权，不放宽旧容差
    status: pending
  - id: coast-local-stage
    content: 在确认后的600体系上按P4实现特殊海岸局部臂，保留240普通带与既有海岸参数
    status: pending
  - id: integrated-world-validation
    content: 合流验证完整高度、水面、海岸作用域、跨tile与多seed，并出同源大图及剖面
    status: pending
---
# 大陆分布、可靠几何与P4海岸统一主线

## 1. 本轮裁定与授权

用户明确：

1. P3旧图先不动；沿当前600/S3体系，稍后做P4海岸，参数与240带不改。
2. 希望远坐标几何方案与世界共用形态引擎方案并案。
3. P4前先看大面积大陆—海洋格局，再调整海陆比；希望大陆成片、周围有广阔海洋。
4. 本轮问答已选择：**先分布总览**，约56000×56000块、同seed同中心、先不改任何生成参数；不将标签图冒充最终水面或高度图。

本文件是两份方案的**统一排期与验收入口**，不是合并实现或删除旧文件：

- [world-shape-engine-unification.plan.md](world-shape-engine-unification.plan.md)：保留P0～P5的节点引擎与海岸详细契约。
- [far-coordinate-geometry-repair.plan.md](far-coordinate-geometry-repair.plan.md)：保留N0～N5数值几何详细契约。
- [coherent-600-world-distribution.plan.md](coherent-600-world-distribution.plan.md)：600/S1～S3的现行分布、浅海、外边权重规则与历史证据。

**最新状态以§13为准。** M0与+0.30已本地提交`d47de74`（未push）；M1a高度接线与独立驱动R0～R2通过，旧M1b原驱动FAIL证据保留。本轮R3传播通过、R4驱动A/B/剖面/成本已交付待用户验收，未接M1c/P4。早期“待确认/未实施”等为历史快照；600/240/旧S3/0.30/生产不动，N3～N5与生产迁移不由此自动授权。

## 2. 基线与统一关系

唯一当前参照为600/S3：600微细胞、1200宏锚点、S2浅海匹配、同配方并集外边权重、原NodeMorphEngine和旧陆覆盖。P3/1200仅为历史对照，不能再当当前布局要求用户重新确认。

S3已被用户接受保持，无须再要求“先定稿一次才能往下走”。此次新增的是**扩大观察范围与重新审视宏观海陆格局**，不是撤回S3。

- 旧S3 raw/PNG与原7000块样点窗口不覆盖、不改现行基准。
- 微尺度600、宏地貌锚点1200、普通总带240/半宽120保持；18陆地和5海洋配方、seed/幅度/频率/bias、HeightCurve、海平面、覆盖语义不动。
- P4海岸既有80～116块加工带、剖面参数暂冻结，不能拿240带替代海岸加工带。
- 用户未来批准海陆分布调整时，新候选的**具体坐标布局可以变化**；保留的是600/S3架构和旧基准，不承诺“改变海陆比却所有标签逐位不变”。
- 默认、生产、水文、cache schema不动，不新增方块/物品，不自动提交推送。

统一主线：

```text
M0 原状大范围分布总览
 → M1 用户决定海陆比例与大陆形态
 → 必要时单变量分布候选/同范围A/B验收
 → 确认后的600体系
 → P4特殊海岸局部验收
 → 完整世界合流验收

数值几何N2证据复核 → N3/N4/N5独立候选验证
                         └→为完整高度、海岸几何与全域声明提供共同基础
```

可以统一管理、共享类型/几何/求值上下文，但**分布、数值几何、特殊海岸三种变量分别出臂验收**，不一次全改后无法归因。

## 3. M0：先看原状分布，不改参数

### 3.1 固定规格

- seed=`9139912035078620160`；中心=(-140,137)。
- 1401²，step=40块，样点跨度56000×56000块；样点原点=(-28140,-27863)。这只是扩大观察范围和采样步长，**不是把600细胞改成更大格子**。
- 在图上标出原S3样点窗口：X[-3642.5,3357.5]，Z[-3365.5,3634.5]。
- 不换seed挑图、不移动中心寻找好看的大陆，不画地图边框海洋，不把触边陆地伪装成完整大陆。若仍大量触边，如实标记并建议下一档范围，不宣称已经看全。

### 3.2 真实数据入口与等价证明

[CoherentRegionDistribution.java:25-45](../../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/terrain/candidate/CoherentRegionDistribution.java#L25-L45)提供微分区和实际宏锚点选型；c场为既有4000/6，海陆抽签在宏锚点进行。复用它们，不另写一套世界分布。

首图只需要**旧float前向warp → S3同定义NATURAL微细胞赢家 → 原CoherentRegionDistribution.choose → 基础海/陆标签**，不需要world折线、反warp、S2浅海距离或高度求值。

注意：不能直接把RegionPartition的3×3 float赢家当S3精确赢家。S3路径是float前向后提升、double站点势求最小值；新只读导出器需按同定义的有限候选上界完整枚举，保持原遍历/并列裁决。以原窗口真实LocalBoundaryGeometry.exactWinner和冻结cell key/基础标签做独立对拍，再扩大候选负控；不把新导出器与自己对拍作为证明。参考[LocalBoundaryGeometry.java](../../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/terrain/candidate/LocalBoundaryGeometry.java)。

现有[CoherentLayoutGallery.java:51-66](../../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/terrain/candidate/CoherentLayoutGallery.java#L51-L66)仍构建tile折线，不能只改step就称为“无反解总览”。实施时用独立只读诊断入口；不修改旧Gallery默认或接N2几何。

S2只替换海洋内部配方，不改海陆Side0，因此基础海陆图可用于本次大陆格局判断；但内部海洋配方图必须注明“S2匹配前”，不能称最终S3类型图。

### 3.3 交付与统计

至少给出：

1. 海陆双色全幅大图：真实600微细胞上的基础标签，含比例尺、坐标、原S3窗口、触边标记。
2. 同范围大陆连片诊断图：主要陆块/海盆及面积分布；必要时叠加原c场/海概率供诊断，不把它直接当最终大陆轮廓。
3. 原7000块窗口的同定义局部核对，确保大图没有换算法或退回P3/1200。
4. 完整元数据、原始标签/细胞键、SHA、运行日志及可复现入口；无任何地形JVM覆盖属性。

统计分开报告：基础陆/海标签比例；可见连片数、最大陆块份额、面积分位、孤立小片比例；触边/未触边分别列。40块栅格4邻接/8邻接均只作**近似诊断**（角接触与窄海峡可能误判），不能叫解析连通证明；仅离线统计，绝不把组件/面积配额反馈到世界运行选型。

**不输出或声称**最终Y<63水面比例、真实海岸线、海底高程、P4效果、无限世界验证通过。它们都需要完整高度链；不以赢家onehot高度或Python合成高度充当S3高度。

## 4. M1：海陆比与大陆形态分别决策

当前海陆分配是c场影响概率、每个1200宏锚点独立hash抽签，见[CoherentRegionDistribution.java:36-45](../../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/terrain/candidate/CoherentRegionDistribution.java#L36-L45)、[RegionalMorphWorld.java:71-74](../../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/terrain/candidate/RegionalMorphWorld.java#L71-L74)。相同总海洋比例并不保证完整大陆或广阔连通海洋；宏锚点局部抽签可能贡献碎片，但在新大图前**不预先判定必须换算法**。

M0出图后再确认：

- 只是希望海更多/陆更少，还是大陆需要更成片、海洋需要更连通，或二者都要？
- 目标是这一固定范围的视觉格局，还是较大尺度的统计倾向？不能把一个有限窗口百分比当无限世界精确配额。
- 不擅设“现实=海71%陆29%”；用户未给目标值，也未授权构造板块运动/地质模拟。“大陆板块”在本轮先解释为成片陆块的视觉与连通需求。

后续选型先方案再实施：纯比例候选仅动分布概率控制；若要大陆成片，另评连续海陆组织/相关抽签等方案。保持600微几何与1200地貌分布职责，不把大陆尺度与地貌片区尺度混成一个旋钮。每个候选都给同seed同范围A/B、原窗口局部和统计，用户决定后再接下游。不扫密集参数梯度、不调配方高度或海平面凑比例。

## 5. 可靠几何与P4如何合流

**修正此前说法：**“远坐标问题不挡P4出图”只适用于已经实测可用的有限局部；不是全域承诺。M0标签图不依赖反解，可以先推进。P4局部可用现行S3做受控沙箱；同范围完整高度/P4整图必须先探测实际支持域，失败就停，不放宽0.02或偷偷换后端。

N0/N1已有基线与多seed证据；N2已有前向/解析导数及有限格检查结果，但不能因日志PASS就宣称严格单射证明：

- `L`的一个上界>1只说明**该界未证明收缩性**，不等于真实L>1，也不等于收缩法必然失败。
- det J>0是局部可逆条件，不是全域单射。
- 当前Bernstein计算使用普通double（无向外舍入误差证书），且需补核clamp分段、格点边界/远坐标、独立噪声真值等覆盖。因此旧方案“N2严格已证”的措辞应在数值分支启动前复核，保留原证据，不当作可直接用于N3的最终证书。见[GeometryCellBound.java](../../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/terrain/candidate/GeometryCellBound.java)、[GeometryTransformCheck.java](../../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/terrain/candidate/GeometryTransformCheck.java)。本轮不修这些源码。

需要数值升级时继续原N3～N5：受保护反解/唯一性或分支证据→同后端贯穿赢家/浅海/外边→旧S3保护与候选A/B。无充分证据不合流，不关闭warp、不按坐标阈值拼两套算法。

P4沿用户确认后的600体系，继续原方案特殊加工契约：海陆真实邻接与正权重触发、无独立岸型票槽、同一节点链、有限作用域、窄地峡/多向无顺序覆写；岸型连续参数与原剖面自身问题分臂验证。大陆组织/比例调整在P4前完成，避免岸线在底图仍变化时反复重做。

## 6. 停止点与最终验证

近期顺序明确：**M0原状分布总览 → 用户看图定方向 → 单变量分布候选（若需要）→ P4局部 → 合流整图**。数值几何按实际阻塞插入，不要求为首张分布图先完成全部N阶段，也不能绕过失败进入全域验收。

最终完整图必须走同一真实S3/候选求值链，分别统计标签海陆、最终水面、海岸加工变化；保留海底图、同色带大图、局部、六全幅与三局部真实剖面，完成窗口/跨tile/顺序/缓存/多seed门禁。生产迁移、预览schema与实机另行授权。

## 7. M0实施与交付（2026-11-01，已完成后停止）

### 7.1 提交与范围

先完成本地提交 `c165e92`（530路径），未push。提交保留此前全部正式沙箱成果和证据；6个临时Java探针及2个pyc未入库、未删除。**M0是在该提交之后新增，尚未另行提交。**

M0仅新增诊断侧 `M0DistributionSampler/Gallery/Check.java` 与根工具 `tools/render-m0-overview.py`，另回写本方案。703份原工程文件（含生产、配置、既有diagnostics与mdk工具）SHA未变，旧S3 raw与PNG对冻结内容一致。不改600/1200、warp参数、240带、配方/默认/生产/cache schema；不做P4、N3或全幅高度。

采样器严格复刻旧float前向和站点float相加，再用double NATURAL势；中心格势上界给完整候选枚举，赢家并列按真实站点vz/vx/cellX排序。复用原 `CoherentRegionDistribution.choose`，不另造宏分布、不调比例。无反warp、无world折线、无高度求值，S2前海洋内部配方不冒称最终S3类型。

### 7.2 结果与图

证据目录：[world-distribution-m0-20261101](../../handoff/attachments/world-distribution-m0-20261101/metrics.json)。

- [原状双色总览](../../handoff/attachments/world-distribution-m0-20261101/overview.png)：样点跨度56000×56000块，1401²、step40、同seed同中心；橙框为原S3窗口。
- [4邻接连片图](../../handoff/attachments/world-distribution-m0-20261101/components.png)：主要陆块/海盆分色，触边逐项标明。
- [原S3局部标签](../../handoff/attachments/world-distribution-m0-20261101/local-side.png)：step5独立真实导出，不从总览放大。
- [统计](../../handoff/attachments/world-distribution-m0-20261101/metrics.json)、[组件CSV](../../handoff/attachments/world-distribution-m0-20261101/components.csv)、[原始数据压缩包](../../handoff/attachments/world-distribution-m0-20261101/raw-data.zip)、[验证记录](../../handoff/attachments/world-distribution-m0-20261101/verification.json)。

总览陆标签1485831（75.6995%）、海标签476970（24.3005%）。4/8邻接统计本图一致：陆5片（4触边），最大陆片占陆标签99.9283%；海106片（34触边、72未触边），最大海片占海标签7.1128%、全窗1.7284%。在本窗内更像一大片连通陆地中散布海域，而非多个大陆被广阔连通海洋包围；**这是标签与近似连通观察，不是最终水面或无限世界结论**。面积估计=像素×1600块²，不能与首末样点跨度平方混同；窄海峡/地桥仍有40块采样局限。

### 7.3 实际验证与复现

- Java21运行、target17；`gradlew.bat --offline --no-daemon --console=plain compileDiagnosticsJava` 成功。
- `M0DistributionCheck`：5892214断言，0失败。原窗1401²细胞键及基础类型逐像素一致；202点与真实geometry.exactWinner/warpForward一致；4seed各400点扩大8格枚举及冷实例乱序一致；原窗S2替换349705像素配方但海陆侧全部不变。
- 原窗导出SHA：type=`5680442dcec0000952d767db86898d50fa2befb0acc56ed12e903c8bbfec2937`；cells=`0358f77326e362146afa132c5525c2da302637d96e0e25486359c56c883eed08`，与S1冻结逐字节一致。
- 渲染自检通过；主代理另用行段并查集独立核对4邻接数量、面积、最大片与触边数一致，PNG面板1401²原生色块无插值。三张PNG均回读通过，输入/产物SHA核对通过。
- JVM只带 `-Xmx3g -Dfile.encoding=UTF-8`，没有任何geogenesis覆盖属性。首次未给PowerShell的-D参数加引号导致主类加载失败（检查main未运行），引号修正后正常；未放宽生成判据。

从mdk工作目录，用 `build/coherent-stream-s4/coordinate/classpath.txt` 的完整classpath（diagnostics/main优先），执行：

```powershell
$java = 'D:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot/bin/java.exe'
$cp = (Get-Content -Raw 'build/coherent-stream-s4/coordinate/classpath.txt').Trim()
& $java '-Xmx3g' '-Dfile.encoding=UTF-8' -cp $cp com.geogenesis.worldgen.terrain.candidate.M0DistributionCheck build/m0-repeat-check build/coherent-shallow-s2/frozen-c
& $java '-Xmx3g' '-Dfile.encoding=UTF-8' -cp $cp com.geogenesis.worldgen.terrain.candidate.M0DistributionGallery build/m0-repeat-data
# 回到仓库根后渲染；所有输出路径必须尚不存在
python tools/render-m0-overview.py forge-1.20.1-47.4.10-mdk/build/m0-repeat-data handoff/attachments/m0-repeat
```

原始数据含总览及local的meta/type/side/cells/c/probability和采样统计。验证记录明确区分工具退出结果转录与原始日志；本次没有游戏实机/高度剖面/全水文门禁，亦未宣称修复旧远坐标反解。

## 8. 海陆判定偏移定稿 +0.30（2026-11-01）

### 8.1 结论与范围

用户先要求“把海陆判断的位置往大陆范围收窄”，随后指定 0.30 并定稿。实现为**只平移原大陆场的判别点**：`probability = seaProbability(c − 0.30)`。原 c 噪声、seed、1200 锚点抽签、配方、600 几何、240 带、旧 S3 基准和生产默认全部不动。

诊断侧仅三处小改：`CoherentRegionDistribution` 增加显式 `(seed, landThreshold)` 构造（无参仍为 0，`choice` 内部与原算式逐行相同）、`M0DistributionSampler` 转发、`M0DistributionGallery` 接受可选第二参数并写入 meta；新增 `LandThresholdCheck`。**未改 `RegionalMorphWorld.seaProbability` 本身，未接高度链、S2 注入、P4 或生产。**

已废弃并删除前面的大洲组织实验路线（`ContinentOrganization`/`ContinentDistribution`/两个 Gallery/其 Check/`tools/render-continent-d1.py`、对应 3 个 `continent-d0-d1*` 证据目录、`land-threshold-shift-20261101` 0.20 中间档及该轮方案文件）。其中为那条路线抽出的 `recipeOnSide` 已还原为内联原算式，避免留下无人调用的悬空接口。**保留 `land-threshold-shift030-20261101` 作为定稿证据。**

### 8.2 同窗口实测（seed 9139912035078620160，1401²/step40/56000块）

| 臂 | 陆标签 | 海标签 | 海片(4邻接) | 最大海片占海 |
|---|---:|---:|---:|---:|
| 原状 0 | 75.6995% | 24.3005% | 106 | 7.1128% |
| **定稿 0.30** | **33.0670%** | **66.9330%** | **18** | **97.3407%** |

0→0.30 共 836792 像素由陆转海，反向 0；阈值增大只把原判海的位置推向更深海侧，单调性成立。最大海片占海升到 97.34%，最大陆片占陆降到 8.11%、陆组分 84 片——**说明旧场在更低陆占比下露出更多孤立陆块，不是“几块大洲”**；形态仍由旧 c 场决定，未做任何海岸或高度加工。

证据：[三档并排（原状/0.20/0.30）](../../handoff/attachments/land-threshold-shift030-20261101/abc.png)、[A/B](../../handoff/attachments/land-threshold-shift030-20261101/ab.png)、[0.30 单幅](../../handoff/attachments/land-threshold-shift030-20261101/b/overview.png)、[统计](../../handoff/attachments/land-threshold-shift030-20261101/metrics.json)、[原始数据](../../handoff/attachments/land-threshold-shift030-20261101/raw-data.zip)。ABC 图中的 0.20 仅作历史参照，其证据目录已删除。

### 8.3 验证与复现

- `compileDiagnosticsJava` 成功（清理后重新编译）。
- `LandThresholdCheck`：52488 样本 × 4 seed，0.20/0.30 双档独立偏移公式、海概率单调、旧海不变陆、同侧配方不变、零偏移与无参构造完全等价、NaN 被拒绝；全部通过。
- 零偏移主窗/局部 12 个 meta/type/side/cells/c/probability 文件与冻结 M0 逐字节一致；0.30 的 cells 与原 c 也与原状逐字节一致（几何与噪声未变）。
- 清理重构后重新生成 0.30 主窗/局部 9 个文件，与已交付压缩包内字节完全一致，证明清理没有改动产出。
- 旧 S3 冻结 raw 仍逐字节一致（after 高度 SHA `af207c94…`）；M0 冻结 side SHA `0f607e31…`。清理后全仓已无大洲路线标识符引用。
- 未接高度链/P4，未改生产默认，未 commit/push。

复现（mdk 目录，Java21，`-Xmx3g -Dfile.encoding=UTF-8`，classpath 沿既有 `coordinate/classpath.txt`）：

```powershell
& $java -cp $cp com.geogenesis.worldgen.terrain.candidate.LandThresholdCheck
& $java -cp $cp com.geogenesis.worldgen.terrain.candidate.M0DistributionGallery build/m0-threshold030 0.30
```

**0.30 定位为定稿的标签口径，但仍是基础海陆标签而非最终水面。** 此处记录当时停止点；随后本轮用户要求M1，拟在独立候选中补齐S2/S3显式接线，见§9；不更改旧入口或生产。

## 9. 当前M1方案：固定速度应力驱动构造地貌（待确认实施）

### 9.1 已确认范围与真实现状

用户要求“继续M1，做构造板块运动/地质模拟，完成后就可以进入P4”，并已在问答中确认：

- **固定seed板块位置与速度→应力驱动地貌**，不是地质年代积分、板块位置逐年演化或完整物理模拟。
- **保留+0.30基础海陆标签**，构造只改候选地貌；最终高度与实际水面变化单独出图，不反过来改海陆抽签。
- 600微细胞、1200宏地貌、240普通带、旧S3、海平面和HeightCurve不动；本轮不接生产、不改缓存schema、不提交推送。

本节把M1明确为构造阶段；早期§4的“海陆比决策M1”现已由+0.30定稿结束。当前HEAD为`d47de74`，M0与0.30均已提交；仅6个旧临时Java与2个pyc未跟踪，实施时不删除或纳入本轮。

| 现有实现 | 核实结论与处理 |
|---|---|
| [TectonicField.java](../../forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/terrain/TectonicField.java#L916-L943) | 已有2000块板块站点、固定方向/速率、5×5速度投票应力；复用实例，不新写第二套板块运动。`btype/rate/tangent`来自近邻配对，不能直接作为连续地形驱动。 |
| [TectonicDeformation.java](../../forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/terrain/TectonicDeformation.java#L142-L171) | 已有世界坐标褶皱/断层及构造带；参数和seed保持。它是有正有负的局部形变，不等于保证拉张处整体下沉的盆地模拟。 |
| [CellGenerator.java](../../forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/terrain/CellGenerator.java#L306-L388) | 生产常规/quick均已有构造与形变；本轮不往生产再叠一遍。其旧TerrainClass权重不能直接搬到18+5配方池。 |
| [NodeMorphEngine.java](../../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/terrain/candidate/NodeMorphEngine.java#L77-L130) | 当前600链尚无真实构造场；复用同一raw/amp/bias与非线性节点内核，不能改成两份成品高度lerp。 |
| [OceanRecipeCatalog.java](../../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/terrain/candidate/OceanRecipeCatalog.java#L426-L453) | 洋中脊已有独立门控，但stress/dist是噪声代理；候选只替换驱动来源，保留raw细节、-0.36/-0.20端点、130宽度等既有参数。 |
| [ShallowRegionMatcher.java](../../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/terrain/candidate/ShallowRegionMatcher.java#L21-L60) | 仍默认零偏移；基础recipe与patch边两侧判陆必须注入同一个0.30分布对象，不能只改一处。 |

不扩展BASIN配方、不把海底峡谷冒充俯冲海沟、不新增火山/岩石/物品，不将构造板块与宏海陆标签强行对应。不使用旧CombineCandidate的整份高度混合，也不恢复`TectonicField.elevationOffset`直接抬整片大陆。

### 9.2 接线与唯一变量

```text
显式seed + landThreshold=0.30
  → 同一基础分布 → S2浅海匹配 → S3身份/外边/240权重（A、B相同）
  → A：现有统一节点求值 + 旧陆覆盖
  → B：同一权重 → 连续构造调制 → 同一节点求值 + 同一旧陆覆盖
                                      + 一次陆地参与量门控的既有形变
  → 同一HeightCurve → e/Y/水面/差图
```

建议最小诊断侧构造上下文（暂名`TectonicMorphContext`），每world/seed独立持有真实TectonicField与TectonicDeformation，不运行中setSeed、不用全局最近实例、不与其他seed共享可变缓存。生产类只读复用；若其数值缺陷阻塞，按9.3停下，不顺手改生产。

**陆地汇聚：**在求值前将归一的陆配方权重`q`连续推向既有MOUNTAIN条目，`q'=(1-g)q+g·onehotMountain`。推荐`g=discreteStrength(-stress)×boundaryStrength(sample)`，沿用已有0.15应力饱和与边界包络参数，先验证二者非负、有界与连续性。总陆参与量和每个海权重不变，不在每个600格重新抽类型；诊断分别导出基础身份与有效形态权重，不能把两者混称同一标签。无构造/零门控时直接走旧路径保证逐位相同，不能用乘零后的重算冒充原字节。

**褶皱与断层：**复用`deform.offset(sample,x,z)`，在统一基础e及旧覆盖求值之后只加一次`landWeight×offset`，再交HeightCurve。它是显式地质形变项，不是第二份陆海成品高度融合；纯海严格不加，混合随陆权重归零。单列该项对水面、山顶和覆盖台地的影响，不承诺零水面变化。既有`smoothPos`在负半带可能为负，保留原算式但不得拿它当非负概率/权重；若因此违背期望语义，先报证据，不偷偷改系数。

**离散海洋：**显式候选OceanRecipeCatalog接真实`stress/dist`，只在原MID_OCEAN_RIDGE配方的amp中替换代理门控；其参与权重为0时不影响其他海型。保留原票槽、不把所有离散海域强制换脊，因此不保证每条离散板界均有洋中脊。其余四海型原式不动，负e不被陆地夹零逻辑截掉。

**走滑/剪切：**现有连续标量stress不足以独立表示剪切强度；离散TRANSFORM标签只可画诊断，不直接开关高度、不投影不连续tangent作噪声坐标。本次不额外造垂向山带或横向搬移整片地形；真正连续剪切场与侧向错断需另列方案，不把未实现项写成“全地质模拟完成”。

先做同参数单臂，不扫构造强度梯度。陆汇聚权重、形变、真实海脊三项分别可导出消融臂定位效果，合成臂只在各项门禁通过后出图。旧18+5目录端点在**原入口**保持；B臂有效形态权重及真实海脊门控是明确的新行为，不要求它与旧端点成品逐位相同。

### 9.3 分阶段实施与停止点

| 阶段 | 实施内容 | 验收/停止条件 |
|---|---|---|
| M1a：0.30完整高度A臂 | 冻结源码/SHA/0.30标签；为matcher/world增加显式分布入口，基础recipe与两侧判陆同源；旧无参仍0；统一evaluate不复制 | 零偏移重放旧S3 raw与50109剖面逐位一致；0.30基础side/cell与定稿图一致，S2只改海内配方。先探测原窗和matcher所有依赖patch，失败即停，不改窗口挑图掩盖。 |
| M1b：真实驱动预检 | 原参数/seed复用场；核验符号、幅度、非空分布与独立扩大候选；定位板块种子点、投票枚举换窗、dist模糊过渡 | **5×5有限投票切窗并非严格连续；单位向量在种子点邻域可能有方向极限问题。** 对这些位置做1/0.1/0.01块及更小epsilon扫描，判断有限坡度还是跳变，不能沿用“高斯所以全域连续”的注释。驱动有阶跃/不可接受伪影则停在此处，报反例并另提诊断侧修复方案，不接B或改生产/调参掩盖。 |
| M1c：构造分量接线 | 实现9.2三项候选；复用原引擎、旧覆盖、配方细节、应力与形变参数；每项有独立贡献输出 | 权重合法/归一/总陆海量守恒；零门控等旧链；纯海无陆形变；混合含极小海权重、三域与窄地峡连续；重复叠加负控必须检出。无新BASIN、无重抽side。 |
| M1d：归因出图与交付 | 同seed同窗A/B，原窗1401²/step5、原六全幅三局部逐块剖面；补真实汇聚/离散局部；量化成本 | 高度、海底、有效权重、stress/包络、形变、差图/高通图同时交付；数值绿不替代用户看图。无法在原窗采到某语义则明确零样本并补固定局部，不算PASS。 |

M1a与M1b可先分别验证，但必须都通过才接M1c。M1不包含N3～N5修复；若0.30浅海新邻域触发旧float反warp失败，保留栈与坐标，停止完整高度接线，另回数值分支确认，而不是拿M0标签图替代高度验收。

### 9.4 验证矩阵、交付及P4进入条件

- **旧资产保护：**旧S3、0.30标签raw/PNG、原生产/配置/default/600/240源码SHA冻结；检查旧guard和原入口仍有效。输出新目录，不覆盖认可图。
- **驱动因果：**人工正/负/零应力样本验证门控符号与非负范围；真实场独立哈希/扩大站点枚举只作交叉证据。停用真实驱动、颠倒符号、半接线0.30、重复形变分别作为负控，必须由对应断言检出。
- **真实完整高度：**正典及0/-419/12345近点，多seed冷实例/交错隔离、正反乱序、跨tile/换窗、真实LRU淘汰重建；有非空纯陆/纯海/混合/覆盖样本。构造场远点检查不等于完整远高度通过。
- **连续性与伪影：**新候选逐分量梯度P50/P99.9、长尾比、高通/阴影图与缩步剖面联合验收；dist仅包络、不作噪声坐标；无噪声量化、最近配对阶跃或reach硬切。既有TectonicContinuityProbe/WaveProbe等只作相关回归，**不会覆盖新权重链且不能排除种子点奇点**，新增门禁必须覆盖新接线，FAIL非零。
- **工程回归：**按工程`compileDiagnosticsJava`及相关NodeMorphEngineCheck、RecipeMorphCatalogCheck、FullMorphLandCheck、CoherentMorphCheck、RegionalMorphWorldCheck、LandThresholdCheck和新构造check验证；新增入口再跑对应S2/S3/streaming检查。不把未运行的旧历史PASS当本轮结果。
- **图与成本：**Java统一入口真实输出e/Y、基础side、最终Y<63水面、构造前后有效形态权重及三项贡献；Python仅渲染。同窗同色带A/B、海底、局部与50109剖面，报告新增/消失水面像素、每分量耗时及每tile/采样成本，不冒称FPS。56000大范围仅标签/构造驱动总览，未经支持域检验不能声称全幅真实高度。

**M1完成≠立即证明全域可用。** 进入P4须同时满足：0.30完整A臂接线通过、构造分量及组合门禁通过、用户认可真实高度A/B效果、选定P4局部及全部依赖patch几何可构建。之后按原P4合同推进80～116特殊海岸带，保持240普通带；如几何阻塞，先停回数值分支。海岸导数/端点已知问题仍独立验收，不在M1顺手修复。

**本段为制定方案时的停止点。** 随后用户已批准先做M1a～M1b；实际结果见§10。生产迁移、缓存版本升级、游戏实机、N3～N5、连续剪切/年代演化仍不包含在此次M1范围中。

## 10. M1a～M1b实施证据与阻断（2026-11-01）

### 10.1 范围与保护

仅为`CoherentMorphWorld`增加显式`context + distribution`重载和同seed守卫，为`ShallowRegionMatcher`增加显式分布重载。matcher内基础选型与patch两侧判陆共用同一个注入对象；旧无参/seed入口仍零偏移。未修改统一evaluate、NodeMorphEngine、陆覆盖、几何数值后端、配方或生产。

新增诊断：`M1ThresholdHeightCheck`、`M1ThresholdHeightGallery`、`M1TectonicDriverCheck`，以及只读渲染工具`tools/render-m1-threshold-height.py`。实施前冻结687文件，其中188份生产文件；SHA复核仅上述两个既有诊断文件变化，其他冻结文件、生产、配置、旧S3与0.30证据全部不变。600/1200/240、海平面、HeightCurve、原噪声与默认不动；未提交/推送，未启动游戏。

证据目录：[m1a-m1b-20261101](../../handoff/attachments/m1a-m1b-20261101/verification.json)。命令均Java21、`-Xmx3g -Dfile.encoding=UTF-8`，无geogenesis属性覆盖，classpath见证据；完整原始stdout/stderr、退出码及失败CSV保留。

### 10.2 M1a：已完成真实高度导出，非构造效果

- 工程`compileDiagnosticsJava`成功；旧无参CoherentMorphGallery重出12个非计时文件（含50109逐块剖面）与冻结S3逐字节一致。旧after高度SHA仍`af207c94935c86b55ddd00e7155a4b375bfa2f1254a227c10811960cefafdc29`。
- 0.30原窗1962801个基础cell/type/side对冻结定稿raw一致；同对象接线与错seed拒绝通过，旧零偏移半接线负控在49个站点样本中有23个差异可检出。原窗64 tile及matcher依赖patch构建通过，无容差或窗口改动。
- 4seed（正典/0/-419/12345）各169真实完整高度样本：首轮5923709断言通过，扩展最终5923734断言PASS/退出0；纯陆148、纯海428、混合100、覆盖76（聚合计数）。零偏移对旧入口逐位相同、反序冷实例一致；0.30强制跨tile（7个混合点）、换窗、真实tile淘汰重建及4seed交错隔离全部通过。
- 0.30完整统一入口导出1401²/step5同原窗高度/e/主导配方/活跃数/海权重/赢家/side/cells及50109剖面；side/cells与定稿local全幅逐字节相同，S2只在海内换配方（713125像素），没有重塑基础海陆。
- 新高度SHA=`47936fad83882ad0e38bf22e1638bca1e5635b1013481edb04e49b925ab4763c`。高度全部有限，Y范围16.57184～272.97744。原窗Y<63从439085/1962801（22.3703%）到1159261/1962801（59.0616%），陆转水720176、水转陆0。**这是7000块原窗的真实高度阈值，不是56000块标签图的66.9330%，也不是已放置水方块的实机统计。**
- [同窗A/B](../../handoff/attachments/m1a-m1b-20261101/render/pair-height-full.png)、[海底](../../handoff/attachments/m1a-m1b-20261101/render/pair-seafloor-full.png)、[六全幅剖面](../../handoff/attachments/m1a-m1b-20261101/render/profiles-full.png)、[三局部剖面](../../handoff/attachments/m1a-m1b-20261101/render/profiles-local.png)、[统计](../../handoff/attachments/m1a-m1b-20261101/render/metrics.json)、[真实raw](../../handoff/attachments/m1a-m1b-20261101/height030-raw.zip)。A=旧S3零偏移arm-after，B=0.30无构造；不是构造开/关对照。主代理已看图；形态变化与海陆改判一致，保留地貌细节，不能由此宣布P4海岸或实机通过。
- 渲染18张原生图，PNG回读/输入SHA/50109剖面坐标与固定纵轴全部通过，18项小样本负控拒绝；主代理独立核对水面数、side/cells及压缩包逐文件字节。采样成本保留原始计时，不把并发离线数值当FPS或严格性能A/B。
- 相关S3检查10672断言PASS，旧streaming全部阶段PASS，旧S2反序165站点与32细几何复核PASS，LandThresholdCheck52488样本PASS。未改引擎/配方/覆盖，不重跑无关全水文；旧远坐标反warp缺陷未修，不宣称全世界可构建。

### 10.3 M1b：FAIL，停止接构造地貌

`M1TectonicDriverCheck`退出1：59720断言、897条失败、11352个public路径采样、逆解定位失败0。4seed原点附近固定集合；独立BigInteger模2^64哈希/站点/速度、5×5公式、warp和距离参考与实际private/public结果对拍无公式失败。扩大9×9/13×13只作交叉观察，不擅自替换生产。

**897不是897个独立bug**，含同一事件的私有/public/解析极限与下游重复测量；阻断归纳为以下机制：

1. **5×5窗口切换仍有非零投票→应力C0阶跃。** seed=-419，构造q=(-2000,-1700)，实际world中心约(-2010.7180242616168,-1738.660769782364)。固定窗口两侧应力极限为-0.09156530444115953/-0.09163110140488198，差6.57969637e-5；h缩到2^-20仍差6.57973615e-5。真实130包络门控差5.31775480e-4，既有offset差6.73722022e-7 e。量小不等于数学连续，也不足以单独宣称实机可见。
2. **种子附近单位向量方向依赖与零盘边界。** 正典seed、cell(0,0)，q=(499.38056468963623,934.4878196716309)，world中心约(408.1912317710077,888.4415715833302)。盘外沿速度两侧h=2^-20的stress=-1/0.6855754305220745，offset=-0.007249263657684839/0.004057636163436434（差0.01130689982 e）。`d<1e-9`使中心附近小盘恒0，**中心不是不连续点**；真实C0阶跃在该零盘边界，cutoff.csv独立界限与0.5r/2r实现采样证实。不能把盘外缩步平台误称中心真正极限。
3. **有限环均值保留距离C1折角。** seed12345，bisector-0-1，world约(975.0845618990552,1821.8342492225638)。左右斜率差在缩步中约0.39986不趋零；blur在rawDist=0时仍保留中心项1/5，预测跳量`(2/5)×rawDist单侧斜率`≈0.39986027，与实测相符。值C0连续，但不能作C1保证。24个bisector此项FAIL，24个reach420的C1通过；不是420渐隐接口出错。

`smoothPos(-0.09,0.18)=-0.0140625`也如实记录，不能当非负概率。未修改其算法。

完整反例：[driver-verification.json](../../handoff/attachments/m1a-m1b-20261101/driver-verification.json)、[简要坐标](../../handoff/attachments/m1a-m1b-20261101/counterexamples.json)、[逐步world数据](../../handoff/attachments/m1a-m1b-20261101/driver-check/world.csv)、[缩步](../../handoff/attachments/m1a-m1b-20261101/driver-check/shrink.csv)、[独立单侧极限](../../handoff/attachments/m1a-m1b-20261101/driver-check/limits.csv)、[零盘边界](../../handoff/attachments/m1a-m1b-20261101/driver-check/cutoff.csv)。h=2^-k（k0..20）是warp后构造坐标步长，不是world欧氏步长；没有精确0.01/0.001实测。普通尾阶收缩判据不是普遍连续性定理，本结论以明确单侧极限、解析结构与中尺度数据共同支撑，不机械把所有微小传播FAIL都当独立缺陷。

**停止结果：M1a无构造候选保留；M1b未通过，未修驱动、未接造山/形变/真实洋中脊、未进入M1c或P4。** 后续需先确认只在诊断侧修复构造驱动的方案，保留原参数与生产；本轮不擅自修复或转入其他阶段。

## 11. 诊断侧构造驱动修复方案（待批准实施）

### 11.1 唯一目标与保护合同

本轮用户要求：**先出只修诊断侧构造驱动的方案，解决§10.3三类问题；参数、600/240、旧S3与生产不动，验证通过后再接构造地貌。** 本次只写方案，未编译、未执行新探针、未改源码。

推荐路线：**固定窗口连续投票＋150内近种子多项式修补＋固定world格点正三次B-spline距离准插值**。新增显式候选，不替换旧驱动，不更改旧M1b检查使其转绿。

冻结：seed/哈希及溢出、站点与速度、板块格距2000/抖动0.7、原warp130/400、投票σ1000/增益2、应力clamp[-1,1]、门控0.15/包络110与130、现有形变参数和`smoothPos`；600/1200/240、+0.30、18+5配方、HeightCurve、旧S3与M1a无构造资产全部不变。生产源码/配置/cache schema不动，不提交推送。

**必须区分参数与算法：**不连续函数不可能在处处逐位相同的同时被修成连续函数。保留的是上述参数数值及旧入口；候选驱动数值会变化。下述150的新用途、B-spline表示格步及移除旧候选环采样/420回退，均是本方案明确提出的算法选择，不能称为“无损原算法优化”。如果“参数不动”还要求候选必须保留旧四环点和420外raw路径，则与距离C1目标冲突，实施前停止确认，不隐瞒替换。

### 11.2 三类修复的确定义

#### A. 投票换窗：混合固定窗口的分子/分母

在**原warp后的构造坐标**q，令L=2000，b=floor(q/L)、局部u/v∈[0,1]，H(t)=3t²−2t³。以当前网格四角索引b、b+(1,0)、b+(0,1)、b+(1,1)为固定投票窗口锚点；每窗口仍是半径2的5×5同seed站点。

- 四角权重φ为H的双轴张量积，非负且Σφ=1；接口上退出窗口的φ及一阶导均为0。
- 各固定窗口独立形成未限幅分子Nₐ=ΣwᵢPᵢ、分母Dₐ=Σwᵢ，wᵢ=exp(−dᵢ²/(2σ²))。
- **先混合N=ΣφₐNₐ、D=ΣφₐDₐ，再算U=2N/D、S=clamp(U,−1,1)**，不混合已经clamp的四份stress。
- 四窗口并集最多6×6=36站点，可按全局cell键去重并固定累加顺序；连续性来自权重接口归零，不是“枚举更大所以跳量更小”。

证明义务：四格角/正负格线两侧公共窗口相同，新增贡献值与导数均归零；分母严格正。当前格站点始终位于四窗口内、有效系数1，其距q≤0.85√2L，故D≥exp(−2.89)>0，无需`den<=阈值→0`回退。非有限输入/越界显式FAIL。修补投影后U为C1；**保留硬clamp，S只承诺C0、严格非饱和区C1，不承诺饱和接口全域C1**，也不另改tanh补偿强度。

#### B. 种子方向：只修近种子，保留其他投票

复用现有距离平滑半径R=150作为候选近种子修补半径；不新增可调旋钮。r=q−sᵢ，d=|r|，定义单站点投影：

```text
Pᵢ(r) = (vᵢ·r)/R × (3/2 − d²/(2R²))     d<R
       = (vᵢ·r)/d                        d≥R
```

内部式直接使用r²，不用归一方向，也不设`d<1e-9`整函数早退：r=0时仅该站点分子贡献为0，其他站点继续参与，**总stress无需为0**。

解析合同：原点梯度3vᵢ/(2R)；d=R内外值/梯度相等，|Pᵢ|≤|vᵢ|，梯度可取上界3|vᵢ|/(2R)。因此零盘边界阶跃与方向奇点消除；d≥R的单站点投影仍原式。POU已改变总投票，不能据此声称远端总stress逐位不变。若幅度或构造活动覆盖明显改变，单列差图，不提高增益补回。

#### C. 距离折角：固定系数的C2距离代理

先保留原raw语义`D₀(p)=(d₂(W(p))−d₁(W(p)))/2`，在world坐标p先warp一次；d₁/d₂从全局确定站点取最近/次近。它本来就是距离代理，不冒称精确world欧氏板界距离。

候选取固定全局格步`h=R/(2√2)`，R仍150。格点系数cᵢⱼ=D₀(ih,jh)，不预滤波、不反卷积；以中心化cardinal cubic B-spline B₃形成：

```text
B₃(t) = 2/3 − t² + |t|³/2      |t|<1
      = (2−|t|)³/6             1≤|t|<2
      = 0                      |t|≥2
D(p)  = Σ B₃(x/h−i) B₃(z/h−j) cᵢⱼ
```

- 每次最多16个非零固定系数，权重非负且和1，系数距查询点最大欧氏支持150；D≥0、凸组合有界。
- 固定系数与B₃的C2性质直接给出实现自身C2；格点换索引时退出基函数值/一阶/二阶导均为0。**这不是对查询点移动的16点平均**，不能重新计算随p移动的系数。
- 不插值穿过全部raw格点，不称为真实连续卷积或旧blur等价算法；平滑可抬高板界附近距离，需报告110/130包络的定位/幅度变化、网格方向性与成本。
- **不把raw混回，不在420外切回raw，不保留中心＋四环点平均。** 原生产150/420/4及旧行为均不变；候选替换的是距离算法，420不再作为候选回退接口。
- h由旧R确定，但同时决定表示和平滑尺度，不能把h/2说成同一目标的积分精度收敛；本轮不扫h参数。验证用同一h的独立B₃公式/解析导数与相同系数，而非靠更密格换出好看的图。

最近/次近必须有完整性界：站点位于各格[0.15L,0.85L]，当前格站点距离≤1.203L，按查询点半格选择一个横邻格可给次近上界约1.596L；5×5外站点至少2.15L，因此可先证明固定5×5足够，再用独立扩大枚举交叉核验。界与负坐标floor必须测试，**枚举完备只保证系数正确，不证明旧raw可导**。

### 11.3 改动面与隔离

仅新增诊断侧文件，建议名称：`DiagnosticTectonicDriver`（固定seed的数值候选）、`DiagnosticTectonicDriverCheck`、`DiagnosticTectonicDriverGallery`；必要的只读渲染器可新增于tools。名称不是要求拆成大量抽象。每函数≤80行、每实例seed隔离，无全图统计反馈、无运行时开关默认切换。

- 不编辑[TectonicField.java](../../forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/terrain/TectonicField.java)、[TectonicDeformation.java](../../forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/terrain/TectonicDeformation.java)或旧[M1TectonicDriverCheck.java](../../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/terrain/candidate/M1TectonicDriverCheck.java)。旧实现及其FAIL继续作为负控。
- 候选仅保留所需的seed/hash/站点/速度/warp最小数值后端，不复制整份生产地质系统，不重写褶皱/断层。先与原私有字段/独立参考逐项对拍；生产私有反射仅用于检查，不作候选热路径依赖。
- 输出独立候选快照，含原始U、限幅S、D、解析梯度、投票贡献/系数键及版本。不要把候选S/D替换进Sample后称为“原tf.sample真实值”。
- 传播检查可以显式创建并标明“候选适配Sample”供原`deform.offset`和原110/130包络消费，旧btype/rate/tangent只作元数据，**不驱动候选地形**。S/D同源，不能只替换其中一项后宣称修复完成。
- 距离系数缓存按固定world整数格点、seed/版本隔离，缓存容量只影响成本，不影响数值。先无缓存定义对拍；如加入LRU，须真实淘汰与冷重建验证。旧S3与M1a不读候选，不增新默认。

### 11.4 实施阶段与验收（均待授权）

| 阶段 | 内容 | 必须满足才继续 |
|---|---|---|
| R0 冻结 | 旧生产/参数/600/240源码，旧S3与M1a raw/PNG，M1b原始反例和退出码 | 新目录；旧S3高度SHA af207c94…、M1a高度47936fad…均可验证；不改旧FAIL期望 |
| R1 算子 | POU分子/分母、近种子投影、独立raw系数/B₃和解析梯度；暂不接原形变 | 原式seed/warp等价；POU接口/原点/R接口/B₃网格与支持边界解析合同通过；非空多seed；FAIL非零 |
| R2 反例回放 | 全部旧case集合＋三组重点反例，分别测构造q与直接world路径；补真实1/0.1/0.01/0.001及原2^-k | 新候选通过适用的C0/C1合同；旧驱动仍能触发原问题；先定位/舍入误差核查，不靠增容差或下游掩码放行 |
| R3 消费/确定性 | 仅在驱动检查中接原门控和原deform；固定近窗及远点、负轴/格角、冷实例/乱序/交错seed/缓存重建 | 原始驱动先合格再看传播；权重/门控合法；零/正/负应力符号可检；硬clamp和smoothPos限制单列 |
| R4 驱动验收交付 | 固定同窗旧/新stress、D、110/130门控、原形变传播A/B，梯度/高通、反例测线和成本 | 数学结构＋独立实现＋数值误差证据合格，人工看图无新增网格条纹/尖峰；未解项阻断，不以统计改善代替看图 |

**验证方式必须修正：**旧检查“.75尾阶比例”仅保留为观察/负控，不是普遍连续性定理，不能原样套到新候选制造假FAIL。新检查将解析接口恒等式、独立导数、单侧极限、缩步趋势及舍入/定位误差分别记账；必要时用更高精度仅核对固定反例，不修改世界生成参数或放宽旧阈值。

详细门禁：

1. 4seed=正典/0/−419/12345；逐种子多方向接近每个板块种子，含旧零盘内/外、d=R两侧；检查自身投票为0与总投票非零的区别。逆解只定位旧q反例，并核对实际前向残差；直接world求值不依赖新反warp，不触碰N3～N5。
2. 正负POU格线/格角、固定窗口共同项，独立36站点有效权重表达式对拍；构造错窗口权重/恢复旧floor切窗的负控应被检出。分母下界与权重和检验不可空样本通过。
3. B₃独立多项式/权重和/非负/导数/支持端点；原板界、次近切换线、三域顶点、固定格线/格角及旧420附近皆检查。与扩大枚举生成的相同cᵢⱼ对拍，不用候选自己当真值。恢复中心四环点后原0.39986斜率差必须再被抓住。
4. stress只要求非饱和C1、限幅接口C0；**硬clamp±1导数折角不伪称新bug已修**。保留`smoothPos(-0.09)=-0.0140625`作为签名回归，不当概率；原消费者若暴露新语义冲突，报告并停，不顺手修改它。
5. 展示候选vs旧驱动值域、符号翻转、门控变化、平滑距离基线抬升、梯度P50/P99.9和长尾、格网方向统计；不预先设“差得小就算修好”，不调gain/σ/海平面补偿。有限PASS不能升级为全域地质真实性或全域warp单射证明。
6. 编译按`compileDiagnosticsJava`，新算子/回放check必须exit0；旧M1b原驱动预期exit1单列为负控，不混成新候选失败。原M1a检查重跑，并重新验证旧S3/M1a确定性文件SHA、0.30基础side/cells、188份生产保护。完整旧图仅在依赖确有变化时重出，原资产绝不覆盖。
7. 性能记录站点数、固定系数数、冷建/热查/P95、缓存容量与淘汰成本：36/25站点和16系数只是操作数量，不预先承诺速度；不默认跑56000完整高度，不把离线耗时称实机FPS。

### 11.5 接构造地貌的前置条件与停止点

只有R1～R4全部通过、旧资产保护通过、驱动值域/包络变化已交付并经用户验收，才能回到§9的M1c：在同一0.30/600/S3候选中显式注入同一修复驱动，分别验证汇聚权重、陆地形变、真实海脊，再做组合A/B。**修驱动阶段不往CoherentMorphWorld、NodeMorphEngine或OceanRecipeCatalog接线，不输出冒充构造地貌的合成高度图。**

若B-spline引入明显格纹/包络偏移，或连续性证书、原点/接口、成本不满足合同，停下交原始反例；不能偷偷改h/R/σ、增加噪声、关warp或退回420外raw。若需改为真正连续卷积等另一算子，另提取舍，不在实施中无声换路线。

R4通过后也不自动进入P4：须先完成M1c/M1d真实统一高度与用户效果验收；旧float远坐标反warp问题仍独立存在。生产迁移、预览cache、游戏实机、N3～N5、连续剪切/年代演化均不在本修复方案内。

## 12. R0～R2实施与回放（2026-11-01，完成后停止）

### 12.1 授权、实现与保护

用户仅批准先做R0～R2：修诊断侧驱动、回放三类反例，参数/600/240/旧S3/0.30/生产不动，通过后再做传播验证。**R3/R4、M1c及P4本轮未执行。** §11的“待批准”是规划快照，当前R0～R2事实以本节为准。

新增两个诊断源码：[DiagnosticTectonicDriver.java](../../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/terrain/candidate/DiagnosticTectonicDriver.java)、[DiagnosticTectonicDriverCheck.java](../../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/terrain/candidate/DiagnosticTectonicDriverCheck.java)。原有Java/生产/配置一律未编辑。

驱动实现：36站点去重POU分子/分母，R150近种子多项式，原warp及解析Jacobian，固定world格点B₃距离及解析梯度；无缓存、无deform/gate消费者、无S3/OceanRecipeCatalog接线。输出梯度明确为**raw U**导数，保留硬clamp，只承诺S的C0及非饱和C1，不宣称全域C1。距离是新的平滑代理，不冒称原blur逐位等价。

R0冻结731文件，含188份生产和旧M1b反例CSV、S3/M1a raw/PNG、0.30定稿证据。结束SHA核实731份全部不变；旧S3高度`af207c94…`、M1a高度`47936fad…`不变。未改600/1200/240、原参数、0.30或默认；未提交推送，未运行游戏。

### 12.2 验证结果及过程失败归因

- Java21、target17，工程`compileDiagnosticsJava`最终exit0；运行只带`-Xmx3g -Dfile.encoding=UTF-8`，无地形JVM覆盖属性。
- **R1 operators最终2993项PASS/exit0。** 独立BigInteger hash与旧私有实现对拍，站点/速度逐位相同；warp值对旧实现逐位相同。四固定5×5窗口参考对拍36站点实现；B₃使用另一截断幂表达式，raw系数使用扩大枚举及完整性下界。检查原点/150接口、正负POU格线/格角、B₃格点和支持端点、非负/权重和及导数。
- **R2 replay最终87684项PASS/exit0。** 264个冻结case全部回放，另补4seed各一个真实三域顶点，共268 case、40200条q-U/world-U/world-D缩步记录；h=1/0.1/0.01/0.001及2^-k(k0..20)。每步候选q值及world距离/梯度对独立参考核验，原中心world前向定位也检查；未用“.75比例”当证明。
- h=.001时最大局部Taylor余量：q-U `6.9724e-12`、world-U `2.0084e-11`、world-D `1.9524e-8`。这是有限样本与既定误差判据的检查，不是全域误差证书。
- 旧负控实测仍检出：窗口应力差`6.5797361543e-5`；种子盘外方向差`1.6855754305`；旧零盘边界内0/外−1及0.68557543；旧距离斜率差`0.3998574901`。负控由原真实函数与冻结数据联合验证，旧FAIL探针源码和期望未改。
- 首次编译出现`Math.scalb(1,-k)`选float重载不能加入List<Double>，仅修检查器常量为`1.0`后成功。首轮1802项算子检查12项warp差分失败：均位于原Catmull-Rom格线，跨二阶折点的固定步长高阶差分有截断误差；改用**独立Catmull-Rom系数解析导数**核验后同样/更严误差限通过，驱动源码未因该失败变化，未放宽容差。失败日志原样保留。
- 原M1a完整检查重新执行：**5923734项PASS**，四seed纯陆/纯海/混合/覆盖、0.30基础标签、跨tile/换窗/淘汰/交错隔离均通过；旧S3/M1a依赖未变化，因此未重复生成整幅高度，直接保护确定性文件SHA。

证据：[汇总](../../handoff/attachments/tectonic-driver-r0-r2-20261101/verification.json)、[算子](../../handoff/attachments/tectonic-driver-r0-r2-20261101/operators-final/check.txt)、[反例回放](../../handoff/attachments/tectonic-driver-r0-r2-20261101/replay-final/check.txt)、[逐步数据](../../handoff/attachments/tectonic-driver-r0-r2-20261101/replay-final/samples.csv)、[重点反例与误差](../../handoff/attachments/tectonic-driver-r0-r2-20261101/replay-summary.json)。原命令/日志/退出码、初始失败、源码与class SHA及保护清单同时归档。

### 12.3 边界与下一步

R0～R2的**算子与固定反例合同已通过**，不等于整个构造驱动已获最终效果验收。原硬clamp的饱和接口折角仍按方案保留；`smoothPos`与真实门控/形变没有修改，也没有对新驱动运行传播验证。无全域warp单射或无限世界完整高度声明。

B₃会改变距离包络（例如固定seed12345反例的旧blur中心约91.7866，新代理约26.6192），不是为了对齐旧值再调150/h/σ。本段记录R2当时停止点；随后用户批准R3～R4，结果见§13，仍不直接接构造地貌。

## 13. R3传播与R4驱动图/成本（2026-11-01，交付待验收）

### 13.1 范围与实测合同

只新增诊断传播适配`DiagnosticTectonicPropagation`、检查器、Gallery和只读`tools/render-tectonic-propagation.py`，未编辑原驱动或生产消费者。A=原`TectonicField.sample`；B=原Sample元数据保留，仅用修复驱动的S/D形成**候选适配Sample**。两臂均调原110包络、原0.15平滑门控、原130海脊门控和原`TectonicDeformation.offset`。**不是最终地形、不是构造地貌已接线，没有HeightCurve/Y/海陆改判。**

开始冻结728文件（含188生产、R0～R2候选、旧S3/M1a与0.30证据），结束SHA全部相同。600/1200/240、0.30、原参数、默认、生产/cache schema不动；旧未提交改动和临时探针保留，未提交推送、未启动游戏。

### 13.2 R3验证

- `compileDiagnosticsJava`成功；R2回归87684项通过；新传播检查**655047项PASS/exit0**。
- 4seed各207固定点（13²近窗＋负轴/格角/ULP两侧＋1e5/1e6/±29998000有限远点），六通道冷实例/反序/固定乱序/同实例重访/seed交错逐位一致。驱动无缓存，淘汰/重建N/A，不伪造缓存测试。
- 264个旧中心全覆盖，直接world双轴及保存的真实world方向做1/.1/.01/.001与2^-k缩步；原门控独立smoothstep/高斯公式和Taylor误差预算核验，形变反射原私有分量再独立组合校验；局部Lipschitz界检查有限采样C0，不拿`.75`比例当证明。
- 聚合覆盖：S≥.15为353、S≤−.15为309、中性带166，汇聚gate非零428、离散gate非零400，belt内非零offset382、belt外446；精确零应力另用12个合成样本。`smoothPos`负值签名保留，不当概率。
- 元数据扰动（btype/rate/tangent/along）不影响消费者；形变仅依赖S/world，不依赖D；半接线只替S/只替D、旧新形变叠加、重复形变、反符号负控均有非空下游差异被检出。硬clamp饱和界只承诺C0，导数观察不升级为全域C1。

### 13.3 R4同窗A/B、剖面与观察

固定seed9139912035078620160，中心(-140,137)，原点(-3642.5,-3365.5)，**701²/step10、跨度7000**。与旧S3是同一世界窗口，**不是原1401²的相同分辨率**。六通道：stress、distance、envelope110、convergent110、divergent130、offset；每臂491401真实Java样本。六条全幅逐块测线42006点＋三条反例focus各601点，共43809点；focus-window seed=-419、focus-bisector seed12345明确另标，不冒称主seed。

证据入口：[验证汇总](../../handoff/attachments/tectonic-driver-r3-r4-20261101/verification.json)、[raw压缩包](../../handoff/attachments/tectonic-driver-r3-r4-20261101/raw-data.zip)、[统计](../../handoff/attachments/tectonic-driver-r3-r4-20261101/render/metrics.json)。重点图：[应力A/B](../../handoff/attachments/tectonic-driver-r3-r4-20261101/render/stress-pair.png)、[距离梯度](../../handoff/attachments/tectonic-driver-r3-r4-20261101/render/distance-gradient.png)、[离散130门控](../../handoff/attachments/tectonic-driver-r3-r4-20261101/render/divergent130-pair.png)、[形变A/B](../../handoff/attachments/tectonic-driver-r3-r4-20261101/render/offset-pair.png)、[全幅形变测线](../../handoff/attachments/tectonic-driver-r3-r4-20261101/render/profiles-offset-full.png)、[反例形变测线](../../handoff/attachments/tectonic-driver-r3-r4-20261101/render/profiles-offset-focus.png)。offset单位是e偏移，不是最终e或Y。

- stress符号翻转517/491401（约0.1052%），梯度P99.9由0.0181369降至0.0106770；长尾比25.11→14.74。距离绝对差中位2.3409wu、P95=39.2453、最大81.5226。
- 原110包络、汇聚110和离散130门控最大绝对差分别约0.37556/0.32103/0.29123；**接线正确不等于包络原标定数值完全不变**，没有调参数补偿。
- offset绝对差P95=`9.1369e-6 e`、P99=`2.5209e-5 e`、最大`0.01309536 e`；符号翻转197/491401。A范围[-0.0417764,0.0343220]，B[-0.0417764,0.0343464]。零区很多，梯度P50=0时长尾比标null，不虚造指标。
- 主代理目检：候选距离梯度的旧环采样层状折角明显减弱，本窗未见明显固定格网条纹；原形变大部分轮廓保留，种子反例局部阶跃被连续变化替代。距离梯度图的亮线本身不等于折痕，不宣称全域无伪影/地质真实性。
- 固定B₃格相8组梯度能量观察：距离x/z的最大组/均值约1.0159/1.0097，offset约1.1764/1.2005；只作周期格网风险观察，无人为PASS阈值，不代表地质各向同性证明。
- 42张PNG原生回读与输入/输出SHA验证通过，9项小样本负控拒绝。独立numpy对全部raw的原门控公式最大差1.11e-16，43809测线坐标和数值有限；重出15个非计时文件逐字节一致。渲染器不生成高度、不插值放大，不以图片边界参与驱动。

### 13.4 成本及停止点

[原始成本](../../handoff/attachments/tectonic-driver-r3-r4-20261101/data/cost.json)：固定128点，2轮预热、5轮测量，每项640次；所有返回字段进入checksum并写volatile sink，防止只消费某一字段导致JIT裁剪工作。无缓存、含分配与JIT，不是FPS或实际区块耗时；旧/new驱动字段集合不完全相同（新含解析导数），比较是当前实现成本。

| 路径 | mean μs/query | P95 μs/query |
|---|---:|---:|
| 旧驱动 | 2.452 | 3.3 |
| 候选驱动（无缓存） | 20.457 | 21.1 |
| 原消费者（预计算旧Sample） | 0.285 | 0.4 |
| 同原消费者（预计算候选Sample） | 0.220 | 0.3 |
| 两臂适配诊断整对 | 22.846 | 31.7 |

候选驱动mean约旧驱动**8.34倍**，不能称零开销；成本主要与36投票及16个无缓存距离系数重复采样有关，但本轮未做未经授权的缓存/近似优化。早期只消费部分字段的成本另保留，最终以上述完整checksum版本为准，raw与剖面不受此修正影响。

**R3数值检查通过，R4规定图与数据已交付。** 随后用户裁定“效果先这样”，但不接受约8倍开销，要求先出仅诊断侧、逐位不变的缓存优化方案，见§14。未接造山权重、陆地形变叠加到高度、真实洋中脊配方或P4；未修改生产或调参数。

## 14. 诊断侧驱动缓存性能方案（只规划，待批准）

### 14.1 范围与冻结参照

只优化当前修复驱动的重复计算，不改变效果。性能参照为§13完整输出消费的无缓存候选（mean20.457μs、P95 21.1μs）和同批旧驱动；该数字只作历史检查点，实施时同进程重测。**逐位真值是当前无缓存DiagnosticTectonicDriver，而非旧生产TectonicField**。

冻结当前驱动全部Snapshot字段（U/S、world应力导数、D与距离导数）、原消费者六通道、R4主窗12个f64be文件及43809测线；参数、600/1200/240、+0.30、旧S3/M1a、生产/配置/cache schema不动。禁止降精度、改h/warp/σ/公式、删导数、跳过小权重或换累加顺序。仅写方案，本轮没有源码改动、构建或性能实测。

### 14.2 热点与推荐最小方案

依据[DiagnosticTectonicDriver.java:113-149](../../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/terrain/candidate/DiagnosticTectonicDriver.java#L113-L149)：每次distance求16个固定格点系数，每系数重跑warp和25站点最近/次近；相邻查询高度复用同一系数。另有每次stress的36站点与系数构建反复算hash、sin/cos；**是源码热点推断，不假称已由分量profiler确认**。

首选两个精确记忆化缓存：

1. **固定距离系数缓存**：键为B₃全局整数格点(ix,iz)，值严格为原`rawDistance(ix*H, iz*H)`返回的double；miss必须用与无缓存路径完全相同的乘法、原公式及循环次序计算，不能从查询坐标四舍五入反推格点。缓存的是系数，不是近似距离网格，也不再次插值最终结果。
2. **站点/速度缓存**：键为全局板块整数格点(cx,cz)，值为原`site`的不可变Site，包括sx/sz/vx/vz。hash、三角函数和浮点计算顺序保持；不缓存依赖查询点的投票权重或贡献。

每实例固定seed/算法版本，缓存完全实例隔离，因此内部键可用`((long)x<<32) ^ (z&0xffffffffL)`，负坐标不能碰撞。不使用全局静态cache、Gallery最近实例或运行中setSeed；不要把缓存内容与世界存档绑定。不预热邻域、不fire-and-forget生成邻tile。淘汰只能重算同值，不能走粗采样回退。

建议首档：系数容量8192、站点容量1024，各为有界access-order LRU（仅缓存预算，不是地形旋钮，不新增用户配置/-D）。主窗每行约136个系数列、4行支持约544条，8192可覆盖当前连续扫描活跃集并留重访余量；这是容量理由，不是保证命中率。报告实际容量、峰值条目、hit/miss/eviction与估算内存/worker。先不扫多档容量调出漂亮数据；若容量/对象开销不合适，报测量再决定。

### 14.3 接线与逐位纪律

- 保留显式无缓存构造/路径，原无参行为不自动切换；新增显式cached入口供新check/性能臂使用，传播Gallery也仅显式选择。数学版本不变，缓存策略另记元数据。
- 允许把原`site`和系数计算抽为唯一uncached body，再由cached lookup包装；不得重构stress/distance算法或重排reduce。旧driver源文件N0备份及已编译输出作为独立真值，不能只拿同一个改后helper证明自己。
- 命中值仍以原位置进入原i/j循环，导数相加与归一完全原序。使用`Double.doubleToRawLongBits`比较，不用epsilon/allclose；签名零也核对。计时、统计和路径声明不要求字节相同，其余数值都要求。
- 单worker单实例，cache非线程安全，不承诺共享实例并发；多个worker各自固定seed和LRU，另测独立实例并发对拍。若将来要共享实例，另评同步，不为本轮性能直接引入全局锁或线程本地无界Map。
- 不缓存最终任意(x,z)样本：浮点键成本高且对邻近不同点无复用；不再加第三层noise cache、批量近似、导数裁剪或算法优化。两个cache已足够作为首个单变量集合。

### 14.4 分阶段与有牙齿验收

| 阶段 | 工作 | 通过合同 |
|---|---|---|
| C0 冻结/测量 | 冻结无缓存源码/class/SHA、R4数据与旧资产；增加局部分量计数，固定同一基准集 | 可区分stress/系数构建/距离/消费者成本，不能只看整对适配耗时；不改公式 |
| C1 精确缓存 | 依次接系数cache、再站点cache，分别保留off/coefficient-only/both臂 | 每项值、导数与无缓存独立真值raw bits一致；无默认切换 |
| C2 正确性 | R1/R2/R3、4seed、正负/远点、ULP/格边界、同点重访、正反乱序与worker隔离 | 无缓存/cold/warm/容量压力全部逐位一致；缓存未命中或淘汰都无数值降级 |
| C3 成本/交付 | 先有限基准、再一次同窗R4全幅及43809测线导出 | 所有12 raw与剖面数值/坐标逐位一致，PNG同图（标题/耗时元数据分离）；实测加速和内存交付后停止 |

真正淘汰测试须让某个已记录键被其他键挤出、确认evictions增加且该键不在cache，再查询使miss增加并对独立基线相同；不能仅clear后称LRU淘汰。容量再小的测试夹具也不能改变原生成参数。错seed复用/错误格点键/缓存错值必须被负控检出。

基准至少分四类，不提前许诺目标倍数：

- **原128点序列**：同时测每轮全新实例的冷cache、热cache；不能只有循环命中同128点的漂亮热数。
- **局部真实访问**：701²同窗step10行扫描、逐块剖面、固定tile式分块次序，核对系数构建数下降。
- **稀疏跳点/容量抖动**：固定远隔坐标、负轴、远点及强制超过容量；如比无缓存更慢，如实报告。
- **独立worker**：单worker与少量独立实例并发，报告吞吐、P50/P95/max、分配/GC与内存；不冒称实机FPS，不混入生成A旧driver整对适配的成本。

同JVM、同固定序列、相同完整checksum/volatile消费，交替基线/候选运行顺序，报告多轮分布，避免JIT、CPU节流与测量顺序误导。若采用microbatch计时降低纳秒钟噪声，必须两臂一致；不把cache miss暖机丢弃后称冷性能。缓存读写自身成本算在内，计数埋点影响单列。

**硬停止：**任何一位不同立即停修，不升级容差；冷/稀疏成本或内存不可接受则交证据，不靠减少质量补偿。性能结论先归因为两层cache各自节省的实际构建次数，不能凭命中率反推固定加速比。缓存通过也不接构造地貌/P4、不动生产；这不是为朋友游戏拆SDK的授权。

### 14.5 朋友游戏初步对接的当前边界

**可做固定快照、有限范围的原型验证，暂不能称为可直接嵌入的稳定跨引擎SDK。** 推荐先用已验收0.30无构造有限区域的高度/类型数据对接朋友的地形渲染、碰撞和水面，再确认是否需要实时采样接口；这不会擅改当前主线。

事实依据：当前600完整链入口[CoherentMorphWorld.java](../../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/terrain/candidate/CoherentMorphWorld.java)仍在独立diagnostics source set，类/方法多为包内可见，含可变LRU与Gallery/反射耦合，并非现成公共库。Java目标17/运行构建21不代表可直接给Unity/Unreal/Godot调用；接入方式需看朋友引擎/语言。生产`GeoGenesisTerrain`仍依赖项目配置、水文与MC生成环境，不能直接把整个Forge模组jar当其他游戏插件。

需要分开交付：原生产版有原水文/侵蚀等；当前0.30/600候选未与这些系统及新构造/P4整链合流。不能把README功能列表当成当前新候选已统一完成。原版树/结构/洞穴装饰/方块等是Minecraft提供，在其他游戏不会自动存在；零MC依赖的形态/洞穴几何也仍需适配坐标、材料/岩性、资源与生命周期。

实时/大范围阻塞仍在：旧float反warp远坐标失败、候选性能与多worker生命周期、区块边界/采样精度/种子序列化和接口版本尚未形成跨引擎验收合同。跨语言浮点/整数溢出/三角函数重写不保证逐位；初期固定Java输出作真值，不让对方引擎另写一套相似算法便称相同世界。

对外代码或分发前还须核对许可：项目README声明All Rights Reserved，但[RefNoise.java](../../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/terrain/candidate/RefNoise.java#L3-L9)写参考原样实现，[RegionCellTable.java](../../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/terrain/candidate/RegionCellTable.java#L3-L7)写表原值照录；本地FreeTerraForged参考LICENSE为MIT，要求保留相关版权/许可。README“无第三方需署名代码”不能替代逐文件来源核查；MDK LICENSE是Forge条款，不是模组整体授权。此处只提示审计边界，未完成许可结论，不把Minecraft/Forge代码、资源、参考目录整包迁移。

开展实际对接前先明确：朋友游戏引擎与语言、体素或高度网格、离线导入或实时无限生成、首批只接地形还是含水文/洞穴/材料。先约定seed/版本、世界单位与轴向、Y/海平面范围、块尺寸/采样网格、水体与材料数据格式，以及固定测试点/地图/接缝验收，再单独授权导出适配器或纯Java核心封装。本次不创建交接包、SDK或修改许可证。
