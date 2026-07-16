package com.geogenesis.worldgen.terrain;

import com.geogenesis.worldgen.noise.*;

/**
 * Voronoi 地形区域系统 —— 域扭曲空间上的距离场（彻底消除网格感）。
 * <p>
 * v4 关键修复：消除"细胞状规则亮点"。
 * v5：SEARCH_RADIUS + typeWeights 连续权重。
 * v5.2：SIGMA=CELL_SPACING/4=100 取代 CELL_SPACING/2=200。
 * <p>
 * <b>sigma 变更理由（v5.2）</b>：旧 SIGMA=200 导致 cell 中心邻居合计权重 52%，
 * 类型噪声被严重稀释为"五不像的大杂烩"。SIGMA=100 后 cell 中心主导类型
 * 权重 99.7%，噪声纯度高。配合 3×3 搜索窗口（边缘 cell 权重 0.0003），
 * 搜索窗口边界切换无跳变（前一轮的 SEARCH_RADIUS=2 可以降回 1）。
 */
public final class VoronoiRegionField {

    // ===== 细胞网格参数 =====
    /** Cell 间距（块）。TerraForged 默认 400 */
    public static final double CELL_SPACING = 400.0;
    /** 邻近搜索 cell 半径（1 = 3×3 = 9 cells）。SIGMA=100 使边缘 cell 权重 exp(-8)=0.0003，可忽略 */
    private static final int SEARCH_RADIUS = 1;
    /** 距离场域扭曲幅度（块）。> CELL_SPACING/2 才能打散网格感 */
    private static final double WARP_AMP = 250.0;
    /**
     * 高斯权重 σ（块）。CELL_SPACING/4 = 100：
     * <ul>
     *   <li>cell 中心：主导类型权重 99.7%（邻居 0.3%）→ 干净的类型区域</li>
     *   <li>cell 边界：2 个最近 cell 各 50% → 平滑过渡</li>
     *   <li>3×3 窗口边缘（距中心 ~400 块）：exp(-8)=0.0003 → 可忽略</li>
     * </ul>
     */
    private static final double SIGMA = 100.0;

    // ===== 地形类型权重分布（合计 100） =====
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

    public VoronoiRegionField() {
        this.warpX = new Frequency(new Simplex(310), 1.0 / 800.0);
        this.warpZ = new Frequency(new Simplex(311), 1.0 / 800.0);
    }

    public void seed(long worldSeed) {
        this.worldSeed = worldSeed;
        Noises.seedAll(warpX, worldSeed, 0);
        Noises.seedAll(warpZ, worldSeed, 0);
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
            weightedLo += w * TypeGenerators.getTypeLo(tc);
            weightedHi += w * TypeGenerators.getTypeHi(tc);
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
        long h = hash(cx, cz);
        int idx = (int) ((h >> 32) & 0x7FFFFFFF);
        idx = Math.floorMod(idx, TYPE_BY_WEIGHT.length);
        return TYPE_BY_WEIGHT[idx];
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
