package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.CellGenerator;

/** Shared chunk sampling helper used by production hydrology entry points. */
final class HydrologyChunkSampling {
    private HydrologyChunkSampling() {
    }

    static Cell[] sample(CellGenerator generator, double horizontalScale, int chunkX, int chunkZ) {
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
