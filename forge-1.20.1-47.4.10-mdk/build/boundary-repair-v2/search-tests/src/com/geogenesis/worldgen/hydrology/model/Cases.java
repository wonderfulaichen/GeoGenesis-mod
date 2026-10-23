package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.hydrology.model.OrderedSpillSearch.Status;

import java.util.ArrayList;
import java.util.List;

/**
 * Synthetic windows: each one pins exactly one clause of the search contract.
 *
 * <p>Every case carries a hand expectation derived from the geometry alone, so the probe compares
 * three independent things: the hand expectation (authority), {@link Oracle} (brute force) and the
 * subject. Geometry notes that bit during development and are easy to get wrong:</p>
 *
 * <ul>
 *   <li>{@code crest >= bed[start]} always — the start cell is part of every path;</li>
 *   <li>any reachable cell at bed &lt; bed[start] is already a terminal, so corridor cells must be
 *       kept at or above the start bed unless that cell is meant to be the endpoint;</li>
 *   <li>a flat plateau at bed == bed[start] reaches the frame, so "unknown at the same crest"
 *       is the default and has to be walled out explicitly.</li>
 * </ul>
 */
final class Cases {

    private Cases() { }

    static List<Scenario> all() {
        List<Scenario> out = new ArrayList<>();
        out.add(blockedWallDetour());
        out.add(cheapCorridorWins());
        out.add(blockedEdgeDetour());
        out.add(tieSameLengthSameCrest());
        out.add(tieWorldOrderSameLayer());
        out.add(domainUnknownFartherThanGoal());
        out.add(frameEqualCrestMuchFarther());
        out.add(domainUnknownCloserThanGoal());
        out.add(boundaryStrictlyCheaper());
        out.add(boundaryEqualCrestLaterLayer());
        out.add(boundaryAtCap());
        out.add(receiverAbsorbsCheaperLower());
        out.add(crestBeatsShortcut());
        out.add(noTerminalInsideCap());
        out.add(budgetExhausted());
        out.add(cancelBeforeStart());
        out.add(cancelInsideBfs());
        return out;
    }

    /**
     * Flat 50 basin with a 90 rim; the only crest below the cap is the 60 notch at (4,5). The far
     * side holds a 40 cell, so the answer is ESCAPE at crest 60 through the notch.
     */
    static Scenario blockedWallDetour() {
        int n = 12;
        Scenario s = new Scenario("wall/opening-at-60",
                "90 rim closes the frame; the only crest below cap is the 60 notch at (4,5)",
                Scenario.fill(n * n, 50), n, n, idx(n, 5, 5), 12);
        for (int k = 0; k < n; k++) {
            s.bed(k, 0, 90); s.bed(k, n - 1, 90); s.bed(0, k, 90); s.bed(n - 1, k, 90);
        }
        for (int z = 1; z <= 10; z++) s.block(4, z);
        s.bed(4, 5, 60); s.unblock(4, 5);
        s.bed(2, 5, 40);
        return s.expect(Status.ESCAPE, 60.0, idx(n, 2, 5));
    }

    /**
     * Two carved corridors out of a 50 start (everything else blocked, inner frame ring walled).
     * Corridor A is 2 steps at 58, corridor B is 3 steps at 52; they are kept 2 cells apart so the
     * 8-neighbourhood cannot merge them. Crest must be 52.
     */
    static Scenario cheapCorridorWins() {
        int n = 16;
        Scenario s = new Scenario("cheap/crest-beats-shorter-corridor",
                "58 corridor in 2 steps vs 52 corridor in 3 steps to a 40 terminal",
                Scenario.fill(n * n, 50), n, n, idx(n, 8, 8), 40);
        ringWall(s, n);
        for (int x = 2; x <= n - 3; x++) for (int z = 2; z <= n - 3; z++) s.block(x, z);
        s.unblock(8, 8);
        for (int[] c : new int[][]{{8, 7}}) { s.bed(c[0], c[1], 58); s.unblock(c[0], c[1]); }
        s.bed(8, 6, 40); s.unblock(8, 6);
        for (int[] c : new int[][]{{9, 9}, {10, 10}}) { s.bed(c[0], c[1], 52); s.unblock(c[0], c[1]); }
        s.bed(11, 11, 40); s.unblock(11, 11);
        return s.expect(Status.ESCAPE, 52.0, idx(n, 11, 11));
    }

    /**
     * Two legal crossings of a north-south wall: a 50 gap at (4,5) whose incoming east-to-west
     * edges are blocked, and a 58 gap at (4,3). With the edge guard the crest is 58.
     */
    static Scenario blockedEdgeDetour() {
        int n = 12;
        Scenario s = new Scenario("edge/blocked-edge-forces-detour",
                "gap (4,5) is bed 50 but every edge into it is blocked; only the 58 gap at (4,3) is legal",
                Scenario.fill(n * n, 50), n, n, idx(n, 5, 4), 40);
        for (int z = 1; z <= 10; z++) s.block(4, z);
        s.bed(4, 3, 58); s.unblock(4, 3);
        s.bed(4, 5, 50); s.unblock(4, 5);
        s.edge(5, 4, 4, 5); s.edge(5, 5, 4, 5); s.edge(5, 6, 4, 5);
        ringWall(s, n);
        s.bed(2, 3, 40); s.recv(2, 3);
        return s.expect(Status.JOIN, 58.0, idx(n, 2, 3));
    }

    /** Two receivers, same crest, same BFS length: world order (x, then z) must decide. */
    static Scenario tieSameLengthSameCrest() {
        int n = 14;
        Scenario s = new Scenario("tie/same-crest-same-length",
                "receivers at (5,7) and (5,3): identical crest and length, world order decides",
                Scenario.fill(n * n, 50), n, n, idx(n, 5, 5), 12);
        ringWall(s, n);
        s.recv(5, 7);
        s.recv(5, 3);
        return s.expect(Status.JOIN, 50.0, idx(n, 5, 3));
    }

    /** Two terminals in one BFS layer: the smaller world x wins. */
    static Scenario tieWorldOrderSameLayer() {
        int n = 14;
        Scenario s = new Scenario("tie/world-order",
                "receivers at (6,8) and (8,8): same layer, the west one must win",
                Scenario.fill(n * n, 50), n, n, idx(n, 7, 7), 12);
        ringWall(s, n);
        s.recv(6, 8);
        s.recv(8, 8);
        return s.expect(Status.JOIN, 50.0, idx(n, 6, 8));
    }

    /**
     * POSITIVE ASSERTION (required): a same-crest unknown frame contact that is strictly farther
     * than the endpoint must NOT block certification. The endpoint is a receiver at layer 5, the
     * frame is only entered at layer 25, so the answer must be JOIN at that receiver.
     */
    static Scenario domainUnknownFartherThanGoal() {
        int n = 30;
        Scenario s = new Scenario("domain/frame-farther-than-endpoint-must-certify",
                "flat 50: receiver at (10,20) is 5 layers away, the frame is 25 layers away",
                Scenario.fill(n * n, 50), n, n, idx(n, 15, 20), 12);
        s.recv(10, 20);
        return s.expect(Status.JOIN, 50.0, idx(n, 10, 20));
    }

    /**
     * Flat plateau at the start bed: every reachable cell, including the whole frame, sits exactly at
     * the start crest. The endpoint is 2 layers away and the frame is 18 layers away, so a
     * strictly-farther same-crest contact must not block. Positive assertion: JOIN.
     */
    static Scenario frameEqualCrestMuchFarther() {
        int n = 40;
        Scenario s = new Scenario("domain/equal-crest-frame-17-layers-out-must-certify",
                "flat 50 start at (20,20); receiver at (20,22) layer 2, frame layer 18",
                Scenario.fill(n * n, 50), n, n, idx(n, 20, 20), 12);
        s.recv(20, 22);
        return s.expect(Status.JOIN, 50.0, idx(n, 20, 22));
    }

    /** Negative control: the same crest contact is *closer* than the endpoint -> unresolved. */
    static Scenario domainUnknownCloserThanGoal() {
        int n = 40;
        Scenario s = new Scenario("domain/frame-closer-than-endpoint-must-pend",
                "start at (1,20) touches the frame at layer 1; the receiver is at layer 19",
                Scenario.fill(n * n, 50), n, n, idx(n, 1, 20), 12);
        s.recv(20, 20);
        return s.expect(Status.DOMAIN_PENDING, 50.0, -1);
    }

    /** A frame cell at crest 40 while the best endpoint costs 50: strictly cheaper outside. */
    static Scenario boundaryStrictlyCheaper() {
        int n = 12;
        Scenario s = new Scenario("boundary/strictly-cheaper",
                "frame cell (0,6) at 40 beats the receiver at 50",
                Scenario.fill(n * n, 50), n, n, idx(n, 5, 6), 12);
        s.recv(5, 6);
        s.bed(0, 6, 40);
        return s.expect(Status.DOMAIN_PENDING, 50.0, -1);
    }

    /**
     * Equal-crest frame contact, 15+ layers farther than the endpoint. Flat plateau at the start bed,
     * so the frame is exactly at the crest; the receiver is 3 layers away. Positive: JOIN.
     * This is the window the old `boundary <= crest` shortcut used to block.
     */
    static Scenario boundaryEqualCrestLaterLayer() {
        int n = 40;
        Scenario s = new Scenario("domain/equal-crest-15-layers-out-must-certify",
                "flat 50; receiver at (20,23) is layer 3, the frame is layer 17",
                Scenario.fill(n * n, 50), n, n, idx(n, 20, 20), 12);
        s.recv(20, 23);
        return s.expect(Status.JOIN, 50.0, idx(n, 20, 23));
    }

    /** A frame cell sits exactly at the cap: reachable, so it is not "no escape". */
    static Scenario boundaryAtCap() {
        int n = 12;
        Scenario s = new Scenario("cap/frame-at-cap",
                "single corridor to a frame cell at exactly bed[start]+rise; no terminal exists",
                Scenario.fill(n * n, 50), n, n, idx(n, 5, 5), 3);
        for (int k = 0; k < n; k++) {
            s.bed(k, 0, 53); s.bed(k, n - 1, 53); s.bed(0, k, 53); s.bed(n - 1, k, 53);
        }
        for (int x = 1; x <= n - 2; x++) for (int z = 1; z <= n - 2; z++) s.block(x, z);
        s.unblock(5, 5);
        for (int[] c : new int[][]{{4, 5}, {4, 4}, {3, 4}, {3, 3}, {2, 3}, {2, 2}, {2, 1}, {2, 0}}) {
            s.unblock(c[0], c[1]);
        }
        return s.expect(Status.DOMAIN_PENDING, Double.NaN, -1);
    }

    /** A receiver at 50 must absorb the search even though a 30 bowl sits one step beyond it. */
    static Scenario receiverAbsorbsCheaperLower() {
        int n = 14;
        Scenario s = new Scenario("receiver/absorbs",
                "receiver at (7,9) crest 50 stops the search; a 30-bowl sits at (7,10)",
                Scenario.fill(n * n, 50), n, n, idx(n, 7, 7), 12);
        ringWall(s, n);
        for (int x = 6; x <= 8; x++) for (int z = 10; z <= 12; z++) s.bed(x, z, 30);
        s.recv(7, 9);
        return s.expect(Status.JOIN, 50.0, idx(n, 7, 9));
    }

    /**
     * The minimax crest ignores a 4-step 58 shortcut and takes the long 50 way round the wall to the
     * same receiver, so crest 50 and a path longer than the shortcut.
     */
    static Scenario crestBeatsShortcut() {
        int n = 14;
        Scenario s = new Scenario("len/crest-beats-shortcut",
                "58 shortcut in 4 steps vs the long 50 way round the wall (7 steps) to one receiver",
                Scenario.fill(n * n, 50), n, n, idx(n, 7, 7), 12);
        ringWall(s, n);
        for (int z = 2; z <= 9; z++) s.block(6, z);
        s.bed(6, 4, 58); s.unblock(6, 4);
        s.recv(4, 7);
        return s.expect(Status.JOIN, 50.0, idx(n, 4, 7));
    }

    /** Nothing is reachable: every step exceeds the cap. */
    static Scenario noTerminalInsideCap() {
        int n = 12;
        Scenario s = new Scenario("no-escape/walled-in",
                "start sits in a 1-cell well; rise 0.5 cannot reach the 50 rim",
                Scenario.fill(n * n, 50), n, n, idx(n, 5, 5), 0.5);
        for (int dz = -1; dz <= 1; dz++) for (int dx = -1; dx <= 1; dx++) {
            if (dx != 0 || dz != 0) s.block(5 + dx, 5 + dz);
        }
        return s.expect(Status.NO_ESCAPE, Double.NaN, -1);
    }

    /** Tiny work budget: the search must report EXHAUSTED, never NO_ESCAPE. */
    static Scenario budgetExhausted() {
        int n = 40;
        Scenario s = new Scenario("budget/exhausted",
                "budget 40 on a 40x40 plateau: status must stay EXHAUSTED",
                Scenario.fill(n * n, 50), n, n, idx(n, 20, 20), 12);
        s.recv(35, 20);
        s.budget = 40;
        return s.expect(Status.EXHAUSTED, Double.NaN, -1);
    }

    /** Interrupted before the search starts. */
    static Scenario cancelBeforeStart() {
        int n = 20;
        Scenario s = new Scenario("cancel/pre-start",
                "thread already interrupted: find() must throw CancellationException before any work",
                Scenario.fill(n * n, 50), n, n, idx(n, 10, 10), 12);
        s.recv(2, 10);
        s.interruptBeforeStart = true;
        return s;
    }

    /**
     * Interrupted on the first guard callback after the endpoint cell is seen, i.e. inside the
     * tie-break BFS. The corridor keeps everything else out so the endpoint is unambiguous.
     */
    static Scenario cancelInsideBfs() {
        int n = 14;
        Scenario s = new Scenario("cancel/inside-bfs",
                "corridor to a receiver; interrupt fires on the callback after the endpoint",
                Scenario.fill(n * n, 50), n, n, idx(n, 7, 7), 12);
        ringWall(s, n);
        for (int x = 2; x <= n - 3; x++) for (int z = 2; z <= n - 3; z++) s.block(x, z);
        for (int[] c : new int[][]{{7, 7}, {7, 8}, {7, 9}, {6, 9}}) s.unblock(c[0], c[1]);
        s.recv(6, 9);
        s.interruptAfterCell = idx(n, 6, 9);
        return s.expect(Status.JOIN, 50.0, idx(n, 6, 9));
    }

    /** Blocks the inner ring of the frame so no frame contact is possible at all. */
    static void ringWall(Scenario s, int n) {
        for (int k = 0; k < n; k++) {
            s.block(1, k); s.block(n - 2, k); s.block(k, 1); s.block(k, n - 2);
        }
    }

    static int idx(int n, int x, int z) { return x * n + z; }
}
