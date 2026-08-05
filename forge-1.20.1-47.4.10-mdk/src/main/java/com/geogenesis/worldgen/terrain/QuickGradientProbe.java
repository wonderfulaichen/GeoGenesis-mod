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

        final int R = 200;
        double maxD = 0, maxDE = 0;
        int mx = 0, mz = 0;
        Cell maxC = null;
        long t0 = System.currentTimeMillis();
        for (int z = -R; z < R; z++) {
            double prevH = Double.NaN;
            for (int x = -R; x < R; x++) {
                Cell c = gen.sample(x, z);
                double hv = c.height;
                if (!Double.isNaN(prevH)) {
                    double d = Math.abs(hv - prevH);   // 最终地形 Y 梯度（块/格）
                    if (d > maxD) { maxD = d; mx = x; mz = z; maxC = c; }
                }
                prevH = hv;
            }
        }
        // 附带统计 eLand 中间量梯度作对照（已知海陆边界处中间量跳变是假断裂）
        long ms = System.currentTimeMillis() - t0;
        System.out.printf("=== QuickGradientProbe seed=%d region=%dx%d step=1 (最终地形 height) ===%n", seed, 2 * R, 2 * R);
        System.out.printf("maxDeltaY=%.2f 块/格 at (%d,%d)  time=%dms%n", maxD, mx, mz, ms);
        if (maxC != null) {
            StringBuilder sb = new StringBuilder();
            sb.append("dominant=").append(TypeLandShape.dominantFromWeights(maxC.typeWeights));
            sb.append("  eLand=").append(String.format("%.4f", maxC.eLand));
            sb.append("  height=").append(String.format("%.1f", maxC.height));
            System.out.println(sb);
        }
        System.out.println("阈值参考: 19 块/格为严重断裂（= e 梯度 0.05）");
    }
}
