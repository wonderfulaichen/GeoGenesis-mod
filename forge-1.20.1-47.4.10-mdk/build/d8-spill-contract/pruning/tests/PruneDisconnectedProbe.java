package com.geogenesis.worldgen.hydrology.model;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import com.geogenesis.worldgen.hydrology.model.DeterministicSkeleton.End;
import com.geogenesis.worldgen.hydrology.model.DeterministicSkeleton.Trace;

/** Synthetic pruning contract only; does not certify build placement or cross-region closure. */
public final class PruneDisconnectedProbe {
    private static final int NX = 17;
    private static final int NZ = 11;
    private static final int MIN_NODES = 5;
    private static Method prune;
    private static int passed;
    private static int failed;
    private static int invocations;

    private record Case(String name, List<Trace> input, Set<Integer> incoming,
                        int minNodes, List<Trace> kept) { }

    private record Snapshot(Trace trace, int[] cellRef, int[] cells,
                            double[] tailRef, double[] tail,
                            double[] contRef, double[] cont,
                            End end, boolean fromOutlet, int prefix, BoundaryPort port) {
        Snapshot(Trace t) {
            this(t, t.cell, t.cell.clone(), t.outsideTail, t.outsideTail.clone(),
                    t.contPath, t.contPath.clone(), t.end, t.fromOutlet,
                    t.geometryPrefixNodes, t.port);
        }

        void verify() {
            require(trace.cell == cellRef && Arrays.equals(trace.cell, cells), "cell changed");
            require(trace.outsideTail == tailRef && Arrays.equals(trace.outsideTail, tail),
                    "outsideTail changed");
            require(trace.contPath == contRef && Arrays.equals(trace.contPath, cont),
                    "contPath changed");
            require(trace.end == end && trace.fromOutlet == fromOutlet
                    && trace.geometryPrefixNodes == prefix && trace.port == port,
                    "trace metadata changed (boundary must not become a water mouth)");
        }
    }

    public static void main(String[] args) {
        try {
            prune = DeterministicSkeleton.class.getDeclaredMethod("pruneDisconnected",
                    List.class, int.class, int.class, int.class, Set.class);
            require(Modifier.isStatic(prune.getModifiers()), "pruneDisconnected must be static");
            require(List.class.isAssignableFrom(prune.getReturnType()), "return type must be List");
            prune.setAccessible(true);
            System.out.println("TARGET " + DeterministicSkeleton.class.getProtectionDomain()
                    .getCodeSource().getLocation());
            System.out.println("SCOPE synthetic cell-successor pruning; BOUNDARY_EXIT is pending, not water");
            for (Case test : cases()) run(test);
            System.out.printf("PRUNING %s casesPassed=%d casesFailed=%d invocations=%d%n",
                    failed == 0 ? "PASS" : "FAIL", passed, failed, invocations);
            System.exit(failed == 0 ? 0 : 1);
        } catch (Throwable error) {
            System.err.println("PRUNING FAIL setup: expected static pruneDisconnected"
                    + "(List<Trace>,int nx,int nz,int minNodes,Set<Integer> incomingStarts)");
            error.printStackTrace(System.err);
            System.exit(2);
        }
    }

    private static List<Case> cases() {
        List<Case> tests = new ArrayList<>();
        deadEnds(tests);
        waterAndLeaves(tests);
        downstreamAndConnectors(tests);
        incomingAndBoundary(tests);
        cyclesAndConflicts(tests);
        tests.add(new Case("empty-input", List.of(), Set.of(), MIN_NODES, List.of()));
        return tests;
    }

    private static void deadEnds(List<Case> tests) {
        Trace outletDead = trace(End.DEAD_END, true, line(5, 4, 10));
        add(tests, "lake-outlet-dead-end-whole-trace-removed", List.of(outletDead), Set.of());
        Trace outletUnknown = trace(End.UNRESOLVED, true, line(5, 4, 10));
        add(tests, "lake-outlet-unresolved-not-exempt", List.of(outletUnknown), Set.of());
        Trace dead = trace(End.DEAD_END, false, line(5, 8, 13));
        Trace tributary = trace(End.JOIN, false, line(5, 3, 8));
        add(tests, "tributary-join-dead-main-both-removed", List.of(dead, tributary), Set.of());
        Trace outlet = trace(End.DEAD_END, true, line(5, 8, 13));
        add(tests, "tributary-join-outlet-dead-main-both-removed", List.of(outlet, tributary), Set.of());
        Trace unresolved = trace(End.UNRESOLVED, false, line(5, 8, 13));
        add(tests, "join-unresolved-main-both-removed", List.of(tributary, unresolved), Set.of());
        Trace frameDead = trace(End.DEAD_END, false, line(5, 11, 16));
        add(tests, "frame-dead-end-not-implicit-boundary-exit", List.of(frameDead), Set.of());
        Trace dangling = trace(End.JOIN, false, line(5, 4, 10));
        add(tests, "join-without-real-shared-downstream-removed", List.of(dangling), Set.of());
    }

    private static void waterAndLeaves(List<Case> tests) {
        Trace sea = trace(End.MOUTH_SEA, false, line(5, 4, 8));
        Trace lake = trace(End.MOUTH_LAKE, false, line(5, 4, 8));
        add(tests, "sea-at-minimum-length-retained", List.of(sea), Set.of(), sea);
        add(tests, "lake-at-minimum-length-retained", List.of(lake), Set.of(), lake);
        Trace longOutlet = trace(End.MOUTH_LAKE, true, line(5, 4, 10));
        add(tests, "long-outlet-reaching-lake-retained", List.of(longOutlet), Set.of(), longOutlet);
        Trace shortSea = trace(End.MOUTH_SEA, false, line(5, 4, 7));
        Trace shortLake = trace(End.MOUTH_LAKE, false, line(5, 4, 7));
        Trace shortOutlet = trace(End.MOUTH_LAKE, true, line(5, 4, 7));
        add(tests, "short-independent-sea-leaf-removed", List.of(shortSea), Set.of());
        add(tests, "short-independent-lake-mouth-leaf-removed", List.of(shortLake), Set.of());
        add(tests, "short-lake-outlet-leaf-not-exempt", List.of(shortOutlet), Set.of());
        tests.add(new Case("minNodes-one-disables-length-pruning", List.of(shortLake),
                Set.of(), 1, List.of(shortLake)));
        Trace dead = trace(End.DEAD_END, false, line(5, 4, 7));
        tests.add(new Case("minNodes-one-does-not-save-dead-end", List.of(dead),
                Set.of(), 1, List.of()));
        Trace main = trace(End.MOUTH_SEA, false, line(5, 8, 13));
        Trace shortJoin = trace(End.JOIN, false, c(7, 3), c(7, 4), c(8, 5));
        add(tests, "short-source-leaf-joining-live-main-removed",
                List.of(shortJoin, main), Set.of(), main);
    }

    private static void downstreamAndConnectors(List<Case> tests) {
        Trace main = trace(End.MOUTH_SEA, false, line(5, 6, 13));
        Trace feeder = trace(End.JOIN, false,
                c(4, 1), c(5, 2), c(6, 3), c(7, 4), c(8, 5));
        add(tests, "join-follows-real-interior-shared-cell-to-sea",
                List.of(feeder, main), Set.of(), feeder, main);
        Trace lakeMain = trace(End.MOUTH_LAKE, false, line(5, 10, 15));
        Trace middle = trace(End.JOIN, false, line(5, 6, 10));
        Trace head = trace(End.JOIN, false, line(5, 2, 6));
        add(tests, "multi-hop-join-chain-reaches-lake", List.of(head, middle, lakeMain),
                Set.of(), head, middle, lakeMain);
        for (int entry = 8; entry <= 10; entry++) {
            Trace connector = trace(End.MOUTH_SEA, false, line(5, 8, 10));
            Trace upstream = entry == 8
                    ? trace(End.JOIN, false, line(5, 3, 8))
                    : trace(End.JOIN, false, c(entry - 4, 1), c(entry - 3, 2),
                            c(entry - 2, 3), c(entry - 1, 4), c(entry, 5));
            add(tests, "short-connector-fed-at-node-" + (entry - 8) + "-retained",
                    List.of(connector, upstream), Set.of(), connector, upstream);
        }
        Trace connector = trace(End.JOIN, false, line(5, 6, 8));
        Trace upstream = trace(End.JOIN, false, line(5, 2, 6));
        Trace tail = trace(End.MOUTH_LAKE, false, line(5, 8, 13));
        add(tests, "short-middle-join-connector-between-long-traces-retained",
                List.of(upstream, connector, tail), Set.of(), upstream, connector, tail);
    }

    private static void incomingAndBoundary(List<Case> tests) {
        Trace main = trace(End.MOUTH_SEA, false, line(5, 2, 8));
        Trace typed = trace(End.JOIN, false, line(5, 0, 2));
        add(tests, "typed-incoming-start-protects-short-live-connector",
                List.of(typed, main), Set.of(typed.sourceCell()), typed, main);
        add(tests, "same-short-connector-without-incoming-start-is-leaf",
                List.of(typed, main), Set.of(), main);
        Trace shortWater = trace(End.MOUTH_LAKE, false, line(5, 0, 2));
        add(tests, "incoming-start-protects-short-direct-water-connector",
                List.of(shortWater), Set.of(shortWater.sourceCell()), shortWater);
        for (End end : List.of(End.DEAD_END, End.UNRESOLVED, End.JOIN)) {
            Trace bad = trace(end, false, line(5, 0, 2));
            add(tests, "incoming-start-not-dead-end-exemption-" + end,
                    List.of(bad), Set.of(bad.sourceCell()));
        }
        Trace deadMain = trace(End.DEAD_END, false, line(5, 2, 8));
        add(tests, "typed-short-join-dead-main-not-protected", List.of(typed, deadMain),
                Set.of(typed.sourceCell()));
        Trace pending = boundaryTrace();
        add(tests, "unknown-short-cross-region-port-pending-not-sea", List.of(pending),
                Set.of(), pending);
        Trace upstream = trace(End.JOIN, false, line(5, 9, 14));
        add(tests, "join-real-downstream-to-pending-boundary-retained",
                List.of(upstream, pending), Set.of(), upstream, pending);
        Trace noPort = trace(End.BOUNDARY_EXIT, false, line(5, 14, 16));
        add(tests, "missing-port-unverified-not-kept",
                List.of(noPort), Set.of());
    }

    private static void cyclesAndConflicts(List<Case> tests) {
        Trace a = trace(End.JOIN, false,
                c(4, 4), c(5, 4), c(6, 4), c(7, 4), c(8, 4), c(8, 5));
        Trace b = trace(End.JOIN, false,
                c(8, 5), c(7, 5), c(6, 5), c(5, 5), c(4, 5), c(4, 4));
        Trace control = trace(End.MOUTH_SEA, false, line(9, 9, 14));
        add(tests, "mutual-join-cycle-removed-control-water-retained",
                List.of(a, b, control), Set.of(), control);
        Trace self = trace(End.JOIN, true,
                c(5, 5), c(6, 5), c(7, 5), c(7, 6), c(6, 6), c(5, 6), c(5, 5));
        add(tests, "self-join-cycle-outlet-and-incoming-do-not-save", List.of(self),
                Set.of(self.sourceCell()));
        Trace sea = trace(End.MOUTH_SEA, false, line(5, 4, 10));
        Trace reverseDead = trace(End.DEAD_END, false,
                c(7, 1), c(7, 2), c(7, 3), c(7, 4), c(7, 5), c(8, 4), c(9, 3));
        // Shared cell (7,5): the dead branch cannot run backwards along sea's prefix.
        // It also assigns a different successor there, so both affected traces are invalid.
        add(tests, "reverse-undirected-sharing-cannot-save-dead-downstream",
                List.of(sea, reverseDead, control), Set.of(), control);
        Trace alternate = trace(End.MOUTH_LAKE, false,
                c(4, 2), c(5, 3), c(6, 4), c(7, 5), c(8, 6), c(9, 7));
        Trace feeder = trace(End.JOIN, false,
                c(3, 1), c(4, 2), c(5, 3), c(6, 4), c(7, 5));
        add(tests, "conflicting-successors-reject-both-water-paths-and-feeder",
                List.of(sea, alternate, feeder, control), Set.of(feeder.sourceCell()), control);
    }

    private static Trace boundaryTrace() {
        int[] cells = line(5, 14, 16);
        int origin = -30_000_000;
        BoundaryPort port = BoundaryPort.of(origin + 16 * 4, origin + 5 * 4,
                origin + 17 * 4, origin + 5 * 4, 1, 0, cells[2], 1, 0);
        require(!port.stamped(), "fixture port must have unknown region identity");
        double[] tail = {port.nextX(), port.nextZ()};
        return new Trace(cells, End.BOUNDARY_EXIT, false, tail, continuous(cells), cells.length, port);
    }

    private static Trace trace(End end, boolean outlet, int... cells) {
        return new Trace(cells, end, outlet, new double[0], continuous(cells), cells.length, null);
    }

    private static double[] continuous(int[] cells) {
        double[] path = new double[cells.length * 2];
        for (int i = 0; i < cells.length; i++) {
            path[2 * i] = cells[i] / NZ + 0.125;
            path[2 * i + 1] = cells[i] % NZ + 0.25;
        }
        return path;
    }

    private static int c(int x, int z) {
        require(x >= 0 && x < NX && z >= 0 && z < NZ, "fixture cell outside small grid");
        return x * NZ + z;
    }

    private static int[] line(int z, int firstX, int lastX) {
        int[] cells = new int[lastX - firstX + 1];
        for (int i = 0; i < cells.length; i++) cells[i] = c(firstX + i, z);
        return cells;
    }

    private static void add(List<Case> tests, String name, List<Trace> input,
                            Set<Integer> incoming, Trace... kept) {
        tests.add(new Case(name, input, incoming, MIN_NODES, List.of(kept)));
    }

    private static void run(Case test) {
        try {
            List<List<Trace>> permutations = new ArrayList<>();
            permute(new ArrayList<>(test.input()), 0, permutations);
            for (List<Trace> order : permutations) {
                verifyFixture(order);
                List<Trace> expected = order.stream().filter(test.kept()::contains).toList();
                List<Trace> actual = invoke(order, test.incoming(), test.minNodes());
                sameIdentities(actual, expected, "wrong kept traces for input permutation");
                List<Trace> again = invoke(actual, test.incoming(), test.minNodes());
                sameIdentities(again, expected, "pruning is not idempotent");
            }
            passed++;
            System.out.println("PASS " + test.name() + " permutations=" + permutations.size());
        } catch (Throwable error) {
            failed++;
            System.err.println("FAIL " + test.name() + ": " + error);
            error.printStackTrace(System.err);
        }
    }

    private static List<Trace> invoke(List<Trace> order, Set<Integer> starts, int minNodes)
            throws Throwable {
        List<Trace> input = new ArrayList<>(order);
        List<Snapshot> snapshots = input.stream().map(Snapshot::new).toList();
        Set<Integer> incoming = new HashSet<>(starts);
        Object raw;
        invocations++;
        try {
            raw = prune.invoke(null, input, NX, NZ, minNodes, incoming);
        } catch (InvocationTargetException error) {
            throw error.getCause();
        } finally {
            for (Snapshot snapshot : snapshots) snapshot.verify();
            sameIdentities(input, order, "input list was mutated");
            require(incoming.equals(starts), "incomingStarts was mutated");
        }
        require(raw instanceof List<?>, "prune returned null/non-List");
        List<Trace> output = new ArrayList<>();
        Set<Trace> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        int lastIndex = -1;
        for (Object element : (List<?>) raw) {
            require(element instanceof Trace, "result contains non-Trace");
            Trace trace = (Trace) element;
            int index = identityIndex(order, trace);
            require(index >= 0, "filter reconstructed a Trace instead of retaining original object");
            require(seen.add(trace), "filter duplicated a Trace");
            require(index > lastIndex, "filter changed original relative order");
            lastIndex = index;
            output.add(trace);
        }
        return output;
    }

    private static void verifyFixture(List<Trace> traces) {
        for (Trace t : traces) {
            require(t.nodeCount() > 0, "fixture contains empty trace");
            for (int i = 0; i < t.cell.length; i++) {
                int k = t.cell[i];
                require(k >= 0 && k < NX * NZ, "fixture cell out of bounds");
                if (i == 0) continue;
                int previous = t.cell[i - 1];
                int dx = Math.abs(k / NZ - previous / NZ);
                int dz = Math.abs(k % NZ - previous % NZ);
                require(Math.max(dx, dz) == 1, "fixture contains non-D8/duplicate step");
            }
        }
    }

    private static int identityIndex(List<Trace> traces, Trace target) {
        for (int i = 0; i < traces.size(); i++) if (traces.get(i) == target) return i;
        return -1;
    }

    private static void sameIdentities(List<Trace> actual, List<Trace> expected, String message) {
        require(actual.size() == expected.size(), message + "; expected " + describe(expected)
                + " but got " + describe(actual));
        for (int i = 0; i < expected.size(); i++) {
            require(actual.get(i) == expected.get(i), message + "; identity mismatch at " + i);
        }
    }

    private static String describe(List<Trace> traces) {
        return traces.stream().map(t -> t.end + ":" + Arrays.toString(t.cell)).toList().toString();
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
