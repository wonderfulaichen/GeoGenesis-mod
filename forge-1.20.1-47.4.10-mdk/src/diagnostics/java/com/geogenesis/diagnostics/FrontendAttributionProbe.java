package com.geogenesis.diagnostics;

import java.lang.management.ManagementFactory;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/** Pure Java, no game or saves. Assertion failures produce a nonzero exit. */
public final class FrontendAttributionProbe {
    private static final AtomicBoolean ENABLED = new AtomicBoolean();
    private static volatile long blackhole;

    public static void main(String[] args) throws Exception {
        List<String> logs = new CopyOnWriteArrayList<>();
        FrontendAttribution.configure(ENABLED::get, logs::add, logs::add);
        check(FrontendAttribution.begin("disabled") == null, "disabled scope");
        check(!FrontendAttribution.timerStarted(), "disabled configure must not start timer");
        check(logs.isEmpty(), "disabled output");
        ENABLED.set(true);
        FrontendAttribution.bound(9139912035078620160L);
        try (var outer = sampled("getNoiseBiome")) {
            outer.waitingForLifecycle(); outer.acquired(); outer.outcome("cacheMiss");
            outer.sampleBegin(); outer.sampleEnd();
            String captured = FrontendAttribution.capture();
            check(FrontendAttribution.begin("getNoiseBiome") == null, "skipped nested call");
            check(captured.equals(FrontendAttribution.capture()), "skip leaves sampled parent intact");
            try (var inner = sampled("getBaseHeight")) {
                check(FrontendAttribution.capture().contains("chain=getNoiseBiome>getBaseHeight"), "nested chain");
                throw new IllegalArgumentException("intentional");
            } catch (IllegalArgumentException expected) { /* close must restore */ }
            check(captured.equals(FrontendAttribution.capture()), "exception restores parent");
            Thread worker = new Thread(() -> check(FrontendAttribution.capture().contains("source=other"), "thread isolation"));
            worker.setUncaughtExceptionHandler((t, ex) -> logs.add("FAIL:" + ex));
            worker.start(); worker.join();
        }
        check(FrontendAttribution.capture().contains("source=other"), "scope removed");
        long stacks = logs.stream().filter(s -> s.contains("event=tileSourceStack")).count();
        Thread.sleep(5100);
        check(FrontendAttribution.begin("getNoiseBiome") == null, "unsampled tile context");
        FrontendAttribution.capture(); FrontendAttribution.capture();
        check(logs.stream().filter(s -> s.contains("event=tileSourceStack")).count() == stacks + 1,
                "tile capture independent of sampled scope, throttled five seconds");
        FrontendAttribution.firstFill("test"); FrontendAttribution.firstFill("testAgain");
        check(logs.stream().filter(s -> s.contains("event=firstFillEntry")).count() == 1, "first fill once");
        FrontendAttribution.reportNow();
        check(logs.stream().anyMatch(s -> s.contains("event=aggregate") && s.contains("cacheMiss")
                && s.contains("no_total_time_extrapolation")), "sampled aggregate");
        check(logs.stream().noneMatch(s -> s.startsWith("FAIL:")), "worker check");
        check(FrontendAttribution.classify("createStructures").equals("STRUCTURE_STARTS"), "structure category");
        check(FrontendAttribution.classify("fillBiomesFromNoise").equals("BIOMES"), "biomes category");
        check(FrontendAttribution.classify("generateRingPositions findBiome createStructures")
                .equals("STRUCTURE_RING"), "ring priority over spawn and starts");
        FrontendAttribution.configure(ENABLED::get, text -> {}, text -> {});
        benchmark();
        ENABLED.set(false);
        System.out.println("PASS FrontendAttributionProbe sampling/nesting/exception/thread/boundary/aggregation/categories/tileStack/lazyTimer");
    }

    private static FrontendAttribution.Scope sampled(String source) {
        for (int i = 0; i <= FrontendAttribution.SAMPLE_EVERY; i++) {
            var scope = FrontendAttribution.begin(source);
            if (scope != null) return scope;
        }
        throw new AssertionError("no sample: " + source);
    }

    private static void benchmark() {
        var bean = ManagementFactory.getThreadMXBean();
        com.sun.management.ThreadMXBean alloc = bean instanceof com.sun.management.ThreadMXBean b
                && b.isThreadAllocatedMemorySupported() ? b : null;
        if (alloc != null && !alloc.isThreadAllocatedMemoryEnabled()) alloc.setThreadAllocatedMemoryEnabled(true);
        try (var first = sampled("skipAllocationCheck")) { first.outcome("test"); }
        long skippedTid = Thread.currentThread().getId();
        long skippedBefore = alloc == null ? -1 : alloc.getThreadAllocatedBytes(skippedTid);
        int unexpected = 0;
        for (int i = 0; i < FrontendAttribution.SAMPLE_EVERY - 1; i++) {
            try (var scope = FrontendAttribution.begin("skipAllocationCheck")) {
                if (scope != null) unexpected++;
            }
        }
        long skippedBytes = alloc == null ? -1 : alloc.getThreadAllocatedBytes(skippedTid) - skippedBefore;
        check(unexpected == 0, "all skipped calls return null");
        check(alloc == null || skippedBytes == 0, "skipped calls must not allocate");
        System.out.println("SKIPPED calls=1023 allocatedBytes=" + skippedBytes + " sampledCount=" + unexpected);
        // Warm both real call paths, including first-source registration and lazy diagnostics.
        for (boolean on : new boolean[]{false, true}) {
            ENABLED.set(on);
            for (int i = 0; i < 200_000; i++) invoke();
        }
        for (int round = 0; round < 3; round++) {
            for (boolean on : new boolean[]{false, true}) {
                ENABLED.set(on);
                long tid = Thread.currentThread().getId();
                long before = alloc == null ? -1 : alloc.getThreadAllocatedBytes(tid);
                long start = System.nanoTime(), samples = 0;
                for (int i = 0; i < 1_000_000; i++) samples += invoke();
                long elapsed = System.nanoTime() - start;
                long bytes = alloc == null ? -1 : alloc.getThreadAllocatedBytes(tid) - before;
                blackhole = samples;
                check(on ? samples >= 976 && samples <= 977 : samples == 0, "benchmark sampling count");
                System.out.println("BENCH round=" + round + " gate=" + on + " calls=1000000 sampledCount=" + samples
                        + " allocatedBytes=" + bytes + " wallNs=" + elapsed + " nsPerCall=" + elapsed / 1_000_000.0
                        + " allocationScope=current_thread;includes_sampled_diagnostics;not_MC_overhead");
            }
        }
    }

    private static int invoke() {
        try (var scope = FrontendAttribution.begin("benchmarkNoiseBiome")) {
            if (scope == null) return 0;
            scope.waitingForLifecycle(); scope.acquired(); scope.runtime("runtime=benchmark");
            scope.outcome("cacheHit"); scope.sampleBegin(); scope.sampleEnd();
            return 1;
        }
    }

    private static void check(boolean ok, String message) {
        if (!ok) throw new AssertionError(message);
    }
}
