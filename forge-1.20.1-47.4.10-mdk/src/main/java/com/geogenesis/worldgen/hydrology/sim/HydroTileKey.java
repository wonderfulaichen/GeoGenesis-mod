package com.geogenesis.worldgen.hydrology.sim;

import java.util.Objects;

/**
 * 水文 tile 的身份：{@code (seed, tx, tz, lod)}。
 *
 * <p>tile 是<b>无限世界</b>的求解单元。它由全球对齐的水文格坐标系定义，与 Minecraft
 * 的 chunk / 旧 region 都没有绑定关系 —— 这样"同一世界点在任何 tile 中都是同一格"
 * 才成立，跨 tile 结果才可能逐位一致。</p>
 *
 * <p>坐标一律 {@code floorDiv} 语义：负坐标的 tile 编号是连续的（{@code -1} 紧邻 {@code 0}），
 * 与 Java 截断除法的行为无关。</p>
 */
public record HydroTileKey(long seed, int tx, int tz, int lod) {

    public static HydroTileKey of(long seed, int tx, int tz, int lod) {
        return new HydroTileKey(seed, tx, tz, lod);
    }

    /** 包含全球水文格 {@code (gx, gz)} 的 tile 编号（floorDiv，负坐标正确）。 */
    public static int tileOfCell(int g) {
        return Math.floorDiv(g, HydroContract.TILE_CORE_CELLS);
    }

    /** 该 tile 的 core 起点（全球格坐标，含）。 */
    public int coreMinX() {
        return tx * HydroContract.TILE_CORE_CELLS;
    }

    public int coreMinZ() {
        return tz * HydroContract.TILE_CORE_CELLS;
    }

    /** 该 tile 的 window 起点（core − halo，全球格坐标，含）。 */
    public int winMinX() {
        return coreMinX() - HydroContract.TILE_HALO_CELLS;
    }

    public int winMinZ() {
        return coreMinZ() - HydroContract.TILE_HALO_CELLS;
    }

    /** window 边长（格）：core + 2·halo。 */
    public static int windowCells() {
        return HydroContract.TILE_CORE_CELLS + 2 * HydroContract.TILE_HALO_CELLS;
    }

    /** 端口身份：边界格（全球格坐标）+ 方向 ordinal。方向 ordinal 是契约的一部分。 */
    public record PortId(int gx, int gz, int dir) {
        @Override
        public String toString() {
            return "port[" + gx + "," + gz + " " + HydroContract.DIR_NAME[dir] + "]";
        }
    }

    /** 与该 tile 共享边界的 4 个正邻 tile（固定方向序：E, S, W, N —— 与 8 邻序无关，专用于 tile 级递归）。 */
    public static final int[] TILE_DX = { 1, 0, -1, 0 };
    public static final int[] TILE_DZ = { 0, 1, 0, -1 };

    public HydroTileKey neighbour(int ordinal) {
        return new HydroTileKey(seed, tx + TILE_DX[ordinal], tz + TILE_DZ[ordinal], lod);
    }

    /** 稳定哈希（缓存键用；不使用 Objects.hash，避免依赖实现细节）。 */
    public long stableKey() {
        long h = HydroContract.cacheSalt(seed, 0);
        h = h * 1000003L + tx;
        h = h * 1000003L + tz;
        h = h * 1000003L + lod;
        return h;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof HydroTileKey k)) return false;
        return seed == k.seed && tx == k.tx && tz == k.tz && lod == k.lod;
    }

    @Override
    public int hashCode() {
        return Objects.hash(seed, tx, tz, lod);
    }

    @Override
    public String toString() {
        return "tile(" + tx + "," + tz + ")@lod" + lod;
    }
}
