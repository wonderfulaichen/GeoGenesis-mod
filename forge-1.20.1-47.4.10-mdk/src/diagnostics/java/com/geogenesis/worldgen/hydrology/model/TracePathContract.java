package com.geogenesis.worldgen.hydrology.model;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;

/** 连续路径是权威；格是世界对齐格心 ±step/2 的半开控制体。零长 visit 只表示接触。 */
final class TracePathContract {
    record Point(double x, double z) {
        Point {
            if (!Double.isFinite(x) || !Double.isFinite(z)) throw new IllegalArgumentException("nonfinite point");
        }
    }
    record Cell(int x, int z) { }
    record Visit(Cell cell, double enter, double leave) { }
    record Hit(int segment, double along, Point point) { }
    enum EventKind { LAKE, SEA, EXISTING_RIVER, BOUNDARY, INPUT_PENDING }
    interface Events { EventKind at(Cell cell); }
    record Event(EventKind kind, Visit visit, Point point) { }
    record Route(List<Point> points, List<Visit> visits) {
        Route { points = List.copyOf(points); visits = List.copyOf(visits); }
    }

    private TracePathContract() { }

    static Cell cell(Point p, double step) {
        if (!(step > 0) || !Double.isFinite(step)) throw new IllegalArgumentException("bad spacing");
        double x = Math.floor(p.x / step + 0.5), z = Math.floor(p.z / step + 0.5);
        if (x <= Integer.MIN_VALUE || x >= Integer.MAX_VALUE || z <= Integer.MIN_VALUE || z >= Integer.MAX_VALUE)
            throw new IllegalArgumentException("outside cell domain");
        return new Cell((int) x, (int) z);
    }

    static List<Visit> walk(Point a, Point b, double step, int budget) {
        Cell start = cell(a, step), end = cell(b, step);
        int x = start.x, z = start.z;
        double dx = b.x - a.x, dz = b.z - a.z, t = 0;
        int sx = dx == 0 ? 0 : dx > 0 ? 1 : -1;
        int sz = dz == 0 ? 0 : dz > 0 ? 1 : -1;
        var out = new ArrayList<Visit>();
        while (true) {
            checkBudget(budget - out.size());
            double tx = sx == 0 ? Double.POSITIVE_INFINITY : ((x + sx * 0.5) * step - a.x) / dx;
            double tz = sz == 0 ? Double.POSITIVE_INFINITY : ((z + sz * 0.5) * step - a.z) / dz;
            double next = Math.min(1, Math.min(tx, tz));
            if (next < t) throw new IllegalStateException("nonmonotone DDA");
            out.add(new Visit(new Cell(x, z), t, next));
            if (next == 1) {
                if (x != end.x || z != end.z) out.add(new Visit(end, 1, 1));
                if (out.size() > budget) throw new IllegalStateException("visit budget exhausted");
                return List.copyOf(out);
            }
            if (tx == tz) {
                // 同时穿角：两侧格仅接触，不声称在其内部经过。
                out.add(new Visit(new Cell(x + sx, z), next, next));
                out.add(new Visit(new Cell(x, z + sz), next, next));
            }
            if (tx <= tz) x += sx;
            if (tz <= tx) z += sz;
            t = next;
        }
    }

    static Route route(List<Point> points, double step, int budget) {
        if (points.isEmpty()) throw new IllegalArgumentException("empty route");
        var visits = new ArrayList<Visit>();
        for (int i = 1; i < points.size(); i++) {
            List<Visit> part = walk(points.get(i - 1), points.get(i), step, budget - visits.size());
            visits.addAll(part);
            if (visits.size() > budget) throw new IllegalStateException("path budget exhausted");
        }
        return new Route(points, visits);
    }

    /** 格事件只是候选，EXISTING_RIVER 仍须 firstHit 的实际相交认证。 */
    static Event firstEvent(Point a, Point b, double step, int budget, Events events) {
        Event result = null;
        for (Visit visit : walk(a, b, step, budget)) {
            EventKind kind = events.at(visit.cell);
            if (kind == null) continue;
            if (result != null && visit.enter > result.visit.enter) break;
            if (result != null && kind != result.kind)
                throw new IllegalStateException("simultaneous incompatible events; unresolved");
            if (result == null) result = new Event(kind, visit,
                    new Point(a.x + visit.enter * (b.x - a.x), a.z + visit.enter * (b.z - a.z)));
        }
        return result;
    }

    /** 仅真实线段相交才 JOIN；同格、近格不构成汇流。重叠时取最早接触点。 */
    static Hit firstHit(Point a, Point b, List<Point> downstream) {
        Hit first = null;
        for (int i = 1; i < downstream.size(); i++) {
            if (Thread.currentThread().isInterrupted()) throw new CancellationException();
            Point c = downstream.get(i - 1), d = downstream.get(i);
            double rx = b.x - a.x, rz = b.z - a.z, sx = d.x - c.x, sz = d.z - c.z;
            double den = cross(rx, rz, sx, sz), qx = c.x - a.x, qz = c.z - a.z;
            double t;
            if (den != 0) {
                t = cross(qx, qz, sx, sz) / den;
                double u = cross(qx, qz, rx, rz) / den;
                if (t < 0 || t > 1 || u < 0 || u > 1) continue;
            } else {
                double rr = rx * rx + rz * rz;
                if (cross(qx, qz, rx, rz) != 0 || rr == 0) continue;
                double tc = (qx * rx + qz * rz) / rr;
                double td = ((d.x - a.x) * rx + (d.z - a.z) * rz) / rr;
                t = Math.max(0, Math.min(tc, td));
                if (t > Math.min(1, Math.max(tc, td))) continue;
            }
            if (first == null || t < first.along)
                first = new Hit(i - 1, t, new Point(a.x + t * rx, a.z + t * rz));
        }
        return first;
    }

    static List<Point> suffix(Hit hit, List<Point> downstream) {
        var result = new ArrayList<Point>();
        result.add(hit.point);
        for (int i = hit.segment + 1; i < downstream.size(); i++)
            if (!result.get(result.size() - 1).equals(downstream.get(i))) result.add(downstream.get(i));
        return List.copyOf(result);
    }

    static void checkBudget(int remaining) {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException();
        if (remaining <= 0) throw new IllegalStateException("path budget exhausted; not a dry terminal");
    }

    private static double cross(double ax, double az, double bx, double bz) { return ax * bz - az * bx; }
}
