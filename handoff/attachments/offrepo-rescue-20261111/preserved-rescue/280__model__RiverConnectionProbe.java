package com.geogenesis.worldgen.hydrology.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;

/** Function-level connection contract; assertion failures exit nonzero. */
public final class RiverConnectionProbe {
    private static int checks;

    public static void main(String[] args) {
        middleJoin();
        serialJoin();
        sharedDownstream();
        unresolvedConnections();
        cyclicConnections();
        terminalAndEmpty();
        permutations();
        repeatedJoinNodes();
        longChain();
        System.out.println("RIVER_CONNECTION_PASS checks=" + checks);
        System.exit(0);
    }

    private static DeterministicSkeleton.Trace trace(DeterministicSkeleton.End end, int... cells) {
        return new DeterministicSkeleton.Trace(cells, end, false);
    }

    private static void middleJoin() {
        var down = trace(DeterministicSkeleton.End.MOUTH_SEA, 11, 21, 31, 41, 51);
        var up = trace(DeterministicSkeleton.End.JOIN, 13, 22, 31);
        var rivers = DeterministicSkeleton.buildRivers(List.of(down, up), 10);
        check(rivers.size() == 2, "middle JOIN must preserve downstream prefix as its own geometry");
        equal(follow(rivers, 13), 13, 22, 31, 41, 51);
        equal(follow(rivers, 11), 11, 21, 31, 41, 51);
        equal(down.cell, 11, 21, 31, 41, 51);
        equal(up.cell, 13, 22, 31);
        var river = source(rivers, 13);
        check(river.downstreamNode == 2, "actual middle-JOIN node");
        equal(river.cells, 13, 22, 31);
        provenance(rivers, List.of(down, up));
    }

    private static void serialJoin() {
        var a = trace(DeterministicSkeleton.End.JOIN, 1, 2, 3);
        var b = trace(DeterministicSkeleton.End.JOIN, 3, 4, 5);
        var c = trace(DeterministicSkeleton.End.MOUTH_LAKE, 5, 6, 7);
        var rivers = DeterministicSkeleton.buildRivers(List.of(c, a, b), 10);
        check(rivers.size() == 1, "unique head JOINs merge without geometry duplication");
        equal(follow(rivers, 1), 1, 2, 3, 4, 5, 6, 7);
        check(rivers.get(0).end == DeterministicSkeleton.End.MOUTH_LAKE, "serial terminal");
        provenance(rivers, List.of(c, a, b));
        var outlet = new DeterministicSkeleton.Trace(new int[]{3, 4},
                DeterministicSkeleton.End.MOUTH_SEA, true);
        var lakeBoundary = DeterministicSkeleton.buildRivers(List.of(a, outlet), 10);
        check(lakeBoundary.size() == 2 && source(lakeBoundary, 3).fromOutlet, "preserve lake outlet role");
    }

    private static void sharedDownstream() {
        var down = trace(DeterministicSkeleton.End.MOUTH_SEA, 31, 41, 51);
        var a = trace(DeterministicSkeleton.End.JOIN, 13, 22, 31);
        var b = trace(DeterministicSkeleton.End.JOIN, 15, 24, 31);
        var input = List.of(a, b, down);
        var rivers = DeterministicSkeleton.buildRivers(input, 10);
        check(rivers.size() == 3, "shared receiver not claimed by first tributary");
        check(source(rivers, 13).downstreamRiver == source(rivers, 15).downstreamRiver, "shared successor");
        equal(follow(rivers, 13), 13, 22, 31, 41, 51);
        equal(follow(rivers, 15), 15, 24, 31, 41, 51);
        provenance(rivers, input);
    }

    private static void unresolvedConnections() {
        var missing = DeterministicSkeleton.buildRivers(List.of(trace(DeterministicSkeleton.End.JOIN, 1, 2)), 10);
        check(missing.get(0).link == DeterministicSkeleton.RiverLink.MISSING_RECEIVER, "missing explicit");
        var input = List.of(trace(DeterministicSkeleton.End.MOUTH_SEA, 3, 4),
                trace(DeterministicSkeleton.End.MOUTH_LAKE, 3, 5), trace(DeterministicSkeleton.End.JOIN, 1, 3));
        var ambiguous = DeterministicSkeleton.buildRivers(input, 10);
        check(source(ambiguous, 1).link == DeterministicSkeleton.RiverLink.AMBIGUOUS_RECEIVER,
                "overlapping divergent receivers must not choose by list order");
        check(source(ambiguous, 1).downstreamRiver == -1, "no fabricated receiver");
    }

    private static void cyclicConnections() {
        var input = List.of(trace(DeterministicSkeleton.End.JOIN, 1, 2),
                trace(DeterministicSkeleton.End.JOIN, 2, 1), trace(DeterministicSkeleton.End.JOIN, 9, 1));
        var rivers = DeterministicSkeleton.buildRivers(input, 10);
        check(source(rivers, 1).link == DeterministicSkeleton.RiverLink.CYCLE, "two-unit cycle");
        check(source(rivers, 2).link == DeterministicSkeleton.RiverLink.CYCLE, "both cycle members marked");
        check(source(rivers, 9).link == DeterministicSkeleton.RiverLink.CONNECTED, "feeder is not a cycle member");
        provenance(rivers, input);
        var self = DeterministicSkeleton.buildRivers(List.of(trace(DeterministicSkeleton.End.JOIN, 1, 2, 1)), 10);
        check(self.get(0).link == DeterministicSkeleton.RiverLink.CYCLE, "self JOIN explicit cycle");
    }

    private static void terminalAndEmpty() {
        check(DeterministicSkeleton.buildRivers(List.of(), 10).isEmpty(), "empty input");
        for (var end : DeterministicSkeleton.End.values()) {
            if (end == DeterministicSkeleton.End.JOIN) continue;
            var r = DeterministicSkeleton.buildRivers(List.of(trace(end, 1, 1, 2)), 10).get(0);
            equal(r.cells, 1, 2);
            check(r.end == end && r.downstreamRiver == -1, "terminal semantics " + end);
        }
        var empty = DeterministicSkeleton.buildRivers(List.of(trace(DeterministicSkeleton.End.JOIN)), 10);
        check(empty.get(0).link == DeterministicSkeleton.RiverLink.EMPTY_PATH, "empty trace explicit");
    }

    private static void permutations() {
        var input = new ArrayList<>(List.of(trace(DeterministicSkeleton.End.JOIN, 13, 22, 31),
                trace(DeterministicSkeleton.End.JOIN, 15, 24, 41),
                trace(DeterministicSkeleton.End.JOIN, 11, 21, 31, 41, 51),
                trace(DeterministicSkeleton.End.MOUTH_SEA, 51, 61)));
        for (int i = 0; i < 32; i++) {
            Collections.shuffle(input, new java.util.Random(i));
            var rivers = DeterministicSkeleton.buildRivers(input, 10);
            equal(follow(rivers, 13), 13, 22, 31, 41, 51, 61);
            equal(follow(rivers, 15), 15, 24, 41, 51, 61);
            equal(follow(rivers, 11), 11, 21, 31, 41, 51, 61);
            provenance(rivers, input);
        }
    }

    private static void repeatedJoinNodes() {
        var input = List.of(trace(DeterministicSkeleton.End.JOIN, 1, 2, 2),
                trace(DeterministicSkeleton.End.MOUTH_SEA, 2, 3, 4));
        var rivers = DeterministicSkeleton.buildRivers(input, 10);
        equal(follow(rivers, 1), 1, 2, 3, 4);
        provenance(rivers, input);
        var revisited = List.of(trace(DeterministicSkeleton.End.JOIN, 1, 2, 3, 2),
                trace(DeterministicSkeleton.End.MOUTH_SEA, 2, 4));
        check(source(DeterministicSkeleton.buildRivers(revisited, 10), 1).link
                == DeterministicSkeleton.RiverLink.AMBIGUOUS_RECEIVER, "nonadjacent revisit not silently erased");
    }

    private static void longChain() {
        var input = new ArrayList<DeterministicSkeleton.Trace>();
        for (int i = 0; i < 10000; i++) input.add(trace(DeterministicSkeleton.End.JOIN, i, i + 1));
        input.add(trace(DeterministicSkeleton.End.MOUTH_SEA, 10000, 10001));
        Collections.reverse(input);
        var rivers = DeterministicSkeleton.buildRivers(input, 10);
        check(rivers.size() == 1 && rivers.get(0).nodeCount() == 10002, "iterative long serial chain");
        check(rivers.get(0).sourceCell() == 0 && rivers.get(0).mouthCell() == 10001, "long chain direction");
    }

    private static int[] follow(List<DeterministicSkeleton.River> rivers, int start) {
        var out = new ArrayList<Integer>();
        var visited = new HashSet<Integer>();
        int id = rivers.indexOf(source(rivers, start)), node = 0;
        while (id >= 0) {
            check(visited.add(id), "unexpected cycle in successful path");
            var r = rivers.get(id);
            for (int k = node; k < r.cells.length; k++) {
                int c = r.cells[k];
                if (out.isEmpty() || out.get(out.size() - 1) != c) out.add(c);
            }
            if (r.downstreamRiver < 0) {
                check(r.link == DeterministicSkeleton.RiverLink.TERMINAL, "path ended without terminal");
                break;
            }
            check(r.link == DeterministicSkeleton.RiverLink.CONNECTED, "connection status");
            var down = rivers.get(r.downstreamRiver);
            check(down.cells[r.downstreamNode] == r.mouthCell(), "JOIN must share exact cell");
            id = r.downstreamRiver;
            node = r.downstreamNode;
        }
        return out.stream().mapToInt(Integer::intValue).toArray();
    }

    private static DeterministicSkeleton.River source(List<DeterministicSkeleton.River> rivers, int cell) {
        return rivers.stream().filter(r -> r.sourceCell() == cell).findFirst().orElseThrow();
    }

    private static void provenance(List<DeterministicSkeleton.River> rivers,
                                   List<DeterministicSkeleton.Trace> traces) {
        int[] owners = new int[traces.size()];
        var original = new HashSet<String>();
        var retained = new HashSet<String>();
        for (var t : traces) for (int i = 1; i < t.cell.length; i++)
            if (t.cell[i - 1] != t.cell[i]) original.add(t.cell[i - 1] + ":" + t.cell[i]);
        for (var r : rivers) {
            for (int t : r.traceIdx) owners[t]++;
            check(r.traceOf.length == r.cells.length, "provenance aligned");
            for (int i = 0; i < r.cells.length; i++) {
                int cell = r.cells[i];
                check(Arrays.stream(traces.get(r.traceOf[i]).cell).anyMatch(c -> c == cell), "node provenance");
                if (i > 0) retained.add(r.cells[i - 1] + ":" + cell);
            }
        }
        check(Arrays.stream(owners).allMatch(n -> n == 1), "each trace retained exactly once");
        check(original.equals(retained), "directed geometry neither lost nor fabricated");
    }

    private static void equal(int[] actual, int... expected) {
        check(Arrays.equals(actual, expected), "expected=" + Arrays.toString(expected) + " actual=" + Arrays.toString(actual));
    }

    private static void check(boolean ok, String message) {
        checks++;
        if (!ok) throw new AssertionError(message);
    }
}
