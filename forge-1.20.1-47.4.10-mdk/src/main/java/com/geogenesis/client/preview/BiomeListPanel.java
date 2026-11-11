package com.geogenesis.client.preview;

import com.geogenesis.worldgen.climate.BiomeClassifier;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;

/**
 * 群系列表面板（手动渲染，不进 widget 列表）。
 *
 * <p>★ v72：<b>按内容全高展开</b>，不再自己滚动 —— 滚轮只归外层页面
 * （用户：「不要上下滚轮页里面再加上下滚轮的控件，这样非常容易误操作」）。
 * 旧实现的固定视口 + 内层滚轮 + 滚动条已删除。</p>
 */
public class BiomeListPanel {

    private final List<BiomeEntryWidget> entries;
    private int filterMode = 0;
    private int x, y, w, h;
    private java.util.function.Consumer<BiomeClassifier.BiomeClass> onSelect;
    private static final int ENTRY_H = 14;
    private static final int SPACING = 2;

    public BiomeListPanel(List<BiomeEntryWidget> entries) { this.entries = entries; }

    public void setOnSelect(java.util.function.Consumer<BiomeClassifier.BiomeClass> cb) { this.onSelect = cb; }

    public void setRect(int x, int y, int w, int h) { this.x = x; this.y = y; this.w = w; this.h = h; }

    public void setFilter(int mode) {
        this.filterMode = mode;
    }

    private int contentHeight() {
        int n = 0;
        for (BiomeEntryWidget e : entries) if (e.matchesFilter(filterMode)) n++;
        return Math.max(0, n * (ENTRY_H + SPACING) - SPACING);
    }

    public void render(GuiGraphics g, int mx, int my) {
        // ★ v7：列表底改为中性深灰（旧值 0xFF15191F 是早期深蓝主题）
        UiKit.roundRect(g, x, y, x + w, y + h, UiKit.RADIUS_CARD, 0xFF1A1A1A);

        // ★ v72：列表按内容全高展开，滚轮<b>只</b>归外层页。
        //   旧实现是固定视口 + 内层滚轮：页面自己也滚滚轮、
        //   列表又滚滚轮 ⇒ 指针在列表上下滚时不知道该动哪个
        //   （用户：「不要上下滚轮页里面再加上下滚轮的控件，非常容易误操作」）。
        g.enableScissor(x, y, x + w, y + h);
        int cy = y;
        for (BiomeEntryWidget e : entries) {
            if (!e.matchesFilter(filterMode)) { e.visible = false; continue; }
            e.visible = true;
            e.setRect(x + 2, cy, w - 4);
            e.render(g, mx, my);
            cy += ENTRY_H + SPACING;
        }
        g.disableScissor();
    }

    /**
     * ★ v72：内层列表<b>不再</b>消费滚轮 —— 交给页面。
     *
     * <p>一个页面只能有一个滚轮目标。列表已按内容全高展开
     * （见 {@link #render}），因此这里返回 {@code false}
     * 让外层页面接管。</p>
     */
    public boolean mouseScrolled(double mx, double my, double delta) {
        return false;
    }

    /** 列表完整显示所需高度（已按当前筛选计算）。 */
    public int naturalHeight() {
        return contentHeight();
    }

    public boolean mouseClicked(double mx, double my, int btn) {
        if (mx < x || mx > x + w || my < y || my > y + h) return false;
        for (BiomeEntryWidget e : entries) {
            if (e.visible && e.mouseClicked(mx, my, btn)) {
                if (onSelect != null) onSelect.accept(e.biome);
                return true;
            }
        }
        return false;
    }
}
