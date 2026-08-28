package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

/** 验证河流横断面与雕刻沿下游连续。 */
public final class HydrologyContinuityProbe {
    private HydrologyContinuityProbe() { }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        TerrainParams params = TerrainParams.defaults();
        CellGenerator terrain = new CellGenerator(params, params.minY(), params.maxY());
        terrain.seed(seed);
        HydrologyGrid grid = HydrologySimulator.run(terrain, seed, -512, -512,
                128, 32, 8, 128.0).grid();
        HydrologyContinuityMetrics metrics = HydrologyContinuityAnalyzer.analyze(grid);
        System.out.println("=== HydrologyContinuityProbe ===");
        System.out.println("seed=" + seed);
        System.out.println("samples=" + metrics.samples());
        System.out.println("missing=" + metrics.missing());
        System.out.println("invalid=" + metrics.invalid());
        System.out.println("erosionJumps=" + metrics.maxErosionJumpCount());
        System.out.println("maxErosionJump=" + metrics.maxErosionJump());
        System.out.println("widthJumps=" + metrics.maxWidthJumpCount());
        System.out.println("maxWidthJump=" + metrics.maxWidthJump());
        System.out.println("depthJumps=" + metrics.maxDepthJumpCount());
        System.out.println("maxDepthJump=" + metrics.maxDepthJump());
        System.out.println("hash=" + Long.toUnsignedString(metrics.hash()));
        System.out.println("status=" + (metrics.missing() + metrics.invalid() == 0 ? "PASS" : "FAIL"));
    }
}
