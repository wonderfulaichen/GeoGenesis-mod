# GeoGenesis 2026-11-04 交接：预侵蚀（低配沟壑）评估

> **冷启动入口。** 接手方默认是有经验的工程师，**不能随时问作者**。
> 每条事实都落到文件 / commit / 命令输出；未核实的标 `未验证`。
>
> 前序文档不重复：[GeoGenesis-20261103-state.md](GeoGenesis-20261103-state.md)（沙箱定稿状态）、
> [GeoGenesis-20261103-final.md](GeoGenesis-20261103-final.md)（定稿结论）。
> 本文只覆盖 2026-11-04 这一轮：**旧路线的沟壑塑形 / 预侵蚀能否作为粒子侵蚀的低配替代品**。

---

## 1. 背景与目标

需求演进（作者的问法三轮变化，最终口径才是有效的）：

1. ~~"把深窄槽改成平滑自然的沟"~~ → 视觉塑形
2. ~~"预侵蚀 + 粒子侵蚀叠加后更自然"~~ → 叠加
3. **最终口径：预侵蚀是物理粒子侵蚀的「低配替代品」**，给玩家一个独立开关。
   关闭粒子侵蚀时仍有近似沟壑；目的是**降低计算消耗**，不是加细节、不是提画质。

**结论一句话：按现有架构，这条路走不通，理由见 §3。生产已有更省的现成手段（§7）。**

## 2. 当前状态

### 2.1 已完成

| 项 | 状态 | 证据 |
|---|---|---|
| 诊断工具链（可复用） | ✅ | commit `fadf368`，见 §4 |
| 预侵蚀各版本形态实验 | ✅ 已跑完并全部否决 | `build/decomposed-carve-20261104/fresh-01..07/` |
| 主干网连线 + 延长断头 | ✅ 已实现 | `build/trunk-network-20261104/{connected-01,extended-01}/` |
| 真实粒子侵蚀对照（4 档） | ✅ 已跑完 | `build/particle-preeorder-20261104/{v1..v5}/` |
| 生产可复用性审计 | ✅ 有明确否定结论 | `build/particle-preeorder-20261104/v6-reuse-audit/report.md` |
| 提交 | ✅ | `fadf368` on `feat/hydrology-target` |

### 2.2 ★ 看起来完成但其实没做的

1. **没有任何改动进 `src/main`**。`git diff --stat -- forge-1.20.1-47.4.10-mdk/src/main` 仍有 6 个 `M` 文件，
   全部是**本会话开始前**遗留的，与本轮无关，未提交。
2. **粒子侵蚀内核未改、也未按生产物理尺度验证**。全部对照是固定 769² 栅格、
   格距 2.3828125wu **当成一个内核单位**、pad=9 边缘钳制、**未注入岩性硬度**。
3. **预侵蚀从未跑过实机**（`runClient`）。全部结论限于离线栅格。
4. **`erosionRidgeEnabled` 单开的真实观感没有出过图**。§7 的推荐基于耗时估算（阶段中位相加），
   没有任何日志真的以 `drops=0` 跑过。
5. 分支已领先 `origin/main` **141 个提交**，未推送（`origin/main` 仍是 `186af8c`）。

## 3. 关键结论与被否决的路线（这一节是接手方最该先看的）

### 3.1 为什么"预侵蚀"在叠加口径下不成立

预侵蚀的落点与粒子侵蚀高度重叠——因为**骨架线本身就是按最陡下降追出来的**，
粒子侵蚀也走最陡下降，两者天然撞在同一批沟上。

实测（`build/particle-preeorder-20261104/v3/`，只切不填 vs 纯粒子）：

| 指标 | A 纯粒子 | B 预侵蚀+粒子 | 差 |
|---|---:|---:|---:|
| 带内（d≤R）下切>0.5Y 覆盖 | 85.96% | 94.08% | +8.12 pp |
| 带外（d>R+6wu）下切>0.5Y 覆盖 | 94.28% | 96.81% | +2.53 pp |
| 带内中位下切 | 4.98Y | 6.56Y | **+1.58Y** |
| 细尺度坡度 P90 | 1.411 | 1.564 Y/wu | **+10.8%** |

带内"更完整"其实是**整条沟统一加深 1.58Y**，不是补齐缺失段；多出来的覆盖约 **69% 落在带外**（=碎条纹）。
**判：不值得。**

### 3.2 极浅走向引导也无效

把深度压到 ≤1.2Y 只做"大尺度平滑走向"（`v4/`）：转折角 P50 反而从 26.9° 变 28.0°，
毛糙度 P90 从 0.714 升到 0.756，且自身多花 1.8% 算力。**判：无效。**

### 3.3 独立低配替代品（L 档）只在离线成立

三档（`v5/tiers-summary.txt`）：

| 档 | 耗时 | 相对完整粒子 | 带内覆盖率 | 中位切深 | 视觉 |
|---|---:|---:|---:|---:|---|
| A 完整粒子 | 4.962 s | 1.000 | 87.5% | 4.34Y | 基准 |
| P40（40% 液滴） | 2.054 s | 0.414 | 77.0% | 1.32Y | 明显变平 |
| P60 | 2.843 s | 0.573 | 84.0% | 2.28Y | 较接近 A |
| L1 / L2 | 0.041 / 0.045 s | ≈0.008 | 50.3% / 54.5% | 0.51 / 0.67Y | 看不出沟 |
| **L3** | **0.047 s** | **≈0.009** | 65.9% | 1.30Y | **不看骨架线能读出沟网** |

**但这 0.047 s 不含骨架距离场**（离线重建 3.44 s）。见 §3.4。

### 3.4 决定性的审计：L3 不能搬进生产

`build/particle-preeorder-20261104/v6-reuse-audit/report.md`：

1. **生产没有折线沟壑数据源。** `GeoGenesisTerrain.fillRiverDistance`（`src/main/java/com/geogenesis/worldgen/terrain/GeoGenesisTerrain.java:294`，赋值在 `:299`）
   **只写 `Double.POSITIVE_INFINITY`**，legacy `RiverLineNetwork` 的生产接线已退役。
   `routes.csv` 是**单 seed 单窗口的离线冻结件**（8100 条 / 471,997 段 / 总长 458,268wu），没有生成器。
2. **每 tile 增量 6.4～27 ms**（桶 8wu、4096～14641 点，3 次中位），相当于现有骨架阶段（中位 13ms）的 +49%～205%，
   整块（中位 133ms）的 +4.8%～20%。
3. **缓存粒度不匹配**：delta 缓存按 48wu / 48×48 / 512 条（`CellGenerator.java:973,981`），距离场格数多 4～12.8 倍。
4. **还缺 `progress`（最长上游弧长）与 `accum`（下游叶数）**——区域级拓扑属性，需整张父子图 + 拓扑排序。
5. 代码自己的证据：`GeoGenesisConfig:734-735` 对 `erosionRoutingAdaptive` 的注释写明
   "网络构建覆盖整个 region ⇒ 区域内全部侵蚀 tile 同步冷生成（约 200ms/块、数百块）⇒ 加载卡死数分钟"，故默认关闭。

**⇒ L3 的"近零开销"是把成本预付到离线网络与属性上，不是算法便宜。不可复用。**

### 3.5 其他被证伪的形态路线（都别再走）

| 路线 | 结论 | 证据目录 |
|---|---|---|
| 高斯柔化旧下切场 | 逐点 min 把旧槽形裁回来，只变浅不改形状 | `build/local-soft-valley-visual-20261104/` |
| 截一小段做谷坡 | 形成独立凹坑，端壁明显 | `build/valley-slope-envelope-20261104/` |
| 整网距离场单尺度 | 相邻支沟间出现多边形折面 | `build/full-network-valley-20261104/` |
| splat 密度场 | 路线密集处糊成斑块，外缘成圈状/钩状轮廓 | `build/composite-erode-20261104/` |
| 去噪（base/detail 分解）再塑形 | 违反"侵蚀必须读成品地形"，且细节被整幅压低 | `build/decomposed-carve-20261104/fresh-01..02/` |
| 按路线重复度分主干/细支 | 骨架几乎不重叠（P90=1.12），98% 被判为细支 | `fresh-04/` |
| 粗尺度（18wu）合并 | 场值已铺满全窗（最小归一值 0.73），变成整幅下压 | `fresh-05/` |
| Rune 滤镜做预侵蚀 | 出细锐折痕/划痕，不像沟 | `build/preeorder-rune-20261104/fresh-01..02/` |
| 预侵蚀含沉积项 | 与粒子侵蚀沉积重复记账，坡度相关性仅 −0.034 | `build/particle-preeorder-20261104/v2/` |

**共同根因（已实测）**：8100 条骨架的平均间距只有约 40 格，**86.7% 的地面点在 15wu 内就有骨架线**。
任何以骨架为基准的塑形都会影响几乎整幅图，做不出"只改沟、沟间不动"。

### 3.6 旧路线 L0/T0 排水分析（已暂停，非本轮主线）

末端反坡退深需要 >115wu 上游，且与实测正常的共享段同点冲突（差 23.89Y），
沿原线非增条件下无解。用户已明确这条线**不是当前要解决的问题**，不要重启。
证据：`build/old-route-l0-20261104/`、`build/old-route-terminal-t0-20261104/`、`build/old-route-upstream-audit-20261104/`。

## 4. 环境与依赖

| 项 | 值 |
|---|---|
| 工作区 | `D:\Office software\Development Project\GeoGenesis-mod`（**不是** mdk 目录） |
| 真工程 | `forge-1.20.1-47.4.10-mdk/` |
| 分支 / HEAD | `feat/hydrology-target` / `fadf368` |
| Java | `java 21.0.11`（Temurin）；诊断一律 `javac --release 17 -Xlint:all -Werror` |
| Python | 3.8.6；numpy 1.24.4 / scipy 1.10.1 / matplotlib 3.7.5（无新依赖，未装 OSQP/cvxpy） |
| 构建 | `gradlew.bat build` / `runClient` / `runWorldgenGate`（需 Java 21） |
| 生产配置 | `forge-1.20.1-47.4.10-mdk/run/config/geogenesis-common.toml`（**只读，探针必须复制副本再加载**） |
| 诊断基类 classpath | `build/remaining-drop-field-sparse-equilibrated-20261104/classes` + `build/old-route-l0-sampler-20261104/classes` |

## 5. 关键文件地图（改 X 要看 Y）

| 想做的事 | 看这里 |
|---|---|
| 粒子侵蚀主循环（三尺度液滴） | `src/main/java/com/geogenesis/worldgen/erosion/ErosionEngine.java`（`runErosionOnFlat` 在 `:292`/`:298`） |
| 侵蚀 tile 的生成管线 | `src/main/java/com/geogenesis/worldgen/terrain/CellGenerator.java:1167` `generateErosionTile` |
| 现有"骨架"（逐点条纹滤波器） | `src/main/java/com/geogenesis/worldgen/erosion/RidgeValleyErosion.java`（`evaluateCell`，纯局部） |
| 侵蚀配置项 | `src/main/java/com/geogenesis/config/GeoGenesisConfig.java:731-` (`Erosion` 段) |
| 调用真实引擎的探针 | `src/diagnostics/java/com/geogenesis/worldgen/erosion/ParticleErosionProbe.java` |
| 减配引擎对照（DROPS 降档） | `src/diagnostics/java/com/geogenesis/worldgen/erosion/GuideErosionBench.java` |
| 距离场成本实测 | `src/diagnostics/java/com/geogenesis/worldgen/erosion/SkeletonDistanceCostBench.java` |
| 无 Forge 环境加载 TOML 的成熟做法 | `src/diagnostics/java/com/geogenesis/worldgen/erosion/ErosionScratchProbe.java`（`loadConfig` 在 `:384`） |
| 预侵蚀候选高度（最新一版） | `build/trunk-network-20261104/extended-01/candidate-height.f64be` |
| 主干网与延长段 | `build/trunk-network-20261104/extended-01/{connected-network.json,extended-routes.json}` |
| 冻结 H0 / 旧强场 | `build/strong-window-erosion-20261104-final/data/{carrier-height,strong-height}.f64be` + `sampling.json` |

## 6. 操作 runbook

```powershell
# 0) 进入真工程（所有诊断都以此为工作目录）
cd forge-1.20.1-47.4.10-mdk

# 1) 跑一版预侵蚀 + 粒子侵蚀对照（约 60s）
python -B src/diagnostics/particle-preeorder-v3.py

# 2) 重跑生产可复用性审计（含 27,826 条 [PERF] erosion tile 解析）
powershell -File build/particle-preeorder-20261104/v6-reuse-audit/run-audit.ps1

# 3) 直接跑真实引擎（单臂）
javac --release 17 -encoding UTF-8 -Xlint:all -Werror -d <classes> `
  -cp <diagnostic-classpath> src/diagnostics/java/com/geogenesis/worldgen/erosion/ParticleErosionProbe.java
java -cp <classes>;<diagnostic-classpath> `
  com.geogenesis.worldgen.erosion.ParticleErosionProbe <in.f64be> <out.f64be> 769 -4158.0078 -4279.5312 2.3828125 2 <toml-copy>
```

**常见故障**

- `GeoGenesisConfig` 未加载 → 必须用 `ErosionScratchProbe.loadConfig` 的同款流程（TOML 副本 + child-first 类加载器）。
- `IllegalStateException: TOML 未真实加载` → 副本路径必须在 `build/` 下，且 `setConfig` 要早于任何 `.get()`。
- 输出目录已存在 → 所有探针都拒绝覆盖，换一个 fresh 目录名。

## 7. 推荐方案（下一步就做这个）

**不要再写新的预侵蚀系统。** 生产已有两个现成旋钮：

| 手段 | 成本 | 依据 |
|---|---|---|
| `erosionRidgeEnabled=true` + `erosionEnabled=false` | 约 **23 ms/tile**（对比完整 133ms，估算） | 仅骨架分支已存在：`CellGenerator.java:1365-1374`，逐点局部、天然无缝、无限世界成立 |
| `erosionDropsMul`（默认 1.0，范围 0.1～4） | 40% 液滴 = **41%** 耗时，60% = **57%**（v4 实测） | `GeoGenesisConfig.java:742-743`，注释原文 "Lower = faster (preview)" |

**下一步唯一值得做的验证**：用这两个开关出三档实机/离线对比图——
**完整粒子 / 只开骨架 / 40% 粒子**——让用户看观感能否接受。
这比继续做新系统实际得多，且零架构风险。

## 8. 风险、坑与回滚

- **禁止改 `src/main` 里的侵蚀逻辑**。本会话反复确认过：现有粒子侵蚀"就这样，后续放生产再改进"。
- **不要 `git stash` / 回退**。工作区有大量前几轮遗留未跟踪实验与 6 个 `src/main` 改动，都不是本轮产物。
- 所有实验参数都是**视觉实验参数，不是生产默认值**（尤其 R=14wu、Dmax=13Y 这类超出旧 8wu 支撑的值）。
- 改动地形产出必须升 `PreviewDisplay.CACHE_SCHEMA_VERSION`（当前值以代码常量为准，在 `PreviewDisplay.java` 常量上方注释）。
- 未推送：本地领先 `origin/main` 141 个提交。推送前先确认范围。
- 中文提交用 UTF-8 无 BOM 文件 + `git commit -F`；PowerShell 5.1 的 `Set-Content -Encoding utf8NoBOM` **不可用**（会报枚举错误），
  要用 `[System.IO.File]::WriteAllText($path, $text, (New-Object System.Text.UTF8Encoding($false)))`。

## 9. 待办与决策点

| 事项 | 卡在谁 | 说明 |
|---|---|---|
| 出「完整粒子 / 只开骨架 / 40% 粒子」三档对比图 | **可直接做** | 零架构改动，用现成开关，见 §7 |
| 是否把上述低配档接进配置屏（让玩家可选） | 作者定 | 需要产品口径：三档语义怎么呈现给玩家 |
| 预侵蚀这条线是否彻底终止 | 作者定 | 证据指向终止；若要复活，唯一值得的形态是**替换现有条纹骨架算子**（同位置 121×121 网格、同缓存粒度），不是在其上再叠全局网络 |
| 推送 141 个提交 | 作者定 | 未授权 |
| 旧路线 L0/T0 排水分析 | **已暂停** | 用户已明确不是当前问题，不要重启 |
