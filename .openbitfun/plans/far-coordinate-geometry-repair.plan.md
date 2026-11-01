---
name: 远坐标数值几何与多seed上下文修复方案
overview: 保留S3旧入口与冻结基准，分离连续高精度分区几何、受保护反warp求解和任意seed完整上下文三条改动；先局部证明再接独立候选，禁止通过调地形参数或放宽容差掩盖失败。
todos:
  - id: freeze-contract
    content: N0已冻结并核对116份候选源码、S3 raw/剖面/10张PNG及S4失败证据
    status: completed
  - id: seed-context
    content: N1显式真实seed上下文已实现，正典raw/PNG逐位不变且三额外seed完整高度链通过
    status: completed
  - id: continuous-geometry
    content: N2已完成：唯一倍频warp的连续double原语、解析导数、Bernstein严格包围与逐格det J>0；全局单射仍未证
    status: completed
  - id: safeguarded-inverse
    content: 增加受保护反解、根唯一性或分支证据、world误差证书与折线加密失败可见性
    status: pending
  - id: isolated-integration
    content: 让分区赢家、浅海判断、配方外边和完整高度链共同注入同一候选几何上下文，不改旧S3
    status: pending
  - id: streaming-validation
    content: 分阶段回放多seed/远坐标/顺序/缓存验证并出候选对照图与成本，失败停止且不接生产
    status: pending
---
# 远坐标数值几何与多seed上下文修复方案

## 0. 授权、推荐路线与保护边界

**状态更新：用户已授权 N0～N1 与 N2；N2 只做原语/导数/单射，未接高度链与新反 warp。N3～N5 未授权、未实施。** N1仍用旧float几何。实施及验证见§9（N0～N1）与§10（N2）。实际工程为Forge 1.20.1/47.4.10，Java21运行构建、源码target17，见[gradle.properties](../../forge-1.20.1-47.4.10-mdk/gradle.properties#L10-L16)。

推荐采用：**旧S3原样保留；先做“任意seed上下文、数值不变”的等价臂，再做“只升级分区几何数值精度”的独立候选。** 不全局把RefNoise改成double，不复制另一套地形引擎，不按离原点距离动态切换算法。

冻结不动：18陆地+5海洋配方、族seed/幅度/频率/bias、地面基准与HeightCurve、600微细胞/1200宏分布、warp频率400/幅度200、S2规则语义、普通带240/半宽120、默认值、配置、生产及预览cache schema。400/200等是现有参数，不是本方案新增调参旋钮。

需要提前明确的产出边界：

- **原S3入口与原始文件必须逐字节不变、仍可复现。** 数值候选是新臂，不覆盖原文件、不更新“现行基准”。
- 连续double几何通常不可能与旧float几何处处逐位相同；同参数不等于同数值。候选的边界、临界赢家、浅海标签与混合高度可能变化，必须量化并出A/B，不提前许诺“修好且一像素不变”。
- 若要求未来新算法同时在全域连续、对float旧产出全域逐位相同，这两项约束一般冲突；发现冲突应停下报证据，不用坐标阈值拼接伪装兼容。
- 修复分区几何不等于提高所有地貌噪声的远坐标精度。现有高度配方/覆盖mask仍有float坐标，先保持其算式不动并单列平台化/不连续风险；如它们另有可见问题，另报方案，不顺手升级。

## 1. 已知失败与根因边界

### 1.1 实证

S4原始[完整S3异常栈](../../handoff/attachments/coherent-stream-s4-20261101/s3-failure-stack.txt)：seed9139912035078620160，`new CoherentMorphWorld().sample(1000000,1000000)`，world折线构建残差7.1913块>0.02，实际exit1。矩阵中seed0的(-100000,100000)也因0.020952>0.02失败；不能把百万格当作安全边界。

[矩阵报告](../../handoff/attachments/coherent-stream-s4-20261101/coordinate-matrix/check.txt)：4seed共688次反解，204次残差失败；远几何6窗5失败，远浅海4次4失败。3个非正典完整高度构造BLOCKED，不算通过。214条FAIL是多次测量，不是214个独立缺陷。近点跨tile/顺序/真实缓存淘汰样本通过，但不能抵消远端失败。

### 1.2 源码事实

| 环节 | 精度/结构问题与证据 | 方案处理 |
|---|---|---|
| 噪声输入 | [RefNoise.java:36-104](../../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/terrain/candidate/RefNoise.java#L36-L104)：Node只收float，Perlin在缩放、格点局部量、插值与累加阶段均float | 旧实现冻结；仅几何warp增加专用连续采样路径 |
| 前向分区 | [RegionPartition.java:87-106](../../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/terrain/candidate/RegionPartition.java#L87-L106)、[LocalBoundaryGeometry.java:415-419](../../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/terrain/candidate/LocalBoundaryGeometry.java#L415-L419)：world先转float，位移相加和乘频率也float | 前向、赢家查询、解析边界统一同一double变换，不能只修反向 |
| 反解 | [LocalBoundaryGeometry.java:434-457](../../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/terrain/candidate/LocalBoundaryGeometry.java#L434-L457)：h=.05前向差分，det小直接break，150步长上限但无残差下降线搜索；返回res可能是最后更新前值 | 新求解器重算终点残差、显式状态、导数和步长保护；旧求解器不变 |
| 反解目标 | 精确u边/二分中点是连续值，float前向输出是离散格点集合，目标不一定可达 | 只加大h或迭代次数不能解决不可达目标；不得吸附u边或放宽tol |
| Voronoi裁剪 | [LocalBoundaryGeometry.java:729-753](../../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/terrain/candidate/LocalBoundaryGeometry.java#L729-L753)：全局u上的系数/相消及固定误差判据也要审计 | 候选使用局部坐标做裁剪，保留全局整数cell键 |
| 折线加密 | [LocalBoundaryGeometry.java:832-877](../../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/terrain/candidate/LocalBoundaryGeometry.java#L832-L877)：沿前点续根；depth20可直接return；小残差不证明根唯一 | 新臂递归上限未达几何误差要求必须FAIL；验证端点/中点/分支/两侧赢家 |
| 高度/覆盖 | [NodeMorphEngine.java:77-101](../../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/terrain/candidate/NodeMorphEngine.java#L77-L101)、[FullMorphLand.java:283-296](../../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/terrain/candidate/FullMorphLand.java#L283-L296)仍有float求值 | 第一轮不修改，只在候选完整高度检查中如实记录 |

已证数值现象：float ULP在1e6处=.0625块、1e7处=1块，固定h=.05在1e7处完全退化；但**尚未证明这是唯一原因，也没有反warp全域单射证明**。不能把“残差低”当“world边没漏分支”。

## 2. 路线取舍

| 路线 | 结论 |
|---|---|
| 只增大差分h、提高迭代数、放宽0.02 | 不采用为正式修复：只能改善某些收敛，不能恢复float丢失信息或保证连续目标可达。可作为定位对照，不得接入候选终态 |
| 全局改RefNoise.Node和所有配方为double | 不采用：会改变原子端点、覆盖与旧图，范围超过数值几何修复 |
| tile中心移到原点后直接采原噪声 | 不采用：若不保留全局格点哈希与相位，会让不同tile采到不同场，产生接缝 |
| **专用连续几何数值后端+显式seed上下文** | 推荐：复用既有拓扑、权重与形态内核，只隔离旧float/新连续几何后端；可逐层测量、旧基准不动 |

无需现在决定某个“可容忍高度差”来包装候选通过；保留既有数值门禁，候选与S3的视觉差由用户看图裁定。

## 3. float精度改动范围

### 3.1 新几何数值契约（建议名GeometryTransform）

只在诊断候选显式注入，旧构造仍绑定旧float后端。变换定义、分区站点/赢家、边界求解、浅海匹配、支持域计算必须同源，不允许赢家用旧float、边线用新double的半接线。

- world x/z、坐标缩放、warp位移相加、u/v、噪声格内小数与插值均double；不能调用`oldNode.compute((float)x,...)`后再转double冒充高精度。
- 只实现几何warp实际需要的Perlin(400,1)连续求值与导数，保留相同哈希整数溢出、梯度表、Hermite曲线、seed、归一规则和现有参数位模式。原`1f/scale`等配置常量先按原位模式提升，避免把“换精度”与“换有效频率”混成两个变量；若需改倒数表示，单列候选，不偷偷改。
- 保持噪声幅度夹取语义与导数分段一致；导数边界/夹取平台要测试，不能对已夹取输出套未夹取导数。
- 格点索引由全局缩放double坐标floor得到，哈希用全局整数；局部浮点运算在格内坐标进行，负坐标用floor，不用截断。
- 微cell稳定键/抖动表/`.7f`历史参数保持；NATURAL势在候选统一double评估，赢家并列裁决固定顺序。宏锚点分布、票槽、c场语义保持原S1，原始基础标签按cell key应逐位一致；新赢家覆盖的像素可因几何移动而变化。
- 裁剪采用平移后的局部u坐标，避免大常数相消；cell key始终全局。不可将局部原点或tile号混入seed/身份。

**不纳入首轮：**大陆c场4000/6、陆地覆盖Worley/warp、全部地貌族/NodeMorphEngine数值精度升级。其float误差单列，不把“几何恢复可构建”宣传成“全部远地形数值完美”。

### 3.2 源码影响表（建议名不等于授权创建）

| 文件/模块 | 预计改动 | 旧行为保护 |
|---|---|---|
| 新GeometryTransform/连续Perlin实现 | 正向值、Jacobian、位移界、版本身份；旧float适配器与新连续实现 | 不扩散修改RefNoise公共Node；共享可安全复用的hash/表需先证明不改旧输出 |
| LocalBoundaryGeometry | 显式数值上下文重载；前向/逆向/赢家/候选界/局部裁剪/折线均用注入后端 | 原of/atGallery/galleryOwner与常量/默认不变 |
| RegionPartition | 候选构造需要的seed/warp配置只读描述，或新上下文提供它 | 原sample和默认1200不重写；600候选不靠全局改常量 |
| ShallowRegionMatcher | 新构造显式接同一几何factory；半径/数值余量来源可追溯，240规则不变 | 旧构造与原S2标签可重放；候选标签变化要另报，不能增余量掩盖 |
| FullMorphLand.Tile/RecipeBoundaryWeights | 候选赢家必须与候选几何同定义；外边/单ease算法不改 | 避免Tile另用旧warp计算赢家；旧路径不变 |
| CoherentMorphWorld/RegionalMorphWorld | 候选显式seed与上下文；共用evaluate，不重造高度链 | 无参S3与旧构造保持旧输出，禁止默认切换 |
| 新Check/Gallery | 分层回放失败、分支/支持证据、候选大图/剖面/成本 | 新目录，不覆盖既有S3和S4证据 |

## 4. 反warp求解：收敛与几何正确性分别验收

### 4.1 求解算法

对连续候选求`F(p)=p+o(p)−targetWorld`，world块单位计算残差，避免先乘小频率再反除导致诊断失真。

1. 首选专用Perlin解析Jacobian；独立中央差分核验，在格点边界取两侧极限。数值差分只作为交叉验证/明确的受控降级，不再对float台阶场盲用固定.05。
2. 牛顿方向加信赖域/步长限制与残差下降回溯；奇异或病态Jacobian、不下降、越过已证有效域、达到迭代/细分上限均输出明确失败状态和输入，不能返回旧残差当成功。
3. 终点重新算前向残差；区分未收敛、病态、歧义、细分未达标。保留外部0.02块残差限；内部目标与误差预算严于外部，不放宽容差。
4. 使用同一全局固定规则选初值，邻边续根仅作加速；独立冷求解、正反方向追踪与不同窗口必须验证同根/同边。不能把第一个缓存根当数学定义。

### 4.2 必须先回答“根是否唯一”

- 对实际warp建立Jacobian导数界：若能证明位移场全域Lipschitz界L<1，则有单射及world误差上界`||δp||≤||F(p)||/(1−L)`，据此给出迭代/搜索半径证书。
- 若全域界太保守或不成立，尝试局部区间Jacobian/区间牛顿证书并覆盖全部目标支持域；单点det非零或一次收敛不是单射证明。
- 若无法排除多个有效原像，或发现多个根/漏分支：**停止当前单折线路线，给出seed/目标/根/两侧赢家与局部图**，另提“在world域直接追踪等势边界/枚举全部分支”的方案。不得擅自减warp幅度、关warp或任挑一根继续交付。
- 新候选对已证位移范围内做有限搜索；不追整世界，不依赖已生成邻tile。超出求解/区间预算是BLOCKED/FAIL，不是丢边。

### 4.3 world折线与误差预算

保留S3实际使用的chord=.02、maxSeg=2、invertTol=.02与支持半径120；默认常量不改。分别测量反解残差、world位置误差、弦偏差、拓扑/两侧赢家和截断完整性，不能把它们都叫“0.02误差”。

- 求解world误差需由逆Jacobian/唯一性证书约束；没有证书就不能拿正向残差直接当world误差。
- 加密用端点/中点并加四分点检查或曲率上界；细分到depth上限仍超限必须失败，不静默return。
- 两侧采样应对应预期两cell，并确认无第三cell遮挡；真三域点保留全部参与者。
- 框截断端点必须在查询tile+120及浅海目标支持域外；frame不当真边，合法穿越真边不能整条丢弃。
- 浅海240+数值余量要来自求解world误差与弦误差预算。原S2余量是预算而非严格证书，新臂不得为了通过大幅扩余量；若理论预算与当前标签冲突，单列变化、停下确认。

## 5. 多seed完整上下文：与精度修复解耦

### 5.1 真正阻塞与可复用部分

[RecipeMorphCatalog.java:254-260](../../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/terrain/candidate/RecipeMorphCatalog.java#L254-L260)的配方与槽表本来按seed构造，但`original()`只反射正典Gallery；[FullMorphLand.java:131-156](../../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/terrain/candidate/FullMorphLand.java#L131-L156)依赖original的分区、mask与旧覆盖族而拒绝其他seed；CoherentMorphWorld无参还固定正典seed。

[LandCompositionGallery.java:299-359](../../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/terrain/candidate/LandCompositionGallery.java#L299-L359)的buildCombine写死SEED且是多个已认可臂共享路径，**不去“修”它**。现有[RecipeMorphCatalogCheck.java:656-684](../../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/terrain/candidate/RecipeMorphCatalogCheck.java#L656-L684)展示了真实seed独立构造；其已测45槽/复合值，不代表mask/覆盖/整S3上下文已全证，不能直接搬测试当新实现的独立真值。

### 5.2 最小上下文（建议LandRegionContext）

按worldSeed构造不可变描述：partition seed/warp参数、45槽及类别、真实覆盖mask、旧覆盖台地族、混合阈值、几何数值后端。不得从全局Gallery或最近访问实例取seed；包含seed、数值版本与尺度的缓存键，或每实例完全隔离缓存。

必须保留的种子计划：

| 内容 | 现有种子/游标位置 |
|---|---|
| 10对低地复合selector/warp | base+0..19，每对两次 |
| 45槽洗牌 | base+20 |
| 微分区warpX/warpZ、cell hash | base+21/base+22/base+23 |
| 覆盖mask | Worley(base+4241,2250)，warp游标base+24(333,2,250)，后接Curve3/Clamp/Map |
| 族偏移 | seed+0/0/33/11/22/44/55/77；base定义保留32位折叠与溢出 |
| S1宏分布 | 原+1700000/+1710000/+1720000/+1730000及c+7000，海洋seed偏移保持原值 |

依据[CombineCandidate.java:228-274](../../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/terrain/candidate/CombineCandidate.java#L228-L274)、[723-744](../../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/terrain/candidate/CombineCandidate.java#L723-L744)。游标位置依赖复合对数，不能因重构构造顺序移动，也不要以32位seed折叠碰撞为由另改哈希算法。

特别保护：覆盖族是旧Plateau REF的1.05 Terrace语义，不是catalog修正后的台地端点；见[PlateauCandidate.java:103-106](../../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/terrain/candidate/PlateauCandidate.java#L103-L106)、[FullMorphLandCheck.java:375-400](../../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/terrain/candidate/FullMorphLandCheck.java#L375-L400)。两者不可互换，mask阈值.55/.78和float混合顺序都保留。

### 5.3 接线顺序与兼容

- 新工厂按真实seed创建必要上下文，优先复用已有族构造器/配方槽表；最少复制seed组装，不把测试里的同一builder移走后再用它自己对拍自己。
- RecipeMorphCatalog.original()与FullMorphLand旧构造守卫保持原义；**新增显式context入口**，不直接删除guard，也不把“有context对象”当等价证明。
- LocalBoundaryGeometry增加显式分区/槽/数值上下文工厂，避免裸分区无slot又包FullMorphLand.Tile的已知测试夹具错误。
- FullMorphLand新增context构造，覆盖函数仍复用同一段算法；RegionalMorphWorld新增context构造复用同一个evaluate；CoherentMorphWorld新增显式seed/context入口。旧无参构造不切换。
- 原子族、OceanRecipeCatalog、统一NodeMorphEngine、CombineCandidate及Gallery旧工厂不改；既有“非正典旧构造应拒绝”的测试仍保留，对新入口另加合法构造测试，不能为新能力删旧守卫负控。

## 6. 分阶段实施与停止条件（N0～N1已授权完成，其余待授权）

| 阶段 | 唯一变量/交付 | 必须满足后才继续 |
|---|---|---|
| N0 冻结 | S3源码、两臂高度/type/active/sea、winner、50109剖面与PNG SHA；S4失败向量/环境/完整属性 | 新目录、旧基准可重放；任一旧字节变动立即停 |
| N1 真实seed上下文 | 仍旧float几何，新增context完整高度入口；正典与旧S3对拍、非正典near真实高度 | 正典mask/覆盖/槽/权重/e/全窗raw逐位一致；新seed不借正典对象；远坐标此阶段仍允许明确FAIL但不宣称修好 |
| N2 连续几何原语 | 只测前向/噪声/Jacobian/局部势边，不接整幅高度 | 独立公式/导数/全局相位/负坐标/远点检查；若单射或世界误差证书不足，先报证据 |
| N3 受保护反解 | 回放已知失败目标、初值/顺序/反向追踪/折线 | 残差≤.02且world误差受界，拓扑/分支/细分完整性通过；旧算法作为失败负控 |
| N4 独立候选接线 | 同一数值后端贯穿赢家、浅海、外边、上下文/evaluate | 不能半接线；基础cell标签不变，因几何引起的winner/浅海变化有因果记录 |
| N5 S4重验与图 | 先局部多seed远点、缓存/窗口，再一次正典全幅A/B+ROI+六全幅三局部剖面+成本 | 失败不靠调参解决，图交用户裁定；不自动改S3基准，不迁生产 |

N1与N2可独立实现/研究，合流前分别验收；同文件变更不可交给并行代理覆盖。每阶段独立留证，可停止，不把批准方案自动解释成整链实施授权。

## 7. 验证矩阵与防自证

### 7.1 不变合同

- 旧S3完整raw/PNG/源码SHA与N0一致；旧无参入口重新生成到新目录对拍。N1显式context+旧后端须与旧S3全窗/50109剖面逐位一致；候选N4不要求与旧图逐位相同，但原配方对同点/同权重求值必须不变。
- 正典上下文：45槽、10复合、分区seed/warp、mask、旧台地覆盖、.55/.78分支逐位对原反射对象；mask自然分支无样本用合成分支补覆盖，不把“没触发”当正确。
- 额外seed=0/-419/12345，加正典：完整上下文构造和近点实际高度，不只测目录原子端点；独立原族构造核对mask/旧覆盖语义，seed敏感性使用不发生32位折叠碰撞的样本。
- 23配方端点、纯陆旧float求值、纯海负e、极小正海权重、A/A/B/孔洞/窄条/三域、单次ease/归一、240/120不变，相关旧门禁继续有效。

### 7.2 数值与几何

- 原S4失败CSV全部作为固定回归；成功/失败区分PASS/FAIL/BLOCKED，进程非零；重点含正典(1e6,1e6)、seed0(-1e5,1e5)、正典接近±29998000。
- 小固定矩阵：0、±512、1e4/1e5/1e6/1e7/近世界边界；正负轴/异号组合、整数/半格/任意连续u目标、噪声格点及两侧epsilon，不扫密集参数梯度。
- 独立噪声参考按全局整数哈希+double局部插值写在探针，不调用同一实现；解析导数对中央差分/边界两侧极限；单调/连续与位移幅度检查。
- 反解既做forward目标，也做非前向格点目标；不同初值/边遍历/冷缓存对拍。重算终点残差，记录Jacobian条件、线搜索与误差证书。故意换回旧float后端和固定h失败向量必须检出。
- world边线与前向直接赢家的独立密集局部/自适应轮廓对拍；5块栅格EDT只作粗交叉验证，不当.02块证明。两侧赢家、第三域、闭合孔洞、截断halo、候选扩大枚举，出现漏分支先停。
- 跨tile/不同中心半径：身份在**同一后端**逐位一致；新权重/e沿用既定2e-3门禁不放宽，同时报告world距离差，不用原始量级掩盖归一误差。含真正混合样本，不只纯onehot。

### 7.3 流式与缓存

- 正序/逆序/固定乱序/独立冷实例，以及8 tile、2048身份、128 patch真实淘汰重建；确认对象确实离开缓存、重建次数增加，不能只清Map或断言容量。
- 新上下文同seed独立对象和不同seed交错查询，禁止同cell键跨seed/数值版本串缓存。基础身份不依赖反warp根、tile号或访问顺序。
- 明确线程模型：旧S3有可变LRU非线程安全，不能默认共享同实例并发。先验证每worker独立实例的并发结果；如目标必须共享实例，单列缓存同步/生命周期设计后再实施，不靠一个并发测试偶然通过宣称安全。
- 记录显式窗口+matcher内部patch总数/失败次数，先小样本定位、再一次全幅。计建场/反解迭代/候选与线段数/求距/采样/P95及内存，区分精度升级开销和新增多seed构建开销，不预先许诺零开销。

## 8. 验证入口与交付约定

实施后依次：新数值类静态/编译 → 工程`compileDiagnosticsJava` → N1上下文门禁/旧基准对拍 → 新几何局部门禁 → S4固定回归与流式 → 用户图像验收。若新建独立check，所有FAIL必须非零；原CoherentCoordinateCheck旧臂仍应保留为失败负控，不把负控失败写成候选失败，也不让BLOCKED算PASS。

相关范围包括RecipeMorphCatalogCheck、FullMorphLandCheck、NodeMorphEngineCheck、CoherentMorphCheck与S4两探针；复验共享构造影响时才扩大旧七臂对拍，不默认重跑无关水文/游戏全门禁。未来生产迁移另行授权，届时才涉及预览schema和生产门禁。

交付至少包括：逐项影响清单、旧基准SHA一致证据、真实seed上下文对拍、失败向量回放、数值误差/分支证书及未证项、候选正典1401²全幅+圈定ROI+逐块剖面、远点局部证据和成本。候选全部用新文件名/目录；若仍FAIL，交原始输入、stack与反例，不补新“当前基准”。

**本方案本身不授权自动推进。** 用户随后只批准N0～N1，已按§9实施；连续几何/反解及N4候选仍未授权。S3原基准、地形参数、240带、默认和生产全程保留。

## 9. N0～N1实施与验收记录（2026-11-01）

### 9.1 本轮授权与实际影响

用户授权：“按方案先做N0～N1，抽出多seed完整上下文，先证明正典seed下与S3基准逐位不变，再验证其他seed能实际构造并采样完整高度链。连续高精度几何和反warp先别接，参数、240块过渡带、默认值和生产都别动。”

- 新增[LandRegionContext.java](../../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/terrain/candidate/LandRegionContext.java)：复用catalog槽表和既有微分区工厂，以真实seed建立mask、旧Plateau REF覆盖、阈值；不从Gallery对象借这些上下文。catalog正典构造本身仍保留原有Gallery引用，这是旧目录行为，不宣称删除全部Gallery代码依赖。
- [LocalBoundaryGeometry.java](../../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/terrain/candidate/LocalBoundaryGeometry.java)只加显式context工厂，原float正反变换/裁剪/折线及所有旧factory不改。
- [FullMorphLand.java](../../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/terrain/candidate/FullMorphLand.java)、[RegionalMorphWorld.java](../../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/terrain/candidate/RegionalMorphWorld.java)、[CoherentMorphWorld.java](../../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/terrain/candidate/CoherentMorphWorld.java)新增显式构造；旧无参入口/非正典旧守卫保留，同一evaluate/覆盖算法复用。S3显式入口拒绝非600尺度。
- [CoherentMorphGallery.java](../../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/terrain/candidate/CoherentMorphGallery.java)两参数仍走旧入口，仅第三参数`context`选择正典新臂。新增[LandRegionContextCheck.java](../../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/terrain/candidate/LandRegionContextCheck.java)，分canonical/multiseed两次显式执行。
- 与N0比较仅5个既有candidate文件修改、2个新文件；191份生产/构建配置SHA不变。RefNoise、RegionPartition、RecipeMorphCatalog、NodeMorphEngine、CombineCandidate、ShallowRegionMatcher、RecipeBoundaryWeights均SHA不变。几何文件移除新增factory后与N0全文一致，见[scope-check.json](../../handoff/attachments/seed-context-n0-n1-20261101/scope-check.json)。

### 9.2 正典先行，逐位不变

N0冻结116份candidate源码、S3数据14份、PNG10张及S4失败。冻结副本完整性见[readonly-audit.json](../../handoff/attachments/seed-context-n0-n1-20261101/readonly-audit-20261101-071407-5d1de8ce.json)。

先重跑旧入口，再运行canonical Check及正典context全幅；通过并核对SHA后才启动multiseed。canonical **34994断言、0失败、676次完整样本逐位比较**：45槽/类别、10复合、分区/warp、713点mask与旧覆盖、float阈值分支、真实S3权重/e与HeightCurve均通过。自然mask低/中/高=582/110/21，合成覆盖分支另计；详见[summary.txt](../../handoff/attachments/seed-context-n0-n1-20261101/canonical-check/summary.txt)。

两种入口分别在新目录导出1401²×两臂及50109剖面点；**各12份验收文件与冻结S3 SHA全同**（计时stats不要求相同）：[parity-old.json](../../handoff/attachments/seed-context-n0-n1-20261101/parity-old.json)、[parity-context.json](../../handoff/attachments/seed-context-n0-n1-20261101/parity-context.json)。

| 数据 | 冻结/旧入口/新context共同SHA256 |
|---|---|
| before高度 | `dd74f4a0ab03a0190a5153f164047c02cc0eb47be023d103a2ae31a0580c2dd1` |
| after高度 | `af207c94935c86b55ddd00e7155a4b375bfa2f1254a227c10811960cefafdc29` |
| winner | `5096a6656493edfab6000029b592d8151077bab37ff84c36805d691d1113805c` |
| 50109点剖面 | `8c9b4149dabf831629725fc4d82737a92e01fab203e30fd0ca3c1f0575bf5697` |

用原渲染脚本从context真实raw重出10张PNG（含全幅、ROI、六全幅三局部剖面），**10/10 SHA与S3一致**：[parity-png.json](../../handoff/attachments/seed-context-n0-n1-20261101/parity-png.json)。图见[pair-full.png](../../handoff/attachments/seed-context-n0-n1-20261101/context-render/pair-full.png)、[profiles-full.png](../../handoff/attachments/seed-context-n0-n1-20261101/context-render/profiles-full.png)。本轮为S3沙箱等价验收，不是游戏生产迁移或新视觉效果验收。

### 9.3 多seed真实完整高度链

每seed在[-1800,1800]²、step300固定169点真实调用S3双臂sample；**无需启用补样**。独立真值使用未改动检查器的原族Combine builder，不使用context builder当自己的oracle。mask/旧1.05台地/统一evaluate逐位、有限HeightCurve、合法权重、纯海负e、正序/逆序/固定乱序/独立冷实例/三seed交错及分支见证回访均通过；旧original及两种旧FullMorphLand构造非正典拒绝仍通过。

| seed | 纯陆 | 纯海 | 海陆混合 | 自然mask低/中/高（713点） |
|---|---:|---:|---:|---|
| 0 | 150 | 3 | 16 | 482/178/53 |
| -419 | 138 | 17 | 14 | 581/127/5 |
| 12345 | 71 | 60 | 38 | 572/130/11 |

两臂各自分类相同，不把构造成功或目录端点当完整链成功。总计**63730断言、0失败、2037次逐位比较**，见[summary.txt](../../handoff/attachments/seed-context-n0-n1-20261101/multiseed-check/summary.txt)及[samples.csv](../../handoff/attachments/seed-context-n0-n1-20261101/multiseed-check/samples.csv)。

### 9.4 相关回归与未解决边界

- `compileDiagnosticsJava`成功；Java21运行、class major61。RecipeMorphCatalogCheck=18201、FullMorphLandCheck=910、NodeMorphEngineCheck=617318、CoherentMorphCheck=10672、RegionalMorphWorldCheck=29033，全部0失败。
- 原S4 StreamingCheck完整重跑PASS：105次比较，权重最大差3.717425e-6、e最大差6.674010e-7；8 tile/2048身份/128 patch实际淘汰重建通过，见[stream.txt](../../handoff/attachments/seed-context-n0-n1-20261101/stream-check/stream.txt)。它是旧入口回归，不等于新context全域缓存证明。
- 旧入口正典(1000000,1000000)失败负控重放仍为**残差7.1913>0.02、exit1**，见[check.txt](../../handoff/attachments/seed-context-n0-n1-20261101/far-negative/check.txt)。这是明确未修的预期负控，不是N1新失败，也不把其BLOCKED写成S4通过。
- 环境/命令/全部JVM属性见[environment-commands.json](../../handoff/attachments/seed-context-n0-n1-20261101/environment-commands.json)：`-Xmx3g -Dfile.encoding=UTF-8`，无地形属性覆盖。原S4完整坐标矩阵保留，未全量重跑。计时统计有并发验证调度影响，不作为受控性能A/B；拓扑与求距次数逐项一致，不承诺零成本。
- **停止于N1**：未实现连续double几何、受保护逆解或单射/分支证书；未证明无限世界；未测试共享实例并发；未启动MC；未改参数/240带/默认/生产/cache schema；未commit/push。

原始重出raw及冻结源码留在工程build/context-n0-n1（非版本保护目录），可版本保护的日志、SHA清单、CSV、重出图片已归档于本节所有链接所指目录；最终归档校验清单为[artifact-sha256.json](../../handoff/attachments/seed-context-n0-n1-20261101/artifact-sha256.json)。

## 10. N2 连续几何原语 / 导数 / 单射性（2026-11-01）

### 10.1 授权与范围

用户授权：“按方案继续做N2，先验证连续高精度几何原语、导数和单射性，别接完整高度链和新反warp，S3基准、参数、240块过渡带、默认值和生产都别动。”

因此本轮只新增三个诊断类，**不改** `RefNoise`、`RegionPartition`、`LocalBoundaryGeometry`、`NodeMorphEngine`、`RecipeMorphCatalog`、`CombineCandidate`、`UnifiedWorld`、海岸剖面与任何生产/配置源码（13个关键文件SHA与N0快照逐位相同）。

- [GeometryTransform.java](../../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/terrain/candidate/GeometryTransform.java)：warp 的 double 前向 `(x+o)·f` 与解析 Jacobian。seed/`frequency`/`gain`/`min`/`max` 全部**反射自真实分区节点**；`frequency` 用 float 倒数的**原位模式**提升（`(double)(1f/scale)`），不写 `1.0/scale`。构造期显式拒绝 `octaves != 1` 与 `warpX/warpZ` 频率不一致。
- [GeometryCellBound.java](../../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/terrain/candidate/GeometryCellBound.java)：一格内导数的 Bernstein 严格包围 + 凸包 + 顶点对配对，给 det J 的**严格下界**。
- [GeometryTransformCheck.java](../../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/terrain/candidate/GeometryTransformCheck.java)：六层判据，任一 FAIL 非零退出。

### 10.2 三条实现陷阱（都已实测踩到并修正，记录以免重犯）

1. **Hermite 曲线不能写成"同一 scale 的两项"**：`3t²−2t³` 两个系数不同（3 与 −2）。首版用 `tPow(2,3,1)` 得到 `t²−t³`，多项式式对拍 FAIL（差 4.5～4.97）。
2. **四角点积必须带角点偏置**：右/上角局部坐标是 `t−1`/`s−1`。漏掉常数项会让整格导数整体平移，det 下界出现负值。
3. **★ 噪声格 ≠ 分区格**：warp 噪声 `frequency = 1/400`，分区 `frequency = 1/600`，比值 **1.5**。一个 u 格跨 1.5 个噪声格，梯度四角**并不固定**，用 u 格做包围会漏掉真实导数（实测违反样本 `det=1.5667` 对 `bounds=[0.4025,1.3942]`）。包围一律以**噪声格**为单位，check 里 `world = 格号 / warpFrequency()`。

### 10.3 结论（分"已证 / 未证"）

**已证（本轮 2021 断言全部通过，0 失败）**：

| 判据 | 结果 |
|---|---|
| 与 float 版同源（近窗 4000 点×4 seed） | 最大 `|Δoffset|` 1.44e-4～1.66e-4（float 坐标 ULP 量级，非公式差异） |
| 解析 Jacobian vs 中心差分（h=1e-2…1e-5） | 最大 1.12e-6 |
| 多项式式 vs 解析式（**全部 4096** 角梯度组合） | 最大偏差 **0.0** |
| 全局界（覆盖平面每一点、任意 seed） | `|∂n/∂u| ≤ 5.5` → world 1.5278；`sup row-sum L ≤ 2.2222` |
| 逐格 det J 严格下界（4 seed × 121² 噪声格 = 58564 格） | 最小 **0.0154**，负值 0 格 ⇒ 这些格上 warp **局部可逆** |
| 包围严格性反证 | 2000 随机点全部落在其严格包围盒内 |
| S3 基准逐位不变 | 重出 12 个验收文件 vs 冻结 S3 **SHA 全同**（[s3-parity.json](../../handoff/attachments/geometry-transform-n2-20261101/s3-parity.json)） |

**未证（必须如实记录，不得宣传为已修）**：

- **全局单射未证**：二维 Banach 条件要求 `L < 1`，本轮严格上界 `L ≤ 2.2222 > 1`，该路线走不通。局部 det J > 0 只能给局部可逆。
- 只覆盖 4 个 seed 各 121² 噪声格；**区间外未扫描**。
- 多倍频、其他 warp 配置未覆盖（原语直接拒绝）。
- 噪声格整数哈希碰撞未做代数分析。
- 高度链与生产接线属 N3/N4，本轮未动。

因此**远坐标问题仍未修复**：`LocalBoundaryGeometry.warpInverse` 仍是旧的固定 `h=.05` 无保护牛顿，S4 的 `(1e6,1e6)` 仍是残差 7.1913 失败。N3 才有资格谈受保护反解；届时若仍无法排除多根，按方案 §4.2 应停下改路线，而不是减 warp 幅度。

复现（工程目录，`-Xmx3g -Dfile.encoding=UTF-8`，无地形属性）：

```powershell
& .\gradlew.bat --offline --no-daemon --console=plain compileDiagnosticsJava
java -cp <classpath.txt> com.geogenesis.worldgen.terrain.candidate.GeometryTransformCheck build/n2/check
```

证据与原型（独立 Python 复刻，用于先验证公式再写 Java）见[geometry-transform-n2-20261101](../../handoff/attachments/geometry-transform-n2-20261101/)，校验清单 [artifact-sha256.json](../../handoff/attachments/geometry-transform-n2-20261101/artifact-sha256.json)。

