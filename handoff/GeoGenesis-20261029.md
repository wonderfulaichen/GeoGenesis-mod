# GeoGenesis 冷启动交接 — 2026-10-29

> 接手方假设：有经验的工程师 / 另一个 AI agent，**不能随时问原作者**。
> 本文所有事实都落到 commit、文件行号或本轮实际命令输出上；**没核实的一律标「未验证」**。
> 本文是**当时快照**。HEAD / schema / 像素数字都会过期，引用前先 `git log` 核对。

---

## 0. 一句话现状

本轮修好了一个**让游戏默认水系残缺的真 bug**（端口开关默认相反），清理了一批"注释说谎"的死代码，
并把源头按比例均匀抽稀了 30%。**三个提交都在本地、未推送。**
游戏已由用户实机看过并认可，但**认可的是 schema 118 那版；schema 119（抽稀 0.7）只在离线图上确认过，没进游戏**。

---

## 1. 背景与目标

### 1.1 项目是什么
Minecraft Forge 1.20.1 模组，自定义 `ChunkGenerator` + `BiomeSource`，程序化生成地形与水文骨架。
工程实体在 `forge-1.20.1-47.4.10-mdk/`，**不是**仓库根。

### 1.2 本轮的用户诉求（按时间顺序，原话）
1. 「不行，明显不对啊，优化前的图不可能是这样的。明显你拿错参照了。」——质疑上一轮性能优化交付的对比基准。
2. 「左侧应该是这样的啊，我记得我在做深度的时候还正常的。你是不是跑错分支了？」——指出左侧水系消失。
3. 「源头似乎有点密集，感觉每个沟壑出现河流的概率有点高」→「我的要求是随机减少几条密集支流源头而已」。
4. 「河流源头也许可以增加一些多样性，比如山泉口等不同类型」——**后来说「算了，这个先搁置了」**。

### 1.3 用户的最终判据
- **只看图，不看代码**（原话：「代码是你写的只有你知道，我只看效果」）。
- 参考图必须是**生产路径**出的 1401² 大图，不是探针自制的渲染。
- 「标准图」由用户口头确认，**具体 SHA 必须记录在案**（本轮踩过坑，见 §6.1）。

---

## 2. 当前状态

### 2.1 分支与提交

```
分支 feat/hydrology-target   领先 origin 同名分支 3 个提交，未推送
工作树 干净（git status 为空）

033e96f  水文：源头按比例均匀抽稀（默认 0.7，只少支流不动主干）
38a4a84  水文：修复骨架端口开关默认相反导致的水系残缺，并清理误导性死代码
3590b79  水文：无损性能优化，分配量 −60~91%（产出逐字节不变）
54ef89b  （本轮起点）整理：水文文档拆分瘦身、旧分支归档、审计文档入库
```

三个提交**都已单独验证可独立编译**（脚本 `build/check-head.py`，见 §5.4）。

### 2.2 关键常量现值（**引用前请重新读代码**）

| 常量 | 位置 | 现值 |
|---|---|---|
| `CACHE_SCHEMA_VERSION` | `PreviewDisplay.java:483` | **119** |
| `SOURCE_KEEP_FRAC` 默认 | `DeterministicSkeleton.java:1329` | **0.7** |
| `PORT_EGRESS` 默认 | `DeterministicSkeleton.java:1935` | **开**（`!"false".equals(...)`） |
| `BOUNDARY_PORTS` 默认 | `ModelHydrologyProvider.java:1330` | **开**（同上，两者现已统一） |
| `skeletonSourceCells` 默认 | `ModelHydrologyProvider.java:1724` | **40**（骨架侧独立键） |
| `channelMinCells` 默认 | `ModelHydrologyProvider.java:1623` | **150**（世界排水分支） |

### 2.3 产出基准（1401²，seed `9139912035078620160`，中心 (-140,137)，r=700）

| 版本 | river 列 | lake 列 | PNG SHA256 | columns SHA256 |
|---|---:|---:|---|---|
| 用户认可的「标准图」`20bde1c8…` | 59250 | 105766 | `20bde1c8…` | `92eb64a7…` |
| 修复端口后 `c15e5f32…`（schema 118） | 58918 | 104313 | `c15e5f32…` | `d611419f…` |
| **当前 HEAD 默认（schema 119，抽稀 0.7）** | **52286** | **104341** | **`2309e45ff73e7635`** | **`17b17bc6170ce0e1`** |

⚠ **`ab404ad7…`（river=41928）不是标准图**，那是"端口开关只开一半"的坏几何。
历史文档多处把它当基准，已在 `HYDROLOGY.md` §〇bu 标注纠正。

**实测命令（本轮真跑过）**：
```
python build/bisect/verify-default.py
→ PNG sha256 = 2309e45ff73e7635
  chunks=8281 uncovered=0 dry=1353342 sea=452832 lake=104341 river=52286
  columnsSHA256=17b17bc6170ce0e1507b0188ab7ee292c35d70834fab0b0c0a0162b058fa4ada
```

### 2.4 半成品 / 未验证（**接手方必须知道**）

| 项 | 状态 |
|---|---|
| **schema 119（抽稀 0.7）实机效果** | ❌ **未进游戏**。只在离线 1401² 图上由用户确认「就是这个效果」。 |
| **`44cfb19` 的 0.2% 残余差异** | ❌ 未归因。相对标准图，`44cfb19` 起 river 59250→58918（少 2867 px，左带 352，均为沿湖岸零散单点）。已证明不是本次开关修复引入。已知单独关 `riverCrossSection` 回不到标准图，说明差异不只来自断面开关。**用户明确说「不需要扣这点像素」。** |
| **源头「成排」** | ❌ 未修。已量化（轴对齐比 3.33，见 §6.3），已记录在 `HYDROLOGY.md` §〇bv 末尾。 |
| **源头多样性** | 🅿️ 用户 2026-10-29 明确「搁置」，半成品代码已回退（见 §6.4）。 |
| **Forge 实机验收** | ❌ 无自动化入口。`runAcceptance` 硬编码 `hierarchicalHydrology=true`，与生产默认不符（详见 `HYDROLOGY.md` §〇bt 末段）。 |

### 2.5 证据文件清单（`build/` 下，**不入库**，fresh clone 没有）

| 路径 | 内容 | PNG SHA256 前 16 位 |
|---|---|---|
| `build/river-depth/A/production.png` | **用户认可的标准图** | `20bde1c80b05f0fa` |
| `build/source-dist/production-keep1.0.png` | 抽稀关闭（=schema 118 几何） | `c15e5f321947c5ef` |
| `build/source-dist/production-keep0.7.png` | **当前 HEAD 默认**（抽稀 0.7） | `2309e45ff73e7635` |
| `build/source-dist/AB-keep.png` | 抽稀 A/B 并排大图（用户据此刻确认效果） | — |
| `build/source-dist/sources-scatter.png` | 源头散点全幅图（成排可视化） | — |
| `build/source-dist/sources-zoom.png` | 源头局部放大 | — |
| `build/bisect/flagrange/fix-three-arm-full.png` | 端口修复三臂对比 | — |
| `build/bisect/flagrange/fix-left-band.png` | 端口修复左带放大 | — |

⚠ **`build/bisect/verify/production.png` 每次跑 `verify-default.py` 都会被覆盖**，
不要拿它当固定基准 —— 它只代表"最后一次跑出来的东西"。固定基准用上表的 `production-keep*.png`。

---

## 3. 环境与依赖

- Windows PowerShell（**不是 bash**）。中文输出在 `Select-String` 里会乱码，汇总脚本建议用 `python -` 读 UTF-8 文件。
- JDK：**Temurin 21.0.11**（`D:\Program Files\Eclipse Adoptium\jdk-21.0.11.10-hotspot`）。Gradle wrapper 8.8。
- Forge 1.20.1 / 47.4.10。
- **独立 javac 用的是另一个 JDK**：`C:\Users\qq274\.jdks\ms-17.0.17`（Java 17）。本轮的离线出图脚本全用它。
- 独立编译的 classpath 清单：`build/hydrology-perf/classpath.txt`（本地生成文件，fresh clone 不自带）。
- 用户配置：`run/config/geogenesis-common.toml`，本轮实测 SHA256
  `CC5D985F7554E4CFB316708E88373530241B9520E4CE983F745A6DEFCF50C16F`。
  冻结副本 `build/hydrology-perf/baseline/config-input.toml` **同哈希**，出图一律用它。
- **`build/` 整个被 gitignore**。本轮所有证据图、脚本、日志都在 `build/` 下 ⇒
  **fresh clone 拿不到**。需要保留的必须手动复制进版本库（见 §7 待办）。
- 无凭据、无部署、无外部服务。

---

## 4. 关键文件地图（改 X 要看 Y）

| 想改什么 | 看哪里 |
|---|---|
| 源头抽稀比例 | `DeterministicSkeleton.java:1329` `SOURCE_KEEP_FRAC`；实现在 `thinSources()`（同文件 :2715） |
| 端口开关语义 | `DeterministicSkeleton.java:1935` `PORT_EGRESS` + `ModelHydrologyProvider.java:1330` `BOUNDARY_PORTS`。**两处读同一属性，必须同默认值** |
| 成河门限（多小的沟算河） | `ModelHydrologyProvider.java:1724` `skeletonSourceCells`（骨架）/ `:1623` `channelMinCells`（世界排水）。**已拆分，勿再混用** |
| 源头位置定义 | `DeterministicSkeleton.collectSources()`（约 :2660）。判据 = `discharge ≥ 门限` 且 **D8 入度为 0** |
| 宽度起点（源头多宽） | `RiverWidthProfile.java:452-468`。真源头恒取 `MIN_HALF_BLOCKS = 0.5`（1 格全宽）；溢口起链走 `outletSourceHalf` |
| 深度剖面 | `RiverCrossSection.java:78` `WIDTH_EXPONENT`；深度**由宽度单向决定**，无独立源头参数 |
| 溢口起链（湖后河） | `Trace.fromOutlet`；宽度 `RiverWidthProfile.java:365/452`，深度 `RiverDepthProfile.java:104` |
| 缓存失效 | `PreviewDisplay.java:483`；改任何产出必须 +1 |
| 源头分布取证 | `runSourceDistributionProbe`（`SourceDistributionProbe.java`，本轮新增） |
| 性能观测 | `HydrologyMetrics`（`-Dgeogenesis.hydrologyMetrics=true` 才开） |

---

## 5. 操作 runbook

### 5.1 编译 & 门禁
```powershell
cd "D:\Office software\Development Project\GeoGenesis-mod\forge-1.20.1-47.4.10-mdk"

# 编译
.\gradlew.bat --offline --console=plain compileJava compileDiagnosticsJava

# 门禁（11 绿 / 1 红，红项是既存的 CenterlineCrossSectionProbe）
.\gradlew.bat --offline --console=plain runWorldgenGate -x runCenterlineCrossSectionProbe
# 预期：BUILD SUCCESSFUL，末尾列出 12 个 > Task :run*
```
> 不带 `-x` 时 Gradle 退出 1。**这个红不是新问题** —— 见 §6.2 的验证方法。

### 5.2 出 1401² 生产图（改水文的唯一可信验收方式）
```powershell
python build/bisect/verify-default.py
# 不带任何 -D，编译工作树后出图。当前期望 2309e45f / river=52286
# ⚠ 结果写到 build/bisect/verify/production.png（每次覆盖）
```
```powershell
python build/source-dist/render-keep.py 0.7    # → production-keep0.7.png (2309e45f)
python build/source-dist/render-keep.py 1.0    # → production-keep1.0.png (c15e5f32，抽稀关闭)
# 脚本会先编译工作树，避免跑成冻结基线类。单次约 5 分钟。
```

### 5.3 源头分布取证
```powershell
.\gradlew.bat --offline --console=plain runSourceDistributionProbe `
  -PprobeArgs="build/hydrology-perf/baseline/config-input.toml 9139912035078620160 1400 2"
# 输出：SOURCE_COUNT / PER_MILLION_BLOCK2 / NN_P50 / ALIGN_RATIO / PHASE_DEV_Z
#       + 32 格占用图 + CSV(build/source-dist/sources-s24-c40.csv)
# 单次约 6 分钟（扫 16+ 个 region）
```

### 5.4 验证「某个提交能不能独立编译」（hunk 分批后必做）
```powershell
python build/check-head.py 3590b79 38a4a84 033e96f
# 期望：三行 COMPILE OK (180 sources)
```

### 5.5 起游戏
```powershell
.\gradlew.bat --offline --console=plain runClient
```
启动到进世界约 4~5 分钟（本轮实测）。日志里**判定修复生效的关键行**：
```
[HYDRO-BOUNDARY] origin(...) role=SECOND_PASS ports=N anchorExact=N ...
[HYDRO-WIRE-MERGE] anchor=... RESOLVED:WATER_SEA steps=...
```
`role=SECOND_PASS` + `ports>0` + `WIRE-MERGE` **只在端口开关打开时出现**。
若只看到 `role=PRIMARY` 且 `ports=0` ⇒ 端口半接线，水系会残缺。

### 5.6 常见故障

| 现象 | 原因 / 处理 |
|---|---|
| 改了代码但预览/出图没变 | `CACHE_SCHEMA_VERSION` 没升，磁盘缓存静默复用。见 `PreviewDisplay.java:483` |
| 出图跑出 `ab404ad7…` | 用了冻结基线类或漏了 `-Dgeogenesis.boundaryPorts=true`。检查脚本是否**先编译工作树** |
| 出图跑出 `c15e5f32…` 而不是 `2309e45f…` | `sourceKeepFrac` 被显式设成 1.0，或脚本没传默认值 |
| 探针里的 A/B 图 A、B 两臂一模一样 | 用了会被覆盖的中间图（`build/bisect/verify/production.png`）。改用 `production-keep1.0.png` vs `production-keep0.7.png` |
| 探针输出中文乱码 | PowerShell 控制台 GBK。`jvmArgs '-Dfile.encoding=UTF-8'` 已加在新探针上，旧探针仍会乱码 —— 看数字即可 |
| `git commit` 中文乱码 | 用 `git -c i18n.commitEncoding=UTF-8 commit -F msg.txt`，msg 文件存 UTF-8 |

---

## 6. 风险、坑、回滚

### 6.1 ★ 最大的坑：同一个 `-D` 属性被两处读取、默认值相反

`geogenesis.boundaryPorts`：
- `ModelHydrologyProvider.BOUNDARY_PORTS = !"false".equals(...)` → **默认开**
- `DeterministicSkeleton.PORT_EGRESS = Boolean.getBoolean(...)` → **默认关**（已修为同式）

游戏正常启动不带 `-D` ⇒ Provider 按"端口开"走双 pass + typed 接力，骨架却从不发端口，**半接线**。
后果：左带河道 24824 → 10771，**左侧水系成片消失**，还伴随平行直线束。

**这条在 `HYDROLOGY.md` §〇bg 里早就被记成「开关不一致（未修）」，但一直没人量化后果。**

现在 `AGENTS.md` §「开关（-D）四条铁律」已写死规则：
1. 同一属性名只许一个默认值（默认开用 `!"false".equals(...)`，默认关用 `Boolean.getBoolean(...)`，**不许混用**）
2. 一个属性只表达一件事（默认值不同就拆键）
3. 注释里的"默认开/关"必须与代码同源
4. 默认值一变必升 schema 并重出图对拍，且要跟**用户认可的标准图**比，不能拿自己上次产出当基准

### 6.2 已知红项：`CenterlineCrossSectionProbe`

`runWorldgenGate` 12 项里它一直红。**验证它是不是新问题**的方法（本轮真跑过）：
把旧默认变体类覆盖到 classpath 前，重跑该探针，对比输出数字。
本轮结果：旧默认下同样是 `连通 119→153 / 拆分 25 / 孤立 7→9，exit 1` ⇒
**逐项相同，是 `44cfb19` 起的既存红，与端口修复无关**。

### 6.3 源头「成排」（未修，但已量化）

| 指标 | 值 |
|---|---|
| 轴对齐比（vs 同数量均匀随机点） | **3.33**（判据 >1.5） |
| 水平同行 : 竖直同列 | 3000 : 1587（水平是 1.9×） |
| 相位异常（32 block 周期） | z 方向 **+62%** |

**单变量证明**：`-Dgeogenesis.sourceSpacing=0` 关掉间距过滤后，比值仍是 3.32
⇒ 成排来自**河网掩码的几何上游边界**（D8 入度为 0 的等值线），**不是筛选规则造成的**。

### 6.4 已被否决的方案（不要再试）

| 方案 | 实测 | 为什么不能用 |
|---|---|---|
| `minSourceSpacingCells` 24→28 | 左带 24507→10142（−58.6%），最大连通分量 −44%，另新增 8789 px 改道 | 按**距离**成片判负。海岸平原那排天然间距 24~30 block 的平行支流被整群同时丢掉。24→28 之间是**悬崖**（28 与 40 结果几乎相同） |
| `maxSources`（每区上限） | 代码注释里有 2026-10-10 实测：最大连通分量 4252→1823（−57%） | 主干链被数量上限切断 |
| `minSourceAccum` 40→80 | 源头仅 −9.8%，且 50/60 档非单调 | 它改的是"多小的沟算河"，会动河道形态，对数量几乎无效 |

### 6.5 回滚方式

```powershell
# 单个提交回退（保留历史）
git revert 033e96f          # 只撤销源头抽稀，回到 schema 118 那版几何
# 想看抽稀前的图：-Dgeogenesis.sourceKeepFrac=1.0 （逐位回到 c15e5f32…）
```
**不要** `git reset --hard` —— 工作树干净，没有需要丢弃的东西；revert 更安全。

### 6.6 不可逆 / 高危操作
- 改 `CACHE_SCHEMA_VERSION` 会让所有旧预览缓存失效并重新生成（耗时长，数据无损失）。
- 删 `build/` 会丢掉本轮**全部**证据图与脚本（它们不在版本库）。删前先看 §7 第 1 条。
- 用户明令：**不要杀他正在运行的游戏进程**，不要替他换 jar / 重启实例。

---

## 7. 待办与决策点（接手方先看这节）

| # | 事项 | 卡在谁身上 |
|---|---|---|
| 1 | **本轮证据图/脚本只在 `build/`，不入库。** 是否需要挑关键图（`AB-keep.png`、`sources-scatter.png`、`fix-three-arm-full.png`）复制进 `handoff/attachments/`？ | 用户决定 |
| 2 | **schema 119 需实机确认。** 抽稀 0.7 只在离线图确认过。开游戏看左侧水系是否仍正常、支流密度是否合适。 | 用户实机 |
| 3 | **是否推送这 3 个提交。** 目前只在本地。 | 用户决定 |
| 4 | **`44cfb19` 的 0.2% 残余差异** 归因未做。用户说「不需要扣这点像素」⇒ 默认不做。 | 用户已在口头放弃 |
| 5 | **源头「成排」** 未修。方案已写（见 §6.3 尾注），做法二选一：①源头起点沿河道下移 1~4 格（只动起点，不动河网）②成河门限加哈希抖动（治根，但动河道形态）。 | 用户选择 |
| 6 | **源头多样性** 用户已搁置。方案文档在 `.openbitfun/plans/river-source-diversity.plan.md`（gitignored，不进库）。取证结论可复用：真源头起点硬编码 `MIN_HALF_BLOCKS=0.5`、展宽恒 32 格、深度由宽度单向决定。 | 用户暂缓 |
| 7 | **`build/bisect/verify-default.py` 的 docstring 已过期**（写的是 river=58918，实际已是 52286）。只影响注释，不影响运行。 | 顺手可修 |

---

## 8. 接手建议的验证顺序

1. `git log --oneline -5` 核对本文 §2.1 的提交是否还在。
2. `git status` 应为空。
3. `.\gradlew.bat --offline --console=plain compileJava compileDiagnosticsJava` → BUILD SUCCESSFUL。
4. `python build/bisect/verify-default.py` → 应得 `2309e45f…` / river=52286。
   若不是这个数 ⇒ 说明有未提交改动或 `build/` 被清过，先查清再动手。
5. `.\gradlew.bat --offline --console=plain runWorldgenGate -x runCenterlineCrossSectionProbe` → 11 绿。
6. 以上四步都过，再谈改代码。

**不要**在没有完成第 3~5 步之前修改任何水文产出逻辑 —— 这个项目的产出对默认值极度敏感，
一个 `-D` 的默认值差异就能吃掉整条左侧水系。
