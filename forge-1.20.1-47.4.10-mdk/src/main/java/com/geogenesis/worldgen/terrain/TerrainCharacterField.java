package com.geogenesis.worldgen.terrain;

import com.geogenesis.worldgen.noise.*;

/**
 * Voronoi 高斯距离权重地形类型场。
 * <p>
 * 核心设计：
 * <ul>
 *   <li>400 块间距的稀疏网格，每格点哈希独立分配 5 种陆地类型之一</li>
 *   <li>任意类型可邻接任意类型（BASIN 可紧邻 PLATEAU / MOUNTAINS）</li>
 *   <li>最近格点高斯距离权重主导（σ=200），类型边界平滑过渡</li>
 *   <li>7×7 搜索窗口（SEARCH_RADIUS=3）：进出格点距离≥1000 → 权重自然衰减到 3.7e-6，零窗口进出跳变</li>
 *   <li>域扭曲打散网格规则感（WARP_AMP=0，保留字段可恢复）</li>
 * </ul>
 */
public final class TerrainCharacterField {

    /** 网格间距（块单位），400 = 25 chunks，细胞区域感鲜明 */
    private static final int CELL_SPACING = 400;

    /**
     * 搜索半径：3 → 7×7 搜索窗口。
     * <p>
     * 2026-08-03 修复：原 3×3 窗口 + SIGMA=200 时，窗口进出的是 ring 1 格点
     * （跨边界瞬间距离 = 1.5×SPACING = 600 块 → 权重 exp(-4.5)≈0.011 不可忽略），
     * 移出/移入格点类型不同 → typeWeights 在 1 格内突变 ~0.022 → argmax 临界处
     * 翻转 dominantType → eLand 突变 → 1 格断裂线（CellBoundaryProbe 实测 @X=400/800）。
     * <p>
     * 方案：窗口扩为 7×7（SEARCH_RADIUS=3），窗口进出格点距离 ≥2.5×SPACING=1000 块
     * → 权重 exp(-1000²/80000)≈3.7e-6 → 进出跳变 <0.0015 块，不可见。
     * 注意：不能使用「边缘硬压制 ×1e-7」（v5 σ=150 时代的方案）——σ=200 下 ring 1
     * 边界权重 0.011 太大，同一格点从 ring 1 滑到 ring 2 时权重骤降 1e7 倍反而制造新跳变。
     */
    private static final int SEARCH_RADIUS = 3;

    /** 高斯 σ：150→200（2026-08-02：类型过渡带拉宽，配合 WARP 缩小 → 边缘悬崖坡度下降） */
    private static final double SIGMA = 200.0;
    private static final double INV_2SIGMA2 = 1.0 / (2.0 * SIGMA * SIGMA);

    /** 5 种陆地类型的 ordinal 映射（顺序与 TypeNoiseProvider.LAND_TYPES 无关） */
    private static final int[] LAND_ORDINALS;

    static {
        LAND_ORDINALS = new int[]{
            TerrainClass.BASIN.ordinal(),
            TerrainClass.PLAIN.ordinal(),
            TerrainClass.HILLS.ordinal(),
            TerrainClass.PLATEAU.ordinal(),
            TerrainClass.MOUNTAINS.ordinal()
        };
    }

    // ===== 域扭曲（打散网格规则感） =====
    private final Noise warpX, warpZ;
    // 2026-08-03：80→0（用户实测确认——类型权重查格点 ±80 块平移让主导沿细胞边界跳跃，
    // 产生 1 格宽断裂伪影；纯噪声 + 侵蚀已足够丰富，warp 纯属冗余。保留字段供未来恢复）。
    private static final double WARP_AMP = 0.0;
    private static final double WARP_FREQ = 1.0 / 500.0;

    // ===== 混合结果 =====
    public static final class BlendResult {
        public double lo;             // 不再使用，恒 0.0
        public double hi;             // 不再使用，恒 0.0
        public TerrainClass dominantType;
        public double alpha;          // 主导类型归一化权重
        public double[] typeWeights;  // [TerrainClass.COUNT]，仅陆地类型非零
    }

    public TerrainCharacterField() {
        Noise wX = new Frequency(new Simplex(310), WARP_FREQ);
        this.warpX = new Map(wX, -1.0, 1.0, -1.0, 1.0);
        Noise wZ = new Frequency(new Simplex(311), WARP_FREQ);
        this.warpZ = new Map(wZ, -1.0, 1.0, -1.0, 1.0);
    }

    public void seed(long worldSeed) {
        Noises.seedAll(warpX, worldSeed, 0);
        Noises.seedAll(warpZ, worldSeed, 0);
    }

    // ===== 公开 API =====

    /**
     * Voronoi 高斯距离权重采样。
     * <ol>
     *   <li>域扭曲打散网格对齐</li>
     *   <li>5×5 搜索窗口，每格点按距查询点的高斯距离贡献类型权重</li>
     *   <li>归一化 → 连续 typeWeights</li>
     *   <li>边缘格点权重 ×1e-7，消除窗口进出跳变</li>
     * </ol>
     */
    public BlendResult sampleBlend(double wx, double wz) {
        // 1. 域扭曲
        double wxw = wx + WARP_AMP * warpX.compute(wx, wz);
        double wzw = wz + WARP_AMP * warpZ.compute(wx, wz);

        // 2. 查询点所在基格
        int baseX = floorToInt(wxw / CELL_SPACING);
        int baseZ = floorToInt(wzw / CELL_SPACING);

        double[] weights = new double[TerrainClass.COUNT];
        double sum = 0;
        int bestOrd = LAND_ORDINALS[0];
        double bestW = 0;
        final double invSpacing = 1.0 / CELL_SPACING;

        // 3. 5×5 搜索窗口（ring 2 格点 ×EDGE_WEIGHT，进出无跳变）
        for (int dx = -SEARCH_RADIUS; dx <= SEARCH_RADIUS; dx++) {
            for (int dz = -SEARCH_RADIUS; dz <= SEARCH_RADIUS; dz++) {
                int cx = baseX + dx;
                int cz = baseZ + dz;

                // 格点中心 = (cx + 0.5) * CELL_SPACING
                double centerX = (cx + 0.5) * CELL_SPACING;
                double centerZ = (cz + 0.5) * CELL_SPACING;

                double dx2 = wxw - centerX;
                double dz2 = wzw - centerZ;
                double dist2 = dx2 * dx2 + dz2 * dz2;
                double gaussian = Math.exp(-dist2 * INV_2SIGMA2);

                int ord = getCellType(cx, cz);
                weights[ord] += gaussian;
                sum += gaussian;
                if (gaussian > bestW) {
                    bestW = gaussian;
                    bestOrd = ord;
                }
            }
        }

        // 4. 归一化
        if (sum > 1e-15) {
            double invSum = 1.0 / sum;
            for (int i = 0; i < TerrainClass.COUNT; i++) {
                weights[i] *= invSum;
            }
            bestW = weights[bestOrd];
        } else {
            weights[bestOrd] = 1.0;
            bestW = 1.0;
        }

        BlendResult result = new BlendResult();
        result.typeWeights = weights;
        result.dominantType = TerrainClass.values()[bestOrd];
        result.alpha = bestW;
        result.lo = 0.0;
        result.hi = 0.0;
        return result;
    }

    /** 快捷获取主导类型 */
    public TerrainClass dominantType(double wx, double wz) {
        return sampleBlend(wx, wz).dominantType;
    }

    // ===== 内部工具 =====

    private static int floorToInt(double v) {
        int i = (int) v;
        return v < i ? i - 1 : i;
    }

    /**
     * 确定性哈希：(cx, cz) → 5 种陆地类型之一。
     * 64 位混合确保均匀分布。
     */
    private static int getCellType(int cx, int cz) {
        long h = (long) cx * 374761393L + (long) cz * 668265263L;
        h = h * 1274126177L ^ (h >>> 16);
        h = h * 709369L ^ (h >>> 13);
        h ^= (h >>> 16);
        int idx = (int) ((h & Long.MAX_VALUE) % LAND_ORDINALS.length);
        return LAND_ORDINALS[idx];
    }
}
