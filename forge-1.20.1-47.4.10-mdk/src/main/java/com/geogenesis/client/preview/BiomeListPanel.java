package com.geogenesis.client.preview;

import com.geogenesis.worldgen.climate.BiomeClassifier;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.util.Mth;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.List;

/**
 * 可滚动群系列表面板（手动渲染，不进 widget 列表）。
 * 按筛选模式过滤后重排、溢出滚动、裁剪渲染，右侧青绿滚动条指示器。
 */
public class BiomeListPanel {

    private static final Logger LOG = LogManager.getLogger("geogenesis");

    // 调试用：mouseScrolled 调用计数（仅前 10 次 + 每 30 次统计一次），用于排查"滚不动"
    private static int scrollCallCount = 0;

    private final List<BiomeEntryWidget> entries;
    private int filterMode = 0;
    private int x, y, w, h;
    private int scrollY = 0;
    private java.util.function.Consumer<BiomeClassifier.BiomeClass> onSelect;
    private static final int ENTRY_H = 14;
    private static final int SPACING = 2;

    public BiomeListPanel(List<BiomeEntryWidget> entries) { this.entries = entries; }

    public void setOnSelect(java.util.function.Consumer<BiomeClassifier.BiomeClass> cb) { this.onSelect = cb; }

    public void setRect(int x, int y, int w, int h) { this.x = x; this.y = y; this.w = w; this.h = h; }

    public void setFilter(int mode) {
        // 仅在筛选模式真正变化时才重置滚动偏移；否则每帧调用会把 scrollY 清零，
        // 导致 mouseScrolled 设置的滚动量立即被下一帧 render 撤销，列表视觉上不动。
        if (this.filterMode != mode) {
            this.filterMode = mode;
            this.scrollY = 0;
        }
    }

    private int contentHeight() {
        int n = 0;
        for (BiomeEntryWidget e : entries) if (e.matchesFilter(filterMode)) n++;
        return Math.max(0, n * (ENTRY_H + SPACING) - SPACING);
    }

    public void render(GuiGraphics g, int mx, int my) {
        g.fill(x, y, x + w, y + h, 0xFF15191F);
        int maxScroll = Math.max(0, contentHeight() - h);
        scrollY = Mth.clamp(scrollY, 0, maxScroll);

        g.enableScissor(x, y, x + w, y + h);
        int cy = y - scrollY;
        for (BiomeEntryWidget e : entries) {
            if (!e.matchesFilter(filterMode)) { e.visible = false; continue; }
            e.visible = true;
            e.setRect(x + 2, cy, w - 4);
            e.render(g, mx, my);
            cy += ENTRY_H + SPACING;
        }
        g.disableScissor();

        if (maxScroll > 0) {
            int barH = Math.max(12, h * h / contentHeight());
            int barY = y + scrollY * (h - barH) / maxScroll;
            g.fill(x + w - 3, barY, x + w - 1, barY + barH, 0xFF00c896);
        }
    }

    public boolean mouseScrolled(double mx, double my, double delta) {
        // 群系页主体即列表：滚轮由内层列表独占。
        // 有溢出就滚列表；无溢出（筛选后条目少）也消费事件、不落外层 UI 滚动，
        // 避免用户看到「外层面板动、列表不动」的错觉。
        int maxScroll = Math.max(0, contentHeight() - h);
        if (maxScroll > 0) {
            int before = scrollY;
            // 用户偏好方向（自然滚动）：下滚轮(delta>0) → 内容向下移动 → 看到上方（scrollY 减小）
            scrollY = Mth.clamp(scrollY - (int) (delta * 20), 0, maxScroll); // 步长 20px
            if (scrollCallCount < 8 || scrollCallCount % 20 == 0)
                LOG.info("DIAG-BLP: scrolled before={} after={} maxScroll={} h={} contentH={}",
                    before, scrollY, maxScroll, h, contentHeight());
        } else if (scrollCallCount < 8) {
            LOG.info("DIAG-BLP: no overflow h={} contentH={} -> consume (no outer scroll)", h, contentHeight());
        }
        scrollCallCount++;
        return true; // 始终消费：内层列表独占滚轮
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
