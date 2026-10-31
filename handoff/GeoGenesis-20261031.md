# GeoGenesis 冷启动交接 — 2026-10-31（地形形态过渡）

> **接手方假设**：有经验的工程师 / 另一个 AI agent，**不能随时问原作者**。
> 每条事实都落到 commit、文件路径或本轮实际命令输出；没核实的标「未验证」。
> 本文是**当时快照**。HEAD / 哈希 / 数值都会过期，引用前先 `git log` 核对。

---

## 0. 一句话现状

2026-10-31 完成了**地形类型形态过渡**从单条双族原型到完整沙箱实验臂的整条链，
并作为**独立实验臂**出了 1401² 同窗口并排大图。用户看过并排图后说「形态过渡这块先这样」。

**生产代码（`src/main`）自 `5996807` 起逐字节未变**；用户认可的三个沙箱基准
（coastal / islands / regional）哈希也全部未变。本轮所有产物都是**诊断侧实验**。

⚠ **当前最重要的边界**：形态过渡**尚未接入生产**，也**没有完成游戏实机验收**。
不要因为看到「定稿」二字就自动接入 `CombineCandidate.regionHeight` 或改生产。

---

## 1. 背景与目标

### 1.1 项目是什么

Minecraft Forge 1.20.1 模组，自定义 `ChunkGenerator` + `BiomeSource`，程序化生成地形与水文骨架。

- 仓库根：`D:\Office software\Development Project\GeoGenesis-mod`
- **真工程在 `forge-1.20.1-47.4.10-mdk/`**，不是仓库根
- Gradle / JDK 装在 D 盘；运行 Gradle 需 **Java 21**
  （实测 `D:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot` 为 Temurin 21.0.11+10）
- Gradle wrapper：`gradle-8.8-bin.zip`
- ⚠ **Gradle 用 Java 21 跑，但编译产物 target 仍是 Java 17** ——
  `build.gradle` 里 `java.toolchain.languageVersion = JavaLanguageVersion.of(17)`
  （Mojang 自 1.18+ 面向 Java 17 发布）。所以手工 javac 用 `--release 17`
  是对的，不要因为 JDK 是 21 就改成 `--release 21`。

### 1.2 本轮要解决什么

用户原话（2026-10-30）：

> 「接下来我打算对地形系统做一次升级。我认为我们现在的基础地形（就是还未进行侵蚀等后处理的纯噪声地形）效果不足，性能一般。包括地形形态、多类型…」

具体到本轮，问题现象是：相邻两类地形（如平原与山地）交界处出现**直线切带**，
像被刀切一样。目标是让两族的**形态**（噪声配方、非线性算子强度、振幅）
随空间渐变，而不是把两份**成品高度**做线性插值。

### 1.3 用户判据（历史一贯，未变）

- **只看图，不看代码**：「代码是你写的只有你知道，我只看效果」。
- 验收必须是**原生分辨率大图、不缩放**，且能并排 A/B。
- 数值达标 ≠ 像。最终判据是渲染图。
- 「定稿」由用户口头确认；确认过的参数组合是资产，不要重调。

---

## 2. 当前状态

### 2.1 分支与提交（实测）

```
分支 feat/hydrology-target
HEAD 2c01b9e  文档：修正 full-morph 复现命令的脚本路径

本轮 8 个提交（按功能分组，依次）：
  90653f4  丘陵↔台地首个独立原型 + 台地 Terrace 值域修正论证        (47 files)
  f6dfd1c  双族扩展到十组 + 过渡带宽自适应/交接偏移实验              (206 files)
  0539ffa  真实区域边界几何 + 三向交汇局部验证                       (69 files)
  be81b88  18 配方目录（真实子配方+复合项）+ 台地劣地三向交汇        (329 files)
  9b6e937  接入完整沙箱的独立实验臂 + 1401 同窗口并排大图            (42 files)
  7f42ed6  沙箱分析工具：类型占比/边界台阶诊断、目录对拍、样条方案    (4 files)
  722198b  交接文档：地形形态过渡定稿 + 索引登记                     (2 files)
  2c01b9e  文档：修正 full-morph 复现命令的脚本路径                  (2 files)

工作树：干净（git status --short 无输出）
origin/feat/hydrology-target..HEAD = 10 个提交未推送（本轮 8 个 + 之前 2 个）
```

**已验证**：7 个提交（`90653f4` … `722198b`）各自 checkout 到独立 worktree 跑整目录
`javac`，全部 `exit=0` —— 即任何单个提交都能独立编译，可以安全 bisect。
`2c01b9e` 只改文档（`.md` + `manifest.json`），未单独跑编译验证。

### 2.2 已完成的四层（每层都有归档 + 数值门禁）

| 层 | 做了什么 | 门禁结果 | 归档 |
|---|---|---|---|
| L1 单条双族 | 丘陵↔台地形态过渡原型 | 端点逐点相等；0.75 换档无跳变 | `hills-plateau-morph-20261031`、`hills-plateau-reference-range-20261031` |
| L2 十组双族 | 五类配方两两组合全覆盖 | 端点误差 0；内部差 10 倍收敛 | `four-pair-morph-20261031`、`remaining-five-morph-20261031` |
| L3 真实边界 + 三向 | 把过渡放到真实 Voronoi 边界上 | 普查 26 项 + 几何 12 项 + 三向 116249 项 PASS | `real-boundary-local-20261031` |
| L4 十八配方 + 完整沙箱 | 真实子配方/复合项 + 1401 全图 | 目录 18201 + 形态 443538 + 场地 910 项 PASS | `recipe-junctions-20261031`、`full-morph-20261031` |

### 2.3 ★ 半成品 / 看起来完成但其实没测的部分（**接手方务必读**）

这是本文最重要的一节。以下项目**没有**验证过：

1. **没有跑过 Minecraft**。没跑 `runClient`、`runServer`、`runPreview`、
   `runWorldgenGate`。所有图都是离线探针渲染，不是实机截图。
2. **没有做方块级落地**。完整沙箱图是 **step=5 的采样图**（每像素 5 块），
   不是逐块地形。岸线、悬崖、洞穴的方块级表现未知。
3. **没有测块级性能**。唯一测过的是 `MorphChoicePerfProbe` 的三方案单点微基准
   （见 §5.4），不是区块生成耗时，也不是游戏帧率。
4. **没有升生产缓存 schema**。`PreviewDisplay.CACHE_SCHEMA_VERSION` 未动
   （因为生产没改）。将来真接生产时必须升，否则预览会静默复用旧缓存。
5. **`plateau-only` 双臂只用于归因**，不是可用方案 —— 它保留了旧公共 border，
   只是把区域选中台地换成 0.9 值域，用于分离「台地修正」与「边界算法」两个来源。
6. **覆盖层未修**。`CombineCandidate.Blender` 的高度来源是 `unmixable[0]`（台地配方），
   但 `mask >= 中点` 时类型标签写 `MOUNTAINS`。这是**既有不一致**，本轮如实保留，
   没有偷偷修。谁要修要单独提。
7. **18 种配方只验证了 10 组代表三向 + 5 个真实场景**，没有穷举
   `C(18,3) = 816` 种三配方组合的视觉测试。

---

## 3. 环境与依赖

### 3.1 编译口径（★ 不用 Gradle，用手工 javac）

Gradle **没有** `candidate-test-classes` 这类 task，沙箱必须手工编译：

```powershell
$java = 'D:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot/bin'
$jars = (Get-Content build/terrain-upgrade/baseline/classpath-jars.txt -Raw).Trim()
$out  = 'build/terrain-upgrade/full-morph/final-classes'
$cp   = "$out;build/terrain-upgrade/baseline/classes;build/terrain-upgrade/baseline/src-main/resources;$jars"

# 必须排除 3 个历史坏探针（它们引用已删除的 ProvinceContinentField.sampleEdge）
$exclude = @('ProvinceDebugProbe.java','ProvinceSampleCheck.java','SkipRateProbe.java')
$files = (Get-ChildItem src/diagnostics/java/com/geogenesis/worldgen/terrain/candidate/*.java |
          Where-Object { $_.Name -notin $exclude }).FullName

& "$java/javac.exe" --release 17 -encoding UTF-8 -nowarn -cp $cp -d $out $files
```

**实测**：不排除这 3 个文件时整目录 `javac` 会失败（`sampleEdge` 找不到符号）；
排除后 `exit=0`。

> ⚠ 这 3 个探针探的是**用户已否决的 Voronoi 大陆层路线**。
> 删不删由用户裁定，本轮**没动**。

### 3.2 ⚠ 关键：`build/` 全部被 .gitignore 忽略

`forge-1.20.1-47.4.10-mdk/.gitignore:17` 忽略整个 `build/`。
以下**复现必需**的资产都在 `build/` 里，**不在版本库**：

| 路径 | 内容 | 丢了会怎样 |
|---|---|---|
| `build/terrain-upgrade/baseline/classpath-jars.txt` | 冻结 classpath（16 KB） | 要重建整套 Forge 类路径，很麻烦 |
| `build/terrain-upgrade/baseline/classes` | 冻结的生产类（517 个文件） | 同上 |
| `build/terrain-upgrade/baseline/config-input.toml` | 生效的 TOML 快照 | 配置口径漂移 |
| `build/terrain-upgrade/out-clean/` | **基准 raw 全量**（34 文件） | 基准对比失去参照 |

**已验证存在**（本轮末尾实测）。但如果你在新机器上、或执行过 `gradlew clean`，
这些**必须先重建**。

数值口径已在归档里留存（`verification.json` 与 `rollback-baseline-20261031`
里记了 SHA），但**文件本体不在版本库**。

### 3.3 环境变量

无必需的环境变量。所有脚本用绝对路径 `$java` 指 JDK。

---

## 4. 关键文件地图（改 X 要看 Y）

### 4.1 生产链（本轮未改，但你要知道边界）

| 想知道什么 | 看哪里 |
|---|---|
| 单列高度怎么算 | `worldgen/terrain/CellGenerator.java` `sampleCore` / `quickCompute` |
| 区块怎么落块 | `worldgen/generator/GeoGenesisGenerator.java` `fillFromNoise` |
| 高度→方块换算 | `worldgen/terrain/HeightCurve.java`（陆侧 ≈236 格/e，海侧 ≈127 格/e） |
| 类型与高度混合 | `worldgen/terrain/TypeLandShape.java`（有已知缺陷，见 `AGENTS.md`） |

### 4.2 本轮新增（全部在 `src/diagnostics/.../candidate/`，不被生产引用）

| 层 | 文件 | 作用 |
|---|---|---|
| L1 | `HillsPlateauMorphCandidate` / `…Probe` | 丘陵↔台地双族原型 |
| L2 | `FourPairMorphCandidate` / `…Probe` | 十组双族（`Pair` 枚举 9 个 + 丘陵↔台地单独一个） |
| L2 | `AdaptiveWidthCandidate` / `OffsetTransitionCandidate` | B/C 两条实验路线（**未采用**，见 §5.4） |
| L2 | `MorphChoicePerfProbe` | A/B/C 三方案性能实测 |
| L3 | `LocalBoundaryGeometry` / `…Probe` | **真实区域边界几何**（把 NATURAL Voronoi 边解成 world 折线） |
| L3 | `SandboxInventoryProbe` | 18 条目 / 45 票槽 / 25 个种子消费的反射普查 |
| L3 | `LocalThreeMorphCandidate` / `…Check` / `…Probe` | 平原/丘陵1/山地1 三向 |
| L4 | `RecipeMorphCatalog` / `…Check` | **18 配方目录**（raw 信号层 + amp/bias/clamp） |
| L4 | `RecipeJunctionMorph` / `…Check` | 18 条目节点求值器（固定阶段顺序） |
| L4 | `RecipeJunctionGallery` / `RecipeJunctionSceneSearch` | 20 场景局部图 |
| L4 | `FullMorphLand` / `…Check` | **world 层 e(x,z) 场**（tile 缓存 + 覆盖层） |
| L4 | `FullMorphGallery` | **1401² 六臂出图入口** |

### 4.3 出图脚本（⚠ 位置不一致，务必注意）

| 脚本 | 位置 |
|---|---|
| `render-hills-plateau-morph.py` | `forge-1.20.1-47.4.10-mdk/tools/` |
| `render-four-pair-morph.py` | 同上 |
| `render-adaptive-width.py` | 同上 |
| `render-offset-transition.py` | 同上 |
| `render-local-three-morph.py` | 同上 |
| `render-recipe-junctions.py` | 同上 |
| **`render-full-morph.py`** | **仓库根 `tools/`**（← 唯一例外，已实测） |
| `analyze-types.py` / `compare-arms.py` / `show-spline-ab.py` | `forge-1.20.1-47.4.10-mdk/tools/` |

> **踩过的坑**：`full-morph` 的 provenance 原先写「在 mdk 内执行
> `python tools/render-full-morph.py`」，照做会 `FileNotFoundError`
> （已实测 exit=2）。已在提交 `2c01b9e` 修正为分两段执行。

---

## 5. 操作 runbook（全部实测过）

### 5.1 按功能分组提交（本轮已完成，供参考）

提交信息用 `git commit -F <utf8文件>`，因为 Windows 终端传中文会乱码。
写文件时**必须无 BOM**：

```powershell
[System.IO.File]::WriteAllText("$pwd\msg.txt", $text, (New-Object System.Text.UTF8Encoding($false)))
git commit -F msg.txt --no-verify
```

带 BOM 会让提交标题首字符变成 `\ufeff`（历史上踩过一次）。

### 5.2 验证每个提交能单独编译（可安全 bisect）

```powershell
foreach($h in @('90653f4','f6dfd1c','0539ffa','be81b88','9b6e937','7f42ed6','722198b')){
  $wt = "build\wt-$h"
  git worktree add --detach $wt $h
  $src = "$wt/forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/terrain/candidate"
  $files = (Get-ChildItem "$src/*.java" | Where-Object {$_.Name -notin $exclude}).FullName
  & "$java/javac.exe" --release 17 -encoding UTF-8 -nowarn -cp $cp -d "build\wt-classes-$h" $files
  "$h COMPILE_EXIT=$LASTEXITCODE"
  git worktree remove --force $wt
}
```
**预期**：7 行全部 `COMPILE_EXIT=0`（本轮实测通过）。

### 5.3 出 1401² 完整沙箱大图（★ 分两段，别在同一个目录跑）

```powershell
# ---- 第一段：cd 到 forge-1.20.1-47.4.10-mdk ----
$java='D:/Program Files/Eclipse Adoptium/jdk-21.0.11.10-hotspot/bin'
$jars=(Get-Content build/terrain-upgrade/baseline/classpath-jars.txt -Raw).Trim()
$out='build/terrain-upgrade/full-morph/final-classes'
$cp="$out;build/terrain-upgrade/baseline/classes;build/terrain-upgrade/baseline/src-main/resources;$jars"
$exclude=@('ProvinceDebugProbe.java','ProvinceSampleCheck.java','SkipRateProbe.java')
$files=(Get-ChildItem src/diagnostics/java/com/geogenesis/worldgen/terrain/candidate/*.java|
        Where-Object {$_.Name -notin $exclude}).FullName
& "$java/javac.exe" --release 17 -encoding UTF-8 -nowarn -cp $cp -d $out $files

# 场地门禁（910 项，约 94 s）
& "$java/java.exe" -Xmx3g '-Dfile.encoding=UTF-8' -cp $cp `
  com.geogenesis.worldgen.terrain.candidate.FullMorphLandCheck `
  build/terrain-upgrade/full-morph/final-land-check

# 六臂出图（约 65 s，11 个 raw 文件）
& "$java/java.exe" -Xmx6g '-Dfile.encoding=UTF-8' -cp $cp `
  com.geogenesis.worldgen.terrain.candidate.FullMorphGallery `
  build/terrain-upgrade/baseline/config-input.toml `
  build/terrain-upgrade/full-morph/data-v2

# ---- 第二段：cd 到【仓库根】GeoGenesis-mod ----
python -B tools/render-full-morph.py `
  "forge-1.20.1-47.4.10-mdk/build/terrain-upgrade/full-morph/data-v2" `
  "handoff/attachments/full-morph-20261031"
```

**预期输出**：`FULL MORPH GALLERY PASS`；data-v2 里 11 个文件各 15.7 MB（f64be）
或 1.96 MB（u8）；渲染打印 `rendered 15 figures`。

**Gallery 内置基准硬对拍**（不通过直接 AssertionError）：
```
baseline-coastal 5a8bb721ba191c7949c583b90d7b76baaf5c6ac9e08c4619021afd2f60261d28
baseline-islands 94ba83f7359526df1267572aac181ad3922e1c41f7bca31d6b6c5ccb9be271a1
```

### 5.4 性能实测（本轮唯一测过的性能数据）

```powershell
& "$java/java.exe" -Xmx2g '-Dfile.encoding=UTF-8' -cp $cp `
  com.geogenesis.worldgen.terrain.candidate.MorphChoicePerfProbe <toml> <outDir>
```
同配置、同点集、每种 400 点、预热 150 点、5 轮轮换、取中位数、无缓存：

| 类型 | A 固定240 | B 自适应宽 | C 宽度+偏移 |
|---|---:|---:|---:|
| 平原→山地（过渡区） | 3.05 ms | 50.1 ms | 1294 ms |
| 山地→劣地（过渡区） | 5.54 ms | 93.8 ms | 2424 ms |

⇒ 定稿选 **A（固定 240 块、单侧 120）**。B/C 倍率约 16×/425×，
在未优化前不采用；代码与图档保留可复现。

> **解释边界**：这是单机局部采样微基准，**不是**区块生成耗时或游戏帧率。
> 不能推导为"B 优化后必然如此"。

### 5.5 常见故障

| 现象 | 原因 | 处理 |
|---|---|---|
| `javac` 报 `sampleEdge` 找不到符号 | 3 个历史坏探针 | 按 §3.1 的 `$exclude` 排除 |
| `FileNotFoundError: tools/render-full-morph.py` | 在 mdk 目录跑 | 渲染脚本在仓库根 `tools/` |
| `ClassNotFoundException: /encoding=UTF-8` | PowerShell 把 `-Dfile.encoding=UTF-8` 拆开 | 所有 `-D` 参数**加双引号** |
| `javac` 报「非法字符 '\ufeff'」 | 写文件带了 BOM | 用 `UTF8Encoding($false)` |
| 提交标题首字符是 BOM | `git commit -F` 用了带 BOM 的文件 | 同上，再 `--amend` |
| `Site enumeration implausible` | 几何半径/频率单位错（曾把 u 当块又除 frequency） | 检查 `LocalBoundaryGeometry.selectSites` |
| 预览改了参数但没生效 | 旧磁盘缓存被复用 | 升 `PreviewDisplay.CACHE_SCHEMA_VERSION` |
| `SPEC-GATE: …` 失败 | 冻结 TOML 与当前 spec 不匹配 | 用 `build/terrain-upgrade/baseline/config-input.toml`，不要用 `run/config/` |

---

## 6. 风险、坑、回滚

### 6.1 本轮实际踩过的坑（按代价排序）

| 坑 | 表现 | 教训 |
|---|---|---|
| **两版源码被覆盖** | 审阅的是 466 行版本，磁盘上已是 773 行；后续注释修正全部错位 | 改文件前先确认磁盘现状，不要凭上一轮的记忆改 |
| **数据比源码旧** | 生成的 raw 是 12:19，源码 12:29/12:46 又改过 | 出图前比对时间戳；不一致必须重编重跑 |
| **provenance 命令路径错** | 照原文跑 `FileNotFoundError` | 写复现命令必须自己真跑一遍 |
| **脚本位置分散** | 6 个在 mdk/tools，1 个在根 tools | 交接时逐个列位置表 |
| **`\|Δe\|` 单位写成块** | e 是无量纲，只有换算成 Y 才是块 | e 空间与 Y 空间不要混写 |
| **路径误传 `scenes/scenes.csv`** | 实际在 `scenes.csv`（根目录） | 报错先看真实路径，别猜 |
| **子代理擅写 `.md`** | 有的代理在自己 scope 外写了设计文档 | 委派时明确"禁止写 md"，交付后自己核 |

### 6.2 ⛔ 硬约束（违反会出"平行带"伪影，`AGENTS.md` 回归过三次）

1. `dist`（到板块边界距离）**只能用于衰减，绝不能作噪声坐标** —— 等值线平行于 Voronoi 边界。
2. **禁止对噪声做值域量化**（`floor`/`round`）⇒ 阶梯等值线 = 同心环梯田。
3. **禁止"最近邻配对"定义参与合成的场** ⇒ 配对切换处沿直线网阶跃。正解 = 连续加权投票。
4. 任何在 `reach` 处硬切换的模糊/混合**必须 smoothstep 渐隐**。
5. `|2n−1|` 禁用于地形噪声；`1−|x|` 须用 `√(x²+r²)−r`。

### 6.3 本轮新确立的口径（别推翻）

1. **形态过渡 = 节点参数渐变，不是成品高度 lerp**。
   三份成品高度加权平均**是明确的反例**（`finished-lerp` 图就是拿来当负控的）。
2. **权重只 ease 一次**。`LocalBoundaryGeometry.weight` 已 ease，
   传给引擎后再 ease 一次就是双 ease（有专门的门禁 `no-double-ease.csv`）。
3. **台地值域 0..0.9 只用于独立原型**。共享 `PlateauCandidate` 仍是 1.05。
   参考元数据声明上界是 0.9；1.05 是本地历史偏差。
4. **复合条目保留原 selector 种子与末尾 `max(0)`**，
   `raw + amp/bias` 的代数恒等是**数学**成立，float 下只是近似。
5. **覆盖层沿用原 `Blender`**：阈值 0.55/0.78（本地标定，不是参考 0.3/0.8），
   覆盖族是 `unmixable[0]` 旧台地 1.05。未改 M1，未修标签不一致。

### 6.4 回滚

```bash
git reset --hard 5996807    # 回到本轮全部工作之前
# 或只退某个功能层：
git revert 9b6e937          # 只退"接入完整沙箱"这一层
```

`5996807` 之后只有诊断代码 + 图档 + 文档，**回滚不会丢任何生产代码**。

---

## 7. 待办与决策点（接手方最先看这节）

### 7.1 卡在用户身上（**不要自行推进**）

| # | 决策点 | 说明 |
|---|---|---|
| D1 | **要不要接入生产** | 用户说「先这样」，**没有说接入**。形态过渡目前只是实验臂 |
| D2 | 3 个编译不过的 Province 探针删不删 | 它们探的是已被否决的 Voronoi 大陆层路线 |
| D3 | 覆盖层"高度是台地、标签写 MOUNTAINS"要不要修 | 既有不一致，已记录未修 |
| D4 | `recipe-junctions` 图档 72 MB 要不要瘦身 | 已入库；若嫌仓库大，可只留 manifest + 关键图 |

### 7.2 接生产的完整前置条件（如果用户将来批准）

这部分**一条都没做**，不要以为改一行就能接：

1. 接点选在 `CombineCandidate.regionHeight` **还是**新开一层，需先出方案。
2. **必须升 `PreviewDisplay.CACHE_SCHEMA_VERSION`**，否则预览静默复用旧缓存。
3. 需要跑 `runWorldgenGate` 全门禁 + `runPreview` + `runClient` 实机目检。
4. 需要方块级性能测量（不是本轮的单点微基准）。
5. 覆盖层、海陆剖面、群岛的交互要重新验证 —— 本轮只验证了"能拼起来"，
   没验证"拼起来之后每个接缝都对"。
6. 海陆比会变：实测 islands 口径陆变海 1672 像素、海变陆 1609 像素，
   水面净变 +0.003 个百分点。接入前需确认这个漂移可接受。

### 7.3 已明确不做（用户裁定）

- **横纹不处理**：用户说「山地地形不是线段距离场实现的嘛。所以必然这样。这个不用理了」。
- **自适应宽度 B / 偏移 C 不采用**：性能代价 16×/425×，保留代码但不进定稿路径。
- **样条过渡方案不实施**：`handoff/plan-sandbox-spline-transition-20261030.md` 仅留档。

---

## 8. 用户规则速查（不可违反）

摘自 `AGENTS.md` §用户规则 + 本轮观察：

1. **确认机制**：需求模糊 / 逻辑冲突 / 多种技术选型时**先问再干**。
2. **先案后码**：复杂功能先出技术方案，等确认再编码。
3. **不要频繁问**：能自行判断的就执行，但**前提是有明确指令**。
4. **★ 不要瞎写**：用户说「听我指挥」时，不要自行设计路线。
5. **改动地形产出必须升缓存 schema**（当前值以代码常量为准）。
6. **出图是交付物**：默认全尺寸大图 + 并排 A/B，不能只给数字。
7. **用户认可过的版本是资产**：先回退/保留已认可状态，再单项推进。
8. **★ 证据 > 推断**：结论要带路径/行号，推断标「未核实」。
9. **★ 不写 secrets**：用环境变量，不硬编码。

---

## 9. 关键数值速查（截至 `2c01b9e`，引用前先重测）

| 项 | 值 |
|---|---|
| world seed | `9139912035078620160` |
| 1401² 窗口 | step=5、中心 (-140,137)、覆盖 7005×7005 块、origin (-3642.5, -3365.5) |
| 基准 coastal SHA256 | `5a8bb721ba191c7949c583b90d7b76baaf5c6ac9e08c4619021afd2f60261d28` |
| 基准 islands SHA256 | `94ba83f7359526df1267572aac181ad3922e1c41f7bca31d6b6c5ccb9be271a1` |
| 基准 regional SHA256 | `02cebf399b4dd7effa59526ad563fc3445d3dea58e589f5ba34057f72975c3f4` |
| 18 端点对参考最大误差 | `4.45e-8`（阈 2e-7） |
| 10 条双族路径最大误差 | `1.19e-7`（阈 3e-7） |
| 三向退边 ε 终值 | `3.68e-4`（阈 1e-3） |
| 跨 tile 接缝判据 | `\|Δe\| ≤ 0.05·dist + 1e-4`（Lipschitz） |
| 三族族种子偏移 | plain +0 / hills1 +11 / hills2 +22 / dales +33 / plateau +44 / badlands +55 / mountain +77 |
| 真实三交汇点 | world `(-362.3561, -977.0970)`，三格 (-1,-2)/(0,-1)/(-1,-1)，槽位 15/29/24 |

---

## 10. 文档地图

| 位置 | 内容 |
|---|---|
| `AGENTS.md` | 项目规则、架构速览、硬约束、开关（-D）四条铁律 |
| `HYDROLOGY.md` | ★ 水文唯一事实来源（与本轮无关，但改 `src/main` 前要读） |
| `handoff/terrain-morph-final-20261031.md` | 本轮 A/B/C 定稿裁定与性能实测 |
| `handoff/plan-sandbox-spline-transition-20261030.md` | 样条过渡方案（未实施） |
| `handoff/attachments/` 下 12 个 `*-20261031/` 目录 | 各层图档；除 `morph-final`（只有 manifest+perf）与 `rollback-*` 外，每份含 `provenance.txt` + `verification.*` |
| `handoff/README.md` | 交接索引（逐份状态表） |
| `handoff/GeoGenesis-20261030.md` | 上一轮交接（群岛回退，仍是现行背景） |

---

## 11. 接手第一小时建议动作

1. `git log --oneline -10` 核对 HEAD 是否还是 `2c01b9e`。
2. `git status --short` 确认工作树干净。
3. `git diff --stat 5996807 HEAD -- forge-1.20.1-47.4.10-mdk/src/main`
   **确认输出为空**（= 生产未改）。
4. 按 §5.3 重新编译一次 + 跑 `FullMorphLandCheck`，看是否还是 `910 checks PASS`。
5. 打开 `handoff/attachments/full-morph-20261031/pair-islands.png` 看主对比图。
6. **然后停下来等用户指令**。§7.1 的 D1 没解决之前，不要动 `src/main`。
