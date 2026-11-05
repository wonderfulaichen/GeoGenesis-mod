# R0-0：基线冻结与影响面确认（2026-11-05）

> **状态：本文冻结 R0-0 当时结果；该阶段没有重构生产代码，没有提交或推送。**
> 后续明确授权的 R0a 已完成，见 [R0a 迁移与验证](GeoGenesis-R0a-ridge-boundary-20261105.md)；本页基线与资产保持不变，以下“本轮”均指 R0-0。
> 用户授权：“按修订后的方案先做 R0-0，把基线和影响面确认好，先别开始 R0a，不提交不推送。”
> 上位界定：[R0 核心参数边界](GeoGenesis-R0-core-boundary-20261105.md)。本页冻结的是当前工作区，而非声称 HEAD 单独包含全部实验。

## 1. 验证结论

| 项目 | 本次结果 | 证明范围 |
|---|---|---|
| 独立核心编译与现有检查 | PASS：BaseCoreCheck、RuneErosionCheck | 无 MC/Forge 核心闭包；包含未默认接线的 Rune 候选检查，不代表选用了它 |
| 当前生产源码隔离编译 | PASS，209 个 Java 源；6 条过时 API 警告 | 未用旧工程 class 替代本次生产源码；不是完整 Forge 打包/实机 |
| 现有 P1CellRenderProbe 独立编译 | PASS，`--release 17 -Xlint:all -Werror` | 不编译整个 diagnostics，绕开已知未完成探针，不改它 |
| P1 1401² 高度复跑 | 与认可资产逐字节相同 | P1 基础路径，非外部侵蚀/水文 |
| P1 完整区块 | 4 块 / 1024 Cell，现有断言 PASS；另冻结 metadata SHA | 高度与参考、非阻塞查询、P1 外部过程旁路及内存 NBT 算法标记回读 |
| 骨架/粒子/tile 当前路径 | 已加载 80 个数组、未加载回退 32 个数组；全部同进程双算逐位相同 | 固定样本的 R0 前现状；不是形态验收、跨区/并发/无限世界证明 |
| 负控 | 骨架强度 0 返回零且不同于有效输出；粒子强度 0 保留输入；塌落 0 改变输出 | 证明所采路径确实执行，不是空样本假绿 |

### P1 固定资产

- seed：`9139912035078620160`，1401×1401，wu 原点 `(-3642.5,-3365.5)`，wu 步长 5，HS=2；采样 MC 坐标在探针入口乘 HS。
- 新输出与原认可资产的 height SHA-256 均为：
  `bea0a47c14d32fa9e2654c77cc6984999004e137e9e6bde8a05d3e2b2a393965`。
- 新冻结的 1024 Cell metadata SHA：
  `9ffcee45d77614eb7e016a8f3747cf0ad4cdcbf78798c5bbdb2517612d15c856`。
  此项是本轮新增的重构前检查点，不冒充已有历史 metadata 验收。
- 用户运行配置与历史冻结配置逐字节一致，SHA：
  `cc5d985f7554e4cfb316708e88373530241b9520e4ce983f745a6defcf50c16f`。
- 原资产未覆盖；副本及本次输出见 [baseline-arrays.zip](attachments/r0-0-20261105/baseline-arrays.zip)。

## 2. 证据与复现输入

| 文件 | 内容 |
|---|---|
| [summary.json](attachments/r0-0-20261105/summary.json) | 总结果、HEAD、参数、P1 SHA、数组数量与改动边界 |
| [manifest.json](attachments/r0-0-20261105/manifest.json) | 本目录证据字节数与 SHA |
| [source-snapshot.zip](attachments/r0-0-20261105/source-snapshot.zip) | 当前工作区源码/构建/工具/配置输入，含未提交代码；隔离采集 Java 与 Gradle 驱动、命令及类路径清单 |
| [baseline-arrays.zip](attachments/r0-0-20261105/baseline-arrays.zip) | P1 大端 f64、高度/参数配置副本；loaded/fallback 骨架与粒子/tile 的大端原生 f32 数组、元数据 |
| [dependency-manifest.json](attachments/r0-0-20261105/dependency-manifest.json) | Gradle 解析出的外部 jar 路径、字节数与 SHA（不含 jar 本体） |
| [class-manifest.json](attachments/r0-0-20261105/class-manifest.json) | 本次新编译 class 的来源清单；class SHA 仅溯源，不作行为等价判据 |
| [before.json](attachments/r0-0-20261105/before.json) | 任务前 888 项源码/工具/配置快照、工作树、HEAD、索引身份与 Java 环境 |
| [core-verify.log](attachments/r0-0-20261105/core-verify.log)、[main-compile.log](attachments/r0-0-20261105/main-compile.log)、[p1-replay.log](attachments/r0-0-20261105/p1-replay.log) | 构建及 P1 原始日志 |
| [loaded-final-arrays.csv](attachments/r0-0-20261105/loaded-final-arrays.csv)、[fallback-final-arrays.csv](attachments/r0-0-20261105/fallback-final-arrays.csv) | 每个数组长度、SHA、双算一致性、非零样本数 |
| [loaded-final.log](attachments/r0-0-20261105/loaded-final.log)、[fallback-final.log](attachments/r0-0-20261105/fallback-final.log)、[config-boundary.txt](attachments/r0-0-20261105/config-boundary.txt) | 样本日志、已加载/回退参数实读、定点负控 |

- HEAD：`0c742b6051fab06bc76517fc697efd585ea6a227`；分支 `feat/hydrology-target`。本来就有未提交实验，见 before；仅 HEAD 不能重建本次状态。
- Java 21.0.11 Temurin，javac `--release 17`；生产独立编译 `-proc:none -g`，采集探针另加 `-Xlint:all -Werror`。
- Java 采样显式 JVM 属性只有 `file.encoding=UTF-8`；**没有任何 geogenesis/hydro 的 `-D`**。各 JVM 完整系统属性及启动参数在数组 ZIP 的 environment.txt。
- 外部 jar 从 Gradle 当前 compileClasspath 解析；运行只用新编译 probe/main 类、当前资源及外部 jar，不包含旧 baseline/classes 或候选编译目录。
- 核心检查复用已有 `verify-base-core.gradle`，用临时 init 脚本把输出改到隔离目录；不改原脚本。生产 209 源隔离 javac，未编译 diagnostics 全集。
- 工作目录统一为 `forge-1.20.1-47.4.10-mdk`。本地完整执行证据在 `build/r0-0-20261105`；该目录被忽略。ZIP 保留源码/命令/参数与数组，外部依赖需另获取并按 manifest 核对，跨机器绝对路径须重新解析；**本轮未做全新 clone/空缓存复现**。

### 采样协议

- 额外采集器只创建于 build 隔离目录；调用当前 `RidgeValleyErosion`、`ErosionEngine` 和 `getErosionTileResultForProbe`，不替代其实现，不把 A/B 接点实验接生产。
- 固定 seed 同 P1；HS∈{1,2}，tile wu 锚点 `(-960,3840)`、`(960,4320)`；世界对齐 48wu。
- 骨架输入按生产几何：121²、spacing=2、起点 tile−56；每点取当前 `terrainEQuick` 并按生产下限钳制。保存输入及 delta，强度 0 仅作诊断负控。
- 粒子内核输入按生产几何：sz=128、pad=9、146² 真邻居高度，单位格距=1wu；岩性关闭/开启分别双算。此独立内核样本不含骨架预叠加；完整组合由 tile full 样本覆盖。
- 已加载配置还采 `full/skel/off` 三个**现状路径**，每条用新 CellGenerator 双算 base/delta/post/discharge；skel/off 的 discharge 为 null，导出空数组。内存开关结束后恢复，不保存配置。
- fallback 是独立未调用 SPEC.setConfig 的 JVM，地形用 TerrainParams.defaults；它与 loaded 的基础参数也有差异，**不能拿两臂数组差值只归因于一个回退字段**。
- 原生 float 数组保存为 `.f32be` 原始位模式，不先转 double 误称保留精度；P1 使用现有大端 `.f64be`，逐行 z/逐列 x。
- 第一次选的 `(-3120,3024)` 在旧地形上是全平海底，粒子不执行，探针断言正确失败。最终样本通过一次有限陆地坡度搜索确定，见 [tile-selection.log](attachments/r0-0-20261105/tile-selection.log)。该失败不是算法回归；本地 loaded.log 保留原栈，最终基线不含无效样本。

## 3. 有效配置与默认差异

下表 spec 定义见 [GeoGenesisConfig.java:731-775](../forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/config/GeoGenesisConfig.java#L731-L775)；主读取见 [RidgeValleyErosion.java:61-81](../forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/erosion/RidgeValleyErosion.java#L61-L81)、[ErosionEngine.java:304-323](../forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/erosion/ErosionEngine.java#L304-L323)。这里列的是未来迁移核对面，不授权本轮改动。

| 配置/输入 | spec / 未加载回退 | 有效读取与时机 | configFingerprint | 后续归属 |
|---|---|---|---|---|
| erosionRidgeEnabled | true / true | fromConfig 每 tile；另查询入口门控 | 有 | R0a |
| erosionRidgeStrength | 2 / 2 | fromConfig→骨架求值 | 有 | R0a |
| erosionRidgeScale | 100 / 100 | 同上，cellWorldSize | 有 | R0a |
| erosionRidgeCellScale | 1.2 / 1.2 | 同上，stripeFreq | 有 | R0a |
| erosionRidgeOctaves | 4 / 4 | 同上 | 有 | R0a |
| erosionRidgeGullyWeight | **0.65 / 0.7** | 同上；已/未加载实读证实 | 有 | R0a，保持双口径，不擅自统一 |
| erosionRidgeRounding | 0.5 / 0.5 | 派生数组前两项 **0.11/0.12**；直接new配置字段为0.1/0.12 | 有 | R0a，区分直接构造与fromConfig |
| erosionRidgeLandRef、Detail | 0.15/0.15、1/1 | 同上 | 有 | R0a |
| normalization/gain/lacunarity/onset/assumedSlope/seed等 | 代码字段/常量 | 算子内部；未进TOML | 无独立项 | R0a记录值，不新加玩家配置 |
| horizontalScale | 2 / fromConfig回退1 | 生产随后用params HS覆盖；static覆盖可绕开此覆盖 | 经TerrainParams身份 | 明确来源，不改单位公式 |
| erosionEnabled | true / true | 每tile门控；查询入口另读 | 有 | R0b |
| erosionXSEnabled | false / false | Cell构造→引擎字段 | **无** | R0b |
| erosionDropsMul | 1 / 1 | 内核每次调用生效；Cell构造读取传入后被丢弃 | 有 | R0b |
| erosionErodeMul | 1 / 1 | 内核读取，但仅传入未消费erodeSpeed；样本1→3输出不变 | **无** | R0b保现状，修生效另议 |
| erosionCascadeStrength | **0.5 / 0.8** | 内核每次调用→cascade | **无** | R0b，不能顺手统一回退 |
| erosionMomentumTransfer | 1 / 1 | 内核每次调用 | **无** | R0b |
| erosionIterations | 2 / 2 | 内核每次调用 | **无** | R0b |
| erosionLrate | 0.1 / 0.1 | 内核每次调用 | **无** | R0b |
| erosionStrength | 1 / 1 | tile→内核；0早退，正值倍率仅进入未消费参数 | 有 | R0b保现状 |
| erosionYieldToRiver、riverEnabled | true / true | GeoGenesisTerrain构造快照；水文回写/门控 | 前者有，后者水文另计 | 暂留门面 |
| erosionRoutingAdaptive | false | 主树无运行读取，诊断HydrologyExperimentEngine有读取 | 无 | 不迁入新核心；不擅删配置 |
| erosionLocalChargeWeight | 1 | 主树无有效读取 | 无 | 同上 |
| tempInfluence/humidityInfluence/continentInfluence | 1 / 1 | 旧Cell逐点读取；P1已有BaseWorldSettings适配 | 不在该指纹 | 列明暂留去向，不扩成气候重写 |

**重要**：正常类初始化会创建非null INSTANCE；“未加载”不等于 INSTANCE=null。真实回退是 `.get()` 抛 IllegalStateException 后走各自辅助方法。直接 `new RidgeConfig()`、`fromConfig(INSTANCE未加载)`、已加载配置是三种不同口径。

## 4. 调用与缓存影响面

| 面 | 已核实事实 | R0a/R0b 前注意 |
|---|---|---|
| 骨架fromConfig | 源码调用方为CellGenerator和诊断RidgePlacementProbe；还有直接new配置的旧探针 | 不把所有探针都当游戏配置；参数快照须值化/复制数组 |
| 粒子入口 | 3个public重载转发到私有实现，另有private locked重载；ErosionScratchProbe按签名反射 | 保留公开入口，迁移后核对反射探针；8/9参默认HS=1 |
| 构造器 | ErosionEngine只保存XS；dropsMul/seed参数未保存 | 不能把“已传参”误当已参数化 |
| static探针覆盖 | PROBE_SKELETON_ONLY、probeRidgeConfig跨实例；setRidgeConfig实例方法实际写static | 双实例隔离是后续要求；本轮未改，采集未使用它们 |
| 生产调用 | extractFromTile/applyTileDelta、erodedHeight、水文bed→erosionDeltaE→getOrGenTile | 实际主路径不是getErosionTile |
| 指纹失效入口 | configFingerprint清缓存位于getErosionTile；该方法主树检索只有定义 | 生产路径目前不会经此入口自动清缓存，不能沿注释假定热改安全；本轮静态结论 |
| 指纹覆盖 | 缺5项内核参数和XS，HS由params参与其他身份 | 运行实测cascade变化但指纹不变；不要把指纹相同当输出相同 |
| 预览重建 | rebuildPreviewIfChanged只比较TerrainParams、seed及侵蚀/骨架两布尔位 | XS/骨架数值/粒子数值修改的触发缺口为源码推导，未做UI实点 |
| P1 | 基础世界直接返回；外部侵蚀delta=0、model关闭 | P1绿不能覆盖R0a/R0b路径，必须保留本页额外样本 |
| 存档 | 只保存TerrainParams和算法标记，不含过程参数 | R0不改存档协议，不擅自把过程字段塞入TerrainParams |
| public配置兼容 | fromConfig/setRidgeConfig及公开可变字段存在诊断消费方 | 不未经检索直接删除公开面；兼容迁移方式在R0a前决定 |

证据位置：[CellGenerator.java:1040-1085](../forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/terrain/CellGenerator.java#L1040-L1085)、[1621-1640](../forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/terrain/CellGenerator.java#L1621-L1640)、[GeoGenesisConfig.java:1073-1102](../forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/config/GeoGenesisConfig.java#L1073-L1102)、[GeoGenesisConfigScreen.java:450-505](../forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/client/GeoGenesisConfigScreen.java#L450-L505)、[GeoGenesisWorldData.java:50-72](../forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/GeoGenesisWorldData.java#L50-L72)。

## 5. 本次定点证伪：只记录，不修正

1. 选定真实陆地输入上，粒子 `str=1` 与 `str=3` 输出逐位相同；`erosionErodeMul=1` 与3亦同。源码内erodeSpeed/depositSpeed未在simulateDrop体内使用，与结果一致。不是“算法完全不执行”，因为强度0早退、cascade0负控都能分辨。
2. cascade=0改变输出，但configFingerprint不变；见config-boundary原始记录。指纹缺项是现状，不在R0-0加字段修正。
3. 双关配置，直接生产tile生成的delta **16384/16384 非零**；对应低通/插值残差。这不是“关侵蚀回B”的目标行为；后续结构迁移先保留现状，行为修复单独验收。
4. 现有SkeletonOnlyTierProbe未完成，不作为本次验证入口、不修复、不删除。整个compileDiagnosticsJava仍有这项已知阻塞；本次只对可信入口与build隔离采集器编译，不宣称全目录绿。

## 6. R0a 准入清单与停点

- 基础P1认可资产、配置与当前源码快照已冻结；骨架真实输入/delta、粒子输入/flat/discharge及tile组合现状有独立基线。
- 后续先保持已加载、未加载回退、直接new配置三种有效值语义；不把默认差异“顺手统一”。
- R0a只迁骨架参数边界，不替换Rune算法、不定A/B接点、不修正正强度无效或双关残差、不启用P1外部过程。
- R0a须明确公开fromConfig/配置字段的迁移办法和数组所有权；独立编译闭包需扩到迁出算子，不能拿本轮不含旧RidgeValleyErosion的核心PASS替代。
- R0b再处理粒子真实参数/门面接线；预览重建/缓存身份改变的范围明确单列，不与数值不变混为一谈。存档策略不在此轮改。
- **本轮不开始R0a**。新机器完整复现、并发与淘汰矩阵、Minecraft实机、完整diagnostics/世界门禁均未验；不扩充为本轮已完成项。

## 7. 改动边界核对

源码、既有tools、构建配置与原运行TOML的任务前888项哈希逐项复核无变化；HEAD和Git索引内容也保持。只新增build下隔离采集脚本/输出、本文及audit证据，更新文档入口和计划进度。

证据ZIP分别约4.8MB（源码快照）和20.3MB（基线数组），不是默认要提交的大文件；当前只落盘保留，**是否把ZIP纳入Git需后续明确选择**。日志/清单与报告可独立审阅；本轮没有暂存、提交或推送。
