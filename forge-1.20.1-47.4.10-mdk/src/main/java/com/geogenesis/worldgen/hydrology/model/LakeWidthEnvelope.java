package com.geogenesis.worldgen.hydrology.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/**
 * <h1>湖接口宽度包围（caps）：只产出逐节点半宽上限</h1>
 *
 * <p>{@link LakeInterfaceScale} 量接口处湖的横向湿宽；本类把该尺度按弧长距离展开成整条折线的上限：
 * {@code cap(node) = scale + SLOPE × dist(node, 接口)}，封顶 {@value #MAX_HALF}。
 * 主线程做 {@code Math.min(width, cap)}。本类<b>不改 width</b>。</p>
 *
 * <h2>距离在图上算，接点弧长由几何投影求</h2>
 * <p>状态 = (trace, 进入该 trace 的弧长)，边 = 相邻 trace 的接点。接点弧长必须用
 * {@link RiverWidthProfile#projectOnto} 把<b>上游 shape 尾点</b>投到下游 shape 上：
 * {@code River.downstreamNode} 是 {@code River.cells} 的下标，不是 shape 下标。</p>
 * <p>每个接口从自身位置做有界 Dijkstra（累计距离单调 ⇒ 首次出队即最优）：
 * 同 trace 沿 {@code |arc[i] − enter|} 展开；下游从本 trace 尾加 {@code (arcEnd − enter) + gap} 进
 * {@code joinArc}；上游从 {@code enter} 退回祖先接点再加间隙落到祖先尾部。
 * 故串联共用同一距离、支流只在近接口处收紧，不 double count。</p>
 *
 * <p>锥饱和（{@code scale + SLOPE×dist ≥ MAX_HALF}）即剪枝，不做全流域扫描；环路跳过。
 * 接点边来自 {@link RiverWidthProfile#chainEdges}，与宽度剖面共享冲突语义。</p>
 *
 * <p>所有限制取 {@code min}，最后做一次双向斜率闭合（只做 {@code min}，不放大上限），
 * 避免 {@code inheritedLimits} 的逐点常数在 coarse 格边界留台阶。
 * 身份缺失 / 方向退化 / 撞窗 ⇒ 只记 {@link Interface} 证据，不施加限制。</p>
 */
final class LakeWidthEnvelope {

    /** 初始/无约束上限（block），与 {@code RiverCenterlines.Params.defaults().maxHalf()} 同值。 */
    static final double MAX_HALF = LakeInterfaceScale.MAX_HALF;

    /** 允许的最小半宽（block）：1 块全宽。 */
    static final double MIN_HALF = LakeInterfaceScale.MIN_HALF;

    /** 锥的放宽速率（半宽 / block）。值 = 1/32，即每 32 block 放宽 1。 */
    static final double HALF_GROWTH_PER_BLOCK = 1.0 / 32.0;

    static final String KIND_SOURCE = "LAKE_SOURCE";
    static final String KIND_MOUTH = "LAKE_MOUTH";

    private LakeWidthEnvelope() { }

    /**
     * 接口证据行（不参与计算）。
     *
     * @param kind       {@link #KIND_SOURCE} 或 {@link #KIND_MOUTH}
     * @param poly       折线下标（= trace 下标，与 {@code limits} 对齐）
     * @param cell       接口格（coarse 格索引）
     * @param basin      实际使用的盆地身份；{@code -1} = 未知
     * @param status     {@link LakeInterfaceScale} 的 status
     * @param maxHalf    该接口处的上限；不可用时 {@link Double#NaN}
     * @param transverse 量到的横向湿宽；不可用时 {@link Double#NaN}
     * @param samples    测量采样次数
     */
    record Interface(String kind, int poly, int cell, int basin, String status,
                     double maxHalf, double transverse, int samples) {
        boolean applied() {
            return Double.isFinite(maxHalf);
        }
    }

    /** @param limits 逐折线逐节点半宽上限（每行长度 == 对应 {@code Shape.size()}） */
    record Result(List<double[]> limits, List<Interface> interfaces) {
        Result {
            limits = List.copyOf(limits);
            interfaces = List.copyOf(interfaces);
        }

        int applied() {
            int n = 0;
            for (var i : interfaces) if (i.applied()) n++;
            return n;
        }
    }

    /**
     * 生产入口。
     *
     * @param polys           骨架折线（下标与 {@code traces} 对齐）
     * @param pipe            水文管线结果；可为 null（⇒ 只剩 {@code inheritedLimits}）
     * @param ox,oz           窗口原点（block）
     * @param n               窗口格数（节点 → coarse 格换算）
     * @param step            每格块数
     * @param traces          骨架 trace 表；可为 null
     * @param rivers          局部河流单元表；可为 null（⇒ 无接点边，只按单 trace 展开）
     * @param inheritedLimits coarse 格 → 上限锚点（跨区）；可为 null/空
     */
    static Result build(List<DeterministicSkeleton.Shaped> polys, HydrologyPipeline.Result pipe,
                        int ox, int oz, int n, int step,
                        List<DeterministicSkeleton.Trace> traces,
                        List<DeterministicSkeleton.River> rivers,
                        Map<Integer, Double> inheritedLimits) {
        List<double[]> limits = initialLimits(polys);
        if (limits.isEmpty()) return new Result(limits, List.of());
        anchorInherited(polys, limits, inheritedLimits, ox, oz, n, step);
        LakeWaterBalance.Result lakes = pipe == null ? null : pipe.lakeResult();
        if (lakes == null || lakes.lake() == null || traces == null) {
            closeAll(limits, RiverWidthProfile.arcLengths(polys));
            return new Result(limits, List.of());
        }
        Graph g = new Graph(polys, traces, rivers, limits);
        List<Interface> found = new ArrayList<>();
        int lim = Math.min(polys.size(), traces.size());
        for (int p = 0; p < lim; p++) {
            var sh = polys.get(p);
            var t = traces.get(p);
            if (sh == null || t == null || sh.size() < 2) continue;
            if (t.fromOutlet) source(g, p, sh, lakes, pipe, step, found);
            if (t.end == DeterministicSkeleton.End.MOUTH_LAKE) mouth(g, p, sh, lakes, pipe, step, found);
        }
        closeAll(limits, g.arc);
        return new Result(limits, found);
    }

    /** 出湖：源点是非湖的溢出口格，身份只能靠 {@code sourceBasinOf}（允许 ≠ {@code basinId[cell]}）。 */
    private static void source(Graph g, int p, DeterministicSkeleton.Shaped sh,
                               LakeWaterBalance.Result lakes, HydrologyPipeline.Result pipe,
                               int step, List<Interface> out) {
        int cell = g.traces.get(p).sourceCell();
        if (cell < 0) return;
        relax(g, out, KIND_SOURCE, p, 0, cell,
                DeterministicSkeleton.sourceBasinOf(lakes, cell),
                sh.x[0] - sh.x[1], sh.z[0] - sh.z[1], lakes, pipe, step);
    }

    /** 入湖：骨架把湖格本身追加为末节点，故 {@code basinOf(mouth)} 有值。 */
    private static void mouth(Graph g, int p, DeterministicSkeleton.Shaped sh,
                              LakeWaterBalance.Result lakes, HydrologyPipeline.Result pipe,
                              int step, List<Interface> out) {
        int cell = g.traces.get(p).mouthCell();
        int m = sh.size() - 1;
        if (cell < 0) return;
        relax(g, out, KIND_MOUTH, p, m, cell, LakeWaterBalance.basinOf(lakes, cell),
                sh.x[m] - sh.x[m - 1], sh.z[m] - sh.z[m - 1], lakes, pipe, step);
    }

    /** 测一次接口；可用才作为 Dijkstra 源点施加锥。 */
    private static void relax(Graph g, List<Interface> out, String kind, int p, int node, int cell,
                              int basin, double inX, double inZ,
                              LakeWaterBalance.Result lakes, HydrologyPipeline.Result pipe, int step) {
        var m = LakeInterfaceScale.measure(pipe.lake(), lakes.basinId(), pipe.nx(), pipe.nz(),
                step, cell, basin, inX, inZ);
        out.add(new Interface(kind, p, cell, basin, m.status(), m.maxHalf(),
                m.transverseBlocks(), m.samples()));
        if (m.usable()) g.spread(p, g.arcOf(p, node), m.maxHalf());
    }

    private static List<double[]> initialLimits(List<DeterministicSkeleton.Shaped> polys) {
        int np = polys == null ? 0 : polys.size();
        List<double[]> out = new ArrayList<>(np);
        for (int p = 0; p < np; p++) {
            var sh = polys.get(p);
            double[] row = new double[sh == null ? 0 : sh.size()];
            Arrays.fill(row, MAX_HALF);
            out.add(row);
        }
        return out;
    }

    /** 逐节点锚：该节点 coarse 格在 {@code inheritedLimits} 里的上限（逐点 min，不做锥）。 */
    private static void anchorInherited(List<DeterministicSkeleton.Shaped> polys, List<double[]> limits,
                                        Map<Integer, Double> inherited, int ox, int oz, int n, int step) {
        if (inherited == null || inherited.isEmpty() || n <= 0 || step <= 0) return;
        for (int p = 0; p < limits.size(); p++) {
            var sh = polys.get(p);
            double[] row = limits.get(p);
            if (sh == null) continue;
            for (int i = 0; i < row.length && i < sh.size(); i++) {
                Double v = inherited.get(cellOf(sh.x[i], sh.z[i], ox, oz, n, step));
                if (v == null || !Double.isFinite(v)) continue;
                row[i] = Math.min(row[i], clampHalf(v));
            }
        }
    }

    /** 世界坐标 → coarse 格索引（越界 -1）。与 {@code pass1Widths} 的 {@code i*n+j} 同口径。 */
    private static int cellOf(double bx, double bz, int ox, int oz, int n, int step) {
        long i = Math.round((bx - ox) / step);
        long j = Math.round((bz - oz) / step);
        if (i < 0 || j < 0 || i >= n || j >= n) return -1;
        return (int) (i * n + j);
    }

    /** 锥：从 {@code cap} 起按弧长线性放宽，封顶 {@link #MAX_HALF}。 */
    static double cone(double cap, double distance) {
        if (!Double.isFinite(cap)) return MAX_HALF;
        return clampHalf(cap + HALF_GROWTH_PER_BLOCK * Math.max(0, distance));
    }

    private static double clampHalf(double v) {
        if (!Double.isFinite(v)) return MAX_HALF;
        return Math.max(MIN_HALF, Math.min(MAX_HALF, v));
    }

    /** {@code row[i] = min(row[i], v)}，值域恒在 {@code [0.5, 6]}。 */
    private static void lower(double[] row, int i, double v) {
        if (i < 0 || i >= row.length || !Double.isFinite(v)) return;
        row[i] = Math.min(row[i], Math.max(MIN_HALF, v));
    }

    private static void closeAll(List<double[]> limits, double[][] arc) {
        for (int p = 0; p < limits.size(); p++) closeLipschitz(limits.get(p), arc[p]);
    }

    /**
     * 双向斜率闭合：cap 对弧长满足"每 block 变化 ≤ {@link #HALF_GROWTH_PER_BLOCK}"。
     *
     * <p>锥本身连续，但 {@code inheritedLimits} 是逐点常数、且 {@code rivers == null} 时没有接点边
     * ⇒ 相邻节点仍可能差很大。闭合只做 {@code min}，不放大任何已有上限。</p>
     */
    static void closeLipschitz(double[] row, double[] arc) {
        if (row == null || row.length == 0) return;
        double[] a = arc != null && arc.length == row.length ? arc : unitArc(row.length);
        for (int i = 1; i < row.length; i++) row[i] = Math.min(row[i], cone(row[i - 1], a[i] - a[i - 1]));
        for (int i = row.length - 2; i >= 0; i--) row[i] = Math.min(row[i], cone(row[i + 1], a[i + 1] - a[i]));
    }

    private static double[] unitArc(int m) {
        double[] a = new double[m];
        for (int i = 0; i < m; i++) a[i] = i;
        return a;
    }

    /** 图中一个状态：(trace, 进入该 trace 的弧长) 与累积距离。 */
    private record State(int poly, double enter, double dist) { }

    /**
     * 骨架图：接点边来自 {@link RiverWidthProfile#chainEdges}（共享冲突语义），
     * 接点弧长由上游 shape 尾点投影到下游 shape 求得。
     */
    private static final class Graph {
        final List<DeterministicSkeleton.Shaped> polys;
        final List<DeterministicSkeleton.Trace> traces;
        final List<double[]> limits;
        final double[][] arc;
        final int[] succ;
        final double[] joinArc;
        final double[] joinGap;
        final List<List<Integer>> pred;
        final boolean[] cyclic;

        Graph(List<DeterministicSkeleton.Shaped> polys, List<DeterministicSkeleton.Trace> traces,
              List<DeterministicSkeleton.River> rivers, List<double[]> limits) {
            this.polys = polys;
            this.traces = traces;
            this.limits = limits;
            this.arc = RiverWidthProfile.arcLengths(polys);
            int np = polys.size();
            this.succ = new int[np];
            this.joinArc = new double[np];
            this.joinGap = new double[np];
            Arrays.fill(succ, -1);
            Arrays.fill(joinArc, -1);
            if (rivers != null) for (var r : rivers) RiverWidthProfile.chainEdges(succ, r, np, rivers);
            this.cyclic = RiverWidthProfile.cycleNodes(succ, np);
            this.pred = new ArrayList<>(np);
            for (int i = 0; i < np; i++) pred.add(new ArrayList<>());
            for (int u = 0; u < np; u++) {
                int d = succ[u];
                if (d < 0 || d >= np || cyclic[u] || cyclic[d]) { succ[u] = -1; continue; }
                double[] j = join(u, d);
                if (j == null) { succ[u] = -1; continue; }
                joinArc[u] = j[0];
                joinGap[u] = j[1];
                pred.get(d).add(u);
            }
        }

        /** 上游尾点投到下游 shape：{弧长, 间隙}；折线退化或间隙过大 ⇒ null。 */
        private double[] join(int u, int d) {
            var su = polys.get(u);
            var sd = polys.get(d);
            if (su == null || sd == null || su.size() < 2 || sd.size() < 2) return null;
            double[] hit = RiverWidthProfile.projectOnto(sd, arc[d],
                    su.x[su.size() - 1], su.z[su.size() - 1]);
            if (hit == null) return null;
            double tailX = su.x[su.size() - 1], tailZ = su.z[su.size() - 1];
            int i = Math.min(Math.max((int) hit[1], 0), Math.max(0, sd.size() - 2));
            double t = Math.min(Math.max(hit[2], 0), 1);
            double px = sd.x[i] + (sd.x[Math.min(i + 1, sd.size() - 1)] - sd.x[i]) * t;
            double pz = sd.z[i] + (sd.z[Math.min(i + 1, sd.size() - 1)] - sd.z[i]) * t;
            double gap = Math.hypot(tailX - px, tailZ - pz);
            return new double[]{hit[0], gap};
        }

        /** 从接口位置以 {@code scale} 起做有界 Dijkstra。 */
        void spread(int p, double anchorArc, double scale) {
            PriorityQueue<State> queue = new PriorityQueue<>(
                    java.util.Comparator.comparingDouble(State::dist)
                            .thenComparingInt(State::poly)
                            .thenComparingDouble(State::enter));
            List<Set<Double>> seen = new ArrayList<>(polys.size());
            for (int i = 0; i < polys.size(); i++) seen.add(new HashSet<>());
            queue.add(new State(p, anchorArc, 0));
            while (!queue.isEmpty()) {
                State s = queue.remove();
                if (!seen.get(s.poly()).add(s.enter())) continue;
                paint(s.poly(), s.enter(), s.dist(), scale);
                if (scale + HALF_GROWTH_PER_BLOCK * s.dist() >= MAX_HALF) continue;
                pushDown(s, queue);
                pushUp(s, queue);
            }
        }

        /** 下游：本 trace 尾 + 几何间隙 → 后继的 joinArc。 */
        private void pushDown(State s, PriorityQueue<State> queue) {
            int d = succ[s.poly()];
            if (d < 0 || joinArc[s.poly()] < 0) return;
            double add = (arcEnd(s.poly()) - s.enter()) + joinGap[s.poly()];
            queue.add(new State(d, joinArc[s.poly()], s.dist() + Math.max(0, add)));
        }

        /** 上游：从 enter 退回该祖先的接点弧长，再跨几何间隙落到祖先尾部。 */
        private void pushUp(State s, PriorityQueue<State> queue) {
            for (int a : pred.get(s.poly())) {
                if (joinArc[a] < 0) continue;
                double add = Math.abs(s.enter() - joinArc[a]) + joinGap[a];
                queue.add(new State(a, arcEnd(a), s.dist() + add));
            }
        }

        /** 同 trace 内按 {@code |arc[i] − enter| + dist} 展开。 */
        private void paint(int p, double enter, double dist, double scale) {
            double[] row = limits.get(p);
            if (row == null) return;
            for (int i = 0; i < row.length; i++) {
                lower(row, i, cone(scale, dist + Math.abs(arcOf(p, i) - enter)));
            }
        }

        double arcOf(int p, int i) {
            double[] a = arc[p];
            if (a == null || a.length == 0) return 0;
            return a[Math.min(Math.max(i, 0), a.length - 1)];
        }

        double arcEnd(int p) {
            double[] a = arc[p];
            return a == null || a.length == 0 ? 0 : a[a.length - 1];
        }
    }
}
