package com.geogenesis.worldgen.hydrology.model;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

/** Connected pre-saddle storage geometry, not a dynamic lake or a rainfall model. */
public final class BasinStorage {
    private final WaterGrid grid;
    private final List<Integer> members;
    private final double crest, bottom;
    private final boolean complete;

    public BasinStorage(WaterGrid grid, int seed, double crest) {
        if (!Double.isFinite(crest) || seed < 0 || seed >= grid.cells() || grid.bed[seed] >= crest) {
            throw new IllegalArgumentException("seed must lie below finite saddle");
        }
        this.grid = grid; this.crest = crest;
        boolean[] seen = new boolean[grid.cells()];
        ArrayDeque<Integer> queue = new ArrayDeque<>();
        ArrayList<Integer> cells = new ArrayList<>(); queue.add(seed); seen[seed] = true;
        double low = grid.bed[seed]; boolean closed = true;
        while (!queue.isEmpty()) {
            if (Thread.currentThread().isInterrupted()) throw new java.util.concurrent.CancellationException();
            int k = queue.remove(); cells.add(k); low = Math.min(low, grid.bed[k]);
            int x = grid.ix(k), z = grid.iz(k);
            if (grid.isBorder(x, z)) closed = false;
            for (int[] dir : new int[][]{{1,0}, {-1,0}, {0,1}, {0,-1}}) {
                int nx = x + dir[0], nz = z + dir[1];
                if (!grid.inside(nx, nz)) continue;
                int next = grid.idx(nx, nz);
                if (!seen[next] && grid.bed[next] < crest) { seen[next] = true; queue.add(next); }
            }
        }
        cells.sort(Integer::compare); members = List.copyOf(cells); bottom = low; complete = closed;
    }

    public boolean complete() { return complete; }
    public List<Integer> members() { return members; }
    public double crest() { return crest; }

    public record BoundaryFace(int inside, int outside, double height) { }

    /** All equally lowest boundary faces, not a selected arbitrary outlet. */
    public List<BoundaryFace> lowestBoundaryFaces() {
        if (!complete) throw new IllegalStateException("unknown exterior");
        boolean[] inside = new boolean[grid.cells()];
        for (int k : members) inside[k] = true;
        ArrayList<BoundaryFace> faces = new ArrayList<>();
        double lowest = Double.POSITIVE_INFINITY;
        for (int k : members) {
            int x = grid.ix(k), z = grid.iz(k);
            for (int[] d : new int[][]{{1,0}, {-1,0}, {0,1}, {0,-1}}) {
                int nx = x + d[0], nz = z + d[1];
                if (!grid.inside(nx, nz)) throw new IllegalStateException("unknown boundary face");
                int next = grid.idx(nx, nz);
                if (inside[next]) continue;
                double height = Math.max(grid.bed[k], grid.bed[next]);
                if (height < lowest) { faces.clear(); lowest = height; }
                if (height == lowest) faces.add(new BoundaryFace(k, next, height));
            }
        }
        return List.copyOf(faces);
    }

    /** A common water level is valid only if every wet cell belongs to one connected component. */
    public boolean connectedAt(double level) {
        boolean[] wet = new boolean[grid.cells()];
        int count = 0, start = -1;
        for (int k : members) if (grid.bed[k] < level) { wet[k] = true; start = k; count++; }
        if (count == 0) return true;
        ArrayDeque<Integer> queue = new ArrayDeque<>(); queue.add(start); wet[start] = false;
        int reached = 0;
        while (!queue.isEmpty()) {
            int k = queue.remove(); reached++;
            int x = grid.ix(k), z = grid.iz(k);
            for (int[] d : new int[][]{{1,0}, {-1,0}, {0,1}, {0,-1}}) {
                int nx = x + d[0], nz = z + d[1];
                if (!grid.inside(nx, nz)) continue;
                int next = grid.idx(nx, nz);
                if (wet[next]) { wet[next] = false; queue.add(next); }
            }
        }
        return count == reached;
    }

    public double volumeAt(double level) {
        if (!complete) throw new IllegalStateException("storage component touches unknown exterior");
        if (!Double.isFinite(level) || level > crest) throw new IllegalArgumentException("level exceeds pre-saddle geometry");
        double v = 0;
        for (int k : members) v += Math.max(0, level - grid.bed[k]) * grid.cellArea();
        return v;
    }

    /** Above-capacity volume requires transfer/merge; it must not be silently discarded. */
    public double levelForVolume(double volume) {
        if (!Double.isFinite(volume) || volume < 0 || volume > volumeAt(crest)) {
            throw new IllegalArgumentException("volume outside pre-saddle storage");
        }
        if (volume == 0) return bottom;
        double lo = bottom, hi = crest;
        for (int it = 0; it < 80; it++) {
            double mid = lo + (hi - lo) * 0.5;
            if (volumeAt(mid) < volume) lo = mid; else hi = mid;
        }
        double level = lo + (hi - lo) * 0.5;
        if (!connectedAt(level)) throw new IllegalStateException("disconnected wet sub-basins require separate water states");
        return level;
    }
}
