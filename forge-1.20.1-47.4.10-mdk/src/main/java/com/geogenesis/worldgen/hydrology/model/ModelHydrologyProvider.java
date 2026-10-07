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
    private static final int REGION = 512;
    /** 窗口保护带（block）—— 与 RegionContractProbe 的 interior 口径一致。 */
    private static final int HALO = 96;
    /** 网格步长（block）—— 生产形态（方案 P3 实测口径）。 */
    private static final int STEP = 4;
    /** 流线采样数（生产形态实测 5.6s 的档位）。 */
    private static final int SAMPLES = 50_000;
    /** 窗口半径 = region 半 + halo。 */
    private static final int RADIUS = REGION / 2 + HALO;      // 352，/4 = 88 ✓
    /** 内容 LRU 容量：单 region ≈ 3 MB ⇒ 12 ≈ 36 MB（有界，防长途探索内存失控）。 */
    private static final int CACHE_CAP = 12;
    /** 后台预热队列上限（超过则跳过本轮预热，防瞬移/高速移动时无限堆积）。 */
    private static final int PREHEAT_QUEUE_CAP = 8;

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
        this.preheat = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "geogenesis-hydro-model-preheat");
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

                if (p.lake()[k]) {
                    // 湖列：不雕刻，按水位洪泛；lakeNode=null ⇒ 写回跳过逃逸重算、按水位分组
                    out.add(new HydrologyBlockCarvedColumn(blockX, blockZ, original, original,
                            surface, surface, 0.0, 1.0,
                            true, true, null, surface));
                    continue;
                }

                // 河道：逐块求【点到中心线段】的精确距离（同一几何源 = paintSegment）
                double half = 0, depth = 0;
                if (!segIndex.isEmpty()) {
                    SegmentIndex.Hit hit = segIndex.nearest(blockX, blockZ);
                    if (hit != null && hit.dist <= hit.half && hit.half > 0) {
                        half = hit.half;
                        depth = Math.max(0, hit.depth);
                    }
                }
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

    /** 取 region 数据；缓存/单飞双层。失败 future 必须移除（绝不缓存半成品）。 */
    private RegionData regionFor(int blockX, int blockZ) {
        int rx = Math.floorDiv(blockX, REGION), rz = Math.floorDiv(blockZ, REGION);
        CacheKey key = new CacheKey(seed, rx, rz);
        synchronized (SHARED_CACHE) {
            RegionData hit = SHARED_CACHE.get(key);
            if (hit != null) return hit;
        }
        CompletableFuture<RegionData> f = SHARED_INFLIGHT.computeIfAbsent(key, kk ->
                CompletableFuture.supplyAsync(() -> solve(rx, rz), runnable -> {
                    // 复用预热线程池（单线程 ⇒ 求解串行，避免与 tile 生成争用放大）
                    preheat.execute(runnable);
                }));
        try {
            RegionData data = f.join();
            SHARED_INFLIGHT.remove(key, f);
            synchronized (SHARED_CACHE) {
                SHARED_CACHE.put(key, data);
                while (SHARED_CACHE.size() > CACHE_CAP) {
                    SHARED_CACHE.remove(SHARED_CACHE.keySet().iterator().next());
                }
            }
            return data;
        } catch (CompletionException e) {
            SHARED_INFLIGHT.remove(key, f);     // ★ 失败绝不缓存
            LOGGER.warn("[HYDRO-MODEL] region ({},{}) solve failed: {}", rx, rz,
                    String.valueOf(e.getCause()));
            throw e;
        }
    }

    /** 求解一个 region 窗口（纯函数；耗时日志只打【求解】不打缓存命中）。 */
    private RegionData solve(int rx, int rz) {
        int centerX = rx * REGION + REGION / 2;
        int centerZ = rz * REGION + REGION / 2;
        long t0 = System.nanoTime();
        var req = new HydrologyPipeline.Request(centerX, centerZ, RADIUS, STEP, CLIMATE, 0,
                HydrologyPipeline.RiverModel.CONTINUOUS, SAMPLES);
        HydrologyPipeline.Result p = HydrologyPipeline.run(gen, gen.params(), req);
        // 中心线（距离场几何源；cellSize=STEP 换算 block 单位）
        RiverCenterlines.Result cl = RiverCenterlines.build(
                p.bed(), p.discharge(), p.channel(), p.sea(), p.nx(), p.nz(),
                RiverCenterlines.Params.defaults(), STEP);
        RegionData rd = new RegionData(p, cl, centerX - RADIUS, centerZ - RADIUS);
        double ms = (System.nanoTime() - t0) / 1e6;
        LOGGER.info("[HYDRO-MODEL] region ({},{}) solved in {} ms (grid={}, reaches={}, lake={}, channel={})",
                rx, rz, String.format("%.0f", ms), p.nx() + "x" + p.nz(),
                cl.reachCount(), countTrue(p.lake()), countTrue(p.channel()));
        return rd;
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
                synchronized (SHARED_CACHE) {
                    SHARED_CACHE.put(key, data);
                    while (SHARED_CACHE.size() > CACHE_CAP) {
                        SHARED_CACHE.remove(SHARED_CACHE.keySet().iterator().next());
                    }
                }
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
