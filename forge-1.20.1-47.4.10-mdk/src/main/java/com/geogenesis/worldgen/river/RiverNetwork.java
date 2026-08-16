package com.geogenesis.worldgen.river;

import com.geogenesis.worldgen.terrain.HeightCurve;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 确定性河网门面（确定性几何河网 · Phase 1）。
 *
 * <p>对外两个入口：</p>
 * <ul>
 *   <li>{@link #plateFor(double,double)} — 世界坐标 → tile 河板块（LRU 缓存）</li>
 *   <li>{@link #eAt(double,double)} — 跨 tile 低分辨率 e 场查询（D8/Dijkstra 成本场）</li>
 * </ul>
 *
 * <p>河网构建纯函数：只依赖世界坐标 + e 场 + 坐标哈希种子，无 tile 局部状态 →
 * 任意时序构建同一河网（结构性无缝根）。构建输入 = 侵蚀前 e 场（terrainEQuick），
 * 理由见设计文档 §10：侵蚀 delta（±2 块）不改变 Basin 级拓扑。</p>
 */
public final class RiverNetwork {

    private static final Logger LOGGER = LogManager.getLogger("geogenesis/river");

    /** Basin 边长（wu）= 8 chunk，与侵蚀 tile 网格对齐（决策 3 拍板） */
    public static final int BASIN_SIZE = 128;
    /** 缓存条目上限（grids/flowFields——重建成本：每 tile ~100ms 全采样；
     *  ★ 2026-08-16 阶段 A：256 → 4096——支流追踪跨界远（260 步×4wu=1040wu），
     *  探针 24×24 tiles 访问 ~900 tiles，256 上限淘汰 3.5 轮 → 重建 3.5×
     *  → 构建时间 21 分钟（1270s）实锤；4096 × ~14KB/tile ≈ 60MB 可接受） */
    private static final int CACHE_MAX = 4096;
    /** plate 缓存上限（重建贵：mountainPath 100ms+/tile）——大上限避免频繁重建，仍防 OOM */
    private static final int PLATE_CACHE_MAX = 2048;

    private final ESampler sampler;
    private final HeightCurve curve;
    private final int gridSpacing;
    private final double mainWidth;
    private final double mainDepth;
    private final double tribWidthFrac;
    private final double tribDepthFrac;
    /** 水平缩放（块→wu：DW 块语义常量 ÷HS 转 wu；山地河宽度/距离换算用） */
    private final double horizontalScale;
    /** 河网构建区域（16×16 tile = 2048×2048 wu，2026-08-14 预构建用） */
    public static final int TILES = 16;

    private final double minFall;
    /** discharge 场采样（液滴流量累积 → 真物理水流方向；null = 无侵蚀数据，探针/预览） */
    private final ESampler dischargeSampler;

    private final Map<Long, RiverGrid> grids = new ConcurrentHashMap<>();
    private final Map<Long, FlowField> flowFields = new ConcurrentHashMap<>();
    private final Map<Long, RiverPlate> plates = new ConcurrentHashMap<>();
    private final FlowRiverBuilder builder;
    /** 阶段 B：洼地填水湖泊（D8 洼地中心 BFS 盆地 + 溢出口高度） */
    private final LakeBuilder lakes;
    /** 阶段 C：地下水位场（泉眼/暗河下潜段） */
    private final GroundwaterField groundwater;
    /** 世界种子（DW WORLD_SEED：河网每世界不同；seed() 时设置） */
    private volatile long worldSeed = 0;

    /**
     * @param e              e 场采样（wu 语义，游戏内 = CellGenerator::terrainEQuick）
     * @param curve          HeightCurve（e→Y 映射，水面/河床 Y）
     * @param gridSpacingWu  追踪网格间距（wu，决策 4 配置 riverGridSpacing，默认 4）
     * @param mainWidthWu    主河半宽（wu，配置 riverWidth 块 ÷HS）
     * @param mainDepthY     主河深（Y 块，配置 riverDepth）
     * @param tribWidthFrac  支流宽系数（×主河，默认 0.6）
     * @param tribDepthFrac  支流深系数（×主河，默认 0.5）
     * @param minFallY       支流最低抬升量（Y 块，配置 riverMinFall，默认 3）
     * @param discharge      discharge 场采样（可选；null = 无侵蚀数据）
     */
    public RiverNetwork(ESampler e, HeightCurve curve, int gridSpacingWu,
                        double mainWidthWu, double mainDepthY,
                        double tribWidthFrac, double tribDepthFrac,
                        double minFallY, double horizontalScale, ESampler discharge) {
        this.sampler = e;
        this.curve = curve;
        this.gridSpacing = Math.max(1, gridSpacingWu);
        this.mainWidth = mainWidthWu;
        this.mainDepth = mainDepthY;
        this.tribWidthFrac = tribWidthFrac;
        this.tribDepthFrac = tribDepthFrac;
        this.minFall = minFallY;
        this.horizontalScale = horizontalScale > 0.01 ? horizontalScale : 1.0;
        this.dischargeSampler = discharge;
        // ★ 2026-08-16 阶段 A：汇水分析驱动重构——FlowRiverBuilder（源头驱动
        //   D8 追踪 + 树状汇入）替代 RiverBuilder2（几何折线，已废弃）。
        this.builder = new FlowRiverBuilder(this);
        // ★ 阶段 B：湖泊 = D8 洼地中心 BFS 盆地 + 溢出口填水（依赖同一 FlowField）
        this.lakes = new LakeBuilder(this);
        // ★ 阶段 C：地下水位场（纯函数，无状态）
        this.groundwater = new GroundwaterField(this);
        // ★ 2026-08-14 卡死修复（32 轮）：构造器**不做全量预构建**——buildAllTiles() 同步
        //   建 256 tiles（mountainPath 200 步×9 候选×groundYAt + Dijkstra）在 Server thread
        //   首 chunk 时执行 = 数十秒冻结（日志 23:03:15 后零输出、无 [RIVER] terrain init）。
        //   改**懒构建 + 无 prune**（31 轮已移除 prune）→ 首 chunk 只建 3×3 邻域 ~9 tiles，
        //   每 tile ~100ms 秒级完成，不冻结；plate 常驻不再重建。
    }

    // ===== 对外入口 =====

    /** 后台预热周边 REGION 段（2026-08-17 卡死修复）：玩家移动时提前生成主河/支流段，
     *  Server thread 首次 buildPlate 命中缓存 → 保存/退出不冻结。幂等，可频繁调用。 */
    public void warmRegionsAround(double wx, double wz, int radius) {
        builder.warmRegionsAround(wx, wz, radius);
    }

    /** 世界坐标 → tile 河板块（缓存） */
    public RiverPlate plateFor(double wx, double wz) {
        int tx = (int) Math.floor(wx / BASIN_SIZE);
        int tz = (int) Math.floor(wz / BASIN_SIZE);
        return plateForTile(tx, tz);
    }

    /** tile 网格坐标 → 河板块（缓存，构建确定性） */
    public RiverPlate plateForTile(int tileCX, int tileCZ) {
        long key = pack(tileCX, tileCZ);
        RiverPlate plate = plates.get(key);
        if (plate == null) {
            plate = builder.buildPlate(tileCX, tileCZ);
            RiverPlate prev = plates.putIfAbsent(key, plate);
            if (prev != null) plate = prev;
        }
        // ★ 2026-08-14 卡死/OOM 修复（33 轮）：恢复有界缓存——31 轮删 prune 后 plates
        //   常驻无界，玩家移动（视距 32）访问海量 tile → 内存无限增长 → OOM（日志
        //   172 行后停止、无堆栈）。用大上限 PLATE_CACHE_MAX=2048（重建贵 100ms+/tile，
        //   大上限避免频繁重建），超限删 1/8。与 grids 的小上限（重建便宜）区分。
        prunePlates();
        return plate;
    }

    /**
     * ★ 2026-08-15 R11e：失效覆盖该 basin 的 plate 缓存（3×3 邻域）。
     * 链构建是懒触发——plate(3,9) 可能在远处链头(4,12)建 3,9 段**之前**已缓存
     * （缺 3,9 段）→ 新段建立后失效邻域 plate → 下次访问重建收集（链段构建全
     * 缓存，重建便宜）。seed 9999 NULL next: 4,10->3,9 实锤修复。
     */
    public void invalidatePlatesAround(int basinX, int basinZ) {
        for (int pz = basinZ - 1; pz <= basinZ + 1; pz++) {
            for (int px = basinX - 1; px <= basinX + 1; px++) {
                plates.remove(pack(px, pz));
            }
        }
    }

    /** plate 专属 prune（大上限，超限删 1/8——重建贵，避免频繁触发） */
    private void prunePlates() {
        if (plates.size() > PLATE_CACHE_MAX) {
            var it = plates.keySet().iterator();
            int toRemove = Math.max(1, plates.size() / 8);
            for (int i = 0; i < toRemove && it.hasNext(); i++) {
                it.next();
                it.remove();
            }
        }
    }

    /**
     * ★ 2026-08-14 卡死修复：构造时全量预构建河网（16×16 tile）。
     * 曾懒构建——fillFromNoise 每列首访新 tile 触发重型 buildPlate（世界创建时
     * 20 Worker 并发 = 卡死）。预构建一次性完成，之后 plateForTile 全命中缓存。
     */
    public void buildAllTiles() {
        for (int tz = 0; tz < TILES; tz++) {
            for (int tx = 0; tx < TILES; tx++) {
                plateForTile(tx, tz);
            }
        }
    }

    /** 跨 tile 低分辨率 e 场查询（floorDiv 定位 tile，双线性插值） */
    double eAt(double wx, double wz) {
        int tx = Math.floorDiv((int) Math.floor(wx), BASIN_SIZE);
        int tz = Math.floorDiv((int) Math.floor(wz), BASIN_SIZE);
        return gridFor(tx, tz).at(wx, wz);
    }

    /** e 场原始采样（构建时用，不走低分辨率插值） */
    double rawE(double wx, double wz) {
        return sampler.eAt(wx, wz);
    }

    // ===== 纯函数雕刻采样（Phase 2） =====

    /**
     * 纯函数：世界坐标（wu）→ 河流雕刻采样（或 NONE）。
     *
     * <p>只读河网（tile plate 缓存），无任何可变状态 → 同一世界坐标任意线程/chunk
     * 同结果（结构性无缝根）。流程：定位 tile → 遍历 plate 段 → 点-折线最近距离
     * → 距离 &lt; 半宽+岸坡 → RiverSample。</p>
     */
    public RiverSample sampleRiver(double wx, double wz) {
        RiverPlate plate = plateFor(wx, wz);
        double bestD = Double.MAX_VALUE;
        RiverSample best = null;
        for (RiverSegment s : plate.segments()) {
            // 快速剔除：点到段包围盒（外扩半宽）距离下界 > 当前最优 → 跳过
            double w = s.width;
            if (wx < s.minX - w || wx > s.maxX + w
                    || wz < s.minZ - w || wz > s.maxZ + w) {
                continue;
            }
            double[] hit = closestOnPath(s, wx, wz);
            double d = hit[0];
            double wAt = hit[6]; // 节点插值宽度（DW bestProjWidth 同款）
            if (d >= wAt || d >= bestD) continue;   // 河道有效范围 = 插值半宽内
            bestD = d;
            w = wAt;
            double bankBlend = d <= w * 0.5 ? 0
                : Math.min(1.0, (d - w * 0.5) / (w * 0.5));
            // ★ 2026-08-14：移除采样层坡度阻断——路径生成（mountainPath）已地形感知
            //   寻路（绕开陡坡/山脊），阻断只会让河道"凭空消失"（更不自然）。
            // 跌水潭：最近线段为瀑布（水面落差 ≥ minFall）且落点侧（t > 0.5）→ 河床加深
            double bedBase = hit[2];
            if (hit[5] > 0) bedBase -= hit[5];
            // V 形横截面（Farseek outletSlopes / DW depthProfile 语义）：河心最深、
            // 岸坡按 (d/w)² 升回水面；河床噪声（DW RIVER_BED_NOISE）：河心 ±1.2 起伏。
            // ★ 贴地钳制不在此处做——采样层无法预知侵蚀 delta（tile 缓存），假地形
            //   钳制会让河道贴"无侵蚀地面"→ 游戏实际地形（含侵蚀山谷）与河道脱节
            //   （用户："一点都不贴侵蚀后的地形"）。贴地移交 RiverCarver.carve（有
            //   游戏实际列高 cell.height）。此处仅保留相对剖面（探针/预览可视化用）。
            double depth = hit[1] - bedBase;
            double prof = 1.0 - (d / w) * (d / w);
            double bedY = hit[1] - depth * prof;
            double bn = Math.sin(wx * 0.05) * Math.cos(wz * 0.05) * 0.7
                      + Math.sin(wx * 0.11 + wz * 0.07) * 0.5;
            bedY += bn * (1.2 * prof);
            best = new RiverSample(true, hit[1], bedY, bankBlend, d, w, s.width, hit[3], hit[4], s.discharge, s.type);
        }
        // ★ 阶段 C：暗河段（地表覆盖厚 → 水面埋地层下 → 视觉"河消失"；
        //   下游低洼处出露成泉 = "河消失又出现"喀斯特暗河）。只隐藏河道，
        //   湖泊不受影响。
        // ★ 2026-08-17 断河修复：移除 subsurface 掩码——32wu 低通地下水模型在凸坡/山脊上
        //   误判"潜入地下"→ sampleRiver 返回 NONE → 360 段可见断河（avg 6 blocks、
        //   max 30 blocks、plate miss=0 实锤纯 groundwater 造成）。喀斯特暗河功能
        //   保留代码，未来用户需要时加开关即可。
        // ★ 阶段 B：河道未命中 → 湖泊（洼地填水盆地）。
        //   湖样本 = 湖面/湖底 + 湖半径宽（TRIBUTARY 语义：不走主河海洋浅槽）；
        //   RiverCarver 河心逻辑挖湖盆 + 岸坡平滑 → 复用雕刻管线，零改 carver。
        if (best == null) {
            LakeBuilder.Basin b = lakes.sample(wx, wz);
            if (b != null) {
                double depth = Math.max(1.0, b.waterY() - b.bedY());
                return new RiverSample(true, b.waterY(), b.waterY() - depth,
                    0.5, 0, b.radius(), b.radius(), 1, 0, 2, RiverSegmentType.TRIBUTARY);
            }
        }
        return best != null ? best : RiverSample.NONE;
    }

    /** 泉眼判定（地下水出露；探针/预览用） */
    public boolean springAt(double wx, double wz) {
        return groundwater.springAt(wx, wz);
    }

    /** 暗河判定（探针/诊断用） */
    boolean subsurfaceAt(double wx, double wz) {
        return groundwater.isSubsurface(wx, wz);
    }

    /**
     * 点-折线最近命中：{dist, waterY, bedY, flowX, flowZ, plungeDepth, width}。
     * 水面/河床/宽度沿相邻节点线性插值（DW bestProjWidth 同款）；流向 = 最近线段方向。
     * plungeDepth &gt; 0 = 该处为瀑布落点（线段两端水面差 ≥ minFall 且投影 t &gt; 0.5），
     * 跌水潭深度 = min(落差, 主河深) × 0.5（Farseek plungePoolDepth 思想，确定性计算）。
     */
    private double[] closestOnPath(RiverSegment s, double wx, double wz) {
        double bestD = Double.MAX_VALUE;
        double waterY = 0, bedY = 0, fx = 1, fz = 0, plunge = 0, width = s.width;
        java.util.List<RiverNode> p = s.path;
        for (int i = 0; i < p.size() - 1; i++) {
            RiverNode a = p.get(i), b = p.get(i + 1);
            double ax = a.x(), az = a.z();
            double dx = b.x() - ax, dz = b.z() - az;
            double len2 = dx * dx + dz * dz;
            double t = len2 < 1e-12 ? 0
                : Math.max(0, Math.min(1, ((wx - ax) * dx + (wz - az) * dz) / len2));
            double nx = ax + dx * t, nz = az + dz * t;
            double dd = Math.sqrt((wx - nx) * (wx - nx) + (wz - nz) * (wz - nz));
            if (dd < bestD) {
                bestD = dd;
                waterY = a.waterSurfaceY() + (b.waterSurfaceY() - a.waterSurfaceY()) * t;
                bedY = a.bedY() + (b.bedY() - a.bedY()) * t;
                // 节点插值宽度（DW bestProjWidth 同款；无 nodeWidths → 段级恒宽）
                if (s.nodeWidths != null) {
                    width = s.nodeWidths[i] + (s.nodeWidths[i + 1] - s.nodeWidths[i]) * t;
                } else {
                    width = s.width;
                }
                double fl = Math.sqrt(len2);
                fx = fl < 1e-12 ? fx : dx / fl;
                fz = fl < 1e-12 ? fz : dz / fl;
                // 瀑布判定：线段两端水面差（上游 − 下游）≥ minFall，且投影落点偏下游侧
                double fall = a.waterSurfaceY() - b.waterSurfaceY();
                if (t > 0.5 && fall >= minFall) {
                    plunge = Math.min(fall, mainDepth) * 0.5; // 跌水潭 ≤ 主河深一半
                } else {
                    plunge = 0;
                }
            }
        }
        return new double[]{bestD, waterY, bedY, fx, fz, plunge, width};
    }

    // ===== 内部 =====

    HeightCurve curve() { return curve; }

    double seaLevelY() { return curve.seaLevelY(); }

    int gridSpacing() { return gridSpacing; }

    double mainWidth() { return mainWidth; }

    double mainDepth() { return mainDepth; }

    double horizontalScale() { return horizontalScale; }

    double tribWidthFrac() { return tribWidthFrac; }

    double tribDepthFrac() { return tribDepthFrac; }

    double minFall() { return minFall; }

    /** 世界种子（DW WORLD_SEED；世界播种时设置，河网每世界不同） */
    public void setWorldSeed(long seed) { this.worldSeed = seed; }

    long worldSeed() { return worldSeed; }

    /** tile → 低分辨率 e 场（缓存） */
    RiverGrid gridFor(int tileCX, int tileCZ) {
        long key = pack(tileCX, tileCZ);
        RiverGrid g = grids.get(key);
        if (g == null) {
            g = new RiverGrid(tileCX, tileCZ, gridSpacing, sampler);
            RiverGrid prev = grids.putIfAbsent(key, g);
            if (prev != null) g = prev;
        }
        prune(grids);
        return g;
    }

    // ===== 汇水分析驱动（阶段 A）：D8 流向场 =====

    /** tile → D8 流向场（缓存；复用同 tile 的 RiverGrid 格点 e，仅边界采样） */
    FlowField flowFieldFor(int tileCX, int tileCZ) {
        long key = pack(tileCX, tileCZ);
        FlowField f = flowFields.get(key);
        if (f == null) {
            f = FlowField.build(tileCX, tileCZ, gridSpacing, sampler);
            FlowField prev = flowFields.putIfAbsent(key, f);
            if (prev != null) f = prev;
        }
        prune(flowFields);
        return f;
    }

    /** 世界坐标 → 所在 tile 最近格点的 D8 流向（0..7；-1 = 洼地/局部最低）。
     *  tile 边界格点世界坐标共享 → 跨 tile 查询一致，无断裂。 */
    int flowDirAt(double wx, double wz) {
        int tx = Math.floorDiv((int) Math.floor(wx), FlowField.SIZE);
        int tz = Math.floorDiv((int) Math.floor(wz), FlowField.SIZE);
        FlowField f = flowFieldFor(tx, tz);
        return f.dirAt(f.ixOf(wx), f.izOf(wz));
    }

    /** 世界坐标 → 最近格点梯度（每 wu e 降幅；缓坡蛇曲/密度判定用） */
    double flowGradAt(double wx, double wz) {
        int tx = Math.floorDiv((int) Math.floor(wx), FlowField.SIZE);
        int tz = Math.floorDiv((int) Math.floor(wz), FlowField.SIZE);
        FlowField f = flowFieldFor(tx, tz);
        return f.gradAt(f.ixOf(wx), f.izOf(wz));
    }

    // ===== 阶段 B：湖泊 =====

    /** tile → 湖盆地列表（探针/诊断用；采样内部走 sampleRiver 叠加） */
    List<LakeBuilder.Basin> lakesFor(int tx, int tz) {
        return lakes.lakesFor(tx, tz);
    }

    /** 世界坐标是否在湖泊盆地内（CellGenerator 湖标记/群系用） */
    public boolean isLakeAt(double wx, double wz) {
        return lakes.sample(wx, wz) != null;
    }

    static long pack(int cx, int cz) {
        return ((long) cx << 32) | (cz & 0xFFFFFFFFL);
    }

    private static void prune(Map<?, ?> map) {
        if (map.size() > CACHE_MAX) {
            var it = map.keySet().iterator();
            int toRemove = Math.max(1, map.size() / 8);
            for (int i = 0; i < toRemove && it.hasNext(); i++) {
                it.next();
                it.remove();
            }
        }
    }

    /** 诊断：缓存大小（探针用） */
    public int gridCacheSize() { return grids.size(); }

    /** 诊断：主河段查询（探针用） */
    public RiverSegment mainSegmentAt(int bx, int bz) {
        return builder.mainSegments().get(RiverNetwork.pack(bx, bz));
    }
}
