package com.geogenesis.client.settings;

import com.geogenesis.client.preview.GeoPalette;
import com.geogenesis.client.preview.GeoPalette.ColormapEntry;
import com.geogenesis.client.preview.PreviewDisplay;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.tabs.GridLayoutTab;
import net.minecraft.client.gui.layouts.GridLayout;
import net.minecraft.client.gui.narration.NarrationElementOutput;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * 高度图设置分页：色带选择列表（带预览渲染块）。
 */
public class HeightmapTab extends GridLayoutTab {

    public HeightmapTab(Minecraft mc, PreviewDisplay preview) {
        super(Component.translatable("geogenesis.settings.heightmap.title"));

        GridLayout.RowHelper row = this.layout.rowSpacing(6).createRowHelper(1);

        // —— 色带选择 ——
        row.addChild(new CenteredLabel(mc.font, 320, 20,
            Component.translatable("geogenesis.settings.heightmap.colormap")));

        List<ColormapEntry> entries = GeoPalette.getColormapEntries();
        ColormapEntry active = GeoPalette.getActiveColormap();

        for (ColormapEntry entry : entries) {
            boolean isActive = entry.name().equals(active.name());
            row.addChild(new ColormapOption(mc.font, entry, isActive, () -> {
                GeoPalette.setActiveColormap(entry);
                preview.forceRefresh();
            }));
        }

        // —— 高度编辑提示 ——
        row.addChild(new CenteredLabel(mc.font, 320, 20,
            Component.translatable("geogenesis.settings.heightmap.height")));
        row.addChild(new CenteredLabel(mc.font, 320, 12,
            Component.translatable("geogenesis.settings.heightmap.restart_hint")));

        // 重置按钮
        row.addChild(Button.builder(
            Component.translatable("geogenesis.settings.heightmap.reset"),
            b -> preview.resetHeightBounds()
        ).width(150).build());
    }

    /** 色带条目：缩略图 + 名称 + 选中态 */
    private static class ColormapOption extends AbstractWidget {
        private final ColormapEntry entry;
        private final boolean active;
        private final Runnable onClick;

        ColormapOption(Font font, ColormapEntry entry, boolean active, Runnable onClick) {
            super(0, 0, 320, 22, Component.literal(entry.name()));
            this.entry = entry;
            this.active = active;
            this.onClick = onClick;
        }

        @Override
        public void renderWidget(GuiGraphics g, int mx, int my, float pt) {
            int x = getX(), y = getY(), w = getWidth();
            int bg = isHoveredOrFocused() ? 0xFF252A33 : (active ? 0xFF1A3A2A : 0xFF1A1E24);
            g.fill(x, y, x + w, y + height, bg);
            if (active) {
                g.fill(x, y, x + w, y + 1, 0xFF00C896);
                g.fill(x, y + height - 1, x + w, y + height, 0xFF00C896);
            }
            // 缩略图（48x12 色带条）
            for (int i = 0; i < 48; i++) {
                float t = i / 47f;
                int col = entry.colormap().getRGB(t);
                g.fill(x + 4 + i, y + 3, x + 5 + i, y + 15, 0xFF000000 | col);
            }
            // 名称
            Font font = Minecraft.getInstance().font;
            g.drawString(font, entry.name(), x + 60, y + 6, active ? 0xFF00C896 : 0xFFCCCCCC);
        }

        @Override
        public void onClick(double mx, double my) {
            onClick.run();
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput out) {}
    }
}
