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
    /** 距离场域扭曲幅度（块）。> CELL_SPACING/2 才能打散网格感 */
    private static final double WARP_AMP = 250.0;
    /**
     * 高斯权重 σ（块）。v6.1: 100→150，让 cell 边界 typeWeights 过渡更平滑：
     * <ul>
     *   <li>cell 中心：主导类型权重 ≈ 80%（邻居 20% 共担）→ 仍清晰但不过锐利</li>
     *   <li>cell 边界（200 块距离）：最近两个 cell 各 ~30% → 平滑过渡</li>
     *   <li>3×3 窗口边缘（400 块距离）：exp(-7.1)=0.0008 → 可忽略</li>
     * </ul>
     */
    private static final double SIGMA = 100.0;

    // ===== 地形类型阈值（按 typeField 值划分） =====
    // v7.8 (Fix): HILLS 带宽从 0.25→0.37 (+48%)，降低相邻 cell 跳过 HILLS 带的风险。
    // 同时 MOUNTAINS→PLAIN 直接相邻时 MOUNTAINS 降级为 HILLS（见地理约束）。
    // 旧：PLAIN<0.35, HILLS<0.60, MOUNTAINS<0.75, PLATEAU<0.90, BASIN>=0.90
    // 新：PLAIN<0.28, HILLS<0.65, MOUNTAINS<0.78, PLATEAU<0.92, BASIN>=0.92
    // 预期最终分类：PLAIN~17%, HILLS~37%, MOUNTAINS~16%, PLATEAU~13%, BASIN~5%
    private static final double TYPE_THRESH_PLAIN     = 0.28;
    private static final double TYPE_THRESH_HILLS     = 0.65;
    private static final double TYPE_THRESH_MOUNTAINS = 0.78;
    private static final double TYPE_THRESH_PLATEAU   = 0.92;
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
    private final TypeGenerators generators; // 类型高度范围（非静态）
    private final Noise warpX, warpZ; // 域扭曲噪声
    // v6.5: typeField — 低频噪声决定 cell type（取代 hash），让 typeWeights 在空间平滑
    private final Noise typeField;

    public VoronoiRegionField(TypeGenerators generators) {
        this.generators = generators;
        this.warpX = new Frequency(new Simplex(310), 1.0 / 800.0);
        this.warpZ = new Frequency(new Simplex(311), 1.0 / 800.0);
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
        double weightedLo = 0;
        double weightedHi = 0;
        int bestIdx = 0;
        double bestDist = candidates[0].distSq;
        double[] twSum = new double[TerrainClass.COUNT];

        for (int i = 0; i < N; i++) {
            CellInfo ci = candidates[i];
            double w = Math.exp(-ci.distSq * inv2Sigma2);
            TerrainClass tc = cellType(ci.cx, ci.cz);
            weightedLo += w * generators.getTypeLo(tc);
            weightedHi += w * generators.getTypeHi(tc);
            twSum[tc.ordinal()] += w;
            totalWeight += w;
            if (ci.distSq < bestDist) {
                bestDist = ci.distSq;
                bestIdx = i;
            }
        }

        BlendResult result = new BlendResult();
        result.lo = weightedLo / totalWeight;
        result.hi = weightedHi / totalWeight;
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

        // v7.5 (Fix 3): PLATEAU 地理约束 — 周围无 MOUNTAINS → 降级为 HILLS
        // 防止"平原直跳高原"的不合理地理序列
        if (tc == TerrainClass.PLATEAU && !hasMountainNeighbor(cx, cz)) {
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
     * 检查 3×3 邻居窗口中是否有 MOUNTAINS 细胞。
     * 用于 PLATEAU 地理约束：只在 MOUNTAINS 附近生成 PLATEAU。
     * v7.8: 修复阈值范围——MOUNTAINS 在 [THRESH_HILLS, THRESH_MOUNTAINS)，旧版误用了 PLATEAU 范围。
     */
    private boolean hasMountainNeighbor(int cx, int cz) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                double nx = cellCenterX(cx + dx, cz + dz);
                double nz = cellCenterZ(cx + dx, cz + dz);
                double nv = typeField.compute(nx, nz);
                // 邻居是 MOUNTAINS?（v ∈ [THRESH_HILLS, THRESH_MOUNTAINS)）
                if (nv >= TYPE_THRESH_HILLS && nv < TYPE_THRESH_MOUNTAINS) {
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
