package com.geogenesis.worldgen.process;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

/** Runtime-private integer B input storage. One lease per caller; no executor or nested lease. */
final class BInputBlockCache implements AutoCloseable {
    interface Sampler { double sample(int x, int z); }
    interface Sink { void accept(int x, int z, double value); }
    static final class InputMetrics {
        long visited, blockHits, samplerCalls;
        @Override public String toString() {
            return "visited=" + visited + ",blockHits=" + blockHits + ",samplerCalls=" + samplerCalls;
        }
    }
    record Stats(int allocated, int peakAllocated, int leased, int peakLeased,
                 long requests, long hits, long evaluations, long evictions,
                 long blockWaits, long capacityWaits, long payloadBytes) {}
    // Nonfinite protocol values cannot collide with stored B (fill accepts finite values only).
    static final long NO_BLOCK = 0x7ff8000000000001L, INVALID_POINT = 0x7ff8000000000002L,
            CLOSED = 0x7ff8000000000003L;
    static final class PeekMetrics {
        final java.util.concurrent.atomic.LongAdder calls = new java.util.concurrent.atomic.LongAdder(),
                waitNs = new java.util.concurrent.atomic.LongAdder(), heldNs = new java.util.concurrent.atomic.LongAdder();
    }
    /** Read value + validity at one metadata linearization point; never lease, fill or touch LRU. */
    long peekBits(int x, int z, PeekMetrics metrics) {
        long start = metrics == null ? 0 : System.nanoTime();
        synchronized (metadata) {
            long acquired = metrics == null ? 0 : System.nanoTime();
            try {
                if (closed) return CLOSED;
                Slot s = index.get(Slot.key(Math.floorDiv(x, 32), Math.floorDiv(z, 32)));
                if (s == null) return NO_BLOCK;
                int i = Math.floorMod(z, 32) * 32 + Math.floorMod(x, 32);
                if ((s.valid[i >>> 6] & (1L << (i & 63))) == 0) return INVALID_POINT;
                return s.bits[i];
            } finally {
                if (metrics != null) {
                    metrics.calls.increment(); metrics.waitNs.add(acquired - start);
                    metrics.heldNs.add(System.nanoTime() - acquired);
                }
            }
        }
    }
    private static final class Slot {
        final ReentrantLock lock = new ReentrantLock();
        long[] bits, valid;
        int x, z, leases;
        Slot previous, next;
        boolean assigned;
        long key() { return key(x, z); }
        private static long key(int x, int z) { return ((long) x << 32) | (z & 0xffffffffL); }
    }
    private final Object metadata = new Object();
    private final Slot[] slots;
    private Map<Long, Slot> index = new HashMap<>();
    // Only unleased slots are linked, oldest first. Index includes leased/waiting slots.
    private Slot first, last;
    private volatile boolean closed;
    private int allocated, peakAllocated, leased, peakLeased;
    private long requests, hits, evaluations, evictions, blockWaits, capacityWaits;

    BInputBlockCache(int capacity) {
        if (capacity < 1) throw new IllegalArgumentException("positive B block capacity required");
        slots = new Slot[capacity];
        for (int i = 0; i < capacity; i++) { slots[i] = new Slot(); append(slots[i]); }
    }
    private void check(BooleanSupplier cancelled) {
        ParticleTile.check(() -> closed || cancelled.getAsBoolean());
    }
    private Slot acquire(int bx, int bz, BooleanSupplier cancelled) throws InterruptedException {
        synchronized (metadata) {
            for (;;) {
                check(cancelled);
                long key = Slot.key(bx, bz);
                Slot found = index.get(key);
                if (found != null) return lease(found);
                Slot oldest = first;
                if (oldest != null) {
                    if (oldest.assigned) { index.remove(oldest.key()); evictions++; }
                    if (oldest.bits == null) {
                        oldest.bits = new long[1024]; oldest.valid = new long[16];
                        peakAllocated = Math.max(peakAllocated, ++allocated);
                    } else Arrays.fill(oldest.valid, 0);
                    oldest.x = bx; oldest.z = bz; oldest.assigned = true;
                    index.put(key, oldest);
                    return lease(oldest);
                }
                capacityWaits++;
                metadata.wait(25); // cancellation tokens need not interrupt the waiting thread
            }
        }
    }
    private Slot lease(Slot s) {
        if (s.leases++ == 0) unlink(s);
        peakLeased = Math.max(peakLeased, ++leased);
        return s;
    }
    private void release(Slot s) {
        synchronized (metadata) {
            s.leases--; leased--;
            if (s.leases == 0) {
                if (closed) discard(s);
                append(s);
            }
            metadata.notifyAll();
        }
    }
    private void unlink(Slot s) {
        if (s.previous == null) first = s.next; else s.previous.next = s.next;
        if (s.next == null) last = s.previous; else s.next.previous = s.previous;
        s.previous = null; s.next = null;
    }
    private void append(Slot s) {
        s.previous = last; s.next = null;
        if (last == null) first = s; else last.next = s;
        last = s;
    }
    private void discard(Slot s) {
        if (s.assigned) index.remove(s.key());
        if (s.bits != null) { s.bits = null; s.valid = null; allocated--; }
        s.assigned = false;
    }
    /** Inclusive-exclusive rectangle; each block is released before requesting the next. */
    void copy(int x0, int z0, int width, int height, Sampler sampler, Sink sink,
              BooleanSupplier cancelled) {
        copy(x0, z0, width, height, sampler, sink, cancelled, new InputMetrics());
    }
    void copy(int x0, int z0, int width, int height, Sampler sampler, Sink sink,
              BooleanSupplier cancelled, InputMetrics metrics) {
        if (width < 1 || height < 1) throw new IllegalArgumentException("positive rectangle required");
        try {
            int x1 = Math.addExact(x0, width), z1 = Math.addExact(z0, height);
            for (int bz = Math.floorDiv(z0, 32); bz <= Math.floorDiv(z1 - 1, 32); bz++) {
                for (int bx = Math.floorDiv(x0, 32); bx <= Math.floorDiv(x1 - 1, 32); bx++) {
                    Slot s = acquire(bx, bz, cancelled);
                    try {
                        while (!s.lock.tryLock(25, TimeUnit.MILLISECONDS)) {
                            synchronized (metadata) { blockWaits++; }
                            check(cancelled);
                        }
                        try {
                            fill(s, Math.max(x0, bx * 32), Math.max(z0, bz * 32),
                                    Math.min(x1, bx * 32 + 32), Math.min(z1, bz * 32 + 32),
                                    sampler, sink, cancelled, metrics);
                        } finally { s.lock.unlock(); }
                    } finally { release(s); }
                }
            }
            check(cancelled);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new CancellationException("B input wait interrupted");
        }
    }
    private void fill(Slot s, int x0, int z0, int x1, int z1, Sampler sampler, Sink sink,
                      BooleanSupplier cancelled, InputMetrics metrics) {
        long count = 0, reused = 0, computed = 0;
        try {
            for (int z = z0; z < z1; z++) for (int x = x0; x < x1; x++) {
                check(cancelled); count++;
                int i = Math.floorMod(z, 32) * 32 + Math.floorMod(x, 32);
                long mask = 1L << (i & 63);
                if ((s.valid[i >>> 6] & mask) == 0) {
                    computed++;
                    double value = sampler.sample(x, z);
                    if (!Double.isFinite(value)) throw new IllegalStateException("nonfinite B input");
                    // close linearizes against point publication, never against slow evaluation.
                    synchronized (metadata) {
                        if (closed) throw new CancellationException("B input closed");
                        s.bits[i] = Double.doubleToRawLongBits(value);
                        s.valid[i >>> 6] |= mask;
                    }
                } else reused++;
                check(cancelled);
                sink.accept(x, z, Double.longBitsToDouble(s.bits[i]));
            }
        } finally {
            metrics.visited += count; metrics.blockHits += reused; metrics.samplerCalls += computed;
            synchronized (metadata) { requests += count; hits += reused; evaluations += computed; }
        }
    }
    Stats stats() {
        synchronized (metadata) {
            return new Stats(allocated, peakAllocated, leased, peakLeased, requests, hits,
                    evaluations, evictions, blockWaits, capacityWaits, allocated * 8320L);
        }
    }
    @Override public void close() {
        synchronized (metadata) {
            closed = true;
            for (Slot s : slots) if (s.leases == 0) discard(s);
            index = new HashMap<>(); // drop table capacity as well as entries on retirement
            metadata.notifyAll();
        }
    }
}
