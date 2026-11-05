# R0b：粒子参数与必要门面接线（2026-11-05）

> 授权原话：“继续做 R0b，只迁粒子参数和门面接线，保持现有算法、默认值和回退行为不变，已知问题别顺手修。完成后跑基线对拍，先别接新功能，不提交不推送。”
> 本轮按此收窄范围完成；不实施早期方案中的缓存失效修复、static 隔离或新功能。基准为 [R0-0](GeoGenesis-R0-0-baseline-20261105.md)，承接 [R0a](GeoGenesis-R0a-ridge-boundary-20261105.md)。

## 1. 最小方案及实际改动

- `worldgen/erosion/ParticleErosionParams.java`：六个原始值的不可变 record，不扩 TerrainParams、不新增玩家配置。double 保持到原转换点；iterations 仍 int。
- `config/ParticleErosionConfigAdapter.java`：迁出 dropsMul、erodeMul、cascadeStrength、momentumTransfer、iterations、lrate 的六次读取。保持读取顺序及仅捕获 `IllegalStateException` 的内核回退；cascade spec=.5、未加载=.8。没有统一两种默认。
- `ErosionEngine` 新增 `(boolean, Supplier<ParticleErosionParams>, int)` 显式构造：在原 `sz<16 || str<=0` 早退之后，每次 run 调一次 supplier，得到本次不可变快照；不是构造时固化六项配置，也不是全局静态注入。XS 仍只在构造时给定。
- `CellGenerator` 构造改用适配工厂；保留原构造期 dropsMul 的无消费读取（其 ConfigSafe null/异常语义不变）和 XS 读取。三个 run 调用点、开关/强度读取时点、HS 传递和缓存入口均原样保留；引擎内每次 run 通过适配器读取六项。
- 两处平滑此前仅为 `rowsPerTask` 除数引用 `CellGenerator.TILE_PARALLELISM`（8），并未使用它的线程池。现将同一值作为显式构造输入，Cell/适配诊断仍传该常量，旧构造默认8。并行流、任务分片公式、commonPool、中断检查完全不改。不迁线程池/线程策略。移除未使用的 log4j logger/import 后，内核真实依赖闭包为 Java 标准库 + 参数 record。
- `verify-base-core.gradle` 闭包加入 ErosionEngine 和参数载体；不引入 Forge、MC 或预编译生产 class。

### 公共兼容与全部调用面

起始全量文本枚举包含忽略目录、build 和历史 worktree，共632条提及，见 `calls-before.json`。这不是632个活动调用者。

- 活动生产构造：仅 CellGenerator；其3个 run 入口分别为 tile、AB探针门面、并发探针门面，均使用适配工厂后的同一实例，已加载参数不会换成 fallback。
- 旧 `(double,int)` / `(double,int,boolean)` 保留；dropsMul/seed 仍不消费。为保持纯核，这些入口现在明确采用**原未加载默认值**，配置感知外部源码须改用 `ParticleErosionConfigAdapter.create(xs, parallelism)` 或显式 supplier 构造。不是声称外部旧调用的已加载行为完全兼容。
- 公开8/9/10参 run 和 private11 反射签名均保留；8/9参仍 HS=1。private locked 转发保留。
- `ErosionScratchProbe.EngineRef` 和 `GuideErosionBench.Engine` 是活动反射构造消费者：优先新显式构造，提供适配器 supplier；对历史冻结 class 继续选择旧构造。用真实 loaded 输入运行两者，均与 R0-0 flat 相同，避免反射兼容表面通过但数值回退。反射仍仅在原有诊断加载器中，**核心没有 Object/反射桥**。
- `ParticleErosionProbe` 及旧 particle-preeorder 脚本通过上述 EngineRef 间接运行；源码拷贝/减粒子实验仍是非生产实验，本轮不启动或重写其脚本。历史 build 采集器不改，新目录的 R00BaselineProbe/ConfigBoundaryCheck 使用适配工厂。其余命中为常量/硬度辅助、注释、源码实验或冻结历史；均不新增生产接线。

## 2. 验证结果与实际退出

全部新输出在 `forge-1.20.1-47.4.10-mdk/build/r0b-20261105`；新编译 main/probe/core class，无旧 class 替代新源码。Java21.0.11，javac release17。Java采样仅显式 `-Dfile.encoding=UTF-8`，无 geogenesis/hydro 属性；完整 JVM properties 在各臂 environment.txt。

| 验证 | 最终结果 |
|---|---|
| Gradle 独立核心编译 + BaseCoreCheck/RuneErosionCheck | 退出0，包含真实粒子依赖闭包；classpath仅普通Java支持库 |
| production隔离 javac | 退出0，212源，6条既有过时API警告 |
| P1/采集器/RidgePlacementProbe编译及三臂运行 | 均退出0；复用R0a replay，输入始终引用R0-0 |
| 全量逐字节对拍 | **114/114**：loaded80 + fallback32数组 + P1高度 + metadata SHA文件 |
| ConfigBoundaryCheck | 退出0，`config-boundary.txt` 与R0-0逐字节相同 |
| ParticleBoundaryCheck | 退出0；未加载/spec默认/loaded六值；同一已加载引擎run间修改cascade后生效并恢复；XS旧实例不随配置改、新实例生效 |
| ParticleCoreCheck | 退出0，仅core-classes运行，无Forge/MC；真实输入8组flat/discharge逐位对拍、早退零读取、每run一次读取、同实例快照切换、旧构造无消费、双实例并发和cascade负控 |
| ErosionScratchProbe、GuideErosionBench scoped严格编译 | 退出0，`-Xlint:all -Werror` |
| ReflectiveCallerCheck | 退出0，两个反射加载消费者loaded真实输入匹配基线；8/9/10/private11签名解析成功 |

P1高度SHA：`bea0a47c14d32fa9e2654c77cc6984999004e137e9e6bde8a05d3e2b2a393965`。
metadata SHA：`9ffcee45d77614eb7e016a8f3747cf0ad4cdcbf78798c5bbdb2517612d15c856`。

失败尝试如实保留：环境捕获尚未完成时首次replay因缺classpath清单退出1，未启动编译/采样；随后按顺序成功。额外把ParticleErosionProbe加入严格编译时，其既有两个try资源未引用警告在Werror下退出1，未修该源码；去掉该额外入口后两项实际迁移反射消费者严格编译通过。相关首次日志保留，不将失败记为PASS。

复现（mdk为工作目录）：

```powershell
.\gradlew.bat -I tools/verify-base-core.gradle -I build/r0b-20261105/capture.gradle verifyBaseCore r00CaptureEnvironment --offline
python -u build/r0b-20261105/replay.py
python -u build/r0b-20261105/checks.py
```

replay数组目录使用CREATE_NEW，重复运行需选择新的隔离输出目录；不要删/覆盖冻结基准。反射检查精确命令在 `reflective*-command.json`。

## 3. 保留项、边界与证据

未改算法循环、float运算次序、drops/erode倍率的既有传递/消费状态、positive strength无效、both-off残差、指纹漏项、预览重建缺口、static探针/旧RidgeConfig可变面、原TOML、schema、存档、P1外部过程、骨架接点或高低配模式。ConfigBoundaryCheck仍证实正强度/erode倍率不改变输出、cascade负控改变输出但指纹不变、双关16384/16384非零。

没有统一查询/生成缓存失效或声称热改缓存安全；每run快照不等于整个tile/世界热切换事务。CellGenerator仍有Forge、气候/分类、诊断与调度依赖，未来按既定层迁移，不冒充全纯核。

未运行完整Forge打包、全diagnostics、世界门禁、Minecraft/预览UI、真实存档、无关水文实机、全新clone/空缓存复现或历史减粒子实验。SkeletonOnlyTierProbe未改未跑。

小型证据见 [attachments/r0b-20261105](attachments/r0b-20261105)：summary、comparison、before/calls/changed清单、依赖/class清单、原日志和 verification-tools.zip（本轮源码/工具/命令/环境）。原始112数组和P1输出只留build，本包不复制R0-0约25MB数组资产；原R0-0/r0a资产和运行TOML逐项哈希复核。HEAD/index、起始脏树非授权文件保持；无git add/commit/push/stash/回退。
