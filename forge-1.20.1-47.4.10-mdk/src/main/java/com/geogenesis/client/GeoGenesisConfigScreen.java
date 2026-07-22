package com.geogenesis.client;

import com.geogenesis.client.preview.ClimateConfigPanel;
import com.geogenesis.client.preview.ParameterConfigPanel;
import com.geogenesis.client.preview.PreviewDisplay;
import com.geogenesis.client.preview.TerrainConfigPanel;
import com.geogenesis.client.preview.GeoPalette;
import com.geogenesis.config.GeoGenesisConfig;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;
import com.geogenesis.worldgen.terrain.TerrainParams;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.util.Arrays;
import java.util.List;
import com.geogenesis.client.SeedManager.SeedEntry;
import com.geogenesis.client.settings.SettingsScreen;

/**
 * GeoGenesis 配置屏（三页签面板版）。
 *
 * <p>左侧三页签面板（地形/气候/参数）+ 右侧预览地图。
 * 面板内部通过 scrollOffset 自适配滚动（不依赖 GuiGraphics translate）。
 */
public class GeoGenesisConfigScreen extends Screen {

    private static final int TERRAIN_TYPE_LAYER = 9;

    private final Screen parent;
    private long seed = (long) (Math.random() * Long.MAX_VALUE);

    private int tab = 0;
    /** 标签页名：上游→下游排列。页签0=世界参数（最上游），页签1=气候，页签2=地形（含雪线） */
    private static final String[] TAB_NAMES = {"世界参数", "气候", "地形"};

    /** 确认对话框 */
    private final ConfirmDialog confirmDialog = new ConfirmDialog();

    private final TerrainConfigPanel terrainPanel = new TerrainConfigPanel();
    private final ClimateConfigPanel climatePanel = new ClimateConfigPanel();
    private final ParameterConfigPanel paramPanel = new ParameterConfigPanel();

    private PreviewDisplay preview;
    private EditBox seedBox;
    private Button saveBtn, resetBtn, tabBtns[], layerPrev, layerNext, hydroBtn, slopeBtn, favBtn;
    /** 种子收藏列表（点击切换） */
    private List<Button> seedFavButtons = List.of();
    private int currentMode = TERRAIN_TYPE_LAYER;

    private int panelX, panelW, headerY, listTop, listBottom;
    private int previewX, previewY, previewW, previewH;
    private int scroll;
    private boolean dirty, saved;
    private int debounce;
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
        int w = width, h = height;
        panelX = 10;
        panelW = (w - 40) * 5 / 9;
        headerY = 64;
        listTop = headerY + 26;
        listBottom = h - 44;
        previewX = panelX + panelW + 20;
        previewY = 90;
        previewW = w - previewX - 10;
        previewH = listBottom - previewY;

        seedBox = new EditBox(Minecraft.getInstance().font, panelX, 36, panelW - 24, 18, Component.literal("Seed"));
        seedBox.setResponder(s -> { try { seed = Long.parseLong(s.trim()); } catch (NumberFormatException ignored) {} });
        seedBox.setValue(String.valueOf(seed));
        addRenderableWidget(seedBox);
        // 种子刷新按钮（↻），不依赖重置
        Button seedRefreshBtn = Button.builder(Component.literal("↻"), b -> {
            seed = (long) (Math.random() * Long.MAX_VALUE);
            seedBox.setValue(String.valueOf(seed));
            rebuildPreview();
        }).pos(panelX + panelW - 22, 36).size(20, 18).build();
        addRenderableWidget(seedRefreshBtn);

        // 收藏切换按钮
        SeedManager sm = SeedManager.getInstance();
        favBtn = Button.builder(Component.literal("☆"), b -> {
            if (sm.isFavorite(seed)) {
                sm.removeFavorite(seed);
            } else {
                sm.addFavorite(seed, "Seed " + seed);
            }
            rebuildSeedFavButtons(sm);
        }).pos(panelX + panelW - 44, 36).size(20, 18).build();
        addRenderableWidget(favBtn);
        rebuildSeedFavButtons(sm);

        tabBtns = new Button[3];
        int tabW = Math.max(60, (panelW - 8) / 3);
        for (int i = 0; i < 3; i++) {
            final int ti = i;
            tabBtns[i] = Button.builder(Component.literal(TAB_NAMES[i]), b -> {
                tab = ti; scroll = 0;
                // 切到地形页时刷新高度依赖（雪线Y范围、地形图e→Y映射）
                if (ti == 2) {
                    terrainPanel.refreshHeightDependent();
                }
                pushScrollToPanels();
            }).pos(panelX + i * (tabW + 4), headerY).size(tabW, 20).build();
            addRenderableWidget(tabBtns[i]);
        }

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
        Button settingsBtn = Button.builder(Component.literal("设置"), b ->
            Minecraft.getInstance().setScreen(new SettingsScreen(this, preview))
        ).pos(panelX + 128, listBottom + 6).size(60, 20).build();
        addRenderableWidget(saveBtn);
        addRenderableWidget(resetBtn);
        addRenderableWidget(settingsBtn);

        // 按钮在 widget 上方留出 4px 间隙
        layerPrev = Button.builder(Component.literal("◀ 图层"), b -> cycleLayer(-1))
            .pos(previewX, previewY - 24).size(70, 20).build();
        layerNext = Button.builder(Component.literal("图层 ▶"), b -> cycleLayer(1))
            .pos(previewX + previewW - 70, previewY - 24).size(70, 20).build();
        hydroBtn = Button.builder(Component.literal("水文"), b -> toggleHydro())
            .pos(previewX + previewW / 2 - 48, previewY - 24).size(40, 20).build();
        slopeBtn = Button.builder(Component.literal("阴影"), b -> toggleSlope())
            .pos(previewX + previewW / 2 - 6, previewY - 24).size(40, 20).build();
        addRenderableWidget(layerPrev);
        addRenderableWidget(layerNext);
        addRenderableWidget(hydroBtn);
        addRenderableWidget(slopeBtn);

        rebuildPreview();
        pushScrollToPanels();
    }

    /** 把当前 scroll 值同步到活动面板 */
    private void pushScrollToPanels() {
        terrainPanel.setScrollOffset(tab == 0 ? scroll : 0);
        climatePanel.setScrollOffset(tab == 1 ? scroll : 0);
        paramPanel.setScrollOffset(tab == 2 ? scroll : 0);
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
            addRenderableWidget(preview);
        } else {
            preview.setTerrain(terrain, seed, p);
            preview.setMode(currentMode);
        }
        // 记录已应用参数（避免下次相同 markDirty 触发重建）
        lastAppliedParams = p;
        lastAppliedSeed = seed;
        dirty = false;
    }

    /** 重建种子收藏按钮列表 */
    private void rebuildSeedFavButtons(SeedManager sm) {
        // 移除旧按钮
        for (Button b : seedFavButtons) removeWidget(b);
        List<SeedEntry> favs = sm.getFavorites();
        List<Button> btns = new java.util.ArrayList<>();
        favBtn.setMessage(Component.literal(sm.isFavorite(seed) ? "★" : "☆"));
        // 只显示最近 5 个
        int count = Math.min(favs.size(), 5);
        for (int i = 0; i < count; i++) {
            SeedEntry e = favs.get(favs.size() - 1 - i);
            boolean active = e.seed() == seed;
            Button b = Button.builder(
                Component.literal(active ? "▶ " + e.name() : e.name()),
                btn -> {
                    seed = e.seed();
                    seedBox.setValue(String.valueOf(seed));
                    rebuildPreview();
                    rebuildSeedFavButtons(sm);
                })
                .pos(panelX, 58 + i * 14)
                .size(panelW - 8, 12)
                .build();
            b.active = !active;
            addRenderableWidget(b);
            btns.add(b);
        }
        seedFavButtons = btns;
    }

    private void cycleLayer(int d) {
        currentMode = (currentMode + d + GeoPalette.PreviewLayer.values().length) % GeoPalette.PreviewLayer.values().length;
        if (preview != null) preview.setMode(currentMode);
    }
    private void toggleHydro() {
        if (preview == null) return;
        preview.setHydrology(!preview.isHydrology());
        hydroBtn.setMessage(Component.literal("水文" + (preview.isHydrology() ? "✓" : "")));
    }

    private void toggleSlope() {
        if (preview == null) return;
        preview.setSlopeShading(!preview.isSlopeShading());
        slopeBtn.setMessage(Component.literal("阴影" + (preview.isSlopeShading() ? "✓" : "")));
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
        g.drawString(this.font, "种子:", panelX, 22, 0xAAAAAA);

        int tabW = Math.max(60, (panelW - 8) / 3);
        for (int i = 0; i < 3; i++) {
            boolean on = tab == i;
            int bx = panelX + i * (tabW + 4);
            g.fill(bx, headerY, bx + tabW, headerY + 20, on ? 0x2a3340 : 0x1a1f28);
            g.fill(bx, headerY, bx + tabW, headerY + 1, on ? 0x00c896 : 0x444444);
        }

        // 图层名由 widget 内部图例显示，此处不重复绘制（避免与按钮重叠）

        // 左栏面板：裁剪区 + 直接渲染（无 translate）
        // tab 0=世界参数(paramPanel), 1=气候(climatePanel), 2=地形(terrainPanel)
        g.enableScissor(panelX, listTop, panelX + panelW, listBottom);
        if (tab == 0) paramPanel.render(g, mx, my);
        else if (tab == 1) climatePanel.render(g, mx, my);
        else {
            terrainPanel.render(g, mx, my);
            terrainPanel.renderHeaderTooltip(g, mx, my);
        }
        g.disableScissor();

        super.render(g, mx, my, pt);

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
        if (mx >= panelX && mx <= panelX + panelW && my >= listTop && my <= listBottom) {
            if (tab == 0) { if (paramPanel.mouseClicked(mx, my, b)) return true; }
            else if (tab == 1) { if (climatePanel.mouseClicked(mx, my, b)) return true; }
            else { if (terrainPanel.mouseClicked(mx, my, b)) return true; }
        }
        return super.mouseClicked(mx, my, b);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int b) {
        if (confirmDialog.isShowing()) return true;
        // ★ 修复：面板处理了就不传给 super，防止预览被误触
        if (tab == 0) { if (paramPanel.mouseReleased(mx, my, b)) return true; }
        else if (tab == 1) { if (climatePanel.mouseReleased(mx, my, b)) return true; }
        else { if (terrainPanel.mouseReleased(mx, my, b)) return true; }
        return super.mouseReleased(mx, my, b);
    }

    @Override
    public boolean mouseDragged(double mx, double my, int b, double dx, double dy) {
        if (confirmDialog.isShowing()) return true;
        if (tab == 0) { if (paramPanel.mouseDragged(mx, my, b, dx, dy)) return true; }
        else if (tab == 1) { if (climatePanel.mouseDragged(mx, my, b, dx, dy)) return true; }
        else { if (terrainPanel.mouseDragged(mx, my, b, dx, dy)) return true; }
        return super.mouseDragged(mx, my, b, dx, dy);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        if (confirmDialog.isShowing()) return true;
        if (mx >= panelX && mx <= panelX + panelW) {
            int contentH = tab == 0 ? paramPanel.getHeight()
                         : tab == 1 ? climatePanel.getHeight()
                         : terrainPanel.getHeight();
            int visibleH = listBottom - listTop;
            int maxScroll = Math.max(0, contentH - visibleH);
            scroll = Math.max(0, Math.min(maxScroll, scroll - (int) (delta * 20)));
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
