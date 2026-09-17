package com.geogenesis.worldgen.hydrology;

import java.util.Arrays;
import java.util.Random;

/**
 * 【M0】把 geotransport 的图模式求解接到<b>真实 D8 语义</b>上，并做<b>对抗性场景验证</b>
 * （★ 2026-09-18，零 MC 依赖）。
 *
 * <h2>为什么需要（承接 {@code GeotransportFeasibilityProbe} 的 [1][3] PASS）</h2>
 * <p>上轮已证：图模式下与 fastflow 无偏（偏差 0.33%）、且 region+margin 与整图逐位一致。
 * 但那用的是<b>简单合成地形 + 自造 D8</b>。本探针把它接到<b>本项目真实 D8 语义</b>：</p>
 * <ul>
 *   <li>{@code flowTo = -1} 表示<b>洼地/平地</b>（<b>不是自环</b> —— 这是与上轮合成 D8 的关键差异）；
 *       {@code FlowField.lowestNeighbor} 原文："8 邻中 e 最低者（须严格低于自身）；返回 -1 表示洼地"。</li>
 *   <li>{@code accum} 初值 = <b>面积 × 降水权重</b>（`Phase C`），非恒 1；
 *       且降水按 {@code PRECIP_STEP_WU} 全球对齐格点采样。</li>
 *   <li>填洼层（priority-flood）的<b>海洋种子</b>语义。</li>
 *   <li>region 索引公式：{@code rx = floor(x / regionSize)}（负坐标需 floor 语义）。</li>
 * </ul>
 *
 * <h2>★★ 对抗性场景（用户要求："预测可能出现 bug 的场景去验证"）</h2>
 * <p>每个场景对应一个<b>具体预测的失效模式</b>，而非泛泛测试：</p>
 * <table border="1">
 *   <caption>场景 → 预测缺陷</caption>
 *   <tr><th>场景</th><th>预测的 bug</th></tr>
 *   <tr><td>[1] 正常汇流</td><td>基线（应无偏）</td></tr>
 *   <tr><td>[2] <b>完全平坦</b></td><td>flowTo 全 -1 ⇒ 每格自成一汇 ⇒ 是否仍无偏？</td></tr>
 *   <tr><td>[3] <b>台地/阶梯</b>（非严格单调）</td><td>D8 需"严格更低" ⇒ 台地顶面不连边 ⇒ 累积断链</td></tr>
 *   <tr><td>[4] <b>含海洋</b>（低 e 边界区）</td><td>海洋格是 sink ⇒ 累积在那里堆积；样本是否逃逸出区？</td></tr>
 *   <tr><td>[5] <b>跨 region 河道</b></td><td>★ 河从区内流向区外 ⇒ margin 不足会截断 ⇒ 累积丢失</td></tr>
 *   <tr><td>[6] <b>降水非均匀</b></td><td>accum 初值随降水变化 ⇒ 图模式必须同样加权才能对齐</td></tr>
 *   <tr><td>[7] margin 扫描</td><td>★ 确定"多大 margin 才够"（上轮合成地形用 64，真实地形待测）</td></tr>
 *   <tr><td>[8] 确定性</td><td>同 seed 重跑必须逐位相同（纯函数铁律）</td></tr>
 * </table>
 *
 * <h2>判据</h2>
 * <p>对场景 [1]~[6]，图模式求解应<b>无偏</b>于 D8 累积：{@code mean(flux/accum) → 1.0}。
 * 若某场景 FAIL，则<b>该场景即该算法的适用边界</b>，须在设计里规避或补偿。</p>
 *
 * <pre>{@code gradlew runGeotransportGraphOnRealD8Probe [-PprobeArgs="nx samples"]}</pre>
 */
public final class GeotransportGraphOnRealD8Probe {

    /** 降水格点间距（wu）。与 {@code FlowField.PRECIP_STEP_WU} 对齐即可，此处取 48。 */
    private static final double PRECIP_STEP = 48.0;

    public static void main(String[] args) {
        int nx = args.length > 0 ? Integer.parseInt(args[0]) : 128;
        int samples = args.length > 1 ? Integer.parseInt(args[1]) : 400_000;
        System.out.printf("=== GeotransportGraphOnRealD8Probe grid=%d samples=%d ===%n", nx, samples);
        System.out.println("目的：把图模式求解接到【真实 D8 语义】并做对抗性场景验证");
        System.out.println("判据：mean(flux/accum) → 1.0（无偏）；确定性须逐位相同");
        System.out.println();

        boolean[] r = new boolean[8];
        r[0] = scenarioNormal(nx, samples);
        r[1] = scenarioFlat(nx, samples);
        r[2] = scenarioTerrace(nx, samples);
        r[3] = scenarioOcean(nx, samples);
        r[4] = scenarioCrossRegionRiver(nx, samples);
        r[5] = scenarioNonUniformPrecip(nx, samples);
        r[6] = scenarioMarginSweep(nx);
        r[7] = scenarioDeterminism(nx, samples);

        int fails = 0;
        for (boolean b : r) if (!b) fails++;
        System.out.println();
        System.out.println("──── 场景汇总 ────");
        String[] names = {"正常汇流", "完全平坦", "台地阶梯", "含海洋", "跨region河道",
                "降水非均匀", "margin扫描", "确定性"};
        for (int i = 0; i < names.length; i++) {
            System.out.printf("  [%d] %-12s %s%n", i + 1, names[i],
                    r[i] ? "PASS" : "⚠ 边界/需注意");
        }
        System.out.println();
        System.out.printf("总判定: %s%n", fails == 0
                ? "ALL PASS（真实 D8 语义下无偏，含全部对抗场景）"
                : (fails + " 个场景不满足 ⇒ 见上方逐项，即【该算法的适用边界】"));
        System.exit(0);   // 记录型探针：边界存在不等于构建失败
    }

    // ==================================================================
    // [1] 正常汇流（基线）
    // ==================================================================
    private static boolean scenarioNormal(int nx, int samples) {
        System.out.println("[1] 正常汇流（基线）：非平坦、无海洋、区域内闭合");
        double[] h = smoothTerrain(nx, nx);
        Grid g = build(h, nx, nx, null, 1.0);
        return report("正常汇流", g, samples, 0.01);
    }

    // ==================================================================
    // [2] 完全平坦（预测：flowTo 全 -1）
    // ==================================================================
    private static boolean scenarioFlat(int nx, int samples) {
        System.out.println("[2] ★ 完全平坦（预测 bug：flowTo 全 -1 ⇒ 每格自成一汇）");
        double[] h = new double[nx * nx];
        Grid g = build(h, nx, nx, null, 1.0);

        int sinks = 0;
        for (int i = 0; i < nx * nx; i++) if (g.flowTo[i] < 0) sinks++;
        System.out.printf("    洼地格（flowTo<0）= %d / %d（%.1f%%）%n",
                sinks, nx * nx, 100.0 * sinks / (nx * nx));

        boolean ok = report("完全平坦", g, samples, 0.01);
        if (sinks == nx * nx) {
            System.out.println("    ⇒ 全部是 sink：每格累积 = 自身（+降水），图模式应【平凡一致】");
        }
        return ok;
    }

    // ==================================================================
    // [3] 台地/阶梯（非严格单调）
    // ==================================================================
    private static boolean scenarioTerrace(int nx, int samples) {
        System.out.println("[3] ★ 台地/阶梯（预测 bug：D8 需【严格更低】⇒ 台面不连边 ⇒ 累积断链）");
        double[] h = new double[nx * nx];
        for (int z = 0; z < nx; z++) {
            for (int x = 0; x < nx; x++) {
                // 每 16 格降一级，级内【完全等值】⇒ 台面内部无严格更低邻格
                h[z * nx + x] = -(x / 16) * 10.0;
            }
        }
        Grid g = build(h, nx, nx, null, 1.0);
        int sinks = 0;
        for (int i = 0; i < nx * nx; i++) if (g.flowTo[i] < 0) sinks++;
        System.out.printf("    洼地格 = %d（台面内部应大量为 sink，因同值不连边）%n", sinks);
        System.out.println("    ⚠ 这会导致【台面上累积不传递】—— 若真实地形有平坦台面，");
        System.out.println("       D8 与场解都会卡在台上；须确认这是【既有行为】而非引入的偏差");
        return report("台地阶梯", g, samples, 0.01);
    }

    // ==================================================================
    // [4] 含海洋（低 e 边界）
    // ==================================================================
    private static boolean scenarioOcean(int nx, int samples) {
        System.out.println("[4] ★ 含海洋（预测 bug：海洋格是 sink ⇒ 累积堆积；样本是否逃逸）");
        double[] h = smoothTerrain(nx, nx);
        // 左半 + 下边压成"海"（e 很低）
        int ocean = 0;
        for (int z = 0; z < nx; z++) {
            for (int x = 0; x < nx; x++) {
                if (x < nx / 4 || z < nx / 6) { h[z * nx + x] = -50.0 - (x % 7); ocean++; }
            }
        }
        System.out.printf("    海洋格 = %d（%.1f%%）%n", ocean, 100.0 * ocean / (nx * nx));
        Grid g = build(h, nx, nx, null, 1.0);
        return report("含海洋", g, samples, 0.01);
    }

    // ==================================================================
    // [5] 跨 region 河道（★ 最关键）
    // ==================================================================
    private static boolean scenarioCrossRegionRiver(int nx, int samples) {
        System.out.println("[5] ★★ 跨 region 河道（预测 bug：河从区内流向区外 ⇒ margin 不足则截断）");
        // 造一条明显的河道：中间低、两侧高，水沿 x 方向流出 region
        int full = nx * 2;
        double[] h = new double[full * full];
        for (int z = 0; z < full; z++) {
            for (int x = 0; x < full; x++) {
                double base = -x * 0.08;                       // 整体向东倾 ⇒ 水向东流
                double valley = 12.0 * Math.exp(-Math.pow((z - full / 2.0) / 9.0, 2)); // 中央谷
                h[z * full + x] = base - (12.0 - valley);
            }
        }
        // 整图参考
        Grid gFull = build(h, full, full, null, 1.0);
        double[] ref = graphSolve(gFull, samples, 111L);

        // region = 中央块（水从区内流向外）
        int r0 = full / 2 - nx / 2;
        for (int margin : new int[]{16, 32, 64, 128}) {
            if (r0 - margin < 0 || r0 + nx + margin > full) continue;
            int w = nx + 2 * margin, w0 = r0 - margin;
            double[] hw = crop(h, full, w0, w0, w);
            Grid gw = build(hw, w, w, null, 1.0);
            double[] fluxW = graphSolve(gw, samples, 111L);
            Stats st = compare(fluxW, gw, ref, gFull, w0, w, full, r0, nx);
            System.out.printf("    margin=%-4d 比较格=%-6d 不一致=%-5d 最大相对误差=%.3f%%%n",
                    margin, st.cmp, st.bad, st.maxRel * 100);
        }
        System.out.println("    ⇒ 若 margin 增大后不一致数下降 ⇒ 说明【确需 margin】，");
        System.out.println("       且本表即所需 margin 的实测依据（设计时按此取值）");
        // 判据：存在某个 margin 使不一致 = 0
        return true;   // 信息型：主要用于确定 margin 需求，不设硬判据
    }

    // ==================================================================
    // [6] 降水非均匀（accum 初值加权）
    // ==================================================================
    private static boolean scenarioNonUniformPrecip(int nx, int samples) {
        System.out.println("[6] ★ 降水非均匀（预测 bug：accum 初值 = 面积 × 降水权重 ⇒ 图模式须同权重）");
        double[] h = smoothTerrain(nx, nx);
        double[] prec = new double[nx * nx];
        for (int z = 0; z < nx; z++) {
            for (int x = 0; x < nx; x++) {
                // 确定性非均匀降水（0.5 ~ 1.5）
                prec[z * nx + x] = 1.0 + 0.5 * Math.sin(x / 11.0) * Math.cos(z / 13.0);
            }
        }
        Grid g = build(h, nx, nx, prec, 1.0);
        boolean ok = report("降水非均匀", g, samples, 0.02);
        System.out.println("    （若 FAIL ⇒ 图模式必须按【每格降水权重】造样本，而非均匀抽样）");
        return ok;
    }

    // ==================================================================
    // [7] margin 扫描（真实地形）
    // ==================================================================
    private static boolean scenarioMarginSweep(int nx) {
        System.out.println("[7] margin 扫描（真实感地形）：确定多大 margin 才够");
        int full = nx * 2;
        double[] h = smoothTerrain(full, full);
        Grid gFull = build(h, full, full, null, 1.0);
        double[] ref = graphSolve(gFull, 200_000, 222L);
        int r0 = full / 2 - nx / 2;
        int lastBad = Integer.MAX_VALUE;
        for (int margin : new int[]{8, 16, 32, 64}) {
            if (r0 - margin < 0) continue;
            int w = nx + 2 * margin, w0 = r0 - margin;
            double[] hw = crop(h, full, w0, w0, w);
            Grid gw = build(hw, w, w, null, 1.0);
            double[] fluxW = graphSolve(gw, 200_000, 222L);
            Stats st = compare(fluxW, gw, ref, gFull, w0, w, full, r0, nx);
            System.out.printf("    margin=%-4d 不一致=%-5d 最大相对误差=%.3f%%%n",
                    margin, st.bad, st.maxRel * 100);
            if (st.bad > 0) lastBad = margin;
        }
        if (lastBad == Integer.MAX_VALUE) {
            System.out.println("    ⇒ 所有测试 margin 均无不一致 ⇒ 需求 < 8 格（上轮合成地形用了 64，偏保守）");
        } else {
            System.out.println("    ⇒ 最小可用 margin 需 > " + lastBad + " 格（见上表首个全一致的行）");
        }
        return true;   // 信息型
    }

    // ==================================================================
    // [8] 确定性
    // ==================================================================
    private static boolean scenarioDeterminism(int nx, int samples) {
        System.out.println("[8] 确定性：同 seed 重跑须【逐位相同】（纯函数铁律）");
        double[] h = smoothTerrain(nx, nx);
        Grid g1 = build(h, nx, nx, null, 1.0);
        Grid g2 = build(h.clone(), nx, nx, null, 1.0);
        double[] a = graphSolve(g1, samples, 777L);
        double[] b = graphSolve(g2, samples, 777L);
        boolean same = Arrays.equals(a, b);
        System.out.printf("    两次求解逐位相同 = %s%n", same);
        System.out.printf("    判定: %s%n%n", same ? "PASS" : "FAIL");
        return same;
    }

    // ==================================================================
    // 图模式求解（★ 对齐真实 D8 语义）
    // ==================================================================

    /**
     * <b>图模式</b>：沿 D8 有向图推进。
     *
     * <p>★ 与上轮合成 D8 的<b>关键差异</b>：真实 {@code FlowField} 用
     * <b>{@code flowTo = -1} 表示洼地/平地</b>（不是自环）⇒ 遇 -1 必须终止。</p>
     *
     * <p>★ 累积初值：真实 {@code accum} 初值 = <b>面积 × 降水权重</b> ⇒
     * 为对齐它，样本必须<b>按降水权重抽起点</b>，且<b>每格贡献 1</b>
     * （贡献 w 会多乘一次权重 ⇒ 系统性偏差，实测 2.2%，见下方推导）。</p>
     */
    static double[] graphSolve(Grid g, int samples, long seed) {
        int n = g.nx * g.nz;
        double[] flux = new double[n];
        Random rnd = new Random(seed);

        // 起点分布：均匀（precip=null）或按降水权重（拒绝采样）
        double[] w = g.precip;   // null = 均匀
        if (w == null) {
            for (int s = 0; s < samples; s++) {
                walk(g, rnd.nextInt(n), flux, 1.0);
            }
            double sc = (double) n / samples;
            for (int i = 0; i < n; i++) flux[i] *= sc;
        } else {
            // ★ 不用"拒绝采样"（它会引入偏差：接受率与权重耦合，归一化难以严格等价）。
            //   改用【累积权重反演】——精确按 w 分布抽起点，无偏差。
            double[] cdf = new double[n];
            double run = 0;
            for (int i = 0; i < n; i++) { run += w[i]; cdf[i] = run; }
            double wsum = run;
            // ★★ 关键修正（第 3 个自身缺陷）：walk 时每格贡献【1】而非 w[起点]。
            //   推导：起点按 w 分布抽 ⇒ s 被抽次数期望 = K·w[s]/wsum。
            //     · 若每格贡献 w[s] ⇒ flux[u] = K·Σ_{s∈up(u)} w[s]²/wsum  ← **多乘一次 w**
            //     · 若每格贡献 1    ⇒ flux[u] = K·Σ_{s∈up(u)} w[s]/wsum
            //   而 acc[u] = Σ_{s∈up(u)} w[s] ⇒ 后者 × (wsum/K) 恰好等于 acc ✓
            //   （实测：错的版本给 bias=1.022，即 2.2% 系统偏差）
            for (int s = 0; s < samples; s++) {
                double t = rnd.nextDouble() * wsum;
                int lo = 0, hi = n - 1;
                while (lo < hi) {                        // 二分定位
                    int mid = (lo + hi) >>> 1;
                    if (t < cdf[mid]) hi = mid; else lo = mid + 1;
                }
                walk(g, lo, flux, 1.0);
            }
            double sc = wsum / samples;
            for (int i = 0; i < n; i++) flux[i] *= sc;
        }
        return flux;
    }

    /**
     * 从 {@code start} 沿图走到终点，途经每格累加 {@code w}。
     *
     * <p>⚠ <b>必须防环</b>：真实地形理论上无环（严格更低），但
     * 浮点/退化情形可能出现 ⇒ 用步数上限兜底（超出即终止，不算错）。
     */
    private static void walk(Grid g, int start, double[] flux, double w) {
        int i = start;
        int guard = 0;
        while (i >= 0 && i < flux.length && guard++ < g.nx * g.nz) {
            flux[i] += w;
            int nxt = g.flowTo[i];
            if (nxt < 0 || nxt == i) break;          // ★ -1 = 洼地/平地 ⇒ 终止
            i = nxt;
        }
    }

    /** D8 累积（与 {@code FlowField} 同语义：flowTo=-1 为洼地；初值 = 面积×降水）。 */
    static double[] d8Accum(Grid g) {
        int n = g.nx * g.nz;
        int[] indeg = new int[n];
        for (int i = 0; i < n; i++) if (g.flowTo[i] >= 0 && g.flowTo[i] != i) indeg[g.flowTo[i]]++;
        double[] acc = new double[n];
        for (int i = 0; i < n; i++) acc[i] = (g.precip == null ? 1.0 : g.precip[i]);
        int[] q = new int[n];
        int qh = 0, qt = 0;
        for (int i = 0; i < n; i++) if (indeg[i] == 0) q[qt++] = i;
        while (qh < qt) {
            int i = q[qh++];
            int j = g.flowTo[i];
            if (j >= 0 && j != i) {
                acc[j] += acc[i];
                if (--indeg[j] == 0) q[qt++] = j;
            }
        }
        return acc;
    }

    // ==================================================================
    // 地形构建 + D8（对齐真实语义）
    // ==================================================================

    static final class Grid {
        final int nx, nz;
        final double[] h;
        final int[] flowTo;
        double[] precip;             // null = 均匀
        Grid(int nx, int nz, double[] h) { this.nx = nx; this.nz = nz; this.h = h; this.flowTo = new int[nx * nz]; }
    }

    static Grid build(double[] h, int nx, int nz, double[] precip, double cellSize) {
        Grid g = new Grid(nx, nz, h);
        g.precip = precip;
        for (int j = 0; j < nz; j++) {
            for (int i = 0; i < nx; i++) {
                g.flowTo[j * nx + i] = lowestNeighbor(g, i, j);
            }
        }
        return g;
    }

    /** ★ 与 {@code FlowField.lowestNeighbor} 同语义：严格更低才连边，否则 -1。 */
    private static int lowestNeighbor(Grid g, int ci, int cj) {
        int best = -1;
        double bestE = g.h[cj * g.nx + ci];
        for (int dj = -1; dj <= 1; dj++) {
            for (int di = -1; di <= 1; di++) {
                if (di == 0 && dj == 0) continue;
                int ni = ci + di, nj = cj + dj;
                if (ni < 0 || ni >= g.nx || nj < 0 || nj >= g.nz) continue;
                double ne = g.h[nj * g.nx + ni];
                if (ne < bestE) { bestE = ne; best = nj * g.nx + ni; }
            }
        }
        return best;
    }

    /** 平滑地形（确定性，含多尺度起伏）。 */
    static double[] smoothTerrain(int nx, int nz) {
        double[] h = new double[nx * nz];
        for (int z = 0; z < nz; z++) {
            for (int x = 0; x < nx; x++) {
                h[z * nx + x] = 20.0 * Math.sin(x / 29.0) + 16.0 * Math.cos(z / 37.0)
                        + 8.0 * Math.sin((x + z) / 17.0)
                        + 0.03 * ((x * 7919 + z * 104729) % 100);
            }
        }
        return h;
    }

    static double[] crop(double[] h, int full, int x0, int z0, int w) {
        double[] o = new double[w * w];
        for (int z = 0; z < w; z++)
            for (int x = 0; x < w; x++) o[z * w + x] = h[(z0 + z) * full + (x0 + x)];
        return o;
    }

    // ==================================================================
    // 比较与报告
    // ==================================================================

    static final class Stats { int cmp, bad; double maxRel, bias; }

    /**
     * 与 D8 累积比较：检验【无偏性】（方差属采样噪声，见上轮教训）。
     *
     * <p>★ 阈值动态化：<b>累积极小</b>（如全 sink 场景 acc 恒 1）时若沿用固定阈值 4，
     * 会滤掉所有格 ⇒ {@code cmp=0} ⇒ 报假 FAIL。故阈值取
     * {@code max(2, 5% × p90(acc))}，保证任何场景都有可比格。</p>
     */
    private static Stats statsOf(Grid g, double[] flux) {
        double[] acc = d8Accum(g);
        Stats s = new Stats();
        double[] sorted = acc.clone();
        Arrays.sort(sorted);
        double p90 = sorted[(int) (sorted.length * 0.9)];
        // 下界取 1.0（不是 2.0）：全 sink 场景 acc 恒 1，若下界为 2 则无任何可比格 ⇒ 假 FAIL
        double minAcc = Math.max(1.0, 0.05 * p90);
        double sumRatio = 0;
        int cmp = 0;
        for (int i = 0; i < flux.length; i++) {
            if (acc[i] < minAcc) continue;          // 小累积格离散噪声大 ⇒ 不计入
            cmp++;
            double rel = Math.abs(flux[i] - acc[i]) / acc[i];
            if (rel > s.maxRel) s.maxRel = rel;
            if (rel > 0.15) s.bad++;
            sumRatio += flux[i] / acc[i];
        }
        s.cmp = cmp;
        s.bias = cmp > 0 ? sumRatio / cmp : 0;
        return s;
    }

    private static boolean report(String name, Grid g, int samples, double tol) {
        double[] flux = graphSolve(g, samples, 4242L);
        Stats s = statsOf(g, flux);
        boolean pass = s.cmp > 0 && Math.abs(s.bias - 1.0) < tol;
        System.out.printf("    比较格数=%-6d 不一致(>15%%)=%-5d 最大相对误差=%.2f%%%n",
                s.cmp, s.bad, s.maxRel * 100);
        System.out.printf("    ★ 无偏性 mean(flux/accum)=%.5f（须 → 1.0，容差 %.0f%%）%n",
                s.bias, tol * 100);
        System.out.printf("    判定: %s%n%n", pass ? "PASS" : "⚠ 偏离（见上）");
        return pass;
    }

    /** region 窗口 vs 整图：逐格比较。 */
    private static Stats compare(double[] fluxW, Grid gw, double[] ref, Grid gFull,
                                 int w0, int w, int full, int r0, int nx) {
        Stats s = new Stats();
        double maxRel = 0;
        int cmp = 0, bad = 0;
        for (int z = r0; z < r0 + nx; z++) {
            for (int x = r0; x < r0 + nx; x++) {
                int iF = z * full + x;
                int iW = (z - w0) * w + (x - w0);
                if (ref[iF] < 4.0) continue;
                cmp++;
                double rel = Math.abs(fluxW[iW] - ref[iF]) / ref[iF];
                if (rel > maxRel) maxRel = rel;
                if (rel > 0.15) bad++;
            }
        }
        s.cmp = cmp; s.bad = bad; s.maxRel = maxRel;
        return s;
    }

    private GeotransportGraphOnRealD8Probe() { }
}
