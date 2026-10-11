package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * <h1>{@link ModelHydrologyProvider} pass-1 单飞的<b>并发回归</b>（2026-10-10 性能修复配套）</h1>
 *
 * <h2>被测的生产不变量</h2>
 * <ol>
 *   <li>排队未开跑的 pass-1 任务可被需求线程<b>原子接管执行</b>（修复前，
 *       刚性路径见到 {@code P1_INFLIGHT} 里的 future 就 {@code join} ⇒ 未开跑的预热
 *       也把刚需挡在后面排队）。</li>
 *   <li>接管<b>不导致重复求解</b>（池里那个 runnable 稍后 CAS 失败 ⇒ 空转）。</li>
 *   <li>正在执行（RUNNING）的任务仍等待，不抢占。</li>
 *   <li>失败 ⇒ 撤出在途表 + 不缓存 ⇒ 可重试。</li>
 *   <li>预热名额计数不泄漏、落定后归零（结构性等式 {@code queue == #{仍持名额的任务}}）。</li>
 *   <li>数学产出与执行线程无关：接管路径与预热池路径的管线数组/出口种子逐位相同。</li>
 * </ol>
 *
 * <h2>怎么制造"排队未开跑"（确定性）</h2>
 * <p>预热池是 {@code newFixedThreadPool(SOLVE_THREADS)}（无界队列）。先反射取该池并提交
 * max 个阻塞任务占满全部线程 ⇒ 之后入队的 pass-1 任务只能停在 QUEUED（不拒绝、不执行）
 * —— 正是实机"预热池占满时刚需被排队挡住"的形态，且完全可控。</p>
 *
 * <h2>为什么走反射</h2>
 * <p>只读视图（在途数 / 队列深度 / 缓存命中）全部反射读生产字段，不新增生产 getter；
 * {@code regionPass1}/{@code schedulePreheat} 是私有的（走 {@code columnsFor} 会连带
 * 本区 solve + 八邻 {@code collectIncoming} ≈ 9 个 region ⇒ 单次数分钟）。反射风格同
 * {@code ErosionConcurrencyProbe}。仅"求解计数/失败注入"用 package-private hook
 * （生产端由 {@code hydrologyConcurrencyDiag} 门控，默认零开销）。</p>
 *
 * <p>用法：{@code gradlew runModelProviderConcurrencyProbe}（真实退出码：ALL PASS ⇒ 0）。</p>
 */
public final class ModelProviderConcurrencyProbe {

    /**
     * 轮询等待上限（秒），由 {@code main} 第三参设置（默认 600）—— 仅阴性对照用
     * （把修复改回旧行为后，本探针应<b>快速 FAIL</b> 而非挂 10 分钟）。
     */
    private static long settleTimeoutSec = 600;
    /** 段 3 竞争轮数（竞争窗口是概率性的，用多线程 × 多轮保证聚合覆盖）。 */
    private static final int RACE_BURSTS = 6;
    /** 每轮竞争线程数（越多越容易撞进窗口）。 */
    private static final int RACE_THREADS = 16;

    private static int failures = 0;
    private static final StringBuilder LOG = new StringBuilder();

    private ModelProviderConcurrencyProbe() { }

    // ==========================================================================
    // 反射小工具
    // ==========================================================================

    private static Field field(Class<?> c, String name) throws Exception {
        Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private static final Method M_REGION_PASS1;
    private static final Method M_SCHEDULE_PREHEAT;
    private static final Method M_CLEAR;
    private static final Field F_P1_INFLIGHT;
    private static final Field F_P1_CACHE;
    private static final Field F_PREHEAT_QUEUE;
    static {
        try {
            M_REGION_PASS1 = ModelHydrologyProvider.class
                    .getDeclaredMethod("regionPass1", int.class, int.class);
            M_REGION_PASS1.setAccessible(true);
            M_SCHEDULE_PREHEAT = ModelHydrologyProvider.class
                    .getDeclaredMethod("schedulePreheat", int.class, int.class);
            M_SCHEDULE_PREHEAT.setAccessible(true);
            M_CLEAR = ModelHydrologyProvider.class.getDeclaredMethod("clear");
            M_CLEAR.setAccessible(true);
            F_P1_INFLIGHT = field(ModelHydrologyProvider.class, "P1_INFLIGHT");
            F_P1_CACHE = field(ModelHydrologyProvider.class, "P1_CACHE");
            F_PREHEAT_QUEUE = field(ModelHydrologyProvider.class, "preheatQueue");
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private static Object regionPass1(ModelHydrologyProvider p, int rx, int rz) throws Exception {
        return M_REGION_PASS1.invoke(p, rx, rz);
    }

    private static void schedulePreheat(ModelHydrologyProvider p, int rx, int rz) throws Exception {
        M_SCHEDULE_PREHEAT.invoke(p, rx, rz);
    }

    /** 清空共享缓存/在途表（复用生产 {@code clear()}，不新增 hook）。 */
    private static void clearCaches(ModelHydrologyProvider p) throws Exception {
        M_CLEAR.invoke(p);
    }

    /** 预热队列深度（反射只读 {@code preheatQueue}）。 */
    private static int queueDepth(ModelHydrologyProvider p) throws Exception {
        return ((AtomicInteger) F_PREHEAT_QUEUE.get(p)).get();
    }

    @SuppressWarnings("unchecked")
    private static ConcurrentHashMap<Object, Object> inflight() throws Exception {
        return (ConcurrentHashMap<Object, Object>) F_P1_INFLIGHT.get(null);
    }

    /** 在途 pass-1 任务数（反射只读 {@code P1_INFLIGHT}）。 */
    private static int inflightCount() throws Exception {
        return inflight().size();
    }

    /** 某 region 的 pass-1 是否已入内容缓存（反射只读 {@code P1_CACHE}）。 */
    @SuppressWarnings("unchecked")
    private static boolean pass1Cached(long seed, int rx, int rz) throws Exception {
        Object cache = F_P1_CACHE.get(null);
        synchronized (cache) {
            return ((java.util.Map<Object, ?>) cache).containsKey(cacheKey(seed, rx, rz));
        }
    }

    /** 反射构造 {@code CacheKey(seed,rx,rz)}。 */
    private static Object cacheKey(long seed, int rx, int rz) throws Exception {
        Class<?> ck = Class.forName(ModelHydrologyProvider.class.getName() + "$CacheKey");
        var c = ck.getDeclaredConstructor(long.class, int.class, int.class);
        c.setAccessible(true);
        return c.newInstance(seed, rx, rz);
    }

    /** 在途任务中仍持有预热名额的个数（= 队列计数应等于的值）。 */
    private static int countSlotsHeld() throws Exception {
        int n = 0;
        for (Object task : inflight().values()) {
            AtomicBoolean held = (AtomicBoolean) field(task.getClass(), "slotHeld").get(task);
            if (held.get()) n++;
        }
        return n;
    }

    /** 在途任务中处于 QUEUED（尚未被任何线程接管）的个数。 */
    private static int countQueued() throws Exception {
        int n = 0;
        for (Object task : inflight().values()) {
            AtomicInteger st = (AtomicInteger) field(task.getClass(), "state").get(task);
            if (st.get() == 0) n++;
        }
        return n;
    }

    private static Object pass1Pipe(Object pass1) throws Exception {
        return invoke(pass1, "pipe");
    }

    private static List<?> pass1ExitSeeds(Object pass1) throws Exception {
        return (List<?>) invoke(pass1, "exitSeeds");
    }

    private static Object invoke(Object target, String accessor) throws Exception {
        Method m = target.getClass().getDeclaredMethod(accessor);
        m.setAccessible(true);
        return m.invoke(target);
    }

    /** 在某 region 上武装"下一次 pass-1 求解必失败"（生产 hook，须先开 concurrencyDiag）。 */
    private static void armFailure(int rx, int rz) {
        ModelHydrologyProvider.injectPass1Failure(rx, rz);
    }

    private static void disarmFailures() {
        ModelHydrologyProvider.clearPass1Failures();
    }

    // ==========================================================================
    // 通用
    // ==========================================================================

    private static CellGenerator newGenerator(long seed) {
        TerrainParams tp = TerrainParams.defaults();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        return gen;
    }

    /** 占满预热池全部线程（返回释放闸门）。 */
    private static CountDownLatch blockPreheatPool(ModelHydrologyProvider p) throws Exception {
        ExecutorService pool = (ExecutorService) field(ModelHydrologyProvider.class, "preheat").get(p);
        int max = ((ThreadPoolExecutor) pool).getMaximumPoolSize();
        CountDownLatch started = new CountDownLatch(max);
        CountDownLatch release = new CountDownLatch(1);
        for (int i = 0; i < max; i++) {
            pool.execute(() -> {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
        }
        require(started.await(60, TimeUnit.SECONDS), "预热池阻塞任务未能全部启动");
        LOG.append(String.format("  [setup] 预热池占满：%d 线程被阻塞%n", max));
        return release;
    }

    /** 等所有 pass-1 落定（队列与在途表均归零）。 */
    private static void settle(ModelHydrologyProvider p) throws Exception {
        long t0 = System.nanoTime();
        while ((System.nanoTime() - t0) < settleTimeoutSec * 1_000_000_000L) {
            if (queueDepth(p) == 0 && inflightCount() == 0) return;
            Thread.sleep(50);
        }
        throw new AssertionError(String.format("settle 超时：queue=%d inflight=%d queued=%d",
                queueDepth(p), inflightCount(), countQueued()));
    }

    private interface Case { void run() throws Exception; }

    private static void check(String name, Case c) {
        try {
            c.run();
            LOG.append("[PASS] ").append(name).append('\n');
            System.out.println("[PASS] " + name);
        } catch (Throwable t) {
            failures++;
            LOG.append("[FAIL] ").append(name).append(": ").append(t).append('\n');
            System.out.println("[FAIL] " + name + ": " + t);
        }
    }

    private static void require(boolean cond, String msg) {
        if (!cond) throw new AssertionError(msg);
    }

    /** 跑一段；段内未捕获异常（如 settle 超时）计为一次 FAIL 并继续下一段。 */
    private static void runSegment(String name, Case c) {
        try {
            c.run();
        } catch (Throwable t) {
            failures++;
            LOG.append("[FAIL] ").append(name).append(" 段异常: ").append(t).append('\n');
            System.out.println("[FAIL] " + name + " 段异常: " + t);
        }
    }

    // ==========================================================================
    // 段 1：排队任务被需求线程接管 + 恰好求解一次 + 名额归还
    // ==========================================================================
    private static void segment1(long seed) throws Exception {
        LOG.append("\n=== 段 1：需求线程接管排队任务 ===\n");
        ModelHydrologyProvider p = new ModelHydrologyProvider(newGenerator(seed), seed);
        clearCaches(p);                                   // 静态缓存 ⇒ 段间必须隔离
        ModelHydrologyProvider.resetProbeCounters();
        int[][] neighbors = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};

        CountDownLatch release = blockPreheatPool(p);
        try {
            schedulePreheat(p, 0, 0);                     // 4 个邻区 → 全部 QUEUED

            check("排队 4 个邻区 pass-1（池占满 ⇒ 全部未开跑）", () -> {
                require(queueDepth(p) == 4, "队列深度=" + queueDepth(p));
                require(inflightCount() == 4, "在途=" + inflightCount());
                require(countQueued() == 4, "QUEUED=" + countQueued());
                require(countSlotsHeld() == queueDepth(p),
                        "名额等式不成立：slotsHeld=" + countSlotsHeld() + " queue=" + queueDepth(p));
                LOG.append(String.format("  queue=%d inflight=%d queued=%d slotsHeld=%d%n",
                        queueDepth(p), inflightCount(), countQueued(), countSlotsHeld()));
            });

            check("需求线程原子接管（(1,0) 由需求线程执行，不排队等预热）", () -> {
                ExecutorService demand = Executors.newSingleThreadExecutor(r -> {
                    Thread t = new Thread(r, "probe-demand"); t.setDaemon(true); return t;
                });
                try {
                    var fut = demand.submit(() -> regionPass1(p, 1, 0));
                    Object pass1 = fut.get(settleTimeoutSec, TimeUnit.SECONDS);
                    require(pass1 != null, "接管返回 null");
                    require(ModelHydrologyProvider.pass1SolveCount(1, 0) == 1,
                            "求解次数=" + ModelHydrologyProvider.pass1SolveCount(1, 0));
                    String who = ModelHydrologyProvider.pass1SolverThread(1, 0);
                    require("probe-demand".equals(who), "求解线程=" + who + "（应 = probe-demand）");
                    require(queueDepth(p) == 3,
                            "接管后队列深度=" + queueDepth(p) + "（应 4→3，名额已归还）");
                    require(countSlotsHeld() == queueDepth(p), "接管后名额等式不成立");
                    LOG.append(String.format("  接管线程=%s queue=4→%d%n", who, queueDepth(p)));
                } finally {
                    demand.shutdownNow();
                }
            });
        } finally {
            release.countDown();
        }
        settle(p);

        check("池排空后：每 region 恰好求解一次、队列与在途归零", () -> {
            for (int[] d : neighbors) {
                int c = ModelHydrologyProvider.pass1SolveCount(d[0], d[1]);
                require(c == 1, String.format("region(%d,%d) 求解 %d 次（应 1）", d[0], d[1], c));
            }
            require(queueDepth(p) == 0, "队列未归零=" + queueDepth(p));
            require(inflightCount() == 0, "在途未归零=" + inflightCount());
            LOG.append("  4 邻区各求解 1 次、queue=0 inflight=0\n");
        });
    }

    // ==========================================================================
    // 段 2：失败 ⇒ 撤出在途表 + 不缓存 ⇒ 可重试
    // ==========================================================================
    private static void segment2(long seed) throws Exception {
        LOG.append("\n=== 段 2：失败移除可重试 ===\n");
        ModelHydrologyProvider p = new ModelHydrologyProvider(newGenerator(seed), seed);
        clearCaches(p);
        ModelHydrologyProvider.resetProbeCounters();
        final int fx = 300, fz = 300;

        try {
            armFailure(fx, fz);

            check("注入失败 ⇒ 抛异常 + 不入缓存", () -> {
                boolean threw = false;
                try {
                    regionPass1(p, fx, fz);
                } catch (java.lang.reflect.InvocationTargetException e) {
                    threw = true;
                    LOG.append("  注入异常=").append(e.getCause()).append('\n');
                }
                require(threw, "未抛异常");
                require(!pass1Cached(seed, fx, fz), "失败结果被缓存（违反『绝不缓存半成品』）");
                require(inflightCount() == 0, "失败未撤出在途表：inflight=" + inflightCount());
                require(queueDepth(p) == 0, "队列=" + queueDepth(p));
                require(ModelHydrologyProvider.pass1SolveCount(fx, fz) == 1,
                        "求解尝试=" + ModelHydrologyProvider.pass1SolveCount(fx, fz));
            });

            check("再次调用 ⇒ 重试成功 + 入缓存", () -> {
                disarmFailures();
                Object pass1 = regionPass1(p, fx, fz);
                require(pass1 != null, "重试返回 null");
                require(pass1Cached(seed, fx, fz), "重试结果未入缓存");
                require(inflightCount() == 0, "重试后在途未清=" + inflightCount());
                require(ModelHydrologyProvider.pass1SolveCount(fx, fz) == 2,
                        "求解尝试=" + ModelHydrologyProvider.pass1SolveCount(fx, fz) + "（应 1 失败 + 1 成功）");
                LOG.append("  失败 1 次 + 成功 1 次，缓存已发布、在途已清\n");
            });
        } finally {
            disarmFailures();
        }
    }

    // ==========================================================================
    // 段 3：schedulePreheat 竞争下的名额计数不泄漏（原缺陷回归）
    // ==========================================================================
    private static void segment3(long seed) throws Exception {
        LOG.append("\n=== 段 3：预热名额计数不泄漏（竞争） ===\n");
        ModelHydrologyProvider p = new ModelHydrologyProvider(newGenerator(seed), seed);

        // 本段只验计数不变量，不关心管线产出 ⇒ 失败注入让 pass-1 立即结束
        // （失败路径同样走"占名额 → 接管/落定 → 归还名额"全链，计数语义一致）。
        final int rx = 30_000, rz = 30_000;
        armFailure(rx + 1, rz);
        armFailure(rx - 1, rz);
        armFailure(rx, rz + 1);
        armFailure(rx, rz - 1);

        try {
            for (int round = 0; round < RACE_BURSTS; round++) {
                clearCaches(p);
                ModelHydrologyProvider.resetProbeCounters();
                ModelHydrologyProvider.clearPass1Failures();   // 每轮重新注入 ⇒ 保持毫秒级
                armFailure(rx + 1, rz);
                armFailure(rx - 1, rz);
                armFailure(rx, rz + 1);
                armFailure(rx, rz - 1);
                CountDownLatch release = blockPreheatPool(p);
                final int r = round;
                try {
                    // 竞争点：多线程同时对同一 region 调 schedulePreheat
                    //   ⇒ 同一批邻区键上出现"containsKey 未命中 → putIfAbsent 已存在"的交叉。
                    CountDownLatch go = new CountDownLatch(1);
                    CountDownLatch done = new CountDownLatch(RACE_THREADS);
                    AtomicInteger errs = new AtomicInteger();
                    for (int t = 0; t < RACE_THREADS; t++) {
                        Thread th = new Thread(() -> {
                            try {
                                go.await();
                                schedulePreheat(p, rx, rz);
                            } catch (Throwable e) {
                                errs.incrementAndGet();
                            } finally {
                                done.countDown();
                            }
                        }, "race-" + r + "-" + t);
                        th.setDaemon(true);
                        th.start();
                    }
                    go.countDown();
                    require(done.await(60, TimeUnit.SECONDS), "竞争线程未结束");

                    check(String.format("轮 %d：名额等式成立且未超上限（threads=%d）", r, RACE_THREADS),
                            () -> {
                        require(errs.get() == 0, "竞争线程异常=" + errs.get());
                        int depth = queueDepth(p);
                        int held = countSlotsHeld();
                        // 核心不变量（旧实现：queue 虚增而 slotsHeld 少 ⇒ 永不归零）
                        require(depth == held, "名额泄漏：queue=" + depth + " slotsHeld=" + held);
                        require(depth <= 8, "队列超上限：queue=" + depth);
                        LOG.append(String.format("  queue=%d slotsHeld=%d queued=%d inflight=%d%n",
                                depth, held, countQueued(), inflightCount()));
                    });
                } finally {
                    release.countDown();
                }
                settle(p);
            }
        } finally {
            disarmFailures();
        }

        check("全部落定后：queue=0 / inflight=0 / 无任务仍持名额", () -> {
            require(queueDepth(p) == 0, "队列未归零=" + queueDepth(p));
            require(inflightCount() == 0, "在途未归零=" + inflightCount());
            require(countSlotsHeld() == 0, "仍有任务持有名额=" + countSlotsHeld());
            // 本段刻意【不】断言"每 region 恰好求解一次"：本段注入失败，
            // 而"失败 ⇒ 撤出在途表 ⇒ 可重试"是设计语义（见段 2）。
            LOG.append("  ").append(RACE_BURSTS)
                    .append(" 轮竞争后：queue=0 inflight=0 slotsHeld=0\n");
        });
    }

    // ==========================================================================
    // 段 4：接管路径 vs 预热池路径 ⇒ 数学产出逐位相同
    // ==========================================================================
    private static void segment4(long seed) throws Exception {
        LOG.append("\n=== 段 4：接管 vs 池执行 ⇒ 产出逐位相同 ===\n");
        ModelHydrologyProvider p = new ModelHydrologyProvider(newGenerator(seed), seed);
        final int rx = 40_000, rz = 40_000;          // 预热宿主 region
        final int nx = rx + 1, nz = rz;              // 被预热的邻区（正东）

        clearCaches(p);
        ModelHydrologyProvider.resetProbeCounters();

        // ---- 路径 A：需求线程接管 ----
        CountDownLatch release = blockPreheatPool(p);
        Object pass1Takeover;
        try {
            schedulePreheat(p, rx, rz);
            pass1Takeover = regionPass1(p, nx, nz);      // 主线程 = 需求线程接管
            require(pass1Takeover != null, "接管返回 null");
        } finally {
            release.countDown();
        }
        settle(p);

        // ---- 路径 B：预热池自己跑（重新求解同一 region）----
        clearCaches(p);
        ModelHydrologyProvider.resetProbeCounters();
        schedulePreheat(p, rx, rz);
        long t0 = System.nanoTime();
        while (!pass1Cached(seed, nx, nz)) {
            require((System.nanoTime() - t0) < settleTimeoutSec * 1_000_000_000L, "等待预热池求解超时");
            Thread.sleep(50);
        }
        Object pass1Pool = regionPass1(p, nx, nz);       // 缓存命中
        settle(p);

        check("同 region 两条路径产出逐位相同（管线数组 + 出口种子）", () -> {
            Object a = pass1Pipe(pass1Takeover), b = pass1Pipe(pass1Pool);
            require(Arrays.equals((double[]) invoke(a, "bed"), (double[]) invoke(b, "bed")), "bed 不同");
            require(Arrays.equals((double[]) invoke(a, "level"), (double[]) invoke(b, "level")), "level 不同");
            require(Arrays.equals((int[]) invoke(a, "parent"), (int[]) invoke(b, "parent")), "parent 不同");
            require(Arrays.equals((double[]) invoke(a, "discharge"), (double[]) invoke(b, "discharge")),
                    "discharge 不同");
            require(Arrays.equals((boolean[]) invoke(a, "channel"), (boolean[]) invoke(b, "channel")),
                    "channel 不同");
            require(Arrays.equals((boolean[]) invoke(a, "lake"), (boolean[]) invoke(b, "lake")), "lake 不同");
            require(Arrays.equals((boolean[]) invoke(a, "sea"), (boolean[]) invoke(b, "sea")), "sea 不同");
            require(Arrays.equals((boolean[]) invoke(a, "resolved"), (boolean[]) invoke(b, "resolved")),
                    "resolved 不同");
            require(Arrays.equals((double[]) invoke(a, "waterSurface"), (double[]) invoke(b, "waterSurface")),
                    "waterSurface 不同");
            List<?> sa = pass1ExitSeeds(pass1Takeover), sb = pass1ExitSeeds(pass1Pool);
            require(sa.equals(sb), "出口种子不同：" + sa.size() + " vs " + sb.size());
            LOG.append(String.format("  管线 9 数组逐位相同；出口种子 %d 条相同%n", sa.size()));
        });
    }

    public static void main(String[] args) throws Exception {
        // 与实机配置默认一致（config hydrologyDeterministicSkeleton=true）——
        // 必须在触碰 ModelHydrologyProvider 类之前设置（SKELETON_TRACE 类初始化时解析）。
        System.setProperty("geogenesis.skeletonTrace", "true");
        // 并发回归诊断（运行期读取）：启用求解计数/失败注入 hook。
        System.setProperty("geogenesis.hydrologyConcurrencyDiag", "true");

        // 可选段选择：`-PprobeArgs="seed seg1,seg3"`（调试单段用；默认全部）
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 9139912035078620160L;
        java.util.Set<String> want = new java.util.HashSet<>();
        if (args.length > 1) {
            for (String s : args[1].split(",")) want.add(s.trim().toLowerCase());
        }
        boolean all = want.isEmpty();
        if (args.length > 2) settleTimeoutSec = Long.parseLong(args[2]);   // 阴性对照用
        LOG.append("=== ModelProviderConcurrencyProbe ===\n")
                .append("seed=").append(seed)
                .append(" segments=").append(all ? "all" : want)
                .append(" settleTimeoutSec=").append(settleTimeoutSec)
                .append(" modelRegion=").append(System.getProperty("geogenesis.modelRegion", "512"))
                .append(" halo=").append(System.getProperty("geogenesis.modelHalo", "96"))
                .append(" step=").append(System.getProperty("geogenesis.modelStep", "4"))
                .append('\n');
        System.out.print(LOG);
        LOG.setLength(0);

        long t0 = System.nanoTime();
        try {
            if (all || want.contains("seg1")) runSegment("seg1", () -> segment1(seed));
            if (all || want.contains("seg2")) runSegment("seg2", () -> segment2(seed));
            if (all || want.contains("seg3")) runSegment("seg3", () -> segment3(seed));
            if (all || want.contains("seg4")) runSegment("seg4", () -> segment4(seed));
        } finally {
            disarmFailures();
        }

        LOG.append(String.format("%n总耗时 %.1f s%n", (System.nanoTime() - t0) / 1e9));
        LOG.append("边界：真实退出码由 System.exit 承载；不改世界退出生命周期（不在范围内）\n");
        LOG.append(String.format("%n判定：%s%n", failures == 0 ? "ALL PASS" : "★ FAIL"));
        System.out.print(LOG);

        java.nio.file.Files.createDirectories(java.nio.file.Path.of("build/modelprovider-concurrency"));
        java.nio.file.Files.writeString(
                java.nio.file.Path.of("build/modelprovider-concurrency/metrics.txt"), LOG.toString());
        System.exit(failures == 0 ? 0 : 1);
    }
}
