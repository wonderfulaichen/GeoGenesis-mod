package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

/** 验证河流单元相对横向邻域位于地形低处，而非随机穿越山脊。 */
public final class HydrologyTerrainFitProbe {
    private HydrologyTerrainFitProbe() { }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        TerrainParams params = TerrainParams.defaults();
        CellGenerator terrain = new CellGenerator(params, params.minY(), params.maxY());
        terrain.seed(seed);
        HydrologyGrid grid = HydrologySimulator.run(terrain, seed, -512, -512,
                128, 32, 8, 128.0).grid();
        int samples = 0, valleyCells = 0, ridgeCells = 0;
        double minMargin = Double.POSITIVE_INFINITY, meanMargin = 0.0;
        for (int i = 0; i < grid.size() * grid.size(); i++) {
            if (!grid.river(i)) continue;
            int x = grid.x(i), z = grid.z(i);
            double sideSum = 0.0;
            int sideCount = 0;
            double lowestSide = Double.POSITIVE_INFINITY;
            for (int d = 0; d < 8; d++) {
                int nx = x + FlowDirectionSolver.dx(d);
                int nz = z + FlowDirectionSolver.dz(d);
                if (nx < 0 || nz < 0 || nx >= grid.size() || nz >= grid.size()) continue;
                int ni = grid.index(nx, nz);
                if (FlowDirectionSolver.downstream(grid, i) == ni) continue;
                sideSum += grid.elevation(ni);
                lowestSide = Math.min(lowestSide, grid.elevation(ni));
                sideCount++;
            }
            if (sideCount == 0) continue;
            double margin = sideSum / sideCount - grid.elevation(i);
            samples++;
            meanMargin += margin;
            minMargin = Math.min(minMargin, margin);
            if (margin >= 0.0) valleyCells++;
            else ridgeCells++;
        }
        meanMargin = samples == 0 ? 0.0 : meanMargin / samples;
        System.out.println("=== HydrologyTerrainFitProbe ===");
        System.out.println("seed=" + seed);
        System.out.println("samples=" + samples);
        System.out.println("valleyCells=" + valleyCells);
        System.out.println("ridgeCells=" + ridgeCells);
        System.out.println("valleyRatio=" + (samples == 0 ? 0.0 : valleyCells / (double) samples));
        System.out.println("meanSideMargin=" + meanMargin);
        System.out.println("minSideMargin=" + minMargin);
        System.out.println("status=" + (samples > 0 && valleyCells > ridgeCells ? "PASS" : "REVIEW"));
    }
}
