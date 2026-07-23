package com.geogenesis.client.settings;

import com.geogenesis.client.preview.GeoPalette;
import com.geogenesis.client.preview.PreviewDisplay;
import com.geogenesis.worldgen.climate.BiomeClassifier;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.tabs.GridLayoutTab;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;

/**
 * 群系设置分页：GeoGenesis 19 类简化群系列表、7 种筛选过滤器、颜色预览。
 * 按模组性质本地化，使用 BiomeClassifier.BiomeClass 而非 TFC 群系。
 * 列表区为可滚动面板，筛选后自动重排（不留空洞）。
 */
public class BiomesTab extends GridLayoutTab {

    private final List<BiomeEntryWidget> entries = new ArrayList<>();
    private BiomeListPanel listPanel;

    public BiomesTab(Minecraft mc, PreviewDisplay preview) {
        super(Component.translatable("geogenesis.settings.biomes.title"));

        GridLayout.RowHelper row = this.layout.rowSpacing(6).createRowHelper(1);

        // ---------- 筛选按钮行 ----------
        String[] filterLabels = {
            "geogenesis.settings.biomes.filter_all",
            "geogenesis.settings.biomes.filter_land",
            "geogenesis.settings.biomes.filter_ocean",
            "geogenesis.settings.biomes.filter_cold",
            "geogenesis.settings.biomes.filter_temp",
            "geogenesis.settings.biomes.filter_hot",
            "geogenesis.settings.biomes.filter_dry"
        };
        GridLayout filterGrid = new GridLayout();
        GridLayout.RowHelper fRow = filterGrid.rowSpacing(2).columnSpacing(4).createRowHelper(7);
        for (int i = 0; i < filterLabels.length; i++) {
            final int fi = i;
            fRow.addChild(Button.builder(
                Component.translatable(filterLabels[i]),
                btn -> { if (listPanel != null) listPanel.setFilter(fi); }
            ).width(44).build());
        }
        filterGrid.arrangeElements();
        row.addChild(filterGrid);

        // ---------- 群系列表（可滚动面板） ----------
        BiomeClassifier.BiomeClass[] allBiomes = BiomeClassifier.BiomeClass.values();
        for (BiomeClassifier.BiomeClass biome : allBiomes) {
            int color = GeoPalette.colorForBiome(biome.ordinal());
            String labelKey = "geogenesis.biome." + biome.name();
            String localName = I18n.get(labelKey);
            if (localName.equals(labelKey)) localName = biome.name();
            entries.add(new BiomeEntryWidget(mc.font, biome, color, localName));
        }
        int panelH = Math.max(120, mc.getWindow().getGuiScaledHeight() - 110);
        listPanel = new BiomeListPanel(0, 0, 340, panelH, entries);
        row.addChild(listPanel);
    }

    /** 可滚动群系列表面板：筛选后重排、溢出滚动、裁剪渲染。 */
    private static class BiomeListPanel extends AbstractWidget {
        private final List<BiomeEntryWidget> entries;
        private int filterMode = 0;
        private int scrollY = 0;

        private static final int ENTRY_H = 14;
        private static final int SPACING = 2;

        BiomeListPanel(int x, int y, int w, int h, List<BiomeEntryWidget> entries) {
            super(x, y, w, h, Component.empty());
            this.entries = entries;
        }

        void setFilter(int mode) {
            this.filterMode = mode;
            this.scrollY = 0;
        }

        private int contentHeight() {
            int n = 0;
            for (BiomeEntryWidget e : entries) if (e.matchesFilter(filterMode)) n++;
            return Math.max(0, n * (ENTRY_H + SPACING) - SPACING);
        }

        @Override
        public void renderWidget(GuiGraphics g, int mx, int my, float pt) {
            int x = getX(), y = getY(), w = getWidth(), h = getHeight();
            g.fill(x, y, x + w, y + h, 0xFF15191F);
            int maxScroll = Math.max(0, contentHeight() - h);
            scrollY = Mth.clamp(scrollY, 0, maxScroll);

            g.enableScissor(x, y, x + w, y + h);
            int cy = y - scrollY;
            for (BiomeEntryWidget e : entries) {
                if (!e.matchesFilter(filterMode)) { e.visible = false; continue; }
                e.visible = true;
                e.setPosition(x + 2, cy);
                e.render(g, mx, my, pt);
                cy += ENTRY_H + SPACING;
            }
            g.disableScissor();
        }

        @Override
        public boolean mouseScrolled(double mx, double my, double delta) {
            if (mx < getX() || mx > getX() + getWidth() || my < getY() || my > getY() + getHeight())
                return false;
            int maxScroll = Math.max(0, contentHeight() - getHeight());
            scrollY = Mth.clamp(scrollY + (int) (delta * ENTRY_H), 0, maxScroll);
            return true;
        }

        @Override
        public boolean mouseClicked(double mx, double my, int btn) {
            if (mx < getX() || mx > getX() + getWidth() || my < getY() || my > getY() + getHeight())
                return false;
            for (BiomeEntryWidget e : entries) {
                if (e.visible && e.isMouseOver(mx, my)) {
                    return e.mouseClicked(mx, my, btn);
                }
            }
            return false;
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput out) {}
    }

    /** 单个群系条目：颜色方块 + 名称 */
    private static class BiomeEntryWidget extends AbstractWidget {
        private final BiomeClassifier.BiomeClass biome;
        private final int color;
        private final String label;

        BiomeEntryWidget(Font font, BiomeClassifier.BiomeClass biome, int color, String label) {
            super(0, 0, 320, 14, Component.literal(label));
            this.biome = biome;
            this.color = color;
            this.label = label;
        }

        boolean matchesFilter(int filter) {
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

        @Override
        public void renderWidget(GuiGraphics g, int mx, int my, float pt) {
            int x = getX(), y = getY(), w = getWidth();
            int bg = isHoveredOrFocused() ? 0xFF252A33 : 0xFF1A1E24;
            g.fill(x, y, x + w, y + height, bg);
            // 颜色方块 10x10
            g.fill(x + 2, y + 2, x + 12, y + 12, 0xFF000000 | color);
            g.fill(x + 2, y + 2, x + 12, y + 3, 0xFF888888);
            // 名称
            g.drawString(Minecraft.getInstance().font, label, x + 18, y + 3, 0xFFCCCCCC);
        }

        @Override
        public void onClick(double mx, double my) {}

        @Override
        protected void updateWidgetNarration(NarrationElementOutput out) {}

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
}
