package com.geogenesis.diagnostics;

import com.geogenesis.worldgen.generator.WorldSeedLifecycle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.*;

/** Standalone regression: no Forge bootstrap, registry, world files or terrain generation. */
public final class WorldSeedLifecycleProbe {
    public static void main(String[] args) throws Exception {
        lifecycle();
        race();
        wiring();
        System.out.println("PASS: unbound/zero/switch/unload, serialized publication, production wiring");
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
        check(biome.indexOf("return sampleBoundBiome", build) > build, "refresh identity before cache lookup");
        check(!biome.contains("biomeCacheValues[slot] = plains"), "early fallback not cached");
    }
}
