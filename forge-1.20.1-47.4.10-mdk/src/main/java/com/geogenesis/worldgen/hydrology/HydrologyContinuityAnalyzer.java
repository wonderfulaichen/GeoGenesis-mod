package com.geogenesis.worldgen.hydrology;

import java.util.List;
import java.util.Map;
import com.geogenesis.worldgen.terrain.TerrainParams;
import com.geogenesis.worldgen.terrain.HeightCurve;

/** 检查相邻河流单元的雕刻、宽度和深度连续性。 */
public final class HydrologyContinuityAnalyzer {
    private HydrologyContinuityAnalyzer() { }

    public static HydrologyContinuityMetrics analyze(HydrologyGrid grid) {
        Map<Integer, HydrologyRiverSample> samples = HydrologyRiverAdapter.adapt(grid,
                new HeightCurve(TerrainParams.defaults(), -64, 320));
        List<HydrologyCarvedCell> carved = HydrologyCarver.carve(grid,
                new HeightCurve(TerrainParams.defaults(), -64, 320));
        Map<Integer, HydrologyCarvedCell> carvedByCell = carved.stream()
                .collect(java.util.stream.Collectors.toUnmodifiableMap(
                        HydrologyCarvedCell::cell, value -> value));
        int missing = 0, invalid = 0, erosionJumps = 0, widthJumps = 0, depthJumps = 0;
        double maxErosion = 0.0, maxWidth = 0.0, maxDepth = 0.0;
        long hash = 0xcbf29ce484222325L;
        for (Map.Entry<Integer, HydrologyRiverSample> entry : samples.entrySet()) {
            int cell = entry.getKey();
            HydrologyRiverSample sample = entry.getValue();
            HydrologyCarvedCell carvedCell = carvedByCell.get(cell);
            if (carvedCell == null) { missing++; continue; }
            if (!Double.isFinite(carvedCell.carvedHeight())
                    || carvedCell.carvedHeight() > carvedCell.originalHeight()
                    // ★ 世界 Y 语义：原始地形低于河床时保持原状（只下挖），不算非法
                    || (carvedCell.originalHeight() > sample.bedY()
                    && carvedCell.carvedHeight() < sample.bedY() - 1e-9)) invalid++;
            hash ^= Double.doubleToLongBits(carvedCell.carvedHeight()); hash *= 0x100000001b3L;
            int next = sample.downstream();
            if (next < 0 || !samples.containsKey(next)) continue;
            HydrologyRiverSample downstream = samples.get(next);
            HydrologyCarvedCell downstreamCarved = carvedByCell.get(next);
            if (downstreamCarved == null) { missing++; continue; }
            double erosionJump = Math.abs(carvedCell.erosion() - downstreamCarved.erosion());
            double widthJump = Math.abs(sample.width() - downstream.width());
            double depthJump = Math.abs(sample.depth() - downstream.depth());
            if (erosionJump > 1.0) erosionJumps++;
            if (widthJump > 4.0) widthJumps++;
            if (depthJump > 1.0) depthJumps++;
            maxErosion = Math.max(maxErosion, erosionJump);
            maxWidth = Math.max(maxWidth, widthJump);
            maxDepth = Math.max(maxDepth, depthJump);
        }
        return new HydrologyContinuityMetrics(samples.size(), missing, invalid,
                erosionJumps, maxErosion, widthJumps, maxWidth, depthJumps, maxDepth, hash);
    }
}
