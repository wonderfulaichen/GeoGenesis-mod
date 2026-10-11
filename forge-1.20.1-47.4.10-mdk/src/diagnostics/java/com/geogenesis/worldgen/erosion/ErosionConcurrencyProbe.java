package com.geogenesis.worldgen.erosion;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 侵蚀 tile 并发的<b>死锁回归探针</b>（★ 2026-09-11，D3 修复配套）。
 *
 * <p><b>背景</b>：{@code ErosionEngine} 的两处平滑（{@code smoothErosionResult} /
 * {@code smoothDepositionZones}）曾用 {@code TILE_SAMPLER.execute + CountDownLatch.await}
 * 做并行。该模式会<b>永久死锁</b>：
 * <ul>
 *   <li>{@code TILE_SAMPLER} 是<b>有界池</b>（core 8 / max 16 / 队列 64 / CallerRunsPolicy）；</li>
 *   <li>但每次仅提交约 <b>8</b> 个子任务 → <b>队列永不满</b>
 *       → 唯一的救命稻草 {@code CallerRunsPolicy}（队满时由提交者自己跑）<b>永不触发</b>；</li>
 *   <li>于是当 16 个池线程同时走到平滑、全部阻塞在 {@code await} 时，
 *       <b>没有任何线程</b>能取走队列里的子任务 → 死锁。</li>
 *   <li>且 {@code CellGenerator:603} 会在池内任务里调 {@code generateErosionTile}
 *       → 构成"池内提交同池任务"，即 {@code CellGenerator:42} 记录过的
 *       <b>"池饥饿死锁 27 轮"</b>。</li>
 * </ul>
 *
 * <p><b>修复</b>：改用 {@code IntStream.range(...).parallel()}（ForkJoinPool.commonPool，
 * work-stealing）——<b>调用线程会参与执行子任务</b>，不存在"等自己"。</p>
 *
 * <p>本探针用<b>硬超时</b>守护该不变量：并发跑大量 tile，若挂起则 FAIL。</p>
 *
 * <p>用法：{@code gradlew runErosionConcurrencyProbe [seed] [threads]}</p>
 */
public final class ErosionConcurrencyProbe {

    /** 并发线程数（&gt; TILE_SAMPLER 上限 16 才有机会触发原死锁）。 */
    private static final int DEFAULT_THREADS = 24;
    /** 每线程采样次数。 */
    private static final int PER_THREAD = 40;
    /** 硬超时（秒）——超过即判定死锁。 */
    private static final long TIMEOUT_SEC = 180;

    private ErosionConcurrencyProbe() {}

    private static Object field(Object target, String name) throws Exception {
        var f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        return f.get(target);
    }

    private static void setField(Object target, String name, Object value) throws Exception {
        var f = target.getClass().getDeclaredField(name);
        f.setAccessible(true);
        f.set(target, value);
    }

    private static Object tile(CellGenerator gen, boolean neighbor) throws Exception {
        var m = CellGenerator.class.getDeclaredMethod(neighbor ? "neighborTile"
                : "getOrGenTileUnchecked", neighbor ? new Class<?>[]{long.class, int.class, int.class}
                : new Class<?>[]{int.class, int.class});
        m.setAccessible(true);
        return neighbor ? m.invoke(gen, 0L, 0, 0) : m.invoke(gen, 0, 0);
    }

    private static CellGenerator newGenerator() {
        var p = TerrainParams.defaults();
        var g = new CellGenerator(p, p.minY(), p.maxY());
        g.seed(9139912035078620160L);
        return g;
    }

    private static boolean publicationCheck(boolean neighbor) throws Exception {
        var gen = newGenerator();
        @SuppressWarnings("unchecked")
        var cache = (java.util.concurrent.ConcurrentHashMap<Long, Object>) field(gen, "erosionTileCache");
        var unpublished = new AtomicInteger();
        var flights = new java.util.concurrent.ConcurrentHashMap<Long,
                java.util.concurrent.CompletableFuture<Object>>() {
            @Override
            public java.util.concurrent.CompletableFuture<Object> putIfAbsent(Long key,
                    java.util.concurrent.CompletableFuture<Object> value) {
                value.whenComplete((r, error) -> {
                    if (error == null && cache.get(key) != r) unpublished.incrementAndGet();
                });
                return super.putIfAbsent(key, value);
            }
        };
        setField(gen, "erosionTilesInFlight", flights);
        Object result = tile(gen, neighbor);
        boolean pass = result != null && unpublished.get() == 0 && cache.get(0L) == result
                && flights.isEmpty();
        System.out.printf("[singleflight] publish-before-complete neighbor=%s unpublished=%d %s%n",
                neighbor, unpublished.get(), pass ? "PASS" : "FAIL");
        return pass;
    }

    private static boolean staleMissCheck(boolean neighbor) throws Exception {
        var gen = newGenerator();
        var observedMiss = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        var first = new java.util.concurrent.atomic.AtomicBoolean(true);
        var cache = new java.util.concurrent.ConcurrentHashMap<Long, Object>() {
            @Override
            public Object get(Object key) {
                Object result = super.get(key);
                if (result == null && Thread.currentThread().getName().equals("delayed-miss")
                        && first.compareAndSet(true, false)) {
                    observedMiss.countDown();
                    try {
                        if (!resume.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("resume timeout");
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(e);
                    }
                }
                return result;
            }
        };
        setField(gen, "erosionTileCache", cache);
        var worker = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            var t = new Thread(r, "delayed-miss"); t.setDaemon(true); return t;
        });
        try {
            var delayed = worker.submit(() -> tile(gen, neighbor));
            if (!observedMiss.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("miss timeout");
            Object published = tile(gen, neighbor);
            resume.countDown();
            Object received = delayed.get(30, TimeUnit.SECONDS);
            boolean pass = received == published;
            System.out.printf("[singleflight] stale-miss-recheck neighbor=%s same-object=%s %s%n",
                    neighbor, pass, pass ? "PASS" : "FAIL");
            return pass;
        } finally {
            resume.countDown();
            worker.shutdownNow();
        }
    }

    private static boolean saturatedPrewarmCheck() throws Exception {
        java.util.concurrent.ThreadPoolExecutor pool;
        try {
            var f = CellGenerator.class.getDeclaredField("TILE_PREHEATER");
            f.setAccessible(true);
            pool = (java.util.concurrent.ThreadPoolExecutor) f.get(null);
        } catch (NoSuchFieldException e) {
            pool = (java.util.concurrent.ThreadPoolExecutor) CellGenerator.TILE_SAMPLER;
        }
        int max = pool.getMaximumPoolSize();
        var started = new CountDownLatch(max);
        var release = new CountDownLatch(1);
        Runnable blocker = () -> {
            started.countDown();
            try { release.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        };
        var gen = newGenerator();
        var caller = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            var t = new Thread(r, "saturated-prewarm-caller"); t.setDaemon(true); return t;
        });
        try {
            int capacity = pool.getQueue().remainingCapacity();
            for (int i = 0; i < max + capacity; i++) pool.execute(blocker);
            if (!started.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("pool saturation timeout");
            var future = caller.submit(() -> gen.prewarmErosionTiles(0, 0, 0, 2));
            future.get(2, TimeUnit.SECONDS);
            boolean pass = gen.erosionTileCacheSize() == 0;
            System.out.printf("[singleflight] saturated-prewarm no-inline-generation=%s %s%n",
                    pass, pass ? "PASS" : "FAIL");
            return pass;
        } finally {
            release.countDown();
            caller.shutdownNow();
        }
    }

    private static boolean mixedConcurrentCheck() throws Exception {
        var gen = newGenerator();
        var creations = new AtomicInteger();
        var flights = new java.util.concurrent.ConcurrentHashMap<Long,
                java.util.concurrent.CompletableFuture<Object>>() {
            @Override
            public java.util.concurrent.CompletableFuture<Object> putIfAbsent(Long key,
                    java.util.concurrent.CompletableFuture<Object> value) {
                var prior = super.putIfAbsent(key, value);
                if (prior == null) creations.incrementAndGet();
                return prior;
            }
        };
        setField(gen, "erosionTilesInFlight", flights);
        var start = new CountDownLatch(1);
        var workers = java.util.concurrent.Executors.newFixedThreadPool(12, r -> {
            var t = new Thread(r, "mixed-tile-caller"); t.setDaemon(true); return t;
        });
        try {
            var results = new ArrayList<java.util.concurrent.Future<Object>>();
            for (int i = 0; i < 12; i++) {
                boolean neighbor = (i & 1) == 0;
                results.add(workers.submit(() -> { start.await(); return tile(gen, neighbor); }));
            }
            start.countDown();
            Object first = results.get(0).get(30, TimeUnit.SECONDS);
            boolean pass = first != null;
            for (var f : results) pass &= f.get(30, TimeUnit.SECONDS) == first;
            var direct = CellGenerator.class.getDeclaredMethod("generateErosionTile", int.class, int.class);
            direct.setAccessible(true);
            Object baseline = direct.invoke(newGenerator(), 0, 0);
            var delta = first.getClass().getDeclaredField("delta");
            delta.setAccessible(true);
            pass &= java.util.Arrays.deepEquals((float[][]) delta.get(first), (float[][]) delta.get(baseline));
            pass &= creations.get() == 1 && flights.isEmpty();
            System.out.printf("[singleflight] mixed-12-callers creations=%d direct-delta-identical=%s %s%n",
                    creations.get(), pass, pass ? "PASS" : "FAIL");
            return pass;
        } finally {
            start.countDown();
            workers.shutdownNow();
        }
    }

    private static void runSingleFlightRegression() throws Exception {
        boolean pass = true;
        for (boolean neighbor : new boolean[]{false, true}) {
            pass &= publicationCheck(neighbor);
            pass &= staleMissCheck(neighbor);
        }
        pass &= mixedConcurrentCheck();
        if (Boolean.getBoolean("probe.saturation")) pass &= saturatedPrewarmCheck();
        System.out.println(pass ? "=== SINGLEFLIGHT ALL PASS ===" : "=== SINGLEFLIGHT FAIL ===");
        System.exit(pass ? 0 : 1);
    }

    private static void runOasisCostProbe(String[] args) throws Exception {
        var gen = newGenerator();
        var terrain = new com.geogenesis.worldgen.terrain.GeoGenesisTerrain(gen);
        int bx = args.length > 1 ? Integer.parseInt(args[1]) : -6744;
        int bz = args.length > 2 ? Integer.parseInt(args[2]) : -5752;
        var base = gen.sample(bx / gen.params().horizontalScale(), bz / gen.params().horizontalScale());
        System.out.println("[oasis] block=" + bx + "," + bz + " biome=" + base.biomeType);
        com.geogenesis.diagnostics.WorldGenProfiler.configure(true, 0);
        var worker = java.util.concurrent.Executors.newSingleThreadExecutor(r -> {
            var t = new Thread(r, "oasis-fast-path"); t.setDaemon(true); return t;
        });
        try {
            long start = System.nanoTime();
            var cell = worker.submit(() -> terrain.sampleCellLight(bx, bz)).get(120, TimeUnit.SECONDS);
            System.out.println("[oasis] ms=" + (System.nanoTime() - start) / 1e6
                    + " tileCache=" + gen.erosionTileCacheSize() + " distance=" + cell.riverDistance);
            if (Boolean.getBoolean("geogenesis.modelPipeline")) {
                boolean pass = base.biomeType == com.geogenesis.worldgen.climate.WhittakerType.DESERT
                        && gen.erosionTileCacheSize() == 0 && cell.riverDistance == Double.POSITIVE_INFINITY;
                // 完整路径也共用门控；验证旧的有限距离不会残留。
                var fill = terrain.getClass().getDeclaredMethod("fillRiverDistance",
                        com.geogenesis.worldgen.terrain.Cell.class, double.class, double.class);
                fill.setAccessible(true);
                cell.riverDistance = 0;
                fill.invoke(terrain, cell, bx / gen.params().horizontalScale(), bz / gen.params().horizontalScale());
                pass &= cell.riverDistance == Double.POSITIVE_INFINITY;
                for (int d : new int[]{-4, 0, 4}) {
                    var nearby = terrain.sampleCellLight(bx + d, bz - d);
                    pass &= nearby.riverDistance == Double.POSITIVE_INFINITY && gen.erosionTileCacheSize() == 0;
                }
                System.out.println("[oasis] model fast/full gating zero-cold-tiles " + (pass ? "PASS" : "FAIL"));
                if (!pass) throw new AssertionError("model 群系查询触发 legacy/冷侵蚀，或测试未命中沙漠");
            }
            com.geogenesis.diagnostics.WorldGenProfiler.reportNow("oasis fast path cost");
        } finally {
            worker.shutdownNow();
        }
    }

    private static void previewSeedCheck() throws Exception {
        long seed = 9139912035078620160L;
        var gen = newGenerator();
        var terrain = new com.geogenesis.worldgen.terrain.GeoGenesisTerrain(gen);
        var engine = field(terrain, "hydrologyExperiment");
        var provider = field(engine, "modelProvider");
        long before = (long) field(provider, "seed");
        terrain.seed(seed);
        long after = (long) field(provider, "seed");
        boolean pass = before == 0L && after == seed;
        System.out.printf("[preview-seed] before=%d after=%d expected=%d %s%n",
                before, after, seed, pass ? "PASS" : "FAIL");
        if (!pass) throw new AssertionError("terrain.seed 未播种生产水文引擎");
    }

    public static void main(String[] args) throws Exception {
        if (args.length > 0 && "preview-seed".equals(args[0])) {
            previewSeedCheck();
            return;
        }
        if (args.length > 0 && "oasis".equals(args[0])) {
            runOasisCostProbe(args);
            return;
        }
        if (args.length > 0 && "singleflight".equals(args[0])) {
            runSingleFlightRegression();
            return;
        }
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        int threads = args.length > 1 ? Integer.parseInt(args[1]) : DEFAULT_THREADS;
        TerrainParams tp = TerrainParams.defaults();
        System.out.printf("=== ErosionConcurrencyProbe seed=%d threads=%d perThread=%d ===%n",
            seed, threads, PER_THREAD);

        // ================= [1] 并发无死锁 =================
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        CountDownLatch start = new CountDownLatch(1);
        CountDownLatch done = new CountDownLatch(threads);
        AtomicInteger errors = new AtomicInteger();

        List<Thread> workers = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            final int tid = t;
            Thread th = new Thread(() -> {
                try {
                    start.await();
                    // 每线程取【互不相同】的坐标 → 强制生成大量不同侵蚀 tile（制造池压力）
                    for (int i = 0; i < PER_THREAD; i++) {
                        double x = 12000 + tid * 5000 + i * 48.0;
                        double z = 12000 + i * 48.0;
                        gen.sampleWu(x, z);
                    }
                } catch (Throwable e) {
                    errors.incrementAndGet();
                    e.printStackTrace();
                } finally {
                    done.countDown();
                }
            }, "ErosionProbe-" + t);
            th.setDaemon(true);
            workers.add(th);
            th.start();
        }

        long t0 = System.nanoTime();
        start.countDown();
        boolean finished = done.await(TIMEOUT_SEC, TimeUnit.SECONDS);
        double sec = (System.nanoTime() - t0) / 1e9;

        boolean pass1 = finished && errors.get() == 0;
        System.out.printf("[1] 并发 %d 线程 × %d 次 sampleWu: %s  用时 %.2fs  线程错误=%d  tile缓存=%d %s%n",
            threads, PER_THREAD, finished ? "全部完成" : "★ 超时（疑似死锁）★",
            sec, errors.get(), gen.erosionTileCacheSize(), pass1 ? "PASS" : "FAIL");
        if (!finished) {
            System.out.println("    → 超时：检查是否有『池内提交同池任务 + 阻塞等待』的成环路径");
        }

        // ================= [2] 并行确定性 =================
        //   并行化的前提是【输出与调度无关】：两个独立实例（同 seed）跑同一批坐标，
        //   结果必须逐位一致。若并行写冲突，这里会暴露。
        CellGenerator a = new CellGenerator(tp, tp.minY(), tp.maxY());
        a.seed(seed);
        CellGenerator b = new CellGenerator(tp, tp.minY(), tp.maxY());
        b.seed(seed);
        int n = 0, mismatchE = 0, mismatchH = 0, shown = 0;
        for (double z = -8000; z <= 8000; z += 311) {
            for (double x = -8000; x <= 8000; x += 313) {
                var ca = a.sampleWu(x, z);
                var cb = b.sampleWu(x, z);
                n++;
                // 用 Double.compare 而非 != ：后者对 NaN 恒真（NaN != NaN），
                // 会把"两者都是 NaN"误报成不一致。
                boolean eDiff = Double.compare(ca.e, cb.e) != 0;
                boolean hDiff = Double.compare(ca.height, cb.height) != 0;
                if (eDiff) mismatchE++;
                if (hDiff) mismatchH++;
                if ((eDiff || hDiff) && shown < 5) {
                    shown++;
                    System.out.printf("    失配 @ x=%.1f z=%.1f : a.e=%.17g b.e=%.17g | a.h=%.17g b.h=%.17g%n",
                        x, z, ca.e, cb.e, ca.height, cb.height);
                }
            }
        }
        boolean pass2 = n > 0 && mismatchE == 0 && mismatchH == 0;
        System.out.printf("[2] 并行确定性(两独立实例, n=%d): e 不一致=%d  height 不一致=%d %s%n",
            n, mismatchE, mismatchH, pass2 ? "PASS" : "FAIL");

        boolean all = pass1 && pass2;
        System.out.println(all ? "=== ALL PASS ===" : "=== FAILURES PRESENT ===");
        if (!all) System.exit(1);
    }
}
