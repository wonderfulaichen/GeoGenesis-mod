package com.geogenesis.worldgen.terrain;

import java.util.HashMap;
import java.util.Map;

/**
 * 临时诊断：统计全 TerrainClass 分布（重点海洋类型：OCEAN/DEEP_OCEAN/SHELF/RIDGE/SEAMOUNT）。
 * 验证海山（SEAMOUNT）是否生成。验证后删除。
 */
public final class OceanTypeProbe {

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        TerrainParams p = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(p, -64, 320);
        gen.seed(seed);

        final int HALF = 4096, STEP = 32; // 8192×8192 大区域稳定统计
        Map<TerrainClass, Integer> count = new HashMap<>();
        int seamount = 0, ridge = 0, deep = 0, ocean = 0, shelf = 0;
        int ampOk = 0, ampOkDeep = 0, eDeep = 0;
        double maxSeamountAmp = 0, maxRidgeAmp = 0;
        long t0 = System.currentTimeMillis();
        for (int z = -HALF; z < HALF; z += STEP) {
            for (int x = -HALF; x < HALF; x += STEP) {
                Cell c = gen.sample(x, z);
                TerrainClass t = c.terrainType;
                count.merge(t, 1, Integer::sum);
                if (t == TerrainClass.SEAMOUNT) seamount++;
                else if (t == TerrainClass.SUBMARINE_RIDGE) ridge++;
                else if (t == TerrainClass.DEEP_OCEAN) deep++;
                else if (t == TerrainClass.OCEAN) ocean++;
                else if (t == TerrainClass.CONTINENTAL_SHELF) shelf++;
                if (c.oceanFeat != null) {
                    double sa = c.oceanFeat.seamount;
                    double ra = c.oceanFeat.ridge;
                    if (sa > maxSeamountAmp) maxSeamountAmp = sa;
                    if (ra > maxRidgeAmp) maxRidgeAmp = ra;
                    if (sa > 0.02) ampOk++;
                    if (sa > 0.02 && c.e < -0.08) ampOkDeep++;
                }
                if (c.e < -0.08) eDeep++;
            }
        }
        long ms = System.currentTimeMillis() - t0;
        System.out.println("=== OceanTypeProbe seed=" + seed + " region=" + (2 * HALF) + "x" + (2 * HALF)
                + " step=" + STEP + " time=" + ms + "ms ===");
        count.forEach((t, n) ->
            System.out.printf("%-20s %6d (%.2f%%)%n", t.name(), n, 100.0 * n / 65536));
        System.out.println("--- 海洋细分 ---");
        System.out.printf("SEAMOUNT=%d  RIDGE=%d  DEEP_OCEAN=%d  OCEAN=%d  SHELF=%d%n",
                seamount, ridge, deep, ocean, shelf);
        System.out.printf("--- 中间量诊断 ---%nseamountAmp>0.02: %d 个; 其中 e<-0.08: %d 个; e<-0.08 总样本: %d%n",
                ampOk, ampOkDeep, eDeep);
        System.out.printf("maxSeamountAmp=%.4f  maxRidgeAmp=%.4f%n", maxSeamountAmp, maxRidgeAmp);
    }
}
