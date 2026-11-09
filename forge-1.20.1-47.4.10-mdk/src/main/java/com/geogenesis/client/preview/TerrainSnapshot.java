package com.geogenesis.client.preview;

/**
 * <b>地形快照</b> —— 一份可被<b>任意视图</b>光栅化的地形数据（★ 2026-11-09 统一重构）。
 *
 * <h2>为什么需要它（用户指出的"分裂"根源）</h2>
 * <p>旧实现里两条视图各自持有数据：</p>
 * <pre>
 *   平面模式：TerrainQueue → CellCache（chunk → Cell[256]）
 *   沙盘模式：TerrainFieldData（自建扁平数组，独立采样）
 *   ⇒ 两套数据结构、两套采样时机、两套坐标系，切换时构图必然跳变。
 * </pre>
 * <p>现在统一为：<b>CellCache → TerrainSnapshot → 任意 rasterizer</b>。
 * 切换模式只换 rasterizer，<b>不重采、不重建快照</b>。</p>
 *
 * <h2>★ 零 MC 依赖（架构硬约束）</h2>
 * <p>用户 2026-11-09 明确：<i>「这就是我让你抽离 MC 做完全纯 Java 核心的原因，
 * 可以在外部计算后直接复用。」</i></p>
 * <p>本类及其下游 {@code SandboxGeometry} 必须<b>零 {@code net.minecraft.*} import</b>
 * ⇒ 预览、探针、离线出图、进世界加载<b>四处共用同一份实现</b>。</p>
 *
 * <h2>与 {@link TerrainFieldData} 的关系</h2>
 * <p>本类是 {@code TerrainFieldData} 的<b>零拷贝视图</b>：只持有引用，不复制数组。
 * 逐步过渡 —— 先把「视图层只认 Snapshot」这件事建立起来，
 * 之后再把 {@code TerrainFieldData} 的采样工厂方法搬进来、旧类删除。</p>
 *
 * <h2>坐标</h2>
 * <p><b>X 主序</b>：{@code index = gx * gridH + gz}（与 {@code TerrainFieldData} 一致）。</p>
 */
public final class TerrainSnapshot {

    // ---- 几何 ----
    /** 网格宽（X 方向细格数）。 */
    public final int gridW;
    /** 网格高（Z 方向细格数）。 */
    public final int gridH;
    /** 左上角世界坐标（方块）。 */
    public final int originX, originZ;
    /** 覆盖的世界尺寸（方块）。 */
    public final int blocksWide, blocksHigh;

    /**
     * 每细格代表的方块数 = {@code blocksWide / gridW}。
     *
     * <p>★ 坡度光照的分母基准必须用它（参考项目 {@code paintBlock} 的
     * {@code 2 × step × HEIGHT_STRIDE}）。用错会把坡度放大/缩小一个数量级
     * —— 实测量纲差 30~300 倍，导致整幅死平。</p>
     */
    public final double blocksPerCell;

    /** 快照标识（原点 + 尺寸 + 网格 + 缓存版本）；变了才需要重建。 */
    public final long key;

    // ---- 每格数据（X 主序，与 TerrainFieldData 共享引用）----
    /** 归一化高程 e ∈ [-1,1]（&lt;0 为水）。 */
    public final double[] e;
    /** 真实世界高度 Y（方块）—— 柱高与坡度光照的唯一来源。 */
    public final double[] height;
    public final double[] temperature;
    public final double[] humidity;
    public final double[] precipitation;
    public final double[] continent;
    public final double[] shape;
    public final double[] discharge;
    /** {@code WhittakerType.ordinal()}（10 项）。 */
    public final short[] biomeId;
    /** {@code BiomeClass.ordinal()}（35 项）—— 沙盘本色。 */
    public final short[] biomeClassId;
    /** {@code TerrainClass.ordinal()}。 */
    public final short[] terrainId;
    /** 额外离散 id（RIVER_TYPE / ROCK_TYPE / ROCK_LAYER），每格 4 个。 */
    public final short[] extraId;
    /** 雪盖。 */
    public final boolean[] snow;
    /** 粗层水面 Y；{@code NaN} = 无水。 */
    public final double[] waterY;

    private TerrainSnapshot(TerrainFieldData d) {
        this.gridW = d.gridW;
        this.gridH = d.gridH;
        this.originX = d.originX;
        this.originZ = d.originZ;
        this.blocksWide = d.blocksWide;
        this.blocksHigh = d.blocksHigh;
        this.blocksPerCell = (gridW > 0) ? (blocksWide / (double) gridW) : 1.0;
        this.key = d.viewportId;
        this.e = d.e;
        this.height = d.height;
        this.temperature = d.temperature;
        this.humidity = d.humidity;
        this.precipitation = d.precipitation;
        this.continent = d.continent;
        this.shape = d.shape;
        this.discharge = d.discharge;
        this.biomeId = d.biomeId;
        this.biomeClassId = d.biomeClassId;
        this.terrainId = d.terrainId;
        this.extraId = d.extraId;
        this.snow = d.snow;
        this.waterY = d.waterY;
    }

    /** 零拷贝包装（不复制任何数组）。 */
    public static TerrainSnapshot of(TerrainFieldData d) {
        return d == null ? null : new TerrainSnapshot(d);
    }

    /** 格数。 */
    public int cells() { return gridW * gridH; }

    /** 索引（X 主序）。越界返回 -1。 */
    public int index(int gx, int gz) {
        if (gx < 0 || gx >= gridW || gz < 0 || gz >= gridH) return -1;
        return gx * gridH + gz;
    }

    /** 细格 → 世界 X。 */
    public int worldX(int gx) {
        return originX + (int) Math.round(gx * blocksPerCell);
    }

    /** 细格 → 世界 Z。 */
    public int worldZ(int gz) {
        return originZ + (int) Math.round(gz * blocksPerCell);
    }

    /** 该格是否水面。 */
    public boolean isWater(int i) {
        return i >= 0 && e[i] < 0.0;
    }

    /**
     * 该细格是否有数据（已被采样）。
     *
     * <p>无 MC 依赖的判据：{@code height} 非 NaN 即视为有数据。
     * 未采区域在 {@code fromCellCache} 里保持默认 0 —— 但那些格子的
     * {@code bornAt} 为 0，渲染层据此跳过。</p>
     */
    public boolean hasCell(int gx, int gz) {
        int i = index(gx, gz);
        return i >= 0 && !Double.isNaN(height[i]);
    }

    /** 该格的地表 Y（水面取海平面，陆地取真实高度）。 */
    public double surfaceY(int i, double seaLevel) {
        return isWater(i) ? seaLevel : height[i];
    }

    /**
     * 坡度光照用的真实高度网格（水面取海平面 ⇒ 水面平，无假明暗）。
     *
     * <p>★ 必须用<b>真实 Y</b>，不能用归一化 {@code e} 或 {@code (h-base)/span}：
     * 后者的相邻差随网格密度变小，256 格时仅 0.0012，
     * 乘任何固定增益后亮度差 0.003 ⇒ 整幅均匀一片（实机与离线图均复现）。</p>
     */
    public double[] buildSurfaceYGrid(double seaLevel) {
        double[] y = new double[gridW * gridH];
        for (int i = 0; i < y.length; i++) {
            y[i] = isWater(i) ? seaLevel : height[i];
        }
        return y;
    }

    @Override
    public String toString() {
        return String.format(java.util.Locale.ROOT,
                "TerrainSnapshot[%dx%d @(%d,%d) %dx%d块 %.2f块/格 key=%d]",
                gridW, gridH, originX, originZ, blocksWide, blocksHigh, blocksPerCell, key);
    }
}
