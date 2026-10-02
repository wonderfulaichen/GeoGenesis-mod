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
        int cells = nx * nz;
        double[] discharge = new double[cells];
        // 源项：每格坡面径流 + 该格的湖泊溢流。
        for (int k = 0; k < cells; k++) {
            discharge[k] = runoffPerCell;
            Double extra = overflowAt.get(k);
            if (extra != null) discharge[k] += extra;
        }
        // ★ 拓扑序 = 按【水面高程降序】。
        //   平衡洪泛保证 parent[k] 的水位不高于 k 的水位（水流向下游），
        //   故降序遍历时，k 的全部上游都已累加完毕，一次遍历即正确。
        //   （此前我用"反复松弛算深度"，既慢又引入无谓假设，已弃用。）
        Integer[] order = new Integer[cells];
        for (int k = 0; k < cells; k++) order[k] = k;
        final double[] levelFinal = level;
        Arrays.sort(order, (a, b) -> {
            int c = Double.compare(levelFinal[b], levelFinal[a]);
            return c != 0 ? c : Integer.compare(a, b);
        });
        for (int idx = 0; idx < cells; idx++) {
            int k = order[idx];
            int p = parent[k];
            if (p >= 0) discharge[p] += discharge[k];
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
