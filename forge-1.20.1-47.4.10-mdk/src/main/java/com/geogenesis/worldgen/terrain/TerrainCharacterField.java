package com.geogenesis.worldgen.terrain;

import com.geogenesis.worldgen.noise.*;

/**
 * Voronoi 高斯距离权重地形类型场。
 * <p>
 * 核心设计：
 * <ul>
 *   <li>400 块间距的稀疏网格，每格点哈希独立分配类型之一（2026-08-06：海陆=2 大地形类型，
 *       OCEAN/DEEP_OCEAN 与 5 陆地类型共同参与细胞竞争）</li>
 *   <li>海洋细胞概率由大陆性 c 调制（c 低→大概率海洋，c 高→陆地；过渡带概率对半）——
 *       保留大陆大尺度结构，但海陆边界 = Voronoi 细胞竞争（400 块折线），与类型边界同构</li>
 *   <li>任意类型可邻接任意类型（OCEAN 可紧邻 MOUNTAINS）</li>
 *   <li>最近格点高斯距离权重主导（σ=200），类型边界平滑过渡</li>
 *   <li>7×7 搜索窗口（SEARCH_RADIUS=3）：进出格点距离≥1000 → 权重自然衰减到 3.7e-6，零窗口进出跳变</li>
 *   <li>域扭曲打散网格规则感（默认 {@code WARP_AMP=0}；★ 2026-09-16 起改为<b>可设</b>，见 {@link #setWarpAmp}）</li>
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

    // ===== 海洋类型参与细胞竞争（2026-08-06：海陆=2 大地形类型） =====
    private static final int OCEAN_ORD = TerrainClass.OCEAN.ordinal();
    private static final int DEEP_OCEAN_ORD = TerrainClass.DEEP_OCEAN.ordinal();

    /** 海洋细胞概率调制半宽（cBiased 空间）：cBiased∈[-OCEAN_RAMP, +OCEAN_RAMP] 内概率 1→0 线性 */
    private static final double OCEAN_RAMP = 0.33;

    private final ContinentField continent;
    private final double continentBias;

    /**
     * ★ 2026-09-16 修复（严重）：世界种子参与【格点类型哈希】。
     *
     * <h3>原缺陷：地形类型场与种子无关 ⇒ 所有世界的山脉/高原/平原布局相同</h3>
     * <p>{@code getCellType} 原先用 {@code hash(cx, cz)} 决定每个格点的类型，
     * <b>哈希不含世界种子</b>；而 {@link #seed} 只播种了 {@code warpX/warpZ}，
     * 但 {@code WARP_AMP = 0} ⇒ warp 对采样坐标<b>没有任何影响</b>。
     * 合起来 ⇒ <b>{@code seed()} 对类型场完全无效。</b></p>
     * <p>后果：格点 {@code (cx,cz)} 的陆地类型在所有世界都相同 ⇒
     * <b>山脉带、高原区、平原区的大尺度位置是世界无关的常量</b>。
     * 随种子变化的只有海陆比例（由已播种的 {@code ContinentField} 决定，
     * 即"哪些格点是海、哪些是陆"）⇒ 换种子只换海岸线，不换地形性格。</p>
     *
     * <p>★ 发现途径：新建的 {@code runTypeAxisProbe} 用 3 个不同种子跑出
     * <b>逐位相同</b>的结果（水平 920 / 垂直 776 / 对角 781、419，方向比 1.18）
     * ⇒ 暴露了"种子没接进去"。<b>若不是先补了那条遗失的门禁，这个 bug 不会现形。</b></p>
     */
    private volatile long seedHash = 0L;

    // ===== 域扭曲（打散网格规则感） =====
    private final Noise warpX, warpZ;

    /**
     * 域扭曲幅度默认值（块）。★ 2026-09-16：<b>40.0（启用）</b> —— 用于消除轴向对齐缺陷。
     *
     * <h4>取值依据（5 种子 × 4 相位实测量化，见 {@code docs/plans/轴向对齐与域扭曲-预研}）</h4>
     * <table>
     *   <tr><th>amp</th><th>最长轴向直段中位</th><th>{@code border.maxSurfaceDelta}</th>
     *       <th>LandE 跳变（既知未达标）</th></tr>
     *   <tr><td>0（旧）</td><td>944 块（0.315×）</td><td>1.845</td>
     *       <td>10 次 / 0.02251e @(723,−755)</td></tr>
     *   <tr><td><b>40（采用）</b></td><td><b>272 块（0.091×）</b></td><td><b>0.764</b></td>
     *       <td>11 次 / 0.02257e <b>@(723,−755) 同一位置</b></td></tr>
     *   <tr><td>80</td><td>192 块</td><td>1.032</td>
     *       <td>14 次 / 0.02479e @(−338,294) <b>新位置</b></td></tr>
     * </table>
     * <p>⇒ <b>取 40 而非 80</b>：直段已改善 3.5×，而更重要的是
     * <b>amp=40 的 LandE 最坏点仍在原位置（幅度 +0.3%）= 不引入新跳变源</b>；
     * amp=80 则出现<b>新位置</b>的跳变。而两档的排水哨兵都<b>改善</b>（1.845 → 0.764 / 1.032）。</p>
     *
     * <h4>⚠ 这条改动的性质</h4>
     * <p>它会<b>改变所有世界的地形大尺度位置</b>（类型场被形变）⇒ 旧存档/旧预览缓存失效。
     * 故已同步 bump {@code PreviewDisplay.CACHE_SCHEMA_VERSION}，
     * 且<b>须实机复验</b>。若观感不佳，把本常量改回 {@code 0.0} 即完全回退（无其它牵连）。</p>
     */
    private static final double WARP_AMP_DEFAULT = 40.0;

    /**
     * 域扭曲幅度（块）。★ 2026-09-16：由 {@code static final 0.0} 改为<b>可设</b>
     * （默认 {@link #WARP_AMP_DEFAULT} ⇒ <b>产出逐位不变</b>，故无需 bump 预览缓存）。
     *
     * <h4>为何改成可设：为【实证复核】2026-08-03 那条结论</h4>
     * <p>当时的记载是："80→0（用户实测确认——类型权重查格点 ±80 块平移让主导沿细胞边界跳跃，
     * 产生 1 格宽断裂伪影…）"。但<b>同一次改动（同日）还修了另一处、症状完全相同的缺陷</b>：
     * {@link #SEARCH_RADIUS} 由 1（3×3 窗口）扩到 3（7×7），而其记录明确写着
     * "移出/移入格点类型不同 → typeWeights 在 1 格内突变 ~0.022 → argmax 翻转 →
     * eLand 突变 → <b>1 格断裂线（CellBoundaryProbe 实测 @X=400/800）</b>"。
     * ⇒ <b>两条记录描述的是同一个"1 格宽断裂"，而窗口那条有实测定位、warp 那条只有现象描述</b>
     * ⇒ <b>warp 疑似背了窗口的锅（误诊）。</b></p>
     *
     * <h4>代码与数学都不支持"warp 致断裂"</h4>
     * <ul>
     *   <li>采样是 {@code w' = w + A·warp(w)} 的<b>连续</b>位移，权重是 {@code w'} 的连续函数；</li>
     *   <li>唯一的非连续点是 7×7 窗口随 {@code floor(w'/400)} 换格；而进出窗口的格点
     *       距离 ≥ {@code 2.5×400 = 1000} ⇒ 权重 {@code exp(-12.5) ≈ 3.7e-6}，
     *       对 typeWeights 的影响约 {@code 2.6e-5} —— <b>不可能造成断裂</b>
     *       （这正是 {@code SEARCH_RADIUS=3} 的设计目的）。</li>
     * </ul>
     *
     * <h4>另有强对照</h4>
     * <p>{@code TectonicField} 用<b>同一机制</b>（扭曲查询点，{@code WARP_AMP=130 / 波长 400}），
     * 且 CHANGELOG（2026-09-12）记载它<b>实测消除了长直线边界</b>
     * （"contours_1block 中的长直线全部消失"），未见断裂。</p>
     *
     * <h4>★ 若确认可用，它正是 P1「轴向对齐」所需的机制</h4>
     * <p>位移<b>有界</b>（≤A 块，远小于 {@code CELL_SPACING=400}）⇒ 类型图是原图的
     * <b>拓扑形变</b>：各类型区的<b>邻接关系与规模不变</b>，只是边界被平滑弯折。
     * 这与"种子抖动"那种<b>改归属、重排格局</b>的做法有本质区别
     * （后者已被证伪：`border.maxSurfaceDelta` 1.358→12.772）。</p>
     * <p>实证：断裂看 {@code WarpFractureProbe}，轴向对齐看 {@code TypeAxisProbe}（第 4 参数 = warpAmp）。</p>
     */
    private static volatile double warpAmp = WARP_AMP_DEFAULT;

    /** 域扭曲频率（1/wu）：波长 500 块。 */
    private static final double WARP_FREQ = 1.0 / 500.0;

    /** 设置域扭曲幅度（块）；{@code 0} = 关闭。供探针/实验使用。 */
    public static void setWarpAmp(double amp) {
        warpAmp = amp < 0 ? 0 : amp;
    }

    /** 当前域扭曲幅度（块）。 */
    public static double warpAmp() {
        return warpAmp;
    }

    // ===== 混合结果 =====
    public static final class BlendResult {
        public double lo;             // 不再使用，恒 0.0
        public double hi;             // 不再使用，恒 0.0
        public TerrainClass dominantType;
        public double alpha;          // 主导类型归一化权重
        public double[] typeWeights;  // [TerrainClass.COUNT]，仅陆地类型非零
    }

    public TerrainCharacterField(ContinentField continent, double continentBias) {
        this.continent = continent;
        this.continentBias = continentBias;
        Noise wX = new Frequency(new Simplex(310), WARP_FREQ);
        this.warpX = new Map(wX, -1.0, 1.0, -1.0, 1.0);
        Noise wZ = new Frequency(new Simplex(311), WARP_FREQ);
        this.warpZ = new Map(wZ, -1.0, 1.0, -1.0, 1.0);
    }

    public void seed(long worldSeed) {
        // ★ 2026-09-16：必须记录种子本身 —— 它要参与【格点类型哈希】（见 seedHash）。
        //   原先只播种 warp（而 WARP_AMP=0 ⇒ 等于什么都没做）⇒ 类型场与种子无关。
        seedHash = worldSeed;
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
        // 1. 域扭曲（幅度默认 0；实测复核见 warpAmp 字段的说明）
        double wxw = wx + warpAmp * warpX.compute(wx, wz);
        double wzw = wz + warpAmp * warpZ.compute(wx, wz);

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
     * 确定性哈希 + 大陆性概率调制：(cx, cz) → 海洋（OCEAN/DEEP_OCEAN）或 5 陆地类型之一。
     * <p>
     * 2026-08-06 海陆类型化：c 低（海洋区）细胞大概率分到海洋类型，c 高（陆地区）分到陆地类型，
     * 过渡带概率对半 → 海陆边界 = Voronoi 细胞竞争（400 块折线），与地形类型边界同构；
     * 大尺度大陆结构仍由 c 概率场保证。
     */
    private int getCellType(int cx, int cz) {
        // ★ 2026-09-16：把【世界种子】折进哈希（黄金比例常数乘 ⇒ 种子低位也充分扩散，
        //   后面的雪崩混合再把坐标与种子彻底搅在一起）。
        //   原实现缺这一项 ⇒ 类型场与种子无关（见 seedHash 的说明）。
        long h = seedHash * 0x9E3779B97F4A7C15L
                + (long) cx * 374761393L + (long) cz * 668265263L;
        h = h * 1274126177L ^ (h >>> 16);
        h = h * 709369L ^ (h >>> 13);
        h ^= (h >>> 16);

        // 细胞中心的大陆性 cBiased（用于海洋/陆地细胞概率）
        double cBiased = continent.sample((cx + 0.5) * CELL_SPACING, (cz + 0.5) * CELL_SPACING) - continentBias;
        double pOcean = oceanCellProbability(cBiased);
        double r1 = (h & 0xFFFFFFFFL) / 4294967296.0;
        if (r1 < pOcean) {
            // 海洋细分：c 越低越可能深海
            double pDeep = oceanCellProbability(cBiased * 0.75);
            double r2 = ((h >>> 32) & 0xFFFFFFFFL) / 4294967296.0;
            return r2 < pDeep ? DEEP_OCEAN_ORD : OCEAN_ORD;
        }
        int idx = (int) ((h >>> 8) % LAND_ORDINALS.length);
        return LAND_ORDINALS[idx];
    }

    /** 海洋细胞概率：cBiased=-OCEAN_RAMP→1（纯海），0→0.5，+OCEAN_RAMP→0（纯陆），线性夹紧 */
    private static double oceanCellProbability(double cBiased) {
        double t = 0.5 - cBiased / (2.0 * OCEAN_RAMP);
        return t < 0.0 ? 0.0 : (t > 1.0 ? 1.0 : t);
    }
}
