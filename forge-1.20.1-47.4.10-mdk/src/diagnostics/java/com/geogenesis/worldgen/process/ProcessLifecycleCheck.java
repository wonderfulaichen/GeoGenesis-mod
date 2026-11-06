package com.geogenesis.worldgen.process;

import java.lang.reflect.Field;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** Deterministic lifecycle races. Assertions and timeouts propagate as nonzero exit. */
public final class ProcessLifecycleCheck {
    private static final long TIMEOUT = 60;
    static void require(boolean value, String message) {
        if (!value) throw new AssertionError(message);
    }
    static void await(CountDownLatch latch) throws InterruptedException {
        require(latch.await(TIMEOUT, TimeUnit.SECONDS), "latch timeout");
    }
    static void uninterruptible(CountDownLatch latch) {
        boolean interrupted = false;
        while (latch.getCount() != 0) {
            try { await(latch); } catch (InterruptedException ex) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }
    static void failed(ProcessRuntime.Subscription s) throws Exception {
        try {
            s.future().toCompletableFuture().get(TIMEOUT, TimeUnit.SECONDS);
            throw new AssertionError("expected cancelled subscription");
        } catch (ExecutionException | CancellationException expected) { }
    }
    static ProcessRuntime runtime(int queue, ProcessRuntime.Observer observer) {
        return new ProcessRuntime(ProcessRuntimeCheck.context(ProcessContext.Mode.PARTICLE),
                new ProcessRuntime.Budget(1, queue, 32), observer);
    }
    static Object gate(ProcessRuntime r) throws Exception {
        Field field = ProcessRuntime.class.getDeclaredField("gate");
        field.setAccessible(true);
        return field.get(r);
    }
    static void gateProbe(ProcessRuntime r, Object gate) throws Exception {
        require(!Thread.holdsLock(gate), "external callback holds gate");
        CompletableFuture.supplyAsync(r::metrics).get(5, TimeUnit.SECONDS);
    }
    static void assembleRace(boolean close) throws Exception {
        var entered = new CountDownLatch(1);
        var resume = new CountDownLatch(1);
        var assemblyReturned = new CountDownLatch(1);
        AtomicBoolean first = new AtomicBoolean(true);
        var observer = new ProcessRuntime.Observer() {
            public void beforeAssemble() {
                if (!first.getAndSet(false)) return;
                entered.countDown(); uninterruptible(resume); assemblyReturned.countDown();
            }
        };
        try (var r = runtime(8, observer)) {
            var s = r.requestExact(24, 24);
            await(entered);
            if (close) r.close(); else s.cancel();
            failed(s);
            resume.countDown(); await(assemblyReturned);
            // The worker cannot service this barrier until the old assemble/finish has returned.
            Field field = ProcessRuntime.class.getDeclaredField("executor");
            field.setAccessible(true);
            if (!close) {
                ((ThreadPoolExecutor) field.get(r)).submit(() -> {}).get(TIMEOUT, TimeUnit.SECONDS);
                require(r.peek(24, 24) instanceof ProcessRuntime.Unavailable,
                        "cancelled assemble must not remember point");
                require(Double.isFinite(r.exactP(24, 24).eP()), "published tile survives cancel");
                require(r.metrics().builds() == 1, "settled tile not cancelled/rebuilt");
            } else {
                require(((ThreadPoolExecutor) field.get(r)).awaitTermination(TIMEOUT, TimeUnit.SECONDS),
                        "closed worker did not terminate");
                require(r.metrics().completed() == 0 && r.metrics().flights() == 0, "close clears all");
                require(r.peek(24, 24).equals(new ProcessRuntime.Unavailable(ProcessRuntime.Missing.CLOSED)),
                        "no remember after close");
                failed(r.requestExact(24, 24));
            }
        } finally { resume.countDown(); }
        System.out.println("PASS " + (close ? "close" : "cancel") + " during assemble");
    }
    static void callbacks() throws Exception {
        for (int action = 0; action < 3; action++) {
            var entered = new CountDownLatch(1); var resume = new CountDownLatch(1);
            try (var r = runtime(8, new ProcessRuntime.Observer() {
                public void beforeBuild(TileGeometry.Owner o) {
                    entered.countDown(); uninterruptible(resume);
                }
            })) {
                var s = r.requestExact(24, 24); await(entered);
                Object gate = gate(r);
                var callback = s.future().handle((v, ex) -> {
                    try { gateProbe(r, gate); }
                    catch (Exception problem) { throw new CompletionException(problem); }
                    return true;
                }).toCompletableFuture();
                if (action == 0) resume.countDown();
                else if (action == 1) s.cancel(); else r.close();
                require(callback.get(TIMEOUT, TimeUnit.SECONDS), "callback missing");
                resume.countDown();
                if (action != 0) failed(s);
            } finally { resume.countDown(); }
        }
        System.out.println("PASS success/cancel/close callbacks reenter without gate");
    }
    static void inlineQueue() throws Exception {
        var entered = new CountDownLatch(1); var resume = new CountDownLatch(1);
        var caller = Thread.currentThread(); AtomicBoolean inline = new AtomicBoolean();
        AtomicReference<ProcessRuntime> ref = new AtomicReference<>();
        var observer = new ProcessRuntime.Observer() {
            public void beforeBuild(TileGeometry.Owner o) throws Exception {
                gateProbe(ref.get(), gate(ref.get()));
                if (o.x() == 0) { entered.countDown(); await(resume); }
                if (o.x() == 192) inline.set(Thread.currentThread() == caller);
            }
        };
        try (var r = runtime(1, observer)) {
            ref.set(r);
            var a = r.requestExact(24, 24); await(entered);
            var b = r.requestExact(120, 24);
            var c = r.requestExact(216, 24);
            require(Double.isFinite(c.await().eP()), "inline demand lost");
            require(inline.get() && r.metrics().inlineFallbacks() == 1, "full queue runs inline");
            require(r.metrics().queued() == 1, "bounded queue");
            a.cancel(); b.cancel(); failed(a); failed(b); resume.countDown();
        } finally { resume.countDown(); }
        System.out.println("PASS queue full inline outside gate / queued cancellation");
    }
    static void prewarm(boolean close) throws Exception {
        var entered = new CountDownLatch(1); var resume = new CountDownLatch(1);
        AtomicReference<TileGeometry.Owner> first = new AtomicReference<>();
        var observer = new ProcessRuntime.Observer() {
            public void beforeBuild(TileGeometry.Owner o) {
                if (first.compareAndSet(null, o)) { entered.countDown(); uninterruptible(resume); }
            }
        };
        try (var r = runtime(8, observer)) {
            require(r.prewarmTiles(24, 24, 24, 24) > 0, "prewarm submits"); await(entered);
            var owner = first.get();
            var a = r.requestExact(owner.x() + 24, owner.z() + 24);
            var b = r.requestExact(owner.x() + 24, owner.z() + 24);
            a.cancel(); failed(a);
            if (close) { r.close(); failed(b); }
            resume.countDown();
            if (!close) {
                require(Double.isFinite(b.await().eP()), "demand takes over prewarm; other holder survives");
                executor(r).submit(() -> {}).get(TIMEOUT, TimeUnit.SECONDS);
                long builds = r.metrics().builds();
                r.exactP(owner.x() + 24, owner.z() + 24);
                require(r.metrics().builds() == builds, "prewarm singleflight cached");
            }
        } finally { resume.countDown(); }
        System.out.println("PASS prewarm takeover / " + (close ? "shutdown" : "one-holder cancellation"));
    }
    static void prewarmLastCancel() throws Exception {
        var entered = new CountDownLatch(1); var resume = new CountDownLatch(1);
        AtomicReference<TileGeometry.Owner> first = new AtomicReference<>();
        try (var r = runtime(8, new ProcessRuntime.Observer() {
            public void beforeBuild(TileGeometry.Owner o) {
                if (first.compareAndSet(null, o)) { entered.countDown(); uninterruptible(resume); }
            }
        })) {
            r.prewarmTiles(24, 24, 24, 24); await(entered);
            var o = first.get(); var s = r.requestExact(o.x() + 24, o.z() + 24);
            s.cancel(); failed(s); resume.countDown();
            require(Double.isFinite(r.exactP(o.x() + 24, o.z() + 24).eP()), "cancelled prewarm retries");
        } finally { resume.countDown(); }
        System.out.println("PASS prewarm last-holder cancel / retry");
    }
    static ThreadPoolExecutor executor(ProcessRuntime r) throws Exception {
        Field field = ProcessRuntime.class.getDeclaredField("executor");
        field.setAccessible(true);
        return (ThreadPoolExecutor) field.get(r);
    }
    static void shutdownDispatch() throws Exception {
        try (var r = runtime(8, new ProcessRuntime.Observer() {})) {
            executor(r).shutdownNow();
            failed(r.requestExact(24, 24));
            require(r.metrics().flights() == 0 && r.metrics().builds() == 0,
                    "executor shutdown must fail rather than inline or hang");
        }
        System.out.println("PASS shutdown dispatch completes exceptionally");
    }
    static void closeBeforePublish() throws Exception {
        var entered = new CountDownLatch(1); var resume = new CountDownLatch(1);
        try (var r = runtime(8, new ProcessRuntime.Observer() {
            public void beforePublish(TileGeometry.Owner o) {
                entered.countDown(); uninterruptible(resume);
            }
        })) {
            var s = r.requestExact(24, 24); await(entered);
            r.close(); failed(s); resume.countDown();
            require(executor(r).awaitTermination(TIMEOUT, TimeUnit.SECONDS), "late publish worker stuck");
            require(r.metrics().successful() == 0 && r.metrics().completed() == 0,
                    "late tile must not publish after close");
        } finally { resume.countDown(); }
        System.out.println("PASS close rejects uncooperative late publication");
    }
    static void dependentCancellationAndReserve() throws Exception {
        var entered = new CountDownLatch(1); var resume = new CountDownLatch(1);
        try (var r = runtime(1, new ProcessRuntime.Observer() {
            public void beforeBuild(TileGeometry.Owner o) throws Exception {
                entered.countDown(); await(resume);
            }
        })) {
            var a = r.requestExact(24, 24); await(entered);
            var view = a.future().toCompletableFuture(); view.cancel(false);
            require(r.prewarmTiles(500, 500, 500, 500) == 0, "prewarm yields reserved demand queue");
            var b = r.requestExact(120, 24);
            require(r.metrics().queued() == 1, "demand retains reserved queue");
            resume.countDown();
            require(Double.isFinite(a.await().eP()) && Double.isFinite(b.await().eP()),
                    "dependent view cancellation cannot cancel subscription");
        } finally { resume.countDown(); }
        System.out.println("PASS dependent cancellation isolation / prewarm demand reserve");
    }
    public static void main(String[] args) throws Exception {
        assembleRace(false); assembleRace(true); callbacks(); inlineQueue();
        prewarm(false); prewarm(true); prewarmLastCancel(); shutdownDispatch();
        closeBeforePublish(); dependentCancellationAndReserve();
        System.out.println("PROCESS LIFECYCLE ALL PASS");
    }
}
