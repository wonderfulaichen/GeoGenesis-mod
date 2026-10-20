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
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;

/**
 * Block-geometry handoff: where does the <b>block certificate</b> lose its identity when it reaches the coarse
 * backwater interface?
 *
 * <h2>What is reused, and what is NOT</h2>
 * <ul>
 *   <li><b>Reused read-only</b>: production {@link WorldDrainage} + {@link WorldBasinRouting} for the coarse
 *       local/published body, and {@code CellGenerator.erodedHeight} for real terrain.</li>
 *   <li><b>Not reused</b>: {@link CertifiedBasinQuery} (G44) and the G57 unified-graph projection. The block
 *       certificate here is built from two <b>independent</b> algorithms of my own — a priority-flood bottleneck
 *       and a Kruskal activation + union-find bottleneck — cross-checked against each other, so agreement is
 *       evidence rather than a shared assumption.</li>
 *   <li>Unlike G44, every certificate carries an explicit <b>slab-frame closure</b> condition, so a window frame can
 *       never be published as an outlet.</li>
 * </ul>
 *
 * <h2>The interface question this probe isolates</h2>
 * <ol>
 *   <li><b>Block first outlet</b> — unit four-neighbour steps on integer columns: the lowest crest at which the
 *       strict level set first reaches a column below the sink floor. Complete closed cut set, no ranking rule.</li>
 *   <li><b>Coarse backwater interface</b> — production expresses the merge rule as
 *       {@code downstream.members().containsAll(initial.members())} over <b>lattice points</b> (multiples of
 *       {@code step}). A block member set contains columns that are not lattice points at all, so that predicate
 *       cannot even be <i>evaluated</i> on it. This is measured, not assumed.</li>
 *   <li><b>First outlet to downstream block sink</b> — the at-sill outer column is followed (no highest / lowest /
 *       nearest pick: more than one at-sill outer column is reported pending) across the sill flat to a receiving
 *       bed, then down to a strict block minimum, which is certified in turn. If that sink's own sill rises, the
 *       merged body at the higher level must provably contain the original basin and be one component; otherwise it
 *       stays pending.</li>
 * </ol>
 *
 * <h2>Assertion discipline</h2>
 * <ul>
 *   <li>Identity is asserted at a <b>fixed level or floor</b>, the only entry-invariant form. A self-floored
 *       per-entry certificate is measured separately and reported as a divergence — that difference <i>is</i> the
 *       interface gap.</li>
 *   <li>A certificate touching its slab frame is not closed and is never published as a basin. Budget and frame
 *       exhaustion are pending: never "no basin", never "dry".</li>
 *   <li>An empty coarse/block intersection is <b>not</b> a pass, and a non-empty one is not sameness either; both
 *       wrong classifiers are executed as negative controls.</li>
 * </ul>
 *
 * <p><b>Scope</b>: difference experiment. No discharge/Q, no storage, no legal lake level, no production
 * acceptance. Experimental completion is not basin-migration completion.
 *
 * <p>Output: {@code <localFlow.output>/result.txt} plus {@code block-certificate*.csv}, {@code block-cuts.csv},
 * {@code outlet-trace.csv}, {@code interface-gap.csv}. Exit 0 = every assertion passed; 1 = candidate red or an
 * open interface conflict; 2 = infrastructure failure.
 */
public final class LocalFlowBlockHandoffProbe {
    /** Output directory contract; resolved once so every writer agrees. */
    static final Path OUT = Path.of(System.getProperty("localFlow.output", "build/local-flow-reassessment/block-handoff"));
    /** Same default world as the rest of the reassessment harness. */
    static final long DEFAULT_SEED = 9139912035078620160L;
    /** Same documented real sample as {@code LocalFlowBasinProbe} R1; overridable, never silently replaced. */
    static final int DEFAULT_START_X = -428, DEFAULT_START_Z = -92;
    static final int GEOMETRY_LIMIT = 131072, CACHE_BUDGET = 400000, GUARD = 4096, IDENTITY_ENTRIES = 12;
    /** Unit four-neighbour steps. No block geometry here ever uses the coarse stride. */
    static final int[][] FOUR = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    /** Reversed traversal order, used by the order-independence assertions. */
    static final int[][] FLIPPED_FOUR = {{0, -1}, {0, 1}, {-1, 0}, {1, 0}};
    static final double EPS = 1e-9;

    static final StringBuilder LOG = new StringBuilder();
    static final List<String> CANDIDATE = new ArrayList<>(), RED = new ArrayList<>(), INFRA = new ArrayList<>();
    /** Measured differences between two contracts answering different questions: neither pass nor fail. */
    static final List<String> DIVERGENCE = new ArrayList<>();
    static int checks;
    static long seed = DEFAULT_SEED;

    private LocalFlowBlockHandoffProbe() { }

    public static void main(String[] args) {
        try {
            seed = resolveSeed(args.length > 0 ? args[0] : null);
            Files.createDirectories(OUT);
            log("=== LocalFlowBlockHandoffProbe — block certificate vs coarse backwater identity ===");
            log("dir=" + OUT.toAbsolutePath() + " seed=" + seed + " reuse=WorldDrainage/WorldBasinRouting"
                    + " notReused=CertifiedBasinQuery,G57-projection algorithm=own-minimax+own-kruskal");
            TerrainParams params = loadConfig();
            if (params != null) {
                stage("S", LocalFlowBlockHandoffProbe::syntheticStage);
                stage("R", () -> realStage(params));
            }
        } catch (Throwable fatal) {
            INFRA.add("probe aborted: " + fatal);
            fatal.printStackTrace();
        }
        emit();
    }

    /** Seed resolution: CLI first, then {@code -DlocalFlow.seed}, then the shared default. Malformed input is infra. */
    static long resolveSeed(String arg) {
        if (arg != null && !arg.isBlank()) {
            try { return Long.parseLong(arg.trim()); }
            catch (NumberFormatException bad) { INFRA.add("seed argument not a long: " + arg); return DEFAULT_SEED; }
        }
        String property = System.getProperty("localFlow.seed", "").trim();
        if (property.isEmpty()) return DEFAULT_SEED;
        try { return Long.parseLong(property); }
        catch (NumberFormatException bad) { INFRA.add("localFlow.seed not a long: " + property); return DEFAULT_SEED; }
    }

    /**
     * Loads a <b>copy</b> of the user TOML through the production spec. The original is read only; if it changed
     * during the run that is reported, never silently accepted.
     */
    static TerrainParams loadConfig() {
        try {
            Path src = Path.of("run", "config", "geogenesis-common.toml");
            if (!Files.exists(src)) { undecided("R0 config", src.toAbsolutePath() + " missing"); return null; }
            byte[] original = Files.readAllBytes(src);
            Path work = OUT.resolve("config-probe.toml");
            Files.copy(src, work, StandardCopyOption.REPLACE_EXISTING);
            try (CommentedFileConfig cfg = CommentedFileConfig.of(work, TomlFormat.instance())) {
                cfg.load();
                GeoGenesisConfig.SPEC.setConfig(cfg);
                TerrainParams tp = GeoGenesisConfig.INSTANCE.buildParams();
                log("config copy=" + work.toAbsolutePath() + " (original read-only) hs=" + tp.horizontalScale()
                        + " sea=" + tp.seaLevel() + " minY=" + tp.minY() + " maxY=" + tp.maxY()
                        + " fingerprint=" + GeoGenesisConfig.configFingerprint()
                        + " step=" + Integer.getInteger("geogenesis.modelStep", 4));
                check(Arrays.equals(original, Files.readAllBytes(src)),
                        "R0 the user TOML is unchanged on disk", src.toString());
                return tp;
            }
        } catch (Throwable failure) {
            undecided("R0 config", String.valueOf(failure));
            return null;
        }
    }

    @FunctionalInterface interface Body { void run() throws Exception; }

    private static void stage(String name, Body body) {
        try { body.run(); }
        catch (Throwable failure) {
            INFRA.add("stage " + name + " threw: " + failure);
            log("  [UNDECIDED] stage " + name + " threw " + failure);
            failure.printStackTrace();
        }
    }

    // ------------------------------------------------------------------ verdict plumbing

    private static void check(boolean ok, String name, String detail) {
        checks++;
        if (ok) { log("  [PASS] " + name); return; }
        CANDIDATE.add(name + " — " + detail);
        log("  [FAIL] " + name + " — " + detail);
    }

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

    private static void divergence(String name, String detail) {
        checks++;
        DIVERGENCE.add(name + " — " + detail);
        log("  [DIVERGENCE] " + name + " — " + detail);
    }

    private static void log(String s) { LOG.append(s).append('\n'); }

    private static void cancelled() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("block handoff");
    }

    private static long key(int x, int z) { return ((long) x << 32) ^ (z & 0xffffffffL); }

    private static void emit() {
        String verdict = !INFRA.isEmpty() ? "INFRASTRUCTURE FAILURE"
                : (CANDIDATE.isEmpty() && RED.isEmpty() ? "PASS (differences recorded)" : "CANDIDATE RED");
        log("\nNOT_ESTABLISHED water discharge/Q, storage, legal lake level, provider migration, whole-basin closure,");
        log("                infinite source catalogue, natural river shape, any production acceptance");
        log("CHECKS=" + checks + " CANDIDATE_FAIL=" + CANDIDATE.size() + " UNRESOLVED=" + RED.size()
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
            System.out.println("[INFRA_FAIL] could not persist result.txt: " + failure);
            written = false;
        }
        if (!written) System.exit(2);
        System.exit(!INFRA.isEmpty() ? 2 : (CANDIDATE.isEmpty() && RED.isEmpty() ? 0 : 1));
    }

    // ------------------------------------------------------------------ reusable records

    /** Integer-column height oracle. Bounds enforcement belongs to {@link Slab}, not to this interface. */
    interface Blocks { double at(int x, int z); }

    record BlockCell(int x, int z) implements Comparable<BlockCell> {
        @Override public int compareTo(BlockCell o) {
            int c = Integer.compare(x, o.x);
            return c != 0 ? c : Integer.compare(z, o.z);
        }
    }

    /** One unit four-neighbour cut from a member column outward, with both endpoint heights. */
    record Cut(BlockCell inner, BlockCell outer, double innerHeight, double outerHeight, double crest) { }

    /**
     * A block certificate. The strict level set must keep clear of the slab frame and its complete minimum cut
     * must attain the minimax escape. No lower-crest path can leave the slab without crossing that strict set.
     * Frame minimum alone is not a necessary closure condition.
     */
    record Certificate(BlockCell owner, double floor, double escape, List<BlockCell> members, List<Cut> cuts,
                       int atSill, int belowSill, double minCrest, boolean frameClean, boolean closed, String reason) {
        Certificate { members = List.copyOf(members); cuts = List.copyOf(cuts); }
    }

    /** At-sill cut set. Several distinct outer columns are reported, never ranked. */
    record Outlet(List<Cut> atSill, List<BlockCell> outer, String reason) { }

    /**
     * A descent that either settled or refused. When {@code sink} is null the walk is <b>pending</b> and
     * {@code reason} says why (frame, tied equal-height flats, several distinct lower components, guard exhausted) —
     * a refusal is never reported as "no basin" and never as a fabricated local minimum.
     */
    record Descent(BlockCell sink, List<BlockCell> path, String reason) {
        boolean settled() { return sink != null; }
    }

    /**
     * Receiving beds reached across the sill flat. {@code seed} is non-null only when the flat opens onto
     * <b>exactly one</b> strict below-level component; otherwise {@code reason} names the refusal and
     * {@code pans} counts the distinct components found.
     */
    record Receiver(BlockCell seed, int boundaryColumns, int pans, String reason) {
        boolean unique() { return seed != null; }
    }

    /** Equal-height flood result. {@code frame} and {@code overBudget} are refusals, not bounds to clamp. */
    record Flat(List<BlockCell> cells, boolean frame, boolean overBudget) { }

    /**
     * One descent decision: a unique downhill step, a contiguous equal-height transit to a drain column (with the
     * interior columns that transit visits), a settled sink, or a pending refusal.
     */
    enum Move { STEP, PATH, SINK, PENDING }

    record Walk(Move move, BlockCell target, List<BlockCell> route, String reason) {
        Walk { route = List.copyOf(route); }
        Walk(Move move, BlockCell target, String reason) { this(move, target, List.of(), reason); }
    }

    record Merge(boolean contains, boolean connected, int size, int skippedSeeds, String reason) { }

    record Closed(Slab slab, Certificate certificate) { }

    // ------------------------------------------------------------------ block sampler and slab

    /** Counting, memoising block sampler over real production terrain. One instance is one sampling generation. */
    static final class Sampler implements Blocks, WindowOutlets.Terrain {
        private final CellGenerator gen;
        private final double hs;
        private final Map<Long, Double> cache = new HashMap<>();
        private long calls, misses;

        Sampler(CellGenerator gen, double hs) { this.gen = gen; this.hs = hs; }

        @Override public double at(int x, int z) {
            calls++;
            long k = key(x, z);
            Double old = cache.get(k);
            if (old != null) return old;
            if (cache.size() >= CACHE_BUDGET) throw new IllegalStateException("block height cache budget");
            double h = gen.erodedHeight(x / hs, z / hs);
            if (!Double.isFinite(h)) throw new IllegalArgumentException("nonfinite terrain at " + x + "," + z);
            cache.put(k, h);
            misses++;
            return h;
        }

        @Override public double height(double x, double z) {
            if (x != Math.floor(x) || z != Math.floor(z))
                throw new IllegalArgumentException("block lattice sample must be integral: " + x + "," + z);
            return at((int) x, (int) z);
        }

        long misses() { return misses; } long calls() { return calls; }
    }

    /**
     * Bounded integer-column slab over the four Dirichlet frame walls. Every off-slab sample throws, so a window
     * frame can never be read as an outlet; {@link #certify} then decides whether the slab was large enough to close
     * the level set.
     */
    static final class Slab {
        private final Blocks body;
        private final int x0, z0, x1, z1;
        private final boolean flip;

        Slab(Blocks body, int x0, int z0, int x1, int z1, boolean flip) {
            if (x1 <= x0 || z1 <= z0) throw new IllegalArgumentException("slab geometry");
            this.body = body; this.x0 = x0; this.z0 = z0; this.x1 = x1; this.z1 = z1; this.flip = flip;
        }

        boolean contains(int x, int z) { return x >= x0 && z >= z0 && x <= x1 && z <= z1; }
        boolean onFrame(int x, int z) { return contains(x, z) && (x == x0 || z == z0 || x == x1 || z == z1); }
        int[][] dirs() { return flip ? FLIPPED_FOUR : FOUR; }
        Slab flipped() { return new Slab(body, x0, z0, x1, z1, !flip); }
        int scopeX0() { return x0; } int scopeZ0() { return z0; }
        int scopeX1() { return x1; } int scopeZ1() { return z1; }

        String scope() { return "[" + x0 + "," + z0 + ".." + x1 + "," + z1 + "]"; }

        double h(int x, int z) {
            if (!contains(x, z))
                throw new IllegalArgumentException("block sample outside the declared slab: " + x + "," + z);
            return body.at(x, z);
        }

        double frameMin() {
            double min = Double.POSITIVE_INFINITY;
            for (int x = x0; x <= x1; x++) {
                min = Math.min(min, h(x, z0));
                min = Math.min(min, h(x, z1));
            }
            for (int z = z0; z <= z1; z++) {
                min = Math.min(min, h(x0, z));
                min = Math.min(min, h(x1, z));
            }
            return min;
        }

        /**
         * Complete certificate at a fixed wall clock. The strict predicate is {@code height < floor}, so the
         * <b>floor</b> — not the entry column — defines identity. That is the only entry-invariant form; the
         * self-floored variant is measured separately as a divergence.
         *
         * <h3>Why {@code frameClean} proves the restricted minimum is the true one</h3>
         * <p>Suppose the true escape path had crest {@code E_true} strictly below the slab-restricted {@code E}. Every
         * column of that path has height {@code < E}, so every one of them is a member of the level set computed at
         * {@code E}. The path leaves the slab, so the last of its columns inside the slab lies on the frame — and it
         * would be a member, contradicting frame cleanliness. Hence a frame-clean level set implies no cheaper escape
         * exists outside the slab either, and the restricted minimax is exact.</p>
         *
         * <p>{@code minCrest == escape} is then the witness that the escape is actually <i>attained</i> on a real cut
         * edge rather than inherited from a numerical accident, and {@code belowSill == 0} is a redundant consistency
         * check kept because a cut below the escape would mean the level-set flood and the cut enumeration disagreed.</p>
         *
         * <p>Deliberately <b>not</b> used as the closure test: {@code escape <= frameMinimum}. That is sufficient but
         * not necessary, and on ordinary outward-descending terrain the outer frame is legitimately lower than the
         * escape, so gating on it would report a healthy basin as pending. The frame minimum is still measured and
         * logged, because it is the number a reader would otherwise reach for.</p>
         */
        Certificate certify(int sx, int sz, double floor) {
            if (!contains(sx, sz)) throw new IllegalArgumentException("certificate entry outside slab");
            double escape = minimax(sx, sz, floor);
            if (!Double.isFinite(escape)) return rejected(sx, sz, floor, "no escape inside the slab");
            if (!(escape > floor)) return rejected(sx, sz, floor, "the sink drains without storage (escape == floor)");
            List<BlockCell> members = levelSet(sx, sz, escape);
            if (members.isEmpty()) return rejected(sx, sz, floor, "empty strict level set at " + escape);
            boolean frameClean = members.stream().noneMatch(m -> onFrame(m.x(), m.z()));
            List<Cut> cuts = cuts(members);
            int atSill = 0, below = 0;
            double min = Double.POSITIVE_INFINITY;
            // EXACT comparisons, deliberately not EPS. Both sides are max/comparison of the same cached height
            // values, never an iterative solve, so equality is exact by construction:
            //  * `escape` is the max along a minimax path, hence equals h(c*) for some column c*;
            //  * h(c*) == escape is not < escape, so c* is NOT a member, and it is adjacent to a member (the path
            //    entered it from one) => a cut of crest exactly escape exists, so min == escape exactly;
            //  * a cut below escape would mean the level-set flood and the cut enumeration disagree.
            // Using EPS here is not merely redundant, it is WRONG: a non-member sliver at escape+1e-12 touching a
            // member is a genuine higher cut, and EPS folds it into the at-sill set and fabricates a multi-outlet
            // refusal out of nothing. (Verified by counterexample, not asserted.)
            for (Cut k : cuts) {
                min = Math.min(min, k.crest());
                if (k.crest() < escape) below++;
                if (k.crest() == escape) atSill++;
            }
            boolean closed = frameClean && below == 0 && min == escape;
            String reason = !frameClean ? "level set touches the slab frame"
                    : below > 0 ? below + " cut(s) below the escape level"
                    : Double.isFinite(min) && min != escape
                            ? "lowest cut crest " + min + " does not attain the escape " + escape : "";
            return new Certificate(members.get(0), floor, escape, members, cuts, atSill, below, min,
                    frameClean, closed, reason);
        }

        private Certificate rejected(int sx, int sz, double floor, String reason) {
            return new Certificate(new BlockCell(sx, sz), floor, Double.NaN, List.of(), List.of(), 0, 0,
                    Double.NaN, false, false, reason);
        }

        /** Bottleneck flood: the lowest crest at which a column strictly below {@code floor} is reached. */
        double minimax(int sx, int sz, double floor) {
            Map<Long, Double> best = new HashMap<>();
            PriorityQueue<double[]> queue = new PriorityQueue<>((a, b) -> {
                int c = Double.compare(a[0], b[0]);
                if (c != 0) return c;
                c = Double.compare(a[1], b[1]);
                return c != 0 ? c : Double.compare(a[2], b[2]);
            });
            double start = h(sx, sz);
            best.put(key(sx, sz), start);
            queue.add(new double[]{start, sx, sz});
            int guard = 0;
            while (!queue.isEmpty()) {
                cancelled();
                if (++guard > CACHE_BUDGET) throw new IllegalStateException("block minimax expansion budget");
                double[] head = queue.poll();
                int x = (int) head[1], z = (int) head[2];
                if (head[0] > best.getOrDefault(key(x, z), Double.POSITIVE_INFINITY)) continue;
                if (h(x, z) < floor) return head[0];
                for (int[] d : dirs()) {
                    int nx = x + d[0], nz = z + d[1];
                    if (!contains(nx, nz)) continue;
                    double cost = Math.max(head[0], h(nx, nz));
                    if (cost < best.getOrDefault(key(nx, nz), Double.POSITIVE_INFINITY)) {
                        best.put(key(nx, nz), cost);
                        queue.add(new double[]{cost, nx, nz});
                    }
                }
            }
            return Double.POSITIVE_INFINITY;
        }

        /** Independent oracle: Kruskal activation by height plus union-find. Structurally unlike the flood above. */
        double minimaxKruskal(int sx, int sz, double floor) {
            int w = x1 - x0 + 1, d = z1 - z0 + 1, n = w * d;
            double[] heights = new double[n];
            Integer[] order = new Integer[n];
            for (int i = 0; i < w; i++) for (int j = 0; j < d; j++) {
                int k = i * d + j;
                heights[k] = h(x0 + i, z0 + j);
                order[k] = k;
            }
            Arrays.sort(order, (a, b) -> heights[a] != heights[b]
                    ? Double.compare(heights[a], heights[b]) : Integer.compare(a, b));
            int[] parent = new int[n];
            boolean[] active = new boolean[n], escape = new boolean[n];
            Arrays.fill(parent, -1);
            int start = (sx - x0) * d + (sz - z0);
            for (int p = 0; p < n; ) {
                int q = p;
                while (q < n && heights[order[q]] == heights[order[p]]) {
                    int k = order[q++];
                    parent[k] = k;
                    active[k] = true;
                    escape[k] = heights[k] < floor;
                }
                for (int m = p; m < q; m++) activate(order[m], w, d, parent, active, escape);
                if (active[start] && escape[root(parent, start)]) return heights[order[p]];
                p = q;
            }
            return Double.POSITIVE_INFINITY;
        }

        private void activate(int k, int w, int d, int[] parent, boolean[] active, boolean[] escape) {
            int i = k / d, j = k % d;
            if (i > 0 && active[k - d]) join(parent, escape, k, k - d);
            if (i + 1 < w && active[k + d]) join(parent, escape, k, k + d);
            if (j > 0 && active[k - 1]) join(parent, escape, k, k - 1);
            if (j + 1 < d && active[k + 1]) join(parent, escape, k, k + 1);
        }

        private void join(int[] parent, boolean[] escape, int a, int b) {
            int ra = root(parent, a), rb = root(parent, b);
            if (ra == rb) return;
            parent[rb] = ra;
            escape[ra] |= escape[rb];
        }

        /** Strict level set at {@code level} from {@code (sx,sz)}, canonical order; empty if the seed is not below. */
        List<BlockCell> levelSet(int sx, int sz, double level) {
            if (!(h(sx, sz) < level)) return List.of();
            Set<Long> seen = new HashSet<>();
            ArrayDeque<int[]> queue = new ArrayDeque<>();
            List<BlockCell> out = new ArrayList<>();
            seen.add(key(sx, sz));
            queue.add(new int[]{sx, sz});
            while (!queue.isEmpty()) {
                cancelled();
                int[] p = queue.remove();
                out.add(new BlockCell(p[0], p[1]));
                if (out.size() > CACHE_BUDGET) throw new IllegalStateException("block level set budget");
                for (int[] dir : dirs()) {
                    int nx = p[0] + dir[0], nz = p[1] + dir[1];
                    if (!contains(nx, nz) || seen.contains(key(nx, nz))) continue;
                    if (h(nx, nz) < level) { seen.add(key(nx, nz)); queue.add(new int[]{nx, nz}); }
                }
            }
            out.sort(null);
            return out;
        }

        /** Every member to non-member unit four-neighbour cut. Frame-clean members guarantee none is dropped. */
        List<Cut> cuts(List<BlockCell> members) {
            Set<Long> inside = new HashSet<>();
            for (BlockCell m : members) inside.add(key(m.x(), m.z()));
            List<Cut> out = new ArrayList<>();
            for (BlockCell m : members) for (int[] dir : dirs()) {
                int nx = m.x() + dir[0], nz = m.z() + dir[1];
                if (!contains(nx, nz) || inside.contains(key(nx, nz))) continue;
                double hi = h(m.x(), m.z()), ho = h(nx, nz);
                out.add(new Cut(m, new BlockCell(nx, nz), hi, ho, Math.max(hi, ho)));
            }
            out.sort(Comparator.comparing(Cut::inner).thenComparing(Cut::outer));
            return out;
        }

        /** Multi-seed strict flood. Seeds at or above {@code level} are skipped; the caller counts them. */
        List<BlockCell> floodAt(List<BlockCell> seeds, double level) {
            Set<Long> seen = new HashSet<>();
            ArrayDeque<BlockCell> queue = new ArrayDeque<>();
            List<BlockCell> out = new ArrayList<>();
            for (BlockCell s : seeds) {
                if (!contains(s.x(), s.z()) || h(s.x(), s.z()) >= level) continue;
                if (!seen.add(key(s.x(), s.z()))) continue;
                queue.add(s);
            }
            while (!queue.isEmpty()) {
                cancelled();
                BlockCell p = queue.remove();
                out.add(p);
                if (out.size() > CACHE_BUDGET) throw new IllegalStateException("block flood budget");
                for (int[] dir : dirs()) {
                    int nx = p.x() + dir[0], nz = p.z() + dir[1];
                    if (!contains(nx, nz) || seen.contains(key(nx, nz))) continue;
                    if (h(nx, nz) < level) { seen.add(key(nx, nz)); queue.add(new BlockCell(nx, nz)); }
                }
            }
            out.sort(null);
            return out;
        }

        /** Number of four-connected components inside an already flooded set. */
        int componentCount(List<BlockCell> set) {
            Set<Long> inside = new HashSet<>();
            for (BlockCell c : set) inside.add(key(c.x(), c.z()));
            Set<Long> seen = new HashSet<>();
            int components = 0;
            for (BlockCell start : set) {
                if (!seen.add(key(start.x(), start.z()))) continue;
                components++;
                ArrayDeque<BlockCell> queue = new ArrayDeque<>();
                queue.add(start);
                while (!queue.isEmpty()) {
                    BlockCell p = queue.remove();
                    for (int[] dir : dirs()) {
                        int nx = p.x() + dir[0], nz = p.z() + dir[1];
                        if (!inside.contains(key(nx, nz)) || !seen.add(key(nx, nz))) continue;
                        queue.add(new BlockCell(nx, nz));
                    }
                }
            }
            return components;
        }

        /**
         * Every column four-connected to {@code from} at <b>exactly</b> its height, excluding {@code forbidden}. This
         * is the equal-height component a greedy descent cannot cross: on a flat, "no strictly lower neighbour" is
         * satisfied everywhere, so a walk that stops there reports a sink that the flat itself contradicts.
         *
         * <p>{@code frame} is set when the component reaches the slab border: the flat may continue outside, so its
         * closure is unproven and no plateau conclusion may be drawn. {@code overBudget} is a refusal, never a
         * silently truncated component.</p>
         */
        Flat equalHeightFlat(BlockCell from, Set<Long> forbidden) {
            double level = h(from.x(), from.z());
            Set<Long> seen = new HashSet<>();
            ArrayDeque<BlockCell> queue = new ArrayDeque<>();
            List<BlockCell> out = new ArrayList<>();
            boolean frame = false;
            seen.add(key(from.x(), from.z()));
            queue.add(from);
            while (!queue.isEmpty()) {
                cancelled();
                BlockCell p = queue.remove();
                out.add(p);
                if (out.size() > CACHE_BUDGET) return new Flat(List.copyOf(out), frame, true);
                for (int[] dir : dirs()) {
                    int nx = p.x() + dir[0], nz = p.z() + dir[1];
                    if (!contains(nx, nz)) continue;
                    if (onFrame(nx, nz)) frame = true;
                    if (forbidden.contains(key(nx, nz)) || seen.contains(key(nx, nz))) continue;
                    if (h(nx, nz) == level) { seen.add(key(nx, nz)); queue.add(new BlockCell(nx, nz)); }
                }
            }
            out.sort(null);
            return new Flat(List.copyOf(out), frame, false);
        }

        /** The single canonical member of a flat: smallest cell that admits a strictly lower neighbour. */
        List<BlockCell> lowerBoundary(List<BlockCell> flat) {
            List<BlockCell> out = new ArrayList<>();
            for (BlockCell p : flat) if (hasStrictlyLower(p.x(), p.z())) out.add(p);
            out.sort(null);
            return out;
        }

        /**
         * Shortest route across an already flooded equal-height flat, excluding {@code from} and including {@code to}.
         * Only cells of that flat may be used, so a route can never step over a column that was not shown to sit at
         * the same level. Empty means the two columns are not connected inside this flat.
         */
        List<BlockCell> flatRoute(List<BlockCell> flat, BlockCell from, BlockCell to) {
            Set<Long> inside = new HashSet<>();
            for (BlockCell c : flat) inside.add(key(c.x(), c.z()));
            long start = key(from.x(), from.z()), goal = key(to.x(), to.z());
            if (!inside.contains(start) || !inside.contains(goal)) return List.of();
            Map<Long, Long> previous = new HashMap<>();
            ArrayDeque<BlockCell> queue = new ArrayDeque<>();
            previous.put(start, null);
            queue.add(from);
            while (!queue.isEmpty() && !previous.containsKey(goal)) {
                cancelled();
                BlockCell p = queue.remove();
                for (int[] d : dirs()) {
                    int nx = p.x() + d[0], nz = p.z() + d[1];
                    long k = key(nx, nz);
                    if (!inside.contains(k) || previous.containsKey(k)) continue;
                    previous.put(k, key(p.x(), p.z()));
                    queue.add(new BlockCell(nx, nz));
                }
            }
            if (!previous.containsKey(goal)) return List.of();
            List<BlockCell> route = new ArrayList<>();
            for (Long at = goal; at != null && at != start; at = previous.get(at))
                route.add(new BlockCell((int) (at >> 32), (int) (long) at));
            Collections.reverse(route);
            return List.copyOf(route);
        }

        /** True iff some unit four-neighbour inside the slab is strictly lower than this column. */
        boolean hasStrictlyLower(int x, int z) {
            double h = h(x, z);
            for (int[] d : FOUR) {
                int nx = x + d[0], nz = z + d[1];
                if (!contains(nx, nz)) continue;
                if (h(nx, nz) < h) return true;
            }
            return false;
        }

        /**
         * Distinct four-connected components of the columns strictly below {@code level}, seeded from {@code seeds}
         * and never crossing {@code forbidden}. This is what makes a multi-receiver sill measurable: one component is
         * one receiving pan, and two components mean the flat really does open onto two different bodies.
         */
        List<List<BlockCell>> lowerComponents(Set<Long> seeds, double level, Set<Long> forbidden) {
            Set<Long> seen = new HashSet<>();
            List<List<BlockCell>> out = new ArrayList<>();
            for (long seed : new TreeSet<>(seeds)) {
                BlockCell start = new BlockCell((int) (seed >> 32), (int) seed);
                if (!contains(start.x(), start.z()) || forbidden.contains(seed)) continue;
                if (!(h(start.x(), start.z()) < level) || seen.contains(seed)) continue;
                List<BlockCell> comp = new ArrayList<>();
                ArrayDeque<BlockCell> queue = new ArrayDeque<>();
                seen.add(seed);
                queue.add(start);
                while (!queue.isEmpty()) {
                    cancelled();
                    BlockCell p = queue.remove();
                    comp.add(p);
                    if (seen.size() > CACHE_BUDGET) throw new IllegalStateException("lower component budget");
                    for (int[] dir : dirs()) {
                        int nx = p.x() + dir[0], nz = p.z() + dir[1];
                        if (!contains(nx, nz)) continue;
                        long k = key(nx, nz);
                        if (forbidden.contains(k) || seen.contains(k)) continue;
                        if (h(nx, nz) < level) { seen.add(k); queue.add(new BlockCell(nx, nz)); }
                    }
                }
                comp.sort(null);
                out.add(List.copyOf(comp));
            }
            out.sort(Comparator.comparing(c -> c.get(0)));
            return List.copyOf(out);
        }
    }

    private static int root(int[] parent, int k) {
        int r = k;
        while (parent[r] != r) r = parent[r];
        while (parent[k] != r) { int next = parent[k]; parent[k] = r; k = next; }
        return r;
    }

    /**
     * Grows the slab until the level set is provably interior, doubling the radius. If it still does not close, the
     * last (not closed) certificate is returned and every caller treats it as pending. The radius is never tuned to
     * a lucky sample.
     */
    static Closed certifyClosed(Blocks body, int sx, int sz, double floor, int radius, int maxRadius) {
        Closed last = null;
        for (int r = radius; r <= maxRadius; r *= 2) {
            Slab slab = new Slab(body, sx - r, sz - r, sx + r, sz + r, false);
            Certificate c = slab.certify(sx, sz, floor);
            last = new Closed(slab, c);
            if (c.closed()) return last;
        }
        return last;
    }

    /**
     * One greedy descent step, decided without ever letting traversal order pick the answer.
     *
     * <p>Three outcomes, all explicit:
     * <ol>
     *   <li><b>{@link Move#STEP}</b> — exactly one neighbour attains the strict minimum height, so the step is
     *       forced. A <b>tie</b> on the lowest height is <i>not</i> resolved by {@code dirs()} order: the tied
     *       neighbours are flooded as equal-height components, and the step continues only when that yields exactly
     *       one option.</li>
     *   <li><b>{@link Move#SINK}</b> — no strictly lower neighbour <i>and</i> the equal-height component provably
     *       closes with no lower boundary inside the slab. This is a genuine closed plateau, which is a real sink
     *       for the block graph even though it is not a strict minimum.</li>
     *   <li><b>{@link Move#PENDING}</b> — frame contact, over-budget flat, several distinct lower components, or an
     *       equal-height flat that does have a lower boundary but no unique entry into it. Never a sink.</li>
     * </ol>
     *
     * <p>Rationale: a flat is the one place where "no strictly lower neighbour" is satisfied at <i>every</i> column,
     * so a naive walk stops wherever it arrived. Reporting that as a sink is exactly the "sampling artefact promoted
     * to geometry" failure this probe exists to prevent. Because production terrain clamps deep ocean to a perfectly
     * flat {@code minY} plane, this is a real configuration, not a hypothetical one.
     */
    static Walk walk(Slab slab, BlockCell from, Set<Long> forbidden, double here) {
        double lowest = Double.POSITIVE_INFINITY;
        for (int[] d : slab.dirs()) {
            int nx = from.x() + d[0], nz = from.z() + d[1];
            if (!slab.contains(nx, nz) || forbidden.contains(key(nx, nz))) continue;
            lowest = Math.min(lowest, slab.h(nx, nz));
        }
        if (!(lowest < here)) return plateauOrFrame(slab, from, forbidden);
        List<BlockCell> tied = new ArrayList<>();
        for (int[] d : slab.dirs()) {
            int nx = from.x() + d[0], nz = from.z() + d[1];
            if (!slab.contains(nx, nz) || forbidden.contains(key(nx, nz))) continue;
            if (slab.h(nx, nz) == lowest) tied.add(new BlockCell(nx, nz));
        }
        if (tied.size() == 1) return new Walk(Move.STEP, tied.get(0), "");
        return tieBreak(slab, from, tied, forbidden);
    }

    /** No strictly lower neighbour: a strict pit floor, a real closed plateau, or a refusal. */
    private static Walk plateauOrFrame(Slab slab, BlockCell from, Set<Long> forbidden) {
        if (slab.onFrame(from.x(), from.z()))
            return new Walk(Move.PENDING, null, "settled on the slab frame, so a lower neighbour may lie outside");
        Flat flat = slab.equalHeightFlat(from, forbidden);
        if (flat.overBudget()) return new Walk(Move.PENDING, null, "equal-height flat exceeded the cell budget");
        if (flat.frame()) return new Walk(Move.PENDING, null, "equal-height flat reaches the slab frame");
        List<BlockCell> boundary = slab.lowerBoundary(flat.cells());
        // A one-cell equal-height component with no lower boundary is a genuine strict pit floor; only a multi-cell
        // one is a plateau. Reporting both as "plateau" would hide a real pit behind a flat-looking label.
        boolean plateau = flat.cells().size() > 1;
        if (boundary.isEmpty())
            return new Walk(Move.SINK, from, plateau
                    ? "closed plateau of " + flat.cells().size() + " equal-height columns, no lower boundary in the slab"
                    : "strict pit floor: no lower and no equal-height neighbour in the slab");
        if (boundary.size() == 1) {
            // The drain may be many equal-height columns away. Stepping straight to it would be a teleport across
            // unvisited terrain, so the flat route is walked one cell at a time and must stay on this exact level.
            List<BlockCell> route = slab.flatRoute(flat.cells(), from, boundary.get(0));
            if (route.isEmpty())
                return new Walk(Move.PENDING, null, "no contiguous equal-height route to " + boundary.get(0));
            return new Walk(Move.PATH, boundary.get(0), route,
                    "plateau drains through " + route.size() + " contiguous equal-height columns");
        }
        return new Walk(Move.PENDING, null,
                "plateau has " + boundary.size() + " lower-boundary columns and no unique step");
    }

    /** Several neighbours tie on the lowest height: decide by equal-height components, never by direction order. */
    private static Walk tieBreak(Slab slab, BlockCell from, List<BlockCell> tied, Set<Long> forbidden) {
        double target = slab.h(tied.get(0).x(), tied.get(0).z());
        Set<Long> merged = new HashSet<>();
        for (BlockCell t : tied) {
            for (BlockCell c : slab.equalHeightFlat(t, forbidden).cells()) merged.add(key(c.x(), c.z()));
        }
        merged.remove(key(from.x(), from.z()));
        List<List<BlockCell>> pans = slab.lowerComponents(merged, target, forbidden);
        if (pans.isEmpty()) return new Walk(Move.PENDING, null,
                "every tied lower neighbour is a closed flat with no exit: not a unique descent");
        if (pans.size() > 1) return new Walk(Move.PENDING, null,
                "tied lower neighbours open onto " + pans.size() + " distinct components: no unique descent");
        Set<Long> one = new HashSet<>();
        for (BlockCell c : pans.get(0)) one.add(key(c.x(), c.z()));
        List<BlockCell> entry = new ArrayList<>();
        for (BlockCell t : tied) if (one.contains(key(t.x(), t.z()))) entry.add(t);
        entry.sort(null);
        if (entry.size() != 1) return new Walk(Move.PENDING, null,
                "tied component has " + entry.size() + " entry columns: no unique descent");
        return new Walk(Move.STEP, entry.get(0), "");
    }

    /**
     * Walks unit four-neighbour steps down until the graph really settles, refusing every ambiguous outcome.
     *
     * <p>{@link Walk} decides each step; this loop only drives it and records the path. It returns a
     * <b>non-settled</b> {@link Descent} (null sink plus a reason) for a frame, a tied multi-component flat, an
     * open plateau, or guard exhaustion. A closed plateau <i>is</i> returned as a sink, with the plateau conclusion
     * recorded in the reason so no reader mistakes it for a strict minimum.
     */
    static Descent descend(Slab slab, BlockCell from, Set<Long> forbidden) {
        BlockCell p = from;
        List<BlockCell> path = new ArrayList<>();
        path.add(p);
        for (int guard = 0; guard < GUARD; guard++) {
            cancelled();
            Walk step = walk(slab, p, forbidden, slab.h(p.x(), p.z()));
            if (step.move() == Move.PENDING)
                return new Descent(null, List.copyOf(path), step.reason());
            if (step.move() == Move.SINK)
                return new Descent(p, List.copyOf(path), step.reason());
            if (step.move() == Move.PATH) {
                // Transit columns are recorded, so the accepted path stays one unit-four-neighbour walk with no jump.
                path.addAll(step.route());
            } else {
                path.add(step.target());
            }
            p = step.target();
        }
        return new Descent(null, List.copyOf(path), "descent guard exhausted");
    }

    /**
     * Descends with automatic slab growth, so a walk is never truncated by its own window.
     *
     * <p>Only a <b>frame</b> refusal is retried: it is the one outcome a larger slab can genuinely fix. An ambiguous
     * tie or an open plateau is a property of the terrain, not of the window, so it is returned immediately rather
     * than re-attempted at four larger radii.</p>
     */
    static Descent descendClosed(Blocks body, BlockCell from, Set<Long> forbidden, int radius, int maxRadius) {
        Descent last = null;
        for (int r = radius; r <= maxRadius; r *= 2) {
            Slab slab = new Slab(body, from.x() - r, from.z() - r, from.x() + r, from.z() + r, false);
            if (!slab.contains(from.x(), from.z())) continue;
            Descent down = descend(slab, from, forbidden);
            last = down;
            if (down.settled()) return down;
            if (!frameRefusal(down.reason())) return down;
        }
        if (last == null) return new Descent(null, List.of(from), "no slab could hold the start column");
        return last;
    }

    private static boolean frameRefusal(String reason) {
        return reason != null && (reason.contains("frame") || reason.contains("outside"));
    }

    /**
     * True iff no unit four-neighbour inside the slab is strictly lower. <b>Weak</b> minimality: a plateau, a flat,
     * and a genuine pit floor all satisfy it, so this answer alone never proves that a column is a local minimum.
     * Kept under its original name and signature for the main thread; see {@link #weakNoDescent} for the honest alias
     * and {@link #strictAllNeighboursHigher} for the genuinely strict property.
     */
    static boolean strictFourMinimum(Slab slab, BlockCell p) {
        return !slab.hasStrictlyLower(p.x(), p.z());
    }

    /** Alias of {@link #strictFourMinimum} carrying the meaning its name does not: no strictly lower neighbour. */
    static boolean weakNoDescent(Slab slab, BlockCell p) { return strictFourMinimum(slab, p); }

    /**
     * True iff <b>every</b> in-slab unit four-neighbour is strictly higher. This is real strict minimality and is
     * deliberately stricter than "at least one neighbour is higher": requiring only one higher neighbour would accept
     * a saddle, which is a pass-through and not a minimum at all.
     */
    static boolean strictAllNeighboursHigher(Slab slab, BlockCell p) {
        double here = slab.h(p.x(), p.z());
        int seen = 0;
        for (int[] d : FOUR) {
            int nx = p.x() + d[0], nz = p.z() + d[1];
            if (!slab.contains(nx, nz)) continue;
            seen++;
            if (!(slab.h(nx, nz) > here)) return false;
        }
        return seen > 0;
    }

    /**
     * The at-sill cut set with <b>no</b> selection rule. One distinct outer column is unambiguous; two or more stay
     * pending, because picking the highest / lowest / nearest would hide a real multi-outlet conflict.
     */
    static Outlet firstOutlet(Certificate c) {
        List<Cut> at = new ArrayList<>();
        TreeSet<BlockCell> outer = new TreeSet<>();
        for (Cut k : c.cuts()) {
            // EXACT: the at-sill set is by definition {crest == escape}. An EPS window would pull in a strictly
            // higher sliver and report a spurious MULTIPLE_OUTLETS refusal, inventing a conflict that is not there.
            if (k.crest() != c.escape()) continue;
            at.add(k);
            outer.add(k.outer());
        }
        String reason = at.isEmpty() ? "NO_SILL_CUT"
                : outer.size() == 1 ? "" : "MULTIPLE_OUTLETS(" + outer.size() + ")";
        return new Outlet(List.copyOf(at), List.copyOf(outer), reason);
    }

    /**
     * Crosses the sill flat from the outlet's outer column and reports the receiving bed, <b>without</b> letting
     * traversal order choose it.
     *
     * <p>The flat is the set of non-member columns at or below {@code level}, reached from {@code outer}. Only its
     * <b>strict below-level boundary</b> columns are candidates. Those candidates are then grouped into four-connected
     * components of the columns strictly below {@code level} — one component is one receiving pan — and the answer is
     * declared only when there is <b>exactly one</b> such pan.
     *
     * <p>This is the property the earlier first-come-first-served scan did not have: on a symmetric sill flat that
     * fans into two mirror-image pits, a FIFO queue returns whichever pit {@code dirs()} reached first, so the
     * reported pan changes when the traversal order changes. Nothing about that is a physical choice, so two pans,
     * zero pans, frame contact, or a budget stop are all refusals.
     */
    static Receiver receiverSeed(Slab slab, Set<Long> members, BlockCell outer, double level) {
        if (members.contains(key(outer.x(), outer.z())))
            throw new IllegalArgumentException("outlet outer column is a member");
        Set<Long> flat = new HashSet<>();
        Set<Long> boundary = new HashSet<>();
        boolean frame = false;
        ArrayDeque<BlockCell> queue = new ArrayDeque<>();
        flat.add(key(outer.x(), outer.z()));
        queue.add(outer);
        while (!queue.isEmpty()) {
            cancelled();
            BlockCell p = queue.remove();
            if (flat.size() > CACHE_BUDGET)
                return new Receiver(null, boundary.size(), 0, "sill flat exceeded the cell budget");
            for (int[] d : slab.dirs()) {
                int nx = p.x() + d[0], nz = p.z() + d[1];
                if (!slab.contains(nx, nz)) continue;
                if (slab.onFrame(nx, nz)) frame = true;
                if (members.contains(key(nx, nz))) continue;
                double nh = slab.h(nx, nz);
                if (nh < level) { boundary.add(key(nx, nz)); continue; }
                if (nh == level && flat.add(key(nx, nz))) queue.add(new BlockCell(nx, nz));
            }
        }
        if (frame)
            return new Receiver(null, boundary.size(), 0, "sill flat reaches the slab frame, so it may continue outside");
        if (boundary.isEmpty())
            return new Receiver(null, 0, 0, "sill flat has no column strictly below the level");
        List<List<BlockCell>> pans = slab.lowerComponents(boundary, level, members);
        if (pans.stream().flatMap(List::stream).anyMatch(p -> slab.onFrame(p.x(), p.z())))
            return new Receiver(null, boundary.size(), pans.size(), "receiving component reaches slab frame");
        if (pans.isEmpty())
            return new Receiver(null, boundary.size(), 0, "below-level boundary produced no component");
        if (pans.size() > 1)
            return new Receiver(null, boundary.size(), pans.size(),
                    "sill flat opens onto " + pans.size() + " distinct receiving pans; no first-arrival pick is made");
        List<BlockCell> pan = pans.get(0);
        Set<Long> inPan = new HashSet<>();
        for (BlockCell c : pan) inPan.add(key(c.x(), c.z()));
        List<BlockCell> entry = new ArrayList<>();
        for (long k : new TreeSet<>(boundary)) if (inPan.contains(k)) entry.add(new BlockCell((int) (k >> 32), (int) k));
        entry.sort(null);
        return new Receiver(entry.get(0), boundary.size(), 1, "");
    }

    /**
     * Merged body at {@code level}: must contain every base member and be a single four-connected component.
     *
     * <p>Seed columns at or above the level are skipped and <b>counted</b> rather than silently dropped, and a budget
     * stop is reported as {@code connected == false} — never as a conflicting merge verdict. A merge test that cannot
     * finish must read as pending, because the alternative is a fabricated answer about identity.
     */
    static Merge mergeAt(Slab slab, List<BlockCell> base, List<BlockCell> extra, double level) {
        int skipped = 0;
        List<BlockCell> seeds = new ArrayList<>(base);
        seeds.addAll(extra);
        for (BlockCell s : seeds) if (!(slab.h(s.x(), s.z()) < level)) skipped++;
        List<BlockCell> flooded;
        try {
            flooded = slab.floodAt(seeds, level);
        } catch (CancellationException interrupted) {
            throw interrupted;
        } catch (RuntimeException budget) {
            return new Merge(false, false, 0, skipped, "merge flood stopped: " + budget.getMessage());
        }
        if (flooded.stream().anyMatch(p -> slab.onFrame(p.x(), p.z())))
            return new Merge(false, false, flooded.size(), skipped, "merged level set touches slab frame");
        boolean contains = new HashSet<>(flooded).containsAll(base);
        int components = slab.componentCount(flooded);
        String reason = skipped > 0 ? "seeds at or above the merge level=" + skipped
                : components != 1 ? "merged set has " + components + " components"
                : contains ? "" : "merged set does not contain the original basin";
        return new Merge(contains, components == 1, flooded.size(), skipped, reason);
    }

    // ------------------------------------------------------------------ S: synthetic block series

    /**
     * Two block pans in series with a designed throat. {@code lowerThroat} above the internal channel crest raises the
     * receiving pan's own sill, so the merged level must rise (backwater); below the crest the pans drain separately.
     * The base is a strictly monotone high ramp, so nothing else competes and no flat is invented.
     */
    static Blocks seriesBody(double lowerThroat) {
        return (x, z) -> {
            if (x >= 21 && x <= 23 && z >= 15 && z <= 17) return -9.0;        // synthetic below-sea outfall
            if (x == 20 && z == 16) return lowerThroat;                        // lower throat
            if (x >= 17 && x <= 19 && z >= 15 && z <= 17) return 2.0;          // lower pan floor
            if (x == 15 && z == 16) return 5.0;                                // channel crest
            if (x == 16 && z == 16) return 5.0;                                // channel crest
            if (x >= 12 && x <= 14 && z >= 15 && z <= 17) return 3.0;          // upper pan floor
            return 40.0 + 0.5 * x + 0.4 * z;                                  // monotone high base
        };
    }

    private static void syntheticStage() {
        log("\n== [S] synthetic block series on unit four-neighbour geometry (own flood vs own Kruskal) ==");
        seriesCase("BACKWATER", 6.5, true);
        seriesCase("NO_BACKWATER", 4.0, false);
        Blocks openPan = (x, z) -> z == 0 && x <= 1 ? 2.0 : x == 2 && z == 0 ? 5.0 : x == 3 && z == 0 ? 1.0 : 20.0;
        Slab clipped = new Slab(openPan, -2, -2, 4, 2, false);
        Certificate open = clipped.certify(0, 0, 2.0);
        check(!open.closed() && !open.frameClean(),
                "negative control: frame-touching level set never becomes a closed basin", describe(open));
        Merge incomplete = mergeAt(clipped, List.of(new BlockCell(0, 0)), List.of(), 5.0);
        check(!incomplete.connected(), "negative control: frame-touching merge stays pending", incomplete.toString());
        receiverNegativeControls();
        descentNegativeControls();
        strictSemanticsControl();
        exactCutControls();
    }

    /**
     * The exact-vs-EPS question at the <b>publication</b> site, proved by counterexample rather than by assertion.
     *
     * <p>Both sides of every cut comparison are maxima/comparisons of the same cached height values — never an
     * iterative solve — so {@code escape} equals {@code h(c*)} for some column {@code c*} exactly. That makes
     * {@code minCrest == escape} and {@code belowSill == 0} exact identities, which is what the certificate now
     * publishes.</p>
     *
     * <p>An EPS window is therefore not a harmless safety margin: it is <b>wrong</b>. The fixture below puts a
     * non-member sliver at {@code escape + 1e-12} touching a member. Its cut crest legitimately exceeds the sill on
     * the opposite side of the basin, so it is <i>not</i> an outlet. Under exact rules one outlet remains; under an
     * EPS window the sliver is folded into the at-sill set and a {@code MULTIPLE_OUTLETS} refusal is fabricated out of
     * terrain that has exactly one spill. Both directions are asserted, so the exact rule cannot be "passing" for the
     * wrong reason.</p>
     */
    private static void exactCutControls() {
        double sliver = 5.0 + 1e-12;
        Blocks body = (x, z) -> {
            if (x >= 0 && x <= 2 && z >= -1 && z <= 1) return 1.0;      // basin, floor 1.0
            if (x == 3 && z == 0) return 5.0;                           // THE single real spill
            if (x == 4 && z == 0) return 0.5;                           // its receiving bed
            if (x == 1 && z == 2) return sliver;                        // sliver just above the sill
            if (x == 1 && z == 3) return 0.4;                           // and a lower column beyond it
            return 60.0;
        };
        Slab slab = new Slab(body, -2, -3, 6, 5, false);
        Certificate c = slab.certify(0, 0, 1.0);
        check(c.closed() && c.escape() == 5.0,
                "S10 the sliver basin still certifies, at exactly the real sill", describe(c));
        // The structural identity: the minimum cut attains the escape level EXACTLY, and nothing lies below it.
        check(c.minCrest() == c.escape() && c.belowSill() == 0,
                "S10 minCrest == escape and belowSill == 0 hold EXACTLY, not merely within a tolerance",
                "minCrest=" + c.minCrest() + " escape=" + c.escape() + " below=" + c.belowSill());
        Outlet exact = firstOutlet(c);
        check(exact.outer().size() == 1 && new BlockCell(3, 0).equals(exact.outer().get(0)),
                "S10 under EXACT comparison the basin has exactly one outlet, the real spill",
                "outer=" + exact.outer() + " reason=" + exact.reason());
        // The rejected EPS rule on the same certificate must disagree — otherwise the control proves nothing.
        TreeSet<BlockCell> epsOuter = new TreeSet<>();
        for (Cut k : c.cuts())
            if (Math.abs(k.crest() - c.escape()) <= EPS) epsOuter.add(k.outer());
        check(epsOuter.size() > exact.outer().size(),
                "S10 the rejected EPS window pulls the sliver in and invents a second outlet",
                "epsOutlets=" + epsOuter.size() + " " + epsOuter
                        + " vs exact=" + exact.outer().size() + " " + exact.outer()
                        + " — so EPS at the publication site is wrong, not merely loose");
    }

    /**
     * A symmetric sill flat that fans into two mirror-image receiving pans, both strictly below the basin floor.
     * The old first-come-first-served scan returned whichever pan {@code dirs()} reached first; a correct receiver
     * must refuse under <b>both</b> traversal orders, so the assertion cannot pass on one lucky direction.
     */
    private static void receiverNegativeControls() {
        Blocks twin = (x, z) -> {
            if (x >= 0 && x <= 2 && z >= -1 && z <= 1) return 1.0;      // basin, floor 1.0
            if (x == 3 && z == 0) return 5.0;                           // the single at-sill cut
            if (x == 4 && z == 0) return 5.0;                           // saddle stub at exactly the level
            if (x == 4 && (z == 1 || z == -1)) return 0.9;              // two mirror pans, below the floor
            return 60.0;
        };
        Slab slab = new Slab(twin, -1, -3, 7, 3, false);
        Certificate c = slab.certify(0, 0, 1.0);
        check(c.closed() && Math.abs(c.escape() - 5.0) <= EPS, "S7 the twin-pan basin certifies at its own sill",
                describe(c));
        Set<Long> members = keys(c.members());
        Receiver forward = receiverSeed(slab, members, new BlockCell(3, 0), c.escape());
        Receiver reverse = receiverSeed(slab.flipped(), members, new BlockCell(3, 0), c.escape());
        check(!forward.unique() && !reverse.unique(),
                "S7 a sill flat opening onto TWO receiving pans is refused in both traversal orders",
                "forward=" + forward + " reverse=" + reverse);
        check(forward.pans() == 2 && reverse.pans() == 2,
                "S7 both orders agree on the pan count, so the refusal is geometry and not traversal order",
                "forwardPans=" + forward.pans() + " reversePans=" + reverse.pans());
        // Negative control on the negative control: the OLD rule really does flip its answer here.
        BlockCell oldForward = firstBelowLevel(slab, members, new BlockCell(3, 0), c.escape());
        BlockCell oldReverse = firstBelowLevel(slab.flipped(), members, new BlockCell(3, 0), c.escape());
        check(oldForward != null && oldReverse != null && !oldForward.equals(oldReverse),
                "S7 the rejected first-arrival rule provably returns two different pans under the two orders",
                "oldForward=" + oldForward + " oldReverse=" + oldReverse);
    }

    /** The rejected first-come-first-served rule, kept only as a negative control. */
    private static BlockCell firstBelowLevel(Slab slab, Set<Long> members, BlockCell outer, double level) {
        Set<Long> seen = new HashSet<>();
        ArrayDeque<BlockCell> queue = new ArrayDeque<>();
        seen.add(key(outer.x(), outer.z()));
        queue.add(outer);
        while (!queue.isEmpty()) {
            BlockCell p = queue.remove();
            if (slab.h(p.x(), p.z()) < level) return p;
            for (int[] d : slab.dirs()) {
                int nx = p.x() + d[0], nz = p.z() + d[1];
                if (!slab.contains(nx, nz) || members.contains(key(nx, nz)) || seen.contains(key(nx, nz))) continue;
                if (slab.h(nx, nz) <= level) { seen.add(key(nx, nz)); queue.add(new BlockCell(nx, nz)); }
            }
        }
        return null;
    }

    /**
     * Descent must not let direction order decide, and must not promote a plateau to a sink.
     * Three cases: a tie on equal lower neighbours (refused), a plateau whose only drain is far away (followed to
     * the drain, or refused — never reported as a sink in place), and a genuinely closed plateau (a real sink).
     */
    private static void descentNegativeControls() {
        // (1) symmetric tie: two equal lower neighbours in different components
        Blocks tie = (x, z) -> {
            if (x == 0 && z == 0) return 9.0;
            if (x == 1 && z == 0) return 4.0;
            if (x == -1 && z == 0) return 4.0;
            if (x == 1 && z == 1) return 3.0;      // drains the +x side
            if (x == -1 && z == -1) return 3.0;    // drains the -x side
            return 50.0;
        };
        Slab tieSlab = new Slab(tie, -3, -3, 3, 3, false);
        Descent tieF = descend(tieSlab, new BlockCell(0, 0), Set.of());
        Descent tieR = descend(tieSlab.flipped(), new BlockCell(0, 0), Set.of());
        check(!tieF.settled() && !tieR.settled(),
                "S8 a tie on equal lower neighbours is refused in both traversal orders, not resolved by order",
                "forward=" + tieF.reason() + " | reversed=" + tieR.reason());
        check(tieF.path().equals(tieR.path()),
                "S8 both orders produce the SAME path up to the refusal (the tie is not silently stepped)",
                "forward=" + tieF.path() + " reversed=" + tieR.path());
        // (2) plateau whose real drain sits far away: the interior must never be reported as a sink
        Blocks far = (x, z) -> (z == 0 && x >= -20 && x <= 20) ? 5.0
                : (x == 21 && z == 0) ? 0.0 : 50.0;
        Slab farSlab = new Slab(far, -30, -10, 30, 10, false);
        Descent farDown = descend(farSlab, new BlockCell(0, 0), Set.of());
        check(farDown.settled() ? farDown.sink().equals(new BlockCell(21, 0))
                        : farDown.reason().contains("boundary"),
                "S8 a plateau with a distant drain is followed to it, or refused; the interior is never a sink",
                "sink=" + farDown.sink() + " reason=" + farDown.reason() + " pathTail=" + tail(farDown.path()));
        if (farDown.settled())
            check(new BlockCell(21, 0).equals(farDown.sink()),
                    "S8 the plateau walk reaches the true drain column", String.valueOf(farDown.sink()));
        // (3) genuinely closed plateau: a real sink, accepted but flagged as weak
        Blocks closed = (x, z) -> (x >= 0 && x <= 2 && z >= 0 && z <= 2) ? 7.0 : 50.0;
        Slab closedSlab = new Slab(closed, -5, -5, 7, 7, false);
        Descent closedDown = descend(closedSlab, new BlockCell(1, 1), Set.of());
        check(closedDown.settled() && closedDown.reason().contains("closed plateau"),
                "S8 a genuinely closed plateau is a real sink, and says so instead of claiming strict minimality",
                "sink=" + closedDown.sink() + " reason=" + closedDown.reason());
        check(!strictAllNeighboursHigher(closedSlab, new BlockCell(1, 1)),
                "S8 that closed plateau is correctly NOT a strict minimum", "the weak test alone would have said yes");
        // (3b) a genuine one-cell pit must be labelled a pit floor, not a plateau
        Blocks pit = (x, z) -> (x == 0 && z == 0) ? 1.0
                : (Math.abs(x) <= 1 && Math.abs(z) <= 1) ? 9.0 : 50.0;
        Descent pitDown = descend(new Slab(pit, -4, -4, 4, 4, false), new BlockCell(0, 0), Set.of());
        check(pitDown.settled() && pitDown.reason().contains("strict pit floor"),
                "S8 a one-cell strict pit is reported as a pit floor, not mislabelled as a plateau",
                "sink=" + pitDown.sink() + " reason=" + pitDown.reason());
        // (4) flat touching the slab frame must stay pending, never a closed plateau
        Slab tight = new Slab(far, -2, -2, 2, 2, false);
        Descent clippedDown = descend(tight, new BlockCell(0, 0), Set.of());
        check(!clippedDown.settled(),
                "S8 a flat that reaches the slab frame is refused, never promoted to a closed plateau",
                "reason=" + clippedDown.reason());
    }

    /**
     * The weak/strict distinction must be observable, so neither predicate can stand in for the other.
     *
     * <p>The fixture is a true saddle at the origin: higher on two opposed sides, lower on the other two. That makes
     * "no strictly lower neighbour" <b>false</b> at the centre, so it cannot exhibit the weak/strict difference. The
     * saddle is therefore used for its right property — the strict predicate must reject it — and the difference
     * between the two predicates is demonstrated on a flat, where the weak one says yes and the strict one says no.
     */
    private static void strictSemanticsControl() {
        Blocks saddle = (x, z) -> (x == 0 && z == 0) ? 5.0
                : (x == 1 && z == 0) ? 6.0 : (x == -1 && z == 0) ? 6.0
                : (x == 0 && z == 1) ? 4.0 : (x == 0 && z == -1) ? 4.0 : 50.0;
        Slab saddleSlab = new Slab(saddle, -4, -4, 4, 4, false);
        BlockCell centre = new BlockCell(0, 0);
        check(!strictFourMinimum(saddleSlab, centre) && !strictAllNeighboursHigher(saddleSlab, centre),
                "S9 at a saddle both predicates are false: it has a lower neighbour and it is not a minimum",
                "the weak name alone is only misleading where the answer is TRUE");
        Blocks flat = (x, z) -> (x >= 0 && x <= 2 && z >= 0 && z <= 2) ? 7.0 : 50.0;
        Slab flatSlab = new Slab(flat, -5, -5, 7, 7, false);
        BlockCell mid = new BlockCell(1, 1);
        check(strictFourMinimum(flatSlab, mid) && !strictAllNeighboursHigher(flatSlab, mid),
                "S9 on a flat the weak predicate is TRUE and the strict one is FALSE, so the name can mislead",
                "weak=" + strictFourMinimum(flatSlab, mid)
                        + " strict=" + strictAllNeighboursHigher(flatSlab, mid));
        check(weakNoDescent(flatSlab, mid) == strictFourMinimum(flatSlab, mid),
                "S9 the honest alias and the retained name agree, so existing callers keep one meaning",
                "weakNoDescent == strictFourMinimum");
        Blocks pit = (x, z) -> (x == 0 && z == 0) ? 1.0
                : (Math.abs(x) == 1 || Math.abs(z) == 1) && Math.abs(x) <= 1 && Math.abs(z) <= 1 ? 9.0 : 50.0;
        Slab pitSlab = new Slab(pit, -4, -4, 4, 4, false);
        check(strictFourMinimum(pitSlab, centre) && strictAllNeighboursHigher(pitSlab, centre),
                "S9 the two predicates agree only at a true pit, which is the case the old name implied everywhere",
                "weak=true strict=true at a genuine floor");
    }

    /** One series case: certificate, independent oracle, single outlet, receiving bed, then the merge verdict. */
    private static void seriesCase(String tag, double lowerThroat, boolean expectBackwater) {
        Blocks body = seriesBody(lowerThroat);
        Slab slab = new Slab(body, 4, 6, 30, 26, false);
        Certificate upper = slab.certify(12, 16, 3.0);
        log("  " + tag + " upper=" + describe(upper));
        check(upper.closed() && Math.abs(upper.escape() - 5.0) <= EPS,
                "S1 " + tag + " the upper pan's first outlet is the designed channel crest 5.0", describe(upper));
        check(Math.abs(slab.minimaxKruskal(12, 16, 3.0) - upper.escape()) <= EPS,
                "S1 " + tag + " the flood sill equals the independent Kruskal activation oracle",
                "flood=" + upper.escape() + " kruskal=" + slab.minimaxKruskal(12, 16, 3.0));
        check(upper.members().size() == 9 && upper.atSill() == 1 && upper.belowSill() == 0
                        && upper.minCrest() == upper.escape(),
                "S1 " + tag + " the complete cut set is closed: 9 members, exactly one at-sill cut, none below",
                "members=" + upper.members().size() + " atSill=" + upper.atSill()
                        + " below=" + upper.belowSill() + " minCrest=" + upper.minCrest());
        identityInvariance(slab, upper, tag);
        Outlet outlet = firstOutlet(upper);
        check(outlet.outer().size() == 1 && outlet.reason().isEmpty(),
                "S2 " + tag + " the shed has exactly one at-sill outer column (no ranking applied)",
                "outer=" + outlet.outer() + " reason=" + outlet.reason());
        if (outlet.outer().size() != 1) return;
        Receiver receiver = receiverSeed(slab, keys(upper.members()), outlet.outer().get(0), upper.escape());
        check(receiver.unique() && new BlockCell(17, 16).equals(receiver.seed()),
                "S2 " + tag + " the outlet crosses the sill flat into the lower pan's own bed",
                "receiver=" + receiver);
        if (!receiver.unique()) return;
        BlockCell seed = receiver.seed();
        Descent down = descend(slab, seed, keys(upper.members()));
        check(down.settled() && seed.equals(down.sink()),
                "S2 " + tag + " the receiving bed is already a settled four-neighbour sink",
                String.valueOf(down));
        if (!down.settled()) return;
        Certificate lower = slab.certify(down.sink().x(), down.sink().z(), slab.h(down.sink().x(), down.sink().z()));
        log("  " + tag + " lower=" + describe(lower));
        check(lower.closed() && Math.abs(lower.escape() - lowerThroat) <= EPS,
                "S3 " + tag + " the receiving pan's own first outlet is its designed throat " + lowerThroat,
                describe(lower));
        mergeVerdict(slab, upper, lower, down.sink(), expectBackwater, tag);
        reversedOrder(body, upper, tag);
    }

    /** Backwater (or its absence) decided by the measured downstream sill, never assumed. */
    private static void mergeVerdict(Slab slab, Certificate upper, Certificate lower, BlockCell sink,
                                     boolean expectBackwater, String tag) {
        boolean rising = lower.escape() > upper.escape();
        check(rising == expectBackwater,
                "S4 " + tag + " the measured downstream sill " + (expectBackwater ? "rises above" : "stays below")
                        + " the upstream first outlet",
                "upstream=" + upper.escape() + " downstream=" + lower.escape());
        if (rising) {
            Merge merge = mergeAt(slab, upper.members(), lower.members(), lower.escape());
            check(merge.contains() && merge.connected(),
                    "S4 " + tag + " the backwater merged body provably contains the original basin as one component",
                    merge.toString());
            // Compare against the UPSTREAM basin only: the downstream certificate already spans the upstream pan at
            // the higher level, so comparing with the larger of the two would be trivially false.
            check(merge.size() > upper.members().size(),
                    "S4 " + tag + " the merged body is strictly larger than the upstream basin it must contain",
                    "merged=" + merge.size() + " upper=" + upper.members().size()
                            + " lowerCertificate=" + lower.members().size());
        } else {
            // No rise: at exactly the upstream sill the two pans are still two strict components (the channel sits
            // at the sill), and only a level above it merges them. So nothing forces the level up for the lower pan.
            List<BlockCell> atUpstream = slab.floodAt(concat(upper.members(), lower.members()), upper.escape());
            List<BlockCell> justAbove = slab.floodAt(concat(upper.members(), lower.members()), upper.escape() + 1e-6);
            int strict = slab.componentCount(atUpstream), merged = slab.componentCount(justAbove);
            check(strict == 2 && merged == 1,
                    "S4 " + tag + " without backwater the pans stay two strict components at the upstream sill and"
                            + " merge only just above it",
                    "strict=" + strict + " justAbove=" + merged + " sink=" + sink);
        }
    }

    /** The same slab with reversed traversal order must reproduce the whole certificate. */
    private static void reversedOrder(Blocks body, Certificate reference, String tag) {
        Slab flipped = new Slab(body, 4, 6, 30, 26, true);
        Certificate again = flipped.certify(12, 16, 3.0);
        check(again.escape() == reference.escape() && again.members().equals(reference.members())
                        && again.owner().equals(reference.owner()) && again.cuts().equals(reference.cuts()),
                "S5 " + tag + " reversed traversal order reproduces the whole certificate", describe(again));
    }

    /**
     * Identity stated in the only form that can hold. At a <b>fixed</b> level every internal column re-floods to the
     * same member set, which also proves the published set is one strict component rather than two sub-pans sharing a
     * crest. A self-floored query with no descent is measured separately: it is what a handoff carrying only an entry
     * column would have to rely on.
     */
    private static void identityInvariance(Slab slab, Certificate ref, String tag) {
        boolean same = true;
        int differing = 0;
        StringBuilder detail = new StringBuilder();
        for (BlockCell m : ref.members()) {
            same &= slab.levelSet(m.x(), m.z(), ref.escape()).equals(ref.members());
            Certificate self = slab.certify(m.x(), m.z(), slab.h(m.x(), m.z()));
            if (!(self.escape() == ref.escape() && self.members().equals(ref.members()))) {
                differing++;
                if (detail.length() < 200)
                    detail.append(m).append('@').append(slab.h(m.x(), m.z())).append(
                            Double.isFinite(self.escape())
                                    ? "->level " + self.escape() + "/" + self.members().size() + " members "
                                    : "->NO_CERTIFICATE(" + self.reason() + ") ");
            }
        }
        check(same, "S6 " + tag + " every internal entry re-floods to one identity at the certified level",
                "members=" + ref.members().size() + " escape=" + ref.escape());
        if (differing == 0) {
            log("  S6 SELF_FLOOR_OBSERVATION all " + ref.members().size() + " entries agree without a descent too"
                    + " (uniform pan floor by construction; not an assertion)");
        } else {
            divergence("S6 " + tag + " self-floored per-entry certificates differ on " + differing
                            + "/" + ref.members().size() + " internal columns", detail
                            + "— the handoff must carry the floor or the level, not only the entry column");
        }
    }

    // ------------------------------------------------------------------ R: real erodedHeight

    /** Mutable real-terrain context; every field is filled by exactly one stage, so no reader can see a guess. */
    static final class Real {
        final Sampler warm;
        final TerrainParams params;
        final int step, startX, startZ;
        final double sea;
        WorldDrainage raw;
        WorldBasinRouting routing;
        WorldDrainage.Point coarseSink;
        WorldBasin.Geometry coarseLocal;
        WorldBasinRouting.Basin coarseBasin;
        Descent blockDescent;
        Closed upstream;
        BlockCell blockSink;
        /** Deliberately wider than the certificate slab, so following the sill flat is never cut off by a frame. */
        Slab wide;

        Real(Sampler warm, TerrainParams params, int step, int startX, int startZ) {
            this.warm = warm; this.params = params; this.step = step; this.startX = startX; this.startZ = startZ;
            this.sea = params.seaLevel();
        }
    }

    private static void realStage(TerrainParams params) {
        log("\n== [R] real erodedHeight: block first outlet into the coarse backwater interface ==");
        int step = Integer.getInteger("geogenesis.modelStep", 4);
        int[] start = startOverride();
        CellGenerator gen = generator(params);
        if (gen == null) return;
        Real r = new Real(new Sampler(gen, params.horizontalScale()), params, step, start[0], start[1]);
        if (!coarsePart(r)) return;
        if (!blockPart(r)) return;
        // A certificate that did not close is never a valid subject for the interface question: publishing an outlet
        // off it would answer a different question than the one asked. Everything downstream stops here instead.
        if (!r.upstream.certificate().closed()) {
            unresolved("R5-R7 downstream interface analysis is blocked by an unclosed block certificate",
                    describe(r.upstream.certificate()) + " — reported pending, never as an absent or dry basin");
            return;
        }
        gapPart(r);
        outletPart(r);
        coldPart(r);
        log("  R8 COST blockCallbacks=" + r.warm.calls() + " uniqueBlockSamples=" + r.warm.misses()
                + " step=" + r.step + " NOTE=localSlabNotWholeBasin NOT_PRODUCTION_ACCEPTANCE=true");
    }

    private static CellGenerator generator(TerrainParams params) {
        try {
            CellGenerator gen = new CellGenerator(params, params.minY(), params.maxY());
            gen.seed(seed);
            return gen;
        } catch (RuntimeException failure) {
            undecided("R0 real generator", String.valueOf(failure));
            return null;
        }
    }

    private static int[] startOverride() {
        String property = System.getProperty("localFlow.blockHandoff.start", "").trim();
        if (property.isEmpty()) return new int[]{DEFAULT_START_X, DEFAULT_START_Z};
        String[] parts = property.split(",");
        try {
            if (parts.length != 2) throw new NumberFormatException("need x,z");
            int[] out = {Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim())};
            log("  start override from -DlocalFlow.blockHandoff.start=" + property);
            return out;
        } catch (NumberFormatException bad) {
            undecided("R0 start override", "not a lattice pair x,z: " + property);
            return new int[]{DEFAULT_START_X, DEFAULT_START_Z};
        }
    }

    /**
     * The coarse body, read straight from production. Both numbers the interface would have to carry are recorded:
     * the local cut at this sink, and the published body after the spill chain followed the backwater.
     */
    private static boolean coarsePart(Real r) {
        r.coarseSink = new WorldDrainage.Point(r.startX, r.startZ);
        if (Math.floorMod(r.startX, r.step) != 0 || Math.floorMod(r.startZ, r.step) != 0) {
            undecided("R1 coarse sink", r.coarseSink + " is not a step-" + r.step + " lattice point");
            return false;
        }
        try {
            r.raw = new WorldDrainage(r.step, 150, r.sea, r.warm);
            r.routing = new WorldBasinRouting(r.raw, r.warm, r.step, GEOMETRY_LIMIT);
            r.coarseLocal = r.routing.outlet(r.coarseSink).geometry();
            r.coarseBasin = r.routing.at(r.coarseSink);
        } catch (RuntimeException failure) {
            undecided("R1 coarse local body at " + r.coarseSink, String.valueOf(failure));
            return false;
        }
        log("  R1 COARSE sink=" + r.coarseSink + " localState=" + r.coarseLocal.state()
                + " localFloor=" + r.coarseLocal.floor() + " localSill=" + r.coarseLocal.sill()
                + " localMembers=" + r.coarseLocal.members().size() + " localExits=" + r.coarseLocal.exits().size());
        if (r.coarseBasin != null)
            log("  R1 PUBLISHED identity=" + r.coarseBasin.identity() + " sill=" + r.coarseBasin.sill()
                    + " floor=" + r.coarseBasin.floor() + " members=" + r.coarseBasin.members().size()
                    + " lip=" + r.coarseBasin.outlet() + " reason=" + r.coarseBasin.reason());
        check(!r.coarseLocal.members().isEmpty() && Double.isFinite(r.coarseLocal.sill())
                        && Double.isFinite(r.coarseLocal.floor()),
                "R1 the coarse sample settles to a non-empty local body with a finite floor and sill",
                "members=" + r.coarseLocal.members().size() + " floor=" + r.coarseLocal.floor()
                        + " sill=" + r.coarseLocal.sill());
        boolean lattice = r.coarseLocal.members().stream()
                .allMatch(p -> Math.floorMod(p.x(), r.step) == 0 && Math.floorMod(p.z(), r.step) == 0);
        check(lattice, "R1 every coarse member is a step-" + r.step + " lattice point, not a block column",
                "members=" + r.coarseLocal.members().size());
        crestOperator(r);
        return true;
    }

    /**
     * Re-derives the coarse crest operator from the same callback and compares it with the face production chose.
     * Single-domain consistency only: it says nothing about block exactness.
     */
    private static void crestOperator(Real r) {
        if (r.coarseLocal.exits().isEmpty()) {
            undecided("R1 coarse crest operator", "no rim face to re-derive");
            return;
        }
        WorldBasin.Face face = r.coarseLocal.exits().get(0);
        double max = Math.max(r.warm.at(face.inner().x(), face.inner().z()),
                r.warm.at(face.outer().x(), face.outer().z()));
        for (int i = 1; i < r.step; i++) {
            int x = face.inner().x() + (face.outer().x() - face.inner().x()) * i / r.step;
            int z = face.inner().z() + (face.outer().z() - face.inner().z()) * i / r.step;
            max = Math.max(max, r.warm.at(x, z));
        }
        check(Math.abs(max - face.crest()) <= EPS,
                "R1 the coarse crest is the max over every interior integer column of the face",
                "rederived=" + max + " production=" + face.crest() + " face=" + face);
        log("  R1 CREST_OPERATOR face=" + face + " columns=" + (r.step + 1) + " interiorMax=" + max);
    }

    /** Descends to the lowest block column reachable by forced descent, then certifies it. */
    private static boolean blockPart(Real r) {
        long t0 = System.nanoTime();
        Descent descent = descendClosed(r.warm, new BlockCell(r.startX, r.startZ), Set.of(), 16, 256);
        if (!descent.settled()) {
            unresolved("R2 the block descent did not settle: " + descent.reason(),
                    "from=" + r.coarseSink + " pathTail=" + tail(descent.path())
                            + " — reported pending, never as a local minimum");
            return false;
        }
        r.blockDescent = descent;
        r.blockSink = descent.sink();
        double floor = r.warm.at(r.blockSink.x(), r.blockSink.z());
        log("  R2 BLOCK_DESCENT from=" + r.coarseSink + "@" + r.warm.at(r.startX, r.startZ)
                + " to=" + r.blockSink + "@" + floor + " hops=" + (descent.path().size() - 1)
                + " coarseFloor=" + r.coarseLocal.floor()
                + " deltaToCoarseFloor=" + (floor - r.coarseLocal.floor())
                + " settleReason=" + (descent.reason().isEmpty() ? "<strict descent>" : descent.reason()));
        Slab probe = new Slab(r.warm, r.blockSink.x() - 8, r.blockSink.z() - 8,
                r.blockSink.x() + 8, r.blockSink.z() + 8, false);
        check(strictFourMinimum(probe, r.blockSink),
                "R2 the sink admits no strictly lower four-neighbour (weak minimality: a closed plateau also"
                        + " satisfies it)", "sink=" + r.blockSink + "@" + floor);
        if (!strictAllNeighboursHigher(probe, r.blockSink))
            divergence("R2 the sink is not a STRICT minimum: at least one neighbour is at the same height",
                    "sink=" + r.blockSink + "@" + floor + " settleReason="
                            + (descent.reason().isEmpty() ? "<strict descent>" : descent.reason())
                            + " — expected and accepted for a closed plateau, but it is not a pit floor");
        r.upstream = certifyClosed(r.warm, r.blockSink.x(), r.blockSink.z(), floor, 16, 256);
        Certificate c = r.upstream.certificate();
        log("  R3 BLOCK_CERT " + describe(c));
        check(c.closed(), "R3 the block level set closes: frame clean and the lowest cut attains the escape level",
                c.reason() + " slab=" + r.upstream.slab().scope());
        check(c.minCrest() == c.escape() && c.belowSill() == 0 && c.atSill() >= 1,
                "R3 the lowest of ALL unit-4 cuts attains the escape level exactly and none lies below it",
                "minCrest=" + c.minCrest() + " escape=" + c.escape() + " below=" + c.belowSill()
                        + " atSill=" + c.atSill() + " cuts=" + c.cuts().size());
        log("  R3 FRAME_MIN=" + r.upstream.slab().frameMin() + " escape=" + c.escape()
                + " (reported only: an outward-descending frame is legitimately below the escape)");
        double kruskal = r.upstream.slab().minimaxKruskal(r.blockSink.x(), r.blockSink.z(), c.floor());
        check(Math.abs(kruskal - c.escape()) <= EPS,
                "R3 the flood sill equals the independent Kruskal activation oracle on real terrain",
                "flood=" + c.escape() + " kruskal=" + kruskal);
        check(c.owner().equals(c.members().get(0)) && c.members().contains(c.owner()),
                "R3 the block identity is the lexicographically first member and is one of its own members",
                "owner=" + c.owner() + " members=" + c.members().size());
        realIdentity(r, c);
        Slab certificateSlab = r.upstream.slab();
        r.wide = new Slab(r.warm, certificateSlab.scopeX0() - 64, certificateSlab.scopeZ0() - 64,
                certificateSlab.scopeX1() + 64, certificateSlab.scopeZ1() + 64, false);
        log("  R3 TRACE_SLAB " + r.wide.scope() + " (strictly wider than the certificate slab "
                + certificateSlab.scope() + ")");
        log("  R3 block stage ms=" + (System.nanoTime() - t0) / 1_000_000L);
        writeCertificate("block-certificate.csv", r);
        writeCuts(c);
        return true;
    }

    /**
     * The same question on real terrain, split into the three answers that actually differ:
     * <ol>
     *   <li><b>Fixed level</b> — re-flooding from any member at the certified escape level reproduces the member
     *       set, which is what makes it <i>one</i> strict component rather than two sub-pans sharing a crest.</li>
     *   <li><b>Per-entry with descent</b> — a receiver that only receives it an entry column can still recover the
     *       basin, provided it descends to the strict four-neighbour minimum first. This is the form the handoff must
     *       carry, so it is asserted.</li>
     *   <li><b>Per-entry self-floored, no descent</b> — taking the entry column's own height as the floor. Measured
     *       and reported as a divergence, because that difference is precisely the identity that is lost.</li>
     * </ol>
     */
    private static void realIdentity(Real r, Certificate c) {
        Slab slab = r.upstream.slab();
        boolean oneComponent = true;
        int differing = 0, onFloor = 0, tested = 0, descended = 0, unsettled = 0;
        StringBuilder detail = new StringBuilder();
        for (BlockCell m : c.members()) {
            if (tested >= IDENTITY_ENTRIES) break;
            tested++;
            oneComponent &= slab.levelSet(m.x(), m.z(), c.escape()).equals(c.members());
            Certificate self = slab.certify(m.x(), m.z(), slab.h(m.x(), m.z()));
            if (!(self.escape() == c.escape() && self.members().equals(c.members()))) {
                differing++;
                if (detail.length() < 200)
                    // Distinguish the two failure modes explicitly: taking the entry's own height as its floor makes
                    // the column drain with no storage at all, which is not the same as resolving a different level.
                    detail.append(m).append('@').append(slab.h(m.x(), m.z())).append(
                            Double.isFinite(self.escape())
                                    ? "->level " + self.escape() + "/" + self.members().size() + " members "
                                    : "->NO_CERTIFICATE(" + self.reason() + ") ");
            }
            // Grow the slab rather than reuse the certificate frame: a descent truncated by its own window would
            // otherwise be counted as "did not settle" and silently weaken the assertion. The radius is capped at 64
            // (not 256) because a column of the same small basin cannot legitimately descend further than that, and
            // an unbounded arm here would spend the shared sampler budget on chatter rather than evidence.
            Descent down = descendClosed(r.warm, m, Set.of(), 8, 64);
            if (!down.settled()) {
                unsettled++;
                oneComponent = false;
                continue;
            }
            descended++;
            Closed sunk = certifyClosed(r.warm, down.sink().x(), down.sink().z(),
                    r.warm.at(down.sink().x(), down.sink().z()), 16, 256);
            Certificate viaDescent = sunk.certificate();
            oneComponent &= down.sink().equals(r.blockSink)
                    && viaDescent.escape() == c.escape() && viaDescent.members().equals(c.members());
            if (Math.abs(slab.h(m.x(), m.z()) - c.floor()) <= EPS) onFloor++;
        }
        check(oneComponent,
                "R4 every internal entry reaches one identity: at the certified level, and per entry after descending",
                "tested=" + tested + "of" + c.members().size() + " descended=" + descended
                        + " unsettled=" + unsettled + " escape=" + c.escape() + " sink=" + r.blockSink);
        log("  R4 ENTRY_SELF_FLOOR differing=" + differing + " onFloor=" + onFloor + " tested=" + tested
                + " descended=" + descended + " unsettled=" + unsettled + " members=" + c.members().size()
                + (detail.length() == 0 ? "" : " " + detail));
        if (differing == 0) {
            log("  R4 SELF_FLOOR_OBSERVATION all " + tested + " tested entries agree without a descent too"
                    + " (uniform floor on this sample; not an assertion, since a non-uniform floor must break it)");
        } else {
            divergence("R4 a self-floored per-entry certificate without descent is NOT entry-invariant",
                    differing + "/" + tested + " internal columns differ: " + detail
                            + "— so the handoff must carry the floor (or the descent), not only the entry column");
        }
    }

    /**
     * The identity gap, measured rather than asserted. Two separate facts: how many block members are even
     * <i>representable</i> as coarse lattice points, and how the two member sets relate. Neither an empty nor a
     * non-empty intersection is treated as sameness; both wrong classifiers run as negative controls.
     */
    private static void gapPart(Real r) {
        Certificate c = r.upstream.certificate();
        Set<BlockCell> coarseCells = new TreeSet<>();
        for (WorldDrainage.Point p : r.coarseLocal.members()) coarseCells.add(new BlockCell(p.x(), p.z()));
        Set<WorldDrainage.Point> coarse = new HashSet<>(r.coarseLocal.members());
        List<BlockCell> onLattice = new ArrayList<>();
        Set<BlockCell> overlap = new TreeSet<>(), containing = new TreeSet<>();
        for (BlockCell m : c.members()) {
            if (Math.floorMod(m.x(), r.step) == 0 && Math.floorMod(m.z(), r.step) == 0) onLattice.add(m);
            if (coarse.contains(new WorldDrainage.Point(m.x(), m.z()))) overlap.add(m);
            containing.add(new BlockCell(Math.floorDiv(m.x(), r.step) * r.step, Math.floorDiv(m.z(), r.step) * r.step));
        }
        log("  R5 GAP blockMembers=" + c.members().size() + " coarseMembers=" + coarseCells.size()
                + " onCoarseLattice=" + onLattice.size() + " overlap=" + overlap
                + " blockFloor=" + c.floor() + " coarseFloor=" + r.coarseLocal.floor()
                + " blockEscape=" + c.escape() + " coarseLocalSill=" + r.coarseLocal.sill()
                + (r.coarseBasin == null ? "" : " publishedSill=" + r.coarseBasin.sill()
                        + " publishedMembers=" + r.coarseBasin.members().size()));
        // Structural invariant, true by construction of the coarse lattice and therefore a reliable statement: every
        // coarse member IS a lattice point, so the coarse set is incapable of containing a non-lattice block column.
        boolean coarseIsLattice = coarseCells.stream()
                .allMatch(p -> Math.floorMod(p.x(), r.step) == 0 && Math.floorMod(p.z(), r.step) == 0);
        check(coarseIsLattice && !coarseCells.containsAll(c.members()),
                "R5 the production merge predicate (coarse members containAll the body) cannot hold for the block body",
                "coarseMembersAreLatticePoints=" + coarseIsLattice + "; "
                        + (c.members().size() - onLattice.size()) + "/" + c.members().size()
                        + " block columns are not lattice points at all");
        log("  R5 CONTAINING_COARSE_CELLS=" + containing + " (floor-div superset relation, not identity)");
        overlapControl(overlap, c, coarseCells, containing);
        liftPart(r, c, coarseCells, onLattice, overlap, containing);
        divergence("R5 the block identity has no coarse representation on this sample",
                "blockFloor=" + c.floor() + " vs coarseFloor=" + r.coarseLocal.floor()
                        + " (delta=" + (c.floor() - r.coarseLocal.floor()) + "); blockEscape=" + c.escape()
                        + " vs coarseLocalSill=" + r.coarseLocal.sill()
                        + " (delta=" + (c.escape() - r.coarseLocal.sill()) + "); "
                        + onLattice.size() + "/" + c.members().size() + " block members are lattice points");
        // The predicate cannot name block columns at all. Note what this does NOT say: it is not "identity is
        // undecidable forever", because a lift exists (see liftPart). It says the coarse predicate has no meaning on
        // the certificate's own domain, so any answer requires a promotion step that is not part of the contract yet.
        if (c.members().size() - onLattice.size() > 0) {
            unresolved("R5 the block body cannot even be named by the production merge predicate",
                    "production tests containment on lattice points only, so a block member set ("
                            + c.members().size() + " columns, " + onLattice.size() + " on-lattice) is outside that"
                            + " predicate's domain; the shared on-lattice columns " + overlap
                            + " must NOT be read as one identity, and R5-C shows lifting it makes the predicate"
                            + " PASS, which is a false pass, not a resolution");
        } else {
            divergence("R5 this sample happens to be lattice-aligned, so it does not exhibit the interface gap",
                    "blockMembers=" + c.members().size() + " all on the step-" + r.step + " lattice"
                            + " — the gap is still structural (a block certificate is not expressed in lattice"
                            + " points) but this coordinate is not the evidence for it");
        }
        writeGap(r, c, coarseCells, onLattice, overlap, containing);
    }

    /**
     * Can the block body be <b>lifted</b> into the coarse domain so the production predicate becomes answerable?
     *
     * <p>The previous section only showed that the predicate cannot be <i>evaluated</i> on block columns. That is a
     * weaker statement than it looks, because a lift function is always available. This section therefore executes
     * two concrete, defensible lifts and reports what each of them costs:</p>
     * <ol>
     *   <li><b>Cover lift</b> — each block member is mapped to the coarse lattice point covering it (floor-div). The
     *       predicate becomes decidable. It is asserted to be decidable <i>and</i> to answer <b>no</b>: lifting does
     *       not make the two bodies the same body, so a green result here would have to come from the lift, not
     *       from the geometry.</li>
     *   <li><b>Strict lift</b> — only the block members that already <i>are</i> lattice points are kept. This is the
     *       lift an implementer reaches for because it looks lossless.</li>
     * </ol>
     *
     * <p>The two lifts disagree on this sample, which is the real finding: <b>the lift function is not canonical</b>.
     * A predicate whose answer depends on an undeclared lift cannot certify basin identity, so the interface still
     * needs an explicitly specified same-domain promotion — not merely "lift it somehow".</p>
     *
     * <p>Second, independent cost: the block escape level and the coarse sill are values of <b>different
     * operators</b> (minimax over unit-column cuts vs. minimax over lattice cuts sampled through interior columns).
     * Lifting a member set without also converting its level would compare a body defined at one level with a body
     * defined at another.</p>
     */
    private static void liftPart(Real r, Certificate c, Set<BlockCell> coarseCells, List<BlockCell> onLattice,
                                 Set<BlockCell> overlap, Set<BlockCell> containing) {
        Set<WorldDrainage.Point> coarse = new HashSet<>(r.coarseLocal.members());
        Set<BlockCell> coverLift = new TreeSet<>(containing);
        Set<BlockCell> strictLift = new TreeSet<>(onLattice);
        boolean coverDecidable = coverLift.stream()
                .allMatch(p -> Math.floorMod(p.x(), r.step) == 0 && Math.floorMod(p.z(), r.step) == 0);
        boolean coverAnswered = coverDecidable && coarseCells.containsAll(coverLift);
        boolean strictAnswered = coarseCells.containsAll(strictLift);
        log("  R5 LIFT cover=" + coverLift + " strict=" + strictLift + " coarseMembers=" + coarseCells
                + " coverContained=" + coverAnswered + " strictContained=" + strictAnswered);
        check(coverDecidable,
                "R5-C the cover lift makes the production predicate decidable on a block body",
                "decidable=" + coverDecidable + " cover=" + coverLift);
        // MEASURED, and the opposite of the naive worry: the predicate does not merely become decidable, it comes
        // back TRUE. A caller that treats "no exception, contains == true" as identity would accept this handoff.
        check(coverAnswered && strictAnswered,
                "R5-C the lifted block bodies DO satisfy production containment, so 'contains' reads as a pass",
                "coverContained=" + coverAnswered + " strictContained=" + strictAnswered
                        + " coarseMissing=" + missing(coarseCells, coverLift));
        // The decisive negative control: containment is monotone, so it also accepts the body with everything
        // dropped. A predicate that passes for both the real body and the empty body cannot certify identity.
        check(coarseCells.containsAll(Set.of()) && !coverLift.isEmpty(),
                "R5-C negative control: production containment also passes for an EMPTY lift, so it is not a certificate",
                "emptyContained=" + coarseCells.containsAll(Set.of()) + " realLift=" + coverLift.size());
        boolean liftsDisagree = !coverLift.equals(strictLift);
        check(liftsDisagree,
                "R5-C the cover and strict lifts produce different bodies, so the lift is not canonical",
                "cover=" + coverLift.size() + " strict=" + strictLift.size()
                        + " coverOnly=" + missing(strictLift, coverLift) + " strictOnly=" + missing(coverLift, strictLift));
        // Both lifts pass, they are different sets, and neither equals the 9-column block body: three distinct bodies
        // all report "contained", so the predicate cannot say which basin was handed over.
        check(coverLift.size() != c.members().size() && strictLift.size() != c.members().size(),
                "R5-C no lift reproduces the block body, yet both report containment",
                "block=" + c.members().size() + " cover=" + coverLift.size() + " strict=" + strictLift.size());
        boolean sameOperator = c.escape() == r.coarseLocal.sill();
        divergence("R5-C the block escape and the coarse sill are different operators at different levels",
                "blockEscape=" + c.escape() + " coarseSill=" + r.coarseLocal.sill()
                        + " delta=" + (c.escape() - r.coarseLocal.sill())
                        + " blockMembers=" + c.members().size() + " onLattice=" + onLattice.size()
                        + " overlap=" + overlap.size()
                        + (sameOperator ? "; numerically equal here, which is a coincidence of this sample"
                                : "; not interchangeable without an explicit level conversion"));
        unresolved("R5-C containment passes under both lifts, so passing cannot certify basin identity",
                "the predicate is decidable only after an undeclared lift, and the two defensible lifts disagree"
                        + " (" + coverLift.size() + " vs " + strictLift.size() + " cells) while an empty lift also"
                        + " passes; production needs one declared block->coarse promotion plus a level conversion"
                        + " (blockEscape=" + c.escape() + " vs coarseSill=" + r.coarseLocal.sill() + ")");
        writeLift(r, c, coverLift, strictLift, coarseCells, coverAnswered, strictAnswered);
    }

    private static Set<BlockCell> missing(Set<BlockCell> from, Set<BlockCell> of) {
        Set<BlockCell> out = new TreeSet<>(of);
        out.removeAll(from);
        return out;
    }

    private static void writeLift(Real r, Certificate c, Set<BlockCell> coverLift, Set<BlockCell> strictLift,
                                  Set<BlockCell> coarseCells, boolean coverAnswered, boolean strictAnswered) {
        StringBuilder csv = new StringBuilder("lift,x,z,inCoarseBody,isLatticePoint,blockEscape,coarseSill\n");
        Set<BlockCell> all = new TreeSet<>(coverLift);
        all.addAll(strictLift);
        for (BlockCell p : all)
            csv.append(coverLift.contains(p) && strictLift.contains(p) ? "BOTH"
                            : coverLift.contains(p) ? "COVER_ONLY" : "STRICT_ONLY").append(',')
                    .append(p.x()).append(',').append(p.z()).append(',')
                    .append(coarseCells.contains(p)).append(',')
                    .append(Math.floorMod(p.x(), r.step) == 0 && Math.floorMod(p.z(), r.step) == 0).append(',')
                    .append(c.escape()).append(',').append(r.coarseLocal.sill()).append('\n');
        csv.append("VERDICT,coverAnswered=").append(coverAnswered).append(",strictAnswered=")
                .append(strictAnswered).append(",,,,\n");
        write("interface-lift.csv", csv);
    }

    /**
     * Both deliberately wrong identity classifiers, executed so that the correct verdict cannot pass on the strength
     * of the right classifier alone. The two bodies are measurably different, so a classifier returning SAME is
     * provably wrong here — and the disjointness classifier is shown to be unsound independently of this sample.
     */
    private static void overlapControl(Set<BlockCell> overlap, Certificate c, Set<BlockCell> coarse,
                                       Set<BlockCell> containing) {
        boolean bodiesEqual = coarse.equals(new TreeSet<>(c.members()));
        log("  R5 CONTROL overlap=" + overlap.size() + " bodiesEqual=" + bodiesEqual
                + " coarse=" + coarse.size() + " block=" + c.members().size()
                + " containing=" + containing.size());
        check(!bodiesEqual,
                "R5 pre-condition: the block and coarse member sets are genuinely different bodies",
                "coarse=" + coarse.size() + " block=" + c.members().size());
        if (overlap.isEmpty()) {
            divergence("R5 the overlap negative control cannot fire: the member sets are disjoint on this sample",
                    "blockMembers=" + c.members().size() + " coarseMembers=" + coarse.size()
                            + " — SAME_BY_OVERLAP would be accidentally right, so it is not relied on");
            return;
        }
        check(sameByOverlap(overlap),
                "R5 negative control #1 fires: SAME_BY_OVERLAP declares two different bodies identical",
                "overlap=" + overlap.size() + " blockMembers=" + c.members().size()
                        + " — a shared lattice column is not an identity");
        check(sameByDisjoint(Set.of()),
                "R5 negative control #2 is unsound: SAME_BY_DISJOINT calls a zero-overlap pair 'no conflict'",
                "it returns the same verdict for the empty set regardless of which basins are being compared,"
                        + " so it cannot detect a conflict at all");
    }

    /** Deliberately wrong identity classifier #1: any non-empty overlap is treated as sameness. */
    private static boolean sameByOverlap(Set<BlockCell> overlap) { return !overlap.isEmpty(); }

    /** Deliberately wrong identity classifier #2: an empty overlap is read as "no conflict". */
    private static boolean sameByDisjoint(Set<BlockCell> overlap) { return overlap.isEmpty(); }

    /**
     * First outlet → receiving bed → downstream block sink → measured backwater verdict.
     *
     * <p>Every step after the certificate is taken on {@code r.wide}, which is strictly wider than the certificate
     * slab: on the certificate slab the sill flat would run into its own frame and read as "no receiver", which is
     * the exact false negative this probe must not produce. The wider slab is still bounded, and a receiver that
     * only exists outside it is reported pending rather than invented.
     */
    private static void outletPart(Real r) {
        Certificate c = r.upstream.certificate();
        Outlet outlet = firstOutlet(c);
        log("  R6 OUTLET atSillCuts=" + outlet.atSill().size() + " outer=" + outlet.outer()
                + " reason=" + (outlet.reason().isEmpty() ? "<none>" : outlet.reason()));
        if (outlet.outer().size() != 1) {
            unresolved("R6 the first outlet is not single-valued; no ranking rule is applied",
                    "atSillCuts=" + outlet.atSill().size() + " distinctOuter=" + outlet.outer()
                            + " reason=" + outlet.reason() + " — highest/lowest/nearest would hide a real conflict");
            writeTrace(r, outlet.atSill(), null, null, null);
            return;
        }
        Slab trace = r.wide;
        BlockCell outer = outlet.outer().get(0);
        Set<Long> members = keys(c.members());
        Receiver receiver;
        try {
            receiver = receiverSeed(trace, members, outer, c.escape());
        } catch (IllegalArgumentException failure) {
            undecided("R6 receiver seed", String.valueOf(failure));
            return;
        }
        log("  R6 RECEIVER seed=" + receiver.seed() + " boundaryColumns=" + receiver.boundaryColumns()
                + " pans=" + receiver.pans() + " reason="
                + (receiver.reason().isEmpty() ? "<none>" : receiver.reason()));
        check(receiver.unique(),
                "R6 the sill flat opens onto exactly ONE receiving pan, chosen without any first-arrival rule",
                "boundaryColumns=" + receiver.boundaryColumns() + " pans=" + receiver.pans()
                        + " reason=" + receiver.reason() + " outer=" + outer + "@" + trace.h(outer.x(), outer.z())
                        + " escape=" + c.escape() + " slab=" + trace.scope());
        if (!receiver.unique()) {
            writeTrace(r, outlet.atSill(), null, null, null);
            return;
        }
        BlockCell seed = receiver.seed();
        check(!trace.onFrame(seed.x(), seed.z()),
                "R6 the receiving bed is strictly inside the trace slab, not a frame artefact",
                "seed=" + seed + " slab=" + trace.scope());
        if (trace.onFrame(seed.x(), seed.z())) return;
        Descent down = descendClosed(r.warm, seed, members, 16, 256);
        if (!down.settled()) {
            unresolved("R6 the downstream block descent did not settle: " + down.reason(),
                    "from=" + seed + " pathTail=" + tail(down.path())
                            + " — reported pending, never as a receiving sink");
            return;
        }
        double floor = r.warm.at(down.sink().x(), down.sink().z());
        // The descent excluded the basin, so it must be re-checked ignoring that exclusion: a sink draining back
        // into the basin below its own floor is the SAME body, not a receiving pan, and must not be reported as one.
        Slab check8 = new Slab(r.warm, down.sink().x() - 8, down.sink().z() - 8,
                down.sink().x() + 8, down.sink().z() + 8, false);
        if (!strictFourMinimum(check8, down.sink())) {
            unresolved("R6 the receiving bed drains back into the basin below its own floor",
                    "seed=" + seed + " sink=" + down.sink() + "@" + floor
                            + " has a strictly lower neighbour once the basin is re-admitted, so this is one body"
                            + " below the sill, not two pans in series; no backwater term is published");
            writeTrace(r, outlet.atSill(), seed, down, null);
            return;
        }
        Closed downstream = certifyClosed(r.warm, down.sink().x(), down.sink().z(), floor, 16, 256);
        Certificate d = downstream.certificate();
        log("  R6 DOWNSTREAM seed=" + seed + "@" + trace.h(seed.x(), seed.z()) + " sink=" + down.sink() + "@" + floor
                + " hops=" + (down.path().size() - 1) + " cert=" + describe(d));
        writeTrace(r, outlet.atSill(), seed, down, d);
        writeCertificate("block-certificate-downstream.csv", downstream);
        if (!d.closed()) {
            unresolved("R6 the downstream block certificate is pending, so no backwater verdict is published",
                    describe(d));
            return;
        }
        // The merged body is evaluated on one slab that contains both certificates, so no containment test can fail
        // merely because the two certificates were grown on different frames.
        Slab union = unionSlab(r.warm, r.upstream.slab(), downstream.slab());
        log("  R6 UNION_SLAB " + union.scope());
        if (d.escape() > c.escape()) backwater(r, c, d, union);
        else separate(r, c, d, down.sink(), union);
    }

    /** Smallest slab containing both inputs, so a containment test cannot be decided by frame placement. */
    static Slab unionSlab(Blocks body, Slab a, Slab b) {
        return new Slab(body, Math.min(a.scopeX0(), b.scopeX0()), Math.min(a.scopeZ0(), b.scopeZ0()),
                Math.max(a.scopeX1(), b.scopeX1()), Math.max(a.scopeZ1(), b.scopeZ1()), false);
    }

    /**
     * Downstream sill rises: the merged body must provably contain the original basin and be one component. If it
     * does not, the verdict is <b>pending</b> — production would refuse an incomplete merge, so inventing a green
     * answer here would be exactly the failure mode this probe exists to avoid.
     */
    private static void backwater(Real r, Certificate c, Certificate d, Slab union) {
        Merge merge = mergeAt(union, c.members(), d.members(), d.escape());
        log("  R7 BACKWATER upstreamEscape=" + c.escape() + " downstreamEscape=" + d.escape()
                + " delta=" + (d.escape() - c.escape()) + " merged=" + merge);
        if (merge.contains() && merge.connected()) {
            check(merge.size() > c.members().size(),
                    "R7 a rising downstream sill yields one merged body that strictly contains the original basin",
                    "merged=" + merge.size() + " original=" + c.members().size());
        } else {
            unresolved("R7 the downstream sill rises but the merged body could not be closed",
                    merge + " — reported pending, never as a validated backwater body");
        }
        if (r.coarseBasin != null)
            divergence("R7 the coarse published sill and the block merged sill answer different domains",
                    "coarsePublished=" + r.coarseBasin.sill() + " blockMerged=" + d.escape()
                            + " coarseMembers=" + r.coarseBasin.members().size()
                            + " blockMergedMembers=" + merge.size()
                            + " — neither is required to match, and neither licenses a water level");
    }

    /**
     * Downstream sill does not rise: there is no backwater term to add, so no containment is required and none is
     * asserted. What is asserted is that both bodies are genuinely below the operative level, which is what makes
     * the merged body well defined at all.
     */
    private static void separate(Real r, Certificate c, Certificate d, BlockCell sink, Slab union) {
        Merge merge = mergeAt(union, c.members(), d.members(), c.escape());
        log("  R7 NO_BACKWATER upstreamEscape=" + c.escape() + " downstreamEscape=" + d.escape()
                + " merged=" + merge + " sink=" + sink);
        check(merge.contains() && merge.skippedSeeds() == 0,
                "R7 with no rising sill, both bodies still sit entirely below the operative level",
                merge.toString());
        divergence("R7 no block-level backwater at this sample",
                "downstream=" + d.escape() + " at or below upstream=" + c.escape()
                        + "; mergedAtUpstream=" + merge.size()
                        + "; the coarse published body may still merge at a higher level"
                        + (r.coarseBasin == null ? "" : " (coarse published sill=" + r.coarseBasin.sill() + ")"));
    }

    /**
     * Cold rebuild and reversed traversal order. The cold arm is a fresh {@code CellGenerator} and a fresh sampler,
     * so no cached height can carry the answer; the reversed arm flips the four-neighbour traversal order, the only
     * place a tie-break could leak in.
     */
    private static void coldPart(Real r) {
        if (r.upstream == null || r.blockSink == null) {
            undecided("R8 cold rebuild", "no upstream certificate to compare against");
            return;
        }
        Certificate ref = r.upstream.certificate();
        CellGenerator cold = generator(r.params);
        if (cold == null) return;
        Sampler coldSampler = new Sampler(cold, r.params.horizontalScale());
        Descent coldDescent = descendClosed(coldSampler, new BlockCell(r.startX, r.startZ), Set.of(), 16, 256);
        if (!coldDescent.settled()) {
            unresolved("R8 the cold rebuild descent did not settle: " + coldDescent.reason(),
                    "from=" + r.coarseSink + " pathTail=" + tail(coldDescent.path()));
            return;
        }
        Closed coldClosed = certifyClosed(coldSampler, coldDescent.sink().x(), coldDescent.sink().z(),
                coldSampler.at(coldDescent.sink().x(), coldDescent.sink().z()), 16, 256);
        Certificate rebuilt = coldClosed.certificate();
        check(coldDescent.sink().equals(r.blockSink) && rebuilt.escape() == ref.escape()
                        && rebuilt.members().equals(ref.members()) && rebuilt.cuts().equals(ref.cuts()),
                "R8 an independent cold rebuild reproduces the sink, escape level, members and every cut",
                "cold=" + describe(rebuilt) + " warm=" + describe(ref));
        Certificate flipped = r.upstream.slab().flipped().certify(r.blockSink.x(), r.blockSink.z(), ref.floor());
        check(flipped.closed() && flipped.escape() == ref.escape() && flipped.owner().equals(ref.owner()),
                "R8 reversed four-neighbour traversal order reproduces the escape level and the owner",
                "flipped=" + describe(flipped));
        log("  R8 COST coldBlockCallbacks=" + coldSampler.calls() + " coldUnique=" + coldSampler.misses());
    }

    // ------------------------------------------------------------------ CSV writers

    /**
     * One row per block member. {@code onCoarseLattice} and {@code inCoarseLocalBody} are the two facts the interface
     * would need; both are recorded per column so the gap is auditable column by column.
     */
    private static void writeCertificate(String name, Real r) {
        Certificate c = r.upstream.certificate();
        Set<WorldDrainage.Point> coarse = new HashSet<>(r.coarseLocal.members());
        StringBuilder csv = new StringBuilder("role,x,z,height,onCoarseLattice,inCoarseLocalBody,creditedFloor,escape\n");
        for (BlockCell m : c.members())
            csv.append(m.equals(c.owner()) ? "owner" : "member").append(',')
                    .append(m.x()).append(',').append(m.z()).append(',')
                    .append(r.warm.at(m.x(), m.z())).append(',')
                    .append(Math.floorMod(m.x(), r.step) == 0 && Math.floorMod(m.z(), r.step) == 0).append(',')
                    .append(coarse.contains(new WorldDrainage.Point(m.x(), m.z()))).append(',')
                    .append(c.floor()).append(',').append(c.escape()).append('\n');
        write(name, csv);
    }

    private static void writeCertificate(String name, Closed closed) {
        Certificate c = closed.certificate();
        StringBuilder csv = new StringBuilder("role,x,z,height,creditedFloor,escape\n");
        for (BlockCell m : c.members())
            csv.append(m.equals(c.owner()) ? "owner" : "member").append(',').append(m.x()).append(',')
                    .append(m.z()).append(',').append(closed.slab().h(m.x(), m.z())).append(',')
                    .append(c.floor()).append(',').append(c.escape()).append('\n');
        write(name, csv);
    }

    private static void writeCuts(Certificate c) {
        StringBuilder csv = new StringBuilder("innerX,innerZ,outerX,outerZ,innerH,outerH,crest,atSill,belowSill\n");
        for (Cut k : c.cuts())
            csv.append(k.inner().x()).append(',').append(k.inner().z()).append(',')
                    .append(k.outer().x()).append(',').append(k.outer().z()).append(',')
                    .append(k.innerHeight()).append(',').append(k.outerHeight()).append(',')
                    .append(k.crest()).append(',')
                    .append(k.crest() == c.escape()).append(',')
                    .append(k.crest() < c.escape()).append('\n');
        write("block-cuts.csv", csv);
    }

    /** The full geometry chain as one table, so every hop is auditable instead of only the endpoints. */
    private static void writeTrace(Real r, List<Cut> atSill, BlockCell seed, Descent down, Certificate d) {
        StringBuilder csv = new StringBuilder("kind,x,z,height,note\n");
        csv.append("coarseSink,").append(r.startX).append(',').append(r.startZ).append(',')
                .append(r.warm.at(r.startX, r.startZ)).append(",step=").append(r.step).append('\n');
        for (BlockCell p : r.blockDescent.path())
            csv.append("descent,").append(p.x()).append(',').append(p.z()).append(',')
                    .append(r.warm.at(p.x(), p.z())).append(",\n");
        csv.append("blockSink,").append(r.blockSink.x()).append(',').append(r.blockSink.z()).append(',')
                .append(r.warm.at(r.blockSink.x(), r.blockSink.z())).append(",floor\n");
        for (Cut k : atSill)
            csv.append("sillCut,").append(k.outer().x()).append(',').append(k.outer().z()).append(',')
                    .append(k.outerHeight()).append(",inner=").append(k.inner()).append(" crest=")
                    .append(k.crest()).append('\n');
        if (seed != null)
            csv.append("receiverSeed,").append(seed.x()).append(',').append(seed.z()).append(',')
                    .append(r.warm.at(seed.x(), seed.z())).append(",\n");
        if (down != null) {
            for (BlockCell p : down.path())
                csv.append("downDescent,").append(p.x()).append(',').append(p.z()).append(',')
                        .append(r.warm.at(p.x(), p.z())).append(",\n");
            csv.append("downstreamSink,").append(down.sink().x()).append(',').append(down.sink().z()).append(',')
                    .append(r.warm.at(down.sink().x(), down.sink().z())).append(",\n");
        }
        if (d != null)
            csv.append("downstreamEscape,,,").append(d.escape()).append(",members=").append(d.members().size())
                    .append(" closed=").append(d.closed()).append('\n');
        write("outlet-trace.csv", csv);
    }

    private static void writeGap(Real r, Certificate c, Set<BlockCell> coarse, List<BlockCell> onLattice,
                                 Set<BlockCell> overlap, Set<BlockCell> containing) {
        StringBuilder csv = new StringBuilder("metric,blockValue,coarseValue,delta,note\n");
        csv.append("members,").append(c.members().size()).append(',').append(coarse.size()).append(",,")
                .append("block columns vs coarse lattice points\n");
        csv.append("floor,").append(c.floor()).append(',').append(r.coarseLocal.floor()).append(',')
                .append(c.floor() - r.coarseLocal.floor()).append(",block floor is not a lattice point value\n");
        csv.append("sill,").append(c.escape()).append(',').append(r.coarseLocal.sill()).append(',')
                .append(c.escape() - r.coarseLocal.sill()).append(",first-outlet crest vs coarse local cut\n");
        if (r.coarseBasin != null)
            csv.append("publishedSill,").append(c.escape()).append(',').append(r.coarseBasin.sill()).append(',')
                    .append(c.escape() - r.coarseBasin.sill()).append(",after the backwater merge\n");
        csv.append("onCoarseLattice,").append(onLattice.size()).append(',').append(coarse.size()).append(",,")
                .append("how many block members are even representable as lattice points\n");
        csv.append("overlap,").append(overlap.size()).append(",,,")
                .append("shared lattice columns (not identity)\n");
        csv.append("containingCoarseCells,").append(containing.size()).append(',').append(coarse.size()).append(",,")
                .append("floor-div superset relation, not identity\n");
        write("interface-gap.csv", csv);
    }

    private static void write(String name, CharSequence body) {
        try {
            Files.writeString(OUT.resolve(name), body.toString());
        } catch (Exception failure) {
            undecided("write " + name, String.valueOf(failure));
        }
    }

    // ------------------------------------------------------------------ small helpers

    private static String describe(Certificate c) {
        return "closed=" + c.closed() + " floor=" + c.floor() + " escape=" + c.escape()
                + " members=" + c.members().size() + " cuts=" + c.cuts().size()
                + " atSill=" + c.atSill() + " belowSill=" + c.belowSill() + " owner=" + c.owner()
                + " reason=" + (c.reason().isEmpty() ? "<none>" : c.reason());
    }

    private static Set<Long> keys(List<BlockCell> cells) {
        Set<Long> out = new HashSet<>();
        for (BlockCell c : cells) out.add(key(c.x(), c.z()));
        return out;
    }

    /** Last few columns of a walk, so a refusal can be reported without dumping a long path. */
    private static String tail(List<BlockCell> path) {
        if (path == null || path.isEmpty()) return "<empty>";
        int from = Math.max(0, path.size() - 4);
        StringBuilder sb = new StringBuilder();
        if (from > 0) sb.append("...(").append(path.size()).append(" nodes)...");
        for (int i = from; i < path.size(); i++) sb.append(path.get(i));
        return sb.toString();
    }

    private static List<BlockCell> concat(List<BlockCell> a, List<BlockCell> b) {
        List<BlockCell> out = new ArrayList<>(a);
        out.addAll(b);
        return out;
    }
}
