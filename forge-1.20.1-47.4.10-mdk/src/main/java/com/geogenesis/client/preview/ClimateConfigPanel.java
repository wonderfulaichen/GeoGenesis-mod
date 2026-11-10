package com.geogenesis.client.preview;

import com.geogenesis.client.ParamSlider;
import com.geogenesis.client.preview.mixer.CategoryBar;
import com.geogenesis.client.preview.mixer.MixerPanel;
import com.geogenesis.config.ConfigSpecRanges;
import com.geogenesis.config.GeoGenesisConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.common.ForgeConfigSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
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
public class ClimateConfigPanel extends ConfigPanel {

    private final List<FactorSection> factors = new ArrayList<>();

    /** 气候缩放区块（纬度/温度/湿度 xz 缩放），独立于分类因素，用普通滑块呈现 */
    private final MixerPanel scalePanel = new MixerPanel("气候缩放");
    private final List<ScaleSpec> scaleSpecs = new ArrayList<>();
    private final List<ParamSlider> scaleSliders = new ArrayList<>();

    private static final int SECTION_GAP = 4;
    private static final int INFLUENCE_W = 64;
    private static final int HEADER_H = 16; // 对齐 MixerPanel.TITLE_H
    private static final int BAR_H = 22;
    private static final int DATA_LABEL_GAP = 12; // dataMin/dataMax 标签占用的下方空间
    private static final int SCALE_ROW_H = UiKit.SLIDER_H;
    private static final int SCALE_LABEL_W = 60;
    private static final int SCALE_VAL_W = 38;

    /** 气候缩放键名（与 GeoGenesisConfig 字段名一致）。 */
    private static final String KEY_LATITUDE = "latitudeScale";
    private static final String KEY_TEMP_WARP = "tempWarpScale";
    private static final String KEY_HUMIDITY = "humidityScale";
    private static final String KEY_REGION = "climateRegionSize";

    public ClimateConfigPanel() {}
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
                new String[]{"深海", "近海", "沿海", "近岸", "近内陆", "内陆", "深内陆"},
                new ForgeConfigSpec.DoubleValue[]{
                    c.continentDeepOceanThreshold, c.continentNearOceanThreshold,
                    c.continentCoastThreshold, c.continentTransitionalThreshold,
                    c.continentNearInlandThreshold, c.continentInlandThreshold
                },
                c.continentInfluence));
        factors.get(factors.size() - 1).influence.setTooltipText(
            "大陆性（距海远近）对生物群系分类的影响权重。注意：此参数不影响海陆面积/海岸线位置（那由参数标签的「海陆偏置」控制）。值越大→大陆性差异对生物群系的影响越显著。");

        // 气候缩放（纬度/温度/湿度 xz 坐标缩放）
        buildScaleSliders(c);
        scalePanel.setContentHeight(scaleSpecs.size() * SCALE_ROW_H + 10);
    }

    /** 构建气候缩放滑块（纬度/温度/湿度/气候区），独立于分类色条 */
    private void buildScaleSliders(GeoGenesisConfig c) {
        scaleSpecs.clear(); scaleSliders.clear();
        // ★ 上下限一律从 defineInRange 现取（旧实现手抄 500/30000 等字面量，
        //   改默认值/范围时会静默漂移 —— 本轮审计抓到 5 处同型漂移）。
        addScale("纬度缩放", KEY_LATITUDE);
        addScale("温度缩放", KEY_TEMP_WARP);
        addScale("湿度缩放", KEY_HUMIDITY);
        // ★ 2026-11-07 新增：气候区尺寸是 A 类真旋钮（BOTH），此前从未上过界面。
        addScale("气候区尺寸", KEY_REGION);
        Function<Double, String> fmt = v -> String.format(Locale.ROOT, "%.0f", v);
        for (ScaleSpec s : scaleSpecs) {
            ParamSlider ps = new ParamSlider(0, 0, 100,
                    s.meta.min(), s.meta.max(),
                    ConfigSpecRanges.getDouble(s.key, s.meta.min()), v -> {
                ConfigSpecRanges.setDouble(s.key, v);
                onMarkDirty.run();
            }, fmt);
            ps.setDefaultValue(parseDefault(s.meta));
            ps.setTooltipText(KeyText.tooltip(s.key));
            scaleSliders.add(ps);
        }
    }

    private static double parseDefault(ConfigSpecRanges.Meta m) {
        try {
            return Double.parseDouble(m.defaultText());
        } catch (NumberFormatException e) {
            return m.min();
        }
    }

    private void addScale(String label, String key) {
        ConfigSpecRanges.Meta meta = ConfigSpecRanges.metaOf(key);
        if (meta == null) {
            // 目录/配置不一致由 ConfigPanelCoverageProbe 报错；此处不造控件也不静默兜底
            throw new IllegalStateException("climate scale key missing from config: " + key);
        }
        scaleSpecs.add(new ScaleSpec(label, key, meta));
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
        // ★ v9：内容高度 = 控制点数值(16) + 色条(BAR_H) + 端点刻度(10)
        //          + 间距(12) + 影响滑块行(22) + 底部间距(4)
        //   旧值没算影响滑块那一行 —— 改成上下两行后必须补上，否则下一组会压上来。
        int contentH = 16 + BAR_H + 10 + 12 + SLIDER_ROW_H + 4;
        panel.setContentHeight(contentH);
        return new FactorSection(title, bar, influence, panel);
    }

    /** 影响滑块行高（★ v14：引用统一常量）。 */
    private static final int SLIDER_ROW_H = UiKit.SLIDER_H;

    public int getHeight() {
        int h = 4;
        for (FactorSection f : factors) h += f.panel.getFullHeight() + SECTION_GAP;
        h += scalePanel.getFullHeight() + SECTION_GAP;
        // ★ v22【修"滚动到底还差一截"】：给底部留一行呼吸空间。
        //   旧实现刚好算到最后一行底边，滚到 max 时最后一项仍被下边界裁掉半个胶囊。
        h += 18;
        return h;
    }

    /**
     * 因子行的水平/垂直布局（★ v9 单一来源，render 与 mouseClicked 共用）。
     *
     * <h2>★ v9 为什么改成上下两行</h2>
     * <p>用户截图指出「2 个小控件并排太挤了」：色条 + 影响滑块 +
     * 两个重置键 + 数值胶囊全塞在同一行，宽度只有约 300px ⇒
     * 色条只剩 3~4 个色块、影响滑块只剩一小截，数值还被迫截断成「1....」。</p>
     * <p>改为：<b>色条独占一行</b>（横向拉伸，阈值点看得清），
     * <b>影响滑块另起一行</b>（配标签 + 吃满剩余宽度）。代价是行高增加约 22px，
     * 换来两个控件都能真正操作。</p>
     *
     * @return {@code {色条宽, 滑块 x, 滑块宽, 色条顶相对偏移, 滑块顶相对偏移}}
     */
    private int[] layoutSection(int contentLeft, int contentW) {
        // ★★ v13【修"色条与重置键超出窗口"】★★
        //   CategoryBar 自己会在 {@code barX + barW + 6} 处画重置键（宽 18）。
        //   上一版 barW = contentW - 2 ⇒ 重置键落在 contentLeft+contentW+4，
        //   正好越过面板右缘 4px（实机截图：窗口边缘被切掉一个角）。
        //   现在从总宽里<b>先扣掉重置键占位</b>，色条与按钮都落在内容区内。
        int resetReserve = 18 + 8;                       // 按钮宽 + 间隙
        int barW = Math.max(60, contentW - resetReserve);
        int sliderX = contentLeft + 56;                  // 让出"影响"标签
        int influenceW = Math.max(60, contentW - 60);
        int barDY = 0;
        int sliderDY = BAR_H + 22;                       // 色条 + 端点刻度 + 间隙
        return new int[]{barW, sliderX, influenceW, barDY, sliderDY};
    }

    /** 影响滑块的标签文案（渲染与布局共用）。 */
    private static final String INFLUENCE_LABEL = "影响";

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

                // ★★ v9【修"2 个小控件并排太挤"】★★
                //   色条独占一行（横向拉伸，阈值点看得清），影响滑块另起一行。
                int[] geo = layoutSection(contentLeft, contentW);
                int barW = geo[0];
                int barY = barTop + geo[3];
                int sliderY = barTop + geo[4];

                // 色条（标题由父 MixerPanel 渲染）
                sec.bar.setBounds(contentLeft, barY, barW, BAR_H);
                sec.bar.render(g, mx, my);
                sec.bar.renderTooltips(g, mx, my);

                // 影响滑块：下一行，左侧带标签
                g.drawString(f, INFLUENCE_LABEL, contentLeft, sliderY + 3,
                        UiKit.C_TXT_SUB, false);
                sec.influence.setX(geo[1]);
                sec.influence.setY(sliderY);
                sec.influence.setWidth(geo[2]);
                sec.influence.render(g, mx, my, 0);
                sec.influence.renderTooltip(g, mx, my);
            }
            sec.panel.renderTooltip(g, mx, my);
            curY += sec.panel.getFullHeight() + SECTION_GAP;
        }

        // 气候缩放区块（普通滑块，无分类色条）
        scalePanel.setBounds(x + 4, curY, w - 8);
        scalePanel.renderHeader(g, mx, my);
        if (!scalePanel.isCollapsed()) {
            int contentLeft = x + 4;
            int slW = w - 16 - SCALE_LABEL_W - SCALE_VAL_W - 8;
            int panelContentY = scalePanel.getY() + HEADER_H; // 标题栏下方
            for (int i = 0; i < scaleSliders.size(); i++) {
                String label = scaleSpecs.get(i).label;
                ParamSlider ps = scaleSliders.get(i);
                int rowY = panelContentY + i * SCALE_ROW_H;
                // ★ v13：悬停底色彻底去掉，改用文字提亮
                boolean hoverRow = mx >= contentLeft && mx < x + w - 8
                        && my >= rowY && my < rowY + SCALE_ROW_H;
                g.drawString(f, UiKit.clip(label, SCALE_LABEL_W - 6), contentLeft + 2,
                        rowY + (SCALE_ROW_H - f.lineHeight) / 2,
                        hoverRow ? UiKit.C_TXT : UiKit.C_TXT_SUB, false);
                int slX = contentLeft + SCALE_LABEL_W;
                ps.setX(slX); ps.setY(rowY + 2); ps.setWidth(slW);
                ps.render(g, mx, my, 0);
            }
        }
        scalePanel.renderTooltip(g, mx, my);
        curY += scalePanel.getFullHeight() + SECTION_GAP;
        for (ParamSlider ps : scaleSliders) ps.renderTooltip(g, mx, my);
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
                // ★ v9：与 render 共用同一份布局（上下两行），不再各算一遍
                int[] geo = layoutSection(contentLeft, contentW);
                int barW = geo[0];
                int barY = barTop + geo[3];
                int sliderY = barTop + geo[4];

                // 影响滑块
                sec.influence.setX(geo[1]);
                sec.influence.setY(sliderY);
                sec.influence.setWidth(geo[2]);
                if (sec.influence.isHoveringReset((int) mx, (int) my)) {
                    sec.influence.resetToDefault();
                    return true;
                }
                if (sec.influence.isMouseOver(mx, my)) {
                    boolean h = sec.influence.mouseClicked(mx, my, btn);
                    if (h) requestValueEdit(sec.influence, "影响系数", onMarkDirty);
                    return h;
                }
                // 色条边界拖拽（CategoryBar 自己的重置按钮在其 mouseClicked 内处理）
                sec.bar.setBounds(contentLeft, barY, barW, BAR_H);
                if (sec.bar.mouseClicked(mx, my, btn)) return true;
            }
            curY += sec.panel.getFullHeight() + SECTION_GAP;
        }

        // 气候缩放区块点击
        scalePanel.setBounds(x + 4, curY, w - 8);
        if (scalePanel.hitTestHeader((int) mx, (int) my)) {
            return scalePanel.mouseClicked(mx, my, btn);
        }
        if (!scalePanel.isCollapsed()) {
            int slW = w - 16 - SCALE_LABEL_W - SCALE_VAL_W - 8;
            int panelContentY = scalePanel.getY() + HEADER_H;
            for (int i = 0; i < scaleSliders.size(); i++) {
                ParamSlider ps = scaleSliders.get(i);
                int rowY = panelContentY + i * SCALE_ROW_H;
                ps.setX(x + 4 + SCALE_LABEL_W); ps.setY(rowY + 2); ps.setWidth(slW);
            }
            for (ParamSlider s : scaleSliders) {
                if (s.isHoveringReset((int) mx, (int) my)) { s.resetToDefault(); return true; }
                if (s.isMouseOver(mx, my)) {
                    boolean h = s.mouseClicked(mx, my, btn);
                    if (h) requestValueEdit(s, "气候缩放", onMarkDirty);
                    return h;
                }
            }
        }
        return false;
    }

    public boolean mouseDragged(double mx, double my, int btn, double dx, double dy) {
        for (FactorSection sec : factors) {
            if (sec.bar.mouseDragged(mx, my, btn, dx, dy)) return true;
            if (sec.influence.isFocused()) { sec.influence.mouseDragged(mx, my, btn, dx, dy); return true; }
        }
        for (ParamSlider s : scaleSliders) if (s.isFocused()) { s.mouseDragged(mx, my, btn, dx, dy); return true; }
        return false;
    }

    public boolean mouseReleased(double mx, double my, int btn) {
        for (FactorSection sec : factors) {
            sec.bar.mouseReleased(mx, my, btn);
            if (sec.influence.isFocused()) sec.influence.mouseReleased(mx, my, btn);
        }
        for (ParamSlider s : scaleSliders) if (s.isFocused()) s.mouseReleased(mx, my, btn);
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

    /** 气候缩放滑块规格（普通滑块，无分类色条）。上下限与默认值全部来自 {@link ConfigSpecRanges}。 */
    private static final class ScaleSpec {
        final String label;
        final String key;
        final ConfigSpecRanges.Meta meta;
        ScaleSpec(String label, String key, ConfigSpecRanges.Meta meta) {
            this.label = label; this.key = key; this.meta = meta;
        }
    }
}
