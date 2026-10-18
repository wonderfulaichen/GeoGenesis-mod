package com.geogenesis.worldgen.hydrology.model;

import java.util.*;
import java.util.concurrent.CancellationException;
import com.geogenesis.worldgen.hydrology.model.WorldDrainage.Point;

/**
 * Budget-bounded world-basin solver: canonical <b>geometry</b> of a depression, never a water licence.
 *
 * <p><b>Where this lives</b> — moved here (unchanged API) from {@code src/diagnostics} so that production code can
 * reach it; the probes still exercise it through the diagnostics classpath. The production entry point on top of it
 * is {@link WorldBasinRouting} (basin lookup + spill route). Nothing in this class knows about flow budgets.
 *
 * <p><b>Fixed-budget lowest cut</b> — {@link #resolve(Point)} minimax (bottleneck) floods from a basin sink: the
 * spill height is the minimum over all escape paths of the maximum crest, i.e. the lowest level at which the
 * depression connects to a strictly lower node. It is bounded by {@code limit} terrain samples and replies
 * {@link State#EXHAUSTED} when the bound is hit — never a fabricated spill. {@link State#GEOMETRY_PENDING} is
 * kept for a draining flat / outlet-free depression, a genuine geometric answer rather than exhaustion.
 *
 * <p><b>Sampled geometry is not a hydrologic licence</b> — {@link #certify(Point)} only names what the sampled
 * chain proves geometrically ({@link Reach#SAMPLED_TO_SEA}, {@link Reach#BACKWATER} or {@link Reach#PENDING}).
 * A sampled descent to a below-sea node does <b>not</b> mean the water arrives there: a downstream pan may
 * evaporate the cascade dry. The actual release verdict is {@link #release(Point, LakeWaterBalance.Climate)}; it
 * is licensed <b>only</b> with a complete, source-de-duplicated downstream water surplus and every hop crest
 * certified, and is pending otherwise. Neither method is a production authority.
 *
 * <p><b>Evidence, not assertion</b> — {@link Certificate#chain} is the full sampled node path (each basin's own
 * {@link Geometry#path()} storage segment included, then every descent hop), and {@link Certificate#crests} is the
 * crest of every hop. A merge ({@link #merge}) refuses to union two pans whose chosen sill does not actually join
 * them ({@code connected == false}). No caller-supplied boolean can produce a green result.
 */
final class WorldBasin {
    enum State { UNIQUE_EXIT, MULTIPLE_EXITS, GEOMETRY_PENDING, EXHAUSTED, RUNOFF_PENDING, CLOSED, OVERFLOW }

    /** Geometric sampled reach of a spill chain — an observation, NOT a hydrological release licence. */
    enum Reach {
        /** The sampled chain descends to a below-sea node. Geometry only: says nothing about downstream water. */
        SAMPLED_TO_SEA,
        /** A downstream pan's sill sits strictly above this basin's <b>initial</b> sill ⇒ backwater. */
        BACKWATER,
        /** Unverified: pending/exhausted geometry, truncated catchment, chain too deep/cyclic, unresolved receiver. */
        PENDING
    }

    record Face(Point inner, Point outer, double crest) { }

    /** Where a basin's spill lands, plus the receiving node's own drainage state and bed. */
    record Spill(Point target, WorldDrainage.State state, double bed) { }

    record Geometry(State state, Point identity, double sill, double floor, List<Point> members,
                    List<Face> exits, List<Point> path, Spill spill, int samples) {
        Geometry { members = List.copyOf(members); exits = List.copyOf(exits); path = List.copyOf(path); }
    }

    record Budget(State state, double inflow, double evaporation, double release, int uniqueRunoffCells) { }

    /**
     * Geometric certificate with its full sampled evidence. {@code operativeSill} is the highest sill the water
     * must cross to leave the sampled chain. {@code sinks} are the resolved basin sinks the chain visits.
     * {@code chain} is every sampled node (basin storage segments included) and {@code crests} the crest of each
     * hop, so a consumer can re-check every claim instead of trusting a flag.
     */
    record Certificate(Reach reach, State geometry, Point identity, double floor, double sill,
                       double operativeSill, Spill spill, int uniqueRunoffCells, boolean catchmentComplete,
                       List<Point> sinks, List<Point> chain, List<Double> crests, String reason) {
        Certificate { sinks = List.copyOf(sinks); chain = List.copyOf(chain); crests = List.copyOf(crests); }
        /** One crest per hop, no gap ⇒ the whole sampled path is described. */
        boolean chainComplete() { return crests.size() == Math.max(0, chain.size() - 1); }
    }

    /**
     * Whole-system release verdict. {@code released} is true only with sampled geometry to sea, a complete hop-crest
     * record, and a de-duplicated downstream water surplus; anything else is a non-green {@code reason}.
     */
    record Release(boolean released, Reach reach, double operativeSill, List<Point> chain, String reason) {
        Release { chain = List.copyOf(chain); }
    }

    /**
     * Two depressions combined at their shared spill. {@code naiveCells} sums the two isolated catchments;
     * {@code overlap = naiveCells - uniqueCells > 0} proves the isolated budgets double count a shared catchment.
     * {@code connected == false} means the chosen sill does not actually join the two pans, so no union is claimed.
     */
    record Merge(State state, double sill, double floor, List<Point> members, List<Face> exits,
                 int uniqueCells, int naiveCells, int overlap, boolean complete, boolean connected) {
        Merge { members = List.copyOf(members); exits = List.copyOf(exits); }
    }

    private record Visit(Point point, double cost) { }

    /** Downstream route of a spill: the ordered sampled nodes and whether they terminate at the sea. */
    private record Route(List<Point> nodes, boolean terminal) {
        Route { nodes = List.copyOf(nodes); }
    }

    private record Catch(int cells, boolean complete) { }

    static final int MAX_MERGE_DEPTH = 8;
    static final int TRACE_BUDGET = 4096;
    private static final Comparator<Point> ORDER = Comparator.comparingInt(Point::x).thenComparingInt(Point::z);
    private final WorldDrainage drainage;
    private final WindowOutlets.Terrain terrain;
    private final int step, limit;
    private final Map<Point, Double> heights = new HashMap<>();
    private final Map<WorldDrainage.Edge, Double> crests = new HashMap<>();
    private final Map<Point, Geometry> resolved = new HashMap<>();

    WorldBasin(WorldDrainage drainage, WindowOutlets.Terrain terrain, int step, int limit) {
        if (step < 1 || limit < 9 || limit > 131072) throw new IllegalArgumentException("bounded basin policy");
        this.drainage = drainage; this.terrain = terrain; this.step = step; this.limit = limit;
    }

    /** A settled basin holds water; every other state is pending-family and may never license a release. */
    static boolean settled(State state) { return state == State.UNIQUE_EXIT || state == State.MULTIPLE_EXITS; }

    static boolean isPending(State state) { return !settled(state); }

    private double height(Point p) {
        cancelled(); Double old = heights.get(p); if (old != null) return old;
        if (heights.size() >= limit) throw new Limit();
        double h = terrain.height(p.x(), p.z());
        if (!Double.isFinite(h)) throw new IllegalArgumentException("nonfinite basin terrain");
        heights.put(p, h); return h;
    }

    private double crest(Point a, Point b) {
        var edge = ORDER.compare(a, b) < 0 ? new WorldDrainage.Edge(a, b) : new WorldDrainage.Edge(b, a);
        Double old = crests.get(edge); if (old != null) return old;
        // All integer columns along the prospective channel, not just the two coarse endpoints.
        double max = Math.max(height(a), height(b));
        for (int i = 1; i < step; i++) {
            cancelled();
            var p = new Point(a.x() + (b.x() - a.x()) * i / step, a.z() + (b.z() - a.z()) * i / step);
            max = Math.max(max, height(p));
        }
        crests.put(edge, max); return max;
    }

    /** Memoized fixed-budget lowest cut (deterministic), so a certify walk and its release re-use one resolution. */
    Geometry resolve(Point sink) {
        cancelled(); Geometry old = resolved.get(sink); if (old != null) return old;
        Geometry value = compute(sink);
        resolved.put(sink, value);
        return value;
    }

    Geometry resolveHydraulic(Point sink) {
        var s=drainage.at(sink);
        if(s.state()==WorldDrainage.State.BELOW_SEA)throw new IllegalArgumentException("hydraulic basin sink required");
        for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++) {
            if(dx==0 && dz==0)continue;var n=offset(sink,dx,dz);
            if(height(n)<s.bed() && crest(sink,n)<=s.bed())throw new IllegalArgumentException("hydraulic basin sink required");
        }
        return computeGeometry(sink);
    }
    private Geometry compute(Point sink) {
        if (drainage.at(sink).state() != WorldDrainage.State.BASIN_PENDING)
            throw new IllegalArgumentException("basin sink required");
        return computeGeometry(sink);
    }
    private Geometry computeGeometry(Point sink) {
        double bottom;
        try { bottom = height(sink); } catch (Limit full) { return exhausted(Double.NaN); }
        var costs = new HashMap<Point, Double>();
        var previous = new HashMap<Point, Point>();
        var queue = new PriorityQueue<Visit>(Comparator.comparingDouble(Visit::cost).thenComparing(Visit::point, ORDER));
        costs.put(sink, bottom); queue.add(new Visit(sink, bottom));
        try {
            while (!queue.isEmpty()) {
                cancelled(); var visit = queue.remove(); var p = visit.point();
                if (visit.cost() != costs.get(p)) continue;
                if (height(p) < bottom) {
                    if (visit.cost() <= bottom) return pending();   // draining flat: no storage footprint
                    return footprint(sink, bottom, visit.cost(), path(previous, p));
                }
                expand(visit, costs, previous, queue);
            }
        } catch (Limit full) { return exhausted(bottom); }
        return pending();
    }

    private void expand(Visit visit, Map<Point, Double> costs, Map<Point, Point> previous, PriorityQueue<Visit> queue) {
        var p = visit.point();
        for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            if (dx == 0 && dz == 0) continue;
            var n = offset(p, dx, dz);
            double cost = Math.max(visit.cost(), crest(p, n));
            if (cost < costs.getOrDefault(n, Double.POSITIVE_INFINITY)) {
                costs.put(n, cost); previous.put(n, p); queue.add(new Visit(n, cost));
            }
        }
    }

    private Geometry footprint(Point sink, double floor, double sill, List<Point> path) {
        var members = flood(List.of(sink), sill);
        var exits = rimFaces(members, sill);
        var ordered = members.stream().sorted(ORDER).toList();
        State state = exits.isEmpty() ? State.GEOMETRY_PENDING
                : exits.size() == 1 ? State.UNIQUE_EXIT : State.MULTIPLE_EXITS;
        return new Geometry(state, ordered.get(0), sill, floor, ordered, exits, path, classify(path), heights.size());
    }

    /** Rebuild a complete witness through the selected face, using the original sampler budget. */
    Geometry through(Geometry g, Face face) {
        if (!g.exits().contains(face)) throw new IllegalArgumentException("face outside complete cut");
        try {
            var inside = new HashSet<>(g.members());
            var prefix = boundedPath(g.identity(), p -> p.equals(face.inner()), inside, true, g.sill(), true);
            var suffix = boundedPath(face.outer(), p -> height(p) < g.floor(), inside, false, g.sill(), false);
            if (prefix.isEmpty() || suffix.isEmpty()) return pending();
            var chain = new ArrayList<>(prefix); chain.addAll(suffix);
            for (int i = 1; i < chain.size(); i++)
                if (crest(chain.get(i - 1), chain.get(i)) > g.sill()) return pending();
            return new Geometry(g.state(), g.identity(), g.sill(), g.floor(), g.members(), g.exits(),
                    chain, classify(chain), heights.size());
        } catch (Limit full) { return exhausted(g.floor()); }
    }

    private List<Point> boundedPath(Point start, java.util.function.Predicate<Point> goal,
                                    Set<Point> members, boolean inside, double sill, boolean strict) {
        var previous = new HashMap<Point, Point>();
        var queue = new ArrayDeque<Point>(); previous.put(start, null); queue.add(start);
        while (!queue.isEmpty()) {
            cancelled(); var p = queue.remove();
            if (goal.test(p)) return path(previous, p);
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                var n = offset(p, dx, dz);
                if (members.contains(n) != inside || previous.containsKey(n)) continue;
                double h = crest(p, n);
                if (h > sill || (strict && h == sill)) continue;
                if (previous.size() >= limit) throw new Limit();
                previous.put(n, p); queue.add(n);
            }
        }
        return List.of();
    }

    private HashSet<Point> flood(Collection<Point> seeds, double sill) {
        var members = new HashSet<Point>(seeds);
        var queue = new ArrayDeque<Point>(seeds);
        while (!queue.isEmpty()) {
            cancelled(); var p = queue.remove();
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                var n = offset(p, dx, dz);
                if (members.contains(n)) continue;
                if (crest(p, n) < sill) { members.add(n); queue.add(n); }
            }
        }
        return members;
    }

    private ArrayList<Face> rimFaces(Collection<Point> members, double sill) {
        var exits = new ArrayList<Face>();
        for (var p : members) for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
            if (dx == 0 && dz == 0) continue;
            var n = offset(p, dx, dz); if (members.contains(n)) continue;
            double crest = crest(p, n); if (crest == sill) exits.add(new Face(p, n, crest));
        }
        exits.sort(Comparator.comparing(Face::inner, ORDER).thenComparing(Face::outer, ORDER));
        return exits;
    }

    private Spill classify(List<Point> path) {
        if (path.isEmpty()) return null;
        var target = path.get(path.size() - 1);
        var state = drainage.at(target).state();
        try { return new Spill(target, state, height(target)); } catch (Limit full) { return null; }
    }

    /** Reverse-D8 catchment of {@code seeds}, each cell counted once. Truncation is reported, never silently accepted. */
    private Catch catchment(Collection<Point> seeds) {
        var seen = new HashSet<Point>(seeds);
        var queue = new ArrayDeque<Point>(seeds);
        while (!queue.isEmpty()) {
            cancelled(); var p = queue.remove();
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                var n = offset(p, dx, dz);
                if (seen.contains(n) || !p.equals(drainage.at(n).next())) continue;
                if (seen.size() >= limit) return new Catch(seen.size(), false);
                seen.add(n); queue.add(n);
            }
        }
        return new Catch(seen.size(), true);
    }

    /** Isolated full-basin steady balance. Diagnostic only: a positive release here is <b>not</b> a licence. */
    Budget budget(Geometry basin, LakeWaterBalance.Climate climate) {
        finite(climate);
        if (basin.state() == State.EXHAUSTED) return new Budget(State.EXHAUSTED, Double.NaN, Double.NaN, Double.NaN, 0);
        if (basin.state() != State.UNIQUE_EXIT) return new Budget(State.GEOMETRY_PENDING, Double.NaN, Double.NaN, Double.NaN, 0);
        Catch cells = catchment(basin.members());
        if (!cells.complete()) return new Budget(State.RUNOFF_PENDING, Double.NaN, Double.NaN, Double.NaN, cells.cells());
        return balance(cells.cells(), basin.members().size(), climate, cells.cells());
    }

    /** Whole-system steady balance of a merged basin: the union catchment is counted exactly once. */
    Budget budget(Merge merged, LakeWaterBalance.Climate climate) {
        finite(climate);
        if (merged.state() == State.EXHAUSTED) return new Budget(State.EXHAUSTED, Double.NaN, Double.NaN, Double.NaN, 0);
        if (merged.state() != State.UNIQUE_EXIT) return new Budget(State.GEOMETRY_PENDING, Double.NaN, Double.NaN, Double.NaN, merged.uniqueCells());
        if (!merged.complete()) return new Budget(State.RUNOFF_PENDING, Double.NaN, Double.NaN, Double.NaN, merged.uniqueCells());
        return balance(merged.uniqueCells(), merged.members().size(), climate, merged.uniqueCells());
    }

    private Budget balance(int runoffCells, int memberCells, LakeWaterBalance.Climate climate, int reported) {
        double area = step * (double) step;
        double inflow = runoffCells * area * climate.precipitation() * climate.runoffCoeff();
        double loss = memberCells * area * climate.evaporation();
        double release = Math.max(0, inflow - loss);
        return new Budget(release > 0 ? State.OVERFLOW : State.CLOSED, inflow, loss, release, reported);
    }

    private static void finite(LakeWaterBalance.Climate climate) {
        if (!Double.isFinite(climate.precipitation()) || !Double.isFinite(climate.evaporation())
                || !Double.isFinite(climate.runoffCoeff())) throw new IllegalArgumentException("finite climate required");
    }

    /**
     * Geometric certificate: walk the spill chain downstream sampling a full node path. A sea terminal ⇒
     * {@link Reach#SAMPLED_TO_SEA}; a downstream sill strictly above the <b>initial</b> sill (not the previous one,
     * which would over-report backwater) ⇒ {@link Reach#BACKWATER}; every other outcome is {@link Reach#PENDING}.
     * Each basin's own {@code path()} storage segment and the crest of every hop are recorded in the certificate.
     */
    Certificate certify(Point sink) {
        try { return certifyWithinBudget(sink); }
        catch (Limit full) { return pendingCert(exhausted(Double.NaN), "integer-column terrain budget exhausted"); }
    }

    private Certificate certifyWithinBudget(Point sink) {
        Geometry g = resolve(sink);
        if (g.state() != State.UNIQUE_EXIT) return pendingCert(g, "geometry " + g.state());
        Catch cells = catchment(g.members());
        if (!cells.complete()) return pendingCert(g, "catchment truncated by the sample budget");
        var chain = new ArrayList<Point>();
        var crests = new ArrayList<Double>();
        var sinks = new ArrayList<Point>();
        sinks.add(sink);
        extend(chain, crests, g.path());
        double initial = g.sill(), operative = g.sill();
        var visited = new HashSet<Point>();
        Spill spill = g.spill();
        for (int depth = 1; depth <= MAX_MERGE_DEPTH; depth++) {
            Route route = receiving(spill);
            if (route == null) return cert(Reach.PENDING, g, operative, cells, sinks, chain, crests, "spill unclassifiable");
            if (route.nodes().isEmpty()) return cert(Reach.PENDING, g, operative, cells, sinks, chain, crests, "empty spill route");
            Point next = route.nodes().get(route.nodes().size() - 1);
            extend(chain, crests, route.nodes());
            if (route.terminal()) return cert(Reach.SAMPLED_TO_SEA, g, operative, cells, sinks, chain, crests,
                    "sampled chain descends to a below-sea node");
            if (!visited.add(next)) return cert(Reach.PENDING, g, operative, cells, sinks, chain, crests, "chain cycle");
            Geometry sub = resolve(next);
            if (sub.state() != State.UNIQUE_EXIT) return cert(Reach.PENDING, g, operative, cells, sinks, chain, crests, "downstream " + sub.state());
            if (sub.sill() > initial) return cert(Reach.BACKWATER, g, Math.max(operative, sub.sill()), cells, sinks, chain, crests,
                    "downstream sill " + sub.sill() + " above the initial sill " + initial);
            operative = Math.max(operative, sub.sill());
            sinks.add(next);
            extend(chain, crests, sub.path());
            spill = sub.spill();
        }
        return cert(Reach.PENDING, g, operative, cells, sinks, chain, crests, "merge depth bound");
    }

    private Certificate pendingCert(Geometry g, String reason) {
        return cert(Reach.PENDING, g, g.sill(), new Catch(0, false), List.of(), List.of(), List.of(), reason);
    }

    private Certificate cert(Reach reach, Geometry g, double operative, Catch cells, List<Point> sinks,
                             List<Point> chain, List<Double> crests, String reason) {
        return new Certificate(reach, g.state(), g.identity(), g.floor(), g.sill(), operative, g.spill(),
                cells.cells(), cells.complete(), sinks, chain, crests, reason);
    }

    /**
     * Whole-system release verdict, derived from a sink and a climate alone. Licensed only when the sampled
     * geometry reaches the sea, the hop-crest record is complete and every crest stays at or below the operative
     * sill, and the de-duplicated cascade (union of all visited pans, catchment counted once) still has a positive
     * water surplus at the given climate. A downstream pan that evaporates the cascade dry ⇒ not licensed.
     */
    Release release(Point sink, LakeWaterBalance.Climate climate) {
        finite(climate);
        Certificate c = certify(sink);
        if (c.reach() != Reach.SAMPLED_TO_SEA)
            return new Release(false, c.reach(), c.operativeSill(), c.chain(), "reach " + c.reach() + ": " + c.reason());
        if (!c.catchmentComplete())
            return new Release(false, c.reach(), c.operativeSill(), c.chain(), "initial catchment truncated");
        if (!c.chainComplete())
            return new Release(false, c.reach(), c.operativeSill(), c.chain(), "incomplete hop-crest evidence");
        double worst = Double.NEGATIVE_INFINITY;
        for (double k : c.crests()) worst = Math.max(worst, k);
        if (!(worst <= c.operativeSill() + 1e-9))
            return new Release(false, c.reach(), c.operativeSill(), c.chain(),
                    "hop crest " + worst + " exceeds the operative sill " + c.operativeSill());
        var union = new LinkedHashSet<Point>();
        for (var s : c.sinks()) {
            union.addAll(resolve(s).members());
            Catch cells = catchment(union);
            if (!cells.complete())
                return new Release(false, c.reach(), c.operativeSill(), c.chain(), "cascade catchment truncated");
            double area = step * (double) step;
            double inflow = cells.cells() * area * climate.precipitation() * climate.runoffCoeff();
            double loss = union.size() * area * climate.evaporation();
            if (!(inflow > loss))
                return new Release(false, c.reach(), c.operativeSill(), c.chain(),
                        "de-duplicated cascade prefix has no net water at " + s + " (inflow=" + inflow + " loss=" + loss + ")");
        }
        return new Release(true, c.reach(), c.operativeSill(), c.chain(),
                "conditional raw-D8 cascade: every prefix surplus, integer-hop crests sampled; not production permission");
    }

    /** Downstream route of a spill: {@code null} = unclassifiable, else the crossed nodes + sea flag. */
    private Route receiving(Spill spill) {
        if (spill == null) return null;
        if (spill.state() == WorldDrainage.State.BELOW_SEA) return new Route(List.of(spill.target()), true);
        if (spill.state() == WorldDrainage.State.BASIN_PENDING) return new Route(List.of(spill.target()), false);
        try {
            var nodes = drainage.trace(spill.target(), TRACE_BUDGET);
            var state = drainage.at(nodes.get(nodes.size() - 1)).state();
            if (state == WorldDrainage.State.BELOW_SEA) return new Route(nodes, true);
            if (state == WorldDrainage.State.BASIN_PENDING) return new Route(nodes, false);
            return null;
        } catch (CancellationException cancelled) {
            throw cancelled;                              // never turn an interrupt into a verdict
        } catch (RuntimeException unclassifiable) { return null; }
    }

    /** Append nodes (skipping a repeated endpoint) and record the crest of every hop, so no hop goes unrecorded. */
    private void extend(List<Point> chain, List<Double> crests, List<Point> add) {
        for (var p : add) {
            if (!chain.isEmpty() && chain.get(chain.size() - 1).equals(p)) continue;
            if (!chain.isEmpty()) crests.add(crest(chain.get(chain.size() - 1), p));
            chain.add(p);
        }
    }

    /**
     * Combine two depressions at their shared spill (the higher of the two sills). The union is claimed only if the
     * chosen sill actually joins them (<b>single connected component</b>); otherwise the merge is {@link
     * State#GEOMETRY_PENDING} with {@code connected == false} and no atomic-basin proof is fabricated. The union
     * catchment is counted once — summing the two isolated counts is the "repeated confluence" error {@code overlap}
     * exposes.
     */
    Merge merge(Point a, Point b) {
        try {
            Geometry ga = resolve(a), gb = resolve(b);
            if (!settled(ga.state()) || !settled(gb.state())) return emptyMerge(State.GEOMETRY_PENDING);
            double sill = Math.max(ga.sill(), gb.sill()), floor = Math.min(ga.floor(), gb.floor());
            var seeds = new LinkedHashSet<Point>();
            seeds.addAll(ga.members()); seeds.addAll(gb.members());
            var members = flood(seeds, sill);
            if (!singleComponent(members, sill)) return emptyMerge(State.GEOMETRY_PENDING);
            var exits = rimFaces(members, sill);
            Catch union = catchment(members), ca = catchment(ga.members()), cb = catchment(gb.members());
            int unique = union.cells(), naive = ca.cells() + cb.cells();
            var ordered = members.stream().sorted(ORDER).toList();
            State state = exits.isEmpty() ? State.GEOMETRY_PENDING
                    : exits.size() == 1 ? State.UNIQUE_EXIT : State.MULTIPLE_EXITS;
            return new Merge(state, sill, floor, ordered, exits, unique, naive, naive - unique,
                    union.complete() && ca.complete() && cb.complete(), true);
        } catch (Limit full) { return emptyMerge(State.EXHAUSTED); }
    }

    /** True iff the flooded set is one component — the chosen sill really connects the seeds. */
    private boolean singleComponent(Collection<Point> members, double sill) {
        if (members.isEmpty()) return true;
        var seen = new HashSet<Point>();
        var queue = new ArrayDeque<Point>();
        var start = members.iterator().next();
        seen.add(start); queue.add(start);
        while (!queue.isEmpty()) {
            cancelled(); var p = queue.remove();
            for (int dx = -1; dx <= 1; dx++) for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) continue;
                var n = offset(p, dx, dz);
                if (members.contains(n) && crest(p, n) < sill && seen.add(n)) queue.add(n);
            }
        }
        return seen.size() == members.size();
    }

    private static Merge emptyMerge(State state) {
        return new Merge(state, Double.NaN, Double.NaN, List.of(), List.of(), 0, 0, 0, false, false);
    }

    /** True iff the merged footprint is a single connected, settled, exactly counted basin. */
    static boolean mergeCertified(Merge merged) {
        return merged.state() == State.UNIQUE_EXIT && merged.complete() && merged.connected();
    }

    /** True iff the two isolated catchments are disjoint, so the two isolated budgets may simply be added. */
    static boolean isolatedAdditive(Merge merged) {
        return merged.connected() && merged.complete()
                && merged.overlap() == 0 && merged.uniqueCells() == merged.naiveCells();
    }

    private static List<Point> path(Map<Point, Point> previous, Point end) {
        var path = new ArrayList<Point>();
        for (var at = end; at != null; at = previous.get(at)) path.add(at);
        Collections.reverse(path); return path;
    }

    private Geometry pending() {
        return new Geometry(State.GEOMETRY_PENDING, null, Double.NaN, Double.NaN, List.of(), List.of(), List.of(), null, heights.size());
    }

    private Geometry exhausted(double floor) {
        return new Geometry(State.EXHAUSTED, null, Double.NaN, floor, List.of(), List.of(), List.of(), null, heights.size());
    }

    private Point offset(Point p, int dx, int dz) {
        return new Point(Math.addExact(p.x(), Math.multiplyExact(dx, step)), Math.addExact(p.z(), Math.multiplyExact(dz, step)));
    }

    private static void cancelled() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("world basin");
    }

    private static final class Limit extends RuntimeException { }
}
