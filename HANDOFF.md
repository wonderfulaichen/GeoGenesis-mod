# GeoGenesis 交接文档（2026-08-11）

> 本会话结束，换新对话前交接。上一交接点：git commit `bf54f02`（main 分支，领先 origin/main 4 个提交，未 push）。

## 一、本次会话完成的工作

### 侵蚀引擎修复系列（对照 SebLague/Hydraulic-Erosion `Erosion.cs` 原文逐项核对）

问题链路：堆积成山包 → 局部产坑 + 坑遇海平面冻结 → 深沟壑遍布。全部在 `forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/erosion/ErosionEngine.java` 的 `simulateDrop`/`cascadeLocal`：

1. **接线 `erodeSpeed/depositSpeed` 死参数**：旧代码 `effD=0.15` 硬编码双向（自 b34ebe7 SH 重构起失效）。现在侵蚀 `delta = erodeSpeed·(c_eq−sed)`、沉积 `delta = depositSpeed·(c_eq−sed)`，三尺度 `ERODE_C/M/F`、`DEPOSIT_C/M/F` 常量真正生效。
2. **侵蚀钳制上限含 depthBoost**：`min(delta, max(0,-dh) + depthBoost)`——修复"坑遇海平面冻结"（旧钳制 `max(0,-dh)` 把水下深度增强驱动的侵蚀全砍）。
3. **沉积分支补上坡填平**：`dh > 0 → delta = -min(dh, sed)`（SH 原文 "If moving uphill, try fill up to the current height"）——修复"只挖不填产坑"。
4. **沉积改 4 邻双线性**（`depositNode` helper）：SH 原文 "Deposition is not distributed over a radius (like erosion) so that it can fill small pits"——修复"堆积成山包"。
5. **`INERTIA` 0.005→0.05、`EVAP_RATE` 0.001→0.01**：对齐 SH `inertia=.05f` / `evaporateSpeed=.01f`——液滴保留惯性不钉死梯度线、水量衰减侵蚀收敛（深沟壑主修复之一）。
6. **`erosionIterations` 默认 5→2**（`GeoGenesisConfig` + `run/config/geogenesis-common.toml` 已同步）、**`erosionCascadeStrength` 0.5→0.3**（toml 已同步）：深沟壑修复（SH `numIterations=1` 折中；回退 08-10 "沟壁锐化"调参）。
7. 删除死常量 `RELAX_RATE=0.1`、`DEPOSIT_SPEED=0.02`。
8. **`CACHE_SCHEMA_VERSION` 19→22**（`PreviewDisplay.java`）：侵蚀公式多次改变，磁盘缓存失效重采。

### 探针工具

- 新增 `worldgen/terrain/ErosionPeriodProbe.java`（untracked 时已随提交入库）+ `build.gradle` task：`gradlew runErosionPeriodProbe [seed] [tileCX] [tileCZ]`——打印 NaN 数、32×32 窗口覆盖率、侵蚀前后均值、相邻跳变谱、tile 耗时。**回归验证用**。

### 验证结果（最后状态，seed 12345）

- `compileJava` BUILD SUCCESSFUL
- 探针：NaN=0/2304（base 与 postErosion 双份）、覆盖率 100%、postErosion mean **-0.0254 → -0.0207**（侵蚀量 -19%）、短尺度跳变 d=1~8 回落 6-8%（细沟壑密度下降）
- 未跑 `runClient` 实测（本会话结束点）——**下一步第一优先：实测确认深沟壑/坑/断裂是否改善**

## 二、待办 / 下一步

1. **`runClient` 实测**（或预览）确认：沟壑是否变自然宽浅、坑是否收敛、水下峡谷是否正常、区块断裂是否存在。
2. 若沟壑仍深：`erosionIterations` 2→1（完全 SH 语义）→ 再不行降 `ERODE_C/M/F` 20-30%。
3. **"区块断裂"尚未定位**：code-explorer 核对过 `extractFromTile` blend 全链路（`CellGenerator.applyTileDelta`，smoothstep、border 40 wu > blend 10 wu、邻居懒生成线程安全）——无结构性 bug，疑似深沟壑方块化阶梯的视觉误判。若实测断裂真实存在，优先查 `sampleTileField` 插值与 river carve 交接处，需要用户提供坐标。
4. 最近 4 个本地提交未 push（含 bf54f02），需要时 `git push`。

## 三、关键机制索引（新对话速览）

| 文件 | 职责 |
|---|---|
| `worldgen/erosion/ErosionEngine.java` | 液滴侵蚀引擎（三尺度层叠 + 动量场 + cascade），本次全部改动在此 |
| `worldgen/terrain/CellGenerator.java` | tile 管线：`extractFromTile`（1034 行）/`applyTileDelta`（1096 行，含边缘 blend）/`carveRiverValleys`（889 行）/`ERODE_TILE_*` 常量（331 行） |
| `worldgen/terrain/GeoGenesisTerrain.java` | 地形主入口（149-154 行调 extractFromTile） |
| `worldgen/terrain/StreamTracer.java` | 河流粒子追踪（不改造地形，与侵蚀引擎无公式同步需求） |
| `config/GeoGenesisConfig.java` | 配置（`erosion*` 系列 225-267 行；改默认必须同步 toml，铁律） |
| `client/preview/PreviewDisplay.java` | 预览 + 磁盘缓存（`CACHE_SCHEMA_VERSION`，公式改动必须 bump） |
| `run/config/geogenesis-common.toml` | 运行时配置（**未被 git 跟踪**，本地生效） |

## 四、项目铁律（已与用户确认）

1. **改 Forge 配置默认值必须同步 `run/config/geogenesis-common.toml`**（toml 旧值会覆盖代码默认）。
2. **侵蚀公式/产出改动必须 bump `CACHE_SCHEMA_VERSION`**（否则预览读到旧缓存，出伪影且难排查；15 曾踩坑）。
3. **先源码级参考再改**：动侵蚀行为先对照 SH `Erosion.cs` / TF 原文，不要凭感觉乱调。
4. 确定性与无缝性：所有侵蚀参数必须是世界坐标确定性函数（哈希播种、无全局随机）；改参数不许破坏确定性。
5. `StreamTracer` 只追踪不侵蚀，改侵蚀公式无需双同步。
6. `.codebuddy` 目录是项目数据，不要删。

## 五、工作记忆位置

- `d:\Office software\Development Project\GeoGenesis-mod\.codebuddy\memory\2026-08-11.md`：本日详细记录（三次修复的根因、SH 原文对照、量级结论）
- 新对话开始建议先读该文件 + MEMORY.md
