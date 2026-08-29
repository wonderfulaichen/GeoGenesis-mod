package com.geogenesis.worldgen.terrain;

/**
 * 海岸线剖面诊断：沿直线采样，输出 e 穿过 0（海岸线）附近的
 * e/eOcean/eLand/cont/cEdge/类型权重，验证海岸线是否退化为 c 等值线（平滑圆弧）。
 */
public final class CoastlineProfileProbe {

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        TerrainParams p = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(p, -64, 320);
        gen.seed(seed);

        System.out.println("=== CoastlineProfileProbe seed=" + seed + " ===");
        System.out.printf("continentScale=%.1f coastlineWarpAmp=%.3f%n",
            p.continentScale(), p.coastlineWarpAmp());
        System.out.printf("oceanFadeStart=%.3f landRampEnd=%.3f coastLoc=%.3f%n",
            p.oceanFadeStart(), p.landRampEnd(), p.coastLoc());

        // 沿多条直线扫描，找 e 穿过 0 的位置，打印剖面
        double[] angles = {0, Math.PI / 4, Math.PI / 2, 3 * Math.PI / 4};
        final int R = 2000, STEP = 8;

        int[] lt = {TerrainClass.PLAIN.ordinal(), TerrainClass.HILLS.ordinal(),
            TerrainClass.MOUNTAINS.ordinal(), TerrainClass.PLATEAU.ordinal(), TerrainClass.BASIN.ordinal()};
        String[] tn = {"PLAIN", "HILLS", "MOUNT", "PLAT", "BASIN"};

        for (double angle : angles) {
            double dx = Math.cos(angle), dz = Math.sin(angle);
            boolean found = false;
            int crossings = 0;
            double prevE = Double.NaN;
            double prevDist = 0;

            for (int dist = -R; dist <= R && crossings < 3; dist += STEP) {
                double x = dist * dx, z = dist * dz;
                Cell c = gen.sample(x, z);
                double e = c.e;

                if (!Double.isNaN(prevE) && ((prevE < 0 && e >= 0) || (prevE >= 0 && e < 0))) {
                    crossings++;
                    found = true;
                    System.out.printf("%n--- 直线角 %.1f° 海岸线穿越 #%d @ dist=%d (x=%.0f,z=%.0f) ---%n",
                        Math.toDegrees(angle), crossings, dist, x, z);
                    // 打印穿越点前后各 2 个采样
                    for (int d2 = Math.max(-R, dist - STEP * 2); d2 <= Math.min(R, dist + STEP * 2); d2 += STEP) {
                        Cell cc = gen.sample(d2 * dx, d2 * dz);
                        double[] w = cc.typeWeights;
                        System.out.printf("  d=%5d e=%7.4f eOcean=%7.4f eLand=%7.4f oceanW=%5.3f cEdge=%6.3f dom=%s w[%s/%s/%s/%s/%s]=[%.2f,%.2f,%.2f,%.2f,%.2f]%n",
                            d2, cc.e, cc.eOcean, cc.eLand, cc.blendCont, cc.coastCoord,
                            cc.terrainType,
                            tn[0], tn[1], tn[2], tn[3], tn[4],
                            w[lt[0]], w[lt[1]], w[lt[2]], w[lt[3]], w[lt[4]]);
                    }
                }
                prevE = e;
                prevDist = dist;
            }
            if (!found) System.out.printf("%n--- 直线角 %.1f° 无海岸线穿越 ---%n", Math.toDegrees(angle));
        }

        System.out.println("\n=== 诊断判读（2026-08-06 海陆类型化后） ===");
        System.out.println("oceanW = 海洋类型权重和（OCEAN+DEEP_OCEAN）。穿越点 oceanW≈0.4~0.7 且");
        System.out.println("dom 在 OCEAN↔陆地翻转 → 海陆边界 = Voronoi 类型竞争（与类型过渡同构）✓");
        System.out.println("穿越点 cEdge>0（内陆）→ 内陆洼地积水成湖（自然）✓");
    }
}
