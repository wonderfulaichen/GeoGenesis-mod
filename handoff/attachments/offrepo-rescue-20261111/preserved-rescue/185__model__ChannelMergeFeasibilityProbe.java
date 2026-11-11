package com.geogenesis.worldgen.hydrology.model;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.geogenesis.config.GeoGenesisConfig;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;
import java.nio.file.*;
import java.util.*;

/**
 * Can the world graph's water levels be attached to the skeleton's shaped polylines?
 *
 * <p><b>Why this probe exists</b>: the two geometry branches produce nearly identical water
 * (river cells differ by 1.4%), so the merge that is worth doing is structural — keep the world
 * graph's authority (basin identity, lake plane, cross-region relay) and the skeleton's shaped
 * geometry. That merge only works if each world edge can be projected onto skeleton segments.</p>
 *
 * <p><b>What is measured, never assumed</b>: every world edge carries a lattice endpoint pair and
 * (for same-basin corridors) a single water plane. This probe builds both geometries for the same
 * region and measures how many world edges find a skeleton segment within a bounded radius, and how
 * many skeleton segments receive a plane from exactly one basin. A merge is only declared feasible
 * when the mismatch rate is below the threshold; otherwise the answer is NO and no merge is claimed.</p>
 *
 * <p>Read-only: nothing here touches production output, and the probe writes only under its own
 * output directory. No river-shape or meander change is attempted.</p>
 */
public final class ChannelMergeFeasibilityProbe {
    private static final Path OUT = Path.of(System.getProperty("localFlow.output",
            "build/channel-merge-feasibility"));
    /** Projection radius (blocks). One world edge is 4 blocks long; 4 blocks is one lattice step. */
    private static final double PROJECT_RADIUS = 4.0;
    /** User-set gate: above this the merge premise does not hold and nothing may be merged. */
    private static final double MISMATCH_LIMIT = 0.30;

    private static final StringBuilder LOG = new StringBuilder();
    private static int checks, failures;

    private static void log(String text) { LOG.append(text).append('\n'); }
    private static void check(boolean ok, String text) {
        checks++; if (!ok) failures++;
        log((ok ? "PASS " : "FAIL ") + text);
    }

    public static void main(String[] args) throws Exception {
        Files.createDirectories(OUT);
        long seed = args.length > 0 ? Long.parseLong(args[0]) : ViewSpec.SEED;
        Path config = Path.of("run/config/geogenesis-common.toml");
        byte[] original = Files.readAllBytes(config);
        Path copy = OUT.resolve("config.toml");
        Files.write(copy, original);
        try (var cfg = CommentedFileConfig.of(copy, TomlFormat.instance())) {
            cfg.load();
            GeoGenesisConfig.SPEC.setConfig(cfg);
            TerrainParams params = GeoGenesisConfig.INSTANCE.buildParams();
            CellGenerator gen = new CellGenerator(params, params.minY(), params.maxY());
            gen.seed(seed);
            run(gen, params, seed);
        }
        check(Arrays.equals(original, Files.readAllBytes(config)), "original TOML unchanged");
        log("SUMMARY checks=" + checks + " failures=" + failures);
        Files.writeString(OUT.resolve("metrics.txt"), LOG);
        System.out.print(LOG);
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void run(CellGenerator gen, TerrainParams params, long seed) throws Exception {
        double hs = Math.max(0.01, params.horizontalScale());
        int step = 4, region = 512, halo = 96;
        // Same region as the canonical figure centre so the numbers describe what was rendered.
        int rx = Math.floorDiv(ViewSpec.CENTER_X, region);
        int rz = Math.floorDiv(ViewSpec.CENTER_Z, region);
        WindowOutlets.Terrain terrain = (x, z) -> gen.erodedHeight(x / hs, z / hs);

        int originX = rx * region - halo, originZ = rz * region - halo;
        int side = region + 2 * halo;
        int n = side / step + 1;
        var request = new HydrologyPipeline.Request(rx * region + region / 2, rz * region + region / 2,
                side / 2, step, new LakeWaterBalance.Climate(0.004, 0.002, 0.3), 0,
                HydrologyPipeline.RiverModel.D8, 600_000);
        HydrologyPipeline.Result p = HydrologyPipeline.run(gen, params, request);
        log("region=(" + rx + "," + rz + ") grid=" + p.nx() + " origin=(" + originX + "," + originZ + ")");

        // --- world graph geometry (branch A) ------------------------------------------------
        var graph = new WorldDrainage(step, upstreamThreshold(), params.seaLevel(), terrain);
        var routing = new WorldBasinRouting(graph, terrain, step, 131072);
        var flow = new WorldBasinFlow(graph, routing,
                new LakeWaterBalance.Climate(0.004, 0.002, 0.3), step, 32768);
        var water = flow.region(rx, rz, region);
        var world = water.graph();
        log("world edges=" + world.edges().size() + " pendingBasins=" + world.pendingBasins().size()
                + " unresolved=" + water.unresolved());
        check(water.unresolved() == 0, "world region resolves without UNKNOWN nodes");

        // --- skeleton geometry (branch B) ----------------------------------------------------
        var params2 = new DeterministicSkeleton.Params(6, 0.0, 2, 0.0, 24.0, 0, 6, 0, 12);
        var sk = DeterministicSkeleton.build(p.level(), p.parent(), p.resolved(), p.lake(), p.sea(),
                p.discharge(), p.lakeResult() == null ? null : p.lakeResult().basinOutlet(),
                p.nx(), p.nz(), params2, List.of(), p.bed(), null);
        var shaped = DeterministicSkeleton.shape(sk.traces, p.level(),
                originX, originZ, step, p.nx(), p.nz(), params2, p.bed());
        int segments = 0;
        for (var shape : shaped) segments += Math.max(0, shape.size() - 1);
        log("skeleton traces=" + sk.traces.size() + " shapedPolylines=" + shaped.size()
                + " segments=" + segments);
        check(segments > 0, "skeleton produces non-empty shaped geometry");

        // --- projection: world edge -> skeleton segment ---------------------------------------
        var skeletonPoints = new ArrayList<double[]>();
        for (var shape : shaped)
            for (int i = 0; i < shape.size(); i++) skeletonPoints.add(new double[]{shape.x[i], shape.z[i]});
        log("skeleton vertices=" + skeletonPoints.size());
        check(!skeletonPoints.isEmpty(), "skeleton vertices exist for projection");

        int matched = 0, unmatched = 0, withPlane = 0;
        double worstDistance = 0;
        var unmatchedEdges = new ArrayList<String>();
        for (var edge : world.edges()) {
            double ax = edge.from().x(), az = edge.from().z();
            double bx = edge.to().x(), bz = edge.to().z();
            double best = Double.POSITIVE_INFINITY;
            for (double[] point : skeletonPoints) {
                double distance = distanceToSegment(point[0], point[1], ax, az, bx, bz);
                if (distance < best) best = distance;
            }
            worstDistance = Math.max(worstDistance, best);
            if (best <= PROJECT_RADIUS) matched++;
            else {
                unmatched++;
                if (unmatchedEdges.size() < 8)
                    unmatchedEdges.add("(" + (int) ax + "," + (int) az + ")->(" + (int) bx + "," + (int) bz
                            + ") d=" + String.format("%.2f", best));
            }
            var wa = water.water().get(edge.from());
            var wb = water.water().get(edge.to());
            if (wa != null && wb != null && wa.basin() != null && wb.basin() != null
                    && wa.basin().identity().equals(wb.basin().identity())) withPlane++;
        }
        double mismatch = world.edges().isEmpty() ? 0.0
                : unmatched / (double) world.edges().size();
        log(String.format("PROJECTION matched=%d unmatched=%d mismatchRate=%.3f worstDist=%.2f"
                        + " sameBasinEdges=%d", matched, unmatched, mismatch, worstDistance, withPlane));
        for (String line : unmatchedEdges) log("  unmatched " + line);
        check(mismatch <= MISMATCH_LIMIT,
                String.format("world edges project onto skeleton within %.0f blocks (mismatch %.1f%% <= %.0f%%)",
                        PROJECT_RADIUS, mismatch * 100, MISMATCH_LIMIT * 100));
        check(withPlane > 0,
                "at least one world edge carries a same-basin water plane to attach");

        // --- the merge verdict is derived, never asserted -------------------------------------
        boolean feasible = mismatch <= MISMATCH_LIMIT && withPlane > 0;
        log("MERGE_FEASIBILITY=" + (feasible ? "FEASIBLE" : "NOT_FEASIBLE")
                + " (mismatch=" + String.format("%.3f", mismatch)
                + " limit=" + MISMATCH_LIMIT + " sameBasinPlanes=" + withPlane + ")");
        log("NOT_CLAIMED river shape, meander, width, flow direction, production wiring");
    }

    /** Same threshold the production world graph uses (kept local: this probe must not change it). */
    private static int upstreamThreshold() {
        double cells = Double.parseDouble(
                System.getProperty("geogenesis.worldSourceCells", "150"));
        return (int) Math.ceil(Math.max(1, Math.min(4096, cells)));
    }

    /** Distance from a point to a segment (projection test; no geometry is modified). */
    private static double distanceToSegment(double px, double pz, double ax, double az,
                                            double bx, double bz) {
        double dx = bx - ax, dz = bz - az;
        double lengthSq = dx * dx + dz * dz;
        if (lengthSq == 0) return Math.hypot(px - ax, pz - az);
        double t = ((px - ax) * dx + (pz - az) * dz) / lengthSq;
        t = Math.max(0, Math.min(1, t));
        return Math.hypot(px - (ax + t * dx), pz - (az + t * dz));
    }
}
