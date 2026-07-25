package com.geogenesis.client.preview.mixer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;

/**
 * 模板E：尺度预览（Scale Preview）。
 *
 * <p>显示垂直缩放比例和水平缩放比例（HS参数），控制世界的缩放方式。
 * 使用水平刻度尺样式，更直观。
 * 标题由父容器绘制，避免重复。
 */
public class ScalePreview {

    private Runnable onMarkDirty = () -> {};

    private int baseX, baseY, baseW = 200, baseH = 80;

    private double verticalScaleValue = 1.0;   // 垂直缩放比例（1-8）
    private double horizontalScaleValue = 1.0; // HS参数（0.5-8，默认1=1:1）

    // 默认值
    private double defaultVerticalScale = 1.0;
    private double defaultHorizontalScale = 1.0;

    // 标记位置
    private int vsMarkerY, hsMarkerX;

    // 重置按钮
    private static final int RESET_BTN_W = 18;
    private static final int RESET_BTN_H = 14;
    private static final int RESET_BG = 0xFF2a2f3a;
    private static final int RESET_HOVER_BG = 0xFF3a4050;
    private static final int RESET_BORDER = 0xFF4a5060;
    private boolean resetHovered = false;

    public ScalePreview() {}

    public void setOnMarkDirty(Runnable r) { this.onMarkDirty = r; }
    public void setVerticalScale(double v) { this.verticalScaleValue = Math.max(1.0, Math.min(8.0, v)); }
    public double getVerticalScale() { return verticalScaleValue; }
    public void setHorizontalScale(double v) { this.horizontalScaleValue = Math.max(0.5, Math.min(8.0, v)); }
    public double getHorizontalScale() { return horizontalScaleValue; }
    public void setDefaultVerticalScale(double v) { this.defaultVerticalScale = v; }
    public void setDefaultHorizontalScale(double v) { this.defaultHorizontalScale = v; }
    
    /** 重置到默认值 */
    public void resetToDefaults() {
        this.verticalScaleValue = defaultVerticalScale;
        this.horizontalScaleValue = defaultHorizontalScale;
        if (onMarkDirty != null) onMarkDirty.run();
    }

    public void setBounds(int x, int y, int w, int h) {
        baseX = x + 4;
        baseY = y + 4;
        baseW = Math.max(120, w - 8);
        baseH = Math.max(40, h - 20);
    }
    public int getHeight() { return baseH + 20; }

    /** 垂直缩放值到屏幕Y坐标（竖直柱） */
    private int vsScaleToY(double val, int yTop, int height) {
        double frac = (val - 1.0) / 7.0; // 统一范围：1 到 8 → 0 到 1
        return yTop + height - (int)(frac * height); // 顶部=大值，底部=小值
    }
    /** 水平HS值到屏幕X坐标（水平滑块），范围 0.5-8.0 */
    private int hsScaleToX(double val, int xLeft, int width) {
        double frac = (val - 0.5) / 7.5; // 0.5 → 0, 8.0 → 1
        return xLeft + (int)(frac * width);
    }

    public void render(GuiGraphics g, int mx, int my) {
        var f = Minecraft.getInstance().font;

        // 统一布局参数
        int vsX = baseX;
        int vsW = 24;
        int hsX = vsX + vsW + 60;
        int hsW = baseW - vsW - 60;
        int hsY = baseY;
        int hsH = 16;

        // ── 垂直缩放柱（竖直方向） ──
        int vsH = baseH;
        // 柱背景
        g.fill(vsX, hsY, vsX + vsW, hsY + vsH, 0xFF1a1f28);
        g.fill(vsX, hsY, vsX + 1, hsY + vsH, 0xFF333344);
        g.fill(vsX + vsW - 1, hsY, vsX + vsW, hsY + vsH, 0xFF333344);
        g.fill(vsX, hsY, vsX + vsW, hsY + 1, 0xFF333344);
        g.fill(vsX, hsY + vsH - 1, vsX + vsW, hsY + vsH, 0xFF333344);
        // 标题（统一位置）
        g.drawString(f, "垂直", vsX + vsW / 2 - 8, hsY - 2, 0xFF888888);
        // 刻度（统一范围：1 到 8，与水平滑块一致）
        for (int i = 1; i <= 8; i++) {
            int tickY = vsScaleToY((double) i, hsY, vsH);
            g.fill(vsX - 2, tickY, vsX, tickY + 1, 0xFF888888);
            g.drawString(f, String.valueOf(i), vsX + vsW + 3, tickY - 4, 0xFF888888);
        }
        // 当前标记（水平条）
        vsMarkerY = vsScaleToY(verticalScaleValue, hsY, vsH);
        g.fill(vsX - 2, vsMarkerY - 1, vsX + vsW + 2, vsMarkerY + 2, 0xFF44AA66);
        // 当前值（显示在垂直柱下方，与水平滑块的值位置一致）
        g.drawString(f, String.format("×%.1f", verticalScaleValue), vsX, hsY + vsH + 4, 0xFF44AA66);

        // ── 水平缩放滑块（水平方向） ──
        // 滑块背景
        g.fill(hsX, hsY, hsX + hsW, hsY + hsH, 0xFF1a1f28);
        g.fill(hsX, hsY, hsX + hsW, hsY + 1, 0xFF333344);
        g.fill(hsX, hsY + hsH - 1, hsX + hsW, hsY + hsH, 0xFF333344);
        g.fill(hsX, hsY, hsX + 1, hsY + hsH, 0xFF333344);
        g.fill(hsX + hsW - 1, hsY, hsX + hsW, hsY + hsH, 0xFF333344);
        // 标题（统一位置）
        g.drawString(f, "水平 HS", hsX, hsY - 2, 0xFF888888);
        // 刻度（1到8）
        for (int i = 1; i <= 8; i++) {
            int tickX = hsScaleToX(i, hsX, hsW);
            g.fill(tickX, hsY, tickX + 1, hsY + hsH, 0xFF888888);
            g.drawString(f, String.valueOf(i), tickX - 3, hsY + hsH + 2, 0xFF888888);
        }
        // 当前标记（竖直条）
        hsMarkerX = hsScaleToX(horizontalScaleValue, hsX, hsW);
        g.fill(hsMarkerX - 1, hsY - 2, hsMarkerX + 2, hsY + hsH + 2, 0xFF66CCFF);
        // 当前值（统一位置：显示在滑块下方）
        g.drawString(f, String.format("×%.1f", horizontalScaleValue), hsX, hsY + hsH + 12, 0xFF66CCFF);
        
        // 重置按钮（水平 HS 下方右侧空白处）
        int rbX = baseX + baseW - RESET_BTN_W - 4;
        int rbY = hsY + hsH + 22;
        resetHovered = mx >= rbX && mx <= rbX + RESET_BTN_W && my >= rbY && my <= rbY + RESET_BTN_H;
        int rbBg = resetHovered ? RESET_HOVER_BG : RESET_BG;
        g.fill(rbX, rbY, rbX + RESET_BTN_W, rbY + RESET_BTN_H, rbBg);
        g.fill(rbX, rbY, rbX + 1, rbY + RESET_BTN_H, RESET_BORDER);
        g.fill(rbX + RESET_BTN_W - 1, rbY, rbX + RESET_BTN_W, rbY + RESET_BTN_H, RESET_BORDER);
        String resetIcon = "↩";
        var f2 = Minecraft.getInstance().font;
        int iconW = f2.width(resetIcon);
        int iconX = rbX + (RESET_BTN_W - iconW) / 2;
        int iconY = rbY + (RESET_BTN_H - 8) / 2;
        g.drawString(f2, resetIcon, iconX, iconY, resetHovered ? 0xFF00c896 : 0xFF999999);
    }

    // ---- 鼠标 ----

    public boolean mouseClicked(double mx, double my, int btn) {
        if (btn != 0) return false;
        int vsX = baseX;
        int vsW = 24;
        int hsX = vsX + vsW + 60;
        int hsW = baseW - vsW - 60;
        int hsY = baseY;
        int hsH = 16;
        // 检测重置按钮点击（与 render 中位置一致：水平 HS 下方右侧空白处）
        int rbX = baseX + baseW - RESET_BTN_W - 4;
        int rbY = hsY + hsH + 22;
        if (mx >= rbX && mx <= rbX + RESET_BTN_W && my >= rbY && my <= rbY + RESET_BTN_H) {
            resetToDefaults();
            return true;
        }
        // 检测垂直缩放标记（水平条）
        if (Math.abs(mx - (vsX + vsW / 2)) <= vsW / 2 + 4 && Math.abs(my - vsMarkerY) <= 6) {
            return true;
        }
        // 检测水平缩放标记（竖直条）
        if (Math.abs(mx - hsMarkerX) <= 6 && my >= hsY - 4 && my <= hsY + hsH + 4) {
            return true;
        }
        return false;
    }

    public boolean mouseDragged(double mx, double my, int btn, double dx, double dy) {
        int vsX = baseX;
        int vsW = 24;
        int hsX = vsX + vsW + 60;
        int hsW = baseW - vsW - 60;
        int hsY = baseY;
        int hsH = 16;
        int vsH = baseH; // 直接使用 baseH，与 render 方法一致
        int sy = Math.max(baseY, Math.min(baseY + vsH, (int) my));
        // 垂直缩放：1 到 8（与水平一致）
        if (Math.abs(mx - (vsX + vsW / 2)) <= vsW / 2 + 4) {
            double frac = 1.0 - (double)(sy - baseY) / vsH;
            verticalScaleValue = 1.0 + frac * 7.0;
            verticalScaleValue = Math.max(1.0, Math.min(8.0, verticalScaleValue));
            return true;
        }
        // 水平缩放：0.5 到 8.0
        int sx = Math.max(hsX, Math.min(hsX + hsW, (int) mx));
        if (my >= hsY - 4 && my <= hsY + hsH + 4) {
            double frac = (double)(sx - hsX) / hsW;
            horizontalScaleValue = 0.5 + frac * 7.5;
            horizontalScaleValue = Math.max(0.5, Math.min(8.0, horizontalScaleValue));
            return true;
        }
        return false;
    }

    public boolean mouseReleased(double mx, double my, int btn) {
        if (onMarkDirty != null) onMarkDirty.run();
        return false;
    }
}
