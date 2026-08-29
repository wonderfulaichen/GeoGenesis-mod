package com.geogenesis.worldgen.terrain;

/**
 * 山体占位比 (H/D) 诊断工具。
 *
 * <p>用户关注点：一般山体「山底占地」应大于「山高」，除非是桂林式峰林（H/D ≥ 1）。
 * 本探针只做诊断，不改任何地形逻辑。
 *
 * <p>方法：
 * 1. 以步长 S 采样 eLand 网格（归一化陆地高度场，[0,1]）。
 * 2. 在网格上找局部极大值（严格大于 8 邻域）且 eLand 高于 {@code PEAK_MIN_E} 的峰。
 * 3. 对每个峰从峰顶沿 8 个方向外向行进，直到 eLand 跌破山脚等高线 {@code FOOT_E}，
 *    记录到山脚的水平半径，取 8 方向平均 → 山底半径 R，山底直径 D = 2R。
 * 4. 山高 H = heightFromE(peakE) - heightFromE(FOOT_E)（峰相对山脚的拔高，单位 block）。
 * 5. 输出 H / D 分布直方图与统计量。H/D < 1 表示山底占地 > 山高（正常宽底山）；
 *    H/D ≥ 1 表示峰林/尖塔（异常）。
 *
 * <p>仅统计 8 方向均在网格内抵达山脚的「孤立峰」，避免山脉连片导致半径测到边界。
 */
public final class MountainFootprintProbe {

    // 采样区域（block）与步长
    private static final int REGION = 3000;
    private static final int STEP = 5;
    // 峰阈值：仅统计明显的山（eLand 高于此值才算山）
    private static final double PEAK_MIN_E = 0.35;
    // 山脚等高线：eLand 跌破此值视为到达山体基部
    private static final double FOOT_E = 0.20;

    // 8 个方向（grid 索引增量）
    private static final int[][] DIRS = {
        {1, 0}, {1, 1}, {0, 1}, {-1, 1},
        {-1, 0}, {-1, -1}, {0, -1}, {1, -1}
    };

    public static void main(String[] args) {
        long seed = (args.length > 0) ? Long.parseLong(args[0]) : 12345L;

        TerrainParams p = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(p, -64, 320);
        gen.seed(seed);
        HeightCurve curve = new HeightCurve(p, -64, 320);

        int N = REGION / STEP; // grid 边长
        double[][] e = new double[N][N];

        System.out.println("=== MountainFootprintProbe ===");
        System.out.println("Seed=" + seed + "  Region=" + REGION + "x" + REGION
                + "  step=" + STEP + "  grid=" + N + "x" + N);
        System.out.println("PEAK_MIN_E=" + PEAK_MIN_E + "  FOOT_E=" + FOOT_E);
        System.out.print("Sampling ... ");
        long t0 = System.currentTimeMillis();

        for (int gx = 0; gx < N; gx++) {
            int wx = gx * STEP;
            for (int gz = 0; gz < N; gz++) {
                int wz = gz * STEP;
                Cell cell = gen.sample(wx, wz);
                e[gx][gz] = cell.eLand;
            }
        }
        System.out.println("done in " + (System.currentTimeMillis() - t0) + " ms");

        // 区域 eLand 分布预检（定位山区是否存在 / 阈值是否合理）
        double maxE = -1, sumE = 0;
        int[] ehist = new int[11]; // 0.0-0.1 ... 1.0-1.1
        long cnt40 = 0, cnt50 = 0, cnt60 = 0;
        for (int gx = 0; gx < N; gx++) {
            for (int gz = 0; gz < N; gz++) {
                double v = e[gx][gz];
                if (v > maxE) maxE = v;
                sumE += v;
                int b = (int) (v / 0.1);
                if (b >= ehist.length) b = ehist.length - 1;
                if (b < 0) b = 0;
                ehist[b]++;
                if (v > 0.4) cnt40++;
                if (v > 0.5) cnt50++;
                if (v > 0.6) cnt60++;
            }
        }
        System.out.println("eLand 均值=" + String.format("%.3f", sumE / (N * N))
                + "  最大=" + String.format("%.3f", maxE));
        System.out.println("eLand>0.4 格数=" + cnt40 + "  >0.5=" + cnt50 + "  >0.6=" + cnt60);
        System.out.println("eLand 直方图 (每 bin 0.1):");
        for (int i = 0; i < ehist.length; i++) {
            double lo = i * 0.1;
            System.out.printf("  [%.1f,%.1f) %s%n", lo, lo + 0.1, "#".repeat(Math.min(60, ehist[i] / 50)));
        }
        System.out.println();

        // 1. 找局部极大值峰
        int totalPeaks = 0;
        int isolatedPeaks = 0;
        double sumH = 0, sumD = 0, sumR = 0;
        double minRatio = Double.POSITIVE_INFINITY, maxRatio = Double.NEGATIVE_INFINITY;
        double medianAccumH = 0, medianAccumD = 0, medianAccumR = 0;
        java.util.ArrayList<Double> ratios = new java.util.ArrayList<>();
        java.util.ArrayList<Double> hs = new java.util.ArrayList<>();
        java.util.ArrayList<Double> ds = new java.util.ArrayList<>();

        for (int gx = 1; gx < N - 1; gx++) {
            for (int gz = 1; gz < N - 1; gz++) {
                double pe = e[gx][gz];
                if (pe < PEAK_MIN_E) continue;
                // 严格局部极大值
                boolean isMax = true;
                for (int[] d : DIRS) {
                    if (e[gx + d[0]][gz + d[1]] >= pe) { isMax = false; break; }
                }
                if (!isMax) continue;
                totalPeaks++;

                // 沿 8 方向测到山脚的半径（grid 步数）
                double[] radii = new double[DIRS.length];
                boolean allReached = true;
                for (int i = 0; i < DIRS.length; i++) {
                    int[] d = DIRS[i];
                    int k = 1;
                    double r = -1;
                    while (true) {
                        int nx = gx + d[0] * k;
                        int nz = gz + d[1] * k;
                        if (nx < 0 || nx >= N || nz < 0 || nz >= N) { allReached = false; break; }
                        if (e[nx][nz] < FOOT_E) { r = (k - 1); break; } // 最后一个仍在山体内的步
                        k++;
                        if (k > N) { allReached = false; break; }
                    }
                    if (!allReached) break;
                    radii[i] = r;
                }
                if (!allReached) continue; // 连片山脉/触边界，跳过

                isolatedPeaks++;
                double avgRGrid = 0;
                for (double r : radii) avgRGrid += r;
                avgRGrid /= DIRS.length;
                double R = avgRGrid * STEP;          // 山底半径 (block)
                double D = 2.0 * R;                   // 山底直径 (block)
                double H = curve.heightFromE(pe) - curve.heightFromE(FOOT_E); // 拔高 (block)
                double ratio = H / D;

                hs.add(H);
                ds.add(D);
                ratios.add(ratio);
                sumH += H; sumD += D; sumR += R;
                minRatio = Math.min(minRatio, ratio);
                maxRatio = Math.max(maxRatio, ratio);
            }
        }

        System.out.println("局部峰总数 (eLand>" + PEAK_MIN_E + ") = " + totalPeaks);
        System.out.println("孤立峰 (8方向均达山脚) = " + isolatedPeaks);
        if (isolatedPeaks == 0) {
            System.out.println("无孤立峰可测，请扩大区域或降低 PEAK_MIN_E。");
            return;
        }

        // 排序求中位数
        java.util.Collections.sort(ratios);
        java.util.Collections.sort(hs);
        java.util.Collections.sort(ds);
        int m = isolatedPeaks / 2;
        double medRatio = (isolatedPeaks % 2 == 1) ? ratios.get(m) : (ratios.get(m - 1) + ratios.get(m)) / 2;
        double medH = (isolatedPeaks % 2 == 1) ? hs.get(m) : (hs.get(m - 1) + hs.get(m)) / 2;
        double medD = (isolatedPeaks % 2 == 1) ? ds.get(m) : (ds.get(m - 1) + ds.get(m)) / 2;

        System.out.println();
        System.out.println("=== 统计量 (孤立峰) ===");
        System.out.printf("H  均值=%.1f 中位=%.1f  (block)%n", sumH / isolatedPeaks, medH);
        System.out.printf("D  均值=%.1f 中位=%.1f  (block)%n", sumD / isolatedPeaks, medD);
        System.out.printf("H/D 均值=%.3f 中位=%.3f  最小=%.3f 最大=%.3f%n",
                (sumH / isolatedPeaks) / (sumD / isolatedPeaks), medRatio, minRatio, maxRatio);

        // H/D 直方图（bins 宽 0.1，0..2.0+）
        System.out.println();
        System.out.println("=== H/D 直方图 ===");
        int[] bins = new int[21]; // 0.0-0.1 ... 2.0-2.1+
        for (double r : ratios) {
            int b = (int) (r / 0.1);
            if (b >= bins.length) b = bins.length - 1;
            if (b < 0) b = 0;
            bins[b]++;
        }
        int maxCount = 0;
        for (int c : bins) maxCount = Math.max(maxCount, c);
        for (int i = 0; i < bins.length; i++) {
            double lo = i * 0.1, hi = lo + 0.1;
            String label = (i == bins.length - 1) ? (">=2.0") : String.format("%.1f-%.1f", lo, hi);
            int bars = maxCount == 0 ? 0 : (int) Math.round((double) bins[i] / maxCount * 40);
            StringBuilder sb = new StringBuilder();
            for (int j = 0; j < bars; j++) sb.append('#');
            System.out.printf("%-8s | %3d %s%n", label, bins[i], sb);
        }

        System.out.println();
        System.out.println("判读：H/D 中位 < 1 → 山底占地 > 山高（正常宽底山）；");
        System.out.println("      H/D 中位 ≥ 1 → 峰林/尖塔（桂林式异常，需调 FALLOFF/verticalScale）。");
    }
}
