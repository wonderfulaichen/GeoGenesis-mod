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
        // ★ v7：圆角行 + 左主色条（旧版是上下两条亮绿描边 + 深绿底）
        int bg = active ? 0x2E48DE56 : (hover ? 0x14FFFFFF : 0x00000000);
        if (bg != 0) UiKit.roundRect(g, x, y, x + w, y + H, 3, bg);
        if (active) g.fill(x + 1, y + 4, x + 3, y + H - 4, UiKit.C_ACCENT);
        // 色带预览
        for (int i = 0; i < 48; i++) {
            float t = i / 47f;
            int col = entry.colormap().getRGB(t);
            g.fill(x + 8 + i, y + 5, x + 9 + i, y + 15, 0xFF000000 | col);
        }
        g.drawString(Minecraft.getInstance().font, entry.name(), x + 64,
                y + (H - Minecraft.getInstance().font.lineHeight) / 2,
                active ? UiKit.C_ACCENT : UiKit.C_TXT_SUB, false);
    }

    public boolean mouseClicked(double mx, double my, int btn) {
        if (mx >= x && mx <= x + w && my >= y && my <= y + H) {
            onClick.run();
            return true;
        }
        return false;
    }
}
