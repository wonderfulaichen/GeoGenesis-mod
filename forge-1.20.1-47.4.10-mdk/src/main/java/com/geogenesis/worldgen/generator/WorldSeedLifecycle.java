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

    /**
     * <b>绑定种子并直接采用一个已建好的实例</b>（★ 2026-11-09）。
     *
     * <h2>为什么需要它</h2>
     * <p>配置屏（新建世界）在 MC 之外用<b>纯 Java 核心</b>算好了一片地形
     * （{@code GeoGenesisTerrain}，含 4096-chunk Cell 缓存与细层水文 region）。
     * 若进世界时走 {@link #bind} + {@link #getOrBuild}，那个实例会被丢弃、从零重采
     * —— 正是用户指出的「重复计算浪费」。</p>
     *
     * <p>本方法让 {@code bind} 的同时把已有实例发布为 {@code value}，
     * 于是紧随其后的 {@code getOrBuild} 直接返回它，不再调用工厂。</p>
     *
     * <h2>★ 安全前提（由调用方保证）</h2>
     * <p>调用方必须先校验「种子 + 参数 + morphBase」三项全等，
     * 否则会发布一个<b>属于别的世界</b>的地形。见
     * {@code GeoGenesisGenerator.bindSeedLocked} 的接管判据。</p>
     *
     * @param seed  要绑定的种子（必须与 {@code value} 实际种子一致）
     * @param value 已建好的实例（非 null）
     */
    public synchronized void adopt(long seed, T value) {
        this.seed = seed;
        this.bound = true;
        this.value = java.util.Objects.requireNonNull(value, "adopted value");
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
