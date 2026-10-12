package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.util.Map;

/**
 * 统一水文管线：把已验证的组件串成单一入口。
 *
 * <h2>为什么需要它</h2>
 * <p>此前每个环节都是独立探针：平衡水位、边界出口、水量平衡、伪洼地过滤、河网。
 * 各自有实测支撑，但<b>从未串成一条链跑通</b>。本类是唯一入口，
 * 供生产侧调用与端到端验证。</p>
 *
 * <h2>链路（全部为局部、确定、形状无关）</h2>
 * <pre>
 *   ① 窗口采样地形（含窗外一格的边界地形）
 *   ② 边界出口补全（{@link WindowOutlets}）—— 消除"必须够到海"
 *   ③ 平衡水面（{@link EquilibriumWater}）—— minimax + 收敛判据
 *   ④ 水量平衡（{@link LakeWaterBalance}）—— 来水=蒸发，防沙漠长湖
 *   ⑤ 河网（{@link RiverNetwork}）—— 溢流+径流沿排水树累积，流量定宽
 * </pre>
 *
 * <h2>本类刻意不做的事</h2>
 * <ul>
 *   <li>不改任何方块：只产出"水深/水面/河宽"，供上层决定如何落块。</li>
 *   <li>不声称结果可信：是否可信由 {@code resolved} 判据给出，调用方负责处理未决格。</li>
 *   <li>不做全局稳态外推：窗口半径由调用方给定，超出已验证范围（step=4, radius≤2048）不保证。</li>
 * </ul>
 *
 * <h2>★ 世界查询契约（本项交付，依据见各条括注）</h2>
 * <ol>
 *   <li><b>模型时间 = 无</b>。本管线求的是<b>平衡态</b>，不是时间演化：世界无界 ⇒
 *       没有外部边界、没有起始时刻，时间推进模型无法定义初值。
 *       平衡态是地形的纯函数，故不需要 {@code t}。</li>
 *   <li><b>水位与湖泊是局部量</b>。按 chunk 独立求解（局部窗口）与整窗求解
 *       <b>逐位相同</b>（{@code ChunkSplitContractProbe}：localRadius=768 时
 *       双方可信格 2048/2304，水位/湖泊/河流不一致数均为 0）。</li>
 *   <li><b>窗口必须含真实排水终端</b>。半径不足以致窗口内无海时，{@code resolved}
 *       全为 false（实测 256 半径时 only 22% 可比）。这是<b>方法性限制</b>，
 *       不是数值误差 ⇒ 生产上必须保证局部窗口够大。</li>
 *   <li><b>未决必须可识别且不得当作结论</b>。{@code resolved[k]==false} 表示该格水位
 *       由窗口切割决定 ⇒ 调用方不得据它落块，应标为 pending。</li>
 *   <li><b>河流在窗口边界容许截断</b>。流量沿排水树累加，边界会截断外部上游；
 *       生产上用 {@link RiverNetwork} 的 {@code borderBufferCells} 在边界带不输出河。</li>
 *   <li><b>确定性</b>：同 seed、同配置、同坐标 ⇒ 逐位相同（进程内已断言）；
 *       与查询顺序、缓存命中无关。</li>
 * </ol>
 */
public final class HydrologyPipeline {

    /** 输入契约：中心块坐标、半径（块）、每格块数、气候、（可选）边界缓冲格数。 */
    public record Request(int centerBlockX, int centerBlockZ, int radiusBlocks, int step,
                          LakeWaterBalance.Climate climate, int borderBufferCells,
                          RiverModel riverModel, int streamSamples,
                          double[] extraSource) {
        public Request {
            if (radiusBlocks <= 0) throw new IllegalArgumentException("radius must be > 0");
            if (step <= 0) throw new IllegalArgumentException("step must be > 0");
            if (climate == null) throw new IllegalArgumentException("climate required");
            if (borderBufferCells < 0) throw new IllegalArgumentException("buffer >= 0");
            if (riverModel == null) riverModel = RiverModel.D8;
            if (streamSamples <= 0) streamSamples = 400_000;
        }
        /** 默认 D8（对照基线）。 */
        public Request(int cx, int cz, int radius, int step, LakeWaterBalance.Climate climate) {
            this(cx, cz, radius, step, climate, 0, RiverModel.D8, 400_000, null);
        }
        public Request(int cx, int cz, int radius, int step, LakeWaterBalance.Climate climate,
                       int borderBufferCells) {
            this(cx, cz, radius, step, climate, borderBufferCells, RiverModel.D8, 400_000, null);
        }
        public Request(int cx, int cz, int radius, int step, LakeWaterBalance.Climate climate,
                       int borderBufferCells, RiverModel riverModel) {
            this(cx, cz, radius, step, climate, borderBufferCells, riverModel, 400_000, null);
        }
        public Request(int cx, int cz, int radius, int step, LakeWaterBalance.Climate climate,
                       int borderBufferCells, RiverModel riverModel, int streamSamples) {
            this(cx, cz, radius, step, climate, borderBufferCells, riverModel, streamSamples, null);
        }
    }

    /**
     * @param nx,nz       网格尺寸
     * @param cellBlocks  每格块数
     * @param bed         床面高程
     * @param sea         海格掩码
     * @param resolved    与窗口无关的格（可信）
     * @param level       水面高程
     * @param depth       水深（水量平衡后）
     * @param lake        是否湖泊
     * @param parent      排水父指针
     * @param discharge   出流量
     * @param channel     是否成河
     * @param width       水面半宽（block）
     */
    public record Result(int nx, int nz, int cellBlocks, double[] bed, boolean[] sea,
                         boolean[] resolved, double[] level, double[] depth, boolean[] lake,
                         int[] parent, double[] discharge, boolean[] channel, double[] width,
                         LakeWaterBalance.Result lakeResult, RiverNetwork.Result riverResult,
                         double[] deathMask, double[] waterSurface,
                         int couplingRounds, boolean couplingConverged, double couplingResidual) {

        /**
         * 权威水面高程（含水面有效性）。
         *
         * <p><b>为什么不能直接用 {@link #level()}</b>：`level` 是
         * {@link EquilibriumWater} 的<b>几何逃逸高程</b>（minimax 洪泛），
         * 表示"水刚好能溢出的高度"——它在湖内被抹平、在未决区是窗口残留。
         * 而湖的<b>实际水面</b>由 {@link LakeWaterBalance} 的水量平衡给出
         * （{@code bed + depth}）。两者不同是正常的，错在把前者当最终水面用。</p>
         *
         * <p>⇒ 对外统一从 {@code waterSurface[k]} 取水面；湖格 = {@code bed+depth}，
         * 其余格 = 几何水面 {@code level}。需要区分"是否可信"仍看
         * {@link #resolved()}。</p>
         */
        public double waterSurfaceAt(int k) {
            return waterSurface != null ? waterSurface[k] : level[k];
        }
    }

    /** 湖↔河耦合的最大迭代轮次。
     *  ⚠ 2026-10 实测：first-capture 账本修复后（见 {@link ContinuousRiverNetwork.Ledger}），
     *  正典窗在【第 5 轮】收敛（residual=0）——旧上限 4 轮恰好差一轮（4 轮时 residual=0.36
     *  仍在单调下降）。放大环（每轮 ×5 几何发散）已被账本结构性切断，
     *  故上限只决定"链条长度内传播几轮"，取 6 留余量。诊断旋钮 {@code hydro.couplingMaxRounds}。 */
    private static final int COUPLING_ROUNDS = 6;

    /**
     * 窗口采样缓存开关（`-Dgeogenesis.hydroSampleCache=false` 关；A/B 归因用）。
     *
     * <p>见 {@code run()} 内说明：治"实机进不去世界"（窗口 31684 次 sampleWu
     * 强制冷生成整片侵蚀 tile）。纯函数缓存 ⇒ 产出逐位不变。</p>
     */
    private static final boolean HYDRO_SAMPLE_CACHE =
            !"false".equals(System.getProperty("geogenesis.hydroSampleCache"));

    /**
     * 侵蚀 tile 批量预热开关（`-Dgeogenesis.prewarmTiles=false` 关；A/B 归因用）。
     *
     * <p>见 {@code run()} 内说明：窗口 352×352 wu 跨约 64 个侵蚀 tile，
     * 而 {@code getOrGenTile} 是调用线程同步生成 ⇒ 串行几十秒。预热用池并行。</p>
     */
    /**
     * 侵蚀 tile 批量预热开关（`-Dgeogenesis.prewarmTiles=false` 关；默认<b>开</b>）。
     *
     * <p>⚠ 2026-10-10：{@link #HYDRO_BED_ERODED} 默认 <b>true</b>（含侵蚀 bed，效果优先）
     * ⇒ 窗口求解会触发侵蚀 tile 生成（tile 中心仅 48 wu ⇒ 352×352 wu 跨约 8×8=64 个）
     * ⇒ <b>必须预热</b>用池并行生成，否则单线程串行数十秒（实机「进不去世界」）。</p>
     *
     * <p>本预热是 <b>fire-and-forget</b>（不等待）—— 求解线程按需自己取，
     * {@code getOrGenTile} 的占位式去重保证不重复生成。详见
     * {@code CellGenerator.prewarmErosionTiles} 的 javadoc（含"阻塞版踩坑"记录）。</p>
     */
    private static final boolean PREWARM_TILES =
            !"false".equals(System.getProperty("geogenesis.prewarmTiles"));

    /**
     * ★★★ 2026-10-10【求解地形口径：含侵蚀 sampleWu（用户拍板"效果优先"）】★★★
     *
     * <h3>两条链的生产口径对照（用户要求"对照清楚"）</h3>
     * <table border="1">
     *   <tr><th>环节</th><th>旧链 legacy</th><th>新链 model</th></tr>
     *   <tr><td><b>建网/求解地形</b></td><td>{@code terrainEQuick} 派生（无侵蚀、零 tile）</td>
     *       <td><b>本开关：{@code sampleWu}（含侵蚀）</b></td></tr>
     *   <tr><td>落块列地形</td><td>{@code sampleWu}</td><td>{@code sampleWu}（不变）</td></tr>
     *   <tr><td>淹水基线</td><td>{@code sample()}</td><td>{@code sample()}（不变）</td></tr>
     *   <tr><td>侵蚀施加时机</td><td>落块时叠加</td><td>落块时叠加（不变）</td></tr>
     * </table>
     *
     * <h3>为什么明知有性能代价仍选含侵蚀（三段实测证据）</h3>
     * <ol>
     *   <li><b>效果差距不可补</b>：无侵蚀 bed 让源头数从 37~61 掉到 12~30（−55%），
     *       实测 r=300 河道格 <b>5691 → 2724</b>。</li>
     *   <li><b>方案 A（放宽源头判据）只补回 63%</b>：关 {@code inValleyTrough}
     *       + 门限调优 ⇒ 最多 3601，仍差 37%。</li>
     *   <li><b>方案 B（轻量脊谷增强）完全无效</b>：在 bed 上加"局部下凹加深"
     *       （{@code avg(4邻域) − c}），三种强度（1.5/3.0/6.0）结果逐位相同。
     *       机理：<b>{@code step=4} 的窗口格网采不到亚格沟壑</b>——侵蚀的作用尺度是
     *       几个块，而格网间距就是 4 块 ⇒ 该尺度地形平滑，下凹度恒为 0。</li>
     * </ol>
     * <p>⇒ 剩余 37% 只能靠真实侵蚀地形（唯一替代是降 {@code step} 到 1~2，采样 ×16）。</p>
     *
     * <h3>用户决策（2026-10-10）</h3>
     * <p>「先效果优先，目前性能开销控制在一定范围内就好了。后续再进一步解决性能问题。」
     * ⇒ 重量放在【效果】；性能靠 {@link #PREWARM_TILES} 预热 + 并发修复
     * （见 {@code CellGenerator.getOrGenTile} 的占位式去重）控制在可接受范围。</p>
     *
     * <p><b>开关</b>：{@code -Dgeogenesis.hydroBedEroded=false} 退回无侵蚀口径（仅 A/B 归因）。</p>
     */
    static final boolean HYDRO_BED_ERODED =
            !"false".equals(System.getProperty("geogenesis.hydroBedEroded"));

    private static int couplingMaxRounds() {
        String p = System.getProperty("hydro.couplingMaxRounds");
        if (p == null) return COUPLING_ROUNDS;
        try { return Math.max(1, Integer.parseInt(p)); } catch (NumberFormatException e) {
            return COUPLING_ROUNDS;
        }
    }

    /**
     * 河网模型选择。
     *
     * <ul>
     *   <li>{@link #D8} —— 连通图累积（每格唯一流向）。<b>不是流体模型</b>：
     *       均匀坡面上流向必然平行 ⇒ 平原出现平行直纹（实测）。保留用于对照。</li>
     *   <li>{@link #CONTINUOUS} —— 连续速度场 + 流线积分。已用解析期望验证
     *       （均匀坡面上汇流量与上游面积成正比，比值收敛到 4/16 精确值）。</li>
     * </ul>
     */
    public enum RiverModel { D8, CONTINUOUS }

    /** 两组流量场的最大相对差（耦合收敛判据用的残差）。 */
    private static double relativeResidual(double[] a, double[] b) {
        if (a == null || b == null || a.length != b.length) return Double.POSITIVE_INFINITY;
        double worst = 0;
        for (int k = 0; k < a.length; k++) {
            double scale = Math.max(1e-9, Math.max(Math.abs(a[k]), Math.abs(b[k])));
            worst = Math.max(worst, Math.abs(a[k] - b[k]) / scale);
        }
        return worst;
    }

    /** 两组流量场是否在容差内一致（耦合收敛判据）。 */
    private static boolean nearlyEqual(double[] a, double[] b) {
        return relativeResidual(a, b) <= 1e-6;
    }

    /**
     * 按所选模型计算汇流量场（耦合迭代用）。
     *
     * @param captureOut 【首次截获/边界入流账本】（方案 §4.2 的入流口径）；
     *                   null = 不记账。⚠ 账本只写独立数组，
     *                   <b>不改变 flux/channel/width 的任何一位</b>。
     *                   CONTINUOUS = 流线首触记账；D8 = 边界净入流
     *                   （本地径流 + 组外→组内的跨边界水量，与湖即汇拦截配套）。
     */
    private static double[] buildDischarge(Request request, int n, int step,
                                           WindowOutlets.Result built,
                                           EquilibriumWater.Surface surface,
                                           LakeWaterBalance.Result lakeResult,
                                           double[] captureOut) {
        int cells = n * n;
        if (request.riverModel() == RiverModel.CONTINUOUS) {
            ContinuousRiverNetwork.Ledger ledger = null;
            if (captureOut != null) {
                java.util.Arrays.fill(captureOut, 0);
                int[] outletBasin = new int[cells];
                java.util.Arrays.fill(outletBasin, -1);
                int[] bo = lakeResult.basinOutlet();
                if (bo != null) {
                    for (int b = 0; b < bo.length; b++) {
                        if (bo[b] >= 0 && bo[b] < outletBasin.length) outletBasin[bo[b]] = b;
                    }
                }
                // ⚠ basinId/outletBasin 必须来自【同一份】lakeResult（索引一致性）：
                //   干盆地会在本轮 solve 中被跳过 ⇒ 不同轮次的 basinId 索引可能错位，
                //   而 capture 的求和按【组几何】（solve 内部重扫 isLakePot）而非索引 ⇒ 求和不受错位影响；
                //   只有 sourceBasin==自家 的判定用索引 —— 它与同轮 outletBasin 同源 ⇒ 一致。
                ledger = new ContinuousRiverNetwork.Ledger(
                        lakeResult.basinId(), outletBasin, captureOut);
            }
            var c = ContinuousRiverNetwork.build(built.bed(), surface.level(), surface.resolved(),
                    n, n, step, ContinuousRiverNetwork.Params.forRunoff(lakeResult.runoffPerCell()),
                    request.streamSamples(), 0x5EEDL, request.borderBufferCells(),
                    built.drains(), surface.parent(), lakeResult.lake(), lakeResult.overflowAt(),
                    null, ledger);
            return c.flux();
        }
        // ── D8 骨架（2026-10 切换，方案 §4.2 湖即汇）────────────────────────
        //   ① 拦截组内→组外的累积：湖盆截留入流水，只经 overflowAt 出口下泄；
        //   ② 边界入流账本 = 本地径流 + 组外(basinId<0)→组内的跨边界水量。
        //      ⚠ 只认 basinId<0 的上游：组→组 的边已被 ① 拦截（水不越权穿盆），
        //        若把 G′ 的 discharge 也算进 G 的入流，就是"记了不流的水"。
        int[] basinId = lakeResult.basinId();
        // ★ 成河阈值口径（2026-10-08 诊断旋钮，默认 = 原行为 1.0）：
        //   `-Dgeogenesis.riverThresholdFrac=0.003` ⇒ 按【面积比例】标定（同 legacy 口径）。
        //   用于定位"生产河格 8.41% vs 正典流量掩码 1.90%"是否阈值过低所致。
        double frac = Double.parseDouble(System.getProperty("geogenesis.riverThresholdFrac", "0"));
        RiverNetwork.Params rp = frac > 0
                ? RiverNetwork.Params.byAreaFraction(n, frac)
                : RiverNetwork.Params.defaults();
        var res = RiverNetwork.build(n, n, surface.level(), surface.parent(),
                lakeResult.runoffPerCell(), lakeResult.overflowAt(), surface.resolved(),
                rp, request.borderBufferCells(), basinId, request.extraSource(),
                lakeResult.lake());
        if (captureOut != null && basinId != null) {
            java.util.Arrays.fill(captureOut, 0);
            int[] parent = surface.parent();
            double localRunoff = lakeResult.runoffPerCell();
            for (int k = 0; k < cells; k++) {
                if (basinId[k] >= 0) captureOut[k] = localRunoff;      // 本地径流（留在盆内）
            }
            // ★★ 自环回灌扣除（2026-10-08 实测根因）：
            //   出口格 o 在组外而 parent[o] 在组内（自环 selfLoop）⇒ 注入 o 的本家
            //   outflow 下一轮经 o→parent 成为本家 boundary inflow ⇒ 同一份水记两次
            //   ⇒ I_{n+1} = I_n + c 恒定步长线性增长（实测 ΣIn +192.6/轮 90 轮不止）。
            //   实测相关性完美：增长的 2 个盆地 selfLoop=true，收敛的 false。
            //   修法：跨边界水量中扣掉【本家注入】（仅本家出口格的注入分量 ——
            //   它是回灌的同一份水；他家注入流经此处属合法级联，照常计一次）。
            int[] bo = lakeResult.basinOutlet();
            int[] outletBasin = new int[cells];
            java.util.Arrays.fill(outletBasin, -1);
            if (bo != null) {
                for (int b = 0; b < bo.length; b++) {
                    if (bo[b] >= 0 && bo[b] < cells) outletBasin[bo[b]] = b;
                }
            }
            var overflowAt = lakeResult.overflowAt();
            for (int c = 0; c < cells; c++) {
                if (basinId[c] >= 0) continue;                        // 组内源：不作边界入流
                int p = parent[c];
                if (p < 0 || p >= cells || basinId[p] < 0) continue;
                double v = res.discharge()[c];                         // 组外→组内：跨边界水量
                if (outletBasin[c] == basinId[p]) {                    // 本家出口回灌
                    Double own = overflowAt.get(c);
                    if (own != null) v -= own;
                }
                if (v > 0) captureOut[p] += v;
            }
        }
        return res.discharge();
    }

    private static int countTrue(boolean[] a) {        int c = 0;
        for (boolean b : a) if (b) c++;
        return c;
    }

    private static double maxOf(double[] a) {
        double m = 0;
        for (double v : a) m = Math.max(m, v);
        return m;
    }

    /**
     * ★ 分层受控实验（{@code -Dgeogenesis.hierarchicalHydrology=true}，默认关）的【最小】注入点。
     *
     * <p>只做三件事，不改任何既有默认行为（{@code layer == null} ⇒ 与旧路径逐位相同）：</p>
     * <ul>
     *   <li>{@code terrain} —— 替换床面地形回调（粗层注入 {@code terrainEQuick→heightFromE}，
     *       细层注入 {@code sampleWu}）；</li>
     *   <li>{@code boundary} —— 窗外一格外环的边界条件（细层注入粗层水位接口 =
     *       向下游的权威水位）；返回 NaN ⇒ 退回本层地形；</li>
     *   <li>{@code prewarm} —— 是否预热侵蚀 tile（<b>粗层必须 false</b>：零 tile 铁律）。</li>
     * </ul>
     */
    record Layer(WindowOutlets.Terrain terrain, WindowOutlets.Terrain boundary,
                 boolean prewarm, String tag) { }

    /** 最近一次 {@code run} 的规模/阶段/采样读视图（包内可见，供探针直接读）。 */
    static final class RunStats {
        public int n, nz, cells, step, radiusBlocks, borderCells, prewarmSpanBlocks, couplingRounds;
        public long terrainSamples, boundaryOverrides, boundaryMisses;
        public double prewarmMs, outletsMs, surfaceMs, lakeMs, couplingResidual;
        public boolean couplingConverged;
        public String layerTag = "-";
    }

    private static final RunStats LAST_RUN = new RunStats();

    /** 最近一次 run 的读视图（探针据此写阶段/采样/tile 规模判据）。 */
    static RunStats lastRunStats() { return LAST_RUN; }

    /** 端到端执行。抛出异常仅用于"契约被违反"，不代表结果可信。 */
    public static Result run(CellGenerator gen, TerrainParams tp, Request request) {
        return run(gen, tp, request, null);
    }

    /** 端到端执行（分层实验的最小重载；{@code layer == null} = 旧路径逐位不变）。 */
    static Result run(CellGenerator gen, TerrainParams tp, Request request, Layer layer) {
        double sea = gen.seaLevel(), hs = tp.horizontalScale();
        int step = request.step(), radius = request.radiusBlocks();
        int n = 2 * radius / step + 1;
        // ★ 2026-10-05 收敛单一代码线：原用 worktree 独有的 `erodedHeightForRouting`
        //   （一条"廉价捷径"，声称与 sampleWu().height 等价）。
        //   实测（RoutingHeightEquivalenceProbe）：**不等价** ——
        //     maxErr=217.4 块、平均|err|=37.5 块、99.98% 的点不同、系统性偏高 27.5 块。
        //   ⇒ 改用主工作区【已有且正确】的 `sampleWu().height`。
        //   ⚠ 代价：较慢（走完整 sample()）。若后续性能成问题，
        //     正确做法是修快那条捷径本身并证明等价，而不是用一条未证等价的路径。
        // ★ 坐标契约修正（block → wu）：本类 Request 的坐标全部是【block】
        //   （centerBlockX/radiusBlocks/step=每格块数），而 CellGenerator.sample/sampleWu
        //   的坐标语义是【wu】（wu = block / horizontalScale，见 sample() javadoc
        //   「坐标语义 = wu」与 extractFromTile 的 wu 化核心）。
        //   默认 horizontalScale = 2.0 ⇒ 此前直接传 block 会采到 2 倍错位的地形
        //   （契约测试实测：中心格 bed 期望 134.44、实际 82.69）。
        //   ⚠ 只有【地形采样坐标】需要换算；下游 cellSize/step 保持 block 单位：
        //     cellArea = step² 是 block²（与 MIN_VISIBLE_AREA 同口径）、
        //     速度场梯度 = dY_block/dX_block、Manning 坡度因此一致。
        //     格间距 = step 块（= step/hs wu），但全场高度/面积均以 block 计 ⇒ 自洽。
        // ★★★ 2026-10-10【窗口采样缓存 —— 治"进不去世界"（实机卡死归因）】★★★
        //
        // 【症状】实机新建世界卡死：单 region 求解 15.7 s（同类 0.6 s）、单块 hydro=33 s、
        //   日志 53 s 零输出。
        // 【阶段计时定位（`-Dhydro.stageTimer=true`）】`outlets=15467ms surface=68ms
        //   lakeSolve=22ms` ⇒ **99% 耗时在 WindowOutlets.build 的地形采样**。
        // 【机理】该窗口一次要采 `(nx+1)×(nz+1) = 178² = 31684` 点，每点走
        //   `gen.sampleWu()`（= sampleCore + 气候 + **侵蚀 tile 生成**）。
        //   窗口 704×704 块跨约 3×3 个侵蚀 tile，冷启动时**整片 tile 被迫同步生成**
        //   （单 tile 242~640 ms，实机并发下更慢）⇒ 主线程等不起。
        // 【为什么 legacy 链没这问题】legacy 沿流水线**顺序**采样（缓存命中率高、
        //   tile 逐块生成），而 model 链是**整窗一次性**批量采样 ⇒ 冷启动全撞在一起。
        // 【修法】给采样回调加**直接映射缓存**（`terrainEQuick` 同款范式：
        //   AtomicLongArray 存 double 位模式，先写值后写键；键用 wu 坐标的位模式）。
        //   命中 ~20ns vs 计算几十 µs~ms。
        // 【纯加速保证】`sampleWu` 是纯函数（seed 固定 ⇒ 同坐标同值）⇒
        //   命中与未命中等价 ⇒ **产出逐位不变**。
        // 【开关】`-Dgeogenesis.hydroSampleCache=false` 关（A/B 归因用）。
        // ★ 窗口地形采样计数（分层实验读视图用；⚠ 只在分层路径挂计数包装，
        //   默认路径保持原 lambda 原样 ⇒ 零额外开销、产出逐位不变）。
        final boolean layered = layer != null;
        final java.util.concurrent.atomic.AtomicLong terrainSamples =
                new java.util.concurrent.atomic.AtomicLong();
        final java.util.concurrent.atomic.AtomicLong boundaryUses =
                new java.util.concurrent.atomic.AtomicLong();
        final java.util.concurrent.atomic.AtomicLong boundaryMisses =
                new java.util.concurrent.atomic.AtomicLong();
        final WindowOutlets.Terrain bedTerrain = layered && layer.terrain() != null
                ? layer.terrain()                                               // ★ 分层地形（粗层=零 tile）
                : (HYDRO_BED_ERODED
                // ★ 2026-10-12【height-only】改调 erodedHeight：与 sampleWu().height 逐位等价，
                //   但跳过气候/分类（本回调只读 height）。窗口 31684 点/窗 ⇒ 收益显著。
                ? (bx, bz) -> gen.erodedHeight(bx / hs, bz / hs)          // 侵蚀后地形（等价、轻）
                : (bx, bz) -> gen.sample(bx / hs, bz / hs).height);       // ★ 生产同源（轻）
        final WindowOutlets.Terrain terrainRaw = layered
                ? count(bedTerrain, terrainSamples) : bedTerrain;
        // ★ 窗外一格外环的边界条件（细层注入粗层水位接口 = 向下游的权威；粗层为 null）。
        final WindowOutlets.Terrain boundary = layer == null || layer.boundary() == null ? null
                : count(layer.boundary(), boundaryUses);
        // ★★★ 2026-10-10【方案 B：轻量脊谷增强（零 tile 依赖的"沟壑"替代）】★★★
        //
        // 【动机】方案 A（仅放宽源头判据）实测最多补回 63%（3601 vs 含侵蚀的 5691）——
        //   剩余差距来自【地形本身少了沟壑】：侵蚀在地形上刻出沟槽 ⇒ D8 入度 0 的谷地更多、
        //   汇流更集中。判据放宽补不回"地形上没有的谷"。
        // 【本方案】用【纯 terrainEQuick（零 tile）】做一个廉价的"沟壑增强"：
        //   下凹度 concave(x,z) = 局部均值 − 点值（>0 = 该点比邻域低 = 谷）
        //   ⇒ bed = base − strength × max(0, concave)
        //   即【把已有的浅谷加深】，让 D8 汇流更集中在谷线上 ⇒ 源头/河网恢复密集。
        // 【为什么等价于侵蚀】侵蚀（液滴/骨架）的净效果正是"谷加深、脊削平"；
        //   本算子只做其中的"谷加深"一半，成本是 4 次额外 EQuick 采样（有缓存 ⇒ 极廉价）。
        // 【确定性】纯函数（只依赖 terrainEQuick + 固定参数）⇒ 逐位可复现。
        // 【开关】`-Dgeogenesis.bedConcave=<强度>`（默认 0 = 关闭）；建议 1.5~4.0。
        final double concaveK = Double.parseDouble(
                System.getProperty("geogenesis.bedConcave", "0"));
        final double concaveStepBlocks = Double.parseDouble(
                System.getProperty("geogenesis.bedConcaveStep", "16"));
        WindowOutlets.Terrain terrain = terrainRaw;
        WindowOutlets.Terrain terrainBase = terrain;
        if (concaveK > 0) {
            final WindowOutlets.Terrain t0 = terrain;
            final double ds = concaveStepBlocks;
            terrain = (bx, bz) -> {
                double c = t0.height(bx, bz);
                double avg = 0.25 * (t0.height(bx + ds, bz) + t0.height(bx - ds, bz)
                        + t0.height(bx, bz + ds) + t0.height(bx, bz - ds));
                double concave = avg - c;                 // >0 = 该点比邻域低（谷）
                return concave > 0 ? c - concaveK * concave : c;
            };
        }
        // ⚠ 分层粗层（prewarm=false）跳过本缓存：其地形本身就是 `terrainEQuick`（自带直接映射缓存），
        //   再造一份 6 MB 逐窗缓存纯属浪费 —— 本缓存是纯记忆化 ⇒ 跳过不改变任何产出。
        if (HYDRO_SAMPLE_CACHE && (layer == null || layer.prewarm())) {
            terrain = new WindowOutlets.Terrain() {
                private static final int BITS = 18;               // 262144 槽 ≈ 4 MB
                private static final int SIZE = 1 << BITS;
                private static final int MASK = SIZE - 1;
                private static final long EMPTY = Long.MIN_VALUE;
                private final java.util.concurrent.atomic.AtomicLongArray kx =
                        new java.util.concurrent.atomic.AtomicLongArray(SIZE);
                private final java.util.concurrent.atomic.AtomicLongArray kz =
                        new java.util.concurrent.atomic.AtomicLongArray(SIZE);
                private final java.util.concurrent.atomic.AtomicLongArray vv =
                        new java.util.concurrent.atomic.AtomicLongArray(SIZE);
                {
                    for (int i = 0; i < SIZE; i++) { kx.set(i, EMPTY); kz.set(i, EMPTY); }
                }

                @Override
                public double height(double blockX, double blockZ) {
                    double wx = blockX / hs, wz = blockZ / hs;
                    long a = Double.doubleToRawLongBits(wx);
                    long b = Double.doubleToRawLongBits(wz);
                    int slot = (int) (((a * 0x9E3779B97F4A7C15L) ^ (b * 0x632BE59BD9B4E019L))
                            >>> 40) & MASK;
                    // 先读键（键为 volatile 语义：AtomicLongArray 元素读写原子）
                    if (kx.get(slot) == a && kz.get(slot) == b) {
                        return Double.longBitsToDouble(vv.get(slot));
                    }
                    double h = terrainBase.height(blockX, blockZ);
                    // 写入次序：先写值、后写键（读者读到两键即保证值已写入）
                    vv.set(slot, Double.doubleToRawLongBits(h));
                    kz.set(slot, b);
                    kx.set(slot, a);
                    return h;
                }
            };
        }
        // ★ 分层细层：窗外一格外环的边界条件由【粗层水位接口】给出（下游权威）。
        //   ⚠ 判据必须与 WindowOutlets 的采样坐标【精确】对齐，否则要么漏掉窗外环
        //   （边界条件失效）、要么误改窗内（产出被偷偷改动）。逐点核对：
        //     · 谷底角点（含窗内格心）：x = cx − radius − step/2 … cx + radius + step/2
        //     · 窗外环（exterior）：x = cx − radius − step 与 cx + radius + step
        //   ⇒ 窗外 ⟺ |dx| > radius + step/2（严格，含 1e-9 容差以排除恰好落在角点者）。
        if (boundary != null) {
            final WindowOutlets.Terrain inner = terrain;
            final double edgeX = radius + step * 0.5;
            final int cx = request.centerBlockX(), cz = request.centerBlockZ();
            terrain = (bx, bz) -> {
                if (!(Math.abs(bx - cx) > edgeX + 1e-9 || Math.abs(bz - cz) > edgeX + 1e-9)) {
                    return inner.height(bx, bz);        // 窗内（含角点）⇒ 本层地形，逐位不变
                }
                double lv = boundary.height(bx, bz);
                if (Double.isFinite(lv)) return lv;     // 粗层水位权威（下游接口）
                boundaryMisses.incrementAndGet();
                return inner.height(bx, bz);
            };
        }

        // ①② 地形 + 边界出口
        long __t0 = System.nanoTime();
        // ★ 阶段计时（`-Dhydro.stageTimer=true`）：定位 region 求解的耗时分布。
        //   背景：2026-10-10 实机「进不去世界」——region (0,-1) 求解达 15.7 s
        //   （同类 region 仅 0.6 s），且与 basins=21 强相关；需逐段量化。
        final boolean STAGE_TIMER = Boolean.getBoolean("hydro.stageTimer");
        // ★★★ 2026-10-10【侵蚀 tile 批量预热 —— 治"进不去世界"的第二半】★★★
        //   窗口一次采 31684 点 × 每点触发 getOrGenTile（tile 中心仅 48 wu
        //   ⇒ 352×352 wu 跨约 8×8=64 个 tile），而 getOrGenTile 是【调用线程同步生成】
        //   ⇒ 单线程串行跑 64 次完整侵蚀。此处用 TILE_SAMPLER 池并行预热 ⇒ 采样全命中。
        //   纯加速（tile 是纯函数）⇒ 产出逐位不变。
        //   ⚠ 分层粗层显式 prewarm=false ⇒ 走 terrainEQuick（零 tile）⇒ 绝不预热。
        final boolean doPrewarm = layer == null ? PREWARM_TILES : layer.prewarm();
        if (doPrewarm) {
            gen.prewarmErosionTiles(request.centerBlockX() - radius,
                    request.centerBlockZ() - radius, 2.0 * radius, hs);
        }
        long __tp = System.nanoTime();
        if (STAGE_TIMER) {
            System.out.printf("  [stage] prewarm=%.0fms%n", (__tp - __t0) / 1e6);
        }
        var built = WindowOutlets.build(n, n, request.centerBlockX(), request.centerBlockZ(),
                radius, step, sea, terrain);
        long __t1 = System.nanoTime();

        // ③ 平衡水面
        EquilibriumWater.Surface surface = EquilibriumWater.compute(
                built.bed(), n, n, built.drains(), built.exterior());
        long __t2 = System.nanoTime();
        if (STAGE_TIMER) {
            System.out.printf("  [stage] outlets=%.0fms surface=%.0fms%n",
                    (__t1 - __t0) / 1e6, (__t2 - __t1) / 1e6);
        }

        // ★ 平坦区破平局（诊断层 A/B，默认 baseline = 逐位不变）。
        //   背景：minimax 洪泛在平坦高原上令大片 level 全等 ⇒ ∇η=0 ⇒ 速度场归零 ⇒
        //   流线打转/只剩 EDT 径向。FastFlow Alg.6 的解法是沿受主图加 ε 单调坡度。
        //   注意：只改【水面】，不改【床面】—— ε 是水面性质，不是地形性质。
        //   ⚠ 边界：此处只改 surface.level()，而 surface.parent() 是按【原 level】算出的。
        //     施加 ε 不会让 parent 失效（parent 已由严格字典序保证无环），
        //     但"level 与 parent 的降序关系"可能被 ε 抬高到极小量级 —— 这是预期内的。
        if (DirectionRule.epsilonSlope()) {
            DirectionRule.applyEpsilonSlope(surface.level(), surface.parent(),
                    built.drains(), n * n);
        }
        if (DirectionRule.noise()) {
            FlatSurfaceJitter.apply(built.bed(), n, n, 0x5EEDL);
        }

        boolean[] borderMask = new boolean[n * n];
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < n; j++) {
                borderMask[i * n + j] = i == 0 || j == 0 || i == n - 1 || j == n - 1;
            }
        }

        // ④⑤ 湖 ↔ 河【耦合迭代】：湖泊水位取决于来水，而来水由河网汇流给出。
        //   这正是"湖河是同一套水量平衡两端"的实现（用户提问驱动）：
        //   单次求解时湖只能按"汇水格数"估来水（不含沿程损失）；
        //   迭代后湖水位与【真实到达水量】一致，且溢流又反哺下游。
        //   收敛判据：水位变化 < 1e-6 或达到最大轮次（未收敛则如实保留最后结果）。
        LakeWaterBalance balance = new LakeWaterBalance(n, n, step, built.bed(), surface);
        // ★ 2026-10-09【闭式水位场（WaterField）接线已实测否决，见 LakeWaterBalance.solve 的注释】
        //   结论：WaterField 是 legacy 链 `WaterLevelSolver` 的"水位数据源"，
        //   而 model 链的湖是**盆地机制**（需 sill/rim/resolved 才能蓄水成盆）
        //   ⇒ 把"水位源"当"湖生成器"用属概念错位，实测只出零散水坑。
        //   类与 LAKE_FIELD_MODE 开关保留（默认 false，无调用点）供将来参考。
        LakeWaterBalance.Result lakeResult =
                balance.solve(request.climate(), built.drains(), borderMask,
                        null, request.extraSource());
        if (STAGE_TIMER) {
            System.out.printf("  [stage] lakeSolve=%.0fms%n", (System.nanoTime() - __t2) / 1e6);
        }
        double[] flow = null;
        RiverNetwork.Result river = null;
        int rounds = 0;
        // ★ 收敛必须是【可判定】的，而不是"跑满轮次就算完"（方案 §P1 明确要求）。
        //   迭代上限用尽 ≠ 收敛 ⇒ 必须把 rounds / converged / residual 一并回传，
        //   让调用方（与门禁）能区分"收敛"与"预算用尽"。
        boolean converged = false;
        double residual = Double.NaN;
        // 首次截获/边界入流账本：solve 的来水口径 = Σ capture over group（方案 §4.2），
        // 不再是 Σ flux/discharge over group（每格累积重复计水 ⇒ 几何发散，见各 Ledger javadoc）。
        //   CONTINUOUS = 流线首触；D8 = 边界净入流（与湖即汇拦截配套）。
        //   ⚠ 诊断旋钮 hydro.ledgerOff=true ⇒ 退回旧口径（反向验证判据用：
        //     CONTINUOUS 实测 14 轮 Σ来水 8058× ⇒ FAIL；D8 实测 6 轮 1.3e14 ⇒ FAIL）。
        double[] capture = new double[n * n];
        boolean ledgerOn = !Boolean.getBoolean("hydro.ledgerOff");
        for (int it = 0; it < couplingMaxRounds(); it++) {
            double[] next = buildDischarge(request, n, step, built, surface, lakeResult,
                    ledgerOn ? capture : null);
            boolean same = flow != null && nearlyEqual(flow, next);
            residual = flow == null ? Double.NaN : relativeResidual(flow, next);
            // ★ 逐轮取证（hydro.couplingDiagnostics）：worst-case 相对残差被【近零格】主导 ——
            //   1e-6 vs 1e-5 的格相对差 0.9，但绝对差可忽略 ⇒ 必须同时看
            //   『显著格』（scale ≥ 1e-3·maxFlux）的残差与两侧规模，否则会把
            //   度量噪声误读成"物理不收敛"。
            if (System.getProperty("hydro.couplingDiagnostics") != null) {
                double mx = maxOf(next);
                double worstSig = 0, worstAbs = 0;
                int sigCells = 0, sigBad = 0, lakeCells = countTrue(lakeResult.lake());
                int worstK = -1;
                for (int k = 0; k < next.length; k++) {
                    double sc = Math.max(Math.abs(flow == null ? 0 : flow[k]), Math.abs(next[k]));
                    if (flow != null) {
                        double ad = Math.abs(flow[k] - next[k]);
                        if (ad > worstAbs) { worstAbs = ad; worstK = k; }
                        if (sc >= 1e-3 * mx) {
                            sigCells++;
                            double rel = Math.abs(flow[k] - next[k]) / Math.max(1e-9, sc);
                            worstSig = Math.max(worstSig, rel);
                            if (rel > 1e-6) sigBad++;
                        }
                    }
                }
                // ★ 放大环定位：盆地账本总量 + 最恶格身份（本轮实测 maxFlux 每轮 ×5 ⇒
                //   必须知道是"总出流在涨"还是"某单格在涨"，以及涨的是不是出口格）。
                double sumIn = 0, sumOut = 0, sumOv = 0;
                for (var b : lakeResult.basins()) { sumIn += b.inflow(); sumOut += b.outflow(); }
                for (double v : lakeResult.overflowAt().values()) sumOv += v;
                String worst = worstK < 0 ? "-" : String.format(
                        "(%d,%d) f=%.2f→n=%.2f lake=%b out=%b",
                        worstK / n, worstK % n, flow[worstK], next[worstK],
                        lakeResult.lake()[worstK],
                        lakeResult.overflowAt().containsKey(worstK));
                System.out.printf(
                        "  [couple#%d] residual=%.3e sig=%d/%d maxAbs=%.1f maxFlux=%.1f "
                                + "lake=%d | ΣIn=%.1f ΣOut=%.1f ΣOv=%.1f | worst=%s%n",
                        it, residual, sigBad, sigCells, worstAbs, mx, lakeCells,
                        sumIn, sumOut, sumOv, worst);
                // ★★ 吃水大户定位（放大环在哪个盆地）：打印 inflow 最大的 3 个盆地 ——
                //   seed/outlet 坐标 + 其组内 flux 前 3 格。若某盆地 inflow 主要来自
                //   【自己出口注入的流线又回到自己组内】⇒ 自环放大；若来自上游出口格
                //   ⇒ 级联重复计数。
                if (lakeResult.basins().size() > 0) {
                    var sorted = new java.util.ArrayList<>(lakeResult.basins());
                    sorted.sort((a, b) -> Double.compare(b.inflow(), a.inflow()));
                    int basinId = 0;
                    for (var b : sorted) {
                        if (basinId++ >= 3) break;
                        if (b.inflow() <= 0) continue;
                        // 该盆地的格集 = basinId 匹配（借 lake 掩码不行：组是连通分量）
                        int seedCell = b.seedCell();
                        // 组内 flux 前 3：借 basinId 数组定位同组格
                        double[][] top = new double[3][];
                        for (int t = 0; t < 3; t++) top[t] = new double[]{-1, -1};
                        int bid = lakeResult.basinId() != null && seedCell >= 0
                                && seedCell < lakeResult.basinId().length
                                ? lakeResult.basinId()[seedCell] : -1;
                        double topSum = 0;
                        int topN = 0;
                        if (bid >= 0) {
                            for (int k = 0; k < next.length; k++) {
                                if (lakeResult.basinId()[k] != bid) continue;
                                double f = next[k];
                                if (f <= 0) continue;
                                topSum += f;   // 注：topSum 累计全组（= inflow 口径）
                                topN++;
                                // 简易 top3 插入
                                for (int t = 0; t < 3; t++) {
                                    if (f > top[t][1]) {
                                        for (int u = 2; u > t; u--) top[u] = top[u - 1];
                                        top[t] = new double[]{k, f};
                                        break;
                                    }
                                }
                            }
                        }
                        System.out.printf(
                                "    [top basin] seed=(%d,%d) outlet=%s inflow=%.1f outflow=%.1f "
                                        + "ov=%b | 组内flux格=%d Σ=%.1f | top3=%s%n",
                                seedCell / n, seedCell % n,
                                b.outflow() > 0 ? "有" : "无", b.inflow(), b.outflow(), b.overflow(),
                                topN, topSum,
                                String.format("(%d,%.0f)(%d,%.0f)(%d,%.0f)",
                                        (int) top[0][0] / n, top[0][1],
                                        (int) top[1][0] / n, top[1][1],
                                        (int) top[2][0] / n, top[2][1]));
                        // ★ 自环假设判定（2026-10-08 实测驱动）：ΣIn 恒定 +192.6/轮线性增长
                        //   ⇒ 形如 I_{n+1} = I_n + c ⇒ 每轮有【固定新水】进入某组。
                        //   最强嫌疑：出口 rim 的 parent 指回自家组内 ⇒ 注入的 outflow
                        //   下一轮又成为本家 boundary inflow（自我循环）。
                        //   判据：parent[outlet] 仍属本组 ⇒ 自环；再打 parent 指向。
                        if (bid >= 0 && lakeResult.basinOutlet() != null
                                && bid < lakeResult.basinOutlet().length) {
                            int oc = lakeResult.basinOutlet()[bid];
                            int[] par = surface.parent();
                            if (oc >= 0 && oc < par.length) {
                                int po = par[oc];
                                int pBasin = po >= 0 && lakeResult.basinId() != null
                                        && po < lakeResult.basinId().length
                                        ? lakeResult.basinId()[po] : -1;
                                System.out.printf(
                                        "      [outlet?] cell=(%d,%d) parent=(%d,%d) parentBasin=%d"
                                                + " selfLoop=%b%n",
                                        oc / n, oc % n,
                                        po >= 0 ? po / n : -1, po >= 0 ? po % n : -1,
                                        pBasin, pBasin == bid);
                            }
                        }
                    }
                }
            }
            flow = next;
            rounds = it + 1;
            if (same) { converged = true; break; }
            // ★ 来水口径切换（核心修复）：solve 的 inflow = Σ capture over 组。
            //   CONTINUOUS = 首次截获；D8 = 边界净入流（与湖即汇拦截配套）。
            //   旧口径 Σ flux/discharge over 组 = 每格累积重复计水 ⇒ 几何发散
            //   （实测反向验证：CONTINUOUS 14 轮 8058×、D8 6 轮 1.3e14 ⇒ 判据 FAIL）。
            lakeResult = balance.solve(request.climate(), built.drains(), borderMask,
                    ledgerOn ? capture : flow);
        }
        // 最终一轮：产出与所选模型一致的河网结果（含宽度/成河掩码）
        double[] deathMask = null;
        if (request.riverModel() == RiverModel.CONTINUOUS) {
            deathMask = new double[n * n];   // ★ 诊断：流线死亡格计数（河道碎块归因用）
            var c = ContinuousRiverNetwork.build(built.bed(), surface.level(), surface.resolved(),
                    n, n, step, ContinuousRiverNetwork.Params.forRunoff(lakeResult.runoffPerCell()),
                    request.streamSamples(), 0x5EEDL, request.borderBufferCells(),
                    built.drains(), surface.parent(), lakeResult.lake(), lakeResult.overflowAt(),
                    deathMask);
            river = new RiverNetwork.Result(c.flux(), c.channel(), c.width(),
                    c.channelCells(), c.maxFlux(), 0, 0);
        } else {
            // ★ 与耦合迭代同一语义（湖即汇拦截 + 出口注入）与同一阈值参数，
            //   否则输出口径与账本口径分叉。
            double fracF = Double.parseDouble(System.getProperty("geogenesis.riverThresholdFrac", "0"));
            RiverNetwork.Params rpF = fracF > 0
                    ? RiverNetwork.Params.byAreaFraction(n, fracF)
                    : RiverNetwork.Params.defaults();
            river = RiverNetwork.build(n, n, surface.level(), surface.parent(),
                    lakeResult.runoffPerCell(), lakeResult.overflowAt(), surface.resolved(),
                    rpF, request.borderBufferCells(),
                    lakeResult.basinId(), request.extraSource(), lakeResult.lake());
        }
        // ★ 耦合诊断（系统属性开启；默认不打印）：每轮湖格数与流量规模。
        //   加它的原因：耦合后 lake 从数千骤降到几百，**原因未知** ⇒ 必须打印取证，不得靠猜。
        if (System.getProperty("hydro.couplingDiagnostics") != null) {
            System.out.printf("  [couple] rounds=%d converged=%b residual=%.3e finalLakeCells=%d"
                            + " basins=%d dryBasins=%d maxFlow=%.3f%n",
                    rounds, converged, residual, countTrue(lakeResult.lake()),
                    lakeResult.basins().size(), lakeResult.dryBasins(),
                    river == null ? 0 : maxOf(river.discharge()));
        }

        // ★ 权威水面：湖格取水量平衡面（bed+depth），其余取几何水面。
        //   对外不再让调用方去猜"level 到底是不是最终水面"。
        double[] waterSurface = new double[n * n];
        boolean[] isLake = lakeResult.lake();
        double[] lakeDepth = lakeResult.depth();
        for (int k = 0; k < waterSurface.length; k++) {
            waterSurface[k] = isLake[k] ? built.bed()[k] + lakeDepth[k] : surface.level()[k];
        }

        // ★ 分层实验读视图发布（阶段/采样/tile 规模；默认路径也发布，探针可对照）。
        synchronized (LAST_RUN) {
            LAST_RUN.n = n; LAST_RUN.nz = n; LAST_RUN.cells = n * n;
            LAST_RUN.step = step; LAST_RUN.radiusBlocks = radius;
            LAST_RUN.borderCells = built.borderCells();
            LAST_RUN.prewarmSpanBlocks = doPrewarm ? 2 * radius : 0;
            LAST_RUN.couplingRounds = rounds;
            LAST_RUN.couplingConverged = converged;
            LAST_RUN.couplingResidual = residual;
            LAST_RUN.terrainSamples = terrainSamples.get();
            LAST_RUN.boundaryOverrides = boundaryUses.get();
            LAST_RUN.boundaryMisses = boundaryMisses.get();
            LAST_RUN.prewarmMs = (__tp - __t0) / 1e6;
            LAST_RUN.outletsMs = (__t1 - __t0) / 1e6;
            LAST_RUN.surfaceMs = (__t2 - __t1) / 1e6;
            LAST_RUN.lakeMs = 0;
            LAST_RUN.layerTag = layer == null ? "-" : layer.tag();
        }
        if (boundary != null) {     // 细层：窗外边界条件由粗层权威覆盖的规模（如实计数）
            long uses = boundaryUses.get(), misses = boundaryMisses.get();
            HierarchicalHydrology.Diag.exteriorAuthority((int) (uses - misses), (int) misses);
        }
        if (layer != null) {        // 分层阶段留痕（探针读；粗/细各一条）
            HierarchicalHydrology.Diag.layer(layer.tag(), "prewarmMs", (__tp - __t0) / 1_000_000L);
            HierarchicalHydrology.Diag.layer(layer.tag(), "outletsMs", (__t1 - __t0) / 1_000_000L);
            HierarchicalHydrology.Diag.layer(layer.tag(), "surfaceMs", (__t2 - __t1) / 1_000_000L);
            HierarchicalHydrology.Diag.layer(layer.tag(), "prewarmSpan", doPrewarm ? 2 * radius : 0);
            HierarchicalHydrology.Diag.layer(layer.tag(), "samples", terrainSamples.get());
            HierarchicalHydrology.Diag.layer(layer.tag(), "cells", (long) n * n);
        }
        return new Result(n, n, step, built.bed(), built.drains(), surface.resolved(),
                surface.level(), lakeResult.depth(), lakeResult.lake(), surface.parent(),
                river.discharge(), river.channel(), river.width(), lakeResult, river, deathMask,
                waterSurface, rounds, converged, residual);
    }

    /** 采样计数器包装（只读视图；不改产出）。 */
    private static WindowOutlets.Terrain count(WindowOutlets.Terrain t,
                                               java.util.concurrent.atomic.AtomicLong sink) {
        return (bx, bz) -> { sink.incrementAndGet(); return t.height(bx, bz); };
    }

    /** 便捷统计，供探针与上层日志使用。 */
    public static String summarize(Result r) {
        int lake = 0, channel = 0, resolved = 0;
        for (int k = 0; k < r.nx() * r.nz(); k++) {
            if (r.lake()[k]) lake++;
            if (r.channel()[k]) channel++;
            if (r.resolved()[k]) resolved++;
        }
        Map<Integer, Double> overflow = r.lakeResult().overflowAt();
        return String.format("cells=%d resolved=%d(%.1f%%) lake=%d channel=%d overflowPoints=%d maxQ=%.2f",
                r.nx() * r.nz(), resolved, 100.0 * resolved / (r.nx() * (double) r.nz()),
                lake, channel, overflow.size(), r.riverResult().maxDischarge());
    }

    private HydrologyPipeline() { }
}
