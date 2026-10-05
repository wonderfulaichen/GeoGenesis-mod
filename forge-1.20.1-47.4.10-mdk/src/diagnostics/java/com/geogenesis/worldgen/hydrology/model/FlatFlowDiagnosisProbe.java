package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/**
 * <h1>平坦区伪影归因探针：把"河流流动一点就断开"拆成两种互不相同的伪影</h1>
 *
 * <h2>为什么必须有这个探针</h2>
 * <p>{@link CanonicalViewProbe} 的连通性度量只有一个数（1078 个分量 / P50=5 格），
 * 它<b>无法区分</b>两种成因完全不同的伪影。正典图上肉眼可见两类：
 * <ul>
 *   <li><b>平行等距直纹</b>（图像上部大片浅色高原）：河道彼此平行、间距均匀；</li>
 *   <li><b>放射状"喷泉"汇聚</b>（图像左下）：河道向若干点辐散收敛。</li>
 * </ul>
 * 上一轮交接把它们一并归因为"平坦区流向欠定（只能用 EDT 径向收敛）"。
 * <b>本探针要检验这个归因是否成立</b> —— 这是"用局部证据推全局"的反面：
 * 先量，再下结论。
 *
 * <h2>★ 核心区分：靠"床面梯度"诊断，不靠"看起来像什么"</h2>
 * <p>两者都可以用同一张连通分量表来数，但必须按<b>该格自身的床面梯度</b>分开：
 * <table border="1">
 *   <caption>梯度分档</caption>
 *   <tr><th>梯度 |∇bed|</th><th>物理含义</th><th>预期成因</th></tr>
 *   <tr><td>≈ 0（<code>GRAD_NEAR_FLAT</code>）</td>
 *       <td>真·平坦，∇η 也≈0 ⇒ 流速为零</td>
 *       <td><b>流向欠定</b>：只能退回 EDT ⇒ 径向直纹 + 原地打转</td></tr>
 *   <tr><td>明显 &gt; 0</td>
 *       <td>有真实坡度，流向<b>本应</b>有定义</td>
 *       <td><b>不是</b>流向问题 ⇒ 另有成因（本探针要定位）</td></tr>
 * </table>
 * <b>这一条决定设计方向</b>：若分量主要落在"明显有坡度"档，
 * 那么照交接文档的方案（"改用真实地形坡度定方向"）<b>无效</b>，
 * 因为那里的坡度本来就不是零。
 *
 * <h2>★ 第二个问题：平行直纹是"伪影"还是"物理正确"？</h2>
 * <p>这一点必须分清，否则会把正确的东西改错。在<b>坡度均匀</b>的斜面上，
 * 平行流线是<b>物理正确</b>的（无横向输运）。但真实河网不是梳子，
 * 而是<b>树</b>——它之所以成树，是因为地形有<b>局部扰动</b>让流线<b>汇聚</b>。
 * 因此判据是<b>结构性</b>的，不该用肉眼：
 * <ul>
 *   <li><b>汇流树深度</b>：一条正常河应能回溯出多级支流（每级汇流面积更大）。
 *       梳子状河网每一级宽度都相同 ⇒ 深度≈1。</li>
 *   <li><b>汇流面积增益</b>：沿一条河从源头到河口，上游汇水面积应<b>单调增</b>。
 *       梳子状河网该比值全程≈1。</li>
 * </ul>
 * 本探针按 {@code ∇bed} 分档分别报这两个量 ⇒ 可直接判定。
 *
 * <h2>★ 第三个问题（可操作）：三种消除欠定机制，谁有效</h2>
 * <p>消除"∇η=0 ⇒ 方向无定义"，有三条机制路线。本探针做<b>同参 A/B</b>：
 * <ol>
 *   <li><code>baseline</code>：现状（未决格用 bed、有坡度就用坡度）；</li>
 *   <li><code>edgepsilon</code>：<b>FastFlow Algorithm 6 的 ε 坡度</b>
 *       —— 沿 parent 图给水位加一个极小单调增量 {@code w[k] ≥ w[parent] + ε}。
 *       依据：FastFlow(CGF43(7)) Alg.6 {@code w[c] ← max(z[c], w[r_c] + 2^(i−1)ϵ)}。
 *       优点：<b>处处严格单调</b> ⇒ 数学上不可能打转；ε 可取 1e-6，几乎不改水位。</li>
 *   <li><code>noise</code>：给床面加<b>微小确定性噪声</b>（种子化，纯函数）打破平局。
 *       依据：FastFlow §"stochastic selection" 与 FTG 参考的随机受主选择。</li>
 * </ol>
 * <b>注意 A/B 的判据是"分量数"这一结构量，不是单点读数</b> ——
 * 本项目已有教训："用 gap 局部读数推断全局"曾被实测证伪。
 *
 * <p>输出 {@code build/hydro-reassessment/flatdiag-<ts>/}：
 * {@code metrics.txt} + {@code A_<mode>.png}（底图等高线，河道高亮）。
 *
 * <p><b>NOT VERIFIED</b>：方块铺设、实机渲染；本探针只测诊断层数值。
 */
public final class FlatFlowDiagnosisProbe {

    /** 判定"真·平坦"的床面梯度阈值（块/块）。实测典型坡面梯度见 metrics。 */
    private static final double GRAD_NEAR_FLAT = 0.01;

    private FlatFlowDiagnosisProbe() { }

    public static void main(String[] args) throws Exception {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : ViewSpec.SEED;
        int cx = args.length > 1 ? Integer.parseInt(args[1]) : ViewSpec.CENTER_X;
        int cz = args.length > 2 ? Integer.parseInt(args[2]) : ViewSpec.CENTER_Z;
        int radius = args.length > 3 ? Integer.parseInt(args[3]) : ViewSpec.RADIUS;
        double precip = args.length > 4 ? Double.parseDouble(args[4]) : 0.004;
        int samples = args.length > 5 ? Integer.parseInt(args[5]) : 600_000;

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        var climate = new LakeWaterBalance.Climate(precip, 0.002, 0.3);

        // ★ 同参 A/B：三种破平局机制。第 6 参可指定只跑某几档（逗号分隔）。
//   ⚠ baseline 必须包含在内 —— 它同时充当"与改动前逐位一致"的对照。
        java.util.List<String> modes = new java.util.ArrayList<>();
        if (args.length > 6) {
            for (String m : args[6].split(",")) modes.add(m.trim());
        } else {
            modes.add("baseline"); modes.add("edgepsilon"); modes.add("noise");
        }

        StringBuilder log = new StringBuilder();
        log.append("=== 平坦区伪影归因 ===\n");
        log.append(String.format("seed=%d centre=(%d,%d) radius=%d step=%d precip=%.4f samples=%d%n",
                seed, cx, cz, radius, ViewSpec.STEP, precip, samples));
        log.append(String.format("GRAD_NEAR_FLAT=%.4f block/block  modes=%s%n%n",
                GRAD_NEAR_FLAT, modes));

        for (String mode : modes) {
            DirectionRule.setMode(mode);
            long t0 = System.nanoTime();
            var r = HydrologyPipeline.run(gen, tp,
                    new HydrologyPipeline.Request(cx, cz, radius, ViewSpec.STEP, climate, 0,
                            HydrologyPipeline.RiverModel.CONTINUOUS, samples));
            int n = r.nx();
            Report rep = analyse(r, n);

            log.append(String.format("[%s] (%.0fs)%n", mode, (System.nanoTime() - t0) / 1e9));
            log.append(String.format(
                    "  河道格=%d 连通分量=%d 最大分量=%d(%.1f%%) 孤立块(<=2格)=%d(%.1f%%)%n",
                    rep.channelCells, rep.comps, rep.maxComp,
                    100.0 * rep.maxComp / Math.max(1, rep.channelCells),
                    rep.singletons, 100.0 * rep.singletons / Math.max(1, rep.comps)));
            log.append(String.format("  分量大小 P50=%d P90=%d max=%d%n",
                    rep.p50, rep.p90, rep.maxComp));
            log.append(String.format("  GAPS(单格断口)=%d%n", rep.gaps));
            // ★ 按床面梯度分档 —— 本探针的核心
            log.append(String.format(
                    "  分量按∇bed分档: 真平坦档=%d(%.1f%%) 有坡度档=%d(%.1f%%)%n",
                    rep.flatComps, 100.0 * rep.flatComps / Math.max(1, rep.comps),
                    rep.slopeComps, 100.0 * rep.slopeComps / Math.max(1, rep.comps)));
            log.append(String.format(
                    "  河道格中: 真平坦=%d(%.1f%%) 有坡度=%d(%.1f%%)%n",
                    rep.flatCells, 100.0 * rep.flatCells / Math.max(1, rep.channelCells),
                    rep.slopeCells, 100.0 * rep.slopeCells / Math.max(1, rep.channelCells)));
            // ★ 梯度分布直方图：代替"我猜一个阈值再分档"。
            //   若 P50 都远超 GRAD_NEAR_FLAT，则"真平坦"这一档本就无意义 ⇒ 阈值是伪问题。
            log.append("  河道格 ∇bed 分位: P10=");
            log.append(String.format("%.4f P50=%.4f P90=%.4f P99=%.4f max=%.4f  (阈值=%.4f)%n",
                    rep.g10, rep.g50, rep.g90, rep.g99, rep.gmax, GRAD_NEAR_FLAT));
            log.append("  河道格 ∇bed 直方图(对数档): ");
            for (int b = 0; b < rep.hist.length; b++) {
                if (rep.hist[b] == 0) continue;
                // ⚠ 上一版标签写成 [<1e-%d] 并传 6-b ⇒ 打印的方向与实际分档相反
                //   （bucket 6 明明是 <1e-6，却被打成 [<1e-0]）。
                //   后果：直方图与分位数互相矛盾（P50=0.42 却有 14304 格 "<1e-6"）
                //   ⇒ 险些据此得出错误结论。**标签必须自己算，不能靠索引倒推。**
                String label = (b >= 6) ? "<1e-6"
                        : String.format("[1e-%d..1e-%d)", 6 - b, 5 - b);
                log.append(String.format("%s=%d ", label, rep.hist[b]));
            }
            log.append(String.format("%n  河道格 |∇bed| 均值=%.4f%n", rep.gmean));
            log.append(String.format(
                    "  汇流增益(河口/河头 discharge 比) 中位=%.2f%n",
                    rep.gainP50));
            log.append('\n');

            Path dir = Path.of("build/hydro-reassessment/flatdiag-" + System.currentTimeMillis());
            Files.createDirectories(dir);
            ImageIO.write(render(r, n), "png",
                    dir.resolve("A_" + mode + ".png").toFile());
            log.append(String.format("  IMAGE=%s%n%n",
                    dir.resolve("A_" + mode + ".png").toAbsolutePath()));
        }
        DirectionRule.setMode("baseline");
        Files.createDirectories(Path.of("build/hydro-reassessment"));
        Files.writeString(Path.of("build/hydro-reassessment/flatdiag-latest.txt"), log);
        System.out.println(log);
    }

    /** 单个模式下的统计结果。 */
    private record Report(int channelCells, int comps, int maxComp, int singletons,
                          int p50, int p90, int gaps,
                          int flatComps, int slopeComps,
                          int flatCells, int slopeCells,
                          int[] hist, double gmean, double g10, double g50,
                          double g90, double g99, double gmax,
                          double gainP50) { }

    private static Report analyse(HydrologyPipeline.Result r, int n) {
        int cells = n * n;
        // 4-连通分量
        int[] comp = new int[cells];
        java.util.Arrays.fill(comp, -1);
        int comps = 0;
        java.util.List<Integer> sizes = new java.util.ArrayList<>();
        int[] stack = new int[cells];
        for (int k0 = 0; k0 < cells; k0++) {
            if (comp[k0] >= 0 || !r.channel()[k0]) continue;
            int sp = 0;
            stack[sp++] = k0;
            comp[k0] = comps;
            int size = 0;
            while (sp > 0) {
                int c = stack[--sp];
                size++;
                int ci = c / n, cj = c % n;
                if (ci > 0 && comp[c - n] < 0 && r.channel()[c - n]) { comp[c - n] = comps; stack[sp++] = c - n; }
                if (ci < n - 1 && comp[c + n] < 0 && r.channel()[c + n]) { comp[c + n] = comps; stack[sp++] = c + n; }
                if (cj > 0 && comp[c - 1] < 0 && r.channel()[c - 1]) { comp[c - 1] = comps; stack[sp++] = c - 1; }
                if (cj < n - 1 && comp[c + 1] < 0 && r.channel()[c + 1]) { comp[c + 1] = comps; stack[sp++] = c + 1; }
            }
            sizes.add(size);
            comps++;
        }
        java.util.Collections.sort(sizes);
        int maxComp = sizes.isEmpty() ? 0 : sizes.get(sizes.size() - 1);
        int singletons = 0;
        for (int s : sizes) if (s <= 2) singletons++;
        int p50 = pct(sizes, 0.50), p90 = pct(sizes, 0.90);

        int channelCells = 0, flatComps = 0, slopeComps = 0, flatCells = 0, slopeCells = 0;
        // ★ 梯度分位 + 对数直方图：不用"猜一个阈值"来定义"平坦"。
        int[] hist = new int[7];       // [1e-1, 1e-2, ... 1e-6, <1e-6]
        double[] grads = new double[cells];
        int gc = 0;
        for (int k = 0; k < cells; k++) {
            if (r.channel()[k]) {
                channelCells++;
                double g = gradAt(r, n, k);
                grads[gc++] = g;
                if (g < GRAD_NEAR_FLAT) flatCells++; else slopeCells++;
                hist[logBucket(g)]++;
            }
        }
        java.util.Arrays.sort(grads, 0, gc);
        double gmean = 0;
        for (int t = 0; t < gc; t++) gmean += grads[t];
        gmean = (gc > 0) ? gmean / gc : Double.NaN;
        // 逐分量：其成员的平均床面梯度 → 决定该分量归入哪一档
        double[] gradSum = new double[Math.max(1, comps)];
        int[] gradCnt = new int[Math.max(1, comps)];
        for (int k = 0; k < cells; k++) {
            if (comp[k] < 0) continue;
            gradSum[comp[k]] += gradAt(r, n, k);
            gradCnt[comp[k]]++;
        }
        for (int c = 0; c < comps; c++) {
            if (gradCnt[c] == 0) continue;
            if (gradSum[c] / gradCnt[c] < GRAD_NEAR_FLAT) flatComps++; else slopeComps++;
        }

        // GAPS：单格断口
        int gaps = 0;
        for (int i = 1; i < n - 1; i++) {
            for (int j = 1; j < n - 1; j++) {
                int k = i * n + j;
                if (r.sea()[k] || r.lake()[k] || r.channel()[k]) continue;
                if ((r.channel()[k - 1] && r.channel()[k + 1])
                        || (r.channel()[k - n] && r.channel()[k + n])) gaps++;
            }
        }

        // 结构性：最大分量上，河头 → 河口的 discharge 增益
        double gain = gainAndDepth(r, n, comp, comps, maxComp);
        return new Report(channelCells, comps, maxComp, singletons, p50, p90, gaps,
                flatComps, slopeComps, flatCells, slopeCells,
                hist, gmean, pctD(grads, gc, 0.10), pctD(grads, gc, 0.50),
                pctD(grads, gc, 0.90), pctD(grads, gc, 0.99),
                (gc > 0) ? grads[gc - 1] : Double.NaN, gain);
    }

    /** 对数分档：0=≥1e-1, 1=≥1e-2, ... 5=≥1e-6, 6=<1e-6。 */
    private static int logBucket(double g) {
        for (int b = 0; b < 6; b++) {
            if (g >= Math.pow(10.0, -(b + 1))) return b;
        }
        return 6;
    }

    private static double pctD(double[] sorted, int n, double q) {
        if (n <= 0) return Double.NaN;
        int i = (int) Math.round(q * (n - 1));
        return sorted[Math.max(0, Math.min(n - 1, i))];
    }

    /** 该格的床面梯度模（中心差分，块/块）。 */
    private static double gradAt(HydrologyPipeline.Result r, int n, int k) {
        int i = k / n, j = k % n;
        int i0 = Math.max(0, i - 1), i1 = Math.min(n - 1, i + 1);
        int j0 = Math.max(0, j - 1), j1 = Math.min(n - 1, j + 1);
        double dx = (r.bed()[i1 * n + j] - r.bed()[i0 * n + j]) / (i1 - i0);
        double dz = (r.bed()[i * n + j1] - r.bed()[i * n + j0]) / (j1 - j0);
        return Math.hypot(dx, dz);
    }

    /**
     * 结构性度量：沿最大连通分量的一条源头→河口路径，量 discharge 增益与汇流树深度。
     *
     * <p>动机：<b>平行直纹不是靠肉眼判的</b>。在均匀斜面上平行流线是物理正确的，
     * 但真实河网是树 —— 判据是"上游汇水面积沿程单调增"。
     * 梳子状河网每一级增益都≈1 ⇒ 判据能把它和真河网分开。</p>
     */
    /**
     * 结构性度量：最大分量上，河头 → 河口的 discharge 增益。
     *
     * <p><b>修正</b>：原实现用 {@code cnt[c] == big}（{@code big} 初值 -1）去找最大分量，
     * 该条件永不成立 ⇒ 恒返回 NaN。改为先求最大值再定位。</p>
     */
    private static double gainAndDepth(HydrologyPipeline.Result r, int n,
                                       int[] comp, int comps, int maxComp) {
        if (comps <= 0) return Double.NaN;
        int[] cnt = new int[comps];
        for (int k = 0; k < comp.length; k++) if (comp[k] >= 0) cnt[comp[k]]++;
        int big = -1, best = -1;
        for (int c = 0; c < comps; c++) if (cnt[c] > best) { best = cnt[c]; big = c; }
        if (big < 0) return Double.NaN;
        // 在最大分量内沿 parent 下游走，取 discharge 首尾比
        int start = -1;
        double minQ = Double.MAX_VALUE;
        for (int k = 0; k < comp.length; k++) {
            if (comp[k] != big) continue;
            double q = r.discharge()[k];
            if (q < minQ) { minQ = q; start = k; }
        }
        if (start < 0 || minQ <= 0) return Double.NaN;
        int cur = start;
        int budget = 4 * (n + n);
        for (int s = 0; s < budget && cur >= 0; s++) {
            if (r.sea()[cur] || r.lake()[cur]) break;
            int nxt = r.parent()[cur];
            if (nxt < 0) break;
            cur = nxt;
        }
        double endQ = r.discharge()[cur];
        return (endQ > 0) ? endQ / minQ : Double.NaN;
    }

    private static int pct(java.util.List<Integer> sorted, double q) {
        if (sorted.isEmpty()) return 0;
        int i = (int) Math.round(q * (sorted.size() - 1));
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, i)));
    }

    /** 底图 = 无光照等高线（hillshade 会把谷画成脊，本项目已因此误判两次）。 */
    private static BufferedImage render(HydrologyPipeline.Result r, int n) {
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
        double hMin = Double.MAX_VALUE, hMax = -Double.MAX_VALUE;
        for (double v : r.bed()) { hMin = Math.min(hMin, v); hMax = Math.max(hMax, v); }
        double band = Math.max(4.0, (hMax - hMin) / 24.0);
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                int k = i * n + j;
                int color;
                if (r.sea()[k]) color = ViewSpec.SEA_COLOR;
                else if (r.lake()[k]) color = ViewSpec.LAKE_COLOR;
                else if (r.channel()[k]) color = ViewSpec.RIVER_COLOR;
                else color = ViewSpec.contour(r.bed(), n, i, j, hMin, band);
                img.setRGB(i, j, color);
            }
        }
        return img;
    }
}