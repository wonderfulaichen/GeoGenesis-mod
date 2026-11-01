# C 臂陆地影响审计与统一世界架构裁定

> 原始审计范围：诊断只修 `pickCoast` 分布的 C 臂。下文 §1–7 是修复前证据快照。
> 后续经用户明确授权，仅修距离算法；结果见本节。没有改其他地形参数或接生产。
> 距离参照是同一 8 块网格上到最近异类节点的距离，不冒充精确连续岸线距离。

## 后续：距离算法已修复（单变量 A/B）

用户指令：“先修正这个距离算法，验证海岸不会再深入内陆，然后出修复前后的 A/B 大图和剖面给我看。先别动其他地形参数。”

- A = 修复前 **BOTH**（两处选型均修复）臂；不是先前审计的旧 BASELINE 臂。
- B = 同样的 BOTH 选型，仅替换 `CoastalAssembler.twoPass/relax` 为行列分离的精确平方欧氏距离变换。
- `UnifiedWorld`、全部海陆/海岸配方、形态引擎源码 SHA 未变；基底网格、逐点类型高度、逐点带宽逐位未变。基础带宽80、陆侧最大116、距离网格8均未改。
- 算法 O(n²) 建场，查询沿用 O(1) 双线性；全海/全陆时保留原有限远距哨兵，避免插值产生 NaN。

| 验证 | 结果 |
|---|---:|
| `DistanceFieldCheck` 解析/随机暴力对拍（含全海全陆、单格、零高度、轴/斜线、插值与边缘） | 48712 项 PASS |
| 同一回归测试对冻结旧代码 | 预期 FAIL：800→80，非零退出 |
| 全部海/陆距离节点与独立 SciPy EDT 最大差 | 0 / 0 格 |
| 1962801 输出点的插值距离最大差 | 1.82e-12 块 |
| 修复后带外像素 | 1787950 |
| 带外与未加工类型高度逐位差异 | **0** |
| 岸带覆盖 A→B | 1097429→174851 px（55.91%→8.91%） |
| 水面 A→B | 29.958666%→29.746062% |
| 原问题点旧/新距离 | 97.200692→1057.654449 块 |
| 原问题点旧/新 Y | 77.822650→243.083198（与纯陆型一致） |

两类距离字段已用独立精确算法全节点核验，再按正确逐点带宽验证带外零覆盖，而非依赖旧错误 d 自证。网格/插值误差、最近点切换处非 C¹、有限窗口外缺少海陆样本等边界不因本修复消失；没有据此宣称无限世界架构或所有海岸接缝已完成。

**单变量 SHA256**：A `e653672839eeb79ea6edfe6e763500ed8d20c3787f251cb8f2a35394896b2711`；B `5af5df1fd9d78c45d91398deda1a3773bc49635a8573515a63d97daba433befe`。

出图与证据：
- [pair-full.png](attachments/distance-fix-20261101/pair-full.png)：2918×1690并排，两幅各1401²未缩放。
- [profiles-full.png](attachments/distance-fix-20261101/profiles-full.png)：六条同位置全幅剖面，共用纵轴。
- [profile-recovered-inland.png](attachments/distance-fix-20261101/profile-recovered-inland.png)：原问题点局部1块采样剖面。
- [verification.json](attachments/distance-fix-20261101/verification.json)：独立对拍与源码隔离记录。

冻结源码与原始图在工程 `build/distance-fix-20261101/frozen-source` / `before`；旧源码用独立输出目录编译，不回滚用户工作树。新旧两套运行同一 `DistanceFixWorldCheck`，新实现另跑 `DistanceFieldCheck`。独立验证与出图复现命令（仓库根）：

```powershell
python -B tools/render-distance-fix.py `
  forge-1.20.1-47.4.10-mdk/build/distance-fix-20261101 `
  handoff/attachments/distance-fix-20261101
```

修复只在诊断沙箱，不改 `src/main` 或生产缓存 schema；新图使用独立目录，未复用旧缓存。实机 Minecraft 未验证。

## 后续：共用形态引擎 P0～P2（未接整幅）

本阶段授权仅为：泛化现有节点引擎、接入五个海洋真实节点、验证原陆地不变与海洋端点。分布层、完整世界接线和特殊海岸未做；前文截图四类线的问题没有因此宣告修复。

- `RecipeJunctionMorph` 现为薄适配，真正求值统一在 `NodeMorphEngine`。目录通过 `MorphRecipe` 提供源信号/幅度/偏置及台地、劣地、山地角色，不再硬编码世界目录长度或15/16/17。原18项陆地目录、45槽、原覆盖层源码不动。
- 纯陆保留原float运算顺序与h→e外壳；含海洋时外壳统一到e，海洋不套陆地GROUND_E/E_SPAN。复合内部下限、陆地外层下限、陆架上限分开。
- `OceanRecipeCatalog.height01`保留原公式供旧链使用；`morphEntry`另暴露真实节点，不用成品高度倒推raw。峡谷幅度显式为负，不除幅度。
- 海山首版把整段抬升作raw且幅度为1，端点虽正确，海山↔陆架混合出现最高e=0.02561的错误出水，局部门禁确实FAIL。改为在每个原鼓包节点内部按既有幅度上界`0.05+0.08`归一，再取max；外壳还原该上界。原高度式、噪声、种子、概率、半径和各鼓包实际幅度均不变；没有用调参或最后截平海面掩盖。
- 域坐标保留陆地float端点，同时使该舍入残差随山地参与量归零，避免海洋端点因微小山地权重而跳位；两侧端点极限分别检查。

验证与范围：

| 检查 | 结果 |
|---|---|
| 冻结旧源码与新纯陆路径，多种子14400个输出 | SHA逐位一致 |
| 冻结旧海洋API与新API，4种子×2臂共120000个输出 | SHA逐位一致 |
| 原目录/形态/完整陆地场地门禁 | 18201 / 443538 / 910 项PASS |
| 局部节点门禁（端点、10海海双族、五海混合、海陆扫权、第三权重退边、重排、负幅度穿零） | 617318 项；最终结果见附件 |
| 截断负e的人工负控 | 应FAIL并非零退出，不计为正常失败 |
| 历史七臂Gallery | 各臂SHA对冻结文件，不覆盖原baseline |

局部[权重剖面图](attachments/world-engine-p0-p2-20261101/local-weight-profiles.png)的横轴是同一坐标的配方权重，不是空间距离，不是海岸效果图；台地原非线性仍可产生形状折点，未声称全域C¹。最终[验证摘要](attachments/world-engine-p0-p2-20261101/verification.json)与[完整日志](attachments/world-engine-p0-p2-20261101/final-verification.log)单独归档。旧`OceanTransitionCheck`仅作为兼容回归，其“海岸内部类”历史标签不是当前架构验收标准。

复现（仓库根）：

```powershell
& ./forge-1.20.1-47.4.10-mdk/tools/verify-world-engine.ps1
python -B tools/render-engine-local.py `
  forge-1.20.1-47.4.10-mdk/build/world-engine-p0-p2/evidence/NodeMorphEngineCheck/local-weight-profiles.csv `
  handoff/attachments/world-engine-p0-p2-20261101
```

脚本依赖本机忽略的`build/world-engine-p0-p2/frozen-source`、冻结七臂与旧陆地指纹，以及原baseline类路径；不是可在空build直接运行的自包含脚本。没有运行新整幅世界或实机，也没有测区块性能。生产源码、缓存schema、`UnifiedWorld`、距离算法与海岸剖面均未改。P3～P5保持未授权。

## 后续：P3 原区域海陆统一权重（独立沙箱臂，待用户目检）

用户授权继续P3：复用原区域，交付类型布局/普通过渡同窗口A/B大图及剖面；不调参数、不处理海岸剖面、不接生产。此节覆盖上节末尾“P3未授权”的历史状态；P4/P5仍未授权。

新入口`RegionalMorphWorld`只复用原`FullMorphLand.Tile`、`LocalBoundaryGeometry`的站点/距离/240块单次ease及归一。站点身份由原cell坐标确定：在该站点的固定world位置采样既有c场（seed+7000、4000/6），按原seaProbability(c)与独立站点hash（seed+7400）决定内部海/陆；陆型仍取原45槽映射，海型取原独立12槽表，不按c枚举顺序指定深浅。所有23配方连续权重交同一个`NodeMorphEngine`。没有调用旧`oceanWeight/merge`，没有在每个查询点重选配方。

旧覆盖层只保留原陆地语义：纯陆复刻原h域计算；混合时只将原陆地覆盖增量乘陆地参与量接回，纯海不覆盖。该项是维持原覆盖层端点的适配，不是成品陆/海高度融合。所有既有candidate源码与P2冻结SHA相同，包括配方/内核/区域几何/旧世界/海岸。

本轮抓到并修复两处接线bug：
1. `sumLand==1`不足以判纯陆：极小正海权重会被double求和吞掉，局部采样曾抛`land-only weights required`。改为检查实际海洋参与项；240个极小权重向量回归及回退负控已覆盖。
2. `RefNoise.hash2D`的末尾`hash ^= hash >> 13`使符号位恒0，是31位非负输出。首次按32位缩放让抽签只在[0,.5)，海槽6..11无法访问。现按2^-31换算，既有seed/权重不动；12槽及5海型可达检查已补，回退旧换算负控FAIL。错误中间图已被最终图替换，原raw单独保留在忽略的`rejected-half-hash-data`，不得拿来验收。

### 最终图口径与结果

固定seed `9139912035078620160`，仅JVM属性`file.encoding=UTF-8`；1401²、step5、中心(-140,137)、原点(-3642.5,-3365.5)，高度由Java的HeightCurve直接输出，Python只渲染。

| 臂 | 作用 | 水面 |
|---|---|---:|
| A | 距离修复后的旧完整图（含旧海岸） | 29.746062% |
| base | 旧链未加工基底（无海岸） | 29.511244% |
| hard | 新站点布局，仅赢家配方硬切（无海岸） | 29.411030% |
| B/morph | 同新布局、海陆统一节点过渡（无海岸） | 29.571974% |

A→B陆变海107867px、海变陆111284px，水面净−0.174088个百分点。内部类型主导标记改变25.91%，不能说布局没变；A/B还同时有“旧海岸加工有无”的差异。**仅hard→morph是同布局普通过渡的单变量比较**：水面翻转23867px，P99|ΔY|=44.523格。

最终B五海型均在窗口出现；按主导id统计，占全图分别：深海平原10.4642%、洋中脊7.1330%、海山1.9237%、峡谷2.5119%、陆架7.3782%。这是内部类型主导面积，非实际水域内占比，也不是类型目标概率精确兑现。海侧权重11px超过1一个ULP（2.22e-16），超过1e-12的真越界0；未修改权重或以图像裁剪伪造归一。

- [旧完整图A/B](attachments/regional-world-p3-20261101/pair-full.png)：两幅各1401²原生。
- [类型布局A/B](attachments/regional-world-p3-20261101/pair-types.png)：旧内部类型与新主导类型，非海岸类型图。
- [同布局硬切/过渡](attachments/regional-world-p3-20261101/pair-smoothing.png)：最适合单独观察普通形态过渡。
- [六条同坐标全幅剖面](attachments/regional-world-p3-20261101/profiles-full.png)：共用Y轴−64..320、step5。
- [三类边界局部剖面](attachments/regional-world-p3-20261101/profiles-local.png)：法向±240、step1（不是8块距离网格或5块像素重采样）。
- [出图统计/来源哈希](attachments/regional-world-p3-20261101/metrics.json)与[接线验证](attachments/regional-world-p3-20261101/verification.json)。

### 实测与限制

区域门禁29033项、P2节点617318项、原陆地910项通过；273个跨窗口站点身份一致、14个强制跨tile同点权重/高度最大差0；实际扫到陆陆669、海海150、海陆308点，三类各3条边缩步收敛，不以零样本PASS。12海槽可达、全部5海型可选。纯陆/纯海各60点逐位对拍，特殊覆盖有正alpha的独立点另测。

本次整图采样1962801点，有效配方均值1.4086、最多4。节点+权重采样累计23.95秒（不含tile构建），站点选型约4ms；全图循环约58.02秒，另含hard对照、旧类型查询和导出开销，**不是FPS/区块成本，也不是严格A/B性能基准**。几何64tile、缓存淘汰56；原有限LRU未改。

本轮仅canonical seed实测；反warp单射未证、有限精度折线沿用原限制，不宣称无限世界验收。保留原内部配方的Terrace/Steps等折点，没有声称所有边界全域C¹。海岸是未接入的特殊阶段，不把B的普通入海坡说成已完成沙滩/崖岸。未运行实机、未改生产或schema。

复现Java：按P2相同baseline类路径，编译candidate目录后运行`RegionalMorphWorldCheck <out>`，再运行`RegionalMorphGallery build/regional-world-p3/data`。Gallery要求data内已复制、校验原距离B的`baseline-a.f64be`与旧`Y-base`的`baseline-base.f64be`。渲染从仓库根运行：

```powershell
python -B tools/render-regional-world.py `
  forge-1.20.1-47.4.10-mdk/build/regional-world-p3/data `
  handoff/attachments/regional-world-p3-20261101
```

## 1. 用户设计定稿（本节覆盖旧方案中的冲突表述）

用户原话：

> 新一代世界地形的架构是类似我们之前弄的大陆机制，扩展到海陆完整的世界地形。分布只管分布，地形高度由各自类型的高度决定。
>
> 类型之间过渡就是我之前研制的平滑过渡，只是由原来只负责大陆变成全世界。陆-陆过渡、海-陆过渡、海-海过渡都是过渡类型；其中陆与海都有各自的内部类型。内部类型有自己的地形形态，过渡类型一般情况没有自己的地形形态，是由需要过渡的内部类型去确定。特殊过渡类型是在基础上有一定的加工形态，如：海岸等各异形态。
>
> 模组的地形类型等于现实里面的地形地貌定义。（可以写笔记）

落实为约束：

1. **分布只管分布**：选择类型与空间位置；不直接用连续 `c` 的权重决定最终海底或陆地高度。
2. **内部类型**：陆地、海洋内部各自有独立的地貌定义、形态和高度。
3. **普通过渡类型**：陆陆、海陆、海海都复用已研制的平滑形态过渡；不是另造三套算法，一般不自带独立形态，由相邻内部类型决定。
4. **特殊过渡类型**：海岸在普通形态过渡基础上加工，具有各异海岸地貌；不是独立占区的第三种内部类。
5. **类型 = 现实地形地貌**，不是噪声算法名的集合。
6. **当前实现尚不符合完整架构**：`UnifiedWorld` 仅陆侧用了 `FullMorphLand`，海型和岸型仍硬选，海岸由单独 d 场覆盖。不能以探针 PASS 或生成整图宣称统一已完成。

旧方案 [ocean-unified-terrain.plan.md](../.openbitfun/plans/ocean-unified-terrain.plan.md) 已在顶部登记本次裁定并标注旧段落失效边界。

## 2. 结论：是不是本次改动引入的

**不是本次 `pickCoast` 改动新造了距离错误；但本次换型确实在受旧错误影响的内陆产生了新增的巨大高度差。**

- 根因：此前助手写的 `CoastalAssembler.twoPass/relax` 并不计算欧氏距离，却被注释和报告称为欧氏距离。
- 直接触发：C 修改分布后，同一位置换成不同海岸配方；旧错距把真正内陆位置当作海岸带，配方差异便在那里生效。
- 最大差值的放大器：既有沙滩高度封顶 `min(eLand,0.070)` 与带末端 `smoothstep(0.85,1,t)` 交接，使错误落入沙滩的高山在大部分带内被压成低滩。
- 上一轮报告失误：把“A 中 Y≥63 的点发生变化”直接推论成“C 新增带外泄漏”，没有检查 d，也把“陆上”误说成“内陆”。

因此不能仅说“正常海岸变化、没有 bug”，也不能说“C 新写的分布逻辑越过了带外分支”。准确说法是：**旧错距导致空间越界，本次换型放大了该问题。责任仍在此前和本轮的助手实现、验证与误诊。**

## 3. 原始证据未变，对照可复现

- 改动前 `build/unified-world/Y-world.f64be` 与 A 臂 SHA256 完全相同：
  `a10ebcc30ba83ec1c482914f72ed4ee24cf4964fd941b476c720648550106394`。
- C 臂 SHA256：`e41ff08dddfbf4cf9aff84dbd4c955522b2570c1706074c3144e9af2bb908e9e`。
- 不覆盖原 raw；新增只读探针在新目录输出审计。

对同一窗口 1962801 点重放：

| 检查 | 结果 |
|---|---:|
| A 重算与 A raw 的逐位差异 | 0 |
| C 重算与 C raw 的逐位差异 | 0 |
| A/C baseGrid 逐位差异 | 0 |
| A/C toSea / toLand 数组逐位差异 | 0 / 0 |
| A/C 逐点带宽差异 | 0 |
| 旧 d 判断的带外像素 | 865372 |
| 这些“带外”像素发生变化 | 0 |
| 同岸型点发生变化 | 0 |
| A/C 总变化像素（逐位） | 736596 |
| A 原陆上点发生变化 | 458812 |

**作用域重放检查通过，仅证明代码尊重自己的旧 d 判据；不证明 d 算法正确。**

## 4. 错距的最小反例与独立验证

现有递推：

```java
nd = d2[from] + di * di + dj * dj;
// 传播完后
return sqrt(d2);
```

沿 x 轴走 k 个格子，上式累计 k，最后返回 `sqrt(k)`；真正距离应为 k。
8 块网格、100 个格子的合成反例：**真实 800 块，旧算法 80 块**。
这不是小精度误差，也不是欧氏距离变换。不能通过提高容差包装成 PASS。

独立使用 `scipy.ndimage.distance_transform_edt` 对同一不变 baseGrid 计算，到输出点作双线性查询：

| 检查 | 结果 |
|---|---:|
| 距异类网格点超过最大海岸带宽 116 块仍发生变化 | 591487 |
| 其中 A 原陆上且基底位于陆侧 | 385361 |
| 最远发生变化点的参考距离 | 1598.3191 块 |
| 正确距离下对称 ±116 块范围内像素 | 222518 |
| 错误 d 判为岸带的像素（实际带宽 80..116） | 1097429 |

前两项使用保守的全局最大 116 块，不是完整逐点真实带宽门禁；足以证实越界，不夸称为精确全部越界量。节点距离也不是最终高度的连续岸线距离。

## 5. 最大差值点归因

位置 `(2522.5,-1305.5)`：

| 量 | 值 |
|---|---:|
| 最近海网格点距离，暴力遍历独立计算 | 1057.6469 块 |
| SciPy EDT 双线性查询 | 1057.6544 块 |
| 旧 d | 97.2007 块 |
| band / t | 116 / 0.837937 |
| 内部陆型原始高度 Y | 243.0832 |
| A 岸型 / Y | SEA_CLIFF / 235.5049 |
| C 岸型 / Y | SANDY_BEACH / 77.8227 |
| 最大高度差 | 157.6823 格 |

`coastBand` 的交接系数在 t<0.85 时为 0。该点 C 完全取沙滩剖面，而沙滩 anchor 被封顶到 0.070 e。分布变化没有修改配方公式，却改变了受该公式作用的位置。

![影响范围](attachments/sea-select-ab-20261101/coast-leak-audit/scope-audit.png)

![最大差值点剖面](attachments/sea-select-ab-20261101/coast-leak-audit/worst-profile.png)

## 6. 需要撤回的旧结论

1. 全窗口岸型直方图不是实际岸带占比；原报告的 58.49% 是全窗口潜在选型。
   实际错误岸带中 A 礁盘 `645088/1097429`，C 为 `207222/1097429`。仍需注明该岸带本身错距。
2. 更早“礁盘两条孤立配对水太多”发生于孤立配方图，不能用后来 `pickCoast` 世界分布去解释；上一轮把两者因果串起来没有证据，应撤回。
3. 内部深海平原的低振幅是其地貌定义，不因为看起来平就自动认定为要增幅的 bug。
4. “海型枚举从深到浅严格单调”不成立（CANYON 为深切类）；单纯反转索引不能证明完整类型序列正确。此事未在本轮扩范围修复，须服从分布/高度分离与统一形态过渡的既定架构。

## 7. 复现

工程目录运行（Java 21 启动，编译 target 17）：

```powershell
$java='D:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot/bin'
$jars=(Get-Content build/terrain-upgrade/baseline/classpath-jars.txt -Raw).Trim()
$out='build/coast-distribution-audit/classes'
New-Item -ItemType Directory -Force $out | Out-Null
$cp="$out;build/terrain-upgrade/baseline/classes;build/terrain-upgrade/baseline/src-main/resources;$jars"
& "$java/javac.exe" --release 17 -encoding UTF-8 -nowarn -cp $cp -d $out `
  (Get-ChildItem src/diagnostics/java/com/geogenesis/worldgen/terrain/candidate/*.java).FullName
if ($LASTEXITCODE -ne 0) { throw 'compile failed' }
& "$java/java.exe" -Xmx5g '-Dfile.encoding=UTF-8' -cp $cp `
  com.geogenesis.worldgen.terrain.candidate.CoastDistributionAudit `
  build/terrain-upgrade/verify-20261101 build/coast-distribution-audit/evidence-distance `
  'D:/Office software/Development Project/GeoGenesis-mod/debug-agent.log'
if ($LASTEXITCODE -ne 0) { throw 'scope/replay failed' }
```

仓库根运行独立参考计算（本机已安装 NumPy/SciPy）：

```powershell
python -B tools/audit-coast-distribution.py `
  forge-1.20.1-47.4.10-mdk/build/coast-distribution-audit/evidence-distance `
  forge-1.20.1-47.4.10-mdk/build/terrain-upgrade/verify-20261101 `
  handoff/attachments/sea-select-ab-20261101/coast-leak-audit/distance-reference.json
```

原始 base-grid 与距离数组位于忽略的 build 目录；摘要、分组 CSV、1 块剖面、参考 JSON 和两张图已归档在本笔记同目录 attachments 中。源码构建与作用域重放通过；**距离正确性反例为 FAIL，尚未修复**。没有运行 Minecraft，也没有修改生产源文件。
