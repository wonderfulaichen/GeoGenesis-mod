package com.geogenesis.worldgen.terrain.candidate;

import java.util.Locale;

/**
 * 「平原深处的浅色晕斑」归因：那一大片 {@code Y≈67.73} 的平地到底来自哪里。
 *
 * <h3>已确认的事实</h3>
 * <p>{@code Y = 67.7288} 精确对应 {@code height01 = 0}：
 * {@code e = GROUND_E + 0 × E_SPAN = 0.02}，{@code Y = 63 + 0.02×236.44 = 67.7288}。
 * 实测陆地内坡度 {@code <0.02} 的区域占 <b>1.81%</b>，最大连通块 617 px。</p>
 *
 * <h3>要查的三件事</h3>
 * <ol>
 *   <li>{@code height01} 是否真的被 {@code Math.max(...,0)} 削到 0，还是本来就极小；</li>
 *   <li>若是被削：{@code regionHeight} 的哪个输入为负
 *       （{@code selected} 名额族？{@code borderAt} 边界地形？还是 {@code edge} 混合？）；</li>
 *   <li>这些负值来自哪个族的配方。</li>
 * </ol>
 *
 * <p>用法：{@code [px0 pz0 px1 pz1]}（默认 617px 最大连通块所在窗口）</p>
 */
public final class FlatSpotCauseProbe {

    private static final long SEED = 9139912035078620160L;

    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        // 实测最大极平块：块 x[3015..3225] z[3242..3387]
        double bx0 = args.length > 0 ? Double.parseDouble(args[0]) : 3015;
        double bz0 = args.length > 1 ? Double.parseDouble(args[1]) : 3242;
        double bx1 = args.length > 2 ? Double.parseDouble(args[2]) : 3225;
        double bz1 = args.length > 3 ? Double.parseDouble(args[3]) : 3387;

        // 构造与 LandCompositionGallery.buildCombine 完全一致（低地 5 + 独立 3）
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

        // 边界地形 = makeBorder → plains.height01 * 0.5
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

        System.out.println("=== 逐分量检查（窗口中心采样 4000 点）===");
        System.out.println("  height01  | regionHeight | blender.apply | mask | edge");

        double minH01 = 9, maxH01 = -9;
        int negRegion = 0, zeroH01 = 0, n = 0;
        double minRegion = 9;
        long st = 0x9E3779B97F4A7C15L;
        for (int i = 0; i < 4000; i++) {
            st ^= st << 13; st ^= st >>> 7; st ^= st << 17;
            double x = bx0 + ((st >>> 11) & 0xFFFFF) / 1048576.0 * (bx1 - bx0);
            st ^= st << 13; st ^= st >>> 7; st ^= st << 17;
            double z = bz0 + ((st >>> 11) & 0xFFFFF) / 1048576.0 * (bz1 - bz0);

            double h01 = c.height01(x, z);
            double rh = c.debugRegionHeight(x, z);
            double bl = c.debugBlenderApply(x, z);
            float mask = c.debugMountainMask(x, z);
            float edge = c.debugEdge(x, z);

            minH01 = Math.min(minH01, h01); maxH01 = Math.max(maxH01, h01);
            minRegion = Math.min(minRegion, rh);
            if (rh < 0) negRegion++;
            if (h01 == 0.0) zeroH01++;
            n++;
            if (i < 8) {
                System.out.printf(Locale.ROOT,
                        "   %.6f  | %+.6f | %+.6f | %.4f | %.4f%n", h01, rh, bl, mask, edge);
            }
        }
        System.out.println();
        System.out.printf(Locale.ROOT, "  height01    : %.6f .. %.6f%n", minH01, maxH01);
        System.out.printf(Locale.ROOT, "  regionHeight: min %+.6f  (负值 %d / %d = %.1f%%)%n",
                minRegion, negRegion, n, 100.0 * negRegion / n);
        System.out.printf(Locale.ROOT, "  height01==0 : %d / %d = %.1f%%%n", zeroH01, n, 100.0 * zeroH01 / n);
    }
}
