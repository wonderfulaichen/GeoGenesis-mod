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
import com.geogenesis.client.preview.WorldScalePanel;
import com.geogenesis.config.GeoGenesisConfig;
import com.geogenesis.config.PanelLayout;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;
import com.geogenesis.worldgen.terrain.TerrainParams;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
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
    private static final int TAB_SEL_BG = 0xFF1e222c;
    private static final int TAB_SEL_HOVER = 0xFF252a33;
    private static final int TAB_SEL_TOP = 0xFF00c896;
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
    private Button applyBtn, savePresetBtn, resetBtn, layerPrev, layerNext;
    private Button largeAreaBtn;
    /** ★ S3：平面/沙盘 模式切换按钮。 */
    private Button sandboxBtn;
    private int currentMode = DEFAULT_PREVIEW_LAYER;

    private int panelX, panelW, headerY, listTop, listBottom;
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
     * 计算配置窗的屏幕矩形。
     *
     * <p>★ 2026-11-08 全屏地图改造：预览铺满整个屏幕 (0,0,w,h)，
     * 配置窗是<b>浮在上面</b>的一层，不再挤占预览宽度。
     * 收起时只剩一个把手，展开时覆盖左侧一部分 —— 两种状态下预览都保持全屏。</p>
     *
     * <p>所有绘制与命中测试都调本方法取矩形，避免"画在一处、点判在另一处"。</p>
     */
    private void computePanelRect() {
        int h = this.height;
        cfgW = panelOpen
                ? Math.min(PANEL_W_OPEN, Math.max(320, this.width * 2 / 5))
                : PANEL_W_CLOSED;
        cfgX = PANEL_MARGIN;
        cfgY = PANEL_MARGIN;
        // 展开时留出上下的呼吸空间；太矮就贴到可用高度并内部滚动
        cfgH = panelOpen ? Math.max(120, h - PANEL_MARGIN * 2 - 24) : 24;
    }

    private void layoutPanels() {
        if (panels == null) return;
        for (int i = 0; i < panels.length; i++) {
            panels[i].setBounds(cfgX + 4, listTop, cfgW - 8);
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
        headerY = cfgY + 22;                 // 让出顶部把手/标题行
        // ★ 页签流式换行：先算占几行，再决定列表起点（否则内容会压在换行后的页签下面）
        tabBarBottom = computeTabBarBottom();
        listTop = tabBarBottom + 4;
        // ★ 底部要放两行按钮（应用/保存/重置 + 图层/大范围/沙盘），故留 52px
        listBottom = cfgY + cfgH - BOTTOM_BAR_H;

        Runnable markDirty = () -> { dirty = true; saved = false; debounce = 8; };
        worldScalePanel.setOnMarkDirty(markDirty);
        worldScalePanel.buildFromConfig();
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

        // ---- 底部按钮区（两行，都在面板内，不压列表）----
        //   第一行：应用 / 保存 / 重置
        //   第二行：◀ ▶ 图层 | 大范围 | 平面/沙盘
        int row1Y = listBottom + 4;
        int row2Y = row1Y + 22;
        int btnW = Math.max(40, (panelW - 16) / 3 - 4);

        applyBtn = Button.builder(Component.literal(readOnly ? "只读" : "应用"), b -> doApply())
            .pos(panelX + 4, row1Y).size(btnW, 20).build();
        savePresetBtn = Button.builder(Component.literal("保存"), b -> onSavePreset())
            .pos(panelX + 8 + btnW, row1Y).size(btnW, 20).build();
        resetBtn = Button.builder(Component.literal(readOnly ? "只读" : "重置"), b -> doReset())
            .pos(panelX + 12 + btnW * 2, row1Y).size(btnW, 20).build();
        if (readOnly) { applyBtn.active = false; resetBtn.active = false; }
        addRenderableWidget(applyBtn);
        addRenderableWidget(savePresetBtn);
        addRenderableWidget(resetBtn);

        if (panelOpen && panelW >= 180) {
            int x = panelX + 4;
            int avail = panelW - 12;
            int wArrow = 22;
            int wRest = Math.max(48, (avail - wArrow * 2 - 10) / 2);
            layerPrev = Button.builder(Component.literal("◀"), b -> cycleLayer(-1))
                .pos(x, row2Y).size(wArrow, 20).build();
            layerNext = Button.builder(Component.literal("▶"), b -> cycleLayer(1))
                .pos(x + wArrow + 2, row2Y).size(wArrow, 20).build();
            largeAreaBtn = Button.builder(largeAreaLabel(), b -> toggleLargeArea())
                .pos(x + (wArrow + 2) * 2, row2Y).size(wRest, 20).build();
            largeAreaBtn.setTooltip(net.minecraft.client.gui.components.Tooltip.create(
                Component.literal(I18nSafe("geogenesis.settings.sampling.large.tooltip"))));
            sandboxBtn = Button.builder(sandboxLabel(), b -> toggleSandbox())
                .pos(x + (wArrow + 2) * 2 + wRest + 4, row2Y).size(wRest, 20).build();
            addRenderableWidget(layerPrev);
            addRenderableWidget(layerNext);
            addRenderableWidget(largeAreaBtn);
            addRenderableWidget(sandboxBtn);
        } else {
            layerPrev = null; layerNext = null; largeAreaBtn = null; sandboxBtn = null;
        }

        if (preview != null) {
            preview.setPosition(previewX, previewY, previewW, previewH);
            if (!previewRegistered) {
                addRenderableWidget(preview);
                previewRegistered = true;
            }
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
        TerrainParams p = GeoGenesisConfig.INSTANCE.buildParams();
        boolean morphBase = worldCtx != null || Minecraft.getInstance().level == null
                || com.geogenesis.worldgen.generator.GeoGenesisGenerator.usesCurrentWorldMorphBase();
        CellGenerator gen = new CellGenerator(p, p.minY(), p.maxY(), morphBase);
        GeoGenesisTerrain terrain = new GeoGenesisTerrain(gen);
        terrain.seed(seed);
        if (preview == null) {
            preview = new PreviewDisplay(previewX, previewY, previewW, previewH, terrain, seed, p, currentMode);
            // ★ 全屏地图：预览自带缩放标签会压在地形正中，改由右下角 HUD 显示
            preview.setDrawZoomLabel(false);
        } else {
            preview.setTerrain(terrain, seed, p);
            preview.setMode(currentMode);
        }
        Minecraft mc = Minecraft.getInstance();
        preview.setStructureContext(
            worldCtx != null ? ctxRegistry(worldCtx)
                    : (mc.level != null ? mc.level.registryAccess() : null),
            worldCtx != null ? ctxGenerator(worldCtx) : currentGenerator(mc),
            seed);
        preview.setPosition(previewX, previewY, previewW, previewH);
        if (!previewRegistered) {
            addRenderableWidget(preview);
            previewRegistered = true;
        }
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

    /** 沙盘按钮文案。 */
    private Component sandboxLabel() {
        boolean on = preview != null && preview.isSandboxMode();
        return Component.literal(on ? "沙盘视图" : "平面视图");
    }

    /** 平面 ⇄ 沙盘 切换。 */
    private void toggleSandbox() {
        if (preview == null) return;
        preview.setSandboxMode(!preview.isSandboxMode());
        if (sandboxBtn != null) sandboxBtn.setMessage(sandboxLabel());
    }

    private void cycleLayer(int d) {
        currentMode = (currentMode + d + GeoPalette.PreviewLayer.values().length)
                % GeoPalette.PreviewLayer.values().length;
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
        if (largeAreaBtn != null) largeAreaBtn.setMessage(largeAreaLabel());
    }

    private void doApply() {
        GeoGenesisConfig.SPEC.save();
        saved = true;
        dirty = false;
        if (parent instanceof net.minecraft.client.gui.screens.worldselection.CreateWorldScreen cws) {
            com.geogenesis.client.GeoGenesisClient.writeSeedToCreateWorld(cws, seed);
        }
        Minecraft.getInstance().setScreen(parent);
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

    @Override
    public void tick() {
        super.tick();
        if (dirty && debounce > 0) { debounce--; if (debounce <= 0) rebuildPreviewIfChanged(); }
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

        // ---------------- 第 1 层：全屏地形（widget 系统，含预览自身） ----------------
        super.render(g, mx, my, pt);

        // ---------------- 第 2 层：左侧悬浮配置窗 ----------------
        computePanelRect();
        if (panelOpen) {
            renderPanelOverlay(g, mx, my);
        }
        renderPanelHandle(g, mx, my);

        // ---------------- 第 3 层：页签与内容 ----------------
        if (panelOpen && !dialogActive) {
            int n = visibleTabs.size();
            // ★ 2026-11-08 修复页签溢出：按文字宽度流式换行，不再强行等分（11 个页签等分必然超宽）
            int cxTab = panelX + 6;
            int cyTab = headerY;
            int rowMaxX = panelX + panelW - 6;
            for (int i = 0; i < n; i++) {
                String label = visibleTabs.get(i).label();
                int tw = this.font.width(label) + 20;
                if (cxTab + tw > rowMaxX && cxTab > panelX + 6) {
                    cxTab = panelX + 6;
                    cyTab += TAB_H + 4;
                }
                boolean on = tab == i;
                boolean hover = mx >= cxTab && mx <= cxTab + tw && my >= cyTab && my <= cyTab + TAB_H;
                int bg = on ? (hover ? TAB_SEL_HOVER : TAB_SEL_BG)
                            : (hover ? TAB_PILL_HOVER : TAB_PILL_BG);
                drawRoundedPill(g, cxTab, cyTab, tw, TAB_H, TAB_PILL_R, bg, on);
                if (on) g.fill(cxTab + TAB_PILL_R, cyTab, cxTab + tw - TAB_PILL_R, cyTab + 2, TAB_SEL_TOP);
                g.drawCenteredString(this.font, label, cxTab + tw / 2,
                    cyTab + (TAB_H - this.font.lineHeight) / 2 + 1, on ? C_TEXT : TAB_INACTIVE_TEXT);
                cxTab += tw + 4;
            }
            tabBarBottom = cyTab + TAB_H;

            if (layerPrev != null && largeAreaBtn != null) {
                Component want = largeAreaLabel();
                if (!want.equals(largeAreaBtn.getMessage())) largeAreaBtn.setMessage(want);
            }

            g.enableScissor(panelX, listTop, panelX + panelW, listBottom);
            if (panels != null && tab < panels.length) {
                ConfigPanel active = panels[tab];
                if (active == biomesPanel) biomesPanel.setPanelBottom(listBottom);
                active.render(g, mx, my);
            }
            g.disableScissor();

            if (presetsPanel != null) {
                presetsPanel.setWidgetsVisible(tab == visibleTabs.indexOf(PanelLayout.Tab.PRESETS));
                presetsPanel.setViewportHeight(listBottom - presetsPanel.getScrollTopY());
            }

            // widget（按钮等）要在面板之上重画一次：super.render 已画过一遍，
            // 但当时被预览覆盖，这里补画悬浮窗内部的可交互件
            renderPanelWidgets(g, mx, my, pt);

            if (panels != null && tab < panels.length) {
                Component tip = panels[tab].consumeHoverTooltip();
                if (tip != null) g.renderTooltip(font, tip, mx, my);
            }
        }

        // ---------------- 第 4 层：右下角状态字 ----------------
        renderStatusHud(g);

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

    // ------------------------------------------------------------------ 悬浮层绘制

    /** 配置窗的半透明底板 + 边框。展开时地形仍能从边缘透出来。 */
    private void renderPanelOverlay(GuiGraphics g, int mx, int my) {
        // 底板：深色半透明（不是全不透明，能看到下方地形轮廓）
        g.fill(cfgX, cfgY, cfgX + cfgW, cfgY + cfgH, 0xC8101216);
        // 1px 描边：在全屏地形上区分层次
        g.fill(cfgX, cfgY, cfgX + cfgW, cfgY + 1, 0xFF3a4250);
        g.fill(cfgX, cfgY + cfgH - 1, cfgX + cfgW, cfgY + cfgH, 0xFF3a4250);
        g.fill(cfgX, cfgY, cfgX + 1, cfgY + cfgH, 0xFF3a4250);
        g.fill(cfgX + cfgW - 1, cfgY, cfgX + cfgW, cfgY + cfgH, 0xFF3a4250);
        // 顶部标题行
        g.drawString(this.font, "GeoGenesis 地形配置", cfgX + 8, cfgY + 6, C_TEXT, false);
    }

    /**
     * 折叠把手。
     *
     * <p>收起时它就是唯一的入口（`▶ 配置`）；展开时变成标题行右侧的 `◀` 收起按钮。
     * 位置固定在左上角，与配置窗同高对齐，不随展开状态跳动。</p>
     */
    private void renderPanelHandle(GuiGraphics g, int mx, int my) {
        int hx = cfgX;
        int hy = cfgY + (panelOpen ? 0 : 0);
        int hw = panelOpen ? 22 : PANEL_W_CLOSED;
        int hh = 18;
        if (panelOpen) {
            hx = cfgX + cfgW - 26;   // 展开时放到标题行右端
        }
        boolean hover = mx >= hx && mx <= hx + hw && my >= hy && my <= hy + hh;
        int bg = hover ? TAB_PILL_HOVER : TAB_PILL_BG;
        g.fill(hx, hy, hx + hw, hy + hh, bg);
        String label = panelOpen ? "◀" : "▶ 配置";
        int tw = this.font.width(label);
        g.drawString(this.font, label, hx + (hw - tw) / 2,
                hy + (hh - this.font.lineHeight) / 2 + 1, hover ? C_TEXT : TAB_INACTIVE_TEXT, false);
        handleRect[0] = hx; handleRect[1] = hy; handleRect[2] = hw; handleRect[3] = hh;
    }

    /** 把配置窗内部的 widget（应用/保存/重置/图层按钮）重画一遍，盖在地形之上。 */
    private void renderPanelWidgets(GuiGraphics g, int mx, int my, float pt) {
        if (applyBtn != null && applyBtn.visible) applyBtn.render(g, mx, my, pt);
        if (savePresetBtn != null && savePresetBtn.visible) savePresetBtn.render(g, mx, my, pt);
        if (resetBtn != null && resetBtn.visible) resetBtn.render(g, mx, my, pt);
        if (layerPrev != null && layerPrev.visible) layerPrev.render(g, mx, my, pt);
        if (layerNext != null && layerNext.visible) layerNext.render(g, mx, my, pt);
        if (largeAreaBtn != null && largeAreaBtn.visible) largeAreaBtn.render(g, mx, my, pt);
        if (sandboxBtn != null && sandboxBtn.visible) sandboxBtn.render(g, mx, my, pt);
    }

    /** 右下角状态字：缩放比例 / 采样档位 / 耗时。极简，不挡地形。 */
    private void renderStatusHud(GuiGraphics g) {
        if (preview == null) return;
        String txt = preview.statusLine();
        if (txt == null || txt.isEmpty()) return;
        int tw = this.font.width(txt);
        int pad = 4;
        int x = this.width - tw - pad * 2 - 6;
        int y = this.height - this.font.lineHeight - pad * 2 - 6;
        g.fill(x, y, x + tw + pad * 2, y + this.font.lineHeight + pad * 2, 0x99000000);
        g.drawString(this.font, txt, x + pad, y + pad, 0xCCCCCCCC, false);
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

    /** 按当前页签集合预演流式换行，返回页签条底部的 y。 */
    private int computeTabBarBottom() {
        int n = visibleTabs.size();
        int cxTab = panelX + 6;
        int cyTab = headerY;
        int rowMaxX = panelX + panelW - 6;
        for (int i = 0; i < n; i++) {
            int tw = this.font.width(visibleTabs.get(i).label()) + 20;
            if (cxTab + tw > rowMaxX && cxTab > panelX + 6) {
                cxTab = panelX + 6;
                cyTab += TAB_H + 4;
            }
            cxTab += tw + 4;
        }
        return cyTab + TAB_H;
    }

    /**
     * 页签命中测试：与渲染<b>同一套</b>流式换行算法，避免"画在一处、点判在另一处"。
     *
     * @return 命中的页签索引；未命中返回 -1
     */
    private int hitTestTab(double mx, double my) {
        int n = visibleTabs.size();
        int cxTab = panelX + 6;
        int cyTab = headerY;
        int rowMaxX = panelX + panelW - 6;
        for (int i = 0; i < n; i++) {
            int tw = this.font.width(visibleTabs.get(i).label()) + 20;
            if (cxTab + tw > rowMaxX && cxTab > panelX + 6) {
                cxTab = panelX + 6;
                cyTab += TAB_H + 4;
            }
            if (mx >= cxTab && mx <= cxTab + tw && my >= cyTab && my <= cyTab + TAB_H) return i;
            cxTab += tw + 4;
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

        // 配置窗收起时，点击除把手外的任何位置都交给地形（平移/缩放）
        if (!panelOpen) {
            return super.mouseClicked(mx, my, b);
        }

        if (my >= headerY && my <= tabBarBottom && mx >= panelX && mx <= panelX + panelW) {
            int hit = hitTestTab(mx, my);
            if (hit >= 0 && hit != tab) {
                tab = hit;
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
        return super.mouseClicked(mx, my, b);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int b) {
        if (nameDialog.isShowing()) return true;
        if (confirmDialog.isShowing()) return true;
        if (panels != null && tab < panels.length && panels[tab].mouseReleased(mx, my, b)) return true;
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
        // 配置窗之外（或收起时全局）交给地形：平移
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
            int visibleH = listBottom - listTop;
            int maxScroll = Math.max(0, contentH - visibleH);
            scroll = Math.max(0, Math.min(maxScroll, scroll - (int) (delta * 20)));
            pushScrollToPanels();
            return true;
        }
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
