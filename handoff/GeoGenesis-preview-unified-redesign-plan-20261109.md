---
title: 预览/配置屏统一重构 —— 实施方案（待确认）
status: plan
created: 2026-11-09
---

# 预览与配置屏统一重构 —— 实施方案

> 本文是**方案**，不是记录。用户确认后才动代码。
> 参考项目（均已在本地完整读过）：
> - `参考/sources/FreeTerraForged-1.21.1`（下称 **RTF**）—— 双视图统一架构的范本
> - `参考/sources/yardsite`（下称 **YS**）—— 3D 斜视角方柱的渲染范本

---

## 〇、先说我理解的现状与问题（请重点核对这一节）

### 0.1 现在的代码是什么样

| 文件 | 行数 | 实际职责 |
|---|---:|---|
| `client/preview/PreviewDisplay.java` | **2254** | 一个 Widget 里塞了：精确平面管线 + 大范围采样 + 沙盘模式 + 图例 + 悬停 + 拖拽 + 缓存 + 结构叠加 |
| `client/preview/SandboxRenderer.java` | 955 | 沙盘 3D 方柱（照 YS 移植） |
| `client/preview/SandboxLod.java` | 211 | 沙盘尺度（1 柱 = 16 格） |
| `client/preview/TerrainFieldData.java` | 458 | 采样容器 |
| `client/preview/ProgressiveSampler.java` | 282 | 后台采样（**已不在沙盘路径上**） |
| `client/GeoGenesisConfigScreen.java` | 1003 | 全屏布局 + 左侧悬浮窗 + 12 页签 |
| 其余 preview/*.java | ~3500 | 12 个配置页签面板 |

### 0.2 为什么你说「两个视图各自完全独立分裂」

我核对后，**你说得对**，分裂是结构性的：

1. **数据源分裂**：平面模式走 `TerrainQueue` + `CellCache`（按 chunk 采）；沙盘模式走 `TerrainFieldData.fromCellCache`（另建扁平数组）。两份数据结构、两套采样时机、两套坐标系。
2. **相机/缩放分裂**：平面有 `scaleBlockPos`/`renderScale`；沙盘有 `zoom`/`SandboxLod`。互不同步，切换时构图跳变。
3. **图例/悬停分裂**：平面有 `drawLegend`（可搜索、可排序、可折叠）；沙盘有 `drawSandboxHints`。两套。
4. **重绘触发分裂**：平面靠 `cellCache.version()` 脏检查；沙盘靠 `sandboxDataId`。
5. **模式切换是 `if` 分支**：`renderWidget` 里 `if (sandboxMode) { renderSandbox(); ... }`，两条路各走各的，共享的只有「同一个 Widget 的矩形」。

⇒ 这不是「两个视图」，是**两套平行实现被塞进一个类**。

### 0.3 RTF 给出的正解（我已读完并核实）

RTF 把 2D/3D 做成**同一个接口的两个实现**，共享部分写在接口 default 方法里：

```
IPreviewHandler（接口，650 行）
├── 共享（default 实现）：
│     regenerate() / scheduleRegeneration() / executeRegenerate()
│     requestRasterization() / refreshRenderMode() / close()
│     renderLegend() / handleClick() / handleScroll() / getSpawnCoords()
│     ── 三阶段异步流水线、75ms 防抖、取消、失败显示、LRU 缓存 ──
└── 不共享（只有 4 处真正不同）：
     ① createRasterData()      2D=俯视位图 / 3D=等距投影位图
     ② captureRasterParams()   2D=固定 256² / 3D=跟随 widget 尺寸
     ③ getZoom()              公式相同，输入不同
     ④ buildLegendLine/legendLineX  文字布局
```

**关键结论：2D 和 3D 的差异只有「一格怎么变成像素」。** 其余全部共享。

YS 用另一条路达到同样目的：`SiteField` 接口（106 行），`SiteTerrainRenderer` 只认接口，
大沙盘（`SiteMapSampler.View`）与区块精读（`SiteReliefField`）共用同一套柱子/投影/排序/渐入。

**两个参考项目指向同一个设计原则：把「数据场」和「渲染器」解耦，多个视图共用。**
我们现在缺的正是这一层。

---

## 一、目标形态

```
GeoGenesisConfigScreen（全屏）
├── 地形区：占满 (0,0,w,h)，无上下栏、无右侧 HUD
│   由「一个」PreviewWidget 承担，内部按 TerrainViewMode 分派：
│
│   TerrainViewMode.PLANE      → 俯视正射（现有精确管线）
│   TerrainViewMode.SANDBOX    → YS 斜视角方柱
│
│   两者共用同一份 TerrainSnapshot（见 §二）
│   切换模式 = 只换 rasterizer，不重采、不重建数据
│
└── 左侧悬浮配置窗（现有，可折叠，12 页签）
    ├── 视图区（新增/合并）：视图模式、图层、缩放、种子
    └── 底部：应用/保存/重置
```

**「两个视图」不是两个 Widget，是同一个 Widget 的两个 rasterizer。**
这是我要改的核心。

---

## 二、数据层：统一为一份 TerrainSnapshot

### 2.0 ★ 纯 Java 核心边界（用户 2026-11-09 明确的目的）

> 用户原话：「这就是我让你抽离 MC 做完全纯 Java 核心的原因，可以在外部计算后直接复用。」

**这是整个架构存在的理由，不是附带优点。** 已有的边界（实测）：

| 类 | MC import 数 | 说明 |
|---|---:|---|
| `worldgen/terrain/GeoGenesisTerrain.java` | **0** | 采样门面，**已纯** |
| `worldgen/terrain/CellGenerator.java` | **0** | 地形产线，**已纯** |
| `worldgen/terrain/Cell.java` | **0** | 数据单元，**已纯** |
| `worldgen/terrain/TerrainParams.java` | **0** | 参数，**已纯** |
| `worldgen/climate/BiomeClassifier.java` | 5 | 只依赖 `ResourceKey<Biome>` / `Biomes` 常量（**命名**而非运行时），且有 `approxBiomeClass` 降级 |

⇒ **核心地形计算已经能在无 MC 环境下跑**（探针进程就是证据：`runSandboxCellCacheProbe` 里 `new CellGenerator(...)` 没有 Bootstrap）。

**本次重构必须保持并强化这条边界**：

```
纯 Java 核心（零 MC）                       MC 适配层（薄）
├─ CellGenerator / Cell / TerrainParams    ├─ GeoGenesisGenerator (ChunkGenerator)
├─ GeoGenesisTerrain（chunk 缓存）          ├─ GeoGenesisBiomeSource
├─ StratumField / TectonicField / 水文      ├─ VanillaDecorationFilter
├─ BiomeClassifier（到 BiomeClass 枚举）    ├─ GeoGenesisConfig（TOML）
├─ TerrainSnapshot（新增，渲染数据）         └─ PreviewController（GUI）
├─ SandboxGeometry（新增，纯函数）
└─ SandboxShade / GeoPalette 色表
        ↓
   可被：预览 / 探针 / 离线出图 / 进世界加载  四处共用同一份实现
```

**禁用清单（新增代码必须遵守）**：
- `TerrainSnapshot` / `SandboxGeometry` / `TerrainRasterizer` 接口 —— **零 `net.minecraft.*` import**
- 任何「计算」不允许读 `Minecraft.getInstance()`
- 渲染（`SandboxRasterizer` / `PlaneRasterizer`）才允许碰 MC

**验收**：`SandboxGeometry` 与 `TerrainSnapshot` 的 MC import 数必须为 **0**（探针检查）。

### 2.1 现状问题

| | 平面模式 | 沙盘模式 |
|---|---|---|
| 数据容器 | `CellCache`（chunk → Cell[256]） | `TerrainFieldData`（扁平 double[]/short[]） |
| 采样单元 | 1 Cell = 1 方块 | 1 细格 = 8 方块（窗口/细格数） |
| 采集时机 | `TerrainQueue` 后台持续采 | `fromCellCache` 按视口一次性构建 |
| 精度 | 1:1 ~ 1:16 | 固定（柱宽 16 格） |

### 2.2 目标：`TerrainSnapshot`

新建 `client/preview/TerrainSnapshot.java`（约 150 行）：

```java
/**
 * 一份「可被任意视图光栅化」的地形快照。RTF 的 Tile 对应物。
 * 不持有采样逻辑，只持有数据 + 元信息。
 */
public final class TerrainSnapshot implements AutoCloseable {
    public final int originX, originZ;      // 世界坐标左上角
    public final int blocksWide, blocksHigh; // 覆盖方块数
    public final int gridW, gridH;           // 细格数
    public final double blocksPerCell;       // = blocksWide / gridW

    // 每细格的原始数据（从 CellCache 读，1 Cell = 1 方块，再按 blocksPerCell 抽取）
    public final double[] height;      // 真实世界 Y
    public final float[] e;            // 地形系数
    public final short[] biomeClassId;
    public final short[] terrainId;
    public final float[] temperature, humidity, precipitation;
    public final boolean[] snow;
    public final float[] waterY;       // NaN = 无水

    public final long revision;        // 缓存键用

    public static TerrainSnapshot fromCache(CellCache cache, int ox, int oz,
                                            int blocksWide, int gridW, long revision);
}
```

**与现有 `TerrainFieldData` 的关系**：
- 短期：`TerrainSnapshot` 可以直接包装 `TerrainFieldData`（字段照搬），先不删旧的。
- 长期：`TerrainFieldData` 的 `put/copyCellFrom` 等采样方法移进 `TerrainSnapshot` 的工厂方法，
  旧类删除。**本轮只做包装，不做删除**（降低风险）。

### 2.3 谁生产它

`PreviewWidget` 内部一个 `SnapshotProvider`：

```
视口变化 / 图层不需要重采 / 缓存版本变化
        ↓
决定 (originX, originZ, blocksWide, gridW)
        ↓
TerrainSnapshot.fromCache(cellCache, ...)   ← 直读平面视图的缓存，零二次采样
        ↓
缓存（只有 key 变了才重建；1 份就够，不需要 RTF 的 6 份 LRU，因为我们是单视口）
```

**缓存键**：`(originX, originZ, blocksWide, gridW, cellCache.version())`。
用 `long` 混合，别用对象。

---

## 三、渲染层：两个 rasterizer 同接口

### 3.1 `TerrainRasterizer` 接口

新建 `client/preview/raster/TerrainRasterizer.java`（约 40 行）：

```java
public interface TerrainRasterizer {
    /** 视图模式标识。 */
    TerrainViewMode mode();

    /** 这份快照需要多少细格（决定 SnapshotProvider 怎么建）。 */
    int requiredGrid(TerrainSnapshot snapshot, int viewportPx);

    /** 光栅化成 ARGB 像素（2D 画布 or 3D 画布）。 */
    int[] rasterize(TerrainSnapshot snapshot, RasterParams params);

    /** 悬停：屏幕坐标 → 世界坐标 + 该格的描述行。 */
    HoverInfo hover(TerrainSnapshot snapshot, RasterParams params, int mx, int my);

    /** 图例要显示什么（视图相关的第一行，如「视野 2048 格 / 128 柱」）。 */
    String legendLine0(TerrainSnapshot snapshot, RasterParams params);
}
```

`RasterParams`：`(int width, int height, float zoom, GeoPalette.PreviewLayer layer, int seaLevel)`。

### 3.2 两个实现

| 实现 | 来源 | 代码量估计 |
|---|---|---|
| `PlaneRasterizer` | 抽取现有 `PreviewDisplay` 的平面绘制 | 现有平面逻辑约 600 行 → 精简到 ~350 |
| `SandboxRasterizer` | 现有 `SandboxRenderer`（955 行）改造 | 保留投影/排序/光照，去掉自采数据 → ~600 |

**关键改动在 `SandboxRasterizer`**：
- 不再自己算 `collect()` 的采样尺寸，改为从 `TerrainSnapshot` 读。
- 保留：`buildRotation` / `fitScaleWide` / `collect` / `sortByDepth` / `wall` / `vertex` / `quadFlat` / `hoverOverlay`。
- 删除：`sandboxData` 缓存（移到 Widget 层）/ 自建 `TerrainFieldData`。
- 坡度光照**必须**用真实方块 Y + 分母 `2 × blocksPerCell`（已修好，保留）。

### 3.3 ★ 定稿：OpenGL 逐柱 + 几何纯函数层（不采用全软件光栅化）

#### 为什么不能全软件光栅化

算过规模：我们拉远档上限 **512 柱 × 512 柱 = 262,144 柱**，
RTF 预览是 256×256 = 65,536 柱 —— **我们是它的 4 倍**。
每柱 5 个四边形 × 几十像素 ⇒ 几千万次像素写入，Java 里 100ms+，拉远档不可行。

⇒ **保留 YS 的逐柱 OpenGL**。

#### 但 OpenGL 的真正代价是「无法离线验证」

RTF 的价值不在光栅化方式，而在**可验证性**：它的 3D 就是纯 Java 算 `int[]`，
探针能直接调 `createRasterData()` 出图，与实机像素**逐位一致**。

我们对这条路的教训已经很清楚：离线 Python 脚本复刻 Java 渲染逻辑，
一旦两边不同步就误判 —— 实测 `per_column_for()` 恒返回 1，
我在**错图**上判断了好几轮（"整片是平的""柱子太细"全是假象）。

#### 解法：把「算」和「画」彻底分开

照搬项目里已有的 `CaveShape`（零 MC 依赖纯函数）+ `CaveCarver`（MC 适配器）分层：

```
client/preview/sandbox/SandboxGeometry.java   纯函数，零 MC 依赖
    Column[] buildColumns(TerrainSnapshot, RasterParams)   // 柱高/四角/颜色/alpha
    int[]     sortByDepth(Column[], float yaw, float pitch) // 远→近
    float     fitScale(float yaw, float pitch, int w, int h) // 投影包围盒
    int       pick(Column[], ..., int mx, int my)            // hover 拾取
    float     edgeFade / slopeLight（已有 SandboxShade，继续复用）
              ↓ 同一份输出，两条消费者
    ┌──────────────────────┴──────────────────────┐
SandboxProbe（探针）                     SandboxRasterizer（实机）
  CPU 填充几十行 → PNG                     bufferSource + 提交 vertex
  验证柱数/柱高/排序/光照/包围盒            只做「画」，不做「算」
```

**收益**：
- 所有可能出错的数学（投影、排序、光照、包围盒、拾取）都能离线验证；
- 离线图与实机图**用同一份 `Column[]`**，不可能失同步；
- 实机只负责「把已知四边形画出来」——这部分已六次实测证明不出错；
- 没有第二套渲染实现，不产生新分裂。

---

## 四、控制器：`PreviewController`（取代 2254 行的 God Widget）

新建 `client/preview/PreviewController.java`（约 500 行），承担 RTF `IPreviewHandler` + `PreviewState` 的职责：

```java
public final class PreviewController implements AutoCloseable {
    private final PreviewState state;                 // 见 §4.1
    private TerrainViewMode viewMode = PLANE;
    private TerrainRasterizer rasterizer;             // 按 viewMode 切换
    private TerrainSnapshot snapshot;                 // 唯一数据
    private long snapshotKey = Long.MIN_VALUE;

    // ── 对外（GeoGenesisConfigScreen 只调这些）──
    public void render(GuiGraphics g, int x, int y, int w, int h, int mx, int my);
    public boolean mouseClicked(double mx, double my, int button);
    public boolean mouseDragged(...); public boolean mouseReleased(...);
    public boolean mouseScrolled(...);
    public void setViewMode(TerrainViewMode m);       // 只换 rasterizer，不重采
    public void setLayer(GeoPalette.PreviewLayer l);
    public void invalidate();                         // 参数改了 → 下次 render 重建
    public void close();
}
```

### 4.1 `PreviewState`（新建，约 60 行）

照 RTF：

```java
final class PreviewState {
    TerrainSnapshot snapshot;
    long snapshotKey;
    int centerX, centerZ;          // 视图中心（世界坐标），两个模式共用
    float zoom = 1.0f;             // 两个模式共用（各自解释，但值同步）
    GeoPalette.PreviewLayer layer;
    TerrainViewMode viewMode;
    // 图例 4 行（RTF 同构）
    final String[] legendLabels = {"视野", "地形", "图层值", "中心"};
    final String[] legendValues = {"", "", "", ""};
    int hoverX, hoverZ;
    String hoverLine = "";
    boolean dirty = true;
    boolean closed;
    // 待提交的光栅结果（异步）
    int[] pendingPixels; int pendingW, pendingH; boolean pendingReady;
    long rasterVersion;
}
```

### 4.2 与 RTF 的差异（不照抄的部分）

| RTF 的做法 | 我们的做法 | 原因 |
|---|---|---|
| 服务端生成 `Tile`（重跑 worldgen） | **直读 `CellCache`** | 我们已有平面视图的方块级缓存，不需要重生成 |
| 6 份 tile LRU | **1 份快照** | 单视口，不需要多档预取 |
| 75ms 防抖重生成 | **75ms 防抖重建快照** | 保留（滑块拖动时有效） |
| 异步三阶段 | **两阶段**（构建快照 / 光栅化） | 快照构建已在 `CellCache` 上有缓存 |
| `BiomePreview` sidecar | **不需要** | 我们的 `Cell` 已带 `biomeClassId` |

---

## 五、要改的文件清单（逐条）

### 5.1 新增（7 个）

| 文件 | 行数估计 | 作用 |
|---|---:|---|
| `client/preview/TerrainSnapshot.java` | 150 | 统一数据容器（包装 `TerrainFieldData` 字段） |
| `client/preview/TerrainViewMode.java` | 20 | 枚举 `PLANE` / `SANDBOX` |
| `client/preview/raster/TerrainRasterizer.java` | 40 | 光栅化接口 + `RasterParams` + `HoverInfo` |
| `client/preview/raster/PlaneRasterizer.java` | 350 | 平面正射（抽取现有逻辑） |
| `client/preview/raster/SandboxRasterizer.java` | 600 | 3D 方柱（改造现有 `SandboxRenderer`） |
| `client/preview/PreviewState.java` | 60 | 共享状态 |
| `client/preview/PreviewController.java` | 500 | 控制器（渲染循环 + 交互 + 缓存） |
| `src/diagnostics/.../PreviewUnifiedProbe.java` | 200 | 验收探针（见 §七） |

### 5.2 改（3 个）

| 文件 | 改什么 |
|---|---|
| `client/preview/PreviewDisplay.java` | **2254 → ≤400 行**。保留精确平面纹理管线（1:1~1:16 的 `TerrainQueue`+`CellCache`+`DynamicTexture`），删掉沙盘分支、图例、悬停、拖拽（全部移交 `PreviewController`）。它退化成一个「提供 `CellCache` 和精确纹理」的数据源。 |
| `client/GeoGenesisConfigScreen.java` | 1003 → 约 700 行。`preview` 字段类型从 `PreviewDisplay` 改为 `PreviewController`；视图模式按钮接到 `setViewMode()`；加「视图」页签（模式/图层/缩放/种子）。 |
| `client/preview/SandboxRenderer.java`（955 行） | **拆分为 `SandboxGeometry`（纯函数）+ `SandboxRasterizer`（只画）**，旧类删除。保留：投影矩阵、`fitScaleWide`、计数排序、四向侧墙亮度、`snapTop` 三处共用、`PRIM_OVERLAP`。 |

### 5.2b 拆分 `SandboxRenderer` 的分界线（关键）

| 归 `SandboxGeometry`（纯函数，可探针调用） | 归 `SandboxRasterizer`（只在实机跑） |
|---|---|
| `buildRotation()` 算 `r[9]` | `g.bufferSource().getBuffer(RenderType.gui())` |
| `fitScaleWide()` / `planeBox()` | `vertex()` 里的 `c.vertex(...).color(...).endVertex()` |
| `collect()`：柱高、边、颜色、alpha | `sortByDepth()` 的**执行**（桶排序） |
| `edgeFade()` / `slopeLightAt()` | `g.flush()` |
| `sortByDepth()` 的**算法**（返回 `int[] sorted`） | `RenderSystem.disableCull/depthMask` |
| `pick()`：屏幕 → 柱坐标 | `hoverOverlay()` 的提交 |
| `snapTop` / `PRIM_OVERLAP` 常量 | — |

⇒ 所有可能算错的东西都在纯函数层，探针能 100% 覆盖。

### 5.3 删（1 个）

| 文件 | 理由 |
|---|---|
| `client/preview/SandboxRenderer.java`（955 行） | 拆分为 `SandboxGeometry`（纯函数）+ `SandboxRasterizer`（只画）后，旧类整体删除。功能零丢失。 |

> ⚠ **`ProgressiveSampler` 不删**（§八 决策 5）。它仍是平面大范围模式的唯一采样器
> （`PreviewDisplay.paintLargeArea:747-777`）。我先判它是死代码，grep 后修正。

### 5.4 明确**不动**的

- 12 个配置页签面板（`ClimateConfigPanel` / `CavePanel` / `OrePanel` / `PresetsPanel` 等）
- `PanelLayout` / `ConfigKeyCatalog` / `DomainPanel`
- `GeoPalette`（颜色中枢）/ `SandboxPalette` / `SandboxShade` / `ColorMap`
- `CellCache` / `TerrainQueue` / `PreviewDiskCache` / `ChunkWorkUnit`
- 地形生成链（`CellGenerator` / `StratumField` / 水文 / 侵蚀）—— **一行不碰**

---

## 六、实施顺序（分批提交，每批可独立验证）

| 批次 | 内容 | 验证 |
|---|---|---|
| **B0** | 新增 `TerrainViewMode` / `TerrainSnapshot` / `TerrainRasterizer` / `PreviewState`，不接线；**`TerrainSnapshot` 零 MC import** | `compileJava` + MC import 检查 |
| **B0.5★** | **预览→世界复用契约**（§九）：`offerPreviewTerrain` / `bindSeedLocked` 接管 / `runPreviewWorldReuseProbe` | 探针 5 条判据 PASS + 指纹 PASS |
| **B1★** | **`SandboxGeometry`（纯函数）+ `SandboxGeometryProbe`（离线出图）**。从 `SandboxRenderer` 抽出所有数学，**先用探针证明投影/排序/光照/包围盒正确** | 探针 7 条判据全 PASS + 出图人工确认 |
| **B2** | `SandboxRasterizer`（只画）+ `PlaneRasterizer`（抽取平面逻辑），都实现 `TerrainRasterizer` | `compileJava` + 平面视觉不变 |
| **B3** | `PreviewController` 接线 + `GeoGenesisConfigScreen` 改造（模式按钮 + 视图页签） | 实机两模式切换不跳变 |
| **B4** | 删 `SandboxRenderer` 旧类，`PreviewDisplay` 瘦身（保留三条平面子路径） | 全门禁 PASS |

**B1 是本轮的关键节点**：几何层一旦过了探针，后面只是「搬运 + 接线」，
所有可能出错的数学已经在离线环境里被证明过了。**B1 出图不通过就不往下走。**

每批结束都跑：`compileJava` + `runDefaultOutputFingerprintProbe` + `runWorldgenGate -x runCenterlineCrossSectionProbe`。

---

## 七、验收标准（可量化，不靠"感觉"）

### 7.1 结构验收（探针 `runPreviewUnifiedProbe`）

| 判据 | 通过条件 |
|---|---|
| 同一份快照能喂给两个 rasterizer | 两个 rasterize 都非空，且**快照对象是同一个引用** |
| 切换模式不重建快照 | `setViewMode` 前后 `snapshotKey` 相等 |
| 切模式后悬停世界坐标一致 | 同一屏幕点 → 同一 `(worldX, worldZ)`（容差 ≤ 1 柱宽） |
| 图例 4 行在两个模式下都非空 | 4/4 |
| `PreviewDisplay` 行数 | ≤ 400 |
| `PreviewController` 行数 | ≤ 600 |
| `SandboxGeometry` 行数 | ≤ 400 |
| `SandboxRasterizer` 行数 | ≤ 300 |
| 任一文件里 `if (sandboxMode)` 分支数 | **0**（模式分派只在 `rasterizer` 字段） |
| **`SandboxGeometry` 的 MC 依赖** | **0**（不 import 任何 `net.minecraft.*`） |

### 7.1b 几何纯函数验收（探针 `runSandboxGeometryProbe`）

**这是本轮最重要的一条**——此前六次渲染翻车全在这里。

| 判据 | 通过条件 |
|---|---|
| 柱数 = 快照细格数 / `HEIGHT_STRIDE²` | 精确相等 |
| 每柱柱高 = 组内 16×16 中位真实高度（经 `ratio*RELIEF`） | 与手算一致 |
| 投影包围盒装得下视口 | `spanX*scale ≤ w` 且 `spanY*scale ≤ h` |
| 排序是全序（远→近） | 相邻深度单调不减 |
| 坡度光照饱和率 | 贴死暗 < 5%、贴死亮 < 5%（当前实测 1.3% / 2.6%） |
| 光照动态范围 | p95 − p5 ≥ 0.30（当前 0.431） |
| `pick()` 往返一致 | `project(col)` → `pick()` 回到原柱，误差 ≤ 1 |
| 离线 PNG 与实机截图 | 人工比对，形状一致（不平、不糊、无扫描线、无网纹） |

### 7.2 视觉验收（离线出图 + 实机）

| 项 | 标准 |
|---|---|
| 平面模式 | 与当前截图**逐像素一致**（`verify-preview-source-parity.py` 扩展） |
| 沙盘模式 | 斜视角方柱、每柱 16 格、方形铺满、有坡度明暗（你已认可的那版） |
| 模式切换 | 中心与缩放保持，只换观感 |
| 拖动 | 两个模式都改世界中心（不是只平移像素） |
| 悬停 | 两个模式都显示世界坐标 + 该格地形名 |

### 7.3 回归（必须全绿）

```
compileJava / compileDiagnosticsJava / compileAcceptanceJava
runDefaultOutputFingerprintProbe -PprobeArgs="--compare build/geogenesis-baseline.tsv"   PASS（地形逐位不变）
runWorldgenGate -x runCenterlineCrossSectionProbe                                        ALL PASS
runSandboxLodProbe                                                                       PASS
runConfigKeyGuardProbe                                                                   PASS
```

---

## 八、已定的 5 个决策（用户授权我判断，以下为结论）

| # | 决策 | 结论 | 依据 |
|---|---|---|---|
| 1 | 沙盘绘制方式 | **YS 逐柱 OpenGL + 几何纯函数层** | 262,144 柱规模下软件光栅化不可行；但数学必须可离线验证 ⇒ 算/画分离（§3.3） |
| 2 | `PreviewDisplay` 定位 | **瘦身为「精确平面数据源 + 纹理」** | 保留 `TerrainQueue`+`CellCache`+`DynamicTexture`（「预览=游戏」唯一保证），移走沙盘/图例/悬停/拖拽 |
| 3 | 14 个图层 | **两模式共用同一份列表** | 用户原话：「温度、湿度、高程、群系等等原来渲染窗口的内容」都要在沙盘里。呈现方式（连续色带/离散本色）是 rasterizer 内部事。`VEIN_MAP` 两边 SKIP（`Cell` 无字段） |
| 4 | 种子/缩放/中心 | **中心共用（硬性）+ 缩放共用一个 `zoomFactor`，各自解释语义** | 中心不共用则切模式会跳到别处；种子本来就是同一个世界 |
| 5 | `ProgressiveSampler` | **保留，不删** | ⚠ 我先以为它已死，**实测 grep 后修正**：`PreviewDisplay.paintLargeArea:747-777` 仍在用 —— 平面**大范围模式**（窗口可达数万格）必须走 `sampleCellCoarse` 廉价路径，它是唯一采样器。沙盘不再用它，但平面还需要 |

### 8.1 缩放的共用方式（补充）

```
state.zoomFactor : double ∈ [0, 1]（RTF 同构：滑块归一化值）
        ↓
PlaneRasterizer:   precision = 1 << round(zoomFactor * 4)              // 1:1 ~ 1:16
SandboxRasterizer: windowBlocks = 2048 * 2^round((0.5 - zoomFactor) * 4) // 512 ~ 8192 格
```

两个 rasterizer 各自把 `zoomFactor` 翻译成自己的语义，但**滑块物理位置一致**
（拖到最右：平面最精细、沙盘最近）。切换模式时档位保持。

### 8.2 平面模式的三条子路径（不能压成一条）

`PreviewDisplay` 瘦身后仍须保留**三条**，这是它的既有能力，不是重复实现：

| 子路径 | 触发 | 采样器 | 「预览=游戏」 |
|---|---|---|---|
| 精确管线 | `scaleBlockPos/renderScale ≤ 16` | `TerrainQueue` → `CellCache`（`sampleCell` 完整链） | ✅ 保证 |
| 大范围模式 | 更远 | `ProgressiveSampler`（`sampleCellCoarse`） | ❌ 明确不保证（看气候格局） |
| 磁盘缓存回填 | 滑回旧视口 | `PreviewDiskCache` → `CellCache` | ✅ 与首次一致 |

沙盘复用**大范围模式**那份 `TerrainSnapshot`（因为沙盘本身就是「看格局」），
精确管线只在平面模式 ≤1:16 时生效。

---

## 九、★ 预览 → 进世界的复用契约（用户 2026-11-09 明确要求）

### 9.1 为什么需要，以及为什么 RTF 给不了答案

用户原话：
> 「这就是我让你抽离 MC 做完全纯 Java 核心的原因，可以在外部计算后直接复用。」
> 「谁没事在已经创建的游戏里面打开预览？肯定是只有在创建新世界时才会打开配置面板啊。」

⇒ **唯一的复用场景是「配置屏（新建世界）算完 → 点创建世界 → 进世界直接接管」。**

**实测 RTF 完全不做这件事**（子代理调研，证据已核）：

| 问题 | RTF 的答案 | 证据 |
|---|---|---|
| 是否复用预览地形 | **否** | `PresetConfigScreen.onDone()` 只写种子；`applyPreset()` 只导出 datapack zip |
| 数据来源是否相同 | 采样函数相同，缓存完全不同 | 预览 `makeUncached`（`cache=null`）；世界 `makeCached`（`TileCache`） |
| 能否共用 | **结构上不可能** | 预览 tile 坐标是缩放域 `blockX*zoom+translateX`，世界是真实 block 坐标 |
| 是否认为重复计算是问题 | 没讨论过 | 唯一措辞 `MixinRandomState:111` 称进世界要 build a **heavy** GeneratorContext —— 明知昂贵仍重建 |

**所以我们比 RTF 有利**：我们的 `GeoGenesisTerrain` 按 **chunk (cx,cz) → Cell[256]** 缓存，
预览与游戏用的是**同一份真实世界坐标** ⇒ 复用不需要搬运数据，只需**实例是同一个**。

### 9.2 现状缺口（已核实代码路径）

```
配置屏（新建世界）
  → previewReusableTerrain(seed, params) 返回 null（WORLD_LIFECYCLE 未绑定）
  → 自建 new GeoGenesisTerrain(gen)        [GeoGenesisConfigScreen:441-445]
  → 采样 N 个 chunk（4096-chunk LRU 填充）
        ↓ 用户点「创建世界」
  → bindWorld(seed, params, morphBase)     [GeoGenesisGenerator:537]
  → bindSeedLocked(seed) → invalidate() + retireTerrain(旧实例)
  → 全新实例，从零重采                        ← 预览算的全丢
```

### 9.3 补法：`offerPreviewTerrain` / 接管

在 `GeoGenesisGenerator` 新增（约 40 行，**不改任何地形产出**）：

```java
/** 预览算好的实例，等待进世界接管。null = 无待接管。 */
private static volatile OfferedTerrain offeredTerrain = null;

record OfferedTerrain(GeoGenesisTerrain terrain, long seed,
                      TerrainParams params, boolean morphBase) {}

/**
 * 配置屏在**关屏 / 点创建世界**时调用，把自建实例交出来。
 * 只登记，不绑定；若进世界时种子/参数不匹配，会被丢弃并走原路径。
 */
public static void offerPreviewTerrain(GeoGenesisTerrain t, long seed,
                                       TerrainParams params, boolean morphBase) {
    offeredTerrain = (t != null && t.isUsable())
            ? new OfferedTerrain(t, seed, params, morphBase) : null;
}

private static GeoGenesisTerrain bindSeedLocked(long seed) {
    OfferedTerrain off = offeredTerrain;
    if (off != null
            && off.seed() == seed
            && Objects.equals(off.params(), currentWorldParams)
            && off.morphBase() == currentWorldMorphBase
            && off.terrain().isUsable()) {
        offeredTerrain = null;
        WORLD_LIFECYCLE.adopt(off.terrain());   // ★ 接管：保留 4096-chunk LRU + 水文 region
        return null;                            // 不 retire 任何东西
    }
    offeredTerrain = null;                      // 不匹配 ⇒ 丢弃，走原路径
    ... 原有逻辑（worldSeed == seed ? current : buildTerrain(...)）
}
```

### 9.4 安全约束（三条硬性）

1. **种子 + 参数 + morphBase 三者全等才接管**，否则丢弃（防止画着别的参数的地形）。
2. **`adopt` 后必须校验种子一致**：`terrain.generator().processRuntime().context().definition().seed() == seed`
   （已有同类校验在 `buildTerrain:629-630`）。
3. **`ownsTerrain` 语义反转**：接管后该实例归游戏所有，配置屏必须已置 `ownsTerrain=false`，
   不得在关屏时 `closeProcessErosion()`（现有机制已正确，见 `PreviewDisplay:2081`）。

### 9.5 复用的实际收益（可量化）

| 复用对象 | 省掉什么 | 量级 |
|---|---|---|
| 4096-chunk Cell 缓存 | 重新 `sampleCell`（含侵蚀 tile） | 每 chunk 数十 ms |
| 细层水文 region（`REGION=512` 覆盖 32×32 chunk） | 每 region 3.6~10.9 s（日志 `[HYDRO-MODEL] region solved in 10862 ms`） | **秒级** |
| `StratumField` / `TectonicField` 噪声 | 重复场求值 | 与 chunk 数线性 |

### 9.6 验收（探针 `runPreviewWorldReuseProbe`）

| 判据 | 通过条件 |
|---|---|
| 接管前后是**同一个实例** | `assertSame(offered, adopted)` |
| chunk 缓存命中 | 接管后立即 `getChunkCells(同 cx,cz)`，`cacheStats.misses` **不增加** |
| 种子/参数不匹配时丢弃 | 改 seed 后 `offer → bind`，`adopt` 不发生，走 buildTerrain |
| 接管后不泄漏 | `adopt` 后 `offeredTerrain == null` |
| 产出不变 | `runDefaultOutputFingerprintProbe` PASS |
