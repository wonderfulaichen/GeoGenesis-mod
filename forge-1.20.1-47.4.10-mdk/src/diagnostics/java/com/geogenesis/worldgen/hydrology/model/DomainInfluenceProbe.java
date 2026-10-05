package com.geogenesis.worldgen.hydrology.model;

/**
 * P2 可行性门槛：<b>影响半径实测</b>（决定"有限 margin 是否够用"）。
 *
 * <h2>为什么这是 P2 的核心问题</h2>
 * <p>世界生成只能按 chunk 算有限窗口，但稳态是<b>椭圆问题</b>（信息可传到无穷远）。
 * 于是"按窗口求解"等价于"把窗口外的地形换掉" ⇒ 会引入误差。
 * plan §4.2 明确要求：<b>若"固定有限成本 + 稳定无限结果"不成立，就不接生产</b>，
 * 不得靠缩半径止痛。所以必须先量出：<b>一处改动会影响多远</b>。</p>
 *
 * <h2>测法（扰动实验，直接、可解释）</h2>
 * <ol>
 *   <li>全域求解（N×N，向东排水）⇒ 基准解；</li>
 *   <li>在目标窗口<b>上游 d 远处</b>把地形抬高 +Δ ⇒ 重解；</li>
 *   <li>量目标窗口内 {@code max|Δ水深|}；</li>
 *   <li>扫 d ⇒ 得到"影响随距离的衰减"。</li>
 * </ol>
 * <p>截断一个窗口的效果与"把窗外地形改掉"等价 ⇒ 这个衰减曲线<b>就是</b>
 * 截断误差随 margin 的曲线。</p>
 *
 * <h2>预期与判据</h2>
 * <ul>
 *   <li>{@code decay = 0}（纯湿润）：影响应呈<b>幂律慢衰减</b> ⇒ margin 需求大；</li>
 *   <li>{@code decay > 0}（蒸发/入渗）：影响应<b>指数衰减</b> ⇒ margin 有界；</li>
 *   <li>判据：在给定容差下，指数情形的 margin 必须<b>存在且可量化</b>；
 *       若两者都要求不可接受的 margin，则 P2 门槛不通过（如实报 FAIL）。</li>
 * </ul>
 *
 * <pre>{@code gradlew runHydroDomainProbe [-PprobeArgs="N rained"]}</pre>
 */
public final class DomainInfluenceProbe {

    /** 目标窗口半宽（格）。 */
    private static final int HALF = 8;

    public static void main(String[] args) {
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 64;
        boolean rain = args.length > 1 && Boolean.parseBoolean(args[1]);
        System.out.printf("=== P2 影响半径实测 N=%d 目标窗口=%d² 降水=%b ===%n", n, 2 * HALF, rain);
        System.out.println("地形：向东(+x)下倾1.0/格 + 多尺度起伏；全部外侧边界OPEN@130");
        System.out.println("扰动：上游 d 远处抬高 5 块；量目标窗口 max|Δ水深|");

        System.out.println("\n--- A. 无蒸发（decay = 0，纯守恒输运）---");
        double[] a = sweep(n, rain, 0.0, 0.0);

        System.out.println("\n--- B. 常率蒸发（势蒸散，不改变算子性质）---");
        double[] b = sweep(n, rain, 0.0, 3.0e-4);

        System.out.println("\n--- C. 深度比例蒸发（引入 −λ·d ⇒ 预期指数衰减）---");
        double[] c = sweep(n, rain, 3.0e-3, 0.0);

        System.out.println("\n=== 判读（比值 = d=最远 / d=最近）===");
        System.out.printf("A 无蒸发      : %s%n", verdict(a));
        System.out.printf("B 常率蒸发    : %s%n", verdict(b));
        System.out.printf("C 深度比例蒸发: %s%n", verdict(c));
        System.out.println("\n注：数值是【目标窗口内 max|Δ水深|】(block)；");
        System.out.println("    这只是单域地形扰动响应，不是窗口截断误差上界。");
        System.out.println("    不能据此证明无限世界相容或固定margin可行；需独立分片对照。");
    }

    /**
     * 扫扰动距离 d，返回各 d 处目标窗口的 max|Δ水深|。
     *
     * @param decayDepth 深度比例蒸发系数（1/时间）
     * @param evapRate   常率蒸发（block/时间）
     */
    private static double[] sweep(int n, boolean rain, double decayDepth, double evapRate) {
        int ci = n / 2, cj = n / 2;
        // 扰动必须落在域内且留住边距：d 上限 = ci - 3
        java.util.List<Integer> dl = new java.util.ArrayList<>();
        for (int d : new int[]{4, 8, 12, 16, 24, 32}) {
            if (ci - d >= 3) dl.add(d);
        }
        double[] out = new double[dl.size()];

        double[] base = solve(n, rain, decayDepth, evapRate, -1, 0.0);
        for (int t = 0; t < dl.size(); t++) {
            int d = dl.get(t);
            int pi = ci - d;                          // 上游（西侧）扰动位置
            double[] pert = solve(n, rain, decayDepth, evapRate, pi, 5.0);   // 抬高 5 块
            double mx = 0, sum = 0;
            int cnt = 0;
            for (int i = ci - HALF; i <= ci + HALF; i++) {
                for (int j = cj - HALF; j <= cj + HALF; j++) {
                    int k = i * n + j;
                    double dd = Math.abs(pert[k] - base[k]);
                    mx = Math.max(mx, dd);
                    sum += dd;
                    cnt++;
                }
            }
            out[t] = mx;
            System.out.printf("  d=%-3d（距窗口上游缘 %2d 格）⇒ max|Δd|=%.3e  mean|Δd|=%.3e%n",
                    d, d - HALF, mx, sum / cnt);
        }
        return out;
    }

    /**
     * 求解一次。
     *
     * @param pI 若 ≥0，在该列把地形抬高 {@code pAmp}
     */
    private static double[] solve(int n, boolean rain, double decayDepth, double evapRate,
                                  int pI, double pAmp) {
        double[] bed = new double[n * n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                double z = 200.0 - 1.0 * i
                        + 1.2 * Math.sin(i * 0.42) * Math.cos(j * 0.31)
                        + 0.6 * Math.sin(i * 0.17 + j * 0.23);
                if (i == pI) z += pAmp;
                bed[i * n + j] = z;
            }
        }
        WaterGrid g = WaterGrid.of(n, n, 1.0, bed, WaterGrid.Boundary.OPEN_AT_LEVEL, 130.0);
        ModelParams base = ModelParams.defaults();
        ModelParams p = new ModelParams(1.0, base.manningN(),
                rain ? 2.0e-3 : 0.0, 0.0, evapRate, decayDepth,
                base.dtMax(), base.steadyTol(), base.maxSteps());
        DiffusiveWaveModel m = new DiffusiveWaveModel(g, p);
        m.steadyState();
        if (!m.converged()) {
            throw new IllegalStateException(String.format(
                    "Unconverged solution: steps=%d rate=%.3e; influence inference is invalid",
                    m.steps(), m.lastRate()));
        }
        return m.depth();
    }

    private static double maxOf(double[] a) {
        double m = 0;
        for (double v : a) m = Math.max(m, v);
        return m;
    }

    private static String verdict(double[] errs) {
        if (errs.length < 2) return "样本不足，无法判定";
        // ★ 必须看【远端的行为】，不能用"最近/最远"比值：d 很小时扰动紧贴窗口缘，
        //   误差天然巨大 ⇒ 比值恒很小 ⇒ 会把"平台不衰减"误判成"强衰减"（本探针踩过）。
        //   正解：只看 d ≥ 窗口半宽的那些点（扰动已在窗口之外），看它们是否继续下降。
        int start = 0;
        while (start < errs.length && errs[start] > 0) {
            // 找到第一个"扰动在窗口之外"的点：d > HALF ⇒ 由调用方保证首点 d=4 可能在内
            if (start == 0) { start = 1; break; }
            break;
        }
        double first = errs[start];
        double last = errs[errs.length - 1];
        if (Double.isNaN(first) || Double.isNaN(last)) return "无法判定（扰动越界）";
        if (last <= 0) return "★ 最远处误差为 0（影响已衰减到零）";
        if (first <= 0) return "远端起点误差为 0";
        double ratio = last / first;
        String shape = ratio < 0.10 ? "本实验远端响应下降，尚未证明全域截断误差"
                : ratio < 0.35 ? "本实验响应下降，尚未证明分片相容"
                : ratio < 0.70 ? "本实验响应缓慢下降"
                : "本实验未观察到持续下降，不能推断全域影响范围";
        return String.format("远端 %.3e → 最远 %.3e（比值 %.3f）⇒ %s", first, last, ratio, shape);
    }

    private DomainInfluenceProbe() { }
}
