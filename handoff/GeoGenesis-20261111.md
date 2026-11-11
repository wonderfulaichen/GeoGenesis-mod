# GeoGenesis 工作交接（2026-11-11）

> **方向**：交出。默认接手方 = 有经验的工程师 / 另一个 AI agent，**不能随时问原作者**。
> **口径**：本文是 2026-11-11 那一刻的快照。HEAD、远端状态、门禁结果都会过期，用前先按 §2 runbook 复跑。
> **证据约定**：凡标「实测」= 本轮真跑过命令；标「用户实机」= 用户口述，我未独立复现；标「未验证」= 没跑过，不要当成事实。
> 所有路径相对工作区根 `D:\Office software\Development Project\GeoGenesis-mod`，命令默认在
> `forge-1.20.1-47.4.10-mdk\` 下执行（`gradlew.bat` 在那里）。

---

## 一、背景与目标

本轮不是一个功能，而是**配置面板重设计的收尾 + 仓库门面与合规补齐**，共 6 个提交。

驱动它的用户口径（原文，按时间顺序）：

1. 「我不是说底板不对，是视野一缩放就超出底板了……数据源统一，这样能保证计算复用。除非我们需要全局预览才不进行高精度计算复用。」
2. 「平面与沙盘模式的切换……应该是一个运镜动画变化关系，你当前其实和做图片过渡没区别。」
3. **批注 #1**：「我定的是可以用2种表现力，我明明说了样式可以不同，但你这分明就不是样式不同。」
4. 「不要上下滚轮页里面再加上下滚轮的控件，这样非常容易误操作。」
5. 「你好像没审查更新相关许可内容吧？我们不是又拿了一些参考项目给你吗？」
6. 「README.md 建议整理一遍，我现在看他完全不像一个模组的仓库描述。更像一个笔记。」

⇒ 三条贯穿性的约束，后续任何改动都要守：
- **一个相机 + 两种样式**：可以有不同的表现力，但视口/数据/输入不能是两套程序。
- **一个页面只留一个滚轮目标**。
- **文档与注释必须和代码同源**（本项目多次因「注释说谎」返工）。

**完成的定义（本轮已达到的部分）**：配置面板可实机使用、切换动画自然、页签在小窗口全可见、预设页单滚轮、README 像模组仓库、许可声明可追溯到文件、世界类型有中文名。用户最后一句确认：「实测正常，目前没发现问题。配置面板先这样推送吧。」

---

## 二、当前状态（最重要，先看这节）

### 2.1 Git 状态（实测 2026-11-11）

```
分支 main
HEAD            654d7fb  feat(lang): 补齐世界类型条目的中英文翻译键
origin/main     18ab92f  docs(readme): 重写为模组仓库门面
ahead/behind    1 / 0     ← 本地领先 1 个提交，未推送
工作区          clean（不含本文与 audit/README.md 的修正，见下）
```

**未推送的提交只有一个**：`654d7fb`。其余五个已在远端：

| SHA | 标题 | 文件数 / 行数 |
|---|---|---|
| `48266e8` | 种子框点不到/重登丢失、预设点 A 应用 B、恢复气候色条拖拽 | 3 files, +130/−14 |
| `e745e02` | 小窗口/高 GUI Scale 页签 2/11→11/11 + 页面内不再嵌滚轮控件 | 9 files, +454/−144 |
| `4b7cbe5` | 预设页三块内层滚动合并为页面单滚轮 | 2 files, +82/−160 |
| `a593254` | 补 yardsite 派生声明 + diorama 核查记录 | 5 files, +223 |
| `18ab92f` | README 重写为模组仓库门面 | 3 files, +212/−158 |
| `654d7fb` | 世界类型条目的中英文翻译键 | 3 files, +2/−2 |

`git show --stat --oneline 48266e8 e745e02 4b7cbe5 a593254 18ab92f 654d7fb` 已实测，上表数字来自该输出。

> ⚠ 本文写作过程中还改了 `audit/README.md:17`（修「待补的 NOTICES」这处已过时的口径漂移），
> 该文件**未提交**。接手时 `git status` 应看到这一处未提交改动。

### 2.2 跑起来了吗 / 半成品在哪

| 项 | 状态 | 证据 |
|---|---|---|
| 编译 | ✅ | `gradlew.bat compileJava compileDiagnosticsJava --offline` → BUILD SUCCESSFUL in 9s（本轮实测） |
| 打包 | ✅ | `gradlew.bat build --offline` → BUILD SUCCESSFUL in 20s；`build/libs/geogenesis-0.0.2.jar` 内两份 lang 均含新键（本轮实测） |
| 6 个探针 | ✅ | `runNavRailLayoutProbe` / `runPreviewCameraProbe` / `runViewTransitionProbe` / `runSandboxGeometryProbe` 全 PASS；`runConfigKeyGuardProbe` CONFIG KEY GUARD PASS；`runConfigPanelCoverageProbe` CONFIG PANEL COVERAGE PASS（本轮实测） |
| 配置面板整体交互 | ✅ **用户实机** | 「实测正常，目前没发现问题」 |
| 切换动画自然、不闪黑屏 | ✅ **用户实机** | 回退 v58 后用户确认；我未独立复现 |
| 预设页单滚轮 | ✅ **用户实机** | 「实测正常」（同上那句） |
| **世界类型中文名** | ⚠ **未实机验证** | 键名由反编译源码推导 + jar 内含键，但没跑 `runClient` 看创建世界界面 |
| 落成动画 | ⏸ **有意暂停**，仍在 jar 内 | `RevealScreenHook.ENABLED` 默认 false，属性 `geogenesis.reveal.enabled`；`client/reveal/**` 仍编译进 jar |
| `runWorldgenGate` 全量 | ⚠ **未在本轮跑** | 已知既存红项 `runCenterlineCrossSectionProbe`，见 §5 |

### 2.3 「一个相机 + 两种样式」到底做到哪一步（别高估）

**已统一**：视口来源。`PreviewCamera`（纯数据、零 MC 依赖）是唯一 window/origin 来源，平面与沙盘都调 `PreviewDisplay.viewCamera(...)`（`PreviewDisplay.java:1264`），差异被显式声明为样式参数（`align` / `pan` / `pitch` / `yaw`）。守门 `runPreviewCameraProbe`。

**已统一**：拾取产物、取色、悬停文案、采样窗口。
- `SandboxRasterizer.pickedWorldX/pickedWorldZ/hasPick()`（`:818-827`）—— 2D 与 3D 算法不同，但**输出统一**。
- `GeoPalette.color(...)` 是唯一取色入口；`HoverLines.forWorld(...)` 是唯一文案源，被 `PlanePainter:440` 与 `SandboxHud:90` 共用。
- 沙盘窗口 = 平面窗口 `texW * scaleBlockPos`，缩放两视图共享 `scaleBlockPos` 一个档位键。

**未统一**：`sandboxMode` 布尔仍在 `PreviewDisplay.java:102`，生产代码里还有 **11 处分支**（`:747/1766/1800/2016/2023/2042/2645/2783/2802` + `PlanePainter.java:494` + `GeoGenesisConfigScreen.java:711`）。输入层（鼠标/键盘）也仍按模式分叉。

> ⚠ `TerrainViewMode.java:12-14` 的注释声称模式只存在于 `PreviewController.rasterizer`，但 **`PreviewController` 这个类全仓不存在**。这是「注释说谎」的现存实例，别照它找代码。

### 2.4 切换动画的最终形态（改之前先读）

`ViewTransition`（纯函数，零 MC 依赖）现在是**两段时间线 + 收拢遮罩**：

| 量 | 值 / 位置 |
|---|---|
| `DEFAULT_DURATION_MS` | `720L`（`ViewTransition.java:59`；慢放档曾设 2880，已恢复） |
| `PHASE_TEXTURE` | `0.42f`（`:112`）—— 第一段：相机完全静止，只做「高精度平面 blit ⇄ 体素正俯视」在完全重合矩形内的交叉淡变 |
| `MASK_DONE` | `0.72f`（`:135`）—— **不是第三个阶段**；`maskPhase = clamp01(texturePhase / 0.72)`（`:146-148`），即椭圆暗区的收拢发生在**第一段内部**，走到 texturePhase 0.72 时合拢完，余下 0.28 仍在第一段里完成材质交接 |
| 第二段 | 只抬 pitch/yaw，relief 恒 1、纹理 alpha 恒 0 |
| 尺度 | 整段用**目标姿态**算一次 `fillScale` 并沿用（逐帧算会「镜头呼吸」） |

**遮罩实现的关键约束**：`PreviewVignette`（`sandbox/PreviewVignette.java`）是**纯数学、零渲染**。`PreviewDisplay.applyVignetteToPlane(float)`（`:1150`）从 `planeSnapshot` CPU 数组重建、走**原有** `texture.upload()` + `g.blit`。

> 💥 不要改成「新建 NativeImage / DynamicTexture / 每帧 register 新纹理」—— v58 这么做过，用户实机「又开始闪烁黑屏才出画面」，已整块 `git revert`（`893eec4`）。本项目历史上另有三次黑屏也全是「从未跑过的 GL 提交路径」：POSITION_TEX_COLOR / POSITION_TEX / RenderType.guiTextured。**只走已验证的 `g.blit` 链路。**

**收尾两处缺陷已修**：`endTransition()`（`PreviewDisplay.java:171`）在画完 ease=1 那一帧之后才调，避免末帧落进稳态分支「跳一下另一个视图」；切回平面时 `restorePlaneTexture()`（`:1184`）从快照拷回并 upload。

---

## 三、环境与依赖

| 项 | 值 | 来源 |
|---|---|---|
| 工作区 | `D:\Office software\Development Project\GeoGenesis-mod`（**不是** mdk 子目录） | `AGENTS.md` |
| JDK | **Java 21**（实测 `openjdk version "21.0.11" 2026-04-21 LTS`） | Gradle 需要；模组面向 MC 1.20.1 |
| Gradle | wrapper `gradlew.bat`（Windows）/ `gradlew`（Unix），Gradle 8.8 | 构建输出 |
| MC / Forge | `minecraft_version=1.20.1` / `forge_version=47.4.10` / `forge_version_range=[47,)` | `forge-.../gradle.properties` |
| 模组版本 / 许可 | `mod_version=0.0.2` / `mod_license=All Rights Reserved` | 同上 |
| 打包产物 | `forge-.../build/libs/geogenesis-0.0.2.jar` | 实测 |
| 运行配置 | `run/config/geogenesis-common.toml`（探针进程无 Forge 环境，若要复现用户调过参的行为须自己解析此文件） | `AGENTS.md` |
| 网络 | 本轮构建/探针全部用 `--offline` 成功 ⇒ 依赖已在本地 Gradle 缓存；**新机器首次构建需要联网**，国内网络可能需要换源（未实测） |
| 忽略目录 | `参考/`、`docs/` 被 `.gitignore` 忽略（**本地可见、不进版本库**） | `.gitignore:43` / `:56` |
| 外部服务 | 无账号/凭据依赖；git 远端 `https://github.com/wonderfulaichen/GeoGenesis-mod.git` | `git remote -v` |

⚠ **判断文件是否存在不要用受 `.gitignore` 过滤的搜索工具**。权威做法 `git status --ignored` 或显式路径列目录。本项目为此踩过坑并写错过 README。

`参考/sources/` 下 9 个被忽略的参考副本：`FreeTerraForged-1.21.1`、`MOBIDICpy-main`、`Realistic-Terrain-Generation-Plus-main`、`TerraForged-0.3.x`、`diorama`、`novoatlas-ref`、`worldgen-master`、`yardsite`、`骨架侵蚀参考项目`。**它们都没有 `.git`** ⇒ 无法用提交号证明版本，见 §6 许可风险。

---

## 四、关键文件地图（改 X 要看 Y）

| # | 要改什么 | 必读 |
|---|---|---|
| 1 | 项目规则、硬约束、命令 | [`AGENTS.md`](../AGENTS.md)（地质 6 条禁令、`-D` 开关四条铁律、探针铁律、文档地图 `:271`） |
| 2 | 水文 | [`HYDROLOGY.md`](../HYDROLOGY.md)（唯一事实来源） |
| 3 | 视口 / 相机 | `client/preview/PreviewCamera.java:56-77`（`originX/Z`、`alignDown`、`copy`、`isTopDown`）；唯一构造点 `PreviewDisplay.java:1264` |
| 4 | 平面 ⇄ 沙盘切换 | `client/preview/ViewTransition.java`（纯函数）+ `PreviewDisplay.java:146/159/171/743/981-1000/1086/1150/1184` + `sandbox/PreviewVignette.java` |
| 5 | 沙盘几何/绘制 | `sandbox/SandboxGeometry.java`（纯函数 798 行）、`raster/SandboxRasterizer.java:595/720/818/896`、`TerrainFieldData.java:160-243` |
| 6 | 导航栏页签布局 | `client/preview/NavRailMetrics.java:105-151`（纯几何）+ `GeoGenesisConfigScreen.java:1181(renderNavRail)/1240(navRailLayout)/1775(hitTestTab)`；**两者必须共用同一 `rect()`** |
| 7 | 收起态浮条按钮 | `GeoGenesisConfigScreen.java:1482(drawCollapsedActions)/1517(collapsedLayout)/1649(hitActionButton)`；左侧竖列、宽 = `PANEL_W_CLOSED` |
| 8 | 种子框 / 预设卡片 | `PresetsPanel.java:144(ensureWidgetsRegistered)/174(getHeight)/81(cardRef)/439(mouseScrolled)`；`GeoGenesisConfigScreen.java:595` 每次 init 调登记，`:1847` 调 `super.mouseClicked` |
| 9 | 页面滚轮 | `GeoGenesisConfigScreen.java:1950` 是唯一页面滚动目标；全部 `ConfigPanel` 子类的 `mouseScrolled` 必须返回 false |
| 10 | 气候 13 阈值 | `mixer/CategoryBar.java`（v70 恢复拖拽，`HIT_PX=6`、`applyDrag`、`nearestBoundary`）。这些键被 `PanelLayout.SPECIALIZED_CLIMATE` 从目录驱动里扣掉，**色条是唯一输入控件** |
| 11 | 世界类型显示名 | `src/main/resources/assets/geogenesis/lang/{zh_cn,en_us}.json` 的 `generator.geogenesis.geogenesis`；键名由反编译 `WorldCreationUiState.describePreset()` + `ResourceLocation.toLanguageKey("generator")` 推出 |
| 12 | 缓存 | `PreviewDisplay.java:641` `CACHE_SCHEMA_VERSION = 129`（实测）。**改地形/水文产出必须升它**，否则预览静默复用旧磁盘缓存 |
| 13 | 落成动画（暂停） | `client/reveal/RevealScreenHook.java:62-63`（`ENABLED` 默认关）；`GeoGenesisServerEvents.java:22` 无 `Dist` 限制却在 `:78` 引用客户端类 ⇒ **专用服务器风险，见 §6** |
| 14 | 许可 | [`THIRD-PARTY-NOTICES.md`](../THIRD-PARTY-NOTICES.md)（55 行，仅 yardsite）+ [`audit/沙盘与预览参考项目许可核查-20261111.md`](../audit/沙盘与预览参考项目许可核查-20261111.md) + [`audit/新版地形第三方许可核查.md`](../audit/新版地形第三方许可核查.md)（顶部 `:5` 有互相排除范围的指针） |
| 15 | README 历史注记 | [`_archive/readme-2026-notes-20261111.md`](../_archive/readme-2026-notes-20261111.md)（88 行，**只读**） |
| 16 | 门禁矩阵 | [`audit/守门门禁清单-2026-09-16.md`](../audit/守门门禁清单-2026-09-16.md) + `audit/原版复用对照审计-2026-09-16.md` |
| 17 | 探针门禁定义 | `forge-.../build.gradle:1625(runConfigKeyGuardProbe)、1639(runWorldgenGate)、2697/2712/2785/2873/2894/2901/2908` |

---

## 五、操作 runbook（本轮命令都实测跑过）

### 5.1 冷启动

```powershell
cd "D:\Office software\Development Project\GeoGenesis-mod"
git status --short                 # 预期：本轮交接会多一处 audit/README.md 未提交（见 §2.1）
git log --oneline origin/main..HEAD # 预期：654d7fb（未推送）
cd forge-1.20.1-47.4.10-mdk
.\gradlew.bat compileJava compileDiagnosticsJava --offline --console=plain
# 预期：BUILD SUCCESSFUL
```

### 5.2 本轮相关验证（一条命令串起来）

```powershell
cd "D:\Office software\Development Project\GeoGenesis-mod\forge-1.20.1-47.4.10-mdk"
foreach ($t in @('runNavRailLayoutProbe','runPreviewCameraProbe','runViewTransitionProbe',`
                 'runSandboxGeometryProbe','runConfigKeyGuardProbe','runConfigPanelCoverageProbe')) {
    .\gradlew.bat $t --offline --console=plain -q
}
```

预期输出（本轮实测）：`PASS` ×4、`CONFIG KEY GUARD PASS`、`CONFIG PANEL COVERAGE PASS`。

其它常用：

```powershell
.\gradlew.bat build --offline                 # 打包 → build/libs/geogenesis-0.0.2.jar
.\gradlew.bat runClient                       # 实机（创建世界界面看中文名要跑这个）
.\gradlew.bat runPreview --args=12345         # 纯 Java 预览窗口，不启动 MC
.\gradlew.bat runWorldgenGate                 # ⚠ 见下方红项
.\gradlew.bat runTransitionFilmstripProbe     # 8 帧分镜 → build/rv2/filmstrip.png
.\gradlew.bat runDefaultOutputFingerprintProbe -PprobeArgs="--compare build\s3-fp.tsv"
```

**`runWorldgenGate` 的既存红项**：`runCenterlineCrossSectionProbe` 是**已知红**（`AGENTS.md:195`、`handoff/GeoGenesis-20261029.md:242-247`、`audit/GeoGenesis-旧侵蚀路径清理清单-20261106.md:143-149` 均记录）。排除它后全绿。**不要**用 `-x runConfigKeyGuardProbe` 跳过配置门禁；也不要把这条红项当新问题抛回用户。

### 5.3 实机验收清单（未做，接手方补）

1. `runClient` → 创建世界界面，确认世界类型显示 **「GeoGenesis 地形」** 而不是 `geogenesis:geogenesis`（`654d7fb` 的实机缺口）。
2. 高 GUI Scale（如 1080p + Scale 4）打开配置屏，确认 11 个页签全可见且都能点中（探针只验了几何，没验渲染文字）。
3. 预设页滚到底、点任意卡片，确认应用的确实是点中的那个；种子框输入、resize 后仍在。
4. 平面 ⇄ 沙盘来回切，确认不闪黑屏、末帧不跳另一个视图、柱体抬升动画每次都播。

### 5.4 提交 / 推送

- Windows 下含中文的提交信息：`git commit -F file.txt`（UTF-8 无 BOM）。本轮 6 次都用了 `build/commitmsg.txt`。
- **不要**在提交信息里写 AI 工具署名。
- `git push origin main` 是快进推送（本轮实测 `4b7cbe5..18ab92f main -> main`），**不要强推**。

---

## 六、风险、坑、回滚

| # | 风险 / 坑 | 事实与对策 |
|---|---|---|
| 1 | **GUI 绘制中新建 GL 对象 ⇒ 黑屏** | 见 §2.4。四次事故（v58 遮罩 + 三条未验证 GL 路径）。只走 `texture.upload()` + `g.blit`。回退方式：`git revert`（`893eec4` 是现成先例） |
| 2 | **含中文的 Java 源码不能用 PowerShell `Set-Content -Encoding UTF8` 改** | PS 5.1 会写 BOM，曾把 `SandboxRasterizer.java` 全部中文损坏、`TransitionFilmstripProbe.java` 首行变 `?package`。用 `Edit` 工具；不得已用脚本时：`[System.IO.File]::WriteAllText(path, txt, New-Object System.Text.UTF8Encoding($false))` |
| 3 | **python 按行范围删代码曾误删 5000+ 字符** | `s.index(start_marker)` 命中第一个 `float topMin` 但 `end_marker` 在很后面，把 `applyGrow`/`sortByDepth`/plate fill/绘制循环全删了。恢复靠 `git checkout -- <file>`。**删大段前先 `git diff` 确认边界** |
| 4 | **同一 `-D` 属性两个默认值 = 半接线事故** | `AGENTS.md`「开关四条铁律」。新增/改开关前全仓 grep 属性名，确认每处读取的默认口径一致 |
| 5 | **改地形产出不升 `CACHE_SCHEMA_VERSION`** | 预览会静默复用旧磁盘缓存，表现为「改了没生效」。当前 `129` |
| 6 | **许可：jar 内尚未内嵌第三方声明** | 源码分发已由 `THIRD-PARTY-NOTICES.md` 满足；jar 里没有。待办见 §7。参考副本无 `.git` ⇒ 上游改许可后现有核查自动失效 |
| 7 | **`GeoGenesisServerEvents` 专用服务器风险（未修、未实测）** | `GeoGenesisServerEvents.java:22` 的 `@Mod.EventBusSubscriber(bus=FORGE)` **无 `Dist` 限制**，`:78` 却调用 `com.geogenesis.client.reveal.RevealScreenHook.resetSession()`。类注释 `:19-21` 说这是刻意的，但从类加载角度，专用服务器上有 `NoClassDefFoundError` 风险。**本轮没动、没在专用服务器复现，标为未验证** |
| 8 | **`参考/` 与 `docs/` 不进版本库** | 新机器 clone 后不存在，`audit/` 是受版本保护的权威副本 |
| 9 | **文档口径会漂移** | 本轮子代理实测发现 `audit/README.md:17` 仍写「待补的 NOTICES」，已在写作本交接时修正（**该修正未提交**）。`TerrainViewMode` 注释里的 `PreviewController` 不存在是另一例 |
| 10 | 带判据的探针必须以 `System.exit(非零)` 承载 FAIL | 否则 Gradle 报 BUILD SUCCESSFUL、门禁名存实亡 |

**回滚锚点**：配置面板最后一次用户认可的状态 = `4b7cbe5`（已推送）。若后续改动出问题，`git diff 4b7cbe5..HEAD -- forge-.../src/main` 可看清差异；纯回退用 `git revert`，不要用 `reset --hard` 改已推送历史。

---

## 七、待办与决策点（接手方最先看）

| # | 事项 | 状态 | 卡在谁 |
|---|---|---|---|
| 1 | **推送 `654d7fb`**（世界类型翻译键） | 本地领先 1，工作区另有 `audit/README.md:17` 的口径修正未提交 | **用户**：上一轮明确说过「推送上去吧」是指到 `18ab92f`，之后新提交未授权推送 |
| 2 | **把 `THIRD-PARTY-NOTICES.md` 放进 jar** | 建议路径 `forge-.../src/main/resources/META-INF/THIRD-PARTY-NOTICES.md`（该目录已存在，现有 `mods.toml` + `licenses/`，无此文件）。放进即进 jar，须重新构建/发版 | **用户**：我曾两次提出「与下次发版一起做」，未获授权 |
| 3 | **世界类型中文名实机验证** | `654d7fb` 只验到 JSON 解析 + jar 内含键 + `build` 成功 | **接手方**：跑一次 `runClient`，看创建世界界面 |
| 4 | **实机验收清单 §5.3 的 2/3/4 项** | 用户只给了整体「实测正常」，高 GUI Scale 下的文字可读性、预设页滚到底点击、动画末帧未逐项复验 | **接手方** |
| 5 | **落成动画暂停是否恢复** | `RevealScreenHook.ENABLED` 默认关；功能整体仍在 jar。用户曾明确「先暂停这个功能？后续专项修复」 | **用户**：不要擅自打开 |
| 6 | **消除剩余 `sandboxMode` 分支** | 11 处生产分支 + 输入层分叉仍在（§2.3）。用户批注 #1 针对的是「样式不同 ≠ 两套程序」，视口已统一，但交互层未收口 | **用户**：要不要继续做这轮重构需明确；不要当成已完成的顺手活 |
| 7 | **专用服务器 `GeoGenesisServerEvents` 的 Dist 风险** | 未修、未实测（§6 #7） | **接手方**：先 `runServer` 复现，确认是否真 `NoClassDefFoundError` 再动 |
| 8 | **其余 5 个参考目录的许可核查** | `MOBIDICpy-main`、`novoatlas-ref`、`Realistic-Terrain-Generation-Plus-main`、`worldgen-master`、`骨架侵蚀参考项目` 未审；不能默认「没用过」 | **用户**：是否开新一轮 |
| 9 | `runWorldgenGate` 既存红项 `runCenterlineCrossSectionProbe` | 已知、与配置面板无关，别当新问题 | 无需动作（除非专项修） |
| 10 | `ColormapListPanel.java:19` 的 `scrollY` 死字段 | v72 后不再被读，可顺手删 | 低优先，非阻塞 |

---

## 八、接手自检（按顺序做）

1. `git status --short` + `git log --oneline origin/main..HEAD` → 确认 §2.1 是否仍成立（**SHA / 未推送数会过期**）。
2. `gradlew.bat compileJava compileDiagnosticsJava --offline` → 应 BUILD SUCCESSFUL。
3. 跑 §5.2 的 6 个探针 → 应全 PASS。
4. 读 [`AGENTS.md`](../AGENTS.md) 全文（尤其地质硬约束、开关四条铁律、用户规则），再读本文 §2.3 与 §2.4，别按注释里的 `PreviewController` 找代码。
5. 若要改世界生成 → 先读 `audit/守门门禁清单-2026-09-16.md` A 节；改地形产出 → 升 `CACHE_SCHEMA_VERSION`。
6. 若要打包发布 → 先处理 §7 #2（jar 内声明），否则 MIT 条件对 jar 仍未满足。
7. 任何「实机正常」的结论都要自己跑 `runClient`，本文标「用户实机」的部分我没有独立复现。

---

*建立于 2026-11-11。本文件是快照，不随代码自动更新；现行事实以代码、`AGENTS.md`、`HYDROLOGY.md` 为准。*