package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

/**
 * 找一块"以陆地为主、且含山地与洼地"的窗口中心，用于出图观察湖河。
 *
 * <p>此前出图中心 (-140,137) / (-140,900) 都有 60~75% 是海，图上看不清湖与河。
 * 本探针在较大范围粗扫地形，按"陆地占比接近 1 且地形起伏较大"打分，输出候选中心。</p>
 */
public final class LandFinderProbe {

    public static void main(String[] args) {
        long seed = 9139912035078620160L;
        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        double sea = gen.seaLevel(), hs = tp.horizontalScale();

        int scan = 8000, gs = 250, win = 1200;   // 扫描 ±8000，每 250 块取样，窗口 1200 块
        int m = 2 * scan / gs + 1;
        double[] h = new double[m * m];
        for (int i = 0; i < m; i++) {
            for (int j = 0; j < m; j++) {
                h[i * m + j] = gen.sampleWu(
                        (-scan + i * gs) / hs, (-scan + j * gs) / hs).height;
            }
        }
        int half = win / gs / 2;
        java.util.List<double[]> scored = new java.util.ArrayList<>();
        for (int i = half; i < m - half; i++) {
            for (int j = half; j < m - half; j++) {
                int landCount = 0, total = 0;
                double mn = Double.MAX_VALUE, mx = -Double.MAX_VALUE;
                for (int a = i - half; a <= i + half; a++) {
                    for (int b = j - half; b <= j + half; b++) {
                        double v = h[a * m + b];
                        total++;
                        if (v >= sea) landCount++;
                        if (v < sea + 200) { mn = Math.min(mn, v); mx = Math.max(mx, v); }
                    }
                }
                double landFrac = landCount / (double) total;
                double relief = mx - mn;
                // 打分：陆地占比高 + 起伏大
                if (landFrac > 0.97) {
                    scored.add(new double[]{landFrac, relief, -scan + i * gs, -scan + j * gs});
                }
            }
        }
        scored.sort((a, b) -> Double.compare(b[1], a[1]));
        System.out.println("=== inland candidates (land>=97%, sorted by relief) ===");
        int shown = 0;
        for (double[] c : scored) {
            System.out.printf("centre=(%7.0f,%7.0f)  land=%.3f  relief=%.1f blocks%n",
                    c[2], c[3], c[0], c[1]);
            if (++shown >= 12) break;
        }
        if (shown == 0) System.out.println("(no window with >=97% land found in scan range)");
        System.out.println("USAGE: runHydroViewProbe \"<seed> <cx> <cz> <step> <precip>\"");
    }

    private LandFinderProbe() { }
}
