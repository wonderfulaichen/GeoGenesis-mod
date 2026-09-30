package com.geogenesis.worldgen.hydrology.sim;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 跨 tile 的全球格点采样缓存。
 *
 * <p>tile halo 重叠。该缓存只按全球格坐标复用地形/source/decay 采样，不缓存求解结果，
 * 因而不把查询顺序变成水文输入。LRU 驱逐只影响性能，被驱逐格重算仍是同一纯函数值。</p>
 */
public final class HydroCellSampler implements HydroSampler {

    private static final int MAX_CELLS = 262_144;

    private record CellData(double height, double source, double decay) { }

    private final HydroSampler delegate;
    private final Map<Long, CellData> cache = new LinkedHashMap<>(4096, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<Long, CellData> eldest) {
            return size() > MAX_CELLS;
        }
    };
    private long hits;
    private long misses;

    public HydroCellSampler(HydroSampler delegate) {
        this.delegate = delegate;
    }

    private static long key(int gx, int gz) {
        return ((long) gx << 32) ^ (gz & 0xffffffffL);
    }

    private synchronized CellData at(double x, double z) {
        int gx = HydroContract.cellX(x);
        int gz = HydroContract.cellZ(z);
        long k = key(gx, gz);
        CellData d = cache.get(k);
        if (d != null) {
            hits++;
            return d;
        }
        misses++;
        double cx = HydroContract.cellCenterX(gx);
        double cz = HydroContract.cellCenterZ(gz);
        d = new CellData(delegate.height(cx, cz), delegate.source(cx, cz), delegate.decay(cx, cz));
        cache.put(k, d);
        return d;
    }

    @Override public double height(double blockX, double blockZ) { return at(blockX, blockZ).height(); }
    @Override public double source(double blockX, double blockZ) { return at(blockX, blockZ).source(); }
    @Override public double decay(double blockX, double blockZ) { return at(blockX, blockZ).decay(); }

    public synchronized long hits() { return hits; }
    public synchronized long misses() { return misses; }
    public synchronized int size() { return cache.size(); }

    public synchronized void clear() {
        cache.clear();
        hits = 0;
        misses = 0;
    }
}
