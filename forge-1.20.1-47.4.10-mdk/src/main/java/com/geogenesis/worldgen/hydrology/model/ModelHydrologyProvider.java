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
                              SegmentIndex skeletonSeg,
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

    /**
     * ★ 2026-10-10【pass-1（双 pass 续流的"发送"阶段）】。
     *
     * <h3>为什么要分出 pass-1</h3>
     * <p>跨区续流必须让【接收方】用它自己的 {@code parent} 树追进来（方向场统一）。
     * 但接收方要知道"哪个邻区朝我吐了河"⇒ 得先有邻区的出口种子；而出口种子
     * 需要邻区跑完管线。若 {@code solve} 直接这么做，就会出现
     * <b>solve → 8 邻 solve → 64 邻 solve…</b> 的递归爆炸。</p>
     *
     * <p>⇒ 拆成两层：{@link #regionPass1} <b>只跑管线 + 无接力的骨架</b>
     * （产出 {@link DeterministicSkeleton.Result#exitSeeds}），<b>绝不触碰任何邻区</b>
     * ⇒ 扇出恒为 1；{@link #solve} 才取 8 邻的 pass-1 拼出 {@code RegionData}。</p>
     *
     * <p>⚠ 这也是 {@link #schedulePreheat} 改为预热 <b>pass-1</b> 而非 {@code solve}
     * 的原因 —— 否则 4 个预热各带 8 个邻区 ⇒ 32 次管线白跑。</p>
     */
    private static final LinkedHashMap<CacheKey, Pass1> P1_CACHE =
            new LinkedHashMap<>(32, 0.75f, true);
    private static final ConcurrentHashMap<CacheKey, CompletableFuture<Pass1>> P1_INFLIGHT =
            new ConcurrentHashMap<>();

    /**
     * pass-1 产物：管线结果 + 出口种子（无接力时的骨架产出）。
     *
     * @param pipe      管线结果（本区窗口的 level/parent/lake/sea/discharge/basinOutlet）
     * @param exitSeeds 走到本窗口边框的链（交给朝向的邻区接力）
     */
    private record Pass1(HydrologyPipeline.Result pipe,
                         List<DeterministicSkeleton.ExitSeed> exitSeeds,
                         int originX, int originZ) { }

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
        synchronized (P1_CACHE) {
            P1_CACHE.keySet().removeIf(k -> k.seed() != newSeed);
        }
        P1_INFLIGHT.keySet().removeIf(k -> k.seed() != newSeed);
    }

    /** 兼容旧语义：完全清空共享缓存（换世界时由 setSeed 按 seed 处理）。 */
    public void clear() {
        synchronized (SHARED_CACHE) {
            SHARED_CACHE.clear();
        }
        SHARED_INFLIGHT.clear();
        synchronized (P1_CACHE) {
            P1_CACHE.clear();
        }
        P1_INFLIGHT.clear();
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

    /** 河道半宽直方图诊断（`-Dgeogenesis.riverWidthDiag=true`），桶 = floor(half) 0..15。 */
    static final boolean RIVER_WIDTH_DIAG = Boolean.getBoolean("geogenesis.riverWidthDiag");
    static final java.util.concurrent.atomic.AtomicLong[] riverHalfHist =
            new java.util.concurrent.atomic.AtomicLong[16];
    static {
        for (int i = 0; i < riverHalfHist.length; i++) {
            riverHalfHist[i] = new java.util.concurrent.atomic.AtomicLong();
        }
    }

    /** 河道半宽分布报告。 */
    public static String riverWidthReport() {
        long tot = 0;
        for (var a : riverHalfHist) tot += a.get();
        StringBuilder sb = new StringBuilder("【河道半宽分布】(floor(half) 桶)\n");
        for (int i = 0; i < riverHalfHist.length; i++) {
            long v = riverHalfHist[i].get();
            if (v == 0) continue;
            sb.append(String.format("  half∈[%d,%d) : %d (%.1f%%)%n",
                    i, i + 1, v, 100.0 * v / Math.max(1, tot)));
        }
        sb.append(String.format("  合计 %d%n", tot));
        return sb.toString();
    }

    // ==========================================================================
    // ★★★ 2026-10-09【河道断点归因诊断 `-Dgeogenesis.riverBreakDiag=true`】★★★
    //
    // 【用户判据】「是真的有断的地方，不是视觉问题」。
    //
    // 【为什么需要】适配层有**两道门**，此前只诊断了第一道：
    //   门 1 `p.channel()[k]`   —— 粗格布尔说"这里是不是河道"
    //   门 2 `surface > carved + 0.5` —— 水柱 ≥0.5 格才真的出水
    // ⇒ 两类"断"必须分开数，否则永远说不清断在哪一步：
    //   (a) `onChannel=true` 但 `fill=false`  ⇒ 【有河道没水】= 真断（用户看到的）
    //   (b) `onChannel=false` 但距离场命中  ⇒ 粗格布尔把它判掉了
    // ==========================================================================
    static final boolean RIVER_BREAK_DIAG = Boolean.getBoolean("geogenesis.riverBreakDiag");
    static final java.util.concurrent.atomic.AtomicLong brkOnChannel =
            new java.util.concurrent.atomic.AtomicLong();
    static final java.util.concurrent.atomic.AtomicLong brkNoWater =
            new java.util.concurrent.atomic.AtomicLong();
    static final java.util.concurrent.atomic.AtomicLong brkDistHitButOff =
            new java.util.concurrent.atomic.AtomicLong();
    static final java.util.concurrent.atomic.AtomicLong brkWaterOk =
            new java.util.concurrent.atomic.AtomicLong();
    /** 断点样本（block 坐标 + 差值），最多记 12 个。 */
    static final java.util.List<String> brkSamples =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    public static String riverBreakReport() {
        long on = brkOnChannel.get(), noW = brkNoWater.get();
        long off = brkDistHitButOff.get(), ok = brkWaterOk.get();
        StringBuilder sb = new StringBuilder("【河道断点归因】(方块级)\n");
        sb.append(String.format("  onChannel=true  总格 = %d%n", on));
        sb.append(String.format("   ├ 出水 √          = %d (%.1f%%)%n", ok,
                100.0 * ok / Math.max(1, on)));
        sb.append(String.format("   └ ★有河却没水 ✗    = %d (%.1f%%)  ← 真断（用户看到的）%n", noW,
                100.0 * noW / Math.max(1, on)));
        sb.append(String.format("  距离场命中但 onChannel=false = %d  ← 粗格布尔误杀%n", off));
        sb.append("  断点样本（blockX,blockZ | surface,carved,差）:\n");
        synchronized (brkSamples) {
            for (String s : brkSamples) sb.append("    ").append(s).append('\n');
        }
        return sb.toString();
    }

    // ==========================================================================
    // 2026-10-10 确定性骨架（A/B 开关 `-Dgeogenesis.skeletonTrace=true`）
    //
    // 用户方向（HYDROLOGY.md 用户口径②③ + 2026-10-10 会话）：
    //   河流做成确定性骨架（PL-RGA 式沿 minimax 树追踪 + 湖溢口续流），
    //   model 链是 legacy 链的升级版 —— legacy 的既有机制必须继承。
    //
    // 开关 true 时用 DeterministicSkeleton 替代 RiverCenterlines（累积->阈值->细化）；
    // 默认 false = 旧路径逐位不变（A/B 对照基线）。
    // ==========================================================================
    static final boolean SKELETON_TRACE = Boolean.getBoolean("geogenesis.skeletonTrace");

    /**
     * 双 pass 跨区续流总开关（`-Dgeogenesis.skeletonCrossPass=false` 关闭）。
     *
     * <p>关 = 完全不取邻区出口种子（等价于"无跨区续流"），用于 A/B 归因；
     * 开（默认）= {@link #collectIncoming} 给 pass-2 注入接力种子。</p>
     */
    static final boolean CROSS_PASS =
            !"false".equals(System.getProperty("geogenesis.skeletonCrossPass"));

    /**
     * 断口现场 watch（`-Dgeogenesis.gapWatch=x1,z1;x2,z2`）：
     * 对指定世界坐标方块打印落块判据全链（isLake/half/dist/onChannel/fill/nearBorder）
     * ⇒ 一次跑定位"断口是几何未覆盖、湖掩码覆盖、还是落块门控"。
     */
    static final java.util.Set<Long> GAP_WATCH = parseGapWatch();

    private static java.util.Set<Long> parseGapWatch() {
        java.util.Set<Long> s = new java.util.HashSet<>();
        String v = System.getProperty("geogenesis.gapWatch", "");
        for (String part : v.split(";")) {
            String[] xy = part.trim().split(",");
            if (xy.length != 2) continue;
            try {
                long x = Long.parseLong(xy[0].trim());
                long z = Long.parseLong(xy[1].trim());
                s.add((x << 32) ^ (z & 0xffffffffL));
            } catch (NumberFormatException ignored) { }
        }
        return s;
    }

    /** 端点类型表收集（`-Dgeogenesis.skeletonEndDiag=true`，断口归因用）。 */
    static final boolean SKELETON_END_DIAG = Boolean.getBoolean("geogenesis.skeletonEndDiag");

    /** 源头门限诊断（`-Dgeogenesis.logSrc=true`）：打印 srcAccum / runoff / maxQ。 */
    static final boolean LOG_SRC = Boolean.getBoolean("geogenesis.logSrc");

    private static double maxDischarge(double[] d) {
        double m = 0;
        for (double v : d) if (Double.isFinite(v) && v > m) m = v;
        return m;
    }
    static final java.util.List<String> SKELETON_ENDS =
            java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    /**
     * 骨架折线 dump（`-Dgeogenesis.skelDump=x,z,r`）：
     * 打印所有经过 [x±r, z±r] 方块的折线点序列 ⇒ 断口现场的直接证据
     * （点序列有无洞、洞在哪两点之间，一次说清）。
     */
    static final String SKEL_DUMP = System.getProperty("geogenesis.skelDump", "");

    /** 骨架模式的 region 产物：日志用 cl（Reach 表）+ 落块用亚格段索引 + 出口种子（接力用）。 */
    private record SkeletonRegion(RiverCenterlines.Result cl, SegmentIndex seg,
                                  List<DeterministicSkeleton.ExitSeed> exitSeeds) { }

    /**
     * 从 {@link DeterministicSkeleton} 构建 region 骨架。
     *
     * <p><b>参数口径</b>（全部对齐 legacy，见 {@code DeterministicSkeleton.Params}）：</p>
     * <ul>
     *   <li>源头间距 = {@code -Dgeogenesis.sourceSpacing}（默认 24 块，折算 /STEP 格）；</li>
     *   <li>源头面积下限 = {@code n² × 0.003} 集水（legacy 骨架
     *       {@code thr = gridN²×0.003} 同值；{@code -Dgeogenesis.skeletonSourceFrac} 可 A/B）；</li>
     *   <li>就近汇入窗口 = 2 格（legacy {@code traceStep}）；</li>
     *   <li>meander 默认关（amp=0，用户 2026-10-10："弯曲先移除，我后面重新设计"）；
     *       重设计时用 {@code -Dgeogenesis.skeletonMeanderAmp}（legacy 默认 6.0 / 波长 24）开；</li>
     *   <li>分支上限 {@code -Dgeogenesis.skeletonMaxRivers}（legacy {@code riverCount} 同义；0=关）。</li>
     * </ul>
     *
     * <p><b>几何源</b>：形态收尾后是亚格 block 折线（Catmull-Rom + 格点吸附），
     * 与落块侧 {@link SegmentIndex} 同一坐标语义 ⇒ 逐块连续、无 STEP 方块化。
     * 宽度/深度恒定最小可见值（用户方针：宽度后续用流量平滑处理）。</p>
     *
     * @param incomingCells 接力种子（<b>本区</b>格索引；来自 8 邻 pass-1 的出口种子。
     *                      空 = pass-1（不接力，只产出出口种子））
     * @param primary       true = 主 pass（打日志 / 收集端点表）；
     *                      false = pass-1（静音 —— 否则每个 region 的日志翻倍）
     */
    private SkeletonRegion skeletonRegion(HydrologyPipeline.Result p,
                                          int originX, int originZ,
                                          List<Integer> incomingCells, boolean primary) {
        int n = p.nx();
        // 源头间距（block）：legacy `sourceSpacingCells=2` × SKELETON_CELL_BLOCKS=8
        //   ⇒ 16 block（其注释写"≈48wu"= 24wu ≈ 48 block，按 wu 口径；骨架路径按格 ⇒ 16 block）。
        //   我方沿用 block 语义 ⇒ 默认 24 block（略宽于 legacy 16，抑制过密）。
        int spacingBlocks = Integer.getInteger("geogenesis.sourceSpacing", 24);
        int spacingCells = Math.max(1, Math.round(spacingBlocks / (float) STEP));
        double runoff = p.lakeResult() == null ? 0.0 : p.lakeResult().runoffPerCell();
        double meanderAmp = Double.parseDouble(
                System.getProperty("geogenesis.skeletonMeanderAmp", "0.0"));
        // ★★★ 2026-10-10【默认 0 = 不限制 —— 实测归因后的决定，勿凭"legacy 有 80"就照抄】★★★
        //
        // 【我曾设 27】理由："按每 1000 block 边长折算 legacy riverCount=80"。
        // 【实测证否（同参 r=700 单变量 A/B）】：
        //   全开(maxRivers=27) ⇒ 河道 14000 格 / 分量 5297
        //   关掉(maxRivers=0)  ⇒ 河道 22475 格 / 分量 7995
        //   ⇒ 河道 −38% 几乎【全是这个上限砍的】，且【最大连通分量 4252 → 1823（−57%）】
        //      = 主干链被数量上限切断（用户判据"截断"反而恶化）。
        // 【为什么 legacy 能用 80 而我方不能用】legacy `riverCount` 计的是
        //   【commitRiver 的尝试数】，且它的 region 含 margin 后是 1536 block 边长
        //   （640wu × hs2.0 = 1280 block，再加 SKELETON_MARGIN 128×2）⇒ 面积是我方的 9 倍。
        //   且 legacy 的源头候选已被 `SOURCE_MIN_ACCUM_CELLS` 等判据大幅收窄，
        //   80 是"上限"而非常规命中数。直接照抄数字 = 刻舟求剑。
        // 【结论】不设上限，交给"源头判据 + minNodes + 自贴近"去控密度。
        int maxRivers = Integer.getInteger("geogenesis.skeletonMaxRivers", 0);
        // ★★★ 2026-10-10【成河门限改为【绝对集水面积】—— 这是"短碎河流"的真正根因】★★★
        //
        // 【旧口径（错）】门限 = n² × frac = 窗口格数的 0.3%。
        // 【为什么错】"成河"是物理判据（绝对集水面积），不是"占窗口的比例"。
        //     legacy 窗口 = 192 格 × 8 block ⇒ 0.3% = **110 格** ≈ 7078 block²
        //     我方窗口 = 177 格 × 4 block ⇒ 0.3% = **94 格** ≈ 1504 block²
        //   ⇒ 我方门限只有 legacy 的 1/4.7 ⇒ 遍地都是"河"。
        //
        // 【换算（★ 单位实测校准过，勿凭直觉改）】
        //   `runoffPerCell = precipitation × runoffCoeff × cellArea`（LakeWaterBalance:392），
        //   `RiverNetwork.build` 里 `discharge[k]` 初值就是它、沿 parent 累加
        //   ⇒ **discharge 单位 = 上游格数 × runoffPerCell**。
        //   实测（正典 r=300）：`runoff=0.01920`、`maxQ≈234`、`P99≈57`
        //   ⇒ legacy 等价门限 = 440 格 × 0.0192 = **8.45**（不是 7078！）。
        //   ⚠ 我一度把门限当成 block² 直接写 7078 ⇒ 高于 maxQ ⇒ `sources=0`，
        //     而残存的河【全部来自 basinOutlet 强制起链】（见 DeterministicSkeleton
        //     的溢口门限说明）⇒ 那才是"提高门限看似无效"的原因。
        //
        //   公式：srcAccum = 目标格数 × runoff，目标格数 = 110 × (8/STEP)² = 440
        double chanMinCells = Double.parseDouble(
                System.getProperty("geogenesis.channelMinCells", "150"));
        double srcAccum = runoff > 0 ? chanMinCells * runoff : 0.0;
        // ★★★ 2026-10-10【三项 legacy 机制补齐（用户判据驱动）】★★★
        //
        // 【用户报的三个问题】① 很多短碎河流 ② 一小部分截断 ③ 反复交叉/重叠。
        // 【共同根因】我只移植了 legacy 的"追踪 + 汇入"，漏了它【过滤】的一半：
        //   · minRiverNodes（长度下限）—— 短段不丢 ⇒ 短碎河流；
        //   · riverCount（数量上限）—— 源头不限 ⇒ 河网过密；
        //   · SELF_APPROACH（自贴近守卫）—— 只排自己节点 ⇒ 擦身绕行 = 交叉重叠。
        //
        // 【尺度换算（关键）】legacy `buildSkeletonRivers` 用 `SKELETON_CELL_BLOCKS=8`
        //   （block），我方 STEP=4 ⇒ **我方 1 格 = legacy 半格** ⇒ legacy 的格数阈值
        //   须**乘 2** 才等价：
        //     minRiverNodes  3 格(legacy) × 8 ÷ 4 = **6 格**（我方）
        //     SELF_APPROACH_RADIUS 2 格 × 8 ÷ 4 = **4 格**（我方）
        //     SELF_APPROACH_EXEMPT 6 步 × 2 = **12 步**（我方）
        //   ⚠ 若直接照抄 legacy 数字，判据会【过松】（半径 2 格在我方只覆盖 8 block）。
        int legacyToOurs = Math.max(1, 8 / STEP);                  // 8 block / STEP
        // ★ 单变量 A/B 开关（2026-10-10 归因用）—— 每项可独立关掉（0 = 关）：
        //   -Dgeogenesis.skeletonMinNodes=6 / skeletonSelfRadius=4 / skeletonMaxRivers=27
        // ★★★ 2026-10-10【selfRadius 默认 0 —— 它无贡献且制造孤立段】★★★
        // 【无贡献】单变量 A/B 实测：关它 14119 格 vs 全开 14000（差 0.8%）⇒
        //   防交叉的主力是 segmentCrossesAny（交叉守卫），不是它。
        // 【有害】用户圈出孤立段（既不连湖也不入海）⇒ skelDump 铁证：
        //   poly#18 end=DEAD_END mouth=(-416,468) 不在窗口边框 —— 是自贴近守卫
        //   误触发（大弯河曲相隔 >12 步 ⇒ 豁免放不过 ⇒ "贴着自己"就地终止）。
        //   终止的链既不接湖也不接海 ⇒ 凭空断流。legacy 同名机制由"洼地续流"兜底，
        //   我方无该兜底 ⇒ 直接关闭（候选：日后给自贴近终止加专用 End + 兜底）。
        int minNodes = Integer.getInteger("geogenesis.skeletonMinNodes", 3 * legacyToOurs);
        int selfRadius = Integer.getInteger("geogenesis.skeletonSelfRadius", 0);
        int selfExempt = Integer.getInteger("geogenesis.skeletonSelfExempt", 6 * legacyToOurs);
        var skParams = new DeterministicSkeleton.Params(
                spacingCells,
                srcAccum,                            // ★ 绝对集水面积门限（见上方 javadoc）
                2,                                   // 就近汇入窗口（legacy traceStep）
                meanderAmp, 24.0,
                maxRivers,
                minNodes,                            // legacy minRiverNodes=3
                selfRadius,                          // legacy SELF_APPROACH_RADIUS=2
                selfExempt);                         // legacy SELF_APPROACH_EXEMPT=6
        var sk = DeterministicSkeleton.build(
                p.level(), p.parent(), p.resolved(), p.lake(), p.sea(),
                p.discharge(), p.lakeResult() == null ? null : p.lakeResult().basinOutlet(),
                n, n, skParams, incomingCells, p.bed());
        if (SKELETON_END_DIAG || LOG_SRC) {
            LOGGER.info("[HYDRO-SRC] srcAccum={} runoff={} traces={} sources={} maxQ={}",
                    String.format("%.1f", srcAccum), String.format("%.5f", runoff),
                    sk.traces.size(), sk.sourcesTotal, String.format("%.1f", maxDischarge(p.discharge())));
        }
        // 亚格几何（Catmull-Rom + 格点吸附）→ SegmentIndex（落块用）
        var shaped = DeterministicSkeleton.shape(sk.traces, p.level(),
                originX, originZ, STEP, n, n, skParams, p.bed());
        List<DeterministicSkeleton.Shaped> allPolys = shaped;
        // ★ 诊断（`-Dgeogenesis.joinChk=true`）：JOIN 点的像素是否重合
        //   —— 共享锚点表若生效，两条链在该格应产出【逐位相同】的像素。
        if (Boolean.getBoolean("geogenesis.joinChk")) {
            var cellOwner = new java.util.HashMap<Integer, Integer>();
            for (int ti = 0; ti < shaped.size() && ti < sk.traces.size(); ti++) {
                for (int c : sk.traces.get(ti).cell) cellOwner.putIfAbsent(c, ti);
            }
            int joins = 0, exact = 0, adjacent = 0, mid = 0, far = 0;
            var hist = new java.util.TreeMap<Integer, Integer>();
            for (int ti = 0; ti < shaped.size() && ti < sk.traces.size(); ti++) {
                var tr = sk.traces.get(ti);
                if (tr.end != DeterministicSkeleton.End.JOIN) continue;
                var sh = shaped.get(ti);
                if (sh.size() == 0) continue;
                Integer ow = cellOwner.get(tr.mouthCell());
                if (ow == null || ow == ti) continue;
                joins++;
                int px = (int) Math.rint(sh.x[sh.size() - 1]);
                int pz = (int) Math.rint(sh.z[sh.size() - 1]);
                var osh = shaped.get(ow);
                int best = Integer.MAX_VALUE;
                for (int i = 0; i < osh.size(); i++) {
                    int d = Math.max(Math.abs((int) Math.rint(osh.x[i]) - px),
                                     Math.abs((int) Math.rint(osh.z[i]) - pz));
                    if (d < best) best = d;
                }
                hist.merge(Math.min(best, 9), 1, Integer::sum);
                if (best == 0) exact++;
                else if (best == 1) adjacent++;
                else if (best <= 3) mid++;
                else far++;
            }
            LOGGER.info("[JOINCHK] origin({},{}) joins={} exact={} adjacent={} 2-3={} far={} hist={}",
                    originX, originZ, joins, exact, adjacent, mid, far, hist);
        }
        // 断口现场：dump 折线点序列（`-Dgeogenesis.skelDump=x,z,r`，多框用 ';' 分隔）
        if (!SKEL_DUMP.isEmpty()) {
            for (String box : SKEL_DUMP.split(";")) {
                String[] pt = box.trim().split(",");
                if (pt.length < 2) continue;
                double qx = Double.parseDouble(pt[0].trim());
                double qz = Double.parseDouble(pt[1].trim());
                double qr = pt.length > 2 ? Double.parseDouble(pt[2].trim()) : 24;
                boolean anyHit = false;
                for (int pi = 0; pi < shaped.size(); pi++) {
                    var sh = shaped.get(pi);
                    boolean near = false;
                    for (int i = 0; i < sh.size(); i++) {
                        if (Math.abs(sh.x[i] - qx) <= qr && Math.abs(sh.z[i] - qz) <= qr) {
                            near = true;
                            break;
                        }
                    }
                    if (!near) continue;
                    anyHit = true;
                    var tr = sk.traces.get(pi);
                    int sc0 = tr.sourceCell(), mc0 = tr.mouthCell();
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < sh.size(); i++) {
                        if (Math.abs(sh.x[i] - qx) <= qr + 10
                                && Math.abs(sh.z[i] - qz) <= qr + 10) {
                            sb.append(String.format("(%.1f,%.1f)", sh.x[i], sh.z[i]));
                        }
                    }
                    LOGGER.info("[SKEL-DUMP] box({},{}) poly#{} n={} end={} fromOutlet={}"
                                    + " src=({},{}) mouth=({},{}) 近区点: {}",
                            qx, qz, pi, sh.size(), tr.end, tr.fromOutlet,
                            originX + (sc0 / n) * STEP, originZ + (sc0 % n) * STEP,
                            originX + (mc0 / n) * STEP, originZ + (mc0 % n) * STEP,
                            sb);
                }
                if (!anyHit && Math.abs(originX - (qx - 352)) < 300) {
                    LOGGER.info("[SKEL-DUMP] box({},{}) 本窗口无折线经过", qx, qz);
                }
            }
        }
        SegmentIndex seg = allPolys.isEmpty() ? SegmentIndex.EMPTY
                : SegmentIndex.buildFromNodes(allPolys);
        // 格路径 Reach（日志 reachCount 用；宽度恒最小可见值）
        List<RiverCenterlines.Reach> reaches = new ArrayList<>();
        for (var t : sk.traces) {
            int m = t.nodeCount();
            int[] cell = new int[m];
            double[] q = new double[m];
            double[] w = new double[m];
            double[] d = new double[m];
            for (int i = 0; i < m; i++) {
                cell[i] = t.cell[i];
                q[i] = p.discharge()[cell[i]];
                w[i] = 1.0;    // 全宽 1 块（半宽 0.5）—— 用户方针：宽度后续再说
                d[i] = 1.0;
            }
            reaches.add(new RiverCenterlines.Reach(cell, q, w, d, 0));
        }
        int mouthsLake = 0, mouthsSea = 0, joins = 0, unresolved = 0, deadEnd = 0;
        for (var t : sk.traces) {
            switch (t.end) {
                case MOUTH_LAKE -> mouthsLake++;
                case MOUTH_SEA -> mouthsSea++;
                case JOIN -> joins++;
                case UNRESOLVED -> unresolved++;
                case DEAD_END -> deadEnd++;
            }
        }
        // DEAD_END = parent=-1 且非海非湖（窗口排水根）—— 断口归因的头号嫌疑。
        if (primary) {
            LOGGER.info("[HYDRO-SKELETON] traces={} sources={} droppedBySpacing={} outletStarts={}"
                            + " incomingStarts={} ends: lake={} sea={} join={} unresolved={}"
                            + " DEAD_END={} exitSeeds={}",
                    sk.traces.size(), sk.sourcesTotal, sk.sourcesDroppedBySpacing,
                    sk.outletStarts, sk.incomingStarts, mouthsLake, mouthsSea, joins,
                    unresolved, deadEnd, sk.exitSeeds.size());
        }
        // 端点表收集（与图上断口坐标对表用）
        if (SKELETON_END_DIAG && primary && originX != Integer.MIN_VALUE) {
            for (var t : sk.traces) {
                int mc = t.mouthCell();
                int wx = originX + (mc / n) * STEP;
                int wz = originZ + (mc % n) * STEP;
                String extra = "";
                if (t.end == DeterministicSkeleton.End.DEAD_END) {
                    // DEAD_END 属性（判定它是否窗口边框种子格）：parent / frame / level
                    int mci = mc / n, mcj = mc % n;
                    boolean frame = mci == 0 || mcj == 0 || mci == n - 1 || mcj == n - 1;
                    extra = String.format(",p=%d,frame=%b,lvl=%.2f,sea=%b",
                            p.parent()[mc], frame, p.level()[mc], p.sea()[mc]);
                }
                SKELETON_ENDS.add(wx + "," + wz + "," + t.end + extra);
            }
        }
        return new SkeletonRegion(RiverCenterlines.fromReaches(reaches, n, n, STEP), seg,
                sk.exitSeeds);
    }

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
        // 骨架模式直接用求解期建好的亚格段索引（Catmull-Rom + 格点吸附后的连续折线）；
        // 不能从 reaches 重建（那会退回网格锚点、丢掉曲线）。
        SegmentIndex segIndex = rd.skeletonSeg() != null ? rd.skeletonSeg()
                : (rd.centerline().reaches.isEmpty()
                ? SegmentIndex.EMPTY : SegmentIndex.build(rd.centerline(), rd.originX(), rd.originZ(), n, STEP));

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
        // ⚠⚠⚠ 2026-10-09【性能修复：邻区改为【惰性】加载 —— 别再无条件取 9 个】⚠⚠⚠
        //
        // 【事故】原写法在**每个 chunk** 开头无条件遍历 9 个邻区并调 `regionFor`：
        //   ```
        //   for (int t = 0; t < 9; t++) {
        //       neigh[t] = regionFor(...);                     // ← 可能触发求解
        //       neighSeg[t] = SegmentIndex.build(...);          // ← 还重建段索引
        //   }
        //   ```
        //   ⇒ 每 chunk 的 region 工作量 **×9**。
        //   实测（正典规格 8281 chunk）：**580s → 791s（+36%）**；
        //   且 `-Dgeogenesis.modelStep=1` 下**直接跑不完**（用户："缩小都跑不完"）。
        //
        // 【正解】惰性：只在**本区判定为空**、且该方块**贴近 region 边界**时，
        //   才去取对应邻区。理由：
        //     · 本区窗口 = `[rx*REGION−HALO, rx*REGION+REGION+HALO]`
        //       ⇒ 已完整覆盖本 region 并外扩 HALO=96 块 ⇒ **内部方块无需邻区**；
        //     · 只有边界带（±HALO）才可能出现"本区说没有、邻区说有"。
        //   ⇒ 绝大多数方块**零邻区开销**（原来每个方块都要扫 9 个）。
        int rdRx = Math.floorDiv(centerBlockX, REGION), rdRz = Math.floorDiv(centerBlockZ, REGION);
        // 本 region 的块范围（用于判断"是否贴近边界"）
        int regLoX = rdRx * REGION, regHiX = regLoX + REGION;
        int regLoZ = rdRz * REGION, regHiZ = regLoZ + REGION;
        // 邻区缓存（惰性填充；下标 = t，与 neighDx/neighDz 对齐；未取 = null）
        RegionData[] neigh = new RegionData[9];
        SegmentIndex[] neighSeg = new SegmentIndex[9];
        int[] neighDx = {-1, 0, 1, -1, 0, 1, -1, 0, 1};
        int[] neighDz = {-1, -1, -1, 0, 0, 0, 1, 1, 1};
        neigh[4] = rd;
        neighSeg[4] = segIndex;

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
                //
                // ⚠⚠ 2026-10-09【严重 bug 修复：越界必须跳过，绝不能 clamp】⚠⚠
                //
                // 【伪影现象】实测出图后，沿 region 边界出现**矩形湖块**：
                //   col i=358 从 j=51 连续 418 像素全判湖，i=362 与之逐位相同
                //   ⇒ 完美的矩形，且 j=51 正是 region 边界（512×(-1)+563）。
                //
                // 【根因】原写法对**不在该邻区窗口内**的方块也去查询，并把索引
                //   `clamp` 到邻区边界 ⇒ 查到的是"邻区边缘格" ⇒ 若边缘格判湖，
                //   则在**整条 region 宽度**上都被误判成湖 ⇒ 矩形块。
                //
                // 【正解】越界 ⇒ `continue` 跳过该邻区（不查、不 clamp）。
                //   3×3 邻区的**有效窗口**本就有重叠（RADIUS=352 > REGION/2=256），
                //   所以"每个方块都能被至少一个邻区覆盖"—— 不需要靠 clamp 兜底。
                boolean isLakeBlock = p.lake()[k];
                double half = 0, depth = 0, bestDist = Double.POSITIVE_INFINITY;
                // ★ 本区先判（零邻区开销）
                if (!isLakeBlock && !segIndex.isEmpty()) {
                    SegmentIndex.Hit h0 = segIndex.nearest(blockX, blockZ);
                    if (h0 != null && h0.half > 0 && h0.dist <= h0.half) {
                        bestDist = h0.dist;
                        half = h0.half;
                        depth = Math.max(0, h0.depth);
                    }
                }
                // ★ 惰性邻区：仅当【本区无命中】且【远离本 region 中心、贴近边界带】时才查。
                //   本区窗口已外扩 HALO（RADIUS = REGION/2 + HALO）⇒ 内部方块无需邻区。
                //   （2026-10-10 双 pass 后无须再拓宽：接力折线由【接收方】在自己窗口内追出
                //    ⇒ 段落全在接收方窗口内 ⇒ 原 HALO 带即可命中。）
                boolean nearBorder = (blockX < regLoX + HALO) || (blockX >= regHiX - HALO)
                        || (blockZ < regLoZ + HALO) || (blockZ >= regHiZ - HALO);
                if (!isLakeBlock && half <= 0 && nearBorder) {
                    for (int t = 0; t < 9; t++) {
                        if (t == 4) continue;
                        // 只取"该方块靠近的那一侧"的邻区，避免无谓求解
                        if (neighDx[t] < 0 && blockX >= regLoX + HALO) continue;
                        if (neighDx[t] > 0 && blockX < regHiX - HALO) continue;
                        if (neighDz[t] < 0 && blockZ >= regLoZ + HALO) continue;
                        if (neighDz[t] > 0 && blockZ < regHiZ - HALO) continue;
                        if (neigh[t] == null) {
                            neigh[t] = regionFor((rdRx + neighDx[t]) * REGION + REGION / 2,
                                    (rdRz + neighDz[t]) * REGION + REGION / 2);
                            // 骨架模式用邻区求解期建好的亚格索引（同本区，勿从 reaches 重建）
                            neighSeg[t] = neigh[t].skeletonSeg() != null ? neigh[t].skeletonSeg()
                                    : (neigh[t].centerline().reaches.isEmpty()
                                    ? SegmentIndex.EMPTY
                                    : SegmentIndex.build(neigh[t].centerline(),
                                            neigh[t].originX(), neigh[t].originZ(),
                                            neigh[t].pipe().nx(), STEP));
                        }
                        RegionData rr = neigh[t];
                        int nn = rr.pipe().nx();
                        int ti = (int) Math.round((blockX - rr.originX()) / (double) STEP);
                        int tj = (int) Math.round((blockZ - rr.originZ()) / (double) STEP);
                        if (ti < 0 || tj < 0 || ti >= nn || tj >= nn) continue;   // 越界跳过
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
                    // ★ 断口现场 watch
                    if (!GAP_WATCH.isEmpty()
                            && GAP_WATCH.contains((((long) blockX) << 32) ^ (blockZ & 0xffffffffL))) {
                        LOGGER.info("[GAP-WATCH] ({},{}) => LAKE_COL region=({},{})",
                                blockX, blockZ, rdRx, rdRz);
                    }
                    // 湖列：不雕刻，按水位洪泛；lakeNode=null ⇒ 写回跳过逃逸重算、按水位分组
                    out.add(new HydrologyBlockCarvedColumn(blockX, blockZ, original, original,
                            surface, surface, 0.0, 1.0,
                            true, true, null, surface));
                    continue;
                }

                // ★ 诊断（-Dgeogenesis.riverWidthDiag=true）：河道半宽分布。
                //   目的：验证"河道精确率仅 19%"是否源于【宽度律饱和】——
                //   `fullWidth(q) = min(maxWidth, headWidth × (q/widthRef)^0.45)`
                //   在 widthRef=1.0（从 ContinuousRiverNetwork 继承的连续量口径）
                //   而 discharge 是【格数累积】（1~数千）时会极早饱和 ⇒ 全部 12 块宽。
                if (RIVER_WIDTH_DIAG && half > 0) {
                    int b = (int) Math.min(15, Math.floor(half));
                    riverHalfHist[b].incrementAndGet();
                }

                // 河道：`half`/`depth` 已由上方给出（本区 + 惰性邻区的最邻近中心线命中）。
                // ★★★ 2026-10-09【河道掩码必须用 `p.channel()` —— 与参照图同口径】★★★
                //
                // 【为什么（用户判据："参考图里面的湖泊河流都基本达到我要求"）】
                //   参照图 `CanonicalViewProbe:91` 的掩码来源是：
                //     `boolean[] chMask = useCenterline ? cl.channel : r.channel();`
                //   —— **默认就是 `r.channel()`（RiverNetwork 的【流量阈值掩码】）**。
                //   而生产原先只用【距离场命中】(`hit.dist <= hit.half`)——
                //   距离场 = `thin(channel)` 再按宽度扫掠 ⇒ **比原掩码宽 3.5 倍**
                //   （实测：生产河道 4.51% vs 参照 1.30%）。
                //
                // 【我曾误判 A/B 为"错误"】（务必勿重犯）：
                //   · "错误 A：加 `p.channel()` 门控 = 同条件过滤两次" —— **错**。
                //     参照图正是用 `r.channel()` 直出；距离场只是"给宽度"，
                //     不该拿它自己当"是不是河"的判据。
                //   · "错误 B：给最小可雕值" —— 那次碎片化的真因不是补宽度，
                //     而是【叠加了 `fillWater` 门槛】（见下方 fill 判定）。
                //
                // 【正解】`p.channel()[k]` 决定"是不是河"；距离场只提供宽度/深度；
                //   距离场未覆盖时给最小可雕值（保证刻出河床 ⇒ 有水面）。
                //
                // 骨架 A/B（2026-10-10）：B 侧（skeletonTrace=true）= 距离场命中即河
                //   （去粗格门控 —— 旧路径的粗格布尔导致 STEP=4 方块化，
                //    像素铁证：河段起点 x mod 4 = 2 占 97.7%）；
                //   A 侧保持旧口径，逐位不变。
                boolean onChannel;
                if (SKELETON_TRACE) {
                    onChannel = half > 0;      // 距离场命中 = 河（连续几何，无粗格门控）
                } else {
                    onChannel = p.channel() != null && p.channel()[k];
                }
                if (onChannel) {
                    // ★★★ 2026-10-09【宽度统一固定为最小可见值 —— 用户的既定方针】★★★
                    //
                    // 【用户原话（已两次强调）】「宽度目前不需要，因为如果有宽度会影响
                    //   骨架判断的，可能会看错图。」
                    //
                    // 【为什么必须固定（本轮实测）】宽度律
                    //   `W = min(12, 1.5 × (q/1.0)^0.45)` 的 `widthRef=1.0` 是从
                    //   【连续流量模型】继承的，而 D8 的 `q` 是【格数累积】（几百~几千）
                    //   ⇒ 量纲错配 ⇒ (833)^0.45 ≈ 21 ⇒ **所有河全部饱和到 maxWidth=12**
                    //   ⇒ 图上河道糊成 2~6 像素粗带 ⇒ 骨架走向完全被掩盖
                    //     （我因此把骨架形态看错了好几轮）。
                    //
                    // ⇒ 本阶段只验【骨架形态】：半宽统一 0.5 块（全宽 1 块 = 1 像素细线）。
                    //   宽度是【后续议题】（见 HYDROLOGY.md §〇②）：
                    //   修它需要先把 widthRef 按 D8 的格数累积重新标定，而不是现在。
                    half = RiverCenterlines.MIN_HALF_BLOCKS;
                    depth = 1.0;
                    // 断因「有河却没水」修复（仅骨架侧；2026-10-10 断点归因）：
                    //   实测 318/31336 格（1%）的 surface-carved 落 0.18~0.50
                    //   （刚好卡在 0.5 水柱门槛下）⇒ depth=1.0 写死 + 平坦坡水位落差小
                    //   ⇒ 河在缓坡上断成虚线。修法：河床下挖深度按水位差自适应
                    //   （上限 4 块，防悬崖式深槽）。
                    if (SKELETON_TRACE) {
                        depth = Math.min(4.0, Math.max(depth, eroded - surface + 0.6));
                    }
                } else {
                    half = 0;
                    depth = 0;
                }
                if (half > 0) {
                    double carved = eroded - depth;                 // 意图床面
                    // ★ 出水判定 = 至少 0.5 格水柱（**不得放宽**）
                    //   依据（项目既有结论）："深度 < 0.5 块 = 地面与水面同一格、
                    //   放不下任何一个水块 ⇒ 物理上本就该干"。
                    //   ⚠ 我曾改成 `+1e-6` 图省事 —— 那会让"半格浅水"也出水，
                    //     而这些格在 MC 里放不下水块 ⇒ 只是凭空加湿（伪影）。
                    boolean fill = surface > carved + 0.5;
                    // ★ 断点归因诊断（默认关，零开销）
                    if (RIVER_BREAK_DIAG) {
                        brkOnChannel.incrementAndGet();
                        if (fill) brkWaterOk.incrementAndGet();
                        else {
                            brkNoWater.incrementAndGet();
                            if (brkSamples.size() < 12) {
                                brkSamples.add(String.format(
                                        "(%d,%d) surface=%.2f carved=%.2f diff=%.3f depth=%.2f",
                                        blockX, blockZ, surface, carved, surface - carved, depth));
                            }
                        }
                    }
                    // ★ 断口现场 watch（`-Dgeogenesis.gapWatch=x,z;...`）
                    if (!GAP_WATCH.isEmpty()
                            && GAP_WATCH.contains((((long) blockX) << 32) ^ (blockZ & 0xffffffffL))) {
                        LOGGER.info("[GAP-WATCH] ({},{}) => RIVER_COL fill={} half={} bestDist={}"
                                        + " depth={} surface={} carved={} region=({},{})",
                                blockX, blockZ, fill, half, bestDist,
                                depth, surface, carved, rdRx, rdRz);
                    }
                    out.add(new HydrologyBlockCarvedColumn(blockX, blockZ, original, carved,
                            surface, surface, depth, 1.0,
                            fill, false, null, Double.NaN));
                } else {
                    // 普通列：不雕刻、不出水（海洋水体由 fillFromNoise 的高度判定负责）
                    // ★ 诊断：距离场命中却被粗格布尔判掉（"误杀"）
                    if (RIVER_BREAK_DIAG && bestDist < Double.POSITIVE_INFINITY) {
                        brkDistHitButOff.incrementAndGet();
                    }
                    // ★ 断口现场 watch —— 「无命中」就是断口的直接现场
                    if (!GAP_WATCH.isEmpty()
                            && GAP_WATCH.contains((((long) blockX) << 32) ^ (blockZ & 0xffffffffL))) {
                        LOGGER.info("[GAP-WATCH] ({},{}) => PLAIN_COL(no hit) bestDist={}"
                                        + " nearBorder={} region=({},{}) lakeCoarse={}",
                                blockX, blockZ, bestDist,
                                (blockX < regLoX + HALO) || (blockX >= regHiX - HALO)
                                        || (blockZ < regLoZ + HALO) || (blockZ >= regHiZ - HALO),
                                rdRx, rdRz, p.lake()[k]);
                    }
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

    /**
     * ★ 双 pass 的 pass-1：跑本区管线 + 无接力骨架，产出出口种子。
     *
     * <p><b>绝不取任何邻区</b>（扇出恒为 1）—— 这是"不递归爆炸"的关键。
     * 缓存/inflight 范式与 {@link #regionFor} 相同（含 seed 键控、失败不缓存）。</p>
     */
    private Pass1 regionPass1(int rx, int rz) {
        CacheKey key = new CacheKey(seed, rx, rz);
        synchronized (P1_CACHE) {
            Pass1 hit = P1_CACHE.get(key);
            if (hit != null) return hit;
        }
        CompletableFuture<Pass1> existing = P1_INFLIGHT.get(key);
        if (existing != null) return awaitPass1(existing, key);
        CompletableFuture<Pass1> mine = new CompletableFuture<>();
        CompletableFuture<Pass1> raced = P1_INFLIGHT.putIfAbsent(key, mine);
        if (raced != null) return awaitPass1(raced, key);
        try {
            Pass1 data = solvePass1(rx, rz);
            mine.complete(data);
            synchronized (P1_CACHE) {
                P1_CACHE.put(key, data);
                while (P1_CACHE.size() > CACHE_CAP) {
                    P1_CACHE.remove(P1_CACHE.keySet().iterator().next());
                }
            }
            P1_INFLIGHT.remove(key, mine);
            return data;
        } catch (RuntimeException | Error e) {
            mine.completeExceptionally(e);
            P1_INFLIGHT.remove(key, mine);          // ★ 失败绝不缓存
            LOGGER.warn("[HYDRO-MODEL] pass1 ({},{}) failed: {}", rx, rz, String.valueOf(e));
            throw e;
        }
    }

    private Pass1 awaitPass1(CompletableFuture<Pass1> f, CacheKey key) {
        try {
            Pass1 data = f.join();
            P1_INFLIGHT.remove(key, f);
            synchronized (P1_CACHE) {
                P1_CACHE.put(key, data);
            }
            return data;
        } catch (CompletionException e) {
            P1_INFLIGHT.remove(key, f);
            throw e;
        }
    }

    /** pass-1 实体：跑管线，骨架【不接力】（只需出口种子；traces 丢弃）。 */
    private Pass1 solvePass1(int rx, int rz) {
        int centerX = rx * REGION + REGION / 2;
        int centerZ = rz * REGION + REGION / 2;
        double[] extra = computeMarginInflow(centerX, centerZ, RADIUS, STEP, 2 * RADIUS / STEP + 1);
        var req = new HydrologyPipeline.Request(centerX, centerZ, RADIUS, STEP, CLIMATE, 0,
                riverModel(), SAMPLES, extra);
        HydrologyPipeline.Result p = HydrologyPipeline.run(gen, gen.params(), req);
        List<DeterministicSkeleton.ExitSeed> seeds = List.of();
        if (SKELETON_TRACE) {
            // 不接力（incoming = 空）⇒ 只产出出口种子。整条骨架白跑一遍是刻意的：
            // 出口种子必须来自"本区独立追踪"的结果，否则会与接力链互相依赖。
            var sr = skeletonRegion(p, centerX - RADIUS, centerZ - RADIUS, List.of(), false);
            seeds = sr.exitSeeds();
        }
        return new Pass1(p, seeds, centerX - RADIUS, centerZ - RADIUS);
    }

    /**
     * 收集接力种子：8 邻 pass-1 中【指向本区】的出口种子 → 换算成本区格索引。
     *
     * <p>换算按 legacy 的<b>方向匹配</b>（邻区出口指向本区 ⟺ dRX/dRZ 与本区
     * 相对邻区的偏移同号反向）：</p>
     * <pre>
     *   邻区在 (rx+dRX, rz+dRZ)，其出口朝 (dRX,dRZ) ⇒ 接收方就是本区
     * </pre>
     * <p>落在本区窗口内才收（窗口外 = 该种子朝更远的区，与本区无关）。</p>
     */
    private List<Integer> collectIncoming(int rx, int rz, int n) {
        List<Integer> out = new ArrayList<>();
        for (int dRX = -1; dRX <= 1; dRX++) {
            for (int dRZ = -1; dRZ <= 1; dRZ++) {
                if (dRX == 0 && dRZ == 0) continue;
                Pass1 nb = regionPass1(rx + dRX, rz + dRZ);
                int dOriginX = (rx + dRX) * REGION + REGION / 2 - RADIUS;
                int dOriginZ = (rz + dRZ) * REGION + REGION / 2 - RADIUS;
                int myOriginX = rx * REGION + REGION / 2 - RADIUS;
                int myOriginZ = rz * REGION + REGION / 2 - RADIUS;
                for (DeterministicSkeleton.ExitSeed s : nb.exitSeeds()) {
                    // 只收"朝本区"的方向：邻区相对本区的偏移是 (-dRX,-dRZ)，
                    // 故其出口方向须为 (-dRX,-dRZ)。
                    if (s.dRX() != -dRX || s.dRZ() != -dRZ) continue;
                    int si = s.cell() / n, sj = s.cell() % n;
                    // 邻区格 → 世界 → 本区格
                    int wx = dOriginX + si * STEP, wz = dOriginZ + sj * STEP;
                    int mi = Math.floorDiv(wx - myOriginX, STEP);
                    int mj = Math.floorDiv(wz - myOriginZ, STEP);
                    if (mi < 0 || mj < 0 || mi >= n || mj >= n) continue;   // 越界（朝更远的区）
                    out.add(mi * n + mj);
                }
            }
        }
        return out;
    }

    /** 求解一个 region 窗口（纯函数；耗时日志只打【求解】不打缓存命中）。 */
    private RegionData solve(int rx, int rz) {
        int centerX = rx * REGION + REGION / 2;
        int centerZ = rz * REGION + REGION / 2;
        long t0 = System.nanoTime();
        // ★ 双 pass：本区 pass-1（管线 + 出口种子）—— 复用缓存，绝不在 solve 里重跑管线。
        Pass1 base = regionPass1(rx, rz);
        HydrologyPipeline.Result p = base.pipe();
        // 骨架 A/B（2026-10-10）：true = DeterministicSkeleton（沿 minimax 树追踪 +
        //   湖溢口续流 + legacy 形态收尾）；false = RiverCenterlines（累积->阈值->细化的旧路径）。
        RiverCenterlines.Result cl;
        SegmentIndex skelSeg = null;
        if (SKELETON_TRACE) {
            // ★ 双 pass 续流：取 8 邻 pass-1 中【指向本区】的出口种子，换算成本区格索引，
            //   由本区自己的 parent 树追进来（方向场统一 ⇒ 缝上不再断）。
            List<Integer> incoming = CROSS_PASS ? collectIncoming(rx, rz, p.nx()) : List.of();
            var sr = skeletonRegion(p, base.originX(), base.originZ(), incoming, true);
            cl = sr.cl();
            skelSeg = sr.seg();
        } else {
            // 中心线（距离场几何源；cellSize=STEP 换算 block 单位）
            cl = RiverCenterlines.build(
                    p.bed(), p.discharge(), p.channel(), p.sea(), p.nx(), p.nz(),
                    RiverCenterlines.Params.defaults(), STEP);
        }
        RegionData rd = new RegionData(p, cl, skelSeg, base.originX(), base.originZ());
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
            // ★ 2026-10-10 双 pass：预热【pass-1】而非 solve —— solve 会取 8 邻 pass-1
            //   ⇒ 4 个预热各带 8 次管线 = 32 次白跑；pass-1 扇出恒为 1。
            //   预热的 pass-1 正是 solve 随后最需要的（省掉刚性路径上的那次管线）。
            synchronized (P1_CACHE) {
                if (P1_CACHE.containsKey(key)) continue;
            }
            if (P1_INFLIGHT.containsKey(key)) continue;
            if (preheatQueue.incrementAndGet() > PREHEAT_QUEUE_CAP) {
                preheatQueue.decrementAndGet();
                return;
            }
            CompletableFuture<Pass1> f = P1_INFLIGHT.computeIfAbsent(key, kk ->
                    CompletableFuture.supplyAsync(() -> {
                        try {
                            return solvePass1(nx, nz);
                        } finally {
                            preheatQueue.decrementAndGet();
                        }
                    }, preheat::execute));
            f.thenAccept(data -> {
                P1_INFLIGHT.remove(key, f);
                synchronized (P1_CACHE) {
                    P1_CACHE.put(key, data);
                    while (P1_CACHE.size() > CACHE_CAP) {
                        P1_CACHE.remove(P1_CACHE.keySet().iterator().next());
                    }
                }
            }).exceptionally(ex -> {
                P1_INFLIGHT.remove(key, f);   // ★ 预热失败同样不缓存
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
                    registerTile(tiles, si, x0, z0, x1, z1);
                }
            }
            return new SegmentIndex(seg, tiles);
        }

        /**
         * 从亚格 block 折线建段索引（骨架模式；2026-10-10）。
         *
         * <p>{@link DeterministicSkeleton.shape} 输出 Catmull-Rom + 格点吸附后的
         * 连续折线 —— 节点不是网格点，故不能走 {@link #build}（它按
         * {@code origin + cell·step} 换算）。半宽/深度恒定最小可见值
         * （用户方针：宽度后续议题）。</p>
         */
        static SegmentIndex buildFromNodes(List<DeterministicSkeleton.Shaped> polys) {
            // ★★★ 2026-10-10【端点缝合 —— 治"拓扑已 JOIN、像素差 2~3 格"的孤立段】★★★
            //
            // 【实测依据（OrphanGap）】孤立段到最近河/湖/海的距离：**2 格 8 个、3 格 2 个**，
            //   其余 ≥7 格 ⇒ **10/16 是 2~3 格的像素缝**（复查：折线自身必 8 连通，
            //   所以缝只能出现在两条折线之间）。
            // 【做法】把每条折线的【首/尾点】连到【另一条】折线 3 格内最近的点
            //   （切比雪夫距离 2~3；=1 已 8 连通无需连、>3 属真正断裂不硬连）。
            //   ⇒ 缝处补一个 2~3 块的短段 ⇒ 落块后像素连通。
            // 【为什么不是"放宽半宽"】那会把所有河加粗（用户：宽度是后续议题）。
            List<double[]> bridges = sewEndpoints(polys,
                    Integer.getInteger("geogenesis.sewGap", 3));   // 0 = 关（A/B 归因）
            if (!polys.isEmpty()) {
                LOGGER.info("[SEW] polys={} bridges={} gap={}", polys.size(), bridges.size(),
                        Integer.getInteger("geogenesis.sewGap", 3));
            }
            int count = 0;
            for (var sh : polys) count += Math.max(0, sh.size() - 1);
            double[] seg = new double[(count + bridges.size()) * 8];
            Map<Long, List<Integer>> tiles = new HashMap<>();
            int idx = 0;
            for (var sh : polys) {
                for (int i = 1; i < sh.size(); i++) {
                    double x0 = sh.x[i - 1], z0 = sh.z[i - 1];
                    double x1 = sh.x[i], z1 = sh.z[i];
                    int si = idx++;
                    int o = si * 8;
                    seg[o] = x0; seg[o + 1] = z0; seg[o + 2] = x1; seg[o + 3] = z1;
                    seg[o + 4] = RiverCenterlines.MIN_HALF_BLOCKS;  // h0
                    seg[o + 5] = RiverCenterlines.MIN_HALF_BLOCKS;  // h1
                    seg[o + 6] = 1.0;                               // d0
                    seg[o + 7] = 1.0;                               // d1
                    registerTile(tiles, si, x0, z0, x1, z1);
                }
            }
            for (double[] br : bridges) {
                int si = idx++;
                int o = si * 8;
                seg[o] = br[0]; seg[o + 1] = br[1]; seg[o + 2] = br[2]; seg[o + 3] = br[3];
                seg[o + 4] = RiverCenterlines.MIN_HALF_BLOCKS;
                seg[o + 5] = RiverCenterlines.MIN_HALF_BLOCKS;
                seg[o + 6] = 1.0;
                seg[o + 7] = 1.0;
                registerTile(tiles, si, br[0], br[1], br[2], br[3]);
            }
            return new SegmentIndex(seg, tiles);
        }

        /**
         * 端点缝合（见 {@link #buildFromNodes} 的说明）：
         * 每条折线的首/尾点连到<b>另一条</b>折线在 {@code maxGap} 格内最近的点。
         *
         * @return 缝合段列表 {x0,z0,x1,z1}（双精度 block 坐标）
         */
        private static List<double[]> sewEndpoints(List<DeterministicSkeleton.Shaped> polys, int maxGap) {
            final int PT = 8;                       // 点网格边长（块）
            Map<Long, List<int[]>> grid = new HashMap<>();
            if (maxGap <= 0) return new ArrayList<>();   // 关闭（A/B）
            for (int pi = 0; pi < polys.size(); pi++) {
                var sh = polys.get(pi);
                for (int i = 0; i < sh.size(); i++) {
                    int px = (int) Math.rint(sh.x[i]), pz = (int) Math.rint(sh.z[i]);
                    long key = (((long) Math.floorDiv(px, PT)) << 32)
                            ^ (Math.floorDiv(pz, PT) & 0xFFFFFFFFL);
                    List<int[]> lst = grid.get(key);
                    if (lst == null) { lst = new ArrayList<>(); grid.put(key, lst); }
                    lst.add(new int[]{px, pz, pi});
                }
            }
            List<double[]> out = new ArrayList<>();
            int span = maxGap / PT + 2;
            for (int pi = 0; pi < polys.size(); pi++) {
                var sh = polys.get(pi);
                if (sh.size() < 2) continue;
                for (int k = 0; k < 2; k++) {
                    int i = (k == 0) ? 0 : sh.size() - 1;
                    int px = (int) Math.rint(sh.x[i]), pz = (int) Math.rint(sh.z[i]);
                    int gx = Math.floorDiv(px, PT), gz = Math.floorDiv(pz, PT);
                    int bx = 0, bz = 0, best = Integer.MAX_VALUE;
                    for (int ax = gx - span; ax <= gx + span; ax++) {
                        for (int az = gz - span; az <= gz + span; az++) {
                            List<int[]> lst = grid.get((((long) ax) << 32) ^ (az & 0xFFFFFFFFL));
                            if (lst == null) continue;
                            for (int[] pt : lst) {
                                if (pt[2] == pi) continue;                 // 不连自己
                                int d = Math.max(Math.abs(pt[0] - px), Math.abs(pt[1] - pz));
                                if (d > 1 && d <= maxGap && d < best) {
                                    best = d; bx = pt[0]; bz = pt[1];
                                }
                            }
                        }
                    }
                    if (best != Integer.MAX_VALUE) {
                        out.add(new double[]{px, pz, bx, bz});
                    }
                }
            }
            return out;
        }

        /** 段 bbox + 半宽余量（≤16 < TILE=64 ⇒ 至多 2×2 tile）登记。 */
        private static void registerTile(Map<Long, List<Integer>> tiles, int si,
                                         double x0, double z0, double x1, double z1) {
            final int TILE = 64;
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
