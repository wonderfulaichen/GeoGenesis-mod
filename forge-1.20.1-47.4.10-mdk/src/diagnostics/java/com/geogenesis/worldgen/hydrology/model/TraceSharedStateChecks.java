package com.geogenesis.worldgen.hydrology.model;

import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Executors;

final class TraceSharedStateChecks {
    private static int checks;
    static int checks() { return checks; }

    static void run() throws Exception {
        var world = new TraceSharedStateContract.Identity(9139912035078620160L, "bed-hermite-c1/no-q/v1");
        var one = reach(world, 0, List.of(p(0, -3), p(0, 3), p(4, 5)));
        var two = reach(world, 5, List.of(p(5, -3), p(5, 3)));
        var a = new TraceSharedStateContract(world, List.of(one, two));
        var b = new TraceSharedStateContract(world, List.of(two, one));
        var join = a.join(p(-2, 0), p(7, 0));
        check(join.equals(b.join(p(-2, 0), p(7, 0))), "cold reverse declared inputs same JOIN");
        check(a.require(one.id()).equals(b.require(one.id())), "immutable receiver authority");
        check(join.suffix().equals(List.of(p(0, 0), p(0, 3), p(4, 5))), "common downstream suffix");
        var changed = new TraceSharedStateContract.Reach(one.id(), List.of(p(0, -3), p(1, 3)));
        MultiphysicsContractProbe.rejects(() -> new TraceSharedStateContract(world, List.of(one, changed)), "late conflict not overwrite");
        MultiphysicsContractProbe.rejects(() -> a.require(new TraceSharedStateContract.ReachId(world, 80, 80)), "missing receiver pending");
        var other = new TraceSharedStateContract.Identity(12345, "bed-hermite-c1/no-q/v1");
        MultiphysicsContractProbe.rejects(() -> new TraceSharedStateContract(other, List.of(one)), "seed mismatch");
        var duplicateGeometry = reach(world, -1, one.path());
        var ambiguous = new TraceSharedStateContract(world, List.of(duplicateGeometry, one));
        MultiphysicsContractProbe.rejects(() -> ambiguous.join(p(-2, 0), p(7, 0)), "ambiguous earliest receiver");
        var early = reach(world, 99, List.of(p(-1, -3), p(-1, 3)));
        var laterTie = new TraceSharedStateContract(world, List.of(duplicateGeometry, one, early));
        check(laterTie.join(p(-2, 0), p(7, 0)).receiver().equals(early.id()), "later tie cannot hide earlier hit");
        ports(world);
        var pool = Executors.newFixedThreadPool(3);
        try {
            var futures = pool.invokeAll(java.util.Collections.nCopies(3,
                    (java.util.concurrent.Callable<TraceSharedStateContract.Join>) () -> b.join(p(-2, 0), p(7, 0))));
            for (var f : futures) check(join.equals(f.get()), "three concurrent queries same suffix");
        } finally { pool.shutdownNow(); }
        Thread.currentThread().interrupt();
        try {
            a.join(p(-2, 0), p(7, 0));
            throw new AssertionError("cancel ignored");
        } catch (CancellationException expected) { checks++; }
        finally { Thread.interrupted(); }
        check(a.join(p(-2, 0), p(7, 0)).equals(join), "cancel retry no published mutation");
    }

    private static void ports(TraceSharedStateContract.Identity world) {
        TracePhysicsContract.State previous = null;
        for (int n = -2; n <= 2; n++) {
            double boundary = n * 512.0;
            var r = reach(world, n, List.of(p(boundary - 1, 5), p(boundary + 1, 5)));
            var state = new TracePhysicsContract.State(p(boundary, 5), 0.75, -0.125);
            var port = TraceSharedStateContract.port(r, 0, 0, n * 512L, 512, state);
            var received = TraceSharedStateContract.receive(port, r.id(), n, 0);
            check(received.equals(state), "seam copies position and velocity bitwise");
            if (previous != null) check(received.vx() == previous.vx(), "five seam payloads no reset");
            previous = received;
            int wrongOwner = n + 1;
            MultiphysicsContractProbe.rejects(() -> TraceSharedStateContract.receive(port, r.id(), wrongOwner, 0), "wrong owner");
        }
        var seam = reach(world, 9, List.of(p(-1, -1), p(1, 1)));
        MultiphysicsContractProbe.rejects(() -> TraceSharedStateContract.port(seam, 0, 0, 0, 512,
                new TracePhysicsContract.State(p(0, 0), 1, 1)), "corner needs explicit event split");
    }

    private static TraceSharedStateContract.Reach reach(TraceSharedStateContract.Identity w, int n,
            List<TracePathContract.Point> points) {
        return new TraceSharedStateContract.Reach(new TraceSharedStateContract.ReachId(w, n, n), points);
    }
    private static TracePathContract.Point p(double x, double z) { return new TracePathContract.Point(x, z); }
    private static void check(boolean c, String name) {
        checks++;
        if (!c) throw new AssertionError(name);
    }
    private TraceSharedStateChecks() { }
}
