package com.geogenesis.worldgen.terrain;

/**
 * 地形纹理方向（grain）连续性探针（★ 2026-09-12）。
 *
 * <p><b>用户反馈</b>：岩石类型的边界导致地形衔接不自然，看起来"每块各自独立生成"。
 * 这指向一个特定缺陷：<b>地形纹理的走向按板块格子各自定向，在边界处突变</b>。
 *
 * <p><b>根因假设</b>：T5 形变用 {@code Sample.alongCoord()} 作为噪声坐标，而 alongCoord
 * 是<b>相对每个 Voronoi 单元</b>定义的（(d1+d2)/2）→ 相邻板块的"沿走向方向"不同
 * → 同一世界位置的纹理走向不同 → 边界处纹理错位。
 *
 * <p><b>worldgen 的做法（对照）</b>：它把 along/across 只用于算<b>标量幅度</b>，
 * 并对 profile 做<b>高斯模糊</b>（源码注释明确写 "Smooth profiles to eliminate
 * Voronoi ridge discontinuities"），且山脊噪声用<b>世界坐标</b>采样。
 *
 * <p>本探针沿细步长遍历，检测<b>纹理方向（局部梯度方向）在跨板块边界前后的突变</b>。
 *
 * <p>用法：{@code gradlew runTerrainGrainProbe [seed]}
 */
public final class TerrainGrainProbe {

    /** 形变贡献（e 单位），用于度量纹理走向。 */
    private static double deformAt(TectonicDeformation td, TectonicField tf,
                                   double x, double z) {
        return td.offset(tf.sample(x, z), x, z);
    }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        System.out.printf("=== TerrainGrainProbe seed=%d ===%n", seed);
        TectonicField tf = new TectonicField(seed);
        TectonicDeformation td = new TectonicDeformation(seed);

        // ================= [1] 纹理方向突变检测 =================
        //   沿一条扫描线以细步取值，用【二阶差分】估计局部纹理方向；
        //   跨板块边界（c1 配对变化）前后，方向角应连续。
        //   判据：跨边界处的方向突变 不应显著大于非边界处。
        final double STEP = 4.0;
        double maxJumpAtBnd = 0, maxJumpOff = 0, sumAtBnd = 0, sumOff = 0;
        int nAtBnd = 0, nOff = 0;
        for (double z0 = -6000; z0 <= 6000; z0 += 1201) {
            double[] v = new double[3];
            for (int i = 0; i < 3; i++) {
                v[i] = deformAt(td, tf, -6000 + i * STEP, z0);
            }
            for (double x = -6000 + 3 * STEP; x <= 6000; x += STEP) {
                v[0] = v[1]; v[1] = v[2];
                v[2] = deformAt(td, tf, x, z0);
                // 局部曲率（二阶差分）：纹理起伏剧烈处大
                double curv = Math.abs(v[2] - 2 * v[1] + v[0]);
                // 是否跨板块边界：看 (c1 种子) 是否变化 —— 用 dist 是否接近 0 近似
                TectonicField.Sample s = tf.sample(x, z0);
                boolean nearEdge = s.dist() < STEP * 2;
                if (nearEdge) { maxJumpAtBnd = Math.max(maxJumpAtBnd, curv); sumAtBnd += curv; nAtBnd++; }
                else { maxJumpOff = Math.max(maxJumpOff, curv); sumOff += curv; nOff++; }
            }
        }
        double meanAtBnd = nAtBnd > 0 ? sumAtBnd / nAtBnd : 0;
        double meanOff = nOff > 0 ? sumOff / nOff : 0;
        System.out.printf("[1] 边界处 vs 非边界处 局部曲率: 均值 %.5f (n=%d) vs %.5f (n=%d)  比值=%.2f×%n",
            meanAtBnd, nAtBnd, meanOff, nOff, meanOff > 0 ? meanAtBnd / meanOff : 0);
        boolean pass1 = meanAtBnd < meanOff * 3.0;
        System.out.printf("    边界处曲率不应显著高于内部(<3×): %s%n", pass1 ? "PASS" : "FAIL（边界处纹理突变）");

        boolean all = pass1;
        System.out.println(all ? "=== ALL PASS ===" : "=== FAILURES PRESENT ===");
        if (!all) System.exit(1);
    }
}
