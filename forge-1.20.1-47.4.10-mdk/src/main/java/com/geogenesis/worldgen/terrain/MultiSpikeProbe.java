package com.geogenesis.worldgen.terrain;

/**
 * 一次性探针（2026-08-10）：多种子多 tile 批量扫描"单点尖"。
 *
 * <p>尖刺"有概率生成"且用户世界种子随机 → 多 tile 扫描提高命中率。
 * 对每个 tile 数据区（44×44 wu）逐格检查：
 * <ul>
 *   <li>侵蚀尖：sampleWu.e 比 4 邻 max 高 >0.05 且 delta(=sampleWu.e−sample.e) 显著 >0.03</li>
 *   <li>基础尖：sample.e 比 4 邻 max 高 >0.05 且 delta 小（≈0）</li>
 * </ul>
 * 输出坐标、%96（tile 数据区边界 96 对齐嫌疑）、类型、delta。
 *
 * 用法：gradlew runMultiSpikeProbe "seed1 seed2 ..." [tileCount]
 */
public class MultiSpikeProbe {

    public static void main(String[] args) {
        String seedArg = args.length > 0 ? args[0] : "12345 98765 555 4242 31415";
        int tileCount = args.length > 1 ? Integer.parseInt(args[1]) : 5;
        String[] seeds = seedArg.split(" ");
        int spikesE = 0, spikesB = 0;
        for (String s : seeds) {
            long seed = Long.parseLong(s);
            TerrainParams p = TerrainParams.defaults();
            CellGenerator gen = new CellGenerator(p, p.minY(), p.maxY());
            gen.seed(seed);
            System.out.printf("=== seed=%d ===\n", seed);
            for (int ti = 0; ti < tileCount; ti++) {
                // tile 索引交错分布（覆盖不同区域）
                int tcx = (ti * 3) % 6 - 2, tcz = (ti * 7) % 6 - 2;
                int x0 = tcx * 48, z0 = tcz * 48;
                for (int z = z0 + 2; z < z0 + 46; z += 2) {
                    for (int x = x0 + 2; x < x0 + 46; x += 2) {
                        Cell c = gen.sampleWu(x, z);
                        Cell p0 = gen.sample(x, z);
                        double d = c.e - p0.e;
                        double nE = max4(gen, x, z);
                        if (c.e - nE > 0.05) {
                            if (d > 0.03) {
                                System.out.printf("E-SPIKE (%d,%d): e=%.3f 邻=%.3f delta=%+.3f type=%s x%%96=%d z%%96=%d%n",
                                    x, z, c.e, nE, d, c.terrainType, mod96(x), mod96(z));
                                spikesE++;
                            } else if (p0.e - max4base(gen, x, z) > 0.05) {
                                System.out.printf("B-SPIKE (%d,%d): e=%.3f 邻=%.3f delta=%+.3f type=%s x%%96=%d z%%96=%d%n",
                                    x, z, p0.e, max4base(gen, x, z), d, c.terrainType, mod96(x), mod96(z));
                                spikesB++;
                            }
                        }
                    }
                }
            }
        }
        System.out.printf("TOTAL: E-SPIKE(侵蚀尖)=%d B-SPIKE(基础尖)=%d%n", spikesE, spikesB);
    }

    private static double max4(CellGenerator gen, int x, int z) {
        double a = gen.sampleWu(x, z - 1).e, b = gen.sampleWu(x, z + 1).e;
        double c = gen.sampleWu(x - 1, z).e, d = gen.sampleWu(x + 1, z).e;
        return Math.max(Math.max(a, b), Math.max(c, d));
    }

    private static double max4base(CellGenerator gen, int x, int z) {
        double a = gen.sample(x, z - 1).e, b = gen.sample(x, z + 1).e;
        double c = gen.sample(x - 1, z).e, d = gen.sample(x + 1, z).e;
        return Math.max(Math.max(a, b), Math.max(c, d));
    }

    private static int mod96(int v) {
        int m = v % 96;
        return m < 0 ? m + 96 : m;
    }
}
