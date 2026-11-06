package com.geogenesis.diagnostics;

import com.geogenesis.worldgen.core.BaseWorldSampler;
import com.geogenesis.worldgen.core.BaseWorldSettings;
import com.geogenesis.worldgen.terrain.TerrainParams;
import com.geogenesis.worldgen.terrain.morph.MorphTerrainSampler;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Production sampleE collision stress, adapted from runtime-attribution-9488/CacheCollisionStress.
 * No reflective cache writes; reference is an independent, uncached terrain sampler.
 */
public final class BaseSampleCacheProbe {
    private static final long[] SEEDS = {0L, 9139L, 9139912035078620160L, 686443174003246080L};
    private static final int THREADS = 4, CALLS = 20000;

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            fork(false);
            fork(true);
            System.out.println("PASS: independent default-cache and cache-off JVMs");
            return;
        }
        String expectedProperty = args[0].equals("off") ? "false" : null;
        if (!java.util.Objects.equals(System.getProperty("geogenesis.baseSampleCache"), expectedProperty)) {
            throw new AssertionError("Unexpected cache property for " + args[0]);
        }
        for (long seed : SEEDS) verify(seed);
    }

    private static void fork(boolean off) throws Exception {
        var command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        command.add("-Xmx1g");
        if (off) command.add("-Dgeogenesis.baseSampleCache=false");
        command.add("-cp");
        command.add(System.getProperty("java.class.path"));
        command.add(BaseSampleCacheProbe.class.getName());
        command.add(off ? "off" : "default");
        Process child = new ProcessBuilder(command).inheritIO().start();
        if (!child.waitFor(300, TimeUnit.SECONDS)) {
            child.destroyForcibly();
            throw new AssertionError("cache child timeout");
        }
        if (child.exitValue() != 0) throw new AssertionError("cache child failed: " + child.exitValue());
    }

    private static void verify(long seed) throws Exception {
        var params = TerrainParams.defaults();
        var sampler = new BaseWorldSampler(seed, params, -64, 320, BaseWorldSettings.defaults());
        var truth = new MorphTerrainSampler(seed, params, -64, 320);
        long signature = sequential(sampler, truth);
        long[] expected = {bits(truth.sampleE(0, -2)), bits(truth.sampleE(0, 0))};
        if (expected[0] == expected[1]) throw new AssertionError("Collision witness must have distinct values");
        if (slot(0, -2) != slot(0, 0)) throw new AssertionError("Witness no longer collides");
        var errors = new AtomicInteger();
        var first = new AtomicReference<String>();
        var start = new CountDownLatch(1);
        var pool = Executors.newFixedThreadPool(THREADS);
        var jobs = new ArrayList<Future<?>>();
        try {
            for (int t = 0; t < THREADS; t++) {
                final int thread = t;
                jobs.add(pool.submit(() -> {
                    try { start.await(); }
                    catch (InterruptedException e) { throw new AssertionError(e); }
                    for (int i = 0; i < CALLS; i++) {
                        int q = (i + thread) & 1;
                        long actual = bits(sampler.sampleE(0, q == 0 ? -2 : 0));
                        if (actual != expected[q]) {
                            errors.incrementAndGet();
                            first.compareAndSet(null, "thread=" + thread + " i=" + i + " q=" + q
                                    + " expected=" + Long.toHexString(expected[q])
                                    + " actual=" + Long.toHexString(actual));
                        }
                    }
                }));
            }
            start.countDown();
            for (var job : jobs) job.get(120, TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        System.out.println("seed=" + seed + " cache="
                + System.getProperty("geogenesis.baseSampleCache", "DEFAULT_ON")
                + " signature=" + Long.toHexString(signature) + " calls=" + THREADS * CALLS
                + " errors=" + errors + " first=" + first.get());
        if (errors.get() != 0) throw new AssertionError("Production sampleE cache mismatch");
    }

    private static long sequential(BaseWorldSampler sampler, MorphTerrainSampler truth) {
        double[][] special = {{0, -2}, {0, 0}, {-0.0, 0}, {0, -0.0}, {-0.0, -0.0},
                {-0.0, -2}, {1, -1}, {-1, 1}, {123.25, -987.5}, {-16384, 8192}};
        long hash = 0xcbf29ce484222325L;
        for (int i = 0; i < 522; i++) {
            double x = i < special.length ? special[i][0] : (i - 266) * 7.25;
            double z = i < special.length ? special[i][1] : (i % 37 - 18) * 13.5;
            long expected = bits(truth.sampleE(x, z));
            for (int repeat = 0; repeat < 3; repeat++) {
                if (bits(sampler.sampleE(x, z)) != expected) {
                    throw new AssertionError("Single-thread raw-bit mismatch at " + x + "," + z);
                }
            }
            hash = (hash ^ expected) * 0x100000001b3L;
        }
        return hash;
    }

    private static long bits(double value) { return Double.doubleToRawLongBits(value); }

    private static int slot(double x, double z) {
        return (int) (((bits(x) * 0x9E3779B97F4A7C15L)
                ^ (bits(z) * 0x632BE59BD9B4E019L)) >>> 40) & ((1 << 18) - 1);
    }
}
