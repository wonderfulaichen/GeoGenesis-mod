package com.geogenesis.worldgen.terrain.candidate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * {@link RegionalMorphWorld} 的独立判据程序：{@code main(args[0] = outDir)}，
 * 任一判据失败 ⇒ {@code AssertionError} + {@code exit(1)}。
 *
 * <h3>★ 只测既有 API</h3>
 * <p>只调用 {@link RegionalMorphWorld} 已暴露的
 * {@code tileAt / sample / evaluate / hardE / choose / originalLand / merge}，
 * 以及 {@link FullMorphLand}、{@link LocalBoundaryGeometry}、{@link NodeMorphEngine} 的既有入口。
 * <b>不改任何生产/沙箱类，不加 {@code -D}，不插新 seed，不跑整图。</b></p>
 *
 * <h3>判据（每条都记实测值，不靠推理）</h3>
 * <ol>
 *   <li><b>带宽同源</b>：{@code progress} 是<b>单次</b> smoothstep，半宽 120 / 全宽 240，
 *       {@code progress(0)=0.5}、{@code progress(±120)} 到端、{@code |s|>120} 恒 0；
 *       并用"二次 ease 会改变多少个点"证明该反证非空。</li>
 *   <li><b>几何权重 == query</b>：tile 的 bbox 跳过必须与
 *       {@link LocalBoundaryGeometry#query} <b>逐位</b>一致（赢家 / 每格权重 / 归并后条目权重），
 *       且"每格权重 = progress(sd)/Σprogress"这一单次 ease 身份逐位成立。</li>
 *   <li><b>归并器</b>：{@code merge} 等于"按 recipe 累加 → 归一"，和<b>恰</b>为 1，非负，
 *       长度不一致抛异常，单站点 = onehot。</li>
 *   <li><b>纯陆逐位</b>：{@code world.evaluate(x,z, padding18)} 与 {@code originalLand().e(x,z)}
 *       <b>逐位</b>相等（容差 0，不放松）。同段另断言 {@code Σw[0..17]} <b>恰</b>为 1.0 ——
 *       这是 {@link FullMorphLand#normalize} 的<b>归一不变式</b>，
 *       <b>不</b>是（也已不再是）{@code evaluate} 的分支条件。</li>
 *   <li><b>★ landWeight==1 陷阱回归</b>：{@code w[0]=1}、{@code w[18]=1e-18} 时
 *       {@code Σw[0..17]} 与全量求和<b>都</b>恰为 1.0（{@code 1+1e-18} 舍入回 1.0），
 *       但海分量确实为正 ⇒ {@code evaluate} 必须<b>仍走混合语义</b>
 *       （{@code sampleE + landWeight·delta}）而<b>不</b>是 {@code sampleLand}。
 *       这条就是 Gallery 全量导出时 {@code land-only weights required} 的回归。</li>
 *   <li><b>★ 海洋票槽可达性回归</b>：{@code RefNoise.hash2D} 的 {@code hash ^= hash >> 13}
 *       是<b>算术</b>右移 ⇒ 符号位恒 0 ⇒ 返回值是 31 位非负整数（不是 uint32）。
 *       旧 {@code unit = h·2⁻³²} 因此只给出 {@code draw ∈ [0,0.5)}，
 *       {@code (int)(draw·12)} 只能到槽 0..5，<b>后 6 个槽与只占高半区的海洋配方永不出现</b>。
 *       本段反射读实现的 {@code unit()/base()} 独立复算，要求
 *       <b>12 槽全可达</b>、{@code unit max > 0.99}、{@code min < 0.01}、
 *       {@code choose()} 与该复算逐格一致，且<b>5 个海洋配方各被选到过至少一次</b>。</li>
 *   <li><b>纯海无覆盖</b>：纯海 {@code evaluate} 与<b>独立构造</b>的 {@link NodeMorphEngine#sampleE}
 *       逐位相等；并断言覆盖守卫 {@code landWeight>0 && alpha>0} 的命中数为 0。</li>
 *   <li><b>身份可复现</b>：同一 cell 在<b>相邻/重叠</b>几何、在<b>两个独立</b> world 实例上抽签逐位相同；
 *       并<b>逆序</b>重建 {@code recipes[]} 与正序逐位一致（不依赖 world 自己的 choose 缓存）。</li>
 *   <li><b>跨 tile 强取样</b>：tile 边界附近同一点用两侧 tile 各自<b>强制</b>求值
 *       （{@code sample(tile,x,z)}，绕过 {@code tileAt}）差异 ≤ 2e-3；实测如实记录，失败不放宽。</li>
 *   <li><b>边界法向收敛</b>：在 1401 图像范围内枚举原区域边，按 land-land / sea-sea / land-sea
 *       各取候选，沿中点法向取 ±eps（1 / 0.1 / 0.01）：
 *       权重必须收敛（阶跃检测，真判据）；高度只用
 *       "两端 hard 场自身变化 + 权重变化×端点落差" 的上界判断
 *       ⇒ <b>不</b>要求配方自身处处光滑（劣地 Steps、台地 Terrace 的固有台阶落在 hard 项里）。
 *       至少各出现一次 sea-sea 与 land-sea，端点与中点高度/类型一并跟踪。</li>
 *   <li><b>带宽 240 不变</b>：取一条 land-land 边在 ±240 法向剖面上验证"全程仅 2 候选、三者权重为 0"。</li>
 * </ol>
 *
 * <h3>口径约束</h3>
 * <ul>
 *   <li><b>只用正典种子</b>{@link RecipeMorphCatalog#GALLERY_SEED}
 *       （{@link FullMorphLand} 对其它种子直接抛 {@code UnsupportedOperationException}）。</li>
 *   <li>整图渲染由 {@link RegionalMorphGallery} 负责；本类只在 tile 上点采样。</li>
 * </ul>
 */
public final class RegionalMorphWorldCheck {

    // ============================================================ 常量（取自既有类，不复制字面量）

    static final long SEED = RecipeMorphCatalog.GALLERY_SEED;
    static final int LAND = RegionalMorphWorld.LAND_COUNT;
    static final int COUNT = RegionalMorphWorld.COUNT;
    static final double HALF_BAND = LocalBoundaryGeometry.BAND_HALF_BLOCKS;
    static final double TOTAL_BAND = LocalBoundaryGeometry.BAND_TOTAL_BLOCKS;

    static final double IMG_X0 = RegionalMorphGallery.X0;
    static final double IMG_Z0 = RegionalMorphGallery.Z0;
    static final double IMG_STEP = RegionalMorphGallery.STEP;
    static final int IMG_N = RegionalMorphGallery.SIZE;
    static final double IMG_X1 = IMG_X0 + (IMG_N - 1) * IMG_STEP;
    static final double IMG_Z1 = IMG_Z0 + (IMG_N - 1) * IMG_STEP;

    static final double[] EPS = {1.0, 0.1, 0.01};
    static final double TOL_XTILE_WEIGHT = 2e-3;
    static final double TOL_XTILE_E = 2e-3;
    static final double TOL_PURE = 0.0;
    /**
     * {@link FullMorphLand#normalize} <b>自己声明</b>的求和契约 {@code |Σw−1| ≤ 1e-12}。
     *
     * <p>⚠ 这条<b>不是</b>我给归并器放松的容差，而是既有模块写明的契约：
     * {@code normalize} 用一次性 {@code w[argmax] += 1.0 - s} 补残差，而 {@code x + (1−s)} 自身
     * 会再舍入，所以结果可能停在 {@code 1±1ULP} 而不<b>恰</b>为 1。
     * {@link NodeMorphEngine.Evaluation} 只要求 {@code |Σw−1| ≤ 1e-9}，即生产链路本就按这个契约工作。</p>
     * <p>★ <b>"恰为 1"不是分支条件</b>（曾经是，已被一次真实缺陷推翻）：早期
     * {@code RegionalMorphWorld.evaluate} 用 {@code landWeight == 1} 判纯陆，而这条判据
     * <b>漏掉</b> {@code w[0]=1, w[18]=1e-18} 这种"求和恰为 1 但海分量确为正"的向量
     * ⇒ 误走 {@link NodeMorphEngine#sampleLand} 并抛 {@code land-only weights required}。
     * 修好之后纯陆判据看的是<b>是否真有正的海洋分量</b>。
     * 所以 pure-land 段对 {@code Σw[0..17]} 的"恰为 1"断言现在只是<b>归一不变式</b>的记录，
     * 真正的回归在 {@code sectionLandWeightOneTrap}。</p>
     */
    static final double NORMALIZE_SLACK = 1e-12;
    static final double CONVERGE_RATIO = 0.25;
    static final double CONVERGE_ABS = 1e-6;
    static final int CAND_PER_KIND = 3;
    static final int GRID = 7;
    /** 网格半径比例：0.68·728·√2 ≈ 700 < 728 ⇒ 全部点落在声明查询圆内。 */
    static final double GRID_SPAN = 0.68;
    static final int PURE_CAP = 60;
    static final double NORMAL_HALF = 240.0;
    static final double BAND_STEP = 0.5;
    /** 240 带宽剖面的法向半跨度（块）：比 240 宽，才能看到两侧饱和。 */
    static final double BAND_PROFILE_HALF = 360.0;
    /** 每条 land-land 候选边上尝试的剖面原点（沿折线均匀取 12 个内点）。 */
    static final int[] EDGE_ALONG = {0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11};
    /** band240 最多尝试的 land-land 候选数（防止在病态几何上长时间扫描）。 */
    static final int BAND_CANDIDATE_CAP = 40;
    /** 需要更大候选池的边界类（只有 land-land 需要，用于 240 带宽剖面）。 */
    static final List<String> BAND_CAP_KINDS = List.of("land-land");
    /** 海洋槽可达性回归的格坐标半宽（{@code cellX/cellZ ∈ [-50,50]}）。 */
    static final int OCEAN_SLOT_CELL_SPAN = 50;
    /** 判据 3「5 个海洋配方都被选到过」用的更宽格坐标半宽。 */
    static final int OCEAN_RECIPE_CELL_SPAN = 200;
    /**
     * 供 {@code choose} 合成站点用的几何查询半径（块）。
     *
     * <p>合成站点集中在 {@code cellX/cellZ ∈ [-200,200]}，u 平面坐标因此也只落在
     * {@code (-200,200)} 格内；半径取 900 时，反 warp 牛顿的初值离解很近、残差容易达标，
     * 且不依赖站点是否被 {@code selectSites()} 收进场景（{@code choose} 只用几何的
     * {@code warpInverse} 与 {@code slotOfCell}，不用站点表）。</p>
     */
    static final double OCEAN_CHOOSE_RADIUS = 900.0;

    static final String[] KINDS = {"land-land", "sea-sea", "land-sea"};
    static final String[] CLASSES = {"land_single", "land_land", "sea_single", "sea_sea",
            "mixed_land_sea", "degenerate"};

    // ============================================================ 台账

    private static int checks;
    private static int failures;
    private static String firstFailure;
    private static final List<String> FAILURE_ROWS = new ArrayList<>();
    private static final int MAX_FAILURE_ROWS = 200;
    private static final Map<String, Double> METRICS = new LinkedHashMap<>();
    private static final List<String> NOTES = new ArrayList<>();

    private static void require(boolean ok, String message) {
        checks++;
        if (!ok) {
            failures++;
            if (firstFailure == null) {
                firstFailure = message;
            }
            record("assert", message);
        }
    }

    private static void record(String kind, String message) {
        if (FAILURE_ROWS.size() < MAX_FAILURE_ROWS) {
            FAILURE_ROWS.add(kind + ",\"" + message.replace('"', '\'').replace('\n', ' ') + "\"");
        }
    }

    private static void metric(String key, double value) {
        METRICS.put(key, value);
    }

    private static void note(String text) {
        NOTES.add(text);
    }

    // ============================================================ 入口

    public static void main(String[] args) {
        Path out = args.length > 0 ? Path.of(args[0]) : Path.of("build/regional-world-p3/check");
        try {
            Locale.setDefault(Locale.ROOT);
            Files.createDirectories(out);
            run(out);
            System.out.println("PASS checks=" + checks + " out=" + out.toAbsolutePath());
        } catch (Throwable t) {
            t.printStackTrace();
            try {
                Files.createDirectories(out);
                finish(out);
            } catch (Exception ignored) {
                // 台账尽力而为：写盘失败不得掩盖原始断言。
            }
            System.out.println("FAIL " + t);
            System.exit(1);
        }
    }

    private static void run(Path out) throws Exception {
        Context c = new Context();
        stage(out, "constants", () -> sectionConstants(out, c));
        stage(out, "geometry", () -> sectionGeometry(out, c));
        stage(out, "merger", () -> sectionMerger(out, c));
        Sweep sweep = sectionSweep(out, c);
        stage(out, "pure-land", () -> sectionPureLand(out, c, sweep));
        stage(out, "landweight1-trap", () -> sectionLandWeightOneTrap(out, c));
        stage(out, "ocean-slots", () -> sectionOceanSlots(out, c));
        stage(out, "pure-sea", () -> sectionPureSea(out, c, sweep));
        stage(out, "identity", () -> sectionIdentity(out, c));
        stage(out, "tile-boundary", () -> sectionTileBoundary(out, c));
        stage(out, "boundaries", () -> sectionBoundaries(out, c, sweep));
        finish(out);
        if (failures > 0) {
            throw new AssertionError(failures + " gate(s) failed; first: " + firstFailure);
        }
    }

    private interface Stage {
        void run() throws Exception;
    }

    private static void stage(Path out, String name, Stage body) throws Exception {
        long t0 = System.nanoTime();
        body.run();
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        try {
            Path p = out.resolve("section-times.txt");
            Files.writeString(p, name + "," + ms + "\n", StandardCharsets.UTF_8,
                    Files.exists(p) ? java.nio.file.StandardOpenOption.APPEND
                            : java.nio.file.StandardOpenOption.CREATE);
        } catch (Exception ignored) {
            // 计时写盘失败不得影响判据。
        }
        System.out.println("section " + name + " " + ms + "ms checks=" + checks
                + " failures=" + failures);
    }

    // ============================================================ 上下文

    /** 两个<b>独立</b>的 RegionalMorphWorld（A 提供 tile 缓存，B 只做独立抽签）+ 一个独立节点内核。 */
    private static final class Context {
        final RecipeMorphCatalog catalog;
        final RegionalMorphWorld worldA;
        final RegionalMorphWorld worldB;
        final FullMorphLand land;
        final NodeMorphEngine engine;

        Context() {
            catalog = new RecipeMorphCatalog(SEED);
            worldA = new RegionalMorphWorld(catalog);
            worldB = new RegionalMorphWorld(catalog);
            land = worldA.originalLand();
            engine = new NodeMorphEngine(MorphCatalog.world(catalog,
                    new OceanRecipeCatalog(SEED + OceanRecipeCatalog.SEED_OFFSET, true)), SEED);
        }
    }

    // ============================================================ 1. 常量 / 带宽

    private static void sectionConstants(Path out, Context c) throws Exception {
        List<String> csv = new ArrayList<>(List.of("constant,value,source,expected,ok"));
        BandFacts f = checkConstantsAndBand(c);
        writeConstantsCsv(csv, f);
        write(out, "constants.csv", csv);
        writeConstantsMetrics(f);
    }

    /** 常量/带宽判据的全部实测值（{@link #checkConstantsAndBand} 的产物）。 */
    private record BandFacts(double p0, double pPlus, double pMinus, double edgePlus,
                             double edgeMinus, double worstShape, int doubleEaseDiffers,
                             int shapePoints, double sea0, double seaFull) { }

    /**
     * 常量与带宽的<b>全部</b> {@code require}。
     *
     * <p>⚠ 方法内 {@code require} 的<b>条数与顺序</b>就是 checks 计数：拆动这里会移动总数。</p>
     */
    private static BandFacts checkConstantsAndBand(Context c) {
        require(c.catalog.seed() == SEED, "catalog seed != canonical");
        require(LAND == RecipeMorphCatalog.ENTRY_COUNT, "LAND_COUNT != 18");
        require(COUNT == LAND + OceanRecipeCatalog.ENTRY_COUNT, "COUNT != LAND + ocean entries");
        require(c.land != null, "originalLand() returned null");
        require(HALF_BAND == 120.0, "band half width moved: " + HALF_BAND);
        require(TOTAL_BAND == 240.0, "band total width moved: " + TOTAL_BAND);

        double p0 = LocalBoundaryGeometry.progress(0.0);
        double pPlus = LocalBoundaryGeometry.progress(HALF_BAND);
        double pMinus = LocalBoundaryGeometry.progress(-HALF_BAND);
        require(Double.compare(p0, 0.5) == 0, "progress(0) != 0.5: " + p0);
        require(Double.compare(pPlus, 1.0) == 0, "progress(+120) != 1: " + pPlus);
        require(Double.compare(pMinus, 0.0) == 0, "progress(-120) != 0: " + pMinus);
        // ★ progress 的真实语义（读源码 + 实测）：t = clamp(0.5 + s/240, 0, 1)，
        // 返回 smoothstep(t)。所以它<b>单调递增并在两端饱和</b>：
        //   s ≤ −120 ⇒ 0     s = 0 ⇒ 0.5     s ≥ +120 ⇒ 1
        // 即"带内 (0,1)，两侧各饱和到 0 / 1"（不是两侧都归零）。
        // 这正是 weights() 需要的方向：赢家 sd>0 → progress 趋 1，其余 sd<0 → 趋 0。
        // ⚠ 早期把"两侧都归零"当判据是本检查自己的错，不是实现的问题。
        double farIn = HALF_BAND * 10;
        require(LocalBoundaryGeometry.progress(farIn) == 1.0,
                "progress(+10·120) is not exactly 1: " + LocalBoundaryGeometry.progress(farIn));
        require(LocalBoundaryGeometry.progress(-farIn) == 0.0,
                "progress(-10·120) is not exactly 0: " + LocalBoundaryGeometry.progress(-farIn));
        require(LocalBoundaryGeometry.progress(HALF_BAND - 1e-6) < 1.0,
                "progress(+120−eps) is already saturated: the band is narrower than 240");
        require(LocalBoundaryGeometry.progress(-HALF_BAND + 1e-6) > 0.0,
                "progress(-120+eps) is already zero: the band is narrower than 240");
        double edgePlus = measureSaturationEdge(+1);
        double edgeMinus = measureSaturationEdge(-1);
        require(Math.abs(edgePlus - HALF_BAND) <= 1e-6,
                "measured +band edge " + edgePlus + " != " + HALF_BAND);
        require(Math.abs(edgeMinus + HALF_BAND) <= 1e-6,
                "measured −band edge " + edgeMinus + " != " + (-HALF_BAND));

        double worstShape = 0;
        int doubleEaseDiffers = 0;
        int shapePoints = 0;
        for (int k = -480; k <= 480; k++) {
            double s = k * 0.5;
            double t = Math.max(0, Math.min(1, 0.5 + s / TOTAL_BAND));
            double expect = t * t * (3 - 2 * t);
            double got = LocalBoundaryGeometry.progress(s);
            worstShape = Math.max(worstShape, Math.abs(got - expect));
            shapePoints++;
            if (Math.abs(got * got * (3 - 2 * got) - got) > 1e-3) {
                doubleEaseDiffers++;
            }
        }
        require(worstShape == 0.0, "progress() is not the single smoothstep(0.5+s/240): " + worstShape);
        require(doubleEaseDiffers > 0, "no point distinguishes a single ease from a double ease"
                + " -> the no-double-ease gate would be vacuous");

        double sea0 = RegionalMorphWorld.seaProbability(0.0);
        double seaFull = RegionalMorphWorld.seaProbability(-UnifiedWorld.OCEAN_C);
        require(sea0 == 0.0, "seaProbability(0) != 0");
        require(seaFull == 1.0, "seaProbability(-OCEAN_C) != 1");
        return new BandFacts(p0, pPlus, pMinus, edgePlus, edgeMinus, worstShape,
                doubleEaseDiffers, shapePoints, sea0, seaFull);
    }

    private static void writeConstantsCsv(List<String> csv, BandFacts f) {
        csv.add(row("BAND_HALF_BLOCKS", HALF_BAND, "LocalBoundaryGeometry", "120", HALF_BAND == 120.0));
        csv.add(row("BAND_TOTAL_BLOCKS", TOTAL_BAND, "LocalBoundaryGeometry", "240", TOTAL_BAND == 240.0));
        csv.add(row("progress(0)", f.p0(), "LocalBoundaryGeometry", "0.5", f.p0() == 0.5));
        csv.add(row("progress(+120)", f.pPlus(), "LocalBoundaryGeometry", "1", f.pPlus() == 1.0));
        csv.add(row("progress(-120)", f.pMinus(), "LocalBoundaryGeometry", "0", f.pMinus() == 0.0));
        csv.add(row("measured_band_edge_plus", f.edgePlus(), "bisection on progress()", "120.0",
                Math.abs(f.edgePlus() - HALF_BAND) <= 1e-6));
        csv.add(row("measured_band_edge_minus", f.edgeMinus(), "bisection on progress()", "-120.0",
                Math.abs(f.edgeMinus() + HALF_BAND) <= 1e-6));
        csv.add(row("progress_shape_points", f.shapePoints(), "identity vs smoothstep", "0 error",
                f.worstShape() == 0.0));
        csv.add(row("double_ease_would_differ", f.doubleEaseDiffers(), "p -> p*p*(3-2p)", ">0", true));
        csv.add(row("seaProbability(0)", f.sea0(), "RegionalMorphWorld", "0", f.sea0() == 0.0));
        csv.add(row("seaProbability(-OCEAN_C)", f.seaFull(), "RegionalMorphWorld", "1",
                f.seaFull() == 1.0));
        csv.add(row("LAND_COUNT", LAND, "RegionalMorphWorld", "18",
                LAND == RecipeMorphCatalog.ENTRY_COUNT));
        csv.add(row("COUNT", COUNT, "RegionalMorphWorld", "23",
                COUNT == LAND + OceanRecipeCatalog.ENTRY_COUNT));
        csv.add(row("IMAGE_X0", IMG_X0, "RegionalMorphGallery", IMG_X0 + "", true));
        csv.add(row("IMAGE_X1", IMG_X1, "RegionalMorphGallery", IMG_X1 + "", true));
        csv.add(row("IMAGE_Z0", IMG_Z0, "RegionalMorphGallery", IMG_Z0 + "", true));
        csv.add(row("IMAGE_Z1", IMG_Z1, "RegionalMorphGallery", IMG_Z1 + "", true));
    }

    private static void writeConstantsMetrics(BandFacts f) {
        metric("constants.progress_shape_points", f.shapePoints());
        metric("constants.max_abs_progress_shape_error", f.worstShape());
        metric("constants.double_ease_would_differ_points", f.doubleEaseDiffers());
        metric("constants.measured_band_edge_plus", f.edgePlus());
        metric("constants.measured_band_edge_minus", f.edgeMinus());
    }

    /**
     * 二分出 {@code progress} 在 {@code sign} 方向首次饱和的 {@code s}（= 实际带宽半宽）。
     *
     * <p>用来<b>实测</b>带宽，而不是假定它恰为 120。</p>
     *
     * @param sign {@code +1} 找 +120（首次达到 1），{@code -1} 找 −120（首次降到 0）
     */
    private static double measureSaturationEdge(int sign) {
        double lo = 0.0;
        double hi = HALF_BAND * 2;
        boolean saturated = sign > 0
                ? LocalBoundaryGeometry.progress(hi) == 1.0
                : LocalBoundaryGeometry.progress(-hi) == 0.0;
        if (!saturated) {
            return Double.NaN;
        }
        for (int i = 0; i < 200; i++) {
            double mid = 0.5 * (lo + hi);
            boolean sat = sign > 0
                    ? LocalBoundaryGeometry.progress(mid) == 1.0
                    : LocalBoundaryGeometry.progress(-mid) == 0.0;
            if (sat) {
                hi = mid;
            } else {
                lo = mid;
            }
            if (hi - lo <= 1e-13) {
                break;
            }
        }
        return sign > 0 ? hi : -hi;
    }

    // ============================================================ 2. 几何权重 == query

    private static void sectionGeometry(Path out, Context c) throws Exception {
        List<String> csv = new ArrayList<>(List.of(
                "tileX,tileZ,points,sites,winner_mismatch,max_site_weight_diff,site_bitwise,"
                        + "max_merged_diff,merged_bitwise,max_progress_ratio_diff,ratio_bitwise,ratio_points,"
                        + "query_radius,in_circle"));
        int[] totals = new int[3];
        double[] worst = new double[3];
        for (int[] tile : new int[][]{{0, 0}, {1, 0}, {0, 1}}) {
            TileProbe p = probeOneTile(c, tile[0], tile[1], csv);
            totals[0] += p.winnerMismatch();
            totals[1] += p.ratioBitwise();
            totals[2] += p.ratioPoints();
            worst[0] = Math.max(worst[0], p.site());
            worst[1] = Math.max(worst[1], p.merged());
            worst[2] = Math.max(worst[2], p.ratio());
        }
        require(totals[2] > 0, "no point with a usable signed-distance set: the ratio identity"
                + " could not be checked at all");
        require(totals[1] == totals[2], "site weights are not progress(sd)/sum bitwise: "
                + (totals[2] - totals[1]) + "/" + totals[2] + " points differ (max " + worst[2]
                + ") -> a second easing layer or an extra normalisation step is in play");
        write(out, "geometry-parity.csv", csv);
        metric("geometry.points", probedPoints);
        metric("geometry.winner_mismatches", totals[0]);
        metric("geometry.max_site_weight_diff_vs_query", worst[0]);
        metric("geometry.max_merged_weight_diff_vs_query", worst[1]);
        metric("geometry.max_progress_ratio_diff", worst[2]);
        metric("geometry.ratio_bitwise_points", totals[1]);
    }

    /** 所有 tile 累加后的比值判据可评点数（{@link #sectionGeometry} 与探针共享）。 */
    private static int probedPoints;

    /** 单个 tile 的几何对拍结果。 */
    private record TileProbe(int winnerMismatch, int ratioBitwise, int ratioPoints,
                             double site, double merged, double ratio) { }

    /**
     * 一个 tile 的几何权重对拍（{@code weights()} vs {@link LocalBoundaryGeometry#query}）。
     *
     * <p>⚠ 内部 {@code require} 的条数与顺序即 checks 计数，勿动。</p>
     */
    private static TileProbe probeOneTile(Context c, int tx, int tz, List<String> csv) {
        RegionalMorphWorld.RegionTile rt =
                c.worldA.tileAt(FullMorphLand.center(tx), FullMorphLand.center(tz));
        FullMorphLand.Tile t = rt.original();
        LocalBoundaryGeometry g = t.geometry();
        Random r = new Random(20261101L + tx * 31L + tz);
        int n = 120;
        int localWinner = 0;
        int localRatioPoints = 0;
        int localRatioBitwise = 0;
        double localSite = 0;
        double localMerged = 0;
        double localRatio = 0;
        boolean siteBitwise = true;
        boolean mergedBitwise = true;
        boolean inCircle = true;
        for (int i = 0; i < n; i++) {
            double ang = r.nextDouble() * Math.PI * 2;
            double rad = Math.sqrt(r.nextDouble()) * g.queryRadius() * 0.95;
            double x = t.centerX() + Math.cos(ang) * rad;
            double z = t.centerZ() + Math.sin(ang) * rad;
            inCircle &= Math.hypot(x - t.centerX(), z - t.centerZ()) <= g.queryRadius();
            double[] mine = t.weights(x, z);
            LocalBoundaryGeometry.Sample q = g.query(x, z);
            if (mine.length != q.weight.length) {
                require(false, "site count mismatch between tile and query");
                continue;
            }
            probedPoints++;
            if (t.winnerIndex(x, z) != q.winnerIndex()) {
                localWinner++;
            }
            double[] wDiff = weightAndMergeDiff(t, mine, q, localSite, localMerged);
            localSite = Math.max(localSite, wDiff[0]);
            localMerged = Math.max(localMerged, wDiff[1]);
            for (int k = 0; k < mine.length; k++) {
                siteBitwise &= Double.compare(mine[k], q.weight[k]) == 0;
            }
            mergedBitwise &= wDiff[2] == 0.0;
            double[] ratio = ratioIdentity(q);
            if (ratio != null) {
                localRatioPoints++;
                localRatio = Math.max(localRatio, ratio[0]);
                if (ratio[1] == 0.0) {
                    localRatioBitwise++;
                }
            }
        }
        require(localWinner == 0, "tile winner != geometry.query winner at " + localWinner
                + " points in tile (" + tx + "," + tz + ")");
        require(localSite == 0.0, "tile site weights differ from query by " + localSite
                + " in tile (" + tx + "," + tz + ")");
        require(mergedBitwise, "merged entry weights differ (max " + localMerged
                + ") between tile path and query path");
        require(inCircle, "probe points escaped the declared query radius");
        csv.add(String.format(Locale.ROOT, "%d,%d,%d,%d,%d,%.3e,%s,%.3e,%s,%.3e,%d,%d,%.1f,%s",
                tx, tz, n, t.siteCount(), localWinner, localSite, siteBitwise,
                localMerged, mergedBitwise, localRatio, localRatioBitwise, localRatioPoints,
                g.queryRadius(), inCircle));
        return new TileProbe(localWinner, localRatioBitwise, localRatioPoints, localSite,
                localMerged, localRatio);
    }

    /**
     * 站点权重差 / 归并后权重差 / 归并是否逐位（{@code {siteDiff, mergedDiff, mergedMismatchFlag}}）。
     */
    private static double[] weightAndMergeDiff(FullMorphLand.Tile t, double[] mine,
                                               LocalBoundaryGeometry.Sample q,
                                               double prevSite, double prevMerged) {
        double site = prevSite;
        for (int k = 0; k < mine.length; k++) {
            site = Math.max(site, Math.abs(mine[k] - q.weight[k]));
        }
        double[] m1 = RegionalMorphWorld.merge(t.entryOfSite(), mine);
        double[] m2 = RegionalMorphWorld.merge(t.entryOfSite(), q.weight);
        double merged = prevMerged;
        for (int k = 0; k < COUNT; k++) {
            merged = Math.max(merged, Math.abs(m1[k] - m2[k]));
        }
        return new double[]{site, merged, maxDiff(m1, m2)};
    }

    /**
     * 单次抽样上"每格权重 == progress(sd)/Σprogress"的偏差与是否逐位。
     *
     * @return {@code {maxAbsDiff, mismatchFlag}}；该点无可评的 sd 集时返回 {@code null}
     */
    private static double[] ratioIdentity(LocalBoundaryGeometry.Sample q) {
        double[] pr = new double[q.weight.length];
        double sum = 0;
        for (int k = 0; k < pr.length; k++) {
            pr[k] = LocalBoundaryGeometry.progress(q.signedDistance[k]);
            sum += pr[k];
        }
        if (!(sum > 0) || Double.isNaN(q.signedDistance[q.winnerIndex()])) {
            return null;
        }
        double worst = 0;
        boolean bitwise = true;
        for (int k = 0; k < pr.length; k++) {
            double expect = pr[k] / sum;
            worst = Math.max(worst, Math.abs(q.weight[k] - expect));
            bitwise &= Double.compare(q.weight[k], expect) == 0;
        }
        return new double[]{worst, bitwise ? 0.0 : 1.0};
    }

    // ============================================================ 3. 归并器

    private static void sectionMerger(Path out, Context c) throws Exception {
        List<String> csv = new ArrayList<>(List.of("case,cases,max_abs_diff,sum_error,bitwise,note"));
        int cases = 300;
        MergerTotals t = accumulateMergerCases(cases);
        checkMergerEdges();
        writeMergerCsv(csv, t, cases);
        write(out, "merger.csv", csv);
        writeMergerMetrics(t, cases);
    }

    /** 归并器 300 例累加出来的极值与"恰为 1"的例数。 */
    private record MergerTotals(double worst, double worstSum, double worstReversed, double worstAbs,
                                int exactSum) { }

    /** 300 例随机权重：{@code merge} 必须逐位等于手工累加+归一，和落在契约内，非负。 */
    private static MergerTotals accumulateMergerCases(int cases) {
        Random r = new Random(555001);
        double worst = 0;
        double worstSum = 0;
        double worstReversed = 0;
        double worstAbs = 0;
        int exactSum = 0;
        for (int i = 0; i < cases; i++) {
            int n = 1 + r.nextInt(8);
            int[] ids = new int[n];
            double[] w = new double[n];
            for (int k = 0; k < n; k++) {
                ids[k] = r.nextInt(COUNT);
                w[k] = r.nextDouble();
            }
            double[] got = RegionalMorphWorld.merge(ids, w);
            double[] expect = new double[COUNT];
            for (int k = 0; k < n; k++) {
                expect[ids[k]] += w[k];
            }
            FullMorphLand.normalize(expect);
            double d = maxDiff(got, expect);
            double sumErr = Math.abs(inOrderSum(got, 0, COUNT) - 1.0);
            worst = Math.max(worst, d);
            worstSum = Math.max(worstSum, sumErr);
            worstAbs = Math.max(worstAbs, sumErr);
            require(d == 0.0, "merge != manual accumulate+normalize at case " + i + ": " + d);
            require(sumErr <= NORMALIZE_SLACK, "merge output deviates from sum 1 by " + sumErr
                    + " > " + NORMALIZE_SLACK + " at case " + i);
            for (int k = 0; k < COUNT; k++) {
                require(got[k] >= 0.0, "merge produced a negative weight at case " + i);
            }
            int[] rids = new int[n];
            double[] rw = new double[n];
            for (int k = 0; k < n; k++) {
                rids[k] = ids[n - 1 - k];
                rw[k] = w[n - 1 - k];
            }
            double[] rev = RegionalMorphWorld.merge(rids, rw);
            worstReversed = Math.max(worstReversed, maxDiff(got, rev));
            require(Math.abs(inOrderSum(rev, 0, COUNT) - 1.0) <= NORMALIZE_SLACK,
                    "reversed-input merge deviates from sum 1 at case " + i);
            if (Double.compare(inOrderSum(got, 0, COUNT), 1.0) == 0) {
                exactSum++;
            }
        }
        return new MergerTotals(worst, worstSum, worstReversed, worstAbs, exactSum);
    }

    /** 归并器的两个边界：长度不一致必须抛，单站点必须就是 onehot。 */
    private static void checkMergerEdges() {
        boolean threw = false;
        try {
            RegionalMorphWorld.merge(new int[]{1, 2}, new double[]{1.0});
        } catch (IllegalArgumentException expected) {
            threw = true;
        }
        require(threw, "merge accepted mismatched ids/weights lengths");
        double[] one = RegionalMorphWorld.merge(new int[]{7}, new double[]{1.0});
        require(one[7] == 1.0 && inOrderSum(one, 0, COUNT) == 1.0, "single-site merge is not onehot");
    }

    private static void writeMergerCsv(List<String> csv, MergerTotals t, int cases) {
        csv.add(String.format(Locale.ROOT,
                "merge_vs_manual,%d,%.3e,%.3e,true,bitwise equal to manual accumulate+normalize",
                cases, t.worst(), t.worstSum()));
        csv.add(String.format(Locale.ROOT,
                "merge_sum_contract,%d,%.3e,%.3e,%s,|sum-1| <= %.0e per FullMorphLand.normalize;"
                        + " exact 1.0 at %d/%d cases", cases, t.worstSum(), t.worstAbs(),
                t.worstAbs() <= NORMALIZE_SLACK, NORMALIZE_SLACK, t.exactSum(), cases));
        csv.add(String.format(Locale.ROOT,
                "merge_reversed_input,%d,%.3e,0,false,reported only (float addition order);"
                        + " |sum-1| stays inside %.0e", cases, t.worstReversed(), NORMALIZE_SLACK));
        csv.add("merge_length_guard,1,0,0,true,IllegalArgumentException");
        csv.add("merge_single_site_onehot,1,0,0,true,weight[index]==1 and sum==1 exactly");
    }

    private static void writeMergerMetrics(MergerTotals t, int cases) {
        metric("merger.cases", cases);
        metric("merger.exact_sum_cases", t.exactSum());
        metric("merger.max_abs_diff_vs_manual", t.worst());
        metric("merger.max_sum_deviation", t.worstSum());
        metric("merger.max_reversed_input_diff", t.worstReversed());
        note("merger: bitwise equal to manual accumulate+normalize on " + cases + " cases;"
                + " |sum-1| max=" + t.worstSum() + " (contract " + NORMALIZE_SLACK + "), exact 1.0 at "
                + t.exactSum() + "/" + cases + ". Nothing here branches on 'sum == 1' any more:"
                + " the merger's gate is FullMorphLand.normalize's own 1e-12 contract");
    }

    // ============================================================ 4. 扫描

    /** 扫描产物：分类计数 + 纯陆/纯海证据点 + 各类型的边界候选。 */
    private static final class Sweep {
        final Map<String, Integer> counts = new LinkedHashMap<>();
        final int[] winnerHistogram = new int[COUNT];
        final int[] dominantHistogram = new int[COUNT];
        final List<double[]> pureLand = new ArrayList<>();
        final List<double[]> pureSea = new ArrayList<>();
        final List<List<Candidate>> byKind = new ArrayList<>();
        long samples;
        long nonFinite;
        int tiles;
        double maxSeaWeight;

        Sweep() {
            for (String k : CLASSES) {
                counts.put(k, 0);
            }
            for (int i = 0; i < KINDS.length; i++) {
                byKind.add(new ArrayList<>());
            }
        }

        List<Candidate> of(String kind) {
            for (int i = 0; i < KINDS.length; i++) {
                if (KINDS[i].equals(kind)) {
                    return byKind.get(i);
                }
            }
            throw new IllegalArgumentException(kind);
        }

        int count(String cls) {
            Integer v = counts.get(cls);
            return v == null ? 0 : v;
        }
    }

    /** 一条候选原区域边：中点 + 单位法向 + 两端 recipe + 两个 cell 身份。 */
    private static final class Candidate {
        String kind;
        int tileX;
        int tileZ;
        int edgeIndex;
        double x;
        double z;
        double nx;
        double nz;
        int recipeA;
        int recipeB;
        String cellA;
        String cellB;
    }

    private static Sweep sectionSweep(Path out, Context c) throws Exception {
        Sweep sweep = new Sweep();
        int tx0 = FullMorphLand.tileIndex(IMG_X0);
        int tx1 = FullMorphLand.tileIndex(IMG_X1 - 1e-9);
        int tz0 = FullMorphLand.tileIndex(IMG_Z0);
        int tz1 = FullMorphLand.tileIndex(IMG_Z1 - 1e-9);
        for (int tz = tz0; tz <= tz1; tz++) {
            for (int tx = tx0; tx <= tx1; tx++) {
                RegionalMorphWorld.RegionTile rt =
                        c.worldA.tileAt(FullMorphLand.center(tx), FullMorphLand.center(tz));
                sweep.tiles++;
                gridSamples(c, rt, sweep);
                collectCandidates(rt, tx, tz, sweep);
            }
            System.out.println("sweep tz=" + tz + " tiles=" + sweep.tiles + " samples=" + sweep.samples
                    + " " + sweep.counts);
        }
        require(sweep.nonFinite == 0, "non-finite e at " + sweep.nonFinite + " of " + sweep.samples
                + " sweep samples");
        require(sweep.samples > 0, "sweep produced no samples");
        require(sweep.count("mixed_land_sea") > 0,
                "no mixed land-sea sample: sea weight never lands strictly inside (0,1)");
        require(sweep.count("sea_sea") > 0, "no sea-sea sample (two ocean recipes active at once)");
        require(sweep.count("land_land") > 0, "no land-land sample (two land recipes active at once)");
        require(sweep.count("land_single") > 0, "no single-recipe land sample");
        require(!sweep.pureLand.isEmpty(), "no pure-land evidence point collected");
        require(!sweep.pureSea.isEmpty(), "no pure-sea evidence point collected");

        List<String> csv = new ArrayList<>(List.of("tiles,samples,class,count,fraction"));
        for (Map.Entry<String, Integer> e : sweep.counts.entrySet()) {
            csv.add(String.format(Locale.ROOT, "%d,%d,%s,%d,%.6f", sweep.tiles, sweep.samples,
                    e.getKey(), e.getValue(), (double) e.getValue() / sweep.samples));
        }
        write(out, "counts.csv", csv);

        List<String> hist = new ArrayList<>(List.of("index,is_land,id,winner_samples,dominant_samples"));
        for (int i = 0; i < COUNT; i++) {
            String id = i < LAND ? c.catalog.entry(i).id() : "OCEAN_" + (i - LAND);
            hist.add(String.format(Locale.ROOT, "%d,%s,%s,%d,%d", i, i < LAND, id,
                    sweep.winnerHistogram[i], sweep.dominantHistogram[i]));
        }
        write(out, "recipe-histogram.csv", hist);

        metric("sweep.tiles", sweep.tiles);
        metric("sweep.samples", sweep.samples);
        metric("sweep.land_single", sweep.count("land_single"));
        metric("sweep.land_land", sweep.count("land_land"));
        metric("sweep.sea_single", sweep.count("sea_single"));
        metric("sweep.sea_sea", sweep.count("sea_sea"));
        metric("sweep.mixed_land_sea", sweep.count("mixed_land_sea"));
        metric("sweep.degenerate", sweep.count("degenerate"));
        metric("sweep.max_sea_weight", sweep.maxSeaWeight);
        note("counts: " + sweep.counts + " over " + sweep.samples + " samples in " + sweep.tiles
                + " tiles");
        return sweep;
    }

    /** 一个 tile 内 GRID×GRID 采样：分类计数 + 收集纯陆/纯海证据点。 */
    private static void gridSamples(Context c, RegionalMorphWorld.RegionTile rt, Sweep sweep) {
        FullMorphLand.Tile t = rt.original();
        for (int j = 0; j < GRID; j++) {
            for (int i = 0; i < GRID; i++) {
                double fx = ((i + 0.5) / GRID * 2 - 1) * GRID_SPAN;
                double fz = ((j + 0.5) / GRID * 2 - 1) * GRID_SPAN;
                double x = t.centerX() + fx * FullMorphLand.QUERY_RADIUS;
                double z = t.centerZ() + fz * FullMorphLand.QUERY_RADIUS;
                RegionalMorphWorld.Sample s = c.worldA.sample(rt, x, z);
                sweep.samples++;
                if (!Double.isFinite(s.e())) {
                    sweep.nonFinite++;
                    continue;
                }
                double[] w = s.weights();
                int landActive = 0;
                int seaActive = 0;
                for (int k = 0; k < LAND; k++) {
                    if (w[k] != 0.0) {
                        landActive++;
                    }
                }
                for (int k = LAND; k < COUNT; k++) {
                    if (w[k] != 0.0) {
                        seaActive++;
                    }
                }
                String cls;
                if (landActive == 0 && seaActive == 0) {
                    cls = "degenerate";
                } else if (landActive == 0 && seaActive >= 2) {
                    cls = "sea_sea";
                } else if (landActive == 0) {
                    cls = "sea_single";
                } else if (seaActive == 0 && landActive >= 2) {
                    cls = "land_land";
                } else if (seaActive == 0) {
                    cls = "land_single";
                } else {
                    cls = "mixed_land_sea";
                }
                sweep.counts.merge(cls, 1, Integer::sum);
                sweep.winnerHistogram[s.winnerRecipe()]++;
                sweep.dominantHistogram[s.dominant()]++;
                sweep.maxSeaWeight = Math.max(sweep.maxSeaWeight, s.seaWeight());
                if (seaActive == 0 && sweep.pureLand.size() < PURE_CAP) {
                    sweep.pureLand.add(new double[]{x, z});
                }
                if (landActive == 0 && sweep.pureSea.size() < PURE_CAP) {
                    sweep.pureSea.add(new double[]{x, z});
                }
            }
        }
    }

    /** 收集 tile 内可作为边界候选的原区域边（中点 + 法向 + 类型），先到先得。 */
    private static void collectCandidates(RegionalMorphWorld.RegionTile rt, int tx, int tz, Sweep s) {
        FullMorphLand.Tile t = rt.original();
        for (LocalBoundaryGeometry.Edge e : t.geometry().edges()) {
            if (e.frameTruncated || e.wx.length < 5) {
                continue;
            }
            int mid = e.wx.length / 2;
            double x = e.wx[mid];
            double z = e.wz[mid];
            if (x < IMG_X0 + NORMAL_HALF || x > IMG_X1 - NORMAL_HALF
                    || z < IMG_Z0 + NORMAL_HALF || z > IMG_Z1 - NORMAL_HALF) {
                continue;
            }
            if (Math.hypot(x - t.centerX(), z - t.centerZ()) + NORMAL_HALF
                    > t.geometry().queryRadius()) {
                continue;
            }
            double nx = -(e.wz[mid + 1] - e.wz[mid - 1]);
            double nz = e.wx[mid + 1] - e.wx[mid - 1];
            double len = Math.hypot(nx, nz);
            if (!(len > 0)) {
                continue;
            }
            nx /= len;
            nz /= len;
            int ra = rt.recipes()[e.a.index()];
            int rb = rt.recipes()[e.b.index()];
            if (ra == rb) {
                continue;
            }
            String kind = ra < LAND && rb < LAND ? "land-land"
                    : ra >= LAND && rb >= LAND ? "sea-sea" : "land-sea";
            List<Candidate> bucket = s.of(kind);
            int cap = BAND_CAP_KINDS.contains(kind) ? BAND_CANDIDATE_CAP : CAND_PER_KIND;
            if (bucket.size() >= cap) {
                continue;
            }
            Candidate cand = new Candidate();
            cand.kind = kind;
            cand.tileX = tx;
            cand.tileZ = tz;
            cand.edgeIndex = e.index;
            cand.x = x;
            cand.z = z;
            cand.nx = nx;
            cand.nz = nz;
            cand.recipeA = ra;
            cand.recipeB = rb;
            cand.cellA = e.a.id();
            cand.cellB = e.b.id();
            bucket.add(cand);
        }
    }

    // ============================================================ 5. 纯陆逐位

    /**
     * {@code evaluate(x,z, padding18)} 与 {@code originalLand().e(x,z)} <b>逐位</b>相等（容差 0）。
     *
     * <p>同段记录并断言 {@code Σw[0..17] == 1.0}。这是 {@link FullMorphLand#normalize} 的
     * <b>归一不变式</b>（残差补进最大项，海洋 5 槽在纯陆点是 {@code +0.0}，故加它们不改变部分和），
     * <b>不是</b> {@code evaluate} 的分支条件 —— 后者曾经是 {@code landWeight == 1}，
     * 已被 {@code w[0]=1, w[18]=1e-18} 这个真实缺陷推翻，改成检查实际正的海洋分量。
     * 纯陆分支的回归在 {@code sectionLandWeightOneTrap}。</p>
     */
    private static void sectionPureLand(Path out, Context c, Sweep sweep) throws Exception {
        List<double[]> points = ordered(sweep.pureLand);
        List<String> csv = new ArrayList<>(List.of(
                "x,z,land_weight_in_order,padded_sum_exact,active_land,land_e,evaluate_padded,"
                        + "abs_diff,bitwise,other_branch_abs_diff,cover_alpha"));
        PureLandTotals t = accumulatePureLand(c, points, csv);
        require(!points.isEmpty(), "no pure-land evidence point");
        require(t.worst() <= TOL_PURE, "pure land: evaluate(padded 18) != originalLand().e by "
                + t.worst() + " (tolerance " + TOL_PURE + ", points=" + points.size()
                + ", bitwise=" + t.bitwise() + ", normalize invariant Sum(w[0..17])==1 at "
                + t.landWeightExact() + ")");
        require(t.landWeightExact() == points.size(),
                "the normalize invariant fails at " + (points.size() - t.landWeightExact()) + " of "
                        + points.size() + " pure-land points: the in-order sum of weights[0..17]"
                        + " is not exactly 1.0 (this is FullMorphLand.normalize's contract, not"
                        + " evaluate's branch condition)");
        write(out, "pure-land.csv", csv);
        writePureLandSummary(out, t, points.size());
        writePureLandMetrics(t, points.size());
    }

    /** 纯陆逐点对拍的累加结果。 */
    private record PureLandTotals(double worst, int bitwise, int landWeightExact,
                                  int otherBranchDiffer, int alphaPoints) { }

    /** 纯陆逐点：{@code evaluate(padded18)} vs {@code originalLand().e}，并记录覆盖带命中数。 */
    private static PureLandTotals accumulatePureLand(Context c, List<double[]> points,
                                                     List<String> csv) {
        double worst = 0;
        int bitwise = 0;
        int landWeightExact = 0;
        int otherBranchDiffer = 0;
        int alphaPoints = 0;
        for (double[] p : points) {
            double x = p[0];
            double z = p[1];
            double[] padded = new double[COUNT];
            System.arraycopy(c.land.weights(x, z), 0, padded, 0, LAND);
            double landWeight = inOrderSum(padded, 0, LAND);
            boolean exact = Double.compare(landWeight, 1.0) == 0;
            if (exact) {
                landWeightExact++;
            }
            int landActive = 0;
            for (int k = 0; k < LAND; k++) {
                if (padded[k] != 0.0) {
                    landActive++;
                }
            }
            double landE = c.land.e(x, z);
            double got = c.worldA.evaluate(x, z, padded);
            double d = Math.abs(got - landE);
            boolean bits = Double.compare(got, landE) == 0;
            if (bits) {
                bitwise++;
            }
            worst = Math.max(worst, d);
            double alpha = c.land.alphaOf(c.land.maskValue(x, z));
            if (alpha > 0) {
                alphaPoints++;
            }
            double other = mixedBranchValue(c, x, z, padded, landWeight, alpha);
            if (Math.abs(other - got) > 0) {
                otherBranchDiffer++;
            }
            csv.add(String.format(Locale.ROOT,
                    "%.4f,%.4f,%.17g,%s,%d,%.17g,%.17g,%.3e,%s,%.3e,%.4f",
                    x, z, landWeight, exact, landActive, landE, got, d, bits,
                    Math.abs(other - got), alpha));
        }
        return new PureLandTotals(worst, bitwise, landWeightExact, otherBranchDiffer, alphaPoints);
    }

    /**
     * 反事实值：若 {@code landWeight != 1} 会走 {@code e = engineE + landWeight·delta}。
     * 只用于观测"两分支是否可分辨"，不参与判据。
     */
    private static double mixedBranchValue(Context c, double x, double z, double[] padded,
                                           double landWeight, double alpha) {
        double other = c.engine.sampleE(x, z, padded);
        if (landWeight > 0 && alpha > 0) {
            double[] landOnly = new double[COUNT];
            System.arraycopy(padded, 0, landOnly, 0, LAND);
            FullMorphLand.normalize(landOnly);
            double region = c.engine.sampleLand(x, z, landOnly);
            double delta = landEOf(c.land.replayOverlay(region, x, z)) - landEOf(region);
            other += landWeight * delta;
        }
        return other;
    }

    private static void writePureLandSummary(Path out, PureLandTotals t, int total) throws Exception {
        List<String> summary = new ArrayList<>(List.of(
                "case,points,bitwise,land_weight_exact,max_abs_diff,tolerance,cover_alpha_points,"
                        + "other_branch_differs"));
        summary.add(String.format(Locale.ROOT, "pure_land_vs_originalLand.e,%d,%d,%d,%.3e,%.1f,%d,%d",
                total, t.bitwise(), t.landWeightExact(), t.worst(), TOL_PURE, t.alphaPoints(),
                t.otherBranchDiffer()));
        summary.add("note,cover_alpha>0 at " + t.alphaPoints() + " of " + total
                + " points; land_weight_exact counts normalize's invariant Sum(w[0..17])==1.0 and"
                + " is NOT the pure-land branch condition any more (see landweight1-trap-summary.csv):"
                + " evaluate now tests whether any ocean component is actually > 0");
        write(out, "pure-land-summary.csv", summary);
    }

    private static void writePureLandMetrics(PureLandTotals t, int total) {
        metric("pure_land.points", total);
        metric("pure_land.bitwise", t.bitwise());
        metric("pure_land.land_weight_exact", t.landWeightExact());
        metric("pure_land.max_abs_diff", t.worst());
        metric("pure_land.cover_alpha_points", t.alphaPoints());
        metric("pure_land.other_branch_differs", t.otherBranchDiffer());
        note("pure_land: " + t.bitwise() + "/" + total + " bitwise; normalize invariant"
                + " Sum(w[0..17])==1.0 at " + t.landWeightExact() + "/" + total
                + " (recorded only -- no longer a branch condition); max|diff|=" + t.worst()
                + "; mask alpha>0 at " + t.alphaPoints() + " points");
    }

    // ============================================================ 5b. 回归：landWeight==1 但海权重非零

    /**
     * <b>回归：{@code Σw[0..17] == 1.0} 但某个海洋分量仍为正。</b>
     *
     * <h3>被固定的真实缺陷（Gallery 全量导出时炸出来的）</h3>
     * <p>取 {@code w[0] = 1.0}、{@code w[18] = 1e-18}（其余 0）。因为在 {@code 0..17} 顺序里
     * 求和恰为 {@code 1.0}，而 {@code 1.0 + 1e-18} 又<b>舍入回 1.0</b>，所以：</p>
     * <pre>
     *   Σw[0..17] == 1.0      // "看起来是纯陆"
     *   Σw[0..22] == 1.0      // 连全量求和也看不出海分量
     * </pre>
     * <p>于是任何"用求和判断纯陆"的口径都会把这条向量当成纯陆，进而拿它去调
     * {@link NodeMorphEngine#sampleLand} —— 而 {@code Evaluation} 的 {@code landOnly}
     * 是<b>按分量是否 > 0</b> 判的（{@code w[18]=1e-18 > 0}）⇒ 直接抛
     * {@code IllegalArgumentException("land-only weights required")}。
     * 现场表现就是 {@code main} 报 {@code land-only weights required}。</p>
     *
     * <p>正解（已被 main 修入实现）：<b>纯陆必须检查是否真有正的海洋分量</b>，
     * 不能只看 {@code Σw[0..17] == 1}。本段把这条钉死。</p>
     *
     * <h3>判据</h3>
     * <ol>
     *   <li>{@code evaluate} 必须<b>返回有限值且不抛</b>（缺陷复现时这里抛异常）；</li>
     *   <li>必须<b>走混合语义</b>：{@code engine.sampleE + landWeight·delta}
     *       （{@code delta} 用 {@link FullMorphLand#replayOverlay} 与
     *       {@link NodeMorphEngine#sampleLand} 现算，与实现同源但<b>不</b>调
     *       {@code evaluate} 自身）；</li>
     *   <li>在 alpha&gt;0 且两分支可分辨的点上，结果必须<b>不是</b>纯陆分支的值
     *       ⇒ 证明它真的没走 {@code sampleLand} 快捷路径。</li>
     * </ol>
     *
     * <p>⚠ 这不是"用求和判纯陆已经对了"的证明，而是"这条向量不再被判成纯陆"的回归。
     * {@code Σw[0..17] == 1.0} 依然是<b>归一器的不变式</b>（见纯陆段），
     * 只是它<b>不再</b>被当作 {@code evaluate} 的分支条件 —— 二者是不同的事。</p>
     */
    private static void sectionLandWeightOneTrap(Path out, Context c) throws Exception {
        List<String> csv = new ArrayList<>(List.of(
                "eps_seed,sea_recipe,x,z,sum_land_in_order,sum_all,sum_all_looks_1,"
                        + "alpha,land_only_positive_component,evaluate,evaluate_finite,"
                        + "engine_sampleE,contained_value,pure_land_branch_value,mixed_matches,"
                        + "branches_distinguishable,took_mixed_branch"));
        int sea = LAND;                                  // recipe 18 = 第一个海洋配方
        require(sea < COUNT, "no ocean recipe in the world catalog");
        List<double[]> probes = trapProbes(c);
        int alphaPositive = trapAlphaPositive;
        TrapTotals t = accumulateTrap(c, probes, csv, sea);
        require(alphaPositive >= 1, "could not find any point with coverage alpha > 0, so"
                + " the 'cover term is applied' half of this regression would be vacuous");
        checkTrapTotals(t);
        writeTrapEvidence(out, csv, t, sea, probes.size(), alphaPositive);
    }

    /** 陷阱回归的累加结果。 */
    private record TrapTotals(int rows, int finiteOk, int mixedOk, int tookMixed,
                              int distinguishable, double worstMixedDiff, double maxEpsKeptSum) { }

    /** {@link #trapProbes} 填好的"alpha&gt;0 的探针数"。 */
    private static int trapAlphaPositive;

    /**
     * 构造陷阱探针：图内 40 点 + 至多 40 个 {@code alpha > 0} 点。
     *
     * <p>要求至少 1 个 {@code alpha > 0}，否则"覆盖项确实会被施加"这一半判据是空的。</p>
     */
    private static List<double[]> trapProbes(Context c) {
        List<double[]> probes = new ArrayList<>();
        Random r = new Random(771001L);
        for (int i = 0; i < 40 && probes.size() < 40; i++) {
            probes.add(new double[]{IMG_X0 + r.nextDouble() * (IMG_X1 - IMG_X0),
                    IMG_Z0 + r.nextDouble() * (IMG_Z1 - IMG_Z0)});
        }
        int alphaPositive = 0;
        for (double x = -14000; x <= 14000 && alphaPositive < 40; x += 700) {
            for (double z = -14000; z <= 14000 && alphaPositive < 40; z += 700) {
                if (c.land.alphaOf(c.land.maskValue(x, z)) > 0) {
                    probes.add(new double[]{x, z});
                    alphaPositive++;
                }
            }
        }
        trapAlphaPositive = alphaPositive;
        return probes;
    }

    /**
     * 三种 eps 量级 × 全部探针：每条陷阱向量的 evaluate 结果与混合语义比对。
     *
     * <p>⚠ 内部 {@code require} 的条数与顺序即 checks 计数，勿动。</p>
     */
    private static TrapTotals accumulateTrap(Context c, List<double[]> probes, List<String> csv,
                                             int sea) {
        double[] eplions = {1e-18, 1e-20, Double.MIN_VALUE};
        int rows = 0;
        int finiteOk = 0;
        int mixedOk = 0;
        int tookMixed = 0;
        int distinguishable = 0;
        double worstMixedDiff = 0;
        double maxEpsKeptSum = 0;
        for (double eps : eplions) {
            for (double[] p : probes) {
                TrapRow row = evalTrapPoint(c, p[0], p[1], eps, sea);
                if (row == null) {
                    continue;
                }
                maxEpsKeptSum = Math.max(maxEpsKeptSum, eps);
                rows++;
                if (row.finite()) {
                    finiteOk++;
                }
                if (row.mixedOk()) {
                    mixedOk++;
                }
                worstMixedDiff = Math.max(worstMixedDiff, row.mixedDiff());
                if (row.distinguishable()) {
                    distinguishable++;
                    if (row.tookMixed()) {
                        tookMixed++;
                    }
                }
                csv.add(row.csvLine());
            }
        }
        return new TrapTotals(rows, finiteOk, mixedOk, tookMixed, distinguishable, worstMixedDiff,
                maxEpsKeptSum);
    }

    /** 一条陷阱向量的评测结果（{@code null} 表示这一档 eps 构造不出陷阱）。 */
    private record TrapRow(boolean finite, boolean mixedOk, boolean distinguishable,
                           boolean tookMixed, double mixedDiff, String csvLine) { }

    /**
     * 评测单条陷阱向量 {@code w[0]=1, w[sea]=eps}。
     *
     * @return {@code null} 若两种求和都不恰好"看起来是 1.0"（该 eps 构造不出陷阱）
     */
    private static TrapRow evalTrapPoint(Context c, double x, double z, double eps, int sea) {
        double[] w = new double[COUNT];
        w[0] = 1.0;
        w[sea] = eps;
        double sumLand = inOrderSum(w, 0, LAND);
        double sumAll = inOrderSum(w, 0, COUNT);
        if (Double.compare(sumLand, 1.0) != 0 || Double.compare(sumAll, 1.0) != 0) {
            return null;                             // 这一档 eps 构造不出陷阱，跳过（不伪造）
        }
        boolean positiveSeaComponent = false;
        for (int k = LAND; k < COUNT; k++) {
            positiveSeaComponent |= w[k] > 0.0;
        }
        double got;
        boolean finite = true;
        String thrown = "";
        try {
            got = c.worldA.evaluate(x, z, w);
            finite = Double.isFinite(got);
        } catch (RuntimeException ex) {
            got = Double.NaN;
            finite = false;
            thrown = ex.getClass().getSimpleName() + ": " + ex.getMessage();
            record("regression", "landWeight==1 with a positive sea component (" + eps
                    + ") at (" + x + "," + z + ") threw " + thrown
                    + " -- pure-land detection must inspect the positive sea components,"
                    + " not just the sum");
        }
        require(finite, "evaluate(w[0]=1, w[" + sea + "]=" + eps + ") at (" + x + "," + z
                + ") is not finite (" + (thrown.isEmpty() ? "returned " + got : thrown) + ")");

        // 混合语义的期望值（不调 evaluate；只用公开/包内既有入口现算）。
        double engineE = c.engine.sampleE(x, z, w);
        double[] landOnly = new double[COUNT];
        System.arraycopy(w, 0, landOnly, 0, LAND);
        FullMorphLand.normalize(landOnly);
        double region = c.engine.sampleLand(x, z, landOnly);
        double alpha = c.land.alphaOf(c.land.maskValue(x, z));
        double delta = landEOf(c.land.replayOverlay(region, x, z)) - landEOf(region);
        double contain = sumLand > 0 && alpha > 0 ? sumLand * delta : 0.0;
        double mixedExpected = engineE + contain;
        double pureLandValue = landEOf(c.land.replayOverlay(region, x, z));

        double mixedDiff = Math.abs(got - mixedExpected);
        boolean mixedOk = mixedDiff == 0.0;
        require(mixedOk, "evaluate(w[0]=1, w[" + sea + "]=" + eps + ") at (" + x + ","
                + z + ") is not bitwise engine.sampleE + landWeight*delta: got " + got
                + " expected " + mixedExpected + " (diff " + mixedDiff + ")");

        boolean distinguishable = Math.abs(engineE - landEOf(region)) > 1e-12;
        boolean tookMixed = false;
        if (distinguishable) {
            boolean isMixed = Double.compare(got, mixedExpected) == 0;
            boolean isPureLand = Double.compare(got, pureLandValue) == 0;
            tookMixed = isMixed && !isPureLand;
            require(tookMixed, "evaluate(w[0]=1, w[" + sea + "]=" + eps
                    + ") at (" + x + "," + z + ") does not take the mixed branch:"
                    + " got " + got + ", mixed=" + mixedExpected + ", pure-land="
                    + pureLandValue + " -- it silently used sampleLand");
        }
        String line = String.format(Locale.ROOT,
                "%.1e,%d,%.4f,%.4f,%.17g,%.17g,%s,%.4f,%s,%.17g,%s,%.17g,%.17g,%.17g,"
                        + "%s,%s,%s",
                eps, sea, x, z, sumLand, sumAll, true, alpha, positiveSeaComponent, got,
                finite, engineE, contain, pureLandValue, mixedOk, distinguishable,
                distinguishable ? (Double.compare(got, mixedExpected) == 0) : false);
        return new TrapRow(finite, mixedOk, distinguishable, tookMixed, mixedDiff, line);
    }

    /** 陷阱回归的四条总结判据。 */
    private static void checkTrapTotals(TrapTotals t) {
        require(t.rows() > 0, "no (eps, point) pair produced the sum-looks-like-1.0 trap at all");
        require(t.finiteOk() == t.rows(), (t.rows() - t.finiteOk()) + "/" + t.rows()
                + " trap vectors made evaluate non-finite");
        require(t.mixedOk() == t.rows(), (t.rows() - t.mixedOk()) + "/" + t.rows()
                + " trap vectors did not follow engine.sampleE + landWeight*delta (max diff "
                + t.worstMixedDiff() + ")");
        require(t.distinguishable() > 0, "no trap point had the two branches numerically"
                + " distinguishable, so 'it really took the mixed branch' was not observable");
        require(t.tookMixed() == t.distinguishable(), (t.distinguishable() - t.tookMixed())
                + "/" + t.distinguishable() + " distinguishable trap points did not take the"
                + " mixed branch");
    }

    private static void writeTrapEvidence(Path out, List<String> csv, TrapTotals t, int sea,
                                          int probeCount, int alphaPositive) throws Exception {
        List<String> summary = new ArrayList<>(List.of(
                "case,trap_vectors,finite,mixed_semantics_bitwise,distinguishable,took_mixed,"
                        + "max_abs_diff_vs_mixed,max_eps_that_keeps_sum_at_1,sea_recipe"));
        summary.add(String.format(Locale.ROOT, "landWeight1_positiveSeaComponent,%d,%d,%d,%d,%d,"
                        + "%.3e,%.1e,%d", t.rows(), t.finiteOk(), t.mixedOk(), t.distinguishable(),
                t.tookMixed(), t.worstMixedDiff(), t.maxEpsKeptSum(), sea));
        summary.add("why=w[0]=1.0 plus a positive ocean component eps keeps BOTH the in-order land"
                + " sum and the full sum at exactly 1.0 (1.0 + eps rounds back to 1.0), so any"
                + " sum-based pure-land test misclassifies the vector and NodeMorphEngine.sampleLand"
                + " rejects it with 'land-only weights required'");
        summary.add("fix_pinned=pure-land detection must inspect whether any ocean component is"
                + " actually > 0, not whether the sum equals 1");
        write(out, "landweight1-trap.csv", csv);
        write(out, "landweight1-trap-summary.csv", summary);

        metric("landweight1_trap.vectors", t.rows());
        metric("landweight1_trap.finite", t.finiteOk());
        metric("landweight1_trap.mixed_semantics_bitwise", t.mixedOk());
        metric("landweight1_trap.distinguishable", t.distinguishable());
        metric("landweight1_trap.took_mixed", t.tookMixed());
        metric("landweight1_trap.max_abs_diff_vs_mixed", t.worstMixedDiff());
        metric("landweight1_trap.max_eps_keeping_sum_at_1", t.maxEpsKeptSum());
        metric("landweight1_trap.alpha_positive_probes", alphaPositive);
        note("landweight1_trap: " + t.rows() + " trap vectors (w[0]=1, w[" + sea + "]=1e-18/1e-20/"
                + "Double.MIN_VALUE) at " + probeCount + " points; evaluate finite " + t.finiteOk()
                + "/" + t.rows() + "; bitwise engine.sampleE+landWeight*delta " + t.mixedOk()
                + "/" + t.rows() + "; mixed branch observable and taken at " + t.tookMixed()
                + "/" + t.distinguishable());
    }

    // ============================================================ 5c. 回归：海洋票槽全部可达

    /**
     * <b>回归：12 个海洋票槽必须全部可达，5 个海洋配方必须全部被抽到过。</b>
     *
     * <h3>被固定的真实缺陷（主代理全量导出后审计发现）</h3>
     * <p>{@code RefNoise.hash2D} 的收尾是 {@code hash ^= hash >> 13}（<b>算术</b>右移）。
     * 算术右移把符号位复制下来，再 {@code xor} 回去 ⇒ <b>符号位恒为 0</b>，
     * 返回值是 <b>31 位非负整数</b>，而不是 uint32。于是：</p>
     * <pre>
     *   旧：unit(h) = h · 2⁻³²   ⇒ draw ∈ [0, 0.5)     （因为 h &lt; 2³¹）
     *   新：unit(h) = h · 2⁻³¹   ⇒ draw ∈ [0, 1]       （正确铺满）
     * </pre>
     * <p>而 {@code choose} 用 {@code (int)(draw · oceanSlots.length)} 选海洋槽，
     * 槽数 12 ⇒ 旧口径下 {@code oceanSlot ∈ 0..5}，<b>后 6 个槽永远抽不到</b>。
     * 又因 {@link UnifiedTerrainCatalog#buildOceanSlots} 的展开表里
     * {@code MID_OCEAN_RIDGE}/{@code SEAMOUNT_FIELD} 的 2 个份额可能整个落在高半区，
     * 现场表现就是"某些海洋配方完全不出现"。</p>
     *
     * <h3>判据（三条，全部<b>不</b>使用 {@code oceanSlots} 表来自证）</h3>
     * <ol>
     *   <li><b>独立复算 {@code unit}</b>：直接在 {@code cellX/cellZ ∈ [-50,50]} 上复算
     *       {@code unit(hash2D(base+OFFSET, x, z))}，要求
     *       {@code max > 0.99} 且 {@code min < 0.01}，并且
     *       {@code (int)(u·12)} <b>覆盖 0..11 全部 12 个值</b>。
     *       这条<b>不</b>依赖 {@code choose}、<b>不</b>依赖 {@code oceanSlots} 表的内容，
     *       所以能独立抓住 {@code 2⁻³² → 2⁻³¹} 这类位宽错误。</li>
     *   <li><b>走真实路径核对槽号</b>：用<b>同一份几何</b>的 {@code siteXZ} 合成
     *       {@link LocalBoundaryGeometry.Cell}，喂给真实的 {@code choose}，把
     *       {@code SiteChoice.oceanSlot()} 与第 1 条的独立复算值<b>逐格比对</b>
     *       —— 证明 {@code choose} 内部用的就是同一个 {@code unit}，没有被再除一次。</li>
     *   <li><b>5 个海洋配方都真的被选到</b>：在更宽的稳定站点网格上跑 {@code choose}
     *       ({@link #OCEAN_RECIPE_CELL_SPAN})，统计 {@code recipe ∈ [18,23)} 的分布，
     *       要求 5 个配方<b>计数全部 &gt; 0</b>；同时要求 12 个槽号也全部出现。
     *       计数只与"是否 &gt; 0"比较 —— <b>不</b>拿它当期望值去对表（那是同源自证）。</li>
     * </ol>
     *
     * <h3>与 isSea 无关</h3>
     * <p>{@code oceanSlot} 是<b>无条件</b>计算的（{@code draw < p} 与否都算），
     * 所以这条回归只用 {@code choose} 的返回值就能查，不需要构造"确实被判为海"的站点；
     * 本段把 {@code isSea} 的分布也记下来，证明样本里两种情形都有。</p>
     */
    private static void sectionOceanSlots(Path out, Context c) throws Exception {
        int slots = UnifiedTerrainCatalog.OCEAN_SLOTS;
        int recipes = OceanRecipeCatalog.ENTRY_COUNT;
        require(slots == 12, "ocean slot count is " + slots + ", expected 12");
        require(recipes == 5, "ocean recipe count is " + recipes + ", expected 5");

        // ★ 直接读<b>实现里的</b> unit()/base()，不在本文件里复制字面量：
        // 若实现把 2^-31 改回 2^-32，这里立刻跟着变（判据才不会自证）。
        int hashSeed = baseOf(SEED + OceanRecipeCatalog.SEED_OFFSET);
        SlotScan independent = scanIndependentUnit(hashSeed, slots);
        OceanChoose choose = scanChoosePath(c, hashSeed, slots);
        OceanWide wide = scanWideRecipes(c, slots, recipes);
        writeOceanEvidence(out, independent, choose, wide, slots, recipes);
    }

    /** 判据 1 的产物：{@code unit()} 的极值、各槽命中数、可达/缺失槽数。 */
    private record SlotScan(double unitMax, double unitMin, int[] slotHits, long cells,
                            int reachable, int missing, String missList) { }

    /** 判据 2 的产物：{@code choose()} 与独立复算的逐格比对结果。 */
    private record OceanChoose(long compared, long mismatched, long inverseFailures, long seaSites,
                               long landSites, int[] slotHits, List<String> csvRows) { }

    /** 判据 3 的产物：宽扫描下的配方/槽命中数。 */
    private record OceanWide(long cells, long sea, int[] recipeHits, int[] slotHits,
                             int recipesSeen, String missingRecipes, int slotsReachable) { }

    /**
     * 判据 1：不带 {@code choose}、不带 {@code oceanSlots} 表，直接在格坐标上复算
     * {@code unit(hash2D(seed, x, z))}，看 {@code (int)(u·slots)} 是否铺满所有槽。
     */
    private static SlotScan scanIndependentUnit(int hashSeed, int slots) throws Exception {
        int span = OCEAN_SLOT_CELL_SPAN;
        double unitMax = Double.NEGATIVE_INFINITY;
        double unitMin = Double.POSITIVE_INFINITY;
        int[] slotHits = new int[slots];
        long cells = 0;
        for (int x = -span; x <= span; x++) {
            for (int z = -span; z <= span; z++) {
                double u = unitOf(RefNoise.hash2D(hashSeed, x, z));
                unitMax = Math.max(unitMax, u);
                unitMin = Math.min(unitMin, u);
                int slot = (int) (u * slots);
                require(slot >= 0 && slot < slots,
                        "independent unit(" + x + "," + z + ")=" + u + " maps to slot " + slot
                                + " outside [0," + slots + ")");
                slotHits[Math.max(0, Math.min(slots - 1, slot))]++;
                cells++;
            }
        }
        int reachable = 0;
        int missing = 0;
        StringBuilder missList = new StringBuilder();
        for (int i = 0; i < slots; i++) {
            if (slotHits[i] > 0) {
                reachable++;
            } else {
                missing++;
                if (missList.length() > 0) {
                    missList.append(' ');
                }
                missList.append(i);
            }
        }
        require(cells > 0, "no cells scanned");
        require(unitMax > 0.99, "unit() max over cellX/cellZ in [-" + span + "," + span + "] is "
                + unitMax + " <= 0.99: the draw never reaches the top of [0,1)"
                + " (a 31-bit hash scaled by 2^-32 saturates at 0.5)");
        require(unitMin < 0.01, "unit() min is " + unitMin + " >= 0.01: the draw never reaches"
                + " the bottom of [0,1)");
        require(missing == 0, missing + " of " + slots + " ocean slots are UNREACHABLE over"
                + " cellX/cellZ in [-" + span + "," + span + "]: slots " + missList
                + " (draw max " + unitMax + " caps the slot index at "
                + ((int) (unitMax * slots)) + ")");
        return new SlotScan(unitMax, unitMin, slotHits, cells, reachable, missing,
                missList.toString());
    }

    /**
     * 判据 2：用同一份几何的 {@code siteXZ} 合成 {@code Cell}，喂给真实 {@code choose}，
     * 逐格比对 {@code oceanSlot()} 与独立复算值。
     */
    private static OceanChoose scanChoosePath(Context c, int hashSeed, int slots) throws Exception {
        int span = OCEAN_SLOT_CELL_SPAN;
        LocalBoundaryGeometry g = oceanProbeGeometry(c);
        long compared = 0;
        long mismatched = 0;
        long inverseFailures = 0;
        long seaSites = 0;
        long landSites = 0;
        int[] slotHits = new int[slots];
        List<String> rows = new ArrayList<>();
        for (int x = -span; x <= span; x++) {
            for (int z = -span; z <= span; z++) {
                RegionalMorphWorld.SiteChoice choice = syntheticChoice(g, x, z, c);
                if (choice == null) {
                    inverseFailures++;
                    continue;
                }
                double u = unitOf(RefNoise.hash2D(hashSeed, x, z));
                int independent = (int) (u * slots);
                compared++;
                if (choice.oceanSlot() != independent) {
                    mismatched++;
                    record("ocean_slot", "cell (" + x + "," + z + ") choose().oceanSlot="
                            + choice.oceanSlot() + " but the independent recomputation gives "
                            + independent + " (unit=" + u + ")");
                }
                require(choice.oceanSlot() >= 0 && choice.oceanSlot() < slots,
                        "choose().oceanSlot " + choice.oceanSlot() + " outside [0," + slots + ")");
                slotHits[Math.max(0, Math.min(slots - 1, choice.oceanSlot()))]++;
                boolean isSea = choice.recipe() >= LAND;
                if (isSea) {
                    seaSites++;
                } else {
                    landSites++;
                }
                rows.add(String.format(Locale.ROOT,
                        "%d,%d,%.17g,%d,%d,%s,%.17g,%.17g,%s,%d",
                        x, z, u, independent, choice.oceanSlot(),
                        choice.oceanSlot() == independent, choice.draw(), choice.probability(),
                        isSea, choice.recipe()));
            }
        }
        require(compared >= (2L * span + 1) * (2L * span + 1) / 2, "only " + compared + " of "
                + ((2L * span + 1) * (2L * span + 1)) + " grid cells produced a SiteChoice"
                + " (inverse failures=" + inverseFailures + "): the cross-check sample is thin");
        require(mismatched == 0, mismatched + "/" + compared + " cells got a different oceanSlot"
                + " from choose() than from the independent unit() recomputation -- choose() and"
                + " unit() disagree, or the slot index is scaled an extra time");
        require(seaSites > 0 && landSites > 0, "cross-check sample has seaSites=" + seaSites
                + " landSites=" + landSites + ": both sides of isSea must appear, because"
                + " oceanSlot is filled unconditionally");
        require(slotHits.length == slots, "slot histogram size mismatch");
        return new OceanChoose(compared, mismatched, inverseFailures, seaSites, landSites, slotHits,
                rows);
    }

    /** 供海洋槽探针使用的几何（半径见 {@link #OCEAN_CHOOSE_RADIUS}）。 */
    private static LocalBoundaryGeometry oceanProbeGeometry(Context c) {
        LocalBoundaryGeometry g = LocalBoundaryGeometry.of(c.catalog.original(),
                FullMorphLand.center(0), FullMorphLand.center(0), OCEAN_CHOOSE_RADIUS,
                FullMorphLand.REQ_HALF, FullMorphLand.CHORD_TOL);
        g.setMaxSeg(FullMorphLand.MAX_SEG_BLOCKS);
        return g;
    }

    /** 合成站点并 {@code choose}；反 warp 失败时返回 {@code null}（跳过，不伪造）。 */
    private static RegionalMorphWorld.SiteChoice syntheticChoice(LocalBoundaryGeometry g,
                                                                 int cellX, int cellZ,
                                                                 Context c) {
        double[] uv = g.siteXZ(cellX, cellZ);
        LocalBoundaryGeometry.Cell synthetic =
                new LocalBoundaryGeometry.Cell(cellX, cellZ, uv[0], uv[1], 0);
        try {
            return c.worldA.choose(g, synthetic);
        } catch (RuntimeException ex) {
            return null;
        }
    }

    /** 判据 3：更宽的格网格上跑真实 {@code choose}，统计配方与槽命中。 */
    private static OceanWide scanWideRecipes(Context c, int slots, int recipes) {
        int wideSpan = OCEAN_RECIPE_CELL_SPAN;
        LocalBoundaryGeometry g = oceanProbeGeometry(c);
        int[] recipeHits = new int[COUNT];
        int[] slotHits = new int[slots];
        long cells = 0;
        long sea = 0;
        for (int x = -wideSpan; x <= wideSpan; x++) {
            for (int z = -wideSpan; z <= wideSpan; z++) {
                RegionalMorphWorld.SiteChoice choice = syntheticChoice(g, x, z, c);
                if (choice == null) {
                    continue;
                }
                cells++;
                recipeHits[choice.recipe()]++;
                slotHits[Math.max(0, Math.min(slots - 1, choice.oceanSlot()))]++;
            }
        }
        int recipesSeen = 0;
        StringBuilder missingRecipes = new StringBuilder();
        for (int r = LAND; r < COUNT; r++) {
            sea += recipeHits[r];
            if (recipeHits[r] > 0) {
                recipesSeen++;
            } else {
                if (missingRecipes.length() > 0) {
                    missingRecipes.append(' ');
                }
                missingRecipes.append(r);
            }
        }
        int slotsReachable = 0;
        for (int i = 0; i < slots; i++) {
            if (slotHits[i] > 0) {
                slotsReachable++;
            }
        }
        require(cells > 0, "the wide recipe scan produced no cells");
        require(sea > 0, "no site in the wide scan was classified as sea, so the ocean"
                + " recipes could not have been drawn at all");
        require(recipesSeen == recipes, (recipes - recipesSeen) + " of " + recipes
                + " ocean recipes were NEVER chosen over cellX/cellZ in [-" + wideSpan + ","
                + wideSpan + "]: recipes " + missingRecipes
                + " -- every ocean recipe needs a slot that the draw can actually reach");
        require(slotsReachable == slots, slotsReachable + "/" + slots
                + " ocean slots were hit by the real choose() path over the wide scan");
        return new OceanWide(cells, sea, recipeHits, slotHits, recipesSeen,
                missingRecipes.toString(), slotsReachable);
    }

    private static void writeOceanEvidence(Path out, SlotScan s, OceanChoose ch, OceanWide w,
                                           int slots, int recipes) throws Exception {
        int span = OCEAN_SLOT_CELL_SPAN;
        int wideSpan = OCEAN_RECIPE_CELL_SPAN;
        List<String> csv = new ArrayList<>(List.of(
                "cellX,cellZ,unit_ocean,slot_independent,slot_by_choose,equal,"
                        + "draw_land,probability,is_sea,recipe"));
        csv.addAll(ch.csvRows());
        List<String> summary = new ArrayList<>(List.of(
                "case,cells,slots,slots_reachable,slots_missing,unit_min,unit_max,"
                        + "max_slot_reachable_by_draw,choose_cells,choose_mismatch,"
                        + "choose_inverse_failures,choose_sea_sites,choose_land_sites,"
                        + "wide_cells,wide_sea_hits,ocean_recipes_seen,wide_slots_reachable"));
        summary.add(String.format(Locale.ROOT,
                "ocean_slot_reachability,%d,%d,%d,%d,%.6f,%.6f,%d,%d,%d,%d,%d,%d,%d,%d,%d,%d",
                s.cells(), slots, s.reachable(), s.missing(), s.unitMin(), s.unitMax(),
                (int) (s.unitMax() * slots), ch.compared(), ch.mismatched(),
                ch.inverseFailures(), ch.seaSites(), ch.landSites(), w.cells(), w.sea(),
                w.recipesSeen(), w.slotsReachable()));
        summary.add("why=RefNoise.hash2D ends with 'hash ^= hash >> 13' (ARITHMETIC shift), which"
                + " copies the sign bit back in and therefore leaves the sign bit at 0: the result"
                + " is a 31-bit non-negative int, not uint32. unit(h) = h * 2^-32 then yields"
                + " draw in [0,0.5), so (int)(draw*12) can only reach slots 0..5 and the upper six"
                + " ocean slots -- plus any recipe that only occupies them -- never appear");
        summary.add("fix_pinned=unit(h) must be h * 2^-31 so the draw spans [0,1]. This check reads"
                + " the implementation's own unit()/base() reflectively, recomputes the slot"
                + " independently of oceanSlots, and then requires choose() to agree cell by cell");
        summary.add("not_self_proof=the gate is 'all 12 slots reachable' and 'each of the 5 ocean"
                + " recipes is chosen at least once'. No count is compared against"
                + " buildOceanSlots' expansion table, so the check cannot pass by agreeing with"
                + " itself");
        write(out, "ocean-slots.csv", csv);
        write(out, "ocean-slots-summary.csv", summary);

        List<String> hist = new ArrayList<>(List.of("ocean_slot,independent_hits,choose_hits,"
                + "wide_choose_hits,reachable"));
        for (int i = 0; i < slots; i++) {
            hist.add(String.format(Locale.ROOT, "%d,%d,%d,%d,%s", i, s.slotHits()[i],
                    ch.slotHits()[i], w.slotHits()[i], w.slotHits()[i] > 0));
        }
        write(out, "ocean-slot-histogram.csv", hist);

        metric("ocean_slot.cells_scanned", s.cells());
        metric("ocean_slot.unit_min", s.unitMin());
        metric("ocean_slot.unit_max", s.unitMax());
        metric("ocean_slot.slots_reachable", s.reachable());
        metric("ocean_slot.slots_missing", s.missing());
        metric("ocean_slot.max_slot_reachable_by_draw", (int) (s.unitMax() * slots));
        metric("ocean_slot.choose_cells", ch.compared());
        metric("ocean_slot.choose_slot_mismatch", ch.mismatched());
        metric("ocean_slot.choose_inverse_failures", ch.inverseFailures());
        metric("ocean_slot.choose_sea_sites", ch.seaSites());
        metric("ocean_slot.choose_land_sites", ch.landSites());
        metric("ocean_slot.wide_cells", w.cells());
        metric("ocean_slot.wide_sea_hits", w.sea());
        metric("ocean_slot.ocean_recipes_seen", w.recipesSeen());
        metric("ocean_slot.wide_slots_reachable", w.slotsReachable());
        note("ocean_slot: unit over [-" + span + "," + span + "]^2 in [" + s.unitMin() + ", "
                + s.unitMax() + "]; " + s.reachable() + "/" + slots + " slots reachable"
                + " independently; choose() agrees on " + ch.compared() + " cells (mismatch="
                + ch.mismatched() + ", inverseFail=" + ch.inverseFailures() + ", sea="
                + ch.seaSites() + " land=" + ch.landSites() + "); wide scan " + w.cells()
                + " cells over [-" + wideSpan + "," + wideSpan + "]^2 -> " + w.recipesSeen() + "/"
                + recipes + " ocean recipes and " + w.slotsReachable() + "/" + slots + " slots hit");
    }

    /**
     * 反射读 {@link RegionalMorphWorld} 的私有 {@code unit(int)}。
     *
     * <p><b>为什么反射而不是复制公式</b>：本段要抓的正是"位宽写错"（{@code 2^-32} vs
     * {@code 2^-31}）。若在本文件里硬编码 {@code 2^-31}，判据就变成"和我的常量比"，
     * 实现改回 {@code 2^-32} 也照样通过。<b>读实现本身</b>才能让它的错误传导到判据。</p>
     */
    private static double unitOf(int hash) throws Exception {
        if (UNIT_METHOD == null) {
            UNIT_METHOD = RegionalMorphWorld.class.getDeclaredMethod("unit", int.class);
            UNIT_METHOD.setAccessible(true);
        }
        return (double) UNIT_METHOD.invoke(null, hash);
    }

    /** 反射读私有 {@code base(long)}（{@code (int)(seed ^ (seed >>> 32))}）。 */
    private static int baseOf(long seed) throws Exception {
        if (BASE_METHOD == null) {
            BASE_METHOD = RegionalMorphWorld.class.getDeclaredMethod("base", long.class);
            BASE_METHOD.setAccessible(true);
        }
        return (int) BASE_METHOD.invoke(null, seed);
    }

    private static java.lang.reflect.Method UNIT_METHOD;
    private static java.lang.reflect.Method BASE_METHOD;

    // ============================================================ 6. 纯海无覆盖

    private static void sectionPureSea(Path out, Context c, Sweep sweep) throws Exception {
        List<double[]> points = ordered(sweep.pureSea);
        List<String> csv = new ArrayList<>(List.of(
                "x,z,land_weight_in_order,sea_weight,sea_active,engine_e,evaluate,abs_diff,bitwise,"
                        + "mask,cover_alpha,cover_guard_fires"));
        double worst = 0;
        int bitwise = 0;
        int coverGuard = 0;
        int seaActiveMax = 0;
        int seaSeaPoints = 0;
        double maskMax = 0;
        for (double[] p : points) {
            double x = p[0];
            double z = p[1];
            RegionalMorphWorld.Sample s = c.worldA.sample(x, z);
            double[] w = s.weights();
            double landWeight = inOrderSum(w, 0, LAND);
            int seaActive = 0;
            for (int k = LAND; k < COUNT; k++) {
                if (w[k] != 0.0) {
                    seaActive++;
                }
            }
            seaActiveMax = Math.max(seaActiveMax, seaActive);
            if (seaActive >= 2) {
                seaSeaPoints++;
            }
            double engineE = c.engine.sampleE(x, z, w);
            double got = c.worldA.evaluate(x, z, w);
            double d = Math.abs(got - engineE);
            boolean bits = Double.compare(got, engineE) == 0;
            if (bits) {
                bitwise++;
            }
            worst = Math.max(worst, d);
            float mask = c.land.maskValue(x, z);
            double alpha = c.land.alphaOf(mask);
            maskMax = Math.max(maskMax, mask);
            boolean fires = landWeight > 0 && alpha > 0;
            if (fires) {
                coverGuard++;
            }
            csv.add(String.format(Locale.ROOT,
                    "%.4f,%.4f,%.17g,%.17g,%d,%.17g,%.17g,%.3e,%s,%.4f,%.4f,%s",
                    x, z, landWeight, s.seaWeight(), seaActive, engineE, got, d, bits, mask, alpha,
                    fires));
        }
        require(!points.isEmpty(), "no pure-sea evidence point");
        require(worst <= TOL_PURE, "pure sea: evaluate != independent engine.sampleE by " + worst
                + " (tolerance " + TOL_PURE + ", points=" + points.size() + ", bitwise=" + bitwise + ")");
        require(coverGuard == 0, "pure sea: the cover guard {landWeight>0 && alpha>0} fired at "
                + coverGuard + " points -> the sea path picked up the land coverage delta");
        write(out, "pure-sea.csv", csv);

        List<String> summary = new ArrayList<>(List.of(
                "case,points,bitwise,max_abs_diff,tolerance,cover_guard_fired,max_sea_active,"
                        + "sea_sea_points,mask_max_in_image"));
        summary.add(String.format(Locale.ROOT, "pure_sea_vs_engine.sampleE,%d,%d,%.3e,%.1f,%d,%d,%d,%.4f",
                points.size(), bitwise, worst, TOL_PURE, coverGuard, seaActiveMax, seaSeaPoints, maskMax));
        summary.add("structural_note,landWeight==0 makes the coverage term landWeight*delta exactly 0,"
                + " so a pure sea sample can only equal the bare engine evaluation");
        write(out, "pure-sea-summary.csv", summary);

        metric("pure_sea.points", points.size());
        metric("pure_sea.bitwise", bitwise);
        metric("pure_sea.max_abs_diff", worst);
        metric("pure_sea.cover_guard_fired", coverGuard);
        metric("pure_sea.max_sea_active", seaActiveMax);
        metric("pure_sea.sea_sea_points", seaSeaPoints);
        metric("pure_sea.mask_max_in_image", maskMax);
        note("pure_sea: " + bitwise + "/" + points.size() + " bitwise vs independent engine.sampleE;"
                + " cover guard fired 0 times; max sea-active=" + seaActiveMax + "; sea-sea points="
                + seaSeaPoints + "; mask max=" + maskMax);
    }

    // ============================================================ 7. 身份跨 tile / 跨实例 / 逆序

    /**
     * 同一 cell 在<b>不同（含相邻/重叠）</b>几何、在<b>两个独立</b> world 实例上抽签逐位相同；
     * 并用 worldB <b>逆序</b>重建 worldA tile 的 {@code recipes[]}。
     *
     * <p><b>为什么必须两个世界</b>：{@code RegionalMorphWorld.choose} 自身有 {@code choices} 缓存，
     * 同一个 world 上重复调用会命中缓存 ⇒ 只能证明"缓存一致"，不能证明"身份只由 cell 决定"。
     * 这里 worldB 从不到 {@code tileAt}，它的每次 {@code choose} 都是<b>首算</b>。</p>
     */
    private static void sectionIdentity(Path out, Context c) throws Exception {
        List<LocalBoundaryGeometry> geoms = identityWindows(c);
        List<String> csv = new ArrayList<>(List.of(
                "geomA,geomB,shared_cells,identical,recipe_equal,max_field_diff,note"));
        int[] pairTotals = compareWindowPairs(c, geoms, csv);
        int shared = pairTotals[0];
        int identical = pairTotals[1];
        int recipeMismatch = pairTotals[2];
        require(shared >= 1, "no cell is shared by any pair of windows: the cross-window identity"
                + " check could not run at all");
        require(identical == shared, (shared - identical) + "/" + shared
                + " shared cells draw differently across two independent worlds");

        TileRebuild rebuild = rebuildTile(c, csv);
        write(out, "identities.csv", csv);
        writeIdentityWindows(out, geoms);
        writeIdentityMetrics(shared, identical, recipeMismatch, rebuild, geoms.size());
    }

    /** 逆序/正序重建 worldA tile 的失配数。 */
    private record TileRebuild(int reverseMismatch, int forwardMismatch, int sites) { }

    /**
     * 身份检查用的几何窗口：半径刻意跨两个数量级。
     *
     * <p>{@code RegionPartition.REGION_SCALE=1200} 块/格，728 块只覆盖 ~1.2×1.2 格
     * （2~4 个站点），4000 块才有几十个站点 ⇒ 重叠 cell 也更多。</p>
     */
    private static List<LocalBoundaryGeometry> identityWindows(Context c) {
        double[][] specs = {
                {FullMorphLand.center(0), FullMorphLand.center(0), FullMorphLand.QUERY_RADIUS},
                {FullMorphLand.center(1), FullMorphLand.center(0), FullMorphLand.QUERY_RADIUS},
                {FullMorphLand.center(0), FullMorphLand.center(1), FullMorphLand.QUERY_RADIUS},
                {FullMorphLand.center(0) + 300.0, FullMorphLand.center(0) - 220.0, 2000.0},
                {FullMorphLand.center(1) - 200.0, FullMorphLand.center(1), 4000.0},
        };
        List<LocalBoundaryGeometry> geoms = new ArrayList<>();
        for (double[] s : specs) {
            LocalBoundaryGeometry g = LocalBoundaryGeometry.of(c.catalog.original(), s[0], s[1], s[2],
                    FullMorphLand.REQ_HALF, FullMorphLand.CHORD_TOL);
            g.setMaxSeg(FullMorphLand.MAX_SEG_BLOCKS);
            geoms.add(g);
        }
        return geoms;
    }

    /**
     * 逐对窗口比对共享 cell 的抽签结果。
     *
     * @return {@code {shared, identical, recipeMismatch}}
     */
    private static int[] compareWindowPairs(Context c, List<LocalBoundaryGeometry> geoms,
                                            List<String> csv) {
        int shared = 0;
        int identical = 0;
        int recipeMismatch = 0;
        for (int i = 0; i < geoms.size(); i++) {
            for (int j = i + 1; j < geoms.size(); j++) {
                int[] pair = compareWindowPair(c, geoms.get(i), geoms.get(j), i, j, csv);
                shared += pair[0];
                identical += pair[1];
                recipeMismatch += pair[2];
            }
        }
        return new int[]{shared, identical, recipeMismatch};
    }

    /**
     * 一对窗口：{@code worldA} 读 A 几何、{@code worldB} 读 B 几何（后者从不 {@code tileAt}，
     * 故其 {@code choose} 都是首算，避开"同一 world 的缓存自证"）。
     *
     * @return {@code {shared, identical, recipeMismatch}}
     */
    private static int[] compareWindowPair(Context c, LocalBoundaryGeometry ga,
                                           LocalBoundaryGeometry gb, int i, int j,
                                           List<String> csv) {
        Map<Long, LocalBoundaryGeometry.Site> index = new LinkedHashMap<>();
        for (LocalBoundaryGeometry.Site s : gb.sites()) {
            index.put(s.key(), s);
        }
        int localShared = 0;
        int localIdentical = 0;
        int localRecipe = 0;
        int recipeMismatch = 0;
        for (LocalBoundaryGeometry.Site sa : ga.sites()) {
            LocalBoundaryGeometry.Site sb = index.get(sa.key());
            if (sb == null) {
                continue;
            }
            localShared++;
            RegionalMorphWorld.SiteChoice ca;
            RegionalMorphWorld.SiteChoice cb;
            try {
                ca = c.worldA.choose(ga, sa);
                cb = c.worldB.choose(gb, sb);
            } catch (RuntimeException ex) {
                require(false, "site " + sa.id() + " failed the warp inverse: " + ex.getMessage());
                continue;
            }
            if (ca.recipe() == cb.recipe()) {
                localRecipe++;
            } else {
                recipeMismatch++;
                require(false, "site " + sa.id() + " got recipe " + ca.recipe()
                        + " from window " + i + " but " + cb.recipe() + " from window " + j);
            }
            if (sameChoice(ca, cb)) {
                localIdentical++;
            } else {
                require(false, "site " + sa.id() + " draws differently across windows "
                        + i + " and " + j + ": " + describe(ca) + " vs " + describe(cb));
            }
        }
        require(recipeViewMismatch(c, ga, gb, index) == 0, "recipes[] differs across windows "
                + i + " and " + j + " (worldA tile path vs worldB first computation)");
        csv.add(String.format(Locale.ROOT, "%d,%d,%d,%d,%d,0,%s", i, j, localShared,
                localIdentical, localRecipe,
                localShared == 0 ? "no overlap (windows disjoint)" : "two independent worlds"));
        return new int[]{localShared, localIdentical, recipeMismatch};
    }

    /** {@code recipes[]} 视图（worldA 的 tile 路径）在两侧窗口下也必须一致。 */
    private static int recipeViewMismatch(Context c, LocalBoundaryGeometry ga,
                                          LocalBoundaryGeometry gb,
                                          Map<Long, LocalBoundaryGeometry.Site> index) {
        int mismatches = 0;
        for (LocalBoundaryGeometry.Site sa : ga.sites()) {
            LocalBoundaryGeometry.Site sb = index.get(sa.key());
            if (sb == null) {
                continue;
            }
            if (c.worldA.choose(ga, sa).recipe() != c.worldB.choose(gb, sb).recipe()) {
                mismatches++;
            }
        }
        return mismatches;
    }

    /** 用 worldB 逆序 / 正序重建 worldA tile 的 {@code recipes[]}。 */
    private static TileRebuild rebuildTile(Context c, List<String> csv) {
        RegionalMorphWorld.RegionTile tile =
                c.worldA.tileAt(FullMorphLand.center(0), FullMorphLand.center(0));
        List<LocalBoundaryGeometry.Site> sites = tile.original().geometry().sites();
        int reverseMismatch = 0;
        for (int i = sites.size() - 1; i >= 0; i--) {
            LocalBoundaryGeometry.Site s = sites.get(i);
            if (c.worldB.choose(tile.original().geometry(), s).recipe() != tile.recipes()[i]) {
                reverseMismatch++;
            }
        }
        int forwardMismatch = 0;
        for (int i = 0; i < sites.size(); i++) {
            LocalBoundaryGeometry.Site s = sites.get(i);
            if (c.worldB.choose(tile.original().geometry(), s).recipe() != tile.recipes()[i]) {
                forwardMismatch++;
            }
        }
        require(reverseMismatch == 0, "reverse-order rebuild of recipes[] differs at " + reverseMismatch
                + " of " + sites.size() + " sites");
        require(forwardMismatch == 0, "forward-order rebuild from the other world differs at "
                + forwardMismatch + " of " + sites.size() + " sites");
        csv.add(String.format(Locale.ROOT, "tile0,reverse,%d,%d,%d,0,%s", sites.size(),
                sites.size() - reverseMismatch, sites.size() - reverseMismatch,
                "reverse traversal via a fresh independent world"));
        csv.add(String.format(Locale.ROOT, "tile0,forward,%d,%d,%d,0,%s", sites.size(),
                sites.size() - forwardMismatch, sites.size() - forwardMismatch,
                "forward traversal via a fresh independent world"));
        return new TileRebuild(reverseMismatch, forwardMismatch, sites.size());
    }

    private static void writeIdentityWindows(Path out, List<LocalBoundaryGeometry> geoms)
            throws Exception {
        List<String> geomMeta = new ArrayList<>(List.of(
                "window,centerX,centerZ,radius,sites,edges,maxWarpResidualBlocks,maxSegBlocks"));
        for (int i = 0; i < geoms.size(); i++) {
            LocalBoundaryGeometry g = geoms.get(i);
            geomMeta.add(String.format(Locale.ROOT, "%d,%.1f,%.1f,%.1f,%d,%d,%.3e,%.1f", i,
                    g.centerX(), g.centerZ(), g.queryRadius(), g.sites().size(), g.edges().size(),
                    g.maxWarpResidualBlocks(), g.maxSegBlocks()));
        }
        write(out, "identity-windows.csv", geomMeta);
    }

    private static void writeIdentityMetrics(int shared, int identical, int recipeMismatch,
                                             TileRebuild rebuild, int windowCount) {
        metric("identity.windows", windowCount);
        metric("identity.shared_cells", shared);
        metric("identity.identical", identical);
        metric("identity.recipe_mismatch",
                recipeMismatch + rebuild.reverseMismatch() + rebuild.forwardMismatch());
        metric("identity.tile0_sites", rebuild.sites());
        note("identity: " + shared + " cells shared across " + windowCount
                + " windows; " + identical + " draw bit-identically on two independent worlds;"
                + " reverse rebuild mismatches=" + rebuild.reverseMismatch()
                + "; tile0 sites=" + rebuild.sites());
    }

    // ============================================================ 8. 跨 tile 强取样

    private static void sectionTileBoundary(Path out, Context c) throws Exception {
        List<String> csv = new ArrayList<>(List.of(
                "boundaryX,offset,z,e_left,e_right,max_abs_de,max_abs_dw,verdict,"
                        + "canonical_minus_left,canonical_minus_right,canonical_matches_a_side"));
        double worstE = 0;
        double worstW = 0;
        int rows = 0;
        int canonicalMatches = 0;
        RegionalMorphWorld.RegionTile left =
                c.worldA.tileAt(FullMorphLand.center(0), FullMorphLand.center(0));
        RegionalMorphWorld.RegionTile right =
                c.worldA.tileAt(FullMorphLand.center(1), FullMorphLand.center(0));
        double boundaryX = FullMorphLand.center(1) - FullMorphLand.TILE_BLOCKS / 2;
        double[] offsets = {0.0, -0.5, 0.5, 50.0, 150.0, 220.0};
        double[] zs = {137.0, -180.0, 420.0, 700.0, -600.0};
        for (double dz : zs) {
            for (double off : offsets) {
                double x = boundaryX + off;
                double z = dz;
                if (Math.hypot(x - left.original().centerX(), z - left.original().centerZ())
                        > left.original().geometry().queryRadius()
                        || Math.hypot(x - right.original().centerX(), z - right.original().centerZ())
                        > right.original().geometry().queryRadius()) {
                    continue;
                }
                RegionalMorphWorld.Sample sl = c.worldA.sample(left, x, z);
                RegionalMorphWorld.Sample sr = c.worldA.sample(right, x, z);
                RegionalMorphWorld.Sample sc = c.worldA.sample(x, z);
                double de = Math.abs(sl.e() - sr.e());
                double dw = maxDiff(sl.weights(), sr.weights());
                boolean ok = de <= TOL_XTILE_E && dw <= TOL_XTILE_WEIGHT;
                double dl = Math.abs(sc.e() - sl.e());
                double dr = Math.abs(sc.e() - sr.e());
                boolean matches = Double.compare(sc.e(), sl.e()) == 0
                        || Double.compare(sc.e(), sr.e()) == 0;
                if (matches) {
                    canonicalMatches++;
                }
                worstE = Math.max(worstE, de);
                worstW = Math.max(worstW, dw);
                rows++;
                csv.add(String.format(Locale.ROOT,
                        "%.1f,%.1f,%.1f,%.17g,%.17g,%.3e,%.3e,%s,%.3e,%.3e,%s",
                        boundaryX, off, z, sl.e(), sr.e(), de, dw, ok ? "ok" : "MISMATCH", dl, dr,
                        matches));
                if (!ok) {
                    record("cross_tile", String.format(Locale.ROOT,
                            "forced sample at (%.2f,%.1f): de=%.3e dw=%.3e > %.1e/%.1e",
                            x, z, de, dw, TOL_XTILE_E, TOL_XTILE_WEIGHT));
                }
            }
        }
        require(rows >= 8, "only " + rows + " forced cross-tile points inside both query circles");
        require(worstE <= TOL_XTILE_E, "forced cross-tile: max |de| = " + worstE + " > " + TOL_XTILE_E);
        require(worstW <= TOL_XTILE_WEIGHT, "forced cross-tile: max |dw| = " + worstW + " > "
                + TOL_XTILE_WEIGHT);
        require(canonicalMatches == rows, "the canonical tileAt(x,z) sample is not bitwise equal to"
                + " either forced side at " + (rows - canonicalMatches) + " of " + rows + " points");
        write(out, "tile-boundary.csv", csv);
        metric("tile_boundary.points", rows);
        metric("tile_boundary.max_abs_de", worstE);
        metric("tile_boundary.max_abs_dw", worstW);
        metric("tile_boundary.canonical_matches", canonicalMatches);
        note("tile_boundary: " + rows + " forced cross-boundary points; max|de|=" + worstE
                + " max|dw|=" + worstW + " (tolerance " + TOL_XTILE_E + "/" + TOL_XTILE_WEIGHT + ")");
    }

    // ============================================================ 9. 边界法向

    private static void sectionBoundaries(Path out, Context c, Sweep sweep) throws Exception {
        for (String kind : KINDS) {
            require(!sweep.of(kind).isEmpty(),
                    "no " + kind + " region edge inside the image: the boundary class is missing");
        }
        List<String> rows = new ArrayList<>(List.of(
                "kind,candidate,edgeIndex,tileX,tileZ,edgeX,edgeZ,nx,nz,recipeA,recipeB,eps,"
                        + "abs_de,abs_dw,dw_ratio_vs_eps1,slope,hard_delta,hard_spread,allow_e,"
                        + "height_verdict,criteria,weight_converged,cellA,cellB"));
        double worstWeightRatio = 0;
        int weightFailures = 0;
        int heightFailures = 0;
        List<Candidate> all = new ArrayList<>();
        for (String kind : KINDS) {
            List<Candidate> bucket = sweep.of(kind);
            // band240 需要更大的 land-land 候选池，但 ±eps 收敛/端点剖面只取前 CAND_PER_KIND 条
            // （再多也不增加判据强度，只会线性变慢）。
            int n = Math.min(bucket.size(), CAND_PER_KIND);
            for (int ci = 0; ci < n; ci++) {
                Candidate cand = bucket.get(ci);
                all.add(cand);
                double[] stats = epsRows(rows, c, cand, ci, kind);
                worstWeightRatio = Math.max(worstWeightRatio, stats[0]);
                if (stats[1] != 0) {
                    weightFailures++;
                }
                if (stats[2] != 0) {
                    heightFailures++;
                }
            }
        }
        require(weightFailures == 0, weightFailures + " boundary candidates show a weight STEP"
                + " (|dw(0.01)| did not shrink versus |dw(1)|)");
        require(heightFailures == 0, heightFailures + " boundary candidates show a height jump that"
                + " neither converges as eps->0 nor is explained by the endpoint recipes' own hard"
                + " fields (see the height verdict column in normals.csv)");
        write(out, "normals.csv", rows);
        endpointRows(out, c, all);
        metric("boundaries.candidates_land_land", sweep.of("land-land").size());
        metric("boundaries.candidates_sea_sea", sweep.of("sea-sea").size());
        metric("boundaries.candidates_land_sea", sweep.of("land-sea").size());
        metric("boundaries.max_weight_ratio", worstWeightRatio);
        metric("boundaries.weight_failures", weightFailures);
        metric("boundaries.height_failures", heightFailures);
        note("boundaries: land-land=" + sweep.of("land-land").size() + " sea-sea="
                + sweep.of("sea-sea").size() + " land-sea=" + sweep.of("land-sea").size()
                + "; worst weight ratio=" + worstWeightRatio);
        band240(out, c, sweep);
    }

    /**
     * 一条候选边中点沿法向的 ±eps 判据。
     *
     * <h3>★ 权重：收敛（阶跃检测，唯一真判据）</h3>
     * <p>{@code |dw(0.01)| ≤ 0.25·|dw(1)| + 1e-6}。若正中间存在阶跃，{@code |dw|} 不随 eps 缩小。</p>
     *
     * <h3>★ 高度：收敛 <b>或</b> 局部可解释 —— 两条之一成立即可</h3>
     * <p><b>不</b>要求高度场处处光滑：劣地的 {@code Steps}、台地的 {@code Terrace} 本身带固有台阶，
     * 山谷/山脊线也可能正好穿过的采样点。这里只要求下面两条之一：</p>
     * <ol>
     *   <li><b>收敛</b>：{@code |de(0.01)| ≤ 0.25·|de(1)| + 1e-6} —— 说明 de 只是正常斜率
     *       （斜率 ≈ de/2eps，与 eps 无关），<b>没有阶跃</b>；</li>
     *   <li><b>局部可解释</b>：{@code |de| ≤ 两端 hard 场自身变化 + 权重变化×端点落差 + 1e-6} ——
     *       说明这一点的高度变化完全由"配方自身在动"和"权重在动"解释掉了。</li>
     * </ol>
     * <p>两条都<b>不是</b>光滑性要求；只有"既不收敛、又超出局部可解释上界"才是新阶跃。
     * 局部可解释上界只用于记录，不用于放宽。每条候选实际走了哪一条，见
     * {@code normals.csv} 的 {@code criteria} 列与 {@code metrics.txt} 的
     * {@code heightCriterion=} 行。</p>
     *
     * @return {@code {maxWeightRatio, weightFailed, heightFailed}}
     */
    private static double[] epsRows(List<String> rows, Context c, Candidate cand, int index,
                                    String kind) {
        int start = rows.size();
        double[] dw = new double[EPS.length];
        double[] de = new double[EPS.length];
        boolean[] okLocal = new boolean[EPS.length];
        List<double[]> localRows = new ArrayList<>();
        int heightFailed = 0;
        for (int i = 0; i < EPS.length; i++) {
            double eps = EPS[i];
            double px = cand.x + cand.nx * eps;
            double pz = cand.z + cand.nz * eps;
            double mx = cand.x - cand.nx * eps;
            double mz = cand.z - cand.nz * eps;
            RegionalMorphWorld.Sample sp = c.worldA.sample(px, pz);
            RegionalMorphWorld.Sample sm = c.worldA.sample(mx, mz);
            double dE = Math.abs(sp.e() - sm.e());
            double dW = maxDiff(sp.weights(), sm.weights());
            // 局部可解释上界：两端 hard 场自身的变化 + 权重变化 × 端点落差。
            double hardAPlus = c.worldA.hardE(px, pz, cand.recipeA);
            double hardAMinus = c.worldA.hardE(mx, mz, cand.recipeA);
            double hardBPlus = c.worldA.hardE(px, pz, cand.recipeB);
            double hardBMinus = c.worldA.hardE(mx, mz, cand.recipeB);
            double hardDelta = Math.max(Math.abs(hardAPlus - hardAMinus),
                    Math.abs(hardBPlus - hardBMinus));
            double spread = Math.abs(hardAPlus - hardBPlus);
            double allow = hardDelta + spread * dW + 1e-6;
            okLocal[i] = dE <= allow;
            dw[i] = dW;
            de[i] = dE;
            localRows.add(new double[]{eps, dE, dW, hardDelta, spread, allow});
        }
        double ratio = dw[dw.length - 1] / (dw[0] + CONVERGE_ABS);
        boolean wConverged = dw[dw.length - 1] <= CONVERGE_RATIO * dw[0] + CONVERGE_ABS;
        if (!wConverged) {
            record("weight", String.format(Locale.ROOT,
                    "%s edge %d at (%.1f,%.1f): weight STEP, |dw(0.01)|=%.3e vs |dw(1)|=%.3e",
                    kind, cand.edgeIndex, cand.x, cand.z, dw[dw.length - 1], dw[0]));
        }
        double eRatio = de[de.length - 1] / (de[0] + CONVERGE_ABS);
        boolean eConverged = de[de.length - 1] <= CONVERGE_RATIO * de[0] + CONVERGE_ABS;
        String criterion;
        if (eConverged) {
            criterion = "converges";
        } else if (okLocal[okLocal.length - 1]) {
            criterion = "local";
        } else {
            criterion = "STEP";
            heightFailed = 1;
            record("height", String.format(Locale.ROOT,
                    "%s edge %d at (%.1f,%.1f): de(0.01)=%.3e neither converges (ratio %.4f vs"
                            + " |de(1)|=%.3e) nor is locally explained",
                    kind, cand.edgeIndex, cand.x, cand.z, de[de.length - 1], eRatio, de[0]));
        }
        for (double[] lr : localRows) {
            rows.add(String.format(Locale.ROOT,
                    "%s,%d,%d,%d,%d,%.4f,%.4f,%.6f,%.6f,%d,%d,%.2f,%.3e,%.3e,%.4f,%.6f,"
                            + "%.3e,%.3e,%.3e,%s,%s,%s,%s,%s",
                    kind, index, cand.edgeIndex, cand.tileX, cand.tileZ, cand.x, cand.z,
                    cand.nx, cand.nz, cand.recipeA, cand.recipeB, lr[0], lr[1], lr[2],
                    lr[2] / (dw[0] + CONVERGE_ABS), lr[1] / (2 * lr[0]), lr[3], lr[4], lr[5],
                    "STEP".equals(criterion) ? "HEIGHT_JUMP" : "ok", criterion, wConverged,
                    cand.cellA, cand.cellB));
        }
        note(String.format(Locale.ROOT,
                "cand %s#%d edge=%d at (%.1f,%.1f) dw %.2e/%.2e/%.2e de %.2e/%.2e/%.2e"
                        + " eRatio=%.4f weightConverged=%s heightCriterion=%s",
                kind, index, cand.edgeIndex, cand.x, cand.z, dw[0], dw[1], dw[2],
                de[0], de[1], de[2], eRatio, wConverged, criterion));
        return new double[]{ratio, wConverged ? 0 : 1, heightFailed};
    }

    /** 端点跟踪：±240 / 0 处的 e、主导条目、赢家 recipe、seaWeight。 */
    private static void endpointRows(Path out, Context c, List<Candidate> all) throws Exception {
        List<String> rows = new ArrayList<>(List.of(
                "kind,candidate,s,x,z,e,dominant,winnerRecipe,seaWeight,active,is_endpoint"));
        List<String> tracking = new ArrayList<>(List.of(
                "kind,candidate,endpoint_minus240_e,endpoint0_e,endpoint_plus240_e,"
                        + "sea_minus240,sea_0,sea_plus240,sea_span,crosses_coast"));
        for (int ci = 0; ci < all.size(); ci++) {
            Candidate cand = all.get(ci);
            double[] e = new double[3];
            double[] sea = new double[3];
            double[] svals = {-NORMAL_HALF, 0.0, NORMAL_HALF};
            for (int i = 0; i < svals.length; i++) {
                double s = svals[i];
                double x = cand.x + cand.nx * s;
                double z = cand.z + cand.nz * s;
                RegionalMorphWorld.Sample sample = c.worldA.sample(x, z);
                e[i] = sample.e();
                sea[i] = sample.seaWeight();
                rows.add(String.format(Locale.ROOT, "%s,%d,%.1f,%.4f,%.4f,%.17g,%d,%d,%.6f,%d,%s",
                        cand.kind, ci, s, x, z, e[i], sample.dominant(), sample.winnerRecipe(),
                        sea[i], sample.active(), Math.abs(s) == NORMAL_HALF ? "endpoint" : "midpoint"));
            }
            double seaSpan = Math.max(sea[0], Math.max(sea[1], sea[2]))
                    - Math.min(sea[0], Math.min(sea[1], sea[2]));
            boolean crosses = seaSpan > 0.5;
            tracking.add(String.format(Locale.ROOT, "%s,%d,%.17g,%.17g,%.17g,%.6f,%.6f,%.6f,%.6f,%s",
                    cand.kind, ci, e[0], e[1], e[2], sea[0], sea[1], sea[2], seaSpan, crosses));
            if ("land-sea".equals(cand.kind)) {
                require(crosses, "land-sea candidate " + ci + " does not actually cross the coast:"
                        + " seaWeight span " + seaSpan + " (" + sea[0] + "/" + sea[1] + "/" + sea[2] + ")");
            }
            if ("sea-sea".equals(cand.kind)) {
                require(Math.min(sea[0], Math.min(sea[1], sea[2])) > 0.5,
                        "sea-sea candidate " + ci + " has land weight at a profile point: seaWeight "
                                + sea[0] + "/" + sea[1] + "/" + sea[2]);
            }
            require(Double.isFinite(e[0]) && Double.isFinite(e[1]) && Double.isFinite(e[2]),
                    "non-finite e on boundary profile " + ci);
        }
        write(out, "normal-endpoints.csv", rows);
        write(out, "normal-tracking.csv", tracking);
    }

    // ============================================================ 10. 带宽 240

    /**
     * <b>带宽 240 的实测剖面</b>。
     *
     * <h3>★ 判据必须按 "wA 从 1 降到 0 的跨度"，不能按 "wA/wB 首次非零"</h3>
     * <p>{@code progress(sd)} 里的 {@code sd} 是<b>该格到它自己最近边</b>的有符号距离，
     * 不是到 A|B 这条边的距离。所以 {@code wB > 0} 只说明"B 自己的最近边在 120 块内"，
     * 与 A|B 这条边在哪无关 —— 拿它当带宽端点会得出 480 这种荒诞值（整窗都在带上）。</p>
     *
     * <p>正确口径（纯 A|B 直边、两格权重互补时的解析结果）：
     * {@code wA = smoothstep(0.5 + t/240)}、{@code wB = smoothstep(0.5 − t/240)}，
     * 故 {@code wA} 在 {@code t ≥ +120} 处恰为 1、在 {@code t ≤ −120} 处恰为 0
     * ⇒ <b>A→B 交接跨度恰为 240 块</b>。这里就沿法向实测这个跨度。</p>
     *
     * <p>剖面用 ±{@value #BAND_PROFILE_HALF} 块（比 240 宽，才能看到两侧饱和），
     * 并要求：过渡区间内只有 A/B 两个条目有权重、{@code wA} 单调不增、两端分别饱和到 1 / 0。</p>
     */
    /**
     * 线性插值出 {@code w(s)} 穿过 {@code target} 的 {@code s} 位置（诊断列用）。
     *
     * @param decreasing {@code true} = 期望 w 随 s 递减
     * @return 交点 s；找不到返回 {@code NaN}
     */
    private static double crossoverS(double[] s, double[] w, double target, boolean decreasing) {
        for (int i = 0; i + 1 < w.length; i++) {
            double a = w[i];
            double b = w[i + 1];
            boolean crossed = decreasing ? (a >= target && b <= target) : (a <= target && b >= target);
            if (!crossed) {
                continue;
            }
            if (a == b) {
                return s[i];
            }
            double t = (target - a) / (b - a);
            return s[i] + t * (s[i + 1] - s[i]);
        }
        return Double.NaN;
    }

    private static void band240(Path out, Context c, Sweep sweep) throws Exception {
        List<String> csv = new ArrayList<>(List.of(
                "candidate,alongIndex,s,e,active,seaWeight,wA,wB,other_weight_sum,verdict"));
        Band240Result r = searchBand240(c, sweep);
        if (r.chosen() >= 0) {
            checkBand240Found(r);
            writeBand240Found(csv, r);
        } else {
            writeBand240Skipped(csv, r);
        }
        write(out, "band-240.csv", csv);
    }

    /** 240 带宽剖面的搜索结果：选中项与被拒项。 */
    private record Band240Result(int chosen, double width, double sLo, double sHi,
                                 double worstOther, double otherAtEnds, int worstActive,
                                 int tried, int rejected, double bestRejectedOther,
                                 int bestRejectedActive, double bestRejectedSpan,
                                 String bestRejectedAt, List<String> chosenRows,
                                 List<String> bestRejectedRows) { }

    /**
     * 沿 land-land 候选边找一条"过渡区间内只有 A/B 两个条目"的剖面。
     *
     * <p>只取边中点常落在 Voronoi 三交汇附近 ⇒ 法向上真的会出现第三个条目。
     * 沿该边的 world 折线取多个<b>内点</b>作原点重试（与
     * {@code LocalBoundaryGeometry.profileCandidates} 的判据同源：那里也要求
     * "整条剖面恰好两个正权重格"才接受候选），只改取点位置，不改地形、不改带宽。</p>
     */
    private static Band240Result searchBand240(Context c, Sweep sweep) {
        int chosen = -1;
        double width = Double.NaN;
        double sLo = Double.NaN;
        double sHi = Double.NaN;
        double worstOther = 0;
        double otherAtEnds = 0;
        int worstActive = 0;
        int tried = 0;
        int rejected = 0;
        double bestRejectedOther = Double.POSITIVE_INFINITY;
        int bestRejectedActive = 0;
        double bestRejectedSpan = Double.NaN;
        String bestRejectedAt = "none";
        List<String> bestRejectedRows = new ArrayList<>();
        List<String> chosenRows = new ArrayList<>();
        for (int ci = 0; ci < sweep.of("land-land").size() && chosen < 0; ci++) {
            Candidate cand = sweep.of("land-land").get(ci);
            for (int along = 0; along < EDGE_ALONG.length && chosen < 0; along++) {
                double[] origin = edgeOrigin(c, cand, along);
                if (origin == null) {
                    continue;
                }
                tried++;
                SpanProbe p = probeSpan(c, cand, origin, along, ci);
                if (p == null) {
                    rejected++;
                    continue;
                }
                // ★ 真判据：过渡区间内<b>只有</b> A/B 两个条目有权重（{@code other == 0}）。
                // ⚠ 不能用 {@code active <= 2} 代替：{@code active} 只数"非零项个数"，
                // 而 {@code {A, C}} 也是 2 —— 但那已经是第三个条目 C 进来了。
                if (p.otherInTransition() > 1e-12) {
                    rejected++;
                    if (p.otherInTransition() < bestRejectedOther) {
                        bestRejectedOther = p.otherInTransition();
                        bestRejectedActive = p.activeInTransition();
                        bestRejectedSpan = p.sHi() - p.sLo();
                        bestRejectedAt = String.format(Locale.ROOT, "cand%d/along%d (%.1f,%.1f)",
                                ci, EDGE_ALONG[along], origin[0], origin[1]);
                        bestRejectedRows = p.rows();
                    }
                    continue;
                }
                if (p.activeInTransition() > 2) {
                    rejected++;
                    continue;
                }
                chosen = ci;
                width = p.sHi() - p.sLo();
                sLo = p.sLo();
                sHi = p.sHi();
                worstOther = p.otherInTransition();
                worstActive = p.activeInTransition();
                otherAtEnds = p.otherOutside();
                chosenRows = p.rows();
            }
        }
        return new Band240Result(chosen, width, sLo, sHi, worstOther, otherAtEnds, worstActive,
                tried, rejected, bestRejectedOther, bestRejectedActive, bestRejectedSpan,
                bestRejectedAt, chosenRows, bestRejectedRows);
    }

    /** 单条剖面原点的采样结果。 */
    private record SpanProbe(double sLo, double sHi, double otherInTransition,
                             int activeInTransition, double otherOutside, List<String> rows) { }

    /**
     * 沿一个剖面原点从 −{@value #BAND_PROFILE_HALF} 扫到 +{@value #BAND_PROFILE_HALF}，
     * 用 {@code wA} 落在 (0,1) 的 s 范围确定过渡区间。
     *
     * <p>★ 正确口径：在纯 A|B 直边上 {@code wA = smoothstep(0.5 + t/240)}，所以 {@code wA}
     * 落在<b>开区间 (0,1)</b> 的 s 范围恰是 {@code t ∈ (−120, +120)} ⇒ <b>跨度 240 块</b>。
     * 用"wA 首次 &gt;0"与"wA 最后 &lt;1"夹出，比拿 {@code wA&gt;0/wB&gt;0} 当端点稳
     * （那两者是各自格到自己最近边的距离，与 A|B 这条边无关）。</p>
     *
     * @return {@code null} 若该原点夹不出有效区间
     */
    private static SpanProbe probeSpan(Context c, Candidate cand, double[] origin, int along,
                                       int ci) {
        int steps = (int) Math.round(2 * BAND_PROFILE_HALF / BAND_STEP);
        double[] sAll = new double[steps + 1];
        double[] wAs = new double[steps + 1];
        double[] others = new double[steps + 1];
        int[] actives = new int[steps + 1];
        List<String> rows = new ArrayList<>();
        for (int k = 0; k <= steps; k++) {
            double s = -BAND_PROFILE_HALF + k * BAND_STEP;
            double x = origin[0] + origin[2] * s;
            double z = origin[1] + origin[3] * s;
            RegionalMorphWorld.Sample sample = c.worldA.sample(x, z);
            double[] w = sample.weights();
            double other = 0;
            for (int j = 0; j < COUNT; j++) {
                if (j != cand.recipeA && j != cand.recipeB) {
                    other += w[j];
                }
            }
            sAll[k] = s;
            wAs[k] = w[cand.recipeA];
            others[k] = other;
            actives[k] = sample.active();
            rows.add(String.format(Locale.ROOT,
                    "%d,%d,%.2f,%.17g,%d,%.6f,%.17g,%.17g,%.3e,-\n", ci, EDGE_ALONG[along], s,
                    sample.e(), sample.active(), sample.seaWeight(), wAs[k], w[cand.recipeB],
                    other));
        }
        double lo = Double.NaN;
        double hi = Double.NaN;
        for (int k = 0; k <= steps; k++) {
            if (wAs[k] > 0.0 && Double.isNaN(lo)) {
                lo = sAll[k];
            }
            if (wAs[k] < 1.0) {
                hi = sAll[k];
            }
        }
        if (Double.isNaN(lo) || Double.isNaN(hi) || !(hi > lo)) {
            return null;
        }
        // ★ 判据只覆盖<b>过渡区间</b> [lo,hi]（wA 从 1 交接到 0 的那 240 块）。
        // 区间之外是饱和端：A 或 B 独占，那里出现第三个条目属于"剖面原点离三交汇太近"
        // ——换原点重试，而不是把上界放宽。
        double otherIn = 0;
        double otherOut = 0;
        int activeIn = 0;
        for (int k = 0; k <= steps; k++) {
            boolean inside = sAll[k] >= lo && sAll[k] <= hi;
            if (inside) {
                otherIn = Math.max(otherIn, others[k]);
                activeIn = Math.max(activeIn, actives[k]);
            } else {
                otherOut = Math.max(otherOut, others[k]);
            }
        }
        return new SpanProbe(lo, hi, otherIn, activeIn, otherOut, rows);
    }

    /** 找到可用剖面时的三条判据。 */
    private static void checkBand240Found(Band240Result r) {
        require(Math.abs(r.width() - TOTAL_BAND) <= 1.5, "measured A->B crossover width "
                + r.width() + " blocks != " + TOTAL_BAND);
        require(r.worstActive() <= 2, "active recipe count reached " + r.worstActive()
                + " inside the A->B transition interval");
        require(r.width() <= 2 * NORMAL_HALF + 1e-6, "transition span " + r.width()
                + " exceeds the sampled +/-" + NORMAL_HALF + " window");
    }

    private static void writeBand240Found(List<String> csv, Band240Result r) {
        csv.addAll(r.chosenRows());
        csv.add(String.format(Locale.ROOT, "measured_width,0,0,0,0,0,%.4f,%.4f,%.3e,"
                        + "transition span [%.2f,%.2f] = %.2f blocks (expected %.1f);"
                        + " other-cell weight inside span=%.3e",
                r.sLo(), r.sHi(), r.worstOther(), r.sLo(), r.sHi(), r.width(), TOTAL_BAND,
                r.worstOther()));
        metric("band240.candidate", r.chosen());
        metric("band240.width_blocks", r.width());
        metric("band240.other_cell_weight_in_transition", r.worstOther());
        metric("band240.other_weight_at_ends", r.otherAtEnds());
        metric("band240.active_max", r.worstActive());
        metric("band240.spans_tried", r.tried());
        metric("band240.spans_rejected", r.rejected());
        note(String.format(Locale.ROOT, "band240: candidate %d A->B transition spans"
                        + " [%.2f,%.2f] = %.2f blocks at %.1f-block sampling (expected %.1f,"
                        + " so the discretisation alone accounts for the 0.5-block gap);"
                        + " only the two endpoint recipes carry weight across the span;"
                        + " outside it the weight goes to other recipes (max %.3e), which is"
                        + " the saturated side",
                r.chosen(), r.sLo(), r.sHi(), r.width(), BAND_STEP, TOTAL_BAND, r.otherAtEnds()));
    }

    /** 没有可用剖面时如实记为 skipped（不当作 pass），并留下最接近的一条。 */
    private static void writeBand240Skipped(List<String> csv, Band240Result r) {
        csv.add("skipped,0,0,0,0,0,0,0,0,no_clean_two_candidate_transition_inside_image");
        metric("band240.spans_tried", r.tried());
        metric("band240.spans_rejected", r.rejected());
        metric("band240.best_rejected_other", r.bestRejectedOther());
        metric("band240.best_rejected_active", r.bestRejectedActive());
        metric("band240.best_rejected_span", r.bestRejectedSpan());
        csv.addAll(r.bestRejectedRows());
        note("band240: none of the " + r.tried() + " sampled boundary spans produced a clean"
                + " two-candidate A->B transition across +/-240; the analytic band constants"
                + " (progress identity, measured +/-120 edges, 240 total) remain the gate and"
                + " this profile is reported as skipped, NOT as a pass");
        note(String.format(Locale.ROOT, "band240 best rejected span: %s other=%.4e"
                        + " activeMax=%d span=%.2f blocks -- the A|B crossover is never isolated"
                        + " because a third cell's 240-block band always reaches into the"
                        + " transition; LocalBoundaryGeometry.profileCandidates has exactly the"
                        + " same requirement (\"always exactly 2 positive-weight candidates\") and"
                        + " this P3 sandbox's ~3600-block cells make that rarer than in the"
                        + " narrower 18-entry arm",
                r.bestRejectedAt(), r.bestRejectedOther(), r.bestRejectedActive(),
                r.bestRejectedSpan()));
    }

    /**
     * 候选边第 {@code which} 个内点的世界位置与单位法向（{@code {x, z, nx, nz}}）。
     *
     * <p>用 {@link RegionalMorphWorld#tileAt} 取回该边的 world 折线（候选里只存了中点，
     * 折线本身要能从 tile 重新取到）；越界或无内点返回 {@code null}。</p>
     */
    private static double[] edgeOrigin(Context c, Candidate cand, int which) {
        RegionalMorphWorld.RegionTile rt =
                c.worldA.tileAt(FullMorphLand.center(cand.tileX), FullMorphLand.center(cand.tileZ));
        for (LocalBoundaryGeometry.Edge e : rt.original().geometry().edges()) {
            if (e.index != cand.edgeIndex) {
                continue;
            }
            int n = e.wx.length;
            if (n < 5) {
                return null;
            }
            int idx = (int) Math.round((which + 1.0) / (EDGE_ALONG.length + 1.0) * (n - 1));
            idx = Math.max(1, Math.min(n - 2, idx));
            double nx = -(e.wz[idx + 1] - e.wz[idx - 1]);
            double nz = e.wx[idx + 1] - e.wx[idx - 1];
            double len = Math.hypot(nx, nz);
            if (!(len > 0)) {
                return null;
            }
            return new double[]{e.wx[idx], e.wz[idx], nx / len, nz / len};
        }
        return null;
    }

    // ============================================================ 收尾 / 台账

    private static void finish(Path out) throws Exception {
        List<String> failRows = new ArrayList<>();
        failRows.add("kind,message");
        failRows.addAll(FAILURE_ROWS);
        write(out, "failures.csv", failRows);

        List<String> txt = new ArrayList<>();
        txt.add("seed=" + SEED + " (canonical Gallery only; no other seed is used)");
        txt.add("checks=" + checks);
        txt.add("failures=" + failures);
        txt.add("firstFailure=" + (firstFailure == null ? "" : firstFailure));
        for (Map.Entry<String, Double> e : METRICS.entrySet()) {
            txt.add(String.format(Locale.ROOT, "%s=%.9g", e.getKey(), e.getValue()));
        }
        for (String n : NOTES) {
            txt.add("note: " + n);
        }
        txt.add("result=" + (failures == 0 ? "PASS" : "FAIL"));
        Files.writeString(out.resolve("metrics.txt"), String.join("\n", txt) + "\n",
                StandardCharsets.UTF_8);
        Files.writeString(out.resolve("metrics.json"), json(), StandardCharsets.UTF_8);
        Files.writeString(out.resolve("limits.txt"), limits(), StandardCharsets.UTF_8);
    }

    private static String json() {
        StringBuilder sb = new StringBuilder();
        sb.append("{\n");
        sb.append("  \"entry\": \"com.geogenesis.worldgen.terrain.candidate.RegionalMorphWorldCheck\",\n");
        sb.append(String.format(Locale.ROOT, "  \"seed\": %d,\n", SEED));
        sb.append("  \"canonicalSeedOnly\": true,\n");
        sb.append(String.format(Locale.ROOT,
                "  \"image\": {\"x0\": %.1f, \"x1\": %.1f, \"z0\": %.1f, \"z1\": %.1f,"
                        + " \"n\": %d, \"step\": %.1f},\n", IMG_X0, IMG_X1, IMG_Z0, IMG_Z1, IMG_N,
                IMG_STEP));
        sb.append(String.format(Locale.ROOT,
                "  \"constants\": {\"LAND_COUNT\": %d, \"COUNT\": %d, \"bandHalf\": %.1f,"
                        + " \"bandTotal\": %.1f},\n", LAND, COUNT, HALF_BAND, TOTAL_BAND));
        sb.append(String.format(Locale.ROOT,
                "  \"tolerances\": {\"pureLand\": %.1f, \"pureSea\": %.1f,"
                        + " \"crossTileWeight\": %.1e, \"crossTileE\": %.1e,"
                        + " \"weightConvergenceRatio\": %.2f, \"weightConvergenceAbs\": %.1e},\n",
                TOL_PURE, TOL_PURE, TOL_XTILE_WEIGHT, TOL_XTILE_E, CONVERGE_RATIO, CONVERGE_ABS));
        sb.append("  \"checks\": ").append(checks).append(",\n");
        sb.append("  \"failures\": ").append(failures).append(",\n");
        sb.append("  \"firstFailure\": ")
                .append(firstFailure == null ? "null"
                        : "\"" + escape(firstFailure) + "\"")
                .append(",\n");
        sb.append("  \"metrics\": {");
        boolean firstEntry = true;
        for (Map.Entry<String, Double> e : METRICS.entrySet()) {
            if (!firstEntry) {
                sb.append(", ");
            }
            firstEntry = false;
            sb.append('"').append(e.getKey()).append("\": ");
            double v = e.getValue();
            sb.append(Double.isFinite(v) ? String.format(Locale.ROOT, "%.9g", v) : "null");
        }
        sb.append("},\n");
        sb.append("  \"notes\": [");
        for (int i = 0; i < NOTES.size(); i++) {
            if (i > 0) {
                sb.append(", ");
            }
            sb.append('"').append(escape(NOTES.get(i))).append('"');
        }
        sb.append("],\n");
        sb.append("  \"evidence\": [\"constants.csv\", \"geometry-parity.csv\", \"merger.csv\","
                + " \"counts.csv\", \"recipe-histogram.csv\", \"pure-land.csv\","
                + " \"pure-land-summary.csv\", \"landweight1-trap.csv\","
                + " \"landweight1-trap-summary.csv\", \"ocean-slots.csv\","
                + " \"ocean-slots-summary.csv\", \"ocean-slot-histogram.csv\","
                + " \"pure-sea.csv\", \"pure-sea-summary.csv\","
                + " \"identities.csv\", \"identity-windows.csv\", \"tile-boundary.csv\","
                + " \"normals.csv\", \"normal-endpoints.csv\", \"normal-tracking.csv\","
                + " \"band-240.csv\", \"failures.csv\", \"section-times.txt\"],\n");
        sb.append("  \"result\": \"").append(failures == 0 ? "PASS" : "FAIL").append("\"\n");
        sb.append("}\n");
        return sb.toString();
    }

    private static String limits() {
        List<String> lines = new ArrayList<>();
        lines.addAll(limitsScope());
        lines.addAll(limitsPureLand());
        lines.addAll(limitsOceanSlots());
        lines.addAll(limitsRemaining());
        return String.join("\n", lines);
    }


    /** 范围 / 种子。 */
    private static List<String> limitsScope() {
        return List.of(
                "scope=RegionalMorphWorld P3 sandbox: per-1024-tile LocalBoundaryGeometry -> site weights"
                        + " -> per-site recipe draw -> merged 23 weights -> shared NodeMorphEngine",
                "canonical_seed_only=only RecipeMorphCatalog.GALLERY_SEED. FullMorphLand rejects every"
                        + " other seed, and this check adds no seed of its own");
    }

    /** 纯陆 / landWeight==1 陷阱回归。 */
    private static List<String> limitsPureLand() {
        return List.of(
                "pure_land=bitwise, tolerance 0. The check also records and asserts the normalize"
                        + " invariant Sum(w[0..17]) == 1.0 on pure-land points."
                        + " FullMorphLand.normalize patches the rounding residual into the argmax entry and"
                        + " the ocean slots stay +0.0 on a pure land point, so adding them cannot change the"
                        + " partial sum. NOTE: this is the merger's invariant, NOT evaluate()'s branch"
                        + " condition -- the former 'landWeight == 1' test is exactly what the"
                        + " landWeight==1 trap broke (see next entry)",
                "landweight1_trap=REGRESSION for the defect the full Gallery export hit: with"
                        + " w[0]=1.0 and one ocean component = 1e-18 (also 1e-20 and"
                        + " Double.MIN_VALUE), BOTH the in-order land sum and the full sum are exactly"
                        + " 1.0 (because 1.0 + eps rounds back to 1.0), yet an ocean component is"
                        + " genuinely positive. A sum-based pure-land test therefore misclassifies the"
                        + " vector and NodeMorphEngine.sampleLand rejects it with"
                        + " 'land-only weights required'. The fixed evaluate() must inspect whether any"
                        + " ocean component is actually > 0; this check asserts (a) evaluate returns a"
                        + " finite value, (b) it is bitwise engine.sampleE + landWeight*delta, and"
                        + " (c) at points where the two branches are numerically distinguishable it"
                        + " demonstrably takes the mixed branch rather than the sampleLand shortcut",
                "trap_eps_caveat=only eps values for which 'landWeight + eps == 1.0' in double"
                        + " actually construct the trap; the check skips (and reports the count of)"
                        + " any eps that does not, rather than fabricating a pass");
    }

    /** 海洋票槽可达性回归。 */
    private static List<String> limitsOceanSlots() {
        return List.of(
                "ocean_slot_reachability=REGRESSION for the defect main's audit found:"
                        + " RefNoise.hash2D ends with 'hash ^= hash >> 13' using an ARITHMETIC shift,"
                        + " which copies the sign bit back in and leaves the sign bit at 0, so the"
                        + " return value is a 31-bit non-negative int rather than uint32."
                        + " unit(h) = h * 2^-32 therefore yields draw in [0, 0.5), and"
                        + " (int)(draw * 12) can only reach ocean slots 0..5, so slots 6..11 and any"
                        + " ocean recipe that lives only there never appear. The fix is"
                        + " unit(h) = h * 2^-31. This check (a) reflects the implementation's own"
                        + " unit()/base(), (b) recomputes the slot independently of the oceanSlots"
                        + " table over cellX/cellZ in [-50,50] and requires all 12 slots reachable plus"
                        + " unit max > 0.99 and min < 0.01, (c) requires choose() to agree with that"
                        + " recomputation cell by cell on the real code path, and (d) requires all 5"
                        + " ocean recipes to be chosen at least once over a wider cell grid",
                "ocean_slot_not_self_proof=the gate is 'every slot reachable' and 'each ocean recipe"
                        + " chosen at least once'. No count is compared against"
                        + " UnifiedTerrainCatalog.buildOceanSlots' expansion table, so the check"
                        + " cannot pass by agreeing with itself. oceanSlot is computed unconditionally"
                        + " in choose(), i.e. regardless of isSea, so the reachability claim does not"
                        + " depend on constructing a point that is actually classified as sea; the"
                        + " check does nonetheless report the sea/land split of its sample");
    }

    /** 纯海 / 身份 / 跨 tile / 边界收敛 / 带宽 / 边界声明。 */
    private static List<String> limitsRemaining() {
        return List.of(
                "pure_sea=bitwise against an independently constructed NodeMorphEngine.sampleE. Structural"
                        + " argument: landWeight == 0 makes the coverage term landWeight*delta exactly 0,"
                        + " so a pure sea sample can only equal the bare engine evaluation; the cover guard"
                        + " {landWeight>0 && alpha>0} is asserted to fire zero times",
                "identity=cell identity is decided per (cellX,cellZ) from the inverse-warped site"
                        + " position, so two INDEPENDENT RegionalMorphWorld instances over different"
                        + " (including neighbouring and overlapping) geometry windows must produce"
                        + " bit-identical SiteChoice records. The second world never calls tileAt, so its"
                        + " choices are always first-time computations; this is what makes the check more"
                        + " than a cache self-proof. A reverse-order rebuild of recipes[] is included",
                "identity_window_note=LocalBoundaryGeometry windows are narrow (radius 728 blocks with a"
                        + " 120-block halo over a ~3600-block cell grid), so only a handful of cells are"
                        + " shared per window pair. The measured shared-cell count is written out; the gate"
                        + " only requires that at least one shared cell exists and that ALL shared cells"
                        + " agree",
                "cross_tile=the same world point is force-sampled through BOTH tiles that contain it near a"
                        + " tile boundary, using sample(tile,x,z) which bypasses tileAt. Tolerance 2e-3 on"
                        + " weights and on e is inherited from the geometry (120-block weight band,"
                        + " 0.02-block inverse-warp residual, 2-block maximum segment). Measured values are"
                        + " written out; a failure is NOT relaxed",
                "boundary_convergence=weights must converge as eps -> 0"
                        + " (|dw(0.01)| <= 0.25*|dw(1)| + 1e-6). That is the real step detector. Height is"
                        + " deliberately NOT required to be smooth: badlands Steps and the plateau Terrace"
                        + " carry intrinsic steps, so the height criterion only demands"
                        + " |de| <= (the two endpoint recipes' own hard-field change) + (weight change)"
                        + " *(endpoint spread). Requiring a globally C1 height field would be wrong",
                "band_240=progress(s) is verified to be exactly one smoothstep of s/240 with a 120-block"
                        + " half width whose saturation edges are measured by bisection rather than"
                        + " assumed, and the double-ease counterexample is quantified so that gate is not"
                        + " vacuous. One land-land boundary is profiled across +/-360 and its A->B"
                        + " crossover is measured as the s-range where wA stays strictly inside (0,1);"
                        + " the live measurement is written to band-240.csv / metrics.txt"
                        + " (band240.width_blocks) rather than restated here, because it depends on"
                        + " 0.5-block sampling and on which boundary the sweep happens to pick.",
                "not_covered=world injectivity of the inverse warp (unlimited-world correctness is not"
                        + " claimed), the full 1401 map render, visual acceptability,"
                        + " Minecraft/runWorldgenGate, chunk-generation cost, and any change to"
                        + " NodeMorphEngine, its parameters, or FullMorphLand",
                "read_only=no production or sandbox class is modified, no -D property is set, no new seed"
                        + " is introduced; this file only observes existing APIs");
    }

    // ============================================================ 小工具

    private static String row(String name, double value, String source, String expected, boolean ok) {
        return String.format(Locale.ROOT, "%s,%.9g,%s,%s,%s", name, value, source, expected, ok);
    }

    private static String escape(String s) {
        return s.replace('\\', '/').replace('"', '\'').replace('\n', ' ');
    }

    /** 按 tile 分组排序，避免纯陆/纯海段落因 LRU 反复重建几何。 */
    private static List<double[]> ordered(List<double[]> points) {
        List<double[]> copy = new ArrayList<>(points);
        copy.sort(Comparator
                .comparingInt((double[] p) -> FullMorphLand.tileIndex(p[1]))
                .thenComparingInt(p -> FullMorphLand.tileIndex(p[0])));
        return copy;
    }

    private static double landEOf(double h) {
        return Math.max(CombineCandidate.GROUND_E + h * CombineCandidate.E_SPAN, 0.0);
    }

    private static double inOrderSum(double[] w, int from, int to) {
        double s = 0;
        for (int i = from; i < to; i++) {
            s += w[i];
        }
        return s;
    }

    private static double maxDiff(double[] a, double[] b) {
        double worst = 0;
        int n = Math.min(a.length, b.length);
        for (int i = 0; i < n; i++) {
            worst = Math.max(worst, Math.abs(a[i] - b[i]));
        }
        return worst;
    }

    private static boolean sameChoice(RegionalMorphWorld.SiteChoice a,
                                      RegionalMorphWorld.SiteChoice b) {
        return a.cellX() == b.cellX()
                && a.cellZ() == b.cellZ()
                && Double.compare(a.x(), b.x()) == 0
                && Double.compare(a.z(), b.z()) == 0
                && Double.compare(a.c(), b.c()) == 0
                && Double.compare(a.probability(), b.probability()) == 0
                && Double.compare(a.draw(), b.draw()) == 0
                && a.landSlot() == b.landSlot()
                && a.oceanSlot() == b.oceanSlot()
                && a.recipe() == b.recipe();
    }

    private static String describe(RegionalMorphWorld.SiteChoice s) {
        return String.format(Locale.ROOT,
                "cell(%d,%d) x=%.9f z=%.9f c=%.9f p=%.9f draw=%.9f landSlot=%d oceanSlot=%d"
                        + " recipe=%d",
                s.cellX(), s.cellZ(), s.x(), s.z(), s.c(), s.probability(), s.draw(),
                s.landSlot(), s.oceanSlot(), s.recipe());
    }

    private static void write(Path out, String name, List<String> lines) throws Exception {
        Files.writeString(out.resolve(name), String.join("\n", lines) + "\n", StandardCharsets.UTF_8);
    }
}
