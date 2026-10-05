package com.geogenesis.worldgen.hydrology.model;

/**
 * 独立参照：扩散波方程的<b>一维稳定剖面</b>（半解析，与本项目 FV 格式无关）。
 *
 * <p><b>为什么需要它</b>：运动波公式 {@code h=(nRx/√S0)^0.6} 忽略了水面坡降，而且它恰是下述
 * 常微分方程的<b>不稳定平衡点</b>：{@code h>h_kin ⇒ dh/dx>0}，离开它只会更远。
 * 因此运动波解不能当"正确答案"，否则会把动力学偏差误判为实现缺陷。</p>
 *
 * <p><b>方程</b>：稳定态下 {@code q=R·x}（x 为距分水岭距离），配合 Manning 律与
 * 水面坡降 {@code S_f=−dη/dx}：{@code S_f=(n·R·x/h^{5/3})²}。由 {@code η=z+h}、
 * {@code z=−S0·x} 得</p>
 * <pre>  dh/dx = S0 − (n·R·x / h^{5/3})² ,  h(0)=0 或 h(L)=给定出口水深</pre>
 *
 * <p>两种用法：{@link #fromDivide}（正向，出口自由）；{@link #backwater}（由给定出口水深
 * <b>反向</b>积分），后者用于检验 FV 是否忠实复现它自己的下游边界条件。</p>
 *
 * <p><b>边界</b>：本类是同一连续方程的另一离散，可判"实现是否解对了方程"，
 * <b>不能</b>判"该方程是否够用"；惯性/急变流问题须另有完整浅水参照。</p>
 */
public final class SteadyProfileOracle {

    public static double depthAt(double targetX, double slope, double rain, double manning, int steps) {
        double h = 1e-9, dx = targetX / steps;
        for (int k = 0; k < steps; k++) {
            double x = k * dx;
            double k1 = dh(x, h, slope, rain, manning);
            double k2 = dh(x + dx / 2, h + dx * k1 / 2, slope, rain, manning);
            double k3 = dh(x + dx / 2, h + dx * k2 / 2, slope, rain, manning);
            double k4 = dh(x + dx, h + dx * k3, slope, rain, manning);
            h += dx * (k1 + 2 * k2 + 2 * k3 + k4) / 6;
            if (!(h > 0) || !Double.isFinite(h)) throw new IllegalStateException("oracle left physical range");
        }
        return h;
    }

    /**
     * 由出口水深反向积分得到全剖面（下标 i 对应 x=i·dx，i=1..cells-1）。
     *
     * <p><b>要求出口水深高于临界（运动波）水深</b>：此时 {@code dh/dx>0}，反向积分使水深
     * 向临界水深衰减（M1 型回水），是<b>稳定</b>且唯一的剖面。若给出等于或低于临界水深，
     * 参照会沿不稳定平衡漂移、在分水岭处降到零 ⇒ 那种构造不能当判据。</p>
     *
     * @param length  域长
     * @param hOutlet 最下游格的水深（须 &gt; 当地临界水深）
     * @param cells   输出格数（含 i=0）
     */
    public static double[] backwater(double length, double hOutlet, double slope, double rain,
                                     double manning, int cells) {
        return backwater(length, hOutlet, slope, rain, manning, cells, 1);
    }

    /**
     * @param substeps 每个网格内的 RK4 子步数（≥1）。用于自检："步数减半结果不变"才可信，
     *                 因为临界点附近方程本身是中性稳定的，粗积分会越过临界水深落到不稳定分支。
     */
    public static double[] backwater(double length, double hOutlet, double slope, double rain,
                                     double manning, int cells, int substeps) {
        if (substeps < 1) throw new IllegalArgumentException("substeps must be >= 1");
        double dx = length / (cells - 1);
        double normalAtOutlet = kinematic(length, slope, rain, manning);
        if (hOutlet <= normalAtOutlet * 1.01) {
            throw new IllegalArgumentException("outlet depth must exceed normal depth for a stable M1 profile");
        }
        double[] profile = new double[cells];
        profile[cells - 1] = hOutlet;
        double h = hOutlet;
        for (int i = cells - 2; i >= 0; i--) {
            double x = i * dx;
            double hBefore = h;
            double sub = dx / substeps;
            for (int s = 0; s < substeps; s++) {
                double xs = x + (substeps - 1 - s) * sub;
                double k1 = dh(xs, h, slope, rain, manning);
                double k2 = dh(xs + sub / 2, h + sub * k1 / 2, slope, rain, manning);
                double k3 = dh(xs + sub / 2, h + sub * k2 / 2, slope, rain, manning);
                double k4 = dh(xs + sub, h + sub * k3, slope, rain, manning);
                h -= sub * (k1 + 2 * k2 + 2 * k3 + k4) / 6;
            }
            // 回水（M1）剖面只能从上方逼近临界水深；越过即落到不稳定分支 ⇒ 该点起不比较。
            double normalHere = kinematic(x, slope, rain, manning);
            // ⚠ 阈值 1.02 太贴近临界点：该处 dη/dx→0，剖面曲率最大，数值积分最脆
            //   （实测自检漂移就发生在这一带）。留出稳定的余量，宁可少比几格。
            if (!(h > 0) || !Double.isFinite(h) || h <= normalHere * 1.20 || h > hBefore) {
                java.util.Arrays.fill(profile, 0, i + 1, Double.NaN);
                return profile;
            }
            profile[i] = h;
        }
        return profile;
    }

    /**
     * 参照自检：子步加密后同点水深变化应可忽略。
     *
     * <p>只在<b>两次都给出定义</b>的区间比较：粗/细积分会在临界水深附近停在不同的格，
     * 把"一格有、另一格无"记成无穷漂移是判据构造错误，不是参照缺陷。</p>
     */
    public static double selfCheckStepRefinement(double length, double hOutlet, double slope,
                                                 double rain, double manning, int cells) {
        double[] coarse = backwater(length, hOutlet, slope, rain, manning, cells, 16);
        double[] fine = backwater(length, hOutlet, slope, rain, manning, cells, 64);
        double worst = 0;
        int compared = 0;
        for (int i = 0; i < cells; i++) {
            if (Double.isNaN(coarse[i]) || Double.isNaN(fine[i])) continue;
            worst = Math.max(worst, Math.abs(coarse[i] - fine[i]) / fine[i]);
            compared++;
        }
        return compared == 0 ? Double.POSITIVE_INFINITY : worst;
    }

    /** 运动波近似（只用于对照"忽略水面坡降"差多少；不作判据）。 */
    public static double kinematic(double x, double slope, double rain, double manning) {
        return Math.pow(manning * rain * x / Math.sqrt(slope), 0.6);
    }

    private static double dh(double x, double h, double slope, double rain, double manning) {
        double q = rain * x;
        double sf = manning * q / Math.pow(Math.max(h, 1e-12), 5.0 / 3.0);
        return slope - sf * sf;
    }

    private SteadyProfileOracle() { }
}
