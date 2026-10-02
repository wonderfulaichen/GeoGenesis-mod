package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

/**
 * ★ 无限世界有效性的【硬判据】：窗口无关性。
 *
 * <p><b>为什么这是判据</b>：若同一世界坐标点的水状态随"我们看了多大的窗口"而变，
 * 那它就不是无限世界的解，只是裁剪产物（此前所有图都有这个缺陷）。
 * 只有结果<b>收敛</b>到与窗口大小无关时，按需计算才有意义。</p>
 *
 * <p>做法：以同一中心，用一系列递增半径的窗口分别计算平衡水面，
 * 比较中心区域同一批采样点的水面高程。同时记录"未决格"比例随窗口的变化趋势——
 * 未决应随窗口扩大而收缩到只剩真正需要更远上游的点。</p>
 */
public final class WindowIndependenceProbe {

    public static void main(String[] args) {
        long seed = 9139912035078620160L;
        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        double sea = gen.seaLevel();
        double hs = tp.horizontalScale();
        int step = 4;

        // 中心点的世界坐标（块）
        int cx = -140, cz = 137;
        int[] radii = {256, 512, 1024, 2048};

        System.out.printf("=== window independence; centre=(%d,%d) step=%d hs=%.1f sea=%.1f ===%n",
                cx, cz, step, hs, sea);
        double[] previous = null;
        int previousRadius = 0;
        for (int radius : radii) {
            int n = 2 * radius / step + 1;
            double[] bed = new double[n * n];
            for (int i = 0; i < n; i++) {
                double wx = cx - radius + i * step;
                for (int j = 0; j < n; j++) {
                    double wz = cz - radius + j * step;
                    bed[i * n + j] = gen.erodedHeightForRouting(wx / hs, wz / hs);
                }
            }
            boolean[] drains = new boolean[n * n];
            double[] exterior = new double[n * n];
            java.util.Arrays.fill(exterior, Double.NaN);
            int seaCount = 0;
            for (int i = 0; i < n; i++) {
                for (int j = 0; j < n; j++) {
                    int k = i * n + j;
                    if (bed[k] < sea) { drains[k] = true; seaCount++; }
                    // 边框格的窗外水位：由窗口外一格的【真实地形】给出（程序化已知）。
                    if (i == 0 || j == 0 || i == n - 1 || j == n - 1) {
                        int oi = i == 0 ? -1 : i == n - 1 ? n : i;
                        int oj = j == 0 ? -1 : j == n - 1 ? n : j;
                        double outside = gen.erodedHeightForRouting(
                                (cx - radius + oi * step) / hs, (cz - radius + oj * step) / hs);
                        exterior[k] = Math.max(outside, sea);
                    }
                }
            }
            EquilibriumWater.Surface s = EquilibriumWater.compute(bed, n, n, drains, exterior);

            // ★ 采样区必须固定在【世界坐标】上，不能随窗口半径变化：
            //   否则各次数组长度不同，"比较"会静默跳过（本轮实测踩到，比较列全空）。
            int halfBlocks = 64;
            int q = halfBlocks / step;
            int lo = n / 2 - q, hi = n / 2 + q;
            double[] sample = new double[(hi - lo) * (hi - lo)];
            int idx = 0, unresolved = 0;
            for (int i = lo; i < hi; i++) {
                for (int j = lo; j < hi; j++) {
                    int k = i * n + j;
                    sample[idx++] = s.resolved()[k] ? s.level()[k] : Double.NaN;
                    if (!s.resolved()[k]) unresolved++;
                }
            }
            String comparison = "";
            if (previous != null) {
                double worst = 0, sumAbs = 0;
                int compared = 0;
                for (int t = 0; t < sample.length; t++) {
                    if (Double.isNaN(sample[t]) || Double.isNaN(previous[t])) continue;
                    double delta = Math.abs(sample[t] - previous[t]);
                    worst = Math.max(worst, delta);
                    sumAbs += delta;
                    compared++;
                }
                comparison = String.format("  vs r=%d: compared=%d/%d maxDelta=%.4f meanDelta=%.4f",
                        previousRadius, compared, sample.length, worst,
                        compared > 0 ? sumAbs / compared : Double.NaN);
            }
            System.out.printf("r=%5d cells=%7d seaCells=%6d unresolvedCenter=%5d/%d (%.1f%%)%s%n",
                    radius, n * n, seaCount, unresolved, sample.length,
                    100.0 * unresolved / sample.length, comparison);
            previous = sample;
            previousRadius = radius;
        }
        System.out.println("READ: maxDelta 随半径收敛 ⇒ 无限域解成立；持续变大 ⇒ 仍是裁剪产物。");
    }

    private WindowIndependenceProbe() { }
}
