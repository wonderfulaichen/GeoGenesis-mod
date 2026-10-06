package com.geogenesis.worldgen.hydrology.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * <h1>中心线河段网 + 沿流线定宽横断面</h1>
 *
 * <h2>为什么需要它（取代"逐格流量过门槛"）</h2>
 * <p>旧实现把 {@code smooth[flux] >= threshold} 的<b>每一格</b>都染成河道，
 * 于是"哪里的格过门槛"直接等于"河道长什么样"。三个后果：</p>
 * <ol>
 *   <li>均匀坡面上流线平行不汇聚 ⇒ 一大片格同时过门槛 ⇒ 成块不成条（"抹开"）；</li>
 *   <li>算出的 {@code width[]} 只用来换颜色，<b>从未参与几何</b> ⇒ 河宽与形状脱节；</li>
 *   <li>门槛附近的抖动逐格翻转 ⇒ 散点/虚线（只能用"传播"补丁掩盖）。</li>
 * </ol>
 *
 * <h2>本类的分工（方案 §4.1/§4.3）</h2>
 * <pre>
 *   ① 证据：延续用流量场（坡面漫流保留），但只用于【选出集中流带】；
 *   ② 中心线：对集中流带做【细化(thinning)】得到 1 格宽骨架，按junction切分成【河段】；
 *   ③ 拓扑：每段定向为"上游→下游"（沿更低的床面走），故有向且无环；
 *   ④ 横断面：W = Wref·(Q/Qref)^b（全宽）；深度由 Q 与【谷地横断面】共同约束；
 *   ⑤ 几何：给出【到中心线的归一化距离】与半宽 ⇒ 河道掩码由距离场产生，
 *      而不是由"该格自己过不过门槛"产生。
 * </pre>
 *
 * <h2>与参考项目的关系（方案 §3）</h2>
 * <p>借 FreeTerraForged 的"中心线距离场 + 四段横断面"与 DynamicWaters 的
 * "中心线→有限横向作用域"；<b>不借</b>它们的随机几何河线（FreeTF）——
 * 本类的中心线来自真实汇流证据，是"流出来的"而非"画出来的"。</p>
 *
 * <h2>确定性</h2>
 * <p>全部步骤为确定性遍历（细化的两趟子迭代、段内按索引定序、端点排序用
 * {@code (bed, index)} 字典序）⇒ 同输入逐位相同。</p>
 *
 * <h2>★★ 实测结论（2026-10，必读：本版【尚未】优于旧路径）★★</h2>
 * <p>{@code runCenterlineCrossSectionProbe}（r=400 / 150k，同参对照）：</p>
 * <pre>
 *   旧掩码（flux≥门槛）    河道格 15,762  分量 164  抹开占比 18.19%
 *   新掩码（本类中心线+全宽）河道格 21,738  分量 152  抹开占比 31.47%   ← 更差
 *   ⇒ ①连通性 PASS ③散点 PASS（44→19）④湿宽比 1.47 PASS ⑤确定性 PASS
 *   ⇒ ②抹开 FAIL
 * </pre>
 * <p><b>根因（已定位，不是实现 bug）</b>：宽度来自 {@code flux}，而
 * <b>平坦区 flux 本身就被系统性抬高</b>（正是"抹开"的原始根因）⇒
 * 把"平行线骨架"按各自 inflated 的 Q 膨胀回去 ⇒ 抹开原样重现。
 * ⇒ <b>只做"细化 + 按 Q 定宽"不足以修复抹开</b>。</p>
 * <p><b>缺失的前置步骤 = 走廊聚合（方案 §4.1）</b>：必须在细化【之前】
 * 把同一汇流走廊内的多个平行候选**归并成唯一一条**（受谷地轴约束），
 * 否则骨架本身就是一张平行线网，怎么定宽都还是网。
 * 参考 FreeTerraForged 之所以没有此问题，是因为它的河道<b>不是**从逐格流量场
 * 派生的**，而是"每 region 一条中心线"（几何构造）⇒ 结构上不存在平行网。</p>
 * <p>⇒ <b>本类刻意【未接入】{@link HydrologyPipeline}</b>：当前状态下接线会让
 * 形态变差。它保留为已通过①②③④⑤中四项的**可复用部件**（细化/切段/定向/
 * 横断面查询）与上表的量化基线，供"走廊聚合"落地后直接复用。</p>
 *
 * <p><b>NOT VERIFIED</b>：方块铺设、实机渲染未覆盖。</p>
 */
public final class RiverCenterlines {

    /** 一条河段：中心线折线 + 逐节点水量/宽深。 */
    public static final class Reach {
        /** 节点网格坐标（已按上游→下游排序）。 */
        public final int[] cell;
        /** 逐节点出流量（与 RiverNetwork 同单位）。 */
        public final double[] discharge;
        /** 逐节点水面【全宽】（block）。 */
        public final double[] width;
        /** 逐节点水深（block）。 */
        public final double[] depth;
        /** 所属河道分量编号（同一连通河道 = 同一编号），供拓扑判据使用。 */
        public final int component;

        Reach(int[] cell, double[] discharge, double[] width, double[] depth, int component) {
            this.cell = cell;
            this.discharge = discharge;
            this.width = width;
            this.depth = depth;
            this.component = component;
        }

        public int nodeCount() { return cell.length; }
        public int sourceCell() { return cell[0]; }
        public int mouthCell() { return cell[cell.length - 1]; }
    }

    /** 参数（与 RiverNetwork/ContinuousRiverNetwork 的水力几何律保持一致，便于对照）。 */
    public record Params(double widthRef, double headWidth, double widthExponent, double maxWidth,
                         double depthRef, double headDepth, double maxDepth,
                         double minSlope) {
        public Params {
            if (!(widthRef > 0) || !(headWidth > 0) || !(widthExponent > 0) || maxWidth < headWidth) {
                throw new IllegalArgumentException("invalid width law");
            }
            if (!(depthRef > 0) || !(headDepth > 0) || maxDepth < headDepth || minSlope < 0) {
                throw new IllegalArgumentException("invalid depth law");
            }
        }
        /** 与 {@code ContinuousRiverNetwork.Params.forRunoff} 同一套宽度律。 */
        public static Params defaults() {
            return new Params(1.0, 1.5, 0.45, 12.0, 1.0, 0.6, 8.0, 0.002);
        }
    }

    /** 结果：河段表 + 逐格横断面查询。 */
    public static final class Result {
        public final List<Reach> reaches;
        /** 逐格：到最近中心线的【归一化】距离（0=河心，1=水边，>1=岸上）；无河处为 +inf。 */
        public final double[] normalizedDistance;
        /** 逐格：最近河段的半宽（block）；无河处为 0。 */
        public final double[] halfWidth;
        /** 逐格：最近河段的水深（block）；无河处为 0。 */
        public final double[] depth;
        /** 逐格：由距离场产生的河道掩码（normalizedDistance <= 1 且深度可见）。 */
        public final boolean[] channel;

        Result(List<Reach> reaches, double[] normalizedDistance, double[] halfWidth,
               double[] depth, boolean[] channel) {
            this.reaches = reaches;
            this.normalizedDistance = normalizedDistance;
            this.halfWidth = halfWidth;
            this.depth = depth;
            this.channel = channel;
        }

        public int reachCount() { return reaches.size(); }
        public int channelCells() {
            int c = 0;
            for (boolean b : channel) if (b) c++;
            return c;
        }
    }

    /** 8 邻域偏移（与 {@link EquilibriumWater} 同序，保证确定性）。 */
    private static final int[][] DIRS8 = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1},
            {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };

    private RiverCenterlines() { }

    /**
     * 从"集中流带证据"提取中心线并生成横断面。
     *
     * @param bed      床面高程
     * @param flux     流量场（证据；坡面漫流保留但不会自动成为河道）
     * @param evidence 集中流带掩码（通常 = 现状的成河判定）；本方法只把它当"候选区域"
     * @param sea      海掩码（合法终端）
     * @param nx,nz    网格尺寸
     * @param params   水力几何参数
     */
    public static Result build(double[] bed, double[] flux, boolean[] evidence,
                               boolean[] sea, int nx, int nz, Params params) {
        int cells = nx * nz;
        if (bed.length != cells || flux.length != cells || evidence.length != cells) {
            throw new IllegalArgumentException("array length != nx*nz");
        }
        // ① 细化 ⇒ 1 格宽骨架（保留 8 连通性，不产生新断口）
        boolean[] skeleton = thin(evidence, nx, nz);

        // ② 切分河段：沿骨架走链，遇分叉点/端点断开
        int[] degree = new int[cells];
        for (int k = 0; k < cells; k++) {
            if (!skeleton[k]) continue;
            degree[k] = neighbors(skeleton, nx, nz, k).size();
        }
        // 顶点 = 度数 != 2 的骨架格（端点为 1、分叉为 >=3）
        boolean[] vertex = new boolean[cells];
        for (int k = 0; k < cells; k++) if (skeleton[k] && degree[k] != 2) vertex[k] = true;

        boolean[] visitedEdge = new boolean[cells];   // 以"起点格"标记已用过的段
        List<Reach> reaches = new ArrayList<>();
        // 分量编号：同一连通骨架 = 同一编号（供拓扑判据）
        int[] component = new int[cells];
        Arrays.fill(component, -1);
        int compCount = 0;
        for (int s = 0; s < cells; s++) {
            if (!skeleton[s] || component[s] >= 0) continue;
            ArrayDeque<Integer> q = new ArrayDeque<>();
            q.add(s); component[s] = compCount;
            while (!q.isEmpty()) {
                int c = q.removeFirst();
                for (int nb : neighbors(skeleton, nx, nz, c)) {
                    if (component[nb] < 0) { component[nb] = compCount; q.addLast(nb); }
                }
            }
            compCount++;
        }
        // 先从【端点/分叉】出发切段；再从剩余的纯环（无顶点）出发
        for (int pass = 0; pass < 2; pass++) {
            for (int k = 0; k < cells; k++) {
                if (!skeleton[k]) continue;
                if (pass == 0 && !vertex[k]) continue;
                if (pass == 1 && vertex[k]) continue;
                if (visitedEdge[k]) continue;
                traceBranch(skeleton, vertex, visitedEdge, flux, bed, sea, nx, nz,
                        k, component[k], params, reaches);
            }
        }

        // ③ 横断面距离场（把中心线按半宽展开成几何）
        double[] normDist = new double[cells];
        double[] halfW = new double[cells];
        double[] depth = new double[cells];
        Arrays.fill(normDist, Double.POSITIVE_INFINITY);
        boolean[] channel = new boolean[cells];
        for (Reach reach : reaches) {
            for (int i = 1; i < reach.cell.length; i++) {
                paintSegment(reach, reach.cell[i - 1], reach.cell[i], i - 1, i,
                        nx, nz, normDist, halfW, depth, channel);
            }
            if (reach.cell.length == 1) {
                paintSegment(reach, reach.cell[0], reach.cell[0], 0, 0,
                        nx, nz, normDist, halfW, depth, channel);
            }
        }
        return new Result(reaches, normDist, halfW, depth, channel);
    }

    /** 从 seed 沿骨架走一条段，尽量走直（优先非顶点邻居），并定向为上游→下游。 */
    private static void traceBranch(boolean[] skeleton, boolean[] vertex, boolean[] used,
                                    double[] flux, double[] bed, boolean[] sea,
                                    int nx, int nz, int seed, int comp,
                                    Params params, List<Reach> out) {
        ArrayList<Integer> path = new ArrayList<>();
        int prev = -1;
        int cur = seed;
        while (true) {
            path.add(cur);
            used[cur] = true;
            if (prev >= 0 && vertex[cur]) break;          // 走到另一个顶点 ⇒ 段结束
            List<Integer> nbs = neighbors(skeleton, nx, nz, cur);
            int next = -1;
            for (int nb : nbs) {
                if (nb == prev) continue;
                if (nb == cur) continue;
                if (used[nb] && !vertex[nb]) continue;     // 不回穿已走过的段内格
                if (next < 0) { next = nb; continue; }
                // 分叉处优先走【更低床面】= 下游方向；床面相同取更小索引（确定性）
                double bd = bed[nb], bc = bed[next];
                if (bd < bc - 1e-12 || (Math.abs(bd - bc) <= 1e-12 && nb < next)) next = nb;
            }
            if (next < 0) break;
            prev = cur;
            cur = next;
            if (path.size() > nx * nz) break;              // 防御：异常输入
        }
        // 定向：若尾端床面更高，说明走反了 ⇒ 反转
        int head = path.get(0), tail = path.get(path.size() - 1);
        if (bed[tail] > bed[head] + 1e-12) {
            java.util.Collections.reverse(path);
        }
        int n = path.size();
        int[] cell = new int[n];
        double[] q = new double[n];
        double[] w = new double[n];
        double[] d = new double[n];
        for (int i = 0; i < n; i++) {
            int k = path.get(i);
            cell[i] = k;
            q[i] = Math.max(0, flux[k]);
            w[i] = fullWidth(q[i], params);
            // 深度：由水量与【当地坡度】共同决定（陡则浅、缓则深），并受地形落差限制。
            d[i] = depthFor(q[i], localSlope(bed, nx, nz, k), params);
            if (sea != null && sea[k]) d[i] = Math.max(d[i], 0);
        }
        out.add(new Reach(cell, q, w, d, comp));
    }

    /** 全宽 W = headWidth·(Q/widthRef)^b，上限 maxWidth。 */
    private static double fullWidth(double q, Params p) {
        double ratio = Math.max(1e-9, q / p.widthRef());
        return Math.min(p.maxWidth(), p.headWidth() * Math.pow(ratio, p.widthExponent()));
    }

    /**
     * 水深由水力学几何与地形坡度共同约束（方案 §4.3）。
     *
     * <p>取 Leopold–Maddock 的深度律 {@code D = headDepth·(Q/depthRef)^b}
     * 与"坡度越陡越浅"的横向断面约束：
     * {@code D_slope = D · clamp(sqrt(minSlope/max(slope,minSlope)), 0.5, 1.5)}。
     * 陡坡段水浅而急、缓坡段水深而缓 —— 这是横断面族的简化实现
     * （未做完整 Manning 求解；平湖/跌水需另走 S=0 分支，本版不假装适用）。</p>
     */
    private static double depthFor(double q, double slope, Params p) {
        double ratio = Math.max(1e-9, q / p.depthRef());
        double base = p.headDepth() * Math.pow(ratio, 0.40);
        double s = Math.max(slope, p.minSlope());
        double factor = Math.sqrt(p.minSlope() / s);
        factor = Math.max(0.5, Math.min(1.5, factor));
        return Math.min(p.maxDepth(), base * factor);
    }

    /**
     * 当地坡度（沿最陡下降方向，用 8 邻域最大落差 / 距离）。
     *
     * <p>⚠ 刻意用【落差】而不是中心差分的模长：河道是线状的，
     * 中心差分把"横向岸壁"也算进坡度 ⇒ 陡坡河段被系统性误判。
     * 沿流向的最大落差才对应"水在这一格掉多快"。</p>
     */
    private static double localSlope(double[] bed, int nx, int nz, int k) {
        int x = k / nz, z = k % nz;
        double here = bed[k];
        double best = 0;
        for (int[] d : DIRS8) {
            int a = x + d[0], b = z + d[1];
            if (a < 0 || b < 0 || a >= nx || b >= nz) continue;
            double drop = here - bed[a * nz + b];
            double dist = (d[0] == 0 || d[1] == 0) ? 1.0 : Math.sqrt(2.0);
            best = Math.max(best, drop / dist);
        }
        return best;
    }

    /** 把一个段沿其两端点展开成横断面（逐格取"最近段"的最小归一化距离）。 */
    private static void paintSegment(Reach reach, int k0, int k1, int i0, int i1,
                                     int nx, int nz, double[] normDist, double[] halfW,
                                     double[] depth, boolean[] channel) {
        // 每端向外多涂一轮，避免段间出现 1 格缝（相邻段必然共享顶点）
        double half = Math.max(reach.width[i0], reach.width[i1]) * 0.5;
        boolean sea0 = reach.depth[i0] <= 0 && reach.depth[i1] <= 0;
        double depth0 = 0.5 * (reach.depth[i0] + reach.depth[i1]);
        double qd = 0.5 * (reach.discharge[i0] + reach.discharge[i1]);
        if (!(half > 0)) return;
        // 段是相邻格对（也可能同格）⇒ 涂布半径取整
        int r = (int) Math.ceil(half) + 1;
        int nz2 = nz;
        for (int k : new int[]{k0, k1}) {
            int x = k / nz2, z = k % nz2;
            for (int dx = -r; dx <= r; dx++) {
                for (int dz = -r; dz <= r; dz++) {
                    int a = x + dx, b = z + dz;
                    if (a < 0 || b < 0 || a >= nx || b >= nz2) continue;
                    int kk = a * nz2 + b;
                    // 到段的距离（同格 ⇒ 0；相邻格 ⇒ 用中心距）
                    double dist = Math.hypot(dx, dz);
                    if (k0 == k1) dist = 0;
                    double cn = dist / half;
                    if (cn < normDist[kk]) {
                        normDist[kk] = cn;
                        halfW[kk] = half;
                        depth[kk] = depth0;
                    }
                    if (cn <= 1.0 && !(sea0 && qd <= 0)) channel[kk] = true;
                }
            }
        }
    }

    /** 骨架格的 8 邻域骨架邻居（确定性顺序）。 */
    private static List<Integer> neighbors(boolean[] m, int nx, int nz, int k) {
        int x = k / nz, z = k % nz;
        List<Integer> out = new ArrayList<>(8);
        for (int[] d : DIRS8) {
            int a = x + d[0], b = z + d[1];
            if (a < 0 || b < 0 || a >= nx || b >= nz) continue;
            int nb = a * nz + b;
            if (m[nb]) out.add(nb);
        }
        return out;
    }

    /**
     * 形态学细化（Zhang–Suen，两趟子迭代）⇒ 1 格宽骨架。
     *
     * <p>两张子迭代分别删除"东南边界"与"西北边界"的简单点，
     * 合起来保证：不破坏连通性、不缩短端点、不产生孔洞。
     * 迭代到本轮无删除为止 ⇒ 确定性收敛。</p>
     */
    static boolean[] thin(boolean[] mask, int nx, int nz) {
        boolean[] m = mask.clone();
        boolean changed = true;
        int guard = 0;
        while (changed && guard++ < 4 * (nx + nz)) {
            changed = false;
            for (int sub = 0; sub < 2; sub++) {
                ArrayList<Integer> remove = new ArrayList<>();
                for (int x = 1; x < nx - 1; x++) {
                    for (int z = 1; z < nz - 1; z++) {
                        int k = x * nz + z;
                        if (!m[k]) continue;
                        if (deletable(m, nx, nz, x, z, sub)) remove.add(k);
                    }
                }
                if (!remove.isEmpty()) {
                    changed = true;
                    for (int k : remove) m[k] = false;
                }
            }
        }
        return m;
    }

    /** Zhang–Suen 单点可删除判定（P2/P4/P6/P8 与子迭代条件）。 */
    private static boolean deletable(boolean[] m, int nx, int nz, int x, int z, int sub) {
        int k = x * nz + z;
        // P1..P8 顺时针：N, NE, E, SE, S, SW, W, NW
        boolean p1 = m[k];
        boolean p2 = m[(x) * nz + (z - 1)];
        boolean p3 = m[(x + 1) * nz + (z - 1)];
        boolean p4 = m[(x + 1) * nz + (z)];
        boolean p5 = m[(x + 1) * nz + (z + 1)];
        boolean p6 = m[(x) * nz + (z + 1)];
        boolean p7 = m[(x - 1) * nz + (z + 1)];
        boolean p8 = m[(x - 1) * nz + (z)];
        boolean p9 = m[(x - 1) * nz + (z - 1)];
        if (!p1) return false;
        int bp = 0;
        boolean[] ring = {p2, p3, p4, p5, p6, p7, p8, p9};
        for (int i = 0; i < 8; i++) if (ring[i]) bp++;
        if (bp < 2 || bp > 6) return false;
        int ap = 0;
        for (int i = 0; i < 8; i++) {
            if (!ring[i] && ring[(i + 1) % 8]) ap++;
        }
        if (ap != 1) return false;
        if (sub == 0) {
            if (p2 && p4 && p6) return false;
            if (p4 && p6 && p8) return false;
        } else {
            if (p2 && p4 && p8) return false;
            if (p2 && p6 && p8) return false;
        }
        return true;
    }
}
