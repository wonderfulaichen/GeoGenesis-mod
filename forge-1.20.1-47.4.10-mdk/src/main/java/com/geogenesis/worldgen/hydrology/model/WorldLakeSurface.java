package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.hydrology.model.WorldDrainage.Point;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CancellationException;

/** Block wetness inside one world-aligned cell; regions never define the flood boundary. */
final class WorldLakeSurface {
    static final int POLICY_VERSION = 1;
    record Plane(Point identity, double level, double sill) { }
    record Cell(int x, int z, int step, double[] levels) {
        double levelAt(int blockX, int blockZ) {
            return levels[(blockX - x) * step + blockZ - z];
        }
    }

    static Cell resolve(int x, int z, int step, Map<Point, WorldBasinFlow.Water> water,
                        WindowOutlets.Terrain terrain) {
        if (step < 1 || Math.floorMod(x, step) != 0 || Math.floorMod(z, step) != 0)
            throw new IllegalArgumentException("world-aligned lake cell required");
        var seeds = new LinkedHashMap<Plane, java.util.List<Point>>();
        var identities = new LinkedHashMap<Point, Plane>();
        for (int dx : new int[]{0, step}) for (int dz : new int[]{0, step}) {
            var p = new Point(x + dx, z + dz);
            var w = water.get(p);
            if (w == null || !w.known()) throw new IllegalStateException("missing/unknown lake corner at " + p);
            if (w.basin() == null) continue;
            var plane = new Plane(w.basin().identity(), w.level(), w.basin().sill());
            if (!Double.isFinite(plane.level()) || !(plane.level() < plane.sill()))
                throw new IllegalStateException("invalid lake plane at " + p);
            var old = identities.putIfAbsent(plane.identity(), plane);
            if (old != null && !old.equals(plane)) throw new IllegalStateException("conflicting lake plane " + p);
            seeds.computeIfAbsent(plane, k -> new java.util.ArrayList<>()).add(p);
        }
        double[] result = new double[step * step];
        Arrays.fill(result, Double.NaN);
        if (seeds.isEmpty()) return new Cell(x, z, step, result);
        int side = step + 1;
        double[] heights = new double[side * side];
        for (int dx = 0; dx <= step; dx++) for (int dz = 0; dz <= step; dz++) {
            if (Thread.currentThread().isInterrupted()) throw new CancellationException("lake cell interrupted");
            double h = terrain.height(x + dx, z + dz);
            if (!Double.isFinite(h)) throw new IllegalArgumentException("nonfinite lake terrain");
            heights[dx * side + dz] = h;
        }
        Point[] owners = new Point[result.length];
        for (var entry : seeds.entrySet()) flood(x, z, step, heights, entry.getKey(), entry.getValue(), result, owners);
        return new Cell(x, z, step, result);
    }

    private static void flood(int x, int z, int step, double[] heights, Plane plane,
                              java.util.List<Point> seeds, double[] result, Point[] owners) {
        int side = step + 1;
        boolean[] seen = new boolean[heights.length];
        var queue = new ArrayDeque<Integer>();
        for (var p : seeds) {
            int k = (p.x() - x) * side + p.z() - z;
            if (heights[k] < plane.level() - 0.5 && !seen[k]) { seen[k] = true; queue.add(k); }
        }
        while (!queue.isEmpty()) {
            if (Thread.currentThread().isInterrupted()) throw new CancellationException("lake cell interrupted");
            int k = queue.remove(), dx = k / side, dz = k % side;
            if (dx < step && dz < step) {
                int out = dx * step + dz;
                if (owners[out] != null && !owners[out].equals(plane.identity()))
                    throw new IllegalStateException("competing lake identities at " + (x + dx) + "," + (z + dz)
                            + " prior=" + owners[out] + " candidate=" + plane.identity());
                owners[out] = plane.identity(); result[out] = plane.level();
            }
            // Face-connected water cannot jump diagonally through two dry banks.
            for (int[] offset : new int[][]{{-1, 0}, {1, 0}, {0, -1}, {0, 1}}) {
                int nx = dx + offset[0], nz = dz + offset[1];
                if (nx < 0 || nz < 0 || nx > step || nz > step) continue;
                int n = nx * side + nz;
                if (!seen[n] && heights[n] < plane.level() - 0.5) { seen[n] = true; queue.add(n); }
            }
        }
    }
}
