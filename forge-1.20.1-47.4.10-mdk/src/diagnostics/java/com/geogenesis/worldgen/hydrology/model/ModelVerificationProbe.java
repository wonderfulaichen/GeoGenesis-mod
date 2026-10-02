package com.geogenesis.worldgen.hydrology.model;

/**
 * P1 物理模型的<b>判据型</b>验收探针（plan §6.2「数值物理」矩阵）。
 *
 * <h2>与旧验证据的三点不同（plan §6 指出的盲区）</h2>
 * <ul>
 *   <li><b>参照独立</b>：溢口用 {@link ModelOracle} 的 Dijkstra、库容用定义式求和 + 二分，
 *       <b>不复制求解器公式</b>；</li>
 *   <li><b>判据不空转</b>：先断言确有对象可测（样本 / 体积 &gt; 0），0 样本 ⇒ FAIL；</li>
 *   <li><b>初值必须是可分辨的</b>：把大量水灌进单格 = 不可解的冲击波，
 *       显式格式只能缩步长到失效 —— 那是<b>判据构造错误</b>。故初值一律用
 *       "把某区域填到某水位"（水深有界、梯度可分辨）。</li>
 * </ul>
 *
 * <h2>两条容易误判的纪律（本探针实际踩过）</h2>
 * <ol>
 *   <li>守恒/静水判据必须用 {@link ModelParams#still()}（无降水/入渗/蒸发）。
 *       用 {@code defaults()} 时水会<b>合法地</b>缓慢蒸发 ⇒ "体积不守恒"是假失败。</li>
 *   <li>时间 t 由同一套演化方程推进到收敛；{@code converged=false} 是失败信号，
 *       不得当作"结果一样"放过。</li>
 * </ol>
 *
 * <pre>{@code gradlew runHydroModelProbe}</pre>
 */
public final class ModelVerificationProbe {

    private static int failures = 0;
    /** 只跑指定编号的判据（调试用；0 = 全跑）。 */
    private static int only = 0;
    /**
     * "湿"的判定阈值（block）。
     *
     * <p>⚠ 不能用 {@code 1e-9}：收敛后的域内会残留<b>微米级水膜</b>（数值扩散的尾巴），
     * 它们挂在<b>高地</b>上（水面=床面=高地高程）⇒ 会把"最高水面"读到碗缘的 102.33，
     * 于是看起来像"没排干"（本探针实测踩过）。物理上 1e-3 块以下的水膜不成水面。</p>
     */
    private static final double WET = 1e-3;

    public static void main(String[] args) {
        if (args.length > 0) only = Integer.parseInt(args[0]);
        System.out.println("=== P1 物理模型验收（扩散波 / 守恒 / 独立参照）"
                + (only > 0 ? "  只跑 [" + only + "]" : "") + " ===");
        if (sel(1)) t1StaticHold();
        if (sel(2)) t2ZeroInput();
        if (sel(3)) t3ClosedBowl();
        if (sel(4)) t4SpillOracle();
        if (sel(5)) t5SaddleOverflow();
        if (sel(6)) t6NestedBasins();
        if (sel(7)) t7SteepSlope();
        if (sel(8)) t8EvapInfil();
        if (sel(9)) t9NonNegative();
        if (sel(10)) t10GlobalLedger();
        if (sel(11)) t11ResolutionConvergence();
        if (sel(12)) t12TimeStepConvergence();
        if (sel(13)) t13RotationalEquivariance();
        if (sel(14)) t14UnsubmergedBarrier();
        if (sel(15)) t15EnergyAndFailedConvergence();

        System.out.println();
        if (failures == 0) { System.out.println("ALL PASS"); System.exit(0); }
        System.out.println("FAILURES = " + failures);
        System.exit(1);
    }

    private static boolean sel(int n) { return only == 0 || only == n; }

    /** 收敛诊断串（未收敛时看它离容差多远）。 */
    private static String conv(DiffusiveWaveModel m) {
        double dMax = 0;
        for (double v : m.depth()) dMax = Math.max(dMax, v);
        return String.format("[步=%d 末步率=%.2e dMax=%.4f 需<%.2e 受限α=%.3f]",
                m.steps(), m.lastRate(), dMax, 1e-4 * Math.max(1, dMax), m.lastAlpha());
    }

    // ================= [1] 静水保持 =================
    private static void t1StaticHold() {
        int n = 21;
        WaterGrid g = WaterGrid.flat(n, n, 1.0, 100.0, WaterGrid.Boundary.CLOSED, 0);
        DiffusiveWaveModel m = new DiffusiveWaveModel(g, ModelParams.still().withRain(0));
        fill(m, 0, n, 0, n, 103.0);                    // 平水面 3 块
        double before = m.storage();
        m.steadyState();
        double maxDev = 0;
        for (double v : m.depth()) maxDev = Math.max(maxDev, Math.abs(v - 3.0));
        boolean ok = m.converged() && maxDev < 1e-9 && Math.abs(m.storage() - before) <= 0;
        check("静水保持（平底 + 平水面不得自流）", ok,
                String.format("收敛=%b 步数=%d 最大偏差=%.3e 体积变化=%.3e",
                        m.converged(), m.steps(), maxDev, m.storage() - before));
    }

    // ================= [2] 零输入不凭空蓄水 =================
    private static void t2ZeroInput() {
        WaterGrid g = bowl(31, 1.0, 100.0, 0.02, WaterGrid.Boundary.CLOSED, 0);
        DiffusiveWaveModel m = new DiffusiveWaveModel(g, ModelParams.still().withRain(0));
        m.steadyState();
        double maxD = 0;
        for (double v : m.depth()) maxD = Math.max(maxD, v);
        check("零输入不凭空蓄水（干洼地不得自动满湖）", maxD == 0.0 && m.converged(),
                String.format("最大水深=%.3e（须严格为 0）收敛=%b", maxD, m.converged()));
    }

    // ================= [3] 封闭碗：重分布 + 体积守恒 + 解析水位 =================
    private static void t3ClosedBowl() {
        int n = 31;
        WaterGrid g = bowl(n, 1.0, 100.0, 0.02, WaterGrid.Boundary.CLOSED, 0);
        DiffusiveWaveModel m = new DiffusiveWaveModel(g, ModelParams.still().withSteadyTol(1e-7));
        fill(m, 0, n, 0, n, 102.0);
        m.setDepth(n / 2, n / 2, m.depth(n / 2, n / 2) + 0.1); // 非平衡扰动
        double v0 = m.storage();
        m.steadyState();
        double etaRef = ModelOracle.levelForVolume(g, v0);      // 独立参照
        double massErr = Math.abs(m.storage() - v0);

        double mn = Double.MAX_VALUE, mx = -Double.MAX_VALUE;
        int wet = 0;
        for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) {
            if (m.depth(i, j) <= WET) continue;
            double s = m.surface(i, j);
            mn = Math.min(mn, s); mx = Math.max(mx, s); wet++;
        }
        double flatErr = mx - mn;
        double lvlErr = Math.abs(0.5 * (mn + mx) - etaRef);
        boolean ok = m.converged() && wet > 0 && massErr < v0 * 1e-6
                && flatErr < 1e-4 && lvlErr < 0.02;
        check("封闭碗：体积守恒 + 水面水平 + 匹配独立解析水位", ok,
                String.format("收敛=%b 步数=%d 末步率=%.2e 湿格=%d 质量误差=%.3e 水面差=%.3e 水位vs参照=%.5f",
                        m.converged(), m.steps(), m.lastRate(), wet, massErr, flatErr, lvlErr));
    }

    // ================= [4] 最低溢口（对照独立 Dijkstra） =================
    private static void t4SpillOracle() {
        int n = 31;
        double[] bed = new double[n * n];
        double z0 = 100.0, a = 0.02;
        for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) {
            double dx = i - n / 2.0, dz = j - n / 2.0;
            bed[i * n + j] = z0 + a * (dx * dx + dz * dz);
        }
        // 向 +x 开一条下切溢流槽（宽 3 格；**槽口高度 = z0+0.5** 由槽的最后一格决定），
        // 通到东边界。⚠ 槽必须足够宽：1 格宽的浅槽本身过流能力极小，
        //   水会在槽里堆成堰塞，量到的是"槽容"而不是溢口高度（探针实测踩过）。
        for (int i = n / 2; i < n; i++) {
            for (int dj = -1; dj <= 1; dj++) {
                bed[i * n + (n / 2 + dj)] = Math.max(z0 + 0.5 - 0.02 * (i - n / 2), z0 - 3);
            }
        }
        WaterGrid g = WaterGrid.of(n, n, 1.0, bed, WaterGrid.Boundary.OPEN_AT_LEVEL, z0 - 10);
        // 独立参照的终点必须与槽口一致：槽出口高度就是 z0+0.5
        double spillRef = ModelOracle.minimaxSpill(g, g.idx(n / 2, n / 2), z0 - 10);

        DiffusiveWaveModel m = new DiffusiveWaveModel(g, ModelParams.still().withRain(0));
        fill(m, 0, n, 0, n, z0 + 1.5);                 // 灌到槽口之上 ⇒ 应溢流排出
        m.steadyState();
        // ⚠ 只统计【湿格】水面：干格 surface = 床面高度，会把碗缘(109.6)误当成水面。
        double mx = Double.NEGATIVE_INFINITY;
        int wet = 0, mi = -1, mj = -1;
        for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) {
            if (m.depth(i, j) <= WET) continue;
            double s = m.surface(i, j);
            if (s > mx) { mx = s; mi = i; mj = j; }
            wet++;
        }
        boolean ok = m.converged() && wet > 0 && Double.isFinite(spillRef)
                && m.boundaryLossVolume() > 0 && mx <= spillRef + 0.05;
        check("最低溢口：水溢过槽口排出，湿区水面≈独立 minimax 溢口", ok,
                String.format("收敛=%b 独立溢口=%.3f 湿区最高水面=%.3f 湿格=%d 边界排出=%.1f",
                        m.converged(), spillRef, mx, wet, m.boundaryLossVolume()));
        if (!ok) {
            int km = g.idx(mi, mj);
            System.out.printf("        最高水面 @(%d,%d) bed=%.2f depth=%.2f surf=%.2f%n",
                    mi, mj, g.bed[km], m.depth(mi, mj), mx);
            StringBuilder sb = new StringBuilder("        剖面 j=").append(n / 2).append(" (i, bed, depth, surf): ");
            for (int i = n / 2 - 2; i < n; i += 2) {
                int k = g.idx(i, n / 2);
                sb.append(String.format("[%d %.2f %.2f %.2f] ", i, g.bed[k], m.depth(i, n / 2),
                        g.bed[k] + m.depth(i, n / 2)));
            }
            System.out.println(sb);
        }
    }

    // ================= [5] 鞍口溢流（两碗连通） =================
    private static void t5SaddleOverflow() {
        int n = 31;
        double[] bed = new double[n * n];
        for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) {
            double za = 100.0 + 0.02 * ((i - 6) * (i - 6) + (j - 15) * (j - 15));
            double zb = 100.0 + 0.02 * ((i - 24) * (i - 24) + (j - 15) * (j - 15));
            bed[i * n + j] = Math.min(za, zb);
        }
        WaterGrid g = WaterGrid.of(n, n, 1.0, bed, WaterGrid.Boundary.CLOSED, 0);
        DiffusiveWaveModel m = new DiffusiveWaveModel(g, ModelParams.still().withRain(0));
        fill(m, 1, 12, 10, 21, 103.0);                 // 只灌左碗侧，水位高于鞍点(101.62)
        double before = m.storage();
        m.steadyState();
        double left = 0, right = 0;
        for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) {
            double v = m.depth(i, j);
            if (i < n / 2) left += v; else right += v;
        }
        double massErr = Math.abs(m.storage() - before);
        boolean ok = m.converged() && right > 0 && left > 0 && massErr < before * 1e-6;
        check("鞍口溢流：水越过鞍点流入邻碗且总量守恒", ok,
                String.format("收敛=%b 左/右蓄水=%.1f/%.1f 体积误差=%.3e %s",
                        m.converged(), left, right, massErr, conv(m)));
    }

    // ================= [6] 嵌套盆地 =================
    private static void t6NestedBasins() {
        int n = 31;
        double[] bed = new double[n * n];
        for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) {
            double dx = i - n / 2.0, dz = j - n / 2.0;
            double outer = 100.0 + 0.012 * (dx * dx + dz * dz);
            double inner = 99.0 + 0.06 * (dx * dx + dz * dz);
            bed[i * n + j] = Math.min(outer, inner);
        }
        WaterGrid g = WaterGrid.of(n, n, 1.0, bed, WaterGrid.Boundary.CLOSED, 0);
        DiffusiveWaveModel m = new DiffusiveWaveModel(g, ModelParams.still().withRain(0));
        fill(m, n / 2 - 3, n / 2 + 4, n / 2 - 3, n / 2 + 4, 101.5);   // 灌内盆并漫过其盆缘(≈100.25)
        double before = m.storage();
        m.steadyState();
        // 内盆之外（r > 5）是否也有水 ⇒ 证明水确实外溢
        int wetOutside = 0;
        for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) {
            double dx = i - n / 2.0, dz = j - n / 2.0;
            if (dx * dx + dz * dz > 25 && m.depth(i, j) > WET) wetOutside++;
        }
        double massErr = Math.abs(m.storage() - before);
        boolean ok = m.converged() && wetOutside > 0 && massErr < before * 1e-6;
        check("嵌套盆地：内盆蓄满后溢入外盆，总量守恒", ok,
                String.format("收敛=%b 内盆外湿格=%d 内水面=%.3f 体积误差=%.3e %s",
                        m.converged(), wetOutside, m.surface(n / 2, n / 2), massErr, conv(m)));
    }

    // ================= [7] 陡坡输运 + 质量守恒 =================
    private static void t7SteepSlope() {
        int n = 31;
        double[] bed = new double[n * n];
        for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) bed[i * n + j] = 200.0 - 4.0 * i;
        WaterGrid g = WaterGrid.of(n, n, 1.0, bed, WaterGrid.Boundary.OPEN_AT_LEVEL, 100.0);
        DiffusiveWaveModel m = new DiffusiveWaveModel(g, ModelParams.still().withRain(0));
        fill(m, 0, 3, 0, n, 202.0);
        double before = m.storage();
        m.steadyState();
        double lost = m.boundaryLossVolume();
        double bal = (before - m.storage()) - lost;
        boolean ok = m.converged() && lost > 0 && Math.abs(bal) < before * 1e-5;
        check("陡坡：水向低处输运并在边界排出，账本闭合", ok,
                String.format("收敛=%b 排出=%.1f (%.1f%%) 账本差=%.3e",
                        m.converged(), lost, 100 * lost / before, bal));
    }

    // ================= [8] 蒸发 / 入渗账本 =================
    private static void t8EvapInfil() {
        ModelParams p = ModelParams.defaults();          // 含降水/入渗/蒸发
        WaterGrid g = WaterGrid.flat(11, 11, 1.0, 100.0, WaterGrid.Boundary.CLOSED, 0);
        DiffusiveWaveModel m = new DiffusiveWaveModel(g, p);
        double v0 = m.storage();
        m.steadyState();
        double expect = m.inputVolume() - m.infilVolume() - m.evapVolume() - m.boundaryLossVolume();
        double err = Math.abs((m.storage() - v0) - expect);
        boolean ok = m.inputVolume() > 0 && m.infilVolume() > 0 && m.evapVolume() > 0
                && err < Math.max(1e-9, Math.abs(expect) * 1e-6);
        check("蒸发/入渗账本：ΔV = 输入 − 入渗 − 蒸发 − 边界流失", ok,
                String.format("ΔV=%.6f 账本=%.6f 误差=%.3e（输入=%.4f 入渗=%.4f 蒸发=%.4f）",
                        m.storage() - v0, expect, err,
                        m.inputVolume(), m.infilVolume(), m.evapVolume()));
    }

    // ================= [9] 水深非负 =================
    private static void t9NonNegative() {
        int n = 31;
        double[] bed = new double[n * n];
        for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) {
            // 崎岖 + 整体向 +x 下倾 ⇒ 水有明确去处，稳态可达
            // （此前用"中间一大池水 + 全域封闭高低起伏"，是超长瞬态 ⇒ 判据测不出想测的东西）
            bed[i * n + j] = 100.0 + 5.0 * Math.sin(i * 0.6) * Math.cos(j * 0.45) - 0.5 * i;
        }
        WaterGrid g = WaterGrid.of(n, n, 1.0, bed, WaterGrid.Boundary.OPEN_AT_LEVEL, 80.0);
        DiffusiveWaveModel m = new DiffusiveWaveModel(g, ModelParams.defaults());
        fill(m, 0, 6, 0, n, 101.0);                     // 上游一次注入（有限初值）
        m.steadyState();
        double minD = Double.MAX_VALUE;
        for (double v : m.depth()) minD = Math.min(minD, v);
        check("水深非负（崎岖地形 + 降水/蒸发下不得出现负水深）", minD >= 0 && m.converged(),
                String.format("最小水深=%.3e 收敛=%b %s", minD, m.converged(), conv(m)));
    }

    // ================= [10] 全域账本 =================
    private static void t10GlobalLedger() {
        int n = 41;
        double[] bed = new double[n * n];
        for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) {
            double dx = i - n / 2.0, dz = j - n / 2.0;
            bed[i * n + j] = 110.0 + 0.02 * (dx * dx + dz * dz) - 3.0 * Math.sin(i * 0.4);
        }
        WaterGrid g = WaterGrid.of(n, n, 1.0, bed, WaterGrid.Boundary.OPEN_AT_LEVEL, 100.0);
        DiffusiveWaveModel m = new DiffusiveWaveModel(g, ModelParams.defaults());
        double v0 = m.storage();
        m.steadyState();
        double expect = m.inputVolume() - m.infilVolume() - m.evapVolume() - m.boundaryLossVolume();
        double err = Math.abs((m.storage() - v0) - expect);
        double rel = err / Math.max(1.0, Math.abs(expect));
        check("全域账本闭合（降水 + 边界流失）", rel < 1e-6 && m.inputVolume() > 0,
                String.format("ΔV=%.4f 账本=%.4f 误差=%.3e (%.2e%%) 输入=%.1f 排出=%.1f",
                        m.storage() - v0, expect, err, 100 * rel,
                        m.inputVolume(), m.boundaryLossVolume()));
    }

    // ================= [11] 分辨率收敛 =================
    private static void t11ResolutionConvergence() {
        double coarse = bowlLevelAt(1.0);
        double fine = bowlLevelAt(0.5);
        double diff = Math.abs(coarse - fine);
        check("非平衡分辨率对照与独立库容参照一致",
                diff < 1e-3 && Math.abs(coarse) < 1e-3 && Math.abs(fine) < 1e-3,
                String.format("cs=1.0水位误差=%.3e cs=0.5水位误差=%.3e 两解误差差=%.3e",
                        coarse, fine, diff));
    }

    /** 同几何、不同格距：灌同样的物理水位后的平衡水位。 */
    private static double bowlLevelAt(double cs) {
        int n = (int) Math.round(31 / cs);
        double[] bed = new double[n * n];
        double a = 0.02;
        for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) {
            double dx = (i - n / 2.0) * cs, dz = (j - n / 2.0) * cs;
            bed[i * n + j] = 100.0 + a * (dx * dx + dz * dz);
        }
        WaterGrid g = WaterGrid.of(n, n, cs, bed, WaterGrid.Boundary.CLOSED, 0);
        ModelParams base = ModelParams.still().withRain(0);
        ModelParams p = base.withCellSize(cs);      // 用 wither ⇒ 加参数不必改这里
        DiffusiveWaveModel m = new DiffusiveWaveModel(g, p);
        // ★ 必须灌【同一个物理水位】：碗几何以 block 定义，与格距无关。
        //   （此前写成 100+2·cs ⇒ cs=0.5 时只灌到 101，两档根本不同题 ⇒ 假失败。）
        fill(m, 0, n, 0, n, 102.0);
        double volume = m.storage();
        m.setDepth(n / 2, n / 2, m.depth(n / 2, n / 2) + 0.1);
        m.steadyState();
        if (!m.converged()) return Double.NaN;
        double expected = ModelOracle.levelForVolume(g, volume + 0.1 * g.cellArea());
        return flatLevel(m, n) - expected;
    }

    // ================= [12] 时步收敛 =================
    private static void t12TimeStepConvergence() {
        double a = levelWithDt(0.05);
        double b = levelWithDt(0.005);
        double diff = Math.abs(a - b);
        check("非平衡时步对照与独立库容参照一致",
                diff < 1e-3 && Math.abs(a) < 1e-3 && Math.abs(b) < 1e-3,
                String.format("dt=0.05水位误差=%.3e dt=0.005水位误差=%.3e 两解误差差=%.3e", a, b, diff));
    }

    private static double levelWithDt(double dt) {
        int n = 31;
        double[] bed = new double[n * n];
        for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) {
            double dx = i - n / 2.0, dz = j - n / 2.0;
            bed[i * n + j] = 100.0 + 0.02 * (dx * dx + dz * dz);
        }
        WaterGrid g = WaterGrid.of(n, n, 1.0, bed, WaterGrid.Boundary.CLOSED, 0);
        ModelParams p = ModelParams.still().withRain(0).withDtMax(dt);
        DiffusiveWaveModel m = new DiffusiveWaveModel(g, p);
        fill(m, 0, n, 0, n, 102.0);
        double volume = m.storage();
        m.setDepth(n / 2, n / 2, m.depth(n / 2, n / 2) + 0.1);
        m.steadyState();
        if (!m.converged()) return Double.NaN;
        double expected = ModelOracle.levelForVolume(g, volume + 0.1 * g.cellArea());
        return flatLevel(m, n) - expected;
    }

    /** 湿区水面的平均水平（平衡时应为水平面）。 */
    private static double flatLevel(DiffusiveWaveModel m, int n) {
        double mn = Double.MAX_VALUE, mx = -Double.MAX_VALUE;
        for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) {
            if (m.depth(i, j) <= WET) continue;
            double s = m.surface(i, j);
            mn = Math.min(mn, s); mx = Math.max(mx, s);
        }
        return 0.5 * (mn + mx);
    }

    // ================= [13] 旋转等价性（无格方向偏置 —— 重建的核心动机之一） =================
    /**
     * 把同一地形旋转 90° 后求解，解必须同样旋转。
     *
     * <p><b>为什么这条判据最关键</b>：旧实现是 D8（每格只朝 8 邻中一个方向排水），
     * 其方向集在格坐标系里被离散化 ⇒ 平原上会出现<b>网格状笔直长直线</b>
     * （重建的直接起因）。本模型用"连续水面差"决定通量，
     * <b>构造上不应有格方向偏好</b> —— 旋转等价性正是它的可证伪形式。</p>
     *
     * <p>判据形式：{@code sol(rot(terrain)) == rot(sol(terrain))}，逐格比较水深。</p>
     */
    private static void t13RotationalEquivariance() {
        int n = 32;                                   // 偶数便于 90° 旋转
        double[] bed = new double[n * n];
        // 非对称、多尺度地形（含洼地与坡面），避免对称巧合
        for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) {
            bed[i * n + j] = 100.0
                    + 4.0 * Math.sin(i * 0.31) * Math.cos(j * 0.23)
                    + 1.5 * Math.sin(i * 0.11 + j * 0.37)
                    - 0.18 * i;
        }
        double[] rot = new double[n * n];             // rot(terrain): (i,j) → (j, n-1-i)
        for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) {
            rot[j * n + (n - 1 - i)] = bed[i * n + j];
        }

        double[] s1 = solveDepth(n, bed);
        double[] s2 = solveDepth(n, rot);
        double maxDiff = 0;
        for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) {
            double rotated = s1[i * n + j];                       // 原解在 (i,j) 的水深
            double expect = s2[j * n + (n - 1 - i)];              // 旋转后解应在该处
            maxDiff = Math.max(maxDiff, Math.abs(rotated - expect));
        }
        boolean ok = maxDiff < 1e-6;
        check("旋转等价性（旋转地形 ⇒ 解同样旋转；无格方向偏置）", ok,
                String.format("最大水深差=%.3e（仅验证方格的90度对称性，不证明任意角度无方向偏置）", maxDiff));
    }

    /** 在给定床面上推进到稳态并返回水深场。初值必须【旋转不变】才能做等价性对照。 */
    private static double[] solveDepth(int n, double[] bed) {
        WaterGrid g = WaterGrid.of(n, n, 1.0, bed, WaterGrid.Boundary.OPEN_AT_LEVEL, 90.0);
        DiffusiveWaveModel m = new DiffusiveWaveModel(g, ModelParams.defaults());
        // ⚠ 初值必须是旋转不变量：此前只灌"低 i 侧一条带"，旋转后那条带落到别处
        //   ⇒ 两次求解根本不同题，"解不对应"是必然的（假失败）。
        fill(m, 0, n, 0, n, 104.0);                   // 全域灌到同一水位 ⇒ 旋转不变
        m.steadyState();
        if (!m.converged()) {
            double[] invalid = new double[n * n];
            java.util.Arrays.fill(invalid, Double.NaN);
            return invalid;
        }
        return m.depth();
    }
    /** 把矩形区域填到水位 {@code level}（水深有界 ⇒ 初值可分辨）。 */
    // ================= 工具 =================
    /** 把矩形区域填到水位 {@code level}（水深有界 ⇒ 初值可分辨）。 */
    private static void fill(DiffusiveWaveModel m, int i0, int i1, int j0, int j1, double level) {
        for (int i = i0; i < i1; i++) for (int j = j0; j < j1; j++) m.fillTo(i, j, level);
    }

    private static void t14UnsubmergedBarrier() {
        double[] bed = {0, 10};
        WaterGrid g = WaterGrid.of(2, 1, 1, bed, WaterGrid.Boundary.CLOSED, 0);
        DiffusiveWaveModel m = new DiffusiveWaveModel(g, ModelParams.still());
        m.setDepth(0, 0, 1);
        for (int i = 0; i < 20; i++) m.step();
        check("未淹没的床面高坎不透水", m.depth(0, 0) == 1 && m.depth(1, 0) == 0,
                String.format("低床水深=%.6f 高床水深=%.6f", m.depth(0, 0), m.depth(1, 0)));
    }

    private static void t15EnergyAndFailedConvergence() {
        WaterGrid g = bowl(11, 1, 0, 0.02, WaterGrid.Boundary.CLOSED, 0);
        DiffusiveWaveModel m = new DiffusiveWaveModel(g, ModelParams.still().withMaxSteps(1));
        fill(m, 0, 11, 0, 11, 1);
        m.setDepth(5, 5, m.depth(5, 5) + 0.2);
        double volume = m.storage();
        double initial = potentialEnergy(m);
        boolean dissipates = true;
        double previous = initial;
        for (int step = 0; step < 100; step++) {
            m.step();
            double current = potentialEnergy(m);
            dissipates &= current <= previous + 1e-10;
            previous = current;
        }
        DiffusiveWaveModel limited = new DiffusiveWaveModel(g, ModelParams.still().withMaxSteps(1));
        fill(limited, 0, 11, 0, 11, 1);
        limited.setDepth(5, 5, limited.depth(5, 5) + 0.2);
        limited.steadyState();
        check("封闭域势能下降且耗尽步数不伪装收敛",
                dissipates && previous < initial && Math.abs(m.storage() - volume) < 1e-9
                        && !limited.converged(),
                String.format("能量 %.9f→%.9f 质量误差=%.3e 耗尽后converged=%b",
                        initial, previous, m.storage() - volume, limited.converged()));
    }

    private static double potentialEnergy(DiffusiveWaveModel m) {
        WaterGrid g = m.grid();
        double energy = 0;
        for (int i = 0; i < g.nx; i++) for (int j = 0; j < g.nz; j++) {
            double depth = m.depth(i, j);
            energy += g.cellArea() * (g.bed[g.idx(i, j)] * depth + depth * depth / 2);
        }
        return energy;
    }

    /** 抛物碗：{@code z = z0 + a·r²}。 */
    private static WaterGrid bowl(int n, double cs, double z0, double a,
                                  WaterGrid.Boundary b, double level) {
        double[] bed = new double[n * n];
        for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) {
            double dx = (i - n / 2.0) * cs, dz = (j - n / 2.0) * cs;
            bed[i * n + j] = z0 + a * (dx * dx + dz * dz);
        }
        return WaterGrid.of(n, n, cs, bed, b, level);
    }

    private static void check(String name, boolean ok, String detail) {
        if (!ok) failures++;
        System.out.printf("  [%s] %s%n        %s%n", ok ? "PASS" : "FAIL", name, detail);
    }

    private ModelVerificationProbe() { }
}
