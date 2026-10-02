package com.geogenesis.worldgen.hydrology.model;

import java.util.Arrays;
import java.util.PriorityQueue;

/** Finite sampled bed with explicit drainage terminals and unresolved exterior. No water is created. */
public final class LakeBasinGeometry {
    private record Node(int cell, double level) implements Comparable<Node> {
        public int compareTo(Node other) {
            int c = Double.compare(level, other.level);
            return c != 0 ? c : Integer.compare(cell, other.cell);
        }
    }
    private final WaterGrid grid;
    private final double[] escape, unknown;
    private final int[] downstream;

    public LakeBasinGeometry(WaterGrid grid, boolean[] terminals, double terminalLevel) {
        if (terminals.length != grid.cells()) throw new IllegalArgumentException("terminal mask");
        this.grid = grid;
        downstream = new int[grid.cells()];
        Arrays.fill(downstream, -1);
        escape = flood(terminals, terminalLevel, downstream, null);
        boolean[] exterior = new boolean[grid.cells()];
        for (int k = 0; k < exterior.length; k++) exterior[k] = !terminals[k] && grid.isBorder(grid.ix(k), grid.iz(k));
        unknown = flood(exterior, Double.NEGATIVE_INFINITY, null, terminals);
    }

    private double[] flood(boolean[] seeds, double level, int[] parent, boolean[] blocked) {
        double[] cost = new double[grid.cells()];
        Arrays.fill(cost, Double.POSITIVE_INFINITY);
        PriorityQueue<Node> queue = new PriorityQueue<>();
        for (int k = 0; k < cost.length; k++) if (seeds[k]) {
            cost[k] = Math.max(grid.bed[k], level);
            queue.add(new Node(k, cost[k]));
        }
        boolean[] settled = new boolean[cost.length];
        int[][] dirs = {{1, 0}, {0, 1}, {-1, 0}, {0, -1}};
        while (!queue.isEmpty()) {
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
            Node node = queue.remove();
            if (settled[node.cell] || node.level != cost[node.cell]) continue;
            settled[node.cell] = true;
            int x = grid.ix(node.cell), z = grid.iz(node.cell);
            for (int[] dir : dirs) {
                int nx = x + dir[0], nz = z + dir[1];
                if (!grid.inside(nx, nz)) continue;
                int next = grid.idx(nx, nz);
                if (blocked != null && blocked[next]) continue;
                double candidate = Math.max(node.level, grid.bed[next]);
                if (candidate < cost[next]) {
                    cost[next] = candidate;
                    if (parent != null) parent[next] = node.cell;
                    queue.add(new Node(next, candidate));
                }
            }
        }
        return cost;
    }

    /** Strict inequality excludes unknown equal-height alternative exits. */
    public boolean certified(int cell) { return Double.isFinite(escape[cell]) && escape[cell] < unknown[cell]; }
    public double escapeLevel(int cell) { return escape[cell]; }
    public double unknownExitLowerBound(int cell) { return unknown[cell]; }
    public int downstream(int cell) { return downstream[cell]; }
    public double potentialDepth(int cell) { return Math.max(0, escape[cell] - grid.bed[cell]); }
}
