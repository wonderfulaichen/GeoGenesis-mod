package com.geogenesis.worldgen.process;

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
                          int completed, int flights, int queued, int active) {}
    // Package-private lifecycle seam for deterministic fault/race verification, not terrain injection.
    interface Observer {
        default void beforeBuild(TileGeometry.Owner owner) throws Exception {}
        default void beforePublish(TileGeometry.Owner owner) throws Exception {}
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
            failures = new AtomicLong(), cancellations = new AtomicLong();
    private final class Flight {
        final TileGeometry.Owner owner;
        final CompletableFuture<ParticleTile> result = new CompletableFuture<>();
        final Set<Subscription> holders = new HashSet<>();
        volatile boolean cancelled;
        FutureTask<Void> task;
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
                }, new ThreadPoolExecutor.AbortPolicy());
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
        synchronized (gate) {
            if (closed) { s.result.completeExceptionally(new CancellationException("closed context")); return s; }
            if (context.definition().mode() == ProcessContext.Mode.OFF) {
                s.result.complete(remember(assemble(x, z, footprint, Map.of()))); return s;
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
                            try { s.result.complete(remember(assemble(x, z, footprint, tiles))); }
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
            try { executor.execute(f.task); }
            catch (RejectedExecutionException ex) {
                flights.remove(owner, f); f.result.completeExceptionally(ex); throw ex;
            }
        } else { f.holders.add(s); s.held.add(f); }
        return f.result;
    }
    private void run(Flight f) {
        try {
            builds.incrementAndGet();
            observer.beforeBuild(f.owner);
            ParticleTile.check(() -> f.cancelled);
            ParticleTile tile = ParticleTile.build(context, f.owner, () -> f.cancelled);
            observer.beforePublish(f.owner);
            synchronized (gate) {
                ParticleTile.check(() -> closed || f.cancelled || flights.get(f.owner) != f);
                completed.put(f.owner, tile);
                while (completed.size() > budget.completedTiles()) {
                    completed.remove(completed.keySet().iterator().next());
                }
                successes.incrementAndGet();
                // Cache publication precedes callbacks; cleanup compares unique flight identity.
                f.result.complete(tile);
                flights.remove(f.owner, f);
            }
        } catch (Throwable ex) {
            synchronized (gate) {
                if (ex instanceof CancellationException || f.cancelled || closed) cancellations.incrementAndGet();
                else failures.incrementAndGet();
                f.result.completeExceptionally(ex);
                flights.remove(f.owner, f);
            }
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
    private Sample assemble(double x, double z, TileGeometry.Footprint f,
                            Map<TileGeometry.Owner, ParticleTile> tiles) {
        var b = context.sampleBase(x, z);
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
    }
    public Metrics metrics() {
        synchronized (gate) {
            return new Metrics(builds.get(), successes.get(), failures.get(), cancellations.get(),
                    completed.size(), flights.size(), executor.getQueue().size(), executor.getActiveCount());
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
