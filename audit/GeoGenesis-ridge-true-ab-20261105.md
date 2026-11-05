# 真正节点 A / 过渡后 B 小样本补充（2026-11-05）

> 承接用户本轮诊断授权及主代理纠正：实现诊断真正 A 无需另获生产授权。上一轮以需要写诊断代码为由停止不当。本轮继续完成 operator-level 接线及公平小窗。旧 proxy 实验、报告与资产全部原样保留；本报告取代其“需另获批准才可实现诊断 A”的停点判断，但不改变生产禁止边界。

## 用户纠偏与后续授权（2026-11-05）

> 用户原话：『阶地移动、重塑、局部抬升平台不算代价，这正是侵蚀带来的效果。不要搞错了。』
>
> 随后授权：『先保留 A 作为候选，再补纯山地、劣地、覆盖层和海岸的小样本对比图及剖面，看看会不会破坏原地貌。先别接生产，不提交不推送。』

下文原始图检与数值证据保留；任何把阶地位移、平台重塑、局部抬升单独解释成“A 的代价/风险”的旧读法撤销。这些属于预期塑形，不以贴近 control 为验收目标。候选风险限定为地貌身份/主体尺度丢失、人工折痕断崖接缝、域错误或不合理海陆大改，数值偏离本身不是坏证据。后续隔离覆盖验证见 `GeoGenesis-ridge-a-coverage-20261105.md`；A 继续保留候选，未获生产接线授权。

## A 的明确内部阶段

**本轮真正 A = raw 信号节点级插入**，不是 bias-shell，不是成品高度 lerp：

1. 复制当前 `MorphKernel` 到隔离 `build/ridge-ab-true-20261105/DiagnosticRidgeKernel.java`；类重命名，原数学分支保留。只在 `preBad` 的 domain→raw 后、terrace/surface→mountain texture→badlands→amp/bias→clamp 前插入旧 `RidgeValleyErosion`。
2. raw 输入的物理标定适配：用同一节点的 `max(GROUND_E+(raw*amp+bias)*E_SPAN,0)` 供旧算子读取 e/坡度；将返回 e delta 除 `E_SPAN*amp` 转回 raw 信号单位。**结果加在 raw，不写 bias、不在成品场最后加偏移**。保留旧 float h/邻点转换、±2wu差分、分母4f×HS、world坐标evaluatePoint及全部配置。
3. 过渡先按真实连续权重混合 raw/amp/bias/role/clamp 节点参数，再对这个节点应用一次算子。邻点也重新查询真实连续权重；不是过滤18个成品再平均。当前滤镜配置全类型同值（连续参数的平凡特例），未新增类型调参。
4. 域扭曲仅在原 `domain(worldX,worldZ,roleMountain)` 发生一次。差分读取独立的**未过滤 raw 节点函数**；不把 warped 坐标再次传进完整 onehot kernel，无自递归、无 proxy 双扭曲。
5. 后续台地/劣地运算真实消费修改后的 raw。原山地 `MorphNoise.Erosion` 的输入与执行保持不变（它是独立节点，不偷偷将骨架塞进其内部重采样）。因此这次具体比较的是 **raw节点前置 A** 与 **covered continuous e 后置 B**；不声称排除了其他合法 A 阶段，例如 terrace 之后但 shell 之前。

该阶段是可执行、无需修改生产算法的诊断候选。它并非唯一“配方内”的抽象定义，本轮给出具体候选与证据，而非继续把阶段问题当作无法实验的借口。

## 验证与公平性

- scoped javac release17 `-Xlint:all -Werror` 编译两个隔离 Java 文件成功；最终 checks 独立 JVM 成功，失败抛 AssertionError 非零退出。
- zero strength：18个 onehot + 实际 mixed 权重与原生产 MorphKernel **位模式一致**。
- 非山地单类型：独立 raw-node wrapper 与诊断 A 端点一致（误差<1e-12）；山地另用原direction/texture节点和独立“一次domain + raw修正 + 原纹理 + shell”公式对照，避免只测LOW。
- 全18类未过滤节点的独立 float ±2wu差分与 A rawDelta **逐位一致**。
- 15→17退边 eps=1e-3..1e-9，误差单调收敛，最终<1e-6；18类重复/交错查询及真实权重查询顺序一致。
- 仅新增 transition、junction 两个 **129²、step=2wu** 小窗，覆盖上一轮同一256wu物理范围；同seed、原点、HS=2、配置、旧算子、光照与色标。每个窗口control/B与旧257²原始数组 `[::2,::2]` **完全一致**，未换参或算法。
- 当前 main 源212项、旧新编main/probe class清单逐项哈希匹配；复用的是上一轮独立当前main，不是旧baselineclasses。classpath没有旧build/classes/java/main兜底。命令、源码和class SHA均保存。
- 渲染脚本首次使用Python当前版本不支持的 `removeprefix` 失败，改为切片后成功；补充山地独立检查首次编译因lambda与局部变量重名失败，改名后严格编译/检查成功，原失败日志保留。这些没有影响Java原始数组，未把失败记作PASS。

## 数值与看图结论

相对control，单位MC Y blocks：

| 窗口 | true A MAE | B MAE | true A ΔY范围 | B ΔY范围 | max绝对A-B |
|---|---:|---:|---|---|---:|
| transition | 4.4986 | 8.5696 | -5.6048..48.7425 | -15.1027..30.4043 | 36.3157 |
| junction | 4.3516 | 5.5288 | -1.5872..34.5985 | -66.3679..97.8087 | 83.1218 |

已用 **view_image** 实际查看两张三臂并排及 junction 细剖面。真A相对B明显减少了本窗口台地陡边上的密集黑白碎裂/尖齿，保留更完整的台地面与坡面；但剖面显示A会改变台地阶地的位置与宽度（raw改变后被terrace重映射），有局部抬升平台和偏移陡边。B更直接追随已完成的地表坡度，却把既有陡边切成深窄槽/锯齿。不能把A较小MAE或较平滑误称侵蚀质量全面胜出；两个窗口都以正抬升为主，尚未证明“关粒子后的沟壑替代品”已达标。

**本轮接点推荐：在这两个已检窗口、当前参数和保留旧纹理的条件下，优先保留 raw节点内 A 作后续低配候选，不推荐 B 直接落在已完成台地的坡度上。** 这是有范围限制的实验推荐，不是生产接线或用户验收。真正A/B样本已交付，不再用proxy替代；仍需其他地貌（如纯山地/劣地）、覆盖层与海岸激活区才能推广结论。本轮不调参、不换Rune、不叠粒子。

## 精确交付路径

可版本化附件：`audit/attachments/ridge-ab-true-20261105/`

- `true-transition/comparison.png`、`true-junction/comparison.png`：同窗 control / 真A / B。
- 两窗 `profiles-detail.png`、`profiles.csv`：对应三条剖面与数值。
- 两窗 `pre-coast-comparison.png`：covered land e，未海岸组装，按原HeightCurve映射显示；不冒充最终游戏阶段。
- 两窗 `metrics.json`，根 `checks.txt`、`boundary-check.json`、`current-main-source-class-verification.json`、源码与工具ZIP。

完整输出：`forge-1.20.1-47.4.10-mdk/build/ridge-ab-true-20261105/`。每窗control/recipe/post为组装后MC Y f64be，pre-*为海岸前e，另有原始差值f64be、配置、命令JSON、编译/运行日志。`recipe`文件名在本轮明确是真A而非proxy。大端double、先z后x，seed=9139912035078620160；所有臂仅显式file.encoding=UTF-8，无geogenesis/hydro -D。当前参数沿用上一报告，不重复调优。

复现精确Java编译/运行参数见 `compile-command.json`、`run-command.json`；原始输出用CREATE_NEW，复跑应改新输出目录。`check.py`只重编并运行检查，不覆盖数组；`render.py`渲染两窗并逐位验证control/B对应旧子集。复用上一轮经锥/坑检查的负梯度法向、固定[-64,320]色标与stepWu×HS水平距离，不额外乘VS。

## 边界

本补充仅新增build隔离实现及audit记录/小型附件；没有再改诊断目录、生产src/main、run配置、默认/schema。before/after复核包含起始文件、既有proxy资产与所有脏树文件，HEAD/index不变。无stash/add/commit/push/回退，无1401²、昂贵性能扫、粒子/水文、Minecraft；未碰SkeletonOnlyTierProbe。单类节点通过不等于无限世界性质、参数热切换安全或最终产品验收。
