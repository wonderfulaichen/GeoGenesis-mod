package com.geogenesis.worldgen.terrain.tectonicsand;

/**
 * 沙箱连续性自检：全图、多方向测相邻格跳变，并按分量归因。
 *
 * <p>单条横线不足为证（用户曾指出图上仍有网格状边线）。本探针：
 * <ol>
 *   <li>在 900×900 网格上测<b>横向/纵向</b>两个方向的相邻差，报告 max / p99.9 / p99；</li>
 *   <li>逐项拆开（全量 / 不含细节 / 只含细节），定位跳变来自哪一项；</li>
 *   <li>在最大跳变点打印附近若干点的分量值，供人工判读。</li>
 * </ol>
 *
 * <p>⚠ 判据提醒：{@code p99.9} 大不一定是"接缝"——真实的陡陆坡也会给出大梯度。
 * 区分办法是<b>缩小步长</b>：1wu 步长下仍平滑 = 陡坡；某一步骤突变 = 接缝。</p>
 */
public final class SandContinuityCheck {

    private static final long SEED = 9139912035078620160L;
    private static final double R = 1750.0;
    private static final int N = 900;

    public static void main(String[] args) {
        double step = 2 * R / (N - 1);

        double[][] h = new double[N][N];
        for (int j = 0; j < N; j++) {
            for (int i = 0; i < N; i++) {
                h[j][i] = SandTerrain.height(SEED, -R + i * step, -R + j * step);
            }
        }
        report("full", h);

        // 不含细节（base + 构造项）
        double[][] hNoDetail = new double[N][N];
        for (int j = 0; j < N; j++) {
            for (int i = 0; i < N; i++) {
                hNoDetail[j][i] = SandTerrain.heightOf(
                        SandPlateField.sample(SEED, -R + i * step, -R + j * step));
            }
        }
        report("no-detail", hNoDetail);

        // 只含细节（本应完全连续）
        double[][] hOnlyDetail = new double[N][N];
        for (int j = 0; j < N; j++) {
            for (int i = 0; i < N; i++) {
                SandPlateField.Sample s = SandPlateField.sample(SEED, -R + i * step, -R + j * step);
                hOnlyDetail[j][i] = SandTerrain.partsOf(s).detail();
            }
        }
        report("only-detail", hOnlyDetail);

        // 定位最大跳变点附近的分量（用全量）
        int bi = 0, bj = 0;
        double best = 0;
        for (int j = 0; j < N - 1; j++) {
            for (int i = 0; i < N - 1; i++) {
                double d = Math.max(Math.abs(h[j][i + 1] - h[j][i]), Math.abs(h[j + 1][i] - h[j][i]));
                if (d > best) {
                    best = d;
                    bi = i;
                    bj = j;
                }
            }
        }
        System.out.printf("%n最大跳变点 (%d,%d) world=(%.1f,%.1f) jump=%.4f e = %.1f 格%n",
                bi, bj, -R + bi * step, -R + bj * step, best, best * 236);
        System.out.println("附近分量解剖（沿 x 方向 6 点）：");
        System.out.println("  x(wu)\tcrust\tdist\tbase\torogen\ttrench\tdetail\tislet\tfull");
        for (int k = bi - 2; k <= bi + 3; k++) {
            if (k < 0 || k >= N) {
                continue;
            }
            double wx = -R + k * step, wz = -R + bj * step;
            SandPlateField.Sample s = SandPlateField.sample(SEED, wx, wz);
            SandTerrain.Parts p = SandTerrain.partsOf(s);
            double crust = SandTerrain.crust01(s.seed(), s.warpedX(), s.warpedZ());
            System.out.printf("  %.1f\t%.3f\t%.1f\t%.4f\t%.4f\t%.4f\t%.4f\t%.4f\t%.4f%n",
                    wx, crust, s.dist(), p.base(), p.orogen(), p.trench(), p.detail(),
                    p.islet(), p.sum());
        }
    }

    private static void report(String label, double[][] h) {
        int n = h.length;
        double[] diffs = new double[(n - 1) * (n - 1) * 2];
        int k = 0;
        double max = 0;
        for (int j = 0; j < n - 1; j++) {
            for (int i = 0; i < n - 1; i++) {
                double dx = Math.abs(h[j][i + 1] - h[j][i]);
                double dy = Math.abs(h[j + 1][i] - h[j][i]);
                diffs[k++] = dx;
                diffs[k++] = dy;
                max = Math.max(max, Math.max(dx, dy));
            }
        }
        java.util.Arrays.sort(diffs);
        double p50 = diffs[diffs.length / 2];
        double p99 = diffs[(int) (diffs.length * 0.99)];
        double p999 = diffs[(int) (diffs.length * 0.999)];
        System.out.printf("%-12s max=%.4fe(%.1f格)  p99.9=%.4f  p99=%.4f  p50=%.5f  (格/step, step=%.2fwu)%n",
                label, max, max * 236, p999, p99, p50, 2 * R / (n - 1));
    }
}