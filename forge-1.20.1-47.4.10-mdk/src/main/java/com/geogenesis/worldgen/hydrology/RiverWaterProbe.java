package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

/** 验证河流纵向水面无断裂、无上游低于下游和河床高于水面。 */
public final class RiverWaterProbe {
    private RiverWaterProbe() { }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        TerrainParams params = TerrainParams.defaults();
        CellGenerator terrain = new CellGenerator(params, params.minY(), params.maxY());
        terrain.seed(seed);
        HydrologyGrid grid = HydrologySimulator.run(terrain, seed, -512, -512,
                128, 32, 8, 128.0).grid();
        RiverWaterProfile[] profiles = RiverWaterSolver.solve(grid);
        int count = 0, missing = 0, bedViolations = 0, uphill = 0;
        double maxStep = 0.0;
        for (RiverWaterProfile profile : profiles) {
            if (profile == null) continue;
            count++;
            if (!(profile.bed() < profile.surface())) bedViolations++;
            int next = profile.downstream();
            if (next >= 0 && grid.river(next)) {
                RiverWaterProfile downstream = profiles[next];
                if (downstream == null) missing++;
                else {
                    maxStep = Math.max(maxStep, Math.abs(profile.surface() - downstream.surface()));
                    if (profile.surface() + 1e-9 < downstream.surface()) uphill++;
                }
            }
        }
        System.out.println("=== RiverWaterProbe ===");
        System.out.println("seed=" + seed);
        System.out.println("profiles=" + count);
        System.out.println("missingDownstream=" + missing);
        System.out.println("bedViolations=" + bedViolations);
        System.out.println("uphillViolations=" + uphill);
        System.out.println("maxSurfaceStep=" + maxStep);
        System.out.println("status=" + (missing + bedViolations + uphill == 0 ? "PASS" : "FAIL"));
    }
}
