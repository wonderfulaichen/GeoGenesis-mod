package com.geogenesis.worldgen.terrain;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 轻量缓存命中统计（★ 2026-09-11 P0-3）。
 *
 * <p><b>为什么需要</b>：此前项目<b>零命中率埋点</b> —— 既无法判断缓存是否有效，
 * 也无法判断驱逐是否过频（只能靠猜）。本类以 3 个 {@link AtomicLong} 记录
 * hit / miss / eviction，供诊断日志与探针读取，热路径开销仅一次自增。</p>
 *
 * <p><b>刻意不记录延迟分位数（P50/P99）</b>：那需要在热路径取样或维护直方图，
 * 成本与收益不匹配；"命中率 + 驱逐数"已能回答"缓存是否有效、驱逐是否过频"这一核心问题。</p>
 */
public final class CacheStats {

    private final String name;
    private final AtomicLong hits = new AtomicLong();
    private final AtomicLong misses = new AtomicLong();
    private final AtomicLong evictions = new AtomicLong();

    public CacheStats(String name) { this.name = name; }

    public void hit()     { hits.incrementAndGet(); }
    public void miss()    { misses.incrementAndGet(); }
    public void evicted() { evictions.incrementAndGet(); }

    public long hits()      { return hits.get(); }
    public long misses()    { return misses.get(); }
    public long evictions() { return evictions.get(); }

    public void reset() {
        hits.set(0);
        misses.set(0);
        evictions.set(0);
    }

    /** 命中率（0..1）；尚无查询时返回 0。 */
    public double hitRate() {
        long total = hits.get() + misses.get();
        return total == 0 ? 0.0 : (double) hits.get() / total;
    }

    @Override
    public String toString() {
        return String.format("%s cache: hit=%d miss=%d (hitRate=%.1f%%) evict=%d",
            name, hits.get(), misses.get(), hitRate() * 100.0, evictions.get());
    }
}
