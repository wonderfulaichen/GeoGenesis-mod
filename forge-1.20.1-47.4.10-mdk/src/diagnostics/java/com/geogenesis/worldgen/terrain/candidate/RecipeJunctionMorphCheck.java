package com.geogenesis.worldgen.terrain.candidate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

/**
 * {@link RecipeJunctionMorph} 的独立判据程序：{@code main(args[0] = outDir)}。
 *
 * <h3>判据（任一失败 ⇒ 非零退出）</h3>
 * <ol>
 *   <li><b>18 端点</b>：{@code weights[id]=1} 逐点对 {@code catalog.entry(id).reference(x,z)}，
 *       18 配方 × 3 seed × 1000 点，容差 {@code 2e-7}；同时记录
 *       {@code |reference − (raw·amplitude+bias[+clamp])|} 残差（该恒等式只对 LOW/复合成立，
 *       台地/劣地/山地的 raw 是非线性算子的输入，残差只留痕不作判据）与 {@code ids} 重载是否逐位一致。</li>
 *   <li><b>已认可的 10 条双族边</b>（{@code FourPair} 9 条 + {@code HillsPlateau} 1 条）：
 *       固定 t 网格 + 随机 t，用 <b>inverse smoothstep</b> 反解 position 传给旧双族实现，
 *       <b>只有权重控制</b>新引擎；容差 {@code 3e-7}。</li>
 *   <li><b>旧三族回归</b>：{@code LocalThreeMorphCandidate}（{@code 0/3/17}）三分点 + 内部 Dirichlet，
 *       容差 {@code 3e-7}。</li>
 *   <li><b>C(18,2)=153 边 + 若干 triple</b>：全部有限、有界、逐位确定、id 顺序无关、
 *       同配方拆分后归并逐位一致。</li>
 *   <li><b>五大类 10 个无序三组</b>：三角形内部 Dirichlet 采样有限/确定；三个顶点方向
 *       ε 递减（{@code 1e-1…1e-4}）收敛到端点，{@code ε=1e-4} 时 {@code < 1e-3}。
 *       ⚠ 逐点抽样，<b>不构成全域连续性证明</b>。</li>
 *   <li><b>三族→双边退化</b>：每个三组的每条边，把第三族权重压到 {@code ε}（{@code 1e-2/1e-3/1e-4}），
 *       与<b>同一引擎</b>的 renormalized 双权重比较，终值 {@code ≤ 1e-3}。
 *       ⚠ 这是"同参数退化"，<b>不是</b>严格几何连续性证明。</li>
 *   <li><b>台地端点口径</b>：id 15 的端点必须对 <b>参考修正</b>的 {@code Terrace} 值域（0..0.9），
 *       因此与旧 {@code PlateauCandidate.REF}（0..1.05 节点）必须显著不同；相同即判失败。</li>
 *   <li><b>负控区分"平均值"</b>：内部点与"成品端点加权平均"必须显著不同（证明不是成品 lerp），
 *       并反证权重不被二次 ease。</li>
 *   <li><b>契约拒绝</b>：长度、越界 id、负/超界/NaN/∞ 分量、和不为 1 全部抛
 *       {@link IllegalArgumentException}。</li>
 * </ol>
 *
 * <p>端点台地（id 15）的目标是 {@code catalog} 的<b>参考修正</b>条目，不是旧
 * {@code PlateauCandidate.REF} 的 1.05 口径。</p>
 *
 * <p><b>⚠ M↔劣地（{@code MOUNTAINS_BADLANDS}）的顺序</b>：旧 {@code FourPairMorphCandidate} 把
 * 山地当 {@code a}、劣地当 {@code b}，与下标序（16 劣地 &lt; 17 山地）相反。故边登记为
 * {@code high=16 / low=17}（见 {@link #approvedEdges}）——引擎按"低下标在前"取
 * {@code a=劣地, b=山地, t=w[16]}，与旧实现 {@code mix(mountain, badlands, t)} 同向。
 * 其余九条边下标序与旧 {@code mix(low,high,t)} 一致。</p>
 *
 * <p>只读：不修改任何共享代码、不写 {@code .md}，输出全部落在 {@code args[0]}。
 * 每个判据的实测极值另有一份 {@code witness.csv}（指标 → 值 → 来源 CSV → 容差 → 判定），
 * 使 {@code metrics.txt} 里的数字都能回溯到具体列。</p>
 */
public final class RecipeJunctionMorphCheck {

    /** 含用户实机 seed，便于与既有记录对拍。 */
    private static final long[] SEEDS = {9139912035078620160L, 1L, -42L};
    /** 端点容差（用户指定）。 */
    private static final double TOL_ENDPOINT = 2e-7;
    /** 双族边 / 三族回归容差（用户指定）。 */
    private static final double TOL_REGRESSION = 3e-7;
    private static final int ENDPOINT_POINTS = 1000;
    private static final int EDGE_POINTS = 1000;
    private static final int TRIPLE_POINTS = 500;
    private static final int STRUCTURE_POINTS = 64;
    private static final int SWEEP_POINTS = 128;
    private static final int INTERIOR_POINTS = 200;
    /**
     * 值域<b>粗筛</b>上界：只用于发现"某配方返回到 1e6 级"的失控，不作为物理量程断言
     * （台地参考条目的 base 量级由 catalog 决定，判据是有限而非具体区间）。
     */
    private static final double VALUE_LIMIT = 100.0;
    private static final double[] T_GRID = {0.0, 0.25, 0.5, 0.75, 1.0};
    private static final int RANDOM_T = 3;
    private static final double[] EPSILONS = {1e-1, 1e-2, 1e-3, 1e-4};
    private static final double EPSILON_LIMIT = 1e-3;
    private static final double NEGATIVE_CONTROL_MIN = 0.02;
    /** 台地端点相对旧 {@code PlateauCandidate.REF}（0..1.05）的最小必要偏离。 */
    private static final double PLATEAU_LEGACY_MIN = 0.01;
    private static final int[][] CLASS_TRIPLES = classTriples();
    private static final int[][] OTHER_TRIPLES = {{0, 3, 17}, {15, 16, 17}, {3, 4, 15}, {0, 15, 17}, {4, 16, 17}};
    /** 四域候选（含劣地 + 台地混排，故意跨"独立条目"边界）。 */
    private static final int[][] QUADS = {{0, 1, 4, 15}, {0, 3, 16, 17}, {3, 15, 16, 17}};
    /** 五域候选：{@code 5,9,15,16,17} 是用户点名的复合+台地+劣地+山地混排。 */
    private static final int[][] QUINTS = {{5, 9, 15, 16, 17}, {0, 3, 15, 16, 17}};
    /** 退化到双边用的 ε 阶梯：第三族权重趋 0。 */
    private static final double[] EDGE_EPSILONS = {1e-2, 1e-3, 1e-4};
    /** 退化到双边时 ε 终值的上界。 */
    private static final double EDGE_EPS_LIMIT = 1e-3;
    /** 退化到双边用的固定点集大小（每 triple × 每条边 × 每 ε 共用同一点集）。 */
    private static final int EDGE_EPS_POINTS = 128;
    /** 退化到双边时另两族的内部配比（非端点，避免退化恰好落在端点而失去意义）。 */
    private static final double CROSS_PAIR_LOW = 0.3;

    private static int checks;
    /**
     * 失败台账：任何 {@code require} 抛错前先把"具体可复现坐标"追加到这里，
     * 由 {@link #main} 的 catch 写盘。否则第一条越阈会把后面的 CSV 全部吞掉。
     */
    private static final List<String> FAILURES = new ArrayList<>(List.of(
            "seed,edge,high_id,low_id,t,recovered_t,x,z,max_abs_diff,tolerance"));
    /**
     * 各判据的实测头号量：键 → 单调极值（max 用 −∞ 起、min 用 +∞ 起）。
     * 只用于把 {@code metrics.txt} 变成"自证"的汇总 —— 每一行都能回指对应 CSV 的某一列。
     */
    private static final Map<String, double[]> WITNESS = new TreeMap<>();

    private interface OldMorph { double at(double x, double z, double position); }

    private record Edge(int high, int low, String name, OldMorph old) { }

    /** 一组点的最差点：值 + 坐标，供失败台账逐位复现。 */
    private record Worst(double diff, double x, double z) { }

    public static void main(String[] args) {
        Path out = args.length > 0 ? Path.of(args[0])
                : Path.of("build/terrain-upgrade/recipe-junctions/check");
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
                // 台账尽力而为：写盘失败不得掩盖原始断言错误。
            }
            System.out.println("FAIL " + t);
            System.exit(1);
        }
    }

    private static void run(Path out) throws Exception {
        // 端点放最后：前面的判据若已失败也先把它们的 CSV 落盘，便于逐项定位。
        write(out, "plateau-reference.csv", sectionPlateauReference());
        write(out, "edges.csv", sectionEdges());
        write(out, "local-triple.csv", sectionLocalTriple());
        write(out, "triangles-epsilon.csv", sectionTriangles());
        write(out, "triangles-edge-collapse.csv", sectionEdgeCollapse());
        write(out, "triangles-interior.csv", sectionTriangleInterior());
        write(out, "negative-control.csv", sectionNegativeControl());
        write(out, "structure-all-pairs.csv", sectionAllPairs());
        write(out, "rejects.csv", sectionRejects());
        write(out, "endpoints.csv", sectionEndpoints());
        write(out, "witness.csv", sectionWitness());
        write(out, "failures.csv", FAILURES);
        write(out, "repro.txt", repro());
        Files.writeString(out.resolve("metrics.txt"), metrics() + "result=PASS\n");
        Files.writeString(out.resolve("limits.txt"), LIMITS);
    }

    // ================================================================ 1. 18 端点

    private static List<String> sectionEndpoints() {
        List<String> csv = new ArrayList<>(List.of(
                "seed,id,kind,reference_is_raw_amp_bias,max_abs_diff_vs_reference,"
                        + "max_abs_reference_minus_raw_amp_bias,ids_path_bitwise,points,tolerance"));
        for (long seed : SEEDS) {
            RecipeMorphCatalog cat = new RecipeMorphCatalog(seed);
            RecipeJunctionMorph engine = new RecipeJunctionMorph(cat, seed);
            Random r = new Random(seed + 101);
            double[] w = new double[18];
            for (int id = 0; id < 18; id++) endpoint(csv, engine, cat, seed, id, r, w);
        }
        return csv;
    }

    private static void endpoint(List<String> csv, RecipeJunctionMorph engine, RecipeMorphCatalog cat,
                                 long seed, int id, Random r, double[] w) {
        Arrays.fill(w, 0);
        w[id] = 1;
        double worst = 0;
        double formula = 0;
        boolean idsPath = true;
        for (int i = 0; i < ENDPOINT_POINTS; i++) {
            double x = r.nextDouble() * 200000 - 100000;
            double z = r.nextDouble() * 200000 - 100000;
            double got = engine.sample(x, z, w);
            requireFinite("endpoint id=" + id, got);
            require(Double.compare(got, engine.sample(x, z, w)) == 0, "endpoint nondeterministic id=" + id);
            double reference = cat.entry(id).reference(x, z);
            worst = Math.max(worst, Math.abs(got - reference));
            formula = Math.max(formula, Math.abs(reference - formulaOf(cat.entry(id), x, z)));
            idsPath &= Double.compare(engine.sample(x, z, new int[]{id}, new double[]{1}), got) == 0;
        }
        require(worst <= TOL_ENDPOINT, "endpoint id=" + id + " max_abs_diff=" + worst
                + (formula <= TOL_ENDPOINT ? " — catalog reference matches raw*amp+bias,"
                + " so the mismatch is in the engine, not the catalog" : ""));
        require(idsPath, "ids-path endpoint mismatch id=" + id);
        witnessMax("endpoints.max_abs_diff_vs_reference", worst);
        witnessMax("endpoints.max_abs_reference_minus_raw_amp_bias", formula);
        // LOW/复合满足 reference == raw·amp+bias；台地/劣地/山地的 raw 只是非线性算子的输入，
        // 该恒等式按构造不成立 ⇒ 只记录残差，不作判据。
        csv.add(String.format(Locale.ROOT, "%d,%d,%s,%s,%.3e,%.3e,%s,%d,%.1e",
                seed, id, cat.entry(id).kind(), formula <= TOL_ENDPOINT, worst, formula, idsPath,
                ENDPOINT_POINTS, TOL_ENDPOINT));
    }

    /** {@code catalog} 自己的端点公式：{@code raw·amplitude + bias}（clamp 条目取 max(·,0)）。 */
    private static double formulaOf(RecipeMorphCatalog.Entry e, double x, double z) {
        float fx = (float) x;
        float fz = (float) z;
        double v = e.raw(fx, fz) * e.amplitude(fx, fz) + e.bias(fx, fz);
        return e.clamped() ? Math.max(v, 0) : v;
    }

    // ================================================================ 2. 已认可双族边

    /**
     * 台地端点必须对<b>参考修正</b>口径（{@code Terrace} 上界 0..0.9），不是旧
     * {@code PlateauCandidate.REF} 的 0..1.05：故端点与旧节点的高度必须<b>显著不同</b>。
     */
    private static List<String> sectionPlateauReference() {
        List<String> csv = new ArrayList<>(List.of(
                "seed,points,min_abs_diff_vs_legacy,max_abs_diff_vs_legacy,mean_abs_diff_vs_legacy,min_required"));
        for (long seed : SEEDS) {
            RecipeMorphCatalog cat = new RecipeMorphCatalog(seed);
            RecipeJunctionMorph engine = new RecipeJunctionMorph(cat, seed);
            PlateauCandidate legacy = new PlateauCandidate(PlateauCandidate.Variant.REF);
            legacy.seed(seed + RecipeMorphCatalog.OFF_PLATEAU);
            Random r = new Random(seed + 151);
            double[] w = new double[18];
            w[15] = 1;
            double min = Double.MAX_VALUE;
            double max = 0;
            double sum = 0;
            int points = 200;
            for (int i = 0; i < points; i++) {
                double x = r.nextDouble() * 20000 - 10000;
                double z = r.nextDouble() * 20000 - 10000;
                double diff = Math.abs(engine.sample(x, z, w) - legacy.height01(x, z));
                min = Math.min(min, diff);
                max = Math.max(max, diff);
                sum += diff;
            }
            require(max > PLATEAU_LEGACY_MIN, "plateau endpoint equals the legacy 0..1.05 reference"
                    + " (max diff=" + max + ") -> terrace range correction not in effect");
            witnessMax("plateau-reference.max_abs_diff_vs_legacy", max);
            csv.add(String.format(Locale.ROOT, "%d,%d,%.3e,%.3e,%.3e,%.2f",
                    seed, points, min, max, sum / points, PLATEAU_LEGACY_MIN));
        }
        return csv;
    }

    private static List<String> sectionEdges() {
        List<String> csv = new ArrayList<>(List.of(
                "seed,edge,high_id,low_id,kind,t,recovered_t,max_abs_diff,points,tolerance"));
        for (long seed : SEEDS) {
            RecipeJunctionMorph engine = new RecipeJunctionMorph(new RecipeMorphCatalog(seed), seed);
            for (Edge edge : approvedEdges(seed)) {
                edgeGridRows(csv, engine, edge, seed);
                edgeRandomRows(csv, engine, edge, seed);
            }
        }
        return csv;
    }

    /** 10 条已认可双族边：{@code high} 拿 {@code t}，与旧实现 {@code mix(low,high,t)} 同向。 */
    private static List<Edge> approvedEdges(long seed) {
        List<Edge> edges = new ArrayList<>(10);
        edges.add(new Edge(3, 0, "PLAIN_HILLS", four(seed, FourPairMorphCandidate.Pair.PLAIN_HILLS)));
        edges.add(new Edge(17, 3, "HILLS_MOUNTAINS", four(seed, FourPairMorphCandidate.Pair.HILLS_MOUNTAINS)));
        edges.add(new Edge(17, 15, "PLATEAU_MOUNTAINS",
                four(seed, FourPairMorphCandidate.Pair.PLATEAU_MOUNTAINS)));
        edges.add(new Edge(16, 15, "PLATEAU_BADLANDS",
                four(seed, FourPairMorphCandidate.Pair.PLATEAU_BADLANDS)));
        edges.add(new Edge(15, 0, "PLAIN_PLATEAU", four(seed, FourPairMorphCandidate.Pair.PLAIN_PLATEAU)));
        edges.add(new Edge(17, 0, "PLAIN_MOUNTAINS", four(seed, FourPairMorphCandidate.Pair.PLAIN_MOUNTAINS)));
        edges.add(new Edge(16, 0, "PLAIN_BADLANDS", four(seed, FourPairMorphCandidate.Pair.PLAIN_BADLANDS)));
        edges.add(new Edge(16, 3, "HILLS_BADLANDS", four(seed, FourPairMorphCandidate.Pair.HILLS_BADLANDS)));
        edges.add(new Edge(16, 17, "MOUNTAINS_BADLANDS",
                four(seed, FourPairMorphCandidate.Pair.MOUNTAINS_BADLANDS)));
        edges.add(new Edge(15, 3, "HILLS_PLATEAU_1", new HillsPlateauMorphCandidate(seed)::sample));
        return edges;
    }

    private static OldMorph four(long seed, FourPairMorphCandidate.Pair pair) {
        return new FourPairMorphCandidate(seed, pair)::sample;
    }

    private static void edgeGridRows(List<String> csv, RecipeJunctionMorph engine, Edge edge, long seed) {
        Random r = new Random(seed ^ (edge.name().hashCode() * 0x9E3779B9L));
        for (double t : T_GRID) {
            double position = positionOf(t);
            double recovered = (float) HillsPlateauMorphCandidate.ease(position);
            Worst worst = edgeWorst(engine, edge, position, recovered, r, EDGE_POINTS);
            recordIfOver(worst, seed, edge, "grid", t, recovered, TOL_REGRESSION);
            require(worst.diff() <= TOL_REGRESSION, "edge " + edge.name() + " t=" + t + " max=" + worst.diff());
            csv.add(String.format(Locale.ROOT, "%d,%s,%d,%d,grid,%.6f,%.9f,%.3e,%d,%.1e",
                    seed, edge.name(), edge.high(), edge.low(), t, recovered, worst.diff(), EDGE_POINTS,
                    TOL_REGRESSION));
        }
    }

    private static void edgeRandomRows(List<String> csv, RecipeJunctionMorph engine, Edge edge, long seed) {
        Random r = new Random(seed + edge.name().hashCode());
        for (int case_ = 0; case_ < RANDOM_T; case_++) {
            double target = r.nextDouble();
            double position = r.nextDouble();
            double recovered = (float) HillsPlateauMorphCandidate.ease(position);
            Worst worst = edgeWorst(engine, edge, position, recovered, r, EDGE_POINTS);
            String label = String.format(Locale.ROOT, "random#%d(target=%.6f)", case_, target);
            recordIfOver(worst, seed, edge, label, position, recovered, TOL_REGRESSION);
            require(worst.diff() <= TOL_REGRESSION, "edge " + edge.name() + " " + label
                    + " max=" + worst.diff());
            csv.add(String.format(Locale.ROOT, "%d,%s,%d,%d,%s,%.6f,%.9f,%.3e,%d,%.1e",
                    seed, edge.name(), edge.high(), edge.low(), label, position, recovered, worst.diff(),
                    EDGE_POINTS, TOL_REGRESSION));
        }
    }

    /** 逐点最大差：新引擎只吃权重，旧实现只吃 position（内部自己 ease）；同时留最差点的坐标。 */
    private static Worst edgeWorst(RecipeJunctionMorph engine, Edge edge, double position,
                                   double recovered, Random r, int points) {
        Worst worst = new Worst(0, Double.NaN, Double.NaN);
        double[] w = new double[18];
        for (int i = 0; i < points; i++) {
            double x = r.nextDouble() * 200000 - 100000;
            double z = r.nextDouble() * 200000 - 100000;
            Arrays.fill(w, 0);
            w[edge.high()] = recovered;
            w[edge.low()] = 1 - recovered;
            double got = engine.sample(x, z, w);
            requireFinite("edge " + edge.name(), got);
            double diff = Math.abs(got - edge.old().at(x, z, position));
            if (diff > worst.diff()) worst = new Worst(diff, x, z);
        }
        return worst;
    }

    /**
     * 越阈即把 {@code seed, 边, 高低 id, t, 恢复出的权重, 最差点坐标, 实测差} 记入
     * {@code failures.csv}，供<b>按坐标复现</b>；不在此处放宽任何容差。
     */
    private static void recordIfOver(Worst worst, long seed, Edge edge, String kind, double t,
                                     double recovered, double tolerance) {
        witnessMax("edges.max_abs_diff[" + edge.name() + "]", worst.diff());
        witnessMax("edges.max_abs_diff_all", worst.diff());
        if (!(worst.diff() > tolerance)) return;
        FAILURES.add(String.format(Locale.ROOT, "%d,%s,%d,%d,%s,%.9f,%.17g,%.17g,%.3e,%.1e",
                seed, edge.name(), edge.high(), edge.low(), kind, recovered, worst.x(), worst.z(),
                worst.diff(), tolerance));
    }

    /** smoothstep 的解析反函数：给 {@code t} 求 {@code position}，使 {@code ease(position) == t}。 */
    static double positionOf(double t) {
        double c = Math.max(0, Math.min(1, t));
        return 0.5 - Math.sin(Math.asin(1 - 2 * c) / 3.0);
    }

    // ================================================================ 3. 旧三族回归

    private static List<String> sectionLocalTriple() {
        List<String> csv = new ArrayList<>(List.of(
                "seed,case,w_plain,w_hills,w_m1,max_abs_diff,points,tolerance"));
        for (long seed : SEEDS) {
            RecipeJunctionMorph engine = new RecipeJunctionMorph(new RecipeMorphCatalog(seed), seed);
            LocalThreeMorphCandidate three = new LocalThreeMorphCandidate(seed);
            Random r = new Random(seed + 211);
            tripleRow(csv, engine, three, seed, "thirds", new double[]{1.0 / 3, 1.0 / 3}, r);
            for (int k = 0; k < 3; k++) {
                tripleRow(csv, engine, three, seed, "random#" + k, dirichlet(r, 3), r);
            }
        }
        return csv;
    }

    private static void tripleRow(List<String> csv, RecipeJunctionMorph engine, LocalThreeMorphCandidate three,
                                  long seed, String label, double[] whm, Random r) {
        double wP = whm[0];
        double wH = whm[1];
        double wM = 1 - wP - wH;
        double[] w = new double[18];
        w[0] = wP;
        w[3] = wH;
        w[17] = wM;
        double worst = 0;
        for (int i = 0; i < TRIPLE_POINTS; i++) {
            double x = r.nextDouble() * 100000 - 50000;
            double z = r.nextDouble() * 100000 - 50000;
            double got = engine.sample(x, z, w);
            requireFinite("local-three", got);
            worst = Math.max(worst, Math.abs(got - three.sample(x, z, wP, wH, wM)));
        }
        require(worst <= TOL_REGRESSION, "local-three " + label + " max=" + worst);
        witnessMax("local-triple.max_abs_diff", worst);
        csv.add(String.format(Locale.ROOT, "%d,%s,%.9f,%.9f,%.9f,%.3e,%d,%.1e",
                seed, label, wP, wH, wM, worst, TRIPLE_POINTS, TOL_REGRESSION));
    }

    // ================================================================ 4. 全 153 边 + 三/四/五域结构

    private static List<String> sectionAllPairs() {
        List<String> csv = new ArrayList<>(List.of(
                "seed,case,ids,weights,max_abs_value,max_abs_order_diff,max_abs_split_diff,"
                        + "finite,deterministic,order_ok,split_ok"));
        for (long seed : SEEDS) {
            RecipeJunctionMorph engine = new RecipeJunctionMorph(new RecipeMorphCatalog(seed), seed);
            Random r = new Random(seed + 307);
            for (int a = 0; a < 18; a++) {
                for (int b = a + 1; b < 18; b++) structureRow(csv, engine, seed, "pair", new int[]{a, b}, r);
            }
            for (int[] ids : OTHER_TRIPLES) structureRow(csv, engine, seed, "triple", ids, r);
            for (int[] ids : QUADS) {
                structureRow(csv, engine, seed, "quad", ids, r);
                structureRandomRow(csv, engine, seed, "quad-rand", ids, r);
            }
            for (int[] ids : QUINTS) {
                structureRow(csv, engine, seed, "quint", ids, r);
                structureRandomRow(csv, engine, seed, "quint-rand", ids, r);
            }
        }
        return csv;
    }

    /**
     * 四/五域的随机 Dirichlet 权重版本：只判有限 + 置换不变（同 id 归并后逐位一致）。
     * <p>随机权重不是二进制精确，故<b>不做</b>拆分归并逐位比较 —— 那条只对 {@code 2⁻ᵏ}
     * 权重有意义（拆半后求和仍精确）。</p>
     */
    private static void structureRandomRow(List<String> csv, RecipeJunctionMorph engine, long seed,
                                           String kind, int[] ids, Random r) {
        int[] reversedIds = reversed(ids);
        double maxValue = 0;
        double orderDiff = 0;
        boolean finite = true;
        boolean deterministic = true;
        String weights = "";
        for (int i = 0; i < STRUCTURE_POINTS; i++) {
            double[] w = dirichlet(r, ids.length);
            if (i == 0) weights = joinedDoubles(w);
            double[] reversedWeights = reversed(w);
            double x = r.nextDouble() * 40000 - 20000;
            double z = r.nextDouble() * 40000 - 20000;
            double value = engine.sample(x, z, ids, w);
            finite &= Double.isFinite(value) && Math.abs(value) <= VALUE_LIMIT;
            maxValue = Math.max(maxValue, Math.abs(value));
            deterministic &= Double.compare(value, engine.sample(x, z, ids, w)) == 0;
            orderDiff = Math.max(orderDiff, Math.abs(value - engine.sample(x, z, reversedIds, reversedWeights)));
        }
        require(finite, "structure-rand non-finite " + kind + joined(ids));
        require(deterministic, "structure-rand nondeterministic " + kind + joined(ids));
        require(orderDiff == 0, "structure-rand depends on id order " + kind + joined(ids)
                + " d=" + orderDiff);
        csv.add(String.format(Locale.ROOT, "%d,%s,%s,%s,%.6f,%.3e,na,%s,%s,%s,na",
                seed, kind, joined(ids), weights, maxValue, orderDiff,
                finite, deterministic, orderDiff == 0));
    }

    /**
     * 一对/一组配方的结构判据：有限、有界、逐位确定、id 顺序无关、同配方拆分后归并逐位一致。
     * <p>权重取 {@code 0.5/0.25/0.25}（二进制精确），故"拆半"后逐分量求和仍精确相等。</p>
     */
    private static void structureRow(List<String> csv, RecipeJunctionMorph engine, long seed,
                                     String kind, int[] ids, Random r) {
        double[] unit = unitWeights(ids.length);
        double[] merged = merge(ids, unit);
        int[] reversedIds = reversed(ids);
        double[] reversedWeights = reversed(unit);
        int[] splitIds = new int[ids.length * 2];
        double[] splitWeights = new double[ids.length * 2];
        for (int i = 0; i < ids.length; i++) {
            splitIds[i] = ids[i];
            splitIds[i + ids.length] = ids[i];
            splitWeights[i] = unit[i] / 2;
            splitWeights[i + ids.length] = unit[i] / 2;
        }
        double maxValue = 0;
        double orderDiff = 0;
        double splitDiff = 0;
        boolean finite = true;
        boolean deterministic = true;
        for (int i = 0; i < STRUCTURE_POINTS; i++) {
            double x = r.nextDouble() * 40000 - 20000;
            double z = r.nextDouble() * 40000 - 20000;
            double value = engine.sample(x, z, merged);
            finite &= Double.isFinite(value) && Math.abs(value) <= VALUE_LIMIT;
            maxValue = Math.max(maxValue, Math.abs(value));
            deterministic &= Double.compare(value, engine.sample(x, z, merged)) == 0;
            orderDiff = Math.max(orderDiff, Math.abs(value - engine.sample(x, z, reversedIds, reversedWeights)));
            splitDiff = Math.max(splitDiff, Math.abs(value - engine.sample(x, z, splitIds, splitWeights)));
        }
        require(finite, "structure non-finite " + kind + Arrays.toString(ids));
        require(deterministic, "structure nondeterministic " + kind + Arrays.toString(ids));
        require(orderDiff == 0, "structure depends on id order " + Arrays.toString(ids) + " d=" + orderDiff);
        require(splitDiff == 0, "structure split-merge differs " + Arrays.toString(ids) + " d=" + splitDiff);
        witnessMax("structure.max_abs_value", maxValue);
        witnessMax("structure.max_abs_order_diff", orderDiff);
        witnessMax("structure.max_abs_split_diff", splitDiff);
        csv.add(String.format(Locale.ROOT, "%d,%s,%s,%s,%.6f,%.3e,%.3e,%s,%s,%s,%s",
                seed, kind, joined(ids), joinedDoubles(unit), maxValue, orderDiff, splitDiff,
                finite, deterministic, orderDiff == 0, splitDiff == 0));
    }

    /**
     * 二进制精确的等价权重：{@code 1/n} 只在 {@code n} 为 2 的幂时精确，故按
     * <b>2 的负幂</b>取 {@code 2⁻ᵏ} 且和为 1。
     *
     * <p>{@code n=2 → 0.5/0.5}；{@code n=3 → 0.5/0.25/0.25}；{@code n=4 → 0.25×4}；
     * {@code n=5 → 0.25/0.1875/0.1875/0.1875/0.1875}。全部是 2 的负幂，拆半后逐分量求和
     * 仍<b>精确</b>相等，故 {@code split_ok} 才能要求逐位一致。</p>
     */
    private static double[] unitWeights(int n) {
        double[] w = new double[n];
        switch (n) {
            case 2 -> {
                w[0] = 0.5;
                w[1] = 0.5;
            }
            case 3 -> {
                w[0] = 0.5;
                w[1] = 0.25;
                w[2] = 0.25;
            }
            case 4 -> {
                for (int i = 0; i < 4; i++) w[i] = 0.25;
            }
            case 5 -> {
                w[0] = 0.25;
                for (int i = 1; i < 5; i++) w[i] = 0.1875;
            }
            default -> throw new IllegalArgumentException("unsupported arity " + n);
        }
        return w;
    }

    /** CSV 里的权重列表：{@code |} 分隔，不含逗号（同 {@link #joined}）。 */
    private static String joinedDoubles(double[] values) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append('|');
            sb.append(String.format(Locale.ROOT, "%.6f", values[i]));
        }
        return sb.toString();
    }

    // ================================================================ 5. 五大类 10 个三组

    private static List<String> sectionTriangles() {
        List<String> csv = new ArrayList<>(List.of(
                "seed,triple,vertex,epsilon,max_abs_diff_vs_vertex_reference,ratio_to_previous,"
                        + "points,epsilon_limit"));
        for (long seed : SEEDS) {
            RecipeMorphCatalog cat = new RecipeMorphCatalog(seed);
            RecipeJunctionMorph engine = new RecipeJunctionMorph(cat, seed);
            for (int[] triple : CLASS_TRIPLES) triangleRows(csv, engine, cat, seed, triple);
        }
        return csv;
    }

    private static List<String> sectionTriangleInterior() {
        List<String> csv = new ArrayList<>(List.of(
                "seed,triple,points,min_weight,value_min,value_max,max_abs_value,deterministic"));
        for (long seed : SEEDS) {
            RecipeMorphCatalog cat = new RecipeMorphCatalog(seed);
            RecipeJunctionMorph engine = new RecipeJunctionMorph(cat, seed);
            for (int[] triple : CLASS_TRIPLES) {
                interiorRow(csv, engine, seed, triple, new Random(seed + 401 + Arrays.hashCode(triple)));
            }
        }
        return csv;
    }

    /** 五大类代表配方（平原 0 / 丘陵 hills1 3 / 台地 15 / 劣地 16 / 山地 m1 17）的 10 个无序三组。 */
    private static int[][] classTriples() {
        int[] reps = {0, 3, 15, 16, 17};
        List<int[]> list = new ArrayList<>(10);
        for (int i = 0; i < reps.length; i++) {
            for (int j = i + 1; j < reps.length; j++) {
                for (int k = j + 1; k < reps.length; k++) list.add(new int[]{reps[i], reps[j], reps[k]});
            }
        }
        return list.toArray(new int[0][]);
    }

    private static void triangleRows(List<String> csv, RecipeJunctionMorph engine, RecipeMorphCatalog cat,
                                     long seed, int[] triple) {
        String name = joined(triple);
        double[][] points = pointSet(new Random(seed + 401 + Arrays.hashCode(triple)), SWEEP_POINTS);
        for (int vertex = 0; vertex < 3; vertex++) {
            epsilonRows(csv, engine, cat, seed, triple, name, vertex, points);
        }
    }

    // ================================================================ 5b. 三族→双边退化

    /**
     * 三族权重里<b>一项趋 0</b> 时必须退回该边的双族结果。
     *
     * <p>对每个三组的每条边（{@code drop} = 被压到 0 的那一族），在<b>同一固定点集</b>上比：
     * 三族引擎取 {@code (wLow, 1−wLow−ε, ε)}，双族基准取把 {@code ε} 归并进低族后的
     * <b>renormalized 双权重</b> {@code (wLow, 1−wLow)}。<b>两个臂都过同一个引擎</b>，
     * 故这是"同一引擎的参数退化"，<b>不是</b>严格几何连续性证明。</p>
     *
     * <p>只要求 ε 终值 {@code ≤ 1e-3} 且不放大（中点带宽因 node cliff 可能非逐点单调，
     * 不强制每次严格递减）。</p>
     */
    private static List<String> sectionEdgeCollapse() {
        List<String> csv = new ArrayList<>(List.of(
                "seed,triple,drop_id,keep_low_id,keep_high_id,epsilon,max_abs_diff,ratio_to_previous,"
                        + "points,epsilon_limit"));
        for (long seed : SEEDS) {
            RecipeJunctionMorph engine = new RecipeJunctionMorph(new RecipeMorphCatalog(seed), seed);
            for (int[] triple : CLASS_TRIPLES) edgeCollapseRows(csv, engine, seed, triple);
        }
        return csv;
    }

    private static void edgeCollapseRows(List<String> csv, RecipeJunctionMorph engine, long seed, int[] triple) {
        String name = joined(triple);
        double[][] points = pointSet(new Random(seed + 601 + Arrays.hashCode(triple)), EDGE_EPS_POINTS);
        for (int drop = 0; drop < 3; drop++) {
            int dropId = triple[drop];
            int lowId = triple[(drop + 1) % 3];
            int highId = triple[(drop + 2) % 3];
            double previous = Double.NaN;
            double last = Double.NaN;
            for (double eps : EDGE_EPSILONS) {
                // 三族臂：low/high 保持 1:1 的 (CROSS_PAIR_LOW : 1−CROSS_PAIR_LOW) 比例，
                // 第三族分到 ε，整体再按 (1+ε) 归一 ⇒ 权重和恒为 1。
                double[] tripleW = new double[18];
                tripleW[lowId] = CROSS_PAIR_LOW / (1 + eps);
                tripleW[highId] = (1 - CROSS_PAIR_LOW) / (1 + eps);
                tripleW[dropId] = eps / (1 + eps);
                // 双边臂：第三族**权重恒为 0**，另两族仍是同一比例、和恰为 1。
                // ★ 消失族的质量必须<不>转移给幸存族；首版把 1−low−eps 全塞给 highId，
                //   等于把 0.7 的 plain 变成 0.7 的 plateau ⇒ 残差恒 0.2+，是构造错不是引擎错。
                double[] pairW = new double[18];
                pairW[lowId] = CROSS_PAIR_LOW;
                pairW[highId] = 1 - CROSS_PAIR_LOW;
                double worst = collapseWorst(engine, points, tripleW, pairW);
                csv.add(String.format(Locale.ROOT, "%d,%s,%d,%d,%d,%.0e,%.3e,%s,%d,%.1e",
                        seed, name, dropId, lowId, highId, eps, worst,
                        Double.isNaN(previous) ? "na" : String.format(Locale.ROOT, "%.3f", worst / previous),
                        points.length, EDGE_EPS_LIMIT));
                if (eps == EDGE_EPSILONS[EDGE_EPSILONS.length - 1]) {
                    // 只有最后一档才算"到极限"；第一档 1e-2 本来就该是 1e-2 量级。
                    witnessMax("edge-collapse.max_abs_diff_final", worst);
                }
                previous = worst;
                last = worst;
            }
            require(last <= EDGE_EPS_LIMIT, "edge collapse too loose " + name + " drop=" + dropId
                    + " -> " + last);
        }
    }

    private static double collapseWorst(RecipeJunctionMorph engine, double[][] points,
                                        double[] tripleW, double[] pairW) {
        double worst = 0;
        for (double[] p : points) {
            double a = engine.sample(p[0], p[1], tripleW);
            double b = engine.sample(p[0], p[1], pairW);
            requireFinite("edge collapse", a);
            worst = Math.max(worst, Math.abs(a - b));
        }
        return worst;
    }

    /** 逐顶点 ε 递减：{@code w_v=1−2ε}，另两族各 {@code ε}；端点目标取 catalog reference。 */
    private static void epsilonRows(List<String> csv, RecipeJunctionMorph engine, RecipeMorphCatalog cat,
                                    long seed, int[] triple, String name, int vertex, double[][] points) {
        double previous = Double.NaN;
        double last = Double.NaN;
        for (double eps : EPSILONS) {
            double[] w = new double[18];
            w[triple[vertex]] = 1 - 2 * eps;
            w[triple[(vertex + 1) % 3]] = eps;
            w[triple[(vertex + 2) % 3]] = eps;
            double worst = 0;
            for (double[] p : points) {
                double got = engine.sample(p[0], p[1], w);
                require(Double.isFinite(got) && Math.abs(got) <= VALUE_LIMIT, "epsilon non-finite " + name);
                worst = Math.max(worst, Math.abs(got - cat.entry(triple[vertex]).reference(p[0], p[1])));
            }
            if (!Double.isNaN(previous)) {
                require(worst <= previous, "epsilon did not tighten " + name + " vertex=" + vertex
                        + " eps=" + eps + " worst=" + worst + " previous=" + previous);
            }
            csv.add(String.format(Locale.ROOT, "%d,%s,%d,%.0e,%.3e,%s,%d,%.1e",
                    seed, name, vertex, eps, worst,
                    Double.isNaN(previous) ? "na" : String.format(Locale.ROOT, "%.4f", worst / previous),
                    points.length, EPSILON_LIMIT));
            previous = worst;
            last = worst;
            // 只记 ε 阶梯末级（容差 EPSILON_LIMIT 就是对该级的判据）；前几级的最大值见 CSV。
            if (eps == EPSILONS[EPSILONS.length - 1]) {
                witnessMax("triangles-epsilon.max_abs_diff_at_epsilon_limit", worst);
            }
        }
        require(last <= EPSILON_LIMIT, "epsilon limit too loose " + name + " vertex=" + vertex + " -> " + last);
    }

    /** 内部点：Dirichlet 全正权重，只判有限/确定性；⚠ 逐点抽样，不构成全域连续性证明。 */
    private static void interiorRow(List<String> csv, RecipeJunctionMorph engine, long seed,
                                    int[] triple, Random r) {
        String name = joined(triple);
        double min = 1;
        double minValue = Double.MAX_VALUE;
        double maxValue = -Double.MAX_VALUE;
        boolean deterministic = true;
        for (int i = 0; i < INTERIOR_POINTS; i++) {
            double[] d = dirichlet(r, 3);
            double[] w = new double[18];
            w[triple[0]] = d[0];
            w[triple[1]] = d[1];
            w[triple[2]] = d[2];
            double x = r.nextDouble() * 20000 - 10000;
            double z = r.nextDouble() * 20000 - 10000;
            double got = engine.sample(x, z, w);
            requireFinite("interior " + name, got);
            deterministic &= Double.compare(got, engine.sample(x, z, w)) == 0;
            min = Math.min(min, Math.min(d[0], Math.min(d[1], d[2])));
            minValue = Math.min(minValue, got);
            maxValue = Math.max(maxValue, got);
        }
        require(deterministic, "interior nondeterministic " + name);
        witnessMax("triangles-interior.max_abs_value", Math.max(Math.abs(minValue), Math.abs(maxValue)));
        witnessMin("triangles-interior.min_weight", min);
        csv.add(String.format(Locale.ROOT, "%d,%s,%d,%.4f,%.4f,%.4f,%.4f,%s",
                seed, name, INTERIOR_POINTS, min, minValue, maxValue,
                Math.max(Math.abs(minValue), Math.abs(maxValue)), deterministic));
    }

    // ================================================================ 6. 负控

    private static List<String> sectionNegativeControl() {
        List<String> csv = new ArrayList<>(List.of(
                "seed,control,max_abs_diff,mean_abs_diff,points,min_required"));
        for (long seed : SEEDS) {
            RecipeMorphCatalog cat = new RecipeMorphCatalog(seed);
            RecipeJunctionMorph engine = new RecipeJunctionMorph(cat, seed);
            Random r = new Random(seed + 503);
            for (Edge edge : approvedEdges(seed)) {
                averageControlRow(csv, engine, cat, seed, edge, r);
            }
            doubleEaseRows(csv, engine, seed, r);
        }
        return csv;
    }

    /**
     * 负控 A：内部点必须与"两族<b>成品端点</b>的加权平均"显著不同 ⇒ 不是成品 lerp，
     * 复合项（域扭曲 / 阶地 / 侵蚀 / 振幅混合）在混合<b>之前</b>生效。
     */
    private static void averageControlRow(List<String> csv, RecipeJunctionMorph engine, RecipeMorphCatalog cat,
                                          long seed, Edge edge, Random r) {
        double max = 0;
        double sum = 0;
        int points = 100;
        for (int i = 0; i < points; i++) {
            double t = r.nextDouble();
            double x = r.nextDouble() * 20000 - 10000;
            double z = r.nextDouble() * 20000 - 10000;
            double[] w = new double[18];
            w[edge.high()] = t;
            w[edge.low()] = 1 - t;
            double average = t * cat.entry(edge.high()).reference(x, z)
                    + (1 - t) * cat.entry(edge.low()).reference(x, z);
            double diff = Math.abs(engine.sample(x, z, w) - average);
            max = Math.max(max, diff);
            sum += diff;
        }
        require(max > NEGATIVE_CONTROL_MIN, "interior equals finished-height average for " + edge.name()
                + " (max=" + max + ") -> composite looks like a finished lerp");
        witnessMin("negative-control.average_of_finished.max_abs_diff", max);
        csv.add(String.format(Locale.ROOT, "%d,average_of_finished/%s,%.3e,%.3e,%d,%.2f",
                seed, edge.name(), max, sum / points, points, NEGATIVE_CONTROL_MIN));
    }

    /**
     * 负控 B：权重已 smoothstep、本类不再 ease。
     * <p>{@code ease} 在 {@code (0,1)} 内只有 {@code t=0.5} 一个定点：那里<b>必须</b>相等（正控），
     * 其余 t 处若相等即说明被二次 ease。</p>
     */
    private static void doubleEaseRows(List<String> csv, RecipeJunctionMorph engine, long seed, Random r) {
        double[] probes = {0.2, 0.3, 0.5};
        for (double t : probes) {
            double e = HillsPlateauMorphCandidate.ease(t);
            boolean fixed = Math.abs(e - t) < 1e-12;
            double worst = 0;
            for (int i = 0; i < 100; i++) {
                double x = r.nextDouble() * 20000 - 10000;
                double z = r.nextDouble() * 20000 - 10000;
                double[] direct = new double[18];
                direct[3] = t;
                direct[0] = 1 - t;
                double[] eased = new double[18];
                eased[3] = e;
                eased[0] = 1 - e;
                worst = Math.max(worst, Math.abs(engine.sample(x, z, direct) - engine.sample(x, z, eased)));
            }
            if (fixed) require(worst < 1e-12, "fixed point t=" + t + " must be identical, got " + worst);
            else require(worst > 1e-9, "weights look re-eased at t=" + t + " (diff=" + worst + ")");
            if (fixed) witnessMax("negative-control.double_ease.fixed_point_abs_diff", worst);
            else witnessMin("negative-control.double_ease.min_abs_diff_non_fixed", worst);
            csv.add(String.format(Locale.ROOT, "%d,double_ease_t%.3f,%.3e,%.3e,100,%s",
                    seed, t, worst, worst, fixed ? "must_be_zero" : "must_exceed_1e-9"));
        }
    }

    // ================================================================ 7. 契约拒绝

    private static List<String> sectionRejects() {
        List<String> csv = new ArrayList<>(List.of("case,threw,expected"));
        RecipeJunctionMorph engine = new RecipeJunctionMorph(new RecipeMorphCatalog(SEEDS[0]), SEEDS[0]);
        double[] ok = new double[18];
        ok[0] = 0.5;
        ok[3] = 0.5;
        reject(csv, "length17", () -> engine.sample(0, 0, Arrays.copyOf(ok, 17)));
        reject(csv, "length19", () -> engine.sample(0, 0, Arrays.copyOf(ok, 19)));
        reject(csv, "sum_off_plus", () -> engine.sample(0, 0, offset(ok, 0, 1e-6)));
        reject(csv, "sum_off_minus", () -> engine.sample(0, 0, offset(ok, 0, -1e-6)));
        reject(csv, "negative", () -> engine.sample(0, 0, weight(ok, 0, -0.5, 3, 1.5)));
        reject(csv, "over_one", () -> engine.sample(0, 0, weight(ok, 0, 1.5, 3, -0.5)));
        reject(csv, "nan", () -> engine.sample(0, 0, weight(ok, 0, Double.NaN, 3, 1.0)));
        reject(csv, "infinite", () -> engine.sample(0, 0,
                weight(ok, 0, Double.POSITIVE_INFINITY, 3, Double.NEGATIVE_INFINITY)));
        reject(csv, "ids_length_mismatch", () -> engine.sample(0, 0, new int[]{0}, new double[]{0.5, 0.5}));
        reject(csv, "ids_empty", () -> engine.sample(0, 0, new int[0], new double[0]));
        reject(csv, "id_negative", () -> engine.sample(0, 0, new int[]{-1}, new double[]{1.0}));
        reject(csv, "id_out_of_range", () -> engine.sample(0, 0, new int[]{18}, new double[]{1.0}));
        reject(csv, "ids_negative_weight", () -> engine.sample(0, 0, new int[]{0, 3}, new double[]{-0.5, 1.5}));
        reject(csv, "ids_sum_off", () -> engine.sample(0, 0, new int[]{0, 3}, new double[]{0.6, 0.6}));
        reject(csv, "ids_nan", () -> engine.sample(0, 0, new int[]{0, 3}, new double[]{Double.NaN, 1.0}));
        csv.add("valid_control,false,false");
        return csv;
    }

    private static double[] offset(double[] source, int index, double delta) {
        double[] copy = source.clone();
        copy[index] += delta;
        return copy;
    }

    private static double[] weight(double[] source, int index, double value, int other, double otherValue) {
        double[] copy = source.clone();
        copy[index] = value;
        copy[other] = otherValue;
        return copy;
    }

    private static void reject(List<String> csv, String name, Runnable action) {
        boolean thrown = false;
        try {
            action.run();
        } catch (IllegalArgumentException expected) {
            thrown = true;
        }
        csv.add(name + "," + thrown + ",IllegalArgumentException");
        require(thrown, "negative control '" + name + "' did not throw");
    }

    // ================================================================ 工具

    private static double[][] pointSet(Random r, int count) {
        double[][] points = new double[count][];
        for (int i = 0; i < count; i++) {
            points[i] = new double[]{r.nextDouble() * 4000 - 2000, r.nextDouble() * 4000 - 2000};
        }
        return points;
    }

    /** Dirichlet(1,…,1) 内点：所有分量恒为正；末分量扣前项以保证和精确为 1。 */
    private static double[] dirichlet(Random r, int n) {
        double[] raw = new double[n];
        double sum = 0;
        for (int i = 0; i < n; i++) {
            raw[i] = -Math.log(1 - r.nextDouble());
            sum += raw[i];
        }
        double[] w = new double[n];
        double used = 0;
        for (int i = 0; i < n - 1; i++) {
            w[i] = raw[i] / sum;
            used += w[i];
        }
        w[n - 1] = 1 - used;
        return w;
    }

    private static double[] merge(int[] ids, double[] weights) {
        double[] merged = new double[18];
        for (int i = 0; i < ids.length; i++) merged[ids[i]] += weights[i];
        return merged;
    }

    /**
     * CSV 里的配方 id 列表：用 {@code |} 分隔，<b>不含逗号</b>。
     * <p>{@code Arrays.toString} 会输出 {@code [0, 3, 17]} —— 三个逗号把列数撑裂，
     * 后面所有数值列都会错位（实测 474 行全部错位）。</p>
     */
    private static String joined(int[] ids) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < ids.length; i++) {
            if (i > 0) sb.append('|');
            sb.append(ids[i]);
        }
        return sb.toString();
    }

    private static int[] reversed(int[] ids) {
        int[] out = new int[ids.length];
        for (int i = 0; i < ids.length; i++) out[i] = ids[ids.length - 1 - i];
        return out;
    }

    private static double[] reversed(double[] weights) {
        double[] out = new double[weights.length];
        for (int i = 0; i < weights.length; i++) out[i] = weights[weights.length - 1 - i];
        return out;
    }

    private static void write(Path out, String name, List<String> lines) throws Exception {
        Files.writeString(out.resolve(name), String.join("\n", lines) + "\n");
    }

    /**
     * 复现口径。证明"只有权重驱动新引擎"与"旧实现只吃 position"这两点在本机是同一份字节码：
     * 本类不缓存、不 fork、不写共享状态，同参数重跑逐位相同（见 {@code endpoints.csv} 的 ids 路径列）。
     */
    private static List<String> repro() {
        return List.of(
                "entry=com.geogenesis.worldgen.terrain.candidate.RecipeJunctionMorphCheck <outDir>",
                "compile=javac --release 17 -encoding UTF-8 -nowarn -cp build/classes/java/main"
                        + " -d build/terrain-upgrade/recipe-junctions/check-classes <the 15 sources:"
                        + " RefNoise, RegionCellTable, RegionPartition, Plains/Hills/Mountains/"
                        + "Plateau/Badlands/Combine/FourPairMorph/HillsPlateauMorph/LocalThreeMorph"
                        + " candidates, RecipeMorphCatalog, RecipeJunctionMorph, this class>",
                "run_cp_order=check-classes;<aux dir with LandCompositionGallery for the seed constant>"
                        + ";build/classes/java/main;night-config core",
                "run=java -Xmx2g -Dfile.encoding=UTF-8 -cp <run_cp_order>"
                        + " com.geogenesis.worldgen.terrain.candidate.RecipeJunctionMorphCheck"
                        + " build/terrain-upgrade/recipe-junctions/check",
                "engine_input=weights only (double[18], already eased); old implementations get position only",
                "no_cache=every sample is a fresh node evaluation; no shared or static mutable state",
                "outputs=endpoints/edges/local-triple/structure-all-pairs/triangles-epsilon/"
                        + "triangles-interior/negative-control/rejects/plateau-reference/"
                        + "witness/failures/metrics/limits",
                "csv=comma separated, no embedded commas; recipe id lists use '|'",
                "witness=every metric row names the CSV it came from, its tolerance and its verdict");
    }

    private static void requireFinite(String label, double value) {
        require(Double.isFinite(value) && Math.abs(value) <= VALUE_LIMIT, label + " out of range: " + value);
    }

    private static void require(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }

    /** 记最大值（首见时初始化）——{@link #WITNESS} 的单调上界。 */
    private static void witnessMax(String key, double value) {
        WITNESS.merge(key, new double[]{value, 1}, (cur, add) -> {
            cur[0] = Math.max(cur[0], add[0]);
            return cur;
        });
    }

    /** 记最小值——{@link #WITNESS} 的单调下界。 */
    private static void witnessMin(String key, double value) {
        WITNESS.merge(key, new double[]{value, 0}, (cur, add) -> {
            cur[0] = Math.min(cur[0], add[0]);
            return cur;
        });
    }

    /** {@code witness.csv}：每行一件实测极值 + 它对应的判据文件与容差，便于逐项追溯。 */
    private static List<String> sectionWitness() {
        List<String> csv = new ArrayList<>(List.of("metric,value,kind,csv_file,tolerance,verdict"));
        for (Map.Entry<String, double[]> e : WITNESS.entrySet()) {
            String key = e.getKey();
            double value = e.getValue()[0];
            String tolerance = toleranceOf(key);
            csv.add(String.format(Locale.ROOT, "%s,%.6e,%s,%s,%s,%s",
                    key, value, e.getValue()[1] == 1 ? "max" : "min", csvFileOf(key), tolerance,
                    verdictOf(key, value)));
        }
        return csv;
    }

    /** 该极值写在哪份 CSV 里（键前缀 → 文件名，不是"前缀 + .csv"）。 */
    private static String csvFileOf(String key) {
        if (key.startsWith("structure.")) return "structure-all-pairs.csv";
        if (key.startsWith("triangles-epsilon.")) return "triangles-epsilon.csv";
        if (key.startsWith("edge-collapse.")) return "triangles-edge-collapse.csv";
        if (key.startsWith("triangles-interior.")) return "triangles-interior.csv";
        if (key.startsWith("negative-control.")) return "negative-control.csv";
        if (key.startsWith("plateau-reference.")) return "plateau-reference.csv";
        if (key.startsWith("local-triple.")) return "local-triple.csv";
        if (key.startsWith("endpoints.")) return "endpoints.csv";
        if (key.startsWith("edges.")) return "edges.csv";
        return "unknown";
    }

    /** 该极值对应的判据容差（与各 section 里 {@code require} 用的是同一常量）。 */
    private static String toleranceOf(String key) {
        if (key.startsWith("endpoints.max_abs_diff")) return String.format(Locale.ROOT, "<=%.1e", TOL_ENDPOINT);
        if (key.startsWith("endpoints.max_abs_reference")) return "reported_only";
        if (key.startsWith("plateau-reference.")) return String.format(Locale.ROOT, ">%.2f", PLATEAU_LEGACY_MIN);
        if (key.startsWith("edges.")) return String.format(Locale.ROOT, "<=%.1e", TOL_REGRESSION);
        if (key.startsWith("local-triple.")) return String.format(Locale.ROOT, "<=%.1e", TOL_REGRESSION);
        if (key.startsWith("structure.max_abs_order") || key.startsWith("structure.max_abs_split")) return "==0";
        if (key.startsWith("structure.max_abs_value")) return String.format(Locale.ROOT, "<=%.1f", VALUE_LIMIT);
        if (key.startsWith("triangles-epsilon.")) return String.format(Locale.ROOT, "<=%.1e", EPSILON_LIMIT);
        if (key.startsWith("triangles-interior.min_weight")) return ">0";
        if (key.startsWith("triangles-interior.max_abs_value")) return String.format(Locale.ROOT, "<=%.1f", VALUE_LIMIT);
        if (key.startsWith("edge-collapse.")) return String.format(Locale.ROOT, "<=%.1e", EDGE_EPS_LIMIT);
        if (key.contains("average_of_finished")) return String.format(Locale.ROOT, ">%.2f", NEGATIVE_CONTROL_MIN);
        if (key.contains("fixed_point")) return "<1e-12";
        return ">1e-9";
    }

    private static String verdictOf(String key, double value) {
        if (key.startsWith("endpoints.max_abs_reference")) return "informational";
        if (key.contains("fixed_point")) return value < 1e-12 ? "ok" : "FAIL";
        if (key.startsWith("structure.max_abs_order") || key.startsWith("structure.max_abs_split")) {
            return value == 0 ? "ok" : "FAIL";
        }
        if (key.startsWith("triangles-interior.min_weight")) return value > 0 ? "ok" : "FAIL";
        if (key.startsWith("triangles-epsilon.")) return value <= EPSILON_LIMIT ? "ok" : "FAIL";
        if (key.startsWith("edge-collapse.")) return value <= EDGE_EPS_LIMIT ? "ok" : "FAIL";
        if (key.startsWith("edges.") || key.startsWith("local-triple.")) {
            return value <= TOL_REGRESSION ? "ok" : "FAIL";
        }
        if (key.startsWith("endpoints.max_abs_diff")) return value <= TOL_ENDPOINT ? "ok" : "FAIL";
        if (key.startsWith("plateau-reference.")) return value > PLATEAU_LEGACY_MIN ? "ok" : "FAIL";
        if (key.contains("average_of_finished")) return value > NEGATIVE_CONTROL_MIN ? "ok" : "FAIL";
        if (key.contains("non_fixed")) return value > 1e-9 ? "ok" : "FAIL";
        return value <= VALUE_LIMIT ? "ok" : "FAIL";
    }

    private static String metrics() {
        return String.format(Locale.ROOT,
                "checks=%d%nseeds=%s%nendpoint_points=%d%nedge_points=%d%nlocal_three_points=%d%n"
                        + "structure_points=%d%nepsilon_points=%d%ninterior_points_per_triple=%d%n"
                        + "tolerance_endpoint=%.1e%ntolerance_regression=%.1e%n"
                        + "epsilon_ladder=%s%nepsilon_limit=%.1e%nnegative_control_min=%.2f%n"
                        + "plateau_legacy_min=%.2f%n"
                        + "value_limit=%.1f%nclass_triples=%d%nall_pairs=153%nwitness_metrics=%d%n"
                        + "edge_collapse_ladder=%s%nedge_collapse_limit=%.1e%nquads=%d%nquints=%d%n"
                        + "endpoint_target=catalog.entry(id).reference (plateau id 15 = reference-corrected, "
                        + "not PlateauCandidate.REF 1.05)%n",
                checks, Arrays.toString(SEEDS), ENDPOINT_POINTS, EDGE_POINTS, TRIPLE_POINTS,
                STRUCTURE_POINTS, SWEEP_POINTS, INTERIOR_POINTS, TOL_ENDPOINT, TOL_REGRESSION,
                Arrays.toString(EPSILONS), EPSILON_LIMIT, NEGATIVE_CONTROL_MIN, PLATEAU_LEGACY_MIN,
                VALUE_LIMIT, CLASS_TRIPLES.length, WITNESS.size(), Arrays.toString(EDGE_EPSILONS),
                EDGE_EPS_LIMIT, QUADS.length, QUINTS.length);
    }

    private static final String LIMITS =
            "coverage=18 catalog endpoints, the 10 approved two-family edges, the old 3-family "
                    + "LocalThree triangle, all C(18,2)=153 edges, five extra triples, "
                    + "10 unordered triples over the five classes, plus 3 quads and 2 quints "
                    + "(0,1,4,15 / 0,3,16,17 / 3,15,16,17 and 5,9,15,16,17 / 0,3,15,16,17) with both "
                    + "binary-exact and random Dirichlet weights, plus the 3-family-to-two-family "
                    + "edge collapse ladder\n"
                    + "proven=endpoint equality vs catalog reference (the plateau endpoint is checked "
                    + "against the reference-corrected terrace range, not the legacy 0..1.05 node), "
                    + "edge/vertex numerical reduction against FourPairMorphCandidate and "
                    + "HillsPlateauMorphCandidate, order-independent recipe-id merging, single "
                    + "consumption of already-eased weights, strict weight/id contract, composite "
                    + "interior not equal to a finished-height lerp, and that dropping one of three "
                    + "families to epsilon<=1e-4 reduces to the renormalized two-family result to "
                    + "<=1e-3\n"
                    + "not_proven=global continuity (every epsilon ladder is pointwise sampling only), "
                    + "visual acceptability, junction rendering, chunk-generation cost, full-map wiring\n"
                    + "LIMITATION=edge collapse and vertex epsilon sweeps both drive the SAME engine, so "
                    + "they demonstrate parameter degeneracy (one weight -> 0), NOT a strict geometric "
                    + "continuity or a C0/C1 proof across node cliffs; a node cliff (Terrace index / "
                    + "Steps band change) can move discontinuously in position even when the weights "
                    + "vary continuously\n"
                    + "LIMITATION=the edges section compares against the old two-family implementations "
                    + "on a fixed t grid plus 3 random t per edge; it samples, it does not sweep\n"
                    + "note=structure checks use 2^-k weights (0.5/0.5, 0.5/0.25/0.25, 0.25x4, "
                    + "0.25+0.1875x4) so that binary-exact splitting keeps the split-merge comparison "
                    + "bitwise; the random-Dirichlet rows skip split_ok for that reason\n"
                    + "note=random t rows use an independent Random per edge; the reported target column "
                    + "is informational, the compared value is the recovered float weight\n"
                    + "note=endpoint identity reference==raw*amp+bias only holds for LOW/composite entries; "
                    + "PLATEAU/BADLANDS/MOUNTAIN raw is the input of a nonlinear operator, so that residual "
                    + "is reported but not asserted\n";
}
