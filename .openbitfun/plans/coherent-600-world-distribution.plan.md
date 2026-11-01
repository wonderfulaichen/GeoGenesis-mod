---
name: 600细胞与大尺度地貌分布解耦
overview: 用600细胞表达细部轮廓，用独立约1200尺度分布组织同类型区域；沿海海洋匹配浅海，按同配方区域并集的外边界生成240块过渡权重，继续使用唯一形态内核，所有运行查询必须局部确定且有界。
todos:
  - id: freeze-layout-contract
    content: 固化1200基准和独立600对照，测量真实同配方连通面积；浅海实施留待S2
    status: completed
  - id: build-coherent-layout
    content: 构建600微细胞与独立宏分布锚点，先给保持成片尺度的布局对照，不接新高度加工
    status: completed
  - id: constrain-shallow-neighbors
    content: 根据不可变基础海陆标签和真实几何关系分配近岸浅海，覆盖正边邻接及240支持带渗入；当前沙箱折线验证见§12，全域几何限制未解除
    status: completed
  - id: remove-internal-transitions
    content: 已在独立沙箱排除同配方内部边，外边单次ease权重复用现有NodeMorphEngine；S3图/检查见§13
    status: completed
  - id: verify-streaming-contract
    content: 验证有限依赖闭包、多seed、负坐标/远坐标、跨窗口/顺序/缓存及独立几何对拍，不以正典单图冒充无限世界证明
    status: pending
  - id: deliver-visual-and-cost-evidence
    content: 交付分阶段同窗口大图、圈定局部、真实剖面和成本对照，保留旧臂，等待用户验收
    status: pending
---
# 600细胞与大尺度地貌分布解耦

## 0. 本轮授权与结论

**S0～S3已由用户接受保持。当前授权S4只做流式一致性验证：跨tile/查询顺序/缓存淘汰/多seed/远坐标，发现问题先取证不修生成逻辑。S4已发现远坐标反warp实际失败与非正典完整高度链限制，证据见§14；不能判S4通过，不改参数、240带、默认或生产。**

用户目标：

1. 细胞保持600块，用于刻画更细、更不规则的类型边缘。
2. 同类型地貌连续区域的面积，保持原1200细胞版本的量级，不把所有地貌缩成四分之一面积。
3. 沿海海洋匹配浅海内部类型，不让深海内部类型直接贴陆。
4. 同配方内部细胞边界不产生地貌过渡，只在不同配方之间过渡。
5. 保留唯一节点形态引擎，保留无限世界按需生成、确定性和有限依赖要求。

**建议：改分布与权重几何，不改地形配方。** 先完成这里的布局与普通权重验证，再讨论原方案P4特殊海岸加工。此前“先选1200/600再进入P4”的建议被本轮目标取代；新实验臂固定600，旧1200仍保留为对照，尚不修改任何全局默认。

“保持1200时的量级”指连通地貌片区面积分布接近原臂，不指每块1200父区域原封不动、不指每个600单元恰好四个一组、不指所有类型在一个窗口的面积比例必须逐位相同。浅海规则会有意扩大近岸浅海占比，不能为了保旧比例把浅海再抽回深海。

## 1. 源码事实与借鉴边界

| 已核实事实 | 对本方案的影响 |
|---|---|
| 当前站点海陆以c概率和cell hash决定，海型独立hash抽槽；陆型经几何slot映射。见[RegionalMorphWorld.java:41-60](../../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/terrain/candidate/RegionalMorphWorld.java#L41-L60) | 仅减半格距不会保留原同类型片区面积，也没有浅海邻接约束。 |
| 当前先算每site距离权重，再按recipe合并。见[FullMorphLand.java:683-708](../../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/terrain/candidate/FullMorphLand.java#L683-L708)、[RegionalMorphWorld.java:129-134](../../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/terrain/candidate/RegionalMorphWorld.java#L129-L134) | 同配方已不会作为两个不同条目重复进入引擎，但其内部边仍被求距；A/A/B交汇也仍受site权重归一影响，不能把“已有归并”当成需求已经完成。 |
| 当前只有SHELF_RIDGE明确属于浅海内部类，设计e区间[-0.10,0]。见[OceanRecipeCatalog.java:60-79](../../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/terrain/candidate/OceanRecipeCatalog.java#L60-L79)、[198-210](../../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/terrain/candidate/OceanRecipeCatalog.java#L198-L210) | 第一轮沿海匹配复用陆架沙脊，不发明新浅海配方，不将枚举次序当深度梯度。 |
| reference在微板块站点采大尺度共同噪声，形成成片海陆；又用全图排序补面积。见[properties.rs:76-105](../../参考/sources/worldgen-master/src/plates/properties.rs#L76-L105) | 借鉴小单元与分布尺度分离；不照搬全图排序、面积配额和微板块全图扩张。 |
| reference通过噪声代价Dijkstra生长边界，近岸再max抬高海底。见[grow.rs:34-100](../../参考/sources/worldgen-master/src/plates/grow.rs#L34-L100)、[elevation.rs:334-401](../../参考/sources/worldgen-master/src/elevation.rs#L334-L401) | 不移植这两条运行链：前者全图依赖，后者违反本项目“类型决定高度”的接线方向。 |
| 现几何world反warp未证明单射；FullMorphLand依赖固定Gallery seed。见[LocalBoundaryGeometry.java:35-41](../../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/terrain/candidate/LocalBoundaryGeometry.java#L35-L41)、[FullMorphLand.java:123-156](../../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/terrain/candidate/FullMorphLand.java#L123-L156) | 无限世界不能只靠“有限缓存＋一张图PASS”宣布完成；需列入真实seed构建、坐标数值与几何证据工作。 |

既有[尺度实验](regional-cell-half-scale.plan.md#L43)记录A1200水面29.571974%、多配方35.127912%；B独立600水面30.394166%、多配方61.543274%。这些是旧实验测量值，不是本方案的新产出或强制目标值。

## 2. 唯一目标管线

```text
世界seed + 稳定细胞键
  ├─ 600微细胞几何（现有NATURAL、抖动与warp不变）
  └─ 独立宏分布场（初始锚点尺度约1200，不参与高度合成）
        → 基础Side0（陆/海）与基础配方Recipe0
        → 查询不可变Side0的局部几何：近岸海细胞改选已有浅海配方
        → 最终RecipeKey（配方id + 真正的seed/参数身份）
        → 同RecipeKey细胞的区域并集：仅保留异配方外边界
        → 每个有效配方一个有符号距离 → progress一次 → normalize一次
        → 现有NodeMorphEngine + 现有陆地覆盖语义
        → e → HeightCurve → 真实水陆分类/渲染
```

c仍只参与分布，不乘最终高度；海岸不是第三个内部票槽池。普通带仍为总宽240、单侧120。现有配方raw/算子/幅度/bias/频率/族seed偏移/HeightCurve均冻结，水文与特殊海岸加工不在本次范围。

## 3. 600微细胞与约1200宏分布独立

### 3.1 推荐的第一实现：无限锚点场，而非逐细胞随机或单一噪声分档

在现有600微细胞之上设置**独立、确定性的抖动宏锚点场**，初始锚点格距L=1200块。它只表示“这一带偏向哪个内部配方”，不产生高度、不创建第二套地形引擎。

- 锚点在无限规则索引上按独立seed salt抖动，具有稳定键和固定世界标称坐标。
- 微细胞用其抖动站点的标称坐标 `q=(siteU*600, siteV*600)` 查询宏分布赢家，所有微细胞共享同一个分布场；不以tile中心、查询像素、缓存内容决定身份。
- 使用q是明确的分布坐标约定：它是warp空间换算后的块单位，不是反warp求出的真实站点world根。宏c在固定锚点标称坐标采样；场尺度/参数沿用，但布局相位可能相对旧臂变化。选型因此无需反warp，避免一个微细胞因不同求根初值获得不同配方。world几何仍使用原warp，不顺手改幅度。
- 宏赢家只决定离散类型标签，不把最近邻配对距离、宏区id或边缘程度混入高度。先使用现有NATURAL度量与抖动规则，无新增边界扭曲参数。
- 一个宏锚点的配方被附近多个600微细胞共享；外轮廓仍是这些600细胞的并集，不直接拿1200宏胞几何当最终地貌边界。
- 锚点seed、海陆抽签、陆型槽、海型槽使用互相隔离的派生键，不能推进已有配方构造游标。锚点查询用势函数上界排除外部候选，禁止未经证明固定取最近两站点。

**为何不直接复用一个低频标量去查45个打散槽？** 那会把同一噪声值域切成很多窄段，相关长度不等于最终每种配方的成片尺度，且会偏置类型概率。宏锚点先决定地貌片区、再独立抽其配方，更容易让片区面积与微细胞尺寸解耦。

**为何不是固定父子四格？** 微细胞站点和宏锚点各自抖动，按空间关系归属，不是`floor(cell/2)`继承一个固定2×2父格。相邻宏锚点抽到同配方也能自然连片；宏区只是分布支持，不产生额外过渡。

### 3.2 海陆与配方分配

每个宏锚点先沿用现有c场与seaProbability语义，抽取Side0；再按既有陆地45槽映射或海洋槽表抽Recipe0。同一片宏分布支持内的微细胞共享结果，不再各自重抽海陆或配方。既有槽表及族参数不变；抽签身份从微细胞迁到宏锚点是本轮**明确的分布规则变化**，不声称与A1200逐位同图。

不同宏区抽到相同完整RecipeKey时，无需保留宏区边界。复合低地配方仍是原完整条目，不拆成两个内部区域。未来允许同名异参数时，身份比较不能只用“大类名称”。

### 3.3 面积目标与校准

L=1200是初始设计值，**不是已证明最终连通面积一定相等**。微细胞离散化、相邻同配方合并、海陆相关性都会改变连片分布。

先从冻结A1200统计：原始赢家RecipeKey区域的真实邻接连通分量面积、等效线尺度sqrt(area)、数目/分位数/每类型占比。以正长度共边为连通，角点相触不算连通。不要使用过渡后dominant图、最终高度或整个类型的总像素数代替连片面积。

新臂重点比较：同配方连片的P50/P75、面积加权分位数、单600细胞孤岛比例、边界周长与形状；有限视窗截断分量单列，不伪装成完整面积。先做相同窗口，再用固定扩展窗口确认，不用全图统计反向决定运行标签。

验收意图是**面积接近A1200量级而非B独立600的约四分之一单元尺度**，外轮廓能看出多个小单元组成、不是一个放大的圆斑。具体面积比例容差需由基线统计与图像共同确认，不预先捏造PASS阈值。若L=1200未达到目标，停在布局阶段；仅提出调整这一独立分布尺度，不改600格距、不缩240带、不调配方参数，不扫密集梯度。

浅海受位置约束，其连片形状天然偏沿岸长条，单独统计；不要求每段浅海都满足普通内陆片区的面积分位数。

## 4. 沿海匹配浅海：有限、无反馈的分配规则

### 4.1 明确“沿海”的两个口径

- **运行匹配规则**：不可变基础陆型域附近的海型细胞必须选浅海。第一轮保守覆盖所有陆型，不依赖未来P4岸型标签，保证不会漏掉任何沿海陆型。
- **最终水边验收**：仍由最终e/高度穿海平面确定。分布标签不是海岸线；新权重导致的离岸水面、狭窄岛体等要单独看图/剖面，不能把类型邻接PASS当作所有真实水边问题已解决。

### 4.2 推荐规则：直接邻接必浅海，并覆盖正权重支持带

定义600细胞真实区域为Ci，Side0在分配后不再变化。对每个基础海细胞i：

1. 如果它与任一基础陆细胞有正长度共边，最终配方必须为已有`SHELF_RIDGE`。
2. 为防三域顶点或薄浅海区使深海权重仍参与陆海混合，进一步检查：`distance(Ci, 基础陆型域) <= 2*120 + 数值安全余量`时，也选浅海。
3. 其余海细胞沿用宏分布Recipe0（原本为SHELF_RIDGE者可保留）；陆细胞不变，不为了腾出浅海面积把陆型改为海型。

第二条按**细胞区域到陆型域**距离，不是站点到站点距离，不是固定1格宽，不是c阈值圈一层海。240来自现有两个正权重120支持半带的安全隔离，而非新增随意的地貌参数；数值余量必须来自几何误差与坐标精度预算，不能拍一个大值盖住问题。

这会让近岸整颗海细胞换成浅海，因此浅海带实际宽度随细胞形状变化，并非严格240块宽的岸线环带。它只改变类型选择，不用max抬升海床，也不新增浅海配方。SHELF_RIDGE之外的洋中脊/海山/峡谷不能因局部较浅被随意认作浅海类。

### 4.3 为什么不会无限递归

```text
finalRecipe(i)
  → baseChoice(i)
  → completeCellGeometry(i)及其有限扩展域
  → baseChoice(j)（只读Side0，不读取finalRecipe(j)）
```

不调用“邻居最终世界生成”，不因邻居换浅海再次生长陆地，不反复松弛到稳定，不追踪整条海岸、不求连通域全尺寸。最终标签只依赖固定范围的基础标签与几何。窄海峡可能整个变浅，这是规则的预期后果，需出局部图，不按遍历顺序保留一侧深海。

**类型占比变化须诚实记录**：浅海优先约束后，原海洋票槽权重只表示无约束候选分配，不再代表最终全海域比例。不能再用“仍沿用票槽”宣称最终比例完全没变。

## 5. 同类型内部不做过渡：按配方并集的外边界求距

### 5.1 去掉的是语义内部边，不是原几何拓扑

先有完整微细胞几何和最终RecipeKey，再标记每条真实边：

- 两侧RecipeKey相同：内部边，不参与地貌过渡距离、权重归一与过渡计数。
- 两侧不同：保留为语义外边，构建按配方索引的线段bbox查询。
- 原微细胞拓扑仍可用于赢家查询、浅海判定和诊断；不能在浅海标签确定之前随意删几何边。

原始边拓扑先生成；能在world折线加密前安全过滤同配方边时再减少建场成本。第一版不承诺能省掉全部原始Voronoi几何工作。

### 5.2 权重定义

对完整配方键r，定义区域并集`Omega_r = union(Ci | finalKey(i)=r)`，边界`B_r`只包含r与其他配方相接的外边。

```text
sd_r(x) = +distance(x, B_r)，若原始细胞赢家属于r
          -distance(x, B_r)，否则
p_r(x)  = progress(sd_r(x))  // 原单次smoothstep，±120饱和
w_r(x)  = p_r(x) / sum(p)
```

只需枚举x周围120块内所有有效外边；r边界不在支持范围内时按符号饱和为1或0，不必知道这一整片同类型区域延伸多远。相同r的多个断开区域仍是同一配方域的并集，不通过全图flood fill获得运行id。

- 纯内部只留下一个r，权重精确onehot=1，继续走现有引擎端点；只是不算“自己到自己”的过渡，配方自身噪声仍照常求值。
- A/A内部接缝若靠近B，允许A↔B真实过渡延伸到接缝处；不能因为当前两格相同就提前return A而掐断第三类型。
- 双类型、三/多类型都把所有正权重配方交同一个NodeMorphEngine，不取最近两类，不逐边覆盖成品高度。
- 两片A被窄B夹开时，仍保留A/B外边；不能把B孔洞填掉或因A同名跨过B建立一条假内部边。

**这不是旧site归并权重的逐位等价优化。** 旧做法是“各细胞先progress、归一，再加同类权重”；新做法是“同类先形成区域并集，再对外边progress、归一”。尤其A/A/B顶点、窄带的权重会有意变化。必须用独立并集几何基准证明语义正确，不能拿旧算法结果当唯一真值，也不能以性能优化名义隐瞒产出变化。

## 6. 无限世界：有限依赖闭包与当前阻塞项

### 6.1 有界候选与halo

不硬猜一个邻居格数、不沿同类片区无限扩张。现有抖动NATURAL格可推导统一的粗界：对任意u点，同索引格站点每轴差不超过1，势不超过4；赢家到该点距离不超过2个u单位。因此一个600细胞相对其标称站点的warp空间覆盖半径不超过1200块；再加world warp位移向量上界与浮点误差得到`R_cell`保守world界。

实施时须把这一推导和实际表值/频率/坐标精度写成可测试的枚举证书，不把此处数学粗界直接冒充当前数值world折线的误差证明。

- 一般tile：查询域外扩120，完整枚举所有可影响权重的微细胞与异配方边。
- 对浅海分配：构建覆盖目标完整细胞及其240安全扩展的局部基础几何；`R_cell + 240 + 误差余量`提供固定查询域，再由现有势函数排除界获得有限候选集。
- 对这批候选的基础选型仅查询有界宏锚点，不再触发邻区最终选型/高度；依赖层数固定。
- tile/图片边框绝不能作为地貌边或海陆邻接。支持范围内的截断边意味着halo不足，应FAIL，不能简单丢弃以得到PASS。

### 6.2 seed、坐标和缓存不能留空

1. 从真实world seed构造分区、覆盖mask和原陆地覆盖族；保留既有seed偏移与运算语义。现有FullMorphLand的Gallery seed守卫不能简单删除：须抽出可由任意seed真实构造的上下文，正典seed与冻结对象逐位对拍后才接新臂。旧构造和旧臂继续可复现，不复制一套高度配方。
2. 宏/微身份与并列赢家按稳定cell key排序裁决；固定坐标约定，无共享随机游标，无“第一个访问者写身份”。
3. 缓存仅加速，不参与定义；新路径的站点/宏锚点/类型边缓存必须有界或随tile生命周期淘汰。当前`choices`为无淘汰Map，不能原样当成无限探索内存契约。
4. 负坐标使用floor/floorDiv；验证支持的Minecraft世界坐标范围。现有float前向warp/反解在远坐标精度与多根问题仍在，必须显式探测，不把近原点PASS当全域证明。
5. 反warp多根、world折线漏分支或远坐标误差若影响正确性，停在沙箱并给反例；需要单独几何修正方案，不擅自关warp、改地形参数或提高阈值掩盖。此次新分布身份不用反warp，并不等于原几何问题已经解决。

## 7. 改动范围与不改事项

实施候选点（获准后先全仓检索调用，再编辑）：

| 模块 | 责任 |
|---|---|
| `RegionPartition` / `LocalBoundaryGeometry` | 复用600实例与真实微几何；增加完整细胞/有限邻接支持、候选证书，旧默认入口不变。 |
| 独立分布模块（建议`CoherentRegionDistribution`，最终文件名实施时核对） | 宏锚点、基础海陆与配方、无递归浅海约束；不采成品高度，不调用NodeMorphEngine。 |
| 语义边权重模块（建议`RecipeBoundaryWeights`） | 最终标签过滤外边、区域并集距离与连续权重、支持完整性；不复制形态求值。 |
| `RegionalMorphWorld`与显式seed上下文 | 接分布/权重，复用现有evaluate和旧陆覆盖语义；新实验入口隔离，缓存不定义结果。 |
| Gallery / Check / 现有renderer | 分阶段出原始标签、外边掩码、权重、e/Y和证据；渲染不重造高度。 |

不改：18陆地＋5海洋内部配方、海岸特殊剖面、普通240带、现有warp参数、HeightCurve、水文、方块/物品、生产注册、全局默认和缓存schema。未来获准接生产时才另行升级预览schema并跑生产门禁。

## 8. 分阶段实施与停止点

### S0：先冻结可复现基准，不生成新世界

保留A1200（高度SHA `7941b81484200c5d99b96b212b9c15b9a5dcb361ca2aedc763f379b04aca3803`）和已出独立600臂（SHA `033745573517406e8165fd3da839ad7a5a39d31c7ac0295e23af2ab8e066cc15`）。核对源码/seed/JVM属性，采原始赢家标签与正边邻接，测连通面积；不把旧图重跑覆盖原raw。先完成多seed上下文与布局查询界的最小验证。

### S1：只验证成片分布

600几何＋约1200宏分布，暂不启用浅海修正与新权重。先出A1200/B独立600/C成片600的同窗口原始类型图、边界叠加与面积报告。重点看同类成片是否仍有原尺度、边缘是否由多个小单元表达、稀有配方是否被吞没。不合目标停在此处，不先做特殊海岸或高度调参。

### S2：同一C布局增加浅海约束

只改变海细胞最终配方，保留Side0/陆型逐位不变。给修正前后同坐标布局与实际邻接报告，附近岸替换mask；验证陆型↔非浅海正边邻接数为0，区域距离安全带不漏三域顶点。报告浅海占比、窄海峡变浅及远洋保留，不为达海陆面积配额回调参数。

### S3：同一最终布局改用配方并集外边权重

对照“旧site权重归并”与“外边权重”，单独归因权重变化；接原NodeMorphEngine导出高度。无第三类型支持时，同类内部应onehot，内部边过渡mask=0；有异类支持时允许必要过渡。检查240宽度、双域端点、多域/孔洞/薄条、浮点极小海权重、海底负e。用独立局部栅格/多边形并集距离对拍，不让实现自证。

### S4：流式一致性、真实大图与成本

同点跨tile/不同窗口/正逆顺序/冷缓存/淘汰重建/并发、多seed/负坐标/远坐标验证；独立枚举比小窗口更大的支持域，检查漏候选。比较身份须逐位；world数值几何按既定误差与收敛判据，不提高旧阈值迁就新臂。

每阶段可独立回滚，先给阶段图再决定是否继续；本文件获准不等于生产迁移授权。最终效果仍由用户看图验收。

## 9. 有牙齿的验收与交付

| 主题 | 检查与反例 |
|---|---|
| 解耦 | 微尺度600固定，改变分布尺度不会改变微细胞几何SHA；调换遍历顺序不会改变站点配方。独立600随机分配为碎片化对照。 |
| 成片量级 | 比较A1200真实同配方连通面积分布及形状，不只看总占比；记录边框截断与无样本。固定2×2父格继承不能作为自然外轮廓验收通过的证据。 |
| 浅海 | 正边邻接及120支持区内陆/深海共同正权重均不得漏；含三域顶点、窄地峡、微小海细胞。显式关闭浅海规则时旧深海邻陆样例必须能被检测到。 |
| 同类免过渡 | 全A多细胞onehot对拍原配方；A/A/B与A/B/A孔洞等反例证明内部边被去除、异类边不丢。人为保留A/A内部边时语义边掩码检查必须FAIL。 |
| 高度契约 | 23端点、负e、既有覆盖只贡献陆侧、一次ease/归一、有限合法数值；节点内核不变，禁止成品高度lerp替代。 |
| 无限世界 | 给出依赖DAG、实际候选上界/查询半径；无全图排序/配额/递归生长。缓存开关不改结果，压力探索缓存受界。单seed单图不是充分证据。 |
| 几何 | 候选与更大穷举域对照、真边/帧边区分、反解残差/密度对拍与远坐标误差；多根未证须明列，不能写成数学保证。 |

交付固定seed=`9139912035078620160`、样点原点=(-3642.5,-3365.5)、1401²、步长5（7000块样点跨度／7005像素覆盖），JVM属性完整列明。提供全幅同坐标并排、用户圈定左上局部、原始类型与外边掩码、六条固定全幅与三条局部逐块真实剖面；纵轴包含世界384格范围、局部另给共用放大纵轴，不由Python合成高度。

面积统计/标签多seed检查可轻量先跑，不先重复历史七臂整图链或无关水文门禁。相关旧端点、构造链回归必须跑；失败进程返回非零。图像最终判据高于“数字达到阈值”。

性能拆开记：宏分布/浅海建场、原始几何、外边线段数与求距次数、内部onehot比例、引擎活跃配方数、冷/暖tile/采样耗时与缓存峰值。同一布局下对比过滤内部边前后，才可归因优化收益；不拿变换类型布局后的总耗时直接归因删边。600和浅海查询增加建场成本，同类成片与去内边减少采样工作，**净收益待实测，不承诺零开销或实机FPS**。

## 10. 批准本方案时需要明确的语义

推荐采用以下边界（已写入上文，不隐藏额外功能）：

- 600是新沙箱微细胞尺度，宏分布初值1200，目标是片区面积量级而非逐片保形；旧臂和全局默认不变。
- 近岸匹配第一轮只用已有陆架沙脊，并保守覆盖所有基础陆型附近海细胞，浅海份额允许因此改变。
- “同类”是完整RecipeKey，不是大类标签；先并集后求距会改变交汇权重，不声称逐位无损优化。
- 特殊海岸、生产落地不在本次实施；若想要更多浅海地貌或严格保持原海洋占比，应另行设计，不顺手添加。

**当前状态（2026-11-01更新）：S0/S1成片面积由用户接受保持；S2浅海匹配已获准并实施，详见§12。S3内部免过渡、新高度接线与生产迁移未实施；S4完整流式门禁仍未完成。**

## 11. S0/S1本轮实测

### 实施边界与源码

新增3个独立诊断类：[CoherentRegionDistribution.java](../../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/terrain/candidate/CoherentRegionDistribution.java)、[CoherentLayoutGallery.java](../../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/terrain/candidate/CoherentLayoutGallery.java)、[CoherentLayoutCheck.java](../../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/terrain/candidate/CoherentLayoutCheck.java)，及[render-coherent-layout.py](../../tools/render-coherent-layout.py)。本轮冻结的104份既有candidate源码逐字节未变；生产无差异、配置/默认/配方/240带/warp/HeightCurve不动。

宏锚点格距1200；微站点标称坐标=siteU/V×600；独立salt为base+1700000/1710000/1720000/1730000，分别用于宏几何/海陆/陆槽/海槽。c仍为原4000/6与seaProbability。该salt在单次实验前确定，未按图片/面积挑seed或调参。新分布无缓存、无全图配额、无邻居最终结果依赖；有限宏候选界由同格站点势上界推导。

显示数据来自真实LocalBoundaryGeometry赢家和原始选型，不计算height，不进行浅海替换或同类边过滤。Gallery正典几何复用现有构建链；新增microPartition支持真实seed并做正典逐位对拍。完整任意seed覆盖mask/FullMorphLand迁移仍未做，本轮仅布局上下文，不绕过既有Gallery守卫冒充整世界多seed完成。

### 交付

- [A1200与C成片600全幅](../../handoff/attachments/coherent-layout-s1-20261101/pair-a-c-full.png)、[同坐标圈定局部](../../handoff/attachments/coherent-layout-s1-20261101/pair-a-c-local.png)。
- [A/B/C三臂全幅](../../handoff/attachments/coherent-layout-s1-20261101/triptych-types-full.png)、[三臂局部](../../handoff/attachments/coherent-layout-s1-20261101/triptych-types-local.png)。
- [全部微细胞边叠加](../../handoff/attachments/coherent-layout-s1-20261101/pair-a-c-boundaries.png)、[局部边叠加](../../handoff/attachments/coherent-layout-s1-20261101/pair-a-c-boundaries-local.png)：黑线仅是诊断显示，S3没接，不能解释成游戏中会有同类接缝。
- [统计/分类型/源码SHA/图像校验](../../handoff/attachments/coherent-layout-s1-20261101/metrics.json)、[布局相关检查](../../handoff/attachments/coherent-layout-s1-20261101/check.txt)。逐组件CSV、真边邻接、站点/宏归属CSV、元数据和源码哈希同目录归档。

主图：seed9139912035078620160，原点(-3642.5,-3365.5)，1401²、step5，左上局部像素框[0,0,620,660)。扩展统计窗同中心，原点(-7142.5,-6865.5)，1401²、step10，样点跨度14000块。两窗JVM属性都仅file.encoding=UTF-8，导出Xmx5g。两种采样步长是统计覆盖不同，不是更改细胞尺度。

### 连片面积（平方块）

连通性=窗口内正长度真边两侧同RecipeKey的DSU，而非栅格4/8邻接。零像素几何节点仍可连接组件，但halo-only无面积组不进入分位数。面积以原始赢家栅格像素数×step²近似；“完整”仅指未触及栅格边的组件，边框接触判定有采样误差，不称精确解析面积或全域连通性证明。每类型分布、截断与零像素组分别记录。

| 窗口/指标 | A原1200 | B独立600 | C成片600＋宏1200 |
|---|---:|---:|---:|
| 主窗未触边组件数 | 15 | 71 | 14 |
| 主窗未触边P50 | 1362100 | 386850 | 1385187.5 |
| 主窗未触边P75 | 1560425 | 557950 | 1785487.5 |
| 扩展窗未触边组件数 | 85 | 358 | 79 |
| 扩展窗未触边P50 | 1504200 | 379200 | 1524400 |
| 扩展窗未触边P75 | 1694900 | 463575 | 1986700 |
| 扩展窗面积加权P50 | 1595900 | 433300 | 1679700 |
| 扩展窗全部有面积组件数（含截断） | 139 | 460 | 133 |
| 扩展窗全部组件面积P50（含截断） | 1366000 | 360600 | 1347400 |
| 扩展窗单600细胞孤立片区面积份额 | 不适用 | 60.8842% | 0.7474% |

C/A未触边面积P50主窗1.017倍、扩展窗1.013倍；扩展P75约1.172倍。**两个固定窗口均支持“恢复原1200的成片面积量级”，不再是独立600的约四分之一。** 没有以这些实测数值临时设阈值包装成质量PASS；外轮廓是否合意仍等用户看图。

主窗C可见50个宏锚点，单锚点可见微细胞数P25/P50/P75=2/3/4、最大7，非固定2×2父格继承；同类还可跨宏锚点合并。原始海洋类型像素份额A29.4110%、B29.9459%、C25.1569%，**不是最终水面占比**，布局本就不是旧图保形重放。

稀有类型：主窗C缺1/2/13/19/21，共18类；扩大窗口C有22类，仍缺1(STEPPE)。A/B扩展窗有23类。不能把局部缺失直接断言成配方不可达；当前槽映射保留可达性，但尚未证明其在无限空间或多seed上的面积频率，不擅自补洞/重抽以强凑23类。

### 相关验证与限制

- 原A1200 raw winner类型逐位等于P3 type-hard.u8，SHA=cfeb1d8a8508eae41c7d1ea4e42c51c9419b29d8bcd6fc59aa884f0443cac474；不要拿过渡后dominant图当这一基准。
- 主/扩展窗B与C的cells.i64be分别逐位相同；三臂两窗共有701²采样点的cell与recipe逐位一致。
- 宏有限候选与向外多8格穷举：4seed×1000点、坐标±3000万，全部相同，实际候选最大16；这只验证宏分布数值，不证明远坐标world反warp精度。
- 显式seed分区与正典原几何800点赢家/站点逐位相同，边拓扑数量相同；64组跨窗口独立逆序站点身份一致。
- Gradle compileDiagnosticsJava成功；无src/test导入这些新类。Python AST/函数长度通过；DSU零像素桥接正控、无边断开负控、halo-only不进分位数检查通过。9张图均逐像素验证原生面板未缩放。
- 不运行高度/剖面或原七臂/水文全门禁：本轮只动新布局类，不改高度内核与旧源。未测新布局的游戏地形、帧率、完整S4并发缓存与反warp单射；不以4000点/9张图替代无限世界严格证明。

**停止点：S0/S1布局已交付，等待用户确认外轮廓；S2、S3继续保持未实施。**

### 本地复现

mdk目录Java21/target17；已有baseline依赖与cell-scale-ab/classes必须存在。新类位于新输出目录最前，不覆盖原类：

```powershell
$java='D:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot/bin'
$r='build/coherent-layout-s1'; $base='build/terrain-upgrade/baseline'
$jars=(Get-Content "$base/classpath-jars.txt" -Raw).Trim()
$cp="$r/classes;build/cell-scale-ab/classes;$base/classes;$base/src-main/resources;$jars"
$src='src/diagnostics/java/com/geogenesis/worldgen/terrain/candidate'
New-Item -ItemType Directory -Force "$r/classes" | Out-Null
& "$java/javac.exe" --release 17 -encoding UTF-8 -cp $cp -d "$r/classes" "$src/CoherentRegionDistribution.java" "$src/CoherentLayoutGallery.java" "$src/CoherentLayoutCheck.java"
if ($LASTEXITCODE) { throw 'compile failed' }
& "$java/java.exe" -Xmx5g '-Dfile.encoding=UTF-8' -cp $cp com.geogenesis.worldgen.terrain.candidate.CoherentLayoutCheck "$r/check"
if ($LASTEXITCODE) { throw 'check failed' }
& "$java/java.exe" -Xmx5g '-Dfile.encoding=UTF-8' -cp $cp com.geogenesis.worldgen.terrain.candidate.CoherentLayoutGallery "$r/data"
if ($LASTEXITCODE) { throw 'export failed' }
```

仓库根：

```powershell
python -B tools/render-coherent-layout.py forge-1.20.1-47.4.10-mdk/build/coherent-layout-s1/data handoff/attachments/coherent-layout-s1-20261101
```

原raw/冻结源与构建类路径在忽略的build/coherent-layout-s1；主窗/扩展raw本轮未归档整份二进制，只归档哈希、标签站点/真边和组件证据，复现依赖仍需本地已有基准资源。

## 12. S2沿海浅海匹配：本轮实施与实测

用户接受S1成片面积保持后，明确批准：仅在当前C布局上改变海洋类型分配，出同窗口全幅与圈定局部；不接S3，不改地形参数、默认或生产。

### 实现

新增诊断侧ShallowRegionMatcher、CoherentShallowGallery、CoherentShallowCheck与render-coherent-shallow.py。冻结的107份既有candidate源码全部逐字节不变，src/main无Git差异。细胞600、宏锚点1200、原分布与地形配方不动。

匹配器仅查基础Side0：目标完整细胞保守盒外扩240和数值余量，使用固定1024 tile内裁切的真实world边折线拼接细胞边界；到基础陆海边界距离≤240+余量时，海洋标签替换为既有22陆架沙脊。无最终邻居递归、无全图排序或高度抬升。patch缓存上限128。盒框不当真边；截断端点进入负责tile时抛错，而非忽略支持域缺口。

数值余量按chord、反解停止容差预算及float ULP组合，主图约0.08块；**它不是已证明的反warp世界误差严格上界**。Infinity只表示在查询阈值以外，不表示数学距离无穷。

### 同窗口结果

主窗/seed/ROI沿用§11；仅file.encoding=UTF-8这一JVM属性，Xmx5g。C输入标签SHA仍为5680442dcec0000952d767db86898d50fa2befb0acc56ed12e903c8bbfec2937；细胞图SHA仍为0358f77326e362146afa132c5525c2da302637d96e0e25486359c56c883eed08。S2标签SHA为5096a6656493edfab6000029b592d8151077bab37ff84c36805d691d1113805c。

| 指标 | 匹配前 | 匹配后 |
|---|---:|---:|
| 陆型直接邻接非浅海型的去重真边对 | 57 | 0 |
| 浅海22像素 | 93993 | 443698 |
| 浅海占海洋标签像素 | 19.0354% | 89.8574% |
| 海洋标签像素 | 493780 | 493780 |
| 深海平原像素 | 271252 | 22168 |
| 海山群像素 | 128535 | 27914 |

165个导出细胞记录中27个改选；349705像素变化，占全图17.8166%。所有陆地标签和海陆Side0逐像素不变，海洋标签面积仍25.1569%，**不是水面面积**。本窗原先没有19洋中脊和21峡谷，不声称这两类经过S2后的全域频率已验证。整细胞替换使狭窄海域大片变浅，浅海在此沿岸窗口达到约90%；这是需要用户看图判断的实际后果，不反调配额或缩小规则。

### 相关检查及限制

- Gradle compileDiagnosticsJava成功；Java21运行、target17。新增Check退出0。
- 165个细胞由独立冷实例逆序重算，全部匹配；实际构建79个patch，缓存79≤128，所有参与patch截断端点守门通过。没有做缓存淘汰/并发或多seed全世界验收。
- 全部32个非浅海基础海细胞，以chord=.005/maxSeg=1对拍默认.02/2，最终判定全部一致，包含未变远端样本；非共边却应改浅海的两个实际距离为13.01697与24.18395块。没有本窗恰好240附近的自然样本，不冒充阈值临界收敛证明。
- 线段平行239/241、交叉与端点接触正负控制通过；冻结C关闭规则时57条坏邻接作为非空反证，S2后为0。
- 独立采用既有S1扩展窗10块赢家栅格，scipy EDT计算到陆型像素距离：32海细胞中，距离<220却未替换、距离>270却替换的反例均0。220/270只是排除栅格边界采样误差的诊断带，不是修改运行240阈值；该检查是独立近似交叉验证，不是精确区域距离证书。
- Python AST通过；6张PNG逐像素验证原生面板，无缩放；全幅及局部已目检。
- 既有反warp单射/分支完整性、远坐标float、任意seed高度上下文、S4流式门禁仍未解决。**S2仅在当前沙箱world折线近似上验收布局，不宣布无限世界或实际海岸线问题全部解决。** 未求高度/剖面、不启动游戏、不重跑无关水文/七臂；本轮没有S3性能收益结论。

### 交付与复现

[全幅前后图](../../handoff/attachments/coherent-shallow-s2-20261101/pair-types-full.png)、[圈定局部](../../handoff/attachments/coherent-shallow-s2-20261101/pair-types-local.png)、[替换mask](../../handoff/attachments/coherent-shallow-s2-20261101/changed-mask.png)。同目录归档metrics.json、check.txt、独立距离审计、站点/真边CSV、元数据与冻结SHA。

mdk目录，沿用§11的java/base/jars/src变量：

```powershell
$r='build/coherent-shallow-s2'
$cp="$r/classes;build/coherent-layout-s1/classes;build/cell-scale-ab/classes;$base/classes;$base/src-main/resources;$jars"
& "$java/javac.exe" --release 17 -encoding UTF-8 -cp $cp -d "$r/classes" "$src/ShallowRegionMatcher.java" "$src/CoherentShallowGallery.java" "$src/CoherentShallowCheck.java"
if ($LASTEXITCODE) { throw 'compile failed' }
& "$java/java.exe" -Xmx5g '-Dfile.encoding=UTF-8' -cp $cp com.geogenesis.worldgen.terrain.candidate.CoherentShallowGallery "$r/frozen-c" "$r/data"
if ($LASTEXITCODE) { throw 'export failed' }
& "$java/java.exe" -Xmx5g '-Dfile.encoding=UTF-8' -cp $cp com.geogenesis.worldgen.terrain.candidate.CoherentShallowCheck "$r/data"
if ($LASTEXITCODE) { throw 'check failed' }
```

仓库根运行renderer，输出目录必须尚不存在，以免覆盖旧证据：

```powershell
python -B tools/render-coherent-shallow.py forge-1.20.1-47.4.10-mdk/build/coherent-shallow-s2/data handoff/attachments/coherent-shallow-s2-reproduce
```

**S2停止点（历史）：用户已接受浅海匹配保持，并授权S3；S3当前交付见下一节。特殊海岸和生产继续不动。**

## 13. S3同类型内部免过渡：同布局地形对照

### 授权与实现

用户明确：继续S3、复用统一形态引擎，出接入前后同窗口地形大图、圈定局部和剖面；地形参数、240带、默认不变，不接生产。

仅新增4个诊断类RecipeBoundaryWeights、CoherentMorphWorld、CoherentMorphGallery、CoherentMorphCheck，及render-coherent-morph.py。110份冻结既有candidate源码全部逐字节不变，src/main无Git差异。没有改S2 matcher/基础分布、配方、HeightCurve、默认或缓存schema。

两臂都用同一600几何、同一S2最终recipe：A=旧site.weights→按recipe归并；B=去除相同recipe内部边→各recipe外边最近距离→符号由原始赢家确定→原单次progress→归一。空间120块格桶只加速完整候选，不取最近两类。没有创建第二份地形算子：两臂都调用既有RegionalMorphWorld.evaluate，复用NodeMorphEngine和陆地覆盖增量语义。原始几何仍先全部建成，S3省的是后续内部边求距，不声称已省全部建场。

新沙箱tile LRU8、最终身份缓存2048、S2 patch缓存128；缓存不定义标签。仍受正典Gallery seed与既有反warp/远坐标限制，未解除S4阻塞。

### 同窗口图与剖面

[全幅前后地形](../../handoff/attachments/coherent-morph-s3-20261101/pair-full.png)、[左上圈定局部](../../handoff/attachments/coherent-morph-s3-20261101/pair-local-height.png)、[6条全幅剖面](../../handoff/attachments/coherent-morph-s3-20261101/profiles-full.png)、[3条局部剖面](../../handoff/attachments/coherent-morph-s3-20261101/profiles-local.png)。另有主导类型和活跃支持集对照、两臂1401²原生高度，共10张图。

口径固定：seed9139912035078620160，1401² step5 origin[-3642.5,-3365.5]，ROI[0,0,620,660)，仅file.encoding=UTF-8属性，Xmx6g。50109点剖面由Java实际入口逐块采样，不从Python栅格插值高度；完整剖面纵轴[-64,320]，局部共用实测范围，0裁切。脚本复用原shade/grid/profile helper，仅修正本机旧Pillow文本锚点的图框外刻度排版，不改变曲线或旧renderer。

Gallery逐像素验证cell key与S2细胞图相同、winner recipe与S2 after.u8相同；winner SHA=5096a6656493edfab6000029b592d8151077bab37ff84c36805d691d1113805c。A高度SHA=dd74f4a0ab03a0190a5153f164047c02cc0eb47be023d103a2ae31a0580c2dd1，B=af207c94935c86b55ddd00e7155a4b375bfa2f1254a227c10811960cefafdc29。A是首次在S2布局上导出的旧权重地形，不是历史P3高度基准。

| 全幅指标 | A旧权重 | B配方并集外边 |
|---|---:|---:|
| 真实高度水面率(Y<63) | 22.39458% | 22.37033% |
| 纯配方像素比例 | 62.06472% | 62.06472% |
| 活跃配方均值/最大 | 1.425721/4 | 1.425721/4 |

active图逐位相同，不宣称纯度提高：旧实现本来已归并同类，S3改变的是交汇处权重及内部求距。1218207个纯配方像素高度全部逐位不变。全幅279100像素高度数值变化，其中>0.5块者占6.83197%；P95绝对差1.08179、P99=5.92163、栅格最大31.43990块；逐块剖面捕捉到最大32.22080块。主导配方变化15565像素(0.7930%)，而原始赢家标签始终不变。不能把主导变化说成分布被改。

### 有牙齿的相关验证

主代理最终执行Gradle compileDiagnosticsJava成功、新CoherentMorphCheck退出0；NodeMorphEngineCheck全套相关端点/混合检查成功(endpointMax0、failures0)，未重跑无关水文或七臂大图。

- 实际全同配方几何移除全部内部段，精确onehot；A/A/B保留B支持，A/B/A窄带和孔洞不会把A重复投票或抹掉B，三域均保留。
- 240带961点独立单ease公式最大误差2.22e-16；二次ease负控462点可分辨，±120饱和未改。
- 独立Line2D全线段穷举vs空间索引1200点(897负坐标)，最大权重差5.31e-14。
- 62组强制相邻tile同点，最大新权重差2.70716e-6、最大e差1.78814e-7，既定2e-3阈值不变；geometry.query独立归并旧权重最大差1.11e-16，171实际点外边独立穷举差7.77e-16，两臂evaluate重放误差0。
- 真盒框端点55个距负责tile最近479.57块，未进入120支持；31处真实同类内部边附近仍保留第三类型支持，101处真实onehot逐位等hardE。16个远异类内部边样本新旧均onehot，诚实保留。
- 冻结S2 165站点逆序身份全一致；负e60例/tinysea36例均通过；所有栅格与剖面数值有限。
- 独立以冻结S2赢家栅格按recipe分别求scipy EDT，并用字面smoothstep/normalize，对拍243实际点(81混合)，最大权重差0.02460、P95=0.01413；这是5块栅格离散边界的近似交叉验证，不是解析几何误差证书，不修改运行带宽/跨tile容差。
- 10图原生面板逐像素校验，50109剖面点step1/有限/无裁切。raw/check/源码哈希/CSV同目录归档，完整大二进制仍留本地build。

### 成本（单次离线同布局配对记账，不是实机FPS或重复微基准）

1962801对样点：旧权重24.9764秒，新权重3.9172秒；旧/新统一求值5.9982/5.4311秒。权重阶段减少约84.3%，但包含旧路径逐site开销和新空间索引，不将全部收益归因一个因素。共同建场109.4679秒(含浅海匹配)，整次导出151.6025秒；原始几何没有免掉，净实机性能仍未测。

64个tile所建线段保留350394/内部排除331375(建场记账，含各tile重复halo，不是全世界去重数量)。新点距测试141056818，旧次数未插桩，不能捏造比例。浅海patch共构建210次、缓存峰上限128，说明有淘汰重建但未单独完成S4压力/并发/多seed/远坐标门禁。

### 本地复现

mdk目录沿用前节Java21环境、baseline jars；先运行compileDiagnosticsJava，再用其输出优先：

```powershell
$r='build/coherent-morph-s3'
$cp="build/classes/java/diagnostics;build/classes/java/main;$base/classes;$base/src-main/resources;$jars"
& "$java/java.exe" -Xmx6g '-Dfile.encoding=UTF-8' -cp $cp com.geogenesis.worldgen.terrain.candidate.CoherentMorphGallery "$r/frozen-s2" "$r/reproduce-data"
if ($LASTEXITCODE) { throw 'S3 export failed' }
& "$java/java.exe" -Xmx6g '-Dfile.encoding=UTF-8' -cp $cp com.geogenesis.worldgen.terrain.candidate.CoherentMorphCheck "$r/reproduce-check" "$r/frozen-s2"
if ($LASTEXITCODE) { throw 'S3 check failed' }
```

仓库根：

```powershell
python -B tools/render-coherent-morph.py forge-1.20.1-47.4.10-mdk/build/coherent-morph-s3/data handoff/attachments/coherent-morph-s3-reproduce
```

**S3停止点（历史）：用户已接受保持并授权S4只验证取证；结果见下一节，不接生产。**

## 14. S4流式一致性验证：FAIL，发现证据后停止修复

### 授权与范围

用户只要求验证跨tile、不同查询顺序、缓存淘汰重建、多seed与远坐标；发现问题先给证据，不擅自改地形参数、240带、默认或生产。本轮只新增CoherentStreamingCheck与CoherentCoordinateCheck两个独立诊断入口。冻结114份旧candidate源码与14份S3 raw产物，结束时全部SHA不变，src/main无Git差异。未重出地形图、未替换S3基准、未绕过原seed守卫。

**总体结论：S4未通过。** 近原点有限样本的顺序/缓存/跨tile验证通过，但远坐标实际构建失败；完整多seed高度链受当前架构限制。以下成功样本不能抵消FAIL或把BLOCKED改成PASS。

### 已通过的有限样本

[最终流式报告](../../handoff/attachments/coherent-stream-s4-20261101/stream-final/stream.txt)、[逐点CSV](../../handoff/attachments/coherent-stream-s4-20261101/stream-final/samples.csv)。

- 正典seed，4个tile内20点：原序/暖缓存逆序/独立冷实例固定乱序，40次比较身份、23维新权重与e逐位一致。
- x/z两轴各-1024/0/1024边界及角附近，42组强制双tile同点样本，其中21混合支持；最大权重差6.55642e-7、最大e差8.94070e-8，身份无差异；原2e-3阈值未放宽。不是跨tile逐位相同，不掩盖小数值差。
- 两个偏移中心/半径900窗口14点初测恰全纯，不能只用它们证明混合；因此另补8个真实异类边中点，共22点，最大新权重差3.71743e-6、最大e差6.67401e-7，通过原阈值。见[混合换窗证据](../../handoff/attachments/coherent-stream-s4-20261101/window-mixed/stream.txt)。
- tile容量8：实际访问10个不同tile，目标对象已淘汰并产生新对象；重建权重/e逐位同；底层FullMorphLand也实际发生28次淘汰。不是手动clear模拟。
- 身份容量2048：通过真实分布选择2200个陆cell写入，目标海cell确实不在Map后重新查询，最终配方22一致。陆cell填充避免额外昂贵海几何，但目标是实际非shelf基础海cell。
- patch容量128：14次实际非shelf海cell查询导致新增159个patch，再查目标新建2个，总新增161；证实旧patch对象消失、目标重建，Match的before/after/distance/margin/segments逐位一致，缓存128。未改缓存容量或强制移除对象。
- 主报告97次比较(40顺序+42接缝+14初始换窗+1tile重建)，补充换窗22次单列，不混算成独立119个坐标。

首次换窗测试夹具误将裸RegionPartition几何包FullMorphLand.wrap，因无slot表触发slot -1；这是**探针错误**，不是S3生成缺陷。仅修探针为catalog.original同尺度factory，最终整项重跑exit0；旧失败留本地build。最终源码又追加了混合换窗样本，追加部分单独javac/运行exit0，所有被测源始终未改。

### 多seed与远坐标矩阵

[矩阵结果](../../handoff/attachments/coherent-stream-s4-20261101/coordinate-matrix/check.txt)、[完整输入/输出/stack CSV](../../handoff/attachments/coherent-stream-s4-20261101/coordinate-matrix/evidence.csv)。seed=[9139912035078620160,0,-419,12345]；坐标0、±512、±1e4/1e5/1e6/1e7/29998000，覆盖轴向和异号/同号组合。

| 层级 | 实测与状态 |
|---|---|
| 宏身份 | 172点有限候选vs扩大8格穷举一致，独立逆序一致 |
| 微候选/基础身份 | 172点3×3 vs11×11一致，逆序一致；仅是布局，不是完整高度 |
| 反warp | 688次，484在容差内，204实际残差FAIL，失败出现在所测1e6及以上坐标 |
| 近点跨窗口 | 4seed共24点S1配方权重、站点带内距离、赢家对拍通过；不冒充S2最终高度多seed验证 |
| 远几何 | 6个窗口，1成功、5构造失败 |
| S2浅海 | 每seed近点/远点各1个真实非shelf基础海cell：近4成功、远4反解失败 |
| 完整RegionalMorphWorld高度 | 正典near成功、far失败；其余3seed原构造抛UnsupportedOperationException，BLOCKED |

矩阵进程实际exit1，214条FAIL是204反解尝试+5远几何+4远matcher+1旧完整高度调用，**不是214个独立缺陷**。19条近似不同根差异处于float平台尺度内，未据此认定真多分支，亦不证明单射。

显式窗口16，但真实near matcher内部另完成64个patch，far matcher各在首个patch抛错；记录总构建超过最初约20窗的内部工作目标，不把16当总成本。发现证据后未继续密集扫描。

### 主代理独立复现：实际完整S3入口百万坐标失败

[原始异常栈](../../handoff/attachments/coherent-stream-s4-20261101/s3-failure-stack.txt)，主代理重新Gradle编译当前源码后实跑，实际exit1：

```text
seed=9139912035078620160
new CoherentMorphWorld().sample(1000000,1000000)
IllegalStateException: warp inverse residual 7.1913e+00 blocks > tol 2.0000e-02
at u=(1666.044433594,1664.999977410); world polyline would be untrustworthy
LocalBoundaryGeometry.toWorld → densify → buildWorldEdges → FullMorphLand.tileFor
→ CoherentMorphWorld.tileAt → sample
```

其他原始例：seed0的(-100000,100000)构建残差0.020952>0.02；seed-419的(1000000,-1000000)约14.062块；正典(29998000,-29998000)约38.982块。均保留既有容差，不以放宽阈值处理。

**定位事实与推断分开：**现warpForward将world坐标转float，warpInverse差分h固定.05。在1e7坐标float ULP=1，x+.05/z+.05均回到同一float，前向差分为0；在1e6 ULP=.0625，离散化也已大于.02容差。直接forward格点目标与偏离格点目标、不同初值的残差已有CSV。这支持“数值精度/固定差分步长是重要原因”，但未证明它是唯一原因，也未证明无warp折叠。现阶段不擅自换double、改warp/容差或绕过guard。

### 编译、复现与停止点

Gradle compileDiagnosticsJava成功；完整stream-final进程exit0；window-mixed定向编译运行exit0；coordinate矩阵exit1；主代理完整S3复现exit1。**最后失败是被测几何的有效失败证据，未修，S4验收继续pending。** 没有把shell取到预期exit1称成世界生成通过。未做共享同实例并发/全世界单射证明，亦未把多seed分布可测替代多seed完整高度可用。

mdk目录(Java21/target17)，本地已有baseline依赖；输出目录用新名称，coordinate拒覆盖：

```powershell
$java='D:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot/bin'
$base='build/terrain-upgrade/baseline'
$jars=(Get-Content "$base/classpath-jars.txt" -Raw).Trim()
$cp="build/classes/java/diagnostics;build/classes/java/main;$base/classes;$base/src-main/resources;$jars"
# Gradle编译后，新追加的Streaming混合换窗测试可单文件javac到新classes置于cp最前。
& "$java/java.exe" -Xmx6g '-Dfile.encoding=UTF-8' -cp $cp com.geogenesis.worldgen.terrain.candidate.CoherentStreamingCheck build/coherent-stream-s4/reproduce-stream
# 预期取证退出1，不吞掉：
& "$java/java.exe" -Xmx5g '-Dfile.encoding=UTF-8' -cp $cp com.geogenesis.worldgen.terrain.candidate.CoherentCoordinateCheck build/coherent-stream-s4/coordinate/reproduce-s3 s3-only
$LASTEXITCODE
```

**停止点：已交证据，不修改生成逻辑。需要单独确定远坐标数值几何修复路线与任意seed完整上下文；当前不接生产、不改参数/240带/默认，也不将S4验收标完成。**
