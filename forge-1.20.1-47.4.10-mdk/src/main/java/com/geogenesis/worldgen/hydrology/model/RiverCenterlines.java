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
 *
 * <p><b>⚠ 2026-10 复核：下列历史数字【已失真，勿再引用】。</b>
 * 同一代码同一 seed/cx/cz/radius=400/150k 重跑实测：
 * <pre>
 *   bankRise=0.25：旧抹开 21.47% → 新 21.95%（湿宽比 1.51）
 *   bankRise=0.50：旧抹开 21.47% → 新 26.21%（湿宽比 1.51）
 * </pre>
 * 而本 javadoc 原记 8.81% / 1.07 —— <b>与两者都差 2.5× / 1.4×</b>。
 * 差异来源推断：本类的输入是 {@code flux} 与 {@code evidence(=旧 channel)}，
 * 而这两者在随后几轮中<b>都被改动</b>（filler 父指针替换、溢流注入起点修复
 * ⇒ 输入分布已不同）。<b>结论：历史数字属改动前状态，不可作为当前决策依据。</b></p>
 *
 * <p><b>当前可信判据</b>（{@code runCenterlineCrossSectionProbe}，两档 bankRise 均 ALL PASS）：</p>
 * <pre>
 *   ③ 拆分数 = 0（无真碎片化）  ④ 湿宽比 1.51  ⑤ 确定性 PASS
 *   ⑥ 河段内部断口 = 0          ⑦ 孤立块 ≤2 格: 6→2     ⑧ 有向无环
 *   ② 抹开占比：按方案 §5.4 【不作判据】（该口径混同正常宽河汇合区与真抹开）
 * </pre>
 * <p>⇒ 谷轴夹逼仍然有效（分量 119→82），但<b>降幅远小于历史记录</b>；
 * 若要恢复历史数字，须先查清上游输入差异，而不是回调参。</p>
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
 * —— 骨架来自证据掩码的细化。<b>⚠ 河道的平行性【不是缺陷】</b>
 * （用户已两次明确；且早有实测：{@code runValleyConfinementProbe} 显示
 * 23.54% 河道格位于横坡局部低点 vs 陆地基线 6.54% = <b>3.60×</b>
 * ⇒ 高原平行线就是<b>地形冲沟</b>，随谷走是正确行为）。
 * 任何形式的"去平行化"都会破坏"河贴谷"，勿再立项。</p>
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
        /** 被过滤掉的过短碎段数（<2 节点，不绘；供核对，不静默）。 */
        public final int droppedFragments;

        Result(List<Reach> reaches, double[] normalizedDistance, double[] halfWidth,
               double[] depth, boolean[] channel, int droppedFragments) {
            this.reaches = reaches;
            this.normalizedDistance = normalizedDistance;
            this.halfWidth = halfWidth;
            this.depth = depth;
            this.channel = channel;
            this.droppedFragments = droppedFragments;
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
        return build(bed, flux, evidence, sea, nx, nz, params, 1.0);
    }

    /**
     * ★★★ 2026-10-09【外部折线注入 —— 骨架来源切换】★★★
     *
     * <p><b>为什么需要</b>：本类原走「窗口内 D8 汇流累积 → {@code thin} → 骨架」，
     * 正典出图实测有两类伪影（等距平行竖直长线 / region 边界矩形）——
     * 根因是汇流累积**窗口局部**。</p>
     *
     * <p>而 legacy 的 {@code RiverLineNetwork} 已有**跨区连续**的 D8 骨架
     * （SKELETON_MARGIN 外扩 + {@code OutletSeed} 双 pass 续流）。
     * 用户定位「**model 是 legacy 的升级**」⇒ 骨架应**继承** legacy 的精华，
     * 而不是另起一套。本入口即为此：把外部折线直接当作骨架，
     * 下游（切段/距离场/雕刻）全部复用本类既有实现。</p>
     *
     * @param polys 外部折线（世界坐标 **wu**，见 {@link Poly}）
     * @param flux  逐格流量（仅用于宽度律；骨架位置【不依赖它】）
     * @param nx,nz 网格尺寸
     * @param originX,originZ 网格原点（**block**）
     * @param hs    block→wu 换算（wu = block × hs）
     */
    public static Result buildFromPolys(List<Poly> polys, double[] flux, boolean[] bed,
                                        int nx, int nz, Params params, int originX, int originZ,
                                        double hs, double cellSize) {
        int cells = nx * nz;
        List<Reach> reaches = new ArrayList<>();
        int comp = 0;
        for (Poly poly : polys) {
            int n = poly.wx.length;
            if (n < 2) continue;
            int[] cell = new int[n];
            double[] q = new double[n];
            double[] w = new double[n];
            double[] d = new double[n];
            boolean ok = true;
            for (int i = 0; i < n; i++) {
                // wu → block → 格索引
                int bx = (int) Math.round(poly.wx[i] / hs);
                int bz = (int) Math.round(poly.wz[i] / hs);
                int gi = Math.floorDiv(bx - originX, (int) cellSize);
                int gj = Math.floorDiv(bz - originZ, (int) cellSize);
                if (gi < 0 || gj < 0 || gi >= nx || gj >= nz) { ok = false; break; }
                cell[i] = gi * nz + gj;
                q[i] = i < flux.length && cell[i] < flux.length ? Math.max(0, flux[cell[i]]) : 0;
                // ★ 宽度：legacy 的 width 是**半宽**（block）⇒ 本类的 width 是**全宽**
                w[i] = Math.max(2 * MIN_HALF_BLOCKS, 2 * poly.halfWidth[i]);
                d[i] = Math.max(0, poly.depth[i]);
            }
            if (!ok) continue;
            reaches.add(new Reach(cell, q, w, d, comp++));
        }
        // ③ 横断面距离场（与 build 的 ③ 同一实现：线段扫掠圆盘）
        double[] normDist = new double[cells];
        double[] halfW = new double[cells];
        double[] depth = new double[cells];
        Arrays.fill(normDist, Double.POSITIVE_INFINITY);
        boolean[] channel = new boolean[cells];
        for (Reach reach : reaches) {
            for (int i = 1; i < reach.cell.length; i++) {
                paintSegment(reach, i - 1, i, nx, nz, normDist, halfW, depth, channel, cellSize);
            }
        }
        return new Result(reaches, normDist, halfW, depth, channel, 0);
    }

    /**
     * 外部折线（骨架来源切换用，见 {@link #buildFromPolys}）。
     *
     * @param wx,wz      节点世界坐标（**wu**）
     * @param halfWidth  逐节点半宽（block）
     * @param depth      逐节点水深（block）
     */
    public record Poly(double[] wx, double[] wz, double[] halfWidth, double[] depth) { }

    /**
     * 带【网格间距】的构建。
     *
     * <p>{@code cellSize} = 每格多少 block（生产 step=4 ⇒ 4.0）。
     * 宽度律/岸高/深度坡度全部是 block 物理量，而细化/绘制在格空间进行
     * ⇒ 二者必须换算。step=1 时 cellSize=1.0 与旧入口逐位等价
     * （本类全部既有验证都在 step=1 下取得，不得破坏）。</p>
     *
     * <p>⚠ 不带 cellSize 的旧入口隐含"1格=1块" —— 生产 step=4 下若用旧入口，
     * 绘制距离被当成 block 会把半宽放大 4×（抹开回归）。故适配器必须走本入口。</p>
     */
    public static Result build(double[] bed, double[] flux, boolean[] evidence,
                               boolean[] sea, int nx, int nz, Params params,
                               double cellSize) {
        if (!(cellSize > 0)) throw new IllegalArgumentException("cellSize must be > 0");
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
        // 先从【端点/分叉】出发切段；再从剩余的纯环（无顶点）出发；
        // 最后兜底：骨架格未被任何段覆盖时单独成段（防分叉处丢枝 ⇒ 断流）。
        for (int pass = 0; pass < 3; pass++) {
            for (int k = 0; k < cells; k++) {
                if (!skeleton[k]) continue;
                if (pass == 0 && !vertex[k]) continue;
                if (pass == 1 && vertex[k]) continue;
                if (pass < 2 && visitedEdge[k]) continue;
                if (pass == 2 && visitedEdge[k]) continue;   // 兜底只捡漏
                traceBranch(skeleton, vertex, visitedEdge, flux, bed, sea, nx, nz,
                        k, component[k], params, cellSize, reaches);
            }
        }

        // ③ 横断面距离场（把中心线【沿折线扫掠】展开成几何）
        double[] normDist = new double[cells];
        double[] halfW = new double[cells];
        double[] depth = new double[cells];
        Arrays.fill(normDist, Double.POSITIVE_INFINITY);
        boolean[] channel = new boolean[cells];
        int dropped = 0;
        for (Reach reach : reaches) {
            // ★ 过短碎段不绘（<2 节点 = 孤立单格团）：它们是细化后的残渣，
            //   不是河。数量打印出来供核对，绝不静默。
            if (reach.cell.length < 2) { dropped++; continue; }
            // ★★★ 连续性修复：按【线段扫掠圆盘】绘制（点到线段距离 ≤ half），
            //   而不是逐节点垂直短杆 —— 后者方向逐节点翻转时必留断口
            //   （用户："这河道也不连续啊，怎么一段段的？"）。
            //   这正是参考项目 DynamicWaters `intersectDistanceSq` 与 Streams 样条的同一做法。
            for (int i = 1; i < reach.cell.length; i++) {
                paintSegment(reach, i - 1, i, nx, nz, normDist, halfW, depth, channel, cellSize);
            }
            if (reach.cell.length == 1) {
                paintSegment(reach, 0, 0, nx, nz, normDist, halfW, depth, channel, cellSize);
            }
        }
        return new Result(reaches, normDist, halfW, depth, channel, dropped);
    }

    /** 从 seed 沿骨架走一条段，尽量走直（优先非顶点邻居），并定向为上游→下游。
     *  {@code cellSize} = 每格块数：宽度/岸高/坡度是 block 物理量，走链/绘制在格空间。 */
    private static void traceBranch(boolean[] skeleton, boolean[] vertex, boolean[] used,
                                    double[] flux, double[] bed, boolean[] sea,
                                    int nx, int nz, int seed, int comp,
                                    Params params, double cellSize, List<Reach> out) {
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
        // 定向（★ 保证有向无环，方案 §4.1）：
        //   ① 床面降序：尾端更高 ⇒ 反转（精确比较，不用 ε —— 容差会留灰区，
        //      使 bed[head] 可能略低于 mouth，破坏下述严格性论证）；
        //   ② 严格等高才用【格索引】破平局（head 索引 > mouth 索引）。
        //   ⇒ 恒有 φ = (bed, cellIndex) 满足 φ(head) > φ(mouth)：
        //     床面严格降 ⇒ 第一分量严格大；床面相等 ⇒ 索引严格大。
        //   段图中 A→B 要求 mouth(A)==head(B)（同一格 ⇒ 同 φ），绕环一圈即
        //   φ 严格下降又回到自身 ⇒ 矛盾 ⇒ **数学上不可能有环**。
        //   （实测背景：只按 bed 定向时平坦区出现过 1 个河段环；
        //     等高段任意外定向是环的唯一来源 —— 与 EquilibriumWater parent 2-环同型。）
        if (bed[path.get(path.size() - 1)] > bed[path.get(0)]) {
            java.util.Collections.reverse(path);
        } else if (bed[path.get(path.size() - 1)] == bed[path.get(0)]
                && path.get(0) < path.get(path.size() - 1)) {
            java.util.Collections.reverse(path);        // 平地：head 索引 > mouth 索引
        }
        // ★ 环闭合修剪（实测抓到 1 个自环段）：路径绕回起点时首格==末格
        //   ⇒ φ(head)==φ(mouth)，上面的严格不等式失效 ⇒ 段图自环。
        //   去掉重复的【闭合顶点】即得开放路径：环几何仍完整绘出（只丢一次顶点记录），
        //   且 (bed, index) 严格性恢复 ⇒ 段图无环。
        //   形态来源：顶点处的环状枝（骨架的圈挂在分叉点上），
        //   或纯环（无顶点）绕回起点 —— 两者修剪后均合法。
        if (path.size() >= 2 && path.get(path.size() - 1).intValue() == path.get(0).intValue()) {
            path.remove(path.size() - 1);
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
                    directionAt(cell, i, nz), params.bankRise(), params.maxHalf(), cellSize);
            double half = Math.min(halfQ, halfValley);
            // ★★★ 2026-10-09【修复：最小河宽不得随 STEP 放大】★★★
            //
            // 【原写法】`half = Math.max(half, 0.5 * cellSize)` —— 地板值正比于步长
            //   ⇒ **STEP=4 时最小全宽 4 块**（参照图只有 1~3 块）⇒ 河道粗 2~3 倍。
            //
            // 【实测证据】（`-Dgeogenesis.riverWidthDiag=true`，同 seed/中心）：
            //   | step | half∈[0,1) | half∈[1,2) | half∈[2,3) | 最小全宽 |
            //   |  4   |     0%    |     0%    |   **90.3%** | 4 块  ← 全部顶在地板 |
            //   |  2   |     0%    |   64.3%   |    31.1%    | 2 块  |
            //   |  1   |   14.3%   |  **74.5%**|    10.5%    | 1 块  ✅ 与参照图吻合
            //   ⇒ 河道宽度实际上被【地板】而非【水力几何】支配 ⇒ 形态随 step 漂移。
            //
            // 【为什么地板本身是对的】"中心线本身必须有水"—— 而那需要 **1 个水面方块**，
            //   与"网格步长"无关（游戏里水的最小单位恒为 1 块）。
            //   原写法把"1 格"当成了 unit ⇒ step>1 时被放大 STEP 倍。
            //
            // 【正解】地板 = 固定 0.5 块（= 1 块全宽），**与 cellSize 解耦**。
            //   这样 STEP=4 也能给出 1 块宽的河道：保留速度，形态不再随 step 漂移。
            half = Math.max(half, MIN_HALF_BLOCKS);
            double dep = depthFor(q[i], localSlope(bed, nx, nz, k, cellSize), params);
            // ★ 地形夹逼不是"裁掉宽度丢掉流量"：宽度被钳时相应【加深】，
            //   保持过水断面量级（方案 §4.3 明确要求）。
            if (half < halfQ - 1e-9) {
                dep = Math.min(params.maxDepth(), dep * Math.sqrt(halfQ / half));
            }
            w[i] = 2 * half;
            d[i] = dep;
            if (sea != null && sea[k]) d[i] = Math.max(d[i], 0);
        }
        // ★★ 沿程平滑（治"串珠/团状"）★★
        //   bankRise 逐点测量会被地形噪声抖动 ⇒ 宽度沿程忽大忽小
        //   （用户："散点团状河流"）。参考项目同一做法：
        //   Streams/Farseek 的河宽是样条上的连续量；本项目生产线
        //   亦有 clampMonotonicDownstream（宽度/深度有限变化率，方案 §4.3）。
        //   ⇒ 中值 3 滤波去孤立尖峰 + 相邻节点变化率限幅。
        median3(w);
        median3(d);
        limitSlope(w, MAX_WIDTH_STEP);
        limitSlope(d, MAX_WIDTH_STEP);
        out.add(new Reach(cell, q, w, d, comp));
    }

    /** 沿程宽度/深度的最大逐节点变化比（1+X ⇒ 单步最多涨 X%）。 */
    private static final double MAX_WIDTH_STEP = 0.5;

    /**
     * 河道【最小半宽】（block）—— ★ 与网格步长解耦（2026-10-09 修复）。
     *
     * <p>语义："中心线本身必须有水"需要 1 个水面方块 ⇒ 半宽 0.5 块（全宽 1 块）。
     * <b>游戏里水的最小单位恒为 1 块，与网格步长无关</b> ⇒ 该值必须是常量。</p>
     *
     * <p>⚠ 原写法是 {@code 0.5 * cellSize}（地板正比于步长）⇒ `STEP=4` 时最小全宽
     * 4 块 ⇒ 实测 90.3% 的河道被顶在该地板上 ⇒ 宽度由地板而非水力几何决定
     * ⇒ **形态随 step 漂移**。改常量后 `STEP=4` 也能给出 1 块宽的河道。</p>
     */
    private static final double MIN_HALF_BLOCKS = 0.5;

    /** 3 点中值滤波（原地；首尾不动，保持端点=汇口/河口语义）。 */
    private static void median3(double[] a) {
        if (a.length < 3) return;
        double[] copy = a.clone();
        for (int i = 1; i < a.length - 1; i++) {
            double x = copy[i - 1], y = copy[i], z = copy[i + 1];
            double m = Math.max(Math.min(x, y), Math.min(Math.max(x, y), z));
            a[i] = m;
        }
    }

    /** 相邻节点变化率限幅：双向各扫一遍（确定性），消除台阶与串珠。 */
    private static void limitSlope(double[] a, double maxStep) {
        for (int i = 1; i < a.length; i++) {
            double limit = a[i - 1] * (1 + maxStep);
            if (a[i] > limit) a[i] = limit;
        }
        for (int i = a.length - 2; i >= 0; i--) {
            double limit = a[i + 1] * (1 + maxStep);
            if (a[i] > limit) a[i] = limit;
        }
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
                                          double[] dir, double bankRise, double maxHalf,
                                          double cellSize) {
        int x = k / nz, z = k % nz;
        double base = bed[k];
        // 法向 = 方向旋转 90°（在网格坐标里足够近似，测量本身对角度不敏感）
        double nxn = -dir[1], nzn = dir[0];
        double norm = Math.hypot(nxn, nzn);
        if (norm < 1e-9) return maxHalf;
        nxn /= norm;
        nzn /= norm;
        // 走格数 = 半宽(block) / 每格块数 —— cellSize=1 时即旧的 ceil(maxHalf)
        int limit = (int) Math.ceil(maxHalf / cellSize);
        double halfCells = limit;
        for (int sign = -1; sign <= 1; sign += 2) {
            int reached = limit;
            for (int s = 1; s <= limit; s++) {
                int a = (int) Math.round(x + sign * nxn * s);
                int b = (int) Math.round(z + sign * nzn * s);
                if (a < 0 || b < 0 || a >= nx || b >= nz) { reached = s - 1; break; }
                if (bed[a * nz + b] > base + bankRise) { reached = s - 1; break; }
            }
            halfCells = Math.min(halfCells, Math.max(1, reached));
        }
        // 格 → block（并夹回 maxHalf：limit 用 ceil ⇒ 不整除时可能超出）
        // ★ 2026-10-09：地板由 `cellSize` 改为 `MIN_HALF_BLOCKS` —— 与上处同一 bug
        //   （原 `Math.max(cellSize, …)` 在 STEP=4 时把最小半宽抬到 4 块）。
        return Math.min(maxHalf, Math.max(MIN_HALF_BLOCKS, halfCells * cellSize));
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
    private static double localSlope(double[] bed, int nx, int nz, int k, double cellSize) {
        int x = k / nz, z = k % nz;
        double here = bed[k];
        double best = 0;
        for (int[] d : DIRS8) {
            int a = x + d[0], b = z + d[1];
            if (a < 0 || b < 0 || a >= nx || b >= nz) continue;
            double drop = here - bed[a * nz + b];
            // 邻距（block）= 格距 × 每格块数 ⇒ slope 单位 = block/block（cellSize=1 即旧口径）
            double dist = ((d[0] == 0 || d[1] == 0) ? 1.0 : Math.sqrt(2.0)) * cellSize;
            best = Math.max(best, drop / dist);
        }
        return best;
    }

    /**
     * <h2>沿折线【扫掠圆盘】绘制（连续性修复）</h2>
     *
     * <p>用户实测反馈：「这河道也不连续啊，怎么一段段的？」
     * 根因：此前用<b>逐节点垂直短杆</b>绘制 —— 节点方向逐格翻转时，
     * 相邻两根短杆之间必留 1 格缝（尤其对角走向）⇒ 河道断成一段段。</p>
     *
     * <p><b>正解 = 点到线段距离场</b>：对每个线段，把包围盒内每格
     * 与线段求距离，{@code dist ≤ half} 即属河道。
     * 这是<b>连续几何</b>：相邻线段的圆盘在共享端点处必然重叠 ⇒ 无断口。
     * 参考项目同一做法：DynamicWaters {@code intersectDistanceSq}
     * （`HydrologyManager.txt`）、Streams 样条距离场、Farseek {@code Valley.carve}。</p>
     *
     * <p>半宽沿线段【线性插值】于两端节点 ⇒ 宽度沿程连续变化（无台阶）。</p>
     *
     * <p>单位：{@code half} 是 block（宽度律/岸测都是 block 物理量），
     * 而点线距离在【格空间】计算 ⇒ 乘 {@code cellSize} 换算成 block 再比较
     * （cellSize=1 时与旧版逐位一致）。</p>
     */
    private static void paintSegment(Reach reach, int i0, int i1, int nx, int nz,
                                     double[] normDist, double[] halfW, double[] depth,
                                     boolean[] channel, double cellSize) {
        int k0 = reach.cell[i0], k1 = reach.cell[i1];
        int x0 = k0 / nz, z0 = k0 % nz;
        int x1 = k1 / nz, z1 = k1 % nz;
        double h0 = reach.width[i0] * 0.5, h1 = reach.width[i1] * 0.5;
        double halfMax = Math.max(h0, h1);
        if (!(halfMax > 0)) return;
        // 包围盒（含半宽余量；半宽 block → 格）
        int r = (int) Math.ceil(halfMax / cellSize) + 1;
        int ax = Math.max(0, Math.min(x0, x1) - r), bx = Math.min(nx - 1, Math.max(x0, x1) + r);
        int az = Math.max(0, Math.min(z0, z1) - r), bz = Math.min(nz - 1, Math.max(z0, z1) + r);
        double dx = x1 - x0, dz = z1 - z0;
        double segLen2 = dx * dx + dz * dz;
        for (int a = ax; a <= bx; a++) {
            for (int b = az; b <= bz; b++) {
                // 点到线段距离（端点处 t 钳到 [0,1]，单节点段退化为点距离）
                double t = segLen2 > 0
                        ? Math.max(0, Math.min(1, ((a - x0) * dx + (b - z0) * dz) / segLen2))
                        : 0;
                double px = x0 + t * dx, pz = z0 + t * dz;
                double dist = Math.hypot(a - px, b - pz) * cellSize;   // 格距 → block
                double half = h0 + t * (h1 - h0);      // 半宽沿线性插值 ⇒ 沿程连续
                if (dist > half || half <= 0) continue;
                int kk = a * nz + b;
                double cn = dist / half;               // 0=河心 1=水边（无量纲，单位自洽）
                if (cn < normDist[kk]) {
                    normDist[kk] = cn;
                    halfW[kk] = half;
                    depth[kk] = reach.depth[i0] + t * (reach.depth[i1] - reach.depth[i0]);
                }
                channel[kk] = true;
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
