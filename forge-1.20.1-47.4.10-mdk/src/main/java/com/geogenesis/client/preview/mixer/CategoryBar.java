package com.geogenesis.client.preview.mixer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import com.geogenesis.client.preview.UiKit;

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

    // ★ v16：删除 `dragging` 字段 —— 色条只读后不再有拖拽状态。

    // 重置按钮
    private static final int RESET_BTN_W = 18;
    private static final int RESET_BTN_H = 14;
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
            // ★ v16：Boundary 不再继承 ClickableRegion，无需 setOnMarkDirty
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
    /**
     * 本控件占用的总高度（★ v7：上下各一行文字 —— 上方控制点数值、下方端点刻度）。
     *
     * <p>上方 14px 给控制点数值（barY-14 起），下方 14px 给 -1.0/1.0。
     * 父布局必须用这个值来推进 curY，否则下一个标题会压到端点刻度上。</p>
     */
    public int getHeight() { return 14 + barH + 16; }

    private int dataToScreenX(double v) { return barX + (int)((v - dataMin) / (dataMax - dataMin) * barW); }

    public void render(GuiGraphics g, int mx, int my) {
        int n = categories.size();
        if (n == 0) return;
        var f = Minecraft.getInstance().font;
        // 注意：标题由父容器（MixerPanel/panel）绘制，避免重复
        UiKit.roundRect(g, barX - 1, barY - 1, barX + barW + 1, barY + barH + 1, UiKit.RADIUS_FIELD, 0xFF2A2A2A);
        int prevX = barX;
        for (int i = 0; i < n; i++) {
            int nextX = (i < n - 1) ? boundaries.get(i).getScreenX() : barX + barW;
            g.fill(prevX, barY, nextX, barY + barH, categories.get(i).color());
            prevX = nextX;
        }

        // ★ v7【修"控制点数值叠成一团"（用户截图 -0.60.30）】：
        //   原来每个边界各自在 barY-16 画数值，相距近时必然重叠。
        //   现在先算出所有数值的中心/宽度，交给 UiKit.fitLabels 贪心丢弃
        //   保不下的，宁可少画也不叠。
        int bn = boundaries.size();
        int[] centers = new int[bn];
        int[] textWs = new int[bn];
        String[] texts = new String[bn];
        for (int i = 0; i < bn; i++) {
            Boundary b = boundaries.get(i);
            int bx = b.getScreenX();
            b.hovered = Math.abs(mx - bx) <= 4 && my >= barY && my <= barY + barH;
            texts[i] = String.format("%.2f", b.getValue());
            textWs[i] = f.width(texts[i]);
            centers[i] = bx;
        }
        boolean[] keep = UiKit.fitLabels(centers, textWs, 4);

        // ---- 分界线（★ v16：只读 ⇒ 去掉"可拖"的抓握把手）----
        //   用户裁定示意图不可直接交互。旧版为了提示"可拖"画了上下凸出的
        //   抓握块 + 加粗线，视觉上很吵；现在只留一条<b>细分割线 + 顶端小三角</b>，
        //   表达"这里是一档分界"，不表达"这里能拖"。
        for (int i = 0; i < bn; i++) {
            Boundary b = boundaries.get(i);
            int bx = b.getScreenX();
            boolean hot = b.hovered;
            // 分割线：极细白线（分隔两块颜色，不抢眼）
            g.fill(bx, barY, bx + 1, barY + barH, hot ? 0xEEFFFFFF : 0x99FFFFFF);
            // 顶端小三角/短标：标示阈值位置，不暗示拖拽
            UiKit.roundRect(g, bx - 2, barY - 3, bx + 3, barY, 1,
                    hot ? 0xFFFFFFFF : 0xCCFFFFFF);
        }
        // 数值：只画保留下来的，并夹在色条范围内
        for (int i = 0; i < bn; i++) {
            if (!keep[i]) continue;
            int vx = Math.max(barX, Math.min(centers[i] - textWs[i] / 2, barX + barW - textWs[i]));
            g.drawString(f, texts[i], vx, barY - 13, 0xFFEEEEEE, false);
        }

        // 端点刻度：放在色条<b>下方</b>，与上方控制点数值分属两行，不会互相压
        g.drawString(f, String.format("%.1f", dataMin), barX, barY + barH + 3,
                UiKit.C_TXT_DIM, false);
        String maxStr = String.format("%.1f", dataMax);
        g.drawString(f, maxStr, barX + barW - f.width(maxStr), barY + barH + 3,
                UiKit.C_TXT_DIM, false);

        // 重置按钮（色条右上角）—— 新版：圆角、无描边、ASCII 图标
        int rbX = barX + barW + 6;
        int rbY = barY;
        resetHovered = mx >= rbX && mx <= rbX + RESET_BTN_W && my >= rbY && my <= rbY + RESET_BTN_H;
        UiKit.roundRect(g, rbX, rbY, rbX + RESET_BTN_W, rbY + RESET_BTN_H, 3,
                resetHovered ? 0xFF3E3E3E : 0xFF333333);
        String icon = "R";
        g.drawString(f, icon, rbX + (RESET_BTN_W - f.width(icon)) / 2,
                rbY + (RESET_BTN_H - f.lineHeight) / 2,
                resetHovered ? UiKit.C_ACCENT : UiKit.C_TXT_DIM, false);
    }

    /**
     * 登记悬停提示（★ v15：不再自己绘制 —— 旧版是直角半透明块，另一套外观）。
     */
    public void renderTooltips(GuiGraphics g, int mx, int my) {
        for (Boundary b : boundaries) {
            if (b.hovered) {
                UiKit.Hover.report(b.name + ": " + String.format("%.3f", b.getValue()), mx, my);
            }
        }
    }

    // ---- 鼠标（★ v16：色条改为<b>只读</b>，只有重置按钮仍可点）----
    //
    //   用户裁定：「示意图改成不可直接交互，仅由 UI 控件配置」。
    //   阈值现在只能通过下方的滑块调整；色条本身是<b>输出</b>。
    //   「重置」按钮保留 —— 它是明确的独立操作，不是"拖图改值"。

    public boolean mouseClicked(double mx, double my, int btn) {
        if (btn != 0) return false;
        // 只保留重置按钮
        int rbX = barX + barW + 6;
        int rbY = barY;
        if (mx >= rbX && mx <= rbX + RESET_BTN_W && my >= rbY && my <= rbY + RESET_BTN_H) {
            resetToDefaults();
            return true;
        }
        return false;
    }

    public boolean mouseDragged(double mx, double my, int btn, double dx, double dy) {
        return false;
    }

    public boolean mouseReleased(double mx, double my, int btn) {
        return false;
    }

    /**
     * 一条阈值分界（★ v16：纯数据，不再继承 {@code ClickableRegion}）。
     *
     * <p>色条改为只读后，Boundary 不再需要"可点击/可拖拽/可渲染"的接口，
     * 只保留「值 ↔ 屏幕 x」的换算职责。</p>
     */
    private class Boundary {
        private double value;
        private int screenX;
        final String name;
        private DoubleConsumer valueCallback;
        /** 本帧鼠标是否靠近（仅用于高亮显示，不表示可交互）。 */
        boolean hovered;

        Boundary(double value, String name) { this.value = value; this.name = name; }

        void setValueCallback(DoubleConsumer cb) { this.valueCallback = cb; }
        double getValue() { return value; }
        int getScreenX() { return screenX; }
        void setValue(double v) { this.value = v; if (valueCallback != null) valueCallback.accept(v); }
        void setScreenX(int sx) { this.screenX = sx; }
    }
}
