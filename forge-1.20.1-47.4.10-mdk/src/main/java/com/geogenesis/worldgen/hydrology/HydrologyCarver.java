package com.geogenesis.worldgen.hydrology;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import com.geogenesis.worldgen.terrain.HeightCurve;

/** 纯 Java 河谷雕刻实验器：流量控制横断面，严格只下挖并保持连续过渡。 */
public final class HydrologyCarver {
    private HydrologyCarver() { }

    public static List<HydrologyCarvedCell> carve(HydrologyGrid grid) {
        return carve(grid, new HeightCurve(
                com.geogenesis.worldgen.terrain.TerrainParams.defaults(), -64, 320));
    }

    public static List<HydrologyCarvedCell> carve(HydrologyGrid grid, HeightCurve curve) {
        Map<Integer, HydrologyRiverSample> samples = HydrologyRiverAdapter.adapt(grid, curve);
        List<HydrologyCarvedCell> result = new ArrayList<>();
        for (Map.Entry<Integer, HydrologyRiverSample> entry : samples.entrySet()) {
            int cell = entry.getKey();
            HydrologyRiverSample sample = entry.getValue();
            double original = grid.elevation(cell);
            double target = targetHeight(grid, sample, original);
            result.add(new HydrologyCarvedCell(cell, original, target,
                    Math.max(0.0, original - target), sample));
        }
        return List.copyOf(result);
    }

    private static double targetHeight(HydrologyGrid grid, HydrologyRiverSample sample,
                                       double original) {
        // ★ 世界 Y 语义（与 HydrologyBlockCarver 对齐）：水面 = 填洼地形 − 1，
        //   河道中心挖到水下 0.6；只下挖。
        double waterline = sample.surfaceY() - 0.6;
        if (original > sample.surfaceY()) {
            return Math.max(waterline, original - (original - waterline));
        }
        return original;
    }
}
