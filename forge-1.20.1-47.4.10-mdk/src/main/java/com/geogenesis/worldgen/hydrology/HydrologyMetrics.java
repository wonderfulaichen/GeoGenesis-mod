package com.geogenesis.worldgen.hydrology;

/** 水文拓扑验收指标，所有统计限定在核心区，避免把 halo 误计入结果。 */
public record HydrologyMetrics(int coreCells, int riverCells, int riverSources,
                               int confluences, int oceanOutlets, int lakeOutlets,
                               int boundaryOutlets, int landOutlets,
                               int monotonicViolations, int maxRiverChain,
                               double maxRiverFlow, long hash) {
}
