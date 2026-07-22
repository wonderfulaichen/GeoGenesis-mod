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

    private static final float[][] S_ELEVATION = {
            {0.00f, 0.18f, 0.30f, 0.55f},   // 最深洋盆 (Y=-64) — 明显蓝紫
            {0.18f, 0.22f, 0.40f, 0.68f},   // 深海 (Y≈-6)
            {0.30f, 0.30f, 0.55f, 0.78f},   // 大陆坡 (Y=32)
            {0.45f, 0.50f, 0.65f, 0.42f},   // 海岸/低地 (Y=80)
            {0.65f, 0.60f, 0.55f, 0.36f},   // 丘陵 (Y=144)
            {0.85f, 0.68f, 0.58f, 0.50f},   // 山地 (Y=208)
            {1.00f, 0.96f, 0.96f, 0.96f},   // 雪峰 (Y=256)
    };
    private static final float[][] S_TEMPERATURE = {
            {0.00f, 0.20f, 0.40f, 0.85f},
            {0.50f, 0.95f, 0.90f, 0.40f},
            {1.00f, 0.85f, 0.20f, 0.15f},
    };
    private static final float[][] S_HUMIDITY = {
            {0.00f, 0.76f, 0.66f, 0.40f},
            {0.50f, 0.45f, 0.66f, 0.35f},
            {1.00f, 0.25f, 0.55f, 0.80f},
    };
    private static final float[][] S_CONTINENTALITY = {
            {0.00f, 0.20f, 0.20f, 0.22f},
            {1.00f, 0.85f, 0.85f, 0.88f},
    };
    private static final float[][] S_RELIEF = {
            {0.00f, 0.12f, 0.12f, 0.14f},
            {1.00f, 0.95f, 0.85f, 0.60f},
    };
    private static final float[][] S_LATITUDE = {
            {0.00f, 0.30f, 0.62f, 0.35f},
            {0.50f, 0.85f, 0.82f, 0.55f},
            {1.00f, 0.95f, 0.97f, 1.00f},
    };
    private static final float[][] S_RIVER_NETWORK = {
            {0.00f, 0.30f, 0.32f, 0.36f},  // 非河：浅灰（底，比背景亮）
            {0.55f, 0.18f, 0.45f, 0.75f},  // 近河：亮蓝
            {1.00f, 0.40f, 0.90f, 1.00f},  // 河心：亮青
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

    static {
        register("elevation", S_ELEVATION);
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
    // 高程缩放（setElevationRange 自动缩放；未设置时用传入 minY/maxY）
    // ============================================================

    private static int elevMin = Integer.MIN_VALUE;
    private static int elevMax = Integer.MIN_VALUE;
    /** 海平面 Y，用于海洋区域 ocean_depth 色带独立映射。 */
    private static int seaLevel = 63;

    public static void setElevationRange(int min, int max) {
        elevMin = min;
        elevMax = max;
    }

    /** 设置海平面 Y，海洋区域用 ocean_depth 色带展示海底地貌。 */
    public static void setSeaLevel(int sl) {
        seaLevel = sl;
    }

    public static void clearElevationRange() {
        elevMin = elevMax = Integer.MIN_VALUE;
    }

    // ============================================================
    // 离散映射默认色
    // ============================================================

    // TERRAIN_TYPE id 与 TerrainClass.ordinal() 严格对齐（顺序/数量必须一致！）：
    // 0 OCEAN,1 DEEP_OCEAN,2 CONTINENTAL_SHELF,3 SUBMARINE_RIDGE,4 SEAMOUNT,
    // 5 LAKE,6 RIVER,7 BEACH,8 PLAIN,9 HILLS,10 PLATEAU,11 MOUNTAINS,
    // 12 PEAK,13 BASIN,14 SNOW
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
            boolean oceanElev = (layer == PreviewLayer.ELEVATION && c.terrainType.isOcean());
            switch (layer) {
                case ELEVATION: {
                    int lo = (elevMin != Integer.MIN_VALUE) ? elevMin : minY;
                    int hi = (elevMax != Integer.MIN_VALUE) ? elevMax : maxY;
                    if (oceanElev) {
                        // 海洋区域：用 ocean_depth 色带 + 单独映射范围 [lo, seaLevel] → [0, 1]
                        double oceanSpan = Math.max(1.0, seaLevel - lo);
                        double p = clamp((c.height - lo) / oceanSpan, 0.0, 1.0);
                        ColorMap cm = COLORMAPS.get("ocean_depth");
                        base = (cm != null) ? cm.getRGB((float) p) : 0x000000;
                    } else {
                        pos = (c.height - lo) / Math.max(1.0, hi - lo);
                    }
                    break;
                }
                case TEMPERATURE: pos = (c.temperature + 1.0) * 0.5; break;  // [-1,1] → [0,1]
                case HUMIDITY:    pos = (c.humidity + 1.0) * 0.5; break;     // [-1,1] → [0,1]
                case CONTINENTALITY: pos = (c.continentNoise + 1.0) * 0.5; break;
                case RELIEF:      pos = (c.shape + 1.0) * 0.5; break;
                case LATITUDE:    pos = Latitude.latitude01(worldZ); break;
                case RIVER_NETWORK: {
                    double d = c.riverNetDist;                 // 0=河心,1=谷缘
                    pos = (d >= 1.0) ? 0.0 : (1.0 - d);  // 河心亮、远暗
                    break;
                }
                default: pos = 0.0;
            }
            if (!oceanElev) {
                base = continuous(layer, pos);
                // RIVER_NETWORK：溢出河段（木桶短板被突破）→ 洪泛黄高亮
                if (layer == PreviewLayer.RIVER_NETWORK && c.riverNetOverflow && pos > 0.0) {
                    base = blendRGB(base, 0xE0B050, 0.6);
                }
            }
        }
        // 气候连续图层叠加地形/海陆轮廓，避免看起来像纯噪声色块
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

    /** 把气候颜色叠到地形上：海洋/陆地都浅叠气候数据自身，不淹没问题。 */
    private static int overlayTerrain(int climateRGB, Cell c, int worldX, int worldZ, int minY, int maxY) {
        int terrainRGB = color(PreviewLayer.ELEVATION, c, worldX, worldZ, minY, maxY);
        if (c.terrainType.isOcean()) {
            return blendRGB(climateRGB, terrainRGB, 0.35); // 海洋只取 35% 地形色，气候可见
        } else {
            return blendRGB(climateRGB, terrainRGB, 0.35);
        }
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
