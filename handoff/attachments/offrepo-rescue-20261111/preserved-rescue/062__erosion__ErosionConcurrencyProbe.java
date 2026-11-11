package com.geogenesis.worldgen.erosion;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.geogenesis.config.GeoGenesisConfig;
import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
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
 * <p>用法：{@code gradlew runErosionConcurrencyProbe [seed] [threads]}
 * · {@code gradlew runErosionConcurrencyProbe -PprobeArgs="singleflight"}
 * · {@code gradlew runErosionConcurrencyProbe -PprobeArgs="cache-atomicity"}
 * · {@code gradlew runErosionConcurrencyProbe -PprobeArgs="height-only"}（erodedHeight 等价回归）</p>
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

    // ===================== ★ 2026-10-12 缓存确定性回归 =====================
    //
    // 【缺陷】terrainEQuick / landFactorAt / rockResistanceAt 的缓存原为
    //   【两个 AtomicLongArray】（键与值各写一次）。读者"先命中 key、再读 value"
    //   两步之间，若另一线程用【不同坐标】覆盖同一槽，读者会拿到【别人坐标的值】。
    //   这不是撕裂读（64 位各自原子），而是静默错值 —— 液滴并行 + 水文多线程下真实存在。
    //
    // 【修复】键与值合并进同一【不可变 entry】，经 AtomicReferenceArray 发布
    //   ⇒ 读者一次 get 即得自洽的 key+value；哈希槽只定位，身份由完整坐标比较。
    //
    // 【判据】把 N 个【故意撞同一槽】的坐标丢给多线程：
    //   · 默认路径：与反射调用的【无缓存真值】（生产方法 terrainEQuickCompute 等）
    //     逐位对照，mismatches 必须 == 0；
    //   · 负对照（对照装置，不碰生产代码）：现场合成【旧式双数组结构】，
    //     证明"命中后同槽被覆盖"确实能让读者拿到错值 —— 若对照也 0 错则判据无鉴别力。

    /** 每线程重复次数（每次轮转一个不同坐标）。 */
    private static final int CACHE_ATOMICITY_PER_THREAD = 6000;
    /** 并发线程数（撞同一哈希槽）。 */
    private static final int CACHE_ATOMICITY_THREADS = 8;

    private record SlotCoord(double wx, double wz) {}
    private record SlotExpect(double wx, double wz, long bits) {}

    private static long slotOf(long key, int bits) {
        return (key * 0x9E3779B97F4A7C15L) >>> (64 - bits);
    }

    /** 找出 count 个【哈希槽相同但坐标不同】的坐标（槽只定位不当身份 ⇒ 必须能区分）。 */
    private static List<SlotCoord> collisionSet(int count, int bits) {
        var bySlot = new java.util.HashMap<Long, List<SlotCoord>>();
        for (int i = 0; i < 500_000; i++) {
            double wx = i * 0.5 + 0.25, wz = i * 0.5 + 0.125;
            long key = Double.doubleToRawLongBits(wx)
                    ^ Long.rotateLeft(Double.doubleToRawLongBits(wz), 32);
            if (key == Long.MIN_VALUE) key = Long.MAX_VALUE;
            var list = bySlot.computeIfAbsent(slotOf(key, bits), s -> new ArrayList<>());
            boolean same = false;
            for (var c : list) {
                if (c.wx() == wx && c.wz() == wz) { same = true; break; }
            }
            if (same) continue;
            list.add(new SlotCoord(wx, wz));
            if (list.size() >= count) return new ArrayList<>(list);
        }
        throw new IllegalStateException("找不到 " + count + " 个同槽坐标");
    }

    /** 反射取生产【无缓存计算】：cached 路径的真值来源。 */
    private static SlotExpect truth(CellGenerator gen, SlotCoord c) throws Exception {
        var m = CellGenerator.class.getDeclaredMethod("terrainEQuickCompute", double.class, double.class);
        m.setAccessible(true);
        Object raw = m.invoke(gen, c.wx(), c.wz());
        if (!(raw instanceof Double d) || !Double.isFinite(d)) return null;   // 非有限值不参与
        return new SlotExpect(c.wx(), c.wz(), Double.doubleToRawLongBits(d));
    }

    private static boolean cacheAtomicityEqCheck() throws Exception {
        var gen = newGenerator();
        var coords = collisionSet(3, 17);
        var alphas = new ArrayList<SlotExpect>();
        for (var c : coords) {
            SlotExpect t = truth(gen, c);
            if (t != null) alphas.add(t);
        }
        if (alphas.size() < 2) {
            System.out.println("[cache-atomicity] EQ 同槽真值不足(finite=" + alphas.size() + ") FAIL");
            return false;
        }
        return slamSlot("EQ", CACHE_ATOMICITY_PER_THREAD, (k, tid) ->
                gen.terrainEQuick(alphas.get((k + tid) % alphas.size()).wx(),
                        alphas.get((k + tid) % alphas.size()).wz()),
                (k, tid) -> Double.longBitsToDouble(alphas.get((k + tid) % alphas.size()).bits()));
    }

    /** 并发撞同一槽：cached 结果 vs 预计算真值 逐位对照。 */
    private interface Sample { double at(int k, int tid); }

    private static boolean slamSlot(String tag, int perThread,
            Sample cached, Sample expected) throws Exception {
        var pool = java.util.concurrent.Executors.newFixedThreadPool(CACHE_ATOMICITY_THREADS, r -> {
            var t = new Thread(r, tag + "-slot-slam"); t.setDaemon(true); return t;
        });
        var mismatches = new AtomicInteger();
        var checked = new AtomicInteger();
        var sample = new java.util.concurrent.atomic.AtomicReference<String>();
        try {
            var start = new CountDownLatch(1);
            var futures = new ArrayList<java.util.concurrent.Future<?>>();
            for (int t = 0; t < CACHE_ATOMICITY_THREADS; t++) {
                final int tid = t;
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int k = 0; k < perThread; k++) {
                        double got = cached.at(k, tid);
                        double want = expected.at(k, tid);
                        checked.incrementAndGet();
                        if (Double.compare(got, want) != 0) {
                            mismatches.incrementAndGet();
                            sample.compareAndSet(null, "k=" + k + " expected=" + want + " got=" + got);
                        }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (var f : futures) f.get(120, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        boolean pass = mismatches.get() == 0 && checked.get() > 0;
        System.out.printf("[cache-atomicity] %s 同槽并发 检查=%d mismatches=%d %s%s%n",
                tag, checked.get(), mismatches.get(), pass ? "PASS" : "FAIL",
                sample.get() == null ? "" : ("  例:" + sample.get()));
        return pass;
    }

    /**
     * 负对照（对照装置，不碰生产代码）：现场合成【旧式双数组结构】——
     * 与旧实现同构（keys/vals 各自独立数组、先键后值），
     * 证明"读者命中 key 后同槽的 value 被别的坐标覆盖"确实能产出错值。
     */
    private static boolean cacheAtomicityOldShapeControl() {
        final int SLOTS = 1;
        final var keys = new java.util.concurrent.atomic.AtomicLongArray(SLOTS);
        final var vals = new java.util.concurrent.atomic.AtomicLongArray(SLOTS);
        final double va = 1.0, vb = 2.0;
        final long keyA = 111L, keyB = 222L;
        keys.set(0, keyA);
        vals.set(0, Double.doubleToRawLongBits(va));
        var start = new CountDownLatch(1);
        var errors = new AtomicInteger();
        var reads = new AtomicInteger();
        var stop = new java.util.concurrent.atomic.AtomicBoolean(false);
        var writer = new Thread(() -> {
            try {
                start.await();
                // A 占槽 0；B 覆盖槽 0（先值后键）；再回到 A。天然存在"键=A、值=vb"的窗口。
                while (!stop.get()) {
                    vals.set(0, Double.doubleToRawLongBits(va));
                    keys.set(0, keyA);
                    vals.set(0, Double.doubleToRawLongBits(vb));
                    keys.set(0, keyB);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "old-shape-writer");
        var reader = new Thread(() -> {
            try {
                start.await();
                long want = Double.doubleToRawLongBits(va);
                for (int i = 0; i < 50_000_000; i++) {
                    if (keys.get(0) == keyA) {                 // 命中 A 的 key
                        reads.incrementAndGet();
                        if (vals.get(0) != want) errors.incrementAndGet();   // 却读到 B 的值
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }, "old-shape-reader");
        writer.setDaemon(true);
        reader.setDaemon(true);
        writer.start();
        reader.start();
        start.countDown();
        try {
            reader.join(20_000);
            stop.set(true);
            writer.join(2_000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        boolean torn = errors.get() > 0;
        System.out.printf("[cache-atomicity] 负对照(旧双数组同构) 命中=%d 读到异坐标值=%d %s%n",
                reads.get(), errors.get(), torn ? "PASS（缺陷可复现 ⇒ 判据有鉴别力）" : "FAIL（判据无效）");
        return torn;
    }

    /** HR 缓存：真值须等于生产 rockResistanceAt 的实现（16wu 网格中心采样）。 */
    private static boolean cacheAtomicityHrCheck() throws Exception {
        final int QUANT = CellGenerator.ROCK_QUANT;
        var gen = newGenerator();
        var coords = new ArrayList<SlotCoord>();
        for (int i = 0; i < 8; i++) coords.add(new SlotCoord(10.0 + i * 0.5, 20.0 + i * 0.5));

        var contF = CellGenerator.class.getDeclaredField("continent");
        contF.setAccessible(true);
        Object cont = contF.get(gen);
        var contSample = cont.getClass().getMethod("sample", double.class, double.class);
        var biasF = CellGenerator.class.getDeclaredField("continentBias");
        biasF.setAccessible(true);
        double bias = (double) biasF.get(gen);
        var strataF = CellGenerator.class.getDeclaredField("strata");
        strataF.setAccessible(true);
        Object strata = strataF.get(gen);
        var layerAt = strata.getClass().getMethod("layerAt", double.class, double.class);
        var tecF = CellGenerator.class.getDeclaredField("tectonic");
        tecF.setAccessible(true);
        Object tectonic = tecF.get(gen);
        var tecSample = tectonic.getClass().getMethod("sample", double.class, double.class);
        var shellFromC = tectonic.getClass().getMethod("shellFromC", double.class);
        var resistanceAt = Class.forName("com.geogenesis.worldgen.terrain.StratumField")
                .getMethod("resistanceAt", tecSample.getReturnType(), boolean.class, int.class);

        final var base = new java.util.HashMap<Double, Double>();
        for (var c : coords) {
            long qx = Math.floorDiv((long) Math.floor(c.wx()), QUANT);
            long qz = Math.floorDiv((long) Math.floor(c.wz()), QUANT);
            double sx = (qx + 0.5) * QUANT, sz = (qz + 0.5) * QUANT;
            double cb = (double) contSample.invoke(cont, sx, sz) - bias;
            Object ts = tecSample.invoke(tectonic, sx, sz);
            int layer = (int) layerAt.invoke(strata, sx, sz);
            boolean land = (double) shellFromC.invoke(tectonic, cb) > 0.5;
            base.put(c.wx(), (double) resistanceAt.invoke(null, ts, land, layer));
        }
        return slamSlot("HR", CACHE_ATOMICITY_PER_THREAD,
                (k, tid) -> gen.rockResistanceAt(coords.get((k + tid) % coords.size()).wx(),
                        coords.get((k + tid) % coords.size()).wz()),
                (k, tid) -> base.get(coords.get((k + tid) % coords.size()).wx()));
    }

    /** LF 缓存：landFactorAt 的等价预计算真值（与生产实现同源：smoothstep(continent−bias)）。 */
    private static boolean cacheAtomicityLfCheck() throws Exception {
        var gen = newGenerator();
        var contF = CellGenerator.class.getDeclaredField("continent");
        contF.setAccessible(true);
        Object cont = contF.get(gen);
        var contSample = cont.getClass().getMethod("sample", double.class, double.class);
        var biasF = CellGenerator.class.getDeclaredField("continentBias");
        biasF.setAccessible(true);
        double bias = (double) biasF.get(gen);
        double[] xs = {32.0, 32.5, 33.0, 33.5};
        final var base = new java.util.HashMap<Double, Double>();
        for (double x : xs) {
            double cb = (double) contSample.invoke(cont, x, 48.0) - bias;
            base.put(x, smoothstep(-0.01, 0.01, cb));
        }
        return slamSlot("LF", CACHE_ATOMICITY_PER_THREAD,
                (k, tid) -> gen.landFactorAt(xs[(k + tid) % xs.length], 48.0),
                (k, tid) -> base.get(xs[(k + tid) % xs.length]));
    }

    private static double smoothstep(double a, double b, double x) {
        double t = Math.max(0.0, Math.min(1.0, (x - a) / (b - a)));
        return t * t * (3 - 2 * t);
    }

    private static void runCacheAtomicityRegression() throws Exception {
        boolean pass;
        if (Boolean.getBoolean("probe.negativeOnly")) {
            pass = cacheAtomicityOldShapeControl();
        } else if (Boolean.getBoolean("probe.negative")) {
            pass = cacheAtomicityOldShapeControl();
            pass &= cacheAtomicityEqCheck();
        } else {
            pass = cacheAtomicityEqCheck();
            pass &= cacheAtomicityLfCheck();
            pass &= cacheAtomicityHrCheck();
        }
        System.out.println(pass ? "=== CACHE ATOMICITY ALL PASS ===" : "=== CACHE ATOMICITY FAIL ===");
        System.exit(pass ? 0 : 1);
    }

    // ===================== ★ 2026-10-12 height-only 等价回归 =====================
    //
    // 【被验对象】{@code CellGenerator.erodedHeight(wu)} —— 水文 model 链窗口床面采样新入口。
    //   口径（严格 4 步）：terrainEQuick → + erosionDeltaE → NaN/Inf 守卫 → softCapLandE → heightFromE。
    //
    // 【判据】与生产 {@code sampleWu(wu).height} <b>逐位相等</b>（raw bits），覆盖
    //   海 / 陆 / 火山 / 海岸 / 高峰（softCap 压缩窗口<b>真正触发</b>——由地形极值搜索保证）。
    //
    // 【牙齿】近失变体必须在某处打破逐位相等，否则判据无鉴别力：
    //   V1 省略【第二次 softCap】（只在压缩窗口内破）· V2 省略【侵蚀增量】（窗口外即破）。
    //
    // 【刻意不做】不跑整窗大网格（HS 1/2/3.5 的全 tile 扫描极慢且与"等价性"结论无关）。
    //   点集 = 分类扫描 + 地形极值搜索（复用 scratch 研究同款 region/step 参数与点位来源）。
    //
    // 用法：{@code gradlew runErosionConcurrencyProbe -PprobeArgs="height-only"}

    private static int hoFails;
    private static Method hoSoftCapM;

    private static void hoChk(boolean ok, String name, String detail) {
        if (ok) System.out.println("  [PASS] " + name);
        else { hoFails++; System.out.println("  **[FAIL] " + name + " -- " + detail); }
    }

    private static long hoBits(double v) { return Double.doubleToRawLongBits(v); }

    /** 用户 toml → 工作副本并加载到 {@code GeoGenesisConfig.SPEC}（<b>不碰原件</b>）。 */
    private static boolean hoLoadRealConfig() {
        Path src = Path.of("run", "config", "geogenesis-common.toml");
        if (!Files.exists(src)) return false;
        Path work = Path.of("build", "scratch", "config-erosion-probe.toml");
        try {
            Files.createDirectories(work.getParent());
            Files.copy(src, work, StandardCopyOption.REPLACE_EXISTING);
            CommentedFileConfig cfg = CommentedFileConfig.of(work, TomlFormat.instance());
            cfg.load();
            GeoGenesisConfig.SPEC.setConfig(cfg);
            return true;
        } catch (Throwable e) { return false; }
    }

    private static TerrainParams hoParams() {
        try { return GeoGenesisConfig.INSTANCE.buildParams(); }
        catch (Throwable e) { return TerrainParams.defaults(); }
    }

    private static double hoSoftCap(CellGenerator gen, double e) throws Exception {
        return (Double) hoSoftCapM.invoke(gen, e);
    }

    /** V1：省略第二次 softCap（q + d → 曲线）。窗口内应破、窗口外应同。 */
    private static double hoV1(CellGenerator gen, double u, double v) {
        double e = gen.terrainEQuick(u, v) + gen.erosionDeltaE(u, v);
        return gen.heightCurve().heightFromE(e);
    }

    /** V2：省略侵蚀增量（q → softCap → 曲线）。窗口外即应破。 */
    private static double hoV2(CellGenerator gen, double u, double v) throws Exception {
        return gen.heightCurve().heightFromE(hoSoftCap(gen, gen.terrainEQuick(u, v)));
    }

    /**
     * 地形极值搜索：8 个 region（span/step 同 scratch 研究）粗扫 → 爬坡精修 →
     * 只保留【侵蚀后 raw e 落在压缩窗口内】的点。返回 {u, v, rawE}。
     */
    private static List<double[]> hoWindowPoints(CellGenerator gen, double softStart, int span, int step) {
        List<double[]> tops = new ArrayList<>();
        for (int r = 0; r < 8; r++) {
            double ang = r * (Math.PI / 4);
            int ox = (int) Math.round(Math.cos(ang) * span * 0.7);
            int oz = (int) Math.round(Math.sin(ang) * span * 0.7);
            for (int z = oz; z < oz + span; z += step) {
                for (int x = ox; x < ox + span; x += step) {
                    double e = gen.terrainEQuick(x, z);
                    if (e > softStart - 0.15) tops.add(new double[]{e, x, z});
                }
            }
        }
        tops.sort((a, b) -> Double.compare(b[0], a[0]));
        List<double[]> win = new ArrayList<>();
        for (int i = 0; i < Math.min(150, tops.size()); i++) {
            double[] p = tops.get(i);
            double px = p[1], pz = p[2], pe = p[0];
            for (double s = 32; s >= 0.5; s *= 0.5) {
                boolean moved = true;
                while (moved) {
                    moved = false;
                    for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                        double ne = gen.terrainEQuick(px + d[0] * s, pz + d[1] * s);
                        if (ne > pe) { px += d[0] * s; pz += d[1] * s; pe = ne; moved = true; }
                    }
                }
            }
            double raw = gen.terrainEQuick(px, pz) + gen.erosionDeltaE(px, pz);
            if (raw > softStart) win.add(new double[]{px, pz, raw});
        }
        return win;
    }

    /** 分类扫描（海/陆/高峰/火山/海岸）→ 每类均匀取样，返回 {u, v, cat}。 */
    private static List<double[]> hoCategoryScan(CellGenerator gen, int perCat, int span, int step) {
        final int SEA = 0, LAND = 1, HIGH = 2, VOLCANO = 3, COAST = 4, CATS = 5;
        List<List<double[]>> buckets = new ArrayList<>();
        for (int i = 0; i < CATS; i++) buckets.add(new ArrayList<>());
        for (int z = -1024; z < -1024 + span; z += step) {
            for (int x = -1024; x < -1024 + span; x += step) {
                Cell c = gen.sample(x, z);
                int cat;
                if (c.landFeat != null && (c.landFeat.singleEdifice > 0.010 || c.landFeat.fieldEdifice > 0.010)) cat = VOLCANO;
                else if (c.e < 0.0) cat = SEA;
                else if (c.e > 0.45) cat = HIGH;
                else if (Math.abs(c.e) < 0.05) cat = COAST;
                else cat = LAND;
                buckets.get(cat).add(new double[]{x, z, cat});
            }
        }
        List<double[]> picked = new ArrayList<>();
        for (int k = 0; k < CATS; k++) {
            List<double[]> b = buckets.get(k);
            int n = Math.min(perCat, b.size());
            for (int i = 0; i < n; i++) picked.add(b.get((int) ((long) i * b.size() / Math.max(1, n))));
        }
        return picked;
    }

    private static void runHeightOnlyRegression() throws Exception {
        System.out.println("=== ErosionConcurrencyProbe : height-only equivalence ===");
        hoFails = 0;
        boolean cfgOk = hoLoadRealConfig();
        TerrainParams tp = hoParams();
        double maxLandHi = tp.splineConfig().maxLandHi();
        double softStart = maxLandHi * 0.97;
        double hs = tp.horizontalScale();
        hoChk(cfgOk, "用户 toml 已复制为工作副本并生效（不回退却声称一致）", "configResolved=false");
        System.out.printf("  hs=%.3f  maxLandHi=%.5f  softStart=%.5f%n", hs, maxLandHi, softStart);

        long seed = 9139912035078620160L;
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        hoSoftCapM = CellGenerator.class.getDeclaredMethod("softCapLandE", double.class);
        hoSoftCapM.setAccessible(true);

        // ---- point set ----
        long t0 = System.nanoTime();
        List<double[]> pts = hoCategoryScan(gen, 60, 4096, 64);
        int catPts = pts.size();
        List<double[]> win = hoWindowPoints(gen, softStart, 10240, 64);
        for (double[] w : win) pts.add(new double[]{w[0], w[1], 5});   // cat 5 = softcap-window
        System.out.printf("  point set: %d category pts + %d softcap-window pts = %d (%.1f s)%n",
                catPts, win.size(), pts.size(), (System.nanoTime() - t0) / 1e9);
        hoChk(!win.isEmpty(), "softCap 压缩窗口真正触发（窗口内有点位）",
                "window pts=0 ⇒ 判据无法覆盖承重的第二次 softCap");

        // ---- [Q] erodedHeight == sampleWu().height（逐位）+ 牙齿（近失变体必须破） ----
        //   窗口归属按【真实侵蚀后 raw e】判定（= terrainEQuick + erosionDeltaE），
        //   而非搜索时的分类标签 —— V1 恰好且仅在 softCap 生效处（raw e > softStart）破。
        int bad = 0, winBad = 0, winN = 0, firstBad = -1;
        double worst = 0;
        int v1WinBad = 0, v1OutBad = 0, v2GenBad = 0;
        for (int i = 0; i < pts.size(); i++) {
            double u = pts.get(i)[0], v = pts.get(i)[1];
            double rawE = gen.terrainEQuick(u, v) + gen.erosionDeltaE(u, v);
            boolean isWin = rawE > softStart;
            double ref = gen.sampleWu(u, v).height;
            double cand = gen.erodedHeight(u, v);
            if (isWin) winN++;
            if (hoBits(ref) != hoBits(cand)) {
                bad++;
                if (isWin) winBad++;
                if (firstBad < 0) firstBad = i;
                worst = Math.max(worst, Math.abs(ref - cand));
            }
            if (hoBits(ref) != hoBits(hoV1(gen, u, v))) { if (isWin) v1WinBad++; else v1OutBad++; }
            if (hoBits(ref) != hoBits(hoV2(gen, u, v))) v2GenBad++;
        }
        hoChk(bad == 0, "Q erodedHeight == sampleWu().height（逐位）over " + pts.size() + " pts",
                "mismatch=" + bad + " window=" + winBad + "/" + winN + " worst|d|=" + worst + " first=" + firstBad);
        System.out.printf("  window pts actually exercised in Q: %d%n", winN);
        hoChk(winN > 0, "softCap 压缩窗口在 Q 点集内真正触发", "window pts=0 ⇒ 判据未覆盖承重的第二次 softCap");

        hoChk(v1WinBad > 0, "V1（省略第二次 softCap）在压缩窗口内破 ⇒ 该步承重",
                "window mismatches=0 ⇒ 窗口不可达 / 变体未被证伪");
        hoChk(v1OutBad == 0, "V1 在窗口外与生产逐位相同 ⇒ 第二次 softCap 仅窗口内生效", "outside mismatches=" + v1OutBad);
        hoChk(v2GenBad > 0, "V2（省略侵蚀增量）在窗口外即破 ⇒ 增量承重", "general mismatches=" + v2GenBad);

        // ---- 负对照（对照装置，不碰生产代码）：把 V1（漏第二次 softCap）当作"待验候选"再跑一次
        //      同一套逐位判据 —— 判据必须报 FAIL（mismatches > 0）。
        //      若这里也 0 错，则说明判据对该承重步骤无鉴别力（判据无效）。 ----
        int negBad = 0;
        for (int i = 0; i < pts.size(); i++) {
            double u = pts.get(i)[0], v = pts.get(i)[1];
            if (hoBits(gen.sampleWu(u, v).height) != hoBits(hoV1(gen, u, v))) negBad++;
        }
        hoChk(negBad > 0, "负对照：漏第二次 softCap 的候选被逐位判据拒绝（判据有鉴别力）",
                "mismatches=0 ⇒ 判据无法区分该承重步骤（判据无效）");
        System.out.println("  （负对照 mismatches=" + negBad + " ⇒ 判据对该变体报 FAIL，牙齿成立）");

        // ---- 确定性：cold/warm、正序/逆序、并发 ----
        double[] refWarm = new double[pts.size()];
        for (int i = 0; i < pts.size(); i++) refWarm[i] = gen.erodedHeight(pts.get(i)[0], pts.get(i)[1]);
        int revBad = 0;
        for (int i = pts.size() - 1; i >= 0; i--) {
            if (hoBits(refWarm[i]) != hoBits(gen.erodedHeight(pts.get(i)[0], pts.get(i)[1]))) revBad++;
        }
        hoChk(revBad == 0, "erodedHeight 与查询顺序无关（热/逆序）", "differing=" + revBad);

        CellGenerator cold = new CellGenerator(tp, tp.minY(), tp.maxY());
        cold.seed(seed);
        int coldBad = 0;
        for (int i = 0; i < pts.size(); i++) {
            if (hoBits(refWarm[i]) != hoBits(cold.erodedHeight(pts.get(i)[0], pts.get(i)[1]))) coldBad++;
        }
        hoChk(coldBad == 0, "erodedHeight 与引擎冷热无关（fresh vs warm）", "differing=" + coldBad);

        // 并发：多线程同时采样同一批点，结果必须逐位等于串行参考，且无异常
        final int HO_THREADS = 8;
        var pool = java.util.concurrent.Executors.newFixedThreadPool(HO_THREADS, r -> {
            var t = new Thread(r, "ho-concurrent"); t.setDaemon(true); return t;
        });
        final int[] concBad = {0};
        final int[] concErr = {0};
        try {
            var start = new CountDownLatch(1);
            List<java.util.concurrent.Future<?>> fs = new ArrayList<>();
            for (int t = 0; t < HO_THREADS; t++) {
                final int tid = t;
                fs.add(pool.submit(() -> {
                    start.await();
                    for (int i = 0; i < pts.size(); i++) {
                        try {
                            double h = gen.erodedHeight(pts.get(i)[0], pts.get(i)[1]);
                            if (hoBits(h) != hoBits(refWarm[i])) concBad[0]++;
                        } catch (Throwable e) { concErr[0]++; }
                    }
                    return null;
                }));
            }
            start.countDown();
            for (var f : fs) f.get(180, TimeUnit.SECONDS);
        } finally {
            pool.shutdownNow();
        }
        hoChk(concBad[0] == 0 && concErr[0] == 0,
                "erodedHeight 并发无错值（" + HO_THREADS + " 线程 × " + pts.size() + " 点，逐位对照串行参考）",
                "mismatches=" + concBad[0] + " errors=" + concErr[0]);

        // ---- bench（同 coord 热，height-only vs full；多轮 + 轮换坐标；不据毫秒判定通过） ----
        int bn = Math.min(256, pts.size());
        double[] bu = new double[bn], bv = new double[bn];
        for (int i = 0; i < bn; i++) { bu[i] = pts.get(i)[0]; bv[i] = pts.get(i)[1]; }
        for (int i = 0; i < bn; i++) { gen.erodedHeight(bu[i], bv[i]); gen.sampleWu(bu[i], bv[i]); }
        double[] bRef = new double[bn];
        for (int i = 0; i < bn; i++) bRef[i] = gen.sampleWu(bu[i], bv[i]).height;
        int rounds = 5, reps = 60;
        System.out.printf("  bench n=%d reps=%d rounds=%d（热；坐标每轮轮换；仅报告，不据毫秒判定）%n",
                bn, reps, rounds);
        for (int r = 0; r < rounds; r++) {
            int off = (r * 97) % bn;
            long a0 = System.nanoTime();
            double s1 = 0;
            for (int k = 0; k < reps; k++)
                for (int i = 0; i < bn; i++) s1 += gen.sampleWu(bu[(i + off) % bn], bv[(i + off) % bn]).height;
            double full = (System.nanoTime() - a0) / (double) (reps * bn);
            long b0 = System.nanoTime();
            double s2 = 0;
            for (int k = 0; k < reps; k++)
                for (int i = 0; i < bn; i++) s2 += gen.erodedHeight(bu[(i + off) % bn], bv[(i + off) % bn]);
            double fast = (System.nanoTime() - b0) / (double) (reps * bn);
            if (s1 == 12345.6789 && s2 == 12345.6789) System.out.print("");
            System.out.printf("    round %d off=%3d: sampleWu=%8.1f ns/pt  erodedHeight=%8.1f ns/pt  ratio=%5.2fx%n",
                    r, off, full, fast, full / Math.max(1e-9, fast));
        }
        int benchBad = 0;
        for (int i = 0; i < bn; i++)
            if (hoBits(bRef[i]) != hoBits(gen.erodedHeight(bu[i], bv[i]))) benchBad++;
        hoChk(benchBad == 0, "bench 后同批坐标仍逐位等于首次观测", "differing=" + benchBad);

        System.out.println(hoFails == 0 ? "=== HEIGHT-ONLY ALL PASS ===" : "=== HEIGHT-ONLY FAIL (" + hoFails + ") ===");
        System.exit(hoFails == 0 ? 0 : 1);
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
        if (args.length > 0 && "cache-atomicity".equals(args[0])) {
            runCacheAtomicityRegression();
            return;
        }
        if (args.length > 0 && "height-only".equals(args[0])) {
            runHeightOnlyRegression();
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
