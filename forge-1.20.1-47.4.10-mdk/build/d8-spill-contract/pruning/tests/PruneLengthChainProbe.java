package com.geogenesis.worldgen.hydrology.model;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;

import com.geogenesis.worldgen.hydrology.model.DeterministicSkeleton.End;
import com.geogenesis.worldgen.hydrology.model.DeterministicSkeleton.Trace;

/**
 * Short-leaf pruning must measure the <b>whole joined chain</b>, not each window piece.
 *
 * <p>Encoded rule (the intent of these cases, not an implementation hint):</p>
 * <ol>
 *   <li>chain length = distinct nodes from the upstream-most tip to the water mouth of the
 *       JOIN-connected group; if that total is below {@code minNodes}, the whole group goes;</li>
 *   <li>a piece shorter than {@code minNodes} that is <b>needed</b> to complete a chain
 *       (its downstream continuation is itself shorter than {@code minNodes}) is kept
 *       together with that continuation;</li>
 *   <li>a short piece that is externally fed (incoming start / another trace's mouth landing on
 *       it) is kept, and a two-node boundary-port piece stays provisional so a genuine
 *       cross-region connection is not cut;</li>
 *   <li>a short <b>source leaf</b> whose downstream is already a full-length river on its own
 *       is an independent short tributary and is removed, keeping the long river.</li>
 *   <li><b>single-node pieces have no in-window length</b> ({@code nodeCount() == 1}): a lone
 *       node whose only claim is a <b>boundary port with an empty outside tail</b> — the real
 *       production shape of a local source that exits the window — is removed unless real
 *       upstream flow or an incoming start feeds it. A lake-outlet flag on a lone node is
 *       likewise not a length. This is the minimal exception to "very short rivers need not be
 *       generated"; it does not prove any global terminal.</li>
 *   <li><b>port is not tail</b>: the two are different claims. A non-empty outside tail is real
 *       geometry beyond the window and is deliberately preserved, so a lone node carrying a
 *       multi-point tail is <b>not</b> zero-length and stays. No case here asserts that a tailed
 *       piece is deleted, and every port-only fixture uses an empty tail to match production.</li>
 * </ol>
 *
 * <p>Every case also pins order/identity preservation, no input mutation and idempotence.
 * The number of window pieces must never change the verdict for the same physical chain.</p>
 */
public final class PruneLengthChainProbe {
    private static final int NX = 21;
    private static final int NZ = 15;
    private static final int MIN_NODES = 5;

    private static Method prune;
    private static int passed;
    private static int failed;

    private record Case(String name, List<Trace> pieces, List<Trace> kept) { }

    private record Snapshot(Trace trace, int[] cellRef, int[] cells, double[] tailRef, double[] tail,
                            double[] contRef, double[] cont, End end, boolean fromOutlet,
                            int prefix, BoundaryPort port) {
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
                    "trace metadata changed");
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
            System.out.println("SCOPE whole-chain length for short leaves; minNodes=" + MIN_NODES
                    + "; pieces must not be judged in isolation");
            List<Case> cases = cases();
            for (Case test : cases) run(test);
            splitInvariance();
            incomingSingleNodePort();
            System.out.printf("LENGTHCHAIN %s casesPassed=%d casesFailed=%d of %d%n",
                    failed == 0 ? "PASS" : "FAIL", passed, failed, cases.size());
            System.exit(failed == 0 ? 0 : 1);
        } catch (Throwable error) {
            System.err.println("LENGTHCHAIN FAIL setup: expected static pruneDisconnected"
                    + "(List<Trace>,int nx,int nz,int minNodes,Set<Integer> incomingStarts)");
            error.printStackTrace(System.err);
            System.exit(2);
        }
    }

    private static List<Case> cases() {
        List<Case> tests = new ArrayList<>();

        // 3 + 3 across a shared JOIN cell = one 5-node river ending in the sea: keep both pieces.
        Trace aHead = trace(End.JOIN, c(2, 5), c(3, 5), c(4, 5));
        Trace aTail = trace(End.MOUTH_SEA, c(4, 5), c(5, 5), c(6, 5));
        tests.add(new Case("two-short-pieces-total-5-all-kept", List.of(aHead, aTail),
                List.of(aHead, aTail)));

        // 3 + 2 = 4 nodes total: below minNodes, so the whole chain goes, pieces included.
        Trace bHead = trace(End.JOIN, c(2, 6), c(3, 6), c(4, 6));
        Trace bTail = trace(End.MOUTH_SEA, c(4, 6), c(5, 6));
        tests.add(new Case("head-3-tail-2-total-4-all-removed", List.of(bHead, bTail), List.of()));

        // Short source leaf feeding an already full-length river: independent short tributary.
        Trace cHead = trace(End.JOIN, c(2, 7), c(3, 7), c(4, 7));
        Trace cTail = trace(End.MOUTH_SEA,
                c(4, 7), c(5, 7), c(6, 7), c(7, 7), c(8, 7));
        tests.add(new Case("long-tail-5-plus-short-head-3-head-removed", List.of(cHead, cTail),
                List.of(cTail)));

        // Long head into a short tail: the tail is the only route to water, keep both.
        Trace dHead = trace(End.JOIN, c(2, 8), c(3, 8), c(4, 8), c(5, 8), c(6, 8));
        Trace dTail = trace(End.MOUTH_SEA, c(6, 8), c(7, 8), c(8, 8));
        tests.add(new Case("short-tail-3-long-head-5-all-kept", List.of(dHead, dTail),
                List.of(dHead, dTail)));

        // Three short pieces, 7 nodes from source to sea: all kept.
        Trace e1 = trace(End.JOIN, c(1, 9), c(2, 9), c(3, 9));
        Trace e2 = trace(End.JOIN, c(3, 9), c(4, 9), c(5, 9));
        Trace e3 = trace(End.MOUTH_SEA, c(5, 9), c(6, 9), c(7, 9));
        tests.add(new Case("three-short-pieces-total-7-all-kept", List.of(e1, e2, e3),
                List.of(e1, e2, e3)));

        // Same 7-node river cut into two pieces instead of three: identical verdict.
        Trace f1 = trace(End.JOIN, c(1, 10), c(2, 10), c(3, 10), c(4, 10));
        Trace f2 = trace(End.MOUTH_SEA, c(4, 10), c(5, 10), c(6, 10), c(7, 10));
        tests.add(new Case("two-piece-split-same-total-7-all-kept", List.of(f1, f2),
                List.of(f1, f2)));

        // Three pieces, 4 nodes total: whole chain below minNodes, nothing survives.
        Trace g1 = trace(End.JOIN, c(1, 11), c(2, 11));
        Trace g2 = trace(End.JOIN, c(2, 11), c(3, 11));
        Trace g3 = trace(End.MOUTH_LAKE, c(3, 11), c(4, 11));
        tests.add(new Case("three-pieces-total-4-all-removed", List.of(g1, g2, g3), List.of()));

        // Same 5-node river as the first case but ending in a lake: verdict must not change.
        Trace hHead = trace(End.JOIN, c(2, 12), c(3, 12), c(4, 12));
        Trace hTail = trace(End.MOUTH_LAKE, c(4, 12), c(5, 12), c(6, 12));
        tests.add(new Case("two-short-pieces-total-5-lake-mouth-all-kept",
                List.of(hHead, hTail), List.of(hHead, hTail)));

        // Exactly at the threshold: 2 + 2 + 2 pieces forming a 5-node river.
        Trace i1 = trace(End.JOIN, c(1, 13), c(2, 13));
        Trace i2 = trace(End.JOIN, c(2, 13), c(3, 13));
        Trace i3 = trace(End.MOUTH_SEA, c(3, 13), c(4, 13), c(5, 13));
        tests.add(new Case("three-pieces-total-5-exactly-at-min-all-kept", List.of(i1, i2, i3),
                List.of(i1, i2, i3)));
        singleNodePorts(tests);
        return tests;
    }

    /**
     * Single-node pieces, split by <b>which claim</b> they carry — port is not tail.
     *
     * <ul>
     *   <li>Port / lake-outlet flag on a lone node with an <b>empty outside tail</b> (the real
     *       production shape of a local source leaving the window) is not in-window length
     *       ⇒ removed, unless real upstream flow feeds it (long feeder, incoming start).</li>
     *   <li>A lone node with a <b>multi-point world tail</b> has real geometry beyond the window
     *       ⇒ retained. The tail is deliberately preserved by production, so no case here claims
     *       that a tailed piece is deleted.</li>
     *   <li>A 2-node port piece stays provisional so a genuine cross-region connection is not cut.</li>
     * </ul>
     */
    private static void singleNodePorts(List<Case> tests) {
        // Port only, empty tail: no in-window length, nothing upstream -> removed.
        Trace lone = portTrace(End.BOUNDARY_EXIT, c(15, 3));
        tests.add(new Case("one-node-port-no-upstream-removed", List.of(lone), List.of()));

        Trace fedNode = portTrace(End.BOUNDARY_EXIT, c(15, 4));
        Trace feeder = trace(End.JOIN, c(11, 4), c(12, 4), c(13, 4), c(14, 4), c(15, 4));
        tests.add(new Case("one-node-port-fed-by-long-feeder-kept", List.of(fedNode, feeder),
                List.of(fedNode, feeder)));

        Trace outletNode = outletTrace(End.MOUTH_LAKE, c(15, 5));
        tests.add(new Case("one-node-outlet-no-upstream-removed", List.of(outletNode), List.of()));

        Trace twoNodePort = portTrace(End.BOUNDARY_EXIT, c(15, 6), c(16, 6));
        tests.add(new Case("two-node-port-provisional-kept", List.of(twoNodePort),
                List.of(twoNodePort)));

        // Positive control: the same lone node, now with a real multi-point tail past the frame.
        // This piece is NOT zero-length and must never be asserted as removable.
        Trace tailed = tailedNode(End.BOUNDARY_EXIT, c(17, 3), 4);
        tests.add(new Case("one-node-with-outside-tail-retained", List.of(tailed),
                List.of(tailed)));
    }

    /** The same single node, protected by an incoming start instead of by a feeder trace. */
    private static void incomingSingleNodePort() {
        String name = "one-node-port-with-incoming-start-kept";
        Trace node;
        try {
            node = portTrace(End.BOUNDARY_EXIT, c(15, 7));
            verifyFixture(List.of(node));
            Set<Integer> incoming = new java.util.HashSet<>();
            incoming.add(node.sourceCell());
            List<Trace> actual = invoke(List.of(node), incoming);
            sameIdentities(actual, List.of(node), "incoming start on a single node must keep it");
            sameIdentities(invoke(actual, incoming), List.of(node), "pruning is not idempotent");
            passed++;
            System.out.println("PASS " + name);
        } catch (Throwable error) {
            failed++;
            System.err.println("FAIL " + name + ": " + error);
            error.printStackTrace(System.err);
        }
    }

    /** The same physical chain must be judged identically no matter how many pieces it is cut into. */
    private static void splitInvariance() {
        String name = "split-count-invariance-7-node-chain";
        try {
            Trace two1 = trace(End.JOIN, c(1, 10), c(2, 10), c(3, 10), c(4, 10));
            Trace two2 = trace(End.MOUTH_SEA, c(4, 10), c(5, 10), c(6, 10), c(7, 10));
            Trace three1 = trace(End.JOIN, c(1, 9), c(2, 9), c(3, 9));
            Trace three2 = trace(End.JOIN, c(3, 9), c(4, 9), c(5, 9));
            Trace three3 = trace(End.MOUTH_SEA, c(5, 9), c(6, 9), c(7, 9));
            int two = chainNodes(invoke(List.of(two1, two2)));
            int three = chainNodes(invoke(List.of(three1, three2, three3)));
            require(two == 7 && three == 7, "piece count changed retained chain length: two="
                    + two + " three=" + three);
            passed++;
            System.out.println("PASS " + name + " twoPiece=" + two + " threePiece=" + three);
        } catch (Throwable error) {
            failed++;
            System.err.println("FAIL " + name + ": " + error);
            error.printStackTrace(System.err);
        }
    }

    private static int chainNodes(List<Trace> kept) {
        Set<Integer> nodes = new java.util.HashSet<>();
        for (Trace t : kept) for (int c : t.cell) nodes.add(c);
        return nodes.size();
    }

    private static void run(Case test) {
        try {
            List<List<Trace>> permutations = new ArrayList<>();
            permute(new ArrayList<>(test.pieces()), 0, permutations);
            for (List<Trace> order : permutations) {
                verifyFixture(order);
                List<Trace> expected = order.stream().filter(test.kept()::contains).toList();
                List<Trace> actual = invoke(order);
                sameIdentities(actual, expected, "wrong kept pieces for input permutation");
                List<Trace> again = invoke(actual);
                sameIdentities(again, expected, "pruning is not idempotent");
            }
            passed++;
            System.out.println("PASS " + test.name() + " permutations=" + permutations.size()
                    + " kept=" + test.kept().size() + "/" + test.pieces().size());
        } catch (Throwable error) {
            failed++;
            System.err.println("FAIL " + test.name() + ": " + error);
            error.printStackTrace(System.err);
        }
    }

    private static List<Trace> invoke(List<Trace> order) throws Throwable {
        return invoke(order, Set.of());
    }

    private static List<Trace> invoke(List<Trace> order, Set<Integer> starts) throws Throwable {
        List<Trace> input = new ArrayList<>(order);
        List<Snapshot> snapshots = input.stream().map(Snapshot::new).toList();
        Set<Integer> incoming = new java.util.HashSet<>(starts);
        Object raw;
        try {
            raw = prune.invoke(null, input, NX, NZ, MIN_NODES, incoming);
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
            require(t.outsideTail.length % 2 == 0,
                    "fixture outside tail must alternate x,z world coordinates");
            for (int i = 0; i < t.cell.length; i++) {
                require(t.cell[i] >= 0 && t.cell[i] < NX * NZ, "fixture cell out of bounds");
                if (i == 0) continue;
                int dx = Math.abs(t.cell[i] / NZ - t.cell[i - 1] / NZ);
                int dz = Math.abs(t.cell[i] % NZ - t.cell[i - 1] % NZ);
                require(Math.max(dx, dz) == 1, "fixture contains non-D8/duplicate step");
            }
        }
    }

    private static Trace trace(End end, int... cells) {
        return new Trace(cells, end, false, new double[0], continuous(cells), cells.length, null);
    }

    /** Lake-outlet piece (downstream of a lake) with no port. */
    private static Trace outletTrace(End end, int... cells) {
        return new Trace(cells, end, true, new double[0], continuous(cells), cells.length, null);
    }

    /**
     * Piece carrying a real (unstamped) boundary port with window-relative anchor and next coords,
     * so the port has genuine geometry and a stable identity key.
     *
     * <p><b>Port is not tail.</b> The outside tail is left <b>empty</b>, matching the production
     * shape of a single-node source that leaves the window: the port records where the water
     * would go, but there is no ordered world geometry beyond the frame. A non-empty tail is a
     * different claim — see {@link #tailedNode}.</p>
     */
    private static Trace portTrace(End end, int... cells) {
        int last = cells[cells.length - 1];
        BoundaryPort port = portAt(last);
        return new Trace(cells, end, false, new double[0], continuous(cells), cells.length, port);
    }

    /**
     * Positive control: the same lone node, but with a <b>multi-point</b> world tail outside the
     * window. Real geometry beyond the frame is deliberately preserved by production, so such a
     * piece is not zero-length and must be retained.
     */
    private static Trace tailedNode(End end, int nodeCell, int tailPoints) {
        require(tailPoints >= 2, "an outside tail needs at least two points to be real geometry");
        BoundaryPort port = portAt(nodeCell);
        double[] tail = new double[tailPoints * 2];
        double x = port.anchorX();
        double z = port.anchorZ();
        for (int i = 0; i < tailPoints; i++) {
            tail[2 * i] = x + 4 * (i + 1);
            tail[2 * i + 1] = z;
        }
        require(tail.length > 2, "fixture tail must be multi-point");
        return new Trace(new int[]{nodeCell}, end, false, tail, continuous(new int[]{nodeCell}),
                1, port);
    }

    /** Unstamped port with real anchor / next / key geometry, anchored on {@code cell}. */
    private static BoundaryPort portAt(int cell) {
        int originX = -30_000_000;
        int originZ = -30_000_000;
        int ax = originX + (cell / NZ) * 4;
        int az = originZ + (cell % NZ) * 4;
        BoundaryPort port = BoundaryPort.of(ax, az, ax + 4, az, 1, 0, cell, 1, 0);
        require(port.hasAnchor(), "fixture port must carry real anchor coordinates");
        require(!port.receiveKey().isEmpty(), "fixture port must carry a receive key");
        return port;
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
