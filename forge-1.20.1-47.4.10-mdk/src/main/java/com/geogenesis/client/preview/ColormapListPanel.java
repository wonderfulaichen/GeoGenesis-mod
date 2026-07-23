package com.geogenesis.client.preview;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;

import java.util.List;

/**
 * 可滚动色带列表（手动渲染，不进 widget 列表）。
 * 固定高度、溢出滚动、scissor 裁剪，右侧青绿滚动条指示器。
 */
public class ColormapListPanel {

    private final List<ColormapOption> options;
    private int x, y, w, h;
    private int scrollY = 0;
    private static final int OPTION_H = 20;
    private static final int SPACING = 2;

    public ColormapListPanel(List<ColormapOption> options) { this.options = options; }

    public void setRect(int x, int y, int w, int h) { this.x = x; this.y = y; this.w = w; this.h = h; }

    private int contentHeight() {
        return Math.max(0, options.size() * (OPTION_H + SPACING) - SPACING);
    }

    public void render(GuiGraphics g, int mx, int my) {
        g.fill(x, y, x + w, y + h, 0xFF15191F);
        int maxScroll = Math.max(0, contentHeight() - h);
        scrollY = Mth.clamp(scrollY, 0, maxScroll);

        g.enableScissor(x, y, x + w, y + h);
        int cy = y - scrollY;
        for (ColormapOption o : options) {
            o.setRect(x, cy, w);
            o.render(g, mx, my);
            cy += OPTION_H + SPACING;
        }
        g.disableScissor();

        if (maxScroll > 0) {
            int barH = Math.max(12, h * h / contentHeight());
            int barY = y + scrollY * (h - barH) / maxScroll;
            g.fill(x + w - 3, barY, x + w - 1, barY + barH, 0xFF00c896);
        }
    }

    public boolean mouseScrolled(double mx, double my, double delta) {
        if (mx < x || mx > x + w || my < y || my > y + h) return false;
        int maxScroll = Math.max(0, contentHeight() - h);
        // 滚轮上滚(delta>0)→内容下移(scrollY 减小，显示更靠前的项)，与全站列表/惯用方向一致
        scrollY = Mth.clamp(scrollY - (int) (delta * OPTION_H), 0, maxScroll);
        return true;
    }

    public boolean mouseClicked(double mx, double my, int btn) {
        if (mx < x || mx > x + w || my < y || my > y + h) return false;
        for (ColormapOption o : options) {
            if (o.mouseClicked(mx, my, btn)) return true;
        }
        return false;
    }
}
