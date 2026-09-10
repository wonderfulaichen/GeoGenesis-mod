package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.hydrology.riverline.RiverLineNetwork;
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
     * 只读暴露河线网络 —— 供"离水多远"类群系规则（河流绿洲等）在<b>完整管线与群系
     * 快速路径</b>两条采样路径上取到同一份数据，避免规则只在预览生效。
     * <b>不改变任何水文生产逻辑。</b>
     */
    public RiverLineNetwork riverNetwork() {
        return hydrology.network();
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
