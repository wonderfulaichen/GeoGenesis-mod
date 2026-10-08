package com.geogenesis.worldgen.hydrology.model;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.Map;

/**
 * 河网：把湖泊溢流与坡面径流沿排水路径汇集成河，流量决定河宽。
 *
 * <h2>物理依据（不用任何"画曲线"）</h2>
 * <p>稳态下每格的出流量由连续性给出，无需时间积分：</p>
 * <pre>  Q(格) = 本格径流 + Σ 上游来水（含湖泊溢流）</pre>
 * <p>沿 {@link EquilibriumWater} 已给出的排水父指针 {@code parent} 逐级累加即可
 * —— 因为平衡洪泛保证该图是<b>DAG 且方向明确</b>（每格唯一流向更靠近排水终端的一格），
 * 按水位降序处理即为拓扑序。</p>
 *
 * <h2>河宽</h2>
 * <p>用 Leopold–Maddock 型水力几何幂律 {@code W = W0·(Q/Q0)^b}，与项目既有
 * 河宽标定同一形式；{@code Q0} 取"成河门槛流量"。低于门槛者不算河（是坡面漫流）。</p>
 *
 * <h2>边界</h2>
 * <p>① 只处理窗口内排水树，窗口外上游来水未知 ⇒ 汇水截断处流量偏小，标未决；
 * ② 不含河槽下切/侵蚀；③ 河宽是<b>水面宽度</b>估计，未做横断面求解。</p>
 */
public final class RiverNetwork {

    /**
     * @param channelThreshold 成河门槛流量（block³/时间）：低于此值不画河（坡面漫流）
     * @param widthRef         参考流量（通常=门槛），对应河头宽度
     * @param headWidth        河头半宽（block）
     * @param widthExponent    宽—流量幂律指数（Leopold–Maddock 常用 0.4~0.5）
     * @param maxWidth         半宽上限（block），防止极端流量把河画到荒谬尺寸
     */
    public record Params(double channelThreshold, double widthRef, double headWidth,
                         double widthExponent, double maxWidth) {
        public Params {
            if (!(channelThreshold > 0) || !(widthRef > 0) || !(headWidth > 0)
                    || !(widthExponent > 0) || !(maxWidth >= headWidth)) {
                throw new IllegalArgumentException("invalid river parameters");
            }
        }
        public static Params defaults() {
            return new Params(1.0, 1.0, 1.5, 0.45, 12.0);
        }

        /**
         * 按【面积比例】生成参数 —— ★ 2026-10-08 诊断入口（「河道 4 倍密集」归因）。
         *
         * <p><b>为什么需要它</b>：{@link #defaults()} 的 {@code channelThreshold=1.0}
         * 是<b>绝对量</b>，而 {@code discharge} 的单位是【格数累积】
         * （每格初值 {@code runoffPerCell} ⇒ 累积 ≈ 上游格数 × runoffPerCell）。
         * 实测生产（177² 网格）河格占 <b>8.41%</b>、且 4 邻域 <b>3+ 度占 86.4%</b>
         * ⇒ 河道交织成网（正典 r=700/step=1 的流量掩码仅 1.90%）
         * ⇒ 疑似阈值过低：大量本应"坡面漫流"的浅沟被判成河道。</p>
         *
         * <p>对照：legacy 河网（{@code RiverLineNetwork}）用<b>面积比例</b>标定
         * {@code thr = max(4, round(n²×0.003))}（177² ⇒ 94 格）。本方法把两者统一到
         * 同一口径，便于 A/B 对比。</p>
         *
         * @param n 网格边长（格）
         * @param frac 成河门槛 = 集水面积占窗口比例（legacy 链实测标定 0.003）
         */
        public static Params byAreaFraction(int n, double frac) {
            double thr = Math.max(4.0, n * (double) n * frac);
            return new Params(thr, thr, 1.5, 0.45, 12.0);
        }
    }

    /** @param sinkX/sinkZ 最近排水终端坐标（用于缓冲带诊断）。 */
    public record Result(double[] discharge, boolean[] channel, double[] width,
                         int channelCells, double maxDischarge, int truncatedSources,
                         int suppressedByBorder) { }

    public static Result build(int nx, int nz, double[] level, int[] parent,
                               double runoffPerCell, Map<Integer, Double> overflowAt,
                               boolean[] resolved, Params params) {
        return build(nx, nz, level, parent, runoffPerCell, overflowAt, resolved, params, 0);
    }

    /**
     * @param borderBufferCells 距窗口边界多少格内【不输出河】。
     *
     * <p><b>为什么需要它</b>：流量是沿排水树累加的量，窗口边界会<b>截断外部上游来水</b>
     * ⇒ 边界带的流量系统性偏小 ⇒ 成河判定在门槛附近随机翻转
     * （实测重叠区 2.2% 的河格不一致，且全在边界带）。
     * 参考实现（FreeTerraForged / PL-RGA）都用"边界带不放河"回避这一点
     * （PL-RGA：{@code border_safe_mask} + "No Rivers & Lakes Near Plate Border"）。</p>
     *
     * <p><b>代价（必须明说）</b>：缓冲带内<b>永远没有河</b>。所以生产上必须保证
     * 相邻分片的有效区能覆盖彼此，否则会留下带状空缺。</p>
     */
    public static Result build(int nx, int nz, double[] level, int[] parent,
                               double runoffPerCell, Map<Integer, Double> overflowAt,
                               boolean[] resolved, Params params, int borderBufferCells) {
        return build(nx, nz, level, parent, runoffPerCell, overflowAt, resolved, params,
                borderBufferCells, null);
    }

    /**
     * 带【盆地身份】的构建 —— 湖即汇（lake-as-sink，方案 §4.2，2026-10 骨架化）。
     *
     * <h3>语义</h3>
     * <p>传入 {@code basinId}（非 null）后，Kahn 累积中<b>组内 → 组外</b>的边不传递水量：
     * 湖盆截留进入的水，只以 {@code overflowAt} 注入的 {@code outflow = inflow − evap}
     * 在【指定出口】下泄。每盆地水量守恒：
     * <pre>inflow（本地径流 + 跨边界净流入）= outflow（出口注入）+ evap（solve 扣除）</pre>
     * 组间直连边（G′→G）同样被拦截 ⇒ 水不越权穿盆，G′ 的水统一从 G′ 出口释放。</p>
     *
     * <h3>为什么旧口径会发散（实测）</h3>
     * <p>不拦截时，穿过流已把上游水带到下游，{@code overflowAt} 又注入一遍
     * ⇒ 同一份水双份计入下游入流；配合 solve 的 {@code Σ discharge over 组}
     * （每格累积量重复计），6 轮耦合 ΣIn 发散到 1.3e14（residual 恒 0.996）。</p>
     *
     * <p>{@code basinId == null} ⇒ 不拦截（旧行为，逐位一致 —— 直接调用本类的
     * 探针/对照全部走此路径，不受影响）。</p>
     *
     * <p><b>v1 已知边界</b>：出口回退路径（rimKey&lt;0 窗口截断时 outlet 可能落在组内）
     * 的注入会被本拦截挡住 ⇒ 该稀有情形下下游少收这份水（窗口截断场景，可接受）。</p>
     */
    public static Result build(int nx, int nz, double[] level, int[] parent,
                               double runoffPerCell, Map<Integer, Double> overflowAt,
                               boolean[] resolved, Params params, int borderBufferCells,
                               int[] basinId) {
        return build(nx, nz, level, parent, runoffPerCell, overflowAt, resolved, params,
                borderBufferCells, basinId, null);
    }

    /**
     * 带额外源项的构建（★ 跨区水量传递，2026-10-08）。
     *
     * <p>{@code extraSource[k]}（可 null）= 窗口边界入流：由
     * {@code ModelHydrologyProvider.computeMarginInflow} 对窗外 margin 带做
     * 最陡下降追踪得到 —— 窗外是纯函数地形，不必求解邻区 ⇒ 无依赖顺序问题、
     * 无循环依赖、确定性。水在窗内沿 parent 图继续汇流，湖经 capture 账本
     * 自动收到这份水（边界入流记在组外格，流入组内即成边界净入流）。</p>
     *
     * <p>⚠ margin 的局部洼地与 margin 湖溢流 v1 未建模（洼地水丢弃）。</p>
     */
    public static Result build(int nx, int nz, double[] level, int[] parent,
                               double runoffPerCell, Map<Integer, Double> overflowAt,
                               boolean[] resolved, Params params, int borderBufferCells,
                               int[] basinId, double[] extraSource) {
        int cells = nx * nz;
        double[] discharge = new double[cells];
        // 源项：每格坡面径流 + 该格的湖泊溢流 + 窗外边界入流。
        for (int k = 0; k < cells; k++) {
            discharge[k] = runoffPerCell;
            Double extra = overflowAt.get(k);
            if (extra != null) discharge[k] += extra;
            if (extraSource != null && extraSource[k] > 0) discharge[k] += extraSource[k];
        }
        // 拓扑序必须由 parent 图本身决定，不能用 level/index 排序。
        // 等高的合法 DAG 没有可靠的数值次序；按索引遍历会在汇流前
        // 消费节点，静默丢掉上游水量。叶子优先的 Kahn 排序正好符合
        // “每个节点的所有子节点先处理”的累积方向。
        int[] childCount = new int[cells];
        for (int k = 0; k < cells; k++) {
            int p = parent[k];
            if (p >= 0 && p < cells && p != k) childCount[p]++;
        }
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        for (int k = 0; k < cells; k++) if (childCount[k] == 0) queue.add(k);
        int processed = 0;
        while (!queue.isEmpty()) {
            int k = queue.removeFirst();
            processed++;
            int p = parent[k];
            if (p >= 0 && p < cells && p != k) {
                // ★ 湖即汇：组内→组外的边不传递（水由出口注入的 outflow 代表）。
                //   注意只拦截【累积】，不拦 childCount 记账（否则拓扑序判环会误报）。
                boolean lakeSink = basinId != null && basinId[k] >= 0
                        && (p >= basinId.length || basinId[p] != basinId[k]);
                if (!lakeSink) discharge[p] += discharge[k];
                if (--childCount[p] == 0) queue.addLast(p);
            }
        }
        if (processed != cells) {
            throw new IllegalArgumentException("parent graph contains a cycle");
        }
        boolean[] channel = new boolean[cells];
        double[] width = new double[cells];
        int channelCells = 0, truncated = 0, suppressed = 0;
        double maxQ = 0;
        for (int k = 0; k < cells; k++) {
            if (!resolved[k]) { truncated++; continue; }
            maxQ = Math.max(maxQ, discharge[k]);
            if (discharge[k] < params.channelThreshold()) continue;
            // 边界缓冲：距窗口边界 borderBufferCells 内的格不输出河。
            //   那里流量累积被截断（缺外部上游），判定不可靠。
            int i = k / nz, j = k % nz;
            if (borderBufferCells > 0 && (i < borderBufferCells || j < borderBufferCells
                    || i >= nx - borderBufferCells || j >= nz - borderBufferCells)) {
                suppressed++;
                continue;
            }
            channel[k] = true; channelCells++;
            double ratio = discharge[k] / params.widthRef();
            width[k] = Math.min(params.maxWidth(),
                    params.headWidth() * Math.pow(Math.max(1e-9, ratio), params.widthExponent()));
        }
        return new Result(discharge, channel, width, channelCells, maxQ, truncated, suppressed);
    }

    private RiverNetwork() { }
}
