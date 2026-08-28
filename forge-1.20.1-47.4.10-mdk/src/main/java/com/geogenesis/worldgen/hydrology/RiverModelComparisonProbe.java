package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.river.RiverNetwork;
import com.geogenesis.worldgen.river.RiverSample;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;
import com.geogenesis.worldgen.terrain.TerrainParams;

/** 在同一坐标采样水文原型与旧 RTF 河网，仅输出对照指标，不改变游戏路径。 */
public final class RiverModelComparisonProbe {
    private RiverModelComparisonProbe() { }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        int size = args.length > 1 ? Integer.parseInt(args[1]) : 128;
        int spacing = args.length > 2 ? Integer.parseInt(args[2]) : 8;
        TerrainParams params = TerrainParams.defaults();
        CellGenerator generator = new CellGenerator(params, params.minY(), params.maxY());
        GeoGenesisTerrain terrain = new GeoGenesisTerrain(generator);
        terrain.seed(seed);
        HydrologyGrid grid = HydrologySimulator.run(generator, seed, -size / 2, -size / 2,
                size, 32, spacing, 128.0).grid();
        RiverNetwork legacy = terrain.riverNetwork();
        Comparison comparison = compare(grid, legacy, RiverWaterSolver.solve(grid));
        System.out.println("=== RiverModelComparisonProbe ===");
        System.out.println("seed=" + seed);
        System.out.println("size=" + size + " spacing=" + spacing + " coordinate=wu");
        System.out.println("hydrologyRiverCells=" + comparison.hydrologyCells);
        System.out.println("legacyRiverCells=" + comparison.legacyCells);
        System.out.println("bothRiverCells=" + comparison.bothCells);
        System.out.println("hydrologyOnly=" + comparison.hydrologyOnly);
        System.out.println("legacyOnly=" + comparison.legacyOnly);
        System.out.println("waterLevelDeltaSamples=" + comparison.waterDeltaSamples);
        System.out.println("meanWaterLevelDelta=" + comparison.meanWaterDelta());
        System.out.println("status=COMPARISON_ONLY");
    }

    private static Comparison compare(HydrologyGrid grid, RiverNetwork legacy,
                                      RiverWaterProfile[] water) {
        Comparison result = new Comparison();
        for (int i = 0; i < grid.size() * grid.size(); i++) {
            if (!grid.isCore(i)) continue;
            double x = grid.worldX(grid.x(i));
            double z = grid.worldZ(grid.z(i));
            boolean hydrology = grid.river(i);
            RiverSample sample = legacy.sampleRiver(x, z);
            boolean old = sample.inChannel();
            if (hydrology) result.hydrologyCells++;
            if (old) result.legacyCells++;
            if (hydrology && old) result.bothCells++;
            if (hydrology && !old) result.hydrologyOnly++;
            if (!hydrology && old) result.legacyOnly++;
            if (old && Double.isFinite(sample.waterSurfaceY())) {
                double hydrologySurface = water[i] == null ? 0.0 : water[i].surface();
                result.waterDeltaSum += Math.abs(sample.waterSurfaceY() - hydrologySurface);
                result.waterDeltaSamples++;
            }
        }
        return result;
    }

    private static final class Comparison {
        int hydrologyCells;
        int legacyCells;
        int bothCells;
        int hydrologyOnly;
        int legacyOnly;
        int waterDeltaSamples;
        double waterDeltaSum;

        double meanWaterDelta() {
            return waterDeltaSamples == 0 ? 0.0 : waterDeltaSum / waterDeltaSamples;
        }
    }
}
