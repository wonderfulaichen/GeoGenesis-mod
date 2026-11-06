package com.geogenesis.diagnostics;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** Diagnostic wall-clock observations only. No Minecraft dependency or generation decisions. */
public final class FrontendAttribution {
    private static volatile BooleanSupplier gate = () -> false;
    private static volatile Consumer<String> sink = text -> {};
    private static volatile Consumer<String> console = text -> {};
    private static final AtomicLong IDS = new AtomicLong();
    private static final ThreadLocal<Scope> CURRENT = new ThreadLocal<>();
    private static final ConcurrentHashMap<Long, Scope> ACTIVE = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, Totals> TOTALS = new ConcurrentHashMap<>();
    private static final ConcurrentHashMap<String, AtomicLong> STACK_LAST = new ConcurrentHashMap<>();
    public static final int SAMPLE_EVERY = 1024;
    private static final ThreadLocal<java.util.HashMap<String, int[]>> CALLS =
            ThreadLocal.withInitial(java.util.HashMap::new);
    private static final AtomicLong CAPTURE_LAST = new AtomicLong();
    private static volatile java.util.concurrent.ScheduledExecutorService timer;
    private static volatile String session = "unbound";
    private static volatile long boundNs, firstFillNs;
    private static final Object BOUNDARY = new Object();
    private FrontendAttribution() {}

    public static void configure(BooleanSupplier enabled, Consumer<String> log, Consumer<String> heartbeat) {
        gate = enabled; sink = log; console = heartbeat;
    }

    private static synchronized void ensureTimer() {
        if (timer != null) return;
        timer = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "GeoGenesis-attribution"); t.setDaemon(true); return t;
        });
        timer.scheduleAtFixedRate(() -> {
            try { report(); } catch (Exception ignored) { /* diagnostics cannot stop timer */ }
        }, 5, 5, TimeUnit.SECONDS);
    }

    static boolean timerStarted() { return timer != null; }
    static void reportNow() { report(); }

    public static void bound(long seed) {
        if (!gate.getAsBoolean()) return;
        synchronized (BOUNDARY) {
            session = "binding=" + IDS.incrementAndGet() + " seed=" + seed;
            boundNs = System.nanoTime(); firstFillNs = 0;
            CAPTURE_LAST.set(0);
            emit("event=bound " + session + " monoNs=" + boundNs);
        }
    }

    public static void firstFill(String generator) {
        if (!gate.getAsBoolean()) return;
        synchronized (BOUNDARY) {
            if (firstFillNs != 0) return;
            firstFillNs = System.nanoTime();
            emit("event=firstFillEntry " + session + " generator=" + generator
                    + " monoNs=" + firstFillNs + " sinceBindWallNs=" + (firstFillNs - boundNs));
        }
    }

    public static Scope begin(String source) {
        if (!gate.getAsBoolean()) return null;
        // Only thread-local mutable state on skipped calls; source strings are supplied by callers.
        var calls = CALLS.get();
        int[] count = calls.get(source);
        if (count == null) { count = new int[1]; calls.put(source, count); }
        if ((count[0]++ & (SAMPLE_EVERY - 1)) != 0) return null;
        ensureTimer();
        Scope scope = new Scope(source);
        CURRENT.set(scope); ACTIVE.put(scope.id, scope);
        return scope;
    }

    public static String currentSource() {
        Scope s = CURRENT.get();
        return s == null ? "other" : s.source;
    }

    public static String capture() {
        if (!gate.getAsBoolean()) return "diagnostics=off";
        Scope s = CURRENT.get();
        long now = System.nanoTime();
        AtomicLong last = CAPTURE_LAST;
        long old = last.get();
        if ((old == 0 || now - old >= 5_000_000_000L) && last.compareAndSet(old, now)) {
            String frames = stack();
            emit("event=tileSourceStack " + session + " source=" + currentSource()
                    + " category=" + classify(frames) + " frames=" + frames);
        }
        if (s != null) return s.label() + " attributionContext=nearest_sampled_scope_not_exclusive";
        // null scopes have no exit callback: do not mislabel a stale last-entered source as current.
        return session + " source=other scope=0 sampled=false submitterThread=" + Thread.currentThread().getName()
                + "#" + Thread.currentThread().getId() + " enteredPreFill=" + (firstFillNs == 0);
    }

    private static String stack() {
        StringBuilder out = new StringBuilder();
        for (StackTraceElement f : Thread.currentThread().getStackTrace()) {
            if (f.getClassName().equals(FrontendAttribution.class.getName())
                    || f.getClassName().startsWith(FrontendAttribution.class.getName() + "$")
                    || f.getClassName().equals(Thread.class.getName())) continue;
            if (out.length() > 0) out.append(" <- ");
            out.append(f);
            if (out.length() > 6000) break;
        }
        return out.toString();
    }

    // Stack categories are evidence-based hints; raw sampled frames remain authoritative.
    public static String classify(String frames) {
        if (frames.contains("generateRingPositions")) return "STRUCTURE_RING";
        if (frames.contains("createStructures") || frames.contains("generateStructure")
                || frames.contains("StructureStart") || frames.contains("findGenerationPoint")) return "STRUCTURE_STARTS";
        if (frames.contains("findBiome") || frames.contains("findClosestBiome")
                || frames.contains("findSpawn") || frames.contains("setInitialSpawn")) return "spawn_biome_search_hint";
        if (frames.contains("createBiomes") || frames.contains("fillBiomesFromNoise")) return "BIOMES";
        return "other";
    }

    public static final class Scope implements AutoCloseable {
        final Scope previous = CURRENT.get();
        final long id = IDS.incrementAndGet(), start = System.nanoTime();
        final String binding = session, source, thread = Thread.currentThread().getName();
        final long threadId = Thread.currentThread().getId();
        final boolean preFill = firstFillNs == 0;
        volatile String runtime = "runtime=unknown", phase = "entry", outcome = "exception";
        volatile long waitStart, acquired, sampleStart, sampleNs;
        public void waitingForLifecycle() { waitStart = System.nanoTime(); phase = "WORLD_LIFECYCLE_wait"; }
        private long waitNs() { return acquired == 0 || waitStart == 0 ? 0 : acquired - waitStart; }
        Scope(String source) {
            this.source = source;
            String key = binding + ":" + source;
            AtomicLong last = STACK_LAST.computeIfAbsent(key, k -> new AtomicLong());
            long old = last.get();
            if ((old == 0 || start - old >= 5_000_000_000L) && last.compareAndSet(old, start)) {
                String frames = stack();
                emit("event=sourceStack " + label() + " category=" + classify(frames) + " frames=" + frames);
            }
        }
        public void acquired() { acquired = System.nanoTime(); phase = "locked"; }
        public void runtime(String label) { runtime = label; }
        public void outcome(String value) { outcome = value; phase = value; }
        public void sampleBegin() { sampleStart = System.nanoTime(); phase = "sample"; }
        public void sampleEnd() { sampleNs += System.nanoTime() - sampleStart; sampleStart = 0; phase = "sampleDone"; }
        public String label() {
            return binding + " " + runtime + " sampled=true source=" + source + " scope=" + id
                    + " parent=" + (previous == null ? 0 : previous.id)
                    + " chain=" + chain() + " submitterThread=" + thread + "#" + threadId
                    + " enteredPreFill=" + preFill;
        }
        private String chain() { return previous == null ? source : previous.chain() + ">" + source; }
        @Override public void close() {
            CURRENT.set(previous);
            if (previous == null) CURRENT.remove();
            ACTIVE.remove(id);
            long wall = System.nanoTime() - start;
            String key = binding + " " + runtime + " source=" + source + " outcome=" + outcome
                    + " enteredPreFill=" + preFill;
            Totals t = TOTALS.computeIfAbsent(key, k -> new Totals());
            t.count.increment(); t.wall.add(wall); t.wait.add(waitNs());
            t.sample.add(sampleNs); t.max.accumulateAndGet(wall, Math::max);
            if (wall >= 100_000_000L) {
                AtomicLong last = STACK_LAST.computeIfAbsent(key + ":slow", k -> new AtomicLong());
                long now = System.nanoTime(), old = last.get();
                if (now - old >= 5_000_000_000L && last.compareAndSet(old, now))
                    emit("event=slowQuery " + label() + " outcome=" + outcome + " wallNs=" + wall
                            + " lifecycleWaitWallNs=" + (waitNs())
                            + " sampleWallNs=" + sampleNs + " frames=" + stack());
            }
        }
    }

    private static final class Totals {
        final LongAdder count = new LongAdder(), wall = new LongAdder(), wait = new LongAdder(), sample = new LongAdder();
        final AtomicLong max = new AtomicLong(), reported = new AtomicLong();
    }
    private static void report() {
        if (!gate.getAsBoolean()) return;
        boolean busy = !ACTIVE.isEmpty();
        for (var e : TOTALS.entrySet()) {
            Totals t = e.getValue(); long n = t.count.sum();
            if (t.reported.getAndSet(n) == n) continue;
            busy = true;
            emit("event=aggregate " + e.getKey() + " sampled=true sampleEvery=" + SAMPLE_EVERY
                    + " coverage=first_then_every_N_per_thread_source;not_full_totals;no_total_time_extrapolation"
                    + " sampledCount=" + n + " sampledWallNs=" + t.wall.sum()
                    + " lifecycleWaitWallNs=" + t.wait.sum() + " sampleWallNs=" + t.sample.sum()
                    + " maxWallNs=" + t.max.get() + " totals=cumulative_nonAtomic_snapshot"
                    + " overlap=wait_and_sample_subset_query;queries_nested_or_parallel_not_additive;sample_outside_lifecycle_lock");
        }
        for (Scope s : ACTIVE.values()) emit("event=inflight " + s.label() + " phase=" + s.phase
                + " ageWallNs=" + (System.nanoTime() - s.start) + " monoNs=" + System.nanoTime());
        if (busy) console.accept("[FRONTEND] diagnostic heartbeat active=" + ACTIVE.size()
                + " " + session + " firstFillSeen=" + (firstFillNs != 0) + " details=root/debug-agent.log");
    }
    private static void emit(String text) {
        try { sink.accept(text); } catch (Exception ignored) { /* no business dependency */ }
    }
}
