package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

/** 纯 Java 水文基线探针：固定参数输出河网摘要与确定性哈希。 */
public final class HydrologyProbe {
    private HydrologyProbe() { }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        int size = args.length > 1 ? Integer.parseInt(args[1]) : 128;
        int spacing = args.length > 2 ? Integer.parseInt(args[2]) : 8;
        TerrainParams params = TerrainParams.defaults();
        CellGenerator terrain = new CellGenerator(params, params.minY(), params.maxY());
        terrain.seed(seed);
        HydrologyResult result = HydrologySimulator.run(terrain, seed, -size / 2, -size / 2,
                size, 8, spacing, 128.0);
        System.out.println("=== HydrologyProbe ===");
        System.out.println("seed=" + seed);
        System.out.println("coordinate=wu");
        System.out.println("coreSize=" + size + " spacing=" + spacing + " halo=8");
        System.out.println("riverCells=" + result.riverCells());
        System.out.println("outletCells=" + result.outletCells());
        System.out.println("oceanOutlets=" + result.oceanOutlets());
        System.out.println("lakeCells=" + result.lakeCells());
        System.out.println("flowCycles=" + result.flowCycles());
        System.out.println("boundaryRiverCells=" + result.boundaryRiverCells());
        System.out.println("maxFlow=" + result.maxFlow());
        HydrologyMetrics metrics = HydrologyDiagnostics.measure(result.grid(), result.hash());
        System.out.println("sources=" + metrics.riverSources());
        System.out.println("confluences=" + metrics.confluences());
        System.out.println("lakeOutlets=" + metrics.lakeOutlets());
        System.out.println("boundaryOutlets=" + metrics.boundaryOutlets());
        System.out.println("landOutlets=" + metrics.landOutlets());
        System.out.println("flowViolations=" + metrics.monotonicViolations());
        System.out.println("maxRiverChain=" + metrics.maxRiverChain());
        RiverNetworkSummary summary = RiverNetworkExtractor.summarize(result.grid());
        System.out.println("segments=" + summary.segments().size());
        System.out.println("outletOcean=" + count(summary, RiverOutlet.Type.OCEAN));
        System.out.println("outletLake=" + count(summary, RiverOutlet.Type.LAKE));
        System.out.println("outletBoundary=" + count(summary, RiverOutlet.Type.REGION_BOUNDARY));
        System.out.println("outletLandSink=" + count(summary, RiverOutlet.Type.LAND_SINK));
        System.out.println("mainStemFlow=" + (summary.mainStem() == null ? 0.0 : summary.mainStem().targetFlow()));
        System.out.println("hash=" + Long.toUnsignedString(result.hash()));
        if (size <= 256) System.out.print(HydrologyAscii.render(result.grid(), Math.max(1, size / 64)));
    }

    private static long count(RiverNetworkSummary summary, RiverOutlet.Type type) {
        return summary.outlets().stream().filter(outlet -> outlet.type() == type).count();
    }
}
