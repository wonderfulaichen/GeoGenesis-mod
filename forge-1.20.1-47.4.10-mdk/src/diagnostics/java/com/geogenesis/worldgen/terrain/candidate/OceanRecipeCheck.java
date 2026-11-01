package com.geogenesis.worldgen.terrain.candidate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

/**
 * <b>{@link OceanRecipeCatalog} 的门禁</b>（方案 {@code ocean-unified-terrain} 步 1）。
 *
 * <h3>核心问题：CANYON 真的能"向下切"吗</h3>
 * <p>其余 5 个配方都可写成"在平原基准上抬升"，形式上与既有 {@code OceanFeatures}
 * （只能加）同类。只有 {@link OceanRecipeCatalog.Recipe#CANYON} 要求
 * {@code height01} 显著<b>低于</b>周围基准 —— 它直接检验新架构是否还带着
 * 陆地那套"height01 只能 ≥ 0 / 只能抬升"的假设。</p>
 *
 * <h3>九项判据</h3>
 * <pre>
 * R1 range       全部配方的 height01 恒 ∈ [-1, 0]（0=海平面，-1=世界底）
 * R2 depth-band  每个配方的实测值落在其声明的 depthRange 内（±10% 容差）
 * R3 canyon-neg  ★ CANYON 的中位数必须【低于】ABYSSAL_PLAIN 的中位数（向下切证据）
 * R4 canyon-gap  ★ 该差值 ≥ 0.05 e（≈6.4 格），否则"切"得太浅、读不出峡谷
 * R5 no-island   所有海洋配方恒 ≤ 0（不得造岛；造岛是 ShallowSeaIslandCandidate 的职责）
 * R6 finite      全域无 NaN / Inf
 * R7 spread      每个配方有非零起伏（否则是常量、无形态）
 * R8 determinism 同 seed 两次构造逐位相同
 * R9 distinct    6 个配方两两可区分（中位深度或分布不重合）
 * </pre>
 *
 * <p>FAIL 一律 {@code System.exit(1)}（{@code AGENTS.md}：带判据的探针必须用非零退出码）。</p>
 */
public final class OceanRecipeCheck {

    private static final long SEED = 9139912035078620160L;
    /** 采样窗口半宽（块）。4096² 覆盖足够多格点，含多个海山谷与峡谷。 */
    private static final int HALF = 4096;
    private static final int STEP = 16;
    /** 深度带容差（相对 depthRange 的 span 比例）。 */
    private static final double BAND_TOL = 0.10;
    /** CANYON 与平原的最小可读深度差（e）。≈6.4 格。 */
    private static final double CANYON_MIN_GAP = 0.05;

    private static final StringBuilder csv = new StringBuilder();
    private static int checks;

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Path out = Path.of(args.length > 0 ? args[0] : "build/ocean-recipe-check");
        Files.createDirectories(out);
        long seed = args.length > 1 ? Long.parseLong(args[1]) : SEED;

        csv.append("check,recipe,metric,value,verdict\n");

        long catSeed = seed + OceanRecipeCatalog.SEED_OFFSET;
        OceanRecipeCatalog cat = new OceanRecipeCatalog(catSeed);
        System.out.print(cat.summary());

        Stats[] st = sample(cat);
        printStats(st);
        writeStats(st, out);

        checkRange(st);
        checkDepthBand(st);
        checkCanyon(st);
        checkNoIsland(st);
        checkSpread(st);
        checkDistinct(st);
        checkDeterminism(catSeed);

        Files.writeString(out.resolve("checks.csv"), csv.toString());
        System.out.println("PASS checks=" + checks + " out=" + out.toAbsolutePath());
    }

    // ---------------------------------------------------------------- 采样

    private static final class Stats {
        final OceanRecipeCatalog.Recipe r;
        int n;
        double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
        double p01 = 0, p50 = 0, p99 = 0;
        int nonFinite;

        Stats(OceanRecipeCatalog.Recipe r) { this.r = r; }

        void finalizeStats(double[] v) {
            java.util.Arrays.sort(v);
            n = v.length;
            min = v[0];
            max = v[n - 1];
            p01 = q(v, 0.01);
            p50 = q(v, 0.50);
            p99 = q(v, 0.99);
        }

        static double q(double[] sorted, double p) {
            return sorted[Math.max(0, Math.min(sorted.length - 1,
                    (int) Math.round(p * (sorted.length - 1))))];
        }
    }

    private static Stats[] sample(OceanRecipeCatalog cat) {
        OceanRecipeCatalog.Recipe[] all = OceanRecipeCatalog.Recipe.values();
        Stats[] st = new Stats[all.length];
        int pts = 0;
        for (OceanRecipeCatalog.Recipe r : all) {
            st[r.id] = new Stats(r);
        }
        // 一次遍历取全部配方（同一点喂 6 个配方，保证可比）
        int est = ((2 * HALF / STEP) + 1) * ((2 * HALF / STEP) + 1);
        double[][] vals = new double[all.length][est];
        int[] counts = new int[all.length];

        for (int z = -HALF; z <= HALF; z += STEP) {
            for (int x = -HALF; x <= HALF; x += STEP) {
                pts++;
                for (OceanRecipeCatalog.Recipe r : all) {
                    double v = cat.height01(r, x, z);
                    if (!Double.isFinite(v)) {
                        st[r.id].nonFinite++;
                        continue;
                    }
                    vals[r.id][counts[r.id]++] = v;
                }
            }
        }
        for (OceanRecipeCatalog.Recipe r : all) {
            st[r.id].finalizeStats(java.util.Arrays.copyOf(vals[r.id], counts[r.id]));
        }
        System.out.printf(Locale.ROOT, "sampled %d points per recipe (%dx%d step=%d)%n",
                pts, 2 * HALF, 2 * HALF, STEP);
        return st;
    }

    private static void printStats(Stats[] st) {
        System.out.println("=== 各配方 height01 分布（0=海平面, -1=世界底）===");
        System.out.println("  id  recipe              n      min      P01      P50      P99      max    blocks(P50)");
        for (Stats s : st) {
            System.out.printf(Locale.ROOT,
                    "  %d   %-18s %6d  %+.4f  %+.4f  %+.4f  %+.4f  %+.4f   %+.1f%n",
                    s.r.id, s.r, s.n, s.min, s.p01, s.p50, s.p99, s.max,
                    s.p50 * OceanRecipeCatalog.BLOCKS_PER_E);
        }
    }

    private static void writeStats(Stats[] st, Path out) throws Exception {
        StringBuilder sb = new StringBuilder("id,recipe,n,min,p01,p50,p99,max,medianBlocks\n");
        for (Stats s : st) {
            sb.append(String.format(Locale.ROOT, "%d,%s,%d,%.6f,%.6f,%.6f,%.6f,%.6f,%.3f%n",
                    s.r.id, s.r, s.n, s.min, s.p01, s.p50, s.p99, s.max,
                    s.p50 * OceanRecipeCatalog.BLOCKS_PER_E));
        }
        Files.writeString(out.resolve("recipe-stats.csv"), sb.toString());
    }

    // ---------------------------------------------------------------- R1 / R2

    /** R1：全部配方恒 ∈ [-1, 0]。 */
    private static void checkRange(Stats[] st) {
        for (Stats s : st) {
            boolean ok = s.min >= -1.0 - 1e-9 && s.max <= 0.0 + 1e-9;
            record("R1", s.r, "min", s.min, ok && s.min >= -1.0 - 1e-9,
                    "下界必须 ≥ -1（世界底）");
            record("R1", s.r, "max", s.max, s.max <= 0.0 + 1e-9,
                    "上界必须 ≤ 0（海平面；海洋配方不得造岛）");
        }
    }

    /** R2：实测值落在声明 depthRange 内（按 span 的 10% 容差）。 */
    private static void checkDepthBand(Stats[] st) {
        for (Stats s : st) {
            double[] d = OceanRecipeCatalog.depthRange(s.r);
            double span = Math.abs(d[1] - d[0]);
            double tol = span * BAND_TOL + 1e-9;
            boolean belowOk = s.min >= d[0] - tol;
            boolean aboveOk = s.max <= d[1] + tol;
            record("R2", s.r, "min-vs-lo", s.min - d[0], belowOk,
                    String.format(Locale.ROOT,
                            "实测 min 不得低于声明下界 %.3f − 容差 %.3f", d[0], tol));
            record("R2", s.r, "max-vs-hi", s.max - d[1], aboveOk,
                    String.format(Locale.ROOT,
                            "实测 max 不得高于声明上界 %.3f + 容差 %.3f", d[1], tol));
        }
    }

    // ---------------------------------------------------------------- R3 / R4（CANYON 核心）

    /**
     * R3/R4：CANYON 必须真的比平原更深。
     *
     * <p>用<b>中位数</b>而非极值：极值会被单个最深点满足，中位数才能证明
     * "整体上是向下切的形态"。同时报 P01，用于确认深槽确实存在。</p>
     */
    private static void checkCanyon(Stats[] st) {
        Stats canyon = st[OceanRecipeCatalog.Recipe.CANYON.id];
        Stats plain = st[OceanRecipeCatalog.Recipe.ABYSSAL_PLAIN.id];

        double gap = plain.p50 - canyon.p50;      // 正 = 峡谷更深
        record("R3", OceanRecipeCatalog.Recipe.CANYON, "median-below-plain", gap, gap > 0,
                "★ CANYON 的 P50 必须低于 ABYSSAL_PLAIN 的 P50 ⇒ 整体上向下切，"
                        + "而不是把「在平原上抬升」换个参数");

        record("R4", OceanRecipeCatalog.Recipe.CANYON, "median-gap-vs-plain", gap,
                gap >= CANYON_MIN_GAP,
                String.format(Locale.ROOT,
                        "★ 深度差必须 ≥ %.2f e（≈%.1f 格），否则切得太浅、读不出峡谷。实测 %.4f e (%.1f 格)",
                        CANYON_MIN_GAP, CANYON_MIN_GAP * OceanRecipeCatalog.BLOCKS_PER_E,
                        gap, gap * OceanRecipeCatalog.BLOCKS_PER_E));

        // 深槽是否真的存在：P01 应显著低于平原 P01
        double deepGap = plain.p01 - canyon.p01;
        record("R4", OceanRecipeCatalog.Recipe.CANYON, "p01-gap-vs-plain", deepGap, deepGap > 0,
                String.format(Locale.ROOT,
                        "CANYON 的 P01 也应低于平原 P01（深槽确实存在的证据）。实测 %.4f e", deepGap));

        // 与"抬升型"配方对比：洋中脊应当【比平原浅】
        Stats ridge = st[OceanRecipeCatalog.Recipe.MID_OCEAN_RIDGE.id];
        double ridgeGap = plain.p50 - ridge.p50;   // 负 = 脊更高
        record("R4", OceanRecipeCatalog.Recipe.MID_OCEAN_RIDGE, "median-above-plain", -ridgeGap,
                ridgeGap < 0,
                "对照组：洋中脊应比平原【浅】（负值更小）⇒ 证明两族极性相反、不是同一算子换参数");
    }

    // ---------------------------------------------------------------- R5 / R6

    /** R5/R6：全部海洋配方恒 ≤ 0 且无 NaN/Inf。 */
    private static void checkNoIsland(Stats[] st) {
        int viol = 0;
        int nonFinite = 0;
        for (Stats s : st) {
            nonFinite += s.nonFinite;
            if (s.max > 0.0 + 1e-9) viol++;
        }
        record("R5", null, "recipes-above-sea-level", viol, viol == 0,
                "所有海洋配方的最大值都必须 ≤ 0（造岛归 ShallowSeaIslandCandidate，两者正交）");
        record("R6", null, "non-finite-samples", nonFinite, nonFinite == 0,
                "全域不得出现 NaN / Inf");
    }

    // ---------------------------------------------------------------- R7 / R9

    /** R7：每个配方都有非零起伏（P99-P01 > 0）。 */
    private static void checkSpread(Stats[] st) {
        for (Stats s : st) {
            double spread = s.p99 - s.p01;
            record("R7", s.r, "spread-p01-p99", spread, spread > 1e-6,
                    "配方必须有形态（P99−P01 > 0），常量配方等于没有形态");
        }
    }

    /**
     * R9：6 个配方两两可区分。
     *
     * <p>判据：任意两配方的 P50 不同（差 &gt; 1e-6）<b>或</b> 起伏区间不同。
     * 只比 P50 会漏掉"CANYON 与 PLAIN 基准相同、只有凹陷不同"的情形。</p>
     */
    private static void checkDistinct(Stats[] st) {
        int pairs = 0, identical = 0;
        for (int i = 0; i < st.length; i++) {
            for (int j = i + 1; j < st.length; j++) {
                pairs++;
                boolean medSame = Math.abs(st[i].p50 - st[j].p50) <= 1e-6;
                boolean spreadSame = Math.abs((st[i].p99 - st[i].p01) - (st[j].p99 - st[j].p01)) <= 1e-6;
                if (medSame && spreadSame) {
                    identical++;
                    System.out.printf(Locale.ROOT,
                            "  [R9] indistinguishable: %s vs %s (p50=%+.4f spread=%.4f)%n",
                            st[i].r, st[j].r, st[i].p50, st[i].p99 - st[i].p01);
                }
            }
        }
        record("R9", null, "pairs", pairs, pairs == st.length * (st.length - 1) / 2,
                "两两组合数（" + st.length + " 配方 = " + (st.length * (st.length - 1) / 2)
                        + " 对；随 ENTRY_COUNT 自动派生，不写死）");
        record("R9", null, "indistinguishable-pairs", identical, identical == 0,
                "任意两配方的（P50, 起伏幅度）不得同时相同");
    }

    // ---------------------------------------------------------------- R8

    /** R8：同 seed 两次构造逐位相同。 */
    private static void checkDeterminism(long catSeed) {
        OceanRecipeCatalog a = new OceanRecipeCatalog(catSeed);
        OceanRecipeCatalog b = new OceanRecipeCatalog(catSeed);
        int pts = 0, diff = 0;
        for (int z = -2000; z <= 2000; z += 37) {
            for (int x = -2000; x <= 2000; x += 41) {
                pts++;
                for (OceanRecipeCatalog.Recipe r : OceanRecipeCatalog.Recipe.values()) {
                    if (Double.compare(a.height01(r, x, z), b.height01(r, x, z)) != 0) diff++;
                }
            }
        }
        record("R8", null, "points", pts, pts > 5000, "确定性对比点数");
        record("R8", null, "bitwise-diff", diff, diff == 0, "同 seed 两次构造必须逐位相同");
    }

    // ---------------------------------------------------------------- 工具

    private static void record(String id, OceanRecipeCatalog.Recipe r, String metric,
                               double value, boolean ok, String note) {
        checks++;
        String who = r == null ? "-" : r.name();
        csv.append(String.format(Locale.ROOT, "%s,%s,%s,%.9g,%s%n", id, who, metric, value,
                ok ? "PASS" : "FAIL"));
        System.out.printf(Locale.ROOT, "%-3s %-18s %-22s %14.6g  %s%n",
                id, who, metric, value, ok ? "PASS" : "FAIL");
        if (!ok) {
            System.out.println("OCEAN RECIPE CHECK FAIL: " + id + " " + who + " " + metric
                    + " = " + value);
            System.out.println("  note: " + note);
            System.exit(1);
        }
    }

    private OceanRecipeCheck() { }
}
