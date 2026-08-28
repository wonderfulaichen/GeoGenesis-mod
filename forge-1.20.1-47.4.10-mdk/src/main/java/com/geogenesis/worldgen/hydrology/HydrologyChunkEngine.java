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
        Cell[] cells = new Cell[256];
        for (int lz = 0; lz < 16; lz++) for (int lx = 0; lx < 16; lx++) {
            cells[lx * 16 + lz] = generator.sample(
                    (chunkX * 16 + lx) / horizontalScale,
                    (chunkZ * 16 + lz) / horizontalScale);
        }
        return cells;
    }

    private static double[] heights(Cell[] cells) {
        double[] heights = new double[cells.length];
        for (int i = 0; i < cells.length; i++) heights[i] = cells[i].height;
        return heights;
    }
}
