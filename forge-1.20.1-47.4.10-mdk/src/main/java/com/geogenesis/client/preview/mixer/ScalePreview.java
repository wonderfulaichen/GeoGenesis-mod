package com.geogenesis.client.preview.mixer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;

/**
 * 模板E：尺度预览（Scale Preview）。
 *
 * <p>对比垂直尺度（山脉/丘陵/平原/海洋段）+ 水平采样尺度波纹。
 * 段间边界可拖拽调整各类型高度比例。
 * 标题由父容器绘制，避免重复。
 */
public class ScalePreview {

    private Runnable onMarkDirty = () -> {};

    private int baseX, baseY, colW = 40, colH = 80, rowH = 24;

    private double horizontalScaleValue = 4.0;

    // 段间比例 [0,1]（从底到顶累积），最后一项恒为 1.0
    private final double[] segments = {0.05, 0.15, 0.35, 0.70, 1.0};
    private final int[] segColors = {0xFF446688, 0xFF44AA66, 0xFF88CC44, 0xFFCC8844, 0xFF884422};
    private final String[] segNames = {"海洋", "平原", "丘陵", "高原", "山脉"};

    // 可拖拽边界（位于段间）
    private final List<SegBoundary> boundaries = new ArrayList<>();
    private SegBoundary dragging;

    public ScalePreview() {
        // 初始化 4 个边界
        for (int i = 0; i < segments.length - 1; i++) {
            boundaries.add(new SegBoundary(i, segments[i]));
        }
    }

    public void setOnMarkDirty(Runnable r) { this.onMarkDirty = r; }
    public void setHorizontalScale(double v) { this.horizontalScaleValue = v; }
    public double getHorizontalScale() { return horizontalScaleValue; }

    public void setBounds(int x, int y, int w, int h) {
        baseX = x + 4;
        baseY = y + 8;
        colW = Math.max(30, Math.min(60, (w - 8) / 4));
        colH = Math.max(50, h - 60);
        rowH = 18;
        layoutBoundaries();
    }
    public int getHeight() { return colH + 50; }

    private void layoutBoundaries() {
        for (SegBoundary b : boundaries) {
            double frac = b.value / segments[segments.length - 1];
            b.screenY = baseY + colH - (int) (frac * colH);
            b.screenX = baseX + colW;
        }
    }

    // 从边界值重建 segments[]
    private void rebuildSegments() {
        for (int i = 0; i < boundaries.size(); i++) {
            segments[i] = boundaries.get(i).value;
        }
    }

    // 钳制边界不越界（相邻边界之间）
    private void clampBoundary(int idx) {
        if (idx < 0 || idx >= boundaries.size()) return;
        double lo = (idx > 0) ? boundaries.get(idx - 1).value + 0.005 : 0.005;
        double hi = (idx < boundaries.size() - 1) ? boundaries.get(idx + 1).value - 0.005 : 0.995;
        boundaries.get(idx).value = Math.max(lo, Math.min(hi, boundaries.get(idx).value));
    }

    public void render(GuiGraphics g, int mx, int my) {
        var f = Minecraft.getInstance().font;

        // ── 垂直柱（类型高度比例） ──
        int colX = baseX;
        int curY = baseY + colH;
        for (int i = segments.length - 1; i >= 0; i--) {
            double prevH = (i == 0) ? 0 : segments[i - 1];
            double hFrac = (i == 0) ? segments[0] : segments[i] - segments[i - 1];
            int segH = (int) (colH * hFrac);
            int segY = curY - segH;
            g.fill(colX, segY, colX + colW, curY, segColors[i]);
            g.fill(colX, segY, colX + colW, segY + 1, 0xFF555555);
            g.drawString(f, segNames[i], colX + colW + 4, segY + segH / 2 - 4, segColors[i]);
            curY = segY;
        }
        // 当前列高数值
        g.drawString(f, "Y" + (int)(colH * 0.8), colX + colW + 4, baseY - 4, 0xFF66CCFF);

        // ── 可拖拽段间边界（菱形标记） ──
        for (SegBoundary b : boundaries) {
            boolean sel = dragging == b;
            b.hovered = Math.abs(mx - b.screenX) <= 6 && Math.abs(my - b.screenY) <= 6;
            // 横线
            g.fill(colX - 2, b.screenY, colX + colW + 2, b.screenY + 1, sel ? 0xFFFFFFFF : 0xAAFFFFFF);
            // 菱形标记
            int cx = colX + colW / 2;
            int cy = b.screenY;
            int rs = 4;
            for (int dy = -rs; dy <= rs; dy++) {
                for (int dx = -rs; dx <= rs; dx++) {
                    if (Math.abs(dx) + Math.abs(dy) <= rs)
                        g.fill(cx + dx, cy + dy, cx + dx + 1, cy + dy + 1, sel ? 0xFFFFFF88 : 0xFFFFCC44);
                }
            }
            // 值标签
            String label = String.format("%.0f%%", b.value * 100);
            g.drawString(f, label, colX + colW + 50, b.screenY - 4, 0xFFCCCCCC);
        }

        // ── 水平尺度可视化（波纹图） ──
        int waveX = baseX + colW + 80;
        int waveY = baseY;
        int waveW = Math.max(60, baseX + 250 - waveX);
        int waveH = colH;
        g.fill(waveX, waveY, waveX + waveW, waveY + waveH, 0xFF1a1f28);
        int waves = Math.max(1, (int)(8 / horizontalScaleValue));
        for (int w = 0; w < waves; w++) {
            int wy = waveY + 4 + w * (waveH - 8) / waves + (waveH - 8) / waves / 2;
            int amp = Math.max(2, (int)(6 / horizontalScaleValue));
            for (int px = 0; px < waveW; px += 2) {
                int py = wy + (int)(amp * Math.sin((px + w * 10) * 0.3));
                g.fill(waveX + px, py, waveX + px + 2, py + 1, 0xFF66CCFF);
            }
        }
        g.drawString(f, String.format("×%.1f", horizontalScaleValue), waveX, waveY + waveH + 4, 0xFF66CCFF);
    }

    // ---- 鼠标 ----

    public boolean mouseClicked(double mx, double my, int btn) {
        if (btn != 0) return false;
        for (SegBoundary b : boundaries) {
            if (Math.abs(mx - b.screenX) <= 6 && Math.abs(my - b.screenY) <= 6) {
                dragging = b; return true;
            }
        }
        return false;
    }

    public boolean mouseDragged(double mx, double my, int btn, double dx, double dy) {
        if (dragging != null) {
            int ny = Math.max(baseY, Math.min(baseY + colH, (int) my));
            dragging.screenY = ny;
            double frac = 1.0 - (double) (ny - baseY) / colH;
            dragging.value = frac * segments[segments.length - 1];
            int idx = boundaries.indexOf(dragging);
            clampBoundary(idx);
            layoutBoundaries();
            rebuildSegments();
            return true;
        }
        return false;
    }

    public boolean mouseReleased(double mx, double my, int btn) {
        if (dragging != null) { dragging = null; if (onMarkDirty != null) onMarkDirty.run(); return true; }
        return false;
    }

    // ---- 段间边界 ----

    private static class SegBoundary {
        final int segIdx;
        double value;        // 累积段底比例 [0,1]
        int screenX, screenY;
        boolean hovered;

        SegBoundary(int idx, double val) { this.segIdx = idx; this.value = val; }
    }
}
