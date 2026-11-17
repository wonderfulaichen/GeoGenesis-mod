# 洋中脊修复交接包 — 2026-11-18

> 交接范围：**只覆盖洋中脊（mid-ocean ridge）这条工作线**（衔接分界线 + 轴谷宽度），
> 以及冷启动必需的门禁/铁律/回滚知识。水文、侵蚀、陆地的历史见 `handoff/README.md` 索引，
> `HYDROLOGY.md` 是水文唯一事实来源。
>
> 交接人：当前会话 → 接手方：自己 / 另一个 AI agent（**不能随时问原作者**）。
> 本文每条事实都标了出处；标 `未验证` 的不要当真。命令路径以仓库根为基准。

---

## 1. 背景与目标

### 这件事要解决什么

用户从 2026-11-17 起反复反馈程序化生成的洋中脊"不对"，历经约 10 轮返工。
最后两个真问题是：

1. **衔接线**（schema152 修）：洋中脊亮带与两侧海床之间有一条人工分界线。
2. **轴谷过宽**（schema153 修）：中央凹槽比真实洋中脊宽 2~3 倍。

两条都已修复并验证，本次交接是**收尾归档**，不是进行中的任务。

### 做成的标准（用户口径）

- 渲染图 = 实机：预览出图必须走生产路径，否则图不能作验收依据。
- 俯视图 + 剖面图必须一起给（用户两次强调"明明要求俯视图又没做剖面图"）。
-  geological judgment 由 AI 负责（用户明说"我不懂地质学"）：宽度/深度按真实 MAR 数据标定。
- 改地形产出必须升 `CACHE_SCHEMA_VERSION`，否则预览静默复用旧缓存（"改了没生效"）。

### 明确不做的范围

- 不新建任何方块/物品（用户硬约束）——洋中脊只用原版 STONE。
- 不动水文、侵蚀、陆地地形（本次全域 SHA 变化仅限海侧，陆侧 SHA 逐位不变是硬指标）。
- 不碰 `RidgeFormProbe`（沙箱自带一份独立常量副本，与生产解耦，改它不影响产出）。

---

## 2. 当前状态

| 模块 | 状态 | 说明 |
| --- | --- | --- |
| 洋中脊衔接（schema152，commit `8c5cf46`） | ✅ 已验证 | 统一纹理：删脊带专属纹理，全洋底共用 CrustField 丘陵场 |
| 轴谷宽度（schema153，commit `79d11f6`） | ✅ 已验证 | `RIFT_HALF` 60→25，谷宽对齐真实 MAR |
| 提交钩子安全归类（commit `d288bf8`） | ✅ 已归档 | `audit/GeoGenesis-诊断探针安全扫描归类-20261118.md` |
| 门禁 | ✅ 全绿 | `runWorldgenGate` 12m55s，58 条 PASS，无 FAILED |
| P1 对拍 | ✅ PASS | 陆侧 SHA `5dd95cec…` 逐位不变 |

### 当前提交基线（2026-11-18）

```
d288bf8 docs(audit): Mimosa 12 处 medium 标记归类存档
79d11f6 feat(terrain): 轴谷二次收窄——宽度对齐真实 MAR 轴谷（schema153）
8c5cf46 feat(terrain): 洋中脊衔接统一纹理——脊带内外共用一套洋底纹理（schema152）
```

分支 `main`，`CACHE_SCHEMA_VERSION = 153`（
`forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/client/preview/PreviewDisplay.java:817`）。
工作区有 3 个**未跟踪**项（非本次工作，见 §7）：`.zcode/`、`_archive/sandbox-tectonics-removed-20261114/`、
`audit/GeoGenesis-morph重复计算优化-否定结论-20261112.md`。

### 已知的坑（都踩过）

- **衔接线根因不是高度不连续，是质感跳变**：脊带内曾是 blockTex(100wu ±3.8格)/fabric，
  带外是丘陵场(900wu ±1.15格)。海拔 C¹ 连续也救不了质感突变。修法是统一纹理，
  不是调包络——以后别再往脊带加专属纹理。
- **核心常量几个历史错误**（都已修，别回退）：constitution 曾返回 stress 值不是 0-1 成员度；
  `distRaw` 加蜿蜒偏移会让谷消失/裂双谷；`Math.min` 硬钳制产生 C0 折痕（已改 smoothMin）；
  `PerlinRidge=1−|Perlin|` 折叠算子产生成对虚线（已改 Perlin）。
- **上一提交（4e5812f）标题写 schema152 但常量漏升（仍 151）**——那次改动实际没隔离旧预览缓存，
  可能是用户一直觉得"没修复"的原因之一。schema153 已关闭这个窗口。以后提交必须核对常量真升了。
- **给 `distRaw` 加偏移的教训**：`distRaw = max(0,(d2−d1)/2)` 是无符号距离，加偏移不是平移零线，
  wander>0 谷消失、wander<0 裂双谷。要真平移脊轴需有符号距离，TectonicField 不提供。

---

## 3. 环境与依赖

| 项 | 版本 / 值 | 获取或安装方式 |
| --- | --- | --- |
| 运行时 | Java 21（实测 Temurin 21.0.11） | `java -version` 验证过 |
| Gradle | 8.8 wrapper | 用 `gradlew` / `gradlew.bat`，不装全局 gradle |
| 工作区 | `D:\Office software\Development Project\GeoGenesis-mod` | 仓库根，**不是** mdk 目录 |
| gradlew 实际位置 | `forge-1.20.1-47.4.10-mdk\gradlew.bat`（根下没有） | 所有命令先 `cd` 到 mdk 目录 |
| 主配置 | `run/config/geogenesis-common.toml` | 探针无 Forge 环境，须解析它复现游戏行为 |
| P1 冻结输入 | `tools/p1-frozen-input.toml` | P1 探针专用，改了会报前置错误 |
| 诊断探针源集 | `src/diagnostics/java`（独立，`jar` 只打包 `sourceSets.main`） | build.gradle:126 |

- 网络/环境限制：Windows + Git Bash；含中文的提交信息用 `git commit -F file.txt`（UTF-8），
  直接 `-m` 中文会乱码。
- 本机特有：JDK/Gradle 装在 D 盘；`runClient` 配了 `-Xmx6G`。

---

## 4. 关键文件地图

| 路径 | 作用 | 改动时要注意 |
| --- | --- | --- |
| `worldgen/terrain/RidgeProfile.java` | 洋中脊横剖面**确定性纯函数**（谷/肩/翼几何） | 常量：`RIFT_HALF=25` `RIFT_DEPTH=0.090` `SHOULDER_AT=80` `SHOULDER_H=0.085` `FOOT=200`；改任一值必升 schema |
| `worldgen/terrain/OceanFeatures.java` | 脊增量计算：constitution 门控 + distRaw + 剖面 + smoothMin 软限 | 脊增量现在是**纯光滑海拔**，无专属纹理；别加回 fabric/blockTex |
| `worldgen/terrain/CrustField.java` | 洋底基准层：年龄律 + 全洋底统一丘陵场（含 150/75wu 细倍频） | 脊带纹理由此统一提供；沉积平滑 `sediment=1−0.7×smoothstep(0.15,0.75,age)` |
| `worldgen/terrain/morph/MorphOceanAssembler.java` | 海床剖面装配（深海/浅海/岸段）+ oceanDetail | 深海生产走 `crustProfile`（年龄律），旧 Perlin 深海路径不生效 |
| `worldgen/terrain/morph/MorphTerrainSampler.java` | 把特征增量并入 deep 锚；类型分类用 `ridgeEnvelope` | `oceanFeat` 曾恒 null（已修）；分类与几何必须同源 |
| `client/preview/PreviewDisplay.java` | `CACHE_SCHEMA_VERSION`（当前 153） | **改地形产出必升**，注释清单是唯一留痕处 |
| `src/diagnostics/.../RidgeViewProbe.java` | 俯视 + 剖面双视图（洋中脊验收图） | 无参 = 自动扫全球；`-PprobeArgs="cx cz half"` 指定窗口 |
| `src/diagnostics/.../RidgeDecomposeProbe.java` | 四分量分解（age/ridge/residual/final） | 判"像不像海沟"用 |
| `src/diagnostics/.../P1CellRenderProbe.java` | 1401² 高度 SHA 对拍 | `EXPECTED=2dd0cecd…`（陆侧基准 `5dd95cec…`） |
| `build.gradle` | 所有 run 任务 + `runWorldgenGate`（实测 17 个 dependsOn） | 新增探针要挂门禁才有效；判据须 `System.exit(非零)`；探针现数 537 个 tracked `.java`（AGENTS.md 里 446 是旧快照） |

---

## 5. 操作 runbook

以下命令**全部先** `cd "D:\Office software\Development Project\GeoGenesis-mod\forge-1.20.1-47.4.10-mdk"`。
Gradle 用 `gradlew.bat`（Git Bash 下 `./gradlew` 也可）。

### 编译

```
./gradlew compileDiagnosticsJava
```
预期：`BUILD SUCCESSFUL`（约 50s）。

### 出洋中脊验收图（俯视 + 剖面）

```
./gradlew runRidgeViewProbe
./gradlew runRidgeViewProbe -PprobeArgs="100 1750 250"
```
产物：`build/ridge-view/ridge-view.png`（覆盖写）。
第一张自动扫全球选段；第二张为中心 (100,1750) 半宽 250wu 的特写。
判据：轴谷是一条**连续细暗线**，翼部纹理与两侧海床连续无分界线。

### P1 对拍（改地形后必跑）

```
./gradlew runP1CellRenderProbe
```
预期输出（当前基线）：
```
productionCellSha=2dd0cecd5dea62f26d8deea94f0c41866b8c442d31cf995bbd8865453d777428
landCells=585461 landCellSha=5dd95cec86eac1112feacaa63737ea67f267c1ee77cb873bc1ef1a515b7705a8
P1 CELL PASS: HS=2, no external erosion/hydrology, markers roundtrip
```
陆侧 SHA 必须逐位不变；海侧变化属预期时，更新 `EXPECTED` 后再跑一次确认 PASS。

### 全门禁（改产出后必跑）

```
./gradlew runWorldgenGate > /tmp/gate.log 2>&1; echo "exit=$?"
```
预期：`exit=0`，日志末尾 `BUILD SUCCESSFUL`，含 `P1 CELL PASS` 与多条 `ALL PASS`，无 FAILED。
耗时约 12~13 分钟。⚠ 不要用管道包 `| grep -c` 包装后台任务——实测会得到空日志 + 假 exit 1。

### 常见故障

| 现象 | 原因 | 处理 |
| --- | --- | --- |
| 改了地形预览没变化 | 没升 `CACHE_SCHEMA_VERSION`，复用旧磁盘缓存 | 升常量并重出图 |
| P1 报 "height drift" | 配置被调过（非算法漂移）或海侧预期变化 | 先看 `frozenParams` 打印；确认后更新 `EXPECTED` |
| 探针报某个私有成员找不到 | 生产类重构，反射探针签名变了 | 同步改探针（这是预期行为，不是 bug） |
| 中文提交信息乱码 | `git commit -m` 在 GBK 终端 | 用 `git commit -F file.txt`（UTF-8） |

---

## 6. 风险与回滚

### 硬约束（违反会回归，逐条来自 `AGENTS.md`）

1. **改地形产出必升 `CACHE_SCHEMA_VERSION`**——否则预览静默复用旧缓存。
2. **陆侧逐位不变**——海洋改动不能影响陆地（P1 landCellSha 守护）。
3. **渲染图 = 生产路径**——出图不走生产链则图不可作验收依据。
4. **`-D` 开关四条铁律**：同一属性名只一个默认值；一个属性只表达一件事；注释与代码同源；默认值一变必升 schema 并重出 1401² 图对拍。
5. **地质硬约束**：`dist` 不能作噪声坐标（平行带伪影）；禁止值域量化；禁止最近邻配对；reach 处硬切换必 smoothstep 渐隐；折叠算子 `|2n−1|` 禁用。
6. **过滤原版装饰**：`applyBiomeDecoration` 委托 super，新增群系/改 `BiomeClassifier` 时复核 `VanillaDecorationFilter` 仍覆盖团块特征。

### 回滚步骤

最近三个提交各自独立，可单独 revert：
```
git revert 79d11f6   # 退回旧宽谷（schema153）
git revert 8c5cf46   # 退回脊带专属纹理（schema152）
```
⚠ revert 后仍需升 schema（否则旧缓存复活），并重跑 P1 + 门禁。
更早的洋中脊提交（4e5812f / 38e47f2 / d790c0f / a17082c / ac8519f）串联较深，
不建议单独 revert——要回退到某版本优先用 `git switch -c <branch> <commit>` 开分支验证。

### 数据影响面

仅海洋地形产出 + 预览缓存 schema。无方块/物品/配置键变更。陆侧、水文、侵蚀产出零变。

---

## 7. 待办与决策点

| # | 事项 | 优先级 | 卡在谁身上 | 备注 |
| --- | --- | --- | --- | --- |
| 1 | 3 个未跟踪项处置：`.zcode/`（本会话工具目录）、`_archive/sandbox-tectonics-removed-20261114/`（归档）、`audit/GeoGenesis-morph重复计算优化-否定结论-20261112.md` | 低 | 接手方 | 确认是刻意保留还是该提交；别盲目 add |
| 2 | stash@{0} `sandbox-wip-unverified-20261108`（6 文件，SandboxLod 相关，标注 unverified） | 低 | 接手方 | 废弃前先看 `git stash show -p`；未验证内容别直接应用 |
| 3 | 轴谷深度偏深：`RIFT_DEPTH=0.090 × valleyScale[0.55,1]`，谷深比真实偏大，用户未质疑故保持 | 低 | 用户偏好 | 若日后要调，先出剖面图对拍真实 MAR |
| 4 | 脊肩位置偏外：`SHOULDER_AT=80` 占脊带半宽 0.40，真实 0.10~0.30 | 低 | 用户偏好 | 用户已接受整体形状，别擅自改 |
| 5 | Mimosa 提交钩子重复报 12 处 medium（诊断探针） | 低 | 接手方 | 已归类存档 `audit/GeoGenesis-诊断探针安全扫描归类-20261118.md`；同批直接放行，新位置才重查 |

---

## 8. 未验证项

- `RidgeViewProbe` 三个尺度（1000/600/440wu）的截图已目检连续，但**没有量化判据**
  （无自动梯度比值/接缝检测）——最终依据是渲染图，符合项目方法论。
- 轴谷真实尺度换算（1wu ≈ 250~500m）基于脊带半宽 200wu ≈ 50~100km 的标定假设，
  该假设来自历史沙箱记录，未在本会话重新实测。
- schema152/153 出图后**未跑 `runClient` 实机目检**（只跑探针 + 门禁）——用户确认 schema152 已正常，
  schema153 的窄谷是最后产出，实机感受待用户复核。
- `handoff/README.md` 的索引条目未更新收录本文（若需要，接手方补一行）。
