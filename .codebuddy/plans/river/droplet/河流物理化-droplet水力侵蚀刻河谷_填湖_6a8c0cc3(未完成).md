---
name: 河流物理化-droplet水力侵蚀刻河谷+填湖
overview: 用户否决路线 B 的 D8 妥协（只标 riverMask 不刻地形，导致「河流不成河流、流向不对」），明确选定「纯 droplet 水力侵蚀刻河谷 + TileLakeSolver 填洼」。移植备份 ErosionEngine 的水滴水力侵蚀，在区域级 eLand 高度场直接把树枝状河谷刻入地形（流向=地形最陡下降，物理正确），用 priority-flood 解闭流盆地成湖。替换现有 RiverField（D8）。预览优先：先加 RIVER_NETWORK 图层目检河网形态，验证后再接 MC 刻蚀。
todos:
  - id: new-hydraulic-erosion
    content: 新建 HydraulicErosion（移植 droplet 侵蚀+simulateDrop+carveRivers+后处理，零 MC）
    status: pending
  - id: port-tile-lake-solver
    content: 移植 TileLakeSolver 到 worldgen/river（priority-flood 解湖）
    status: pending
  - id: rewrite-river-field
    content: 重写 RiverField 为 region 驱动器（采样 eLand→侵蚀→dis 派生→解湖→2D 数组 Result）；删除 RiverNetwork
    status: pending
    dependencies:
      - new-hydraulic-erosion
      - port-tile-lake-solver
  - id: river-settings-droplet
    content: RiverSettings 改写为 droplet 参数（dropsMul/erodeMul/carve/minDischarge/lake*）
    status: pending
    dependencies:
      - rewrite-river-field
  - id: terrain-wiring
    content: GeoGenesisTerrain 改写 rasterizeRiverNetworkInto 采样网格填 Cell；预览路径喂 eCarved 到高度；CellGenerator 加 heightFromELand
    status: pending
    dependencies:
      - rewrite-river-field
  - id: preview-layer
    content: GeoPalette 加 RIVER_NETWORK 图层与 color case；Cell 注释改 erosion 语义
    status: pending
    dependencies:
      - terrain-wiring
  - id: preview-tune
    content: runPreview 目检调参（drops/erode/carve/lake 阈值），验证树枝状河网与真实河谷
    status: pending
    dependencies:
      - preview-layer
  - id: mc-contract-build
    content: 用 [subagent:code-explorer] 核对 MC 下游契约；config 暴露参数；gradlew build + 文档同步（阶段2）
    status: pending
    dependencies:
      - preview-tune
---

## 用户需求

- 用户否决当前路线 B（`RiverField` D8 下坡汇流妥协）：河流「完全不成河、流向不对、完全没物理河流效果」。根因是路线 B 只标 `riverMask` 不刻地形，河流只是画在平滑地形上的细蓝线。
- 确认方案（q1/q2 已选）：采用**纯 droplet 水力侵蚀**（参考备份 `ErosionEngine`，SimpleHydrology 范式）——在 `eLand` 高度场上撒水滴沿**最陡下降**走，沿途侵蚀/沉积并累计 `dis` 流量场，再按 `log1p(dis)` 把**树枝状河谷真正刻进 `eLand`**；流向=地形梯度最陡下降，物理正确、有机不规则；河流形态由侵蚀自然涌现，一套系统最简。
- 湖泊采用 **`TileLakeSolver` priority-flood**：在侵蚀后高度数组上解闭流盆地，最物理、与侵蚀同源。
- 在 **region 级**（复用现有 512-block region 缓存 + padding 世界对齐无缝）对 `eLand` 跑侵蚀，产出 (1) 侵蚀后 `eLand`（真实河谷下切）、(2) 流量场 `dis`（→ 河宽/河床/`riverMask`）、(3) 湖泊（填洼）。预览与游戏走同一条采样路径。

## 核心特性

- **水滴沿坡侵蚀**：每滴从确定性种子位置出发，双线性梯度求最陡下降方向，沿途 `cap` 判定侵蚀（按 brush 平滑扣高、收沙）或沉积（下坡/超载落淤），每步 `addD(wat)` 累计流量场 `dis`。
- **河谷自然涌现**：`carveRivers` 按 `d=log1p(dis)/log1p(maxDis)` 把树枝状河谷下切进 `eLand`（流量大刻深、近海平面减弱），流向完全由地形决定。
- **闭流盆地成湖**：`TileLakeSolver.solveTile` 在侵蚀后高度上 priority-flood 填洼，按深度/面积阈值提取湖泊（mask + 水面高度）。
- **确定性 + 跨区无缝**：region 级一次计算，droplet 位置由 region seed 哈希派生；padding 按世界坐标采样，相邻 region 共享边界值 → 无接缝。
- **预览优先 + 物理可见**：阶段1 即在预览路径把 `eCarved` 喂给高度与 `RIVER_NETWORK` 图层，让用户直接看到真实河谷与河网；`CellGenerator.populate`/`heightAt` 保持未刻蚀（雨影契约不变）。

## 技术栈

- Java 17 / Forge 1.20.1；零 MC 依赖纯 Java 引擎（`worldgen/river`、`worldgen/terrain`），与 `TerrainBlender`/`StructuralField` 同层。
- 复用 `CellGenerator` 已实现的 `HeightProvider`（返回未刻蚀 `eLand`、海洋 `NaN`）+ `GeoGenesisTerrain.getRegionCells(...)` 作为预览与 MC 共用的采样入口。
- 下游沿用 `GeoPalette`/`GeoPalette.PreviewLayer` 枚举、`TerrainPreview`（`values().length` 自动遍历）、`GeoGenesisGenerator`（放水）、`BiomeSource`（气候选群系，不变）。

## 实现方案

### 策略

把「河流生成」从「D8 拓扑妥协」整体替换为 **droplet 水力侵蚀刻河谷**：每个 region（对齐现有 512-block region 缓存，含 padding 世界对齐）先由 `HeightProvider` 采样确定性 `eLand` 网格，在其上跑水滴水力侵蚀（沿最陡下降、累计 `dis` 流量场），再 `carveRivers` 按 `log1p(dis)` 把树枝状河谷刻入 `eLand`，最后 `TileLakeSolver` 解闭流盆地成湖。结果以 **2D 网格数组** 形式缓存，预览与后续 MC 生成共用同一份采样。阶段1 仅把结果喂给预览（高度层看真实河谷 + `RIVER_NETWORK` 图层看河网），不动 `CellGenerator.populate` 的地形契约。

### 关键技术决策

1. **droplet 侵蚀 > D8 拓扑（用户已确认）**：D8 只算拓扑不刻地形，河流只是标签；droplet 侵蚀天然产出 `dis` 流量场并以物理正确的「最陡下降」为流向，刻出的河谷随流量由浅到深、有机不规则，河流形态完全涌现，无需额外的「河网几何雕刻」系统（决策文档 q1 推荐项）。
2. **网格结果取代线段结果**：备份的 `RiverNetwork.Seg` 是 D8 拓扑的线段表达；droplet 直接产出逐格 `dis` 流量场，故 `RiverField.Result` 改为持有 **2D 数组**（`eCarved`/`dis`/`riverMask`/`riverWidth`/`riverWetness`/`lakeMask`/`lakeLevel`），与现有 `RegionRiverArrays`（本就是 2D 数组）完美契合，删去线段距离场栅格化的复杂逻辑。
3. **确定性 spawn（简化备份的 per-chunk 循环）**：备份 `applyErosionNormalized` 按 chunk 列表循环派发水滴（开销大）。改为 region 级按总 droplet 数 `N = baseDrops × erosionDropsMul`，每个 droplet 用 `hash(regionSeed, dropIndex)` 派生起始 cell + 偏移，确定性、无 chunk 循环开销，跨区仍无缝（padding 世界对齐）。
4. **padding 世界对齐无缝**：eLand 网格外扩 `±PAD` 个 cell，padding 处用世界坐标 `(regionOriginX - PAD*cellStep + ...)` 采样 `landHeight`，相邻 region 在共享边界拿到相同值 → 侵蚀/河谷跨区无接缝（沿用既有 padding 范式）。
5. **`dis` → 河网字段**：`riverMask = dis ≥ riverMinDischarge`；`riverWidth ∝ clamp(K·√dis, min, max)`；`riverNetDischarge = dis`；`riverNetDist` 由对 `riverMask` 做一次 BFS 距离变换得到（0=河心,1=谷缘）；`riverNetOverflow` 由「dis 高但局部坡度极低」派生（潜在河漫滩）。
6. **预览物理可见（验证用）**：`getRegionCells` 在填完展示字段后，把 `eCarved` 网格采样值经 `CellGenerator.heightFromELand(e)` 覆盖 `cell.height`（仅预览/region 装配层；`populate`/`heightAt` 不刻蚀 → 雨影契约不变）。用户即可在 HEIGHT 图层看到真实河谷、在 RIVER_NETWORK 看到河网。
7. **下游契约不变**：`Cell.riverMask` 布尔（放水/预览蓝/RIVER 类型）、`riverWetness`、`lakeMask`/`lakeLevel` 全部保留；`HeightProvider` 接口不变；MC 放水路阶段2 才接。

### 性能与可靠性

- 复杂度 ≈ `O(drops × lifetime)`，region 级（hs=4 时 128×128）跑一次，`erosionDropsMul` 控制密度；droplet 步数 `LIFE_LARGE/FINE` 限制。按 region 缓存（`regionRiverCache`，现有 512 上限清空），跨 chunk 零重复。
- `carveRivers`/`TileLakeSolver` 各 O(n)（n=cell 数）；BFS 距离变换 O(n)。均远低于旧 per-chunk D8 多次重算。
- `dis` 缓冲按 region 分配、计算完即弃，不常驻；`RegionRiverArrays` 仅存展示所需 2D 数组，内存可控。
- 不确定性点：droplet 在极高起伏山地可能刻出过深沟壑 → 用 `carveRivers` 的 `damp`（近海平面减弱）+ `smoothErosionResult`/`smoothDepositionZones` 后处理抑制；阈值（`riverMinDischarge`/`erosionErodeMul`）留作可调旋钮。

### 防回归

- `riverMask` 布尔语义（放水/预览蓝/RIVER 类型/DRAINAGE 图层）必须保留。
- 删除 `RiverNetwork`（Seg 线段结构）与旧 D8 逻辑；`RiverField` 重写为 droplet 驱动器，避免双份河网。
- 侵蚀只影响预览装配层高度与展示字段；`CellGenerator.populate`/`heightAt` 不进侵蚀（雨影契约不变）。
- 阶段1 不修改 MC `fillFromNoise` 路径（放水留阶段2），零地形生成回归风险。

## 架构设计

```mermaid
flowchart TD
    A[GeoGenesisTerrain.getRegionCells 区域key取缓存] --> B[采样 eLand 网格 +padding 世界对齐]
    B --> C[HydraulicErosion.apply: droplet 沿最陡下降侵蚀/沉积+累计 dis 流量场]
    C --> D[carveRivers: log1p(dis) 把树枝状河谷刻进 eCarved]
    D --> E[由 dis 派生 riverMask/riverWidth/riverWetness + BFS 距离]
    E --> F[TileLakeSolver.solveTile: priority-flood 解闭流盆地→lakeMask/lakeLevel]
    F --> G[RiverField.Result 2D 数组; 采样填 Cell 展示字段 + 预览高度]
    G --> H[TerrainPreview: HEIGHT 看真实河谷 + RIVER_NETWORK 看河网]
    H -->|阶段2| I[CellGenerator 采 eCarved 写 cell.height; GeoGenesisGenerator 放水]
```

## 目录结构

```
forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/river/
├── HeightProvider.java   # [KEEP] 函数式接口：landHeight 返回未刻蚀 eLand（海洋 NaN）+ provinceWeights + landToWorld。供侵蚀采样与 RainShadow。
├── HydraulicErosion.java # [NEW] droplet 水力侵蚀引擎（移植备份 ErosionEngine）：apply(eLand[][],sz,ox,oz,seaNorm,str)+ simulateDrop + carveRivers + 后处理 smooth*；零 MC。输出侵蚀后 eLand + dis 流量场。
├── TileLakeSolver.java   # [NEW] 移植备份：solveTile(heights,ox,oz,size,worldHeightBlocks) priority-flood 填洼解闭流盆地 → PatchResult(lakeLevelNorm,mask)。
├── RiverField.java       # [MODIFY] 重写为 region 驱动器：采样 eLand 网格(+padding)→HydraulicErosion.apply→dis 派生 riverMask/Width/Wetness→TileLakeSolver.solveTile→返回 2D 数组 Result（取代 RiverNetwork.Seg）。
├── RiverSettings.java    # [MODIFY] 改写 droplet 参数：erosionDropsMul/erosionErodeMul/riverCarveStrength/riverMinDischarge/riverValleyDepth/lakeMinArea/lakeDepthThreshold + enableRivers/enableLakes。
├── RiverNetwork.java     # [DELETE] Seg 线段结构，被 RiverField.Result 的 2D 数组取代。
└── RiverSample.java      # [KEEP] 运行期采样载体，暂未使用。

forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/worldgen/terrain/
├── GeoGenesisTerrain.java # [MODIFY] rasterizeRiverNetworkInto 改为采样 RiverField.Result 2D 数组填 Cell 展示字段（删线段栅格化）；预览路径(getRegionCells)把 eCarved 喂给 cell.height 看真实河谷；RegionRiverArrays 改存新数组。
├── Cell.java              # [MODIFY] 注释改 erosion 语义（riverNet* 由 dis 流量场派生）；字段结构可保留。
├── CellGenerator.java     # [MODIFY] 加 public heightFromELand(double e)（委托 heightCurve.height）；注释更新（河网由 HydraulicErosion 刻蚀产出）；populate/heightAt 保持未刻蚀。
└── TerrainParams.java    # [KEEP] 不变。

forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/client/preview/
├── GeoPalette.java        # [MODIFY] PreviewLayer 枚举加 RIVER_NETWORK（Group.WATER）；color() switch 加 case：按 riverNetDischarge(log1p 归一) 上色，流量大色深。
└── TerrainPreview.java    # [KEEP] 自动经 values().length 遍历、getRegionCells 取 Cell；加枚举即显示新图层。

forge-1.20.1-47.4.10-mdk/src/main/java/com/geogenesis/
├── config/GeoGenesisConfig.java            # [MODIFY, 阶段2] COMMON 暴露 RiverSettings droplet 字段（"River Network" 段）。
└── worldgen/generator/GeoGenesisGenerator.java # [MODIFY, 阶段2] 放水逻辑：riverMask(布尔) 照旧；riverWetness 平滑河湖边缘（沿用）。
```

## 关键代码结构（接口级）

```java
// droplet 水力侵蚀：确定性，零 MC 依赖；输入归一化 eLand 网格，输出侵蚀后 eLand + dis 流量场
public final class HydraulicErosion {
    /** 在 h[0..sz-1][0..sz-1]（归一化 [0,1]，海洋=NaN）上原地跑水滴侵蚀，
     *  累计 dis 流量场（同尺寸），并按 log1p(dis) 把树枝状河谷刻入 h。
     *  ox/oz 为世界对齐原点（padding 边界），用于确定性种子与无缝。 */
    public void apply(float[][] h, float[][] dis, int sz, int ox, int oz,
                     float seaNorm, float str, int erosionDropsMul, float erodeMul);
}

// RiverField 计算结果：region 级 2D 数组（cells×cells），直接喂 Cell 展示字段与预览高度
public final class RiverField {
    public static final class Result {
        public final float[][] eCarved;     // 侵蚀后 eLand（真实河谷）
        public final float[][] dis;         // 流量场（→ riverMask/Width/Discharge）
        public final boolean[][] riverMask;
        public final float[][] riverWidth;   // 河宽代理（∝ √dis）
        public final float[][] riverWetness;
        public final boolean[][] lakeMask;
        public final float[][] lakeLevel;    // 湖面 eLand
    }
    public Result computeRegion(int obx, int obz, int sizeBX, int sizeBZ, int pad);
}
```

## Agent Extensions

### SubAgent

- **code-explorer**
- Purpose: 在阶段2（MC 集成）跨文件核对下游契约——`GeoGenesisGenerator` 放水、`GeoGenesisTerrain` 字段拷贝、`LakeGenerator` 协同、`PreviewColor`/`GeoPalette`/`PreviewDisplay`/`TerrainPreview` 对 `riverMask`/`riverWetness`/`lakeMask`/`riverNet*` 的消费点，确认无遗漏且布尔语义保留。
- Expected outcome: 输出所有受影响消费点清单与需同步字段，避免破坏既有放水/预览/RIVER 类型契约。