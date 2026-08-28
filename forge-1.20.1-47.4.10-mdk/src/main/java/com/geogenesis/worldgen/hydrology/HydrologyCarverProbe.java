package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.TerrainParams;
import com.geogenesis.worldgen.terrain.HeightCurve;

/** 验证水文雕刻只下挖、结果有限且不把地面切到河床以下。 */
public final class HydrologyCarverProbe {
    private HydrologyCarverProbe() { }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        TerrainParams params = TerrainParams.defaults();
        CellGenerator terrain = new CellGenerator(params, params.minY(), params.maxY());
        terrain.seed(seed);
        HydrologyGrid grid = HydrologySimulator.run(terrain, seed, -512, -512,
                128, 32, 8, 128.0).grid();
        int count = 0, invalid = 0, uplift = 0;
        double maxErosion = 0.0;
        for (HydrologyCarvedCell cell : HydrologyCarver.carve(grid,
                new HeightCurve(params, params.minY(), params.maxY()))) {
            count++;
            if (!Double.isFinite(cell.carvedHeight()) || cell.carvedHeight() > cell.originalHeight()
                    || (cell.originalHeight() > cell.sample().bedY()
                    && cell.carvedHeight() < cell.sample().bedY() - 1e-9)) invalid++;
            if (cell.carvedHeight() > cell.originalHeight() + 1e-9) uplift++;
            maxErosion = Math.max(maxErosion, cell.erosion());
        }
        System.out.println("=== HydrologyCarverProbe ===");
        System.out.println("seed=" + seed);
        System.out.println("carvedCells=" + count);
        System.out.println("invalid=" + invalid);
        System.out.println("uplift=" + uplift);
        System.out.println("maxErosion=" + maxErosion);
        System.out.println("status=" + (invalid + uplift == 0 ? "PASS" : "FAIL"));
    }
}
