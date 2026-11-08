---
title: 配置面板重设计（地图为主视野 + 悬浮窗 + 沙盘模式）交接
status: in-progress
created: 2026-11-08
base: ec74931 (0.0.2)
---

# GeoGenesis 配置面板重设计 —— 交接

> **冷启动入口。** 接手方默认是有经验的工程师，**不能随时问作者**。
> 每条事实都落到文件 / commit / 命令输出；未核实的标 `未验证`。
>
> 前序文档不重复：[GeoGenesis-20261104-preeorder.md](GeoGenesis-20261104-preeorder.md)（legacy 清理的上一轮）、
> [yardsite-research-20261108.md](yardsite-research-20261108.md)（★ 参考项目完整研究，必读）。
>
> **本文只写「配置面板重设计」这一轮。** 0.0.2 的 legacy 清理已单独提交（`ec74931`）。

---

## 一、背景与目标

### 1.1 用户需求（原话三轮变化，最终口径有效）

1. ~~「顶栏宽幅预览 + 下方配置区」~~ → 有上下栏
2. ~~「顶部通栏预览」~~ → 仍不是全屏
3. **最终口径（2026-11-08）**：
   > 「左侧做成可折叠收回的悬浮窗口，点击展开。渲染窗口等内容就借鉴我给的新参考项目，
   > 并与原有设计融合，比如平面模式、沙盘模式。」
   > 「实际没有上下栏，类似像一个游戏地图那样全屏显示画面内容，配置那些作为悬浮窗展开/收回。」

### 1.2 参考项目

`参考/sources/yardsite/`（MIT，github.com/CYun2024/yardsite，已 clone）。
**已完整读过的文件**（6 个，3085 行）：

| 文件 | 行数 | 作用 |
|---|---:|---|
| `SiteTerrainRenderer.java` | 1054 | 3D 方柱渲染（投影/可见面/侧墙/深度排序/渲染状态） |
| `SiteMapSampler.java` | 630 | 后台多线程采样（中心向外 / 活数据 / 三态就绪） |
| `YardSiteScreen.java` | 1275 | 界面布局与交互（沙盘铺满 / 右侧面板 / 右下按钮） |
| `BiomePalette.java` | 176 | 群系着色（水按深度渐变 / 坡度光照） |
| `SurfaceProbe.java` | 161 | 不加载区块问生成器地表高度 |
| `SiteField.java` | 106 | 「能画成沙盘的场」接口 |

**未读**（与本轮无关，不必读）：`SiteRevealScreen` / `SiteRevealWorldRenderer`（进世界动画）、
`ChunkSitePlanner`（区块精读）、`SiteReliefField`、`SiteEditorPrefs`、`SurfaceTrend`、
`SiteThreadBudget`、`SiteText`、`YardSiteConfig`、`SiteRevealHandoff`、`SiteBlueprint`、
`BiomeInfo`、`SiteRevealGuard`、`SiteFastLoad`、`SiteSelection`、`SiteRevealState`、
`YardSite`、`YardSiteLog`、全部 `mixin/`、`compat/`、`api/`、`client/`。

### 1.3 目标形态

```
GeoGenesisConfigScreen（全屏）
├── 地形区：占满 (0,0,w,h)，无顶栏无底栏
│   ├── 平面模式：现有 PreviewDisplay（保留图层/缓存/结构叠加/精确管线）
│   └── 沙盘模式：新 SandboxRenderer（移植 yardsite）+ 现有 GeoPalette 颜色
│        ↑ 两种模式共用同一份 TerrainFieldData（切换不重采）
│
└── 左侧悬浮配置窗（可折叠）
    ├── 12 页签（现有 PanelLayout + DomainPanel）
    ├── 种子/图层/沙盘切换 并入页内
    └── 底部两行按钮：应用/保存/重置 + ◀▶图层/大范围/平面-沙盘
```

---

## 二、当前状态

### 2.1 已完成并验证

| 项 | 状态 | 证据 |
|---|---|---|
| **0.0.2 发布** | ✅ 已提交已构建 | `ec74931`；jar 1.49 MB；SHA256 `29BD9467…D73F49` |
| **S0 采样成本实测** | ✅ 完成 | `runPreviewSampleCostProbe`；见 §2.3 |
| **S1 全屏地图 + 悬浮窗** | ✅ 代码完成 | `GeoGenesisConfigScreen`：预览 `(0,0,w,h)`；`panelOpen` 折叠；页签流式换行 |
| **S2 采样管线** | ✅ 改架构完成 | 由「分帧」改为**后台多线程**；`runProgressiveSamplerProbe` PASS |
| **S3 沙盘模式** | ✅ 代码完成，**效果未实机确认** | `SandboxRenderer`（488 行）；见 §2.2 |
| 页签溢出修复 | ✅ 完成 | `computeTabBarBottom()` + `hitTestTab()` 共用同一套换行算法 |
| 沙盘按钮被面板遮住 | ✅ 修复 | 底部按钮区改两行（`BOTTOM_BAR_H = 52`） |
| **采样线程泄漏** | ✅ 修复 | 交接前发现 `close()` 与 `setLargeArea(false)` 未停 `ProgressiveSampler` 的线程池（10 线程×核数÷2），已补 `stop()` 并编译核实 |

### 2.2 ★ 看起来完成但其实没测的部分

| 项 | 说明 |
|---|---|
| **沙盘的视觉效果** | **用户三次反馈"效果完全不对"**。前两版是实现错误（详见 §5），第三版已按 yardsite 重写，但**用户尚未确认第三版效果**。这是本轮最大风险。 |
| **S3 的旋转/平移手感** | 代码按 yardsite 参数实现（`dragRotate` 系数 0.006、pitch 减、右键平移），**未实机验证**。 |
| **S4 打磨** | 未做：坐标轴、水面高亮、采样进度环、种子回显。 |
| **S5 群系颜色编辑** | 未做。现状核实：群系颜色是**硬编码** `GeoPalette.T_BIOME`（`private static final int[]`），只有 `GeoGenesisColorReloadListener` 能**只读**资源包 JSON 覆盖，**无编辑 UI、无写回**。用户以为已有，实为误解（见 §7 决策点 3）。 |
| **平面模式的分帧采样** | S2 改了 `LargeAreaSampler` → `ProgressiveSampler`，但**平面模式也走新采样器**，`PreviewDisplay` 里的精确管线未受影响。此项未做实机对比。 |

### 2.3 S0 实测数据（决定后续档位的依据）

探针：`runPreviewSampleCostProbe`，窗口 3072²，5 轮取中位。

| 网格 | 路径 A：`sampleCellCoarse`（全字段） | 路径 B：`sampleHeightNonBlocking`（纯高度） |
|---:|---:|---:|
| 64×64 | **54.5 ms** | **8.9 ms** |
| 128×128 | 153.2 ms | 77.4 ms |
| 256×256 | 603.2 ms | 344.4 ms |
| 512×512 | 2,365.8 ms | 1,399.3 ms |

**⇒ 结论**：全字段采样在 256 档完全不可行；纯高度快约 6 倍但拿不到群系。
这直接决定了「必须后台多线程一次跑完」，不能分帧。

### 2.4 当前验证状态（本次交接前实测，2026-11-08）

```
compileJava / compileDiagnosticsJava / compileAcceptanceJava   BUILD SUCCESSFUL
runDefaultOutputFingerprintProbe --compare（对 0.0.2 基线）  PASS
runProgressiveSamplerProbe                                   PASS
runSandboxShadeProbe                                         PASS（负对照成立）
runWorldgenGate -x runCenterlineCrossSectionProbe            ALL PASS / BUILD SUCCESSFUL
```

`runProgressiveSamplerProbe` 实测：

| 指标 | 值 |
|---|---:|
| `reset()` 返回（不阻塞渲染线程） | **3.61 ms** |
| 后台采完 128×128（12 线程） | **73.0 ms** |
| 与一次性同步采样对拍 | **16,384 点零差异** |
| 同视口重复 reset | **0.00 ms** |

`runSandboxShadeProbe` 实测（2026-11-08 新增）：

| 指标 | 旧算法 | 新算法 | 判据 |
|---|---:|---:|---|
| 陆地光照饱和率 | **84.9%** | **24.9%** | ≤ 35% |
| 陆地动态范围 P95−P05 | 0.750 | 0.750 | ≥ 0.25 |
| 水面动态范围 P95−P05 | — | **0.023** | ≤ 0.12 |

（负对照：旧算法饱和率 84.9% > 35% ⇒ 探针有牙齿。）

---

## 三、环境与依赖

| 项 | 值 |
|---|---|
| 工作区 | `D:\Office software\Development Project\GeoGenesis-mod`（**不是** mdk 目录） |
| 真工程 | `forge-1.20.1-47.4.10-mdk/` |
| 分支 / HEAD | `main` / `ec74931`（**领先 `origin/main` 3 个提交，未推送**） |
| Java | **21.0.11**（Temurin）；诊断源码用 `javac --release 17 -Xlint:all -Werror` |
| Gradle | wrapper `gradlew.bat`（8.8） |
| 参考项目 | `参考/sources/yardsite/`（MIT，已 clone）；`参考/` 在 `.gitignore` 内 |
| 方案文档 | `.prism/plans/config-panel-map-first-redesign.plan.md`（`.prism/` 被 gitignore） |
| 研究文档 | `handoff/yardsite-research-20261108.md`（**已入库**） |
| 生产配置 | `forge-1.20.1-47.4.10-mdk/run/config/geogenesis-common.toml`（只读，探针须复制副本再加载） |

**无网络限制**（yardsite clone 正常）。**无 secrets**。

---

## 四、关键文件地图（改 X 要看 Y）

### 4.1 本轮新增（**未提交**，接手方第一件事就是认领它们）

| 文件 | 行数 | 作用 | 依赖 |
|---|---:|---|---|
| `src/main/java/com/geogenesis/client/preview/TerrainFieldData.java` | 233 | 扁平 `double[]`/`short[]` 采样容器（替代 `Cell[][]`），含 `height[]`（真实 Y） | 无 |
| `src/main/java/com/geogenesis/client/preview/ProgressiveSampler.java` | 197 | **后台多线程采样器**（核数÷2、优先级 -2、中心向外） | `TerrainFieldData`、`GeoGenesisTerrain` |
| `src/main/java/com/geogenesis/client/preview/SandboxRenderer.java` | 590 | 3D 方柱渲染（移植 yardsite）★ 2026-11-08 改了坡度光照与缩放口径 | `TerrainFieldData`、`GeoPalette`、`SandboxShade` |
| `src/main/java/com/geogenesis/client/preview/SandboxShade.java` | 76 | **坡度光照纯函数**（★ 2026-11-08 新增）—— 渲染器与探针共用同一份实现 | 无 |
| `src/diagnostics/java/com/geogenesis/worldgen/terrain/preview/PreviewSampleCostProbe.java` | 179 | 采样成本基准（S0） | — |
| `src/diagnostics/java/com/geogenesis/worldgen/terrain/preview/ProgressiveSamplerProbe.java` | 140 | 后台采样器行为验证 | `ProgressiveSampler` |
| `src/diagnostics/java/com/geogenesis/worldgen/terrain/preview/SandboxShadeProbe.java` | 240 | **沙盘光照分布验证**（★ 2026-11-08 新增，含量纲错误负对照 + TSV 导出） | `SandboxShade`、`TerrainFieldData` |

**仓库根新增**：`tools/render_sandbox_offline.py` —— 沙盘**离线出图自检**
（numpy 复刻投影/排序/着色，不启动 MC）。见 §五之二 §8.4。

### 4.2 本轮修改（**未提交**）

| 文件 | 改了什么 |
|---|---|
| `src/main/java/com/geogenesis/client/GeoGenesisConfigScreen.java` | 全屏布局、`panelOpen` 折叠、`computePanelRect()`、页签流式换行（`computeTabBarBottom()` / `hitTestTab()`）、底部两行按钮、沙盘切换按钮 |
| `src/main/java/com/geogenesis/client/preview/PreviewDisplay.java` | 沙盘模式分流（`renderSandbox`）、`statusLine()`、拖动/滚轮分流、图层切换按钮位置 |
| `build.gradle` | 新增 2 个探针 task（`runPreviewSampleCostProbe` / `runProgressiveSamplerProbe`） |

### 4.3 未动但有依赖的关键文件

| 文件 | 与本轮的关系 |
|---|---|
| `src/main/java/com/geogenesis/client/preview/LargeAreaSampler.java` | **仍被 `TerrainPreview`（Swing 窗口）与探针使用**，未删。`PreviewDisplay` 已不再用它 |
| `src/main/java/com/geogenesis/client/preview/GeoPalette.java` | 沙盘颜色来源。`T_BIOME`（第 452 行）是硬编码群系色 |
| `src/main/java/com/geogenesis/client/preview/PreviewDisplay.java` 的精确管线 | **S2 的改动不影响它**，平面模式 ≤1:16 仍走精确管线（预览=游戏） |
| `src/main/java/com/geogenesis/config/PanelLayout.java` | 12 页签定义与 `SPECIALIZED_TERRAIN`（已改为空集，因 `TerrainConfigPanel` 被删） |
| `src/main/java/com/geogenesis/config/ConfigKeyCatalog.java` | 70 个配置键的轴/域/可见性 |

### 4.4 已删除（0.0.2，`ec74931`）

11 个生产类 + 8 个 legacy 探针 + 约 100 个配置键。**本轮不要再动 legacy 链**，它已彻底清干净。

---

## 五、★ 沙盘前两版为什么错（**必读，避免重犯**）

用户三次反馈「效果完全不对」。前两版是我的实现错误，**不是参考项目不清楚**。
根因清单（完整版见 `handoff/yardsite-research-20261108.md` §2）：

| # | 错误做法 | yardsite 实际 | 后果 |
|---|---|---|---|
| 1 | `Tesselator`/`BufferBuilder` | `g.bufferSource().getBuffer(RenderType.gui())` + `g.flush()` | **全黑** |
| 2 | 自己推投影公式 | JOML `Matrix3f.rotateZ→rotateX→rotateZ` 摊成行 | **竖条纹** |
| 3 | 4 个侧面全画 | 按 `r[6]/r[7]/r[8]` 符号**只画 2 面** | 遮挡错 |
| 4 | 每格都画侧墙 | `top > max(邻居, 0)` 才画 | **密集黑线** |
| 5 | 水下 `top = -WATER_SINK`（负值） | `ratio = max((Y-base)/span, WATER_SINK)`，**永远为正** | **水面凹成坑** |
| 6 | `e∈[-1,1]` 乘系数 | 用**真实 Y**，`base = seaLevel - 4` | 高差比例全错 |
| 7 | 每帧重算 `span` | `easeReliefSpan` 缓动 | 高度抖动 |
| 8 | `RELIEF = 0.85`（我擅自改的） | **`0.40`** | 柱子过高 |
| 9 | `zoom = 1.0` | **`2.0`** | 沙盘小一半 |
| 10 | `scaleOf = areaSize/2.5` | `areaSize*0.46*zoom*zoomBoost` | 尺寸不符 |
| 11 | `dragRotate` 系数 0.010、pitch 加 | **0.006、pitch 减** | 手感反了 |
| 14 | 分帧渲染线程采样 | **后台多线程**一次跑完 | 永远看到粗档 |

**教训（写给接手方）**：参考项目里这些都是现成且经过实机验证的，不该重新发明。
我连续三轮「边看边改」造成了这个局面；第三轮是**先读完 6 个文件 3085 行**再动手。

---

## 六、操作 runbook

```powershell
# 0) 进入真工程（所有诊断与构建都以此为工作目录）
cd forge-1.20.1-47.4.10-mdk

# 1) 编译三个源集
.\gradlew.bat compileJava compileDiagnosticsJava compileAcceptanceJava

# 2) ★ 指纹对拍：确认 UI 改动没有动到地形产出（对 0.0.2 基线）
.\gradlew.bat runDefaultOutputFingerprintProbe -PprobeArgs="--compare build\s3-fp.tsv"
# 期望：PASS（中文在 GBK 控制台里是乱码，看 "PASS" / "FAIL" 关键字）

# 3) 后台采样器行为验证
.\gradlew.bat runProgressiveSamplerProbe
# 期望：reset≈4ms / 采完≈70ms / 16384 点零差异 / 重复 reset 0ms / PASS

# 3.5) 沙盘坡度光照分布（★ 2026-11-08 新增）
.\gradlew.bat runSandboxShadeProbe
# 期望：陆地饱和率 ≤35% / 动态范围 ≥0.25 / 水面范围 ≤0.12 / 负对照（旧算法）FAIL / PASS

# 3.6) 沙盘离线出图自检（★ 2026-11-08 新增，不启动 MC）
.\gradlew.bat runSandboxShadeProbe "-PsandboxTsv=build\sandbox-terrain.tsv"
python ..\tools\render_sandbox_offline.py build\sandbox-terrain.tsv ..\build\sandbox-fit2.png
# 期望：图里整幅地形完整可见、有山脊沟谷与明暗层次

# 4) 采样成本基准（S0，改动采样路径后必须重跑）
.\gradlew.bat runPreviewSampleCostProbe

# 5) 完整门禁（约 3 分钟）
.\gradlew.bat runWorldgenGate -x runCenterlineCrossSectionProbe
# 期望：ALL PASS / BUILD SUCCESSFUL

# 6) 实机看效果
.\gradlew.bat runClient
```

**打开配置屏的路径**：主菜单 → 创建世界 → GeoGenesis 配置（`CreateWorldScreen` 下）；
游戏内也可开（此时**只读**，不能改参数）。

### 常见故障

| 现象 | 原因与处理 |
|---|---|
| `runWorldgenGate` 在 `runCenterlineCrossSectionProbe` 挂 | **既存红项**，与本轮无关。已在 `37c96ce` 干净 worktree 上复现同一条「2处…9 < 24 FAIL」。用 `-x` 排除 |
| 控制台中文乱码 | PowerShell 是 GBK。输出里找 ASCII 关键字（PASS/FAIL/ALL PASS） |
| 沙盘全黑 | 检查是否用了 `Tesselator`；必须 `g.bufferSource().getBuffer(RenderType.gui())` + `g.flush()` |
| 沙盘像草丛（又细又高） | 柱高没用**真实 Y**；或 `RELIEF` 被改大（应 0.40）；或 `zoom` 不是 2.0 |
| 沙盘水面凹成坑 | `top` 被写成负值；必须 `max((Y-base)/span, WATER_SINK)` |
| 沙盘有密集黑线 | 侧墙没加 `top > max(邻居,0)` 条件 |
| 页签溢出面板 | `computeTabBarBottom()` 与 `hitTestTab()` 必须用**同一套**换行算法 |
| 沙盘按钮点不到 | 按钮必须在**面板内**（底部第二行，`BOTTOM_BAR_H=52`），不能放 `listBottom` 之上 |
| `IllegalStateException: TOML 未真实加载` | 探针须复制 TOML 副本到 `build/` 下，且 `setConfig` 早于任何 `.get()` |

---

## 七、风险、坑、回滚

| 风险 | 影响 | 缓解 |
|---|---|---|
| **沙盘视觉仍不达标** | 用户已三次否定，可能还有第 4 版 | 改动前先**离线出图**自检（用 numpy 复刻投影算法），别再靠实机试错 |
| **`PreviewDisplay` 被改动过多** | 平面模式（核心承诺「预览=游戏」）可能回归 | 每次改完必跑 `runDefaultOutputFingerprintProbe`；门禁全绿 |
| **后台线程与关屏竞态** | 线程泄漏 / 采样打到已关闭的 terrain | `ProgressiveSampler.stop()` 已在 `PreviewDisplay.close()`（第 1832 行起）与 `setLargeArea(false)`（第 726 行起）调用 —— **2026-11-08 交接前修复并核实** |
| **多线程写 `TerrainFieldData`** | 数据竞争 | 各线程写**不同索引**，无共享写；`Cell` 每线程各持一个 `reuse` 实例（已实现） |
| `BaseWorldSampler` 线程安全 | 缓存竞争 | 已用 `AtomicReferenceArray`（`BaseWorldSampler.java:35`），**已核实** |
| **未提交工作量大** | 丢失 | 接手后**第一件事**：`git add -A && git commit`（当前 3 改 + 5 新 + 1 文档） |
| 提交信息里的工具署名 | 用户明确要求**不带任何工具署名** | 本机提交用 `git commit -F <file>`，信息里不要出现 AI 署名 |

### 回滚

- **放弃本轮全部改动**：`git checkout -- .` + `git clean -fd forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/client/preview/ProgressiveSampler.java` 等 5 个新文件。
  ⚠ **不要用 `git stash`**（用户明确禁止，Prism 环境下会出问题）。
- **只回滚沙盘**（保留 S1 全屏布局）：删 `SandboxRenderer.java`，移除 `PreviewDisplay` 里的 `sandboxMode` 分支与 `GeoGenesisConfigScreen` 的 `sandboxBtn`。
- **回滚到 0.0.2 干净态**：`git reset --hard ec74931`（**会丢掉全部未提交改动**，先确认已备份）。

---

## 八、待办与决策点

| 事项 | 卡在谁 | 说明 |
|---|---|---|
| **① 沙盘视觉效果验收** | **用户** | ★ **2026-11-08 已推进**：发现并修掉两个**实质量级错误**（坡度量纲、缩放口径），并建立**离线出图自检**。详见新增的 **§五之二**。仍需跑 `runClient` 打开配置屏 → 点「沙盘视图」→ 截图反馈 |
| ② 沙盘还差什么（用户对比参考图后指出） | 用户 | 待 ① 的反馈。当前已知未做：坐标轴、水面高亮、逐柱淡入（yardsite 有 `drawnAt` + FADE_MS=520）、群系颜色编辑 |
| ③ 群系颜色编辑要不要做 | **用户** | 用户以为「现有渲染窗口就有」——**实为误解**：群系色是硬编码 `GeoPalette.T_BIOME`，只有资源包能只读覆盖，无编辑 UI、无写回。若要做，最小实现见方案文档 §3.7（存 `config/geogenesis/biome_colors.json`） |
| ④ 悬浮窗默认展开还是收起 | 用户 | 现为**默认展开**（`panelOpen = true`）。折叠状态**未持久化**（应存客户端偏好，不写 TOML） |
| ⑤ 沙盘是否支持与平面模式**同视口联动** | 用户 | 现两种模式各自算视口，切换时**不重采**（共用 `TerrainFieldData`），但视口原点不保证一致 |
| ⑥ S4 打磨项 | 用户定范围 | 坐标轴 / 水面 / 进度环 / 种子回显 |
| ⑦ 提交本轮改动 | 接手方 | 验证通过后提交。提交信息**不含工具署名** |
| ⑧ 推送 `main` | **用户授权** | 本地领先 `origin/main` **3 个提交**（0.0.1/0.0.2 等），**未推送**。推送前先确认范围 |
| ⑨ 版本号 | 用户 | 当前 `0.0.2`。本轮若提交，是否升 `0.0.3` 待定 |
| ⑩ 沙盘 `LIGHT_GAIN=8.0` 是否要用户调 | 用户 | 该增益是**实测得出**的（探针量了真实地形的光照分布），不是拍脑袋。但"多少亮度层次最好看"是审美问题，见 §五之二 |

---

## 五之二、★ 2026-11-08 新增：沙盘的两个实质量级错误 + 离线自检

> 交接文档 §五 列的 17 处 yardsite 对照错误都是**"抄错了常数"**。
> 本节是**"抄对了形式但量纲/口径错了"** —— 上一版自查没抓到，因为
> 它们**编译通过、探针全绿、只有肉眼能看出"效果不对"**。

### 8.1 错误 ①：坡度光照的量纲错了（死平 + 死暗）

| | yardsite（`SiteMapSampler.java:553`） | 本项目上一版 |
|---|---|---|
| 公式 | `slope = ΔY / (2·step·HEIGHT_STRIDE)` | `slope = ΔY / 2.0` |
| 分母含义 | **相邻采样点之间的实际方块距离** | 写死 2，注释写「step=1」 |
| 量纲 | 「每块上升多少」 | 无量纲（错） |

我们的细格间距是 `blocksWide/gridW`（3072/128 = **24 格**）
⇒ 坡度被放大 24 倍 ⇒ `light = 1 - slope·0.55` 几乎处处触底/触顶。

**实测铁证**（`runSandboxShadeProbe`，真实生产地形）：

| 算法 | 饱和率 | 动态范围 |
|---|---:|---:|
| 旧（量纲错） | **84.9%**（41.5% 贴死黑 + 43.5% 贴死亮） | 0.750 |
| 新（量纲对） | **24.9%** | 0.750 |

⇒ 旧版整幅沙盘只有"全黑"和"全白"两档，**没有任何中间层次**。

**修法**：坡度定义在**归一化高度** `r = (surfaceY - base)/span` 上
（与柱高同量纲）⇒ 光照强度**与采样间距无关**，缩放视口时明暗关系稳定。

### 8.2 错误 ②：缩放口径抄的是 yardsite 的「近景」口味

yardsite `scale = areaSize * 0.46 * zoom`，`zoom` 默认 **2.0** ⇒ `0.92·areaSize`。
但按默认视角实算，投影后屏幕横向跨度 = `scale·(|r0|+|r1|) = scale·1.401`
⇒ **默认只显示地图中央约 39%**，其余地形全在画面外。

**修法**：改成**「整图恰好铺满视口」= zoom 1.0**。
实现要注意：投影后的平面是**斜着的平行四边形**，其屏幕包围盒比两个方向的
投影跨度都大 ⇒ 必须投影**四个角**算包围盒（不能只算跨度），并用包围盒
**中心**做偏移（`fitScale()` + `fitCenterX/Y`），否则地图会偏下。

### 8.3 顺带修的：海平面硬编码

`SandboxRenderer` 与 `PreviewDisplay.renderSandbox()` 各自硬编码 `63.0`，
而预览海平面是**可配**的（`params.seaLevel()`）⇒ 沙盘基准面可能与平面模式不一致。
现在两处都走 `this.seaLevel`。

### 8.4 ★ 方法论：离线出图自检（交接 §七 要求，本轮落地）

`tools/render_sandbox_offline.py` —— 用 numpy 复刻渲染器的投影/排序/着色算法，
**不启动 Minecraft** 就能出图。

```powershell
# 1) 导出现实地形数据（生产采样路径）
.\gradlew.bat runSandboxShadeProbe "-PsandboxTsv=build\sandbox-terrain.tsv"
# 2) 离线出图
python ..\tools\render_sandbox_offline.py build\sandbox-terrain.tsv ..\build\sandbox-fit2.png
```

本轮**靠它发现了错误 ②**（第一版按"投影跨度"算，结果地图仍然溢出；
改成包围盒才对）。⇒ 这就是交接 §七「别再靠实机试错」的具体落地方式。

### 8.5 新增门禁

`runSandboxShadeProbe`（`build.gradle` 已注册）：

| 判据 | 阈值 |
|---|---|
| 陆地光照饱和率 | ≤ 35% |
| 陆地动态范围 P95−P05 | ≥ 0.25 |
| 水面动态范围 P95−P05 | ≤ 0.12 |
| **负对照：旧算法必须 FAIL** | 饱和率 >35% **或** 范围 <0.25 |

⚠ **负对照是关键**：没有它，探针可能"测不出问题"却照样 PASS
（交接文档的教训：「测试要有牙齿」）。

---

## 九、给接手方的话

1. **先读 `handoff/yardsite-research-20261108.md`**（174 行），它把参考项目的研究结论、
   我 17 处错误清单、常数对照表、融合取舍全写了。读完再动代码。
2. **不要重写已正确的部分**：`ProgressiveSampler`（后台多线程）与 `SandboxRenderer` 的
   投影/排序/渲染状态部分已与 yardsite 对齐，改动前先读 §5 的错误清单。
3. **改动前先离线出图自检**。前两版沙盘都靠实机试错，浪费了两轮。
4. **每次改完必跑指纹**。地形产出必须逐位不变 —— 这是本项目的铁律，也是唯一可靠的回归信号。
5. 用户对**视觉质量**要求高且会直接否定；截图比文字描述有效得多。

---

## 十、交接时的确切工作区状态（2026-11-08 交付前核实）

```
分支/HEAD : main / ec74931  （领先 origin/main 3 个提交，未推送）

已修改（3）:
  M forge-1.20.1-47.4.10-mdk/build.gradle
  M forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/client/GeoGenesisConfigScreen.java
  M forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/client/preview/PreviewDisplay.java

未跟踪（6，全部为新增文件）:
  ?? src/diagnostics/java/com/geogenesis/worldgen/terrain/preview/PreviewSampleCostProbe.java
  ?? src/diagnostics/java/com/geogenesis/worldgen/terrain/preview/ProgressiveSamplerProbe.java
  ?? src/main/java/com/geogenesis/client/preview/ProgressiveSampler.java
  ?? src/main/java/com/geogenesis/client/preview/SandboxRenderer.java
  ?? src/main/java/com/geogenesis/client/preview/TerrainFieldData.java
  ?? handoff/GeoGenesis-config-panel-redesign-20261108.md   ← 本文
  ?? handoff/yardsite-research-20261108.md
```

`git diff --stat`（仅统计已跟踪文件）：**3 files changed, 510 insertions(+), 77 deletions(-)**。
新增文件行数：`TerrainFieldData` 233、`ProgressiveSampler` 197、`SandboxRenderer` 488、
`PreviewSampleCostProbe` 179、`ProgressiveSamplerProbe` 140。

**交付时的验证结果（全部实测）**：

```
compileJava / compileDiagnosticsJava / compileAcceptanceJava   BUILD SUCCESSFUL
runDefaultOutputFingerprintProbe --compare（对 0.0.2 基线）  PASS
runProgressiveSamplerProbe                                   PASS
runWorldgenGate -x runCenterlineCrossSectionProbe            ALL PASS
```
