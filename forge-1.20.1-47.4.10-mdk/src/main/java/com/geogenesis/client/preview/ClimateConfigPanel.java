package com.geogenesis.client.preview;

import com.geogenesis.client.ParamSlider;
import com.geogenesis.client.preview.mixer.CategoryBar;
import com.geogenesis.client.preview.mixer.MixerPanel;
import com.geogenesis.config.GeoGenesisConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.common.ForgeConfigSpec;

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
 * <p>v3 修复：
 * - 所有边界和影响程度滑块绑定到 GeoGenesisConfig
 * - 大陆性从 3 段扩展为 7 段（深海/近海/沿海/过渡/近内陆/内陆/深内陆）
 * - 拖拽色条边界或滑动影响滑块时实时写入配置
 */
public class ClimateConfigPanel {

    private int x, baseY, w;
    private int scrollOffset;
    private final List<FactorSection> factors = new ArrayList<>();
    private Runnable onMarkDirty = () -> {};

    private static final int SECTION_GAP = 4;
    private static final int INFLUENCE_W = 64;
    private static final int HEADER_H = 16; // 对齐 MixerPanel.TITLE_H
    private static final int BAR_H = 22;
    private static final int DATA_LABEL_GAP = 12; // dataMin/dataMax 标签占用的下方空间

    public ClimateConfigPanel() {}

    public void setOnMarkDirty(Runnable r) { this.onMarkDirty = r; }
    public void setBounds(int x, int y, int w) { this.x = x; this.baseY = y; this.w = w; }
    public void setScrollOffset(int off) { this.scrollOffset = off; }
    private int top() { return baseY - scrollOffset; }

    public void buildClimateFactors() {
        GeoGenesisConfig c = GeoGenesisConfig.INSTANCE;
        factors.clear();

        // 温度（5段，4个边界）
        factors.add(createFactor("温度",
                new int[]{0xFF3366CC, 0xFF66AADD, 0xFF44BB66, 0xFFFF8833, 0xFFDD3333},
                new String[]{"极寒", "寒冷", "温和", "温暖", "炎热"},
                new ForgeConfigSpec.DoubleValue[]{
                    c.tempFrozenThreshold, c.tempColdThreshold, c.tempWarmThreshold, c.tempHotThreshold
                },
                c.tempInfluence));
        factors.get(factors.size() - 1).influence.setTooltipText(
            "温度对群系分类和雪线的影响权重。值越大→温度差异对生物群系和雪线高度的影响越显著。");

        // 湿度（4段，3个边界）
        factors.add(createFactor("湿度",
                new int[]{0xFFDD8844, 0xFFCCBB44, 0xFF66AA66, 0xFF3366AA},
                new String[]{"干旱", "半干旱", "湿润", "潮湿"},
                new ForgeConfigSpec.DoubleValue[]{
                    c.humidityDryThreshold, c.humiditySemiThreshold, c.humidityWetThreshold
                },
                c.humidityInfluence));
        factors.get(factors.size() - 1).influence.setTooltipText(
            "湿度对群系分类和雪线的影响权重。值越大→湿度差异对生物群系和雪线高度的影响越显著。");

        // 大陆性（7段，6个边界）
        factors.add(createFactor("大陆性",
                new int[]{0xFF1A4D80, 0xFF3070A0, 0xFF66CCFF, 0xFF88AA44, 0xFFBB8833, 0xFFCC6644, 0xFF884422},
                new String[]{"深海", "近海", "沿海", "过渡", "近内陆", "内陆", "深内陆"},
                new ForgeConfigSpec.DoubleValue[]{
                    c.continentDeepOceanThreshold, c.continentNearOceanThreshold,
                    c.continentCoastThreshold, c.continentTransitionalThreshold,
                    c.continentNearInlandThreshold, c.continentInlandThreshold
                },
                c.continentInfluence));
        factors.get(factors.size() - 1).influence.setTooltipText(
            "大陆性（距海远近）对群系分类的影响权重。值越大→大陆性差异对生物群系的影响越显著。");
    }

    private FactorSection createFactor(String title,
                                        int[] colors, String[] catNames,
                                        ForgeConfigSpec.DoubleValue[] thresholdCfgs,
                                        ForgeConfigSpec.DoubleValue influenceCfg) {
        List<CategoryBar.Category> cats = new ArrayList<>();
        for (int i = 0; i < catNames.length; i++)
            cats.add(new CategoryBar.Category(catNames[i], colors[i]));

        CategoryBar bar = new CategoryBar(title);
        List<DoubleConsumer> setters = new ArrayList<>();
        List<DoubleSupplier> getters = new ArrayList<>();
        for (int i = 0; i < thresholdCfgs.length; i++) {
            ForgeConfigSpec.DoubleValue cfg = thresholdCfgs[i];
            setters.add(cfg::set);
            getters.add(cfg::get);
        }
        List<Double> initThresholds = new ArrayList<>();
        for (ForgeConfigSpec.DoubleValue cfg : thresholdCfgs)
            initThresholds.add(cfg.get());
        bar.setCategories(cats, initThresholds, setters, getters);
        bar.setOnMarkDirty(onMarkDirty);
        
        // 设置默认阈值（从配置 getDefault() 获取）
        List<Double> defaultThresholds = new ArrayList<>();
        for (ForgeConfigSpec.DoubleValue cfg : thresholdCfgs)
            defaultThresholds.add(cfg.getDefault());
        bar.setDefaultThresholds(defaultThresholds);

        Function<Double, String> fmt = v -> String.format("%.2f", v);
        ParamSlider influence = new ParamSlider(0, 0, INFLUENCE_W, 0.0, 1.0, influenceCfg.get(),
                v -> { influenceCfg.set(v); onMarkDirty.run(); }, fmt);
        influence.setDefaultValue(influenceCfg.getDefault());

        MixerPanel panel = new MixerPanel(title); // 折叠图标由 MixerPanel 自己渲染
        // 内容高度 = 控制点数值空间(16) + 色条(BAR_H=22) + dataMin标签(DATA_LABEL_GAP=12) + 底部间距(4)
        int contentH = 16 + BAR_H + DATA_LABEL_GAP + 4;
        panel.setContentHeight(contentH);
        return new FactorSection(title, bar, influence, panel);
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
                int contentLeft = x + 8;
                int contentW = w - 16;
                // 控制点数值在 barY - 16 位置，必须在标题栏下方
                // 标题栏占用 TITLE_H=16，所以 barY >= curY + 16 + 16 = curY + 32
                int barTop = curY + HEADER_H + 16; // 标题下方留16像素间距（控制点数值空间）

                // 布局：色条 | 色条重置键 | 影响滑块 | 影响滑块重置键 | 数值标签
                // CategoryBar 自身会在 barX+barW+6 处绘制重置按钮（RESET_BTN_W=18）
                // 所以影响滑块必须放在色条重置按钮之后，避免重叠
                int categoryResetArea = ParamSlider.RESET_BTN_W + ParamSlider.RESET_GAP; // 色条重置按钮占宽 24
                int sliderAreaW = 64 + ParamSlider.RESET_BTN_W + ParamSlider.RESET_GAP + 6; // 滑块+自身重置键
                int influenceAreaW = categoryResetArea + sliderAreaW + 24; // 24 是数值标签余量
                int barW = contentW - influenceAreaW - 6; // 留6像素间距

                // 色条（标题由父 MixerPanel 渲染）
                sec.bar.setBounds(contentLeft, barTop, barW, BAR_H);
                sec.bar.render(g, mx, my);
                sec.bar.renderTooltips(g, mx, my);

                // 影响滑块放在色条重置按钮右侧
                int sliderX = contentLeft + barW + 6 + categoryResetArea;
                sec.influence.setX(sliderX);
                sec.influence.setY(barTop);
                sec.influence.setWidth(64);
                sec.influence.render(g, mx, my, 0);
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
                int contentLeft = x + 8;
                int contentW = w - 16;
                int barTop = curY + HEADER_H + 16; // 标题下方留16像素间距（控制点数值空间）
                // 与 render 一致的布局计算
                int categoryResetArea = ParamSlider.RESET_BTN_W + ParamSlider.RESET_GAP;
                int sliderAreaW = 64 + ParamSlider.RESET_BTN_W + ParamSlider.RESET_GAP + 6;
                int influenceAreaW = categoryResetArea + sliderAreaW + 24;
                int barW = contentW - influenceAreaW - 6;
                int sliderX = contentLeft + barW + 6 + categoryResetArea;

                // 影响滑块
                sec.influence.setX(sliderX);
                sec.influence.setY(barTop);
                sec.influence.setWidth(64);
                if (sec.influence.isHoveringReset((int) mx, (int) my)) {
                    sec.influence.resetToDefault();
                    return true;
                }
                if (sec.influence.isMouseOver(mx, my)) return sec.influence.mouseClicked(mx, my, btn);
                // 色条边界拖拽（CategoryBar 自己的重置按钮在其 mouseClicked 内处理）
                sec.bar.setBounds(contentLeft, barTop, barW, BAR_H);
                if (sec.bar.mouseClicked(mx, my, btn)) return true;
            }
            curY += sec.panel.getFullHeight() + SECTION_GAP;
        }
        return false;
    }

    public boolean mouseDragged(double mx, double my, int btn, double dx, double dy) {
        for (FactorSection sec : factors) {
            if (sec.bar.mouseDragged(mx, my, btn, dx, dy)) return true;
            if (sec.influence.isFocused()) { sec.influence.mouseDragged(mx, my, btn, dx, dy); return true; }
        }
        return false;
    }

    public boolean mouseReleased(double mx, double my, int btn) {
        for (FactorSection sec : factors) {
            sec.bar.mouseReleased(mx, my, btn);
            if (sec.influence.isFocused()) sec.influence.mouseReleased(mx, my, btn);
        }
        return false;
    }

    // ---- 内部辅助 ----

    private static class FactorSection {
        final String title;
        final CategoryBar bar;
        final ParamSlider influence;
        final MixerPanel panel;
        FactorSection(String title, CategoryBar bar, ParamSlider influence, MixerPanel panel) {
            this.title = title; this.bar = bar; this.influence = influence; this.panel = panel;
        }
    }
}
