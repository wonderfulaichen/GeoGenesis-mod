package com.geogenesis.worldgen.terrain;

import com.geogenesis.worldgen.noise.*;

/**
 * Voronoi 地形区域系统 —— 域扭曲空间上的距离场（彻底消除网格感）。
 * <p>
 * v4 关键修复：消除"细胞状规则亮点"。
 * v5：SEARCH_RADIUS + typeWeights 连续权重。
 * v5.2：SIGMA=CELL_SPACING/4=100。
 * v6.1：SIGMA=150 修复 v6.0 per-type eLand 加权后 cell 边界 typeWeights 跳变。
 * <p>
 * <b>v6.1 SIGMA=150 变更理由</b>：v6.0 用 per-type eLand 加权混合（TypeLandShape.sample），
 * typeWeights 锐利变化（最近 cell 主导 99.7%）会直接放大为 eLand 跳变（17 块）。
 * SIGMA=150 让 200 块距离的 cell 权重从 0.135 升到 0.26，边界过渡更平滑。
 */
public final class VoronoiRegionField {

    // ===== 细胞网格参数 =====
    /** Cell 间距（块）。TerraForged 默认 400 */
    public static final double CELL_SPACING = 400.0;
    /** 邻近搜索 cell 半径。v6.2: 1→2，5×5=25 cells 让 typeWeights 在 cell 边界更平滑 */
    private static final int SEARCH_RADIUS = 2;
    /** 距离场域扭曲幅度（块）。> CELL_SPACING/2 才能打散网格感。Phase 2.2：改为实例字段，由 TerrainParams.voronoiWarpAmp 注入（默认 250.0） */
    private final double WARP_AMP;
    /**
     * 高斯权重 σ（块）。v6.1: 100→150，让 cell 边界 typeWeights 过渡更平滑：
     * <ul>
     *   <li>cell 中心：主导类型权重 ≈ 80%（邻居 20% 共担）→ 仍清晰但不过锐利</li>
     *   <li>cell 边界（200 块距离）：最近两个 cell 各 ~30% → 平滑过渡</li>
     *   <li>3×3 窗口边缘（400 块距离）：exp(-7.1)=0.0008 → 可忽略</li>
     * </ul>
     */
    private static final double SIGMA = 150.0;

    // ===== 地形类型阈值（按 typeField 值划分） =====
    // v7.9 (Fix): 重新平衡各类型带宽（HILLS 从 37%→20% 抑制过度主导）。
    // 带宽分布：PLAIN 35% → HILLS 20% → MOUNTAINS 23% → PLATEAU 17% → BASIN 5%
    // 经地理约束 + c-affinity 后，预期最终：PLAIN~28%, HILLS~35%, MOUNTAINS~17%,
    // PLATEAU~12%, BASIN~3%, 其余为 PEAK/SNOW/BEACH。
    private static final double TYPE_THRESH_PLAIN     = 0.35;
    private static final double TYPE_THRESH_HILLS     = 0.55;
    private static final double TYPE_THRESH_MOUNTAINS = 0.78;
    private static final double TYPE_THRESH_PLATEAU   = 0.95;
    // 旧 hash-based type（保留作为后备，但实际用 typeField）
    private static final int[] TYPE_WEIGHTS = {30, 25, 20, 15, 10};
    private static final TerrainClass[] TYPE_BY_WEIGHT = buildTypeByWeight();

    private static TerrainClass[] buildTypeByWeight() {
        TerrainClass[] types = {
            TerrainClass.PLAIN, TerrainClass.HILLS,
            TerrainClass.MOUNTAINS, TerrainClass.PLATEAU,
            TerrainClass.BASIN
        };
        int total = 0;
        for (int w : TYPE_WEIGHTS) total += w;
        TerrainClass[] map = new TerrainClass[total];
        int idx = 0;
        for (int i = 0; i < types.length; i++) {
            for (int w = 0; w < TYPE_WEIGHTS[i]; w++) {
                map[idx++] = types[i];
            }
        }
        return map;
    }

    // ===== 混合结果 =====
    public static final class BlendResult {
        public double lo;
        public double hi;
        public TerrainClass dominantType;
        public double alpha; // [0,1] 主导类型权重
        /** 连续权重（按 TerrainClass.ordinal() 索引，和=1），消除离散突变 */
        public double[] typeWeights;
    }

    // ===== 状态 =====
    private long worldSeed;
    private final Noise warpX, warpZ; // 域扭曲噪声
    // v6.5: typeField — 低频噪声决定 cell type（取代 hash），让 typeWeights 在空间平滑
    private final Noise typeField;

    public VoronoiRegionField(double warpAmp) {
        this.WARP_AMP = warpAmp;
        // 双频 warp（仿海岸线 FBM）：主频 1/800 + 次频 1/260（权重 0.4），归一化到 [-1,1]，
        // 让 Voronoi 细胞边界像海岸线一样多尺度蜿蜒（而非直 Voronoi 边），类型过渡更自然。
        Noise wX1 = new Frequency(new Simplex(310), 1.0 / 800.0);
        Noise wX2 = new Frequency(new Simplex(5310), 1.0 / 260.0);
        this.warpX = new Map(new Add(wX1, new Boost(wX2, 0.4)), -1.4, 1.4, -1.0, 1.0);
        Noise wZ1 = new Frequency(new Simplex(311), 1.0 / 800.0);
        Noise wZ2 = new Frequency(new Simplex(5311), 1.0 / 260.0);
        this.warpZ = new Map(new Add(wZ1, new Boost(wZ2, 0.4)), -1.4, 1.4, -1.0, 1.0);
        // v7.5: typeField 频率 1/500，兼顾平滑过渡 + 合理采样窗口内出现所有类型
        // 原 1/1500 导致 800×800 窗口内只有~2 个 cell，typeField 几乎恒定→无 PLATEAU
        this.typeField = new Map(new Frequency(new Simplex(312), 1.0 / 500.0), -1.0, 1.0, 0.0, 1.0);
    }

    public void seed(long worldSeed) {
        this.worldSeed = worldSeed;
        Noises.seedAll(warpX, worldSeed, 0);
        Noises.seedAll(warpZ, worldSeed, 0);
        Noises.seedAll(typeField, worldSeed, 0);
    }

    // ===== 公开 API =====

    /** 采样混合结果 */
    public BlendResult sampleBlend(double wx, double wz) {
        // 1. 域扭曲
        double wxw = wx + WARP_AMP * warpX.compute(wx, wz);
        double wzw = wz + WARP_AMP * warpZ.compute(wx, wz);

        // 2. 收集 3×3 邻近 cell
        double gx = wxw / CELL_SPACING;
        double gz = wzw / CELL_SPACING;
        int baseCx = (int) Math.floor(gx);
        int baseCz = (int) Math.floor(gz);

        final int N = (2 * SEARCH_RADIUS + 1) * (2 * SEARCH_RADIUS + 1);
        CellInfo[] candidates = new CellInfo[N];
        int idx = 0;
        for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
            for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
                int nc = baseCx + dx;
                int nz = baseCz + dz;
                double cx = cellCenterX(nc, nz);
                double cz = cellCenterZ(nc, nz);
                double dsq = (wxw - cx) * (wxw - cx) + (wzw - cz) * (wzw - cz);
                candidates[idx++] = new CellInfo(nc, nz, dsq);
            }
        }

        // 3. 高斯权重 + 类型权重累积
        final double inv2Sigma2 = 1.0 / (2.0 * SIGMA * SIGMA);
        double totalWeight = 0;
        int bestIdx = 0;
        double bestDist = candidates[0].distSq;
        double[] twSum = new double[TerrainClass.COUNT];

        for (int i = 0; i < N; i++) {
            CellInfo ci = candidates[i];
            double w = Math.exp(-ci.distSq * inv2Sigma2);
            TerrainClass tc = cellType(ci.cx, ci.cz);
            twSum[tc.ordinal()] += w;
            totalWeight += w;
            if (ci.distSq < bestDist) {
                bestDist = ci.distSq;
                bestIdx = i;
            }
        }

        BlendResult result = new BlendResult();
        // 注：blend.lo/hi 不再计算（类型高度范围统一由 SplineConfig 派生，见 TypeLandShape）。
        // 保留字段仅供 DiscontinuityProbe 诊断兼容，恒为 0.0。
        result.dominantType = cellType(candidates[bestIdx].cx, candidates[bestIdx].cz);

        // alpha：最近 cell 权重占比（找真正的 2 个最近 cell）
        double secondBestDist = Double.MAX_VALUE;
        for (int i = 0; i < N; i++) {
            double d = candidates[i].distSq;
            if (d != bestDist && d < secondBestDist) secondBestDist = d;
        }
        double bestW = Math.exp(-bestDist * inv2Sigma2);
        double secondW = Math.exp(-secondBestDist * inv2Sigma2);
        result.alpha = (bestW + secondW) > 0 ? bestW / (bestW + secondW) : 0.5;

        // 归一化类型权重
        result.typeWeights = new double[TerrainClass.COUNT];
        if (totalWeight > 0) {
            for (int t = 0; t < TerrainClass.COUNT; t++) {
                result.typeWeights[t] = twSum[t] / totalWeight;
            }
        } else {
            result.typeWeights[result.dominantType.ordinal()] = 1.0;
        }

        return result;
    }

    /** 主导类型：最近细胞类型 */
    public TerrainClass dominantType(double wx, double wz) {
        double wxw = wx + WARP_AMP * warpX.compute(wx, wz);
        double wzw = wz + WARP_AMP * warpZ.compute(wx, wz);
        double gx = wxw / CELL_SPACING;
        double gz = wzw / CELL_SPACING;
        int baseCx = (int) Math.floor(gx);
        int baseCz = (int) Math.floor(gz);

        int bestCx = baseCx, bestCz = baseCz;
        double bestD = Double.MAX_VALUE;
        for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
            for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
                int nc = baseCx + dx;
                int nz = baseCz + dz;
                double cx = cellCenterX(nc, nz);
                double cz = cellCenterZ(nc, nz);
                double dsq = (wxw - cx) * (wxw - cx) + (wzw - cz) * (wzw - cz);
                if (dsq < bestD) {
                    bestD = dsq;
                    bestCx = nc;
                    bestCz = nz;
                }
            }
        }
        return cellType(bestCx, bestCz);
    }

    // ===== 内部 =====

    private double cellCenterX(int cx, int cz) {
        long h = hash(cx, cz);
        double jitter = ((h & 0xFFFF) / 65536.0 - 0.5) * 0.45;
        return (cx + jitter) * CELL_SPACING;
    }

    private double cellCenterZ(int cx, int cz) {
        long h = hash(cx, cz);
        double jitter = (((h >> 16) & 0xFFFF) / 65536.0 - 0.5) * 0.45;
        return (cz + jitter) * CELL_SPACING;
    }

    private TerrainClass cellType(int cx, int cz) {
        // v7 (Phase 2): 采样低频 typeField 于 cell 中心 → 空间聚集的类型分布
        double centerX = cellCenterX(cx, cz);
        double centerZ = cellCenterZ(cx, cz);
        double v = typeField.compute(centerX, centerZ); // ∈ [0,1]
        TerrainClass tc;
        if (v < TYPE_THRESH_PLAIN)         tc = TerrainClass.PLAIN;
        else if (v < TYPE_THRESH_HILLS)    tc = TerrainClass.HILLS;
        else if (v < TYPE_THRESH_MOUNTAINS) tc = TerrainClass.MOUNTAINS;
        else if (v < TYPE_THRESH_PLATEAU)  tc = TerrainClass.PLATEAU;
        else                               tc = TerrainClass.BASIN;

        // 高原地理约束：周围无高地（HILLS/MOUNTAINS/PLATEAU）→ 降级为 HILLS
        // v7.5(Fix3) 原仅要求邻 MOUNTAINS，导致高原永远被更高雪山环抱呈火山口伪形；
        // 放宽到邻丘陵即可，使高原能作为独立桌山矗立丘陵之上、向四周陡降。仍禁止"平原直跳高原"。
        if (tc == TerrainClass.PLATEAU && !hasHighlandNeighbor(cx, cz)) {
            tc = TerrainClass.HILLS;
        }

        // v7.8: MOUNTAINS 地理约束 — 与 PLAIN 相邻时必须有 HILLS 缓冲
        // 防止"山脉直接挨着平原"的不合理地理序列（跳过丘陵过渡）
        if (tc == TerrainClass.MOUNTAINS && hasPlainNeighbor(cx, cz) && !hasHillsNeighbor(cx, cz)) {
            tc = TerrainClass.HILLS;
        }
        return tc;
    }

    /**
     * 检查 3×3 邻居窗口中是否有高地细胞（HILLS / MOUNTAINS / PLATEAU）。
     * 用于 PLATEAU 地理约束：只在高地附近生成 PLATEAU（禁止"平原直跳高原"），
     * 但允许邻丘陵，使高原可作为独立桌山矗立于丘陵之上（修复火山口伪形）。
     */
    private boolean hasHighlandNeighbor(int cx, int cz) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                double nx = cellCenterX(cx + dx, cz + dz);
                double nz = cellCenterZ(cx + dx, cz + dz);
                double nv = typeField.compute(nx, nz);
                // 邻居是高于平原的高地?（v >= THRESH_PLAIN）
                if (nv >= TYPE_THRESH_PLAIN) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * v7.8: 检查 3×3 邻居窗口中是否有 HILLS 细胞。
     * 用于 MOUNTAINS 地理约束：MOUNTAINS 与 PLAIN 相邻时必须有 HILLS 缓冲。
     */
    private boolean hasHillsNeighbor(int cx, int cz) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                double nx = cellCenterX(cx + dx, cz + dz);
                double nz = cellCenterZ(cx + dx, cz + dz);
                double nv = typeField.compute(nx, nz);
                // 邻居是 HILLS?（v ∈ [THRESH_PLAIN, THRESH_HILLS)）
                if (nv >= TYPE_THRESH_PLAIN && nv < TYPE_THRESH_HILLS) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * v7.8: 检查 3×3 邻居窗口中是否有 PLAIN 细胞。
     * 用于 MOUNTAINS 地理约束。
     */
    private boolean hasPlainNeighbor(int cx, int cz) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                double nx = cellCenterX(cx + dx, cz + dz);
                double nz = cellCenterZ(cx + dx, cz + dz);
                double nv = typeField.compute(nx, nz);
                // 邻居是 PLAIN?（v < THRESH_PLAIN）
                if (nv < TYPE_THRESH_PLAIN) {
                    return true;
                }
            }
        }
        return false;
    }

    private long hash(int cx, int cz) {
        long h = cx * 341873128712L + cz * 132897987541L + worldSeed;
        h ^= h >> 16;
        h *= 0x85ebca6bL;
        h ^= h >> 13;
        h *= 0xc2b2ae35L;
        h ^= h >> 16;
        return h;
    }

    // ===== 内部数据结构 =====
    private static final class CellInfo {
        final int cx, cz;
        final double distSq;
        CellInfo(int cx, int cz, double distSq) {
            this.cx = cx;
            this.cz = cz;
            this.distSq = distSq;
        }
    }
}
