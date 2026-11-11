package com.geogenesis.worldgen.terrain.candidate;

/**
 * <b>板块领地式大陆场</b>：按参考 {@code worldgen-master} + {@code FreeTerraForged} 的
 * 真实机制实现，替换 {@code ContinentField} 的 FBM 等值线。
 *
 * <h3>为什么换</h3>
 * <p>FBM 的超阈值集在数学上必然是"一个大连通分量 + 大量小碎块"。
 * 实测 {@code ContinentShapeProbe}：634 个陆块、最大占 99.3%、第二大 0.2%、碎块 600 个。
 * 调阈值只能在大陆和海洋两个极端之间跳，中间没有"几块大小相近的大陆"这个解。</p>
 *
 * <h3>参考的四个机制（本类全部实现）</h3>
 * <ol>
 *   <li><b>Voronoi 板块领地</b>：3×3 抖动格，每格是一个板块。</li>
 *   <li><b>陆壳/洋壳按板块中心的低频噪声判定</b>（{@code properties.rs:80-105}）：
 *       噪声高的一片连续区域成为大陆，噪声低的成为开阔海洋。</li>
 *   <li><b>陆壳/洋壳的基准高度差</b>（{@code properties.rs:145-152}）。</li>
 *   <li>★ <b>对板块高度做平滑混合</b>（{@code elevation.rs:105-107}）：
 *       参考明确写了 "Phase 2: Smooth profiles to eliminate Voronoi ridge
 *       discontinuities"，用 {@code blur_grid} 消除板块边界的断裂。
 *       我们的流式场景用 <b>3×3 高斯距离加权混合</b>等价实现。</li>
 * </ol>
 *
 * <h3>★★★ 2026-10-30 三次关键修正（都曾导致图不可用）</h3>
 * <ol>
 *   <li><b>edge 不是高度来源</b>：前三版把 Voronoi 距离场的归一化值当高度 ⇒
 *       格界 edge→0 ⇒ 格界必然是海 ⇒ 每格孤立成岛、海洋成沟渠网。</li>
 *   <li><b>不能用布尔取 base</b>：相邻板块 base 直接跳变最多 1600 格 ⇒
 *       每道边界一条断崖 ⇒ 图上"碎马赛克"。必须按距离高斯加权混合（见机制 ④）。</li>
 *   <li><b>尺度必须对齐世界高度</b>：参考的 continentScale=3000/tectonicScale=12000
 *       在我们的世界（高 384 格）里算出大陆 24000–36000 格宽，是生产尺度的 4–6 倍，
 *       完全不可玩。按"山高 300 格、坡 20% ⇒ 宽 1500 格"反推，
 *       板块格应取 1600 格。</li>
 * </ol>
 *
 * <h3>尺度（对齐世界高度 minY=−64 … maxY=320，总高 384 格）</h3>
 * <pre>
 *   板块格边长  1600 格
 *   大陆宽      4800–6400 格（3–4 个板块）—— 与生产 continentScale=4000 同量级
 *   1401² 正典窗口 ≈ 0.9 个板块 ⇒ 能同时看到陆地 + 海岸 + 近海
 * </pre>
 *
 * <h3>流式约束</h3>
 * <p>全部 (x,z) 纯函数：3×3 Voronoi + 每格 2 次哈希 + 2 次域扭曲 + 1 次起伏噪声。</p>
 */
public final class ProvinceContinentField {

    // ===== 尺度 =====
    /** 板块格边长（块）。大陆 = 3–4 个板块 = 4800–6400 块。 */
    static final int TECTONIC_SCALE = 1600;
    /** 参考的 ×4 关系。 */
    static final int CONTINENT_SCALE = TECTONIC_SCALE / 4;
    static final float JITTER = 0.7f;

    /**
     * 板块高度混合的高斯 σ（格单位，板块格=1.0）。
     *
     * <p>σ=0.5 使过渡带宽约 1 个板块（1600 格）—— 与参考的 {@code blur_sigma=3}
     * 在它自己的 84 像素板块尺度下的相对宽度相当。太小则断崖回归，太大则大陆糊成一片。</p>
     */
    private static final double BLEND_SIGMA = 0.5;

    // ===== 陆/洋判定与高度 =====
    /** 陆壳板块占比（参考 {@code continental_fraction = 0.54}）。 */
    static final float CONTINENTAL_FRACTION = 0.54f;

    /**
     * 陆壳判定阈值（作用在板块中心的低频噪声上，噪声已展宽到接近均匀）。
     *
     * <p>{@code ContinentFlagProbe} 实测（200×200 板块）：0.40→64.1%、0.46→56.5%、
     * <b>0.48→≈54%</b>、0.50→49.3%。</p>
     */
    static final double CONTINENTAL_THRESHOLD = 0.48;

    /**
     * 陆壳基准高度（格）。跨度 400 格，配合 σ=0.5 的混合带宽 1600 格 ⇒ 坡约 0.25。
     * 现实大陆内部高差也是几百米量级。
     */
    private static final double CONTINENTAL_BASE_LO = 300.0;
    private static final double CONTINENTAL_BASE_HI = 700.0;
    /**
     * 洋壳基准高度（格）。参考是 −4000..−3000，但我们的世界 {@code minY=−64}
     * ⇒ −4000 在世界外。改成 −700..−300（跨度 400，与陆壳对称）。
     */
    private static final double OCEANIC_BASE_LO = -700.0;
    private static final double OCEANIC_BASE_HI = -300.0;

    /** 换算到 c 的除数（陆壳上限 700 ⇒ c=+1；洋壳下限 −700 ⇒ c=−1）。 */
    private static final double C_DIVISOR = 700.0;

    // ===== 域扭曲（两级复合，参考 UpliftContinentGenerator.createWarp）=====
    //   参考：warpScale = tectonicScale × 0.225，strength = tectonicScale × 0.33
    private final RefNoise.Node warp1X, warp1Z;
    private static final float WARP1_DIST = 20.0f;
    private final RefNoise.Node warp2X, warp2Z;
    private static final float WARP2_SCALE = TECTONIC_SCALE * 0.225f;
    private static final float WARP2_DIST = TECTONIC_SCALE * 0.33f;

    // ===== 状态 =====
    private final int seed;
    private final int baseSeed;
    private final int oceanSeed;
    private final float frequency;
    private final RefNoise.Node continentNoiseNode;
    private final RefNoise.Node reliefNoiseNode;

    static boolean DEBUG = false;

    public ProvinceContinentField(long worldSeed) {
        int base = (int) (worldSeed ^ (worldSeed >>> 32));
        this.seed = base;
        this.baseSeed = base + 2609;
        this.oceanSeed = base + 3767;
        this.frequency = 1f / TECTONIC_SCALE;

        // 两级域扭曲（把 Voronoi 直边揉成有机曲线）
        this.warp1X = RefNoise.Perlin.ofScale(seedOf(base + 2141), 20f, 2);
        this.warp1Z = RefNoise.Perlin.ofScale(seedOf(base + 2142), 20f, 2);
        // 第二级用 5 倍频 + lac 4.33：最高频约 360/4.33⁴ ≈ 1 块，才揉得碎
        this.warp2X = new RefNoise.Perlin(seedOf(base + 2143), 1f / WARP2_SCALE,
                5, 4.33f, 0.5f);
        this.warp2Z = new RefNoise.Perlin(seedOf(base + 2144), 1f / WARP2_SCALE,
                5, 4.33f, 0.5f);

        // 大陆倾向噪声：波长 ≈ 3.5 格距（5600 块 ≈ 一块大陆），展宽到接近均匀
        RefNoise.Node rawCont = RefNoise.Perlin.ofScale(seedOf(base + 5519),
                TECTONIC_SCALE * 3.5f, 3);
        this.continentNoiseNode = new RefNoise.Mapped(
                new RefNoise.Clamped(rawCont, 0.30f, 0.70f), 0.30f, 0.70f, 0f, 1f);

        // 板块内部起伏：波长 2 格距（3200 块）
        this.reliefNoiseNode = RefNoise.Perlin.ofScale(seedOf(base + 4483),
                TECTONIC_SCALE * 2f, 3);
    }

    /**
     * 采样大陆性 {@code c ∈ [−1,1]}（0 = 海岸线）。
     *
     * <p>{@code ContinentalField} 的接口契约不变，下游全部读 c。</p>
     */
    public double sample(double wx, double wz) {
        // ① 两级复合域扭曲
        double ox1 = (warp1X.compute((float) wx, (float) wz) - 0.5f) * WARP1_DIST;
        double oz1 = (warp1Z.compute((float) wx, (float) wz) - 0.5f) * WARP1_DIST;
        double x1 = wx + ox1, z1 = wz + oz1;
        double ox2 = (warp2X.compute((float) x1, (float) z1) - 0.5f) * WARP2_DIST;
        double oz2 = (warp2Z.compute((float) x1, (float) z1) - 0.5f) * WARP2_DIST;
        double px = (wx + ox2) * frequency;
        double pz = (wz + oz2) * frequency;

        // ② 3×3 邻居按高斯距离加权混合高度（等价参考的 blur_grid，消除断崖）
        int xi = RefNoise.floor((float) px);
        int yi = RefNoise.floor((float) pz);
        double weightSum = 0.0;
        double baseSum = 0.0;

        for (int dy = -1; dy <= 1; dy++) {
            for (int dx = -1; dx <= 1; dx++) {
                int cx = xi + dx, cy = yi + dy;
                float[] vec = RegionCellTable.CELL[RefNoise.hash2D(seed, cx, cy) & 0xFF];
                double cxp = cx + vec[0] * JITTER;
                double cyp = cy + vec[1] * JITTER;
                double ddx = cxp - px, ddy = cyp - pz;
                double dist2 = ddx * ddx + ddy * ddy;

                double w = Math.exp(-dist2 / (2.0 * BLEND_SIGMA * BLEND_SIGMA));
                if (w < 1e-6) continue;

                weightSum += w;
                baseSum += w * plateBaseBlocks(cx, cy);
            }
        }
        double baseBlocks0 = weightSum > 1e-9 ? baseSum / weightSum : 0.0;

        // ③ 板块内部起伏（幅度随 |base| 缩放，保证不跨过 0 ⇒ 陆地不被压成海）
        double amp = Math.max(0.0, Math.abs(baseBlocks0) - 20.0) * 0.5;
        double relief = (reliefNoiseNode.compute((float) wx, (float) wz) - 0.5) * 2.0 * amp;
        double baseBlocks = baseBlocks0 + relief;

        // ④ 归一化
        double c = baseBlocks / C_DIVISOR;
        if (DEBUG) {
            System.out.printf(java.util.Locale.ROOT,
                    "[DBG] cell=(%d,%d) base0=%.1f relief=%.1f c=%.4f%n",
                    xi, yi, baseBlocks0, relief, c);
        }
        return Math.max(-1.0, Math.min(1.0, c));
    }

    /**
     * 某板块的基准高度（格）。
     *
     * <p>陆壳/洋壳由<b>板块中心</b>的低频噪声决定（参考 {@code properties.rs:80-105}：
     * 按板块中心的 fbm 排序取前 54% 为陆壳 ⇒ 噪声高的一片连续区域成陆）。</p>
     */
    private double plateBaseBlocks(int cellX, int cellY) {
        double centerWx = (cellX + 0.5) / frequency;
        double centerWz = (cellY + 0.5) / frequency;
        float contNoise = continentNoiseNode.compute((float) centerWx, (float) centerWz);
        if (contNoise > CONTINENTAL_THRESHOLD) {
            return lerp(CONTINENTAL_BASE_LO, CONTINENTAL_BASE_HI,
                    cellValue(baseSeed, cellX, cellY));
        }
        return lerp(OCEANIC_BASE_LO, OCEANIC_BASE_HI,
                cellValue(oceanSeed, cellX, cellY));
    }

    /** {@code AbstractContinent.getCellValue}：0.5 + valCoord2D×0.5，输出 [0,1]。 */
    private static float cellValue(int seed, int cellX, int cellY) {
        return 0.5f + valCoord2D(seed, cellX, cellY) * 0.5f;
    }

    private static float valCoord2D(int seed, int x, int y) {
        int n = seed;
        n ^= 1619 * x;
        n ^= 31337 * y;
        return n * n * n * 60493 / 2.14748365E9F;
    }

    private static double lerp(double a, double b, double t) { return a + (b - a) * t; }

    private static int seedOf(int v) { return v & 0x7FFFFFFF; }
}