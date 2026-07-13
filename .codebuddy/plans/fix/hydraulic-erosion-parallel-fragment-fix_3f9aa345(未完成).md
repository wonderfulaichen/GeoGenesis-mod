---
name: hydraulic-erosion-parallel-fragment-fix
overview: 修复 droplet 水力侵蚀产生的同向平行沟壑与河流碎片化。根因：方向模型错误（INERTIA=0.005 纯沿最陡下降 + 动量项误用梯度方向而非既有水流方向）、水滴寿命过短（10-15 步无法贯穿区域）、速度累积死代码、carve/成河阈值过高。对齐 C++ SimpleHydrology water.h 原理重写方向/速度更新并加水流方向累积，提高寿命，放宽刻蚀/成河阈值。
todos:
  - id: alloc-flowdir
    content: HydraulicErosion 新增 flowX/flowY 字段并在 apply 内随 h/dis 同尺寸分配清零
    status: pending
  - id: rewrite-drop
    content: 重写 simulateDrop 方向/速度模型（重力下坡+沿flowX/Y动量+定长步进），删 INERTIA、修速度死代码、调 GRAVITY/MOMENTUM/LIFE
    status: pending
    dependencies:
      - alloc-flowdir
  - id: relax-carve
    content: 放宽 carveRivers 阈值 0.12→0.06、半径 2→3 以连通河谷
    status: pending
    dependencies:
      - rewrite-drop
  - id: lower-threshold
    content: RiverSettings.riverMinDischarge 12→4 并同步调低 GeoGenesisConfig.RIVER_MIN_DISCHARGE 默认
    status: pending
  - id: verify
    content: compileJava 编译通过，runPreview/runClient 目检无平行沟壑且河道成网
    status: pending
    dependencies:
      - alloc-flowdir
      - rewrite-drop
      - relax-carve
      - lower-threshold
---

## 用户需求

分析并修复 GeoGenesis 地形引擎中水滴水力侵蚀（droplet hydraulic erosion）暴露的两个视觉/形态缺陷：

1. **同向平行侵蚀**：截图显示大片近乎平行、方向一致的侵蚀沟壑，疑似 bug。
2. **河流碎片化**：本应汇聚成完整水系的河道被拆散成孤立水塘/碎段，未连成连续河道。

用户要求不确定时参考 `参考/sources/SimpleHydrology`（C++ 原版 droplet 算法）与备份 `backups/...物理侵蚀-整体基本无断裂-局部断裂`。

## 核心特征

- 平行沟壑：在存在一致坡度方向的区域，侵蚀刻出大量平行直线沟，不符合真实河流蜿蜒汇聚的特征（截图 1）。
- 河道断裂：水网在陆地上呈散点状，主流无法从源头连到海口，河谷被切断成孤立片段（截图 2）。
- 修复目标：河流应沿既有水道蜿蜒、自我强化并汇聚成连续水系；消除同向平行沟壑；近海平面不产生过深沟壑。

## 功能与视觉效果

- 侵蚀产出的河谷从"平行直线沟"变为"蜿蜒、相互汇聚的树状水系"。
- 河流流量场（dis）高度集中于少数主干河道，碎片化水塘消失，河道连成完整网络。
- 游戏内与独立预览（TerrainPreview / PreviewDisplay）中河流连续、自然，无棋盘/平行伪影。

## 技术栈

- 语言/平台：Java 17 + Minecraft Forge 1.20.1 模组（Gradle 构建，入口 `gradlew.bat build` / `compileJava`）。
- 零 MC 依赖核心：`HydraulicErosion`（droplet 侵蚀 + 河谷刻蚀）、`RiverField`（region 级河流/侵蚀计算与结果装配）、`RiverSettings`（侵蚀/河流参数）。
- 参考依据：C++ 原版 `参考/sources/SimpleHydrology/source/water.h` 的 `Drop::descend()` 方向/动量模型（已确认是当前 Java 实现偏差的对照基线）。

## 关键发现（已代码核实）

原 plan 的「512-block X/Z 转置 bug」在当前代码中**已修复**：`GeoGenesisTerrain.sampleHeight`/`applyRiverFields` 已用 `r.finalY[j][i]`（`i=worldX-obx, j=worldZ-obz`），与 `RiverField` 的 `[z][x]` 写入约定一致。真正问题在 `HydraulicErosion` 的侵蚀物理模型，对照 `water.h` 存在以下偏差：

1. **方向模型错误**：`INERTIA=0.005` + `dir = dir*INERTIA - grad*(1-INERTIA)`（L21/L122）使水滴近乎纯沿最陡下降，坡度一致区域所有水滴走同一直线 → 平行沟壑（截图 1 主因）。
2. **动量用错方向**：L126 动量项 `dir += a*gx`（用梯度方向）而非「该格已累积的水流方向」，无法沿既有河道强化、也不产生蜿蜒（截图 2 主因之一）。
3. **寿命过短**：`LIFE_LARGE=10 / LIFE_FINE=15`（L24/L26），步长约 1 block，水滴仅走 10–15 block，无法贯穿区域连成河道（截图 2 主因之二）。
4. **速度累积死代码**：L131 `spd = sqrt(spd + abs(h0-h0)*GRAVITY)` 中 `h0-h0=0`，重力加速失效。
5. **仅累积 dis 幅值，无水流方向累积**，导致无法做「沿既有流向」的正反馈。
6. **下切/成河阈值过严**：`carveRivers` 阈值 `t<0.12`（L195）+ 半径 `r=2`（L184）；`RiverSettings.riverMinDischarge=12.0`（RiverSettings.java:32）过高，中等流量段不刻蚀/不标河，加剧碎片。

## 实现方案（对齐 water.h 原理）

**策略**：仅重写 `HydraulicErosion` 的方向/速度/寿命模型与下切阈值，并在 `RiverField` 支撑水流方向累积；不改存储端与读取端坐标约定（已正确）。

### A. HydraulicErosion — 方向/速度模型重写（仿 water.h）

- 新增实例字段 `private float[][] flowX, flowY;`，在 `apply` 内随 `h/dis` 同尺寸分配并清零（避免改 `apply/spawnAndSim/simulateDrop` 签名，blast radius 最小）。
- `simulateDrop` 每步：
- 计算下坡方向：`gx=dH/dx, gy=dH/dz`，下坡 = `(-gx,-gy)`。
- 速度向量累积重力：`sx += -gx*GRAVITY; sy += -gy*GRAVITY`（修复 L131 死代码，重力真正加速）。
- 动量（沿既有水流方向）：`fx=flowX[iy][ix], fy=flowY[iy][ix]`；`fmag=hypot(fx,fy)`；若 `fmag>0`：`align=max(0, dot(sx,sy,fx,fy)/(fmag*slen+ε))`；`k=MOMENTUM*fmag/(fmag+dis[iy][ix]+ε)*align`；`sx += k*fx; sy += k*fy`。该正反馈使河道蜿蜒并自我强化、汇聚成网（解决平行 + 碎片）。
- 定长步进：`slen=hypot(sx,sy)`；若 `<1e-9` return；`posX += sx/slen*STEP; posY += sy/slen*STEP`（STEP≈1.0，对齐 water.h 定值步进）。
- 累积水流方向：`flowX[iy][ix] += water*sx/slen; flowY[iy][ix] += water*sy/slen`。
- 参数对齐：删除 INERTIA 用法；`GRAVITY=1.0`（原 2.5）、`MOMENTUM=0.8`（原 0.6，强化河道汇聚）、`ENTRAINMENT=10` 保持；`LIFE_LARGE=100, LIFE_FINE=150`（原 10/15，使水滴可贯穿区域、从源头连到河口）。

### B. RiverField — 支撑水流方向累积

`computeRegion`（L93-100）在 `erosion.apply(...)` 前分配 `flowX/flowY`（已由 `apply` 内部完成，无需改 `computeRegion` 签名）；`erosion` 为每 region 新建实例，字段无跨区污染。确认 `apply` 内 `flowX/flowY` 与 `h/dis` 同步清零、随 `dis` 在 L169 处同格点累积。

### C. carveRivers — 放宽阈值与半径

- L195 阈值 `t < 0.12f` → `t < 0.06f`（让中低流量段也被刻蚀，连通河谷）。
- L184 半径 `r = 2` → `r = 3`（刻出略宽 U 形谷，水流更易汇聚成连续河道）。
- 保留近海平面 damp（L197 `abv/0.1f`）与下切不超过 seaNorm 的约束。

### D. RiverSettings — 降低成河流量阈值

- `riverMinDischarge` 默认 `12.0` → `4.0`（RiverSettings.java:32）；同步核查并调低 `GeoGenesisConfig.RIVER_MIN_DISCHARGE` 默认值（运行期 MC 实际读数来源），使中等流量段也标为河流、铺水连通。
- 其余默认值（erosionStrength/erosionDropsMul/erosionErodeMul/riverValleyDepth）保持，避免回归。

## 性能与可靠性

- 复杂度：侵蚀工作量 ≈ drops × lifetime × 侵蚀核大小。drops≈area×0.065（512 region ≈ 17k），lifetime≈100–150 → 约 2–2.5M 步；每步侵蚀核（R_LARGE=6≈113 点 / R_FINE=4≈50 点）→ 每 region 约 1.5–3 亿次浮点操作。region 级一次计算并缓存（LRU，30s TTL），单 region 首算约数十毫秒级，可接受；预览/MC 共用同路径，无额外开销。
- 确定性：rng 以 `(ox,oz)` 派生，水流方向累积为同 region 内跨滴正反馈，结果确定、跨区无缝（坐标约定已正确）。
- 风险：寿命增大使首算略慢，但仅影响缓存未命中；`smoothErosionResult` 近海平面平滑保留，抑制海岸过深沟壑。建议 `riverValleyDepth` 维持 0.06 不变，避免河谷过深。

## 架构与数据流

```mermaid
flowchart LR
    A[RiverField.computeRegion] --> B[分配 h/dis/flowX/flowY 清零]
    B --> C[HydraulicErosion.apply 撒滴]
    C --> D[simulateDrop: 重力下坡 + 沿flowX/Y动量 + 定长步进]
    D --> E[累积 flowX/Y 与 dis]
    E --> F[carveRivers 按 log1p(dis) 下切 阈值0.06/半径3]
    F --> G[TileLakeSolver 解湖]
    G --> H[RiverField.Result 返回 finalY/dis/riverMask...]
    H --> I[GeoGenesisTerrain 读取 finalY[j][i] 已正确]
```

## 目录结构

```
forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/river/
├── HydraulicErosion.java   # [MODIFY] 重写 simulateDrop 方向/速度模型（重力+沿flow方向动量+定长步进），删 INERTIA 用法、修速度死代码；新增 flowX/flowY 实例字段并在 apply 内分配清零；放宽 carveRivers 阈值 0.12→0.06、半径 2→3；调常量 GRAVITY=1.0/MOMENTUM=0.8/LIFE_LARGE=100/LIFE_FINE=150。
├── RiverField.java         # [MODIFY 轻微] 确认 erosion.apply 内部已分配 flowX/flowY（无需改签名）；保留 computeRegion 现有写入约定（[z][x]）。
└── RiverSettings.java      # [MODIFY] riverMinDischarge 默认 12.0 → 4.0。
forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/config/
└── GeoGenesisConfig.java   # [MODIFY 轻微] RIVER_MIN_DISCHARGE 默认值同步调低（与 RiverSettings 对齐），范围下限允许更小值。
```