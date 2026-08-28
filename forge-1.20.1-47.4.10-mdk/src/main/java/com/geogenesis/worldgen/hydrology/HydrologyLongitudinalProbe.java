package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;

/** 检查河流沿下游的原始/填洼高程走势，定位瀑布、洼地和异常上坡段。 */
public final class HydrologyLongitudinalProbe {
    private HydrologyLongitudinalProbe() { }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        TerrainParams params = TerrainParams.defaults();
        CellGenerator terrain = new CellGenerator(params, params.minY(), params.maxY());
        terrain.seed(seed);
        HydrologyGrid grid = HydrologySimulator.run(terrain, seed, -512, -512,
                128, 32, 8, 128.0).grid();
        int edges = 0, filledUpslope = 0, rawUpslope = 0, longestAscent = 0;
        double maxRawRise = 0.0, maxFilledRise = 0.0;
        for (int i = 0; i < grid.size() * grid.size(); i++) {
            if (!grid.river(i)) continue;
            int next = FlowDirectionSolver.downstream(grid, i);
            if (next < 0 || !grid.river(next)) continue;
            edges++;
            double rawRise = grid.elevation(next) - grid.elevation(i);
            double filledRise = grid.filledElevation(next) - grid.filledElevation(i);
            if (rawRise > 1e-9) {
                rawUpslope++;
                maxRawRise = Math.max(maxRawRise, rawRise);
            }
            if (filledRise > 1e-9) {
                filledUpslope++;
                maxFilledRise = Math.max(maxFilledRise, filledRise);
            }
            longestAscent = Math.max(longestAscent, ascentRun(grid, i));
        }
        System.out.println("=== HydrologyLongitudinalProbe ===");
        System.out.println("seed=" + seed);
        System.out.println("riverEdges=" + edges);
        System.out.println("rawUpslopeEdges=" + rawUpslope);
        System.out.println("maxRawRise=" + maxRawRise);
        System.out.println("filledUpslopeEdges=" + filledUpslope);
        System.out.println("maxFilledRise=" + maxFilledRise);
        System.out.println("longestRawAscentRun=" + longestAscent);
        System.out.println("status=" + (filledUpslope == 0 ? "PASS" : "FAIL"));
    }

    private static int ascentRun(HydrologyGrid grid, int start) {
        int run = 0, current = start;
        while (true) {
            int next = FlowDirectionSolver.downstream(grid, current);
            if (next < 0 || !grid.river(next)
                    || grid.elevation(next) <= grid.elevation(current) + 1e-9) return run;
            run++;
            current = next;
            if (run > grid.size() * grid.size()) return run;
        }
    }
}
