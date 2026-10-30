# GeoGenesis 冷启动交接 — 2026-10-30

> **接手方假设**：有经验的工程师 / 另一个 AI agent，**不能随时问原作者**。
> 本文每条事实都落到 commit、文件行号或本轮实际命令输出上；**没核实的一律标「未验证」**。
> 本文是**当时快照**。HEAD / schema / 数值都会过期，引用前先 `git log` 核对。

---

## 0. 一句话现状

本轮**没有推进地形升级，而是把两轮走错路的群岛实验全部回退**，并在回退后提交了一个干净的基线提交。
`src/main`（生产代码）**全程零改动**，`schema 119` 标准图与实机效果不受影响。
**用户已明确叫停"自行发挥"，要求先听指挥再动手**（见 §1.3）。

---

## 1. 背景与目标

### 1.1 项目是什么

Minecraft Forge 1.20.1 模组，自定义 `ChunkGenerator` + `BiomeSource`，程序化生成地形与水文骨架。
工程实体在 `forge-1.20.1-47.4.10-mdk/`，**不是**仓库根。
仓库根：`D:\Office software\Development Project\GeoGenesis-mod`
Gradle 需要 **Java 21**（`JAVA_HOME=D:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot`）。

### 1.2 本轮之前的目标（用户原话，未变）

> 「接下来我打算对地形系统做一次升级。我认为我们现在的基础地形（就是还未进行侵蚀等后处理的纯噪声地形）效果不足，性能一般。包括地形形态、多类型…认真仔细研究，不要看一两个文件就认为读好了。当前先分析清楚。结合地理学等相关知识、制定一个初步方案。」

参考项目（本地只读快照）：
- `参考/sources/FreeTerraForged-1.21.1`（MIT，TerraForged 的 1.21 分支）
- `参考/sources/TerraForged-0.3.x`（MIT，`Engine` 子模块**本地缺失**，不能编译）
- `参考/sources/worldgen-master`（Rust，**无 LICENSE 文件**，17 个 `.rs`）

### 1.3 ★ 本轮最重要的口径变化（务必遵守）

用户在回退时明确说：

> 「不能再怎么瞎写了。先回退那些错误的。听我指挥怎么写。」
> 「不行你这样太费劲了。完全破坏了原本的地形。我不是说了 `#dir:worldgen-master` 这有参考啊。」

⇒ **接手方不要在"群岛/岛屿"上自行设计**。要做任何地形改动，**先等用户给出具体指令**，
或先提交一份**只读**方案等裁定。历史教训：我以"我是对的"为前提连写十几版，每一版都在破坏原有地形。

### 1.4 用户判据（历史一贯，未变）

- **只看图，不看代码**（原话：「代码是你写的只有你知道，我只看效果」）。
- 验收必须是**生产路径**出的 1401² 大图，不是探针自制渲染。
- 「标准图」由用户口头确认，SHA 必须记录在案。

---

## 2. 当前状态

### 2.1 分支与提交（实测）

```
分支 feat/hydrology-target
HEAD 33a508d  地形升级沙箱：回退群岛/弧岛实验，恢复到组合基线   (2026-10-30)
     cbb44b6  文档：将 schema 119 定为现行标准图，并归档可复现证据   (2026-10-29)
     544d145  文档：2026-10-29 冷启动交接（端口开关修复 / 死代码清理 / 源头抽稀）

工作树：仅两个未跟踪项（与本轮无关，见 §2.4）
origin/feat/hydrology-target..HEAD = 1 个提交（33a508d 未推送）
```

`src/main` 状态：**空**（`git status --short src/main` 无输出）。这是最关键的"没弄坏生产"的凭据。

### 2.2 本轮做了什么（33a508d 的实质内容）

**删除了 11 个文件**（全部是我这两轮新写的群岛/弧岛实验）：
```
ArchipelagoCandidate / ArchLayerProbe / ArchRealizeProbe / ArchIslandProbe
ArchFieldImageProbe / ArchBandDiffProbe / ArchLocateProbe
ArcIslandCandidate / ArcDiagProbe / ArcWindowProbe / ArcLocateProbe
```

**还原了 3 个文件**：
- `OceanAssemblyCandidate.java`：去掉 `archipelago` / `arcIslands` / `tectonicField` 字段、
  `enableArchipelago` / `enableArcIslands` / `heightBlocksWithIslands` / `heightBlocksWithArc`
  及全部相关常量；`e()` 回到只调 `heightBlocks`。
- `LandCompositionGallery.java`：去掉 `compose-arch-d*` / `compose-arc` 臂与 `buildCombineForProbe`。
- `tools/render-sharp.py`：`:88` 的 `keep` 列表还原为
  `['compose-production', 'compose-coastal', 'compose-regional']`。

**新增提交的其余内容**：这是沙箱（`src/diagnostics` + `tools/`）第一次入库，
共 52 文件 9774 行，包含你此前定稿的五个地貌族（平原/丘陵/山地/台地/劣地/盆地）
及它们的组合、海陆组装、以及出图脚本。**这些是资产，不要删。**

### 2.3 回退验证（实测，可复现）

回退后重跑 `compose-coastal` 臂：

```
compose-coastal   Y=5.14..269.14   relief=264.00
compose-production Y=   ...        relief=247
compose-regional   Y=3.03..269.14   relief=266.11
```

与回退前基线（`relief=264.00`、`Y=5.14..269.14`）**一致** ⇒ 回退是干净的、无残留。

出图：[`handoff/attachments/revert-baseline-20261030/sharp-wide.png`](attachments/revert-baseline-20261030/sharp-wide.png)

（图上三臂：`compose-production` 水面 59% / `compose-coastal` 水面 35% / `compose-regional` 水面 18%。
`compose-coastal` 与 `compose-regional` 的陆地层相同，差别只在海侧走不走三段海岸剖面。）

### 2.4 未跟踪项（与本轮无关，不要误删）

```
?? geogenesis-width-count.txt      （仓库根，宽度统计临时文件）
?? tools/                          （仓库根的 tools 目录，只有 __pycache__ + compare-terrain-upgrade.py）
```

### 2.5 已知问题（**未处理，等指令**）

`candidate/` 目录整体 `javac` **会失败**，根因是三个探针引用了已不存在的
`ProvinceContinentField.sampleEdge`：

```
ProvinceDebugProbe.java:22, :41
ProvinceSampleCheck.java:16
SkipRateProbe.java:18
```

它们探的是**用户已否决的 Voronoi 大陆层**（§3.3）。本轮**没有动它们**。

实测两种编译口径：
- 原样整目录 `javac src/diagnostics/.../candidate/*.java` ⇒ **exit=1**（上述 3 文件报错）
- 排除这 3 个文件 ⇒ **exit=0，产出 91 个 class**

⇒ 想整体编译必须先排除或删除这 3 个文件。**删不删等用户裁定**（它们是被否决路线的遗留探针）。

---

## 3. 关键事实地图（改 X 要看 Y）

### 3.1 生产链入口（改地形必读）

| 想知道什么 | 看哪里 |
|---|---|
| 单列高度怎么算出来 | `worldgen/terrain/CellGenerator.java` `sampleCore` / `quickCompute` |
| 区块怎么落块 | `worldgen/generator/GeoGenesisGenerator.java` `fillFromNoise` |
| 地形类型与高度混合 | `worldgen/terrain/TypeLandShape.java`（**有已知缺陷，见 §3.2**） |
| 五种地貌的噪声配方 | `worldgen/terrain/TypeNoiseProvider.java` |
| 海陆与海底 | `ContinentField` / `OceanFeatures` / `TectonicField.oceanProfile` |
| 高度→方块的换算 | `worldgen/terrain/HeightCurve.java`（陆侧 ≈236 格/e，海侧 ≈127 格/e） |

### 3.2 ★ 已测出的结构缺陷（重要，但**不要自行修**）

**`TypeLandShape` 的加性混合有交叉项。** 现行公式可化简为：

```
m  = clamp(s + 0.75·(Σ ŵ_t·n_t − s))
e  = Σ ŵ_t·lo_t + (Σ ŵ_t·Δ_t)·m
```

第三项 `λ·(Σ ŵ_t·Δ_t)·(Σ ŵ_t·n_t)` 是**两个加权和的乘积** ⇒ 展开出 `ŵ_i·ŵ_j·Δ_i·n_j`（i≠j）：
**类型 j 的噪声经由类型 i 的高度区间漏进地形**。

单类型敏感度：`∂e/∂n_j ≈ 1.125 · ŵ_j · Δ̄`。
以近均分权重（ŵ≈0.2）与默认 Δ 代入，**每类型即使不主导也仍贡献 ≈0.057 e ≈ 13 格**。
这是历史上「per-block Δe 0.04~0.07」与 `MORPH_STRENGTH` 卡在 0.75 的机制来源。

候选沙箱里已有一条走通的路（`CombineCandidate`：「先把每族算成完整高度，再按权重混合」，
实测类型配比 平原 30% / 丘陵 28% / 台地 13% / 山地 21%，不再"平原 75% + 碎斑"）。
**但要不要动生产混合方式，等用户拍板。**

### 3.3 已被用户否决的路线（别再提）

| 路线 | 否决原话 / 依据 | 残留物 |
|---|---|---|
| **Voronoi 领地大陆层**（替换 FBM） | 「经过我观察，好像还是我们的 FBM 大陆非常合适。效果很好。」 | `ProvinceContinentField` + `compose-province` 臂 + 3 个编译不过的探针 |
| **Worley / 噪声自由撒岛**做群岛 | 「不行，你这群岛类型的地形完全不对，你这个完全像一个台地一样。」 | 本轮已全部删除 |
| **弧峰出水**做岛弧 | 「不行你这样太费劲了。完全破坏了原本的地形。」 | 本轮已全部删除 |
| 构造偏置（orogen/rift 接类型权重） | 实测起伏 244→243、P99 无变化，**肉眼不可辨** | `compose-tectonic` 臂 |
| 边界反转 / 连带放置 | 全局反转出现沿 Voronoi 边界的脊线网络；连带放置只改"哪些格子同类型"、不改格子形状 ⇒ 仍是圆斑不产生带状 | `compose-inverted` / `compose-coherent` 臂 |

### 3.4 参考项目里"岛"到底是什么（本轮踩坑的根因）

`worldgen-master` **没有**任何独立的岛屿/群岛实现（`grep island|archipelago` 只命中
`elevation.rs` 的 `SALT_CHAIN` / `chain_seed`）。它的"岛"完全是**板块边界剖面**的产物：

- `elevation.rs:295-304` 洋-洋汇聚 ⇒ 海沟 + **离轴火山弧**（`gaussian(dist − 35·scale, 18·scale)`）
- `elevation.rs:78-95` **chain modulation**：用 `ridged_fbm(along×6, across×18)` 把连续弧脊
  调制成一串独立峰，`m = 0.25 + 0.75·chain`，再整条剖面乘 `m`
- `elevation.rs:189` 所有分量**纯加法**叠到 `base + profile_offset + ...`
- `elevation.rs:106-107` **`blur_grid`** 抹平 Voronoi 断崖（这是它能用 `btype`/`rate` 的前提）
- `elevation.rs:396-402` 大陆架只在近岸海域**抬高**（`max(shelf_elev)`，永不挖坑）

**关键算术**：本项目深海基面 ≈ −0.35 e（−63 格），弧剖面 0.10 e ⇒ 弧顶 ≈ −45 格；
要露出水面需再补 ≈0.25 e，成岛需 ≈0.38 e。项目 `TectonicField:212-215` 的注释
明确写过「参考项目自己的整图也无裸露岛弧链」，这是同一笔算术。

> ⚠ 我们**已有**正确的弧几何：`TectonicField.oceanProfile`（`TectonicField:606-614`，
> 海沟窄槽 σ=45 + 离轴弧 ARC_OFFSET=210/σ=55）。`OceanFeatures:220-225` 用
> `cap = −eOcean − MARGIN` 把弧的正值压在海平面下，**这是刻意的**（防浅海露头造岛）。
> 要"岛弧出水"就必须去掉这个 cap —— 那会改变海陆比，**不是纯形态改动，需用户明确批准**。

### 3.5 沙箱代码地图（`src/diagnostics/.../candidate/`，40 文件 6430 行）

| 文件 | 行数 | 作用 |
|---|---|---|
| `LandCompositionGallery.java` | 474 | **出图入口**（`main`），渲染 13 个臂 |
| `CombineCandidate.java` | 555 | 参考的 `combine` 两两复合 + `RegionLerper` 语义 |
| `OceanAssemblyCandidate.java` | 515 | 海陆组装（三段海岸 + cliffFactor + 海底分层） |
| `TerrainBlenderCandidate.java` | 237 | 旧版 TF 0.3.x `TerrainBlender` 复刻（与上者机制不同，见类注释） |
| `LandCompositionCandidate.java` | 193 | 「每族完整高度再加权」的对照实现 |
| `RegionCompositionCandidate.java` | 188 | 参考的区域划分 + 公共边界过渡 |
| `BasinCandidate.java` | 180 | B1/B2/B3 盆地族（参考无对应，自行设计） |
| `TypeCandidateGallery.java` | 177 | 单类型出图入口 |
| `HillsCandidate.java` / `MountainsCandidate.java` / `PlateauCandidate.java` / `PlainsCandidate.java` / `BadlandsCandidate.java` | 66–145 | 各族配方 |
| `RefNoise.java` + `RegionCellTable.java` | — | 零 MC 依赖的噪声原语 + 参考抖动表（原值照录） |

### 3.6 `tools/` 脚本

| 脚本 | 作用 |
|---|---|
| `render-sharp.py` | **画图**（原生分辨率，不缩放）。`:88` 的 `keep` 写死三个臂 |
| `run-terrain-upgrade.py` | 组合流程 |
| `render-candidate-gallery.py` / `render-composition.py` / `render-field-diagnostic.py` / `render-orogen-probe.py` / `show-closeup.py` | 各类专项出图 |
| `terrain-upgrade-baseline.json` | **可复现基线**（坐标规格、编译/出图命令、臂参数、SHA256） |
| `terrain-upgrade-contract.json` | 指标契约 |

---

## 4. 操作 runbook（全部实测过）

### 4.1 编译沙箱

```powershell
cd "D:\Office software\Development Project\GeoGenesis-mod\forge-1.20.1-47.4.10-mdk"
$env:JAVA_HOME='D:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot'
$jars = (Get-Content build\terrain-upgrade\baseline\classpath-jars.txt -Raw).Trim()
$cp = "build\terrain-upgrade\baseline\classes;build\terrain-upgrade\baseline\src-main\resources;$jars"
$files = (Get-ChildItem src\diagnostics\java\com\geogenesis\worldgen\terrain\candidate\*.java |
  Where-Object { $_.Name -notin @('ProvinceDebugProbe.java','ProvinceSampleCheck.java','SkipRateProbe.java') }).FullName
& "$env:JAVA_HOME\bin\javac.exe" --release 17 -encoding UTF-8 -nowarn -cp $cp `
  -d build\terrain-upgrade\candidate-test-classes $files
# 预期：无输出，$LASTEXITCODE=0，产出 91 个 .class
```

> ⚠ **不要用 `-sourcepath` 单编某一个文件**有什么问题就改什么：
> 目录内文件互相依赖，单文件编译会因缺依赖报一堆假错（实测 40 个文件里 35 个"失败"）。
> **必须整目录一次编译。**

### 4.2 出图

```powershell
$cp2 = "build\terrain-upgrade\candidate-test-classes;$cp"
& "$env:JAVA_HOME\bin\java.exe" -Xmx8g "-Dfile.encoding=UTF-8" -cp $cp2 `
  com.geogenesis.worldgen.terrain.candidate.LandCompositionGallery `
  build\terrain-upgrade\baseline\config-input.toml build\terrain-upgrade\out 1401 5 -140 137
# 约 100 s，末尾打印各臂 Y 范围与 relief
python tools\render-sharp.py build\terrain-upgrade\out wide "副标题"
# 产出 build\terrain-upgrade\out\sharp-wide.png
```

`size=1401 step=5` ⇒ 覆盖 7005×7005 块。`size=701 step=10` 更快但更粗。

### 4.3 跑游戏 / 构建

```powershell
.\gradlew.bat build        # 编译 + 打包
.\gradlew.bat runClient    # 启动客户端
.\gradlew.bat runPreview --args=12345
.\gradlew.bat runWorldgenGate   # 世界生成门禁
```

> ⚠ `gradlew.bat` 里**没有** `candidate-test-classes` 这个 task（实测
> `Task 'candidate-test-classes' not found`）。沙箱只能走 §4.1 的手工 `javac`。

### 4.4 环境目录（不可重建，别删）

- `build/terrain-upgrade/baseline/` —— **冻结输入**：
  `config-input.toml`（生效的 TOML 快照）、`classpath-jars.txt`（类路径）、
  `classes/`、`src-main/resources/`、`production-reproduced/`、`verification.json`。
  丢了要重建整套 classpath，很麻烦。
- `build/` 整个被 `.gitignore` 忽略 ⇒ **提交保护不了它**。
  要长期保留的东西必须拷到 `handoff/attachments/`（本轮已拷基线图）。

---

## 5. 风险、坑、回滚

### 5.1 硬约束（违反会出"平行带"伪影，已回归三次）

见 `AGENTS.md` §「⛔ 地质系统硬约束」。摘要：

1. `dist`（到板块边界距离）**只能用于衰减，绝不能作噪声坐标** —— 等值线平行于 Voronoi 边界。
2. **禁止对噪声做值域量化**（`floor`/`round` on noise） ⇒ 阶梯等值线 = 同心环梯田。
3. **禁止"最近邻配对"定义参与合成的场** ⇒ 配对切换处沿直线网阶跃。正解 = 连续加权投票。
4. 任何在 `reach` 处硬切换的模糊/混合**必须 smoothstep 渐隐**（两分支数值不等即产生阶跃）。
5. `|2n−1|` 禁用于地形噪声；`1−|x|` 须用圆化绝对值 `sqrt(x²+r²)−r`。

### 5.2 本轮实际踩过的坑（按代价排序）

| 坑 | 表现 | 教训 |
|---|---|---|
| **擅自重构路线** | 用户说"参考 worldgen-master"，我先自己发明 Worley 撒岛，连写十几版 | 参考里**有**答案时必须先读懂它的结构，不要自创 |
| **把"岛"当独立装饰去融进地形** | 每次融合都在破坏地形：抬平台、挖水池、改陆地高度 | 参考里"岛"是**边界剖面**的分量，纯加法叠加，不是装饰 |
| **`smoothMax(a, a) = a + k/4`** | 用 smoothMax 融岛 ⇒ 陆地整体被抬高 4 格 + 水陆交界下挖 | 融合算子必须先验算"两输入相等时是否恒等" |
| **支撑半径超出邻域** | 岛底座支撑 1.15 格距 > 3×3 邻域半宽 1.0 ⇒ 跨格界时远处岛体时而进出窗口 | 任何有限邻域算法，支撑半径必须 ≤ 半窗口宽 |
| **轮廓相位写死成每格常数** | 邻格算出的岛边界半径不同 ⇒ 岛身出现笔直切痕 | 轮廓/形状噪声必须用**世界坐标**连续采样 |
| **用 `pow` 代替剖面** | 岛缘高度从 0 直跳到几十格 ⇒ 垂直刀切 | 剖面在边界处必须收敛到 0，且两端导数为 0 |
| **掩码饱和** | `clamp01(shape/fade)` 在饱和区恒 1 ⇒ 台地 | 用软饱和 `r/(r+SOFT)`，渐近但永不达到 |
| **PowerShell `Set-Content -Encoding UTF8`** | 写出 **BOM** ⇒ `javac` 报「非法字符 '\ufeff'」 | 用 `[System.IO.File]::WriteAllText($p,$t,(New-Object System.Text.UTF8Encoding($false)))` |
| **`git commit -F` 用了带 BOM 的文件** | 提交标题首字符是 BOM | 同上，改用 UTF8Encoding($false) 再 `--amend` |
| **`-Dfile.encoding=UTF-8` 未加引号** | PowerShell 把 `-Dfile...` 拆开 ⇒ `ClassNotFoundException: /encoding=UTF-8` | 所有 `-D` 参数**加双引号** |

### 5.3 回滚

```
git reset --hard cbb44b6     # 回到本轮之前（沙箱未入库的状态）
```

`33a508d` 只新增了未跟踪文件的入库 + 删除我自己的实验文件，回滚它**不会丢任何生产代码**。

---

## 6. 待办与决策点（**接手方最先看这节**）

### 6.1 卡在用户身上（**不要自行推进**）

| # | 决策点 | 卡在谁 | 说明 |
|---|---|---|---|
| D1 | **下一步做什么** | **用户** | 用户说「听我指挥怎么写」。**未给出下一步指令前不要动任何地形代码** |
| D2 | 三个编译不过的 province 探针删不删 | **用户** | 见 §2.5。它们探的是已被否决的路线 |
| D3 | 要不要把 `TypeLandShape` 的加性混合换成"完整配方再混合" | **用户** | 结构缺陷已证（§3.2），但改混合方式会改变全部地形产出 |
| D4 | 岛弧是否允许出水（去掉 `OceanFeatures` 的 cap） | **用户** | 会改变海陆比，不是纯形态改动 |

### 6.2 已完成、无需再动

- 沙箱入库（`33a508d`），五个地貌族与组合/海陆组装可复现。
- 群岛/弧岛坏实验已全部清除，回退后数值与基线一致（§2.3）。
- `schema 119` 标准图资产完好，`src/main` 零改动。

### 6.3 方案文档现状

`\.openbitfun\plans\base-terrain-upgrade-research.plan.md`（61257 字节，**未提交**，被 git 忽略）：
这是前期研究 + 初步方案，**其中 §6.6 / §6.8 / §6.8.6 / §10 已按用户"保留 FBM 大陆"的裁定更新**，
但 **§6.8 / §6.8.6 里还留着现在的群岛/Voronoi 设想 —— 那是本轮已被否决的内容，读时须跳过**。

> ⚠ 该文件读出来是乱码（GBK 被当 UTF-8 或反之）。要读请先确认编码，
> 或直接用 `git`/编辑器以正确编码打开。**不要因为乱码就当成"文件损坏"删掉。**

### 6.4 未验证项（诚实标注）

- 群岛/弧岛删除后，沙箱里**其余臂**（`compose-blender` / `compose-combine` /
  `compose-ground-base` / `compose-ocean-allmixed` / `compose-coherent` /
  `compose-inverted` / `compose-tectonic` / `compose-province`）本轮**未逐一目检**，
  只确认编译通过、`compose-coastal` 数值回基线。
- 本轮**没有**跑 `runClient`、`runWorldgenGate`、`runPreview`，也**没有**重出 schema 119 生产图。
- 参考项目 `worldgen-master` 的**许可状态未核实**（目录内无 `LICENSE*` 文件）。
  若将来要真正移植其代码，必须先确认授权。

---

## 7. 用户规则速查（不可违反）

摘自 `AGENTS.md` §用户规则 + 本轮新增：

1. **确认机制**：需求模糊 / 逻辑冲突 / 多种技术选型时**先问再干**。
2. **先案后码**：复杂功能先出技术方案，等确认再编码。简单具体指令直接执行。
3. **不要频繁问**：能自行判断的就执行 —— 但**前提是有明确指令**。本轮属于"无指令 → 必须停"。
4. **★ 不要瞎写**（本轮新增，最高优先级）：用户说「听我指挥怎么写」时，
   不要再自行设计路线、不要"我觉得这样更好"。
5. **改动地形产出必须升 `PreviewDisplay.CACHE_SCHEMA_VERSION`**（当前值以代码常量为准），
   否则预览静默复用旧磁盘缓存 ⇒ 表现为"改了没生效"。
6. **出图是交付物**：默认全尺寸大图 + 并排 A/B，不能只给数字。
7. **用户认可过的版本是资产**：先回退/保留已认可状态，再单项推进。
