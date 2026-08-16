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
            "气候地形底图: " + GeoPalette.terrainUnderlayLabel(), false, mx, my);
        if (hb) hoverTooltip = Component.literal(
            "气候/纬度等图层的数据披在地形之上的表现方式：关闭=纯数据；染色底图=混入高程彩色；地形阴影=仅按海拔调亮度");

        boolean h3 = drawToggleRow(g, x, toggleY(3), w, ROW_H,
            "细胞边界叠加", preview.showCellBorders, mx, my);
        if (h3) hoverTooltip = Component.literal(
            "诊断子图层：相邻格主导地形类型不同处画深色线，用于检查 Voronoi 类型混合是否生硬/断裂");

        boolean h3b = drawToggleRow(g, x, toggleY(4), w, ROW_H,
            "侵蚀tile边界叠加", preview.showTileBorders, mx, my);
        if (h3b) hoverTooltip = Component.literal(
            "诊断子图层：侵蚀 tile 网格线（48wu，块坐标=48×水平缩放），用于检查 tile 边缘错位/微断裂位置");

        boolean h4 = drawToggleRow(g, x, toggleY(5), w, ROW_H,
            "拖动时简化视图", preview.dragSimplify, mx, my);
        if (h4) hoverTooltip = Component.literal(
            "拖动预览时跳过坡度阴影保证流畅（松手后补画）。关闭 = 拖动也画完整阴影，帧率可能下降");

        boolean h5 = drawToggleRow(g, x, toggleY(6), w, ROW_H,
            "类型过滤模式", preview.filterMode, mx, my);
        if (h5) hoverTooltip = Component.literal(
            "离散图层多选过滤：开启后点击图例/地图勾选类型（可多选），未勾选类型压暗显示。关闭 = 单选高亮");

        boolean h6 = drawButton(g, x, toggleY(7), w, ROW_H, "定位出生点", false, mx, my);
        if (h6) hoverTooltip = Component.literal("视口中心跳转到出生点（无出生点时回到 0,0）");
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
}
