# GeoGenesis

**Minecraft Forge 1.20.1 的程序化地形模组。** 用自定义 `ChunkGenerator` + `BiomeSource` 替换主世界地形生成：先算地貌与地质骨架，再在其上跑侵蚀与水文，最后由气候分类决定群系。

| | |
|---|---|
| **游戏 / 加载器** | Minecraft `1.20.1` · Forge `47.x`（编译基线 `47.4.10`） |
| **版本** | `0.0.2` — 早期预览，**世界生成与配置格式仍会变，旧存档不保证兼容** |
| **前置** | 无（只依赖 Forge） |
| **许可** | All Rights Reserved，另附第三方声明见 [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md) |

---

## 特性

**地形**
- 大陆/海洋尺度的连续场地形：类型场（Voronoi + 高斯加权）+ 每类型噪声配方 + Hermite 高度曲线
- 地层与岩性（`StratumField`）、构造骨架与形变（`TectonicField`，褶皱/断层）
- 液滴水力侵蚀（`ErosionEngine`，物质守恒，唯一生产路径）

**水文**
- D8 汇流场派生河网、瀑布/峡谷横截面、洼地湖；确定性骨架，支持无限世界
- 详细规格与铁律见 [HYDROLOGY.md](HYDROLOGY.md)

**洞穴**
- 洞穴几何与 MC 适配器分离（`CaveShape` / `CaveCarver`）：空腔 + 层调制 + 隧道
- 地下洞穴群系（气候 × 3D 噪声），档位可切：`REALISTIC` / `VANILLA_LIKE` / `MINIMAL` / `OFF` / `CUSTOM`

**矿脉**
- 按岩性成矿：成矿带门控 + 宿主岩/深度带 + 3D 等值面；可整体关闭

**群系**
- 零 MC 依赖的气候分类（Whittaker 群区 × 垂直带谱），由气候而非坐标决定群系
- **植被、洞穴装饰、地牢、原版结构、紫水晶洞、化石、顶层雪冰一律复用原版**，本模组只自研原版确实没有的部分（边界见 [原版复用对照审计](audit/原版复用对照审计-2026-09-16.md)）

**游戏内预览与配置**
- 配置屏内嵌地形预览，两种表现：**平面**（地图像素级）与**沙盘**（体素柱 + 光照），带运镜切换
- 十余种图层（高程 / 群系 / 岩性 / 气候 / 降水 …）、图例、悬停读数、出生点标记、种子收藏与预设

---

## 安装

1. 安装 **Minecraft 1.20.1 + Forge 47.x**
2. 把构建出的 jar 放进 `.minecraft/mods/`
3. 创建世界时把**世界类型**选为 **GeoGenesis**（本模组自带 `world_preset`，下界与末地仍用原版）

> 已知粗糙处：世界类型条目目前**没有翻译键**，中文界面下会显示成原始 id（`geogenesis:geogenesis`）；功能正常。

---

## 使用

### 打开配置 / 预览

| 入口 | 位置 |
|---|---|
| 创建世界界面 | 世界类型选 GeoGenesis 后，点原版「**自定义**」按钮 |
| Mods 列表 | `Mods` → 选中 GeoGenesis → `Config` |
| 游戏中 | 无任何界面时按 **`G`** |

### 预览视图操作

| 操作 | 平面 | 沙盘 |
|---|---|---|
| 左键拖动 | 平移 | 平移 |
| 中键拖动 | — | 旋转视角 |
| 滚轮 | 缩放（档位） | 缩放（档位） |
| `Ctrl` + 滚轮 | — | 柱体粗细 |
| `R` | — | 重置视角 |
| 右键 | 复制世界坐标 | 设为出生点标记 |

面板左侧提供**视图切换**（平面 ⇄ 沙盘运镜）、**全局查看**（低精度大范围）、**图层**、**保存**、**重置**、**完成**。具体以界面内提示卡为准。

### 配置文件

`config/geogenesis-common.toml`（Forge common 配置）。键表见 [ARCHITECTURE.md](ARCHITECTURE.md)；只被客户端读取或无人读取的假键由 `runConfigKeyGuardProbe` 门禁拦截。

---

## 从源码构建

需要 **JDK 21**（Gradle 用；模组本身面向 MC 1.20.1）。

```bash
gradlew.bat build                 # 编译 + 打包 → build/libs/
gradlew.bat runClient             # 启动客户端
gradlew.bat runServer             # 启动服务器
gradlew.bat runData               # 数据生成 → src/generated/resources/
gradlew.bat runPreview --args=12345   # 独立预览窗口（纯 Java，不启动 MC）
```

`gradlew.bat` 为 Windows；Unix 用同目录 `gradlew`。

---

## 开发与验证

没有单元测试，验证靠**探针 + 门禁 + 实机**：

```bash
gradlew.bat runWorldgenGate       # 世界生成门禁：纳入的探针任一 FAIL 即构建失败
```

- **改世界生成前必读** [守门门禁清单](audit/守门门禁清单-2026-09-16.md)（「改什么 ⇒ 必跑什么」矩阵）
- **实机性能诊断**：`config/geogenesis-common.toml` 里开 `worldgenProfilerEnabled`，看 `logs/latest.log` 的 `[WGP]` 行或运行目录下 `geogenesis-wgp.txt`（默认关闭、零开销、产出逐位不变）
- **项目规则与硬约束**（含地质系统 6 条禁令、开关一致性铁律、探针铁律）见 [AGENTS.md](AGENTS.md)

---

## 状态与已知限制

- **非稳定版**：世界生成算法、配置键、存档格式都可能变更；旧存档不保证兼容
- **湖泊群系映射未做**
- **水面插值残差**：多段线节点间约 1.8 格（**非断裂**）
- **新版地形候选链是实验分支**，尚未替换游戏内生产地形 —— 对接前请读 [新版地形对接入口](audit/新版地形对接入口.md)，不要把当前生产入口误认为已定稿
- 部分探针/诊断门禁存在**已知红项**，理由记录在对应文档内，不视为回归

---

## 文档

| 文档 | 内容 |
|---|---|
| [ARCHITECTURE.md](ARCHITECTURE.md) | 核心架构、配置表、注册流程 |
| [HYDROLOGY.md](HYDROLOGY.md) | ★ 水文唯一事实来源：用户口径 · 生产链 · 铁律 · 正典规格 |
| [AGENTS.md](AGENTS.md) | 项目规则、架构速览、硬约束、调试与探针铁律 |
| [audit/](audit/README.md) | ★ 受版本保护的审计与需求台账（改动世界生成前必读） |
| [handoff/](handoff/README.md) | 交接与专项记录（历史快照，逐份状态见其 README） |
| [CHANGELOG.md](forge-1.20.1-47.4.10-mdk/CHANGELOG.md) | 版本发布记录 |

> 早期散落的历史注记已归档到 [_archive/readme-2026-notes-20261111.md](_archive/readme-2026-notes-20261111.md)，不再维护。

---

## 许可与第三方

项目总许可为 **All Rights Reserved**；这不取代第三方代码各自的许可条件，也不会把所用 MIT 代码"ARR 化"。

- **河流系统为原创实现**：高位布源、沿 D8 下坡追踪汇入形成树状水系，随机源为坐标派生确定性哈希（满足跨 block 无缝）。
- **预览面板的沙盘视图 / 视图切换 / 落成动画**派生自 `yardsite`（**MIT**，`Copyright (c) 2026 CYun_CYer`）—— 源码分发已由 [THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md) 满足，**jar 内尚未内嵌**。
- **新版地形诊断候选**含标注了"参考复刻 / 原值照录"的内容（FreeTerraForged 1.21.1、TerraForged 0.3.x，均 MIT）。

已知来源、逐文件派生关系与**仍未核实项**见两份核查：
[新版地形第三方许可核查](audit/新版地形第三方许可核查.md) · [沙盘与预览参考项目许可核查](audit/沙盘与预览参考项目许可核查-20261111.md)。