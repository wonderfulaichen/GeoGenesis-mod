package com.geogenesis.worldgen.hydrology.model;

import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/** Finite-domain basin ownership and truncation counterexamples. No production routing. */
public final class BasinOwnershipProbe {
    private record Summary(int seed, int cells, double volume, double rain) { }

    public static void main(String[] args) {
        int nx = 41, nz = 15;
        double[] bed = new double[nx * nz];
        for (int x = 0; x < nx; x++) for (int z = 0; z < nz; z++) {
            bed[x * nz + z] = Math.min(
                    0.015 * ((x - 10) * (x - 10) + (z - 7) * (z - 7)),
                    0.015 * ((x - 30) * (x - 30) + (z - 7) * (z - 7)));
        }
        WaterGrid g = WaterGrid.of(nx, nz, 1, bed, WaterGrid.Boundary.CLOSED, 0);
        // Above the saddle: both depressions belong to one connected water body.
        double level = 1.8;
        Summary oracle = oracle(g, level, 0.003);
        for (int size : new int[]{3, 5, 8, 11}) for (boolean reverse : new boolean[]{false, true}) {
            Summary result = partitioned(g, level, 0.003, size, reverse);
            require(result.seed == oracle.seed && result.cells == oracle.cells,
                    "one global basin owner across tile sizes and visitation orders");
            require(Math.abs(result.volume - oracle.volume) < 1e-10,
                    "capacity counted only by owned cells, never halo cells");
            require(Math.abs(result.rain - oracle.rain) < 1e-10,
                    "basin rain counted exactly once");
        }
        System.out.printf("[PASS] joined basin owner=%d cells=%d volume=%.9f rain=%.9f%n",
                oracle.seed, oracle.cells, oracle.volume, oracle.rain);
        truncationCounterexample();
        farUpstreamLedger();
        System.out.println("ALL PASS: finite basin summaries and counterexamples; infinite-world closure is not proven.");
    }

    private static Summary oracle(WaterGrid g, double level, double rain) {
        boolean[] seen = new boolean[g.cells()];
        int seed = -1;
        for (int k = 0; k < g.cells(); k++) if (g.bed[k] < level) { seed = k; break; }
        if (seed < 0) throw new AssertionError("empty basin");
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        queue.add(seed); seen[seed] = true;
        int cells = 0;
        double volume = 0;
        while (!queue.isEmpty()) {
            int k = queue.remove();
            cells++; volume += (level - g.bed[k]) * g.cellArea();
            int x = g.ix(k), z = g.iz(k);
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                int xx = x + d[0], zz = z + d[1];
                if (!g.inside(xx, zz)) continue;
                int next = g.idx(xx, zz);
                if (!seen[next] && g.bed[next] < level) { seen[next] = true; queue.add(next); }
            }
        }
        int allWet = 0;
        for (double h : g.bed) if (h < level) allWet++;
        require(cells == allWet, "oracle fixture has one connected wet component");
        return new Summary(seed, cells, volume, rain * cells * g.cellArea());
    }

    private static Summary partitioned(WaterGrid g, double level, double rain, int size, boolean reverse) {
        int[] parent = new int[g.cells()];
        Arrays.fill(parent, -1);
        int tx = (g.nx + size - 1) / size, tz = (g.nz + size - 1) / size;
        // Each tile creates only its own cells. No duplicated halo storage or source.
        for (int order = 0; order < tx * tz; order++) {
            int tile = reverse ? tx * tz - 1 - order : order;
            int x0 = (tile / tz) * size, z0 = (tile % tz) * size;
            for (int x = x0; x < Math.min(g.nx, x0 + size); x++) {
                for (int z = z0; z < Math.min(g.nz, z0 + size); z++) {
                    int k = g.idx(x, z);
                    if (g.bed[k] < level) parent[k] = k;
                }
            }
        }
        // Canonical cross-owner adjacency merge; minimum global cell index is owner identity.
        for (int k = 0; k < parent.length; k++) {
            if (parent[k] < 0) continue;
            int x = g.ix(k), z = g.iz(k);
            if (x + 1 < g.nx) join(parent, k, g.idx(x + 1, z));
            if (z + 1 < g.nz) join(parent, k, g.idx(x, z + 1));
        }
        Map<Integer, double[]> sums = new HashMap<>();
        for (int k = 0; k < parent.length; k++) {
            if (parent[k] < 0) continue;
            double[] sum = sums.computeIfAbsent(find(parent, k), ignored -> new double[3]);
            sum[0]++; sum[1] += (level - g.bed[k]) * g.cellArea();
            sum[2] += rain * g.cellArea();
        }
        require(sums.size() == 1, "cross-tile wet components merged into one basin");
        Map.Entry<Integer, double[]> result = sums.entrySet().iterator().next();
        double[] sum = result.getValue();
        return new Summary(result.getKey(), (int) sum[0], sum[1], sum[2]);
    }

    private static int find(int[] parent, int k) {
        while (parent[k] != k) { parent[k] = parent[parent[k]]; k = parent[k]; }
        return k;
    }

    private static void join(int[] parent, int a, int b) {
        if (parent[b] < 0) return;
        int aa = find(parent, a), bb = find(parent, b);
        parent[Math.max(aa, bb)] = Math.min(aa, bb);
    }

    private static void truncationCounterexample() {
        int nx = 41, nz = 5;
        double[] bed = new double[nx * nz];
        Arrays.fill(bed, 10);
        for (int x = 1; x < nx - 1; x++) bed[x * nz + 2] = 1;
        bed[(nx - 1) * nz + 2] = 3; // True outlet is far outside the cropped query.
        WaterGrid full = WaterGrid.of(nx, nz, 1, bed, WaterGrid.Boundary.OPEN_AT_LEVEL, 0);
        double correct = ModelOracle.minimaxSpill(full, full.idx(4, 2), 0);
        int cutNx = 11;
        double[] cropped = Arrays.copyOf(bed, cutNx * nz);
        WaterGrid cut = WaterGrid.of(cutNx, nz, 1, cropped, WaterGrid.Boundary.OPEN_AT_LEVEL, 0);
        double local = ModelOracle.minimaxSpill(cut, cut.idx(4, 2), 0);
        require(correct == 3 && local == 1, "cropped edge is an artificial outlet");
        System.out.printf("[PASS] truncation counterexample: full spill=%.1f cropped spill=%.1f -> local result must be pending%n",
                correct, local);
    }

    private static void farUpstreamLedger() {
        int tiles = 12, tileCells = 8;
        double rain = 0.003, cellArea = 4;
        double accumulated = 0;
        for (int t = 0; t < tiles; t++) accumulated += rain * cellArea * tileCells;
        double truncated = rain * cellArea * tileCells * 3;
        require(accumulated > truncated, "upstream outside two rings cannot be silently omitted");
        System.out.printf("[PASS] analytic no-loss river: 12 tile input=%.9f two-ring input=%.9f missing=%.9f%n",
                accumulated, truncated, accumulated - truncated);
    }

    private static void require(boolean pass, String name) {
        if (!pass) throw new AssertionError(name);
    }
}
