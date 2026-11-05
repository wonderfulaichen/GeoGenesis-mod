# 骨架接点小样本：A-proxy / B（2026-11-05）

> 用户授权：“按方案做骨架 A/B 接点的小样本验证，出同窗口对比图和剖面，看看放在配方内还是过渡后更合适。先别接生产，不提交不推送。”
>
> **结论：证据不足以裁定真正 A/B；不接生产。** 本轮真实执行旧 `RidgeValleyErosion`，不是 Rune 候选，不是水文骨架。A 现有实现只能称 **A-proxy（bias-shell 修正）**。B 是可解释的后过渡参照，但目前图面也不足以推荐直接接线。未进行用户验收。

## 1. 已执行范围

复用并扩展 `src/diagnostics/.../morph/RidgePlacementProbe.java`：新增 `select` 小范围选点、`small` 自定义单窗、海岸前 e 导出和稀疏真实权重网格；旧算法、配方、参数不改。复用 `tools/render-ridge-placement.py`，增加 small 渲染模式、明确 A-proxy 标签、修正已为 MC Y 的高度与水平 `stepWu * HS` 的坡度尺度。未运行旧默认 1401² 分支、performance 方法或全 diagnostics；已知坏 `SkeletonOnlyTierProbe` 未触碰。

在 `forge-1.20.1-47.4.10-mdk/build/ridge-ab-small-20261105` 独立编译当前 **212 个 main 源文件**，再 scoped 严格编译单个探针（release17，`-Xlint:all -Werror`），成功。不是把 R0-0/baselineclasses 当当前生产。R0b classpath 仅作为依赖解析来源，当前源码另存 SHA，main/probe class 全存 SHA。首次运行 classpath 尾部还带旧 `build/classes/java/main`（本轮新 class 在前）；随后移除此兜底并重跑全部四窗、逐字节复核原始数组一致，见 `classpath-clean-replay.json`。first-pass 命令和日志保留，不掩盖此准备问题。

## 2. 同输入与窗口

所有臂 seed=`9139912035078620160`，HS=2，读取同一 `run/config/geogenesis-common.toml` 副本，不改配置。旧骨架有效参数：strength=2、cellWorldSize=100、stripeFreq=1.2、octaves=4、gullyWeight=.65、detail=1、filter seed=1337、rounding=[.11,.12,.1,2]、onset=[.9,1.25,2.8,1.5]。仅显式 `-Dfile.encoding=UTF-8`；无 geogenesis/hydro JVM 属性，无粒子/河湖执行。

| 窗口 | 大小 / step wu | 原点 x,z wu | 每16像素稀疏网格（289点）真实权重 |
|---|---|---|---|
| pure | 257² / .5 | -2799.5,3648.5 | 289点单条目15 PLATEAU_REF |
| transition | 257² / 1 | -2815.5,3200.5 | 242双类型、41单类型、6三类型；主导15/17 |
| junction | 257² / 1 | -2943.5,2944.5 | 183三类型、87双类型、19单类型；14/15/17 |
| interior（初选保留） | 257² / 1 | -2751.5,3520.5 | 240单类型、49双类型；主导15，不冒称全窗纯类型 |

每一窗内部 control/A-proxy/B 的 seed/原点/尺度/参数/光照/色标完全相同。pure 是额外小窗口，以免将初选 interior 边缘过渡冒称纯类型；权重只在网格验证，未声称全65536+点都做了类型纯度断言。所有选窗网格 coverage alpha=0，因此未验证覆盖层激活区及海岸变化策略。窗口类型14=HILLS1_GEO+HILLS2，15=PLATEAU_REF，17=M1_GEO。

## 3. 为什么 A 不是目标契约

源码定位（行号以本轮副本为准）：

- `RidgePlacementProbe` 的 `recipeFields[id]` 是 `toE(kernel.sampleLand(onehot))`，即完整单类型形态场，不是原始 recipe raw 信号。
- wrapper 保留 raw/amplitude，只在 **bias** 加 `delta(singleTypeFinishedField)/E_SPAN`。没有成品高度直接 lerp，但这仍然是成品场差值的外壳修正，不等于把算子接入形态运算图。
- `MorphKernel.sample`/`parameter` 会在 mountain 加权域扭曲之后取 bias，导致 correction 内部再做 onehot kernel 的扭曲，不能保证差分位置与混合后的实际域同源。
- 原 endpoint 检查只测无山地扭曲的 LOW entry0；它和 point/grid 逐位检查、query-order PASS **不能证明** mountain/plateau/clamp/三交汇的配方算子语义正确。
- 修正写回在 kernel clamp 之前，B 在 covered continuous e 上做 delta；截断次序本身也是 A-proxy/B 差异来源。pure 内也有最大6.83 blocks差异，不能全部归因于类型过渡。
- 现有 `MorphNoise.Erosion` 原封保留，尤其17参与时继续执行；本轮不是移除旧纹理后的骨架单算子质量验收，也不是两种算法替换或粒子叠加。

B 的差分采样直接读 `land.e(x±2,z)` / `land.e(x,z±2)`，包含权重变化；A 的每个 onehot 差分没有邻点权重变化。两臂沿用旧 float/±2wu 与 HS 分母公式，不改它来追求视觉好看。配置本轮全类型相同，是参数连续过渡的平凡特例；**未证明将来不同类型强度/尺度/rounding/onset 的连续混合方案**。A/B 真接点需要先明确这些契约，不应靠调参或 Rune 替换来掩盖。

## 4. 数值与实际图检

下表单位为组装后 MC Y blocks；MAE 为相对 control 的平均绝对变化，不是质量评分。

| 窗口 | A-proxy MAE | B MAE | max绝对 A-B |
|---|---:|---:|---:|
| pure | 9.0521 | 9.0528 | 6.8338 |
| transition | 8.0746 | 8.5716 | 73.0875 |
| junction | 5.0909 | 5.5331 | 119.7822 |
| interior | 5.8839 | 5.9516 | 78.9336 |

transition 的 A 相对 control 范围[-65.03,+62.90]，B[-15.10,+30.40]；junction A[-70.58,+128.96]，B[-71.17,+97.81]。这些局部大差不是能靠平均误差忽略的。完整 P99、原始 SHA、前海岸 e 指标见附件 metrics。

**实际使用 view_image 看过** pure/transition/junction 并排、junction 细剖面及光照锥/坑图。观察：两候选都明显增加沟纹，但既有台地锐陡边上出现很密的明暗碎裂、尖齿/窄深槽；pure A/B整体几乎重合，不代表边缘效果已合格。过渡窗与交汇窗中 A-proxy/B 的强局部差异可见于剖面窄峰/窄谷，A-proxy 存在更极端的点，不能视为真正 A 必然失败。没有从图上证明无限世界连续骨架、河网连通或最终游戏品质。

渲染使用固定项目色标[-64,320]、LIGHT=(-.5,-.35,1)，法向=(-gx,-gz,1)，水平采样 blocks=stepWu*2；wu等效高度是既有 MC Y/HS（非另乘 VS），不把 e 直接当 wu。保留配置原竖向映射，不重新标定世界单位。参考光照检查脚本的原 pit fixture 会把负坑 clip 到0，故本轮不冒报其坑测试PASS：另建带符号锥/坑，数值验证并实际看图 `light-fixture.png`；原脚本未改。

## 5. 图、剖面、原始数据与复现

版本化小型附件：[ridge-ab-small-20261105](attachments/ridge-ab-small-20261105)。主要入口：

- `junction/comparison.png`、`transition/comparison.png`、`pure/comparison.png`：组装后同窗口三臂。
- 各窗口 `profiles-detail.png` + `profiles.csv`：三条对应剖面与差值，横轴wu、纵轴MC Y。
- 各窗口 `pre-coast-comparison.png`：covered land e 经同一既有 HeightCurve 映射用于显示，**未海岸组装**，非最终阶段。
- 各窗口 `recipe-delta.png` / `post-delta.png`：同窗共同对称差值色标，数值范围在 metrics 的 deltaColorScaleBlocks。

完整 build 输出每窗包含 `control/recipe/post.f64be`（海床/海岸组装后Y）、`pre-control/pre-recipe/pre-post.f64be`（海岸前e）、各阶段原始差值 f64be；真大端double、先z后x，非float压缩。保留 config、provenance、selection/weights CSV、源码class哈希、命令JSON、编译/执行日志及执行脚本。不复制旧25MB ZIP。

仓库根复现（现有输出请另建隔离目录，不覆盖冻结资产；run.py默认会编译并选择，绝不默认跑大图）：

```powershell
python -u forge-1.20.1-47.4.10-mdk/build/ridge-ab-small-20261105/run.py
python -u forge-1.20.1-47.4.10-mdk/build/ridge-ab-small-20261105/run.py pure 257 0.5 -2799.5 3648.5
python -u forge-1.20.1-47.4.10-mdk/build/ridge-ab-small-20261105/run.py transition 257 1 -2815.5 3200.5
python -u forge-1.20.1-47.4.10-mdk/build/ridge-ab-small-20261105/run.py junction 257 1 -2943.5 2944.5
python -B forge-1.20.1-47.4.10-mdk/_archive/experiment-tools-20261106/tools/render-ridge-placement.py forge-1.20.1-47.4.10-mdk/build/ridge-ab-small-20261105 small
python -B forge-1.20.1-47.4.10-mdk/build/ridge-ab-small-20261105/evidence.py
```

## 6. 推荐与停点

**暂不选择 A/B 生产接点。** B 的输入/差分语义更直接，可保留为后续接点参照；本轮 A-proxy 不能代表真正 A，因此不构成 B 胜过 A 的公平证据。现参数也在台地陡边形成明显碎纹/尖齿，不以“数值PASS”或换算法/调参掩盖。若下一步验证真正 A，需另获批准定义 operator-level 接线与类型连续参数语义；本轮不修改生产来强行完成它。

仅修改诊断探针、渲染工具，新增本实验 build/audit 文件；生产 `src/main`、run配置、默认值、schema、既有基线与起始其他脏文件逐项 before/after 哈希复核见 `boundary-check.json`。HEAD/index不变，无 stash/add/commit/push/回退，无生产接线、无Minecraft或用户验收。
