package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

/** 多种子水文验收：汇总贴谷、纵向坡降、汇流连续性和雕刻约束。 */
public final class HydrologyMultiSeedProbe {
    private static final long[] SEEDS = {12345L, 777L, 98765L, 24680L};

    private HydrologyMultiSeedProbe() { }

    public static void main(String[] args) {
        long[] seeds = args.length == 0 ? SEEDS : parseSeeds(args);
        int failures = 0;
        for (long seed : seeds) {
            SeedMetrics metrics = measure(seed);
            failures += metrics.pass() ? 0 : 1;
            print(metrics);
        }
        System.out.println("=== HydrologyMultiSeedSummary ===");
        System.out.println("seeds=" + seeds.length);
        System.out.println("failures=" + failures);
        System.out.println("status=" + (failures == 0 ? "PASS" : "REVIEW"));
    }

    private static SeedMetrics measure(long seed) {
        TerrainParams params = TerrainParams.defaults();
        CellGenerator terrain = new CellGenerator(params, params.minY(), params.maxY());
        terrain.seed(seed);
        HydrologyGrid grid = HydrologySimulator.run(terrain, seed, -512, -512,
                128, 32, 8, 128.0).grid();
        int samples = 0, landCells = 0, valleys = 0, up = 0, confluences = 0;
        double minMargin = Double.POSITIVE_INFINITY, maxSurfaceJump = 0.0;
        RiverWaterProfile[] water = RiverWaterSolver.solve(grid);
        for (int i = 0; i < water.length; i++) {
            if (grid.isCore(i) && !grid.ocean(i)) landCells++;
            if (!grid.river(i)) continue;
            samples++;
            int x = grid.x(i), z = grid.z(i);
            double margin = sideMargin(grid, i, x, z);
            minMargin = Math.min(minMargin, margin);
            if (margin >= 0.0) valleys++;
            int next = FlowDirectionSolver.downstream(grid, i);
            if (next >= 0 && grid.river(next)) {
                if (grid.elevation(next) > grid.elevation(i) + 1e-9) up++;
                maxSurfaceJump = Math.max(maxSurfaceJump,
                        Math.abs(water[i].surface() - water[next].surface()));
            }
            if (riverParents(grid, i) >= 2) confluences++;
        }
        double ratio = samples == 0 ? 0.0 : valleys / (double) samples;
        boolean pass = landCells == 0 || (samples > 0 && ratio >= 0.85 && up == 0 && maxSurfaceJump < 0.25);
        return new SeedMetrics(seed, samples, ratio, up, confluences,
                minMargin, maxSurfaceJump, pass);
    }

    private static double sideMargin(HydrologyGrid grid, int cell, int x, int z) {
        double sum = 0.0;
        int count = 0;
        for (int d = 0; d < 8; d++) {
            int nx = x + FlowDirectionSolver.dx(d), nz = z + FlowDirectionSolver.dz(d);
            if (nx < 0 || nz < 0 || nx >= grid.size() || nz >= grid.size()) continue;
            int next = grid.index(nx, nz);
            if (FlowDirectionSolver.downstream(grid, cell) == next) continue;
            sum += grid.elevation(next);
            count++;
        }
        return count == 0 ? 0.0 : sum / count - grid.elevation(cell);
    }

    private static int riverParents(HydrologyGrid grid, int target) {
        int count = 0;
        for (int i = 0; i < grid.size() * grid.size(); i++) {
            if (grid.river(i) && FlowDirectionSolver.downstream(grid, i) == target) count++;
        }
        return count;
    }

    private static long[] parseSeeds(String[] args) {
        long[] seeds = new long[args.length];
        for (int i = 0; i < args.length; i++) seeds[i] = Long.parseLong(args[i]);
        return seeds;
    }

    private static void print(SeedMetrics m) {
        System.out.println("seed=" + m.seed + " samples=" + m.samples
                + " valleyRatio=" + m.valleyRatio + " uphill=" + m.uphill
                + " confluences=" + m.confluences + " minMargin=" + m.minMargin
                + " maxSurfaceJump=" + m.maxSurfaceJump + " status=" + (m.pass ? "PASS" : "REVIEW"));
    }

    private record SeedMetrics(long seed, int samples, double valleyRatio, int uphill,
                               int confluences, double minMargin, double maxSurfaceJump,
                               boolean pass) { }
}
