---
title: 预览/配置屏统一重构 —— 完成交接
status: done
created: 2026-11-09
base: ec74931 (0.0.2)
---

# 预览与配置屏统一重构 —— 完成交接

> 本文是**完成记录**，不是方案。方案见
> [`GeoGenesis-preview-unified-redesign-plan-20261109.md`](GeoGenesis-preview-unified-redesign-plan-20261109.md)。
>
> 用户指出的病：「明明我都说了要重构重构，结果各自完全独立分裂。」

---

## 一、这轮做了什么（一句话）

把「平面 / 沙盘两个平行实现」重构成「**一份快照 + 两个 rasterizer**」，
并把沙盘的所有数学抽成**零 MC 依赖的纯函数层**，使其可离线验证、可被进世界加载复用。

---

## 二、最终架构

```
GeoGenesisConfigScreen（全屏，左侧可折叠悬浮窗 + 12 页签）
    │
    ├── PreviewDisplay（Widget 门面 + 平面精确管线委托 + 图例）
    │     └── PlanePainter（★ 剪切自 PreviewDisplay，447 行）
    │
    └── PreviewController（★ 状态 + 模式分派，唯一分派点）
          ├── PreviewState        中心 / 缩放 / 图层 / 视图模式（两视图共用）
          ├── PlaneRasterizer     俯视正射
          └── SandboxRasterizer   3D 方柱（只负责"画"）
                 └── SandboxGeometry  ★ 纯函数：投影 / 柱高 / 排序 / 光照 / 拾取
                      └── SandboxShade（纯函数，坡度光照唯一实现）
```

**模式分派只发生在 `PreviewController.rasterizer` 这一个字段上。**

---

## 三、新增文件（9 个，全部零 MC 依赖）

| 文件 | 行数 | 作用 |
|---|---:|---|
| `client/preview/TerrainViewMode.java` | 58 | 视图模式枚举（PLANE / SANDBOX） |
| `client/preview/TerrainSnapshot.java` | 156 | 统一数据容器（零拷贝包装 `TerrainFieldData`） |
| `client/preview/PreviewState.java` | 105 | 共享状态（中心/缩放/图层/模式） |
| `client/preview/PreviewController.java` | ~280 | 状态 + 模式分派 + 沙盘绘制 |
| `client/preview/PlanePainter.java` | 531 | 平面精确管线（**剪切**自 `PreviewDisplay`） |
| `client/preview/raster/TerrainRasterizer.java` | 73 | 光栅化接口 + `RasterParams` + `HoverInfo` |
| `client/preview/raster/PlaneRasterizer.java` | 126 | 俯视正射（探针用） |
| `client/preview/raster/SandboxRasterizer.java` | ~490 | 3D 方柱（只画，不算） |
| `client/preview/sandbox/SandboxGeometry.java` | ~600 | **纯函数几何层**（探针 100% 覆盖） |

**零 MC 依赖已实测**：`TerrainViewMode` / `TerrainSnapshot` / `PreviewState` /
`TerrainRasterizer` / `RasterParams` / `HoverInfo` / `PlaneRasterizer` /
`SandboxGeometry` 的 `net.minecraft.*` + `com.mojang.*` import 数**均为 0**。

---

## 四、删除文件（1 个）

| 文件 | 行数 | 理由 |
|---|---:|---|
| `client/preview/SandboxRenderer.java` | **1132** | 与新的 `SandboxGeometry` 构成**两套沙盘实现** —— 正是要消灭的"独立分裂" |

---

## 五、★ 关键决策与依据

### 5.1 沙盘尺度：1 柱 = 16×16 方块

用户裁定：「既然这样其实我们改成按参考项目 16x16 方块吧。」

```
柱数 columns = 128（基准）
细格 fine   = columns × HEIGHT_STRIDE(2) = 256
每柱       = 16 格方块
窗口       = 柱数 × 16
```

### 5.2 ★ 柱高问题：真因是 scale 口径，不是 RELIEF

我曾擅自把 `RELIEF` 从 yardsite 的 0.40 改成 0.85（**误判**），后来又改回。

**实测数据（820px 视口，pitch=0.95/yaw=0.65）：**

| | scale | 盘面宽 | 竖向跨度 |
|---|---|---|---|
| 之前（`fitScaleWide`，盘面刚好装下） | 287 | 804px（=视口） | **93px（11%）** ⇒ 整片看着是平的 |
| **参考项目**（`areaSize×0.46×zoom`，**故意超视口**） | 754 | 2115px（超 2.6 倍） | **245px（30%）** ⇒ 立体感强 |

⇒ **参考项目故意让盘面超出视口**（zoom 默认 2.0），靠"盘面大 + 柱子高"取立体感。

**最终改动**（只改缩放档位，`RELIEF` 保持 yardsite 的 0.40 不动）：
- `SandboxGeometry.scaleOf(areaSize, zoom) = areaSize × 0.46 × zoom`（替代 `fitScaleWide`）
- `SandboxLod.BASE_ZOOM`：1.0 → **2.0**（对齐参考项目默认）

结果：竖向跨度 **93px → 245px（视口 30%）**，与参考项目同档。

### 5.3 沙盘绘制：保留 YS 的逐柱 OpenGL + 几何纯函数层

RTF 用软件光栅化（`int[]` → `DynamicTexture`），但我们拉远档是 **512² = 262,144 柱**
（RTF 是 256² = 65,536，4 倍）⇒ 软件光栅化不可行。

⇒ 保留 OpenGL 逐柱，但把**所有数学**抽到 `SandboxGeometry`（纯函数），
探针与实机**消费同一份 `Column[]`** ⇒ 不可能失同步。

> 这一步是对本轮前几次翻车的直接回应：此前所有渲染问题都源于
> 「离线脚本自己实现另一套逻辑」⇒ 在错图上判断（如 `per_column_for()` 恒返回 1）。

### 5.4 平面精确管线：只剪切，不重写

`PlanePainter` 的方法**原样搬过来**，通过 `host` 访问 `PreviewDisplay` 的包级成员。
平面视觉因此**逐像素不变**（验收靠指纹 + 截图对拍）。

「预览 = 游戏」是这个管线的唯一保证 —— 重写它等于放弃保证。

---

## 六、★ 预览 → 进世界的复用（用户明确要求）

> 用户原话：「这就是我让你抽离 MC 做完全纯 Java 核心的原因，可以在外部计算后直接复用。」

### 6.1 现状缺口（已核实）

```
配置屏（新建世界）→ 自建 GeoGenesisTerrain（采 chunk + 水文 region）
  → 点「应用」→ bindWorld → invalidate + retireTerrain(自建实例)
  → 全新实例，从零重采            ← 预览算的全丢
```

`previewReusableTerrain` 只能复用**存档单例**（游戏中打开预览时），
而配置屏**只在创建新世界时打开** ⇒ 唯一需要的场景恰好没打通。

### 6.2 实现

| 文件 | 改动 |
|---|---|
| `worldgen/generator/PreviewTerrainAdoption.java` | **新建**：接管判据（纯函数，四项全等） |
| `worldgen/generator/WorldSeedLifecycle.java` | 新增 `adopt(seed, value)`（绑定并发布已有实例） |
| `worldgen/generator/GeoGenesisGenerator.java` | 新增 `offerPreviewTerrain()` / `clearOfferedPreviewTerrain()`；`bindSeedLocked` 先尝试接管 |
| `client/preview/PreviewDisplay.java` | 新增 `transferOwnership()`（交出所有权，避免 `close()` 关停引擎） |
| `client/GeoGenesisConfigScreen.java` | `doApply()` 登记接管 + 交出所有权 |

### 6.3 实测收益

```
预览采样 6×6 chunks = 11393 ms（含一个水文 region 2585 ms）
接管后重复访问       =      0 ms   （misses 零增长，hits +36）
```

### 6.4 安全边界（全部验证）

种子不同 / 参数不同 / morphBase 不同 / 实例已退役 / 无实例 ⇒ **一律拒绝**，走原有重建路径。

---

## 七、★ 探针抓到的真 bug（本轮修掉的）

| # | bug | 影响 | 修法 |
|---|---|---|---|
| 1 | `gridFor()` 返回窗口方块数（2048） | **OOM 崩溃**（419 万格 × 十几数组/帧） | 细格 = 柱数 × HEIGHT_STRIDE |
| 2 | `blocksPerPillar` 用细格数（2）而非柱间距（16） | 坡度放大 8 倍 ⇒ 光照饱和 23%/34% | `per × blocksPerCell` |
| 3 | `pick` 逆变换按 2×2 平面解 | 漏 `r5·mz`（系数 -0.81）⇒ 系统性偏 2 柱宽 | 照 yardsite 用**视线推进**（沿深度 t 扫） |
| 4 | `pickWide` 漏 `modelOf` 的逆运算 `-0.5` | 偏半格 | 补 `-0.5` |
| 5 | `SandboxPanProbe` 自复刻拖动数学（旧口径） | 验证的是过时逻辑 | 改调 `SandboxGeometry.dragToBlocks` |

**探针清单（8 个，全 PASS）**：

```
runSandboxGeometryProbe     沙盘几何纯函数（7 条判据）+ 离线出图
runSandboxLodProbe          尺度规格（1 柱 = 16 格，柱数随缩放变）
runPreviewUnifiedProbe      双视图统一（同一快照 → 两个 rasterizer）
runPreviewWorldReuseProbe   预览 → 世界复用（5 条判据）
runSandboxPanProbe          拖动换算
runSandboxShadeProbe        坡度光照分布
runSandboxLayerProbe        14 图层接线
runProgressiveSamplerProbe  后台采样器
```

**回归全绿**：8 探针 PASS + `runDefaultOutputFingerprintProbe` PASS（地形逐位不变）
+ `runWorldgenGate` **ALL PASS**。

---

## 八、行数变化

| 文件 | 之前 | 现在 |
|---|---:|---:|
| `PreviewDisplay.java` | 2254 | **1913**（平面管线已剪切到 `PlanePainter`） |
| `PlanePainter.java` | — | 531（剪切而来） |
| `PreviewController.java` | — | ~280 |
| `SandboxGeometry.java` | — | ~600（纯函数） |
| `SandboxRenderer.java` | 1132 | **0（已删）** |
| 合计（preview 包核心文件） | ~5100 | ~4600 |

> `PreviewDisplay` 仍有 1913 行：图例系统（~180 行）与其余交互逻辑仍在内。
> 继续瘦身需要把图例搬到独立 `LegendPanel`（它与 `selectedLayer`/`filterIds` 深度耦合），
> 属于**下一轮**的工作，不影响本轮的功能与结构目标。

---

## 九、★ 已知遗留

### 9.1 沙盘柱间的暗线

柱间可见黑色细缝。成因：每柱 16 格时相邻柱高差仅 **0.9px**，而侧墙亮度只有顶面的
0.40~0.82 ⇒ 一条暗线。参考项目每柱 31 格，侧墙有 4~10px，不显得是"线"。

⇒ 这是「1 柱 = 16 格」的必然取舍，不是渲染 bug。可选缓解：
① 侧墙亮度整体调高；② 侧墙按真实高度画（不量化）；③ 接受（参考项目观感也偏硬朗）。

### 9.2 `PreviewDisplay` 未完全瘦身

见 §八。

### 9.3 沙盘未接粗层水位

`SandboxWaterLookup` 仍在，但沙盘路径未接（河道靠 `CellCache` 的 `waterY`）。
`TerrainFieldData.fromCellCache` 的 `waterY` 来自 `Cell`，不含粗层水位。

---

## 十、下一步建议

1. **实机验收**（用户）：配置屏 → 平面/沙盘切换 → 看构图、立体感、拖动、悬停
2. **图例搬家** → `LegendPanel`，`PreviewDisplay` 降到 ≤400 行
3. 沙盘接粗层水位（河道/湖面更清楚）
4. 沙盘侧墙暗线是否要缓解（§9.1）

---

## 十一、runbook

```powershell
cd forge-1.20.1-47.4.10-mdk

# 编译
.\gradlew.bat compileJava compileDiagnosticsJava compileAcceptanceJava

# 沙盘几何（纯函数，含离线出图）
.\gradlew.bat runSandboxGeometryProbe "-PsandboxPng=build\geo"

# 尺度 / 统一 / 复用
.\gradlew.bat runSandboxLodProbe
.\gradlew.bat runPreviewUnifiedProbe
.\gradlew.bat runPreviewWorldReuseProbe

# 回归（地形逐位不变）
.\gradlew.bat runDefaultOutputFingerprintProbe -PprobeArgs="--compare build\s3-fp.tsv"
.\gradlew.bat runWorldgenGate -x runCenterlineCrossSectionProbe

# 实机
.\gradlew.bat runClient
```

**打开配置屏**：主菜单 → 创建世界 → GeoGenesis 配置（`CreateWorldScreen` 下）。
游戏内也可开（只读）。

**沙盘快捷键**（沙盘模式内）：
- 右键拖动 = 移动地图（移动**世界中心**，不是平移像素）
- 左键拖动 = 旋转视角
- 滚轮 = 推近/拉远（推近柱数减少、柱子变大）
- Ctrl+滚轮 = 柱宽
- R = 重置视角

---

## 十二、给接手方的话

1. **沙盘的所有数学都在 `SandboxGeometry`**（纯函数、零 MC）。
   改投影/柱高/排序/光照/拾取 ⇒ 只改那里 ⇒ 跑 `runSandboxGeometryProbe` 即可验证。
   **不要**在 `SandboxRasterizer` 里加任何几何计算。
2. **别再写"复刻渲染逻辑"的离线脚本**。本轮前几次翻车全源于此
   （`per_column_for()` 恒返回 1，我在错图上判断了好几轮）。
   要出图就用 `SandboxGeometryProbe`（它调用的是实机同一份函数）。
3. **`RELIEF` 不要动**。它是 yardsite 的 0.40，立体感来自
   `scale = areaSize × 0.46 × zoom` 与 `BASE_ZOOM = 2.0`。
   （我曾擅自改成 0.85，被证伪。）
4. **复用契约在 `PreviewTerrainAdoption`**（纯函数，探针可测）。
   改判据必须同步改探针的负对照。
5. `PlanePainter` 是**剪切**来的，改它等于动「预览 = 游戏」的保证 ⇒ 必须跑指纹对拍。
