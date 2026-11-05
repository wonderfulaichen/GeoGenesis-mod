# A 候选跨地貌 / 覆盖层 / 海岸隔离验证（2026-11-05）

## 用户授权与验收口径

> 『阶地移动、重塑、局部抬升平台不算代价，这正是侵蚀带来的效果。不要搞错了。』
>
> 『先保留 A 作为候选，再补纯山地、劣地、覆盖层和海岸的小样本对比图及剖面，看看会不会破坏原地貌。先别接生产，不提交不推送。』

**继续保留真 raw A 候选。** 不以接近 control、较小 MAE、平台不动作为“好”；地貌身份/主体尺度丢失、人工折痕断崖接缝、域错误或不合理海陆大改才是风险。数值变化只能定位，不能独立定罪。前序报告的误读已在 [真A报告](GeoGenesis-ridge-true-ab-20261105.md) 开头明确纠正，原证据未删。方案入口及索引已追加引用。

本轮未接生产，无 src/main、run 配置、schema 改动；不换 proxy / Rune、不叠粒子、不调算法参数。没有提交、推送、stash、add、回退或启动其他代理。未跑大图、无关门禁、性能扫或实机。

## 1. Operator placement 核查与必要修正

隔离代码：`forge-1.20.1-47.4.10-mdk/build/ridge-a-coverage-20261105/`（下简称 build）。

- **region 台地**：原 `DiagnosticRidgeKernel.java` 逐字复制且哈希留证；domain 后 raw 加旧算子 delta，再过原 0.9 Terrace、surface、shell、clamp。台阶移动/平台重塑是这个接点的真实作用，不是代价。
- **region 劣地**：修改 hills/mask 后 raw，后续 steps4/steps10/shapeSteps 及位移后的 `preBad` 重采样都消费 A。位移重采样不是额外 domain 或递归过滤；保留原算法。
- **region 山地**：domain 一次后修改 raw；保留独立 `MorphNoise.Erosion` 原纹理节点，其输入仍是未修改的原山地 raw。不是将 A 偷塞进纹理内部重采样。onehot17 时 A 的 raw 贡献经过原 0.35 混合系数，不能称“移除原纹理后的纯骨架”。
- **覆盖层：确实发现上轮漏接。** 原 `TrueRidgeProbe.values` 的覆盖高度直接读 `oldCoverageHeight`，所以 alpha=1 时 region A 被完全覆盖，A 不执行覆盖层塑形。这是证据覆盖盲区，不是 A 在覆盖层安全的证明。
- 本轮仅诊断 `CoverageRaw` 从原 `PlateauRecipe.REF`（同 seed+44）拆取 baseNode / Terrace / surface / amp，**只替换 Terrace.input**：`raw += oldDelta(rawShell)/(E_SPAN*0.475f)`。保留该图原 **1.05** Terrace 所有参数、surface、0.475f，不能拿 region15 的0.9图代替。rawShell 用同一 e 标定和原 float / ±2wu / 4f×HS / world-coordinate evaluatePoint。无额外域扭曲。
- region A 与 coverage A 分别在各自 raw 接入，最后仍由原 mask/alpha 混合，之后原 ocean assembler 与 HeightCurve；B 继续在 covered continuous e 后置，参数不变。`region-only-A.f64be` 是上轮漏接负控，**不是本轮 A**。

## 2. 选点与真实纯度（全像素，不是仅稀疏网格）

先用便宜 partition 类型 / mask / continent 256wu 间距筛候选（33²廉价点，只有64个候选进入真实几何），再按真实权重核实局部小窗。没有密集世界高度扫。粗类型仅用于选点，不作纯度证明；`selection.csv` 保留其与真实结果的差异。每窗完整18权重在 build 的 `weights.csv`，版本附件带统计与原始数组 SHA。

| 样本 | n / step wu / 原点 wu | 全窗真实类型权重 | coverage alpha | 组装后陆地率 control / A / B |
|---|---|---|---|---|
| mountains | 257 / 1 / (-3200,-3200) | 66049点均 w17=1，**纯山地** | 全0 | 100% / 100% / 100% |
| badlands | 257 / 0.5 / (3776,960) | 66049点均 w16=1，**纯劣地** | 全0 | 100% / 100% / 100% |
| coverage | 257 / 1 / (3200,-3456) | entry0/15过渡；单条目41.177%；主导0=58381、15=7668；**不称纯台地** | 0.236784892..0.925740822，100%激活过渡 | 100% / 100% / 99.99394% |
| coast | 257 / 1 / (-1408,-2944) | 全 w17=1，但海陆组装窗，**不称纯陆地山地窗** | 全0 | 6.65869% / 6.68140% / 6.70260% |
| coverage-full（额外负控） | 129 / 1 / (4032,-3648) | 全 w15=1，实际呈共享覆盖台地 | 全1 | 0.68505% / 0.68505% / 0.71510% |

coverage-full 恰处海岸，组装后大多被海洋遮掉，因此**必须看 pre-coast 图及右列剖面**才知道 A 已执行。不是将海岸遮蔽冒称零效果/质量通过。coast 的 edge=0.23420556..0.47275301，覆盖浅海、岸线和低岸坡，**未覆盖高岸顶完整内陆接管**；合成 edge 端点探针不替代真实高崖海岸图检。

## 3. 实际图检（已逐张 view_image，不以指标代替）

五组 `comparison.png`、五组 `profiles-detail.png` 均实际查看；另实际查看五组 pre-coast 同窗。同 seed=`9139912035078620160`、HS=2、配置、原点、step、[-64,320]色标、LIGHT=(-.5,-.35,1)、负梯度法向。像素为原生样本，不平滑或逐臂归一化。剖面固定各窗1/4、1/2、3/4行，左右明确分组装后 Y 与海岸前 e 经原陆侧 HeightCurve 映射用于显示，不混阶段。

- **纯山地**：A 保留主山脊、宽谷与整体坡面位置，增加局部沟脊细节；三条剖面的主体峰谷仍可辨认。B 的密集窄槽/尖峰明显更强，仅保留参照不重新裁决。control 中已有的中部短暗线在 A 仍能看到，不能把既有线条归罪 A。A 也有窄细纹，尚不能凭单窗证明无限世界无折痕。
- **纯劣地**：A 明显重塑阶地、局部平台与沟脊，陡边迁移且阶地高度改变，但台面—陡坎—低地的身份仍可读；这属于**预期塑形，不作代价**。部分台缘出现细尖状/分叉暗线，剖面局部峰/坡变尖；是需保留的**未判定局部锐边风险**，尚无证据认定为域接缝或人工断崖，更不能只用较大变化数值否定 A。B 主要沿原陡边产生深窄槽，参照而已。
- **coverage 过渡**：补接后 A 确实重塑覆盖台地，上部阶地与坡面整体结构仍可读。阶地台面移动、沟脊增强属预期。三条剖面 A 有平台/阶边重排，未见在 alpha 某值处贯穿全窗的硬缝；局部细锐线仍需后续针对性确认。B 在陡边有更明显窄极值。未声称覆盖层所有强度/所有过渡都安全。
- **coast**：组装后 A/control 岸线位置基本一致，局部小变可见且不构成本窗不合理海陆大改；A海陆翻转17/66049像素，净陆地增加15像素（0.02271个百分点）。预海岸图中山地细沟变化明显，经过原岸段组装后被大幅压弱；不能拿“组装后像 control”独立当成功。低岸坡剖面连续、未见新刀切缝。高崖岸顶与激活覆盖层的海岸复合情形仍未验证。
- **coverage-full**：海岸前 A 的台阶/沟脊变化清晰；组装后大部分差异被海床遮挡。负控证实旧漏接 A 在 alpha1 下恒等 control，而完整 A 在5764/16641点的前海岸 e 发生变化；不存在“覆盖层算子没执行却判绿”。这个样本验证执行及分阶段区别，不冒称内陆全覆盖质量已通过。

**本轮推荐：继续保留 A 作为候选，不接生产。** 新小窗没有给出地貌身份整体丢失或大范围错误海陆转换的证据；台地重塑不是失败。劣地/覆盖层局部细锐线暂列未判定，不能宣布全面排除人工伪影。下一步若另获授权，优先针对这些细线更细采样，以及高崖/覆盖层激活的海岸完整接管，而非重跑大图、调参或引入新算法。本轮不宣布用户验收。

## 4. 针对性验证与数值用途

- scoped javac `--release 17 -encoding UTF-8 -proc:none -Xlint:all -Werror`，新编 `DiagnosticRidgeKernel`、`CoverageProbe` 和独立 `CoverageChecks`；两个检查 JVM 均成功，无吞 FAIL（AssertionError 非零）。
- region 零强度18端点/真实混合逐位，raw float ±2差分逐位，非山地端点独立 wrapper，山地单域+原纹理+shell 独立公式，15→17退边、重复/查询顺序通过。
- 覆盖层原图零强度逐位、五代表点全链A/B零强度逐位、独立 raw差分→1.05 Terrace→surface→shell、alpha0忽略NaN覆盖/alpha1忽略NaN region、低高阈值退边、查询顺序通过。
- 原海岸组装公式在 shore / shore+width 两端、五个真实 land 高度下，用合成 constant edge 分离边界：eps1e-3..1e-9的高度差收敛到约3.2e-8..1.0e-6 blocks；这是值连续探针，**不证明导数或所有真实空间接缝不存在**。
- 漏接覆盖层负控：真实 alpha1 点旧接线==control，而完整A不同；alpha0全窗完整A==漏接A。覆盖过渡窗 full-minus-missing 最大0.18370038e，全覆盖窗0.20855212e。无alpha1/0像素的窗口对应检查记 null，不能用空集合通过冒报。
- 各臂全部样本非有限=0。组装后 relief Y，control/A：山地128.638/126.809、劣地63.005/97.966、覆盖114.157/114.260、海岸18.739/18.762。坡度P50/P99/max与相邻跳变分布见 metrics；劣地A相邻最大23.817Y（step0.5wu），定位局部锐坡，**不凭该数判坏**。负控、图像、剖面须一起读。

## 5. 资产、复现和冻结边界

版本化附件根：`audit/attachments/ridge-a-coverage-20261105/`。

每个 `mountains/`、`badlands/`、`coverage/`、`coast/`、`coverage-full/` 包含：

- `comparison.png`（组装后 control/A/B）
- `pre-coast-comparison.png`（covered land e 映射显示，不是组装后地表）
- `profiles-detail.png`、`profiles.csv`（固定世界位置双阶段）
- `coverage-evidence.png`（alpha / edge / 完整A减漏接A）
- `metrics.json`、`window.json`。

根有 `selection.csv`、`summary-metrics.json`、`checks.txt`、`coverage-checks.txt`、`boundary-check.json`、`current-main-verification.json`、`provenance.json`、`SHA256SUMS.json`，以及小型 `sources-tools-commands.zip`（Java/Python、配置输入、所有实际命令及日志）。完整18权重与f64大端原始数组留 build，不重复打包巨型数据。源码和class哈希、原始数组hash可追溯。

**main复用依据**：不是旧 baselineclasses。对 `ridge-ab-small-20261105/main-classes` 所对应当前212个main源与完整class清单逐项SHA核对，再复用；结束再次核对。classpath只有该 current main目录和依赖jar，无 `build/classes/java/main` 或旧baseline class兜底。仅显式 JVM 属性 `-Dfile.encoding=UTF-8`，另有`-Xmx2G`；无 geogenesis/hydro 属性。有效配置沿用前序 strength2/cell100/freq1.2/octaves4/gully.65/detail1/filterSeed1337、原rounding/onset，未换参数。

复现从仓库根（输出CREATE_NEW，先另建fresh目录；已有目录不要覆盖，setup也不要重跑覆盖正在保留的源码）：

```powershell
python <fresh>/run.py compile
python <fresh>/run.py checks
python <fresh>/run.py mountains 257 1 -3200 -3200
python <fresh>/run.py badlands 257 0.5 3776 960
python <fresh>/run.py coverage 257 1 3200 -3456
python <fresh>/run.py coast 257 1 -1408 -2944
python <fresh>/run.py coverage-full 129 1 4032 -3648
python -B <fresh>/render.py
```

`CoverageChecks` 的精确 javac / Java 命令另存 `coverage-check-compile-command.json` / `coverage-check-command.json`；所有命令不需要重编全 diagnostics。Java源码无变更后，首次运行render赶上后两窗仍在生成只渲了完成项；全部完成后重渲五窗，未覆盖原始数组。

冻结 `before.json` 在首次audit纠偏前生成；最终复核所有起始受保护文件（含已有src/main修改/未跟踪文件、run配置、认可基线附件、既有audit资产），允许变化的起始文件仅三份audit文档：真A报告、R0方案引用、audit索引。其余起始文件哈希、HEAD/index均不变。新报告/附件仅为授权新产物。未把起始脏树清掉或提交。
