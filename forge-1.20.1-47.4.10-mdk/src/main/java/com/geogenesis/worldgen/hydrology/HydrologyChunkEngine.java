package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.CellGenerator;

/** 完整水文 chunk 实验管线：原始 Cell → 雕刻计划 → 水体统计。 */
public final class HydrologyChunkEngine {
    private final CellGenerator generator;
    private final HydrologyExperimentEngine hydrology;
    private final double horizontalScale;

    public HydrologyChunkEngine(CellGenerator generator, long seed) {
        this.generator = generator;
        this.horizontalScale = Math.max(0.01, generator.params().horizontalScale());
        this.hydrology = new HydrologyExperimentEngine(generator, seed);
    }

    /**
    /**
     * 到最近水体的距离（wu）——河流绿洲输入，走新核心（与雕刻同一 solver/缓存，
     * 边际成本≈0）。2026-09-29 起替代 riverNetwork().distanceToWater。
     */
    public double distanceToWaterWu(double wuX, double wuZ) {
        return hydrology.distanceToWaterWu(wuX, wuZ);
    }

    public void setSeed(long seed) {
        hydrology.setSeed(seed);
    }

    public HydrologyChunkResult calculate(int chunkX, int chunkZ) {
        Cell[] cells = sampleOriginal(chunkX, chunkZ);
        double[] heights = heights(cells);
        var columns = HydrologyBlockCarver.carveChunk(hydrology, chunkX, chunkZ,
                horizontalScale, heights);
        int water = 0;
        double maxErosion = 0.0;
        long hash = 0xcbf29ce484222325L;
        for (HydrologyBlockCarvedColumn column : columns) {
            if (column.fillWater()) water++;
            maxErosion = Math.max(maxErosion, column.erosion());
            hash ^= Double.doubleToLongBits(column.carvedGroundY());
            hash *= 0x100000001b3L;
        }
        return new HydrologyChunkResult(cells, columns, water, maxErosion, hash);
    }

    private Cell[] sampleOriginal(int chunkX, int chunkZ) {
        return HydrologyChunkSampling.sample(generator, horizontalScale, chunkX, chunkZ);
    }

    private static double[] heights(Cell[] cells) {
        double[] heights = new double[cells.length];
        for (int i = 0; i < cells.length; i++) heights[i] = cells[i].height;
        return heights;
    }
}
