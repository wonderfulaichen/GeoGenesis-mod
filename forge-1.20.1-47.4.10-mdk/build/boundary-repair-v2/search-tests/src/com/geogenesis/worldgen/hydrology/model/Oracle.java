package com.geogenesis.worldgen.hydrology.model;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Brute-force reference for {@link OrderedSpillSearch} (verification only, never production).
 *
 * <p>Deliberately written with a different shape than the subject, so that a shared bug is
 * unlikely. Contract (read off the subject source):</p>
 *
 * <pre>
 *   cap        = bed[start] + rise
 *   passable   = inside window &amp;&amp; !blocked(cell) &amp;&amp; cell != start
 *   step from→to at tau = passable(to) &amp;&amp; max(bed[from],bed[to]) &lt;= tau &amp;&amp; !blockedEdge(from,to)
 *   terminal   = cell != start &amp;&amp; (receiver(cell) || bed[cell] &lt; bed[start] - 1e-9)
 *   tau*       = min over reachable terminals of the minimax crest (min over paths of max bed)
 *   boundary   = min over frame cells *entered by a step* of their minimax crest
 *   result     : boundary &lt; tau*            -&gt; DOMAIN_PENDING   (strictly cheaper outside)
 *                some terminal on frame    -&gt; DOMAIN_PENDING   (same-crest unknown)
 *                else                      -&gt; JOIN if receiver(end) else ESCAPE
 *                no terminal within cap    -&gt; DOMAIN_PENDING if a frame cell was entered else NO_ESCAPE
 * </pre>
 *
 * <p>Three independent measurements are exposed instead of one verdict, so a disagreement can be
 * attributed: {@link #tauStar}, {@link #boundaryInclRecv} (all entered frame cells, receivers too)
 * and {@link #boundaryExclRecv} (receiver frame cells dropped — what the subject appears to use).</p>
 */
public final class Oracle {

    public enum Kind { ESCAPE, JOIN, DOMAIN_PENDING, NO_ESCAPE }

    /** A terminal at the minimal crest, with its plain BFS distance at that crest. */
    public static final class Terminal {
        public final int cell; public final int len; public final boolean recv;
        Terminal(int cell, int len, boolean recv) { this.cell = cell; this.len = len; this.recv = recv; }
        @Override public String toString() {
            return cell + "(len=" + len + (recv ? ",recv" : ",lower") + ")";
        }
    }

    public final Kind kind;
    public final double tauStar;
    public final double boundaryInclRecv, boundaryExclRecv;
    /** terminals whose minimax crest equals tau*, sorted by cell index (all of them). */
    public final List<Terminal> terminals = new ArrayList<>();
    /** shortest path from start to the selected endpoint at crest tau* (or empty). */
    public final int[] path;
    public final int endCell;
    /** non-empty when the certified verdict depends on a reading the source may not share. */
    public final String remark;

    static final int[][] D8 = {{1,0},{-1,0},{0,1},{0,-1},{1,1},{1,-1},{-1,1},{-1,-1}};

    private Oracle(Kind kind, double tauStar, double bi, double be, int[] path, int endCell,
                   boolean frameContact, String remark) {
        this.kind = kind; this.tauStar = tauStar; this.boundaryInclRecv = bi; this.boundaryExclRecv = be;
        this.path = path; this.endCell = endCell; this.remark = remark;
    }

    static boolean onFrame(int cell, int nx, int nz) {
        int i = cell / nz, j = cell % nz;
        return i == 0 || j == 0 || i == nx - 1 || j == nz - 1;
    }

    /** The subject seeds {@code start} unconditionally, so its own block flag is not consulted. */
    static boolean passable(OrderedSpillSearch.Guards g, int start, int cell) {
        return cell == start || !g.blocked(cell);
    }

    static boolean terminal(double[] bed, OrderedSpillSearch.Guards g, int start, int cell) {
        return cell != start && (g.receiver(cell) || bed[cell] < bed[start] - 1e-9);
    }

    static boolean stepOk(double[] bed, OrderedSpillSearch.Guards g, int start, int from, int to, double tau) {
        if (!passable(g, start, from) || !passable(g, start, to)) return false;
        if (Math.max(bed[from], bed[to]) > tau) return false;
        return !g.blockedEdge(from, to);
    }

    /**
     * Contract reading used as the reference (matches the two-phase design that was stated for the
     * subject, which is stricter than what the current source implements):
     *
     * <ul>
     *   <li>the endpoint is the terminal with the smallest (layer, world order) — i.e. the BFS picks
     *       it from the earliest layer, and a farther layer can never replace it;</li>
     *   <li>the result stays DOMAIN_PENDING when a same-crest unknown frame contact exists at a
     *       layer &lt;= the endpoint layer, or when a frame cell is strictly cheaper than the crest;</li>
     *   <li>a frame contact in a strictly farther layer does not block the certification.</li>
     * </ul>
     *
     * <p>{@link #remark} reports where the subject's behaviour departs from this reading.</p>
     */
    public static Oracle eval(double[] bed, int nx, int nz, int start, double rise, OrderedSpillSearch.Guards g) {
        int n = bed.length;
        double base = bed[start], cap = base + rise;

        // ---- pass 1: minimax crest per cell (lazy heap Dijkstra on the capped graph) ----
        double[] best = new double[n];
        Arrays.fill(best, Double.POSITIVE_INFINITY);
        boolean[] settled = new boolean[n];
        PriorityQueue<long[]> heap = new PriorityQueue<>(Comparator.comparingDouble((long[] e) -> Double.longBitsToDouble(e[0]))
                .thenComparingLong(e -> e[1]));
        best[start] = base;
        heap.add(new long[]{Double.doubleToLongBits(base), start});
        while (!heap.isEmpty()) {
            long[] e = heap.poll();
            double crest = Double.longBitsToDouble(e[0]);
            int k = (int) e[1];
            if (settled[k]) continue;
            settled[k] = true;
            if (k != start && g.receiver(k)) continue; // a known receiver owns all further flow
            int i = k / nz, j = k % nz;
            for (int[] d : D8) {
                int a = i + d[0], b = j + d[1];
                if (a < 0 || b < 0 || a >= nx || b >= nz) continue;
                int to = a * nz + b;
                if (settled[to]) continue;
                double c = Math.max(crest, bed[to]);
                if (c > cap || c >= best[to]) continue;
                if (!stepOk(bed, g, start, k, to, cap)) continue;
                best[to] = c;
                heap.add(new long[]{Double.doubleToLongBits(c), to});
            }
        }

        double tauStar = Double.POSITIVE_INFINITY;
        for (int k = 0; k < n; k++) {
            if (!terminal(bed, g, start, k)) continue;
            if (best[k] < tauStar) tauStar = best[k];
        }
        double bi = Double.POSITIVE_INFINITY, be = Double.POSITIVE_INFINITY;
        for (int k = 0; k < n; k++) {
            if (k == start || best[k] == Double.POSITIVE_INFINITY) continue;
            if (!onFrame(k, nx, nz)) continue;
            if (best[k] < bi) bi = best[k];
            if (!g.receiver(k) && best[k] < be) be = best[k];
        }

        if (tauStar == Double.POSITIVE_INFINITY) {
            Kind kind = bi == Double.POSITIVE_INFINITY ? Kind.NO_ESCAPE : Kind.DOMAIN_PENDING;
            return new Oracle(kind, Double.NaN, bi, be, new int[0], -1, false, "");
        }
        if (bi < tauStar) return new Oracle(Kind.DOMAIN_PENDING, tauStar, bi, be, new int[0], -1, false, "");

        // ---- pass 2: plain BFS at exactly tau* (distance + parent), terminals not expanded ----
        int[] dist = new int[n];
        int[] par = new int[n];
        Arrays.fill(dist, -1);
        Arrays.fill(par, -1);
        ArrayList<Integer> q = new ArrayList<>();
        dist[start] = 0; q.add(start);
        for (int head = 0; head < q.size(); head++) {
            int k = q.get(head);
            if (terminal(bed, g, start, k)) continue;
            int i = k / nz, j = k % nz;
            for (int[] d : D8) {
                int a = i + d[0], b = j + d[1];
                if (a < 0 || b < 0 || a >= nx || b >= nz) continue;
                int to = a * nz + b;
                if (dist[to] >= 0) continue;
                if (!stepOk(bed, g, start, k, to, tauStar)) continue;
                dist[to] = dist[k] + 1; par[to] = k;
                q.add(to);
            }
        }
        int pick = -1, pickLen = Integer.MAX_VALUE;
        for (int k = 0; k < n; k++) {
            if (!terminal(bed, g, start, k)) continue;
            if (best[k] != tauStar || dist[k] < 0) continue;
            if (dist[k] < pickLen || dist[k] == pickLen && k < pick) { pickLen = dist[k]; pick = k; }
        }
        // same-crest unknown contact at a layer <= the endpoint layer
        boolean blockAtOrBefore = false, blockAnywhere = false;
        if (pick >= 0) {
            for (int k = 0; k < n; k++) {
                if (k == start || g.receiver(k)) continue;
                if (!onFrame(k, nx, nz) || dist[k] < 0) continue;
                if (best[k] != tauStar) continue;
                blockAnywhere = true;
                if (dist[k] <= dist[pick]) blockAtOrBefore = true;
            }
        }
        String remark = "";
        if (pick < 0) {
            remark = "no terminal is reachable at exactly tau*";
            return new Oracle(Kind.DOMAIN_PENDING, tauStar, bi, be, new int[0], -1, blockAnywhere, remark);
        }
        if (blockAtOrBefore) {
            return new Oracle(Kind.DOMAIN_PENDING, tauStar, bi, be, new int[0], pick, blockAnywhere, "");
        }
        if (blockAnywhere) {
            remark = "same-crest frame contact exists at a farther layer than the endpoint "
                    + "(endpoint layer " + dist[pick] + ")";
        }
        ArrayList<Integer> chain = new ArrayList<>();
        for (int k = pick; k >= 0; k = par[k]) chain.add(k);
        int[] path = new int[chain.size()];
        for (int i = 0; i < path.length; i++) path[i] = chain.get(chain.size() - 1 - i);
        Kind kind = g.receiver(pick) ? Kind.JOIN : Kind.ESCAPE;
        Oracle self = new Oracle(kind, tauStar, bi, be, path, pick, blockAnywhere, remark);
        for (int k = 0; k < n; k++) {
            if (!terminal(bed, g, start, k)) continue;
            if (best[k] != tauStar) continue;
            self.terminals.add(new Terminal(k, dist[k], g.receiver(k)));
        }
        return self;
    }

    /** world (x, z) of a cell under the window frame, matching the subject's tie-break order. */
    public static long worldKey(int cell, int nz, int ox, int oz, int step) {
        long x = ox + cell / nz * step, z = oz + cell % nz * step;
        return (x << 32) ^ (z & 0xffffffffL);
    }

    /** world (x, z) of a cell under the window frame. */
    public static String world(int cell, int nz, int ox, int oz, int step) {
        return (ox + cell / nz * step) + "," + (oz + cell % nz * step);
    }
}
