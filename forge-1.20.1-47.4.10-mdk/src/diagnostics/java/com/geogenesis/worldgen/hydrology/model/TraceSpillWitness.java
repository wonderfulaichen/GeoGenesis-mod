package com.geogenesis.worldgen.hydrology.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;

/** 隔离的有界 minimax 逃逸见证；返回整条路径，耗尽绝不充当内流湖证书。 */
final class TraceSpillWitness {
    interface Bed { double height(TracePathContract.Cell cell); }
    interface Domain { boolean contains(TracePathContract.Cell cell); }
    enum Status { ESCAPE, UNKNOWN_LIMIT, UNKNOWN_DOMAIN }
    record Result(Status status, java.util.List<TracePathContract.Point> path, double crest, int expanded) {
        Result { path = java.util.List.copyOf(path); }
    }
    private record Node(TracePathContract.Cell cell, double crest) { }

    static Result find(TracePathContract.Cell start, Bed bed, Domain domain,
                       Set<TracePathContract.Cell> forbidden, double step, int budget) {
        if (!(step > 0) || !Double.isFinite(step) || budget < 1 || !domain.contains(start))
            throw new IllegalArgumentException("invalid spill query");
        var queue = new PriorityQueue<Node>(java.util.Comparator.comparingDouble(Node::crest)
                .thenComparingInt(n -> n.cell.x()).thenComparingInt(n -> n.cell.z()));
        Map<TracePathContract.Cell, Double> best = new HashMap<>();
        Map<TracePathContract.Cell, TracePathContract.Cell> parent = new HashMap<>();
        double floor = height(bed, start);
        best.put(start, floor);
        queue.add(new Node(start, floor));
        int expanded = 0;
        while (!queue.isEmpty()) {
            TracePathContract.checkBudget(Integer.MAX_VALUE);
            Node node = queue.remove();
            if (Double.compare(node.crest, best.get(node.cell)) != 0) continue;
            if (expanded++ >= budget) return new Result(Status.UNKNOWN_LIMIT, java.util.List.of(), Double.NaN, expanded - 1);
            if (!node.cell.equals(start) && height(bed, node.cell) < floor)
                return new Result(Status.ESCAPE, path(node.cell, parent, step), node.crest, expanded);
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                var next = new TracePathContract.Cell(node.cell.x() + dx, node.cell.z() + dz);
                if (forbidden.contains(next)) continue;
                if (!domain.contains(next)) return new Result(Status.UNKNOWN_DOMAIN, java.util.List.of(), Double.NaN, expanded);
                double crest = Math.max(node.crest, height(bed, next));
                if (crest < best.getOrDefault(next, Double.POSITIVE_INFINITY)) {
                    best.put(next, crest);
                    parent.put(next, node.cell);
                    queue.add(new Node(next, crest));
                }
            }
        }
        return new Result(Status.UNKNOWN_DOMAIN, java.util.List.of(), Double.NaN, expanded);
    }

    private static double height(Bed bed, TracePathContract.Cell cell) {
        double h = bed.height(cell);
        if (!Double.isFinite(h)) throw new IllegalArgumentException("unknown spill bed");
        return h;
    }

    private static java.util.List<TracePathContract.Point> path(TracePathContract.Cell end,
            Map<TracePathContract.Cell, TracePathContract.Cell> parent, double step) {
        var points = new ArrayList<TracePathContract.Point>();
        for (var c = end; c != null; c = parent.get(c))
            points.add(new TracePathContract.Point(c.x() * step, c.z() * step));
        Collections.reverse(points);
        return java.util.List.copyOf(points);
    }

    private TraceSpillWitness() { }
}
