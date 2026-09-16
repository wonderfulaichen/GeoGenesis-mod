package com.geogenesis.worldgen.terrain;

import java.util.Arrays;

/**
 * 类型场域扭曲【断裂实证】探针（2026-09-16 新建）。
 *
 * <h2>它回答一个问题</h2>
 * <p>{@code TerrainCharacterField} 的域扭曲（{@code warpAmp}）在<b>当前代码</b>下
 * 到底会不会造成"<b>1 格宽断裂</b>"？</p>
 *
 * <h2>为何这个问题需要实证（而非照抄注释）</h2>
 * <p>2026-08-03 的注释把断裂归因于 warp（"类型权重查格点 ±80 块平移让主导沿细胞边界跳跃"），
 * 但<b>同一次改动还修了症状完全相同的另一处</b>：{@code SEARCH_RADIUS} 1→3（3×3 → 7×7 窗口），
 * 其记录写着"移出/移入格点类型不同 → typeWeights 在 1 格内突变 <b>~0.022</b> →
 * argmax 翻转 → eLand 突变 → <b>1 格断裂线（CellBoundaryProbe 实测 @X=400/800）</b>"。
 * ⇒ 两条记录是<b>同一个症状</b>，而窗口那条有实测定位 ⇒ warp 疑似<b>误诊</b>。</p>
 *
 * <p>理论也不支持"warp 致断裂"：位移 {@code w' = w + A·warp(w)} 是<b>连续</b>的，
 * 唯一的非连续点是 7×7 窗口随 {@code floor(w'/400)} 换格，而进出窗口的格点距离 ≥1000 块
 * ⇒ 权重 {@code exp(-12.5) ≈ 3.7e-6}，影响约 {@code 2.6e-5}。</p>
 *
 * <h2>度量：1 块步长下的最大跳变</h2>
 * <p>在 <b>1 块步长</b>的二维栅格上采样，统计相邻格之间的</p>
 * <ul>
 *   <li>{@code Δw} = {@code Σ_i |ΔtypeWeights_i|}（归一化权重，故 ∈[0,2]）；</li>
 *   <li>{@code Δe} = {@code |ΔeLand|}。</li>
 * </ul>
 * <p>断裂的特征是<b>孤立、量级跳变</b>，而不是"梯度整体变大"
 * —— 后者是域扭曲<b>预期</b>的效果（拉伸空间，梯度最多 ×{@code (1+|∇W|)}）。
 * 故除极值外还报告 <b>p99.9</b> 与 <b>尖峰比 = max / p99.9</b>，
 * 并以 <b>2026-08-03 记录的量级 {@value #HISTORICAL_STEP}</b> 作参考线统计超限格数。</p>
 *
 * <h2>判读</h2>
 * <ul>
 *   <li>若各 {@code amp} 的超限计数都 ≈0（或与 {@code amp=0} 同量级）
 *       ⇒ <b>无"1 格断裂"证据</b> ⇒ warp 可用（再去看 {@code TypeAxisProbe} 的收益）；</li>
 *   <li>若某 {@code amp} 出现超限计数骤增 <b>且尖峰比同时飙升</b>
 *       ⇒ 确实存在断裂 ⇒ 需限定幅度或放弃。</li>
 * </ul>
 * <p>⚠ 本探针只回答"有没有断裂"；<b>"改了多少地形 / 排水是否退化"必须另测</b>
 * （{@code runFlowAccumProbe} 的 {@code border} 是哨兵）。</p>
 *
 * <pre>{@code gradlew runWarpFractureProbe [-PprobeArgs="seed half"]}</pre>
 */
public final class WarpFractureProbe {

    /** 默认种子（与其它探针一致，便于交叉对照）。 */
    private static final long DEFAULT_SEED = 12345L;

    /** 默认采样半边长（块）：窗口 ±256 ⇒ 跨过 {@code x=0} / {@code z=0} 两条格边界。 */
    private static final int DEFAULT_HALF = 256;

    /** 被检验的域扭曲幅度（块）。含历史值 80 与 {@code TectonicField} 的 130。 */
    private static final double[] AMPS = {0.0, 40.0, 80.0, 130.0, 200.0};

    /** 2026-08-03 记录的"1 格断裂"量级（typeWeights 1 块突变 ≈0.022）→ 作参考线。 */
    private static final double HISTORICAL_STEP = 0.022;

    private WarpFractureProbe() { }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : DEFAULT_SEED;
        int half = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_HALF;

        System.out.printf("=== WarpFractureProbe seed=%d 窗口±%d 步长=1 块 ===%n", seed, half);
        System.out.printf("参考线：2026-08-03 记录的『1 格断裂』量级 = typeWeights 1 块突变 ≈ %.3f%n",
                HISTORICAL_STEP);
        System.out.println("（Δw = Σ|Δ权重|，归一化后 ∈[0,2]；Δe = |ΔeLand|）");
        System.out.println();
        System.out.printf("%6s | %9s %9s %10s %8s | %9s %9s %8s | %s%n",
                "amp", "maxΔw", "p99.9Δw", "中位Δw", "尖峰比",
                "maxΔe", "p99.9Δe", "尖峰比", "#(Δw>参考线)");
        System.out.println("-".repeat(100));

        double[] over = new double[AMPS.length];
        double baseOver = -1;
        for (int a = 0; a < AMPS.length; a++) {
            Result r = measure(seed, half, AMPS[a]);
            over[a] = r.overCount;
            if (a == 0) baseOver = r.overCount;
            System.out.printf("%6.0f | %9.5f %9.5f %10.7f %8.1f | %9.5f %9.5f %8.1f | %.0f%n",
                    AMPS[a], r.maxDw, r.p999Dw, r.medDw, safeDiv(r.maxDw, r.p999Dw),
                    r.maxDe, r.p999De, safeDiv(r.maxDe, r.p999De), r.overCount);
        }

        System.out.println();
        System.out.println("判读：");
        if (baseOver <= 1) {
            System.out.printf("  - amp=0 基线超限 %.0f 格 ⇒ 参考线（%.3f）在现状下本就几乎不被触发。%n",
                    baseOver, HISTORICAL_STEP);
        }
        System.out.println("  - 若 amp>0 的超限计数与 amp=0 同量级、且尖峰比未飙升"
                + " ⇒ 无『1 格断裂』证据（warp 疑似被误诊）。");
        System.out.println("  - maxΔ 随 amp 变大是【预期】的（域扭曲拉伸空间）"
                + "⇒ 判据看『超限计数』与『尖峰比』，不看 maxΔ 本身。");
        System.out.println("  ⚠ 本探针不回答『地形改了多少 / 排水是否退化』——"
                + "那必须另测 runFlowAccumProbe 的 border 哨兵。");
    }

    /** 单个 amp 的度量结果。 */
    private record Result(double maxDw, double p999Dw, double medDw,
                          double maxDe, double p999De, double overCount) { }

    /** 在 1 块步长栅格上采样并统计相邻格跳变。 */
    private static Result measure(long seed, int half, double amp) {
        TerrainCharacterField.setWarpAmp(amp);

        TerrainParams p = TerrainParams.defaults();
        ContinentField cf = new ContinentField(p);
        TypeLandShape tls = new TypeLandShape(p);
        cf.seed(seed);
        tls.seed(seed);

        int n = 2 * half + 1;
        double[][][] w = new double[n][n][];
        double[][] e = new double[n][n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                double wx = -half + i;
                double wz = -half + j;
                var blend = tls.sampleBlend(wx, wz);
                // ★ 必须克隆：sampleFromUnifiedSpline 会【就地】修改 tw（cAffinity 偏置），
                //   直接持有引用会让"权重跳变"度量被高度计算污染。
                w[i][j] = blend.typeWeights.clone();
                e[i][j] = tls.sample(blend, wx, wz);
            }
        }

        int cap = 2 * n * n;
        double[] dw = new double[cap];
        double[] de = new double[cap];
        int k = 0, over = 0;
        for (int i = 0; i + 1 < n; i++) {
            for (int j = 0; j < n; j++) {
                double l1 = l1Diff(w[i][j], w[i + 1][j]);
                if (l1 > HISTORICAL_STEP) over++;
                dw[k] = l1;
                de[k++] = Math.abs(e[i][j] - e[i + 1][j]);
            }
        }
        for (int j = 0; j + 1 < n; j++) {
            for (int i = 0; i < n; i++) {
                double l1 = l1Diff(w[i][j], w[i][j + 1]);
                if (l1 > HISTORICAL_STEP) over++;
                dw[k] = l1;
                de[k++] = Math.abs(e[i][j] - e[i][j + 1]);
            }
        }
        // 只统计实际写入的前 k 个（两个方向条带长度不同，尾部是 0）
        double[] dwk = Arrays.copyOf(dw, k);
        double[] dek = Arrays.copyOf(de, k);
        return new Result(max(dwk), percentile(dwk, 0.999), median(dwk),
                max(dek), percentile(dek, 0.999), over);
    }

    private static double l1Diff(double[] a, double[] b) {
        double s = 0;
        for (int i = 0; i < a.length && i < b.length; i++) {
            s += Math.abs(a[i] - b[i]);
        }
        return s;
    }

    private static double safeDiv(double a, double b) {
        return b <= 1e-12 ? 0.0 : a / b;
    }

    private static double median(double[] a) {
        double[] c = a.clone();
        Arrays.sort(c);
        int n = c.length;
        return n == 0 ? 0.0 : (n % 2 == 1 ? c[n / 2] : 0.5 * (c[n / 2 - 1] + c[n / 2]));
    }

    private static double percentile(double[] a, double q) {
        double[] c = a.clone();
        Arrays.sort(c);
        if (c.length == 0) return 0.0;
        int idx = (int) Math.round(q * (c.length - 1));
        return c[Math.max(0, Math.min(c.length - 1, idx))];
    }

    private static double max(double[] a) {
        double m = 0;
        for (double v : a) m = Math.max(m, v);
        return m;
    }
}
