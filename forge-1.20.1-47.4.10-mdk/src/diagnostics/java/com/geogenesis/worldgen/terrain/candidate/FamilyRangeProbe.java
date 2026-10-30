package com.geogenesis.worldgen.terrain.candidate;

import java.util.Locale;

/**
 * 量出每个族 {@code height01} 的<b>真实值域</b>，作为"重标定到 [0,1] 非负区间"的依据。
 *
 * <h3>为什么必须实测</h3>
 * <p>{@code FlatSpotCauseProbe} 已证实：{@code regionHeight} 有 44.8% 的采样点为<b>负</b>
 * （实测 {@code -0.0047 .. +0.0108}），被 {@code Math.max(...,0)} 削成 0
 * ⇒ 图上出现 {@code Y=67.73} 的平顶晕斑。</p>
 *
 * <p>参考 {@code TerrainPopulator} 的族输出本来就是<b>非负小增量</b>
 * （plains {@code [-0.02,+0.15]}、mountains {@code [0,+0.70]}），
 * 而我们的族输出量级只有 {@code 0.01} 左右 —— <b>小了约 20~70 倍</b>。</p>
 *
 * <h3>本探针输出</h3>
 * <p>每个族的 min/P50/max，以及"若要映射到参考区间，需要的缩放系数"。</p>
 *
 * <p>用法：{@code [samples]}</p>
 */
public final class FamilyRangeProbe {

    private static final long SEED = 9139912035078620160L;

    /** 参考各族 height 项的目标区间（{@code Populators.java} 原文换算）。 */
    private record Target(String name, double lo, double hi) { }

    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 200_000;

        PlainsCandidate plains = new PlainsCandidate(PlainsCandidate.Variant.PLAINS_GEO);
        PlainsCandidate steppe = new PlainsCandidate(PlainsCandidate.Variant.STEPPE);
        HillsCandidate h1 = new HillsCandidate(HillsCandidate.Variant.HILLS_1_GEO);
        HillsCandidate h2 = new HillsCandidate(HillsCandidate.Variant.HILLS_2);
        HillsCandidate dales = new HillsCandidate(HillsCandidate.Variant.DALES_GEO);
        PlateauCandidate plateau = new PlateauCandidate(PlateauCandidate.Variant.REF);
        BadlandsCandidate badlands = new BadlandsCandidate();
        MountainsCandidate m1 = new MountainsCandidate(MountainsCandidate.Variant.M1_GEO);
        plains.seed(SEED); steppe.seed(SEED);
        h1.seed(SEED + 11); h2.seed(SEED + 22); dales.seed(SEED + 33);
        plateau.seed(SEED + 44); badlands.seed(SEED + 55); m1.seed(SEED + 77);

        String[] names = {"plains", "steppe", "hills1", "hills2", "dales", "plateau", "badlands", "mtn1"};
        CombineCandidate.Family[] fams = {
                plains::height01, steppe::height01, h1::height01, h2::height01,
                dales::height01, plateau::height01, badlands::height01, m1::height01
        };
        // 参考目标区间（Populators.java 的 Noises.mul 系数）
        Target[] targets = {
                new Target("plains",  -0.02, 0.15),
                new Target("steppe",  -0.02, 0.08),
                new Target("hills1",   0.00, 0.60),
                new Target("hills2",   0.00, 0.55),
                new Target("dales",    0.00, 0.40),
                new Target("plateau",  0.00, 0.475),
                new Target("badlands", 0.00, 0.25),
                new Target("mtn1",     0.00, 0.70),
        };

        System.out.printf(Locale.ROOT, "=== 各族 height01 实测值域（%d 样本）===%n%n", n);
        System.out.printf(Locale.ROOT, "  %-9s %10s %10s %10s  %10s %10s  %8s%n",
                "族", "min", "P50", "max", "参考 lo", "参考 hi", "建议倍数");
        System.out.println("  " + "-".repeat(80));

        long st = 0x243F6A8885A308D3L;
        double[][] vals = new double[fams.length][n];
        for (int i = 0; i < n; i++) {
            st ^= st << 13; st ^= st >>> 7; st ^= st << 17;
            double x = ((st >>> 11) & 0x3FFFFF) * 0.05 - 100_000;
            st ^= st << 13; st ^= st >>> 7; st ^= st << 17;
            double z = ((st >>> 11) & 0x3FFFFF) * 0.05 - 100_000;
            for (int f = 0; f < fams.length; f++) {
                vals[f][i] = fams[f].height01(x, z);
            }
        }

        for (int f = 0; f < fams.length; f++) {
            double[] v = vals[f].clone();
            java.util.Arrays.sort(v);
            double mn = v[0], p50 = v[n / 2], mx = v[n - 1];
            double tl = targets[f].lo, th = targets[f].hi;
            // 建议倍数：把实测 [mn,mx] 映射到参考 [tl,th]
            double scale = (th - tl) / Math.max(1e-9, mx - mn);
            System.out.printf(Locale.ROOT, "  %-9s %10.6f %10.6f %10.6f  %10.4f %10.4f  %8.1f×%n",
                    names[f], mn, p50, mx, tl, th, scale);
        }

        System.out.println();
        System.out.println("=== 联合值域（全部族合并）===");
        double gmin = 9, gmax = -9;
        for (double[] v : vals) {
            for (double d : v) { gmin = Math.min(gmin, d); gmax = Math.max(gmax, d); }
        }
        System.out.printf(Locale.ROOT, "  全部族: %.6f .. %.6f  (跨度 %.6f)%n", gmin, gmax, gmax - gmin);
        System.out.printf(Locale.ROOT, "  → 若统一映射到 [0, 0.70]: scale = %.1f×, offset = %.6f%n",
                0.70 / (gmax - gmin), -gmin);

        System.out.println();
        System.out.println("=== 结论 ===");
        System.out.printf(Locale.ROOT, "  我们的族输出量级 %.4f，参考是 0.7 ⇒ 小 %.0f 倍%n",
                gmax - gmin, 0.70 / Math.max(1e-9, gmax - gmin));
        System.out.println("  ⇒ 需要整体放大，且负值要抬到 0 以上（参考的 plains 允许小幅负值作起伏）");
    }
}
