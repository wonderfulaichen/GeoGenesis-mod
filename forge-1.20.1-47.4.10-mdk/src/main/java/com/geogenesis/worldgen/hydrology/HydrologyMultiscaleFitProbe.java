package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

/** 多尺度法向剖面验证：区分平缓斜坡、浅谷和真正的河脊。 */
public final class HydrologyMultiscaleFitProbe {
    private static final int[] RADII = {1, 2, 4, 8};

    private HydrologyMultiscaleFitProbe() { }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        TerrainParams params = TerrainParams.defaults();
        CellGenerator terrain = new CellGenerator(params, params.minY(), params.maxY());
        terrain.seed(seed);
        HydrologyGrid grid = HydrologySimulator.run(terrain, seed, -512, -512,
                128, 32, 8, 128.0).grid();
        int samples = 0, ridge = 0;
        int[] valley = new int[RADII.length];
        double[] minMargins = {Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY,
                Double.POSITIVE_INFINITY, Double.POSITIVE_INFINITY};
        for (int i = 0; i < grid.size() * grid.size(); i++) {
            if (!grid.river(i)) continue;
            int next = FlowDirectionSolver.downstream(grid, i);
            if (next < 0) continue;
            int dx = Integer.signum(grid.x(next) - grid.x(i));
            int dz = Integer.signum(grid.z(next) - grid.z(i));
            samples++;
            boolean allScalesRise = true;
            for (int r = 0; r < RADII.length; r++) {
                double margin = normalMargin(grid, i, dx, dz, RADII[r]);
                minMargins[r] = Math.min(minMargins[r], margin);
                if (margin >= 0.0) valley[r]++;
                if (margin >= -0.05) allScalesRise = false;
            }
            if (allScalesRise) ridge++;
        }
        System.out.println("=== HydrologyMultiscaleFitProbe ===");
        System.out.println("seed=" + seed);
        System.out.println("samples=" + samples);
        for (int i = 0; i < RADII.length; i++) {
            System.out.println("radius=" + RADII[i] + " valleyRatio="
                    + ratio(valley[i], samples) + " minMargin=" + minMargins[i]);
        }
        System.out.println("strongRidgeCells=" + ridge);
        System.out.println("status=" + (ridge == 0 ? "PASS" : "REVIEW"));
    }

    private static double normalMargin(HydrologyGrid grid, int cell, int dx, int dz, int radius) {
        int x = grid.x(cell), z = grid.z(cell);
        int leftX = x - dz * radius, leftZ = z + dx * radius;
        int rightX = x + dz * radius, rightZ = z - dx * radius;
        if (!inside(grid, leftX, leftZ) || !inside(grid, rightX, rightZ)) return 0.0;
        double center = grid.elevation(cell);
        return (grid.elevation(grid.index(leftX, leftZ))
                + grid.elevation(grid.index(rightX, rightZ))) * 0.5 - center;
    }

    private static boolean inside(HydrologyGrid grid, int x, int z) {
        return x >= 0 && z >= 0 && x < grid.size() && z < grid.size();
    }

    private static double ratio(int count, int total) {
        return total == 0 ? 0.0 : count / (double) total;
    }
}
