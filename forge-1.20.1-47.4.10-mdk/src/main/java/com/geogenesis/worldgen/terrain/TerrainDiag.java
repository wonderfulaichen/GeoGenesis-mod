package com.geogenesis.worldgen.terrain;

/**
 * 临时量化诊断。
 * 用默认参数 + 多个种子采样地形场，统计：
 *   1) 海陆占比（含深海）
 *   2) 各地形类型占比
 *   3) 每类的形态统计量（eLand / e / 高度 Y 的均值±标准差）
 *   4) 陆地 eLand 直方图
 *   5) 种子敏感性
 * 纯 Java，无 MC 依赖。跑完即删。
 */
public final class TerrainDiag {

    public static void main(String[] args) {
        TerrainParams p = com.geogenesis.config.GeoGenesisConfig.INSTANCE.defaultParams();
        int minWY = -64, maxWY = 320;
        long[] seeds = {12345L, 777L, 123456789L};
        int N = 2048;
        int step = 4;
        int M = N / step;
        int total = M * M;

        CellGenerator gen = new CellGenerator(p, minWY, maxWY);

        System.out.println("=== GeoGenesis Terrain Quantitative Diagnosis ===");
        System.out.println("Region=" + N + "x" + N + " blocks, step=" + step + " => " + M + "x" + M + " = " + total + " samples");
        System.out.println("Params: continentBias=" + p.continentBias()
                + ", elevHigh=" + p.elevHigh() + ", reliefHigh=" + p.reliefHigh() + ", peakE=" + p.peakE());
        System.out.println();

        TerrainClass[] TCV = TerrainClass.values();
        int nT = TCV.length;

        for (int si = 0; si < seeds.length; si++) {
            long s = seeds[si];
            gen.seed(s);

            int land = 0, sea = 0, deep = 0, beach = 0;
            int[] cls = new int[nT];
            double[] sumELand = new double[nT], sumELand2 = new double[nT];
            double[] sumE = new double[nT], sumH = new double[nT];
            int[] elevHist = new int[10];

            for (int ix = 0; ix < M; ix++) {
                double wx = ix * step;
                for (int iz = 0; iz < M; iz++) {
                    double wz = iz * step;
                    Cell c = gen.sample(wx, wz);
                    int o = c.terrainType.ordinal();
                    cls[o]++;

                    if (c.terrainType == TerrainClass.DEEP_OCEAN) { sea++; deep++; }
                    else if (c.terrainType == TerrainClass.OCEAN) { sea++; }
                    else if (c.terrainType == TerrainClass.BEACH) { beach++; land++; }
                    else land++;

                    sumELand[o] += c.eLand; sumELand2[o] += c.eLand * c.eLand;
                    sumE[o] += c.e; sumH[o] += c.height;

                    if (!c.terrainType.isOcean() && c.terrainType != TerrainClass.LAKE) {
                        int b = (int) (c.eLand * 10);
                        if (b < 0) b = 0; if (b > 9) b = 9;
                        elevHist[b]++;
                    }
                }
            }

            System.out.println("----- Seed " + s + " -----");
            System.out.printf("  Land=%.1f%%  Sea=%.1f%% (deep=%.1f%%, beach=%.1f%%)\n",
                    100.0 * land / total, 100.0 * sea / total, 100.0 * deep / total, 100.0 * beach / total);

            System.out.println("  Per-class (count% | meanELand±sd | meanE meanHeight):");
            for (int t = 0; t < nT; t++) {
                int cnt = cls[t];
                if (cnt == 0) { System.out.printf("    %-10s  0%%\n", TCV[t].name()); continue; }
                double me = sumELand[t] / cnt, se = sumE[t] / cnt, mh = sumH[t] / cnt;
                double sd = Math.sqrt(Math.max(0, sumELand2[t] / cnt - me * me));
                System.out.printf("    %-10s %5.1f%% | %5.3f±%5.3f | %5.3f %6.1f\n",
                        TCV[t].name(), 100.0 * cnt / total, me, sd, se, mh);
            }

            System.out.print("  Land eLand histogram [0,1] by 0.1: ");
            int maxH = 1; for (int v : elevHist) maxH = Math.max(maxH, v);
            for (int b = 0; b < 10; b++) {
                int bars = (int) (40.0 * elevHist[b] / maxH);
                System.out.printf("\n    [%3.1f-%3.1f] %5.1f%% ", b * 0.1, (b + 1) * 0.1, 100.0 * elevHist[b] / Math.max(1, land));
                for (int k = 0; k < bars; k++) System.out.print('#');
            }
            System.out.println();
        }

        // 种子敏感性
        System.out.println("----- Seed sensitivity (same " + N + "x" + N + " region) -----");
        long sa = seeds[0], sb = seeds[1];
        gen.seed(sa);
        int[] ta = new int[total];
        double[] ha = new double[total];
        int k = 0;
        for (int ix = 0; ix < M; ix++) { double wx = ix * step;
            for (int iz = 0; iz < M; iz++) { double wz = iz * step;
                Cell c = gen.sample(wx, wz); ta[k] = c.terrainType.ordinal(); ha[k] = c.height; k++; } }
        gen.seed(sb);
        int diffType = 0; double sumAbsH = 0; int bothLand = 0;
        k = 0;
        for (int ix = 0; ix < M; ix++) { double wx = ix * step;
            for (int iz = 0; iz < M; iz++) { double wz = iz * step;
                Cell c = gen.sample(wx, wz);
                if (ta[k] != c.terrainType.ordinal()) diffType++;
                sumAbsH += Math.abs(ha[k] - c.height);
                if (!TCV[ta[k]].isOcean() && !c.terrainType.isOcean()) bothLand++;
                k++; } }
        System.out.printf("  Seeds %d vs %d: type-diff=%.2f%%  mean|Δheight|=%.2f blocks  (both-land cells=%d)\n",
                sa, sb, 100.0 * diffType / total, sumAbsH / total, bothLand);
        System.out.println("  => 若 type-diff 显著>0，说明引擎对种子敏感。");

        System.out.println("\n=== Diagnosis done ===");
    }
}
