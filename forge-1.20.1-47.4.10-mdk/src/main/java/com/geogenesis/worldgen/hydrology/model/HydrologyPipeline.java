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
                          RiverModel riverModel, int streamSamples) {
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
            this(cx, cz, radius, step, climate, 0, RiverModel.D8, 400_000);
        }
        public Request(int cx, int cz, int radius, int step, LakeWaterBalance.Climate climate,
                       int borderBufferCells) {
            this(cx, cz, radius, step, climate, borderBufferCells, RiverModel.D8, 400_000);
        }
        public Request(int cx, int cz, int radius, int step, LakeWaterBalance.Climate climate,
                       int borderBufferCells, RiverModel riverModel) {
            this(cx, cz, radius, step, climate, borderBufferCells, riverModel, 400_000);
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
     * @param captureOut CONTINUOUS 时填入【首次截获账本】（方案 §4.2 的入流口径）；
     *                   null = 不记账。D8 基线不记账（保持旧语义，见类 javadoc 的决策边界）。
     *                   ⚠ 账本只写独立数组，<b>不改变 flux/channel/width 的任何一位</b>。
     */
    private static double[] buildDischarge(Request request, int n, int step,
                                           WindowOutlets.Result built,
                                           EquilibriumWater.Surface surface,
                                           LakeWaterBalance.Result lakeResult,
                                           double[] captureOut) {
        if (request.riverModel() == RiverModel.CONTINUOUS) {
            ContinuousRiverNetwork.Ledger ledger = null;
            if (captureOut != null) {
                java.util.Arrays.fill(captureOut, 0);
                int[] outletBasin = new int[n * n];
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
        return RiverNetwork.build(n, n, surface.level(), surface.parent(),
                lakeResult.runoffPerCell(), lakeResult.overflowAt(), surface.resolved(),
                RiverNetwork.Params.defaults(), request.borderBufferCells()).discharge();
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

    /** 端到端执行。抛出异常仅用于"契约被违反"，不代表结果可信。 */
    public static Result run(CellGenerator gen, TerrainParams tp, Request request) {
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
        WindowOutlets.Terrain terrain = (bx, bz) -> gen.sampleWu(bx / hs, bz / hs).height;

        // ①② 地形 + 边界出口
        var built = WindowOutlets.build(n, n, request.centerBlockX(), request.centerBlockZ(),
                radius, step, sea, terrain);

        // ③ 平衡水面
        EquilibriumWater.Surface surface = EquilibriumWater.compute(
                built.bed(), n, n, built.drains(), built.exterior());

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
        LakeWaterBalance.Result lakeResult =
                balance.solve(request.climate(), built.drains(), borderMask);
        double[] flow = null;
        RiverNetwork.Result river = null;
        int rounds = 0;
        // ★ 收敛必须是【可判定】的，而不是"跑满轮次就算完"（方案 §P1 明确要求）。
        //   迭代上限用尽 ≠ 收敛 ⇒ 必须把 rounds / converged / residual 一并回传，
        //   让调用方（与门禁）能区分"收敛"与"预算用尽"。
        boolean converged = false;
        double residual = Double.NaN;
        // 首次截获账本（CONTINUOUS）：solve 的来水口径 = Σ capture over group（方案 §4.2），
        // 不再是 Σ flux over group（每格穿越重复计水 ⇒ 几何发散，见 Ledger javadoc）。
        double[] capture = request.riverModel() == RiverModel.CONTINUOUS
                ? new double[n * n] : null;
        for (int it = 0; it < couplingMaxRounds(); it++) {
            double[] next = buildDischarge(request, n, step, built, surface, lakeResult, capture);
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
                    }
                }
            }
            flow = next;
            rounds = it + 1;
            if (same) { converged = true; break; }
            // ★ 来水口径切换（本轮核心修复）：solve 的 inflow = Σ 来水 over 组。
            //   CONTINUOUS 传 capture（首次截获，每条流线只记一次）；
            //   D8 保持旧语义（flux=discharge 的 Ω 累积）—— 它是【对照基线】，
            //   改它会同时改掉所有 d8 对照数字 ⇒ 刻意不动（决策边界，见方案 §6）。
            lakeResult = balance.solve(request.climate(), built.drains(), borderMask,
                    capture != null ? capture : flow);
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
            river = RiverNetwork.build(n, n, surface.level(), surface.parent(),
                    lakeResult.runoffPerCell(), lakeResult.overflowAt(), surface.resolved(),
                    RiverNetwork.Params.defaults(), request.borderBufferCells());
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

        return new Result(n, n, step, built.bed(), built.drains(), surface.resolved(),
                surface.level(), lakeResult.depth(), lakeResult.lake(), surface.parent(),
                river.discharge(), river.channel(), river.width(), lakeResult, river, deathMask,
                waterSurface, rounds, converged, residual);
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
