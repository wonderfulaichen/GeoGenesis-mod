package com.geogenesis.client.preview;

import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;

/**
 * 大范围采样器（★ 2026-09-11，参考 FreeTerraForged 的 {@code TileGenerator.generateZoomed}）。
 *
 * <p><b>核心：固定采样数 + 步长缩放</b> —— 无论视野多大，采样数恒为 {@code gridW × gridH}，
 * 缩放只改变【采样步长】。因此<b>开销与视野无关</b>，这是能一眼看数万格的根本原因
 * （FTF 原话精神：固定 256² 采样缓冲，zoom 只改采样步长，开销与 zoom 无关）。</p>
 *
 * <p>与本项目精确模式（{@code chunk/} 引擎按 chunk 遍历、stride 上限 16）的差别：
 * 精确模式视野一大就会遍历海量 chunk（视口 30 万格时约 3.7 亿次迭代），
 * 故<b>大范围必须走本采样器</b>；精确模式则保留"预览 = 游戏"的完整管线。两者构成分级 LOD。</p>
 *
 * <p>管线：{@link GeoGenesisTerrain#sampleCellCoarse} —— 高度场 + 气候 + 降水；
 * 跳过侵蚀 tile 与河谷雕刻，也不查河流距离（否则大范围下会触发大量河网 region 构建）。</p>
 */
public final class LargeAreaSampler {

    private LargeAreaSampler() {}

    /** 默认采样网格边长（64×64 = 4096 点）。与视野无关，是成本的唯一决定项。 */
    public static final int DEFAULT_GRID = 64;

    /**
     * 一次采样结果（含视口信息，供上采样绘制使用）。
     *
     * @param cells       采样网格，<b>X 主序</b>（{@code cells[gx][gz]}）
     * @param blocksWide  本次覆盖的世界宽度（块）
     * @param blocksHigh  本次覆盖的世界高度（块）
     * @param costMs      本次采样耗时（诊断用）
     */
    public record Grid(Cell[][] cells, int originX, int originZ, int blocksWide, int blocksHigh,
                       int gridW, int gridH, long viewportId, long costMs) {

        /** 取网格点（越界返回 null）。 */
        public Cell at(int gx, int gz) {
            if (gx < 0 || gx >= gridW || gz < 0 || gz >= gridH) return null;
            return cells[gx][gz];
        }
    }

    /**
     * 执行采样。
     *
     * @param terrain     地形引擎
     * @param originX/Z   视口左上角世界坐标（块）
     * @param blocksWide/H 视口世界尺寸（块）
     * @param gridW/H     采样网格尺寸（<b>固定</b>，决定成本；与 blocksWide/H 无关）
     * @param viewportId  视口标识（用于调用方判断是否需要重采）
     */
    public static Grid sample(GeoGenesisTerrain terrain, int originX, int originZ,
                              int blocksWide, int blocksHigh, int gridW, int gridH, long viewportId) {
        Cell[][] grid = new Cell[Math.max(1, gridW)][Math.max(1, gridH)];
        if (terrain == null || gridW <= 0 || gridH <= 0) {
            return new Grid(grid, originX, originZ, blocksWide, blocksHigh, gridW, gridH, viewportId, 0L);
        }
        int gw = grid.length, gh = grid[0].length;
        // 步长 = 世界尺寸 / 网格数 —— 视野越大步长越大，采样数不变（FTF 同款做法）
        double stepX = blocksWide / (double) gw;
        double stepZ = blocksHigh / (double) gh;

        long t0 = System.nanoTime();
        for (int gx = 0; gx < gw; gx++) {
            int wx = originX + (int) Math.round(gx * stepX);
            for (int gz = 0; gz < gh; gz++) {
                int wz = originZ + (int) Math.round(gz * stepZ);
                grid[gx][gz] = terrain.sampleCellCoarse(wx, wz);
            }
        }
        long costMs = (System.nanoTime() - t0) / 1_000_000L;
        return new Grid(grid, originX, originZ, blocksWide, blocksHigh, gw, gh, viewportId, costMs);
    }
}
