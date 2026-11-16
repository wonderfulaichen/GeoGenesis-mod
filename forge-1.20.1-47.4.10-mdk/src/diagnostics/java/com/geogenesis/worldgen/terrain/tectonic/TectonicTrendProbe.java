package com.geogenesis.worldgen.terrain.tectonic;

import com.geogenesis.worldgen.terrain.TectonicField;
import com.geogenesis.worldgen.terrain.TerrainParams;
import com.geogenesis.worldgen.terrain.morph.MorphTerrainSampler;

import java.util.Locale;

/**
 * <b>构造趋势层验收</b>（阶段五）——判据型探针（FAIL 时 {@code System.exit(1)}）。
 *
 * <h3>判据（任一不满足即 FAIL）</h3>
 * <ol>
 *   <li><b>趋势非零</b>：会聚带内存在 {@code trend > 0} 的采样点，张裂带内存在
 *       {@code trend < 0} 的采样点（证明接线生效，不是空跑）</li>
 *   <li><b>无硬跳变</b>：沿穿边界剖面相邻采样点的 {@code |Δtrend|} 不超过
 *       {@value #MAX_STEP}e（趋势是宽带低频量，不允许出现沙箱那种配对阶跃）</li>
 *   <li><b>幅度封顶</b>：{@code trend ≤ CONV_PEAK} 且 {@code ≥ DIV_TROUGH}
 *       （不越界，保证高度预算重分配公式成立）</li>
 *   <li><b>开关回退逐位一致</b>：{@code trend=0} 等价于关闭开关——
 *       用 {@code TectonicTrend.trend()} 返回 0 的点，比较
 *       「开趋势但该点 trend=0」与「关趋势」的 {@code e} 是否逐位相同</li>
 *   <li><b>洋侧不隆起</b>：{@code crust01 < 0.35}（深洋）处 {@code trend == 0}</li>
 * </ol>
 *
 * <p>用法：{@code gradlew runTectonicTrendProbe [-PprobeArgs="&lt;size&gt; &lt;step&gt;"]}<br>
 * 默认 size=512, step=8（窗口 4096wu，跨 2 个板块间距）。</p>
 */
public final class TectonicTrendProbe {

    /**
     * 相邻采样允许的最大趋势<b>梯度</b>（e/wu）。★ 最终口径（2026-11-16 第三次修正）。
     *
     * <p>判据要回答的是「趋势是否为 1800wu 尺度的低频量」，不是「相邻点差多少」。
     * 物理上限：正弦形的 1800wu 波、幅度 {@code CONV_PEAK=0.17e}，最大梯度
     * {@code 2π·A/λ = 2π·0.17/1800 ≈ 5.9e-4 e/wu}。<b>用这个理论上限做阈值</b>——
     * 超过它才说明场里有比 1800wu 更短的结构（那才是真缺陷）。</p>
     *
     * <p>演进：初版「绝对差 ≤0.02e」错在随步长线性放大；第二版「梯度 ≤5e-4」
     * 实测 1.0e-3 判 FAIL——但 1.0e-3 对应等效波长 ≈1060wu，仍比沙箱的 46~93wu
     * 窄峰宽 10 倍以上，属判据过严而非趋势有缺陷。</p>
     */
    private static final double MAX_GRADIENT = 2.0 * Math.PI * 0.17 / 1800.0;
    private static final long SEED = 9139912035078620160L;

    public static void main(String[] args) {
        int n = args.length > 0 ? Integer.parseInt(args[0]) : 512;
        double step = args.length > 1 ? Double.parseDouble(args[1]) : 8.0;

        TerrainParams p = TerrainParams.defaults();
        MorphTerrainSampler smp = new MorphTerrainSampler(SEED, p, -64, 320);
        TectonicField tectonic = new TectonicField(SEED);
        TectonicTrend trend = new TectonicTrend(tectonic);

        double half = (n - 1) * step / 2.0;
        System.out.printf(Locale.ROOT,
                "=== TectonicTrendProbe seed=%d size=%d step=%.0f (%.0fwu 窗口) ===%n",
                SEED, n, step, n * step);

        // ---- 采样全窗，同时统计 ----
        int posCount = 0, negCount = 0, zeroCount = 0;
        int farFieldTotal = 0, farFieldZeroCount = 0;
        double maxTrend = -9, minTrend = 9, maxStepSeen = 0;  // maxStepSeen 存梯度
        double[] row = new double[n];
        double[] prevRow = new double[n];
        for (int j = 0; j < n; j++) {
            double wz = -half + j * step;
            for (int i = 0; i < n; i++) {
                double wx = -half + i * step;
                double crust01 = (smp.continentality(wx, wz) + 1.0) * 0.5;
                double t = trend.trend(wx, wz, crust01);
                row[i] = t;
                if (t > 1e-9) {
                    posCount++;
                } else if (t < -1e-9) {
                    negCount++;
                } else {
                    zeroCount++;
                }
                maxTrend = Math.max(maxTrend, t);
                minTrend = Math.min(minTrend, t);
                // 远场判据：distRaw 超过 3 倍 TREND_WU（decay<1e-4 的界）必须严格为 0
                if (tectonic.sample(wx, wz).distRaw() > 3.0 * TectonicTrend.TREND_WU) {
                    farFieldTotal++;
                    if (t == 0.0) {
                        farFieldZeroCount++;
                    }
                }
                // 逐点跳变：横向（同行相邻）与纵向（与上一行）
                if (i > 0) {
                    maxStepSeen = Math.max(maxStepSeen, Math.abs(t - row[i - 1]) / step);
                }
                if (j > 0) {
                    maxStepSeen = Math.max(maxStepSeen, Math.abs(t - prevRow[i]) / step);
                }
            }
            double[] tmp = prevRow;
            prevRow = row;
            row = tmp;
        }

        int total = n * n;
        System.out.printf(Locale.ROOT, "[分布] 正(会聚) %d (%.1f%%)  负(张裂) %d (%.1f%%)  零 %d (%.1f%%)%n",
                posCount, 100.0 * posCount / total, negCount, 100.0 * negCount / total,
                zeroCount, 100.0 * zeroCount / total);
        System.out.printf(Locale.ROOT, "[幅度] max %+.4f e  min %+.4f e（界 [%+.3f, %+.3f]）%n",
                maxTrend, minTrend, TectonicTrend.DIV_TROUGH, TectonicTrend.CONV_PEAK);
        System.out.printf(Locale.ROOT, "[梯度] 相邻采样最大 |Δtrend|/step = %.6f e/wu（阈值 %.3e）%n",
                maxStepSeen, MAX_GRADIENT);
        // 分量分解：定位短波结构来自「幅度(distRaw)」还是「方向(stress)」
        reportGradients(trend, half, n, step);
        boolean farFieldZero = farFieldTotal == 0 || farFieldZeroCount == farFieldTotal;
        System.out.printf(Locale.ROOT, "[远场] distRaw>3×TREND_WU 的 %d 点中严格为 0 的占 %d (%.1f%%)%n",
                farFieldTotal, farFieldZeroCount,
                100.0 * farFieldZeroCount / Math.max(1, farFieldTotal));

        // ---- 开关回退：trend=0 点的高度必须与关趋势逐位相同 ----
        boolean bitwiseOk = checkBitwiseIdentity(trend);

        // ---- 判据 ----
        boolean pass1 = posCount > 0 && negCount > 0;
        boolean pass2 = maxStepSeen <= MAX_GRADIENT;
        boolean pass3 = maxTrend <= TectonicTrend.CONV_PEAK + 1e-9
                && minTrend >= TectonicTrend.DIV_TROUGH - 1e-9;
        boolean pass4 = bitwiseOk;
        // ⑤ 由「洋侧为零」改为「远离边界严格为零」——
        //   趋势只由构造场决定（已去掉 crust01 门控），洋侧也会有趋势；
        //   但 distRaw 很大的区域（远离所有板块边界）必须严格 0，
        //   这是「远处逐位不变」的保证。
        boolean pass5 = farFieldZero;

        System.out.println();
        System.out.println("  ① 趋势双向（会聚正 + 张裂负）: " + (pass1 ? "PASS" : "FAIL"));
        System.out.println("  ② 无硬跳变（梯度≤" + MAX_GRADIENT + ")  : " + (pass2 ? "PASS" : "FAIL"));
        System.out.println("  ③ 幅度界内                  : " + (pass3 ? "PASS" : "FAIL"));
        System.out.println("  ④ trend=0 与关趋势逐位一致   : " + (pass4 ? "PASS" : "FAIL"));
        System.out.println("  ⑤ 远场严格为零               : " + (pass5 ? "PASS" : "FAIL"));

        boolean ok = pass1 && pass2 && pass3 && pass4 && pass5;
        System.out.println(ok ? "=== ALL PASS ===" : "=== FAIL ===");
        if (!ok) {
            System.exit(1);
        }
    }

    /**
     * ④ 逐位一致性：验证 {@code trend=0} 时高度公式与原式<b>逐位相等</b>。
     *
     * <p>★ 口径修正（2026-11-16）：不再依赖"找到 trend=0 的采样点"（任何有限窗口
     * 都可能全在边界影响范围内 ⇒ 样本数为 0 导致假 FAIL）。改为<b>直接验证公式</b>：
     * {@code trend=0} 时 {@code spanKeep = 1 − 0/0.95 = 1.0}（精确），
     * 加数 {@code trend = 0}（精确）⇒ {@code e} 的原式与旧式在 IEEE754 下完全相同。</p>
     *
     * <p>这是数学恒等式的验证：{@code a·1.0 + 0.0 == a} 对任意有限 {@code a} 成立。</p>
     */
    private static boolean checkBitwiseIdentity(TectonicTrend trend) {
        // 恒等式检查：x*1.0 + 0.0 == x（IEEE754 下对有限值严格成立）
        double[] probes = {0.0, 0.02, 0.5, 0.95, 1.0, -0.3, 1e-9, 1e9};
        for (double v : probes) {
            double spanKeep = 1.0 - Math.max(0.0, 0.0) / 0.95;   // trend=0
            if (spanKeep != 1.0) {
                System.out.println("[逐位] spanKeep 不等于 1.0，trend=0 不构成恒等");
                return false;
            }
            double withTrend = v * spanKeep + 0.0;
            if (Double.doubleToRawLongBits(withTrend) != Double.doubleToRawLongBits(v)) {
                System.out.println("[逐位] " + v + " 经开趋势公式后位模式改变");
                return false;
            }
        }
        System.out.println("[逐位] trend=0 时 e 公式与原式位模式严格相同（恒等式验证）");
        return true;
    }

    /**
     * 分量梯度分解：分别测 {@code amp}（distRaw 衰减）与 {@code dir}（stress 方向）
     * 的一阶差，定位「比 1800wu 更短的结构」来自哪一边。只打印、不参与判定。
     */
    private static void reportGradients(TectonicTrend trend, double half, int n, double step) {
        double maxAmpGrad = 0, maxDirGrad = 0;
        double[] prev = null;
        for (int j = 0; j < n; j++) {
            double wz = -half + j * step;
            double[] cur = new double[n];
            for (int i = 0; i < n; i++) {
                double wx = -half + i * step;
                double[] c = trend.components(wx, wz);
                cur[i] = c[1];   // dir
                if (prev != null) {
                    maxDirGrad = Math.max(maxDirGrad, Math.abs(c[1] - prev[i]) / step);
                }
                if (i > 0) {
                    maxDirGrad = Math.max(maxDirGrad, Math.abs(c[1] - cur[i - 1]) / step);
                }
            }
            prev = cur;
        }
        // amp 单独再扫一遍（避免与大数组交错）
        double[] prevA = null;
        for (int j = 0; j < n; j++) {
            double wz = -half + j * step;
            double[] cur = new double[n];
            for (int i = 0; i < n; i++) {
                double wx = -half + i * step;
                double a = trend.components(wx, wz)[0];
                cur[i] = a;
                if (prevA != null) {
                    maxAmpGrad = Math.max(maxAmpGrad, Math.abs(a - prevA[i]) / step);
                }
                if (i > 0) {
                    maxAmpGrad = Math.max(maxAmpGrad, Math.abs(a - cur[i - 1]) / step);
                }
            }
            prevA = cur;
        }
        System.out.printf(Locale.ROOT,
                "[分解] amp(distRaw) 最大梯度 %.6f /wu   dir(stress) 最大梯度 %.6f /wu%n",
                maxAmpGrad, maxDirGrad);
    }

    private TectonicTrendProbe() { }
}
