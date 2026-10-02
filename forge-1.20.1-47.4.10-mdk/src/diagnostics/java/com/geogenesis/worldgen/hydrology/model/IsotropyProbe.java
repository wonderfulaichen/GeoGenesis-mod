package com.geogenesis.worldgen.hydrology.model;

/**
 * 各向同性检验：把床面<b>旋转任意角度</b>后重采样，看排水方向是否随之旋转。
 *
 * <p><b>为什么需要它</b>：{@link FlatTieDirectionProbe} 只做了 90° 旋转，
 * 而 90° 是格点的对称操作 ⇒ "旋转后直方图不变"也可能只是格点对称的必然结果，
 * <b>不能</b>据此判断各向异性。要检出格点偏置，必须用<b>非格点对齐的角度</b>
 * （如 30°、45°+ε），或直接检验方向分布本身是否均匀。</p>
 *
 * <p>本探针做两件事：</p>
 * <ol>
 *   <li><b>单一洼地（径向收敛）</b>：平地上一个排水终端 ⇒ 理想流向应<b>径向指向终端</b>。
 *       统计"流向与真实径向的夹角"分布：真正各向同性应集中在 0°，且与格轴无关。</li>
 *   <li><b>方向直方图熵</b>：把方向按 8 方位分桶。若某桶显著占优（尤其与坐标轴对齐的桶），
 *       即为格点偏置。</li>
 * </ol>
 */
public final class IsotropyProbe {

    public static void main(String[] args) {
        int n = 96;
        double[] bed = new double[n * n];
        boolean[] drains = new boolean[n * n];
        double[] exterior = new double[n * n];
        java.util.Arrays.fill(exterior, Double.NaN);
        int dcx = n / 2, dcz = n / 2;
        drains[dcx * n + dcz] = true;      // 唯一排水终端（平地上的一个洞）

        EquilibriumWater.Surface s = EquilibriumWater.compute(bed, n, n, drains, exterior);
        int[] parent = s.parent();

        // 1) 流向 vs 真实径向的夹角
        int[] aligned = new int[181];      // 0..180 度
        int counted = 0;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                int k = i * n + j, p = parent[k];
                if (p < 0) continue;
                double ti = dcx - i, tj = dcz - j;              // 指向终端的真实径向
                double fi = p / n - i, fj = p % n - j;          // 实际流向
                double tn = Math.hypot(ti, tj), fn = Math.hypot(fi, fj);
                if (tn < 2 || fn == 0) continue;
                double cos = (ti * fi + tj * fj) / (tn * fn);
                cos = Math.max(-1, Math.min(1, cos));
                aligned[(int) Math.round(Math.toDegrees(Math.acos(cos)))]++;
                counted++;
            }
        }
        // 2) 8 方位直方图
        int[] octant = new int[8];
        for (int k = 0; k < n * n; k++) {
            int p = parent[k];
            if (p < 0) continue;
            double fi = p / n - k / n, fj = p % n - k % n;
            double ang = Math.toDegrees(Math.atan2(fj, fi));
            if (ang < 0) ang += 360;
            octant[(int) (ang / 45) % 8]++;
        }

        System.out.println("=== isotropy of flat-bed drainage (single sink at centre) ===");
        int deg0 = 0, deg45 = 0, deg90 = 0, degGt90 = 0;
        for (int d = 0; d <= 180; d++) {
            if (aligned[d] == 0) continue;
            if (d <= 22) deg0 += aligned[d];
            else if (d <= 67) deg45 += aligned[d];
            else if (d <= 112) deg90 += aligned[d];
            else degGt90 += aligned[d];
        }
        System.out.printf("  flow vs radial: within22deg=%d  ~45deg=%d  ~90deg=%d  worse=%d  (total=%d)%n",
                deg0, deg45, deg90, degGt90, counted);
        System.out.printf("  radial-aligned fraction = %.1f%% (ideal near 100%% for a single sink on flat bed)%n",
                100.0 * deg0 / Math.max(1, counted));
        String[] names = {"E", "SE", "S", "SW", "W", "NW", "N", "NE"};
        StringBuilder sb = new StringBuilder("  octant histogram: ");
        for (int i = 0; i < 8; i++) sb.append(names[i]).append('=').append(octant[i]).append("  ");
        System.out.println(sb);
        int max = 0, min = Integer.MAX_VALUE;
        for (int v : octant) { max = Math.max(max, v); min = Math.min(min, v); }
        System.out.printf("  max/min octant ratio = %.2f (ideal ~1.0; axis-aligned excess indicates grid bias)%n",
                max / (double) Math.max(1, min));
    }

    private IsotropyProbe() { }
}
