package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

/** 验证流量驱动宽深的正值、有限性和下游单调性。 */
public final class RiverProfileProbe {
    private RiverProfileProbe() { }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        TerrainParams params = TerrainParams.defaults();
        CellGenerator terrain = new CellGenerator(params, params.minY(), params.maxY());
        terrain.seed(seed);
        HydrologyGrid grid = HydrologySimulator.run(terrain, seed, -512, -512,
                128, 32, 8, 128.0).grid();
        int count = 0, invalid = 0, widthViolations = 0, depthViolations = 0;
        double maxWidth = 0.0, maxDepth = 0.0;
        for (RiverProfile profile : RiverProfileSolver.solve(grid)) {
            count++;
            RiverCrossSection section = profile.section();
            if (!Double.isFinite(section.width()) || !Double.isFinite(section.depth())
                    || section.width() <= 0.0 || section.depth() <= 0.0) invalid++;
            maxWidth = Math.max(maxWidth, section.width());
            maxDepth = Math.max(maxDepth, section.depth());
            int downstream = profile.downstream();
            if (downstream >= 0 && grid.river(downstream)) {
                RiverCrossSection next = RiverProfileSolver.profileAt(grid, downstream).section();
                if (next.width() + 1e-9 < section.width()) widthViolations++;
                if (next.depth() + 1e-9 < section.depth()) depthViolations++;
            }
        }
        System.out.println("=== RiverProfileProbe ===");
        System.out.println("seed=" + seed);
        System.out.println("profiles=" + count);
        System.out.println("invalid=" + invalid);
        System.out.println("widthViolations=" + widthViolations);
        System.out.println("depthViolations=" + depthViolations);
        System.out.println("maxWidth=" + maxWidth);
        System.out.println("maxDepth=" + maxDepth);
        System.out.println("status=" + (invalid == 0 ? "PASS" : "FAIL"));
    }

}
