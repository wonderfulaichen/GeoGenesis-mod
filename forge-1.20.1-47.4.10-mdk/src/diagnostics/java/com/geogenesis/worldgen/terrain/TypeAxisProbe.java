package com.geogenesis.worldgen.terrain;

import java.util.Arrays;

/**
 * 类型场【轴向对齐】诊断探针（2026-09-16 新建；★ 同日两轮升级：多种子×多相位、补直段长判据）。
 *
 * <h2>为何要补这个探针（一段被遗失的门禁）</h2>
 * <p>CHANGELOG / AGENTS 里长期记着一条已知缺陷：</p>
 * <pre>
 *   量化（新增 [G] 探针）：窗口 (-1500,1500) 最长水平直段 560 wu（0.19×），
 *   且正好落在网格中垂线 z=3204；另一窗口方向比 2.35 …
 * </pre>
 * <p>但全仓搜索 <b>560 / runLength / 水平直段</b> 只能命中 CHANGELOG 与 AGENTS，
 * <b>那个 [G] 探针根本不在仓库里</b> —— 指标被记录了下来，测它的工具却没留下。
 * 后果：<b>该缺陷从那以后再没有被客观测量过</b>（既无法确认现状，
 * 也无法验证任何修复是否真的生效）。本探针把这条门禁补回来。</p>
 *
 * <h2>★ 升级一：多种子 × 多相位（单种子读数不可用于决策）</h2>
 * <p>初版只跑 <b>单种子 + 单窗口</b>，实测立刻暴露弱点：<b>方向比随种子在 0.77~1.50 漂移</b>。
 * 拿它判断"变好还是变坏"，等于重犯 <b>「单次测量下结论」</b>老毛病。
 * 故现在扫 {@code 种子 × 相位} 笛卡尔积并输出<b>方差分解</b>。</p>
 *
 * <p>相位取 {@code CELL_SPACING(400)} 的模，且取 <b>step 的整数倍</b>，
 * 以保证只改变 Voronoi 相位、不改变采样格点自身的相位（干净的对照实验）。</p>
 *
 * <p><b>实测结论（2026-09-16，5 种子 × 4 相位）</b>：跨种子 sd 中位 <b>0.396</b>、
 * 跨相位 sd 中位 <b>0.080</b>（差 5 倍）⇒ <b>方差主要来自「哪个世界」，不是相位</b>。
 * 故 AGENTS 里"某窗口 2.35 属窗口地理取样"的说法<b>应改述为"属种子差异"</b>。</p>
 *
 * <h2>★★ 升级二：判据盲区 —— 方向比对「等长直段」是瞎的（本次实测发现）</h2>
 * <p>规则网格 Voronoi 的边界落在格点<b>中垂线</b>上 ⇒ 方形格点下，
 * <b>0° / 90° / 45° 三个方向都会出现长直段</b>。</p>
 * <p>而"方向比 = 轴向最长 / 对角最长"<b>只比较两者谁更长</b>：
 * 当轴向与对角<b>都很长</b>时比值 ≈1 ⇒ <b>判为"各向同性/达标"，而边界依然是一条条长直线</b>。
 * 实测已复现该盲区：方向比中位 <b>1.16</b>（≤1.5，看起来"已达标"），
 * 而最长轴向直段<b>中位 0.315×窗口 ≈ 945 块、最大 0.640× ≈ 1920 块</b>
 * —— 远长于历史记录的 560 块。</p>
 * <p>⇒ 因此新增 <b>判据3：直段长</b>（绝对量）与方向比（相对量）<b>互补</b>。
 * <b>只保留比例判据 = 会被 45° 直段骗过。</b></p>
 *
 * <h2>★★★ 升级三：判据该怎么设 —— 一条"两面都栽过"的教训</h2>
 * <p>本探针的判据设计经历了<b>两次失败</b>，是同一个病的两面 —— <b>拿单点数值去框一个重尾分布</b>：</p>
 * <ol>
 *   <li><b>太松</b>（初版）：单种子单样本 ≤2.6 ⇒ 实测换种子就到 <b>3.20</b>，
 *       即<b>"靠选对种子"通过</b>（漏报）；</li>
 *   <li><b>太严</b>（第二版）：把 2.6 套到"n 样本 max"上 ⇒ 8 种子集 max 3.20
 *       <b>直接 FAIL，而代码一字未改</b> ⇒ <b>换种子即误报</b>。</li>
 * </ol>
 * <p>⇒ 结论：<b>判据必须用分布统计量（中位 + p90），不能用单点极值。</b>
 * 现为：判据1 中位 ≤1.5（各向同性目标）· 判据2 p90 ≤2.6（旧阈值改口径）·
 * 判据3 直段中位 ≤0.40×窗口。<b>max 只报告、不判定。</b></p>
 *
 * <h2>另一条实测指纹：直段长对相位不变 ⇒ 是结构性的</h2>
 * <p>{@code seed=777} 与 {@code seed=42} 在 4 个相位下的方向比<b>精确不变</b>
 * （1.00 / 1.16）—— 因为最长直段的长度由<b>格点几何</b>决定（两个 Voronoi 顶点之间），
 * 与窗口如何切割无关。⇒ <b>"相位不变"本身就是格点伪影的判据</b>（值得后人记住）。</p>
 *
 * <h2>度量方法</h2>
 * <p>统计 4 个方向（+x、+z、两条对角）上"主导类型不变"的<b>最长连续段</b>：</p>
 * <ul>
 *   <li><b>方向比</b> = 轴向最长 / 对角最长 ⇒ ≈1 各向同性；明显 &gt;1 轴向主导；</li>
 *   <li><b>直段长</b> = 最长段（块）/ 窗口 ⇒ <b>绝对"直线感"</b>，与方向无关。</li>
 * </ul>
 * <p>⚠ 段长按<b>实际距离（块）</b>计 —— 对角每步是 {@code step×√2} 块，
 * 若不换算就会系统性低估对角段长、虚增方向比。</p>
 *
 * <h2>判据是【锚定】而非【达标】</h2>
 * <p>本缺陷至今<b>未修</b>（当年试修"种子抖动"虽把直段 560→120，却改坏排水格局
 * 导致 chunk 边界出现 12.8 块水面落差而被<b>全量回退</b>）。故判据把指标
 * <b>锚定</b>在历史水平：现在会通过，但一旦有人把它改得更糟就立刻报警。</p>
 *
 * <pre>{@code gradlew runTypeAxisProbe [-PprobeArgs="seeds window step"]}</pre>
 * <p>{@code seeds} 为逗号分隔（默认 {@link #DEFAULT_SEEDS}）。</p>
 */
public final class TypeAxisProbe {

    /** 默认种子集：多种子是本次升级的核心，单种子读数已被证伪为不可靠。 */
    private static final long[] DEFAULT_SEEDS = {12345L, 777L, 98765L, 24680L, 42L};

    /**
     * 窗口相位（块，对 {@code CELL_SPACING=400} 取模）。
     * 全部为 step 的整数倍 ⇒ 只移动 Voronoi 相位，不动采样格点相位。
     */
    private static final int[] DEFAULT_PHASES = {0, 96, 192, 288};

    /**
     * 判据2：方向比 <b>p90</b> 锚定上界 —— 沿用历史单种子阈值 {@code 2.6}，
     * 但<b>口径改为"90 分位"</b>。
     *
     * <p>⚠ 为何不是 max：曾把 2.6 直接套到"n 样本 max"上，8 种子集实测 max <b>3.20</b>
     * ⇒ 判据 FAIL，而<b>代码一字未改</b>。<b>把单样本阈值套到 max 上 = 换种子即误报</b>
     * （与旧版"单样本太松、靠运气通过"是同一个病的两面：拿单点数值框重尾分布）。</p>
     *
     * <p>标定基线：n=64 p90 <b>1.87</b>；n=20 p90 <b>1.60</b> ⇒ 余量 ~28~39%。</p>
     */
    private static final double RATIO_ANCHOR_P90 = 2.6;

    /**
     * 判据1：方向比【中位】上界 = 文档记载的各向同性目标值。
     *
     * <p>标定基线：n=64（16 种子×4 相位）中位 <b>1.11</b>；n=20（默认集）<b>1.16</b> ⇒ 余量 ~26~29%。</p>
     */
    private static final double RATIO_TARGET_MEDIAN = 1.5;

    /**
     * 判据3：轴向最长直段 / 窗口 的【中位】锚定上界。
     *
     * <p>标定依据（2026-09-16）：n=64 中位 <b>0.307</b>、n=20 中位 <b>0.315</b>、最大 0.640。
     * 取 <b>0.40</b> = 基线 × ~1.30（留 ~27~30% 余量，避免换种子即误报）。</p>
     */
    private static final double STRAIGHT_ANCHOR_MEDIAN = 0.40;

    private static final double SQRT2 = Math.sqrt(2.0);

    private TypeAxisProbe() { }

    public static void main(String[] args) {
        long[] seeds = args.length > 0 ? parseSeeds(args[0]) : DEFAULT_SEEDS;
        int window = args.length > 1 ? Integer.parseInt(args[1]) : 3000;
        int step = args.length > 2 ? Integer.parseInt(args[2]) : 8;
        int ns = seeds.length, np = DEFAULT_PHASES.length;

        double[][] ratio = new double[ns][np];
        // 方向段长（块）：0=水平H 1=垂直V 2=对角↘ 3=对角↗
        double[][][] len = new double[4][ns][np];

        System.out.printf("=== TypeAxisProbe 多种子×多相位 seed=%d phase=%d window=%d step=%d ===%n",
                ns, np, window, step);
        System.out.println("（历史基线：最长水平直段 560 wu / 0.19×，窗口 (-1500,1500)）");
        System.out.println();
        System.out.println("[1] 逐样本方向比 = 轴向最长 / 对角最长");
        for (int s = 0; s < ns; s++) {
            StringBuilder row = new StringBuilder(String.format("    seed=%8d ", seeds[s]));
            for (int p = 0; p < np; p++) {
                Sample m = measure(seeds[s], window, step, DEFAULT_PHASES[p]);
                ratio[s][p] = m.ratio();
                len[0][s][p] = m.h();
                len[1][s][p] = m.v();
                len[2][s][p] = m.d1();
                len[3][s][p] = m.d2();
                row.append(String.format("| phase=%3d %5.2f ", DEFAULT_PHASES[p], m.ratio()));
            }
            row.append(String.format("| 行中位 %5.2f", median(ratio[s])));
            System.out.println(row);
        }

        double[] allRatio = flatten(ratio);
        System.out.println();
        System.out.printf("[2] 方向比聚合（n=%d）: 中位=%.2f  均值=%.2f  最小=%.2f  最大=%.2f  p90=%.2f%n",
                allRatio.length, median(allRatio), mean(allRatio),
                min(allRatio), max(allRatio), percentile(allRatio, 0.90));

        // ★ 判据盲区所必需的绝对值：直段长度（块）
        String[] names = {"水平 H    ", "垂直 V    ", "对角 ↘ D1 ", "对角 ↗ D2 "};
        double[] hArr = flatten(len[0]);
        double[] vArr = flatten(len[1]);
        for (int k = 0; k < 4; k++) {
            double[] a = flatten(len[k]);
            System.out.printf("    最长直段 %s中位=%7.0f 块 (%.3f×win)  最大=%7.0f 块 (%.3f×win)%n",
                    names[k], median(a), median(a) / window, max(a), max(a) / window);
        }
        // 轴向 = 逐样本 max(H,V)，与方向比的"轴向"口径保持一致
        double[] axisLen = new double[hArr.length];
        for (int i = 0; i < axisLen.length; i++) axisLen[i] = Math.max(hArr[i], vArr[i]);
        double[] axisRunFrac = new double[axisLen.length];
        for (int i = 0; i < axisLen.length; i++) axisRunFrac[i] = axisLen[i] / window;
        System.out.printf("    最长轴向直段 max(H,V)  中位=%7.0f 块 (%.3f×win)  最大=%7.0f 块 (%.3f×win)%n",
                median(axisLen), median(axisRunFrac), max(axisLen), max(axisRunFrac));

        System.out.println();
        System.out.println("[3] ★ 方差分解（谁在制造方差？）");
        double sdAcrossSeeds = median(columnSd(ratio));
        double sdAcrossPhases = median(rowSd(ratio));
        System.out.printf("    跨种子 sd 的中位（固定相位）= %.3f%n", sdAcrossSeeds);
        System.out.printf("    跨相位 sd 的中位（固定种子）= %.3f%n", sdAcrossPhases);
        System.out.println("    判读：" + (sdAcrossSeeds >= sdAcrossPhases
                ? "种子 > 相位 ⇒ 读数主要随【世界】而变，非相位巧合"
                : "相位 > 种子 ⇒ 单窗口读数依赖采样相位"));

        System.out.println();
        // ★ 判据全部用【分布统计量】；max 只报告不判定（见 RATIO_ANCHOR_P90 的注释）
        boolean p1 = median(allRatio) <= RATIO_TARGET_MEDIAN;
        boolean p2 = percentile(allRatio, 0.90) <= RATIO_ANCHOR_P90;
        boolean p3 = median(axisRunFrac) <= STRAIGHT_ANCHOR_MEDIAN;
        System.out.printf("[判据1] 中位方向比 ≤ %.1f（各向同性目标）: %s（实测 %.2f）%n",
                RATIO_TARGET_MEDIAN, p1 ? "PASS" : "FAIL", median(allRatio));
        System.out.printf("[判据2] p90 方向比 ≤ %.1f（沿用旧单种子阈值，改【90 分位】口径）: %s（实测 %.2f）%n",
                RATIO_ANCHOR_P90, p2 ? "PASS" : "FAIL", percentile(allRatio, 0.90));
        System.out.printf("        （max=%.2f 仅报告不判定 —— 曾用作 gate，8 种子集 3.20 误报）%n",
                max(allRatio));
        System.out.printf("[判据3] 轴向最长直段中位 ≤ %.2f×窗口（★ 补方向比的 45° 盲区）: %s（实测 %.3f）%n",
                STRAIGHT_ANCHOR_MEDIAN, p3 ? "PASS" : "FAIL", median(axisRunFrac));
        System.out.println("     说明：判据1 是【相对量】（各向异性），判据3 是【绝对量】（直线感）。");
        System.out.println("     方形格点下 0°/45°/90° 三个方向都长 ⇒ 只看向对比值会被骗过（实测已复现）。");
        System.out.println("     当年试修『种子抖动』可把直段降到 120 wu，但因改坏排水（chunk 边界 12.8 块水面落差）已回退。");
        System.out.println((p1 && p2 && p3) ? "ALL PASS" : "FAILURES");
    }

    /** 单样本（一种子 × 一相位）：方向比 + 四方向最长直段（块）。 */
    private record Sample(double ratio, double h, double v, double d1, double d2) { }

    /** 采样主导类型 → 四方向最长边界直线段 → 方向比。 */
    private static Sample measure(long seed, int window, int step, int phase) {
        TerrainParams p = TerrainParams.defaults();
        ContinentField cf = new ContinentField(p);
        TypeLandShape tls = new TypeLandShape(p);
        cf.seed(seed);
        tls.seed(seed);

        int G = window / step;
        int x0 = -window / 2 + phase;
        int z0 = -window / 2 + phase;

        int[][] dom = new int[G][G];
        for (int i = 0; i < G; i++) {
            for (int j = 0; j < G; j++) {
                var blend = tls.sampleBlend(x0 + i * step, z0 + j * step);
                dom[i][j] = TypeLandShape.dominantFromWeights(blend.typeWeights).ordinal();
            }
        }

        // ★ 必须量【边界线】的直线段，而不是"同一类型的连续区域"：
        //   后者的长度由细胞尺寸（CELL_SPACING=400）决定，与轴向无关。
        //   边界像素定义：dom[p] != dom[p+n]（n 为法向）；直线段沿方向 d 连续。
        double h = maxBoundaryRun(dom, G, 1, 0, 0, 1) * (double) step;      // 水平边界，沿 +x
        double v = maxBoundaryRun(dom, G, 0, 1, 1, 0) * (double) step;      // 垂直边界，沿 +z
        double d1 = maxBoundaryRun(dom, G, 1, 1, 1, -1) * step * SQRT2;     // 对角 ↘
        double d2 = maxBoundaryRun(dom, G, 1, -1, 1, 1) * step * SQRT2;     // 对角 ↗

        double axis = Math.max(h, v);
        double diag = Math.max(d1, d2);
        double ratio = diag > 0 ? axis / diag : Double.NaN;
        return new Sample(ratio, h, v, d1, d2);
    }

    /**
     * 沿方向 {@code d} 统计【边界像素】连续成线的最长段（格数）。
     *
     * <p>边界像素：{@code dom[p] != dom[p+n]}（{@code n} 为法向）。
     * 例如"水平边界"取 {@code n=(0,1)}、{@code d=(1,0)} ⇒ 得到沿 x 延伸的、
     * 位于某个 {@code z} 上的水平边界直线段 —— 即历史指标"最长水平直段"。</p>
     *
     * <p>只在<b>段的起点</b>展开（前一格不是边界或越界）⇒ 每格最多被访问两次，O(G²)。</p>
     */
    private static int maxBoundaryRun(int[][] g, int G, int dx, int dz, int nx, int nz) {
        boolean[][] b = new boolean[G][G];
        for (int i = 0; i < G; i++) {
            for (int j = 0; j < G; j++) {
                int ni = i + nx, nj = j + nz;
                b[i][j] = ni >= 0 && ni < G && nj >= 0 && nj < G && g[ni][nj] != g[i][j];
            }
        }
        int best = 0;
        for (int i = 0; i < G; i++) {
            for (int j = 0; j < G; j++) {
                if (!b[i][j]) continue;
                int pi = i - dx, pj = j - dz;
                boolean isStart = pi < 0 || pi >= G || pj < 0 || pj >= G || !b[pi][pj];
                if (!isStart) continue;
                int len = 1, ci = i, cj = j;
                while (true) {
                    int ni = ci + dx, nj = cj + dz;
                    if (ni < 0 || ni >= G || nj < 0 || nj >= G || !b[ni][nj]) break;
                    len++;
                    ci = ni;
                    cj = nj;
                }
                if (len > best) best = len;
            }
        }
        return best;
    }

    // ================= 统计工具（容忍 NaN：对角段为 0 时 ratio 会是 NaN） =================

    /** 展平二维数组并剔除 NaN。 */
    private static double[] flatten(double[][] m) {
        double[] out = new double[m.length * m[0].length];
        int k = 0;
        for (double[] row : m) {
            for (double val : row) out[k++] = val;
        }
        return stripNaN(out);
    }

    /** 每行 sd（固定种子、跨相位）。 */
    private static double[] rowSd(double[][] m) {
        double[] out = new double[m.length];
        for (int i = 0; i < m.length; i++) out[i] = sd(stripNaN(m[i]));
        return out;
    }

    /** 每列 sd（固定相位、跨种子）。 */
    private static double[] columnSd(double[][] m) {
        double[] out = new double[m[0].length];
        for (int j = 0; j < m[0].length; j++) {
            double[] col = new double[m.length];
            for (int i = 0; i < m.length; i++) col[i] = m[i][j];
            out[j] = sd(stripNaN(col));
        }
        return out;
    }

    private static double[] stripNaN(double[] a) {
        int n = 0;
        for (double v : a) if (!Double.isNaN(v)) n++;
        double[] out = new double[n];
        int k = 0;
        for (double v : a) if (!Double.isNaN(v)) out[k++] = v;
        return out;
    }

    private static double median(double[] a) {
        if (a.length == 0) return Double.NaN;
        double[] c = a.clone();
        Arrays.sort(c);
        int n = c.length;
        return n % 2 == 1 ? c[n / 2] : 0.5 * (c[n / 2 - 1] + c[n / 2]);
    }

    private static double mean(double[] a) {
        if (a.length == 0) return Double.NaN;
        double s = 0;
        for (double v : a) s += v;
        return s / a.length;
    }

    private static double sd(double[] a) {
        if (a.length < 2) return 0.0;
        double mu = mean(a);
        double s = 0;
        for (double v : a) s += (v - mu) * (v - mu);
        return Math.sqrt(s / (a.length - 1));
    }

    private static double percentile(double[] a, double q) {
        if (a.length == 0) return Double.NaN;
        double[] c = a.clone();
        Arrays.sort(c);
        int idx = (int) Math.round(q * (c.length - 1));
        return c[Math.max(0, Math.min(c.length - 1, idx))];
    }

    private static double min(double[] a) {
        double m = Double.POSITIVE_INFINITY;
        for (double v : a) m = Math.min(m, v);
        return a.length == 0 ? Double.NaN : m;
    }

    private static double max(double[] a) {
        double m = Double.NEGATIVE_INFINITY;
        for (double v : a) m = Math.max(m, v);
        return a.length == 0 ? Double.NaN : m;
    }

    private static long[] parseSeeds(String s) {
        String[] parts = s.split(",");
        long[] out = new long[parts.length];
        for (int i = 0; i < parts.length; i++) out[i] = Long.parseLong(parts[i].trim());
        return out.length == 0 ? DEFAULT_SEEDS : out;
    }
}
