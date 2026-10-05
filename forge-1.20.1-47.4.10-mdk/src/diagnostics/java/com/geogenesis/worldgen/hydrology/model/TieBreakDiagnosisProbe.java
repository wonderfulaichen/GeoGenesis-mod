package com.geogenesis.worldgen.hydrology.model;

/**
 * 定位 8 邻域残留各向异性（轴向 47 vs 对角 2260）的**成因**，而不是继续猜着改。
 *
 * <p>候选成因（必须区分）：</p>
 * <ol>
 *   <li><b>距离度量</b>：Dijkstra 用 √2 对角边长 ⇒ 生长前沿呈八边形，轴向与对角到达时间不同。</li>
 *   <li><b>平局顺序</b>：距离与水位都相同时按格索引破平局 ⇒ 索引依赖残留。</li>
 *   <li><b>离散本质</b>：8 邻域只能表达 8 个方向 ⇒ 部分偏斜是采样几何效应而非伪影。</li>
 * </ol>
 *
 * <p><b>判据</b>：理想径向场中，某格的真实流向应指向洞心。
 * 位于轴向射线上的格其正确流向本就是轴向；位于对角射线上的格本就是对角。
 * 因此要看<b>每一类各自选对了没有</b>，而不是总计数。</p>
 */
public final class TieBreakDiagnosisProbe {

    public static void main(String[] args) {
        int n = 96;
        double[] bed = new double[n * n];
        boolean[] drains = new boolean[n * n];
        double[] exterior = new double[n * n];
        java.util.Arrays.fill(exterior, Double.NaN);
        int cx = n / 2, cz = n / 2;
        drains[cx * n + cz] = true;

        EquilibriumWater.Surface s = EquilibriumWater.compute(bed, n, n, drains, exterior);
        int[] parent = s.parent();

        int bins = 36;
        int[] count = new int[bins];
        int okAxis = 0, badAxis = 0, okDiag = 0, badDiag = 0;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                int k = i * n + j, p = parent[k];
                if (p < 0) continue;
                double ti = cx - i, tj = cz - j;
                double fi = p / n - i, fj = p % n - j;
                if (Math.hypot(ti, tj) < 3 || (fi == 0 && fj == 0)) continue;
                double ang = Math.toDegrees(Math.atan2(fj, fi));
                if (ang < 0) ang += 360;
                count[(int) (ang / (360.0 / bins)) % bins]++;

                double want = Math.toDegrees(Math.atan2(tj, ti));
                if (want < 0) want += 360;
                double diff = Math.abs(((ang - want + 540) % 360) - 180);
                boolean axisRay = nearMultiple(want, 90, 11);
                boolean diagRay = nearMultiple(want, 45, 11);
                if (diff <= 23) { if (diagRay && !axisRay) okDiag++; else okAxis++; }
                else { if (diagRay && !axisRay) badDiag++; else badAxis++; }
            }
        }
        System.out.println("=== tie-break diagnosis (single sink, flat bed) ===");
        StringBuilder sb = new StringBuilder("  angle histogram (10 deg bins):\n   ");
        for (int b = 0; b < bins; b++) {
            sb.append(String.format("%5d", count[b]));
            if (b % 9 == 8) sb.append("\n   ");
        }
        System.out.println(sb);
        System.out.printf("  axis-like rays : correct=%d wrong=%d (%.1f%% correct)%n",
                okAxis, badAxis, 100.0 * okAxis / Math.max(1, okAxis + badAxis));
        System.out.printf("  diag-like rays : correct=%d wrong=%d (%.1f%% correct)%n",
                okDiag, badDiag, 100.0 * okDiag / Math.max(1, okDiag + badDiag));
        System.out.println("READ: 两类 correct 都高 ⇒ 偏置只是 8 方向量化的采样效应。");
        System.out.println("      某一类 correct 明显低 ⇒ 该方向族被系统性误导向，需修平局/度规。");
    }

    private static boolean nearMultiple(double angle, double step, double tol) {
        double r = angle % step;
        return r <= tol || step - r <= tol;
    }

    private TieBreakDiagnosisProbe() { }
}
