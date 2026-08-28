package com.geogenesis.worldgen.hydrology;

import java.util.Arrays;

/** 从下游出口向上游反推连续水面；海洋出口锚定 e=0，湖泊出口锚定填洼水位。 */
public final class RiverWaterSolver {
    private RiverWaterSolver() { }

    public static RiverWaterProfile[] solve(HydrologyGrid grid) {
        int total = grid.size() * grid.size();
        RiverWaterProfile[] result = new RiverWaterProfile[total];
        Integer[] order = new Integer[total];
        for (int i = 0; i < total; i++) order[i] = i;
        Arrays.sort(order, (a, b) -> {
            int cmp = Double.compare(grid.filledElevation(a), grid.filledElevation(b));
            return cmp != 0 ? cmp : Integer.compare(a, b);
        });
        for (int p = 0; p < total; p++) {
            int i = order[p];
            if (!grid.river(i)) continue;
            int next = FlowDirectionSolver.downstream(grid, i);
            double surface = outletSurface(grid, i, next, result);
            if (next >= 0 && grid.river(next)) {
                double terrainDrop = Math.max(0.0,
                        grid.filledElevation(i) - grid.filledElevation(next));
                double drop = Math.max(0.0005, terrainDrop * 0.15);
                surface = result[next].surface() + drop;
            }
            RiverProfile profile = RiverProfileSolver.profileAt(grid, i);
            double bed = surface - normalizedDepth(profile.section().depth());
            result[i] = new RiverWaterProfile(i, next, surface, bed,
                    next < 0 ? 0.0 : Math.max(0.0, surface - resultSurface(result, next)),
                    profile.discharge());
        }
        return result;
    }

    private static double outletSurface(HydrologyGrid grid, int cell, int next,
                                        RiverWaterProfile[] profiles) {
        if (next >= 0 && grid.ocean(next)) return 0.0;
        if (next >= 0 && grid.lake(next)) return grid.filledElevation(next);
        if (next >= 0 && grid.river(next)) return resultSurface(profiles, next);
        return Math.max(0.0, grid.filledElevation(cell) - 0.01);
    }

    private static double resultSurface(RiverWaterProfile[] profiles, int index) {
        return profiles[index] == null ? 0.0 : profiles[index].surface();
    }

    private static double normalizedDepth(double depth) {
        return Math.min(0.12, depth * 0.01);
    }
}
