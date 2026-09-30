package com.geogenesis.worldgen.hydrology.model;

/**
 * 有限域床面 + 边界条件（P1 原型用；零 MC 依赖）。
 *
 * <h2>边界是显式的，不是"窗口边框的副产品"</h2>
 * <p>旧实现用 priority-flood 的<b>窗口边框</b>当隐式排水口，于是"水去哪儿"取决于
 * 查询窗口的切割位置（plan §2「世界边界」行）。本原型要求边界条件必须被<b>显式声明</b>：
 * 要么是不透水壁（CLOSED）、要么是给定水位的自由出流（OPEN）。</p>
 *
 * <p>只有两种边界 ⇒ 有限域内的所有水量去向都可账（P1 的"水量账本"由此闭合）。</p>
 */
public final class WaterGrid {

    /** 边界类型。 */
    public enum Boundary {
        /** 不透水壁：跨边通量恒为 0（用于"封闭碗"的解析对照）。 */
        CLOSED,
        /** 自由出流：当边界格的水面高于 {@code level} 时按通量流出域外（记为 boundaryLoss）。 */
        OPEN_AT_LEVEL
    }

    public final int nx, nz;
    public final double cellSize;
    /** 床面高程 z（block）。 */
    public final double[] bed;
    private final Boundary boundary;
    private final double boundaryLevel;

    private WaterGrid(int nx, int nz, double cellSize, double[] bed,
                      Boundary boundary, double boundaryLevel) {
        this.nx = nx;
        this.nz = nz;
        this.cellSize = cellSize;
        this.bed = bed;
        this.boundary = boundary;
        this.boundaryLevel = boundaryLevel;
    }

    public static WaterGrid of(int nx, int nz, double cellSize, double[] bed, Boundary b, double level) {
        if (bed.length != nx * nz) throw new IllegalArgumentException("bed length != nx*nz");
        return new WaterGrid(nx, nz, cellSize, bed, b, level);
    }

    /** 全平床面（用于静水保持/零输入判据）。 */
    public static WaterGrid flat(int nx, int nz, double cellSize, double z, Boundary b, double level) {
        double[] bed = new double[nx * nz];
        java.util.Arrays.fill(bed, z);
        return of(nx, nz, cellSize, bed, b, level);
    }

    public Boundary boundary() { return boundary; }
    public double boundaryLevel() { return boundaryLevel; }

    /** 格数 X（访问器形式，与其余 API 一致）。 */
    public int nx() { return nx; }

    /** 格数 Z。 */
    public int nz() { return nz; }

    /** 格边长（block）。 */
    public double cellSize() { return cellSize; }

    public int idx(int i, int j) { return i * nz + j; }
    public int ix(int k) { return k / nz; }
    public int iz(int k) { return k % nz; }
    public boolean inside(int i, int j) { return i >= 0 && i < nx && j >= 0 && j < nz; }
    public boolean isBorder(int i, int j) { return i == 0 || j == 0 || i == nx - 1 || j == nz - 1; }
    public int cells() { return nx * nz; }
    public double cellArea() { return cellSize * cellSize; }
    public double edgeLength() { return cellSize; }
}
