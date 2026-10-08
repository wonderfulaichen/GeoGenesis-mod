package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.hydrology.HydrologyBlockCarvedColumn;
import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.CellGenerator;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * <h1>model 链 → 生产列 适配器（区域缓存 + 邻区异步预热）</h1>
 *
 * <h2>为什么需要区域缓存</h2>
 * <p>{@link HydrologyPipeline#run} 是【窗口式】求解，单窗口实测
 * （{@code runPipelinePerfProbe}，r≈512/step4/50k）约 <b>5.6 秒</b> ——
 * 逐 chunk 同步求解必然卡死世界生成。而同一窗口的产出对窗口内所有 chunk
 * 共享 ⇒ 按【世界对齐 region】缓存一次、邻区后台预热是唯一可行结构
 * （方案 §P3：区域缓存 + 异步预热）。</p>
 *
 * <h2>窗口布局（与 RegionContractProbe 验证口径严格一致）</h2>
 * <pre>
 *   region = 512×512 block（世界对齐，floorDiv）
 *   窗口中心 = region 中心；半径 = 256 + 96(halo) = 352（step=4 整除）
 *   ⇒ 区域内所有点距窗口缘 ≥ 96 块 = RegionContractProbe 的 interior 口径
 *     （该探针实测：双方 interior 同点水位逐位一致 max|Δ|=0、河道一致 99.2%）
 * </pre>
 *
 * <h2>列语义（★ 写回代数的推导，勿改）</h2>
 * <p>{@code GeoGenesisTerrain.applyHydrologyValley} 的写回是
 * {@code placed = eroded − erosion − rawDelta×(1−mask)}，其中 rawDelta = 侵蚀增量。
 * model 链的 bed 来自 {@code sampleWu}（<b>侵蚀后</b>地形）⇒ model 意图的河道床是
 * "侵蚀后地形 − depth"。取 <b>mask=1、erosion=depth</b>：</p>
 * <pre>
 *   placed = eroded − depth − rawDelta×0 = eroded − depth   ⇒ 恰为 model 意图
 *   cut = erosion + rawDelta×(1−mask) = depth ≥ 0            ⇒ RIVER_CUT_NONNEGATIVE 恒过
 *   placed ≤ eroded 恒成立                                 ⇒ "只下挖"铁律恒成立
 * </pre>
 * <p>⚠ 这与 legacy carver 的"河心 mask=0（计划相对无侵蚀基线）"<b>语义相反</b>，
 * 但两者都满足同一写回公式 —— 差别只在雕刻的参照系：legacy 参照无侵蚀基线，
 * model 参照侵蚀后地形（因为 model 就是在侵蚀后地形上求解的水）。自洽即可。</p>
 *
 * <h2>湖列</h2>
 * <p>{@code lakePlan=true} + {@code lakeNode=null} + {@code lakeLevelY=水位}：
 * 写回走湖分支（湖不雕刻、按 spill 洪泛）、逃逸水位重算在 lakeNode==null 时
 * <b>自动跳过</b>（模型水位已是权威）、{@code lakeGroupOf} 对 null 节点按
 * <b>水位</b>分组 —— 三处均读码确认，消费端零改动。块级洪泛
 * （{@code lakeFineFlood}）仍由写回层做 1 块精度精修。</p>
 *
 * <h2>确定性</h2>
 * <p>region 求解是 (seed, region 坐标, 配置) 的纯函数；缓存只改变时序不改变内容
 * （与侵蚀 tile 缓存同一论证）。失败的 future 必须移除，绝不缓存半成品。</p>
 *
 * <p><b>已知边界（v1，如实）：</b></p>
 * <ul>
 *   <li>气候用与全部探针验证一致的固定 Climate(0.004,0.002,0.3)
 *       （空间化降水驱动是后续工作；改了会使既有实测基线全部失效）；</li>
 *   <li>{@code fillRiverDistance}（河流绿洲的距离度量）仍走 legacy 河网 ——
 *       预览与游戏同源（都走 legacy 距离）但与 model 可见河道不同源；</li>
 *   <li>无瀑布/水幕（lipSurfaceY = waterSurfaceY）；</li>
 *   <li>并发与冷 tile 竞争未实机压测。</li>
 * </ul>
 */
public final class ModelHydrologyProvider {

    /** region 边长（block，世界对齐）。必须整除于 step，且 halo 后的窗口半径整除于 step。 */
    /**
     * ★ 2026-10-08【受控实验旋钮】：region 边长 / halo / 步长改为可配置，用于分离
     * 「窗口截断上游」与「step=4 分辨率」这两个导致"游戏 ≠ 渲染图"的候选根因。
     * 默认值 = 生产原值（512 / 96 / 4）⇒ 零行为变更。
     * <p>用法（诊断）：{@code -Dgeogenesis.modelRegion=2048 -Dgeogenesis.modelHalo=0}</p>
     * <p>⚠ 若某 region 放大后与"正典单窗口 r=700"的湖格数吻合 ⇒ 根因是【窗口截断】；
     * 若仍不吻合但把 STEP 降到 1 后吻合 ⇒ 根因是【分辨率】。二者对策完全不同。</p>
     */
    private static final int REGION = Integer.getInteger("geogenesis.modelRegion", 512);
    /**
     * 窗口保护带（block）—— ★ 2026-10-09 仍是**待定项**。
     *
     * <h3>为什么这个值重要</h3>
     * <p>窗口外的影响若没被充分吸收，会出现两类缺陷：
     * ① 内陆 region 找不到海 ⇒ {@code EquilibriumWater} 的 {@code resolved} 全 false
     * ⇒ 湖整块消失（实测 7 个 region 里 4 个如此）；
     * ② 湖/河在 region 缝上断半截。</p>
     *
     * <h3>用户提示的参照：侵蚀 tile 的比例</h3>
     * <p>用户："我记得我地形侵蚀也是按大陆来分的。" 核实 `CellGenerator:912`：
     * <pre>
     *   ERODE_TILE_CENTER = 48 wu    // 有效输出区（中心）
     *   ERODE_TILE_BORDER = 40 wu    // 边界上下文（两侧）
     *   ERODE_TILE_SIZE   = 128 wu   // 总窗口
     * </pre>
     * ⇒ border/center = **83%**（相邻 tile 共享约 60% 上下文）。
     * 而水文原为 `HALO=96 / REGION=512` = **19%**，远小于侵蚀。</p>
     *
     * <h3>⚠ 实测结论：HALO 不是"越大越好"（本轮数据）</h3>
     * <table border="1">
     *   <tr><th>配置</th><th>湖</th><th>说明</th></tr>
     *   <tr><td>HALO=96</td><td>0.53%</td><td>内陆 region 全灭（resolved=0）</td></tr>
     *   <tr><td>HALO=96 + inlandResolved</td><td>3.72%（r=300 小窗）</td><td>靠放宽判据补回</td></tr>
     *   <tr><td><b>HALO=512</b></td><td><b>4.98%（r=300 小窗）</b></td><td>窗口自然含海，<b>开关已无关</b></td></tr>
     * </table>
     * <p>⇒ <b>HALO 够大时 `inlandResolved` 完全无效</b>（两种配置结果逐位相同）
     * —— 这证明"窗口够大"才是正解，放宽判据只是权宜。
     * 但 HALO=512 的湖量（4.98%）高于 HALO=96 的 3.72%，因为**窗口越大看到的流域越多**。</p>
     *
     * <h3>⇒ 当前取值与理由</h3>
     * <p><b>默认仍为 96（= 生产历史值，零行为变更）</b>，因为：
     * ① HALO=512 未经完整验收（正典规格出图 >50 分钟未跑完）；
     * ② 单次求解面积 ×2.6，世界加载代价未评估；
     * ③ 湖量的"正确值"本身依赖窗口，无绝对基准。</p>
     * <p>要实验：{@code -Dgeogenesis.modelHalo=512}。</p>
     */
    private static final int HALO = Integer.getInteger("geogenesis.modelHalo", 96);
    /** 网格步长（block）—— 生产形态（方案 P3 实测口径）。 */
    private static final int STEP = Integer.getInteger("geogenesis.modelStep", 4);
    /** 流线采样数（生产形态实测 5.6s 的档位）。 */
    private static final int SAMPLES = 50_000;
    /** 窗口半径 = region 半 + halo。 */
    private static final int RADIUS = REGION / 2 + HALO;      // 352，/4 = 88 ✓
    /**
     * 内容 LRU 容量 —— ★ 2026-10-09 改为【按 region 面积自适应】。
     *
     * <p>原为固定 12（注释写"单 region ≈ 3 MB ⇒ 12 ≈ 36 MB"）—— 那是在
     * REGION=512/STEP=4（177² 格）下成立的估算。一旦放大 region / 缩小 step，
     * 单 region 内存按 <b>格数 × 每格字节数</b> 增长：
     * <pre>
     *   REGION=512  STEP=4 ⇒ 177²  = 31k 格  ⇒ ~3 MB
     *   REGION=1408 STEP=1 ⇒ 1409² = 2.0M 格 ⇒ ~190 MB
     *   REGION=1408 STEP=1 +HALO=96 ⇒ 1601² = 2.6M 格 ⇒ ~250 MB
     * </pre>
     * 固定 12 会变成 <b>3 GB</b> ⇒ OOM。故改为"总预算 512 MB 内能放几个就放几个"，
     * 下限 2（保证邻区预热/命中仍有意义）。</p>
     */
    private static final int CACHE_CAP = computeCacheCap();

    private static int computeCacheCap() {
        int n = 2 * RADIUS / STEP + 1;
        long bytesPerRegion = (long) n * n * 96L;      // 粗估：~12 个 double/bool 数组
        int byBudget = (int) Math.max(2, (512L << 20) / Math.max(1L, bytesPerRegion));
        return Math.min(byBudget, Integer.getInteger("geogenesis.modelCacheCap", 12));
    }
    /** 后台预热队列上限（超过则跳过本轮预热，防瞬移/高速移动时无限堆积）。 */
    private static final int PREHEAT_QUEUE_CAP = 8;

    /**
     * 跨区水量传递：窗外 margin 带宽（块，`-Dgeogenesis.modelMargin`）。
     * ★ 默认 <b>0 = 关闭</b> —— 实测注入方式有伪影（见 computeMarginInflow javadoc）。
     */
    private static final int MARGIN_BLOCKS = Integer.getInteger("geogenesis.modelMargin", 0);
    /** margin 带采样步长（块）。 */
    private static final int MARGIN_STEP = 8;

    /** 与全部探针/契约验证一致的固定气候（空间化气候 = 后续工作，见类 javadoc）。 */
    private static final LakeWaterBalance.Climate CLIMATE =
            new LakeWaterBalance.Climate(0.004, 0.002, 0.3);

    private static final org.apache.logging.log4j.Logger LOGGER =
            org.apache.logging.log4j.LogManager.getLogger("geogenesis");

    /** 单 region 求解产物（不可变；发布后只读 ⇒ LRU 淘汰不影响在途读者）。 */
    private record RegionData(HydrologyPipeline.Result pipe,
                              RiverCenterlines.Result centerline,
                              int originX, int originZ) { }

    private final CellGenerator gen;
    /** block → wu 换算（sample/sampleWu 的坐标语义是 wu，见 CellGenerator.sample javadoc）。 */
    private final double invHs;
    /**
     * 内容缓存（LRU）—— ★ static 共享，按 seed 键控。
     *
     * <h3>为什么必须 static（2026-10 实机实测驱动）</h3>
     * <p>实机日志证实存在【多个地形实例】：
     * {@code [HYDRO] modelPipeline} 与 {@code [RIVER] terrain init} 各出现 <b>5 次</b>
     * （4 次在主菜单 Render thread、1 次世界生成 Worker-Main），
     * 因为 {@code GeoGenesisGenerator.setWorldSeed} 会 {@code sharedTerrain = null}
     * ⇒ 之后每个构造点各建一个实例、各持一份实例级缓存。</p>
     * <p>后果（实测，两个 region 的原始日志）：
     * <pre>
     *   region (0,0) solved in  4933 ms (reaches=16,  lake=3,  channel=49)     ← 预览实例
     *   region (0,0) solved in 13355 ms (reaches=218, lake=7,  channel=2719)   ← 世界实例
     *   region (1,0) solved in  3823 ms (reaches=94,  lake=0,  channel=763)
     *   region (1,0) solved in 18490 ms (reaches=774, lake=60, channel=5553)
     * </pre>
     * ① 每个 region 被求解两次（单次 14~18 秒 ⇒ 一半算力白烧）；
     * ② <b>同一 (seed, region) 两次产出不同</b> —— 违反本类 javadoc 的
     *    "缓存只改时序不改内容" 断言（差异来自不同 CellGenerator 实例的状态）。</p>
     * <p>修法：缓存提升为 static，用 {@code seed} 做键的一部分 ⇒
     * 任何实例对同一 (seed, region) 命中的是同一份结果，换 seed 自动失效。</p>
     */
    private static final LinkedHashMap<CacheKey, RegionData> SHARED_CACHE =
            new LinkedHashMap<>(32, 0.75f, true);
    /** 单飞（single-flight）：同一 (seed, region) 只求解一次。 */
    private static final ConcurrentHashMap<CacheKey, CompletableFuture<RegionData>> SHARED_INFLIGHT =
            new ConcurrentHashMap<>();

    /** 缓存键 = (世界种子, region 坐标) —— 见 {@link #SHARED_CACHE} 的 javadoc。 */
    private record CacheKey(long seed, int rx, int rz) { }

    private final ExecutorService preheat;
    private final AtomicInteger preheatQueue = new AtomicInteger();
    /** 本实例的世界种子（缓存键的一部分；由 {@link #setSeed} 更新）。 */
    private volatile long seed;

    /**
     * 求解线程数 —— ★ 2026-10-08 实机驱动的修复。
     *
     * <h3>为什么不能是单线程（事故记录）</h3>
     * <p>原为 {@code newSingleThreadExecutor}。实机新建世界日志实测：
     * <pre>
     *   chunk(-3,-3) hydro=21303ms   ← 出生点预加载，单块水文 21.3 秒
     *   chunk(0,-3)  hydro=18140ms
     *   chunk(0,0)   hydro=9595ms
     *   之后 chunk(2,*) = 34~74ms    ← 缓存命中
     * </pre>
     * 机理：出生点周围有 <b>多个 region 首次求解</b>（每个 5~8 秒），
     * 而单线程池 + 每个 {@code regionFor} 都 {@code join()} 阻塞
     * ⇒ 4 个 region <b>串行</b> 5~8 秒 = 20+ 秒，用户体感"打开非常慢"。
     * （注意：骨架化把单次求解从 14~18s 降到 5~8s，但<b>串行叠加</b>吃掉了收益。）</p>
     *
     * <h3>为什么是 4 而不是核数</h3>
     * <p>本机 20 逻辑核（实测）。世界生成同期的 CPU 消耗方还有：
     * {@code Worker-Main}（地形 tile 采样）、{@code TileSampler}、
     * {@code commonPool}（侵蚀内部并行）、预览线程。
     * region 求解是<b>纯 CPU 密集</b>（单次 5~8s 打满一核），全给出去会饿死 tile 生成
     * ⇒ 反而拖长整体加载（本项目 2026-09 已有"线程超额订阅"事故）。
     * 4 是"并行吃掉串行叠加、又不抢光 tile 生成"的折中，可用
     * {@code -Dgeogenesis.modelSolveThreads} 覆盖。</p>
     */
    private static final int SOLVE_THREADS = Integer.getInteger("geogenesis.modelSolveThreads", 4);

    /**
     * @param seed 世界种子 —— <b>必需参数</b>（缓存键的一部分，见 {@link #SHARED_CACHE}）。
     *
     * <p>⚠ 刻意<b>不提供</b>无参重载：静默默认 {@code seed=0} 会让调用方
     * "忘了传种子"时以一个合法但错误的值缓存 region ⇒ 与传了真种子的实例
     * 各缓存一份、且两者产出不同（正是实机缺陷 B 的形态）。
     * 编译期强制显式传参，比运行期日志警告更可靠。</p>
     */
    public ModelHydrologyProvider(CellGenerator gen, long seed) {
        this.gen = gen;
        this.seed = seed;
        double hs = Math.max(0.01, gen.params().horizontalScale());
        this.invHs = 1.0 / hs;
        // ★ 多线程求解（见 SOLVE_THREADS javadoc）：region 之间互不依赖（各自纯函数），
        //   并行安全。solve() 路径内【零并行流】（已核查 HydrologyPipeline /
        //   EquilibriumWater / LakeWaterBalance / WindowOutlets 均无 parallel/commonPool）
        //   ⇒ 无嵌套并行死锁风险。
        this.preheat = Executors.newFixedThreadPool(SOLVE_THREADS, r -> {
            Thread t = new Thread(r, "geogenesis-hydro-model-solve");
            t.setDaemon(true);
            return t;
        });
    }

    /**
     * 世界/种子切换：清空全部缓存（旧世界内容绝不复用）。
     * ★ static 缓存下，"清空"改为按 seed 隔离 —— 只丢弃本实例以外种子的条目，
     *   避免多实例并存时互相踩踏（实例 A 切种子不该毁掉实例 B 的有效缓存）。
     */
    public void setSeed(long newSeed) {
        if (this.seed == newSeed) return;
        this.seed = newSeed;
        synchronized (SHARED_CACHE) {
            SHARED_CACHE.keySet().removeIf(k -> k.seed() != newSeed);
        }
        SHARED_INFLIGHT.keySet().removeIf(k -> k.seed() != newSeed);
    }

    /** 兼容旧语义：完全清空共享缓存（换世界时由 setSeed 按 seed 处理）。 */
    public void clear() {
        synchronized (SHARED_CACHE) {
            SHARED_CACHE.clear();
        }
        SHARED_INFLIGHT.clear();
    }

    /**
     * 湖判定口径诊断（`-Dgeogenesis.lakeDiag=true`）——
     * 分离「粗格布尔命中」「邻域放宽命中」「逐块精算命中」三者，
     * 用于回答"游戏湖比正典少 95% 到底是哪一步丢的"。
     */
    static final boolean LAKE_DIAG = Boolean.getBoolean("geogenesis.lakeDiag");
    static final java.util.concurrent.atomic.AtomicLong lakeDiagTotal =
            new java.util.concurrent.atomic.AtomicLong();
    static final java.util.concurrent.atomic.AtomicLong lakeDiagCoarse =
            new java.util.concurrent.atomic.AtomicLong();
    static final java.util.concurrent.atomic.AtomicLong lakeDiagNear =
            new java.util.concurrent.atomic.AtomicLong();
    static final java.util.concurrent.atomic.AtomicLong lakeDiagBlock =
            new java.util.concurrent.atomic.AtomicLong();
    static final java.util.concurrent.atomic.AtomicLong lakeDiagCoarseButNotBlock =
            new java.util.concurrent.atomic.AtomicLong();
    static final java.util.concurrent.atomic.AtomicLong lakeDiagGainedByRefine =
            new java.util.concurrent.atomic.AtomicLong();

    /**
     * 已求解 region 的【网格级湖格总数】累计（诊断用）——
     * 用于判定"湖是窗口丢的还是适配层丢的"：
     * 网格湖格 × step² ≈ 应有的方块湖面积，与探针实测湖列数比较即得保留率。
     */
    static final java.util.concurrent.atomic.AtomicLong regionLakeCells =
            new java.util.concurrent.atomic.AtomicLong();

    /** 网格级湖格累计（各 region 累加，含重叠区 ⇒ 仅为量级对照）。 */
    public static long lastRegionLakeCells() {
        return regionLakeCells.get();
    }

    /** 打印湖判定诊断汇总（探针在结束时调用）。 */
    public static String lakeDiagReport() {        return String.format(
                "【湖判定诊断】总列=%d%n"
                + "  粗格布尔命中(coarseLake)=%d%n"
                + "  邻域放宽后(nearLake)=%d%n"
                + "  逐块精算命中(isLakeBlock)=%d%n"
                + "  粗格真但精算否=%d  ← 精算【杀掉】的量%n"
                + "  邻域放宽新增=%d%n",
                lakeDiagTotal.get(), lakeDiagCoarse.get(), lakeDiagNear.get(),
                lakeDiagBlock.get(), lakeDiagCoarseButNotBlock.get(),
                lakeDiagGainedByRefine.get());
    }

    /**
     * 求（或建）指定 chunk 的 256 列雕刻计划。
     *
     * @param chunkX,chunkZ chunk 坐标
     * @param originalCells 该 chunk 的【无侵蚀】基线 Cell（HydrologyChunkSampling 产物）
     *                       —— 只取 {@code height} 作 originalGroundY
     * @return 256 列（顺序与调用方惯例一致：{@code cells[lx*16+lz]}）
     */
    public List<HydrologyBlockCarvedColumn> columnsFor(int chunkX, int chunkZ, Cell[] originalCells) {
        if (originalCells == null || originalCells.length != 256) {
            throw new IllegalArgumentException("originalCells must be 256");
        }
        int blockX0 = chunkX * 16, blockZ0 = chunkZ * 16;
        // 中心点入区（floorDiv：负坐标不能用 / 截断）
        int centerBlockX = blockX0 + 8, centerBlockZ = blockZ0 + 8;
        RegionData rd = regionFor(centerBlockX, centerBlockZ);
        schedulePreheat(Math.floorDiv(centerBlockX, REGION), Math.floorDiv(centerBlockZ, REGION));

        // 区域窗口内子索引（整除性：origin ≡ 0 mod STEP 由布局保证）
        int gi0 = Math.floorDiv(blockX0 - rd.originX, STEP);
        int gj0 = Math.floorDiv(blockZ0 - rd.originZ, STEP);
        HydrologyPipeline.Result p = rd.pipe();
        int n = p.nx();

        // 段空间索引（每 region 建一次：tile → 段下标；64-block tile，容差 12 < 64）
        SegmentIndex segIndex = rd.centerline().reaches.isEmpty()
                ? SegmentIndex.EMPTY : SegmentIndex.build(rd.centerline(), rd.originX(), rd.originZ(), n, STEP);

        // ★★★ 2026-10-09【3×3 邻 region 合并 —— 修"region 缝上断半截"】★★★
        //
        // 【问题】原实现只用【本方块的 region】（`rd`）。相邻 region 各自求解
        //   （各自的窗口、各自的海/无海、各自的盆地）⇒ 边界两侧判定不一致
        //   ⇒ 视觉上"湖/河在 region 缝上断成半截"。
        //
        // 【实测】（1401² 出图，512 块 region 网格，逐缝统计）：
        //   河-竖缝：跨缝对 414，单侧断开 146（35.3%）
        //   河-横缝：跨缝对 383，单侧断开 223（58.2%）
        //   湖-竖缝：跨缝对 172，单侧断开  76（44.2%）
        //   ⇒ 横缝上 58% 的河道在边界处断开 —— 正是用户"放实机就断"的直接证据。
        //
        // 【正解 = legacy 链早已在做的做法】`RiverLineNetwork.sampleAll`
        //   （第 4421-4427 行）对 **3×3 邻 region** 求并集：本区没有的水，
        //   邻区若判定有则照常输出 ⇒ 缝两侧都能看到同一份水。
        //   本处照搬：遍历 3×3 邻区，取（湖优先，其次最近的河道命中）。
        //
        // ⚠ 邻区求解走 `regionFor`（同步、有缓存、有单飞）⇒ 不引入顺序依赖
        //   （region 内容是 (seed, 坐标) 纯函数，缓存只改时序）。
        RegionData[] neigh = new RegionData[9];
        SegmentIndex[] neighSeg = new SegmentIndex[9];
        int[] neighDx = {-1, 0, 1, -1, 0, 1, -1, 0, 1};
        int[] neighDz = {-1, -1, -1, 0, 0, 0, 1, 1, 1};
        int rdRx = Math.floorDiv(centerBlockX, REGION), rdRz = Math.floorDiv(centerBlockZ, REGION);
        for (int t = 0; t < 9; t++) {
            RegionData rr = (t == 4) ? rd : regionFor((rdRx + neighDx[t]) * REGION + REGION / 2,
                    (rdRz + neighDz[t]) * REGION + REGION / 2);
            neigh[t] = rr;
            // ★ 段索引按 region 缓存（原来每【方块】重建一次 ⇒ 256 倍浪费）
            neighSeg[t] = (t == 4) ? segIndex
                    : (rr.centerline().reaches.isEmpty() ? SegmentIndex.EMPTY
                        : SegmentIndex.build(rr.centerline(), rr.originX(), rr.originZ(),
                                rr.pipe().nx(), STEP));
        }

        List<HydrologyBlockCarvedColumn> out = new ArrayList<>(256);
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                int blockX = blockX0 + lx, blockZ = blockZ0 + lz;
                double original = originalCells[lx * 16 + lz].height;
                // 网格索引（4-block 网格 → 最近格点）
                int gi = clamp((int) Math.round((blockX - rd.originX) / (double) STEP), n);
                int gj = clamp((int) Math.round((blockZ - rd.originZ) / (double) STEP), n);
                int k = gi * n + gj;

                // 侵蚀后地形（region 求解已把窗口内 tile 全部生成 ⇒ 此处命中缓存，廉价）
                // ★ block → wu：sampleWu 的坐标语义是 wu（1 wu = hs 块）
                double eroded = gen.sampleWu(blockX * invHs, blockZ * invHs).height;
                // 连续水位（双线性：湖面/河面在 4-block 网格上平滑）
                double surface = bilinear(p.waterSurface(), n, gi, gj, rd.originX, rd.originZ, blockX, blockZ, STEP);

                // ★ 3×3 邻区求并集：湖优先（任一邻区判湖即湖），否则取"最近中心线命中"。
                boolean isLakeBlock = p.lake()[k];
                double half = 0, depth = 0, bestDist = Double.POSITIVE_INFINITY;
                if (!isLakeBlock) {
                    for (int t = 0; t < 9; t++) {
                        RegionData rr = neigh[t];
                        if (rr == null) continue;
                        int nn = rr.pipe().nx();
                        int ti = clamp((int) Math.round((blockX - rr.originX()) / (double) STEP), nn);
                        int tj = clamp((int) Math.round((blockZ - rr.originZ()) / (double) STEP), nn);
                        if (rr.pipe().lake()[ti * nn + tj]) { isLakeBlock = true; break; }
                        SegmentIndex si = neighSeg[t];
                        if (si == null || si.isEmpty()) continue;
                        SegmentIndex.Hit h2 = si.nearest(blockX, blockZ);
                        if (h2 != null && h2.half > 0 && h2.dist <= h2.half && h2.dist < bestDist) {
                            bestDist = h2.dist;
                            half = h2.half;
                            depth = Math.max(0, h2.depth);
                        }
                    }
                    if (isLakeBlock) half = 0;
                }

                // ★★★ 2026-10-08【湖判据最终定案：用模型原生 `p.lake()`】★★★
                //
                // 【为什么不再用"逐块比较水位与地形"】
                //   我曾改成 `isLakeAt(eroded, surface)`（逐块判 `地形 < 水位 − 0.5`），
                //   理由是"湖岸落在等高线上、无方块台阶"。**实测证明这是错的**：
                //   非湖格的 `waterSurface` 是 EquilibriumWater 的【几何逃逸高程 level】，
                //   它的定义就是"水刚好能溢出的高度" ⇒ **恒 > 床面** ⇒
                //   该判据把【整个填洼谷地】都判成湖 ⇒ 湖 7.17% vs 正典 3.63%（**多一倍**）。
                //
                // （湖/河判定已在上方由 3×3 邻区并集完成，见那里的 javadoc）
                // ★ 诊断（-Dgeogenesis.lakeDiag=true）：对照"粗格布尔"与"邻区并集"两口径。
                if (LAKE_DIAG) {
                    boolean coarseLake = p.lake()[k];
                    lakeDiagTotal.incrementAndGet();
                    if (coarseLake) lakeDiagCoarse.incrementAndGet();
                    if (isLakeBlock) lakeDiagBlock.incrementAndGet();
                    if (coarseLake && !isLakeBlock) lakeDiagCoarseButNotBlock.incrementAndGet();
                    if (!coarseLake && isLakeBlock) lakeDiagGainedByRefine.incrementAndGet();
                }
                if (isLakeBlock) {
                    // 湖列：不雕刻，按水位洪泛；lakeNode=null ⇒ 写回跳过逃逸重算、按水位分组
                    out.add(new HydrologyBlockCarvedColumn(blockX, blockZ, original, original,
                            surface, surface, 0.0, 1.0,
                            true, true, null, surface));
                    continue;
                }

                // 河道：`half`/`depth` 已由上方 3×3 邻区并集给出（"最近命中"）。
                // ★ 2026-10-09【回退我引入的两个错误】：
                //   【错误 A】曾加 `p.channel()[k]` 门控 —— 距离场本就是从 channel 细化而来，
                //     再门控是"同条件过滤两次"，且两套索引体系交集易漏格。
                //   【错误 B】曾加"距离场取不到就给最小可雕值" —— 会让每个
                //     `p.channel()` 为真的格都成河（含远离中心线者）
                //     ⇒ 实测打碎成 8803 个连通分量、32% 孤立点。
                //   【正解】只认【距离场命中】（`hit.dist <= hit.half`），不补最小宽度。
                //     `RiverCenterlines.paintSegment` 的扫掠圆盘已保证沿折线连续覆盖。
                if (half > 0) {
                    double carved = eroded - depth;                 // 意图床面
                    boolean fill = surface > carved + 0.5;           // 至少 0.5 格水柱
                    out.add(new HydrologyBlockCarvedColumn(blockX, blockZ, original, carved,
                            surface, surface, depth, 1.0,
                            fill, false, null, Double.NaN));
                } else {
                    // 普通列：不雕刻、不出水（海洋水体由 fillFromNoise 的高度判定负责）
                    out.add(new HydrologyBlockCarvedColumn(blockX, blockZ, original, original,
                            surface, surface, 0.0, 1.0,
                            false, false, null, Double.NaN));
                }
            }
        }
        return out;
    }

    /**
     * 取 region 数据；缓存/单飞双层。失败的 future 必须移除（绝不缓存半成品）。
     *
     * <h3>★ 刚性求解在【调用线程】上跑，不进队列（2026-10-08 实机修复）</h3>
     * <p>原实现把本 region 的求解也 {@code supplyAsync} 进 preheat 线程池再
     * {@code join()} —— 当池子/队列被【邻区预热任务】占满时，刚性需求要排在
     * 预热<b>后面</b>干等。实机出生点实测正是这个形态：
     * {@code chunk(-3,-3) hydro=21303ms} ≈ 3 个预热任务（各 ~7s）排队 + 本解。
     * 而调用方本来就要阻塞等结果 ⇒ 在调用线程上直接解，<b>零排队</b>；
     * 线程池只服务"不阻塞任何人的"邻区预热。</p>
     */
    private RegionData regionFor(int blockX, int blockZ) {
        int rx = Math.floorDiv(blockX, REGION), rz = Math.floorDiv(blockZ, REGION);
        CacheKey key = new CacheKey(seed, rx, rz);
        // ① 缓存命中
        synchronized (SHARED_CACHE) {
            RegionData hit = SHARED_CACHE.get(key);
            if (hit != null) return hit;
        }
        // ② 已有在途求解（它在别人的线程/队列上）→ 等它
        CompletableFuture<RegionData> existing = SHARED_INFLIGHT.get(key);
        if (existing != null) return await(existing, key, rx, rz);
        // ③ 抢占：赢家在【本线程】直接求解（不进池 ⇒ 不排队在预热后面）
        CompletableFuture<RegionData> mine = new CompletableFuture<>();
        CompletableFuture<RegionData> raced = SHARED_INFLIGHT.putIfAbsent(key, mine);
        if (raced != null) return await(raced, key, rx, rz);   // 输了 → 等赢家
        try {
            RegionData data = solve(rx, rz);
            mine.complete(data);
            cachePut(key, data);
            SHARED_INFLIGHT.remove(key, mine);
            return data;
        } catch (RuntimeException | Error e) {
            mine.completeExceptionally(e);
            SHARED_INFLIGHT.remove(key, mine);     // ★ 失败绝不缓存
            LOGGER.warn("[HYDRO-MODEL] region ({},{}) solve failed: {}", rx, rz, e);
            throw e;
        }
    }

    /** 等待在途求解并入缓存（两个入口共用：输掉竞争的调用方 / 预热方）。 */
    private RegionData await(CompletableFuture<RegionData> f, CacheKey key, int rx, int rz) {
        try {
            RegionData data = f.join();
            SHARED_INFLIGHT.remove(key, f);
            cachePut(key, data);
            return data;
        } catch (CompletionException e) {
            SHARED_INFLIGHT.remove(key, f);     // ★ 失败绝不缓存
            LOGGER.warn("[HYDRO-MODEL] region ({},{}) solve failed: {}", rx, rz,
                    String.valueOf(e.getCause()));
            throw e;
        }
    }

    /** 入 LRU 缓存（容量满时淘汰最久未用）。 */
    private void cachePut(CacheKey key, RegionData data) {
        synchronized (SHARED_CACHE) {
            SHARED_CACHE.put(key, data);
            while (SHARED_CACHE.size() > CACHE_CAP) {
                SHARED_CACHE.remove(SHARED_CACHE.keySet().iterator().next());
            }
        }
    }

    /**
     * 跨区水量传递：窗外 margin 带的最陡下降追踪（2026-10-08）。
     *
     * <p><b>⚠ 默认关闭（MARGIN_BLOCKS=0）—— 实测有伪影，未采纳</b>：
     * margin 把窗外水量按【落点】注入窗框离散点（每 MARGIN_STEP 块一个），
     * 大量水从边框离散点涌入 ⇒ 平坦区沿填洼 parent 图汇流时流向退化为直线
     * ⇒ 图上出现【等长横条纹】（用户实测指出：平原上的密集等长横线）。
     * 这与 HANDOFF §17.6 记录的"平坦区流向欠定"同源 —— 水一多，缺陷就显形。</p>
     *
     * <p>⇒ 保留代码供后续改用"沿真实 D8 路径推进窗内再入账"的方案，
     * <b>默认不启用</b>。要复现历史实验：{@code -Dgeogenesis.modelMargin=384}。</p>
     *
     * @return 窗口网格上的逐格额外来水（格数当量），MARGIN_BLOCKS&lt;=0 时返回 null
     */
    private double[] computeMarginInflow(int centerX, int centerZ, int radius, int step, int n) {
        int marginBlocks = MARGIN_BLOCKS;
        if (marginBlocks <= 0) return null;
        double hs = Math.max(0.01, gen.params().horizontalScale());
        double sea = gen.seaLevel();
        double[] extra = new double[n * n];
        int ox = centerX - radius, oz = centerZ - radius;          // 窗口原点（块）
        int mSteps = marginBlocks / MARGIN_STEP;                    // margin 带内采样步数
        long t0 = System.nanoTime();
        // 遍历窗外 margin 带（四条边），步长 MARGIN_STEP
        for (int side = 0; side < 4; side++) {
            for (int t = -mSteps; t <= mSteps; t++) {
                for (int d = 1; d <= mSteps; d++) {
                    // 带内样点（块坐标）：沿边方向 t、垂直边方向 d（窗外为正）
                    int dx, dz;
                    switch (side) {
                        case 0 -> { dx = t * MARGIN_STEP; dz = -d * MARGIN_STEP; }   // 北
                        case 1 -> { dx = t * MARGIN_STEP; dz = d * MARGIN_STEP; }    // 南
                        case 2 -> { dx = -d * MARGIN_STEP; dz = t * MARGIN_STEP; }   // 西
                        default -> { dx = d * MARGIN_STEP; dz = t * MARGIN_STEP; }   // 东
                    }
                    int bx = centerX + dx, bz = centerZ + dz;
                    // 只处理窗外点（边框内一点由窗内自己算）
                    if (Math.abs(dx) <= radius && Math.abs(dz) <= radius) continue;
                    double h = gen.sampleWu(bx / hs, bz / hs).height;
                    if (h <= sea) continue;                          // 海：无径流
                    // 最陡下降追踪到窗框（步长 MARGIN_STEP，上限 mSteps*2 步）
                    double cx2 = bx, cz2 = bz;
                    boolean landed = false;
                    for (int s = 0; s < mSteps * 2; s++) {
                        // 8 邻最陡下降（步长 MARGIN_STEP）
                        double best = Double.NaN; int bdx = 0, bdz = 0;
                        for (int di = -1; di <= 1; di++) {
                            for (int dj = -1; dj <= 1; dj++) {
                                if (di == 0 && dj == 0) continue;
                                double nx = cx2 + di * (double) MARGIN_STEP;
                                double nz = cz2 + dj * (double) MARGIN_STEP;
                                double nh = gen.sampleWu(nx / hs, nz / hs).height;
                                if (Double.isNaN(best) || nh < best) { best = nh; bdx = di; bdz = dj; }
                            }
                        }
                        if (Double.isNaN(best) || best >= h) break;  // 洼地/平地：丢弃（v1）
                        cx2 += bdx * (double) MARGIN_STEP;
                        cz2 += bdz * (double) MARGIN_STEP;
                        // 已入窗？
                        if (Math.abs(cx2 - centerX) <= radius && Math.abs(cz2 - centerZ) <= radius) {
                            landed = true;
                            break;
                        }
                        h = best;
                    }
                    if (!landed) continue;                           // 没到窗（洼地/到海/超步）
                    // 落点 → 窗口网格索引（最近格点）
                    int gi = (int) Math.round((cx2 - ox) / (double) step);
                    int gj = (int) Math.round((cz2 - oz) / (double) step);
                    if (gi < 0) gi = 0; else if (gi >= n) gi = n - 1;
                    if (gj < 0) gj = 0; else if (gj >= n) gj = n - 1;
                    extra[gi * n + gj] += 1;
                }
            }
        }
        marginMs = (System.nanoTime() - t0) / 1e6;
        return extra;
    }

    /** margin 追踪耗时（诊断）。 */
    private volatile double marginMs = 0;

    /** 求解一个 region 窗口（纯函数；耗时日志只打【求解】不打缓存命中）。 */
    private RegionData solve(int rx, int rz) {
        int centerX = rx * REGION + REGION / 2;
        int centerZ = rz * REGION + REGION / 2;
        long t0 = System.nanoTime();
        double[] extra = computeMarginInflow(centerX, centerZ, RADIUS, STEP, 2 * RADIUS / STEP + 1);
        var req = new HydrologyPipeline.Request(centerX, centerZ, RADIUS, STEP, CLIMATE, 0,
                riverModel(), SAMPLES, extra);
        HydrologyPipeline.Result p = HydrologyPipeline.run(gen, gen.params(), req);
        // 中心线（距离场几何源；cellSize=STEP 换算 block 单位）
        RiverCenterlines.Result cl = RiverCenterlines.build(
                p.bed(), p.discharge(), p.channel(), p.sea(), p.nx(), p.nz(),
                RiverCenterlines.Params.defaults(), STEP);
        RegionData rd = new RegionData(p, cl, centerX - RADIUS, centerZ - RADIUS);
        double ms = (System.nanoTime() - t0) / 1e6;
        int lakeCells = countTrue(p.lake());
        regionLakeCells.addAndGet(lakeCells);       // ★ 诊断累计（见 lastRegionLakeCells）
        // ★ 诊断（2026-10-09）：`resolved` 是 EquilibriumWater 的"与窗口切割无关"标志。
        //   小窗口下若 resolved 大面积失效 ⇒ 盆地无法建立 ⇒ 湖丢失。
        //   这是"小窗口丢湖"最可能的直接机理（此前未打印过）。
        int resolvedN = countTrue(p.resolved());
        int basinsN = p.lakeResult() == null ? -1 : p.lakeResult().basins().size();
        int dryN = p.lakeResult() == null ? -1 : p.lakeResult().dryBasins();
        LOGGER.info("[HYDRO-MODEL] region ({},{}) solved in {} ms (model={}, grid={}, reaches={}, lake={}, channel={}, margin={}ms, resolved={}/{} basins={} dry={})",
                rx, rz, String.format("%.0f", ms), riverModel(), p.nx() + "x" + p.nz(),
                cl.reachCount(), lakeCells, countTrue(p.channel()),
                String.format("%.0f", marginMs), resolvedN, p.nx() * p.nz(), basinsN, dryN);
        return rd;
    }

    /**
     * 证据场模型：默认 {@link HydrologyPipeline.RiverModel#D8}（骨架化决策 2026-10-07，
     * 用户选定）。D8 = priority-flood/D8 拓扑累积，<b>零随机数、O(n log n)</b>，
     * 实测同参比 CONTINUOUS 快 ~3×（正典 r=700: 53s vs 150s）且水量账本可收敛。
     * <p>{@code -Dgeogenesis.modelRiverModel=continuous} ⇒ 蒙特卡洛流线证据
     * （50k 随机起点，湖↔河耦合首轮实测 14~18 秒/region）。</p>
     */
    private static HydrologyPipeline.RiverModel riverModel() {
        String v = System.getProperty("geogenesis.modelRiverModel", "d8");
        return v.toLowerCase().startsWith("c")
                ? HydrologyPipeline.RiverModel.CONTINUOUS : HydrologyPipeline.RiverModel.D8;
    }

    /** 4 邻区异步预热（队列超限则跳过 —— 只是缓存优化，绝不能反压世界生成）。 */
    private void schedulePreheat(int rx, int rz) {
        if (preheatQueue.get() >= PREHEAT_QUEUE_CAP) return;
        int[][] d = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] dd : d) {
            int nx = rx + dd[0], nz = rz + dd[1];
            CacheKey key = new CacheKey(seed, nx, nz);
            synchronized (SHARED_CACHE) {
                if (SHARED_CACHE.containsKey(key)) continue;
            }
            if (SHARED_INFLIGHT.containsKey(key)) continue;
            if (preheatQueue.incrementAndGet() > PREHEAT_QUEUE_CAP) {
                preheatQueue.decrementAndGet();
                return;
            }
            CompletableFuture<RegionData> f = SHARED_INFLIGHT.computeIfAbsent(key, kk ->
                    CompletableFuture.supplyAsync(() -> {
                        try {
                            return solve(nx, nz);
                        } finally {
                            preheatQueue.decrementAndGet();
                        }
                    }, preheat::execute));
            f.thenAccept(data -> {
                SHARED_INFLIGHT.remove(key, f);
                cachePut(key, data);
            }).exceptionally(ex -> {
                SHARED_INFLIGHT.remove(key, f);   // ★ 预热失败同样不缓存
                return null;
            });
        }
    }

    private static int clamp(int v, int n) {
        return v < 0 ? 0 : (v >= n ? n - 1 : v);
    }

    private static int countTrue(boolean[] a) {
        int c = 0;
        for (boolean b : a) if (b) c++;
        return c;
    }

    /**
     * ★ 湖泊判定的【连续口径】—— 与游戏落块层同一物理语义。
     *
     * <p>游戏在 {@code GeoGenesisTerrain} 用 {@code cell.height < spill − 0.5} 判湖，
     * 其中 {@code cell.height} 是<b>侵蚀后</b>地形、{@code spill} 是水位。
     * 本方法把同一判据用在【逐方块】上：{@code eroded} 是本块侵蚀后地形、
     * {@code surface} 是双线性插值的水位 ⇒ 湖岸落在地形与水位面的交线上，
     * 是连续曲线而非 4 块网格的直角台阶。</p>
     *
     * <p>{@code 0.5} 与游戏层一致（"至少半格水柱才算湖"）。</p>
     */
    private static boolean isLakeAt(double erodedGroundY, double waterSurfaceY) {
        return erodedGroundY < waterSurfaceY - 0.5;
    }

    /** 4-block 网格上的双线性采样（水面是连续量 ⇒ 取最近格点会有 4 块台阶）。 */
    private static double bilinear(double[] field, int n, int gi, int gj,
                                   int originX, int originZ, int blockX, int blockZ, int step) {
        double fx = ((blockX - originX) - gi * (double) step) / step;
        double fz = ((blockZ - originZ) - gj * (double) step) / step;
        // gi 是 round 后的格点 ⇒ fx ∈ [-0.5, 0.5] 附近；取两侧格点插值
        int g0x = fx >= 0 ? gi : Math.max(0, gi - 1);
        int g1x = fx >= 0 ? Math.min(n - 1, gi + 1) : gi;
        int g0z = fz >= 0 ? gj : Math.max(0, gj - 1);
        int g1z = fz >= 0 ? Math.min(n - 1, gj + 1) : gj;
        double tx = (blockX - originX - g0x * (double) step) / step;
        double tz = (blockZ - originZ - g0z * (double) step) / step;
        tx = Math.max(0, Math.min(1, tx));
        tz = Math.max(0, Math.min(1, tz));
        double v00 = field[g0x * n + g0z], v10 = field[g1x * n + g0z];
        double v01 = field[g0x * n + g1z], v11 = field[g1x * n + g1z];
        return v00 * (1 - tx) * (1 - tz) + v10 * tx * (1 - tz)
                + v01 * (1 - tx) * tz + v11 * tx * tz;
    }

    /**
     * 中心线索段的空间索引（64-block tile → 段下标）。
     *
     * <p>与 {@code RiverCenterlines.paintSegment} 共用同一几何源（节点 = 网格点
     * {@code origin + cell*step}）—— 适配器逐块求的距离与探针画的掩码是同一段线，
     * 不存在"预览画 A、游戏查 B"的第二套几何。</p>
     */
    static final class SegmentIndex {
        static final SegmentIndex EMPTY = new SegmentIndex(new double[0], new HashMap<>());

        /** 段参数平铺：x0,z0,x1,z1（block）, h0,h1（半宽 block）, d0,d1（深度 block）。 */
        private final double[] seg;
        private final Map<Long, List<Integer>> tiles;

        private record Hit(double dist, double half, double depth) { }

        private SegmentIndex(double[] seg, Map<Long, List<Integer>> tiles) {
            this.seg = seg;
            this.tiles = tiles;
        }

        boolean isEmpty() { return seg.length == 0; }

        static SegmentIndex build(RiverCenterlines.Result cl, int originX, int originZ,
                                  int n, int step) {
            int count = 0;
            for (RiverCenterlines.Reach r : cl.reaches) count += Math.max(0, r.nodeCount() - 1);
            double[] seg = new double[count * 8];
            Map<Long, List<Integer>> tiles = new HashMap<>();
            int idx = 0;
            final int TILE = 64;
            for (RiverCenterlines.Reach r : cl.reaches) {
                for (int i = 1; i < r.nodeCount(); i++) {
                    int c0 = r.cell[i - 1], c1 = r.cell[i];
                    double x0 = originX + (c0 / n) * (double) step;
                    double z0 = originZ + (c0 % n) * (double) step;
                    double x1 = originX + (c1 / n) * (double) step;
                    double z1 = originZ + (c1 % n) * (double) step;
                    double h0 = r.width[i - 1] * 0.5, h1 = r.width[i] * 0.5;
                    double d0 = r.depth[i - 1], d1 = r.depth[i];
                    // ★ idx 是【段号】，每段占 8 个 double（原写 `idx++ / 8` 使前 8 段
                    //   全部写进 slot 0 互相覆盖、其余槽位残留全零段 ⇒ 段索引形同虚设
                    //   （适配器探针实测：region 有 7682 个证据河道格而列产出 河=0）。
                    int si = idx++;
                    int o = si * 8;
                    seg[o] = x0; seg[o + 1] = z0; seg[o + 2] = x1; seg[o + 3] = z1;
                    seg[o + 4] = h0; seg[o + 5] = h1; seg[o + 6] = d0; seg[o + 7] = d1;
                    // 覆盖段 bbox + 半宽余量（≤12 < TILE ⇒ 至多 2×2 tile）
                    int tx0 = (int) Math.floor((Math.min(x0, x1) - 16) / TILE);
                    int tx1 = (int) Math.floor((Math.max(x0, x1) + 16) / TILE);
                    int tz0 = (int) Math.floor((Math.min(z0, z1) - 16) / TILE);
                    int tz1 = (int) Math.floor((Math.max(z0, z1) + 16) / TILE);
                    for (int tx = tx0; tx <= tx1; tx++) {
                        for (int tz = tz0; tz <= tz1; tz++) {
                            tiles.computeIfAbsent(((long) tx << 32) ^ (tz & 0xffffffffL),
                                    x -> new ArrayList<>()).add(si);
                        }
                    }
                }
            }
            return new SegmentIndex(seg, tiles);
        }

        /** 该 block 到最近段的距离/半宽/深度；无候选 ⇒ null。 */
        Hit nearest(int blockX, int blockZ) {
            final int TILE = 64;
            List<Integer> cand = tiles.get(((long) Math.floorDiv(blockX, TILE) << 32)
                    ^ (Math.floorDiv(blockZ, TILE) & 0xffffffffL));
            if (cand == null) return null;
            double best = Double.POSITIVE_INFINITY, half = 0, depth = 0;
            for (int si : cand) {
                int o = si * 8;
                double x0 = seg[o], z0 = seg[o + 1], x1 = seg[o + 2], z1 = seg[o + 3];
                double dx = x1 - x0, dz = z1 - z0;
                double len2 = dx * dx + dz * dz;
                double t = len2 > 0
                        ? Math.max(0, Math.min(1, ((blockX - x0) * dx + (blockZ - z0) * dz) / len2))
                        : 0;
                double px = x0 + t * dx, pz = z0 + t * dz;
                double dist = Math.hypot(blockX - px, blockZ - pz);
                if (dist < best) {
                    best = dist;
                    half = seg[o + 4] + t * (seg[o + 5] - seg[o + 4]);
                    depth = seg[o + 6] + t * (seg[o + 7] - seg[o + 6]);
                }
            }
            return best == Double.POSITIVE_INFINITY ? null : new Hit(best, half, depth);
        }
    }
}
