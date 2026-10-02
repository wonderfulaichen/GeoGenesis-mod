package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

/**
 * ★ 决定性判据：所需域半径是否随【到海洋的距离】增长？
 *
 * <p><b>为什么这是成败判据</b>：平衡水位方法必须以"真实海格"为排水终端。
 * 若某点深处大陆内部、距海 R 格，窗口就必须大到能包含海，
 * 于是<b>所需半径 ≥ 到海距离</b>。若 R 可达数千~上万格，
 * "每 chunk 固定成本"就不成立，无限世界的按需查询随之破产。</p>
 *
 * <p>做法：粗扫描定位海洋 → 挑距海递增的陆地点 → 对每点用递增半径求中心水位，
 * 找到结果稳定的最小半径，与"到海距离"并列输出。</p>
 *
 * <p><b>本探针只测到 radius=2048 / distToOcean≈4000</b>（更大半径的格数会到千万级，
 * 单次采样不可接受）。因此结论须标注外推边界，不能声称"任意内陆都测过"。</p>
 */
public final class InlandRadiusProbe {

    private static final int SCAN = 4000, GS = 100;
    private static final int STEP = 4;
    private static final int[] RADII = {256, 512, 1024, 2048};

    public static void main(String[] args) {
        long seed = 9139912035078620160L;
        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        double sea = gen.seaLevel(), hs = tp.horizontalScale();

        int m = 2 * SCAN / GS + 1;
        boolean[] isSea = new boolean[m * m];
        for (int i = 0; i < m; i++) {
            for (int j = 0; j < m; j++) {
                double h = gen.erodedHeightForRouting(
                        (-SCAN + i * GS) / hs, (-SCAN + j * GS) / hs);
                isSea[i * m + j] = h < sea;
            }
        }
        int seaCells = 0;
        for (boolean b : isSea) if (b) seaCells++;
        System.out.printf("coarse scan %dx%d @ %d blocks; seaCells=%d (%.1f%%)%n",
                m, m, GS, seaCells, 100.0 * seaCells / (m * (double) m));
        if (seaCells == 0) throw new IllegalStateException("no sea in scan window");

        java.util.List<int[]> candidates = new java.util.ArrayList<>();
        for (int i = 0; i < m; i++) {
            for (int j = 0; j < m; j++) {
                if (isSea[i * m + j]) continue;
                int best = Integer.MAX_VALUE;
                for (int a = 0; a < m; a++) {
                    for (int b = 0; b < m; b++) {
                        if (!isSea[a * m + b]) continue;
                        int d = Math.max(Math.abs(a - i), Math.abs(b - j));
                        if (d < best) best = d;
                    }
                }
                candidates.add(new int[]{-SCAN + i * GS, -SCAN + j * GS, best * GS});
            }
        }
        if (candidates.isEmpty()) throw new IllegalStateException("no land in scan window");
        candidates.sort((p, q) -> Integer.compare(p[2], q[2]));

        System.out.println("probe                distToOcean  requiredRadius        level");
        for (int target : new int[]{300, 800, 1600, 3200}) {
            int[] pick = null;
            for (int[] c : candidates) {
                if (pick == null || Math.abs(c[2] - target) < Math.abs(pick[2] - target)) pick = c;
            }
            if (pick == null) continue;
            int need = -1;
            double last = Double.NaN;
            StringBuilder row = new StringBuilder();
            for (int radius : RADII) {
                double level = waterLevelAt(gen, hs, sea, pick[0], pick[1], radius);
                row.append(Double.isNaN(level) ? "  NO-SEA" : String.format("%8.3f", level));
                if (Double.isFinite(last) && Double.isFinite(level)
                        && Math.abs(level - last) < 1e-9 && need < 0) need = radius;
                last = level;
            }
            System.out.printf("(%6d,%6d)   %10d  %14s  %s%n", pick[0], pick[1], pick[2],
                    need < 0 ? "not stable<=2048" : need + " blocks", row);
        }
        System.out.println("READ: requiredRadius 若 ≈ distToOcean 或更大 ⇒ 成本随内陆深度增长。");
        System.out.println("LIMIT: 只测到 radius=2048 / dist≈3200；更大距离未测，不得外推。");
    }

    private static double waterLevelAt(CellGenerator gen, double hs, double sea,
                                       int px, int pz, int radius) {
        int n = 2 * radius / STEP + 1;
        double[] bed = new double[n * n];
        boolean[] seaMask = new boolean[n * n];
        double[] exterior = new double[n * n];
        java.util.Arrays.fill(exterior, Double.NaN);
        boolean anySea = false;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                int k = i * n + j;
                bed[k] = gen.erodedHeightForRouting((px - radius + i * STEP) / hs,
                        (pz - radius + j * STEP) / hs);
                seaMask[k] = bed[k] < sea;
                if (seaMask[k]) anySea = true;
                if (i == 0 || j == 0 || i == n - 1 || j == n - 1) {
                    int oi = i == 0 ? -1 : i == n - 1 ? n : i;
                    int oj = j == 0 ? -1 : j == n - 1 ? n : j;
                    exterior[k] = Math.max(gen.erodedHeightForRouting(
                            (px - radius + oi * STEP) / hs,
                            (pz - radius + oj * STEP) / hs), sea);
                }
            }
        }
        if (!anySea) return Double.NaN;
        EquilibriumWater.Surface s = EquilibriumWater.compute(bed, n, n, seaMask, exterior);
        return s.level()[(n / 2) * n + n / 2];
    }

    private InlandRadiusProbe() { }
}
