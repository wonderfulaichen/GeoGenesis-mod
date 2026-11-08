---
title: yardsite 完整研究 + 与本项目融合设计
status: research
created: 2026-11-08
source: 参考/sources/yardsite (MIT, github.com/CYun2024/yardsite)
---

# yardsite 完整研究与融合设计

> 本文是**逐行读完**参考项目核心文件后的完整结论。
> 之前几轮我边看边改，导致沙盘"像草丛""效果不对"——那些错误都在 §2 列出并给出根因。
>
> **读完的文件**（共 6 个，3085 行）：
> `SiteTerrainRenderer`(1054) · `SiteMapSampler`(630) · `YardSiteScreen`(1275) ·
> `BiomePalette`(176) · `SurfaceProbe`(161) · `SiteField`(106)

---

## 一、参考项目的完整架构

### 1.1 三层职责

```
SiteField（接口 106 行）
   ↑ 抽象"一张能画成沙盘的地形场"
   ├── SiteMapSampler.View      大沙盘：128~512 根柱子，中心向外采样
   └── SiteReliefField          区块精读：121 格圆形窗口

SiteTerrainRenderer（1054 行）
   ↑ 只认 SiteField，两种视图共用一套柱子/投影/排序

YardSiteScreen（1275 行）
   ↑ 界面：沙盘铺满 + 右侧编辑面板 + 右下按钮
```

**关键设计：渲染器只认接口**。所以"大沙盘"和"区块精读"共用同一个渲染器，
区别只在 `columns()` / `perColumn()` / `step()` 这几个方法。

### 1.2 数据流（`SiteMapSampler.build()` 第 421-457 行）

```
后台线程池（核数÷2，优先级 -2）
   │  AtomicInteger cursor 领活
   ↓
按 centerOutOrder 从中心向外
   │
   ├─ samplePoint(ci,cj)            ← 先问群系，是水就记海平面、跳过高度采样
   ↓
settleNearby 检查周围 3×3          ← 自己+四邻高度齐了 → paintBlock
   │
   ├─ paintBlock                     ← 算坡度光照、写 2×2 细格的 heights/biome/light
   ↓
View（活数据，渲染线程直接读）        ← blockState: PENDING/WORKING/READY
```

**这是"边算边画"的机制**：不是分帧，而是后台线程跑、渲染线程读活数据。
`blockState` 三态保证渲染器只画已经写好的块。

---

## 二、我之前错在哪（逐条对照）

| # | 我做的 | 参考项目实际 | 后果 |
|---|---|---|---|
| 1 | 用 `Tesselator`/`BufferBuilder` | `g.bufferSource().getBuffer(RenderType.gui())` + `g.flush()` | **全黑** |
| 2 | 自己推投影公式 | JOML `Matrix3f.rotateZ(roll)→rotateX(pitch)→rotateZ(yaw)` 摊成行 | **竖条纹** |
| 3 | 4 个侧面全画 | `${seeEast=r[6]>0}` 等**按矩阵符号只画 2 面** | 面片翻转、遮挡错 |
| 4 | 每格都画侧墙 | `top > max(邻居, 0)` 才画 | **密集黑线（筛子感）** |
| 5 | 水下 `top = -WATER_SINK`（负值） | `ratio = max((Y-base)/span, WATER_SINK)`，**永远为正** | **水面凹成坑** |
| 6 | `e∈[-1,1]` 乘系数 | 用**真实 Y**：`(surface - base)/span`，`base = seaLevel-4` | 高差比例全错 |
| 7 | 每帧重算 `span` | `easeReliefSpan`：涨 8%/降 2%，差 0.6 内对齐 | 高度抖动 |
| 8 | `RELIEF = 0.85`（我改的） | **`RELIEF = 0.40`** | 柱子过高 |
| 9 | `zoom = 1.0` | **`zoom = 2.0`** | 沙盘小一半 |
| 10 | `scaleOf = areaSize/2.5` | `areaSize * 0.46 * zoom * zoomBoost` | 尺寸不符 |
| 11 | `dragRotate` 系数 0.010、pitch 加 | **0.006、pitch 减** | 手感反了 |
| 12 | 无 `widthScale` | 有，0.5~2.0，在 `vertex()` 里横向撑开 | 缺"粗细"旋钮 |
| 13 | 无 `panX/panY` | 有，`dragPan` 右键拖动 | 不能平移 |
| 14 | 分帧渲染线程采样 | **后台多线程**一次跑完 | 永远看到粗档 |
| 15 | 按索引猜邻居 | `column % columns` / `column / columns`（**列主序**） | 邻居错位 |
| 16 | `model(i,n)` 分母 `n` | **分母 `n-1`**（细格是"点"） | 边界偏一格 |
| 17 | 无底板 | `floor()` 画 `[-1,1]²` 深色板 | 柱子悬空 |

---

## 三、参考项目的关键常数（照抄清单）

```java
RELIEF        = 0.40F    // 地形起伏最大厚度（地面边长 2）
WATER_SINK    = 0.05F    // 水面薄层（不是负值！）
DEPTH_SCALE   = 110.0F
Z_BASE        = 200.0F
PITCH_MIN     = 0.22F
PITCH_MAX     = 1.40F
DEFAULT_PITCH = 0.95F    // ≈54°
DEFAULT_YAW   = 0.65F
BUCKETS       = 4096
zoom 初值     = 2.0F     // 范围 0.6~8.0
widthScale    = 1.0F     // 范围 0.5~2.0
scaleOf       = areaSize * 0.46 * zoom * zoomBoost
base          = seaLevel - 4
span          = max(20, max(60, 最高地表 - base))  // 经 easeReliefSpan
ratio         = max((surface - base) / span, WATER_SINK)
tops          = ratio * RELIEF
```

**侧墙亮度**（`render()` 第 448-475 行）：

| 方向 | 内侧 | 外侧 |
|---|---:|---:|
| East  | 0.82 | 0.60 |
| West  | 0.56 | 0.40 |
| South | 0.70 | 0.52 |
| North | 0.46 | 0.34 |

---

## 四、与本项目原有设计的融合

### 4.1 我们有的、它没有的（必须保留）

| 能力 | 我们的实现 | 为什么保留 |
|---|---|---|
| **图层切换** | `GeoPalette.PreviewLayer`（高程/群系/温度/湿度/降水/类型…） | yardsite 只有"群系"一种，我们需要多维诊断 |
| **精确模式**（预览=游戏） | `PreviewDisplay` 精确管线（1:1~1:16，含侵蚀） | yardsite 只有粗采样；我们的核心承诺是"预览=游戏" |
| **结构图标叠加** | `StructureIconOverlay` | yardsite 没有 |
| **磁盘缓存** | `PreviewDiskCache`（schema 版本控制） | yardsite 每次重建 |
| **配置面板 12 页签** | `PanelLayout` + `DomainPanel` | yardsite 只有一个 178px 编辑面板 |
| **页面布局** | `LargeAreaSampler`（Swing 预览共用） | 独立窗口还在用 |

### 4.2 它有的、我们没有的（要吸收）

| 能力 | 参考位置 | 价值 |
|---|---|---|
| **后台多线程采样** | `SiteMapSampler.build()` 421-457 | 一次到位 vs 分帧猜谜 |
| **中心向外 + 三态就绪** | `centerOutOrder` 187, `blockState` | 边算边画 |
| **真实 Y 竖向归一 + 缓动** | `collect()` 660/677/686 | 柱高比例正确 |
| **按矩阵符号判可见面** | `render()` 418-422 | 正确遮挡 |
| **widthScale 横向撑开** | `vertex()` 752-757 | "粗细"旋钮 |
| **逐柱淡入** `drawnAt` + FADE_MS | `collect()` 684-690 | 长出动画 |
| **坡度光照注入颜色** | `paintBlock()` 553-555 | 块面感 |
| **水面按深度渐变** | `BiomePalette.colorFor()` 91-95 | 深浅可读 |

### 4.3 融合后的目标形态

```
GeoGenesisConfigScreen（全屏）
├── 地形区：占满 (0,0,w,h)
│   ├── 平面模式：现有 PreviewDisplay（保留图层/缓存/结构/精确管线）
│   └── 沙盘模式：新 SandboxRenderer（移植 yardsite）+ 现有图层颜色
│        ↑ 两种模式共用同一份 TerrainFieldData
│
└── 左侧悬浮配置窗（可折叠）
    ├── 12 页签（现有）
    ├── 种子/图层（并入「世界与尺度」）
    └── 底部两行按钮：应用/保存/重置 + 图层箭头/大范围/平面-沙盘
```

**关键约束（来自我们的项目铁律）**：
- 沙盘模式**只用于看形态**，不承诺"预览=游戏"（那由平面精确模式保证）。
- 沙盘绝不触碰地形产出 → 任何沙盘改动后必须跑 `runDefaultOutputFingerprintProbe` 确认逐位不变。
- 颜色来源统一走 `GeoPalette`，不引入第二套调色板（yardsite 的 `BiomePalette` 只作为"按深度渐变"的实现参考）。

### 4.4 不采纳的部分

| 参考内容 | 为什么不做 |
|---|---|
| 区块精读（`ChunkSitePlanner` 570 行） | 我们是配置面板，不是选点工具 |
| 落成动画（`SiteRevealWorldRenderer` 1304 行） | 同上，且我们没有"进世界前"的时机 |
| 出生点选点 + 双层视图 | 目标不同 |
| `SurfaceProbe` 竖直扫描优化 | 它的成本来自 `getBaseHeight` 扫空气；我们的 `sampleCellCoarse` 是纯噪声求值，无此成本 |
| `SurfaceTrend` 邻居趋势 | 同上 |
| TetraBlender/Sodium 兼容 | 不需要 |
| `majorityCell` 2×2 多数群系 | 我们单格采样，无聚合需求（柱子=细格） |

---

## 五、当前实现状态 vs 目标

| 项 | 状态 |
|---|---|
| 后台多线程采样 | ✅ 已改（reset 4.19ms 返回，128×128 采完 64.8ms，零差异） |
| 真实 Y 竖向归一 | ✅ 已改 |
| `base = seaLevel - 4` / `ratio = max(...)` | ✅ 已改 |
| `easeReliefSpan` 缓动 | ✅ 已改 |
| 可见面按矩阵符号 | ✅ 已改 |
| 侧墙 `top > low` 条件 | ✅ 已改 |
| `g.bufferSource()` 渲染 | ✅ 已改 |
| `RELIEF = 0.40` | ✅ 已改 |
| `zoom = 2.0` | ✅ 已改 |
| `scaleOf = areaSize*0.46*zoom` | ✅ 已改 |
| `dragRotate` 0.006 / pitch 减 | ✅ 已改 |
| `widthScale` | ⚠️ 已加字段与 `vertex()` 支持，**UI 未接** |
| `panX/panY` | ⚠️ 已加字段，**右键拖动未接** |
| `model(i, n-1)` 分母 | ⚠️ **待核对** |
| 底板 `floor()` | ✅ 已加 |
| 逐柱淡入 `drawnAt` | ❌ 未做 |
| 颜色按图层走 `GeoPalette` | ✅ 保持 |

---

## 六、下一步（按优先级）

1. **核对 `model()` 分母与邻居索引**（§5 标 ⚠️ 的两项，错一格就会错位）
2. **接 widthScale / pan 到 UI**（右键拖动平移）
3. **逐柱淡入**（可选，观感加分）
4. **实机验证**：旋转、缩放、平移、两种模式切换
5. 全部通过后跑门禁 + 指纹，再提交
