package com.geogenesis.worldgen.hydrology.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import com.geogenesis.worldgen.hydrology.model.DeterministicSkeleton.CoreBounds;
import com.geogenesis.worldgen.hydrology.model.DeterministicSkeleton.End;
import com.geogenesis.worldgen.hydrology.model.DeterministicSkeleton.OutsideContinuation;
import com.geogenesis.worldgen.hydrology.model.DeterministicSkeleton.Trace;

/**
 * Independent source-component admission contract, NOT a hydrological connectivity certificate.
 *
 * <p>Expected survivors are named explicitly by each fixture. This probe contains no component
 * search, reachability oracle, union-find, or reimplementation of the pruning predicate. Shared
 * cells intentionally protect a whole group even when they do not prove a directed water mouth.
 * Ports are real BoundaryPort.of objects, with a frame anchor and an actual next point outside.
 * No fixture treats a port as a lake/sea certificate or asks this method to prune short rivers.</p>
 *
 * <p>Every input permutation runs with both mutable and immutable lists, checking trace/port
 * identity, relative order, all array references and contents, untouched inputs and idempotence.
 * Compile/run against the main thread's candidate; this source does not install an implementation.
 * All failures, including linkage/setup failures, exit 1.</p>
 */
public final class HaloComponentProbe {
    private static final int NX = 19;
    private static final int NZ = 13;
    private static int passed;
    private static int failed;
    private static int permutations;
    private static int calls;

    private record Env(OutsideContinuation outside, CoreBounds core) { }

    private record Case(String name, List<Trace> input, List<Trace> kept,
                        Set<Integer> incoming, OutsideContinuation outside, CoreBounds core) { }

    private record Snapshot(Trace trace, int[] cellRef, int[] cells,
                            double[] tailRef, double[] tail, double[] pathRef, double[] path,
                            End end, boolean outlet, int prefix, BoundaryPort port) {
        Snapshot(Trace t) {
            this(t, t.cell, t.cell.clone(), t.outsideTail, t.outsideTail.clone(),
                    t.contPath, t.contPath.clone(), t.end, t.fromOutlet,
                    t.geometryPrefixNodes, t.port);
        }

        void verify() {
            require(trace.cell == cellRef && Arrays.equals(trace.cell, cells), "cell mutated");
            require(trace.outsideTail == tailRef && sameBits(trace.outsideTail, tail),
                    "outsideTail mutated");
            require(trace.contPath == pathRef && sameBits(trace.contPath, path),
                    "contPath mutated");
            require(trace.end == end && trace.fromOutlet == outlet
                    && trace.geometryPrefixNodes == prefix && trace.port == port,
                    "trace metadata/port identity mutated");
        }
    }

    public static void main(String[] args) {
        try {
            List<Case> tests = cases();
            System.out.println("TARGET " + DeterministicSkeleton.class.getProtectionDomain()
                    .getCodeSource().getLocation());
            System.out.println("SCOPE halo-only source admission; NOT lake/sea reachability");
            for (Case test : tests) run(test);
            System.out.printf("HALO_COMPONENT %s casesPassed=%d casesFailed=%d total=%d"
                    + " permutations=%d invocations=%d%n", failed == 0 ? "PASS" : "FAIL",
                    passed, failed, tests.size(), permutations, calls);
            System.exit(failed == 0 ? 0 : 1);
        } catch (Throwable error) {
            System.err.println("HALO_COMPONENT FAIL setup/linkage");
            error.printStackTrace(System.err);
            System.exit(1);
        }
    }

    private static List<Case> cases() {
        List<Case> tests = new ArrayList<>();
        Env e = env(-120, -80, 4);
        isolated(tests, e);
        coreSupport(tests, e);
        incomingSupport(tests, e);
        typedSupport(tests, e);
        narrowScope(tests, e);
        mixedGroups(tests, e);
        boundaries(tests, e, "negative");
        boundaries(tests, env(29_999_701, 29_999_603, 4), "large-positive");
        boundaries(tests, env(-29_999_903, -29_999_807, 4), "large-negative");
        boundaries(tests, env(1_200_003, -2_300_007, 7), "mixed-sign-step7");
        return tests;
    }

    private static void isolated(List<Case> tests, Env e) {
        Trace lone = port(e, c(18, 1));
        add(tests, e, "halo-single-node-port-removed", List.of(lone), List.of());
        Trace exit = port(e, c(16, 1), c(17, 1), c(18, 1));
        add(tests, e, "halo-isolated-source-removed", List.of(exit), List.of());
        Trace longHalo = east(e, 0, 1);
        add(tests, e, "halo-long-source-not-a-length-exemption", List.of(longHalo), List.of());
        Trace mid = plain(End.JOIN, c(13, 1), c(14, 1), c(15, 1), c(16, 1));
        Trace head = plain(End.JOIN, c(10, 1), c(11, 1), c(12, 1), c(13, 1));
        add(tests, e, "halo-multilevel-joins-whole-group-removed",
                List.of(exit, head, mid), List.of());
        Trace fork = plain(End.JOIN, c(14, 3), c(14, 2), c(14, 1));
        add(tests, e, "halo-fork-joining-interior-whole-group-removed",
                List.of(exit, head, mid, fork), List.of());
        Trace intersect = plain(End.UNRESOLVED, c(17, 3), c(17, 2), c(17, 1));
        add(tests, e, "shared-cell-group-not-limited-to-JOIN-label",
                List.of(exit, intersect), List.of());
        Trace northExit = north(e, 16, 1);
        add(tests, e, "two-real-exit-ports-whole-group-removed",
                List.of(exit, northExit), List.of());
    }

    private static void coreSupport(List<Case> tests, Env e) {
        Trace direct = east(e, 10, 5);
        add(tests, e, "direct-core-to-port-retained", List.of(direct), List.of(direct));
        Trace interior = east(e, 0, 5);
        add(tests, e, "core-in-interior-not-source-or-mouth", List.of(interior), List.of(interior));
        Trace exit = port(e, c(16, 1), c(17, 1), c(18, 1));
        Trace mid = plain(End.JOIN, c(13, 1), c(14, 1), c(15, 1), c(16, 1));
        Trace head = plain(End.JOIN, c(10, 1), c(11, 1), c(12, 1), c(13, 1));
        Trace feeder = plain(End.JOIN, c(10, 5), c(10, 4), c(10, 3), c(10, 2), c(10, 1));
        List<Trace> fed = List.of(exit, mid, head, feeder);
        add(tests, e, "core-feeds-three-levels-upstream-all-retained", fed, fed);
        Trace shortCore = plain(End.JOIN, c(10, 5));
        add(tests, e, "one-node-core-connector-not-length-pruned",
                List.of(shortCore, direct), List.of(shortCore, direct));
        Trace shortBridge = plain(End.JOIN, c(10, 4), c(10, 3));
        Trace upstream = plain(End.JOIN, c(10, 5), c(10, 4));
        Trace lower = plain(End.JOIN, c(10, 3), c(10, 2), c(10, 1), c(11, 1),
                c(12, 1), c(13, 1), c(14, 1), c(15, 1), c(16, 1));
        List<Trace> bridged = List.of(exit, lower, shortBridge, upstream);
        add(tests, e, "two-node-core-bridge-completes-upstream-support", bridged, bridged);
        Trace waterlessCore = plain(End.DEAD_END, c(8, 5), c(9, 5));
        add(tests, e, "waterless-core-is-not-a-directed-water-proof",
                List.of(waterlessCore), List.of(waterlessCore));
        Trace secondPort = north(e, 16, 1);
        List<Trace> twoPorts = List.of(exit, mid, head, feeder, secondPort);
        add(tests, e, "core-support-keeps-every-port-in-component", twoPorts, twoPorts);
        Trace crossing = plain(End.UNRESOLVED, c(17, 0), c(17, 1), c(16, 2), c(15, 3),
                c(14, 4), c(13, 5), c(12, 5), c(11, 5), c(10, 5));
        add(tests, e, "interior-overlap-with-core-not-a-directed-water-certificate",
                List.of(exit, crossing), List.of(exit, crossing));
    }

    private static void incomingSupport(List<Case> tests, Env e) {
        Trace head = plain(End.JOIN, c(10, 1), c(11, 1), c(12, 1), c(13, 1));
        Trace mid = plain(End.JOIN, c(13, 1), c(14, 1), c(15, 1), c(16, 1));
        Trace exit = port(e, c(16, 1), c(17, 1), c(18, 1));
        List<Trace> group = List.of(head, mid, exit);
        // Explicitly retain all three named traces for a hit at EVERY array position.
        for (int member = 0; member < group.size(); member++) {
            for (int position = 0; position < group.get(member).cell.length; position++) {
                int hit = group.get(member).cell[position];
                addIncoming(tests, e, "incoming-member-" + member + "-position-" + position,
                        group, group, Set.of(hit));
            }
        }
        Trace lone = port(e, c(18, 1));
        addIncoming(tests, e, "incoming-on-single-node-exit", List.of(lone), List.of(lone),
                Set.of(c(18, 1)));
        addIncoming(tests, e, "incoming-unrelated-cell-does-not-protect", group, List.of(),
                Set.of(c(2, 11)));
        addIncoming(tests, e, "incoming-adjacent-not-shared-does-not-protect", group, List.of(),
                Set.of(c(17, 2)));
        addIncoming(tests, e, "incoming-valid-hit-plus-unrelated", group, group,
                Set.of(c(14, 1), c(2, 11)));
    }

    private static void typedSupport(List<Case> tests, Env e) {
        Trace exit = port(e, c(16, 1), c(17, 1), c(18, 1));
        Trace head = plain(End.JOIN, c(10, 1), c(11, 1), c(12, 1), c(13, 1));
        Trace mid = plain(End.JOIN, c(13, 1), c(14, 1), c(15, 1), c(16, 1));
        List<Trace> base = List.of(head, mid, exit);
        for (int member = 0; member < base.size(); member++) {
            Trace t = base.get(member);
            Trace outlet = make(t.end, true, new double[0], t.port, t.cell.clone());
            List<Trace> group = new ArrayList<>(base);
            group.set(member, outlet);
            add(tests, e, "outlet-on-member-" + member, group, group);
            double[] tail = { e.outside().originX() - 4.0, e.outside().originZ() - 4.0 };
            Trace tailed = make(t.end, false, tail, t.port, t.cell.clone());
            require(tailed.hasOutsideTail(), "single-pair tail must satisfy real hasOutsideTail");
            group = new ArrayList<>(base);
            group.set(member, tailed);
            add(tests, e, "hasOutsideTail-single-pair-on-member-" + member, group, group);
        }
        BoundaryPort p = exit.port;
        Trace tail = make(End.BOUNDARY_EXIT, false,
                new double[]{ p.nextX(), p.nextZ(), p.nextX() + 4.0, p.nextZ() },
                p, exit.cell.clone());
        add(tests, e, "multi-point-outside-tail-retained", List.of(tail), List.of(tail));
        for (End water : List.of(End.MOUTH_LAKE, End.MOUTH_SEA)) {
            Trace branch = plain(water, c(14, 3), c(14, 2), c(14, 1));
            List<Trace> group = List.of(exit, head, mid, branch);
            add(tests, e, water + "-on-side-branch-keeps-group-not-directed-certificate",
                    group, group);
            Trace alone = plain(water, c(3, 11), c(4, 11));
            add(tests, e, water + "-without-exit-retained", List.of(alone), List.of(alone));
        }
    }

    private static void narrowScope(List<Case> tests, Env e) {
        for (End end : List.of(End.BOUNDARY_EXIT, End.JOIN, End.UNRESOLVED, End.DEAD_END)) {
            Trace noPort = plain(end, c(17, 1), c(18, 1));
            add(tests, e, "no-port-" + end + "-not-full-hydrology-filter",
                    List.of(noPort), List.of(noPort));
        }
        for (End end : List.of(End.JOIN, End.UNRESOLVED, End.DEAD_END)) {
            Trace portOnly = make(end, false, new double[0], portAt(e, c(18, 1)),
                    c(17, 1), c(18, 1));
            add(tests, e, "port-without-real-exit-" + end, List.of(portOnly), List.of(portOnly));
        }
        Trace exitNoPort = plain(End.BOUNDARY_EXIT, c(16, 1), c(17, 1), c(18, 1));
        Trace otherPort = make(End.JOIN, false, new double[0], portAt(e, c(18, 1)),
                c(18, 2), c(18, 1));
        List<Trace> splitClaims = List.of(exitNoPort, otherPort);
        add(tests, e, "exit-and-port-on-different-traces-not-real-exit", splitClaims, splitClaims);
        Trace a = plain(End.UNRESOLVED, c(2, 10), c(3, 10));
        Trace b = plain(End.JOIN, c(3, 10), c(4, 10));
        add(tests, e, "waterless-no-port-group-retained", List.of(a, b), List.of(a, b));
        Trace removable = port(e, c(17, 1), c(18, 1));
        List<Trace> input = List.of(removable, a);
        tests.add(new Case("null-outside-is-noop", input, input, Set.of(), null, e.core()));
        tests.add(new Case("null-core-is-noop", input, input, Set.of(), e.outside(), null));
        tests.add(new Case("both-contexts-null-is-noop", input, input, Set.of(), null, null));
        add(tests, e, "empty-list", List.of(), List.of());
        tests.add(new Case("empty-null-outside", List.of(), List.of(), Set.of(), null, e.core()));
        tests.add(new Case("empty-null-core", List.of(), List.of(), Set.of(), e.outside(), null));
        tests.add(new Case("empty-both-contexts-null", List.of(), List.of(), Set.of(), null, null));
    }

    private static void mixedGroups(List<Case> tests, Env e) {
        Trace badHead = plain(End.JOIN, c(10, 1), c(11, 1), c(12, 1), c(13, 1));
        Trace badExit = east(e, 13, 1);
        Trace goodCore = east(e, 10, 5);
        Trace inert = plain(End.UNRESOLVED, c(2, 11), c(3, 11), c(4, 11));
        Trace secondBad = port(e, c(18, 10));
        add(tests, e, "mixed-core-inert-and-two-removed-groups",
                List.of(badHead, goodCore, badExit, inert, secondBad), List.of(goodCore, inert));
        Trace incomingExit = port(e, c(17, 10), c(18, 10));
        addIncoming(tests, e, "mixed-incoming-does-not-protect-other-component",
                List.of(badExit, incomingExit, badHead), List.of(incomingExit), Set.of(c(17, 10)));
        Trace adjacent = plain(End.UNRESOLVED, c(16, 2), c(17, 2), c(18, 2));
        add(tests, e, "adjacent-cells-not-a-shared-cell-component",
                List.of(badExit, adjacent), List.of(adjacent));
        Trace plainExit = plain(End.BOUNDARY_EXIT, c(17, 10), c(18, 10));
        add(tests, e, "real-port-in-other-group-does-not-enable-no-port-deletion",
                List.of(badExit, plainExit), List.of(plainExit));
        Trace outlet = make(End.UNRESOLVED, true, new double[0], null, c(3, 10), c(4, 10));
        add(tests, e, "mixed-outlet-does-not-protect-other-component",
                List.of(badHead, outlet, badExit), List.of(outlet));
        Trace tailed = make(End.UNRESOLVED, false, new double[]{ -1.0, -2.0 }, null,
                c(3, 10), c(4, 10));
        add(tests, e, "mixed-tail-does-not-protect-other-component",
                List.of(badHead, tailed, badExit), List.of(tailed));
        for (End water : List.of(End.MOUTH_LAKE, End.MOUTH_SEA)) {
            Trace mouth = plain(water, c(3, 10), c(4, 10));
            add(tests, e, "mixed-" + water + "-does-not-protect-other-component",
                    List.of(badExit, mouth, badHead), List.of(mouth));
        }
    }

    private static void boundaries(List<Case> tests, Env e, String label) {
        // Each ray leaves AWAY from the tested boundary: no other node can supply a core hit.
        boundary(tests, e, label + "-minX-inclusive", north(e, 6, 5), true);
        boundary(tests, e, label + "-before-minX-exclusive", north(e, 5, 5), false);
        boundary(tests, e, label + "-maxX-exclusive", north(e, 12, 5), false);
        boundary(tests, e, label + "-before-maxX-inclusive", north(e, 11, 5), true);
        boundary(tests, e, label + "-minZ-inclusive", east(e, 8, 4), true);
        boundary(tests, e, label + "-before-minZ-exclusive", east(e, 8, 3), false);
        boundary(tests, e, label + "-maxZ-exclusive", east(e, 8, 9), false);
        boundary(tests, e, label + "-before-maxZ-inclusive", east(e, 8, 8), true);
        boundary(tests, e, label + "-lo-lo-corner-inclusive", north(e, 6, 4), true);
        boundary(tests, e, label + "-hi-hi-corner-exclusive", north(e, 12, 9), false);
        boundary(tests, e, label + "-lo-hi-corner-exclusive", east(e, 6, 9), false);
        boundary(tests, e, label + "-hi-lo-corner-exclusive", north(e, 12, 4), false);
    }

    private static void boundary(List<Case> tests, Env e, String name, Trace t, boolean keep) {
        add(tests, e, name, List.of(t), keep ? List.of(t) : List.of());
    }

    private static Env env(int ox, int oz, int step) {
        OutsideContinuation outside = new OutsideContinuation((x, z) -> 120.0,
                ox, oz, step, 32, 63.0);
        CoreBounds core = new CoreBounds(ox + 6 * step, oz + 4 * step,
                ox + 12 * step, oz + 9 * step);
        return new Env(outside, core);
    }

    private static void add(List<Case> tests, Env e, String name,
                            List<Trace> input, List<Trace> kept) {
        addIncoming(tests, e, name, input, kept, Set.of());
    }

    private static void addIncoming(List<Case> tests, Env e, String name,
                                    List<Trace> input, List<Trace> kept, Set<Integer> incoming) {
        tests.add(new Case(name, List.copyOf(input), List.copyOf(kept),
                Set.copyOf(incoming), e.outside(), e.core()));
    }

    private static void run(Case test) {
        try {
            verifyFixture(test);
            List<List<Trace>> orders = new ArrayList<>();
            permute(new ArrayList<>(test.input()), 0, orders);
            for (List<Trace> order : orders) {
                permutations++;
                List<Trace> expected = order.stream().filter(t -> containsIdentity(test.kept(), t))
                        .toList();
                for (boolean immutable : List.of(false, true)) {
                    List<Trace> actual = invoke(test, order, immutable);
                    sameIdentities(actual, expected, "wrong survivors/order");
                    sameIdentities(invoke(test, actual, immutable), expected, "not idempotent");
                }
            }
            passed++;
            System.out.println("PASS " + test.name() + " permutations=" + orders.size()
                    + " kept=" + test.kept().size() + "/" + test.input().size());
        } catch (Throwable error) {
            failed++;
            System.err.println("FAIL " + test.name() + ": " + error);
            error.printStackTrace(System.err);
        }
    }

    private static List<Trace> invoke(Case test, List<Trace> order, boolean immutable) {
        List<Trace> input = immutable ? List.copyOf(order) : new ArrayList<>(order);
        Set<Integer> starts = immutable ? Set.copyOf(test.incoming()) : new HashSet<>(test.incoming());
        List<Snapshot> snapshots = order.stream().map(Snapshot::new).toList();
        List<Trace> result;
        calls++;
        try {
            result = DeterministicSkeleton.pruneHaloComponents(input, NZ, starts,
                    test.outside(), test.core());
        } finally {
            for (Snapshot snapshot : snapshots) snapshot.verify();
            sameIdentities(input, order, "input list mutated");
            require(starts.equals(test.incoming()), "incomingStarts mutated");
        }
        require(result != null, "returned null list");
        int last = -1;
        for (Trace t : result) {
            int index = identityIndex(order, t);
            require(index >= 0, "trace reconstructed or foreign trace returned");
            require(index > last, "trace duplicated/reordered");
            last = index;
        }
        // Re-read the actual port-bearing survivors, not a side-channel exit-port list.
        List<BoundaryPort> actualPorts = result.stream().filter(t -> t.port != null)
                .map(t -> t.port).toList();
        List<BoundaryPort> expectedPorts = order.stream()
                .filter(t -> containsIdentity(test.kept(), t) && t.port != null)
                .map(t -> t.port).toList();
        require(actualPorts.size() == expectedPorts.size(), "geometry/port deletion disagrees");
        for (int i = 0; i < actualPorts.size(); i++) {
            require(actualPorts.get(i) == expectedPorts.get(i), "surviving port identity/order changed");
        }
        return result;
    }

    private static void verifyFixture(Case test) {
        for (Trace t : test.kept()) {
            require(containsIdentity(test.input(), t), "expected survivor absent from input");
        }
        for (Trace t : test.input()) {
            require(t.cell.length > 0, "empty fixture trace");
            require(t.contPath.length == t.cell.length * 2, "unaligned continuous fixture path");
            require(t.outsideTail.length % 2 == 0, "tail is not x,z pairs");
            for (int i = 0; i < t.cell.length; i++) {
                int k = t.cell[i];
                require(k >= 0 && k < NX * NZ, "fixture cell outside grid");
                if (i == 0) continue;
                int previous = t.cell[i - 1];
                int dx = Math.abs(k / NZ - previous / NZ);
                int dz = Math.abs(k % NZ - previous % NZ);
                require(Math.max(dx, dz) == 1, "fixture path not adjacent/distinct");
            }
            if (t.port != null) {
                require(t.port.hasAnchor() && !t.port.receiveKey().isEmpty(), "fake fixture port");
                require(t.port.cell() == t.mouthCell(), "port not anchored at trace mouth");
                if (test.outside() != null) verifyPort(t, test.outside());
            }
        }
    }

    private static void verifyPort(Trace t, OutsideContinuation outside) {
        BoundaryPort p = t.port;
        int step = outside.step();
        require(p.anchorX() == outside.originX() + t.mouthCell() / NZ * step
                && p.anchorZ() == outside.originZ() + t.mouthCell() % NZ * step,
                "port/world mapping inconsistent");
        int x = (p.nextX() - outside.originX()) / step;
        int z = (p.nextZ() - outside.originZ()) / step;
        require(x < 0 || x >= NX || z < 0 || z >= NZ, "next point not outside window");
    }

    private static Trace plain(End end, int... cells) {
        return make(end, false, new double[0], null, cells);
    }

    private static Trace port(Env e, int... cells) {
        return make(End.BOUNDARY_EXIT, false, new double[0], portAt(e, cells[cells.length - 1]), cells);
    }

    private static Trace make(End end, boolean outlet, double[] tail, BoundaryPort port, int... cells) {
        double[] path = new double[cells.length * 2];
        for (int i = 0; i < cells.length; i++) {
            path[2 * i] = cells[i] / NZ + 0.125;
            path[2 * i + 1] = cells[i] % NZ + 0.25;
        }
        return new Trace(cells, end, outlet, tail, path, cells.length, port);
    }

    private static BoundaryPort portAt(Env e, int cell) {
        int x = cell / NZ, z = cell % NZ;
        int dx = x == NX - 1 ? 1 : x == 0 ? -1 : 0;
        int dz = dx != 0 ? 0 : z == NZ - 1 ? 1 : z == 0 ? -1 : 0;
        require(dx != 0 || dz != 0, "port fixture mouth not on frame");
        OutsideContinuation o = e.outside();
        int ax = o.originX() + x * o.step();
        int az = o.originZ() + z * o.step();
        return BoundaryPort.of(ax, az, ax + dx * o.step(), az + dz * o.step(),
                dx, dz, cell, dx, dz);
    }

    private static Trace east(Env e, int x, int z) {
        int[] cells = new int[NX - x];
        for (int i = 0; i < cells.length; i++) cells[i] = c(x + i, z);
        return port(e, cells);
    }

    private static Trace north(Env e, int x, int z) {
        int[] cells = new int[z + 1];
        for (int i = 0; i < cells.length; i++) cells[i] = c(x, z - i);
        return port(e, cells);
    }

    private static int c(int x, int z) {
        require(x >= 0 && x < NX && z >= 0 && z < NZ, "cell fixture outside window");
        return x * NZ + z;
    }

    private static boolean containsIdentity(List<Trace> traces, Trace t) {
        return identityIndex(traces, t) >= 0;
    }

    private static int identityIndex(List<Trace> traces, Trace t) {
        for (int i = 0; i < traces.size(); i++) if (traces.get(i) == t) return i;
        return -1;
    }

    private static void sameIdentities(List<Trace> actual, List<Trace> expected, String message) {
        require(actual.size() == expected.size(), message + "; expected=" + expected.size()
                + " actual=" + actual.size());
        for (int i = 0; i < expected.size(); i++) {
            require(actual.get(i) == expected.get(i), message + "; identity mismatch at " + i);
        }
    }

    private static boolean sameBits(double[] a, double[] b) {
        if (a.length != b.length) return false;
        for (int i = 0; i < a.length; i++) {
            if (Double.doubleToRawLongBits(a[i]) != Double.doubleToRawLongBits(b[i])) return false;
        }
        return true;
    }

    private static void permute(List<Trace> traces, int at, List<List<Trace>> output) {
        if (at == traces.size()) {
            output.add(List.copyOf(traces));
            return;
        }
        for (int i = at; i < traces.size(); i++) {
            Collections.swap(traces, at, i);
            permute(traces, at + 1, output);
            Collections.swap(traces, at, i);
        }
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
