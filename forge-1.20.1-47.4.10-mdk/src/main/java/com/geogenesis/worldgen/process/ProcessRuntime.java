package com.geogenesis.worldgen.process;

import com.geogenesis.diagnostics.WorldGenProfiler;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;

/** Isolated streaming B -> P authority. No F/model, application adapter or implicit prewarm. */
public final class ProcessRuntime implements AutoCloseable {
    public record Budget(int workers, int queuedTiles, int completedTiles) {
        public Budget {
            if (workers < 1 || queuedTiles < 1 || completedTiles < 1) {
                throw new IllegalArgumentException("positive bounded execution budgets required");
            }
        }
    }
    public record Sample(String identity, ProcessContext.BaseSample base, double eP, double yP) {
        public double deltaE() { return eP - base.eB(); }
        public double deltaY() { return yP - base.yB(); }
        public String dischargeAvailability() {
            return identity.contains("|OFF|") ? "OFF" : "NOT_REQUESTED";
        }
    }
    public enum Missing { NOT_READY, CLOSED }
    public sealed interface Peek permits Ready, Unavailable {}
    public record Ready(Sample sample) implements Peek {}
    public record Unavailable(Missing reason) implements Peek {}
    public record Metrics(long builds, long successful, long failures, long cancelled,
                          int completed, int flights, int queued, int active,
                          long inlineFallbacks) {}
    // Package-private lifecycle seam for deterministic fault/race verification, not terrain injection.
    interface Observer {
        default void beforeBuild(TileGeometry.Owner owner) throws Exception {}
        default void beforePublish(TileGeometry.Owner owner) throws Exception {}
    }
    // Diagnostic-only, task-local accounting. No global-total subtraction; no CPU sums.
    private static final AtomicLong DIAG_IDS = new AtomicLong();
    private final long diagnosticId = DIAG_IDS.incrementAndGet();
    private final ThreadLocal<SamplingTiming> samplingTiming = new ThreadLocal<>();
    public final class SamplingTiming implements AutoCloseable {
        private final SamplingTiming previous = samplingTiming.get();
        private final long id = DIAG_IDS.incrementAndGet(), start = System.nanoTime();
        private final String task;
        private final java.util.concurrent.atomic.LongAdder requests = new java.util.concurrent.atomic.LongAdder(),
                requestNs = new java.util.concurrent.atomic.LongAdder(), awaitNs = new java.util.concurrent.atomic.LongAdder(),
                assembleNs = new java.util.concurrent.atomic.LongAdder(), baseNs = new java.util.concurrent.atomic.LongAdder(),
                assembles = new java.util.concurrent.atomic.LongAdder();
        private SamplingTiming(String task) { this.task = task; }
        public void request(long prepare, long await) {
            requests.increment(); requestNs.add(prepare); awaitNs.add(await);
        }
        @Override public void close() {
            samplingTiming.set(previous);
            WorldGenProfiler.stutterLog("SAMPLING", "window-" + diagnosticId + "-" + id,
                    diagnosticLabel() + " window=" + id + " task=" + task + " startNs=" + start
                    + " endNs=" + System.nanoTime() + " requests=" + requests.sum()
                    + " requestNs=" + requestNs.sum() + " awaitNs=" + awaitNs.sum()
                    + " assembles=" + assembles.sum() + " assembleNs=" + assembleNs.sum()
                    + " assembleBaseNs=" + baseNs.sum()
                    + " overlap=assembleBase_subset_assemble;assemble_and_inlineTiles_subset_request_or_await;"
                    + "tile_tasks_shared_and_parallel;durations_wall_not_CPU;scope=thread_local_pipeline");
        }
    }
    /** Diagnostic scope only; callers must close on the opening thread. Null when gated off. */
    public SamplingTiming beginSamplingTiming(String task) {
        if (!WorldGenProfiler.stutterEnabled()) return null;
        SamplingTiming timing = new SamplingTiming(task);
        samplingTiming.set(timing);
        return timing;
    }
    public SamplingTiming currentSamplingTiming() { return samplingTiming.get(); }
    public String diagnosticLabel() {
        return "runtime=" + diagnosticId + " seed=" + context.definition().seed();
    }
    private final Object gate = new Object();
    private final ProcessContext context;
    private final Budget budget;
    private final Observer observer;
    private final ThreadPoolExecutor executor;
    private final LinkedHashMap<TileGeometry.Owner, ParticleTile> completed =
            new LinkedHashMap<>(16, .75f, true);
    private record Point(double x, double z) {}
    private final LinkedHashMap<Point, Sample> points = new LinkedHashMap<>(16, .75f, true);
    private final Map<TileGeometry.Owner, Flight> flights = new HashMap<>();
    private boolean closed;
    private final AtomicLong builds = new AtomicLong(), successes = new AtomicLong(),
            failures = new AtomicLong(), cancellations = new AtomicLong(),
            /** 线程池满而内联执行的次数（>0 说明发生过 backpressure；不是错误，但要看分布）。 */
            inlineFallbacks = new AtomicLong();
    private final class Flight {
        final long timingId = WorldGenProfiler.stutterEnabled() ? DIAG_IDS.incrementAndGet() : 0;
        final long submittedNs = timingId == 0 ? 0 : System.nanoTime();
        final SamplingTiming timing = samplingTiming.get();
        final TileGeometry.Owner owner;
        final CompletableFuture<ParticleTile> result = new CompletableFuture<>();
        final Set<Subscription> holders = new HashSet<>();
        volatile boolean cancelled;
        FutureTask<Void> task;
        long enqueuedNs;
        Flight(TileGeometry.Owner owner) { this.owner = owner; }
    }
    public final class Subscription implements AutoCloseable {
        private final CompletableFuture<Sample> result = new CompletableFuture<>();
        private final List<Flight> held = new ArrayList<>();
        private boolean released;
        // A dependent view may be cancelled without affecting this or other subscribers.
        public CompletionStage<Sample> future() { return result.minimalCompletionStage(); }
        public Sample await() throws InterruptedException, ExecutionException { return result.get(); }
        public void cancel() {
            synchronized (gate) {
                result.cancel(false);
                release(this);
            }
        }
        @Override public void close() { cancel(); }
    }
    public ProcessRuntime(ProcessContext context, Budget budget) {
        this(context, budget, new Observer() {});
    }
    ProcessRuntime(ProcessContext context, Budget budget, Observer observer) {
        this.context = Objects.requireNonNull(context);
        this.budget = Objects.requireNonNull(budget);
        this.observer = Objects.requireNonNull(observer);
        executor = new ThreadPoolExecutor(budget.workers(), budget.workers(), 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(budget.queuedTiles()), r -> {
                    Thread t = new Thread(r, "B-P-tile"); t.setDaemon(true); return t;
                },
                // ★ 2026-11-06：原来是 AbortPolicy —— 队列一满就抛 RejectedExecutionException，
                //   调用方（CellGenerator.processErosionDeltaE / erodedHeight）兜底成
                //   「本格不施加增量」⇒ 那些格子静默按未侵蚀算，邻居却侵蚀过 ⇒ 台阶/接缝，
                //   且发生与否取决于当时负载 ⇒ 同种子同坐标结果不确定。
                //   实机一次会话实测 269,508 次拒绝（pool=4/queue=64）。
                //   正解：拒绝即【内联执行】——绝不丢工作。线程池满说明该让调用方
                //   自己承担这段计算（标准的 backpressure），产出与串行一致。
                //   ParticleTile.build 不再回调 exactP（已核对）⇒ 内联不会递归。
                (r, e) -> {
                    if (e.isShutdown()) {
                        throw new RejectedExecutionException("process runtime closed");
                    }
                    inlineFallbacks.incrementAndGet();
                    r.run();
                });
    }
    public ProcessContext context() { return context; }
    public Sample exactP(double x, double z) throws InterruptedException, ExecutionException {
        Subscription subscription = requestExact(x, z);
        try { return subscription.await(); }
        finally { subscription.close(); }
    }
    public Subscription requestExact(double x, double z) {
        TileGeometry.Footprint footprint = TileGeometry.footprint(x, z);
        Subscription s = new Subscription();
        SamplingTiming timing = samplingTiming.get();
        synchronized (gate) {
            if (closed) { s.result.completeExceptionally(new CancellationException("closed context")); return s; }
            if (context.definition().mode() == ProcessContext.Mode.OFF) {
                s.result.complete(remember(timedAssemble(x, z, footprint, Map.of(), timing))); return s;
            }
            Map<TileGeometry.Owner, CompletableFuture<ParticleTile>> deps = new LinkedHashMap<>();
            try {
                for (var owner : footprint.required()) deps.put(owner, acquire(owner, s));
            } catch (RuntimeException ex) {
                s.result.completeExceptionally(ex); release(s); return s;
            }
            CompletableFuture.allOf(deps.values().toArray(CompletableFuture[]::new)).whenComplete((v, ex) -> {
                synchronized (gate) {
                    if (!s.result.isDone()) {
                        if (ex != null) s.result.completeExceptionally(ex);
                        else if (closed) s.result.completeExceptionally(new CancellationException("closed context"));
                        else {
                            Map<TileGeometry.Owner, ParticleTile> tiles = new HashMap<>();
                            deps.forEach((key, future) -> tiles.put(key, future.join()));
                            try { s.result.complete(remember(timedAssemble(x, z, footprint, tiles, timing))); }
                            catch (RuntimeException failure) { s.result.completeExceptionally(failure); }
                        }
                    }
                    release(s);
                }
            });
        }
        return s;
    }
    /**
     * Equivalent to the retired CellGenerator tile prewarm. Never blocks, never throws,
     * and never starves demand: when the executor queue is full the tile is simply
     * skipped and built later on demand.
     *
     * <p>Tiles are pure functions of the frozen context, so submitting them early changes only
     * timing, never output. That is why this does not enter {@link ProcessContext#identity()}.</p>
     *
     * @return number of tiles actually submitted
     */
    public int prewarmTiles(double x0, double z0, double x1, double z1) {
        if (context.definition().mode() == ProcessContext.Mode.OFF) return 0;
        // An owner a renders [a-40, a+87]; only submit tiles whose own range really
        // intersects the requested one, so the hint never wastes a cold tile build.
        double minX = Math.min(x0, x1), maxX = Math.max(x0, x1);
        double minZ = Math.min(z0, z1), maxZ = Math.max(z0, z1);
        int fromX = Math.floorDiv((int) Math.floor(minX) - 88, 48) * 48;
        int toX = Math.floorDiv((int) Math.floor(maxX) + 48, 48) * 48;
        int fromZ = Math.floorDiv((int) Math.floor(minZ) - 88, 48) * 48;
        int toZ = Math.floorDiv((int) Math.floor(maxZ) + 48, 48) * 48;
        int submitted = 0;
        for (int ax = fromX; ax <= toX; ax += 48) {
            if (ax - 40 > maxX || ax + 87 < minX) continue;
            for (int az = fromZ; az <= toZ; az += 48) {
                if (az - 40 > maxZ || az + 87 < minZ) continue;
                if (prewarmOwner(new TileGeometry.Owner(ax, az))) submitted++;
            }
        }
        return submitted;
    }
    /** Submit one tile unless already cached/in flight, or too little queue is left for demand. */
    private boolean prewarmOwner(TileGeometry.Owner owner) {
        synchronized (gate) {
            if (closed || completed.containsKey(owner) || flights.containsKey(owner)) return false;
            // Keep room for demand: a full queue would make acquire() hit AbortPolicy.
            // Prewarm is a hint, so it yields to real requests instead of crowding them out.
            int reserve = Math.max(1, budget.queuedTiles() / 2);
            if (executor.getQueue().remainingCapacity() <= reserve) return false;
            Flight f = new Flight(owner);
            flights.put(owner, f);
            f.task = new FutureTask<>(() -> { run(f); return null; });
            try {
                f.enqueuedNs = f.timingId == 0 ? 0 : System.nanoTime();
                executor.execute(f.task);
            } catch (RejectedExecutionException ex) {
                // Queue full: drop the prewarm hint only. Demand still gets its own slot.
                flights.remove(owner, f);
                return false;
            }
            return true;
        }
    }
    private CompletableFuture<ParticleTile> acquire(TileGeometry.Owner owner, Subscription s) {
        ParticleTile cached = completed.get(owner);
        if (cached != null) return CompletableFuture.completedFuture(cached);
        Flight f = flights.get(owner);
        if (f == null) {
            f = new Flight(owner);
            flights.put(owner, f);
            Flight created = f;
            f.task = new FutureTask<>(() -> { run(created); return null; });
            f.holders.add(s); s.held.add(f);
            try {
                f.enqueuedNs = f.timingId == 0 ? 0 : System.nanoTime();
                executor.execute(f.task);
            }
            catch (RejectedExecutionException ex) {
                flights.remove(owner, f); f.result.completeExceptionally(ex); throw ex;
            }
        } else { f.holders.add(s); s.held.add(f); }
        return f.result;
    }
    private void run(Flight f) {
        long start = f.timingId == 0 ? 0 : System.nanoTime();
        long buildEnd = 0, publishStart = 0, publishAcquired = 0, publishEnd = 0;
        long completeStart = 0, completeEnd = 0;
        ParticleTile tile = null;
        boolean published = false;
        try {
            builds.incrementAndGet();
            observer.beforeBuild(f.owner);
            ParticleTile.check(() -> f.cancelled);
            tile = ParticleTile.build(context, f.owner, () -> f.cancelled);
            buildEnd = f.timingId == 0 ? 0 : System.nanoTime();
            observer.beforePublish(f.owner);
            publishStart = f.timingId == 0 ? 0 : System.nanoTime();
            synchronized (gate) {
                publishAcquired = f.timingId == 0 ? 0 : System.nanoTime();
                ParticleTile.check(() -> closed || f.cancelled || flights.get(f.owner) != f);
                completed.put(f.owner, tile);
                while (completed.size() > budget.completedTiles()) {
                    completed.remove(completed.keySet().iterator().next());
                }
                successes.incrementAndGet();
                // Cache publication precedes callbacks; cleanup compares unique flight identity.
                completeStart = f.timingId == 0 ? 0 : System.nanoTime();
                f.result.complete(tile);
                completeEnd = f.timingId == 0 ? 0 : System.nanoTime();
                flights.remove(f.owner, f);
                published = true;
                publishEnd = f.timingId == 0 ? 0 : System.nanoTime();
            }
        } catch (Throwable ex) {
            synchronized (gate) {
                if (ex instanceof CancellationException || f.cancelled || closed) cancellations.incrementAndGet();
                else failures.incrementAndGet();
                f.result.completeExceptionally(ex);
                flights.remove(f.owner, f);
            }
        } finally {
            if (f.timingId != 0) WorldGenProfiler.stutterLog("SAMPLING", "tile-" + diagnosticId + "-" + f.timingId,
                    diagnosticLabel() + " flight=" + f.timingId + " owner=" + f.owner
                    + " submitterWindow=" + (f.timing == null ? 0 : f.timing.id)
                    + " task=" + (f.timing == null ? "external" : f.timing.task)
                    + " submittedNs=" + f.submittedNs + " startNs=" + start + " endNs=" + System.nanoTime()
                    + " dispatchDelayNs=" + (start - f.submittedNs)
                    + " queueDispatchNs=" + (start - f.enqueuedNs)
                    + " inputNs=" + (tile == null ? -1 : tile.inputNs)
                    + " kernelNs=" + (tile == null ? -1 : tile.kernelNs)
                    + " buildNs=" + (tile == null ? -1 : tile.buildNs)
                    + " buildOtherNs=" + (tile == null ? -1 : tile.buildNs - tile.inputNs - tile.kernelNs)
                    + " buildToPublishAttemptNs=" + (publishStart == 0 ? -1 : publishStart - buildEnd)
                    + " publishGateWaitNs=" + (publishAcquired == 0 ? -1 : publishAcquired - publishStart)
                    + " completeCallbacksNs=" + (completeEnd == 0 ? -1 : completeEnd - completeStart)
                    + " publishHeldCallbacksNs=" + (published ? publishEnd - publishAcquired : -1)
                    + " published=" + published
                    + " overlap=build_contains_input_and_kernel;queueDispatch=execute_to_run_includes_scheduling_or_inline;"
                    + "publishHeld_contains_dependent_assemble;shared_tile_not_exclusive_to_submitter;wall_not_CPU");
        }
    }
    private void release(Subscription s) {
        if (s.released) return;
        s.released = true;
        for (Flight f : s.held) {
            f.holders.remove(s);
            if (f.holders.isEmpty() && !f.result.isDone()) {
                f.cancelled = true;
                flights.remove(f.owner, f);
                if (f.task != null) { f.task.cancel(true); executor.remove(f.task); }
                f.result.completeExceptionally(new CancellationException("no subscribers"));
            }
        }
        s.held.clear();
    }
    public Peek peek(double x, double z) {
        var footprint = TileGeometry.footprint(x, z);
        synchronized (gate) {
            if (closed) return new Unavailable(Missing.CLOSED);
            Map<TileGeometry.Owner, ParticleTile> tiles = new HashMap<>();
            if (context.definition().mode() != ProcessContext.Mode.OFF) {
                for (var owner : footprint.required()) {
                    var tile = completed.get(owner);
                    if (tile == null) return new Unavailable(Missing.NOT_READY);
                    tiles.put(owner, tile);
                }
            }
            // Strict zero-computation peek: even B is not sampled here. Exact requests
            // publish a bounded immutable point result; all blend tiles must still be present.
            Sample point = points.get(new Point(x, z));
            return point == null ? new Unavailable(Missing.NOT_READY) : new Ready(point);
        }
    }
    private Sample remember(Sample sample) {
        points.put(new Point(sample.base().x(), sample.base().z()), sample);
        while (points.size() > (long) budget.completedTiles() * 64) {
            points.remove(points.keySet().iterator().next());
        }
        return sample;
    }
    private Sample timedAssemble(double x, double z, TileGeometry.Footprint f,
                                 Map<TileGeometry.Owner, ParticleTile> tiles, SamplingTiming timing) {
        long start = timing == null ? 0 : System.nanoTime();
        try {
        var b = context.sampleBase(x, z);
        if (timing != null) timing.baseNs.add(System.nanoTime() - start);
        if (context.definition().mode() == ProcessContext.Mode.OFF) {
            return new Sample(context.identity(), b, b.eB(), b.yB());
        }
        double d00 = tiles.get(f.main()).at(x, z), delta = d00;
        if (f.fx() > 0 || f.fz() > 0) {
            double d10 = f.fx() > 0 ? tiles.get(f.sideX()).at(x, z) : d00;
            double d01 = f.fz() > 0 ? tiles.get(f.sideZ()).at(x, z) : d00;
            double d11 = f.fx() > 0 && f.fz() > 0 ? tiles.get(f.corner()).at(x, z)
                    : f.fx() > 0 ? d10 : d01;
            delta = d00 * (1 - f.fx()) * (1 - f.fz()) + d10 * f.fx() * (1 - f.fz())
                    + d01 * (1 - f.fx()) * f.fz() + d11 * f.fx() * f.fz();
        }
        double eP = context.assemble(b.eB(), delta);
        if (!Double.isFinite(eP)) throw new IllegalStateException("invalid P point");
        return new Sample(context.identity(), b, eP, context.height(eP));
        } finally {
            if (timing != null) {
                timing.assembles.increment(); timing.assembleNs.add(System.nanoTime() - start);
            }
        }
    }
    public Metrics metrics() {
        synchronized (gate) {
            return new Metrics(builds.get(), successes.get(), failures.get(), cancellations.get(),
                    completed.size(), flights.size(), executor.getQueue().size(), executor.getActiveCount(),
                    inlineFallbacks.get());
        }
    }
    @Override public void close() {
        synchronized (gate) {
            closed = true;
            for (Flight f : List.copyOf(flights.values())) {
                f.cancelled = true;
                if (f.task != null) f.task.cancel(true);
                f.result.completeExceptionally(new CancellationException("closed context"));
            }
            flights.clear(); completed.clear(); points.clear(); executor.shutdownNow();
        }
    }
}
