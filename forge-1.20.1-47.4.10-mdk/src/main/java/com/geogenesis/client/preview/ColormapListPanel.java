package com.geogenesis.client.preview;

import net.minecraft.client.gui.GuiGraphics;

import java.util.List;

/**
 * 色带列表（手动渲染，不进 widget 列表）。
 *
 * <p>★ v72：<b>按内容全高展开</b>，不再自己滚。
 * 旧实现是固定视口 + 内层滚轮，嵌在同样滚滚轮的页面里
 * ⇒ 一个页面两个滚轮目标，非常容易误操作
 * （用户原话如上）。现在滚轮只归外层页面。</p>
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
        // ★ v7：列表底改为中性深灰（旧值 0xFF15191F 是早期深蓝主题）
        UiKit.roundRect(g, x, y, x + w, y + h, UiKit.RADIUS_CARD, 0xFF1A1A1A);
        g.enableScissor(x, y, x + w, y + h);
        int cy = y;
        for (ColormapOption o : options) {
            o.setRect(x, cy, w);
            o.render(g, mx, my);
            cy += OPTION_H + SPACING;
        }
        g.disableScissor();
    }

    /** 列表完整显示所需高度。 */
    public int naturalHeight() {
        return contentHeight();
    }

    /** ★ v72：不消费滚轮 —— 一个页面只能有一个滚轮目标，交给页面。 */
    public boolean mouseScrolled(double mx, double my, double delta) {
        return false;
    }

    public boolean mouseClicked(double mx, double my, int btn) {
        if (mx < x || mx > x + w || my < y || my > y + h) return false;
        for (ColormapOption o : options) {
            if (o.mouseClicked(mx, my, btn)) return true;
        }
        return false;
    }
}
