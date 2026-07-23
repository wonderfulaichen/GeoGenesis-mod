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
        if (selected) {
            g.fill(x, y, x + w, y + H, 0xFF10302A);          // 选中底色（深青绿）
            g.fill(x, y, x + 2, y + H, 0xFF00c896);           // 左侧高亮条
        } else {
            g.fill(x, y, x + w, y + H, hover ? 0xFF252A33 : 0xFF1A1E24);
        }
        g.fill(x + 2, y + 2, x + 12, y + 12, 0xFF000000 | color);
        g.fill(x + 2, y + 2, x + 12, y + 3, 0xFF888888);
        g.drawString(Minecraft.getInstance().font, label, x + 18, y + 3, selected ? 0xFF00c896 : 0xFFCCCCCC);
    }

    public boolean mouseClicked(double mx, double my, int btn) {
        return mx >= x && mx <= x + w && my >= y && my <= y + H;
    }

    private static boolean isMarine(String n) {
        return n.contains("OCEAN") || n.contains("BEACH") || n.contains("LAKE") || n.contains("RIVER");
    }
    private static boolean isCold(String n) {
        return n.contains("SNOW") || n.contains("TUNDRA") || n.contains("TAIGA");
    }
    private static boolean isTemperate(String n) {
        return n.contains("PLAIN") || n.contains("FOREST") || n.contains("SWAMP") || n.contains("HILLS") || n.contains("PLATEAU");
    }
    private static boolean isHot(String n) {
        return n.contains("DESERT") || n.contains("SAVANNA") || n.contains("JUNGLE");
    }
    private static boolean isDry(String n) {
        return n.contains("DESERT") || n.contains("SAVANNA");
    }
}
