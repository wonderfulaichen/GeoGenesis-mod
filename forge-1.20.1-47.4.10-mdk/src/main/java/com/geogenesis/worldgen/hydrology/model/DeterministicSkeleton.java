package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.noise.Simplex;

import java.util.ArrayList;
import java.util.List;

/**
 * ★★★ 2026-10-10【确定性骨架 —— PL-RGA 式追踪 + 湖溢口续流 + legacy 形态收尾】★★★
 *
 * <p><b>用户方向（2026-10-10）</b>：「model 的河流应该像 legacy 那样进行确定性骨架连接，
 * 类似 plate-local-river-generation。宽度目前不要理。河流必须要满足单调下降的机制，
 * 到局部洼地就应该结束开始蓄水成湖，直到湖泊出现新的溢出口作为河流新起点。」
 * 后续补充：「我们的 legacy 就是基于 PL-RGA 进行改进的……model 应该是 legacy 的升级版。」</p>
 *
 * <h3>追踪规则（PL-RGA rivers.cpp:384-468 同型 + legacy 改进）</h3>
 * <ol>
 *   <li><b>源头判据三重</b>（legacy build 同款，治"源头在山顶/河槽切在坡面上"）：
 *       上游面积 ≥ {@code minSourceAccum}（channel initiation，legacy
 *       {@code SOURCE_MIN_ACCUM_CELLS} 同义）+ <b>汇流槽判据</b>（legacy
 *       {@code inValleyTrough}：垂直于下坡方向的两侧任一更低 ⇒ 凸坡 ⇒ 否决）
 *       + parent 存在。按上游面积降序（确定性）。</li>
 *   <li><b>走链</b> = 沿 {@code parent[k]} 前进。minimax 树保证 {@code level[parent] <= level[k]}
 *       ⇒ <b>单调不升</b>（构造性质，探针硬判）。</li>
 *   <li><b>入湖终止</b> = 下一格是湖水面 ⇒ 当前格记 MOUTH_LAKE
 *       （「入湖后结束河流」，用户判据原话）。</li>
 *   <li><b>溢口续流</b> = 每盆地 {@code basinOutlet[b]} 作新河段起点
 *       （「湖泊出现新的溢出口作为河流新起点」）。</li>
 *   <li><b>就近汇入</b>（legacy {@code nearbyDownhillNode} 同型，治"支流平行跑到头"）：
 *       每步先查 {@code joinWindow} 窗口内「已 covered 且 level 严格更低」的节点
 *       （评分 = level + 距离权重 ⇒ 最低优先、等距就近）⇒ 直接连线汇入（JOIN）。</li>
 *   <li><b>入海终止</b> / <b>未决终止</b>（UNRESOLVED 显式类型，不假装有水）。</li>
 *   <li><b>源头间距裁剪</b>（PL-RGA {@code min_source_spacing} 同型）：</li>
 * </ol>
 *
 * <h3>形态收尾（legacy {@code smoothPath} 移植，治"45° 阶梯折线"）</h3>
 * <p>{@link #shape} 把格路径折线加工成可见几何（block 坐标）：</p>
 * <ol>
 *   <li>Catmull-Rom 细分（1 block 间距重采样）—— 折线变曲线；</li>
 *   <li><b>4 倍频 meander</b>（legacy {@code MEANDER_OCTAVES} 同款：波长/振幅逐级减半，
 *       amp={@code 6.0}、波长={@code 24} 块，与 legacy 默认一致；两端 20% 弧长平滑淡出
 *       ⇒ 源头/入湖口/汇合点零位移）；</li>
 *   <li>只动 X/Z（几何蜿蜒），<b>不碰 level/parent/lake</b> ⇒ 湖判/水位/拓扑零影响
 *       （与已否决的"meander 改床面"完全不同——那次切碎了主干）。</li>
 * </ol>
 *
 * <p><b>纯函数</b>：输入全是不可变数组；噪声以固定 seed 播种、世界坐标驱动 ⇒ 同参逐位一致。</p>
 */
public final class DeterministicSkeleton {

    /**
     * 骨架参数。
     *
     * @param minSourceSpacingCells 源头最小间距（格；0=关闭）—— PL-RGA min_source_spacing
     * @param minSourceAccum        源头最小上游面积（discharge 单位；0=关闭）——
     *                              legacy {@code SOURCE_MIN_ACCUM_CELLS} 同义
     * @param joinWindow            就近汇入窗口（格）—— legacy {@code traceStep=2}
     * @param meanderAmp            蜿蜒振幅（block；0=关闭）—— legacy {@code meanderAmp=6.0}
     * @param meanderWavelength     蜿蜒波长（block）—— legacy {@code meanderWavelength=24}
     * @param maxSources            <b>分支数量上限</b>（每 region 成功起段的源头数；0=不限制）
     *                              —— legacy {@code riverCount}（默认 80/region）同义。
     *                              溢口续流段（湖后河）不计入此上限。
     * @param minNodes              <b>河段长度下限</b>（格；&lt;=1 = 不设限）——
     *                              legacy {@code minRiverNodes=3} 同义。短于此的段整条丢弃
     *                              （用户判据「有很多短碎河流」的对症判据；同时是
     *                              "交叉即回滚"的配套语义：不能留下零碎残段）。
     * @param selfApproachRadius    <b>自贴近半径</b>（格；0 = 只排除自己节点）——
     *                              legacy {@code SELF_APPROACH_RADIUS=2} 同义。
     *                              迈步前若目标格【半径内】有本河已走过的格且不在最近
     *                              若干步内 ⇒ 判定"河贴着自己绕" ⇒ 就地终止
     *                              （用户判据「反复和其他分支交叉/重叠」的对症判据）。
     * @param selfApproachExempt    自贴近豁免步数（正常曲率/紧密河曲不算违规）。
     */
    public record Params(int minSourceSpacingCells, double minSourceAccum,
                         int joinWindow, double meanderAmp, double meanderWavelength,
                         int maxSources, int minNodes, int selfApproachRadius,
                         int selfApproachExempt) {
        /**
         * 默认参数 = legacy 三项机制【开启】的等价换算（2026-10-10）。
         *
         * <p>⚠ 尺度：本类 STEP=4 block，legacy `SKELETON_CELL_BLOCKS=8` ⇒
         * 本类 1 格 = legacy 半格 ⇒ legacy 格数阈值须【×2】：
         * {@code minNodes}=3×2=6、{@code selfApproachRadius}=2×2=4、
         * {@code selfApproachExempt}=6×2=12。</p>
         */
        public static Params defaults() {
            return new Params(6, 0.0, 2, 6.0, 24.0, 0, 6, 4, 12);
        }
    }

    /** 河段终止类型（显式，不假装有水）。 */
    public enum End {
        /** 入湖：下一格是湖水面。 */
        MOUTH_LAKE,
        /** 入海。 */
        MOUTH_SEA,
        /** 并入已有河段（树合并）。 */
        JOIN,
        /** 走到未决格（窗口边界/未收敛）——显式记录，不算"水没了"。 */
        UNRESOLVED,
        /**
         * 走出本区窗口、仍在下行 ⇒ 由跨区接力继续（<b>合法终点</b>）。
         *
         * <p>用户硬约束是"河流只能入湖/入海结束"。窗口是人为切分，
         * 走出窗口不等于河流结束 —— 必须与 {@link #DEAD_END} 区分。</p>
         */
        BOUNDARY_EXIT,
        /** 走到 parent=-1 且非海非湖（理论不应发生，防御）。 */
        DEAD_END
    }

    /** 一条河段：格索引序列（含首尾）+ 终止类型。 */
    public static final class Trace {
        public final int[] cell;
        public final End end;
        /** 是否由溢出口起新段（湖后段）。 */
        public final boolean fromOutlet;
        /**
         * 窗口外续流的<b>世界坐标</b>尾段（block，交替 x,z）。
         *
         * <p>{@code cell[]} 只能表达本窗口格索引，窗外格没有索引。早先版本走出窗口后
         * 直接丢弃路径 ⇒ 河"出了窗口却没画" ⇒ 实测河格 −14.5%。这段必须在 {@code shape}
         * 里接到折线尾部，窗外那段才会被画出来。</p>
         */
        public final double[] outsideTail;

        /**
         * ★★★ <b>连续路径</b>（格坐标，非整数）：交替 x,z。
         *
         * <p><b>为什么需要</b>（用户 2026-10-21）：
         * 「骨架落地时还是 D8 落点？我让你做多条件物理运行向量就是让你落地不再是那八个方向，
         * 而是拿那个算出来的结果向量的方向。并且我也说过路线应该像柏林噪声的生成原理那样，
         * 进行向量运动线。」</p>
         *
         * <p>{@code cell[]} 只能是格索引（8 邻之一）⇒ 画出来的线永远落在 8 个方向上。
         * 本数组记录<b>每步的真实位置</b>（当前点 + 合成方向 × 步长，单位=格），
         * 是浮点连续量 ⇒ {@code shape()} 直接用它，不再经格心。</p>
         *
         * <p>为空时（旧路径/外部构造）退回 {@code cell[]} 的格心。</p>
         */
        public final double[] contPath;

        Trace(int[] cell, End end, boolean fromOutlet) {
            this(cell, end, fromOutlet, new double[0], new double[0]);
        }

        Trace(int[] cell, End end, boolean fromOutlet, double[] outsideTail) {
            this(cell, end, fromOutlet, outsideTail, new double[0]);
        }

        Trace(int[] cell, End end, boolean fromOutlet, double[] outsideTail, double[] contPath) {
            this.cell = cell;
            this.end = end;
            this.fromOutlet = fromOutlet;
            this.outsideTail = outsideTail;
            this.contPath = contPath;
        }

        public boolean hasOutsideTail() { return outsideTail.length >= 4; }

        /** 是否有连续路径可用（长度与 cell 对齐 ×2）。 */
        public boolean hasContPath() { return contPath.length >= cell.length * 2 && cell.length > 1; }

        public int nodeCount() { return cell.length; }
        public int sourceCell() { return cell[0]; }
        public int mouthCell() { return cell[cell.length - 1]; }
    }

    /**
     * 出口种子（legacy {@code RiverLineRegion.OutletSeed} 同构，2026-10-10 双 pass 续流）。
     *
     * <p>链走到本窗口<b>边框</b>（DEAD_END 且 mouth 落在 frame 上）⇒ 记下
     * 「水从哪条边离开、朝哪个邻区流」。pass-2 时接收方把它当**强制续流源**，
     * 用【接收方自己的 {@code parent} 树】往里追 —— 方向场全程统一。
     * （v1 让发送方沿窗外地形外走是反的：地形最陡下降 ≠ minimax 树，两边接不上。）</p>
     *
     * @param cell 本窗口格索引（<b>非</b>世界坐标；调用方用 origin+step 换算）
     * @param dRX  流出方向 X（-1 西框 / +1 东框；0 = 不跨 X）
     * @param dRZ  流出方向 Z（-1 北框 / +1 南框；0 = 不跨 Z）
     */
    public record ExitSeed(int cell, int dRX, int dRZ) { }

    // #region agent log: R5 incoming 逐入口归因，实机确认后清理
    record IncomingDiagnostic(int start, String status, int nodes, int mouth, End end) { }
    // #endregion

    /**
     * 窗口外续流：{@code parent=-1} 且该格不是湖/海时，用世界坐标地形下降继续追。
     *
     * <p><b>为什么需要</b>：{@code parent[]} 是本窗口 minimax 树的产物，出窗即 -1。
     * 生产 9 region 普查：58/262 条链 DEAD_END，归因 100% 是 WINDOW_FRAME，
     * 其中 41 条邻区已正确发出接力种子、方向也对 ⇒ 断点在"接力链自己也跑到边界又撞上
     * parent=-1"（接力只继承一级，而河要穿多区）。正解：窗外地形仍可采样 ⇒
     * 沿最陡下降继续追，直到入海/入湖/步数耗尽。等价旧版 margin 效果，但不扩求解域。</p>
     *
     * <p>{@code outside == null} 时行为与旧版<b>逐位一致</b>。</p>
     */
    public interface OutsideTerrain {
        /** @return 该世界坐标的高程；非有限值表示不可采样。 */
        double height(int blockX, int blockZ);
    }

    /** 窗口外续流的参数与采样器。 */
    public record OutsideContinuation(OutsideTerrain terrain, int originX, int originZ,
                                      int step, int maxSteps, double seaLevel) { }

    /** 窗口外续流的结果：追加的格路径 + 世界坐标尾段 + 终止类型。 */
    private record Continuation(int[] path, double[] outsideTail, End end) { }

    /** 结果：河段表（确定性顺序）+ 诊断计数 + 出口种子。 */
    public static final class Result {
        public final List<Trace> traces;
        public final int sourcesTotal;
        public final int sourcesDroppedBySpacing;
        public final int outletStarts;
        /** ★ 接力种子实际起链数（诊断：判定双 pass 是否真的接到邻区的水）。 */
        public final int incomingStarts;
        /**
         * ★ 出口种子（2026-10-10 双 pass 续流）：走到窗口边框的链尾。
         * 交邻区 pass-2 作强制续流源。⚠ 只从 pass-1（{@code incoming} 为空）产出，
         * 与 legacy 相同 ⇒ 传递恰好一级、顺序无关、无递归。
         */
        public final List<ExitSeed> exitSeeds;
        /**
         * ★ 网络连通性过滤【本次是否启用】+ 它【删了几条链】。
         *
         * <p><b>为什么必须回传</b>：过滤会整簇删除"不接水体"的链 —— 拿它的产出当
         * 连通性证据 = 用删除掩盖失败（分层实验的失败规范禁止）。调用方与探针必须能
         * 区分「骨架几何完整」与「已被过滤」；后者<b>不得</b>判 PASS。</p>
         */
        public final boolean networkFilterEnabled;
        public final int droppedByNetworkFilter;
        List<IncomingDiagnostic> incomingDiagnostics = List.of();

        Result(List<Trace> traces, int sourcesTotal, int sourcesDroppedBySpacing,
               int outletStarts, int incomingStarts, List<ExitSeed> exitSeeds,
               boolean networkFilterEnabled, int droppedByNetworkFilter) {
            this.traces = traces;
            this.sourcesTotal = sourcesTotal;
            this.sourcesDroppedBySpacing = sourcesDroppedBySpacing;
            this.outletStarts = outletStarts;
            this.incomingStarts = incomingStarts;
            this.exitSeeds = exitSeeds;
            this.networkFilterEnabled = networkFilterEnabled;
            this.droppedByNetworkFilter = droppedByNetworkFilter;
        }
    }

    /**
     * 亚格几何折线（block 坐标，形态收尾后）。{@code half}/{@code depth} 恒为
     * 最小可见值 —— 宽度是后续议题（用户方针）。
     */
    public static final class Shaped {
        public final double[] x;
        public final double[] z;

        Shaped(double[] x, double[] z) {
            this.x = x;
            this.z = z;
        }

        public int size() { return x.length; }
    }

    /**
     * meander 倍频的波长下限（block）。1-block 重采样下，波长 &lt;6 块的倍频会
     * 每 ~3 个节点翻转方向 ⇒ 高频打卷（用户实测"河流打转"）—— 截断它。
     */
    private static final double MIN_MEANDER_WAVELENGTH = 6.0;

    /** 平地破平局的邻居扫描序（与 {@code EquilibriumWater.DIRS8} 同序 ⇒ 确定性）。 */
    private static final int[][] EQUAL_DIRS8 = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1},
            {1, 1}, {1, -1}, {-1, 1}, {-1, -1}
    };

    /**
     * 从窗口内一个 {@code parent=-1} 的格出发，用世界坐标地形最陡下降续追到水体。
     *
     * <p>终止条件严格限制为「入海 / 入湖 / 步数耗尽 / 无严格下降」。前两者是合法终点；
     * 后两者返回 {@code null}，由调用方如实记 {@code DEAD_END}——绝不伪造终点。</p>
     */
    private static Continuation continueOutside(int startCell, double[] level,
                                                boolean[] lakeMask, boolean[] sea,
                                                int nx, int nz, OutsideContinuation ctx) {
        int sx = startCell / nz, sz = startCell % nz;
        int step = ctx.step();
        int bx = ctx.originX() + sx * step;
        int bz = ctx.originZ() + sz * step;
        var path = new ArrayList<Integer>();
        var tail = new ArrayList<Double>();
        double here = ctx.terrain().height(bx, bz);
        if (!Double.isFinite(here)) return null;
        var seen = new java.util.HashSet<Long>();
        seen.add(((long) bx << 32) ^ (bz & 0xffffffffL));
        for (int i = 0; i < ctx.maxSteps(); i++) {
            int bestX = bx, bestZ = bz;
            double best = here;
            for (int[] d : EQUAL_DIRS8) {
                int cx = bx + d[0] * step, cz = bz + d[1] * step;
                long key = ((long) cx << 32) ^ (cz & 0xffffffffL);
                if (seen.contains(key)) continue;
                double h = ctx.terrain().height(cx, cz);
                if (!Double.isFinite(h)) continue;
                if (h < best - 1e-9) { best = h; bestX = cx; bestZ = cz; }
            }
            if (bestX == bx && bestZ == bz) return null;
            bx = bestX; bz = bestZ; here = best;
            seen.add(((long) bx << 32) ^ (bz & 0xffffffffL));
            int gi = Math.floorDiv(bx - ctx.originX(), step);
            int gj = Math.floorDiv(bz - ctx.originZ(), step);
            boolean inWindow = gi >= 0 && gj >= 0 && gi < nx && gj < nz;
            if (inWindow) {
                int cell = gi * nz + gj;
                if (sea[cell]) { path.add(cell); return new Continuation(toIntArray(path), toDoubleArray(tail), End.MOUTH_SEA); }
                if (lakeMask[cell]) { path.add(cell); return new Continuation(toIntArray(path), toDoubleArray(tail), End.MOUTH_LAKE); }
                path.add(cell);
            } else {
                tail.add((double) bx); tail.add((double) bz);
                if (here < ctx.seaLevel())
                    return new Continuation(toIntArray(path), toDoubleArray(tail), End.MOUTH_SEA);
            }
        }
        return null;
    }

    private static int[] toIntArray(List<Integer> list) {
        int[] out = new int[list.size()];
        for (int i = 0; i < out.length; i++) out[i] = list.get(i);
        return out;
    }

    private static double[] toDoubleArray(List<Double> list) {
        double[] out = new double[list.size()];
        for (int i = 0; i < out.length; i++) out[i] = list.get(i);
        return out;
    }

    /**
     * 严格 D8 单步：返回 8 邻中 {@code bed} <b>严格最低</b>的格；无更低者返回 {@code -1}。
     *
     * <p><b>纯函数</b>（不读写任何外部状态），便于探针逐点断言"零爬坡"。
     * 平局按 {@link #EQUAL_DIRS8} 固定扫描序取先到者 ⇒ 确定性。</p>
     *
     * <p>不在此处判断湖/海/covered —— 那些是<b>终止条件</b>，由调用方在迈步后判定，
     * 保持本函数只回答"地形上水往哪走"。</p>
     */
    static int strictD8Next(int k, double[] bed, int nx, int nz) {
        int bi = k / nz, bj = k % nz;
        int best = -1;
        double bestBed = bed[k];
        for (int[] d : EQUAL_DIRS8) {
            int a = bi + d[0], b = bj + d[1];
            if (a < 0 || b < 0 || a >= nx || b >= nz) continue;
            int c = a * nz + b;
            if (bed[c] < bestBed - 1e-9) { bestBed = bed[c]; best = c; }
        }
        return best;
    }

    /**
     * ★★★ 阶段 B：带<b>惯性</b>的严格 D8 单步（物理向量机制的正面落地）★★★
     *
     * <p><b>要治的缺陷</b>：{@link #strictD8Next} 在<b>平局</b>时按 {@code EQUAL_DIRS8}
     * 固定扫描序取先到者。缓坡上 8 邻的 bed 差常小于 1e-9（甚至完全相等），
     * 此时方向与地形<b>无关</b>，只由扫描序决定 ⇒ 相邻格各选各的 ⇒
     * 实测表现为「高频抖动、锯齿、平行带」。这正是与物理动线差异的主因。</p>
     *
     * <p><b>与物理粒子的对应</b>（{@code ErosionEngine.simulateDrop}）：
     * {@code dir = dir·INERTIA − ∇h·(1−INERTIA)} —— 一阶递推，状态只有「上一步方向」。
     * 一阶状态可随链携带 ⇒ <b>仍是纯局部计算，不引入窗口依赖</b>
     * （这正是"柏林噪声式纯函数"在路线机制上的对应物）。</p>
     *
     * <p><b>为什么不是替换 D8</b>（方案 §5 阶段 B）：
     * 候选集仍限 8 邻 ⇒ 保持 D8 无环性与确定性；
     * 惯性只用于在候选里选，<b>不引入新的可达格</b> ⇒ 汇流拓扑（{@code parent[]}）不受影响。</p>
     *
     * <p><b>硬约束（回归即失败）</b>：{@code bed} 严格下降优先于惯性偏好。
     * 即：只要存在严格更低的邻居，就只在「严格更低的那些」里按惯性挑；
     * 惯性<b>绝不</b>把路线拉向更高或等高的邻居。</p>
     *
     * @param inDirX 上一步方向的单位向量 x 分量（首步传 0）
     * @param inDirZ 上一步方向的单位向量 z 分量（首步传 0）
     * @return 下一格；{@code -1} 表示无严格更低邻居（交由溢流/终止逻辑）
     */
    static int strictD8NextInertial(int k, double[] bed, int nx, int nz,
                                    double inDirX, double inDirZ) {        int bi = k / nz, bj = k % nz;
        // ① 先求出「严格最低」的 bed 值（与 strictD8Next 同判据，保证零爬坡）
        double lowest = bed[k];
        for (int[] d : EQUAL_DIRS8) {
            int a = bi + d[0], b = bj + d[1];
            if (a < 0 || b < 0 || a >= nx || b >= nz) continue;
            double h = bed[a * nz + b];
            if (h < lowest - 1e-9) lowest = h;
        }
        if (!(lowest < bed[k] - 1e-9)) return -1;          // 无严格更低 ⇒ 零爬坡硬约束

        // ② 在「与最低值几乎相等」的候选里（即平局集），按惯性方向择优
        //
        //   容差用 1e-9：与 strictD8Next 的比较阈值一致 ⇒ 判定口径不变。
        //   若无惯性（首步）或平局集只有一个，行为退化为扫描序取先到者（与旧版逐位相同）。
        double tieTol = 1e-9;
        int best = -1;
        double bestScore = Double.NEGATIVE_INFINITY;
        boolean hasInertia = (inDirX != 0 || inDirZ != 0);
        for (int[] d : EQUAL_DIRS8) {
            int a = bi + d[0], b = bj + d[1];
            if (a < 0 || b < 0 || a >= nx || b >= nz) continue;
            int c = a * nz + b;
            if (bed[c] > lowest + tieTol) continue;        // 不在平局集（含严格最低者）
            double score;
            if (hasInertia) {
                // 步进方向与该方向的单位向量点积 ⇒ 越顺着惯性越高分
                double len = Math.hypot(d[0], d[1]);
                score = (d[0] * inDirX + d[1] * inDirZ) / len;
                // ★ 极小的 bed 优先权：平局集内若某方向确实更低（差 > 1e-12），
                //   仍应优先下降。用 bed 差做微调项，且量级远小于方向分（≤1）。
                score += (lowest - bed[c]) * 1e-3;
            } else {
                score = 0;                                 // 首步：全部并列 ⇒ 扫描序取先到者
            }
            if (score > bestScore + 1e-12) { bestScore = score; best = c; }
        }
        return best;
    }

    /**
     * ★★★ <b>D8 向量化升级：多条件加权合成一个连续方向</b> ★★★
     *
     * <p><b>用户口径</b>（2026-10-21，本轮核心指令）：</p>
     * <blockquote>
     * 「D8 不是在一个位置点的八个方向找最近下降的位置点吗？那找点不再是只找最近下降点
     * 这一个条件的方向，而是变成向量化，有方向有力度。找惯性、最近下降等等多条件的
     * 总结果向量，就类似噪声生成原理那样弄向量连续。」
     * </blockquote>
     *
     * <h3>与旧实现（{@link #strictD8Next}）的本质区别</h3>
     * <table border="1">
     *   <tr><th>旧</th><th>新</th></tr>
     *   <tr>
     *     <td>8 个候选里<b>挑一个</b>（argmin）⇒ 方向只能是 45° 的倍数</td>
     *     <td>8 个方向各自算一个<b>带力度的贡献向量</b>，<b>求和</b>得合成方向
     *         ⇒ 方向连续（任意角度）</td>
     *   </tr>
     *   <tr>
     *     <td>只有「谁最低」一个条件</td>
     *     <td>四个条件同时作用：坡降、惯性、落差、贴近度</td>
     *   </tr>
     * </table>
     *
     * <h3>合成公式（每个方向 d 一个标量贡献，再乘单位向量累加）</h3>
     * <pre>
     *   for each d ∈ 8 邻 (且 bed(c) ≤ bed(k)，不往高处走):
     *       drop   = (bed(k) − bed(c)) / stepMax      // 坡降力度（0..1）
     *       grav   = max(0, d̂ · ĝ_down)              // 与连续坡向的贴合（-1..1，负则截 0）
     *       keep   = max(0, d̂ · dirPrev)             // 与上一步方向的一致度
     *       prox   = 1 / (1 + |d|)                   // 正交方向优先（对角不会被系统性偏爱）
     *       w      = W_DROP·drop + W_DOWN·grav + W_KEEP·keep + W_PROX·prox
     *       V     += d̂ · w                            // ★ 向量求和（不是取最大值）
     *
     *   若 |V| < ε（完全相互抵消）⇒ 退回 argmin（保证不死锁）
     *   方向 = V / |V|   ⇒ 连续
     * </pre>
     *
     * <h3>为什么这样能得到"向量连续"（用户说的噪声原理）</h3>
     * <p>柏林噪声之所以平滑，是因为它在每个点对<b>多个梯度向量加权求和</b>，
     * 而不是在离散候选里挑一个。本函数对 8 个方向做同样的事：
     * 相邻格的地形/惯性输入是连续变化的 ⇒ 合成向量也连续变化
     * ⇒ 走出来的折线方向连续，不再有 45° 台阶。</p>
     *
     * <h3>硬约束</h3>
     * <ul>
     *   <li><b>bed(c) ≤ bed(k)</b>：不往高处走（河流的物理下限；允许相等）；</li>
     *   <li><b>确定性</b>：只依赖 bed 数组 + 上一步方向，无随机、无窗口外信息；</li>
     *   <li><b>不死锁</b>：合成向量退化时退回 argmin。</li>
     * </ul>
     *
     * @param dirX 上一步方向单位向量 x（首步 0）
     * @param dirZ 上一步方向单位向量 z（首步 0）
     * @param span 梯度跨度（格）
     * @return 下一格；-1 = 无「不上升」的邻居
     */
    static StepResult vectorTraceNext(int k, double[] bed, int nx, int nz,
                               double dirX, double dirZ, int span) {
        int bi = k / nz, bj = k % nz;
        double here = bed[k];

        // ① 连续坡向（跨 span 格中心差分，抑制单格噪声）
        int s = Math.max(1, span);
        int xp = Math.min(nx - 1, bi + s), xm = Math.max(0, bi - s);
        int zp = Math.min(nz - 1, bj + s), zm = Math.max(0, bj - s);
        double gx = (bed[xp * nz + bj] - bed[xm * nz + bj]) / Math.max(1, xp - xm);
        double gz = (bed[bi * nz + zp] - bed[bi * nz + zm]) / Math.max(1, zp - zm);
        double gl = Math.hypot(gx, gz);
        double downX = gl > 1e-12 ? -gx / gl : 0;
        double downZ = gl > 1e-12 ? -gz / gl : 0;
        boolean hasPrev = (dirX != 0 || dirZ != 0);

        // ② 8 个方向的带力度贡献求和
        double vx = 0, vz = 0;
        double dropMax = 0;
        // 先求最大落差（用于归一化，让"坡降力度"与格距无关）
        for (int[] d : EQUAL_DIRS8) {
            int a = bi + d[0], b = bj + d[1];
            if (a < 0 || b < 0 || a >= nx || b >= nz) continue;
            int c = a * nz + b;
            if (bed[c] > here + 1e-9) continue;
            dropMax = Math.max(dropMax, here - bed[c]);
        }
        for (int[] d : EQUAL_DIRS8) {
            int a = bi + d[0], b = bj + d[1];
            if (a < 0 || b < 0 || a >= nx || b >= nz) continue;
            int c = a * nz + b;
            if (bed[c] > here + 1e-9) continue;              // 不往高处走

            double len = Math.hypot(d[0], d[1]);
            double ux = d[0] / len, uz = d[1] / len;

            double drop = dropMax > 1e-12 ? Math.max(0, here - bed[c]) / dropMax : 0;
            double grav = Math.max(0, ux * downX + uz * downZ);
            double keep = hasPrev ? Math.max(0, ux * dirX + uz * dirZ) : 0;

            // ★ 2026-10-21 移除 W_PROX。
            //   旧写法 `W_PROX * (1/len)` 给正交方向恒定加成（1.0 vs 0.707）
            //   ⇒ 系统性偏爱东/南/西/北 ⇒ 图上大量【竖直直线与 90° 直角】。
            //   对角步长"每步降得多"的问题改由【落格阶段按实际位移归一化】处理：
            //   见下方对 dot 除以候选的**实际世界位移长度**，而不是方向单位向量。
            double w = W_DROP * drop + W_DOWN * grav + W_KEEP * keep;
            // 乘以实际位移长度 ⇒ 贡献向量代表"这一步真实推动了多少距离"
            vx += ux * len * w;
            vz += uz * len * w;
        }

        // ③ 合成向量长度太小（完全抵消）⇒ 退回 argmin，保证不死锁
        double vlen = Math.hypot(vx, vz);
        if (vlen < 1e-9) {
            int fb = strictD8Next(k, bed, nx, nz);
            return fb < 0 ? StepResult.NONE : new StepResult(fb, 0, 0);
        }
        double dirOutX = vx / vlen, dirOutZ = vz / vlen;

        // ④ 落格：沿合成方向前进一个格距（step），取最近的格
        //
        //   ★ 不再"在 8 个方向里挑最贴合的" —— 那会在方向变化时跳档 ⇒ 90° 直角。
        //   改为：目标点 = 当前点 + 合成方向 × step，再取离目标最近的**可下降**格。
        //   这样方向连续变化时，落格也连续变化（只会相差 1 格）。
        int best = -1;
        double bestDist = Double.POSITIVE_INFINITY;
        // 目标点（格坐标，浮点）
        double tx = bi + dirOutX, tz = bj + dirOutZ;
        for (int[] d : EQUAL_DIRS8) {
            int a = bi + d[0], b = bj + d[1];
            if (a < 0 || b < 0 || a >= nx || b >= nz) continue;
            int c = a * nz + b;
            if (bed[c] > here + 1e-9) continue;
            // ★ 2026-10-21【不得禁止"朝后走"】—— 用户：「你又忘记牛轭湖了？」
            //   牛轭湖的成因正是河流弯回自己身边（曲流→裁弯取直→留下弧形成湖）。
            //   任何"候选必须朝前(夹角≤90°)"的约束都会把这种弯强行拉直，
            //   等于人为消灭牛轭湖。故此处只看【到目标点的距离】，不限制方向。
            //   ⚠ 真正防打死的机制在调用方：`covered[]` 已走过的格会被 nearbyJoin 并入，
            //     不会无限绕圈；循环由"是否重复进入同一格"判定，不由方向约束判定。
            double dist = Math.hypot(a - tx, b - tz);
            if (dist < bestDist - 1e-12
                    || (Math.abs(dist - bestDist) <= 1e-12 && (best < 0 || c < best))) {
                bestDist = dist;
                best = c;
            }
        }
        return new StepResult(best, dirOutX, dirOutZ);
    }

    /**
     * 向量追踪的一步结果：<b>落格</b> + <b>连续方向</b>。
     *
     * <p>用户要求（2026-10-21）：「落地不再是那八个方向，而是拿那个算出来的结果向量的方向。」
     * ⇒ 必须把合成方向一并返回，让调用方用它推进<b>连续坐标</b>，
     * 而不是只用 {@code cell}（那只表达 8 邻之一）。</p>
     *
     * @param cell 落到的格索引（-1 = 无解）
     * @param dirX 合成方向单位向量 x（连续）
     * @param dirZ 合成方向单位向量 z（连续）
     */
    record StepResult(int cell, double dirX, double dirZ) {
        static final StepResult NONE = new StepResult(-1, 0, 0);
    }

    /** 坡降权重（地形吸引）。 */
    static final double W_DROP = Double.parseDouble(
            System.getProperty("geogenesis.wDrop", "0.35"));
    /** 连续坡向贴合权重。 */
    static final double W_DOWN = Double.parseDouble(
            System.getProperty("geogenesis.wDown", "0.55"));
    /** 惯性权重（上一步方向延续）。 */
    static final double W_KEEP = Double.parseDouble(
            System.getProperty("geogenesis.wKeep", "0.50"));
    /** 正交优先权重（抵消对角步长优势，防 45° 偏爱）。 */
    static final double W_PROX = Double.parseDouble(
            System.getProperty("geogenesis.wProx", "0.25"));

    /** 向量化追踪开关（默认开）。 */
    static final boolean VECTOR_TRACE =
            !"false".equals(System.getProperty("geogenesis.vectorTrace"));

    /**
     * 洼地溢流口：从洼地底 {@code start} 起做有界 priority-flood（按 {@code bed} 由低到高），
     * 返回第一个"外侧存在严格更低邻居"的格（水能从此继续下降的鞍部）。
     *
     * <p><b>为什么必须有</b>：严格 D8 走进局部洼地后无更低邻居。真实河流在该处
     * <b>积成小湖</b>，水位涨到鞍部后从溢出口继续下行 —— 这正是 priority-flood 语义。
     * 没有它，严格 D8 会让大量链死在地形噪声造成的微洼里。</p>
     *
     * @param visited 已走过的格（避免原地打转），不会被修改
     * @return 溢出口格索引；{@code -1} 表示有界搜索内无溢出口
     */
    static int spillCell(int start, double[] bed, int nx, int nz, java.util.Set<Integer> visited) {
        record Node(int cell, double bed) { }
        var pq = new java.util.PriorityQueue<Node>(java.util.Comparator.comparingDouble(Node::bed));
        var seen = new java.util.HashSet<Integer>();
        pq.add(new Node(start, bed[start]));
        seen.add(start);
        int budget = 4096;
        while (!pq.isEmpty() && budget-- > 0) {
            Node n = pq.remove();
            if (n.cell() != start) {
                int nxt = strictD8Next(n.cell(), bed, nx, nz);
                if (nxt >= 0 && !visited.contains(nxt) && !seen.contains(nxt)) return n.cell();
            }
            int bi = n.cell() / nz, bj = n.cell() % nz;
            for (int[] d : EQUAL_DIRS8) {
                int a = bi + d[0], b = bj + d[1];
                if (a < 0 || b < 0 || a >= nx || b >= nz) continue;
                int c = a * nz + b;
                if (seen.add(c)) pq.add(new Node(c, bed[c]));
            }
        }
        return -1;
    }

    /**
     * 有界粒子最大偏移（× step）。legacy {@code PARTICLE_MAX_DEV_FRAC=0.45}，
     * 我方实测 0.45 在 1-block 扫掠半径下让相邻段互相贴近 ⇒ 默认 0.25。
     */
    static final double PARTICLE_AMP_FRAC =
            Double.parseDouble(System.getProperty("geogenesis.particleAmp", "0.25"));

    /**
     * ★★★ 严格 D8 拓扑开关（`-Dgeogenesis.strictD8=false` 回到 minimax parent 链）★★★
     *
     * <p><b>用户指令（2026-10-21）</b>：「肯定不能爬坡啊，我们做的是河流」。
     * 五方案实测（{@code FlowMechanismABProbe}）：minimax {@code flood()} 只保证
     * {@code level} 非升，实测 {@code bed} 爬坡 115 步（5.68%、单步最大 14.53 块）、
     * 等高 276 步（13.64%）⇒ 河会翻过比起点更高的地形，物理上不成立。</p>
     *
     * <p><b>做法</b>：追踪时不用 {@code parent[]}（minimax 瓶颈优化的产物，
     * 目标是最小化"最高点"而非"当前下降最快"），改为每一步取 8 邻中
     * <b>{@code bed} 严格最低</b>者 ⇒ 单步高差恒为负 ⇒ 爬坡 0。
     * 洼地无更低邻居时按 priority-flood 找鞍部溢流（见 {@link #spillCell}），
     * 对应"洼地蓄水 → 最低溢口 → 继续下泄"。</p>
     *
     * <p>默认开（用户指令）。关掉即逐位回到旧的 parent 链行为。</p>
     */
    static final boolean STRICT_D8 =
            !"false".equals(System.getProperty("geogenesis.strictD8"));

    /**
     * ★★★ 阶段 B 开关：严格 D8 平局时按<b>惯性</b>择优（`-Dgeogenesis.inertialD8=false` 关）★★★
     *
     * <p><b>要治什么</b>：{@code strictD8Next} 在平局（8 邻 bed 差 < 1e-9，缓坡上极常见）
     * 时按 {@code EQUAL_DIRS8} 固定扫描序取先到者 ⇒ 方向与地形无关、相邻格各选各的
     * ⇒ 实测的「高频抖动 / 锯齿 / 平行带」。这是与物理动线差异的主因。</p>
     *
     * <p><b>与粒子的一次阶递推对应</b>：{@code dir = dir·INERTIA − ∇h·(1−INERTIA)}。
     * 状态只有「上一步方向」⇒ 可随链携带 ⇒ <b>仍是纯局部计算</b>。</p>
     *
     * <p><b>不破坏的硬约束</b>：候选仍限 8 邻（D8 无环、确定性不变）；
     * {@code bed} 严格下降优先（惯性绝不把路线拉向更高/等高）。</p>
     *
     * <p>默认开。关掉即逐位回到扫描序平局（阶段 B 前的行为）。</p>
     */
    static final boolean INERTIAL_D8 =
            "true".equals(System.getProperty("geogenesis.inertialD8"));

    /**
     * ★★★ <b>物理向量方向场</b>（`-Dgeogenesis.gradientDir=false` 关）★★★
     *
     * <p><b>用户口径</b>（2026-10-21，多次重申）：
     * 「明明河流就按梯度走 + 我们的物理向量就好了。天然形成树状靠的是地形，不是你人为拉动。」
     * 「我之前提出了一个运动向量机制，怎么不行吗？」</p>
     *
     * <h3>要治什么</h3>
     * <p>严格 D8 的选向是 <b>8 邻里取 bed 最低</b>。在缓坡（实测坡度 0.019）上，
     * 2 wu 间距的高差只有 <b>0.037</b>，再经 {@code VALLEY_MIN} 四角取最小进一步抹平
     * ⇒ 8 个候选的差落在 1e-9 量级 ⇒ 判据退化为「谁先被扫到」
     * ⇒ 骨架走成<b>严格竖直/45° 直线</b>（route 40 实测 x 恒为 -415 共 140+ 步）。</p>
     *
     * <h3>做法</h3>
     * <p>用<b>多点中心差分</b>估出该格的真实坡向（连续向量），
     * 再在 8 个 D8 候选里选<b>与坡向点积最大</b>的那个：</p>
     * <pre>
     *   gx = bed[i+1,j] − bed[i−1,j]      (跨 ±1 格，抑制单格噪声)
     *   gz = bed[i,j+1] − bed[i,j−1]
     *   dirDown = (−gx, −gz) / |·|
     *   score(候选d) = (d · dirDown) / |d|      ← 越顺坡越高分
     * </pre>
     *
     * <h3>为什么不破坏硬约束</h3>
     * <ul>
     *   <li><b>零爬坡</b>：只在「严格更低」的候选里选（{@link #strictD8Next} 的同一判据），
     *       若没有任何严格更低者 ⇒ 仍返回 -1，交由洼地/终点逻辑；</li>
     *   <li><b>无环</b>：每步 bed 严格下降 ⇒ 不可能成环；</li>
     *   <li><b>确定性</b>：只依赖 bed 数组与格索引，无随机、无窗口外信息。</li>
     * </ul>
     *
     * <p>与 {@link #INERTIAL_D8} 的区别：惯性用的是<b>上一步方向</b>（历史依赖），
     * 本开关用的是<b>当前点的地形梯度</b>（纯空间）——后者才是"按梯度走"。</p>
     */
    static final boolean GRADIENT_DIR =
            "true".equals(System.getProperty("geogenesis.gradientDir"));

    /**
     * 坡度判据用的梯度跨度（格）。1 = 相邻格差分（噪声大）；2 = 跨两格（更稳）。
     *
     * <p>缓坡上单格高差仅 ~0.037，单格差分会把浮点噪声当坡向 ⇒ 必须跨格。
     * 实测取 2 时缓坡方向稳定性明显改善。</p>
     */
    static final int GRADIENT_SPAN =
            Integer.getInteger("geogenesis.gradientSpan", 2);

    /**
     * ★★★ 阶段 C 开关：<b>连续锚点</b>（`-Dgeogenesis.continuousAnchor=false` 关）★★★
     *
     * <p><b>要治什么</b>（节点图实测，2026-10-21）：</p>
     * <pre>
     *   节点坐标 x%4 / z%4 = 0 的占比：4676/4676 = 100%
     *   段内步向量种类：只有 {0,±4}×{0,±4} 的 8 档（+两格连走）
     * </pre>
     * <p>即<b>所有节点都被量化到 STEP 网格上</b> ⇒ 路线只可能是 45°/90° 阶梯
     * （用户：「我们的节点生成位置也许就不太对」）。
     * 物理粒子的方向是连续的（任意角度），骨架只有 8 档 ⇒ 缓坡上方向必然对不上。</p>
     *
     * <p><b>★★★ 2026-10-21 实测结论：本机制<b>对方向一致性无益</b>，故默认<b>关</b> ★★★</b></p>
     *
     * <p>它确实达成了设计目标（节点离开网格）：{@code x%4=0 且 z%4=0} 的节点占比
     * <b>100% → 2.7%</b>，段内步进方向从 8 档铺开到 <b>72/72 个 5° 分箱</b>。
     * <b>但</b>用 {@code DirectionAgreementProbe} 对同一批源头做方向夹角 A/B（只差本开关）：</p>
     * <pre>
     *   指标      A=基线    B=连续锚点    判定
     *   ≤15°      35.6%      33.6%      −2.0  恶化
     *   ≤30°      54.4%      56.4%      +2.0
     *   ≤45°      65.8%      69.8%      +4.0
     *   &gt;90°      20.1%      22.8%      +2.7  恶化（完全反向变多）
     * </pre>
     * <p>即「中间改善、两端变差」，净效果不成立。同时段端点悬空
     * <b>56.4% → 60.6%</b>（JOIN 点离开格心后与共享锚点对不齐）。</p>
     *
     * <p><b>推论</b>：节点是否落在网格上，<b>不是</b>方向错的主因。
     * 真正瓶颈在追踪层——骨架在缓坡上产出「互不汇聚的平行线」，
     * 而物理动线在连续梯度场中被同一谷线吸引 ⇒ 汇聚成树。
     * 方向夹角只是这个结构差异的表征。故本开关保留但不默认启用。</p>
     */
    static final boolean CONTINUOUS_ANCHOR =
            "true".equals(System.getProperty("geogenesis.continuousAnchor"));

    /**
     * 连续锚点的「格心吸引力」（0=完全顺坡，1=完全贴格心）。
     *
     * <p>取 0.30 的依据：每步推进长度是 {@code step}，格心距当前点的距离也在
     * {@code step} 量级；0.30 表示"坡向占七成、格心占三成"。
     * 可用 {@code -Dgeogenesis.anchorStiffness} 调。</p>
     */
    static final double ANCHOR_STIFFNESS =
            Double.parseDouble(System.getProperty("geogenesis.anchorStiffness", "0.30"));

    /**
     * 洼地溢流允许的最大抬升（块）。超过此值即认定<b>真封闭盆地</b>，
     * 不伪造溢出口（{@code -Dgeogenesis.maxSpillRise} 可调）。
     */
    static final double MAX_SPILL_RISE =
            Double.parseDouble(System.getProperty("geogenesis.maxSpillRise", "12.0"));

    /**
     * ★★★ <b>河流洼地续流</b>（`-Dgeogenesis.riverSpill=false` 关）★★★
     *
     * <p><b>2026-10-21 二次修正</b>：我曾把这里改成「洼地直接 `p=-1` 结束」，
     * 理由是用户说「溢流应该是给湖泊用的，不是给河流的」。
     * <b>但实测证明那是错的</b>：真洼地 6398 个，其中被 {@code lakeMask} 认成湖的
     * <b>只有 0 个</b> ⇒ 河走到洼地就 `DEAD_END` ⇒
     * 用户看到「非常多短河、非正常结束」。</p>
     *
     * <p><b>用户澄清</b>：「不是越不越过的问题啊。是我们现在的河流怎么这样了，
     * 明明之前不会有这么多短河。」——即：<b>河流完整性</b>优先于"严格单调"。</p>
     *
     * <p><b>正确理解「溢流属于湖泊」</b>：那是指<b>湖泊水位涨满后的出流</b>
     * 应由 {@code basinOutlet} 起新段；而<b>河流遇到小洼地</b>时，
     * 水会漫过鞍部继续流（这就是自然河流不会在坑里凭空消失的原因）。
     * 两者不是同一件事。</p>
     *
     * <p><b>做法</b>：无严格更低邻居时，用 {@link #spillCell} 找最低鞍部溢出口；
     * 抬升超过 {@link #MAX_SPILL_RISE} 才认为是真封闭盆地（此时才 DEAD_END）。</p>
     *
     * <p><b>已知代价</b>：溢流段会产生少量「沿等高线行走」的步
     * （实测格层爬升 7.6%、等高 2.9%）。用户已知悉，接受。</p>
     */
    static final boolean RIVER_SPILL =
            !"false".equals(System.getProperty("geogenesis.riverSpill"));

    /** 平地破平局开关（`-Dgeogenesis.flatBreak=false` 关；归因"揉团锯齿"用）。 */
    static final boolean FLAT_BREAK =
            !"false".equals(System.getProperty("geogenesis.flatBreak"));

    /**
     * 汇流槽判据开关（{@code -Dgeogenesis.valleyTrough=false} 关；<b>方案 A 的密度补偿</b>）。
     *
     * <p>见 {@code collectSources} 内说明：无侵蚀 bed 下源头数腰斩（37~61 → 12~30），
     * 关掉本判据可让"缓坡上的源头"也起链，补偿密度。
     * 默认开（保留 2026-09-01 确立的"源头应该在山谷中"）。</p>
     */
    static final boolean VALLEY_TROUGH =
            !"false".equals(System.getProperty("geogenesis.valleyTrough"));

    /**
     * ★ 网络级连通性过滤开关（`-Dgeogenesis.networkFilter=false` 关；A/B 归因用）。
     *
     * <p>把 trace 按共享格并查集聚簇，只保留「簇内有链通向海/湖/窗口接力」的簇
     * ⇒ 结构上消除孤立河段（用户判据：不应出现"既不连湖也不入海"的河）。</p>
     */
    static final boolean NETWORK_FILTER =
            !"false".equals(System.getProperty("geogenesis.networkFilter"));

    /**
     * 步骤级交叉守卫开关（`-Dgeogenesis.stepCrossGuard=false` 关；性能 A/B 用）。
     *
     * <p>⚠ 2026-10-10 性能事件：该守卫在<b>每个迈步</b>上线性扫描全部已有线段
     * （{@link #segmentCrossesAny} 是 O(段数)）⇒ 单 region 的复杂度约
     * {@code 链数 × 每链步数 × 段数} ≈ 10⁸~10⁹ 量级 ⇒ 实测 region 求解
     * 611ms → <b>15746ms</b>（26×）。本开关用于量化该开销。</p>
     */
    static final boolean STEP_CROSS_GUARD =
            !"false".equals(System.getProperty("geogenesis.stepCrossGuard"));

    /**
     * 粒子方向惯性（治"揉团锯齿"：skelDump 铁证 = parent 弹出序交替折返，
     * CR 磨成圆钩 ⇒ 揉团）。本锚点偏移方向 = 本地 −∇bed ⊕ 上一锚点偏移 × 本系数
     * （legacy FlowField 动量语义的锚点层等价）。0 = 无惯性。
     */
    static final double MOMENTUM_FRAC =
            Double.parseDouble(System.getProperty("geogenesis.particleMomentum", "0.6"));

    private DeterministicSkeleton() { }

    // ======================================================================
    // ① 沿 minimax 洪泛树追踪确定性骨架
    // ======================================================================

    /**
     * @param level      水面高程（EquilibriumWater.level）
     * @param parent     minimax 洪泛树（EquilibriumWater.parent；-1 = 无下游）
     * @param resolved   可信掩码（EquilibriumWater.resolved）
     * @param lakeMask   真实湖面（LakeWaterBalance.lake）
     * @param sea        海掩码
     * @param discharge  上游面积累积（RiverNetwork.discharge；单位 = 每格径流 × 格数）
     * @param basinOutlet 每盆地溢出口格（LakeWaterBalance.basinOutlet；-1 = 无）
     */
    public static Result build(double[] level, int[] parent, boolean[] resolved,
                               boolean[] lakeMask, boolean[] sea, double[] discharge,
                               int[] basinOutlet, int nx, int nz, Params params) {
        return build(level, parent, resolved, lakeMask, sea, discharge, basinOutlet,
                nx, nz, params, List.of());
    }
    /**
     * 带【跨区续流】的构建（2026-10-10 双 pass，legacy {@code OutletSeed} 同构）。
     *
     * <h3>为什么不是 v1（发送方越窗外走）</h3>
     * <p>v1 让链走到窗口边框的发送方沿【窗外真实地形最陡下降】继续走 96 块。
     * 实测<b>缝上断口 5 → 9 反增</b>：窗外地形场与邻区的 minimax 洪泛树
     * {@code parent[]} 是<b>两套方向场</b>，画出来的线接不上邻区的链，
     * 只在 96 块外留一个新断头。</p>
     *
     * <h3>正解（= legacy 的两趟结构）</h3>
     * <ul>
     *   <li><b>pass-1</b>：本区独立构建（{@code incoming} 为空）⇒ 产出
     *       {@link Result#exitSeeds}「水从哪条边框离开、指向哪个邻区」；</li>
     *   <li><b>pass-2</b>：收集 8 邻 pass-1 中<b>指向本区</b>的种子，转成本区格索引后
     *       作<b>强制续流源</b> —— 由<b>本区自己的 {@code parent}</b> 往里追。
     *       方向场全程一致；pass-1 不依赖任何邻区 ⇒ 无递归、顺序无关。</li>
     * </ul>
     *
     * @param incomingCells 接力种子（<b>本区</b>格索引；调用方负责邻区坐标→本区坐标换算）。
     *                      落在湖/海/已被覆盖的格上时跳过。
     */
    public static Result build(double[] level, int[] parent, boolean[] resolved,
                               boolean[] lakeMask, boolean[] sea, double[] discharge,
                               int[] basinOutlet, int nx, int nz, Params params,
                               List<Integer> incomingCells) {
        return build(level, parent, resolved, lakeMask, sea, discharge, basinOutlet,
                nx, nz, params, incomingCells, null);
    }

    /**
     * 完整入口（含 {@code bed} —— 平地破平局用）。
     *
     * <h3>★★★ 2026-10-10【平地径向直线伪影的追踪侧修复】★★★</h3>
     * <p>用户圈出：两条笔直斜线横穿画面、与河道交叉。定性（非猜测）：
     * minimax 洪泛把【水位全等的平地】的 parent 交给"弹出顺序"决定 ——
     * {@code EquilibriumWater:244} 注释自述「同水位格的 parent = 首个弹出它的邻居
     * ≈ 距种子更近者 ⇒ 单排水洞平地呈径向收敛」—— 即平地流向 =
     * 【指向最近种子的直线族】（径向），与真实地形无关 ⇒ 图上笔直斜线。</p>
     *
     * <p><b>追踪侧破平局（HANDOFF §17.6 既定方案）</b>：沿 parent 走链时，若当前格的
     * 下一步 {@code p} 与【替代候选】（bed 更低的邻居）处于同一等水位连通区
     * （{@code level 相等}），说明 parent 的方向是"弹出序产物"而非地形产物
     * ⇒ 改选【bed 最低的等水位邻居】（真实地形的微小坡度 = 水该走的方向）。
     * 只有真正完全平坦（bed 也全等）才退回 parent。</p>
     *
     * <p>⚠ 单调性不破坏：候选与当前格 level 相等（非升），SkeletonProbe [1] 仍过。</p>
     *
     * @param bed 真实床面（{@code WindowOutlets.build} 的谷底 bed；null = 旧行为）
     */
    public static Result build(double[] level, int[] parent, boolean[] resolved,
                               boolean[] lakeMask, boolean[] sea, double[] discharge,
                               int[] basinOutlet, int nx, int nz, Params params,
                               List<Integer> incomingCells, double[] bed) {
        return build(level, parent, resolved, lakeMask, sea, discharge, basinOutlet,
                nx, nz, params, incomingCells, bed, null);
    }

    /**
     * 完整入口 + <b>网络过滤显式开关</b>（★ 分层实验失败规范要求）。
     *
     * <h3>为什么需要显式开关（而不是只念一句"-D 能关"）</h3>
     * <p>{@link #filterUnconnected} 会整簇删除"不接水体"的链。若调用方（分层实验）只把
     * "过滤开着"记进日志然后继续，就是<b>用删除掩盖"支流接不进下游接口"的失败</b>。</p>
     * <p>⇒ 本重载让调用方在<b>构建时</b>就能关掉它，并把
     * {@link Result#networkFilterEnabled}/{@link Result#droppedByNetworkFilter} 一并回传，
     * 使"证据来自完整几何还是被删过的几何"可判定。</p>
     *
     * @param networkFilter {@code null} = 用类级默认 {@link #NETWORK_FILTER}（旧行为逐位不变）；
     *                      {@code FALSE} = <b>本次不删任何链</b>（分层实验用）；
     *                      {@code TRUE} = 强制开
     */
    public static Result build(double[] level, int[] parent, boolean[] resolved,
                               boolean[] lakeMask, boolean[] sea, double[] discharge,
                               int[] basinOutlet, int nx, int nz, Params params,
                               List<Integer> incomingCells, double[] bed,
                               Boolean networkFilter) {
        return build(level, parent, resolved, lakeMask, sea, discharge, basinOutlet,
                nx, nz, params, incomingCells, bed, networkFilter, null);
    }

    /** 带窗口外续流的构建（见 {@link OutsideContinuation}）。{@code outside == null} 时逐位同旧。 */
    public static Result build(double[] level, int[] parent, boolean[] resolved,
                               boolean[] lakeMask, boolean[] sea, double[] discharge,
                               int[] basinOutlet, int nx, int nz, Params params,
                               List<Integer> incomingCells, double[] bed,
                               Boolean networkFilter, OutsideContinuation outside) {
        int cells = nx * nz;
        // 源头候选：累积达标 + 汇流槽判据 + parent 存在（判据详见类 javadoc ①）
        List<Integer> sources = collectSources(level, parent, resolved, lakeMask, sea,
                discharge, nx, nz, params);
        int sourcesTotal = sources.size();

        // 溢出口：每盆地出口格作"必追踪"新段起点（湖后段）。
        // ★★★ 2026-10-10【必须过成河门限 —— 这是"短碎河流"的真正根因】★★★
        //
        // 【实测铁证】`-Dgeogenesis.logSrc=true` 正典 r=300 多 region：
        //     [HYDRO-SRC] srcAccum=7078.0  traces=48 sources=0 maxQ=218.5
        //     [HYDRO-SRC] srcAccum=7078.0  traces=63 sources=0 maxQ=40.1
        //   —— `sources=0`（源头判据正确筛掉了全部格，因门限 > maxQ）
        //   —— 但 `traces` 仍有 28~71 条 ⇒ **它们全部来自 basinOutlet**。
        //
        // 【机理】原实现无条件接受每个 basinOutlet 作起点。本项目湖占比 5.24%
        //   ⇒ 盆地极多 ⇒ **每个盆地溢口都强制起一条河** ⇒ 湖后遍地短碎河
        //   （正是用户判据「有很多短碎河流」；也让"提高成河门限"看似完全无效 ——
        //     实测 1800→7078 河道数 7291→7351 几乎不变，因为门限根本没管到溢口）。
        //
        // 【legacy 的对照】它的河道【全部】来自 `extractChannelSkeletons`
        //   （纯流量掩码），湖格只是【追踪的终止条件】，**没有"溢口强行起链"这一步**
        //   ⇒ 结构上不可能出现"每个湖都吐一条短河"。
        //
        // 【正解】溢口起链同样要求【集水达标】（discharge ≥ minSourceAccum）
        //   ⇒ 只有真正汇成大水的湖才有出流河；小湖（水量不足）不出河。
        //   ⚠ 这是【有物理依据】的：湖的出流量本就应 ∝ 其汇水面积。
        //   `minSourceAccum=0`（探针默认）⇒ 全部保留 = 旧行为（逐位不变）。
        List<Integer> outlets = new ArrayList<>();
        if (basinOutlet != null) {
            for (int o : basinOutlet) {
                if (o >= 0 && o < cells && !lakeMask[o] && !sea[o] && resolved[o]) {
                    if (params.minSourceAccum() > 0 && discharge[o] < params.minSourceAccum()) {
                        continue;      // 汇水不足 ⇒ 该湖不产生出流河
                    }
                    outlets.add(o);
                }
            }
            outlets.sort(Integer::compare);
        }

        boolean[] covered = new boolean[cells];
        List<Trace> traces = new ArrayList<>();
        int outletStarts = 0;
        final int BUCKET = 16;
        java.util.Map<Long, List<int[]>> occupied = new java.util.HashMap<>();
        int droppedBySpacing = 0;
        int droppedNetwork = 0;      // 网络级连通性过滤移除的链数（诊断）
        // ★★★ 2026-10-10【自并入守卫（legacy selfApproach 同义）】★★★
        //
        // 【缺陷（skelDump 铁证）】poly#74 = (-40,0)…(-40,8) 后【折返】回 (-40,4)。
        //   机理：trace 走到 (-40,8)，nearbyJoin 在窗口里扫到**它自己刚走过的**
        //   (-40,4)（平坦区 level 全等 ⇒ 上一轮的"等高容差"放行）⇒ 提前终止。
        //   ⇒ 链画一半、折返重叠；后继地形没被覆盖 ⇒ 图上 4~13 格断口。
        // 【legacy 的做法】`selfApproach(seen, cur, …)`：迈步前若目标格的 8 邻里
        //   存在【本河已走过的格】且不在最近几步内 ⇒ 判定"贴近自己" ⇒ 终止。
        //   ⇒ 我们补上等价守卫：join 候选不得是本 trace 自己的节点。
        // 【实现】selfStamp[c] == stamp 表示 c 属于当前 trace（每 region 复用一个数组，
        //   solve 之间互不共享 ⇒ 线程安全）。
        ParentForest parentForest = FLAT_BREAK && bed != null ? new ParentForest(parent) : null;
        int[] selfStamp = new int[cells];
        int[] selfStep = new int[cells];        // 自贴近豁免用：格 → 该 trace 内步序号
        int[] stampBox = {0};
        // ★ 交叉守卫用：已画线段集合（格坐标 {ax,ay,bx,by}）——
        //   legacy `allSegments`（`nearbyDownhillNode` 的 `_wouldCrossExistingSegments`）同义。
        java.util.List<int[]> segments = new java.util.ArrayList<>();
        int sourceTraces = 0;
        int incomingStarts = 0;
        List<IncomingDiagnostic> incomingDiag = com.geogenesis.diagnostics.WorldGenProfiler.stutterEnabled()
                ? new ArrayList<>() : null;
        // ★★★ 2026-10-10【双 pass 续流：三段起点，接力种子最高优先】★★★
        //
        // 【为什么接力必须最先追】它是【邻区已存在的干流】延续，不是本区新分支：
        //   若让它排在本区源头之后，本区源头会先把这段谷底占掉（covered），
        //   接力链一落地就 JOIN ⇒ 白追；且 maxSources 上限会被本区源耗掉。
        // 【legacy】`build(rx, rz, handoff=true, incoming)` 把 incoming 作强制续流源。
        // 【口径】接力/溢口段都不计入 maxSources（都是干流延续，不是新分支）。
        List<Integer> incoming = new ArrayList<>(incomingCells == null ? List.of() : incomingCells);
        incoming.sort(Integer::compare);          // 确定性：不依赖邻区种子到达顺序
        @SuppressWarnings("unchecked")
        List<Integer>[] passList = new List[]{incoming, outlets, sources};
        for (int pass = 0; pass < 3; pass++) {
            List<Integer> starts = passList[pass];
            for (int idx = 0; idx < starts.size(); idx++) {
                // ★ 分支数量上限（legacy riverCount 同义；0=不限制）：
                //   候选已按上游面积降序 ⇒ 截断保留的是"最大的 N 条源"。
                //   接力段与溢口续流段不计入（都是干流延续，不是新分支）。
                if (pass == 2 && params.maxSources() > 0 && sourceTraces >= params.maxSources()) {
                    break;
                }
                int start = starts.get(idx);
                boolean fromOutlet = pass == 1;
                boolean fromIncoming = pass == 0;
                if (start < 0 || start >= cells) {
                    if (fromIncoming && incomingDiag != null) incomingDiag.add(
                            new IncomingDiagnostic(start, "OUT_OF_WINDOW", 0, -1, null));
                    continue;
                }
                // ★★★ 源头间距判据（2026-10-10 升级：补齐 PL-RGA nearExistingRiver 另一半）★★★
                //
                // 【原缺陷】occupied 只登记【源头位置】⇒ 源头可以贴着大河 2~6 格起链，
                //   起链即 join ⇒ 图上大量"贴链短棒"（8~32 块，用户图上可见的孤立短线）。
                // 【legacy】PL-RGA `nearExistingRiver`：候选源距**任何已有河节点或线段**
                //   < 间距 ⇒ 弃源（rivers.cpp:394）。链身节点必须一并登记（见下方 registerTraceNodes）。
                // 【接力/溢口不受此判据约束】它们是强制源，"贴河"本就是预期（要接上）。
                if (!fromOutlet && !fromIncoming && params.minSourceSpacingCells() > 0) {
                    if (tooCloseToSources(occupied, start, nz,
                            params.minSourceSpacingCells(), BUCKET)) {
                        droppedBySpacing++;
                        continue;
                    }
                }
                if (covered[start]) {
                    if (fromIncoming && incomingDiag != null) incomingDiag.add(
                            new IncomingDiagnostic(start, "COVERED", 0, start, End.JOIN));
                    continue;
                }
                // 接力种子落在湖/海格 ⇒ 该处水体已由湖/海表达，无需再画河。
                // ★ 只对【接力】生效 —— 源头/溢口的 lake/sea 过滤不在本类（改动会变基线产出）。
                if (fromIncoming && (lakeMask[start] || sea[start])) {
                    if (incomingDiag != null) incomingDiag.add(new IncomingDiagnostic(start,
                            lakeMask[start] ? "LAKE" : "SEA", 0, start,
                            lakeMask[start] ? End.MOUTH_LAKE : End.MOUTH_SEA));
                    continue;
                }
                int stamp = ++stampBox[0];
                Trace t = traceOne(start, fromOutlet, fromIncoming, level, parent, resolved,
                        lakeMask, sea, covered, nx, nz, params.joinWindow(),
                        selfStamp, selfStep, stamp, params, segments, bed, parentForest,
                        fromIncoming ? incomingDiag : null, outside);
                if (t == null) continue;
                traces.add(t);
                if (fromOutlet) outletStarts++;
                else if (fromIncoming) incomingStarts++;
                else sourceTraces++;
                // 链身（含溢口干流链）抽样登记进空间桶 ⇒ 后续源头受"距链"间距约束。
                if (params.minSourceSpacingCells() > 0) {
                    registerTraceNodes(occupied, t.cell, nz, BUCKET);
                }
                // ★ 交叉守卫用：登记本链的全部线段（格坐标），供后续 nearbyJoin 判"连线是否横穿"
                for (int si = 1; si < t.cell.length; si++) {
                    int a0 = t.cell[si - 1], b0 = t.cell[si];
                    segments.add(new int[]{a0 / nz, a0 % nz, b0 / nz, b0 % nz});
                }
            }
        }
        // ★★★ 2026-10-10【网络级连通性过滤 —— ★「河网是一棵树」★（用户核心要求）】★★★
        //
        // 【用户原话】「河流也许就像树一样，有一个主干（河湖海连接），其他分支应该像枝干
        //   一样从主干生长出来」；「发现一小段孤立的河流，即不连接湖泊又不入海，
        //   这个现象不应该出现」。
        //
        // 【为什么单链级回滚不够（实测）】孤立段 17~19 个，多为 {@code end=JOIN}
        //   —— 单链【拓扑上】接上了，但整条链构成的【连通网络】不通向任何水体：
        //   例 poly#A JOIN poly#B，而 poly#B 自己也以 DEAD_END/孤立告终
        //   ⇒ 两条链连成一小簇，簇整体不入海不入湖 ⇒ 图上就是孤立河段。
        //   单链回滚（orphanDeadEnd）只按【自身】end 判定 ⇒ 抓不到"整簇孤立"。
        //
        // 【PL-RGA 的对应机制】其输出保证「每个像素节点或到海/湖、或并入已有节点」
        //   （rivers.cpp:408-463）；等价地，**不存在"两端都不接水体"的连通分量**。
        //   本处按同一语义做【簇级】判定：把 trace 按共享格并查集聚簇，
        //   簇内任一链通向海/湖/窗口接力 ⇒ 整簇保留；否则【整簇移除】。
        //
        // 【确定性】并查集按 trace 序号归并、判定只看"存在性" ⇒ 与遍历顺序无关。
        final boolean filterOn = networkFilter == null ? NETWORK_FILTER : networkFilter;
        if (filterOn && !traces.isEmpty()) {
            int before = traces.size();
            traces = filterUnconnected(traces, nx, nz);
            droppedNetwork = before - traces.size();
        }
        // ★★★ 2026-10-10【出口种子（双 pass 的另一半）】★★★
        //
        // 【证据】skelEndDiag 实测：全部 DEAD_END 的 mouth 格都是 frame=true, p=-1
        //   （窗口边框排水种子）⇒ 链走到窗口边缘、水流向窗外 ⇒ 窗外没画 ⇒ 缝上断。
        // 【做法】只【登记】离开方向，不替邻区画线 —— 由邻区 pass-2 用自己的 parent 追。
        // 【方向】mouth 落在哪条框上就朝哪：i==0 西 / i==nx-1 东 / j==0 北 / j==nz-1 南。
        // 【角格只发对角】角格同时在两条框上，但两个轴向接收方拿到的都会落在
        //   【它们自己的边框格】上（parent=-1 ⇒ 单格即死），只有对角接收方落在窗内
        //   ⇒ 只发 (dRX,dRZ) 对角种子（实测：轴向种子 i 或 j 恰为 0/nn-1 ⇒ 无用）。
        List<ExitSeed> exitSeeds = new ArrayList<>();
        for (Trace t : traces) {
            if (t.end != End.DEAD_END) continue;
            int mc = t.mouthCell();
            int mi = mc / nz, mj = mc % nz;
            int dRX = mi == 0 ? -1 : (mi == nx - 1 ? 1 : 0);
            int dRZ = mj == 0 ? -1 : (mj == nz - 1 ? 1 : 0);
            if (dRX == 0 && dRZ == 0) continue;      // 非边框 DEAD_END（防御，不外传）
            exitSeeds.add(new ExitSeed(mc, dRX, dRZ));
        }
        Result result = new Result(traces, sourcesTotal, droppedBySpacing, outletStarts,
                incomingStarts, exitSeeds, filterOn, droppedNetwork);
        if (incomingDiag != null) result.incomingDiagnostics = List.copyOf(incomingDiag);
        return result;
    }

    /**
     * ★★★ 2026-10-10【网络级连通性过滤 —— ★「河网是一棵树」★】★★★
     *
     * <h3>要解决的问题（用户判据）</h3>
     * <p>「发现一小段孤立的河流，即不连接湖泊又不入海，这个现象不应该出现」。
     * 实测孤立段 17~19 个，且多为 {@code end=JOIN}：单链拓扑上接上了，
     * 但<b>整簇</b>不通向任何水体（链 A JOIN 链 B，而 B 自己也以孤立告终）
     * ⇒ 图上就是一小段悬空河。单链级回滚按"自身 end"判定，抓不到"整簇孤立"。</p>
     *
     * <h3>做法（PL-RGA 语义的网络层等价）</h3>
     * <p>PL-RGA 的输出保证「不存在两端都不接水体的连通分量」
     * （{@code rivers.cpp:408-463}：要么到海/湖，要么并入已有节点，否则整条回滚）。
     * 本处按同一语义在<b>簇</b>级判定：</p>
     * <ol>
     *   <li>把 trace 按<b>共享格</b>并查集聚簇（JOIN 终点即共享格 ⇒ 天然连边）；</li>
     *   <li>簇内任一链「通向水体」⇒ 整簇保留。「通向水体」的定义：
     *       {@code MOUTH_SEA} / {@code MOUTH_LAKE}，或 {@code DEAD_END} 且 mouth 在
     *       窗口边框（跨区接力种子 —— 水下落到窗外，由邻区续流）；</li>
     *   <li>否则<b>整簇移除</b>（不留半截孤立河）。</li>
     * </ol>
     *
     * <p>⚠ 溢口段（{@code fromOutlet}）单独放行：其上游是湖，本身即"通向水体"，
     * 即便本窗口内没能走远也不该删（SkeletonProbe [5] 要求溢口必有河起点）。</p>
     *
     * <p><b>确定性</b>：并查集按 trace 序号归并，判定只看"存在性"（不依赖顺序）⇒
     * 纯函数性质不变（SkeletonProbe [8] 逐位相同仍成立）。</p>
     *
     * @param traces 全部已接受链（顺序 = 生成序）
     * @param nz     格阵每行长度（{@code cell = i*nz + j}）
     * @return 过滤后的链列表（保持原相对顺序）
     */
    private static List<Trace> filterUnconnected(List<Trace> traces, int nz, int nx) {
        int n = traces.size();
        int cells = nx * nz;
        // ① 每个格归属哪条链（首占者）—— 用于把"共享格"变成并查集连边
        int[] owner = new int[cells];
        java.util.Arrays.fill(owner, -1);
        int[] uf = new int[n];
        for (int i = 0; i < n; i++) uf[i] = i;
        for (int ti = 0; ti < n; ti++) {
            for (int c : traces.get(ti).cell) {
                if (c < 0 || c >= cells) continue;
                int o = owner[c];
                if (o < 0) owner[c] = ti;
                else if (o != ti) uf[find(uf, o)] = find(uf, ti);
            }
        }
        // ② 标记"通向水体"的簇
        boolean[] rooted = new boolean[n];
        for (int ti = 0; ti < n; ti++) {
            Trace t = traces.get(ti);
            boolean ok;
            if (t.fromOutlet) {
                ok = true;                                   // 溢口段：上游是湖（放行）
            } else if (t.end == End.MOUTH_SEA || t.end == End.MOUTH_LAKE) {
                ok = true;                                   // 直接入海/入湖
            } else if (t.end == End.DEAD_END) {
                int mc = t.mouthCell();
                int mi = mc / nz, mj = mc % nz;
                ok = mi == 0 || mj == 0 || mi == nx - 1 || mj == nz - 1;  // 窗口边框 ⇒ 接力
            } else {
                ok = false;                                  // 孤立（UNRESOLVED 等）
            }
            if (ok) rooted[find(uf, ti)] = true;
        }
        // ③ 只留"有根"的簇（保持原顺序 ⇒ 确定性）
        List<Trace> kept = new ArrayList<>(n);
        for (int ti = 0; ti < n; ti++) {
            if (rooted[find(uf, ti)]) kept.add(traces.get(ti));
        }
        return kept;
    }

    /** 并查集查找（路径减半；迭代实现，避免深递归）。 */
    private static int find(int[] uf, int x) {
        while (uf[x] != x) {
            uf[x] = uf[uf[x]];
            x = uf[x];
        }
        return x;
    }

    /**
     * 源头判据（★ 2026-10-10 改【legacy {@code extractChannelSkeletons} 同构】）。
     *
     * <h3>为什么改（用户判据）</h3>
     * <p>用户报：「有很多短碎河流」「河流/支流还会反复和其他分支交叉」。
     * 旧实现是<b>逐源布点 + 逐链 join</b>：任何"累积达标 + 在槽里"的格都起链
     * ⇒ 同一条沟壑上会起多条链 ⇒ 短碎 + 交叉。补判据（minNodes / 自贴近 / 交叉守卫）
     * 只带来温和改善，治不了结构性成因。</p>
     *
     * <h3>legacy 的做法（结构上不可能短碎/交叉）</h3>
     * <pre>
     *   for (int k …) {
     *       if (!channel[k] || lake[k] || hasUpstream[k] || claimed[k]) continue;   // ← 入度为 0
     *       …沿 down 走，逐格 claimed（全局标记）…
     *   }
     * </pre>
     * <ul>
     *   <li><b>河道格 = 流量掩码</b>（{@code discharge ≥ thr}），不是"每个达标格都是源头"；</li>
     *   <li><b>源头 = 河道格中 D8 入度为 0 者</b> ⇒ <b>每个分水岭唯一源头</b>；</li>
     *   <li>{@code claimed} 全局标记 ⇒ 天然无重复（后续链遇到已 claimed 即并入）。</li>
     * </ul>
     * <p>本方法只产出【源头集合】；"沿 parent 走 + 并入"由 {@link #traceOne} 完成
     * （其 {@code covered} 等价于 legacy 的 {@code claimed}）。</p>
     *
     * @return 源头格列表（按上游面积降序 —— 大河优先，语义与 legacy 的扫描序一致）
     */
    static List<Integer> collectSources(double[] level, int[] parent, boolean[] resolved,
                                                boolean[] lakeMask, boolean[] sea,
                                                double[] discharge, int nx, int nz,
                                                Params params) {
        int cells = nx * nz;
        // ① 河道掩码：discharge ≥ 门限（门限 = n²×frac×runoff，由调用方算好传入 minSourceAccum）
        //    ⚠ 门限为 0 时退化为"全部格都是候选"—— 那是旧行为，仅探针用。
        boolean[] channel = new boolean[cells];
        boolean anyChannel = false;
        for (int k = 0; k < cells; k++) {
            if (lakeMask[k] || sea[k] || !resolved[k]) continue;
            if (parent[k] < 0 || parent[k] >= cells || parent[k] == k) continue;
            if (params.minSourceAccum() > 0 && discharge[k] < params.minSourceAccum()) continue;
            channel[k] = true;
            anyChannel = true;
        }
        if (!anyChannel) return new ArrayList<>();
        // ② D8 入度：某河道格被几个上游河道格指向（legacy hasUpstream）
        boolean[] hasUpstream = new boolean[cells];
        for (int k = 0; k < cells; k++) {
            if (!channel[k]) continue;
            int p = parent[k];
            if (p >= 0 && p < cells && channel[p]) hasUpstream[p] = true;
        }
        // ③ 源头 = 河道格且入度为 0（+ 汇流槽判据，保留"源头应在谷中"的用户要求）
        //
        // ★★★ 2026-10-10【方案 A：放宽源头判据（无侵蚀 bed 的密度补偿）】★★★
        // 【背景】求解 bed 改用无侵蚀 `sample()`（零 tile 依赖，治冷启动）后，
        //   源头数从 37~61 掉到 12~30（约 −55%）—— <b>不是判据失效，是地形本身少了沟壑</b>
        //   （侵蚀在地形上刻出沟槽 ⇒ 入度 0 的谷地更多）。实测 r=300：河道 5691→2724。
        // 【本开关】关掉【汇流槽判据】（{@code inValleyTrough}）—— 该判据要求源头在谷中，
        //   在平滑噪声场上会把"缓坡上的源头"大量否决。关掉后源头按入度 0 全部接受。
        //   {@code -Dgeogenesis.valleyTrough=false} 关闭判据（= 方案 A）。
        List<Integer> sources = new ArrayList<>();
        for (int k = 0; k < cells; k++) {
            if (!channel[k] || hasUpstream[k]) continue;
            // 汇流槽判据（legacy inValleyTrough）：垂直于下坡方向两侧任一更低 ⇒ 凸坡否决
            if (VALLEY_TROUGH && !inValleyTrough(level, parent, k, nx, nz)) continue;
            sources.add(k);
        }
        sources.sort((a, b) -> {
            int c = Double.compare(discharge[b], discharge[a]);
            return c != 0 ? c : Integer.compare(a, b);
        });
        return sources;
    }

    /**
     * 汇流槽判据（legacy {@code inValleyTrough} 同义）：
     * 取 k→parent 方向的两个垂直方向；任一侧（在界内）level 严格更低
     * ⇒ k 在凸坡/山脊上（水会向该侧散开）⇒ 否决。
     * 平地（填充面全等）两侧都不更低 ⇒ 通过（PL-RGA 平原可布源）。
     */
    private static boolean inValleyTrough(double[] level, int[] parent, int k,
                                           int nx, int nz) {
        int i = k / nz, j = k % nz;
        int p = parent[k];
        int di = p / nz - i, dj = p % nz - j;
        if (di == 0 && dj == 0) return false;
        // 两个垂直方向（含对角方向的对角垂线）
        int[][] perps = {{-dj, di}, {dj, -di}};
        double cur = level[k];
        for (int[] q : perps) {
            int a = i + q[0], b = j + q[1];
            if (a < 0 || b < 0 || a >= nx || b >= nz) continue;   // 越界侧不否决
            if (level[a * nz + b] < cur - 1e-9) return false;
        }
        return true;
    }

    private static boolean tooCloseToSources(java.util.Map<Long, List<int[]>> occupied,
                                             int start, int nz, int spacing, int bucket) {
        int sx = start / nz, sz = start % nz;
        int b0x = Math.floorDiv(sx - spacing, bucket);
        int b1x = Math.floorDiv(sx + spacing, bucket);
        int b0z = Math.floorDiv(sz - spacing, bucket);
        int b1z = Math.floorDiv(sz + spacing, bucket);
        for (int bx = b0x; bx <= b1x; bx++) {
            for (int bz = b0z; bz <= b1z; bz++) {
                List<int[]> pts = occupied.get(((long) bx << 32) ^ (bz & 0xffffffffL));
                if (pts == null) continue;
                for (int[] pt : pts) {
                    int ddx = pt[0] - sx, ddz = pt[1] - sz;
                    if (ddx * ddx + ddz * ddz < spacing * (long) spacing) return true;
                }
            }
        }
        return false;
    }

    /** 把连续路径摊平成 {x0,z0,x1,z1,...}，长度按 count 对齐（多退少补）。 */
    private static double[] flattenCont(java.util.List<double[]> cont, int count) {
        double[] out = new double[count * 2];
        for (int i = 0; i < count; i++) {
            if (i < cont.size()) {
                out[i * 2] = cont.get(i)[0];
                out[i * 2 + 1] = cont.get(i)[1];
            } else if (cont.isEmpty()) {
                out[i * 2] = Double.NaN;
            } else {
                double[] last = cont.get(cont.size() - 1);
                out[i * 2] = last[0];
                out[i * 2 + 1] = last[1];
            }
        }
        return out;
    }

    /**
     * 把一条链的【全部节点】抽样（步长 2）登记进空间桶 —— 供"源头距任何已有河"
     * 间距判据查询（PL-RGA {@code nearExistingRiver} 同义；2026-10-10 补齐）。
     * 抽样步长 2 在间距 ≥4 格时最多漏检 1 格，可接受。
     */
    private static void registerTraceNodes(java.util.Map<Long, List<int[]>> occupied,
                                           int[] cell, int nz, int bucket) {
        for (int i = 0; i < cell.length; i += 2) {
            int sx = cell[i] / nz, sz = cell[i] % nz;
            occupied.computeIfAbsent(
                            ((long) Math.floorDiv(sx, bucket) << 32)
                                    ^ (Math.floorDiv(sz, bucket) & 0xffffffffL),
                            x -> new ArrayList<>())
                    .add(new int[]{sx, sz});
        }
    }

    /**
     * 从起点沿 parent 走链。先查就近汇入窗口（legacy nearbyDownhillNode），
     * 再沿 parent 前进。返回 null = 单格无效段 / 短于 {@code params.minNodes()}。
     *
     * @param selfStamp 自并入守卫：{@code selfStamp[c] == stamp} 表示 c 属于本 trace
     *                  （join 必须排除自己的节点 —— legacy {@code selfApproach} 同义）
     * @param params    只读 {@code minNodes}（长度下限）；其余不参与追踪
     */
    private static Trace traceOne(int start, boolean fromOutlet, boolean fromIncoming,
                                  double[] level, int[] parent, boolean[] resolved,
                                  boolean[] lakeMask, boolean[] sea, boolean[] covered,
                                  int nx, int nz, int joinWindow,
                                  int[] selfStamp, int[] selfStep, int stamp, Params params,
                                  java.util.List<int[]> segments, double[] bed, ParentForest parentForest,
                                  List<IncomingDiagnostic> incomingDiag,
                                  OutsideContinuation outside) {
        int cells = nx * nz;
        java.util.TreeMap<Integer, Integer> visited = parentForest == null ? null : new java.util.TreeMap<>();
        List<Integer> path = new ArrayList<>();
        int k = start;
        End end = End.DEAD_END;
        // 窗口外续流的世界坐标尾段（只有走出窗口时非空）。
        double[] outsideTail = new double[0];
        // 严格 D8 的"本链已走过"集合（洼地溢流搜索用，惰性创建）。
        java.util.Set<Integer> strictVisited = null;
        // ★ 阶段 B：惯性方向（上一步格步的单位向量）。一阶状态 ⇒ 随链携带、不引入窗口依赖。
        //   首步为 0（无惯性）⇒ 行为与旧版逐位相同。
        double lastDirX = 0, lastDirZ = 0;
        // ★ 2026-10-10【丢弃时必须只释放"本链新覆盖的格"】——
        //   末位 JOIN 终点是【别的链的节点】（covered 早已为 true）⇒ 若整条 path 一律
        //   置 false，会把别人已占的内部节点释放掉 ⇒ 后继链穿过它 = SkeletonProbe [4] 双占。
        //   （实测：minNodes 丢弃逻辑加"整条释放"后 [4] 立刻报 4 个格双占。）
        int newlyClaimed = 0;
        var ownSegments = new TraceSegments(nz);
        double escapeLevel = Double.NaN;
        // ★ 连续路径（格坐标，浮点）：与 path 一一对应。
        //   用户：「落地不再是那八个方向，而是拿那个算出来的结果向量的方向。」
        var contPts = new ArrayList<double[]>();
        // 当前连续位置（格坐标）。首点为起点格心。
        double curX = start / nz, curZ = start % nz;
        while (true) {
            if (level[k] < escapeLevel - 1e-9) escapeLevel = Double.NaN;
            if (!path.isEmpty()) {
                int previous = path.get(path.size() - 1);
                ownSegments.add(previous, k);
                if (visited != null) markCrossingParents(previous, k, nz, parent, parentForest, visited);
            }
            path.add(k);
            contPts.add(new double[]{curX, curZ});
            if (visited != null) parentForest.mark(k, visited);
            if (!covered[k]) newlyClaimed++;
            covered[k] = true;
            selfStamp[k] = stamp;              // 自并入守卫：标记本 trace 节点
            selfStep[k] = path.size();         // 步序号（自贴近豁免用）
            // ★★★ 溢口首步逃逸（SkeletonProbe [5] 抓出的真问题，2026-10-10）★★★
            //
            // 【现象】2 个溢口 parentLake=true ⇒ 溢口段第一步就 MOUTH_LAKE、单格丢弃
            //   ⇒ 湖溢出后没有新河起点（用户："直到湖泊出现新的溢出口作为河流新起点"）。
            // 【机理】minimax 洪泛把湖面抹平成等高水平面，溢口格与湖面格 level 相等
            //   ⇒ φ=(level, index) 字典序破平局选中 index 更小的【湖内格】
            //   ⇒ 树上的"下游"指回湖里（树结构合法 —— φ 仍严格下降，但语义不是"流出"）。
            // 【正解】溢口首步不沿 parent，改走【盆外邻域真实下坡】：
            //   8 邻域内非湖、resolved、level 严格更低的格中取最低（平局取小索引）。
            //   找到且已 covered ⇒ 并入；找到且是海 ⇒ 入海；否则作为新链头正常续走。
            if (fromOutlet && path.size() == 1) {
                int esc = outletEscape(k, level, resolved, lakeMask, sea, nx, nz);
                if (esc >= 0 && esc != k) {
                    if (covered[esc]) { path.add(esc); end = End.JOIN; break; }
                    if (sea[esc]) { path.add(esc); newlyClaimed++; covered[esc] = true; end = End.MOUTH_SEA; break; }
                    k = esc;
                    continue;
                }
                // 逃逸失败（盆外无下坡/全被湖占）⇒ 沿原 parent 兜底（可能单格终止）
            }
            // 就近汇入：窗口内找「已 covered 且 level 不高于当前」的最优节点
            //   （排除本 trace 自己的节点 —— 否则平坦区会 join 到自己、提前终止+折返）
            int join = nearbyJoin(level, k, nx, nz, covered, joinWindow, selfStamp, stamp,
                    segments, bed);
            if (join >= 0 && !ownSegments.crosses(k, join)) {
                path.add(join);
                end = End.JOIN;
                break;
            }
            // ★★★ 2026-10-21【严格 D8 拓扑 —— 零爬坡】★★★
            //
            // 【为什么换掉 parent 链】minimax `flood()` 优化的是"瓶颈最低"，只保证
            //   `level` 非升；实测 `bed` 爬坡 115 步（5.68%、单步最大 14.53 块）、
            //   等高 276 步（13.64%）⇒ 河会翻上比起点更高的地形。用户判据：
            //   「肯定不能爬坡啊，我们做的是河流」。
            // 【换法】每步取 8 邻中 `bed` 严格最低者 ⇒ 单步高差恒为负 ⇒ 爬坡 0。
            //   严格递减同时保证<b>无环</b>（不需要额外的环检测）。
            // 【洼地】无更低邻居 ⇒ priority-flood 找鞍部溢流（真实河流积成小湖后
            //   从最低溢口继续下泄）；溢出口抬升超过 MAX_SPILL_RISE 才认作封闭盆地。
            int p;
            if (STRICT_D8 && bed != null) {
                // ★ 阶段 B：带惯性的严格 D8。
                //   惯性 = 上一步的格步方向（一阶状态，随链携带 ⇒ 仍局部、仍确定）。
                //   平局时按惯性择优；有严格更低者时惯性绝不把路线拉高（硬约束在函数内）。
                //   ★ 优先用【梯度方向场】（物理向量机制）：
                //     先估连续坡向，再在严格更低的候选里取最贴合者。
                //     这治「缓坡上 8 邻几乎全平局 ⇒ 退化成扫描序 ⇒ 走成直线」。
                //   ★ 没有梯度场时才退回惯性/扫描序。
                int s;
                double stepDirX = 0, stepDirZ = 0;
                if (VECTOR_TRACE) {
                    StepResult sr = vectorTraceNext(k, bed, nx, nz, lastDirX, lastDirZ, GRADIENT_SPAN);
                    s = sr.cell();
                    stepDirX = sr.dirX();
                    stepDirZ = sr.dirZ();
                } else {
                    s = strictD8Next(k, bed, nx, nz);
                }
                if (s >= 0) {
                    p = s;
                    // 更新惯性：本次迈步方向（格步，非块步 —— 只用于点积择优）
                    int ki = k / nz, kj = k % nz, si = p / nz, sj = p % nz;
                    double dx = si - ki, dz = sj - kj;
                    double dl = Math.hypot(dx, dz);
                    if (dl > 1e-9) { lastDirX = dx / dl; lastDirZ = dz / dl; }
                    // ★ 2026-10-21【撤回连续坐标累加】
                    //   它与格索引 k 不同步（k 按 D8 跳、cur 按方向累加）⇒ 会脱钩。
                    //   真正的"位置驱动格"需要重写主循环，见 shape() 处的说明。
                    curX = si; curZ = sj;
                } else {
                    // ★★★ 2026-10-21【洼地续流 —— 恢复，因为删掉它导致大量短河】★★★
                    //
                    // 【我曾删掉它的理由（错的）】用户说「溢流应该是给湖泊用的，不是给河流」。
                    // 【实测证否】真洼地 6398 个，被 lakeMask 认成湖的 0 个
                    //   ⇒ 河到洼地必 DEAD_END ⇒ 用户：「非常多短河并且是非正常结束」。
                    // 【正确的理解】「溢流属于湖泊」指湖泊涨满后的出流由 basinOutlet 起新段；
                    //   河流遇到小洼地漫过鞍部继续流，是自然河流的基本行为
                    //   （水不会在半路的坑里凭空消失）。
                    // 【做法】找最低鞍部溢出；抬升超 MAX_SPILL_RISE 才认真封闭盆地。
                    if (RIVER_SPILL) {
                        if (strictVisited == null) strictVisited = new java.util.HashSet<>();
                        strictVisited.add(k);
                        int sp = spillCell(k, bed, nx, nz, strictVisited);
                        p = (sp >= 0 && bed[sp] - bed[k] <= MAX_SPILL_RISE) ? sp : -1;
                    } else {
                        p = -1;
                    }
                }
            } else {
                p = parent[k];
            }
            if (p < 0 || p >= cells || p == k) {
                if (sea[k]) { end = End.MOUTH_SEA; break; }
                if (lakeMask[k]) { end = End.MOUTH_LAKE; break; }
                // ★【窗口边界不截断】parent 出窗必为 -1，但窗外地形仍可采样 ⇒ 沿最陡下降续追。
                if (outside != null) {
                    Continuation cont = continueOutside(k, level, lakeMask, sea, nx, nz, outside);
                    if (cont != null) {
                        for (int oc : cont.path()) {
                            path.add(oc);
                            if (oc >= 0 && oc < cells) covered[oc] = true;
                        }
                        end = cont.end();
                        outsideTail = cont.outsideTail();
                        break;
                    }
                }
                end = End.DEAD_END;
                break;
            }
            // ★★★ 2026-10-10【未决格不再终止骨架 —— 断口归因修复】★★★
            //
            // 【原写法】`!resolved[p] ⇒ UNRESOLVED 终止` —— 我自造的保守约束。
            // 【归因证据】正典图 101 个断口；落块层零缺陷（出水 100%、误杀 0）；
            //   端点分布 join=85%、unresolved 每 region 5~18 个 ⇒ 量级吻合 ⇒ 断口源头。
            // 【为什么错】`resolved` 是【水位可信度】标志（与窗口切割有关），
            //   而骨架走的是 parent 链 —— 其无环与 level 单调性是【构造性质】，
            //   与水位可信度无关。legacy 场（真实地形+ε）处处有定义 ⇒ 从不因此断河。
            // 【正解】继续沿 parent 走到底；未决只影响水位/湖判定（另一层）。
            if ((lakeMask[p] || sea[p] || covered[p]) && ownSegments.crosses(k, p)) { end = End.DEAD_END; break; }
            if (lakeMask[p]) {
                // ★★★ 2026-10-10【入湖锚点延伸（legacy {@code LAKE_ANCHOR_EXT} 同义）】★★★
                //
                // 【原写法】只 `end = MOUTH_LAKE; break;` —— 折线止于【岸格中心】。
                // 【实测缺陷】湖/海是【格级布尔】(4 块分辨率) 渲染的，而河是【连续折线】：
                //   折线止于岸格中心 ⇒ 岸格内远离折线的方块不染色，而邻格（湖格）整格是水面
                //   ⇒ 两者之间可隔 1~4 块 ⇒ **像素级孤立**（OrphanSeam 实测缝距 2~3 块
                //   占 10/16）。
                // 【正解】把终点【延入湖格】：折线末段穿过岸格→湖格，中间方块被扫掠染色
                //   ⇒ 与湖面像素直接 8 连通。
                // ⚠ 不标记 `covered[p]`：湖面格不参与"认领"，保持既有语义不变。
                path.add(p);
                end = End.MOUTH_LAKE;
                break;
            }
            if (sea[p]) {
                path.add(p);
                if (!covered[p]) newlyClaimed++;
                covered[p] = true;
                end = End.MOUTH_SEA;
                break;
            }
            if (covered[p]) {
                // 沿 parent 正好走到 covered ⇒ 并入（共享节点，树语义）
                path.add(p);
                end = End.JOIN;
                break;
            }
            // ★★★ 2026-10-10【平地破平局 —— 径向直线伪影的追踪侧修复（HANDOFF §17.6）】★★★
            //
            // 【用户圈出】两条笔直斜线横穿画面、与河道交叉（"不可能是伪影啊，我看图
            //   明显有这问题啊"—— 对，这是真实缺陷，我此前误记为"观感可接受"）。
            // 【定性（EquilibriumWater:244 自述）】minimax 洪泛把【水位全等平地】的
            //   parent 交给"弹出顺序"决定 ≈ 指向最近种子的直线族（径向）
            //   ⇒ 平地上的河是【几何直线】，与真实地形无关。
            // 【破平局】若 parent 的目标格与当前格【level 相等】（同一等水位连通区内部），
            //   说明这一步是"弹出序产物"而非地形产物 ⇒ 改选【bed 最低的等水位邻居】
            //   （真实地形微小坡度 = 水该走的方向）。bed 也全等（真平台）才退回 parent。
            // 【单调性】候选 level == 当前 level（非升）⇒ SkeletonProbe [1] 仍过。
            // 【无环】候选必须 covered=false（下方自然满足：走到的格会标 covered），
            //   且候选不得是本 trace 节点（selfStamp 守卫同 parent 路径）。
            // ★ 破平局开关（`-Dgeogenesis.flatBreak=false` 关）—— 归因"揉团锯齿"用。
            // ⚠ 严格 D8 下必须停用：本块用的是 minimax 的 `level` 与 `parent` 口径
            //   （"parent 目标是等水位 ⇒ 弹出序产物"），而严格 D8 已经直接用 `bed`
            //   选方向、且 `level` 与 `bed` 不再一致 —— 让它介入会抢走严格 D8 的方向。
            if (!STRICT_D8 && FLAT_BREAK && bed != null && Double.isNaN(escapeLevel)
                    && level[p] >= level[k] - 1e-9 && level[p] <= level[k] + 1e-9) {
                int bi = k / nz, bj = k % nz;
                double curBed = bed[k];
                int alt = -1;
                double altBed = curBed;
                for (int[] d : EQUAL_DIRS8) {
                    int a = bi + d[0], b = bj + d[1];
                    if (a < 0 || b < 0 || a >= nx || b >= nz) continue;
                    int c = a * nz + b;
                    if (c == p || c == k) continue;
                    if (lakeMask[c] || sea[c] || !resolved[c]) continue;
                    if (level[c] > level[k] + 1e-9) continue;          // 只走等水位区内部
                    if (covered[c] || selfStamp[c] == stamp) continue; // 不撞别人/自己
                    if (bed[c] < altBed - 1e-9 && !parentForest.returnsTo(c, visited)
                            && !ownSegments.crosses(k, c)) {
                        // 不可行候选只排除自己，不能抢占最低值后使整条河回滚。
                        if (STEP_CROSS_GUARD && !segments.isEmpty()
                                && segmentCrossesAny(bi, bj, a, b, segments)) continue;
                        altBed = bed[c]; alt = c;
                    }
                }
                if (alt >= 0) {
                    k = alt;
                    continue;      // 走真实下坡（等水位区内），本轮不前进 parent
                }
                // 无更低 bed 邻居（真平台）⇒ 沿 parent（径向兜底）
            }
            // ★ 步骤级交叉守卫（parent 迈步；同 PL-RGA wouldCross）
            if (ownSegments.crosses(k, p)) { end = End.DEAD_END; break; }
            if (STEP_CROSS_GUARD && !segments.isEmpty()) {
                int ki = k / nz, kj = k % nz, pi = p / nz, pj = p % nz;
                if (segmentCrossesAny(ki, kj, pi, pj, segments)) {
                    end = End.DEAD_END;
                    break;
                }
            }
            // 等水位盆底开始沿父链爬升脱困后，不再逐步改道掉回盆底；降至下一水位才恢复。
            // ⚠ 严格 D8 下停用（同上方 FLAT_BREAK 说明：那是 minimax level 口径）。
            if (!STRICT_D8 && FLAT_BREAK && bed != null && Math.abs(level[p] - level[k]) <= 1e-9
                    && bed[p] > bed[k] + 1e-9) escapeLevel = level[k];
            k = p;
            if (path.size() > cells) { end = End.DEAD_END; break; }  // 防御
            // ★★★ 2026-10-10【自贴近守卫（legacy selfApproach 同义）】★★★
            //
            // 【用户判据（2026-10-10）】「河流/支流还会反复和其他分支交叉，类似重叠的情况」。
            // 【legacy 的解法】`selfApproach(seen, down, nx, nz, path)`：迈步【之前】，
            //   若目标格半径 2 格（SELF_APPROACH_RADIUS）内存在【本河已走过的格】，
            //   且该格不在最近 6 步内（SELF_APPROACH_EXEMPT，排除正常曲率/紧密河曲）
            //   ⇒ 判定"河贴着自己绕一圈" ⇒ 就地终止（legacy 处标为 isLake，由洼地续流接走）。
            // 【为什么必须在【迈步前】判】上一版只做到"join 排除自己节点"（半径 0）：
            //   擦着【旁边一格】绕过去不算 ⇒ 交叉/马蹄形闭环照样产生。
            // 【与层级的关系】本判据与 nearbyJoin 的"等高容差"不冲突：
            //   后者是【主动并入】，前者是【拒绝贴近】；两者都只作用在本链自己的格上。
            if (params.selfApproachRadius() > 0
                    && selfApproach(selfStamp, selfStep, stamp, p, nx, nz,
                            params.selfApproachRadius(), path.size(), params.selfApproachExempt())) {
                end = End.DEAD_END;
                break;
            }
        }
        // ★ 长度下限（legacy {@code minRiverNodes=3} 同义，2026-10-10）：
        //   用户判据「有很多短碎河流」。短于此的段【整条丢弃】并把 covered 释放，
        //   让后继链可以经过（否则会留下幽灵覆盖）。
        //
        // ⚠【豁免】legacy 原文：「独立河 ≥ minRiverNodes；feeder 语义下的【合并连接】
        //   （河头+交汇点的 2 格 Y 形）也放行」。溢口续流段（湖后河）正是"干流延续"，
        //   且 SkeletonProbe [5] 要求「溢口必有河起点」⇒ **溢口段豁免长度下限**。
        //   （实测：不豁免时 [5] 报 1~4 个溢口无覆盖 = 湖边河凭空消失。）
        int minNodes = Math.max(2, params.minNodes());
        // ★★★ 2026-10-10【路径级回滚（PL-RGA rolled_back 同构）—— 治"孤立段"】★★★
        //
        // 【用户判据】「发现一小段孤立的河流，即不连接湖泊又不入海，这个现象不应该出现」。
        // 【实测】Orphan.cs 检出 17 个孤立段（≤60 格且不接湖/海）；skelDump 解剖
        //   poly#18 n=238 end=DEAD_END（mouth 不在窗口边框）⇒ 链在陆地凭空死掉。
        // 【PL-RGA 的做法（rivers.cpp:405-431）】路径无法继续时——
        //   · 不安全（会撞板界 / 非 lake_safe）⇒ 整条回滚（segments.resize + nodes.resize）
        //   · 安全死端 ⇒ 标 LocalMinimum（湖）
        //   论文原话：「the entire path is discarded rather than truncated」。
        // 【我们的正解】DEAD_END = 链没能接上海/湖/别条河 ⇒ 按 PL-RGA 语义【整条丢弃】，
        //   不留半截悬空河。⚠ 两个例外：
        //   ① 溢口段（fromOutlet）：湖后河本该继续（SkeletonProbe [5] 要求溢口必有河起点）；
        //   ② ★【窗口边框的 DEAD_END】：它是【跨区续流的出口种子】（见下方 exitSeeds
        //      收集），丢掉它 = 双 pass 续流失效 ⇒ 缝上重新断流。
        //      （实测判据：mouth 落在 i==0/nx-1/j==0/nz-1 即边框。）
        int mn = path.get(path.size() - 1);
        int mi = mn / nz, mj = mn % nz;
        boolean onFrame = mi == 0 || mj == 0 || mi == nx - 1 || mj == nz - 1;
        boolean orphanDeadEnd = (end == End.DEAD_END) && !fromOutlet && !onFrame;
        boolean connectedIncoming = fromIncoming && path.size() >= 2
                && (end == End.MOUTH_LAKE || end == End.MOUTH_SEA || end == End.JOIN || onFrame);
        if (((path.size() < minNodes && !connectedIncoming) || orphanDeadEnd) && !fromOutlet) {
            // #region agent log: R5 回滚原因与终点，只读不改追踪
            if (incomingDiag != null) incomingDiag.add(new IncomingDiagnostic(start,
                    path.size() < minNodes ? "SHORT" : "ORPHAN_DEAD_END", path.size(), mn, end));
            // #endregion
            // 只释放【本链新覆盖的格】：末位 JOIN 终点属别的链，不得释放（见上方 newlyClaimed 说明）
            for (int i = 0; i < path.size(); i++) {
                int c = path.get(i);
                if (selfStamp[c] == stamp) selfStamp[c] = 0;   // 撤掉自贴近标记
            }
            int release = Math.max(0, path.size() - 1);        // 末位可能是 JOIN 终点
            for (int i = 0; i < release; i++) covered[path.get(i)] = false;
            if (newlyClaimed >= path.size()) covered[path.get(path.size() - 1)] = false;
            return null;
        }
        if (incomingDiag != null) incomingDiag.add(
                new IncomingDiagnostic(start, "ACCEPTED", path.size(), mn, end));
        return new Trace(toArray(path), end, fromOutlet, outsideTail, flattenCont(contPts, path.size()));
    }

    /**
     * 自贴近判据（legacy {@code selfApproach} 同义，2026-10-10）：
     * 目标格 {@code down} 的【半径 r 格】邻域内是否存在本 trace 已走过的格
     * 且它不在最近 {@code exempt} 步内（排除正常曲率/紧密河曲）。
     *
     * <p>⚠ 豁免必须有：步长 1 格 ⇒ 上一步恒在半径内，无豁免则一迈步就误判。
     * legacy 用 {@code path} 列表线性扫描最近 6 步；此处用
     * {@code selfStep[]}（格 → 该 trace 内的步序号）做 O(1) 等价判断。</p>
     *
     * @param curLen 本 trace 当前已走格数（= 即将迈步时的 {@code path.size()}）
     */
    private static boolean selfApproach(int[] selfStamp, int[] selfStep, int stamp, int down,
                                        int nx, int nz, int radius, int curLen, int exempt) {
        int di = down / nz, dj = down % nz;
        for (int oj = -radius; oj <= radius; oj++) {
            for (int oi = -radius; oi <= radius; oi++) {
                if (oi == 0 && oj == 0) continue;
                int ni = di + oi, nj = dj + oj;
                if (ni < 0 || ni >= nx || nj < 0 || nj >= nz) continue;
                int nb = ni * nz + nj;
                if (selfStamp[nb] != stamp) continue;
                if (curLen - selfStep[nb] > exempt) return true;   // 绕回来了
            }
        }
        return false;
    }

    /**
     * 溢口逃逸：8 邻域内「非湖、resolved、level 严格低于溢口」的最优下坡格
     * （level 最低优先，平局取小索引）。-1 = 无（盆外无下坡 / 全被湖占）。
     *
     * <p>只在溢口首步调用 —— 普通链沿 parent 即可（树语义已含单调保证）。</p>
     */
    private static int outletEscape(int k, double[] level, boolean[] resolved,
                                    boolean[] lakeMask, boolean[] sea, int nx, int nz) {
        int i = k / nz, j = k % nz;
        double cur = level[k];
        int best = -1;
        double bestLevel = Double.POSITIVE_INFINITY;
        // 半径 2（8 邻域常被湖面格全占 ⇒ 实测 1 个溢口 8 邻域逃逸失败）。
        //   2 格内仍要求 level 严格更低 ⇒ 单调性不破坏。
        for (int a = Math.max(0, i - 2); a <= Math.min(nx - 1, i + 2); a++) {
            for (int b = Math.max(0, j - 2); b <= Math.min(nz - 1, j + 2); b++) {
                int c = a * nz + b;
                if (c == k) continue;
                if (lakeMask[c] || !resolved[c]) continue;
                if (sea[c]) {
                    // 海格优先（level 可能因海面定义不满足"严格更低"）
                    return c;
                }
                if (level[c] >= cur - 1e-9) continue;
                if (level[c] < bestLevel - 1e-12
                        || (Math.abs(level[c] - bestLevel) <= 1e-12 && c < best)) {
                    bestLevel = level[c];
                    best = c;
                }
            }
        }
        return best;
    }

    /**
     * 就近汇入（legacy {@code nearbyDownhillNode} 同义）：
     * 窗口内已 covered 的节点，评分 {@code level + 1e-4·d²} 取最小（最低优先、等距就近）。
     *
     * <p>★★★ 2026-10-10【接点判据改回 PL-RGA 的【严格更低】+ bed 破平局兜底】★★★</p>
     *
     * <p>原文（等高容差）的理由是"平区 level 全等 ⇒ 严格更低永不触发 ⇒ 平行链不并入"。
     * 但单变量 A/B 实测该容差的贡献 ≈0（关掉后河格 14119 vs 全开 14000，差 0.8%），
     * 而 PL-RGA 用<b>严格更低</b>（{@code rivers.cpp:230}
     * {@code nodes[id].height >= current - river_min_drop ⇒ continue}）有其结构意义：
     * <b>保证支流接在主干的【下游侧】</b>，从而：（a）接完仍单调下降；
     * （b）不会出现"支流接回主干上游"造成的树形错乱与视觉回环。</p>
     *
     * <p><b>平区兜底</b>：真平坦时严格更低确实无候选 ⇒ 退回【bed 更低】判据
     * （真实地形微坡，与 {@code FLAT_BREAK} 同一口径）——两者都无（真平台）
     * 才允许等高并入。这样既拿到 PL-RGA 的树形保证，又不丢平区并入能力。</p>
     *
     * @param bed 真实床面（可为 null ⇒ 跳过 bed 兜底，行为=严格更低）
     */
    private static int nearbyJoin(double[] level, int k, int nx, int nz,
                                  boolean[] covered, int joinWindow,
                                  int[] selfStamp, int stamp,
                                  java.util.List<int[]> segments, double[] bed) {
        if (joinWindow <= 0) return -1;
        int i = k / nz, j = k % nz;
        // ★ 等水位区兜底：本格所在等水位连通区内是否有 bed 更低的候选（真地形下坡）
        double cur = level[k];
        double curBed = bed != null ? bed[k] : 0;
        int best = -1;
        double bestScore = Double.POSITIVE_INFINITY;
        int bestEq = -1;                 // 等高兜底候选（仅当无严格更低/无 bed 更低时启用）
        double bestEqScore = Double.POSITIVE_INFINITY;
        for (int a = Math.max(0, i - joinWindow); a <= Math.min(nx - 1, i + joinWindow); a++) {
            for (int b = Math.max(0, j - joinWindow); b <= Math.min(nz - 1, j + joinWindow); b++) {
                int c = a * nz + b;
                if (c == k || !covered[c]) continue;
                if (selfStamp[c] == stamp) continue;              // ★ 自并入守卫（selfApproach）
                // ★ 交叉守卫（PL-RGA _wouldCrossExistingSegments）：
                //   汇入连线不得横穿任何已有河段（缺它 ⇒ "两条河交叉"）
                if (segments != null && !segments.isEmpty()
                        && segmentCrossesAny(i, j, a, b, segments)) continue;
                int ddi = a - i, ddj = b - j;
                double score = level[c] + 1e-4 * (ddi * ddi + ddj * ddj);
                double lv = level[c];
                if (lv < cur - 1e-9) {
                    // ① PL-RGA 主判据：严格更低（保证接在下游侧）
                    if (score < bestScore - 1e-12
                            || (Math.abs(score - bestScore) <= 1e-12 && c < best)) {
                        bestScore = score;
                        best = c;
                    }
                } else if (lv <= cur + 1e-9
                        && bed != null && bed[c] < curBed - 1e-9) {
                    // ② 等水位 + bed 更低（平区真实微坡）⇒ 视同"更低"
                    if (score < bestScore - 1e-12
                            || (Math.abs(score - bestScore) <= 1e-12 && c < best)) {
                        bestScore = score;
                        best = c;
                    }
                } else if (lv <= cur + 1e-9) {
                    // ③ 真平台兜底（level 与 bed 都不更低）：只在 ①② 全空时用
                    if (score < bestEqScore - 1e-12
                            || (Math.abs(score - bestEqScore) <= 1e-12 && c < bestEq)) {
                        bestEqScore = score;
                        bestEq = c;
                    }
                }
            }
        }
        return best >= 0 ? best : bestEq;
    }

    // 单位对角边只可能被同一方格的另一条对角父边严格穿越；把该父边上游子树加入禁入区。
    private static void markCrossingParents(int from, int to, int nz, int[] parent,
                                            ParentForest forest, java.util.TreeMap<Integer, Integer> ranges) {
        int ax = from / nz, az = from % nz, bx = to / nz, bz = to % nz;
        if (Math.abs(ax - bx) != 1 || Math.abs(az - bz) != 1) return;
        int c = ax * nz + bz, d = bx * nz + az;
        if (parent[c] == d) forest.mark(c, ranges);
        if (parent[d] == c) forest.mark(d, ranges);
    }

    // 本链未提交到全局segments，须单独索引；8格桶只检查候选线段覆盖的局部范围。
    static final class TraceSegments {
        private final int nz;
        private final java.util.Map<Long, List<int[]>> buckets = new java.util.HashMap<>();

        TraceSegments(int nz) { this.nz = nz; }

        void add(int from, int to) {
            int ax = from / nz, az = from % nz, bx = to / nz, bz = to % nz;
            int[] edge = {ax, az, bx, bz};
            for (int x = Math.floorDiv(Math.min(ax, bx), 8); x <= Math.floorDiv(Math.max(ax, bx), 8); x++)
                for (int z = Math.floorDiv(Math.min(az, bz), 8); z <= Math.floorDiv(Math.max(az, bz), 8); z++)
                    buckets.computeIfAbsent(((long) x << 32) ^ (z & 0xffffffffL), ignored -> new ArrayList<>()).add(edge);
        }

        boolean crosses(int from, int to) {
            int ax = from / nz, az = from % nz, bx = to / nz, bz = to % nz;
            for (int x = Math.floorDiv(Math.min(ax, bx), 8); x <= Math.floorDiv(Math.max(ax, bx), 8); x++)
                for (int z = Math.floorDiv(Math.min(az, bz), 8); z <= Math.floorDiv(Math.max(az, bz), 8); z++) {
                    var edges = buckets.get(((long) x << 32) ^ (z & 0xffffffffL));
                    if (edges != null && segmentCrossesAny(ax, az, bx, bz, edges)) return true;
                }
            return false;
        }
    }

    /** 段 (ax,ay)-(bx,by) 是否与任一已有段真交叉（legacy {@code segmentCrossesAny} 同义）。 */
    private static boolean segmentCrossesAny(int ax, int ay, int bx, int by,
                                             java.util.List<int[]> segs) {
        for (int[] s : segs) {
            if (segmentsCross(ax, ay, bx, by, s[0], s[1], s[2], s[3])) return true;
        }
        return false;
    }

    /** 两线段是否【真交叉】（严格异号；共线/端点相接不算；legacy {@code segmentsCross} 同义）。 */
    private static boolean segmentsCross(int ax, int ay, int bx, int by,
                                         int cx, int cy, int dx, int dy) {
        int o1 = orientation(ax, ay, bx, by, cx, cy);
        int o2 = orientation(ax, ay, bx, by, dx, dy);
        int o3 = orientation(cx, cy, dx, dy, ax, ay);
        int o4 = orientation(cx, cy, dx, dy, bx, by);
        return o1 != 0 && o2 != 0 && o3 != 0 && o4 != 0
                && ((o1 > 0) != (o2 > 0)) && ((o3 > 0) != (o4 > 0));
    }

    private static int orientation(int ax, int ay, int bx, int by, int cx, int cy) {
        return (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
    }

    // 父树子树是DFS半开区间：候选原父链触及本链，等价于候选位于某已走节点的子树。
    static final class ParentForest {
        private final int[] entry, exit;

        ParentForest(int[] parent) {
            int n = parent.length;
            entry = new int[n]; exit = new int[n];
            java.util.Arrays.fill(entry, -1);
            int[] head = new int[n], next = new int[n], stack = new int[n];
            java.util.Arrays.fill(head, -1);
            for (int cell = 0; cell < n; cell++) {
                int p = parent[cell];
                if (p >= 0 && p < n && p != cell) { next[cell] = head[p]; head[p] = cell; }
            }
            int time = 0;
            for (int root = 0; root < n; root++) {
                if (parent[root] >= 0 && parent[root] < n && parent[root] != root) continue;
                int top = 0; stack[0] = root; entry[root] = time++;
                while (top >= 0) {
                    if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
                    int cell = stack[top], child = head[cell];
                    if (child < 0) { exit[cell] = time; top--; }
                    else {
                        head[cell] = next[child]; stack[++top] = child; entry[child] = time++;
                    }
                }
            }
        }

        void mark(int cell, java.util.TreeMap<Integer, Integer> ranges) {
            if (entry[cell] < 0) return;
            int lo = entry[cell], hi = exit[cell];
            var prior = ranges.floorEntry(lo);
            if (prior != null && prior.getValue() >= hi) return;
            if (prior != null && prior.getValue() >= lo) { lo = prior.getKey(); ranges.remove(lo); }
            var next = ranges.ceilingEntry(lo);
            while (next != null && next.getKey() < hi) {
                hi = Math.max(hi, next.getValue()); ranges.remove(next.getKey()); next = ranges.ceilingEntry(lo);
            }
            ranges.put(lo, hi);
        }

        boolean returnsTo(int cell, java.util.TreeMap<Integer, Integer> ranges) {
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
            if (entry[cell] < 0) return true;
            var prior = ranges.floorEntry(entry[cell]);
            return prior != null && prior.getValue() > entry[cell];
        }
    }

    private static int[] toArray(List<Integer> list) {
        int[] a = new int[list.size()];
        for (int i = 0; i < a.length; i++) a[i] = list.get(i);
        return a;
    }

    // ======================================================================
    // ② 形态收尾：Catmull-Rom 细分 + 4 倍频 meander（legacy smoothPath 移植）
    // ======================================================================

    /**
     * 把追踪折线加工成 block 坐标几何（治 45° 阶梯 + 无蜿蜒）。
     *
     * <p>只动 X/Z —— 不碰 level/parent/lake ⇒ 拓扑/湖/水位零影响。
     * 两端 20% 弧长平滑淡出 ⇒ 源头、入湖口、汇合点零位移
     * （汇合点在共享节点处，两侧段各自淡出到同一锚点 ⇒ 仍精确重合）。</p>
     *
     * <p>★★★ 2026-10-10【有界粒子锚点（legacy PARTICLE_POS 同构）—— 治"梯子纹"】★★★</p>
     * <p>用户圈出：缓坡河段呈"横档+小竖档"的梯子。根因：锚点钉死在 4-block 格心，
     * 缓坡 D8 走"横 3~4 格、降 1 格" ⇒ CR 重采样把长横档磨成圆角但【档距】还在。
     * legacy 2026-09-20 的解法：节点沿【连续坡向】偏移出格心（钳 0.45 格防绕圈）。
     * 本处用 bed 双线性梯度给每个锚点一个有界偏移：
     * 偏移方向 = −∇bed（下坡），幅度 ∝ 坡向与格步方向的偏差角（正交时最大 0.45·step，
     * 平行时 0 —— 格步本身已沿坡向时不偏）。</p>
     *
     * @param bed 真实床面（谷底取小后；null = 关闭粒子，锚点=格心）
     */
    public static List<Shaped> shape(List<Trace> traces, double[] level,
                                     int originX, int originZ, int step,
                                     int nx, int nz, Params params, double[] bed) {
        // 噪声固定 seed（管线惯例 0x5EEDL）+ 世界坐标驱动 ⇒ 跨窗口同坐标同值
        Simplex oct = new Simplex(0x5EE3);
        oct.seed(0x5EEDL, 0);
        List<Shaped> out = new ArrayList<>(traces.size());
        // ★★★ 2026-10-10【共享锚点表（PL-RGA {@code nodeForPixel} 同构）—— 治"孤立段"】★★★
        //
        // 【根因铁证（skelDump）】三个孤立段全是 {@code end=JOIN} —— 拓扑上确实接上了，
        //   但像素层断开。机理：
        //   · 粒子偏移带【动量】（依赖本链前一个锚点）⇒ 同一个 JOIN 格在【发出链】
        //     与【接收链】里算出【不同坐标】；
        //   · CR 重采样后各自 {@code Math.rint} ⇒ 端点像素错开 1~2 格
        //     ⇒ 8 连通检测判"悬空"（既没接湖也没接海 = 用户圈出的孤立段）。
        //   实测佐证：关粒子孤立段 17→12（粒子放大错位）⇒ 但本身也有 12 个。
        //
        // 【PL-RGA 的做法】{@code nodeForPixel}（rivers.cpp:125-152）：**每个像素只对应
        //   一个节点、一个世界坐标**，所有线段共享同一个节点 ⇒ 天然不可能错位。
        //
        // 【本处等价实现】同一【格索引】只算一次锚点坐标（首见者登记，后来者复用）。
        //   · 链路顺序确定（build 的两 pass + 源降序）⇒ 登记顺序确定 ⇒ 纯函数不变；
        //   · JOIN 目标格必被【更早的链】覆盖（{@code covered} 语义保证）⇒ 复用时
        //     该链已登记过 ⇒ 两条链在该格产出【逐位相同】的锚点。
        java.util.Map<Integer, double[]> anchorTable = new java.util.HashMap<>();
        for (Trace t : traces) {
            int m = t.cell.length;
            // ★★★ 2026-10-10【必须保索引对齐（否则下游全部读错对象）】★★★
            // 原写法 `if (m < 2) continue;` 会让 shaped 与 traces 的【下标不再对应】——
            //   `ModelHydrologyProvider` 里 `shaped.get(i)` ↔ `sk.traces.get(i)`
            //   （端点缝合、SKEL-DUMP 归因、JOIN 诊断）全部错位。
            //   实测后果：JOIN 点到"所属链"的距离出现 8/8、10/10 全 far 的假结论。
            // ⇒ 改为【发一个空折线占位】，下标恒等。
            if (m < 2) {
                out.add(new Shaped(new double[0], new double[0]));
                continue;
            }
            // ① 锚点（block 坐标）+ 有界粒子偏移（治梯子纹：锚点沿 −∇bed 偏出格心）
            //
            // ★★★ 2026-10-10【揉团锯齿根因 + 动量修法】★★★
            // 【归因铁证】skelDump poly#8（z 655→669 段）14 点折返 4 次；
            //   -Dgeogenesis.flatBreak=false 后【逐位相同】⇒ 抖动源 = parent 弹出序本身，
            //   与破平局无关。Catmull-Rom 把每个锯齿磨成小圆钩 ⇒ 图上"揉团"。
            // 【legacy 的解法（PARTICLE_POS + 动量语义）】粒子推进方向 =
            //   【连续流向场 · g + 格步方向 · (1-g)】，且 FlowField 的 dirAtWu 含
            //   上游累积动量 ⇒ 上一步的方向会延续 ⇒ 抖动被惯性平滑掉。
            // 【本处的等价实现】在锚点层加【方向惯性】：每个锚点的偏移方向
            //   与【上一锚点的偏移方向】混合（MOMENTUM_FRAC）——上一锚点向左偏、
            //   本锚点若也向左偏则延续，若反向则被压半 ⇒ 相邻折返不再交替满幅。
            double[] offsetX = new double[m], offsetZ = new double[m];
            double lastOx = 0, lastOz = 0;
            double[] ax = new double[m], az = new double[m];
            for (int i = 0; i < m; i++) {
                double wx = originX + (t.cell[i] / nz) * (double) step;
                double wz = originZ + (t.cell[i] % nz) * (double) step;
                // ★★★ 2026-10-21【撤回 contPath —— 它把骨架变成了蜘蛛网】★★★
                //
                // 【曾经想做的】用向量积分得到的浮点坐标当锚点，
                //   让落地不再是 8 个方向（用户：「落地不再是那八个方向」）。
                // 【为什么失败】`traceOne` 里格索引 `k` 按 D8 相邻跳，
                //   而 `curX/curZ` 按方向独立累加 —— 两者<b>没有同步机制</b>
                //   ⇒ 走几百步后完全脱钩 ⇒ 折线画到错误位置
                //   ⇒ 实测生产图变成【满屏交叉直线的蜘蛛网】（100+ 条线互穿）。
                // 【正确做法（未实现）】必须让位置驱动格：
                //   pos += dir·step;  cell = round(pos);  两者始终同步。
                //   那需要重写 traceOne 的主循环，不是加一个数组能解决的。
                // ⇒ 先回到「格心锚点」的可用状态。
                offsetX[i] = 0; offsetZ[i] = 0;
                // ★★★ 2026-10-10【端锚定 —— 治"孤立段"】★★★
                //
                // 【单变量铁证】孤立段（≤60格不接湖海）：三项 PL-RGA 机制 18 个，
                //   【关粒子】12 个 ⇒ **有界粒子贡献 6 个**。
                // 【机理】粒子偏移按链历史（累计方向/动量）计算 ⇒ 同一个【共享格】
                //   （JOIN 汇合点、跨区接力点）在【两条链】里算出不同像素
                //   ⇒ 落块后两条链的像素在共享点处【错开 1~2 格】⇒ 8 连通检测判悬空。
                // 【正解】首尾锚点【不偏移】（钉死格心）—— 与 meander 的"两端淡出
                //   20%"同一思路（那里也是为保证汇合点两侧精确重合），
                //   但这里必须【严格 0】，因为粒子是几何唯一来源。
                //   · i==0（源头）与 i==m-1（河口/JOIN 终点）强制偏移 0；
                //   · 中间锚点照常偏移 ⇒ 梯子纹改善保留。
                boolean anchorEnd = (i == 0 || i == m - 1);
                if (bed != null && !anchorEnd) {
                    int ci = t.cell[i] / nz, cj = t.cell[i] % nz;
                    // bed 双线性梯度（中心差分，界内才有效）
                    double bL = ci > 0 ? bed[(ci - 1) * nz + cj] : bed[t.cell[i]];
                    double bR = ci < nx - 1 ? bed[(ci + 1) * nz + cj] : bed[t.cell[i]];
                    double bD = cj > 0 ? bed[ci * nz + cj - 1] : bed[t.cell[i]];
                    double bU = cj < nz - 1 ? bed[ci * nz + cj + 1] : bed[t.cell[i]];
                    double gx = (bR - bL) * 0.5, gz = (bU - bD) * 0.5;   // ∇bed
                    double gLen = Math.sqrt(gx * gx + gz * gz);
                    if (gLen > 1e-6) {
                        // 下坡单位向量
                        double dx = -gx / gLen, dz = -gz / gLen;
                        // 本格到下一锚点的格步方向（首尾用邻格）
                        int ni = i + 1 < m ? i + 1 : i - 1;
                        double sx = (t.cell[ni] / nz) - ci, sz2 = (t.cell[ni] % nz) - cj;
                        double sLen = Math.sqrt(sx * sx + sz2 * sz2);
                        if (sLen > 1e-6) {
                            sx /= sLen; sz2 /= sLen;
                            // 坡向与格步的正交分量 = 梯子纹的"横档"来源 ⇒ 用它偏移
                            double cross = dx * sz2 - dz * sx;   // 叉积（有符号）
                            // 偏移方向 = 下坡方向在【垂直于格步】上的投影
                            double ox = dx - sx * (dx * sx + dz * sz2);
                            double oz = dz - sz2 * (dx * sx + dz * sz2);
                            double oLen = Math.sqrt(ox * ox + oz * oz);
                            if (oLen > 1e-6) {
                                ox /= oLen; oz /= oLen;
                                // ★ 动量混合（治揉团锯齿）：本锚点偏移方向与上一锚点的
                                //   混合 —— 反向（ox·lastOx < 0）时幅值减半 ⇒ 交替折返
                                //   被压成单侧弧；同向则不受影响。
                                double align = ox * lastOx + oz * lastOz;
                                double amp = PARTICLE_AMP_FRAC * step * Math.min(1.0, Math.abs(cross));
                                if (lastOx != 0 || lastOz != 0) {
                                    if (align < 0) amp *= 0.5;          // 反向折返压半
                                    double mixX = ox + lastOx * MOMENTUM_FRAC;
                                    double mixZ = oz + lastOz * MOMENTUM_FRAC;
                                    double mixLen = Math.sqrt(mixX * mixX + mixZ * mixZ);
                                    if (mixLen > 1e-9) { ox = mixX / mixLen; oz = mixZ / mixLen; }
                                }
                                wx += ox * amp;
                                wz += oz * amp;
                                offsetX[i] = ox * amp;
                                offsetZ[i] = oz * amp;
                                lastOx = ox; lastOz = oz;
                            }
                        }
                    }
                }
                ax[i] = wx;
                az[i] = wz;
            }
            // ★★★ 阶段 C：连续锚点 —— 节点不再钉死格心 ★★★
            //
            // 【依据（节点图实测）】x%4=0 与 z%4=0 各占 4676/4676 = 100%
            //   ⇒ 节点全被量化到 4 块网格，路线只剩 45°/90° 阶梯。
            // 【做法】沿真实坡向（−∇bed）从上一节点推进 step，再用弱弹簧收回格心：
            //   target = 当前格心（上一步已算出的 wx,wz）
            //   p_i    = p_{i-1} + dir_downhill * step
            //   p_i    = p_i + (target − p_i) * ANCHOR_STIFFNESS
            //   首尾仍钉死（JOIN 点重合硬约束）。
            //
            // 【为什么不破坏确定性】dir 只由 bed 的中心差分决定（纯函数），
            //   p_{i-1} 由同一条链顺序推出 ⇒ 同输入必然同输出，无窗口依赖。
            if (CONTINUOUS_ANCHOR && bed != null) {
                for (int i = 1; i < m - 1; i++) {
                    int ci = t.cell[i] / nz, cj = t.cell[i] % nz;
                    double bL = ci > 0 ? bed[(ci - 1) * nz + cj] : bed[t.cell[i]];
                    double bR = ci < nx - 1 ? bed[(ci + 1) * nz + cj] : bed[t.cell[i]];
                    double bD = cj > 0 ? bed[ci * nz + cj - 1] : bed[t.cell[i]];
                    double bU = cj < nz - 1 ? bed[ci * nz + cj + 1] : bed[t.cell[i]];
                    double gx = (bR - bL) * 0.5, gz = (bU - bD) * 0.5;
                    double gLen = Math.sqrt(gx * gx + gz * gz);
                    if (gLen <= 1e-9) continue;                   // 平台：保持原锚点
                    // 沿下坡推进 step
                    double px = ax[i - 1] - (gx / gLen) * step;
                    double pz = az[i - 1] - (gz / gLen) * step;
                    // 弱弹簧收回格心
                    px += (ax[i] - px) * ANCHOR_STIFFNESS;
                    pz += (az[i] - pz) * ANCHOR_STIFFNESS;
                    // 限制最大偏移：不超过 1 个格距（防绕圈/自交）
                    double dxo = px - ax[i], dzo = pz - az[i];
                    double dlen = Math.sqrt(dxo * dxo + dzo * dzo);
                    double cap = step * 1.0;
                    if (dlen > cap) { px = ax[i] + dxo / dlen * cap; pz = az[i] + dzo / dlen * cap; }
                    // ★ 防"节点重叠"：与【上一锚点】至少保持 0.6*step 的间距，
                    //   否则 CR 重采样会在极短段上打结（实测过"揉团锯齿"）。
                    double sepX = px - ax[i - 1], sepZ = pz - az[i - 1];
                    double sep = Math.sqrt(sepX * sepX + sepZ * sepZ);
                    double minSep = step * 0.6;
                    if (sep < minSep && sep > 1e-9) {
                        px = ax[i - 1] + sepX / sep * minSep;
                        pz = az[i - 1] + sepZ / sep * minSep;
                    }
                    ax[i] = px;
                    az[i] = pz;
                }
            }
            // ★ 共享锚点表：同一格索引全链共用同一坐标（PL-RGA nodeForPixel 同构）。
            //   【为什么必须放在粒子循环之后、CR 之前】粒子依赖本链历史（动量），
            //   是"提议"；表是"最终裁决" —— 已登记的格一律以表为准。
            for (int i = 0; i < m; i++) {
                double[] shared = anchorTable.get(t.cell[i]);
                if (shared != null) {
                    ax[i] = shared[0];
                    az[i] = shared[1];
                } else {
                    anchorTable.put(t.cell[i], new double[]{ax[i], az[i]});
                }
            }
            // ★【窗口外续流尾段接回折线】
            //   走出窗口那段（outsideTail）原本被丢弃 ⇒ 河"出了窗口却没画" ⇒ 河格 −14.5%。
            if (t.hasOutsideTail()) {
                double[] tail = t.outsideTail;
                int add = tail.length / 2;
                double[] ex = new double[m + add];
                double[] ez = new double[m + add];
                System.arraycopy(ax, 0, ex, 0, m);
                System.arraycopy(az, 0, ez, 0, m);
                for (int k = 0; k < add; k++) {
                    ex[m + k] = tail[k * 2];
                    ez[m + k] = tail[k * 2 + 1];
                }
                ax = ex; az = ez; m = m + add;
            }
            // ★★★ 2026-10-10【末端伸入水体（治"河距湖/海 1~2 格"的像素缝）】★★★
            //
            // 【ASCII 现场实锤（WaterTouch）】主河网（4399 格 = 全部河像素 20%）与湖水
            //   最近只差 2 块：河末端止步于水像素边界【前 1~2 格】。
            // 【机理】湖/海是**逐块地形/水位**渲染的（1 块精度、边界即水岸线），
            //   而河的折线锚点在**格心**（STEP=4 块）⇒ 末端格心距水岸线可达 2~3 块
            //   ⇒ 落块后像素不相邻。入湖锚点延伸只加了一格（MOUTH_LAKE 的 `p`），
            //   对"岸线斜切格"仍不够。
            // 【修法】把 MOUTH_SEA / MOUTH_LAKE 的折线尾端沿末段方向【再伸 2 格】，
            //   确保穿过水像素边界 ⇒ 与水体 8 连通。
            //   ⚠ 只动几何末端，不改 end 语义/拓扑（SkeletonProbe 判据不受影响）。
            if ((t.end == End.MOUTH_SEA || t.end == End.MOUTH_LAKE) && m >= 2) {
                double dxs = ax[m - 1] - ax[m - 2];
                double dzs = az[m - 1] - az[m - 2];
                double sl = Math.sqrt(dxs * dxs + dzs * dzs);
                if (sl > 1e-6) {
                    dxs /= sl; dzs /= sl;
                    int ext = 2;                     // 伸 2 格（8 块）——实测足够穿过岸线
                    double[] ex = new double[m + ext];
                    double[] ez = new double[m + ext];
                    System.arraycopy(ax, 0, ex, 0, m);
                    System.arraycopy(az, 0, ez, 0, m);
                    for (int k = 0; k < ext; k++) {
                        ex[m + k] = ax[m - 1] + dxs * step * (k + 1);
                        ez[m + k] = az[m - 1] + dzs * step * (k + 1);
                    }
                    ax = ex;
                    az = ez;
                    m = m + ext;
                }
            }
            // ② Catmull-Rom 重采样（1 block 间距）—— 折线变曲线
            double[][] res = catmullRomResample(ax, az, 1.0);
            double[] rx = res[0], rz = res[1];
            // ③ 4 倍频 meander（两端淡出）
            if (params.meanderAmp() > 0.01 && rx.length >= 3) {
                applyMeander(rx, rz, params, oct);
            }
            // ★★★ ④ 格点吸附（2026-10-10 断口根因修复，watch+像素 dump 实锤）★★★
            //
            // 【现象】8 个真断口形态一致：锚点格有水、中间 3 格无水
            //   （(-40,8)→(-36,12) = 一个对角格步，中间 (-39,9)~(-37,11) 空白；
            //    watch: 锚点 RIVER_COL fill=true、中间 bestDist 无命中）。
            // 【根因】Catmull-Rom 曲线在锚点间【弓离对角弦】>0.5 块，
            //   而线扫掠半径 = MIN_HALF_BLOCKS=0.5 ⇒ 中间格点距曲线 >0.5 ⇒ 不染色
            //   ⇒ 像素链在锚点处断开（锚点在曲线上 dist=0 必染色）。
            // 【修法】重采样节点【吸附到整数块格】：节点 dist=0 必染色，
            //   相邻节点舍入后每轴差 ≤2 ⇒ 中间格点距线段 ≤0.45 < 0.5 必覆盖
            //   ⇒ 8 连通链无洞。曲线形状不变（偏移 ≤0.5 块），纯函数确定性不变。
            for (int i = 0; i < 0; i++) {   // 2026-10-21 停用：格点吸附已随超覆盖落块一并移除
                rx[i] = Math.rint(rx[i]);
                rz[i] = Math.rint(rz[i]);
            }
            // ★★★ 2026-10-10【对角针孔填充（8 连通补点）—— 治"河与湖/海隔 1~2 格"】★★★
            //
            // 【机理（WaterTouch + ASCII 现场实锤）】扫掠判据是"格心到线段距离 ≤ half(0.5)"：
            //   对角步（|Δx|=|Δz|=1）时，两个格心之间的**另一侧**格心到线段距离 = 1/√2
            //   ≈ 0.707 > 0.5 ⇒ 不染色 ⇒ 河道留 1 格斜向空洞
            //   ⇒ 8 连通下"算连通"、但渲染像素断开（孤立段检测抓到的正是它）。
            // 【实测】主河网（3329 格 = 全部河像素 20%）与湖水最近只差 2 块；
            //   ASCII 现场：河伸到 img(436,631) 后止步于湖岸前 1 格，
            //   而湖岸在此处是【斜向】的 ⇒ 正是对角针孔。
            // 【修法】把相邻节点的对角步补成"两段正交步"（经过 (x1,z0) 或 (x0,z1)）
            //   ⇒ 新节点与新老节点皆正交相邻 ⇒ 两段中段格心都 ≤0.5 ⇒ 必染色。
            //   等价于 legacy 的"线段栅格化"语义。
            // 2026-10-21：对角拆步停用（超覆盖落块已保证 8 连通，无需量化几何）
            double[][] filled = new double[][]{rx, rz};
            out.add(new Shaped(filled[0], filled[1]));
        }
        return out;
    }

    /**
     * 8 连通补点：相邻节点的【对角步】拆成两段正交步（见调用处说明）。
     *
     * <p>拆点选 {@code (x1,z0)}（先横后竖）—— 与 {@code (x0,z1)} 二选一，
     * 取定后即确定（纯函数）。两段各自：正交相邻 ⇒ 中段格心距线段 = 0.5 ≤ half ⇒ 染色。</p>
     */
    private static double[][] fillDiagonalGaps(double[] rx, double[] rz) {
        if (rx.length < 2) return new double[][]{rx, rz};
        List<double[]> pts = new ArrayList<>(rx.length + 8);
        pts.add(new double[]{rx[0], rz[0]});
        for (int i = 1; i < rx.length; i++) {
            double px = rx[i - 1], pz = rz[i - 1];
            double cx = rx[i], cz = rz[i];
            boolean diag = Math.abs(cx - px) > 0.5 && Math.abs(cz - pz) > 0.5
                    && Math.abs(cx - px) <= 1.5 && Math.abs(cz - pz) <= 1.5;
            if (diag) {
                pts.add(new double[]{cx, pz});   // 先横：保留 x1、沿用 z0
            }
            pts.add(new double[]{cx, cz});
        }
        double[] ox = new double[pts.size()];
        double[] oz = new double[pts.size()];
        for (int i = 0; i < pts.size(); i++) {
            ox[i] = pts.get(i)[0];
            oz[i] = pts.get(i)[1];
        }
        return new double[][]{ox, oz};
    }

    /**
     * Catmull-Rom 重采样（<b>centripetal 参数化，α=0.5</b>），返回 {x[], z[]}。
     *
     * <p>★ 2026-10-10【打转根因修复之一】：第一版用 <b>uniform</b> CR（等距参数化）
     * —— 教科书已知缺陷：Z 字折返处多项式<b>过冲打圈</b>（用户："河流怎么出现这么多
     * 打转的情况"）。centripetal 参数化（节点距离的 α 幂作节点参数）是标准解法：
     * 对凸包性质成立、折返处不产生环。</p>
     *
     * <p>线程安全（局部变量；region 并行求解）。</p>
     */
    private static double[][] catmullRomResample(double[] ax, double[] az, double spacing) {
        int m = ax.length;
        // ★ 容量估算必须用【绝对跨度】—— 下行路径 ax[m-1] < ax[0] 时原式为负
        //   ⇒ ArrayList Capacity -34 崩溃（SkeletonProbe 首轮抓出）。
        double span = Math.abs(ax[m - 1] - ax[0]) + Math.abs(az[m - 1] - az[0]);
        List<double[]> pts = new ArrayList<>((int) (span / spacing) + m + 8);
        pts.add(new double[]{ax[0], az[0]});
        for (int i = 0; i < m - 1; i++) {
            // ★ 端点虚拟点必须用【反射】而非复制（2026-10-10 SkeletonProbe [7] 抓出）：
            //   duplicate ⇒ d=0 ⇒ sqrt(1e-9) 微分母 ⇒ de Casteljau 数值爆炸、节点飞出窗口。
            //   反射 p_prev = 2p0 − p1 ⇒ d = |p0−p1| ≥ 网格步长 ⇒ 恒正。
            double p0x, p0z, p3x, p3z;
            if (i == 0) {
                p0x = 2 * ax[0] - ax[1];
                p0z = 2 * az[0] - az[1];
            } else {
                p0x = ax[i - 1];
                p0z = az[i - 1];
            }
            double p1x = ax[i], p1z = az[i];
            double p2x = ax[i + 1], p2z = az[i + 1];
            if (i + 2 >= m) {
                p3x = 2 * ax[m - 1] - ax[m - 2];
                p3z = 2 * az[m - 1] - az[m - 2];
            } else {
                p3x = ax[i + 2];
                p3z = az[i + 2];
            }
            // centripetal 节点参数：t = 累计 d^0.5（d = 相邻距离；零长段用下界保护）
            double t0 = 0;
            double t1 = t0 + Math.sqrt(Math.max(1e-9, Math.hypot(p1x - p0x, p1z - p0z)));
            double t2 = t1 + Math.sqrt(Math.max(1e-9, Math.hypot(p2x - p1x, p2z - p1z)));
            double t3 = t2 + Math.sqrt(Math.max(1e-9, Math.hypot(p3x - p2x, p3z - p2z)));
            double segLen = Math.hypot(p2x - p1x, p2z - p1z);
            int steps = Math.max(1, (int) Math.ceil(segLen / spacing));
            for (int s = 1; s <= steps; s++) {
                double t = t1 + (t2 - t1) * (s / (double) steps);
                // 逐级 de Casteljau（Barré-Beaujean centripetal CR 标准式）
                double b1x = ((t1 - t) * p0x + (t - t0) * p1x) / (t1 - t0);
                double b1z = ((t1 - t) * p0z + (t - t0) * p1z) / (t1 - t0);
                double b2x = ((t2 - t) * p1x + (t - t1) * p2x) / (t2 - t1);
                double b2z = ((t2 - t) * p1z + (t - t1) * p2z) / (t2 - t1);
                double b3x = ((t3 - t) * p2x + (t - t2) * p3x) / (t3 - t2);
                double b3z = ((t3 - t) * p2z + (t - t2) * p3z) / (t3 - t2);
                double c1x = ((t2 - t) * b1x + (t - t1) * b2x) / (t2 - t1);
                double c1z = ((t2 - t) * b1z + (t - t1) * b2z) / (t2 - t1);
                double c2x = ((t3 - t) * b2x + (t - t2) * b3x) / (t3 - t2);
                double c2z = ((t3 - t) * b2z + (t - t2) * b3z) / (t3 - t2);
                double x = ((t2 - t) * c1x + (t - t1) * c2x) / (t2 - t1);
                double z = ((t2 - t) * c1z + (t - t1) * c2z) / (t2 - t1);
                pts.add(new double[]{x, z});
            }
        }
        double[] xs = new double[pts.size()];
        double[] zs = new double[pts.size()];
        for (int i = 0; i < pts.size(); i++) {
            xs[i] = pts.get(i)[0];
            zs[i] = pts.get(i)[1];
        }
        return new double[][]{xs, zs};
    }

    /**
     * 4 倍频 meander（legacy {@code smoothPath} 同款结构）：
     * 每个节点沿法向偏移 {@code amp · fade · Σ(0.5^o · unitOffset_o)}，
     * 波长逐级减半；两端 20% 弧长 smoothstep 淡出。
     */
    private static void applyMeander(double[] x, double[] z, Params params, Simplex noise) {
        int m = x.length;
        // 累计弧长
        double[] arc = new double[m];
        for (int i = 1; i < m; i++) {
            arc[i] = arc[i - 1] + Math.hypot(x[i] - x[i - 1], z[i] - z[i - 1]);
        }
        double total = arc[m - 1];
        if (total < 1e-6) return;
        double headArc = 0.2 * total, tailArc = 0.2 * total;
        double[] nx2 = new double[m], nz2 = new double[m];
        double[] off = new double[m];        // 法向偏移量（先集中计算，再统一平滑+应用）
        for (int i = 0; i < m; i++) {
            int prev = Math.max(0, i - 1), next = Math.min(m - 1, i + 1);
            double tx = x[next] - x[prev], tz = z[next] - z[prev];
            double tl = Math.hypot(tx, tz);
            if (tl > 1e-6) { tx /= tl; tz /= tl; } else { tx = 1; tz = 0; }
            nx2[i] = -tz; nz2[i] = tx;                      // 左转 90° 法向
            double headFade = smoothstep(Math.min(1.0, arc[i] / Math.max(1e-6, headArc)));
            double tailFade = smoothstep(Math.min(1.0, (total - arc[i]) / Math.max(1e-6, tailArc)));
            double fade = headFade * tailFade;
            if (fade <= 0) continue;
            // ★★★ 打转根因修复之二（2026-10-10，用户："河流怎么出现这么多打转"）★★★
            //   倍频波长有【采样下限】：本曲线 1-block 重采样 ⇒ 波长 <6 块的倍频
            //   （第 4 级 λ=24×0.5³=3 块）每 1.5 个节点就翻转方向 ⇒ 逐点打卷。
            //   legacy 的 meander 作用在更稀的格路径上无此问题；我们改在亚格曲线上
            //   ⇒ 保留 4 倍频结构但截断 λ<6（实际 24/12/6 三级）。
            double sumW = 0;
            double wl = params.meanderWavelength();
            for (int o = 0; o < 4 && wl >= MIN_MEANDER_WAVELENGTH; o++) {
                sumW += Math.pow(0.5, o);
                wl *= 0.5;
            }
            if (sumW <= 0) continue;
            double shape = 0, w = 1;
            double wavelength = params.meanderWavelength();
            for (int o = 0; o < 4 && wavelength >= MIN_MEANDER_WAVELENGTH; o++) {
                shape += w * noise.compute(x[i] / wavelength, z[i] / wavelength);
                w *= 0.5;
                wavelength *= 0.5;
            }
            shape /= sumW;                                   // 归一化 ⇒ 总振幅 = amp
            off[i] = params.meanderAmp() * fade * shape;
        }
        // ★ 轻拉普拉斯平滑 2 遍（只平滑偏移量，两端锚点固定 0 ⇒ 汇合点/源头零位移）：
        //   消除残余高频（λ=6 在急弯处的次像素抖动）。
        for (int pass = 0; pass < 2; pass++) {
            double[] offN = off.clone();
            for (int i = 1; i < m - 1; i++) {
                offN[i] = 0.25 * off[i - 1] + 0.5 * off[i] + 0.25 * off[i + 1];
            }
            offN[0] = 0;
            offN[m - 1] = 0;
            off = offN;
        }
        for (int i = 0; i < m; i++) {
            if (off[i] == 0) continue;
            x[i] += nx2[i] * off[i];
            z[i] += nz2[i] * off[i];
        }
    }

    private static double smoothstep(double t) {
        return t * t * (3 - 2 * t);
    }
}
