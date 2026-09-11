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
    public int getHeight() { return 340; }

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
            I18n.get("geogenesis.settings.display.underlay") + ": " + GeoPalette.terrainUnderlayLabel(this::localize), false, mx, my);
        if (hb) hoverTooltip = Component.literal(I18n.get("geogenesis.settings.display.underlay.tooltip"));

        boolean h3 = drawToggleRow(g, x, toggleY(3), w, ROW_H,
            I18n.get("geogenesis.settings.display.cell_borders"), preview.showCellBorders, mx, my);
        if (h3) hoverTooltip = Component.literal(I18n.get("geogenesis.settings.display.cell_borders.tooltip"));

        boolean h3b = drawToggleRow(g, x, toggleY(4), w, ROW_H,
            I18n.get("geogenesis.settings.display.tile_borders"), preview.showTileBorders, mx, my);
        if (h3b) hoverTooltip = Component.literal(I18n.get("geogenesis.settings.display.tile_borders.tooltip"));

        boolean h4 = drawToggleRow(g, x, toggleY(5), w, ROW_H,
            I18n.get("geogenesis.settings.display.drag_simplify"), preview.dragSimplify, mx, my);
        if (h4) hoverTooltip = Component.literal(I18n.get("geogenesis.settings.display.drag_simplify.tooltip"));

        boolean h5 = drawToggleRow(g, x, toggleY(6), w, ROW_H,
            I18n.get("geogenesis.settings.display.filter_mode"), preview.filterMode, mx, my);
        if (h5) hoverTooltip = Component.literal(I18n.get("geogenesis.settings.display.filter_mode.tooltip"));

        boolean h6 = drawButton(g, x, toggleY(7), w, ROW_H,
            I18n.get("geogenesis.settings.display.locate_spawn"), false, mx, my);
        if (h6) hoverTooltip = Component.literal(I18n.get("geogenesis.settings.display.locate_spawn.tooltip"));
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
            GeoPalette.cycleTerrainUnderlay();
            // ★ 必须强制重画：脏检查不感知 TerrainUnderlay 变化（不是视口/数据/图层变化），
            //   否则切换后画面不变（"切换阴影失效"实锤根因）。
            preview.needsClear = true;
            playClick(); return true;
        }
        if (hit(x, toggleY(3), w, ROW_H, mx, my)) {
            preview.showCellBorders = !preview.showCellBorders;
            preview.needsClear = true;  // 叠加开关 → 强制重画
            playClick(); return true;
        }
        if (hit(x, toggleY(4), w, ROW_H, mx, my)) {
            preview.showTileBorders = !preview.showTileBorders;
            preview.needsClear = true;
            playClick(); return true;
        }
        if (hit(x, toggleY(5), w, ROW_H, mx, my)) {
            preview.dragSimplify = !preview.dragSimplify;
            preview.needsClear = true;
            playClick(); return true;
        }
        if (hit(x, toggleY(6), w, ROW_H, mx, my)) {
            preview.filterMode = !preview.filterMode;
            preview.filterIds.clear();  // 切换模式时清空勾选，避免残留
            preview.needsClear = true;
            playClick(); return true;
        }
        if (hit(x, toggleY(7), w, ROW_H, mx, my)) {
            preview.centerOnSpawn();
            playClick(); return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int btn) { return false; }

    @Override
    public boolean mouseDragged(double mx, double my, int btn, double dx, double dy) { return false; }

    /** 本地化：优先 I18n，缺失则回退英文表。 */
    private String localize(String key) {
        String s = I18n.get(key);
        return s.equals(key) ? GeoPalette.englishLabel(key) : s;
    }
}
