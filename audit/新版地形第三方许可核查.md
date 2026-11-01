# 新版地形第三方许可核查

> 核查范围：当前工作区中 +0.30 候选高度链的主要来源线索，非全仓库完整依赖或版权审计，也不是法律意见。未对外分发、未修改项目总许可。结论依赖实际交付物、版本及所含文件，未知项未核实前不得标为许可已完成。

## 已核查的主要路径

[M1ThresholdHeightGallery.java](../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/terrain/candidate/M1ThresholdHeightGallery.java#L13-L20) → [CoherentMorphWorld.java](../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/terrain/candidate/CoherentMorphWorld.java#L19-L38) → `RegionalMorphWorld` / `ShallowRegionMatcher` → `LandRegionContext` / `RecipeMorphCatalog` / 区域几何及地貌噪声。此处为静态追踪的主要路径，并非编译器生成的完整闭包；反射、特定种子及不同构造入口可能改变依赖。下文对反射和本地上游原文进一步核查。

| 文件 | 可直接核实的来源说明 | 需要继续核实 |
|---|---|---|
| [RefNoise.java:3-9](../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/terrain/candidate/RefNoise.java#L3-L9) | 文件自述 FreeTerraForged 1.21.1 噪声算子原样实现，并注明本地差异 | 逐片段来源、历史版权声明及适用文件头 |
| [RegionCellTable.java:3-7](../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/terrain/candidate/RegionCellTable.java#L3-L7) | 文件自述 256 项参考抖动表原值照录，候选区域分布实际读取 | 上游版权谱系；本轮 256 对字面量已与本地上游全量比对一致，详见下文 |
| [PlainsCandidate.java](../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/terrain/candidate/PlainsCandidate.java#L3-L5)、[HillsCandidate.java](../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/terrain/candidate/HillsCandidate.java#L3-L5)、[PlateauCandidate.java](../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/terrain/candidate/PlateauCandidate.java#L3-L4)、[BadlandsCandidate.java](../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/terrain/candidate/BadlandsCandidate.java#L3-L4)、[MountainsCandidate.java](../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/terrain/candidate/MountainsCandidate.java#L3-L4) | 文件头明确注明复刻 FreeTerraForged 1.21.1 的地貌配方，部分包含本地修改；目录实际构造这些族 | 不同变体具体沿革、逐文件上游声明和交付范围 |
| [RegionPartition.java:3-7](../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/terrain/candidate/RegionPartition.java#L3-L7) | 文件注明参考 `RegionModule`；候选上下文实际使用 | 哪些代码构成受许可保护的移植或改写，需继续比较 |
| [TerrainBlenderCandidate.java](../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/terrain/candidate/TerrainBlenderCandidate.java#L6-L8) | 文件标注复刻 TerraForged 0.3.x | 目前没有证据证明它参与本候选求值；如果交付整个诊断目录，则仍需纳入审查 |

## 补充核查记录：实际调用、反射与依赖

**结论：MIT 对其覆盖的代码允许商业使用、修改和销售，但必须满足许可条件；不等于整个候选、项目、样本或所有依赖均获商业授权。当前不得将整体标为商业许可已核清。** 下述是对本工作区源码的静态核查，不是运行时类加载轨迹、逐字节相似性鉴定或完整 SBOM。

- 指定入口 [M1ThresholdHeightGallery:17-20](../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/terrain/candidate/M1ThresholdHeightGallery.java#L17-L20) 构造显式 `LandRegionContext` 与阈值 0.30 的 `CoherentRegionDistribution`；二者各构造 `RecipeMorphCatalog`，并由 `CoherentMorphWorld` 接到 `RegionalMorphWorld` 和 `ShallowRegionMatcher`。`RegionalMorphWorld:37-44` 还构造 `OceanRecipeCatalog`、`MorphCatalog`、`NodeMorphEngine`，不是仅有陆地配方。`HeightCurve`、`TerrainParams.defaults()` 来自主源集。地貌族由 [RecipeMorphCatalog:254-315](../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/terrain/candidate/RecipeMorphCatalog.java#L254-L315) 构造；`LandRegionContext:21-31` 另构造台地覆盖。
- **反射是实际依赖，不只在探针里。** `RecipeMorphCatalog.GALLERY_SEED` 在类初始化时调用 `canonicalSeed()`，以 `Class.forName` 加载 `LandCompositionGallery` 并读取 `SEED`；即使其他 seed 也要加载该类。构造函数在 seed 等于该常量时额外调用反射工厂 `buildCombineRegional`，生成 `CombineCandidate`；非正典 seed 不调用该工厂，但仍读取静态 seed。见 [RecipeMorphCatalog:108-109、254-261、520-540](../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/terrain/candidate/RecipeMorphCatalog.java#L520-L540)。指定导出的 `RegionalMorphGallery.SEED` 直接等于 `GALLERY_SEED`（`RegionalMorphGallery.java:17`）；后者反射读取 `LandCompositionGallery.SEED`（该文件第 35 行，当前为 `9139912035078620160L`），因此此导出的目录构造走正典反射工厂分支。
- `RecipeMorphCatalog:312-410` 反射读取各配方私有 `height`、`dbgBase`、`dbgHills`、`seed` 等；`LandRegionContext:21-31` 经 [FullMorphLand:482-488](../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/terrain/candidate/FullMorphLand.java#L482-L488) 反射读取 `CombineCandidate` 常量。`GeometryTransform` 和 `LocalBoundaryGeometry` 也有字段反射，后者的旧工厂路径还反射 `LandCompositionGallery`；是否在具体路径调用要按具体构造入口判断，不能把所有反射路径都宣称已执行。
- [LandCompositionGallery.java](../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/terrain/candidate/LandCompositionGallery.java) 静态引用 NightConfig、`GeoGenesisConfig` 及多种主源集类，其工厂又构造地貌族。**类可加载、编译依赖、方法实际执行和随包分发是四个不同集合**；精简独立移植要实际解析编译/运行闭包并验证，不可仅照入口 import 裁剪。
- [build.gradle:113-135、207-213](../forge-1.20.1-47.4.10-mdk/build.gradle#L113-L135) 将 diagnostics 编译/运行类路径接入 main 输出及其类路径，显式声明 Forge；不启动 Minecraft 不等于运行或打包依赖全部只有 JDK。`ShallowRegionMatcher` 使用 `java.awt.geom.Line2D`，独立运行环境还须有相应 Java 模块。NightConfig 是 Gallery 的直接 import，但尚未核实实际交付版本、传递依赖和许可证；Forge/Minecraft、JDK 及其他实际纳入的库、构建工具和资源须按最终交付分别核查，不能按 FreeTerraForged MIT 一并推定。

## 本地上游逐文件证据和版权链

证据基准：`参考/sources/FreeTerraForged-1.21.1/`（下称 FTF）与 `参考/sources/TerraForged-0.3.x/`（TF），均为本地被忽略的参考副本，均无 `.git`；文件夹名不是已验证的提交号或来源完整性证明。FTF `README.md:7-12` 自述上游 TerraForged、ReTerraForged、NeoTerraForged，并声称历史贡献均沿用 MIT；这是上游项目声明，不是对每一段历史版权的独立证明。

| 候选 / 实际用途 | 已查本地 FTF 上游原文（路径均相对 `common/src/main/java/raccoonman/reterraforged/`） | 逐文件声明与证据边界 |
|---|---|---|
| `PlainsCandidate`、`HillsCandidate`、`PlateauCandidate`、`BadlandsCandidate`、`MountainsCandidate` | `world/worldgen/cell/terrain/Populators.java`：分别查 `makeSteppe/makePlains`、`makeDales/makeHills1/makeHills2`、`makePlateau`、`makeBadlands`、`makeMountains*`；候选文件头给出相应行号 | 该上游文件从 package 开始，无文件级版权/许可头；候选自述逐行复刻及本地改动，有实际目录构造证据。根 MIT 是本地适用声明线索，但未证明全部历史贡献者及所有具体片段谱系。 |
| `CombineCandidate` / `RecipeMorphCatalog` | `world/worldgen/cell/terrain/provider/TerrainProvider.java`，包含 `generateTerrain`、`combine` 等；候选 `CombineCandidate` 文件头还指向 `Heightmap` 的山形覆盖 | `TerrainProvider.java` 从 package 开始，无文件级版权/许可头；对应组合思路及部分实现应保守视为需保留上游声明，`Heightmap` 等链条仍需进一步逐片段比对。 |
| `RegionPartition`、`RegionCellTable` | `world/worldgen/cell/terrain/region/RegionModule.java`；`world/worldgen/noise/NoiseUtil.java:19-23,235` 的 `CELL_2D` | 两个上游文件从 package 开始，无文件级版权/许可头。本轮按源码浮点字面量顺序用正则提取比对，候选 256 对与上游 `CELL_2D` 的 256 对全部一致；这是字面量比对，不是历史权属证明。 |
| `RefNoise` | `world/worldgen/noise/NoiseUtil.java`；`noise/module/Perlin.java`、`Simplex.java`、`Worley.java`、`Terrace.java`、`Erosion.java` 等 | 上列文件均从 package 开始，无文件级版权/许可头；扫描 FTF `world/worldgen/noise/**/*.java` 未发现含 copyright、license/licence、author 或 github.com 的行。扫描不等于全量逐方法溯源，`RefNoise` 所有算子、常数和更早来源仍须逐项复核。 |
| `TerrainBlenderCandidate`，仅在其实际被分发时 | 本地 TF `src/main/java/com/terraforged/mod/worldgen/terrain/TerrainBlender.java:1-23` | **文件头明确是 `Copyright (c) 2021 TerraForged` 的完整 MIT**，不同于 TF 根 LICENSE 的 2020 声明；须保留适用的 2021 文件头，不能以 2020 代替。当前未证明该类参与本入口求值，若交付整个 diagnostics 则不能因未调用而忽略。 |

以上“无文件级头”仅表示对列出的本地文件/范围未找到声明，不代表文件无版权，也不排除更早上游文件头。FTF 根 LICENSE 原始字节 SHA-256 `488ad4a0c0c1351434ded97a038e3d643545ed9aca9276d364fdea986c949527`；TF 根 LICENSE 原始字节 SHA-256 `c1507510b0d8a9c73a90f1c801fef6e09c20393f13572437b349d8b781097b64`。本地来源与许可证文件未取得可验证的上游提交身份。

## 追加核查：同名 Erosion 与具体版本

上游固定提交 `8efd92d61dac69e9e2e49fc346b6fd5c022644a8` 的许可补丁明确将 `densityfunction/tile/filter/Erosion.java` 排除于 MIT、标为 MPL-2.0；这是 Phacelle 地形过滤器，不应因文件同名就把该例外套给本项目候选。

候选 [RefNoise.java:598-703](../forge-1.20.1-47.4.10-mdk/src/diagnostics/java/com/geogenesis/worldgen/terrain/candidate/RefNoise.java#L598-L703) 对应另一个上游 `noise/module/Erosion.java`，其本地副本与 FTF 固定提交 `afbfbc439f07e2118c9f03f01abf1210bbd56deb` 的同路径文件逐字节一致，SHA-256 为 `f65048901f3e3946a5267b8254bf320c7d92ee1ad9781bcb662959f0bb6619c9`。该提交的根许可声明为 MIT；这只固定该文件和许可文本，不证明其他本地参考文件属于同一提交，也不证明完整历史版权谱系。当前未找到上述 MPL 例外适用于候选的证据，也不能因此断言全部来源已核清。`NodeMorphEngine` 在山地权重非零时存在对候选侵蚀噪声的条件调用；不是对每个样本均执行的实测证明。

历史 [classpath.txt](../handoff/attachments/m1a-m1b-20261101/classpath.txt) 列有 NightConfig core/toml 3.6.4；其 Maven POM 标记 LGPL-3.0。出现在类路径不等于已实际执行或必须随独立交付包分发；具体版本、依赖与分发义务仍须对实际交付物核查。

## 本轮补证：固定上游文件、反射闭包与分发

### 固定版本逐字节比对（不是整体版本认定）

以 [FTF 固定提交 afbfbc439f07e2118c9f03f01abf1210bbd56deb](https://github.com/ETcodehome/FreeTerraForged/tree/afbfbc439f07e2118c9f03f01abf1210bbd56deb) 的 raw 文件与本地参考副本原始字节比较：根 `LICENSE`、`world/worldgen/cell/terrain/region/RegionModule.java`、`world/worldgen/noise/NoiseUtil.java`、`world/worldgen/noise/module/Erosion.java` 四个文件分别一致；后面三个 SHA-256 分别为 `9241e1170c1eb0560d0646912f4baf77a54652b308c3d2d297cf8e64ac488be0`、`1b9647baea771ad9146992f34609b512258a8907ed2fab8a35c8b985b19ae139`、`f65048901f3e3946a5267b8254bf320c7d92ee1ad9781bcb662959f0bb6619c9`，根许可哈希见上文。路径前缀为 `common/src/main/java/raccoonman/reterraforged/`。但本地 `Populators.java` 与固定提交同路径文件长度分别为 23052 / 17388 字节，`TerrainProvider.java` 分别为 8967 / 5475 字节，**均不一致**；不能把该提交身份或其当时根许可直接套给这两个本地文件的具体版本。差异原因、真实提交与逐片段谱系仍未知。该对比证明对应文件字节一致，不证明整个参考目录来自同一提交，也不替代历史权属调查。

### 同名文件与 MPL 范围

[上游许可修正补丁 8efd92d](https://github.com/ETcodehome/FreeTerraForged/commit/8efd92d61dac69e9e2e49fc346b6fd5c022644a8.patch) 的例外指向 `etcodehome/freeterraforged/world/worldgen/densityfunction/tile/filter/Erosion.java`（Phacelle filter，补丁新增 Rune Skovbo Johansen 和 2026 FTF Contributors 声明），并非前述 `raccoonman/reterraforged/world/worldgen/noise/module/Erosion.java`。两个路径、实现及版本须分别追踪，不能仅由类名认定同许可；也不能用一个文件的 MIT 证据排除其他代码来源的 MPL 风险。如果最终交付包含 MPL 覆盖软件或其修改，[Mozilla 原文 §1.10、§3.1–3.4](https://www.mozilla.org/en-US/MPL/2.0/) 要求按条文处理覆盖源码、可执行形式对应源码提供及保留声明；新文件包含覆盖代码也可能是 Modification。Larger Work 可按选择的条款分发，但覆盖部分仍须遵守 MPL；不能简单说“同目录都开源”或“改名即可规避”。当前尚无证据表明此例外文件进入本候选交付。

### 运行与反射的证据等级

历史 [commands.json](../handoff/attachments/m1a-m1b-20261101/commands.json#L5-L8)、[height030-exit.txt](../handoff/attachments/m1a-m1b-20261101/height030-exit.txt) 和 [verification.json](../handoff/attachments/m1a-m1b-20261101/verification.json) 是一次指定入口运行及结果记录，不是类加载、方法调用或发行清单。结合上文的类初始化与正典 seed 源码，可识别 `LandCompositionGallery` 的反射加载、字段读取和工厂分支为该源码路径所需；这属于静态控制流结合历史运行记录，**并非本轮采集到的 JVM 类加载/反射事件轨迹**。`SandboxInventoryProbe` 也是独立探针，其对该工厂的反射不等于导出器的跟踪。动态路径、字段解析、JVM 解析时机和外部库实际加载仍须对冻结的实际二进制、完整类路径、不同入口及代表性 seed 采集类加载日志并做缺依赖负控；日志只覆盖被执行情况，不证明所有分支或版权闭包。发行源码即使未执行也需审其许可；历史运行时类路径中的每个库也不等同交付包内容。

### NightConfig 3.6.4：证据与条件性义务

历史 `classpath.txt` 明列 `com.electronwill.night-config:core:3.6.4` 与 `toml:3.6.4` 的 JAR 路径；[Maven Central core POM](https://repo.maven.apache.org/maven2/com/electronwill/night-config/core/3.6.4/core-3.6.4.pom) 和 [toml POM](https://repo.maven.apache.org/maven2/com/electronwill/night-config/toml/3.6.4/toml-3.6.4.pom) 都标记 GNU LGPL 3.0，后者声明编译依赖同版 core。POM 是发布元数据，不是对具体发行包字节、独立版权谱系或“任何版本均同许可”的证明；本轮未核对实际 JAR 与上游发行字节、内嵌许可或最终包是否包含它们。

如果实际分发 LGPL 库及与之组合的作品，应根据 [LGPL 3.0 原文](https://www.gnu.org/licenses/lgpl-3.0.html) 和其引用的 [GPL 3.0 原文](https://www.gnu.org/licenses/gpl-3.0.html) 核对：提供适当版权/许可通知和许可证文本、按适用条款提供库对应源码及修改、允许为调试库修改而逆向工程，并按 LGPL §4 提供合适共享库机制或可重链接材料等符合条件的方式；用户产品还可能触发安装信息等额外要求。原样独立 JAR、合并/shading、修改库、仅运行不分发、以及由客户自行取得库的场景事实与义务可能不同，不能一概断言；仅有链接不等于已经满足源码和重链接条件。LGPL 本身不禁止商业使用或出售，也不自动要求整个独立应用改为 LGPL；具体组合及实际许可适用须按交付方式评估。本轮 GNU 官网正文抓取失败，上述为待发行前再次逐条核对的摘要，而非已验证合规结论。

### 追加：历史 NightConfig 实际 JAR 与发布物核对

本轮只读检查历史 `classpath.txt` 中实际存在的两个 Gradle 缓存 JAR，并通过 HTTPS 直接获取 Maven Central 同坐标发行 JAR，在内存中逐字节比较：

| 坐标 | 字节数 | 本地及 Central JAR 的 SHA-256 | 比较 |
|---|---:|---|---|
| [`com.electronwill.night-config:core:3.6.4`](https://repo.maven.apache.org/maven2/com/electronwill/night-config/core/3.6.4/core-3.6.4.jar) | 199834 | `1bc71c67b54ba9c8e5030b3ddaef7dee16ae43fee6850368f76b0cffef819700` | 原始字节一致 |
| [`com.electronwill.night-config:toml:3.6.4`](https://repo.maven.apache.org/maven2/com/electronwill/night-config/toml/3.6.4/toml-3.6.4.jar) | 31816 | `3cb3d0ef3352e204cda68215fbe41afdc7f56425ef4fd8e84006964a656117d4` | 原始字节一致 |

这补充了上文第 65 行的未核对项：**核对的是当前本机缓存与本次获取的 Central 字节，并非历史运行时或未来发行包的独立哈希证明**。枚举两个 JAR 的 ZIP 条目名称，均未发现名称包含 `license`、`copying` 或 `notice`（不区分大小写）的条目；这只是文件名检查，不证明正文或其他名称的条目不存在许可信息，也不证明 JAR 独立分发即可满足许可条件。两份发布 POM 中，toml 声明 compile 依赖同版 core，core POM 未声明 `<dependencies>`；这只描述对应 POM 的直接依赖，不是 Forge 整体解析图、运行时使用情况或最终交付 SBOM。SCM 指向 `stable-3.x` 分支，未提供固定源码提交，尚不能据此认定对应源码快照。

FTF 两个不一致的本地上游文件仍未固定来源：本轮访问 GitHub 文件提交历史 API 返回 HTTP 403，未取得可用的提交历史，不能把失败当作无匹配提交的证据；仍需从可访问的完整历史定位并对原始文件及同一提交许可逐字节核对。

## 后续补证：两个重点文件的共同上游快照

**本节更新上文第 39、53、80 行关于这两个文件尚未固定的历史状态**：现已找到同一提交下两文件及根许可证均与本地逐字节一致；但这不是整个参考目录或所有历史权利的认定，也不构成法律意见或商业发行保证。

### 方法、结果和可复核证据

只读从 `https://github.com/ETcodehome/FreeTerraForged.git` 克隆到系统临时目录的裸仓库（未检出或修改现有源码），以 `git log --all --format=%H -- <路径>` 查各路径历史，再以 `git ls-tree` 对照本地 `git hash-object`。对命中的提交用 `git show <提交>:<路径>` 获取原始对象字节，Python `read_bytes()` 与其直接作字节相等比较并计算 SHA-256；**并非只比较文本、行尾或哈希**。共同证据快照为 [e4ae4ca382757e0a9714beee54160b50ba366ba6](https://github.com/ETcodehome/FreeTerraForged/commit/e4ae4ca382757e0a9714beee54160b50ba366ba6)，提交信息 `Magic numbers`，作者和提交时间均为 `2026-08-17T19:58:09+08:00`。路径前缀 `common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/terrain/`：

| 同一提交路径 | 字节数 | SHA-256（上游对象和本地一致） | Git blob ID |
|---|---:|---|---|
| [Populators.java](https://github.com/ETcodehome/FreeTerraForged/blob/e4ae4ca382757e0a9714beee54160b50ba366ba6/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/terrain/Populators.java) | 23052 | `ff447136c7a95c26bff209a213a84566732cc1be23b793709701422d45fb2bd7` | `523ebae514a07a17e6a5b2b778addfc9970dc30c` |
| [provider/TerrainProvider.java](https://github.com/ETcodehome/FreeTerraForged/blob/e4ae4ca382757e0a9714beee54160b50ba366ba6/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/terrain/provider/TerrainProvider.java) | 8967 | `9d2bed05aa75ebb6d93b0d0464425611cd0b30c6b23c2f578654d13045df4d7e` | `597cfaec8f6734efd594117cc3102d05b64b0a7a` |
| [根 LICENSE](https://github.com/ETcodehome/FreeTerraForged/blob/e4ae4ca382757e0a9714beee54160b50ba366ba6/LICENSE) | 1070 | `488ad4a0c0c1351434ded97a038e3d643545ed9aca9276d364fdea986c949527` | 未在本轮记录 |

该提交根许可证文本与本地根 LICENSE 原始字节一致，为下文保留全文的 MIT，声明 `Copyright (c) 2023 ReTerraForged`。两文件本身无文件级许可头；根声明是同一快照的明确许可证据，不独立证明其所有代码的历史权利链或不存在其他适用声明。MIT 允许其覆盖范围内的商业使用和销售，须在副本或实质部分保留版权和许可声明；是否覆盖最终作品的每部分仍需核实。

另一个匹配节点 [131eefa135a6a189e376ffec1fe2dc44620050df](https://github.com/ETcodehome/FreeTerraForged/commit/131eefa135a6a189e376ffec1fe2dc44620050df) 的 `TerrainProvider.java` 与本地逐字节一致、根 LICENSE 同上，但其 `Populators.java` 为 19712 字节，SHA-256 `e838f8d895e11b2b2b211bedb8fb992d18f57858fe9a99f9d90c27bd5a2eacfc`，与本地不同。说明**单文件匹配并不能唯一标识整个快照**；e4ae4ca 是已验证的共同快照，不宣称其为唯一匹配或本地副本实际下载来源。上文 afbfbc 对两文件不一致的比较仍然正确，仅当时“未找到匹配”的状态已被本节补证取代。

### 边界、依赖与失败记录

- 裸仓库报告 `--is-shallow-repository=false`，克隆时 HEAD 为 `4dce69465e1ec77457365d119d3259146ea8cae5`；仅搜索当时取得的 refs 和上述旧路径历史（分别 20、14 个提交），不代表所有远端隐藏引用、重命名前路径或全历史权属均已穷尽。原始 blob 可由上述固定提交链接和 Git 命令复核；Git 提交时间是上游元数据而非独立时间戳。
- 上一轮 GitHub 提交历史 API 返回 403；本轮 Git 克隆方式成功，不能把先前失败解释为不存在匹配。曾尝试 `git rev-list --objects --all` 全对象搜索，耗时未完成而主动终止；实际结论来自路径历史筛选和固定提交逐字节验证，而非全对象穷举。
- 本轮没有逐片段验证 GeoGenesis 各候选与该快照的全部对应关系、贡献者及更早 TerraForged/ReTerraForged 权属，也没有证明其他参考文件属于此提交。上文反射依赖、NightConfig、Forge/Minecraft、JDK、其他依赖及最终发行内容的未知项仍然成立；MPL 同名文件排除判断也不得推广到未审查文件。最终交付应冻结物料清单、核对所有适用许可及声明，必要时寻求专业意见。

## 本地上游许可证据

- 本地 FreeTerraForged 1.21.1 参考副本根 `LICENSE` 是 MIT，版权声明为 `Copyright (c) 2023 ReTerraForged`，文本要求在软件的所有副本或实质部分中包含版权及许可声明。本地 `参考/` 被忽略，不能假设交付包包含该文件；原文如下，供保留及追溯：

```text
MIT License

Copyright (c) 2023 ReTerraForged

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

- TF 根 LICENSE 为 MIT，版权声明 2020；第 7 行原样为 `without restricted`，不自行修正。涉及 `TerrainBlender.java` 时还须保留其不同的 2021 文件级声明。以下留存原文；实际发行仍须将适用声明纳入发行物。FTF 根声明不能替代其他历史版权核查。

TF 根 LICENSE 原文：

```text
MIT License

Copyright (c) 2020 TerraForged

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restricted, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

TF `TerrainBlender.java:1-23` 原文件头：

```text
/*
 * MIT License
 *
 * Copyright (c) 2021 TerraForged
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
```
- [LICENSE.txt](../forge-1.20.1-47.4.10-mdk/LICENSE.txt#L1-L4) 描述 Forge/Forge Mod Loader 的许可，不自动代表 GeoGenesis 全部代码或第三方素材的授权。[gradle.properties](../forge-1.20.1-47.4.10-mdk/gradle.properties#L47-L49) 仍声明项目 `All Rights Reserved`；本次没有更改。

## 本轮补充：生产代码线索与一个实际 JAR 的内容

### 生产 Simplex3：明确的第三方实现线索，许可尚未固定

[Simplex3.java:3-18、35-43、85-126](../forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/noise/Simplex3.java#L3-L18) 称其为 Stefan Gustavson 经典 3D Simplex 实现，使用 12 梯度、四面体顶点排序及贡献核；同时声称自己编写、未移植 RTG OpenSimplex。`Seed.java:40-43` 也提及经典 Gustavson 三级置换形式。这些是作者注释与可见算法结构，**不是**已找到具体上游源码版本、复制关系或适用许可的证明；算法共性或相似性不能单独决定版权归属。当前未固定被参考的 Gustavson 文件、版本、原始版权与许可，不可直接标为 MIT、公有领域或已清核；应取得实际参考资料及作者记录，逐段比对相应上游固定版本和许可。条件允许时，仅在确认实际适用许可并满足其条件后商业分发；权利链无法确认时需进一步取得授权或审查独立实现证据。

此线索并非只涉及诊断：生产 [CaveShape.java:103-106](../forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/cave/CaveShape.java#L103-L106)、[CaveBiomeSelector.java:125-136](../forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/cave/CaveBiomeSelector.java#L125-L136) 和 [OreVeins.java:263-264](../forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/ore/OreVeins.java#L263-L264) 静态构造 `Simplex3`；这不证明本轮运行了每条路径。下述旧 JAR 也包含这些类，但仅凭类名不能证明二进制与当前源码一致。候选入口对它是否有依赖尚未证明，不能把生产线索自动套入独立候选交付。

### 追加：Simplex3 的历史及具体对照源码

- 本仓库 `git log --all --follow -- <Simplex3 路径>` 在当前可见 refs 中仅找到引入提交 [e74042e6224bba1e0df519d892e658d1ab1d1748](../forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/noise/Simplex3.java)，提交说明称新增经典 Gustavson 3D Simplex；该提交对象与当前工作区文件的 Git blob 均为 `eb3ded85dbd0a683eff791b8fd45e7be9ddf499e`。引入时已有上述“自己写”和“可对拍”注释，但没有具体参考网址、版本或授权记录；可对拍不等于实际对拍或独立创作已经证实。可见历史不是工作区外、未保存草稿或其他 refs 的完整历史。
- 一个**具体对照，不是已证实的来源**：[Stefan Gustavson 的 `simplexnoise1234.c` 固定提交 a624f5a18395c0ebc04738191efddf1dbd2cad9d](https://github.com/stegu/perlin-noise/blob/a624f5a18395c0ebc04738191efddf1dbd2cad9d/src/simplexnoise1234.c) 的 `snoise3` 与生产代码具有相同的六分支比较顺序、顶点偏移赋值、坐标变量及偏斜计算结构；这值得进一步溯源，但算法约束和共同参考也可产生相似结构，**不足以认定复制或直接来源**。差异包括该 C 文件用 `0.5` 核、`72` 缩放、固定置换表和位运算梯度；生产 Java 用 `0.6`、`32`、项目 `Seed` 及 12 项梯度表，并在输入加 0.5。未做完整逐 token 比对或创作过程调查。
- 该固定 C 文件头自述作者 Stefan Gustavson，称代码原为 GPL、2011 年 2 月起由作者释放至 public domain，并允许任意使用；这是**该具体文件的作者声明**，并非 MIT 或 LGPL，也不能自动赋给生产文件、其他 Gustavson 实现或其更早版本；不同法域的放弃权利效力和可能的其他权利仍需专业核查。上游同一文件还自述 3D 数值等价于 Ken Perlin 的 Java 参考并称自行重写；本轮未核实该 Java、完整历史权利或生产作者是否接触此 C 文件。
- 本地被忽略的 `参考/sources/Realistic-Terrain-Generation-Plus-main/src/main/java/rtg/api/util/noise/OpenSimplexNoise.java` 共 552 行、SHA-256 `8f1d7903d7a061693c3c6ea065f15219ad7c865b76de131d15f9d8285b25fdc7`；其文件头列 KdotJPG、DigitalShadow 等，使用不同的 OpenSimplex 查表和梯度体系，与生产注释所述参考相吻合，但不能仅凭长度和名称证明是创作时使用的同一副本，亦不能排除其他来源。本地参考根 `LICENSE.txt` 是 GPL 3.0 文本；仅有根文本不能独立固定每个文件的历史授权，也不因参考存在而使生产 `Simplex3` 自动受 GPL 约束。本地副本的固定上游提交及文件级授权仍未知。
- 上述文件级证据与本文已有 **FTF 的 MIT**、历史 NightConfig **LGPL 3.0**、Forge/FML 的独立许可线索分别适用，互不替代；没有证据表明本次对照的 Gustavson C 文件就是生产来源，也没有因此证明 Simplex3 为 MIT、公有领域或 LGPL。发行前仍须询问作者取得实际参考链接、版本、草稿或对拍记录，核查 `Simplex`/`Seed` 等关联代码和最终物料；必要时由专业人士判断或取得授权。

### 具体旧 JAR：实际内容，不是未来发行清单

只读以 Python ZIP 枚举本机 `forge-1.20.1-47.4.10-mdk/build/libs/geogenesis-0.0.1.jar`：1,240,170 字节，SHA-256 `c1d9e914c4572a6c02fc084a6c6cfd181a3c368308e6ecae755e2e26d466dadd`，共 529 个 ZIP 条目。这个文件时间为 2026-10-26，是**既存旧构建物**，并非本轮新构建，不证明它将被交付或对应当前未提交源码。

- `META-INF/mods.toml` 实际写有 `license="All Rights Reserved"`，声明 Forge 必需版本 `[47,)`、Minecraft 必需版本 `[1.20.1,1.21)`；这是加载器元数据所声明的范围，不是固定的运行环境版本，也不授予或取代第三方许可。
- 条目包含 `Simplex3.class`、`CaveShape.class`、`OreVeins.class`；名称包含 `/candidate/` 的条目为零，名称含 `electronwill` 的条目为零，亦未发现以 `.jar` 结尾的嵌套条目。**不能据此排除改包名、内联复制、其他第三方来源或外部运行依赖**，更不能推定新版候选交付物的内容。
- 条目名中未发现 `license`、`notice`（不区分大小写）；这只是文件名检查，不能证明所有文本中不存在声明，也不能据此判定整个包合规或违规。该 JAR 中没有按名称发现前述 FTF/MIT 通知，并不能反过来证明它包含或不包含任何需要该通知的实质代码。
- `mods.toml` 中 Forge/Minecraft 是必需的外部环境声明，**不是该 JAR 已打包它们的证据**。仓库 [LICENSE.txt:1-4、23-35](../forge-1.20.1-47.4.10-mdk/LICENSE.txt#L1-L35) 描述 Forge/FML 一般 LGPL 2.1 及例外，另明确称 MCP 数据的再分发需独立许可；它不是对具体 Forge 47.x 发布字节、所有组件或 Minecraft 的固定许可证核验。若交付 Forge、Minecraft、映射数据或其他游戏内容，须逐项确定版本、实际文件及对应权利；普通模组使用与再分发游戏或工具数据的条件不可混同。LGPL 不排斥商业销售，但实际适用范围和对应源码、声明及替换/重链接等条款须依具体交付核对。当前不对整个产品作商业发行保证。

本轮没有运行构建或动态类加载，也未建立完整生产源码溯源、二进制来源或未来发行 SBOM；以上固定哈希仅标识被检查的旧 JAR。发行前需对最终文件重新计算哈希、核查条目和适用声明，并分别核实最终运行环境、外部依赖、上游权属与商业条款。

## 继续取证：Simplex Java 对照、资源与 Minecraft 条款

### 更接近的 Java 对照，仍不是来源认定

- 本轮取得第三方镜像的固定版本 [SimplexNoise.java（GitLab 提交 33c29964）](https://gitlab.com/lfex/loise/-/blob/33c29964e3b560c96dea9bb58cde8fd96bc2224f/resources/implementations/SimplexNoise.java)：文件自称 2012-03-09 版本，基于 Stefan Gustavson 示例并列 Peter Eastman 优化，文件头声称原作者将其置于 public domain，使用和署名按其声明处理。**这是镜像中的文件头，不是本轮从作者原站取得的原件，也不是对所有贡献者权利的独立核验。**
- 逐块人工对照该 Java 3D 函数与 [Simplex3.java:30-43、68-131](../forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/noise/Simplex3.java#L30-L43)：12 个梯度向量的数值及顺序、F3/G3、六个顶点排序分支、0.6 贡献核和 32 倍缩放均相同；`fastFloor` 的整数转换及负数修正形式也相同。生产实现使用世界种子构建的 `Seed` 而非该镜像固定置换表，输入加 0.5，将四个角合并为 `corner`；顶点偏移计算和变量命名有相似之处。这比上文 C 文件在核、缩放及梯度上更接近，但这些特征可来自共同算法或其他中间版本；**不能据此判定复制、独立创作、实际接触或将镜像声明自动授予生产代码**。未进行全历史、逐 token 或独立创作鉴定。
- 相关 [Simplex.java](../forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/noise/Simplex.java#L6-L9) 自述 Gustavson 2D 经典实现；`git log --all --follow` 在现有 refs 最早见提交 `8d8fb44991745b45a0d6f19ca0cda5cd89059837`，该提交已含这条说明。`Seed.java` 在同一可见提交出现，之后引入 `gradIndex3` 的是上文 `e74042e`。这只建立可见仓库时间线，最早快照不等于作者初创时间，也没有给出具体参考及授权。应向实际作者索取参考链接、版本、草稿、聊天及对拍记录，并分别核查 2D/3D/Seed；不存在这些记录不等于证明侵权或证明独立创作。

### 生产资源与第三方条款：按物料分别确认

- 本轮只读枚举 `src/main/resources`：8 个文件（`pack.mcmeta`、2 个颜色 JSON、2 个语言 JSON、世界预设 JSON、`minecraft` 命名空间世界预设 tag JSON、`META-INF/mods.toml`）；没有按扩展名发现纹理、音频或模型文件。`src/generated/resources` 本轮未列出文件。**此为当前目录枚举，不是对旧 JAR、其他源集、生成结果或最终发行内容的完整清单，也不能以扩展名证明素材原创。** `data/minecraft/tags/worldgen/world_preset/normal.json` 列有 5 个 `minecraft:` 预设 ID 和本模组 ID；命名空间或标识符本身不证明复制了原版 JSON，其来源及具体表达仍须核实。
- [颜色表](../forge-1.20.1-47.4.10-mdk/src/main/resources/assets/geogenesis/colormap_preview/geogenesis.json#L33-L65) 含 inferno、viridis、plasma、magma、cividis 的少量数值锚点；可见历史最早提交 `8d8fb44`，但历史未说明来源。对照 [Matplotlib v3.10.0 的色表数据](https://github.com/matplotlib/matplotlib/blob/v3.10.0/lib/matplotlib/_cm_listed.py)，例如 viridis 首项 `[0.267, 0.005, 0.329]` 与该表首项近似取三位小数；其余色表及完整锚点未逐项比对。这是需要调查的来源线索，不证明复制自该版本或一定采用其许可证。该版本 [Matplotlib LICENSE](https://github.com/matplotlib/matplotlib/blob/v3.10.0/LICENSE/LICENSE) 有保留声明及衍生版本变更摘要等条款；另有色表专门声明的可能，**不得只按项目总许可或相似名称定案**。应询问作者并固定实际色表来源、版本和对应文件许可，尤其不可把一种色表的声明直接扩展到 cividis 或全部资源。
- [Mojang 官方 EULA 的 USING mods](https://www.minecraft.net/en-us/eula) 当前明确称从零创建的 Mod 可由作者持有，但以不出售或试图从中获利、也不分发游戏修改版为条件；[官方 Usage Guidelines 的 Extended functionality and modifications](https://www.minecraft.net/en-us/usage-guidelines) 另规定只分发 mod 而不是 modded version，并列举其他限制。故**不能因为 MIT/LGPL 允许商业使用，就认定付费销售本 Minecraft 模组获 Mojang 许可**；也不能将允许某些服务器收费或视频广告的其他章节自动套用到售卖模组。条款可能变化，具体产品、地区、商业模式和适用版本须发行前核验并必要时咨询专业人士或权利人；本记录不代表用户接受条款或取得授权。

## 专项结论：移植到朋友的独立商业游戏

**问题口径：朋友将地形代码或其改写移植到自己的独立游戏并商业化，不是在销售 Minecraft 模组。** 上文 Mojang 模组销售条款不能直接作为禁止独立游戏收费的结论；反过来，脱离 Minecraft 运行也不能自动清除复制内容的权利限制。能否商业发行取决于实际移植和交付内容、各权利人的授权及其条件；目前物料未确定，不能给出整体已获许可的保证。本文不是法律意见。

### 本次补证：将可消除的固定版本未知项与交付未知项分开

以下只针对新版 +0.30 诊断高度链的来源线索，不覆盖旧生产地形或未来朋友的游戏。此前“其他参考文件是否属于 e4ae4ca 尚未证明”的概括，**对下表列出的七个文件现已消除**；对未列文件仍成立。本轮从固定提交的 GitHub raw URL 取原始字节，与本地 `参考/sources/FreeTerraForged-1.21.1/` 中对应文件用 Python 字节相等比较并计算 SHA-256；不是只比文件名、长度或文本。七项全部相等。上文已固定的 `Populators.java`、`TerrainProvider.java` 和根 `LICENSE` 加上本表，因此都有同一上游快照的证据；这仍不证明本地目录整体下载自该提交，或本项目每一行都源自对应文件。

固定提交：[e4ae4ca382757e0a9714beee54160b50ba366ba6](https://github.com/ETcodehome/FreeTerraForged/tree/e4ae4ca382757e0a9714beee54160b50ba366ba6)，下表路径前缀为 `common/src/main/java/raccoonman/reterraforged/world/worldgen/`；SHA-256 同时标识本地文件和本次获取的上游文件。

| 上游路径 | 字节 | SHA-256 | 本项目关联及范围 |
|---|---:|---|---|
| [cell/terrain/region/RegionModule.java](https://github.com/ETcodehome/FreeTerraForged/blob/e4ae4ca382757e0a9714beee54160b50ba366ba6/common/src/main/java/raccoonman/reterraforged/world/worldgen/cell/terrain/region/RegionModule.java) | 3919 | `9241e1170c1eb0560d0646912f4baf77a54652b308c3d2d297cf8e64ac488be0` | `RegionPartition` 自述参考，具体受保护表达尚待逐段判定 |
| [noise/NoiseUtil.java](https://github.com/ETcodehome/FreeTerraForged/blob/e4ae4ca382757e0a9714beee54160b50ba366ba6/common/src/main/java/raccoonman/reterraforged/world/worldgen/noise/NoiseUtil.java) | 18656 | `1b9647baea771ad9146992f34609b512258a8907ed2fab8a35c8b985b19ae139` | `RegionCellTable` 的 256 对字面量已全量比对一致；亦为 `RefNoise` 来源线索 |
| [noise/module/Erosion.java](https://github.com/ETcodehome/FreeTerraForged/blob/e4ae4ca382757e0a9714beee54160b50ba366ba6/common/src/main/java/raccoonman/reterraforged/world/worldgen/noise/module/Erosion.java) | 6439 | `f65048901f3e3946a5267b8254bf320c7d92ee1ad9781bcb662959f0bb6619c9` | `RefNoise` 噪声侵蚀；不是上文另一条路径的 MPL Phacelle filter |
| [noise/module/Perlin.java](https://github.com/ETcodehome/FreeTerraForged/blob/e4ae4ca382757e0a9714beee54160b50ba366ba6/common/src/main/java/raccoonman/reterraforged/world/worldgen/noise/module/Perlin.java) | 3380 | `30bc31c35f9801ecfebdbbe121313ed4efa6afa3ce07bce97a227159346b8816` | `RefNoise.Perlin` 明确指向其实现；候选有本地改写 |
| [noise/module/Simplex.java](https://github.com/ETcodehome/FreeTerraForged/blob/e4ae4ca382757e0a9714beee54160b50ba366ba6/common/src/main/java/raccoonman/reterraforged/world/worldgen/noise/module/Simplex.java) | 4017 | `7b154b3c6804afc33248ca6b2c2942c9a94d54aeac1e0d58ed95a70671908a9a` | `RefNoise` 的上游线索；不能与生产 `Simplex.java`/`Simplex3.java` 混同 |
| [noise/module/Worley.java](https://github.com/ETcodehome/FreeTerraForged/blob/e4ae4ca382757e0a9714beee54160b50ba366ba6/common/src/main/java/raccoonman/reterraforged/world/worldgen/noise/module/Worley.java) | 3607 | `15c5911263366a66589d8d945d2ab5f8c879ef3f0424a318fe17bc5d3cc41217` | `RefNoise` 对应算子来源线索，未证明所有变体均一一对应 |
| [noise/module/Terrace.java](https://github.com/ETcodehome/FreeTerraForged/blob/e4ae4ca382757e0a9714beee54160b50ba366ba6/common/src/main/java/raccoonman/reterraforged/world/worldgen/noise/module/Terrace.java) | 3775 | `087602c39508e0d32b1302d4a92abf039201958cae823867e0aaf646a2b95851` | `RefNoise` 对应算子来源线索，未完成全算子溯源 |

该固定提交的 [LICENSE](https://github.com/ETcodehome/FreeTerraForged/blob/e4ae4ca382757e0a9714beee54160b50ba366ba6/LICENSE) 是 MIT，明确 `Copyright (c) 2023 ReTerraForged`，全文已留存在上文；同提交 [README](https://github.com/ETcodehome/FreeTerraForged/blob/e4ae4ca382757e0a9714beee54160b50ba366ba6/README.md) 声称承继 TerraForged、ReTerraForged、NeoTerraForged 且历史贡献沿用 MIT。该 README 是上游声明而不是全部历史权属的独立证明；这些文件无独立文件级许可头也不等于无版权。**可以清除的是上述文件同一固定版本及该版本根许可具体内容的未知，不是第三方完整权利链或最终产品许可的未知。** 如交付实际包含受该 MIT 覆盖的实质部分，应保留该版权声明和完整 MIT 许可；有其他适用文件头或历史声明时一并核对，不能只提供链接或仅以 GeoGenesis 声明替代。

**关键依赖按必要性分层，而不是把历史 classpath 当成独立游戏 SBOM：**当前导出入口直接构造 `CoherentMorphWorld`、`LandRegionContext`、`CoherentRegionDistribution` 和主源集 `HeightCurve`/`TerrainParams`；实际区域求值另有海洋及陆地配方。`RecipeMorphCatalog` 类初始化反射读取 `LandCompositionGallery.SEED`，正典 seed 还反射调用其工厂；`ShallowRegionMatcher` 调用 `java.awt.geom.Line2D`，Java 原样运行需 `java.desktop`。这些是当前源码的具体静态依赖，不等于跨语言移植后必须保留同一类、模块或运行时。Gallery 直接 import NightConfig，历史类路径中 core/toml 3.6.4 的固定 JAR 和 POM 已在上文核实 LGPL 3.0 元数据，toml 的 POM 声明依赖 core；**尚未验证该导出路径实际加载 NightConfig，亦未证明它必须随最终游戏交付**。同理，Gradle 的 Forge 编译类路径不是独立游戏必须分发 Forge/Minecraft 的证据。删除反射或改写配置可能缩减依赖，但必须对实际移植版本做编译/运行、不同 seed、缺库负控和最终打包核查，不能提前宣布移除；朋友收到的源码及依赖也是独立交付，应分别审查。

### 按来源区分权利

- **GeoGenesis 自有部分**：用户只能许可自己有权许可的原创贡献，且应核实其他贡献者、雇佣或委托关系及已有协议；`All Rights Reserved` 元数据不是朋友的商业授权。建议由有关权利人以书面形式明确允许朋友及其团队复制、修改、跨语言移植、整合、商业发行和必要的再授权，约定交付版本、范围及限制。即便用户同意，也不能替第三方授予超出其原许可的权利；跨语言改写也不当然消除原代码许可。
- **FTF / TF 来源**：上文固定快照及文件头为相关 MIT 许可提供证据，其中 `RefNoise`、`RegionCellTable`、地貌候选等均有具体来源线索，但不是对所有文件及历史权利的保证。对确受 MIT 覆盖的代码，允许闭源、修改、商用和销售，**不要求整个游戏开源或支付许可费**；分发软件副本或实质部分时须随交付保留适用版权声明及完整许可声明，含免责条款。按实际来源保留所有适用声明，不能用项目自己的版权声明代替；例如纳入 `TerrainBlender` 时须核对其 2021 文件头。原样复制、改写或移植后如何对应适用声明应逐项核查，不能仅凭算法名称判断。
- **可能的 NightConfig LGPL 依赖**：历史诊断类路径有 core/toml 3.6.4，上文 POM 和 JAR 证据不证明朋友实际使用或分发。精简反射、配置及 Gallery 后**可能**不需要该库，但必须通过冻结源码依赖、编译/运行闭包和最终包验证，不能预先宣布已移除。若实际纳入，核对具体版本及 LGPL 条款、源码与修改提供、通知、允许调试库修改的逆向工程，以及可替换共享库或可重链接等适用要求；合并打包与独立 JAR 的处理不可一概而论。即使不向最终玩家分发，向朋友交付库也需评估相应分发义务。
- **Minecraft / Forge / 映射与资源**：生产生成器和诊断构建环境与其存在关联，但关联或 import 本身不证明游戏内复制了 Minecraft 代码、资源或映射数据；反之，不启动 Minecraft 也不证明完全没有复制。逐项检查实际移植的源码及生成代码、反编译实现、JAR、数据表、纹理音频、世界数据、标识符和映射文件；分别确认来源、版本和适用许可。仅保留概念或名称与复制受保护表达不是同一事实，具体判断需结合内容及法域。不得把 Forge 的许可视为 Minecraft 或映射数据的授权，也不得把 Minecraft 模组使用许可视为独立游戏复制其内容的授权。能否分发具体映射或其衍生数据须核对对应版本条款及使用方式，不作一概结论。
- **其他未知项**：若交付生产 `Simplex3` 或 `Simplex` 等，来源仍须按上文追溯；候选链是否包含它们尚未证明。预生成地图、颜色表、JDK、第三方引擎及素材也应按实际物料分别核查，不能套用 FTF 的 MIT。

### 可执行清单：从交接到发行

- [ ] 与朋友确认是新版诊断候选还是现有生产链，确定源码/二进制/数据/资源、语言、目标平台、交付方式与版本；分别列出给朋友和给玩家的两份物料清单，记录哈希及直接和传递依赖。
- [ ] 逐项标明权利人、来源版本、是否复制或改写、许可证、证据链接、要求及责任人；向有关作者取得自有部分的书面商业移植授权，未确认的共同贡献或第三方来源标记待解决。
- [ ] 对纳入的 FTF/TF 内容核对固定上游、文件头和历史声明，将适用版权及完整 MIT 文本随相应源码或发行包提供；检查打包产物中的声明实际可获取，而非只保留在本地参考目录。
- [ ] 检查反射及特殊 seed 分支、构建和运行依赖，核实 NightConfig 是否进入各交付物；如纳入，逐项落实该版本 LGPL 义务并测试替换/重链接方案；其他库和运行时同样单独核查。
- [ ] 明确列出并检查 Minecraft、Forge、MCP/Mojang 映射及资源是否被复制或分发；对每个实际纳入项核对适用协议，不能仅凭项目能独立启动排除复制。
- [ ] 发布前对最终安装包、更新包和源码交接包重新扫描、核对哈希及许可文本，保留授权和源码提供等证据；对未解决权利或条款冲突暂停相关内容交付，必要时咨询专业律师或权利人。

## 分发前清单与未决项

1. 先界定实际交付：哪些源码、二进制、资源、样本和外部依赖进入包；包括反射引用及特殊 seed 分支。不要把本表误当完整软件物料清单。
2. 对每个纳入文件核对具体来源版本、原文件头、上游版权人与完整许可证文本；保留适用的所有声明，尤其不要遗漏上游历史版权。对未知来源和许可冲突保留阻断状态，必要时寻求专业意见。
3. 对预生成地图数据能否分享、采用何种条款，结合实际内容及来源另行评估；代码 MIT 不自动回答所有数据、游戏素材或第三方资源问题。
4. 项目自己的授权与上游保留要求是不同问题；未获得作者明确授权前，不将整体重许可，也不把仓库或参考项目一并向朋友开放。
5. 确认最终发行物真正携带所需声明和完整许可，验证构建包内容；仅仓库内有本文不代表外发包自动合规。
