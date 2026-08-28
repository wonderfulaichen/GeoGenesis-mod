package com.geogenesis.worldgen.hydrology;

/** 水文阶段输出；数组索引语义与 {@link HydrologyGrid} 一致。 */
public record HydrologyResult(HydrologyGrid grid, int riverCells, int outletCells,
                              int oceanOutlets, int lakeCells, double maxFlow,
                              int flowCycles, int boundaryRiverCells, long hash) {
}
