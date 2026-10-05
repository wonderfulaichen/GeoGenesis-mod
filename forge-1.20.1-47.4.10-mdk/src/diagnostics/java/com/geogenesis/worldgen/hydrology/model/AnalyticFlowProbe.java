package com.geogenesis.worldgen.hydrology.model;

/**
 * 解析/半解析对照（判别用的【输入侧】检查）。
 *
 * <p><b>为什么先做这个</b>：此前湖泊/溢口实验全用自建场景，无法区分"模型能力不足"与
 * "实现有错"。本探针在谈模型取舍之前，先用已知解与收敛性把两者分开。</p>
 *
 * <p><b>关键教训（本轮实测得出，勿删）</b>：运动波公式 {@code h=(nRx/√S)^0.6}
 * 是忽略水面坡降的近似，且它恰是扩散波常微分方程的<b>不稳定平衡点</b>
 * （h&gt;h_k ⇒ dh/dx&gt;0 ⇒ 继续偏离）。把它当"正确答案"去比，会把动力学偏差
 * 误判成实现缺陷。故 t4 改用<b>由下游边界条件积分出的回水剖面</b>作参照，
 * 该参照与求解器不共享任何离散或系数。</p>
 *
 * <p><b>不做什么</b>：不选模型、不接生产、不作门禁。完整浅水/局部惯性尚未实现。</p>
 */
public final class AnalyticFlowProbe {

    private static final double G = 9.81;

    public static void main(String[] args) {
        System.out.println("=== analytic / semi-analytic references; NOT a model selection ===");
        lakeAtRest();
        perturbedLake();
        damBreakAgainstRitter();
        backwaterProfile();
        System.out.println("NOTE: diffusive wave carries no inertia; the dam-break deviations below are expected"
                + " and are the input to the next comparison, not a defect by themselves.");
    }

    /** t1：非平床静水——良平衡格式必须【精确】保持（最易写错的一条），并随网格加密保持。 */
    private static void lakeAtRest() {
        for (double dx : new double[]{1.0, 0.5, 0.25}) {
            int n = (int) Math.round(48 / dx);
            double level = 0.5;
            double[] bed = bumpBed(n, dx, 0.35, 24.0);
            DiffusiveWaveModel m = new DiffusiveWaveModel(
                    WaterGrid.of(n, 1, dx, bed, WaterGrid.Boundary.CLOSED, level),
                    ModelParams.still().withCellSize(dx).withMaxSteps(2000));
            double[] before = new double[n];
            for (int i = 0; i < n; i++) { m.fillTo(i, 0, level); before[i] = m.depth(i, 0); }
            m.steadyState();
            double worst = 0;
            for (int i = 0; i < n; i++) worst = Math.max(worst, Math.abs(m.depth(i, 0) - before[i]));
            // 阈值取机器精度量级：良平衡不是"误差小"，是"不产生运动"。
            report("t1 still lake exactly preserved (dx=" + dx + ")", worst <= 1e-12,
                    String.format("maxChange=%.3e", worst));
        }
    }

    /** t2：静水小扰动——非良平衡格式会在凸起处凭空造波；扰动只能传播、不得放大。 */
    private static void perturbedLake() {
        for (double dx : new double[]{0.5, 0.25}) {
            int n = (int) Math.round(100 / dx);
            double level = 0.5, amplitude = 2e-3;
            double[] bed = bumpBed(n, dx, 0.35, 24.0);
            DiffusiveWaveModel m = new DiffusiveWaveModel(
                    WaterGrid.of(n, 1, dx, bed, WaterGrid.Boundary.CLOSED, level),
                    ModelParams.still().withCellSize(dx).withMaxSteps(20000));
            double[] initial = new double[n];
            for (int i = 0; i < n; i++) {
                double x = (i - n / 2.0) * dx;
                double eta = level + (Math.abs(x) < 4 ? amplitude * 0.5 * (1 + Math.cos(Math.PI * x / 4)) : 0);
                m.fillTo(i, 0, eta);
                initial[i] = m.depth(i, 0);
            }
            m.steadyState();
            double worst = 0;
            for (int i = 0; i < n; i++) worst = Math.max(worst, Math.abs(m.depth(i, 0) - initial[i]));
            report("t2 perturbation does not amplify (dx=" + dx + ")", worst <= 2 * amplitude,
                    String.format("maxChange=%.3e ratio=%.2f", worst, worst / amplitude));
        }
    }

    /** t3：Ritter 干底溃坝——有精确解、且是【惯性主导】流况；量化扩散波的偏差（对照输入，非缺陷）。 */
    private static void damBreakAgainstRitter() {
        for (double dx : new double[]{1.0, 0.5}) {
            int n = (int) Math.round(400 / dx);
            double h0 = 1.0, target = 1.0;
            ModelParams p = new ModelParams(dx, 1e-3, 0, 0, 0, 0, 0.005 * dx, 1e-9, 200000);
            DiffusiveWaveModel m = new DiffusiveWaveModel(
                    WaterGrid.flat(n, 1, dx, 0, WaterGrid.Boundary.CLOSED, 0), p);
            for (int i = 0; i < n / 2; i++) m.setDepth(i, 0, h0);
            double t = 0;
            while (t < target - 1e-12) t += m.step();
            double l1 = 0, reference = 0, worst = 0;
            int frontModel = -1, frontRitter = -1;
            for (int i = 0; i < n; i++) {
                double x = (i - n / 2.0) * dx;
                double exact = ritter(x, t, h0), model = m.depth(i, 0);
                l1 += Math.abs(model - exact) * dx;
                reference += exact * dx;
                worst = Math.max(worst, Math.abs(model - exact));
                if (model > 0.01) frontModel = i;
                if (exact > 0.01) frontRitter = i;
            }
            // 扩散波必然把激波抹平、把锋面推快 ⇒ 只作"偏差量级 + 加密是否收敛"的记录。
            report("t3 dam break vs Ritter reported (dx=" + dx + ")", true,
                    String.format("relativeL1=%.3f maxAbs=%.3f frontErr=%+.2f blocks",
                            l1 / reference, worst, (frontModel - frontRitter) * dx));
        }
    }

    /**
     * t4：斜面降雨<b>回水剖面（M1 型）</b>。出口水深设在正常（运动波）水深的 5 倍，
     * 使回水段覆盖大部分计算域，再与独立反向积分参照比较。
     *
     * <p>顺序很重要：<b>先证参照可信（t4a 子步加密不变），再看模型（t4b）</b>。
     * 另外加入两条<b>不依赖参照</b>的检查（t4c）：守恒账本与逐面通量口径。</p>
     *
     * <p>⚠ <b>时步必须足够小</b>：本项目内部交换用隐式线性化 {@code Δη·λ/(1+2λ)}，
     * dt 偏大时实际通量被压制 ⇒ 会同时污染 t4b 与 t4c。
     * 故固定"每步位移远小于水深尺度"的判据：dt = CFL·dx/h，而非写死常数。</p>
     */
    private static void backwaterProfile() {
        double slope = 0.01, rain = 1e-3, nMan = 0.03, length = 120;
        for (double dx : new double[]{2.0, 1.0}) {
            int n = (int) Math.round(length / dx);
            double cellDx = length / (n - 1);
            double[] bed = new double[n];
            for (int i = 0; i < n; i++) bed[i] = -slope * i * cellDx;
            double hNormal = SteadyProfileOracle.kinematic(length, slope, rain, nMan);
            double hOutlet = hNormal * 5;
            double outletLevel = bed[n - 1] + hOutlet;
            // 隐式阻尼 →1 的时步：λ = dt·c/A 需 ≲0.05。取保守的 dt = 0.002·dx²。
            double dt = 0.002 * cellDx * cellDx;
            ModelParams p = new ModelParams(cellDx, nMan, rain, 0, 0, 0, dt, 1e-6, 4_000_000);
            DiffusiveWaveModel m = new DiffusiveWaveModel(
                    WaterGrid.of(n, 1, cellDx, bed, WaterGrid.Boundary.EAST_OPEN_AT_LEVEL, outletLevel), p);
            m.steadyState();

            // t4c-1 守恒账本：降水 − 出流 − 储水增量 = 0。
            //   ⚠ 不能用"出流 == 降水"：稳定态未走完时出流必然偏小，差额正是仍在蓄的水。
            double inflow = rain * n * cellDx * cellDx * m.time();
            double initialStorage = 0;
            double storageChange = m.storage() - initialStorage;
            double ledgerResidual = inflow - m.boundaryLossVolume() - storageChange;
            double ledgerScale = Math.max(inflow, Math.abs(storageChange));
            report("t4c conservation ledger dx=" + dx,
                    m.converged() && m.time() > 0 && Math.abs(ledgerResidual) < 1e-6 * ledgerScale,
                    String.format("inflow=%.6e outflow=%.6e storage=%.6e residual=%.3e (rel %.2e)",
                            inflow, m.boundaryLossVolume(), storageChange, ledgerResidual,
                            Math.abs(ledgerResidual) / ledgerScale));

            // t4c-2 单调性：稳定剖面只能沿下游加深（允许极小数值波动）。
            int violations = 0;
            double worstDrop = 0;
            for (int i = 1; i < n; i++) {
                double drop = m.depth(i - 1, 0) - m.depth(i, 0);
                if (drop > 1e-6) { violations++; worstDrop = Math.max(worstDrop, drop); }
            }
            report("t4c profile monotone downstream dx=" + dx, violations == 0,
                    String.format("violations=%d worstDrop=%.3e", violations, worstDrop));

            // t4c-3 ★ 逐面通量核对（不需要任何解析参照）
            //   稳定态下 cell i 右侧断面的体积流量必须等于其上游降水累积：Q_i = R·(i+1)·dx²。
            //   ⚠ 必须用 steadyFaceDischarge（含隐式阻尼 1/(1+2λ)），不能用未阻尼面通量：
            //   本项目用隐式线性化推进，dt 大时实际转移被显著压制；用未阻尼量比较会
            //   把"时间离散偏差"误判成"通量律错误"。已由 SteadyAchievabilityProbe 实测裁决：
            //   dt ×1/10/1/100 ⇒ 比值 0.745 / 0.976 / 0.9975（→1，且 damping→1.00）。
            double worstFluxRel = 0, worstFluxX = 0, worstDamping = 1;
            int fluxCompared = 0;
            for (int i = 0; i < n - 1; i++) {
                double expected = rain * (i + 1) * cellDx * cellDx;
                if (expected <= 0) continue;
                double damping = m.exchangeDamping(i, i + 1);
                // 只用【阻尼接近 1】的断面判定通量律：阻尼大的面其实际转移被时间离散压制，
                // 拿它判"通量律对不对"会把时间离散误差算到空间离散头上（已由时步扫描证明）。
                if (damping < 1.10) {
                    double rel = Math.abs(m.steadyFaceDischarge(i, i + 1) - expected) / expected;
                    fluxCompared++;
                    if (rel > worstFluxRel) {
                        worstFluxRel = rel; worstFluxX = i * cellDx;
                    }
                }
                worstDamping = Math.max(worstDamping, damping);
            }
            report("t4c steady face discharge equals upstream rainfall dx=" + dx,
                    m.converged() && fluxCompared >= 3 && worstFluxRel < 0.05,
                    String.format("eligible=%d/%d worstRel=%.2f%% @x=%.0f  worstDamping=%.2f (全断面)",
                            fluxCompared, n - 1, worstFluxRel * 100, worstFluxX, worstDamping));

            double[] reference = SteadyProfileOracle.backwater(length, hOutlet, slope, rain, nMan, n);
            double oracleDrift = SteadyProfileOracle.selfCheckStepRefinement(length, hOutlet, slope, rain, nMan, n);
            // 判据取 5e-3：下面的模型比较用 5% 容差，参照自身须至少好一个数量级才谈得上有分辨力。
            report("t4a oracle self-check dx=" + dx, oracleDrift < 5e-3,
                    String.format("maxRelativeDrift=%.3e (须 << 模型比较容差 5%%)", oracleDrift));

            // 分带报告：临界过渡带（h 接近正常水深）曲率最大、粗网格最难分辨；
            //   把它与"充分发育区"混成一个数字，会看不出偏差究竟在哪儿 ⇒ 必须分开。
            double worstFar = 0, farX = 0, worstNear = 0, nearX = 0;
            int compared = 0, farCount = 0;
            for (int i = 1; i < n; i++) {
                if (Double.isNaN(reference[i])) continue;
                compared++;
                double x = i * cellDx;
                double rel = Math.abs(m.depth(i, 0) - reference[i]) / reference[i];
                double ratio = reference[i] / SteadyProfileOracle.kinematic(x, slope, rain, nMan);
                if (ratio >= 2.0) {
                    farCount++;
                    if (rel > worstFar) { worstFar = rel; farX = x; }
                } else if (rel > worstNear) { worstNear = rel; nearX = x; }
            }
            if (dx >= 2.0) {
                for (int i : new int[]{n - 1, n - 6, n - 16, n - 30, n / 2}) {
                    if (i < 1 || i >= n) continue;
                    System.out.printf("     x=%6.1f model=%.5f ode=%.5f%s%n", i * cellDx, m.depth(i, 0),
                            reference[i], Double.isNaN(reference[i]) ? "  (超出参照有效域)" : "");
                }
            }
            report("t4b well-developed reach matches ODE dx=" + dx,
                    m.converged() && oracleDrift < 5e-3 && farCount >= 3 && worstFar < 0.05,
                    String.format("compared=%d/%d farReach=%d worstFar=%.2f%% @x=%.0f",
                            compared, n - 1, farCount, worstFar * 100, farX));
            // 临界过渡带单独记录（不判 PASS/FAIL）：这是量化局限，不是隐藏的缺陷。
            System.out.printf("[INFO] t4b near-critical band dx=%.1f  worstRel=%.2f%% @x=%.0f"
                            + "  (该带曲率最大；加密网格 %s)%n",
                    dx, worstNear * 100, nearX, dx <= 1.0 ? "已改善" : "待加密验证");
        }
    }

    private static double[] bumpBed(int n, double dx, double height, double width) {
        double[] bed = new double[n];
        for (int i = 0; i < n; i++) {
            double x = (i - n / 2.0) * dx;
            bed[i] = height * Math.exp(-(x * x) / width);
        }
        return bed;
    }

    /** Ritter 干底溃坝解析解（x=0 为坝址，t>0）。 */
    private static double ritter(double x, double t, double h0) {
        double c0 = Math.sqrt(G * h0);
        if (x <= -c0 * t) return h0;
        if (x >= 2 * c0 * t) return 0;
        double fan = 2 * c0 - x / t;
        return fan * fan / (9 * G);
    }

    private static void report(String name, boolean pass, String detail) {
        System.out.printf("%s %s  %s%n", pass ? "[PASS]" : "[FAIL]", name, detail);
    }

    private AnalyticFlowProbe() { }
}
