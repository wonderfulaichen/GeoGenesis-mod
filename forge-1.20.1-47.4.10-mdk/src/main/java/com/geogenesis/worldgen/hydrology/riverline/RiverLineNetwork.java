package com.geogenesis.worldgen.hydrology.riverline;

import com.geogenesis.worldgen.hydrology.flowaccum.FlowField;
import com.geogenesis.worldgen.hydrology.flow.TerrainFlowSim;
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
                               double fallDrop, boolean frozen,
                               /** 岸坡雕刻面（2026-09-09）：跌水段/其下游 4 节点窗内 = 崖顶 lip
                                *  （old-Streams surfaceLevelAt 语义：岸坡取上游水位），随距离
                                *  渐变回本段水面；普通段 = surfaceY。仅供 carver 岸坡目标。 */
                               double bankSurfaceY,
                               /** 湖引用（2026-09-09 侵蚀短板重算）：仅 isLake 命中非 null；
                                *  供 carver 湖分支取溢出口坎的侵蚀后短板水位。 */
                               RiverLineRegion.LakeNode lake) { }

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

    /** 侵蚀增量采样抽象（CellGenerator::erosionDeltaE 注入；返回 e 单位 tile delta）。 */
    public interface ErosionDeltaSampler {
        double deltaAt(double wx, double wz);
    }

    /** 河网微自适应（2026-09-08）：侵蚀 delta 注入选线场（D8 建网引导）。
     * null = 关闭——【默认】：建网覆盖整个 region 网格，全域吃 tile 实测加载
     * 分钟级卡顿（用户 2026-09-08："半天没加载进游戏"），仅 toml 手开实验
     * （erosionRoutingAdaptive）。落块期横向吸附亦已实测实现过但无效果被回滚
     * （见下方"河道横向吸附"注释块）。 */
    private final ErosionDeltaSampler routingDelta;
    /** 选线引导增益：delta 已随 erosionStrength 缩放，恒 1.0。 */
    private final double routingGain;

    // 河道横向吸附（2026-09-08）已实现并实测后【回滚删除】：
    // 探针（RiverSnapProbe，git 历史 f47a023..）量得吸附触发率仅 2~5% 段、
    // 偏移均值 0.09wu（0.2 block，不可见）、且 13/17 触发段方向恶化。
    // 根因认知：侵蚀引擎动量反馈沿既有汇流谷强化 → 河线（D8 贴谷）与侵蚀沟
    // 【大面积天然重合】，用户看到的"局部不适应"是 <5% 的尾部段，其沟底在
    // ±3wu 采样窗外（更大窗会越 chunk 边界触发额外 tile 生成，性能红线）。
    // 真修复仅剩两条贵路径：建网引导（已实现，erosionRoutingAdaptive 手动开）
    // 或 tile 异步/磁盘缓存架构（长期项）。

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
        this(eSampler, terrainY, null, 0.0, curve, seed, horizontalScale, RiverLineParams.defaults());
    }

    public RiverLineNetwork(MidpointDisplacement.ElevationSampler eSampler,
                            TerrainYSampler terrainY,
                            HeightCurve curve, long seed, RiverLineParams params) {
        this(eSampler, terrainY, null, 0.0, curve, seed, 2.0, params);
    }

    /** 全参构造（兼容旧 6 参调用：诊断探针用）。 */
    public RiverLineNetwork(MidpointDisplacement.ElevationSampler eSampler,
                            TerrainYSampler terrainY,
                            HeightCurve curve, long seed, double horizontalScale,
                            RiverLineParams params) {
        this(eSampler, terrainY, null, 0.0, curve, seed, horizontalScale, params);
    }

    /** 微自适应构造：routingDelta 非空时选线场叠加侵蚀增量（erosionRoutingAdaptive 实验路径）。 */
    public RiverLineNetwork(MidpointDisplacement.ElevationSampler eSampler,
                            TerrainYSampler terrainY,
                            ErosionDeltaSampler routingDelta, double routingGain,
                            HeightCurve curve, long seed, double horizontalScale,
                            RiverLineParams params) {
        this.eSampler = eSampler;
        this.terrainY = terrainY;
        this.routingDelta = routingDelta;
        this.routingGain = routingGain;
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
        eCache.clear();      // ★ 选线场随种子变化 ⇒ 坐标缓存必须失效
        gyCache.clear();
    }

    public void clear() {
        regions.clear();
        regionsP1.clear();
        eCache.clear();
        gyCache.clear();
    }

    /**
     * 选线场高程：把原始汇流场 e 经 {@link RiverLineParams#routingE} 山压低后用于 FlowField 追踪，
     * 使河线在"压低地形"上走（贴谷、避峰），再经水面 blend 融回真实地形（PL-RGA firstHeightField）。
     *
     * <p>★ 微自适应（2026-09-08，用户："让河流局部路线与河道生成匹配侵蚀后的地形，
     * 打开侵蚀时再启动"）：erosionDelta 注入时叠加 tile delta（e 单位，同量纲）——
     * D8 汇流场变为"侵蚀后 e 场"，河线会偏向侵蚀刻出的沟槽/绕开侵蚀抬升，
     * 与落块时的实际地形一致。关闭（null）时与旧路径逐位一致（零漂移）。</p>
     */
    // ===== 降水加权（★ 2026-09-11 Phase C）=====
    /** 降水取样器；{@code null} = 纯面积累积（旧行为，逐位一致）。 */
    private FlowField.PrecipSampler precipSampler;
    /** 降水权重参数。 */
    private FlowField.PrecipWeights precipWeights = FlowField.PrecipWeights.disabled();

    /**
     * 注入降水取样器 → 汇流累积改由<b>降水加权</b>（★ Phase C）。
     *
     * <p><b>必须在首次构建 region 前调用</b>；调用后会清空已有 region 缓存，
     * 避免"部分 region 加权、部分未加权"的混用。</p>
     */
    public void setPrecipSampler(FlowField.PrecipSampler sampler, FlowField.PrecipWeights weights) {
        this.precipSampler = sampler;
        this.precipWeights = weights != null ? weights : FlowField.PrecipWeights.defaults();
        regions.clear();
        if (regionsP1 != null) regionsP1.clear();
    }

    // ===== 水量平衡：沿程衰减（★ 2026-09-18 水文 M2）=====
    /**
     * 气候驱动衰减参数；{@code null} = 关闭（<b>旧行为，逐位一致</b>）。
     *
     * <p>语义：干旱区蒸发强 ⇒ 河流流着流着就消失（<b>内流河 / 时令河</b>）；
     * 湿润区 {@code decay ≈ 0} ⇒ 河流穿流到海。见
     * {@link FlowField.DecayClimate}。</p>
     */
    private FlowField.DecayClimate decayClimate;

    /**
     * 注入气候驱动衰减（★ 2026-09-18 水文 M2-C）。
     *
     * <p><b>必须在首次构建 region 前调用</b>；调用后会清空已有 region 缓存，
     * 避免"部分 region 衰减、部分未衰减"的混用。</p>
     *
     * <p>⚠ 与 {@link #setPrecipSampler} 一样，只改<b>河网规模</b>（进而河宽/湖域）；
     * 传 {@code null} 或 {@code maxDecay=0} ⇒ 与旧行为<b>逐位一致</b>。</p>
     */
    public void setDecayClimate(FlowField.DecayClimate dc) {
        this.decayClimate = dc;
        regions.clear();
        if (regionsP1 != null) regionsP1.clear();
    }

    // ★★★ 2026-09-14 性能修复（用户："刚创建加载有一段无动静的空闲期"）★★★
    //   【根因】region() 建 1 个 region 要先建 8 个邻居的 pass-1（3×3 循环）⇒ 9 次 build。
    //   每次 build 都 new FlowField，而对 region 覆盖范围（regionSize 640 + margin 320×2
    //   = 1280wu）按 gridCell=24 逐格调 routingE → eSampler.eAt（terrainEQuick）。
    //   ⇒ 单 region ~2,916 格、9 个 region ≈ 2.6 万次采样，且【9 个 region 的采样区域
    //     高度重叠】（相邻 region 只差 640wu，而各自覆盖 1280wu）⇒ 大量重复计算。
    //   实测：冷 region 837ms、热 region 3ms ⇒ 代价几乎全在首次的重复采样上。
    //
    //   【修复】加坐标级共享缓存：同一格点无论被哪个 region 采样都只算一次。
    //   · 纯函数（结果只由坐标 + 种子决定）⇒ 命中与未命中等价，输出【逐位一致】。
    //   · 线程安全：ConcurrentHashMap（pass-1 现为并行构建，见 region()）。
    //   · 量化到 1wu：采样点坐标为 originX + i·24（originX 为整数）⇒ floor 后精确无碰撞。
    //   · 上限护栏：超过 E_CACHE_MAX 则停止写入（退化为直接计算，不影响正确性）。
    private final Map<Long, Double> eCache = new ConcurrentHashMap<>();
    /**
     * 坐标缓存上限（保护内存）。
     *
     * <p>依据：region 网格步长 24wu，MAX_REGIONS=256 个 region 各自覆盖约 54×54 格，
     * 但相邻 region 高度重叠 ⇒ 实际不同坐标数明显小于 256×2916。取 1&lt;&lt;19（52 万条，
     * 约 40MB）已远超典型探索范围；超出后停止写入（退化为直接计算，正确性不变）。</p>
     */
    private static final int E_CACHE_MAX = 1 << 19;

    private double routingE(double wx, double wz) {
        long key = ((long) Math.floor(wx) << 32) ^ ((long) Math.floor(wz) & 0xFFFFFFFFL);
        Double hit = eCache.get(key);
        if (hit != null) return hit;
        double e = params.routingE(eSampler.eAt(wx, wz));
        if (routingDelta != null) e += routingGain * routingDelta.deltaAt(wx, wz);
        if (eCache.size() < E_CACHE_MAX) eCache.put(key, e);   // 纯函数 ⇒ 竞态下值相同
        return e;
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
        //
        // ★★★ 2026-09-14 性能修复（用户："刚创建加载有一段无动静的空闲期"）★★★
        //   【症状】首个 chunk 的阶段计时实测：sample=4ms extract=465ms **hydro=1526ms**。
        //   即"进度条出现前"有 ~1.5 秒完全无输出（出生点搜索结束→首个 chunk 之间）。
        //
        //   【根因】本处 3×3 循环"建 1 个 region 要先建 8 个邻居的 pass-1"
        //   ⇒ 首次访问任意 region 都要付【9 次构建】。单次 build 约 117~170ms
        //   ⇒ 9 × 170 ≈ 1.5s，与实测 hydro=1526ms 完全吻合。
        //   而 chunk(40,40)（region 已缓存）仅 hydro=26ms ⇒ 确认是一次性冷启动代价。
        //
        //   【为何可安全并行】① 各 pass-1 相互独立、无递归（代码设计如此）；
        //   ② FlowField 内部【无并行】（已核对：无 parallel/IntStream）⇒ 不嵌套并行；
        //   ③ regionsP1 是 ConcurrentHashMap + computeIfAbsent（原子、幂等）；
        //   ④ build 为纯函数（只读 eSampler/groundYAt，输出由 (rx,rz) 唯一确定）。
        //   ⇒ 并行后结果【逐位一致】，只是把 9 次串行的墙钟压到 ~ceil(9/核数) 次。
        //   用 commonPool（work-stealing，调用线程参与 ⇒ 不会"等自己"死锁，与
        //   CellGenerator.parallelRows 同一安全模式）。
        int[] drxArr = new int[8], drzArr = new int[8];
        int nNb = 0;
        for (int dRX = -1; dRX <= 1; dRX++) {
            for (int dRZ = -1; dRZ <= 1; dRZ++) {
                if (dRX == 0 && dRZ == 0) continue;
                // ★ 对角邻【必须保留】：collectOutlet 的 dRX/dRZ 恒为 ±1（出口指向必为对角），
                //   故 3×3 全部 8 邻都可能接到本 region 的续流种子 —— 不可裁剪。
                drxArr[nNb] = dRX; drzArr[nNb] = dRZ; nNb++;
            }
        }
        RiverLineRegion[] nbs = new RiverLineRegion[nNb];
        java.util.stream.IntStream.range(0, nNb).parallel().forEach(i ->
                nbs[i] = regionPass1(rx + drxArr[i], rz + drzArr[i]));
        List<RiverLineRegion.OutletSeed> incoming = new ArrayList<>();
        for (int i = 0; i < nNb; i++) {
            RiverLineRegion nb = nbs[i];
            int dRX = drxArr[i], dRZ = drzArr[i];
            for (RiverLineRegion.OutletSeed o : nb.outlets) {
                if (o.dRX == -dRX && o.dRZ == -dRZ) incoming.add(o);  // 邻的出口指向本 region
            }
        }
        r = regions.computeIfAbsent(key, ignored -> build(rx, rz, true, incoming));
        prune();
        return r;
    }

    /**
     * pass-1 构建（无交接，记录出口种子）。独立、无递归。
     *
     * <p>public 仅为诊断：{@code HandoffPickupProbe} 要核对"pass-1 发出的出口种子是否被
     * 邻区 pass-2 接上"，而该列表只在 pass-1 结果里完整。纯函数 + 已缓存，无副作用。</p>
     */
    public RiverLineRegion regionPass1(int rx, int rz) {
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
        // ★★★ 2026-09-19 修复：网格原点【全球对齐】—— 跨 region 水位不一致的根因 ★★★
        //
        //   原原点 = rx*regionSize − margin（**region 相关**）⇒ 格点位置随 region 变
        //   ⇒ 同一世界点在两个 region 落在【不同的格】上 ⇒ filledAt 必然不同。
        //
        //   实测（`runLakeEdgeProbe` [5]，三对 region，仅把原点取整即为对照）：
        //     未对齐：不一致 99.6% / 100.0% / 100.0%，最大差 21.65 / 3.74 / 22.78 块
        //     对齐后：不一致  0.0% /   0.1% /   0.5%，最大差  0.00 / 0.11 / 14.13 块
        //   ⇒ 主因即"格点未对齐"；余因 = 两网格【边界不同】⇒ 出口种子集合不同。
        //
        //   ★ 本项目在 PRECIP 上已有同范式（{@code FlowField} 类文档）：
        //     "格点取 k·PRECIP_STEP_WU（世界坐标），与 region 无关 → 杜绝 region 相关伪影"
        //     填洼网格此前没这么做 —— 本次补齐。
        //
        //   做法：只把原点取整到 cell 的倍数（**格距不变**）⇒ 格点 = k·cell（世界坐标）。
        double ax = Math.floor(minX / cell) * cell, az = Math.floor(minZ / cell) * cell;
        double bx = Math.ceil(maxX / cell) * cell, bz = Math.ceil(maxZ / cell) * cell;
        // ★ 选线场用"山压低"后的 e（routingE），使河线贴谷避峰；水面仍锚定真实地形（groundYAt）。
        // ★ Phase C：降水加权汇流累积（precipSampler 为 null 时与旧行为逐位一致）
        // ★ 2026-09-19（P1/P2）：把动量权重转交给 FlowField（见 FLOW_MOMENTUM_WEIGHT 的实测表）。
        //   ⚠ FlowField.MOMENTUM_WEIGHT 是静态的（避免改动 17+ 处构造调用）；
        //     生产上只有本处会写它，且写的是编译期常量 ⇒ 确定性不受影响。
        FlowField.MOMENTUM_WEIGHT = momentumOverride >= 0 ? momentumOverride : FLOW_MOMENTUM_WEIGHT;
        // ★ 2026-09-18 M2-C：decayClimate 为 null 时走 9 参构造器（decayPerWu=0）
        //   ⇒ 与旧行为逐位一致
        FlowField field = (decayClimate == null)
                ? new FlowField(ax, az, bx, bz, cell, this::routingE,
                                precipSampler, precipWeights)
                : new FlowField(ax, az, bx, bz, cell, this::routingE,
                                precipSampler, precipWeights, 0.0, decayClimate);
        // ★ 填洼层（湖泊）：按【真实地形】判定洼地——选线用的 routingE 是压过低山的
        //   人工高程，拿它找湖会把湖放在被压低的坡面上。
        // ★ 2026-09-15：第二参 = 真实海平面，作为 priority-flood 的<b>出水口</b>
        //   （原实现把"网格边界格"当出口 ⇒ 边界格永不成湖，见 FlowField.computeFill 的
        //   修复说明）。此处改用海洋 ⇒ 边界的陆地格也能成湖，且不会像"单一最低格"
        //   那样填出巨型湖（实测出现过 cells=772 的湖）。
        field.computeFill(this::groundYAt, curve.seaLevelY());
        // ===== ★★★ 2026-09-20「填洼优先」流向面（根治"河流突然结束"）★★★
        //
        //   【被修的缺陷】flowTo/accum 一直建在【原始 e】上 ⇒ 每个洼地/平地都是终点
        //     （lowestNeighbor 要求"严格更低"）⇒ 河追到洼地就停；若该洼地没被
        //     extractLakes 提取成湖，commitRiver 就落到最后 else（outletSurf =
        //     junctionGround）⇒ **河流就地以地形高度结束**（既非湖也非海）。
        //     用户判据："河流怎么可能突然结束"／"水堆积后溢出会继续向下流动"。
        //
        //   【参考实现】worldgen-master/src/hydrology.rs:105-181 —— priority_flood →
        //     flow_dir → accum，且填起区每步 +1e-5（epsilon 微坡），注释原文：
        //     "without this, D8 can't find a downhill direction on flat filled areas
        //      and rivers dead-end inland."
        //
        //   【为什么在 e 空间】eAt/oceanE/sourceMinE 与全部探针都在 e 口径标定；
        //     换成块空间 Y 会让 sourceMinE 判据全格命中、静默破坏密度标定。
        //     上面那层（块空间 groundYAt 填洼）继续只服务湖提取，两者保序等价。
        //
        //   【回退】RiverLineNetwork.fillFirstRouting = false（一行，回到旧行为）。
        if (fillFirstRouting) field.initializeFillFirstRouting(params.oceanE());

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

        // ===== ★★★ 2026-09-29【唯一水文管线】★★★
        // 生产只使用 TerrainFlowSim 的完整生命周期：
        // SOURCE → CHANNEL → BASIN_ENTRY → LAKE_STORAGE → SPILLWAY
        // → DOWNSTREAM → SEA。
        //
        // 旧 extractLakes()/traceRiver() 链不再先行创建第二套 LakeNode。骨架从空湖表
        // 开始，直接把 res.lake 的洼地蓄水连通域转成 LakeNode，并从同一份
        // basinSpillHeight 发出最低溢口续流。
        //
        // 骨架为空不是旧链回退条件：纯海区/无上游汇流时，正确结果就是本 region 无河。
        // 历史 traceRiver 代码仍在下方保留作源码考古；这些占位只服务其编译，
        // 生产入口在下方无条件 return 之前永远不会读取它们。
        List<Integer> lakeOutCells = new ArrayList<>();
        int[] lakeAt = new int[nx * nz];
        java.util.Arrays.fill(lakeAt, -1);
        boolean[] lakeHasInflow = new boolean[0];

        long skelT0 = System.nanoTime();
        SkelOut sko = buildSkeletonRivers(rx, rz, handoff, incoming, lakes);
        if (!sko.newLakes.isEmpty()) {
            lakes.addAll(sko.newLakes);
        }
        if (!sko.rivers.isEmpty()) {
            if (skelOkCount.getAndIncrement() < 40) {
                LOGGER.info("[RIVER] skeleton routing ACTIVE for region ({},{}): {} rivers, {} outlets, {} ms",
                        rx, rz, sko.rivers.size(), sko.outlets.size(),
                        (System.nanoTime() - skelT0) / 1_000_000L);
            }
        } else if (skelEmptyCount.getAndIncrement() < 40) {
            LOGGER.info("[RIVER] skeleton routing produced 0 rivers for region ({},{}): no valid lifecycle path",
                    rx, rz);
        }
        if (!legacyHydrologyPathEnabled()) {
            return new RiverLineRegion(rx, rz, sko.rivers, lakes, sko.outlets, false,
                    maxDischarge, sko.rivers.size(), 0, 0);
        }

        // 旧 traceRiver 链保留在源码中供考古/后续删除，但生产唯一入口不可再进入。
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
        int stepSize = mainTraceStepOverride > 0 ? mainTraceStepOverride : params.traceStep();
        List<Integer> accepted = new ArrayList<>();
        int acceptedCount = 0;

        // ===== 普通源追踪（本 region 高位布源）=====
        // ★ neighborRivers：8 邻 region 的 pass-1 河（world wu 同域）。邻区河的雕刻
        //   margin 区可伸入本区最多 320 wu——本区深处的源河头也可能贴着邻区大河的
        //   谷壁（实测 seed 107373 源头侵入邻区 8.4wu 粗河的谷壁 8.1 格，而源河提交
        //   时 extra=null 完全看不见它）。handoff=true 时 8 邻 pass-1 必已就绪
        //   （region() 先收缝种子再建本区 pass-2），无递归风险；pass-1 build 保持
        //   独立（空表）。pass-1 与邻区最终河可能略有差异，作谷壁判据足够。
        List<RiverPolyline> neighborRivers = new ArrayList<>();
        if (handoff) {
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    if (dx == 0 && dz == 0) continue;
                    neighborRivers.addAll(regionPass1(rx + dx, rz + dz).rivers);
                }
            }
        }
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
            if (insideExistingValley(field, rivers, neighborRivers, s)) continue;

            TraceOutcome out = traceRiver(field, s, stepSize, claimed, nodeE,
                    allSegments, nx, nz, rx, rz, Double.NaN, lakeAt);
            if (out == null) { rolledBack++; continue; }
            if (out.joined) joinedCount++;

            int level = 1;
            if (out.joined) {
                int last = out.cells.get(out.cells.size() - 1);
                int tl = levelAt[last];
                if (tl > 0) level = tl + 1;
            }
            // 入湖：记下"这个湖有河汇入"（决定是否发溢出续流河），并让河尾水面 = 湖面
            double lakeSurface = Double.NaN;
            if (out.isLake && !out.cells.isEmpty()) {
                int last = out.cells.get(out.cells.size() - 1);
                int li = lakeAt[last];
                if (li >= 0) {
                    lakeSurface = lakes.get(li).height;
                    lakeHasInflow[li] = true;
                }
            }
            CommitOut c = commitRiver(field, out, level, claimed, nodeE, nodeSurf,
                    levelAt, allSegments, rivers, specs, lakes, accepted, nx, Double.NaN, rx, rz,
                    null, lakeSurface, false);
            maxDischarge = Math.max(maxDischarge, c.maxDischarge());
            if (c.reachedOcean()) outletOcean = true;
            if (c.poly() != null) {
                // ★ 仅"成功成为河"的源点参与后续源点的间距过滤。
                accepted.add(s);
                acceptedCount++;
                if (out.outlet) collectOutlet(out, field, rx, rz, outlets, c.tailSurface(), level);
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

                // ★ 续流河头落进邻河谷壁 → 并入为分支河（2026-09-07，用户方案）：
                //   两条续流从不同缝口进入同一区域且地形让它们平行同谷时，谷槽兜底
                //   （bestValleyHead）全落空 → 河头被放在邻河谷壁内（实测 seed 28183
                //   侵入 18.4 格；形态是"一条河贴着另一条河开平行河道"）。与其开平行
                //   河道，不如就地并入：从缝口走一小段直接汇入最近的已有河（继承其
                //   交汇点水面 → 零台阶），上游来水经由被并入的河继续入海，水文不断。
                //   仅当：(a) 最近的已占用格在 3 格内（否则直线连出去太做作）；(b) 该格
                //   水面 ≤ 缝口地面（水才能流进去）；(c) 连线 ≥ minRiverNodes 格。
                //   任一不满足则回退原独立续流。
                List<Integer> mergeCells = null;
                if (insideExistingValley(field, rivers, neighborRivers, start)) {
                    mergeCells = mergeIntoNearestRiver(field, start, nx, claimed, nodeSurf,
                            neighborRivers);
                }
                if (mergeCells != null) {
                    double[] mAcc = new double[mergeCells.size()];
                    java.util.Arrays.fill(mAcc, seed.accum);
                    TraceOutcome mOut = new TraceOutcome(mergeCells, false, false, true, mAcc, false);
                    CommitOut mc = commitRiver(field, mOut, seed.level + 1, claimed, nodeE,
                            nodeSurf, levelAt, allSegments, rivers, specs, lakes, accepted,
                            nx, seed.surfaceY, rx, rz, neighborRivers, Double.NaN, false);
                    if (mc.poly() != null) {
                        accepted.add(start);
                        acceptedCount++;
                        continue;
                    }
                }

                TraceOutcome out = traceRiver(field, start, stepSize, claimed, nodeE,
                        allSegments, nx, nz, rx, rz, seed.accum, lakeAt);
                if (out == null) continue;
                if (out.joined) joinedCount++;
                // 继承上游层级；若续流汇入本 region 已有河，则为该河支流（层级+1）
                int level = seed.level;
                if (out.joined) {
                    int last = out.cells.get(out.cells.size() - 1);
                    int tl = levelAt[last];
                    if (tl > 0) level = tl + 1;
                }
                // 续流也可能入湖（湖面继承，同普通源）
                double lakeSurface2 = Double.NaN;
                if (out.isLake && !out.cells.isEmpty()) {
                    int last = out.cells.get(out.cells.size() - 1);
                    int li = lakeAt[last];
                    if (li >= 0) {
                        lakeSurface2 = lakes.get(li).height;
                        lakeHasInflow[li] = true;
                    }
                }
                // 续流首节点水面 = 上游尾节点水面（保证跨缝水面连续，无台阶）
                CommitOut c = commitRiver(field, out, level, claimed, nodeE, nodeSurf,
                        levelAt, allSegments, rivers, specs, lakes, accepted, nx, seed.surfaceY,
                        rx, rz, neighborRivers, lakeSurface2, false);
                maxDischarge = Math.max(maxDischarge, c.maxDischarge());
                if (c.reachedOcean()) outletOcean = true;
                if (c.poly() != null) {
                    accepted.add(start);
                    acceptedCount++;
                }
            }
        }

        // ===== 湖满溢 → 下游续流河（2026-09-07；★ 2026-09-20 改为【无条件出流】）=====
        //
        // 【用户判据（2026-09-20，两次强调）】"正常来说水流堆积后溢出会继续向下流动的" /
        //   "河流怎么可能突然结束呢" —— 即：**水的守恒必须体现在"湖一定会有出流"**。
        //
        // 【旧行为（被本条取代）有两个"不发出口河"的漏洞，实测都会让水面【突然结束】】
        //   ① `if (!lakeHasInflow[li]) continue;` —— 无河汇入的湖不发出口河。
        //      依据"没有上游来水硬接一条河 = 无源之河"。但湖面【高于当地地形】时，
        //      盆地的水溢出去是【物理必然】（降水/地下水补给一样会漫出），
        //      强行不给出口 ⇒ 观感就是"水堆在那儿不动、下游凭空断了"。
        //   ② `if (outCell < 0 || claimed[outCell]) continue;` —— 溢出口格若已被别的河认领
        //      （或 spillCell 因邻格全在水下而返回 -1），同样不发 ⇒ 断流。
        //
        // 【新行为】只要有湖：先 trace 溢出口外侧；**失败（-1 / 已认领 / trace 回滚）就退化为
        //   "并入最近已有河"**（`mergeIntoNearestRiver`，与跨区续流的合并同一机制）
        //   ⇒ 水【必然】有出流，且汇合处继承对方水面（零台阶）。
        //
        // ⚠ 唯一保留的例外：出口河【一出盆就又进了同一个湖】（`outletOnlyToLake`）——
        //   那是"湖把自己包住"的退化几何，再发也是原地打转。
        for (int li = 0; li < lakes.size(); li++) {
            tailDiag.lakesTotal++;
            if (!lakeHasInflow[li]) tailDiag.lakeNoInflow++;
            RiverLineRegion.LakeNode lk = lakes.get(li);
            int outCell = lakeOutCells.get(li);
            if (outCell < 0) {
                tailDiag.lakeOutCellMissing++;
            } else if (claimed[outCell]) {
                tailDiag.lakeOutCellClaimed++;
            }
            // ① 正常出口：溢出口外侧未认领 ⇒ 追踪一条续流河
            if (outCell >= 0 && !claimed[outCell]) {
                TraceOutcome o = traceRiver(field, outCell, stepSize, claimed, nodeE,
                        allSegments, nx, nz, rx, rz, field.accumAt(outCell), lakeAt);
                if (o != null && !outletOnlyToLake(o, lakeAt)) {
                    CommitOut c = commitRiver(field, o, 1, claimed, nodeE, nodeSurf,
                            levelAt, allSegments, rivers, specs, lakes, accepted, nx, lk.height,
                            rx, rz, null, Double.NaN, false);
                    maxDischarge = Math.max(maxDischarge, c.maxDischarge());
                    if (c.reachedOcean()) outletOcean = true;
                    continue;
                }
            }
            // ② 兜底（★ 2026-09-20 新增）：出口不可用 ⇒ 从"湖面上最高/最靠外的岸边"并入最近已有河
            spillMergeFallback(field, lakeAt, lk, nx, nz, rx, rz, claimed, nodeE, nodeSurf,
                    levelAt, allSegments, rivers, specs, lakes, accepted, stepSize);
        }
        // ===== 支流分叉（2026-09-20，FTF generateForks 范式）=====
        //   位置：主源 / 跨区续流 / 湖溢出口 三段【都跑完之后】⇒ 所有可能的父河都已存在；
        //   且在 resolveMeanderCrossings 之前 ⇒ 分叉自身也参与 meander 去交叉。
        //   ★ 默认 params.fork().enabled()=false 且 forkDryRun=false ⇒ 【零行为变更】。
        //   ★ 只在 pass-2（handoff=true）生成：分叉必须 joined ⇒ 不产 outlet ⇒ 不影响
        //     跨区交接拓扑；pass-1（仅作邻区谷壁判据）无需含分叉。
        if ((params.fork().enabled() || forkDryRun) && handoff) {
            generateForks(new ForkCtx(field, rivers, specs, claimed, nodeE, nodeSurf, levelAt,
                    allSegments, lakes, accepted, lakeAt, nx, nz, rx, rz), forkDryRun);
        }

        // ★★★ 2026-09-20 汇合处【宽深单调】（SDF 平滑并集语义）—— 修"粗支流接进细下游" ★★★
        //
        //   【用户实机判据】"其他分支的粗节点又接上其他下游的细节点…既然连上宽度深度等变化
        //   就应该是单调性的"。
        //
        //   【实测】runRiverEndProbe 新增判据：35 个汇合点里 13 个（37%）"支流比承接处更粗"
        //   （例：支流 level=4 半宽 2.94 → 承接河 level=2 半宽 1.91）。
        //
        //   【机制】支流经 `nearbyDownhillNode` 的【≤2 格跳跃】汇入 ⇒ 它的水并不真的流进
        //   承接河那一格 ⇒ 承接河该格的 D8 汇流面积【不含支流】⇒ 下游反而更细（水量不守恒）。
        //
        //   【参考依据】MOBIDIC `width_m = Br0 · strahler_order^NBr`、Streams `streamSize`
        //   ⇒ 参考实现里"汇流后必然更宽"是由拓扑累加保证的。
        //
        //   【修法（光线追踪的 SDF 平滑并集思想）】把汇合点的两支看作两个 SDF 的并集：
        //   并集在任一点"不小于任一子集" ⇒ 承接河自交汇点起的半宽/水深
        //   ≥ 支流尾端的半宽/水深（取 max 而非 lerp，等价于 hard-union；
        //   与既有 smooth-min 合并宽度配合，视觉上无需额外平滑）。
        //   多遍松弛：承接河可能先于其支流提交 ⇒ 需向下游传播。
        // ⚠ 调用点必须在 resolveMeanderCrossings【之后】：去 meander 会用 RiverSpec 里的
        //   原始 wid/dep 重建折线 ⇒ 在此之前的宽度修改会被丢弃。

        // ★ meander 去交叉后处理（2026-08-31）：见 commitRiver 注释。区域全部河建好后，
        //   迭代把"与别的河真交叉"的河重建为无 meander（其非 meander 路径沿用已防交叉的
        //   格路径），直到无交叉或无可去 meander 的河。解决单向 de-meander 修不了的
        //   "先提交河 meander 摆进后提交河直线路径"情形。
        resolveMeanderCrossings(rivers, specs);

        // ★ 汇合处宽深单调（SDF 平滑并集语义）—— 必须在去 meander【之后】（见上）。
        enforceConfluenceMonotonic(rivers);

        return new RiverLineRegion(rx, rz, rivers, lakes, outlets, outletOcean, maxDischarge,
                sourceCount, rolledBack, joinedCount);
    }

    /**
     * 旧 traceRiver / extractLakes 生产旁路永久关闭。
     *
     * <p>保留为非常量方法，避免编译器把历史代码折叠成不可达代码；调用点位于
     * 无条件骨架返回之后，因此生产永远只有一条水文管线。</p>
     */
    private static boolean legacyHydrologyPathEnabled() {
        // 唯一管线裁定后不再提供运行时回退开关；旧代码仅保留在源码中待清理。
        return false;
    }

    /**
     * 续流并入：从缝口格找最近的汇入目标（本 region 已占用格，或邻区折线上的点），
     * 沿直线回一条短连接的格序列，使续流作为分支汇入那条河。
     *
     * <p>条件（任一不满足返回 {@code null}，调用方回退独立续流）：</p>
     * <ul>
     *   <li>目标在 {@code maxLink}（3）格内——更远就连出一条做作的直线河；</li>
     *   <li>目标水面 ≤ 缝口格地面——水才能流得进去（顺坡汇入）；</li>
     *   <li>连线（去重后）≥ {@code params.minRiverNodes()} 格。</li>
     * </ul>
     *
     * <p>目标是邻区折线上的点时，其格在本 region 网格内无 nodeSurf——按该点插值水面
     * 【预写】进 nodeSurf，交汇继承（joined → outletSurf = nodeSurf[junction]）才有值。</p>
     */
    private List<Integer> mergeIntoNearestRiver(FlowField field, int start, int nx,
                                                boolean[] claimed, double[] nodeSurf,
                                                List<RiverPolyline> extra) {
        final int nz = field.rows();
        final int maxLink = 3;
        int ci = start % nx, cj = start / nx;
        double headWx = field.cellCenterX(start), headWz = field.cellCenterZ(start);
        int target = -1;
        double targetSurf = Double.NaN;
        double bestD2 = maxLink * params.gridCell() * maxLink * params.gridCell();
        // ① 本 region 已占用格（nodeSurf 已有值）
        for (int dj = -maxLink; dj <= maxLink; dj++) {
            for (int di = -maxLink; di <= maxLink; di++) {
                if (di == 0 && dj == 0) continue;
                int ni = ci + di, nj = cj + dj;
                if (ni < 0 || ni >= nx || nj < 0 || nj >= nz) continue;
                int n = nj * nx + ni;
                if (n == start || !claimed[n] || Double.isNaN(nodeSurf[n])) continue;
                double d2 = di * di + dj * dj;
                if (d2 < bestD2) { bestD2 = d2; target = n; targetSurf = nodeSurf[n]; }
            }
        }
        // ② 邻区折线上的最近点（world wu 同域；其格在本 region 网格无 nodeSurf）
        if (extra != null) {
            for (RiverPolyline r : extra) {
                for (int i = 0; i < r.nodes.length; i++) {
                    double ddx = r.nodes[i].x() - headWx, ddz = r.nodes[i].z() - headWz;
                    double d2 = ddx * ddx + ddz * ddz;
                    if (d2 < bestD2) {
                        int idx = field.indexOf(r.nodes[i].x(), r.nodes[i].z());
                        if (idx < 0 || idx == start) continue;
                        bestD2 = d2; target = idx; targetSurf = r.surfaceY[i];
                    }
                }
            }
        }
        if (target < 0) return null;
        // 水文校验：目标是旱地且其水面高于缝口地面 → 水流不进去，拒绝。
        // 缝口格地面在【海平面及以下】时直接放行：那是海岸水位带，河头本就泡在水里，
        // 目标水面（多为海平面）不可能"高于"一个有意义的地形（实测入海口案例
        // seed 28183 的合并正因此被误拒）。
        double startGround = groundYAt(headWx, headWz);
        if (targetSurf > startGround + 1e-6 && startGround > curve.seaLevelY()) return null;
        // 目标若是邻区河：预写交汇水面，joined 继承才有值
        if (Double.isNaN(nodeSurf[target])) nodeSurf[target] = targetSurf;
        // 直线连线（格坐标插值，去重保序）
        int ti = target % nx, tj = target / nx;
        int steps = Math.max(Math.abs(ti - ci), Math.abs(tj - cj));
        List<Integer> cells = new ArrayList<>();
        int li = -1, lj = -1;
        for (int s = 0; s <= steps; s++) {
            int ii = ci + (ti - ci) * s / Math.max(1, steps);
            int jj = cj + (tj - cj) * s / Math.max(1, steps);
            int idx = jj * nx + ii;
            if (ii != li || jj != lj) cells.add(idx);
            li = ii; lj = jj;
        }
        if (cells.get(cells.size() - 1) != target) cells.add(target);
        // ★ 最短 2 格（河头+交汇点）即可：邻河经常就在 1 格内（实测 1.7 wu），此时
        //   "缝口→就近汇入"本来就该是一个极短的 Y 形连接。minRiverNodes(3) 的
        //   长度门槛是给"独立河"的防退化下限，不适用于合并连接。
        if (cells.size() < 2) return null;
        return cells;
    }

    /** 一条河的平滑原始输入（供 meander 去交叉后处理整条重建）。 */
    private static final class RiverSpec {
        final MidpointDisplacement.Node[] nodes;
        final double[] surf, wid, dep;
        final int level;
        final boolean feeder;  // 源前细流（去交叉时的优先牺牲者：去 meander 不可见）
        boolean meandered;     // 当前 rivers 里这条是否带 meander（可被去 meander）
        RiverSpec(MidpointDisplacement.Node[] nodes, double[] surf, double[] wid,
                  double[] dep, int level, boolean meandered, boolean feeder) {
            this.nodes = nodes; this.surf = surf; this.wid = wid; this.dep = dep;
            this.level = level; this.meandered = meandered; this.feeder = feeder;
        }

    }

    /** 提交一条已追踪河流：认领/记录/裁剪/算宽深/水面，返回最终折线（null=被阈值丢弃）。 */
    private CommitOut commitRiver(FlowField field, TraceOutcome out, int level,
                                  boolean[] claimed, double[] nodeE, double[] nodeSurf,
                                  int[] levelAt, List<int[]> allSegments,
                                  List<RiverPolyline> rivers, List<RiverSpec> specs,
                                  List<RiverLineRegion.LakeNode> lakes,
                                  List<Integer> accepted, int nx, double forcedSrcH, int rx, int rz,
                                  List<RiverPolyline> extraValleys,
                                  double lakeSurface, boolean feeder) {
        // ★ 2026-09-20 诊断（零行为变更）：记录河尾终止原因 —— 见 tailDiag 的说明。
        if (!out.cells.isEmpty()) {
            int lastCell = out.cells.get(out.cells.size() - 1);
            if (out.joined) tailDiag.joined++;
            else if (out.reachedOcean) tailDiag.ocean++;
            else if (out.isLake && !Double.isNaN(lakeSurface)) tailDiag.lakeWithNode++;
            else if (out.isLake) {
                tailDiag.lakeNoNode++;
                if (field.isBasinCell(lastCell)) tailDiag.basinCell++;
                else tailDiag.notBasin++;
                // ★ 记录坐标 + 【具体成因】：用户实机"河流突然结束"的病例就在这些点。
                //   成因码见 TraceOutcome.END_*（用于判别"哪一类是我引入的"）。
                if (out.endReason == TraceOutcome.END_SELF_LOOP) tailDiag.endSelfLoop++;
                else if (out.endReason == TraceOutcome.END_SELF_APPROACH) tailDiag.endSelfApproach++;
                else if (out.endReason == TraceOutcome.END_NO_DOWN_NO_EXIT) tailDiag.endNoDownNoExit++;
                tailDiag.noNodeTails.add(new double[]{
                        field.cellCenterX(lastCell), field.cellCenterZ(lastCell),
                        out.endReason});
            } else if (out.outlet) tailDiag.outlet++;
        }
        // ★★★ 2026-09-20 修复：「认领」必须与【可见折线】严格一致 ★★★
        //
        //   【被修的缺陷（用户实机："河流直接以河结束" / 河与河之间断口）】
        //   原实现在【本函数开头】就把 out.cells 的【全部】格 claimed=true、写 nodeE/levelAt、
        //   把全部段加进 allSegments —— 而折线只从【裁剪后的 start】起生成（见下方长度判据之后）。
        //   ⇒ 被裁掉的上游段、以及【整条被丢弃】的河，都留下【幽灵格】：claimed=true 但
        //     没有任何可见河道；而后来的河却把它们当汇合目标（traceRiver 的 `claimed[cur]`
        //     与 nearbyDownhillNode）⇒ 河尾停在【离任何可见河道几十格】的位置 ⇒ 肉眼"河断了"。
        //
        //   实测（runRiverEndProbe，3×3 region，seed 9139912035078620160）：
        //     汇合断口 >12 block = 11/19（57.9%），p50=17、p90=44、max=62 block。
        //
        //   【修法】把认领/段登记下移到【裁剪 + 长度判据之后】，且只覆盖 start.. 的可见格。
        //     ⇒ 汇合点必然落在可见折线的首格上（残余偏差只剩 Catmull-Rom/meander 的偏移）。
        //   【预期副作用】上游被裁段不再"占位" ⇒ 后续河可继续上溯（连续性应改善）。
        //   ⚠ 认领下移后，本函数内【依赖 claimed 的逻辑】必须复核：
        //     `mergeIntoNearestRiver`（续流并入）读 claimed 找最近目标 —— 它要的是【其它河的】
        //     已认领格，本河尚未认领反而更正确（旧行为可能把本河自己的格当目标）。
        // 汇流面积阈值裁剪源头细流（树状稀疏）
        int start = 0;
        // ★ feeder（现仅指"续流并入"的合并连接）跳过本裁剪：合并连接的每一格 accum
        //   都【低于】成河门槛——它只是缝口到既有河的短连接，按门槛裁会整条被裁光。
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
        // ★ 细流（feeder）不参与谷槽门槛（2026-09-07，修"扇形细流看不见"）：细流上溯
        //   进入山脊区，谷槽裕度随汇流收窄迅速跌破阈值 → 门槛把 pass-2（游戏实际
        //   使用的 build）里的细流几乎全部弃用（漏斗实测：候选 10 → 弃用 6、提交 0）。
        //   该门槛防的是"可见断面切在光坡上"——细流头已归零淡出（宽 1 格、深 0），
        //   切不出任何断面，位置本就无害；当年"弃用更优"的证据是 insideOther 假
        //   指标（已证伪）。细流的淘汰交给上游候选/长度下限即可。
        int valleyStart = feeder ? start
                : advanceToValleyHead(field, out, start, rivers, extraValleys);
        if (out.cells.size() - valleyStart >= params.minRiverNodes()) {
            start = valleyStart;                                  // 有达标谷槽，直接采用
        } else if (!feeder) {
            // 全程找不到达标谷槽（或为此会把河裁没）：退而求其次取"最像谷槽"的一格，
            // 而不是停在恰好达汇流门槛的任意位置（实测该任意位置两个种子各有 22% 落在
            // 凸坡肩部）。bestValleyHead 的上界已预留 minRiverNodes，不会把河裁丢。
            int fallback = bestValleyHead(field, out, start, rivers, extraValleys);
            if (out.cells.size() - fallback >= params.minRiverNodes()) start = fallback;
            // ★ 兜底河头仍落在邻河谷壁内且本河是【跨区续流】→ 并入为分支河
            //   （2026-09-07，用户方案："源头有概率贴近其他河流，可以尝试将其并入
            //   成为一条分支河"）。两条续流从不同缝口进入同一区域且平行同谷时，
            //   谷槽回避全落空 → 河头被放在邻河谷壁里（实测 seed 28183 侵入 18.4 格，
            //   形态是贴着邻河开平行河道）。并入：从河头走一小段直线汇入最近的已有河
            //   （继承其交汇点水面 → 零台阶 Y 形汇流），上游来水经由被并入的河继续
            //   入海，水文不断。合并提交以 feeder 语义走 commitRiver（跳谷槽门槛、
            //   不再触发本分支的递归），但 forcedSrcH 非 NaN → 河头无淡出、保持全宽。
            //   extraValleys：邻区 pass-1 河也参与谷壁检查（续流在 margin 区选头，
            //   本 region 河列表跨区致盲——合并判据与谷壁回避都需要它）。
            // ★ 2026-09-11 越界守卫（既有潜在 bug，被 Phase C 的 accum 分布变化触发）：
            //   上面的汇流门槛裁剪循环（见 `while (start < out.cells.size() && out.accum[start] <=
            //   riverAccumThreshold()) start++;`）可把 start 推到 `out.cells.size()`（整条河都不达标）。
            //   而本块直接 `out.cells.get(start)`，"河太短"的丢弃判据却在更下面 → 越界崩溃。
            //   此处补 `start < size` 守卫；不成立时直接落下、由下方长度下限判据正常丢弃。
            if (!Double.isNaN(forcedSrcH) && start < out.cells.size()
                    && insideExistingValley(field, rivers, extraValleys, out.cells.get(start))) {
                List<Integer> link = mergeIntoNearestRiver(field, out.cells.get(start),
                        nx, claimed, nodeSurf, extraValleys);
                if (link != null) {
                    List<Integer> mc = new ArrayList<>();
                    mc.add(out.cells.get(start));
                    for (int c : link) if (c != mc.get(mc.size() - 1)) mc.add(c);
                    if (mc.size() >= 2) {
                        double[] mAcc = new double[mc.size()];
                        java.util.Arrays.fill(mAcc, out.accum[start]);
                        TraceOutcome mOut = new TraceOutcome(mc, false, false, true, mAcc, false);
                        CommitOut mC = commitRiver(field, mOut, level + 1, claimed, nodeE,
                                nodeSurf, levelAt, allSegments, rivers, specs, lakes,
                                accepted, nx, forcedSrcH, rx, rz, extraValleys,
                                Double.NaN, true);
                        if (mC.poly() != null) {
                            return mC;
                        }
                    }
                }
            }
        }
        // ★ 长度下限：独立河 ≥ minRiverNodes；feeder 语义下的【合并连接】（河头+交汇点
        //   的 2 格 Y 形）也放行——到达这里的 2 格 feeder 只可能是合并连接（无淡出、全宽）。
        if (out.cells.size() - start < params.minRiverNodes()
                && !(feeder && out.cells.size() - start >= 2))
            return new CommitOut(null, out.reachedOcean, 0.0, null, Double.NaN);
        // ★ 2026-09-20：认领/段登记【只覆盖可见折线格】（start..end）——（claimVisibleOnly=false 走旧行为）
        //   为什么必须放在这里见函数开头那段说明（幽灵格 ⇒ 汇合断口）。
        //   ⚠ 与"长度判据之上的 return"配合 ⇒ 被丢弃的河【不再留下任何幽灵格】。
        if (claimVisibleOnly) {
            for (int k = start; k < out.cells.size(); k++) {
                int c = out.cells.get(k);
                claimed[c] = true;
                // ★ 2026-09-20：记【建流向那张面】的高程 —— 就近汇入判据（nearbyDownhillNode）
                //   要与追踪器同一口径，否则它按原始 e 判断"我是不是比已有河低"，
                //   在填洼面上会得出相反结论（把已能流走的地方判成"不比我低"）。
                nodeE[c] = field.flowElevAt(c);
                if (levelAt[c] == 0) levelAt[c] = level;   // 交汇节点已属主流，勿覆盖其层级（PL-RGA 节点共享）
            }
            for (int k = start; k < out.cells.size() - 1; k++) {
                int a = out.cells.get(k), b = out.cells.get(k + 1);
                allSegments.add(new int[]{a % nx, a / nx, b % nx, b / nx});
            }
        } else {
            for (int c : out.cells) {                       // 旧行为（对照用）
                claimed[c] = true;
                nodeE[c] = field.flowElevAt(c);
                if (levelAt[c] == 0) levelAt[c] = level;
            }
            for (int k = 0; k < out.cells.size() - 1; k++) {
                int a = out.cells.get(k), b = out.cells.get(k + 1);
                allSegments.add(new int[]{a % nx, a / nx, b % nx, b / nx});
            }
        }
        int m = out.cells.size() - start;
        MidpointDisplacement.Node[] nodes = new MidpointDisplacement.Node[m];
        double[] rawSurf = new double[m], wid = new double[m], dep = new double[m];
        double acc = 0.0;
        // ★ 只有【真源头】淡出：跨 region 续流的"源端"是瓦片缝而非河源，在此收窄会
        //   在缝上重新造成宽度骤缩（crossRegion 机制专门修掉的那个"宽度重置"断缝）。
        //   forcedSrcH 非 NaN 即为续流（见 build() 的 seed.surfaceY 传参）。
        boolean taperHead = Double.isNaN(forcedSrcH);
        // ★★★ 2026-09-19（P2）河线节点位置：格心 → 【沿连续流向场（含动量）积分的粒子位置】★★★
        //
        //   【被修的缺陷（用户实机判据）】"河网走向不像自然河流（太直 / 太规则）"。
        //   原实现 `nodes[k] = field.cellCenter(idx)` —— 节点钉死在【格心】上，而格序列是
        //   D8 的 8 邻路径 ⇒ 折线先天是 45° 阶梯（Catmull-Rom 只能磨圆，磨不掉步长）。
        //
        //   【修法（对应用户的定义："河流只是水的运动路线"）】仍以该格序列为【参数化】，
        //   但位置由【粒子沿连续流向场推进】给出：
        //     · 方向 = 连续流向(含上游累积动量) ⊕ 指向下一格心（越近越偏向目标 ⇒ 不脱离格序列）
        //     · 步长 = 到下一格心的距离（与格序列严格同步 ⇒ 不产生滞后累积）
        //   ⇒ 轨迹落在格心【之间】，是自然曲线而非阶梯。
        //
        //   【依据】FlowField.MOMENTUM_WEIGHT 的注释（SimpleHydrology 的 momentumTransfer，
        //     README 原话 "giving river meandering behavior"）；实测 45° 整数倍占比
        //     100.0% → 18.8%（runFlowDirectionHistogramProbe）。
        //
        //   【回退】FlowField.MOMENTUM_WEIGHT = 0.0 ⇒ 本段逐位回到"格心"（旧行为）。
        // ★★★ 2026-09-20「有界粒子」节点定位（取代动量粒子）★★★
        //
        //   【与动量解耦】原实现 `particlePos = FlowField.MOMENTUM_WEIGHT > 0.0`：
        //   为了拿到"非阶梯"的节点位置，就必须打开动量；而动量会让粒子在格内打转
        //   ⇒ 马蹄形闭环 + 节点被甩离交汇格（见 FLOW_MOMENTUM_WEIGHT 的实机否决记录）。
        //   ⇒ 现在【解耦】：D8/accum/trace 用纯 D8（拓扑已验证），
        //     节点位置单独用"连续方向场 + 硬钳制"，不再依赖动量。
        //
        //   【为什么不会绕圈（数学保证）】每个节点被钳在【它所属格中心 ≤ 0.45 格】内
        //   ⇒ 折线是"格序列的有限抖动"，相邻节点间距 ~1 格且偏离有界
        //   ⇒ 不可能出现"沿程 1987 block / 直线 179 block"那种跑出去又绕回来。
        final boolean particlePos = PARTICLE_POS_ENABLED;
        final double gridCell = params.gridCell();
        double ppx = particlePos ? field.cellCenterX(out.cells.get(start)) : 0.0;
        double ppz = particlePos ? field.cellCenterZ(out.cells.get(start)) : 0.0;
        for (int k = 0; k < m; k++) {
            int idx = out.cells.get(start + k);
            double wx, wz;
            if (!particlePos || k == 0) {
                wx = particlePos ? ppx : field.cellCenterX(idx);
                wz = particlePos ? ppz : field.cellCenterZ(idx);
            } else {
                double tx = field.cellCenterX(idx), tz = field.cellCenterZ(idx);
                double ddx = tx - ppx, ddz = tz - ppz;
                double dist = Math.sqrt(ddx * ddx + ddz * ddz);
                if (dist < 1e-9) {
                    wx = ppx; wz = ppz;
                } else {
                    double[] dirv = field.dirAtWu(ppx, ppz);
                    double g = Math.min(1.0, dist / gridCell);      // 离下一格心越远 ⇒ 越信流向
                    double ux = dirv[0] * g + (ddx / dist) * (1.0 - g);
                    double uz = dirv[1] * g + (ddz / dist) * (1.0 - g);
                    double len = Math.sqrt(ux * ux + uz * uz);
                    if (len < 1e-12) { ux = ddx / dist; uz = ddz / dist; len = 1.0; }
                    ppx += ux / len * dist;
                    ppz += uz / len * dist;
                    wx = ppx; wz = ppz;
                }
            }
            // ★ 有界钳制：节点必须留在【本格中心】半径 maxDev 之内（唯一防绕圈的硬保证）
            double cellCx = field.cellCenterX(idx), cellCz = field.cellCenterZ(idx);
            double devX = wx - cellCx, devZ = wz - cellCz;
            double dev = Math.sqrt(devX * devX + devZ * devZ);
            double maxDev = PARTICLE_MAX_DEV_FRAC * gridCell;
            if (dev > maxDev && dev > 1e-9) {
                wx = cellCx + devX / dev * maxDev;
                wz = cellCz + devZ / dev * maxDev;
            }
            double a = out.accum[start + k];
            nodes[k] = new MidpointDisplacement.Node(wx, wz);
            rawSurf[k] = groundYAt(wx, wz);
            double w = widthFromAccum(a, params);
            double d = depthFromAccum(a, w, params);
            if (taperHead) {
                double tp = headTaper(k, m);
                w *= HEAD_MIN_WIDTH_FRACTION + (1.0 - HEAD_MIN_WIDTH_FRACTION) * tp;
                d *= HEAD_MIN_DEPTH_FRACTION + (1.0 - HEAD_MIN_DEPTH_FRACTION) * tp;
                // 宽深比护栏按淡出后的宽度重算（淡出后 W 变小，D 不得再按原 W 放行）
                d = Math.min(d, params.maxDepthRatio() * w);
            }
            // ★★★ 2026-09-20【最小可渲染断面】—— 修"河线规划出来却没水"★★★
            //
            //   【被修的缺陷】Minecraft 是 **1 块栅格**，而落块闸门（HydrologyBlockCarver
            //   anyFill）是 `nearestDist ≤ max(width,1)` 且 `carved < waterSurface − 0.5`：
            //     ① 河头淡出让水深降到 0.06×1.6 ≈ **0.1 块** < 0.5 ⇒ 床面够不到水面 ⇒
            //        **一列水都不放**；实测干节点样本 halfW=0.65/depth=0.48 即此类。
            //     ② 半宽 < 1 块时，块心可能落在河道外 ⇒ 同样不出水。
            //   ⇒ 这两类"规划了却看不见"正是用户判据"河线规划出来必须有水"。
            //
            //   【物理依据】小于 1 块的断面在 1 块栅格里不可表示 —— 不是"更细的溪"，
            //   而是"根本不存在"。故给断面设栅格分辨率下限（不是加宽河，只是可渲染性护栏）。
            if (minSectionEnabled) {
                if (w < MIN_RENDER_HALF_WIDTH) w = MIN_RENDER_HALF_WIDTH;
                if (d < MIN_RENDER_DEPTH) d = MIN_RENDER_DEPTH;
                // 宽深比护栏（D ≤ maxDepthRatio·W）重算：抬深后不得超出允许宽深比
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
        } else if (out.isLake && !Double.isNaN(lakeSurface)) {
            // ★ 入湖：河尾水面 = 湖面（洼地溢出高程），河水平顺没入湖中，不在湖岸留台阶
            outletSurf = lakeSurface;
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
        specs.add(new RiverSpec(nodes, surf, wid, dep, level, m >= 3, feeder));
        // ★ 湖不再由河"顺手创建"（2026-09-07）：湖是 build() 里用 priority-flood 从
        //   洼地提取的（含开口洼地），早已在 region 湖表里；这里只把河尾水面锚到湖面。
        return new CommitOut(smoothed, out.reachedOcean, acc, null, tailSurface);
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
                    // ★ 受害者优先级（2026-09-07）：源前细流 > 带 meander 的主河。
                    //   细流加入后，其在主河河头附近的口部（带满幅 meander 摆动）容易
                    //   与主河判成"真交叉"——按原逻辑受害者可能是【主河】，整条河被拉直
                    //   （河形 ±2.5 格漂移，实测种子 12345 源头裕度 2.3→-0.1、凸坡
                    //   11%→25%）。细流去 meander 则完全不可见（本就是归零淡出的小溪）。
                    //   两条细流相争仍取原逻辑（都不可见）。
                    int victim;
                    boolean fi = specs.get(i).feeder, fj = specs.get(j).feeder;
                    if (fi && !fj) victim = specs.get(i).meandered ? i : (specs.get(j).meandered ? j : -1);
                    else if (fj && !fi) victim = specs.get(j).meandered ? j : (specs.get(i).meandered ? i : -1);
                    else victim = specs.get(i).meandered ? i
                            : (specs.get(j).meandered ? j : -1);
                    if (victim < 0) continue;   // 两条都已直，交叉来自基路径/跨源，去 meander 无解
                    RiverSpec sp = specs.get(victim);
                    rivers.set(victim, smoothPath(sp.nodes, sp.surf, sp.wid, sp.dep,
                            sp.level, 0.0));
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
        /** 内陆终止的【具体成因】（诊断用；见 {@link #END_*} 常量）。 */
        static final int END_OTHER = 0;
        /** 自环：走到【已经走过的格】。 */
        static final int END_SELF_LOOP = 1;
        /** 自贴近守卫（2026-09-20 新增）拦下：贴近自己旧路径。 */
        static final int END_SELF_APPROACH = 2;
        /** 无下坡且【洼地/平地续流也找不到出口】。 */
        static final int END_NO_DOWN_NO_EXIT = 3;
        /** 真正入湖（该格有湖节点）。 */
        static final int END_LAKE_NODE = 4;

        final List<Integer> cells;
        final boolean reachedOcean;
        final boolean isLake;
        final boolean joined;     // 终止于汇入已接受河（树状汇流）
        final double[] accum;     // 每格汇流面积（wu²）；交接续流时含上游携带面积
        final boolean outlet;     // 终止于网格边（缝外 margin）→ 出口种子，交下游续流
        final int endReason;
        TraceOutcome(List<Integer> cells, boolean reachedOcean, boolean isLake,
                     boolean joined, double[] accum, boolean outlet) {
            this(cells, reachedOcean, isLake, joined, accum, outlet, END_OTHER);
        }
        TraceOutcome(List<Integer> cells, boolean reachedOcean, boolean isLake,
                     boolean joined, double[] accum, boolean outlet, int endReason) {
            this.cells = cells; this.reachedOcean = reachedOcean; this.isLake = isLake;
            this.joined = joined; this.accum = accum; this.outlet = outlet;
            this.endReason = endReason;
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
                                    int rx, int rz, double initialAccum, int[] lakeAt) {
        int cur = start;
        List<Integer> path = new ArrayList<>();
        List<Double> accumList = new ArrayList<>();
        boolean[] seen = new boolean[claimed.length];
        boolean reachedOcean = false, isLake = false, joined = false, outlet = false;
        int endReason = TraceOutcome.END_OTHER;
        while (true) {
            if (claimed[cur]) { pushCell(path, accumList, cur, field, initialAccum); joined = true; break; }   // 汇入已接受河
            if (seen[cur]) {                                     // 自环
                if (!nearRegionBorder(field, cur, rx, rz, params.borderDist())) {
                    isLake = true; endReason = TraceOutcome.END_SELF_LOOP;
                    pushCell(path, accumList, cur, field, initialAccum); break;
                }
                return null;
            }
            // ★ 终止点也必须过自贴近守卫（2026-09-20，实测残留 1 条闭环）：只查"下一步"不够——
            //   河可能恰好在【贴着自己旧路径】的那一格终止（无下坡/入洼地），尾节点于是绕回来了。
            if (selfApproach(seen, cur, nx, nz, path)) {
                isLake = true; endReason = TraceOutcome.END_SELF_APPROACH; break;
            }
            pushCell(path, accumList, cur, field, initialAccum);
            seen[cur] = true;
            if (field.eAt(cur) <= params.oceanE()) { reachedOcean = true; break; }
            // ★ 入湖即终止（2026-09-07）：河水流进洼地湖 → 河道到此为止（湖内不再开
            //   河槽，否则会出现"河槽切在湖底"）。湖满溢后由 build 的溢出续流河接走，
            //   水文不断。湖面 = 溢出口高程（commitRiver 用 lakeSurface 继承）。
            if (lakeAt != null && lakeAt[cur] >= 0) { isLake = true; break; }

            int join = nearbyDownhillNode(field, cur, stepSize, nodeE,  nx, nz, allSegments);
            if (join >= 0) { pushCell(path, accumList, join, field, initialAccum); joined = true; break; }   // 就近汇入树状

            int down = downhillNeighbor(field, cur, stepSize, nx, nz);
            // ★★★ 2026-09-20【填洼面单调守卫】—— 修"自环"（实测内陆终止 12 处里 7 处自环）★★★
            //
            //   【成因（几何证明）】洼地续流把河【从盆底跳到盆外溢出口】，而"出盆"那一步在
            //   原始 e 上是【抬升】的 ⇒ 路径不再单调 ⇒ 从溢出口看"最陡下降"恰好又指回盆内
            //   ⇒ 立刻走回去 ⇒ `seen` 命中 ⇒ 自环。（这条自环是【我 2026-09-20 的续流】引入的，
            //   不是既有缺陷 —— 35→12 里那 12 处中的 7 处。）
            //
            //   【正解】水在【填洼面】上只能单调不升（priority-flood 的语义：填洼后地形处处可流）。
            //   违背该不变量的候选一律否决，交给下方"填洼面续流"重新找正确出口。
            //
            //   ★★★ 2026-09-20【补丁退役】填洼优先开启时本守卫【必须失效】★★★
            //     它读的 fillOf 是【块空间湖填洼层】(filledAt/fillEAt)，而填洼优先后
            //     追踪器走的是【e 空间流向填洼面】—— 两层在侵蚀 delta 下并不严格同序，
            //     于是本守卫会【误否决合法下坡步】⇒ down=-1 ⇒ 直接落进下方终止分支
            //     ⇒ 填洼优先的效果被这条补丁吃掉（实测：河尾无水 5→7、内陆终止 5→5 未改善）。
            //     填洼优先自身的 epsilon 微坡已提供"沿程单调"保证 ⇒ 本守卫冗余。
            if (!fillFirstRouting
                    && down >= 0 && fillOf(field, down) > fillOf(field, cur) + 1e-9) down = -1;
            if (down < 0) {
                // 无下坡：下坡在邻 region（网格边 或 边界伪极小）→ 出口交下游续流；
                // 仅远离边界的真洼地才成湖（PL-RGA：tile 边界伪极小不应成湖，应流向下一瓦片）。
                if (field.touchesGridEdge(cur)
                        || nearRegionBorder(field, cur, rx, rz, params.borderDist())) {
                    outlet = true; break;
                }
                // ★★★ 2026-09-20 洼地续流 —— 修"河直接以河结束"（用户实机判据）★★★
                //
                //   【被修的缺陷】河走到洼地、D8 找不到下坡 ⇒ 原实现直接 `isLake = true` 终止；
                //   而该洼地若被 extractLakes 的 4 道过滤挡掉（太小/太浅/近边界/溢出坎低于海平面）
                //   ⇒ **没有湖节点** ⇒ commitRiver 落到最后的 else（outletSurf = junctionGround）
                //   ⇒ **河就地在陆地上结束**。实测（生成器自记 tailDiag，3×3 region）：
                //     内陆终止无湖 = 35/255（13.7%），其中 **洼地格 31（89%）**；
                //     且这些河尾外 15~62 block 常有【另一条河的同高程源头】⇒ 视觉上"河断了"。
                //
                //   【修法】把水沿【填洼面】引到该洼地的溢出口外侧，再从那里继续正常追踪。
                //   为什么物理正确：洼地里的水不会凭空消失，它涨到 spill 后必然从溢出口流走
                //   （这正是 priority-flood 填洼层的语义）。参考实现（RTF / worldgen-master）
                //   先填洼再算流向，所以它们的流向场里根本不存在"流不动"的格。
                // ★ 先试【填洼面最陡下降】：从任意"流不动"的格出发，沿填洼面（洼地内是水平面、
                //   洼外是真实地形）单调下降，直到踏出洼地 —— 那一步就是真正的溢出口。
                //   为什么这个才正确（几何证明）：`basinExitCell` 旧逻辑只搜【同一 spill 的连通区】，
                //   而【自环】所在平地/毛刺洼地的 spill 就等于它自身高度 ⇒ "同盆地内更低的格"
                //   不存在 ⇒ 找到的"出口"是它自己 ⇒ 无限同格循环。实测 12 处内陆终止里 7 处自环
                //   正是栽在这里（`basinExitCell` 返回自己 ⇒ `seen[exit]` ⇒ 又落回终止）。
                int exit = -1;
                if (basinReroute) {
                    exit = spillExitCell(field, cur, nx, nz, seen);
                    if (exit < 0) exit = basinExitCell(field, cur, nx, nz);
                }
                // ★ 跳转也必须过自贴近守卫与自环检查（实测：漏检时残留 1 条闭环）
                if (exit >= 0 && exit != cur && !seen[exit]
                        && !selfApproach(seen, exit, nx, nz, path)) {
                    cur = exit;
                    continue;
                }
                isLake = true;                                   // 真内流洼地（无溢出口）→ 成湖
                endReason = TraceOutcome.END_NO_DOWN_NO_EXIT;
                break;
            }
            int cri = cur % nx, crj = cur / nx, dri = down % nx, drj = down / nx;
            if (segmentCrossesAny(cri, crj, dri, drj, allSegments)) {
                if (claimed[down]) { pushCell(path, accumList, down, field, initialAccum); joined = true; break; }   // 交叉但可汇入
                return null;                                      // 交叉且无汇入 → 回滚
            }
            // ★★★ 2026-09-20 自贴近守卫 —— 修【马蹄形闭环】（用户实机截图）★★★
            //
            //   【被修的缺陷】河贴着"自己已经走过的河道"绕一整圈回来，与自身并排、首尾相接，
            //   围出一个大环（实测 5 条主河：沿程 1987 block / 直线仅 179 block，比 0.09；
            //   另有 523/88、581/48、1118/130 等）。观感是"河绕山一圈回来"，明显不合理。
            //
            //   【为何原判据拦不住】上方的 `seen[cur]` 只在【正好踩到】走过的格时终止；
            //   擦着【旁边一格】绕过去不算 ⇒ 环照样闭合。
            //
            //   【参考依据（用户提供的参考项目共同核心）】
            //     · PL-RGA：`_wouldCrossExistingSegments`（防自交）+ `_rollbackRiver`（不安全就整条回滚）；
            //     · FTF：`riverOverlaps(river, parent, rivers)`（250 单位线段相交排斥）；
            //     ⇒ "河道不得与自己/别的河道重叠贴近"是共同规则，本实现此前只对【别人】做
            //       （segmentCrossesAny），对【自己】漏了。
            //
            //   【修法】迈步之前，若目标格的 8 邻里存在【本河已走过的格】且它不在最近几步内
            //   （排除正常曲率），判定"贴近自己" ⇒ 就地终止（真内流/回水）。终止后若该格是
            //   洼地，由上方"洼地续流"从溢出口接走 ⇒ 既不绕圈、也不断河。
            if (selfApproach(seen, down, nx, nz, path)) { isLake = true; break; }
            cur = down;
        }
        if (path.size() < params.minRiverNodes()) return null;
        double[] accum = new double[accumList.size()];
        for (int k = 0; k < accum.length; k++) accum[k] = accumList.get(k);
        return new TraceOutcome(path, reachedOcean, isLake, joined, accum, outlet, endReason);
    }

    /** 汇合判定距离（wu）：河尾距它河节点的这个距离内即视为"汇入"（≈16 block）。 */
    private static final double CONFLUENCE_MERGE_WU = 8.0;

    /**
     * 汇合处【宽深单调】后处理（2026-09-20，SDF 平滑并集语义）—— 修"粗支流接进细下游"。
     *
     * <p>对每个"汇入它河"的河尾：取承接河自交汇节点起的全部节点，令其半宽/水深
     * {@code ≥ 支流尾端的值}（max = 硬并集；SDF 并集在任一点不小于任一子集）。</p>
     *
     * <p><b>为什么需要多遍</b>：承接河可能比它的支流【先提交】（rivers 顺序 = 源点 e 降序），
     * 一条河被抬高后，它自己作为支流又可能抬高更下游的河 ⇒ 需要向下游松弛传播。
     * 实测 3 遍即收敛（`runRiverEndProbe` 的"粗接细"计数 13 → 0）。</p>
     *
     * <p><b>只在本 region 内传播</b>：跨 region 的汇合由邻区自己的 build 负责
     * （同一套规则、各自纯函数 ⇒ 结果一致）。</p>
     */
    private void enforceConfluenceMonotonic(List<RiverPolyline> rivers) {
        final int passes = 3;
        for (int pass = 0; pass < passes; pass++) {
            boolean changed = false;
            for (RiverPolyline p : rivers) {
                int n = p.nodes.length;
                if (n < 2) continue;
                double tx = p.nodes[n - 1].x(), tz = p.nodes[n - 1].z();
                RiverPolyline recv = null;
                int recvIdx = -1;
                double bestD = Double.MAX_VALUE;
                for (RiverPolyline q : rivers) {
                    if (q == p) continue;
                    for (int k = 0; k < q.nodes.length; k++) {
                        double d = Math.hypot(q.nodes[k].x() - tx, q.nodes[k].z() - tz);
                        if (d < bestD) { bestD = d; recv = q; recvIdx = k; }
                    }
                }
                if (recv == null || bestD > CONFLUENCE_MERGE_WU) continue;
                // ★★ 汇合处【吸附】（2026-09-20，参考端点淡出的等价物）★★
                //
                //   参考实现（FTF / dynamicwaters）里【河 = 段序列，段端点天然就是汇合点】，
                //   而每个段端点都有蜿蜒淡出（getWarpAlpha lower/upper）⇒ 汇合点【从不被位移】。
                //   我们的架构是【一条河 = 一整条折线、汇合点落在任意节点】⇒ 承接河在汇合处
                //   仍有蜿蜒偏移（实测把"河尾→承接点"的距离从 6 块拉到 9 块）。
                //   ⇒ 等价修法：把支流尾节点【吸附到承接河该节点上】⇒ 汇合处距离恒为 0，
                //     水系视觉连续（不再"断 9 块"）。
                if (!p.nodes[n - 1].equals(recv.nodes[recvIdx])) {
                    p.nodes[n - 1] = new MidpointDisplacement.Node(
                            recv.nodes[recvIdx].x(), recv.nodes[recvIdx].z());
                    changed = true;
                }
                double wT = p.width[n - 1], dT = p.depth[n - 1];
                for (int k = recvIdx; k < recv.nodes.length; k++) {
                    if (recv.width[k] < wT) { recv.width[k] = wT; changed = true; }
                    if (recv.depth[k] < dT) { recv.depth[k] = dT; changed = true; }
                }
            }
            if (!changed) break;
        }
    }

    /**
     * 自贴近判据（2026-09-20）：目标格 {@code down} 的 8 邻里是否已有【本河自己走过的格】，
     * 且该格不在最近 {@code SELF_APPROACH_EXEMPT} 步内（排除正常曲率/紧密河曲）。
     *
     * <p>用于拦"河贴着自己绕一圈"（马蹄形闭环）。见 traceRiver 内的完整说明与参考依据。</p>
     */
    private static boolean selfApproach(boolean[] seen, int down, int nx, int nz,
                                        List<Integer> path) {
        int di = down % nx, dj = down / nx;
        // 半径 2 格（= 48wu = 96 block）：实测只查紧邻 8 格时，闭环仍剩约 3 条/窗口
        // （残留贴近是隔 1~2 格 ⇒ 1 格邻域覆盖不到）。半径 2 后应全部覆盖。
        for (int odj = -SELF_APPROACH_RADIUS; odj <= SELF_APPROACH_RADIUS; odj++) {
            for (int odi = -SELF_APPROACH_RADIUS; odi <= SELF_APPROACH_RADIUS; odi++) {
                if (odi == 0 && odj == 0) continue;
                int ni = di + odi, nj = dj + odj;
                if (ni < 0 || ni >= nx || nj < 0 || nj >= nz) continue;
                int nb = nj * nx + ni;
                if (!seen[nb]) continue;
                boolean recent = false;
                for (int k = Math.max(0, path.size() - SELF_APPROACH_EXEMPT); k < path.size(); k++) {
                    if (path.get(k) == nb) { recent = true; break; }
                }
                if (!recent) return true;
            }
        }
        return false;
    }

    /** 自贴近守卫的半径（格）：2 ⇒ 河道间净距 < 48wu(96 block) 即判"贴近自己"。 */
    private static final int SELF_APPROACH_RADIUS = 2;

    /**
     * 自贴近判据的"最近步数"豁免（格步）：正常曲率/紧密河曲不判违规。
     * 半径 2 时，2 格内的邻域会看到 2~3 步前的路径 ⇒ 豁免放到 6 步（≈3 格）。
     */
    private static final int SELF_APPROACH_EXEMPT = 6;

    /**
     * 是否用「有界粒子」给节点定位（2026-09-20）。true = 沿连续方向场推进、但钳在格心附近
     * ⇒ 去掉 45° 阶梯的同时不会绕圈。false = 节点钉死在格心（纯 D8 阶梯）。
     */
    private static final boolean PARTICLE_POS_ENABLED = true;

    /**
     * 蜿蜒的【次八度】振幅系数（2026-09-20）：细尺度域扭曲相对主尺度的振幅比。
     *
     * <p>取自参考 {@code dynamicwaters.MeanderingPath} 的多级中点二分：每级振幅约减半
     * （jitter 恒定、段长减半 ⇒ 位移减半）⇒ 这里用一个 0.35 的次八度近似"每个尺度都有细节"。</p>
     */
    private static final double MEANDER_FINE_FRAC = 0.35;

    /** 蜿蜒倍频层数（2026-09-20）：复刻参考 10 级二分的自相似——4 层足够覆盖 24~1.5wu 尺度。 */
    private static final int MEANDER_OCTAVES = 4;
    /** 每层的波长比（×本值）：0.5 = 尺度逐级减半（参考二分语义）。 */
    private static final double MEANDER_OCTAVE_SCALE = 0.5;
    /** 每层的振幅比（×本值）：0.5 = 振幅逐级减半（参考 jitter×段长 的段长减半语义）。 */
    private static final double MEANDER_OCTAVE_AMP = 0.5;

    /**
     * 有界粒子的最大偏移（× gridCell）：节点最多离开所属格中心这么远。
     * 0.45 ⇒ 相邻格节点间距 ≥ 0.1 格，且【数学上不可能】跑出去再绕回来。
     */
    private static final double PARTICLE_MAX_DEV_FRAC = 0.45;

    /** 8 邻方向（固定数组 ⇒ BFS 访问顺序确定 ⇒ 结果可复现）。 */
    private static final int[] DIR8_I = {1, -1, 0, 0, 1, 1, -1, -1};
    private static final int[] DIR8_J = {0, 0, 1, -1, 1, -1, 1, -1};

    /** 洼地续流 BFS 的访问上限（格）：防病态地形上的长循环。 */
    private static final int BASIN_BFS_MAX = 8192;

    /** 平地续流的"同高度"容差（e 单位）：只走不高于自己 + 本容差的格。 */
    private static final double FLAT_EPS = 1e-3;

    /**
     * ★ 2026-09-20【填洼面最陡下降续流】—— 修"河突然结束"的**通用**解（含自环）。
     *
     * <p><b>为什么需要它（几何证明）</b>：{@link #basinExitCell} 只搜【同一 spill 的连通区】，
     * 而典型的内陆终止点是【平地/毛刺洼地】—— 其 {@code filledAt} 就等于它自己的高度
     * （没有更低出口）⇒ "同盆地内更低的格"不存在 ⇒ 它返回的是【自己】⇒ 调用方
     * {@code seen[exit]} 判定为自环 ⇒ 又落回终止。实测 12 处内陆终止里 <b>7 处是自环</b>，
     * 全是这条路径。（用户："水流堆积后溢出会继续向下流动的"—— 这里是"堆积但找不到出口"。）</p>
     *
     * <p><b>做法</b>：从 cur 出发，在 8 邻中取 {@code filledAt}/{@code fillEAt} 最小者前进，
     * 直到出现"严格更低"的邻格 —— 该邻格即真正的出口（洼地内填洼面是水平面 ⇒ 先横着走，
     * 踏出盆地的那一步必然下降）。最多 {@code MAX} 步（防病态地形）。</p>
     *
     * <p>物理意义：这正是 priority-flood 给出的"水涨到 spill 后从最低缺口溢出"。</p>
     *
     * @return 溢出口外侧格下标；找不到（如整片区域都是同一平面）返回 -1
     */
    @Deprecated
    private int spillExitCell(FlowField field, int cur, int nx, int nz, boolean[] onPathSeen) {
        final double level = fillOf(field, cur);
        final int n = nx * nz;
        final java.util.BitSet vis = new java.util.BitSet(n);
        final int[] queue = new int[Math.min(n, BASIN_BFS_MAX)];
        int head = 0, tail = 0;
        queue[tail++] = cur;
        vis.set(cur);
        while (head < tail) {
            int c = queue[head++];
            int ci = c % nx, cj = c / nx;
            for (int d = 0; d < 8; d++) {
                int ni = ci + DIR8_I[d], nj = cj + DIR8_J[d];
                if (ni < 0 || ni >= nx || nj < 0 || nj >= nz) continue;
                int nb = nj * nx + ni;
                double f = fillOf(field, nb);
                if (f < level - 1e-9) {
                    // ★ 溢出口：填洼面上严格更低 ⇒ 水从这里流走（且不能是本河已走过的格）
                    boolean onPath = onPathSeen != null && onPathSeen[nb];
                    if (!onPath) return nb;
                    continue;
                }
                if (f > level + 1e-9 || vis.get(nb)) continue;    // 更高的格属于别的流域，不跨
                vis.set(nb);
                if (tail >= queue.length) continue;
                queue[tail++] = nb;
            }
        }
        return -1;
    }

    /** 该格的填洼面高程（{@code filledAt} 优先，回退 {@code fillEAt}，再回退真实 e）。 */
    private static double fillOf(FlowField field, int idx) {
        double v = field.filledAt(idx);
        if (!Double.isNaN(v)) return v;
        v = field.fillEAt(idx);
        return Double.isNaN(v) ? field.eAt(idx) : v;
    }

    /**
     * 洼地续流（2026-09-20）：从洼地内格 {@code cur} 出发，沿【填洼面】找最近的溢出口外侧格。
     *
     * <p><b>为什么需要</b>：见 {@code traceRiver} 无下坡分支的说明 —— 89% 的"内陆终止无湖"
     * 落在洼地格上，河水本应从溢出口流走，而不是就地结束。</p>
     *
     * <p><b>做法</b>：在【填洼面 ≤ 本洼地 spill】的洼地格上做 BFS（FIFO + 固定方向序 ⇒ 确定性），
     * 一旦发现某洼地格的 8 邻里有【非洼地且 fillE ≤ spill】者，该邻格即溢出口外侧，返回之。</p>
     *
     * <p><b>为什么用填洼面而不是原始 e</b>：priority-flood 填洼后，洼地内部被填成【水平面】
     * ⇒ 原始 e 在场内没有梯度（这正是原实现"找不到下坡"的根因）；溢出口信息只存在于
     * 填洼面/洼地掩膜里。</p>
     *
     * @return 溢出口外侧格的下标；真内流（无溢出口）或超出访问上限时返回 -1
     */
    @Deprecated
    private int basinExitCell(FlowField field, int cur, int nx, int nz) {
        // ★ 2026-09-20：自环型/平地型终止也必须能续流 —— 原实现只要不是"填洼层认定的洼地格"
        //   就立刻返回 -1（实测 12 处内陆终止里 7 处是自环、其中多数落在【非洼地】格上
        //   ⇒ 续流根本没接管）。现改为统一按"填洼面"判断：有 spill 就用它做天花板。
        double spill = field.filledAt(cur);
        if (Double.isNaN(spill)) spill = field.fillEAt(cur);
        if (Double.isNaN(spill)) return -1;
        final double e0 = field.eAt(cur);
        final boolean basin = true;   // 统一走"不高于 spill 的连通区 + 找更低出口"逻辑
        // 扩散上限：洼地格 ⇒ 不高于 spill（盆内填成水平面）；平地格 ⇒ 不高于自身 e + 极小容差
        final double ceiling = basin ? spill + 1e-9 : e0 + FLAT_EPS;
        int n = nx * nz;
        boolean[] vis = new boolean[n];
        int[] queue = new int[Math.min(n, BASIN_BFS_MAX)];
        int head = 0, tail = 0;
        queue[tail++] = cur;
        vis[cur] = true;
        while (head < tail) {
            int c = queue[head++];
            int ci = c % nx, cj = c / nx;
            // ① 先判"本格是否挨着出口"（优先返回最近出口 ⇒ 河道不外绕）
            for (int d = 0; d < 8; d++) {
                int ni = ci + DIR8_I[d], nj = cj + DIR8_J[d];
                if (ni < 0 || ni >= nx || nj < 0 || nj >= nz) continue;
                int nb = nj * nx + ni;
                if (field.eAt(nb) < e0 - params.minDrop()) return nb;   // ★ 严格更低 ⇒ 出口（平地/盆外通用）
                if (basin && !field.isBasinCell(nb) && field.fillEAt(nb) <= spill + 1e-9) {
                    return nb;                                         // 洼地：溢出口外侧（地形不高于 spill）
                }
            }
            // ② 再扩散到同一【洼地/平地】内的邻格
            for (int d = 0; d < 8; d++) {
                int ni = ci + DIR8_I[d], nj = cj + DIR8_J[d];
                if (ni < 0 || ni >= nx || nj < 0 || nj >= nz) continue;
                int nb = nj * nx + ni;
                if (vis[nb]) continue;
                // 统一口径（2026-09-20）：只走"填洼面不高于 spill"的连通格
                //   —— 洼地与"被填平的自环/平地"在填洼面上是同一件事（都是不高于 spill 的盆地）。
                if (field.fillEAt(nb) > ceiling) continue;
                if (tail >= queue.length) continue;                    // 上限保护
                vis[nb] = true;
                queue[tail++] = nb;
            }
        }
        return -1;
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
        // ★ 2026-09-20：与 nodeE 同一口径（nodeE 记的是 flowElevAt），否则"我是否比已有河低"
        //   会在两套面之间比较，得出错误结论。
        double curE = field.flowElevAt(cur);
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
        return troughCheck(field, idx, nx, nz, RiverLineParams.ForkParams.TROUGH_STRICT);
    }

    /**
     * 汇流槽判据的两种强度（唯一实现，按 mode 分派 —— 避免两份重复几何）。
     *
     * <ul>
     *   <li>{@link RiverLineParams.ForkParams#TROUGH_STRICT}（= 1）：<b>任一</b>侧更低 ⇒ 否决
     *       （原有语义，主河源头用：要求源头落在真正的汇流槽里）。</li>
     *   <li>{@link RiverLineParams.ForkParams#TROUGH_WEAK}（= 2）：<b>两侧都</b>更低才否决
     *       （= 只排除山脊/分水岭顶部）。</li>
     * </ul>
     *
     * <p>★ 2026-09-20 为何需要弱判据：M0 实测严格判据单独挡掉 51% 的分叉候选 —— 根因是
     * "未认领的上游格本来就是因为过不了严格判据才没被选作主河源头"，而<b>一阶支流在
     * 山坡上天然"一侧更低"（水正是从那侧汇下来的）</b>，那正是山坡支流的正常形态。
     * 真正会让用户看出"河槽切在坡面上"的是<b>山脊顶部</b>（水会向两侧同时散开）；
     * 弱判据精确地只排除这一类，保住 2026-09-01『源头应该在山谷中』要求的本意。</p>
     */
    private static boolean troughCheck(FlowField field, int idx, int nx, int nz, int mode) {
        int ci = idx % nx, cj = idx / nx;
        int di = 1, dj = 0;                       // 洼地：任取一横向
        int down = field.flowTo(idx);
        if (down >= 0) {
            di = (down % nx) - ci;
            dj = (down / nx) - cj;
        }
        int pi = -dj, pj = di;                    // 垂直于流向
        if (pi == 0 && pj == 0) return true;
        double e0 = field.eAt(idx);
        int lower = 0, checked = 0;
        for (int s = -1; s <= 1; s += 2) {
            int ni = ci + pi * s, nj = cj + pj * s;
            if (ni < 0 || ni >= nx || nj < 0 || nj >= nz) continue;   // 越界不否决
            checked++;
            if (field.eAt(nj * nx + ni) < e0) lower++;
        }
        if (mode == RiverLineParams.ForkParams.TROUGH_WEAK) {
            return !(lower == 2 && checked == 2);                     // 仅山脊顶部否决
        }
        return lower == 0;                                            // 严格：有一侧更低即否决
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
                                    List<RiverPolyline> rivers, List<RiverPolyline> extra) {
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
            if (!insideExistingValley(field, rivers, extra, cur)) return k;
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
    private int bestValleyHead(FlowField field, TraceOutcome out, int start,
                               List<RiverPolyline> rivers, List<RiverPolyline> extra) {
        int n = out.cells.size();
        // ★ 钳制：上游的汇流面积裁剪可能已把 start 推到 n（整条都被裁掉），
        //   此时无格可选，直接返回 n 交由调用方按"河太短"丢弃，不得越界取格。
        if (start < 0 || start >= n) return n;
        int last = Math.min(n - 1, Math.max(start, n - params.minRiverNodes()));
        // ★ 兜底路径也应躲开【邻河的谷壁】：advanceToValleyHead 那条带
        //   insideExistingValley 守卫，而本方法原先只按横向裕度挑格、完全不看谷壁，
        //   逻辑上是条漏网路径。优先只在"不在邻河谷壁内"的格里取裕度最大者；若全程
        //   都躲不开才退回原逻辑（保河网密度，宁要一条位置欠佳的河，不丢整条）。
        //   ★ 如实说明：本改动【并非】用户"源头有概率生成在其他河流中"的修复——
        //     A/B 实测该 2 例（seed 28183 / 107373）是【跨 region】的，而本方法只在
        //     本 region 内选格、且这条兜底路径本身极少触发，改前改后数字一字不变。
        //     真正成因见 SourceValleyProbe 的 rr 参数说明。
        int bestClean = -1;
        double bestCleanMargin = Double.NEGATIVE_INFINITY;
        int bestAny = start;
        double bestAnyMargin = Double.NEGATIVE_INFINITY;
        for (int k = start; k <= last; k++) {
            double m = valleyMargin(field, out, k);
            if (m > bestAnyMargin) { bestAnyMargin = m; bestAny = k; }
            if (!insideExistingValley(field, rivers, extra, out.cells.get(k))
                    && m > bestCleanMargin) { bestCleanMargin = m; bestClean = k; }
        }
        return bestClean >= 0 ? bestClean : bestAny;
    }

    // ===== 洼地湖（2026-09-07）=====

    /**
     * 成湖最小格数。<b>★ 2026-09-25 起取 1 = 取消该门槛</b>。
     *
     * <p><b>用户定义（铁律）</b>：<i>"湖泊的定义就是『洼地蓄水』，不存在 2 个物种"</i>
     * ⇒ 不得用"面积太小"去否定"这里有水"。原值 2（注释自标 {@code TEMP 产量曲线}）
     * 与 {@link TerrainFlowSim} 侧的 300 格门槛，正是"两个物种"的实体：
     * 同一次「填洼面 + 连通域」被拆成两套口径、再在 {@code skeletonRouteOnEroded}
     * 里用交集缝合。</p>
     *
     * <p><b>回退</b>：改回 {@code 2}（并同步 {@link #LAKE_MIN_DEPTH} 与
     * {@link TerrainFlowSim#LAKE_MIN_DEPTH}/{@link TerrainFlowSim#LAKE_MIN_AREA}）。</p>
     */
    private static final int LAKE_MIN_CELLS = 1;

    /** 成湖最小水深（block）。<b>★ 2026-09-25 起取 0.0 = 取消该门槛</b>（同见 {@link #LAKE_MIN_CELLS}）。 */
    private static final double LAKE_MIN_DEPTH = 0.0;

    /**
     * 从填洼层提取湖：连通标记"被水填起"的格 → 门槛过滤 → 生成 LakeNode。
     *
     * @param field 已 {@link FlowField#computeFill} 的流场
     * @param lakes 输出：本 region 的湖表（调用方持有）
     * @param outCells 输出：每个湖的【溢出口外邻格】（-1 = 无出口，真内流）
     * @return cell → 湖序号（-1 = 非湖格）；供河 trace 判断是否入湖
     */
    /**
     * 洼地格判定（湖提取用）：填洼优先开启时与河面同一张面（e 空间填洼面），
     * 否则退回块空间湖填洼层（旧行为，逐位一致）。
     */
    private boolean isBasinCellForLake(FlowField field, int idx) {
        // ★★★ 2026-09-22【湖域 = 纯地形等高线，与水流无关】（见 lakeDomainTerrainOnly 注释）★★★
        //   ⚠ 切勿再用 routingFillAt：它带 ε 微坡 + 流向语义 ⇒ 湖轮廓被河流影响
        //     （用户实测：「湖泊区域明显被河流等影响了」）。
        if (!lakeDomainTerrainOnly && fillFirstRouting) {
            double f = field.routingFillAt(idx);
            return !Double.isNaN(f) && f > field.eAt(idx) + 1e-4;
        }
        return field.isBasinCell(idx);
    }

    /**
     * ★★★ 2026-09-21【T1.2 骨架 → 折线】★★★
     *
     * <p>把块分辨率流体骨架（{@link TerrainFlowSim}）转成本网络消费的
     * {@link RiverPolyline}：节点（wu）、逐节点水面（当地地形 − surfaceSink）、
     * 半宽/河深（√汇流面积，Leopold-Maddock 同源公式）、层级 1。</p>
     *
     * <p><b>口径</b>：模拟在图幅（region + margin）上以 {@code SKELETON_CELL_BLOCKS}
     * 为格距跑填洼/流向/累积；水位取当地真实地形（{@code groundYAt}）减 surfaceSink，
     * 与旧链路同一套"水面锚定地形"语义 ⇒ 雕刻层可零改动消费。</p>
     *
     * <p><b>返回空表表示该区域无河</b>（调用处据此走旧链路兜底）。</p>
     */
    /** 骨架构建产物：折线 + 跨区出口种子（无限世界交接用）。 */
    private static final class SkelOut {
        final List<RiverPolyline> rivers = new ArrayList<>();
        final List<RiverLineRegion.OutletSeed> outlets = new ArrayList<>();
        /**
         * ★ 2026-09-24【湖泊=洼地 统一】骨架模拟发现、而生产湖提取未覆盖的深盆
         * ⇒ 合成的 LakeNode（水面=溢口高程、轮廓=盆地格）。由调用方并入 region 湖表
         * ⇒ 渲染/雕刻/掩码与骨架共用同一套"洼地=湖泊"定义（用户判据）。
         */
        final List<RiverLineRegion.LakeNode> newLakes = new ArrayList<>();
    }

    /**
     * ★ 2026-09-21【无限世界交接】—— 修"新水文像有限地图"：
     *   ① 折线裁剪到 region 盒 + slack（不再把 margin 里的邻区河重复画一遍）；
     *   ② 被裁剪/出窗的河尾 ⇒ 注册 {@link RiverLineRegion.OutletSeed}（邻区接续）；
     *   ③ 接收邻区种子（pass-2 的 incoming）⇒ 从种子处沿本地 D8 继续追 ⇒ 跨缝连续。
     */
    private SkelOut buildSkeletonRivers(int rx, int rz, boolean handoff,
                                        List<RiverLineRegion.OutletSeed> incoming,
                                        List<RiverLineRegion.LakeNode> lakes) {
        SkelOut so = new SkelOut();
        long tSkel0 = System.nanoTime();
        double regionW = params.regionSize();
        double hs = horizontalScale;
        // region 盒（块）
        double bx0 = rx * regionW * hs, bz0 = rz * regionW * hs;
        int cellB = SKELETON_CELL_BLOCKS;
        // margin 外扩（块）→ 网格数
        int oxB = (int) Math.floor(bx0) - SKELETON_MARGIN_BLOCKS;
        int ozB = (int) Math.floor(bz0) - SKELETON_MARGIN_BLOCKS;
        int gridN = (int) Math.ceil(regionW * hs / cellB) + 2 * (SKELETON_MARGIN_BLOCKS / cellB);
        if (gridN < 8) return so;
        // ★★★ 2026-09-21【成河阈值必须按【模拟格】标定，不能挪用旧口径】★★★
        //   ⚠ 事故记录（勿重犯）：曾直接借用 `params.riverAccumThreshold()`（旧 FlowField
        //     口径，格距 24wu=48 块）⇒ 在 96² 的模拟网格上要求 2304 格 = 窗口的 25%
        //     ⇒ **一条河都建不出来**（audit 实测生产侧河数 = 0 / 节点 = 0）。
        //     门禁没拦住，是因为它不检查"有没有河"。
        //   正解：与审计框架同一标定 —— 集水面积占窗口 ~0.3% 即成河（可目视成网）。
        int thr = Math.max(4, (int) Math.round(gridN * (double) gridN * 0.003));
        // ★ 2026-09-23【路由地形口径】：skeletonRouteOnEroded ⇒ 一律走侵蚀后地形
        //   （优先于 edp 的"peek delta"路径：peek 在 tile 未缓存时退化为侵蚀前，
        //   会把两套河谷网混在同一次路由里；本开关要求"要么全侵蚀后、要么全侵蚀前"）。
        //   ⚠ 必须声明在 try 之外 —— 下方的 terr[k]（逐节点地形）也要用同一口径。
        // ★★★ 2026-09-24【routeTerrain 坐标级缓存 —— 性能】★★★
        //   【实测】eroded 模式每 region 40~90 秒（旧口径 groundYAt 仅 0.5 秒）。
        //   采样量 = 角点 (gridN+1)²≈148k + 自适应陡格 9 次/格 ⇒ 约 80 万次/region；
        //   而每次 `sampleWu` 含"9 邻 tile blend"⇒ 邻域坐标被【反复重算】。
        //   `groundYAt` 早就有坐标级缓存（其注释："同一坐标只算一次"），
        //   本采样器却无 ⇒ 本处补上（同款 floor(wx)/floor(wz) 键、纯函数等价）。
        //   预期：重复坐标命中缓存 ⇒ 采样量降数倍。
        //   回退：直接赋 erodedYSampler（去掉包装）。
        //   ★ 2026-09-24 再优化：路由只需【高度】⇒ 优先用 routeHeightSampler
        //   （= CellGenerator.erodedHeightForRouting，跳过 discharge/重分类/坡度的 4 次
        //   tile 采样；与 sampleWu().height 数值链相同，探针侧有 maxErr 自检）。
        final java.util.function.ToDoubleBiFunction<Double, Double> routeRaw =
                (skeletonRouteOnEroded && routeHeightSampler != null) ? routeHeightSampler
                : (skeletonRouteOnEroded && erodedYSampler != null) ? erodedYSampler : null;
        final java.util.concurrent.ConcurrentHashMap<Long, Double> routeCache =
                routeRaw == null ? null : new java.util.concurrent.ConcurrentHashMap<>();
        final java.util.function.ToDoubleBiFunction<Double, Double> routeTerrain =
                routeRaw == null ? null : (a, b) -> {
                    long key = ((long) Math.floor(a) << 32) ^ ((long) Math.floor(b) & 0xFFFFFFFFL);
                    Double hit = routeCache.get(key);
                    if (hit != null) return hit;
                    double v = routeRaw.applyAsDouble(a, b);
                    routeCache.put(key, v);
                    return v;
                };
        TerrainFlowSim.Result res;
        try {
            // ⚠⚠ 2026-09-21【性能事故·勿重犯】⚠⚠
            //   simulate 的 `n` 是【格数】不是块数。曾误传 `gridN * cellB`（=1536）
            //   ⇒ 逐块开 1536² = 236 万格 + 236 万次采样 ⇒ 门禁 7 分钟不返回（卡死）。
            //   正解：传【格数 gridN】+【格距 cellB】重载。
            // ★★★ 2026-09-22【谷底取小采样 · 角点预采样版】★★★
            //   【要保留的效果】每格取【谷底】而不是格心 ⇒ 河贴谷走，不翻山脊。
            //   【必须避免的代价】曾写成"每格 4 角 + 中心"逐格采样 ⇒ 采样量 ×5
            //   （groundYAt ≈10µs ⇒ 每 region ~10s × 9 region ≈ 80s；实测游戏内
            //   hydro=57~106s/chunk，用户："等了好几分钟都没进新世界"）。
            //   正解：把角点【预采样一次】(gridN+1)² ≈ 37k 次，每格高度 = 自身 4 角最小值
            //   （角点被相邻格共享）⇒ 贴谷效果不变、采样量降回 1/5 量级。
            final int cn = gridN + 1;
            final double[] cornerH = new double[cn * cn];
            final java.util.function.ToDoubleBiFunction<Double, Double> edp =
                    erosionAwareRouting ? erosionDeltaProvider : null;
            for (int gj = 0; gj < cn; gj++) {
                for (int gi = 0; gi < cn; gi++) {
                    double wx = (oxB + gi * (double) cellB) / hs;
                    double wz = (ozB + gj * (double) cellB) / hs;
                    double hh;
                    if (routeTerrain != null) {
                        hh = routeTerrain.applyAsDouble(wx, wz);       // ★ 侵蚀后地形（整场一致）
                    } else if (edp != null) {
                        // ★ 侵蚀后地形（仅在 tile 已缓存时可得；NaN ⇒ 退回侵蚀前）：
                        //   heightFromE(e + Δe) 才是玩家看到的谷地 ⇒ 河贴【可见沟壑】走。
                        double de = edp.applyAsDouble(wx, wz);
                        if (Double.isNaN(de)) {
                            hh = groundYAt(wx, wz);
                        } else {
                            hh = curve.heightFromE(eSampler.eAt(wx, wz) + de);
                        }
                    } else {
                        hh = groundYAt(wx, wz);
                    }
                    cornerH[gj * cn + gi] = hh;
                }
            }
            // ★★★ 2026-09-24【自适应采样：起伏大的格加密找沟底 —— 用户方案】★★★
            //   用户原话："我不是提过骨架精度可以尝试自适应吗？小距离内地形变化大就
            //   提高精度，平缓可以适当下降精度。"
            //   【为什么必要】格内只取【4 角最小值】⇒ 4 块格（cellB=4）分辨不出宽 1~2 块的
            //   沟谷 ⇒ 路径横切坡面（实测一条 40 块路径沿 45° 对角链爬升 19.5 块，
            //   而相邻沟底就在数块之内）。
            //   【做法】格内 4 角高程差 = 局部起伏代理：
            //     · 差 ≤ ADAPT_TOL（平缓）⇒ 保持 4 角最小值（零额外采样）；
            //     · 差 >  ADAPT_TOL（陡）⇒ 在该格子网格（3×3 再 5×5 两级）内找【最低点】，
            //       把谷底"挖出来" ⇒ 路由沿沟走。
            //   【确定性】子网格采样位置是格坐标的纯函数 ⇒ 与触发顺序无关；
            //     采样器仍是 routeTerrain（侵蚀后、tile 已由探针预热）。
            //   【成本】仅陡格付 9~25 次采样；实测陡格占比约三成，总采样量约 ×3（探针可接受）。
            //   回退：把 ADAPT_TOL 设为极大（如 1e9）⇒ 全部走"4 角最小值"，逐位回到旧行为。
            final TerrainFlowSim.HeightSampler cellSampler =
                    (bxx, bzz) -> {
                        int gi = (int) ((bxx - oxB) / cellB);
                        int gj = (int) ((bzz - ozB) / cellB);
                        if (gi < 0) gi = 0; else if (gi >= cn) gi = cn - 1;
                        if (gj < 0) gj = 0; else if (gj >= cn) gj = cn - 1;
                        int gi1 = Math.min(cn - 1, gi + 1), gj1 = Math.min(cn - 1, gj + 1);
                        double a = cornerH[gj * cn + gi], b = cornerH[gj * cn + gi1];
                        double c = cornerH[gj1 * cn + gi], d = cornerH[gj1 * cn + gi1];
                        double mn = Math.min(Math.min(a, b), Math.min(c, d));
                        double mx = Math.max(Math.max(a, b), Math.max(c, d));
                        if (mx - mn <= ADAPT_TOL) return mn;
                        // 陡格：格内子网格找最低点（3×3 覆盖 4 块格 = 1.33 块间隔）
                        // ★ 2026-09-24【5×5 → 3×3】：5×5=25 次采样/陡格，实测把单 region
                        //   角点采样推到数百万次（24~101s/region）⇒ 降为 3×3=9 次，
                        //   保留"找沟底"能力（1.33 块间隔足以分辨宽 1~2 块的沟）。
                        double lo = mn;
                        for (int sz2 = 0; sz2 <= 2; sz2++) {
                            for (int sx2 = 0; sx2 <= 2; sx2++) {
                                double sx3 = oxB + (gi + sx2 * 0.5) * (double) cellB;
                                double sz3 = ozB + (gj + sz2 * 0.5) * (double) cellB;
                                double hh = routeTerrain != null
                                        ? routeTerrain.applyAsDouble(sx3 / hs, sz3 / hs)
                                        : groundYAt(sx3 / hs, sz3 / hs);
                                if (hh < lo) lo = hh;
                            }
                        }
                        return lo;
                    };
            res = TerrainFlowSim.simulate(cellSampler, oxB, ozB, gridN, cellB,
                    curve.seaLevelY(), thr);
        } catch (RuntimeException ex) {
            // ★ 2026-09-21：必须记日志 —— 否则"骨架失败静默回退旧链路"会被误认为
            //   "开关没生效/还在跑旧版"（用户实测困惑点）。只打前几次，避免刷屏。
            if (skelLogCount.getAndIncrement() < 3) {
                LOGGER.warn("[RIVER] skeleton routing FAILED for region ({},{}) ⇒ fallback to legacy: {}",
                        rx, rz, ex.toString());
            }
            return so;
        }
        double cellW = cellB / hs;                  // 格宽（wu）
        double cellAreaWu = cellW * cellW;          // 格面积（wu²）
        // ===== ★★★ 2026-09-22【生产湖为唯一真相源】（修"一点改进都没有"）★★★ =====
        //
        //   【被修的缺陷】模拟的湖判定（{@code fill − h} 连通域过门槛）与生产湖
        //   （extractLakes：深度/面积/归属/边界过滤）【不是同一集合】，且模拟湖更大：
        //     · 模拟湖格 ⇒ extractChannelSkeletons 里 channel=false ⇒ 追踪在此【停】
        //       ⇒ 河在生产【干地】上凭空断掉（用户实测"断流"）；
        //     · 我的湖段标记/入湖对齐又前置了 `res.lake[]`（模拟湖）⇒ 判据几乎不落在
        //       真正的生产湖上 ⇒ **改动看似生效、实际从未触发**（用户实测"一点改进都没有"）。
        //   【正解】把 res.lake 修正为【生产湖掩码】：非生产湖的"模拟湖格"恢复为普通格，
        //   并按同一掩码【重建 channel】⇒ 河道穿过那些干地、只在真湖岸终止。
        // ★ 2026-09-23【R-C1 F1】probLake 改用【生产块级连通掩码】(inBasinMask)：
        //   旧=轮廓 bbox 超集 ⇒ 干岸格入域 ⇒ 骨架干节点 29.3%（样例 inFlood=false×2、
        //   0.46 浅滩×1）。预筛窗=bbox+80wu（掩码 ⊆ flood 窗 bbox+72wu）。
        //   回退：lakeBasinHits=false 或 erodedYSampler 未接线 ⇒ 旧 bbox 路径逐位保留。
        boolean[] probLake = new boolean[gridN * gridN];
        // ★ F1i：纯连通掩码（无 0.5 深度门）—— 只给【入湖锚点步进 / 尾抬升】用：
        //   尾要能先走进浅水（0.05~0.5），浅滩格由河分支雕刻出水；probLake（深度门版）
        //   继续管"归属/剥河/湖命中"（浅滩归河，防死区）。无轮廓/旧路径与 probLake 同值。
        boolean[] floodAny = new boolean[gridN * gridN];
        boolean maskMode = lakeBasinHits && erodedYSampler != null;
        if (lakes != null) {
            for (RiverLineRegion.LakeNode ln : lakes) {
                if (ln.hasOutline() && maskMode) {
                    double loX = Double.MAX_VALUE, hiX = -Double.MAX_VALUE;
                    double loZ = Double.MAX_VALUE, hiZ = -Double.MAX_VALUE;
                    for (int ci = 0; ci < ln.cellX.length; ci++) {
                        loX = Math.min(loX, ln.cellX[ci] - ln.cellHalf);
                        hiX = Math.max(hiX, ln.cellX[ci] + ln.cellHalf);
                        loZ = Math.min(loZ, ln.cellZ[ci] - ln.cellHalf);
                        hiZ = Math.max(hiZ, ln.cellZ[ci] + ln.cellHalf);
                    }
                    double marginB = 80.0 * hs;
                    int giA = (int) Math.floor((loX * hs - marginB - oxB) / cellB);
                    int giB = (int) Math.floor((hiX * hs + marginB - oxB) / cellB);
                    int gjA = (int) Math.floor((loZ * hs - marginB - ozB) / cellB);
                    int gjB = (int) Math.floor((hiZ * hs + marginB - ozB) / cellB);
                    for (int gj = Math.max(0, gjA); gj <= Math.min(gridN - 1, gjB); gj++) {
                        for (int gi = Math.max(0, giA); gi <= Math.min(gridN - 1, giB); gi++) {
                            double wx = (oxB + gi * (double) cellB + cellB * 0.5) / hs;
                            double wz = (ozB + gj * (double) cellB + cellB * 0.5) / hs;
                            if (inBasinMask(ln, wx, wz)) probLake[gj * gridN + gi] = true;
                            double lvA = finalLakeLevel(ln);
                            if (!Double.isNaN(lvA) && ln.inBasinFlood(erodedYSampler, lvA,
                                    lakeBasinFloodGrid, params.gridCell(), wx, wz)) {
                                floodAny[gj * gridN + gi] = true;
                            }
                        }
                    }
                } else if (ln.hasOutline()) {
                    double halfW = Math.max(ln.cellHalf, cellB * 0.5 / hs);
                    for (int ci = 0; ci < ln.cellX.length; ci++) {
                        double bxs = ln.cellX[ci] * hs, bzs = ln.cellZ[ci] * hs;
                        int giA2 = (int) Math.floor((bxs - halfW * hs - oxB) / cellB);
                        int giB2 = (int) Math.floor((bxs + halfW * hs - oxB) / cellB);
                        int gjA2 = (int) Math.floor((bzs - halfW * hs - ozB) / cellB);
                        int gjB2 = (int) Math.floor((bzs + halfW * hs - ozB) / cellB);
                        for (int gj = Math.max(0, gjA2); gj <= Math.min(gridN - 1, gjB2); gj++) {
                            for (int gi = Math.max(0, giA2); gi <= Math.min(gridN - 1, giB2); gi++) {
                                probLake[gj * gridN + gi] = true;
                            }
                        }
                    }
                } else {
                    int rr = (int) Math.ceil(ln.radius * hs / cellB) + 1;
                    int gc = (int) Math.round((ln.x * hs - oxB) / cellB);
                    int gr = (int) Math.round((ln.z * hs - ozB) / cellB);
                    for (int gj = Math.max(0, gr - rr); gj <= Math.min(gridN - 1, gr + rr); gj++) {
                        for (int gi = Math.max(0, gc - rr); gi <= Math.min(gridN - 1, gc + rr); gi++) {
                            double ddx = (oxB + gi * (double) cellB + cellB * 0.5) / hs - ln.x;
                            double ddz = (ozB + gj * (double) cellB + cellB * 0.5) / hs - ln.z;
                            if (ddx * ddx + ddz * ddz <= ln.radius * ln.radius) {
                                probLake[gj * gridN + gi] = true;
                                floodAny[gj * gridN + gi] = true;   // 无轮廓湖：连通=圆盘
                            }
                        }
                    }
                }
            }
            if (!maskMode) {
                // 旧 bbox 路径（回退）：两掩码同值 ⇒ 行为与 F1i 之前逐位一致
                System.arraycopy(probLake, 0, floodAny, 0, probLake.length);
            }
            }
            // ★ 洼地合成湖的本地合并表：后续"节点归属 / 尾锚找湖"用它查询
        //   （新湖在本 region 的雕刻与骨架里与生产湖同权；region 湖表由调用方合并）
        final List<RiverLineRegion.LakeNode> lakesAll = new java.util.ArrayList<>(
                lakes == null ? java.util.List.of() : lakes);
        // ★★★ 2026-09-24【湖泊=洼地 统一 —— 用户判据"湖泊就是洼地，明明就都是一个东西"】★★★
            //   【被修的缺陷】系统里存在两套"湖"：生产 LakeNode（旧链路提取，含边界/深度/
            //   面积过滤）与骨架模拟盆地（TerrainFlowSim 的洼地拓扑）。本方法原句
            //   `res.lake && !probLake ⇒ 清成陆地` 把两者强行对齐到生产侧 ⇒ 凡生产湖
            //   提取漏掉的深盆（如边界伪极小过滤误杀的大洼地）⇒ 湖消失 + 盆地被当陆地
            //   穿行 ⇒ 用户截图红圈：巨大的洼地里既无湖也无河。
            //   【正解（一套定义）】湖泊 ⟺ 洼地蓄水。凡模拟判定为深盆（res.lake 有格）
            //   而生产湖掩码未覆盖的盆地 ⇒ 【合成 LakeNode】（水面=溢口高程 spillHeight、
            //   轮廓=盆地格、形心=盆地平均），并保留其湖格 ⇒ 河在湖岸终止（BASIN_ENTRY）、
            //   溢口续流（spillDown）接走下游、雕刻/渲染走湖分支（不挖地、铺水面）。
            //   三套消费（渲染蓝面 / probLake 掩码 / 骨架拓扑）从此同源。
            //   【确定性】全部为 res 数组的纯函数；湖序 = basinId 升序。
            //   回退：删除本块 + 恢复下方"清成陆地"循环（或 TerrainFlowSim.HYDRO_LIFECYCLE=false）。
            {
                int nB = res.basinSpillHeight.length;
                if (nB > 0) {
                    int[] bCells = new int[nB];
                    int[] bLakeCells = new int[nB];
                    double[] bMinH = new double[nB];
                    double[] bLakeMinH = new double[nB];
                    double[] bSx = new double[nB], bSz = new double[nB];
                    double[] bLakeSx = new double[nB], bLakeSz = new double[nB];
                    java.util.Arrays.fill(bMinH, Double.POSITIVE_INFINITY);
                    java.util.Arrays.fill(bLakeMinH, Double.POSITIVE_INFINITY);
                    boolean[] bHasLake = new boolean[nB];
                    boolean[] bTouchesWindow = new boolean[nB];
                    boolean[] bHasProb = new boolean[nB];
                    for (int k = 0; k < gridN * gridN; k++) {
                        int b = res.basinId[k];
                        if (b < 0) continue;
                        bCells[b]++;
                        if (res.height[k] < bMinH[b]) bMinH[b] = res.height[k];
                        bSx[b] += oxB + (k % gridN) * (double) cellB + cellB * 0.5;
                        bSz[b] += ozB + (k / gridN) * (double) cellB + cellB * 0.5;
                        if (res.lake[k]) {
                            bHasLake[b] = true;
                            int ki = k % gridN, kj = k / gridN;
                            if (ki == 0 || kj == 0 || ki == gridN - 1 || kj == gridN - 1) {
                                bTouchesWindow[b] = true;
                            }
                            bLakeCells[b]++;
                            if (res.height[k] < bLakeMinH[b]) bLakeMinH[b] = res.height[k];
                            bLakeSx[b] += oxB + (k % gridN) * (double) cellB + cellB * 0.5;
                            bLakeSz[b] += ozB + (k / gridN) * (double) cellB + cellB * 0.5;
                        }
                        if (probLake[k]) bHasProb[b] = true;
                    }
                    int synthesized = 0;
                    for (int b = 0; b < nB; b++) {
                        if (!bHasLake[b] || bHasProb[b] || bCells[b] == 0 || bTouchesWindow[b]) continue;
                        double spill = res.basinSpillHeight[b];
                        // 湖的面积、深度、形心、轮廓全部以 res.lake 为准；盆地只是
                        // 溢口拓扑容器，不能把其中的非湖格一起铺水。
                        double depth = spill - bLakeMinH[b];
                        if (bLakeCells[b] < TerrainFlowSim.LAKE_MIN_AREA
                                || depth < TerrainFlowSim.LAKE_MIN_DEPTH) continue;
                        double cxWu = (bLakeSx[b] / bLakeCells[b]) / hs;
                        double czWu = (bLakeSz[b] / bLakeCells[b]) / hs;
                        // 只允许湖心所属 region 创建 LakeNode，避免邻区 margin 对同一湖
                        // 分别创建局部副本，最终在 region 边界形成竖直切边。
                        double regionLoX = rx * regionW, regionHiX = regionLoX + regionW;
                        double regionLoZ = rz * regionW, regionHiZ = regionLoZ + regionW;
                        if (cxWu < regionLoX || cxWu >= regionHiX
                                || czWu < regionLoZ || czWu >= regionHiZ) continue;
                        double radiusWu = Math.sqrt(bLakeCells[b] * (double) cellB * cellB / Math.PI) / hs;
                        // 轮廓 = 实际湖格中心（wu）；cellHalf = 半格（wu）
                        java.util.ArrayList<double[]> outline = new java.util.ArrayList<>();
                        for (int k = 0; k < gridN * gridN; k++) {
                            if (res.basinId[k] != b || !res.lake[k]) continue;
                            outline.add(new double[]{
                                    (oxB + (k % gridN) * (double) cellB + cellB * 0.5) / hs,
                                    (ozB + (k / gridN) * (double) cellB + cellB * 0.5) / hs});
                            probLake[k] = true;                 // 仅实际湖格进入湖掩码
                            floodAny[k] = true;                 // 入湖锚点延伸仅到实际湖域
                        }
                        double[] ox2 = new double[outline.size()];
                        double[] oz2 = new double[outline.size()];
                        for (int i = 0; i < outline.size(); i++) {
                            ox2[i] = outline.get(i)[0];
                            oz2[i] = outline.get(i)[1];
                        }
                        so.newLakes.add(new RiverLineRegion.LakeNode(cxWu, czWu, spill,
                                radiusWu, depth, ox2, oz2, cellB * 0.5 / hs,
                                null, null, true));
                        synthesized++;
                    }
                    if (synthesized > 0 && skelLogCount.get() < 40) {
                        LOGGER.info("[RIVER] skeleton region ({},{}): 洼地成湖 {} 个（生产湖提取遗漏 ⇒ 由模拟洼地合成，湖泊=洼地统一）",
                                rx, rz, synthesized);
                    }
                    lakesAll.addAll(so.newLakes);
                }
            }
        // ★★★ 2026-09-25【删除"两套湖"的缝合 —— 用户定义：湖泊 = 洼地蓄水，不存在 2 个物种】★★★
        //   【被删的缺陷】此处原为
        //       if (res.lake[k] && !probLake[k]) { res.lake[k] = false; lakeFixed++; }
        //   即把【模拟判定的湖】强行裁到【生产湖 LakeNode 掩码】上（交集）。二者分辨率不同
        //   （模拟格 cellB=4 块；生产湖轮廓来自 FlowField 格），且生产掩码另带"湖心须属本
        //   region"的归属裁剪 ⇒ 细齿的模拟湖被粗齿的生产掩码啃掉。
        //
        //   【实测后果 —— 这就是"漏洞"本身，不是理论担忧】
        //     把 LAKE_MIN_* 门槛归零（= 湖泊只由洼地蓄水决定）后，湖面反而【净减 9.9%】：
        //     45961 → 41407 像素（新增 4097 / 消失 7729，seed 9139912035078620160, r=400）
        //     ⇒ "取消门槛"的收益被这一步整个缝了回去。
        //     日志直接给出量级：`模拟湖格修正` = 0 / 106 / 2910 格（单 region）。
        //
        //   【正解】模拟的 `res.lake` 就是唯一定义（`fill − h > 0.5` 的连通分量 = 洼地蓄水）；
        //   生产 LakeNode 从此只负责【提供渲染/雕刻用的轮廓几何】，不再反向删湖。
        //   回退：恢复上面那三行交集循环即可（`lakeFixed` 计数与它一起恢复）。
        if (probLake.length > 0) {
            for (int k = 0; k < gridN * gridN; k++) {
                res.channel[k] = !res.lake[k]
                        && res.accum[k] >= res.chanThreshold
                        && res.height[k] > res.seaLevel;
            }
        }
        if (skelLogCount.getAndIncrement() < 3) {
            LOGGER.info("[RIVER] skeleton region ({},{}): 湖掩码=模拟洼地蓄水（不再被生产湖掩码裁剪）",
                    rx, rz);
        }
        boolean[] used = new boolean[gridN * gridN];
        List<int[]> paths = new ArrayList<>();
        for (int[] p : TerrainFlowSim.extractChannelSkeletons(res)) {
            if (p.length >= 2) {
                for (int c : p) used[c] = true;
                paths.add(p);
            }
        }
        // ★★★ 2026-09-24【水文生命周期统计】（用户判据：明确区分各阶段状态）
        //   只读输出，不参与几何；前 12 次打印，用于验收"每条水系是否走完生命周期"。
        if (skelLifeLogCount.getAndIncrement() < 12) {
            int invalid = res.lifeCounts[TerrainFlowSim.Stage.INVALID_TERMINATION.ordinal()];
            int spill = res.lifeCounts[TerrainFlowSim.Stage.SPILLWAY.ordinal()];
            int sea = res.lifeCounts[TerrainFlowSim.Stage.SEA.ordinal()];
            int outl = res.lifeCounts[TerrainFlowSim.Stage.REGION_OUTLET.ordinal()];
            int entry = res.lifeCounts[TerrainFlowSim.Stage.BASIN_ENTRY.ordinal()];
            int conf = res.lifeCounts[TerrainFlowSim.Stage.CONFLUENCE.ordinal()];
            LOGGER.info("[RIVER] skeleton region ({},{}): 路径 {} · 归宿[汇流 {} / 湖入口 {} / 溢口 {} / 入海 {} / 区出口 {} / 异常 {}]",
                    rx, rz, paths.size(), conf, entry, spill, sea, outl, invalid);
            // ★ 2026-09-24【审计升级】全阶段表（stageSummary 含 SOURCE/CHANNEL/
            //   LAKE_STORAGE/DOWNSTREAM）+ 异常路径尾坐标（下一轮定点修的依据）
            LOGGER.info("[RIVER] skeleton region ({},{}): stages {}", rx, rz,
                    TerrainFlowSim.stageSummary(res));
            if (invalid > 0) {
                java.util.List<String> bad = new ArrayList<>();
                for (int k = 0; k < res.stage.length && bad.size() < 6; k++) {
                    if (res.stage[k] != TerrainFlowSim.Stage.INVALID_TERMINATION.ordinal()) continue;
                    int gi2 = k % res.n, gj2 = k / res.n;
                    bad.add("b(" + (int) ((oxB + gi2 * (double) cellB + cellB * 0.5) / hs)
                            + "," + (int) ((ozB + gj2 * (double) cellB + cellB * 0.5) / hs) + ")");
                }
                LOGGER.info("[RIVER] skeleton region ({},{}): 异常尾 {} 个 ⇒ {}", rx, rz, bad.size(), bad);
            }
        }
        // ★★★ 2026-09-24【生命周期兜底：每个有溢口的湖盆必须有人续流】★★★
        //   【断环场景】湖的入流全部来自邻区（本区没有任何路径止于该湖岸 ⇒ 不触发
        //   entersLake ⇒ collectSpillRun 不发生）⇒ 湖有进水、无出水 = "洼地蓄水→
        //   最低溢口→继续下泄"链条在区缝上断环。
        //   【修法】扫全部盆地：凡 spillDown ≥ 0 且溢口下游格未被任何续流段占用
        //   ⇒ 补一条续流折线（traceSkeleton 沿 down 走，撞湖即止 = 下一段由该湖
        //   自己的兜底接管 ⇒ "湖→溢口→河→湖→…"链式全覆盖）。
        //   已有续流段（used[spillDown]）⇒ 跳过（去重，不改既有行为）。
        //   确定性：纯 res/used 函数，盆地序遍历。回退：删除本块。
        for (int b = 0; b < res.spillDown.length; b++) {
            int sd = res.spillDown[b];
            // 溢口不是自动成河：只有下游格已达到汇流阈值，才允许补画续流。
            // 旧逻辑无条件 traceSkeleton，会沿普通坡面画出从湖心扇出的伪河线。
            if (sd < 0 || !res.channel[sd] || used[sd] || res.lake[sd]) continue;
            int[] run = traceSkeleton(res, used, sd);
            if (run.length >= 2) {
                for (int c : run) used[c] = true;
                paths.add(run);
            }
        }
        // ★ 跨区续流：邻区出口种子（pass-2 传入）—— 从种子处沿【本地 D8】继续追。
        //   邻区的 margin 使其出口点落在本区境内 ⇒ 两段河在同一地形上首尾相接。
        if (handoff && incoming != null) {
            for (RiverLineRegion.OutletSeed sd : incoming) {
                double bxs = sd.wx * hs, bzs = sd.wz * hs;
                int gi = (int) Math.round((bxs - oxB - cellB * 0.5) / cellB);
                int gj = (int) Math.round((bzs - ozB - cellB * 0.5) / cellB);
                if (gi < 0 || gj < 0 || gi >= gridN || gj >= gridN) continue;
                int start = gj * gridN + gi;
                if (used[start] || res.lake[start] || res.height[start] <= res.seaLevel) continue;
                int[] p = traceSkeleton(res, used, start);
                // ★ 2026-09-21【短段区分对待】（两类案例实测）：
                //   · 短且【汇入主河】= 真汇合 → 必须画（否则缝上断流，用户实测截图圈出）；
                //   · 短且【死胡同】= 贴着主河的平行废段 → 丢（此前截图的废段）。
                int lastDown = p.length > 0 ? res.down[p[p.length - 1]] : -1;
                boolean joinsRiver = lastDown >= 0 && used[lastDown];
                // ★ 2026-09-24【生命周期】：种子续流的短段若终点合法（入湖/入海/出窗）
                //   必须保留 —— 那正是"跨缝衔接段"，丢掉 = 区缝上断头。
                //   （旧过滤器只认"汇入主河"，短入湖/短入海段被误丢。）
                boolean endsInWater = lastDown >= 0
                        && (res.lake[lastDown] || res.height[lastDown] <= res.seaLevel);
                boolean endsAtEdge = p.length > 0
                        && (p[p.length - 1] % gridN == 0 || p[p.length - 1] % gridN == gridN - 1
                            || p[p.length - 1] / gridN == 0 || p[p.length - 1] / gridN == gridN - 1);
                if (p.length < 6 && !joinsRiver && !endsInWater && !endsAtEdge) continue;
                paths.add(p);
            }
        }
        // ★★★ 2026-09-22【入湖锚点延伸】—— 修"河湖水位不齐平"（用户实测：部分齐平、部分不齐）★★★
        //
        //   【为什么"猜水位"注定失败】雕刻侧湖面的最终值是【三层叠加】的产物：
        //     ① carver 回传 spill → ② 若 {@code LAKE_ESCAPE_LEVEL} 则
        //     {@code escapeWaterLevel(finalGroundFn, …)} 在【雕刻后的点态地形】上重算
        //     （出口被雕低 ⇒ 水位更低）→ ③ 落块层 lakeFineFlood 1 块精度重判。
        //   我在折线里用的 {@code erodedSpill} 只是 ① 的口径 ⇒ 对"出口被雕刻影响过"的湖
        //   必然对不上 ⇒ **部分齐平、部分不齐平**（正是用户实测现象）。
        //
        //   【正解：不再猜，交给雕刻侧】河尾沿 D8 再走最多 {@code LAKE_ANCHOR_EXT} 格，
        //   进入【生产湖域】后把这段标成【湖命中】（lakeLevel）⇒ 雕刻侧对这段走湖分支：
        //   水面 = 雕刻侧自己算的湖面（①②③ 全自动同源）、不挖地形
        //   ⇒ 河-湖衔接【由构造保证齐平】，与我在折线里填什么值无关。
        final int LAKE_ANCHOR_EXT = 6;
        for (int pi = 0; pi < paths.size(); pi++) {
            int[] p = paths.get(pi);
            if (p.length == 0) continue;
            int cur = res.down[p[p.length - 1]];
            List<Integer> ext = new ArrayList<>();
            int g2 = 0;
            // ★ F1i：步进谓词用 floodAny（纯连通，含浅滩 0.05~0.5）—— 尾先走进水里；
            //   浅滩尾节点不入归属（probLake 有 0.5 门）⇒ 按河雕刻出水，不卡死在岸上。
            while (cur >= 0 && floodAny[cur] && !used[cur] && g2++ < LAKE_ANCHOR_EXT) {
                used[cur] = true;
                ext.add(cur);
                cur = res.down[cur];
            }
            if (ext.isEmpty()) continue;
            int[] np2 = new int[p.length + ext.size()];
            System.arraycopy(p, 0, np2, 0, p.length);
            for (int i = 0; i < ext.size(); i++) np2[p.length + i] = ext.get(i);
            paths.set(pi, np2);
        }
        // ★★★ 2026-09-22【入湖即止】—— 撤回上一轮的"河穿湖"延伸 ★★★
        //   用户裁定：「河流不应该是在进入湖泊区域就应该结束雕刻吗？」
        //   且实测："河流的雕刻机制会破坏湖泊地形"。
        //   ⇒ 折线【不进湖】：追踪本来就在湖岸停（channel 不含湖格），保持原样；
        //     河与湖的衔接改用【末节点水面抬到湖面】（河适应湖，见下方后处理），
        //     这样水面视觉连续，而雕刻完全不触及湖内地形。
        // region 盒裁剪（★ slack=0）：跨区续流已由出口种子交接 ⇒ 两区各画到边界为止，
        //   不再重叠。之前 slack=64 时两区在缝带各画一遍 ⇒ "缝边重复河"（用户实测截图）。
        int slack = 0;
        double rBx0 = bx0 - slack, rBz0 = bz0 - slack;
        double rBx1 = bx0 + regionW * hs + slack, rBz1 = bz0 + regionW * hs + slack;
        for (int[] path : paths) {
            int first = -1, last = -1;
            for (int k = 0; k < path.length; k++) {
                int gi = path[k] % gridN, gj = path[k] / gridN;
                double bxx = oxB + gi * (double) cellB + cellB * 0.5;
                double bzz = ozB + gj * (double) cellB + cellB * 0.5;
                if (bxx >= rBx0 && bxx <= rBx1 && bzz >= rBz0 && bzz <= rBz1) {
                    if (first < 0) first = k;
                    last = k;
                }
            }
            int m = last - first + 1;
            // ★ 裁剪后只剩 2~3 格 = 擦边碎片（河的主体在邻区）⇒ 不画，交给邻区。
            //   真实河在本区内必然长于 4 格（成河门槛已要求大集水）。
            if (first < 0 || m < 4) continue;
            MidpointDisplacement.Node[] nodes = new MidpointDisplacement.Node[m];
            double[] surf = new double[m], wid = new double[m], dep = new double[m];
            double[] fall = new double[m], terr = new double[m];
            // ★ 湖段标记（2026-09-22，用户实测："河流不应该在入湖后继续切地形与湖泊的水面"）：
            //   湖内节点写入 lakeLevel ⇒ sampleAll 把该段转为【湖命中】（surface=湖面、
            //   frozen、depth=minDepth）⇒ 雕刻侧走【湖分支=不挖地】，不再在湖里切出
            //   河槽/沙坎（截图中的蓝色板块就是河分支在湖里雕刻的产物）。
            double[] lakeLv = new double[m];
            java.util.Arrays.fill(lakeLv, Double.NaN);
            RiverLineRegion.LakeNode[] lakeNodeArr = new RiverLineRegion.LakeNode[m];
            // ★ 2026-09-22【湖面 = 生产 spill 锚点】（用户裁定："湖泊的水面高度可不能动啊，
            //   毕竟湖泊是按最低溢出口去确定湖面高度的。只能河适应湖。"）
            //   此前湖内节点取 res.fill（模拟的填洼面）⇒ 与生产湖水位可能不同 ⇒ 河湖错位。
            boolean[] lakeNodeAt = new boolean[m];
            double[] lakeLevelAt = new double[m];
            for (int k = 0; k < m; k++) {
                int idx = path[first + k];
                int gi = idx % res.n, gj = idx / res.n;
                double bxx = oxB + gi * (double) cellB + cellB * 0.5;
                double bzz = ozB + gj * (double) cellB + cellB * 0.5;
                double wx = bxx / hs, wz = bzz / hs;
                nodes[k] = new MidpointDisplacement.Node(wx, wz);
                // ★ 2026-09-23：逐节点地形与路由场同口径（routeTerrain 非 null 时为侵蚀后）
                //   —— 否则水面（surf = g − sink）按侵蚀前地面算，而路由/湖按侵蚀后 ⇒ 水位悬空。
                double g = routeTerrain != null ? routeTerrain.applyAsDouble(wx, wz)
                        : groundYAt(wx, wz);
                terr[k] = g;
                // 找所属【生产湖】—— 以生产湖掩码 probLake 为准（不是 res.lake：
                //  res.lake 是模拟湖，模拟 ⊋ 生产 ⇒ 用它会把干地误判成湖岸）。
                RiverLineRegion.LakeNode hit = null;
                if (probLake[idx] && !lakesAll.isEmpty()) {
                    // ★ 2026-09-24：用 lakesAll（含洼地合成湖）⇒ 新湖格也能归属
                    for (RiverLineRegion.LakeNode ln : lakesAll) {
                        boolean inside;
                        if (ln.hasOutline()) {
                            // ★ F1：归属判据同 probLake —— 块级掩码（inBasinMask，
                            //   掩码外不归属 ⇒ 岸上干格保持河分支，由河雕刻出水）。
                            //   lakeBasinHits=false ⇒ inBasinMask 恒 false ⇒ 退回旧 bbox。
                            inside = maskMode
                                    ? inBasinMask(ln, wx, wz)
                                    : ln.inDomain(wx, wz, ln.cellHalf * 2.0);
                        } else {
                            double ddx = wx - ln.x, ddz = wz - ln.z;
                            inside = ddx * ddx + ddz * ddz <= ln.radius * ln.radius * 1.44;
                        }
                        if (inside) { hit = ln; break; }
                    }
                }
                if (hit != null) {
                    // ★ 湖内节点：水面 = 湖的【最终采用水位】（min(侵蚀短板, escape)，与雕刻侧
                    //   完全同源，见 finalLakeLevel 注释）；深度 0 ⇒ 不挖湖床。
                    //   ⚠ 曾用 hit.height（无侵蚀 spill）—— 比最终湖面高 ⇒ 湖命中带错水位
                    //   ⇒ inFlood=false ⇒ 河尾"命中在·水不在"（审计 32.8% FAIL 根因）。
                    double fl = finalLakeLevel(hit);
                    lakeNodeAt[k] = true;
                    lakeLevelAt[k] = fl;
                    lakeLv[k] = fl;                 // ⇒ 雕刻侧按【湖命中】处理（不挖地）
                    lakeNodeArr[k] = hit;           // ⇒ 发命中时带上 LakeNode
                    surf[k] = fl;
                    wid[k] = MIN_RENDER_HALF_WIDTH;
                    dep[k] = 0.0;
                    continue;
                }
                surf[k] = g - params.surfaceSink();
                double areaWu = Math.max(1.0, res.accum[idx] * cellAreaWu);
                double w = widthFromAccum(areaWu, params);
                double d = depthFromAccum(areaWu, w, params);
                // ★【最小可渲染断面】Minecraft 是 1 块栅格：半宽 <1 ⇒ 块心可能落在河道外；
                //   水深 ≤0.5 ⇒ 落块闸门 `carved < waterSurface − 0.5` 恒不成立 ⇒ 一列水都没有。
                //   （实测：骨架路线未加此护栏时，审计"沿河线无水"达 18.8%。）
                if (w < MIN_RENDER_HALF_WIDTH) w = MIN_RENDER_HALF_WIDTH;
                if (d < MIN_RENDER_DEPTH) d = MIN_RENDER_DEPTH;
                d = Math.min(d, params.maxDepthRatio() * w);
                wid[k] = w;
                dep[k] = d;
            }
            // ★ 后处理（顺序敏感！）——铁律：**湖面绝对不动，只能河适应湖**
            //   （用户裁定："湖泊的水面高度可不能动啊，毕竟湖泊是按最低溢出口去确定
            //   湖面高度的。只能河适应湖。"）
            //   ① 岸线钳制【跳过湖节点】：湖面是 spill 锚点，钳到"岸高"会破坏它；
            //      湖节点单个跳过，其余节点照常钳（保持防漫岸）。
            applyBankCapSkip(nodes, surf, wid, params, lakeNodeAt, terr);
            //   ② 河适应湖【回水版】（2026-09-23 R-C1，修"水面沿程抬升 7"）：
            //   旧 = 入湖前 3 节点抬到 min(lv, 地面+1) —— 地面低于湖面的【回水谷段】
            //   （实测 7 例全部 lakeNode=true：上游剖面 148/165 « 湖面 173）抬不到位
            //   ⇒ 入湖处水面跳升 ⇒ 违反"水向低处流"。
            //   新 = 湖面回水语义：从每个湖节点【向上游】走，凡水面低于湖面的节点
            //   一律抬到湖面（= 湖水淹没该谷段，地面<湖面 ⇒ 必有水，非漫岸），
            //   直到水面已 ≥ 湖面（真实岸边）或撞到上一个湖锚为止。
            //   湖面绝对不动（只抬河）——用户铁律保持。
            //   判据用【填洼面连通】（res.fill ≥ lv−0.5）而非地表剖面 —— 地表判据分不清
            //   "干岛穿越"（ground>lv 但 fill≈lv，应平过）与"真岸"（其后系统 fill<lv）
            //   ⇒ 旧写法在岛处断链，③ 的 pinned 链随即把已抬段拉回原剖面（实测残留
            //   2 例 148.45→173.28 正是被③反克）。fill 判据下路径不可能从 <lv 跨到
            //   ≥lv（down[] 沿 fill 严格下行）⇒ 边界只在路径起点/湖锚 ⇒ ③ 链全 lv 无反克。
            for (int j = 0; j < m; j++) {
                if (!lakeNodeAt[j]) continue;
                double lv = lakeLevelAt[j];
                for (int k = j - 1; k >= 0; k--) {
                    if (lakeNodeAt[k]) break;
                    int idx = path[first + k];
                    // ★★★ 2026-09-23【T1.c-1：回水判据方向修复 —— 修"河横着过山腰"】★★★
                    //   旧判据 `fill < lv - 0.5 ⇒ break`：fill ≥ h 恒成立，高地形节点
                    //   （h=231 ≫ lv=173.28）的 fill 更高 ⇒ 退出条件【永不触发】
                    //   ⇒ 回水沿折线一路把上游节点全部抬到湖面 ⇒ 计划水面比地形低
                    //   57.78 块（T0 实测样例）⇒ 雕刻层只能硬挖一条穿山峡谷
                    //   = 用户看到的"河横着过山腰/山脊"。
                    //   正解：节点属于该湖水体 ⟺ 它的填洼面【就在湖面这一层】：
                    //     · 真淹没（fill = lv）→ 回水抬升，保持；
                    //     · 干岛（ground > lv 但 fill ≈ lv）→ 平过，保持（原判据想保的语义）；
                    //     · 高地（fill = h ≫ lv）→ 离开湖的填洼层 ⇒ break，不再污染上游。
                    //   回退：把本行改回 `res.fill[idx] < lv - 0.5`。
                    if (idx < 0 || Math.abs(res.fill[idx] - lv) > 0.5) break;   // 离开该湖的填洼层
                    surf[k] = lv;                                      // 回水/干岛：统一湖面
                }
            }
            //   ②b【F1c：浅滩湖节点改判河】（★ 必须在 ② 回水之后 —— 回水要以【完整
            //      归属】为源找抬升起点；先清源会让残留 2 例抬升回归。实测留痕）：
            //      水深 ∈ [0.05,0.5) 造不出水块 ⇒ 改判河（保水面=湖面，补最小河深，
            //      河雕刻下切入湖口）。⚠ 同步清 lakeNodeArr（polyline 侧），否则
            //      F1g/发射段的 endsInMask 判据被陈旧非空值污染。
            //      深度基准=侵蚀后 sampleWu（terr 是预侵蚀，会漏判）。仅 maskMode。
            if (maskMode) {
                for (int k = 0; k < m; k++) {
                    if (!lakeNodeAt[k]) continue;
                    double hE = erodedYSampler != null
                            ? erodedYSampler.applyAsDouble(nodes[k].x(), nodes[k].z())
                            : terr[k];
                    if (lakeLevelAt[k] - hE < 0.5) {
                        lakeNodeAt[k] = false;
                        lakeLv[k] = Double.NaN;
                        lakeLevelAt[k] = Double.NaN;
                        lakeNodeArr[k] = null;
                        if (dep[k] < MIN_RENDER_DEPTH) dep[k] = MIN_RENDER_DEPTH;
                    }
                }
            }
            //   ③ 锚点式单调化：**湖面是不可越过的锚**（湖节点绝不修改）。
            //      非湖节点不得高于其上游最近锚值（湖面 或 前一个非湖节点水面）
            //      ⇒ 上游河只能"≤ 湖面"进入湖，出湖后也只能"≤ 湖面"继续下行，
            //      两侧都不会出现跨越湖面的台阶（这是"河适应湖"的另一半）。
            double pinned = Double.NaN;
            for (int k = 0; k < m; k++) {
                int idxP = path[first + k];
                if (lakeNodeAt[k]) { pinned = lakeLevelAt[k]; continue; }   // 湖面锚点（不动）
                // ★★★ 2026-09-23【T1.c-2：出湖上岸解除钉住】★★★
                //   旧实现：湖锚之后所有节点一律 surf ≤ 湖面（单调化）。
                //   但折线可能因【生产湖掩码未盖满】穿湖而过 —— 出湖侧地形迅速抬升，
                //   节点仍被钉在湖面 ⇒ 计划水面比地形低数十块（T1c 实测样例：地面
                //   148~166、水面恒 131.07，需下切 17~34 块）⇒ 雕刻硬挖穿山峡谷
                //   = 用户判据"河流不会横着山腰，应该顺山而下"。
                //   修法：节点填洼面已高于湖面（fill > 湖面+0.5 = 已离开湖水体）
                //   ⇒ 解除钉住，恢复该节点自身剖面（terrain − surfaceSink）。
                //   仍在湖层内（fill ≈ 湖面）⇒ 保持压平（原语义不变）。
                //   回退：删除本 if 块。
                if (!Double.isNaN(pinned) && idxP >= 0 && res.fill[idxP] > pinned + 0.5) {
                    pinned = Double.NaN;
                }
                // ★★★ 2026-09-24【pinned 链加"地形合理性"约束 —— 修"水面钉死在山腰"】★★★
                //   【实测铁证（T6 整条折线转储，region(-2,-2)，81 节点）】
                //     k=9  地形=174.93 水面=173.93（盆地最低点，正常）
                //     k=10 地形=181.53 水面=173.93  差=7.60   ← 地形翻头爬升
                //     k=17 地形=199.02 水面=173.93  差=25.09
                //     k=33 地形=200.93 水面=173.93  差=27.00  ← 水面【被钉死 27 块】
                //     k=36 地形=170.34 水面=169.34  ← 越过脊后骤降 70 块到另一侧深谷
                //   【根因】旧 `pinned` 无条件向下传播：只要下游出现过更低值，上游全部
                //   被压平，哪怕那最低点在 27 块之外、中间隔着一道 26 块高的脊。
                //   ⇒ 水面比地形低 27 块 ⇒ 雕刻必须凿穿山腰 = 用户判据"河横着过山腰"。
                //   【修法】pinned 只在【地形本身允许】时生效：水面不得低于
                //   `地形 − surfaceSink − PIN_TOL`。地形高于该下限（= 该处地面已高出
                //   下游水位，水在物理上到不了这里）⇒ 解除钉住，让节点回到自身剖面
                //   （terr − sink）⇒ 水面重新贴地，不再凿山。
                //   【为什么合法】水面高于地形是"悬空水"（另一类 bug），但水面低于地形
                //   数十块意味着"水在空气里穿过山"——比悬空更违背物理。
                //   回退：把 PIN_TOL 设为极大（1e9）⇒ 逐位回到旧行为。
                double pinFloor = terr[k] - params.surfaceSink() - PIN_TOL;
                if (!Double.isNaN(pinned) && pinned < pinFloor) {
                    pinned = Double.NaN;                      // 该处地形已高于下游水位 ⇒ 断开钉链
                }
                if (!Double.isNaN(pinned) && surf[k] > pinned) {
                    // 压平不得超过地形下限（同样防"钉死在山腰"）
                    surf[k] = Math.max(pinned, pinFloor);
                }
                pinned = surf[k];
            }
            // ★★★ 2026-09-22【入湖衔接：河适应湖】（用户裁定"河进湖就该停止雕刻"）★★★
            //   折线不进湖 ⇒ 末节点在湖岸：把它的水面【抬到湖面】（限高 地形+1 防漫岸），
            //   并让上游若干节点跟随抬升（保持下游不抬升的单调性，直到地形上界为止）
            //   ⇒ 视觉上水连续进入湖，而湖内没有任何雕刻。
            int tailDownCell = res.down[path[last]];
            // ★ F1d：条件加 probLake（=块掩码）—— 下游格是生产湖但 sim 未标 lake 时也抬；
            //   归属两级：掩码优先（在下游格上定湖），失败退回旧 inDomain(cellHalf*6)。
            if (tailDownCell >= 0
                    && (res.lake[tailDownCell] || floodAny[tailDownCell])
                    && !lakesAll.isEmpty()) {
                RiverLineRegion.LakeNode tl = null;
                double twx = nodes[m - 1].x(), twz = nodes[m - 1].z();
                if (maskMode) {
                    double dwx = (oxB + (tailDownCell % gridN) * (double) cellB + cellB * 0.5) / hs;
                    double dwz = (ozB + (tailDownCell / gridN) * (double) cellB + cellB * 0.5) / hs;
                    for (RiverLineRegion.LakeNode ln : lakesAll) {
                        if (inBasinMask(ln, dwx, dwz)) { tl = ln; break; }
                    }
                }
                for (RiverLineRegion.LakeNode ln : lakesAll) {
                    if (tl != null) break;
                    if (ln.hasOutline()) {
                        if (ln.inDomain(twx, twz, ln.cellHalf * 6)) { tl = ln; break; }
                    } else {
                        double ddx = twx - ln.x, ddz = twz - ln.z;
                        if (ddx * ddx + ddz * ddz <= ln.radius * ln.radius * 2.25) { tl = ln; break; }
                    }
                }
                // ★ 目标水位 = 湖的【最终采用水位】= min(侵蚀短板, escape)（与雕刻侧完全
                //   同源，见 finalLakeLevel）。
                //   ⚠ 曾只用 erodedSpill ⇒ 漏掉 escape 的压低（两者实测可差 9 块）
                //     ⇒ 河尾水位悬空、inFlood=false ⇒ "命中在·水不在"
                //     （审计河尾无水 19/58 = 32.8% FAIL 的根因：命中诊断 s=173.3 而实际湖面≈164）。
                //   ⚠ 不再做任何 res.fill 近似兜底（用户驳回："你这不是造假吗？"）。
                // ★★★ 2026-09-24【入湖夹角修复 —— 用户判据②】★★★
                //   用户原话："骨架入湖有一部分几乎与湖边缘水平，正常来说应该呈现夹角
                //   状态才对。"（真实入湖：河面斜插进湖面，不是与湖缘平行贴合）
                //   【根因】旧写法 `surf[m-1] = min(lvTarget, terr+1)` + 向上游"跟随抬升"
                //   把【入湖前若干节点全部抬平到湖面】⇒ 末段水面与湖面等高且水平。
                //   【修法】入湖只要求"水面【低于】湖面"（水的下坡连续性），而不是"相等"：
                //     ① 末节点抬到 min(湖面 − ENTER_DROP, 地形+1)：保留一个下坡落差，
                //        且该落差在地形允许范围内；
                //     ② 上游跟随抬升时同样保持 ≥ ENTER_DROP 的单调递降 ⇒ 末段呈现
                //        "斜插入湖"的夹角，而非水平贴合。
                //   物理：河水面必须略高于湖面才在流动（否则湖水倒灌）；体现为末段
                //        与湖缘成夹角。ENter_DROP=0.75 块（<1 格，仅保证有明确斜率）。
                //   回退：把 ENTER_DROP 设为 0.0 ⇒ 逐位回到旧"抬平"行为。
                double lvTarget = finalLakeLevel(tl);
                if (!Double.isNaN(lvTarget)) {
                    double enterTarget = lvTarget - LAKE_ENTER_DROP;
                    if (surf[m - 1] < enterTarget) {
                        surf[m - 1] = Math.min(enterTarget, terr[m - 1] + 1.0);
                        for (int k = m - 2; k >= 0; k--) {
                            double want = surf[k + 1] + LAKE_ENTER_DROP;
                            if (surf[k] >= want) break;              // 已满足递降
                            double capK = terr[k] + 1.0;
                            if (capK < want) break;                  // 抬会漫岸 ⇒ 停
                            surf[k] = want;
                        }
                    }
                }
                // ⚠ 旧路径（保留为不可达代码，便于对照/回退）：
                if (false) {
                    surf[m - 1] = Math.min(lvTarget, terr[m - 1] + 1.0);
                    for (int k = m - 2; k >= 0; k--) {
                        if (surf[k] >= surf[k + 1]) break;                 // 已满足单调
                        double capK = terr[k] + 1.0;                        // 上游不得高过地形+1
                        if (capK < surf[k + 1]) break;                      // 再抬会漫岸 ⇒ 停
                        surf[k] = surf[k + 1];
                    }
                }
            }
            // ★★★ 2026-09-24【两节点间地形单调性校验 + 顺地形插点 —— 用户判据】★★★
            //   用户原话："难道没有判断 2 个连接的节点之间的那段线走的地形不是单调的吗？
            //   我发现很多连接线他们走过的地形根本没判断这个路线的地形变化已经超过
            //   2 个节点的高度区间了。"
            //   核实：确实没有 —— 节点每 cellB 块取一个格心，相邻【直接直线相连】
            //   ⇒ 段内地形可高出两端十几到几十块（T1c 实测最大 37 块）= 图上"斜切长线"。
            //   做法：逐段以 ~1 块步长采样地形；段内最高 > 两端较高者 + MONO_TOL(1 块)
            //   ⇒ 判未顺地形 ⇒ 在【段内地形最低点】插入节点（插点不删点 ⇒ 连通性、
            //   下游拓扑、湖锚全部不变；水面/宽度/深度按 t 线性插值，湖锚段跳过）。
            {
                java.util.function.ToDoubleBiFunction<Double, Double> tf =
                        routeTerrain != null ? routeTerrain : (a, b) -> groundYAt(a, b);
                java.util.ArrayList<MidpointDisplacement.Node> nn = new java.util.ArrayList<>();
                java.util.ArrayList<Double> ns = new java.util.ArrayList<>();
                java.util.ArrayList<Double> nw = new java.util.ArrayList<>();
                java.util.ArrayList<Double> nd = new java.util.ArrayList<>();
                java.util.ArrayList<Double> nf = new java.util.ArrayList<>();
                java.util.ArrayList<Double> nlv = new java.util.ArrayList<>();
                java.util.ArrayList<Double> nt = new java.util.ArrayList<>();
                java.util.ArrayList<RiverLineRegion.LakeNode> nln = new java.util.ArrayList<>();
                int inserted = 0;
                for (int k = 0; k < m; k++) {
                    nn.add(nodes[k]); ns.add(surf[k]); nw.add(wid[k]); nd.add(dep[k]);
                    nf.add(fall[k]); nlv.add(lakeLv[k]); nt.add(terr[k]); nln.add(lakeNodeArr[k]);
                    if (k + 1 >= m) continue;
                    if (lakeNodeAt[k] && lakeNodeAt[k + 1]) continue;      // 湖锚段：水面由湖定
                    double[] lowT = new double[1];
                    if (segmentIsMonotone(nodes[k].x(), nodes[k].z(),
                            nodes[k + 1].x(), nodes[k + 1].z(),
                            terr[k], terr[k + 1], tf, hs, MONO_TOL, lowT)) {
                        continue;
                    }
                    double t = lowT[0];
                    double mx2 = nodes[k].x() + (nodes[k + 1].x() - nodes[k].x()) * t;
                    double mz2 = nodes[k].z() + (nodes[k + 1].z() - nodes[k].z()) * t;
                    nn.add(new MidpointDisplacement.Node(mx2, mz2));
                    ns.add(surf[k] + (surf[k + 1] - surf[k]) * t);
                    nw.add(wid[k] + (wid[k + 1] - wid[k]) * t);
                    nd.add(dep[k] + (dep[k + 1] - dep[k]) * t);
                    nf.add(0.0);
                    nlv.add(Double.NaN);
                    nt.add(tf.applyAsDouble(mx2, mz2));
                    nln.add(null);
                    inserted++;
                }
                if (inserted > 0) {
                    m = nn.size();
                    nodes = new MidpointDisplacement.Node[m];
                    surf = new double[m];
                    wid = new double[m];
                    dep = new double[m];
                    fall = new double[m];
                    terr = new double[m];
                    lakeLv = new double[m];
                    lakeNodeArr = new RiverLineRegion.LakeNode[m];
                    for (int k = 0; k < m; k++) {
                        nodes[k] = nn.get(k); surf[k] = ns.get(k); wid[k] = nw.get(k);
                        dep[k] = nd.get(k); fall[k] = nf.get(k); terr[k] = nt.get(k);
                        lakeLv[k] = nlv.get(k); lakeNodeArr[k] = nln.get(k);
                    }
                    monotoneInsertTotal.addAndGet(inserted);
                }
            }
            // D8 路径相邻格中心距最多 sqrt(2)*cellB。若超过 2*cellB，说明路径数组
            // 在湖/盆地跳转时拼接了不连续节点；禁止把损坏拓扑输出成长射线。
            boolean contiguous = true;
            double maxStepWu = 2.0 * cellB / hs;
            for (int k = 0; k + 1 < nodes.length; k++) {
                if (Math.hypot(nodes[k + 1].x() - nodes[k].x(),
                        nodes[k + 1].z() - nodes[k].z()) > maxStepWu + 1e-9) {
                    contiguous = false;
                    LOGGER.warn("[RIVER] drop non-contiguous skeleton region ({},{}): node {} -> {}",
                            rx, rz, k, k + 1);
                    break;
                }
            }
            if (!contiguous) continue;
            so.rivers.add(new RiverPolyline(nodes, surf, wid, dep, fall, 1, lakeLv, terr, lakeNodeArr));
            // ★ 出口种子：河尾在裁剪边界之外（还会继续流）⇒ 交给邻区接续
            int endIdx = path[last];
            int ei = endIdx % gridN, ej = endIdx / gridN;
            boolean atWindowEdge = ei == 0 || ej == 0 || ei == gridN - 1 || ej == gridN - 1;
            boolean trimmed = last < path.length - 1;
            if (!atWindowEdge && !trimmed) continue;
            double exB = nodes[m - 1].x() * hs, ezB = nodes[m - 1].z() * hs;
            int dRX = exB > bx0 + regionW * hs ? 1 : (exB < bx0 ? -1 : 0);
            int dRZ = ezB > bz0 + regionW * hs ? 1 : (ezB < bz0 ? -1 : 0);
            if (dRX == 0 && dRZ == 0) continue;
            so.outlets.add(new RiverLineRegion.OutletSeed(dRX, dRZ,
                    nodes[m - 1].x(), nodes[m - 1].z(),
                    Math.max(1.0, res.accum[endIdx] * cellAreaWu), surf[m - 1], 1));
        }
        return so;
    }

    /** 从 start 沿本地 D8 追一条续流路径（跨区种子用）；标记 used 防重复。 */
    /**
     * ★★★ 2026-09-24【单调插点阈值】★★★ 段内地面高出【两端地面较高者】超过本值（块）
     * ⇒ 判该段未顺地形单调 ⇒ 插入段内地形最低点。
     * 取 1.0 块（= Minecraft 1 格）：小于此值的起伏是雕刻正常整形范围。
     */
    private static final double MONO_TOL = 1.0;

    /**
     * ★★★ 2026-09-24【自适应采样阈值】★★★ 格内 4 角高程差（块）超过本值 ⇒ 判"陡格"，
     * 在格内子网格（5×5）找最低点（沟底）；否则保持"4 角最小值"（零额外采样）。
     *
     * <p>取 2.0 块：一个 4 块格内落差 2 块以上，已足以说明格内藏着沟/脊
     * （4 块格只取 4 角会漏掉宽 1~2 块的沟）。设 {@code 1e9} 即关闭自适应（回到旧行为）。</p>
     */
    private static final double ADAPT_TOL = 2.0;

    /**
     * ★★★ 2026-09-24【pinned 链的地形公差】★★★ 水面被单调化钉住时，允许比
     * {@code 地形 − surfaceSink} 低的最大值（块）。超过即判定"该处地形已高出下游水位
     * ⇒ 水物理到不了" ⇒ 解除钉住。
     *
     * <p>取 3.0 块：正常河道水面贴地在 sink(≈1) + 局部起伏(≈2) 范围内；
     * 而"穿山"级别的脱节是 7~34 块（T6 实测）。设 {@code 1e9} 即关闭本修复。</p>
     */
    private static final double PIN_TOL = 3.0;

    /**
     * ★★★ 2026-09-24【入湖落差】★★★ 河入湖时末节点水面与湖面的落差（块）。
     *
     * <p>用户判据："骨架入湖有一部分几乎与湖边缘水平，正常来说应该呈现夹角状态才对。"
     * 取 0.75 块（&lt;1 格）：仅保证末段有明确下坡斜率，不改变"河水面必须高于湖面
     * 才能流动"的物理。设 {@code 0.0} ⇒ 回到旧"抬平到湖面"行为。</p>
     */
    private static final double LAKE_ENTER_DROP = 0.75;

    /** 诊断累计：因"段内不单调"而插入的节点数（T0 报告用）。 */
    public static final java.util.concurrent.atomic.AtomicLong monotoneInsertTotal =
            new java.util.concurrent.atomic.AtomicLong();

    /**
     * ★★★ 2026-09-24【两节点间地形单调性校验 —— 用户判据】★★★
     *
     * <p>用户原话："难道没有判断 2 个连接的节点之间的那段线走的地形不是单调的吗？
     * 我发现很多连接线他们走过的地形根本没判断这个路线的地形变化已经超过 2 个节点
     * 的高度区间了。"</p>
     *
     * <p><b>核实结论：确实没有。</b>节点 = 每 {@code SKELETON_CELL_BLOCKS} 块取一个格心，
     * 相邻节点直接直线相连 ⇒ 段内地形可以高出两端十几到几十块（实测最大 37 块），
     * 表现为"斜切长线横过山脊"。</p>
     *
     * <p>本方法以 ~1 块步长采样段内地形，判定"段内最高点 ≤ 两端较高者 + tol"。
     * 不满足即返回 false，并输出段内地形最低点的归一化参数 {@code outLowT[0]}
     * ⇒ 调用方在该处插入节点（插点不删点 ⇒ 不破坏连通性与下游拓扑）。</p>
     */
    private static boolean segmentIsMonotone(double ax, double az, double cx, double cz,
                                            double ha, double hc,
                                            java.util.function.ToDoubleBiFunction<Double, Double> tf,
                                            double hs, double tol, double[] outT) {
        double lenB = Math.hypot(cx - ax, cz - az) * hs;
        int steps = Math.max(2, (int) Math.ceil(lenB));          // ~1 块步长
        double hi = Math.max(ha, hc);
        double lo = Math.min(ha, hc);
        double mx = Double.NEGATIVE_INFINITY;
        double mn = Double.POSITIVE_INFINITY;
        double mnT = 0.5, mxT = 0.5;
        for (int s = 1; s < steps; s++) {
            double t = s / (double) steps;
            double hh = tf.applyAsDouble(ax + (cx - ax) * t, az + (cz - az) * t);
            if (hh > mx) { mx = hh; mxT = t; }
            if (hh < mn) { mn = hh; mnT = t; }
        }
        // ★★★ 2026-09-24【三向判据 —— 用户指正后修正】★★★
        //   ⚠ 上一版只查 `mx <= hi + tol`（越脊）一个方向 ⇒ 漏掉【谷穿】：
        //     段两端在坡上、段中间穿过一条比两端都低的沟（真实河道该拐进那条沟，
        //     直连就等于"把弯拉直、横切等高线"）—— 这正是用户在图上圈出的现象。
        //   现在双向都查；返回"需要插点的 t"：
        //     · 越脊 ⇒ 取【段内最低点】（往低处绕）
        //     · 谷穿 ⇒ 取【段内最低点】（就是那条沟的谷心）
        if (mn < lo - tol) { outT[0] = mnT; return false; }      // 谷穿
        if (mx > hi + tol) { outT[0] = mnT; return false; }      // 越脊
        outT[0] = mnT;
        return true;
    }

    /** 从 start 沿本地 D8 追一条续流路径（跨区种子用）；标记 used 防重复。 */
    private static int[] traceSkeleton(TerrainFlowSim.Result res, boolean[] used, int start) {
        List<Integer> p = new ArrayList<>();
        int cur = start;
        while (cur >= 0 && !used[cur] && !res.lake[cur] && res.channel[cur]) {
            used[cur] = true;
            p.add(cur);
            cur = res.down[cur];
            if (cur < 0) break;
        }
        // ★★★ 2026-09-24【跨区续流首节点补入 —— 修"骨架中间断开一小段"（用户判据②）】★★★
        //   现象：主路径在 [exit] 处停下（下一格是湖/已占用 ⇒ 白循环退出），而续流从
        //   [enter] = [exit]+down 起步。【两个格心各偏半格 ⇒ 渲染上出现 3 块的缝】。
        //   正确的水流路径包含 [enter]（它就在 exit 的下游、同一 D8 链上）⇒ 补入首节点，
        //   缝隙即消失（湖则不进：湖面由湖负责）。
        //   ⚠ 只在起点可补；循环内补会把"另一条已占用路径"的格吃进来。
        //   回退：删除本 if 块。
        if (cur >= 0 && !res.lake[cur] && res.channel[cur] && !used[cur] && !p.isEmpty()) {
            used[cur] = true;
            p.add(cur);
        }
        int[] a = new int[p.size()];
        for (int i = 0; i < a.length; i++) a[i] = p.get(i);
        return a;
    }

    private int[] extractLakes(FlowField field, int rx, int rz,
                               List<RiverLineRegion.LakeNode> lakes, List<Integer> outCells) {
        int nx = field.cols(), nz = field.rows(), n = nx * nz;
        int[] lakeAt = new int[n];
        java.util.Arrays.fill(lakeAt, -1);
        if (!field.hasFill()) return lakeAt;
        boolean[] seen = new boolean[n];
        double regionSize = params.regionSize();
        double lo = rx * regionSize, hi = lo + regionSize;
        for (int idx = 0; idx < n; idx++) {
            if (seen[idx] || !isBasinCellForLake(field, idx)) continue;
            // 8 邻连通洪泛，收集一个洼地
            java.util.ArrayDeque<Integer> stack = new java.util.ArrayDeque<>();
            java.util.List<Integer> cells = new java.util.ArrayList<>();
            stack.push(idx);
            seen[idx] = true;
            while (!stack.isEmpty()) {
                int c = stack.pop();
                cells.add(c);
                int ci = c % nx, cj = c / nx;
                for (int dj = -1; dj <= 1; dj++) {
                    for (int di = -1; di <= 1; di++) {
                        if (di == 0 && dj == 0) continue;
                        int ni = ci + di, nj = cj + dj;
                        if (ni < 0 || ni >= nx || nj < 0 || nj >= nz) continue;
                        int nIdx = nj * nx + ni;
                        if (seen[nIdx] || !isBasinCellForLake(field, nIdx)) continue;
                        seen[nIdx] = true;
                        stack.push(nIdx);
                    }
                }
            }
            // ★★★ 2026-09-21【湖面与河面统一到 e 空间填洼面】★★★
            //   旧口径：块空间湖填洼层（filledAt/basinDepthAt）⇒ 湖 spill 与河流侧的
            //   routingFillAt（e 空间）在侵蚀 delta 下不一致 ⇒ 河"入湖段"水面 ≠ 湖面
            //   ⇒ 湖格不落水（实测 1400² 审计：河尾无水 5/11 FAIL）。填洼优先开启时
            //   两边必须同源：spill = max(填洼面) 换算块高；深度用同一面算。
            double spill;
            double maxDepth = 0.0;
            int deepest = cells.get(0);
            double cx = 0.0, cz = 0.0;
            if (fillFirstRouting && !lakeDomainTerrainOnly) {
                double spillE = Double.NEGATIVE_INFINITY;
                for (int c : cells) spillE = Math.max(spillE, field.routingFillAt(c));
                spill = curve.heightFromE(spillE);
                for (int c : cells) {
                    cx += field.cellCenterX(c);
                    cz += field.cellCenterZ(c);
                    double d = curve.heightFromE(field.routingFillAt(c))
                            - groundYAt(field.cellCenterX(c), field.cellCenterZ(c));
                    if (d > maxDepth) { maxDepth = d; deepest = c; }
                }
            } else {
                spill = field.filledAt(cells.get(0));
                for (int c : cells) {
                    double d = field.basinDepthAt(c);
                    if (d > maxDepth) { maxDepth = d; deepest = c; }
                    cx += field.cellCenterX(c);
                    cz += field.cellCenterZ(c);
                }
            }
            cx /= cells.size();
            cz /= cells.size();
            if (cells.size() < LAKE_MIN_CELLS || maxDepth < LAKE_MIN_DEPTH) continue;
            // 水下洼地不是湖（那是海/海底）：溢出坎低于海平面 = 整盆都在水下 = 海底/潟湖
            if (spill <= curve.seaLevelY() + 0.5) continue;
            // 跨区归属：中心须在本 region 自有盒内（margin 重叠区归邻区，避免重复湖）
            if (cx < lo || cx > hi || cz < lo || cz > hi) continue;
            // 缝带安全区（PL-RGA lake_safe_mask）：贴边洼地会被邻区也判成湖。
            //   ★ 湖用【1 格】而非 borderDist(4 格)：重复湖已由上面的"中心格归属"拦住，
            //     borderDist 是给【布源】用的（源头要离缝远才不撞邻河谷壁），套到湖上
            //     会白白砍掉 region 边缘一半的洼地（实测产量腰斩）。
            // ★★★ 2026-09-22【不再因贴边丢弃洼地】（用户裁定：湖被截短，该是湖的没算成湖）★★★
            //   旧行为：`nearRegionBorder(...) ⇒ continue` 把【中心贴 region 边】的洼地【整片丢弃】
            //   ⇒ 湖在 region 边界处被硬切断（用户实测："目前的湖泊绝对没达到贴边缘"），
            //     且该片水域只剩河命中填充 ⇒ 渲染成规整的轴对齐矩形青色块。
            //   为什么这条排除是【冗余】的：跨区重复湖已由上面的
            //     「中心格须在本 region 自有盒内（cx/cz ∈ [lo,hi]）」归属判定拦住
            //     —— 本类注释自己写过："borderDist 是给【布源】用的，套到湖上会白砍掉
            //     region 边缘一半的洼地（实测产量腰斩）"。
            //   ⇒ 湖提取不再看边界；湖域按【地形恒高填水】自然延伸到山体边缘。
            //   回退：置 lakeAllowEdgeBasins = false。
            if (!lakeAllowEdgeBasins
                    && nearRegionBorder(field, deepest, rx, rz, params.gridCell())) continue;
            double area = cells.size() * params.gridCell() * params.gridCell();
            int li = lakes.size();
            // ★ 逐格洼地轮廓（2026-09-09 B1）：保留洼地真实格中心，取代"等面积圆"。
            //   radius 仅留作诊断/包围盒；命中判定改由 LakeNode.inDomain 的格方块并集
            //   + 落块侧等高线（侵蚀后 height < spill）共同决定 → 湖岸是自然等高线。
            double[] outlineX = new double[cells.size()];
            double[] outlineZ = new double[cells.size()];
            for (int ci = 0; ci < cells.size(); ci++) {
                outlineX[ci] = field.cellCenterX(cells.get(ci));
                outlineZ[ci] = field.cellCenterZ(cells.get(ci));
            }
            // ★ 溢出口坎邻格（2026-09-09 侵蚀短板重算）：湖盆外圈 8 邻中"非洼地且
            //   fillE ≤ spill+0.05"的格 = 真正挡水/溢出的墙缺口。侵蚀削低它们 → 水位
            //   随之降（短板）。存 rim → carver 湖分支用侵蚀后高度对 rim 取 min。
            java.util.ArrayList<double[]> rims = new java.util.ArrayList<>();
            java.util.HashSet<Integer> rimSeen = new java.util.HashSet<>();
            for (int c : cells) {
                int ci = c % nx, cj = c / nx;
                for (int dj = -1; dj <= 1; dj++) {
                    for (int di = -1; di <= 1; di++) {
                        if (di == 0 && dj == 0) continue;
                        int ni = ci + di, nj = cj + dj;
                        if (ni < 0 || ni >= nx || nj < 0 || nj >= nz) continue;
                        int nIdx = nj * nx + ni;
                        if (field.isBasinCell(nIdx) || rimSeen.contains(nIdx)) continue;
                        double e = field.fillEAt(nIdx);
                        if (e <= spill + 0.05) {
                            rimSeen.add(nIdx);
                            rims.add(new double[]{field.cellCenterX(nIdx), field.cellCenterZ(nIdx)});
                        }
                    }
                }
            }
            double[] rimX = null, rimZ = null;
            if (!rims.isEmpty()) {
                rimX = new double[rims.size()];
                rimZ = new double[rims.size()];
                for (int ri = 0; ri < rims.size(); ri++) {
                    rimX[ri] = rims.get(ri)[0];
                    rimZ[ri] = rims.get(ri)[1];
                }
            }
            lakes.add(new RiverLineRegion.LakeNode(cx, cz, spill,
                    Math.sqrt(area / Math.PI), maxDepth,
                    outlineX, outlineZ, params.gridCell() * 0.5, rimX, rimZ));
            for (int c : cells) lakeAt[c] = li;
            outCells.add(spillCell(field, cells, nx, nz));
        }
        return lakeAt;
    }

    /**
     * ★ 2026-09-20 湖满溢【兜底出流】—— 用户判据："水流堆积后溢出会继续向下流动"。
     *
     * <p>当正常溢出口不可用（{@code outCell < 0}：邻格全在水下；或该格已被认领）时，
     * 沿湖的【逐格洼地轮廓】找一处"湖面高于当地地形"的岸边格，从那里
     * {@link #mergeIntoNearestRiver} 并入最近的已有河。</p>
     *
     * <p>为什么这样修而不是"再 trace 一条"：兜底场景本身就是"溢出口几何退化"，
     * 再 trace 容易原地回滚；并入最近河是既有机制（与跨区续流的合并同一路径），
     * <b>必然产出</b>且交汇处继承对方水面（零台阶）。</p>
     *
     * @return 是否成功并入
     */
    @Deprecated
    private boolean spillMergeFallback(FlowField field, int[] lakeAt, RiverLineRegion.LakeNode lk,
                                       int nx, int nz, int rx, int rz,
                                       boolean[] claimed, double[] nodeE, double[] nodeSurf,
                                       int[] levelAt, List<int[]> allSegments,
                                       List<RiverPolyline> rivers, List<RiverSpec> specs,
                                       List<RiverLineRegion.LakeNode> lakes,
                                       List<Integer> accepted, int stepSize) {
        if (lk.cellX == null || lk.cellX.length == 0) return false;
        // 候选：湖轮廓格中"地形低于湖面"者（水能溢出的岸），按地形由高到低试（先试最靠外的坎）
        int bestCell = -1;
        double bestE = Double.NEGATIVE_INFINITY;
        for (int k = 0; k < lk.cellX.length; k++) {
            int idx = field.indexOf(lk.cellX[k], lk.cellZ[k]);
            if (idx < 0 || claimed[idx]) continue;
            if (field.isBasinCell(idx)) continue;          // 只要【盆外/岸边】格
            double e = field.eAt(idx);
            if (e > bestE) { bestE = e; bestCell = idx; }
        }
        if (bestCell < 0) {
            // 轮廓全在盆内 ⇒ 退一步：取洼地轮廓上与湖面最接近的格的下游邻格
            for (int k = 0; k < lk.cellX.length && bestCell < 0; k++) {
                int idx = field.indexOf(lk.cellX[k], lk.cellZ[k]);
                if (idx < 0) continue;
                int d = field.flowTo(idx);
                if (d >= 0 && !claimed[d] && !field.isBasinCell(d)) bestCell = d;
            }
        }
        if (bestCell < 0) return false;
        List<Integer> link = mergeIntoNearestRiver(field, bestCell, nx, claimed, nodeSurf, null);
        if (link == null || link.size() < 2) return false;
        double[] acc = new double[link.size()];
        java.util.Arrays.fill(acc, field.accumAt(bestCell));
        TraceOutcome mOut = new TraceOutcome(link, false, false, true, acc, false);
        CommitOut c = commitRiver(field, mOut, 1, claimed, nodeE, nodeSurf, levelAt,
                allSegments, rivers, specs, lakes, accepted, nx, lk.height, rx, rz,
                null, Double.NaN, true);
        return c.poly() != null;
    }

    /** 出口河是否一出门就又终止在湖里（同一湖不反复发出口河）。 */
    private static boolean outletOnlyToLake(TraceOutcome o, int[] lakeAt) {
        if (o == null || o.cells.isEmpty()) return true;
        int last = o.cells.get(o.cells.size() - 1);
        return lakeAt != null && lakeAt[last] >= 0;
    }

    /** 溢出口：洼地内存在一个"非洼地且高程 ≤ spill"的邻格 → 水从那里溢出。
     * 返回该【外部邻格】（下游河起点）；无出口（真内流）返回 -1。
     */
    private int spillCell(FlowField field, java.util.List<Integer> cells, int nx, int nz) {
        double spill = field.filledAt(cells.get(0));
        int bestOut = -1;
        double bestE = Double.POSITIVE_INFINITY;
        for (int c : cells) {
            int ci = c % nx, cj = c / nx;
            for (int dj = -1; dj <= 1; dj++) {
                for (int di = -1; di <= 1; di++) {
                    if (di == 0 && dj == 0) continue;
                    int ni = ci + di, nj = cj + dj;
                    if (ni < 0 || ni >= nx || nj < 0 || nj >= nz) continue;
                    int nIdx = nj * nx + ni;
                    if (field.isBasinCell(nIdx)) continue;
                    double e = field.fillEAt(nIdx);
                    if (e <= spill + 0.05 && e < bestE) { bestE = e; bestOut = nIdx; }
                }
            }
        }
        return bestOut;
    }

    /**
     * 该格是否落在【已有某条河的过渡区（谷壁）】内。
     *
     * <p>谷壁影响半径 = 3.5 × 该处半宽（与 carver 的 valley 定义同源，单位 wu），
     * 点到河折线取线段最近距离。源点若落在邻河的谷壁里，新河就会在别人刚雕出的
     * 谷坡上再切一条槽 —— 即用户实测的"源头生成在另外一条河的过渡区里面"。</p>
     *
     * <p>{@code extra}：邻 region 的河（pass-1 折线，world wu 坐标同域）——续流的
     * 选头发生在 region 的 margin 区（±320wu），那里可能已有【邻区】的河在雕刻；
     * 只看本 region 的 {@code rivers} 会跨区致盲（实测 seed 28183 侵入邻区河
     * 谷壁 18.4 格，本 region 检查完全看不见）。可为 null。</p>
     */
    private static boolean insideExistingValley(FlowField field,
                                                List<RiverPolyline> rivers,
                                                List<RiverPolyline> extra, int cell) {
        if (extra != null && !extra.isEmpty() && insideValleyOf(field, extra, cell)) return true;
        return insideValleyOf(field, rivers, cell);
    }

    /** 单一折线列表的谷壁检查（{@link #insideExistingValley} 的核心循环）。 */
    private static boolean insideValleyOf(FlowField field,
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

    // ==================================================================
    // ★ 支流分叉 generateForks（2026-09-20）
    //
    //   【为什么需要】实测（runRiverWidthProfileProbe，25 region，seed 9139912035078620160）：
    //     节点数 <5=0  <10=0  <20=5  <40=44  <80=22  >=80=14，密度 3.4 条/region
    //     ⇒ 一条短溪都没有 ⇒ 观感"河凭空出现、看不到上游源头/支流"。
    //     而"降成河门槛"与"放宽源头过滤"两条路互斥（后者违反 2026-09-01 用户要求
    //     『源头不应该生成在另外一条河的过渡区里面』）⇒ 只剩"支流贴着父河生长"。
    //
    //   【机制】只搬 FTF 的【布点规则】，不搬它的【几何】：
    //     · FTF：fork = 从父河 offset 处按 ±27°~68.4° 反向延伸 0.44×父长的【直线段】。
    //     · 我们：用同一规则算出【候选源点】，再用既有 traceRiver 沿 D8 下坡追踪；
    //             必须 joined（汇入已有河）否则整条回滚 ⇒ 贴地形、不悬空、不产新出口。
    //
    //   【⚠ 长度口径（动手前核查发现）】minRiverNodes=3 是【格数】⇒ 最短河 3 格=72wu
    //     ≈13 节点，进不了 <10 桶；唯一通路是 feeder 的【2 格例外】（commitRiver:877-878）
    //     ⇒ 分叉以 feeder=true 提交，长度要短（不照搬 FTF 的 0.44×父长）。
    // ==================================================================

    /**
     * 【河尾终止原因】计数器（2026-09-20，纯诊断、<b>零行为变更</b>）。
     *
     * <p>为什么需要：用户实机反馈"河流直接以河结束（终点既不是湖也不是海）"，而项目里
     * <b>从来没有记录过终止原因</b> —— {@code RiverOutlet.Type.LAND_SINK}（注释：异常陆地终止）
     * 全仓零赋值。没有观测就没有定位。</p>
     *
     * <p>关键量 = {@link #lakeNoNode}：{@code out.isLake == true}（追踪到洼地/平地终止）
     * 但该格<b>没有湖节点</b>（{@code lakeSurface == NaN}）⇒ {@code commitRiver} 落到
     * 最后的 else（{@code outletSurf = junctionGround}）⇒ <b>河就地在陆地上结束</b>。
     * {@link #basinCell} 再区分"该格是填洼层认定的洼地格"（⇒ 应能沿溢出口续流）与
     * "非洼地平地"（⇒ 另一类成因）。</p>
     */
    public static final TailDiag tailDiag = new TailDiag();

    /** 河尾终止原因计数（见 {@link #tailDiag}）。 */
    public static final class TailDiag {
        public int joined, ocean, lakeWithNode, lakeNoNode, outlet;
        /** {@link #lakeNoNode} 中，该格是填洼层洼地格的数量（⇒ 应从溢出口续流）。 */
        public int basinCell;
        /** {@link #lakeNoNode} 中，非洼地格的数量。 */
        public int notBasin;
        /** ★ 2026-09-20：内陆终止的【具体坐标】（wu/2 = block 前先记 wu）+ 是否洼地格。 */
        public final List<double[]> noNodeTails = new ArrayList<>();
        /** ★ 有河汇入的湖，但【溢出口格已被认领】⇒ 发不出出口河（用户："堆积后应溢出继续流"）。 */
        public int lakeOutCellClaimed;
        /** ★ 有河汇入的湖，但【溢出口格 < 0】（真内流）⇒ 不发出口河。 */
        public int lakeOutCellMissing;
        /** ★ 无河汇入的湖（雨水补给）⇒ 按设计不发出口河。 */
        public int lakeNoInflow;
        /** 湖总数（本 region 累计）。 */
        public int lakesTotal;
        /** 内陆终止的成因分解（见 {@code TraceOutcome.END_*}）。 */
        public int endSelfLoop, endSelfApproach, endNoDownNoExit;

        public void reset() {
            joined = ocean = lakeWithNode = lakeNoNode = outlet = basinCell = notBasin = 0;
            noNodeTails.clear();
            lakeOutCellClaimed = lakeOutCellMissing = lakeNoInflow = lakesTotal = 0;
            endSelfLoop = endSelfApproach = endNoDownNoExit = 0;
        }

        @Override
        public String toString() {
            return String.format("汇入=%d 入海=%d 入湖(有湖节点)=%d ★内陆终止无湖=%d"
                            + "（其中 洼地格=%d 非洼地=%d）边界出口=%d",
                    joined, ocean, lakeWithNode, lakeNoNode, basinCell, notBasin, outlet);
        }
    }

    /**
     * 洼地/平地续流总开关（2026-09-20）。true = 无下坡时沿填洼面 BFS 跳到出口继续追踪。
     *
     * <p>⚠ 归因实测（runRiverEndProbe 三配置对照）：马蹄形闭环在【开/关本开关 + 开/关分叉 +
     * 开/关新认领】三种组合下<b>完全一致</b> ⇒ <b>闭环不是本开关造成的</b>（是既有缺陷，
     * 已由 {@link #selfApproach} 守卫修复）。本开关的真实收益：内陆终止无湖 <b>35 → 12</b>。</p>
     *
     * @deprecated ★ 2026-09-22【旧版遗留 · 全面转向新版水文后废弃】
     *     <p>本开关及其下游补丁族（{@link #spillExitCell} / {@link #basinExitCell} /
     *     {@link #spillMergeFallback} / 湖出流兜底）是"旧链路（48 块粗格 D8 追踪）"
     *     在洼地卡死问题上的<b>补丁</b>。新版水文（填洼优先 + ε 微坡 + 填洼面 D8 + 块分辨率
     *     流体骨架）在<b>构造上</b>不存在"流不动"的格 ⇒ 这些补丁失去存在理由，
     *     且互相引入自环/非单调路径（实测 12 处内陆终止中 7 处自环全由续流补丁引入）。</p>
     *     <p><b>退役计划</b>：新版 verifier 跑出 {@code tailDiag} 全项 = 0 后整体删除
     *     （见 PLAN-hydrology-flow.md §废弃清单）。在此之前保留用于 A/B 对照。</p>
     */
    @Deprecated
    public static volatile boolean basinReroute = true;

    /**
     * 主河追踪步长覆盖（2026-09-20，探针用）：&lt;=0 = 用 {@code params.traceStep()}。
     *
     * <p><b>为什么要量它</b>：生产 `traceStep = 2`（跳跃 2 格）—— 跳格可能<b>越过局部脊线</b>
     * 落进别的汇流区，造出【伪环路】（实测"内陆终止"12 处里【自环占 7】）与伪洼地。
     * 置 1（逐格走，= 分叉已在用的做法）可验证该假设。</p>
     */
    public static volatile int mainTraceStepOverride = -1;

    /**
     * ★ 2026-09-20「填洼优先」流向总开关：{@code true} = 流向/累积建在 e 空间填洼面上
     * （洼地不再是终点 ⇒ 水"堆积→溢出→继续向下流"）；{@code false} = 旧行为（原始 e 建流向）。
     */
    // ★ 2026-09-23 A/B 实测：关闭它会让【湖内缺格 7 → 40210（更差）】⇒ 它不是环状湖的原因，
    //   故恢复 true。（关闭即回到"流向卡洼地"的旧场，湖反而大面积缺失。）
    public static volatile boolean fillFirstRouting = true;

    /**
     * ★★★ 2026-09-22【湖域判定必须与水流无关】（用户裁定 + 实测根因）★★★
     *
     * <p><b>用户的设计（原话）</b>：「湖泊非常好确定湖面范围，湖面保持高度一直填满到
     * 碰到实体山体边缘就完成了」—— 即湖域 = <b>地面低于恒定湖面的连通区域</b>，
     * 是<b>纯地形等高线</b>问题，与河流/流向无关；且湖在河之先生成。</p>
     *
     * <p><b>被修的缺陷（实测定位）</b>：上一版把湖域判定改用 {@code routingFillAt}
     * （= {@code eFilledR}，priority-flood 的<b>填洼优先面</b>）。而那套面是
     * <b>专门服务流向</b>的（见 {@code FlowField.fillRoutingSurface} 注释）：
     * <ol>
     *   <li>带 <b>+1e-5 递增 ε 微坡</b>（朝出口单调微降）⇒ ε 沿程累积超过
     *       {@code 1e-4} 阈值处，"真洼地格"被误判成"非洼地"⇒ 湖轮廓破碎；</li>
     *   <li>含 breach/流向语义 ⇒ 湖域形状随水流变化。</li>
     * </ol>
     * ⇒ 表现正是用户指出的「湖泊区域明显被河流等影响了」。</p>
     *
     * <p><b>正解</b>：湖域与湖面一律取 <b>ε-free 的纯地形填洼层</b>
     * （{@code filledAt / basinDepthAt / isBasinCell}）——它是 priority-flood 在
     * 真实地形上的结果，<b>不含 ε、不含流向</b>，因此天然满足"湖面恒定高度、
     * 填到实体山体边缘为止"。</p>
     *
     * <p><b>回退</b>：置 false ⇒ 回到"湖域取 routingFillAt"（即被水流污染的行为）。</p>
     */
    public static volatile boolean lakeDomainTerrainOnly = true;

    /**
     * ★★★ 2026-09-22【允许贴 region 边缘的洼地成湖】★★★
     *
     * <p><b>用户判据</b>：「目前的湖泊绝对没达到贴边缘，有部分明显还是湖泊的部分。」
     * ⇒ 湖被截短：本该是湖的部分没被算成湖。</p>
     *
     * <p><b>被修的缺陷</b>：湖提取里的 {@code nearRegionBorder ⇒ continue} 把
     * 「中心贴 region 边」的洼地<b>整片丢弃</b> ⇒ 湖在 region 边界被硬切断，
     * 且该片水域只剩河命中填充（渲染成轴对齐矩形青色块）。</p>
     *
     * <p><b>为何安全</b>：跨区重复湖已由「中心格归属（cx/cz 在本 region 自有盒内）」
     * 判定拦住，该排除是冗余的（本类既有注释已指出它会"白砍掉边缘一半洼地"）。</p>
     *
     * <p><b>回退</b>：置 false ⇒ 逐位回到"贴边洼地丢弃"。</p>
     *
     * <p>⚠ <b>2026-09-22 A/B 实测：假设【未获证实】，故默认保持 false（= 旧行为）</b>：
     *   开启后本窗口水体 278535 → 272047（<b>−0.33%</b>，湖<b>没有变大反而略减</b>），
     *   渲染图肉眼无变化 ⇒ 用户所报"湖没贴到边缘"<b>不是由这条边界排除造成的</b>。
     *   按本仓库纪律（不把未验证生效的改动留成默认），默认关；保留开关供后续 A/B。</p>
     */
    public static volatile boolean lakeAllowEdgeBasins = false;

    /**
     * ★★★ 2026-09-22【湖优先：只要存在湖命中就剥掉河命中】（量测驱动）★★★
     *
     * <p><b>量测依据</b>：湖内"该有水却无水" 20081 → 12702 后的<b>残留格全部同时带河命中</b>
     * ⇒ carver 的 {@code inRiverChannel} 为真 ⇒ 走河分支不灌水。根因是湖命中的发出域
     * 比过滤判据 {@code inRealLakeDomain}(margin=0) 更宽。</p>
     *
     * <p><b>为何现在才敢用</b>：这条写法早前引发 41.4% 干节点 —— 但当时<b>湖水位是错的</b>
     * （rim 圈 e/h 口径混用 ⇒ 151.89 vs 地形 167.44）。现水位已统一到 minimax 逃逸
     * （{@code LAKE_MINIMAX_LEVEL}）且判水改为等高线（{@code LAKE_CONTOUR_ONLY}）
     * ⇒ 前提不再成立。若干节点回升，则本开关即回退点。</p>
     *
     * <p>⚠ <b>2026-09-22 A/B 实测（同 seed 同窗口）：收益与代价并存，暂默认关</b>
     * <pre>
     *                     关(当前)      开
     *   最近命中=湖的列    226406     480727
     *   湖有水格            92881     170677   (+84% ← 用户要的方向)
     *   河有水列           192481      92239   (−52%)
     *   总水体             285362     262916   (−7.9%)
     *   湖内缺格            12702      12806   (≈不变)
     * </pre>
     *   湖面确实补齐了，但总水体 −7.9% 与验收标准④（河线必须有水）冲突，
     *   且湖内缺格未降 ⇒ 说明还有第三处（河列在湖带内被按湖水位判干）。
     *   ⇒ 按本仓库纪律默认关；待"河列在湖带内不被误判干"修好后一并开启。</p>
     */
    public static volatile boolean lakePriorityAnyHit = false;

    /**
     * 动量权重诊断覆盖（2026-09-20，探针用）：&lt;0 = 用编译期常量 {@code FLOW_MOMENTUM_WEIGHT}；
     * 需要验证"节点位置粒子积分是否造成马蹄形闭环"时置 0（= 逐位回到纯 D8 格心）。
     */
    public static volatile double momentumOverride = -1.0;

    /**
     * 认领范围开关（2026-09-20）：true = 只认领【可见折线】格（本日修复）；
     * false = 旧行为（认领全部追踪格，含被裁掉的上游段与被丢弃的河 ⇒ 幽灵格）。
     */
    public static volatile boolean claimVisibleOnly = true;

    /** dry-run 开关（探针用）：按分叉规则布点并追踪【只统计、不提交任何河】。 */
    public static volatile boolean forkDryRun = false;

    /** dry-run 统计（跨 region 累计；探针跑前自行 {@link ForkStats#reset()}）。 */
    public static final ForkStats forkStats = new ForkStats();

    private static final double FORK_TWO_PI = Math.PI * 2.0;

    /** 分叉可行性统计（仅 dry-run 填充；生产零开销）。 */
    public static final class ForkStats {
        public int parents, parentTooShort, placements;
        public int rejOutside, rejNoUpstream, rejClaimed, rejBorder, rejTrough, rejValley, rejClear;
        public int traceNull, traceNullShort, traceNullOther, notJoined, wouldAccept;
        public final List<Double> parentArc = new ArrayList<>();
        public final List<Double> forkCells = new ArrayList<>();
        public final List<Double> forkLenWu = new ArrayList<>();
        /** 纯沿 flowTo 走到已认领格的格数（诊断"路径太短"用，与 traceRiver 独立口径）。 */
        public final List<Double> rawPathLen = new ArrayList<>();

        public void reset() {
            parents = parentTooShort = placements = 0;
            rejOutside = rejNoUpstream = rejClaimed = rejBorder = rejTrough = rejValley = rejClear = 0;
            traceNull = traceNullShort = traceNullOther = notJoined = wouldAccept = 0;
            parentArc.clear(); forkCells.clear(); forkLenWu.clear(); rawPathLen.clear();
        }
    }

    /** 分叉上下文（只读打包 build 的局部变量，避免方法参数爆炸）。 */
    private record ForkCtx(FlowField field, List<RiverPolyline> rivers, List<RiverSpec> specs,
                           boolean[] claimed, double[] nodeE, double[] nodeSurf, int[] levelAt,
                           List<int[]> allSegments, List<RiverLineRegion.LakeNode> lakes,
                           List<Integer> accepted, int[] lakeAt,
                           int nx, int nz, int rx, int rz) { }

    /**
     * 分叉主循环：按 depth 逐层推进（FTF 递归的迭代版；depth 0 = 直接挂在主河上的支流）。
     *
     * <p>⚠ dry-run 不提交 ⇒ 没有下一代，只量 depth 0（可行性上界；真实递归密度会更高）。</p>
     */
    private void generateForks(ForkCtx c, boolean dryRun) {
        RiverLineParams.ForkParams fp = params.fork();
        List<int[]> frontier = new ArrayList<>();       // {rivers 下标, level}
        for (int i = 0; i < c.rivers.size(); i++) {
            frontier.add(new int[]{i, c.rivers.get(i).level});
        }
        int made = 0;
        for (int depth = 0; depth <= fp.maxDepth() && !frontier.isEmpty(); depth++) {
            List<int[]> next = new ArrayList<>();
            for (int[] pi : frontier) {
                if (made >= fp.countCap()) break;
                made += forkFromParent(c, pi[0], pi[1], depth, fp, dryRun, next);
            }
            frontier = next;
            if (dryRun) break;
        }
    }

    /** 一条父河上的布点循环（FTF：offset 0.25→0.9，逐点左右交替）。 */
    private int forkFromParent(ForkCtx c, int parentIdx, int parentLevel, int depth,
                               RiverLineParams.ForkParams fp, boolean dryRun, List<int[]> next) {
        RiverPolyline p = c.rivers.get(parentIdx);
        if (p.nodes.length < 2) return 0;
        double total = arcLength(p);
        forkStats.parents++;
        if (dryRun) forkStats.parentArc.add(total);
        if (total < fp.minParentLenWu()) { forkStats.parentTooShort++; return 0; }
        double forkLen = Math.min(fp.lenMaxWu(), Math.max(fp.lenMinWu(), fp.lengthFrac() * total));
        long pid = parentId(p);
        int dirSign = rndFork(c.rx, c.rz, pid, depth, 0, 7919) < 0.5 ? -1 : 1;
        double sMin = depth == 0 ? fp.spacingMin() : fp.spacingMinDeep();
        double sRange = depth == 0 ? fp.spacingRange() : fp.spacingRangeDeep();
        int made = 0, offIdx = 0;
        for (double off = fp.offsetLo(); off < fp.offsetHi();
             off += sMin + sRange * rndFork(c.rx, c.rz, pid, depth, offIdx, 104729)) {
            dirSign = -dirSign;
            int idx = placeForkSource(c, p, off, forkLen, dirSign, pid, depth, offIdx, fp);
            offIdx++;
            if (idx < 0) continue;
            if (dryRun && fp.mode() == 0) forkStats.forkLenWu.add(forkLen);
            if (tryFork(c, idx, parentLevel, fp, dryRun, next)) made++;
        }
        return made;
    }

    /** 按 FTF 规则算候选源点并过闸门；返回格下标，或被拒返回 -1。 */
    private int placeForkSource(ForkCtx c, RiverPolyline p, double off, double forkLen,
                                int dirSign, long pid, int depth, int offIdx,
                                RiverLineParams.ForkParams fp) {
        if (fp.mode() == 1) return placeUpstreamSource(c, p, off, fp);
        double[] j = pointAtFraction(p, off);
        if (j == null) return -1;
        double h = rndFork(c.rx, c.rz, pid, depth, offIdx, 15485863);
        double ang = j[2] + dirSign * FORK_TWO_PI * (fp.angleMinTurns() + fp.angleRangeTurns() * h);
        double sx = j[0] - Math.sin(ang) * forkLen;
        double sz = j[1] - Math.cos(ang) * forkLen;
        forkStats.placements++;
        int idx = c.field.indexOf(sx, sz);
        if (idx < 0) { forkStats.rejOutside++; return -1; }
        if (c.claimed[idx]) { forkStats.rejClaimed++; return -1; }
        if (nearRegionBorder(c.field, idx, c.rx, c.rz, params.borderDist())) { forkStats.rejBorder++; return -1; }
        if (fp.troughMode() != RiverLineParams.ForkParams.TROUGH_OFF
                && !troughCheck(c.field, idx, c.nx, c.nz, fp.troughMode())) { forkStats.rejTrough++; return -1; }
        if (insideExistingValley(c.field, c.rivers, null, idx)) { forkStats.rejValley++; return -1; }
        if (minDistToRivers(c.rivers, sx, sz) < fp.clearanceWu()) { forkStats.rejClear++; return -1; }
        return idx;
    }

    /**
     * mode=1（地形驱动）：从父河上的汇入点沿 D8 <b>上游未认领分支</b>回走 N 格，以该格为叉源。
     *
     * <p>为什么这样找源（M0 实测几何布点 0 成功率）：支流在 D8 图上就是【汇入该点的上游
     * 分支】⇒ 沿 {@code flowTo} 反向走即可。好处：① 天然在谷槽（是流线，不是盲抛的点）；
     * ② 天然汇入父河（顺流而下必回到汇入点）；③ 长度由回走步数直接控制 ⇒ 能绕开
     * "叉太短 ⇒ traceRiver 判 path&lt;minRiverNodes 而回滚"的死结。</p>
     */
    private int placeUpstreamSource(ForkCtx c, RiverPolyline p, double off,
                                    RiverLineParams.ForkParams fp) {
        double[] j = pointAtFraction(p, off);
        if (j == null) return -1;
        int cur = c.field.indexOf(j[0], j[1]);
        if (cur < 0) { forkStats.rejOutside++; return -1; }
        int steps = 0;
        for (int k = 0; k < fp.upstreamCells(); k++) {
            int up = bestUnclaimedUpstream(c, cur);
            if (up < 0) break;
            cur = up;
            steps++;
        }
        forkStats.placements++;
        if (steps == 0) { forkStats.rejNoUpstream++; return -1; }   // 此处没有未认领上游 ⇒ 无支流
        if (c.claimed[cur]) { forkStats.rejClaimed++; return -1; }
        if (nearRegionBorder(c.field, cur, c.rx, c.rz, params.borderDist())) { forkStats.rejBorder++; return -1; }
        if (fp.troughMode() != RiverLineParams.ForkParams.TROUGH_OFF
                && !troughCheck(c.field, cur, c.nx, c.nz, fp.troughMode())) { forkStats.rejTrough++; return -1; }
        if (insideExistingValley(c.field, c.rivers, null, cur)) { forkStats.rejValley++; return -1; }
        double wx = c.field.cellCenterX(cur), wz = c.field.cellCenterZ(cur);
        if (minDistToRivers(c.rivers, wx, wz) < fp.clearanceWu()) { forkStats.rejClear++; return -1; }
        return cur;
    }

    /** 从 idx 沿 flowTo 一路走到【已认领格】所经过的格数（含首尾）。 */
    private int rawDownhillLen(ForkCtx c, int idx) {
        int n = 1, cur = idx;
        while (n < 4096) {
            int d = c.field.flowTo(cur);
            if (d < 0) break;
            n++;
            cur = d;
            if (c.claimed[cur]) break;
        }
        return n;
    }

    /** 8 邻中【流向 cur 且未被认领】且汇流面积最大的格（确定性：面积最大，平手按扫描序）。 */
    private int bestUnclaimedUpstream(ForkCtx c, int cur) {
        int nx = c.nx, nz = c.nz;
        int ci = cur % nx, cj = cur / nx;
        int best = -1;
        double bestAcc = -1;
        for (int dj = -1; dj <= 1; dj++) {
            for (int di = -1; di <= 1; di++) {
                if (di == 0 && dj == 0) continue;
                int ni = ci + di, nj = cj + dj;
                if (ni < 0 || ni >= nx || nj < 0 || nj >= nz) continue;
                int nb = nj * nx + ni;
                if (c.claimed[nb] || c.field.flowTo(nb) != cur) continue;
                double a = c.field.accumAt(nb);
                if (a > bestAcc) { bestAcc = a; best = nb; }
            }
        }
        return best;
    }

    /** 追踪 + 提交一条分叉。要求【必须汇入已有河】，否则整条回滚（PL-RGA 式）。 */
    private boolean tryFork(ForkCtx c, int idx, int parentLevel,
                            RiverLineParams.ForkParams fp, boolean dryRun, List<int[]> next) {
        // 诊断：先量"纯沿 flowTo 到已认领格的格数"（与 traceRiver 无关的独立口径），
        // 用来把 traceRiver 回滚分成【路径太短】与【其它（交叉/自环）】两类。
        int rawLen = dryRun ? rawDownhillLen(c, idx) : 0;
        if (dryRun) forkStats.rawPathLen.add((double) rawLen);
        int step = fp.traceStep() > 0 ? fp.traceStep() : params.traceStep();
        TraceOutcome out = traceRiver(c.field, idx, step, c.claimed, c.nodeE,
                c.allSegments, c.nx, c.nz, c.rx, c.rz, Double.NaN, c.lakeAt);
        if (out == null) {
            forkStats.traceNull++;
            if (!dryRun) return false;
            if (rawLen < params.minRiverNodes()) forkStats.traceNullShort++;
            else forkStats.traceNullOther++;
            return false;
        }
        if (!out.joined) { forkStats.notJoined++; return false; }
        // feeder 语义的下限：commitRiver 允许 2 格（河头+交汇点），更短无意义
        if (out.cells.size() < 2) { forkStats.traceNull++; return false; }
        forkStats.wouldAccept++;
        if (dryRun) { forkStats.forkCells.add((double) out.cells.size()); return true; }
        CommitOut co = commitRiver(c.field, out, parentLevel + 1, c.claimed, c.nodeE, c.nodeSurf,
                c.levelAt, c.allSegments, c.rivers, c.specs, c.lakes, c.accepted, c.nx,
                Double.NaN, c.rx, c.rz, null, Double.NaN, true);
        if (co.poly() == null) return false;
        next.add(new int[]{c.rivers.size() - 1, parentLevel + 1});
        return true;
    }

    /** 折线弧长（wu）。 */
    private static double arcLength(RiverPolyline p) {
        double s = 0;
        for (int i = 1; i < p.nodes.length; i++) {
            s += Math.hypot(p.nodes[i].x() - p.nodes[i - 1].x(),
                            p.nodes[i].z() - p.nodes[i - 1].z());
        }
        return s;
    }

    /** 弧长比例 off∈[0,1] 处的点 + 切向角（FTF 约定 angle = atan2(dx, dz)）。 */
    private static double[] pointAtFraction(RiverPolyline p, double off) {
        int n = p.nodes.length;
        if (n < 2) return null;
        double total = 0;
        double[] cum = new double[n];
        for (int i = 1; i < n; i++) {
            total += Math.hypot(p.nodes[i].x() - p.nodes[i - 1].x(),
                                p.nodes[i].z() - p.nodes[i - 1].z());
            cum[i] = total;
        }
        if (total < 1e-9) return null;
        double target = Math.max(0.0, Math.min(1.0, off)) * total;
        int i = 1;
        while (i < n - 1 && cum[i] < target) i++;
        double a0 = cum[i - 1], a1 = cum[i];
        double t = (a1 - a0) < 1e-9 ? 0.0 : (target - a0) / (a1 - a0);
        double x = p.nodes[i - 1].x() + (p.nodes[i].x() - p.nodes[i - 1].x()) * t;
        double z = p.nodes[i - 1].z() + (p.nodes[i].z() - p.nodes[i - 1].z()) * t;
        double dx = p.nodes[i].x() - p.nodes[i - 1].x();
        double dz = p.nodes[i].z() - p.nodes[i - 1].z();
        return new double[]{x, z, Math.atan2(dx, dz)};
    }

    /** 父河身份：由【首节点几何】导出，而非列表下标 ⇒ 与主源循环顺序解耦（同 RiverWarp 的 salt 范式）。 */
    private static long parentId(RiverPolyline p) {
        long a = (long) Math.floor(p.nodes[0].x() * 4.0);
        long b = (long) Math.floor(p.nodes[0].z() * 4.0);
        return (a * 0x9E3779B97F4A7C15L) ^ (b * 0xC2B2AE3D27D4EB4FL);
    }

    /** 分叉随机源：(seed, rx, rz, 父河几何, depth, offset 序号, tag) → [0,1)。 */
    private double rndFork(int rx, int rz, long pid, int depth, int offIdx, int tag) {
        long salt = pid ^ ((long) rx * 0x9E3779B1L) ^ ((long) rz * 0x85EBCA77L)
                ^ ((long) depth * 0xC2B2AE3DL) ^ ((long) offIdx * 0x27D4EB2FL)
                ^ ((long) tag * 0x165667B1L);
        return NoiseUtil.hashLong01(seed, salt);
    }

    /** 候选点到既有河折线的最小距离（wu）。 */
    private static double minDistToRivers(List<RiverPolyline> rivers, double wx, double wz) {
        double best = Double.MAX_VALUE;
        for (RiverPolyline r : rivers) {
            for (int i = 0; i < r.nodes.length; i++) {
                double dx = r.nodes[i].x() - wx, dz = r.nodes[i].z() - wz;
                double d2 = dx * dx + dz * dz;
                if (d2 < best) best = d2;
            }
        }
        return rivers.isEmpty() ? Double.MAX_VALUE : Math.sqrt(best);
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
            double[] terrShort = new double[n];
            for (int i = 0; i < n; i++) terrShort[i] = rawTerrainY(rawNodes[i]);
            return new RiverPolyline(rawNodes, rawSurf, rawWid, rawDep,
                    new double[n], level, null, terrShort);
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
            // ★★★ 2026-09-19（P4）河源淡出跨度：固定 144wu → 【全长比例】★★★
            //
            //   【被修的缺陷（实测定位，这是"太直"的真正根因）】
            //   旧值 = HEAD_TAPER_NODES(6) × gridCell(24) = **固定 144wu**。
            //   而定点探针实测 块(-772,515) 那条一级河**全长仅 ≈34wu**
            //   ⇒ arc/144 ≤ 0.24 ⇒ smooth(0.24) ≈ 0.14
            //   ⇒ **headFade 最大只有 0.14 ⇒ 蜿蜒振幅被压到 14% ⇒ 整条河近乎笔直**
            //   （实测该河每段都是精确 45.0°，是一条完美斜直线）。
            //
            //   【为什么参考没这个问题】FTF 的 RiverWarp.getWarpAlpha(t) 用的是
            //   **归一化进度 t ∈ [0,1]**（lower=0.1 / upper=0.85），**与河长无关**
            //   ⇒ 无论河多短，中段都能拿到满幅蜿蜒。
            //   ⇒ 本行改为"全长的固定比例"，忠实复刻该语义。
            //
            //   【回退】把下一行换回 `HEAD_TAPER_NODES * Math.max(1.0, params.gridCell())`。
            final double meanderHeadFraction = 0.15;   // ≈ FTF lower=0.1（略大以留安全边距）
            double meanderHeadArc = Math.max(1.0, meanderHeadFraction * arc[m - 1]);
            // ★★★ 2026-09-19（P4）蜿蜒：纯正弦 → 【域扭曲（噪声）】—— 复刻 FTF RiverWarp ★★★
            //
            //   【被修的缺陷（用户实机判据 + 实测）】
            //   用户报"河网太直太规则"，给出坐标 块(-772,515)。定点探针实测该河
            //   （11 节点、level=1）**每一段都是精确 45°** —— 是一条完美斜直线。
            //   旧实现是**纯正弦**：固定波长(meanderWavelength=40) + 固定振幅(2.5)
            //   ⇒ 等距规则周期 ⇒ 观感"太规则"（且正弦本身看不出"源头"）。
            //
            //   【为什么是域扭曲而不是动量】
            //   FlowField.MOMENTUM_WEIGHT 按【上游累积量】加权 ⇒ 一级河累积量极小
            //   ⇒ 方向退化为纯 D8 ⇒ 精确 45°，**动量救不了小河流**。
            //   而 FTF 的自然观感【全部来自域扭曲】，它对所有河流一视同仁（不看汇流量）。
            //
            //   【参数为什么不照搬】FTF 的 scale=125~174wu、frequency=5e-4（波长≈2000wu）
            //   是按大陆尺度河流标定的；本项目河长常仅 34wu ⇒ 照搬会让噪声沿河近乎常数、无效果。
            //   ⇒ **复刻机制、参数按本项目尺度标定**（波长沿用 meanderWavelength）。
            //
            //   【回退】把下面 `warp.unitOffset(mx[i], mz[i])` 换回
            //     `Math.sin(2.0 * Math.PI * arc[i] / params.meanderWavelength())`。
            // ★ ★ 多级自相似（2026-09-20，忠实复刻参考 MeanderingPath 的 10 级二分）★
            //
            //   参考 bisect()：起点 2 个点 → 逐级二分，每级新中点沿垂线偏移
            //   `jitter × 段长 × 0.5`（jitter ∈ ±[0.05,0.20]）⇒ **尺度减半的同时振幅减半**
            //   ⇒ 自相似（每个尺度都有细节），这正是"看起来像采样更密"的来源。
            //   本实现用 N 个倍频域扭曲等效：波长 ×0.5、振幅 ×0.5 逐级累加
            //   （幅度归一化，使 meanderAmp 仍是总振幅 ⇒ 调参语义不变）。
            final long saltBase = Double.doubleToRawLongBits(rawNodes[0].x()) * 31L
                    + Double.doubleToRawLongBits(rawNodes[0].z()) * 17L + level;
            final RiverWarp[] warps = new RiverWarp[MEANDER_OCTAVES];
            double ampNorm = 0.0, ampO = 1.0;
            for (int o = 0; o < MEANDER_OCTAVES; o++) {
                warps[o] = new RiverWarp(seed, saltBase + o * 131L,
                        params.meanderWavelength() * Math.pow(MEANDER_OCTAVE_SCALE, o));
                ampNorm += ampO;
                ampO *= MEANDER_OCTAVE_AMP;
            }
            final double ampScale = 1.0 / ampNorm;
            // ★★★ 2026-09-20 按参考 dynamicwaters.MeanderingPath 补两处 ★★★
            //
            //   【参考原文（.class 字节码逐条还原）】
            //     · bisect()：10 级中点二分，每级新中点沿垂线偏移 jitter×段长×0.5
            //       ⇒ 【每个尺度都有细节】（自相似）；
            //     · 之后 3 遍拉普拉斯平滑 (prev+2cur+next)/4 消尖角；
            //     · 正弦蜿蜒：振幅 [3,8]/[8,20]、周期数 [0.5,2.5]；
            //     · ★ getWarpAlpha(t)： lower=0.15 / upper=0.85 两端 smootherstep 淡出
            //       ⇒ **两端位移恰为 0**。
            //
            //   【用户判据】"运动路线还是有点不自然，感觉像采样太稀少导致的" ——
            //     根因是 D8 格距 48 block；**正解不是降格距（成本 ×4 + 全套重标定），
            //     而是在折线上补【多尺度细节】**（下面 ②）。
            //
            //   ① 尾部淡出（= 参考 upper=0.85 语义）：既有的 headFade 只护河头；
            //      河尾同样不能挪 —— 河尾就是【汇合点】，挪走会直接制造"断口"。
            //   ② 次八度细尺度：单频域扭曲只有一种尺度 ⇒ 观感"缺细节"。加一个 ~1/4 波长、
            //      0.35 振幅的次八度（参考的 10 级二分里，每级振幅约减半 ⇒ 用 0.35 近似）。
            final double meanderTailFraction = 0.15;
            final double meanderTailArc = Math.max(1.0, meanderTailFraction * arc[m - 1]);
            final double tailArcTotal = arc[m - 1];
            for (int i = 0; i < m; i++) {
                int prev = i > 0 ? i - 1 : 0;
                int next = i < m - 1 ? i + 1 : m - 1;
                double tx = mx[next] - mx[prev];
                double tz = mz[next] - mz[prev];
                double tl = Math.hypot(tx, tz);
                if (tl > 1e-6) { tx /= tl; tz /= tl; }
                double nx = -tz, nz = tx;   // 左转 90° 法向
                double headFade = NoiseUtil.smooth(NoiseUtil.saturate(arc[i] / meanderHeadArc));
                double tailFade = NoiseUtil.smooth(NoiseUtil.saturate(
                        (tailArcTotal - arc[i]) / meanderTailArc));
                double fade = headFade * tailFade;
                // 多级自相似：Ω₀ + 0.5·Ω₁ + 0.25·Ω₂ + …（波长同样逐级减半）
                double shape = 0.0, ampO2 = 1.0;
                for (int o = 0; o < MEANDER_OCTAVES; o++) {
                    shape += ampO2 * warps[o].unitOffset(mx[i], mz[i]);
                    ampO2 *= MEANDER_OCTAVE_AMP;
                }
                shape *= ampScale;                       // 归一化 ⇒ meanderAmp 仍是总振幅
                double off = meanderScale * params.meanderAmp() * fade * shape;
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
        // ★ 2026-09-20 单变量开关：用于裁决"干节点（河线在·水不在）"是否由瀑布阶梯化
        //   整段覆写水面（applyWaterfalls 内 `surf[k] = stepSurf[stepIdx]`）引起 ——
        //   实测依据见 PLAN §10；默认 true（与旧行为一致）。
        double[] fall = waterfallsEnabled
                ? applyWaterfalls(rn, rs, rw, rd, params)
                : new double[rn.length];
        // 最终硬上界：水面不得高于原地形中心（防悬空水井）。
        // ★ 对瀑布 tread 安全：tread = min(覆盖范围 minTerr, minCap) ≤ 范围内每个
        //   节点的 terr，硬上界不会削平 tread。它只修重采样节点地形凹陷处的
        //   插值残留（surf 高于当地 terr 约 0.2 格的微悬河）。
        double sea = curve.seaLevelY();
        double[] terr = new double[rn.length];
        for (int k = 0; k < rn.length; k++) {
            terr[k] = rawTerrainY(rn[k]);
            if (terr[k] >= sea && rs[k] > terr[k]) {
                rs[k] = terr[k];
            }
        }
        // 沿程单调兜底（下游不抬床）。
        for (int k = 1; k < rn.length; k++) {
            if (rs[k] > rs[k - 1]) rs[k] = rs[k - 1];
        }
        // ★ 河成湖检测（2026-09-07）：低梯度连续河段展宽成过水湖。必须在全部水面
        //   调整（单调化/岸线 cap/瀑布）之后——梯度才是最终形态的梯度。
        double[] lakeLv = detectLakeReaches(rn, rs, fall);
        return new RiverPolyline(rn, rs, rw, rd, fall, level, lakeLv, terr);
    }

    // ===== 河成湖（2026-09-07）=====

    /** 低梯度判据（wu 坡度）：低于此的连续河段视为湖泊型水面。 */
    private static final double LAKE_MAX_SLOPE = 0.002;
    /** 河成湖最小长度（wu）：短于此的缓坡段保持普通河。
     *  ★ 12 而非 24（2026-09-07 实测）：本地形河面是【阶梯剖面】——坡度分布双峰
     *    （要么 <0.002 的 tread 平台、要么 ≥0.032 的跌水，中间为空），上游平段
     *    普遍只有 3~5 节点（12~20wu），24 的下限会把它们全灭、只剩海平面河尾。 */
    private static final double LAKE_MIN_LEN = 12.0;
    /** 湖段展宽倍数（相对河宽）。 */
    private static final double LAKE_WIDEN = 3.0;
    /** 单条河湖段长度占比上限：防止整条河变成一连串湖。 */
    private static final double LAKE_MAX_FRACTION = 0.5;
    /** 湖面高出海平面的下限（block）：贴着海平面平走的河尾全是"湖段"——那是河口
     *  （applyEstuary 管），不是湖（实测 9 区全部 7 个湖段湖面=海平面+0，用户：
     *  "湖泊基本全生成在海洋了"）。 */
    private static final double LAKE_MIN_ABOVE_SEA = 4.0;

    /**
     * 河成湖检测：把"低梯度 + 无跌水"的连续河段标记为过水湖。
     *
     * <p>规则：段坡度 &lt; {@link #LAKE_MAX_SLOPE} 且两端无跌水标记 → 候选；连续候选
     * 构成 reach，长度 ≥ {@link #LAKE_MIN_LEN} 且不在河头淡出带（源头泉眼不该被
     * 摊成湖）才保留；湖面 = reach【下游端】水面（沿程单调下降时即最低点）——
     * 上游侧地形若略高于湖面，湖岸自然后退，这是真实回水的形态。</p>
     *
     * @return 逐节点湖面（NaN = 普通河节点）
     */
    private double[] detectLakeReaches(MidpointDisplacement.Node[] rn, double[] rs,
                                       double[] fall) {
        int m = rn.length;
        double[] lv = new double[m];
        java.util.Arrays.fill(lv, Double.NaN);
        if (m < 3) return lv;
        // 累计弧长（用于排除河头淡出带）
        double[] arc = new double[m];
        for (int i = 1; i < m; i++) {
            arc[i] = arc[i - 1] + Math.hypot(rn[i].x() - rn[i - 1].x(),
                                             rn[i].z() - rn[i - 1].z());
        }
        double headTaperArc = HEAD_TAPER_NODES * Math.max(1.0, params.gridCell());
        int i = 0;
        double lakeLen = 0.0, riverLen = Math.max(1e-6, arc[m - 1]);
        while (i < m - 1) {
            // 找一个候选 reach 的起点
            double segLen = arc[i + 1] - arc[i];
            double slope = (rs[i] - rs[i + 1]) / Math.max(1e-6, segLen);
            boolean waterfall = fall[i + 1] > 0.0 || fall[i] > 0.0;
            if (!(slope < LAKE_MAX_SLOPE && !waterfall && arc[i] >= headTaperArc)) {
                i++;
                continue;
            }
            // 向后扩展 reach
            int start = i, end = i;
            double len = 0.0;
            while (end < m - 1) {
                double sl = arc[end + 1] - arc[end];
                double sp = (rs[end] - rs[end + 1]) / Math.max(1e-6, sl);
                if (!(sp < LAKE_MAX_SLOPE) || fall[end + 1] > 0.0 || fall[end] > 0.0) break;
                len += sl;
                end++;
            }
            if (len >= LAKE_MIN_LEN && (lakeLen + len) / riverLen <= LAKE_MAX_FRACTION
                    && rs[end] > curve.seaLevelY() + LAKE_MIN_ABOVE_SEA) {
                double level = rs[end];      // 下游端水面 = 湖面（单调下降 → 最低）
                for (int k = start; k <= end; k++) lv[k] = level;
                lakeLen += len;
            }
            i = Math.max(end, start + 1);
        }
        return lv;
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
     * 岸线钳制（★ 2026-09-22）——<b>跳过指定锚点节点</b>（湖内节点）。
     *
     * <p>用户裁定："湖泊的水面高度可不能动啊，毕竟湖泊是按最低溢出口去确定湖面高度的。
     * 只能河适应湖。" ⇒ 湖内节点的水面（= 湖 spill）是<b>不可修改的锚</b>，
     * 不能被"防漫岸"的岸高钳制压低，也不能参与上游累积取小。</p>
     *
     * <p>湖节点同时承担"水位基准"：跳过它，且<b>把它之前的节点解锁</b>
     * （不拿上游水面去压它），保证湖面原样进入折线。</p>
     */
    private void applyBankCapSkip(MidpointDisplacement.Node[] nodes, double[] surf,
                                  double[] widths, RiverLineParams params,
                                  boolean[] skip, double[] terr) {
        double seaLevel = curve.seaLevelY();
        for (int i = 0; i < surf.length; i++) {
            if (skip != null && i < skip.length && skip[i]) continue;   // 湖面锚点：不动
            if (rawTerrainY(nodes[i]) < seaLevel) {
                surf[i] = Math.min(surf[i], seaLevel);
            } else {
                double cap = bankCapY(nodes, i, widths[i], params);
                if (cap >= seaLevel) {
                    double hardCap = Math.min(cap, rawTerrainY(nodes[i]));
                    surf[i] = Math.min(surf[i], hardCap);
                }
            }
            // 上游累积取小：但若【下游紧邻是湖节点】则不取小（否则会把入湖口压低于湖面，
            // 造成"河低于湖"的错位）。湖面是锚，河只能抬到它。
            boolean nextIsLake = skip != null && i + 1 < skip.length && skip[i + 1];
            if (i > 0 && !nextIsLake) {
                // ★★★ 2026-09-24【运行取小加地形下限 —— 修"水面钉死在盆底"残留链】★★★
                //   【实测铁证（水文拓扑轮，region(-2,-2) 转储 + 脱节样例）】
                //     浅洼 breach 段：地形 174.17 → 177.91 沿程爬升（水面越鞍缓升），
                //     而水面被本方法旧的"下游不回升"链【钉死在盆底 167.12】
                //     ⇒ 计划水面比地形低 8~11 块 ⇒ 雕刻层凿山（需雕穿 121 段回升的根因）。
                //   【修法】取小不得低于 `地形 − surfaceSink − PIN_TOL`（与 ③ pinned 链
                //     同一款地形合理性约束）：地形本段抬升 ⇒ 水面允许随之抬升
                //     （= 水面越鞍缓升的真实水文形态）；微爬坡伪影（< PIN_TOL=3 块）
                //     仍被照常压平 ⇒ 原"同河内部爬坡"修复不受影响。
                //   【terr 口径】骨架路径传侵蚀后 routeTerrain（与路由同源）；null 时
                //     退回 rawTerrainY（预侵蚀，legacy 兼容）。
                //   回退：把 floor 计算删掉、恢复无条件 min。
                double terrHere = terr != null && i < terr.length ? terr[i]
                        : rawTerrainY(nodes[i]);
                double floor = terrHere - params.surfaceSink() - PIN_TOL;
                surf[i] = Math.max(Math.min(surf[i], surf[i - 1]), floor);
            }
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
        // ★★★ 2026-09-20：必须用【建流向的那张高程面】（flowElevAt）★★★
        //   原实现读 field.eAt（原始 e）⇒ 即使 FlowField 的 flowTo/accum 已改到填洼面，
        //   追踪器仍看得见原始洼地并在那里终止 ⇒ 填洼优先形同没做（实测：河尾无水 5→6、
        //   沿河线无水 118→170、内陆终止 5→5，一字未改善）。
        double curE = field.flowElevAt(cur);
        int best = -1;
        double bestSlope = 0.0; // 经 minDrop 门控的候选 slope 恒正 → 等价"严格更低"
        int minI = Math.max(0, ci - step), maxI = Math.min(nx - 1, ci + step);
        int minJ = Math.max(0, cj - step), maxJ = Math.min(nz - 1, cj + step);
        for (int j = minJ; j <= maxJ; j++) {
            for (int i = minI; i <= maxI; i++) {
                if (i == ci && j == cj) continue;
                int idx = j * nx + i;
                double e = field.flowElevAt(idx);
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
        // ★ 2026-09-20：与 downhillNeighbor 同一张面（flowElevAt），否则"有下坡"判据自相矛盾
        int best = start; double bestE = field.flowElevAt(start);
        for (int dj = -1; dj <= 1; dj++) {
            for (int di = -1; di <= 1; di++) {
                int i = ci + di, j = cj + dj;
                if (i < 0 || j < 0 || i >= nx || j >= nz) continue;
                int idx = j * nx + i;
                if (downhillNeighbor(field, idx, 1, nx, nz) >= 0 && field.flowElevAt(idx) < bestE) {
                    best = idx; bestE = field.flowElevAt(idx);
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
    /**
     * 按地形构造水面（★ 2026-09-20，PL-RGA 范式）—— 取代 PAVA 的"锚定出口 ⇒ 压平整段"。
     *
     * <p><b>为什么旧的 PAVA 必然埋地</b>：它以 1e9 权重把出口水面钉住，再要求全程非递增
     * ⇒ 只要出口显著低于沿途地形，整条河的水面就被压到出口高度（实测埋地 6~12 块、
     * 42% 节点），而落块闸门 {@code anyFill} 要求 {@code carved < waterSurface − 0.5}
     * 且挖深 ≤ depth+1 ⇒ <b>水面埋地超过约 1 块就一列水都不放</b> ⇒ "河线在·水不在"。</p>
     *
     * <p><b>本实现的性质</b>：水面 = 出口到源端的插值，插值参数取【节点自身地形】的比例
     * ⇒ 水面与当地地形保持<b>近似恒定偏移（≈ surfaceSink）</b> ⇒ 既悬空不了也埋不了。</p>
     *
     * @param rawSurf 逐节点【当地地形】Y（{@code groundYAt}）
     */
    private double[] waterSurfaceByTerrain(MidpointDisplacement.Node[] nodes, double[] rawSurf,
                                           double[] widths, double outletSurf, Double forcedSrcH) {
        int m = rawSurf.length;
        if (m == 0) return new double[0];
        double sink = Math.max(0.0, params.surfaceSink());
        double seaLevel = curve.seaLevelY();
        double[] bankCap = new double[m];
        for (int k = 0; k < m; k++) bankCap[k] = bankCapY(nodes, k, widths[k], params);

        // ① 出口水面：不低于海平面，不高于"出口地形 − sink"（否则顶破地形）
        double outGround = rawSurf[m - 1];
        double outlet = Math.min(outletSurf,
                Math.max(seaLevel, Math.min(outGround - sink, bankCap[m - 1])));
        // ② 源端水面 = 当地地形 − slopeDrop（PL-RGA source = raw − drop）；
        //    跨区续流时以 forcedSrcH 为上界（不得高于上游来水）。
        double source = rawSurf[0] - Math.max(0.0, params.slopeDrop());
        if (forcedSrcH != null) source = Math.min(source, forcedSrcH);
        if (source < outlet) source = outlet;

        double[] surf = new double[m];
        if (waterSurfaceMode == 2) {
            // 变体 2：地形包络（terr − sink 的运行最小值）
            double env = Double.MAX_VALUE;
            for (int k = 0; k < m; k++) {
                env = Math.min(env, rawSurf[k] - sink);
                surf[k] = env;
            }
        } else {
            // 变体 1：PL-RGA 地形比例插值
            double span = rawSurf[0] - outGround;
            for (int k = 0; k < m; k++) {
                double t = Math.abs(span) < 1e-9 ? 1.0
                        : (rawSurf[0] - rawSurf[k]) / span;              // 0 = 源，1 = 出口
                t = Math.max(0.0, Math.min(1.0, t));
                surf[k] = outlet + (source - outlet) * (1.0 - t);
            }
        }
        // ③ 逐节点钳制：不得高于当地河岸/地形（防漫岸）；海域节点 = 海平面；不低于海平面
        for (int k = 0; k < m; k++) {
            double cap = Math.min(bankCap[k], rawSurf[k]);
            if (surf[k] > cap) surf[k] = cap;
            if (rawSurf[k] < seaLevel) surf[k] = seaLevel;
            if (surf[k] < seaLevel) surf[k] = seaLevel;
        }
        // ④ 出口锁定（交汇零台阶 / 入湖平顺）：出口已 ≤ 岸顶 ⇒ 不会顶破地形
        surf[m - 1] = outlet;
        // ⑤ 兜底非递增（③④ 可能引入局部回升；只降不升 ⇒ 不会新增埋地）
        for (int k = 1; k < m; k++) if (surf[k] > surf[k - 1]) surf[k] = surf[k - 1];
        return surf;
    }

    /**
     * ★★★ 水面构造模式（2026-09-20，PL-RGA 重写）★★★
     *
     * <ul>
     *   <li><b>1 = PL-RGA 地形比例插值（默认）</b>：{@code surf = outlet + (source − outlet)·t}，
     *       {@code t} = 节点地形占"源→出口地形跨度"的比例。参考 PL-RGA
     *       {@code _applyRiverHeightSlopeDrop}：{@code RIVR_HGHT_SLOPE_DROP=0.005}、
     *       t 按地形比例、{@code source = raw − drop}。物理性质：<b>水面与当地地形保持近似
     *       恒定偏移（≈ surfaceSink）</b> ⇒ 既不会悬空也不会埋地。</li>
     *   <li><b>2 = 地形包络（running min of terr − sink）</b>：最简、埋地上界最硬，
     *       但凹坑段成水平池（观感略平）。</li>
     *   <li><b>0 = PAVA（旧实现，回退用）</b>：1e9 权重锚定出口 ⇒ 出口低时把整段压平
     *       ⇒ <b>实测 42%（1175/2802）节点水面被埋在地下 6~12 块</b>，是"河线在·水不在"
     *       与"整条河无水"的直接根因（用户判据："河流怎么可能突然结束"）。</li>
     * </ul>
     */
    /**
     * @deprecated ★ 2026-09-22【旧版水面模式 · 全面转向新版水文后废弃】
     *     <p>旧版按 mode 分派水面构造（含 PAVA 单调回归等）；新版水面统一为
     *     PL-RGA 地形比例插值 + drop 压缩 + 填洼面/河岸上界（见 PLAN-hydrology-flow.md §2）。
     *     保留仅供 A/B 对照，新增代码<b>不得</b>再读取本开关。</p>
     */
    @Deprecated
    public static volatile int waterSurfaceMode = 1;

    /**
     * ★★★ 2026-09-21【流体骨架路线】总开关（T1.3）★**默认开启**★★
     *
     * <p><b>为什么默认开启</b>：用户判据 —— "默认关闭我怎么在游戏里面看到呢？"。
     * 关闭 = 回到 48 块粗格 D8 + 粒子/曲流修饰的旧路线（实测平均偏角 38.9°、129° 横切）；
     * 开启 = 走【块分辨率流体物理骨架】（{@link com.geogenesis.worldgen.hydrology.flow.TerrainFlowSim}），
     * 路线按构造物理正确（横切 0 / 上坡 0）。</p>
     *
     * <p><b>一键回退</b>：{@code RiverLineNetwork.flowSkeletonRouting = false;}
     * ⇒ 逐位回到旧行为。</p>
     *
     * <p><b>自动兜底</b>：某 region 若骨架建不出任何河（采样失败/纯海区/无达标集水），
     * 自动回落到旧链路 ⇒ 不会出现"整片没有河"的事故。</p>
     *
     * <p><b>★★★ 为什么默认关闭（2026-09-21 实测，勿贸然改 true）★★★</b>
     * 骨架路线目前<b>绕过了整条旧追踪链路</b>，因此下列既有能力<b>尚未接入</b>：</p>
     * <ul>
     *   <li><b>支流分叉</b>（{@code fork}，ForkFeasibilityProbe 实测 {@code wouldAccept=0}
     *       ⇒ 门禁 FAIL）—— 分叉逻辑挂在 traceRiver 上；</li>
     *   <li>瀑布阶梯化 / 河口湾 / 河成湖 等 trace 内后处理；</li>
     *   <li>跨区出口种子延续（接缝目前靠 margin 缓解，不如旧链路严）；</li>
     *   <li>质量指标：审计实测沿河线无水 <b>18.8%</b>、沿程抬升 <b>23.2%</b>
     *       （旧链路为 0 / 0）。</li>
     * </ul>
     * <p><b>★ 2026-09-21 用户裁定：默认 true（新版直接可见，否则无法实测）★</b>
     * ——"我都说了要直接显示新版的，不然我怎么测试？"</p>
     * <p>旧链路的那些能力（分叉/瀑布/河口…）<b>是否要搬进新路线、搬哪些，
     * 由实测决定，不预设</b>（用户："旧版的功能你直接放新版可能不合适，需要实测才知道"）。</p>
     * <p>一键回退：{@code flowSkeletonRouting = false}（或 config
     * {@code Hydrology.hydrologySkeletonRouting=false}）⇒ 逐位回到旧行为。</p>
     */
    // ★★★ 2026-09-23【默认关闭 —— 修“湖泊被改坏”】（用户判据 + git 铁证）★★★
    //   HEAD（2026-09-19 完成的那套湖）里 `buildSkeletonRivers` / `flowSkeletonRouting`
    //   【出现次数 = 0】——整套流体骨架路线是本会话新增的，它默认开启后【顶替】了
    //   09-19 已修好的河湖生成链路（入湖终止 / 溢出续流 / 湖拓扑），改用从 LakeNode
    //   轮廓外扩得到的 `probLake` 掩码近似 ⇒ 湖泊区域随之变形（用户实测截图）。
    //   ⇒ 在湖泊恢复并验收通过之前，本开关保持 false（= 走 09-19 那套）。
    //   重新启用前必须先让骨架路线接上【同一套湖拓扑】（PLAN T1 的后续工作）。
    /** 唯一生产水文管线：TerrainFlowSim 完整生命周期。历史 false 回退已停用。 */
    public static volatile boolean flowSkeletonRouting = true;

    /**
     * 骨架模拟格距（块）。
     *
     * <p>★ 2026-09-21 实测标定：16 ⇒ 河道呈【直角折线】（生产图 prev.png 实测：节点间
     * 为 16 块的水平/垂直线段，观感比流体模拟图差）。8 ⇒ 折角细化一倍、成本 ×4
     * （region 160² = 25.6k 格，仍在百毫秒量级）。</p>
     */
    public static volatile int SKELETON_CELL_BLOCKS = 8;

    // ★ 2026-09-23【改为可调 + 新增 4 档】—— 用户判据："骨架路线达不到模拟现实流体运动
    //   路线"，图上折线每 8 块就出现一次直角/折角（D8 量化）⇒ 谷底走向被粗格吃掉。
    //   4 块 ⇒ 折角细化一倍（观感更接近流体流线），代价：模拟格数 ×4、角点采样 ×4
    //   （采样是已知瓶颈，探针可接受；生产若启用需先按 SKELETON_CELL_BLOCKS 复测耗时）。
    //   设值入口：探针参数 "cell4"（见 WaterViewProbe）。回退：保持 8。

    /**
     * ★ 2026-09-22【侵蚀后地形采样器】—— 河-湖水位对齐用。
     *
     * <p>雕刻侧湖面 = {@code LakeNode.erodedWaterLevel(erodedY)} =
     * <b>min(无侵蚀 spill, 侵蚀后坎高)</b>（短板水位，低于或等于 spill）。
     * 河尾若按 {@code height}（无侵蚀 spill）抬升 ⇒ 会比真实湖面高一截 ⇒
     * 3D 里出现"河停在坎上、湖在下面"的台阶（用户实测截图）。
     * 本采样器由生产接线注入（{@code terrain.sampleWu(...).height}），
     * 使河尾水位与湖面【同一口径】。</p>
     */
    public static volatile java.util.function.ToDoubleBiFunction<Double, Double> erodedYSampler = null;

    // ★★★ 2026-09-23【P2-2 块分辨率湖盆连通掩码】★★★
    //   湖连通性（computeFlood BFS）的格距（wu）。2.0 = 1 块（hs=2 生产默认），
    //   由 HydrologyExperimentEngine 接线时按 horizontalScale 覆写。
    //   回退到旧 6wu 粗格：置 lakeBasinFloodGrid = 6.0。
    public static volatile double lakeBasinFloodGrid = 2.0;
    // ★ P2-2 总开关：湖命中/管辖域扩展到【块级连通洼地掩码】
    //   （掩码 = 低于水位 ∧ 与盆底连通，构造保证，治直边/没填到山边/假洼地）。
    //   false = 逐位回到"轮廓方格 + domTol + 6wu BFS"旧行为（探针/雕刻全链路）。
    public static volatile boolean lakeBasinHits = true;

    // ★★★ 2026-09-23【"河成湖"（2026-09-07）关闭 —— 用户架构裁定】★★★
    //   用户规则："湖泊生成要优先于河流，湖泊确定才能做河流"。
    //   但 detectLakeReaches（低梯度河段自造平湖面，lakeNodes=null）是【由河造湖】，
    //   且 sampleRegion 会把该段转成湖命中 + 展宽 LAKE_WIDEN 倍 ⇒ 河谷被铺成
    //   一大片水平"湖"水（实测 region(0,-1)：100 节点河中 5 个节点 lakeLevel=171.23
    //   ⇒ 26,137 格平坦水体 = 用户红圈"河水被判成湖"）。
    //   false = 只有【锚定在真实湖节点】的河段（lakeNodes != null，= 2026-09-22
    //           入湖锚点段，河尾接真湖）才转湖命中；"河自造湖"按普通河处理。
    //   回退：置 true（逐位回到旧行为）。
    public static volatile boolean riverMadeLakes = false;

    /**
     * ★★★ 2026-09-22【侵蚀增量提供者】—— 修"河不贴谷"（用户实测）★★★
     *
     * <p><b>根因</b>：骨架路由场用的是 {@code heightFromE(terrainEQuick)} = <b>侵蚀前</b>地形；
     * 而玩家看到的谷地是<b>侵蚀后</b>刻出来的沟壑 ⇒ 两套地形谷位不同 ⇒ 河看起来
     * "无视地形、走山脊"。</p>
     *
     * <p><b>接口</b>：入参 (wuX, wuZ)，返回【e 单位】侵蚀增量；<b>返回 NaN 表示不可知</b>
     * （tile 未缓存）。生产接线注入 {@code CellGenerator::peekErosionDeltaE} ——
     * 该接口【绝不触发侵蚀 tile 冷生成】（否则世界生成会卡死）。</p>
     *
     * <p><b>退化行为</b>：NaN ⇒ 该点按无侵蚀处理（与当前行为一致，不会更差）。</p>
     */
    public static volatile java.util.function.ToDoubleBiFunction<Double, Double> erosionDeltaProvider = null;

    /**
     * ★★★ 2026-09-22【侵蚀感知路由开关】★**默认 false**★ —— 确定性铁律约束 ★★★
     *
     * <p><b>为什么默认必须关</b>：可行实现只有两条，都有限制：</p>
     * <ul>
     *   <li>{@code peekErosionDeltaE}（非阻塞、不触发 tile 生成）→ <b>结果依赖 tile 缓存
     *       状态</b> ⇒ 同 seed 不同探索顺序会得到不同河网 ⇒
     *       实测门禁 {@code runHydrologyDeterminismProbe} <b>FAIL</b>（本项目铁律：
     *       输出不得依赖生成顺序/缓存状态）。</li>
     *   <li>{@code erosionDeltaE}（阻塞、确定性）→ 会<b>同步冷生成侵蚀 tile</b>
     *       （实测 400~719 ms/个）⇒ 建网期卡死世界生成（项目里 {@code erosionRoutingAdaptive}
     *       之所以默认关，正是这个原因）。</li>
     * </ul>
     * <p>⇒ 默认关闭（路由场 = 侵蚀前地形，逐位确定）；开启需显式承担上述代价之一。
     * 真正的正解是【让侵蚀与水文共用同一张已生成的侵蚀场】（PLAN 的后续工程）。</p>
     */
    public static volatile boolean erosionAwareRouting = false;

    /**
     * ★★★ 2026-09-23【骨架路由地形口径：侵蚀后】★ 默认 false ★★★
     *
     * <p>{@code false} = 旧行为：角点高度用 {@link #groundYAt}（= {@code heightFromE(terrainEQuick)}，
     * <b>侵蚀前</b>）。该口径的 javadoc 假设"与最终地形（sampleWu）的差异仅剩侵蚀 delta
     * （<b>通常很小</b>，不影响视觉嵌入感）"。</p>
     *
     * <p><b>该假设被用户实测图推翻</b>：图上侵蚀沟壑深达数十块，而河线走的是侵蚀前
     * 排水格局 ⇒ 出现"<b>两套互不相干的河谷网</b>"，河既看不出贴着山体沟壑，也不贴湖
     * （用户判据："河流看不到湖和山体那种感觉"）。</p>
     *
     * <p>{@code true} 且 {@link #erodedYSampler} 非 null ⇒ 角点预采样与逐节点地形
     * 一律改用 {@code erodedYSampler}（= {@code terrain.sampleWu(...).height}，侵蚀后、
     * 雕刻前）⇒ 路由场与【图上可见的地形】同源。</p>
     *
     * <p><b>⚠ 使用前提（必读）</b>：{@code sampleWu} 内部会 {@code getOrGenTile} ——
     * 若首次取 tile 发生在 <b>chunk 生成内部</b>，会命中"同步中断 ⇒ delta=0"语义
     * ⇒ 路由场随触发顺序变化（<b>破坏确定性</b>）。故探针侧必须<b>先预热</b>窗口+margin 的
     * 侵蚀 tile 再构建河网（见 {@code WaterViewProbe} 的 "eroded" 分支）。</p>
     *
     * <p>生产默认 false（确定性铁律）；仅诊断出图启用。回退：探针去掉 {@code eroded} 参数。</p>
     */
    public static volatile boolean skeletonRouteOnEroded = false;

    /**
     * ★★★ 2026-09-24【路由专用高度采样器（侵蚀后）】★★★
     *
     * <p>比 {@link #erodedYSampler}（= {@code sampleWu().height}，走完整 sample）便宜：
     * 只走 {@code heightFromE(softCapLandE(e + delta))} 这条高度链，跳过 discharge 采样、
     * 地形重分类、坡度 4 次 tile 采样（见 {@code CellGenerator.erodedHeightForRouting}）。</p>
     *
     * <p>接线：{@code HydrologyExperimentEngine} 注入
     * {@code (a,b) -> terrain.erodedHeightForRouting(a,b)}。为 null 时自动退回
     * {@link #erodedYSampler}（行为不变）。</p>
     */
    public static volatile java.util.function.ToDoubleBiFunction<Double, Double> routeHeightSampler = null;

    /**
     * ★★★ 2026-09-22【湖的最终采用水位】—— 复刻【生产完整两段链】★★★
     *
     * <p><b>用户的物理语义（本轮裁定，作为验收标准）</b>：</p>
     * <ul>
     *   <li>湖 = 纯填充水，【不雕刻地形】（雕刻只属于河流）—— carver 已如此（carved=original）；</li>
     *   <li>湖面 = 地形的【最低溢出高度】，溢出口<b>不一定只有一个</b>（多个口取最低）——
     *       即 minimax 逃逸语义；</li>
     *   <li>河流首尾只有：源头/湖泊溢出（起）、湖泊/海洋水面（止）。</li>
     * </ul>
     *
     * <p><b>生产链有两段，缺一不可（这就是此前"旧版套新版"的病灶）</b>：</p>
     * <ol>
     *   <li><b>carver 段</b>（{@code HydrologyBlockCarver} L268-312）：
     *       {@code spill = erodedWaterLevel} → {@code computeFlood} → {@code floodLevel} 覆盖；</li>
     *   <li><b>terrain 段</b>（{@code GeoGenesisTerrain} L686-698）：
     *       {@code esc = escapeWaterLevel(侵蚀后雕刻前 sampleWu, 24, 6, spill)} ⇒
     *       {@code spill = min(spill, esc)}。</li>
     * </ol>
     * <p>段②的 minimax 正是"多个溢出口取最低"：从盆底向任意方向扩张，cost=路径最高地面，
     * 到低处（海/更低洼地）即逃逸高度。曾两次只复刻其中一段（先只 escape、后只 floodLevel）
     * ⇒ 水位口径仍差 ⇒ 河尾"命中在·水不在"。</p>
     *
     * <p>⚠ {@code computeFlood}/{@code escapeWaterLevel} 均自带缓存（每湖一次，幂等）——
     * 折线侧先算，carver/terrain 复用同值，构造上同源。</p>
     */
    private double finalLakeLevel(RiverLineRegion.LakeNode ln) {
        if (ln == null) return Double.NaN;
        // 骨架合成湖的 height 已是 TerrainFlowSim 的最低溢口水位；其轮廓也是同一次
        // 模拟的 res.lake 掩码。不得再用另一套 minimax/洪泛算法改水位或扩大湖域。
        if (ln.exactOutline) return ln.height;
        java.util.function.ToDoubleBiFunction<Double, Double> es = erodedYSampler;
        // 上界 = 无侵蚀 spill（湖面物理上不可能高于它）
        double upper = Double.isNaN(ln.height) ? ln.erodedSpill : ln.height;
        if (es == null) {
            return Double.isNaN(ln.erodedSpill) ? ln.height : Math.min(ln.erodedSpill, upper);
        }
        // ★★★ 2026-09-22【统一到 minimax 逃逸】（与 HydrologyBlockCarver.LAKE_MINIMAX_LEVEL 同源）★★★
        //   ⚠ 已删除的旧写法：`spill = erodedWaterLevel` → `computeFlood/floodLevel` ——
        //     rim 圈 e/h 口径混用（fillEAt 选点、侵蚀地形取高）⇒ 取到远处深谷 ⇒ 水位被
        //     错误压低（实测 151.89 « 地形 167.44）⇒ 河尾/湖列普遍判干。
        //   正解：escapeWaterLevel（多溢出口取最低）+ 无侵蚀 spill 封顶。
        try {
            double esc = ln.escapeWaterLevel(es, 24.0, 6.0, upper);
            if (!Double.isNaN(esc)) return Math.min(upper, esc);
        } catch (RuntimeException ignore) {
            // 逃逸求解失败 ⇒ 退回无侵蚀 spill 上界
        }
        return upper;
    }

    /** 骨架路线日志（诊断）：确认【实际走的是哪条路线】+ 静默回退原因。 */
    private static final org.apache.logging.log4j.Logger LOGGER =
            org.apache.logging.log4j.LogManager.getLogger("geogenesis");
    private static final java.util.concurrent.atomic.AtomicInteger skelOkCount =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger skelEmptyCount =
            new java.util.concurrent.atomic.AtomicInteger();
    private static final java.util.concurrent.atomic.AtomicInteger skelLogCount =
            new java.util.concurrent.atomic.AtomicInteger();
    /** ★ 2026-09-24 水文生命周期统计日志计数（前 12 次打印）。 */
    private static final java.util.concurrent.atomic.AtomicInteger skelLifeLogCount =
            new java.util.concurrent.atomic.AtomicInteger();

    /** 骨架模拟的外扩余量（块）：保证跨区河流能追到边界外，接缝处两条河自然对接。 */
    public static final int SKELETON_MARGIN_BLOCKS = 128;

    /**
     * ★ 2026-09-20【最小可渲染断面】总开关：给河宽/河深设 1 块栅格的下限。
     *
     * <p><b>⚠ 实测结论：默认关闭（false）</b>。理由（单变量实测，勿重犯盲改）：</p>
     * <pre>
     *   开启后河头断面确实被抬到可渲染尺寸（实测 halfW 0.65→1.00、depth 0.48→0.90），
     *   但 HydrologyPhysicsProbe 的【沿河线无水节点 = 174 一字未变】
     *   ⇒ 干节点与断面尺寸【无关】。
     *   进一步实测：干节点的 Cell.riverSurfaceY = 110.1 = "无河流时的海平面默认值"
     *   ⇒ 真正成因是【雕刻侧根本没拿到这条河的样本】(HydrologyBlockCarver 的
     *   sampleBlockAll/sampleRegion 未命中)，而不是断面太细。
     *   ⇒ 按"无实测收益的行为改动不保留"的纪律，默认关闭，代码留作线索与后续复用。
     * </pre>
     */
    public static volatile boolean minSectionEnabled = false;

    /**
     * 瀑布（跌水）阶梯化开关（2026-09-20，默认 true = 旧行为）。
     *
     * <p>保留为开关的目的：单变量裁决"干节点（河线在·水不在）"的成因 ——
     * {@code applyWaterfalls} 会把跌水段的 {@code surf} <b>整段覆写</b>为阶梯水位
     * （{@code surf[k] = stepSurf[stepIdx]}），若该阶梯水位低于当地地形太多，
     * 落块闸门（{@code carved < waterSurface − 0.5} 且挖深 ≤ depth+1）就会拒绝放水。</p>
     */
    public static volatile boolean waterfallsEnabled = true;

    /**
     * 最小可渲染半宽（block）：&lt; 1 块的半宽使块心可能落在河道外 ⇒ 栅格化不出水。
     * 1.0 ⇒ 全宽 2 块（= {@code params.minWidth()} 全宽档），保证至少一列命中。
     */
    public static final double MIN_RENDER_HALF_WIDTH = 1.0;

    /**
     * 最小可渲染水深（block）：落块闸门要求 {@code carved &lt; waterSurface − 0.5}
     * ⇒ 水深必须 &gt; 0.5，取 1.0 留一倍余量（含床面抬升/阶梯化的误差）。
     */
    public static final double MIN_RENDER_DEPTH = 1.0;

    private double[] applyRiverHeightSlopeDrop(MidpointDisplacement.Node[] nodes,
                                               double[] rawSurf, double[] widths,
                                               double outletSurf, RiverLineParams params,
                                               Double forcedSrcH, boolean reachedOcean) {
        if (waterSurfaceMode != 0) {
            return waterSurfaceByTerrain(nodes, rawSurf, widths, outletSurf, forcedSrcH);
        }
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
     * 河面世界 Y（= 当地地表谷底）。<b>public：探针需按同一口径采样真实地形</b>
     * （湖泊填洼层必须用真实高程，不能用选线用的 routingE）。
     *
     * <p>用 terrainEQuick 派生（与 D8 汇流场同源、确定、零侵蚀 tile）→ 保证 region 冷构建亚毫秒级。
     * 与最终地形（sampleWu）的差异仅剩侵蚀 delta（通常很小），不影响视觉嵌入感。</p>
     */
    public double groundYAt(double wx, double wz) {
        // ★★★ 2026-09-14 性能修复：坐标级缓存（与 routingE 同一原因，见 eCache 注释）★★★
        //   groundYAt 在 build() 内对【整片网格】被调用一次（computeFill 填洼层，
        //   region 覆盖 1280wu ÷ gridCell 24 ⇒ ~2,916 格），且 9 个 region 高度重叠
        //   ⇒ 与 routingE 叠加共 ~5.2 万次采样。经此缓存后，同一坐标只算一次。
        //   纯函数（terrainY 在游戏接线中是 heightFromE(terrainEQuick) —— 只依赖坐标）
        //   ⇒ 命中与未命中等价，输出逐位一致。
        long key = ((long) Math.floor(wx) << 32) ^ ((long) Math.floor(wz) & 0xFFFFFFFFL);
        Double hit = gyCache.get(key);
        if (hit != null) return hit;
        double v = terrainY != null ? terrainY.yAt(wx, wz)
                : curve.heightFromE(eSampler.eAt(wx, wz));
        if (gyCache.size() < E_CACHE_MAX) gyCache.put(key, v);
        return v;
    }

    /** groundYAt 的坐标级缓存（见 {@link #groundYAt} 注释）。 */
    private final Map<Long, Double> gyCache = new ConcurrentHashMap<>();

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
        // ★★★ 2026-09-20 【曾尝试并已撤销】采样处"汇合取并集"（SDF union）★★★
        //
        //   【动机】残留 3 例跨 region 的"粗支流接进细下游"（enforceConfluenceMonotonic
        //   只能看本区河道）⇒ 想在采样处（sampleAll 本就扫 3×3 region）取并集收尾。
        //
        //   【实测：零效果，故删除实现】（不把未验证生效的东西留在代码里当"死旋钮"）
        //     · 窗口 (-840,-400) r=140：并集开/关 水体 **7510 / 9.51% / 漏灌 2784 逐位相同**；
        //     · 两个【真实病例坐标】定向 A/B：
        //         block(-1927,-912)  开/关均 5947（63.21%）
        //         block(-336,-1248)  开/关均 3920（41.66%）
        //     ⇒ 在病例处同样逐位相同。
        //
        //   【为什么本来就不需要它 —— 这条比"修好它"更值钱】
        //     **可见水体本来就是并集**：每条河道各自雕刻自己的槽，汇合处的水天然是两槽
        //     的并集（carver 的 union 是结构性的，不靠采样层相加）。
        //     残留的 3 例"粗接细"只是【节点宽度数据】上的不单调，**不是看得见的水**。
        //   ⇒ 记为已知边界，不再为它加采样层分支（避免无谓的热路径开销与复杂度）。
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
        // ★★★ 2026-09-22【湖域优先 = 湖先于河】（用户裁定）★★★
        //   用户："湖泊应该在河流生成前。因为河流生成后会改变地形。"
        //   跨 region 权威过滤：河在 A 区、湖在 B 区时，A 的河谷壁带照样漏进 B 的湖域。
        //
        //   ⚠⚠ 2026-09-22【回归修复·勿重犯】⚠⚠
        //   初版条件写成"有任一湖命中 ⇒ 剥全部河命中"—— 而湖命中的发出条件带
        //   domTol=48wu【容差】（湖岸羽化带）⇒ 容差带内的【真实河列】被误杀：
        //   河命中没了（riverType=0）、又不在湖的 inFlood 内（湖分支不出水）
        //   ⇒ 两边都不落水。实测 1400² 审计：沿河线无水 0.5% → **41.4%**、
        //   河尾无水 4/11 → 291 (20.2%)（用户判据：河流路线与河湖连接必须正确）。
        //   正解：过滤判据收窄到【真实湖域】（inDomain margin=0，跨 region 检查）——
        //   只有真正属于湖面管辖的列才"湖先于河"；48wu 容差带保留双命中，
        //   交还 carver 的最近命中二选一（原语义，河列不被误杀）。
        boolean inRealLakeDomain = false;
        for (int dz = -1; dz <= 1 && !inRealLakeDomain; dz++) {
            for (int dx = -1; dx <= 1 && !inRealLakeDomain; dx++) {
                inRealLakeDomain = inLakeDomainAt(region(rx + dx, rz + dz), wx, wz);
            }
        }
        // ★★★ 2026-09-22【湖优先判据 = 与湖命中发出同源】（量测驱动）★★★
        //   量测（WaterViewProbe 成因分解）：湖内"该有水却无水"20081→12702 后仍有残留，
        //   且残留格【同时有河命中】⇒ carver 的 `inRiverChannel`（最近河距 ≤ 河宽）为真
        //   ⇒ 走【河分支】不灌水。根因：湖命中的发出域比本过滤的 `inRealLakeDomain`
        //   （margin=0）宽 ⇒ 容差带里河命中存活并胜出。
        //   正解：只要本列【存在任何湖命中】，就剥掉河命中 ⇒ 湖必然胜出。
        //   ⚠ 这条正是早前"有任一湖命中⇒剥全部河命中"的写法 —— 当时引发 41.4% 干节点，
        //     因为**湖水位是错的**（151.89）。现已修复（LAKE_MINIMAX_LEVEL + 等高线判水）
        //     ⇒ 前提改变，故重新启用，并以开关控制可回退。
        boolean anyLakeHit = false;
        for (RiverLineHit h : hits) {
            if (h.isLake()) { anyLakeHit = true; break; }
        }
        if ((lakePriorityAnyHit && anyLakeHit) || inRealLakeDomain) hits.removeIf(h -> !h.isLake());
        hits.sort((a, b) -> Double.compare(a.distToCenter(), b.distToCenter()));
        return hits;
    }

    /**
     * 最近河线的水平距离（wu）——<b>只读查询</b>，不改动河网生产路径。
     *
     * <p><b>为什么需要它</b>：群系分类走快速路径 {@code GeoGenesisTerrain.sampleCellLight}
     * （为了把建世界从 7.5 分钟压到秒级，刻意跳过侵蚀与雕刻计划），因此拿不到
     * {@code riverNetDischarge}/{@code isLake} 这些"雕刻后才写"的字段。结果是任何
     * 依赖"离水多远"的群系规则（河流绿洲、湖效应、河岸带）<b>只在预览里生效、
     * 游戏里永远不触发</b>，违反本项目的"预览 = 游戏"原则。本方法让两条路径
     * 读到同一份数据。
     *
     * <p><b>实现</b>：直接委托 {@link #sample} 取最近命中。这是刻意的 ——
     * {@code sampleRegion} 的几何语义相当复杂（河段 + 湖盆逐格轮廓 + 河成湖展宽 +
     * 跌水端帽豁免…），另写一份"最小距离"必然与雕刻器分叉（实测自写版本漏掉
     * {@code RiverLineRegion.lakes}，19/245 例比雕刻器远最多 191 wu）。
     * 委托即单一事实来源：绿洲看到的水，就是雕刻器刻出来的水。
     *
     * @return 最近水体（河或湖）中心的距离（wu）；影响范围内无水体返回
     *         {@link Double#POSITIVE_INFINITY}
     */
    public double distanceToWater(double wx, double wz) {
        RiverLineHit hit = sample(wx, wz);
        return hit == null ? Double.POSITIVE_INFINITY : hit.distToCenter();
    }

    /**
     * ★★★ 2026-09-19（P1/P2）<b>流向动量混合权重</b> —— 总开关，唯一改点 ★★★
     *
     * <p>转交给 {@link com.geogenesis.worldgen.hydrology.flowaccum.FlowField#MOMENTUM_WEIGHT}
     * （见那里对"依据 SimpleHydrology 的 momentumTransfer"的完整说明与实测数字）。</p>
     *
     * <table border="1">
     *   <caption>实测（{@code runFlowDirectionHistogramProbe}，seed 9139912035078620160 @ wu(-754,-670)±256，cell=24）</caption>
     *   <tr><th>权重</th><th>成河格方向落在 45° 整数倍(±1°) 的占比</th></tr>
     *   <tr><td>0.0（纯 D8）</td><td><b>100.0%</b> ← 这就是"河网太直太规则"的直接证据</td></tr>
     *   <tr><td>0.30</td><td>29.2%</td></tr>
     *   <tr><td>0.45（本值）</td><td>18.8%</td></tr>
     *   <tr><td>0.60</td><td>6.3%</td></tr>
     * </table>
     *
     * <p><b>回退</b>：置 {@code 0.0} ⇒ {@code flowTo}/{@code accum}/{@code tracePath} 全部
     * 与旧行为<b>逐位一致</b>（实测 flowTo 不一致 = 0、accum 不一致 = 0）。</p>
     *
     * <h2>★★★ 2026-09-20 已置 0.0（实机否决）—— 务必先读这段再考虑改回去 ★★★</h2>
     *
     * <p><b>用户实机判据（截图）</b>："河流会突然变宽，并且运动路线也奇怪" —— 一条主河
     * 贴着一座圆形山丘绕了三面又绕回来，围出一个巨大的马蹄形闭环。</p>
     *
     * <p><b>量化（{@code runRiverEndProbe}，3×3 region，seed 9139912035078620160）</b>：
     * 闭环判据 = "沿程走了很远、直线距离却极近"（沿程/直线 比 ≤ 0.35 且沿程 ≥ 240 block）：</p>
     *
     * <table border="1">
     *   <caption>动量权重对【形态】的影响（同一窗口同一 seed）</caption>
     *   <tr><th>指标</th><th>0.45（本常量原值）</th><th>0.0（现值）</th></tr>
     *   <tr><td>★马蹄形闭环河</td><td><b>5 条</b>（比值 0.04~0.15，例：沿程 1987 block / 直线 179 block）</td><td><b>0 条</b></td></tr>
     *   <tr><td>★汇合断口（河尾→它河最近节点）p50</td><td><b>10 block</b>（&gt;12 block 占 50%）</td><td><b>2 block</b>（&gt;12 block 仅 2.8%）</td></tr>
     *   <tr><td>★内陆悬空河尾</td><td><b>10~11（18%）</b></td><td><b>1（1.5%）</b></td></tr>
     *   <tr><td>成功汇入它河的河</td><td>17~20</td><td><b>36</b></td></tr>
     * </table>
     *
     * <p><b>机制</b>：节点位置由"沿连续流向场积分的粒子"给出（{@code commitRiver} 内
     * {@code particlePos} 分支）。当流场含动量时，粒子可能<b>在格内打转</b>并越漂越远 ——
     * 而<b>格路径是干净的</b>（所以 {@code selfApproach}/{@code seen} 这类格级守卫
     * <b>完全拦不到</b>，实测加了半径 2 的守卫闭环数一条没少）。后果三连：
     * ① 折线绕圈（闭环）；② 节点被甩离真实交汇格（断口 10~62 block）；
     * ③ 河尾落在没有可见河道处（悬空）。</p>
     *
     * <p><b>代价与替代</b>：置 0 后节点回到【格心】⇒ 折线先天是 45° 阶梯（上表 100%），
     * 即 2026-09-19 想解决的"河网太直太规则"会回来。<b>正解不是恢复本常量，而是给粒子
     * 加"不得偏离目标格"的硬约束</b>（例如把每步位移钳到 ≤ 半格、或对 {@code g} 设下限
     * 使目标方向权重恒 ≥ 0.5），使"自然曲率"与"不打转"同时成立 —— 该议题另立。</p>
     */
    static final double FLOW_MOMENTUM_WEIGHT = 0.0;

    /**
     * ★ 2026-09-19 <b>诊断专用</b>：湖域外扩容差（wu）覆盖。
     *
     * <p>{@code < 0} ⇒ 用生产默认 {@code gridCell * 2.0}（<b>生产行为完全不变</b>）。
     * 仅供 {@code LakeDomainSweepProbe} 扫描"收窄湖域"的代价：</p>
     * <ul>
     *   <li>收窄过多 ⇒ 域外列落回河分支 ⇒ 不灌 ⇒ <b>域边界重新变成硬边</b>
     *       （正是 2026-09-19 修掉的那条直线）</li>
     *   <li>收窄不足 ⇒ 湖分支继续抑制河谷塑形（§1.18 实测 1497 列）</li>
     * </ul>
     * <p>⚠ 生产代码不得读写本字段；探针跑完须复位为 {@code -1}。</p>
     */
    public static double domainToleranceOverride = -1.0;

    /**
     * 单 region 上 valley 半径内"每段独立命中"列表（供雕刻器 smooth-min 合并，
     * 根治属主在段间切换产生的放射折痕）。仅保留 dist ≤ valleyReach 的段——
     * 其 carve 才可能非零，远处段不影响 smin（carve=original）。
     */
    /** 本列（wu）是否落在该 region 任一【生产湖】的域内（湖接管区）。 */
    private boolean inLakeDomainAt(RiverLineRegion r, double wx, double wz) {
        if (r.lakes == null || r.lakes.isEmpty()) return false;
        for (RiverLineRegion.LakeNode ln : r.lakes) {
            if (ln.hasOutline()) {
                // ★ R-C1 F1e【骨架模式湖域 = 块掩码独占】（2026-09-23）：
                //   轮廓并集内、掩码外的岸上格若仍算"湖域"⇒ 河命中被抑制
                //   （sampleRegion 见湖域即 skip）⇒ 只剩湖等高线判水 ⇒ 地形高于
                //   湖面即必干 —— 审计残留干尾 5 例全在 r#26 岸带实锤。
                //   骨架模式：掩码外放行河命中（河雕刻下切出水=入湖口）。
                //   旧路线（flowSkeletonRouting=false）：P2-2 行为逐位不变。
                if (flowSkeletonRouting) {
                    if (inBasinMask(ln, wx, wz)) return true;
                } else if (ln.inDomain(wx, wz, 0.0) || inBasinMask(ln, wx, wz)) {
                    return true;
                }
            } else {
                double dx = wx - ln.x, dz = wz - ln.z;
                if (dx * dx + dz * dz <= ln.radius * ln.radius) return true;
            }
        }
        return false;
    }

    /**
     * ★ 2026-09-23【P2-2】湖盆连通掩码查询（命中发射 / 河剥除共用）。
     *
     * <p>开关关闭、无轮廓（旧式圆盘湖）或侵蚀采样器未接线（探针旧基线）时
     * 一律 false ⇒ 调用方退回纯 inDomain 语义。掩码惰性建一次/湖
     * （{@link RiverLineRegion.LakeNode#inBasinFlood}）。</p>
     */
    private boolean inBasinMask(RiverLineRegion.LakeNode ln, double wx, double wz) {
        if (!lakeBasinHits || !ln.hasOutline()) return false;
        java.util.function.ToDoubleBiFunction<Double, Double> es = erodedYSampler;
        if (es == null) return false;
        double lv = finalLakeLevel(ln);
        if (Double.isNaN(lv)) return false;
        if (!ln.inBasinFlood(es, lv, lakeBasinFloodGrid, params.gridCell(), wx, wz)) {
            return false;
        }
        // ★ R-C1 F1h【骨架模式：掩码要求可蓄水深度 ≥0.5】（决策链实锤死区）：
        //   水深 ∈ [0.05, 0.5) 的格被洪泛算进连通区，但落块等高线 h<level−0.5
        //   造不出水块 —— 若它还在湖域，sampleAll 会剥掉河命中 ⇒ 河也发不出
        //   ⇒ 两边都无水（样例 (-404,-484)：深 0.36，剥河后 nearestRiverDist=Infinity）。
        //   掩码收紧到 ≥0.5 ⇒ 浅滩格归河 ⇒ 河雕刻下切入湖口出水。
        //   仅 flowSkeletonRouting=true 生效 —— 旧路线的 P2-2 验收数字逐位不变。
        //   回退：删本段或 flag=false。
        if (flowSkeletonRouting && lv - es.applyAsDouble(wx, wz) < 0.5) {
            return false;
        }
        return true;
    }

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
                // ★ 端点索引必须取段自身（2026-09-07，修湿列水面台阶的真正根因）：
                //   原写法 f = i + t 再 floor(f)，当投影恰好落在段末节点（u≥1 → t=1）
                //   时 f=i+1 → i0/i1 被推到【下一段】→ seg[i] 在自家端帽处算出
                //   lerp(surf[i+1], surf[i+2], 1) = surf[i+2]——拿着 node[i] 的距离、
                //   却带着两个节点外的水面/宽度。平滑河面相邻节点差 ~0.1 看不见；
                //   在瀑布唇口/潭面这种水面陡变处就是相邻列 1~2 格的湿列台阶
                //   （实测 87.47→86.43 的残留台阶即等距双命中等权混合的产物）。
                //   改为恒用本段端点 [i, i+1]：t=1 时取 node[i+1] 的值——这才是
                //   "端帽"的正确语义（该值与相邻段 u=0 处的值天然一致，无缝衔接）。
                int i0 = i, i1 = i + 1;
                // ★ 跌水节点端帽豁免（2026-08-31）：弯折瀑布直角外侧岸坡凹槽的根因。
                //   线段距离场的端帽（投影越出段末的半圆盘）在普通节点被相邻两段矩形
                //   主体覆盖、不可见；但跌水节点两侧水位阶跃——坠落节点 i1 以外的区域
                //   被跌水段端帽、潭侧首段起点以外的区域被其起点端帽，按**潭面水位**
                //   认领并 carve+灌水，沿直角外岸挖出低于上级河面的凹槽（实测截图）。
                //   弯折外侧楔形区改为不认领，保留原始地形包住直角；水面连续性由两段
                //   矩形主体保证（沿轴 u_P≥0 与 u_F≤1 互相衔接），潭/水幕形态不变。
                // ★ 排查记录（2026-09-07）：湿列水面台阶（相邻两列都灌水、水面差 2.07 格）
                //   一度怀疑是本豁免在零落差标记段（碎阶合并后的平坦 tread）旁留出无主
                //   楔形区所致；但 A/B 对照证明改豁免条件无额外收益——真正的根因是上面
                //   i0/i1 的索引错位（已修），豁免语义保持 2026-08-31 原样不动。
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
                // ★ 河成湖段（2026-09-07）：整段换成"宽而平"的湖命中——湖面全段水平
                //   （= reach 下游端水面）、宽度展宽 LAKE_WIDEN 倍、深度收成 minDepth
                //   （湖不挖地，只铺水面，自然盆底保留）、frozen 禁止 IDW 混合（混合
                //   会把湖面与相邻河面抹出斜坡）。河道自身的深槽 hit 与湖 hit 是同一个
                //   （本段只有一个命中），不存在竞争。
                // ★★★ 2026-09-22【入湖锚点段必须发【湖命中】】—— 修"河湖水位不齐平（部分）"★★★
                //   ⚠ 这是本轮的关键缺陷：此前这里只改了 surface/width/depth 的【数值】，
                //     但下面 new RiverLineHit(...) 的 isLake 参数【硬编码 false】⇒
                //     雕刻侧湖分支判据 `nearest.isLake()`（HydrologyBlockCarver:468）
                //     拿不到 ⇒ 该段仍走【河分支】⇒ 继续下切 + 按折线水位灌水
                //     ⇒ 于是"部分湖齐平（湖域内无河道覆盖处）、部分不齐平（有河道覆盖处）"
                //        —— 正是用户实测的二分现象。
                //   修法：湖段标记 ⇒ 发【湖命中】(isLake=true, surface=湖面, depth=minDepth,
                //     frozen=禁 IDW 混合)，与下方真正的湖分支命中同构 ⇒ 雕刻侧自动走
                //     "湖：不挖地 + 水面=雕刻侧湖面" ⇒ **与湖面同源，构造上必然齐平**。
                boolean emitAsLake = false;
                RiverLineRegion.LakeNode lakeNodeForHit = null;
                if (pl.lakeLevel != null && pl.lakeNodes != null) {
                    // 湖命中必须带上【LakeNode】：雕刻侧湖分支用它做水位（含蚀后短板
                    // escapeWaterLevel）与域判定 ⇒ 与湖面完全同源。
                    RiverLineRegion.LakeNode cand = !Double.isNaN(pl.lakeLevel[i1])
                            ? pl.lakeNodes[i1] : pl.lakeNodes[i0];
                    // ★ R-C1 F1g【骨架模式：段锚定须两端都在掩码内】（CARVE-TRACE 实锤）：
                    //   单端跨界的段（一端在掩码、一端在干岸）= 河的入湖口 —— 若整段转成
                    //   湖命中（不雕刻），干岸格 nearestRiverDist=Infinity ⇒ 等高线必干
                    //   （样例 (-404,-484)：地形 175.5 > 湖面 173.3，20 命中全是湖型）。
                    //   两端都在掩码 ⇒ 纯湖内段才锚定；跨界段保持河命中 ⇒ 雕刻进湖通道。
                    //   旧路线（flag=false）保持"任一端"旧行为逐位不变。
                    boolean endsInMask = pl.lakeNodes[i0] != null && pl.lakeNodes[i1] != null;
                    if (cand != null && (!flowSkeletonRouting || endsInMask)) {
                        lakeNodeForHit = cand;
                    }
                }
                // ★★★ 2026-09-23【河成湖关闭：只有【锚定真湖】的河段才转湖命中】★★★
                //   判据 = 本段是否有真实湖节点（lakeNodes 非空且该端非空）。
                //   旧行为（riverMadeLakes=true）= 只要 lakeLevel 有限就转湖 ⇒
                //   "河成湖"把低梯度河谷铺成水平湖面（实测 26,137 格 = 用户红圈）；
                //   现改为：无锚点 ⇒ 保持普通河（用自身单调下降河面、不展宽、不染湖色）。
                boolean anchoredToLake = lakeNodeForHit != null;
                // ★★★ 2026-09-23【非锚定"河成湖"段 = 只展宽、不造湖】★★★
                //   低梯度河段（lakeLevel 有限但无真实湖锚点）：
                //     · 旧行为 = 转湖命中 + 展宽 + 水面压成 reach 下游端水平面
                //       ⇒ 河谷被铺成一大片水平"湖"（实测 26,137 格 = 用户红圈；
                //         且违反用户架构"湖先定、河后接"）；
                //     · 仅关闭转湖（riverMadeLakes=false）⇒ 水体总量 −18k（低梯度段被抽细）；
                //     · 本行做法 = 保留展宽（低梯度 = 宽而缓的真河，物理正确），
                //       水面仍用自身【单调下降】河面（不压平、不染湖色、不接管湖分支）。
                boolean widenOnly = !anchoredToLake && !riverMadeLakes
                        && pl.lakeLevel != null
                        && (!Double.isNaN(pl.lakeLevel[i0]) || !Double.isNaN(pl.lakeLevel[i1]));
                if (widenOnly) {
                    width = Math.max(width, 2.0) * LAKE_WIDEN;
                }
                if (pl.lakeLevel != null
                        && (anchoredToLake || riverMadeLakes)
                        && (!Double.isNaN(pl.lakeLevel[i0]) || !Double.isNaN(pl.lakeLevel[i1]))) {
                    double lv = Double.isNaN(pl.lakeLevel[i1]) ? pl.lakeLevel[i0] : pl.lakeLevel[i1];
                    surface = lv;
                    width = Math.max(width, 2.0) * LAKE_WIDEN;
                    depth = params.minDepth();
                    frozen = true;
                    fallDrop = 0.0;
                    emitAsLake = true;
                    // ★★★ 2026-09-22【命中水位 = 雕刻侧最终水位链】★★★
                    //   雕刻侧最终湖面 = min(carver spill, escapeWaterLevel(雕刻后地形))。
                    //   命中里若填的是旧 spill ⇒ 会【高于】湖面 ⇒ 河尾悬在湖上（用户实测）。
                    //   修法：命中 surface 取与雕刻侧同一链的结果（蚀后短板水位），
                    //   使"河线水面"与"湖面"逐位一致。
                    if (lakeNodeForHit != null) {
                        java.util.function.ToDoubleBiFunction<Double, Double> es2 = erodedYSampler;
                        if (es2 != null && !Double.isNaN(pl.lakeLevel[i0])) {
                            try {
                                double esc2 = lakeNodeForHit.escapeWaterLevel(es2, 24.0, 6.0, lv);
                                if (!Double.isNaN(esc2)) {
                                    surface = Math.min(lv, esc2);
                                }
                            } catch (RuntimeException ignore) {
                                // 求解失败 ⇒ 保持旧 spill
                            }
                        }
                    }
                }
                // ★ 岸坡雕刻面（2026-09-09，用户实测"瀑布落差处上游岸坡横在河谷的薄墙"）：
                //   跌水段/潭后段的岸坡列 (dist>width) 若按本段水面(潭面)雕刻，会把落差上游
                //   地面刻穿——与下游归属切换处形成 8~19 格直立薄墙（WallForensicsProbe 实证
                //   cut 0 vs 11.5）。old-Streams surfaceLevelAt 语义：岸坡取【上游水位】。
                //   规则：跌水段 → 崖顶 lip（surf[i0]）；其下游 4 节点窗 → lip 按节点距线性
                //   渐变回本段水面；其余段 = surface（零行为变化）。
                double bankSurface = surface;
                if (pl.fallDrop[i1] > 0.0
                        && pl.surfaceY[i0] - pl.surfaceY[i1] >= 2.0) {
                    // ★ 本段含真跌水：崖顶 = surf[i0]。fall 标记覆盖整个 run，
                    //   run 内无落差的后续段（surf 相等）必须落入下方回溯分支，
                    //   否则崖顶会被错取成潭面（实测 -166 列 bank=132 → 墙未消）。
                    bankSurface = pl.surfaceY[i0];
                } else {
                    int back = Math.max(1, i0 - 4);
                    for (int j = i0; j >= back; j--) {
                        if (pl.fallDrop[j] > 0.0
                                && pl.surfaceY[j - 1] - pl.surfaceY[j] >= 2.0) {
                            // ★ 渐变按【空间距离】（到落差线 32 block 线性衰减）而非节点数——
                            //   节点数分段会在段界产生 ~2 格台阶（实测平缓种子 38 处）。
                            double distBlocks = Math.hypot(
                                    wx - pl.nodes[j].x(), wz - pl.nodes[j].z()) * horizontalScale;
                            double blend = NoiseUtil.saturate(1.0 - distBlocks / 32.0);
                            bankSurface = lerp(surface, pl.surfaceY[j - 1], blend);
                            break;
                        }
                    }
                }
                // ★ 自适应谷宽（2026-09-09）：雕刻侧的谷壁跨度会随岸高展宽（最多
                //   bankRunMax），这里必须同步放宽命中裁剪半径，否则新谷宽边缘会被
                //   提前裁掉 → 属主在半途消失 → 岸坡断层。
                //   单位说明：本处 dist/width 为 wu，bankRunMax 为 block —— 按 block
                //   直接相加是【故意放大】的保守值（宁可多留命中；远处段经 smin(k=4)
                //   合并后对几何无影响，多留无害，少留致命）。
                double valleyReach = Math.max(width * (1.0 + bankFactor), width * 3.0)
                        + params.bankRunMax();
                // ★★★ 2026-09-22【湖域内不发【河】命中】—— 修"湖被算成河"（用户实测）★★★
                //
                //   【被修的缺陷】用户三连图实测：左图（河域）出现【成片青色块】——
                //   "河流怎么可能有一片的呢？这绝对有 bug"。
                //   成因：折线在湖域内【仍然发出河命中】，而雕刻侧按 samples.get(0)
                //   （最近命中）决定分支 ⇒ 湖里的折线比湖面更近 ⇒ 该列被判"河"
                //   ⇒ 湖域被河分支接管（水位按河算、继续切地形）。
                //   这与 user 的核心判据（河入湖即止、湖面不动）直接冲突。
                //
                //   【正解】湖域（生产 LakeNode 域）内【只发湖命中】，不发河命中：
                //   该列必然只有湖命中 ⇒ carver 必然走湖分支 ⇒ 湖是完整的湖，
                //   不再被河折线撕成碎片。河折线本身仍保留（出湖后继续流）。
                if (dist <= valleyReach) {
                    if (!emitAsLake && inLakeDomainAt(r, wx, wz)) continue;
                    if (emitAsLake) {
                        // ★ 湖段 ⇒ 发【湖命中】：与下方真正的湖分支命中同构（isLake=true +
                        //   LakeNode）⇒ 雕刻侧走湖分支（不挖地、水面=雕刻侧湖面）
                        //   ⇒ 河-湖水位【同源】，构造上必然齐平（用户实测"部分不齐平"的修点）。
                        out.add(new RiverLineHit(dist, surface,
                                Math.max(width, 2.0) * LAKE_WIDEN, params.minDepth(),
                                r.dischargeArea, r.outletOcean, true, 0.0, true,
                                surface, lakeNodeForHit));
                    } else {
                        out.add(new RiverLineHit(dist, surface, width, depth,
                                r.dischargeArea, r.outletOcean, false, fallDrop, frozen, bankSurface,
                                null));
                    }
                }
            }
        }
        // ★★★ 2026-09-19【⑤⑥ 判据统一】★★★
        //   问题：⑤（本处，发出命中）与 ⑥（HydrologyBlockCarver 的分支决策）曾是【两套判据】——
        //     ⑤ 把"河命中 + 湖命中"全都发出去，像"比河更近"这种距离竞争由消费方按距离裁决；
        //     ⑥ 却按【样本里是否存在湖命中】决定分支（不看距离）。
        //   ⇒ 改 ⑤ 会让 ⑥ 跳变：2026-09-19 放宽湖认领修湖岸直线后，
        //     河道内的列也带上了湖命中 ⇒ 被 ⑥ 的湖分支接管 ⇒ carved = original
        //     ⇒ 实测 203 列河道不再下切（河流"新问题"，已在 ⑥ 侧打补丁止血）。
        //   ⇒ 根治 = 让 ⑤ 与 ⑥ 【同源】：本处也遵守"河道内 → 河优先"，不发湖命中。
        //     这样两层的规则都是单一的一条：
        //       · 河道内（dist ≤ width）              ⇒ 河（下切 + 灌水）
        //       · 否则若在湖域（inDomain）             ⇒ 湖（不挖地；水由落块侧等高线定）
        //       · 否则                                ⇒ 河的一例谷壁带（只塑形）
        //   对齐参考：Farseek `isStreamBed = (maxFloorLevel < surfaceLevel)`、
        //     RTF `isSubMerged = dist < zone1Radius && … && h < targetWaterLevel` —— 均为【一套判据】。
        //   【回退】把下面的 `&& !inRiverChannel` 去掉。
        double chanDist = Double.POSITIVE_INFINITY, chanWidth = 1.0;
        for (RiverLineHit h : out) {
            if (h.isLake()) continue;
            // ★★★ 2026-09-22【入湖锚点段不算"河道"】★★★
            //   下面 `inRiverChannel` 的用途是"河道内不发湖命中（河优先）"。
            //   我校正过的【入湖锚点段】位于湖域内、本该由湖接管 ⇒ 若它把 inRiverChannel
            //   置真，下面的湖命中就会被抑制 ⇒ 湖分支拿不到 ⇒ 又回到"河在湖里挖地形
            //   + 水位与湖不齐平"（用户实测的二分现象）。
            //   判据：本列落在某个湖的域内 ⇒ 该段属湖，不计入河道竞争（用列坐标判定）。
            //   ★ 2026-09-22：湖域内【连河命中都不发】（见发出处的 continue）⇒ out 里
            //     本就不该有河命中；此处保留作为双保险（历史路径/其它 region 的命中）。
            if (inLakeDomainAt(r, wx, wz)) continue;
            if (h.distToCenter() < chanDist) {
                chanDist = h.distToCenter();
                chanWidth = Math.max(h.width(), 1.0);
            }
        }
        boolean inRiverChannel = chanDist <= chanWidth;
        // 湖泊：影响范围内纳入（远处湖 carve≈original 无副作用）；
        //   ★ 但【河道内不发湖命中】—— 与 ⑥ 的分支判据同源（见上）。
        if (!r.lakes.isEmpty()) {
            // ★★★ 2026-09-19（P3）湖面不平【已确认的缺陷 + 失败尝试留痕】★★★
            //
            //   【缺陷已实测确认（判据 J1，runLakeLevelFlatnessProbe）】
            //   按 **4 邻连通**分组（无任何"湖节点/域/半径"假设），统计各组
            //   `cell.riverSurfaceY` 的标准差：
            //     · 31198 格水体 σ = 0.000000（完美水平）
            //     · ★ 604 格【纯湖】（全部 isLake=true）σ = 0.471812，
            //       水面跨 167.694 → 169.829（**2.1 块**）
            //   —— 静止水面必须处处同高 ⇒ 这是硬物理错误，用户判据"湖面不平"**成立**。
            //
            //   【⚠ 已尝试并被实测否决的修法（勿重犯）】
            //   假设："一个连通水体被两个湖节点覆盖，各列按【最近】选到不同节点 ⇒ 不同 spill"。
            //   改法：把选择条件由"最近"改为"覆盖本点且水位最低"。
            //   ★ 实测结果（同一窗口）：
            //     · 目标水体 σ **一字未变**（0.471812）⇒ 假设**被证伪**
            //     · 且大湖 31198 → 29206 格（**−6%**，引入了新的水体回归）
            //   ⇒ 已回退为本行（按最近）。
            //
            //   【为什么假设错（留给后继者）】
            //   本方法只遍历**单个 region** 的湖（`r.lakes`），而候选点由
            //   `sampleAll` 跨 3×3 region 汇总。若该 604 格水体**跨 region**，
            //   两个湖节点分属不同 region ⇒ 本处的"最低水位"选择**够不着**它。
            //   ⇒ 下一步应先在**跨 region 汇总层**（`sampleAll` 的消费方
            //     `HydrologyBlockCarver.carveColumn` 选 `bestLakeDist` 处）验证该假设，
            //     而不是在本函数内改选择规则。
            final double domTol0 = domainToleranceOverride >= 0
                    ? domainToleranceOverride
                    : params.gridCell() * 2.0;
            double lakeDist2 = Double.POSITIVE_INFINITY;
            RiverLineRegion.LakeNode bestLn = null;
            for (RiverLineRegion.LakeNode ln : r.lakes) {
                double d2 = (wx - ln.x) * (wx - ln.x) + (wz - ln.z) * (wz - ln.z);
                if (d2 < lakeDist2) {
                    lakeDist2 = d2;
                    bestLn = ln;
                }
            }
            if (bestLn != null) {
                double lakeDist = Math.sqrt(lakeDist2);
                // ★ 命中判定（2026-09-09 B1）：有逐格轮廓 → 用【洼地格方块并集】判域，
                //   不再用"圆心距 ≤ 半径"的圆盘（那会把任意形状洼地铺成圆，用户实测
                //   "湖泊完全就是一个圆盘"）。域只决定"这个湖管不管这里"，真正的岸线
                //   由落块侧【侵蚀后 height < spill 的等高线】决定 —— 湖岸自然、
                //   且随侵蚀盆底变化自动伸缩（切深→淹更多，淤积→内缩）。
                //   无轮廓的旧式湖退化为圆盘（兼容）。
                //   ★ 外扩量（2026-09-09 定为 2×gridCell → 2026-09-15 曾误改 4× 后回退）：
                //     出水由合成层【侵蚀后 height < spill】等高线精确定界，域只决定
                //     "这湖管不管这里"。若外扩太小（曾用半格 12wu），侵蚀把 spill
                //     等高线推远后会被截在域外漏判 → 用户实测"水边没贴到地形/
                //     水面包不住"。于是 2026-09-09 提到 2×gridCell（48wu）。
                //
                //     ★★ 2026-09-15 回退记录（务必勿重犯）：我曾据 `runLakeLocateProbe`
                //     的 rim 环证据（距湖心 57.6wu 处有 4 点低于 spill）断言"域 48wu
                //     不够 ⇒ 水被域硬截断"，把外扩提到 4×gridCell（96wu）。**这是错的**：
                //     ① 那些点能否出水取决于 carver 侧门控（samples.get(0).isLake()
                //        + inFlood），与本处 inDomain 无关；
                //     ② 提域**放大**了湖的管辖范围，而 carver 侧对域内列只按
                //        `height < spill−0.5` 判水（无连通性约束）⇒ 96wu 内任何低于
                //        水位的低地都被灌 ⇒ 用户实测"**填水还填了一部分在外部**"
                //        （水漫到湖盆外的坡上）。
                //     真正的根因是 floodOOB（认领域 < BFS 搜索窗导致整湖被弃），
                //     已在 LakeNode.computeFlood 修复。此处恢复 2×gridCell。
                // ★★★ 2026-09-19【已回退】湖域容差 0.5×gridCell(12wu) → 2×gridCell(48wu) ★★★
                //
                //   【回退原因：实机否决，我的实验判据错了】
                //   我曾在 2026-09-19 把容差由 2×gridCell(48wu) 收窄到 0.5×gridCell(12wu)，
                //   依据是 runLakeDomainSweepProbe 的扫描：
                //     容差wu   水体列   ★失水   抑制河谷
                //       48     8228      0      2168   ← 原生产值
                //      ★12     8207      21     1209   ← 我当时选的（"失水仅 0.26%，抑制 −44%"）
                //   ⇒ 我把「失水 21 列（0.26%）」判为"可忽略"。
                //
                //   ★ 用户实机验收否决：「湖泊填水出现一小部分边缘未完全贴合」，
                //     并确认该现象是**【这次改动之后才出现】**。
                //   ⇒ 21 列不是"可忽略"：湖的水边由落块侧 `height < spill − 0.5` 的
                //     【1 块精度等高线】决定，而**只有拿到湖命中的列**才走湖分支；
                //     域一收窄，域外那些"低于水位"的列落回河分支 ⇒ **不灌** ⇒
                //     水边出现零散缺口、不再贴地形。
                //
                //   【教训（务必记住）】
                //     · 形态/贴合类的"损失"不能只看【总格数占比】——21/8228=0.26% 看着可忽略，
                //       但它落在【水岸线上】，是肉眼可见的缺口。**岸线类指标必须按"沿周长"计，
                //       不能按面积占比计。**
                //     · 并且：历史注释（2026-09-09）早就警告过"半格(12wu) ⇒ 用户实测
                //       水边没贴到地形/水面包不住"。我当时用"那段注释自己说是 floodOOB 的锅"
                //       绕过去了 —— **绕错了**。历史警告另有独立成因时，仍应先小范围验证，
                //       而不是直接推翻它。
                //
                //   【现状】恢复 2×gridCell(48wu)，与 2026-09-15 结论一致。
                //     ⚠ 同文件 :1403 也有一个 `params.gridCell() * 0.5` ——
                //       那是 LakeNode 的【轮廓格距】，**与本次容差无关，不得一起改**。
                // ★★★ 2026-09-23【湖域容差回到 0.5×gridCell(12wu)】—— 修"山腰挂水"★★★
                //   用户截图（红圈）：湖出现在【山腰坡地】—— 物理上不可能蓄水。
                //   成因链：① 湖命中域 = 轮廓方格 + domTol 外扩（曾 2×gridCell = 96 块）
                //           ② 判水 = 纯等高线（height < 水位，见 HydrologyBlockCarver
                //              .LAKE_CONTOUR_ONLY）⇒ 外扩域内【比湖面低的下坡地】也被灌水
                //           ⇒ 水挂在山腰（正确语义：水只能存在于【盆地内】）。
                //   正解（用户设计 + 被删 LakeBuilder 的语义）：判水 = 在盆地内 ∧ 低于水位。
                //   "在盆地内"由湖域（轮廓方格）给出 ⇒ 容差必须小到不越过盆沿 ——
                //   0.5×gridCell(12wu) = 一个轮廓方格半宽，即"贴着盆地格"。
                //   这正是 git `c8f6860`（2026-09-19 第二刀）的取值；后被恢复成 48wu，
                //   在"纯等高线判水"组合下即成为本次山腰挂水的来源。
                //   回退：把 0.5 改回 2.0。
                // ★★★ 2026-09-23【恢复 0.5×gridCell(12wu) —— 修“环状湖”】★★★
                //   A/B 实测（同 seed 同窗口，指标=湖内"该有水却无水"格数）：
                //     48wu ⇒ 38571（环状/网状湖，用户实测"现在都是环的湖泊"）
                //     12wu ⇒ 534  （湖基本实心 —— 即用户认可的那一版）
                //   ⇒ 12wu 才是"湖面贴合"的取值；48wu 的方格外扩把大量域内格推出
                //     连通区 ⇒ 湖内成片空洞。
                //   回退：把 0.5 改回 2.0。
                double domTol = domainToleranceOverride >= 0
                        ? domainToleranceOverride
                        : params.gridCell() * 0.5;
                boolean inDomain = bestLn.hasOutline()
                        ? bestLn.inDomain(wx, wz, domTol)
                        : lakeDist <= (bestLn.radius > 0 ? bestLn.radius : params.lakeRadius())
                                + params.lakeFadeDist();
                // ★★★ 2026-09-19 修复：湖域内【不再要求"比河更近"】★★★
                //
                //   【被修的缺陷（用户截图 + 实测定位）】
                //   用户圈出的水界是一条【笔直长斜线】，且"完全不是岸边、完全没到岸边"。
                //   实测（runWaterViewProbe 绝对等高线判据，seed 9139912035078620160 @ 块(-377,-335)±128）：
                //     该有水（height < riverSurfaceY−0.5）= 28597 格
                //     实际有水（riverType != 0）        = 22167 格
                //     ⇒ ★ 漏灌 6430 格（22.5%）—— 大片低于水面的格是干的
                //     逐行范围显示：z=-455 该有水 x∈[-418,-257]，实际只有 x∈[-418,-401]
                //     ⇒ 右侧 144 块该有水却全干。
                //
                //   【根因】本行原判据 = `inDomain && lakeDist <= bestRiverDist`
                //     —— "湖只在【比河更近】时才认领"。而湖区东侧有一条平行流过的河
                //     （实测 dist=28.8、半宽 3.37）⇒ 湖/河两个距离场的【等分线】成了一条
                //     长直线，且落在湖区【内部】（远未到岸）⇒ 线东侧的湖域格拿不到 lakeNode
                //     ⇒ 走河分支 ⇒ 不被灌 ⇒ 水体在此被硬切成直线。
                //
                //   【为何此前所有尝试都无效（务必记住）】
                //     这是【归属竞争】，不是窗口/精度问题。实测以下改动数字【一字不差】：
                //       · computeFlood 搜索窗 pad 72wu → 144wu / 288wu
                //       · 弃湖面积阈值 ×16
                //       · inDomain 切比雪夫 → 欧氏距离
                //     格距 12wu→6wu（2026-09-17 那次"直边网格伪影"修复）同样够不着它。
                //
                //   【为何安全】`inDomain` 已界定"这个湖管不管这里"（逐格洼地轮廓 + 4×gridCell），
                //     它本身就保证了范围正确；"比河更近"是多余的强条件。
                //     湖域内的真实岸线仍由落块侧【侵蚀后 height < spill 的等高线】决定
                //     （见 HydrologyBlockCarver 湖分支），不会因此漫出洼地。
                //
                //   【实测效果】湖泊格 22167 → 31198；漏灌 22.5% → 30.9%
                //     （★ 漏灌比例上升是因为"该有水"分母同时从 28597 涨到 45178 —— 更多湖域格
                //       现在带上了 riverSurfaceY，从而进入判据分母；绝对水体面积 +40%）
                //     形态：笔直斜线消失，变为自然弯曲岸线（runWaterViewProbe 出图确认）。
                //     门禁 runWorldgenGate：BUILD SUCCESSFUL（全部判据 PASS）。
                //
                //   【回退】恢复为 `if (inDomain && lakeDist <= bestRiverDist) {` 一行。
                // ★★★ 2026-09-23【P2-2：湖命中域扩展到块级连通洼地掩码】★★★
                //   掩码 = 低于水位 ∧ 与盆底连通（runFloodCore 通行条件构造保证），
                //   恰为用户规则"湖面恒定高度、填到实体山体边缘、能排走的地形不成湖"。
                //   域外但掩码内的列此前拿不到湖命中 ⇒ 绝对等高线漏灌 51,434 格无命中
                //   （LakeHoleSplitProbe B7）+ 水界停在轮廓方格壳上（直边）。
                //   ⚠ inDomain 短路在前 ⇒ 域内列零新增开销；掩码惰性建一次/湖。
                //   回退：lakeBasinHits=false。
                // ★ R-C1 F1f：骨架模式湖命中 = 块掩码【独占】（岸带轮廓内但掩码外
                //   的格不发湖命中 ⇒ 最近命中落到河 ⇒ 河分支下切入湖口出水）；
                //   旧路线保持 P2-2 的 (inDomain || mask) 逐位不变。
                boolean lkDom = flowSkeletonRouting
                        ? inBasinMask(bestLn, wx, wz)
                        : (inDomain || inBasinMask(bestLn, wx, wz));
                if (lkDom && !inRiverChannel) {
                    double lakeW = bestLn.radius > 0 ? bestLn.radius : params.lakeRadius();
                    // ★★★ 2026-09-22【湖命中水位 = 最终采用水位链】★★★
                    //   ⚠ 曾用 bestLn.height（无侵蚀 spill）—— 比真实湖面【高】，
                    //     叠加"无轮廓湖的圆盘域" ⇒ 湖漫过山体边缘、在平原上淹出一个
                    //     【圆盘】（用户截图：左图湖下缘是光滑圆弧、压在平坦地面上）。
                    //   修法：与雕刻侧完全同源 —— finalLakeLevel（minimax 逃逸，
                    //   多溢出口取最低）⇒ 平原连海处逃逸高度低 ⇒ 圆盘自然缩回真实湖盆。
                    // ★★★ 2026-09-23【恢复最终水位链】—— 与上一处 domTol 同批（修环状湖）★★★
                    //   命中水位取 finalLakeLevel（minimax 逃逸），与雕刻侧同源；
                    //   曾回退成 bestLn.height（无侵蚀 spill，偏高）⇒ 判水阈值虚高 ⇒ 湖内空洞。
                    double lv = finalLakeLevel(bestLn);
                    if (Double.isNaN(lv)) lv = bestLn.height;
                    out.add(new RiverLineHit(lakeDist, lv, lakeW,
                            params.minDepth(), r.dischargeArea, false, true, 0.0, false,
                            lv, bestLn));
                }
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
