package com.geogenesis.worldgen.hydrology.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * <h1>沿骨架折线的河宽剖面</h1>
 *
 * <p>Coarse 格 discharge → 逐 shape 节点半宽，并处理三类接缝：trace 间 JOIN（含串联合并段）、
 * 跨区同向路径属性继承、coarse 格台阶。真源头以最小半宽起步，湖口与续流不重复河头淡出。</p>
 *
 * <h2>口径</h2>
 * <ul>
 *   <li>不要求逐点严格单调：只要求总体向下游趋宽，局部允许浮动（来自 {@link #LOCAL_DEVIATION}）；</li>
 *   <li>连续平滑为核心：段与段在共享节点取同一值；</li>
 *   <li>不改骨架：只产出宽度数值，不读写 {@code Shaped.x/z}；</li>
 *   <li>汇合匹配用接收方<b>汇合点处</b>的宽度，不用其首节点宽度；不复制下游；</li>
 *   <li>不消费 {@code dischargeAt}（3×3 邻域最大 Q）—— 那是"凭空加粗"。</li>
 * </ul>
 *
 * <h2>硬 pin 计划（一切接缝约束的统一表示）</h2>
 * <p>每个约束都是一段弧长平台 {@code [lo, hi] → val}：</p>
 * <ul>
 *   <li>boundary 约定宽度 → 平台（投影落在内部时取整段，落在端点上时退化为零长单节点）；</li>
 *   <li>JOIN 汇合约束 → 上游 trace <b>尾节点</b>上的零长平台（{@link #joinTailPin}）。</li>
 * </ul>
 * <p>两者<b>合并进同一份计划一次计算</b>（{@link #applyPinPlan}），而不是"先 fade 再 pins"——
 * 否则上游自己的 pin 会把刚匹配好的尾宽改掉，与接收方再次不等。</p>
 *
 * <h2>写回规则（局部、有界）</h2>
 * <ol>
 *   <li>平台之内：<b>精确</b>等于约定值（不受任何其它 pin 影响）；</li>
 *   <li>平台之外 32 block 内：smoothstep 渐隐，权重下限到原剖面；</li>
 *   <li>多处渐隐重叠：按权重混合，权重和 &gt;1 时归一；</li>
 *   <li>距所有平台都 ≥ {@link #TRANSITION_BLOCKS}：<b>保留原剖面</b>
 *       ⇒ 两个平台相距 &gt; 64 block 时正中不动（不会把中间整段拉成插值）。</li>
 * </ol>
 *
 * <h2>内部契约（违约抛异常，不静默掩盖）</h2>
 * <ul>
 *   <li>两个<b>不同值</b>平台重叠（含共用端点）⇒ {@link IllegalStateException}（带弧长位置）；</li>
 *   <li>同一 trace 两个不同接收者、接收节点越界 ⇒ 同上。</li>
 * </ul>
 *
 * <h2>明确不保证</h2>
 * <ul>
 *   <li>trace 后继成环：该环<b>不做</b> JOIN 匹配（保留原剖面），但其 pins 仍生效
 *       （那是与邻区的硬契约，与拓扑无关）；</li>
 *   <li>短折线的沿程分辨力受 {@code WIDTH_SMOOTH}（单位=节点数）限制：窗口
 *       {@code [i−r,i+r]} 覆盖整条折线时所有节点被摊成均值 ⇒ 沿程对比消失
 *       （实测 {@code widthSmooth=32} 摊平 18 节点支流）；</li>
 *   <li>渲染正确性仍须实机/生产图目检。</li>
 * </ul>
 */
public final class RiverWidthProfile {

    /**
     * <h2>★★ 河宽规则（唯一权威，用户 2026-10-27 最终定稿）★★</h2>
     *
     * <p>用户原话：「明明我说的很多内容都是描述同一个要求」。归纳后只有<b>一条规则链</b>：</p>
     *
     * <blockquote>
     *   <b>一条河的宽度，就是从「起点宽度」到「终点宽度」的一条平滑直线。</b>
     * </blockquote>
     *
     * <h3>规则链（严格按序，无分支）</h3>
     * <ol>
     *   <li><b>① 定终点</b>：接收水体在该接口的尺度。
     *       入湖 = 接收湖的横向尺度（{@link LakeWidthEnvelope} 的 LAKE_MOUTH 测量）；
     *       入海 = 按用户确认的 A 方案，用<b>本河末端天然宽度</b>（不加额外约束）。</li>
     *   <li><b>② 定起点候选</b>：<b>真源头 = 1 格全宽</b>；
     *       <b>出湖 = 湖泊尺度</b>（随湖大小变化，<b>全宽 ≤ {@value #MAX_OUTLET_FULL_WIDTH} 格</b>）。</li>
     *   <li><b>③ 反推起点</b>：{@code 起点 = min(候选, 终点)}。
     *       <b>任何情况下都先定终点、再由终点反推起点；起点不得超过终点。</b>
     *       见 {@link #feasibleStartHalf}。</li>
     *   <li><b>④ 连线</b>：中间节点 = 起点与终点之间按弧长<b>线性插值</b>，
     *       <b>不做逐节点压制</b>（用户：「中间过程按平缓线性变化，不被单调强制」）。</li>
     *   <li><b>⑤ 钳制</b>：全宽 ∈ [1, 12] 格。</li>
     * </ol>
     *
     * <h3>接缝契约（与宽度走向无关，只在端点生效，不改中间插值）</h3>
     * <ul>
     *   <li>跨区 typed 端口：双方宽度必须一致；</li>
     *   <li>串联段：共用同一断面（A 尾 == B 头）；</li>
     *   <li>汇流：合流后接收段 ≥ 任一入流。</li>
     * </ul>
     *
     * <h3>已被否定的实现（三次实错，勿重蹈）</h3>
     * <ol>
     *   <li><b>出湖量当整条河的上限</b>（{@code Math.min} 铺满整个 trace）——
     *       用户澄清「出湖量就是说的出湖起点」，那是<b>起点的取值</b>，不是全河上限；
     *       铺满整条会压平 101 条河（如 {@code (0,-1) trace24} 11.19→2.20 恒定）。</li>
     *   <li><b>向源头全局运行最小</b>——把整棵汇流树削到最细出口：370 条剖面压平、
     *       340 条最大宽度削掉 &gt;1 格。</li>
     *   <li><b>逐节点强制顺流不减</b>——与汇流/湖口上限互相打回，实测 106–114 处汇流违约；
     *       而禁用后同区契约全绿。故本类<b>不再有任何逐节点单调扫描</b>。</li>
     * </ol>
     *
     * <p><b>典型形态</b>：真源头→大湖：1 格 → 大（顺流变宽）；
     * 大湖→小湖：起点被终点压到小湖尺度，整条偏细、近等宽（用户：「细没问题啊」）。</p>
     */
    private RiverWidthProfile() { }

    /** 接缝过渡弧长（block）。两侧各 32 ⇒ 平台相距 &gt;64 时正中必回原剖面。 */
    static final double TRANSITION_BLOCKS = 32.0;

    /** 判定平台重叠/端点相接的弧长容差（block）。 */
    private static final double ARC_EPS = 1e-9;

    /**
     * 趋势中保留的原始偏离比例（0=纯趋势，1=完全跟随原始）。
     *
     * <p>纯 cumulative max 会把局部回落抹平成平台；本值是"总体趋宽 + 局部浮动"的折中。
     * 局部回落<b>正是</b>由此项产生（cumulative max 与它的移动平均都不会引入回落）。
     * 不是系统属性旋钮。</p>
     */
    static final double LOCAL_DEVIATION = 0.35;

    /**
     * 生产入口：返回逐 shape 节点对齐的半宽（block）。
     *
     * @param polys        骨架折线（下标与 traces 对齐）
     * @param pipe         水文管线结果；可为 null（⇒ 全最小可见宽度）
     * @param ox,oz        窗口原点（block）
     * @param n            窗口格数
     * @param step         每格块数
     * @param traces       骨架 trace 表；可为 null
     * @param rivers       局部河流单元表；可为 null（⇒ 跳过 JOIN 匹配）
     * @param boundaryHalf coarse cell → 双方约定半宽；可为 null/空
     * @return 外层=折线；内层长度 == 对应 {@code Shaped.size()}
     * @throws IllegalStateException pin 计划或 river 拓扑自相矛盾（见类注释"内部契约"）
     */
    public static List<double[]> build(List<DeterministicSkeleton.Shaped> polys,
                                       HydrologyPipeline.Result pipe,
                                       int ox, int oz, int n, int step,
                                       List<DeterministicSkeleton.Trace> traces,
                                       List<DeterministicSkeleton.River> rivers,
                                       Map<Integer, Double> boundaryHalf) {
        return build(polys, pipe, ox, oz, n, step, traces, rivers, boundaryHalf, Map.of(), java.util.Set.of());
    }

    static List<double[]> build(List<DeterministicSkeleton.Shaped> polys,
                                HydrologyPipeline.Result pipe, int ox, int oz, int n, int step,
                                List<DeterministicSkeleton.Trace> traces,
                                List<DeterministicSkeleton.River> rivers, Map<Integer, Double> boundaryHalf,
                                Map<Integer, Double> inheritedHalf, java.util.Set<Integer> continuedHeads) {
        return buildDetailed(polys, pipe, ox, oz, n, step, traces, rivers, boundaryHalf,
                inheritedHalf, continuedHeads, Map.of()).widths();
    }

    record Result(List<double[]> widths, List<double[]> limits, List<LakeWidthEnvelope.Interface> interfaces) { }

    static Result buildDetailed(List<DeterministicSkeleton.Shaped> polys,
                                HydrologyPipeline.Result pipe, int ox, int oz, int n, int step,
                                List<DeterministicSkeleton.Trace> traces,
                                List<DeterministicSkeleton.River> rivers, Map<Integer, Double> boundaryHalf,
                                Map<Integer, Double> inheritedHalf, java.util.Set<Integer> continuedHeads,
                                Map<Integer, Double> inheritedLimits) {
        return buildDetailed(polys, pipe, ox, oz, n, step, traces, rivers, boundaryHalf,
                inheritedHalf, continuedHeads, inheritedLimits, Map.of());
    }

    /**
     * @param outletHalfByWorld 世界出口格 → 出湖半宽（跨窗口取小，见 {@link #worldKey}）；
     *        pass1 必须传空（不得读邻区 pass1），只有 final 才消费
     */
    static Result buildDetailed(List<DeterministicSkeleton.Shaped> polys,
                                HydrologyPipeline.Result pipe, int ox, int oz, int n, int step,
                                List<DeterministicSkeleton.Trace> traces,
                                List<DeterministicSkeleton.River> rivers, Map<Integer, Double> boundaryHalf,
                                Map<Integer, Double> inheritedHalf, java.util.Set<Integer> continuedHeads,
                                Map<Integer, Double> inheritedLimits, Map<Long, Double> outletHalfByWorld) {
        int np = polys == null ? 0 : polys.size();
        List<double[]> out = new ArrayList<>(np);
        if (np == 0) return new Result(out, List.of(), List.of());
        var envelope = LakeWidthEnvelope.build(polys, pipe, ox, oz, n, step, traces, rivers, inheritedLimits);
        RiverCenterlines.Params wp = RiverCenterlines.Params.defaults();
        double maxHalf = wp.maxHalf();
        for (int i = 0; i < np; i++) out.add(rawRow(polys.get(i), pipe, ox, oz, n, step, wp, maxHalf,
                traces == null || i >= traces.size() ? null : traces.get(i), outletHalfByWorld));
        for (int i = 0; i < np; i++) {
            double[] raw = out.get(i);
            if (raw.length > 0) out.set(i, profile(raw));
        }
        double[][] arc = arcLengths(polys);
        List<List<double[]>> pins = pinsByShape(boundaryHalf, polys, arc, traces, ox, oz, n, step);
        anchorBoundaryLimits(envelope.limits(), pins, arc);
        taperSources(out, polys, arc, traces, rivers, continuedHeads, pins,
                envelope.interfaces(), envelope.limits());
        inheritAlong(out, polys, arc, traces, inheritedHalf, ox, oz, n, step);
        for (int p = 0; p < np; p++) {
            applyPinPlan(out.get(p), arc[p], pins.get(p));
            for (int i = 0; i < out.get(p).length; i++) {
                out.get(p)[i] = Math.min(out.get(p)[i], envelope.limits().get(p)[i]);
            }
        }
        propagateDownstream(out, polys, arc, rivers);
        applyJoinAndPins(out, polys, arc, rivers, pins, np, envelope.limits());
        reconcileConfluences(out, polys, arc, rivers, envelope.limits(), pins);
        constrainUpstreamTails(out, polys, arc, rivers);
        for (int p = 0; p < np; p++) {
            double[] row = out.get(p), cap = envelope.limits().get(p);
            for (int i = 0; i < row.length; i++) row[i] = Math.min(row[i], cap[i]);
            clampRow(row, maxHalf);
            for (double[] pin : pins.get(p)) for (int i = 0; i < row.length; i++) {
                if (arc[p][i] >= pin[0]-ARC_EPS && arc[p][i] <= pin[1]+ARC_EPS)
                    row[i] = Math.max(RiverCenterlines.MIN_HALF_BLOCKS, Math.min(maxHalf, pin[2]));
            }
        }
        for (var junction : envelope.interfaces()) {
            if (!junction.applied()) continue;
            int p = junction.poly();
            if (junction.kind().equals(LakeWidthEnvelope.KIND_SOURCE)) {
                // ★ 出湖起点宽度已由湖泊规模定死（全宽 ≤ MAX_OUTLET_FULL_WIDTH），
                //   这是用户口径下的"源头宽度"，不再被"该湖横向尺度"的几何上限二次压细。
                //   两者的职责区分：湖泊规模决定**起点**，横向几何上限约束**入湖端**。
                continue;
            }
            int i = out.get(p).length - 1;
            out.get(p)[i] = Math.min(out.get(p)[i], junction.maxHalf());
            envelope.limits().get(p)[i] = Math.min(envelope.limits().get(p)[i], junction.maxHalf());
        }
        constrainUpstreamTails(out, polys, arc, rivers);
        // ★★ P4b 最后一道：单条 trace 内强制「上游 ≤ 下游」★★
        //   必须在 applyJoinAndPins / reconcileConfluences / pin 写回 / 接口上限【全部之后】执行：
        //   这些步骤都会重新写入点值（pin 精确值、汇流抬升、湖口上限），能把单调结果打出倒挂。
        //   ⚠ 曾经定义了本方法却漏掉调用 ⇒ 死代码，( -1,-1) poly16 出现 6.00→1.38 的倒挂
        //     （用户 2026-10-27 从 (-198,-257) 图中直接指出）。
        return new Result(out, envelope.limits(), envelope.interfaces());
    }

    /** 双方端口值已由同一pass1代表裁定；局部弧长差不能再改变该平台的覆盖上限。 */
    private static void anchorBoundaryLimits(List<double[]> limits, List<List<double[]>> pins, double[][] arc) {
        for (int p = 0; p < limits.size(); p++) for (double[] pin : pins.get(p)) {
            for (int i = 0; i < limits.get(p).length; i++) {
                double gap = gapToRange(pin[0], pin[1], arc[p][i]);
                if (gap >= TRANSITION_BLOCKS) continue;
                double value = Math.max(RiverCenterlines.MIN_HALF_BLOCKS, Math.min(6, pin[2]));
                limits.get(p)[i] = Math.max(limits.get(p)[i], value
                        * (1-smoothstep01(gap/TRANSITION_BLOCKS)));
            }
        }
    }

    // ① 原始采样

    /**
     * ★ 出湖起点的全宽上限（block）：<b>6 格</b>。
     *
     * <p>用户 2026-10-27 明确：出湖起点不是从 1 格开始（那是真源头），而是<b>按湖泊规模</b>定，
     * 但"<b>不能超过 6 格，不然后续宽度就很难有变化</b>"。全图河道全宽上限 12 格，
     * 出湖起点只占一半 ⇒ 下游仍有翻倍空间做平缓增宽。</p>
     */
    static final double MAX_OUTLET_FULL_WIDTH = 6.0;

    /**
     * 逐节点取 Q 宽度。
     *
     * <h3>三种起点的宽度语义（用户 2026-10-27 定稿）</h3>
     * <ul>
     *   <li><b>真源头</b>（普通河头）：从 <b>1 格全宽</b>起步，见 {@link #taperSources}；</li>
     *   <li><b>出湖起点</b>（{@code fromOutlet}）：<b>按湖泊规模</b>定，全宽
     *       <b>不超过 {@value #MAX_OUTLET_FULL_WIDTH} 格</b>，见 {@link #outletSourceHalf}；</li>
     *   <li><b>续流/串联起点</b>：沿用上游来流宽度，不重新收尖。</li>
     * </ul>
     * <p>三者都<b>只作用于起点</b>；中间段与下游保留本来的 Q 剖面（局部平缓变化），
     * 由 {@link #linearSource} 从起点线性展宽到首个接点。</p>
     */
    private static double[] rawRow(DeterministicSkeleton.Shaped sh, HydrologyPipeline.Result pipe,
                                   int ox, int oz, int n, int step,
                                   RiverCenterlines.Params wp, double maxHalf,
                                   DeterministicSkeleton.Trace trace, Map<Long, Double> outletHalfByWorld) {
        int m = sh.size();
        double[] raw = new double[m];
        for (int i = 0; i < m; i++) {
            // 共享汇口格的 Q 属于接收河，不能反向抬高整条支流的末端剖面。
            if (trace != null && trace.end == DeterministicSkeleton.End.JOIN && trace.cell.length > 1) {
                int mouth = trace.mouthCell();
                int k = trace.cell.length - 2;
                while (k > 0 && trace.cell[k] == mouth) k--;
                double mx = ox + (mouth / n) * (double) step, mz = oz + (mouth % n) * (double) step;
                if (Math.hypot(sh.x[i] - mx, sh.z[i] - mz) <= step * 1.5) {
                    raw[i] = ModelHydrologyProvider.SegmentIndex.halfWidthAt(pipe, ox, oz, n, step,
                            ox + trace.cell[k] / n * (double) step, oz + trace.cell[k] % n * (double) step, wp);
                    continue;
                }
            }
            double h = ModelHydrologyProvider.SegmentIndex.halfWidthAt(
                    pipe, ox, oz, n, step, sh.x[i], sh.z[i], wp);
            raw[i] = Math.max(RiverCenterlines.MIN_HALF_BLOCKS, Math.min(maxHalf, h));
        }
        return raw;
    }

    /**
     * 出湖起点的<b>半宽</b>：由<b>湖泊横向尺度</b>（{@link LakeInterfaceScale}）决定，
     * 再把<b>全宽夹到 {@value #MAX_OUTLET_FULL_WIDTH} 格</b>以内（⇒ 半宽 ≤ 3）。
     *
     * <p><b>为什么是湖泊尺度而不是净出流</b>（用户口径"这个看湖泊大小"）：
     * 尺度直接来自该接口处的湖面横向湿宽，是"湖有多大"的直观度量；
     * 净出流是水量账本量，受集水区/蒸发影响，和"湖看起来多大"不一致。</p>
     *
     * <p><b>为什么封顶 6 格</b>（用户原话）："不能超过 6 格，不然后续宽度就很难有变化"。
     * 全图上限 12 格，起点只占一半 ⇒ 下游仍有一倍空间做平缓增宽。</p>
     *
     * @param scaleHalf 该接口的湖尺度半宽上限（来自 envelope 的 LAKE_SOURCE 测量）；NaN/未知 ⇒ 退回最小可见宽
     */
    static double outletSourceHalf(double scaleHalf) {
        if (!Double.isFinite(scaleHalf)) return RiverCenterlines.MIN_HALF_BLOCKS;
        double cap = 0.5 * MAX_OUTLET_FULL_WIDTH;
        return Math.max(RiverCenterlines.MIN_HALF_BLOCKS, Math.min(cap, scaleHalf));
    }

    /** 该接口（折线 + 是否出湖端）的湖尺度半宽上限；无测量 ⇒ NaN。 */
    static double scaleHalfOf(List<LakeWidthEnvelope.Interface> interfaces, int poly, boolean source) {
        if (interfaces == null) return Double.NaN;
        String kind = source ? LakeWidthEnvelope.KIND_SOURCE : LakeWidthEnvelope.KIND_MOUTH;
        for (var it : interfaces) {
            if (it.poly() == poly && kind.equals(it.kind()) && it.applied()) return it.maxHalf();
        }
        return Double.NaN;
    }

    /**
     * <b>R4 起点 ≤ 终点</b>：起点宽度<b>永远由终点反推</b>，不得超过终点。
     *
     * <p>用户口径（2026-10-27 最终定稿）：</p>
     * <blockquote>
     *   「是任何时候都是终点反推起点，不是在什么情况下。我之前就说了，起点除非是真起点才1格。」<br>
     *   「起点的宽度不能超过终点的宽度啊。」
     * </blockquote>
     *
     * <p><b>为什么不再有"斜率预算"</b>：此前写过 {@code 终点 + 1/32×弧长}，那会让长河的起点
     * 合法地粗于终点（poly16 就是 6.00 → 1.38），与"起点不能超过终点"直接矛盾。
     * 现在一律 {@code min(候选, 终点)}，端点单调<b>无例外</b>。</p>
     *
     * <p>R6 的线性插值在此之上自然成立：起点 ≤ 终点时，从起点到终点的线性段单调不减。</p>
     *
     * @param wantStart 起点候选半宽：真源头 = MIN_HALF_BLOCKS；出湖 = 湖泊尺度（≤6 格全宽）
     * @param endHalf   终点半宽（接收水体尺度；入海用本河末端天然宽度）
     * @return 最终起点半宽，恒 ≤ {@code endHalf} 且 ≤ {@code wantStart}
     */
    static double feasibleStartHalf(double wantStart, double endHalf, double arcLen) {
        double want = Double.isFinite(wantStart) ? wantStart : RiverCenterlines.MIN_HALF_BLOCKS;
        if (!Double.isFinite(endHalf)) return want;                 // 无终点约束 ⇒ 不反推
        double end = Math.max(RiverCenterlines.MIN_HALF_BLOCKS, endHalf);
        return Math.max(RiverCenterlines.MIN_HALF_BLOCKS, Math.min(want, end));
    }

    private static double halfFromQ(double q, RiverCenterlines.Params wp) {
        return q > 0 ? Math.max(RiverCenterlines.MIN_HALF_BLOCKS,
                Math.min(wp.maxHalf(), 0.5 * RiverCenterlines.fullWidthPublic(q, wp)))
                : RiverCenterlines.MIN_HALF_BLOCKS;
    }

    /** 世界坐标 → 跨窗口身份键（整块取整，与落块坐标同精度）。 */
    static long worldKey(double x, double z) {
        return ((long) Math.round(x) << 32) ^ (Math.round(z) & 0xffffffffL);
    }

    /** 该 trace 按其<b>自身窗口</b>的源盆净出流应有的出湖半宽；非溢口/身份未知 ⇒ NaN。 */
    static double outletHalfOf(HydrologyPipeline.Result pipe, DeterministicSkeleton.Trace trace) {
        if (pipe == null || trace == null || !trace.fromOutlet || trace.cell.length == 0) {
            return Double.NaN;
        }
        var lakes = pipe.lakeResult();
        int basin = DeterministicSkeleton.sourceBasinOf(lakes, trace.sourceCell());
        return basin < 0 ? Double.NaN
                : halfFromQ(lakes.basins().get(basin).outflow(), RiverCenterlines.Params.defaults());
    }

    // ② 剖面：平滑 → 趋势 → 平滑 → 保留小幅偏离

    /**
     * 包内可测纯函数：原始半宽 → 剖面。
     *
     * <ol>
     *   <li>{@code smoothAlong(raw, WIDTH_SMOOTH)} 消 coarse 台阶（半径 0 ⇒ 原样）；</li>
     *   <li>{@code WIDTH_MONOTONE} ⇒ 趋势 = 上一步的 cumulative max，再做同窗口平滑
     *       ⇒ 总体趋宽。两者都单调不减，故趋势本身<b>不会</b>回落；</li>
     *   <li>结果 = 趋势 + (上一步 − 趋势) × {@link #LOCAL_DEVIATION}
     *       ⇒ 局部回落来自这一项（偏离项），不是来自平滑；
     *       {@code widthMonotone=false} ⇒ 直接返回第 1 步。</li>
     * </ol>
     * <p>不做"上游压向下游头宽度"：那句只适合同一条河的不同 trace，对支流 vs 干流是错的。</p>
     */
    static double[] profile(double[] raw) {
        int m = raw == null ? 0 : raw.length;
        if (m == 0) return new double[0];
        int smooth = ModelHydrologyProvider.SegmentIndex.WIDTH_SMOOTH;
        double[] sm = ModelHydrologyProvider.SegmentIndex.smoothAlong(raw, smooth);
        if (!ModelHydrologyProvider.SegmentIndex.WIDTH_MONOTONE) return sm;
        double[] trend = sm.clone();
        for (int i = 1; i < m; i++) {
            if (trend[i] < trend[i - 1]) trend[i] = trend[i - 1];
        }
        double[] tr = ModelHydrologyProvider.SegmentIndex.smoothAlong(trend, smooth);
        double[] out = new double[m];
        for (int i = 0; i < m; i++) out[i] = tr[i] + (sm[i] - tr[i]) * LOCAL_DEVIATION;
        return out;
    }

    /**
     * ★ 起点收尖：<b>真源头从 1 格起步</b>，<b>出湖起点按湖泊规模起步</b>（全宽 ≤ 6 格），
     * 续流/串联起点不重做。
     *
     * <p>与旧实现的差别：旧版把 {@code fromOutlet} 直接 {@code continue} 掉 ⇒ 出湖段<b>完全没有起点处理</b>，
     * 于是整条段按 Q 全宽铺开，源头看着"很粗"。用户口径：出湖起点也是源头的一种，
     * 只是它的起步宽度由湖泊规模决定，而不是固定 1 格。</p>
     *
     * <p>"起步宽度"落成节点 0 上的零长 pin（精确值），再由 {@link #linearSource} 从该值
     * 线性展宽到首个接点 ⇒ 中间段是平缓线性，不会突变。</p>
     */
    private static void taperSources(List<double[]> prof, List<DeterministicSkeleton.Shaped> polys,
                                      double[][] arc, List<DeterministicSkeleton.Trace> traces,
                                      List<DeterministicSkeleton.River> rivers,
                                      java.util.Set<Integer> continuedHeads, List<List<double[]>> pins,
                                      List<LakeWidthEnvelope.Interface> interfaces, List<double[]> limits) {
        if (traces == null) return;
        boolean[] continuation = new boolean[prof.size()];
        int[] checked = new int[prof.size()];
        Arrays.fill(checked, -1);
        if (rivers != null) for (var r : rivers) chainEdges(checked, r, prof.size(), rivers);
        boolean[] cyc = cycleNodes(checked, prof.size());
        if (rivers != null) for (var r : rivers) {
            for (int k = 1; k < r.traceIdx.length; k++) if (!cyc[r.traceIdx[k]]) continuation[r.traceIdx[k]] = true;
            if (r.link == DeterministicSkeleton.RiverLink.CONNECTED
                    && r.traceIdx.length > 0 && !cyc[r.traceIdx[r.traceIdx.length - 1]]) {
                var recv = rivers.get(r.downstreamRiver);
                int d = receiverTrace(recv, r.downstreamNode);
                int c = recv.cells[r.downstreamNode];
                if (d >= 0 && d < traces.size() && traces.get(d).sourceCell() == c) continuation[d] = true;
            }
        }
        for (int p = 0; p < prof.size() && p < traces.size(); p++) {
            var t = traces.get(p);
            if (t.cell.length == 0 || continuation[p]
                    || continuedHeads.contains(t.sourceCell())) continue;
            double[] w = prof.get(p);
            if (w.length == 0) continue;
            // ★★★ 唯一规则链（用户 2026-10-27 最终定稿，无分支）★★★
            //
            //   ① 定终点：接收水体（湖/海）在该接口的尺度。
            //        入湖/入海 → 看是否有 LAKE_MOUTH 尺度；入海按用户确认的 A 方案：
            //        用本河末端天然宽度（不加额外约束）。
            //   ② 定起点候选：真源头 = 1 格全宽；出湖 = 湖泊尺度（随湖大小变化，全宽 ≤ 6 格）。
            //   ③ 反推起点：起点 = min(候选, 终点)  —— 起点永远不能超过终点。
            //   ④ 连线：起点→终点按弧长线性插值，中间不逐节点压制。
            double arcLen = arc[p].length == 0 ? 0 : arc[p][arc[p].length - 1];
            double wantStart = t.fromOutlet
                    ? outletSourceHalf(scaleHalfOf(interfaces, p, true))   // ≤6 格全宽
                    : RiverCenterlines.MIN_HALF_BLOCKS;                    // 真源头 1 格
            double endHalf = w[w.length - 1];                              // 终点（入海=天然末端）
            double mouthScale = scaleHalfOf(interfaces, p, false);
            if (Double.isFinite(mouthScale)) endHalf = mouthScale;         // 入湖用接收湖尺度
            double startHalf = feasibleStartHalf(wantStart, endHalf, arcLen);
            pins.get(p).add(new double[]{0, 0, startHalf});
            // ★ 起点 pin 精确，但要先被"末端反推"约束过（上面），再同步抬节点 0 的上限；
            //   否则 `min(width, cap)` 会把起点再压回去（实测 30 处 half > widthLimits）。
            limits.get(p)[0] = Math.max(limits.get(p)[0], startHalf);
            double reach = arc[p].length == 0 ? TRANSITION_BLOCKS
                    : Math.min(TRANSITION_BLOCKS, arc[p][arc[p].length - 1]);
            for (int i = 0; i < w.length; i++) {
                double alpha = smoothstep01(arc[p][i] / Math.max(ARC_EPS, reach));
                w[i] = startHalf + (w[i] - startHalf) * alpha;
            }
        }
    }

    /** 同一有向格边的独立 pass1 属性；沿已确认格路径插值，末端状态不因换 trace 丢失。 */
    private static void inheritAlong(List<double[]> prof, List<DeterministicSkeleton.Shaped> polys,
                                      double[][] arc, List<DeterministicSkeleton.Trace> traces,
                                      Map<Integer, Double> inherited, int ox, int oz, int n, int step) {
        if (traces == null || inherited.isEmpty()) return;
        for (int p = 0; p < prof.size() && p < traces.size(); p++) {
            List<double[]> samples = new ArrayList<>();
            for (int c : traces.get(p).cell) {
                Double half = inherited.get(c);
                if (half == null) continue;
                double[] hit = projectOnto(polys.get(p), arc[p], ox + (c / n) * step, oz + (c % n) * step);
                if (hit != null) samples.add(new double[]{hit[0], half});
            }
            samples.sort(java.util.Comparator.comparingDouble(a -> a[0]));
            if (samples.isEmpty()) continue;
            double carried = RiverCenterlines.MIN_HALF_BLOCKS;
            for (double[] sample : samples) {
                carried = Math.max(carried, sample[1]);
                sample[1] = carried;
            }
            double[] w = prof.get(p);
            int k = 0;
            for (int i = 0; i < w.length; i++) {
                double s = arc[p][i];
                while (k + 1 < samples.size() && samples.get(k + 1)[0] <= s) k++;
                double[] a = samples.get(k);
                double floor = a[1];
                if (s < a[0]) floor *= smoothstep01(1 - (a[0] - s) / TRANSITION_BLOCKS);
                else if (k + 1 < samples.size()) {
                    double[] b = samples.get(k + 1);
                    double t = (s - a[0]) / Math.max(ARC_EPS, b[0] - a[0]);
                    floor += (b[1] - floor) * t;
                }
                w[i] = Math.max(w[i], floor);
            }
        }
    }

    /** 上游先行，将已有尾宽传到真实接收点之后；不修改接收点之前的河源。 */
    private static void propagateDownstream(List<double[]> prof, List<DeterministicSkeleton.Shaped> polys,
                                             double[][] arc, List<DeterministicSkeleton.River> rivers) {
        int np = prof.size();
        int[] succ = new int[np];
        Arrays.fill(succ, -1);
        if (rivers != null) for (var r : rivers) chainEdges(succ, r, np, rivers);
        boolean[] cyc = cycleNodes(succ, np);
        int[] order = postOrder(succ, np);
        for (int k = order.length - 1; k >= 0; k--) {
            int u = order[k], d = succ[u];
            if (d < 0 || cyc[u] || cyc[d] || prof.get(u).length < 2) continue;
            var sh = polys.get(u);
            double[] hit = projectOnto(polys.get(d), arc[d], sh.x[sh.size() - 1], sh.z[sh.size() - 1]);
            if (hit == null) continue;
            raiseSuffix(prof.get(d), arc[d], hit[0], prof.get(u)[prof.get(u).length - 1]);
        }
    }

    static void raiseSuffix(double[] w, double[] arc, double at, double half) {
        for (int i = 0; i < w.length; i++) {
            double alpha = smoothstep01(1 + (arc[i] - at) / TRANSITION_BLOCKS);
            double floor = RiverCenterlines.MIN_HALF_BLOCKS + (half - RiverCenterlines.MIN_HALF_BLOCKS) * alpha;
            w[i] = Math.max(w[i], floor);
        }
    }

    // ③ 有向继承完成后，逐 trace 逆拓扑匹配接点及显式 pins

    /**
     * 按 trace 后继 DAG 的 post-order 逐个 trace 处理，<b>同一份计划</b>一次写完：
     * 该 trace 自己的 boundary pins + （若有下游）汇合尾约束。
     *
     * <p>接收者先定稿 ⇒ {@link #joinTailPin} 读到的是含接收方 pin 在内的终值。
     * 尾约束是尾节点上的零长平台，故上游自己的 pin 再近也改不掉尾宽（平台内精确优先）。</p>
     */
    private static void applyJoinAndPins(List<double[]> prof, List<DeterministicSkeleton.Shaped> polys,
                                         double[][] arc, List<DeterministicSkeleton.River> rivers,
                                         List<List<double[]>> pins, int np, List<double[]> limits) {
        int[] succ = new int[np];
        Arrays.fill(succ, -1);
        if (rivers != null) for (var r : rivers) chainEdges(succ, r, np, rivers);
        boolean[] cyc = cycleNodes(succ, np);
        for (int u : postOrder(succ, np)) {
            int d = succ[u];
            List<double[]> own = pins.get(u);
            List<double[]> plan = new ArrayList<>(own);
            if (d >= 0 && !cyc[u] && !cyc[d] && serialEdge(u, d, rivers, succ)) {
                double[] tail = joinTailPin(prof, polys, arc, u, d);
                if (tail != null) plan.add(tail);
            }
            applyPinPlan(prof.get(u), arc[u], plan);
            for (int i = 0; i < prof.get(u).length; i++) {
                prof.get(u)[i] = Math.min(prof.get(u)[i], limits.get(u)[i]);
            }
            // 起点 pin（零长平台）存在 ⇒ 该 trace 有明确起点宽度：真源头 1 格，或出湖按湖泊尺度。
            // ★ 两者展宽范围不同（这是关键区别）：
            //   · 真源头是 1 格 ⇒ 可以沿【整程】平缓展宽（用户要求"线性、不突变"）；
            //   · 出湖起点已经按湖泊尺度（最多 6 格）起步 ⇒ 只展宽【起点附近】一小段，
            //     否则当末端被上限压细时，整条长河会被拉成"起点 6 格 → 末端 1 格"的楔形
            //     （实测 35 条、最长 317 节点、差 5 格）。
            // 起点 pin 的值本身就是判据：真源头被钉在 MIN_HALF_BLOCKS（1 格全宽），
            // 出湖起点按湖泊尺度、通常大于它（也可能因小湖恰好等于它）。
            double startPin = Double.NaN;
            for (double[] pin : own) {
                if (pin[0] <= ARC_EPS && pin[1] <= ARC_EPS && Double.isFinite(pin[2])) startPin = pin[2];
            }
            if (Double.isFinite(startPin)) {
                // ★ 用户口径（2026-10-27 第三次重申）：
                //   「单调是对起点和终点的，中间过程按平缓线性变化，不被单调强制」
                //   ⇒ 这里一律展宽到【本 trace 末端】= 该河的终点，
                //     中间节点由 linearSource 线性插值，不再被 32 格截断、也不被中途 pin 打断。
                //   旧写法把 end 取成"最近的 pin"，一旦中途有 boundary pin 就把展宽提前打断，
                //   后面的节点仍按 Q 剖面 ⇒ 起点→pin 之间插值、pin 之后又跳回，形成折角。
                double end = arc[u].length == 0 ? 0 : arc[u][arc[u].length - 1];
                linearSource(prof.get(u), arc[u], end);
            }
        }
    }

    /**
     * 从<b>起点实际宽度</b>线性展宽到首个接点宽度（按弧长均匀）。
     *
     * <p>起点宽度由 {@code applyPinPlan} 精确写入节点 0：</p>
     * <ul>
     *   <li><b>真源头</b> ⇒ 1 格全宽（半宽 {@code MIN_HALF_BLOCKS}）；</li>
     *   <li><b>出湖起点</b> ⇒ 按湖泊规模，全宽 ≤ {@value #MAX_OUTLET_FULL_WIDTH} 格。</li>
     * </ul>
     * <p>因此这里<b>不能</b>再硬编码 {@code MIN_HALF_BLOCKS} 作起点，否则出湖起点会被二次拉回 1 格
     * （这正是用户看到的"出湖和真源头一样细"）。</p>
     */
    static void linearSource(double[] width, double[] arc, double end) {
        if (width.length < 2 || end <= ARC_EPS) return;
        int k = 0;
        while (k + 1 < arc.length && arc[k + 1] < end) k++;
        int next = Math.min(k + 1, width.length - 1);
        double t = (end - arc[k]) / Math.max(ARC_EPS, arc[next] - arc[k]);
        double target = width[k] + (width[next] - width[k]) * t;
        double start = width[0];
        for (int i = 0; i < width.length && arc[i] <= end; i++) {
            width[i] = start + (target - start) * (arc[i] / end);
        }
    }

    /** 所有 pin/河头定稿后，再按有向顺序承接来流；硬上限冲突则平滑收束支流，不反向撑粗。 */
    private static void reconcileConfluences(List<double[]> prof, List<DeterministicSkeleton.Shaped> polys,
                                             double[][] arc, List<DeterministicSkeleton.River> rivers,
                                             List<double[]> limits, List<List<double[]>> pins) {
        int np = prof.size();
        int[] succ = new int[np];
        Arrays.fill(succ, -1);
        if (rivers != null) for (var r : rivers) chainEdges(succ, r, np, rivers);
        boolean[] cyc = cycleNodes(succ, np);
        int[] order = postOrder(succ, np);
        for (int k = order.length - 1; k >= 0; k--) {
            int u = order[k], d = succ[u];
            if (d < 0 || cyc[u] || cyc[d] || prof.get(u).length < 2) continue;
            var sh = polys.get(u);
            double[] hit = projectOnto(polys.get(d), arc[d], sh.x[sh.size()-1], sh.z[sh.size()-1]);
            if (hit == null) continue;
            double[] recv = prof.get(d), up = prof.get(u);
            double target = Math.min(up[up.length-1], lerpAt(limits.get(d), hit));
            raiseSuffix(recv, arc[d], hit[0], target);
            for (int i = 0; i < recv.length; i++) recv[i] = Math.min(recv[i], limits.get(d)[i]);
            // 源头及边界平台必须精确；不再把其32格渐隐重复覆盖到已有来流上。
            for (double[] pin : pins.get(d)) for (int i = 0; i < recv.length; i++) {
                if (arc[d][i] >= pin[0]-ARC_EPS && arc[d][i] <= pin[1]+ARC_EPS)
                    recv[i] = Math.min(pin[2], limits.get(d)[i]);
            }
            double accepted = lerpAt(recv, hit);
            if (up[up.length-1] > accepted + ARC_EPS) {
                double end = arc[u][arc[u].length-1], before = up[up.length-1];
                for (int i = 0; i < up.length; i++) {
                    double ceiling = accepted + (before-accepted)
                            * Math.min(1, (end-arc[u][i])/TRANSITION_BLOCKS);
                    up[i] = Math.min(up[i], ceiling);
                }
                for (double[] pin : pins.get(u)) for (int i = 0; i < up.length; i++) {
                    if (arc[u][i] >= pin[0]-ARC_EPS && arc[u][i] <= pin[1]+ARC_EPS)
                        up[i] = Math.min(pin[2], limits.get(u)[i]);
                }
            }
        }
    }

    /** 下游上限可能收束一条中间接收河，逆拓扑把该终值传回所有更上游的汇口。 */
    private static void constrainUpstreamTails(List<double[]> prof, List<DeterministicSkeleton.Shaped> polys,
                                               double[][] arc, List<DeterministicSkeleton.River> rivers) {
        int[] succ = new int[prof.size()];
        Arrays.fill(succ, -1);
        if (rivers != null) for (var r : rivers) chainEdges(succ, r, prof.size(), rivers);
        boolean[] cyc = cycleNodes(succ, prof.size());
        for (int u : postOrder(succ, prof.size())) {
            int d = succ[u];
            if (d < 0 || cyc[u] || cyc[d] || prof.get(u).length < 2) continue;
            double[] pin = joinTailPin(prof, polys, arc, u, d);
            if (pin == null) continue;
            double[] row = prof.get(u);
            double target = pin[2], before = row[row.length-1];
            if (serialEdge(u, d, rivers, succ) && before < target) {
                var sh = polys.get(u);
                double[] hit = projectOnto(polys.get(d), arc[d], sh.x[sh.size()-1], sh.z[sh.size()-1]);
                double[] recv = prof.get(d);
                int seg = (int) hit[1];
                for (int i = 0; i < recv.length; i++) {
                    double distance = gapToRange(arc[d][seg], arc[d][seg+1], arc[d][i]);
                    double factor = 1-smoothstep01(distance/TRANSITION_BLOCKS);
                    recv[i] = Math.min(recv[i], recv[i] + (before-recv[i])*factor);
                }
                target = before;
            }
            if (before > target) {
                double end = arc[u][arc[u].length-1];
                for (int i = 0; i < row.length; i++) {
                    double cap = target + (before-target)*Math.min(1, (end-arc[u][i])/TRANSITION_BLOCKS);
                    row[i] = Math.min(row[i], cap);
                }
            }
        }
    }

    /** 同一单元内串联，或只有一个来流的接收头，才共享同一断面。 */
    static boolean serialEdge(int up, int down, List<DeterministicSkeleton.River> rivers, int[] succ) {
        if (rivers == null) return false;
        for (var r : rivers) {
            for (int k = 0; k + 1 < r.traceIdx.length; k++) {
                if (r.traceIdx[k] == up && r.traceIdx[k + 1] == down) return true;
            }
            if (r.link != DeterministicSkeleton.RiverLink.CONNECTED || r.traceIdx.length == 0
                    || r.traceIdx[r.traceIdx.length - 1] != up || r.downstreamNode != 0) continue;
            int incoming = 0;
            for (int d : succ) if (d == down) incoming++;
            return incoming == 1;
        }
        return false;
    }

    /** 一个河流单元贡献的后继边：串联段之间 + CONNECTED 的真实接收点。 */
    static void chainEdges(int[] succ, DeterministicSkeleton.River r, int np,
                                   List<DeterministicSkeleton.River> rivers) {
        if (r == null || r.traceIdx == null || r.traceIdx.length == 0) return;
        for (int k = 0; k + 1 < r.traceIdx.length; k++) {
            link(succ, r.traceIdx[k], r.traceIdx[k + 1], np);   // 串联被合并的 trace
        }
        if (r.link != DeterministicSkeleton.RiverLink.CONNECTED) return;
        if (r.downstreamRiver < 0 || r.downstreamRiver >= rivers.size()) {
            throw new IllegalStateException("CONNECTED river with downstreamRiver="
                    + r.downstreamRiver + " outside rivers.size()=" + rivers.size());
        }
        int upstream = r.traceIdx[r.traceIdx.length - 1];
        int receiver = receiverTrace(rivers.get(r.downstreamRiver), r.downstreamNode);
        link(succ, upstream, receiver, np);
    }

    /**
     * 接收方的真实 shape 下标：{@code River.traceOf[node]}。
     *
     * <p>不能直接用 {@code node}：它是接收方 {@code River.cells} 的下标（串接后的格路径），
     * 而 shape 是重采样后的另一条节点序列，两者编号不同 ⇒ 必然错位。</p>
     *
     * @throws IllegalStateException node 越界或字段缺失（拓扑与 shape 不一致）
     */
    static int receiverTrace(DeterministicSkeleton.River recv, int node) {
        if (recv == null || recv.traceOf == null || recv.traceIdx == null) {
            throw new IllegalStateException("CONNECTED receiver river is missing traceOf/traceIdx");
        }
        if (node < 0 || node >= recv.traceOf.length) {
            throw new IllegalStateException("downstreamNode=" + node + " outside receiver traceOf.length="
                    + recv.traceOf.length + " (traceIdx=" + Arrays.toString(recv.traceIdx) + ")");
        }
        return recv.traceOf[node];
    }

    /**
     * 登记后继边。同一 trace 出现两个<b>不同</b>接收者 ⇒ 抛异常。
     *
     * <p>不取"较小下标"作平局：那让胜者取决于数值编号而非拓扑，等于把冲突静默掩盖。
     * 重复登记同一目标则幂等。</p>
     */
    private static void link(int[] succ, int u, int d, int np) {
        if (u < 0 || d < 0 || u >= np || d >= np || u == d) return;
        if (succ[u] < 0) { succ[u] = d; return; }
        if (succ[u] != d) {
            throw new IllegalStateException("trace " + u + " has two different receivers: "
                    + succ[u] + " and " + d);
        }
    }

    /**
     * 汇合尾约束：上游尾节点上的零长平台 {@code {arcEnd, arcEnd, target}}。
     *
     * <p>汇合点由<b>几何投影</b>求（不用 coarse 序号）⇒ 中段 JOIN 取到干流中段宽度，
     * 不是首节点宽度。返回 null 表示无法定位（退化折线），调用方跳过。</p>
     */
    private static double[] joinTailPin(List<double[]> prof, List<DeterministicSkeleton.Shaped> polys,
                                        double[][] arc, int up, int down) {
        if (up >= prof.size() || down >= prof.size()) return null;
        double[] pu = prof.get(up), pd = prof.get(down);
        if (pu.length < 2 || pd.length < 2) return null;
        var su = polys.get(up);
        var sd = polys.get(down);
        if (su == null || sd == null || su.size() < 2 || arc[up].length != pu.length) return null;
        double[] hit = projectOnto(sd, arc[down], su.x[su.size() - 1], su.z[su.size() - 1]);
        if (hit == null) return null;
        double end = arc[up][arc[up].length - 1];
        return new double[]{end, end, lerpAt(pd, hit)};
    }

    // ④ 边界 pins → 逐 shape 平台

    /**
     * 把 {@code boundaryHalf}（coarse cell → 约定半宽）投影成逐 shape 的 pin 平台。
     *
     * <p>只处理<b>确实包含该 cell</b> 的 trace；位置用几何投影求。平台形态：</p>
     * <ul>
     *   <li>投影落在<b>段内部</b> ⇒ 整段 {@code [arc[seg], arc[seg+1]]}（夹住投影的两节点同值）；</li>
     *   <li>投影正好落在<b>端点</b>（{@code t=0} 或 {@code t=1}）⇒ <b>零长</b>平台，只钉该节点。
     *       否则相邻两个 pin 会各自扩成两节点平台而互相重叠 —— 那会凭空制造冲突。</li>
     * </ul>
     */
    private static List<List<double[]>> pinsByShape(Map<Integer, Double> boundaryHalf,
                                                    List<DeterministicSkeleton.Shaped> polys,
                                                    double[][] arc,
                                                    List<DeterministicSkeleton.Trace> traces,
                                                    int ox, int oz, int n, int step) {
        List<List<double[]>> out = new ArrayList<>(polys.size());
        for (int i = 0; i < polys.size(); i++) out.add(new ArrayList<>());
        if (boundaryHalf == null || boundaryHalf.isEmpty() || traces == null) return out;
        if (n <= 0 || step <= 0) return out;
        for (var e : boundaryHalf.entrySet()) {
            if (e.getKey() == null || e.getValue() == null) continue;
            int cell = e.getKey();
            double half = e.getValue();
            if (cell < 0 || !Double.isFinite(half)) continue;
            double bx = ox + (cell / n) * (double) step;
            double bz = oz + (cell % n) * (double) step;
            for (int ti = 0; ti < traces.size() && ti < polys.size(); ti++) {
                var t = traces.get(ti);
                if (t == null || t.cell == null || !containsCell(t.cell, cell)) continue;
                var sh = polys.get(ti);
                if (sh == null || sh.size() < 2 || arc[ti].length != sh.size()) continue;
                double[] hit = projectOnto(sh, arc[ti], bx, bz);
                if (hit == null) continue;
                int seg = (int) hit[1];
                double tt = hit[2];
                double lo;
                double hi;
                if (tt <= ARC_EPS) {                       // 落在前端点 ⇒ 只钉一个节点
                    lo = arc[ti][seg];
                    hi = lo;
                } else if (tt >= 1 - ARC_EPS) {            // 落在后端点 ⇒ 只钉一个节点
                    lo = arc[ti][seg + 1];
                    hi = lo;
                } else {                                   // 段内部 ⇒ 整段平台
                    lo = arc[ti][seg];
                    hi = arc[ti][seg + 1];
                }
                out.get(ti).add(new double[]{lo, hi, half});
            }
        }
        return out;
    }

    /**
     * 把一组 pin 平台落到单个 shape 上：平台内精确、平台外 32 block 渐隐、更远处保留原剖面。
     *
     * <p>合并规则：完全相同的平台去重；<b>同值</b>且重叠/相接的平台取并集；
     * <b>不同值</b>且重叠/相接的平台 ⇒ 抛 {@link IllegalStateException}（带弧长与两个值）。
     * 不做"先到者裁剪"——那会在裁剪点留下阶跃，且让结果取决于输入顺序。</p>
     *
     * @param pins 未排序亦可（内部按 {@code (lo, hi, val)} 排序）
     * @return {@code {生效平台数, 同值合并数}}
     * @throws IllegalStateException 不同值平台重叠
     */
    static int[] applyPinPlan(double[] w, double[] arcN, List<double[]> pins) {
        int m = w.length;
        if (m == 0 || pins == null || pins.isEmpty() || arcN == null || arcN.length != m) {
            return new int[]{0, 0};
        }
        List<double[]> sorted = new ArrayList<>();
        for (double[] p : pins) {
            if (p == null || p.length < 3) continue;
            if (!(p[1] >= p[0]) || !Double.isFinite(p[2])) continue;
            sorted.add(new double[]{p[0], p[1], p[2]});
        }
        if (sorted.isEmpty()) return new int[]{0, 0};
        sorted.sort((a, b) -> {
            int c = Double.compare(a[0], b[0]);
            if (c != 0) return c;
            c = Double.compare(a[1], b[1]);
            if (c != 0) return c;
            return Double.compare(a[2], b[2]);
        });
        List<double[]> plan = new ArrayList<>();
        int merges = 0;
        for (double[] p : sorted) {
            double lo = p[0], hi = p[1], val = p[2];
            double[] last = plan.isEmpty() ? null : plan.get(plan.size() - 1);
            if (last != null && lo <= last[1] + ARC_EPS) {
                if (val != last[2]) {
                    throw new IllegalStateException(String.format(
                            "overlapping pins with different values at arc [%.3f,%.3f]: %.4f vs %.4f",
                            Math.max(lo, last[0]), Math.min(hi, last[1]), last[2], val));
                }
                last[1] = Math.max(last[1], hi);
                merges++;
                continue;
            }
            plan.add(new double[]{lo, hi, val});
        }
        writePlan(w, arcN, plan);
        return new int[]{plan.size(), merges};
    }

    /**
     * 写回：平台内精确；近邻平台间 smoothstep 插值，远平台各自 32 block 渐隐；
     * 距所有平台 ≥ {@link #TRANSITION_BLOCKS} 的节点<b>保持原值</b>。
     */
    private static void writePlan(double[] w, double[] arcN, List<double[]> plan) {
        for (int j = 0; j < w.length; j++) {
            double s = arcN[j];
            int inside = containing(plan, s);
            if (inside >= 0) { w[j] = plan.get(inside)[2]; continue; }
            double[] left = null, right = null;
            for (double[] p : plan) {
                if (p[1] < s) left = p;
                else if (p[0] > s) { right = p; break; }
            }
            if (left != null && right != null && right[0] - left[1] < 2 * TRANSITION_BLOCKS) {
                double t = smoothstep01((s - left[1]) / (right[0] - left[1]));
                w[j] = left[2] + (right[2] - left[2]) * t;
            } else {
                double[] p = left == null ? right : right == null ? left
                        : s - left[1] < right[0] - s ? left : right;
                double d = gapToRange(p[0], p[1], s);
                if (d >= TRANSITION_BLOCKS) continue;
                double blend = 1.0 - smoothstep01(d / TRANSITION_BLOCKS);
                w[j] += (p[2] - w[j]) * blend;
            }
        }
    }

    /** 包含 {@code s} 的平台下标（平台互不重叠 ⇒ 至多一个）；无则 -1。 */
    private static int containing(List<double[]> plan, double s) {
        for (int k = 0; k < plan.size(); k++) {
            double[] p = plan.get(k);
            if (s >= p[0] - ARC_EPS && s <= p[1] + ARC_EPS) return k;
        }
        return -1;
    }

    /** 弧长 {@code s} 到区间 {@code [lo,hi]} 的距离（区间内 = 0）。 */
    static double gapToRange(double lo, double hi, double s) {
        if (s < lo) return lo - s;
        if (s > hi) return s - hi;
        return 0;
    }

    // 通用工具

    /** 逐 shape 累计弧长（block）；空折线给空数组。 */
    static double[][] arcLengths(List<DeterministicSkeleton.Shaped> polys) {
        int np = polys == null ? 0 : polys.size();
        double[][] out = new double[np][];
        for (int i = 0; i < np; i++) {
            var sh = polys.get(i);
            int m = sh == null ? 0 : sh.size();
            double[] a = new double[m];
            for (int k = 1; k < m; k++) {
                a[k] = a[k - 1] + Math.hypot(sh.x[k] - sh.x[k - 1], sh.z[k] - sh.z[k - 1]);
            }
            out[i] = a;
        }
        return out;
    }

    /**
     * 点 {@code (px,pz)} 在 shape 上的最近投影。
     *
     * @return {@code {arc, segIndex, t}}；shape &lt; 2 节点或 arc 长度不符 ⇒ null
     */
    static double[] projectOnto(DeterministicSkeleton.Shaped sh, double[] arc, double px, double pz) {
        int m = sh == null ? 0 : sh.size();
        if (m < 2 || arc.length != m) return null;
        double bestD = Double.POSITIVE_INFINITY, bestT = 0;
        int bestI = 0;
        for (int i = 0; i + 1 < m; i++) {
            double x0 = sh.x[i], z0 = sh.z[i];
            double dx = sh.x[i + 1] - x0, dz = sh.z[i + 1] - z0;
            double len2 = dx * dx + dz * dz;
            double t = len2 > 0 ? (((px - x0) * dx + (pz - z0) * dz) / len2) : 0;
            t = t < 0 ? 0 : (t > 1 ? 1 : t);
            double d = Math.hypot(px - (x0 + t * dx), pz - (z0 + t * dz));
            if (d < bestD - 1e-12) { bestD = d; bestI = i; bestT = t; }
        }
        return new double[]{arc[bestI] + bestT * (arc[bestI + 1] - arc[bestI]), bestI, bestT};
    }

    /** 投影结果处的剖面值（按段线性插值）。 */
    static double lerpAt(double[] v, double[] hit) {
        int i = (int) hit[1];
        int j = Math.min(v.length - 1, i + 1);
        return v[i] + (v[j] - v[i]) * hit[2];
    }

    /** smoothstep：两端导数为 0。 */
    static double smoothstep01(double x) {
        if (x <= 0) return 0;
        if (x >= 1) return 1;
        return x * x * (3 - 2 * x);
    }

    /** 功能图中的环节点（这些节点不做 JOIN 匹配）。 */
    static boolean[] cycleNodes(int[] succ, int np) {
        int[] pos = new int[np];
        Arrays.fill(pos, -1);
        int[] path = new int[np];
        boolean[] cyc = new boolean[np];
        for (int s = 0; s < np; s++) {
            if (pos[s] != -1) continue;
            int len = 0, cur = s;
            while (cur >= 0 && pos[cur] == -1) {
                pos[cur] = len;
                path[len++] = cur;
                cur = succ[cur];
            }
            if (cur >= 0 && pos[cur] >= 0) {
                for (int k = pos[cur]; k < len; k++) cyc[path[k]] = true;
            }
            for (int k = 0; k < len; k++) pos[path[k]] = -2;
        }
        return cyc;
    }

    /** 后继先行的结点序列（post-order）：下游终值先算完。 */
    static int[] postOrder(int[] succ, int np) {
        int[] state = new int[np];
        int[] stack = new int[np];
        int[] buf = new int[np];
        int m = 0;
        for (int s = 0; s < np; s++) {
            if (state[s] != 0) continue;
            int top = 0;
            stack[top++] = s;
            while (top > 0) {
                int cur = stack[top - 1];
                if (state[cur] != 1) state[cur] = 1;
                int nx = succ[cur];
                if (nx >= 0 && state[nx] == 0) { stack[top++] = nx; continue; }
                top--;
                if (state[cur] != 2) { state[cur] = 2; buf[m++] = cur; }
            }
        }
        return Arrays.copyOf(buf, m);
    }

    private static boolean containsCell(int[] cells, int cell) {
        for (int c : cells) if (c == cell) return true;
        return false;
    }

    private static void clampRow(double[] row, double maxHalf) {
        for (int i = 0; i < row.length; i++) {
            double v = row[i];
            if (!Double.isFinite(v)) v = RiverCenterlines.MIN_HALF_BLOCKS;
            row[i] = Math.max(RiverCenterlines.MIN_HALF_BLOCKS, Math.min(maxHalf, v));
        }
    }
}
