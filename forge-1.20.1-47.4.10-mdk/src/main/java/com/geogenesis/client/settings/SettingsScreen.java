package com.geogenesis.client.settings;

import com.geogenesis.client.preview.PreviewDisplay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.tabs.Tab;
import net.minecraft.client.gui.components.tabs.TabManager;
import net.minecraft.client.gui.components.tabs.TabNavigationBar;
import net.minecraft.client.gui.layouts.FrameLayout;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.CommonComponents;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 预览设置屏幕，从 {@link com.geogenesis.client.GeoGenesisConfigScreen} 的齿轮按钮打开。
 * 使用 MC 内建的 TabNavigationBar + TabManager 管理 5 个设置分页。
 */
public class SettingsScreen extends Screen {

    private static final Component TITLE = Component.translatable("geogenesis.settings.title");

    private final Screen lastScreen;
    private final PreviewDisplay previewDisplay;
    private final TabManager tabManager;
    private TabNavigationBar tabNavigationBar;
    private GridLayout bottomButtons;

    public SettingsScreen(Screen lastScreen, PreviewDisplay previewDisplay) {
        super(TITLE);
        this.lastScreen = lastScreen;
        this.previewDisplay = previewDisplay;
        this.tabManager = new TabManager(this::addRenderableWidget, this::removeWidget);
    }

    @Override
    protected void init() {
        Minecraft mc = Objects.requireNonNull(this.minecraft);
        List<Tab> tabs = new ArrayList<>();
        tabs.add(new GeneralTab(mc, previewDisplay));
        tabs.add(new SamplingTab(mc, previewDisplay));
        tabs.add(new HeightmapTab(mc, previewDisplay));
        tabs.add(new CacheTab(mc, previewDisplay));
        tabs.add(new BiomesTab(mc, previewDisplay));

        this.tabNavigationBar = TabNavigationBar.builder(this.tabManager, this.width)
            .addTabs(tabs.toArray(new Tab[0]))
            .build();
        this.tabNavigationBar.selectTab(0, false);
        this.addRenderableWidget(this.tabNavigationBar);

        this.bottomButtons = new GridLayout().columnSpacing(10);
        GridLayout.RowHelper rowHelper = this.bottomButtons.createRowHelper(1);
        rowHelper.addChild(Button.builder(CommonComponents.GUI_BACK, btn -> this.onClose()).build());
        this.bottomButtons.visitWidgets(w -> {
            w.setTabOrderGroup(1);
            this.addRenderableWidget(w);
        });
        this.repositionElements();
    }

    @Override
    public void repositionElements() {
        if (this.tabNavigationBar != null) {
            this.tabNavigationBar.setWidth(this.width);
            this.tabNavigationBar.arrangeElements();
            this.bottomButtons.arrangeElements();
            FrameLayout.centerInRectangle(this.bottomButtons, 0, this.height - 36, this.width, 36);
            int i = this.tabNavigationBar.getRectangle().bottom();
            ScreenRectangle rect = new ScreenRectangle(0, i, this.width, this.bottomButtons.getY() - i);
            this.tabManager.setTabArea(rect);
        }
    }

    @Override
    public void renderBackground(GuiGraphics g) {
        // 强制不透明背景覆盖，避免旧屏幕 widget 透过来
        g.fill(0, 0, this.width, this.height, 0xFF101418);
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, float pt) {
        // 本构建 Screen.render() 不会自动调用 renderBackground()，
        // 故在 super.render 前手动填充不透明背景，盖住旧屏幕。
        this.renderBackground(g);
        super.render(g, mx, my, pt);
        // 底部分隔线
        g.blit(
            net.minecraft.resources.ResourceLocation.parse("textures/gui/footer_separator.png"),
            0, Mth.roundToward(this.height - 36 - 2, 2),
            0.0F, 0.0F, this.width, 2, 32, 2
        );
    }

    @Override
    public void onClose() {
        // 关闭设置屏，回到原来的屏（GeoGenesisConfigScreen）
        Minecraft.getInstance().setScreen(this.lastScreen);
    }
}
