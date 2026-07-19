package com.geogenesis.client.preview;

import com.geogenesis.client.ParamSlider;
import com.geogenesis.client.preview.mixer.ControlPoint;
import com.geogenesis.client.preview.mixer.MixerPanel;
import com.geogenesis.client.preview.mixer.ScalePreview;
import com.geogenesis.client.preview.mixer.SingleCurveChart;
import com.geogenesis.client.preview.mixer.WorldHeightBar;
import com.geogenesis.config.GeoGenesisConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.common.ForgeConfigSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 参数配置面板（参数页签）。
 *
 * <p>vv2 修复：
 * - MixerPanel 在每次 render/mouse 前先 setBounds(x, curY, w) → 折叠点击可用
 * - getHeight 不再 *2（修复多余空白）
 * - renderSection 不再传 baseY，panel.renderHeader() 用自身 y
 * - 鼠标事件：统一用 curY 迭代，与 render 的布局逻辑完全一致
 */
public class ParameterConfigPanel {

    private int x, baseY, w;
    private int scrollOffset;
    private Runnable onMarkDirty = () -> {};

    private static class BasicSpec {
        final String label;
        final ForgeConfigSpec.DoubleValue cfg;
        final double min, max;
        BasicSpec(String label, ForgeConfigSpec.DoubleValue cfg, double min, double max) {
            this.label = label; this.cfg = cfg; this.min = min; this.max = max;
        }
    }
    private final List<BasicSpec> specs = new ArrayList<>();
    private final List<ParamSlider> basicSliders = new ArrayList<>();

    // 图表
    private final WorldHeightBar heightBar = new WorldHeightBar();
    private final SingleCurveChart snowChart = new SingleCurveChart("雪线");
    private final ScalePreview scalePrev = new ScalePreview();

    // 图表关联滑块
    private final List<ParamSlider> heightSliders = new ArrayList<>();
    private final List<ParamSlider> snowSliders = new ArrayList<>();
    private ParamSlider scaleSlider;

    private final MixerPanel heightPanel = new MixerPanel("▸ 世界高度");
    private final MixerPanel snowPanel = new MixerPanel("▸ 雪线");
    private final MixerPanel scalePanel = new MixerPanel("▸ 尺度预览");

    private static final int BASIC_ROW_H = 20;
    private static final int BASIC_LABEL_W = 60;
    private static final int BASIC_VAL_W = 38;
    private static final int CHART_SLIDER_H = 20;

    public ParameterConfigPanel() {
        heightBar.setOnMarkDirty(() -> { if (onMarkDirty != null) onMarkDirty.run(); });
        snowChart.setOnMarkDirty(() -> { if (onMarkDirty != null) onMarkDirty.run(); });
        scalePrev.setOnMarkDirty(() -> { if (onMarkDirty != null) onMarkDirty.run(); });
        // 预设内容高度
        heightPanel.setContentHeight(140 + CHART_SLIDER_H * 3);
        snowPanel.setContentHeight(110 + CHART_SLIDER_H * 2);
        scalePanel.setContentHeight(110 + CHART_SLIDER_H);
    }

    public void setOnMarkDirty(Runnable r) { this.onMarkDirty = r; }
    public void setBounds(int x, int y, int w) { this.x = x; this.baseY = y; this.w = w; }
    public void setScrollOffset(int off) { this.scrollOffset = off; }
    private int top() { return baseY - scrollOffset; }

    public void buildFromConfig() {
        GeoGenesisConfig c = GeoGenesisConfig.INSTANCE;
        Consumer<Double> onChange = v -> { if (onMarkDirty != null) onMarkDirty.run(); };
        Function<Double, String> fmt = v -> String.format("%.3f", v);
        specs.clear(); basicSliders.clear();
        heightSliders.clear(); snowSliders.clear();

        // 基础参数
        addSpec("大陆尺度", c.continentScale, 200, 10000);
        addSpec("海陆偏置", c.continentBias, -0.6, 0.6);
        addSpec("海床细节", c.seabedDetail, 0, 0.2);
        addSpec("高海拔阈值", c.elevHigh, 0, 1.0);
        addSpec("高起伏阈值", c.reliefHigh, 0, 0.6);
        addSpec("峰阈值", c.peakE, 0, 1.0);
        addSpec("雪线海拔", c.snowLine, 0, 1.0);
        addSpec("雪线纬度", c.snowLatitudeInfluence, 0, 0.6);
        addSpec("省尺度", c.provinceScale, 500, 10000);
        for (BasicSpec s : specs) {
            ParamSlider ps = new ParamSlider(0, 0, 100, s.min, s.max, s.cfg.get(), v -> {
                s.cfg.set(v); onChange.accept(v);
            }, fmt);
            ps.setTooltipText(s.label);
            basicSliders.add(ps);
        }

        // 世界高度
        heightBar.setMarks(List.of(
            new WorldHeightBar.Mark("最高点", 0xFFFF4444, () -> c.maxY.get(), v -> { c.maxY.set(v); onChange.accept(0.0); }),
            new WorldHeightBar.Mark("海平面", 0xFF4488FF, () -> c.seaLevel.get(), v -> { c.seaLevel.set(v); onChange.accept(0.0); }),
            new WorldHeightBar.Mark("世界底", 0xFF888888, () -> c.minY.get(), v -> { c.minY.set(v); onChange.accept(0.0); })
        ));
        addYSlider(heightSliders, "最高", c.maxY, 200, 400);
        addYSlider(heightSliders, "海面", c.seaLevel, 0, 200);
        addYSlider(heightSliders, "底", c.minY, -200, 0);

        // 雪线
        snowChart.setXRange(-1.0, 1.0);
        snowChart.setYRange(0.0, 1.0);
        snowChart.setCurveFn(temp -> {
            double base = c.snowLine.get();
            double latInf = c.snowLatitudeInfluence.get();
            double tmp = (temp + 1.0) / 2.0;
            return Math.max(0.05, Math.min(1.0, base + (tmp - 0.5) * latInf));
        });
        List<ControlPoint> snowPoints = List.of(
            new ControlPoint(0, 0, 0xFF66CCFF, "冷端").setShape(ControlPoint.Shape.SQUARE).setSize(6),
            new ControlPoint(0, 0, 0xFFFF8844, "暖端").setShape(ControlPoint.Shape.SQUARE).setSize(6)
        );
        snowChart.setPoints(snowPoints);
        addYSlider(snowSliders, "冷端", () -> snowChart.eval(-1.0),
                v -> { double w = snowChart.eval(1.0);
                       c.snowLine.set((v + w) / 2);
                       c.snowLatitudeInfluence.set(Math.max(0, Math.min(0.6, w - v)));
                       refreshSnowPoints(); onChange.accept(0.0); },
                0.0, 1.0, fmt);
        addYSlider(snowSliders, "暖端", () -> snowChart.eval(1.0),
                v -> { double c0 = snowChart.eval(-1.0);
                       c.snowLine.set((c0 + v) / 2);
                       c.snowLatitudeInfluence.set(Math.max(0, Math.min(0.6, v - c0)));
                       refreshSnowPoints(); onChange.accept(0.0); },
                0.0, 1.0, fmt);
        refreshSnowPoints();

        // 尺度预览
        scalePrev.setHorizontalScale(c.continentScale.get() / 1000.0);
        scaleSlider = new ParamSlider(0, 0, 100, 0.5, 20.0, scalePrev.getHorizontalScale(),
                v -> { scalePrev.setHorizontalScale(v); onChange.accept(0.0); },
                v -> String.format("%.1f", v));
        scaleSlider.setTooltipText("水平采样尺度");
    }

    private void addSpec(String label, ForgeConfigSpec.DoubleValue cfg, double min, double max) {
        specs.add(new BasicSpec(label, cfg, min, max));
    }

    private void addYSlider(List<ParamSlider> list, String label,
                             ForgeConfigSpec.IntValue cfg, int min, int max) {
        ParamSlider ps = new ParamSlider(0, 0, 100, min, max, cfg.get(), v -> {
            cfg.set((int) Math.round(v)); if (onMarkDirty != null) onMarkDirty.run();
        }, v -> String.format("%d", (int) Math.round(v)));
        ps.setTooltipText(label + "（Y）");
        list.add(ps);
    }

    private void addYSlider(List<ParamSlider> list, String label,
                             java.util.function.DoubleSupplier getter,
                             java.util.function.DoubleConsumer setter,
                             double min, double max, Function<Double, String> fmt) {
        ParamSlider ps = new ParamSlider(0, 0, 100, min, max, getter.getAsDouble(), v -> {
            setter.accept(v); if (onMarkDirty != null) onMarkDirty.run();
        }, fmt);
        ps.setTooltipText(label);
        list.add(ps);
    }

    private void refreshSnowPoints() {
        snowChart.refreshPoints(idx -> {
            double x = (idx == 0) ? -1.0 : 1.0;
            return new double[]{x, snowChart.eval(x)};
        });
    }

    public int getHeight() {
        int h = 14 + basicSliders.size() * BASIC_ROW_H + 8;
        h += heightPanel.getFullHeight() + 4;
        h += snowPanel.getFullHeight() + 4;
        h += scalePanel.getFullHeight() + 4;
        return h;
    }

    // ===== 渲染 =====

    public void render(GuiGraphics g, int mx, int my) {
        var f = Minecraft.getInstance().font;
        int ty = top();

        g.drawString(f, "▸ 基础参数", x + 6, ty + 4, 0xFF66CCFF);
        int basicTop = ty + 18;
        for (int i = 0; i < basicSliders.size(); i++) {
            BasicSpec s = specs.get(i);
            ParamSlider ps = basicSliders.get(i);
            int rowY = basicTop + i * BASIC_ROW_H;
            if (i % 2 == 0) g.fill(x + 4, rowY, x + w - 8, rowY + BASIC_ROW_H, 0x12FFFFFF);
            g.drawString(f, s.label, x + 6, rowY + (BASIC_ROW_H - 8) / 2, 0xFFCCCCCC);
            int slX = x + 4 + BASIC_LABEL_W;
            int slW = w - 8 - BASIC_LABEL_W - BASIC_VAL_W - 8;
            ps.setX(slX); ps.setY(rowY + 2); ps.setWidth(slW);
            ps.render(g, mx, my, 0);
            g.drawString(f, String.format("%.3f", ps.getCurrentValue()), slX + slW + 4, rowY + (BASIC_ROW_H - 8) / 2, 0xFF66CCFF);
        }
        for (ParamSlider ps : basicSliders) ps.renderTooltip(g, mx, my);

        int sectionTop = basicTop + basicSliders.size() * BASIC_ROW_H + 8;

        // 世界高度
        renderSection(g, mx, my, heightPanel, sectionTop, () -> {
            heightBar.setBounds(x + 4, heightPanel.getY(), w - 8, 130);
            heightBar.render(g, mx, my);
            heightBar.renderTooltips(g, mx, my);
            int sy = heightPanel.getY() + 132;
            for (int i = 0; i < heightSliders.size(); i++) {
                ParamSlider ps = heightSliders.get(i);
                int slW = w - 24;
                ps.setX(x + 12); ps.setY(sy + i * (CHART_SLIDER_H + 2)); ps.setWidth(slW);
                ps.render(g, mx, my, 0);
                ps.renderTooltip(g, mx, my);
            }
        });
        sectionTop += heightPanel.getFullHeight() + 4;

        // 雪线
        renderSection(g, mx, my, snowPanel, sectionTop, () -> {
            snowChart.setBounds(x + 4, snowPanel.getY(), w - 8, 100);
            snowChart.render(g, mx, my);
            snowChart.renderTooltips(g, mx, my);
            int sy = snowPanel.getY() + 104;
            for (int i = 0; i < snowSliders.size(); i++) {
                ParamSlider ps = snowSliders.get(i);
                int slW = w - 24;
                ps.setX(x + 12); ps.setY(sy + i * (CHART_SLIDER_H + 2)); ps.setWidth(slW);
                ps.render(g, mx, my, 0);
                ps.renderTooltip(g, mx, my);
            }
        });
        sectionTop += snowPanel.getFullHeight() + 4;

        // 尺度预览
        renderSection(g, mx, my, scalePanel, sectionTop, () -> {
            scalePrev.setBounds(x + 4, scalePanel.getY(), w - 8, 100);
            scalePrev.render(g, mx, my);
            int sy = scalePanel.getY() + 102;
            int slW = w - 24;
            scaleSlider.setX(x + 12); scaleSlider.setY(sy); scaleSlider.setWidth(slW);
            scaleSlider.render(g, mx, my, 0);
            scaleSlider.renderTooltip(g, mx, my);
        });
    }

    /** 渲染混合器面板段：先设 bounds 再渲染 */
    private void renderSection(GuiGraphics g, int mx, int my, MixerPanel panel, int py,
                              Runnable contentRenderer) {
        panel.setBounds(x + 4, py, w - 8);
        panel.renderHeader(g, mx, my);
        if (!panel.isCollapsed()) {
            contentRenderer.run();
        }
        panel.renderTooltip(g, mx, my);
    }

    // ===== 鼠标 =====

    public boolean mouseClicked(double mx, double my, int btn) {
        if (btn != 0) return false;
        int ty = top();
        // 基础滑块
        int basicTop = ty + 18;
        for (int i = 0; i < basicSliders.size(); i++) {
            basicSliders.get(i).setY(basicTop + i * BASIC_ROW_H + 2);
        }
        for (ParamSlider s : basicSliders) {
            if (s.isHoveringReset((int) mx, (int) my)) { s.resetToDefault(); return true; }
            if (s.isMouseOver(mx, my)) return s.mouseClicked(mx, my, btn);
        }
        // 折叠段
        int curY = basicTop + basicSliders.size() * BASIC_ROW_H + 8;
        if (trySectionClick(mx, my, btn, curY, heightPanel, heightSliders, heightBar, 132)) return true;
        curY += heightPanel.getFullHeight() + 4;
        if (trySectionClick(mx, my, btn, curY, snowPanel, snowSliders, snowChart, 104)) return true;
        curY += snowPanel.getFullHeight() + 4;
        if (tryScaleClick(mx, my, btn, curY)) return true;
        return false;
    }

    /** 统一折叠段点击处理：先 setBounds，再检测标题/图表/滑块 */
    private boolean trySectionClick(double mx, double my, int btn, int sectionTop,
                                     MixerPanel panel, List<ParamSlider> sliders,
                                     Object chartObj, int chartContentTop) {
        panel.setBounds(x + 4, sectionTop, w - 8);
        if (panel.hitTestHeader((int) mx, (int) my)) return panel.mouseClicked(mx, my, btn);
        if (panel.isCollapsed()) return false;

        int yBase = panel.getY() + chartContentTop;
        // 点击图表
        if (chartObj instanceof WorldHeightBar) {
            if (((WorldHeightBar) chartObj).mouseClicked(mx, my, btn)) return true;
        } else if (chartObj instanceof SingleCurveChart) {
            if (((SingleCurveChart) chartObj).mouseClicked(mx, my, btn)) return true;
        }
        // 点击滑块
        for (int i = 0; i < sliders.size(); i++) {
            sliders.get(i).setY(yBase + i * (CHART_SLIDER_H + 2));
        }
        for (ParamSlider s : sliders) {
            if (s.isHoveringReset((int) mx, (int) my)) { s.resetToDefault(); return true; }
            if (s.isMouseOver(mx, my)) return s.mouseClicked(mx, my, btn);
        }
        return false;
    }

    private boolean tryScaleClick(double mx, double my, int btn, int sectionTop) {
        scalePanel.setBounds(x + 4, sectionTop, w - 8);
        if (scalePanel.hitTestHeader((int) mx, (int) my)) return scalePanel.mouseClicked(mx, my, btn);
        if (scalePanel.isCollapsed()) return false;
        int yBase = scalePanel.getY() + 102;
        scaleSlider.setY(yBase);
        if (scaleSlider.isHoveringReset((int) mx, (int) my)) { scaleSlider.resetToDefault(); return true; }
        if (scaleSlider.isMouseOver(mx, my)) return scaleSlider.mouseClicked(mx, my, btn);
        return false;
    }

    public boolean mouseDragged(double mx, double my, int btn, double dx, double dy) {
        for (ParamSlider s : basicSliders) if (s.isFocused()) { s.mouseDragged(mx, my, btn, dx, dy); return true; }
        for (ParamSlider s : heightSliders) if (s.isFocused()) { s.mouseDragged(mx, my, btn, dx, dy); return true; }
        for (ParamSlider s : snowSliders) if (s.isFocused()) { s.mouseDragged(mx, my, btn, dx, dy); return true; }
        if (scaleSlider != null && scaleSlider.isFocused()) { scaleSlider.mouseDragged(mx, my, btn, dx, dy); return true; }
        if (!heightPanel.isCollapsed() && heightBar.mouseDragged(mx, my, btn, dx, dy)) return true;
        if (!snowPanel.isCollapsed() && snowChart.mouseDragged(mx, my, btn, dx, dy)) return true;
        return false;
    }

    public boolean mouseReleased(double mx, double my, int btn) {
        for (ParamSlider s : basicSliders) if (s.isFocused()) s.mouseReleased(mx, my, btn);
        for (ParamSlider s : heightSliders) if (s.isFocused()) s.mouseReleased(mx, my, btn);
        for (ParamSlider s : snowSliders) if (s.isFocused()) s.mouseReleased(mx, my, btn);
        if (scaleSlider != null && scaleSlider.isFocused()) scaleSlider.mouseReleased(mx, my, btn);
        if (!heightPanel.isCollapsed()) heightBar.mouseReleased(mx, my, btn);
        if (!snowPanel.isCollapsed()) snowChart.mouseReleased(mx, my, btn);
        return false;
    }
}
