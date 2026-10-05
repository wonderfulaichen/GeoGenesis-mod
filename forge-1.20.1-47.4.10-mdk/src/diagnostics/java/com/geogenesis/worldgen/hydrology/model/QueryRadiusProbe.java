package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

/**
 * ★ 无限世界按需查询的可行性：查一个点，需要多大的域？
 *
 * <p><b>为什么这是核心判据</b>：世界无界 ⇒ 不能一次算完。若"查一点"必须展开数千格的域，
 * 按需计算就不成立，无限世界也就无从谈起；若只需几十~几百格，就可以分片。
 * 此前 {@code WindowIndependenceProbe} 只证明<b>固定中心</b>在窗口放大时收敛，
 * 没有回答<b>任意单点所需的最小域</b>。</p>
 *
 * <p><b>做法</b>：在地形上取若干测试点（海岸/内陆/山地/盆地各若干），
 * 对每个点用递增半径的窗口计算该点的水面高程，找到使结果稳定的最小半径。</p>
 *
 * <p><b>判据</b>：报告所需半径的分布。若中位数远小于世界尺度，
 * 则"按需 + 有限域"可行；若接近或超过地形特征尺度（数千格），则不可行，
 * 必须改用不依赖远处出口的水位定义（如连续场）。</p>
 */
public final class QueryRadiusProbe {

    public static void main(String[] args) {
        long seed = 9139912035078620160L;
        int step = 4;
        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        double sea = gen.seaLevel(), hs = tp.horizontalScale();

        // 测试点：跨不同地理位置，块坐标。（(-140,137) 已实测 256 格即收敛）
        int[][] probes = {
                {-140, 137},    // 原窗口中心（近海）
                {-600, 137},    // 向西内陆
                {400, 137},     // 向东
                {-2000, 500},   // 更远
        };
        int[] radii = {128, 256, 512, 1024};

        System.out.println("=== required domain radius for a single-point water level ===");
        System.out.printf("step=%d  sea=%.1f  (radii in blocks)%n", step, sea);
        System.out.println("probe            " + java.util.Arrays.toString(radii) + "   -> convergedAt");
        int[] convergedAt = new int[probes.length];
        for (int p = 0; p < probes.length; p++) {
            int px = probes[p][0], pz = probes[p][1];
            double previous = Double.NaN;
            int need = -1;
            StringBuilder row = new StringBuilder();
            for (int radius : radii) {
                int n = 2 * radius / step + 1;
                double[] bed = new double[n * n];
                boolean[] seaMask = new boolean[n * n];
                double[] exterior = new double[n * n];
                java.util.Arrays.fill(exterior, Double.NaN);
                for (int i = 0; i < n; i++) {
                    for (int j = 0; j < n; j++) {
                        int k = i * n + j;
                        bed[k] = gen.sampleWu(
                                (px - radius + i * step) / hs, (pz - radius + j * step) / hs).height;
                        seaMask[k] = bed[k] < sea;
                        if (i == 0 || j == 0 || i == n - 1 || j == n - 1) {
                            int oi = i == 0 ? -1 : i == n - 1 ? n : i;
                            int oj = j == 0 ? -1 : j == n - 1 ? n : j;
                            exterior[k] = Math.max(gen.sampleWu(
                                    (px - radius + oi * step) / hs,
                                    (pz - radius + oj * step) / hs).height, sea);
                        }
                    }
                }
                double level;
                try {
                    EquilibriumWater.Surface s = EquilibriumWater.compute(bed, n, n, seaMask, exterior);
                    int center = (n / 2) * n + n / 2;
                    level = s.level()[center];
                } catch (IllegalArgumentException noTerminal) {
                    // ★ 窗口内完全没有海格 ⇒ 没有真实排水终端。
                    //   这不是探针缺陷，而是方法本身的边界：内陆窗口无法用"海"定水位。
                    //   正是 FreeTerraForged 用连续场、PL-RGA 用"板边界不放河"所回避的问题。
                    level = Double.NaN;
                }
                row.append(Double.isNaN(level) ? "      NO-SEA" : String.format("%9.3f", level));
                if (Double.isFinite(previous) && Double.isFinite(level)
                        && Math.abs(level - previous) < 1e-9 && need < 0) {
                    need = radius;
                }
                previous = level;
            }
            convergedAt[p] = need;
            System.out.printf("(%5d,%5d) %s   -> %s%n", px, pz, row,
                    need < 0 ? "no stable level (no sea in window, or keeps changing)" : need + " blocks");
        }
        int max = 0, cnt = 0, noSea = 0;
        for (int i = 0; i < probes.length; i++) {
            int v = convergedAt[i];
            if (v > 0) { max = Math.max(max, v); cnt++; }
            else if (v < 0) noSea++;
        }
        System.out.printf("converged %d/%d probes; worst radius = %d blocks (cell=%d blocks); no-stable %d%n",
                cnt, probes.length, max, step, noSea);
        System.out.println("READ: 所需半径 << 地形特征尺度 ⇒ 按需查询可行；接近世界尺度 ⇒ 不可行。");
        System.out.println("NOTE: NO-SEA = 窗口内无海 ⇒ 该方法在纯内陆域无定义（需外部出口或连续场）。");
    }

    private QueryRadiusProbe() { }
}
