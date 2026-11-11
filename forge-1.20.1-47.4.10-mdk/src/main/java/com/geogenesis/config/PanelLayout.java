package com.geogenesis.config;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * <b>配置屏的页签布局 —— 与面板实例化顺序的唯一事实源</b>（零 MC 依赖）。
 *
 * <h2>它解决什么</h2>
 * <p>旧实现有两处平行列表：{@code GeoGenesisConfigScreen.TAB_NAMES}（字符串数组）与
 * {@code panels = new ConfigPanel[]{...}}（实例数组），靠一句注释「顺序必须严格对齐」维系。
 * 这种"两个数组 + 注释"的组合一旦错位，<b>点「地形」显示的是洞穴面板</b>，
 * 而且不会抛任何异常 —— 只能靠人眼发现。</p>
 * <p>现在两个数组都从 {@link Tab} 派生：页签名 = {@code Tab.label()}，
 * 面板 = {@code Tab} 一一对应构造。{@code ConfigPanelCoverageProbe} 再静态校验
 * 「{@code Tab.values().length} == 被构造的面板数」，错位在构建期就红。</p>
 *
 * <h2>★ 每个页签必须声明它显示哪些配置键</h2>
 * <p>这是"面板覆盖"可被机器检查的前提：探针据此断言
 * ① 这里列的每个键都真实存在且在 {@link ConfigKeyCatalog} 里；
 * ② 每个 {@code BOTH}/{@code MORPH_ONLY} 键至少出现在一个<b>非旧算法</b>页签里；
 * ③ 源码里引用到的每个键也都在某个页签里（否则就是"面板偷偷显示了一行"）。</p>
 *
 * <h2>★ 按域划分，不按旧页签</h2>
 * <p>旧页签是历史堆积的结果：{@code 世界参数} 里混着海陆偏置（旧算法）、侵蚀开关、
 * 世界高度标尺（其实是预览标尺）。现在每页 = 一个<b>消费点一致</b>的域。
 * 详见 {@link ConfigKeyCatalog.Domain}。</p>
 */
public final class PanelLayout {

    /**
     * 页签（声明顺序 = 显示顺序 = 面板实例化顺序）。
     *
     * <p>{@code requiresLegacyMode=true} 的页签默认不出现在标签条上，
     * 只有用户在「诊断」页打开「显示旧算法参数」后才出现。
     * ★ 2026-11-08：legacy 链与整条样条链已删除，{@code Domain.LEGACY} 现为空域，
     * 该页签打开后也是空页；保留机制是为了不扩大本轮改动范围，后续可整体移除。</p>
     */
    public enum Tab {
        PRESETS("预设", "预设", null, false),
        WORLD_SCALE("世界与尺度", "尺度", ConfigKeyCatalog.Domain.WORLD_SCALE, false),
        CLIMATE("气候", "气候", ConfigKeyCatalog.Domain.CLIMATE, false),
        TERRAIN_SHAPE("地形形态", "地形", ConfigKeyCatalog.Domain.TERRAIN_SHAPE, false),
        EROSION("侵蚀与河流", "侵蚀", ConfigKeyCatalog.Domain.EROSION, false),
        CAVE("洞穴", "洞穴", ConfigKeyCatalog.Domain.CAVE, false),
        ORE("矿物", "矿物", ConfigKeyCatalog.Domain.ORE, false),
        DIAGNOSTIC("诊断", "诊断", ConfigKeyCatalog.Domain.DIAGNOSTIC, false),
        DISPLAY("显示与色带", "显示", ConfigKeyCatalog.Domain.DISPLAY, false),
        CACHE("缓存", "缓存", null, false),
        BIOMES("群系", "群系", null, false),
        LEGACY("旧算法参数", "旧参数", ConfigKeyCatalog.Domain.LEGACY, true);

        private final String label;
        private final String shortLabel;
        private final ConfigKeyCatalog.Domain domain;
        private final boolean requiresLegacyMode;

        Tab(String label, String shortLabel, ConfigKeyCatalog.Domain domain, boolean requiresLegacyMode) {
            this.label = label;
            this.shortLabel = shortLabel;
            this.domain = domain;
            this.requiresLegacyMode = requiresLegacyMode;
        }

        /** 标签条上的显示名。 */
        public String label() { return label; }

        /**
         * 窄导航栏（高 GUI Scale / 小窗口）里使用的两字简称。
         *
         * <p>1080p + Scale 4 时逻辑宽度只有 480，导航栏宽 130 —— 两列排布每列仅 ~54px，
         * 「侵蚀与河流」这类五字标签（45px）加缩进就溢出。全称在宽屏下照旧。</p>
         */
        public String shortLabel() { return shortLabel; }

        /** 该页以目录驱动渲染的域；{@code null} = 专门手工 UI（预设/缓存/群系）。 */
        public ConfigKeyCatalog.Domain domain() { return domain; }

        /** 是否只在「显示旧算法参数」开启后出现。 */
        public boolean requiresLegacyMode() { return requiresLegacyMode; }
    }

    /**
     * 由专门 UI 手工渲染的键（不在目录驱动滑块里出现）。
     *
     * <p>⚠ 这三组必须与对应面板的源码一致 —— 探针做双向校验：
     * 这里列了但面板源码没引用 = 谎报；面板源码引用了但这里没列 = 漏报。两者都 FAIL。</p>
     * <ul>
     *   <li>{@code CLIMATE}：{@code ClimateConfigPanel} 的分类色条 + 影响滑块 + 缩放滑块；</li>
     *   <li>{@code TERRAIN_SHAPE}：{@code TerrainConfigPanel} 的高度图控制点 + 类型 lo/hi 滑块 + 雪线双曲线；</li>
     *   <li>{@code CAVE} / {@code ORE}：{@code CavePanel} / {@code OrePanel} 的档位按钮与开关行。</li>
     * </ul>
     */
    private static final Set<String> SPECIALIZED_CLIMATE = setOf(
            // 温度 4 边界 + 湿度 3 边界 + 大陆性 6 边界
            "tempFrozenThreshold", "tempColdThreshold", "tempWarmThreshold", "tempHotThreshold",
            "humidityDryThreshold", "humiditySemiThreshold", "humidityWetThreshold",
            "continentDeepOceanThreshold", "continentNearOceanThreshold", "continentCoastThreshold",
            "continentTransitionalThreshold", "continentNearInlandThreshold", "continentInlandThreshold",
            // 三个影响权重
            "tempInfluence", "humidityInfluence", "continentInfluence",
            // 气候缩放
            "latitudeScale", "tempWarpScale", "humidityScale", "climateRegionSize");

    /**
     * ★ 2026-11-07：TERRAIN_SHAPE 页已从专用 {@code TerrainConfigPanel}（高度图控制点 +
     * 类型 lo/hi 滑块 + 雪线双曲线）改为目录驱动的通用 {@code DomainPanel}。
     * 那批样条键随 legacy 链删除，此页现在只承载 {@code Domain.TERRAIN_SHAPE} 的剩余键
     * （海洋控制点等，由 {@code HeightCurve} 真实消费），全部由目录渲染 ⇒ 专门集合为空。
     */
    private static final Set<String> SPECIALIZED_TERRAIN = Set.of();

    private static final Set<String> SPECIALIZED_CAVE = setOf(
            "cavePreset", "caveEnabled", "caveTunnelEnabled", "caveChamberEnabled",
            "caveLayerEnabled", "caveDensityMul", "caveSurfaceLid",
            "caveDepthMin", "caveDepthMax", "caveLithoGating", "caveBiomesEnabled");

    private static final Set<String> SPECIALIZED_ORE = setOf("oreVeinsEnabled", "oreOverrideVanilla");

    /**
     * 「显示与色带」页的 {@code PREVIEW_ONLY} 三键 —— 由 {@code ParameterConfigPanel}（预览标尺）
     * 手工渲染，因为它必须配高度条图形，并且每一行都要写「仅预览标尺，不驱动世界」。
     */
    private static final Set<String> SPECIALIZED_DISPLAY = setOf("seaLevel", "minY", "maxY");

    /** 该页由专门 UI 渲染的键集合（目录驱动的部分 = 域内键 − 本集合）。 */
    public static Set<String> specializedKeys(Tab tab) {
        return switch (tab) {
            case CLIMATE -> SPECIALIZED_CLIMATE;
            case TERRAIN_SHAPE -> SPECIALIZED_TERRAIN;
            case CAVE -> SPECIALIZED_CAVE;
            case ORE -> SPECIALIZED_ORE;
            case DISPLAY -> SPECIALIZED_DISPLAY;
            default -> Set.of();
        };
    }

    /**
     * 该页在目录驱动面板里显示的键（= 域内全部键 − 专门 UI 已渲染的键）。
     * 非目录驱动页返回空集。
     */
    public static Set<String> domainDrivenKeys(Tab tab) {
        ConfigKeyCatalog.Domain d = tab.domain();
        if (d == null) return Set.of();
        Set<String> specialized = specializedKeys(tab);
        Set<String> out = new LinkedHashSet<>();
        for (ConfigKeyCatalog.Entry e : ConfigKeyCatalog.all().values()) {
            if (e.domain() != d) continue;
            if (specialized.contains(e.key())) continue;
            out.add(e.key());
        }
        return out;
    }

    /** 该页显示的全部键（专门 UI + 目录驱动）。 */
    public static Set<String> allKeys(Tab tab) {
        Set<String> out = new LinkedHashSet<>(specializedKeys(tab));
        out.addAll(domainDrivenKeys(tab));
        return out;
    }

    /** 当前应显示的页签（按 {@code showLegacy} 过滤）。 */
    public static List<Tab> visibleTabs(boolean showLegacy) {
        List<Tab> out = new ArrayList<>();
        for (Tab t : Tab.values()) {
            if (t.requiresLegacyMode() && !showLegacy) continue;
            out.add(t);
        }
        return out;
    }

    /** 全部键是否都被某个可见页签覆盖（{@code showLegacy=false} ⇒ 旧算法键不算覆盖）。 */
    public static List<String> uncoveredKeys(boolean showLegacy) {
        Set<String> shown = new LinkedHashSet<>();
        for (Tab t : visibleTabs(showLegacy)) shown.addAll(allKeys(t));
        List<String> missing = new ArrayList<>();
        for (String k : ConfigKeyCatalog.all().keySet()) {
            if (!shown.contains(k)) missing.add(k);
        }
        return missing;
    }

    private static Set<String> setOf(String... keys) {
        Set<String> s = new LinkedHashSet<>();
        for (String k : keys) s.add(k);
        return java.util.Collections.unmodifiableSet(s);
    }

    private PanelLayout() { }
}
