package com.geogenesis.worldgen.terrain.candidate;

import java.util.Locale;

/**
 * 极平区（{@code Y≈67.73}）的<b>局部</b>诊断。
 *
 * <h3>全局 vs 局部的关键分野</h3>
 * <p>{@link RegionHeightProbe} 实测：全局 {@code regionHeight < 0} 只占 <b>0.25%</b>，
 * 而极平区窗口内是 <b>44.8%</b> —— 高 180 倍。所以这不是全局系统性缺陷，
 * 而是<b>该处某个输入恰好落进低值谷</b>。</p>
 *
 * <h3>要区分</h3>
 * <ol>
 *   <li>{@code selected}（选中族）在该处是多少？—— 若接近 0，是族噪声本身到了谷底；</li>
 *   <li>{@code borderAt} 在该处是多少？—— 若也很低，是边界地形太薄；</li>
 *   <li>{@code edge} 接近 1 还是 0？—— 决定是"纯族区"还是"边界带"。</li>
 * </ol>
 *
 * <p>用法：{@code [bx0 bz0 bx1 bz1]}</p>
 */
public final class FlatZoneDiagnoseProbe {

    private static final long SEED = 9139912035078620160L;

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

        System.out.printf(Locale.ROOT, "=== 极平区 块 x[%.0f..%.0f] z[%.0f..%.0f] ===%n%n", bx0, bx1, bz0, bz1);
        System.out.println("  selected | borderAt | edge   | regionH  | 判读");

        double sumSel = 0, sumBor = 0, sumEdge = 0, sumRh = 0;
        int n = 3000, negRh = 0, edgeLe = 0;
        long st = 0x9E3779B97F4A7C15L;
        for (int i = 0; i < n; i++) {
            st ^= st << 13; st ^= st >>> 7; st ^= st << 17;
            double x = bx0 + ((st >>> 11) & 0xFFFFF) / 1048576.0 * (bx1 - bx0);
            st ^= st << 13; st ^= st >>> 7; st ^= st << 17;
            double z = bz0 + ((st >>> 11) & 0xFFFFF) / 1048576.0 * (bz1 - bz0);

            double sel = c.debugSelected(x, z);
            double bor = c.debugBorderAt(x, z);
            float edge = c.debugEdge(x, z);
            double rh = c.debugRegionHeight(x, z);

            sumSel += sel; sumBor += bor; sumEdge += edge; sumRh += rh;
            if (rh < 0) negRh++;
            if (edge > 0.99f) edgeLe++;

            if (i < 10) {
                String tag = edge > 0.99f ? "纯族区" : (edge < 0.2f ? "边界带" : "过渡");
                System.out.printf(Locale.ROOT, "  %+.6f | %+.6f | %.4f | %+.6f | %s%n",
                        sel, bor, edge, rh, tag);
            }
        }
        System.out.println();
        System.out.printf(Locale.ROOT, "  均值: selected %.6f  borderAt %.6f  edge %.4f  regionH %.6f%n",
                sumSel / n, sumBor / n, sumEdge / n, sumRh / n);
        System.out.printf(Locale.ROOT, "  regionH < 0 : %d / %d = %.1f%%%n", negRh, n, 100.0 * negRh / n);
        System.out.printf(Locale.ROOT, "  edge > 0.99 : %d / %d = %.1f%%%n", edgeLe, n, 100.0 * edgeLe / n);
        System.out.println();
        System.out.println("=== 对比全局（RegionHeightProbe 实测）===");
        System.out.println("  selected 全局 P50 = 0.1506，此处均值见上");
        System.out.println("  regionH 全局 P50 = 0.0643，负值仅 0.25%");
    }
}
