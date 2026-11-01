---
name: 大陆分布、可靠几何与P4海岸统一主线
overview: 保留当前600/S3架构及冻结图，先出不改参数的大范围真实海陆分布总览，用户看图决定比例与大陆形态，再以独立候选衔接可靠几何和P4海岸；不把并案视为整链实施授权。
todos:
  - id: wide-distribution-baseline
    content: 先导出当前规则约56000×56000块海陆标签与连通形态总览，标出原S3窗口，不改参数或求高度
    status: pending
  - id: confirm-distribution-target
    content: 用户看原状图后分别确认海陆比例与大陆连通形态目标，必要时另批单变量分布候选
    status: pending
  - id: verify-geometry-foundation
    content: 复核N2证据边界，再按授权推进N3～N5数值几何候选，保留旧S3与既有容差
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

**本轮只形成方案，没有新出图或改代码。** 用户选定首步出图口径，不等于批准新的比例值、分布算法、N3～N5或立即开始P4。实施时先做下节M0；M0交图后停下等待比例/形态决策。

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
