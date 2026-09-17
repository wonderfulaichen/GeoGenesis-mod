# GeoGenesis Mod

> Minecraft Forge 1.20.1 模组，自定义 `ChunkGenerator` + `BiomeSource`，程序化生成地形，并按气候驱动生物群系。

## 项目状态

- **当前版本**：`v0.0.1`（早期预览）— 程序化地形 + 气候群系 + 原版群系装饰（植被）+ D8 物理河网 + 湖泊/瀑布 + **洞穴**（洞穴几何/雕刻 + 地下洞穴群系）+ **地层/构造/侵蚀** + **地质矿脉**（按岩性成矿，**可开关**）
- **编译状态**：`BUILD SUCCESSFUL`
- **已知限制**：水面在多段线节点间插值的残差（~1.8 格，**非断裂**）· 湖泊群系映射未做 · `runLandEConformityProbe` 的 eLand 单步跳变 0.0225e **超过其自设目标 0.02e**（该探针自述"如实报 FAIL、作为继续收敛的靶子"，非回归）（详见 `forge-1.20.1-47.4.10-mdk/CHANGELOG.md`）
- **⚠️ 域扭曲（`WARP_AMP`）：已启用后又回退，当前为 `0.0`**。2026-09-16 曾启用 `WARP_AMP=40`（最长轴向直段 944 → 272 块），但**实机反馈河流/湖泊出问题已回退**（`PreviewDisplay` 缓存版本 70 即因此作废）⇒ 代码现为 `WARP_AMP = 0.0`（`TerrainCharacterField`，2026-08-03 起即 0，字段保留可恢复）。**README 原写"已修"是错的**（2026-09-18 校正）
- **★ 原版复用边界**：世界生成中「原版已经在做」的部分（植被 / 洞穴装饰 / 地牢 / **原版结构** / 紫水晶洞 / 化石 / 顶层雪冰 …）**一律复用原版，不自研**；本项目只自研原版确实没有的（地形本体 / 地层 / 构造 / 侵蚀 / 水文 / 洞穴几何 / 气候分类）。细则见 `AGENTS.md`「原版复用边界」与 `docs/analysis/原版复用对照审计-2026-09-16.md`

## 河流系统与第三方许可

河流系统（D8 汇流场派生河网 + 瀑布/峡谷横截面 + 洼地湖）为本模组**原创实现**：高位布源、沿 D8 下坡追踪汇入，形成树状水系；随机源为坐标派生确定性哈希，以满足跨 block 无缝要求。

本模组当前不含任何需署名的第三方算法代码，许可证为 **All Rights Reserved**。

## 核心命令

```bash
gradlew.bat build              # 编译 + 打包 jar
gradlew.bat runClient          # 启动 Minecraft 客户端
gradlew.bat runServer          # 启动服务器
gradlew.bat runData            # 运行数据生成
gradlew.bat runPreview --args=12345   # 独立预览窗口（纯 Java）
```

## 目录地图

### 根目录（权威文档）

| 文件 | 作用 |
|------|------|
| `AGENTS.md` | 项目速览 + 逐日「当前工作焦点」开发笔记（**需随代码变更手动更新**） |
| `ARCHITECTURE.md` | 核心架构设计，配置表，注册流程 |
| `forge-1.20.1-47.4.10-mdk/CHANGELOG.md` | v0.0.1 发布记录 |

### 源代码（`forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/`）

```
├── GeoGenesisMod.java                  # @Mod 入口，注册 CODEC
├── config/
│   ├── GeoGenesisConfig.java           # Forge 配置（COMMON/DCLIENT；含 Caves / Ores 两段开关）
│   └── ConfigSafe.java                 # 配置容错读取（诊断进程无配置时回退默认，绝不打挂世界生成）
├── worldgen/
│   ├── generator/
│   │   ├── GeoGenesisGenerator.java    # 主生成器，fillFromNoise / getBaseHeight（非阻塞降级）
│   │   ├── GeoGenesisBiomeSource.java  # BiomeSource（委托 BiomeClassifier）
│   │   └── VanillaDecorationFilter.java # ★ 注入过滤版 generationSettingsGetter（剔除破坏岩层的原版"岩块团块"）
│   ├── terrain/
│   │   ├── GeoGenesisTerrain.java      # 地形引擎门面（chunk Cell 真 LRU 缓存 + 装配侵蚀/水文）
│   │   ├── CellGenerator.java          # 逐 cell 地形计算 + 侵蚀 tile（terrainEQuick 廉价采样）
│   │   ├── TypeLandShape.java          # 地形类型形态（Voronoi + 高斯权重）
│   │   ├── TypeNoiseProvider.java      # 每类型噪声配方
│   │   ├── HeightCurve.java            # 高度映射曲线（cubic Hermite）
│   │   ├── CacheStats.java             # 缓存命中埋点（2026-09-11 新增）
│   │   └── Cell.java / TerrainClass.java / ...
│   ├── erosion/
│   │   ├── ErosionEngine.java          # 液滴水力侵蚀（SimpleHydrology 型，物质守恒）
│   │   └── RidgeValleyErosion.java     # 脊-谷条纹骨架滤镜
│   ├── hydrology/                      # ★ 现行河流实现（2026-08-28 D8 汇流场范式）
│   │   ├── riverline/RiverLineNetwork.java   # D8 河线 + Leopold-Maddock 宽深 + PAVA 水面
│   │   ├── flowaccum/FlowField.java          # D8 流向 + 汇流累积
│   │   └── HydrologyBlockCarver.java          # 邻近段 IDW 雕刻
│   ├── cave/                           # ★ 2026-09-15：洞穴（几何与 MC 适配器分离）
│   │   ├── CaveShape.java              # 洞穴几何（零 MC 依赖）：空腔 / 层调制 / 隧道
│   │   ├── CaveCarver.java             # MC 适配器：只把方块挖成空气
│   │   ├── CaveBiomeSelector.java      # 地下洞穴群系（气候 × 3D 噪声交错）
│   │   └── CaveConfig.java             # 档位 + 旋钮（REALISTIC / VANILLA_LIKE / MINIMAL / OFF / CUSTOM）
│   ├── ore/
│   │   └── OreVeins.java               # ★ 地质矿脉（零 MC 依赖）：成矿带 + 宿主岩/深度带 + 3D 等值面；含总开关
│   └── climate/
│       ├── BiomeClassifier.java        # 零依赖群系分类（Whittaker 群区 × 垂直带谱）
│       ├── WhittakerType.java          # Whittaker 群区
│       ├── ClimateZone.java            # Köppen 气候带（已被 Whittaker 边缘化）
│       └── Latitude.java               # 纬度带
├── client/
│   ├── GeoGenesisConfigScreen.java     # 游戏内配置/预览屏（三页标签）
│   ├── ParamSlider.java                # 通用参数滑块
│   └── preview/
│       ├── ColorMap.java               # 零依赖连续色带
│       ├── GeoPalette.java             # 配色中枢（15 图层注册表，含降水）
│       ├── PreviewDisplay.java         # 游戏内预览控件
│       ├── TerrainPreview.java         # 独立 Swing 预览窗口
│       ├── GeoGenesisColorReloadListener.java  # 资源重载监听器
│       ├── CavePanel.java              # 洞穴页签（档位一键切换 + 开关 + 旋钮）
│       ├── TerrainConfigPanel.java     # 地形配置面板
│       ├── ParameterConfigPanel.java   # 参数配置面板（⚠ 旧名 BasicParamsPanel 已不存在）
│       ├── ClimateConfigPanel.java     # 气候配置面板
│       └── mixer/                      # 调音台组件（⚠ 2026-09-18 按实际目录重写）
│           ├── MixerPanel.java         # 调音台面板 UI
│           ├── ControlPoint.java       # 可拖拽控制点
│           ├── ClickableRegion.java    # 可点击区域
│           ├── CategoryBar.java        # 分类色条
│           ├── DualRangeChart.java     # 图表组件（DualRange）
│           ├── SingleCurveChart.java   # 图表组件（SingleCurve）
│           ├── WorldHeightBar.java     # 世界高度柱状图
│           ├── SnowLineChart.java      # 雪线双曲线
│           └── ScalePreview.java       # 尺度预览
```

> ⚠️ 2026-09-18 校正：上表原列的 `PreviewColor.java` / `BasicParamsPanel.java` /
> `mixer/Factor.java` / `FactorCurveChart.java` / `FactorMixer.java` /
> `ConfigBinding.java` / `FactorCategoryBar.java` **七个文件全部不存在**（历史名）；
> `WorldHeightBar` / `SnowLineChart` / `ScalePreview` 实际位于 `mixer/` 下。

### 文档（`docs/`）

> ⚠️ **2026-09-18 重要更正**：`docs/` **真实存在且内容完整**（`01-架构设计/` `02-功能设计/`
> `03-实施计划/` `04-修复记录/` `05-分析诊断/` `06-历史归档/` + `analysis/` `design/`
> `plans/` + `INDEX.md`）。它只是被 `.gitignore:52` 忽略 ⇒ **本地可见、不进版本库**。
> 本文前一版曾误写"不存在"，原因是**用受 .gitignore 过滤的搜索工具做存在性判断** —— 见 `docs/INDEX.md`。
> **★ 改动世界生成前必读**：`docs/analysis/守门门禁清单-2026-09-16.md`（115 个探针的
> 「改什么 ⇒ 必跑什么」矩阵 + 实测基线 + 使用纪律）与 `docs/analysis/原版复用对照审计-2026-09-16.md`。

| 目录 | 内容 |
|------|------|
| `docs/01-架构设计/` … `docs/06-历史归档/` | 编号分类（`ARCHITECTURE.md` 采用此方案） |
| `docs/analysis/` | 分析与审计（**改动前必读**） |
| `docs/design/` `docs/plans/` | 设计 / 计划 |
| `docs/INDEX.md` | 文档分类索引（**含每篇时效性标注**，查新旧以此为准） |

### 其他目录

> ⚠️ **2026-09-18 更正**：下表状态经 `git status --ignored` 权威核实。**原表基本正确**，
> 此前我误判为"全部不存在"，同样是**被 .gitignore 过滤的搜索工具所误导**。

| 目录 | 作用 | 状态 |
|------|------|------|
| `参考/` | 外部参考资料（**9 个项目**：FreeTerraForged-1.21.1 / TerraForged-0.3.x / RTG-Community / SimpleHydrology / novoatlas-ref / MOBIDICpy / geotransport / worldgen…） | ✅ 存在（被忽略） |
| `logs/` | 杂项日志 | ✅ 存在（被忽略） |
| `river_check/` | 河流检查小工具 | ✅ 存在（被忽略） |
| `sca_smoke/` | SCA 编译产物 | ✅ 存在（被忽略） |
| `backups/` | 历史备份 | ❌ 不存在 |
| `net/minecraftforge/` | 疑似误放的依赖源码 | ❌ 不存在 |
| `erosion-test-tool*/` | 旧侵蚀测试工具（已废弃） | ❌ 不存在 |

## 快速开始

1. **接手项目**：先读 **`HANDOFF.md`**（当前状态 / 残余边界 / 必跑的尺子 / 下一步候选）
2. **理解架构**：阅读 `ARCHITECTURE.md`
3. **查看最新开发**：阅读 `AGENTS.md` 的「当前工作焦点」
4. **改世界生成前先查门禁矩阵**：`docs/analysis/守门门禁清单-2026-09-16.md` 的 A 节
5. **文档索引**：`docs/INDEX.md`（含每篇时效性标注）

## 开发环境

- **JDK**：Java 21（Gradle 需要）
- **Gradle**：wrapper 已配置（`gradlew.bat`）
- **IDE**：CodeBuddy（自动扫描 `AGENTS.md`）

## 注意事项

- `AGENTS.md` 记录项目速览与逐日开发焦点，**需随代码变更手动更新**（并非 IDE 自动生成，勿轻信"勿编辑"的旧说明）
- 核心文档（`AGENTS.md`/`ARCHITECTURE.md`/`HANDOFF.md`）保留在根目录
- ⚠️ **文档里抄数值 = 必然漂移**（2026-09-18 教训：`AGENTS.md` 长期写 `CACHE_SCHEMA_VERSION` 当前 69，而代码已到 72；又写"域扭曲已启用 40"，实际回退为 0）。凡会变的数字一律写"以代码常量 X 为准"并指路，不要复述
- ⚠️ **带判据的探针必须以 `System.exit(非零)` 承载 FAIL**，否则 Gradle 报 `BUILD SUCCESSFUL`、门禁名存实亡（128 个探针里只有 18 个做对了）
- 设计/计划/修复文档已分类到 `docs/` 子目录（⚠ `docs/` 被 `.gitignore` 忽略 ⇒ 本地可见、不进版本库）
- ⚠️ **不要用受 `.gitignore` 过滤的搜索工具判断"文件是否存在"**（2026-09-18 踩坑：据此误判 `docs/`、`参考/`、`logs/` 等为"不存在"，并改错了本文件）。**权威做法是 `git status --ignored` 或显式路径列目录**
