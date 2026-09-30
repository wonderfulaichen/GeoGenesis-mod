package com.geogenesis.worldgen.hydrology.model;

/**
 * 独立参照（oracle）—— P1 验收的"第二种算法"。
 *
 * <p><b>为什么必须有它</b>：旧验证据 plan §6 的问题是"重复生产公式"（如
 * {@code HydroCoreProbe} 用与生产相同的最低邻边公式"复核"最低溢口）。那种判据
 * 只能证明代码自洽，不能证明物理正确。本类提供<b>与求解器无关</b>的参照：
 * 只做定义式求和/二分，不含任何时间推进或通量计算。</p>
 */
public final class ModelOracle {

    private ModelOracle() { }

    /** 定义式蓄水体积：{@code Σ A·max(0, η − z)}（直接求和，独立于求解器）。 */
    public static double volumeAtLevel(WaterGrid g, double eta) {
        double v = 0;
        for (double z : g.bed) v += Math.max(0, eta - z);
        return v * g.cellArea();
    }

    /**
     * 反解平衡水面：给定蓄水体积 {@code V}，二分求 {@code η*} 使
     * {@code volumeAtLevel(η*) = V}（封闭域、静水 ⇒ 水面必为水平面）。
     *
     * @return 平衡水面高程；超出可蓄范围时返回床面最低/最高端
     */
    public static double levelForVolume(WaterGrid g, double v) {
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (double z : g.bed) { lo = Math.min(lo, z); hi = Math.max(hi, z); }
        if (v <= 0) return lo;
        double top = hi + v / g.cellArea() + 1.0;        // 保证上界足够
        for (int it = 0; it < 200; it++) {
            double mid = 0.5 * (lo + top);
            if (volumeAtLevel(g, mid) < v) lo = mid; else top = mid;
        }
        return 0.5 * (lo + top);
    }

    /**
     * 全路径最低溢口（minimax）：从盆地内部某格出发，到域外或到给定"外部参考水位"的
     * 最低"路径最高点"。
     *
     * <p>与生产实现<b>不同源</b>：这里用 Dijkstra（以"路径最高点"为代价），
     * 不复用任何邻边枚举公式。</p>
     *
     * @param seed 起点格索引
     * @param outLevel 域外参考水位（边界外视为可自由排向该水位）
     * @return 最低溢口高度；若无处可排返回 {@code +∞}
     */
    public static double minimaxSpill(WaterGrid g, int seed, double outLevel) {
        int n = g.cells();
        double[] best = new double[n];
        java.util.Arrays.fill(best, Double.MAX_VALUE);
        java.util.PriorityQueue<double[]> pq =
                new java.util.PriorityQueue<>((a, b) -> Double.compare(a[0], b[0]));
        best[seed] = g.bed[seed];
        pq.add(new double[]{g.bed[seed], seed});
        int[] dx = {1, -1, 0, 0}, dz = {0, 0, 1, -1};
        while (!pq.isEmpty()) {
            double[] cur = pq.poll();
            double cost = cur[0];
            int k = (int) cur[1];
            if (cost > best[k]) continue;
            int i = g.ix(k), j = g.iz(k);
            // 边界格 ⇒ 可排水到域外，代价 = max(cost, outLevel)
            if (g.isBorder(i, j)) return Math.max(cost, outLevel);
            for (int t = 0; t < 4; t++) {
                int ni = i + dx[t], nj = j + dz[t];
                if (!g.inside(ni, nj)) continue;
                int nk = g.idx(ni, nj);
                double nc = Math.max(cost, g.bed[nk]);
                if (nc < best[nk] - 1e-15) {
                    best[nk] = nc;
                    pq.add(new double[]{nc, nk});
                }
            }
        }
        return Double.POSITIVE_INFINITY;
    }
}
