package com.geogenesis.client.preview;

import com.geogenesis.worldgen.climate.BiomeClassifier;
import com.geogenesis.worldgen.climate.BiomeClassifier.BiomeClass;
import com.geogenesis.worldgen.climate.ClimateZone;
import com.geogenesis.worldgen.climate.ClimateZone.Zone;
import com.geogenesis.worldgen.climate.Latitude;
import com.geogenesis.worldgen.terrain.Cell;
import com.geogenesis.worldgen.terrain.TerrainClass;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 零依赖配色中枢（不 import net.minecraft，Swing 与 MC 共用）。
 *
 * <ul>
 *   <li>{@link PreviewLayer} 注册表：每个图层携带元数据（连续/离散、本地化 key、默认色带、分组、图例/默认可见）。</li>
 *   <li>内置多套色带（elevation/temperature/... + 科学色带 inferno/viridis/...），Lab 插值 {@link ColorMap} 烘焙 LUT。</li>
 *   <li>离散映射：climateZone / biome / terrainType 的 id→颜色。</li>
 *   <li>MC 侧可经 {@link #setLayerColormap}/{@link #setDiscreteColors} 覆盖默认（来自 JSON 资源 / 用户文件）。</li>
 * </ul>
 *
 * 所有颜色以 RGB(0xRRGGBB) 形式在内部流转；MC NativeImage 写入前用 {@link #toABGR} 转换，
 * Swing 直接用 RGB。分类逻辑全部在 climate 包，本类只查表，杜绝枚举/图层写死颜色。
 */
public final class GeoPalette {

    private GeoPalette() {}

    // ============================================================
    // 图层注册表
    // ============================================================

    public enum Kind { CONTINUOUS, DISCRETE }

    public enum Group { BASE, TERRAIN, CLIMATE, WATER }

    public enum PreviewLayer {
        ELEVATION(Kind.CONTINUOUS, "geogenesis.layer.elevation", "elevation", Group.TERRAIN, true, true),
        TEMPERATURE(Kind.CONTINUOUS, "geogenesis.layer.temperature", "temperature", Group.CLIMATE, true, true),
        HUMIDITY(Kind.CONTINUOUS, "geogenesis.layer.humidity", "humidity", Group.CLIMATE, true, true),
        CONTINENTALITY(Kind.CONTINUOUS, "geogenesis.layer.continentality", "continentality", Group.CLIMATE, false, false),
        RELIEF(Kind.CONTINUOUS, "geogenesis.layer.relief", "relief", Group.TERRAIN, false, false),
        LATITUDE(Kind.CONTINUOUS, "geogenesis.layer.latitude", "latitude", Group.CLIMATE, false, false),
        CLIMATE_ZONE(Kind.DISCRETE, "geogenesis.layer.climate_zone", "climateZone", Group.CLIMATE, true, true),
        BIOME(Kind.DISCRETE, "geogenesis.layer.biome", "biome", Group.BASE, true, true),
        TERRAIN_TYPE(Kind.DISCRETE, "geogenesis.layer.terrain_type", "terrainType", Group.TERRAIN, true, true),
        RIVER_NETWORK(Kind.CONTINUOUS, "geogenesis.layer.river_network", "river_network", Group.WATER, true, true),
        BIOME_REAL(Kind.DISCRETE, "geogenesis.layer.biome_real", "biomeReal", Group.BASE, true, true),
        ROCK_LAYER(Kind.DISCRETE, "geogenesis.layer.rock_layer", "rockLayer", Group.TERRAIN, false, false),
        ROCK_TYPE(Kind.DISCRETE, "geogenesis.layer.rock_type", "rockType", Group.TERRAIN, false, false),
        VEIN_MAP(Kind.DISCRETE, "geogenesis.layer.vein_map", "veinMap", Group.TERRAIN, false, false);

        public final Kind kind;
        public final String labelKey;
        public final String colormapKey;
        public final Group group;
        public final boolean legendable;
        public final boolean defaultVisible;

        PreviewLayer(Kind kind, String labelKey, String colormapKey, Group group, boolean legendable, boolean defaultVisible) {
            this.kind = kind;
            this.labelKey = labelKey;
            this.colormapKey = colormapKey;
            this.group = group;
            this.legendable = legendable;
            this.defaultVisible = defaultVisible;
        }

        public int index() {
            return ordinal();
        }
    }

    /** 图例条目（离散图层）。 */
    public static final class LegendEntry {
        public final int id;
        public final int color;       // RGB 0xRRGGBB
        public final String labelKey; // 本地化 key（MC 用 I18n，Swing 用 ENGLISH 回退）
        public LegendEntry(int id, int color, String labelKey) {
            this.id = id;
            this.color = color;
            this.labelKey = labelKey;
        }
    }

    // ============================================================
    // 内置色带（停靠点 [pos,r,g,b]∈[0,1]）
    // ============================================================

    // 高程色带按「语义 e 锚点」定义（非硬编码 pos）：e = HeightCurve 本征坐标，0 = 海平面。
    // 运行时 buildElevationColormap(eMin, eMax) 把每个锚点的 e 换算为 pos = (e - eMin) / (eMax - eMin)，
    // 使色带随地形配置（海床深度 = eMin / 最高峰 = eMax / 海平面 = e=0）自动适配，永不因范围变化而错位。
    // 海平面 e=0 始终是蓝→绿分界，陆地（e>0）立刻显绿、海滩/平原不再泛蓝。
    // 首锚点 e 取极小（≤任何 eMin）拉到 pos 0，末锚点 e 取极大（≥任何 eMax）拉到 pos 1。
    private static final float[][] S_ELEVATION_E = {
            {-1.20f, 0.04f, 0.08f, 0.26f},   // 海床最深处（拉到 pos 0）— 深暗蓝
            {-0.35f, 0.10f, 0.24f, 0.52f},   // 深海 — 蓝
            {-0.15f, 0.22f, 0.46f, 0.62f},   // 大陆架 — 浅蓝（海洋末端）
            { 0.00f, 0.34f, 0.60f, 0.34f},   // 海平面 e=0 — 绿（海陆分界，始终对齐 e=0）
            { 0.06f, 0.46f, 0.64f, 0.30f},   // 平原/海滩 — 黄绿
            { 0.25f, 0.58f, 0.58f, 0.32f},   // 丘陵 — 橄榄绿
            { 0.50f, 0.62f, 0.50f, 0.36f},   // 低山 — 棕绿
            { 0.75f, 0.70f, 0.62f, 0.52f},   // 高山 — 浅棕
            { 1.20f, 0.97f, 0.97f, 0.97f},   // 雪峰（拉到 pos 1）— 白
    };
    // 高对比渐变色带：相邻色阶明显区分（用户反馈「相近色阶变化不明显」）。
    private static final float[][] S_TEMPERATURE = {
            {0.00f, 0.13f, 0.22f, 0.58f},  // 极冷 深蓝
            {0.20f, 0.20f, 0.55f, 0.85f},  // 冷 蓝
            {0.40f, 0.22f, 0.80f, 0.68f},  // 凉 青绿
            {0.55f, 0.45f, 0.82f, 0.30f},  // 温 绿
            {0.70f, 0.95f, 0.85f, 0.22f},  // 暖 黄
            {0.85f, 0.96f, 0.55f, 0.14f},  // 热 橙
            {1.00f, 0.86f, 0.18f, 0.14f},  // 极热 红
    };
    private static final float[][] S_HUMIDITY = {
            {0.00f, 0.80f, 0.62f, 0.30f},  // 干旱 土黄
            {0.25f, 0.62f, 0.72f, 0.28f},  // 半干 黄绿
            {0.50f, 0.38f, 0.70f, 0.34f},  // 湿润 绿
            {0.75f, 0.22f, 0.60f, 0.72f},  // 潮湿 青
            {1.00f, 0.18f, 0.42f, 0.85f},  // 极湿 蓝
    };
    // 大陆性：c<0 海洋 → c>0 内陆。深蓝（海洋）→ 青（近海）→ 橄榄（内陆）→ 棕（深内陆）。
    private static final float[][] S_CONTINENTALITY = {
            {0.00f, 0.10f, 0.30f, 0.65f},  // 海洋 深蓝
            {0.40f, 0.28f, 0.60f, 0.62f},  // 近海 青
            {0.70f, 0.60f, 0.66f, 0.38f},  // 内陆 橄榄
            {1.00f, 0.78f, 0.54f, 0.28f},  // 深内陆 棕
    };
    private static final float[][] S_RELIEF = {
            {0.00f, 0.18f, 0.30f, 0.22f},  // 平 深绿
            {0.30f, 0.35f, 0.55f, 0.28f},  // 缓起伏 绿
            {0.55f, 0.70f, 0.68f, 0.32f},  // 中起伏 黄绿
            {0.78f, 0.85f, 0.60f, 0.30f},  // 强起伏 橙棕
            {1.00f, 0.90f, 0.88f, 0.82f},  // 极强 浅灰白
    };
    private static final float[][] S_LATITUDE = {
            {0.00f, 0.40f, 0.70f, 0.35f},  // 赤道 绿
            {0.35f, 0.80f, 0.82f, 0.40f},  // 亚热带 浅黄绿
            {0.65f, 0.88f, 0.70f, 0.55f},  // 寒带 浅棕
            {1.00f, 0.93f, 0.96f, 0.99f},  // 极地 白
    };
    private static final float[][] S_RIVER_NETWORK = {
            {0.00f, 0.22f, 0.25f, 0.28f},  // 远 深灰
            {0.40f, 0.35f, 0.46f, 0.56f},  // 近河 灰蓝
            {0.70f, 0.20f, 0.56f, 0.86f},  // 河岸 蓝
            {1.00f, 0.45f, 0.92f, 1.00f},  // 河心 亮青
    };

    // 海洋深度色带（用于预览看清海底地貌）
    private static final float[][] S_OCEAN_DEPTH = {
            {0.00f, 0.10f, 0.18f, 0.40f},  // 最深洋盆 — 蓝紫，明显亮于背景
            {0.30f, 0.12f, 0.25f, 0.55f},  // 深海
            {0.55f, 0.18f, 0.40f, 0.72f},  // 大陆坡
            {0.80f, 0.28f, 0.55f, 0.82f},  // 浅海
            {1.00f, 0.50f, 0.78f, 0.92f},  // 海岸
    };

    // 科学色带（用于高程/任意连续图层的可切换色带）
    private static final float[][] S_INFERNO = {
            {0.00f, 0.001f, 0.000f, 0.014f}, {0.20f, 0.270f, 0.010f, 0.330f},
            {0.40f, 0.590f, 0.160f, 0.320f}, {0.60f, 0.860f, 0.350f, 0.180f},
            {0.80f, 0.980f, 0.660f, 0.150f}, {1.00f, 0.990f, 0.990f, 0.760f},
    };
    private static final float[][] S_VIRIDIS = {
            {0.00f, 0.267f, 0.005f, 0.329f}, {0.25f, 0.275f, 0.196f, 0.497f},
            {0.50f, 0.128f, 0.567f, 0.551f}, {0.75f, 0.369f, 0.789f, 0.383f},
            {1.00f, 0.993f, 0.906f, 0.144f},
    };
    private static final float[][] S_PLASMA = {
            {0.00f, 0.050f, 0.030f, 0.530f}, {0.25f, 0.440f, 0.160f, 0.650f},
            {0.50f, 0.790f, 0.280f, 0.510f}, {0.75f, 0.960f, 0.550f, 0.260f},
            {1.00f, 0.940f, 0.980f, 0.130f},
    };
    private static final float[][] S_MAGMA = {
            {0.00f, 0.001f, 0.000f, 0.014f}, {0.25f, 0.290f, 0.070f, 0.350f},
            {0.50f, 0.670f, 0.190f, 0.390f}, {0.75f, 0.920f, 0.400f, 0.280f},
            {1.00f, 0.990f, 0.990f, 0.760f},
    };
    private static final float[][] S_CIVIDIS = {
            {0.00f, 0.000f, 0.130f, 0.300f}, {0.50f, 0.300f, 0.430f, 0.450f},
            {1.00f, 1.000f, 0.930f, 0.280f},
    };
    private static final float[][] S_GRAYSCALE = {
            {0.00f, 0.00f, 0.00f, 0.00f}, {1.00f, 1.00f, 1.00f, 1.00f},
    };

    private static final Map<String, ColorMap> COLORMAPS = new HashMap<>();
    private static final List<String> SCIENTIFIC = Arrays.asList(
            "inferno", "viridis", "plasma", "magma", "cividis", "grayscale", "ocean_depth");

    private static double eMin = -0.35;
    private static double eMax = 0.95;
    static {
        // 高程色带按语义 e 锚点 + 默认范围 [-0.35,0.95] 构建；
        // 运行时 setElevationERange 会以其传入范围重建，自动适配地形配置（海床/最高峰/海平面）。
        eMin = -0.35; eMax = 0.95;
        buildElevationColormap(eMin, eMax);
        register("temperature", S_TEMPERATURE);
        register("humidity", S_HUMIDITY);
        register("continentality", S_CONTINENTALITY);
        register("relief", S_RELIEF);
        register("latitude", S_LATITUDE);
        register("river_network", S_RIVER_NETWORK);
        register("inferno", S_INFERNO);
        register("viridis", S_VIRIDIS);
        register("plasma", S_PLASMA);
        register("magma", S_MAGMA);
        register("cividis", S_CIVIDIS);
        register("grayscale", S_GRAYSCALE);
        register("ocean_depth", S_OCEAN_DEPTH);
        for (ColorMap cm : COLORMAPS.values()) cm.bake(256);
    }

    private static void register(String name, float[][] stops) {
        COLORMAPS.put(name, new ColorMap(name, stops));
    }

    /** 当前高程图层使用的色带名（可被 UI/覆盖切换）。 */
    private static String elevationColormapName = "elevation";

    public static void setElevationColormap(String name) {
        if (COLORMAPS.containsKey(name)) elevationColormapName = name;
    }

    public static String getElevationColormap() {
        return elevationColormapName;
    }

    public static List<String> scientificColormaps() {
        return new ArrayList<>(SCIENTIFIC);
    }

    // ============================================================
    // 高程色阶映射：直接按地形归一化高程 e ∈ [eMin, eMax] 映射，
    // [eMin, eMax] 取地形实际可达 e 区间（由地形类型 e 界限换算，见 TerrainParams.elevationERange）。
    // 这样地形最高处触顶雪白、最深触底深蓝，且不依赖世界绝对高度上下限。
    // ============================================================

    /** 海平面 Y，用于图例海陆分界标注。 */
    private static int seaLevel = 63;

    /**
     * 用语义 e 锚点（S_ELEVATION_E）构建高程色带，并按当前 eMin/eMax 把每个锚点换算为 pos。
     * 这样海平面(e=0)、海床(eMin)、最高峰(eMax) 随地形配置自动适配，色带永不因范围变化而错位。
     */
    private static void buildElevationColormap(double emin, double emax) {
        double span = Math.max(1e-6, emax - emin);
        float[][] stops = new float[S_ELEVATION_E.length][4];
        for (int i = 0; i < S_ELEVATION_E.length; i++) {
            double e = S_ELEVATION_E[i][0];
            double pos = Math.max(0.0, Math.min(1.0, (e - emin) / span));
            stops[i][0] = (float) pos;
            stops[i][1] = S_ELEVATION_E[i][1];
            stops[i][2] = S_ELEVATION_E[i][2];
            stops[i][3] = S_ELEVATION_E[i][3];
        }
        ColorMap cm = new ColorMap("elevation", stops);
        cm.bake(256);
        COLORMAPS.put("elevation", cm);
    }

    /** 设置高程色阶的 e 范围（地形实际可达区间，见 TerrainParams.elevationERange）。
     *  同时按新范围重建高程色带，使其随海床深度/最高峰/海平面自动适配。默认 [-0.35,0.95]。 */
    public static void setElevationERange(double minE, double maxE) {
        if (maxE > minE) {
            eMin = minE;
            eMax = maxE;
            buildElevationColormap(eMin, eMax);
        }
    }

    /** 设置海平面 Y，用于图例海陆分界标注。 */
    public static void setSeaLevel(int sl) {
        seaLevel = sl;
    }

    // ============================================================
    // 气候图层地形底图模式（数据披地形的表现方式）
    // ============================================================

    /** OFF=纯数据色带；TINT=混入高程彩色带（旧，海洋带蓝绿调）；SHADE=仅按海拔/起伏调亮度，保留数据色相。 */
    public enum TerrainUnderlay { OFF, TINT, SHADE }
    /** 默认采用地形阴影（保留数据色相 + 地形阴影），比 TINT 染色底图更干净，属推荐优化项。 */
    private static TerrainUnderlay terrainUnderlay = TerrainUnderlay.SHADE;

    public static void setTerrainUnderlay(TerrainUnderlay m) { if (m != null) terrainUnderlay = m; }
    public static TerrainUnderlay getTerrainUnderlay() { return terrainUnderlay; }
    public static void cycleTerrainUnderlay() {
        if (terrainUnderlay == TerrainUnderlay.OFF) terrainUnderlay = TerrainUnderlay.TINT;
        else if (terrainUnderlay == TerrainUnderlay.TINT) terrainUnderlay = TerrainUnderlay.SHADE;
        else terrainUnderlay = TerrainUnderlay.OFF;
    }
    public static String terrainUnderlayLabel() {
        if (terrainUnderlay == TerrainUnderlay.OFF) return "关闭";
        if (terrainUnderlay == TerrainUnderlay.TINT) return "染色底图";
        return "地形阴影";
    }

    /**
     * 连续图层图例的顶/底语义标签（供 MC PreviewDisplay 与 Swing TerrainPreview 共用）。
     * 图例渐变顶部对应 pos=1、底部对应 pos=0；对多数图层 pos=(值+1)/2，故顶=+1、底=-1。
     * ELEVATION 图层由 UI 自行换算 Y 高度，不在此处理。
     */
    public static String[] continuousLegendLabels(PreviewLayer layer) {
        if (layer == PreviewLayer.TEMPERATURE) return new String[]{"热 +1", "冷 -1"};
        if (layer == PreviewLayer.HUMIDITY) return new String[]{"湿 +1", "干 -1"};
        if (layer == PreviewLayer.CONTINENTALITY) return new String[]{"内陆 +1", "海洋 -1"};
        if (layer == PreviewLayer.RELIEF) return new String[]{"起伏高", "起伏低"};
        if (layer == PreviewLayer.LATITUDE) return new String[]{"北", "南"};
        if (layer == PreviewLayer.RIVER_NETWORK) return new String[]{"河心", "远"};
        return new String[]{"1.0", "0.0"};
    }

    /**
     * 形态起伏数据→pos 映射：bulk 地形 shape≈0 聚在中灰，signed-gamma(g<1) 拉伸低起伏端，
     * 使平缓丘陵与平原区分更明显，同时保持单调与 0 对称（正=高、负=低）。
     */
    public static double reliefPos(double shape) {
        double g = 0.65;
        double s = Math.signum(shape) * Math.pow(Math.abs(shape), g);
        return clamp(0.5 + 0.5 * s, 0.0, 1.0);
    }

    /**
     * 连续图层图例渐变采样位置：默认线性 p；RELIEF 改为按 shape 扫描并应用 {@link #reliefPos}，
     * 使图例颜色与地图（同 shape→同色、图例位置对应 shape）完全一致。
     */
    public static double legendGradientPos(PreviewLayer layer, double p) {
        if (layer == PreviewLayer.RELIEF) {
            double shape = -1.0 + 2.0 * p;   // p=0→shape -1(低,底), p=1→shape +1(高,顶)
            return reliefPos(shape);
        }
        return p;
    }

    // ============================================================
    // 离散映射默认色
    // ============================================================

    // TERRAIN_TYPE id 与 TerrainClass.ordinal() 严格对齐（顺序/数量必须一致！）：
    // 0 OCEAN,1 DEEP_OCEAN,2 CONTINENTAL_SHELF,3 SUBMARINE_RIDGE,4 SEAMOUNT,
    // 5 LAKE,6 RIVER,7 BEACH,8 PLAIN,9 HILLS,10 PLATEAU,11 MOUNTAINS,
    // 12 PEAK,13 BASIN,14 SNOW,15 VOLCANO,16 VOLCANIC_FIELD
    private static final int[] T_TERRAIN_TYPE = {
            0x2E5C8A, // 0  OCEAN 中蓝
            0x1B3F6B, // 1  DEEP_OCEAN 深蓝
            0x5A9EC8, // 2  CONTINENTAL_SHELF 浅蓝
            0x4A8A6A, // 3  SUBMARINE_RIDGE 青绿
            0x6A7A6A, // 4  SEAMOUNT 暗灰绿
            0x34B4D6, // 5  LAKE 青蓝
            0x2E6FD6, // 6  RIVER 浅蓝
            0xD9C18A, // 7  BEACH 沙黄
            0x6FA84B, // 8  PLAIN 草绿
            0x9BBF5A, // 9  HILLS 黄绿
            0xC2A04A, // 10 PLATEAU 赭黄
            0x8A7A66, // 11 MOUNTAINS 棕
            0xDDE6EE, // 12 PEAK 灰白
            0x7A6FA0, // 13 BASIN 紫灰
            0xFFFFFF, // 14 SNOW 白
            0xE0703C, // 15 VOLCANO 火山橙红（熔岩）
            0xC2603A, // 16 VOLCANIC_FIELD 火山群暗红褐
    };
    // CLIMATE_ZONE id: 与 Zone.ordinal() 对齐
    private static final int[] T_CLIMATE_ZONE = {
            0xC0413B, // TROPICAL A
            0xD9A441, // ARID B
            0x4C9A2A, // TEMPERATE C
            0x3B7DA8, // BOREAL D
            0xE8EEF2, // POLAR E
    };
    // BIOME id: 与 BiomeClass.ordinal() 严格对齐（顺序/数量必须一致！）。
    // BiomeClass 枚举（com.geogenesis.worldgen.climate.BiomeClassifier）：
    //   0 OCEAN, 1 DEEP_OCEAN, 2 BEACH, 3 PLAIN, 4 HILLS, 5 PLATEAU, 6 MOUNTAINS,
    //   7 PEAK, 8 LAKE, 9 RIVER, 10 BASIN, 11 SNOW, 12 SWAMP, 13 FOREST, 14 TAIGA,
    //   15 DESERT, 16 SAVANNA, 17 JUNGLE, 18 TUNDRA
    // 若 BiomeClass 增删项，本数组须同步增删，否则图例离散索引越界崩溃。
    private static final int[] T_BIOME = {
            0x2E6FD6, // 0  OCEAN
            0x1B3F8F, // 1  DEEP_OCEAN
            0xE6D29A, // 2  BEACH
            0x7CB342, // 3  PLAIN
            0x8A7A66, // 4  HILLS
            0xC2A04A, // 5  PLATEAU
            0x6B5D4A, // 6  MOUNTAINS
            0xFFFFFF, // 7  PEAK
            0x34B4D6, // 8  LAKE
            0x2E6F9F, // 9  RIVER
            0x7A6FA0, // 10 BASIN
            0xE8F0E8, // 11 SNOW
            0x4A6B3A, // 12 SWAMP
            0x4C9A2A, // 13 FOREST
            0x3B6B4A, // 14 TAIGA
            0xD9C26A, // 15 DESERT
            0xB5C56A, // 16 SAVANNA
            0x2E8B3B, // 17 JUNGLE
            0xCFE0EA, // 18 TUNDRA
    };

    private static final Map<PreviewLayer, int[]> discreteDefaults = new EnumMap<>(PreviewLayer.class);
    private static final Map<PreviewLayer, int[]> discreteOverrides = new EnumMap<>(PreviewLayer.class);

    static {
        discreteDefaults.put(PreviewLayer.TERRAIN_TYPE, T_TERRAIN_TYPE);
        discreteDefaults.put(PreviewLayer.CLIMATE_ZONE, T_CLIMATE_ZONE);
        discreteDefaults.put(PreviewLayer.BIOME, T_BIOME);
        // BIOME_REAL/ROCK_LAYER/ROCK_TYPE/VEIN_MAP 无默认色——由 MC 侧或未来地质系统填充
    }

    // ============================================================
    // 颜色查询
    // ============================================================

        /** 连续图层单点查询：pos∈[0,1] → RGB(0xRRGGBB)。 */
        public static int continuous(PreviewLayer layer, double pos) {
            String key = (layer == PreviewLayer.ELEVATION) ? elevationColormapName : layer.colormapKey;
            ColorMap cm = COLORMAPS.get(key);
            if (cm == null) cm = COLORMAPS.get("grayscale");
            return cm.getRGB((float) pos);
        }

    /** 离散图层按 id 查询 → RGB(0xRRGGBB)。覆盖优先于默认。 */
    public static int discrete(PreviewLayer layer, int id) {
        int[] override = discreteOverrides.get(layer);
        if (override != null && id >= 0 && id < override.length) return override[id];
        int[] def = discreteDefaults.get(layer);
        if (def != null && id >= 0 && id < def.length) return def[id];
        return 0xFF00FF; // 缺失映射：品红
    }

    /** 简单工具：夹持 double 至 [lo, hi]。 */
    private static double clamp(double v, double lo, double hi) {
        return (v < lo) ? lo : (Math.min(v, hi));
    }

    /** 统一入口：根据图层类型，从 Cell 计算连续位置或离散 id → RGB。 */
    public static int color(PreviewLayer layer, Cell c, int worldX, int worldZ, int minY, int maxY) {
        int base = 0;
        if (layer.kind == Kind.DISCRETE) {
            base = discrete(layer, discreteId(layer, c));
        } else {
            double pos = 0.0;
            switch (layer) {
                case ELEVATION: {
                    // 直接按地形归一化高程 e 映射（HeightCurve 本征坐标），
                    // 范围取地形实际可达 e 区间 [eMin, eMax]，使地形最高触顶雪白、最深触底深蓝。
                    // 海陆边界靠色带蓝→绿分界（e=0 处）体现，图例与像素完全一致。
                    pos = clamp((c.e - eMin) / Math.max(1e-6, eMax - eMin), 0.0, 1.0);
                    break;
                }
                case TEMPERATURE: pos = (c.temperature + 1.0) * 0.5; break;  // [-1,1] → [0,1]
                case HUMIDITY:    pos = (c.humidity + 1.0) * 0.5; break;     // [-1,1] → [0,1]
                case CONTINENTALITY: pos = (c.continentNoise + 1.0) * 0.5; break;
                case RELIEF:      pos = reliefPos(c.shape); break;
                case LATITUDE:    pos = Latitude.latitude01(worldZ); break;
                case RIVER_NETWORK: {
                    double d = c.riverNetDist;                 // 0=河心,1=谷缘
                    pos = (d >= 1.0) ? 0.0 : (1.0 - d);  // 河心亮、远暗
                    break;
                }
                default: pos = 0.0;
            }
            base = continuous(layer, pos);
            // RIVER_NETWORK：溢出河段（木桶短板被突破）→ 洪泛黄高亮
            if (layer == PreviewLayer.RIVER_NETWORK && c.riverNetOverflow && pos > 0.0) {
                base = blendRGB(base, 0xE0B050, 0.6);
            }
        }
        // 气候连续图层叠加地形底图（数据披在地形之上，地理图常见表现手法）。
        // 注：图例仍显示纯数据色带（continuous），地形叠加是地图侧的视觉底层，
        // 与 ELEVATION 的色带错配属不同性质——后者是两套色带互不一致（真 bug）。
        if (layer.kind == Kind.CONTINUOUS && layer.group == Group.CLIMATE) {
            base = overlayTerrain(base, c, worldX, worldZ, minY, maxY);
        }
        return base;
    }

    public static int color(PreviewLayer layer, Cell c, int worldX, int worldZ, int minY, int maxY, boolean hydrology) {
        int base = color(layer, c, worldX, worldZ, minY, maxY);
        if (hydrology) return applyHydrology(base, c);
        return base;
    }

    /**
     * 气候图层地形底图：把数据披在地形之上。三种模式（见 {@link TerrainUnderlay}）：
     *  - OFF  : 纯数据色带（图例与地图完全一致）
     *  - TINT : 混入 35% 高程彩色带（旧行为，海洋会带蓝绿色调）
     *  - SHADE: 仅按高程/起伏调制亮度，保留数据色相，呈现干净的地形阴影观感
     */
    private static int overlayTerrain(int climateRGB, Cell c, int worldX, int worldZ, int minY, int maxY) {
        if (terrainUnderlay == TerrainUnderlay.OFF) return climateRGB;
        if (terrainUnderlay == TerrainUnderlay.SHADE) {
            // SHADE 模式：GeoPalette 只返回纯数据色；真实地形阴影由渲染器在像素级
            // 依据高度梯度（applySlopeShading）计算，避免「按高程值假明暗」造成的描边/网格感。
            return climateRGB;
        }
        // TINT：混入 35% 高程彩色带（数据色披在地形彩色之上）
        int terrainRGB = color(PreviewLayer.ELEVATION, c, worldX, worldZ, minY, maxY);
        return blendRGB(climateRGB, terrainRGB, 0.35);
    }

    /** 水文叠加：在任意图层上叠加河/湖掩码。 */
    public static int applyHydrology(int baseRGB, Cell c) {
        if (c.lakeMask) return blendRGB(baseRGB, 0x00B4DC, 0.55);
        if (c.riverMask) return blendRGB(baseRGB, 0x1E64DC, 0.65);
        if (c.riverWetness > 0.01) {
            return blendRGB(baseRGB, 0x3C82E6, c.riverWetness * 0.4); // 平滑河湖蓝边
        }
        if (c.riverDistance < 0.1) {
            double s = 1.0 - c.riverDistance / 0.1;
            return blendRGB(baseRGB, 0x64A0E6, s * 0.3);
        }
        return baseRGB;
    }

    // ============================================================
    // 覆盖接口（MC 资源 / 用户文件）
    // ============================================================

    public static void setLayerColormap(PreviewLayer layer, ColorMap cm) {
        COLORMAPS.put(layer.colormapKey, cm);
        cm.bake(256);
    }

    public static void registerColormap(String name, ColorMap cm) {
        COLORMAPS.put(name, cm);
        cm.bake(256);
    }

    public static void setDiscreteColors(PreviewLayer layer, int[] colors) {
        discreteOverrides.put(layer, colors != null ? colors.clone() : null);
    }

    /** 按名称设置离散覆盖（来自 JSON 资源 / 用户文件）。name 与枚举/名称数组对齐。 */
    public static void setDiscreteColorsByName(PreviewLayer layer, Map<String, int[]> nameToRgb) {
        int n = countIds(layer);
        int[] arr = new int[n];
        int[] def = discreteDefaults.get(layer);
        for (int i = 0; i < n; i++) arr[i] = (def != null && i < def.length) ? def[i] : 0xFF00FF;
        for (Map.Entry<String, int[]> e : nameToRgb.entrySet()) {
            int id = nameToId(layer, e.getKey());
            if (id >= 0 && id < n) {
                int[] c = e.getValue();
                arr[id] = (c[0] << 16) | (c[1] << 8) | c[2];
            }
        }
        discreteOverrides.put(layer, arr);
    }

    private static int nameToId(PreviewLayer layer, String name) {
        switch (layer) {
            case CLIMATE_ZONE:
                try { return Zone.valueOf(name).ordinal(); } catch (IllegalArgumentException ignored) { return -1; }
            case BIOME:
                try { return BiomeClass.valueOf(name).ordinal(); } catch (IllegalArgumentException ignored) { return -1; }
            case TERRAIN_TYPE:
                for (int i = 0; i < TERRAIN_TYPE_NAMES.length; i++) if (TERRAIN_TYPE_NAMES[i].equals(name)) return i;
                return -1;
            default:
                return -1;
        }
    }

    public static void setElevationColormapStops(float[][] stops) {
        ColorMap cm = new ColorMap("elevation", stops);
        cm.bake(256);
        COLORMAPS.put("elevation", cm);
    }

    // ============================================================
    //  设置屏 API（colormap 列表 + 选中 + 群系色）
    // ============================================================

    /** 色带条目，供 {@link com.geogenesis.client.preview.ColormapPanel} 使用 */
    public record ColormapEntry(String name, ColorMap colormap) {}

    /** 获取所有可用色带条目列表 */
    public static List<ColormapEntry> getColormapEntries() {
        List<ColormapEntry> list = new ArrayList<>();
        for (Map.Entry<String, ColorMap> e : COLORMAPS.entrySet()) {
            list.add(new ColormapEntry(e.getKey(), e.getValue()));
        }
        return list;
    }

    /** 获取当前激活的色带条目 */
    public static ColormapEntry getActiveColormap() {
        ColorMap cm = COLORMAPS.get(elevationColormapName);
        return new ColormapEntry(elevationColormapName, cm);
    }

    /** 设置为激活色带（Elevation 图层） */
    public static void setActiveColormap(ColormapEntry entry) {
        elevationColormapName = entry.name();
    }

    /** 获取 BiomeClass 的离散颜色（BIOME 图层） */
    public static int colorForBiome(int biomeId) {
        if (biomeId >= 0 && biomeId < T_BIOME.length) return T_BIOME[biomeId];
        return 0x888888;
    }

    // ============================================================
    // 图例
    // ============================================================

    public static List<LegendEntry> discreteEntries(PreviewLayer layer) {
        List<LegendEntry> out = new ArrayList<>();
        // 防御：图例数量以「标签枚举/名称数组」为准，避免颜色数组与枚举数量漂移时整窗崩溃。
        int count = Math.min(countIds(layer), labelCount(layer));
        for (int i = 0; i < count; i++) {
            out.add(new LegendEntry(i, discrete(layer, i), discreteLabelKey(layer, i)));
        }
        return out;
    }

    /** 各离散图层的「标签条目数」（枚举/名称数组长度），作为图例迭代上限。 */
    private static int labelCount(PreviewLayer layer) {
        return switch (layer) {
            case CLIMATE_ZONE -> Zone.values().length;
            case BIOME -> BiomeClass.values().length;
            case TERRAIN_TYPE -> TERRAIN_TYPE_NAMES.length;
            case BIOME_REAL -> 0; // 由 MC Biome Registry 动态决定，无固定标签列表
            default -> Integer.MAX_VALUE;
        };
    }

    private static int countIds(PreviewLayer layer) {
        int[] o = discreteOverrides.get(layer);
        if (o != null) return o.length;
        int[] d = discreteDefaults.get(layer);
        return d != null ? d.length : 0;
    }

    private static String discreteLabelKey(PreviewLayer layer, int id) {
        switch (layer) {
            case CLIMATE_ZONE: return "geogenesis.zone." + Zone.values()[id].name();
            case BIOME:        return "geogenesis.biome." + BiomeClass.values()[id].name();
            case TERRAIN_TYPE: return "geogenesis.terrain_type." + TERRAIN_TYPE_NAMES[id];
            case BIOME_REAL:   return "geogenesis.biome_real." + id;
            default: return layer.labelKey + "." + id;
        }
    }

    private static final String[] TERRAIN_TYPE_NAMES = {
            "OCEAN", "DEEP_OCEAN", "CONTINENTAL_SHELF", "SUBMARINE_RIDGE", "SEAMOUNT",
            "LAKE", "RIVER", "BEACH", "PLAIN", "HILLS", "PLATEAU",
            "MOUNTAINS", "PEAK", "BASIN", "SNOW"
    };

    /** Swing 端图例英文回退（避免缺失翻译时显示 key）。 */
    public static String englishLabel(String labelKey) {
        return ENGLISH.getOrDefault(labelKey, labelKey);
    }

    private static final Map<String, String> ENGLISH = new HashMap<>();
    static {
        // 图层标题
        ENGLISH.put("geogenesis.layer.elevation", "Elevation");
        ENGLISH.put("geogenesis.layer.temperature", "Temperature");
        ENGLISH.put("geogenesis.layer.humidity", "Humidity");
        ENGLISH.put("geogenesis.layer.continentality", "Continentality");
        ENGLISH.put("geogenesis.layer.relief", "Relief");
        ENGLISH.put("geogenesis.layer.latitude", "Latitude");
        ENGLISH.put("geogenesis.layer.climate_zone", "Climate Zone");
        ENGLISH.put("geogenesis.layer.biome", "Biome");
        ENGLISH.put("geogenesis.layer.terrain_type", "Terrain Type");
        ENGLISH.put("geogenesis.layer.river_network", "River Network");
        ENGLISH.put("geogenesis.layer.biome_real", "Biome (Real)");
        // 气候带
        ENGLISH.put("geogenesis.zone.TROPICAL", "Tropical (A)");
        ENGLISH.put("geogenesis.zone.ARID", "Arid (B)");
        ENGLISH.put("geogenesis.zone.TEMPERATE", "Temperate (C)");
        ENGLISH.put("geogenesis.zone.BOREAL", "Boreal (D)");
        ENGLISH.put("geogenesis.zone.POLAR", "Polar (E)");
        ENGLISH.put("geogenesis.terrain_type.OCEAN", "Ocean");
        ENGLISH.put("geogenesis.terrain_type.DEEP_OCEAN", "Deep Ocean");
        ENGLISH.put("geogenesis.terrain_type.CONTINENTAL_SHELF", "Continental Shelf");
        ENGLISH.put("geogenesis.terrain_type.SUBMARINE_RIDGE", "Mid-Ocean Ridge");
        ENGLISH.put("geogenesis.terrain_type.SEAMOUNT", "Seamount");
        ENGLISH.put("geogenesis.terrain_type.LAKE", "Lake");
        ENGLISH.put("geogenesis.terrain_type.RIVER", "River");
        ENGLISH.put("geogenesis.terrain_type.BEACH", "Beach");
        ENGLISH.put("geogenesis.terrain_type.PLAIN", "Plain");
        ENGLISH.put("geogenesis.terrain_type.HILLS", "Hills");
        ENGLISH.put("geogenesis.terrain_type.PLATEAU", "Plateau");
        ENGLISH.put("geogenesis.terrain_type.MOUNTAINS", "Mountains");
        ENGLISH.put("geogenesis.terrain_type.PEAK", "Peak");
        ENGLISH.put("geogenesis.terrain_type.BASIN", "Basin");
        ENGLISH.put("geogenesis.terrain_type.SNOW", "Snow");
        // 地质图层（预留）
        ENGLISH.put("geogenesis.layer.rock_layer", "Rock Layer");
        ENGLISH.put("geogenesis.layer.rock_type", "Rock Type");
        ENGLISH.put("geogenesis.layer.vein_map", "Vein Map");
        ENGLISH.put("geogenesis.biome.OCEAN", "Ocean");
        ENGLISH.put("geogenesis.biome.DEEP_OCEAN", "Deep Ocean");
        ENGLISH.put("geogenesis.biome.COLD_OCEAN", "Cold Ocean");
        ENGLISH.put("geogenesis.biome.DEEP_COLD_OCEAN", "Deep Cold Ocean");
        ENGLISH.put("geogenesis.biome.FROZEN_OCEAN", "Frozen Ocean");
        ENGLISH.put("geogenesis.biome.DEEP_FROZEN_OCEAN", "Deep Frozen Ocean");
        ENGLISH.put("geogenesis.biome.LUKEWARM_OCEAN", "Lukewarm Ocean");
        ENGLISH.put("geogenesis.biome.DEEP_LUKEWARM_OCEAN", "Deep Lukewarm Ocean");
        ENGLISH.put("geogenesis.biome.WARM_OCEAN", "Warm Ocean");
        ENGLISH.put("geogenesis.biome.BEACH", "Beach");
        ENGLISH.put("geogenesis.biome.SNOWY_BEACH", "Snowy Beach");
        ENGLISH.put("geogenesis.biome.FROZEN_PEAKS", "Frozen Peaks");
        ENGLISH.put("geogenesis.biome.STONY_PEAKS", "Stony Peaks");
        ENGLISH.put("geogenesis.biome.SNOWY_SLOPES", "Snowy Slopes");
        ENGLISH.put("geogenesis.biome.WINDSWEPT_HILLS", "Windswept Hills");
        ENGLISH.put("geogenesis.biome.WINDSWEPT_FOREST", "Windswept Forest");
        ENGLISH.put("geogenesis.biome.SNOWY_TAIGA", "Snowy Taiga");
        ENGLISH.put("geogenesis.biome.SNOWY_PLAINS", "Snowy Plains");
        ENGLISH.put("geogenesis.biome.OLD_GROWTH_PINE_TAIGA", "Old Growth Pine Taiga");
        ENGLISH.put("geogenesis.biome.TAIGA", "Taiga");
        ENGLISH.put("geogenesis.biome.PLAINS", "Plains");
        ENGLISH.put("geogenesis.biome.FOREST", "Forest");
        ENGLISH.put("geogenesis.biome.BIRCH_FOREST", "Birch Forest");
        ENGLISH.put("geogenesis.biome.DESERT", "Desert");
        ENGLISH.put("geogenesis.biome.SAVANNA", "Savanna");
        ENGLISH.put("geogenesis.biome.SAVANNA_PLATEAU", "Savanna Plateau");
        ENGLISH.put("geogenesis.biome.JUNGLE", "Jungle");
        ENGLISH.put("geogenesis.biome.SPARSE_JUNGLE", "Sparse Jungle");
    }

    // ============================================================
    // 离散 id 计算（来自 Cell 布尔标记/分类器）
    // ============================================================

    private static int discreteId(PreviewLayer layer, Cell c) {
        switch (layer) {
            case CLIMATE_ZONE: return ClimateZone.classify(c).ordinal();
            case BIOME:        return BiomeClassifier.classify(c).ordinal();
            case TERRAIN_TYPE: return terrainTypeId(c);
            default: return 0;
        }
    }

    /** 公开：计算 Cell 在指定离散图层的离散条目 id（供预览选中高亮与图例点击对齐，保证置灰与图例同 id）。 */
    public static int discreteIdForCell(PreviewLayer layer, Cell c) {
        return discreteId(layer, c);
    }

    // TERRAIN_TYPE id 与 TerrainClass.ordinal() 对齐；水文/雪覆盖作 override（mirror BiomeClassifier）
    private static int terrainTypeId(Cell c) {
        if (c.riverMask) return TerrainClass.RIVER.ordinal();
        if (c.lakeMask) return TerrainClass.LAKE.ordinal();
        // 雪覆盖：仅压盖低起伏地形（PLAIN/HILLS/PLATEAU/BASIN），保留 MOUNTAINS/PEAK 独立身份
        if (c.isSnow && c.terrainType != TerrainClass.MOUNTAINS && c.terrainType != TerrainClass.PEAK) {
            return TerrainClass.SNOW.ordinal();
        }
        return c.terrainType.ordinal();
    }

    // ============================================================
    // 像素格式转换 / 颜色工具
    // ============================================================

    /** RGB(0xRRGGBB) → ABGR(0xAABBGGRR)，供 MC NativeImage.setPixelRGBA 使用。 */
    public static int toABGR(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        return 0xFF000000 | (b << 16) | (g << 8) | r;
    }

    /** RGB(0xRRGGBB) → ARGB(0xAARRGGBB)，供 GuiGraphics.fill() 使用（MC 图形层）。 */
    public static int toARGB(int rgb) {
        int r = (rgb >> 16) & 0xFF;
        int g = (rgb >> 8) & 0xFF;
        int b = rgb & 0xFF;
        return 0xFF000000 | (r << 16) | (g << 8) | b;
    }

    /** ABGR(0xAABBGGRR) → RGB(0xRRGGBB)。 */
    public static int toRGB(int abgr) {
        int r = abgr & 0xFF;
        int g = (abgr >> 8) & 0xFF;
        int b = (abgr >> 16) & 0xFF;
        return (r << 16) | (g << 8) | b;
    }

    private static int blendRGB(int base, int overlay, double s) {
        int br = (base >> 16) & 0xFF, bg = (base >> 8) & 0xFF, bb = base & 0xFF;
        int or = (overlay >> 16) & 0xFF, og = (overlay >> 8) & 0xFF, ob = overlay & 0xFF;
        int r = (int) (br * (1 - s) + or * s);
        int g = (int) (bg * (1 - s) + og * s);
        int b = (int) (bb * (1 - s) + ob * s);
        return (r << 16) | (g << 8) | b;
    }
}
