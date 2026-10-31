# 地形形态过渡定稿 — 2026-10-31

## 用户裁定与选择

用户原话：

> 横纹我知道什么问题了，这是类型地形本身的问题。山地地形不是线段距离场实现的嘛。所以必然这样。这个不用理了。我看挺正常的。你认为哪个效果好性能又好的就定稿吧。

**本轮选择 A：固定总宽度 240 块的形态参数过渡，作为独立原型的定稿方案。**

- 保留已经认可的原族、种子、信号层与非线性塑形参数渐变，不改成成品高度直接混合。
- 空间进度 `p=clamp((x-center+120)/240,0,1)`，进入原型内部仅一次 smoothstep；无自适应宽度、无交接偏移。
- 横纹本轮不再追查或修复。现有山地确实包含线段距离场侵蚀纹理，但没有新增证据证明截图中每条横纹的具体成因；不把用户的因果判断包装成新实测结论。
- **定稿范围仅限这批独立两类型原型，不是已经接入整幅地形或生产世界。**

## 为什么选 A，而不是 B/C

三版已有相同窗口的效果图。用户允许按效果和性能综合选择；当前 A 的基本形态演变已获认可，B/C 的局部适配收益不足以支撑未优化版本的高采样代价。

同配置、同点集、每种400点、预热150点、5轮轮换执行顺序，取中位数；无缓存逐点调用。实测如下：

| 类型 | 点集 | A ms | B ms | C ms | B/A | C/A |
|---|---|---:|---:|---:|---:|---:|
| 平原→山地 | 共同过渡区 x=center±110 | 3.0472 | 50.1097 | 1294.3705 | 16.44 | 424.77 |
| 山地→劣地 | 共同过渡区 x=center±110 | 5.5397 | 93.8069 | 2424.0232 | 16.93 | 437.57 |
| 平原→山地 | 整窗口 x=center±600 | 1.9997 | 33.5964 | 1172.2977 | 16.80 | 586.24 |
| 山地→劣地 | 整窗口 x=center±600 | 3.6208 | 59.6433 | 2104.6444 | 16.47 | 581.27 |

原始证据：[perf.csv](attachments/morph-final-20261031/perf.csv)。

解释边界：这是单机局部采样微基准，不是区块生成耗时/游戏帧率。整窗口倍率还包含三版影响包络不同的因素；仅代表当前实现，不能推导为自适应路线在优化后必然如此。旧出图允许行内复用引导，不能用其渲染耗时替代无缓存随机点开销。本轮未进行优化、未测分配量。

## 定稿资产与复现口径

五大类共10种无序两两组合：

| 组合 | 固定240块定稿目录 |
|---|---|
| 丘陵→台地 | [hills-plateau-reference-range-20261031](attachments/hills-plateau-reference-range-20261031/) |
| 平原→丘陵、丘陵→山地、台地→山地、台地→劣地 | [four-pair-morph-20261031](attachments/four-pair-morph-20261031/) |
| 平原→台地、平原→山地、平原→劣地、丘陵→劣地、山地→劣地 | [remaining-five-morph-20261031](attachments/remaining-five-morph-20261031/) |

每组的 `spatial-native.png` / `height-profiles.png` / `stages-panel.png` / `profiles.csv` 的精确路径及SHA256已固定在 [manifest.json](attachments/morph-final-20261031/manifest.json)。本轮核验全部存在、PNG可读；不重新拷贝或覆盖认可图。

- seed=9139912035078620160；平原world、丘陵+11、台地+44、山地+77、劣地+55。
- 台地沿用独立参考声明值域0..0.9修正，不改变共享PlateauCandidate的旧行为。
- 空间图1201×601、step1、center(-140,137)，固定交界x[-260,-20]；五阶段601²、step2。
- 冻结TOML/HeightCurve，`e=max(.02+1.5*h01,0)`；无地形实验JVM属性，编码 `-Dfile.encoding=UTF-8`。
- 单对入口 HillsPlateauMorphProbe；另九对 FourPairMorphProbe 默认原四组、参数 `remaining` 为剩余五组。具体命令口径见各目录 provenance。
- B自适应宽度与C宽度+偏移保留在各自 [adaptive-width](attachments/adaptive-width-20261031/)、[offset-transition](attachments/offset-transition-20261031/) 作为实验对照，**不作为后续默认方案，不擅删**。

## 本轮验证和变更

- 仅新增诊断工具 [MorphChoicePerfProbe.java](../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/terrain/candidate/MorphChoicePerfProbe.java)，原型算法与共享代码未改。
- Java21 `javac --release17 -encoding UTF-8 -nowarn`，冻结classpath，独立输出 `build/terrain-upgrade/morph-final/classes`；运行入口 MorphChoicePerfProbe（冻结TOML、输出CSV两个参数），`-Xmx2g -Dfile.encoding=UTF-8`，正常退出。
- 1158个已有源码/脚本/归档/原始高度文件哈希全部未变；三个现行沙箱基准原始高度哈希未变：coastal `5a8bb721…`、islands `94ba83f7…`、regional `02cebf39…`。
- `git diff --check` 通过。本轮不做提交推送；工作树已有的注释/换行差异及实验文件仍保留。

## 未覆盖与后续边界

这次完成的是五大类代表配方的两两受控实验，**没有完成真实区域邻接、三向交汇、子配方/复合条目全覆盖、海陆与群岛接线或游戏实机验收**。不要以“定稿”名义自动接入或改 `CombineCandidate.regionHeight`；后续整幅接线须单独确认方案。
