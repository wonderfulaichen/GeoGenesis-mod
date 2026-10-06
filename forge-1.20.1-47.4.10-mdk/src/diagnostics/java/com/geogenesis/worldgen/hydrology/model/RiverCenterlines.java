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
 * <h2>★★ 实测结论（2026-10，走廊聚合 = 地形谷轴夹逼）★★</h2>
 * <p>{@code runCenterlineCrossSectionProbe} 同参对照（阈值 {@code bankRise=0.25}）：</p>
 * <pre>
 *   r=400 / 150k：旧 18.09% → 新 8.81%（降 51%）；湿宽比 1.07；拆分数 0 ⇒ ALL PASS
 *   r=700 / 600k：旧 22.47% → 新 12.68%（降 44%）；湿宽比 1.08；拆分数 0
 * </pre>
 * <p>⇒ <b>地形谷轴夹逼是本轮唯一有效的机制</b>：宽度取
 * {@code min(水力几何, 法向岸高测量)} ⇒ 平坦浅沟（岸高仅零点几块）被夹成细条，
 * 大河谷照常展宽。出图见 {@code build/centerline/}：高原与海岸带由"青色块"
 * 变为"条状细线"。</p>
 *
 * <p><b>此前两次失败（已移除，勿重犯）</b>：</p>
 * <ol>
 *   <li><b>只做"细化 + 按 Q 定宽"</b>：18% → 31%（更差）。根因：宽度来自被
 *       系统性抬高的平坦区 Q ⇒ 按各自虚高的 Q 膨胀平行骨架 ⇒ 网还是网。</li>
 *   <li><b>走廊聚合改走"通量非极大抑制"</b>：18% → 62%（更糟）。根因：平坦区
 *       平行线通量接近，纯局部比较只能靠索引破平局 ⇒ 骨架被打碎。
 *       ⇒ <b>走廊聚合必须由地形约束，不能由通量局部比较实现</b>（本轮已证实）。</li>
 * </ol>
 *
 * <p><b>★ 已知边界（重要）</b>：本轮修的是"<b>涂布</b>"（宽度），不是"<b>选线</b>"
 * —— <b>骨架本身的平行性未变</b>。故 r=700 下仍有残余"窄而平行"的沟线
 * （约 12.7% 的 5×5 高密度格；且该口径会把正常宽河/汇合区也算进去，
 * 按方案 §5.4 只能作相对改善指标）。要根治需在<b>选线</b>阶段
 * 由谷轴决定唯一中心线，属下一步工作。</p>
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

    /**
     * 参数（与 RiverNetwork/ContinuousRiverNetwork 的水力几何律保持一致，便于对照）。
     *
     * @param bankRise 法向"岸高"阈值（block）：沿法向走到床面比河心高出此值即认为到岸。
     *                 取值很小（默认 0.5）是<b>刻意</b>的：平坦高原上的分汊是<b>浅沟</b>，
     *                 只有小阈值才能量出"这只是一条浅沟"⇒ 渠道保持细条、不被 Q 撑开。
     * @param maxHalf  半宽上限（block）——法向测量与水力几何共同的上限。
     */
    public record Params(double widthRef, double headWidth, double widthExponent, double maxWidth,
                         double depthRef, double headDepth, double maxDepth,
                         double minSlope, double bankRise, double maxHalf) {
        public Params {
            if (!(widthRef > 0) || !(headWidth > 0) || !(widthExponent > 0) || maxWidth < headWidth) {
                throw new IllegalArgumentException("invalid width law");
            }
            if (!(depthRef > 0) || !(headDepth > 0) || maxDepth < headDepth || minSlope < 0) {
                throw new IllegalArgumentException("invalid depth law");
            }
            if (!(bankRise > 0) || !(maxHalf > 0)) {
                throw new IllegalArgumentException("invalid valley constraints");
            }
        }
        /** 与 {@code ContinuousRiverNetwork.Params.forRunoff} 同一套宽度律。 */
        public static Params defaults() {
            return new Params(1.0, 1.5, 0.45, 12.0, 1.0, 0.6, 8.0, 0.002, 0.5, 12.0);
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
            for (int i = 0; i < reach.cell.length; i++) {
                paintNode(reach, i, nx, nz, normDist, halfW, depth, channel);
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
        for (int i = 0; i < n; i++) cell[i] = path.get(i);
        double[] q = new double[n];
        double[] w = new double[n];
        double[] d = new double[n];
        for (int i = 0; i < n; i++) {
            int k = cell[i];
            q[i] = Math.max(0, flux[k]);
            // ★★ 宽度由【水力几何】与【地形谷轴】共同决定（方案 §4.3）★★
            //
            //   此前只用 W = Wref·(Q/Qref)^b ⇒ 平坦区 Q 虚高时宽度被撑开，
            //   把平行线骨架糊成一片（实测抹开 18%→31%）。
            //   现改为取二者较小者：狭/浅谷 ⇒ 宽度被地形夹住 ⇒ 渠道保持细条。
            double halfQ = 0.5 * fullWidth(q[i], params);
            double halfValley = valleyHalfWidth(bed, nx, nz, k,
                    directionAt(cell, i, nz), params.bankRise(), params.maxHalf());
            double half = Math.min(halfQ, halfValley);
            half = Math.max(half, 0.5);                 // 至少半格：中心线本身必须有水
            double dep = depthFor(q[i], localSlope(bed, nx, nz, k), params);
            // ★ 地形夹逼不是"裁掉宽度丢掉流量"：宽度被钳时相应【加深】，
            //   保持过水断面量级（方案 §4.3 明确要求）。
            if (half < halfQ - 1e-9) {
                dep = Math.min(params.maxDepth(), dep * Math.sqrt(halfQ / half));
            }
            w[i] = 2 * half;
            d[i] = dep;
            if (sea != null && sea[k]) d[i] = Math.max(d[i], 0);
        }
        out.add(new Reach(cell, q, w, d, comp));
    }

    /**
     * 中心线在节点 i 处的【下游单位方向】（网格空间）。
     *
     * <p>末节点无下游 ⇒ 沿用上一段方向；单节点段 ⇒ 退化为 (0,1)（确定性）。</p>
     */
    private static double[] directionAt(int[] cell, int i, int nz) {
        int n = cell.length;
        double dx, dz;
        if (i + 1 < n) {
            dx = cell[i + 1] / nz - cell[i] / nz;
            dz = cell[i + 1] % nz - cell[i] % nz;
        } else if (i > 0) {
            dx = cell[i] / nz - cell[i - 1] / nz;
            dz = cell[i] % nz - cell[i - 1] % nz;
        } else {
            dx = 0;
            dz = 1;
        }
        double len = Math.hypot(dx, dz);
        if (len < 1e-9) return new double[]{0, 1};
        return new double[]{dx / len, dz / len};
    }

    /**
     * <h2>法向"谷宽"测量：走廊聚合的地形判据</h2>
     *
     * <p>沿中心线的【法向】向两侧走，直到床面比河心高出 {@code bankRise}
     * 即认为到岸 ⇒ 返回两侧的较小半宽。</p>
     *
     * <p><b>为什么这能治"抹开"</b>：平坦高原上的平行线是<b>浅沟</b>，
     * 沟底与沟间脊的高差只有零点几块 ⇒ 用小 {@code bankRise}（0.5）
     * 就能量出"这只是窄沟" ⇒ 半宽被夹到 1~2 格 ⇒ 渠道保持细条。
     * 而真正的大河谷（岸高数十块）能容纳大宽度 ⇒ 不影响主河道展宽。</p>
     *
     * <p>⚠ <b>与"通量非极大抑制"的区别（那次实验已证否）</b>：本判据用
     * <b>地形</b>（床面抬升），不比较通量 ⇒ 平坦区通量彼此接近时不会靠索引破平局
     * 而把骨架打碎；它只限制【横向展开】，不动中心线本身。</p>
     *
     * @return 半宽（block），至少 1；两侧都走不到岸时返回 {@code maxHalf}
     */
    private static double valleyHalfWidth(double[] bed, int nx, int nz, int k,
                                          double[] dir, double bankRise, double maxHalf) {
        int x = k / nz, z = k % nz;
        double base = bed[k];
        // 法向 = 方向旋转 90°（在网格坐标里足够近似，测量本身对角度不敏感）
        double nxn = -dir[1], nzn = dir[0];
        double norm = Math.hypot(nxn, nzn);
        if (norm < 1e-9) return maxHalf;
        nxn /= norm;
        nzn /= norm;
        int limit = (int) Math.ceil(maxHalf);
        double half = maxHalf;
        for (int sign = -1; sign <= 1; sign += 2) {
            int reached = limit;
            for (int s = 1; s <= limit; s++) {
                int a = (int) Math.round(x + sign * nxn * s);
                int b = (int) Math.round(z + sign * nzn * s);
                if (a < 0 || b < 0 || a >= nx || b >= nz) { reached = s - 1; break; }
                if (bed[a * nz + b] > base + bankRise) { reached = s - 1; break; }
            }
            half = Math.min(half, Math.max(1, reached));
        }
        return Math.max(1.0, half);
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

    /**
     * 在节点 i 处按【法向横断面】涂布宽度 —— 这才是"沿流线定宽"的几何实现。
     *
     * <p>此前用"以端点为圆心的方形块"涂布（{@code r = ceil(half)+1} 的方框），
     * 那是<b>各向同性</b>的 ⇒ 河流被画成圆头/方块 ⇒ 与"条状"相反。
     * 现改为：以节点为心、沿【法向】向两侧各铺 {@code half} 格
     * （即经典"粗折线"渲染）⇒ 得到垂直于流向的横断面。</p>
     */
    private static void paintNode(Reach reach, int i, int nx, int nz,
                                  double[] normDist, double[] halfW, double[] depth,
                                  boolean[] channel) {
        int k = reach.cell[i];
        double half = reach.width[i] * 0.5;
        if (!(half > 0)) return;
        int x = k / nz, z = k % nz;
        double[] dir = directionAt(reach.cell, i, nz);
        double nxn = -dir[1], nzn = dir[0];
        double norm = Math.hypot(nxn, nzn);
        if (norm < 1e-9) { nxn = 0; nzn = 1; norm = 1; }
        nxn /= norm;
        nzn /= norm;
        int steps = (int) Math.ceil(half);
        for (int s = -steps; s <= steps; s++) {
            int a = (int) Math.round(x + nxn * s);
            int b = (int) Math.round(z + nzn * s);
            if (a < 0 || b < 0 || a >= nx || b >= nz) continue;
            int kk = a * nz + b;
            double cn = Math.abs(s) / half;          // 0=河心 1=水边
            if (cn < normDist[kk]) {
                normDist[kk] = cn;
                halfW[kk] = half;
                depth[kk] = reach.depth[i];
            }
            if (cn <= 1.0) channel[kk] = true;
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
