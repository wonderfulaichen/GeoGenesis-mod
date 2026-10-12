package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.terrain.CellGenerator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiFunction;

/**
 * 分层水文受控实验的数据/算法层（开关 {@code -Dgeogenesis.hierarchicalHydrology=true}，默认关）。
 *
 * <h2>粗/细契约（绝对坐标 authority，不依赖生成顺序）</h2>
 * <pre>
 *                  粗层（廉价、跨区骨架）            细层（重、局部细化）
 *   域             tract + 固定 coarseHalo             tract + 固定 halo（= 生产窗口）
 *   地形           terrainEQuick → heightFromE         sampleWu（含侵蚀）
 *   采样/预热       零 sampleWu、零 tile 预热           生产同源预热
 *   邻区           不取（本 tract 自洽）                禁求解邻区细层
 *   跨区           主干端口 + 盆地出口权威              窗外水位 = owner 粗层接口
 * </pre>
 *
 * <p><b>owner</b>：任意绝对坐标的权威由拥有它的粗 tract 给出（{@code floorDiv(block, tract)}）
 * ⇒ 同一坐标在任何调用方、顺序、缓存状态下得到同一值（见 {@link #owner}）。</p>
 *
 * <h2>★ 形态演化口径（不把"形态变化"当失败）</h2>
 * <p>粗层只是<b>候选</b>，细层基于<b>真实侵蚀地形</b>的盆地才是<b>最终</b>形态。因此
 * 盆地的消失 / 分裂 / 合并 / 移动 / 水位变动<b>一律不判失败</b> —— 那是正确演化的结果，
 * 由 {@link MorphologyComparison} 如实记录为诊断（不作为契约判据）。</p>
 * <p>只有<b>真实冲突</b>才硬失败（见 {@link #isNonFinite}、{@link #validate}）：
 * ① 非有限水位 / NaN 坐标；② 排水 parent 成环；③ 结果超出本域；④ 跨区同一坐标的
 * 权威接口不一致。其余一律计数如实回传。</p>
 *
 * <p><b>粗层 halo 盆地不属本区</b>：粗域含 halo ⇒ 会看到邻区的盆地。<b>只按绝对坐标 owner
 * （{@code floorDiv(seedX, tract)} 等）归属盆地</b>，本区只校验自己拥有的候选，
 * 不把别区 halo 里的盆地当本区刚性湖出口。</p>
 */
public final class HierarchicalHydrology {

    private HierarchicalHydrology() { }

    // ======================================================================
    // 固定域参数
    // ======================================================================

    /** 分层固定域参数（所有权威坐标由它导出；probe 与生产同源）。 */
    public record Params(int tract, int halo, int coarseHalo, int step) {
        public Params {
            if (tract <= 0 || step <= 0) throw new IllegalArgumentException("tract/step > 0");
            if (halo < 0) throw new IllegalArgumentException("halo >= 0");
            // 粗域必须覆盖细层窗外一格外环（center ± (radius + step)，radius = tract/2 + halo）。
            if (coarseHalo < halo + step) coarseHalo = halo + step;
            // 粗窗口原点须与细层同余（blockX − originX ≡ 0 mod step）。
            int rem = (tract / 2 + coarseHalo) % step;
            if (rem != 0) coarseHalo += step - rem;
        }

        /** 生产同源参数（tract/halo/step 由调用方传入，与 ModelHydrologyProvider 一致）。 */
        public static Params of(int tract, int halo, int step, int coarseHalo) {
            return new Params(tract, halo, coarseHalo, step);
        }

        /** 独立探针默认参数（= 生产默认值：512/96/4）。 */
        public static Params defaults() { return of(512, 96, 4, 104); }

        /** 细层窗口半径（块）；= 生产 {@code REGION/2 + HALO}。 */
        public int fineRadius() { return tract / 2 + halo; }

        /** 粗层窗口半径（块）。 */
        public int coarseRadius() { return tract / 2 + coarseHalo; }
    }

    // ======================================================================
    // 粗层产物
    // ======================================================================

    /**
     * 边界端口 = 粗层主干<b>跨出</b>本 tract 的那一步（跨区权威段）。
     *
     * <p>只取真实成河的链（{@link DeterministicSkeleton} 粗层主干折线），且要求
     * {@code discharge ≥ 生产源门限}、非湖/海 ⇒ 细层不会在边界上到处强行起河。</p>
     *
     * <p><b>方向</b>：折线为 source→mouth（水从 source 流向 mouth）。端口取
     * 「当前格在 tract 内、<b>下游</b>格在 tract 外」⇒ 水确实<b>离开</b>本 tract；
     * {@code (dRX,dRZ)} = 下游格的 owner tract 相对本 tract 的偏移（接收方）。</p>
     *
     * <p><b>只携带水位，无流量账本</b> ⇒ 跨区水量守恒<b>未证</b>。</p>
     *
     * @param blockX,blockZ 出区前的最后一个 tract 内格（绝对块坐标）
     * @param level         该格的几何水位（上游权威值）
     * @param dRX,dRZ       下游格 owner 相对本 tract 的偏移（±1/0；必非 (0,0)）
     */
    public record Port(int blockX, int blockZ, double level, int dRX, int dRZ) { }

    /** 粗层主干种子：主干链源头格（绝对块坐标）—— 交细层沿侵蚀 parent 细化。 */
    public record TrunkSeed(int blockX, int blockZ) { }

    /**
     * 粗层盆地<b>候选</b>（绝对坐标）。<b>不是</b>细层必须复现的刚性出口 ——
     * 细层基于侵蚀地形的真实盆地才是最终形态（见类 javadoc）。
     *
     * @param seedX,seedZ     盆地代表格绝对块坐标（归属判据：owner = floorDiv(坐标, tract)）
     * @param outletX,outletZ 溢出口绝对块坐标（{@code Integer.MIN_VALUE} = 无出口）
     * @param outletOnRing    出口落在 tract 边界环带（该出口的 owner 可能是邻粗 tract）
     * @param truncated       该盆地组触及粗域边缘（集水被截断，如实报告 ⇒ NOT VERIFIED）
     */
    public record BasinAuthority(int seedX, int seedZ, int outletX, int outletZ, double level,
                                 boolean outletOnRing, boolean truncated) {

        /** 该盆地候选是否由 tract (rx,rz) <b>拥有</b>（绝对坐标归属，不看谁先算出来）。 */
        public boolean ownedBy(Params p, int rx, int rz) {
            return Math.floorDiv(seedX, p.tract()) == rx && Math.floorDiv(seedZ, p.tract()) == rz;
        }
    }

    /**
     * 粗层固定域产物；水位接口一律按绝对块坐标查询。
     *
     * @param trunkSeeds  粗层主干源头格（绝对块坐标）
     * @param solveMillis 本 tract 粗层求解耗时（毫秒）
     */
    public record Coarse(int rx, int rz, int n, int step, int originX, int originZ,
                         HydrologyPipeline.Result pipe, List<Port> ports,
                         List<BasinAuthority> basins, List<TrunkSeed> trunkSeeds,
                         long solveMillis) {

        /** 该绝对坐标是否落在本粗域内（闭开区间）。 */
        public boolean contains(int blockX, int blockZ) {
            return blockX >= originX && blockZ >= originZ
                    && blockX < originX + n * step && blockZ < originZ + n * step;
        }

        /** 绝对坐标 → 粗格索引；域外/舍入越界 ⇒ -1。 */
        public int cellAt(int blockX, int blockZ) {
            if (!contains(blockX, blockZ)) return -1;
            int i = Math.round((blockX - originX) / (float) step);
            int j = Math.round((blockZ - originZ) / (float) step);
            if (i < 0 || j < 0 || i >= n || j >= n) return -1;
            return i * n + j;
        }

        /** 水位接口（NaN = 域外，不伪装成有效值）。 */
        public double levelAt(int blockX, int blockZ) {
            int k = cellAt(blockX, blockZ);
            return k < 0 || pipe.level() == null ? Double.NaN : pipe.level()[k];
        }

        /** 该绝对坐标粗层是否判为湖面。 */
        public boolean lakeAt(int blockX, int blockZ) {
            int k = cellAt(blockX, blockZ);
            return k >= 0 && pipe.lake() != null && pipe.lake()[k];
        }

        /** 权威水面（湖格 = 水量平衡面 bed+depth；其余 = 几何水位；NaN = 域外）。 */
        public double waterSurfaceAt(int blockX, int blockZ) {
            int k = cellAt(blockX, blockZ);
            if (k < 0 || pipe.waterSurface() == null) return Double.NaN;
            return pipe.waterSurface()[k];
        }

        /** 该绝对坐标粗层床面（诊断用）。 */
        public double bedAt(int blockX, int blockZ) {
            int k = cellAt(blockX, blockZ);
            return k < 0 || pipe.bed() == null ? Double.NaN : pipe.bed()[k];
        }
    }

    /**
     * 以绝对坐标找拥有它的 tract，返回该 tract 的粗层产物 —— owner 语义的唯一入口。
     *
     * @param resolver 按 tract 坐标取粗层产物的回调（调用方负责缓存/单飞）
     */
    public static Coarse owner(int blockX, int blockZ, Params p,
                              BiFunction<Integer, Integer, Coarse> resolver) {
        return resolver.apply(Math.floorDiv(blockX, p.tract()), Math.floorDiv(blockZ, p.tract()));
    }

    /**
     * 粗层<b>盆地足迹</b>（格坐标；供跨缝筛查用）—— 不是权威，只是"哪个盆地铺在哪"的可复现记录。
     *
     * @param frameTouch 足迹触及窗口边框（集水被窗口截断）⇒ 调用方应判 NOT VERIFIED 并拒绝受控
     */
    public record BasinFootprint(int basinId, int cells, int minI, int minJ, int maxI, int maxJ,
                                 boolean frameTouch) { }

    /** 逐盆地足迹表（键 = 盆地 id ⇒ 遍历顺序确定）。 */
    public static java.util.Map<Integer, BasinFootprint> basinFootprints(
            HydrologyPipeline.Result pipe) {
        java.util.Map<Integer, BasinFootprint> out = new java.util.TreeMap<>();
        LakeWaterBalance.Result lr = pipe.lakeResult();
        if (lr == null || lr.basinId() == null) return out;
        int n = pipe.nx();
        int[] bid = lr.basinId();
        int[] cnt = new int[lr.basins().size()];
        int[] mnI = new int[cnt.length], mnJ = new int[cnt.length];
        int[] mxI = new int[cnt.length], mxJ = new int[cnt.length];
        boolean[] frame = new boolean[cnt.length];
        java.util.Arrays.fill(mnI, Integer.MAX_VALUE);
        java.util.Arrays.fill(mnJ, Integer.MAX_VALUE);
        java.util.Arrays.fill(mxI, Integer.MIN_VALUE);
        java.util.Arrays.fill(mxJ, Integer.MIN_VALUE);
        for (int k = 0; k < bid.length; k++) {
            int b = bid[k];
            if (b < 0 || b >= cnt.length) continue;
            int i = k / n, j = k % n;
            cnt[b]++;
            mnI[b] = Math.min(mnI[b], i);
            mxI[b] = Math.max(mxI[b], i);
            mnJ[b] = Math.min(mnJ[b], j);
            mxJ[b] = Math.max(mxJ[b], j);
            if (i == 0 || j == 0 || i == n - 1 || j == n - 1) frame[b] = true;
        }
        for (int b = 0; b < cnt.length; b++) {
            if (cnt[b] == 0) continue;
            out.put(b, new BasinFootprint(b, cnt[b], mnI[b], mnJ[b], mxI[b], mxJ[b], frame[b]));
        }
        return out;
    }

    /** 粗层盆地足迹表（键 = 盆地 id）。 */
    public static java.util.Map<Integer, BasinFootprint> basinFootprints(Coarse c) {
        return basinFootprints(c.pipe());
    }

    /**
     * 该粗足迹是否跨过<b>块坐标</b>缝线（粗足迹在 tract 粗窗内 → 换算到世界块）。
     *
     * @param axis 0 = 竖缝（跨 {@code blockX = line}），1 = 横缝（跨 {@code blockZ = line}）
     */
    public static boolean footprintCrossesBlock(BasinFootprint fp, Coarse c, int axis, int line) {
        int lo = axis == 0 ? c.originX() + fp.minI() * c.step() : c.originZ() + fp.minJ() * c.step();
        int hi = axis == 0 ? c.originX() + fp.maxI() * c.step() : c.originZ() + fp.maxJ() * c.step();
        return lo < line && hi >= line;
    }

    /**
     * 该足迹是否<b>跨过</b>缝（沿 {@code axis} 方向同时出现在缝两侧）。
     *
     * @param axis    0 = 竖缝（格 i 方向跨过 {@code seamCell}），1 = 横缝（格 j 方向）
     * @param seamCell 缝在本域网格上的整数格坐标（{@code halfSpan / step}）
     */
    public static boolean footprintCrosses(BasinFootprint fp, int axis, int seamCell) {
        return axis == 0 ? fp.minI() < seamCell && fp.maxI() >= seamCell
                : fp.minJ() < seamCell && fp.maxJ() >= seamCell;
    }

    // ======================================================================
    // 契约失败与校验结果
    // ======================================================================

    /**
     * 真实契约失败（带绝对坐标；不可裁决 ⇒ 抛异常，不回退、不扩域、不隐藏）。
     *
     * <p>只在四类<b>真实冲突</b>下抛出：非有限水位/坐标、排水 parent 成环、
     * 结果超出本域、跨区同坐标权威接口不一致。<b>形态差异（盆地消失/分裂/合并/移动/水位变动）
     * 绝不</b>通过本异常表达 —— 见 {@link MorphologyComparison}。</p>
     */
    public static final class ContractViolation extends RuntimeException {
        /** 冲突绝对块坐标（{@code Integer.MIN_VALUE} = 非位置类冲突）。 */
        public final int blockX, blockZ;
        /** 触发冲突的 tract。 */
        public final int rx, rz;

        ContractViolation(int blockX, int blockZ, int rx, int rz, String message) {
            super(message);
            this.blockX = blockX;
            this.blockZ = blockZ;
            this.rx = rx;
            this.rz = rz;
        }
    }

    /**
     * 细层盆地形态与粗层候选的<b>对比诊断</b>（不是契约判据）。
     *
     * <p>粗层只是候选、细层真实侵蚀盆地才是最终形态 ⇒ 这里的每一项差异都只作<b>记录</b>：
     * {@code missing} 粗候选在细层无对应盆地（候选消失）、{@code moved} 出口位置不同、
     * {@code levelMismatch} 水位不同 —— 三者<b>均不判失败</b>。
     * {@code truncated} 表示对比的采样上下文被截断（粗域集水不完整）⇒ 记为
     * <b>NOT VERIFIED</b>，不得当作"无限跨区已证明"。</p>
     *
     * @param compared       参与对比的、owner 属本 tract 的粗候选数
     * @param foreign        被跳过的"别区 halo 盆地"数（owner 非本 tract ⇒ 不是本区刚性出口）
     * @param contractValid  <b>唯一</b>的契约判据：基本数据合法（见 {@link #isNonFinite}/
     *                       跨区同坐标一致/无环/不超域）。形态差异不影响此值。
     */
    public record MorphologyComparison(int matched, int movedExterior, int missing, int truncated,
                                       int levelMismatch, double worstLevelDelta, int compared,
                                       int foreign, boolean contractValid,
                                       List<String> samples) {
        /** 基本数据合法 ⇒ 契约满足（形态差异不参与判定）。 */
        public boolean contractSatisfied() {
            return contractValid;
        }
    }

    /**
     * 实验契约严格模式。<b>默认 true</b>。
     *
     * <p>严格模式<b>只</b>对真实冲突抛 {@link ContractViolation}（非有限水位/坐标、
     * provider 侧未证接口不得硬失败整片边框 —— 见 {@link #validate}）。
     * <b>形态差异（盆地消失/分裂/合并/移动/水位变动）在严格模式下同样不抛</b>，
     * 只由 {@link MorphologyComparison} 如实记录。</p>
     *
     * <p>{@code -Dgeogenesis.hierStrictContract=false} 仅关闭真实冲突抛异常（用于取完整 metrics 出图）；
     * 无论开关如何，{@link Diag#CONTRACT_UNSATISFIED} 只在真实冲突计数非零时才增长。</p>
     */
    private static final boolean STRICT_CONTRACT =
            !"false".equalsIgnoreCase(System.getProperty("geogenesis.hierStrictContract", "true"));

    /** 有限性判据：真实冲突的第一类（非有限水位 / NaN ⇒ 必须抛/记）。 */
    public static boolean isNonFinite(double v) {
        return !Double.isFinite(v);
    }

    // ======================================================================
    // 粗层构建
    // ======================================================================

    /**
     * 粗层地形：{@code terrainEQuick → heightFromE}（与 {@code sample()} 同源的无侵蚀地形）。
     * <b>零 sampleWu、零侵蚀 tile、零预热</b>。
     */
    public static WindowOutlets.Terrain coarseTerrain(CellGenerator gen) {
        double invHs = 1.0 / Math.max(0.01, gen.params().horizontalScale());
        return (bx, bz) -> {
            Diag.COARSE_SAMPLES.incrementAndGet();
            return gen.heightCurve().heightFromE(gen.terrainEQuick(bx * invHs, bz * invHs));
        };
    }

    /** 粗层骨架参数（只用于产主干，不做形态收尾）。minSourceAccum 传 0 — 门限依赖 runoff，见 ports/trunkSeeds。 */
    private static final DeterministicSkeleton.Params COARSE_SKEL_PARAMS =
            new DeterministicSkeleton.Params(
                    Integer.getInteger("geogenesis.sourceSpacing", 24) / 4, 0.0, 0, 0.0, 24.0,
                    0, 1, 0, 0);

    /** 构建一个 tract 的粗层产物（纯函数；域由 (tract, coarseHalo, rx, rz) 唯一确定）。 */
    public static Coarse buildCoarse(CellGenerator gen, Params p, int rx, int rz,
                                     LakeWaterBalance.Climate climate) {
        long t0 = System.nanoTime();
        int cx = rx * p.tract() + p.tract() / 2;
        int cz = rz * p.tract() + p.tract() / 2;
        int radius = p.coarseRadius();
        // 粗层恒用 D8：确定性拓扑、零随机；prewarm=false ⇒ 绝不预热侵蚀 tile。
        var req = new HydrologyPipeline.Request(cx, cz, radius, p.step(), climate, 0,
                HydrologyPipeline.RiverModel.D8, 1, null);
        HydrologyPipeline.Result pipe = HydrologyPipeline.run(gen, gen.params(), req,
                new HydrologyPipeline.Layer(coarseTerrain(gen), null, false, "coarse"));
        int originX = cx - radius, originZ = cz - radius;
        List<DeterministicSkeleton.Trace> trunks = DeterministicSkeleton.build(
                pipe.level(), pipe.parent(), pipe.resolved(), pipe.lake(), pipe.sea(),
                pipe.discharge(),
                pipe.lakeResult() == null ? null : pipe.lakeResult().basinOutlet(),
                pipe.nx(), pipe.nx(), COARSE_SKEL_PARAMS).traces;
        double minAccum = productionSourceAccum(pipe);
        List<Port> ports = ports(p, pipe, trunks, rx, rz, originX, originZ, minAccum);
        List<TrunkSeed> seeds = trunkSeeds(p, pipe, trunks, rx, rz, originX, originZ, minAccum);
        List<BasinAuthority> basins = authorities(p, pipe, rx, rz, originX, originZ);
        long ms = (System.nanoTime() - t0) / 1_000_000L;
        Diag.COARSE_SOLVES.incrementAndGet();
        Diag.coarseTiming(ms, pipe.nx() * pipe.nx());
        Diag.layer("coarse", "trunks", trunks.size());
        Diag.layer("coarse", "ports", ports.size());
        Diag.layer("coarse", "trunkSeeds", seeds.size());
        Diag.layer("coarse", "basins", basins.size());
        Diag.layer("coarse", "quickSamples", Diag.COARSE_SAMPLES.get());
        Diag.layer("coarse", "prewarmSpan", 0);
        return new Coarse(rx, rz, pipe.nx(), p.step(), originX, originZ, pipe, ports, basins,
                seeds, ms);
    }

    /** 生产源门限（discharge 单位）：{@code chanMinCells × runoffPerCell}。runoff 未知 ⇒ 0（不设限）。 */
    private static double productionSourceAccum(HydrologyPipeline.Result pipe) {
        double runoff = pipe.lakeResult() == null ? 0.0 : pipe.lakeResult().runoffPerCell();
        double chanMinCells = Double.parseDouble(
                System.getProperty("geogenesis.channelMinCells", "150"));
        return runoff > 0 ? chanMinCells * runoff : 0.0;
    }

    /**
     * 边界端口：主干折线上、达源门限、非湖/海，且其<b>下游</b>格落在 tract 外 ⇒ 该格为出区端口。
     *
     * <p><b>方向铁律（勿反）</b>：{@link DeterministicSkeleton.Trace} 的
     * {@code cell[0] = sourceCell}（上游源头）、{@code cell[len-1] = mouthCell}（下游出口）
     * （见 {@code Trace#sourceCell()}/{@code #mouthCell()}）。故<b>下游 = idx+1</b>：
     * 只有「当前格 {@code idx} 在 tract 内、下一格 {@code idx+1} 在 tract 外」才是
     * 水<b>流出</b>本 tract。旧写法用 {@code idx-1}（上游）判"外"⇒ 实际产出的是
     * <b>反向的入区端口</b>（水从上游邻区流进来），方向也标成了本区 → 邻区。</p>
     */
    private static List<Port> ports(Params p, HydrologyPipeline.Result pipe,
                                    List<DeterministicSkeleton.Trace> trunks,
                                    int rx, int rz, int originX, int originZ, double minAccum) {
        int n = pipe.nx(), step = p.step();
        int loX = rx * p.tract(), loZ = rz * p.tract();
        int hiX = loX + p.tract(), hiZ = loZ + p.tract();
        double[] dis = pipe.discharge();
        boolean[] lake = pipe.lake(), sea = pipe.sea();
        List<Port> out = new ArrayList<>();
        java.util.HashSet<Long> seen = new java.util.HashSet<>();   // 去重 (worldX,worldZ,dir)
        if (trunks == null) return out;
        for (DeterministicSkeleton.Trace t : trunks) {
            for (int idx = 0; idx + 1 < t.nodeCount(); idx++) {
                int k = t.cell[idx];
                int bx = originX + (k / n) * step, bz = originZ + (k % n) * step;
                if (bx < loX || bx >= hiX || bz < loZ || bz >= hiZ) continue;
                if (lake[k] || sea[k]) continue;
                if (minAccum > 0 && dis[k] < minAccum) continue;
                int nk = t.cell[idx + 1];                           // ★ 下游格（不是上游）
                int nbx = originX + (nk / n) * step, nbz = originZ + (nk % n) * step;
                int dRX = Integer.compare(Math.floorDiv(nbx, p.tract()), rx);
                int dRZ = Integer.compare(Math.floorDiv(nbz, p.tract()), rz);
                if (dRX == 0 && dRZ == 0) continue;                 // 下游仍在区内 ⇒ 非端口
                long key = (((long) bx) << 42) ^ (((long) bz) << 20)
                        ^ (((long) (dRX + 2)) << 10) ^ (dRZ + 2);
                if (!seen.add(key)) continue;                        // ★ 去重：同格同向只出一个端口
                out.add(new Port(bx, bz, pipe.level()[k], dRX, dRZ));
            }
        }
        return out;
    }

    /**
     * 粗层主干种子 = 主干链源头格（达门限、非湖/海），且源头<b>由本 tract 拥有</b>。
     *
     * <p>粗域含 halo ⇒ 会看到邻 tract 的主干；按绝对坐标 owner 过滤，避免 halo 主干
     * 在本区被重复当成"本区源头"。</p>
     */
    private static List<TrunkSeed> trunkSeeds(Params p, HydrologyPipeline.Result pipe,
                                              List<DeterministicSkeleton.Trace> trunks,
                                              int rx, int rz, int originX, int originZ,
                                              double minAccum) {
        int n = pipe.nx(), step = p.step();
        double[] dis = pipe.discharge();
        boolean[] lake = pipe.lake(), sea = pipe.sea();
        List<TrunkSeed> out = new ArrayList<>();
        java.util.HashSet<Long> seen = new java.util.HashSet<>();
        if (trunks == null) return out;
        for (DeterministicSkeleton.Trace t : trunks) {
            int s = t.sourceCell();
            if (s < 0 || s >= n * n || lake[s] || sea[s]) continue;
            if (minAccum > 0 && dis[s] < minAccum) continue;
            int sx = originX + (s / n) * step, sz = originZ + (s % n) * step;
            if (Math.floorDiv(sx, p.tract()) != rx || Math.floorDiv(sz, p.tract()) != rz) {
                continue;                                            // halo 主干：别区拥有
            }
            long key = (((long) sx) << 32) ^ (sz & 0xffffffffL);
            if (!seen.add(key)) continue;                            // 同源头只出一次
            out.add(new TrunkSeed(sx, sz));
        }
        return out;
    }

    /** 粗层盆地出口权威（绝对坐标）。 */
    private static List<BasinAuthority> authorities(Params p, HydrologyPipeline.Result pipe,
                                                    int rx, int rz, int originX, int originZ) {
        List<BasinAuthority> out = new ArrayList<>();
        LakeWaterBalance.Result lr = pipe.lakeResult();
        if (lr == null || lr.basins() == null || lr.basinOutlet() == null) return out;
        int n = pipe.nx(), step = p.step();
        int loX = rx * p.tract(), loZ = rz * p.tract();
        int hiX = loX + p.tract(), hiZ = loZ + p.tract();
        for (int b = 0; b < lr.basins().size(); b++) {
            LakeWaterBalance.Basin basin = lr.basins().get(b);
            int seed = basin.seedCell();
            int sx = originX + (seed / n) * step, sz = originZ + (seed % n) * step;
            int oc = b < lr.basinOutlet().length ? lr.basinOutlet()[b] : -1;
            int ox = Integer.MIN_VALUE, oz = Integer.MIN_VALUE;
            boolean onRing = false;
            if (oc >= 0) {
                ox = originX + (oc / n) * step;
                oz = originZ + (oc % n) * step;
                onRing = ox < loX + step || ox >= hiX - step
                        || oz < loZ + step || oz >= hiZ - step;
            }
            out.add(new BasinAuthority(sx, sz, ox, oz, basin.level(), onRing,
                    basin.catchmentTruncated()));
        }
        return out;
    }

    // ======================================================================
    // 细层校验
    // ======================================================================

    /**
     * 细层盆地形态 vs 粗层<b>候选</b>的对比（{@link MorphologyComparison}）。
     *
     * <p><b>不做刚性出口校验</b>：粗层只是候选，细层真实侵蚀盆地为最终形态 ⇒
     * 候选消失/分裂/合并/出口移动/水位变动<b>一律不判失败</b>，只如实记录。</p>
     *
     * <p>真实冲突（{@code contractValid=false}，严格模式抛 {@link ContractViolation}）：
     * ① 细层水位/床面非有限（NaN/Inf）；② 盆地 seed 单元格索引超出细层域（不该发生的越域）。</p>
     *
     * <p><b>归属</b>：只对比 owner 属本 tract 的粗候选（{@link BasinAuthority#ownedBy}）。
     * 粗域含 halo ⇒ 会看到别区盆地，那些不是本区刚性湖出口 ⇒ 只计入 {@code foreign}，
     * 不参与对比、也不判失败。</p>
     */
    public static MorphologyComparison validate(Params p, Coarse ct, HydrologyPipeline.Result fine,
                                                int fineOriginX, int fineOriginZ, int rx, int rz) {
        int fn = fine.nx(), step = p.step();
        LakeWaterBalance.Result lr = fine.lakeResult();
        int[] outletOf = lr == null ? null : lr.basinOutlet();
        int matched = 0, moved = 0, missing = 0, trunc = 0, levelBad = 0, compared = 0, foreign = 0;
        double worst = 0;
        boolean valid = true;
        List<String> samples = new ArrayList<>();
        for (BasinAuthority ba : ct.basins()) {
            // ★ 绝对坐标归属：别区 halo 盆地不是本区刚性出口 ⇒ 只计 foreign，不对比不判失败。
            if (!ba.ownedBy(p, rx, rz)) {
                foreign++;
                add(samples, "FOREIGN(别区halo) basin seed=(" + ba.seedX() + "," + ba.seedZ()
                        + ") outlet=(" + ba.outletX() + "," + ba.outletZ() + ")");
                continue;
            }
            compared++;
            if (ba.truncated()) {
                trunc++;                       // 采样上下文被截断 ⇒ NOT VERIFIED（非失败）
                add(samples, "TRUNCATED(NOT VERIFIED) basin seed=("
                        + ba.seedX() + "," + ba.seedZ() + ")");
            }
            int fi = Math.round((ba.seedX() - fineOriginX) / (float) step);
            int fj = Math.round((ba.seedZ() - fineOriginZ) / (float) step);
            int fk = fi < 0 || fj < 0 || fi >= fn || fj >= fn ? -1 : fi * fn + fj;
            int bid = fk < 0 || lr == null || lr.basinId() == null ? -1 : lr.basinId()[fk];
            if (bid < 0) {
                // 细层在此处没有盆地 —— 粗候选在真实侵蚀地形下消失 ⇒ 正确演化，非失败。
                missing++;
                add(samples, "EVOLVED(out) basin seed=(" + ba.seedX() + "," + ba.seedZ() + ")");
                continue;
            }
            double fineLevel = fine.bed()[fk] + fine.depth()[fk];
            if (isNonFinite(fineLevel)) {       // ★ 真实冲突：非有限水位
                valid = false;
                add(samples, "NONFINITE(level) basin=(" + ba.seedX() + "," + ba.seedZ()
                        + ") fine=" + fineLevel);
                continue;
            }
            double d = Math.abs(fineLevel - ba.level());
            worst = Math.max(worst, d);
            if (d > 0) {
                levelBad++;                     // 水位不同（可正可负）⇒ 记录，不判失败
                add(samples, String.format("LEVEL basin=(%d,%d) coarse=%.2f fine=%.2f",
                        ba.seedX(), ba.seedZ(), ba.level(), fineLevel));
            }
            int fo = outletOf == null || bid >= outletOf.length ? -1 : outletOf[bid];
            if (fo < 0) { matched++; continue; }             // 细层判为内流：无出口可比
            if (fo >= fn * fn) {                             // ★ 真实冲突：越域索引
                valid = false;
                add(samples, "OUT-OF-DOMAIN outlet cell=" + fo + " basin=("
                        + ba.seedX() + "," + ba.seedZ() + ")");
                continue;
            }
            int fox = fineOriginX + (fo / fn) * step, foz = fineOriginZ + (fo % fn) * step;
            boolean same = fox == ba.outletX() && foz == ba.outletZ();
            if (same) { matched++; continue; }
            moved++;                             // 出口移动 ⇒ 记录，不判失败
            add(samples, String.format("MOVED(no contract) coarse outlet=(%d,%d) ring=%b "
                            + "fine outlet=(%d,%d) basinSeed=(%d,%d) tract=(%d,%d)",
                    ba.outletX(), ba.outletZ(), ba.outletOnRing(), fox, foz,
                    ba.seedX(), ba.seedZ(), rx, rz));
        }
        Diag.morphology(matched, moved, missing, trunc, levelBad, worst, foreign, valid);
        MorphologyComparison v = new MorphologyComparison(matched, moved, missing, trunc, levelBad,
                worst, compared, foreign, valid, samples);
        if (!valid) {
            Diag.CONTRACT_UNSATISFIED.incrementAndGet();
            if (STRICT_CONTRACT) {
                throw new ContractViolation(Integer.MIN_VALUE, Integer.MIN_VALUE, rx, rz,
                        "真实契约冲突（非有限水位/越域索引；形态差异不在此列）: " + v);
            }
        }
        return v;
    }

    private static void add(List<String> samples, String line) {
        if (samples.size() < 16) samples.add(line);
    }

    // ======================================================================
    // 连通性审计（如实失败，不靠删除好看）
    // ======================================================================

    /**
     * 细层独立连通性审计结果（不依赖骨架的网络过滤）。
     *
     * <p>{@code orphanCells==0} 不等于"接口已验证"：沿 parent 走出窗口只说明"水离开本窗口"，
     * 不证明下游接住了它 ⇒ 这类格计入 {@link #boundaryUnverifiedCells}，不得当连通 PASS。</p>
     *
     * @param channelCells           审计的河道格总数
     * @param orphanCells            孤立河道格（下游终止在窗内且不达湖/海）
     * @param orphanChains           孤立链数（孤立格的"根"数）
     * @param boundaryUnverifiedCells 下游越出窗口的格数（接口未验证）
     * @param loopCells              parent 上出现环的格数（排水图应无环）⇒ 必须 FAIL
     */
    public record Connectivity(int channelCells, int orphanCells, int orphanChains,
                               int boundaryUnverifiedCells, int loopCells,
                               List<String> samples) {
        /** 孤立或成环 ⇒ 硬失败；边框未验证不算硬失败，但也不算通过。 */
        public boolean hardFail() { return orphanCells > 0 || loopCells > 0; }

        /** 完全验证通过：零孤立、零环、且零边框未验证。 */
        public boolean fullyVerified() {
            return orphanCells == 0 && loopCells == 0 && boundaryUnverifiedCells == 0;
        }
    }

    /**
     * 独立连通性审计 —— 不得以删除孤立支流换取指标。
     *
     * <p>从细层管线的 {@code channel}/{@code parent} 直接重算可达性，与骨架侧网络过滤无关。</p>
     *
     * <p>判定三态：沿 parent 遇 sea/lake ⇒ 达水体；下游越出窗口 ⇒ 边框未验证；窗内 parent
     * 终止 ⇒ 孤立（硬失败）；parent 成环 ⇒ 成环（硬失败）。</p>
     */
    public static Connectivity auditConnectivity(HydrologyPipeline.Result fine) {
        boolean[] channel = fine.channel();
        if (channel == null) return new Connectivity(0, 0, 0, 0, 0, List.of());
        int n = fine.nx(), cells = n * n;
        int[] parent = fine.parent();
        boolean[] lake = fine.lake(), sea = fine.sea();
        byte[] state = new byte[cells];                 // 0=未定 1=达水体 2=孤立 3=边框未验证 4=成环
        int[] visiting = new int[cells];                // 本步访问戳记（O(1) 回边检测）
        List<Integer> path = new ArrayList<>();
        int chan = 0, orphan = 0, boundary = 0, loops = 0, stamp = 0;
        for (int k = 0; k < cells; k++) {
            if (!channel[k]) continue;
            chan++;
            switch (reachesWater(k, parent, lake, sea, state, n, cells, path, visiting, ++stamp)) {
                case 2 -> orphan++;
                case 3 -> boundary++;
                case 4 -> loops++;
                default -> { /* 1 = 达水体 */ }
            }
        }
        List<String> samples = new ArrayList<>();
        int chains = 0;
        for (int k = 0; k < cells; k++) {
            if (!channel[k] || state[k] != 2) continue;
            int p = parent == null || k >= parent.length ? -1 : parent[k];
            if (p >= 0 && p < cells && channel[p] && state[p] == 2) continue;
            chains++;
            if (samples.size() < 16) samples.add("(" + (k / n) + "," + (k % n) + ")");
        }
        return new Connectivity(chan, orphan, chains, boundary, loops, samples);
    }

    /**
     * 沿 parent 走：达水体(1) / 孤立(2) / 边框未验证(3) / 成环(4)。
     *
     * <p>"水离开窗口" = 下游格落在窗口外，而不是"当前格落在边框上"（边框格仍可是中转格）。
     * {@code visiting[]} 做 O(1) 回边检测 + {@code maxSteps} 兜底 ⇒ 任何环都有界判为 4。</p>
     */
    private static byte reachesWater(int start, int[] parent, boolean[] lake, boolean[] sea,
                                     byte[] state, int n, int cells, List<Integer> path,
                                     int[] visiting, int stamp) {
        path.clear();
        int k = start, steps = 0, maxSteps = cells + 1;
        byte result;
        while (true) {
            if (steps++ > maxSteps) { result = 4; break; }              // 兜底：超步 = 病态
            if (k < 0 || k >= cells) { result = 3; break; }             // 越界（防御）⇒ 未验证
            if (sea[k] || lake[k]) { result = 1; break; }               // 达水体
            if (state[k] != 0) { result = state[k]; break; }            // 已判定（含 4）
            if (visiting[k] == stamp) { result = 4; break; }            // O(1) 回边检测
            visiting[k] = stamp;
            path.add(k);
            int p = parent == null || k >= parent.length ? -1 : parent[k];
            if (p == k) { result = 4; break; }                          // 自环
            if (p < 0 || p >= cells) {                                  // 下游越出窗口
                result = onFrame(k, n) ? (byte) 3 : (byte) 2;
                break;
            }
            k = p;
        }
        for (int c : path) {
            if (state[c] == 0) state[c] = result;
            visiting[c] = 0;
        }
        return result;
    }

    /** 该格是否落在细窗口边框（水从这里离开窗口，交邻 tract 承接）。 */
    private static boolean onFrame(int k, int n) {
        int i = k / n, j = k % n;
        return i == 0 || j == 0 || i == n - 1 || j == n - 1;
    }

    // ======================================================================
    // 诊断读视图（探针直接读）
    // ======================================================================

    /**
     * 只读诊断视图。计数器全部如实累计；没有任何一个可以把"未验证"说成"通过"。
     * 逐层规模与阶段耗时合并入 {@link #LAYER}（键 = {@code 层/指标}）。
     */
    public static final class Diag {
        /** 粗层求解次数。 */
        public static final AtomicLong COARSE_SOLVES = new AtomicLong();
        /** 粗层地形采样次数（terrainEQuick 路径；证明未走 sampleWu/tile）。 */
        public static final AtomicLong COARSE_SAMPLES = new AtomicLong();
        /** 细层求解次数。 */
        public static final AtomicLong FINE_SOLVES = new AtomicLong();
        /** 细层窗外边界由 owner 粗层权威覆盖的格数（与查不到的缺数）。 */
        public static final AtomicLong EXTERIOR_OVERRIDES = new AtomicLong();
        public static final AtomicLong EXTERIOR_MISSES = new AtomicLong();
        /** 粗层判湖且本块真实地形支持（{@code eroded < 水面 − 0.5}）⇒ 候选一致（仅计数）。 */
        public static final AtomicLong COARSE_LAKE_CANDIDATE_AGREE = new AtomicLong();
        /** 粗层判湖但真实地形不支持 ⇒ 侵蚀打开出口把湖排掉的漏水现场（仅计数，不覆盖输出）。 */
        public static final AtomicLong COARSE_LAKE_CANDIDATE_DISAGREE = new AtomicLong();
        public static final List<String> DISAGREE_SAMPLES = samples();
        /** 细层近边界、本 tract 自身无命中。 */
        public static final AtomicLong IN_TRACT_NO_HIT = new AtomicLong();
        /** 细层近边界无本区命中且粗域也不覆盖 ⇒ 归属缺口（如实记录；非"所有边框"硬失败）。 */
        public static final AtomicLong CONTRACT_GAPS = new AtomicLong();
        /** 盆地出口位置与粗候选不同（形态演化，仅记录）。 */
        public static final AtomicLong OUTLET_MOVED = new AtomicLong();
        /** 粗候选在细层真实侵蚀地形下消失（形态演化，仅记录）。 */
        public static final AtomicLong BASIN_EVOLVED_OUT = new AtomicLong();
        /** 别区 halo 盆地被跳过（owner 非本 tract ⇒ 不是本区刚性出口，仅记录）。 */
        public static final AtomicLong FOREIGN_HALO_BASINS = new AtomicLong();
        /** 采样上下文被截断的对比次数 ⇒ NOT VERIFIED（不得当作"无限跨区已证明"）。 */
        public static final AtomicLong TRUNCATED_CONTEXT = new AtomicLong();
        /** 水位与粗候选不同的盆地数（形态演化，仅记录）。 */
        public static final AtomicLong LEVEL_MISMATCH = new AtomicLong();
        /** 真实冲突（非有限水位 / 越域索引）的细层求解次数；0 才算契约成立。 */
        public static final AtomicLong CONTRACT_UNSATISFIED = new AtomicLong();
        /** 独立连通性审计：河格 / 孤立格 / 孤立链。 */
        public static final AtomicLong CHANNEL_CELLS = new AtomicLong();
        public static final AtomicLong ORPHAN_CELLS = new AtomicLong();
        public static final AtomicLong ORPHAN_CHAINS = new AtomicLong();
        /** 下游越出窗口的格数：接口未验证（不得当连通 PASS）。 */
        public static final AtomicLong BOUNDARY_UNVERIFIED_CELLS = new AtomicLong();
        /** parent 上出现环的格数（排水图应无环）⇒ 真实冲突，硬失败。 */
        public static final AtomicLong LOOP_CELLS = new AtomicLong();

        // ---- ★ 2026-10-12 跨 tract 缝共享湖域（{@link SharedLakeDomain}；默认关）----
        /** 缝域构建数（每个都要付一次同窗粗层筛选）。 */
        public static final AtomicLong SHARED_DOMAINS = new AtomicLong();
        /** 粗层筛选（同窗 D8，零 sampleWu/零 tile）。 */
        public static final AtomicLong SHARED_COARSE_SCREENS = new AtomicLong();
        /** 细层侵蚀求解数（只有确有跨缝盆地才会发生）。 */
        public static final AtomicLong SHARED_FINE_SOLVES = new AtomicLong();
        /** 粗筛选即止（无跨缝盆地）⇒ 细层未跑。 */
        public static final AtomicLong SHARED_SKIPPED_NO_CROSSING = new AtomicLong();
        /** 廉价屏幕：邻 tract 粗层里看到跨缝盆地（→ 需建 704² 对称窗）。 */
        public static final AtomicLong SHARED_SCREEN_CROSS = new AtomicLong();
        /** 廉价屏幕：邻 tract 粗层无跨缝盆地 ⇒ 跳过建窗（省一次粗筛选；安全侧）。 */
        public static final AtomicLong SHARED_SCREENED_OUT = new AtomicLong();
        /** 受控的粗盆地数。 */
        public static final AtomicLong SHARED_CONTROLLED_BASINS = new AtomicLong();
        /** 被共享域<b>改写</b>的列数（本地细窗说了无水，共享域判有水并给出水位）。 */
        public static final AtomicLong SHARED_LAKE_COLUMNS = new AtomicLong();
        /** 被共享域<b>抑制</b>的列数（本地细窗判有湖，共享域按同一盆地判无水；不剔除河）。 */
        public static final AtomicLong SHARED_SUPPRESSED_COLUMNS = new AtomicLong();
        /** 共享域覆盖但裁定为"无水"的列数（不等于抑制：抑制只统计本地有水的情况）。 */
        public static final AtomicLong SHARED_CONTROLLED_COLUMNS = new AtomicLong();
        /** 足迹触窗框 ⇒ 拒绝受控（集水截断，如实记录，不假装已修）。 */
        public static final AtomicLong SHARED_REFUSED_TRUNCATED = new AtomicLong();
        /** 盆地 bbox+干环 装不下 maxHalf 窗 ⇒ 拒绝（有界，不移墙）。 */
        public static final AtomicLong SHARED_REFUSED_TOO_BIG = new AtomicLong();
        /** 环上有水 / 多平面 ⇒ 拒绝受控（形态演化或真竞争）。 */
        public static final AtomicLong SHARED_REFUSED_LEAK = new AtomicLong();
        /** 粗候选在该域侵蚀地形下没有对应湖 ⇒ 记录（形态演化，非失败）。 */
        public static final AtomicLong SHARED_BASIN_LOST = new AtomicLong();
        /** 同一世界点被多个受控域覆盖（平面不一致）⇒ 未决：不并集、不改写、如实计数。 */
        public static final AtomicLong SHARED_COMPETING = new AtomicLong();

        /** 全部计数器（reset 用；新增计数器须登记于此）。 */
        private static final AtomicLong[] ALL = {
                COARSE_SOLVES, COARSE_SAMPLES, FINE_SOLVES, EXTERIOR_OVERRIDES, EXTERIOR_MISSES,
                COARSE_LAKE_CANDIDATE_AGREE, COARSE_LAKE_CANDIDATE_DISAGREE, IN_TRACT_NO_HIT,
                CONTRACT_GAPS, OUTLET_MOVED, BASIN_EVOLVED_OUT, FOREIGN_HALO_BASINS,
                TRUNCATED_CONTEXT, LEVEL_MISMATCH,
                CONTRACT_UNSATISFIED, CHANNEL_CELLS, ORPHAN_CELLS, ORPHAN_CHAINS,
                BOUNDARY_UNVERIFIED_CELLS, LOOP_CELLS,
                SHARED_DOMAINS, SHARED_COARSE_SCREENS, SHARED_FINE_SOLVES,
                SHARED_SKIPPED_NO_CROSSING, SHARED_SCREEN_CROSS, SHARED_SCREENED_OUT,
                SHARED_CONTROLLED_BASINS, SHARED_LAKE_COLUMNS,
                SHARED_SUPPRESSED_COLUMNS, SHARED_CONTROLLED_COLUMNS,
                SHARED_REFUSED_TRUNCATED, SHARED_REFUSED_TOO_BIG,
                SHARED_REFUSED_LEAK,
                SHARED_BASIN_LOST, SHARED_COMPETING,
        };

        /** 逐层规模与阶段耗时：键 = {@code 层/指标}。 */
        public static final ConcurrentHashMap<String, Long> LAYER = new ConcurrentHashMap<>();
        /** 最近一次粗层构建耗时（ms）——性能诊断（{@link #LAYER} 里另有 {@code coarse/buildMs}）。 */
        public static volatile long LAST_COARSE_MS = -1;
        /** 最近一次细层求解耗时（ms：管线 + 骨架/中心线）——性能诊断。 */
        public static volatile long LAST_FINE_MS = -1;
        /** 最近一次细层求解的窗口格数（算力口径：格子数 × 步长² = 面积）。 */
        public static volatile long LAST_FINE_CELLS = -1;
        /** 实验最近一次骨架构建的网络过滤状态（探针断言"确实关了、确实没删"）。 */
        public static volatile boolean LAST_SKELETON_FILTER_ENABLED = true;
        public static volatile int LAST_SKELETON_FILTER_DROPPED = -1;
        public static final List<String> ORPHAN_SAMPLES = samples();
        public static final List<String> GAP_SAMPLES = samples();
        public static final List<String> VALIDATION_SAMPLES = samples();

        private Diag() { }

        private static List<String> samples() {
            return Collections.synchronizedList(new ArrayList<>());
        }

        /** 记录逐层规模 / 阶段耗时（值会覆盖；只留最后一次）。 */
        public static void layer(String tag, String metric, long value) {
            LAYER.put(tag + "/" + metric, value);
        }

        /** 记录实验骨架构建时的网络过滤状态。 */
        static void skeletonBuild(boolean filterEnabled, int dropped) {
            LAST_SKELETON_FILTER_ENABLED = filterEnabled;
            LAST_SKELETON_FILTER_DROPPED = dropped;
        }

        /** 粗层湖候选与真实地形的一致/不一致（仅计数；湖只由细层产出，不覆盖）。 */
        public static void coarseLakeCandidate(boolean terrainSupports) {
            (terrainSupports ? COARSE_LAKE_CANDIDATE_AGREE : COARSE_LAKE_CANDIDATE_DISAGREE)
                    .incrementAndGet();
        }

        /** 细层连通性审计登记（孤立/环/边框未验证均如实累计）。 */
        static void connectivity(Connectivity c) {
            CHANNEL_CELLS.addAndGet(c.channelCells());
            ORPHAN_CELLS.addAndGet(c.orphanCells());
            ORPHAN_CHAINS.addAndGet(c.orphanChains());
            BOUNDARY_UNVERIFIED_CELLS.addAndGet(c.boundaryUnverifiedCells());
            LOOP_CELLS.addAndGet(c.loopCells());
            append(ORPHAN_SAMPLES, c.samples(), 32);
        }

        /**
         * 细层盆地形态对比登记（全部为【记录】，不参与契约判定）。
         * 形态差异（moved/missing/levelBad）只累计；{@code valid=false} 才是真实冲突。
         */
        static void morphology(int matched, int moved, int missing, int trunc,
                               int levelBad, double worst, int foreign, boolean valid) {
            OUTLET_MOVED.addAndGet(moved);
            BASIN_EVOLVED_OUT.addAndGet(missing);
            TRUNCATED_CONTEXT.addAndGet(trunc);
            FOREIGN_HALO_BASINS.addAndGet(foreign);
            LEVEL_MISMATCH.addAndGet(levelBad);
            append(VALIDATION_SAMPLES, List.of(String.format(
                    "matched=%d moved=%d evolvedOut=%d truncatedCtx=%d levelDiff=%d foreign=%d "
                            + "worst=%.2f valid=%b",
                    matched, moved, missing, trunc, levelBad, foreign, worst, valid)), 16);
        }

        /** 记录粗层构建耗时（性能诊断；同时进 {@link #LAYER}）。 */
        public static void coarseTiming(long ms, int cells) {
            LAST_COARSE_MS = ms;
            layer("coarse", "buildMs", ms);
            layer("coarse", "cells", cells);
        }

        /** 记录细层求解耗时与规模（性能诊断；同时进 {@link #LAYER}）。 */
        public static void fineTiming(long ms, int cells) {
            LAST_FINE_MS = ms;
            LAST_FINE_CELLS = cells;
            layer("fine", "solveMs", ms);
            layer("fine", "cells", cells);
        }

        /** 细层窗外边界条件由粗层权威覆盖（由管线调用）。 */
        public static void exteriorAuthority(int overrides, int misses) {
            EXTERIOR_OVERRIDES.addAndGet(overrides);
            EXTERIOR_MISSES.addAndGet(misses);
        }

        /** 跨缝共享湖域的耗时拆分（粗筛选 / 细解）。 */
        public static void sharedTiming(long coarseMs, long fineMs) {
            layer("sharedLake", "coarseMs", coarseMs);
            layer("sharedLake", "fineMs", fineMs);
        }

        /** 共享湖域样本（上限 {@code CAP}，如实留痕拒绝/竞争现场）。 */
        public static final List<String> SHARED_SAMPLES = samples();
        private static final int SHARED_SAMPLE_CAP = 48;

        /** 记录共享湖域的现场行（拒绝/竞争/域摘要）。 */
        static void sharedSample(String line) {
            append(SHARED_SAMPLES, List.of(line), SHARED_SAMPLE_CAP);
        }

        /** 跨缝共享湖域报告（含"移缝"风险的如实口径）。 */
        public static String sharedReport() {
            return String.format(
                    "  跨缝共享湖域: 域=%d 粗筛选=%d 细解=%d 无跨缝即止=%d（廉价屏幕省下建窗=%d）%n"
                            + "    受控盆地=%d 改写列(本区无水⇒共享判有水)=%d 抑制列(本区有水⇒共享判无水)=%d"
                            + " 受控无水列=%d%n"
                            + "    拒绝: 截断=%d 太大=%d 环上有水/多平面=%d 粗候选消失=%d"
                            + " 竞争(未决,不并集)=%d%n"
                            + "    ⇒ 受控 = 盆地级 canonical（闭合+干环+单平面+有限+装得下）；"
                            + "其外仍由本区细窗裁决（不得声称整缝已消除）%n",
                    SHARED_DOMAINS.get(), SHARED_COARSE_SCREENS.get(), SHARED_FINE_SOLVES.get(),
                    SHARED_SKIPPED_NO_CROSSING.get(), SHARED_SCREENED_OUT.get(),
                    SHARED_CONTROLLED_BASINS.get(),
                    SHARED_LAKE_COLUMNS.get(), SHARED_SUPPRESSED_COLUMNS.get(),
                    SHARED_CONTROLLED_COLUMNS.get(), SHARED_REFUSED_TRUNCATED.get(),
                    SHARED_REFUSED_TOO_BIG.get(), SHARED_REFUSED_LEAK.get(),
                    SHARED_BASIN_LOST.get(), SHARED_COMPETING.get());
        }

        /** 细层近边界、本 tract 自身无命中。 */
        public static void inTractNoHit() {
            IN_TRACT_NO_HIT.incrementAndGet();
        }

        /** 细层近边界契约缺口（记录坐标，便于探针定位）。 */
        public static void contractGap(int blockX, int blockZ) {
            CONTRACT_GAPS.incrementAndGet();
            append(GAP_SAMPLES, List.of("(" + blockX + "," + blockZ + ")"), 32);
        }

        /** 清空计数（探针分段隔离）。 */
        public static void reset() {
            for (AtomicLong a : ALL) a.set(0);
            LAYER.clear();
            LAST_COARSE_MS = -1;
            LAST_FINE_MS = -1;
            LAST_FINE_CELLS = -1;
            LAST_SKELETON_FILTER_ENABLED = true;
            LAST_SKELETON_FILTER_DROPPED = -1;
            ORPHAN_SAMPLES.clear();
            GAP_SAMPLES.clear();
            VALIDATION_SAMPLES.clear();
            SHARED_SAMPLES.clear();
        }

        /** 汇总报告（规模/阶段耗时 / 真实契约 / 形态对比 / 连通性 / 湖候选一致性 / 过滤状态）。 */
        public static String report() {
            StringBuilder sb = new StringBuilder("【分层水文诊断】(hierarchicalHydrology)\n");
            sb.append(String.format("  粗层: solves=%d quickSamples=%d lastMs=%d%n",
                    COARSE_SOLVES.get(), COARSE_SAMPLES.get(), LAST_COARSE_MS));
            sb.append(String.format("  细层: solves=%d lastMs=%d cells=%d 窗外owner权威=%d(缺 %d) 区内无命中=%d%n",
                    FINE_SOLVES.get(), LAST_FINE_MS, LAST_FINE_CELLS,
                    EXTERIOR_OVERRIDES.get(), EXTERIOR_MISSES.get(), IN_TRACT_NO_HIT.get()));
            sb.append(String.format("  粗层湖候选(仅校验): 一致=%d 不一致(侵蚀漏水)=%d%n",
                    COARSE_LAKE_CANDIDATE_AGREE.get(), COARSE_LAKE_CANDIDATE_DISAGREE.get()));
            sb.append(String.format("  形态对比(仅记录，不判失败): 出口移动=%d 候选消失=%d 水位不同=%d 别区halo盆地=%d%n",
                    OUTLET_MOVED.get(), BASIN_EVOLVED_OUT.get(), LEVEL_MISMATCH.get(),
                    FOREIGN_HALO_BASINS.get()));
            sb.append(String.format("  真实契约: 归属缺口=%d 非有限/越域不满足次数=%d%n",
                    CONTRACT_GAPS.get(), CONTRACT_UNSATISFIED.get()));
            sb.append(String.format("  截断上下文=%d ⇒ NOT VERIFIED（不得当作“无限跨区已证明”）%n",
                    TRUNCATED_CONTEXT.get()));
            sb.append(String.format("  连通性(独立审计): 河格=%d 孤立格=%d(%s) 孤立链=%d%n",
                    CHANNEL_CELLS.get(), ORPHAN_CELLS.get(),
                    pct(ORPHAN_CELLS.get(), CHANNEL_CELLS.get()), ORPHAN_CHAINS.get()));
            sb.append(String.format("  边框未验证格=%d(%s) 成环格=%d ⇒ 二者皆不得当连通 PASS%n",
                    BOUNDARY_UNVERIFIED_CELLS.get(),
                    pct(BOUNDARY_UNVERIFIED_CELLS.get(), CHANNEL_CELLS.get()), LOOP_CELLS.get()));
            sb.append("  骨架网络过滤: 生产默认=").append(skeletonFilterDefault())
                    .append(" 实验最近一次构建=").append(LAST_SKELETON_FILTER_ENABLED)
                    .append("(删=").append(LAST_SKELETON_FILTER_DROPPED).append("条)")
                    .append(LAST_SKELETON_FILTER_ENABLED
                            ? " ⇒ 几何已被删，不得作连通 PASS\n"
                            : " ⇒ 几何完整（孤立已如实计入）\n");
            sb.append("  孤立根样本: ").append(join(ORPHAN_SAMPLES)).append('\n');
            sb.append(sharedReport());
            sb.append("  共享域样本: ").append(join(SHARED_SAMPLES)).append('\n');
            sb.append("  规模/阶段: ");
            for (String k : new java.util.TreeSet<>(LAYER.keySet())) {
                sb.append(k).append('=').append(LAYER.get(k)).append(' ');
            }
            sb.append('\n').append("  缺口样本: ").append(join(GAP_SAMPLES)).append('\n');
            sb.append("  校验样本: ").append(join(VALIDATION_SAMPLES)).append('\n');
            return sb.toString();
        }

        /**
         * 骨架侧网络过滤（{@code DeterministicSkeleton.NETWORK_FILTER}）的生产默认值。
         * 分层实验构建时显式传 {@code FALSE} 关闭它 —— 见 {@link #LAST_SKELETON_FILTER_ENABLED}。
         */
        public static boolean skeletonFilterDefault() {
            return DeterministicSkeleton.NETWORK_FILTER;
        }

        private static void append(List<String> sink, List<String> lines, int cap) {
            synchronized (sink) {
                for (String s : lines) {
                    if (sink.size() >= cap) return;
                    sink.add(s);
                }
            }
        }

        private static String join(List<String> list) {
            synchronized (list) {
                return list.isEmpty() ? "-" : String.join(" | ", list);
            }
        }

        /** 百分比（分母 0 ⇒ "0.0%"）。 */
        private static String pct(long part, long total) {
            return String.format("%.1f%%", 100.0 * part / Math.max(1L, total));
        }
    }
}
