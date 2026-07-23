package com.geogenesis.client.preview;

import com.geogenesis.client.preview.GeoPalette.ColormapEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 色带设置面板（「色带」页签）：可滚动色带列表（带缩略图）+ 重置高度按钮。
 * 选中色带直接调用 {@link GeoPalette#setActiveColormap}；重置高度调用 {@link PreviewDisplay#resetHeightBounds}。
 */
public class ColormapPanel extends ConfigPanel {

    private final PreviewDisplay preview;
    private final List<ColormapOption> options = new ArrayList<>();
    private final ColormapListPanel listPanel;
    private int listH;
    private static final int LIST_OFF = 24;
    private static final int BTN_OFF = 10;
    private static final int BTN_H = 22;

    public ColormapPanel(PreviewDisplay preview) {
        this.preview = preview;
        List<ColormapEntry> entries = GeoPalette.getColormapEntries();
        ColormapEntry active = GeoPalette.getActiveColormap();
        for (ColormapEntry e : entries) {
            boolean isActive = e.name().equals(active.name());
            options.add(new ColormapOption(e, isActive, () -> {
                GeoPalette.setActiveColormap(e);
                for (ColormapOption o : options) o.setActive(o.entry == e);
                preview.forceRefresh();
                playClick();
            }));
        }
        listPanel = new ColormapListPanel(options);
    }

    @Override
    public int getHeight() { return 340; }

    @Override
    public void render(GuiGraphics g, int mx, int my) {
        drawHeader(g, x, top(), I18n.get("geogenesis.settings.heightmap.colormap"));
        int ly = top() + LIST_OFF;
        listH = Math.max(120, Math.min(260, Minecraft.getInstance().getWindow().getGuiScaledHeight() - 220));
        listPanel.setRect(x, ly, w, listH);
        listPanel.render(g, mx, my);

        int by = ly + listH + BTN_OFF;
        boolean h = drawButton(g, x, by, 150, BTN_H, I18n.get("geogenesis.settings.heightmap.reset"), false, mx, my);
        if (h) hoverTooltip = Component.literal(I18n.get("geogenesis.settings.heightmap.restart_hint"));
    }

    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        if (listPanel.mouseClicked(mx, my, btn)) return true;
        int by = top() + LIST_OFF + listH + BTN_OFF;
        if (hit(x, by, 150, BTN_H, mx, my)) {
            preview.resetHeightBounds();
            playClick();
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        return listPanel.mouseScrolled(mx, my, delta);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int btn) { return false; }

    @Override
    public boolean mouseDragged(double mx, double my, int btn, double dx, double dy) { return false; }
}
