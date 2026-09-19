package com.geogenesis.worldgen.hydrology;

import com.geogenesis.worldgen.terrain.Cell;

import java.util.List;

/**
 * 一次完整水文 chunk 实验计算的只读结果。
 *
 * <p>★ 2026-09-19 清理：原 {@code columnAt(blockX, blockZ)}（线性查找单列）已删除 ——
 * 全仓 {@code search_content} 零调用。</p>
 */
public record HydrologyChunkResult(Cell[] originalCells,
                                   List<HydrologyBlockCarvedColumn> carvedColumns,
                                   int waterColumns, double maxErosion, long hash) {
}
