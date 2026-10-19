package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.hydrology.model.WorldDrainage.Point;
import java.util.*;
import java.util.concurrent.CancellationException;

/** The same bounded integer-column passage is used for hydraulic tests and block geometry. */
final class WorldChannelGeometry {
    record Passage(double crest, List<Point> path) {
        Passage { path = List.copyOf(path); }
    }
    private record Visit(int index, double crest) { }
    private static final Comparator<Point> ORDER = Comparator.comparingInt(Point::x).thenComparingInt(Point::z);
    private final int step;
    private final WindowOutlets.Terrain terrain;

    WorldChannelGeometry(int step, WindowOutlets.Terrain terrain) {
        this.step = step; this.terrain = terrain;
    }

    Passage between(Point from, Point to) {
        if (ORDER.compare(from, to) > 0) {
            var p = between(to, from);
            var reversed = new ArrayList<>(p.path()); Collections.reverse(reversed);
            return new Passage(p.crest(), reversed);
        }
        int dx = to.x() - from.x(), dz = to.z() - from.z();
        if ((dx == 0 && dz == 0) || Math.abs(dx) > step || Math.abs(dz) > step
                || Math.floorMod(dx, step) != 0 || Math.floorMod(dz, step) != 0)
            throw new IllegalArgumentException("adjacent world nodes required");
        int x = Math.min(from.x(), to.x()), z = Math.min(from.z(), to.z());
        var first = solve(x, z, from, to);
        if (dx == 0) return better(first, solve(x - step, z, from, to));
        if (dz == 0) return better(first, solve(x, z - step, from, to));
        return first;
    }

    private Passage solve(int ox, int oz, Point from, Point to) {
        int side = step + 1, size = side * side;
        double[] heights = new double[size], costs = new double[size];
        int[] parent = new int[size];
        Arrays.fill(costs, Double.POSITIVE_INFINITY); Arrays.fill(parent, -1);
        for (int x = 0; x <= step; x++) for (int z = 0; z <= step; z++) {
            cancelled(); double h = terrain.height(ox + x, oz + z);
            if (!Double.isFinite(h)) throw new IllegalArgumentException("nonfinite channel terrain");
            heights[x * side + z] = h;
        }
        int start = (from.x() - ox) * side + from.z() - oz;
        int end = (to.x() - ox) * side + to.z() - oz;
        var queue = new PriorityQueue<Visit>(Comparator.comparingDouble(Visit::crest)
                .thenComparingInt(Visit::index));
        costs[start] = heights[start];
        queue.add(new Visit(start, heights[start]));
        while (!queue.isEmpty()) {
            cancelled(); var v = queue.remove(); int i = v.index();
            if (v.crest() != costs[i]) continue;
            if (i == end) break;
            for (int n : neighbours(i, side)) {
                if (n < 0) continue;
                double crest = Math.max(v.crest(), heights[n]);
                if (crest < costs[n]) {
                    costs[n] = crest;
                    queue.add(new Visit(n, crest));
                }
            }
        }
        // A shorter higher-prefix path can become optimal after a later bottleneck.
        // Resolve length only after the final minimax crest has been established.
        var breadth = new ArrayDeque<Integer>();
        boolean[] seen = new boolean[size]; seen[start] = true; breadth.add(start);
        while (!breadth.isEmpty()) {
            cancelled(); int i = breadth.remove();
            if (i == end) break;
            for (int n : neighbours(i, side)) if (n >= 0 && !seen[n] && heights[n] <= costs[end]) {
                seen[n] = true; parent[n] = i; breadth.add(n);
            }
        }
        var path = new ArrayList<Point>();
        for (int i = end; i != -1; i = parent[i]) path.add(new Point(ox + i / side, oz + i % side));
        Collections.reverse(path); return new Passage(costs[end], path);
    }

    private static int[] neighbours(int index, int side) {
        int x = index / side, z = index % side;
        return new int[]{x > 0 ? index - side : -1, z > 0 ? index - 1 : -1,
                z + 1 < side ? index + 1 : -1, x + 1 < side ? index + side : -1};
    }

    private static Passage better(Passage a, Passage b) {
        int cmp = Double.compare(a.crest(), b.crest());
        if (cmp != 0) return cmp < 0 ? a : b;
        if (a.path().size() != b.path().size()) return a.path().size() < b.path().size() ? a : b;
        for (int i = 0; i < a.path().size(); i++) {
            cmp = ORDER.compare(a.path().get(i), b.path().get(i));
            if (cmp != 0) return cmp < 0 ? a : b;
        }
        return a;
    }

    private static void cancelled() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("channel geometry interrupted");
    }
}
