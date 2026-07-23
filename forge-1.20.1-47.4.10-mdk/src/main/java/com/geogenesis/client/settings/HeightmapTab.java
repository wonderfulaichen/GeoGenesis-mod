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
import net.minecraft.util.Mth;

import java.util.ArrayList;
import java.util.List;

/**
 * 高度图设置分页：色带选择列表（带预览渲染块）。
 * <p>色带列表用 {@link ColormapListPanel} 包裹，避免 14 条目直接堆叠溢出 tab 区域撞上导航栏 / 返回按钮。
 */
public class HeightmapTab extends GridLayoutTab {

    public HeightmapTab(Minecraft mc, PreviewDisplay preview) {
        super(Component.translatable("geogenesis.settings.heightmap.title"));

        GridLayout.RowHelper row = this.layout.rowSpacing(4).createRowHelper(1);

        // —— 色带选择标题 ——
        row.addChild(new CenteredLabel(mc.font, 320, 18,
            Component.translatable("geogenesis.settings.heightmap.colormap")));

        // —— 色带列表（可滚动）——
        List<ColormapEntry> entries = GeoPalette.getColormapEntries();
        ColormapEntry active = GeoPalette.getActiveColormap();

        List<ColormapOption> options = new ArrayList<>();
        for (ColormapEntry entry : entries) {
            boolean isActive = entry.name().equals(active.name());
            ColormapOption opt = new ColormapOption(mc.font, entry, isActive, () -> {
                GeoPalette.setActiveColormap(entry);
                for (ColormapOption o : options) o.setActive(o.entry == entry);
                preview.forceRefresh();
            });
            options.add(opt);
        }
        // 按屏幕高度计算面板高：屏幕高 - 顶导航 - 底栏 - 标签/提示/按钮 - 间距 - 余量
        int panelH = Mth.clamp(mc.getWindow().getGuiScaledHeight() - 220, 100, 260);
        ColormapListPanel listPanel = new ColormapListPanel(0, 0, 320, panelH, options);
        row.addChild(listPanel);

        // —— 高度编辑提示 ——
        row.addChild(new CenteredLabel(mc.font, 320, 18,
            Component.translatable("geogenesis.settings.heightmap.height")));
        row.addChild(new CenteredLabel(mc.font, 320, 12,
            Component.translatable("geogenesis.settings.heightmap.restart_hint")));

        // 重置按钮
        row.addChild(Button.builder(
            Component.translatable("geogenesis.settings.heightmap.reset"),
            b -> preview.resetHeightBounds()
        ).width(150).build());
    }

    /** 可滚动色带列表面板：固定高度、溢出滚动、scissor 裁剪。 */
    private static class ColormapListPanel extends AbstractWidget {
        private final List<ColormapOption> options;
        private int scrollY = 0;
        private static final int OPTION_H = 20;
        private static final int SPACING = 2;

        ColormapListPanel(int x, int y, int w, int h, List<ColormapOption> options) {
            super(x, y, w, h, Component.empty());
            this.options = options;
        }

        private int contentHeight() {
            return Math.max(0, options.size() * (OPTION_H + SPACING) - SPACING);
        }

        @Override
        public void renderWidget(GuiGraphics g, int mx, int my, float pt) {
            int x = getX(), y = getY(), w = getWidth(), h = getHeight();
            // 背景
            g.fill(x, y, x + w, y + h, 0xFF15191F);
            int maxScroll = Math.max(0, contentHeight() - h);
            scrollY = Mth.clamp(scrollY, 0, maxScroll);

            g.enableScissor(x, y, x + w, y + h);
            int cy = y - scrollY;
            for (ColormapOption opt : options) {
                opt.visible = true;
                opt.setPosition(x, cy);
                opt.setWidth(w);
                opt.render(g, mx, my, pt);
                cy += OPTION_H + SPACING;
            }
            g.disableScissor();

            // 滚动条指示器（内容超出时显示）
            if (maxScroll > 0) {
                int barH = Math.max(12, h * h / contentHeight());
                int barY = y + scrollY * (h - barH) / maxScroll;
                g.fill(x + w - 3, barY, x + w - 1, barY + barH, 0xFF00c896);
            }
        }

        @Override
        public boolean mouseScrolled(double mx, double my, double delta) {
            if (mx < getX() || mx > getX() + getWidth() || my < getY() || my > getY() + getHeight())
                return false;
            int maxScroll = Math.max(0, contentHeight() - getHeight());
            scrollY = Mth.clamp(scrollY + (int) (delta * OPTION_H), 0, maxScroll);
            return true;
        }

        @Override
        public boolean mouseClicked(double mx, double my, int btn) {
            if (mx < getX() || mx > getX() + getWidth() || my < getY() || my > getY() + getHeight())
                return false;
            for (ColormapOption opt : options) {
                if (opt.visible && opt.isMouseOver(mx, my)) {
                    return opt.mouseClicked(mx, my, btn);
                }
            }
            return false;
        }

        @Override
        protected void updateWidgetNarration(NarrationElementOutput out) {}
    }

    /** 色带条目：缩略图 + 名称 + 选中态 */
    private static class ColormapOption extends AbstractWidget {
        private final ColormapEntry entry;
        private boolean active;
        private final Runnable onClick;

        ColormapOption(Font font, ColormapEntry entry, boolean active, Runnable onClick) {
            super(0, 0, 320, 20, Component.literal(entry.name()));
            this.entry = entry;
            this.active = active;
            this.onClick = onClick;
        }

        void setActive(boolean active) {
            this.active = active;
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
                g.fill(x + 4 + i, y + 4, x + 5 + i, y + 16, 0xFF000000 | col);
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
