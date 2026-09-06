package com.geogenesis.worldgen.hydrology.riverline;

import com.geogenesis.worldgen.hydrology.flowaccum.FlowField;
import com.geogenesis.worldgen.noise.NoiseUtil;
import com.geogenesis.worldgen.hydrology.riverline.RiverLineRegion.RiverPolyline;
import com.geogenesis.worldgen.terrain.HeightCurve;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 河线网络核心：region 级河网生成（汇流场派生）+ 距离场采样。
 *
 * <p><b>物理范式（2026-08-28 重写）</b>：河网不再由几何/分形线"画"出来，
 * 而是从地形汇流场"流"出来——高位布源、沿 D8 下坡追踪、汇入已有河，
 * 形成树状水系。每条河的水面 = 当地地表谷底（Streams 范式），河深/半宽由汇流面积驱动。</p>
 *
 * <p><b>确定性</b>：全部由 (worldSeed, rx, rz) 导出，复用 ConcurrentHashMap 惰性构建。</p>
 */
public final class RiverLineNetwork {

    /** 距离场采样结果。 */
    public record RiverLineHit(double distToCenter, double surfaceY, double width,
                               double depth, double dischargeArea,
                               boolean reachesOcean, boolean isLake,
                               double fallDrop, boolean frozen) { }

    /**
     * 跌水段水面阶跃位置：t &lt; 此值时取唇口水位，否则取跌水后水位。
     *
     * <p>节点间距 SMOOTH_SPACING(4wu) × horizontalScale(2) = 8 block；若沿用线性插值，
     * 一级 3 block 的跌水会被摊成 8 block 长的缓坡（急流而非瀑布）。取 0.88 使阶跃
     * 压缩到约 1 block 内，形成垂直水面落差。</p>
     */
    private static final double FALL_STEP_T = 0.88;

    /**
     * 源点最小汇流面积（单位：栅格数，1 格 = gridCell² wu²）。<b>1.0 = 不启用</b>。
     *
     * <p>水文成河判据（channel initiation）：山顶/分水岭的 D8 汇流面积仅自身 1 格，
     * 要求源点汇流面积 ≥ N 格即可把峰顶从候选中排除，河头落到山坳/谷头。</p>
     *
     * <p><b>★ 实测后定为 1.0（关闭），源头修复改由 riverAccumThreshold 的事后裁剪承担</b>
     * （种子 9139912035078620160 / 12345）：两者都能把山顶源头从 24%+29% 压到 0%，但</p>
     * <ul>
     *   <li>本筛 = 2 格 + 裁剪 4 格：河数 27、carvedNotch 169，但 runWaterfallProbe
     *       {@code angleFails=1}（布源筛改变了 run 剖面，使一级跌水坡角低于下限）；</li>
     *   <li>只用裁剪 4 格：河数 22、{@code angleFails=0} 全 PASS。</li>
     * </ul>
     * <p>保留本常量与判据代码，是因为它是调节"河源形态"的直接旋钮（若将来瀑布坡角
     * 判据放宽或另有修复，调回 2.0 可换回更高密度）。</p>
     */
    private static final double SOURCE_MIN_ACCUM_CELLS = 1.0;

    /**
     * 河头淡出跨度（节点数）：从源头起，河宽/河深沿程从残余值平滑升到满断面。
     *
     * <p><b>为什么需要（2026-09-01，用户实测截图）</b>：宽深走 Leopold-Maddock 幂律
     * {@code W = minWidth·(A/A_ref)^0.42}，有 minWidth 下限；成河门槛把源头裁到 4 格汇流
     * 面积后，节点 0 的断面仍是半宽 1.78 / 深 2.78 的<b>满尺寸槽</b>，然后在节点 0
     * 被硬截断 → 陡坡上出现一个钝圆的"浴缸尾"水池，上方既没有汇流山坳也没有渐细的
     * 支流，观感极假（"又不是山泉那样"）。</p>
     *
     * <p><b>参考项目做法</b>：Streams 的 RiverUpstreamComponent 上游端不放钝管——
     * ① 高程棘轮（{@code upstreamMinSurfaceLevelUnits} 按 heightDiff/6 逐步抬升要求水面），
     * ② {@code setMaxSurfaceLevels} 要求目标水面处必须是实心地形（拒绝悬空河头），
     * ③ 末端叠 {@code SourceModelPlans}：水流散成数条细小分流并要求足够高的 back wall。
     * DW 则是河宽 ∝ 汇流面积，源头天然趋细。</p>
     *
     * <p>本实现取两者共同要点的最小等价形式：<b>断面沿程淡出到零</b>，使河槽在上游端
     * 逐渐退化为贴地的细流并最终消失，而不是被截断。深度淡出到 0.06 倍时已低于灌水
     * 门控所需的 0.5 格水深，故水体会自然终止在细流处，不再暴露断面切口。</p>
     */
    private static final int HEAD_TAPER_NODES = 6;

    /** 河头最末端的残余半宽比例（× 满断面）。 */
    private static final double HEAD_MIN_WIDTH_FRACTION = 0.30;

    /** 河头最末端的残余水深比例（× 满断面）；足够小以让水体在细流处自然收束。 */
    private static final double HEAD_MIN_DEPTH_FRACTION = 0.06;

    /**
     * 汇流槽最小两侧抬升（block）：河头左右两侧地形须各高出此值，才算真在山谷里。
     * 0 会把"近似平肩"也算作槽；过大则河头被一路推到下游、河长损失。
     */
    private static final double VALLEY_MIN_RISE = 0.5;

    /**
     * 河源后方崖壁的最小抬升（block）：河头上游一步的地形须高出此值，否则视为落在
     * 台地/平地（河会显得"凭空冒出来"），继续往下游找河头。
     *
     * <p>对标 Streams 的 {@code minSourceBackWallHeight} 与
     * {@code RiverUpstreamComponent.setMaxSurfaceLevels} 的实心地形要求。</p>
     */
    private static final double SOURCE_BACK_WALL_RISE = 1.0;

    /** 河头淡出因子：k=0 → ≈0.05，k≥n → 1.0，smoothstep 保证沿程无拐点。 */
    private static double headTaper(int k, int m) {
        int n = Math.max(1, Math.min(HEAD_TAPER_NODES, m / 2));   // 短河不超一半长度
        if (k >= n) return 1.0;
        return NoiseUtil.smooth((k + 1.0) / (n + 1.0));
    }

    /**
     * 细流（feeder rill）专用淡出：首节点为 <b>0</b>，而非 {@link #headTaper} 的首节点残余。
     *
     * <p>主河河头即便收窄也应留一条可见断面（那是"河的起点"）；源前细流则应当
     * <b>真的消散掉</b>——往上越来越浅、最终退回坡面漫流。差别就在首节点：给残余
     * 宽度就是在坡面上切一刀，给零才是细流。</p>
     */
    private static double feederTaper(int k, int m) {
        int n = Math.max(1, Math.min(HEAD_TAPER_NODES, m / 2));
        if (k >= n) return 1.0;
        return NoiseUtil.smooth((double) k / n);
    }

    private static final int MAX_REGIONS = 256;

    /** 汇入评分中的邻近权重（PL-RGA RIVER_JOIN_DISTANCE_WEIGHT）：越低优先，等距时就近。 */
    private static final double RIVER_JOIN_DISTANCE_WEIGHT = 1e-6;

    /**
     * 诊断计数器：防交叉检测的"段比较"总次数（性能诊断用）。
     *
     * <p>{@link #segmentCrossesAny} 对全部已有段做线性扫描，是河网构建的 O(n²) 热点。
     * 单元尺度放大后段数暴涨，该值决定是否需要空间索引（见 platewiseregions 加速结构）。
     * 生产路径开销仅一次 addAndGet。</p>
     */
    private static final java.util.concurrent.atomic.AtomicLong CROSS_COMPARISONS =
            new java.util.concurrent.atomic.AtomicLong();

    /** 防交叉段比较总次数（自上次 reset 起）。 */
    public static long crossComparisons() {
        return CROSS_COMPARISONS.get();
    }

    /** 清零防交叉计数器（探针对比前后使用）。 */
    public static void resetCrossComparisons() {
        CROSS_COMPARISONS.set(0);
    }

    private final Map<Long, RiverLineRegion> regions = new ConcurrentHashMap<>();
    /** pass-1 region 缓存（无交接、含出口种子）。双-pass 交接：pass-2 只读邻 region 的 pass-1 种子。 */
    private final Map<Long, RiverLineRegion> regionsP1 = new ConcurrentHashMap<>();
    /** 选线/贴谷用轻量 e 场（terrainEQuick，纯噪声基础场，无侵蚀 tile 依赖 → 无递归风险）。 */
    private final MidpointDisplacement.ElevationSampler eSampler;
    /** 剖面锚定用地形 Y 采样（sampleWu，含侵蚀 tile delta —— 河流必须贴真实地表走）。 */
    private final TerrainYSampler terrainY;
    private final HeightCurve curve;
    private final RiverLineParams params;
    /** 水平缩放（block ÷ wu）：瀑布角度触发需把 wu 水平距换算成 block 空间（落差本就是 block）。 */
    private final double horizontalScale;
    private volatile long seed;

    /** 真实地表 Y 采样抽象（CellGenerator::sampleWu 注入；返回含侵蚀的最终 height）。 */
    public interface TerrainYSampler {
        double yAt(double wx, double wz);
    }

    public RiverLineNetwork(MidpointDisplacement.ElevationSampler eSampler,
                            HeightCurve curve, long seed) {
        this(eSampler, null, curve, seed, 2.0);
    }

    public RiverLineNetwork(MidpointDisplacement.ElevationSampler eSampler,
                            TerrainYSampler terrainY,
                            HeightCurve curve, long seed) {
        this(eSampler, terrainY, curve, seed, 2.0);
    }

    /** 带水平缩放的构造（瀑布角度触发用）：horizontalScale 缺省 2.0 仅用于无地形的兼容路径。 */
    public RiverLineNetwork(MidpointDisplacement.ElevationSampler eSampler,
                            TerrainYSampler terrainY,
                            HeightCurve curve, long seed, double horizontalScale) {
        this(eSampler, terrainY, curve, seed, horizontalScale, RiverLineParams.defaults());
    }

    public RiverLineNetwork(MidpointDisplacement.ElevationSampler eSampler,
                            TerrainYSampler terrainY,
                            HeightCurve curve, long seed, RiverLineParams params) {
        this(eSampler, terrainY, curve, seed, 2.0, params);
    }

    public RiverLineNetwork(MidpointDisplacement.ElevationSampler eSampler,
                            TerrainYSampler terrainY,
                            HeightCurve curve, long seed, double horizontalScale,
                            RiverLineParams params) {
        this.eSampler = eSampler;
        this.terrainY = terrainY;
        this.curve = curve;
        this.seed = seed;
        this.params = params;
        this.horizontalScale = horizontalScale;
    }

    public void setSeed(long next) {
        if (this.seed == next) return;
        this.seed = next;
        regions.clear();
        regionsP1.clear();
    }

    public void clear() {
        regions.clear();
        regionsP1.clear();
    }

    /**
     * 选线场高程：把原始汇流场 e 经 {@link RiverLineParams#routingE} 山压低后用于 FlowField 追踪，
     * 使河线在"压低地形"上走（贴谷、避峰），再经水面 blend 融回真实地形（PL-RGA firstHeightField）。
     */
    private double routingE(double wx, double wz) {
        return params.routingE(eSampler.eAt(wx, wz));
    }

    public int cachedRegions() {
        return regions.size();
    }

    /** region 边长（wu）。 */
    public double regionSize() {
        return params.regionSize();
    }

    // ===== 拓扑构建 =====

    public RiverLineRegion region(int rx, int rz) {
        long key = pack(rx, rz);
        RiverLineRegion r = regions.get(key);
        if (r != null) return r;
        if (!params.crossRegion()) {
            // 关闭跨 region 连续河：退化为单 pass（旧行为，到网格边整条回滚）。
            r = regions.computeIfAbsent(key, ignored -> build(rx, rz, false, List.of()));
            prune();
            return r;
        }
        // 双-pass：本 region 的 pass-2 吸收 4 邻 region 的 pass-1 出口种子作续流源。
        // pass-1 相互独立、无递归；pass-2 仅依赖邻 region 的 pass-1 结果（固定），顺序无关。
        List<RiverLineRegion.OutletSeed> incoming = new ArrayList<>();
        for (int dRX = -1; dRX <= 1; dRX++) {
            for (int dRZ = -1; dRZ <= 1; dRZ++) {
                if (dRX == 0 && dRZ == 0) continue;
                RiverLineRegion nb = regionPass1(rx + dRX, rz + dRZ);
                for (RiverLineRegion.OutletSeed o : nb.outlets) {
                    if (o.dRX == -dRX && o.dRZ == -dRZ) incoming.add(o);  // 邻的出口指向本 region
                }
            }
        }
        r = regions.computeIfAbsent(key, ignored -> build(rx, rz, true, incoming));
        prune();
        return r;
    }

    /** pass-1 构建（无交接，记录出口种子）。独立、无递归。 */
    private RiverLineRegion regionPass1(int rx, int rz) {
        long key = pack(rx, rz);
        RiverLineRegion r = regionsP1.get(key);
        if (r != null) return r;
        return regionsP1.computeIfAbsent(key, ignored -> build(rx, rz, false, List.of()));
    }

    /**
     * 构建 region：从地形汇流场派生河网（2026-08-28 物理范式重写）。
     *
     * <p>高位布源 → 沿 D8 下坡追踪 → 汇入已有河，生成树状水系；
     * 每条河的水面 = 当地地表谷底（Streams 范式），河深/半宽由汇流面积驱动。</p>
     *
     * <p>跨 region 连续河（{@code crossRegion}）：当 {@code handoff=true} 时，吸收
     * {@code incoming} 出口种子作强制续流源（携带上游汇流面积/层级/水面），使河流跨越
     * 640wu 瓦片缝后不中断、宽度不重置。</p>
     */
    private RiverLineRegion build(int rx, int rz, boolean handoff,
                                  List<RiverLineRegion.OutletSeed> incoming) {
        double regionSize = params.regionSize();
        double cell = params.gridCell();
        double margin = regionSize * 0.5;
        double minX = rx * regionSize - margin, maxX = rx * regionSize + regionSize + margin;
        double minZ = rz * regionSize - margin, maxZ = rz * regionSize + regionSize + margin;
        // ★ 选线场用"山压低"后的 e（routingE），使河线贴谷避峰；水面仍锚定真实地形（groundYAt）。
        FlowField field = new FlowField(minX, minZ, maxX, maxZ, cell, this::routingE);

        int nx = field.cols(), nz = field.rows();
        boolean[] claimed = new boolean[nx * nz];
        double[] nodeE = new double[nx * nz];          // 已接受河路径格 e（就近汇入用）
        java.util.Arrays.fill(nodeE, Double.NaN);
        double[] nodeSurf = new double[nx * nz];       // 已接受河路径格水面（交汇点继承用）
        java.util.Arrays.fill(nodeSurf, Double.NaN);
        int[] levelAt = new int[nx * nz];              // 已接受河路径格的分支层级（0 = 无河）
        List<RiverPolyline> rivers = new ArrayList<>();
        List<RiverSpec> specs = new ArrayList<>();   // 与 rivers 平行：meander 去交叉后处理用
        List<RiverLineRegion.LakeNode> lakes = new ArrayList<>();
        List<RiverLineRegion.OutletSeed> outlets = new ArrayList<>();
        List<int[]> allSegments = new ArrayList<>();   // 全局段集合（防交叉）
        boolean outletOcean = false;
        double maxDischarge = 0.0;

        // 候选源：e > sourceMinE、汇流面积达标、不在 region 边界安全距内，按 e 降序（高地优先）
        // ★ 汇流面积门限（2026-08-31）：原判据只有"高程 > sourceMinE"且候选纯按 e 降序
        //   取点 → 源点必然落在山顶/山脊。实测种子 9139912035078620160：源头 24% 在山顶、
        //   29% 在山脊、平均高程百分位 95%，落在谷头/洼地的只有 6%（用户实机反馈
        //   "河流源头大部分生成在山顶"）。山顶的 D8 汇流面积只有自身一格，加
        //   "≥ N 格汇流"即天然把分水岭峰顶排除，河头落到坡面汇流首成槽处
        //   （channel initiation，水文上的成河判据）。
        //   ★ 必须在【布源阶段】筛、而不是靠 commitRiver 的事后裁剪：裁剪会把小河整条
        //     裁掉（实测门槛提到 4 格后河数 34→16，密度腰斩）；布源筛则河从山坳起追，
        //     全程保留长度。
        double cellArea = params.gridCell() * params.gridCell();
        List<Integer> cand = new ArrayList<>();
        for (int i = 0; i < nx * nz; i++) {
            if (field.eAt(i) > params.sourceMinE()
                    && field.accumAt(i) >= SOURCE_MIN_ACCUM_CELLS * cellArea
                    // ★ 汇流槽判据（2026-09-01，用户："源头应该生成在山谷中"）：
                    //   只有 D8 汇流面积达标是不够的——开阔凸坡上汇流面积同样随下坡
                    //   累积而达标（坡面并无山谷却照样"汇流"）。实测种子 12345：源头
                    //   落在谷槽内的仅 19%，50% 在凸坡/山脊上（源点比垂直流向的某一侧
                    //   还高 2~5 格），即截图里"河槽切在坡面上"的形态。
                    && inValleyTrough(field, i, nx, nz)
                    && !nearRegionBorder(field, i, rx, rz, params.borderDist())) cand.add(i);
        }
        cand.sort((a, b) -> Double.compare(field.eAt(b), field.eAt(a)));
        int sourceCount = cand.size();
        int rolledBack = 0, joinedCount = 0;

        int spacing = params.sourceSpacingCells();
        int stepSize = params.traceStep();
        List<Integer> accepted = new ArrayList<>();
        int acceptedCount = 0;
        // ★ 细流【延后】到主河全部建完再统一发（见下方 post-pass）：若在源循环内就地
        //   提交，后来的主河看得见细流、但先提交的主河看不见 → 细流会切进先提交主河的
        //   谷壁（实测 insideOther 1→4，6%→21%）。
        List<RiverPolyline> feederParents = new ArrayList<>();
        List<Integer> feederLevels = new ArrayList<>();

        // ===== 普通源追踪（本 region 高位布源）=====
        for (int s : cand) {
            if (acceptedCount >= params.riverCount()) break;   // 河数量上限
            if (claimed[s]) continue;
            int si = s % nx, sj = s / nx;
            boolean tooClose = false;
            for (int acc : accepted) {
                if (Math.abs((acc % nx) - si) <= spacing
                        && Math.abs((acc / nx) - sj) <= spacing) { tooClose = true; break; }
            }
            if (tooClose) continue;
            // ★ 源头不得落在【已有河的过渡区（谷壁）】内（2026-09-01，用户："源头不应该
            //   生成在另外一条河的过渡区里面"）：claimed 只标记已有河的【中心线格】，
            //   其谷壁范围（valley = 3.5×半宽）内照样能布源 → 新河在别人的谷壁上
            //   再开一条槽（实测种子 12345 有源点侵入邻河谷壁 22 格）。
            //   只约束【普通布源】：跨 region 续流必须无条件接上，见下方 handoff 循环。
            if (insideExistingValley(field, rivers, s)) continue;

            TraceOutcome out = traceRiver(field, s, stepSize, claimed, nodeE,
                    allSegments, nx, nz, rx, rz, Double.NaN);
            if (out == null) { rolledBack++; continue; }
            if (out.joined) joinedCount++;

            int level = 1;
            if (out.joined) {
                int last = out.cells.get(out.cells.size() - 1);
                int tl = levelAt[last];
                if (tl > 0) level = tl + 1;
            }
            CommitOut c = commitRiver(field, out, level, claimed, nodeE, nodeSurf,
                    levelAt, allSegments, rivers, specs, lakes, accepted, nx, Double.NaN, rx, rz, false);
            maxDischarge = Math.max(maxDischarge, c.maxDischarge());
            if (c.reachedOcean()) outletOcean = true;
            if (c.poly() != null) {
                // ★ 仅"成功成为河"的源点参与后续源点的间距过滤。
                accepted.add(s);
                acceptedCount++;
                if (out.outlet) collectOutlet(out, field, rx, rz, outlets, c.tailSurface(), level);
                // ★ 河源扇形散流：先记下这条主河，细流留到全部主河建完后统一发
                //   （细流本身不再发细流，也不计入 riverCount / accepted 间距过滤）。
                feederParents.add(c.poly());
                feederLevels.add(level);
            }
        }

        // ===== 跨 region 续流（handoff）：吸收上游邻 region 出口种子作强制源 =====
        if (handoff) {
            for (RiverLineRegion.OutletSeed seed : incoming) {
                if (acceptedCount >= params.riverCount()) break;
                int start = field.indexOf(seed.wx, seed.wz);
                if (start < 0) continue;
                // ★ 容错：邻 region 栅格相对上游偏移最多 16wu，种子格可能恰落局部极小（无下坡→续流即死）。
                //   在 1 格窗口内改选有真实下坡的格作续流起点，避免跨缝断流。
                start = bestHandoffStart(field, start, nx, nz);
                if (claimed[start]) continue;
                int si = start % nx, sj = start / nx;
                boolean tooClose = false;
                for (int acc : accepted) {
                    if (Math.abs((acc % nx) - si) <= spacing
                            && Math.abs((acc / nx) - sj) <= spacing) { tooClose = true; break; }
                }
                if (tooClose) continue;

                TraceOutcome out = traceRiver(field, start, stepSize, claimed, nodeE,
                        allSegments, nx, nz, rx, rz, seed.accum);
                if (out == null) continue;
                if (out.joined) joinedCount++;
                // 继承上游层级；若续流汇入本 region 已有河，则为该河支流（层级+1）
                int level = seed.level;
                if (out.joined) {
                    int last = out.cells.get(out.cells.size() - 1);
                    int tl = levelAt[last];
                    if (tl > 0) level = tl + 1;
                }
                // 续流首节点水面 = 上游尾节点水面（保证跨缝水面连续，无台阶）
                CommitOut c = commitRiver(field, out, level, claimed, nodeE, nodeSurf,
                        levelAt, allSegments, rivers, specs, lakes, accepted, nx, seed.surfaceY,
                        rx, rz, false);
                maxDischarge = Math.max(maxDischarge, c.maxDischarge());
                if (c.reachedOcean()) outletOcean = true;
                if (c.poly() != null) {
                    accepted.add(start);
                    acceptedCount++;
                }
            }
        }

        // ===== 河源扇形散流（post-pass，2026-09-06）=====
        // 必须在【所有主河】建完之后发：细流经 advanceToValleyHead 会避开此刻已存在的
        // 全部谷壁（含先提交的主河），而主河的源头筛查不会被细流反向污染。
        for (int i = 0; i < feederParents.size(); i++) {
            emitFeederRills(field, feederParents.get(i), feederLevels.get(i), claimed, nodeE,
                    nodeSurf, levelAt, allSegments, rivers, specs, lakes, accepted, nx, rx, rz);
        }

        // ★ meander 去交叉后处理（2026-08-31）：见 commitRiver 注释。区域全部河建好后，
        //   迭代把"与别的河真交叉"的河重建为无 meander（其非 meander 路径沿用已防交叉的
        //   格路径），直到无交叉或无可去 meander 的河。解决单向 de-meander 修不了的
        //   "先提交河 meander 摆进后提交河直线路径"情形。
        resolveMeanderCrossings(rivers, specs);

        return new RiverLineRegion(rx, rz, rivers, lakes, outlets, outletOcean, maxDischarge,
                sourceCount, rolledBack, joinedCount);
    }

    /** 一条河的平滑原始输入（供 meander 去交叉后处理整条重建）。 */
    private static final class RiverSpec {
        final MidpointDisplacement.Node[] nodes;
        final double[] surf, wid, dep;
        final int level;
        boolean meandered;     // 当前 rivers 里这条是否带 meander（可被去 meander）
        RiverSpec(MidpointDisplacement.Node[] nodes, double[] surf, double[] wid,
                  double[] dep, int level, boolean meandered) {
            this.nodes = nodes; this.surf = surf; this.wid = wid; this.dep = dep;
            this.level = level; this.meandered = meandered;
        }
    }

    /** 提交一条已追踪河流：认领/记录/裁剪/算宽深/水面，返回最终折线（null=被阈值丢弃）。 */
    private CommitOut commitRiver(FlowField field, TraceOutcome out, int level,
                                  boolean[] claimed, double[] nodeE, double[] nodeSurf,
                                  int[] levelAt, List<int[]> allSegments,
                                  List<RiverPolyline> rivers, List<RiverSpec> specs,
                                  List<RiverLineRegion.LakeNode> lakes,
                                  List<Integer> accepted, int nx, double forcedSrcH, int rx, int rz,
                                  boolean feeder) {
        for (int c : out.cells) {
            claimed[c] = true;
            nodeE[c] = field.eAt(c);
            if (levelAt[c] == 0) levelAt[c] = level;   // 交汇节点已属主流，勿覆盖其层级（PL-RGA 节点共享）
        }
        for (int k = 0; k < out.cells.size() - 1; k++) {
            int a = out.cells.get(k), b = out.cells.get(k + 1);
            allSegments.add(new int[]{a % nx, a / nx, b % nx, b / nx});
        }
        // 汇流面积阈值裁剪源头细流（树状稀疏）
        int start = 0;
        // ★ 细流（feeder）跳过本裁剪：rill 的每一格 accum 都【低于】成河门槛——那正是它
        //   作为"源前细流"的定义。按门槛裁会让 start 一路走到末尾、整条被裁光（实测
        //   只有河头恰好落在高 accum 处的极少数 rill 存活，且存活的全是质量最差的）。
        //   rill 的长度与形态改由 emitFeederRills 的格数下限保证。
        if (!feeder) {
            while (start < out.cells.size()
                    && out.accum[start] <= params.riverAccumThreshold()) start++;
        }
        // ★ 河头必须落在【汇流槽】里（2026-09-01，用户："源头应该生成在山谷中"）：
        //   布源阶段筛的是"起点格"，但可见河头是上面这条裁剪循环决定的那一格——
        //   河头会沿程下移到没被筛过的格子，所以只筛起点不够（实测只筛起点时凸坡
        //   源头仍有 39%）。这里继续裁到"该格位于谷槽"为止，直接控制河头位置。
        //   兜底：若为此牺牲到不足 minRiverNodes，则退回仅按汇流面积裁剪的结果——
        //   宁可保留一条源头略欠理想的河，也不让整条河消失（密度已压缩过多轮）。
        int valleyStart = advanceToValleyHead(field, out, start, rivers);
        if (out.cells.size() - valleyStart >= params.minRiverNodes()) {
            start = valleyStart;                                  // 有达标谷槽，直接采用
        } else if (feeder) {
            // ★ 细流【不达标即弃】，不做 bestValleyHead 兜底：兜底会把细流头落在凸坡肩部，
            //   实测使凸坡源头 0%→5%、钝头 1→5 —— 净负面。主河为保河网密度才允许兜底；
            //   细流是河头的装饰，"没有"远好于"切在光坡上"。
            return new CommitOut(null, out.reachedOcean, 0.0, null, Double.NaN);
        } else {
            // 全程找不到达标谷槽（或为此会把河裁没）：退而求其次取"最像谷槽"的一格，
            // 而不是停在恰好达汇流门槛的任意位置（实测该任意位置两个种子各有 22% 落在
            // 凸坡肩部）。bestValleyHead 的上界已预留 minRiverNodes，不会把河裁丢。
            int fallback = bestValleyHead(field, out, start);
            if (out.cells.size() - fallback >= params.minRiverNodes()) start = fallback;
        }
        if (out.cells.size() - start < params.minRiverNodes())
            return new CommitOut(null, out.reachedOcean, 0.0, null, Double.NaN);
        int m = out.cells.size() - start;
        MidpointDisplacement.Node[] nodes = new MidpointDisplacement.Node[m];
        double[] rawSurf = new double[m], wid = new double[m], dep = new double[m];
        double acc = 0.0;
        // ★ 只有【真源头】淡出：跨 region 续流的"源端"是瓦片缝而非河源，在此收窄会
        //   在缝上重新造成宽度骤缩（crossRegion 机制专门修掉的那个"宽度重置"断缝）。
        //   forcedSrcH 非 NaN 即为续流（见 build() 的 seed.surfaceY 传参）。
        boolean taperHead = Double.isNaN(forcedSrcH);
        for (int k = 0; k < m; k++) {
            int idx = out.cells.get(start + k);
            double wx = field.cellCenterX(idx), wz = field.cellCenterZ(idx);
            double a = out.accum[start + k];
            nodes[k] = new MidpointDisplacement.Node(wx, wz);
            rawSurf[k] = groundYAt(wx, wz);
            double w = widthFromAccum(a, params);
            double d = depthFromAccum(a, w, params);
            if (taperHead) {
                double tp, wf, df;
                if (feeder) {
                    // ★ 细流必须淡出到【零】断面：真细流是越往上越浅、最终在坡面散开成
                    //   漫流；沿用主河那种保留残余宽深的淡出，细流头就成"切在坡面上的
                    //   一条断面"——实测使钝头率 1→6(29%)、凸坡源头 0%→5%，净负面。
                    tp = feederTaper(k, m);
                    wf = tp; df = tp;
                    w = Math.max(w * wf, 1.0);   // 1 格下限：防雕刻按 0 宽除零
                    d = df * d;
                } else {
                    tp = headTaper(k, m);
                    w *= HEAD_MIN_WIDTH_FRACTION + (1.0 - HEAD_MIN_WIDTH_FRACTION) * tp;
                    d *= HEAD_MIN_DEPTH_FRACTION + (1.0 - HEAD_MIN_DEPTH_FRACTION) * tp;
                }
                // 宽深比护栏按淡出后的宽度重算（淡出后 W 变小，D 不得再按原 W 放行）
                d = Math.min(d, params.maxDepthRatio() * w);
            }
            wid[k] = w;
            dep[k] = d;
            acc = Math.max(acc, a);
        }
        // 出口水面：入海→海平面附近；汇入主流→继承主流在交汇点水面（PL-RGA 节点共享）；否则贴地形
        int junctionCell = out.cells.get(out.cells.size() - 1);
        double junctionGround = groundYAt(field.cellCenterX(junctionCell), field.cellCenterZ(junctionCell));
        double outletSurf;
        if (out.reachedOcean || junctionGround < curve.seaLevelY()) {
            // 入海/海侵出口：水面由海平面决定，不再沿河床继续下探到海底。
            outletSurf = curve.seaLevelY();
        } else if (out.joined && !Double.isNaN(nodeSurf[junctionCell])) {
            outletSurf = nodeSurf[junctionCell];   // 继承主流交汇点水面 → 交汇处零台阶
        } else {
            outletSurf = junctionGround;
        }
        double[] surf = applyRiverHeightSlopeDrop(nodes, rawSurf, wid, outletSurf, params,
                Double.isNaN(forcedSrcH) ? null : forcedSrcH, out.reachedOcean);
        // ★ meander 防交叉（2026-08-31）：D8 追踪的 segmentCrossesAny 只防【原始格路径】
        //   交叉；meander（±2.5 格横向正弦）在其后叠加，会让近平行的两条河互相穿插
        //   （实测 #6×#7 交叉 8 次、水面差 4.1 格 → 交汇"上下错层"）。先按满 meander
        //   平滑，若与已接受河真交叉则整条去 meander 重来（非 meander 路径沿用已防
        //   交叉的格路径，不再穿插）。
        RiverPolyline smoothed = smoothPath(nodes, surf, wid, dep, level, 1.0);
        // ★ 交汇继承必须用【瀑布处理后】的真实水面（2026-08-31）：smoothPath 内
        //   applyWaterfalls 会把 tread 上游节点抬到阶梯水位，而 surf 是抬升前的贴地
        //   剖面。旧代码用 surf 回写 nodeSurf → 支流在 tread 区汇入时继承到瀑布前的
        //   旧（低）水位，与主流实际（高 tread）水位不符 → 交汇处上下错层（实测：
        //   一条河穿过另一条河并错层）。改为从 smoothed 折线按最近节点回采真实水面。
        for (int k = 0; k < m; k++) {
            nodeSurf[out.cells.get(start + k)] =
                    smoothed.surfaceY[nearestNodeIndex(smoothed, nodes[k].x(), nodes[k].z())];
        }
        double tailSurface = surf[m - 1];
        rivers.add(smoothed);
        specs.add(new RiverSpec(nodes, surf, wid, dep, level, m >= 3));
        RiverLineRegion.LakeNode lake = null;
        if (out.isLake) {
            int last = out.cells.get(out.cells.size() - 1);
            lake = new RiverLineRegion.LakeNode(
                    field.cellCenterX(last), field.cellCenterZ(last), tailSurface);
        }
        return new CommitOut(smoothed, out.reachedOcean, acc, lake, tailSurface);
    }

    /**
     * meander 去交叉后处理：迭代找出与别的河真交叉的河，重建为无 meander，直到无交叉。
     * 优先去 meander 当前仍带 meander 的那条（去后其路径=已防交叉的格路径，不再穿插）。
     */
    private void resolveMeanderCrossings(List<RiverPolyline> rivers, List<RiverSpec> specs) {
        boolean changed = true;
        int guard = 0;
        while (changed && guard++ < 8) {
            changed = false;
            for (int i = 0; i < rivers.size() && !changed; i++) {
                for (int j = i + 1; j < rivers.size(); j++) {
                    // 只对"水面确有显著错层"的交叉去 meander：delta≈0 的近水平叠合
                    // 视觉无害，且重建会重跑 applyWaterfalls 扰动瀑布判定，尽量不碰。
                    double delta = crossLevelDelta(rivers.get(i), rivers.get(j));
                    if (delta <= 1.5) continue;
                    int victim = specs.get(i).meandered ? i
                            : (specs.get(j).meandered ? j : -1);
                    if (victim < 0) continue;   // 两条都已直，交叉来自基路径/跨源，去 meander 无解
                    RiverSpec sp = specs.get(victim);
                    rivers.set(victim, smoothPath(sp.nodes, sp.surf, sp.wid, sp.dep, sp.level, 0.0));
                    sp.meandered = false;
                    changed = true;
                    break;
                }
            }
        }
    }

    /** 两条折线所有真交点处的最大水面差（无交点返回 0）。 */
    private static double crossLevelDelta(RiverPolyline a, RiverPolyline b) {
        double max = 0.0;
        int na = a.nodes.length, nb = b.nodes.length;
        for (int i = 0; i + 1 < na; i++) {
            double x1 = a.nodes[i].x(), y1 = a.nodes[i].z();
            double x2 = a.nodes[i + 1].x(), y2 = a.nodes[i + 1].z();
            for (int j = 0; j + 1 < nb; j++) {
                double x3 = b.nodes[j].x(), y3 = b.nodes[j].z();
                double x4 = b.nodes[j + 1].x(), y4 = b.nodes[j + 1].z();
                double d = (x2 - x1) * (y4 - y3) - (y2 - y1) * (x4 - x3);
                if (Math.abs(d) < 1e-12) continue;
                double t = ((x3 - x1) * (y4 - y3) - (y3 - y1) * (x4 - x3)) / d;
                double u = ((x3 - x1) * (y2 - y1) - (y3 - y1) * (x2 - x1)) / d;
                if (t > 1e-6 && t < 1 - 1e-6 && u > 1e-6 && u < 1 - 1e-6) {
                    double sa = a.surfaceY[i] + (a.surfaceY[i + 1] - a.surfaceY[i]) * t;
                    double sb = b.surfaceY[j] + (b.surfaceY[j + 1] - b.surfaceY[j]) * u;
                    max = Math.max(max, Math.abs(sa - sb));
                }
            }
        }
        return max;
    }

    /** 折线上距给定点最近的节点索引（用于把重采样后的真实水面回采到原始格）。 */
    private static int nearestNodeIndex(RiverPolyline pl, double x, double z) {
        int best = 0;
        double bestD = Double.POSITIVE_INFINITY;
        for (int i = 0; i < pl.nodes.length; i++) {
            double dx = pl.nodes[i].x() - x, dz = pl.nodes[i].z() - z;
            double d = dx * dx + dz * dz;
            if (d < bestD) { bestD = d; best = i; }
        }
        return best;
    }

    /** 收集出口种子：本河到达网格边（缝外 margin）时，记录其尾节点供下游邻 region 续流。 */
    private void collectOutlet(TraceOutcome out, FlowField field, int rx, int rz,
                               List<RiverLineRegion.OutletSeed> outlets, double tailSurface, int level) {
        int last = out.cells.get(out.cells.size() - 1);
        double wx = field.cellCenterX(last), wz = field.cellCenterZ(last);
        double accum = out.accum[out.accum.length - 1];
        double R = params.regionSize();
        double cx = rx * R + R * 0.5, cz = rz * R + R * 0.5;
        int dRX = wx >= cx ? 1 : -1;
        int dRZ = wz >= cz ? 1 : -1;
        outlets.add(new RiverLineRegion.OutletSeed(dRX, dRZ, wx, wz, accum, tailSurface, level));
    }

    /** 提交结果：最终折线（null=丢弃）+ 是否入海 + 主河汇流面积 + 内流湖 + 尾节点水面。 */
    private record CommitOut(RiverPolyline poly, boolean reachedOcean,
                             double maxDischarge, RiverLineRegion.LakeNode lake, double tailSurface) { }

    /** 追踪结果：路径格序列 + 终止类型 + 每格汇流面积 + 是否出口（到网格边）；null = 整条回滚。 */
    private static final class TraceOutcome {
        final List<Integer> cells;
        final boolean reachedOcean;
        final boolean isLake;
        final boolean joined;     // 终止于汇入已接受河（树状汇流）
        final double[] accum;     // 每格汇流面积（wu²）；交接续流时含上游携带面积
        final boolean outlet;     // 终止于网格边（缝外 margin）→ 出口种子，交下游续流
        TraceOutcome(List<Integer> cells, boolean reachedOcean, boolean isLake,
                     boolean joined, double[] accum, boolean outlet) {
            this.cells = cells; this.reachedOcean = reachedOcean; this.isLake = isLake;
            this.joined = joined; this.accum = accum; this.outlet = outlet;
        }
    }

    /**
     * 沿下坡窗口追踪一条河，带回滚/就近汇入/湖终止（PL-RGA 对齐）。
     * null = 整条回滚：无安全下坡且越界/边界、或自环/交叉且无汇入。
     *
     * <p>{@code initialAccum} 为交接续流的携带汇流面积（NaN=普通源，用 field.accumAt）；
     * 非 NaN 时首格面积=initialAccum，后续叠加本格 field 累积，保证宽度跨缝连续。</p>
     *
     * <p>到达网格边（缝外 margin）且 {@code crossRegion} 开启：不再回滚，标记 {@code outlet}
     * 保留该河，由 build 收集出口种子交下游邻 region 续流。</p>
     */
    private TraceOutcome traceRiver(FlowField field, int start, int stepSize,
                                    boolean[] claimed, double[] nodeE,
                                    List<int[]> allSegments, int nx, int nz,
                                    int rx, int rz, double initialAccum) {
        int cur = start;
        List<Integer> path = new ArrayList<>();
        List<Double> accumList = new ArrayList<>();
        boolean[] seen = new boolean[claimed.length];
        boolean reachedOcean = false, isLake = false, joined = false, outlet = false;
        while (true) {
            if (claimed[cur]) { pushCell(path, accumList, cur, field, initialAccum); joined = true; break; }   // 汇入已接受河
            if (seen[cur]) {                                     // 自环
                if (!nearRegionBorder(field, cur, rx, rz, params.borderDist())) {
                    isLake = true; pushCell(path, accumList, cur, field, initialAccum); break;
                }
                return null;
            }
            pushCell(path, accumList, cur, field, initialAccum);
            seen[cur] = true;
            if (field.eAt(cur) <= params.oceanE()) { reachedOcean = true; break; }

            int join = nearbyDownhillNode(field, cur, stepSize, nodeE,  nx, nz, allSegments);
            if (join >= 0) { pushCell(path, accumList, join, field, initialAccum); joined = true; break; }   // 就近汇入树状

            int down = downhillNeighbor(field, cur, stepSize, nx, nz);
            if (down < 0) {
                // 无下坡：下坡在邻 region（网格边 或 边界伪极小）→ 出口交下游续流；
                // 仅远离边界的真洼地才成湖（PL-RGA：tile 边界伪极小不应成湖，应流向下一瓦片）。
                if (field.touchesGridEdge(cur)
                        || nearRegionBorder(field, cur, rx, rz, params.borderDist())) {
                    outlet = true; break;
                }
                isLake = true; break;                            // 远离边界的真内流洼地 → 成湖
            }
            int cri = cur % nx, crj = cur / nx, dri = down % nx, drj = down / nx;
            if (segmentCrossesAny(cri, crj, dri, drj, allSegments)) {
                if (claimed[down]) { pushCell(path, accumList, down, field, initialAccum); joined = true; break; }   // 交叉但可汇入
                return null;                                      // 交叉且无汇入 → 回滚
            }
            cur = down;
        }
        if (path.size() < params.minRiverNodes()) return null;
        double[] accum = new double[accumList.size()];
        for (int k = 0; k < accum.length; k++) accum[k] = accumList.get(k);
        return new TraceOutcome(path, reachedOcean, isLake, joined, accum, outlet);
    }

    /** 入队一个格，同步写入 path 与 accum（保证两者长度相等）。 */
    private static void pushCell(List<Integer> path, List<Double> accumList, int c,
                                 FlowField field, double initialAccum) {
        path.add(c);
        double a = Double.isNaN(initialAccum) ? field.accumAt(c)
                : (path.size() == 1 ? initialAccum : initialAccum + field.accumAt(c));
        accumList.add(a);
    }

    /**
     * step 窗口内"已存在且更低"的河节点（PL-RGA _nearbyDownhillRiverNode）：树状汇入。
     *
     * <p>★ 对齐参考：① 连接会穿过已有河段则跳过（_wouldCrossExistingSegments），
     * 否则支流跨河汇入、在交汇处产生交叉支流；② 评分用 {@code ne + 1e-6·d²}
     * （越低越好、等距就近），而非纯最低点——避免长距离跳跃汇入；
     * ③ 不排除 source 在本实现中无害（源点也已 claimed，跨河跳跃已被①拦截）。</p>
     */
    private int nearbyDownhillNode(FlowField field, int cur, int step,
                                   double[] nodeE, int nx, int nz, List<int[]> allSegments) {
        int ci = cur % nx, cj = cur / nx;
        double curE = field.eAt(cur);
        int best = -1;
        double bestScore = curE + 1e30;
        int minI = Math.max(0, ci - step), maxI = Math.min(nx - 1, ci + step);
        int minJ = Math.max(0, cj - step), maxJ = Math.min(nz - 1, cj + step);
        for (int j = minJ; j <= maxJ; j++) {
            for (int i = minI; i <= maxI; i++) {
                if (i == ci && j == cj) continue;
                int idx = j * nx + i;
                double ne = nodeE[idx];
                if (Double.isNaN(ne)) continue;
                if (ne >= curE - params.minDrop()) continue;     // 须严格更低（PL-RGA RIVER_MIN_DROP）
                // 连接会穿过已有河段 → 跳过（PL-RGA _wouldCrossExistingSegments）
                if (segmentCrossesAny(ci, cj, i, j, allSegments)) continue;
                double d2 = (i - ci) * (i - ci) + (j - cj) * (j - cj);
                double score = ne + RIVER_JOIN_DISTANCE_WEIGHT * d2;  // 越低越好，等距就近
                if (score < bestScore) { bestScore = score; best = idx; }
            }
        }
        return best;
    }

    /**
     * 该格是否位于【汇流槽（山谷）】中——等高线平面曲率为负的位置。
     *
     * <p>做法：取该格 D8 流向，其垂直方向即"横切河谷"的方向；若左右任一侧比该格
     * 更低，则该格处在凸坡/山脊 shoulders 上（水会向该侧散开），不是汇流槽。</p>
     *
     * <p>这是水文上区分"山谷"与"开阔坡面"的标准判据（plan contour curvature）。
     * 仅有汇流面积阈值不够：凸坡上每个下坡格的汇流面积同样随下坡累积而达标，
     * 于是源头会切在光秃坡面上（用户实测截图）。洼地（无更低邻居）本身即汇流
     * 终点，视为槽内。网格越界的一侧不参与否决。</p>
     */
    private static boolean inValleyTrough(FlowField field, int idx, int nx, int nz) {
        int ci = idx % nx, cj = idx / nx;
        int di = 1, dj = 0;                       // 洼地：任取一横向，两侧更高即算槽
        int down = field.flowTo(idx);
        if (down >= 0) {
            di = (down % nx) - ci;
            dj = (down / nx) - cj;
        }
        int pi = -dj, pj = di;                    // 垂直于流向
        if (pi == 0 && pj == 0) return true;
        double e0 = field.eAt(idx);
        for (int s = -1; s <= 1; s += 2) {
            int ni = ci + pi * s, nj = cj + pj * s;
            if (ni < 0 || ni >= nx || nj < 0 || nj >= nz) continue;   // 越界不否决
            if (field.eAt(nj * nx + ni) < e0) return false;           // 该侧更低 → 非汇流槽
        }
        return true;
    }

    /**
     * 从 {@code start} 起向后找第一个【实际地形汇流槽】格，作为可见河头。
     *
     * <p>★ 必须用 {@link #groundYAt}（与渲染同源的地形高程），不能用
     * {@link FlowField#eAt}（纯噪声 e 场、不含侵蚀 tile）：实测按 e 场判定"已在槽内"
     * 的格子，渲染地形上仍是凸坡，源头指标纹丝不动（28%/33%/39% 与只筛起点时完全
     * 一致）。用户看到的是渲染地形，判据就必须建立在同一份数据上。</p>
     *
     * <p>横向 = 路径局部方向的垂直方向；左右各取 1 格横距。两侧地形都不低于该格
     * （留 {@code VALLEY_MIN_RISE} 格余量）才算汇流槽，否则说明该格处在凸坡肩部——
     * 水会向低的那侧散开，不该是河源。</p>
     *
     * @return 槽内格的下标；全程无槽时返回 cells.size()（调用方据此回退）
     */
    private int advanceToValleyHead(FlowField field, TraceOutcome out, int start,
                                    List<RiverPolyline> rivers) {
        int n = out.cells.size();
        double off = Math.max(8.0, params.gridCell());        // 横距（wu）
        for (int k = start; k < n; k++) {
            int cur = out.cells.get(k);
            int nxt = (k + 1 < n) ? out.cells.get(k + 1) : cur;
            double ax = field.cellCenterX(cur), az = field.cellCenterZ(cur);
            double dx = field.cellCenterX(nxt) - ax, dz = field.cellCenterZ(nxt) - az;
            double len = Math.hypot(dx, dz);
            if (len < 1e-6) return k;                          // 末格：无方向，视为可用
            if (valleyMargin(field, out, k) < VALLEY_MIN_RISE) continue;
            double h0 = groundYAt(ax, az);
            // ★ back wall（2026-09-06，对标 Streams：源头须有后方崖壁 ——
            //   RiverComponent:234 的 minSourceBackWallHeight、以及
            //   RiverUpstreamComponent:96-97 要求目标水面处必须是实心地形）。
            //   只测左右两侧仍可能让河头落在台地/平地上：后方一马平川，
            //   河就成了"凭空冒出来"的一条槽。要求上游一步的地形明显更高，
            //   河头才落在坡脚，符合泉眼从坡下渗出的形态。
            double ux = -dx / len * off, uz = -dz / len * off;   // 上游方向
            if (groundYAt(ax + ux, az + uz) < h0 + SOURCE_BACK_WALL_RISE) continue;
            // ★ 同样要在【河头这一格】上检查"不在邻河谷壁内"：布源筛查的是源点格，
            //   而可见河头由本循环决定，两者不是同一格（实测只在源点筛时，侵入邻河
            //   的源头仍剩 1 处）。支流出口照常汇入主流，不受此限（只约束上游端）。
            if (!insideExistingValley(field, rivers, cur)) return k;
        }
        return n;
    }

    /**
     * 该格的【汇流槽横向裕度】（block）：垂直于流向的左右两侧地形高程的最小值，
     * 减去该格自身高程。
     *
     * <p>&gt;0 = 两侧都更高（真汇流槽/山谷）；&lt;0 = 至少一侧更低（凸坡肩部，水会向
     * 那一侧散开）。高程一律走 {@link #groundYAt}（= 生产的 terrainY = sampleWu，
     * 含侵蚀 tile），与 SourceValleyProbe 的复核口径一致。</p>
     *
     * @return 裕度（block）；末格等无法定向的情形返回 +∞（视为可用）
     */
    private double valleyMargin(FlowField field, TraceOutcome out, int k) {
        int n = out.cells.size();
        double off = Math.max(8.0, params.gridCell());
        int cur = out.cells.get(k);
        int nxt = (k + 1 < n) ? out.cells.get(k + 1) : cur;
        double ax = field.cellCenterX(cur), az = field.cellCenterZ(cur);
        double dx = field.cellCenterX(nxt) - ax, dz = field.cellCenterZ(nxt) - az;
        double len = Math.hypot(dx, dz);
        if (len < 1e-6) return Double.POSITIVE_INFINITY;
        double px = -dz / len * off, pz = dx / len * off;
        double h0 = groundYAt(ax, az);
        return Math.min(groundYAt(ax - px, az - pz), groundYAt(ax + px, az + pz)) - h0;
    }

    /**
     * 回退选择：整条路径【没有】达标谷槽时，取"最像谷槽"（{@link #valleyMargin} 最大）
     * 的一格作河头。
     *
     * <p>原先这种情形直接退回"恰好达汇流门槛"的那一格——那是个【任意位置】，实测
     * 两个种子都有 22% 的河头落在凸坡肩部（横向裕度 &lt; -1 格），正是用户看到的
     * "源头切在光秃坡面上"。改为在全程范围内挑横向收敛最强的一格：找不到谷槽时
     * 至少停在最接近谷槽的地方，且【不额外丢河】（不改变河网密度）。</p>
     *
     * <p>搜索上界留出 {@code minRiverNodes} 个节点，保证不会把河裁到被丢弃。</p>
     */
    private int bestValleyHead(FlowField field, TraceOutcome out, int start) {
        int n = out.cells.size();
        // ★ 钳制：上游的汇流面积裁剪可能已把 start 推到 n（整条都被裁掉），
        //   此时无格可选，直接返回 n 交由调用方按"河太短"丢弃，不得越界取格。
        if (start < 0 || start >= n) return n;
        int last = Math.min(n - 1, Math.max(start, n - params.minRiverNodes()));
        int best = start;
        double bestMargin = Double.NEGATIVE_INFINITY;
        for (int k = start; k <= last; k++) {
            double m = valleyMargin(field, out, k);
            if (m > bestMargin) { bestMargin = m; best = k; }
        }
        return best;
    }

    // ===== 河源扇形散流（2026-09-06）=====

    /**
     * 每个河头最多发几条补给细流（<b>0 = 关闭本特性</b>）。
     *
     * <p>实测权衡（region 级，seed 12345 / 9139912035078620160）：</p>
     * <ul>
     *   <li>✓ 河网 +1 / +3 条（20→21 / 18→21），河头出现扇形源前流；</li>
     *   <li>✓ 地形位置指标零回退：谷槽内 50% / 79%、凸坡源头 11% / 0%、
     *       平均裕度 2.3 / 6.6，与关闭时完全一致（你的种子槽内还 78%→79%）；</li>
     *   <li>✓ insideOther 0 / 0（见下方"曾经的误判"）；</li>
     *   <li>~ 聚合断层 332→335（+0.9%），最大坎 9.0 不变；水列 17848→17935。</li>
     * </ul>
     *
     * <p><b>★ 曾经的误判（2026-09-06，务必别再被它吓住）</b>：本特性一度因
     * "insideOther 1→2 / 1→4"被默认关闭。那是<b>探针假阳性</b>，不是缺陷：
     * 细流按设计接在主河河头格上（{@code cells.add(head)}），而
     * SourceValleyProbe 用邻河<b>固定索引</b> {@code width[1]}（源头淡出后的最窄处）
     * 当整条河的谷壁半径，且不排除"邻河出口恰在本河河头"的合法汇流——于是每条
     * 被补给的<b>主河</b>都被判成侵入者。改为最近点局部半宽 + 豁免合法汇流后，
     * 关闭态基线本身也从 1/1 归 0，开启态同样 0/0。</p>
     *
     * <p>教训：本项目第五次栽在"探针与生产不一致"。前四次是<b>数据源</b>不同
     * （platform / angleFails / SourceValleyProbe 的 sample vs sampleWu / 落块取整），
     * 这次是<b>公式</b>不同。核对新特性时，判据必须逐行对齐生产实现，不能凭语义近似。</p>
     */
    private static final int FEEDER_COUNT = 2;
    /** 细流上溯的汇流面积下限（wu²）：低于此说明已进入坡面散流区，停止上溯。= 1 格。 */
    private static final double FEEDER_MIN_ACCUM = 576.0;
    /** 细流最多上溯格数（每格 = gridCell），限制源前流长度。 */
    private static final int FEEDER_MAX_CELLS = 8;

    /**
     * 在已成型河流的【河头】上游补 1~2 条细流，构成扇形／树枝状源前流。
     *
     * <p>真实河源极少是"一条孤线凭空开始"：水流是在山坳处由周围坡面【多股汇成】的，
     * 上游还有若干低于成河门槛的细沟。当前布源按 {@code riverAccumThreshold}（4 格
     * 汇流面积）裁掉了门槛以下的源前分支，于是河头呈现为"切在山坳上的一条断面"。</p>
     *
     * <p><b>完全复用 {@link #commitRiver}，不另写一套雕刻／水面逻辑</b>：</p>
     * <ul>
     *   <li>细流各格 accum 天然低于成河门槛 → {@code widthFromAccum}／
     *       {@code depthFromAccum} 自动给出细流尺寸，无需特判；</li>
     *   <li>{@code joined=true} 且交汇格 = 主河河头格，其 {@code nodeSurf} 已由主河
     *       回写（见 commitRiver 末尾）→ 出口水面继承主河河头水位，<b>汇入零台阶</b>；</li>
     *   <li>河头谷槽判据、源头宽深淡出、瀑布、meander 淡出与防交叉一律自动生效。</li>
     * </ul>
     */
    private void emitFeederRills(FlowField field, RiverPolyline parent, int parentLevel,
                                 boolean[] claimed, double[] nodeE, double[] nodeSurf,
                                 int[] levelAt, List<int[]> allSegments,
                                 List<RiverPolyline> rivers, List<RiverSpec> specs,
                                 List<RiverLineRegion.LakeNode> lakes,
                                 List<Integer> accepted, int nx, int rx, int rz) {
        if (FEEDER_COUNT <= 0 || parent.nodes.length == 0) return;
        int head = field.indexOf(parent.nodes[0].x(), parent.nodes[0].z());
        if (Double.isNaN(nodeSurf[head])) return;      // 主河未回写该格水面 → 无法零台阶汇入

        // 候选补给格：D8 直接汇水入河头的邻格，按汇流面积降序取前 FEEDER_COUNT 条
        List<Integer> ups = new ArrayList<>(field.upstreamOf(head));
        ups.removeIf(u -> claimed[u]);
        ups.sort((a, b) -> Double.compare(field.accumAt(b), field.accumAt(a)));
        int n = Math.min(FEEDER_COUNT, ups.size());

        for (int t = 0; t < n; t++) {
            // 从补给格继续上溯，每步取"汇水面积最大"的上游邻格（即主支），直到
            // 低于细流门槛 / 撞已有河 / 步数耗尽。
            // ★ 注意上游格 accum 必然【小于】当前格，故初值不能用 accumAt(cur) 比较，
            //   否则一步都走不动（写成那样时实测 rill 全部退化为 1 格、被 minRiverNodes 裁光）。
            int cur = ups.get(t);
            for (int step = 0; step < FEEDER_MAX_CELLS; step++) {
                int best = -1;
                double bestAccum = -1.0;
                for (int u : field.upstreamOf(cur)) {
                    if (claimed[u]) continue;
                    double a = field.accumAt(u);
                    if (a > bestAccum) { bestAccum = a; best = u; }
                }
                if (best < 0 || bestAccum < FEEDER_MIN_ACCUM) break;
                cur = best;
            }

            // 顺流回接到主河河头，得到细流的格序列
            List<Integer> cells = new ArrayList<>();
            int p = cur;
            for (int step = 0; step <= FEEDER_MAX_CELLS + 2 && p != head; step++) {
                if (p < 0) break;                       // 洼地：接不回河头
                cells.add(p);
                p = field.flowTo(p);
            }
            if (p != head) continue;                    // 未接到河头，放弃（不得留悬空细流）
            cells.add(head);
            // ★ 长度下限取 HEAD_TAPER_NODES：源头宽深淡出要 6 个节点才走完。短于它的
            //   rill 淡出未完成即入水，河头呈"断面"——实测短 rill 使钝头率 1→6（25%），
            //   反而拉低源头质量。宁缺毋滥。
            if (cells.size() < Math.max(HEAD_TAPER_NODES, params.minRiverNodes())) continue;

            double[] rAcc = new double[cells.size()];
            for (int i = 0; i < cells.size(); i++) rAcc[i] = field.accumAt(cells.get(i));
            TraceOutcome out = new TraceOutcome(cells, false, false, true, rAcc, false);
            commitRiver(field, out, parentLevel + 1, claimed, nodeE, nodeSurf, levelAt,
                    allSegments, rivers, specs, lakes, accepted, nx, Double.NaN, rx, rz, true);
        }
    }

    /**
     * 该格是否落在【已有某条河的过渡区（谷壁）】内。
     *
     * <p>谷壁影响半径 = 3.5 × 该处半宽（与 carver 的 valley 定义同源，单位 wu），
     * 点到河折线取线段最近距离。源点若落在邻河的谷壁里，新河就会在别人刚雕出的
     * 谷坡上再切一条槽 —— 即用户实测的"源头生成在另外一条河的过渡区里面"。</p>
     */
    private static boolean insideExistingValley(FlowField field,
                                                List<RiverPolyline> rivers, int cell) {
        double wx = field.cellCenterX(cell), wz = field.cellCenterZ(cell);
        for (RiverPolyline r : rivers) {
            int len = r.nodes.length;
            for (int i = 0; i < len; i++) {
                double ax = r.nodes[i].x(), az = r.nodes[i].z();
                double valley = Math.max(r.width[i], 1.0) * 3.5;
                double v2 = valley * valley;
                double ddx = wx - ax, ddz = wz - az;
                if (ddx * ddx + ddz * ddz <= v2) return true;
                if (i + 1 >= len) continue;
                double bx = r.nodes[i + 1].x(), bz = r.nodes[i + 1].z();
                double abx = bx - ax, abz = bz - az;
                double l2 = abx * abx + abz * abz;
                double t = l2 < 1e-9 ? 0.0
                        : Math.max(0.0, Math.min(1.0, (ddx * abx + ddz * abz) / l2));
                double qx = ax + abx * t - wx, qz = az + abz * t - wz;
                if (qx * qx + qz * qz <= v2) return true;
            }
        }
        return false;
    }

    /** 格是否距 region 边界 < borderDist（wu）：边界附近不布源/不成湖（PL-RGA border/lake_safe_mask）。 */
    private boolean nearRegionBorder(FlowField field, int idx, int rx, int rz, double borderDist) {
        double wx = field.cellCenterX(idx), wz = field.cellCenterZ(idx);
        double lo = rx * params.regionSize() + borderDist;
        double hi = rx * params.regionSize() + params.regionSize() - borderDist;
        double loZ = rz * params.regionSize() + borderDist;
        double hiZ = rz * params.regionSize() + params.regionSize() - borderDist;
        return wx < lo || wx > hi || wz < loZ || wz > hiZ;
    }

    // ===== Catmull-Rom 细分平滑 =====

    private static final double SMOOTH_SPACING = 4.0; // 目标节点间距（wu）

    /**
     * Catmull-Rom 细分平滑：把 D8 粗折线重采样为 ~SMOOTH_SPACING 间距的光滑曲线。
     * 弯道处节点密集（弧长短→分点多），直线段稀疏，雕刻不再产生矩形切口。
     * 端点复制首尾避免 Catmull-Rom 边界发散。
     */
    private RiverPolyline smoothPath(MidpointDisplacement.Node[] rawNodes,
                                     double[] rawSurf, double[] rawWid, double[] rawDep,
                                     int level, double meanderScale) {
        int n = rawNodes.length;
        if (n < 3) {
            clampMonotonicDownstream(rawSurf, rawWid, rawDep);
            applyBankCap(rawNodes, rawSurf, rawWid, params);
            return new RiverPolyline(rawNodes, rawSurf, rawWid, rawDep,
                    new double[n], level);
        }
        double[] px = new double[n], pz = new double[n];
        for (int i = 0; i < n; i++) {
            px[i] = rawNodes[i].x();
            pz[i] = rawNodes[i].z();
        }
        List<MidpointDisplacement.Node> outNodes = new ArrayList<>();
        List<Double> outSurf = new ArrayList<>();
        List<Double> outWid = new ArrayList<>();
        List<Double> outDep = new ArrayList<>();

        for (int i = 0; i < n - 1; i++) {
            double p0x = i > 0 ? px[i - 1] : px[0];
            double p0z = i > 0 ? pz[i - 1] : pz[0];
            double p1x = px[i], p1z = pz[i];
            double p2x = px[i + 1], p2z = pz[i + 1];
            double p3x = i + 2 < n ? px[i + 2] : px[n - 1];
            double p3z = i + 2 < n ? pz[i + 2] : pz[n - 1];
            double s0 = rawSurf[i], s1 = rawSurf[i + 1];
            double w0 = rawWid[i], w1 = rawWid[i + 1];
            double d0 = rawDep[i], d1 = rawDep[i + 1];
            double segLen = Math.hypot(p2x - p1x, p2z - p1z);
            int steps = Math.max(1, (int) Math.ceil(segLen / SMOOTH_SPACING));
            for (int s = 0; s < steps; s++) {
                double t = (double) s / steps;
                double t2 = t * t, t3 = t2 * t;
                double cx = 0.5 * ((2 * p1x) + (-p0x + p2x) * t
                        + (2 * p0x - 5 * p1x + 4 * p2x - p3x) * t2
                        + (-p0x + 3 * p1x - 3 * p2x + p3x) * t3);
                double cz = 0.5 * ((2 * p1z) + (-p0z + p2z) * t
                        + (2 * p0z - 5 * p1z + 4 * p2z - p3z) * t2
                        + (-p0z + 3 * p1z - 3 * p2z + p3z) * t3);
                outNodes.add(new MidpointDisplacement.Node(cx, cz));
                outSurf.add(s0 + (s1 - s0) * t);
                outWid.add(w0 + (w1 - w0) * t);
                outDep.add(d0 + (d1 - d0) * t);
            }
        }
        // 追加终点（不重复最后一个子段终点）
        outNodes.add(rawNodes[n - 1]);
        outSurf.add(rawSurf[n - 1]);
        outWid.add(rawWid[n - 1]);
        outDep.add(rawDep[n - 1]);

        // 蜿蜒（meander）：沿路径法向叠加正弦偏移，制造自然弯曲（参考 PL-RGA 河网形态）
        int m = outNodes.size();
        if (m >= 3 && params.meanderAmp() > 0.01 && meanderScale > 0.01) {
            double[] mx = new double[m], mz = new double[m];
            double[] arc = new double[m];
            for (int i = 0; i < m; i++) {
                mx[i] = outNodes.get(i).x();
                mz[i] = outNodes.get(i).z();
            }
            double acc = 0.0;
            for (int i = 1; i < m; i++) {
                acc += Math.hypot(mx[i] - mx[i - 1], mz[i] - mz[i - 1]);
                arc[i] = acc;
            }
            // ★ 河源段不施加满幅蜿蜒（2026-09-06）：meanderAmp(2.5) 原先全河 uniform，
            //   而 advanceToValleyHead 已把河头放进了汇流槽（横向裕度 ≥ VALLEY_MIN_RISE），
            //   紧接着又被正弦横向挪走 —— 挪幅足以把窄槽里的河头甩到坡面上。这正是
            //   "凸坡源头稳定卡在 22%"的成因（改用 bestValleyHead 挑最像槽的一格后
            //   数字一字不差，说明河头选取本身没错，错在选完之后又被挪走）。
            //   物理上也应当如此：蜿蜒振幅随流量/河宽增大，河源细流本就近乎顺直。
            //   跨度取 HEAD_TAPER_NODES × gridCell，与河头宽深淡出同段完成。
            double meanderHeadArc = HEAD_TAPER_NODES * Math.max(1.0, params.gridCell());
            for (int i = 0; i < m; i++) {
                int prev = i > 0 ? i - 1 : 0;
                int next = i < m - 1 ? i + 1 : m - 1;
                double tx = mx[next] - mx[prev];
                double tz = mz[next] - mz[prev];
                double tl = Math.hypot(tx, tz);
                if (tl > 1e-6) { tx /= tl; tz /= tl; }
                double nx = -tz, nz = tx;   // 左转 90° 法向
                double headFade = NoiseUtil.smooth(NoiseUtil.saturate(arc[i] / meanderHeadArc));
                double off = meanderScale * params.meanderAmp() * headFade
                        * Math.sin(2.0 * Math.PI * arc[i] / params.meanderWavelength());
                mx[i] += nx * off;
                mz[i] += nz * off;
            }
            for (int i = 0; i < m; i++) {
                outNodes.set(i, new MidpointDisplacement.Node(mx[i], mz[i]));
            }
        }

        MidpointDisplacement.Node[] rn = outNodes.toArray(new MidpointDisplacement.Node[0]);
        double[] rs = new double[outSurf.size()];
        double[] rw = new double[outWid.size()];
        double[] rd = new double[outDep.size()];
        for (int i = 0; i < rs.length; i++) {
            rs[i] = outSurf.get(i);
            rw[i] = outWid.get(i);
            rd[i] = outDep.get(i);
        }
        // 沿程单调化（下游不抬床）：见 clampMonotonicDownstream
        clampMonotonicDownstream(rs, rw, rd);
        // Catmull-Rom 重采样后节点更密，但水面是节点间线性插值：
        // 原始节点间的局部地形可能更低，必须再按逐重采样节点收回到岸线 cap。
        applyBankCap(rn, rs, rw, params);
        applyEstuary(rn, rs, rw, rd, params);
        // 瀑布阶梯化必须在全部水面调整（单调化→岸线 cap→河口）之后：
        // 它抬升裂点上游水位，放在前面会被后续 cap/单调化重新压平。
        double[] fall = applyWaterfalls(rn, rs, rw, rd, params);
        // 最终硬上界：水面不得高于原地形中心（防悬空水井）。
        // ★ 对瀑布 tread 安全：tread = min(覆盖范围 minTerr, minCap) ≤ 范围内每个
        //   节点的 terr，硬上界不会削平 tread。它只修重采样节点地形凹陷处的
        //   插值残留（surf 高于当地 terr 约 0.2 格的微悬河）。
        double sea = curve.seaLevelY();
        for (int k = 0; k < rn.length; k++) {
            if (rawTerrainY(rn[k]) >= sea && rs[k] > rawTerrainY(rn[k])) {
                rs[k] = rawTerrainY(rn[k]);
            }
        }
        // 沿程单调兜底（下游不抬床）。
        for (int k = 1; k < rn.length; k++) {
            if (rs[k] > rs[k - 1]) rs[k] = rs[k - 1];
        }
        return new RiverPolyline(rn, rs, rw, rd, fall, level);
    }

    /**
     * 河口喇叭口：入海口向上游 {@code estuaryLength} 范围内宽度向海渐增，
     * 并保证河口最小水深。
     *
     * <p>参考 Streams {@code RiverMouthComponent}：河口比上游更宽（喇叭口形态），
     * 且有 {@code MinDepth} 保证河口不被填平。只调整几何参数，不改水面与河网拓扑。</p>
     */
    private void applyEstuary(MidpointDisplacement.Node[] nodes, double[] surf,
                              double[] widths, double[] depths, RiverLineParams p) {
        int n = nodes.length;
        if (n < 2) return;
        double seaLevel = curve.seaLevelY();
        // ★ 先判"这条河到底入不入海"（2026-09-01）：本函数原先对每条河无条件执行，
        //   而循环退出条件要求【地形>=海平面 且 距出口>estuaryLength】两者同时成立。
        //   于是任何总长不足 estuaryLength(140wu) 的内陆小河，along 永远达不到阈值
        //   → 整条河（连源头）都被当作河口处理：宽度乘 estuaryWidthFactor(1.9)、
        //   深度被 depths[i]=max(mouthMinDepth=2.0, ·) 抬平，且绕过宽深比护栏
        //   （实测出现半宽1.60/水深2.00 的 D>0.9W 非物理断面），并把河头淡出抹掉。
        //   喇叭口只存在于河真正入海处：出口地形在海面以上即为陆内河（汇流/洼地终
        //   止），直接跳过。
        if (rawTerrainY(nodes[n - 1]) > seaLevel) return;
        // ★ 河口带长度不得超过本河自身长度的一半（2026-09-01）：短河（总长 < estuaryLength
        //   =140wu）若按固定带长处理，喇叭口会一路盖到源头，把源端深度抬到 mouthMinDepth
        //   并绕过宽深比护栏 → 河头淡出被抹掉（实测仍有 7 条源端水深恰为 2.00）。
        //   喇叭口是沿海地貌特征，一条 52wu 长的小溪其河口至多占下游一半。
        double totalLen = 0.0;
        for (int i = 1; i < n; i++) {
            totalLen += Math.hypot(nodes[i].x() - nodes[i - 1].x(),
                                   nodes[i].z() - nodes[i - 1].z());
        }
        double maxLen = Math.max(1.0, Math.min(p.estuaryLength(), totalLen * 0.5));
        double widthFactor = Math.max(1.0, p.estuaryWidthFactor());
        double minDepth = Math.max(0.5, p.mouthMinDepth());
        // 河口上限独立于河道 maxWidth，否则大河河口被钳制到与河道同宽，喇叭口展不开
        double mouthMax = Math.max(p.maxWidth(), p.mouthMaxWidth());
        double along = 0.0;
        for (int i = n - 1; i >= 0; i--) {
            if (i < n - 1) {
                along += Math.hypot(nodes[i + 1].x() - nodes[i].x(),
                        nodes[i + 1].z() - nodes[i].z());
            }
            // 仅在海域内或紧邻入海口的上游过渡带生效
            if (rawTerrainY(nodes[i]) >= seaLevel && along > maxLen) break;
            // t=1 在入海口，t=0 在过渡带上游端
            double t = 1.0 - NoiseUtil.saturate(along / maxLen);
            double grow = 1.0 + (widthFactor - 1.0) * NoiseUtil.smooth(t);
            widths[i] = Math.min(mouthMax, widths[i] * grow);
            depths[i] = Math.max(minDepth, depths[i]);
        }
        // 保持既有水力约束：宽度/深度沿程不减（下游更宽更深）
        for (int i = 1; i < n; i++) {
            if (widths[i] < widths[i - 1]) widths[i] = widths[i - 1];
            if (depths[i] < depths[i - 1]) depths[i] = depths[i - 1];
        }
    }

    /**
     * 瀑布（跌水）阶梯化：基于<b>原地形坡度</b>检测连续陡降 run，把 run 切成多级阶梯水面
     * （2026-08-30 重写）。
     *
     * <p><b>触发</b>：沿河线逐段算原地形（未雕刻）的 XZ 水平距（block）× 垂直落差的夹角
     * {@code θ = atan2(ΔY, ΔXZ)}；仅当连续陡降段 {@code θ ≥ waterfallMinAngle} 且
     * 净落差 {@code ≥ waterfallMinDrop} 才判定为瀑布 run。平缓坡（θ 不足）走普通河流，
     * 不挂瀑——根治旧实现"按水面差抬节点"造成的地形之上悬垂直水壁（水井）。</p>
     *
     * <p><b>台阶数</b>：随角度/长度增长——{@code θ∈[minAngle,45°]} 阶梯增多，
     * {@code θ>45°} 递减，近 90°→1 阶；run 水平跨度 {@code < waterfallStepRun} 强制 1 阶
     * （短陡坡），长陡坡按 {@code floor(runLen/stepRun)} 多阶（上限 waterfallMaxSteps）。</p>
     *
     * <p><b>阶梯水面</b>：run 切 steps 等分子窗，每窗水面恒值、子窗边界跌 {@code runDrop/steps}；
     * 每节点 {@code fallDrop[i] = lip − surf[i]}（自崖顶累计），供水幕从崖顶连挂到潭。
     * 水面恒受两岸原始地形 bankCapY 与海平面硬上界（waterLevelCap）→ 永不抬到地形之上。</p>
     *
     * <p><b>单调性</b>由构造保证（阶梯只降不升）+ 末尾沿程取小双重保险；
     * {@code waterfallMaxDrop} 降级为单级 sanity 钳（仅约束人工抬升量，真实崖面落差可更大）。</p>
     *
     * @return 逐节点跌水落差（block），0 = 普通河段
     */
    private double[] applyWaterfalls(MidpointDisplacement.Node[] nodes, double[] surf,
                                     double[] widths, double[] depths, RiverLineParams p) {
        int n = nodes.length;
        double[] fall = new double[n];
        if (n < 4) return fall;
        double minAngle = Math.max(1.0, p.waterfallMinAngle());      // 度
        double minDrop = Math.max(0.5, p.waterfallMinDrop());
        double maxDrop = Math.max(minDrop, p.waterfallMaxDrop());    // 单级 sanity 钳
        double stepH0 = Math.max(0.5, p.waterfallStepHeight());
        double stepRun = Math.max(1.0, p.waterfallStepRun());
        int maxSteps = Math.max(1, p.waterfallMaxSteps());
        int minSpacing = Math.max(1, p.waterfallMinSpacing());
        double seaLevel = curve.seaLevelY();

        // 1. 预计算原地形高度与每段水平 block 距（wu × horizontalScale）
        double[] terr = new double[n];
        double[] segLen = new double[n];   // segLen[i] = 节点 i-1→i 的水平 block 距
        for (int i = 0; i < n; i++) terr[i] = rawTerrainY(nodes[i]);
        for (int i = 1; i < n; i++) {
            double dx = nodes[i].x() - nodes[i - 1].x();
            double dz = nodes[i].z() - nodes[i - 1].z();
            segLen[i] = Math.hypot(dx, dz) * horizontalScale;
        }

        // 2. 扫描连续陡降 run：局部角 ≥ minAngle 且 terr 下降则并入；平缓/上升断 run
        int lastRunEnd = -minSpacing - 1;
        int i = 1;
        while (i < n) {
            if (terr[i] >= terr[i - 1] - 1e-6) { i++; continue; }   // 非下降
            double localDrop = terr[i - 1] - terr[i];
            double localLen = segLen[i];
            double localAngle = localLen > 1e-6 ? Math.atan2(localDrop, localLen) * 180.0 / Math.PI : 90.0;
            if (localAngle < minAngle) { i++; continue; }
            // 扩展 run [a, b]：连续陡降段并入；遇上升/变缓则断（累计跨度 ≤ minSpacing 的短浅段可桥接）
            int a = i - 1, b = i;
            double runDrop = localDrop, runLen = localLen;
            while (b + 1 < n) {
                int nb = b + 1;
                double dDrop = terr[b] - terr[nb];
                double dLen = segLen[nb];
                if (dDrop <= 1e-6) {
                    // 平/微升崖面平台：仅当累计 run 跨度 ≤ minSpacing 才并入
                    // （视作同一瀑布的台阶平台，避免把一段崖壁误拆成过近的两级）
                    if (nb - a > minSpacing) break;
                    runLen += dLen; b = nb; continue;
                }
                double dAngle = dLen > 1e-6 ? Math.atan2(dDrop, dLen) * 180.0 / Math.PI : 90.0;
                if (dAngle < minAngle) {
                    // 短浅桥接：累计 run 跨度 ≤ minSpacing 才并入（视作同一瀑布的缓坡段），
                    // 且桥接后净角仍 ≥ minAngle；否则断 run（避免把过近的两级误拆/误连）
                    if (nb - a > minSpacing) break;
                    double tryDrop = runDrop + dDrop, tryLen = runLen + dLen;
                    double tryAngle = tryLen > 1e-6 ? Math.atan2(tryDrop, tryLen) * 180.0 / Math.PI : 90.0;
                    if (tryAngle < minAngle) break;
                    runDrop = tryDrop; runLen = tryLen; b = nb;
                    continue;
                }
                runDrop += dDrop; runLen += dLen; b = nb;
            }
            double runAngle = runLen > 1e-6 ? Math.atan2(runDrop, runLen) * 180.0 / Math.PI : 90.0;
            if (runDrop >= minDrop && runAngle >= minAngle && a - lastRunEnd >= minSpacing) {
                buildStaircase(nodes, surf, widths, depths, fall, terr,
                        a, b, runDrop, runLen, runAngle, p,
                        minDrop, maxDrop, stepH0, stepRun, maxSteps, seaLevel);
                lastRunEnd = b;
            }
            i = b + 1;
        }
        // 阶梯化后仍须沿程不回升（构造已保证，这里兜底）
        for (int k = 1; k < n; k++) {
            if (surf[k] > surf[k - 1]) surf[k] = surf[k - 1];
        }
        // ★ 不做"上游深潭渐变抬升"（2026-08-30 移除）：把上游水面在 4 节点内抬到
        //   崖顶 lip 会造成水面爬坡（用户实测"先深→升高→再单调下降"）——违反
        //   ADR#1 单调铁律的观感。DW 语义是水面贴地形连续阶梯化，上游不人为蓄水；
        //   lip 取 run 起点上游水面（surf[a]，与上游河面连续），瀑布落差即
        //   上游水面到潭面的真实落差（凹槽地形下被 bankCap 钳小属防漫岸正确行为）。
        // fall 标记保持（>0 即跌水段；sampleRegion 用 surf 差生成级间水幕，
        // carver 冻结 carveSurfaceY 防 IDW 抹平阶梯）。不再按节点重算——
        // 节点级重算会把 tread 内部（同级水面）的标记清零，导致阶梯被抹平。
        return fall;
    }

    /**
     * 把单个瀑布 run [a,b] 切成 steps 级阶梯水面，并写入 surf/fall/depths。
     *
     * <p><b>贴地形阶梯</b>（Dynamic Waters 语义）：每级 tread 水面 = 该级子窗内
     * 原地形最低点（水面贴崖面，不悬空、不埋地），级间落差 = 地形落差；
     * 崖顶水位 lip = run 起点原地形（水面贴崖顶，瀑布落差 = 完整地形落差）。
     * 每节点 {@code fall[k] = 1.0} 仅作"跌水段"标记（sampleRegion 用 surf 差
     * 生成级间水幕，carver 冻结 carveSurfaceY 防 IDW 抹平阶梯）。</p>
     */
    private void buildStaircase(MidpointDisplacement.Node[] nodes, double[] surf, double[] widths,
                                double[] depths, double[] fall, double[] terr,
                                int a, int b, double runDrop, double runLen, double runAngle,
                                RiverLineParams p, double minDrop, double maxDrop,
                                double stepH0, double stepRun, int maxSteps, double seaLevel) {
        // 阶数：θ≤45° 每级约 stepH0；θ>45° 随角度增大 stepH → 阶数递减；近 90°→1 阶
        double t = NoiseUtil.saturate((runAngle - 45.0) / 45.0);
        double stepH = stepH0 + t * (runDrop - stepH0);
        int steps = (int) Math.round(runDrop / Math.max(1e-6, stepH));
        steps = Math.max(1, steps);
        steps = Math.min(steps, (int) Math.floor(runLen / stepRun));   // 长陡坡多阶，短陡坡受限
        steps = Math.min(steps, maxSteps);
        if (runLen < stepRun) steps = 1;                              // 短陡坡强制 1 阶

        // 崖顶水位 = run 起点上游水面（与上游河面连续，无深潭爬坡）。
        // DW 语义：水面贴地形连续阶梯化，上游不人为蓄水抬升（水面爬坡违反
        // 单调铁律观感——实测"先深→升高→再单调下降"）。瀑布落差 = 上游水面
        // 到潭面的真实落差；凹槽地形下被 bankCap 钳小属防漫岸正确行为。
        double lip = surf[a];
        // ★ 入海段不生成瀑布（2026-08-31）：唇口水位已在海平面及以下 → 整段 run 都在
        //   海面之下（水面沿程单调不回升），水下不存在瀑布（水面被海面钳平，落差不成立）。
        //   旧代码只看 runDrop/minAngle，入海段照样造阶 → 实测种子 9139912035078620160
        //   有 51 列瀑布唇口在海平面下（如 (71,1968) 唇口 60.8 / 潭面 54.1，海平面 63），
        //   表现为入海口的水下落差地形。此处直接跳过：不切阶、不标 fall、不挖潭。
        if (lip <= seaLevel) return;

        // 子窗按累计落差等分（每级落差 ≈ runDrop/steps，均匀）：
        // 陡崖段（每节点落差大）被切成多级，平缓段一级——符合"越陡阶数多"。
        // 子窗 s 边界 = 第一个累计落差 ≥ s·perStep 的节点。
        double perStep = runDrop / steps;
        int[] bounds = new int[steps + 1];
        bounds[0] = a;
        double cd = 0.0;
        int bi = 1;
        for (int k = a; k <= b; k++) {
            cd = Math.max(cd, terr[a] - terr[k]);                     // 累计落差（单调不降）
            while (bi <= steps && cd >= bi * perStep) bounds[bi++] = k;
        }
        for (; bi <= steps; bi++) bounds[bi] = b;                     // 尾部兜底
        // 每节点硬上界（与 waterLevelCap 同源，但与 level 无关，可预计算）：
        // terrI < 海平面 → 海平面；否则 min(bankCapY, terrI)，低于海平面时贴地。
        double[] hardCap = new double[nodes.length];
        for (int k = a; k <= b; k++) {
            double terrI = rawTerrainY(nodes[k]);
            if (terrI < seaLevel) { hardCap[k] = seaLevel; continue; }
            double cap = bankCapY(nodes, k, widths[k], p);
            double hc = Math.min(cap, terrI);
            hardCap[k] = hc >= seaLevel ? hc : terrI;
        }
        // 每级 tread 水面 = min(覆盖范围内原地形最低点, 最严硬上界)——
        // ★ tread s 实际覆盖节点 [bounds[s], bounds[s+1]-1]（下一级起点之前），
        //   水面必须 ≤ 覆盖范围内最低地形（否则 tread 高于范围内地形 = 悬空潭）。
        // ★ tread 恒定：cap 在 tread 级统一取 min，而非逐节点钳制（逐节点钳会让
        //   同一 tread 内水面被 bankCap/terr 锯成递减碎台阶——"阶数太多"的真正来源）。
        double[] stepSurf = new double[steps + 1];
        stepSurf[0] = lip;
        for (int s = 0; s <= steps; s++) {
            int j0 = bounds[s];
            int j1 = (s < steps) ? bounds[s + 1] - 1 : b;
            double minTerr = Double.POSITIVE_INFINITY;
            double minCap = Double.POSITIVE_INFINITY;
            for (int j = j0; j <= j1; j++) {
                minTerr = Math.min(minTerr, terr[j]);
                minCap = Math.min(minCap, hardCap[j]);
            }
            // ★ 水面不得低于海平面（2026-08-31）：河口入海后水面即海面，继续按地形
            //   下切会把"海面以下的阶"当成瀑布级（落差完全淹没在水下）。钳到海平面后
            //   这些级自动等高 → 落差归零，配合下面的 fall 标记判据不再冻结。
            double tread = Math.max(Math.min(minTerr, minCap), seaLevel);
            stepSurf[s] = (s == 0) ? tread : Math.min(tread, stepSurf[s - 1]); // 单调：≤ 上级
        }
        // ★ 碎阶合并（2026-08-31）：bankCap/tread 钳制可能把相邻阶水面压到差 <2 格，
        //   形成肉眼是锯齿/碎石、不是瀑布的"假阶"（实测 23/99 阶 <2 格，且与坡角无关，
        //   提 minAngle 治不了）。落差 < minStepDrop 的阶**向下并入**下一级（只降不升 →
        //   绝不抬高水漫岸，实测向上并会致 bankOverflow>0），该边界跌水归零、上级边界
        //   落差随之增大，只保留真实大阶。lip(上级来源) 与潭面(末级) 为锚点不动。
        double minStepDrop = 2.0;
        for (int s = steps - 1; s >= 1; s--) {
            if (stepSurf[s] - stepSurf[s + 1] < minStepDrop) {
                stepSurf[s] = stepSurf[s + 1];
            }
        }
        // 每节点水面 = 所在级水面（tread 内恒定，无锯齿），fall[k] 标记跌水段
        for (int k = a; k <= b; k++) {
            int stepIdx = 0;
            for (int s = 1; s <= steps; s++) {
                if (k >= bounds[s]) stepIdx = s; else break;
            }
            // 直接设置（贴地形）：tread 低于现有深谷水面时也要降下来，
            // 否则水幕埋在崖壁内部（gap 大，从外面看不见瀑布）。
            surf[k] = stepSurf[stepIdx];
            fall[k] = 1.0;                                            // 跌水段标记
        }
        // 跌水潭：run 末端（潭底）按 plungePoolFactor 加深，向下游平方衰减。
        // ★ 加深量限幅 2 格 + 衰减长度加倍：多阶大瀑布 runDrop 大（50+ 格），
        //   旧限幅 maxDrop*2=8 会让潭底比下游河床深 8 格 → 潭出口河床骤升
        //   （用户实测"先深→升高→再单调下降"的河床爬坡）。潭底 ≤ 下游河床
        //   恒成立（tread ≥ 下游 surf），限幅后出口落差 ≤ 2 格，观感平缓。
        double plunge = Math.min(p.plungePoolFactor() * runDrop, 2.0);
        int poolLen = Math.max(2, p.waterfallMinSpacing() / 2);
        for (int k = 0; k < poolLen && b + k < surf.length; k++) {
            double w = 1.0 - (double) k / poolLen;
            depths[b + k] += plunge * w * w;
        }
    }

    /** 水面抬升上限：受两岸地形上界与海平面约束（与 applyBankCap 同一防漫岸约束）。 */
    private double waterLevelCap(MidpointDisplacement.Node[] nodes, int i, double width,
                                 double level, RiverLineParams p, double seaLevel) {
        double terrI = rawTerrainY(nodes[i]);
        if (terrI < seaLevel) return Math.min(level, seaLevel);
        double cap = bankCapY(nodes, i, width, p);
        // 硬上界：水面不得高于原地形中心（防悬空水井）。近岸 cap 低于海平面时退化为贴地
        // （min(level, terrI)），而非旧实现返回未钳制的 level（会让水漫到地形之上成井）。
        double hardCap = Math.min(cap, terrI);
        return hardCap >= seaLevel ? Math.min(level, hardCap) : Math.min(level, terrI);
    }

    /** 逐节点把水面收回到两岸原始地形 cap，并向下游传播最小值（保持不回升）。 */
    private void applyBankCap(MidpointDisplacement.Node[] nodes, double[] surf,
                              double[] widths, RiverLineParams params) {
        double seaLevel = curve.seaLevelY();
        for (int i = 0; i < surf.length; i++) {
            // ★ 重采样会新增大量中间节点；海域锁平原本只作用于 PAVA 原始节点，
            //   导致落在海域的插值节点仍沿用上游陆地水位（入海口前高一格的真因）。
            //   这里对"地形已低于海平面"的重采样节点同样锁到海平面。
            if (rawTerrainY(nodes[i]) < seaLevel) {
                surf[i] = Math.min(surf[i], seaLevel);
            } else {
                // 海域内不存在河岸上界：岸线低于海平面时不再压低水面，保持海平面。
                double cap = bankCapY(nodes, i, widths[i], params);
                if (cap >= seaLevel) {
                    // 硬上界：水面不得高于原地形中心（防悬空水井/悬河）。
                    // 窄槽中两岸采样可能高于中心，旧实现会放任水面漫过中心 → 钳回中心地形。
                    double hardCap = Math.min(cap, rawTerrainY(nodes[i]));
                    surf[i] = Math.min(surf[i], hardCap);
                }
            }
            if (i > 0) surf[i] = Math.min(surf[i], surf[i - 1]);
        }
    }

    /**
     * 沿程单调化（下游不抬床，2026-08-29）。
     *
     * <p>节点顺序恒为 head[0] → mouth[last]：head 高/浅/窄，mouth 低/深/宽。
     * 对单条折线的水力几何数组做运行约束：</p>
     * <ul>
     *   <li>{@code surfaceY} 运行取小 —— 下游水面不回升；</li>
     *   <li>{@code width}    运行取大 —— 下游河宽不减；</li>
     *   <li>{@code depth}    运行取大 —— 下游河深不减。</li>
     * </ul>
     *
     * <p>根因（探针 {@code RiverLineMicroUphillProbe}）：雕刻器在 {@code blendDist=100wu}
     * 内对邻近段做反距离平方混合 surfaceY/width/depth。同一条河自身在河曲/自身相邻段处
     * 被重复采样（hitCount>1），混合进更高 surfaceY、更小 depth 的"上游/侧向"段，使
     * 局部河床 {@code surfaceY − depth·profile} 抬升，产生方块级爬坡（占可见爬坡约 67%）。
     * 单条折线内先强制水力几何下游单调，从根消除此类同河内部爬坡；交汇处跨河段混合的
     * 残留非单调由雕刻器侧另行处理，不在此处。</p>
     */
    private static void clampMonotonicDownstream(double[] surf, double[] wid, double[] dep) {
        int m = surf.length;
        if (m <= 1) return;
        for (int i = 1; i < m; i++) {
            if (surf[i] > surf[i - 1]) surf[i] = surf[i - 1];
            if (wid[i] < wid[i - 1]) wid[i] = wid[i - 1];
            if (dep[i] < dep[i - 1]) dep[i] = dep[i - 1];
        }
    }

    // ===== 河网生成辅助（PL-RGA 对齐）=====

    /**
     * 下坡邻域：step×step 窗口内取"单位距离落差最大"的格（标准 D8 最陡下降定义）。
     *
     * <p>★ 2026-08-29 实机修复"河沿等高线横走"：旧评分 {@code e + 1e-5·dist²}
     * 的距离惩罚（≤4e-5）远小于 e 的横向差异量级，实际退化为"窗口内绝对最低点"——
     * 等高线方向只要有缓坡平台，河就横向漂移而非直下坡（用户实机红圈案例）。
     * 改为 slope = (curE − e)/dist 单位落差率评分：只有横向落差率真正更大时才横走，
     * 直下坡更陡时必然直下（水的物理走向）。</p>
     */
    private int downhillNeighbor(FlowField field, int cur, int step, int nx, int nz) {
        int ci = cur % nx, cj = cur / nx;
        double curE = field.eAt(cur);
        int best = -1;
        double bestSlope = 0.0; // 经 minDrop 门控的候选 slope 恒正 → 等价"严格更低"
        int minI = Math.max(0, ci - step), maxI = Math.min(nx - 1, ci + step);
        int minJ = Math.max(0, cj - step), maxJ = Math.min(nz - 1, cj + step);
        for (int j = minJ; j <= maxJ; j++) {
            for (int i = minI; i <= maxI; i++) {
                if (i == ci && j == cj) continue;
                int idx = j * nx + i;
                double e = field.eAt(idx);
                if (e >= curE - params.minDrop()) continue;
                double di = i - ci, dj = j - cj;
                double slope = (curE - e) / Math.sqrt(di * di + dj * dj);
                if (slope > bestSlope) { bestSlope = slope; best = idx; }
            }
        }
        return best;
    }

    /**
     * 交接种子起点容错：邻 region 栅格相对上游偏移最多 16wu，种子格可能恰落局部极小
     * （D8 无下坡或坡低于 minDrop → 续流即死）。在 1 格窗口内改选有真实下坡的格作续流起点，
     * 保证跨缝连续。
     */
    private int bestHandoffStart(FlowField field, int start, int nx, int nz) {
        if (downhillNeighbor(field, start, 1, nx, nz) >= 0) return start;
        int ci = start % nx, cj = start / nx;
        int best = start; double bestE = field.eAt(start);
        for (int dj = -1; dj <= 1; dj++) {
            for (int di = -1; di <= 1; di++) {
                int i = ci + di, j = cj + dj;
                if (i < 0 || j < 0 || i >= nx || j >= nz) continue;
                int idx = j * nx + i;
                if (downhillNeighbor(field, idx, 1, nx, nz) >= 0 && field.eAt(idx) < bestE) {
                    best = idx; bestE = field.eAt(idx);
                }
            }
        }
        return best;
    }

    /** 河段 (ax,ay)-(bx,by) 是否与已有段集合任一相交（_segmentsCross）。 */
    private static boolean segmentCrossesAny(int ax, int ay, int bx, int by, List<int[]> segs) {
        // 诊断：按"扫描规模"计数量化 O(n²) 热点（实际比较数因短路更少，
        // 但空间索引要消除的正是这个线性扫描规模）。
        CROSS_COMPARISONS.addAndGet(segs.size());
        for (int[] s : segs) {
            if (segmentsCross(ax, ay, bx, by, s[0], s[1], s[2], s[3])) return true;
        }
        return false;
    }

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

    /**
     * 地形拟合的单调水面：对 {@code terrainY - surfaceSink} 做非递增单调回归（PAVA）。
     *
     * <p>相比运行最小值，PAVA 不会被单个小凹坑永久拖低；它在保证下游绝不回升的前提下，
     * 以最小平方误差贴合整条沿河地形。小凸起会被压平并切穿，小凹坑会形成有水的深槽，
     * 整体仍紧贴地势。出口和跨 region 源端用高权重锚定，维持汇口/瓦片缝连续。</p>
     */
    private double[] applyRiverHeightSlopeDrop(MidpointDisplacement.Node[] nodes,
                                               double[] rawSurf, double[] widths,
                                               double outletSurf, RiverLineParams params,
                                               Double forcedSrcH, boolean reachedOcean) {
        int m = rawSurf.length;
        if (m == 0) return new double[0];
        double sink = Math.max(0.0, params.surfaceSink());
        double seaLevel = curve.seaLevelY();
        double[] target = new double[m];
        double[] weight = new double[m];
        Arrays.fill(weight, 1.0);
        for (int k = 0; k < m; k++) {
            double bankCap = bankCapY(nodes, k, widths[k], params);
            if (rawSurf[k] < seaLevel || bankCap < seaLevel) {
                // 已进入海域（河心或河缘地形低于海平面）就没有"河岸"：
                // 水面由海平面决定，否则会被海底地形压低成"河流继续下探"。
                target[k] = seaLevel;
                continue;
            }
            // 陆上河面也不得低于海平面：surfaceSink 会把紧邻海平面的河段压到海平面以下，
            // 再经单调传播拉低整个入海口。
            target[k] = Math.max(seaLevel, Math.min(rawSurf[k] - sink, bankCap));
        }

        double outlet = Math.min(outletSurf, target[m - 1]);
        target[m - 1] = outlet;
        weight[m - 1] = 1e9;
        if (forcedSrcH != null) {
            // 跨 region 续流只能继承连续水位，不能突破本 region 入口两岸的原始地形上界。
            target[0] = Math.min(target[0], Math.max(outlet, forcedSrcH));
            weight[0] = 1e9;
        }

        double[] surf = isotonicNonIncreasing(target, weight);
        // 不再用 clamp 的"下界"把水面抬回 outlet/sourceCap：那会把水面重新抬到
        // 岸线 cap 之上（PAVA 块均值 + 强制下界 = 水面盖过河岸的根因）。
        // 只保留：不超过本节点两岸原始地形、且沿程不回升。
        for (int k = 0; k < m; k++) {
            surf[k] = Math.min(surf[k], target[k]);
            if (k > 0) surf[k] = Math.min(surf[k], surf[k - 1]);
        }
        if (forcedSrcH != null) surf[0] = Math.min(surf[0], Math.max(outlet, Math.min(forcedSrcH, target[0])));
        surf[m - 1] = Math.min(surf[m - 1], Math.max(outlet, target[m - 1]));
        return surf;
    }

    /**
     * 采样河线左右岸的多点原始地形，取最小值作为该节点水面硬上界。
     *
     * <p>只采单点会在地形起伏处漏判：河岸并非单调升高，横向地形可能比单点更低。
     * 这里沿法向在近岸与远岸各取一点（左右共 4 点），取最低值并留安全余量，
     * 保证水面始终嵌在河谷内，不会出现"一侧河岸被水盖过"。</p>
     */
    private double bankCapY(MidpointDisplacement.Node[] nodes, int i, double width,
                            RiverLineParams params) {
        int prev = Math.max(0, i - 1), next = Math.min(nodes.length - 1, i + 1);
        double tx = nodes[next].x() - nodes[prev].x();
        double tz = nodes[next].z() - nodes[prev].z();
        double len = Math.hypot(tx, tz);
        if (len < 1e-6) return rawTerrainY(nodes[i]);
        double nx = -tz / len, nz = tx / len;
        // ★ 必须采"河缘"(dist≈width)而不是远岸：水从河缘漫出，远岸再高也挡不住。
        double near = Math.max(1.0, width);
        double far = Math.max(2.0, width * 1.35);
        double lowest = Double.POSITIVE_INFINITY;
        for (double offset : new double[]{near, far}) {
            lowest = Math.min(lowest, rawTerrainY(nodes[i].x() + nx * offset,
                    nodes[i].z() + nz * offset));
            lowest = Math.min(lowest, rawTerrainY(nodes[i].x() - nx * offset,
                    nodes[i].z() - nz * offset));
        }
        return lowest - 0.25;
    }

    private double rawTerrainY(MidpointDisplacement.Node node) {
        return groundYAt(node.x(), node.z());
    }

    private double rawTerrainY(double x, double z) {
        return terrainY != null ? terrainY.yAt(x, z) : curve.heightFromE(eSampler.eAt(x, z));
    }

    /** 加权 PAVA：返回与 target 平方误差最小的非递增序列。 */
    private static double[] isotonicNonIncreasing(double[] target, double[] weight) {
        int n = target.length;
        double[] mean = new double[n], sumW = new double[n];
        int[] start = new int[n], end = new int[n];
        int blocks = 0;
        for (int i = 0; i < n; i++) {
            mean[blocks] = target[i]; sumW[blocks] = weight[i];
            start[blocks] = i; end[blocks] = i; blocks++;
            while (blocks >= 2 && mean[blocks - 2] < mean[blocks - 1]) {
                int a = blocks - 2, b = blocks - 1;
                double w = sumW[a] + sumW[b];
                mean[a] = (mean[a] * sumW[a] + mean[b] * sumW[b]) / w;
                sumW[a] = w; end[a] = end[b]; blocks--;
            }
        }
        double[] out = new double[n];
        for (int b = 0; b < blocks; b++) {
            Arrays.fill(out, start[b], end[b] + 1, mean[b]);
        }
        return out;
    }

    /**
     * 河面世界 Y（= 当地地表谷底）。
     *
     * <p>用 terrainEQuick 派生（与 D8 汇流场同源、确定、零侵蚀 tile）→ 保证 region 冷构建亚毫秒级。
     * 与最终地形（sampleWu）的差异仅剩侵蚀 delta（通常很小），不影响视觉嵌入感。</p>
     */
    private double groundYAt(double wx, double wz) {
        return terrainY != null ? terrainY.yAt(wx, wz)
                : curve.heightFromE(eSampler.eAt(wx, wz));
    }

    // ===== 下游水力几何（2026-08-29 宽深改革）=====

    /**
     * 河宽：W = minWidth × (A / A_ref)^bW，钳制到 maxWidth。
     *
     * <p>依据 Leopold-Maddock 下游水力几何 W ∝ Q^b（b≈0.5）、Q ∝ 汇流面积 A，
     * 本项目取 bW=0.42 以压缩超大汇流的宽度爆炸。</p>
     *
     * <p><b>★ 与成河门槛解耦</b>：宽度原点为独立的 {@link RiverLineParams#widthAreaRef()}，
     * 而非 {@link RiverLineParams#riverAccumThreshold()}。旧实现把两者绑死
     * （t = (logA − log A0)/log range，A0 即门槛），导致调河网密度时全河宽度整体平移。
     * 幂律也不会像 smoothstep 那样在中段饱和，沿程保持连续渐变。</p>
     */
    private static double widthFromAccum(double accum, RiverLineParams p) {
        double ratio = Math.max(1.0, accum) / Math.max(1.0, p.widthAreaRef());
        return Math.min(p.maxWidth(), p.minWidth() * Math.pow(ratio, p.widthExp()));
    }

    /**
     * 河深：D = minDepth × (A / A_ref)^bD，钳制到 maxDepth，再受宽深比护栏 D ≤ 0.9W 约束。
     *
     * <p>bD(0.40) &lt; bW(0.42) 使宽深比 W/D ∝ A^0.02 随下游缓慢增大（下游更宽浅，符合真实河流）。
     * 护栏防止窄而极深的非物理断面。</p>
     */
    private static double depthFromAccum(double accum, double width, RiverLineParams p) {
        double ratio = Math.max(1.0, accum) / Math.max(1.0, p.widthAreaRef());
        double d = Math.min(p.maxDepth(), p.minDepth() * Math.pow(ratio, p.depthExp()));
        return Math.min(d, p.maxDepthRatio() * width);
    }

    // ===== 距离场采样 =====

    /**
     * 点到河线距离场采样：查 3×3 邻域 region 的线段，
     * bbox 预滤后取最近命中。无河流返回 null。
     *
     * @param wx/wz 查询坐标（wu）
     */
    public RiverLineHit sample(double wx, double wz) {
        List<RiverLineHit> hits = sampleAll(wx, wz);
        if (hits.isEmpty()) return null;
        RiverLineHit best = hits.get(0);
        for (RiverLineHit h : hits) {
            if (h.distToCenter() < best.distToCenter()) best = h;
        }
        return best;
    }

    /**
     * 返回影响范围内的全部河线命中（按距离升序）。
     */
    public List<RiverLineHit> sampleAll(double wx, double wz) {
        int rx = floorDiv(wx, params.regionSize());
        int rz = floorDiv(wz, params.regionSize());
        List<RiverLineHit> hits = new ArrayList<>();
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                RiverLineRegion r = region(rx + dx, rz + dz);
                if (!r.hasWater()) continue;
                hits.addAll(sampleRegion(r, wx, wz));
            }
        }
        hits.sort((a, b) -> Double.compare(a.distToCenter(), b.distToCenter()));
        return hits;
    }

    /**
     * 单 region 上 valley 半径内"每段独立命中"列表（供雕刻器 smooth-min 合并，
     * 根治属主在段间切换产生的放射折痕）。仅保留 dist ≤ valleyReach 的段——
     * 其 carve 才可能非零，远处段不影响 smin（carve=original）。
     */
    private List<RiverLineHit> sampleRegion(RiverLineRegion r, double wx, double wz) {
        List<RiverLineHit> out = new ArrayList<>();
        double bankFactor = params.bankFactor();
        double bestRiverDist = Double.POSITIVE_INFINITY;   // 最近河段距离（含 valley 外的）
        for (int ri = 0; ri < r.rivers.size(); ri++) {
            RiverPolyline pl = r.rivers.get(ri);
            List<MidpointDisplacement.Node> line = Arrays.asList(pl.nodes);
            int segCount = line.size() - 1;
            for (int i = 0; i < segCount; i++) {
                MidpointDisplacement.Node a = line.get(i), b = line.get(i + 1);
                double abx = b.x() - a.x(), abz = b.z() - a.z();
                double len2 = abx * abx + abz * abz;
                double u = len2 < 1e-9 ? 0.0
                        : ((wx - a.x()) * abx + (wz - a.z()) * abz) / len2;
                double t = NoiseUtil.clamp(u, 0.0, 1.0);
                double px = a.x() + abx * t, pz = a.z() + abz * t;
                double dx = wx - px, dz = wz - pz;
                double dist = Math.sqrt(dx * dx + dz * dz);
                bestRiverDist = Math.min(bestRiverDist, dist);
                double f = i + t;
                int i0 = (int) Math.floor(f), i1 = Math.min(i0 + 1, line.size() - 1);
                // ★ 跌水节点端帽豁免（2026-08-31）：弯折瀑布直角外侧岸坡凹槽的根因。
                //   线段距离场的端帽（投影越出段末的半圆盘）在普通节点被相邻两段矩形
                //   主体覆盖、不可见；但跌水节点两侧水位阶跃——坠落节点 i1 以外的区域
                //   被跌水段端帽、潭侧首段起点以外的区域被其起点端帽，按**潭面水位**
                //   认领并 carve+灌水，沿直角外岸挖出低于上级河面的凹槽（实测截图）。
                //   弯折外侧楔形区改为不认领，保留原始地形包住直角；水面连续性由两段
                //   矩形主体保证（沿轴 u_P≥0 与 u_F≤1 互相衔接），潭/水幕形态不变。
                if (pl.fallDrop[i1] > 0.0 && u > 1.0) continue;
                if (pl.fallDrop[i1] <= 0.0 && pl.fallDrop[i0] > 0.0 && u < 0.0) continue;
                // 跌水段：水面为阶跃而非线性插值——8 block 的 lerp 会把一级跌水摊成缓坡。
                // 唇口侧（t<FALL_STEP_T）取上游阶梯水位（水幕墙顶，无幕）；
                // 跌水侧取潭面水位，并携带 fallDrop 供水幕填充（旧 Streams fillRiver 语义）。
                // 两侧均 frozen：禁止参与 k=4 格 IDW 竞争带混合——混合会把垂直落差
                // 抹成缓坡、把崖顶边缘挖出凹坑（悬空沙块平台/水幕脱节的根因）。
                double surface;
                double fallDrop = 0.0;
                boolean frozen = false;
                if (pl.fallDrop[i1] > 0.0) {
                    frozen = true;
                    if (t < FALL_STEP_T) {
                        surface = pl.surfaceY[i0];
                    } else {
                        surface = pl.surfaceY[i1];
                        fallDrop = pl.surfaceY[i0] - pl.surfaceY[i1];
                    }
                } else {
                    surface = lerp(pl.surfaceY[i0], pl.surfaceY[i1], t);
                }
                double width = lerp(pl.width[i0], pl.width[i1], t);
                double depth = lerp(pl.depth[i0], pl.depth[i1], t);
                double valleyReach = Math.max(width * (1.0 + bankFactor), width * 3.0);
                if (dist <= valleyReach) {
                    out.add(new RiverLineHit(dist, surface, width, depth,
                            r.dischargeArea, r.outletOcean, false, fallDrop, frozen));
                }
            }
        }
        // 湖泊：影响范围内、且比最近河段更近才纳入（保持旧"湖/河竞争"语义；远处湖 carve≈original 无副作用）
        if (!r.lakes.isEmpty()) {
            double lakeDist2 = Double.POSITIVE_INFINITY;
            double lakeH = 0.0;
            for (RiverLineRegion.LakeNode ln : r.lakes) {
                double d2 = (wx - ln.x) * (wx - ln.x) + (wz - ln.z) * (wz - ln.z);
                if (d2 < lakeDist2) { lakeDist2 = d2; lakeH = ln.height; }
            }
            double lakeDist = Math.sqrt(lakeDist2);
            if (lakeDist <= params.lakeRadius() + params.lakeFadeDist()
                    && lakeDist <= bestRiverDist) {
                out.add(new RiverLineHit(lakeDist, lakeH, params.lakeRadius(),
                        params.minDepth(), r.dischargeArea, false, true, 0.0, false));
            }
        }
        return out;
    }

    /**
     * 最近河线段的单位切向（流向），用于诊断时做垂直方向地形采样。
     *  无河流返回 null。
     */
    public double[] flowTangent(double wx, double wz) {
        int rx = floorDiv(wx, params.regionSize());
        int rz = floorDiv(wz, params.regionSize());
        RiverLineRegion bestRegion = null;
        int bestRi = -1, bestSeg = -1;
        double bestT = 0, bestD2 = Double.POSITIVE_INFINITY;
        for (int dz = -1; dz <= 1; dz++) {
            for (int dx = -1; dx <= 1; dx++) {
                RiverLineRegion r = region(rx + dx, rz + dz);
                if (!r.hasRiver()) continue;
                for (int ri = 0; ri < r.rivers.size(); ri++) {
                    RiverPolyline pl = r.rivers.get(ri);
                    List<MidpointDisplacement.Node> line = Arrays.asList(pl.nodes);
                    int segCount = line.size() - 1;
                    for (int i = 0; i < segCount; i++) {
                        MidpointDisplacement.Node a = line.get(i), b = line.get(i + 1);
                        double abx = b.x() - a.x(), abz = b.z() - a.z();
                        double len2 = abx * abx + abz * abz;
                        double t = len2 < 1e-9 ? 0.0
                                : ((wx - a.x()) * abx + (wz - a.z()) * abz) / len2;
                        t = NoiseUtil.clamp(t, 0.0, 1.0);
                        double px = a.x() + abx * t, pz = a.z() + abz * t;
                        double ddx = wx - px, ddz = wz - pz;
                        double d2 = ddx * ddx + ddz * ddz;
                        if (d2 < bestD2) {
                            bestD2 = d2; bestRegion = r; bestRi = ri; bestSeg = i; bestT = t;
                        }
                    }
                }
            }
        }
        if (bestRegion == null || bestRi < 0 || bestSeg < 0) return null;
        RiverPolyline pl = bestRegion.rivers.get(bestRi);
        List<MidpointDisplacement.Node> line = Arrays.asList(pl.nodes);
        MidpointDisplacement.Node a = line.get(bestSeg), b = line.get(bestSeg + 1);
        double dx = b.x() - a.x(), dz = b.z() - a.z();
        double len = Math.hypot(dx, dz);
        if (len < 1e-6) return null;
        return new double[]{dx / len, dz / len};
    }

    /** 全部已缓存 region（诊断导出用）。 */
    public List<RiverLineRegion> cachedList() {
        return new ArrayList<>(regions.values());
    }

    private void prune() {
        if (regions.size() > MAX_REGIONS) {
            var it = regions.keySet().iterator();
            while (regions.size() > MAX_REGIONS && it.hasNext()) {
                it.next();
                it.remove();
            }
        }
        if (regionsP1.size() > MAX_REGIONS) {
            var it = regionsP1.keySet().iterator();
            while (regionsP1.size() > MAX_REGIONS && it.hasNext()) {
                it.next();
                it.remove();
            }
        }
    }

    private static int floorDiv(double v, int div) {
        return Math.floorDiv((int) Math.floor(v), div);
    }

    private static double lerp(double a, double b, double t) {
        return a + (b - a) * t;
    }

    private static long pack(int x, int z) {
        return ((long) x << 32) | (z & 0xffffffffL);
    }
}
