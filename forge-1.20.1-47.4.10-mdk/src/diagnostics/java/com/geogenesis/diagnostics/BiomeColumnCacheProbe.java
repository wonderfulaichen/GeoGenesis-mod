package com.geogenesis.diagnostics;

import com.geogenesis.worldgen.generator.BiomeColumnCache;
import com.geogenesis.worldgen.generator.WorldSeedLifecycle;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Pure JDK concurrency/semantics test, not a Minecraft biome-effects acceptance. */
public final class BiomeColumnCacheProbe {
    private static void check(boolean b, String s) { if (!b) throw new AssertionError(s); }
    private static void await(CountDownLatch l) {
        try { check(l.await(10, TimeUnit.SECONDS), "latch timeout"); }
        catch (InterruptedException e) { throw new AssertionError(e); }
    }
    public static void main(String[] args) throws Exception {
        serial(); parallel(); sameSlot(); switching(); wiring(); performance();
        System.out.println("PASS: serial old-cache semantics, parallel misses, same-slot singleflight, zero/switch/unbind/ABA, wiring");
    }
    private static void serial() {
        var c = new BiomeColumnCache<String>();
        long[] keys = new long[65536]; String[] values = new String[65536];
        Random r = new Random(613);
        for (int i = 0; i < 100000; i++) {
            int x = i % 5 == 0 ? 0 : r.nextInt(512) - 256;
            int z = i % 5 == 0 ? 0 : r.nextInt(512) - 256;
            int slot = BiomeColumnCache.slot(x, z);
            long key = (long)x << 32 | (z & 0xffffffffL);
            String surface = x + ":" + z, miss = "cave:" + surface + ":" + i;
            String expected = keys[slot] == key && values[slot] != null ? values[slot] : miss;
            String actual = c.query(x, z, () -> true,
                    () -> new BiomeColumnCache.Column<>(surface, 123, miss), null);
            check(expected.equals(actual), "serial mismatch at " + i);
            keys[slot] = key; values[slot] = surface;
        }
    }
    private static void parallel() throws Exception {
        var c = new BiomeColumnCache<Integer>(); var state = new WorldSeedLifecycle<Object>();
        state.bind(0); Object terrain = state.getOrBuild(s -> new Object()); long v = state.version();
        var entered = new CountDownLatch(2); var release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try {
            List<Future<Integer>> jobs = new ArrayList<>();
            for (int x = 0; x < 2; x++) {
                int xx = x;
                jobs.add(pool.submit(() -> c.query(xx, 0, () -> state.version() == v, () -> {
                    check(!Thread.holdsLock(state), "sampling holds lifecycle");
                    entered.countDown(); await(release);
                    return new BiomeColumnCache.Column<>(xx, 0, xx);
                }, null)));
            }
            await(entered);
            check(pool.submit(() -> state.matches(v, terrain)).get(2, TimeUnit.SECONDS), "lifecycle blocked");
            release.countDown();
            for (var f : jobs) f.get(5, TimeUnit.SECONDS);
        } finally { release.countDown(); pool.shutdownNow(); }
    }
    private static void sameSlot() throws Exception {
        var c = new BiomeColumnCache<String>(); AtomicInteger loads = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(12);
        try {
            List<Future<String>> jobs = new ArrayList<>();
            for (int i = 0; i < 32; i++) jobs.add(pool.submit(() -> c.query(0, 0, () -> true,
                    () -> { loads.incrementAndGet(); return new BiomeColumnCache.Column<>("surface", 7, "cave"); }, null)));
            int caves = 0;
            for (var f : jobs) if (f.get(5, TimeUnit.SECONDS).equals("cave")) caves++;
            check(loads.get() == 1 && caves == 1, "same key changes miss/hit semantics");
            check(BiomeColumnCache.slot(65536, 0) == 0, "collision fixture");
            c.query(65536, 0, () -> true, () -> new BiomeColumnCache.Column<>("other", 8, "otherCave"), null);
            check(c.query(0, 0, () -> true, () -> new BiomeColumnCache.Column<>("surface", 7, "cave"), null)
                    .equals("cave"), "collision must evict");
        } finally { pool.shutdownNow(); }
    }
    private static void switching() throws Exception {
        for (int mode = 0; mode < 4; mode++) {
            var state = new WorldSeedLifecycle<Object>(); state.bind(12345);
            Object old = state.getOrBuild(s -> new Object()); long v = state.version();
            var cache = new BiomeColumnCache<String>(); var entered = new CountDownLatch(1);
            var release = new CountDownLatch(1); ExecutorService pool = Executors.newSingleThreadExecutor();
            try {
                var stale = pool.submit(() -> cache.query(0, 0, () -> state.version() == v, () -> {
                    entered.countDown(); await(release);
                    return new BiomeColumnCache.Column<>("old", 1, "mixed-global-cave");
                }, null));
                await(entered);
                if (mode == 0) state.bind(0);
                if (mode == 1) state.unbind();
                if (mode == 2) { state.unbind(); state.bind(12345); }
                if (mode == 3) state.invalidate();
                check(!state.matches(v, old), "expired identity still valid");
                release.countDown(); check(stale.get(5, TimeUnit.SECONDS) == null, "stale result leaked");
                // Even the abandoned cache must not contain the mixed old computation.
                check(cache.query(0, 0, () -> true,
                        () -> new BiomeColumnCache.Column<>("new", 2, "newMiss"), null).equals("newMiss"), "stale published");
                if (state.isBound()) {
                    Object fresh = state.getOrBuild(s -> new Object());
                    check(fresh != old && state.matches(state.version(), fresh), "fresh publication");
                    if (mode == 0) check(state.seed() == 0, "zero seed lost");
                }
            } finally { release.countDown(); pool.shutdownNow(); }
        }
    }
    private static void wiring() throws Exception {
        String s = Files.readString(Path.of("src/main/java/com/geogenesis/worldgen/generator/GeoGenesisBiomeSource.java")).replace("\r\n", "\n");
        check(s.contains("private volatile CacheGeneration generation"), "generation publication");
        check(s.contains("sampleBoundBiome(captured, x, y, z, diag)"), "capture use");
        check(s.contains("WORLD_LIFECYCLE.matches(") && s.contains("captured.version, captured.terrain"), "final identity check");
        check(s.contains("terrain.sampleCellLight(QuartPos.toBlock(x), QuartPos.toBlock(z))"), "exact P unchanged");
        check(s.contains("captured.cache.query(x, z,") && s.contains("WORLD_LIFECYCLE.version() == captured.version"), "tested cache wired");
        check(s.contains("setTerrain(null);\n                        return fallbackBiome();"), "unbound fallback");
    }
    private static long timed(boolean global) throws Exception {
        var c = new BiomeColumnCache<Integer>(); Object monitor = new Object();
        ExecutorService pool = Executors.newFixedThreadPool(8); long start = System.nanoTime();
        try {
            List<Callable<Integer>> work = new ArrayList<>();
            for (int i = 0; i < 32; i++) {
                int x = i;
                work.add(() -> {
                    java.util.function.Supplier<Integer> query = () -> c.query(x, 0, () -> true, () -> {
                        try { Thread.sleep(10); } catch (InterruptedException e) { throw new AssertionError(e); }
                        return new BiomeColumnCache.Column<>(x, 0, x);
                    }, null);
                    if (global) synchronized (monitor) { return query.get(); }
                    return query.get();
                });
            }
            for (var f : pool.invokeAll(work)) f.get();
        } finally { pool.shutdownNow(); }
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);
    }
    private static void performance() throws Exception {
        long old = timed(true), striped = timed(false);
        System.out.printf("SYNTHETIC 32 cold 10ms loads / 8 workers: global=%dms striped=%dms speedup=%.2fx (not MC performance)%n",
                old, striped, (double)old / striped);
    }
}
