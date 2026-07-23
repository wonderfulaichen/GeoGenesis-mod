package com.geogenesis.client.preview;

import com.geogenesis.client.preview.GeoPalette.ColormapEntry;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/**
 * 色带列表项：左侧 48×12 色带缩略图 + 名称，选中态整条青绿高亮 + 上下边条。
 * 由 {@link ColormapListPanel} 手动渲染（不进 widget 列表）。
 */
public class ColormapOption {

    final ColormapEntry entry;
    private boolean active;
    private final Runnable onClick;
    private int x, y, w;
    private static final int H = 20;

    public ColormapOption(ColormapEntry entry, boolean active, Runnable onClick) {
        this.entry = entry;
        this.active = active;
        this.onClick = onClick;
    }

    public void setActive(boolean a) { this.active = a; }

    public void setRect(int x, int y, int w) { this.x = x; this.y = y; this.w = w; }

    public void render(GuiGraphics g, int mx, int my) {
        boolean hover = mx >= x && mx <= x + w && my >= y && my <= y + H;
        int bg = hover ? 0xFF252A33 : (active ? 0xFF1A3A2A : 0xFF1A1E24);
        g.fill(x, y, x + w, y + H, bg);
        if (active) {
            g.fill(x, y, x + w, y + 1, 0xFF00C896);
            g.fill(x, y + H - 1, x + w, y + H, 0xFF00C896);
        }
        for (int i = 0; i < 48; i++) {
            float t = i / 47f;
            int col = entry.colormap().getRGB(t);
            g.fill(x + 4 + i, y + 4, x + 5 + i, y + 16, 0xFF000000 | col);
        }
        g.drawString(Minecraft.getInstance().font, entry.name(), x + 60, y + 6,
            active ? 0xFF00C896 : 0xFFCCCCCC);
    }

    public boolean mouseClicked(double mx, double my, int btn) {
        if (mx >= x && mx <= x + w && my >= y && my <= y + H) {
            onClick.run();
            return true;
        }
        return false;
    }
}
