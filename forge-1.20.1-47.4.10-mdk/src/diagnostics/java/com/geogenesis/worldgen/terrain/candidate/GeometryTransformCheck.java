package com.geogenesis.worldgen.terrain.candidate;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * <b>N2 门禁：连续几何原语 / 导数 / 单射性</b>（{@code main(args[0]=outDir)}，任一 FAIL 非零退出）。
 *
 * <p><b>只测原语</b>：不接 {@code FullMorphLand}/{@code CoherentMorphWorld}，不接生产，
 * 不替换 {@link RegionPartition}，不动 S3 基准、地形参数、240 带与默认值。</p>
 *
 * <h3>判据层次（每条都写明"证到了什么"）</h3>
 * <ol>
 *   <li><b>同源</b>：{@link GeometryTransform} 的 seed/频率/gain/min/max 反射自真实分区节点；
 *       与 float 版 {@link RefNoise.Perlin} 在远离 ULP 台阶处一致（近点容差，不是逐位）；</li>
 *   <li><b>导数</b>：解析式 vs 中心差分（多处 h）、以及多项式式 vs 解析式（必须逐位为 0）；</li>
 *   <li><b>全局界</b>：对全部 8^4 角梯度组合做 Bernstein 包围 —— 覆盖平面每一点、每个 seed；</li>
 *   <li><b>逐格单射</b>：按<b>实际 seed</b> 逐格算 det J 的严格下界；</li>
 *   <li><b>不能证的</b>：二维 Banach 条件（sup row-sum L &gt; 1）不成立 ⇒ 整体单射<b>未证</b>，
 *       只报局部可逆 + 未覆盖区域，不写"已修好"。</li>
 * </ol>
 */
public final class GeometryTransformCheck {
    private static final long[] SEEDS = {9139912035078620160L, 0L, -419L, 12345L};
    private final Map<String, List<String>> tables = new LinkedHashMap<>();
    private final List<String> report = new ArrayList<>();
    private Path out;
    private int checks, failures;

    public static void main(String[] args) {
        var check = new GeometryTransformCheck();
        try {
            if (args.length != 1) {
                throw new IllegalArgumentException("usage: GeometryTransformCheck outDirectory");
            }
            check.out = Path.of(args[0]);
            Files.createDirectories(check.out);
            check.run();
        } catch (Throwable t) {
            check.failures++;
            check.report.add("FATAL " + t);
            t.printStackTrace();
        }
        try {
            check.save();
        } catch (Throwable t) {
            check.failures++;
            t.printStackTrace();
        }
        System.out.println("N2 GEOMETRY " + (check.failures == 0 ? "PASS" : "FAIL")
                + " checks=" + check.checks + " failures=" + check.failures);
        if (check.failures != 0) {
            System.exit(1);
        }
    }

    private void run() throws Exception {
        report.add("N2 only: continuous primitive + derivative + Jacobian. No height chain, no new inverse,");
        report.add("no RegionPartition replacement, no S3 baseline / parameter / 240-band / default / production edits.");
        report.add("JVM input arguments=" + java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments());
        stage("source-alignment", this::sourceAlignment);
        stage("derivative-identity", this::derivativeIdentity);
        stage("cell-polynomial", this::cellPolynomial);
        stage("global-bound", this::globalBound);
        stage("per-cell-injectivity", this::perCellInjectivity);
        stage("limits", this::limits);
    }

    // ------------------------------------------------------------ 1. 同源

    private void sourceAlignment() throws Exception {
        for (long seed : SEEDS) {
            RegionPartition partition = CoherentRegionDistribution.microPartition(seed, 600f);
            GeometryTransform t = GeometryTransform.of(partition);
            RefNoise.Node wx = (RefNoise.Node) RecipeMorphCatalog.field(partition, "warpX");
            RefNoise.Node wz = (RefNoise.Node) RecipeMorphCatalog.field(partition, "warpZ");
            require(t.seedX() == (Integer) RecipeMorphCatalog.field(wx, "seed")
                            && t.seedZ() == (Integer) RecipeMorphCatalog.field(wz, "seed"),
                    "seed not taken from the real partition nodes");
            require(t.frequency() == (double) (Float) RecipeMorphCatalog.field(partition, "frequency"),
                    "frequency must be the float reciprocal promoted, not 1.0/scale");
            double maxNear = 0, maxFar = 0;
            int skip = 0;
            Random rnd = new Random(20261101L ^ seed);
            for (int i = 0; i < 2000; i++) {
                double x = rnd.nextDouble() * 8000 - 4000, z = rnd.nextDouble() * 8000 - 4000;
                float fx = (float) x, fz = (float) z;
                float ox = (wx.compute(fx, fz) - 0.5f) * LocalBoundaryGeometry.WARP_STRENGTH;
                float oz = (wz.compute(fx, fz) - 0.5f) * LocalBoundaryGeometry.WARP_STRENGTH;
                double[] mine = t.offset(x, z);
                double dx = Math.abs(mine[0] - ox), dz = Math.abs(mine[1] - oz);
                // float 坐标的 ULP 会按自身比例放大；用它区分"精度差"与"公式差"
                double ulp = Math.ulp(fx) + Math.ulp(fz);
                if (ulp > 0.05) {
                    skip++;
                    maxFar = Math.max(maxFar, Math.max(dx, dz));
                    continue;
                }
                maxNear = Math.max(maxNear, Math.max(dx, dz));
            }
            table("source-alignment.csv", "seed,maxAbsOffsetDiffNear,maxAbsOffsetDiffCoarse,skippedCoarse")
                    .add(csv(seed, maxNear, maxFar, skip));
            require(maxNear < 1e-3, "seed=" + seed + " continuous offset differs from float beyond rounding: " + maxNear);
            report.add("seed=" + seed + " near-window max |Δoffset| = " + maxNear
                    + " (coarse-ulp points skipped=" + skip + ", kept for record only)");
        }
    }

    // ------------------------------------------------------------ 2. 导数

    private void derivativeIdentity() throws Exception {
        double worstFinite = 0;
        for (long seed : SEEDS) {
            GeometryTransform t = GeometryTransform.of(
                    CoherentRegionDistribution.microPartition(seed, 600f));
            Random rnd = new Random(0x5eed ^ seed);
            for (int i = 0; i < 4000; i++) {
                double x = rnd.nextDouble() * 20000 - 10000, z = rnd.nextDouble() * 20000 - 10000;
                double[] d = t.offsetAndJacobian(x, z);
                for (double h : new double[]{1e-2, 1e-3, 1e-4, 1e-5}) {
                    double[] px = t.offset(x + h, z), mx = t.offset(x - h, z);
                    double[] pz = t.offset(x, z + h), mz = t.offset(x, z - h);
                    // 布局：{ox, oz, dox/dx, dox/dz, doz/dx, doz/dz}
                    double doxdx = (px[0] - mx[0]) / (2 * h), dozdx = (px[1] - mx[1]) / (2 * h);
                    double doxdz = (pz[0] - mz[0]) / (2 * h), dozdz = (pz[1] - mz[1]) / (2 * h);
                    worstFinite = Math.max(worstFinite, Math.abs(doxdx - d[2]));
                    worstFinite = Math.max(worstFinite, Math.abs(doxdz - d[3]));
                    worstFinite = Math.max(worstFinite, Math.abs(dozdx - d[4]));
                    worstFinite = Math.max(worstFinite, Math.abs(dozdz - d[5]));
                }
            }
        }
        table("derivative-finite-difference.csv", "maxAbsDiffAllSteps,maxAllowed")
                .add(csv(worstFinite, 2e-4));
        require(worstFinite < 2e-4, "analytic Jacobian vs central difference too large: " + worstFinite);
        report.add("analytic vs central difference (h=1e-2..1e-5, 4 seeds, 4000 pts each): max |Δ| = " + worstFinite);
    }

    // ------------------------------------------------------------ 3. 多项式式 == 解析式

    private void cellPolynomial() throws Exception {
        double worst = 0;
        int combos = 0;
        for (int a = 0; a < 8; a++) {
            for (int b = 0; b < 8; b++) {
                for (int c = 0; c < 8; c++) {
                    for (int d = 0; d < 8; d++) {
                        double[][] g = {gradOf(a), gradOf(b), gradOf(c), gradOf(d)};
                        GeometryCellBound.Poly du = GeometryCellBound.duPolynomial(g);
                        GeometryCellBound.Poly dv = GeometryCellBound.dvPolynomial(g);
                        for (double t : new double[]{0, .125, .25, .5, .75, .875, 1}) {
                            for (double s : new double[]{0, .125, .25, .5, .75, .875, 1}) {
                                double[] v = valueAndDerivative(g, t, s);
                                worst = Math.max(worst, Math.abs(du.at(t, s) - v[1]));
                                worst = Math.max(worst, Math.abs(dv.at(t, s) - v[2]));
                            }
                        }
                        combos++;
                    }
                }
            }
        }
        table("cell-polynomial.csv", "combos,maxAbsDiff,maxAllowed").add(csv(combos, worst, 1e-12));
        require(combos == 4096, "expected all 8^4 corner-gradient combos");
        require(worst < 1e-12, "polynomial derivative disagrees with analytic form: " + worst);
        report.add("all " + combos + " corner-gradient combos: polynomial == analytic derivative, max |Δ| = " + worst);
    }

    /** 与 {@link GeometryTransform#sampleValueAndGradient} 同式，但接受外部给的四角梯度。 */
    private static double[] valueAndDerivative(double[][] g, double t, double s) {
        double d00 = g[0][0] * t + g[0][1] * s;
        double d10 = g[1][0] * (t - 1) + g[1][1] * s;
        double d01 = g[2][0] * t + g[2][1] * (s - 1);
        double d11 = g[3][0] * (t - 1) + g[3][1] * (s - 1);
        double xs = t * t * (3 - 2 * t), ys = s * s * (3 - 2 * s);
        double dxs = 6 * t * (1 - t), dys = 6 * s * (1 - s);
        double f0 = d00 + xs * (d10 - d00), f2 = d01 + xs * (d11 - d01);
        double a = g[0][0] + dxs * (d10 - d00) + xs * (g[1][0] - g[0][0]);
        double b = g[2][0] + dxs * (d11 - d01) + xs * (g[3][0] - g[2][0]);
        double s0 = g[0][1] + xs * (g[1][1] - g[0][1]);
        double s2 = g[2][1] + xs * (g[3][1] - g[2][1]);
        return new double[]{f0 + ys * (f2 - f0), a + ys * (b - a),
                s0 + ys * (s2 - s0) + dys * (f2 - f0)};
    }

    private static double[] gradOf(int i) {
        return new double[][]{{-1, -1}, {1, -1}, {-1, 1}, {1, 1},
                {0, -1}, {-1, 0}, {0, 1}, {1, 0}}[i];
    }

    // ------------------------------------------------------------ 4. 全局界

    private void globalBound() throws Exception {
        double comp = 0, rowSum = 0;
        for (int a = 0; a < 8; a++) {
            for (int b = 0; b < 8; b++) {
                for (int c = 0; c < 8; c++) {
                    for (int d = 0; d < 8; d++) {
                        double[][] g = {gradOf(a), gradOf(b), gradOf(c), gradOf(d)};
                        double[][] u = GeometryCellBound.duPolynomial(g).bernstein();
                        double[][] v = GeometryCellBound.dvPolynomial(g).bernstein();
                        comp = Math.max(comp, Math.max(Math.abs(GeometryCellBound.minOf(u)),
                                Math.max(Math.abs(GeometryCellBound.maxOf(u)),
                                        Math.max(Math.abs(GeometryCellBound.minOf(v)),
                                                Math.abs(GeometryCellBound.maxOf(v))))));
                        rowSum = Math.max(rowSum, Math.abs(GeometryCellBound.minOf(u))
                                + Math.abs(GeometryCellBound.minOf(v)));
                    }
                }
            }
        }
        double k = GeometryTransform.of(0, 0, 400f).derivativeScale();
        table("global-bound.csv", "boundNormalized,worldDerivative,rowSumNormalized,rowSumWorld,supRowSumL")
                .add(csv(comp, comp * k, rowSum, rowSum * k, rowSum * k));
        report.add("GLOBAL (all 8^4 combos, every point of the plane): |∂n/∂u| <= " + comp
                + " -> world " + (comp * k) + "; sup row-sum L <= " + (rowSum * k));
        require(rowSum * k > 1.0, "unexpected: Banach row-sum condition would hold; re-derive the claim");
        report.add("=> the 2-D Banach condition L<1 does NOT hold; global injectivity is NOT proven here.");
    }

    // ------------------------------------------------------------ 5. 逐格单射

    private void perCellInjectivity() throws Exception {
        int cells = 0;
        double worst = Double.POSITIVE_INFINITY;
        String where = "";
        for (long seed : SEEDS) {
            RegionPartition partition = CoherentRegionDistribution.microPartition(seed, 600f);
            GeometryTransform t = GeometryTransform.of(partition);
            double localWorst = Double.POSITIVE_INFINITY;
            int negatives = 0;
            double k = t.derivativeScale();
            for (int i = -60; i <= 60; i++) {
                for (int j = -60; j <= 60; j++) {
                    double[] bounds = GeometryCellBound.detBounds(t.seedX(), t.seedZ(), i, j, 2, k);
                    cells++;
                    if (bounds[0] < 0) {
                        negatives++;
                    }
                    if (bounds[0] < localWorst) {
                        localWorst = bounds[0];
                        if (bounds[0] < worst) {
                            worst = bounds[0];
                            where = "seed=" + seed + " noiseCell=(" + i + "," + j + ")";
                        }
                    }
                }
            }
            table("per-cell-injectivity.csv", "seed,noiseCells,minDetLower,cellsWithNegativeLower")
                    .add(csv(seed, 121 * 121, localWorst, negatives));
            require(negatives == 0, "seed=" + seed + " has noise cells whose rigorous det J lower bound is < 0");
            report.add("seed=" + seed + " 121x121 warp-noise cells: rigorous min det J = " + localWorst);
        }
        report.add("PROVEN: on " + cells + " scanned warp-noise cells (4 seeds), det J > 0 strictly (worst "
                + worst + " at " + where + ") => warp is locally invertible there.");
        // 严格性对照：随机点直算的 det 必须落在包围盒内
        Random rnd = new Random(7);
        int inside = 0;
        double wf = GeometryTransform.of(
                CoherentRegionDistribution.microPartition(SEEDS[0], 600f)).warpFrequency();
        for (long seed : SEEDS) {
            RegionPartition partition = CoherentRegionDistribution.microPartition(seed, 600f);
            GeometryTransform t = GeometryTransform.of(partition);
            for (int i = 0; i < 500; i++) {
                int cx = rnd.nextInt(101) - 50, cz = rnd.nextInt(101) - 50;
                double[] b = GeometryCellBound.detBounds(t.seedX(), t.seedZ(), cx, cz, 2, t.derivativeScale());
                // 包围格是「warp 噪声格」：world = 噪声整数格 / warpFrequency
                double x = (cx + rnd.nextDouble()) / wf;
                double z = (cz + rnd.nextDouble()) / wf;
                double[] j = t.offsetAndJacobian(x, z);
                double det = (1 + j[2]) * (1 + j[5]) - j[3] * j[4];
                require(det >= b[0] - 1e-9 && det <= b[1] + 1e-9,
                        "rigorous enclosure violated at seed=" + seed + " (" + x + "," + z
                                + ") det=" + det + " bounds=[" + b[0] + "," + b[1] + "]");
                inside++;
            }
        }
        table("enclosure-sanity.csv", "sampledPoints,allInside").add(csv(inside, true));
        report.add("enclosure sanity: " + inside + " random points all inside their rigorous det J box");
    }

    // ------------------------------------------------------------ 6. 边界

    private void limits() throws Exception {
        double worst = 0;
        for (double v : new double[]{1e4, 1e5, 1e6, 1e7, 29998000}) {
            float f = (float) v;
            double ulp = Math.ulp(f);
            worst = Math.max(worst, ulp / 400.0);
            table("float-ulp.csv", "world,floatUlp,phaseCellsLost").add(csv(v, ulp, ulp / 400.0));
        }
        require(worst > 0, "float ULP table empty");
        report.add("float ULP at 3e7 loses up to " + worst + " u-cells per quantum (info only; no threshold set)");
        List<String> unproven = table("unproven.csv", "item,status");
        unproven.add(csv("global injectivity of world->u", "NOT PROVEN (sup row-sum L>1)"));
        unproven.add(csv("coverage outside scanned cells", "NOT SCANNED"));
        unproven.add(csv("multi-octave / other warp configs", "NOT COVERED (primitive rejects octaves!=1)"));
        unproven.add(csv("integer hash collisions between cells", "NOT ANALYSED"));
        unproven.add(csv("height chain / production wiring", "OUT OF SCOPE (N2)"));
    }

    // ------------------------------------------------------------ 工具

    private void stage(String name, Action action) throws Exception {
        long start = System.nanoTime();
        try {
            action.run();
            report.add(name + " PASS elapsedMs=" + (System.nanoTime() - start) / 1_000_000);
        } catch (Throwable t) {
            failures++;
            report.add(name + " FAIL " + t);
            t.printStackTrace();
            saveQuietly();
        }
    }

    private void saveQuietly() {
        try {
            save();
        } catch (Exception ignored) {
            // 失败信息已进 report
        }
    }

    private void save() throws Exception {
        for (Map.Entry<String, List<String>> e : tables.entrySet()) {
            Files.write(out.resolve(e.getKey()), e.getValue(), StandardCharsets.UTF_8);
        }
        List<String> summary = new ArrayList<>(report);
        summary.add("mode=N2-continuous-geometry status=" + (failures == 0 ? "PASS" : "FAIL")
                + " checks=" + checks + " failures=" + failures);
        Files.write(out.resolve("summary.txt"), summary, StandardCharsets.UTF_8);
    }

    private List<String> table(String name, String header) {
        return tables.computeIfAbsent(name, k -> new ArrayList<>(List.of(header)));
    }

    private static String csv(Object... values) {
        List<String> row = new ArrayList<>(values.length);
        for (Object v : values) {
            row.add("\"" + String.valueOf(v).replace("\"", "\"\"") + "\"");
        }
        return String.join(",", row);
    }

    private void require(boolean ok, String message) {
        checks++;
        if (!ok) {
            throw new AssertionError(message);
        }
    }

    private interface Action {
        void run() throws Exception;
    }
}
