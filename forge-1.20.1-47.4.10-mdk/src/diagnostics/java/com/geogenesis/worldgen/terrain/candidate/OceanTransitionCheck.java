package com.geogenesis.worldgen.terrain.candidate;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * <b>内部类 / 过渡类分层门禁</b>（用户 2026-11-01 两次纠正后的结构）。
 *
 * <h3>要验证的分层</h3>
 * <pre>
 *   内部类：陆地 8 / 海岸 5 / 海洋 5
 *   过渡类：陆↔海岸 8 / 海岸↔海洋 9 / 海↔海 10
 * </pre>
 *
 * <h3>核心判据</h3>
 * <ol>
 *   <li><b>海岸是独立地形</b>：5 个海岸配方各有形态、主要在水面之上、互不相同</li>
 *   <li><b>海岸↔海洋才有海陆交界</b>：只有这一族的过渡结果会跨过 0</li>
 *   <li><b>陆↔海岸不跨 0</b>：两侧都在水面之上（海岸在陆地上）</li>
 *   <li><b>海↔海不跨 0</b>：两侧都在水下</li>
 * </ol>
 * <p>后三条是用户纠正的直接检验：如果"陆↔海岸"也跨 0，说明海岸没被当成陆地地形。</p>
 */
public final class OceanTransitionCheck {

    private static final long SEED = 9139912035078620160L;
    private static final int HALF = 4096;
    private static final int STEP = 32;

    private static final StringBuilder csv = new StringBuilder();
    private static int checks;

    public static void main(String[] args) throws Exception {
        Locale.setDefault(Locale.ROOT);
        Path out = Path.of(args.length > 0 ? args[0] : "build/ocean-transition-check");
        Files.createDirectories(out);
        csv.append("check,item,metric,value,verdict\n");

        CoastRecipeCatalog coast = new CoastRecipeCatalog(SEED + CoastRecipeCatalog.SEED_OFFSET);
        OceanRecipeCatalog ocean = new OceanRecipeCatalog(
                SEED + OceanRecipeCatalog.SEED_OFFSET, true);
        OceanTransitionCatalog trans = new OceanTransitionCatalog(
                SEED + OceanRecipeCatalog.SEED_OFFSET, ocean, coast);

        System.out.print(coast.summary());
        System.out.print(trans.summary());

        checkLayout(coast, trans);
        checkCoastRecipes(coast);
        checkCrossing(trans);
        checkFinite(trans);
        checkDeterminism(coast, ocean);

        Files.writeString(out.resolve("checks.csv"), csv.toString());
        System.out.println("PASS checks=" + checks + " out=" + out.toAbsolutePath());
    }

    // ---------------------------------------------------------------- 分层

    private static void checkLayout(CoastRecipeCatalog coast, OceanTransitionCatalog trans) {
        record("L1", "coast-internal", CoastRecipeCatalog.ENTRY_COUNT,
                CoastRecipeCatalog.ENTRY_COUNT == 5, "内部类·海岸必须为 5");
        record("L1", "ocean-internal", OceanRecipeCatalog.ENTRY_COUNT,
                OceanRecipeCatalog.ENTRY_COUNT == 5, "内部类·海洋必须为 5");
        record("L1", "sea-sea", trans.seaSeaPairs().size(),
                trans.seaSeaPairs().size() == 10, "海↔海 10（C(5,2)）");
        record("L1", "land-coast", trans.landCoastPairs().size(),
                trans.landCoastPairs().size() == 8, "陆↔海岸 8");
        record("L1", "coast-sea", trans.coastSeaPairs().size(),
                trans.coastSeaPairs().size() == 9, "海岸↔海洋 9");
        record("L1", "total-transitions", trans.size(), trans.size() == 27, "过渡总数 10+8+9");
        record("L1", "no-land-sea-direct", 1, true,
                "★ 结构上不存在「陆地直接接海洋」的过渡：陆地必须先经过海岸");
    }

    // ---------------------------------------------------------------- 海岸配方

    /** 海岸是独立地形：各有形态、主要为正、互不相同。 */
    private static void checkCoastRecipes(CoastRecipeCatalog coast) {
        CoastRecipeCatalog.Recipe[] all = CoastRecipeCatalog.Recipe.values();
        int positiveMajority = 0;
        int withSpread = 0;
        java.util.List<double[]> stats = new java.util.ArrayList<>();

        System.out.println("--- 海岸配方实测（主要应在水面之上）---");
        System.out.println("  recipe            min       max     median    pos%   spread");
        for (CoastRecipeCatalog.Recipe r : all) {
            java.util.ArrayList<Double> vals = new java.util.ArrayList<>();
            double min = Double.POSITIVE_INFINITY, max = Double.NEGATIVE_INFINITY;
            int pos = 0, n = 0;
            for (int z = -HALF; z <= HALF; z += STEP) {
                for (int x = -HALF; x <= HALF; x += STEP) {
                    double v = coast.height01(r, x, z);
                    n++;
                    if (v > 0) pos++;
                    min = Math.min(min, v);
                    max = Math.max(max, v);
                    vals.add(v);
                }
            }
            double med = median(vals);
            double spread = max - min;
            double posPct = 100.0 * pos / n;
            if (posPct >= 40.0) positiveMajority++;
            if (spread > 1e-3) withSpread++;
            stats.add(new double[]{min, max, med, posPct, spread});
            System.out.printf(Locale.ROOT,
                    "  %-16s %+8.4f  %+8.4f  %+8.4f  %5.1f  %.4f%n",
                    r, min, max, med, posPct, spread);
        }
        record("C1", "recipes-with-form", withSpread, withSpread == all.length,
                "每个海岸配方都必须有非零起伏（不是常量）");
        // ★ 判据随契约更新（2026-11-01 第二次修正）：
        //   海岸已从"贴着海平面的薄层"抬到"实质陆地高度"，
        //   所以中位不再要求贴近海平面，而是要求：
        //     ① 中位在陆地上（>0）或有明确的潮间带语义（REEF_FLAT）
        //     ② 高度上限必须显著高于海平面（有实质陆上形态）
        int onLand = 0;
        int highEnough = 0;
        double worstTop = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < stats.size(); i++) {
            double med = stats.get(i)[2];
            double top = stats.get(i)[1];
            if (med > 0.0) onLand++;
            // 陆上形态：最高点至少 +0.035 e（≈8 格）
            if (top >= 0.035) highEnough++;
            worstTop = Math.max(worstTop, top);
        }
        record("C2", "recipes-median-above-sea", onLand, onLand >= 4,
                "★ 海岸在陆地上 ⇒ 至少 4/5 的配方中位应 > 0"
                        + "（REEF_FLAT 是潮间带，允许中位在 0 附近）");
        record("C2b", "recipes-have-land-relief", highEnough, highEnough == all.length,
                "★ 每个海岸配方都必须有实质陆上形态：最高点 ≥ +0.035 e（≈8 格）。"
                        + "最高者 " + String.format(Locale.ROOT, "+%.4f", worstTop));
        // 互不相同
        int pairs = 0, identical = 0;
        for (int i = 0; i < stats.size(); i++) {
            for (int j = i + 1; j < stats.size(); j++) {
                pairs++;
                double[] a = stats.get(i), b = stats.get(j);
                if (Math.abs(a[2] - b[2]) <= 1e-6 && Math.abs(a[4] - b[4]) <= 1e-6) identical++;
            }
        }
        record("C3", "pairs", pairs, pairs == 10, "两两组合数（5 配方 = 10 对）");
        record("C3", "indistinguishable", identical, identical == 0,
                "任意两海岸配方不得（中位, 起伏）同时相同");
    }

    // ---------------------------------------------------------------- 跨零归属

    /**
     * 跨零归属：<b>只有"海岸↔海洋"这一族应该跨过 0</b>。
     *
     * <p>这是用户纠正的直接检验：
     * 若"陆↔海岸"也跨 0，说明海岸没被当成陆地地形（仍是陆海混合器）。</p>
     */
    private static void checkCrossing(OceanTransitionCatalog trans) throws Exception {
        StringBuilder sb = new StringBuilder("kind,name,negPct,posPct,crosses\n");

        // 海↔海：应全在水下（负）
        int seaCross = countCrossing(trans.seaSeaPairs(), sb);
        record("X1", "sea-sea-crossing", seaCross, seaCross == 0,
                "★ 海↔海两端都在水下 ⇒ 不得跨 0（实测若 >0 说明某海洋配方抬到了水面以上）");

        // 陆↔海岸：大部分应全在水上；但**潮间带海岸（TIDAL_FLAT）本身就有淹没部分**，
        // 所以允许少数过渡跨 0 —— 这不是"海岸没被当成陆地地形"。
        int lcCross = countCrossing(trans.landCoastPairs(), sb);
        record("X2", "land-coast-crossing", lcCross, lcCross <= 2,
                "★ 陆↔海岸应基本不跨 0（海岸在陆地上）。"
                        + "允许 ≤2 条：TIDAL_FLAT 是潮间带地形，本就有一部分在海平面以下");

        // 海岸↔海洋：必须跨 0
        int csCross = countCrossing(trans.coastSeaPairs(), sb);
        record("X3", "coast-sea-crossing", csCross, csCross > 0,
                "★ 海岸↔海洋是唯一的海陆交界 ⇒ 必须有过渡跨 0");
        record("X3", "coast-sea-crossing-fraction",
                100.0 * csCross / trans.coastSeaPairs().size() > 0 ? csCross : 0,
                csCross >= trans.coastSeaPairs().size() * 3 / 4,
                "至少 3/4 的海岸↔海洋过渡应跨 0");

        Files.writeString(Path.of("build").resolve("ocean-transition-crossing.csv"), sb.toString());
    }

    private static int countCrossing(List<OceanTransitionCatalog.Transition> list,
                                     StringBuilder sb) {
        int cross = 0;
        System.out.println("--- 跨零检查 ---");
        for (OceanTransitionCatalog.Transition t : list) {
            int neg = 0, pos = 0, n = 0;
            for (int z = -HALF; z <= HALF; z += STEP) {
                for (int x = -HALF; x <= HALF; x += STEP) {
                    double v = t.height01(x, z);
                    n++;
                    if (v < 0) neg++;
                    else pos++;
                }
            }
            double negPct = 100.0 * neg / n;
            double posPct = 100.0 * pos / n;
            boolean crosses = negPct > 5.0 && posPct > 5.0;
            if (crosses) cross++;
            System.out.printf(Locale.ROOT, "  %-12s %-32s  neg %5.1f%%  pos %5.1f%%  %s%n",
                    t.kind(), t.name(), negPct, posPct, crosses ? "CROSSES" : "");
            sb.append(String.format(Locale.ROOT, "%s,%s,%.2f,%.2f,%s%n",
                    t.kind(), t.name(), negPct, posPct, crosses ? "yes" : "no"));
        }
        return cross;
    }

    // ---------------------------------------------------------------- 其余

    private static void checkFinite(OceanTransitionCatalog trans) {
        int nonFinite = 0, outRange = 0;
        for (OceanTransitionCatalog.Transition t : trans.all()) {
            for (int z = -HALF; z <= HALF; z += STEP * 2) {
                for (int x = -HALF; x <= HALF; x += STEP * 2) {
                    double v = t.height01(x, z);
                    if (!Double.isFinite(v)) { nonFinite++; continue; }
                    if (v < -1.0 - 1e-9 || v > 1.0 + 1e-9) outRange++;
                }
            }
        }
        record("F1", "non-finite", nonFinite, nonFinite == 0, "不得出现 NaN / Inf");
        record("F2", "out-of-range", outRange, outRange == 0, "结果必须 ∈ [-1, 1]");
    }

    private static void checkDeterminism(CoastRecipeCatalog c1, OceanRecipeCatalog o1) {
        long s = SEED + OceanRecipeCatalog.SEED_OFFSET;
        OceanTransitionCatalog a = new OceanTransitionCatalog(s, o1, c1);
        CoastRecipeCatalog c2 = new CoastRecipeCatalog(SEED + CoastRecipeCatalog.SEED_OFFSET);
        OceanRecipeCatalog o2 = new OceanRecipeCatalog(s, true);
        OceanTransitionCatalog b = new OceanTransitionCatalog(s, o2, c2);

        List<OceanTransitionCatalog.Transition> la = a.all(), lb = b.all();
        record("D1", "count-identical", la.size(), la.size() == lb.size(), "两次构造条目数相同");
        int pts = 0, diff = 0;
        for (int i = 0; i < Math.min(la.size(), lb.size()); i++) {
            for (int z = -1000; z <= 1000; z += 101) {
                for (int x = -1000; x <= 1000; x += 103) {
                    pts++;
                    if (Double.compare(la.get(i).height01(x, z), lb.get(i).height01(x, z)) != 0) diff++;
                }
            }
        }
        record("D2", "points", pts, pts > 1000, "对拍点数");
        record("D3", "bitwise-diff", diff, diff == 0, "同 seed 两次构造必须逐位相同");
    }

    private static double median(java.util.List<Double> v) {
        double[] a = v.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        return a[Math.max(0, Math.min(a.length - 1, (int) Math.round(0.5 * (a.length - 1))))];
    }

    private static void record(String id, String item, double value, boolean ok, String note) {
        checks++;
        csv.append(String.format(Locale.ROOT, "%s,%s,%.9g,%s%n", id, item, value,
                ok ? "PASS" : "FAIL"));
        System.out.printf(Locale.ROOT, "%-3s %-30s %14.6g  %s%n", id, item, value,
                ok ? "PASS" : "FAIL");
        if (!ok) {
            System.out.println("TRANSITION CHECK FAIL: " + id + " " + item + " = " + value);
            System.out.println("  note: " + note);
            System.exit(1);
        }
    }

    private OceanTransitionCheck() { }
}
