package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.awt.image.BufferedImage;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;

/**
 * <h1>河道连通分量的【终止原因】分类 —— 判定 河流到底断在哪、还是本该断</h1>
 *
 * <h2>为什么必须先做这个（而不是直接看 分量数）</h2>
 * <p>上一轮实测：r=700 下河道 83,729 格碎成 <b>1,076 个连通分量</b>，P50=<b>5 格</b>。
 * 看起来像 河流流动一点就断开了。但 <b>分量数本身不是缺陷指标</b>：</p>
 * <ul>
 *   <li>本图有 <b>71,382 湖格（3.64%）+ 604 个干湖盆</b>
 *       ⇒ 大量河道<b>本就应该</b>终止于湖/海，各算一个独立分量是<b>物理正确</b>的；</li>
 *   <li>窗口是有限的 ⇒ 边缘截断也是必然，不是缺陷；</li>
 *   <li>真正的缺陷只有一个形态：<b>河在坡面凭空断掉，且那里其实有水</b>。</li>
 * </ul>
 * <p>⇒ <b>在分清这四类之前动手改，极可能在修一个健康的东西。</b>
 * 这与项目已有的 GAPS 盲区同型（GAPS 只抓单格断口），只是方向相反：
 * GAPS 漏报，本次是<b>误报</b>。</p>
 *
 * <h2>★ 四类终止，各有<b>不同的修复方式</b>（这是分类的价值）</h2>
 * <table border="1">
 *   <caption>分类与判据</caption>
 *   <tr><th>类</th><th>判据</th><th>含义</th><th>要不要修</th></tr>
 *   <tr><td><b>WATER</b></td>
 *       <td>分量的任何格与 湖/海 格 4-邻接</td>
 *       <td>河流终止于水体 —— <b>物理正确</b></td>
 *       <td><b>不修</b></td></tr>
 *   <tr><td><b>BORDER</b></td>
 *       <td>分量触及窗口边框</td>
 *       <td>窗口截断 —— 有限域的固有限制</td>
 *       <td><b>不修</b>（生产有 borderBuffer）</td></tr>
 *   <tr><td><b>SUFFICIENT</b></td>
 *       <td>坡面出口邻格的 <b>3×3 平滑流量 ≥ 成河门槛</b>，却没成河</td>
 *       <td><b>水够、门槛也说够，就是没画</b> ⇒ 判定/传播逻辑的<b>真缺陷</b></td>
 *       <td><b>要修</b>（且易修）</td></tr>
 *   <tr><td><b>STARVED</b></td>
 *       <td>其余：坡面出口邻格的平滑流量 &lt; 门槛</td>
 *       <td>出口真的<b>没水</b> ⇒ 要么蒙特卡洛欠采样，要么上游水真的散了</td>
 *       <td>先量幅度再判</td></tr>
 * </table>
 *
 * <p><b>关键设计：SUFFICIENT 与 STARVED 必须分开。</b>
 * 前者是 <b>量纲/口径 bug</b>（有水不画），后者可能是<b>物理</b>（没水）。
 * 若只统计 前者占比，就能直接回答 有多少是 真缺陷。</p>
 *
 * <h2>★ 与 STARVED 有关的判定量：出口流量离门槛有多远</h2>
 * <p>STARVED 里若 出口流量 ≈ 门槛（如 0.9 vs 1.0），则是<b>采样噪声</b>
 * （加样本数可解）；若远低于门槛（如 0.01），则是<b>上游水真的没了</b>
 * （加样本数无用，是别的问题）。故打印 出口平滑流量/门槛 的分位分布，
 * 而不是只报一个计数 —— <b>这正是上一轮 三个被证伪的假说里
 * 「加样本数能消除虚线」被证伪所用的同一把尺子。</b></p>
 *
 * <h2>输出</h2>
 * <p>{@code build/hydro-reassessment/term-<ts>/}：{@code metrics.txt}
 * + {@code classified.png}（<b>按类别着色</b>，可肉眼定位缺陷是否成片）。
 * 底图用 无光照等高线（hillshade 会把谷画成脊，本项目已因此误判两次）。</p>
 *
 * <p><b>NOT VERIFIED</b>：方块铺设、实机渲染未覆盖。
 */
public final class ChannelTerminationProbe {

    private ChannelTerminationProbe() { }

    /** 四类终止。 */
    private enum Kind { WATER, BORDER, SUFFICIENT, STARVED }

    public static void main(String[] args) throws Exception {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : ViewSpec.SEED;
        int cx = args.length > 1 ? Integer.parseInt(args[1]) : ViewSpec.CENTER_X;
        int cz = args.length > 2 ? Integer.parseInt(args[2]) : ViewSpec.CENTER_Z;
        int radius = args.length > 3 ? Integer.parseInt(args[3]) : ViewSpec.RADIUS;
        int samples = args.length > 4 ? Integer.parseInt(args[4]) : 600_000;

        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        var climate = new LakeWaterBalance.Climate(0.004, 0.002, 0.3);

        DirectionRule.setMode("baseline");
        long t0 = System.nanoTime();
        var r = HydrologyPipeline.run(gen, tp,
                new HydrologyPipeline.Request(cx, cz, radius, ViewSpec.STEP, climate, 0,
                        HydrologyPipeline.RiverModel.CONTINUOUS, samples));
        int n = r.nx(), cells = n * n;

        // ★ 门槛必须取自生产口径，不能自己写死一个数（否则两法不可比）。
        double threshold = ContinuousRiverNetwork.Params
                .forRunoff(r.lakeResult().runoffPerCell()).channelThreshold();

        // 3×3 平滑流量（与 ContinuousRiverNetwork 内部 smooth 完全同口径：海格不参与）
        double[] smooth = new double[cells];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                int k = i * n + j;
                if (r.sea()[k]) { smooth[k] = Double.NaN; continue; }
                double sum = 0; int cnt = 0;
                for (int di = -1; di <= 1; di++) {
                    for (int dj = -1; dj <= 1; dj++) {
                        int a = i + di, b = j + dj;
                        if (a < 0 || b < 0 || a >= n || b >= n) continue;
                        int kk = a * n + b;
                        if (r.sea()[kk]) continue;
                        sum += r.discharge()[kk]; cnt++;
                    }
                }
                smooth[k] = cnt > 0 ? sum / cnt : r.discharge()[k];
            }
        }

        // 4-连通分量
        int[] comp = new int[cells];
        java.util.Arrays.fill(comp, -1);
        int comps = 0;
        int[] stack = new int[cells];
        int[] size = new int[cells];
        for (int k0 = 0; k0 < cells; k0++) {
            if (comp[k0] >= 0 || !r.channel()[k0]) continue;
            int sp = 0;
            stack[sp++] = k0;
            comp[k0] = comps;
            int sz = 0;
            while (sp > 0) {
                int c = stack[--sp];
                sz++;
                int ci = c / n, cj = c % n;
                if (ci > 0 && comp[c - n] < 0 && r.channel()[c - n]) { comp[c - n] = comps; stack[sp++] = c - n; }
                if (ci < n - 1 && comp[c + n] < 0 && r.channel()[c + n]) { comp[c + n] = comps; stack[sp++] = c + n; }
                if (cj > 0 && comp[c - 1] < 0 && r.channel()[c - 1]) { comp[c - 1] = comps; stack[sp++] = c - 1; }
                if (cj < n - 1 && comp[c + 1] < 0 && r.channel()[c + 1]) { comp[c + 1] = comps; stack[sp++] = c + 1; }
            }
            size[comps] = sz;
            comps++;
        }

        // ★ 逐分量归类
        Kind[] kind = new Kind[comps];
        double[] exitRatio = new double[comps];   // 出口平滑流量 / 门槛
        int[] exitCells = new int[comps];
        java.util.Arrays.fill(exitRatio, Double.NaN);
        for (int k = 0; k < cells; k++) {
            int c = comp[k];
            if (c < 0) continue;
            int i = k / n, j = k % n;
            for (int[] d : new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) {
                int a = i + d[0], b = j + d[1];
                // 越界 = 窗口截断
                if (a < 0 || b < 0 || a >= n || b >= n) {
                    if (kind[c] == null) kind[c] = Kind.BORDER;
                    continue;
                }
                int nk = a * n + b;
                if (r.channel()[nk]) continue;
                if (r.sea()[nk] || r.lake()[nk]) {
                    if (kind[c] == null) kind[c] = Kind.WATER;
                    continue;
                }
                // 坡面出口：记录流量最高者（那是最接近 继续成河 的地方）
                double sv = smooth[nk];
                if (Double.isNaN(sv)) continue;
                exitCells[c]++;
                double ratio = sv / threshold;
                if (Double.isNaN(exitRatio[c]) || ratio > exitRatio[c]) exitRatio[c] = ratio;
            }
        }
        for (int c = 0; c < comps; c++) {
            if (kind[c] != null) continue;
            kind[c] = (exitCells[c] == 0) ? Kind.WATER : Kind.STARVED;
        }
        // SUFFICIENT 优先于 STARVED（同分量既有水出口也有高流量坡出口时，
        // 只要有一处 门槛说够 就算缺陷，这样才不漏报）
        for (int c = 0; c < comps; c++) {
            if (kind[c] == Kind.STARVED && !Double.isNaN(exitRatio[c]) && exitRatio[c] >= 1.0) {
                kind[c] = Kind.SUFFICIENT;
            }
        }

        int[] nComp = new int[4];
        int[] nCells = new int[4];
        // ⚠ 上一版把 nComp[idx]++ 和 nCells[idx] += size[c] 写在【逐格】循环里
        //   ⇒ 前者把【格数】当成分量数（6556 个分量 vs 实际 169），
        //   后者把【整个分量的尺寸】按格重复累加（35.6%+63.3%=98.9% 的占比失真）。
        //   ⇒ 正确做法：按【分量】循环，每个分量只计一次。
        for (int c = 0; c < comps; c++) {
            int idx = kind[c].ordinal();
            nComp[idx]++;
            nCells[idx] += size[c];
        }
        int totalCells = 0;
        for (int c = 0; c < comps; c++) totalCells += size[c];

        StringBuilder log = new StringBuilder();
        // ★ 自洽性自检：分类求和必须与总量一致。本探针已栽过一次
        //   （把格数当分量数、分量尺寸按格重复累加），故把不变量写进代码，
        //   一旦再犯立刻炸，而不是输出一个"看起来像数据"的错表。
        int sumComp = 0, sumCells = 0;
        for (int i = 0; i < 4; i++) { sumComp += nComp[i]; sumCells += nCells[i]; }
        if (sumComp != comps || sumCells != totalCells) {
            throw new IllegalStateException("分类自洽性失败: Σ分量=" + sumComp + " vs comps=" + comps
                    + "; Σ格=" + sumCells + " vs total=" + totalCells
                    + "  (勿在逐格循环里累加分量级指标)");
        }
        log.append("=== 河道终止原因分类 ===\n");
        log.append(String.format("seed=%d centre=(%d,%d) radius=%d step=%d samples=%d  (%.0fs)%n",
                seed, cx, cz, radius, ViewSpec.STEP, samples, (System.nanoTime() - t0) / 1e9));
        log.append(String.format("channelThreshold=%.4f (取自 Params.forRunoff，非写死)%n", threshold));
        log.append(String.format("河道格=%d 连通分量=%d%n%n", totalCells, comps));
        log.append(String.format("%-12s %10s %8s %12s %8s%n",
                "类别", "分量数", "占比", "河道格数", "格占比"));
        String[] names = {"WATER 终止于水体", "BORDER 窗口截断", "SUFFICIENT 有水未画", "STARVED 出口无水"};
        for (int i = 0; i < 4; i++) {
            log.append(String.format("%-12s %10d %7.1f%% %12d %7.1f%%%n", names[i],
                    nComp[i], 100.0 * nComp[i] / Math.max(1, comps),
                    nCells[i], 100.0 * nCells[i] / Math.max(1, totalCells)));
        }
        int defective = nComp[Kind.SUFFICIENT.ordinal()];
        log.append(String.format("%n★ 真缺陷(SUFFICIENT) = %d 个分量 = %.2f%% 的分量、%.2f%% 的河道格%n",
                defective, 100.0 * defective / Math.max(1, comps),
                100.0 * nCells[Kind.SUFFICIENT.ordinal()] / Math.max(1, totalCells)));
        if (defective == 0) {
            log.append("  => 河道没有 一处 出现 水够却不画 的情况。\n");
            log.append("  ⇒ 分量多主要来自 终止于湖/海 与 窗口截断（物理正确），不是缺陷。\n");
        }

        // STARVED 的出口流量离门槛有多远 —— 区分 采样噪声 vs 上游真的没水
        double[] sr = new double[nComp[Kind.STARVED.ordinal()] > 0 ? comps : 0];
        int sn = 0;
        for (int c = 0; c < comps; c++) {
            if (kind[c] == Kind.STARVED && !Double.isNaN(exitRatio[c])) sr[sn++] = exitRatio[c];
        }
        java.util.Arrays.sort(sr, 0, sn);
        if (sn > 0) {
            log.append(String.format("%n[STARVED 出口流量/门槛] n=%d  P10=%.3f P50=%.3f P90=%.3f max=%.3f%n",
                    sn, q(sr, sn, 0.10), q(sr, sn, 0.50), q(sr, sn, 0.90), q(sr, sn, 1.0)));
            int near = 0;
            for (int t = 0; t < sn; t++) if (sr[t] >= 0.5) near++;
            log.append(String.format("  ≥0.5×门槛(加样本数可能有效)=%d(%.1f%%)  <0.5×(加样本数无效)=%d(%.1f%%)%n",
                    near, 100.0 * near / sn, sn - near, 100.0 * (sn - near) / sn));
        }

        reportUpstream(r, n, smooth, comp, size, kind, comps, threshold, log);

        Path dir = Path.of("build/hydro-reassessment/term-" + System.currentTimeMillis());
        Files.createDirectories(dir);
        ImageIO.write(render(r, n, comp, kind, size), "png",
                dir.resolve("classified.png").toFile());
        Files.writeString(dir.resolve("metrics.txt"), log);
        System.out.println(log);
        System.out.println("IMAGE=" + dir.resolve("classified.png").toAbsolutePath());
    }

    /**
     * ★ 追加诊断：STARVED 分量的<b>上游端</b>是否有源。
     *
     * <p><b>为什么问这个</b>（实测看图得出）：classified.png 显示高原上的
     * STARVED 河道是<b>中途开始、中途结束</b>的短段 ——
     * 既无上游来源、也未抵达水体。若成立，则成因不是"下游没水"，
     * 而是 <b>这段河自己凭空出现</b>（逐格门槛在均匀坡面上随机成河）。
     * 这与"高程直纹"是同一现象的两面：均匀坡面 ⇒ 无汇流 ⇒ 各自独立成河。</p>
     *
     * <p>判据：分量<b>最高</b>的格（上游端）若其 3×3 平滑流量 &lt; 门槛，
     * 说明连它自己都不够河 ⇒ 该河没有源，是"凭空出现"。</p>
     */
    private static void reportUpstream(HydrologyPipeline.Result r, int n, double[] smooth,
                                       int[] comp, int[] size, Kind[] kind,
                                       int comps, double threshold, StringBuilder log) {
        int[] hiIdx = new int[comps];
        double[] hiVal = new double[comps];
        java.util.Arrays.fill(hiVal, Double.NEGATIVE_INFINITY);
        for (int k = 0; k < comp.length; k++) {
            int c = comp[k];
            if (c < 0) continue;
            double v = smooth[k];
            if (!Double.isNaN(v) && v > hiVal[c]) { hiVal[c] = v; hiIdx[c] = k; }
        }
        int starved = 0, noSource = 0;
        double[] srcRatio = new double[comps];
        int sn = 0;
        for (int c = 0; c < comps; c++) {
            if (kind[c] != Kind.STARVED) continue;
            starved++;
            double ratio = (hiVal[c] == Double.NEGATIVE_INFINITY) ? Double.NaN : hiVal[c] / threshold;
            if (Double.isNaN(ratio) || ratio < 1.0) noSource++;
            if (!Double.isNaN(ratio)) srcRatio[sn++] = ratio;
        }
        java.util.Arrays.sort(srcRatio, 0, sn);
        log.append(String.format("%n[STARVED 的上游端] 分量=%d  其中 源头自身流量 < 门槛(无源/凭空出现)=%d (%.1f%%)%n",
                starved, noSource, 100.0 * noSource / Math.max(1, starved)));
        log.append(String.format("  源头流量/门槛 分位: P10=%.3f P50=%.3f P90=%.3f%n",
                q(srcRatio, sn, 0.10), q(srcRatio, sn, 0.50), q(srcRatio, sn, 0.90)));
        if (noSource > 0 && noSource == starved) {
            log.append("  => 【全部】STARVED 河段自己都不够河：它们在坡面上【凭空出现】。\n");
            log.append("     成因不是 下游断流，而是 均匀坡面上逐格门槛各自独立触发。\n");
        } else if (noSource > 0) {
            log.append("  => 部分 STARVED 河段 无源（凭空出现），部分有源但下游失水。\n");
        }
    }

    private static double q(double[] a, int n, double qq) {
        if (n <= 0) return Double.NaN;
        int i = (int) Math.round(qq * (n - 1));
        return a[Math.max(0, Math.min(n - 1, i))];
    }

    /**
     * 按类别着色：只给 缺陷类 着高饱和色，其余压暗。
     * <p>目的：<b>一眼看出缺陷是否成片</b> —— 若 SUFFICIENT 零星散布，
     * 多半是采样噪声；若连成大片，则是真的系统性缺陷。</p>
     */
    private static BufferedImage render(HydrologyPipeline.Result r, int n,
                                        int[] comp, Kind[] kind, int[] size) {
        BufferedImage img = new BufferedImage(n, n, BufferedImage.TYPE_INT_RGB);
        double hMin = Double.MAX_VALUE, hMax = -Double.MAX_VALUE;
        for (double v : r.bed()) { hMin = Math.min(hMin, v); hMax = Math.max(hMax, v); }
        double band = Math.max(4.0, (hMax - hMin) / 24.0);
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                int k = i * n + j;
                int c = comp[k];
                if (r.sea()[k]) { img.setRGB(i, j, ViewSpec.SEA_COLOR); continue; }
                if (r.lake()[k]) { img.setRGB(i, j, ViewSpec.LAKE_COLOR); continue; }
                if (c >= 0) {
                    Kind kd = kind[c];
                    // WATER/BORDER 压暗成青灰（健康），SUFFICIENT 亮红，STARVED 橙
                    img.setRGB(i, j, switch (kd) {
                        case SUFFICIENT -> 0xFF2020;
                        case STARVED    -> 0xFF9020;
                        case WATER      -> 0x1E7A72;
                        default         -> 0x3C5A66;
                    });
                } else {
                    img.setRGB(i, j, ViewSpec.contour(r.bed(), n, i, j, hMin, band));
                }
            }
        }
        return img;
    }
}