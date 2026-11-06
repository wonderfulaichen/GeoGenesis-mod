package com.geogenesis.worldgen.generator;

import java.util.function.LongFunction;

/** One monitor for binding, invalidation and publication; no numeric seed sentinel. */
public final class WorldSeedLifecycle<T> {
    private boolean bound;
    private long seed;
    private T value;
    private volatile long version;

    /** Changes on every bind/unbind/invalidation, including same-seed rebinding (ABA). */
    public long version() { return version; }

    /** Call under this monitor when capturing or accepting an out-of-lock result. */
    public synchronized boolean matches(long capturedVersion, T capturedValue) {
        return bound && version == capturedVersion && value == capturedValue;
    }

    public synchronized void bind(long seed) {
        this.seed = seed;
        bound = true;
        value = null;
        version++;
    }

    public synchronized void unbind() {
        bound = false;
        value = null;
        version++;
    }

    public synchronized boolean isBound() { return bound; }

    public synchronized long seed() {
        if (!bound) throw new IllegalStateException("World seed not bound by server Load");
        return seed;
    }

    public synchronized void invalidate() { value = null; version++; }

    public synchronized T current() { return value; }

    public synchronized T getOrBuild(LongFunction<T> factory) {
        long actualSeed = seed();
        if (value == null) value = java.util.Objects.requireNonNull(factory.apply(actualSeed));
        return value;
    }
}
