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

    /** 搜索半径：2 → 5×5 搜索窗口（ring 2 在 σ=300, spacing=400 时权重 e^-1.39≈0.25，参与混合） */
    private static final int SEARCH_RADIUS = 2;

    /** 高斯 σ：300（过渡核心约 81 块宽，每 5 块升 1 块——自然缓坡，配合高度带连续衔接无台阶）。
     *  【2026-08-01 评估】σ=400（=spacing）权重场过度平滑 → MOUNTAINS 3.5%/PLATEAU 0.1%
     *  （类型被 PLAIN/HILLS 淹没）；σ=300 + 高度带连续衔接：类型平衡 + 高度连续（1.6 块）。 */
    private static final double SIGMA = 300.0;
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
        cellTypeCache.clear(); // cell 类型依赖 seed（continent 场）
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

    /** cell 类型缓存（cell 类型只依赖 (cx,cz)，seed 时清空；避免邻接检查重复计算 continent） */
    private final java.util.HashMap<Long, Integer> cellTypeCache = new java.util.HashMap<>();

    /**
     * 确定性哈希 + 软 c 调制 + 邻接约束：(cx, cz) + cell 中心大陆性 → 5 种陆地类型。
     * 【2026-07-31 软调制 v4】：
     * ① 软 c 调制（v3）：随机布局为体、c 概率调制为用（PLAIN 0.30→内陆 0.18 中部平原保留、
     *    PLATEAU 海岸 0、内陆 0.14——用户要求"随机生成的陆地中部平原"）；
     * ② 邻接约束（用户反馈"高原旁边不应该是平原"）：PLATEAU cell 若 3×3 邻域内有
     *    PLAIN cell（按无约束 baseType 判定，避免递归）→ 降级为 HILLS（缓冲）
     *    → 高原永远不与平原直接相邻（内陆随机布局保留）。
     * cell 类型只依赖 (cx,cz) → 确定性 + 无缝。
     */
    private int getCellType(int cx, int cz) {
        long key = ((long) cx << 32) | (cz & 0xFFFFFFFFL);
        Integer v = cellTypeCache.get(key);
        if (v != null) return v;
        int t = computeCellType(cx, cz);
        cellTypeCache.put(key, t);
        return t;
    }

    private int computeCellType(int cx, int cz) {
        int base = baseType(cx, cz);
        // 邻接约束：仅降级「孤立高原」——邻 PLAIN 且邻域无 MOUNTAINS/PLATEAU。
        // 山地群中的高原（邻山地）保留（高原旁无平原 ✓）；孤立高原（被平原包围）降级 HILLS 缓冲。
        // v4 全降级教训：8 邻内 PLAIN 概率 78% → PLATEAU 仅剩 1.4%（高原灭绝）。
        if (base == TerrainClass.PLATEAU.ordinal()
                && hasPlainNeighbor(cx, cz) && !hasMountainNeighbor(cx, cz)) {
            return TerrainClass.HILLS.ordinal();
        }
        return base;
    }

    /** 3×3 邻域内是否有 PLAIN（用无约束 baseType，避免递归） */
    private boolean hasPlainNeighbor(int cx, int cz) {
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dz == 0) continue;
                if (baseType(cx + dx, cz + dz) == TerrainClass.PLAIN.ordinal()) return true;
            }
        }
        return false;
    }

    /** 3×3 邻域内是否有 MOUNTAINS/PLATEAU（山地群判定） */
    private boolean hasMountainNeighbor(int cx, int cz) {
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                if (dx == 0 && dz == 0) continue;
                int n = baseType(cx + dx, cz + dz);
                if (n == TerrainClass.MOUNTAINS.ordinal() || n == TerrainClass.PLATEAU.ordinal()) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 无约束概率选择（软 c 调制） */
    private int baseType(int cx, int cz) {
        long h = (long) cx * 374761393L + (long) cz * 668265263L;
        h = h * 1274126177L ^ (h >>> 16);
        h = h * 709369L ^ (h >>> 13);
        h ^= (h >>> 16);
        double r = (h & Long.MAX_VALUE) / (double) Long.MAX_VALUE;
        double c = continent.sample((cx + 0.5) * CELL_SPACING, (cz + 0.5) * CELL_SPACING) + continentBias;
        double t = c < -0.1 ? 0.0 : (c > 0.6 ? 1.0 : (c + 0.1) / 0.7); // 海岸→内陆 0~1
        // 【2026-08-01 v2】σ=300 权重扩围使 MOUNTAINS/PLATEAU 被淹没（实测 7.7%/2.8%）→ 概率回调：
        //   MOUNTAINS 0.10-0.20（海岸起步）、PLATEAU 0-0.16、PLAIN 0.26-0.156、HILLS 0.26、BASIN 0.10
        double wP = 0.26 * (1.0 - 0.4 * t);                       // 内陆 0.156
        double wH = 0.26;
        double wM = 0.20 * (0.5 + 0.5 * t);                       // 海岸 0.10、内陆 0.20
        double wPl = 0.16 * Math.max(0.0, (t - 0.2) / 0.8);       // 海岸 0、内陆 0.16
        double wB = 0.10;
        double sum = wP + wH + wM + wPl + wB;
        double acc = 0;
        if (r < (acc += wP) / sum) return TerrainClass.PLAIN.ordinal();
        if (r < (acc += wH) / sum) return TerrainClass.HILLS.ordinal();
        if (r < (acc += wM) / sum) return TerrainClass.MOUNTAINS.ordinal();
        if (r < (acc += wPl) / sum) return TerrainClass.PLATEAU.ordinal();
        return TerrainClass.BASIN.ordinal();
    }
}
