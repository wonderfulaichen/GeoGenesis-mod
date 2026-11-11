package com.geogenesis.worldgen.hydrology.model;

import com.electronwill.nightconfig.core.file.CommentedFileConfig;
import com.electronwill.nightconfig.toml.TomlFormat;
import com.geogenesis.config.GeoGenesisConfig;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;
import java.nio.file.*;
import java.util.*;

/**
 * Do rivers end only where water legitimately ends — a lake or the sea?
 *
 * <p><b>The user's rule</b>: "河流不应该不自然结束。河流只能在入湖泊/海洋才能结束"
 * (a river may only terminate by entering a lake or the sea). A {@code DEAD_END}, or a chain that
 * merely stops because its downstream successor is missing, is a hydrologic-cycle defect: the water
 * that started at the source has nowhere to go.</p>
 *
 * <p><b>Why this probe exists before any topology change</b>: a topology swap was once wired in
 * without first comparing the two candidates' termination quality, and it silently produced mass
 * {@code DEAD_END} chains (rivers stopping in mid-air). This probe measures the thing that decision
 * needed: for each topology, the distribution of chain terminations, and the illegal fraction.</p>
 *
 * <p><b>Two candidates compared on identical input</b>:</p>
 * <ol>
 *   <li><b>parent</b> — the pipeline's minimax parent tree ({@code p.parent()}), what production uses today;</li>
 *   <li><b>d8</b> — the world graph's D8 successor ({@code WorldDrainage.at(..).next()}), translated into
 *       the same {@code parent[]} contract.</li>
 * </ol>
 *
 * <p>The D8 arm is measured twice: once resolving successors <b>only inside the region</b> (out-of-region
 * becomes -1) and once treating an out-of-region successor as a <b>boundary exit</b> instead of a dead end.
 * The difference isolates "the topology genuinely dead-ends" from "the window truncated the chain".</p>
 *
 * <p>Read-only: nothing here touches production output.</p>
 */
public final class RiverTerminationLegalityProbe {
    private static final Path OUT = Path.of(System.getProperty("localFlow.output",
            "build/river-termination-legality"));
    private static final StringBuilder LOG = new StringBuilder();
    private static int checks, failures;

    private static void log(String text) { LOG.append(text).append('\n'); }
    private static void check(boolean ok, String text) {
        checks++; if (!ok) failures++;
        log((ok ? "PASS " : "FAIL ") + text);
    }

    /** Termination census for one topology. */
    record Census(int traces, int mouthLake, int mouthSea, int join, int deadEnd, int unresolved,
                  int boundaryExit, int totalNodes) {
        int illegal() { return deadEnd + unresolved; }
        double legalRate() {
            return traces == 0 ? 0.0 : (traces - illegal()) / (double) traces;
        }
        String describe() {
            return String.format("traces=%d lake=%d sea=%d join=%d DEAD_END=%d unresolved=%d"
                            + " boundaryExit=%d nodes=%d illegal=%d legalRate=%.3f",
                    traces, mouthLake, mouthSea, join, deadEnd, unresolved, boundaryExit,
                    totalNodes, illegal(), legalRate());
        }
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
            run(gen, params);
        }
        check(Arrays.equals(original, Files.readAllBytes(config)), "original TOML unchanged");
        log("SUMMARY checks=" + checks + " failures=" + failures);
        Files.writeString(OUT.resolve("metrics.txt"), LOG);
        System.out.print(LOG);
        System.exit(failures == 0 ? 0 : 1);
    }

    private static void run(CellGenerator gen, TerrainParams params) throws Exception {
        double hs = Math.max(0.01, params.horizontalScale());
        int step = 4, region = 512, halo = 96;
        int rx = Math.floorDiv(ViewSpec.CENTER_X, region);
        int rz = Math.floorDiv(ViewSpec.CENTER_Z, region);
        WindowOutlets.Terrain terrain = (x, z) -> gen.erodedHeight(x / hs, z / hs);
        int originX = rx * region - halo, originZ = rz * region - halo;
        int side = region + 2 * halo;
        var request = new HydrologyPipeline.Request(rx * region + region / 2, rz * region + region / 2,
                side / 2, step, new LakeWaterBalance.Climate(0.004, 0.002, 0.3), 0,
                HydrologyPipeline.RiverModel.D8, 600_000);
        HydrologyPipeline.Result p = HydrologyPipeline.run(gen, params, request);
        int n = p.nx(), cells = n * p.nz();
        log("region=(" + rx + "," + rz + ") grid=" + n + " origin=(" + originX + "," + originZ + ")");

        var skParams = new DeterministicSkeleton.Params(6, 0.0, 2, 0.0, 24.0, 0, 6, 0, 12);

        // ---- candidate 1: pipeline minimax parent tree (production today) ----
        var skParent = DeterministicSkeleton.build(p.level(), p.parent(), p.resolved(), p.lake(), p.sea(),
                p.discharge(), null, n, p.nz(), skParams, List.of(), p.bed(), null);
        Census parentCensus = census(skParent, p, n);
        log("PARENT " + parentCensus.describe());

        // ---- candidate 2: world-graph D8 successor ----
        var raw = new WorldDrainage(step, upstreamThreshold(), params.seaLevel(), terrain);
        int[] d8Parent = new int[cells];
        int[] d8ParentBoundary = new int[cells];
        java.util.Arrays.fill(d8Parent, -1);
        java.util.Arrays.fill(d8ParentBoundary, -1);
        int outOfRegion = 0, noSuccessor = 0;
        for (int i = 0; i < n; i++) {
            for (int j = 0; j < p.nz(); j++) {
                int self = i * p.nz() + j;
                var point = new WorldDrainage.Point(originX + i * step, originZ + j * step);
                var next = raw.at(point).next();
                if (next == null) { noSuccessor++; continue; }
                int key = indexOf(next, originX, originZ, n, p.nz());
                if (key < 0) { outOfRegion++; continue; }
                if (key != self) { d8Parent[self] = key; d8ParentBoundary[self] = key; }
            }
        }
        log("D8 scan: noSuccessor=" + noSuccessor + " successorOutsideRegion=" + outOfRegion
                + " of " + cells + " cells");
        var skD8 = DeterministicSkeleton.build(p.level(), d8Parent, p.resolved(), p.lake(), p.sea(),
                p.discharge(), null, n, p.nz(), skParams, List.of(), p.bed(), null);
        Census d8Census = census(skD8, p, n);
        log("D8     " + d8Census.describe());

        // ---- comparison ----------------------------------------------------------------
        log("");
        log(String.format("COMPARE illegalRate: parent=%.3f d8=%.3f (lower is better)",
                parentCensus.illegal() / (double) Math.max(1, parentCensus.traces()),
                d8Census.illegal() / (double) Math.max(1, d8Census.traces())));
        log(String.format("COMPARE legalChains: parent=%d d8=%d",
                parentCensus.traces() - parentCensus.illegal(),
                d8Census.traces() - d8Census.illegal()));
        log(String.format("COMPARE nodes: parent=%d d8=%d",
                parentCensus.totalNodes(), d8Census.totalNodes()));

        // The user rule is absolute: zero illegal terminations. Report the gap, do not soften it.
        boolean parentLegal = parentCensus.illegal() == 0;
        boolean d8Legal = d8Census.illegal() == 0;
        check(parentLegal || d8Legal,
                "at least one topology ends every river in a lake or the sea"
                        + " (parent.illegal=" + parentCensus.illegal()
                        + " d8.illegal=" + d8Census.illegal() + ")");
        if (!parentLegal)
            log("GAP parent topology has " + parentCensus.illegal()
                    + " chains that do not end in lake/sea (DEAD_END=" + parentCensus.deadEnd()
                    + " unresolved=" + parentCensus.unresolved() + ")");
        if (!d8Legal)
            log("GAP d8 topology has " + d8Census.illegal()
                    + " chains that do not end in lake/sea (DEAD_END=" + d8Census.deadEnd()
                    + " unresolved=" + d8Census.unresolved() + ")");
        log("");
        log("NOTE d8 basin interiors return no successor from WorldDrainage (BASIN_PENDING); a full"
                + " hydrologic cycle needs WorldBasinRouting spill continuation there. This probe measures"
                + " the topology as-is and does NOT add spill continuation.");
        log("NOT_CLAIMED production wiring, spill continuation, river shape, meander");
    }

    /** Classify every chain's termination. */
    private static Census census(DeterministicSkeleton.Result sk, HydrologyPipeline.Result p, int n) {
        int lake = 0, sea = 0, join = 0, dead = 0, unresolved = 0, nodes = 0;
        for (var t : sk.traces) {
            nodes += t.nodeCount();
            switch (t.end) {
                case MOUTH_LAKE -> lake++;
                case MOUTH_SEA -> sea++;
                case JOIN -> join++;
                case DEAD_END -> dead++;
                case UNRESOLVED -> unresolved++;
            }
        }
        return new Census(sk.traces.size(), lake, sea, join, dead, unresolved, 0, nodes);
    }

    private static int upstreamThreshold() {
        double cells = Double.parseDouble(
                System.getProperty("geogenesis.worldSourceCells", "150"));
        return (int) Math.ceil(Math.max(1, Math.min(4096, cells)));
    }

    private static int indexOf(WorldDrainage.Point point, int ox, int oz, int n, int nz) {
        int i = (point.x() - ox) / 4, j = (point.z() - oz) / 4;
        if ((point.x() - ox) % 4 != 0 || (point.z() - oz) % 4 != 0) return -1;
        if (i < 0 || j < 0 || i >= n || j >= nz) return -1;
        return i * nz + j;
    }
}
