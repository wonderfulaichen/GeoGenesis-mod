package com.geogenesis.worldgen.terrain.candidate;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * <b>过渡类配方目录</b>（方案 {@code ocean-unified-terrain}）。
 *
 * <h3>★ 拼图结构（2026-11-01 用户两次纠正后的最终理解）</h3>
 * <pre>
 *   内部类（各有独立形态的完整地形）
 *   ├── 陆地 8（PLAIN/STEPPE/DALES/HILLS1/HILLS2/PLATEAU/BADLANDS/MOUNTAIN）
 *   ├── 海岸 5（SANDY_BEACH/SEA_CLIFF/REEF_FLAT/TIDAL_FLAT/DELTA）  ← 本身在陆地上
 *   └── 海洋 5（ABYSSAL_PLAIN/MID_OCEAN_RIDGE/SEAMOUNT_FIELD/CANYON/SHELF_RIDGE）
 *
 *   过渡类（内部类之间的接缝，只做"哪两块相邻、怎么衔接"）
 *   ├── 陆 ↔ 陆     10（已有，C(5,2)）
 *   ├── 陆 ↔ 海岸    （陆地接海岸）
 *   ├── 海岸 ↔ 海洋  （海岸接海洋）
 *   └── 海 ↔ 海     10（C(5,2)，其中含大陆坡）
 * </pre>
 *
 * <h3>★ 为什么没有"陆↔海"过渡</h3>
 * <p>用户纠正：</p>
 * <blockquote>
 * "过渡类型不应该是海岸地形+海洋地形（这个才有海有陆）。海岸地形还在陆地上，
 * 只是位置会生成在内部陆地与海洋之间。"
 * </blockquote>
 * <p>首版直接混合陆地配方与海洋配方 ⇒ <b>没有海岸这一块，陆地直接掉进海里</b>。
 * 正确序列是：</p>
 * <pre>
 *   内部陆地 →【陆↔海岸过渡】→ 海岸地形 →【海岸↔海洋过渡】→ 内部海洋
 * </pre>
 * <p>海岸地形自己是一块拼图（有沙滩/崖岸/礁滩等独立形态），
 * 它位于<b>陆地上</b>（高度略高于海平面），只是空间位置上在陆地与海洋之间。</p>
 *
 * <h3>为什么过渡不需要手写形态</h3>
 * <p>过渡 = 两个内部配方按连续 alpha 混合。这与 {@code CombineCandidate.buildComposites}
 * 是<b>同一机制</b>，只是两端可以来自不同的内部类族。</p>
 */
final class OceanTransitionCatalog {

    static final double BLOCKS_PER_E = OceanRecipeCatalog.BLOCKS_PER_E;

    // ============================================================ 过渡条目

    /** 过渡的两个端点来自哪些内部类族。 */
    enum PairKind {
        /** 海↔海（如大陆坡 = 深海平原 ↔ 陆架沙脊）。 */
        SEA_SEA,
        /** 陆↔陆（已有，由 CombineCandidate 处理；本类只镜像其结构供对照）。 */
        LAND_LAND,
        /** 陆↔海岸（陆地接海岸地形）。 */
        LAND_COAST,
        /** 海岸↔海洋（海岸地形接海洋）。 */
        COAST_SEA
    }

    /** 一个过渡条目。 */
    static class Transition {
        final String name;
        final PairKind kind;
        final RefNoise.Node alpha;
        final java.util.function.DoubleBinaryOperator a;
        final java.util.function.DoubleBinaryOperator b;
        /**
         * 票槽权重（默认 1.0）。决定这条过渡在池里出现的相对频率。
         *
         * <p>用于压低"占比过大的过渡"：礁盘（{@code REEF_FLAT}）有两条海岸↔海洋配对，
         * 若都用默认权重 1.0，礁盘在海岸带的面积占比会是其它海岸的两倍
         * （实测水占 77–81%，陆地被压得太薄）。给它一个 &lt;1 的权重即可收敛。</p>
         */
        final double weight;

        Transition(String name, PairKind kind, RefNoise.Node alpha,
                   java.util.function.DoubleBinaryOperator a,
                   java.util.function.DoubleBinaryOperator b) {
            this(name, kind, alpha, a, b, 1.0);
        }

        Transition(String name, PairKind kind, RefNoise.Node alpha,
                   java.util.function.DoubleBinaryOperator a,
                   java.util.function.DoubleBinaryOperator b, double weight) {
            this.name = name;
            this.kind = kind;
            this.alpha = alpha;
            this.a = a;
            this.b = b;
            this.weight = weight;
        }

        String name() { return name; }

        PairKind kind() { return kind; }

        double weight() { return weight; }

        /**
         * 混合求值。
         *
         * <h3>★ 为什么"海岸↔海洋"不能用线性混合</h3>
         * <p>实测（{@code coast-sea-profile.png}）：9 条海岸↔海洋过渡里
         * <b>5 条水占比 100%</b>，其余也在 83–96%，几乎看不到陆地。</p>
         * <p>根因是<b>两端量级悬殊</b>：</p>
         * <pre>
         *   海岸配方中位  ≈ +0.01 e    （≈ +2 格，紧贴海平面）
         *   海洋配方中位  ≈ −0.36 e    （≈ −46 格）
         *   线性混合:  (1−α)·0.01 + α·(−0.36)
         *   ⇒ α 只要 &gt; 0.027 结果就为负 ⇒ 海岸被【完全抹掉】
         * </pre>
         * <p>这与"陆地↔海洋"那次是同一个几何事实：
         * <b>线性混合的结果落在两端区间的凸包内</b>，量级小的一端会被量级大的一端吞掉。</p>
         *
         * <h3>正解：两段式【凹函数】</h3>
         * <pre>
         *   s &lt; 0.5 :  a · g(1 − 2s)       g(x) = 2x − x²
         *   s ≥ 0.5 :  b · g(2s − 1)
         * </pre>
         * <p>{@code g(0)=0}、{@code g(1)=1}，{@code g'(x)=2−2x} ⇒
         * <b>端点导数有限</b>（{@code g'(0)=2}、{@code g'(1)=0}）。</p>
         *
         * <h3>★ 为什么是 {@code 2x−x²} 而不是 {@code x²} 或 {@code √x}</h3>
         * <p>三种形状的实测对比（{@code SANDY_BEACH↔SHELF_RIDGE} 的 Y 跨度）：</p>
         * <pre>
         *   形状          陆上高度分布            实测 Y 跨度
         *   x²（凸）       全压在岸边窄带内        64..52（12 格）← 首版，陆上段读作平的
         *   √x（强凹）     几乎全程满高度          端点导数无穷 ⇒ 水边有折角
         *   2x−x²（凹）    岸边降得快、内侧趋平     两者折中，端点导数有限
         * </pre>
         * <p>物理上 {@code 2x−x²} 正是真实的海岸剖面：
         * <b>水边最陡（shoreface），离开水边后迅速变缓（陆架/海岸平原）</b>。
         * 用 {@code x²} 会让"最陡处"跑到远离水边的地方，与真实相反。</p>
         *
         * <p>水边（{@code s=0.5}）两侧高度都为 0 ⇒ 地形连续；
         * 两侧斜率不同（陆侧 {@code −4a}、海侧 {@code +4b}）—— 这是<b>正确的</b>，
         * 水边本来就是剖面的坡折处。</p>
         */
        double height01(double x, double z) {
            double al = RefNoise.clamp(alpha.compute((float) x, (float) z), 0f, 1f);
            double av = a.applyAsDouble(x, z);
            double bv = b.applyAsDouble(x, z);
            if (kind == PairKind.COAST_SEA) {
                if (al < 0.5) {
                    double u = 1.0 - 2.0 * al;      // 0 在水边，1 在内陆
                    return av * (2.0 * u - u * u);
                }
                double u = 2.0 * al - 1.0;          // 0 在水边，1 在远海
                return bv * (2.0 * u - u * u);
            }
            // 其余族：线性混合（端点量级相近、同号）
            if (al <= 0.0) return av;
            if (al >= 1.0) return bv;
            return av * (1.0 - al) + bv * al;
        }
    }

    // ============================================================ 状态

    private final long seed;
    private final OceanRecipeCatalog ocean;
    private final CoastRecipeCatalog coast;
    private final List<Transition> seaSea = new ArrayList<>();
    private final List<Transition> landCoast = new ArrayList<>();
    private final List<Transition> coastSea = new ArrayList<>();

    OceanTransitionCatalog(long seed, OceanRecipeCatalog ocean, CoastRecipeCatalog coast) {
        this.seed = seed;
        this.ocean = ocean;
        this.coast = coast;
        int b = (int) (seed ^ (seed >>> 32));
        buildSeaSea(b);
        buildLandCoast(b);
        buildCoastSea(b);
    }

    /** 兼容旧签名（无海岸池）：只有海↔海。 */
    OceanTransitionCatalog(long seed, OceanRecipeCatalog ocean) {
        this(seed, ocean, new CoastRecipeCatalog(seed + CoastRecipeCatalog.SEED_OFFSET));
    }

    // ============================================================ 海↔海

    /**
     * 生成 {@code C(5,2)=10} 个海↔海过渡。
     *
     * <p>其中 {@code ABYSSAL_PLAIN × SHELF_RIDGE} 就是<b>大陆坡</b>：
     * 实测 α=0.5 混合得 {@code −0.2121}，与手写 {@code CONTINENTAL_SLOPE} 的
     * 中位 {@code −0.2056} 相差 {@code 0.0109 e ≈ 1.4 格} ⇒ 复合可替代手写。</p>
     *
     * <h3>alpha 必须做对比度拉伸</h3>
     * <p>{@code RefNoise.Perlin} 的输出集中在 0.5 附近（P10≈0.35 / P90≈0.65），
     * 直接用会让结果被平均成一片（实测 10 张图几乎全蓝）。
     * 把 {@code [0.30, 0.70]} 拉伸到 {@code [0,1]} 后中间 80% 能铺满值域。</p>
     */
    private void buildSeaSea(int b) {
        OceanRecipeCatalog.Recipe[] all = OceanRecipeCatalog.Recipe.values();
        int k = 0;
        for (int i = 0; i < all.length; i++) {
            for (int j = i + 1; j < all.length; j++) {
                final OceanRecipeCatalog.Recipe ra = all[i], rb = all[j];
                float scale = 900f + (k % 6) * 300f;
                RefNoise.Node raw = RefNoise.warpPerlin(
                        RefNoise.Perlin.ofScale(b + 800 + k, scale, 2),
                        b + 850 + k, scale / 2f, 2, scale / 4f);
                RefNoise.Node alpha = contrast(raw);
                seaSea.add(new Transition(ra + "↔" + rb, PairKind.SEA_SEA, alpha,
                        (x, z) -> ocean.height01(ra, x, z),
                        (x, z) -> ocean.height01(rb, x, z)));
                k++;
            }
        }
    }

    // ============================================================ 陆↔海岸

    /**
     * 陆↔海岸过渡：陆地地形接到海岸地形。
     *
     * <p>每个海岸配方都接到它<b>背后最可能的陆地地形</b>上
     * （按高度近似：沙滩接平原、崖岸接山地、潮滩接盆地…）。
     * 这样"海岸性格"由两侧共同决定，而不是固定配对。</p>
     */
    private void buildLandCoast(int b) {
        // {陆地档, 海岸配方, 权重}
        // ★ 礁盘两条同样降权（理由见 buildCoastSea 的注释）
        Object[][] pairs = {
                {LandBand.BASIN,     CoastRecipeCatalog.Recipe.TIDAL_FLAT,   1.0},
                {LandBand.PLAIN,     CoastRecipeCatalog.Recipe.SANDY_BEACH,  1.0},
                {LandBand.PLAIN,     CoastRecipeCatalog.Recipe.DELTA,        1.0},
                {LandBand.HILLS,     CoastRecipeCatalog.Recipe.SANDY_BEACH,  1.0},
                {LandBand.HILLS,     CoastRecipeCatalog.Recipe.REEF_FLAT,    0.55},
                {LandBand.PLATEAU,   CoastRecipeCatalog.Recipe.SEA_CLIFF,    1.0},
                {LandBand.PLATEAU,   CoastRecipeCatalog.Recipe.REEF_FLAT,    0.55},
                {LandBand.MOUNTAINS, CoastRecipeCatalog.Recipe.SEA_CLIFF,    1.0},
        };
        int k = 0;
        for (Object[] pr : pairs) {
            LandBand band = (LandBand) pr[0];
            final CoastRecipeCatalog.Recipe cr = (CoastRecipeCatalog.Recipe) pr[1];
            final double w = (Double) pr[2];

            // 陆地端：该档名义区间内的噪声
            RefNoise.Node landNoise = RefNoise.Perlin.ofScale(b + 1700 + k, 700f + (k % 5) * 250f, 3);
            final double lo = band.lo, hi = band.hi;
            java.util.function.DoubleBinaryOperator landFn =
                    (x, z) -> lo + (hi - lo) * landNoise.compute((float) x, (float) z);

            // alpha：陆地 ↔ 海岸的位置过渡
            RefNoise.Node raw = RefNoise.warpPerlin(
                    RefNoise.Perlin.ofScale(b + 1750 + k, 1300f + (k % 4) * 180f, 2),
                    b + 1800 + k, 650f, 2, 325f);
            RefNoise.Node alpha = contrast(raw);

            landCoast.add(new Transition(band.label + "↔" + cr, PairKind.LAND_COAST, alpha,
                    landFn, (x, z) -> coast.height01(cr, x, z), w));
            k++;
        }
    }

    // ============================================================ 海岸↔海洋

    /**
     * 海岸↔海洋过渡：海岸地形接到海洋地形。
     *
     * <p><b>这里是"海陆交界"真正发生的地方</b>：海岸配方在陆地上（高度略正），
     * 海洋配方在水下（高度为负），两者的过渡带就是**从陆地到水下的斜坡**，
     * 也就是陆架/陆坡的形态。混合结果跨过 0 的位置 = 实际水边线。</p>
     *
     * <p>配对：每个海岸配方接它<b>地理上相连</b>的海洋地形
     * （沙滩接陆架沙脊、崖岸接深海平原/峡谷、三角洲接陆架…）。</p>
     */
    private void buildCoastSea(int b) {
        // {海岸配方, 海洋配方, 权重}
        // ★ 权重用于压低"占比过大的海岸类型"（2026-11-01 看图后）：
        //   礁盘有两条配对（接海山群 / 接陆架），若都取 1.0，它在海岸带的面积
        //   是其它海岸的两倍 ⇒ 实测水占 77–81%、陆地被压得太薄。
        //   降到 0.55 后礁盘占比约等于其它海岸类型。
        //   配对本身保留（礁盘接海山群/陆架都是合理的地理组合），只是频率降低。
        Object[][] pairs = {
                {CoastRecipeCatalog.Recipe.SANDY_BEACH, OceanRecipeCatalog.Recipe.SHELF_RIDGE,      1.0},
                {CoastRecipeCatalog.Recipe.SANDY_BEACH, OceanRecipeCatalog.Recipe.ABYSSAL_PLAIN,    1.0},
                {CoastRecipeCatalog.Recipe.SEA_CLIFF,   OceanRecipeCatalog.Recipe.ABYSSAL_PLAIN,    1.0},
                {CoastRecipeCatalog.Recipe.SEA_CLIFF,   OceanRecipeCatalog.Recipe.CANYON,           1.0},
                {CoastRecipeCatalog.Recipe.REEF_FLAT,   OceanRecipeCatalog.Recipe.SEAMOUNT_FIELD,  0.55},
                {CoastRecipeCatalog.Recipe.REEF_FLAT,   OceanRecipeCatalog.Recipe.SHELF_RIDGE,     0.55},
                {CoastRecipeCatalog.Recipe.TIDAL_FLAT,  OceanRecipeCatalog.Recipe.SHELF_RIDGE,      1.0},
                {CoastRecipeCatalog.Recipe.DELTA,       OceanRecipeCatalog.Recipe.SHELF_RIDGE,      1.0},
                {CoastRecipeCatalog.Recipe.DELTA,       OceanRecipeCatalog.Recipe.ABYSSAL_PLAIN,    1.0},
        };
        int k = 0;
        for (Object[] pr : pairs) {
            final CoastRecipeCatalog.Recipe cr = (CoastRecipeCatalog.Recipe) pr[0];
            final OceanRecipeCatalog.Recipe or = (OceanRecipeCatalog.Recipe) pr[1];
            final double w = (Double) pr[2];

            // alpha 用更宽的拉伸窗口：海岸↔海洋的过零发生在 s=0.5，
            // 窗口窄会让"纯海岸"和"纯海洋"两端的面积被压缩。
            // 取 [0.22, 0.78]：中间 56% 铺满值域，两端各约 22% 饱和 ⇒ 海陆各占近半。
            RefNoise.Node raw = RefNoise.warpPerlin(
                    RefNoise.Perlin.ofScale(b + 1900 + k, 1200f + (k % 5) * 220f, 2),
                    b + 1950 + k, 600f, 2, 300f);
            RefNoise.Node alpha = new RefNoise.Mapped(
                    new RefNoise.Clamped(raw, CS_ALPHA_LO, CS_ALPHA_HI),
                    CS_ALPHA_LO, CS_ALPHA_HI, 0f, 1f);

            coastSea.add(new Transition(cr + "↔" + or, PairKind.COAST_SEA, alpha,
                    (x, z) -> coast.height01(cr, x, z),
                    (x, z) -> ocean.height01(or, x, z), w));
            k++;
        }
    }

    /**
     * 海岸↔海洋的 alpha 拉伸窗口（比其它族更宽）。
     *
     * <p>其它族用 {@code [0.30, 0.70]}；海岸↔海洋用 {@code [0.22, 0.78]},
     * 因为它的两端是"陆上一个薄层"与"水下一个厚层"，
     * 过零点固定 s=0.5 ⇒ 需要更宽的分布才能让两侧都有足够面积。</p>
     */
    static final float CS_ALPHA_LO = 0.22f;
    static final float CS_ALPHA_HI = 0.78f;

    // ============================================================ 工具

    /**
     * alpha 对比度拉伸：{@code Clamped(raw, LO, HI)} → {@code Mapped(..., 0, 1)}。
     *
     * <p>★ 必须两步：{@code RefNoise.Mapped} <b>不做 clamp</b>，只写它会把
     * 窗口外的值线性外推（首版踩过：窗宽 0.12 时把噪声放大 7 倍）。</p>
     */
    private static RefNoise.Node contrast(RefNoise.Node raw) {
        return new RefNoise.Mapped(
                new RefNoise.Clamped(raw, ALPHA_LO, ALPHA_HI),
                ALPHA_LO, ALPHA_HI, 0f, 1f);
    }

    /** alpha 对比度拉伸窗口（实测 Perlin 2oct 的 P10≈0.35 / P90≈0.65）。 */
    static final float ALPHA_LO = 0.30f;
    static final float ALPHA_HI = 0.70f;

    // ============================================================ 陆地档（配对依据）

    /**
     * 陆地成员的"名义高度"（配对依据，不是计算参数）。
     *
     * <p>实测 {@code SplineConfig.landTypeBounds()}（{@code TerrainParams.defaults()}）：</p>
     * <pre>
     *   PLAIN      lo=+0.0050 hi=+0.0300   (hi ≈ +7.1 格)
     *   HILLS      lo=+0.0600 hi=+0.3680   (hi ≈ +86.8 格)
     *   MOUNTAINS  lo=+0.4500 hi=+0.9500   (hi ≈ +224.2 格)
     *   PLATEAU    lo=+0.4100 hi=+0.7060   (hi ≈ +166.6 格)
     *   BASIN      lo=-0.0800 hi=+0.0500   (可低于海平面，用于内陆积水)
     * </pre>
     *
     * <h3>★ BASIN 作为"陆↔海岸"的陆地端时抬到海平面以上</h3>
     * <p>SplineConfig 的 {@code lo=-0.08} 是为了让<b>内陆</b>盆地积水成湖。
     * 但作为<b>接海岸</b>的陆地端时，它必须在水面之上，否则海岸线无处可切
     * （实测首版 BASIN↔SHELF_RIDGE 水面 91%，全是碎斑）。</p>
     * <p>取 {@code [0.01, 0.09]}：仍是五档里最低的陆地，但与海岸/海洋有明确落差。</p>
     */
    enum LandBand {
        BASIN("BASIN", 0.01, 0.09),
        PLAIN("PLAIN", 0.005, 0.03),
        HILLS("HILLS", 0.06, 0.368),
        PLATEAU("PLATEAU", 0.41, 0.706),
        MOUNTAINS("MOUNTAINS", 0.45, 0.95);

        final String label;
        final double lo, hi;

        LandBand(String label, double lo, double hi) {
            this.label = label;
            this.lo = lo;
            this.hi = hi;
        }
    }

    // ============================================================ 访问器

    List<Transition> seaSeaPairs() { return seaSea; }

    List<Transition> landCoastPairs() { return landCoast; }

    List<Transition> coastSeaPairs() { return coastSea; }

    /** 全部过渡条目（海↔海 → 陆↔海岸 → 海岸↔海洋）。 */
    List<Transition> all() {
        List<Transition> out = new ArrayList<>(seaSea.size() + landCoast.size() + coastSea.size());
        out.addAll(seaSea);
        out.addAll(landCoast);
        out.addAll(coastSea);
        return out;
    }

    int size() { return seaSea.size() + landCoast.size() + coastSea.size(); }

    // ============================================================ 诊断

    String summary() {
        StringBuilder sb = new StringBuilder();
        sb.append("OceanTransitionCatalog seed=").append(seed).append('\n');
        sb.append(String.format(Locale.ROOT, "  sea-sea    : %d (auto C(5,2))%n", seaSea.size()));
        sb.append(String.format(Locale.ROOT, "  land-coast : %d (陆地接海岸)%n", landCoast.size()));
        sb.append(String.format(Locale.ROOT, "  coast-sea  : %d (海岸接海洋)%n", coastSea.size()));
        sb.append(String.format(Locale.ROOT, "  total      : %d%n", size()));
        sb.append("  --- sea↔sea ---\n");
        for (Transition t : seaSea) sb.append("    ").append(t.name()).append('\n');
        sb.append("  --- land↔coast ---\n");
        for (Transition t : landCoast) sb.append("    ").append(t.name()).append('\n');
        sb.append("  --- coast↔sea ---\n");
        for (Transition t : coastSea) sb.append("    ").append(t.name()).append('\n');
        return sb.toString();
    }

    private OceanTransitionCatalog() { throw new AssertionError(); }
}
