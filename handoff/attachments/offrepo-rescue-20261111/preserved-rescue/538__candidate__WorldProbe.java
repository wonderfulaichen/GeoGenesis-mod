package com.geogenesis.worldgen.terrain.candidate;

import java.util.Locale;

/** 诊断 UnifiedWorld 为什么没有海。 */
public final class WorldProbe {
    public static void main(String[] args) throws Exception {
        Locale.setDefault(java.util.Locale.ROOT);
        long SEED = 9139912035078620160L;
        var cat = new RecipeMorphCatalog(SEED);
        var morph = new FullMorphLand(cat);
        var coast = new CoastRecipeCatalog(SEED + CoastRecipeCatalog.SEED_OFFSET);
        var ocean = new OceanRecipeCatalog(SEED + OceanRecipeCatalog.SEED_OFFSET, true);
        var w = new UnifiedWorld(morph, coast, ocean, SEED);

        System.out.println("=== 沿 z=137 横线 ===");
        System.out.println("     x        c   p(sea)  isSea   baseE    e(final)");
        for (double x = -3000; x <= 3000; x += 300) {
            double cc = w.c(x, 137);
            double p = w.seaTypeProbability(cc);
            boolean isSea = w.isSea(x, 137);
            double be = w.baseEof(x, 137);
            double e = w.e(x, 137);
            System.out.printf(Locale.ROOT, "  %7.0f  %+7.4f  %6.3f  %5s  %+8.4f  %+8.4f%n",
                    x, cc, p, isSea, be, e);
        }

        // c 与 eSea 的分布
        java.util.ArrayList<Double> cv = new java.util.ArrayList<>();
        java.util.ArrayList<Double> sv = new java.util.ArrayList<>();
        int nNeg = 0, n = 0;
        for (double z = -3000; z <= 3000; z += 150) {
            for (double x = -3000; x <= 3000; x += 150) {
                double cc = w.c(x, z);
                cv.add(cc);
                var or = w.pickOcean(cc);
                double eS = CombineCandidate.seaE(ocean.height01(or, x, z));
                sv.add(eS);
                if (cc < 0) nNeg++;
                n++;
            }
        }
        double[] cArr = cv.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        double[] sArr = sv.stream().mapToDouble(Double::doubleValue).sorted().toArray();
        System.out.printf(Locale.ROOT, "%n=== 分布（n=%d，c<0 占 %.1f%%）===%n", n, 100.0 * nNeg / n);
        System.out.printf(Locale.ROOT, "c     : P1=%+.3f P10=%+.3f P50=%+.3f P90=%+.3f P99=%+.3f%n",
                q(cArr, .01), q(cArr, .10), q(cArr, .50), q(cArr, .90), q(cArr, .99));
        System.out.printf(Locale.ROOT, "eSea  : P1=%+.3f P10=%+.3f P50=%+.3f P90=%+.3f P99=%+.3f%n",
                q(sArr, .01), q(sArr, .10), q(sArr, .50), q(sArr, .90), q(sArr, .99));
    }

    private static double q(double[] a, double p) {
        return a[Math.max(0, Math.min(a.length - 1, (int) Math.round(p * (a.length - 1))))];
    }

    private WorldProbe() { }
}
