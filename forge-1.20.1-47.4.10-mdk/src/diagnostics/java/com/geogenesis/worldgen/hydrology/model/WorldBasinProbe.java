package com.geogenesis.worldgen.hydrology.model;

import com.geogenesis.worldgen.hydrology.model.LakeWaterBalance.Climate;
import com.geogenesis.worldgen.hydrology.model.WorldBasin.Face;
import com.geogenesis.worldgen.hydrology.model.WorldBasin.Reach;
import com.geogenesis.worldgen.hydrology.model.WorldDrainage.Point;
import java.util.*;
import java.util.concurrent.CancellationException;

/**
 * Diagnostic only: independent analytic tests for {@link WorldBasin}. One new file, no production/Gradle edit;
 * any failed check exits 1. Terrain is a plain map, no MC. Assertions are strict: a real defect is reported,
 * never loosened.
 *
 * <p>The contract under test: sampled geometry reaching a below-sea node is <b>not</b> a water licence. A
 * downstream pan can evaporate the cascade dry (downstream-dry-sea-cascade negative), a downstream sill strictly
 * above the <b>initial</b> sill is backwater, and a chosen merge sill that does not actually join the two pans is
 * pending. Only a de-duplicated cascade with a positive downstream surplus at the given climate is released.
 * Step-4 checks confirm the crest evidence covers the intermediate integer columns of a coarse D8 edge.
 */
public final class WorldBasinProbe {
    private static final int STEP = 1;
    private static final double P = 0.004, E = 0.002, C = 0.3, E3 = 0.0001, EDRY = 0.5;
    private static final Comparator<Point> ORDER = Comparator.comparingInt(Point::x).thenComparingInt(Point::z);
    private static final Map<Point, Double> T = new HashMap<>();
    private static double def = 10.0;
    private static int failures;

    public static void main(String[] args) throws Exception {
        strictMinimum(); uniqueRim(); tieAndPending(); canonical(); degenerate(); runoffBudget();
        closedBalance(); geometryReach(); dryCascadeNotLicensed(); backwaterContract(); stepFourCrests();
        mergeUnion(); mergeDisconnected(); pendingReasons(); prefixMustFlow(); integerSampleBudget(); cancelRetry();
        System.out.println(failures == 0 ? "ALL PASS" : "FAILURES=" + failures);
        System.exit(failures == 0 ? 0 : 1);
    }

    // ---- harness ------------------------------------------------------------------------------------
    private static void check(boolean ok, String what) {System.out.println((ok ? "[PASS] " : "[FAIL] ") + what);if (!ok) failures++;}
    private static Throwable trap(Runnable r) {try {r.run();return null;} catch (Throwable x) {return x;}}
    private static boolean threw(Runnable r, Class<? extends Throwable> type) {return type.isInstance(trap(r));}
    private static double h(double x, double z) {return T.getOrDefault(new Point((int) x, (int) z), def);}
    private static WindowOutlets.Terrain terrain() {return (x, z) -> h(x, z);}
    private static void put(int x, int z, double v) {T.put(new Point(x, z), v);}
    private static void rim(int r, double v) {for (int x = -r; x <= r; x++) for (int z = -r; z <= r; z++) if (Math.max(Math.abs(x), Math.abs(z)) == r) put(x, z, v);}
    private static WorldDrainage drain(double sea) {return new WorldDrainage(STEP, 1, sea, terrain());}
    private static WorldBasin basin(double sea, int limit) {return new WorldBasin(drain(sea), terrain(), STEP, limit);}
    private static Climate cl(double e) {return new Climate(P, e, C);}

    // ---- independent oracle (documented spec, not the implementation's private helpers) -------------
    private static double crest(Point a, Point b) {return Math.max(h(a.x(), a.z()), h(b.x(), b.z()));}
    private static Set<Point> flood(Point sink, double sill) {
        var m = new HashSet<Point>();var q = new ArrayDeque<Point>();m.add(sink);q.add(sink);
        while (!q.isEmpty()) {var p = q.remove();
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;var n = new Point(p.x() + dx, p.z() + dz);
                if (!m.contains(n) && crest(p, n) < sill) {m.add(n);q.add(n);}
            }}
        return m;
    }
    private static List<Face> rimFaces(Set<Point> m, double sill) {
        var out = new ArrayList<Face>();
        for (var p : m) for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            if (dx == 0 && dz == 0) continue;var n = new Point(p.x() + dx, p.z() + dz);
            if (!m.contains(n) && crest(p, n) == sill) out.add(new Face(p, n, sill));
        }
        out.sort(Comparator.comparing(Face::inner, ORDER).thenComparing(Face::outer, ORDER));return out;
    }
    private static Set<Point> upstream(WorldDrainage d, Collection<Point> members, int cap) {
        var s = new HashSet<Point>(members);var q = new ArrayDeque<Point>(members);
        while (!q.isEmpty()) {var p = q.remove();
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;var n = new Point(p.x() + dx, p.z() + dz);
                if (s.contains(n)) continue;var st = d.at(n);
                if (st.next() != null && st.next().equals(p)) {if (s.size() >= cap) return s;s.add(n);q.add(n);}
            }}
        return s;
    }

    // ---- fixtures -----------------------------------------------------------------------------------
    private static void fA() {T.clear();def = 10;put(0, 0, 0);put(1, 0, 2);put(2, 0, -1);long[][] r = {{-1, -1}, {-1, 0}, {-1, 1}, {0, -1}, {0, 1}, {1, -1}, {1, 1}};
        for (var c : r) put((int) c[0], (int) c[1], 10);for (int k = 2; k <= 30; k++) {put(0, k, 10 + k);put(0, -k, 10 + k);}}
    private static void fC() {fA();put(0, 1, 2);put(0, 2, -1);}
    private static void fD() {T.clear();def = 50;for (int x = 0; x < 3; x++) for (int z = 0; z < 2; z++) put(x, z, 0);
        for (int x = -1; x <= 3; x++) for (int z = -1; z <= 2; z++) if (x < 0 || z < 0 || x > 2 || z > 1) put(x, z, 5);put(3, 0, 4);put(4, 0, -1);}
    private static void fE() {T.clear();def = 50;put(0, 0, 0);rim(1, 5);rim(2, 5);rim(3, 5);}
    private static void fDeg() {T.clear();def = 10;put(0, 0, 0);put(1, 0, 0);put(2, 0, 0);put(3, 0, -1);
        long[][] r = {{-1, -1}, {-1, 0}, {-1, 1}, {0, -1}, {0, 1}, {1, -1}, {1, 1}, {2, -1}, {2, 1}};for (var c : r) put((int) c[0], (int) c[1], 5);}
    private static void fR() {T.clear();def = 50;put(0, 0, 0);put(0, -1, 1);put(0, -2, -1);
        for (int k = 1; k <= 60; k++) {put(k, 0, 10 + k);put(k, 1, 10 + k);put(k, -1, 10 + k);}
        long[][] r = {{0, 1}, {-1, 0}, {-1, 1}, {1, 0}, {-1, -1}, {1, 1}, {1, -1}};for (var c : r) put((int) c[0], (int) c[1], 10);}
    /** Sea basin: floor 0, shoulder 1, then a node below sea ⇒ a single-hop sampled descent to the sea. */
    private static void fSea() {T.clear();def = 10;put(0, 0, 0);put(1, 0, 1);put(2, 0, -5);}
    /** Two bowls in a one-wide trench: A (sill 2) spills into B (sill 5 > 2, and B's flood encloses A). */
    private static void fTail() {T.clear();def = 10;put(0, 0, 0);put(1, 0, 2);put(2, 0, -2);put(3, 0, 5);put(4, 0, -3);}
    /** Two-step staircase to the sea (sea = -1): A (floor 0, sill 2) → B (floor -1, sill 1.5) → below-sea node. */
    private static void fStair() {T.clear();def = 10;put(0, 0, 0);put(1, 0, 2);put(2, 0, -1);put(3, 0, 1.5);put(4, 0, -5);}
    /**
     * Four-step cascade to the sea with <b>rising intermediate sills</b>: A sill 4 → B sill 2 → C sill 3 → D sill 1
     * → below-sea. C's sill (3) is above B's (2) but not above the initial sill (4), so the chain must stay
     * SAMPLED_TO_SEA — the regression that forbids comparing against the previous sill instead of the initial one.
     */
    private static void fRise() {T.clear();def = 10;put(0, 0, 0);put(1, 0, 4);put(2, 0, -1);put(3, 0, 2);
        put(4, 0, -3);put(5, 0, 3);put(6, 0, -5);put(7, 0, 1);put(8, 0, -100);}
    /**
     * A tiny upper pan A (floor 0, sill 2) spills into a <b>large</b> downstream pan B (an 11-cell trench at -1,
     * sill 1) which then descends to a below-sea node. Geometry reaches the sea, but a high evaporation over the
     * big downstream pan can still dry the cascade — the false-green case the licence must refuse.
     */
    private static void fDry() {T.clear();def = 10;put(0, 0, 0);put(1, 0, 2);
        for (int x = 2; x <= 12; x++) put(x, 0, -1);
        put(13, 0, 1);put(14, 0, -1000);}
    /** Two separated bowls of equal sill 2 whose floods never join (flat 10 ridge between them). */
    private static void fSplit() {T.clear();def = 10;put(-10, 0, 0);put(-9, 0, 2);put(-8, 0, -5);put(10, 0, 0);put(11, 0, 2);put(12, 0, -5);}

    // ---- lowest-cut / rim / budget tests (unchanged contract) ----------------------------------------
    private static void strictMinimum() {
        T.clear();def = 10;put(0, 0, 0);put(1, 0, -1);
        check(threw(() -> basin(-10000, 100000).resolve(new Point(0, 0)), IllegalArgumentException.class),
                "sink strict minimum: a descending sink is rejected, not treated as a basin");
        T.clear();put(0, 0, 0);rim(1, 5);
        check(threw(() -> basin(100, 100000).resolve(new Point(0, 0)), IllegalArgumentException.class),
                "sink strict minimum: a below-sea sink is rejected");
    }

    private static void uniqueRim() {
        fA();var g = basin(-10000, 100000).resolve(new Point(0, 0));
        check(g.state() == WorldBasin.State.UNIQUE_EXIT && g.sill() == 2.0 && g.members().size() == 1
                && g.exits().size() == 1 && g.identity().equals(new Point(0, 0))
                && g.path().equals(List.of(new Point(0, 0), new Point(1, 0), new Point(2, 0))),
                "unique exit: sill/identity/single-face/path resolved correctly");
        check(g.sill() > h(0, 0) && g.floor() == h(0, 0), "unique exit: sill strictly above the recorded floor");
        check(new HashSet<>(g.members()).equals(flood(new Point(0, 0), g.sill())) && g.exits().equals(rimFaces(new HashSet<>(g.members()), g.sill())),
                "unique exit: footprint and rim are complete against the independent oracle");
    }

    private static void tieAndPending() {
        fC();var t = basin(-10000, 100000).resolve(new Point(0, 0));
        check(t.state() == WorldBasin.State.MULTIPLE_EXITS && t.exits().size() == 2, "tied exits -> MULTIPLE_EXITS with two faces");
        check(basin(-10000, 100000).budget(t, cl(E)).state() == WorldBasin.State.GEOMETRY_PENDING,
                "tied/unknown basin never becomes CLOSED or OVERFLOW");
        fDeg();var d = basin(-10000, 100000).resolve(new Point(0, 0));
        check(d.state() == WorldBasin.State.GEOMETRY_PENDING && d.members().isEmpty() && d.exits().isEmpty()
                && d.identity() == null && d.path().isEmpty(),
                "draining flat: definite geometric answer, no fabricated members/exits/identity");
        check(basin(-10000, 100000).budget(d, cl(E)).state() == WorldBasin.State.GEOMETRY_PENDING,
                "pending geometry budget stays GEOMETRY_PENDING, never CLOSED");
        fA();var b9 = basin(-10000, 9).resolve(new Point(0, 0));
        check(b9.state() == WorldBasin.State.EXHAUSTED && b9.exits().isEmpty() && b9.members().isEmpty()
                && b9.identity() == null, "budget-exhausted resolve of a resolvable basin fabricates no exits");
        fE();var sealed = basin(-10000, 500).resolve(new Point(0, 0));
        check(sealed.state() == WorldBasin.State.EXHAUSTED && sealed.exits().isEmpty() && sealed.identity() == null,
                "★ a sealed pan with no lower node reachable within budget is EXHAUSTED, not a fabricated sill");
    }

    private static void canonical() {
        fD();var a = basin(-10000, 100000).resolve(new Point(0, 0));
        var b = basin(-10000, 100000).resolve(new Point(2, 0));
        var c = basin(-10000, 100000).resolve(new Point(2, 1));
        check(a.identity().equals(b.identity()) && b.identity().equals(c.identity())
                && new HashSet<>(a.members()).equals(new HashSet<>(b.members()))
                && new HashSet<>(b.members()).equals(new HashSet<>(c.members())) && a.state() == b.state() && b.state() == c.state(),
                "same basin, different accurate sink entries -> one canonical identity and footprint");
    }

    private static void degenerate() {
        // Flat floor (0,0)/(1,0)/(2,0)=0 draining to (3,0)=-1 along an equal-level chain. A resolved depression must
        // have a spill strictly above its floor and no floor-level lip; two accurate sinks of one floor must agree.
        fDeg();var a = basin(-10000, 100000).resolve(new Point(0, 0));double bottom = h(0, 0);
        boolean resolved = a.state() == WorldBasin.State.UNIQUE_EXIT || a.state() == WorldBasin.State.MULTIPLE_EXITS;
        boolean lipAtFloor = false;for (var f : a.exits()) if (f.crest() <= bottom) lipAtFloor = true;
        check(!resolved || (a.sill() > bottom && !lipAtFloor), "equal-level floor: a resolved spill must be above the "
                + "floor with no floor-level exit lip (state=" + a.state() + ", sill=" + a.sill() + ", floor=" + bottom
                + ", floorLevelLip=" + lipAtFloor + ")");
        var b = basin(-10000, 100000).resolve(new Point(1, 0));
        boolean same = a.state() == b.state() && Objects.equals(a.identity(), b.identity())
                && new HashSet<>(a.members()).equals(new HashSet<>(b.members()));
        boolean bothPending = a.state() == WorldBasin.State.GEOMETRY_PENDING && b.state() == WorldBasin.State.GEOMETRY_PENDING;
        check(same || bothPending, "equal-level floor: accurate sinks (0,0) and (1,0) of one floor must resolve "
                + "canonically or both pend (" + a.state() + "@" + a.identity() + " vs " + b.state() + "@" + b.identity() + ")");
    }

    private static void runoffBudget() {
        fR();var g = basin(-10000, 100000).resolve(new Point(0, 0));
        check(g.state() == WorldBasin.State.UNIQUE_EXIT && g.members().size() == 1, "runoff fixture: small basin, unique exit");
        var ok = basin(-10000, 100000).budget(g, cl(E));
        var cat = upstream(drain(-10000), g.members(), 100000);
        check(ok.uniqueRunoffCells() == cat.size(), "runoff cell count equals the independent catchment oracle");
        check(ok.inflow() == cat.size() * 1.0 * P * C, "steady inflow = P*C*each unique catchment area, not lake rain");
        check(ok.evaporation() == g.members().size() * 1.0 * E, "steady loss = E * each basin member area");
        check(ok.release() == Math.max(0, ok.inflow() - ok.evaporation())
                && ok.state() == (ok.release() > 0 ? WorldBasin.State.OVERFLOW : WorldBasin.State.CLOSED),
                "release and state follow the steady balance (overflow iff inflow exceeds loss)");
        var t = basin(-10000, 30).resolve(new Point(0, 0));
        var tb = basin(-10000, 30).budget(t, cl(E));
        check(t.state() == WorldBasin.State.UNIQUE_EXIT && tb.state() == WorldBasin.State.RUNOFF_PENDING
                && tb.uniqueRunoffCells() == 30 && !Double.isFinite(tb.release()) && !Double.isFinite(tb.inflow())
                && tb.state() != WorldBasin.State.CLOSED, "truncated catchment -> RUNOFF_PENDING with no release, never CLOSED");
    }

    private static void closedBalance() {
        fA();var wb = basin(-10000, 100000);var g = wb.resolve(new Point(0, 0));
        var wet = wb.budget(g, cl(0));
        check(wet.state() == WorldBasin.State.OVERFLOW && wet.release() == wet.inflow()
                && wet.evaporation() == 0 && wet.inflow() > 0, "zero evaporation: every drop runs off, release == inflow > 0");
        double breakeven = wet.inflow() / g.members().size();
        var atBreakeven = wb.budget(g, cl(breakeven));
        check(atBreakeven.state() == WorldBasin.State.CLOSED && atBreakeven.release() == 0
                && Math.abs(atBreakeven.evaporation() - atBreakeven.inflow()) < 1e-12,
                "evaporation at break-even: zero release, CLOSED");
        var dry = wb.budget(g, cl(breakeven * 2));
        check(dry.state() == WorldBasin.State.CLOSED && dry.release() == 0 && dry.inflow() < dry.evaporation(),
                "inflow below loss: CLOSED with zero release");
        check(threw(() -> wb.budget(g, new Climate(Double.NaN, E, C)), IllegalArgumentException.class),
                "NaN climate cannot become a false CLOSED basin");
    }

    // ---- sampled geometry vs water licence -----------------------------------------------------------
    private static void geometryReach() {
        fStair();var wb = basin(-1, 100000);var ga = wb.resolve(new Point(0, 0));
        check(ga.state() == WorldBasin.State.UNIQUE_EXIT && ga.sill() == 2.0, "stair: A settled with sill 2");
        var cert = wb.certify(new Point(0, 0));
        check(cert.reach() == Reach.SAMPLED_TO_SEA && cert.catchmentComplete(),
                "stair: two-hop sampled chain reaching a below-sea node names SAMPLED_TO_SEA");
        check(cert.chain().subList(0, ga.path().size()).equals(ga.path()),
                "★ evidence path retains the basin's own g.path storage segment as its prefix");
        check(cert.chain().equals(List.of(new Point(0, 0), new Point(1, 0), new Point(2, 0), new Point(3, 0), new Point(4, 0))),
                "★ evidence path lists every crossed sampled node through both pans to the sea node");
        check(cert.chainComplete() && cert.crests().size() == cert.chain().size() - 1,
                "★ evidence records one crest per hop, no gap");
        check(cert.crests().equals(List.of(crest(new Point(0, 0), new Point(1, 0)), crest(new Point(1, 0), new Point(2, 0)),
                        crest(new Point(2, 0), new Point(3, 0)), crest(new Point(3, 0), new Point(4, 0)))),
                "★ every recorded crest equals the independent oracle crest");
        check(drain(-1).at(cert.chain().get(cert.chain().size() - 1)).state() == WorldDrainage.State.BELOW_SEA,
                "★ evidence is re-checkable: the last chained node really is below sea (project h<sea convention)");
        fSea();var ws = basin(-1, 100000);var sc = ws.certify(new Point(0, 0));
        check(sc.reach() == Reach.SAMPLED_TO_SEA && sc.chain().equals(List.of(new Point(0, 0), new Point(1, 0), new Point(2, 0))),
                "single-hop sea fixture also names SAMPLED_TO_SEA with its full path");
        var rel = ws.release(new Point(0, 0), cl(E3));
        check(rel.released() && rel.reach() == Reach.SAMPLED_TO_SEA && rel.chain().equals(sc.chain()),
                "★ non-trivial control: a certified sea cascade with a surplus IS released (not all-pending)");
    }

    private static void dryCascadeNotLicensed() {
        fDry();var wb = basin(-500, 100000);var ga = wb.resolve(new Point(0, 0));
        check(ga.state() == WorldBasin.State.UNIQUE_EXIT && ga.sill() == 2.0 && ga.spill() != null
                && ga.spill().target().equals(new Point(2, 0)), "dry: upper pan A (sill 2) spills into the big lower pan");
        var gb = wb.resolve(new Point(2, 0));
        check(gb.state() == WorldBasin.State.UNIQUE_EXIT && gb.sill() == 1.0 && gb.members().size() == 11,
                "dry: the downstream pan B is settled with a large footprint (11 cells) and sill 1");
        var cert = wb.certify(new Point(0, 0));
        check(cert.reach() == Reach.SAMPLED_TO_SEA && cert.catchmentComplete() && cert.chainComplete(),
                "★ dry: geometry is genuinely sampled to sea with complete evidence");
        var wet = wb.release(new Point(0, 0), cl(E3));
        var dry = wb.release(new Point(0, 0), cl(EDRY));
        check(wet.released(), "★ low evaporation: the same cascade keeps a downstream surplus ⇒ released");
        check(!dry.released() && dry.reach() == Reach.SAMPLED_TO_SEA,
                "★★ downstream-dry-sea-cascade NEGATIVE: geometry to sea but high downstream evaporation ⇒ NOT released");
        check(!dry.released() && dry.reason().contains("net water"),
                "★ the refusal is a water-balance reason, not a geometry reason");
    }

    private static void backwaterContract() {
        fTail();var wb = basin(-10000, 100000);
        var cert = wb.certify(new Point(0, 0));
        check(cert.reach() == Reach.BACKWATER && cert.operativeSill() == 5.0,
                "tailwater: a downstream sill above the initial sill ⇒ BACKWATER with operative sill 5");
        var back = wb.release(new Point(0, 0), cl(E3));
        check(!back.released(), "★ backwater refuses release (no false green)");
        fRise();var wr = basin(-50, 100000);var ra = wr.resolve(new Point(0, 0));
        check(ra.state() == WorldBasin.State.UNIQUE_EXIT && ra.sill() == 4.0, "rise: A sill 4");
        check(wr.resolve(new Point(2, 0)).sill() == 2.0 && wr.resolve(new Point(4, 0)).sill() == 3.0,
                "rise: B sill 2 then C sill 3 (below the initial sill ⇒ the chain must keep going)");
        var rc = wr.certify(new Point(0, 0));
        check(rc.reach() == Reach.SAMPLED_TO_SEA && rc.operativeSill() == 4.0,
                "★ a rise that stays below the INITIAL sill is not backwater (regression: not 'previous sill')");
    }

    private static void stepFourCrests() {
        // Step-4 lattice: (0,0)=0 sink, (4,0)=1, (8,0)=-1 basin node; hidden bumps (2,0)=7 on the (0,0)-(4,0) edge
        // and (5,0..7,0)=1 on the (4,0)-(8,0) edge. Endpoint-only crests would read 1, the true midpoints read 7.
        T.clear();def = 10;put(0, 0, 0);put(1, 0, 1);put(2, 0, 7);put(3, 0, 1);
        put(4, 0, 1);put(5, 0, 1);put(6, 0, 1);put(7, 0, 1);put(8, 0, -1);
        var wb = new WorldBasin(new WorldDrainage(4, 1, -10000, terrain()), terrain(), 4, 2000);
        var g = wb.resolve(new Point(0, 0));
        check(g.state() == WorldBasin.State.UNIQUE_EXIT && g.sill() == 7.0,
                "★ step-4: the spill honours the hidden integer-column bump (sill 7, endpoint-only would read 1)");
        var cert = wb.certify(new Point(0, 0));
        check(cert.crests().size() >= 1 && cert.crests().get(0) == 7.0,
                "★ step-4: the recorded hop crest is the true column maximum, not the coarse endpoint maximum");
        boolean lattice = true;
        for (var p : cert.chain()) lattice &= Math.floorMod(p.x(), 4) == 0 && Math.floorMod(p.z(), 4) == 0;
        check(lattice && cert.chain().get(0).equals(new Point(0, 0)),
                "★ step-4: every evidence node is an exact D8 lattice node at the world step");
    }

    // ---- merge --------------------------------------------------------------------------------------
    private static void mergeUnion() {
        fTail();var wb = basin(-10000, 100000);
        var m = wb.merge(new Point(0, 0), new Point(2, 0));
        check(m.state() == WorldBasin.State.UNIQUE_EXIT && m.sill() == 5.0 && m.floor() == -2.0 && m.connected(),
                "merge of nested pans resolves to one connected basin at the higher sill / lower floor");
        check(m.overlap() == m.naiveCells() - m.uniqueCells() && m.overlap() > 0,
                "★ nested pans share a catchment ⇒ naive isolated counts double count it (overlap>0)");
        check(!WorldBasin.isolatedAdditive(m), "★ nested double basin is NOT additive: isolated budgets must not be summed");
        check(WorldBasin.mergeCertified(m), "the union is a certified connected settled basin");
        var union = upstream(drain(-10000), m.members(), 100000);
        var a = upstream(drain(-10000), wb.resolve(new Point(0, 0)).members(), 100000);
        var b = upstream(drain(-10000), wb.resolve(new Point(2, 0)).members(), 100000);
        check(new HashSet<>(m.members()).equals(flood(new Point(2, 0), 5.0)) && m.uniqueCells() == union.size()
                && m.uniqueCells() < m.naiveCells() && m.naiveCells() == a.size() + b.size(),
                "merge counts match the independent oracle: union once, naive = sum of the two");
        var whole = wb.budget(m, cl(E3));
        var sum = wb.budget(wb.resolve(new Point(0, 0)), cl(E3)).inflow() + wb.budget(wb.resolve(new Point(2, 0)), cl(E3)).inflow();
        check(whole.inflow() < sum && whole.state() == WorldBasin.State.OVERFLOW,
                "whole-system inflow < summed isolated inflow ⇒ summing isolated budgets over-counts water");
    }

    private static void mergeDisconnected() {
        fSplit();var wb = basin(-10000, 100000);
        var a = wb.resolve(new Point(-10, 0));var b = wb.resolve(new Point(10, 0));
        check(a.state() == WorldBasin.State.UNIQUE_EXIT && b.state() == WorldBasin.State.UNIQUE_EXIT
                && a.sill() == 2.0 && b.sill() == 2.0,
                "split: two settled bowls of equal sill whose floods are disjoint");
        var m = wb.merge(new Point(-10, 0), new Point(10, 0));
        check(!m.connected() && m.state() == WorldBasin.State.GEOMETRY_PENDING,
                "★ a chosen sill that does not actually join the two pans ⇒ PENDING with connected == false");
        check(m.members().isEmpty() && Double.isNaN(m.sill()) && Double.isNaN(m.floor()),
                "★ no union is fabricated when connectivity is unverified");
        check(!WorldBasin.mergeCertified(m) && !WorldBasin.isolatedAdditive(m),
                "an unconnected merge is neither certified nor additive");
    }

    private static void pendingReasons() {
        fC();var wb = basin(-10000, 100000);
        var t = wb.certify(new Point(0, 0));
        check(t.reach() == Reach.PENDING && t.geometry() == WorldBasin.State.MULTIPLE_EXITS && t.chain().isEmpty(),
                "tied exits: certificate PENDING with no fabricated path, never a spurious sea reach");
        fDeg();var e = basin(-10000, 100000).certify(new Point(0, 0));
        check(e.reach() == Reach.PENDING && e.geometry() == WorldBasin.State.GEOMETRY_PENDING,
                "draining flat: certificate PENDING");
        fDeg();var wd = basin(-10000, 100000);
        check(!wd.release(new Point(0, 0), cl(E3)).released(),
                "★ a pending-geometry basin can never be released (no caller boolean involved)");
        fStair();var wn = basin(-1, 100000);
        check(threw(() -> wn.release(new Point(0, 0), new Climate(Double.NaN, E, C)), IllegalArgumentException.class),
                "nonfinite climate is rejected before any verdict");
        var tb = basin(-1, 9).certify(new Point(0, 0));
        check(tb.reach() == Reach.PENDING, "★ budget-exhausted geometry is PENDING, never a sampled sea reach");
    }

    private static void prefixMustFlow() {
        fStair();
        for (int z = 1; z <= 200; z++) put(2, z, 3 + z * 0.01);
        var wb = basin(-1, 100000); var a = new Point(0, 0); var b = new Point(2, 0);
        var ga = wb.resolve(a); var gb = wb.resolve(b);
        int ua = upstream(drain(-1), ga.members(), 100000).size();
        var union = new HashSet<>(ga.members()); union.addAll(gb.members());
        int total = upstream(drain(-1), union, 100000).size();
        double p = 0.004, c = 0.3;
        double ownLimit = ua * p * c / ga.members().size();
        double wholeLimit = total * p * c / union.size();
        check(wholeLimit > ownLimit, "prefix negative fixture: downstream local rain can hide a dry upstream basin");
        double e = (wholeLimit + ownLimit) / 2;
        var climate = new Climate(p, e, c);
        check(wb.budget(ga, climate).state() == WorldBasin.State.CLOSED && total * p * c > union.size() * e,
                "prefix negative: whole union positive but upstream cannot spill");
        check(!wb.release(a, climate).released(), "positive downstream rain cannot retroactively license a dry upstream prefix");
    }

    private static void integerSampleBudget() {
        fSea(); var calls = new java.util.concurrent.atomic.AtomicInteger();
        WindowOutlets.Terrain counted = (x,z)->{ calls.incrementAndGet(); return (x==0 && z==0)?0:10; };
        var graph = new WorldDrainage(4, 1, -100, counted);
        graph.at(new Point(0,0)); calls.set(0);
        var wb = new WorldBasin(graph, counted, 4, 9);
        check(wb.resolve(new Point(0,0)).state()==WorldBasin.State.EXHAUSTED && calls.get()<=9,
                "integer edge-interior columns count against the same nine-sample terrain budget");
        check(wb.certify(new Point(0,0)).reach()==Reach.PENDING,
                "exhausted integer-column certificate returns pending, not a private Limit exception");
    }

    private static void cancelRetry() {
        fA();var b = basin(-10000, 100000);
        Throwable x = trap(() -> {Thread.currentThread().interrupt();b.resolve(new Point(0, 0));});
        Thread.interrupted();
        check(x instanceof CancellationException, "interrupted resolve surfaces CancellationException");
        check(b.resolve(new Point(0, 0)).equals(basin(-10000, 100000).resolve(new Point(0, 0))),
                "retry after cancellation matches a fresh computation");
        var g = basin(-10000, 100000).resolve(new Point(0, 0));
        check(threw(() -> g.members().add(new Point(9, 9)), UnsupportedOperationException.class)
                && threw(() -> g.exits().clear(), UnsupportedOperationException.class)
                && threw(() -> g.path().clear(), UnsupportedOperationException.class), "geometry lists are read-only");
    }
}
