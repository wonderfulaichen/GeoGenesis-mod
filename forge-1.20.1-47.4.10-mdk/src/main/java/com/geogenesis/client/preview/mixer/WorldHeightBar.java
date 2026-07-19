package com.geogenesis.client.preview.mixer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/**
 * 模板D：世界高度柱状图（World Height Bar）。
 *
 * <p>显示世界垂直范围参数（最高点/海平面/世界底），
 * 以水平彩色标记在竖轴上的位置表示，可拖拽。
 */
public class WorldHeightBar {

    public static record Mark(String name, int color, IntSupplier getter, IntConsumer setter) {}

    private final List<Mark> marks = new ArrayList<>();
    private final List<MarkHandle> handles = new ArrayList<>();
    private Runnable onMarkDirty = () -> {};
    private MarkHandle dragging;

    private int barX, barY, barW = 24, barH = 200;

    public WorldHeightBar() {}

    public void setOnMarkDirty(Runnable r) { this.onMarkDirty = r; }

    public void setMarks(List<Mark> newMarks) {
        marks.clear(); handles.clear();
        marks.addAll(newMarks);
        for (int i = 0; i < marks.size(); i++) {
            MarkHandle h = new MarkHandle(marks.get(i));
            h.setOnMarkDirty(onMarkDirty);
            handles.add(h);
        }
        layoutHandles();
    }

    private void layoutHandles() {
        if (marks.isEmpty()) return;
        int yTop = marks.get(0).getter().getAsInt();
        int yBot = marks.get(marks.size() - 1).getter().getAsInt();
        int range = Math.max(1, yTop - yBot);
        for (int i = 0; i < marks.size(); i++) {
            int val = marks.get(i).getter().getAsInt();
            double frac = (double)(yTop - val) / range;
            int sy = barY + (int)(frac * barH);
            handles.get(i).setPosition(barX + barW / 2, sy);
        }
    }

    public void setBounds(int x, int y, int w, int h) {
        barX = x + 50; barY = y + 10;
        barW = Math.max(16, w - 70); barH = Math.max(60, h - 30);
        layoutHandles();
    }
    public int getHeight() { return barH + 30; }
    public void refreshFromConfig() { layoutHandles(); }

    public void render(GuiGraphics g, int mx, int my) {
        var f = Minecraft.getInstance().font;
        g.fill(barX, barY, barX + barW, barY + barH, 0xFF1a1f28);
        g.fill(barX, barY, barX + barW, barY + 1, 0xFF333333);
        g.fill(barX, barY + barH - 1, barX + barW, barY + barH, 0xFF333333);

        if (marks.size() >= 2) {
            int seaY = handles.get(1).getY();
            g.fill(barX, seaY, barX + barW, seaY + 1, 0x880088FF);
        }
        for (MarkHandle h : handles) {
            boolean sel = dragging == h;
            h.hovered = h.hitTest(mx, my);
            g.fill(barX, h.getY() - 1, barX + barW, h.getY() + 2, h.mark.color());
            int cx = h.getX(), cy = h.getY();
            int rx = 6, ry = 6;
            for (int dy = -ry; dy <= ry; dy++) {
                for (int dx = -rx; dx <= rx; dx++) {
                    if (Math.abs(dx) + Math.abs(dy) <= Math.max(rx, ry))
                        g.fill(cx + dx, cy + dy, cx + dx + 1, cy + dy + 1, sel ? 0xFFFFFFFF : h.mark.color());
                }
            }
            String label = h.mark.name() + "=" + h.mark.getter().getAsInt();
            g.drawString(f, label, barX + barW + 6, h.getY() - 4, h.mark.color());
        }
    }

    public void renderTooltips(GuiGraphics g, int mx, int my) {
        for (MarkHandle h : handles) {
            if (h.hovered) {
                String tip = h.mark.name() + ": Y=" + h.mark.getter().getAsInt();
                int tw = Minecraft.getInstance().font.width(tip) + 8;
                int tx = Math.min(mx + 10, barX + barW + 100 - tw);
                g.fill(tx, my - 16, tx + tw, my - 4, 0xEE000000);
                g.drawString(Minecraft.getInstance().font, tip, tx + 4, my - 14, 0xFFFFFF);
            }
        }
    }

    // ---- 鼠标 ----
    public boolean mouseClicked(double mx, double my, int btn) {
        if (btn != 0) return false;
        for (MarkHandle h : handles) {
            if (h.hitTest((int)mx, (int)my)) { dragging = h; return true; }
        }
        return false;
    }
    public boolean mouseDragged(double mx, double my, int btn, double dx, double dy) {
        if (dragging != null) {
            int newY = Math.max(barY, Math.min(barY + barH, (int)my));
            dragging.setPosition(dragging.getX(), newY);
            // 松手时才写配置，拖拽中只存临时位置
            return true;
        }
        return false;
    }
    public boolean mouseReleased(double mx, double my, int btn) {
        if (dragging != null) {
            // 松手时根据最终位置计算值并写入配置
            int yTop = marks.get(0).getter().getAsInt();
            int yBot = marks.get(marks.size() - 1).getter().getAsInt();
            double frac = (double)(dragging.getY() - barY) / barH;
            int newVal = (int)Math.round(yTop - frac * (yTop - yBot));
            newVal = Math.max(yBot, Math.min(yTop, newVal));
            dragging.mark.setter().accept(newVal);
            dragging = null;
            if (onMarkDirty != null) onMarkDirty.run();
            return true;
        }
        return false;
    }

    // ---- 标记 handle（非 static，可访问 barY/barH） ----
    private class MarkHandle extends ClickableRegion {
        final Mark mark;
        private int px, py;
        MarkHandle(Mark m) { this.mark = m; }
        public void setPosition(int x, int y) { px = x; py = y; }
        public int getX() { return px; }
        public int getY() { return py; }
        @Override public boolean hitTest(int mx, int my) { return Math.abs(mx - px) <= 10 && Math.abs(my - py) <= 8; }
        @Override public void render(GuiGraphics g, int mx, int my, boolean selected) {}
        @Override public void renderTooltip(GuiGraphics g, int mx, int my) {}
    }
}
