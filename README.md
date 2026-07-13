# GeoGenesis Mod

> Minecraft Forge 1.20.1 模组，自定义 `ChunkGenerator` + `BiomeSource`，程序化生成地形，并按气候驱动生物群系。

## 项目状态

- **当前版本**：地质过程范式地形 + 水滴侵蚀河流系统
- **编译状态**：`BUILD SUCCESSFUL`（2026-07-11）
- **待验证**：实机目检河谷蜿蜒度、海岸过渡、湖泊连续性

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
| `AGENTS.md` | IDE 自动扫描，项目速览（**勿手动编辑**） |
| `ARCHITECTURE.md` | 核心架构设计，配置表，注册流程 |
| `DEV_REPORT.md` | 开发报告，版本记录，修复历史 |
| `HANDOFF.md` | 项目交接，上下文快照 |

### 源代码（`forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/`）

```
├── GeoGenesisMod.java                  # @Mod 入口，注册 CODEC
├── config/
│   └── GeoGenesisConfig.java           # Forge 配置（COMMON/DCLIENT）
├── worldgen/
│   ├── generator/
│   │   ├── GeoGenesisGenerator.java    # 主生成器，fillFromNoise
│   │   ├── GeoGenesisTerrain.java      # 地形引擎（TileCache + RiverField）
│   │   └── BiomeMapper.java            # 分类器 → 原版群系映射
│   ├── terrain/
│   │   ├── CellGenerator.java          # 逐 cell 地形计算（StructuralField + TerrainBlender）
│   │   ├── StructuralField.java        # 地质背景场（省权重）
│   │   ├── TerrainBlender.java         # 过程形态合成
│   │   ├── HeightCurve.java            # 高度映射曲线
│   │   ├── HeightProvider.java         # 高度提供接口
│   │   ├── RiverField.java             # 河流系统（HydraulicErosion + priority-flood 湖泊）
│   │   ├── HydraulicErosion.java       # 水滴侵蚀引擎
│   │   ├── TileLakeSolver.java         # 湖泊求解器
│   │   └── Cell.java / RiverSample.java / RiverRegion.java / TileCache.java
│   └── climate/
│       ├── BiomeClassifier.java        # 零依赖群系分类
│       ├── ClimateZone.java            # Köppen 气候带
│       └── Latitude.java               # 纬度带
├── client/
│   ├── GeoGenesisConfigScreen.java     # 游戏内配置/预览屏（三页标签）
│   ├── ParamSlider.java                # 通用参数滑块
│   └── preview/
│       ├── ColorMap.java               # 零依赖连续色带
│       ├── GeoPalette.java             # 配色中枢（11 图层注册表）
│       ├── PreviewColor.java           # MC 侧着色（ABGR）
│       ├── PreviewDisplay.java         # 游戏内预览控件
│       ├── TerrainPreview.java         # 独立 Swing 预览窗口
│       ├── GeoGenesisColorReloadListener.java  # 资源重载监听器
│       ├── TerrainConfigPanel.java     # 地形配置面板
│       ├── BasicParamsPanel.java       # 基础参数面板
│       ├── WorldHeightBar.java         # 世界高度柱状图
│       ├── SnowLineChart.java          # 雪线双曲线
│       ├── ScalePreview.java           # 尺度预览
│       └── mixer/                      # 调音台组件
│           ├── Factor.java             # 因素数据模型
│           ├── FactorCurveChart.java   # 曲线可视化
│           ├── FactorMixer.java        # 多因素协调管理器
│           ├── MixerPanel.java         # 调音台面板 UI
│           ├── ControlPoint.java       # 可拖拽控制点
│           ├── ConfigBinding.java      # 控制点→配置绑定
│           └── FactorCategoryBar.java  # 条件因素分类色条
```

### 文档（`docs/`）

| 目录 | 内容 |
|------|------|
| `docs/design/` | UI/交互/算法设计文档（8个） |
| `docs/plans/` | 实施计划 |
| `docs/fixes/` | 修复记录 |
| `docs/archived/` | 已归档的历史文档（整体过时，仅作过程留痕） |
| `docs/INDEX.md` | 文档分类索引（**含每篇时效性标注 ✅有效/⚠️部分过时/❌已过时**，查新旧以此为准） |

> 文档时效以 `docs/INDEX.md` 为权威来源；`archived/` 下历史子目录（chat/design/fix/notes/optimization/research_notes/status）为更早归档，整体过时，未逐篇标注。

### 其他目录

| 目录 | 作用 | 状态 |
|------|------|------|
| `参考/` | 外部参考资料（地形学、侵蚀算法等） | 保留 |
| `backups/` | 历史备份（zip、工具归档） | 保留 |
| `logs/` | 杂项日志（symcheck、preview_run.err） | 保留 |
| `net/minecraftforge/` | 疑似误放的依赖源码 | 待确认 |
| `erosion-test-tool*/` | 旧侵蚀测试工具（已废弃） | 保留 |
| `sca_smoke/` | SCA 编译产物 | 可归档 |
| `river_check/` | 河流检查小工具 | 保留 |

## 快速开始

1. **理解架构**：阅读 `ARCHITECTURE.md`
2. **查看最新开发**：阅读 `DEV_REPORT.md`
3. **设计变更**：查看 `docs/design/`
4. **实施计划**：查看 `docs/plans/PLAN.md`
5. **文档索引**：查看 `docs/INDEX.md`

## 开发环境

- **JDK**：Java 21（Gradle 需要）
- **Gradle**：wrapper 已配置（`gradlew.bat`）
- **IDE**：CodeBuddy（自动扫描 `AGENTS.md`）

## 注意事项

- `AGENTS.md` 由 IDE 自动维护，勿手动编辑
- 核心文档（AGENTS/ARCHITECTURE/DEV_REPORT/HANDOFF）保留在根目录
- 设计/计划/修复文档已分类到 `docs/` 子目录
- 侵蚀测试工具已废弃但保留（用户确认）
