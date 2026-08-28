package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

/** 按河流法向两侧比较岸坡高度，避免把连续斜坡误判为河脊。 */
public final class HydrologyCrossSectionProbe {
    private HydrologyCrossSectionProbe() { }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        TerrainParams params = TerrainParams.defaults();
        CellGenerator terrain = new CellGenerator(params, params.minY(), params.maxY());
        terrain.seed(seed);
        HydrologyGrid grid = HydrologySimulator.run(terrain, seed, -512, -512,
                128, 32, 8, 128.0).grid();
        int samples = 0, valley = 0, inverted = 0;
        double minLeft = Double.POSITIVE_INFINITY, minRight = Double.POSITIVE_INFINITY;
        for (int i = 0; i < grid.size() * grid.size(); i++) {
            if (!grid.river(i)) continue;
            int next = FlowDirectionSolver.downstream(grid, i);
            if (next < 0) continue;
            int dx = grid.x(next) - grid.x(i), dz = grid.z(next) - grid.z(i);
            int leftX = grid.x(i) - dz, leftZ = grid.z(i) + dx;
            int rightX = grid.x(i) + dz, rightZ = grid.z(i) - dx;
            if (!inside(grid, leftX, leftZ) || !inside(grid, rightX, rightZ)) continue;
            double center = grid.elevation(i);
            double left = grid.elevation(grid.index(leftX, leftZ));
            double right = grid.elevation(grid.index(rightX, rightZ));
            double leftMargin = left - center, rightMargin = right - center;
            samples++;
            minLeft = Math.min(minLeft, leftMargin);
            minRight = Math.min(minRight, rightMargin);
            if (leftMargin >= 0.0 && rightMargin >= 0.0) valley++;
            if (leftMargin < -0.05 && rightMargin < -0.05) inverted++;
        }
        System.out.println("=== HydrologyCrossSectionProbe ===");
        System.out.println("seed=" + seed);
        System.out.println("samples=" + samples);
        System.out.println("valleyCells=" + valley);
        System.out.println("invertedCells=" + inverted);
        System.out.println("valleyRatio=" + (samples == 0 ? 0.0 : valley / (double) samples));
        System.out.println("minLeftMargin=" + minLeft);
        System.out.println("minRightMargin=" + minRight);
        System.out.println("status=" + (inverted == 0 ? "PASS" : "REVIEW"));
    }

    private static boolean inside(HydrologyGrid grid, int x, int z) {
        return x >= 0 && z >= 0 && x < grid.size() && z < grid.size();
    }
}
