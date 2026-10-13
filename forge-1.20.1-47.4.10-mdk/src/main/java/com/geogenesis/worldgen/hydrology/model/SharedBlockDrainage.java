package com.geogenesis.worldgen.hydrology.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;
import java.util.concurrent.CancellationException;
import java.util.function.BiPredicate;

/** Diagnostic fixed-domain capacity composition. No production water or basin budget is published. */
final class SharedBlockDrainage {
    enum End { CERTIFIED_SEA, SEA_CANDIDATE, UNKNOWN_FRAME }
    record Capacity(double sea, double unknown) { }
    record Trace(End end, double barrier, double unknownBound,
                 List<SharedBoundaryDrainage.WorldCell> path) {
        Trace { path = List.copyOf(path); }
    }
    private record Visit(int cell, double barrier, int distance) { }
    private record Forest(double[] barrier, int[] parent, int[] distance) { }
    private static final int[][] DIRS = {{-1, 0}, {0, -1}, {0, 1}, {1, 0}};

    static final class Domain {
        private final int ox, oz, nx, nz, step, treeEdges, parts;
        private final double seaLevel;
        private final double[] bed;
        private final byte[] graph;
        private final boolean[] ocean, covered;
        private final Forest sea, unknown;

        private Domain(int ox, int oz, int nx, int nz, int step, double seaLevel, int parts,
                       double[] bed, byte[] graph, boolean[] ocean, boolean[] covered) {
            this.ox = ox; this.oz = oz; this.nx = nx; this.nz = nz; this.step = step; this.seaLevel = seaLevel;
            this.parts = parts;
            this.bed = bed; this.graph = graph; this.ocean = ocean; this.covered = covered;
            int edges = 0;
            for (byte links : graph) edges += Integer.bitCount(Byte.toUnsignedInt(links));
            this.treeEdges = edges / 2;
            this.sea = flood(graph, bed, nx, nz, seaLevel, ocean);
            this.unknown = flood(graph, bed, nx, nz, Double.NEGATIVE_INFINITY, exposed(covered, nx, nz));
            checkForest(sea); checkForest(unknown);
        }

        int nodes() { return bed.length; }
        int treeEdges() { return treeEdges; }
        int step() { return step; }
        int parts() { return parts; }
        int originX() { return ox; }
        int originZ() { return oz; }
        int sizeX() { return nx; }
        int sizeZ() { return nz; }
        double seaLevel() { return seaLevel; }
        double bedAtIndex(int k) { return bed[k]; }
        int linksAtIndex(int k) { return Byte.toUnsignedInt(graph[k]); }
        boolean oceanAtIndex(int k) { return ocean[k]; }
        boolean coveredAtIndex(int k) { return covered[k]; }
        Capacity capacityAt(int x, int z) {
            int k = index(x, z);
            return new Capacity(sea.barrier[k], unknown.barrier[k]);
        }

        SharedBoundaryDrainage.WorldCell nextAt(int x, int z) {
            int k = index(x, z);
            int p = (sea.barrier[k] < unknown.barrier[k] ? sea : unknown).parent[k];
            return p < 0 ? null : new SharedBoundaryDrainage.WorldCell(ox + p / nz, oz + p % nz);
        }

        Trace trace(int x, int z) {
            int k = index(x, z);
            // Touching an equally good unknown frame cannot certify the downstream terminal.
            boolean certified = sea.barrier[k] < unknown.barrier[k];
            Forest forest = certified ? sea : unknown;
            double level = forest.barrier[k], bound = unknown.barrier[k];
            var path = new ArrayList<SharedBoundaryDrainage.WorldCell>();
            while (k >= 0) {
                cancelled();
                path.add(new SharedBoundaryDrainage.WorldCell(ox + k / nz, oz + k % nz));
                k = forest.parent[k];
            }
            return new Trace(certified ? End.CERTIFIED_SEA : End.UNKNOWN_FRAME, level, bound, path);
        }

        /** Known-ocean witness may exist even when an unknown boundary prevents canonical certification. */
        Trace seaWitness(int x, int z) {
            int k = index(x, z);
            if (!Double.isFinite(sea.barrier[k])) return new Trace(End.UNKNOWN_FRAME,
                    Double.POSITIVE_INFINITY, unknown.barrier[k], List.of());
            double level = sea.barrier[k], bound = unknown.barrier[k];
            var path = new ArrayList<SharedBoundaryDrainage.WorldCell>();
            while (k >= 0) {
                cancelled(); path.add(new SharedBoundaryDrainage.WorldCell(ox + k / nz, oz + k % nz));
                k = sea.parent[k];
            }
            return new Trace(level < bound ? End.CERTIFIED_SEA : End.SEA_CANDIDATE, level, bound, path);
        }

        boolean sameTopology(Domain other) {
            return ox == other.ox && oz == other.oz && nx == other.nx && nz == other.nz && step == other.step
                    && seaLevel == other.seaLevel && Arrays.equals(graph, other.graph)
                    && Arrays.equals(covered, other.covered) && Arrays.equals(ocean, other.ocean)
                    && treeEdges == other.treeEdges && Arrays.equals(bed, other.bed)
                    && Arrays.equals(sea.barrier, other.sea.barrier) && Arrays.equals(sea.parent, other.sea.parent)
                    && Arrays.equals(sea.distance, other.sea.distance)
                    && Arrays.equals(unknown.barrier, other.unknown.barrier) && Arrays.equals(unknown.parent, other.unknown.parent)
                    && Arrays.equals(unknown.distance, other.unknown.distance);
        }

        String fingerprint() {
            return ox + ":" + oz + ":" + nx + "x" + nz + ":" + Arrays.hashCode(bed) + ":"
                    + Arrays.hashCode(sea.barrier) + ":" + Arrays.hashCode(sea.parent) + ":"
                    + Arrays.hashCode(unknown.barrier) + ":" + Arrays.hashCode(unknown.parent);
        }

        private int index(int x, int z) {
            int i = x - ox, j = z - oz;
            if (i < 0 || j < 0 || i >= nx || j >= nz || !covered[i * nz + j])
                throw new IllegalArgumentException("outside covered block domain");
            return i * nz + j;
        }

        private void checkForest(Forest forest) {
            for (int k = 0; k < bed.length; k++) {
                int p = forest.parent[k];
                if (p < 0) continue;
                if (Math.abs(k / nz - p / nz) + Math.abs(k % nz - p % nz) != 1
                        || forest.barrier[p] > forest.barrier[k]
                        || (forest.barrier[p] == forest.barrier[k] && forest.distance[p] >= forest.distance[k]))
                    throw new IllegalStateException("nonprogressing capacity forest");
            }
        }
    }

    static Domain build(SharedBoundaryDrainage.Key key, int tract, int half, int step, double sea,
                        WindowOutlets.Terrain terrain, BiPredicate<Integer, Integer> certifiedOcean) {
        int along = key.alongTile() * tract + tract / 2;
        int cx = key.axis() == 0 ? key.boundary() : along;
        int cz = key.axis() == 0 ? along : key.boundary();
        return buildAt(cx, cz, half, step, sea, terrain, certifiedOcean, false);
    }

    /** The covered rectangle is the union of fixed aligned cells, never a caller-centred search. */
    static Domain buildAt(int cx, int cz, int half, int step, double sea, WindowOutlets.Terrain terrain,
                          BiPredicate<Integer, Integer> certifiedOcean, boolean reverse) {
        if (step < 2 || step > 8 || step % 2 != 0 || half <= 0 || half > 512
                || half % step != 0 || Math.floorMod(cx, step) != 0 || Math.floorMod(cz, step) != 0
                || !Double.isFinite(sea)) throw new IllegalArgumentException("fixed aligned block domain");
        int ox = cx - half - step / 2, oz = cz - half - step / 2, n = 2 * half + 1;
        double[] bed = new double[n * n];
        for (int k = 0; k < bed.length; k++) {
            cancelled();
            bed[k] = terrain.height(ox + k / n, oz + k % n);
            if (!Double.isFinite(bed[k])) throw new IllegalArgumentException("nonfinite block bed");
        }
        byte[] graph = new byte[bed.length];
        compose(graph, bed, ox, oz, n, step, reverse);
        boolean[] ocean = new boolean[bed.length];
        for (int k = 0; k < ocean.length; k++) {
            cancelled();
            ocean[k] = bed[k] < sea && certifiedOcean.test(ox + k / n, oz + k % n);
        }
        boolean[] covered = new boolean[bed.length]; Arrays.fill(covered, true);
        return new Domain(ox, oz, n, n, step, sea, 1, bed, graph, ocean, covered);
    }

    /** Bounded capacity union, not a chain of independent local parents or proof of infinite drainage. */
    static Domain combine(List<Domain> domains) {
        if (domains.isEmpty() || domains.size() > 2) throw new IllegalArgumentException("at most two fixed domains");
        int ox = Integer.MAX_VALUE, oz = Integer.MAX_VALUE, ex = Integer.MIN_VALUE, ez = Integer.MIN_VALUE;
        Domain first = domains.get(0);
        int parts = domains.stream().mapToInt(d -> d.parts).sum();
        if (parts > 2) throw new IllegalArgumentException("recursive domain expansion refused");
        for (Domain d : domains) {
            if (d.step != first.step || d.seaLevel != first.seaLevel) throw new IllegalArgumentException("domain policy mismatch");
            ox = Math.min(ox, d.ox); oz = Math.min(oz, d.oz);
            ex = Math.max(ex, d.ox + d.nx - 1); ez = Math.max(ez, d.oz + d.nz - 1);
        }
        int nx = ex - ox + 1, nz = ez - oz + 1;
        if (nx > 1217 || nz > 1217 || (long) nx * nz > 1_500_000)
            throw new IllegalArgumentException("fixed two-domain union budget");
        double[] bed = new double[nx * nz]; Arrays.fill(bed, Double.POSITIVE_INFINITY);
        byte[] graph = new byte[bed.length];
        boolean[] ocean = new boolean[bed.length], covered = new boolean[bed.length];
        for (Domain d : domains) copyDomain(d, ox, oz, nz, bed, graph, ocean, covered);
        return new Domain(ox, oz, nx, nz, first.step, first.seaLevel, parts, bed, graph, ocean, covered);
    }

    private static void copyDomain(Domain d, int ox, int oz, int nz, double[] bed, byte[] graph,
                                   boolean[] ocean, boolean[] covered) {
        for (int k = 0; k < d.bed.length; k++) {
            cancelled();
            if (!d.covered[k]) continue;
            int out = (d.ox + k / d.nz - ox) * nz + d.oz + k % d.nz - oz;
            if (covered[out] && (Double.doubleToRawLongBits(bed[out]) != Double.doubleToRawLongBits(d.bed[k])
                    || ocean[out] != d.ocean[k])) throw new IllegalArgumentException("conflicting shared world node");
            covered[out] = true; bed[out] = d.bed[k]; ocean[out] = d.ocean[k]; graph[out] |= d.graph[k];
        }
    }

    private static boolean[] exposed(boolean[] covered, int nx, int nz) {
        boolean[] result = new boolean[covered.length];
        for (int k = 0; k < covered.length; k++) {
            if (!covered[k]) continue;
            int x = k / nz, z = k % nz;
            for (int[] dir : DIRS) {
                int i = x + dir[0], j = z + dir[1];
                if (i < 0 || j < 0 || i >= nx || j >= nz || !covered[i * nz + j]) result[k] = true;
            }
        }
        return result;
    }

    private static int compose(byte[] graph, double[] bed, int ox, int oz, int n, int step, boolean reverse) {
        int count = (n - 1) / step, edgeCount = 0;
        for (int c = 0; c < count * count; c++) {
            cancelled();
            int k = reverse ? count * count - 1 - c : c;
            int x = ox + k / count * step + step / 2, z = oz + k % count * step + step / 2;
            var cell = SharedBoundaryDrainage.cellConnectivity(new SharedBoundaryDrainage.WorldCell(x, z), step,
                    (bx, bz) -> bed[((int) bx - ox) * n + (int) bz - oz]);
            for (var route : cell.routes()) {
                int a = (route.from().x() - ox) * n + route.from().z() - oz;
                int b = (route.to().x() - ox) * n + route.to().z() - oz;
                for (int d = 0; d < DIRS.length; d++) {
                    if (b - a != DIRS[d][0] * n + DIRS[d][1]) continue;
                    if ((graph[a] & (1 << d)) == 0) edgeCount++;
                    graph[a] |= (byte) (1 << d); graph[b] |= (byte) (1 << (3 - d));
                }
            }
        }
        return edgeCount;
    }

    private static Forest flood(byte[] graph, double[] bed, int nx, int nz, double floor, boolean[] seeds) {
        double[] best = new double[bed.length];
        int[] parent = new int[bed.length], distance = new int[bed.length];
        Arrays.fill(best, Double.POSITIVE_INFINITY); Arrays.fill(parent, -1); Arrays.fill(distance, Integer.MAX_VALUE);
        var queue = new PriorityQueue<Visit>(Comparator.comparingDouble(Visit::barrier)
                .thenComparingInt(Visit::distance).thenComparingInt(Visit::cell));
        for (int k = 0; k < bed.length; k++) {
            if (!seeds[k]) continue;
            best[k] = Math.max(floor, bed[k]); distance[k] = 0;
            queue.add(new Visit(k, best[k], 0));
        }
        while (!queue.isEmpty()) {
            cancelled();
            var node = queue.remove(); int k = node.cell;
            if (node.barrier != best[k] || node.distance != distance[k]) continue;
            for (int d = 0; d < DIRS.length; d++) {
                if ((graph[k] & (1 << d)) == 0) continue;
                int next = k + DIRS[d][0] * nz + DIRS[d][1];
                double barrier = Math.max(node.barrier, bed[next]); int length = node.distance + 1;
                if (barrier < best[next] || (barrier == best[next] && length < distance[next])) {
                    best[next] = barrier; distance[next] = length; parent[next] = k;
                    queue.add(new Visit(next, barrier, length));
                }
            }
        }
        return new Forest(best, parent, distance);
    }

    private static void cancelled() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("shared block drainage");
    }

    private SharedBlockDrainage() { }
}
