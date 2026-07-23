package com.geogenesis.client.preview;

import com.geogenesis.client.preview.GeoPalette;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;

/**
 * 显示设置面板（「显示」页签）：帧时间开关、玩家标记开关、地形底图模式循环。
 * 全部为运行时显示状态，直接操作 {@link PreviewDisplay} 字段与 {@link GeoPalette} 静态。
 */
public class DisplayPanel extends ConfigPanel {

    private final PreviewDisplay preview;
    private static final int ROW_H = 24;
    private static final int GAP = 8;

    public DisplayPanel(PreviewDisplay preview) { this.preview = preview; }

    private int toggleY(int idx) { return top() + 30 + idx * (ROW_H + GAP); }

    @Override
    public int getHeight() { return 220; }

    @Override
    public void render(GuiGraphics g, int mx, int my) {
        drawHeader(g, x, top(), I18n.get("geogenesis.settings.general.title"));

        boolean h0 = drawToggleRow(g, x, toggleY(0), w, ROW_H,
            I18n.get("geogenesis.settings.general.show_frametime"), preview.showFrameTime, mx, my);
        if (h0) hoverTooltip = Component.literal(I18n.get("geogenesis.settings.general.show_frametime.tooltip"));

        boolean h1 = drawToggleRow(g, x, toggleY(1), w, ROW_H,
            I18n.get("geogenesis.settings.general.show_player"), preview.showPlayerMarkers, mx, my);
        if (h1) hoverTooltip = Component.literal(I18n.get("geogenesis.settings.general.show_player.tooltip"));

        boolean hb = drawButton(g, x, toggleY(2), w, ROW_H,
            "气候地形底图: " + GeoPalette.terrainUnderlayLabel(), false, mx, my);
        if (hb) hoverTooltip = Component.literal(
            "气候/纬度等图层的数据披在地形之上的表现方式：关闭=纯数据；染色底图=混入高程彩色；地形阴影=仅按海拔调亮度");
    }

    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        if (hit(x, toggleY(0), w, ROW_H, mx, my)) {
            preview.showFrameTime = !preview.showFrameTime; playClick(); return true;
        }
        if (hit(x, toggleY(1), w, ROW_H, mx, my)) {
            preview.showPlayerMarkers = !preview.showPlayerMarkers; playClick(); return true;
        }
        if (hit(x, toggleY(2), w, ROW_H, mx, my)) {
            GeoPalette.cycleTerrainUnderlay(); playClick(); return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int btn) { return false; }

    @Override
    public boolean mouseDragged(double mx, double my, int btn, double dx, double dy) { return false; }
}
