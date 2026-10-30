package com.geogenesis.worldgen.terrain.candidate;

import java.util.Locale;

/**
 * 极平区到底选中了哪个族，以及该族在全局/局部的取值分布。
 *
 * <h3>已确认</h3>
 * <p>{@link FlatZoneDiagnoseProbe}：极平区 {@code selected} 均值 <b>0.0005</b>，
 * 全局 P50 是 <b>0.1506</b> —— 差 300 倍。{@code edge=0.977}（74% 纯族区），
 * 所以 {@code regionH ≈ selected}，问题在"选中的族在这里输出≈0"。</p>
 *
 * <h3>两种可能</h3>
 * <ol>
 *   <li><b>选中了低值族</b>：如 plateaus<b>谷底</b>或 badlands 的某段，
 *       该族本身在 0 附近有大量低值；</li>
 *   <li><b>选中族正常，但这片地形恰好是它的谷底</b>：族噪声在几十万块尺度上
 *       出现长波低谷（1 个 Voronoi 区 = 1200 块，一个低值区可能覆盖 1000+ 块）。</li>
 * </ol>
 * <p>区分方法：看选中族的<b>全局分布</b>。若该族全局就很低（P50&lt;0.05），是①；
 * 若全局正常（P50≈0.15）而只在局部低，是②。</p>
 *
 * <p>用法：{@code [bx0 bz0 bx1 bz1]}</p>
 */
public final class SelectedFamilyProbe {

    private static final long SEED = 9139912035078620160L;
    private static final String[] KIND = {
            "plains", "steppe", "dales", "hills1", "hills2",
            "plateau", "badlands", "mtn1"
    };

    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        double bx0 = args.length > 0 ? Double.parseDouble(args[0]) : 3015;
        double bz0 = args.length > 1 ? Double.parseDouble(args[1]) : 3242;
        double bx1 = args.length > 2 ? Double.parseDouble(args[2]) : 3225;
        double bz1 = args.length > 3 ? Double.parseDouble(args[3]) : 3387;

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

        CombineCandidate.Family[] fams = {
                plains::height01, steppe::height01, dales::height01,
                h1::height01, h2::height01,
                plateau::height01, badlands::height01, m1::height01
        };

        // 该区的选中族（用与 gallery 相同的 slots 展开）
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
                (x, z) -> plains.height01(x, z) * 0.5);

        // 局部：被选中族的直方图
        int[] localCnt = new int[fams.length];
        double[] localSum = new double[fams.length];
        int n = 4000;
        long st = 0x9E3779B97F4A7C15L;
        for (int i = 0; i < n; i++) {
            st ^= st << 13; st ^= st >>> 7; st ^= st << 17;
            double x = bx0 + ((st >>> 11) & 0xFFFFF) / 1048576.0 * (bx1 - bx0);
            st ^= st << 13; st ^= st >>> 7; st ^= st << 17;
            double z = bz0 + ((st >>> 11) & 0xFFFFF) / 1048576.0 * (bz1 - bz0);
            // 用最终高度反查哪个族，比反射 slot 更稳：直接比较各族的 height01
            double rh = c.debugRegionHeight(x, z);
            int best = 0;
            double bd = Double.MAX_VALUE;
            for (int f = 0; f < fams.length; f++) {
                double d = Math.abs(fams[f].height01(x, z) - rh);
                if (d < bd) { bd = d; best = f; }
            }
            localCnt[best]++;
            localSum[best] += rh;
        }

        System.out.println("=== 极平区：哪个族被选中（按值匹配反查）===");
        System.out.printf(Locale.ROOT, "  %-9s %8s %10s%n", "族", "占比", "该处均值");
        for (int f = 0; f < fams.length; f++) {
            if (localCnt[f] == 0) continue;
            System.out.printf(Locale.ROOT, "  %-9s %7.1f%% %+10.6f%n",
                    KIND[f], 100.0 * localCnt[f] / n, localSum[f] / localCnt[f]);
        }

        System.out.println();
        System.out.println("=== 各族全局分布（对照：局部是否异常低）===");
        System.out.printf(Locale.ROOT, "  %-9s %10s %10s %10s%n", "族", "P10", "P50", "P90");
        int gn = 100_000;
        long gs = 0x243F6A8885A308D3L;
        for (int f = 0; f < fams.length; f++) {
            double[] v = new double[gn];
            gs = 0x243F6A8885A308D3L;
            for (int i = 0; i < gn; i++) {
                gs ^= gs << 13; gs ^= gs >>> 7; gs ^= gs << 17;
                double x = ((gs >>> 11) & 0x3FFFFF) * 0.05 - 100_000;
                gs ^= gs << 13; gs ^= gs >>> 7; gs ^= gs << 17;
                double z = ((gs >>> 11) & 0x3FFFFF) * 0.05 - 100_000;
                v[i] = fams[f].height01(x, z);
            }
            java.util.Arrays.sort(v);
            System.out.printf(Locale.ROOT, "  %-9s %10.6f %10.6f %10.6f%n",
                    KIND[f], v[gn / 10], v[gn / 2], v[gn * 9 / 10]);
        }
    }
}
