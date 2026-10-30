package com.geogenesis.worldgen.terrain.candidate;

import java.util.Locale;

/**
 * {@code regionHeight}（{@code RegionLerper} 输出）的全局分布。
 *
 * <h3>为什么要单独测它</h3>
 * <p>{@link FamilyRangeProbe} 已证实<b>各族自身值域正常</b>：
 * plains {@code [-0.02, 0.106]}、mtn1 {@code [0.0085, 0.617]}，与参考几乎一致。</p>
 *
 * <p>但 {@link FlatSpotCauseProbe} 在极平区测到 {@code regionHeight ≈ ±0.005}，
 * <b>比族输出小 60 倍</b>。说明问题出在<b>组合层</b>（{@code slots} 选人 /
 * {@code borderAt} 边界地形 / {@code edge} 混合），不在族配方。</p>
 *
 * <h3>要区分的三件事</h3>
 * <ol>
 *   <li>极平区是<b>局部的</b>还是<b>全局的</b>？</li>
 *   <li>若是局部：该处的 {@code selected}（选中族）与 {@code borderAt} 各是多少？</li>
 *   <li>{@code borderAt} 是否把 {@code selected} 稀释了？（边界带占 41% 面积）</li>
 * </ol>
 *
 * <p>用法：{@code [samples]}</p>
 */
public final class RegionHeightProbe {

    private static final long SEED = 9139912035078620160L;

    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 200_000;

        long seed = SEED;
        PlainsCandidate plains = new PlainsCandidate(PlainsCandidate.Variant.PLAINS_GEO);
        PlainsCandidate steppe = new PlainsCandidate(PlainsCandidate.Variant.STEPPE);
        HillsCandidate h1 = new HillsCandidate(HillsCandidate.Variant.HILLS_1_GEO);
        HillsCandidate h2 = new HillsCandidate(HillsCandidate.Variant.HILLS_2);
        HillsCandidate dales = new HillsCandidate(HillsCandidate.Variant.DALES_GEO);
        PlateauCandidate plateau = new PlateauCandidate(PlateauCandidate.Variant.REF);
        BadlandsCandidate badlands = new BadlandsCandidate();
        MountainsCandidate m1 = new MountainsCandidate(MountainsCandidate.Variant.M1_GEO);
        plains.seed(seed); steppe.seed(seed);
        h1.seed(seed + 11); h2.seed(seed + 22); dales.seed(seed + 33);
        plateau.seed(seed + 44); badlands.seed(seed + 55); m1.seed(seed + 77);

        CombineCandidate.Family border = (x, z) -> plains.height01(x, z) * 0.5;
        CombineCandidate c = new CombineCandidate(
                seed,
                new CombineCandidate.Family[]{
                        plains::height01, steppe::height01, dales::height01,
                        h1::height01, h2::height01
                },
                new CombineCandidate.Category[]{
                        CombineCandidate.Category.PLAIN, CombineCandidate.Category.PLAIN,
                        CombineCandidate.Category.HILLS, CombineCandidate.Category.HILLS,
                        CombineCandidate.Category.HILLS
                },
                new float[]{2.0f, 1.0f, 1.5f, 2.0f, 2.0f},
                new CombineCandidate.Family[]{
                        plateau::height01, badlands::height01, m1::height01
                },
                new CombineCandidate.Category[]{
                        CombineCandidate.Category.PLATEAU,
                        CombineCandidate.Category.BADLANDS,
                        CombineCandidate.Category.MOUNTAINS
                },
                new float[]{5.0f, 3.0f, 9.0f},
                border);

        double[] rh = new double[n], bh = new double[n], ed = new double[n], sel = new double[n];
        long st = 0x9E3779B97F4A7C15L;
        for (int i = 0; i < n; i++) {
            st ^= st << 13; st ^= st >>> 7; st ^= st << 17;
            double x = ((st >>> 11) & 0x3FFFFF) * 0.05 - 100_000;
            st ^= st << 13; st ^= st >>> 7; st ^= st << 17;
            double z = ((st >>> 11) & 0x3FFFFF) * 0.05 - 100_000;
            rh[i] = c.debugRegionHeight(x, z);
            bh[i] = c.debugBorderAt(x, z);
            ed[i] = c.debugEdge(x, z);
            sel[i] = c.debugSelected(x, z);
        }

        show("regionHeight", rh);
        show("borderAt", bh);
        show("edge", ed);
        show("selected", sel);

        System.out.println();
        System.out.println("=== 负值比例 ===");
        System.out.printf(Locale.ROOT, "  regionHeight < 0 : %.2f%%%n", 100 * fracNeg(rh));
        System.out.printf(Locale.ROOT, "  selected  < 0   : %.2f%%%n", 100 * fracNeg(sel));
        System.out.printf(Locale.ROOT, "  borderAt  < 0   : %.2f%%%n", 100 * fracNeg(bh));

        System.out.println();
        System.out.println("=== 按 edge 分档看 regionHeight 均值 ===");
        for (double[] band : new double[][]{{0, 0.2}, {0.2, 0.5}, {0.5, 0.8}, {0.8, 0.99}, {0.99, 1.01}}) {
            double sum = 0; int cnt = 0;
            for (int i = 0; i < n; i++) {
                if (ed[i] >= band[0] && ed[i] < band[1]) { sum += rh[i]; cnt++; }
            }
            if (cnt > 0) {
                System.out.printf(Locale.ROOT, "  edge [%.2f,%.2f): mean %.6f  (n=%d, %.1f%%)%n",
                        band[0], band[1], sum / cnt, cnt, 100.0 * cnt / n);
            }
        }
    }

    private static void show(String name, double[] v) {
        double[] s = v.clone();
        java.util.Arrays.sort(s);
        System.out.printf(Locale.ROOT, "  %-14s min %+.6f  P25 %+.6f  P50 %+.6f  P75 %+.6f  max %+.6f%n",
                name, s[0], s[s.length / 4], s[s.length / 2], s[s.length * 3 / 4], s[s.length - 1]);
    }

    private static double fracNeg(double[] v) {
        int c = 0;
        for (double d : v) if (d < 0) c++;
        return (double) c / v.length;
    }
}
