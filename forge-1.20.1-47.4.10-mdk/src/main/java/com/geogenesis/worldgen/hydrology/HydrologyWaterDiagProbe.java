package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.HeightCurve;
import com.geogenesis.worldgen.terrain.TerrainParams;

/** 诊断水面/河床世界 Y 与原始地形的关系，定位灌水失败原因。 */
public final class HydrologyWaterDiagProbe {
    private HydrologyWaterDiagProbe() { }

    public static void main(String[] args) {
        long seed = args.length > 0 ? Long.parseLong(args[0]) : 12345L;
        TerrainParams params = TerrainParams.defaults();
        HeightCurve curve = new HeightCurve(params, params.minY(), params.maxY());
        CellGenerator terrain = new CellGenerator(params, params.minY(), params.maxY());
        terrain.seed(seed);
        HydrologyGrid grid = HydrologySimulator.run(terrain, seed, -512, -512,
                128, 32, 8, 128.0).grid();
        var samples = HydrologyRiverAdapter.adapt(grid, curve);
        int count = 0, aboveGround = 0, belowGround = 0;
        double minSurface = Double.POSITIVE_INFINITY, maxSurface = Double.NEGATIVE_INFINITY;
        double minBed = Double.POSITIVE_INFINITY, maxBed = Double.NEGATIVE_INFINITY;
        for (var entry : samples.entrySet()) {
            int i = entry.getKey();
            HydrologyRiverSample sample = entry.getValue();
            double groundY = curve.heightFromE(grid.elevation(i));
            count++;
            minSurface = Math.min(minSurface, sample.surfaceY());
            maxSurface = Math.max(maxSurface, sample.surfaceY());
            minBed = Math.min(minBed, sample.bedY());
            maxBed = Math.max(maxBed, sample.bedY());
            if (sample.surfaceY() > groundY) aboveGround++;
            else belowGround++;
        }
        System.out.println("=== HydrologyWaterDiagProbe ===");
        System.out.println("seed=" + seed);
        System.out.println("samples=" + count);
        System.out.println("surfaceAboveGround=" + aboveGround);
        System.out.println("surfaceBelowOrEqual=" + belowGround);
        System.out.println("surfaceYRange=[" + minSurface + ", " + maxSurface + "]");
        System.out.println("bedYRange=[" + minBed + ", " + maxBed + "]");
        System.out.println("seaLevel=" + curve.heightFromE(0.0));
    }
}
