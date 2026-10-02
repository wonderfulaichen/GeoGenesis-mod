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
        OPEN_AT_LEVEL,
        /** East outlet only; all other exterior faces are impermeable. */
        EAST_OPEN_AT_LEVEL,
        /**
         * 显式排水格掩码：仅掩码内的格子可向域外排水（水位高于 {@code boundaryLevel} 时），
         * 其余外边界不透水。
         *
         * <p><b>用途</b>：真实地形上的湖泊实验必须给水一个真实的去处。
         * 用 {@link #CLOSED} 会变成"浴缸"——水只能不断积高（已实测：整个窗口 100% 淹没、
         * 水深 46 块）。本类型把"低于海平面的海格"声明为出口，
         * 使陆地上真正<b>蓄不住</b>的水能流走，从而只有真实洼地才积水。</p>
         *
         * <p>配合 {@link #exteriorLevel} 时，每个出口格可用<b>各自</b>的外部水位，
         * 而不是一个全局常数——这是把"窗口边框"换成"按真实外部地形定水位"的关键。</p>
         */
        DRAIN_MASK
    }

    public final int nx, nz;
    public final double cellSize;
    /** 床面高程 z（block）。 */
    public final double[] bed;
    private final Boundary boundary;
    private final double boundaryLevel;
    /** DRAIN_MASK 下允许排水的格掩码（null 表示不适用）。按引用持有，调用方不得改写。 */
    private final boolean[] drainMask;
    /** DRAIN_MASK 下每格的外部水位（可为 null ⇒ 退回全局 boundaryLevel）。 */
    private final double[] exterior;

    private WaterGrid(int nx, int nz, double cellSize, double[] bed,
                      Boundary boundary, double boundaryLevel, boolean[] drainMask, double[] exterior) {
        this.nx = nx;
        this.nz = nz;
        this.cellSize = cellSize;
        this.bed = bed;
        this.boundary = boundary;
        this.boundaryLevel = boundaryLevel;
        this.drainMask = drainMask;
        this.exterior = exterior;
    }

    public static WaterGrid of(int nx, int nz, double cellSize, double[] bed, Boundary b, double level) {
        if (bed.length != nx * nz) throw new IllegalArgumentException("bed length != nx*nz");
        if (b == Boundary.DRAIN_MASK) throw new IllegalArgumentException("DRAIN_MASK requires an explicit mask");
        return new WaterGrid(nx, nz, cellSize, bed, b, level, null, null);
    }

    /** 带排水掩码的构造：仅 {@code drainMask} 为真的格可向域外排水。 */
    public static WaterGrid draining(int nx, int nz, double cellSize, double[] bed,
                                     boolean[] drainMask, double level) {
        if (bed.length != nx * nz || drainMask.length != nx * nz) {
            throw new IllegalArgumentException("bed/mask length != nx*nz");
        }
        return new WaterGrid(nx, nz, cellSize, bed, Boundary.DRAIN_MASK, level, drainMask.clone(), null);
    }

    /**
     * 逐格外部水位版本：出口格 {@code i} 的外部水位为 {@code exteriorLevel[i]}。
     * 这样窗口每条边（或每格）的水位由<b>窗口外的真实地形</b>决定，而非写死常数。
     */
    public static WaterGrid draining(int nx, int nz, double cellSize, double[] bed,
                                     boolean[] drainMask, double[] exteriorLevel) {
        if (bed.length != nx * nz || drainMask.length != nx * nz || exteriorLevel.length != nx * nz) {
            throw new IllegalArgumentException("bed/mask/exterior length != nx*nz");
        }
        return new WaterGrid(nx, nz, cellSize, bed, Boundary.DRAIN_MASK, 0, drainMask.clone(),
                exteriorLevel.clone());
    }

    /** 全平床面（用于静水保持/零输入判据）。 */
    public static WaterGrid flat(int nx, int nz, double cellSize, double z, Boundary b, double level) {
        double[] bed = new double[nx * nz];
        java.util.Arrays.fill(bed, z);
        return of(nx, nz, cellSize, bed, b, level);
    }

    public Boundary boundary() { return boundary; }
    public double boundaryLevel() { return boundaryLevel; }

    public int openSides(int i, int j) {
        if (boundary == Boundary.CLOSED) return 0;
        if (boundary == Boundary.DRAIN_MASK) {
            // 排水格视为四面临外（水可从这里离域）；其余格不透水。
            return drainMask[idx(i, j)] ? 4 : 0;
        }
        if (boundary == Boundary.EAST_OPEN_AT_LEVEL) return i == nx - 1 ? 1 : 0;
        return (i == 0 ? 1 : 0) + (i == nx - 1 ? 1 : 0)
                + (j == 0 ? 1 : 0) + (j == nz - 1 ? 1 : 0);
    }

    /** 该格是否为显式排水口（仅 DRAIN_MASK 有效）。 */
    public boolean isDrain(int i, int j) {
        return boundary == Boundary.DRAIN_MASK && drainMask[idx(i, j)];
    }

    /**
     * 该格排水口的外部水位：优先使用逐格值，未提供则退回全局 {@code boundaryLevel}。
     * 只有排水格有意义。
     */
    public double exteriorLevelAt(int i, int j) {
        if (boundary != Boundary.DRAIN_MASK) return boundaryLevel;
        int k = idx(i, j);
        if (!drainMask[k]) return boundaryLevel;
        return exterior == null ? boundaryLevel : exterior[k];
    }

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
