package com.geogenesis.client.preview;

import com.geogenesis.worldgen.climate.BiomeClassifier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/**
 * 群系列表项：10×10 颜色方块 + 本地化名称。只读，无选中动作。
 * 由 {@link BiomeListPanel} 手动渲染（不进 widget 列表）。
 */
public class BiomeEntryWidget {

    final BiomeClassifier.BiomeClass biome;
    private final int color;
    private final String label;
    boolean visible = false;
    private int x, y, w;
    private boolean selected = false;
    private static final int H = 14;

    public BiomeEntryWidget(BiomeClassifier.BiomeClass biome, int color, String label) {
        this.biome = biome;
        this.color = color;
        this.label = label;
    }

    public void setRect(int x, int y, int w) { this.x = x; this.y = y; this.w = w; }
    public void setSelected(boolean sel) { this.selected = sel; }

    public boolean matchesFilter(int filter) {
        if (filter == 0) return true;
        String name = biome.name();
        switch (filter) {
            case 1: return !isMarine(name);
            case 2: return isMarine(name);
            case 3: return isCold(name);
            case 4: return isTemperate(name);
            case 5: return isHot(name);
            case 6: return isDry(name);
            default: return true;
        }
    }

    public void render(GuiGraphics g, int mx, int my) {
        boolean hover = mx >= x && mx <= x + w && my >= y && my <= y + H;
        // ★ v7：圆角行 + 中性配色（旧版是深青绿底 + 亮绿 2px 条，与新主题冲突）
        if (selected) {
            UiKit.roundRect(g, x, y, x + w, y + H, UiKit.RADIUS_FIELD, 0x2E48DE56);
            g.fill(x + 1, y + 4, x + 3, y + H - 4, UiKit.C_ACCENT);
        } else if (hover) {
            UiKit.roundRect(g, x, y, x + w, y + H, UiKit.RADIUS_FIELD, 0x14FFFFFF);
        }
        // 色块（2px 圆角，去掉旧的顶部亮线）
        UiKit.roundRect(g, x + 6, y + 2, x + 16, y + 12, 2, 0xFF000000 | color);
        g.drawString(Minecraft.getInstance().font, label, x + 22,
                y + (H - Minecraft.getInstance().font.lineHeight) / 2,
                selected ? UiKit.C_ACCENT : UiKit.C_TXT_SUB, false);
    }

    public boolean mouseClicked(double mx, double my, int btn) {
        return mx >= x && mx <= x + w && my >= y && my <= y + H;
    }

    private static boolean isMarine(String n) {
        return n.contains("OCEAN") || n.contains("BEACH") || n.contains("LAKE") || n.contains("RIVER");
    }
    // 【2026-09-10】群系列表改为真实 MC 群系名，补充新增条目的归类
    private static boolean isCold(String n) {
        return n.contains("SNOW") || n.contains("TUNDRA") || n.contains("TAIGA")
            || n.contains("FROZEN") || n.contains("ICE") || n.contains("GROVE");
    }
    private static boolean isTemperate(String n) {
        return n.contains("PLAIN") || n.contains("FOREST") || n.contains("SWAMP")
            || n.contains("HILLS") || n.contains("PLATEAU") || n.contains("MEADOW");
    }
    private static boolean isHot(String n) {
        return n.contains("DESERT") || n.contains("SAVANNA") || n.contains("JUNGLE")
            || n.contains("BADLANDS");
    }
    private static boolean isDry(String n) {
        return n.contains("DESERT") || n.contains("SAVANNA") || n.contains("BADLANDS");
    }
}
