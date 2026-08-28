package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

/** 检查汇流节点上游水面和河床是否出现不连续台阶。 */
public final class HydrologyConfluenceProbe {
    private HydrologyConfluenceProbe() { }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        TerrainParams params = TerrainParams.defaults();
        CellGenerator terrain = new CellGenerator(params, params.minY(), params.maxY());
        terrain.seed(seed);
        HydrologyGrid grid = HydrologySimulator.run(terrain, seed, -512, -512,
                128, 32, 8, 128.0).grid();
        RiverWaterProfile[] water = RiverWaterSolver.solve(grid);
        int confluences = 0, surfaceJumps = 0, bedJumps = 0;
        double maxSurfaceJump = 0.0, maxBedJump = 0.0;
        for (int i = 0; i < water.length; i++) {
            if (!grid.river(i) || riverParents(grid, i) < 2) continue;
            confluences++;
            double minSurface = Double.POSITIVE_INFINITY, maxSurface = Double.NEGATIVE_INFINITY;
            double minBed = Double.POSITIVE_INFINITY, maxBed = Double.NEGATIVE_INFINITY;
            for (int parent = 0; parent < water.length; parent++) {
                if (!grid.river(parent) || FlowDirectionSolver.downstream(grid, parent) != i) continue;
                RiverWaterProfile profile = water[parent];
                if (profile == null) continue;
                minSurface = Math.min(minSurface, profile.surface());
                maxSurface = Math.max(maxSurface, profile.surface());
                minBed = Math.min(minBed, profile.bed());
                maxBed = Math.max(maxBed, profile.bed());
            }
            double surfaceJump = maxSurface - minSurface;
            double bedJump = maxBed - minBed;
            if (surfaceJump > 0.25) surfaceJumps++;
            if (bedJump > 0.5) bedJumps++;
            maxSurfaceJump = Math.max(maxSurfaceJump, surfaceJump);
            maxBedJump = Math.max(maxBedJump, bedJump);
        }
        System.out.println("=== HydrologyConfluenceProbe ===");
        System.out.println("seed=" + seed);
        System.out.println("confluences=" + confluences);
        System.out.println("surfaceJumps=" + surfaceJumps);
        System.out.println("maxSurfaceJump=" + maxSurfaceJump);
        System.out.println("bedJumps=" + bedJumps);
        System.out.println("maxBedJump=" + maxBedJump);
        System.out.println("status=" + (surfaceJumps == 0 && bedJumps == 0 ? "PASS" : "REVIEW"));
    }

    private static int riverParents(HydrologyGrid grid, int target) {
        int count = 0;
        for (int i = 0; i < grid.size() * grid.size(); i++) {
            if (grid.river(i) && FlowDirectionSolver.downstream(grid, i) == target) count++;
        }
        return count;
    }
}
