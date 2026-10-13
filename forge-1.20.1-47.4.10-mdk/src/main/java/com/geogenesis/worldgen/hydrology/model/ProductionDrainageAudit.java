package com.geogenesis.worldgen.hydrology.model;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Read-only evidence from the same traces and shaped segments used by columnsFor. */
final class ProductionDrainageAudit {
    enum Terminal { SEA, CLOSED_LAKE, EXTERNAL, UNRESOLVED, BROKEN_JOIN, MISSING_OUTLET, CYCLE }
    record Region(int x, int z) { }
    record Point(double x, double z) { }
    record SegmentId(Region region, int ordinal) { }
    record Crossing(SegmentId segment, int axis, int boundary, Point point,
                    Point tangent, Region upstream, Region downstream) { }
    record Segment(SegmentId id, Point source, Point mouth, DeterministicSkeleton.End end,
                   boolean fromOutlet, int downstreamSegment, int lake, boolean frame,
                   double maxGeometryGap) { }
    record Lake(int id, Point seed, Point outlet, double level, double sill, double inflow,
                double evaporation, double outflow, boolean truncated, int outletSegment) { }
    record Chain(SegmentId source, Terminal terminal, int lakes, int segments) { }
    record Snapshot(Region region, List<Segment> segments, List<Lake> lakes,
                    List<Crossing> crossings, List<Chain> chains, boolean filtered) { }

    private ProductionDrainageAudit() { }

    static Snapshot capture(HydrologyPipeline.Result p, DeterministicSkeleton.Result sk,
                            List<DeterministicSkeleton.Shaped> shaped, int ox, int oz, int tract) {
        int n = p.nx(), step = p.cellBlocks();
        Region region = new Region(Math.floorDiv(ox + (n / 2) * step, tract),
                Math.floorDiv(oz + (n / 2) * step, tract));
        Map<Integer, Integer> owner = new HashMap<>(), starts = new HashMap<>();
        for (int t = 0; t < sk.traces.size(); t++) {
            starts.putIfAbsent(sk.traces.get(t).sourceCell(), t);
            for (int c : sk.traces.get(t).cell) owner.putIfAbsent(c, t);
        }
        List<Lake> lakes = lakeNodes(p, ox, oz, starts);
        List<Segment> segments = new ArrayList<>();
        List<Crossing> crossings = new ArrayList<>();
        for (int t = 0; t < sk.traces.size(); t++) {
            var trace = sk.traces.get(t);
            var shape = shaped.get(t);
            SegmentId id = new SegmentId(region, t);
            int mouth = trace.mouthCell(), x = mouth / n, z = mouth % n;
            int join = owner.getOrDefault(mouth, -1);
            if (join == t) join = -1;
            int lake = p.lakeResult() == null ? -1 : LakeWaterBalance.basinOf(p.lakeResult(), mouth);
            double maxGap = 0;
            for (int i = 1; i < shape.size(); i++) {
                Point a = new Point(shape.x[i - 1], shape.z[i - 1]);
                Point b = new Point(shape.x[i], shape.z[i]);
                maxGap = Math.max(maxGap, Math.hypot(b.x - a.x, b.z - a.z));
                for (var crossing : crossings(id, a, b, tract)) {
                    // A vertex on the seam may touch and turn back without crossing.
                    int next = i + 1;
                    Point after = b;
                    while (next < shape.size()) {
                        after = new Point(shape.x[next], shape.z[next]);
                        double coordinate = crossing.axis == 0 ? after.x : after.z;
                        if (coordinate != crossing.boundary) break;
                        next++;
                    }
                    double bv = crossing.axis == 0 ? b.x : b.z;
                    double afterV = crossing.axis == 0 ? after.x : after.z;
                    double direction = crossing.axis == 0 ? crossing.tangent.x : crossing.tangent.z;
                    if (bv == crossing.boundary && (afterV - crossing.boundary) * direction <= 0) continue;
                    crossings.add(crossing);
                }
            }
            var end = trace.end;
            if (end == DeterministicSkeleton.End.JOIN && p.sea()[mouth])
                end = DeterministicSkeleton.End.MOUTH_SEA;
            if (end == DeterministicSkeleton.End.JOIN && p.lake()[mouth])
                end = DeterministicSkeleton.End.MOUTH_LAKE;
            segments.add(new Segment(id, gridPoint(trace.sourceCell(), n, step, ox, oz),
                    gridPoint(mouth, n, step, ox, oz), end, trace.fromOutlet, join, lake,
                    x == 0 || z == 0 || x == n - 1 || z == n - 1, maxGap));
        }
        List<Chain> chains = new ArrayList<>();
        for (var segment : segments) chains.add(follow(segment.id, segments, lakes));
        return new Snapshot(region, List.copyOf(segments), List.copyOf(lakes),
                List.copyOf(crossings), List.copyOf(chains), sk.networkFilterEnabled);
    }

    private static List<Lake> lakeNodes(HydrologyPipeline.Result p, int ox, int oz,
                                        Map<Integer, Integer> starts) {
        List<Lake> result = new ArrayList<>();
        var lr = p.lakeResult();
        if (lr == null) return result;
        for (int i = 0; i < lr.basins().size(); i++) {
            var b = lr.basins().get(i);
            int o = LakeWaterBalance.outletOf(lr, i);
            result.add(new Lake(i, gridPoint(b.seedCell(), p.nx(), p.cellBlocks(), ox, oz),
                    o < 0 ? null : gridPoint(o, p.nx(), p.cellBlocks(), ox, oz), b.level(),
                    b.sillLevel(), b.inflow(), b.evaporationOut(), b.outflow(),
                    b.catchmentTruncated(), starts.getOrDefault(o, -1)));
        }
        return result;
    }

    static Chain follow(SegmentId source, List<Segment> segments, List<Lake> lakes) {
        Set<Integer> seenSegments = new HashSet<>(), seenLakes = new HashSet<>();
        int t = source.ordinal;
        while (t >= 0 && t < segments.size()) {
            if (!seenSegments.add(t)) return chain(source, Terminal.CYCLE, seenLakes, seenSegments);
            Segment segment = segments.get(t);
            switch (segment.end) {
                case MOUTH_SEA -> { return chain(source, Terminal.SEA, seenLakes, seenSegments); }
                case UNRESOLVED -> { return chain(source, Terminal.UNRESOLVED, seenLakes, seenSegments); }
                case DEAD_END -> { return chain(source, segment.frame ? Terminal.EXTERNAL : Terminal.UNRESOLVED,
                        seenLakes, seenSegments); }
                case JOIN -> {
                    t = segment.downstreamSegment;
                    if (t < 0) return chain(source, Terminal.BROKEN_JOIN, seenLakes, seenSegments);
                }
                case MOUTH_LAKE -> {
                    int b = segment.lake;
                    if (b < 0 || b >= lakes.size())
                        return chain(source, Terminal.UNRESOLVED, seenLakes, seenSegments);
                    if (!seenLakes.add(b)) return chain(source, Terminal.CYCLE, seenLakes, seenSegments);
                    Lake lake = lakes.get(b);
                    if (lake.truncated) return chain(source, Terminal.UNRESOLVED, seenLakes, seenSegments);
                    if (lake.outflow <= 0 && lake.inflow <= lake.evaporation + 1e-9)
                        return chain(source, Terminal.CLOSED_LAKE, seenLakes, seenSegments);
                    if (!(lake.outflow > 0) || lake.level + 1e-9 < lake.sill || lake.outletSegment < 0)
                        return chain(source, Terminal.MISSING_OUTLET, seenLakes, seenSegments);
                    t = lake.outletSegment;
                }
            }
        }
        return chain(source, Terminal.BROKEN_JOIN, seenLakes, seenSegments);
    }

    private static Chain chain(SegmentId id, Terminal end, Set<Integer> lakes, Set<Integer> segments) {
        return new Chain(id, end, lakes.size(), segments.size());
    }

    private static Point gridPoint(int cell, int n, int step, int ox, int oz) {
        return new Point(ox + (cell / n) * (double) step, oz + (cell % n) * (double) step);
    }

    static List<Crossing> crossings(SegmentId id, Point a, Point b, int tract) {
        List<Crossing> result = new ArrayList<>();
        for (int axis = 0; axis < 2; axis++) {
            double av = axis == 0 ? a.x : a.z, bv = axis == 0 ? b.x : b.z;
            if (av == bv) continue;
            int first = (int) Math.ceil(Math.min(av, bv) / tract);
            int last = (int) Math.floor(Math.max(av, bv) / tract);
            for (int tile = first; tile <= last; tile++) {
                int boundary = tile * tract;
                double fraction = (boundary - av) / (bv - av);
                // Half-open segments: a seam vertex belongs to the incoming edge, once.
                if (fraction <= 0 || fraction > 1) continue;
                Point point = new Point(a.x + fraction * (b.x - a.x), a.z + fraction * (b.z - a.z));
                double length = Math.hypot(b.x - a.x, b.z - a.z);
                Point tangent = new Point((b.x - a.x) / length, (b.z - a.z) / length);
                double epsilon = 1e-6;
                Region upstream = owner(point.x - epsilon * tangent.x, point.z - epsilon * tangent.z, tract);
                Region downstream = owner(point.x + epsilon * tangent.x, point.z + epsilon * tangent.z, tract);
                result.add(new Crossing(id, axis, boundary, point, tangent, upstream, downstream));
            }
        }
        return List.copyOf(result);
    }

    private static Region owner(double x, double z, int tract) {
        return new Region((int) Math.floor(x / tract), (int) Math.floor(z / tract));
    }
}
