package com.geogenesis.worldgen.terrain.candidate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * <b>概念移植的门禁</b>（方案 {@code ocean-unified-terrain} 步 4）。
 *
 * <h3>移植的判据是"语义等价"，不是"数值等价"</h3>
 * <p>结构变了（旧链以 {@code edge} 为主坐标 + 三段拼接；新架构是票槽池成员 + 单一公式），
 * 逐位相同<b>做不到也不该追求</b>。所以本探针分两组判据：</p>
 *
 * <h3>第一组：概念的不变式（必须成立）</h3>
 * <pre>
 * M1 cliff-endpoints    shoreShape(0, c) == 0 且 shoreShape(1, c) == 1，对任意 c
 * M2 cliff-monotonic    对任意固定 c，shoreShape 在 t 上单调不减
 * M3 cliff-moves-shape  cliff=1 与 cliff=0 的 shape 在 t=0/1 处相同、中段不同
 *                       （证明「改 cliff 只改坡形、不动岸线」这条 C3 不变式）
 * M4 width-growth       shoreWidthFactor 随 landBlocks 单调升，且落在 [1, 1.45]
 * M5 gate-needs-both    tectonicGate 在「只满足 stress」或「只满足 dist」时都 ≈ 0
 * M6 gate-peak          stress<0 且 dist=0 时 gate ≈ 1
 * M7 gate-symmetric     把 stress 反号（汇聚）时 gate == 0（脊不该出现在汇聚边界）
 * </pre>
 *
 * <h3>第二组：移植是否真的改变了产出（单变量对照）</h3>
 * <pre>
 * M8 slope-differs      概念版 CONTINENTAL_SLOPE 与纯坡版的分布必须不同
 * M9 ridge-differs      概念版 MID_OCEAN_RIDGE 与纯带状版的分布必须不同
 * M10 still-in-band     两者仍必须落在声明 depthRange 内（移植不得破坏深度契约）
 * M11 still-no-island   两者仍必须恒 ≤ 0
 * </pre>
 *
 * <p><b>M8/M9 是"移植有没有落地"的证据</b>：若两版逐位相同，说明概念根本没接进去
 * （或接了但参数被抹平）。</p>
 */
public final class OceanMorphConceptsCheck {

    private static final long SEED = 9139912035078620160L;
    private static final int HALF = 4096;
    private static final int STEP = 32;
    /** 概念版与基线版的最小可辨差异（e 单位）。≈1.3 格。 */
    private static final double MIN_CONCEPT_DIFF = 0.01;

    private static final StringBuilder csv = new StringBuilder();
    private static int checks;

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Path out = Path.of(args.length > 0 ? args[0] : "build/ocean-concepts-check");
        Files.createDirectories(out);

        csv.append("check,metric,value,verdict\n");

        // ---------------- 第一组：概念不变式
        checkCliffEndpoints();
        checkCliffMonotonic();
        checkCliffMovesShape();
        checkWidthGrowth();
        checkGate();

        // ---------------- 第二组：单变量对照
        long catSeed = SEED + OceanRecipeCatalog.SEED_OFFSET;
        OceanRecipeCatalog plain = new OceanRecipeCatalog(catSeed, false);
        OceanRecipeCatalog concept = new OceanRecipeCatalog(catSeed, true);

        record("M0", "plain-conceptMorph", plain.conceptMorph() ? 1 : 0, !plain.conceptMorph(),
                "基线实例必须 conceptMorph=false");
        record("M0", "concept-conceptMorph", concept.conceptMorph() ? 1 : 0, concept.conceptMorph(),
                "概念实例必须 conceptMorph=true");

        // ⚠ M8（陆坡单变量对照）已移除：CONTINENTAL_SLOPE 已从内部类移到过渡类，
        //    由 OceanTransitionCatalog 的 ABYSSAL_PLAIN × SHELF_RIDGE 复合生成。
        //    替代判据见 OceanTransitionCheck 的 T2（复合陆坡中位 vs 手写值）。
        //    C1/C2/C3 三个概念现在只作为参数语义参考（cliffFactor / shoreWidthFactor /
        //    shoreProfile 的不变式仍由 M1–M4 验证）。
        compareRecipe(plain, concept, OceanRecipeCatalog.Recipe.MID_OCEAN_RIDGE,
                "M9", "ridge-differs");

        Files.writeString(out.resolve("checks.csv"), csv.toString());
        System.out.println("PASS checks=" + checks + " out=" + out.toAbsolutePath());
    }

    // ---------------------------------------------------------------- M1~M3

    /** M1：端点条件 —— 任意 cliff 下 shape(0)=0、shape(1)=1。 */
    private static void checkCliffEndpoints() {
        int badAt0 = 0, badAt1 = 0;
        double worst0 = 0.0, worst1 = 0.0;
        for (double c = 0.0; c <= 1.0001; c += 0.05) {
            double s0 = OceanMorphConcepts.shoreShape(0.0, c);
            double s1 = OceanMorphConcepts.shoreShape(1.0, c);
            if (Math.abs(s0) > 1e-12) { badAt0++; worst0 = Math.max(worst0, Math.abs(s0)); }
            if (Math.abs(s1 - 1.0) > 1e-12) { badAt1++; worst1 = Math.max(worst1, Math.abs(s1 - 1.0)); }
        }
        record("M1", "violations-at-t0", badAt0, badAt0 == 0,
                "★ C3 不变式：shoreShape(0,c) 必须恒为 0（岸线高度）。最差=" + worst0);
        record("M1", "violations-at-t1", badAt1, badAt1 == 0,
                "★ C3 不变式：shoreShape(1,c) 必须恒为 1（与内陆等高）。最差=" + worst1);
    }

    /** M2：单调不减（每个 cliff 值）。 */
    private static void checkCliffMonotonic() {
        int violations = 0;
        double worstDrop = 0.0;
        for (double c = 0.0; c <= 1.0001; c += 0.1) {
            double prev = OceanMorphConcepts.shoreShape(0.0, c);
            for (double t = 0.01; t <= 1.0001; t += 0.01) {
                double v = OceanMorphConcepts.shoreShape(t, c);
                if (v < prev - 1e-12) {
                    violations++;
                    worstDrop = Math.min(worstDrop, v - prev);
                }
                prev = v;
            }
        }
        record("M2", "monotonic-violations", violations, violations == 0,
                "shape 在 t 上必须单调不减（否则坡会出现反向段）。最大回落=" + worstDrop);
    }

    /**
     * M3：cliff 只改坡形、不动两端。
     *
     * <p>判据：{@code shape(0.5, 0)} 与 {@code shape(0.5, 1)} 必须有显著差异，
     * 而 {@code t=0/1} 处完全相同（已由 M1 保证）。</p>
     */
    private static void checkCliffMovesShape() {
        double sandy = OceanMorphConcepts.shoreShape(0.5, 0.0);
        double cliffy = OceanMorphConcepts.shoreShape(0.5, 1.0);
        double gap = Math.abs(sandy - cliffy);
        record("M3", "mid-shape-gap", gap, gap > 1e-3,
                "★ 沙岸与崖岸在中点的 shape 必须显著不同（否则 cliff 是空参数）。"
                        + "实测 sandy=" + String.format(Locale.ROOT, "%.4f", sandy)
                        + " cliffy=" + String.format(Locale.ROOT, "%.4f", cliffy));
        // 崖岸前段应更平（t 小处 shape 更小）
        double sandyEarly = OceanMorphConcepts.shoreShape(0.2, 0.0);
        double cliffyEarly = OceanMorphConcepts.shoreShape(0.2, 1.0);
        record("M3", "cliff-flatter-early", sandyEarly - cliffyEarly, cliffyEarly < sandyEarly,
                "崖岸在前段（t=0.2）必须比沙岸更平（海蚀平台语义）");
    }

    // ---------------------------------------------------------------- M4

    /** M4：宽度因子单调升且封顶在 1+RETREAT_GAIN。 */
    private static void checkWidthGrowth() {
        double at0 = OceanMorphConcepts.shoreWidthFactor(0);
        double atRef = OceanMorphConcepts.shoreWidthFactor(OceanMorphConcepts.RETREAT_REF_BLOCKS);
        double atHuge = OceanMorphConcepts.shoreWidthFactor(10000);
        double maxAllowed = 1.0 + OceanMorphConcepts.RETREAT_GAIN;
        record("M4", "factor-at-0", at0, Math.abs(at0 - 1.0) < 1e-12,
                "陆高为 0 时因子必须为 1（无放大）");
        record("M4", "factor-at-ref", atRef, atRef > at0 && atRef <= maxAllowed + 1e-12,
                "达到参考陆高时应接近但不超过封顶");
        record("M4", "factor-capped", atHuge, Math.abs(atHuge - maxAllowed) < 1e-12,
                "极高陆高时因子必须封顶在 " + maxAllowed + "（不得无限增长）");
        record("M4", "monotonic", atRef > at0 ? 1 : 0, atRef > at0, "因子随陆高单调升");
    }

    // ---------------------------------------------------------------- M5~M7

    /** M5~M7：构造门控的完整语义。 */
    private static void checkGate() {
        double sigma = OceanMorphConcepts.RIDGE_BAND_SIGMA;

        // M6：stress<0（离散）+ dist=0 ⇒ 满值
        double peak = OceanMorphConcepts.tectonicGate(-1.0, 0.0, sigma);
        record("M6", "peak-value", peak, peak > 0.99,
                "★ stress=−1（强离散）且 dist=0（在边界上）时 gate 必须 ≈ 1");

        // M5a：只满足 stress（dist 很远）⇒ ≈ 0
        double farAway = OceanMorphConcepts.tectonicGate(-1.0, sigma * 6, sigma);
        record("M5", "stress-only", farAway, farAway < 1e-3,
                "★ 只有应力、远离边界 ⇒ gate ≈ 0（否则脊会铺满整个海盆）");

        // M5b：只满足 dist（stress>0 汇聚）⇒ 0
        double convergence = OceanMorphConcepts.tectonicGate(+1.0, 0.0, sigma);
        record("M5", "dist-only", convergence, Math.abs(convergence) < 1e-12,
                "★ 在边界上但应力为汇聚 ⇒ gate == 0（那里应该是海沟/弧，不是脊）");

        // M7：对称性 —— 反号必然为 0
        int nonzero = 0;
        for (double s = 0.05; s <= 1.0; s += 0.05) {
            if (OceanMorphConcepts.tectonicGate(s, 0.0, sigma) != 0.0) nonzero++;
        }
        record("M7", "convergent-nonzero", nonzero, nonzero == 0,
                "★ 所有 stress>0（汇聚）的取值下 gate 必须严格为 0");

        // 单调性：dist 增大 ⇒ gate 递减
        double g0 = OceanMorphConcepts.tectonicGate(-1.0, 0, sigma);
        double g1 = OceanMorphConcepts.tectonicGate(-1.0, sigma, sigma);
        double g2 = OceanMorphConcepts.tectonicGate(-1.0, sigma * 2, sigma);
        record("M5", "dist-monotonic", g0 - g2, g0 > g1 && g1 > g2,
                "gate 随 dist 单调递减（高斯包络）");
    }

    // ---------------------------------------------------------------- M8~M11

    /** 对比概念版与基线版在同一 id 下的分布差异。 */
    private static void compareRecipe(OceanRecipeCatalog plain, OceanRecipeCatalog concept,
                                      OceanRecipeCatalog.Recipe r,
                                      String diffId, String diffMetric) {
        int n = 0, diff = 0;
        double maxAbs = 0.0, sumAbs = 0.0;
        double minC = Double.POSITIVE_INFINITY, maxC = Double.NEGATIVE_INFINITY;
        double minP = Double.POSITIVE_INFINITY, maxP = Double.NEGATIVE_INFINITY;

        for (int z = -HALF; z <= HALF; z += STEP) {
            for (int x = -HALF; x <= HALF; x += STEP) {
                double pv = plain.height01(r, x, z);
                double cv = concept.height01(r, x, z);
                n++;
                minP = Math.min(minP, pv);
                maxP = Math.max(maxP, pv);
                minC = Math.min(minC, cv);
                maxC = Math.max(maxC, cv);
                double d = Math.abs(cv - pv);
                sumAbs += d;
                maxAbs = Math.max(maxAbs, d);
                if (Double.compare(cv, pv) != 0) diff++;
            }
        }
        record(diffId, diffMetric + "-changed-pixels", diff, diff > 0,
                "★ 概念版与基线版必须有差异（否则移植没落地）。改动 " + diff + "/" + n);
        record(diffId, diffMetric + "-max-abs", maxAbs, maxAbs >= MIN_CONCEPT_DIFF,
                "★ 最大差异必须 ≥ " + MIN_CONCEPT_DIFF + " e（≈1.3 格），否则差异不可见");
        record(diffId, diffMetric + "-mean-abs", n == 0 ? 0 : sumAbs / n, true,
                "平均绝对差异（参考量）");

        // M10：两版都必须仍在 depthRange 内
        double[] range = OceanRecipeCatalog.depthRange(r);
        double span = Math.abs(range[1] - range[0]);
        double tol = span * 0.10 + 1e-9;
        boolean pOk = minP >= range[0] - tol && maxP <= range[1] + tol;
        boolean cOk = minC >= range[0] - tol && maxC <= range[1] + tol;
        record("M10", r + "-plain-in-band", pOk ? 1 : 0, pOk,
                String.format(Locale.ROOT, "基线版必须落在 [%.2f, %.2f]±%.3f（实测 %.4f..%.4f）",
                        range[0], range[1], tol, minP, maxP));
        record("M10", r + "-concept-in-band", cOk ? 1 : 0, cOk,
                String.format(Locale.ROOT, "概念版必须落在 [%.2f, %.2f]±%.3f（实测 %.4f..%.4f）",
                        range[0], range[1], tol, minC, maxC));

        // M11：两版都必须恒 ≤ 0
        boolean pNoIsland = maxP <= 0.0 + 1e-9;
        boolean cNoIsland = maxC <= 0.0 + 1e-9;
        record("M11", r + "-plain-no-island", maxP, pNoIsland, "基线版不得造岛");
        record("M11", r + "-concept-no-island", maxC, cNoIsland, "概念版不得造岛");
    }

    // ---------------------------------------------------------------- 工具

    private static void record(String id, String metric, double value, boolean ok, String note) {
        checks++;
        csv.append(String.format(Locale.ROOT, "%s,%s,%.9g,%s%n", id, metric, value,
                ok ? "PASS" : "FAIL"));
        System.out.printf(Locale.ROOT, "%-4s %-38s %14.6g  %s%n", id, metric, value,
                ok ? "PASS" : "FAIL");
        if (!ok) {
            System.out.println("OCEAN CONCEPTS CHECK FAIL: " + id + " " + metric + " = " + value);
            System.out.println("  note: " + note);
            System.exit(1);
        }
    }

    private OceanMorphConceptsCheck() { }
}
