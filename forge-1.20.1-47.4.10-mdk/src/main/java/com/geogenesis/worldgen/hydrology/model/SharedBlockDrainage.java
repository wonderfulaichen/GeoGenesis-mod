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
    record FirstTerminalCapacity(double declaredSea, double landUnknown) { }
    record FirstTerminals(java.util.Map<SharedBoundaryDrainage.WorldCell, FirstTerminalCapacity> capacities) {
        FirstTerminals { capacities = java.util.Map.copyOf(capacities); }
    }
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

    /** Conditional on the supplied ocean declarations: an unknown land path cannot cross an absorbing sea terminal. */
    static FirstTerminals firstTerminals(Domain d, List<SharedBoundaryDrainage.WorldCell> queries) {
        if (queries.size() > 128) throw new IllegalArgumentException("bounded terminal queries");
        cancelled();
        byte[] full = new byte[d.nodes()], land = new byte[d.nodes()];
        boolean[] frame = exposed(d.covered, d.nx, d.nz);
        for (int k = 0; k < d.nodes(); k++) {
            cancelled(); if (!d.covered[k]) continue;
            frame[k] &= !d.ocean[k];
            for (int dir = 0; dir < DIRS.length; dir++) {
                int x = k / d.nz + DIRS[dir][0], z = k % d.nz + DIRS[dir][1];
                if (x < 0 || z < 0 || x >= d.nx || z >= d.nz) continue;
                int next = x * d.nz + z; if (!d.covered[next]) continue;
                full[k] |= (byte) (1 << dir);
                if (!d.ocean[k] && !d.ocean[next]) land[k] |= (byte) (1 << dir);
            }
        }
        // A capacity MST preserves unrestricted minimax, not minimax after removing terminal vertices.
        var sea = flood(full, d.bed, d.nx, d.nz, d.seaLevel, d.ocean);
        var unknown = flood(land, d.bed, d.nx, d.nz, Double.NEGATIVE_INFINITY, frame);
        var result = new java.util.HashMap<SharedBoundaryDrainage.WorldCell, FirstTerminalCapacity>();
        for (var at : queries) {
            cancelled(); int k = d.index(at.x(), at.z());
            result.put(at, new FirstTerminalCapacity(sea.barrier[k], unknown.barrier[k]));
        }
        return new FirstTerminals(result);
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

    enum DirectionEnd { DOWNHILL, FLAT_ROUTE, OCEAN_CANDIDATE, BASIN_PENDING, UNKNOWN_FRAME }
    record DirectionStep(SharedBoundaryDrainage.WorldCell point, SharedBoundaryDrainage.WorldCell next,
                         double bed, double nextBed, int flatDistance, DirectionEnd state) { }
    record DirectionTrace(DirectionEnd end, List<SharedBoundaryDrainage.WorldCell> path,
                          double maxBedRise, int flatEdges) {
        DirectionTrace { path = List.copyOf(path); }
    }

    record DirectionEdgeId(SharedBoundaryDrainage.WorldCell from, SharedBoundaryDrainage.WorldCell to, int tract) { }
    record DirectionTransfer(SharedBoundaryDrainage.WorldCell from, SharedBoundaryDrainage.WorldCell to,
                             int axis, int boundary, int tract, DirectionEnd end,
                             SharedBoundaryDrainage.WorldCell terminal) {
        DirectionEdgeId id() { return new DirectionEdgeId(from, to, tract); }
    }

    enum ChannelState { SELECTED_LOCAL, INPUT_PENDING, BELOW_LOCAL_THRESHOLD }
    record ChannelCell(SharedBoundaryDrainage.WorldCell point, long contributingBlocks,
                       boolean inputPending, ChannelState state) { }
    record ChannelEdge(DirectionEdgeId id, long contributingBlocks, boolean inputPending) { }

    record ContinuationSource(SharedBoundaryDrainage.WorldCell source, SharedBoundaryDrainage.WorldCell entry) { }
    record ContinuationEdge(DirectionEdgeId id, List<SharedBoundaryDrainage.WorldCell> sources) {
        ContinuationEdge { sources = List.copyOf(sources); }
    }
    record ContinuationEnd(SharedBoundaryDrainage.WorldCell source, SharedBoundaryDrainage.WorldCell entry,
                           SharedBoundaryDrainage.WorldCell terminal, DirectionEnd state) { }
    record Continuations(List<ContinuationEdge> edges, List<ContinuationEnd> sources) {
        Continuations { edges = List.copyOf(edges); sources = List.copyOf(sources); }
    }

    /** Fixed-direction local area only, not actual discharge or a global catchment certificate. */
    static final class Channels {
        private final Direction direction;
        private final long[] area;
        private final boolean[] pending;
        private final double threshold;

        private Channels(Direction direction, long[] area, boolean[] pending, double threshold) {
            this.direction = direction; this.area = area; this.pending = pending; this.threshold = threshold;
        }

        ChannelCell at(int x, int z) {
            int k = direction.domain.index(x, z);
            ChannelState state = area[k] >= threshold ? ChannelState.SELECTED_LOCAL
                    : pending[k] ? ChannelState.INPUT_PENDING : ChannelState.BELOW_LOCAL_THRESHOLD;
            return new ChannelCell(direction.point(k), area[k], pending[k], state);
        }

        List<ChannelEdge> edges(int tract) {
            if (tract < 1) throw new IllegalArgumentException("positive tract size");
            var result = new ArrayList<ChannelEdge>();
            for (int k = 0; k < area.length; k++) {
                cancelled(); int p = direction.parent[k];
                if (p >= 0 && area[k] >= threshold) result.add(new ChannelEdge(
                        new DirectionEdgeId(direction.point(k), direction.point(p), tract), area[k], pending[k]));
            }
            return List.copyOf(result);
        }

        List<ChannelEdge> transfers(int tract) {
            return edges(tract).stream().filter(e -> Math.floorDiv(e.id.from.x(), tract) != Math.floorDiv(e.id.to.x(), tract)
                    || Math.floorDiv(e.id.from.z(), tract) != Math.floorDiv(e.id.to.z(), tract)).toList();
        }
    }

    enum BasinState { SHORE_CANDIDATE, MERGE_PENDING, UNKNOWN_FRAME }
    record BasinShore(SharedBoundaryDrainage.WorldCell inner, SharedBoundaryDrainage.WorldCell outer,
                      double sill, DirectionTrace downstream) { }
    record LocalBasin(BasinState state, SharedBoundaryDrainage.WorldCell sink,
                      List<SharedBoundaryDrainage.WorldCell> catchment,
                      List<SharedBoundaryDrainage.WorldCell> storage, double capacity,
                      List<BasinShore> lowestShores) {
        LocalBasin {
            catchment = List.copyOf(catchment); storage = List.copyOf(storage);
            lowestShores = List.copyOf(lowestShores);
        }
    }

    enum RelationEnd { NEXT_BASIN, OCEAN_CANDIDATE, OUTSIDE_SET, AMBIGUOUS_SHORE,
                       MERGE_PENDING, UNKNOWN_FRAME, SELF_RETURN, NONDESCENDING }
    record BasinRelation(SharedBoundaryDrainage.WorldCell source, SharedBoundaryDrainage.WorldCell target,
                         RelationEnd state, double sill, int shoreCount, int localMembers) { }
    record BasinRelations(List<LocalBasin> basins, List<BasinRelation> relations,
                          int assignedLocalCells, int unassignedCoveredCells) {
        BasinRelations { basins = List.copyOf(basins); relations = List.copyOf(relations); }
    }

    enum BudgetEnd { UNKNOWN_INPUT, ROUTE_PENDING, FULL_SURPLUS_CANDIDATE, FULL_NOT_SUPPORTED }
    record BasinBudget(SharedBoundaryDrainage.WorldCell sink, BudgetEnd state,
                       double landRunoff, double lakeRain, double upstreamRelease, double externalInflow,
                       double fullEvaporation, double conditionalRelease) { }
    record BasinBudgets(List<BasinBudget> basins) {
        BasinBudgets { basins = List.copyOf(basins); }
    }

    enum RangeBudgetEnd { POSITIVE_LOWER_BOUND, NO_POSITIVE_UPPER_BOUND, UNRESOLVED, ROUTE_PENDING }
    record RateRange(double lower, double upper) {
        RateRange {
            if (!Double.isFinite(lower) || lower < 0 || Double.isNaN(upper) || upper < lower)
                throw new IllegalArgumentException("nonnegative finite lower and ordered upper rate");
        }
    }
    record BasinBudgetRange(SharedBoundaryDrainage.WorldCell sink, RangeBudgetEnd state,
                           double landRunoff, double lakeRain, RateRange upstream, RateRange external,
                           double fullEvaporation, RateRange conditionalRelease) { }
    record BasinBudgetRanges(List<BasinBudgetRange> basins) {
        BasinBudgetRanges { basins = List.copyOf(basins); }
    }

    /** Diagnostic direction on the FULL four-neighbour terrain, independent of capacity-tree edges. */
    static final class Direction {
        private final Domain domain;
        private final int[] parent, distance;
        private final byte[] state;

        private Direction(Domain domain, int[] parent, int[] distance, byte[] state) {
            this.domain = domain; this.parent = parent; this.distance = distance; this.state = state;
        }

        DirectionStep at(int x, int z) {
            int k = domain.index(x, z), p = parent[k];
            return new DirectionStep(point(k), p < 0 ? null : point(p), domain.bed[k],
                    p < 0 ? domain.bed[k] : domain.bed[p], distance[k], DirectionEnd.values()[state[k]]);
        }

        DirectionTrace trace(int x, int z) {
            int k = domain.index(x, z), flats = 0;
            double rise = 0;
            var path = new ArrayList<SharedBoundaryDrainage.WorldCell>();
            while (true) {
                cancelled(); path.add(point(k));
                int p = parent[k];
                if (p < 0) return new DirectionTrace(DirectionEnd.values()[state[k]], path, rise, flats);
                if (path.size() > parent.length) throw new IllegalStateException("direction cycle");
                rise = Math.max(rise, domain.bed[p] - domain.bed[k]);
                if (domain.bed[p] == domain.bed[k]) flats++;
                k = p;
            }
        }

        boolean sameDirection(Direction other) {
            return domain.ox == other.domain.ox && domain.oz == other.domain.oz
                    && domain.nx == other.domain.nx && domain.nz == other.domain.nz
                    && Arrays.equals(parent, other.parent) && Arrays.equals(distance, other.distance)
                    && Arrays.equals(state, other.state);
        }

        /** All surface drainage edges, NOT channel-selected river ports or water-surface certificates. */
        List<DirectionTransfer> transfers(int tract) {
            if (tract < 1) throw new IllegalArgumentException("positive tract size");
            int[] terminal = directionTerminals();
            var result = new ArrayList<DirectionTransfer>();
            for (int k = 0; k < parent.length; k++) {
                cancelled(); int p = parent[k]; if (p < 0) continue;
                var from = point(k); var to = point(p);
                if (Math.floorDiv(from.x(), tract) == Math.floorDiv(to.x(), tract)
                        && Math.floorDiv(from.z(), tract) == Math.floorDiv(to.z(), tract)) continue;
                int axis = from.x() == to.x() ? 1 : 0;
                int boundary = axis == 0 ? Math.max(from.x(), to.x()) : Math.max(from.z(), to.z());
                int end = terminal[p];
                result.add(new DirectionTransfer(from, to, axis, boundary, tract,
                        DirectionEnd.values()[state[end]], point(end)));
            }
            return List.copyOf(result);
        }

        private int[] directionTerminals() {
            int[] terminal = new int[parent.length], chain = new int[parent.length]; Arrays.fill(terminal, -1);
            for (int start = 0; start < parent.length; start++) {
                cancelled(); if (!domain.covered[start] || terminal[start] >= 0) continue;
                int k = start, size = 0;
                while (terminal[k] < 0 && parent[k] >= 0) {
                    cancelled(); if (size >= parent.length) throw new IllegalStateException("direction cycle");
                    chain[size++] = k; k = parent[k];
                }
                int end = terminal[k] < 0 ? k : terminal[k]; terminal[k] = end;
                for (int i = 0; i < size; i++) terminal[chain[i]] = end;
            }
            return terminal;
        }

        /** Threshold is block area (production channelMinCells * cellBlocks^2), NOT local-window Q. */
        Channels channels(double thresholdBlocks) {
            if (!Double.isFinite(thresholdBlocks) || thresholdBlocks <= 0)
                throw new IllegalArgumentException("positive finite channel area");
            int[] indegree = new int[parent.length], queue = new int[parent.length];
            long[] area = new long[parent.length];
            boolean[] pending = exposed(domain.covered, domain.nx, domain.nz);
            int covered = 0, size = 0;
            for (int k = 0; k < parent.length; k++) {
                cancelled(); if (!domain.covered[k]) continue;
                covered++; area[k] = domain.ocean[k] ? 0 : 1;
                if (parent[k] >= 0) indegree[parent[k]]++;
            }
            // An exposed flat has no published exit; adjacent inputs must not be called complete.
            boolean[] frame = pending.clone();
            for (int k = 0; k < parent.length; k++)
                if (domain.covered[k] && state[k] == DirectionEnd.UNKNOWN_FRAME.ordinal()) frame[k] = pending[k] = true;
            for (int k = 0; k < parent.length; k++) {
                cancelled(); if (!frame[k]) continue;
                for (int dir = 0; dir < DIRS.length; dir++) {
                    int p = neighbor(domain, k, dir); if (p >= 0) pending[p] = true;
                }
            }
            for (int k = 0; k < parent.length; k++) if (domain.covered[k] && indegree[k] == 0) queue[size++] = k;
            for (int head = 0; head < size; head++) {
                cancelled(); int k = queue[head], p = parent[k]; if (p < 0) continue;
                area[p] += area[k]; pending[p] |= pending[k];
                if (--indegree[p] == 0) queue[size++] = p;
            }
            if (size != covered) throw new IllegalStateException("channel contribution cycle");
            return new Channels(this, area, pending, thresholdBlocks);
        }

        /** Already-selected source obligations on THIS graph, not source selection, Q or generated water. */
        Continuations continueSources(List<ContinuationSource> requested, int tract) {
            if (requested.size() > 32 || tract < 1) throw new IllegalArgumentException("at most 32 sources and positive tract");
            var order = Comparator.comparingInt(SharedBoundaryDrainage.WorldCell::x).thenComparingInt(SharedBoundaryDrainage.WorldCell::z);
            var entries = new java.util.TreeMap<SharedBoundaryDrainage.WorldCell, SharedBoundaryDrainage.WorldCell>(order);
            for (var source : requested) {
                cancelled();
                if (entries.containsKey(source.source) && !java.util.Objects.equals(entries.get(source.source), source.entry))
                    throw new IllegalArgumentException("conflicting source entry");
                entries.put(source.source, source.entry);
            }
            var origins = new ArrayList<>(entries.keySet());
            long[] support = new long[parent.length];
            int[] starts = new int[origins.size()]; Arrays.fill(starts, -1);
            for (int i = 0; i < origins.size(); i++) {
                cancelled(); var entry = entries.get(origins.get(i)); if (entry == null) continue;
                int x = entry.x() - domain.ox, z = entry.z() - domain.oz;
                if (x < 0 || z < 0 || x >= domain.nx || z >= domain.nz || !domain.covered[x * domain.nz + z]) continue;
                int k = starts[i] = x * domain.nz + z;
                for (int steps = 0; ; steps++) {
                    cancelled(); if (steps >= parent.length) throw new IllegalStateException("continuation cycle");
                    support[k] |= 1L << i; if (parent[k] < 0) break; k = parent[k];
                }
            }
            var edges = new ArrayList<ContinuationEdge>();
            for (int k = 0; k < parent.length; k++) {
                cancelled(); if (support[k] == 0 || parent[k] < 0) continue;
                var ids = new ArrayList<SharedBoundaryDrainage.WorldCell>();
                for (int i = 0; i < origins.size(); i++) if ((support[k] & (1L << i)) != 0) ids.add(origins.get(i));
                edges.add(new ContinuationEdge(new DirectionEdgeId(point(k), point(parent[k]), tract), ids));
            }
            int[] terminals = directionTerminals(); var ends = new ArrayList<ContinuationEnd>();
            for (int i = 0; i < origins.size(); i++) {
                cancelled(); int k = starts[i], end = k < 0 ? -1 : terminals[k];
                ends.add(new ContinuationEnd(origins.get(i), entries.get(origins.get(i)), end < 0 ? null : point(end),
                        end < 0 ? DirectionEnd.UNKNOWN_FRAME : DirectionEnd.values()[state[end]]));
            }
            return new Continuations(edges, ends);
        }

        /** Explicit bounded set only: local cell ownership is not global inflow or a water budget. */
        BasinRelations relations(List<SharedBoundaryDrainage.WorldCell> sinks) {
            if (sinks.isEmpty() || sinks.size() > 32) throw new IllegalArgumentException("one to 32 explicit sinks");
            var roots = new java.util.TreeSet<Integer>();
            for (var sink : sinks) { cancelled(); roots.add(canonicalSink(domain.index(sink.x(), sink.z()))); }
            var basins = new ArrayList<LocalBasin>();
            for (int root : roots) basins.add(basinAt(domain.ox + root / domain.nz, domain.oz + root % domain.nz));
            int[] owner = new int[parent.length]; Arrays.fill(owner, -1);
            int assigned = 0, covered = 0;
            for (boolean cell : domain.covered) if (cell) covered++;
            for (int i = 0; i < basins.size(); i++) for (var cell : basins.get(i).catchment) {
                cancelled(); int k = domain.index(cell.x(), cell.z());
                if (owner[k] >= 0) throw new IllegalStateException("local runoff member counted twice");
                owner[k] = i; assigned++;
            }
            var result = new ArrayList<BasinRelation>();
            for (int i = 0; i < basins.size(); i++) result.add(relation(i, basins, owner));
            return new BasinRelations(basins, result, assigned, covered - assigned);
        }

        private int canonicalSink(int sink) {
            if (parent[sink] >= 0 || state[sink] != DirectionEnd.BASIN_PENDING.ordinal())
                throw new IllegalArgumentException("pending basin sink required");
            boolean[] seen = new boolean[parent.length]; int[] queue = new int[parent.length];
            int size = 1, root = sink; seen[sink] = true; queue[0] = sink;
            for (int head = 0; head < size; head++) {
                cancelled(); int k = queue[head]; root = Math.min(root, k);
                for (int dir = 0; dir < DIRS.length; dir++) {
                    int p = neighbor(domain, k, dir);
                    if (p >= 0 && !seen[p] && parent[p] < 0 && state[p] == state[sink] && domain.bed[p] == domain.bed[sink]) {
                        seen[p] = true; queue[size++] = p;
                    }
                }
            }
            return root;
        }

        private BasinRelation relation(int source, List<LocalBasin> basins, int[] owner) {
            cancelled(); var basin = basins.get(source);
            double sill = basin.lowestShores.isEmpty() ? Double.NaN : basin.lowestShores.get(0).sill;
            RelationEnd end; SharedBoundaryDrainage.WorldCell target = null;
            if (basin.state == BasinState.UNKNOWN_FRAME) end = RelationEnd.UNKNOWN_FRAME;
            else if (basin.state == BasinState.MERGE_PENDING) end = RelationEnd.MERGE_PENDING;
            else if (basin.lowestShores.size() != 1) end = RelationEnd.AMBIGUOUS_SHORE;
            else {
                var trace = basin.lowestShores.get(0).downstream;
                target = trace.path.get(trace.path.size() - 1);
                boolean returns = false;
                for (var cell : trace.path) { cancelled(); returns |= owner[domain.index(cell.x(), cell.z())] == source; }
                if (returns) end = RelationEnd.SELF_RETURN;
                else if (trace.end == DirectionEnd.UNKNOWN_FRAME) end = RelationEnd.UNKNOWN_FRAME;
                else if (trace.end == DirectionEnd.OCEAN_CANDIDATE) end = RelationEnd.OCEAN_CANDIDATE;
                else {
                    target = point(canonicalSink(domain.index(target.x(), target.z())));
                    int downstream = -1;
                    for (int i = 0; i < basins.size(); i++) if (basins.get(i).sink.equals(target)) downstream = i;
                    if (downstream < 0) end = RelationEnd.OUTSIDE_SET;
                    else {
                        var next = basins.get(downstream); target = next.sink;
                        if (next.state == BasinState.UNKNOWN_FRAME) end = RelationEnd.UNKNOWN_FRAME;
                        else if (next.state == BasinState.MERGE_PENDING) end = RelationEnd.MERGE_PENDING;
                        else end = next.lowestShores.get(0).sill < sill ? RelationEnd.NEXT_BASIN : RelationEnd.NONDESCENDING;
                    }
                }
            }
            return new BasinRelation(basin.sink, target, end, sill, basin.lowestShores.size(), basin.catchment.size());
        }

        /** Local catchment is NOT the published lake mask, nor proof of complete external inflow. */
        LocalBasin basinAt(int x, int z) {
            int sink = domain.index(x, z);
            if (parent[sink] >= 0 || state[sink] != DirectionEnd.BASIN_PENDING.ordinal())
                throw new IllegalArgumentException("pending basin sink required");
            boolean[] member = catchment(sink);
            double[] access = basinAccess(sink, member);
            var members = new ArrayList<SharedBoundaryDrainage.WorldCell>();
            boolean truncated = false;
            double sill = Double.POSITIVE_INFINITY, ambiguous = Double.POSITIVE_INFINITY;
            var pairs = new ArrayList<int[]>();
            for (int k = 0; k < member.length; k++) {
                cancelled(); if (!member[k]) continue;
                members.add(point(k));
                for (int dir = 0; dir < DIRS.length; dir++) {
                    int p = neighbor(domain, k, dir);
                    if (p < 0) { truncated = true; continue; }
                    if (member[p]) continue;
                    double barrier = Math.max(access[k], domain.bed[p]);
                    if (state[p] == DirectionEnd.UNKNOWN_FRAME.ordinal() && domain.bed[p] >= domain.bed[k])
                        ambiguous = Math.min(ambiguous, barrier);
                    if (barrier < sill) { sill = barrier; pairs.clear(); }
                    if (barrier == sill) pairs.add(new int[]{k, p});
                }
            }
            if (truncated || pairs.isEmpty() || ambiguous <= sill) return new LocalBasin(BasinState.UNKNOWN_FRAME,
                    point(sink), List.of(), List.of(), Double.NaN, List.of());
            var shores = new ArrayList<BasinShore>();
            for (int[] pair : pairs) {
                var downstream = trace(domain.ox + pair[1] / domain.nz, domain.oz + pair[1] % domain.nz);
                if (downstream.path.contains(point(sink))) throw new IllegalStateException("shore returned to same catchment");
                shores.add(new BasinShore(point(pair[0]), point(pair[1]), sill, downstream));
            }
            return storageAt(sink, sill, member, members, shores);
        }

        private double[] basinAccess(int sink, boolean[] member) {
            double[] best = new double[parent.length]; Arrays.fill(best, Double.POSITIVE_INFINITY);
            var queue = new PriorityQueue<Visit>(Comparator.comparingDouble(Visit::barrier).thenComparingInt(Visit::cell));
            best[sink] = domain.bed[sink]; queue.add(new Visit(sink, best[sink], 0));
            while (!queue.isEmpty()) {
                cancelled(); var visit = queue.remove(); int k = visit.cell;
                if (visit.barrier != best[k]) continue;
                for (int dir = 0; dir < DIRS.length; dir++) {
                    int p = neighbor(domain, k, dir);
                    if (p < 0 || !member[p]) continue;
                    double barrier = Math.max(best[k], domain.bed[p]);
                    if (barrier < best[p]) { best[p] = barrier; queue.add(new Visit(p, barrier, 0)); }
                }
            }
            return best;
        }

        private boolean[] catchment(int sink) {
            boolean[] member = new boolean[parent.length];
            int[] queue = new int[parent.length]; int size = 1;
            member[sink] = true; queue[0] = sink;
            for (int head = 0; head < size; head++) {
                cancelled(); int k = queue[head];
                for (int dir = 0; dir < DIRS.length; dir++) {
                    int p = neighbor(domain, k, dir);
                    boolean sameSinkFlat = p >= 0 && domain.bed[k] == domain.bed[sink] && parent[p] < 0
                            && state[p] == DirectionEnd.BASIN_PENDING.ordinal() && domain.bed[p] == domain.bed[sink];
                    if (p >= 0 && !member[p] && (parent[p] == k || sameSinkFlat)) {
                        member[p] = true; queue[size++] = p;
                    }
                }
            }
            return member;
        }

        /** At the lowest local saddle, a connected submerged neighbour basin requires merge authority. */
        private LocalBasin storageAt(int sink, double sill, boolean[] catchment,
                                     List<SharedBoundaryDrainage.WorldCell> members, List<BasinShore> shores) {
            boolean[] visited = new boolean[parent.length];
            int[] queue = new int[parent.length]; int size = 0;
            if (domain.bed[sink] < sill) { queue[size++] = sink; visited[sink] = true; }
            var storage = new ArrayList<SharedBoundaryDrainage.WorldCell>();
            boolean merge = false, exposed = false;
            for (int head = 0; head < size; head++) {
                cancelled(); int k = queue[head];
                storage.add(point(k));
                merge |= !catchment[k];
                for (int dir = 0; dir < DIRS.length; dir++) {
                    int p = neighbor(domain, k, dir);
                    if (p < 0) { exposed = true; continue; }
                    if (!visited[p] && domain.bed[p] < sill) { visited[p] = true; queue[size++] = p; }
                }
            }
            storage.sort(Comparator.comparingInt(SharedBoundaryDrainage.WorldCell::x)
                    .thenComparingInt(SharedBoundaryDrainage.WorldCell::z));
            double volume = 0;
            for (var c : storage) { cancelled(); volume += sill - domain.bed[domain.index(c.x(), c.z())]; }
            if (exposed) return new LocalBasin(BasinState.UNKNOWN_FRAME, point(sink), List.of(), List.of(), Double.NaN, List.of());
            return new LocalBasin(merge ? BasinState.MERGE_PENDING : BasinState.SHORE_CANDIDATE,
                    point(sink), members, storage, volume, shores);
        }

        private SharedBoundaryDrainage.WorldCell point(int k) {
            return new SharedBoundaryDrainage.WorldCell(domain.ox + k / domain.nz, domain.oz + k % domain.nz);
        }
    }

    private record FlatExit(int inner, int outer, boolean exposed) { }

    static Direction direction(Domain d, boolean reverse) {
        int[] parent = new int[d.nodes()], distance = new int[d.nodes()], queue = new int[d.nodes()];
        byte[] state = new byte[d.nodes()];
        boolean[] seen = new boolean[d.nodes()];
        Arrays.fill(parent, -1); Arrays.fill(distance, -1);
        for (int i = 0; i < d.nodes(); i++) {
            cancelled(); int k = reverse ? d.nodes() - 1 - i : i;
            if (!d.covered[k] || seen[k]) continue;
            if (d.ocean[k]) {
                seen[k] = true; distance[k] = 0; state[k] = (byte) DirectionEnd.OCEAN_CANDIDATE.ordinal();
                continue;
            }
            int size = collectFlat(d, k, queue, seen);
            FlatExit exit = flatExit(d, queue, size);
            if (exit.exposed || exit.outer < 0) {
                byte end = (byte) (exit.exposed ? DirectionEnd.UNKNOWN_FRAME : DirectionEnd.BASIN_PENDING).ordinal();
                for (int j = 0; j < size; j++) state[queue[j]] = end;
            } else {
                orientFlat(d, exit, queue, parent, distance, state);
            }
        }
        validateDirection(d, parent, distance);
        return new Direction(d, parent, distance, state);
    }

    /** Diagnostic two-step preference, but every world block keeps ONE cardinal downstream edge. */
    static Direction directionWithLookahead(Domain d, boolean reverse) {
        Direction base = direction(d, reverse);
        int[] parent = base.parent.clone();
        for (int i = 0; i < d.nodes(); i++) {
            cancelled(); int k = reverse ? d.nodes() - 1 - i : i;
            if (!d.covered[k] || base.parent[k] < 0 || base.state[k] != DirectionEnd.DOWNHILL.ordinal()) continue;
            double best = d.bed[k] - d.bed[parent[k]];
            int selected = parent[k], selectedTarget = Integer.MAX_VALUE;
            for (int dx : new int[]{-1, 1}) for (int dz : new int[]{-1, 1}) {
                int x = k / d.nz + dx, z = k % d.nz + dz;
                if (x < 0 || z < 0 || x >= d.nx || z >= d.nz) continue;
                int target = x * d.nz + z;
                if (!d.covered[target]) continue;
                double score = (d.bed[k] - d.bed[target]) / Math.sqrt(2);
                if (score < best) continue;
                int a = x * d.nz + k % d.nz, b = k / d.nz * d.nz + z;
                for (int middle : new int[]{Math.min(a, b), Math.max(a, b)}) {
                    if (!d.covered[middle] || d.bed[middle] >= d.bed[k] || d.bed[middle] < d.bed[target]) continue;
                    if (score > best || (score == best && selectedTarget != Integer.MAX_VALUE
                            && (middle < selected || (middle == selected && target < selectedTarget)))) {
                        best = score; selected = middle; selectedTarget = target;
                    }
                }
            }
            parent[k] = selected;
        }
        validateDirection(d, parent, base.distance);
        return new Direction(d, parent, base.distance.clone(), base.state.clone());
    }

    private static int neighbor(Domain d, int k, int direction) {
        int x = k / d.nz + DIRS[direction][0], z = k % d.nz + DIRS[direction][1];
        if (x < 0 || z < 0 || x >= d.nx || z >= d.nz) return -1;
        int p = x * d.nz + z;
        return d.covered[p] ? p : -1;
    }

    private static int collectFlat(Domain d, int start, int[] queue, boolean[] seen) {
        int size = 1; queue[0] = start; seen[start] = true;
        for (int head = 0; head < size; head++) {
            cancelled(); int k = queue[head];
            for (int dir = 0; dir < DIRS.length; dir++) {
                int p = neighbor(d, k, dir);
                if (p >= 0 && !seen[p] && !d.ocean[p] && d.bed[p] == d.bed[start]) {
                    seen[p] = true; queue[size++] = p;
                }
            }
        }
        return size;
    }

    private static FlatExit flatExit(Domain d, int[] members, int size) {
        int inner = -1, outer = -1; boolean exposed = false;
        double drop = -1;
        for (int i = 0; i < size; i++) {
            cancelled(); int k = members[i];
            for (int dir = 0; dir < DIRS.length; dir++) {
                int p = neighbor(d, k, dir);
                if (p < 0) { exposed = true; continue; }
                double delta = d.bed[k] - d.bed[p];
                if (delta < 0 || (delta == 0 && !d.ocean[p])) continue;
                if (delta > drop || (delta == drop && (k < inner || (k == inner && p < outer)))) {
                    inner = k; outer = p; drop = delta;
                }
            }
        }
        return new FlatExit(inner, outer, exposed);
    }

    /** Only a completely sampled equal-bed component can select a canonical local exit. */
    private static void orientFlat(Domain d, FlatExit exit, int[] queue, int[] parent,
                                   int[] distance, byte[] state) {
        int size = 1; queue[0] = exit.inner;
        parent[exit.inner] = exit.outer;
        distance[exit.inner] = d.bed[exit.inner] == d.bed[exit.outer] ? 1 : 0;
        state[exit.inner] = (byte) (distance[exit.inner] == 0 ? DirectionEnd.DOWNHILL : DirectionEnd.FLAT_ROUTE).ordinal();
        for (int head = 0; head < size; head++) {
            cancelled(); int k = queue[head];
            for (int dir = 0; dir < DIRS.length; dir++) {
                int p = neighbor(d, k, dir);
                if (p < 0 || d.ocean[p] || distance[p] >= 0 || d.bed[p] != d.bed[k]) continue;
                parent[p] = k; distance[p] = distance[k] + 1;
                state[p] = (byte) DirectionEnd.FLAT_ROUTE.ordinal(); queue[size++] = p;
            }
        }
    }

    private static void validateDirection(Domain d, int[] parent, int[] distance) {
        for (int k = 0; k < parent.length; k++) {
            cancelled(); int p = parent[k];
            if (p < 0) continue;
            if (Math.abs(k / d.nz - p / d.nz) + Math.abs(k % d.nz - p % d.nz) != 1
                    || d.bed[p] > d.bed[k] || (d.bed[p] == d.bed[k] && distance[p] >= distance[k]))
                throw new IllegalStateException("nonprogressing terrain direction");
        }
    }

    /** Conditional FULL-basin steady-state ledger, not a level solver or published overflow. NaN = unknown inflow. */
    static BasinBudgets fullBudgets(BasinRelations graph, LakeWaterBalance.Climate climate,
            java.util.function.ToDoubleFunction<SharedBoundaryDrainage.WorldCell> externalInflow) {
        var ranges = fullBudgetRanges(graph, climate, sink -> {
            double value = externalInflow.applyAsDouble(sink);
            if (!Double.isNaN(value) && !Double.isFinite(value)) throw new IllegalArgumentException("finite or unknown inflow");
            return Double.isNaN(value) ? new RateRange(0, Double.POSITIVE_INFINITY) : new RateRange(value, value);
        });
        var result = new ArrayList<BasinBudget>();
        for (var range : ranges.basins) {
            boolean exact = range.upstream.lower == range.upstream.upper && range.external.lower == range.external.upper;
            BudgetEnd state = range.state == RangeBudgetEnd.ROUTE_PENDING ? BudgetEnd.ROUTE_PENDING
                    : !exact ? BudgetEnd.UNKNOWN_INPUT : range.state == RangeBudgetEnd.POSITIVE_LOWER_BOUND
                    ? BudgetEnd.FULL_SURPLUS_CANDIDATE : BudgetEnd.FULL_NOT_SUPPORTED;
            double release = exact && state != BudgetEnd.ROUTE_PENDING ? range.conditionalRelease.lower : Double.NaN;
            result.add(new BasinBudget(range.sink, state, range.landRunoff, range.lakeRain,
                    range.upstream.lower == range.upstream.upper ? range.upstream.lower : Double.NaN,
                    range.external.lower == range.external.upper ? range.external.lower : Double.NaN,
                    range.fullEvaporation, release));
        }
        return new BasinBudgets(result);
    }

    /** Bounds hold only for the supplied fixed topology, constant climate and FULL storage footprint. */
    static BasinBudgetRanges fullBudgetRanges(BasinRelations graph, LakeWaterBalance.Climate climate,
            java.util.function.Function<SharedBoundaryDrainage.WorldCell, RateRange> externalInflow) {
        if (!Double.isFinite(climate.precipitation()) || climate.precipitation() < 0
                || !Double.isFinite(climate.evaporation()) || climate.evaporation() < 0
                || !Double.isFinite(climate.runoffCoeff()) || climate.runoffCoeff() < 0 || climate.runoffCoeff() > 1)
            throw new IllegalArgumentException("finite nonnegative climate rates and runoff coefficient <=1");
        int n = graph.basins.size();
        double[] lower = new double[n], upper = new double[n];
        BasinBudgetRange[] budgets = new BasinBudgetRange[n];
        var indices = new ArrayList<Integer>();
        for (int i = 0; i < n; i++) {
            indices.add(i); var relation = graph.relations.get(i);
            if (relation.state != RelationEnd.NEXT_BASIN && relation.state != RelationEnd.OCEAN_CANDIDATE)
                for (int j = 0; j < n; j++) if (graph.basins.get(j).sink.equals(relation.target)) upper[j] = Double.POSITIVE_INFINITY;
        }
        indices.sort(Comparator.<Integer>comparingDouble(i -> graph.relations.get(i).sill).reversed().thenComparingInt(i -> i));
        for (int i : indices) {
            cancelled(); var basin = graph.basins.get(i); var relation = graph.relations.get(i);
            var external = externalInflow.apply(basin.sink); var upstream = new RateRange(lower[i], upper[i]);
            boolean route = relation.state == RelationEnd.NEXT_BASIN || relation.state == RelationEnd.OCEAN_CANDIDATE;
            double land = Double.NaN, rain = Double.NaN, evaporation = Double.NaN;
            RateRange release = new RateRange(0, Double.POSITIVE_INFINITY);
            RangeBudgetEnd state = RangeBudgetEnd.ROUTE_PENDING;
            if (basin.state == BasinState.SHORE_CANDIDATE) {
                land = (basin.catchment.size() - basin.storage.size()) * climate.precipitation() * climate.runoffCoeff();
                rain = basin.storage.size() * climate.precipitation(); evaporation = basin.storage.size() * climate.evaporation();
                if (route) {
                    release = new RateRange(Math.max(0, land + rain + upstream.lower + external.lower - evaporation),
                            Math.max(0, land + rain + upstream.upper + external.upper - evaporation));
                    state = release.lower > 0 ? RangeBudgetEnd.POSITIVE_LOWER_BOUND : release.upper == 0
                            ? RangeBudgetEnd.NO_POSITIVE_UPPER_BOUND : RangeBudgetEnd.UNRESOLVED;
                }
            }
            budgets[i] = new BasinBudgetRange(basin.sink, state, land, rain, upstream, external, evaporation, release);
            if (relation.state == RelationEnd.NEXT_BASIN)
                for (int j = 0; j < n; j++) if (graph.basins.get(j).sink.equals(relation.target)) {
                    lower[j] += release.lower; upper[j] += release.upper;
                }
        }
        return new BasinBudgetRanges(Arrays.asList(budgets));
    }

    private static void cancelled() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("shared block drainage");
    }

    private SharedBlockDrainage() { }
}
