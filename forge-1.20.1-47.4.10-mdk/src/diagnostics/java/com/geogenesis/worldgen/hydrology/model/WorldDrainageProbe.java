package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.hydrology.model.WorldDrainage.*;
import com.geogenesis.worldgen.hydrology.model.WindowOutlets.Terrain;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Diagnostic only: analytic contract tests for {@link WorldDrainage}; one new file, no production/Gradle edit;
 * any failed check exits 1. Fixture bed=r&lt;=64?+0.35:-2.0 scaled by (r-64) plus a bounded ripple, so the ridge
 * at r=64 is a watershed and the centre the only interior terminal; inside r&lt;=58 upstream sets are finite,
 * exact and lattice-independent (verified at LIM=80/200/400). Oracle = one FORWARD pass (own descent tree plus
 * up[u]=1+sum(up[child])); the implementation's reverse BFS is never re-run.
 */
public final class WorldDrainageProbe {
    private static final int STEP = 4, RIDGE = 64, LIM = 80, TRACT = 16, TRUST = 58, THR = 3, THR2 = 5, PATH = 64;
    private static final double SEA = -10000;
    private static final Comparator<Point> POINT = Comparator.comparingInt(Point::x).thenComparingInt(Point::z);
    private static final Comparator<Edge> EDGE = Comparator.comparing(Edge::from, POINT).thenComparing(Edge::to, POINT);
    private static final AtomicInteger CALLS = new AtomicInteger();
    private static int failures;

    public static void main(String[] args) throws Exception {
        Terrain t = WorldDrainageProbe::bowl;Grid g = new Grid();
        field(g);thresholds(g, t);downstream(g, t);merges(g, t);regions(g, t);traces(g, t);cache(t);cancel(t);exceptions();
        System.out.println(failures == 0 ? "ALL PASS" : "FAILURES=" + failures);
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void check(boolean ok, String what) {System.out.println((ok ? "[PASS] " : "[FAIL] ") + what);if (!ok) failures++;}
    private static Throwable trap(Runnable r) {try {r.run();return null;} catch (Throwable x) {return x;}}
    private static boolean threw(Runnable r, Class<? extends Throwable> type) {return type.isInstance(trap(r));}
    private static Map<Point, Double> memo() {return new LinkedHashMap<>();}
    private static double bowl(double x, double z) {
        double r = Math.hypot(x, z);
        return (r <= RIDGE ? 0.35 : -2.0) * (r - RIDGE) + 3.0 * Math.sin(x * 0.02) * Math.cos(z * 0.02);
    }
    private static double counted(Map<Point, Double> m, Terrain t, Point p) {
        Double o = m.get(p);if (o != null) return o;CALLS.incrementAndGet();
        double h = t.height(p.x(), p.z());m.put(p, h);return h;
    }
    private static WorldDrainage drain(int thr, Terrain t, Map<Point, Double> m) {
        return new WorldDrainage(STEP, thr, SEA, (x, z) -> counted(m, t, new Point((int) x, (int) z)));
    }
    private static boolean cropEquals(Result a, Result b) {
        return a.edges().equals(b.edges()) && a.pendingBasins().equals(b.pendingBasins());
    }

    /** Independent FORWARD oracle: own descent tree, children lists, exact upstream counts and depths. */
    private static final class Grid {
        final List<Point> pts;final Map<Point, Double> h = new LinkedHashMap<>();
        final Map<Point, Point> next = new LinkedHashMap<>();final Map<Point, List<Point>> kids = new LinkedHashMap<>();
        final Map<Point, Integer> up = new LinkedHashMap<>();final Map<Point, Integer> lens = new LinkedHashMap<>();
        Grid() {
            var all = new ArrayList<Point>();
            for (int x = -LIM; x <= LIM; x += STEP) for (int z = -LIM; z <= LIM; z += STEP) all.add(new Point(x, z));
            all.sort(POINT);pts = List.copyOf(all);
            for (var p : pts) h.put(p, bowl(p.x(), p.z()));
            var set = new HashSet<>(pts);
            for (var p : pts) {
                double best = 0;Point to = null;
                for (int dx = -STEP; dx <= STEP; dx += STEP) for (int dz = -STEP; dz <= STEP; dz += STEP) {
                    if (dx == 0 && dz == 0) continue;var q = new Point(p.x() + dx, p.z() + dz);
                    if (!set.contains(q)) continue;double d = (h.get(p) - h.get(q)) / Math.hypot(dx, dz);
                    if (d > best) {best = d;to = q;}
                }
                next.put(p, to);
            }
            for (var p : pts) kids.put(p, new ArrayList<>());
            for (var p : pts) if (next.get(p) != null) kids.get(next.get(p)).add(p);
            var desc = new ArrayList<>(pts);desc.sort(Comparator.comparingDouble(h::get));Collections.reverse(desc);
            for (var p : desc) {int u = 1;for (var c : kids.get(p)) u += up.get(c);up.put(p, u);}
            for (var p : pts) depth(p);
        }
        int depth(Point p) {
            Integer o = lens.get(p);if (o != null) return o;Point n = next.get(p);int v = n == null ? 1 : 1 + depth(n);
            lens.put(p, v);return v;
        }
        boolean region(Point p, int rx, int rz) {return Math.floorDiv(p.x(), TRACT) == rx && Math.floorDiv(p.z(), TRACT) == rz;}
        boolean trust(Point p) {return Math.hypot(p.x(), p.z()) <= TRUST;}
        List<Point> crop(int rx, int rz) {
            var out = new ArrayList<Point>();int ox = rx * TRACT, oz = rz * TRACT;
            for (int x = ox - STEP; x <= ox + TRACT; x += STEP) for (int z = oz - STEP; z <= oz + TRACT; z += STEP) out.add(new Point(x, z));
            out.sort(POINT);return out;
        }
    }

    private static void field(Grid g) {
        var d = drain(THR, WorldDrainageProbe::bowl, memo());boolean aligned = true, monotone = true;
        for (var p : g.pts) {
            if (Math.abs(p.x()) > LIM - STEP || Math.abs(p.z()) > LIM - STEP) continue;Step s = d.at(p);
            if (!Objects.equals(g.next.get(p), s.next()) || s.bed() != g.h.get(p)) aligned = false;
            if (s.next() != null && !(g.h.get(s.next()) < s.bed())) monotone = false;
        }
        check(monotone, "fixture: every successor is strictly lower, so the flow graph is acyclic");
        check(aligned, "fixture: at() steepest descent and bed match the independent forward oracle");
        check(g.up.get(new Point(0, 0)) == 725 && g.up.get(new Point(4, 0)) == 171 && g.up.get(new Point(12, 0)) == 13
                && g.up.get(new Point(48, 0)) == 4 && g.up.get(new Point(56, 0)) == 2 && g.depth(new Point(56, 0)) == 15,
                "fixture: independent anchor counts match the computed oracle (0,0)=725 (12,0)=13 (56,0)=2");
        check(g.trust(new Point(48, 0)) && g.trust(new Point(56, 0)) && !g.trust(new Point(76, 0)),
                "fixture: threshold anchors sit inside the trustworthy, lattice-independent band");
    }

    /** Saturation cut: exact predicate and exact distinct-cell count on both sides of the cut. */
    private static void thresholds(Grid g, Terrain t) {
        var d = drain(THR, t, memo());var d2 = drain(THR2, t, memo());int bad = 0, chan = 0, sat = 0, under = 0;
        for (var p : g.pts) {
            if (!g.trust(p)) continue;
            var a = d.admission(p);var b = d2.admission(p);int u = g.up.get(p);
            if (a.channel() != (u >= THR) || a.uniqueCells() != Math.min(u, THR) || a.complete() != (u < THR)) bad++;
            if (b.channel() != (u >= THR2) || b.uniqueCells() != Math.min(u, THR2) || b.complete() != (u < THR2)) bad++;
            if (a.channel()) chan++;if (u == THR2) sat++;if (u == THR2 - 1) under++;
        }
        check(bad == 0, "admission == exact upstream-count oracle, no duplicate/overlapping runoff (bad=" + bad + ")");
        check(chan > 0, "threshold admits a non-empty strict subset (channels=" + chan + ")");
        check(sat > 0 && under > 0, "saturation is exact on both sides of the cut (sat=" + sat + " under=" + under + ")");
    }

    /** Downstream monotonicity: bed strictly falls along the channel until the terminal. */
    private static void downstream(Grid g, Terrain t) {
        var d = drain(THR, t, memo());boolean falls = true, one = true;int checked = 0;
        for (var p : g.pts) {
            if (!g.trust(p) || g.next.get(p) == null) continue;Step s = d.at(p);Step n = d.at(s.next());
            if (!(n.bed() < s.bed())) falls = false;if (!Objects.equals(s.next(), n.point())) one = false;checked++;
        }
        check(falls && checked > 0, "downstream bed strictly decreases along every trusted channel hop (n=" + checked + ")");
        check(one, "successor identity is stable: at(next).point() == next");
        List<Point> path = d.trace(new Point(56, 0), PATH);boolean monotonePath = true;
        for (int i = 1; i < path.size(); i++) if (!(g.h.get(path.get(i)) < g.h.get(path.get(i - 1)))) monotonePath = false;
        check(monotonePath && path.get(path.size() - 1).equals(new Point(0, 0)) && path.size() == 15,
                "trace from (56,0) is strictly falling and lands exactly on the centre sink in 15 nodes");
    }

    /** Merging branches: two incoming channels union upstream counts and share one downstream channel. */
    private static void merges(Grid g, Terrain t) {
        var d = drain(THR, t, memo());Point join = null;
        for (var p : g.pts) if (g.trust(p) && g.kids.get(p).size() >= 2 && g.up.get(p) >= THR && g.region(p, -1, -1)) join = p;
        check(join != null, "fixture exposes a trusted confluence with two branches (-28,-8)");
        if (join == null) return;
        var branch = g.kids.get(join);int union = 1;for (var b : branch) union += g.up.get(b);
        check(union == g.up.get(join) && d.admission(join).uniqueCells() == THR && d.admission(join).channel(),
                "confluence upstream count is the union of its branches, not a duplicate count");
        boolean shared = true;for (var b : branch) shared &= Objects.equals(d.at(b).next(), join);
        check(shared, "both branch heads route into the single downstream node of the confluence");
        List<Point> pa = d.trace(branch.get(0), PATH), pb = d.trace(branch.get(1), PATH), pj = d.trace(join, PATH);
        check(subsequence(pj, pa) && subsequence(pj, pb) && pj.get(0).equals(join),
                "both branch paths reconverge on the same downstream trace after the join");
    }
    private static boolean subsequence(List<Point> a, List<Point> b) {
        int i = 0;for (var p : b) if (i < a.size() && a.get(i).equals(p)) i++;return i == a.size();
    }

    private static void regions(Grid g, Terrain t) {
        var d = drain(THR, t, memo());int bad = 0;
        for (int[] r : new int[][]{{0, 0}, {1, 0}, {0, 1}, {-1, -1}, {-1, 0}, {0, -1}})
            if (!cropEquals(d.region(r[0], r[1], TRACT), oracleCrop(g, r[0], r[1]))) bad++;
        check(bad == 0, "region crop equals the forward oracle over 6 crops incl. negatives (bad=" + bad + ")");
        Edge x = new Edge(new Point(16, 4), new Point(12, 4));
        check(drain(THR, t, memo()).region(0, 0, TRACT).edges().contains(x)
                && drain(THR, t, memo()).region(1, 0, TRACT).edges().contains(x),
                "seam-crossing edge keeps one shared identity on both sides of the window");
        var saddle = new WorldDrainage(4, 1, -1000, (xx, zz) -> -xx + zz);
        var antiDiagonal = new Edge(new Point(-4, 0), new Point(0, -4));
        check(saddle.region(-1, -1, 16).edges().contains(antiDiagonal),
                "anti-diagonal at negative corner covers intermediate columns even with both endpoints outside core");
        boolean corners = true;
        for (int[] o : new int[][]{{0, 0}, {16, -16}, {-32, 32}}) for (int direction : new int[]{-1, 1}) {
            var field = new WorldDrainage(4, 1, -10000, (xx, zz) -> direction * (-xx + zz));
            var a = new Point(o[0] - 4, o[1]); var b = new Point(o[0], o[1] - 4);
            var edge = direction > 0 ? new Edge(a, b) : new Edge(b, a);
            corners &= field.region(Math.floorDiv(o[0], 16) - 1, Math.floorDiv(o[1], 16) - 1, 16).edges().contains(edge);
        }
        check(corners, "both anti-diagonal directions survive positive/negative translated corner crops");
    }
    private static Result oracleCrop(Grid g, int rx, int rz) {
        var e = new ArrayList<Edge>();var p = new ArrayList<Point>();
        for (var c : g.crop(rx, rz)) {
            Point n = g.next.get(c);
            if (n == null) {if (g.region(c, rx, rz) && g.up.get(c) >= THR) p.add(c);continue;}
            boolean covered = false;
            for (int i = 0; i <= STEP; i++)
                covered |= g.region(new Point(c.x() + (n.x() - c.x()) * i / STEP,
                        c.z() + (n.z() - c.z()) * i / STEP), rx, rz);
            if (covered && g.up.get(c) >= THR) e.add(new Edge(c, n));
        }
        e.sort(EDGE);p.sort(POINT);return new Result(e, p, 0, 0);
    }

    private static void traces(Grid g, Terrain t) {
        var d = drain(THR, t, memo());
        check(traceOk(g, d, new Point(12, 0), 4), "trace from channel source (12,0) is deterministic and ends at the centre");
        check(traceOk(g, d, new Point(-48, 0), 13) && traceOk(g, d, new Point(48, 0), 13),
                "trace identity is stable on negative-coordinate and mirrored sources (-48,0)/(48,0)");
        check(traceOk(g, d, new Point(-40, -12), 11) && traceOk(g, d, new Point(20, -20), 6),
                "trace identity is stable across mixed-sign and off-axis sources");
    }
    private static boolean traceOk(Grid g, WorldDrainage d, Point src, int len) {
        List<Point> a = d.trace(src, PATH);
        return a.equals(d.trace(src, PATH)) && a.size() == len && g.depth(src) == len
                && a.get(a.size() - 1).equals(new Point(0, 0)) && d.admission(src).channel();
    }

    private static void cache(Terrain t) throws Exception {
        Point c = new Point(0, 0);var d = drain(THR, t, memo());
        d.region(0, 0, TRACT);int base = CALLS.get();d.region(0, 0, TRACT);d.admission(c);d.trace(c, PATH);
        check(CALLS.get() == base, "hot cache: repeat region/admission/trace issues zero new terrain samples");
        CALLS.set(0);var cold = drain(THR, t, memo());
        cold.region(0, 0, TRACT);int first = CALLS.get();cold.region(0, 0, TRACT);
        check(first > 0 && CALLS.get() == first, "cold then warm: each node sampled once, then cached");
        var ra = drain(THR, t, memo()).region(0, 0, TRACT);var rb = drain(THR, t, memo()).region(1, 0, TRACT);
        var db = drain(THR, t, memo());var rb2 = db.region(1, 0, TRACT);var ra2 = db.region(0, 0, TRACT);
        // Compare crop content, not whole Result: the counters legitimately differ by query order.
        check(cropEquals(ra, ra2) && cropEquals(rb, rb2), "reversed query order yields identical crops (cache is order-independent)");
        var pool = Executors.newFixedThreadPool(3);
        try {
            var jobs = new ArrayList<Future<Result>>();
            for (int i = 0; i < 3; i++) jobs.add(pool.submit(() -> drain(THR, t, memo()).region(0, 0, TRACT)));
            boolean same = true;for (var j : jobs) same &= ra.equals(j.get(30, TimeUnit.SECONDS));
            check(same, "independent concurrent instances agree with the single-threaded crop");
        } finally {pool.shutdownNow();}
    }

    private static void cancel(Terrain t) {
        var d = drain(THR, t, memo());
        Throwable c = trap(() -> {Thread.currentThread().interrupt();d.region(0, 0, TRACT);});
        Thread.interrupted();
        check(c instanceof CancellationException, "interrupted thread surfaces CancellationException");
        check(d.region(0, 0, TRACT).equals(drain(THR, t, memo()).region(0, 0, TRACT)),
                "retry after cancellation returns the same crop");
    }

    private static void exceptions() {
        check(threw(() -> new WorldDrainage(0, THR, SEA, (x, z) -> 0), IllegalArgumentException.class), "rejects non-positive step");
        check(threw(() -> new WorldDrainage(STEP, 4097, SEA, (x, z) -> 0), IllegalArgumentException.class), "rejects out-of-range threshold");
        check(threw(() -> new WorldDrainage(STEP, THR, Double.NaN, (x, z) -> 0), IllegalArgumentException.class), "rejects nonfinite sea level");
        var d = drain(THR, WorldDrainageProbe::bowl, memo());
        check(threw(() -> d.at(new Point(2, 0)), IllegalArgumentException.class), "rejects non-lattice point");
        check(threw(() -> d.region(0, 0, 15), IllegalArgumentException.class), "rejects a misaligned region tract");
        check(threw(() -> new WorldDrainage(STEP, THR, SEA, (x, z) -> Double.NaN).at(new Point(0, 0)),
                IllegalArgumentException.class), "nonfinite terrain throws instead of going dry");
        check(threw(() -> new WorldDrainage(STEP, THR, SEA, (x, z) -> Double.POSITIVE_INFINITY).at(new Point(0, 0)),
                IllegalArgumentException.class), "infinite terrain throws instead of going dry");
        // Spaced points: each at() samples ~9 fresh cells, reaching the 524288-node cap.
        var b = new WorldDrainage(STEP, THR, SEA, (x, z) -> 5);boolean budget = false;
        try {for (int i = 0; i < 60000 && !budget; i++) b.at(new Point(i * 1000, 0));} catch (IllegalStateException ex) {budget = true;}
        check(budget, "world terrain budget exhaustion throws, not a silent dry result");
        check(threw(() -> d.trace(new Point(0, 0), 0), IllegalArgumentException.class), "rejects a zero trace budget");
        check(threw(() -> d.trace(new Point(0, 0), 131073), IllegalArgumentException.class), "rejects an over-large trace budget");
    }
}
