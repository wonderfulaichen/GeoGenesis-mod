package com.geogenesis.worldgen.hydrology.model;

import java.util.List;
import java.util.Set;

/** 契约探针，不接入生产；每个失败都抛 AssertionError，进程非零退出。 */
public final class MultiphysicsContractProbe {
    private static int checks;

    public static void main(String[] args) throws Exception {
        height();
        gradients();
        paths();
        hits();
        spill();
        physics();
        TraceSharedStateChecks.run();
        System.out.println("PASS multiphysics-contract checks=" + checks + " shared=" + TraceSharedStateChecks.checks());
    }

    private static void height() {
        for (double step : new double[]{1, 2, 4}) {
            var plane = new TraceBedField(step, (x, z) -> 120 + 0.3 * x * step - 0.7 * z * step);
            var flat = new TraceBedField(step, (x, z) -> 50);
            var saddle = new TraceBedField(step, (x, z) -> (x * x - z * z) * step * step);
            var valley = new TraceBedField(step, (x, z) -> x * x * step * step - z * step);
            for (double x : new double[]{-7.25, -2, -0.25, 0, 0.7, 3, 4.25}) {
                double z = x * 0.37;
                var p = plane.at(x, z);
                close(p.height(), 120 + 0.3 * x - 0.7 * z, 1e-10, "plane height");
                close(p.dx(), 0.3, 1e-10, "plane dx wu");
                close(p.dz(), -0.7, 1e-10, "plane dz wu");
                close(flat.at(x, z).dx(), 0, 0, "flat no artificial slope");
                close(saddle.at(x, z).height(), x * x - z * z, 1e-10, "saddle");
                close(valley.at(x, z).dx(), 2 * x, 1e-10, "valley attracts toward floor");
            }
        }
        rejects(() -> new TraceBedField(0, (x, z) -> 1), "zero spacing");
        rejects(() -> new TraceBedField(1, (x, z) -> Double.NaN).at(0, 0), "unknown node not dry");
    }

    private static void gradients() {
        var f = new TraceBedField(2, (x, z) -> Math.sin(x * 0.31) + Math.cos(z * 0.27) + x * z * 0.01);
        for (int i = -10; i <= 10; i++) {
            double x = i * 0.73, z = i * 0.29 + 0.17, e = 1e-5;
            close(f.at(x, z).dx(), (f.at(x + e, z).height() - f.at(x - e, z).height()) / (2 * e),
                    1e-7, "height gradient same function x");
            close(f.at(x, z).dz(), (f.at(x, z + e).height() - f.at(x, z - e).height()) / (2 * e),
                    1e-7, "height gradient same function z");
            double seam = i * 2.0;
            close(f.at(seam - e, z).height(), f.at(seam + e, z).height(), 1e-5, "C0 seam");
            close(f.at(seam - e, z).dx(), f.at(seam + e, z).dx(), 1e-5, "C1 seam");
        }
    }

    private static void paths() {
        var a = point(-6, -3);
        var b = point(6, 5);
        var visits = TracePathContract.walk(a, b, 2, 128);
        for (var visit : visits) if (visit.leave() > visit.enter()) {
            double t = (visit.enter() + visit.leave()) * 0.5;
            check(visit.cell().equals(TracePathContract.cell(point(a.x() + t * (b.x() - a.x()),
                    a.z() + t * (b.z() - a.z())), 2)), "segment interior belongs to visited cell");
        }
        var corner = TracePathContract.walk(point(0, 0), point(2, 2), 2, 32);
        check(corner.stream().filter(v -> v.enter() == v.leave()).count() == 2, "corner contacts explicit");
        check(TracePathContract.cell(point(-1, -1), 2).equals(new TracePathContract.Cell(0, 0)), "negative half-open");
        rejects(() -> TracePathContract.walk(a, b, 2, 1), "DDA budget cannot report success");
        rejects(() -> TracePathContract.walk(point(0, 0), point(1, 0), 2, 1), "endpoint contact counts against budget");
        rejects(() -> TracePathContract.walk(point(0, 0), point(2, 2), 2, 3), "corner contacts count against budget");
        var route = TracePathContract.route(List.of(a, point(0, 1), b), 2, 128);
        check(route.points().equals(List.of(a, point(0, 1), b)), "final geometry is original path");
        check(TracePathContract.walk(point(-0.0, 0), point(-0.0, 0), 2, 4).size() == 1, "signed zero stationary");
        for (var kind : TracePathContract.EventKind.values()) {
            var event = TracePathContract.firstEvent(point(0, 0), point(8, 0), 2, 32,
                    c -> c.x() == 1 ? kind : null);
            check(event != null && event.kind() == kind && event.point().equals(point(1, 0)),
                    "intermediate cell event not skipped " + kind);
        }
        TracePathOracleChecks.run();
    }

    private static void hits() {
        var down = List.of(point(0, -2), point(0, 2), point(3, 3));
        check(TracePathContract.firstHit(point(0.1, -0.2), point(0.1, 0.2), down) == null,
                "same control cell is not JOIN");
        var hit = TracePathContract.firstHit(point(-2, 0), point(2, 0), down);
        check(hit != null && hit.point().equals(point(0, 0)), "actual intersection");
        check(TracePathContract.suffix(hit, down).equals(List.of(point(0, 0), point(0, 2), point(3, 3))),
                "JOIN shares exact point and suffix");
        var along = TracePathContract.firstHit(point(0, -4), point(0, 0), down);
        check(along != null && along.point().equals(point(0, -2)), "collinear first contact");
        check(TracePathContract.firstHit(point(-2, 4), point(2, 4), down) == null, "no false join");
    }

    private static void spill() {
        var start = new TracePathContract.Cell(0, 0);
        TraceSpillWitness.Bed bed = c -> c.x() == 0 && c.z() == 0 ? 0
                : c.z() == 0 && c.x() == 1 ? 1 : c.z() == 0 && c.x() == 2 ? -1 : 10;
        TraceSpillWitness.Domain domain = c -> Math.abs(c.x()) <= 4 && Math.abs(c.z()) <= 4;
        var r = TraceSpillWitness.find(start, bed, domain, Set.of(), 2, 64);
        check(r.status() == TraceSpillWitness.Status.ESCAPE, "spill escaped");
        check(r.path().equals(List.of(point(0, 0), point(2, 0), point(4, 0))), "all witness nodes not jump");
        close(r.crest(), 1, 0, "minimax crest");
        check(TracePathContract.route(r.path(), 2, 64).points().equals(r.path()), "spill geometry same witness");
        check(TraceSpillWitness.find(start, bed, domain, Set.of(), 2, 1).status()
                == TraceSpillWitness.Status.UNKNOWN_LIMIT, "exhaustion is pending not lake");
        check(TraceSpillWitness.find(start, c -> 0, domain, Set.of(), 2, 128).status()
                == TraceSpillWitness.Status.UNKNOWN_DOMAIN, "closed query window not closed basin");
    }

    private static void physics() {
        var f = new TraceBedField(2, (x, z) -> 100.0 - x * 2);
        var params = new TracePhysicsContract.Params(1, 0.08, 0.1, 0.5, 8);
        var state = new TracePhysicsContract.State(point(0, 0), 1, 0);
        for (int i = 0; i < 40; i++) {
            var r = TracePhysicsContract.step(f, state, params, 32);
            check(r.status() == TracePhysicsContract.Status.MOVE, "plane moves");
            close(r.headAfter(), r.headBefore() - r.frictionHead(), 1e-12, "discrete energy budget");
            check(r.state().point().x() > state.point().x(), "plane downhill");
            close(r.state().point().z(), 0, 0, "plane no artificial bending");
            state = r.state();
        }
        var flat = new TraceBedField(2, (x, z) -> 50);
        var stopped = TracePhysicsContract.step(flat, new TracePhysicsContract.State(point(0, 0), 0, 0), params, 32);
        check(stopped.status() == TracePhysicsContract.Status.STALLED_OR_BASIN_PENDING, "zero speed not legal terminal");
        var uphill = TracePhysicsContract.step(f, new TracePhysicsContract.State(point(0, 0), -1, 0), params, 32);
        check(uphill.status() == TracePhysicsContract.Status.UNKNOWN_LIMIT, "unapproved uphill rejected not teleported");
    }

    static TracePathContract.Point point(double x, double z) { return new TracePathContract.Point(x, z); }
    static void check(boolean condition, String name) {
        checks++;
        if (!condition) throw new AssertionError(name);
    }
    static void close(double actual, double expected, double tolerance, String name) {
        check(Double.isFinite(actual) && Math.abs(actual - expected) <= tolerance,
                name + " actual=" + actual + " expected=" + expected);
    }
    static void rejects(Runnable action, String name) {
        try { action.run(); } catch (IllegalArgumentException | IllegalStateException e) { check(true, name); return; }
        throw new AssertionError("negative control accepted: " + name);
    }
}
