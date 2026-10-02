package com.geogenesis.worldgen.hydrology.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;

/** Lower-star terrain connectivity tree. Equal-height connected plateaus are processed together. */
public final class BasinMergeTree {
    public record Saddle(double level, List<Integer> plateau, List<Integer> childMinima,
                         boolean touchesUnknownBoundary) {
        public Saddle { plateau = List.copyOf(plateau); childMinima = List.copyOf(childMinima); }
    }
    private final WaterGrid grid;
    private final int[] parent, minimum;
    private final boolean[] boundary;
    private final List<Saddle> saddles = new ArrayList<>();

    public BasinMergeTree(WaterGrid grid) {
        this.grid = grid;
        parent = new int[grid.cells()]; minimum = new int[grid.cells()];
        boundary = new boolean[grid.cells()]; Arrays.fill(parent, -1);
        Integer[] order = new Integer[grid.cells()];
        for (int k = 0; k < order.length; k++) order[k] = k;
        Arrays.sort(order, (a, b) -> {
            int c = Double.compare(grid.bed[a], grid.bed[b]); return c == 0 ? Integer.compare(a, b) : c;
        });
        for (int from = 0; from < order.length;) {
            int to = from + 1;
            while (to < order.length && grid.bed[order[to]] == grid.bed[order[from]]) to++;
            processLevel(order, from, to);
            from = to;
        }
    }

    private void processLevel(Integer[] order, int from, int to) {
        if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
        java.util.HashSet<Integer> remaining = new java.util.HashSet<>();
        for (int p = from; p < to; p++) remaining.add(order[p]);
        for (int p = from; p < to; p++) {
            int start = order[p]; if (!remaining.remove(start)) continue;
            ArrayList<Integer> plateau = new ArrayList<>();
            java.util.ArrayDeque<Integer> queue = new java.util.ArrayDeque<>(); queue.add(start);
            TreeSet<Integer> lower = new TreeSet<>(); boolean edge = false;
            while (!queue.isEmpty()) {
                int k = queue.remove(); plateau.add(k);
                edge |= grid.isBorder(grid.ix(k), grid.iz(k));
                for (int next : neighbors(k)) {
                    if (grid.bed[next] == grid.bed[start] && remaining.remove(next)) queue.add(next);
                    else if (grid.bed[next] < grid.bed[start]) lower.add(root(next));
                }
            }
            // Earlier plateaus at the same level may have united lower components.
            TreeSet<Integer> roots = new TreeSet<>();
            for (int r : lower) roots.add(root(r));
            ArrayList<Integer> minima = new ArrayList<>();
            for (int r : roots) { minima.add(minimum[r]); edge |= boundary[r]; }
            minima.sort(Integer::compare); plateau.sort(Integer::compare);
            if (roots.size() >= 2) saddles.add(new Saddle(grid.bed[start], plateau, minima, edge));
            int owner = plateau.get(0);
            parent[owner] = owner; minimum[owner] = owner; boundary[owner] = edge;
            for (int k : plateau) parent[k] = owner;
            for (int r : roots) {
                if (grid.bed[minimum[r]] < grid.bed[minimum[owner]]
                        || (grid.bed[minimum[r]] == grid.bed[minimum[owner]] && minimum[r] < minimum[owner])) {
                    minimum[owner] = minimum[r];
                }
                parent[r] = owner;
            }
        }
    }

    private int root(int k) {
        int r = k; while (parent[r] != r) r = parent[r];
        while (k != r) { int next = parent[k]; parent[k] = r; k = next; }
        return r;
    }

    private int[] neighbors(int k) {
        int x = grid.ix(k), z = grid.iz(k); int[] list = new int[4]; int count = 0;
        if (x > 0) list[count++] = grid.idx(x - 1, z);
        if (z > 0) list[count++] = grid.idx(x, z - 1);
        if (x + 1 < grid.nx) list[count++] = grid.idx(x + 1, z);
        if (z + 1 < grid.nz) list[count++] = grid.idx(x, z + 1);
        return Arrays.copyOf(list, count);
    }

    public List<Saddle> saddles() { return List.copyOf(saddles); }
}
