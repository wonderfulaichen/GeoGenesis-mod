# R0a：骨架配置解析迁出核心（2026-11-05）

> 已完成最小迁移及定点验证，停在 R0a；**未开始 R0b，未暂存、提交或推送**。
> 授权：只迁骨架配置到适配端，保持现有算法、默认、回退及公开可变参数面；不扩成不可变快照工程。
> 上位方案：[R0 边界](GeoGenesis-R0-core-boundary-20261105.md)；对拍基准：[R0-0](GeoGenesis-R0-0-baseline-20261105.md)。

## 1. 实际改动与公共面

- 新增 [RidgeConfigAdapter.java](../forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/config/RidgeConfigAdapter.java)：原 `fromConfig(GeoGenesisConfig)` 及三个 Forge helper 迁到 config 适配端。保留读取顺序、double→float、softness 派生公式及 `catch (Exception)`。
- [RidgeValleyErosion.java](../forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/erosion/RidgeValleyErosion.java)：移除配置 import、工厂及 helper；算法不改。`RidgeConfig` 类型、构造、字段、数组可变性及数学入口保留。
- [CellGenerator.java](../forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/terrain/CellGenerator.java) 与 [RidgePlacementProbe.java](../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/terrain/morph/RidgePlacementProbe.java) 更新工厂接线；HS 覆盖位置、static 探针覆盖语义不变。
- [verify-base-core.gradle](../forge-1.20.1-47.4.10-mdk/tools/verify-base-core.gradle) 在原有闭包加入旧 `RidgeValleyErosion`，不加入粒子 `ErosionEngine`。
- build 隔离采集器仅复制到新目录更新调用，R0-0 原件不改。

**外部源码调用需迁移**：`RidgeValleyErosion.RidgeConfig.fromConfig(cfg)` → `com.geogenesis.config.RidgeConfigAdapter.fromConfig(cfg)`。按本轮明确裁定，不保留会引回 Forge 依赖的旧签名，不造 Object/反射/注册器兼容。旧二进制调用方需重编译；没有声称二进制兼容。

任务前全仓文本枚举包含忽略目录、旧 build 采集器、完全限定名及历史工作树，194 处提及保存在 calls-before。当前活动树工厂消费者为 CellGenerator、RidgePlacementProbe 和两份 build 采集器。ChunkBorderProbe、RidgeValleyABProbe、SingleShaperProbe 直接 new 的语义不变；历史 worktree/快照不改。未发现活动树反射查找旧工厂的调用。

本轮**不是深不可变快照实施**。每次工厂调用仍创建独立配置及数组，与原实现一致；公开字段、数组和 static 跨实例风险仍保留，不宣称已解决任务中途热切换或双实例隔离。

## 2. 验证结果

所有新验证使用 `build/r0a-20261105` 新编译 class，不以 R0-0 class 运行新源码。Java 21.0.11，编译 release 17。采样仅显式设置 `file.encoding=UTF-8`，无 geogenesis/hydro `-D`；输入、seed、几何和导出协议同 R0-0。

| 验证 | 结果 |
|---|---|
| 独立核心闭包编译 / BaseCoreCheck / RuneErosionCheck | PASS；闭包含旧 RidgeValleyErosion；classpath 仅普通 Java DFU/fastutil 支持库 |
| 生产源码隔离 javac | PASS：原 209 源 + 新适配器 = 210 源；6 条既有过时 API 警告 |
| scoped 诊断及采集器编译 | P1CellRenderProbe、RidgePlacementProbe、R00BaselineProbe、ConfigBoundaryCheck 通过；诊断采用 `-Xlint:all -Werror` |
| loaded 数组 | **80/80 逐字节一致**，含骨架 input/delta/zero-control、粒子 flat/discharge、full/skel/off tile |
| fallback 数组 | **32/32 逐字节一致**；独立未加载 Forge SPEC JVM |
| P1 高度 | 1401² 大端 f64 与 R0-0/认可资产逐字节一致 |
| Cell 元数据 | 4 块、1024 Cell；探针断言 PASS，metadata SHA 文件逐字节一致 |
| 无 Forge 独立旧算子运行 | RidgeCoreCheck 仅 core-classes，真实基线输入→delta 逐字节一致；零强度负控 PASS |
| 回退/所有权定点 | direct-new、工厂 null、INSTANCE 未加载及 loaded 精确值 PASS；三 helper null 语义、独立数组及 enabled=false 负控 PASS |
| 现状配置证伪复跑 | ConfigBoundaryCheck PASS；不修粒子倍率无效、指纹缺项或双关残差 |

回退口径保留：direct-new / 工厂 null 为 gully=.7、rounding=.1/.12、HS=1；INSTANCE 未加载为 .7、.11/.12、HS=1；本次 loaded 为 .65、.11/.12、HS=2。布尔 helper 的 null 返回 false（不是 fb），异常返回 fb；数值 null 返回 fb。Javadoc 的 ErosionEngine link 原样保留，当前 javac 独立闭包编译未拉入粒子，无证据要求修改链接。

P1 高度 SHA-256：
`bea0a47c14d32fa9e2654c77cc6984999004e137e9e6bde8a05d3e2b2a393965`

Cell metadata SHA-256：
`9ffcee45d77614eb7e016a8f3747cf0ad4cdcbf78798c5bbdb2517612d15c856`

逐数组 SHA、长度及共 **114 项**比较（112 数组 + P1 高度 + metadata SHA 文件）见 [comparison.json](attachments/r0a-20261105/comparison.json)。没有用 class SHA 冒充行为等价。

## 3. 命令与证据

工作目录 `forge-1.20.1-47.4.10-mdk`：

```powershell
.\gradlew.bat -I tools/verify-base-core.gradle -I build/r0a-20261105/capture.gradle verifyBaseCore r00CaptureEnvironment --offline
python -u build/r0a-20261105/replay.py
```

replay 执行 210 源 javac、可信诊断编译与 P1/loaded/fallback 三臂并逐字节比较；定点 boundary、ridge-core、配置证伪完整命令另存 `*-command.json`。路径来自本地 Gradle 解析；换机器须重新解析依赖。

- [summary.json](attachments/r0a-20261105/summary.json)：HEAD/index/非授权文件哈希复核、测试和停点。
- [manifest.json](attachments/r0a-20261105/manifest.json)：本轮小型证据的 SHA/字节数。
- [calls-before.json](attachments/r0a-20261105/calls-before.json)、[changed-files.json](attachments/r0a-20261105/changed-files.json)：任务前枚举及任务后边界。
- [verification-tools.zip](attachments/r0a-20261105/verification-tools.zip)：本轮小型采集脚本、定点测试、classpath/命令及触达源码前后副本；不复制数组大 ZIP。
- 日志与比较清单位于同目录；完整原始数组、本轮 before 全清单和 class 在被忽略的 `build/r0a-20261105`。原始基线仍引用 R0-0 数组 ZIP，不覆盖或重打包约 25MB 资产。

验证准备曾有错误相对路径、遗漏编译 ConfigBoundaryCheck 导致主类未找到；修正后重新执行，最终通过。一次并行小测试触发 log4j latest/debug 日志文件争用警告，boundary 后续顺序重跑通过；未改日志配置或生成代码掩盖。原失败日志保留，不能把失败尝试记为 PASS。

## 4. 授权边界及剩余缺口

未改 ErosionEngine、配置默认、缓存/schema、HS/VS 公式、static 诊断语义、原实验或 P1 外部过程开关；无实机、无真实存档操作、无无关大水文。起始脏树保留，HEAD 与 index 内容不变。

未运行完整 Forge 打包、全 diagnostics、世界门禁、Minecraft 或全新 clone/空缓存复现。已知未完成 SkeletonOnlyTierProbe 未修、未删、未作为验收。未运行 A/B 接点形态实验，RidgePlacementProbe 只编译未启动其渲染。

R0b 的粒子读取、门面开关/缓存身份、预览热改和 static 隔离仍待后续授权；不能据本轮 PASS 宣称 CellGenerator 或整个世界引擎已完全纯 Java。
