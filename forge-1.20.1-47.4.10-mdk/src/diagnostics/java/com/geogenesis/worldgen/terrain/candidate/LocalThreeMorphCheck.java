package com.geogenesis.worldgen.terrain.candidate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * {@link LocalThreeMorphCandidate} 的独立判据程序：{@code main(args[0] = outDir)}。
 *
 * <h3>判据（任一失败 ⇒ 非零退出）</h3>
 * <ol>
 *   <li><b>三双边退化</b>：用 inverse smoothstep 反解出 position，交给
 *       {@code FourPairMorphCandidate.sample(x,z,position)}，与三向同权重逐点比较；
 *       三条边 {@code PLAIN_HILLS / PLAIN_MOUNTAINS / HILLS_MOUNTAINS} × 4 seed ×
 *       5 个固定 t 各 1000 点 + 1 组随机 t 1000 点，容差 {@code 2e-7}。</li>
 *   <li><b>端点对原族</b>：三个顶点分别对 {@code PlainsCandidate(PLAINS_GEO)} /
 *       {@code HillsCandidate(HILLS1_GEO)} / {@code MountainsCandidate(M1_GEO)}。</li>
 *   <li><b>纯区短路</b>：权重为 0 的族<b>不参与求值</b>（以节点求值计数观测）。</li>
 *   <li><b>finite / 确定性</b>：同输入两次取值逐位相等。</li>
 *   <li><b>重排不变 + 严格契约</b>：{@code sample(ids[],weights[])} 按 id 归并，随机打乱 200 次逐位相等；
 *       同 recipe 可重复出现；<b>Category 短名 / 未支持配方 / 长度不符 / 任一非法分量（负、超界、NaN、
 *       同 id 正负抵消）/ 离单纯形</b>必须全部报错。</li>
 *   <li><b>单纯形内 + 负控</b>：Dirichlet 内部点有限有界、确定性；且与三族<b>成品高度</b>的
 *       加权平均显著不同（证明不是成品 lerp）。</li>
 *   <li><b>三向 epsilon 收敛</b>：{@code w=(1−2ε, ε, ε)} 对同一<b>抽样点集</b>误差随 ε 单调收紧，
 *       {@code ε=1e-4} 时 {@code < 1e-3}。这是<b>逐点抽样</b>检查，不构成全局连续性证明。</li>
 * </ol>
 *
 * <p>只读：不修改任何共享代码、不写 {@code .md}，输出全部落在 {@code args[0]}。</p>
 */
public final class LocalThreeMorphCheck {

    /** 与定稿探针同批 seed（含用户实机 seed），便于对拍既有记录。 */
    private static final long[] SEEDS = {9139912035078620160L, 1L, -817263L, -42L};
    /** 双边退化比较的固定目标 t（反解成 position）。 */
    private static final double[] T_GRID = {0.0, 0.25, 0.5, 0.75, 1.0};
    /** 三条边：边的身份 → 哪一族权重为 0。 */
    private static final FourPairMorphCandidate.Pair[] EDGES = {
            FourPairMorphCandidate.Pair.PLAIN_HILLS,
            FourPairMorphCandidate.Pair.PLAIN_MOUNTAINS,
            FourPairMorphCandidate.Pair.HILLS_MOUNTAINS};
    private static final double TOLERANCE = 2e-7;
    private static final int POINTS = 1000;

    private static int checks;
    private static Path out;

    private interface Field { double at(double x, double z); }

    public static void main(String[] args) {
        try {
            Locale.setDefault(Locale.ROOT);
            out = args.length > 0 ? Path.of(args[0])
                    : Path.of("build/terrain-upgrade/real-boundary-local/morph-check-final");
            Files.createDirectories(out);
            run();
            System.out.println("PASS checks=" + checks + " out=" + out.toAbsolutePath());
        } catch (Throwable t) {
            t.printStackTrace();
            System.out.println("FAIL " + t);
            System.exit(1);
        }
    }

    private static void run() throws Exception {
        write("reduction.csv", reduceEdges());
        write("endpoints.csv", verifyEndpoints());
        write("shortcut.csv", verifyShortcut());
        write("permutation.csv", verifyPermutation());
        write("interior.csv", verifyInterior());
        write("convergence.csv", verifyConvergence());
        write("no-double-ease.csv", verifyNoDoubleEase());
        Files.writeString(out.resolve("limits.txt"), LIMITS);
        Files.writeString(out.resolve("metrics.txt"), String.format(Locale.ROOT,
                "checks=%d%nseeds=%s%nedges=%s%npoints_per_edge_t=%d%nfixed_t=%s%n"
                        + "tolerance=%.3e%ncheck_domain=PLAIN_GEO / HILLS1_GEO / M1_GEO only%n",
                checks, Arrays.toString(SEEDS), Arrays.toString(EDGES), POINTS,
                Arrays.toString(T_GRID), TOLERANCE));
    }

    // ================================================================ 1. 三双边退化

    private static List<String> reduceEdges() {
        List<String> csv = new ArrayList<>(List.of(
                "seed,edge,position,target_t,recovered_t,recovery_error,max_abs_diff,points"));
        for (long seed : SEEDS) {
            LocalThreeMorphCandidate three = new LocalThreeMorphCandidate(seed);
            for (FourPairMorphCandidate.Pair pair : EDGES) {
                FourPairMorphCandidate four = new FourPairMorphCandidate(seed, pair);
                reduceOneEdge(three, four, pair, seed, csv);
            }
        }
        return csv;
    }

    private static void reduceOneEdge(LocalThreeMorphCandidate three, FourPairMorphCandidate four,
                                      FourPairMorphCandidate.Pair pair, long seed, List<String> csv) {
        Random r = new Random(seed ^ (pair.ordinal() * 0x9E3779B9L));
        double worst = 0;
        for (double target : T_GRID) {
            double position = positionOf(target);
            double recovered = (float) HillsPlateauMorphCandidate.ease(position);
            double recovery = Math.abs(recovered - target);
            require(recovery <= TOLERANCE,
                    "inverse smoothstep recovery " + pair + " t=" + target + " err=" + recovery);
            double edgeWorst = 0;
            for (int i = 0; i < POINTS; i++) {
                double x = r.nextDouble() * 200000 - 100000;
                double z = r.nextDouble() * 200000 - 100000;
                edgeWorst = Math.max(edgeWorst, compareAt(three, four, pair, x, z, position, recovered));
            }
            worst = Math.max(worst, edgeWorst);
            require(edgeWorst <= TOLERANCE, "edge reduction " + pair + " t=" + target
                    + " max=" + edgeWorst);
            csv.add(String.format(Locale.ROOT, "%d,%s,%.12f,%.6f,%.9f,%.3e,%.3e,%d",
                    seed, pair, position, target, recovered, recovery, edgeWorst, POINTS));
        }
        double randomWorst = 0;
        for (int i = 0; i < POINTS; i++) {
            double x = r.nextDouble() * 200000 - 100000;
            double z = r.nextDouble() * 200000 - 100000;
            double position = r.nextDouble();
            double recovered = (float) HillsPlateauMorphCandidate.ease(position);
            randomWorst = Math.max(randomWorst,
                    compareAt(three, four, pair, x, z, position, recovered));
        }
        require(randomWorst <= TOLERANCE, "edge reduction random-t " + pair + " max=" + randomWorst);
        csv.add(String.format(Locale.ROOT, "%d,%s,random,random,random,na,%.3e,%d",
                seed, pair, randomWorst, POINTS));
        System.out.printf(Locale.ROOT, "seed=%d edge=%s worst=%.3e%n",
                seed, pair, Math.max(worst, randomWorst));
    }

    private static double compareAt(LocalThreeMorphCandidate three, FourPairMorphCandidate four,
                                    FourPairMorphCandidate.Pair pair, double x, double z,
                                    double position, double t) {
        double[] w = edgeWeights(pair, t);
        double threeWay = three.sample(x, z, w[0], w[1], w[2]);
        require(Double.isFinite(threeWay), "non-finite three-way sample");
        return Math.abs(threeWay - four.sample(x, z, position));
    }

    /** 边上权重：{@code 1−t} 给"低"族，{@code t} 给"高"族，第三族恒 0（与 FourPair 的 mix 同名同序）。 */
    private static double[] edgeWeights(FourPairMorphCandidate.Pair pair, double t) {
        return switch (pair) {
            case PLAIN_HILLS -> new double[]{1 - t, t, 0};
            case PLAIN_MOUNTAINS -> new double[]{1 - t, 0, t};
            case HILLS_MOUNTAINS -> new double[]{0, 1 - t, t};
            default -> throw new IllegalArgumentException("not a PHM edge: " + pair);
        };
    }

    /** smoothstep 的解析反函数：给 {@code t} 求 {@code position}，使 {@code ease(position) == t}。 */
    static double positionOf(double t) {
        double c = Math.max(0, Math.min(1, t));
        return 0.5 - Math.sin(Math.asin(1 - 2 * c) / 3.0);
    }

    // ================================================================ 2. 端点对原族

    private static List<String> verifyEndpoints() {
        List<String> csv = new ArrayList<>(List.of("seed,family,max_abs_diff,points"));
        for (long seed : SEEDS) {
            LocalThreeMorphCandidate three = new LocalThreeMorphCandidate(seed);
            Field plains = plainsField(seed);
            Field hills = hillsField(seed);
            Field mountains = mountainsField(seed);
            Random r = new Random(seed + 5);
            double[] worst = new double[3];
            for (int i = 0; i < 3000; i++) {
                double x = r.nextDouble() * 200000 - 100000;
                double z = r.nextDouble() * 200000 - 100000;
                worst[0] = Math.max(worst[0], checkVertex(three, 0, plains, x, z));
                worst[1] = Math.max(worst[1], checkVertex(three, 1, hills, x, z));
                worst[2] = Math.max(worst[2], checkVertex(three, 2, mountains, x, z));
            }
            String[] names = {"PLAIN_GEO", "HILLS1_GEO", "M1_GEO"};
            for (int i = 0; i < 3; i++) {
                require(worst[i] <= TOLERANCE, names[i] + " vertex mismatch=" + worst[i]);
                csv.add(String.format(Locale.ROOT, "%d,%s,%.3e,3000", seed, names[i], worst[i]));
            }
        }
        return csv;
    }

    private static double checkVertex(LocalThreeMorphCandidate three, int vertex,
                                      Field family, double x, double z) {
        double[] w = {0, 0, 0};
        w[vertex] = 1;
        double got = three.sample(x, z, w[0], w[1], w[2]);
        require(got == three.sample(x, z, w[0], w[1], w[2]), "vertex nondeterministic");
        return Math.abs(got - family.at(x, z));
    }

    // ================================================================ 3. 纯区短路

    private static List<String> verifyShortcut() {
        List<String> csv = new ArrayList<>(List.of("wP,wH,wM,node_evaluations,expected,ok"));
        LocalThreeMorphCandidate three = new LocalThreeMorphCandidate(SEEDS[0]);
        shortcutCase(three, 1, 0, 0, 1, csv);
        shortcutCase(three, 0, 1, 0, 1, csv);
        shortcutCase(three, 0, 0, 1, 3, csv);
        shortcutCase(three, 0.5, 0.5, 0, 2, csv);
        shortcutCase(three, 1.0 / 3, 1.0 / 3, 1.0 / 3, 5, csv);
        return csv;
    }

    private static void shortcutCase(LocalThreeMorphCandidate three, double wP, double wH, double wM,
                                     int expected, List<String> csv) {
        three.resetEvaluations();
        three.sample(1234.5, -6789.25, wP, wH, wM);
        int observed = three.evaluations();
        csv.add(String.format(Locale.ROOT, "%.6f,%.6f,%.6f,%d,%d,%s",
                wP, wH, wM, observed, expected, observed == expected));
        require(observed == expected, "pure-region short circuit expected " + expected
                + " got " + observed + " for " + wP + "/" + wH + "/" + wM);
    }

    // ================================================================ 4. 重排不变

    private static List<String> verifyPermutation() {
        List<String> csv = new ArrayList<>(List.of("case,shuffles,bitwise_identical,message"));
        LocalThreeMorphCandidate three = new LocalThreeMorphCandidate(SEEDS[0]);
        String[] ids = {"PLAIN_GEO", "M1_GEO", "PLAIN_GEO", "HILLS1_GEO"};
        double[] weights = {0.3, 0.4, 0.2, 0.1};
        double reference = three.sample(777.5, -321.25, ids, weights);
        require(Double.isFinite(reference), "permutation reference non-finite");

        Random r = new Random(99);
        String[] order = ids.clone();
        double[] shuffled = weights.clone();
        boolean identical = true;
        for (int i = 0; i < 200; i++) {
            shufflePair(order, shuffled, r);
            identical &= Double.compare(three.sample(777.5, -321.25, order, shuffled), reference) == 0;
        }
        csv.add("duplicate+shuffle,200," + identical + ",reference=" + reference);
        require(identical, "sample(ids,weights) depends on traversal order");

        rejectCase(csv, "category_short_plain", () -> three.sample(0, 0,
                new String[]{"PLAIN"}, new double[]{1.0}));
        rejectCase(csv, "category_short_hills", () -> three.sample(0, 0,
                new String[]{"HILLS"}, new double[]{1.0}));
        rejectCase(csv, "category_short_mountains", () -> three.sample(0, 0,
                new String[]{"MOUNTAINS"}, new double[]{1.0}));
        rejectCase(csv, "category_short_all_three", () -> three.sample(0, 0,
                new String[]{"PLAIN", "HILLS", "MOUNTAINS"}, new double[]{0.4, 0.3, 0.3}));
        rejectCase(csv, "unsupported_recipe", () -> three.sample(0, 0,
                new String[]{"PLATEAU_BADLANDS"}, new double[]{1.0}));
        rejectCase(csv, "lowercase_recipe", () -> three.sample(0, 0,
                new String[]{"plain_geo"}, new double[]{1.0}));
        rejectCase(csv, "length_mismatch", () -> three.sample(0, 0,
                new String[]{"PLAIN_GEO"}, new double[]{1.0, 0.0}));
        rejectCase(csv, "off_simplex_pair", () -> three.sample(0, 0,
                new String[]{"PLAIN_GEO", "M1_GEO"}, new double[]{0.5, 0.6}));
        // ★ 抵消漏洞：两个各自非法的分量归一化后变成合法配方，必须被逐分量校验拦下。
        rejectCase(csv, "cancel_negative_plus_over", () -> three.sample(0, 0,
                new String[]{"PLAIN_GEO", "PLAIN_GEO"}, new double[]{-0.5, 1.0}));
        rejectCase(csv, "cancel_tiny_negative", () -> three.sample(0, 0,
                new String[]{"PLAIN_GEO", "PLAIN_GEO"}, new double[]{-1e-9, 0.5000000005}));
        rejectCase(csv, "cancel_over_one", () -> three.sample(0, 0,
                new String[]{"M1_GEO", "M1_GEO"}, new double[]{1.2, -0.2}));
        rejectCase(csv, "nan_component", () -> three.sample(0, 0,
                new String[]{"PLAIN_GEO", "M1_GEO"}, new double[]{Double.NaN, 0.5}));
        rejectCase(csv, "infinite_component", () -> three.sample(0, 0,
                new String[]{"PLAIN_GEO", "M1_GEO"}, new double[]{Double.POSITIVE_INFINITY, 0.5}));
        // ★ 三元组直接采样的严格边界：负 t 不再被 -tol 放过。
        rejectCase(csv, "triplet_tiny_negative", () -> three.sample(0, 0, -1e-9, 1 + 1e-9, 0));
        rejectCase(csv, "triplet_over_one", () -> three.sample(0, 0, 1 + 1e-9, -1e-9, 0));
        rejectCase(csv, "triplet_negative", () -> three.sample(0, 0, -0.1, 0.6, 0.5));
        rejectCase(csv, "triplet_nan", () -> three.sample(0, 0, Double.NaN, 0.5, 0.5));
        rejectCase(csv, "triplet_sum_off", () -> three.sample(0, 0, 0.6, 0.6, 0.6));
        csv.add("invalid_rejects,0,true,all listed rejections observed");
        return csv;
    }

    /** 断言某调用抛出 {@link IllegalArgumentException}，并把结果记入 CSV。 */
    private static void rejectCase(List<String> csv, String name, Runnable action) {
        boolean thrown = throwsIllegal(action);
        csv.add(name + ",0," + thrown + ",expected IllegalArgumentException");
        require(thrown, "negative control '" + name + "' did not throw");
    }

    /** 同长度同步洗牌的 Fisher–Yates（同一排列同时作用于 id 与权重）。 */
    private static void shufflePair(String[] ids, double[] weights, Random r) {
        for (int i = ids.length - 1; i > 0; i--) {
            int j = r.nextInt(i + 1);
            String ti = ids[i];
            ids[i] = ids[j];
            ids[j] = ti;
            double tw = weights[i];
            weights[i] = weights[j];
            weights[j] = tw;
        }
    }

    private static boolean throwsIllegal(Runnable action) {
        try {
            action.run();
            return false;
        } catch (IllegalArgumentException expected) {
            return true;
        }
    }

    // ================================================================ 5. 单纯形内 + 负控

    private static List<String> verifyInterior() {
        List<String> csv = new ArrayList<>(List.of(
                "seed,points,wP_min,wH_min,wM_min,max_abs_diff_vs_family_mean,"
                        + "mean_abs_diff_vs_family_mean,min_abs_diff_vs_family_mean"));
        for (long seed : SEEDS) {
            LocalThreeMorphCandidate three = new LocalThreeMorphCandidate(seed);
            Field plains = plainsField(seed);
            Field hills = hillsField(seed);
            Field mountains = mountainsField(seed);
            Random r = new Random(seed + 11);
            double minDist = Double.MAX_VALUE, maxDist = 0, sumDist = 0;
            double[] minW = {1, 1, 1};
            for (int i = 0; i < POINTS; i++) {
                double[] w = simplexPoint(r);
                double x = r.nextDouble() * 12000 - 6000;
                double z = r.nextDouble() * 12000 - 6000;
                double value = three.sample(x, z, w[0], w[1], w[2]);
                require(Double.isFinite(value) && Math.abs(value) < 2, "interior out of range " + value);
                require(Double.compare(value, three.sample(x, z, w[0], w[1], w[2])) == 0,
                        "interior nondeterministic");
                minW[0] = Math.min(minW[0], w[0]);
                minW[1] = Math.min(minW[1], w[1]);
                minW[2] = Math.min(minW[2], w[2]);
                double mean = w[0] * plains.at(x, z) + w[1] * hills.at(x, z)
                        + w[2] * mountains.at(x, z);
                double dist = Math.abs(value - mean);
                minDist = Math.min(minDist, dist);
                maxDist = Math.max(maxDist, dist);
                sumDist += dist;
            }
            require(maxDist > 0.02, "three-way equals finished-height weighted mean (max=" + maxDist + ")");
            csv.add(String.format(Locale.ROOT, "%d,%d,%.6f,%.6f,%.6f,%.6f,%.6f,%.6f",
                    seed, POINTS, minW[0], minW[1], minW[2], maxDist, sumDist / POINTS, minDist));
            System.out.printf(Locale.ROOT, "interior seed=%d |v-mean| max=%.4f mean=%.4f min=%.4f%n",
                    seed, maxDist, sumDist / POINTS, minDist);
        }
        return csv;
    }

    /** Dirichlet(1,1,1) 采样：三权重恒为正、和为 1。 */
    private static double[] simplexPoint(Random r) {
        double a = -Math.log(1 - r.nextDouble());
        double b = -Math.log(1 - r.nextDouble());
        double c = -Math.log(1 - r.nextDouble());
        double s = a + b + c;
        return new double[]{a / s, b / s, c / s};
    }

    // ================================================================ 6. 三向 epsilon 收敛

    private static List<String> verifyConvergence() {
        List<String> csv = new ArrayList<>(List.of(
                "seed,vertex,epsilon,max_abs_diff_vs_vertex,ratio_to_previous"));
        double[] epsilons = {1e-1, 1e-2, 1e-3, 1e-4};
        for (long seed : SEEDS) {
            LocalThreeMorphCandidate three = new LocalThreeMorphCandidate(seed);
            for (int vertex = 0; vertex < 3; vertex++) {
                double[][] points = pointSet(new Random(seed + vertex * 31L), 200);
                double previous = Double.NaN, last = Double.NaN;
                for (double eps : epsilons) {
                    double[] w = approaching(vertex, eps);
                    double worst = 0;
                    for (double[] pt : points) {
                        double[] pure = {0, 0, 0};
                        pure[vertex] = 1;
                        worst = Math.max(worst, Math.abs(
                                three.sample(pt[0], pt[1], w[0], w[1], w[2])
                                        - three.sample(pt[0], pt[1], pure[0], pure[1], pure[2])));
                    }
                    String ratio = Double.isNaN(previous) ? "na"
                            : String.format(Locale.ROOT, "%.3f", worst / previous);
                    csv.add(String.format(Locale.ROOT, "%d,%d,%.3e,%.3e,%s",
                            seed, vertex, eps, worst, ratio));
                    if (!Double.isNaN(previous)) {
                        require(worst < previous, "epsilon did not tighten at vertex " + vertex
                                + " eps=" + eps + " worst=" + worst + " previous=" + previous);
                    }
                    previous = worst;
                    last = worst;
                }
                require(last < 1e-3, "epsilon limit too loose at vertex " + vertex + " -> " + last);
            }
        }
        return csv;
    }

    private static double[][] pointSet(Random r, int count) {
        double[][] pts = new double[count][];
        for (int i = 0; i < count; i++) {
            pts[i] = new double[]{r.nextDouble() * 4000 - 2000, r.nextDouble() * 4000 - 2000};
        }
        return pts;
    }

    /** {@code vertex} 权 {@code 1−2ε}，另两族各 {@code ε}（和恒为 1）。 */
    private static double[] approaching(int vertex, double eps) {
        double[] w = {eps, eps, eps};
        w[vertex] = 1 - (eps + eps);
        return w;
    }

    // ================================================================ 7. 权重不得二次 ease

    /**
     * 反证"权重已 smoothstep、本类不再 ease"：
     *
     * <p>取一对<b>已经 smoothstep 过</b>的权重 {@code (1−t, t, 0)}，若本类再套一次
     * {@code ease}，等价于用 {@code ease(t)} 采样。故只要证明
     * {@code sample(…, 1−t, t, 0) ≠ sample(…, 1−ease(t), ease(t), 0)} 在若干 t 上成立，
     * 就排除了"二次 ease"。{@code ease} 在 {@code (0,1)} 内只有 {@code t=0.5} 一个定点，
     * 那里两者<b>必须</b>相等 —— 该点作正控，证明上述判据不是恒真。
     */
    private static List<String> verifyNoDoubleEase() {
        List<String> csv = new ArrayList<>(List.of("seed,t,ease_t,abs_diff,is_fixed_point,ok"));
        double[] probes = {0.1, 0.25, 0.4, 0.5, 0.6, 0.75, 0.9};
        for (long seed : SEEDS) {
            LocalThreeMorphCandidate three = new LocalThreeMorphCandidate(seed);
            Random r = new Random(seed + 23);
            for (double t : probes) {
                double e = HillsPlateauMorphCandidate.ease(t);
                boolean fixed = Math.abs(e - t) < 1e-12;
                double worst = 0;
                for (int i = 0; i < 200; i++) {
                    double x = r.nextDouble() * 20000 - 10000;
                    double z = r.nextDouble() * 20000 - 10000;
                    double direct = three.sample(x, z, 1 - t, t, 0);
                    double eased = three.sample(x, z, 1 - e, e, 0);
                    worst = Math.max(worst, Math.abs(direct - eased));
                }
                if (fixed) {
                    require(worst < 1e-12, "fixed point t=" + t + " must be identical, got " + worst);
                } else {
                    require(worst > 1e-9, "weights look re-eased at t=" + t + " (diff=" + worst + ")");
                }
                csv.add(String.format(Locale.ROOT, "%d,%.9f,%.9f,%.3e,%s,true",
                        seed, t, e, worst, fixed));
            }
        }
        return csv;
    }

    // ================================================================ 族字段
    private static Field plainsField(long seed) {
        PlainsCandidate p = new PlainsCandidate(PlainsCandidate.Variant.PLAINS_GEO);
        p.seed(seed);
        return p::height01;
    }

    private static Field hillsField(long seed) {
        HillsCandidate h = new HillsCandidate(HillsCandidate.Variant.HILLS_1_GEO);
        h.seed(seed + LocalThreeMorphCandidate.OFFSET_HILLS);
        return h::height01;
    }

    private static Field mountainsField(long seed) {
        MountainsCandidate m = new MountainsCandidate(MountainsCandidate.Variant.M1_GEO);
        m.seed(seed + LocalThreeMorphCandidate.OFFSET_MOUNTAINS);
        return m::height01;
    }

    // ================================================================ 工具

    private static void write(String name, List<String> lines) throws Exception {
        Files.writeString(out.resolve(name), String.join("\n", lines) + "\n");
    }

    private static void require(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }

    private static final String LIMITS =
            "coverage=PLAIN_GEO / HILLS1_GEO / M1_GEO triangle only\n"
            + "not_covered=PLATEAU, BADLANDS, real composite terms, full-map wiring\n"
            + "proven=edge/vertex numerical reduction, order independence, "
            + "epsilon continuity inside the triangle, pure-region short circuit, "
            + "weights consumed once (no second ease), strict recipe-id and per-component "
            + "weight contract\n"
            + "not_proven=visual acceptability, cliff attribution, plateau/badlands "
            + "behaviour, chunk-generation cost\n"
            + "note=continuity is checked pointwise (epsilon sweep), not proven globally\n";
}
