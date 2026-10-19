package com.geogenesis.worldgen.hydrology.model;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.geogenesis.config.GeoGenesisConfig;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * test-basin-continuation — local true first outlet vs steady net flow / downstream backwater.
 *
 * <h2>The interface question this probe isolates</h2>
 * <ol>
 *   <li><b>Local true first outlet</b> — the lowest crest at which one pan's strict level set first reaches a
 *       strictly lower node. Certifiable from a bounded local search once the level set and every adjacency cut are
 *       closed (the G44 principle, {@link CertifiedBasinQuery}); no world sea and no far terminal are needed.</li>
 *   <li><b>Certified world basin</b> — production {@link WorldDrainage} + {@link WorldBasinRouting}. A receiving pan
 *       whose own sill is higher can force a <b>higher</b> merged sill than the pan-local first outlet. Both numbers
 *       are measured separately and the delta is logged, so the backwater term is attributable, never assumed.</li>
 *   <li><b>Steady net flow</b> — production {@link DemandFlow} / {@link WorldBasinFlow}: zero inflow, evaporation
 *       exhaustion, "a bounded lower bound already satisfies the demand" and budget exhaustion are four separate
 *       outcomes with separate expectations.</li>
 * </ol>
 *
 * <h2>Hard rules encoded as assertions</h2>
 * <ul>
 *   <li>An UNKNOWN certificate is never classified as zero (W6 includes the broken classifier as a negative
 *       control, so the assertion cannot pass on the strength of the correct classifier alone).</li>
 *   <li>A bounded lower bound is never reported as an exact discharge (D1).</li>
 *   <li>Level sets only <i>touching</i> at the sill are never merged (S6); bodies connected strictly below the sill
 *       are never published as two lakes (S5). Both share one geometry and differ only in the divider crest.</li>
 *   <li>A window frame is never an outlet: {@link Lattice#height} throws when sampled off-lattice or off-frame.</li>
 *   <li>Oracles are structurally different from production (union-find minimax, reverse-D8 catchment walk), so
 *       agreement is evidence rather than tautology.</li>
 * </ul>
 *
 * <p><b>Scope</b>: this is a difference experiment, not a production acceptance gate. Nothing here requires a whole
 * world, an exhaustive source enumeration, or block-exact basin closure; real terrain yields a small local body and
 * only <i>that</i> body's coarse cut is certified. Experimental completion is not basin-migration completion.
 *
 * <p>Output: {@code <localFlow.output>/result.txt} plus {@code outlet-profile.csv}, and identical stdout.
 * Exit 0 = every assertion passed; 1 = a candidate red or an open real conflict; 2 = infrastructure failure.
 */
public final class LocalFlowBasinProbe {
    /**
     * Output directory contract: {@code -DlocalFlow.output=<dir>} overrides the default
     * {@code build/local-flow-reassessment/basin}. Resolved once at class init so every writer agrees.
     */
    static final Path OUT = Path.of(System.getProperty("localFlow.output", "build/local-flow-reassessment/basin"));
    /** Synthetic sea level, below every synthetic pit floor and outfall so "below sea" is a real terminal. */
    static final double SEA = -5;
    /** World seed. CLI form is {@code Probe [seed]}; {@code -DlocalFlow.seed} is the property fallback. */
    static final long DEFAULT_SEED = 9139912035078620160L;
    /** Production climate (same triple as {@code ModelHydrologyProvider.CLIMATE}). */
    static final LakeWaterBalance.Climate PRODUCTION = new LakeWaterBalance.Climate(0.004, 0.002, 0.3);
    /** Synthetic ledger climate: per-cell inflow 0.12 > per-cell loss 0.02, so both terms really act. */
    static final LakeWaterBalance.Climate SYNTH = new LakeWaterBalance.Climate(0.4, 0.02, 0.3);
    static final int STEP = 1, DEMAND_LIMIT = 32768, GEOMETRY_LIMIT = 131072;
    static final StringBuilder LOG = new StringBuilder();
    static final List<String> CANDIDATE = new ArrayList<>(), RED = new ArrayList<>(), INFRA = new ArrayList<>();
    /** Measured differences between two contracts answering different questions: neither pass nor fail. */
    static final List<String> DIVERGENCE = new ArrayList<>();
    static int checks;
    /** Effective seed used by the real-terrain stage (CLI argument wins over the property/default). */
    static long seed = DEFAULT_SEED;
    /** The certified real sink chosen by the scan, handed from the R stage to the profile/cost stage. */
    static WorldDrainage.Point physicalSink;
    /** Unit four-neighbour steps. Used by the block-exact stage, which must never borrow the coarse stride. */
    static final int[][] FOUR = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    /** Block-cell budget for the block-exact certificate. Separate from the coarse geometry budget. */
    static final int BLOCK_BUDGET = 131072;

    private LocalFlowBasinProbe() { }

    /**
     * Seed resolution: CLI argument first, then {@code -DlocalFlow.seed}, then the production seed. A malformed
     * argument is an infrastructure failure, not a silent fallback to a different world.
     */
    static long resolveSeed(String arg) {
        if (arg != null && !arg.isBlank()) {
            try { return Long.parseLong(arg.trim()); }
            catch (NumberFormatException bad) {
                INFRA.add("seed argument not a long: " + arg);
                return DEFAULT_SEED;
            }
        }
        String property = System.getProperty("localFlow.seed", "").trim();
        if (!property.isEmpty()) {
            try { return Long.parseLong(property); }
            catch (NumberFormatException bad) {
                INFRA.add("localFlow.seed not a long: " + property);
                return DEFAULT_SEED;
            }
        }
        return DEFAULT_SEED;
    }

    public static void main(String[] args) {
        try {
            seed = resolveSeed(args.length > 0 ? args[0] : null);
            Files.createDirectories(OUT);
            log("=== LocalFlowBasinProbe — local first outlet vs steady net flow / backwater ===");
            log("dir=" + OUT.toAbsolutePath() + " seed=" + seed
                    + " (default=" + DEFAULT_SEED + " cliArg=" + (args.length > 0 ? args[0] : "<none>")
                    + " prop=" + System.getProperty("localFlow.seed", "<unset>") + ")"
                    + " synthStep=" + STEP
                    + " prodClimate=" + PRODUCTION + " synthClimate=" + SYNTH
                    + " demandLimit=" + DEMAND_LIMIT + " geometryLimit=" + GEOMETRY_LIMIT);
            reuseBoundary();
            stage("S", LocalFlowBasinProbe::geometryStage);
            stage("W", LocalFlowBasinProbe::waterStage);
            stage("D", LocalFlowBasinProbe::demandStage);
            stage("R", LocalFlowBasinProbe::realStage);
        } catch (Throwable fatal) {
            INFRA.add("probe aborted: " + fatal);
            fatal.printStackTrace();
        }
        emit();
    }

    private static void reuseBoundary() {
        log("  reuse  : production WorldDrainage/WorldBasin/WorldBasinRouting/DemandFlow/WorldBasinFlow/"
                + "WorldLakeSurface + diagnostics CertifiedBasinQuery (G44), all called directly");
        log("  reuse  : G42 rolling-window sampler (private in SharedLakeSeamProbe) NOT reused; the contract is"
                + " exercised via the production world graph instead");
    }

    private static void stage(String name, Runnable body) {
        try {
            body.run();
        } catch (Throwable failure) {
            INFRA.add("stage " + name + " threw: " + failure);
            log("  [UNDECIDED] stage " + name + " threw " + failure);
            failure.printStackTrace();
        }
    }

    /** Verdict and exit code. Candidate reds, open real conflicts and infra failures stay separate. */
    /**
     * Prints, persists and exits. A failed <b>write</b> is an infrastructure failure: the run's evidence would be
     * lost, so it must not exit 0 even when every assertion passed.
     */
    private static void emit() {
        String verdict = !INFRA.isEmpty() ? "INFRASTRUCTURE FAILURE"
                : (CANDIDATE.isEmpty() && RED.isEmpty() ? "PASS (differences recorded)" : "CANDIDATE RED");
        log("\nCHECKS=" + checks + " CANDIDATE_FAIL=" + CANDIDATE.size() + " UNRESOLVED=" + RED.size()
                + " DIVERGENCE=" + DIVERGENCE.size() + " INFRA_FAIL=" + INFRA.size() + " VERDICT=" + verdict);
        for (String s : CANDIDATE) log("  CANDIDATE_FAIL " + s);
        for (String s : RED) log("  UNRESOLVED     " + s);
        for (String s : DIVERGENCE) log("  DIVERGENCE     " + s);
        for (String s : INFRA) log("  INFRA_FAIL     " + s);
        System.out.print(LOG);
        boolean written;
        try {
            Files.createDirectories(OUT);
            Files.writeString(OUT.resolve("result.txt"), LOG.toString());
            written = true;
        } catch (Exception failure) {
            System.out.println("[INFRA_FAIL] could not persist result.txt to " + OUT.toAbsolutePath()
                    + ": " + failure);
            written = false;
        }
        if (!written) System.exit(2);
        System.exit(!INFRA.isEmpty() ? 2 : (CANDIDATE.isEmpty() && RED.isEmpty() ? 0 : 1));
    }

    // ------------------------------------------------------------------ verdict counters

    private static void check(boolean ok, String name, String detail) {
        checks++;
        if (ok) { log("  [PASS] " + name); return; }
        CANDIDATE.add(name + " — " + detail);
        log("  [FAIL] " + name + " — " + detail);
    }

    /** A required behaviour the current code demonstrably does not deliver, or a real conflict that is still open. */
    private static void unresolved(String name, String detail) {
        checks++;
        RED.add(name + " — " + detail);
        log("  [UNRESOLVED] " + name + " — " + detail);
    }

    private static void undecided(String name, String detail) {
        checks++;
        INFRA.add(name + " — " + detail);
        log("  [UNDECIDED] " + name + " — " + detail);
    }

    /** A measured difference between two contracts that answers a different question. Not a pass, not a failure. */
    private static void divergence(String name, String detail) {
        checks++;
        DIVERGENCE.add(name + " — " + detail);
        log("  [DIVERGENCE] " + name + " — " + detail);
    }

    private static void log(String s) { LOG.append(s).append('\n'); }

    // ------------------------------------------------------------------ synthetic lattice

    /**
     * Integer lattice with a strictly monotone base ramp, so every drop rate is distinct and no flat routing is
     * manufactured. Sampling off-lattice or outside the frame <b>throws</b>, so a window frame can never be mistaken
     * for an outlet. At {@code step == 1} the production crest operator degenerates to {@code max(h(a), h(b))}
     * exactly, which makes the 8-neighbour oracle below an exact oracle for the production graph.
     */
    static final class Lattice implements WindowOutlets.Terrain {
        final int step, ox, oz, nx, nz;
        private final double[] node;

        Lattice(int step, int ox, int oz, int nx, int nz) {
            if (step < 1 || nx < 8 || nz < 8) throw new IllegalArgumentException("lattice geometry");
            this.step = step; this.ox = ox; this.oz = oz; this.nx = nx; this.nz = nz;
            this.node = new double[nx * nz];
            for (int i = 0; i < nx; i++) for (int j = 0; j < nz; j++) {
                boolean sea = i < 3 || j < 3 || i > nx - 4 || j > nz - 4;
                node[i * nz + j] = sea ? -9.0 : 8 + 0.5 * i + 0.4 * j;
            }
        }

        void set(int i, int j, double v) {
            if (i < 0 || j < 0 || i >= nx || j >= nz) throw new IllegalArgumentException("lattice index " + i + "," + j);
            node[i * nz + j] = v;
        }

        void rect(int i0, int j0, int i1, int j1, double v) {
            for (int i = i0; i <= i1; i++) for (int j = j0; j <= j1; j++) set(i, j, v);
        }

        int blockX(int i) { return ox + step * i; }
        int blockZ(int j) { return oz + step * j; }

        boolean inside(double x, double z) {
            return x >= ox && z >= oz && x <= blockX(nx - 1) && z <= blockZ(nz - 1);
        }

        @Override public double height(double x, double z) {
            if (x != Math.floor(x) || z != Math.floor(z))
                throw new IllegalArgumentException("lattice sample must be integral: " + x + "," + z);
            if (!inside(x, z)) throw new IllegalArgumentException("outside synthetic frame: " + x + "," + z);
            int bx = (int) x, bz = (int) z;
            int i = Math.min(nx - 2, Math.max(0, Math.floorDiv(bx - ox, step)));
            int j = Math.min(nz - 2, Math.max(0, Math.floorDiv(bz - oz, step)));
            double fx = (bx - blockX(i)) / (double) step, fz = (bz - blockZ(j)) / (double) step;
            double a = node[i * nz + j] + (node[(i + 1) * nz + j] - node[i * nz + j]) * fx;
            double b = node[i * nz + j + 1] + (node[(i + 1) * nz + j + 1] - node[i * nz + j + 1]) * fx;
            return a + (b - a) * fz;
        }
    }

    /** Collision-free key for the small synthetic lattices (coordinates there are within ±32767). */
    private static int pack(int x, int z) { return (x << 16) ^ (z & 0xffff); }
    /** Invertible block key: high 32 bits = x, low 32 bits = z. Required for negative world coordinates. */
    private static long blockKey(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }
    private static int keyX(long k) { return (int) (k >> 32); }
    private static int keyZ(long k) { return (int) k; }

    private static boolean near(double a, double b) { return Double.isFinite(a) && Math.abs(a - b) <= 1e-9; }

    // ------------------------------------------------------------------ independent oracles

    @FunctionalInterface interface Escape { boolean test(int x, int z); }

    /**
     * Independent minimax (bottleneck) oracle: Kruskal-style activation ordered by height plus union-find.
     * Structurally different from the production priority flood, so agreement is evidence rather than tautology.
     *
     * @return the lowest crest at which the start's component contains a node satisfying {@code escape}
     */
    static double minimax(Lattice g, int sx, int sz, Escape escape) {
        int n = g.nx * g.nz;
        double[] h = new double[n];
        Integer[] order = new Integer[n];
        for (int i = 0; i < g.nx; i++) for (int j = 0; j < g.nz; j++) {
            int k = i * g.nz + j;
            h[k] = g.height(g.blockX(i), g.blockZ(j));
            order[k] = k;
        }
        Arrays.sort(order, (a, b) -> h[a] != h[b] ? Double.compare(h[a], h[b]) : Integer.compare(a, b));
        int[] parent = new int[n];
        boolean[] active = new boolean[n], escapeAt = new boolean[n];
        Arrays.fill(parent, -1);
        int sink = Math.floorDiv(sx - g.ox, g.step) * g.nz + Math.floorDiv(sz - g.oz, g.step);
        for (int p = 0; p < n; ) {
            int q = p;
            while (q < n && h[order[q]] == h[order[p]]) {
                int k = order[q++];
                parent[k] = k;
                active[k] = true;
                escapeAt[k] = escape.test(g.blockX(k / g.nz), g.blockZ(k % g.nz));
            }
            for (int m = p; m < q; m++) link(g, order[m], parent, active, escapeAt);
            if (active[sink] && escapeAt[root(parent, sink)]) return h[order[p]];
            p = q;
        }
        return Double.POSITIVE_INFINITY;
    }

    private static void link(Lattice g, int k, int[] parent, boolean[] active, boolean[] escapeAt) {
        int i = k / g.nz, j = k % g.nz;
        for (int di = -1; di <= 1; di++) for (int dj = -1; dj <= 1; dj++) {
            if (di == 0 && dj == 0) continue;
            int ni = i + di, nj = j + dj;
            if (ni < 0 || nj < 0 || ni >= g.nx || nj >= g.nz) continue;
            int other = ni * g.nz + nj;
            if (!active[other]) continue;
            int a = root(parent, k), b = root(parent, other);
            if (a == b) continue;
            parent[b] = a;
            escapeAt[a] |= escapeAt[b];
        }
    }

    private static int root(int[] parent, int k) {
        int r = k;
        while (parent[r] != r) r = parent[r];
        while (parent[k] != r) { int next = parent[k]; parent[k] = r; k = next; }
        return r;
    }

    /** The pan-local first outlet: lowest crest at which the strict level set reaches a strictly lower node. */
    private static double firstOutlet(Lattice g, int bx, int bz) {
        double floor = g.height(bx, bz);
        return minimax(g, bx, bz, (x, z) -> g.height(x, z) < floor);
    }

    /** Independent D8 catchment: every lattice cell whose own steepest descent reaches the reported footprint. */
    private static int catchment(Lattice g, List<WorldDrainage.Point> members) {
        Set<Integer> inside = new HashSet<>();
        for (WorldDrainage.Point p : members) inside.add(pack(p.x(), p.z()));
        int count = 0;
        for (int i = 0; i < g.nx; i++) for (int j = 0; j < g.nz; j++) {
            int x = g.blockX(i), z = g.blockZ(j);
            if (inside.contains(pack(x, z))) { count++; continue; }
            int cx = x, cz = z;
            for (int guard = 0; guard < 4096; guard++) {
                int[] n = steepest(g, cx, cz);
                if (n == null) break;
                cx = n[0]; cz = n[1];
                if (inside.contains(pack(cx, cz))) { count++; break; }
            }
        }
        return count;
    }

    private static int[] steepest(Lattice g, int x, int z) {
        double h = g.height(x, z), best = 0;
        int bx = 0, bz = 0;
        boolean found = false;
        for (int di = -1; di <= 1; di++) for (int dj = -1; dj <= 1; dj++) {
            if (di == 0 && dj == 0) continue;
            int nx = x + di * g.step, nz = z + dj * g.step;
            if (!g.inside(nx, nz)) continue;
            double drop = (h - g.height(nx, nz)) / Math.hypot(di, dj);
            if (drop > best) { best = drop; bx = nx; bz = nz; found = true; }
        }
        return found ? new int[]{bx, bz} : null;
    }

    // ------------------------------------------------------------------ production plumbing

    private static final class Fixture {
        final Lattice lattice;
        final WorldDrainage raw;
        final WorldBasinRouting routing;

        Fixture(Lattice lattice) {
            this.lattice = lattice;
            this.raw = new WorldDrainage(lattice.step, 1, SEA, lattice);
            this.routing = new WorldBasinRouting(raw, lattice, lattice.step, GEOMETRY_LIMIT);
        }

        View view(int i, int j) { return View.of(routing.at(at(i, j))); }

        WorldDrainage.Point at(int i, int j) {
            return new WorldDrainage.Point(lattice.blockX(i), lattice.blockZ(j));
        }

        WorldBasinFlow flow(LakeWaterBalance.Climate climate, int limit) {
            return new WorldBasinFlow(raw, routing, climate, lattice.step, limit);
        }
    }

    private record View(boolean published, String reason, WorldDrainage.Point identity, double sill, double floor,
                        List<WorldDrainage.Point> members, WorldBasinRouting.Lip lip, String geometry) {
        static View of(WorldBasinRouting.Basin b) {
            if (b == null) return new View(false, "BELOW_SEA", null, Double.NaN, Double.NaN, List.of(), null, "-");
            WorldBasin.State state = b.geometry();
            return new View(b.published(), b.reason(), b.identity(), b.sill(), b.floor(), b.members(), b.outlet(),
                    state == null ? "-" : state.name());
        }

        boolean sameIdentity(View o) { return identity != null && identity.equals(o.identity); }
        boolean sameLip(View o) { return lip != null && lip.equals(o.lip); }
        boolean distinctIdentity(View o) { return identity != null && !identity.equals(o.identity); }

        String text() {
            return "state=" + geometry + " sill=" + sill + " floor=" + floor + " members=" + members.size()
                    + " identity=" + identity + " lip=" + lip + " reason=" + reason;
        }
    }

    /**
     * The <b>local</b> hydraulic cut resolved at exactly this point, with every rim face listed.
     *
     * <p>Deliberately distinct from {@link View}, which reports the <b>published</b> basin after
     * {@code WorldBasinRouting.at} follows the spill chain downstream and merges a higher receiving pan. When the two
     * disagree, that disagreement IS the backwater term, so they must never be printed under one label.
     */
    private static String localCut(WorldBasinRouting routing, WorldDrainage.Point point) {
        WorldBasinRouting.Outlet o = routing.outlet(point);
        WorldBasin.Geometry g = o.geometry();
        StringBuilder sb = new StringBuilder("LOCAL_CUT state=").append(g.state())
                .append(" floor=").append(g.floor()).append(" sill=").append(g.sill())
                .append(" members=").append(g.members().size())
                .append(" exits=").append(g.exits().size()).append('[');
        for (int i = 0; i < g.exits().size(); i++) {
            WorldBasin.Face face = g.exits().get(i);
            if (i > 0) sb.append(' ');
            sb.append(face.inner()).append("->").append(face.outer()).append('@').append(face.crest());
        }
        return sb.append("] chosen=").append(o.face()).append(" reason=").append(o.reason()).toString();
    }

    // ------------------------------------------------------------------ fixtures

    /**
     * One pit (floor 1) with a single throat at 6.5 into a below-sea outfall. The monotone base ramp is far higher
     * than the throat, so the throat is provably the lowest cut without needing a hand-built wall ring.
     */
    private static Lattice lonePit() {
        Lattice g = new Lattice(STEP, -80, -80, 41, 41);
        g.rect(12, 15, 14, 17, 1.0);
        g.set(11, 16, 6.5);
        g.set(10, 16, -9.0);
        return g;
    }

    /**
     * Two pits in series (upper floor 3, channel crest 5, lower floor 2) whose only below-floor escape is one lower
     * throat. {@code lowerThroat > 5} makes the receiving pan's sill higher, so the certified body is the merged
     * (backwater) one; {@code lowerThroat < 5} lets the upper pan drain through, keeping two separate basins.
     */
    private static Lattice series(double lowerThroat) {
        Lattice g = new Lattice(STEP, -80, -80, 41, 41);
        g.rect(12, 15, 14, 17, 3.0);
        g.set(15, 16, 5.0);
        g.set(16, 16, 5.0);
        g.rect(17, 15, 19, 17, 2.0);
        g.set(20, 16, lowerThroat);
        g.set(21, 16, -9.0);
        return g;
    }

    /**
     * Two pits (floors 1), each with its own throat at 5.0 into a below-sea outfall, joined by exactly one divider
     * cell. {@code divider < 5.0} connects them strictly below the sill; {@code divider == 5.0} makes them merely
     * touch; {@code divider > 5.0} keeps a real wall. The throats, not the divider, set the sill in every case, so
     * the three cases differ in exactly one physical quantity.
     */
    private static Lattice twinPits(double divider) {
        Lattice g = new Lattice(STEP, -80, -80, 41, 41);
        g.rect(12, 15, 14, 17, 1.0);
        g.rect(16, 15, 18, 17, 1.0);
        g.rect(15, 14, 15, 18, 40.0);
        g.set(15, 16, divider);
        g.set(11, 16, 5.0);
        g.set(10, 16, -9.0);
        g.set(19, 16, 5.0);
        g.set(20, 16, -9.0);
        return g;
    }

    // ------------------------------------------------------------------ S: synthetic geometry

    private static void geometryStage() {
        log("\n== [S] synthetic geometry on the PRODUCTION world graph (step=" + STEP + ") ==");
        lonePitStage();
        g44CrossCheck();
        backwaterSeries();
        separateSeries();
        connectedBelowSill();
        touchingAtSill();
    }

    private static void lonePitStage() {
        Lattice g = lonePit();
        Fixture f = new Fixture(g);
        int bi = 12, bj = 15;
        WorldDrainage.Point sink = f.at(bi, bj);
        View v = f.view(bi, bj);
        log("  LONE_PIT " + localCut(f.routing, sink));
        check(v.published && v.lip != null && near(v.lip.crest(), v.sill) && v.reason.isEmpty(),
                "S1 a single pit publishes a certified outlet whose crest is the sill", v.text());
        double oracle = firstOutlet(g, g.blockX(bi), g.blockZ(bj));
        check(near(v.sill, oracle),
                "S1 certified sill equals the independent union-find minimax oracle (exact at step 1)",
                "prod=" + v.sill + " oracle=" + oracle);
        check(near(v.sill, 6.5), "S1 the sill is the designed throat crest, not the surrounding height",
                "prod=" + v.sill + " expected=6.5");
        int[][] entries = {{12, 15}, {13, 16}, {14, 17}, {12, 17}, {14, 15}};
        boolean same = true;
        StringBuilder seen = new StringBuilder();
        for (int[] e : entries) {
            View other = f.view(e[0], e[1]);
            same &= other.published && other.sameIdentity(v) && other.sameLip(v)
                    && other.members.equals(v.members) && near(other.sill, v.sill);
            seen.append(e[0]).append('/').append(e[1]).append(' ');
        }
        check(same, "S2 all five pit entries share basin identity, certified lip, sill and member set",
                "entries=" + seen + "ref=" + v.text());
        View upland = f.view(1, 1);
        check(!upland.published && upland.identity == null,
                "S2 a below-sea lattice point has no basin identity at all (identity is not universal)",
                upland.text());
        log("  PAN_LOCAL_FIRST_OUTLET=" + oracle + " CERTIFIED_SILL=" + v.sill
                + " — one pit: the pan-local outlet alone already fixes the level");
    }

    /**
     * G44 cross-check. {@link CertifiedBasinQuery} floods 4-neighbours on an int terrain and stops at the declared
     * ocean, so it is compared against an independent 4-neighbour minimax here — not against the 8-neighbour graph.
     */
    private static void g44CrossCheck() {
        CertifiedBasinQuery.Terrain t = (x, z) -> {
            if (x <= -9) return -1;
            if (x == -8 && z == 0) return 3;
            if (x >= -7 && x <= 7 && z >= -4 && z <= 4) return 1;
            return 10;
        };
        CertifiedBasinQuery query = new CertifiedBasinQuery(t, (x, z) -> x <= -9, 0, 1);
        CertifiedBasinQuery.Result a = query.resolve(-5, 0, 1000), b = query.resolve(5, 2, 1000);
        double oracle = fourMinimax(t, -5, 0);
        check(a.status() == CertifiedBasinQuery.Status.RESOLVED && near(a.escapeLevel(), oracle),
                "G44 local certificate: escape level equals an independent 4-neighbour minimax",
                "g44=" + a.escapeLevel() + " oracle=" + oracle);
        check(b.status() == CertifiedBasinQuery.Status.RESOLVED && a.owner().equals(b.owner())
                        && a.members().equals(b.members()),
                "G44 two roots of one pan share basin identity and member set",
                a.owner() + "/" + a.members().size() + " vs " + b.owner() + "/" + b.members().size());
        CertifiedBasinQuery.Result starved = query.resolve(-5, 0, 4);
        check(starved.status() == CertifiedBasinQuery.Status.SEARCH_PENDING
                        && Double.isNaN(starved.escapeLevel()),
                "G44 budget exhaustion is PENDING, never an invented window outlet", starved.status().name());
    }

    /** Independent 4-neighbour minimax toward the declared ocean frontier, matching the G44 sea contract. */
    private static double fourMinimax(CertifiedBasinQuery.Terrain t, int sx, int sz) {
        Map<Long, Double> best = new LinkedHashMap<>();
        java.util.PriorityQueue<double[]> pq =
                new java.util.PriorityQueue<>((x, y) -> x[0] != y[0] ? Double.compare(x[0], y[0])
                        : x[1] != y[1] ? Double.compare(x[1], y[1]) : Double.compare(x[2], y[2]));
        double bottom = t.height(sx, sz);
        best.put(((long) sx << 32) ^ (sz & 0xffffffffL), bottom);
        pq.add(new double[]{bottom, sx, sz});
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        while (!pq.isEmpty()) {
            double[] head = pq.poll();
            int x = (int) head[1], z = (int) head[2];
            if (head[0] > best.getOrDefault(((long) x << 32) ^ (z & 0xffffffffL), Double.MAX_VALUE)) continue;
            if (x <= -9) return Math.max(0, head[0]);
            for (int[] d : dirs) {
                int nx = x + d[0], nz = z + d[1];
                double cost = Math.max(head[0], t.height(nx, nz));
                long k = ((long) nx << 32) ^ (nz & 0xffffffffL);
                if (cost < best.getOrDefault(k, Double.MAX_VALUE) - 1e-15) {
                    best.put(k, cost);
                    pq.add(new double[]{cost, nx, nz});
                }
            }
        }
        return Double.POSITIVE_INFINITY;
    }

    /** Lower throat above the upper pan's own outlet ⇒ one merged (backwater) body with a higher sill. */
    private static void backwaterSeries() {
        Lattice g = series(6.5);
        Fixture f = new Fixture(g);
        View upper = f.view(12, 15), lower = f.view(17, 15);
        double panLocal = firstOutlet(g, g.blockX(12), g.blockZ(15));
        double bodyOracle = firstOutlet(g, g.blockX(17), g.blockZ(15));
        log("  BACKWATER upper=" + upper.text());
        log("  BACKWATER lower=" + lower.text());
        log("  PAN_LOCAL_FIRST_OUTLET=" + panLocal + " WHOLE_BODY_ORACLE=" + bodyOracle);
        if (!upper.published || !lower.published) {
            unresolved("S3 backwater series: the two pans of one body are not both certified",
                    "upper=" + upper.text() + " lower=" + lower.text());
            return;
        }
        check(upper.sameIdentity(lower) && upper.sameLip(lower),
                "S3 two pans in series certify one body with one identity and one lip",
                upper.text() + " | " + lower.text());
        check(upper.members.size() >= 20, "S3 the certified body contains both floors and the channel",
                "members=" + upper.members.size());
        check(near(upper.sill, bodyOracle),
                "S3 the certified sill equals the independent whole-body oracle",
                "prod=" + upper.sill + " oracle=" + bodyOracle);
        check(panLocal < upper.sill,
                "S3 the pan-local first outlet is strictly BELOW the certified merged sill (backwater is real)",
                "panLocal=" + panLocal + " certified=" + upper.sill);
        log("  BACKWATER_DELTA=" + (upper.sill - panLocal)
                + " — a level taken from the pan-local outlet alone would under-fill by this much");
    }

    /** Lower throat below the upper pan's own outlet ⇒ two separate basins, sill == pan-local outlet. */
    private static void separateSeries() {
        Lattice g = series(4.0);
        Fixture f = new Fixture(g);
        View upper = f.view(12, 15), lower = f.view(17, 15);
        double panLocal = firstOutlet(g, g.blockX(12), g.blockZ(15));
        if (!upper.published || !lower.published) {
            unresolved("S4 no-backwater control: pans not certified separately",
                    "upper=" + upper.text() + " lower=" + lower.text());
            return;
        }
        check(upper.distinctIdentity(lower) && !upper.sameLip(lower),
                "S4 no-backwater control: the two pans stay two basins with distinct lips",
                upper.text() + " | " + lower.text());
        check(near(upper.sill, panLocal),
                "S4 no-backwater control: the certified sill equals the pan-local first outlet",
                "prod=" + upper.sill + " panLocal=" + panLocal);
        log("  NO_BACKWATER upperSill=" + upper.sill + " lowerSill=" + lower.sill
                + " — the merge appears only when the receiving pan's own sill is higher");
    }

    /**
     * Two pits connected strictly below the 5.0 throat sill. This is one water body, so production must refuse a
     * two-lake answer, and the ledger must call that refusal UNDECIDED — never dry. The refusal is recorded with its
     * exact reason so it cannot be confused with a pass.
     */
    private static void connectedBelowSill() {
        Lattice g = twinPits(2.0);
        Fixture f = new Fixture(g);
        View a = f.view(12, 15), b = f.view(16, 15);
        log("  CONNECTED_BELOW_SILL a " + localCut(f.routing, f.at(12, 15)));
        log("  CONNECTED_BELOW_SILL b " + localCut(f.routing, f.at(16, 15)));
        log("  CONNECTED_BELOW_SILL a=" + a.text());
        log("  CONNECTED_BELOW_SILL b=" + b.text());
        check(!(a.published && b.published && a.distinctIdentity(b)),
                "S5 pits connected strictly below the sill are never published as two lakes",
                a.text() + " | " + b.text());
        WorldBasinFlow.Water w = f.flow(PRODUCTION, DEMAND_LIMIT).at(f.at(12, 15));
        check(!ledgerState(w).equals("LEDGER_ZERO"),
                "S5 a body whose geometry is not certified is never classified LEDGER_ZERO",
                ledgerState(w) + " " + w.amount() + " reason=" + w.reason());
    }

    /**
     * Two pits whose divider crest equals the throat sill: the level sets only touch there. No merge and no invented
     * split may be published. Because the divider is the only cell at the sill, the strict-flood separation oracle
     * below is exercised on the raw lattice rather than through the published basin (whose cut is refused).
     */
    private static void touchingAtSill() {
        Lattice g = twinPits(5.0);
        Fixture f = new Fixture(g);
        View a = f.view(12, 15), b = f.view(16, 15);
        log("  TOUCHING_AT_SILL a " + localCut(f.routing, f.at(12, 15)));
        log("  TOUCHING_AT_SILL a=" + a.text());
        log("  TOUCHING_AT_SILL b=" + b.text());
        check(!(a.published && b.published && a.sameIdentity(b)),
                "S6 pits only touching at the sill are never silently merged into one lake",
                a.text() + " | " + b.text());
        check(strictFloodSeparates(g, 5.0, 12, 16),
                "S6 independent strict flood keeps the two pre-sill level sets separate",
                "throats at i=12 and i=16, divider crest 5.0");
    }

    /**
     * The strict-flood identity oracle, run directly on the lattice at a given sill. With {@code <} the two pit
     * level sets must be disjoint; relaxing to {@code <=} must merge them through the divider cell. Both halves are
     * required, so a flood that is too strict and one that is too loose each fail here instead of cancelling out.
     */
    private static boolean strictFloodSeparates(Lattice g, double sill, int leftSeed, int rightSeed) {
        Set<Integer> leftStrict = floodFrom(g, g.blockX(leftSeed), g.blockZ(16), sill, true);
        Set<Integer> rightStrict = floodFrom(g, g.blockX(rightSeed), g.blockZ(16), sill, true);
        Set<Integer> leftLoose = floodFrom(g, g.blockX(leftSeed), g.blockZ(16), sill, false);
        Set<Integer> rightLoose = floodFrom(g, g.blockX(rightSeed), g.blockZ(16), sill, false);
        boolean disjoint = Collections.disjoint(leftStrict, rightStrict);
        boolean mergesWhenLoose = !Collections.disjoint(leftLoose, rightLoose);
        boolean dividerReached = leftLoose.contains(pack(g.blockX(15), g.blockZ(16)));
        log("  STRICT_FLOOD left=" + leftStrict.size() + " right=" + rightStrict.size() + " disjoint=" + disjoint
                + " | LE_FLOOD merges=" + mergesWhenLoose + " dividerReached=" + dividerReached);
        return disjoint && mergesWhenLoose && dividerReached;
    }

    private static Set<Integer> floodFrom(Lattice g, int sx, int sz, double sill, boolean strict) {
        Set<Integer> seen = new HashSet<>();
        seen.add(pack(sx, sz));
        ArrayDeque<WorldDrainage.Point> queue = new ArrayDeque<>();
        queue.add(new WorldDrainage.Point(sx, sz));
        while (!queue.isEmpty()) {
            WorldDrainage.Point p = queue.remove();
            for (int di = -1; di <= 1; di++) for (int dj = -1; dj <= 1; dj++) {
                if (di == 0 && dj == 0) continue;
                int x = p.x() + di * g.step, z = p.z() + dj * g.step;
                if (!g.inside(x, z) || seen.contains(pack(x, z))) continue;
                double h = g.height(x, z);
                if (strict ? h < sill : h <= sill) {
                    seen.add(pack(x, z));
                    queue.add(new WorldDrainage.Point(x, z));
                }
            }
        }
        return seen;
    }

    // ------------------------------------------------------------------ W: synthetic water ledger

    private static void waterStage() {
        log("\n== [W] synthetic water ledger on the PRODUCTION DemandFlow / WorldBasinFlow ==");
        Lattice g = series(6.5);
        Fixture f = new Fixture(g);
        WorldBasinFlow flow = f.flow(SYNTH, DEMAND_LIMIT);
        WorldBasinFlow.Water wu = flow.at(f.at(12, 15)), wl = flow.at(f.at(17, 15));
        if (wu.known() && wl.known())
            check(wu.amount().equals(wl.amount()),
                    "W1 one merged body is one ledger node: identical Amount from either pan",
                    "upper=" + wu.amount() + " lower=" + wl.amount());
        else
            undecided("W1 merged body is one ledger node",
                    "series body has no certificate (upper=" + wu.amount() + " lower=" + wl.amount() + ")");
        massBalance(g, wu);
        climateCases();
    }

    private static void massBalance(Lattice g, WorldBasinFlow.Water w) {
        if (!w.known() || !w.amount().exact() || w.basin() == null) {
            undecided("W2 independent catchment mass balance",
                    "no exact water for the merged body: " + w.amount() + " reason=" + w.reason());
            return;
        }
        int cells = catchment(g, w.basin().members());
        double unit = g.step * (double) g.step;
        double inflow = cells * unit * SYNTH.precipitation() * SYNTH.runoffCoeff();
        double loss = w.basin().members().size() * unit * SYNTH.evaporation();
        double oracle = Math.max(0, inflow - loss);
        check(near(w.amount().bound(), oracle),
                "W2 independent reverse-D8 catchment mass balance reproduces the exact discharge",
                "prod=" + w.amount().bound() + " oracle=" + oracle + " cells=" + cells
                        + " members=" + w.basin().members().size());
        check(inflow > 0 && loss > 0 && !near(oracle, inflow),
                "W2 both terms are exercised: dropping the evaporation term would change the number",
                "inflow=" + inflow + " loss=" + loss + " oracle=" + oracle);
    }

    /** Four independent water expectations on one geometry, plus the UNKNOWN-vs-dry negative control. */
    private static void climateCases() {
        log("  -- four independent water expectations on one pit (three climates + one budget) --");
        Lattice g = lonePit();
        Fixture f = new Fixture(g);
        WorldDrainage.Point root = f.at(12, 15);
        WorldBasinFlow.Water zero = f.flow(new LakeWaterBalance.Climate(0, 0.002, 0.3), DEMAND_LIMIT).at(root);
        check(zero.known() && zero.amount().exact() && zero.amount().bound() == 0
                        && ledgerState(zero).equals("LEDGER_ZERO"),
                "W3 zero inflow is a known EXACT ledger zero, not an unresolved certificate",
                zero.amount() + " level=" + zero.level());
        WorldBasinFlow.Water drained = f.flow(new LakeWaterBalance.Climate(0.004, 1e6, 0.3), DEMAND_LIMIT).at(root);
        check(drained.known() && drained.amount().exact() && drained.amount().bound() == 0
                        && ledgerState(drained).equals("LEDGER_ZERO"),
                "W4 local evaporation exhausting the pit is a known ledger zero, computed independently of W3",
                drained.amount() + " level=" + drained.level());
        WorldBasinFlow.Water filled = f.flow(new LakeWaterBalance.Climate(0.004, 0, 0.3), DEMAND_LIMIT).at(root);
        check(filled.known() && filled.basin() != null && ledgerState(filled).equals("LEDGER_POSITIVE")
                        && near(filled.level(), filled.basin().sill() - 1e-9),
                "W5 sufficient water fills the pit to its certified sill (overflow), never above it",
                "level=" + filled.level() + " sill=" + (filled.basin() == null ? Double.NaN : filled.basin().sill()));
        WorldBasinFlow.Water starved = f.flow(PRODUCTION, 4).at(root);
        check(!starved.known() && ledgerState(starved).equals("UNDECIDED"),
                "W6 budget-exhausted UNKNOWN stays UNDECIDED", starved.amount() + " reason=" + starved.reason());
        check(ledgerStateBroken(starved).equals("LEDGER_ZERO")
                        && !ledgerState(starved).equals(ledgerStateBroken(starved)),
                "W6 negative control: the classifier that folds UNKNOWN into zero really does, and is rejected",
                "broken=" + ledgerStateBroken(starved) + " correct=" + ledgerState(starved));
    }

    /**
     * Ledger-state classification. Deliberately <b>not</b> named wet/dry: a positive steady discharge does not mean
     * the carved water column reaches even half a block, so this is a statement about the water <b>ledger</b>, not a
     * production surface-wetness oracle. Only {@code LEDGER_ZERO} is a real zero; an UNKNOWN certificate is
     * {@code UNDECIDED} and is never folded into the zero case.
     */
    private static String ledgerState(WorldBasinFlow.Water w) {
        if (!w.known()) return "UNDECIDED";
        return w.amount().exact() && w.amount().bound() == 0 ? "LEDGER_ZERO" : "LEDGER_POSITIVE";
    }

    /**
     * Deliberately wrong classifier used only as a negative control. It encodes the exact failure mode the user
     * prohibits — treating an UNKNOWN certificate as zero — so {@link #climateCases()} proves that assertion can
     * actually fail. Without this the UNKNOWN test passes on the strength of the correct classifier alone.
     */
    private static String ledgerStateBroken(WorldBasinFlow.Water w) {
        return w.known() && w.amount().exact() && w.amount().bound() > 0 ? "LEDGER_POSITIVE" : "LEDGER_ZERO";
    }

    // ------------------------------------------------------------------ D: demand oracle

    /**
     * Builds the demand fixture. Every node has at most one successor, so the <b>whole</b> graph is single-successor
     * — not merely each query tree. The two pans that both need the spring at index 3 each get their <b>own copy</b>
     * of it (indices 3 and 6). Sharing one node would make index 3 have two successors (2 and 5), so {@code DemandFlow}
     * would only stay legal because each root happens to be queried separately; the fixture is therefore made
     * globally legal, and the forward oracle sees exactly the same graph.
     *
     * <pre>
     *   0 (spring)  -> 1 (+3)  -> 2 (confluence +14, loss 3)  -> 4 (loss 9) -> closed
     *   3 (spring)  -> 2
     *   6 (spring)  -> 5 (loss 6)   [separate pan: its own loss exceeds what index 3 supplies]
     * </pre>
     */
    private static List<DemandFlow.Node<Integer>> demandFixture() {
        return List.of(
                new DemandFlow.Node<>(0, 0, 1, List.of()),          // 0 spring
                new DemandFlow.Node<>(3, 0, 1, List.of(0)),         // 1 +3
                new DemandFlow.Node<>(14, 3, 1, List.of(1, 3)),     // 2 confluence, loss 3
                new DemandFlow.Node<>(4, 1, 1, List.of()),          // 3 spring feeding 2
                new DemandFlow.Node<>(0, 9, 1, List.of(2)),         // 4 downstream pan, loss 9
                new DemandFlow.Node<>(0, 6, 1, List.of(6)),         // 5 separate pan, loss 6
                new DemandFlow.Node<>(0, 0, 1, List.of()));         // 6 its own spring (copy of 3's role)
    }

    private static void demandStage() {
        log("\n== [D] production DemandFlow against an independent forward mass balance ==");
        List<DemandFlow.Node<Integer>> graph = demandFixture();
        checkSingleSuccessor(graph);
        double[] exact = forward(graph);
        log("  independent forward values = " + Arrays.toString(exact));
        DemandFlow<Integer> solver = new DemandFlow<>(graph::get, 64);
        boolean ok = true;
        int compared = 0;
        for (int i = 0; i < graph.size(); i++) {
            for (double demand : new double[]{0, 1, 3, 4, 13, 16, Double.POSITIVE_INFINITY}) {
                DemandFlow.Amount a = solver.query(i, demand).amount();
                compared++;
                ok &= a.known() && a.bound() <= exact[i] && a.reaches(demand) == (exact[i] >= demand);
                ok &= !a.exact() || a.exactValue() == exact[i];
                ok &= a.exact() || Double.isNaN(a.exactValue());
            }
        }
        check(ok, "D1 independent forward oracle over a lossy basin series",
                compared + " demands, forward=" + Arrays.toString(exact));

        DemandFlow.Amount near = solver.query(2, 13).amount();
        check(near.known() && !near.exact() && near.reaches(13) && near.bound() <= exact[2],
                "D2 a demand satisfied before the upstream tree is complete returns AT_LEAST, not EXACT",
                near + " exact=" + exact[2]);
        DemandFlow.Amount full = solver.query(2, Double.POSITIVE_INFINITY).amount();
        check(full.exact() && full.bound() == exact[2],
                "D2 the full-precision query equals the forward oracle at the confluence",
                full + " forward=" + exact[2]);
        double withoutLoss = exact[1] + exact[3];
        check(!near(exact[2], withoutLoss),
                "D2 negative control: skipping the confluence loss changes the value",
                "withLoss=" + exact[2] + " withoutLoss=" + withoutLoss);

        DemandFlow.Amount dryPan = solver.query(5, 1).amount();
        check(dryPan.exact() && dryPan.bound() == 0 && exact[5] == 0,
                "D2 a pan whose own loss exceeds the surplus it receives is EXACT zero, never UNKNOWN",
                dryPan + " forward=" + exact[5]);

        List<DemandFlow.Node<Integer>> broken = new ArrayList<>(graph);
        broken.set(2, new DemandFlow.Node<>(14, 3, 1, List.of(1)));
        double[] brokenExact = forward(broken);
        check(!near(brokenExact[2], exact[2]),
                "D2 negative control: dropping one tributary is detected by the same comparison",
                brokenExact[2] + " vs " + exact[2]);
        DemandFlow.Amount brokenFull = new DemandFlow<Integer>(broken::get, 64)
                .query(2, Double.POSITIVE_INFINITY).amount();
        check(brokenFull.exact() && brokenFull.bound() == brokenExact[2]
                        && !near(brokenFull.bound(), exact[2]),
                "D2 negative control: production follows the broken graph, so the oracle is not self-fulfilling",
                brokenFull + " vs " + exact[2]);
    }

    // ------------------------------------------------------------------ R: real erodedHeight
    /**
     * Independent forward mass balance. Relaxed to a fixed point because the fixture's node list is deliberately in
     * query order, not topological order — a single forward pass would silently read an uncomputed tributary as 0 and
     * make the oracle agree with nothing.
     */
    /**
     * Asserts the fixture really is single-successor <b>globally</b>: every node appears as an input of at most one
     * node. Without this the fixture would only be legal per-query, and "production accepted it" would prove nothing
     * about the graph contract.
     */
    private static void checkSingleSuccessor(List<DemandFlow.Node<Integer>> graph) {
        Map<Integer, List<Integer>> consumers = new java.util.TreeMap<>();
        for (int i = 0; i < graph.size(); i++)
            for (int input : graph.get(i).inputs())
                consumers.computeIfAbsent(input, k -> new ArrayList<>()).add(i);
        List<String> shared = new ArrayList<>();
        for (var entry : consumers.entrySet())
            if (entry.getValue().size() > 1) shared.add(entry.getKey() + "->" + entry.getValue());
        check(shared.isEmpty(),
                "D0 the demand fixture is globally single-successor (no node has two consumers)",
                "shared inputs=" + shared);
        log("  D0 consumers=" + consumers);
    }

    private static double[] forward(List<DemandFlow.Node<Integer>> graph) {
        double[] exact = new double[graph.size()];
        for (int pass = 0; pass < graph.size(); pass++) {
            boolean moved = false;
            for (int i = 0; i < graph.size(); i++) {
                DemandFlow.Node<Integer> n = graph.get(i);
                double total = n.source();
                for (int child : n.inputs()) total += exact[child];
                double value = Math.max(0, total - n.loss());
                if (value != exact[i]) { exact[i] = value; moved = true; }
            }
            if (!moved) break;
        }
        return exact;
    }

    // ------------------------------------------------------------------ R: real erodedHeight

    private static void realStage() {
        log("\n== [R] real erodedHeight samples (read-only config copy, production seed) ==");
        CellGenerator gen;
        try {
            gen = realGenerator();
        } catch (Throwable failure) {
            undecided("R0 real terrain setup", String.valueOf(failure));
            return;
        }
        if (gen == null) return;
        TerrainParams tp = gen.params();
        int step = Integer.getInteger("geogenesis.modelStep", 4);
        double hs = Math.max(0.01, tp.horizontalScale());
        WindowOutlets.Terrain terrain = (x, z) -> gen.erodedHeight(x / hs, z / hs);
        WorldDrainage raw = new WorldDrainage(step, 150, tp.seaLevel(), terrain);
        WorldBasinRouting routing = new WorldBasinRouting(raw, terrain, step, GEOMETRY_LIMIT);
        WorldDrainage.Point root = realPan(raw, routing, step);
        if (root != null) {
            realPanEntries(raw, routing, root);
            realFirstOutlet(routing, terrain, step, root);
            blockExactStage(terrain);
            physicalStage(routing, terrain, step, raw);
        }
        conflictCell("(-372,-96)", -372, -96, raw, routing, terrain, step);
        conflictCell("(-108,-520)", -108, -520, raw, routing, terrain, step);
    }

    /**
     * The <b>real first outlet</b>, certified the G44 way: a complete strict level set plus every adjacency cut,
     * not just the one face production happened to select.
     *
     * <p>Three independent things are checked on real {@code erodedHeight}:
     * <ol>
     *   <li><b>Complete strict level set</b> — re-flooding from the basin's own floor with {@code crest < sill}
     *       must reproduce the published member set exactly. A truncated flood cannot pass, because a missing
     *       member would show up as a difference rather than a smaller set.</li>
     *   <li><b>All adjacency cuts closed</b> — every {@code (member -> non-member)} pair is enumerated and its
     *       integer-column crest computed. The minimum must <i>attain</i> the sill and no cut may sit below it,
     *       which is what "the spill is the lowest closed cut" actually means. Testing the selected lip alone
     *       cannot distinguish that from "one face happens to be lowest".</li>
     *   <li><b>G44 cross-domain indicator</b> — {@link CertifiedBasinQuery} is run on the same real terrain with its
     *       ocean declared as "strictly below the basin floor", from the basin's lowest member. It reports a
     *       <b>different sampling domain</b> (unit four-neighbour on block coordinates) and is therefore recorded as
     *       a divergence, never as containment or equality. See {@link #g44Real}.</li>
     * </ol>
     *
     * <p><b>Scope, stated precisely</b>: everything here is a complete cut on the <b>coarse step-{@code step}
     * eight-neighbour graph</b>. It is <b>not</b> a block-exact basin certification: the coarse crest is a max over
     * interior integer columns, but the flood and the cut enumeration never visit every block. Real terrain here
     * also yields a small local body (a handful of coarse members), so this certifies <i>that</i> body's cut and
     * says nothing about whole-basin or block-level exactness elsewhere.
     */
    private static void realFirstOutlet(WorldBasinRouting routing, WindowOutlets.Terrain terrain, int step,
                                       WorldDrainage.Point root) {
        long t0 = System.nanoTime();
        View ref = View.of(routing.at(root));
        WorldBasin.Geometry g = routing.outlet(root).geometry();
        if (g.members().isEmpty() || !Double.isFinite(g.sill()) || !Double.isFinite(g.floor())) {
            undecided("R5 complete strict level set", "no settled geometry at " + root + ": " + ref.text());
            return;
        }
        List<WorldDrainage.Point> members = g.members();
        double sill = g.sill(), floor = g.floor();

        Set<WorldDrainage.Point> reflood;
        try {
            reflood = floodStrict(terrain, step, g.identity(), sill);
        } catch (java.util.concurrent.CancellationException bounded) {
            unresolved("R5 complete strict level set could not be closed",
                    "floodStrict refused at " + bounded.getMessage() + " (limit=" + GEOMETRY_LIMIT
                            + "); reported undecided, never as an absent or dry body");
            return;
        }
        check(reflood.size() == members.size() && reflood.containsAll(members),
                "R5 the coarse strict level set re-floods to exactly the published members (complete, not truncated)",
                "reflood=" + reflood.size() + " published=" + members.size());

        Cut cut = allCuts(terrain, step, members, sill);
        check(near(cut.minCrest, sill),
                "R5 the lowest of ALL adjacency cuts attains the certified sill",
                "minCutCrest=" + cut.minCrest + " sill=" + sill + " cuts=" + cut.edges);
        check(cut.belowSill == 0,
                "R5 no adjacency cut lies below the sill (the cut set is closed)",
                "belowSill=" + cut.belowSill + " of " + cut.edges);
        check(cut.edges > 0 && cut.atSill >= 1,
                "R5 the cut is non-empty: the sill is attained on real cut edges", "atSill=" + cut.atSill);
        log("  R5 COMPLETE_CUT edges=" + cut.edges + " atSill=" + cut.atSill + " minCrest=" + cut.minCrest
                + " sill=" + sill + " members=" + members.size() + " floor=" + floor);

        g44Real(terrain, step, floor, members, sill);
        log("  real first-outlet stage ms=" + (System.nanoTime() - t0) / 1_000_000L);
    }

    /** Every {@code (member -> non-member)} adjacency cut, enumerated in full with its integer-column crest. */
    private record Cut(int edges, int atSill, int belowSill, double minCrest) { }

    private static Cut allCuts(WindowOutlets.Terrain terrain, int step, List<WorldDrainage.Point> members,
                              double sill) {
        Set<WorldDrainage.Point> inside = new HashSet<>(members);
        int edges = 0, atSill = 0, belowSill = 0;
        double min = Double.POSITIVE_INFINITY;
        for (WorldDrainage.Point m : members) {
            for (int di = -1; di <= 1; di++) for (int dj = -1; dj <= 1; dj++) {
                if (di == 0 && dj == 0) continue;
                WorldDrainage.Point n = new WorldDrainage.Point(m.x() + di * step, m.z() + dj * step);
                if (inside.contains(n)) continue;
                double crest = columnCrest(terrain, m, n, step);
                edges++;
                min = Math.min(min, crest);
                if (crest < sill - 1e-9) belowSill++;
                if (near(crest, sill)) atSill++;
            }
        }
        return new Cut(edges, atSill, belowSill, min);
    }

    /**
     * Crest of the prospective channel between two lattice points: the maximum over <b>every</b> integer column
     * along the segment, which is the same operator production uses. Sampling just the two endpoints would hide a
     * ridge cell in between and make the cut look lower than it is.
     */
    private static double columnCrest(WindowOutlets.Terrain terrain, WorldDrainage.Point a, WorldDrainage.Point b,
                                      int step) {
        double h = Math.max(terrain.height(a.x(), a.z()), terrain.height(b.x(), b.z()));
        for (int i = 1; i < step; i++) {
            int x = a.x() + (b.x() - a.x()) * i / step, z = a.z() + (b.z() - a.z()) * i / step;
            h = Math.max(h, terrain.height(x, z));
        }
        return h;
    }

    /**
     * Complete strict level set from the basin's own floor: coarse 8-neighbour, {@code crest < sill}.
     *
     * <p>Bounded by {@link #GEOMETRY_LIMIT} cells and cooperative on interrupt. A flood that hits either bound
     * <b>throws</b> rather than returning a short set: a truncated level set would make the completeness assertion
     * fail for the wrong reason, or worse, silently pass against a coincidentally equal published set. Callers turn
     * the throw into an explicit UNRESOLVED/UNDECIDED, never into "dry" or "not present".
     */
    private static Set<WorldDrainage.Point> floodStrict(WindowOutlets.Terrain terrain, int step,
                                                        WorldDrainage.Point seed, double sill) {
        Set<WorldDrainage.Point> seen = new HashSet<>();
        ArrayDeque<WorldDrainage.Point> queue = new ArrayDeque<>();
        seen.add(seed);
        queue.add(seed);
        while (!queue.isEmpty()) {
            if (Thread.currentThread().isInterrupted())
                throw new java.util.concurrent.CancellationException("floodStrict interrupted");
            WorldDrainage.Point p = queue.remove();
            for (int di = -1; di <= 1; di++) for (int dj = -1; dj <= 1; dj++) {
                if (di == 0 && dj == 0) continue;
                WorldDrainage.Point n = new WorldDrainage.Point(p.x() + di * step, p.z() + dj * step);
                if (seen.contains(n)) continue;
                if (columnCrest(terrain, p, n, step) < sill) {
                    seen.add(n);
                    if (seen.size() > GEOMETRY_LIMIT)
                        throw new java.util.concurrent.CancellationException(
                                "floodStrict exceeded GEOMETRY_LIMIT=" + GEOMETRY_LIMIT);
                    queue.add(n);
                }
            }
        }
        return seen;
    }

    /**
     * G44 on the same real terrain, started from the basin's own <b>floor</b>.
     *
     * <p><b>Why the floor and not {@code identity}</b>: {@code CertifiedBasinQuery.capacity} returns an empty
     * certificate when {@code height(start) >= level}. A basin's published {@code identity} is its
     * lexicographically-first member, which need not sit on the floor, so starting there silently yields
     * {@code members=0}. Starting at the lowest member is the configuration the G44 contract actually describes.
     *
     * <p><b>It is NOT a "4 vs 8 neighbour" comparison.</b> {@code CertifiedBasinQuery} takes a {@code cellSize} but
     * uses it only for {@code area} (capacity). Its lattice is always {@code neighbor = ±1} on the raw integer
     * coordinates it is given, i.e. <b>unit four-neighbour</b> adjacency on whatever grid the caller feeds it. The
     * caller here passes <b>block</b> coordinates, so G44 sees a unit 4-neighbour graph over blocks, while
     * production routes <b>coarse step-4 eight-neighbour</b> edges whose crest is a max over the interior integer
     * columns. Those are genuinely different sampling domains, so their escape levels are recorded side by side and
     * are <b>not</b> required to agree. Consequently G44's members are block coordinates and must <b>not</b> be
     * forced to be a subset of the coarse member set — no containment is asserted in either direction.
     *
     * <p>What G44 <i>is</i> useful for here: an independent <b>synthetic-domain</b> certificate (see
     * {@link #g44CrossCheck()}), where the terrain is a unit lattice by construction and a complete strict level set
     * plus closed cut set can be checked exactly. On real terrain it is reported as a cross-domain indicator.
     */
    private static void g44Real(WindowOutlets.Terrain terrain, int step, double floor,
                                List<WorldDrainage.Point> members, double sill) {
        WorldDrainage.Point lowest = members.stream()
                .min(java.util.Comparator.comparingDouble((WorldDrainage.Point p) -> terrain.height(p.x(), p.z()))
                        .thenComparingInt(WorldDrainage.Point::x).thenComparingInt(WorldDrainage.Point::z))
                .orElseThrow();
        CertifiedBasinQuery.Terrain t = (x, z) -> terrain.height(x, z);
        // G44's lattice is always unit-4-neighbour on the coordinates handed in, so it is given block coordinates
        // (cellSize only scales the capacity area; it does not change adjacency).
        CertifiedBasinQuery query = new CertifiedBasinQuery(t, (x, z) -> terrain.height(x, z) < floor,
                floor - 1e9, 1);
        CertifiedBasinQuery.Result result;
        try {
            result = query.resolve(lowest.x(), lowest.z(), GEOMETRY_LIMIT);
        } catch (RuntimeException failure) {
            undecided("R6 G44 cross-domain indicator on real terrain", "query threw " + failure);
            return;
        }
        log("  R6 G44 status=" + result.status() + " escape=" + result.escapeLevel() + " owner=" + result.owner()
                + " members=" + result.members().size() + " sampled=" + result.sampledCells()
                + " startFloor=" + lowest + "(" + terrain.height(lowest.x(), lowest.z()) + ")"
                + " DOMAIN=unit-four-neighbour-on-blocks");
        if (result.status() != CertifiedBasinQuery.Status.RESOLVED) {
            undecided("R6 G44 cross-domain indicator on real terrain",
                    "did not close within budget: " + result.status() + " sampled=" + result.sampledCells());
            return;
        }
        check(Double.isFinite(result.escapeLevel()),
                "R6 G44 closes its own unit-4-neighbour search to a definite escape level on real terrain",
                "escape=" + result.escapeLevel() + " sampled=" + result.sampledCells());
        // Compared as two different sampling domains. No containment and no equality is required.
        divergence("R6 G44 unit-4-neighbour-on-blocks vs production coarse step-" + step + " eight-neighbour crest",
                "g44Escape=" + result.escapeLevel() + " g44Members=" + result.members().size()
                        + " capacity=" + result.capacity()
                        + " | prodSill=" + sill + " prodMembers=" + members.size()
                        + " delta=" + (result.escapeLevel() - sill)
                        + " — different sampling domains: G44 floods unit 4-neighbour on block coordinates,"
                        + " production routes coarse 8-neighbour edges with an integer-column max crest;"
                        + " neither is required to match the other, and G44 members are block coords,"
                        + " not coarse members");
    }

    // ------------------------------------------------------------------ B: block-exact local basin

    /**
     * A genuine <b>block-level</b> local basin, distinct from the coarse R5 cut.
     *
     * <p>Four things make this block-exact rather than coarse:
     * <ol>
     *   <li><b>Descent is per block.</b> Starting near the R5 sample, walk unit four-neighbour steps down 1 block at
     *       a time to a true integer local minimum. The coarse floor is never used as a sink; the descent crosses
     *       coarse cells freely and only stops when no 4-neighbour is strictly lower.</li>
     *   <li><b>Escape is G44 unit four-neighbour</b> on that sink, with the ocean predicate being "strictly below the
     *       sink block", so the certificate's level is the first crest at which water leaves downward.</li>
     *   <li><b>Members are re-flooded independently</b> at that level with {@code <} over unit four-neighbours, and
     *       must reproduce G44's complete strict member set exactly (both directions, so neither a short nor a long
     *       flood passes).</li>
     *   <li><b>All four-neighbour cuts are enumerated</b>; the minimum must attain the escape level and none may sit
     *       below it, and G44 must report a non-empty capacity.</li>
     * </ol>
     *
     * <p>Budget exhaustion is reported as a pending candidate, never as "no basin". This stage never uses the coarse
     * stride: every step here is exactly one block.
     */
    private static void blockExactStage(WindowOutlets.Terrain terrain) {
        long t0 = System.nanoTime();
        if (physicalSink == null) {
            undecided("B1 block-exact basin", "no certified coarse sink to start the block descent from");
            return;
        }
        WorldDrainage.Point start = new WorldDrainage.Point(physicalSink.x(), physicalSink.z());
        WorldDrainage.Point sink = descendToBlockMinimum(terrain, start);
        if (sink == null) {
            undecided("B1 block-exact basin", "block descent did not reach a local minimum from " + start);
            return;
        }
        double floor = terrain.height(sink.x(), sink.z());
        check(near(floor, terrain.height(start.x(), start.z())) || floor < terrain.height(start.x(), start.z()),
                "B1 the block descent reached a floor no higher than its starting block",
                "start=" + start + "@" + terrain.height(start.x(), start.z()) + " sink=" + sink + "@" + floor);
        check(strictFourMinimum(terrain, sink),
                "B1 the sink is a true integer local minimum under unit four-neighbour descent",
                "sink=" + sink + "@" + floor);
        CertifiedBasinQuery query = new CertifiedBasinQuery(
                (x, z) -> terrain.height(x, z), (x, z) -> terrain.height(x, z) < floor, floor - 1e9, 1);
        CertifiedBasinQuery.Result r;
        try {
            r = query.resolve(sink.x(), sink.z(), BLOCK_BUDGET);
        } catch (RuntimeException failure) {
            undecided("B2 block-exact certificate", "G44 threw " + failure);
            return;
        }
        if (r.status() != CertifiedBasinQuery.Status.RESOLVED) {
            unresolved("B2 block-exact certificate is pending at the block budget",
                    "status=" + r.status() + " sampled=" + r.sampledCells() + " budget=" + BLOCK_BUDGET
                            + " — reported pending, never as an absent basin");
            return;
        }
        verifyBlockCertificate(terrain, sink, floor, r);
        costBlock(t0, sink, floor, r);
    }

    /** Walk unit four-neighbour steps to a true integer local minimum. Never uses the coarse stride. */
    private static WorldDrainage.Point descendToBlockMinimum(WindowOutlets.Terrain terrain, WorldDrainage.Point from) {
        WorldDrainage.Point p = from;
        for (int guarded = 0; guarded < 4096; guarded++) {
            if (Thread.currentThread().isInterrupted())
                throw new java.util.concurrent.CancellationException("block descent interrupted");
            double h = terrain.height(p.x(), p.z());
            WorldDrainage.Point best = null;
            double bestH = h;
            for (int[] d : FOUR) {
                WorldDrainage.Point n = new WorldDrainage.Point(p.x() + d[0], p.z() + d[1]);
                double nh = terrain.height(n.x(), n.z());
                if (nh < bestH) { bestH = nh; best = n; }
            }
            if (best == null) return p;
            p = best;
        }
        return null;   // did not settle within the guard: reported, not silently accepted
    }

    /** True iff no unit four-neighbour is strictly lower than the sink. */
    private static boolean strictFourMinimum(WindowOutlets.Terrain terrain, WorldDrainage.Point sink) {
        double h = terrain.height(sink.x(), sink.z());
        for (int[] d : FOUR) {
            WorldDrainage.Point n = new WorldDrainage.Point(sink.x() + d[0], sink.z() + d[1]);
            if (terrain.height(n.x(), n.z()) < h) return false;
        }
        return true;
    }

    /** Independent strict reflood over unit four-neighbours, compared both ways with G44's member set. */
    private static void verifyBlockCertificate(WindowOutlets.Terrain terrain, WorldDrainage.Point sink,
                                               double floor, CertifiedBasinQuery.Result r) {
        double escape = r.escapeLevel();
        Set<Long> mine = blockReflood(terrain, sink, escape);
        Set<Long> theirs = new HashSet<>();
        for (var c : r.members()) theirs.add(blockKey(c.x(), c.z()));
        check(mine.size() == theirs.size() && mine.containsAll(theirs),
                "B3 an independent unit-4 strict reflood reproduces G44's complete member set exactly",
                "reflood=" + mine.size() + " g44=" + theirs.size() + " escape=" + escape);
        Cut cut = blockCuts(terrain, mine, escape);
        check(near(cut.minCrest, escape),
                "B3 the lowest of ALL unit-4 adjacency cuts attains the escape level",
                "minCutCrest=" + cut.minCrest + " escape=" + escape + " cuts=" + cut.edges);
        check(cut.belowSill == 0 && cut.edges > 0,
                "B3 no unit-4 adjacency cut lies below the escape level and the cut set is non-empty",
                "belowSill=" + cut.belowSill + " of " + cut.edges);
        check(r.capacity() > 0,
                "B4 G44 reports a non-empty connected capacity for the block-exact body",
                "capacity=" + r.capacity() + " members=" + theirs.size()
                        + " floor=" + floor + " (floor is a block sink, not the coarse floor)");
        StringBuilder csv = new StringBuilder("index,pointX,pointZ,terrainH,escapeLevel,floor,capacity\n");
        int i = 0;
        for (long k : new java.util.TreeSet<>(mine)) {
            int x = keyX(k), z = keyZ(k);
            csv.append(i++).append(',').append(x).append(',').append(z).append(',')
                    .append(terrain.height(x, z)).append(',').append(escape).append(',')
                    .append(floor).append(',').append(r.capacity()).append('\n');
        }
        write("block-exact-basin.csv", csv);
        log("  B3 BLOCK_EXACT sink=" + sink + " floor=" + floor + " escape=" + escape
                + " members=" + mine.size() + " cuts=" + cut.edges + " minCut=" + cut.minCrest
                + " capacity=" + r.capacity() + " STEP=1(block) NOT_COARSE=true");
    }

    /** Independent unit-4 strict reflood at {@code level}, from the block sink. */
    private static Set<Long> blockReflood(WindowOutlets.Terrain terrain, WorldDrainage.Point sink, double level) {
        Set<Long> seen = new HashSet<>();
        ArrayDeque<WorldDrainage.Point> queue = new ArrayDeque<>();
        seen.add(blockKey(sink.x(), sink.z()));
        queue.add(sink);
        while (!queue.isEmpty()) {
            if (Thread.currentThread().isInterrupted())
                throw new java.util.concurrent.CancellationException("block reflood interrupted");
            if (seen.size() > BLOCK_BUDGET)
                throw new java.util.concurrent.CancellationException("block reflood exceeded BLOCK_BUDGET");
            WorldDrainage.Point p = queue.remove();
            for (int[] d : FOUR) {
                WorldDrainage.Point n = new WorldDrainage.Point(p.x() + d[0], p.z() + d[1]);
                if (seen.contains(blockKey(n.x(), n.z()))) continue;
                if (terrain.height(n.x(), n.z()) < level) {
                    seen.add(blockKey(n.x(), n.z()));
                    queue.add(n);
                }
            }
        }
        return seen;
    }

    /** Every unit-4 cut from the member set outward, with the crest being the max of the two block heights. */
    private static Cut blockCuts(WindowOutlets.Terrain terrain, Set<Long> members, double escape) {
        int edges = 0, atSill = 0, belowSill = 0;
        double min = Double.POSITIVE_INFINITY;
        for (long k : members) {
            int x = keyX(k), z = keyZ(k);
            for (int[] d : FOUR) {
                int nx = x + d[0], nz = z + d[1];
                if (members.contains(blockKey(nx, nz))) continue;
                double crest = Math.max(terrain.height(x, z), terrain.height(nx, nz));
                edges++;
                min = Math.min(min, crest);
                if (crest < escape - 1e-9) belowSill++;
                if (Math.abs(crest - escape) <= 1e-9) atSill++;
            }
        }
        log("  B3 BLOCK_CUT edges=" + edges + " atSill=" + atSill + " minCrest=" + min + " escape=" + escape);
        return new Cut(edges, atSill, belowSill, min);
    }

    private static void costBlock(long t0, WorldDrainage.Point sink, double floor, CertifiedBasinQuery.Result r) {
        log("  B5 COST_BLOCK sink=" + sink + " floor=" + floor
                + " g44SampledCells=" + r.sampledCells() + " blockBudget=" + BLOCK_BUDGET
                + " g44Members=" + r.members().size()
                + " ms=" + (System.nanoTime() - t0) / 1_000_000L
                + " NOTE=blockExactIsLocalNotWholeBasin NOT_PRODUCTION_ACCEPTANCE=true");
    }

    // ------------------------------------------------------------------ P: physical profile + cost

    /**
     * Physical indicator and cost accounting. <b>Not a production acceptance criterion</b> — it emits a CSV and
     * counts, and the only assertions are internal consistency of what was just measured.
     *
     * <p>The profile follows the <b>complete local first outlet</b> from the basin floor out through the spill lip,
     * sampling the terrain at <b>every integer column</b> along each hop. Three distinct levels are kept apart in the
     * CSV rather than collapsed into one number:
     * <ul>
     *   <li>{@code local_first_outlet} — the pan-local siphon crest, i.e. the first level at which this pan alone
     *       reaches a strictly lower node. This is what a purely local rule would use.</li>
     *   <li>{@code certified_sill} — the sill of the <b>published</b> body after the downstream chain is followed.
     *       Equal to the local outlet when nothing below forces a merge.</li>
     *   <li>{@code merged_sill} — the same value spelled out separately when the published body merged a higher
     *       receiving pan, so a reader cannot mistake the merged number for the local one. {@code NaN} when the two
     *       coincide, since there is then no merge to report.</li>
     * </ul>
     */
    private static void physicalStage(WorldBasinRouting routing, WindowOutlets.Terrain terrain, int step,
                                      WorldDrainage raw) {
        long t0 = System.nanoTime();
        if (physicalSink == null) {
            undecided("P1 profile", "no certified real sink available from the R stage");
            return;
        }
        long samplesBefore = raw.terrainSamples();
        WorldDrainage.Point sink = physicalSink;
        View body = View.of(routing.at(sink));
        WorldBasinRouting.Outlet outlet = routing.outlet(sink);
        WorldBasin.Geometry g = outlet.geometry();
        if (g.path().isEmpty() || !Double.isFinite(g.sill())) {
            undecided("P1 profile", "no settled local geometry at " + sink + ": " + body.text());
            return;
        }
        // A merge is reported only when the published lip differs from this sink's own local outlet.
        WorldBasinRouting.Lip localLip = outlet.face() == null ? null
                : new WorldBasinRouting.Lip(outlet.face().inner(), outlet.face().outer(), outlet.face().crest());
        boolean merged = body.lip != null && localLip != null && !body.lip.equals(localLip);
        int profileSamples = writeProfile(terrain, step, sink, g, body, merged);
        costReport(raw, samplesBefore, profileSamples, g, body, outlet);
        log("  P1 profile+cost ms=" + (System.nanoTime() - t0) / 1_000_000L);
    }

    /**
     * Emits {@code outlet-profile.csv}: one row per <b>integer column</b> along the local first-outlet path.
     *
     * <p>Each hop between two coarse path nodes is expanded into every integer column along the segment (exactly the
     * columns whose max defines that hop's crest), so the profile is a real cross-section and not just a list of coarse
     * nodes. All three levels appear on every row so a single row is self-describing.
     */
    private static int writeProfile(WindowOutlets.Terrain terrain, int step, WorldDrainage.Point sink,
                                    WorldBasin.Geometry g, View body, boolean merged) {
        List<WorldDrainage.Point> path = g.path();
        double localOutlet = g.sill();
        double certifiedSill = body.sill;
        double mergedSill = merged ? certifiedSill : Double.NaN;
        StringBuilder csv = new StringBuilder("hop,columnInHop,pointX,pointZ,terrainH,terrainSamples,isSillColumn,"
                + "local_first_outlet,certified_sill,merged_sill,merged,cumulativeMaxH\n");
        double running = Double.NEGATIVE_INFINITY;
        int sillColumns = 0, rows = 0, samples = 0;
        for (int hop = 0; hop < path.size() - 1; hop++) {
            WorldDrainage.Point a = path.get(hop), b = path.get(hop + 1);
            // Every integer column of this hop, inclusive of both coarse endpoints.
            int columns = step;
            for (int c = 0; c <= columns; c++) {
                int x = a.x() + (b.x() - a.x()) * c / columns;
                int z = a.z() + (b.z() - a.z()) * c / columns;
                double h = terrain.height(x, z);
                samples++;
                running = Math.max(running, h);
                boolean isSillColumn = Math.abs(h - localOutlet) <= 1e-9;
                if (isSillColumn) sillColumns++;
                csv.append(hop).append(',').append(c).append(',').append(x).append(',').append(z).append(',')
                        .append(h).append(',').append(samples).append(',').append(isSillColumn).append(',')
                        .append(localOutlet).append(',').append(certifiedSill).append(',')
                        .append(mergedSill).append(',').append(merged).append(',')
                        .append(running).append('\n');
                rows++;
            }
        }
        check(path.size() >= 2 && rows > path.size() - 1 && sillColumns >= 1,
                "P1 the profile expands every hop into its integer columns and contains a sill column",
                "hops=" + (path.size() - 1) + " rows=" + rows + " sillColumns=" + sillColumns
                        + " localOutlet=" + localOutlet);
        check(Double.isFinite(running), "P1 every profile row carries a finite integer-column terrain height",
                "cumulativeMax=" + running);
        check(merged == Double.isFinite(mergedSill),
                "P1 the merged sill is reported separately exactly when a merge actually happened",
                "merged=" + merged + " mergedSill=" + mergedSill + " certified=" + certifiedSill);
        write("outlet-profile.csv", csv);
        log("  P1 OUTLET_PROFILE hops=" + (path.size() - 1) + " rows=" + rows + " sillColumns=" + sillColumns
                + " profileTerrainSamples=" + samples
                + " local_first_outlet=" + localOutlet + " certified_sill=" + certifiedSill
                + " merged=" + merged + " merged_sill=" + mergedSill + " sink=" + sink);
        return samples;
    }

    /**
     * Cost accounting, split by kind so a bounded working set is never passed off as bounded total work:
     * unique terrain samples, geometric member counts, the geometry budget actually in force, and the local
     * sampler's own reported count.
     */
    private static void costReport(WorldDrainage raw, long samplesBefore, int profileSamples, WorldBasin.Geometry g,
                                   View body, WorldBasinRouting.Outlet outlet) {
        long after = raw.terrainSamples();
        long routingSamples = after - samplesBefore;
        log("  P2 COST routingSamplesTotal=" + after
                + " routingSamplesThisStage=" + routingSamples
                + " profileTerrainSamples=" + profileSamples
                + " localMembers=" + g.members().size()
                + " localExits=" + g.exits().size()
                + " localPathNodes=" + g.path().size()
                + " publishedMembers=" + body.members.size()
                + " geometryLimit=" + GEOMETRY_LIMIT
                + " demandLimit=" + DEMAND_LIMIT
                + " localSamplesReported=" + g.samples()
                + " outletReason=" + (outlet.reason().isEmpty() ? "<none>" : outlet.reason()));
        // The profile samples the terrain directly, outside WorldDrainage's counter, so both kinds are reported and
        // neither is allowed to stand in for the other.
        check(routingSamples >= 0 && after > 0,
                "P2 the routing terrain-sample counter is monotone (stage delta may legitimately be 0: memoised)",
                "before=" + samplesBefore + " after=" + after);
        check(profileSamples > 0,
                "P2 the profile's own integer-column sampling is counted separately and is non-zero",
                "profileTerrainSamples=" + profileSamples);
        check(g.members().size() <= GEOMETRY_LIMIT && g.path().size() <= GEOMETRY_LIMIT,
                "P2 the local geometry stayed inside the declared geometry budget (no silent overshoot)",
                "members=" + g.members().size() + " path=" + g.path().size() + " limit=" + GEOMETRY_LIMIT);
        log("  P2 COST_CLASS kinds=routing(unique terrain, memoised)|profile(integer columns, uncached)"
                + " routingSamplesPerMember=" + (g.members().isEmpty() ? Double.NaN
                        : g.samples() / (double) g.members().size())
                + " profileSamplesPerMember=" + (g.members().isEmpty() ? Double.NaN
                        : profileSamples / (double) g.members().size())
                + " NOTE=boundedWorksetIsNotBoundedTotalWork NOT_PRODUCTION_ACCEPTANCE=true");
    }

    private static void write(String name, CharSequence body) {
        try {
            Files.writeString(OUT.resolve(name), body.toString());
        } catch (Exception failure) {
            undecided("P3 write " + name, String.valueOf(failure));
        }
    }

    private static CellGenerator realGenerator() throws Exception {
        Path src = Path.of("run", "config", "geogenesis-common.toml");
        if (!Files.exists(src)) { undecided("R0 config", src.toAbsolutePath() + " missing"); return null; }
        Path work = OUT.resolve("config-probe.toml");
        Files.copy(src, work, StandardCopyOption.REPLACE_EXISTING);
        CommentedFileConfig cfg = CommentedFileConfig.of(work, TomlFormat.instance());
        cfg.load();
        GeoGenesisConfig.SPEC.setConfig(cfg);
        TerrainParams tp = GeoGenesisConfig.INSTANCE.buildParams();
        CellGenerator gen = new CellGenerator(tp, tp.minY(), tp.maxY());
        gen.seed(seed);
        log("  config copy=" + work.toAbsolutePath() + " (the original is only read) hs=" + tp.horizontalScale()
                + " sea=" + tp.seaLevel() + " step=" + Integer.getInteger("geogenesis.modelStep", 4));
        return gen;
    }

    /**
     * Scans for a real depression and certifies it. A scan is used deliberately so no remembered coordinate can
     * silently point at a different basin than the one reported; only the coordinates actually found are asserted.
     * Selecting a certifiable sample is acceptable for this experiment, but it must be <b>transparent</b>: every
     * skipped point is counted and its cause tallied, and a skipped point is never treated as "no basin here".
     */
    private static WorldDrainage.Point realPan(WorldDrainage raw, WorldBasinRouting routing, int step) {
        long t0 = System.nanoTime();
        List<WorldDrainage.Point> sinks = new ArrayList<>();
        Map<String, Integer> skipped = new java.util.TreeMap<>();
        int scan = 8, tried = 0;
        for (int bx = -420; bx <= -300 && sinks.size() < 3; bx += scan)
            for (int bz = -160; bz <= -40 && sinks.size() < 3; bz += scan) {
                WorldDrainage.Point p = new WorldDrainage.Point(bx, bz);
                tried++;
                try {
                    if (raw.at(p).state() == WorldDrainage.State.BELOW_SEA) {
                        skipped.merge("below_sea", 1, Integer::sum);
                        continue;
                    }
                    WorldDrainage.Point sink = routing.terminal(p);
                    if (!sinks.contains(sink)) sinks.add(sink);
                } catch (IllegalArgumentException expected) {
                    skipped.merge("illegal_arg:" + expected.getMessage(), 1, Integer::sum);
                } catch (IllegalStateException expected) {
                    skipped.merge("budget_or_state:" + expected.getMessage(), 1, Integer::sum);
                } catch (RuntimeException unexpected) {
                    skipped.merge("unexpected:" + unexpected.getClass().getSimpleName(), 1, Integer::sum);
                }
            }
        log("  scan x[-420,-300] z[-160,-40] step " + scan + ": points=" + tried + " distinct terminals="
                + sinks.size() + " terrainSamples=" + raw.terrainSamples()
                + " ms=" + (System.nanoTime() - t0) / 1_000_000L);
        if (!skipped.isEmpty()) {
            // Transparent, and explicitly not a claim that these points hold no depression.
            log("  scan SKIPPED (never treated as 'no basin'): " + skipped);
            String nonSea = skipped.entrySet().stream()
                    .filter(e -> !e.getKey().equals("below_sea"))
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .reduce((a, b) -> a + ", " + b).orElse("");
            if (!nonSea.isEmpty())
                divergence("R1 scan skipped points for reasons other than below-sea",
                        nonSea + " — these are UNKNOWN samples, not absent basins; the certified sample below is"
                                + " only one certifiable point, not an exhaustive survey");
        } else {
            log("  scan SKIPPED none: every scanned point resolved (this is a certifiable sample,"
                    + " not an exhaustive survey)");
        }
        if (sinks.isEmpty()) {
            unresolved("R1 real terrain scan found no land terminal",
                    "points=" + tried + " skipped=" + skipped);
            return null;
        }
        int certified = 0, threw = 0;
        Map<String, Integer> failures = new java.util.TreeMap<>();
        for (WorldDrainage.Point sink : sinks) {
            long s0 = System.nanoTime();
            View v;
            try {
                v = View.of(routing.at(sink));
            } catch (RuntimeException failure) {
                threw++;
                failures.merge(failure.getClass().getSimpleName() + ":" + failure.getMessage(), 1, Integer::sum);
                log("  real sink " + sink + " query threw " + failure);
                continue;
            }
            log("  REAL_SINK " + sink + " " + v.text() + " ms=" + (System.nanoTime() - s0) / 1_000_000L
                    + " terrainSamples=" + raw.terrainSamples());
            if (!v.published) continue;
            log("  REAL_SINK " + localCut(routing, sink));
            certified++;
            check(v.lip != null && near(v.lip.crest(), v.sill),
                    "R1 a real erodedHeight basin publishes one outlet whose crest is the certified sill", v.text());
            check(v.members.size() > 1 && v.members.contains(v.identity),
                    "R1 the certified identity is one of its own members", v.text());
            physicalSink = sink;
            return sink;
        }
        unresolved("R1 no scanned real depression could be certified",
                "sinks=" + sinks + " certified=" + certified + " threw=" + threw + " failures=" + failures);
        return null;
    }

    private static void realPanEntries(WorldDrainage raw, WorldBasinRouting routing, WorldDrainage.Point root) {
        long t0 = System.nanoTime();
        View ref = View.of(routing.at(root));
        int limit = Math.min(8, ref.members.size());
        boolean same = true;
        int threw = 0;
        StringBuilder seen = new StringBuilder();
        for (int i = 0; i < limit; i++) {
            WorldDrainage.Point m = ref.members.get(i);
            View entry;
            try {
                entry = View.of(routing.at(m));
            } catch (RuntimeException failure) {
                // A member whose query fails is UNKNOWN, never "not a member".
                threw++;
                same = false;
                seen.append(m).append("(threw:").append(failure.getClass().getSimpleName()).append(") ");
                continue;
            }
            same &= entry.published && entry.sameIdentity(ref) && entry.sameLip(ref) && near(entry.sill, ref.sill);
            seen.append(m).append(' ');
        }
        if (threw > 0)
            log("  R2 member queries that threw (counted as failures, not as absent): " + threw);
        check(same, "R2 all " + limit + " real member entries share identity, certified lip and sill",
                "members=" + ref.members.size() + " entries=" + seen + "ref=" + ref.text());
        View again = View.of(routing.at(root));
        check(again.identity != null && again.identity.equals(ref.identity) && near(again.sill, ref.sill)
                        && java.util.Objects.equals(again.lip, ref.lip),
                "R2 a repeated query returns the identical certified basin", again.text());
        int competing = 0, unknownMembers = 0;
        for (WorldDrainage.Point m : ref.members) {
            View entry;
            try {
                entry = View.of(routing.at(m));
            } catch (RuntimeException failure) {
                unknownMembers++;      // UNKNOWN, never counted as "not competing"
                continue;
            }
            if (entry.published && entry.distinctIdentity(ref)) competing++;
        }
        check(competing == 0 && unknownMembers == 0,
                "R2 no member of the certified body belongs to a competing identity, and none is UNKNOWN",
                "competing=" + competing + " unknownMembers=" + unknownMembers
                        + " members=" + ref.members.size());
        log("  real entry stage ms=" + (System.nanoTime() - t0) / 1_000_000L
                + " terrainSamples=" + raw.terrainSamples());
    }

    /**
     * Reproduces one documented real conflict. If the competing-identity refusal is still raised it is reported as
     * UNRESOLVED, never green. If the cell resolves, only that cell's own level-set consistency is asserted — that
     * is explicitly not a claim that the conflict is fixed elsewhere.
     */
    private static void conflictCell(String tag, int x, int z, WorldDrainage raw, WorldBasinRouting routing,
                                     WindowOutlets.Terrain terrain, int step) {
        long t0 = System.nanoTime();
        WorldBasinFlow flow = new WorldBasinFlow(raw, routing, PRODUCTION, step, DEMAND_LIMIT);
        Map<WorldDrainage.Point, WorldBasinFlow.Water> water = new LinkedHashMap<>();
        for (int dx : new int[]{0, step}) for (int dz : new int[]{0, step}) {
            WorldDrainage.Point p = new WorldDrainage.Point(x + dx, z + dz);
            WorldBasinFlow.Water w;
            try {
                w = flow.demand(p, 1.0);
            } catch (RuntimeException failure) {
                undecided("R3 conflict cell " + tag, "corner " + p + " threw " + failure);
                return;
            }
            if (!w.known()) {
                unresolved("R3 conflict cell " + tag + " still unresolved: corner " + p + " has no certificate",
                        String.valueOf(w.reason()));
                return;
            }
            water.put(p, w);
        }
        WorldLakeSurface.Cell cell;
        try {
            cell = WorldLakeSurface.resolve(x, z, step, water, terrain);
        } catch (IllegalStateException refusal) {
            String message = String.valueOf(refusal.getMessage());
            if (message.startsWith("competing lake identities")) {
                unresolved("R3 conflict cell " + tag + " is STILL UNRESOLVED (two basin identities compete)",
                        message);
            } else {
                undecided("R3 conflict cell " + tag, "unexpected refusal: " + message);
            }
            return;
        } catch (RuntimeException other) {
            undecided("R3 conflict cell " + tag, "resolve threw " + other);
            return;
        }
        int[] report = wetCellReport(tag, x, z, step, terrain, cell);
        if (report[0] == 0) {
            log("  [PASS] R4 conflict cell " + tag + ": no wet column declared at this cell — not a claim that the"
                    + " conflict is fixed anywhere else");
        } else {
            check(report[1] == 0 && report[2] <= 1,
                    "R4 resolved conflict cell " + tag + " is one real level-set component with a wet corner",
                    "wet=" + report[0] + " dryCornerColumns=" + report[1] + " distinctLevels=" + report[2]);
        }
        log("  conflict cell " + tag + " ms=" + (System.nanoTime() - t0) / 1_000_000L);
    }

    /** Returns {@code {wetColumns, dryCornerColumns, distinctLevels}} for one resolved world lake cell. */
    private static int[] wetCellReport(String tag, int x, int z, int step, WindowOutlets.Terrain terrain,
                                       WorldLakeSurface.Cell cell) {
        int side = step + 1;
        double[] node = new double[side * side];
        for (int dx = 0; dx <= step; dx++) for (int dz = 0; dz <= step; dz++)
            node[dx * side + dz] = terrain.height(x + dx, z + dz);
        Set<Double> levels = new TreeSet<>();
        int wet = 0, dryCorner = 0;
        for (int dx = 0; dx < step; dx++) for (int dz = 0; dz < step; dz++) {
            double level = cell.levelAt(x + dx, z + dz);
            if (Double.isNaN(level)) continue;
            wet++;
            levels.add(level);
            boolean anyCornerWet = false;
            for (int ex : new int[]{dx, dx + 1}) for (int ez : new int[]{dz, dz + 1})
                anyCornerWet |= node[ex * side + ez] < level - 0.5;
            if (!anyCornerWet) dryCorner++;
        }
        log("  WET_CELL " + tag + " wet=" + wet + " dryCorner=" + dryCorner + " levels=" + levels);
        return new int[]{wet, dryCorner, levels.size()};
    }
}
