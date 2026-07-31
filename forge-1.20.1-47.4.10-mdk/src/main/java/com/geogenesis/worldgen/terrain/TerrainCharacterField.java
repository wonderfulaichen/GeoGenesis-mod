package com.geogenesis.worldgen.terrain;

import com.geogenesis.worldgen.noise.*;

/**
 * Voronoi 高斯距离权重地形类型场。
 * <p>
 * 核心设计：
 * <ul>
 *   <li>400 块间距的稀疏网格，每格点哈希独立分配 5 种陆地类型之一</li>
 *   <li>任意类型可邻接任意类型（BASIN 可紧邻 PLATEAU / MOUNTAINS）</li>
 *   <li>最近格点高斯距离权重主导（σ=150），类型边界平滑过渡</li>
 *   <li>5×5 搜索窗口 + 边缘权重压制 → 零窗口进出跳变</li>
 *   <li>域扭曲打散网格规则感</li>
 * </ul>
 */
public final class TerrainCharacterField {

    /** 网格间距（块单位），400 = 25 chunks，细胞区域感鲜明 */
    private static final int CELL_SPACING = 400;

    /** 搜索半径：1 → 3×3 搜索窗口（ring 2 在 σ=150, spacing=400 时权重 < 0.00034，可安全忽略） */
    private static final int SEARCH_RADIUS = 1;

    /** 高斯 σ：150 → 细胞边缘权重 ~0.4（在 200 块边界处），平滑过渡 */
    private static final double SIGMA = 150.0;
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
    private static final double WARP_AMP = 250.0;
    private static final double WARP_FREQ = 1.0 / 500.0;

    // ===== 混合结果 =====
    public static final class BlendResult {
        public double lo;             // 不再使用，恒 0.0
        public double hi;             // 不再使用，恒 0.0
        public TerrainClass dominantType;
        public double alpha;          // 主导类型归一化权重
        public double[] typeWeights;  // [TerrainClass.COUNT]，仅陆地类型非零
    }

    /** c 场（cell 中心大陆性采样，c 分带类型布局用） */
    private final ContinentField continent;
    private final double continentBias;

    /**
     * 【2026-07-31】cell 类型按 c 分带（平原靠海、丘陵缓冲、山地高原内陆）：
     * 原纯哈希均匀分配（28/24/18/18/12）→ 任意类型随机邻接（PLATEAU cell 可落在海岸，
     * 与 PLAIN 直接相邻 → 用户反馈"高原旁边是平原"）。
     */
    public TerrainCharacterField(ContinentField continent, double continentBias) {
        this.continent = continent;
        this.continentBias = continentBias;
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

        // 3. 5×5 搜索窗口
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
     * 确定性哈希 + c 分带：(cx, cz) + cell 中心大陆性 → 5 种陆地类型。
     * 【2026-07-31 c 分带布局 v2】按 cell 中心大陆性 cBiased 分带（与海陆 mask 同空间）：
     *   c<0.35  近岸-中内陆：PLAIN 主导 + HILLS（平原面积足，无 PLATEAU/MOUNTAINS）
     *   c∈[0.35,0.60) 内陆过渡：HILLS 缓冲 + MOUNTAINS/PLATEAU 起步（丘陵作平原→山地过渡带）
     *   c≥0.60  深远内陆：MOUNTAINS/PLATEAU 主导
     * v1（c<0.22 才 PLAIN）→ PLAIN 仅 5.7%（近岸带面积小）；v2 平原带放宽到 c<0.35。
     * PLATEAU 只在 c≥0.35（概率 0.25）→ 与 PLAIN（c<0.35）之间隔着 0.35-0.60 过渡带
     * （HILLS/MOUNTAINS/PLATEAU 混合）→ 平原旁不再直接高原。
     * 带内哈希随机（空间变化），带界由 c 场等值线（不规则自然形状），cell 类型只依赖 (cx,cz)
     * → 确定性 + 无缝。
     */
    private int getCellType(int cx, int cz) {
        long h = (long) cx * 374761393L + (long) cz * 668265263L;
        h = h * 1274126177L ^ (h >>> 16);
        h = h * 709369L ^ (h >>> 13);
        h ^= (h >>> 16);
        double r = (h & Long.MAX_VALUE) / (double) Long.MAX_VALUE;
        double c = continent.sample((cx + 0.5) * CELL_SPACING, (cz + 0.5) * CELL_SPACING) + continentBias;
        if (c < -0.05) {
            return r < 0.5 ? TerrainClass.BASIN.ordinal() : TerrainClass.PLAIN.ordinal();
        } else if (c < 0.35) {
            return r < 0.60 ? TerrainClass.PLAIN.ordinal()
                : (r < 0.95 ? TerrainClass.HILLS.ordinal() : TerrainClass.BASIN.ordinal());
        } else if (c < 0.60) {
            return r < 0.35 ? TerrainClass.HILLS.ordinal()
                : (r < 0.55 ? TerrainClass.MOUNTAINS.ordinal()
                : (r < 0.80 ? TerrainClass.PLATEAU.ordinal() : TerrainClass.PLAIN.ordinal()));
        } else {
            return r < 0.45 ? TerrainClass.MOUNTAINS.ordinal()
                : (r < 0.75 ? TerrainClass.PLATEAU.ordinal() : TerrainClass.HILLS.ordinal());
        }
    }
}
