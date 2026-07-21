package com.geogenesis.client.preview.mixer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleUnaryOperator;
import java.util.function.IntFunction;

/**
 * 模板C：单控制点曲线图（Single ControlPoint Curve）。
 *
 * <p>显示一条连续函数曲线，曲线上有可拖拽的控制点。
 * 用于雪线温度曲线、e→Y 映射等场景。
 */
public class SingleCurveChart {

    private String title;
    private final List<ControlPoint> points = new ArrayList<>();
    private Runnable onMarkDirty = () -> {};
    private ControlPoint dragging;

    private int cx, cy, cw = 180, ch = 100;
    private double xMin = -1.0, xMax = 1.0, yMin = 0.0, yMax = 1.0;
    private DoubleUnaryOperator curveFn = x -> 0;

    public SingleCurveChart(String title) { this.title = title; }

    public void setOnMarkDirty(Runnable r) { this.onMarkDirty = r; }
    public void setCurveFn(DoubleUnaryOperator fn) { this.curveFn = fn; }
    public void setXRange(double min, double max) { this.xMin = min; this.xMax = max; }
    public void setYRange(double min, double max) { this.yMin = min; this.yMax = max; }
    /** 计算曲线在 x 处的 y 值 */
    public double eval(double x) { return curveFn.applyAsDouble(x); }

    public void setPoints(List<ControlPoint> pts) {
        points.clear();
        for (ControlPoint p : pts) { p.setOnMarkDirty(onMarkDirty); points.add(p); }
    }
    public List<ControlPoint> getPoints() { return points; }

    public void setBounds(int x, int y, int w, int h) {
        cx = x + 36; cy = y + 14;
        cw = Math.max(40, w - 46); ch = Math.max(30, h - 38);
    }
    public int getHeight() { return ch + 38; }

    public int xToScreen(double v) { return cx + (int)((v - xMin) / (xMax - xMin) * cw); }
    public int yToScreen(double v) { return cy + (int)((1.0 - (v - yMin) / (yMax - yMin)) * ch); }
    public double screenToX(int sx) { return xMin + (double)(sx - cx) / cw * (xMax - xMin); }
    public double screenToY(int sy) { return yMin + (1.0 - (double)(sy - cy) / ch) * (yMax - yMin); }

    public void refreshPoints(IntFunction<double[]> posFn) {
        for (int i = 0; i < points.size(); i++) {
            double[] xy = posFn.apply(i);
            int px = xToScreen(xy[0]);
            int py = yToScreen(xy[1]);
            // 钳制位置保证控制点在图表内（关键：避免控制点落在图表外被遮挡或不可见）
            px = Math.max(cx + 6, Math.min(cx + cw - 6, px));
            py = Math.max(cy + 6, Math.min(cy + ch - 6, py));
            points.get(i).setPosition(px, py);
            points.get(i).setValueText(String.format("(%.3f, %.1f)", xy[0], xy[1]));
        }
    }

    public void render(GuiGraphics g, int mx, int my) {
        var f = Minecraft.getInstance().font;
        // 标题由父容器绘制（避免重复）
        // 背景 + 边框（与 DualRangeChart 一致）
        g.fill(cx, cy, cx + cw, cy + ch, 0xFF1a1f28);
        g.fill(cx, cy, cx + cw, cy + 1, 0xFF333344);
        g.fill(cx, cy + ch - 1, cx + cw, cy + ch, 0xFF333344);
        g.fill(cx, cy, cx + 1, cy + ch, 0xFF333344);
        g.fill(cx + cw - 1, cy, cx + cw, cy + ch, 0xFF333344);
        // Y 轴网格 + 标签（与 DualRangeChart 一致）
        int ySteps = 5;
        for (int i = 0; i <= ySteps; i++) {
            double frac = (double) i / ySteps;
            double yVal = yMin + frac * (yMax - yMin);
            int gy = yToScreen(yVal);
            int gridColor = (i == 0 || i == ySteps) ? 0xFF333344 : 0xFF222730;
            g.fill(cx, gy, cx + cw, gy + 1, gridColor);
            g.drawString(f, String.format("%.1f", yVal), cx - 28, gy - 4, 0xFF888888);
        }
        // X 轴网格 + 标签（与 DualRangeChart 一致）
        int xSteps = 4;
        for (int i = 0; i <= xSteps; i++) {
            double frac = (double) i / xSteps;
            double xVal = xMin + frac * (xMax - xMin);
            int gx = xToScreen(xVal);
            int gridColor = (i == 0 || i == xSteps) ? 0xFF333344 : 0xFF222730;
            g.fill(gx, cy, gx + 1, cy + ch, gridColor);
            g.drawString(f, String.format("%.1f", xVal), gx - 6, cy + ch + 2, 0xFF888888);
        }
        // 填充区域（曲线下方到X轴，半透明，与 DualRangeChart 一致）
        int segs = Math.max(2, cw);
        int baseY = yToScreen(yMin); // X 轴 Y 坐标
        for (int s = 0; s < segs; s++) {
            double t0 = (double)s / segs, t1 = (double)(s + 1) / segs;
            double x0 = xMin + t0 * (xMax - xMin), x1 = xMin + t1 * (xMax - xMin);
            double y0 = curveFn.applyAsDouble(x0), y1 = curveFn.applyAsDouble(x1);
            int sx0 = xToScreen(x0), sx1 = xToScreen(x1);
            int sy0 = yToScreen(y0), sy1 = yToScreen(y1);
            int top = Math.min(sy0, sy1);
            int bottom = Math.max(sy0, sy1);
            // 半透明填充区域
            g.fill(sx0, top, sx1, baseY, 0x3344CCFF);
        }
        // 曲线（与 DualRangeChart 一致的虚线）
        for (int s = 0; s < segs; s++) {
            double t0 = (double)s / segs, t1 = (double)(s + 1) / segs;
            double x0 = xMin + t0 * (xMax - xMin), x1 = xMin + t1 * (xMax - xMin);
            double y0 = curveFn.applyAsDouble(x0), y1 = curveFn.applyAsDouble(x1);
            int sx0 = xToScreen(x0), sy0 = yToScreen(y0);
            int sx1 = xToScreen(x1), sy1 = yToScreen(y1);
            int steps = Math.max(Math.abs(sx1 - sx0), Math.abs(sy1 - sy0));
            for (int i = 0; i <= steps; i++) {
                if ((i / 3) % 2 != 0) continue; // 虚线（与 DualRangeChart 一致）
                double t = steps > 0 ? (double)i / steps : 0;
                g.fill((int)(sx0 + (sx1 - sx0) * t), (int)(sy0 + (sy1 - sy0) * t),
                       (int)(sx0 + (sx1 - sx0) * t) + 1, (int)(sy0 + (sy1 - sy0) * t) + 1, 0xFF44CCFF);
            }
        }
        for (ControlPoint p : points) { p.hovered = p.hitTest(mx, my); p.render(g, mx, my, dragging == p); }
    }

    public void renderTooltips(GuiGraphics g, int mx, int my) {
        for (ControlPoint p : points) if (p.hovered) p.renderTooltip(g, mx, my);
    }

    // ---- 鼠标 ----
    public boolean mouseClicked(double mx, double my, int btn) {
        if (btn != 0) return false;
        for (ControlPoint p : points) {
            if (p.hitTest((int)mx, (int)my)) { dragging = p; p.onDragStart((int)mx, (int)my); return true; }
        }
        return false;
    }
    public boolean mouseDragged(double mx, double my, int btn, double dx, double dy) {
        if (dragging != null) {
            int nx = Math.max(cx, Math.min(cx + cw, (int)mx));
            int ny = Math.max(cy, Math.min(cy + ch, (int)my));
            dragging.setPosition(nx, ny);
            // 注意：不在此处调用 triggerValueChanged()
            // 拖拽中只更新视觉位置，mouseReleased 一次性写配置
            return true;
        }
        return false;
    }
    public boolean mouseReleased(double mx, double my, int btn) {
        if (dragging != null) {
            dragging.triggerValueChanged(); // 松手时才写配置（只写一次）
            dragging.onRelease();
            dragging = null;
            return true;
        }
        return false;
    }
}
