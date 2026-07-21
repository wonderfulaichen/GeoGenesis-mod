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
 * 以竖直分层高度带图表表示，可拖拽调整各层级高度。
 * 参考 MC 原版世界生成 UI 设计。
 */
public class WorldHeightBar {

    public static record Mark(String name, int color, IntSupplier getter, IntConsumer setter) {}

    private final List<Mark> marks = new ArrayList<>();
    private final List<MarkHandle> handles = new ArrayList<>();
    private Runnable onMarkDirty = () -> {};
    private MarkHandle dragging;

    // 分层颜色（从高到低）：山脊、陆地、浅海、深海
    private static final int COLOR_PEAK     = 0xFFFF4444; // 红色（最高点）
    private static final int COLOR_LAND     = 0xFFFF8844; // 橙色（陆地）
    private static final int COLOR_SEA      = 0xFF4488FF; // 蓝色（海洋）
    private static final int COLOR_DEEP     = 0xFF2a3a4a; // 深蓝（深海）
    private static final int COLOR_BG       = 0xFF1a1f28; // 背景色
    private static final int COLOR_BORDER   = 0xFF333333; // 边框色
    private static final int COLOR_SEA_LINE = 0x880088FF; // 海平面线

    private int barX, barY, barW = 24, barH = 200;
    private static final int MARK_SIZE = 6;

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
        // 标签在右侧，预留空间；y 应为标题栏下方位置
        barX = x + 4; barY = y + 12; // 增加偏移确保在标题栏下方
        barW = Math.max(20, w - 60); barH = Math.max(80, h - 28);
        layoutHandles();
    }
    public int getHeight() { return barH + 20; }
    public void refreshFromConfig() { layoutHandles(); }

    /** 将世界 Y 坐标转换为屏幕 Y 坐标 */
    private int worldYToScreen(int worldY) {
        int yTop = marks.get(0).getter().getAsInt();
        int yBot = marks.get(marks.size() - 1).getter().getAsInt();
        double frac = (double)(yTop - worldY) / Math.max(1, yTop - yBot);
        return barY + (int)(frac * barH);
    }

    /** 将屏幕 Y 坐标转换为世界 Y 坐标 */
    private int screenYToWorld(int screenY) {
        int yTop = marks.get(0).getter().getAsInt();
        int yBot = marks.get(marks.size() - 1).getter().getAsInt();
        double frac = (double)(screenY - barY) / barH;
        return (int)Math.round(yTop - frac * (yTop - yBot));
    }

    public void render(GuiGraphics g, int mx, int my) {
        var f = Minecraft.getInstance().font;

        // 1. 背景
        g.fill(barX, barY, barX + barW, barY + barH, COLOR_BG);
        g.fill(barX, barY, barX + barW, barY + 1, COLOR_BORDER);
        g.fill(barX, barY + barH - 1, barX + barW, barY + barH, COLOR_BORDER);

        if (marks.size() < 3) return;

        int maxY = marks.get(0).getter().getAsInt();  // 最高点
        int seaLevel = marks.get(1).getter().getAsInt(); // 海平面
        int minY = marks.get(2).getter().getAsInt();  // 世界底

        // 2. 分层颜色带（从上到下）
        int seaScreenY = worldYToScreen(seaLevel);
        int maxScreenY = worldYToScreen(maxY);
        int minScreenY = worldYToScreen(minY);

        // 深海（世界底到海平面）
        g.fill(barX, seaScreenY, barX + barW, minScreenY, COLOR_DEEP);
        // 浅海（海平面向下20%处到海平面）
        int shallowEnd = seaScreenY + (minScreenY - seaScreenY) / 5;
        g.fill(barX, seaScreenY, barX + barW, shallowEnd, COLOR_SEA);
        // 海平面线
        g.fill(barX, seaScreenY - 1, barX + barW, seaScreenY + 2, COLOR_SEA_LINE);
        // 陆地（最高点到海平面）
        g.fill(barX, maxScreenY, barX + barW, seaScreenY, COLOR_LAND);
        // 山脊（最高点向上20%处到最高点）
        int peakStart = maxScreenY - (seaScreenY - maxScreenY) / 5;
        g.fill(barX, peakStart, barX + barW, maxScreenY, COLOR_PEAK);

        // 3. 标记（菱形）
        for (MarkHandle h : handles) {
            boolean sel = dragging == h;
            h.hovered = h.hitTest(mx, my);
            drawDiamond(g, h.getX(), h.getY(), MARK_SIZE, sel ? 0xFFFFFFFF : h.mark.color());
            // 右侧标签
            String label = h.mark.name() + "=" + h.mark.getter().getAsInt();
            g.drawString(f, label, barX + barW + 4, h.getY() - 4, h.mark.color());
        }

        // 4. 刻度标记（在柱内居中显示）
        String maxStr = String.valueOf(maxY);
        String seaStr = String.valueOf(seaLevel);
        String minStr = String.valueOf(minY);
        g.drawString(f, maxStr, barX + (barW - f.width(maxStr)) / 2, maxScreenY - 4, 0xFF888888);
        g.drawString(f, seaStr, barX + (barW - f.width(seaStr)) / 2, seaScreenY - 4, 0xFF888888);
        g.drawString(f, minStr, barX + (barW - f.width(minStr)) / 2, minScreenY - 4, 0xFF888888);
    }

    /** 绘制菱形 */
    private void drawDiamond(GuiGraphics g, int cx, int cy, int size, int color) {
        for (int dy = -size; dy <= size; dy++) {
            int halfW = size - Math.abs(dy);
            if (halfW > 0) {
                g.fill(cx - halfW, cy + dy, cx + halfW + 1, cy + dy + 1, color);
            }
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
            int newVal = screenYToWorld(dragging.getY());
            int idx = handles.indexOf(dragging);
            // 高度顺序约束：最高点 > 海平面 > 世界底
            if (idx == 0) {
                // 最高点：必须 > 海平面
                int seaLevel = marks.get(1).getter().getAsInt();
                newVal = Math.max(seaLevel + 1, newVal);
            } else if (idx == 1) {
                // 海平面：必须 > 世界底 且 < 最高点
                int minY = marks.get(2).getter().getAsInt();
                int maxY = marks.get(0).getter().getAsInt();
                newVal = Math.max(minY + 1, Math.min(maxY - 1, newVal));
            } else if (idx == 2) {
                // 世界底：必须 < 海平面
                int seaLevel = marks.get(1).getter().getAsInt();
                newVal = Math.min(seaLevel - 1, newVal);
            }
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
        @Override public boolean hitTest(int mx, int my) { return Math.abs(mx - px) <= MARK_SIZE && Math.abs(my - py) <= MARK_SIZE; }
        @Override public void render(GuiGraphics g, int mx, int my, boolean selected) {}
        @Override public void renderTooltip(GuiGraphics g, int mx, int my) {}
    }
}
