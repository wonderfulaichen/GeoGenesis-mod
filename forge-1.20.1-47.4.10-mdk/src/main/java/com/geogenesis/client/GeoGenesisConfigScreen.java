package com.geogenesis.client;

import com.geogenesis.client.preview.BiomesPanel;
import com.geogenesis.client.preview.CachePanel;
import com.geogenesis.client.preview.ClimateConfigPanel;
import com.geogenesis.client.preview.ColormapPanel;
import com.geogenesis.client.preview.ConfigPanel;
import com.geogenesis.client.preview.DisplayPanel;
import com.geogenesis.client.preview.ParameterConfigPanel;
import com.geogenesis.client.preview.Preset;
import com.geogenesis.client.preview.PresetsPanel;
import com.geogenesis.client.preview.PreviewDisplay;
import com.geogenesis.client.preview.SamplingPanel;
import com.geogenesis.client.preview.TerrainConfigPanel;
import com.geogenesis.client.preview.GeoPalette;
import com.geogenesis.config.GeoGenesisConfig;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;
import com.geogenesis.worldgen.terrain.TerrainParams;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import com.geogenesis.client.SeedManager.SeedEntry;

/**
 * GeoGenesis 配置屏（三页签面板版）。
 *
 * <p>左侧三页签面板（地形/气候/参数）+ 右侧预览地图。
 * 面板内部通过 scrollOffset 自适配滚动（不依赖 GuiGraphics translate）。
 */
public class GeoGenesisConfigScreen extends Screen {

    private static final Logger LOGGER = LogManager.getLogger("geogenesis");
    private static final int TERRAIN_TYPE_LAYER = 9;

    private final Screen parent;
    private long seed = (long) (Math.random() * Long.MAX_VALUE);

    private int tab = 0;
    /** 标签页名：上游→下游排列。页签0=世界参数（最上游），页签1=气候，页签2=地形（含雪线） */
    private static final String[] TAB_NAMES = {"预设", "世界参数", "气候", "地形", "显示", "采样", "色带", "缓存", "群系"};

    /** 标签页导航条配色（仿设置页 TabNavigationBar 原生形态 + 主屏深绿主题） */
    private static final int TAB_H = 24;              // 标签条高度
    private static final int TAB_BAR_BG = 0xFF161A22;   // 标签条凹陷背景
    private static final int TAB_PILL_BG = 0xFF2a3340;  // 未选中标签 pill
    private static final int TAB_PILL_HOVER = 0xFF353c48; // hover 未选中
    private static final int TAB_SEL_BG = 0xFF1e222c;   // 选中标签=面板色（连入内容）
    private static final int TAB_SEL_HOVER = 0xFF252a33; // hover 选中
    private static final int TAB_SEL_TOP = 0xFF00c896;   // 选中标签顶部绿色高亮
    private static final int TAB_INACTIVE_TEXT = 0xFF9aa0aa; // 未选中标签次级字
    private static final int C_TEXT = 0xFFe0e0e0;     // 选中标签亮字
    private static final int C_LINE = 0xFF333333;     // 分隔线
    private static final int TAB_PILL_R = 4;          // 标签 pill 圆角半径

    /** 确认对话框 */
    private final ConfirmDialog confirmDialog = new ConfirmDialog();

    private final TerrainConfigPanel terrainPanel = new TerrainConfigPanel();
    private final ClimateConfigPanel climatePanel = new ClimateConfigPanel();
    private final ParameterConfigPanel paramPanel = new ParameterConfigPanel();

    // 设置类页签面板（运行时显示状态，懒构造以保留列表滚动位置）
    private DisplayPanel displayPanel;
    private SamplingPanel samplingPanel;
    private ColormapPanel colormapPanel;
    private CachePanel cachePanel;
    private BiomesPanel biomesPanel;
    private PresetsPanel presetsPanel;
    private ConfigPanel[] panels;

    private PreviewDisplay preview;
    private Button saveBtn, resetBtn, layerPrev, layerNext;
    private int currentMode = TERRAIN_TYPE_LAYER;

    private int panelX, panelW, headerY, listTop, listBottom;
    private int previewX, previewY, previewW, previewH;
    private int scroll;
    private boolean dirty, saved;
    private int debounce;
    /** 一次性诊断标记：渲染首帧打印 preview 是否仍在 widget 列表（排查"返回后预览消失"） */
    private boolean diagFirstFrame = true;
    /** 跟踪 preview 是否已登记进当前屏幕的 widget 列表（clearWidgets 后会置 false） */
    private boolean previewRegistered = false;
    /** 上次已应用参数快照。比较 hash 决定是否真正需要 rebuild。
     *  解决"点击空白区域就刷新"——面板 slider 在 click 不改变值时也会触发 markDirty。 */
    private TerrainParams lastAppliedParams = null;
    private long lastAppliedSeed = -1;

    public GeoGenesisConfigScreen(Screen parent) {
        super(Component.literal("GeoGenesis 配置"));
        this.parent = parent;
    }
    public GeoGenesisConfigScreen(Screen parent, int tab) {
        super(Component.literal("GeoGenesis 配置"));
        this.parent = parent;
        this.tab = tab;
    }
    public GeoGenesisConfigScreen() { this(null); }

    @Override
    protected void init() {
        super.init();
        // 重新进入（如从设置屏返回）时清掉旧 widget，避免重复累积；
        // 同时保证 preview 在 rebuildPreview 中被重新登记进列表（修复"返回后预览消失"）。
        this.clearWidgets();
        diagFirstFrame = true;
        previewRegistered = false;
        LOGGER.info("DIAG-GCS: init enter, preview==null={}, previewRegistered={}", preview == null, previewRegistered);
        int w = width, h = height;
        panelX = 10;
        panelW = (w - 40) * 5 / 9;
        headerY = 20;  // 标签条贴顶部（标题 y=12 之下）
        listTop = headerY + TAB_H;
        listBottom = h - 44;
        previewX = panelX + panelW + 20;
        previewY = 90;
        previewW = w - previewX - 10;
        previewH = listBottom - previewY;

        // 种子栏（输入框 + ★ + 刷新）已迁移进 PresetsPanel，主屏不再持有这些 widget

        Runnable markDirty = () -> { dirty = true; saved = false; debounce = 8; };
        terrainPanel.setOnMarkDirty(markDirty);
        terrainPanel.setBounds(panelX + 4, listTop, panelW - 8);
        terrainPanel.buildFromConfig();
        climatePanel.setOnMarkDirty(markDirty);
        climatePanel.setBounds(panelX + 4, listTop, panelW - 8);
        climatePanel.buildClimateFactors();
        paramPanel.setOnMarkDirty(markDirty);
        paramPanel.setOnShowConfirm((title, msg, affected, onCncl, onCnfrm) -> {
            confirmDialog.show(title, msg, affected, onCnfrm, onCncl);
        });
        paramPanel.setBounds(panelX + 4, listTop, panelW - 8);
        paramPanel.buildFromConfig();

        saveBtn = Button.builder(Component.literal("保存"), b -> doSave())
            .pos(panelX, listBottom + 6).size(60, 20).build();
        resetBtn = Button.builder(Component.literal("重置"), b -> doReset())
            .pos(panelX + 64, listBottom + 6).size(60, 20).build();
        addRenderableWidget(saveBtn);
        addRenderableWidget(resetBtn);

        // 按钮在 widget 上方留出 4px 间隙
        layerPrev = Button.builder(Component.literal("◀ 图层"), b -> cycleLayer(-1))
            .pos(previewX, previewY - 24).size(70, 20).build();
        layerNext = Button.builder(Component.literal("图层 ▶"), b -> cycleLayer(1))
            .pos(previewX + previewW - 70, previewY - 24).size(70, 20).build();
        addRenderableWidget(layerPrev);
        addRenderableWidget(layerNext);

        // 始终重新登记 preview widget（clearWidgets 后必须重建，否则返回后预览消失）。
        // 思路对齐参考项目 World-Preview-TFC：preview 实例跨屏持久复用，init() 仅「重加同一实例」而非重建，
        // 其内部纹理与采样缓存保留，返回设置屏后无缝衔接。
        if (preview != null) {
            preview.setPosition(previewX, previewY, previewW, previewH);
            if (!previewRegistered) {
                addRenderableWidget(preview);
                previewRegistered = true;
            }
        }
        // 首次(preview==null)创建实例；重入后仅当参数/种子真变化才重建采样缓存，
        // 否则跳过 invalidateAll + 视图重置（避免棋盘/错位重载闪）。
        rebuildPreviewIfChanged();

        // 设置类页签面板（运行时显示状态，懒构造一次以保留列表滚动位置）
        if (presetsPanel == null) presetsPanel = new PresetsPanel(
            () -> seed,
            s -> { seed = s; rebuildPreview(); },
            () -> { seed = (long) (Math.random() * Long.MAX_VALUE); rebuildPreview(); },
            this::showApplyPresetConfirm,
            this::addRenderableWidget);
        if (displayPanel == null) displayPanel = new DisplayPanel(preview);
        if (samplingPanel == null) samplingPanel = new SamplingPanel(preview);
        if (colormapPanel == null) colormapPanel = new ColormapPanel(preview);
        if (cachePanel == null) cachePanel = new CachePanel(preview);
        if (biomesPanel == null) biomesPanel = new BiomesPanel(preview);
        panels = new ConfigPanel[]{ presetsPanel, paramPanel, climatePanel, terrainPanel,
            displayPanel, samplingPanel, colormapPanel, cachePanel, biomesPanel };
        int px0 = panelX + 4, pTop0 = listTop, pW0 = panelW - 8;
        for (ConfigPanel p : panels) p.setBounds(px0, pTop0, pW0);

        pushScrollToPanels();
        LOGGER.info("DIAG-GCS: init done, previewRegistered={}", previewRegistered);
    }

    /** 把当前 scroll 值同步到活动面板 */
    private void pushScrollToPanels() {
        if (panels == null) return;
        for (int i = 0; i < panels.length; i++) panels[i].setScrollOffset(i == tab ? scroll : 0);
    }

    // ---- 预览 ----

    private void rebuildPreview() {
        TerrainParams p = GeoGenesisConfig.INSTANCE.buildParams();
        CellGenerator gen = new CellGenerator(p, p.minY(), p.maxY());
        gen.seed(seed);
        GeoGenesisTerrain terrain = new GeoGenesisTerrain(gen);
        if (preview == null) {
            // ★ 直接传 mode，构造器内设好 activeLayer + requestResample，无需 setMode
            preview = new PreviewDisplay(previewX, previewY, previewW, previewH, terrain, seed, p, currentMode);
        } else {
            preview.setTerrain(terrain, seed, p);
            preview.setMode(currentMode);
        }
        // 每次都同步几何位置（重入 init 后坐标可能变化），并确保已登记进 widget 列表
        preview.setPosition(previewX, previewY, previewW, previewH);
        if (!previewRegistered) {
            addRenderableWidget(preview);
            previewRegistered = true;
        }
        // 记录已应用参数（避免下次相同 markDirty 触发重建）
        lastAppliedParams = p;
        lastAppliedSeed = seed;
        dirty = false;
    }

    /** ▾ 收藏下拉浮层已移除：其功能（重命名/列表/删除）整合进 PresetsPanel 的「收藏种子」区 */

    /**
     * 绘制标签条 pill（圆角矩形，可选平底用于选中标签下连内容区）。
     * 用 MC 的 {@link GuiGraphics#fill} 组合实现：中央矩形 + 四个角的近似弧（用小矩形填充转角视觉）。
     * 选中标签（flatBottom=true）底部不圆，与下方面板无缝连接。
     */
    private void drawRoundedPill(GuiGraphics g, int x, int y, int w, int h, int r, int color, boolean flatBottom) {
        int r2 = Math.min(r, Math.min(w / 2, h / 2));
        // 中央矩形
        g.fill(x, y + r2, x + w, y + h - (flatBottom ? 0 : r2), color);
        // 上下条带（除圆角区域）
        g.fill(x + r2, y, x + w - r2, y + h, color);
        // 顶/底圆角：用四个小方形填补转角（r2×r2），近似圆弧
        int xL = x, xR = x + w - r2;
        if (flatBottom) {
            // 上圆角 + 下平
            g.fill(xL, y, xL + r2, y + r2, color);              // TL
            g.fill(xR, y, xR + r2, y + r2, color);              // TR
        } else {
            g.fill(xL, y, xL + r2, y + r2, color);              // TL
            g.fill(xR, y, xR + r2, y + r2, color);              // TR
            g.fill(xL, y + h - r2, xL + r2, y + h, color);      // BL
            g.fill(xR, y + h - r2, xR + r2, y + h, color);      // BR
        }
    }

    private void cycleLayer(int d) {
        currentMode = (currentMode + d + GeoPalette.PreviewLayer.values().length) % GeoPalette.PreviewLayer.values().length;
        if (preview != null) preview.setMode(currentMode);
    }
    private void doSave() { GeoGenesisConfig.SPEC.save(); saved = true; dirty = false; }
    private void doReset() {
        // 重置到默认值：~120 个 .set() 各自触发文件写入，Windows 文件锁可能抛 WritingException
        // 捕获并继续——部分字段未写入的后续面板重建也会覆盖
        try {
            GeoGenesisConfig.INSTANCE.resetToDefault();
        } catch (Exception e) {
            // 文件写竞争忽略
        }
        // 保持当前 seed 不变，仅重置参数并刷新面板+预览
        terrainPanel.buildFromConfig();
        climatePanel.buildClimateFactors();
        paramPanel.buildFromConfig();
        scroll = 0;
        pushScrollToPanels();
        lastAppliedParams = null; // 强制下次 rebuild
        rebuildPreview();
    }

    /** 点击预设卡片 → 弹确认框（覆盖全部参数前需用户确认） */
    private void showApplyPresetConfirm(Preset p) {
        confirmDialog.show("应用预设：" + p.name,
            "将用预设「" + p.name + "」覆盖当前全部地形 / 气候参数。",
            List.of("海陆偏置、海深、地形类型范围、起伏振幅、气候阈值等全部参数"),
            () -> applyPreset(p),
            () -> {});
    }

    /** 应用预设：回默认 → 写入覆盖表 → 重建预览 → 持久化 → 同步各面板 UI */
    private void applyPreset(Preset p) {
        GeoGenesisConfig.INSTANCE.resetToDefault();
        for (var e : p.overrides.entrySet()) e.getKey().set(e.getValue());
        // 同步其余面板的滑块/控件显示（resetToDefault 只改了配置，UI 仍是旧值）
        terrainPanel.buildFromConfig();
        climatePanel.buildClimateFactors();
        paramPanel.buildFromConfig();
        scroll = 0;
        pushScrollToPanels();
        lastAppliedParams = null; // 强制下次 rebuild
        rebuildPreview();
        try { GeoGenesisConfig.SPEC.save(); } catch (Exception ignored) { /* 文件写竞争忽略 */ }
    }

    @Override
    public void tick() {
        super.tick();
        if (dirty && debounce > 0) { debounce--; if (debounce <= 0) rebuildPreviewIfChanged(); }
    }

    /** 只在参数真的变化时才重建预览（解决"点击空白也刷新"问题） */
    private void rebuildPreviewIfChanged() {
        TerrainParams p = GeoGenesisConfig.INSTANCE.buildParams();
        if (lastAppliedParams != null
                && lastAppliedParams.equals(p)
                && lastAppliedSeed == seed) {
            // 参数和种子都没变 → 跳过 rebuild
            dirty = false;
            return;
        }
        lastAppliedParams = p;
        lastAppliedSeed = seed;
        rebuildPreview();
    }

    // ---- 渲染（不依赖 translate，面板内部 scrollOffset 自适配） ----

    @Override
    public void renderBackground(GuiGraphics g) {
        // 自定义深色背景，避免原版 dirt 背景
        g.fill(0, 0, this.width, this.height, 0xFF0e1218);
        g.fill(0, 0, this.width, 1, 0xFF2a2f3a);
        g.fill(0, this.height - 1, this.width, this.height, 0xFF2a2f3a);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        this.renderBackground(g);
        g.drawCenteredString(this.font, "GeoGenesis 地形配置", width / 2, 12, 0xFFFFFF);

        // ---- 标签页导航条：手绘圆角 pill（MC 形态 + 深绿配色，贴顶部）----
        int n = TAB_NAMES.length;
        int segW = Math.max(40, (panelW - 8) / n);
        for (int i = 0; i < n; i++) {
            boolean on = tab == i;
            int bx = panelX + i * (segW + 4);
            boolean hover = mx >= bx && mx <= bx + segW && my >= headerY && my <= headerY + TAB_H;
            int bg = on ? (hover ? TAB_SEL_HOVER : TAB_SEL_BG)
                        : (hover ? TAB_PILL_HOVER : TAB_PILL_BG);
            drawRoundedPill(g, bx, headerY, segW, TAB_H, TAB_PILL_R, bg, on);
            if (on) g.fill(bx + TAB_PILL_R, headerY, bx + segW - TAB_PILL_R, headerY + 2, TAB_SEL_TOP);
            g.drawCenteredString(this.font, TAB_NAMES[i], bx + segW / 2,
                headerY + (TAB_H - this.font.lineHeight) / 2 + 1, on ? C_TEXT : TAB_INACTIVE_TEXT);
        }

        // 图层名由 widget 内部图例显示，此处不重复绘制（避免与按钮重叠）

        // 左栏面板：裁剪区 + 直接渲染（无 translate），按 tab 索引分发
        g.enableScissor(panelX, listTop, panelX + panelW, listBottom);
        if (panels != null) {
            // 群系面板需要知道面板底 y 才能填满整个区域（消除底部空白）
            if (panels[tab] == biomesPanel) biomesPanel.setPanelBottom(listBottom);
            panels[tab].render(g, mx, my);
            if (tab == 3) terrainPanel.renderHeaderTooltip(g, mx, my);
        }
        g.disableScissor();

        // 预设面板的种子栏 widgets 仅在预设标签可见（其他标签隐藏，避免覆盖气候/地形等面板内容）
        if (presetsPanel != null) {
            presetsPanel.setWidgetsVisible(tab == 0);
            // 同步滚动区视口高度（listBottom - 滚动顶），保证 maxScroll 用真实视口
            presetsPanel.setViewportHeight(listBottom - presetsPanel.getScrollTopY());
        }

        super.render(g, mx, my, pt);

        // 悬停 tooltip（在 scissor 外、对话框下层绘制）
        if (panels != null) {
            Component tip = panels[tab].consumeHoverTooltip();
            if (tip != null) g.renderTooltip(font, tip, mx, my);
        }

        // 一次性诊断：确认 preview 已登记且坐标有效
        if (diagFirstFrame) {
            diagFirstFrame = false;
            LOGGER.info("DIAG-GCS: render first-frame, previewRegistered={}, x={} y={} w={} h={}",
                previewRegistered,
                preview == null ? "-" : preview.getX(),
                preview == null ? "-" : preview.getY(),
                preview == null ? "-" : preview.getWidth(),
                preview == null ? "-" : preview.getHeight());
        }

        // 确认对话框（最上层）
        if (confirmDialog.isShowing()) {
            confirmDialog.render(g, mx, my);
        }
    }

    private String I18nSafe(String key) {
        String v = net.minecraft.client.resources.language.I18n.get(key);
        return v.equals(key) ? key : v;
    }

    // ---- 鼠标（直接传递原始坐标，面板内部做 scroll 偏移） ----

    @Override
    public boolean mouseClicked(double mx, double my, int b) {
        if (confirmDialog.isShowing()) return confirmDialog.mouseClicked(mx, my, b);
        // 标签页点击（命中标签条区域切换页签）
        if (mx >= panelX && mx <= panelX + panelW && my >= headerY && my <= headerY + TAB_H) {
            int segW = Math.max(40, (panelW - 8) / TAB_NAMES.length);
            int idx = (int) ((mx - panelX) / (segW + 4));
            if (idx >= 0 && idx < TAB_NAMES.length && idx != tab) {
                tab = idx; scroll = 0;
                if (idx == 3) terrainPanel.refreshHeightDependent();
                pushScrollToPanels();
                Minecraft.getInstance().getSoundManager().play(
                    net.minecraft.client.resources.sounds.SimpleSoundInstance.forUI(
                        net.minecraft.sounds.SoundEvents.UI_BUTTON_CLICK, 1.0F));
            }
            return true;
        }

        if (mx >= panelX && mx <= panelX + panelW && my >= listTop && my <= listBottom) {
            if (panels != null && panels[tab].mouseClicked(mx, my, b)) return true;
        }
        return super.mouseClicked(mx, my, b);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int b) {
        if (confirmDialog.isShowing()) return true;
        // ★ 修复：面板处理了就不传给 super，防止预览被误触
        if (panels != null && panels[tab].mouseReleased(mx, my, b)) return true;
        return super.mouseReleased(mx, my, b);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int b, double dx, double dy) {
        if (confirmDialog.isShowing()) return true;
        if (panels != null && panels[tab].mouseDragged(mx, my, b, dx, dy)) return true;
        return super.mouseDragged(mx, my, b, dx, dy);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (confirmDialog.isShowing()) return true;
        if (mx >= panelX && mx <= panelX + panelW) {
            // 先给当前面板机会处理内部滚动（如色带/群系列表）
            int panelX0 = panelX, panelX1 = panelX + panelW;
            LOGGER.info("DIAG-GCS-SCROLL: mx={} my={} delta={} panelX=[{},{}] tab={}",
                mx, my, delta, panelX0, panelX1, tab);
            if (panels != null && panels[tab].mouseScrolled(mx, my, delta)) {
                LOGGER.info("DIAG-GCS-SCROLL: panel consumed scroll");
                return true;
            }
            int contentH = panels != null ? panels[tab].getHeight() : 0;
            int visibleH = listBottom - listTop;
            int maxScroll = Math.max(0, contentH - visibleH);
            scroll = Math.max(0, Math.min(maxScroll, scroll - (int) (delta * 20)));
            LOGGER.info("DIAG-GCS-SCROLL: outer scroll, scroll={}", scroll);
            pushScrollToPanels();
            return true;
        }
        return super.mouseScrolled(mx, my, delta);
    }

    @Override
    public void onClose() {
        if (dirty && !saved) Minecraft.getInstance().setScreen(new GeoGenesisConfigScreen(parent, tab));
        super.onClose();
    }
    @Override public boolean isPauseScreen() { return false; }

    public static Screen create(Minecraft mc, Screen parent) { return new GeoGenesisConfigScreen(); }
}
