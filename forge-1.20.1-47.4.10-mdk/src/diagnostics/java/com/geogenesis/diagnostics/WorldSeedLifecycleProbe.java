package com.geogenesis.diagnostics;

import com.geogenesis.worldgen.generator.WorldSeedLifecycle;
import com.geogenesis.worldgen.generator.BiomeColumnCache;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.*;

/** Standalone regression: no Forge bootstrap, registry, world files or terrain generation. */
public final class WorldSeedLifecycleProbe {
    public static void main(String[] args) throws Exception {
        lifecycle();
        race();
        for (int mode = 0; mode < 4; mode++) {
            unlockedSample(mode, false);
            unlockedSample(mode, true);
        }
        wiring();
        System.out.println("PASS: binding/publication, unlocked sampling, bind/unbind/ABA/invalidate, stale cache/return rejection, production wiring");
    }

    private static void check(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }

    private static void rejected(WorldSeedLifecycle<Long> state) {
        try {
            state.getOrBuild(seed -> { throw new AssertionError("unbound factory invoked"); });
            throw new AssertionError("unbound build accepted");
        } catch (IllegalStateException expected) { }
    }

    private static void lifecycle() {
        var state = new WorldSeedLifecycle<Long>();
        rejected(state);
        state.bind(0);
        check(state.getOrBuild(s -> s) == 0, "zero seed is legal");
        state.bind(9139912035078620160L);
        check(state.getOrBuild(s -> s) == 9139912035078620160L, "switch seed");
        state.invalidate();
        check(state.current() == null && state.isBound(), "invalidate retains binding");
        state.unbind();
        rejected(state);
        state.bind(-7);
        check(state.getOrBuild(s -> s) == -7, "rebind after unload");
    }

    private static void race() throws Exception {
        var state = new WorldSeedLifecycle<Long>();
        state.bind(12345);
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var binding = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<Long> old = pool.submit(() -> state.getOrBuild(seed -> {
                entered.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("release timeout");
                } catch (InterruptedException e) { throw new AssertionError(e); }
                return seed;
            }));
            check(entered.await(5, TimeUnit.SECONDS), "builder entered");
            Future<?> bind = pool.submit(() -> { binding.countDown(); state.bind(9139912035078620160L); });
            check(binding.await(5, TimeUnit.SECONDS), "binder started");
            try {
                bind.get(100, TimeUnit.MILLISECONDS);
                throw new AssertionError("bind passed an active old build");
            } catch (TimeoutException expected) { }
            release.countDown();
            check(old.get(5, TimeUnit.SECONDS) == 12345, "old caller completes before bind");
            bind.get(5, TimeUnit.SECONDS);
            check(state.current() == null, "old publication invalidated by bind");
            check(state.getOrBuild(s -> s) == 9139912035078620160L, "post-bind consumer sees real seed");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    private record Generation(long seed, BiomeColumnCache<String> cache) { }

    private static void await(CountDownLatch latch) {
        try { check(latch.await(5, TimeUnit.SECONDS), "sample latch timeout"); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }

    /** Exercises the production capture/query/accept protocol with the real cache and lifecycle. */
    private static void unlockedSample(int mode, boolean beforeReturn) throws Exception {
        var state = new WorldSeedLifecycle<Generation>();
        state.bind(12345);
        Generation captured;
        long version;
        synchronized (state) {
            captured = state.getOrBuild(s -> new Generation(s, new BiomeColumnCache<>()));
            version = state.version();
        }
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<String> old = pool.submit(() -> {
                String result = captured.cache().query(0, 0, () -> state.version() == version, () -> {
                    check(!Thread.holdsLock(state), "expensive sample holds lifecycle");
                    if (!beforeReturn) { entered.countDown(); await(release); }
                    return new BiomeColumnCache.Column<>("oldSurface", 1, "oldOrMixedCave");
                }, null);
                if (beforeReturn) { entered.countDown(); await(release); }
                synchronized (state) {
                    return result != null && state.matches(version, captured) ? result : null;
                }
            });
            await(entered);
            Future<Generation> change = pool.submit(() -> {
                synchronized (state) {
                    if (mode == 0) state.bind(0);
                    if (mode == 1) state.unbind();
                    if (mode == 2) { state.unbind(); state.bind(12345); }
                    if (mode == 3) state.invalidate();
                    return state.isBound()
                            ? state.getOrBuild(s -> new Generation(s, new BiomeColumnCache<>())) : null;
                }
            });
            // Must finish while old sampling/acceptance remains latched, not merely after release.
            Generation fresh = change.get(5, TimeUnit.SECONDS);
            check(!old.isDone() && release.getCount() == 1, "old query was not held across transition");
            check(!state.matches(version, captured), "old generation survived transition");
            if (fresh != null) {
                check(fresh != captured, "generation reused across bind/invalidation");
                check(fresh.seed() == (mode == 0 ? 0 : 12345), "bound seed changed");
                check("newCave".equals(fresh.cache().query(0, 0, () -> true,
                        () -> new BiomeColumnCache.Column<>("newSurface", 2, "newCave"), null)),
                        "old sample polluted new generation before release");
            }
            release.countDown();
            check(old.get(5, TimeUnit.SECONDS) == null, "stale biome/cave returned");
            synchronized (captured.cache().lock(0)) {
                if (!beforeReturn) check(captured.cache().get(0, BiomeColumnCache.key(0, 0)) == null,
                        "invalidated in-flight sample published to old cache");
            }
            if (fresh != null) check("newSurface".equals(fresh.cache().query(0, 0, () -> true,
                    () -> { throw new AssertionError("new cache lost"); }, null)),
                    "old completion polluted new generation");
        } finally {
            release.countDown();
            pool.shutdownNow();
            check(pool.awaitTermination(5, TimeUnit.SECONDS), "sample workers did not stop");
        }
    }

    private static void wiring() throws Exception {
        Path root = Path.of("src/main/java/com/geogenesis");
        String gen = Files.readString(root.resolve("worldgen/generator/GeoGenesisGenerator.java"));
        String biome = Files.readString(root.resolve("worldgen/generator/GeoGenesisBiomeSource.java"));
        String events = Files.readString(root.resolve("GeoGenesisServerEvents.java"));
        check(gen.contains("WORLD_LIFECYCLE.getOrBuild(seed ->"), "production uses tested publication");
        check(gen.contains("built.seed(seed)") && gen.contains("gen.processRuntime().context().definition().seed()"),
                "production seeds and verifies actual runtime");
        check(!gen.contains("worldSeed = 12345") && !gen.contains("synchronized (GeoGenesisGenerator.class)"),
                "no default seed or competing monitor");
        check(events.contains("bindWorld(s.getSeed(), data.getParams(), data.usesMorphBase())"), "atomic save binding");
        check(events.contains("GeoGenesisGenerator.unbindWorld()"), "Unload unbinds");
        int guard = biome.indexOf("if (!GeoGenesisGenerator.WORLD_LIFECYCLE.isBound())");
        int fallback = biome.indexOf("return fallbackBiome();", guard);
        int build = biome.indexOf("setTerrain(GeoGenesisGenerator.buildTerrain", guard);
        check(guard >= 0 && fallback > guard && build > fallback, "unbound fallback before build/cache");
        int sample = biome.indexOf("result = sampleBoundBiome(captured", build);
        int captureLock = biome.lastIndexOf("synchronized (GeoGenesisGenerator.WORLD_LIFECYCLE)", build);
        check(sample > blockEnd(biome, captureLock)
                        && biome.indexOf("captured = generation", build) < blockEnd(biome, captureLock),
                "capture immutable bound identity under monitor, sample outside it");
        check(biome.contains("private volatile CacheGeneration generation")
                        && biome.contains("final GeoGenesisTerrain terrain;")
                        && biome.contains("final long version;") && biome.contains("final long seed;")
                        && biome.contains("final BiomeColumnCache<Holder<Biome>> cache = new BiomeColumnCache<>();"),
                "generation owns immutable identity and isolated cache");
        check(biome.contains("lifecycle.current() != terrain")
                        && biome.contains("old.version != lifecycle.version()"), "terrain and ABA guarded");
        int accept = biome.indexOf("if (result != null && generation == captured", sample);
        int acceptLock = biome.lastIndexOf("synchronized (GeoGenesisGenerator.WORLD_LIFECYCLE)", accept);
        int acceptedReturn = biome.indexOf("captured.version, captured.terrain)) return result;", accept);
        check(acceptLock > sample && accept < blockEnd(biome, acceptLock)
                        && acceptedReturn > accept && acceptedReturn < blockEnd(biome, acceptLock),
                "final return checks version and terrain under monitor");
        int query = biome.indexOf("return captured.cache.query(x, z,");
        int column = biome.indexOf("private BiomeColumnCache.Column", query);
        String queryBody = biome.substring(query, column);
        check(queryBody.contains("generation == captured")
                        && queryBody.contains("WORLD_LIFECYCLE.version() == captured.version")
                        && queryBody.contains("sampleColumn(captured.terrain, x, y, z, diag)")
                        && !queryBody.contains("synchronized") && !queryBody.contains(".matches("),
                "slot validity is lock-free; no lifecycle acquisition under slot monitor");
        check(biome.contains("terrain.sampleCellLight(QuartPos.toBlock(x), QuartPos.toBlock(z))")
                        && biome.contains("BiomeClassifier.pickKey(cell)")
                        && biome.contains("packColInfo((int) Math.floor(cell.height), cell.rockTypeId, cell.biomeType.ordinal())")
                        && biome.contains("caveBiomeAt(x, y, z, info)"), "exact P and classification inputs unchanged");
        check(!biome.contains("biomeCacheValues[slot] = plains"), "early fallback not cached");
        for (String method : new String[]{"setCurrentWorldMorphBase(boolean", "setCurrentWorldParams(TerrainParams",
                "bindWorld(long", "unbindWorld()", "setWorldSeed(long"}) {
            int start = gen.indexOf("public static void " + method);
            int lock = gen.indexOf("synchronized (WORLD_LIFECYCLE)", start);
            int endLock = blockEnd(gen, lock);
            int close = gen.indexOf("retireTerrain(retired);", endLock);
            check(close > endLock && close < blockEnd(gen, start), "detach then close outside lock: " + method);
        }
        check(gen.contains("retired = WORLD_LIFECYCLE.current()")
                && gen.contains("if (retired != null) retired.closeProcessErosion()"), "retirement bridge wired");
        String attribution = Files.readString(root.resolve("diagnostics/FrontendAttribution.java"));
        check(attribution.contains("sample_outside_lifecycle_lock")
                        && !attribution.contains("locked_biome_body_serial"), "diagnostic lock label matches wiring");
    }

    /** Brace matching checks lexical placement, rather than only presence of call names. */
    private static int blockEnd(String source, int start) {
        check(start >= 0, "missing lifecycle block");
        int open = source.indexOf('{', start), depth = 0;
        for (int i = open; i < source.length(); i++) {
            if (source.charAt(i) == '{') depth++;
            if (source.charAt(i) == '}' && --depth == 0) return i;
        }
        throw new AssertionError("unclosed lifecycle block");
    }
}
