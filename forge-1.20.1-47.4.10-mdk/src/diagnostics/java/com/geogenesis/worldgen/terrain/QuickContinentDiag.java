package com.geogenesis.worldgen.terrain;

import java.util.Locale;

/** 临时诊断：核链各层数值（壳值 / FBM / 融合 / c）。 */
public final class QuickContinentDiag {
    public static void main(String[] args) {
        long seed = 9139912035078620160L;
        TerrainParams p = TerrainParams.defaults();
        ContinentField f = new ContinentField(p);
        f.seed(seed);
        ContinentField legacy = new ContinentField(p, true);
        legacy.seed(seed);
        System.out.printf(Locale.ROOT, "%-18s %9s %9s %9s %9s%n",
                "point", "shell", "fbm", "prodShell", "legacyC");
        double[][] pts = {{2000, -16000}, {0, -16000}, {-34600, -31000},
                {-16000, -16000}, {0, 0}, {12000, 12000}};
        for (double[] pt : pts) {
            double sh = f.shellValue(pt[0], pt[1]);
            double fb = legacy.sample(pt[0], pt[1]);
            double ps = f.productionShell(pt[0], pt[1]);
            System.out.printf(Locale.ROOT, "(%8.0f,%8.0f) shell=%7.3f fbm=%7.3f prod=%7.3f  diff=%+7.3f%n",
                    pt[0], pt[1], sh, fb, ps, ps - sh);
        }
    }

    private QuickContinentDiag() { }
}
