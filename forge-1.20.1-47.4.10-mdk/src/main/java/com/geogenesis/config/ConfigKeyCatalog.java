package com.geogenesis.config;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * <b>配置键的算法轴 / 域 / 可见性 —— 全仓唯一事实源</b>（零 MC 依赖，纯 Java）。
 *
 * <h2>为什么要有这个类</h2>
 * <p>2026-11-07 的配置键清理给 {@link GeoGenesisConfig} 的每个字段加了 {@code [BOTH]} /
 * {@code [LEGACY_ONLY]} / {@code [DIAGNOSTIC]} / {@code [PREVIEW_ONLY]} 的 javadoc 标记。
 * 但那份标注<b>只是注释</b> —— 面板无法据此决定"这个键该不该显示"，只能靠人肉判断，
 * 于是长出了两处真实病灶：</p>
 * <ol>
 *   <li>70 个 {@code LEGACY_ONLY} 键里有 27 个仍摆在面板上冒充世界参数
 *       （用户已裁定<b>没有 legacy 存档</b> ⇒ 对新世界零效果）；</li>
 *   <li>14 个 {@code BOTH} 真旋钮（大陆尺度、侵蚀全套…）<b>根本没上界面</b>，
 *       只能手改 TOML。</li>
 * </ol>
 * <p>⇒ 本轮把"注释里的轴"提升为"代码里的数据"，由
 * {@code ConfigPanelCoverageProbe} 做静态交叉校验（注释标记 ↔ 本表 ↔ 面板实际引用）。
 * 任一处不一致即 FAIL。</p>
 *
 * <h2>三个维度</h2>
 * <table border="1">
 *   <caption>口径</caption>
 *   <tr><th>维度</th><th>含义</th><th>决定什么</th></tr>
 *   <tr><td>{@link Axis}</td>
 *       <td>这个键影响哪条地形链（与 javadoc 标记一一对应）</td>
 *       <td><b>是否可见</b>：{@code LEGACY_ONLY} 默认隐藏；{@code DIAGNOSTIC} 折叠</td></tr>
 *   <tr><td>{@link Domain}</td>
 *       <td>按<b>消费点</b>划分的域（不是按旧页签）</td>
 *       <td><b>落在哪个页签</b>；同域内键的消费点必须一致</td></tr>
 *   <tr><td>{@link Visibility}</td>
 *       <td>由 {@code Axis} 纯函数导出（不在表里重复写）</td>
 *       <td>正常显示 / 折叠 / 隐藏 / 冒充标尺</td></tr>
 * </table>
 *
 * <h2>★ 为什么不把 Visibility 也写进表</h2>
 * <p>写进表就会有两个真值源：改 {@code Axis} 忘了改 {@code Visibility} ⇒ 静默不一致。
 * 这里用 {@link #visibilityOf(Axis)} 纯函数导出，<b>不可能漂移</b>。</p>
 *
 * <h2>★ PREVIEW_ONLY 的特殊处置</h2>
 * <p>{@code seaLevel} / {@code minY} / {@code maxY} <b>不驱动 generator</b>
 * （{@code GeoGenesisGenerator} 用编译期常量 -64/320/63）。它们曾摆在「世界高度」分组里
 * 冒充世界设置 —— 用户拖了以为世界变高，实际只有预览标尺动。现归入
 * {@link Visibility#PREVIEW_SCALE}，页签上必须显式写「仅预览标尺，不驱动世界」。</p>
 *
 * <h2>维护铁律</h2>
 * <ul>
 *   <li>新增配置键 ⇒ 必须在本表 {@code put} 一行。漏了 {@code ConfigPanelCoverageProbe} 直接红。</li>
 *   <li>改 {@link GeoGenesisConfig} 的 javadoc 轴标记 ⇒ 必须同批改本表。探针会逐键对比。</li>
 *   <li><b>不要</b>在本类加任何 {@code net.minecraft.*} 依赖：它被 {@code src/main} 的 UI
 *       与 {@code src/diagnostics} 的探针共用，后者没有 Forge 运行时。</li>
 * </ul>
 */
public final class ConfigKeyCatalog {

    /** 算法轴：与 {@link GeoGenesisConfig} 字段 javadoc 首行的 {@code [TAG]} 严格一一对应。 */
    public enum Axis {
        /** 只影响 morph-base-p1 新世界的地形产出。 */
        MORPH_ONLY,
        /** 只影响 legacy 存档（自定义生成器 {@code morphBase=false} 分支）的地形产出。 */
        LEGACY_ONLY,
        /** 两条链都影响。 */
        BOTH,
        /** 只影响日志/诊断/自检，<b>不改任何地形产出</b>。 */
        DIAGNOSTIC,
        /** 只影响预览 UI 的显示尺/色阶；<b>不驱动 {@code GeoGenesisGenerator}</b>。 */
        PREVIEW_ONLY
    }

    /**
     * 域：按<b>消费点</b>划分。页签顺序由 {@code PanelLayout.Tab} 决定（不在本枚举里）。
     *
     * <p>⚠ 域与页签不是一一对应：{@code PRESETS} / {@code CACHE} / {@code BIOMES} 三页没有域
     * （它们是纯 UI，读不到任何配置键）。二者的绑定只发生在
     * {@code PanelLayout.Tab} 的构造参数里，那是唯一的事实源。</p>
     */
    public enum Domain {
        /** 大陆场尺度 + 垂直/水平缩放 + 峰高 —— 决定"世界多大、多高"。 */
        WORLD_SCALE("世界与尺度"),
        /** 温/湿/大陆性阈值 + 气候场尺度 —— 只改群系，不改地形高度。 */
        CLIMATE("气候"),
        /** 海洋样条 / 类型高度区间 / 雪线 —— 改地形形态本身。 */
        TERRAIN_SHAPE("地形形态"),
        /** 粒子侵蚀全套 + 向河让步 + 河流总开关 —— 消费点都在 GeoGenesisTerrain / ProcessContext。 */
        EROSION("侵蚀与河流"),
        /** 洞穴档位与旋钮（挂在 applyCarvers，与地形链无关）。 */
        CAVE("洞穴"),
        /** 矿脉开关（挂在 fillFromNoise + 装饰过滤）。 */
        ORE("矿物"),
        /** 全流程诊断与只读阈值 —— 默认折叠。 */
        DIAGNOSTIC("诊断"),
        /** 仅预览标尺（不驱动世界）。 */
        DISPLAY("显示与色带"),
        /** 旧存档专用（默认隐藏）。 */
        LEGACY("旧算法参数");

        private final String label;
        Domain(String label) { this.label = label; }
        /** 页签显示名（中文）。 */
        public String label() { return label; }
    }

    /**
     * 可见性 —— 由 {@link Axis} 导出，<b>不是</b>独立可写字段（见类注释"为什么不写进表"）。
     */
    public enum Visibility {
        /** 正常显示。 */
        NORMAL,
        /** 归入「诊断」域并默认折叠。 */
        COLLAPSED,
        /** 默认隐藏，需显式打开「显示旧算法参数」才出现。 */
        HIDDEN_LEGACY,
        /** 只决定预览色阶/高度条，<b>不驱动游戏世界</b>。界面必须写明。 */
        PREVIEW_SCALE
    }

    /** 轴 → 可见性的<b>唯一</b>映射点。 */
    public static Visibility visibilityOf(Axis axis) {
        return switch (axis) {
            case BOTH, MORPH_ONLY -> Visibility.NORMAL;
            case DIAGNOSTIC -> Visibility.COLLAPSED;
            case LEGACY_ONLY -> Visibility.HIDDEN_LEGACY;
            case PREVIEW_ONLY -> Visibility.PREVIEW_SCALE;
        };
    }

    /** 一条键的完整档案。 */
    public record Entry(String key, Axis axis, Domain domain) {
        public Visibility visibility() { return visibilityOf(axis); }
        /** 该键是否驱动"新世界"（morph-base-p1）的地形/玩法产出。 */
        public boolean affectsNewWorld() {
            return axis == Axis.BOTH || axis == Axis.MORPH_ONLY;
        }
    }

    private static final Map<String, Entry> BY_KEY;

    static {
        Map<String, Entry> m = new LinkedHashMap<>();
        populate(m);
        BY_KEY = Collections.unmodifiableMap(m);
    }

    private static void put(Map<String, Entry> m, String key, Axis axis, Domain domain) {
        Entry prev = m.put(key, new Entry(key, axis, domain));
        if (prev != null) {
            throw new IllegalStateException("duplicate catalog key: " + key);
        }
    }

    private static void populate(Map<String, Entry> m) {
        // ---- WORLD_SCALE ----
        put(m, "continentScale", Axis.BOTH, Domain.WORLD_SCALE);
        put(m, "continentWarp", Axis.BOTH, Domain.WORLD_SCALE);
        put(m, "continentFbmOctaves", Axis.BOTH, Domain.WORLD_SCALE);
        put(m, "continentFbmLacunarity", Axis.BOTH, Domain.WORLD_SCALE);
        put(m, "continentFbmPersistence", Axis.BOTH, Domain.WORLD_SCALE);
        put(m, "peakHeightFraction", Axis.BOTH, Domain.WORLD_SCALE);
        put(m, "verticalScale", Axis.BOTH, Domain.WORLD_SCALE);
        put(m, "horizontalScale", Axis.BOTH, Domain.WORLD_SCALE);
        // ---- CLIMATE ----
        put(m, "tempFrozenThreshold", Axis.BOTH, Domain.CLIMATE);
        put(m, "tempColdThreshold", Axis.BOTH, Domain.CLIMATE);
        put(m, "tempWarmThreshold", Axis.BOTH, Domain.CLIMATE);
        put(m, "tempHotThreshold", Axis.BOTH, Domain.CLIMATE);
        put(m, "humidityDryThreshold", Axis.BOTH, Domain.CLIMATE);
        put(m, "humiditySemiThreshold", Axis.BOTH, Domain.CLIMATE);
        put(m, "humidityWetThreshold", Axis.BOTH, Domain.CLIMATE);
        put(m, "continentDeepOceanThreshold", Axis.BOTH, Domain.CLIMATE);
        put(m, "continentNearOceanThreshold", Axis.BOTH, Domain.CLIMATE);
        put(m, "continentCoastThreshold", Axis.BOTH, Domain.CLIMATE);
        put(m, "continentTransitionalThreshold", Axis.BOTH, Domain.CLIMATE);
        put(m, "continentNearInlandThreshold", Axis.BOTH, Domain.CLIMATE);
        put(m, "continentInlandThreshold", Axis.BOTH, Domain.CLIMATE);
        put(m, "tempInfluence", Axis.BOTH, Domain.CLIMATE);
        put(m, "humidityInfluence", Axis.BOTH, Domain.CLIMATE);
        put(m, "continentInfluence", Axis.BOTH, Domain.CLIMATE);
        put(m, "latitudeScale", Axis.BOTH, Domain.CLIMATE);
        put(m, "tempWarpScale", Axis.BOTH, Domain.CLIMATE);
        put(m, "humidityScale", Axis.BOTH, Domain.CLIMATE);
        put(m, "climateRegionSize", Axis.BOTH, Domain.CLIMATE);
        // ---- TERRAIN_SHAPE ----
        put(m, "deepOceanLoc", Axis.BOTH, Domain.TERRAIN_SHAPE);
        put(m, "shelfLoc", Axis.BOTH, Domain.TERRAIN_SHAPE);
        put(m, "shallowLoc", Axis.BOTH, Domain.TERRAIN_SHAPE);
        put(m, "deepOceanDepth", Axis.BOTH, Domain.TERRAIN_SHAPE);
        put(m, "shelfDepth", Axis.BOTH, Domain.TERRAIN_SHAPE);
        put(m, "shallowDepth", Axis.BOTH, Domain.TERRAIN_SHAPE);
        put(m, "deepOceanDeriv", Axis.BOTH, Domain.TERRAIN_SHAPE);
        put(m, "shelfDeriv", Axis.BOTH, Domain.TERRAIN_SHAPE);
        put(m, "shallowDeriv", Axis.BOTH, Domain.TERRAIN_SHAPE);
        put(m, "coastDeriv", Axis.BOTH, Domain.TERRAIN_SHAPE);
        put(m, "oceanLo", Axis.BOTH, Domain.TERRAIN_SHAPE);
        put(m, "oceanHi", Axis.BOTH, Domain.TERRAIN_SHAPE);
        put(m, "deepOceanLo", Axis.BOTH, Domain.TERRAIN_SHAPE);
        put(m, "deepOceanHi", Axis.BOTH, Domain.TERRAIN_SHAPE);
        put(m, "shelfLo", Axis.BOTH, Domain.TERRAIN_SHAPE);
        put(m, "shelfHi", Axis.BOTH, Domain.TERRAIN_SHAPE);
        put(m, "subRidgeLo", Axis.BOTH, Domain.TERRAIN_SHAPE);
        put(m, "subRidgeHi", Axis.BOTH, Domain.TERRAIN_SHAPE);
        put(m, "seamountLo", Axis.BOTH, Domain.TERRAIN_SHAPE);
        put(m, "seamountHi", Axis.BOTH, Domain.TERRAIN_SHAPE);
        put(m, "lakeLo", Axis.BOTH, Domain.TERRAIN_SHAPE);
        put(m, "lakeHi", Axis.BOTH, Domain.TERRAIN_SHAPE);
        put(m, "riverLo", Axis.BOTH, Domain.TERRAIN_SHAPE);
        put(m, "riverHi", Axis.BOTH, Domain.TERRAIN_SHAPE);
        put(m, "snowLine", Axis.BOTH, Domain.TERRAIN_SHAPE);
        put(m, "snowLatitudeInfluence", Axis.BOTH, Domain.TERRAIN_SHAPE);
        put(m, "snowHumidityInfluence", Axis.BOTH, Domain.TERRAIN_SHAPE);
        put(m, "splineOuterLoc0", Axis.BOTH, Domain.TERRAIN_SHAPE);
        put(m, "splineOuterLoc1", Axis.BOTH, Domain.TERRAIN_SHAPE);
        put(m, "splineOuterLoc2", Axis.BOTH, Domain.TERRAIN_SHAPE);
        put(m, "splineOuterLoc3", Axis.BOTH, Domain.TERRAIN_SHAPE);
        put(m, "splineOuterLoc4", Axis.BOTH, Domain.TERRAIN_SHAPE);
        put(m, "splineOuterLoc5", Axis.BOTH, Domain.TERRAIN_SHAPE);
        put(m, "splineOuterLoc6", Axis.BOTH, Domain.TERRAIN_SHAPE);
        put(m, "plainHiVal0", Axis.BOTH, Domain.TERRAIN_SHAPE);
        put(m, "hillsHiVal0", Axis.BOTH, Domain.TERRAIN_SHAPE);
        put(m, "mountHiVal0", Axis.BOTH, Domain.TERRAIN_SHAPE);
        put(m, "platHiVal0", Axis.BOTH, Domain.TERRAIN_SHAPE);
        put(m, "basinHiVal0", Axis.BOTH, Domain.TERRAIN_SHAPE);
        // ---- EROSION ----
        put(m, "riverEnabled", Axis.BOTH, Domain.EROSION);
        put(m, "erosionEnabled", Axis.BOTH, Domain.EROSION);
        put(m, "erosionYieldToRiver", Axis.BOTH, Domain.EROSION);
        put(m, "erosionXSEnabled", Axis.BOTH, Domain.EROSION);
        put(m, "erosionStrength", Axis.BOTH, Domain.EROSION);
        put(m, "erosionDropsMul", Axis.BOTH, Domain.EROSION);
        put(m, "erosionErodeMul", Axis.BOTH, Domain.EROSION);
        put(m, "erosionCascadeStrength", Axis.BOTH, Domain.EROSION);
        put(m, "erosionMomentumTransfer", Axis.BOTH, Domain.EROSION);
        put(m, "erosionIterations", Axis.BOTH, Domain.EROSION);
        put(m, "erosionLrate", Axis.BOTH, Domain.EROSION);
        // ---- CAVE ----
        put(m, "cavePreset", Axis.BOTH, Domain.CAVE);
        put(m, "caveEnabled", Axis.BOTH, Domain.CAVE);
        put(m, "caveTunnelEnabled", Axis.BOTH, Domain.CAVE);
        put(m, "caveChamberEnabled", Axis.BOTH, Domain.CAVE);
        put(m, "caveLayerEnabled", Axis.BOTH, Domain.CAVE);
        put(m, "caveDensityMul", Axis.BOTH, Domain.CAVE);
        put(m, "caveSurfaceLid", Axis.BOTH, Domain.CAVE);
        put(m, "caveDepthMin", Axis.BOTH, Domain.CAVE);
        put(m, "caveDepthMax", Axis.BOTH, Domain.CAVE);
        put(m, "caveLithoGating", Axis.BOTH, Domain.CAVE);
        put(m, "caveBiomesEnabled", Axis.BOTH, Domain.CAVE);
        // ---- ORE ----
        put(m, "oreVeinsEnabled", Axis.BOTH, Domain.ORE);
        put(m, "oreOverrideVanilla", Axis.BOTH, Domain.ORE);
        // ---- DIAGNOSTIC ----
        put(m, "elevHigh", Axis.DIAGNOSTIC, Domain.DIAGNOSTIC);
        put(m, "reliefHigh", Axis.DIAGNOSTIC, Domain.DIAGNOSTIC);
        put(m, "worldgenProfilerEnabled", Axis.DIAGNOSTIC, Domain.DIAGNOSTIC);
        put(m, "worldgenProfilerEveryChunks", Axis.DIAGNOSTIC, Domain.DIAGNOSTIC);
        // ---- DISPLAY ----
        put(m, "seaLevel", Axis.PREVIEW_ONLY, Domain.DISPLAY);
        put(m, "minY", Axis.PREVIEW_ONLY, Domain.DISPLAY);
        put(m, "maxY", Axis.PREVIEW_ONLY, Domain.DISPLAY);
        // ---- LEGACY ----
        put(m, "continentThreshold", Axis.LEGACY_ONLY, Domain.LEGACY);
        put(m, "continentBias", Axis.LEGACY_ONLY, Domain.LEGACY);
        put(m, "coastLoc", Axis.LEGACY_ONLY, Domain.LEGACY);
        put(m, "oceanFadeStart", Axis.LEGACY_ONLY, Domain.LEGACY);
        put(m, "landRampEnd", Axis.LEGACY_ONLY, Domain.LEGACY);
        put(m, "seabedDetail", Axis.LEGACY_ONLY, Domain.LEGACY);
        put(m, "oceanDepthFactor", Axis.LEGACY_ONLY, Domain.LEGACY);
        put(m, "beltReliefAmp", Axis.LEGACY_ONLY, Domain.LEGACY);
        put(m, "basinBase", Axis.LEGACY_ONLY, Domain.LEGACY);
        put(m, "cAffinityStrength", Axis.LEGACY_ONLY, Domain.LEGACY);
        put(m, "coastlineWarpAmp", Axis.LEGACY_ONLY, Domain.LEGACY);
        put(m, "coastlineWarpScale", Axis.LEGACY_ONLY, Domain.LEGACY);
        put(m, "coastlineWarpOctaves", Axis.LEGACY_ONLY, Domain.LEGACY);
        put(m, "coastlineWarpLacunarity", Axis.LEGACY_ONLY, Domain.LEGACY);
        put(m, "coastlineWarpPersistence", Axis.LEGACY_ONLY, Domain.LEGACY);
        put(m, "plainLoLoc0", Axis.LEGACY_ONLY, Domain.LEGACY);
        put(m, "plainLoVal0", Axis.LEGACY_ONLY, Domain.LEGACY);
        put(m, "plainLoDeriv0", Axis.LEGACY_ONLY, Domain.LEGACY);
        put(m, "plainLoLoc1", Axis.LEGACY_ONLY, Domain.LEGACY);
        put(m, "plainLoVal1", Axis.LEGACY_ONLY, Domain.LEGACY);
        put(m, "plainLoDeriv1", Axis.LEGACY_ONLY, Domain.LEGACY);
        put(m, "plainHiLoc0", Axis.LEGACY_ONLY, Domain.LEGACY);
        put(m, "plainHiDeriv0", Axis.LEGACY_ONLY, Domain.LEGACY);
        put(m, "plainHiLoc1", Axis.LEGACY_ONLY, Domain.LEGACY);
        put(m, "plainHiVal1", Axis.LEGACY_ONLY, Domain.LEGACY);
        put(m, "plainHiDeriv1", Axis.LEGACY_ONLY, Domain.LEGACY);
        put(m, "hillsLoLoc0", Axis.LEGACY_ONLY, Domain.LEGACY);
        put(m, "hillsLoVal0", Axis.LEGACY_ONLY, Domain.LEGACY);
        put(m, "hillsLoDeriv0", Axis.LEGACY_ONLY, Domain.LEGACY);
        put(m, "hillsLoLoc1", Axis.LEGACY_ONLY, Domain.LEGACY);
        put(m, "hillsLoVal1", Axis.LEGACY_ONLY, Domain.LEGACY);
        put(m, "hillsLoDeriv1", Axis.LEGACY_ONLY, Domain.LEGACY);
        put(m, "hillsHiLoc0", Axis.LEGACY_ONLY, Domain.LEGACY);
        put(m, "hillsHiDeriv0", Axis.LEGACY_ONLY, Domain.LEGACY);
        put(m, "hillsHiLoc1", Axis.LEGACY_ONLY, Domain.LEGACY);
        put(m, "hillsHiVal1", Axis.LEGACY_ONLY, Domain.LEGACY);
        put(m, "hillsHiDeriv1", Axis.LEGACY_ONLY, Domain.LEGACY);
        put(m, "mountLoLoc0", Axis.LEGACY_ONLY, Domain.LEGACY);
        put(m, "mountLoVal0", Axis.LEGACY_ONLY, Domain.LEGACY);
        put(m, "mountLoDeriv0", Axis.LEGACY_ONLY, Domain.LEGACY);
        put(m, "mountLoLoc1", Axis.LEGACY_ONLY, Domain.LEGACY);
        put(m, "mountLoVal1", Axis.LEGACY_ONLY, Domain.LEGACY);
        put(m, "mountLoDeriv1", Axis.LEGACY_ONLY, Domain.LEGACY);
        put(m, "mountHiLoc0", Axis.LEGACY_ONLY, Domain.LEGACY);
        put(m, "mountHiDeriv0", Axis.LEGACY_ONLY, Domain.LEGACY);
        put(m, "mountHiLoc1", Axis.LEGACY_ONLY, Domain.LEGACY);
        put(m, "mountHiVal1", Axis.LEGACY_ONLY, Domain.LEGACY);
        put(m, "mountHiDeriv1", Axis.LEGACY_ONLY, Domain.LEGACY);
        put(m, "platLoLoc0", Axis.LEGACY_ONLY, Domain.LEGACY);
        put(m, "platLoVal0", Axis.LEGACY_ONLY, Domain.LEGACY);
        put(m, "platLoDeriv0", Axis.LEGACY_ONLY, Domain.LEGACY);
        put(m, "platLoLoc1", Axis.LEGACY_ONLY, Domain.LEGACY);
        put(m, "platLoVal1", Axis.LEGACY_ONLY, Domain.LEGACY);
        put(m, "platLoDeriv1", Axis.LEGACY_ONLY, Domain.LEGACY);
        put(m, "platHiLoc0", Axis.LEGACY_ONLY, Domain.LEGACY);
        put(m, "platHiDeriv0", Axis.LEGACY_ONLY, Domain.LEGACY);
        put(m, "platHiLoc1", Axis.LEGACY_ONLY, Domain.LEGACY);
        put(m, "platHiVal1", Axis.LEGACY_ONLY, Domain.LEGACY);
        put(m, "platHiDeriv1", Axis.LEGACY_ONLY, Domain.LEGACY);
        put(m, "basinLoLoc0", Axis.LEGACY_ONLY, Domain.LEGACY);
        put(m, "basinLoVal0", Axis.LEGACY_ONLY, Domain.LEGACY);
        put(m, "basinLoDeriv0", Axis.LEGACY_ONLY, Domain.LEGACY);
        put(m, "basinLoLoc1", Axis.LEGACY_ONLY, Domain.LEGACY);
        put(m, "basinLoVal1", Axis.LEGACY_ONLY, Domain.LEGACY);
        put(m, "basinLoDeriv1", Axis.LEGACY_ONLY, Domain.LEGACY);
        put(m, "basinHiLoc0", Axis.LEGACY_ONLY, Domain.LEGACY);
        put(m, "basinHiDeriv0", Axis.LEGACY_ONLY, Domain.LEGACY);
        put(m, "basinHiLoc1", Axis.LEGACY_ONLY, Domain.LEGACY);
        put(m, "basinHiVal1", Axis.LEGACY_ONLY, Domain.LEGACY);
        put(m, "basinHiDeriv1", Axis.LEGACY_ONLY, Domain.LEGACY);
    }

    /** 全部键档案（不可变，按声明顺序）。 */
    public static Map<String, Entry> all() { return BY_KEY; }

    /** 单键档案；未知键返回 {@code null}（调用方须显式处理"面板写了不存在的键"）。 */
    public static Entry of(String key) { return BY_KEY.get(key); }

    public static Axis axisOf(String key) {
        Entry e = BY_KEY.get(key);
        return e == null ? null : e.axis();
    }

    public static Visibility visibilityOf(String key) {
        Entry e = BY_KEY.get(key);
        return e == null ? null : e.visibility();
    }

    /** 域声明顺序（仅供报告与遍历；<b>不是</b>页签顺序 —— 那个看 {@code PanelLayout.Tab}）。 */
    public static Domain[] domainsInTabOrder() { return Domain.values(); }

    /** 统计：某域有多少键（供页面标题与探针报告）。 */
    public static int countIn(Domain domain) {
        int n = 0;
        for (Entry e : BY_KEY.values()) if (e.domain() == domain) n++;
        return n;
    }

    public static int countAxis(Axis axis) {
        int n = 0;
        for (Entry e : BY_KEY.values()) if (e.axis() == axis) n++;
        return n;
    }

    private ConfigKeyCatalog() { }
}
