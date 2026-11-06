package com.geogenesis.worldgen.process;

import com.geogenesis.worldgen.core.BaseWorldSettings;
import com.geogenesis.worldgen.erosion.ParticleErosionParams;
import com.geogenesis.worldgen.terrain.TerrainParams;
import java.lang.reflect.Field;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Deterministic lifecycle races. Every failed assertion/timeout exits nonzero. */
public final class ProcessRuntimeLifecycleProbe {
    private static final long TIMEOUT = 60;
    private static final ProcessContext CONTEXT = new ProcessContext(new ProcessContext.Definition(
            9139912035078620160L, "P1-frozen-20261105", TerrainParams.defaults(),
            BaseWorldSettings.defaults(), -64, 320, ProcessContext.Mode.PARTICLE,
            new ParticleErosionParams(1, 1, .5, 1, 2, .1), false, 1, true, "spec-loaded=.5"));

    private static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    private static void await(CountDownLatch latch) throws InterruptedException {
        require(latch.await(TIMEOUT, TimeUnit.SECONDS), "latch timeout");
    }
    private static void uninterruptible(CountDownLatch latch) {
        boolean interrupted = false;
        while (latch.getCount() != 0) {
            try { await(latch); } catch (InterruptedException ex) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
    private static Object gate(ProcessRuntime runtime) throws Exception {
        Field field = ProcessRuntime.class.getDeclaredField("gate");
        field.setAccessible(true);
        return field.get(runtime);
    }
    private static void failed(ProcessRuntime.Subscription subscription) throws Exception {
        try {
            subscription.future().toCompletableFuture().get(TIMEOUT, TimeUnit.SECONDS);
            throw new AssertionError("cancelled subscription succeeded");
        } catch (ExecutionException | CancellationException expected) { /* terminal failure required */ }
    }
    private static ProcessRuntime.Sample value(ProcessRuntime.Subscription subscription) throws Exception {
        return subscription.future().toCompletableFuture().get(TIMEOUT, TimeUnit.SECONDS);
    }
    private static void drained(ProcessRuntime runtime) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(TIMEOUT);
        while ((runtime.metrics().active() != 0 || runtime.metrics().queued() != 0)
                && System.nanoTime() < deadline) Thread.sleep(5);
        require(runtime.metrics().active() == 0 && runtime.metrics().queued() == 0
                && runtime.metrics().flights() == 0, "runtime not drained");
    }
    private static void probeGate(ProcessRuntime runtime, Object lock) throws Exception {
        require(!Thread.holdsLock(lock), "external callback holds gate");
        var pool = Executors.newSingleThreadExecutor();
        try { pool.submit(runtime::metrics).get(5, TimeUnit.SECONDS); }
        finally { pool.shutdownNow(); }
    }
    public static void main(String[] args) throws Exception {
        assembleRace(false);
        assembleRace(true);
        synchronousClose();
        callbacks(false);
        callbacks(true);
        queueInline();
        prewarm(false);
        prewarm(true);
        closeQueued();
        System.out.println("PROCESS RUNTIME LIFECYCLE ALL PASS");
    }
    private static void assembleRace(boolean close) throws Exception {
        var build = new CountDownLatch(1); var start = new CountDownLatch(1);
        var assembling = new CountDownLatch(1); var resume = new CountDownLatch(1);
        var observer = new ProcessRuntime.Observer() {
            public void beforeBuild(TileGeometry.Owner owner) throws Exception { build.countDown(); await(start); }
            public void beforeAssemble() { assembling.countDown(); uninterruptible(resume); }
        };
        try (var runtime = new ProcessRuntime(CONTEXT, new ProcessRuntime.Budget(1, 4, 8), observer)) {
            var subscription = runtime.requestExact(24, 24);
            await(build); start.countDown(); await(assembling);
            if (close) runtime.close(); else subscription.cancel();
            failed(subscription);
            resume.countDown(); drained(runtime);
            require(runtime.peek(24, 24) instanceof ProcessRuntime.Unavailable, "late assemble remembered point");
            require(runtime.metrics().successful() == 1, "published tile lost to subscription cancel");
            if (close) require(runtime.metrics().completed() == 0, "close retained tile");
            else {
                value(runtime.requestExact(24, 24));
                require(runtime.metrics().builds() == 1, "published tile rebuilt after cancel");
            }
        } finally { start.countDown(); resume.countDown(); }
        System.out.println("PASS " + (close ? "close" : "cancel") + " during assemble/no late remember");
    }
    private static void synchronousClose() throws Exception {
        var entered = new CountDownLatch(1); var resume = new CountDownLatch(1);
        var definition = CONTEXT.definition();
        var off = new ProcessContext(new ProcessContext.Definition(definition.seed(), definition.baseAlgorithm(),
                definition.terrain(), definition.settings(), -64, 320, ProcessContext.Mode.OFF,
                definition.particle(), false, 1, true, definition.parameterSource()));
        var caller = Executors.newSingleThreadExecutor();
        try (var runtime = new ProcessRuntime(off, new ProcessRuntime.Budget(1, 4, 8), new ProcessRuntime.Observer() {
            public void beforeAssemble() { entered.countDown(); uninterruptible(resume); }
        })) {
            var request = caller.submit(() -> runtime.requestExact(24, 24));
            await(entered); runtime.close(); resume.countDown();
            failed(request.get(TIMEOUT, TimeUnit.SECONDS));
            require(runtime.metrics().builds() == 0, "OFF built tiles");
        } finally { resume.countDown(); caller.shutdownNow(); }
        System.out.println("PASS close during synchronous OFF assemble");
    }
    private static void callbacks(boolean cancel) throws Exception {
        var entered = new CountDownLatch(1); var resume = new CountDownLatch(1);
        try (var runtime = new ProcessRuntime(CONTEXT, new ProcessRuntime.Budget(1, 4, 8), new ProcessRuntime.Observer() {
            public void beforeBuild(TileGeometry.Owner owner) throws Exception { entered.countDown(); await(resume); }
        })) {
            Object lock = gate(runtime);
            var first = runtime.requestExact(24, 24); await(entered);
            var survivor = runtime.requestExact(24, 24);
            var callback = first.future().handle((sample, failure) -> {
                try {
                    probeGate(runtime, lock);
                    if (!cancel) value(runtime.requestExact(24, 24));
                    return true;
                } catch (Exception ex) { throw new CompletionException(ex); }
            }).toCompletableFuture();
            if (cancel) first.cancel();
            resume.countDown();
            require(callback.get(TIMEOUT, TimeUnit.SECONDS), "callback not complete");
            value(survivor);
            if (cancel) failed(first); else value(first);
            require(runtime.metrics().builds() == 1, "singleflight/callback reentry rebuilt tile");
        } finally { resume.countDown(); }
        System.out.println("PASS " + (cancel ? "cancellation" : "success") + " callback reentry outside gate/singleflight");
    }
    static void queueInline() throws Exception {
        var entered = new CountDownLatch(1); var resume = new CountDownLatch(1);
        var reference = new AtomicReference<ProcessRuntime>();
        var inlineThread = new AtomicReference<Thread>();
        var caller = Thread.currentThread();
        try (var runtime = new ProcessRuntime(CONTEXT, new ProcessRuntime.Budget(1, 1, 8), new ProcessRuntime.Observer() {
            public void beforeBuild(TileGeometry.Owner owner) throws Exception {
                require(!Thread.holdsLock(gate(reference.get())), "build holds gate");
                if (owner.x() == 0) { entered.countDown(); await(resume); }
                if (owner.x() == 192) inlineThread.set(Thread.currentThread());
            }
        })) {
            reference.set(runtime);
            var running = runtime.requestExact(24, 24); await(entered);
            var queued = runtime.requestExact(120, 24);
            var inline = runtime.requestExact(216, 24);
            value(inline);
            require(inlineThread.get() == caller && runtime.metrics().inlineFallbacks() == 1,
                    "queue-full demand must run inline");
            require(runtime.metrics().queued() == 1, "bounded executor queue");
            queued.cancel(); running.cancel(); failed(queued); failed(running);
            resume.countDown(); drained(runtime);
            require(runtime.metrics().completed() == 1, "cancelled tasks published");
        } finally { resume.countDown(); }
        System.out.println("PASS queue full inline outside gate/queued and running cancellation");
    }
    private static void prewarm(boolean cancelAll) throws Exception {
        var entered = new CountDownLatch(1); var resume = new CountDownLatch(1);
        var owner = new AtomicReference<TileGeometry.Owner>();
        try (var runtime = new ProcessRuntime(CONTEXT, new ProcessRuntime.Budget(1, 4, 16), new ProcessRuntime.Observer() {
            public void beforeBuild(TileGeometry.Owner tile) throws Exception {
                owner.compareAndSet(null, tile); entered.countDown(); await(resume);
            }
        })) {
            require(runtime.prewarmTiles(24, 24, 24, 24) > 0, "prewarm submitted nothing");
            await(entered);
            var tile = owner.get();
            var first = runtime.requestExact(tile.x() + 24, tile.z() + 24);
            var second = runtime.requestExact(tile.x() + 24, tile.z() + 24);
            first.cancel(); failed(first);
            if (cancelAll) { second.cancel(); failed(second); }
            resume.countDown();
            if (!cancelAll) value(second);
            drained(runtime);
            long builds = runtime.metrics().builds();
            value(runtime.requestExact(tile.x() + 24, tile.z() + 24));
            require(runtime.metrics().builds() == builds + (cancelAll ? 1 : 0),
                    "prewarm adoption cancellation/cache violated");
        } finally { resume.countDown(); }
        System.out.println("PASS prewarm demand adoption/" + (cancelAll ? "all cancel then retry" : "one cancel survivor"));
    }
    private static void closeQueued() throws Exception {
        var entered = new CountDownLatch(1); var resume = new CountDownLatch(1);
        try (var runtime = new ProcessRuntime(CONTEXT, new ProcessRuntime.Budget(1, 4, 8), new ProcessRuntime.Observer() {
            public void beforePublish(TileGeometry.Owner owner) { entered.countDown(); uninterruptible(resume); }
        })) {
            var running = runtime.requestExact(24, 24); await(entered);
            var queued = runtime.requestExact(120, 24);
            Object lock = gate(runtime);
            var callback = queued.future().handle((sample, failure) -> {
                try { probeGate(runtime, lock); return true; }
                catch (Exception ex) { throw new CompletionException(ex); }
            }).toCompletableFuture();
            runtime.close();
            failed(running); failed(queued); failed(runtime.requestExact(216, 24));
            require(callback.get(TIMEOUT, TimeUnit.SECONDS), "close callback not complete");
            resume.countDown(); drained(runtime);
            require(runtime.metrics().completed() == 0 && runtime.metrics().successful() == 0,
                    "publish after close");
            require(runtime.prewarmTiles(24, 24, 24, 24) == 0, "prewarm after close");
        } finally { resume.countDown(); }
        System.out.println("PASS close running/queued/late publication/reentrant callback/post-close requests");
    }
}
