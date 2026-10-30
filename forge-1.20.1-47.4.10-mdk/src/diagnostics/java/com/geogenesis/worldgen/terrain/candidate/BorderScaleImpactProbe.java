package com.geogenesis.worldgen.terrain.candidate;

import java.util.Locale;

/**
 * 量化「边界地形 {@code ×0.5} 去掉」的影响。
 *
 * <h3>背景</h3>
 * <p>我们 {@code borderRecipe()} 返回 {@code plains.height01 * 0.5}，注释称
 * "steppe 的缩放＝基准 ×0.5（2.0 → 1.0）"。核对参考两个预设后发现：</p>
 * <pre>
 *   makeBorder → makePlains(seed, ground, noiseSettings=plains, scalingSettings=steppe, vs)
 *     · noiseSettings 决定 noise 尺度（horizontalScale）
 *     · scalingSettings 决定 baseScale / verticalScale（传给 TerrainPopulator.make）
 *
 *   RTF 默认预设（Presets.java:35-46）   steppe baseScale=2.0, plains baseScale=2.0  ⇒ ×1.0
 *   legacy 预设  （Presets.java:81-92）   steppe baseScale=1.0, plains baseScale=2.0  ⇒ ×0.5
 * </pre>
 * <p><b>所以 ×0.5 是 legacy 预设的系数，套在 RTF 默认预设上是错的。</b>
 * 我们的沙箱对齐的是 RTF 默认（{@code makeRTFDefault}），应为 ×1.0。</p>
 *
 * <h3>本探针测什么</h3>
 * <p>构造两个 {@code CombineCandidate}，唯一差别是 border 的系数 0.5 vs 1.0，
 * 统计 {@code regionHeight} / {@code e} / {@code Y} 的变化幅度，
 * 以及受影响面积（{@code edge < 1} 的边界带占比）。</p>
 *
 * <p>用法：{@code [samples]}</p>
 */
public final class BorderScaleImpactProbe {

    private static final long SEED = 9139912035078620160L;
    private static final double SEA = 63.0;
    /** 陆侧 HeightCurve 斜率：Y = 63 + e × 236.44。 */
    private static final double BLOCKS_PER_E = 236.44;

    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 300_000;

        CombineCandidate half = build(0.5);
        CombineCandidate full = build(1.0);

        double sumH = 0, sumF = 0, sumAbs = 0, maxAbs = 0;
        int changed1 = 0, changed5 = 0, band = 0;
        double sumEdge = 0;

        long st = 0x9E3779B97F4A7C15L;
        for (int i = 0; i < n; i++) {
            st ^= st << 13; st ^= st >>> 7; st ^= st << 17;
            double x = ((st >>> 11) & 0x3FFFFF) * 0.05 - 100_000;
            st ^= st << 13; st ^= st >>> 7; st ^= st << 17;
            double z = ((st >>> 11) & 0x3FFFFF) * 0.05 - 100_000;

            double eh = half.e(x, z), ef = full.e(x, z);
            double d = (ef - eh) * BLOCKS_PER_E;   // 转成格
            sumH += eh; sumF += ef;
            sumAbs += Math.abs(d);
            maxAbs = Math.max(maxAbs, Math.abs(d));
            if (Math.abs(d) > 1) changed1++;
            if (Math.abs(d) > 5) changed5++;
            float edge = half.debugEdge(x, z);
            sumEdge += edge;
            if (edge < 0.999f) band++;
        }

        System.out.printf(Locale.ROOT, "=== 边界地形 ×0.5 → ×1.0 的影响（%d 样本）===%n%n", n);
        System.out.println("  说明：参考 RTF 默认预设的 steppe/plains baseScale 都是 2.0，");
        System.out.println("        makeBorder 得到的就是 plains.height01（×1.0），不是 ×0.5。");
        System.out.println();

        System.out.println("=== 受影响面积 ===");
        System.out.printf(Locale.ROOT, "  edge < 0.999（边界带）: %d / %d = %.1f%%%n",
                band, n, 100.0 * band / n);
        System.out.printf(Locale.ROOT, "  平均 edge             : %.4f%n", sumEdge / n);
        System.out.println();

        System.out.println("=== 高度变化（换算成格）===");
        System.out.printf(Locale.ROOT, "  mean|ΔY| : %.2f 格%n", sumAbs / n);
        System.out.printf(Locale.ROOT, "  max|ΔY|  : %.2f 格%n", maxAbs);
        System.out.printf(Locale.ROOT, "  |ΔY|>1   : %d / %d = %.1f%%%n", changed1, n, 100.0 * changed1 / n);
        System.out.printf(Locale.ROOT, "  |ΔY|>5   : %d / %d = %.1f%%%n", changed5, n, 100.0 * changed5 / n);
        System.out.println();

        System.out.println("=== 平均高度（陆地 e → Y）===");
        double mh = sumH / n, mf = sumF / n;
        System.out.printf(Locale.ROOT, "  ×0.5 臂  mean e=%.6f  → Y=%.1f%n", mh, eToY(mh));
        System.out.printf(Locale.ROOT, "  ×1.0 臂  mean e=%.6f  → Y=%.1f%n", mf, eToY(mf));
        System.out.printf(Locale.ROOT, "  抬升     %+.2f 格%n", eToY(mf) - eToY(mh));
        System.out.println();
        System.out.println("  注：上面是\"全采样均值\"，含海侧；海侧由 OceanAssembly 接管，不受 border 影响，");
        System.out.println("      所以真实陆地的抬升会比这个数更大。逐点见 |ΔY| 分布。");
    }

    /** e → Y（陆侧，e>=0）。 */
    private static double eToY(double e) {
        return e >= 0 ? SEA + e * BLOCKS_PER_E : SEA + e * (SEA + 64);
    }

    /** 构造与 gallery 同配置的 CombineCandidate，border 系数可调。 */
    private static CombineCandidate build(double borderScale) {
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

        CombineCandidate.Family border = (x, z) -> plains.height01(x, z) * borderScale;

        return new CombineCandidate(
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
    }
}
