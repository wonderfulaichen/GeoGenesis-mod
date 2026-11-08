package com.geogenesis.client.preview;

import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;

/**
 * 全屏地形预览的<b>扁平采样数据</b>（★ 2026-11-08，S2）。
 *
 * <h2>为什么不继续用 {@code Cell[][]}</h2>
 * <p>S0 实测（{@code runPreviewSampleCostProbe}，窗口 3072²）：
 * {@code sampleCellCoarse} 每点 <b>9.0~13.3 µs</b>，64×64 一屏要 <b>54.5 ms</b>，
 * 直接超过 2 帧预算。根因是 {@code Cell} 是<b>胖对象</b>——
 * 20+ 个字段，还挂着 {@code Climate} / {@code OceanFeatures.FeatureResult} /
 * {@code LandFeatures.FeatureResult} / {@code double[] typeWeights} 四个引用。
 * 每个格点都 new 一个，等于每个格点付一次分配 + 四个子对象。</p>
 *
 * <p>但绘制只需要<b>几个标量</b>（见 {@code GeoPalette.color}）：
 * 高程 e、温度、湿度、降水、大陆性、起伏 shape、河网流量、群系 id、地形类型 id。
 * ⇒ 存<b>扁平 double[] / short[]</b>，一个格点 8 个 double + 2 个 short ≈ 68 字节，
 * 且无子对象分配。</p>
 *
 * <h2>为什么存原始量而不是已上色像素</h2>
 * <p>切换图层、换色带、改群系颜色都<b>不该重采</b>——重采一次 54 ms，改个色带就卡一下
 * 不可接受。存原始量则只需重画。(yardsite {@code SiteMapSampler} 同款教训：
 * 它把 light 编码进颜色高字节，颜色现算，就是为了改群系色不用重采。)</p>
 *
 * <h2>内存</h2>
 * <p>64×64 = 4,096 点 → 约 280 KB；128² = 16,384 点 → 约 1.1 MB。
 * 相比 {@code Cell[][]} 的 ~4 MB（含子对象）省一个量级。</p>
 *
 * <h2>坐标</h2>
 * <p><b>X 主序</b>：索引 {@code i = gx * gridH + gz}。与 {@code LargeAreaSampler} 一致，
 * 便于两者对拍。</p>
 */
public final class TerrainFieldData {

    /** 网格宽（X 方向格数）。 */
    public final int gridW;
    /** 网格高（Z 方向格数）。 */
    public final int gridH;

    /** 视口左上角世界坐标（块）。 */
    public final int originX;
    public final int originZ;
    /** 视口覆盖的世界尺寸（块）。 */
    public final int blocksWide;
    public final int blocksHigh;

    /** 视口标识（原点 + 尺寸 + 种子 + 参数）—— 变了才需要重采。 */
    public final long viewportId;

    /** 本次采样耗时（诊断）。采样完成时回填，故非 final。 */
    public long costMs;

    // ---- 每格一个标量（X 主序）----
    /** 归一化高程 e ∈ [-1,1]：高程图层、坡度着色、水面判定都用它。 */
    public final double[] e;
    public final double[] temperature;
    public final double[] humidity;
    public final double[] precipitation;
    public final double[] continent;
    /** {@code Cell.shape}，供 RELIEF 图层。 */
    public final double[] shape;
    /** 河网流量（液滴汇聚），供 RIVER_NETWORK 图层。 */
    public final double[] discharge;
    /** {@code WhittakerType.ordinal()}，供 BIOME 图层。 */
    public final short[] biomeId;
    /** {@code TerrainClass.ordinal()}，供 TERRAIN_TYPE 图层。 */
    public final short[] terrainId;
    /**
     * 额外离散 id（★ 2026-11-08 补）：RIVER_TYPE / ROCK_TYPE / ROCK_LAYER / VEIN_MAP
     * 这几个图层要读 {@code Cell} 的对应字段，不存就会出现"图层没接上数据、整幅同色"。
     * 每个格点 4 个 short。
     */
    public final short[] extraId;
    /**
     * 真实世界高度 Y（方块）。
     *
     * <p>★ 2026-11-08 补：沙盘柱高必须用它而不是 {@code e}。
     * yardsite 的柱高是 {@code (surface - base) / reliefSpan}，
     * {@code surface} 就是真实 Y。用 {@code e}∈[-1,1] 直接乘系数会丢掉
     * 高度链路的真实比例，实测“柱子又细又高像草丛”。</p>
     */
    public final double[] height;

    /**
     * 雪盖（★ 2026-11-08 P0-1 补）：沙盘着色要区分「雪白山项」，
     * {@code Cell.isSnow} 是雪线逻辑的产物，平面色带里没有这个维度。
     */
    public final boolean[] snow;

    /**
     * 每格<b>第一次被采到</b>的时刻（毫秒）；0 = 还没采到。
     *
     * <p>★ 2026-11-08 P0-3 补：沙盘「从中心向外长出来」动画的时间戳
     * （yardsite {@code SiteMapSampler.View.drawnAt} 第 265-273 行同策）。
     * 采样线程在 {@link #put} 时写；柱子的出生时刻取其左上角细格。
     * 未采到的柱子（bornAt==0）不画 —— 这正是中心向外生长的来源。</p>
     */
    public final long[] bornAt;

    /**
     * 粗层水文<b>水面 Y</b>（★ 2026-11-08 补）；{@code NaN} = 该格无水面。
     *
     * <p>来自 {@code SandboxWaterLookup}（粗层 {@code buildCoarse}，D8、无侵蚀 tile）。
     * 实测 635ms/冷 tract、100% 有效水位、16.2% 格水面高于地形
     * ⇒ 沙盘用它画「河道/湖面」的水面。</p>
     *
     * <p><b>精度</b>：粗层 step=4 且不做侵蚀雕刻 ⇒ 这是"这一片是不是水面"的骨架级判断，
     * <b>不等于</b>游戏落块的逐格水位。沙盘只用于看形态。</p>
     */
    public final double[] waterY;

    private TerrainFieldData(int gridW, int gridH, int originX, int originZ,
                             int blocksWide, int blocksHigh, long viewportId, long costMs) {
        this.gridW = gridW;
        this.gridH = gridH;
        this.originX = originX;
        this.originZ = originZ;
        this.blocksWide = blocksWide;
        this.blocksHigh = blocksHigh;
        this.viewportId = viewportId;
        this.costMs = costMs;
        int n = gridW * gridH;
        this.e = new double[n];
        this.temperature = new double[n];
        this.humidity = new double[n];
        this.precipitation = new double[n];
        this.continent = new double[n];
        this.shape = new double[n];
        this.discharge = new double[n];
        this.biomeId = new short[n];
        this.terrainId = new short[n];
        this.extraId = new short[n * 4];
        this.height = new double[n];
        this.snow = new boolean[n];
        this.bornAt = new long[n];
        this.waterY = new double[n];
        java.util.Arrays.fill(this.waterY, Double.NaN);
    }

    /** 扁平索引（越界返回 -1）。 */
    public int index(int gx, int gz) {
        if (gx < 0 || gx >= gridW || gz < 0 || gz >= gridH) return -1;
        return gx * gridH + gz;
    }

    /**
     * 把一个格点还原成 {@link Cell}，供 {@code GeoPalette.color} 复用现有上色逻辑。
     *
     * <p>只填绘制真正用到的字段，其余保持 {@code Cell} 的默认值 ——
     * 本方法的返回对象<b>只允许</b>传给 {@code GeoPalette.color}，
     * <b>禁止</b>拿去做分类、写回、或任何依赖完整 Cell 的逻辑。</p>
     */
    public Cell toDisplayCell(int gx, int gz, Cell reuse) {
        int i = index(gx, gz);
        if (i < 0) return null;
        Cell c = reuse != null ? reuse : new Cell();
        c.e = e[i];
        c.eClimate = e[i];
        c.height = height[i];
        c.temperature = temperature[i];
        c.humidity = humidity[i];
        c.precipitation = precipitation[i];
        c.continent = continent[i];
        c.continentNoise = continent[i];
        c.shape = shape[i];
        c.riverNetDischarge = discharge[i];
        c.biomeType = biomeOf(biomeId[i]);
        c.terrainType = terrainOf(terrainId[i]);
        // ★ 2026-11-08：补齐离散图层要读的字段
        //   （否则 RIVER_TYPE / ROCK_TYPE / ROCK_LAYER 会整幅同色 —— "没接上数据"）
        c.riverType = (byte) extraId[i * 4];
        c.rockTypeId = extraId[i * 4 + 1];
        c.rockLayer = extraId[i * 4 + 2];
        return c;
    }

    private static com.geogenesis.worldgen.climate.WhittakerType biomeOf(short id) {
        var v = com.geogenesis.worldgen.climate.WhittakerType.values();
        return (id >= 0 && id < v.length) ? v[id] : v[0];
    }

    private static com.geogenesis.worldgen.terrain.TerrainClass terrainOf(short id) {
        var v = com.geogenesis.worldgen.terrain.TerrainClass.values();
        return (id >= 0 && id < v.length) ? v[id] : com.geogenesis.worldgen.terrain.TerrainClass.OCEAN;
    }

    /**
     * 采样整个视口（<b>同步</b>，一次性）。
     *
     * <p>⚠ 这是「一次跑完」的版本，64×64 约 40~55 ms（S0 实测）。
     * 需要分帧的场合请用 {@link ProgressiveSampler}。</p>
     */
    /**
     * 采样整个视口（<b>同步</b>，一次性），可选用<b>水文管线</b>。
     *
     * <p>⚠ 这是「一次跑完」的版本，64×64 约 40~55 ms（S0 实测）。
     * 需要分帧的场合请用 {@link ProgressiveSampler}。</p>
     *
     * @param hydro true = 用 {@code sampleCell}（带河谷雕刻与湖河数据，但每个新水文
     *              region 需秒级）；false = {@code sampleCellCoarse}（廉价、无水文）
     */
    public static TerrainFieldData sample(GeoGenesisTerrain terrain,
                                          int originX, int originZ,
                                          int blocksWide, int blocksHigh,
                                          int gridW, int gridH, long viewportId, boolean hydro) {
        TerrainFieldData d = new TerrainFieldData(gridW, gridH, originX, originZ,
                blocksWide, blocksHigh, viewportId, 0);
        long t0 = System.nanoTime();
        double stepX = blocksWide / (double) gridW;
        double stepZ = blocksHigh / (double) gridH;
        for (int gx = 0; gx < gridW; gx++) {
            int wx = originX + (int) Math.round(gx * stepX);
            for (int gz = 0; gz < gridH; gz++) {
                int wz = originZ + (int) Math.round(gz * stepZ);
                Cell c = hydro ? terrain.sampleCell(wx, wz) : terrain.sampleCellCoarse(wx, wz);
                d.put(gx, gz, c);
            }
        }
        d.costMs = (System.nanoTime() - t0) / 1_000_000L;
        return d;
    }

    /** 兼容重载：<b>不走</b>水文管线。 */
    public static TerrainFieldData sample(GeoGenesisTerrain terrain,
                                          int originX, int originZ,
                                          int blocksWide, int blocksHigh,
                                          int gridW, int gridH, long viewportId) {
        return sample(terrain, originX, originZ, blocksWide, blocksHigh,
                gridW, gridH, viewportId, false);
    }

    /** 写入一个格点（采样与渐进填充共用）。 */
    void put(int gx, int gz, Cell c) {
        int i = index(gx, gz);
        if (i < 0 || c == null) return;
        e[i] = c.e;
        temperature[i] = c.temperature;
        humidity[i] = c.humidity;
        precipitation[i] = c.precipitation;
        continent[i] = c.continent;
        shape[i] = c.shape;
        discharge[i] = c.riverNetDischarge;
        biomeId[i] = (short) (c.biomeType == null ? 0 : c.biomeType.ordinal());
        terrainId[i] = (short) (c.terrainType == null ? 0 : c.terrainType.ordinal());
        extraId[i * 4] = (short) c.riverType;
        extraId[i * 4 + 1] = (short) c.rockTypeId;
        extraId[i * 4 + 2] = (short) c.rockLayer;
        height[i] = c.height;
        snow[i] = c.isSnow;
        if (bornAt[i] == 0L) bornAt[i] = System.currentTimeMillis();
    }

    /** 写入粗层水位（NaN = 无水面）。 */
    void putWater(int gx, int gz, double waterYv) {
        int i = index(gx, gz);
        if (i < 0) return;
        waterY[i] = waterYv;
    }

    /** 写入粗层水位的<b>公开</b>入口（探针/离线导出用）。 */
    public void putWaterPublic(int gx, int gz, double waterYv) {
        putWater(gx, gz, waterYv);
    }

    /** 拷贝自另一个同时刻的 {@link TerrainFieldData} 的单元格（渐进补丁用）。 */
    void copyCellFrom(TerrainFieldData src, int gx, int gz) {
        int i = index(gx, gz);
        int j = src.index(gx, gz);
        if (i < 0 || j < 0) return;
        e[i] = src.e[j];
        temperature[i] = src.temperature[j];
        humidity[i] = src.humidity[j];
        precipitation[i] = src.precipitation[j];
        continent[i] = src.continent[j];
        shape[i] = src.shape[j];
        discharge[i] = src.discharge[j];
        biomeId[i] = src.biomeId[j];
        terrainId[i] = src.terrainId[j];
        System.arraycopy(src.extraId, j * 4, extraId, i * 4, 4);
        height[i] = src.height[j];
        snow[i] = src.snow[j];
        bornAt[i] = src.bornAt[j];
        waterY[i] = src.waterY[j];
    }

    /**
     * 生成一个内容为空的实例（所有值为 0），供渐进采样先铺底图。
     *
     * <p>0 值在语义上是"海平面、无数据"，上色后会是一片均匀色块 ——
     * 这正是我们要的"先给个底，再逐块填真值"。</p>
     */
    static TerrainFieldData blank(int gridW, int gridH, int originX, int originZ,
                                  int blocksWide, int blocksHigh, long viewportId) {
        return new TerrainFieldData(gridW, gridH, originX, originZ,
                blocksWide, blocksHigh, viewportId, 0);
    }

    /** 一份独立的可写副本（{@link ProgressiveSampler} 每轮补丁写它）。 */
    TerrainFieldData copyForPatch(long newViewportId) {
        TerrainFieldData d = new TerrainFieldData(gridW, gridH, originX, originZ,
                blocksWide, blocksHigh, newViewportId, costMs);
        System.arraycopy(e, 0, d.e, 0, e.length);
        System.arraycopy(temperature, 0, d.temperature, 0, temperature.length);
        System.arraycopy(humidity, 0, d.humidity, 0, humidity.length);
        System.arraycopy(precipitation, 0, d.precipitation, 0, precipitation.length);
        System.arraycopy(continent, 0, d.continent, 0, continent.length);
        System.arraycopy(shape, 0, d.shape, 0, shape.length);
        System.arraycopy(discharge, 0, d.discharge, 0, discharge.length);
        System.arraycopy(biomeId, 0, d.biomeId, 0, biomeId.length);
        System.arraycopy(terrainId, 0, d.terrainId, 0, terrainId.length);
        System.arraycopy(extraId, 0, d.extraId, 0, extraId.length);
        System.arraycopy(height, 0, d.height, 0, height.length);
        System.arraycopy(snow, 0, d.snow, 0, snow.length);
        System.arraycopy(bornAt, 0, d.bornAt, 0, bornAt.length);
        System.arraycopy(waterY, 0, d.waterY, 0, waterY.length);
        return d;
    }
}
