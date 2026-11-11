package com.geogenesis.client;

import com.geogenesis.client.preview.BiomesPanel;
import com.geogenesis.client.preview.CachePanel;
import com.geogenesis.client.preview.CavePanel;
import com.geogenesis.client.preview.ClimateConfigPanel;
import com.geogenesis.client.preview.ColormapPanel;
import com.geogenesis.client.preview.CompositePanel;
import com.geogenesis.client.preview.ConfigPanel;
import com.geogenesis.client.preview.DiagnosticPanel;
import com.geogenesis.client.preview.DisplayPanel;
import com.geogenesis.client.preview.DomainPanel;
import com.geogenesis.client.preview.OrePanel;
import com.geogenesis.client.preview.ParameterConfigPanel;
import com.geogenesis.client.preview.Preset;
import com.geogenesis.client.preview.PresetsPanel;
import com.geogenesis.client.preview.PreviewDisplay;
import com.geogenesis.client.preview.SamplingPanel;
import com.geogenesis.client.preview.GeoPalette;
import com.geogenesis.client.preview.UiKit;
import com.geogenesis.client.preview.WorldScalePanel;
import com.geogenesis.config.GeoGenesisConfig;
import com.geogenesis.config.PanelLayout;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;
import com.geogenesis.worldgen.terrain.TerrainParams;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.List;

/**
 * GeoGenesis 配置屏 —— <b>页签由 {@link PanelLayout} 驱动</b>。
 *
 * <h2>★ 2026-11-07 重构：为什么页签名与面板不再是两个平行数组</h2>
 * <p>旧实现同时存在 {@code private static final String[] TAB_NAMES}（页签名）与
 * {@code panels = new ConfigPanel[]{...}}（面板实例），靠一句注释「顺序必须严格对齐」维系。
 * 两者错位时<b>不抛任何异常</b> —— 表现是「点『地形』弹出洞穴面板」，只能靠人眼发现。</p>
 * <p>现在两者都从 {@link PanelLayout.Tab} 派生：页签名 = {@code tab.label()}，
 * 面板按 {@code Tab} 枚举顺序构造（{@link #panelFor} 是<b>唯一</b>映射点），
 * 构造完再跑一次<b>硬校验</b>（不是 {@code assert} —— Java 默认关断言，实机等于没有）。
 * {@code ConfigPanelCoverageProbe} 另外做静态校验，错位在构建期就红。</p>
 *
 * <h2>★ 可见性规则（不是人工判断，由算法轴导出）</h2>
 * <ul>
 *   <li>{@code BOTH} / {@code MORPH_ONLY} → 正常显示（这些真的驱动新世界）；</li>
 *   <li>{@code DIAGNOSTIC} → 归入「诊断」页，默认折叠语义；</li>
 *   <li>{@code PREVIEW_ONLY}（seaLevel/minY/maxY）→ 明示「仅预览标尺，不驱动世界」，
 *       不再放在任何冒充世界设置的分组里；</li>
 *   <li>{@code LEGACY_ONLY}（70 个）→ <b>默认整页隐藏</b>，只有「诊断」页打开
 *       「显示旧算法参数」才出现，且整页标注「仅旧存档生效」。</li>
 * </ul>
 * <p>映射点是 {@code ConfigKeyCatalog.visibilityOf(Axis)} —— 纯函数，不可能与轴漂移。</p>
 */
public class GeoGenesisConfigScreen extends Screen {

    private static final Logger LOGGER = LogManager.getLogger("geogenesis");
    private static final int DEFAULT_PREVIEW_LAYER = GeoPalette.PreviewLayer.ELEVATION.ordinal();

    private final Screen parent;
    /** 游戏内打开时只读：禁用配置开关，防止修改导致已生成地形断裂（2026-08-10）。 */
    private final boolean readOnly;
    /** 预览种子。固定默认值 12345L，用户可通过预设面板的 ★ 按钮手动随机化。 */
    private long seed = 12345L;

    private int tab = 0;
    /**
     * 是否显示「旧算法参数」页签。
     *
     * <p>默认 {@code false}：用户已裁定没有 legacy 存档 ⇒ 那 70 个键对新世界零效果，
     * 常驻显示只会制造「转了没反应」的困惑。开关本体在「诊断」页。</p>
     *
     * <p>⚠ 这是<b>界面偏好</b>，不写进 TOML —— 它是"我想不想看见"，不是"世界怎么生成"。
     * 写进配置会变成又一个「UI 开关改了产出不变」的假键。</p>
     */
    private boolean showLegacyParams = false;

    /** 标签条配色（仿设置页 TabNavigationBar 原生形态 + 主屏深绿主题） */
    private static final int TAB_H = 24;
    private static final int TAB_BAR_BG = 0xFF161A22;
    private static final int TAB_PILL_BG = 0xFF2a3340;
    private static final int TAB_PILL_HOVER = 0xFF353c48;
    private static final int TAB_SEL_BG = 0xFF1E1E1E;
    private static final int TAB_SEL_HOVER = 0xFF3A3A3A;
    private static final int TAB_SEL_TOP = 0xFF48DE56;
    private static final int TAB_INACTIVE_TEXT = 0xFF9aa0aa;
    private static final int C_TEXT = 0xFFe0e0e0;
    private static final int C_LINE = 0xFF333333;
    private static final int TAB_PILL_R = 4;

    private final ConfirmDialog confirmDialog = new ConfirmDialog();
    private final NameInputDialog nameDialog = new NameInputDialog();

    /** 目录驱动的域面板（每页一个，构造后不再变）。 */
    private final WorldScalePanel worldScalePanel = new WorldScalePanel();
    private final DomainPanel erosionPanel =
            new DomainPanel(PanelLayout.Tab.EROSION, "侵蚀与河流",
                    "粒子侵蚀是本项目唯一的侵蚀实现；河流由同一份汇流场雕刻。");
    private final ClimateConfigPanel climatePanel = new ClimateConfigPanel();
    /** ★ 2026-11-07：原 TerrainConfigPanel（高度图控制点 + 陆地类型 lo/hi 滑块）随样条链删除。
     *  本页现在只承载海洋样条键（{@code HeightCurve} 真实消费），改用目录驱动的通用面板。 */
    private final DomainPanel terrainPanel =
            new DomainPanel(PanelLayout.Tab.TERRAIN_SHAPE, "地形形态",
                    "海洋控制点由 HeightCurve 真实消费；陆地类型高度带已随样条链移除。");
    private final CavePanel cavePanel = new CavePanel();
    private final OrePanel orePanel = new OrePanel();
    private final DiagnosticPanel diagnosticPanel =
            new DiagnosticPanel(() -> showLegacyParams, v -> setShowLegacyParams(v));
    private final DomainPanel legacyPanel =
            new DomainPanel(PanelLayout.Tab.LEGACY, "旧算法参数",
                    "⚠ 这些旋钮只对旧存档（legacy 地形链）生效；新世界走 morph-base-p1，改它们没有效果。");
    private final ParameterConfigPanel paramPanel = new ParameterConfigPanel();

    // 设置类页签面板（运行时显示状态，懒构造以保留列表滚动位置）
    private DisplayPanel displayPanel;
    private SamplingPanel samplingPanel;
    private ColormapPanel colormapPanel;
    private CachePanel cachePanel;
    private BiomesPanel biomesPanel;
    private PresetsPanel presetsPanel;

    /** 当前可见页签（随 {@link #showLegacyParams} 变化）。 */
    private List<PanelLayout.Tab> visibleTabs = PanelLayout.visibleTabs(false);
    /** 与 {@link #visibleTabs} 一一对应的面板实例。 */
    private ConfigPanel[] panels;

    private PreviewDisplay preview;
    /**
     * ★ 2026-11-09 重构：配置窗的<b>唯一</b>所有者。
     *
     * <p>底板 / 页签 / 内容 / 命中全部在它里面，<b>共用同一套坐标</b> ——
     * 重构前这三处各自算坐标，导致"画在一处、点判在另一处"，实测互相重叠打架。</p>
     */
    private final ConfigPanelHost panelHost = new ConfigPanelHost();
    /**
     * ★ 2026-11-09 B3：两个视图的<b>统一入口</b>。
     *
     * <p>用户原话：「明明我都说了要重构重构，结果各自完全独立分裂。」</p>
     * <p>★ v24：中间层（PreviewController / PreviewState / TerrainSnapshot /
     * TerrainRasterizer / PlaneRasterizer）已全部删除。
     * 现在模式状态直接在 {@code preview}（{@code PreviewDisplay}）上，
     * 两种视图共用<b>同一份 CellCache 数据</b>，只是画法不同。</p>
     */
    /**
     * ★ 2026-11-09 v7：这几个按钮已改为<b>自绘</b>（不再注册为 MC widget）。
     *
     * <p>原因：{@code Button} 是固定灰框 + 立体阴影，是"与示意图差距大"最显眼的一处。
     * 现在只保留<b>状态</b>（当前位置/标签/是否可用），绘制与命中见
     * {@link #drawActionButtons} 与 {@link #hitActionButton}。</p>
     */
    private int actionBtnY, navBtnY, navBtnW;
    /** 内容面板顶栏「应用」按钮的 x/宽（每次 init 后随面板宽度重算）。 */
    private int applyBtnX, applyBtnW;
    /** 「完成」按钮几何（★ v21：绘制与命中共用）。 */
    private int doneBtnX, doneBtnW_, doneBtnH_, applyBtnH_;
    private int currentMode = DEFAULT_PREVIEW_LAYER;

    // ---- ★ 2026-11-09 预览 → 世界复用（见 GeoGenesisGenerator.offerPreviewTerrain）----
    /** 当前预览用的 terrain 实例（自建或共享存档单例）。 */
    private GeoGenesisTerrain previewTerrain;
    /** 该实例对应的参数（接管前必须全等校验）。 */
    private TerrainParams previewParams;
    /** 该实例对应的 morphBase（同上）。 */
    private boolean previewMorphBase;
    /** 该实例是否预览自建（false = 共享存档单例，不可交出）。 */
    private boolean previewOwnsTerrain;
    /** 是否已把实例登记为可接管（诊断/避免重复登记）。 */
    private boolean offeredForReuse;

    private int panelX, panelW, headerY, listTop, listBottom;
    /** 底部按钮区高度（0 = 无按钮）—— 与按钮创建条件同源。 */
    private int bottomBarH;
    private int previewX, previewY, previewW, previewH;
    private int scroll;
    private boolean dirty, saved;
    private int debounce;
    private boolean diagFirstFrame = true;
    private boolean previewRegistered = false;
    private TerrainParams lastAppliedParams = null;
    private long lastAppliedSeed = -1;
    private net.minecraft.client.gui.screens.worldselection.WorldCreationContext worldCtx;

    // ------------------------------------------------------------------
    // ★ 2026-11-08 全屏地图 + 左侧悬浮配置窗
    // ------------------------------------------------------------------

    /**
     * 配置窗是否展开。
     *
     * <p>默认展开：首次进来要看到参数，否则用户找不到入口。状态记在客户端偏好里
     * （{@code SiteEditorPrefs} 同款做法），<b>不写进 TOML</b> —— 它是"我想不想看见"，
     * 不是"世界怎么生成"，写进配置会变成又一个假键。</p>
     */
    private boolean panelOpen = true;

    /** 配置窗展开时的宽度（像素）。足够放下滑块与页签。 */
    private static final int PANEL_W_OPEN = 480;
    /** 配置窗收起时只剩这个宽度的把手。 */
    private static final int PANEL_W_CLOSED = 76;
    /** 配置窗与屏幕边缘的外边距。 */
    private static final int PANEL_MARGIN = 8;

    /**
     * 配置窗的<b>宽度上下限</b>（★ 2026-11-09）。
     *
     * <p>用户反馈「GUI 太大，占满屏幕」⇒ 限宽。480 是实测能放下
     * 「标签 + 滑块 + 数值」的最小舒适宽度；520 是再宽就显得空。</p>
     */
    private static final int PANEL_MIN_W = 420;
    private static final int PANEL_MAX_W = 520;

    /**
     * 配置窗的<b>高度上下限</b>（★ 2026-11-09）。
     *
     * <p>旧实现在 1080p 下算到 1032px（几乎顶满）；这里限到 620，
     * 让四周留出地形 —— 视觉上像「浮在世界上的一个窗口」，而不是全屏面板。</p>
     */
    private static final int PANEL_MIN_H = 320;
    private static final int PANEL_MAX_H = 620;

    // ---- ★ 2026-11-09 v6 布局：左侧导航栏 + 右侧内容面板 ----
    /** 导航栏宽度。 */
    private static final int NAV_W = 196;
    /** 内容面板宽度。 */
    private static final int CONTENT_W = 384;
    /** 导航栏左边界（恒 0）。 */
    private int navX;
    /** 导航栏宽度（= NAV_W，运行时缓存）。 */
    private int navW;
    /** 导航栏页签列表顶部 y。 */
    private int navTabTop;
    /** 导航栏每行页签高度。 */
    private int navTabH = 32;
    /**
     * 配置窗底部按钮区高度。
     *
     * <p>★ 2026-11-08 修正：原本同一行并排「应用/保存/重置 + 图层 + 大范围」，
     * 沙盘按钮只能挤到左上一行 —— 而那行在滚动区里，被面板内容盖住，
     * 实测「根本看不到沙盘按钮」。现在改为两行：第一行三个按钮，
     * 第二行图层箭头 + 大范围 + 平面/沙盘。</p>
     */
    private static final int BOTTOM_BAR_H = 52;

    /** 配置窗的屏幕矩形（每帧由 {@link #computePanelRect} 算出，供绘制与命中测试共用）。 */
    private int cfgX, cfgY, cfgW, cfgH;

    public GeoGenesisConfigScreen(Screen parent) {
        super(Component.literal("GeoGenesis 配置"));
        this.parent = parent;
        this.readOnly = !(parent instanceof net.minecraft.client.gui.screens.worldselection.CreateWorldScreen);
    }
    /** 带种子的构造器：创建世界时传随机种子，游戏中传当前世界种子。 */
    public GeoGenesisConfigScreen(Screen parent, long seed) {
        this(parent, seed, null);
    }
    /** 带种子 + 创建世界上下文（结构自动检测用）：创建世界界面传 ctx，游戏内传 null。 */
    public GeoGenesisConfigScreen(Screen parent, long seed,
                                  net.minecraft.client.gui.screens.worldselection.WorldCreationContext ctx) {
        super(Component.literal("GeoGenesis 配置"));
        this.parent = parent;
        this.seed = seed;
        this.worldCtx = ctx;
        this.readOnly = !(parent instanceof net.minecraft.client.gui.screens.worldselection.CreateWorldScreen);
    }
    public GeoGenesisConfigScreen(Screen parent, int tab) {
        super(Component.literal("GeoGenesis 配置"));
        this.parent = parent;
        this.readOnly = !(parent instanceof net.minecraft.client.gui.screens.worldselection.CreateWorldScreen);
        this.tab = Math.max(0, tab);
    }
    public GeoGenesisConfigScreen() { this(null); }

    // ------------------------------------------------------------------ 可见性

    /** 「显示旧算法参数」开关：切页签集合、夹紧当前页、重建面板数组。 */
    private void setShowLegacyParams(boolean on) {
        if (showLegacyParams == on) return;
        PanelLayout.Tab cur = currentTab();
        showLegacyParams = on;
        rebuildTabs(cur);
        LOGGER.info("[Config] showLegacyParams={} → {} 个页签", on, visibleTabs.size());
    }

    private PanelLayout.Tab currentTab() {
        return visibleTabs.get(Math.min(tab, visibleTabs.size() - 1));
    }

    /** 重建可见页签 + 面板数组，并保持"仍停在同一页"。 */
    private void rebuildTabs(PanelLayout.Tab keep) {
        visibleTabs = PanelLayout.visibleTabs(showLegacyParams);
        List<ConfigPanel> list = new ArrayList<>();
        for (PanelLayout.Tab t : visibleTabs) list.add(panelFor(t));
        panels = list.toArray(new ConfigPanel[0]);
        // ★ 硬校验（不是 assert）：Java 默认<b>关闭</b> assert（MC 不会带 -ea），
        //   所以 `assert` 在实机等于没有。旧实现的"两个平行数组"正是靠一句注释维系，
        //   错位不抛异常、只表现为「点地形弹出洞穴面板」。这里必须在生产也真的炸。
        if (panels.length != visibleTabs.size()) {
            throw new IllegalStateException("页签名/面板实例数错位：tabs=" + visibleTabs.size()
                    + " panels=" + panels.length);
        }
        int idx = visibleTabs.indexOf(keep);
        tab = idx >= 0 ? idx : Math.min(tab, visibleTabs.size() - 1);
        scroll = 0;
        layoutPanels();
    }

    /** 页签 → 面板实例。<b>唯一</b>映射点（旧实现有两个平行数组）。 */
    private ConfigPanel panelFor(PanelLayout.Tab t) {
        return switch (t) {
            case PRESETS -> presetsPanel;
            case WORLD_SCALE -> worldScalePanel;
            case CLIMATE -> climatePanel;
            case TERRAIN_SHAPE -> terrainPanel;
            case EROSION -> erosionPanel;
            case CAVE -> cavePanel;
            case ORE -> orePanel;
            case DIAGNOSTIC -> diagnosticPanel;
            // ★「显示与色带」= 预览标尺（目录驱动，明确标注"不驱动世界"）+ 显示 + 采样 + 色带
            case DISPLAY -> new CompositePanel(paramPanel, displayPanel, samplingPanel, colormapPanel);
            case CACHE -> cachePanel;
            case BIOMES -> biomesPanel;
            case LEGACY -> legacyPanel;
        };
    }

    /**
     * 计算配置窗的屏幕矩形（★ 2026-11-09 v6 布局：左侧导航栏 + 右侧内容面板）。
     *
     * <h2>布局（照用户认可的示意图）</h2>
     * <pre>
     *   ┌──────────────┬─────────────────┐      其余区域 = 全屏地形
     *   │ 导航栏 196px  │ 内容面板 384px   │
     *   │ · 品牌       │ · 顶栏(标题+应用) │
     *   │ · 页签竖排    │ · 内容（滚动）    │
     *   │ · 底部迷你条  │                 │
     *   └──────────────┴─────────────────┘
     * </pre>
     *
     * <p>两块分开算：{@code navX/navW} 与 {@code cfgX/cfgW}。
     * 不再"面板居中"（用户否掉），也不再"贴满全屏"。</p>
     */
    private void computePanelRect() {
        int h = this.height;
        int w = this.width;
        if (!panelOpen) {
            cfgW = PANEL_W_CLOSED;
            cfgX = PANEL_MARGIN;
            cfgY = PANEL_MARGIN;
            cfgH = 24;
            return;
        }
        // ★★ 2026-11-09【修"比例严重失调"】：
        //   之前写死 NAV_W=196 / CONTENT_W=384 是<b>像素</b>。
        //   但 MC 有 GUI Scale —— 1080p + scale=2 时逻辑宽度只有 960，
        //   196+384=580 就占了 60%（用户截图里导航栏占 28%、内容面板占 42%）。
        //   示意图是 1600 宽下画的，比例 NAV 12% / CONTENT 24%。
        //
        //   ⇒ 改成<b>按比例</b>算，并给像素上下限兜底：
        //     navW     = clamp(宽 × 13%, 130, 196)
        //     contentW = clamp(宽 × 26%, 260, 420)
        int navW0 = clampInt((int) (w * 0.13), 130, NAV_W);
        int contentW0 = clampInt((int) (w * 0.26), 260, CONTENT_W);

        navX = 0;
        navW = navW0;
        cfgX = navW + 8;
        cfgW = Math.min(contentW0, Math.max(220, w - cfgX - 10));
        cfgY = 8;
        cfgH = Math.max(200, h - 16);
    }

    private static int clampInt(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    /** 把当前状态同步给配置窗宿主（几何 + 页签 + 面板）。 */
    private void syncPanelHost() {
        // ★ v6：几何以 computePanelRect() 为唯一来源；页签由导航栏画 ⇒ 宿主不画页签
        panelX = cfgX;
        panelW = cfgW;
        // ★ v7：内容面板顶部留出<b>标题行</b>（示意图：「世界与尺度 — 8 项」）
        //   标题画在 overlayY + 10（约 10px 高 + 间距）⇒ 内容从 cfgY + 32 起。
        headerY = cfgY + 32;
        listTop = headerY;
        // ★ v7：底部留 20px 给说明行（drawFooterHint）
        listBottom = cfgY + cfgH - 22;
        bottomBarH = 0;
        navTabH = clampInt((int) (this.height * 0.038), 22, 32);
        navTabTop = clampInt((int) (this.height * 0.17), 110, 152);

        panelHost.panelX = panelX;
        panelHost.panelW = panelW;
        panelHost.overlayY = cfgY;
        panelHost.overlayH = cfgH;
        panelHost.headerY = headerY;
        panelHost.listTop = listTop;
        panelHost.listBottom = listBottom;
        panelHost.bottomBarH = bottomBarH;
        panelHost.tab = tab;
        panelHost.panels = panels;
        panelHost.tabLabels = visibleTabs.stream().map(PanelLayout.Tab::label).toList();

        // ★ v20：滚动条数据（内容总高 / 当前偏移），供 host 画右侧细滚动条
        panelHost.contentHeight = (panels != null && tab >= 0 && tab < panels.length)
                ? panels[tab].getHeight() : 0;
        panelHost.scrollOffset = scroll;

        // ★★ v21【修"示意图画在参数行上"】★★
        //   坐标契约：<b>listBottom 只扣一次</b>固定区，host 侧不再扣。
        //   之前屏幕扣一次、{@code drawScrollbar} 又扣一次 ⇒ 双重扣除，
        //   内容区被压短、固定区被算到内容区上方（实机：示意图压在参数上，
        //   下方留一大片空白）。
        boolean pinned = isPinnedTab();
        panelHost.pinnedHeight = pinned ? PINNED_H : 0;
        panelHost.pinnedRenderer = pinned ? this::renderPinnedChart : null;
        panelHost.listBottom = cfgY + cfgH - 22 - panelHost.bottomReserved();

        // 标题 + 项数（示意图顶部那行）
        if (tab >= 0 && tab < visibleTabs.size()) {
            panelHost.titleText = visibleTabs.get(tab).label();
            panelHost.titleCount = countRowsForTab(visibleTabs.get(tab));
            panelHost.footerHint = footerHintFor(visibleTabs.get(tab));
        } else {
            panelHost.titleText = null;
            panelHost.titleCount = -1;
            panelHost.footerHint = null;
        }
    }

    /** 固定示意图区高度（含标题行）。 */
    /**
     * 固定示意图区高度（★ v21：= 标题 14 + 画面 CHART_H 132 + 底部说明 18）。
     *
     * <p>之前 168 刚好卡在"画面 + 底部读数行"，再算上新增的说明行就不够了
     * ⇒ 底部文字会被页脚挤掉。</p>
     */
    private static final int PINNED_H = 176;

    /** 当前页签是否有常驻示意图。 */
    private boolean isPinnedTab() {
        return tab >= 0 && tab < visibleTabs.size()
                && visibleTabs.get(tab) == PanelLayout.Tab.WORLD_SCALE;
    }

    /**
     * 绘制常驻示意图（★ v20：钉在内容区底部，不随参数滚动）。
     *
     * <p>参数滚到哪儿它都在 —— 因为"尺度的意义"需要一边调一边看。</p>
     */
    private void renderPinnedChart(GuiGraphics g, int mx, int my, int topY) {
        UiKit.divider(g, panelX + 8, panelX + panelW - 8, topY - 5);
        g.drawString(font, "尺度示意（随上方参数实时变化）", panelX + 10, topY,
                UiKit.C_TXT_SUB, false);
        worldScalePanel.setPinnedBounds(panelX + 10, topY + 14, panelW - 20);
        worldScalePanel.renderChart(g, mx, my);
    }

    /** 各页签底部的说明行（★ v7：示意图底部那行小字）。 */
    private String footerHintFor(PanelLayout.Tab t) {
        return switch (t) {
            case PRESETS -> "点击预设立即应用；改动需点「应用」才会写入世界生成";
            case WORLD_SCALE -> "数量级越大地形越壮阔，但采样与生成也越慢";
            case CLIMATE -> "气候决定群系分布；温度与湿度同时影响地表形态";
            case TERRAIN_SHAPE -> "类型场控制地貌骨架；改动后需重新采样才看得到";
            case EROSION -> "侵蚀与河流影响细节；耗时随视口增大";
            case CAVE -> "洞穴沿垂直方向分层；不影响地表预览";
            case ORE -> "矿脉按岩性与深度带分布；仅影响地下";
            case DISPLAY -> "显示与色带只影响预览外观，不改世界生成";
            case CACHE -> "缓存按 schema 版本失效；参数改动会自动重采样";
            case BIOMES -> "群系由气候与地形共同判定；此处可查看分布";
            case DIAGNOSTIC -> "诊断项用于排查性能与产出一致性";
            default -> null;
        };
    }

    /** 某页签下的配置项数量（标题行右侧显示「N 项」）。 */
    private int countRowsForTab(PanelLayout.Tab t) {
        if (panels != null && tab < panels.length && panels[tab] instanceof DomainPanel dp) {
            return dp.keys().size();
        }
        return -1;
    }

    private void layoutPanels() {
        if (panels == null) return;
        // ★ v7：内容区左右各留 12px 内边距 —— 紧贴面板边缘是"和示意图差距大"的一处
        //   （示意图里内容距卡片边界有明显留白）。
        for (int i = 0; i < panels.length; i++) {
            panels[i].setBounds(cfgX + 12, listTop + 6, cfgW - 24);
            panels[i].setScrollOffset(i == tab ? scroll : 0);
        }
    }

    // ------------------------------------------------------------------ init

    @Override
    protected void init() {
        super.init();
        this.clearWidgets();
        diagFirstFrame = true;
        previewRegistered = false;
        int w = width, h = height;

        // ★ 全屏地图：预览占满，无顶栏/底栏
        previewX = 0;
        previewY = 0;
        previewW = w;
        previewH = h;

        // 配置窗矩形（悬浮在预览之上）
        computePanelRect();
        panelX = cfgX;
        panelW = cfgW;
        // ★ v6：页签已移到左侧导航栏 ⇒ 内容面板<b>不再有页签区</b>。
        //   内容区从面板顶栏（标题+应用）之下开始。
        headerY = cfgY + 40;                 // 顶栏高 40
        panelHost.drawTabs = false;          // 关掉宿主自己的页签绘制（否则两套页签）
        listTop = headerY;
        // ★ 页签行高按导航栏宽度自适应（窄屏时压缩，避免只显示 6 个页签）
        navTabH = clampInt((int) (this.height * 0.038), 22, 32);
        navTabTop = clampInt((int) (this.height * 0.17), 110, 152);
        tabBarBottom = headerY;
        // ★ 底部按钮区：v6 把按钮移到导航栏 ⇒ 内容面板不再留底部按钮区
        bottomBarH = 0;
        listBottom = cfgY + cfgH - 10;

        // ★★ v20【示意图常驻】★★
        //   用户：「建议给示意图做一下常驻显示，不然按现在这样滚动就消失了」。
        // ★ v20：示意图常驻 —— 由 syncPanelHost() 每帧按当前页签挂载（见 isPinnedTab）

        Runnable markDirty = () -> { dirty = true; saved = false; debounce = 8; };
        worldScalePanel.setOnMarkDirty(markDirty);
        worldScalePanel.buildFromConfig();
        // ★ v19：数值输入请求 → 复用 nameDialog 弹窗（能滑能填）
        //   所有面板统一注入（回调定义在 ConfigPanel 基类，避免每类各写一遍）
        worldScalePanel.setTextEditRequester(this::showValueEditDialog);
        erosionPanel.setTextEditRequester(this::showValueEditDialog);
        terrainPanel.setTextEditRequester(this::showValueEditDialog);
        diagnosticPanel.setTextEditRequester(this::showValueEditDialog);
        legacyPanel.setTextEditRequester(this::showValueEditDialog);
        climatePanel.setTextEditRequester(this::showValueEditDialog);
        cavePanel.setTextEditRequester(this::showValueEditDialog);
        paramPanel.setTextEditRequester(this::showValueEditDialog);
        erosionPanel.setOnMarkDirty(markDirty);
        erosionPanel.buildFromConfig();
        climatePanel.setOnMarkDirty(markDirty);
        climatePanel.buildClimateFactors();
        terrainPanel.setOnMarkDirty(markDirty);
        terrainPanel.buildFromConfig();
        paramPanel.setOnMarkDirty(markDirty);
        paramPanel.buildFromConfig();
        cavePanel.setOnMarkDirty(markDirty);
        cavePanel.buildFromConfig();
        orePanel.setOnMarkDirty(markDirty);
        orePanel.buildFromConfig();
        diagnosticPanel.setOnMarkDirty(markDirty);
        diagnosticPanel.buildFromConfig();
        legacyPanel.setOnMarkDirty(markDirty);
        legacyPanel.setReadOnly(readOnly);
        legacyPanel.buildFromConfig();

        // ---- 内容面板顶栏：应用（主操作）----
        //   ★ v7（2026-11-09）：全部改为<b>自绘</b>按钮 —— MC 原生 Button 是
        //   固定灰框 + 立体阴影，是"和示意图差距大"最显眼的一处。
        //   定义见 {@link #drawActionButtons} / {@link #hitActionButton}。
        actionBtnY = cfgY + 10;
        navBtnY = height - 74;
        navBtnW = Math.max(50, (navW - 22) / 2);

        if (preview != null) {
            preview.setPosition(previewX, previewY, previewW, previewH);
            // ★ 2026-11-09：<b>不再注册为 widget</b> —— 由 render() 手动在
            //   第 1 层绘制。注册的话 super.render() 会再画一次（重复绘制）。
            previewRegistered = true;
        }
        rebuildPreviewIfChanged();

        if (presetsPanel == null) presetsPanel = new PresetsPanel(
            () -> seed,
            s -> { seed = s; rebuildPreview(); },
            () -> { seed = (long) (Math.random() * Long.MAX_VALUE); rebuildPreview(); },
            this::showApplyPresetConfirm,
            this::addRenderableWidget,
            this::applyUserPreset);
        if (displayPanel == null) displayPanel = new DisplayPanel(preview);
        if (samplingPanel == null) samplingPanel = new SamplingPanel(preview);
        if (colormapPanel == null) colormapPanel = new ColormapPanel(preview);
        if (cachePanel == null) cachePanel = new CachePanel(preview);
        if (biomesPanel == null) biomesPanel = new BiomesPanel(preview);

        rebuildTabs(currentTab());
        LOGGER.info("DIAG-GCS: init done, tabs={} previewRegistered={}",
                visibleTabs.size(), previewRegistered);
    }

    private void pushScrollToPanels() {
        if (panels == null) return;
        for (int i = 0; i < panels.length; i++) panels[i].setScrollOffset(i == tab ? scroll : 0);
    }

    // ------------------------------------------------------------------ 预览

    private void rebuildPreview() {
        // ★ 2026-11-08【同源修复】：预览的 params / morphBase 必须与进游戏**同一判据**。
        //
        //   修复前：params 恒取 `GeoGenesisConfig.INSTANCE.buildParams()`（只读 toml），
        //           morphBase 取 `worldCtx != null || level == null || usesCurrentWorldMorphBase()`。
        //   进游戏：params 走 `resolveParams()`（**存档优先**）、morphBase 直接取
        //           `currentWorldMorphBase`。
        //   ⇒ 差异一：世界保存过参数后，预览显示的是 toml 默认值、不是该世界实际值。
        //   ⇒ 差异二：创建新世界时 morphBase 判据不同。
        //   两条都会让"共享 terrain 实例"读出错误地形，所以先统一源头。
        //
        //   `forNewWorld` = 还没有存档参数（创建新世界）⇒ 用户正在编辑 toml，
        //   预览必须反映**编辑中的值**；否则用存档值。
        boolean forNewWorld = !com.geogenesis.worldgen.generator.GeoGenesisGenerator.hasWorldParams();
        TerrainParams p = com.geogenesis.worldgen.generator.GeoGenesisGenerator
                .resolvePreviewParams(forNewWorld);
        boolean morphBase = com.geogenesis.worldgen.generator.GeoGenesisGenerator
                .resolvePreviewMorphBase();

        // ★ 2026-11-08【共享实例】：种子与参数都等于存档时，复用游戏那份 terrain。
        //
        //   收益：预览里已算好的 chunk（4096-chunk LRU）与**细层水文 region**
        //         （REGION=512 覆盖 32×32 chunk，单次 10 秒级）都能被进游戏直接吃到，
        //         不必重算 —— 这正是用户指出的"重复计算浪费"。
        //   边界：用户改了种子/参数试效果时**必须**用独立实例，
        //         否则会画着"别的种子/参数的地形"却写着当前种子（见 previewReusableTerrain 的 javadoc）。
        GeoGenesisTerrain terrain = com.geogenesis.worldgen.generator.GeoGenesisGenerator
                .previewReusableTerrain(seed, p);
        boolean shared = terrain != null;
        if (!shared) {
            CellGenerator gen = new CellGenerator(p, p.minY(), p.maxY(), morphBase);
            terrain = new GeoGenesisTerrain(gen);
            terrain.seed(seed);
        }
        LOGGER.info("[Preview] 地形实例: {} (seed={}, morphBase={})",
                shared ? "复用存档单例" : "新建", seed, morphBase);

        // ★ 2026-11-09【预览 → 世界复用】：记下"这个 terrain 是谁算的"，供 doApply 登记接管。
        //   自建实例 ⇒ 进世界时可以被接管（省掉重采 + 水文 region）；
        //   存档单例 ⇒ 游戏侧本来就在用，不登记（否则会破坏其生命周期）。
        this.previewTerrain = terrain;
        this.previewParams = p;
        this.previewMorphBase = morphBase;
        this.previewOwnsTerrain = !shared;
        if (preview == null) {
            // ownsTerrain = !shared：共享的存档单例由游戏侧管理生命周期，预览不得关闭它
            preview = new PreviewDisplay(previewX, previewY, previewW, previewH, terrain, seed, p,
                    currentMode, !shared);
            // ★ v7【修"切视图模式 GUI 就重叠"】：
            //   预览在配置屏里<b>只负责地形，不画任何 HUD</b>。
            //   导航栏 / 图例 / 右下状态栏 / 悬停信息全部由本屏统一负责。
            //   之前只关了 dropZoomLabel，平面模式仍画图例+边框+悬停，
            //   沙盘模式仍画标题+种子+操作提示 ⇒ 与导航栏叠成两套。
            preview.setStandaloneHud(false);
            preview.setDrawZoomLabel(false);
            // ★ B3：统一入口（平面精确管线 + 沙盘，共用状态与快照）
            //   ★ v24：控制器已删除 —— 模式状态直接在 preview 上
            //   （平面/沙盘只是同一份数据的两种画法，见 PreviewDisplay.setSandboxMode）
        } else {
            preview.setTerrain(terrain, seed, p, !shared);
            preview.setMode(currentMode);
        }
        Minecraft mc = Minecraft.getInstance();
        preview.setStructureContext(
            worldCtx != null ? ctxRegistry(worldCtx)
                    : (mc.level != null ? mc.level.registryAccess() : null),
            worldCtx != null ? ctxGenerator(worldCtx) : currentGenerator(mc),
            seed);
        preview.setPosition(previewX, previewY, previewW, previewH);
        // ★★ v9【修"内容面板右侧冒出原生小方块按钮"】★★
        //   这里原本是 `if (!previewRegistered) addRenderableWidget(preview);`
        //   而 init() 已把 previewRegistered 置 true（语义是"当作已处理"），
        //   于是 rebuildPreview 每次都走 addRenderableWidget ——
        //   Screen 给非全屏 widget 叠的**原生音效按钮**就出现在了面板顶栏右侧
        //   （用户截图里"应用"右上角那个喇叭小方块）。
        //   ⇒ 预览一律手动绘制（render 第 1 层），绝不注册为 widget。
        previewRegistered = true;
        lastAppliedParams = p;
        lastAppliedSeed = seed;
        dirty = false;
    }

    private void drawRoundedPill(GuiGraphics g, int x, int y, int w, int h, int r, int color, boolean flatBottom) {
        int r2 = Math.min(r, Math.min(w / 2, h / 2));
        g.fill(x, y + r2, x + w, y + h - (flatBottom ? 0 : r2), color);
        g.fill(x + r2, y, x + w - r2, y + h, color);
        int xL = x, xR = x + w - r2;
        g.fill(xL, y, xL + r2, y + r2, color);
        g.fill(xR, y, xR + r2, y + r2, color);
        if (!flatBottom) {
            g.fill(xL, y + h - r2, xL + r2, y + h, color);
            g.fill(xR, y + h - r2, xR + r2, y + h, color);
        }
    }

    /** 当前是否处于沙盘视图（★ v24：状态直接读 preview，无中间层）。 */
    private boolean isSandboxActive() {
        return preview != null && preview.isSandboxMode();
    }

    /** 沙盘按钮文案。 */
    private Component sandboxLabel() {
        return Component.literal(isSandboxActive() ? "沙盘视图" : "平面视图");
    }

    /**
     * 平面 ⇄ 沙盘 切换（★ v24：只换画法，不重采、不改缩放/中心/图层）。
     *
     * <p>★ 修"为什么切换沙盘就全局"：此前 {@code setSandboxMode(true)} 内部
     * 会调 {@code setLargeArea(true)} ⇒ 连锁把 {@code scaleBlockPos} 强改成
     * {@code LARGE_AREA_ENTRY_SCALE} ⇒ <b>切一次视图，缩放就被改掉</b>。
     * 那个强制已删除（沙盘数据来自 CellCache，与 largeArea 无关）。</p>
     */
    private void toggleSandbox() {
        if (preview == null) return;
        preview.setSandboxMode(!preview.isSandboxMode());
    }

    private void cycleLayer(int d) {
        currentMode = (currentMode + d + GeoPalette.PreviewLayer.values().length)
                % GeoPalette.PreviewLayer.values().length;
        // ★ v24：图层由 preview 持有，平面与沙盘天然同图层
        if (preview != null) preview.setMode(currentMode);
    }

    private Component largeAreaLabel() {
        boolean on = preview != null && preview.isLargeArea();
        return Component.literal(I18nSafe(on ? "geogenesis.settings.sampling.large.on"
                                              : "geogenesis.settings.sampling.large.off"));
    }

    private void toggleLargeArea() {
        if (preview == null) return;
        preview.setLargeArea(!preview.isLargeArea());
    }

    /**
     * <b>应用</b>（★ v21：只保存 + 重建预览，<b>不再关闭面板</b>）。
     *
     * <h2>为什么要拆开（用户：「点击后就直接关闭配置面板了」）</h2>
     * <p>旧 {@code doApply()} 末尾调 {@code setScreen(parent)} ——
     * 点「应用」= <b>保存并退出配置屏</b>。但"应用"的自然语义是
     * 「把我刚才的改动生效，我继续调别的」，而不是"我改完了，走人"。
     * 用户改完一个参数就得重新打开面板，非常繁琐。</p>
     *
     * <p>现在拆成两个动作：</p>
     * <ul>
     *   <li><b>应用</b>（本方法）：保存配置 + 重建预览，<b>留在面板里</b>继续调；</li>
     *   <li><b>完成</b>（{@link #doDone()}）：应用 + 登记地形复用 + 关闭面板回上一屏。</li>
     * </ul>
     */
    private void doApply() {
        GeoGenesisConfig.SPEC.save();
        saved = true;
        dirty = false;
        // 预览重建：这里是<b>唯一</b>入口（拖动期间不重建，见 tick() 的说明）
        rebuildPreviewIfChanged();
        toast("已应用");
    }

    /**
     * <b>完成</b>：应用参数 + 交出地形复用 + 退回上一屏。
     *
     * <h2>★ v47【落成动画暂停，恢复原版创建流程】</h2>
     * <p>用户裁定（2026-11-10）：「要不先暂停这个功能？其实这个难度比较大，
     * 后续在专项修复？先恢复之前点击完成后关闭自定义配置回到类型？
     * 毕竟我认为完成平面模式与沙盘模式的切换动画更合适。」</p>
     *
     * <p>此前（v38）这里会把创建世界流程直接跑起来（调 {@code onCreate}），
     * 目的是给落成动画提供「{@code LevelLoadingScreen} 打开的触发点」。
     * 现在动画暂停 ⇒ 也<b>不再替玩家点创建</b>，恢复原行为：
     * 退回 {@code CreateWorldScreen}，玩家自己点「创建世界」。</p>
     *
     * <p>⚠ v38 修的那个问题（「点完成就直接关闭回到类型选择」）源于
     * <b>只 setScreen(parent) 却没建世界</b>——当时的症状是玩家以为"完成"了
     * 结果什么都没发生。现在的行为是明确退回上一屏，符合用户本轮要求。</p>
     */
    private void doDone() {
        doApply();
        // ★★ 2026-11-09【预览 → 世界复用】
        //   配置屏在 MC 之外用纯 Java 核心算好的 GeoGenesisTerrain
        //   在这里登记；进世界时 bindWorld 三项全等就<b>直接接管</b>，不重算。
        offerTerrainForReuse();
        // ★ v47：不再向落成动画交接数据（动画已暂停，见类注释）。
        //   动画代码本身保留在 client/reveal/ 下，接回来只需恢复这三行。
        Minecraft.getInstance().setScreen(parent);
    }

    // ---- 轻量提示（★ v21：应用后给个反馈，否则用户不知道点没点上）----
    private String toastMsg = null;
    private long toastUntil = 0;

    private void toast(String msg) {
        toastMsg = msg;
        toastUntil = System.currentTimeMillis() + 1400;
    }

    private void drawToast(GuiGraphics g) {
        if (toastMsg == null) return;
        if (System.currentTimeMillis() > toastUntil) { toastMsg = null; return; }
        int tw = font.width(toastMsg);
        int cx = cfgX + cfgW / 2;
        int cy = cfgY + 58;
        UiKit.roundRect(g, cx - tw / 2 - 10, cy, cx + tw / 2 + 10, cy + 18, 9, 0xE648DE56);
        g.drawString(font, toastMsg, cx - tw / 2, cy + 5, 0xFF0C2210, false);
    }

    /**
     * 把当前预览用的 terrain 登记为「可被进世界接管」（见 {@code bindWorld}）。
     *
     * <p>只在<b>自建实例</b>时登记：共享的存档单例由游戏侧管理，交出去会破坏其生命周期。</p>
     */
    private void offerTerrainForReuse() {
        if (preview == null || previewTerrain == null) return;
        if (!previewOwnsTerrain) {
            // 复用的是存档单例 ⇒ 游戏侧本来就在用，不需要（也不能）交出去
            return;
        }
        com.geogenesis.worldgen.generator.GeoGenesisGenerator.offerPreviewTerrain(
                previewTerrain, seed, previewParams, previewMorphBase);
        // ★ 交出所有权：closePreview() 会调 preview.close()，
        //   若不交权，close() 里的 closeProcessErosion() 会把即将交给游戏的引擎关掉。
        preview.transferOwnership();
        offeredForReuse = true;
        LOGGER.info("[Preview] 已登记预览地形以供进世界接管（seed={}），并交出所有权", seed);
    }

    private void onSavePreset() {
        nameDialog.show("保存为预设", "为当前配置输入预设名称：",
            name -> {
                UserPresetsStore.getInstance().savePreset(name, GeoGenesisConfig.INSTANCE.captureAllValues());
                if (presetsPanel != null) presetsPanel.refreshUserPresets();
                LOGGER.info("Saved current config as user preset '{}'", name);
            },
            () -> {});
    }

    /**
     * <b>数值输入对话框</b>（★ v19）。
     *
     * <p>用户要求「右侧的数值显示可以做成可点击写入数值，这样能滑能填」。
     * 点击值胶囊 → 弹出本对话框 → 输入新值 → 交回面板写入配置。</p>
     *
     * <p>复用 {@link NameInputDialog}：它本来是"输入预设名"，但本质就是
     * 「标题 + 提示 + 单行输入 + 确定/取消」，用来输数字正好，
     * 不必再造一个几乎相同的对话框类。</p>
     */
    private void showValueEditDialog(String label, String current,
                                     java.util.function.Consumer<String> onCommit) {
        nameDialog.show("修改 " + label,
                "输入新数值后回车确认（当前 " + current + "）：",
                current == null ? "" : current,
                text -> onCommit.accept(text),
                () -> {});
    }

    private void applyUserPreset(UserPresetsStore.UserPreset p) {
        if (readOnly) {
            confirmDialog.show("只读模式",
                "游戏内不能修改地形配置（会导致已生成地形断裂）。",
                List.of("请退出世界后在创建世界界面修改配置"),
                () -> {}, () -> {});
            return;
        }
        GeoGenesisConfig.INSTANCE.resetToDefault();
        GeoGenesisConfig.INSTANCE.applyNamedValues(p.values());
        rebuildAllPanelsFromConfig();
        lastAppliedParams = null;
        rebuildPreview();
        try { GeoGenesisConfig.SPEC.save(); } catch (Exception ignored) { }
    }

    private void doReset() {
        if (readOnly) return;
        try {
            GeoGenesisConfig.INSTANCE.resetToDefault();
        } catch (Exception e) {
            // Windows 文件写竞争忽略
        }
        rebuildAllPanelsFromConfig();
        lastAppliedParams = null;
        rebuildPreview();
    }

    /** 全屏统一回读：任何"外部改了配置"的入口（预设/重置/用户预设）都走这里。 */
    private void rebuildAllPanelsFromConfig() {
        terrainPanel.buildFromConfig();
        climatePanel.buildClimateFactors();
        worldScalePanel.buildFromConfig();
        erosionPanel.buildFromConfig();
        cavePanel.buildFromConfig();
        orePanel.buildFromConfig();
        diagnosticPanel.buildFromConfig();
        legacyPanel.buildFromConfig();
        paramPanel.buildFromConfig();
        scroll = 0;
        pushScrollToPanels();
    }

    private void showApplyPresetConfirm(Preset p) {
        if (readOnly) {
            confirmDialog.show("只读模式",
                "游戏内不能修改地形配置（会导致已生成地形断裂）。",
                List.of("请退出世界后在创建世界界面修改配置"),
                () -> {}, () -> {});
            return;
        }
        confirmDialog.show("应用预设：" + p.name,
            "将用预设「" + p.name + "」覆盖当前全部地形 / 气候参数。",
            List.of(p.applyHint()),
            () -> applyPreset(p),
            () -> {});
    }

    private void applyPreset(Preset p) {
        p.applyTo(GeoGenesisConfig.INSTANCE);
        rebuildAllPanelsFromConfig();
        lastAppliedParams = null;
        rebuildPreview();
        try { GeoGenesisConfig.SPEC.save(); } catch (Exception ignored) { }
    }

    /**
     * <b>每 tick</b>（★ v20：不再自动重建预览）。
     *
     * <h2>为什么去掉 debounce 自动重建</h2>
     * <p>旧实现：任何参数变脏 → {@code debounce=8} → 8 tick 后自动
     * {@link #rebuildPreviewIfChanged()}。用户拖动滑块时会连续触发，
     * 于是<b>每 8 tick 就用新参数重建一次地形</b>（含侵蚀/水文）——
     * 表现为"滑来滑去数据一直在反复计算"，界面卡、风扇响。</p>
     *
     * <p>用户要求：「修改配置时背后的预览不应该实时更新，建议在点击应用时才更新」。
     * ⇒ 现在<b>只有</b> {@code doApply()} 会重建预览；
     * 拖动期间只改变配置值与示意图（示意图是纯本地计算，零采样成本）。</p>
     *
     * <p>这也顺带消除了一整类隐患：拖动中途重建会让 {@code PreviewDisplay}
     * 在采样进行中更换 terrain 实例，容易引出线程/缓存竞态。</p>
     */
    @Override
    public void tick() {
        super.tick();
        // 有意留空：预览重建只在 doApply() 里发生。
    }

    /** 侵蚀/河流开关指纹：开关不在 TerrainParams 里，rebuild 判断必须单独比较。 */
    private int lastToggleBits = -1;
    private int toggleBits() {
        GeoGenesisConfig c = GeoGenesisConfig.INSTANCE;
        int b = 0;
        if (c.erosionEnabled != null && c.erosionEnabled.get()) b |= 1;
        if (c.riverEnabled != null && c.riverEnabled.get()) b |= 2;
        return b;
    }

    private static net.minecraft.world.level.chunk.ChunkGenerator currentGenerator(Minecraft mc) {
        try {
            var server = mc.getSingleplayerServer();
            if (server != null) {
                var lvl = server.overworld();
                if (lvl != null) return lvl.getChunkSource().getGenerator();
            }
        } catch (Throwable ignore) { }
        return null;
    }

    private static net.minecraft.core.RegistryAccess ctxRegistry(
            net.minecraft.client.gui.screens.worldselection.WorldCreationContext ctx) {
        try {
            var m = ctx.getClass().getMethod("registryAccess");
            return (net.minecraft.core.RegistryAccess) m.invoke(ctx);
        } catch (Throwable t) { return null; }
    }

    private static net.minecraft.world.level.chunk.ChunkGenerator ctxGenerator(
            net.minecraft.client.gui.screens.worldselection.WorldCreationContext ctx) {
        try {
            var m = ctx.getClass().getMethod("generator");
            return (net.minecraft.world.level.chunk.ChunkGenerator) m.invoke(ctx);
        } catch (Throwable t) { return null; }
    }

    private void rebuildPreviewIfChanged() {
        // 变更检测比较的是<b>用户正在编辑的 toml 值</b>（拖滑条改的是它），
        // 与 rebuildPreview() 里"实际生效的 params"（可能是存档值）是两件事 —— 不要混淆。
        TerrainParams p = GeoGenesisConfig.INSTANCE.buildParams();
        int tb = toggleBits();
        if (lastAppliedParams != null
                && lastAppliedParams.equals(p)
                && lastAppliedSeed == seed
                && tb == lastToggleBits) {
            dirty = false;
            return;
        }
        lastAppliedParams = p;
        lastAppliedSeed = seed;
        lastToggleBits = tb;
        rebuildPreview();
    }

    // ------------------------------------------------------------------ 渲染

    @Override
    public void renderBackground(GuiGraphics g) {
        // ★ 全屏地图：不画整屏底色（会盖住地形），只在地形未就绪时兜底
        g.fill(0, 0, this.width, this.height, 0xFF0e1218);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        this.renderBackground(g);

        boolean dialogActive = nameDialog.isShowing() || confirmDialog.isShowing();

        // ★★ 2026-11-09【修"种子输入框消失"】—— 绘制顺序是根因
        //
        //   旧顺序：super.render()（画所有 widget，含种子 EditBox）
        //         → panelHost.render()（画面板底板，**不透明**）
        //   ⇒ 底板把已画好的 widget 全盖住了（实机截图：只有「种子:」标签，输入框不见了）。
        //
        //   新顺序：底板 → 面板内容 → widget → HUD
        //   为此要先算几何、先画底板，**最后**才 super.render()。
        computePanelRect();
        syncPanelHost();

        // ★★ v22【修"幽灵提示"】★★
        //   每帧开头清空所有面板的悬停状态 —— 否则鼠标移开后，
        //   上一帧写进 hoverTooltip 的文本会一直活着并被读到
        //   （用户："明明没指任何UI控件却一直显示一个提示内容"）。
        if (panels != null) {
            for (ConfigPanel p : panels) if (p != null) p.beginFrame();
        }
        UiKit.Hover.clear();

        // ---------------- 第 1 层：全屏地形 ----------------
        //   ★ 预览不注册为 widget，由这里按顺序手动画（保证地形在最底层）。
        if (preview != null) {
            preview.drawSelf(g, mx, my, pt);
        }

        // ---------------- 第 2 层：左侧导航栏 ----------------
        if (panelOpen && !dialogActive) {
            renderNavRail(g, mx, my);
        }

        // ---------------- 第 3 层：右侧内容面板（底板 + 内容）----------------
        if (panelOpen && !dialogActive) {
            if (presetsPanel != null) {
                presetsPanel.setWidgetsVisible(tab == visibleTabs.indexOf(PanelLayout.Tab.PRESETS));
                presetsPanel.setViewportHeight(listBottom - presetsPanel.getScrollTopY() - 6);
            }
            panelHost.render(g, mx, my, pt, null);
        }

        // ---------------- 第 4 层：widget（按钮 / 输入框 / 下拉）----------------
        //   ★ 必须在底板之后画，否则会被盖住
        if (!dialogActive) {
            super.render(g, mx, my, pt);
        }

        // ---------------- 第 5 层：自绘按钮 + 折叠把手 + 状态栏 ----------------
        //   ★ v7：应用/保存/重置/大范围/沙盘/图层 已改为自绘，必须在这里显式画
        //   （它们不在 widget 列表里，super.render() 不会画）。
        drawActionButtons(g, mx, my);
        renderPanelHandle(g, mx, my);
        renderStatusHud(g);

        // ---------------- 第 6 层：视图辅助（图例 + 操作提示）----------------
        //   ★ v7：这两块原来由 PreviewDisplay 自己画，被 standaloneHud=false 关掉了。
        //   现在由屏幕统一在正确位置画（示意图：右上色带、左上提示卡）。
        //   ★ 折叠后<b>仍要显示</b> —— 配置窗收起时用户就是在看地图，
        //     没有图例和操作提示等于摸黑（实机反馈：「收回后很多悬浮信息消失了」）。
        if (!dialogActive) {
            drawViewHints(g);
            if (preview != null) preview.drawLegendAt(g, mx, my);
        }

        // ---------------- 第 7 层：悬停提示（★ 唯一出口）----------------
        //   ★★ v15【修"tooltip 各页新旧不一致"】★★
        //   此前有<b>三套</b> tooltip 并存：
        //     ① 本屏 drawHoverTooltip（圆角/折行/翻边 —— 最新）
        //     ② 面板的 consumeHoverTooltip（走 ① ）
        //     ③ ParamSlider / CategoryBar / ControlPoint / MixerPanel / WorldHeightBar
        //        各自画（直角黑方块、单行不折行、又一套配色）
        //   ⇒ 换个页签就换一张脸。
        //   现在 ③ 全部改为向 UiKit.Hover <b>登记文本</b>，与 ② 一起在这里统一绘制。
        if (!dialogActive) {
            String tipText = null;
            if (panelOpen && panels != null && tab < panels.length) {
                Component tip = panels[tab].consumeHoverTooltip();
                if (tip != null) tipText = tip.getString();
            }
            // 控件登记的提示优先级更高（它们比面板更靠近光标）
            if (UiKit.Hover.has()) {
                tipText = UiKit.Hover.text();
            }
            if (tipText != null && !tipText.isEmpty()) {
                drawHoverTooltip(g, tipText, mx, my);
            }
            UiKit.Hover.clear();
        }

        // ---------------- 第 8 层：操作反馈（★ v21：应用后的轻量提示）----------------
        if (!dialogActive) drawToast(g);

        if (diagFirstFrame) {
            diagFirstFrame = false;
            LOGGER.info("DIAG-GCS: render first-frame, tabs={} previewRegistered={} panelOpen={}",
                    visibleTabs.size(), previewRegistered, panelOpen);
        }

        if (confirmDialog.isShowing()) confirmDialog.render(g, mx, my);
        if (nameDialog.isShowing()) nameDialog.render(g, mx, my);
    }

    private String I18nSafe(String key) {
        String v = net.minecraft.client.resources.language.I18n.get(key);
        return v.equals(key) ? key : v;
    }

    // ------------------------------------------------------- 导航栏（v6 布局）

    /**
     * 导航栏顶部的<b>种子信息行</b>（★ v22：替换掉原来的「预览地图 / 新建预设」）。
     *
     * <h2>为什么删掉那两项（用户：「这2个功能有点意义不明」）</h2>
     * <ul>
     *   <li><b>「预览地图」</b> = 收起配置面板看地图 —— 与标题行右侧的 {@code <}
     *       折叠键<b>完全重复</b>，同一个动作两个入口；</li>
     *   <li><b>「新建预设」</b> = 跳到「预设」页签 —— 而预设页签本来就在下方的
     *       页签列表里，点它就行，等于给了一个"去点另一个按钮的按钮"。</li>
     * </ul>
     * <p>两者都是纯导航冗余。删掉后这块位置改为显示<b>当前种子</b>
     * —— 那是用户真正常看、又没地方常驻显示的信息。</p>
     */
    private void drawNavSeedLine(GuiGraphics g, int y, int h) {
        String seedTxt = "种子 " + seed;
        g.drawString(this.font, UiKit.clip(seedTxt, navW - 20),
                navX + 14, y + (h - this.font.lineHeight) / 2, NAV_TXT_DIM, false);
    }

    // 配色取自用户认可的示意图（保持不动）
    private static final int NAV_BG = 0xE60B0B0B;
    private static final int NAV_ROW_SEL = 0x24FFFFFF;
    private static final int NAV_ROW_HOVER = 0x14FFFFFF;
    private static final int NAV_ACCENT = 0xFF48DE56;
    private static final int NAV_TXT = 0xFFF2F2F2;
    private static final int NAV_TXT_SUB = 0xFF9E9E9E;
    private static final int NAV_TXT_DIM = 0xFF6E6E6E;

    /**
     * 绘制左侧导航栏（★ v6 布局）。
     *
     * <pre>
     *   ┌────────────────┐
     *   │ GeoGenesis     │  品牌行
     *   │ 预览 / 新建预设 │  顶部两项
     *   │ ──── 配置 ──── │
     *   │ ▸ 预设          │  ← 页签竖排（选中=绿条+亮底）
     *   │ ▸ 世界与尺度     │
     *   │ ▸ 地形          │
     *   │   …            │
     *   │ ─────────────  │
     *   │ 进度条          │  底部迷你条
     *   │ [保存][重置]    │
     *   └────────────────┘
     * </pre>
     */
    private void renderNavRail(GuiGraphics g, int mx, int my) {
        int h = this.height;
        g.fill(navX, 0, navX + navW, h, NAV_BG);

        // ★ 所有纵向位置按高度比例算（GUI Scale 放大后逻辑高度可能只有 540）
        int brandY = clampInt((int) (h * 0.028), 12, 20);
        int sepY = clampInt((int) (h * 0.115), 62, 90);
        int groupY = clampInt((int) (h * 0.128), 72, 100);

        // 品牌行
        g.drawString(this.font, "GeoGenesis", navX + 14, brandY, NAV_TXT, false);

        // ★ v22：原来这里是「预览地图 / 新建预设」两项 —— 功能与折叠键、预设页签
        //   完全重复，已删除。改显示当前种子（用户常看、此前没有常驻位置）。
        drawNavSeedLine(g, brandY + 16, 16);

        // 分组标题
        g.fill(navX + 12, sepY, navX + navW - 12, sepY + 1, 0x1AFFFFFF);
        g.drawString(this.font, "配置", navX + 14, groupY, NAV_TXT_DIM, false);

        // 页签竖排（选中 = 略亮底 + 左侧绿条）
        // ★ v7【修"群系页签与状态行重叠"】：
        //   旧代码 bottomLimit = h-96 让页签画到 h-96，而底部迷你条从 h-120 起
        //   ⇒ 两条区间相交 24px，最后 1~2 个页签（实测「群系」）压在状态文字上。
        //   现在改成：页签必须止于迷你条<b>上方</b>，由同一个 navMiniTop 派生。
        int navMiniTop = navMiniTopY();
        int bottomLimit = navMiniTop - 6;
        int n = visibleTabs.size();
        for (int i = 0; i < n; i++) {
            int ty = navTabTop + i * navTabH;
            if (ty + navTabH > bottomLimit) break;
            boolean on = tab == i;
            boolean hover = mx >= navX && mx < navX + navW
                    && my >= ty && my < ty + navTabH;
            if (on) {
                g.fill(navX + 8, ty, navX + navW - 8, ty + navTabH - 2, NAV_ROW_SEL);
                g.fill(navX + 8, ty + 8, navX + 11, ty + navTabH - 10, NAV_ACCENT);
            } else if (hover) {
                g.fill(navX + 8, ty, navX + navW - 8, ty + navTabH - 2, NAV_ROW_HOVER);
            }
            g.drawString(this.font, visibleTabs.get(i).label(),
                    navX + 22, ty + 8, on ? NAV_TXT : NAV_TXT_SUB, false);
        }

        // ★ v34：底部迷你条（状态 + 进度条）已移到<b>右下角无框文字区</b>
        //   —— 用户：「建议把这个加载进度条移到右侧下方的无框文字位置，并且也是无框。」
        //   导航栏这边只留页签，不再画状态行/进度条。
    }

    /**
     * 底部迷你条（状态 + 进度）的顶部 y（★ v7 单一来源）。
     *
     * <p>它必须同时满足两个约束：在导航栏三行按钮（共 66px + 10px 底距）之上、
     * 且页签区止于它上方。之前这两件事各算一遍 ⇒ 页签压到状态行上。</p>
     */
    private int navMiniTopY() {
        int layerY = this.height - 10 - 20;      // 第三行按钮顶
        int row1Y = layerY - 3 - 20 - 3 - 20;    // 第一行按钮顶
        return row1Y - 30;                       // 迷你条：状态行 + 进度条 + 间距
    }

    /** 按像素宽裁剪文字加省略号。 */
    private static String trim(String s, net.minecraft.client.gui.Font f, int maxW) {
        if (f.width(s) <= maxW) return s;
        while (!s.isEmpty() && f.width(s + "…") > maxW) s = s.substring(0, s.length() - 1);
        return s + "…";
    }

    /**
     * <b>视图操作提示卡</b>（★ v10 重做，参考用户给的信息层级样式）。
     *
     * <h2>两处改动</h2>
     * <ol>
     *   <li><b>位置</b>：用户要求「移到右侧下方，与高程图例一起」——
     *       原来在左上角，和地图内容抢视线；现在排在图例卡<b>正下方</b>，
     *       右侧一列（图例在上、提示在下），左侧完全让给地形。</li>
     *   <li><b>信息层级</b>：参考图那种「小标题 + 分条 + 键位高亮」的写法，
     *       不再是一行行等权的灰字。每条拆成「键位（亮）+ 说明（暗）」两段。</li>
     * </ol>
     */
    private void drawViewHints(GuiGraphics g) {
        // {键位, 说明}
        String[][] rows = isSandboxActive()
                ? new String[][]{{"左键拖动", "旋转视角"}, {"右键拖动", "平移"},
                                 {"右键单击", "设出生点"}, {"滚轮", "推近 / 拉远"},
                                 {"R", "重置视角"}}
                : new String[][]{{"左键拖动", "移动地图"}, {"滚轮", "缩放比例"},
                                 {"右键", "复制坐标"}, {"L", "图例折叠"}};

        String title = isSandboxActive() ? "沙盘视图操作" : "平面视图操作";
        int pad = 0;   // ★ v11：无框 ⇒ 不需要内边距
        int keyW = 0, descW = 0;
        for (String[] r : rows) {
            keyW = Math.max(keyW, this.font.width(r[0]));
            descW = Math.max(descW, this.font.width(r[1]));
        }
        // ★ v12：行距压到 lineHeight+1（原 +3），标题与首行间距 4（原 6）
        int rowH = this.font.lineHeight + 1;
        int cardW = Math.max(keyW + 10 + descW, this.font.width(title));
        int cardH = this.font.lineHeight + 4 + rows.length * rowH;

        // ---- 位置：图例卡正下方；图例不可用时退回右侧底部 ----
        int cardX, cardY;
        int[] legend = preview != null ? preview.legendCardRect() : null;
        if (legend != null) {
            // ★ v12【修"提示卡压着色带下沿"】：间距 8 → 14。
            //   上一版加 8px 时，色带底部的刻度数字（如 19）比色带低 4~6px，
            //   提示卡标题正好贴上去。现在留足一行的呼吸空间。
            cardX = Math.max(cfgX + cfgW + 8, legend[0]);
            cardY = legend[1] + legend[3] + 14;
            // 硬约束：无论如何不得进入图例矩形（宁可整体上移）
            if (cardY < legend[1] + legend[3] + 6) {
                cardY = legend[1] + legend[3] + 6;
            }
        } else {
            cardX = this.width - cardW - 12;
            cardY = this.height - cardH - 12;
        }
        // 夹在屏幕内（底部贴边 8px，不贴死）
        cardX = Math.max(4, Math.min(cardX, this.width - cardW - 4));
        cardY = Math.max(4, Math.min(cardY, this.height - cardH - 8));

        // ---- ★ v11【无框】：用户明确要求"无框的设计" ----
        //   去掉卡片底与描边，改为<b>文字 + 投影</b>（drawString 的 shadow=true）。
        //   投影在浅色草地/深色水体上都够读，且不遮挡地形 —— 这正是参考图的做法。
        int ty = cardY + pad;
        drawHintText(g, title, cardX + pad, ty, 0xFFFFFFFF);
        ty += this.font.lineHeight + 4;   // ★ v12：与 cardH 的定义保持一致（+4）
        for (String[] r : rows) {
            // 键位：暖白色，比说明亮一档，靠亮度而非底块区分
            drawHintText(g, r[0], cardX + pad, ty + 1, 0xFFFFD9A8);
            drawHintText(g, r[1], cardX + pad + keyW + 10, ty + 1, 0xFFC8C8C8);
            ty += rowH;
        }

        // ★★★ v34【把加载进度移到右下角无框文字区】★★★
        //   用户：「建议把这个加载进度条移到右侧下方的无框文字位置，并且也是无框。」
        //   （原来画在左侧导航栏底部迷你条里，带一条分隔线 + 底色，是"框"）
        //   ⇒ 现在排在操作提示<b>正下方</b>，同样只有文字 + 一条极细的进度线，
        //     与上面的键位提示同一套无框语言。
        if (preview != null) {
            ty += this.font.lineHeight + 2;
            String st = preview.statusLine();
            int frac100 = (int) (preview.progressFraction() * 100);
            String pct = frac100 + "%";
            if (st != null && !st.isEmpty()) {
                drawHintText(g, trim(st, this.font, cardW - this.font.width(pct) - 8),
                        cardX + pad, ty, 0xFFA8B0BC);
            }
            drawHintText(g, pct, cardX + cardW - this.font.width(pct), ty, 0xFF8C94A0);
            // 极细进度线（1px，无框，颜色同提示文字层级）
            int lineY = ty + this.font.lineHeight + 2;
            g.fill(cardX, lineY, cardX + cardW, lineY + 1, 0x30FFFFFF);
            if (frac100 > 0) {
                g.fill(cardX, lineY, cardX + (int) (cardW * frac100 / 100f), lineY + 1, 0xFF48DE56);
            }
        }
    }

    /** 带投影的提示文字（★ v11：无框提示的唯一绘制入口）。 */
    private void drawHintText(GuiGraphics g, String s, int x, int y, int color) {
        g.drawString(this.font, s, x, y, color, true);
    }

    /**
     * <b>悬停提示（tooltip）</b>（★ v13 重做）。
     *
     * <h2>为什么自己画而不用 {@code g.renderTooltip}</h2>
     * <ul>
     *   <li>原版 tooltip 是<b>直角灰底 + 1px 边框</b>，与本项目的圆角语言不统一；</li>
     *   <li>它不会自动避让屏幕边缘 —— 参数说明很长时右半会被裁掉
     *       （用户截图：「大陆性轴上的大陆架外缘位置…范围 [-1, 1]」被切）；</li>
     *   <li>不能控制层级 —— 用户要求"应该在最上层"。</li>
     * </ul>
     *
     * <h2>本实现</h2>
     * <ul>
     *   <li>圆角 6 + 近不透明底 {@code 0xF21A1D22} + 极淡描边；</li>
     *   <li><b>按像素宽折行</b>（长说明自动分行，不再横向溢出）；</li>
     *   <li>优先显示在光标右下方，超出右/下边界时自动翻到左侧/上方；</li>
     *   <li>与鼠标之间留 12px 偏移，不遮住正在悬停的控件。</li>
     * </ul>
     */
    private void drawHoverTooltip(GuiGraphics g, String text, int mx, int my) {
        Font f = this.font;

        int maxW = Math.min(300, this.width - 40);
        // 先算折行后的行数与最宽行
        java.util.List<String> lines = new java.util.ArrayList<>();
        int widest = 0;
        for (String para : text.split("\n", -1)) {
            StringBuilder cur = new StringBuilder();
            for (int i = 0; i < para.length(); i++) {
                char ch = para.charAt(i);
                if (cur.length() > 0 && f.width(cur.toString() + ch) > maxW) {
                    lines.add(cur.toString());
                    widest = Math.max(widest, f.width(cur.toString()));
                    cur.setLength(0);
                }
                cur.append(ch);
            }
            if (cur.length() > 0) {
                lines.add(cur.toString());
                widest = Math.max(widest, f.width(cur.toString()));
            }
        }
        if (lines.isEmpty()) return;

        int padX = 8, padY = 6;
        int lineH = f.lineHeight + 1;
        int boxW = widest + padX * 2;
        int boxH = lines.size() * lineH + padY * 2 - 1;

        // 优先右下；越界则翻边（先左右、再上下）
        int x = mx + 12;
        int y = my + 12;
        if (x + boxW > this.width - 4) x = mx - boxW - 12;
        if (y + boxH > this.height - 4) y = my - boxH - 12;
        x = Math.max(4, Math.min(x, this.width - boxW - 4));
        y = Math.max(4, Math.min(y, this.height - boxH - 4));

        // 阴影（比框大 1px 的暗角，让框从地形上"浮起来"）
        // ★ v13：圆角与主体一致（都用 UiKit.RADIUS_CARD），不再出现 7/6 两档
        int rc = UiKit.RADIUS_CARD;
        UiKit.roundRect(g, x - 1, y, x + boxW + 1, y + boxH + 2, rc, 0x55000000);
        // 主体：近不透明，保证任何地形上都读得清
        UiKit.roundRect(g, x, y, x + boxW, y + boxH, rc, 0xF21A1D22);
        UiKit.roundRectOutline(g, x, y, x + boxW, y + boxH, rc, 0x22FFFFFF);

        int ty = y + padY;
        for (int i = 0; i < lines.size(); i++) {
            g.drawString(f, lines.get(i), x + padX, ty,
                    i == 0 ? 0xFFF2F2F2 : 0xFFC8C8C8, false);
            ty += lineH;
        }
    }

    // ------------------------------------------------------------------ 悬浮层绘制

    /** 配置窗的半透明底板 + 边框。展开时地形仍能从边缘透出来。 */


    /**
     * 折叠把手。
     *
     * <p>★ v11【按用户指定位置调整】：展开态放在<b>导航栏品牌行右侧</b>
     * （用户截图圈定的位置），收起态在左上角作为唯一入口。</p>
     *
     * <p>历史：v10 曾放在内容面板标题行最左（{@code cfgX+8}），
     * 但那里紧贴面板标题文字，视觉上像"标题的装饰"而不是可点的折叠键。
     * 现在归位到品牌行右端 —— 与 "GeoGenesis" 同一行，语义清晰（收起整个配置区）。</p>
     */
    private void renderPanelHandle(GuiGraphics g, int mx, int my) {
        int hh = 16;
        int hw = panelOpen ? 20 : PANEL_W_CLOSED;
        int hx, hy;
        if (panelOpen) {
            // 品牌行右端：导航栏内、与 "GeoGenesis" 同行
            int brandY = clampInt((int) (this.height * 0.028), 12, 20);
            hx = navX + navW - hw - 10;
            hy = brandY - 3;
        } else {
            hx = cfgX;
            hy = cfgY;
        }
        boolean hover = mx >= hx && mx <= hx + hw && my >= hy && my <= hy + hh;
        UiKit.button(g, hx, hy, hw, hh, panelOpen ? "<" : "> 配置", false, hover);
        handleRect[0] = hx; handleRect[1] = hy; handleRect[2] = hw; handleRect[3] = hh;
    }

    /**
     * <b>自绘按钮</b>（★ v7）：应用 / 保存 / 重置 / 大范围 / 沙盘 / 图层左右。
     *
     * <p>替代 7 个 MC 原生 {@code Button}。样式走 {@link UiKit#button}：
     * 圆角 + 半透明底 + 悬停提亮；主操作（应用）用主色填充。</p>
     *
     * <p>⚠️ 绘制与 {@link #hitActionButton} <b>必须用同一套坐标</b> ——
     * 两处各算一遍是"按钮点不到"的老根因（见 AGENTS.md）。</p>
     */
    private void renderPanelWidgets(GuiGraphics g, int mx, int my, float pt) {
        drawActionButtons(g, mx, my);
    }

    /** 内容面板顶栏 + 导航栏底部的全部按钮（绘制）。 */
    /**
     * 收起态浮条：<b>视图切换 + 完成</b>（★ v25）。
     *
     * <p>用户 2026-11-11：「建议把那些像完成、视图模式切换等非配置内容
     * 可以在收回配置窗口也显示出来，这样方便交互。」</p>
     *
     * <p>坐标写进 {@link #colViewBtnX} 等字段，由 {@link #hitActionButton} 读同一份
     * —— 不允许各算一遍（那是本项目"按钮点不到"的老根因）。</p>
     */
    private void drawCollapsedActions(GuiGraphics g, int mx, int my) {
        int[] L = collapsedLayout();
        int x = L[0], w = L[1], h = L[2];
        int yView = L[3], yLarge = L[4], yLayer = L[5], ySave = L[6], yDone = L[7];

        UiKit.toggleButton(g, x, yView, w, h, sandboxLabel().getString(),
                isSandboxActive(), inRect(mx, my, x, yView, w, h), NAV_ACCENT);
        UiKit.toggleButton(g, x, yLarge, w, h, fitNavLabel(largeAreaLabel().getString(), w),
                preview != null && preview.isLargeArea(),
                inRect(mx, my, x, yLarge, w, h), NAV_ACCENT);

        // 图层行：< 名称 >
        drawNavBtn(g, x, yLayer, 24, h, "<", false, mx, my);
        drawNavBtn(g, x + w - 24, yLayer, 24, h, ">", false, mx, my);
        String layerName = currentLayerLabel();
        int lx = x + 28;
        int lw = w - 32;
        g.drawString(this.font, trim(layerName, this.font, lw), lx,
                yLayer + (h - this.font.lineHeight) / 2, NAV_TXT_SUB, false);

        int half = (w - 4) / 2;
        drawNavBtn(g, x, ySave, half, h, "保存", false, mx, my);
        drawNavBtn(g, x + w - half, ySave, half, h, readOnly ? "只读" : "重置", false, mx, my);

        UiKit.toggleButton(g, x, yDone, w, h, "完成",
                !readOnly, inRect(mx, my, x, yDone, w, h), UiKit.C_ACCENT);
    }

    /**
     * 收起态浮条布局 —— <b>绘制与命中共用同一份</b>（★ v26）。
     *
     * <p>用户实机：「收起态两个按钮不能点击交互。不过它们压在右上角图例上了，
     * 明明左侧全是空位。还有图 2 这些按键明显也可以显示在全屏画面里啊。」</p>
     *
     * <p>⇒ 从右上角（压着图例，且点击被预览吃掉）挪到<b>左侧把手下方</b>的空白列，
     * 并把导航栏那组非配置操作一并搬上来：视图 / 全局查看 / 图层 / 保存 / 重置 / 完成。</p>
     *
     * @return {x, w, h, yView, yLarge, yLayer, ySave, yDone}
     */
    private int[] collapsedLayout() {
        int x = 10, w = 112, h = 20, gap = 4;
        int y = handleBottomY() + 8;
        int yView = y; y += h + gap;
        int yLarge = y; y += h + gap;
        int yLayer = y; y += h + gap;
        int ySave = y; y += h + gap;
        int yDone = y;
        return new int[]{x, w, h, yView, yLarge, yLayer, ySave, yDone};
    }

    /** 折叠把手的下沿（收起态浮条从它下面开始排）。 */
    private int handleBottomY() {
        return 30;
    }

    private void drawActionButtons(GuiGraphics g, int mx, int my) {
        if (nameDialog.isShowing() || confirmDialog.isShowing()) return;

        // ★ v25【配置窗收起后仍保留"非配置"操作】—— 用户 2026-11-11：
        //   「建议把那些像完成、视图模式切换等非配置内容可以在收回配置窗口也显示出来，
        //     这样方便交互。」
        //   收起态只剩地图，若连"切视图/完成"都要先展开面板才能点，
        //   那就等于"看地图"这条路被断了半截。
        if (!panelOpen) {
            drawCollapsedActions(g, mx, my);
            return;
        }

        // ---- 内容面板顶栏：应用 + 完成（★ v21：拆成两个动作）----
        //   用户：「应用按键应该改下逻辑，现在我点击后就直接关闭配置面板了」。
        //   ⇒ 「应用」= 保存并重建预览，留在面板继续调；
        //     「完成」= 应用 + 交出地形 + 关闭面板。
        int btnH = 20;
        applyBtnW = 56;
        int doneBtnW = 56;
        int gap = 6;
        // 完成在右、应用在左（完成是"离开"这类终点动作，靠边）
        int doneX = cfgX + cfgW - doneBtnW - 10;
        applyBtnX = doneX - gap - applyBtnW;

        boolean applyHover = inRect(mx, my, applyBtnX, actionBtnY, applyBtnW, btnH);
        UiKit.button(g, applyBtnX, actionBtnY, applyBtnW, btnH,
                "应用", !readOnly && dirty, applyHover);

        boolean doneHover = inRect(mx, my, doneX, actionBtnY, doneBtnW, btnH);
        // 完成是终点动作（绿色实心）；应用是次操作（深灰，有改动时才点亮）
        UiKit.toggleButton(g, doneX, actionBtnY, doneBtnW, btnH,
                "完成", !readOnly, doneHover, UiKit.C_ACCENT);
        doneBtnX = doneX;
        doneBtnW_ = doneBtnW;
        doneBtnH_ = btnH;
        applyBtnH_ = btnH;

        // ---- 导航栏底部：三行按钮（★ v7：几何来自 navButtonLayout()，与命中同一份）----
        int[] lay = navButtonLayout();
        int c1 = lay[0], c2 = lay[1], bw = lay[2], bh = lay[3];
        int row1Y = lay[4], row2Y = lay[5], layerY = lay[6];
        navBtnY = row1Y;

        drawNavBtn(g, c1, row1Y, bw, bh, "保存", false, mx, my);
        drawNavBtn(g, c2, row1Y, bw, bh, readOnly ? "只读" : "重置", false, mx, my);
        drawNavBtn(g, c1, row2Y, bw, bh, fitNavLabel(largeAreaLabel().getString(), bw),
                preview != null && preview.isLargeArea(), mx, my);
        drawNavBtn(g, c2, row2Y, bw, bh, fitNavLabel(sandboxLabel().getString(), bw),
                isSandboxActive(), mx, my);

        // ---- 图层切换（第三行）：< [名称] > ----
        drawNavBtn(g, c1, layerY, 24, bh, "<", false, mx, my);
        drawNavBtn(g, c2 + bw - 24, layerY, 24, bh, ">", false, mx, my);
        String layerName = currentLayerLabel();
        int lx = c1 + 28;
        int lw = bw * 2 + 4 - 32;
        g.drawString(this.font, trim(layerName, this.font, lw), lx,
                layerY + (bh - this.font.lineHeight) / 2, NAV_TXT_SUB, false);
    }

    /**
     * 导航栏按钮文字装不下时的缩短版（★ v7）。
     *
     * <p>「大范围预览：关」在窄导航栏里放不下 —— 与其让它画出按钮边界压住
     * 邻居，不如退化成「大范围·关」。</p>
     */
    private String fitNavLabel(String label, int btnW) {
        int maxW = btnW - 10;
        if (this.font.width(label) <= maxW) return label;
        String compact = label.replace("预览：", "·").replace("视图", "");
        if (this.font.width(compact) <= maxW) return compact;
        return trim(compact, this.font, maxW);
    }

    private void drawNavBtn(GuiGraphics g, int x, int y, int w, int h,
                            String label, boolean active, int mx, int my) {
        boolean hover = inRect(mx, my, x, y, w, h);
        // ★ v10【修"切换按键 UI 突变"】：开关两态改走同一形状（胶囊），
        //   只有填充色变 —— 旧版激活态是"圆角4 + 绿描边"，普通态是"真胶囊无描边"，
        //   并排看着像两个不同世代控件（用户截图指出）。
        UiKit.toggleButton(g, x, y, w, h, label, active, hover, NAV_ACCENT);
    }

    private boolean inRect(double mx, double my, int x, int y, int w, int h) {
        return mx >= x && mx < x + w && my >= y && my < y + h;
    }

    /** 当前图层的中文名（导航栏第三行显示）。 */
    private String currentLayerLabel() {
        var layers = com.geogenesis.client.preview.GeoPalette.PreviewLayer.values();
        return I18nSafe(layers[Math.floorMod(currentMode, layers.length)].labelKey);
    }

    /**
     * <b>自绘按钮命中</b>（★ 与 {@link #drawActionButtons} 共用坐标）。
     *
     * @return true = 已消费本次点击
     */
    private boolean hitActionButton(double mx, double my, int btn) {
        if (btn != 0) return false;
        if (nameDialog.isShowing() || confirmDialog.isShowing()) return false;

        // ★ v26：收起态浮条（视图 / 全局 / 图层 / 保存·重置 / 完成）
        if (!panelOpen) {
            int[] L = collapsedLayout();
            int x = L[0], w = L[1], h = L[2];
            int half = (w - 4) / 2;
            if (inRect(mx, my, x, L[3], w, h)) { toggleSandbox(); return true; }
            if (inRect(mx, my, x, L[4], w, h)) { toggleLargeArea(); return true; }
            if (inRect(mx, my, x, L[5], 24, h)) { cycleLayer(-1); return true; }
            if (inRect(mx, my, x + w - 24, L[5], 24, h)) { cycleLayer(1); return true; }
            if (inRect(mx, my, x, L[6], half, h)) { onSavePreset(); return true; }
            if (inRect(mx, my, x + w - half, L[6], half, h)) { if (!readOnly) doReset(); return true; }
            if (inRect(mx, my, x, L[7], w, h)) { if (!readOnly) doDone(); return true; }
            return false;
        }

        // 应用（★ v21：只保存 + 重建预览，留在面板）
        if (inRect(mx, my, applyBtnX, actionBtnY, applyBtnW, applyBtnH_)) {
            if (!readOnly) doApply();
            return true;
        }
        // 完成（★ v21：应用 + 交出地形 + 关闭面板）
        if (inRect(mx, my, doneBtnX, actionBtnY, doneBtnW_, doneBtnH_)) {
            if (!readOnly) doDone();
            return true;
        }

        // ★ 与 drawActionButtons <b>同一份布局</b>（调用同一个 navButtonLayout()），
        //   不允许这里再推一遍 —— 那是本项目"按钮点不到"的老根因。
        int[] lay = navButtonLayout();
        int c1 = lay[0], c2 = lay[1], bw = lay[2], bh = lay[3];
        int row1Y = lay[4], row2Y = lay[5], layerY = lay[6];

        if (inRect(mx, my, c1, row1Y, bw, bh)) { onSavePreset(); return true; }
        if (inRect(mx, my, c2, row1Y, bw, bh)) { if (!readOnly) doReset(); return true; }
        if (inRect(mx, my, c1, row2Y, bw, bh)) { toggleLargeArea(); return true; }
        if (inRect(mx, my, c2, row2Y, bw, bh)) { toggleSandbox(); return true; }
        if (inRect(mx, my, c1, layerY, 24, bh)) { cycleLayer(-1); return true; }
        if (inRect(mx, my, c2 + bw - 24, layerY, 24, bh)) { cycleLayer(1); return true; }
        return false;
    }

    /**
     * 导航栏底部按钮的<b>唯一布局来源</b>（★ v7）。
     *
     * <p>绘制与命中都调它，从结构上消除"各算一遍 → 点不到"的可能。</p>
     *
     * @return {c1, c2, 按钮宽, 按钮高, 第一行 y, 第二行 y, 第三行 y}
     */
    private int[] navButtonLayout() {
        int bh = 20, gap = 4, rowGap = 3, bottomPad = 10;
        int layerY = this.height - bottomPad - bh;
        int row2Y = layerY - rowGap - bh;
        int row1Y = row2Y - rowGap - bh;

        String l1 = largeAreaLabel().getString();
        String l2 = sandboxLabel().getString();
        int row2Needed = Math.max(this.font.width(l1), this.font.width(l2)) + 14;
        int row1Needed = Math.max(this.font.width("保存"),
                this.font.width(readOnly ? "只读" : "重置")) + 14;
        int needW = Math.max(row1Needed, row2Needed);
        int availW = (navW - 20 - gap) / 2;
        int bw = Math.min(availW, Math.max(44, needW));
        int c1 = navX + (navW - (bw * 2 + gap)) / 2;
        int c2 = c1 + bw + gap;
        return new int[]{c1, c2, bw, bh, row1Y, row2Y, layerY};
    }


    /**
     * 右下角状态字（★ v7：<b>已停用</b>）。
     *
     * <p>它和导航栏底部迷你条用的是同一个 {@code preview.statusLine()} ——
     * 实机截图里左下角出现「1:1 … 98%」两行叠在一起，就是这两处各画了一遍
     * （不是文字重叠，是<b>两个组件画同一个信息</b>）。
     *
     * <p>现在状态只在导航栏迷你条里显示（那里还有进度条，信息更全）。</p>
     */
    private void renderStatusHud(GuiGraphics g) {
        // 有意留空：状态统一由 renderNavRail 的迷你条负责。
    }

    /** 折叠把手的命中矩形（{@link #renderPanelHandle} 每帧写，鼠标事件读）。 */
    private final int[] handleRect = new int[4];
    /** 折叠把手是否命中（供鼠标事件与渲染共用同一矩形）。 */
    private boolean inHandle(double mx, double my) {
        return handleRect[2] > 0
            && mx >= handleRect[0] && mx <= handleRect[0] + handleRect[2]
            && my >= handleRect[1] && my <= handleRect[1] + handleRect[3];
    }

    /** 切换配置窗展开/收起，并重建布局（按钮位置随宽度变）。 */
    private void togglePanel() {
        panelOpen = !panelOpen;
        this.init();
    }

    // ------------------------------------------------------------------ 鼠标

    /** 页签条最下面一行 y（流式换行后可能多行）。 */
    private int tabBarBottom = 0;

    /**
     * 页签条底部 y。
     *
     * <p>★ 2026-11-09 重构：委托 {@link ConfigPanelHost} —— 页签换行算法
     * <b>只存在于宿主一处</b>。此前本类与宿主各有一份换行实现，
     * 改一处忘另一处就会"内容起点与页签画的位置对不上"。</p>
     */
    private int computeTabBarBottom() {
        panelHost.panelX = panelX;
        panelHost.panelW = panelW;
        panelHost.headerY = headerY;
        panelHost.tabLabels = visibleTabs.stream().map(PanelLayout.Tab::label).toList();
        return headerY + panelHost.tabBarHeight();
    }

    /**
     * 页签命中测试：与渲染<b>同一套</b>流式换行算法，避免"画在一处、点判在另一处"。
     *
     * @return 命中的页签索引；未命中返回 -1
     */
    private int hitTestTab(double mx, double my) {
        // ★ v6：页签在左侧导航栏<b>竖排</b>；与 renderNavRail 同一套坐标
        if (mx < navX || mx >= navX + navW) return -1;
        int n = visibleTabs.size();
        // ★ v7：与 renderNavRail 同一份 bottomLimit（否则可见页签数与可点击数不一致）
        int bottomLimit = navMiniTopY() - 6;
        for (int i = 0; i < n; i++) {
            int ty = navTabTop + i * navTabH;
            if (ty + navTabH > bottomLimit) break;
            if (my >= ty && my < ty + navTabH) return i;
        }
        return -1;
    }

    @Override
    public boolean mouseClicked(double mx, double my, int b) {
        if (nameDialog.isShowing()) return nameDialog.mouseClicked(mx, my, b);
        if (confirmDialog.isShowing()) return confirmDialog.mouseClicked(mx, my, b);

        // ★ 折叠把手优先：它在地形之上，命中即切换展开状态
        if (inHandle(mx, my)) {
            togglePanel();
            return true;
        }

        // ★ v26【修"收起态按钮点不到"】：自绘按钮必须在"收起就转发给地形"之前判。
        //   旧顺序是 `if (!panelOpen) return forwardToPreviewClick(...)` 在前，
        //   {@code hitActionButton} 永远到不了 ⇒ 按钮画出来也点不动。
        if (hitActionButton(mx, my, b)) return true;

        // 配置窗收起时，点击除把手/浮条外的任何位置都交给地形（平移/缩放）
        if (!panelOpen) {
            // ★★ v8【修"地图实机不能交互"】：
            //   预览不再注册为 widget（否则会被面板底板盖住），
            //   于是 super.mouseClicked 的 widget 分发<b>根本到不了它</b>
            //   ⇒ 必须在这里显式转发，否则地图完全点不动。
            return forwardToPreviewClick(mx, my, b);
        }

        // ★ v7 自绘按钮：它们不在 widget 列表里，必须在这里命中。
        //   ⚠️ 必须在页签/面板之前判 —— 导航栏底部与内容面板顶栏都压在面板之上。
        if (hitActionButton(mx, my, b)) return true;

        // ★ v22：原「预览地图 / 新建预设」两项已删除（与折叠键、预设页签重复），
        //   对应命中逻辑一并移除。

        // ★ v7【修"左侧标签页点不动"】：
        //   旧代码用 `mx 在内容面板内` 作为前置条件，然后调 hitTestTab ——
        //   而 v6 之后页签已经挪到<b>左侧导航栏</b>，两个矩形不重叠
        //   ⇒ 条件恒假，页签永远点不到（实机：只有悬停高亮，点了没反应）。
        //   hitTestTab 自己会判 navX/navW 范围，这里不再多套一层错误前置。
        int tabHit = hitTestTab(mx, my);
        if (tabHit >= 0) {
            if (tabHit != tab) {
                tab = tabHit;
                scroll = 0;
                pushScrollToPanels();
                Minecraft.getInstance().getSoundManager().play(
                    net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                        net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0F));
            }
            return true;
        }

        if (mx >= panelX && mx <= panelX + panelW && my >= listTop && my <= listBottom) {
            if (panels != null && tab < panels.length && panels[tab].mouseClicked(mx, my, b)) return true;
        }

        // ★ v21：固定区（常驻示意图）的点击 —— 几何来自 host 的 pinnedTop()
        if (isPinnedTab() && panelOpen) {
            int pTop = panelHost.pinnedTop();
            if (my >= pTop - 8 && my <= pTop + panelHost.pinnedHeight) {
                if (worldScalePanel.chartClicked(mx, my, b)) return true;
            }
        }

        // ★★ v17【修"鼠标穿透到面板背后的地图"】★★
        //   配置窗<b>展开</b>时，导航栏 + 内容面板占满左侧一大片。用户在这片区域里
        //   点到空白处（不是任何控件）时，旧代码会一路走到 forwardToPreviewClick
        //   ⇒ 地图在面板背后被拖动/缩放，而用户根本看不到它 —— 表现为"点面板空白，
        //     背后的地图乱动"。
        //   ⇒ 展开态下，只要指针落在「导航栏 ∪ 内容面板」任一矩形内，就<b>视为被遮挡</b>，
        //     直接吞掉事件，不再穿透。
        if (panelOpen && isCoveredByPanel(mx, my)) {
            return true;
        }
        // 面板之外 = 真正可见的地形区：转发给预览
        return forwardToPreviewClick(mx, my, b);
    }

    /**
     * 指针是否落在「被 UI 遮挡」的区域（★ v17）。
     *
     * <p>包含两块：左侧导航栏（整条竖栏）+ 内容面板（底板矩形）。
     * 这两块在展开态是<b>不透明</b>的，背后的地图不可见 ⇒ 不应接受地图操作。</p>
     *
     * <p>注：折叠态（{@code panelOpen == false}）不调用本方法 —— 那时面板已收起，
     * 唯一的可见 UI 是把手，其余区域都应能拖地图。</p>
     */
    private boolean isCoveredByPanel(double mx, double my) {
        // 左侧导航栏：整条竖栏（navX..navX+navW，全高）
        if (mx >= navX && mx < navX + navW) return true;
        // 内容面板：底板矩形（cfgX..cfgX+cfgW，cfgY..cfgY+cfgH）
        return mx >= cfgX && mx < cfgX + cfgW && my >= cfgY && my < cfgY + cfgH;
    }

    /**
     * 把鼠标点击转发给预览控件（★ v8）。
     *
     * <p>为什么需要：预览<b>不注册为 widget</b>（注册的话 {@code super.render}
     * 会在面板底板之前画它，种子输入框会被盖住）。代价是 MC 的 widget 分发
     * 不再触达它 ⇒ 所有交互必须由此处显式转发。</p>
     *
     * <p>只在指针确实落在预览矩形内时才转发，避免点面板空白也去拖地图。</p>
     */
    private boolean forwardToPreviewClick(double mx, double my, int b) {
        if (preview == null) return false;
        if (mx < preview.getX() || mx >= preview.getX() + preview.getWidth()
                || my < preview.getY() || my >= preview.getY() + preview.getHeight()) {
            return false;
        }
        return preview.mouseClicked(mx, my, b);
    }

    /**
     * 指针是否落在预览矩形内（★ v8：拖动/滚轮转发前的守卫）。
     *
     * <p>拖动时指针可能移出预览区（拖得快），但<b>已经开始拖</b>的操作必须继续，
     * 所以拖动用 {@code dragging} 状态判、点击用矩形判。</p>
     */
    private boolean overPreview(double mx, double my) {
        return preview != null
                && mx >= preview.getX() && mx < preview.getX() + preview.getWidth()
                && my >= preview.getY() && my < preview.getY() + preview.getHeight();
    }

    @Override
    public boolean mouseReleased(double mx, double my, int b) {
        if (nameDialog.isShowing()) return true;
        if (confirmDialog.isShowing()) return true;
        if (panels != null && tab < panels.length && panels[tab].mouseReleased(mx, my, b)) return true;
        // ★ v8：预览的拖动状态需要释放（不转发会一直卡在"拖拽中"）
        if (preview != null && preview.mouseReleased(mx, my, b)) return true;
        return super.mouseReleased(mx, my, b);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int b, double dx, double dy) {
        if (nameDialog.isShowing()) return true;
        if (confirmDialog.isShowing()) return true;
        if (panelOpen && panels != null && tab < panels.length
                && mx >= panelX && mx <= panelX + panelW
                && my >= listTop && my <= listBottom) {
            if (panels[tab].mouseDragged(mx, my, b, dx, dy)) return true;
        }
        // ★ v17：被 UI 遮挡的区域不接受地图拖动（否则面板背后的地图会跟着动）
        if (panelOpen && isCoveredByPanel(mx, my)) {
            return true;
        }
        // 真正可见的地形区才交给预览拖动
        if (preview != null && preview.mouseDragged(mx, my, b, dx, dy)) return true;
        return super.mouseDragged(mx, my, b, dx, dy);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (nameDialog.isShowing()) return true;
        if (confirmDialog.isShowing()) return true;
        // 只有落在展开的配置窗内才滚动参数列表，否则滚轮交给地形缩放
        if (panelOpen && mx >= panelX && mx <= panelX + panelW
                && my >= listTop && my <= listBottom) {
            if (panels != null && tab < panels.length && panels[tab].mouseScrolled(mx, my, delta)) {
                return true;
            }
            int contentH = panels != null && tab < panels.length ? panels[tab].getHeight() : 0;
            // ★ v21：panelHost.listBottom 已扣过固定区 ⇒ 直接用，不再重复扣
            int visibleH = panelHost.listBottom - listTop;
            int maxScroll = Math.max(0, contentH - visibleH);
            scroll = Math.max(0, Math.min(maxScroll, scroll - (int) (delta * 24)));
            pushScrollToPanels();
            return true;
        }
        // ★ v17：被 UI 遮挡的区域不接受地图缩放
        if (panelOpen && isCoveredByPanel(mx, my)) {
            return true;
        }
        // 真正可见的地形区才交给预览缩放
        if (preview != null && preview.mouseScrolled(mx, my, delta)) return true;
        return super.mouseScrolled(mx, my, delta);
    }

    /**
     * ★★ 2026-09-18 修复【预览线程池泄漏 / 世界创建变慢的真凶】★★
     *
     * <p>{@code Minecraft.setScreen()} 只调用旧屏的 {@link #removed()}，<b>不会</b>调用
     * {@link #onClose()}。而"应用"按钮走的正是 {@code setScreen(parent)} ⇒ 原实现只在
     * {@code onClose()} 里关预览 ⇒ "应用"路径下预览从未被关闭，4 个 TerrainPool 线程
     * 连同已排队的 480 块任务跑进世界生成阶段（实机证据见 git 历史）。</p>
     * <p>本方法覆盖所有关屏路径（应用 / ESC / 被替换），与 {@link #onClose()} 共用幂等的
     * {@link #closePreview()}。</p>
     */
    @Override
    public void removed() {
        closePreview();
        super.removed();
    }

    private void closePreview() {
        if (preview != null) {
            preview.close();
            preview = null;
            LOGGER.info("[Preview] 预览已关闭（释放 TerrainPool 4 线程与排队任务）");
        }
        // ★ 2026-11-09：已登记接管时<b>不能</b>清掉登记（进世界还要用）；
        //   未登记（ESC 取消 / 只读关闭）则确保没有残留待接管实例。
        if (!offeredForReuse) {
            com.geogenesis.worldgen.generator.GeoGenesisGenerator.clearOfferedPreviewTerrain();
            previewTerrain = null;
        }
    }

    @Override
    public void onClose() {
        closePreview();
        super.onClose();
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (nameDialog.isShowing()) return nameDialog.keyPressed(keyCode, scanCode, modifiers);
        return super.keyPressed(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean charTyped(char codePoint, int modifiers) {
        if (nameDialog.isShowing()) return nameDialog.charTyped(codePoint, modifiers);
        return super.charTyped(codePoint, modifiers);
    }
    @Override public boolean isPauseScreen() { return false; }

    public static Screen create(Minecraft mc, Screen parent) { return new GeoGenesisConfigScreen(parent); }
}
