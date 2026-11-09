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
    /** 从 CellCache 构建时缺失的格数（缓存未覆盖该区域）。 */
    public int missingCells;

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
    /** {@code WhittakerType.ordinal()}（10 项），供气候用途。 */
    public final short[] biomeId;
    /**
     * {@code BiomeClass.ordinal()}（<b>35 项</b>），供沙盘 BIOME 图层本色。
     *
     * <p>★ 2026-11-08：沙盘改用自己的<b>既有 35 项群系色</b>
     * （{@link GeoPalette#colorForBiome}），不再另造 10 项色表。
     * 与 {@code BiomeClassifier.classify(cell)} 同源 ⇒ 与平面窗口、图例一致。</p>
     */
    public final short[] biomeClassId;
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
        this.biomeClassId = new short[n];
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

    /**
     * {@code BiomeClass.ordinal()}，<b>带降级</b>。
     *
     * <p>权威路径是 {@code BiomeClassifier.classify(cell)}（与游戏落块群系一致），
     * 但它会触发 {@code Biomes} 静态初始化 ⇒ 无 Forge Bootstrap 的进程会崩。
     * 因此捕获 {@link Throwable} 后走近似映射（{@link #approxBiomeClass}）。</p>
     *
     * <p>降级只影响"极端地形变体"（如高原恶地 ↔ 普通沙漠），
     * 主色调（雪/森林/草原/沙漠/雨林/海）完全一致 —— 沙盘是看形态的，够用。</p>
     */
    private static int biomeClassOfSafe(Cell c) {
        try {
            return com.geogenesis.worldgen.climate.BiomeClassifier.classify(c).ordinal();
        } catch (Throwable ignored) {
            // NoClassDefFoundError / ExceptionInInitializerError / 其他 —— 一律降级，绝不让预览崩
        }
        return approxBiomeClass(c);
    }

    /**
     * 无 MC 注册表时的<b>近似</b>映射：{@code WhittakerType} × 地形/雪 → {@code BiomeClass} 序号。
     *
     * <p>序号必须与 {@code BiomeClassifier.BiomeClass} 声明顺序一致（35 项）：
     * 0-8 海洋、9 RIVER、10 FROZEN_RIVER、11 SWAMP、12 BEACH、13 SNOWY_BEACH、
     * 14 PLAINS、15 MEADOW、16 FOREST、17 BIRCH_FOREST、18 WINDSWEPT_HILLS、
     * 19 WINDSWEPT_FOREST、20 WINDSWEPT_SAVANNA、21 JUNGLE、22 SPARSE_JUNGLE、
     * 23 SAVANNA、24 SAVANNA_PLATEAU、25 DESERT、26 BADLANDS、27 TAIGA、
     * 28 SNOWY_TAIGA、29 SNOWY_PLAINS、30 GROVE、31 SNOWY_SLOPES、
     * 32 STONY_PEAKS、33 JAGGED_PEAKS、34 FROZEN_PEAKS。</p>
     */
    private static int approxBiomeClass(Cell c) {
        var terrain = c.terrainType;
        // 水体 / 海岸优先（强于气候，与 BiomeClassifier.pickKey 的分支顺序一致）
        if (terrain != null) {
            switch (terrain) {
                case RIVER: return 9;
                case LAKE: return 9;
                case BEACH: return 12;
                case SNOW: return 29;
                case VOLCANO: return 32;
                default: break;
            }
        }
        if (c.isSnow) return 29;                     // SNOWY_PLAINS
        if (c.terrainType == com.geogenesis.worldgen.terrain.TerrainClass.PEAK
                || c.terrainType == com.geogenesis.worldgen.terrain.TerrainClass.MOUNTAINS) {
            return 32;                                // STONY_PEAKS
        }
        var wt = c.biomeType == null
                ? com.geogenesis.worldgen.climate.WhittakerType.GRASSLAND : c.biomeType;
        return switch (wt) {
            case ICE, TUNDRA -> 29;                   // SNOWY_PLAINS
            case TAIGA -> 27;                         // TAIGA
            case GRASSLAND -> 14;                     // PLAINS
            case TEMPERATE_FOREST -> 16;              // FOREST
            case TEMPERATE_RAINFOREST -> 17;          // BIRCH_FOREST
            case DESERT -> 25;                        // DESERT
            case SAVANNA -> 23;                       // SAVANNA
            case SEASONAL_FOREST -> 22;               // SPARSE_JUNGLE（季雨林过渡带）
            case TROPICAL_RAINFOREST -> 21;           // JUNGLE
        };
    }

    private static com.geogenesis.worldgen.terrain.TerrainClass terrainOf(short id) {
        var v = com.geogenesis.worldgen.terrain.TerrainClass.values();
        return (id >= 0 && id < v.length) ? v[id] : com.geogenesis.worldgen.terrain.TerrainClass.OCEAN;
    }

    /**
     * <b>直接从地形引擎采样</b>（不经 CellCache）—— 供探针对拍用。
     *
     * <p>★ 2026-11-09 B4：原先这里有两个重载（带/不带 {@code hydro} 参数），
     * <b>已删除</b> —— 它们只被三个探针调用，而那三个探针的判据
     * （与沙盘路径一致性）改用 {@link #fromCellCache} 对拍更直接。</p>
     *
     * <p>⚠ 本方法<b>绕过缓存</b>，每次都重算 —— 只在探针里用，
     * 生产路径一律走 {@code fromCellCache}（读平面视图的缓存，零二次采样）。</p>
     */
    public static TerrainFieldData sampleDirect(GeoGenesisTerrain terrain,
                                                 int originX, int originZ,
                                                 int blocksWide, int blocksHigh,
                                                 int gridW, int gridH, long viewportId) {
        TerrainFieldData d = new TerrainFieldData(gridW, gridH, originX, originZ,
                blocksWide, blocksHigh, viewportId, 0);
        long t0 = System.nanoTime();
        double stepX = blocksWide / (double) gridW;
        double stepZ = blocksHigh / (double) gridH;
        for (int gx = 0; gx < gridW; gx++) {
            int wx = originX + (int) Math.round(gx * stepX);
            for (int gz = 0; gz < gridH; gz++) {
                int wz = originZ + (int) Math.round(gz * stepZ);
                Cell c = terrain.sampleCellCoarse(wx, wz);
                d.put(gx, gz, c);
            }
        }
        d.costMs = (System.nanoTime() - t0) / 1_000_000L;
        return d;
    }

    /**
     * <b>从平面视图的 {@code CellCache} 构建沙盘数据</b>（★ 2026-11-08 用户裁定）。
     *
     * <h2>为什么直读缓存</h2>
     * <p>用户原话：「我们原来的平面视图不就有完整的数据吗？直接转换一下就好了。
     * 明明又有高度数据并且精度都到 MC 方块级了。」</p>
     * <p>{@code CellCache} 每个 chunk 存满 16×16 个 {@link Cell}（1 Cell = 1 方块），
     * 精度就是 MC 方块级 —— 沙盘不该再走 {@code ProgressiveSampler} 二次采样。</p>
     *
     * <h2>★ 尺寸必须由 <b>柱数</b> 决定，不是窗口方块数（OOM 事故）</h2>
     * <p>曾把细格数写成窗口方块数（2048）⇒ 2048² = <b>419 万</b>格 × 十几个数组
     * ⇒ {@code OutOfMemoryError}（crash-2026-11-09_03.26.03 已取证）。</p>
     * <p>参考项目结构：{@code fine = columns × HEIGHT_STRIDE = 128 × 2 = 256}。
     * 细格只表示「取样密度」，与窗口实际覆盖多少方块无关。</p>
     *
     * @param cache    平面视图的 CellCache
     * @param originX  窗口左上角世界 X
     * @param originZ  窗口左上角世界 Z
     * @param blocksWide/blocksHigh 窗口方块尺寸
     * @param gridW/gridH 目标细格数（= 柱数 × 2）
     * @param viewportId 视口标识
     */
    public static TerrainFieldData fromCellCache(
            com.geogenesis.client.preview.chunk.CellCache cache,
            int originX, int originZ, int blocksWide, int blocksHigh,
            int gridW, int gridH, long viewportId) {
        TerrainFieldData d = new TerrainFieldData(gridW, gridH,
                originX, originZ, blocksWide, blocksHigh, viewportId, 0);
        long t0 = System.nanoTime();
        // 每细格对应的方块步长（窗口 / 细格数）
        double stepX = blocksWide / (double) gridW;
        double stepZ = blocksHigh / (double) gridH;
        // ★★ 2026-11-09【修"沙盘深蓝棋盘格"】：每细格在 stepX×stepZ 范围内
        //   采<b>多点取中位</b>，而不是只采左上角 1 个点。
        //
        //   实机取证：BASE_ZOOM=2.0 ⇒ 64 柱 / 1024 格窗口 ⇒ step=16，
        //   一根 16 格宽的柱子高度由<b>1 个采样点</b>决定。
        //   深海区整片等高 ⇒ 棋盘格；山地台阶状跳变。
        //   图例明明显示「高程 Y=19~285」（有 285 格地形），画面却是平的。
        //
        //   每细格采 (sx×sz) 个点（sx = ceil(stepX)，上限 16 防 OOM），
        //   height 取中位 ⇒ 与 SandboxGeometry.buildColumns 的「组内中位」语义衔接，
        //   颜色取第一个有效点（保留群系细节）。
        int sx = Math.max(1, Math.min(16, (int) Math.ceil(stepX)));
        int sz = Math.max(1, Math.min(16, (int) Math.ceil(stepZ)));
        double[] hBuf = new double[sx * sz];
        int sampled = 0, missing = 0;
        for (int gx = 0; gx < gridW; gx++) {
            for (int gz = 0; gz < gridH; gz++) {
                // 该细格覆盖的方块范围（左上角）
                int bx0 = originX + (int) Math.round(gx * stepX);
                int bz0 = originZ + (int) Math.round(gz * stepZ);
                int hvN = 0;
                Cell first = null;
                for (int dx = 0; dx < sx; dx++) {
                    int wx = bx0 + dx;
                    int cx = Math.floorDiv(wx, 16), lx = wx - cx * 16;
                    for (int dz = 0; dz < sz; dz++) {
                        int wz = bz0 + dz;
                        int cz = Math.floorDiv(wz, 16), lz = wz - cz * 16;
                        Cell c = cache.getCell(cx, cz, lx, lz);
                        if (c == null) continue;
                        if (first == null) first = c;
                        hBuf[hvN++] = c.height;
                    }
                }
                if (first == null) { missing++; continue; }
                // ★ height 覆盖为组内中位（抗单点尖峰，反映整格典型高度）
                if (hvN > 0) {
                    java.util.Arrays.sort(hBuf, 0, hvN);
                    d.height[sampleIndex(gx, gz, gridH)] = hBuf[hvN / 2];
                }
                d.put(gx, gz, first);
                sampled++;
            }
        }
        // 缓存数据是"早就采好的"⇒ 不重播生长动画（put 会写 currentTimeMillis）
        long longAgo = (t0 / 1_000_000L) - 60_000L;
        for (int i = 0; i < d.bornAt.length; i++) {
            if (d.bornAt[i] != 0L) d.bornAt[i] = longAgo;
        }
        d.costMs = (System.nanoTime() - t0) / 1_000_000L;
        d.missingCells = missing;
        return d;
    }

    /** X 主序索引。 */
    private static int sampleIndex(int gx, int gz, int gridH) {
        return gx * gridH + gz;
    }

    /** 写入一个格点（采样与渐进填充共用）。 */
    void put(int gx, int gz, Cell c) {        int i = index(gx, gz);
        if (i < 0 || c == null) return;
        e[i] = c.e;
        temperature[i] = c.temperature;
        humidity[i] = c.humidity;
        precipitation[i] = c.precipitation;
        continent[i] = c.continent;
        shape[i] = c.shape;
        discharge[i] = c.riverNetDischarge;
        biomeId[i] = (short) (c.biomeType == null ? 0 : c.biomeType.ordinal());
        // ★ 35 项 BiomeClass（与 GeoPalette.T_BIOME 同序），供沙盘本色。
        //
        //   ⚠ {@code BiomeClassifier.classify} 会触发 {@code Biomes} 静态初始化，
        //   那需要 Forge Bootstrap ⇒ 在<b>探针进程</b>与<b>任何无 MC 环境的调用链</b>里会抛
        //   ExceptionInInitializerError（已实测）。
        //   ⇒ 用 {@link #biomeClassOfSafe} 降级：可用则走权威分类，
        //   不可用则按 Whittaker×地形 近似到同一套 35 项（色值不变，只影响极端变体）。
        biomeClassId[i] = (short) biomeClassOfSafe(c);
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
        biomeClassId[i] = src.biomeClassId[j];
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
        System.arraycopy(biomeClassId, 0, d.biomeClassId, 0, biomeClassId.length);
        System.arraycopy(terrainId, 0, d.terrainId, 0, terrainId.length);
        System.arraycopy(extraId, 0, d.extraId, 0, extraId.length);
        System.arraycopy(height, 0, d.height, 0, height.length);
        System.arraycopy(snow, 0, d.snow, 0, snow.length);
        System.arraycopy(bornAt, 0, d.bornAt, 0, bornAt.length);
        System.arraycopy(waterY, 0, d.waterY, 0, waterY.length);
        return d;
    }
}
