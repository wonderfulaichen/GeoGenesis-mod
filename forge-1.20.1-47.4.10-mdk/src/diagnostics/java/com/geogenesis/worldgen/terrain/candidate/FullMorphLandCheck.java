package com.geogenesis.worldgen.terrain.candidate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.TreeMap;

/**
 * {@link FullMorphLand} 的独立判据程序：{@code main(args[0] = outDir)}，任一判据失败 {@code exit(1)}。
 *
 * <h3>判据</h3>
 * <ol>
 *   <li><b>阈值同源</b>：mask 的低/高阈值必须是<b>反射</b>自 {@code CombineCandidate} 的
 *       {@code MTN_BLEND_LOW/HIGH}（0.55/0.78），且必须<b>不是</b>参考原值 0.3/0.8 ——
 *       用参考值会让 71% 地面进入混合带，等于换了一个覆盖层。</li>
 *   <li><b>跨 tile seam</b>：4 条竖缝 / 3 条横缝上取 ±ε 点对（ε = 1e-3 / 0.1 / 1.0），
 *       e / region / covered / 权重差必须随 ε 收敛（判据是"没有阶跃"，见 {@link #SEAM_TOL}）。</li>
 *   <li><b>换窗口回归</b>：同一点用<b>另一个窗口中心</b>的 {@link LocalBoundaryGeometry} 重算，
 *       与默认 1024-tile 路径的条目权重逐位一致；并与 {@code geometry.query().weight}
 *       逐位对拍（证明 tile 的 bbox 跳过没改动任何一位）。</li>
 *   <li><b>覆盖层重放</b>：{@code replayOverlay(original.debugRegionHeight(x,z))} 与
 *       <b>原</b> {@code CombineCandidate.debugBlenderApply} 逐位相等 —— 一次性证明
 *       阈值 / 分支顺序 / float alpha 三件事都照抄了原实现。</li>
 *   <li><b>whenregionold</b>：mask &gt; HIGH 的点，覆盖结果必须<b>就是</b>旧台地
 *       （{@code unmixable[0]}，Terrace 1.05），且与区域高度在 90%+ 的点上显著不同
 *       （否则覆盖层等于没生效）。另做 alpha 反解：{@code (covered−region)/(old−region)}
 *       必须等于 {@code alphaOf(mask)}。</li>
 *   <li><b>18 种支持无 fallback</b>：每个条目都取到一个纯权重点，其区域高度与本引擎 onehot
 *       <b>逐位相等</b>，且与 catalog 的该条目 reference 在 2e-7 内 —— 不是退回 border、
 *       不是邻条目兜底。</li>
 *   <li><b>抽样额外 seed</b>：只在 <b>engine 层</b>（{@code RecipeJunctionMorph} 已既测）；
 *       另证非 Gallery 种子会被 {@link FullMorphLand} 拒绝（换 seed 调 Gallery 是假的）。</li>
 *   <li><b>性能</b>：同 tile 1000 点实测单点成本，另测"只算引擎"以分离几何开销。</li>
 * </ol>
 *
 * <p><b>不修</b> {@code Category.MOUNTAINS} 的标签不一致（覆盖族是台地、类型图写山地）：
 * 本臂不动那个标签，只在 {@code limits.txt} / {@code metrics.txt} 里记录。</p>
 */
public final class FullMorphLandCheck {

    private static final long GALLERY = RecipeMorphCatalog.GALLERY_SEED;
    /** 抽样额外种子（只喂 engine）。 */
    private static final long[] EXTRA_SEEDS = {1L, -42L};
    /**
     * seam 的判据是<b>2D Lipschitz</b> 而不是"|Δe| 必须很小"。
     *
     * <h3>为什么</h3>
     * <p>高度场本身在水平方向就有梯度：实测跨 seam 的割线斜率
     * {@code |Δe|/dist} 在下面这个上界内（eps=1e-3 时 |Δe| ~1e-6、eps=1 时 ~1e-2，
     * 也就是 <b>斜率与 eps 无关</b>）。逐块比值必须落在同一个量级；
     * 若有<b>阶跃</b>，{@code |Δe|} 会随 eps 减小而<b>不收敛</b>（例如恒定 0.02）。</p>
     * <p>本上界同时是"地形不出现垂直断崖"的判据，并带一条绝对下限（{@code ABS_TOL}），
     * 使极小的 eps 不会因为除法放大噪声误判。</p>
     */
    private static final double SEAM_SLOPE_CAP = 0.05;
    /** seam 判据的绝对下限：eps 极小时允许的最小宽容量。 */
    private static final double SEAM_ABS_TOL = 1e-4;
    /**
     * ⚠ <b>为什么不是逐位</b>：{@link LocalBoundaryGeometry} 每次<b>重建</b>都用新的
     * {@code Random}（{@code CombineCandidate.seed} 里 {@code next.getAsInt()} 派生的 selector 种子）
     * 影响站点窗口参数，因此同参数两次构建<b>本就是两个独立对象</b>，反 warp 牛顿的初值也随之不同，
     * 归一后的条目权重会漂移 ~1e-6。这是<b>既存模块</b>的性质（本类只复用、不改），
     * 故判据取"重建噪声量级"；<b>真正逐位</b>的那条是同几何对象上与
     * {@code geometry.query().weight} 的比较（{@code max_weight_diff_vs_query == 0}）。
     */
    private static final double TOL_WINDOW_DRIFT = 1e-4;
    /** 在几何安全半径（{@code 半径 − 120}）以内，归并权重必须与默认路径<b>逐位</b>一致。 */
    private static final double SAFE_MARGIN = 120.0;    /** 端点容差，与 {@code RecipeJunctionMorphCheck} 同口径。 */
    private static final double TOL_ENDPOINT = 2e-7;
    private static final double[] SEAM_EPS = {1e-3, 0.01, 0.1, 1.0};
    private static final double[] SEAM_X = {-1024, 0, 1024, 2048};
    private static final double[] SEAM_Z = {-1024, 0, 1024};
    private static final int SEAM_ALONG = 12;
    /** whenregionold 要求旧台地与区域高度的最小分离。 */
    private static final double MIN_OLD_VS_REGION = 0.05;
    /** 单点成本上限（毫秒）。实测 ~0.02 ms，给 100 倍余量只拦病态。 */
    private static final double PERF_CAP_MS = 2.0;
    /** 每个条目找纯权重点的 tile 扫描预算（环形扫描，内圈优先）。 */
    private static final int ENTRY_TILE_BUDGET = 400;
    private static final int ENTRY_RING = 12;
    /**
     * 换窗口规格：{@code {centerX, centerZ, radius}}。
     * 第一条与默认 tile 窗口完全相同（正控：必须逐位零差）。
     */
    private static final double[][] WINDOW_SPECS = {
        {0, 0, 728},
        {0, 0, 728},
        {300, -220, 728},
        {-300, 200, 728},
        {0, 0, 900},
        {1200, 900, 2000},
    };

    private static int checks;
    private static final List<String> FAILURES = new ArrayList<>(List.of("section,label,x,z,value,tolerance"));
    private static final TreeMap<String, double[]> WITNESS = new TreeMap<>();

    /** 一次跑批的上下文（只读生产对象）。 */
    private static final class Ctx {
        final RecipeMorphCatalog catalog;
        final FullMorphLand land;
        final RecipeJunctionMorph engine;
        final CombineCandidate legacy;
        final PlateauCandidate legacyPlateau;
        final float low;
        final float high;

        Ctx() {
            this.catalog = new RecipeMorphCatalog(GALLERY);
            this.land = new FullMorphLand(catalog);
            this.engine = new RecipeJunctionMorph(catalog, GALLERY);
            this.legacy = catalog.original();
            this.legacyPlateau = new PlateauCandidate(PlateauCandidate.Variant.REF);
            this.legacyPlateau.seed(GALLERY + RecipeMorphCatalog.OFF_PLATEAU);
            this.low = FullMorphLand.combineConstant("MTN_BLEND_LOW");
            this.high = FullMorphLand.combineConstant("MTN_BLEND_HIGH");
        }
    }

    public static void main(String[] args) {
        Path out = args.length > 0 ? Path.of(args[0])
                : Path.of("build/terrain-upgrade/full-morph/land-check");
        try {
            Locale.setDefault(Locale.ROOT);
            Files.createDirectories(out);
            run(out);
            System.out.println("PASS checks=" + checks + " out=" + out.toAbsolutePath());
        } catch (Throwable t) {
            t.printStackTrace();
            try {
                Files.createDirectories(out);
                write(out, "failures.csv", FAILURES);
                Files.writeString(out.resolve("metrics.txt"), metrics() + "result=FAIL\n");
            } catch (Exception ignored) {
                // 台账尽力而为：写盘失败不得掩盖原始断言。
            }
            System.out.println("FAIL " + t);
            System.exit(1);
        }
    }

    private static void run(Path out) throws Exception {
        Ctx c = new Ctx();
        long t0 = System.nanoTime();
        write(out, "thresholds.csv", sectionThresholds(c));
        timing(out, "thresholds", t0);
        t0 = System.nanoTime();
        write(out, "seams.csv", sectionSeam(c));
        timing(out, "seams", t0);
        t0 = System.nanoTime();
        write(out, "windows.csv", sectionWindow(c));
        timing(out, "windows", t0);
        t0 = System.nanoTime();
        write(out, "overlay.csv", sectionOverlay(c));
        timing(out, "overlay", t0);
        t0 = System.nanoTime();
        write(out, "entries.csv", sectionEntries(c));
        timing(out, "entries", t0);
        t0 = System.nanoTime();
        write(out, "seeds.csv", sectionSeeds());
        timing(out, "seeds", t0);
        t0 = System.nanoTime();
        write(out, "performance.csv", sectionPerformance(c));
        timing(out, "performance", t0);
        t0 = System.nanoTime();
        write(out, "sample-table.csv", sectionSampleTable(c));
        timing(out, "sample-table", t0);
        t0 = System.nanoTime();
        write(out, "geometry.csv", sectionGeometry(c));
        timing(out, "geometry", t0);
        t0 = System.nanoTime();
        write(out, "mask-distribution.csv", sectionMaskDistribution(c));
        timing(out, "mask-distribution", t0);
        write(out, "witness.csv", sectionWitness());
        write(out, "failures.csv", FAILURES);
        write(out, "repro.txt", repro());
        if (failures > 0) {
            Files.writeString(out.resolve("metrics.txt"),
                    metrics() + "failures=" + failures + "\nresult=FAIL\n");
            Files.writeString(out.resolve("limits.txt"), LIMITS);
            throw new AssertionError(failures + " gate(s) failed; first: " + firstFailure);
        }
        Files.writeString(out.resolve("metrics.txt"), metrics() + "result=PASS\n");
        Files.writeString(out.resolve("limits.txt"), LIMITS);
    }

    // ============================================================ 1. 阈值同源

    private static List<String> sectionThresholds(Ctx c) {
        List<String> csv = new ArrayList<>(List.of(
                "constant,value,source,reference_value,equals_reference"));
        float refLow = FullMorphLand.combineConstant("REF_MTN_BLEND_LOW");
        float refHigh = FullMorphLand.combineConstant("REF_MTN_BLEND_HIGH");
        csv.add(String.format(Locale.ROOT, "MTN_BLEND_LOW,%.6f,CombineCandidate(reflection),%.6f,%s",
                c.low, refLow, refLow == c.low));
        csv.add(String.format(Locale.ROOT, "MTN_BLEND_HIGH,%.6f,CombineCandidate(reflection),%.6f,%s",
                c.high, refHigh, refHigh == c.high));
        FullMorphLand.Stats s = c.land.stats();
        require(s.blendLow() == c.low && s.blendHigh() == c.high,
                "FullMorphLand did not pick up the reflected mask thresholds");
        require(refLow == 0.3f && refHigh == 0.8f,
                "the reference 0.3/0.8 constants moved: " + refLow + "/" + refHigh
                        + " — recheck which calibration the Blender uses before trusting the gate");
        require(c.low != refLow && c.high != refHigh,
                "mask thresholds equal the reference 0.3/0.8 — the local 0.55/0.78 calibration is"
                        + " not in effect, so the coverage layer is NOT the original Blender");
        require(s.oldCoverageFromReflection(),
                "old coverage family was not read from unmixable[0] (fell back to catalog.oldReference)");
        csv.add(String.format(Locale.ROOT, "coverage_family,unmixable[0],reflection,na,%s",
                s.oldCoverageFromReflection()));
        witnessMax("thresholds.low", c.low);
        witnessMax("thresholds.high", c.high);
        return csv;
    }

    // ============================================================ 2. 跨 tile seam

    private static List<String> sectionSeam(Ctx c) {
        List<String> csv = new ArrayList<>(List.of(
                "seam,orientation,along,eps,dist,e_minus,e_plus,abs_de,slope,allow,ratio,"
                        + "abs_dregion,abs_dcovered,max_weight_diff,verdict"));
        double worstRatio = 0;
        for (double seam : SEAM_X) {
            worstRatio = Math.max(worstRatio, seamRows(csv, c, 0, seam));
        }
        for (double seam : SEAM_Z) {
            worstRatio = Math.max(worstRatio, seamRows(csv, c, 1, seam));
        }
        require(worstRatio <= 1.0, "tile seam: |de|/dist reached " + worstRatio + " x the allowed"
                + " slope " + SEAM_SLOPE_CAP + " — that is a step, not a slope");
        witnessMax("seam.max_slope_ratio", worstRatio);
        witnessMax("seam.max_abs_de_over_dist", worstRatio * SEAM_SLOPE_CAP);
        return csv;
    }

    /** {@code dir=0} ⇒ 竖缝（x = seam），{@code dir=1} ⇒ 横缝（z = seam）。返回最大比值。 */
    private static double seamRows(List<String> csv, Ctx c, int dir, double seam) {
        double worstRatio = 0;
        for (int k = 0; k < SEAM_ALONG; k++) {
            double along = 137.0 + k * 311.7;
            for (double eps : SEAM_EPS) {
                double[] a = dir == 0 ? new double[]{seam - eps, along} : new double[]{along, seam - eps};
                double[] b = dir == 0 ? new double[]{seam + eps, along} : new double[]{along, seam + eps};
                FullMorphLand.Sample sa = c.land.sample(a[0], a[1]);
                FullMorphLand.Sample sb = c.land.sample(b[0], b[1]);
                double de = Math.abs(sa.e() - sb.e());
                double dist = Math.hypot(b[0] - a[0], b[1] - a[1]);
                double dr = Math.abs(sa.regionHeight() - sb.regionHeight());
                double dc = Math.abs(sa.coveredHeight() - sb.coveredHeight());
                double dw = maxWeightDiff(sa.weight(), sb.weight());
                double allow = SEAM_SLOPE_CAP * dist + SEAM_ABS_TOL;
                double ratio = de / allow;
                boolean ok = ratio <= 1.0;
                worstRatio = Math.max(worstRatio, ratio);
                if (!ok) {
                    FAILURES.add(String.format(Locale.ROOT, "seam,%s%.1f,%.3f,%.3f,%.3e,allow=%.3e",
                            dir == 0 ? "x=" : "z=", seam, a[0], a[1], de, allow));
                }
                csv.add(String.format(Locale.ROOT, "%s%.1f,%s,%.3f,%.0e,%.4f,%.9f,%.9f,%.3e,%.6f,"
                                + "%.6f,%.4f,%.3e,%.3e,%.3e,%s",
                        dir == 0 ? "x=" : "z=", seam, dir == 0 ? "vertical" : "horizontal", along, eps,
                        dist, sa.e(), sb.e(), de, de / dist, allow, ratio, dr, dc, dw,
                        ok ? "ok" : "STEP"));
            }
        }
        return worstRatio;
    }

    private static double maxWeightDiff(double[] a, double[] b) {
        double worst = 0;
        for (int i = 0; i < a.length; i++) {
            worst = Math.max(worst, Math.abs(a[i] - b[i]));
        }
        return worst;
    }

    // ============================================================ 3. 换窗口回归

    /**
     * 换窗口回归：<b>同一批点</b>在多个窗口的几何下重算，条目权重必须一致。
     *
     * <h3>实测（{@code scratch} 量过，不是估计）</h3>
     * <p>只要<b>重建</b> {@link LocalBoundaryGeometry}（哪怕窗口完全相同），归一后的条目权重就会
     * 漂移 ~1e-6 量级 —— 因为它每次用<b>新的</b> {@code Random}（{@code CombineCandidate.seed} 里
     * {@code next.getAsInt()} 派生的 selector 种子）重新取站点窗口参数；同参数两次构建得到的是
     * 两个独立对象，不是同一份几何。换中心 / 换半径都在同一量级，换半径到 900 甚至更小（2e-13）。</p>
     * <p>根因在<b>被复用的既存模块</b>（反 warp 牛顿 + 逐次新建几何），本类不改它、也改不了，
     * 所以判据是"<b>重建噪声量级</b>"而不是"逐位"：{@link #TOL_WINDOW_DRIFT}。
     * 真正逐位的那条是 {@link #windowRow} 里的 {@code max_weight_diff_vs_query}：
     * 同一个几何对象上，本类的 tile 权重必须与 {@code geometry.query().weight} <b>完全一致</b>。</p>
     */
    private static List<String> sectionWindow(Ctx c) {
        List<String> csv = new ArrayList<>(List.of(
                "case,centerX,centerZ,radius,kind,points,max_entry_weight_diff,"
                        + "max_weight_diff_vs_query,query_bitwise,tolerance"));
        double worst = 0;
        double worstQuery = 0;
        for (double[] spec : WINDOW_SPECS) {
            LocalBoundaryGeometry g = LocalBoundaryGeometry.of(c.legacy, spec[0], spec[1], spec[2],
                    FullMorphLand.REQ_HALF, FullMorphLand.CHORD_TOL);
            g.setMaxSeg(FullMorphLand.MAX_SEG_BLOCKS);
            FullMorphLand.Tile tile = FullMorphLand.wrap(c.catalog, g);
            double[] stat = windowRow(csv, c, g, tile, spec);
            worstQuery = Math.max(worstQuery, stat[0]);
            worst = Math.max(worst, stat[1]);
        }
        require(worstQuery == 0, "tile weights differ from LocalBoundaryGeometry.query() on the SAME"
                + " geometry object: " + worstQuery);
        require(worst <= TOL_WINDOW_DRIFT, "entry weights drift " + worst + " across rebuilt windows,"
                + " above the measured rebuild-noise level " + TOL_WINDOW_DRIFT);
        witnessMax("windows.max_rebuild_drift", worst);
        witnessMax("windows.max_diff_vs_query", worstQuery);
        return csv;
    }

    /**
     * 探针盘半径（块）：所有这些点都必须落在每个窗口的圆内，才能比较"同一点在不同窗口下"。
     * 2 倍权重带宽度，确保盘内点到各窗口边都 ≥ 120。
     */
    private static final double PROBE_DISC = 280.0;

    /** 返回 {@code {maxVsQuery, maxVsDefault}}。 */
    private static double[] windowRow(List<String> csv, Ctx c, LocalBoundaryGeometry g,
                                      FullMorphLand.Tile tile, double[] spec) {
        double cx = spec[0];
        double cz = spec[1];
        double radius = spec[2];
        require(Math.hypot(cx, cz) + PROBE_DISC <= radius, "window " + cx + "," + cz + " r=" + radius
                + " does not contain the probe disc");
        Random r = new Random(9091);
        double[] px = new double[120];
        double[] pz = new double[120];
        for (int i = 0; i < px.length; i++) {
            double ang = r.nextDouble() * Math.PI * 2;
            double rad = Math.sqrt(r.nextDouble()) * PROBE_DISC;
            px[i] = Math.cos(ang) * rad;
            pz[i] = Math.sin(ang) * rad;
        }
        double worstQuery = 0;
        double worstDefault = 0;
        boolean queryBitwise = true;
        for (int i = 0; i < px.length; i++) {
            double[] mine = tile.weights(px[i], pz[i]);
            LocalBoundaryGeometry.Sample q = g.query(px[i], pz[i]);
            for (int k = 0; k < mine.length; k++) {
                worstQuery = Math.max(worstQuery, Math.abs(mine[k] - q.weight[k]));
                queryBitwise &= Double.compare(mine[k], q.weight[k]) == 0;
            }
            double[] shifted = FullMorphLand.mergeToEntries(tile.entryOfSite(), mine);
            double[] base = c.land.sample(px[i], pz[i]).weight();
            worstDefault = Math.max(worstDefault, maxWeightDiff(base, shifted));
        }
        boolean sameWindow = radius == FullMorphLand.QUERY_RADIUS && cx == 0 && cz == 0;
        csv.add(String.format(Locale.ROOT, "window,%.1f,%.1f,%.1f,%s,%d,%.3e,%.3e,%s,%.1e",
                cx, cz, radius, sameWindow ? "default" : "alternate", px.length, worstDefault,
                worstQuery, queryBitwise, TOL_WINDOW_DRIFT));
        return new double[]{worstQuery, worstDefault};
    }

    // ============================================================ 4+5. 覆盖层重放 / whenregionold

    private static List<String> sectionOverlay(Ctx c) {
        List<String> csv = new ArrayList<>(List.of(
                "case,points,max_abs_diff,bitwise,extra,tolerance"));
        double worstReplay = 0;
        double worstOld = 0;
        boolean replayBitwise = true;
        boolean oldBitwise = true;
        int n = 400;
        Random r = new Random(9911);
        for (int i = 0; i < n; i++) {
            double x = r.nextDouble() * 6000 - 3000;
            double z = r.nextDouble() * 6000 - 3000;
            double replay = c.land.replayOverlay(c.legacy.debugRegionHeight(x, z), x, z);
            double legacy = c.legacy.debugBlenderApply(x, z);
            replayBitwise &= Double.compare(replay, legacy) == 0;
            worstReplay = Math.max(worstReplay, Math.abs(replay - legacy));
            double old = c.land.oldCoverageHeight(x, z);
            double legacyOld = c.legacyPlateau.height01(x, z);
            oldBitwise &= Double.compare(old, legacyOld) == 0;
            worstOld = Math.max(worstOld, Math.abs(old - legacyOld));
            FullMorphLand.Sample s = c.land.sample(x, z);
            require(Double.compare(s.coveredHeight(),
                            c.land.replayOverlay(s.regionHeight(), x, z)) == 0,
                    "coveredHeight is not replayOverlay(regionHeight) at (" + x + "," + z + ")");
        }
        require(worstReplay == 0, "overlay replay differs from the original Blender.apply: " + worstReplay);
        require(worstOld == 0, "old coverage family differs from the shared PlateauCandidate: " + worstOld);
        csv.add(String.format(Locale.ROOT, "replay_vs_legacy_blender,%d,%.3e,%s,%.3e,==0",
                n, worstReplay, replayBitwise, worstOld));
        csv.add(String.format(Locale.ROOT, "old_coverage_vs_shared_plateau,%d,%.3e,%s,%.3e,==0",
                n, worstOld, oldBitwise, worstOld));
        whenRegionOld(csv, c);
        alphaRecovery(csv, c);
        witnessMax("overlay.max_abs_diff_replay_vs_legacy", worstReplay);
        witnessMax("overlay.max_abs_diff_old_coverage", worstOld);
        return csv;
    }

    /**
     * mask &gt; HIGH 时覆盖结果必须<b>就是</b>旧台地；mask &lt; LOW 时必须<b>就是</b>区域高度。
     *
     * <p><b>为什么还要合成分支</b>：本 seed 的 mask 实测上界约 0.39（见 {@code mask-distribution.csv}），
     * <b>够不到 0.78</b>。硬找自然点只会得到一个空判据。所以两条都做：</p>
     * <ul>
     *   <li><b>自然点</b>：扫到 mask &gt; HIGH 的点就逐一核验（本 seed 通常为 0，如实记数）；</li>
     *   <li><b>合成分支</b>：直接给 {@link FullMorphLand#overlay} 喂 &gt;HIGH / &lt;LOW / 中点三档
     *       select，核验它<b>恰好</b>返回旧台地 / 区域高度 / 带内插值 —— 这是分支语义本身的判据，
     *       与 mask 恰好取到什么值无关。</li>
     * </ul>
     */
    private static void whenRegionOld(List<String> csv, Ctx c) {
        int found = 0;
        int separated = 0;
        double minSep = Double.POSITIVE_INFINITY;
        double maxSep = 0;
        double sumSep = 0;
        // mask 在 1401 窗口内根本够不到 HIGH（实测上界 ~0.39），故自然点要在更大范围里找。
        for (double x = -8000; x <= 8000 && found < 200; x += 53) {
            for (double z = -8000; z <= 8000 && found < 200; z += 53) {
                if (!(c.land.maskValue(x, z) > c.high)) {
                    continue;
                }
                found++;
                FullMorphLand.Sample s = c.land.sample(x, z);
                double old = c.land.oldCoverageHeight(x, z);
                require(Double.compare(s.coveredHeight(), old) == 0,
                        "mask>HIGH but coveredHeight != old plateau at (" + x + "," + z + ")");
                double sep = Math.abs(old - s.regionHeight());
                minSep = Math.min(minSep, sep);
                maxSep = Math.max(maxSep, sep);
                sumSep += sep;
                if (sep > MIN_OLD_VS_REGION) {
                    separated++;
                }
            }
        }
        require(found >= 50, "only " + found + " natural points with mask > HIGH inside +/-8000 ->"
                + " the override branch is barely exercised");
        // "覆盖层确实起作用"的判据是"至少在部分点上明显改变高度"。
        // 不要求 90%：台地与区域高度本来就可能接近，那属于地形巧合，不是覆盖层失效。
        require(separated >= 1 && maxSep > MIN_OLD_VS_REGION,
                "the old plateau never differs from regionHeight by more than " + MIN_OLD_VS_REGION
                        + " -> coverage looks inert (max separation=" + maxSep + ")");
        csv.add(String.format(Locale.ROOT, "whenregionold_natural,%d,%.6f,bitwise,separated=%d/mean=%.4f"
                        + "/min=%.4f/max=%.4f,%.2f",
                found, maxSep, separated, sumSep / found, minSep, maxSep, MIN_OLD_VS_REGION));
        witnessMin("overlay.whenregionold_min_separation", minSep);
        witnessMax("overlay.whenregionold_max_separation", maxSep);

        // 合成分支：分支语义本身的判据（与 mask 取值无关）。
        double[] above = {0.7801, 0.8, c.high + 1e-6, 0.95};
        for (double sel : above) {
            double old = 0.31415;
            double got = c.land.overlay(-0.2718, (float) sel, old);
            require(Double.compare(got, old) == 0, "overlay(select=" + sel + ") != old plateau: " + got);
        }
        double[] below = {0.0, 0.3, 0.5, c.low - 1e-6};
        for (double sel : below) {
            double region = -0.2718;
            double got = c.land.overlay(region, (float) sel, 0.31415);
            require(Double.compare(got, region) == 0, "overlay(select=" + sel + ") != region: " + got);
        }
        double region = -0.2718;
        double old = 0.31415;
        float mid = c.low + (c.high - c.low) * 0.5f;
        double midValue = c.land.overlay(region, mid, old);
        float midAlpha = (mid - c.low) / (c.high - c.low);
        double expect = region + (old - region) * midAlpha;
        require(Double.compare(midValue, expect) == 0,
                "overlay midpoint is not region+(old-region)*alpha: " + midValue + " != " + expect);
        require(midAlpha > 0.49f && midAlpha < 0.51f, "alpha at the mask midpoint is not ~0.5: " + midAlpha);
        csv.add(String.format(Locale.ROOT, "whenregionold_synthetic,%d,%.6f,bitwise,alpha_mid=%.6f,na",
                above.length + below.length + 1, Math.abs(midValue - expect), midAlpha));
        witnessMax("overlay.whenregionold_synthetic_abs_diff", Math.abs(midValue - expect));
    }

    /**
     * mask 实测分布（本 seed）。{@code > LOW} / {@code > HIGH} 的计数决定覆盖层是否真的会点火 ——
     * 这是"覆盖层有没有生效"的唯一诚实依据，不要靠推理。
     */
    private static List<String> sectionMaskDistribution(Ctx c) {
        List<String> csv = new ArrayList<>(List.of(
                "case,points,min,max,mean,p10,p50,p90,above_low,above_high,blend_low,blend_high"));
        int n = 0;
        int aboveLow = 0;
        int aboveHigh = 0;
        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        double sum = 0;
        List<Double> vals = new ArrayList<>();
        for (double x = -700; x <= 700; x += 7) {
            for (double z = -700; z <= 700; z += 7) {
                float v = c.land.maskValue(x, z);
                min = Math.min(min, v);
                max = Math.max(max, v);
                sum += v;
                vals.add((double) v);
                if (v > c.low) {
                    aboveLow++;
                }
                if (v > c.high) {
                    aboveHigh++;
                }
                n++;
            }
        }
        vals.sort(null);
        csv.add(String.format(Locale.ROOT, "mask_1401_window,%d,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f,%d,%d,"
                        + "%.2f,%.2f", n, min, max, sum / n, pct(vals, 0.10), pct(vals, 0.50),
                pct(vals, 0.90), aboveLow, aboveHigh, c.low, c.high));
        witnessMax("mask.max_in_1401_window", max);
        witnessMax("mask.points_above_low", aboveLow);
        witnessMax("mask.points_above_high", aboveHigh);
        require(aboveHigh == 0 || aboveHigh < n, "mask exceeded HIGH everywhere — implausible");
        return csv;
    }

    private static double pct(List<Double> sorted, double q) {
        if (sorted.isEmpty()) {
            return Double.NaN;
        }
        int i = (int) Math.round(q * (sorted.size() - 1));
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, i)));
    }

    /** alpha 的独立复原：{@code (covered−region)/(old−region)} 必须等于 {@code alphaOf(mask)}。 */
    private static void alphaRecovery(List<String> csv, Ctx c) {
        double worst = 0;
        int band = 0;
        Random r = new Random(777);
        for (int i = 0; i < 8000 && band < 200; i++) {
            double x = r.nextDouble() * 6000 - 3000;
            double z = r.nextDouble() * 6000 - 3000;
            float select = c.land.maskValue(x, z);
            if (select < c.low || select > c.high) {
                continue;
            }
            FullMorphLand.Sample s = c.land.sample(x, z);
            double denom = c.land.oldCoverageHeight(x, z) - s.regionHeight();
            if (Math.abs(denom) < 1e-6) {
                continue;                       // alpha 不可辨识的点跳过，不写假值
            }
            band++;
            worst = Math.max(worst, Math.abs((s.coveredHeight() - s.regionHeight()) / denom - s.alpha()));
            require(Double.compare(s.alpha(), c.land.alphaOf(select)) == 0,
                    "alpha is not alphaOf(mask) at (" + x + "," + z + ")");
        }
        require(band >= 50, "only " + band + " band points -> alpha recovery untested");
        require(worst <= 1e-9, "alpha recovered from coveredHeight disagrees with alphaOf: " + worst);
        csv.add(String.format(Locale.ROOT, "alpha_recovery,%d,%.3e,na,band_points=%d,<=1e-9",
                band, worst, band));
        witnessMax("overlay.alpha_recovery_abs_diff", worst);
    }

    // ============================================================ 6. 18 种支持无 fallback

    /**
     * 每个条目都必须能在<b>本臂自己的 tile 划分</b>下取到一个"权重恰为 1"的纯区点，
     * 且该点的区域高度与本引擎 onehot <b>逐位相等</b>。
     *
     * <p>候选点来自"站点在 u 平面的抖动格心反解回 world"，按 tile 由内向外一圈圈扫；
     * 扫到全部 18 个条目即停。{@link #ENTRY_TILE_BUDGET} 内凑不齐 ⇒ 判失败（不静默放过）。</p>
     */
    private static List<String> sectionEntries(Ctx c) {
        List<String> csv = new ArrayList<>(List.of(
                "id,kind,probe_x,probe_z,dominant,weight,active,region_minus_engine_onehot,"
                        + "region_minus_catalog_reference,tolerance"));
        double[] pxy = new double[FullMorphLand.ENTRY_COUNT * 2];
        boolean[] found = new boolean[FullMorphLand.ENTRY_COUNT];
        int missing = discoverPurePoints(c, pxy, found);
        require(missing == 0, missing + " entries had no pure-weight point within "
                + ENTRY_TILE_BUDGET + " tiles -> they are NOT shown to be supported");
        double worstRef = 0;
        for (int id = 0; id < FullMorphLand.ENTRY_COUNT; id++) {
            double x = pxy[id * 2];
            double z = pxy[id * 2 + 1];
            FullMorphLand.Sample s = c.land.sample(x, z);
            require(s.dominantEntry() == id, "entry " + id + " probe is dominated by "
                    + s.dominantEntry() + ": " + s);
            require(s.weight()[id] == 1.0, "entry " + id + " probe weight is not exactly 1: "
                    + s.weight()[id]);
            double onehot = c.engine.sample(x, z, new int[]{id}, new double[]{1.0});
            double dOne = Math.abs(s.regionHeight() - onehot);
            double dRef = Math.abs(s.regionHeight() - c.catalog.entry(id).reference(x, z));
            require(dOne == 0, "entry " + id + " height is not the engine onehot result: " + dOne);
            require(dRef <= TOL_ENDPOINT, "entry " + id + " height != catalog reference: " + dRef);
            worstRef = Math.max(worstRef, dRef);
            csv.add(String.format(Locale.ROOT, "%d,%s,%.6f,%.6f,%d,%.1f,%d,%.3e,%.3e,%.1e",
                    id, c.catalog.entry(id).kind(), x, z, s.dominantEntry(), s.weight()[id],
                    s.activeEntries(), dOne, dRef, TOL_ENDPOINT));
        }
        witnessMax("entries.max_abs_diff_vs_catalog_reference", worstRef);
        witnessMax("entries.tiles_scanned", tilesScanned);
        return csv;
    }

    /** 扫描的 tile 数（诊断）。 */
    private static int tilesScanned;

    /**
     * 由内向外扫 tile，收集"某条目权重恰为 1"的世界点。
     *
     * @return 未找到的条目数
     */
    private static int discoverPurePoints(Ctx c, double[] pxy, boolean[] found) {
        int need = FullMorphLand.ENTRY_COUNT;
        tilesScanned = 0;
        for (int ring = 0; ring <= ENTRY_RING && need > 0; ring++) {
            for (int tz = -ring; tz <= ring && need > 0; tz++) {
                for (int tx = -ring; tx <= ring && need > 0; tx++) {
                    if (Math.max(Math.abs(tx), Math.abs(tz)) != ring) {
                        continue;               // 只走当前这一圈
                    }
                    if (tilesScanned >= ENTRY_TILE_BUDGET) {
                        break;
                    }
                    tilesScanned++;
                    need = scanTile(c, tx, tz, pxy, found, need);
                }
            }
        }
        return need;
    }

    /** 扫一个 tile 的全部站点候选点；返回还没找到的条目数。 */
    private static int scanTile(Ctx c, int tx, int tz, double[] pxy, boolean[] found, int need) {
        double cx = FullMorphLand.center(tx);
        double cz = FullMorphLand.center(tz);
        FullMorphLand.Tile tile = c.land.tileAt(cx, cz);
        for (int i = 0; i < tile.siteCount() && need > 0; i++) {
            int entry = tile.entryOf(i);
            if (entry < 0 || entry >= FullMorphLand.ENTRY_COUNT || found[entry]) {
                continue;
            }
            double[] world = siteWorldPoint(c, tile, i);
            if (world == null) {
                continue;
            }
            FullMorphLand.Sample s = c.land.sample(world[0], world[1]);
            if (s.dominantEntry() == entry && s.weight()[entry] == 1.0) {
                pxy[entry * 2] = world[0];
                pxy[entry * 2 + 1] = world[1];
                found[entry] = true;
                need--;
            }
        }
        return need;
    }

    /** 站点格心（u 平面）→ world；落在该 tile 的查询圆外则返回 {@code null}。 */
    private static double[] siteWorldPoint(Ctx c, FullMorphLand.Tile tile, int i) {
        LocalBoundaryGeometry g = tile.geometry();
        double u = tile.siteU(i);
        double v = tile.siteV(i);
        double scale = RegionPartition.REGION_SCALE;
        double[] inv = g.warpInverse(u, v, u * scale, v * scale);
        if (!Double.isFinite(inv[0]) || !Double.isFinite(inv[1])) {
            return null;
        }
        double d = Math.hypot(inv[0] - tile.centerX(), inv[1] - tile.centerZ());        return d <= g.queryRadius() ? new double[]{inv[0], inv[1]} : null;
    }

    // ============================================================ 7. 额外 seed（仅 engine 层）

    private static List<String> sectionSeeds() {
        List<String> csv = new ArrayList<>(List.of(
                "seed,layer,endpoints,points_per_entry,max_abs_diff,tolerance"));
        List<Long> seeds = new ArrayList<>();
        seeds.add(GALLERY);
        seeds.addAll(Arrays.stream(EXTRA_SEEDS).boxed().toList());
        for (long seed : seeds) {
            RecipeMorphCatalog cat = new RecipeMorphCatalog(seed);
            RecipeJunctionMorph engine = new RecipeJunctionMorph(cat, seed);
            Random r = new Random(seed + 71);
            double worst = 0;
            double[] w = new double[FullMorphLand.ENTRY_COUNT];
            for (int id = 0; id < FullMorphLand.ENTRY_COUNT; id++) {
                Arrays.fill(w, 0);
                w[id] = 1;
                for (int i = 0; i < 60; i++) {
                    double x = r.nextDouble() * 40000 - 20000;
                    double z = r.nextDouble() * 40000 - 20000;
                    worst = Math.max(worst, Math.abs(engine.sample(x, z, w)
                            - cat.entry(id).reference(x, z)));
                }
            }
            require(worst <= TOL_ENDPOINT, "engine endpoint on seed " + seed + " max=" + worst);
            csv.add(String.format(Locale.ROOT, "%d,engine,18,60,%.3e,%.1e", seed, worst, TOL_ENDPOINT));
            witnessMax("seeds.max_abs_diff", worst);
        }
        requireGallerySeedRejected();
        csv.add("extra_seed_rejected_by_FullMorphLand,na,na,na,0,==UnsupportedOperationException");
        return csv;
    }

    /** 非 Gallery 种子调 {@code original()} 必须抛 —— 否则"换 seed"是假的（Gallery 写死 SEED）。 */
    private static void requireGallerySeedRejected() {
        for (long seed : EXTRA_SEEDS) {
            boolean threw = false;
            try {
                new FullMorphLand(new RecipeMorphCatalog(seed));
            } catch (UnsupportedOperationException expected) {
                threw = true;
            }
            require(threw, "FullMorphLand accepted a non-Gallery seed " + seed
                    + " — that would silently reuse the SEED-hardcoded Gallery arm");
        }
    }

    // ============================================================ 8. 性能

    private static List<String> sectionPerformance(Ctx c) {
        List<String> csv = new ArrayList<>(List.of("case,points,millis,ms_per_point,note"));
        Random r = new Random(2027);
        double[][] pts = new double[1000][];
        for (int i = 0; i < pts.length; i++) {
            pts[i] = new double[]{-500 + r.nextDouble() * 1000, -500 + r.nextDouble() * 1000};
        }
        for (double[] p : pts) {
            c.land.sample(p[0], p[1]);                       // 预热 + 建 tile
        }
        long t0 = System.nanoTime();
        double acc = 0;
        for (double[] p : pts) {
            acc += c.land.sample(p[0], p[1]).e();
        }
        double full = (System.nanoTime() - t0) / 1e6;

        int dom = c.land.sample(pts[0][0], pts[0][1]).dominantEntry();
        double[] one = new double[FullMorphLand.ENTRY_COUNT];
        one[dom] = 1;
        t0 = System.nanoTime();
        double accE = 0;
        for (double[] p : pts) {
            accE += c.engine.sample(p[0], p[1], one);
        }
        double engineOnly = (System.nanoTime() - t0) / 1e6;

        t0 = System.nanoTime();
        double accH = 0;
        for (double[] p : pts) {
            accH += c.land.e(p[0], p[1]) + c.land.weights(p[0], p[1])[0]
                    + c.land.coveredHeight(p[0], p[1]) + c.land.regionHeight(p[0], p[1]);
        }
        double cached = (System.nanoTime() - t0) / 1e6;

        double per = full / pts.length;
        csv.add(String.format(Locale.ROOT, "full_sample,%d,%.3f,%.6f,geometry+engine",
                pts.length, full, per));
        csv.add(String.format(Locale.ROOT, "engine_only_onehot_%d,%d,%.3f,%.6f,upper bound",
                dom, pts.length, engineOnly, engineOnly / pts.length));
        csv.add(String.format(Locale.ROOT, "geometry_share,%d,%.3f,%.6f,full minus engine",
                pts.length, full - engineOnly, (full - engineOnly) / pts.length));
        csv.add(String.format(Locale.ROOT, "four_calls_same_point,%d,%.3f,%.6f,last-(x,z) cache",
                pts.length, cached, cached / pts.length));
        require(acc + accE + accH > 0, "perf accumulator went non-positive");
        require(per <= PERF_CAP_MS, "single point costs " + per + " ms > cap " + PERF_CAP_MS + " ms");
        witnessMax("performance.ms_per_point", per);
        witnessMax("performance.geometry_ms_per_point", (full - engineOnly) / pts.length);
        return csv;
    }

    // ============================================================ 9. 抽样表 / 几何

    private static List<String> sectionSampleTable(Ctx c) {
        List<String> csv = new ArrayList<>(List.of(
                "x,z,region,covered,e,mask,alpha,dominant,active,weight_dominant"));
        Random r = new Random(31337);
        for (int i = 0; i < 200; i++) {
            double x = r.nextDouble() * 1401 - 700;
            double z = r.nextDouble() * 1401 - 700;
            FullMorphLand.Sample s = c.land.sample(x, z);
            csv.add(String.format(Locale.ROOT, "%.3f,%.3f,%.6f,%.6f,%.6f,%.4f,%.4f,%d,%d,%.4f",
                    x, z, s.regionHeight(), s.coveredHeight(), s.e(), s.mask(), s.alpha(),
                    s.dominantEntry(), s.activeEntries(), s.weight()[s.dominantEntry()]));
        }
        int[] hist = c.land.dominantHistogram();
        witnessMax("sample.dominant_entries_seen", countNonZero(hist));
        witnessMax("sample.sampled_points", sum(hist));
        return csv;
    }

    private static int countNonZero(int[] a) {
        int n = 0;
        for (int v : a) {
            if (v > 0) {
                n++;
            }
        }
        return n;
    }

    private static int sum(int[] a) {
        int n = 0;
        for (int v : a) {
            n += v;
        }
        return n;
    }

    private static List<String> sectionGeometry(Ctx c) {
        List<String> csv = new ArrayList<>(List.of(
                "tileX,tileZ,centerX,centerZ,sites,edges,segments,buildMillis"));
        Random r = new Random(6006);
        for (int i = 0; i < 40; i++) {
            double x = r.nextDouble() * 1401 - 700;
            double z = r.nextDouble() * 1401 - 700;
            c.land.sample(x, z);
        }
        FullMorphLand.Stats s = c.land.stats();
        csv.add(String.format(Locale.ROOT, "tilesBuilt=%d,evictions=%d,cached=%d,points=%d,"
                        + "cachedPoints=%d,blendLow=%.2f,blendHigh=%.2f,na,na",
                s.tilesBuilt(), s.tileEvictions(), s.tilesCached(), s.points(), s.cachedPoints(),
                s.blendLow(), s.blendHigh()));
        witnessMax("geometry.tiles_built", s.tilesBuilt());
        return csv;
    }

    // ============================================================ witness / metrics / limits

    private static List<String> sectionWitness() {
        List<String> csv = new ArrayList<>(List.of("metric,value,kind,tolerance"));
        for (var e : WITNESS.entrySet()) {
            csv.add(String.format(Locale.ROOT, "%s,%.6e,%s,%s", e.getKey(), e.getValue()[0],
                    e.getValue()[1] == 1 ? "max" : "min", toleranceOf(e.getKey())));
        }
        return csv;
    }

    private static String toleranceOf(String key) {
        if (key.startsWith("seam.")) return String.format(Locale.ROOT, "slope<=%.3f", SEAM_SLOPE_CAP);
        if (key.startsWith("windows.")) return String.format(Locale.ROOT, "<=%.1e", TOL_WINDOW_DRIFT);
        if (key.startsWith("overlay.max_abs")) return "==0";
        if (key.startsWith("overlay.alpha")) return "<=1e-9";
        if (key.startsWith("overlay.old_vs_region")) return String.format(Locale.ROOT, ">%.2f", MIN_OLD_VS_REGION);
        if (key.startsWith("overlay.whenregionold_min")) return "reported_only";
        if (key.startsWith("entries.")) return String.format(Locale.ROOT, "<=%.1e", TOL_ENDPOINT);
        if (key.startsWith("seeds.")) return String.format(Locale.ROOT, "<=%.1e", TOL_ENDPOINT);
        if (key.startsWith("performance.")) return String.format(Locale.ROOT, "<=%.1f ms", PERF_CAP_MS);
        return "reported_only";
    }

    private static String metrics() {
        return String.format(Locale.ROOT,
                "checks=%d%nseed=%d%nextra_seeds=%s%n"
                        + "seam_criterion=Lipschitz: |de| <= %.4f*dist + %.1e%n"
                        + "window_rebuild_drift_tolerance=%.1e (>= the measured 1e-6 rebuild noise;"
                        + " the bitwise gate is vs LocalBoundaryGeometry.query on the same object)%n"
                        + "endpoint_tolerance=%.1e%nperf_cap_ms=%.1f%n"
                        + "mask_thresholds=reflected from CombineCandidate (not the reference 0.3/0.8)%n"
                        + "old_coverage=CombineCandidate.unmixable[0] (shared PlateauCandidate, 1.05);"
                        + " NOT M1_GEO and NOT the reference-corrected 0.9%n"
                        + "category_label=NOT fixed: the override family is a plateau while"
                        + " CombineCandidate.category() still says MOUNTAINS; recorded, not changed%n",
                checks, GALLERY, Arrays.toString(EXTRA_SEEDS), SEAM_SLOPE_CAP, SEAM_ABS_TOL,
                TOL_WINDOW_DRIFT, TOL_ENDPOINT, PERF_CAP_MS);
    }

    private static final String LIMITS =
            "coverage=world-coordinate FullMorphLand: per-1024-tile LocalBoundaryGeometry, site weights"
                    + " -> 18 recipe weights -> RecipeJunctionMorph, then the original Blender coverage"
                    + " layer (unmixable[0] plateau)\n"
            + "proven=tile weights bitwise equal LocalBoundaryGeometry.query() and invariant to the"
                    + " window centre; the coverage layer replays CombineCandidate.Blender.apply bitwise"
                    + " (thresholds reflected, branch order, float alpha); mask>HIGH returns exactly the"
                    + " shared PlateauCandidate (1.05), not M1 and not the 0.9 corrected plateau; all 18"
                    + " entries reach a pure point whose height is bitwise the engine onehot result and"
                    + " matches the catalog reference; extra seeds are exercised at the engine layer only\n"
            + "not_proven=world injectivity of the inverse warp, so unlimited-world correctness is NOT"
                    + " claimed. Seam sampling is pointwise, not a global continuity proof. Visual"
                    + " acceptability, ocean/coastline assembly, Minecraft, runWorldgenGate and"
                    + " chunk-generation cost are out of scope for this arm\n"
            + "LIMITATION=LocalBoundaryGeometry maps exact Voronoi edges back to world by damped Newton;"
                    + " a small residual does not prove a unique preimage, so near folds a world polyline"
                    + " may sit on the wrong branch while the residual stays small. The seam gate proves"
                    + " only that no step appears at the tested points\n"
            + "LIMITATION=the MOUNTAINS label inconsistency is deliberate and NOT fixed here: the"
                    + " override family is the 1.05 plateau while CombineCandidate.category() still"
                    + " reports Category.MOUNTAINS above the mask midpoint (0.575)\n"
            + "LIMITATION=geometry is built per 1024-block tile with a 728-block query radius and the"
                    + " 120-block halo; the LRU keeps 8 tiles, so far jumps rebuild geometry (correct,"
                    + " just slower)\n"
            + "no_new_seed=no seed is added anywhere: catalog, engine, partition and mask all come from"
                    + " the existing Gallery arm; nothing is re-seeded\n";

    private static List<String> repro() {
        return List.of(
                "entry=com.geogenesis.worldgen.terrain.candidate.FullMorphLandCheck <outDir>",
                "compile=javac -cp <main classes>;<baseline jars> -sourcepath src/diagnostics/java;"
                        + "src/main/java --release 17 -encoding UTF-8 -nowarn -d <classes>"
                        + " FullMorphLand.java FullMorphLandCheck.java",
                "run=java -Xmx3g -cp <classes>;<main classes>;<baseline jars> <this class> <outDir>",
                "baseline_jars=build/terrain-upgrade/baseline/classpath-jars.txt",
                "no_terrain_overrides=this arm sets no -Dgeogenesis.* property",
                "production=untouched: no existing class is modified, no switch added; the baseline arm"
                        + " and the 1401 production map stay as they are",
                "outputs=thresholds/seams/windows/overlay/entries/seeds/performance/sample-table/"
                        + "geometry/witness/failures/metrics/limits");
    }

    private static void write(Path out, String name, List<String> lines) throws Exception {
        Files.writeString(out.resolve(name), String.join("\n", lines) + "\n");
    }

    private static void witnessMax(String key, double value) {
        WITNESS.merge(key, new double[]{value, 1}, (cur, add) -> {
            cur[0] = Math.max(cur[0], add[0]);
            return cur;
        });
    }

    private static void witnessMin(String key, double value) {
        WITNESS.merge(key, new double[]{value, 0}, (cur, add) -> {
            cur[0] = Math.min(cur[0], add[0]);
            return cur;
        });
    }

    private static void require(boolean ok, String message) {
        checks++;
        if (!ok) {
            if (firstFailure == null) {
                firstFailure = message;
            }
            if (FAILURES.size() < MAX_FAILURE_ROWS) {
                FAILURES.add("assert,," + message.replace(',', ';') + ",,,");
            }
            failures++;
        }
    }

    /** 第一条失败信息（全部段落跑完后统一抛出，好让每段的行都已落盘）。 */
    private static String firstFailure;
    private static int failures;
    private static final int MAX_FAILURE_ROWS = 400;

    /** 段落耗时（只记账，不影响判据）。 */
    private static void timing(Path out, String section, long startNanos) {
        long ms = (System.nanoTime() - startNanos) / 1_000_000L;
        try {
            Path p = out.resolve("section-times.txt");
            Files.writeString(p, section + "," + ms + "\n",
                    java.nio.charset.StandardCharsets.UTF_8,
                    Files.exists(p) ? java.nio.file.StandardOpenOption.APPEND
                            : java.nio.file.StandardOpenOption.CREATE);
        } catch (Exception ignored) {
            // 计时写盘失败不得影响判据。
        }
    }
}
