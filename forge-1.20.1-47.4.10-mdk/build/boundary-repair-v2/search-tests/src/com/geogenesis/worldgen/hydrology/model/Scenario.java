package com.geogenesis.worldgen.hydrology.model;

import java.util.LinkedHashSet;
import java.util.Set;

/** One synthetic window plus the guard masks the subject receives (verification only). */
final class Scenario {
    final String name;
    final String why;
    final double[] bed;
    final int nx, nz, start;
    final double rise;
    final boolean[] blocked;
    final boolean[] receiver;
    final Set<Long> edges = new LinkedHashSet<>();
    /** hand expectation, independently of both implementations. */
    OrderedSpillSearch.Status expectStatus;
    double expectCrest = Double.NaN;
    /** expected endpoint cell, -1 when the hand expectation has no usable endpoint. */
    int expectEnd = -1;
    /** total work budget handed to the subject (Integer.MAX_VALUE = effectively unlimited). */
    int budget = Integer.MAX_VALUE;
    /** interrupt the worker before find() is even called. */
    boolean interruptBeforeStart;
    /**
     * interrupt the worker on the first guard callback that happens <em>after</em> this cell has been
     * queried. Used to land inside the tie-break BFS: the candidate cell is queried by settle(),
     * and the next callbacks come from the BFS.
     */
    int interruptAfterCell = -1;

    Scenario(String name, String why, double[] bed, int nx, int nz, int start, double rise) {
        this.name = name; this.why = why; this.bed = bed;
        this.nx = nx; this.nz = nz; this.start = start; this.rise = rise;
        this.blocked = new boolean[bed.length];
        this.receiver = new boolean[bed.length];
    }

    Scenario expect(OrderedSpillSearch.Status status, double crest, int end) {
        this.expectStatus = status; this.expectCrest = crest; this.expectEnd = end;
        return this;
    }

    int idx(int x, int z) { return x * nz + z; }

    Scenario wall(int x0, int z0, int x1, int z1, int x2, int z2) {
        for (int x = x0; x <= x1; x++) for (int z = z0; z <= z1; z++) blocked[idx(x, z)] = true;
        for (int x = x1 + 1; x <= x2; x++) blocked[idx(x, z0)] = true;
        return this;
    }

    Scenario block(int x, int z) { blocked[idx(x, z)] = true; return this; }

    Scenario unblock(int x, int z) { blocked[idx(x, z)] = false; return this; }

    Scenario recv(int x, int z) { receiver[idx(x, z)] = true; return this; }

    Scenario bed(int x, int z, double h) { bed[idx(x, z)] = h; return this; }

    Scenario edge(int x0, int z0, int x1, int z1) {
        edges.add(edgeKey(idx(x0, z0), idx(x1, z1)));
        edges.add(edgeKey(idx(x1, z1), idx(x0, z0)));
        return this;
    }

    static long edgeKey(int a, int b) { return ((long) a << 32) ^ (b & 0xffffffffL); }

    /**
     * Guards handed to the subject. When {@link #interruptAfterCell} is set, the wrapper interrupts
     * the calling thread on the first callback that follows a query for that cell — the only way to
     * reach the interrupt checks that live inside the tie-break BFS.
     */
    OrderedSpillSearch.Guards guards() {
        return guards(false);
    }

    /**
     * @param armInterrupt when true the wrapper honours {@link #interruptAfterCell}; only the cancel
     *     scenarios set it, so the flag can never leak into another window's run
     */
    OrderedSpillSearch.Guards guards(boolean armInterrupt) {
        final boolean[] armed = {false};
        final int fireAfter = armInterrupt ? interruptAfterCell : -1;
        return new OrderedSpillSearch.Guards() {
            private void tick(int cell) {
                if (fireAfter < 0) return;
                if (armed[0]) Thread.currentThread().interrupt();
                else if (cell == fireAfter) armed[0] = true;
            }
            public boolean blocked(int c) { tick(c); return blocked[c]; }
            public boolean receiver(int c) { tick(c); return receiver[c]; }
            public boolean blockedEdge(int a, int b) { tick(b); return edges.contains(edgeKey(a, b)); }
        };
    }

    OrderedSpillSearch subject() {
        boolean arm = interruptBeforeStart || interruptAfterCell >= 0;
        return new OrderedSpillSearch(bed, nx, nz, start, rise, 0, 0, 1, budget, guards(arm));
    }

    Oracle oracle() {
        // the oracle must never be interrupted by a scenario hook
        return Oracle.eval(bed, nx, nz, start, rise, guards(false));
    }

    /** Work budget the subject was given (Integer.MAX_VALUE = unbounded). */
    int budget() { return budget; }

    /** flattens the window to one height (used by the synthetic builders). */
    static double[] fill(int n, double h) {
        double[] a = new double[n];
        java.util.Arrays.fill(a, h);
        return a;
    }
}
