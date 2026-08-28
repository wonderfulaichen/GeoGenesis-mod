package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

/** 输出最差河流贴谷单元及其局部高程剖面，便于定位真实地形异常。 */
public final class HydrologyWorstCaseProbe {
    private HydrologyWorstCaseProbe() { }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        TerrainParams params = TerrainParams.defaults();
        CellGenerator terrain = new CellGenerator(params, params.minY(), params.maxY());
        terrain.seed(seed);
        HydrologyGrid grid = HydrologySimulator.run(terrain, seed, -512, -512,
                128, 32, 8, 128.0).grid();
        int worst = -1;
        double worstMargin = Double.POSITIVE_INFINITY;
        for (int i = 0; i < grid.size() * grid.size(); i++) {
            if (!grid.river(i)) continue;
            double margin = sideMargin(grid, i);
            if (margin < worstMargin) {
                worstMargin = margin;
                worst = i;
            }
        }
        System.out.println("=== HydrologyWorstCaseProbe ===");
        System.out.println("seed=" + seed);
        if (worst < 0) {
            System.out.println("status=NO_RIVER");
            return;
        }
        System.out.println("cell=" + worst);
        System.out.println("worldX=" + grid.worldX(grid.x(worst)));
        System.out.println("worldZ=" + grid.worldZ(grid.z(worst)));
        System.out.println("elevation=" + grid.elevation(worst));
        System.out.println("filledElevation=" + grid.filledElevation(worst));
        System.out.println("sideMargin=" + worstMargin);
        printNeighborhood(grid, worst);
        System.out.println("status=REVIEW");
    }

    private static double sideMargin(HydrologyGrid grid, int cell) {
        int x = grid.x(cell), z = grid.z(cell), downstream = FlowDirectionSolver.downstream(grid, cell);
        double sum = 0.0;
        int count = 0;
        for (int d = 0; d < 8; d++) {
            int nx = x + FlowDirectionSolver.dx(d), nz = z + FlowDirectionSolver.dz(d);
            if (nx < 0 || nz < 0 || nx >= grid.size() || nz >= grid.size()) continue;
            int next = grid.index(nx, nz);
            if (next == downstream) continue;
            sum += grid.elevation(next);
            count++;
        }
        return count == 0 ? 0.0 : sum / count - grid.elevation(cell);
    }

    private static void printNeighborhood(HydrologyGrid grid, int center) {
        int cx = grid.x(center), cz = grid.z(center);
        for (int z = cz - 2; z <= cz + 2; z++) {
            StringBuilder row = new StringBuilder();
            for (int x = cx - 2; x <= cx + 2; x++) {
                if (x < 0 || z < 0 || x >= grid.size() || z >= grid.size()) continue;
                row.append(String.format("%8.4f ", grid.elevation(grid.index(x, z))));
            }
            System.out.println(row);
        }
    }
}
