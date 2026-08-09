package com.geogenesis.worldgen.terrain;

/**
 * 一次性探针（2026-08-10）：单 tile 内定位"单点尖 delta"并判定骨架/液滴贡献。
 *
 * <p>只生成 1-2 个 tile（快），扫描 delta 数组找单点尖：
 * <ul>
 *   <li>delta 单点尖 = 中心比 4 邻高 >0.06（e）</li>
 *   <li>输出尖点坐标（含 96wu 对齐性=chunk 边界嫌疑）、base 场 e（陡峭度）、
 *       骨架 delta 单独值（开骨架关液滴）vs 全量 delta</li>
 * </ul>
 *
 * 用法：gradlew runRidgeSpikeProbe [seed] [tileX] [tileZ]
 */
public class RidgeSpikeProbe {

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        int tcx = args.length > 1 ? Integer.parseInt(args[1]) : 0;
        int tcz = args.length > 2 ? Integer.parseInt(args[2]) : 0;
        TerrainParams p = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(p, p.minY(), p.maxY());
        gen.seed(seed);

        System.out.printf("=== RidgeSpikeProbe seed=%d tile=(%d,%d) ===\n", seed, tcx, tcz);
        // 用 sampleWu 逐格扫描：delta = sampleWu.e - sample.e（公开 API，tile 缓存命中后快）
        int x0 = tcx * 48, z0 = tcz * 48; // tile 数据区原点（wu）
        int spikes = 0;
        for (int z = z0 + 2; z < z0 + 46; z++) {
            for (int x = x0 + 2; x < x0 + 46; x++) {
                Cell c = gen.sampleWu(x, z);
                Cell p0 = gen.sample(x, z);
                double d = c.e - p0.e;
                double up = gen.sampleWu(x, z - 1).e, down = gen.sampleWu(x, z + 1).e;
                double lf = gen.sampleWu(x - 1, z).e, rt = gen.sampleWu(x + 1, z).e;
                double n = Math.max(Math.max(up, down), Math.max(lf, rt));
                double maxE = Math.max(c.e, n);
                if (d - (c.e - n) > 0.06 && d > 0.03) { // delta 单点尖且本身显著
                    System.out.printf("SPIKE (%d,%d): e=%.3f 邻max=%.3f | delta=%+.3f | baseE=%.3f | x%%96=%d z%%96=%d type=%s%n",
                        x, z, c.e, n, d, p0.e, mod96(x), mod96(z), c.terrainType);
                    if (++spikes >= 10) break;
                }
            }
            if (spikes >= 10) break;
        }
        System.out.println("spikes=" + spikes + " (0 = 该 tile 数据区无单点尖 delta)");
    }

    private static int mod96(int v) {
        int m = v % 96;
        return m < 0 ? m + 96 : m;
    }

    private static float min(float[][] a) {
        float m = Float.MAX_VALUE;
        for (float[] row : a) for (float v : row) m = Math.min(m, v);
        return m;
    }

    private static float max(float[][] a) {
        float m = -Float.MAX_VALUE;
        for (float[] row : a) for (float v : row) m = Math.max(m, v);
        return m;
    }
}
