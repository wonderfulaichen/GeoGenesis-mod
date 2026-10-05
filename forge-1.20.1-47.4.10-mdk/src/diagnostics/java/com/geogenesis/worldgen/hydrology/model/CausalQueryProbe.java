package com.geogenesis.worldgen.hydrology.model;

import java.util.concurrent.CancellationException;
import java.util.concurrent.Executors;

/** Query-history, eviction, cancellation and independent finite-domain checks. */
public final class CausalQueryProbe {
    private static final CausalWaterQuery.Input INPUT = new CausalWaterQuery.Input() {
        public double bed(int x, int z) { return 0.05 * Math.sin(x * 0.3) * Math.cos(z * 0.2); }
        public double initialDepth(int x, int z) { return 0.12 + 0.01 * Math.sin(x * 0.2 + z * 0.3); }
    };

    public static void main(String[] args) throws Exception {
        ModelParams p = ModelParams.defaults();
        int steps = 3, x = -7, z = 11;
        double dt = 0.00001;
        CausalWaterQuery query = new CausalWaterQuery(INPUT, p, dt, 1000);
        double reference = query.depth(steps, x, z);
        query.depth(steps, x + 1, z + 1);
        requireBits(reference, query.depth(steps, x, z), "different query root/history");
        query.clearCache();
        requireBits(reference, query.depth(steps, x, z), "cold cache rebuild");
        CausalWaterQuery bounded = new CausalWaterQuery(INPUT, p, dt, 32);
        requireBits(reference, bounded.depth(steps, x, z), "cache eviction");
        if (bounded.cacheSize() > 32) throw new AssertionError("cache not bounded");
        Thread.currentThread().interrupt();
        boolean cancelled = false;
        try { query.depth(steps, x, z); } catch (CancellationException expected) { cancelled = true; }
        finally { Thread.interrupted(); }
        if (!cancelled) throw new AssertionError("interrupted query published as success");
        requireBits(reference, query.depth(steps, x, z), "success after cancellation");
        var pool = Executors.newFixedThreadPool(2);
        try {
            var a = pool.submit(() -> query.depth(steps, x, z));
            var b = pool.submit(() -> query.depth(steps, x, z));
            requireBits(a.get(), b.get(), "concurrent queries");
        } finally { pool.shutdownNow(); }
        double domain = finiteReference(steps, x, z, dt, p);
        if (Math.abs(reference - domain) > 1e-14) throw new AssertionError("finite-domain mismatch");
        System.out.printf("[PASS] recursive coordinate query vs independent finite domain error=%.3e%n",
                Math.abs(reference - domain));
        System.out.printf("bounded cache evaluations=%d resident=%d%n", bounded.evaluations(), bounded.cacheSize());
        costGrowth(p, dt);
        System.out.println("ALL PASS: finite-step queries only; physical horizon and production cost are undecided.");
    }

    private static double finiteReference(int steps, int x, int z, double dt, ModelParams p) {
        int pad = 2 * steps + 2, n = 2 * pad + 1;
        double[] bed = new double[n * n];
        for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) bed[i * n + j] = INPUT.bed(x + i - pad, z + j - pad);
        WaterGrid g = WaterGrid.of(n, n, 1, bed, WaterGrid.Boundary.CLOSED, 0);
        PartitionedWaterModel domain = new PartitionedWaterModel(g, p, 4);
        for (int i = 0; i < n; i++) for (int j = 0; j < n; j++) {
            domain.setDepth(i, j, INPUT.initialDepth(x + i - pad, z + j - pad));
        }
        for (int step = 0; step < steps; step++) domain.step(dt, step % 2 == 0);
        return domain.depth(pad, pad);
    }

    private static void costGrowth(ModelParams p, double dt) {
        for (int steps : new int[]{3, 6, 12, 24}) {
            CausalWaterQuery q = new CausalWaterQuery(INPUT, p, dt, 200000);
            long begin = System.nanoTime();
            double result = q.depth(steps, -7, 11);
            double oracle = finiteReference(steps, -7, 11, dt, p);
            if (Math.abs(result - oracle) > 1e-14) throw new AssertionError("cost-growth domain mismatch");
            System.out.printf("[PASS] steps=%d evaluations=%d cached=%d totalMs=%.3f physicalTime=%.6f%n",
                    steps, q.evaluations(), q.cacheSize(), (System.nanoTime() - begin) / 1e6, steps * dt);
        }
    }

    private static void requireBits(double a, double b, String name) {
        if (Double.doubleToLongBits(a) != Double.doubleToLongBits(b)) throw new AssertionError(name);
        System.out.println("[PASS] " + name);
    }
}
