package com.geogenesis.worldgen.terrain;

/**
 * 地形坡度分布探针（2026-08-02，用户反馈"山体高度>宽度"）。
 *
 * <p>用户判断：这是噪声地形本身的整体趋势问题（侵蚀等后处理改变不了整体趋势）。
 * 检测「陡峭占比」：</p>
 * <ul>
 *   <li>采样 L1 地形 Y（heightFromE 映射后），中心差分求每格坡度 |∇Y|（块/块）</li>
 *   <li>输出坡度角度直方图 + 陡峭占比（&gt;30° / &gt;45°）</li>
 *   <li>判读：山体水平可见宽 = H/tanθ——坡度大 → 可见宽小 → 视觉「高瘦」。
 *       真实地形绝大多数 &lt;20°，&gt;30° 占比通常 &lt;10%；若本地形陡峭占比显著更高
 *       = L1 噪声配方本身的问题（mountShape 振幅/频率、HeightCurve 非线性）。</li>
 * </ul>
 */
public final class SlopeDistributionProbe {

    private static final int REGION = 2000;   // 采样区域（block）
    private static final int STEP = 4;        // 采样步长

    private SlopeDistributionProbe() {}

    public static void main(String[] args) {
        long seed = (args.length > 0) ? Long.parseLong(args[0]) : 12345L;
        // args[1] = 等效 horizontalScale（整体倍频变宽 A/B，2026-08-02）。
        // 采样坐标 ÷hs（内部 sample 再 ÷1.0）≡ horizontalScale=hs 的世界；物理间距仍 STEP。
        double hs = (args.length > 1) ? Double.parseDouble(args[1]) : 1.0;
        TerrainParams p = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(p, -64, 320);
        gen.seed(seed);

        int N = REGION / STEP;
        System.out.println("=== SlopeDistributionProbe ===");
        System.out.println("Seed=" + seed + "  Region=" + REGION + "x" + REGION + "  step=" + STEP + "  grid=" + N + "x" + N
                + "  effHS=" + hs);
        System.out.print("Sampling ... ");
        long t0 = System.currentTimeMillis();

        double[][] y = new double[N][N];
        for (int gx = 0; gx < N; gx++) {
            for (int gz = 0; gz < N; gz++) {
                y[gx][gz] = gen.sample(gx * STEP / hs, gz * STEP / hs).height;
            }
        }
        System.out.println("done in " + (System.currentTimeMillis() - t0) + " ms");

        int[] bins = new int[7];              // 0-10, 10-20, ..., 60-90
        long land = 0, ocean = 0, steep30 = 0, steep45 = 0, steep20 = 0;
        double sumDeg = 0;
        for (int gx = 1; gx < N - 1; gx++) {
            for (int gz = 1; gz < N - 1; gz++) {
                if (y[gx][gz] <= 63.0) { ocean++; continue; }      // 海面以下跳过
                double ddx = (y[gx + 1][gz] - y[gx - 1][gz]) / (2.0 * STEP);
                double ddz = (y[gx][gz + 1] - y[gx][gz - 1]) / (2.0 * STEP);
                double g = Math.sqrt(ddx * ddx + ddz * ddz);
                double deg = Math.toDegrees(Math.atan(g));
                int b = (int) (deg / 10.0);
                if (b >= bins.length) b = bins.length - 1;
                bins[b]++;
                land++;
                sumDeg += deg;
                if (deg > 20) steep20++;
                if (deg > 30) steep30++;
                if (deg > 45) steep45++;
            }
        }
        if (land == 0) {
            System.out.println("无陆地格（世界全海洋？）");
            return;
        }
        System.out.println();
        System.out.println("陆地格=" + land + "  海洋格=" + ocean + "  平均坡度=" + String.format("%.1f°", sumDeg / land));
        System.out.println("=== 坡度直方图 ===");
        int maxCount = 0;
        for (int c : bins) maxCount = Math.max(maxCount, c);
        for (int i = 0; i < bins.length; i++) {
            double lo = i * 10.0, hi = lo + 10.0;
            int bars = maxCount == 0 ? 0 : (int) Math.round((double) bins[i] / maxCount * 40);
            System.out.printf("%4.0f-%-4.0f° | %6.2f%% %s%n", lo, hi,
                    100.0 * bins[i] / land, "#".repeat(bars));
        }
        System.out.println();
        System.out.println("陡峭占比:  >20° = " + String.format("%.1f%%", 100.0 * steep20 / land)
                + "  >30° = " + String.format("%.1f%%", 100.0 * steep30 / land)
                + "  >45° = " + String.format("%.1f%%", 100.0 * steep45 / land));
        System.out.println();
        System.out.println("判读: 真实地形绝大多数 <20°，>30° 占比通常 <10%。");
        System.out.println("      陡峭占比高 → 山体水平可见宽 = H/tanθ 小 → 视觉「高瘦」（L1 配方问题）。");
    }
}
