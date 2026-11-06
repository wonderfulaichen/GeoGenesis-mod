package com.geogenesis.worldgen.process;

import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Small-cost bounded storage/concurrency gate; assertion failure exits nonzero. */
public final class BInputBlockCacheProbe {
    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private static void await(CountDownLatch latch) {
        try { require(latch.await(5, TimeUnit.SECONDS), "latch timeout"); }
        catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new CancellationException(); }
    }
    private static void point(BInputBlockCache cache, int x, BInputBlockCache.Sampler sampler) {
        cache.copy(x, -1, 1, 1, sampler, (a, b, value) -> {}, () -> false);
    }
    private static void capacity() {
        for (int capacity : new int[]{1, 2, 4, 4096}) {
            var cache = new BInputBlockCache(capacity);
            AtomicInteger count = new AtomicInteger();
            BInputBlockCache.Sampler sampler = (x, z) -> { count.incrementAndGet(); return -0.0; };
            for (int i = 0; i < capacity; i++) {
                point(cache, i * 32, sampler); point(cache, i * 32, sampler);
            }
            require(count.get() == capacity, "valid bits must suppress duplicate evaluations");
            require(cache.stats().allocated() == capacity, "lazy allocation count");
            require(cache.stats().payloadBytes() == capacity * 8320L, "payload bound");
            point(cache, -33, sampler);
            require(cache.stats().peakAllocated() == capacity, "eviction cannot allocate extra slot");
            require(cache.stats().evictions() == 1, "one eviction");
            System.out.println("capacity=" + capacity + " " + cache.stats());
            cache.close(); require(cache.stats().allocated() == 0, "close releases arrays");
        }
    }
    private static void concurrency() throws Exception {
        var pool = Executors.newFixedThreadPool(3);
        try (var cache = new BInputBlockCache(2)) {
            var entered = new CountDownLatch(1); var resume = new CountDownLatch(1);
            AtomicInteger count = new AtomicInteger();
            BInputBlockCache.Sampler sampler = (x, z) -> {
                count.incrementAndGet(); entered.countDown(); await(resume); return .25;
            };
            var a = pool.submit(() -> point(cache, -1, sampler)); await(entered);
            var b = pool.submit(() -> point(cache, -1, sampler));
            pool.submit(() -> point(cache, 32, (x,z) -> .5)).get(5, TimeUnit.SECONDS);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (cache.stats().blockWaits() == 0 && count.get() == 1) {
                require(System.nanoTime() < deadline, "same block waiter did not enter");
                Thread.sleep(2);
            }
            resume.countDown(); a.get(5, TimeUnit.SECONDS); b.get(5, TimeUnit.SECONDS);
            require(count.get() == 1, "same block single computation; different block progresses");
        } finally { pool.shutdownNow(); }
    }
    private static void retryAndCancel() {
        try (var cache = new BInputBlockCache(1)) {
            try { point(cache, 0, (x,z) -> { throw new IllegalStateException("injected"); });
                throw new AssertionError("expected injected failure");
            } catch (IllegalStateException expected) { }
            AtomicInteger calls = new AtomicInteger();
            point(cache, 0, (x,z) -> { calls.incrementAndGet(); return .75; });
            point(cache, 0, (x,z) -> { throw new AssertionError("failed point retry not retained"); });
            require(calls.get() == 1, "retry once");
            AtomicBoolean cancelled = new AtomicBoolean();
            try {
                cache.copy(1,-1,2,1,(x,z) -> { cancelled.set(true); return .25; },
                        (x,z,v) -> {}, cancelled::get);
                throw new AssertionError("expected cancellation");
            } catch (CancellationException expected) { }
            point(cache, 1, (x,z) -> { throw new AssertionError("completed cancelled point lost"); });
        }
    }
    private static void closeWaiting() throws Exception {
        var pool = Executors.newFixedThreadPool(3);
        var cache = new BInputBlockCache(1);
        var entered = new CountDownLatch(1); var resume = new CountDownLatch(1);
        try {
            var a = pool.submit(() -> point(cache, 0, (x,z) -> {
                entered.countDown(); await(resume); return .25;
            }));
            await(entered);
            var b = pool.submit(() -> point(cache, 32, (x,z) -> .25));
            var c = pool.submit(() -> point(cache, 0, (x,z) -> .25));
            cache.close(); resume.countDown();
            for (var f : new Future<?>[]{a,b,c}) {
                try { f.get(5, TimeUnit.SECONDS); throw new AssertionError("closed request succeeded"); }
                catch (ExecutionException expected) {
                    require(expected.getCause() instanceof CancellationException, "closed cancellation");
                }
            }
            require(cache.stats().allocated() == 0 && cache.stats().leased() == 0, "late result freed");
        } finally { resume.countDown(); cache.close(); pool.shutdownNow(); }
    }
    private static void inputBits() {
        var context = ProcessRuntimeCheck.context(ProcessContext.Mode.PARTICLE);
        var owner = new TileGeometry.Owner(-2160,-3168);
        float[] original = ParticleTile.buildInput(context, owner, () -> false);
        try (var cache = new BInputBlockCache(4)) {
            float[] candidate = ParticleTile.buildInput(context, owner, () -> false, "probe", cache);
            for (int i = 0; i < original.length; i++) require(
                    Float.floatToRawIntBits(original[i]) == Float.floatToRawIntBits(candidate[i]), "input rawfloat " + i);
            cache.copy(-33,-33,34,34,(x,z) -> context.baseE(x,z), (x,z,v) -> require(
                    Double.doubleToRawLongBits(v) == Double.doubleToRawLongBits(context.baseE(x,z)), "raw B bits"), () -> false);
        }
    }
    public static void main(String[] args) throws Exception {
        capacity(); concurrency(); retryAndCancel(); closeWaiting(); inputBits();
        System.out.println("B INPUT BLOCK CACHE SMALL GATE PASS");
    }
}
