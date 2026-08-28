package com.geogenesis.worldgen.hydrology.flowaccum;

import com.geogenesis.worldgen.hydrology.riverline.MidpointDisplacement;
import com.geogenesis.worldgen.hydrology.riverline.RiverLineParams;

import java.util.ArrayList;
import java.util.List;

/**
 * 从汇流场提取河线：锚点格汇流面积 ≥ 阈值 → 沿 D8 路径取折线 → 平滑 → 弧长均匀重采样。
 *
 * <p><b>与旧分形线的关系</b>：端点仍是（锚点, 下游锚点），拓扑沿用"8 邻最低锚点连边"；
 * 但中间节点不再由 MidpointFractal 独立生成，而是真实 D8 下坡路径 ——
 * 动线物理正确（沿最大下坡汇流），且端点钉死保证跨 region 首尾相接。</p>
 *
 * <p><b>干谷剔除</b>：锚点格累积面积 &lt; minDischargeArea → 返回 null（不生成河），
 * 取代旧 estimateDischargeArea 的 9×9 锚点计数近似。</p>
 */
public final class RiverTrace {
    private RiverTrace() { }

    /** 河线提取结果：折线节点 + 逐节点汇流面积（wu²，宽度/深度面积驱动用）。 */
    public record TraceLine(MidpointDisplacement.Node[] nodes, double[] nodeAccum) { }

    /**
     * 提取一条河线（锚点 → 下游锚点）。
     *
     * @return 折线（复用 {@link MidpointDisplacement.Node}，RiverLineRegion 无需改型）
     *         + 逐节点汇流面积；干谷/路径过短返回 null
     */
    public static TraceLine traceLine(
            double ax, double az, double bx, double bz,
            RiverLineParams params, MidpointDisplacement.ElevationSampler eSampler) {

        double cell = params.gridCell();
        // 网格覆盖两端锚点 + regionSize/4 margin：D8 路径可越过 region 边界直达下游锚点，
        // 且上游集水不被窗口截断（累积量 = 真实 catchment 量级；过小窗口 → 河网过稀）。
        double margin = params.regionSize() * 0.25;
        FlowField field = new FlowField(
                Math.min(ax, bx) - margin, Math.min(az, bz) - margin,
                Math.max(ax, bx) + margin, Math.max(az, bz) + margin,
                cell, eSampler);

        int start = field.indexOf(ax, az);
        if (field.accumAt(start) < params.minDischargeArea()) return null;   // 干谷

        FlowField.TraceResult tr = field.tracePath(start, field.indexOf(bx, bz),
                params.maxTraceSteps());
        List<double[]> pts = new ArrayList<>(tr.points());
        if (pts.size() < 2) return null;
        // 未达下游锚点（洼地/步数耗尽）：直线补接 —— 拓扑要求线段首尾相接；
        // 随后 smooth 会圆滑该拐角。物理段（D8 路径）已占绝大部分长度。
        if (!tr.reachedTarget()) pts.add(new double[]{bx, bz});
        MidpointDisplacement.Node[] nodes = resample(smooth(pts, 2), nodeCount(params));
        return new TraceLine(nodes, nodeAccum(field, nodes));
    }

    /** 逐节点汇流面积：在节点位置回查汇流场（重采样后节点可能偏离格心，取最近格）。 */
    private static double[] nodeAccum(FlowField field, MidpointDisplacement.Node[] nodes) {
        double[] out = new double[nodes.length];
        for (int i = 0; i < nodes.length; i++) {
            out[i] = field.accumAt(field.indexOf(nodes[i].x(), nodes[i].z()));
        }
        return out;
    }

    /** 节点数与旧分形一致（2^levels + 1），width/depth 数组逻辑无需改动。 */
    private static int nodeCount(RiverLineParams params) {
        return (1 << params.fractalLevels()) + 1;
    }

    /** 移动平均平滑（端点固定）：消除 D8 栅格锯齿，保留整体走向。 */
    private static List<double[]> smooth(List<double[]> pts, int passes) {
        List<double[]> cur = pts;
        for (int p = 0; p < passes; p++) {
            int n = cur.size();
            List<double[]> next = new ArrayList<>(n);
            for (int i = 0; i < n; i++) {
                if (i == 0 || i == n - 1) { next.add(cur.get(i)); continue; }
                double[] a = cur.get(i - 1), b = cur.get(i), c = cur.get(i + 1);
                next.add(new double[]{(a[0] + 2 * b[0] + c[0]) * 0.25,
                                      (a[1] + 2 * b[1] + c[1]) * 0.25});
            }
            cur = next;
        }
        return cur;
    }

    /** 弧长均匀重采样到 nodeCount 个节点（端点保留）。 */
    private static MidpointDisplacement.Node[] resample(List<double[]> pts, int count) {
        int segCount = pts.size() - 1;
        double[] cum = new double[pts.size()];
        for (int i = 1; i < pts.size(); i++) {
            double dx = pts.get(i)[0] - pts.get(i - 1)[0];
            double dz = pts.get(i)[1] - pts.get(i - 1)[1];
            cum[i] = cum[i - 1] + Math.sqrt(dx * dx + dz * dz);
        }
        double total = cum[segCount];
        MidpointDisplacement.Node[] out = new MidpointDisplacement.Node[count];
        int seg = 0;
        for (int k = 0; k < count; k++) {
            double target = total * k / (count - 1);
            while (seg < segCount - 1 && cum[seg + 1] < target) seg++;
            double span = Math.max(1e-9, cum[seg + 1] - cum[seg]);
            double t = Math.min(1.0, (target - cum[seg]) / span);
            double[] a = pts.get(seg), b = pts.get(seg + 1);
            out[k] = new MidpointDisplacement.Node(a[0] + (b[0] - a[0]) * t,
                                                   a[1] + (b[1] - a[1]) * t);
        }
        return out;
    }
}
