package com.geogenesis.worldgen.terrain;

/**
 * 一次性探针（2026-08-10）：尖刺点地形剖面分解。
 * 对 SpikeLocateProbe 找到的尖刺坐标，沿 X/Z 每 2wu 采样 e/eLand/类型权重，
 * 判定形状：单点凸起（真尖刺）vs 陡崖边缘（单调过渡）vs 丘顶（对称圆滑）。
 */
public class SpikeProfileProbe {

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        TerrainParams p = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(p, p.minY(), p.maxY());
        gen.seed(seed);

        // SpikeLocateProbe 找到的尖刺坐标
        int[][] spikes = {
            {-336, -1168}, {-200, -1128}, {-48, -1112},
            {-344, -1072}, {-296, -1064}, {-320, -1016}
        };
        System.out.printf("=== SpikeProfileProbe seed=%d ===\n", seed);
        for (int[] s : spikes) {
            int x0 = s[0], z0 = s[1];
            System.out.printf("--- spike (%d,%d) ---\n", x0, z0);
            // X 剖面（z 固定）
            StringBuilder xe = new StringBuilder("X e:  ");
            StringBuilder xw = new StringBuilder("X w:  ");
            for (int dx = -16; dx <= 16; dx += 4) {
                Cell c = gen.sample(x0 + dx, z0);
                xe.append(String.format("%.3f ", c.e));
                int pi = TerrainClass.PLATEAU.ordinal();
                xw.append(String.format("%.2f ", c.typeWeights != null && c.typeWeights.length > pi ? c.typeWeights[pi] : -1));
            }
            System.out.println(xe);
            System.out.println(xw);
            // Z 剖面（x 固定）
            StringBuilder ze = new StringBuilder("Z e:  ");
            StringBuilder zw = new StringBuilder("Z w:  ");
            for (int dz = -16; dz <= 16; dz += 4) {
                Cell c = gen.sample(x0, z0 + dz);
                ze.append(String.format("%.3f ", c.e));
                int pi = TerrainClass.PLATEAU.ordinal();
                zw.append(String.format("%.2f ", c.typeWeights != null && c.typeWeights.length > pi ? c.typeWeights[pi] : -1));
            }
            System.out.println(ze);
            System.out.println(zw);
        }
        System.out.println("判读：对称单点凸起=真尖刺；一侧陡降=崖边；权重渐变伴随=类型过渡带");
    }
}
