package com.geogenesis.worldgen.hydrology.model;

import java.util.Arrays;

/** Static minimax boundary compression; does not claim to preserve hydrodynamic flux. */
public final class BasinBoundarySummaryProbe {
    public static void main(String[] args) {
        int nx = 17, nz = 13;
        double[] bed = new double[nx * nz];
        for (int i = 0; i < nx; i++) for (int j = 0; j < nz; j++) {
            double x = i - 8, z = j - 6;
            bed[i * nz + j] = 0.03 * (x * x + z * z)
                    + 0.3 * Math.sin(i * 0.7) * Math.cos(j * 0.8);
        }
        WaterGrid g = WaterGrid.of(nx, nz, 1, bed, WaterGrid.Boundary.OPEN_AT_LEVEL, 0);
        int[] seeds = {g.idx(8, 6), g.idx(4, 5), g.idx(13, 8)};
        for (int tileSize : new int[]{3, 4, 5, 7}) {
            double[][] compressed = compress(g, tileSize, seeds);
            double error = 0;
            for (int seed : seeds) {
                double ref = ModelOracle.minimaxSpill(g, seed, 0);
                double result = solveCompressed(g, compressed, seed);
                error = Math.max(error, Math.abs(ref - result));
            }
            System.out.printf("[%s] tileSize=%d full-grid Dijkstra vs boundary elimination error=%.3e%n",
                    error == 0 ? "PASS" : "FAIL", tileSize, error);
            if (error != 0) throw new AssertionError("minimax compression lost a path");
        }
        System.out.println("ALL PASS: static finite-domain spill information only; no infinite-world or flux claim");
    }

    private static double[][] compress(WaterGrid g, int size, int[] seeds) {
        int n = g.cells();
        double[][] graph = new double[n][n];
        for (double[] row : graph) Arrays.fill(row, Double.POSITIVE_INFINITY);
        for (int a = 0; a < n; a++) {
            graph[a][a] = g.bed[a];
            int i = g.ix(a), j = g.iz(a);
            if (i + 1 < g.nx) connect(graph, a, g.idx(i + 1, j), g.bed);
            if (j + 1 < g.nz) connect(graph, a, g.idx(i, j + 1), g.bed);
        }
        boolean[] keep = new boolean[n];
        for (int i = 0; i < g.nx; i++) for (int j = 0; j < g.nz; j++) {
            int a = g.idx(i, j);
            keep[a] = g.isBorder(i, j) || i % size == 0 || j % size == 0
                    || i % size == size - 1 || j % size == size - 1;
        }
        for (int seed : seeds) keep[seed] = true;
        // Eliminate only interior cells. The min-max semiring preserves every path bottleneck.
        for (int k = 0; k < n; k++) {
            if (keep[k]) continue;
            for (int a = 0; a < n; a++) {
                if (!Double.isFinite(graph[a][k]) || a == k) continue;
                for (int b = 0; b < n; b++) {
                    if (b == k || !Double.isFinite(graph[k][b])) continue;
                    graph[a][b] = Math.min(graph[a][b], Math.max(graph[a][k], graph[k][b]));
                }
            }
            Arrays.fill(graph[k], Double.POSITIVE_INFINITY);
            for (int a = 0; a < n; a++) graph[a][k] = Double.POSITIVE_INFINITY;
        }
        return graph;
    }

    private static void connect(double[][] graph, int a, int b, double[] bed) {
        graph[a][b] = graph[b][a] = Math.max(bed[a], bed[b]);
    }

    private static double solveCompressed(WaterGrid g, double[][] graph, int seed) {
        double[] distance = new double[g.cells()];
        boolean[] settled = new boolean[g.cells()];
        Arrays.fill(distance, Double.POSITIVE_INFINITY);
        distance[seed] = g.bed[seed];
        for (int iteration = 0; iteration < g.cells(); iteration++) {
            int best = -1;
            for (int k = 0; k < distance.length; k++) {
                if (!settled[k] && (best < 0 || distance[k] < distance[best])) best = k;
            }
            if (best < 0 || !Double.isFinite(distance[best])) break;
            if (g.isBorder(g.ix(best), g.iz(best))) return Math.max(0, distance[best]);
            settled[best] = true;
            for (int k = 0; k < distance.length; k++) {
                distance[k] = Math.min(distance[k], Math.max(distance[best], graph[best][k]));
            }
        }
        return Double.POSITIVE_INFINITY;
    }
}
