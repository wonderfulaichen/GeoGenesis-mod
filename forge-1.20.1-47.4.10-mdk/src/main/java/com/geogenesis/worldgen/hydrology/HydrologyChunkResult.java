package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.terrain.Cell;

import java.util.List;

/** 一次完整水文 chunk 实验计算的只读结果。 */
public record HydrologyChunkResult(Cell[] originalCells,
                                   List<HydrologyBlockCarvedColumn> carvedColumns,
                                   int waterColumns, double maxErosion, long hash) {
    public HydrologyBlockCarvedColumn columnAt(int blockX, int blockZ) {
        for (HydrologyBlockCarvedColumn column : carvedColumns) {
            if (column.blockX() == blockX && column.blockZ() == blockZ) return column;
        }
        return null;
    }
}
