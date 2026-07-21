package com.geogenesis.client.preview.mixer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;

/**
 * 模板B：分类色条（Category Bar）。
 *
 * <p>一条水平连续轴（如 [-1,1]）被划分为若干离散类别，每段涂不同颜色。
 * 拖拽类别边界线调整阈值。
 */
public class CategoryBar {

    public static record Category(String name, int color) {}

    private String title;
    private final List<Category> categories = new ArrayList<>();
    private final List<Boundary> boundaries = new ArrayList<>();
    private final List<Double> defaultThresholds = new ArrayList<>();
    private Runnable onMarkDirty = () -> {};

    private int barX, barY, barW = 200, barH = 20;
    private double dataMin = -1.0, dataMax = 1.0;

    private Boundary dragging;

    // 重置按钮
    private static final int RESET_BTN_W = 18;
    private static final int RESET_BTN_H = 14;
    private static final int RESET_BG = 0xFF2a2f3a;
    private static final int RESET_HOVER_BG = 0xFF3a4050;
    private static final int RESET_BORDER = 0xFF4a5060;
    private boolean resetHovered = false;

    public CategoryBar(String title) { this.title = title; }

    public void setOnMarkDirty(Runnable r) { this.onMarkDirty = r; }

    /** 获取边界数量 */
    public int getBoundaryCount() { return boundaries.size(); }
    /** 获取指定边界的当前数据值 */
    public double getBoundaryValue(int idx) { return boundaries.get(idx).getValue(); }
    /** 从外部设置边界数据值（更新位置 + 触发回调），供关联滑块使用 */
    public void setBoundaryValue(int idx, double val) {
        val = Math.max(dataMin + 0.001, Math.min(dataMax - 0.001, val));
        boundaries.get(idx).setValue(val);
        boundaries.get(idx).setScreenX(dataToScreenX(val));
    }

    public void setCategories(List<Category> cats, List<Double> initialThresholds,
                              List<DoubleConsumer> setters, List<DoubleSupplier> getters) {
        categories.clear();
        boundaries.clear();
        defaultThresholds.clear();
        categories.addAll(cats);
        int n = cats.size();
        if (n < 2) return;
        for (int i = 0; i < n - 1; i++) {
            final int idx = i;
            double val = (initialThresholds != null && idx < initialThresholds.size())
                    ? initialThresholds.get(idx) : (dataMin + (dataMax - dataMin) * (idx + 1) / n);
            // 存储默认值（从 getters 读取真正的配置默认值）
            double defVal = (getters != null && idx < getters.size()) ? getters.get(idx).getAsDouble() : val;
            // 如果 initialThresholds 与 getters 值不同，说明 initialThresholds 是当前值，getters 可能也是当前值
            // 我们在 setDefaultThresholds 中单独存储
            defaultThresholds.add(val);
            
            Boundary b = new Boundary(val, cats.get(idx).name() + "→" + cats.get(idx + 1).name());
            b.setValueCallback(v -> {
                if (setters != null && idx < setters.size() && setters.get(idx) != null)
                    setters.get(idx).accept(v);
            });
            b.setOnMarkDirty(onMarkDirty);
            boundaries.add(b);
        }
        layoutBoundaries();
    }

    /** 设置每个边界控制点的默认值（从 GeoGenesisConfig.getDefault() 获取） */
    public void setDefaultThresholds(List<Double> defaults) {
        defaultThresholds.clear();
        defaultThresholds.addAll(defaults);
    }

    /** 重置所有边界控制点到默认值 */
    public void resetToDefaults() {
        for (int i = 0; i < boundaries.size() && i < defaultThresholds.size(); i++) {
            boundaries.get(i).setValue(defaultThresholds.get(i));
            boundaries.get(i).setScreenX(dataToScreenX(defaultThresholds.get(i)));
        }
        if (onMarkDirty != null) onMarkDirty.run();
    }

    /** 获取所有分类的名称（供父面板渲染边界标签用） */
    public String[] getCategoryNames() {
        return categories.stream().map(Category::name).toArray(String[]::new);
    }

    /** 获取分类数量 */
    public int getCategoryCount() {
        return categories.size();
    }

    private void layoutBoundaries() {
        for (Boundary b : boundaries) b.setScreenX(dataToScreenX(b.getValue()));
    }

    public void refreshFromConfig(List<DoubleSupplier> getters) {
        for (int i = 0; i < boundaries.size() && i < getters.size(); i++) {
            double v = getters.get(i).getAsDouble();
            boundaries.get(i).setValue(v);
            boundaries.get(i).setScreenX(dataToScreenX(v));
        }
    }

    public void setBounds(int x, int y, int w, int h) {
        // 不再偏移 16 像素（标题由父组件 MixerPanel 渲染）
        barX = x; barY = y;
        barW = Math.max(40, w); barH = Math.max(16, h);
        layoutBoundaries();
    }
    public int getHeight() { return barH + 12; } // barH + dataMin/dataMax 标签空间

    private int dataToScreenX(double v) { return barX + (int)((v - dataMin) / (dataMax - dataMin) * barW); }
    private double screenXToData(int sx) { return dataMin + (double)(sx - barX) / barW * (dataMax - dataMin); }

    public void render(GuiGraphics g, int mx, int my) {
        int n = categories.size();
        if (n == 0) return;
        var f = Minecraft.getInstance().font;
        // 注意：标题由父容器（MixerPanel/panel）绘制，避免重复
        g.fill(barX - 1, barY - 1, barX + barW + 1, barY + barH + 1, 0xFF333333);
        int prevX = barX;
        for (int i = 0; i < n; i++) {
            int nextX = (i < n - 1) ? boundaries.get(i).getScreenX() : barX + barW;
            g.fill(prevX, barY, nextX, barY + barH, categories.get(i).color());
            prevX = nextX;
        }
        for (Boundary b : boundaries) {
            int bx = b.getScreenX();
            b.hovered = Math.abs(mx - bx) <= 4 && my >= barY && my <= barY + barH;
            int lineColor = (dragging == b || b.hovered) ? 0xFFFFFFFF : 0xAAFFFFFF;
            g.fill(bx, barY, bx + 1, barY + barH, lineColor);
            g.fill(bx - 3, barY - 4, bx + 4, barY, lineColor);
            // 在控制点正上方显示数值，始终可见（非仅 tooltip）
            String valStr = String.format("%.2f", b.getValue());
            int vw = f.width(valStr);
            int vx = Math.max(barX, Math.min(bx - vw / 2, barX + barW - vw));
            g.drawString(f, valStr, vx, barY - 16, 0xFFEEEEEE);
        }
        g.drawString(f, String.format("%.1f", dataMin), barX - 4, barY + barH + 4, 0xFF888888);
        g.drawString(f, String.format("%.1f", dataMax), barX + barW - 20, barY + barH + 4, 0xFF888888);
        
        // 重置按钮（色条右上角）
        int rbX = barX + barW + 6;
        int rbY = barY;
        resetHovered = mx >= rbX && mx <= rbX + RESET_BTN_W && my >= rbY && my <= rbY + RESET_BTN_H;
        int rbBg = resetHovered ? RESET_HOVER_BG : RESET_BG;
        g.fill(rbX, rbY, rbX + RESET_BTN_W, rbY + RESET_BTN_H, rbBg);
        g.fill(rbX, rbY, rbX + 1, rbY + RESET_BTN_H, RESET_BORDER);
        g.fill(rbX + RESET_BTN_W - 1, rbY, rbX + RESET_BTN_W, rbY + RESET_BTN_H, RESET_BORDER);
        String resetIcon = "↩";
        int iconW = f.width(resetIcon);
        int iconX = rbX + (RESET_BTN_W - iconW) / 2;
        int iconY = rbY + (RESET_BTN_H - 8) / 2;
        g.drawString(f, resetIcon, iconX, iconY, resetHovered ? 0xFF00c896 : 0xFF999999);
    }

    public void renderTooltips(GuiGraphics g, int mx, int my) {
        for (Boundary b : boundaries) {
            if (b.hovered) {
                var f = Minecraft.getInstance().font;
                String tip = b.name + ": " + String.format("%.3f", b.getValue());
                int tw = f.width(tip) + 8;
                int tx = Math.min(b.getScreenX() - tw / 2, barX + barW - tw);
                tx = Math.max(barX, tx);
                g.fill(tx, barY - 20, tx + tw, barY - 8, 0xEE000000);
                g.drawString(f, tip, tx + 4, barY - 18, 0xFFFFFF);
            }
        }
    }

    // ---- 鼠标 ----
    public boolean mouseClicked(double mx, double my, int btn) {
        if (btn != 0) return false;
        // 检测重置按钮点击
        int rbX = barX + barW + 6;
        int rbY = barY;
        if (mx >= rbX && mx <= rbX + RESET_BTN_W && my >= rbY && my <= rbY + RESET_BTN_H) {
            resetToDefaults();
            return true;
        }
        for (Boundary b : boundaries) {
            if (Math.abs(mx - b.getScreenX()) <= 4 && my >= barY && my <= barY + barH) {
                dragging = b; return true;
            }
        }
        return false;
    }
    public boolean mouseDragged(double mx, double my, int btn, double dx, double dy) {
        if (dragging != null) {
            int newX = Math.max(barX, Math.min(barX + barW, (int)mx));
            dragging.setScreenX(newX);
            dragging.setValue(screenXToData(newX));
            int idx = boundaries.indexOf(dragging);
            if (idx > 0) {
                int prevBound = boundaries.get(idx - 1).getScreenX();
                dragging.setScreenX(Math.max(dragging.getScreenX(), prevBound + 2));
            }
            if (idx < boundaries.size() - 1) {
                int nextBound = boundaries.get(idx + 1).getScreenX();
                dragging.setScreenX(Math.min(dragging.getScreenX(), nextBound - 2));
            }
            // 数据值同步：在屏幕位置被邻居钳制后，重新计算数据值使其与显示位置一致
            dragging.setValue(screenXToData(dragging.getScreenX()));
            return true;
        }
        return false;
    }
    public boolean mouseReleased(double mx, double my, int btn) {
        if (dragging != null) {
            // 最终值同步：确保释放时值对应钳制后的位置
            dragging.setValue(screenXToData(dragging.getScreenX()));
            dragging = null;
            if (onMarkDirty != null) onMarkDirty.run();
            return true;
        }
        return false;
    }

    // ---- 边界线（非静态内部类，可直接访问外层 barY/barH） ----
    private class Boundary extends ClickableRegion {
        private double value;
        private int screenX;
        final String name;
        private DoubleConsumer valueCallback;

        Boundary(double value, String name) { this.value = value; this.name = name; }

        void setValueCallback(DoubleConsumer cb) { this.valueCallback = cb; }
        double getValue() { return value; }
        int getScreenX() { return screenX; }
        void setValue(double v) { this.value = v; if (valueCallback != null) valueCallback.accept(v); }
        void setScreenX(int sx) { this.screenX = sx; }

        @Override public void setPosition(int x, int y) { this.screenX = x; }
        @Override public int getX() { return screenX; }
        @Override public int getY() { return barY + barH / 2; }
        @Override public boolean hitTest(int mx, int my) { return Math.abs(mx - screenX) <= 4 && my >= barY && my <= barY + barH; }
        @Override public void render(GuiGraphics g, int mx, int my, boolean selected) {}
        @Override public void renderTooltip(GuiGraphics g, int mx, int my) {}
    }
}
