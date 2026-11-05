# GeoGenesis 2026-11-05：隔离 B→P 接线与海岸沟纹交接

> 冷启动对象：有经验、不能再询问原作者的工程师。本文是已核对的本地未提交快照，不是发布记录。
> 最新用户裁定：**「这些沟纹先保留，不用修了」**。
> 本次授权仅整理接线、验证、根因证据；**未授权接 model / F / MC，未授权修复、提交、推送、stash 或回退**。交接完成即停。

## 1. 背景、目标与停点

B 是冻结配置下的基础地形；P 是在 B 上执行既有粒子物理内核后的过程高度；F 是尚未接入的后续最终查询阶段。本轮目标是把 B→P 的上下文、tile 几何、按需计算与完成发布隔离出来，并验证旧路径不变、局部粒子图可重复，随后只读定位海岸梳状沟纹来源。

**四个类虽然放在 `src/main`，但新 process 包未被 MC、preview 或其他既有主源码调用。** 本次整理遍历 `src/main/**/*.java`，包外未找到 `worldgen.process`、`ProcessRuntime`、`ProcessContext`、`ParticleTile`、`TileGeometry` 引用。`exactP` 是本轮隔离图的权威计算入口，**不等于游戏已接线，更不能把这些图称作实机验收图**。未改变旧生成器或旧预览缓存版本。

状态与结论：

| 项目 | 状态及边界 |
|---|---|
| 隔离 B→P | 已实现、已运行定向验证；源码地图见第 3 节 |
| 旧基线与内核 | 原日志证实 114 文件逐字节一致、8 个旧真实输入内核用例通过；不是全项目全绿 |
| 本轮局部图 | exactP 201²、90 tiles；最终运行 53.3697456s；没有完整 1401²物理图 |
| 海岸高度断缝 | 所测交叉点未解析到 C0 断缝；不能推广到所有点 |
| C1 折痕 / 沟纹 | 真实存在、保留；不是“已经修好”或“没有数值影响” |
| 48wu 锁定 | 本窗口没有充分证据支持主导性锁定；未排除弱网格影响 |
| 沟纹来源 | 三处海岸确认在原物理内核第一轮切削/沉积阶段已有，第二轮放大；间距自然性未确证 |
| model / F / MC / preview | **未接、未测**；部署和真实存档验收未做 |
| 骨架路线 | 暂停；不要重启低配骨架、替代算子或减粒子路线 |

本地证据总入口：[附件目录](attachments/bp-20261105/)、[复制清单与 SHA256](attachments/bp-20261105/manifest.json)。本文只陈述实际日志/源码与用户裁定，不扩大授权。

## 2. HEAD、脏树归属、环境与配置

### 2.1 不能把已有脏树归到本轮

整理开始核实：分支 `feat/hydrology-target`，HEAD `0c742b6051fab06bc76517fc697efd585ea6a227`，与 B/P 开始及结束记录相同。未查询网络，未据旧交接推断当前 ahead/behind。

B/P [开始 status](attachments/bp-20261105/bp/start-status.txt) 与[结束 status](attachments/bp-20261105/bp/final-status.txt) 逐行比较，新增恰为：

- `src/main/java/com/geogenesis/worldgen/process/` 下 `ProcessContext.java`、`TileGeometry.java`、`ParticleTile.java`、`ProcessRuntime.java`。
- `src/diagnostics/java/com/geogenesis/worldgen/process/` 下 `ProcessRuntimeCheck.java`、`ProcessRenderProbe.java`。
  （⚠ 2026-11-06 这两个已移到 `_archive/diagnostic-probes-20261106/src/diagnostics/java/com/geogenesis/worldgen/process/`，见该目录 README。）
- `tools/verify-process-core.gradle`、`tools/run-process-verification.py`、`tools/render-process-comparison.py`。
  （⚠ 2026-11-06 这三个已移到 `_archive/experiment-tools-20261106/tools/`，见该目录 README。）

以上路径均以 `forge-1.20.1-47.4.10-mdk/` 为前缀，当前均未跟踪。被移走的是临时 `.openbitfun-bp-evidence-path`，去向原 build 的 `evidence-path.txt`；这不是地形修改。

其余大量修改/未跟踪文件早已存在，包含 README、audit、candidate、PreviewDisplay、CellGenerator、ErosionEngine、RidgeValleyErosion、morph、适配器、实验工具等，**不是这次 B/P 修改，更不是本次文档整理修改**。[旧文件哈希清单](attachments/bp-20261105/bp/start-hashes.txt) 排除该临时 marker 后，本次只读重算 **401 个既有文件：零变化、零缺失**；原验证另有 [invariance 记录](attachments/bp-20261105/bp/preexisting-invariance.json)。不要把“相对 HEAD 有差异”误写成“本轮改动”。

本次文档整理只新增本文、`attachments/bp-20261105/` 小证据，更新 `handoff/README.md`；开始状态见 [documentation-start-status.txt](attachments/bp-20261105/documentation-start-status.txt)，非授权文件前置哈希见 [documentation-scope-before.json](attachments/bp-20261105/documentation-scope-before.json)。本次没有 add/commit/push/stash/reset/checkout。

### 2.2 环境与依赖：只读

- 仓库根：`D:\Office software\Development Project\GeoGenesis-mod`；Gradle/Python工具的工作目录通常是其下 `forge-1.20.1-47.4.10-mdk`（下称 MDK）。海岸审计命令从仓库根运行。
- 已运行日志：Windows PowerShell；Temurin Java **21.0.11+10-LTS**、amd64；Gradle **8.8**；Python **3.8.6**；`javac --release 17`。见 [Gradle 日志](attachments/bp-20261105/bp/gradle.log)、[环境记录](attachments/bp-20261105/kernel/environment.txt)。本次没有重装或重新跑版本探针。
- Python绘图依赖 NumPy、Matplotlib；海岸分析还使用 SciPy。脚本 import 是依赖依据，具体已安装包版本未冻结，跨机像素完全一致未验证。
- Java21/JDK、Gradle 缓存位于本机环境，命令使用 PATH 中的 `java/javac/python`；原实际 classpath、源码列表与命令已附，路径不可跨机器直接复用。[依赖清单](attachments/bp-20261105/bp/dependency-manifest.json) 包括 Forge/Minecraft/NightConfig 等本地工件；Gradle 日志的 `--offline` 成功依赖已有缓存，**不证明空机离线可构建**。安装来源/本机 PATH 配置未独立盘点。
- 配置来源 `MDK/run/config/geogenesis-common.toml`，冻结副本 `MDK/build/bp-20261105-054953-3413/config-input.toml`。两者本次 SHA256 都为 `cc5d985f7554e4cfb316708e88373530241b9520e4ce983f745a6defcf50c16f`。**只读、不改值，不把整份 TOML 放交接包**；跨机需用户提供同哈希输入。
- 本图粒子快照：drops=1、erode=1、cascade=.5、momentum=1、iterations=2、lrate=.1、XS=false、strength=1、material=true、seaE=0；seed `9139912035078620160`，HS=2，Y 范围 -64..320。定义全文见 [bp-final-definition.txt](attachments/bp-20261105/bp/bp-final-definition.txt)。JVM 无地形/水文 `-D`，本次导出仅 `-Dfile.encoding=UTF-8`（堆参数另见 argv）。
- 无需账号、远端服务或凭据即可阅读证据；依赖下载/空机网络未测试。

## 3. 关键文件地图与真实语义

下列源文件链接相对仓库；工具只作入口地图，本次未编辑。

| 文件 | 职责 / 必须保留的边界 |
|---|---|
| [ProcessContext.java](../forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/process/ProcessContext.java) | 冻结 Definition、B sampler、HeightCurve、完整 canonical identity、B/G 硬度与 P 高度组装；不读取全局应用配置 |
| [TileGeometry.java](../forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/process/TileGeometry.java) | wu 坐标、负坐标 floor、半开 owner、所需最多四 tile 与对称 blend footprint |
| [ParticleTile.java](../forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/process/ParticleTile.java) | 私有任务数组、真实 B 输入、原 ErosionEngine 调用、有限值验证、delta 裁限及 float 双线性采样 |
| [ProcessRuntime.java](../forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/process/ProcessRuntime.java) | exact/request/peek、singleflight、订阅取消、有界队列/LRU、成功后发布和关闭隔离 |
| [ProcessRuntimeCheck.java](../_archive/diagnostic-probes-20261106/src/diagnostics/java/com/geogenesis/worldgen/process/ProcessRuntimeCheck.java) | 断言型隔离验证，失败进程非零；Observer 仅是包内生命周期故障注入，不是替换地形（2026-11-06 归档，路径已改） |
| [ProcessRenderProbe.java](../_archive/diagnostic-probes-20261106/src/diagnostics/java/com/geogenesis/worldgen/process/ProcessRenderProbe.java) | 加载冻结 TOML，创建 context，逐点 exactP，输出八列 CSV；禁止覆盖已有 CSV（2026-11-06 归档，路径已改） |
| [verify-process-core.gradle](../forge-1.20.1-47.4.10-mdk/tools/verify-process-core.gradle) | 配合 verify-base-core 定向编译与捕获 support classpath/源列表，不编译全部 diagnostics |
| [run-process-verification.py](../forge-1.20.1-47.4.10-mdk/tools/run-process-verification.py) | 接收 MDK 相对证据目录，编译/运行定向 check 或 render；保存 argv/log/exit |
| [render-process-comparison.py](../forge-1.20.1-47.4.10-mdk/tools/render-process-comparison.py) | 只读 exactP CSV，B/P 同色阶、光照 315/45；Coast 与 Cross-tile 使用同一中线，并非两个独立采样 |
| [ErosionEngine.java](../forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/erosion/ErosionEngine.java) | 沿用旧物理内核；本轮没改它，build-only 观察副本用于定位 |
| [R0b 审计](../audit/GeoGenesis-R0b-particle-boundary-20261105.md) | 已有参数边界、未加载回退、缓存与旧行为限制，不把本轮隔离层验证外推到旧生成器 |

### 3.1 数据、单位、identity

- identity 是完整定义，不是短 hash：包含 seed、B算法、terrain/settings、min/maxY、P算法版本、mode、六参数、XS/strength/material/seaE/来源等；执行预算故意不参与。每 runtime 自己持有缓存，无跨上下文共享。
- OFF 仍有独立阶段 identity，但 eP=eB、yP=yB、delta=0，不启动 tile、不经过 floor/cap/softcap。**不能把旧 CellGenerator 双关行为和此 OFF 等同**。
- 非 OFF 输入是 `float(max(B,-.05))`，delta 相对此 floored float 输入，而不是最终 post 高度替换精确 B。post-pre 先按原 float 运算顺序限到 ±.22；tile 双线性也是 float。blend 使用 double，随后加到精确 B，再做旧第二次 softcap，最后 HeightCurve 映射 Y。因此 deltaY 不能用 deltaE 乘固定系数获得。
- `146×146` 输入：owner48、halo40，模拟/保留网格128，额外 pad9；owner 是 wu、不是像素也不是 HS 缩放后的格数。input 起点 owner-49，保留起点 owner-40。硬度 **11×11、16wu 网格**可查询到 inputOrigin+160，超出高度输入末点 +145；还需把岩性采样对齐到16wu格心，不能用146高度 footprint 当作全部依赖。

### 3.2 blend、取消、peek 的非显然约束

- owner 边界两侧各10wu用 smoothstep 的半权重，边界每侧 .5；角点最多四 tile，固定算术顺序。没有八邻居预热；B 查询不反向请求 P。
- bounded workers/queue/completed cache；flight共享但订阅者隔离，一个 cancel 不取消仍有人持有的计算；最后持有者释放才取消 flight。失败/取消不发布缓冲，旧 flight 用对象身份校验，不能覆盖/删除新 flight。close 清空缓存并拒绝后续请求。
- `future()` 给 dependent view；取消该 view 不等于取消实际订阅，需调用 Subscription.cancel/close。
- **peek 比“tile已算完”更严格**：点快照已由 exact 请求记忆，且每个 blend 依赖 tile仍在完成缓存，才 Ready；不采 B、不计算、不排队。OFF 也要先记忆该点，且关闭后 CLOSED。
- `dischargeAvailability()` 只是 OFF / NOT_REQUESTED 文本状态，**可选 discharge 请求 API 未实现**；不要宣称能对外查询流量。
- 外层队列/缓存有界不等于全部内核线程都受控：旧平滑仍用 commonPool，split=8；取消检查包围内核、输入行及硬度请求，无法立即打断所有最内层循环。取消晚到的缓冲仍不能发布。

## 4. 实际验证与局部图口径

本次整理**只检查现存源码和日志**，未重跑下列实验、构建、门禁或 MC。原运行证据：

| 已运行项目 | 实际结果及证据 |
|---|---|
| 定向 Gradle | `verifyBaseCore captureProcessEnvironment` 成功；独立 JVM BaseCore 两 seed/八点、Rune check；[gradle.log](attachments/bp-20261105/bp/gradle.log) |
| Runtime assertions | OFF/geometry/floor/cap/单位、146真输入、121硬度查询、独立内核组装、边/角blend、HS1/2与材质负控、冷暖逆序并发淘汰/peek、订阅取消/晚到重试/隔离、失败重试/背压均通过；[原日志](attachments/bp-20261105/bp/process-runtime-check.log) |
| 旧内核真实输入 | **8 用例 PASS**，early/per-run/legacy/concurrent/negative-control；[原日志](attachments/bp-20261105/bp/old-kernel-check.log) |
| 旧路径 replay | 编译 main、P1、采集器、RidgePlacementProbe，并运行 loaded/fallback/P1；**114/114 字节相同**，不是114个单元测试；[replay.log](attachments/bp-20261105/bp/baseline-replay.log)、[逐文件清单](attachments/bp-20261105/bp/comparison.json) |
| 最终图重复性 | first/final相同、认可 B 格点1681点精确相同；[记录](attachments/bp-20261105/bp/final-render-comparison.json) |

**最终图**为 `bp-final`：201×201，step2wu，origin(-2242.5,-3165.5)，覆盖400×400wu，seed/HS见第2节；90次构建成功，0失败/取消，**53.3697456s**；changed36813、land30546；deltaY [-26.668603939414027, 7.730409117192025]，全窗 RMS **3.9039036417236477Y（约3.90）**。RMS来自 [final-figure.log](attachments/bp-20261105/bp/final-figure.log)，不是目测估计。

此前同数据 `bp-coast-slope` 单独运行 **45.5292509s**；海岸审计引用的是那次原图，不能把45.529记到final性能上。见 [final原日志](attachments/bp-20261105/bp/process-render-bp-final.log)、[前次原日志](attachments/bp-20261105/bp/process-render-bp-coast-slope.log)。耗时是这台机器的局部导出，不是MC性能结论。

图与剖面：[B/P/delta对比](attachments/bp-20261105/bp/bp-final-comparison.png)、[三剖面图](attachments/bp-20261105/bp/bp-final-profiles.png)、[Coast CSV](attachments/bp-20261105/bp/bp-final-profile-Coast.csv)、[Cross-tile CSV](attachments/bp-20261105/bp/bp-final-profile-Cross-tile.csv)、[Mountain-slope CSV](attachments/bp-20261105/bp/bp-final-profile-Mountain-slope.csv)。

失败尝试必须保留：finite-land窗口无陆地，断言退出1，不能列为通过；独立组装测试早先把HS1硬编码对上HS2被捕获，只修测试；invariance初版误纳入本轮移动的marker，修排除项后通过。[summary](attachments/bp-20261105/bp/summary.json)、[finite-land日志](attachments/bp-20261105/bp/process-render-finite-land.log)、[HS失败日志](attachments/bp-20261105/bp/input-check-hs-mismatch-failed.log) 保留事实。

## 5. 海岸审计：测到了什么，没有证明什么

### 5.1 接缝与48wu周期

权威原报告：[seam/report.txt](attachments/bp-20261105/seam/report.txt)。

- 28处 site/axis、epsilon .1/.01/.001 的双侧外推检查：未解析到 C0 高度断缝；最小epsilon海岸deltaY外推跳跃max4.41e-7Y，控制点1.76e-6Y，不能证明数学上处处C0。
- **C1不成立**：coast-z2一侧斜率1.05229349、另一侧.68870573Y/wu，差约-.36359，随epsilon收缩仍在。普通非边界整数插值结点也有更大折痕，**不是48wu独有**；不能全归罪blend，也不能声称无折痕。
- 海岸匹配比较的 |deltaY| 差 .028813，owner bootstrap95区间[-.049294,.121122]；48wu相关/相位拟合没有孤立强峰。有限窗口、依赖样本、匹配残差仍在，**“48锁定证据不足”不等于排除网格影响**。
- 真实沿岸三个弧段沟槽中位间距12/10/10wu，峰宽且随位置变化；第二弧段仍有近48内容。2wu栅格不能判断sub4wu波长，内陆直线剖面不是海岸样本。
- 三条独立tile贡献剖面已在raw tile中看到沟纹；weighted重建deltaE最大误差1.84e-9。只证明blend不是全部沟纹的起源，当时尚无轨迹不能作因果定位。

代表证据：[全窗](attachments/bp-20261105/seam/overview.png)、[海岸细节](attachments/bp-20261105/seam/coast-detail-1.png)、[精确剖面贡献](attachments/bp-20261105/seam/exact-coast-profiles-contributions.png)、[epsilon图](attachments/bp-20261105/seam/epsilon-continuity.png)、[沿岸位置](attachments/bp-20261105/seam/coast-ring-location.png)、[沿岸频率](attachments/bp-20261105/seam/coast-ring-frequency.png)。

### 5.2 原物理内核来源成立，间距自然性不确证

权威原报告：[kernel/report.txt](attachments/bp-20261105/kernel/report.txt)；优先看 [tile1阶段图](attachments/bp-20261105/kernel/tile1-stages.png)、[首次切削及同期轨迹](attachments/bp-20261105/kernel/tile1-onset-cutting-overlay.png)、[机制/时间图](attachments/bp-20261105/kernel/tile1-mechanisms.png)。三海岸中心分别(-1912.5,-2941.5)、(-2070.5,-2997.5)、(-2114.5,-2907.5)，非全世界随机普查。

- 只在build里生成改名观察副本；原表达式、顺序、随机调用、输入、配置不变。记录实际float写前后差，不拿访问次数冒充切削量。
- **3 tile** 每tile flat和discharge各21316个float均逐位相同，input及16384个limitedDelta也全同；切削+沉积+cascade写差的望远镜和误差恰0。[bitwise.csv](attachments/bp-20261105/kernel/bitwise.csv)、[kernel.log](attachments/bp-20261105/kernel/kernel.log) 是实际运行证据，不只是检查源码。
- 全 **7203** 个1wu局部exactP点原序重建double位差0；其中5838点有观察tile贡献。原2wu图每crop625点，合计 **1875** 点八列误差0；[chain-result](attachments/bp-20261105/kernel/chain-result.txt)、[metrics](attachments/bp-20261105/kernel/metrics.json)。
- 第一次达到声明的HP sigma4、相关≥.5且RMS≥最终10%阈值的检查点分别it0-row96/56/40。**第一轮交错液滴切削/沉积已产生沟纹**，不是最后渲染、HeightCurve、blend、插值或delta限幅凭空制造。
- 第一轮ld为0，第二轮反馈伴随RMS放大 **4.704 / 4.622 / 4.018倍**（约4.02–4.70）；没有消融实验，不能唯一拆成discharge、momentum、地形变化或固定出生扫描顺序各自贡献。
- 三个实测海岸mask内cascade实际净写差 **0**，不是“调cascade=0”的参数对照。最终平滑分别削弱HP RMS **4.65% / 7.31% / 7.08%**；沉积平滑这些mask改0点。不能推广成其他地形cascade无影响。
- 原执行顺序是每个格内C→M→F，不是三套整幅独立运行；第二轮重复hash位置但坡度/海门控会变。仅两轮后才最终平滑。M/F hash是算术变换，未证统计独立；注释中的localCharge不能替代实际dis公式。
- 本地floor未钳住海岸，但首tile远处有floor，不能排除间接影响；硬度可调制切削，其梳状主导性未证。出生、brush半径7/4/2、整数Sobel、顺序反馈与B微结构仍可能选择间距。

**结论强度**：物理启发内核里的实际切削/沉积与反馈是这些沟纹的来源，这条来源定位成立；**地球物理自然性、精确间距为何如此、是否含数值尺度影响未确证**。用户决定“保留、不修”是业务裁定，不是科学上证明“没有数值影响”。没有做平面斜坡、平移网格、半径/粒子数/迭代AB。

## 6. Runbook：历史命令与 fresh 复现边界

### 6.1 已实际运行的命令（阅读记录，不要原地重跑）

工作目录 MDK；完整列表见 [commands.txt](attachments/bp-20261105/bp/commands.txt)，每个Java子进程argv另有 `*-command.json`：

```powershell
.\gradlew.bat -I tools/verify-base-core.gradle -I tools/verify-process-core.gradle -PbpEvidence=build/bp-20261105-054953-3413 verifyBaseCore captureProcessEnvironment --offline
python -u tools/run-process-verification.py build/bp-20261105-054953-3413
python -u build/bp-20261105-054953-3413/baseline-replay.py
python -u tools/run-process-verification.py build/bp-20261105-054953-3413 render 201 2 -2242.5 -3165.5 bp-final
python tools/render-process-comparison.py build/bp-20261105-054953-3413 bp-final
```

预期历史输出：Gradle BUILD SUCCESSFUL；PROCESS RUNTIME ALL PASS；PARTICLE CORE PASS baselineCases=8；BYTE COMPARISON PASS 114；导出90 tiles、RMS见第4节。断言、位差、编译失败必须非零；WARN advanced terminal features是原有终端警告。finite-land的exit1是已保留失败，不能为了日志全绿去换参数掩盖。

海岸两组从仓库根执行，以下 `Q` 是报告中的**路径缩写，不是可直接执行的命令变量**：

- seam目录 `MDK/build/bp-seam-audit-20261105-060952/`：`snapshot.py before` → `analyze.py` → `run-exact.py` → `finish.py` → `coast-trace.py` → `snapshot.py after`，详见其 [report](attachments/bp-20261105/seam/report.txt)。
- kernel目录 `MDK/build/coast-kernel-audit-20261105-062214/`：`setup.py` → `run.py` → `analyze.py` → `chain.py` → `followup.py` → `extra.py`（重定向extra.log）→ `finalize.py`；以 `python -u 完整相对路径/脚本.py` 运行，详见 [report](attachments/bp-20261105/kernel/report.txt)。

原compile/exact/kernel/chain退出记录均附在对应附件子目录。seam没有总“ALL PASS”判定；C1失败正是诊断结果。kernel最终守恒/位差检查不是自然性测试。

### 6.2 fresh复现步骤（流程未实际重跑；需后续单独授权）

1. **先保护证据**，不要在上述三个原目录运行脚本。CSV/二进制用CREATE_NEW，部分图/metrics写入会覆盖；历史命令里的classpath绝对路径只适用于原机器。
2. 校验HEAD、当前源码manifest、同哈希TOML、R0-0与R0b原输入资产、工具链/本地Gradle依赖都存在。新clone只有提交历史，**没有这里未提交的process源码和附件**；需显式交付工作树。即使交付本文附件，完整raw/冻结配置/旧class/input仍缺，不承诺开箱即跑。
3. 在 MDK 创建独立 `build/bp-fresh-<唯一时间>/`；经许可复制冻结配置到新目录且核对SHA。定向Gradle命令替换 `-PbpEvidence` 路径，捕获fresh classpath/源列表，随后给 `run-process-verification.py` 第一个位置参数该fresh目录。此工具确实接受该目录；**它没有 `--out` 参数**。render参数按已运行顺序使用201、2、坐标、独立name。
4. 旧 `baseline-replay.py` 的 `out`、路径字符串替换目标硬编码为旧B/P目录，`old`还依赖R0-0目录及原command JSON。必须先复制到fresh目录，审阅并修改这些常量/替换目标，保留只读R0-0 configs引用；不能原样执行后声称在fresh验证。源码数量216的断言属于历史快照，变化时先调查，不能删断言求绿。
5. seam/kernel脚本也不是通用带out工具：虽然部分 `O`由脚本位置推导，`B`和其他输入常量、文件生成/manifest路径可能硬编码。复制小脚本及Java观察工具到新的单一build目录，逐份检查并改路径常量/manifest目标；只引用原只读输入或受保护副本，先生成/编译再按6.1次序运行。**没有验证过这套重定位后的fresh流程**；不要假装 `--out` 可用。附件保留脚本用于审阅，不要直接在handoff/attachments里执行。
6. 对照原字节、位差、identity、样本量和失败退出；耗时不要求逐位相同。绘图只读fresh CSV/数组，不重算另一个物理实现。若缺原始输入/类/依赖，停止并请求资源，不以默认配置冒充原run。

本轮禁止重跑昂贵实验/构建；这里是给后续获得授权者的可审查路线，不是本次新增执行成果。

## 7. 风险、回滚与待办归属

- **已知旧问题不修**：旧构造dropsMul/seed消费、正strength/erode倍率既有行为、loaded cascade=.5与未加载=.8回退差别、双关残差、缓存fingerprint漏项、preview重建缺口、static可变面均按 [R0b](../audit/GeoGenesis-R0b-particle-boundary-20261105.md) 保留。新层OFF identity通过，不代表旧层这些问题解决。`SkeletonOnlyTierProbe`既有未完成编译问题未改未跑，不能拿全diagnostics当现成入口。
- commonPool取消延迟、严格peek可用性、硬度额外footprint、未实现discharge API见第3节；所有读者必须知道这些是接口/资源限制，不是跨区水文完成证明。
- 本次没有部署或存档修改，软件不需要回滚。若撤回本次交接，只能针对本文、附件及索引新增行进行人工审阅后的局部撤回；**禁止整树reset/clean/stash或覆盖其他人的脏文件**。本次未执行任何撤回。
- raw轨迹/二进制、全部阶段图等仍只在被忽略的build中，清build会丢；不随git clone。附件是小型精选+复现源码/日志，不是数百MB全量实验包。附件当前也**未提交，不在history**；manifest仅覆盖复制的原证据，本次整理的scope/status为单独记录。
- 本轮文档生成后，只做本地链接（含anchor）、编码、围栏、哈希与授权范围检查，交主代理复核；**完成即停**。

| 待办 | 归属 / 授权要求 |
|---|---|
| 复核本文及附件真实性与授权边界 | 主代理；不得趁复核改软件 |
| model/F/MC/preview接线、最终图/实机验收 | 用户另行明确授权后才可立方案实施；当前没有开始 |
| discharge请求接口、取消深度、跨机fresh验证 | 后续需用户授权，先明确范围/资源，不是本轮遗留必须补码 |
| 沟纹修复/平移网格或平面斜坡反事实 | **用户已裁定不修、保留；不主动重启** |
| 骨架低配/减粒子/旧倍率与缓存修复 | 不在本轮，暂停，不借交接重新启动 |
| Git提交、推送、保存完整raw | 需用户单独批准；当前无提交/推送，完整raw如需交付另议 |
