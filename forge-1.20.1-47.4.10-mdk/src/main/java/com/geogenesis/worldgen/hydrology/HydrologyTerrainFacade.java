package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.CellGenerator;

/** 水文实验地形门面：构造带原始 Cell 的 chunk 计划，不写入正式 GeoGenesisTerrain。 */
public final class HydrologyTerrainFacade {
    private final CellGenerator generator;
    private final HydrologyExperimentEngine hydrology;
    private final double horizontalScale;

    public HydrologyTerrainFacade(CellGenerator generator) {
        this.generator = generator;
        this.horizontalScale = Math.max(0.01, generator.params().horizontalScale());
        this.hydrology = new HydrologyExperimentEngine(generator, 0L);
    }

    public Cell[] sampleChunk(long seed, int chunkX, int chunkZ) {
        hydrology.clear();
        hydrology.setSeed(seed);
        Cell[] cells = new Cell[256];
        for (int lz = 0; lz < 16; lz++) {
            for (int lx = 0; lx < 16; lx++) {
                cells[lx * 16 + lz] = generator.sample(
                        (chunkX * 16 + lx) / horizontalScale,
                        (chunkZ * 16 + lz) / horizontalScale);
            }
        }
        return cells;
    }
}
