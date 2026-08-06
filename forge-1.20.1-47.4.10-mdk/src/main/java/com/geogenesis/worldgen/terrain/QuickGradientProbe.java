package com.geogenesis.worldgen.terrain;

/**
 * 临时诊断：用实际引擎（CellGenerator.sample）按 1 格步长扫描，
 * 验证 eLand 相邻格最大梯度（断裂）。验证后删除。
 */
public final class QuickGradientProbe {

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        TerrainParams p = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(p, -64, 320);
        gen.seed(seed);

        final int R = 1000, STEP = 2; // 2000×2000 区域 step2 定位硬边
        java.util.TreeMap<Double, String> top = new java.util.TreeMap<>(); // deltaY → 描述
        java.util.TreeMap<Double, String> typeEdge = new java.util.TreeMap<>(); // 类型边界梯度
        long t0 = System.currentTimeMillis();
        for (int z = -R; z < R; z += STEP) {
            double prevH = Double.NaN;
            TerrainClass prevDom = null;
            Cell prevC = null;
            for (int x = -R; x < R; x += STEP) {
                Cell c = gen.sample(x, z);
                double hv = c.height;
                TerrainClass dom = TypeLandShape.dominantFromWeights(c.typeWeights);
                if (!Double.isNaN(prevH)) {
                    double d = Math.abs(hv - prevH);
                    if (top.size() < 10 || d > top.firstKey()) {
                        String desc = String.format("(%d,%d) dY=%.1f eLand=%.3f h=%.1f dom=%s river=%s lake=%s",
                                x, z, d, c.eLand, c.height, dom, c.riverMask, c.lakeMask);
                        top.put(d, desc);
                        if (top.size() > 10) top.remove(top.firstKey());
                    }
                    // 类型边界（dominant 变化）处的梯度
                    if (prevDom != dom) {
                        if (typeEdge.size() < 6 || d > typeEdge.firstKey()) {
                            String desc = String.format("(%d,%d) dY=%.1f %s->%s", x, z, d, prevDom, dom);
                            typeEdge.put(d, desc);
                            if (typeEdge.size() > 6) typeEdge.remove(typeEdge.firstKey());
                        }
                    }
                }
                prevH = hv;
                prevDom = dom;
            }
        }
        long ms = System.currentTimeMillis() - t0;
        // [DIAG] 硬边来源验证：权重剖面（z=-330，每 4 格打印 5 类权重）
        int[] lt = {TerrainClass.PLAIN.ordinal(), TerrainClass.HILLS.ordinal(),
            TerrainClass.MOUNTAINS.ordinal(), TerrainClass.PLATEAU.ordinal(), TerrainClass.BASIN.ordinal()};
        for (int xx = 740; xx <= 800; xx += 4) {
            Cell c = gen.sample(xx, -330);
            double[] w = c.typeWeights;
            System.out.printf("  x=%d eLand=%.4f w[P,H,M,Pl,B]=[%.3f,%.3f,%.3f,%.3f,%.3f] dom=%s%n",
                xx, c.eLand, w[lt[0]], w[lt[1]], w[lt[2]], w[lt[3]], w[lt[4]],
                TypeLandShape.dominantFromWeights(w));
        }
        int ccx = Math.floorDiv(760, 16), ccz = Math.floorDiv(-330, 16);
        float[][] d = gen.getErosionTile(ccx, ccz);
        float dMin = Float.MAX_VALUE, dMax = -Float.MAX_VALUE;
        for (float[] row : d) for (float v : row) { if (v < dMin) dMin = v; if (v > dMax) dMax = v; }
        System.out.printf("  erosion tile(%d,%d) delta: min=%.4f max=%.4f%n", ccx, ccz, dMin, dMax);
        System.out.printf("=== QuickGradientProbe seed=%d region=%dx%d step=%d ===%n", seed, 2 * R, 2 * R, STEP);
        System.out.printf("time=%dms  类型边界最大梯度(Top-6):%n", ms);
        typeEdge.descendingMap().forEach((dd, desc) -> System.out.println("  " + desc));
        System.out.println("--- 全局最大梯度(Top-10):");
        top.descendingMap().forEach((dd, desc) -> System.out.println("  " + desc));
        System.out.println("阈值参考: 19 块/格为严重断裂; 3~8 块/格为陡坡（视觉硬边候选）");
    }
}
