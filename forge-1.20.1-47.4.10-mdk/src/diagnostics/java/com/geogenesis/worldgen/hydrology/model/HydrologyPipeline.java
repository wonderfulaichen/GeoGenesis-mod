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
                         double[] deathMask) { }

    /** 湖↔河耦合的最大迭代轮次（实测通常 2~3 轮内收敛；上限用于防死循环）。 */
    private static final int COUPLING_ROUNDS = 4;

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

    /** 两组流量场是否在容差内一致（耦合收敛判据）。 */
    private static boolean nearlyEqual(double[] a, double[] b) {
        if (a.length != b.length) return false;
        for (int k = 0; k < a.length; k++) {
            double scale = Math.max(1e-9, Math.max(Math.abs(a[k]), Math.abs(b[k])));
            if (Math.abs(a[k] - b[k]) > 1e-6 * scale + 1e-9) return false;
        }
        return true;
    }

    /** 按所选模型计算汇流量场（耦合迭代用）。 */
    private static double[] buildDischarge(Request request, int n, int step,
                                           WindowOutlets.Result built,
                                           EquilibriumWater.Surface surface,
                                           LakeWaterBalance.Result lakeResult) {
        if (request.riverModel() == RiverModel.CONTINUOUS) {
            var c = ContinuousRiverNetwork.build(built.bed(), surface.level(), surface.resolved(),
                    n, n, step, ContinuousRiverNetwork.Params.forRunoff(lakeResult.runoffPerCell()),
                    request.streamSamples(), 0x5EEDL, request.borderBufferCells(),
                    built.drains(), surface.parent(), lakeResult.lake(), lakeResult.overflowAt());
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
        WindowOutlets.Terrain terrain = (bx, bz) -> gen.sampleWu(bx, bz).height;

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
        for (int it = 0; it < COUPLING_ROUNDS; it++) {
            double[] next = buildDischarge(request, n, step, built, surface, lakeResult);
            boolean same = flow != null && nearlyEqual(flow, next);
            flow = next;
            rounds = it + 1;
            if (same) break;
            lakeResult = balance.solve(request.climate(), built.drains(), borderMask, flow);
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
            System.out.printf("  [couple] rounds=%d finalLakeCells=%d basins=%d dryBasins=%d maxFlow=%.3f%n",
                    rounds, countTrue(lakeResult.lake()), lakeResult.basins().size(),
                    lakeResult.dryBasins(), river == null ? 0 : maxOf(river.discharge()));
        }

        return new Result(n, n, step, built.bed(), built.drains(), surface.resolved(),
                surface.level(), lakeResult.depth(), lakeResult.lake(), surface.parent(),
                river.discharge(), river.channel(), river.width(), lakeResult, river, deathMask);
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
