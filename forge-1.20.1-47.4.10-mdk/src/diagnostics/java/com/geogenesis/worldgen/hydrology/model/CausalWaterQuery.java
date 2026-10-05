package com.geogenesis.worldgen.hydrology.model;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;

/** Experimental finite-step infinite-lattice query. Not a steady-state production model. */
public final class CausalWaterQuery {
    public interface Input {
        double bed(int x, int z);
        double initialDepth(int x, int z);
    }
    private record Key(int step, int x, int z) { }
    private final Input input;
    private final ModelParams params;
    private final double dt;
    private final int capacity;
    private final Map<Key, Double> cache = new LinkedHashMap<>(128, 0.75f, true);
    private long evaluations;

    public CausalWaterQuery(Input input, ModelParams params, double dt, int capacity) {
        if (!(dt > 0) || !Double.isFinite(dt) || capacity <= 0) throw new IllegalArgumentException("query configuration");
        this.input = input;
        this.params = params;
        this.dt = dt;
        this.capacity = capacity;
    }

    /** Serializes this small prototype's memoization; no partially computed values are published. */
    public synchronized double depth(int step, int x, int z) {
        if (step < 0) throw new IllegalArgumentException("negative time layer");
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("query interrupted");
        if (step == 0) {
            double value = input.initialDepth(x, z);
            if (!Double.isFinite(value) || value < 0) throw new IllegalStateException("invalid initial depth");
            return value;
        }
        Key key = new Key(step, x, z);
        Double hit = cache.get(key);
        if (hit != null) return hit;
        double value = evolve(step, x, z);
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("query interrupted before publish");
        evaluations++;
        cache.put(key, value);
        if (cache.size() > capacity) cache.remove(cache.keySet().iterator().next());
        return value;
    }

    private double evolve(int step, int x, int z) {
        int previous = step - 1;
        double net = 0;
        // Fixed canonical face reduction order matches the finite-domain explicit experiment.
        net += limitedFlux(previous, x - 1, z, x, z) * dt;
        net += limitedFlux(previous, x, z - 1, x, z) * dt;
        net -= limitedFlux(previous, x, z, x + 1, z) * dt;
        net -= limitedFlux(previous, x, z, x, z + 1) * dt;
        double next = depth(previous, x, z) + net / params.cellArea();
        if (next < -1e-10 || !Double.isFinite(next)) throw new IllegalStateException("invalid query depth");
        double available = Math.max(0, next) + params.rainRate() * dt;
        available -= Math.min(available, params.infilRate() * dt);
        double evaporation = (params.evapRate() + params.evapDepthCoeff() * available) * dt;
        return available - Math.min(available, evaporation);
    }

    private double limitedFlux(int step, int ax, int az, int bx, int bz) {
        double flux = rawFlux(step, ax, az, bx, bz);
        int x = flux >= 0 ? ax : bx, z = flux >= 0 ? az : bz;
        return flux * donorScale(step, x, z);
    }

    private double donorScale(int step, int x, int z) {
        double demand = Math.max(0, rawFlux(step, x, z, x + 1, z))
                + Math.max(0, rawFlux(step, x, z, x, z + 1))
                + Math.max(0, -rawFlux(step, x - 1, z, x, z))
                + Math.max(0, -rawFlux(step, x, z - 1, x, z));
        double volume = depth(step, x, z) * params.cellArea();
        return demand * dt > volume ? volume / (demand * dt) : 1;
    }

    private double rawFlux(int step, int ax, int az, int bx, int bz) {
        double aBed = input.bed(ax, az), bBed = input.bed(bx, bz);
        double aSurface = aBed + depth(step, ax, az), bSurface = bBed + depth(step, bx, bz);
        double head = aSurface - bSurface;
        double faceDepth = Math.max(0, Math.max(aSurface, bSurface) - Math.max(aBed, bBed));
        return Math.signum(head) * Math.pow(faceDepth, 5.0 / 3.0) / params.manningN()
                * Math.sqrt(Math.abs(head) / params.cellSize()) * params.edgeLength();
    }

    public synchronized void clearCache() { cache.clear(); }
    public synchronized int cacheSize() { return cache.size(); }
    public synchronized long evaluations() { return evaluations; }
}
