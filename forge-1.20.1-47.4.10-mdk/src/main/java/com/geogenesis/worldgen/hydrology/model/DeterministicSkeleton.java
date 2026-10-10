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
        /** 走到 parent=-1 且非海非湖（理论不应发生，防御）。 */
        DEAD_END
    }

    /** 一条河段：格索引序列（含首尾）+ 终止类型。 */
    public static final class Trace {
        public final int[] cell;
        public final End end;
        /** 是否由溢出口起新段（湖后段）。 */
        public final boolean fromOutlet;

        Trace(int[] cell, End end, boolean fromOutlet) {
            this.cell = cell;
            this.end = end;
            this.fromOutlet = fromOutlet;
        }

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

        Result(List<Trace> traces, int sourcesTotal, int sourcesDroppedBySpacing,
               int outletStarts, int incomingStarts, List<ExitSeed> exitSeeds) {
            this.traces = traces;
            this.sourcesTotal = sourcesTotal;
            this.sourcesDroppedBySpacing = sourcesDroppedBySpacing;
            this.outletStarts = outletStarts;
            this.incomingStarts = incomingStarts;
            this.exitSeeds = exitSeeds;
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
     * 有界粒子最大偏移（× step）。legacy {@code PARTICLE_MAX_DEV_FRAC=0.45}，
     * 我方实测 0.45 在 1-block 扫掠半径下让相邻段互相贴近 ⇒ 默认 0.25。
     */
    static final double PARTICLE_AMP_FRAC =
            Double.parseDouble(System.getProperty("geogenesis.particleAmp", "0.25"));

    /** 平地破平局开关（`-Dgeogenesis.flatBreak=false` 关；归因"揉团锯齿"用）。 */
    static final boolean FLAT_BREAK =
            !"false".equals(System.getProperty("geogenesis.flatBreak"));

    /**
     * ★ 网络级连通性过滤开关（`-Dgeogenesis.networkFilter=false` 关；A/B 归因用）。
     *
     * <p>把 trace 按共享格并查集聚簇，只保留「簇内有链通向海/湖/窗口接力」的簇
     * ⇒ 结构上消除孤立河段（用户判据：不应出现"既不连湖也不入海"的河）。</p>
     */
    static final boolean NETWORK_FILTER =
            !"false".equals(System.getProperty("geogenesis.networkFilter"));

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
        int[] selfStamp = new int[cells];
        int[] selfStep = new int[cells];        // 自贴近豁免用：格 → 该 trace 内步序号
        int[] stampBox = {0};
        // ★ 交叉守卫用：已画线段集合（格坐标 {ax,ay,bx,by}）——
        //   legacy `allSegments`（`nearbyDownhillNode` 的 `_wouldCrossExistingSegments`）同义。
        java.util.List<int[]> segments = new java.util.ArrayList<>();
        int sourceTraces = 0;
        int incomingStarts = 0;
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
                if (start < 0 || start >= cells) continue;      // 接力种子防御
                boolean fromOutlet = pass == 1;
                boolean fromIncoming = pass == 0;
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
                if (covered[start]) continue;
                // 接力种子落在湖/海格 ⇒ 该处水体已由湖/海表达，无需再画河。
                // ★ 只对【接力】生效 —— 源头/溢口的 lake/sea 过滤不在本类（改动会变基线产出）。
                if (fromIncoming && (lakeMask[start] || sea[start])) continue;
                int stamp = ++stampBox[0];
                Trace t = traceOne(start, fromOutlet, level, parent, resolved,
                        lakeMask, sea, covered, nx, nz, params.joinWindow(),
                        selfStamp, selfStep, stamp, params, segments, bed);
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
        if (NETWORK_FILTER && !traces.isEmpty()) {
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
        return new Result(traces, sourcesTotal, droppedBySpacing, outletStarts,
                incomingStarts, exitSeeds);
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
    private static List<Integer> collectSources(double[] level, int[] parent, boolean[] resolved,
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
        List<Integer> sources = new ArrayList<>();
        for (int k = 0; k < cells; k++) {
            if (!channel[k] || hasUpstream[k]) continue;
            // 汇流槽判据（legacy inValleyTrough）：垂直于下坡方向两侧任一更低 ⇒ 凸坡否决
            if (!inValleyTrough(level, parent, k, nx, nz)) continue;
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
    private static Trace traceOne(int start, boolean fromOutlet,
                                  double[] level, int[] parent, boolean[] resolved,
                                  boolean[] lakeMask, boolean[] sea, boolean[] covered,
                                  int nx, int nz, int joinWindow,
                                  int[] selfStamp, int[] selfStep, int stamp, Params params,
                                  java.util.List<int[]> segments, double[] bed) {
        int cells = nx * nz;
        List<Integer> path = new ArrayList<>();
        int k = start;
        End end = End.DEAD_END;
        // ★ 2026-10-10【丢弃时必须只释放"本链新覆盖的格"】——
        //   末位 JOIN 终点是【别的链的节点】（covered 早已为 true）⇒ 若整条 path 一律
        //   置 false，会把别人已占的内部节点释放掉 ⇒ 后继链穿过它 = SkeletonProbe [4] 双占。
        //   （实测：minNodes 丢弃逻辑加"整条释放"后 [4] 立刻报 4 个格双占。）
        int newlyClaimed = 0;
        while (true) {
            path.add(k);
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
                    if (sea[esc]) { path.add(esc); covered[esc] = true; end = End.MOUTH_SEA; break; }
                    k = esc;
                    continue;
                }
                // 逃逸失败（盆外无下坡/全被湖占）⇒ 沿原 parent 兜底（可能单格终止）
            }
            // 就近汇入：窗口内找「已 covered 且 level 不高于当前」的最优节点
            //   （排除本 trace 自己的节点 —— 否则平坦区会 join 到自己、提前终止+折返）
            int join = nearbyJoin(level, k, nx, nz, covered, joinWindow, selfStamp, stamp,
                    segments, bed);
            if (join >= 0) {
                path.add(join);
                end = End.JOIN;
                break;
            }
            int p = parent[k];
            if (p < 0 || p >= cells || p == k) {
                end = sea[k] ? End.MOUTH_SEA
                        : (lakeMask[k] ? End.MOUTH_LAKE : End.DEAD_END);
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
            if (FLAT_BREAK && bed != null && level[p] >= level[k] - 1e-9 && level[p] <= level[k] + 1e-9) {
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
                    if (bed[c] < altBed - 1e-9) { altBed = bed[c]; alt = c; }
                }
                if (alt >= 0) {
                    // ★★★ 2026-10-10【步骤级交叉守卫（PL-RGA wouldCross 同构）】★★★
                    // PL-RGA 在【每一个候选步】上跑 wouldCross（rivers.cpp:183），
                    //   而不只在接点时（rivers.cpp:233）。我们此前只有后者 ⇒
                    //   破平局跳向别条沟时会横穿已有河段 ⇒ 图上"反复交叉"。
                    // 【为什么不会误判自己】本链的段在 traceOne 返回【之后】才登记进
                    //   segments（见 build 的登记循环）⇒ 追踪期间 segments 只含【别人】的段
                    //   —— 这正是 PL-RGA "跳过共享端点段"的等价效果。
                    if (!segments.isEmpty()
                            && segmentCrossesAny(bi, bj, alt / nz, alt % nz, segments)) {
                        end = End.DEAD_END;
                        break;
                    }
                    k = alt;
                    continue;      // 走真实下坡（等水位区内），本轮不前进 parent
                }
                // 无更低 bed 邻居（真平台）⇒ 沿 parent（径向兜底）
            }
            // ★ 步骤级交叉守卫（parent 迈步；同 PL-RGA wouldCross）
            if (!segments.isEmpty()) {
                int ki = k / nz, kj = k % nz, pi = p / nz, pj = p % nz;
                if (segmentCrossesAny(ki, kj, pi, pj, segments)) {
                    end = End.DEAD_END;
                    break;
                }
            }
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
        if ((path.size() < minNodes || orphanDeadEnd) && !fromOutlet) {
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
        return new Trace(toArray(path), end, fromOutlet);
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
        return ((o1 > 0) != (o2 > 0)) && ((o3 > 0) != (o4 > 0));
    }

    private static int orientation(int ax, int ay, int bx, int by, int cx, int cy) {
        return (bx - ax) * (cy - ay) - (by - ay) * (cx - ax);
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
            for (int i = 0; i < rx.length; i++) {
                rx[i] = Math.rint(rx[i]);
                rz[i] = Math.rint(rz[i]);
            }
            out.add(new Shaped(rx, rz));
        }
        return out;
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
