package com.geogenesis.worldgen.hydrology.model;

/**
 * 判定：t4c「稳定态面通量 ≠ 上游降水累积」是【没到稳定态】还是【通量律/离散有错】。
 *
 * <p>这两者后果完全不同：前者只需跑更久；后者说明求解器解的不是它声称的方程，
 * 则所有基于它的结论（含此前湖泊阶段图）都要重新审视。</p>
 *
 * <p>做法：同一场景持续推进，每隔一段打印
 * ① 相对储量（储水/累计降水，稳定态应停止变化、不再增长）
 * ② 中游断面的 {@code q_model / (R·x)}（真稳定态必须 → 1）。
 * 若 ① 持续增长而 ② 停在 >1，则是"仍在充水"（瞬态），不是通量律错误。</p>
 *
 * <p>不做判据、不选模型、不接生产。</p>
 */
public final class SteadyAchievabilityProbe {

    public static void main(String[] args) {
        double slope = 0.01, rain = 1e-3, manning = 0.03, length = 120;
        int n = 60;
        double dx = length / (n - 1);
        double[] bed = new double[n];
        for (int i = 0; i < n; i++) bed[i] = -slope * i * dx;
        double hNormal = SteadyProfileOracle.kinematic(length, slope, rain, manning);
        double hOutlet = hNormal * 5;
        System.out.printf("hNormal=%.5f hOutlet=%.5f dx=%.3f%n", hNormal, hOutlet, dx);
        System.out.println("--- 时间步扫描：细分 dt 应使稳态通量口径趋于成立（λ→1） ---");
        for (double dtFactor : new double[]{1.0, 0.1, 0.01}) {
            ModelParams p = new ModelParams(dx, manning, rain, 0, 0, 0,
                    0.1 * dx * dtFactor, 1e-4, 40_000_000);
            DiffusiveWaveModel m = new DiffusiveWaveModel(
                    WaterGrid.of(n, 1, dx, bed, WaterGrid.Boundary.EAST_OPEN_AT_LEVEL, bed[n - 1] + hOutlet), p);
            // 固定【模型时间】而非步数：步数随 dt 缩小而激增，用时间作终止条件才是同口径比较。
            double target = 2000;
            while (m.time() < target && m.steps() < 40_000_000) m.step();
            int mid = n / 4;
            double expected = rain * (mid + 1) * dx * dx;
            double damp = m.exchangeDamping(mid, mid + 1);
            System.out.printf("  dt=%.4e(×%.3f)  time=%8.0f steps=%9d  q_steady/(R*x)=%8.4f  damp=%.3f  conv=%s%n",
                    p.dtMax(), dtFactor, m.time(), m.steps(),
                    m.steadyFaceDischarge(mid, mid + 1) / expected, damp, m.converged());
        }
        System.out.println("READ: q_steady/(R*x) 随 dt 减小趋向 1 ⇒ 差异来自时间离散(隐式阻尼)，非通量律错误。");
    }

    private SteadyAchievabilityProbe() { }
}
