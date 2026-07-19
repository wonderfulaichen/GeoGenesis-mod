package com.geogenesis.client.preview;

import com.geogenesis.client.ParamSlider;
import com.geogenesis.client.preview.mixer.CategoryBar;
import com.geogenesis.client.preview.mixer.MixerPanel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.Function;

/**
 * 气候配置面板（气候页签）。
 *
 * <p>每个条件因素用 MixerPanel 包裹，提供：
 * - 标题栏（▸/▾ 折叠切换）
 * - 展开时显示：分类色条 + 边界滑块 + 影响滑块
 *
 * <p>v2 修复：
 * - render 前调用 panel.setBounds(px, curY, pw) 更新 Y 位置
 * - panel.renderHeader() 不再传 baseY 参数（使用 panel 自身 y）
 * - 加每个边界的关联 ParamSlider
 */
public class ClimateConfigPanel {

    private int x, baseY, w;
    private int scrollOffset;
    private final List<FactorSection> factors = new ArrayList<>();
    private Runnable onMarkDirty = () -> {};

    private static final int SECTION_GAP = 4;
    private static final int INFLUENCE_W = 96;
    private static final int BOUNDARY_SLIDER_H = 14;
    private static final int BOUNDARY_SLIDER_W = 60;
    private static final int CONTENT_H = 80; // bar(22) + 4gap + boundary sliders(18) + 4gap + influence(18)

    public ClimateConfigPanel() {}

    public void setOnMarkDirty(Runnable r) { this.onMarkDirty = r; }
    public void setBounds(int x, int y, int w) { this.x = x; this.baseY = y; this.w = w; }
    public void setScrollOffset(int off) { this.scrollOffset = off; }
    private int top() { return baseY - scrollOffset; }

    public void buildClimateFactors() {
        factors.clear();
        factors.add(createFactor("温度",
                new int[]{0xFF3366CC, 0xFF66AADD, 0xFF44BB66, 0xFFFF8833, 0xFFDD3333},
                new String[]{"极寒", "寒冷", "温和", "温暖", "炎热"}));
        factors.add(createFactor("湿度",
                new int[]{0xFFDD8844, 0xFFCCBB44, 0xFF66AA66, 0xFF3366AA},
                new String[]{"干旱", "半干旱", "湿润", "潮湿"}));
        factors.add(createFactor("大陆性",
                new int[]{0xFF66CCFF, 0xFF88AA44, 0xFFCC8844},
                new String[]{"沿海", "过渡", "内陆"}));
    }

    private FactorSection createFactor(String title,
                                        int[] colors, String[] catNames) {
        List<CategoryBar.Category> cats = new ArrayList<>();
        for (int i = 0; i < catNames.length; i++)
            cats.add(new CategoryBar.Category(catNames[i], colors[i]));

        CategoryBar bar = new CategoryBar(title);
        List<DoubleConsumer> setters = new ArrayList<>();
        List<DoubleSupplier> getters = new ArrayList<>();
        double[] arr = new double[catNames.length - 1];
        double base = -1.0, span = 2.0;
        for (int j = 0; j < arr.length; j++) arr[j] = base + (j + 1) * span / catNames.length;
        for (int i = 0; i < catNames.length - 1; i++) {
            final int idx = i;
            setters.add(v -> arr[idx] = v);
            getters.add(() -> arr[idx]);
        }
        List<Double> initThresholds = new ArrayList<>();
        for (int i = 1; i < catNames.length - 1; i++)
            initThresholds.add(base + (i + 1) * span / catNames.length);
        bar.setCategories(cats, initThresholds, setters, getters);
        bar.setOnMarkDirty(onMarkDirty);

        // 每个边界一个 ParamSlider
        Function<Double, String> boundFmt = v -> String.format("%.2f", v);
        List<ParamSlider> boundarySliders = new ArrayList<>();
        for (int i = 0; i < bar.getBoundaryCount(); i++) {
            final int idx = i;
            double initVal = getters.get(idx).getAsDouble();
            ParamSlider ps = new ParamSlider(0, 0, BOUNDARY_SLIDER_W, -1.0, 1.0, initVal, v -> {
                bar.setBoundaryValue(idx, v);
            }, boundFmt);
            ps.setTooltipText(catNames[idx] + "→" + catNames[idx + 1]);
            boundarySliders.add(ps);
        }

        Function<Double, String> fmt = v -> String.format("%.2f", v);
        ParamSlider influence = new ParamSlider(0, 0, INFLUENCE_W, 0.0, 1.0, 0.5, v -> {}, fmt);
        influence.setTooltipText(title + " 对群系分布的影响程度");
        influence.setDefaultValue(0.5);

        MixerPanel panel = new MixerPanel("▸ " + title);
        panel.setContentHeight(CONTENT_H);
        return new FactorSection(title, bar, influence, boundarySliders, panel);
    }

    public int getHeight() {
        int h = 4;
        for (FactorSection f : factors) h += f.panel.getFullHeight() + SECTION_GAP;
        return h;
    }

    public void render(GuiGraphics g, int mx, int my) {
        var f = Minecraft.getInstance().font;
        int ty = top();
        int curY = ty + 4;

        for (FactorSection sec : factors) {
            sec.panel.setBounds(x + 4, curY, w - 8);
            sec.panel.renderHeader(g, mx, my);

            if (!sec.panel.isCollapsed()) {
                int barLeft = x + 10;
                int barTop = curY + 20;
                int barW = w - INFLUENCE_W - 44;
                int barH = 22;
                sec.bar.setBounds(barLeft - 40, barTop - 16, barW + 50, barH + 28);
                sec.bar.render(g, mx, my);
                sec.bar.renderTooltips(g, mx, my);

                // 边界滑块行
                int bslY = barTop + barH + 4;
                int bslCount = sec.boundarySliders.size();
                int bslStep = bslCount > 1 ? Math.min(BOUNDARY_SLIDER_W + 6, (w - 20) / bslCount) : BOUNDARY_SLIDER_W + 6;
                for (int i = 0; i < bslCount; i++) {
                    ParamSlider ps = sec.boundarySliders.get(i);
                    ps.setX(x + 10 + i * bslStep);
                    ps.setY(bslY);
                    ps.setWidth(BOUNDARY_SLIDER_W);
                    ps.render(g, mx, my, 0);
                    g.drawString(f, String.format("%.2f", ps.getCurrentValue()),
                            ps.getX() + BOUNDARY_SLIDER_W + 2, bslY + 2, 0xFF66CCFF);
                }

                // 影响滑块
                sec.influence.setX(barLeft + barW + 8);
                sec.influence.setY(barTop + 22);
                sec.influence.setWidth(INFLUENCE_W);
                sec.influence.render(g, mx, my, 0);
                g.drawString(f, "影响", sec.influence.getX(), barTop + 22 - 12, 0xFF999999);
                g.drawString(f, String.format("%.2f", sec.influence.getCurrentValue()),
                        sec.influence.getX() + INFLUENCE_W - 26, barTop + 22 - 12, 0xFF66CCFF);
                sec.influence.renderTooltip(g, mx, my);
            }
            sec.panel.renderTooltip(g, mx, my);
            curY += sec.panel.getFullHeight() + SECTION_GAP;
        }
    }

    // ---- 鼠标 ----

    public boolean mouseClicked(double mx, double my, int btn) {
        if (btn != 0) return false;
        int ty = top();
        int curY = ty + 4;
        for (FactorSection sec : factors) {
            sec.panel.setBounds(x + 4, curY, w - 8);
            if (sec.panel.hitTestHeader((int) mx, (int) my)) {
                return sec.panel.mouseClicked(mx, my, btn);
            }
            if (!sec.panel.isCollapsed()) {
                int barLeft = x + 10;
                int barTop = curY + 20;
                int barW = w - INFLUENCE_W - 44;
                // 边界滑块
                int bslY = barTop + 22 + 4;
                for (int i = 0; i < sec.boundarySliders.size(); i++) {
                    sec.boundarySliders.get(i).setY(bslY);
                }
                for (ParamSlider s : sec.boundarySliders) {
                    if (s.isMouseOver(mx, my)) return s.mouseClicked(mx, my, btn);
                }
                // 影响滑块
                sec.influence.setY(barTop + 22);
                if (sec.influence.isHoveringReset((int) mx, (int) my)) {
                    sec.influence.resetToDefault();
                    return true;
                }
                if (sec.influence.isMouseOver(mx, my)) return sec.influence.mouseClicked(mx, my, btn);
                // 色条边界拖拽
                if (sec.bar.mouseClicked(mx, my, btn)) return true;
            }
            curY += sec.panel.getFullHeight() + SECTION_GAP;
        }
        return false;
    }

    public boolean mouseDragged(double mx, double my, int btn, double dx, double dy) {
        for (FactorSection sec : factors) {
            if (sec.bar.mouseDragged(mx, my, btn, dx, dy)) return true;
            for (ParamSlider s : sec.boundarySliders) if (s.isFocused()) { s.mouseDragged(mx, my, btn, dx, dy); return true; }
            if (sec.influence.isFocused()) { sec.influence.mouseDragged(mx, my, btn, dx, dy); return true; }
        }
        return false;
    }

    public boolean mouseReleased(double mx, double my, int btn) {
        for (FactorSection sec : factors) {
            sec.bar.mouseReleased(mx, my, btn);
            for (ParamSlider s : sec.boundarySliders) if (s.isFocused()) s.mouseReleased(mx, my, btn);
            if (sec.influence.isFocused()) sec.influence.mouseReleased(mx, my, btn);
        }
        return false;
    }

    // ---- 内部辅助 ----

    private static class FactorSection {
        final String title;
        final CategoryBar bar;
        final ParamSlider influence;
        final List<ParamSlider> boundarySliders;
        final MixerPanel panel;
        FactorSection(String title, CategoryBar bar, ParamSlider influence,
                      List<ParamSlider> boundarySliders, MixerPanel panel) {
            this.title = title; this.bar = bar; this.influence = influence;
            this.boundarySliders = boundarySliders; this.panel = panel;
        }
    }
}
