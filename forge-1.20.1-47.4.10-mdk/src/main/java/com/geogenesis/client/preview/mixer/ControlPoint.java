package com.geogenesis.client.preview.mixer;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/**
 * 可拖拽控制点（圆形/方形两种形态）。
 *
 * <p>用于：
 * <ul>
 *   <li>模板A DualRangeChart：上下两组曲线上的控制点（方形：精确数值定位）</li>
 *   <li>模板C SingleCurveChart：曲线上的形状控制点（圆形：自由度更高）</li>
 * </ul>
 */
public class ControlPoint extends ClickableRegion {

    /** 形态：圆点（自由拖拽）/ 方形（精确数值定位） */
    public enum Shape { CIRCLE, SQUARE }

    private int cx, cy;
    private final int color;
    private final String name;
    private String valueText = "";
    private Shape shape = Shape.CIRCLE;
    private int size = 6;  // 半径或半边长
    private boolean readOnly = false;

    private Runnable onValueChanged = () -> {};

    public ControlPoint(int cx, int cy, int color, String name) {
        this.cx = cx; this.cy = cy; this.color = color; this.name = name;
    }

    public ControlPoint setShape(Shape s) { this.shape = s; return this; }
    public ControlPoint setSize(int s) { this.size = s; return this; }
    public int getSize() { return size; }
    public Shape getShape() { return shape; }

    public int getX() { return cx; }
    public int getY() { return cy; }
    public void setPosition(int x, int y) { this.cx = x; this.cy = y; }
    public void setValueText(String s) { this.valueText = s; }
    public void setOnValueChanged(Runnable r) { this.onValueChanged = r; }
    /** 标记为只读（不可拖拽） */
    public ControlPoint setReadOnly(boolean b) { this.readOnly = b; return this; }
    public boolean isReadOnly() { return readOnly; }

    /** 外部触发值变更回调（拖拽时使用） */
    public void triggerValueChanged() { if (onValueChanged != null) onValueChanged.run(); }

    @Override
    public boolean hitTest(int mx, int my) {
        if (shape == Shape.SQUARE) {
            return Math.abs(mx - cx) <= size && Math.abs(my - cy) <= size;
        }
        int dx = mx - cx, dy = my - cy;
        return dx * dx + dy * dy <= size * size;
    }

    @Override
    public void onDrag(int dx, int dy) {
        cx += dx; cy += dy;
        if (onValueChanged != null) onValueChanged.run();
    }

    @Override
    public void render(GuiGraphics g, int mx, int my, boolean selected) {
        int s = selected ? size + 2 : size;
        // 外圈高亮（选中或悬停）
        if (selected || hovered) {
            if (shape == Shape.SQUARE) {
                drawSquare(g, cx, cy, s + 2, 0xAAFFFFFF);
            } else {
                fillCircle(g, cx, cy, s + 2, 0x88FFFFFF);
            }
        }
        // 本体（方形带渐变高光 / 圆形带内圈高光）
        if (shape == Shape.SQUARE) {
            drawSquare(g, cx, cy, s, color);
            // 高光（左上角白色叠加）
            int hl = (s * 3) / 8;
            for (int dy = -s; dy < -s + hl; dy++) {
                for (int dx = -s; dx < -s + hl; dx++) {
                    g.fill(cx + dx, cy + dy, cx + dx + 1, cy + dy + 1, 0x44FFFFFF);
                }
            }
            // 边框
            g.fill(cx - s, cy - s, cx + s + 1, cy - s + 1, darken(color, 0.7f));
            g.fill(cx - s, cy + s, cx + s + 1, cy + s + 1, darken(color, 0.7f));
            g.fill(cx - s, cy - s, cx - s + 1, cy + s + 1, darken(color, 0.7f));
            g.fill(cx + s, cy - s, cx + s + 1, cy + s + 1, darken(color, 0.7f));
        } else {
            fillCircle(g, cx, cy, s, color);
            fillCircle(g, cx - 1, cy - 1, Math.max(2, s - 4), 0x55FFFFFF);
        }
    }

    @Override
    public void renderTooltip(GuiGraphics g, int mx, int my) {
        var f = Minecraft.getInstance().font;
        String tip = name + " " + valueText;
        int tw = f.width(tip) + 8;
        int tx = Math.min(mx + 10, cx + 60 - tw);
        int ty = my - 18;
        g.fill(tx, ty, tx + tw, ty + 12, 0xEE000000);
        g.drawString(f, tip, tx + 4, ty + 2, 0xFFFFFF);
    }

    // ---- 工具 ----

    private static void drawSquare(GuiGraphics g, int cx, int cy, int s, int color) {
        g.fill(cx - s, cy - s, cx + s, cy + s, color);
    }

    private static void fillCircle(GuiGraphics g, int cx, int cy, int r, int color) {
        int r2 = r * r;
        for (int dy = -r; dy <= r; dy++) {
            for (int dx = -r; dx <= r; dx++) {
                if (dx * dx + dy * dy <= r2)
                    g.fill(cx + dx, cy + dy, cx + dx + 1, cy + dy + 1, color);
            }
        }
    }

    private static int darken(int color, float factor) {
        int a = (color >> 24) & 0xFF;
        int r = (int) (((color >> 16) & 0xFF) * factor);
        int g = (int) (((color >> 8) & 0xFF) * factor);
        int b = (int) ((color & 0xFF) * factor);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }
}
