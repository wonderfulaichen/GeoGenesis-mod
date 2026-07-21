package com.geogenesis.client.preview;

import com.geogenesis.client.ParamSlider;
import com.geogenesis.client.preview.mixer.ControlPoint;
import com.geogenesis.client.preview.mixer.MixerPanel;
import com.geogenesis.client.preview.mixer.ScalePreview;
import com.geogenesis.client.preview.mixer.SnowLineChart;
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
        final String description;
        final ForgeConfigSpec.DoubleValue cfg;
        final double min, max;
        BasicSpec(String label, String description, ForgeConfigSpec.DoubleValue cfg, double min, double max) {
            this.label = label; this.description = description; this.cfg = cfg; this.min = min; this.max = max;
        }
    }
    private final List<BasicSpec> specs = new ArrayList<>();
    private final List<ParamSlider> basicSliders = new ArrayList<>();

    // 图表
    private final WorldHeightBar heightBar = new WorldHeightBar();
    private final SnowLineChart snowChart = new SnowLineChart();
    private final ScalePreview scalePrev = new ScalePreview();

    // 图表关联滑块
    private final List<ParamSlider> heightSliders = new ArrayList<>();
    private final List<ParamSlider> snowSliders = new ArrayList<>();
    private ParamSlider scaleSlider;

    private final MixerPanel heightPanel = new MixerPanel("▸ 世界高度");
    private final MixerPanel snowPanel = new MixerPanel("雪线"); // 折叠图标由 MixerPanel 自己渲染
    private final MixerPanel scalePanel = new MixerPanel("尺度预览");

    private static final int BASIC_ROW_H = 20;
    private static final int BASIC_LABEL_W = 60;
    private static final int BASIC_VAL_W = 38;
    private static final int CHART_SLIDER_H = 20;

    public ParameterConfigPanel() {
        heightBar.setOnMarkDirty(() -> { if (onMarkDirty != null) onMarkDirty.run(); });
        snowChart.setOnMarkDirty(() -> { if (onMarkDirty != null) onMarkDirty.run(); });
        scalePrev.setOnMarkDirty(() -> { if (onMarkDirty != null) onMarkDirty.run(); });
        // 预设内容高度
        // heightPanel: heightBar(100) + gap(16) + 3 sliders(22×2+18=62) = 178 → 180 含底部 padding
        heightPanel.setContentHeight(180);
        // snowPanel: chart(100) + buffer(4) + 3 sliders(22×2+18=62) = 166 → 182
        snowPanel.setContentHeight(182);
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

        // 基础参数（只保留独立功能的参数）
        // 移除：大陆尺度（由尺度预览 HS 参数控制）
        // 移除：雪线海拔、雪线纬度（由雪线图表控制点控制）
        // 移除：省尺度（省系统已被地形类型系统取代，新引擎不读取）
        addSpec("海陆偏置", "大陆性噪声偏置。正值→陆地更多更宽；负值→海洋更多，陆地收缩。影响海陆整体比例。", c.continentBias, -0.6, 0.6);
        addSpec("海床细节", "海床微地形振幅。越大→海床起伏越明显（海山、海沟等特征）。", c.seabedDetail, 0, 0.2);
        addSpec("高海拔阈值", "海拔分类阈值。低于此值为平原/丘陵，高于为高地（山脉+高原+雪峰）。值越低→更多区域划入高地。", c.elevHigh, 0, 1.0);
        addSpec("高起伏阈值", "起伏度分类阈值。低于此值为丘陵，高于为山脉。值越低→更多区域划入山脉类型。", c.reliefHigh, 0, 0.6);
        addSpec("峰阈值", "山峰阈值。高海拔区域中 e 值超过此阈值为 PEAK（雪峰亚型）。值越低→更多山峰。", c.peakE, 0, 1.0);
        for (BasicSpec s : specs) {
            ParamSlider ps = new ParamSlider(0, 0, 100, s.min, s.max, s.cfg.get(), v -> {
                s.cfg.set(v); onChange.accept(v);
            }, fmt);
            ps.setDefaultValue(s.cfg.getDefault());
            ps.setTooltipText(s.description);
            basicSliders.add(ps);
        }

        // 世界高度（支持自定义高度：原版/512/1024等）
        heightBar.setMarks(List.of(
            new WorldHeightBar.Mark("最高点", 0xFFFF4444, () -> c.maxY.get(), v -> { c.maxY.set(v); onChange.accept(0.0); }),
            new WorldHeightBar.Mark("海平面", 0xFF4488FF, () -> c.seaLevel.get(), v -> { c.seaLevel.set(v); onChange.accept(0.0); }),
            new WorldHeightBar.Mark("世界底", 0xFF888888, () -> c.minY.get(), v -> { c.minY.set(v); onChange.accept(0.0); })
        ));
        // 滑块范围：支持自定义高度（-512 到 1024）
        addYSlider(heightSliders, "最高", c.maxY, -512, 1024, c);
        heightSliders.get(heightSliders.size()-1).setTooltipText("世界高度上限（最高点Y坐标）。影响总生成高度范围，决定地形的最高堆叠高度。");
        addYSlider(heightSliders, "海面", c.seaLevel, -512, 1024, c);
        heightSliders.get(heightSliders.size()-1).setTooltipText("海平面Y坐标。e=0 的锚定点，决定海洋与陆地的分界高度，也影响雪线Y轴计算基准。");
        addYSlider(heightSliders, "底", c.minY, -512, 1024, c);
        heightSliders.get(heightSliders.size()-1).setTooltipText("世界高度下限（基岩层Y坐标）。影响总生成高度范围，决定地形的底部基准。");
        // 高度滑块重置时同步刷新 WorldHeightBar
        for (ParamSlider s : heightSliders) {
            s.setOnReset(() -> heightBar.refreshFromConfig());
        }

        // 雪线（X=温度[-1,1]，Y=雪线高度[世界高度Y坐标]）
        // 双曲线：干燥（橙）和湿润（蓝），受温度×湿度共同影响
        int snowMinY = c.minY.get();
        int snowMaxY = c.maxY.get();
        snowChart.setXRange(-1.0, 1.0);
        snowChart.setYRange(snowMinY, snowMaxY);
        snowChart.setSeaLevel(c.seaLevel.get());
        snowChart.setConfigBindings(
            () -> c.snowLine.get(), v -> { c.snowLine.set(v); onChange.accept(0.0); },
            () -> c.snowLatitudeInfluence.get(), v -> { c.snowLatitudeInfluence.set(v); onChange.accept(0.0); },
            () -> c.snowHumidityInfluence.get(), v -> { c.snowHumidityInfluence.set(v); onChange.accept(0.0); }
        );
        final int finalSnowMinY = snowMinY;
        final int finalSnowMaxY = snowMaxY;
        snowChart.setOnPointsChanged(v -> {
            // v = [coldCenterY, warmCenterY, dryColdY, dryWarmY, wetColdY, wetWarmY]
            if (snowSliders.size() >= 6) {
                snowSliders.get(0).setCurrentValue(v[0]); // coldCenter
                snowSliders.get(1).setCurrentValue(v[1]); // warmCenter
                snowSliders.get(2).setCurrentValue(v[2]); // dryCold
                snowSliders.get(3).setCurrentValue(v[3]); // dryWarm
                snowSliders.get(4).setCurrentValue(v[4]); // wetCold
                snowSliders.get(5).setCurrentValue(v[5]); // wetWarm
            }
        });

        // 5 个滑块：冷端中点、暖端中点、干燥冷端、干燥暖端、湿润冷端、湿润暖端
        // 前两个滑块通过 onChange 写配置 + 刷新图表，后四个滑块只显示值不可拖拽（或只读）
        // 实际上我们只需要 3 个驱动滑块（寒带中心、暖带中心、湿度带宽），其余用于显示
        snowSliders.clear();

        // 冷端中点（温度=-1 时的中曲线雪线高度）
        addYSlider(snowSliders, "寒带中心",
            () -> (snowChart.eval(-1.0, 0.0)),
            v -> {
                double warmCenter = snowChart.eval(1.0, 0.0);
                double seaLevelY = c.seaLevel.get();
                double landRange = (double)(finalSnowMaxY - seaLevelY);
                double ratioCold = Math.max(0, Math.min(1, (v - seaLevelY) / landRange));
                double ratioWarm = Math.max(0, Math.min(1, (warmCenter - seaLevelY) / landRange));
                c.snowLine.set((ratioCold + ratioWarm) / 2);
                c.snowLatitudeInfluence.set(Math.max(0, Math.min(0.6, ratioWarm - ratioCold)));
                snowChart.refreshPoints();
                onChange.accept(0.0);
            },
            finalSnowMinY, finalSnowMaxY, v -> String.format("%.0f", v));

        // 暖端中点（温度=+1 时的中曲线雪线高度）
        addYSlider(snowSliders, "暖带中心",
            () -> (snowChart.eval(1.0, 0.0)),
            v -> {
                double coldCenter = snowChart.eval(-1.0, 0.0);
                double seaLevelY = c.seaLevel.get();
                double landRange = (double)(finalSnowMaxY - seaLevelY);
                double ratioCold = Math.max(0, Math.min(1, (coldCenter - seaLevelY) / landRange));
                double ratioWarm = Math.max(0, Math.min(1, (v - seaLevelY) / landRange));
                c.snowLine.set((ratioCold + ratioWarm) / 2);
                c.snowLatitudeInfluence.set(Math.max(0, Math.min(0.6, ratioWarm - ratioCold)));
                snowChart.refreshPoints();
                onChange.accept(0.0);
            },
            finalSnowMinY, finalSnowMaxY, v -> String.format("%.0f", v));

        // 湿度带宽（干燥-湿润曲线之间的高度差）
        addYSlider(snowSliders, "湿度带宽",
            () -> (snowChart.eval(-1.0, -1.0) - snowChart.eval(-1.0, 1.0)),
            v -> {
                double seaLevelY = c.seaLevel.get();
                double landRange = (double)(finalSnowMaxY - seaLevelY);
                double bwRatio = Math.max(0, Math.min(0.5, v / landRange));
                c.snowHumidityInfluence.set(bwRatio);
                snowChart.refreshPoints();
                onChange.accept(0.0);
            },
            0, finalSnowMaxY - finalSnowMinY, v -> String.format("%.0f", v));

        // 后 3 个只读滑块（干燥冷端、干燥暖端、湿润冷端、湿润暖端）用于显示当前控制点高度
        // 添加 3 个补充只读滑块（干燥冷端、湿润冷端、干燥暖端）→ 实际上我们添加所有 6 个
        // 但只让前 3 个可交互，后 3 个只读显示
        double seaLev = c.seaLevel.get();
        double landRange = (double)(finalSnowMaxY - seaLev);
        for (int i = 0; i < 3; i++) {
            snowSliders.get(i).setTooltipText(i == 0 ? "寒带中心（温度=-1，湿度=0 时的雪线高度）" :
                i == 1 ? "暖带中心（温度=+1，湿度=0 时的雪线高度）" :
                "湿度带宽（干燥-湿润曲线之间的高度差）");
            // 默认值：snowLine.getDefault (ratio) → 海平面锚定世界高度
            snowSliders.get(i).setDefaultValue(c.snowLine.getDefault() * landRange + seaLev);
        }
        // 雪线滑块重置时：恢复全部三个配置字段到真默认值（覆盖 onChange 写入的错误值），再刷新图表
        for (ParamSlider s : snowSliders) {
            s.setOnReset(() -> {
                c.snowLine.set(c.snowLine.getDefault());
                c.snowLatitudeInfluence.set(c.snowLatitudeInfluence.getDefault());
                c.snowHumidityInfluence.set(c.snowHumidityInfluence.getDefault());
                snowChart.refreshPoints();
                onChange.accept(0.0);
            });
        }
        snowChart.refreshPoints();

        // 尺度预览（直接拖拽刻度尺标记调整，不需要额外滑块）
        scalePrev.setHorizontalScale(c.continentScale.get() / 1000.0);
        scalePrev.setVerticalScale(c.verticalScale.get());
        scalePrev.setDefaultHorizontalScale(c.continentScale.getDefault() / 1000.0);
        scalePrev.setDefaultVerticalScale(c.verticalScale.getDefault());
        scalePrev.setOnMarkDirty(() -> {
            // 拖拽后更新配置
            c.continentScale.set(scalePrev.getHorizontalScale() * 1000);
            c.verticalScale.set(scalePrev.getVerticalScale());
            onChange.accept(0.0);
        });
    }

    private void addSpec(String label, String description, ForgeConfigSpec.DoubleValue cfg, double min, double max) {
        specs.add(new BasicSpec(label, description, cfg, min, max));
    }

    private void addYSlider(List<ParamSlider> list, String label,
                             ForgeConfigSpec.IntValue cfg, int min, int max,
                             GeoGenesisConfig cc) {
        // 使用 holder 模式解决 lambda 中引用 ps 的初始化顺序问题
        final ParamSlider[] psHolder = new ParamSlider[1];
        psHolder[0] = new ParamSlider(0, 0, 100, min, max, cfg.get(), v -> {
            int val = (int) Math.round(v);
            // 高度顺序约束：最高点 > 海平面 > 世界底
            // 通过 cfg 字段引用识别滑块（避免 size() 在所有滑块添加完成后才取值的 bug）
            if (list == heightSliders) {
                if (cfg == cc.maxY) {
                    // 最高点：必须 > 海平面
                    val = Math.max(cc.seaLevel.get() + 1, val);
                } else if (cfg == cc.seaLevel) {
                    // 海平面：必须 > 世界底 且 < 最高点
                    val = Math.max(cc.minY.get() + 1, Math.min(cc.maxY.get() - 1, val));
                } else if (cfg == cc.minY) {
                    // 世界底：必须 < 海平面
                    val = Math.min(cc.seaLevel.get() - 1, val);
                }
            }
            cfg.set(val);
            // 同步滑块视觉位置到钳制后的值（否则滑块拇指会停在鼠标位置而实际值已被钳制）
            psHolder[0].setCurrentValue(val);
            if (onMarkDirty != null) onMarkDirty.run();
        }, v -> String.format("%d", (int) Math.round(v)));
        psHolder[0].setDefaultValue(cfg.getDefault());
        psHolder[0].setTooltipText(label + "（Y）");
        list.add(psHolder[0]);
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

    // 删除 refreshSnowPoints — SnowLineChart 内部管理控制点

    public int getHeight() {
        int h = 14 + basicSliders.size() * BASIC_ROW_H + 8;
        h += heightPanel.getFullHeight() + 4; // 世界高度 + 尺度预览（已整合）
        h += snowPanel.getFullHeight() + 4;
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
            // 数值文字统一由 ParamSlider 内部渲染（与地形页一致）
            // 滑块与数值之间留出 BASIC_VAL_W 空间供重置按钮使用
        }
        for (ParamSlider ps : basicSliders) ps.renderTooltip(g, mx, my);

        int sectionTop = basicTop + basicSliders.size() * BASIC_ROW_H + 8;

        // 世界高度 + 尺度预览（整合在同一个面板）
        renderSection(g, mx, my, heightPanel, sectionTop, () -> {
            int panelContentY = heightPanel.getY() + 16; // 标题栏下方
            // 左侧：世界高度图（缩小，不占太多空间）
            int heightBarW = w / 2 - 20;
            heightBar.setBounds(x + 4, panelContentY + 8, heightBarW, 100);
            heightBar.render(g, mx, my);
            heightBar.renderTooltips(g, mx, my);
            // 右侧：尺度预览（垂直柱+水平滑块）
            // 调整 y 坐标，使其底部与世界高度图底部对齐
            int scaleStartX = x + heightBarW + 30;
            scalePrev.setBounds(scaleStartX, panelContentY + 4, w - scaleStartX - 4, 100);
            scalePrev.render(g, mx, my);
            // 下方：所有滑块 - 在两个图表下方
            int sy = panelContentY + 116; // 为图表预留足够空间
            int slW = w - 70;
            for (int i = 0; i < heightSliders.size(); i++) {
                ParamSlider ps = heightSliders.get(i);
                ps.setX(x + 12); ps.setY(sy + i * (CHART_SLIDER_H + 2)); ps.setWidth(slW);
                ps.render(g, mx, my, 0);
                ps.renderTooltip(g, mx, my);
            }
        });
        sectionTop += heightPanel.getFullHeight() + 4;

        // 雪线
        renderSection(g, mx, my, snowPanel, sectionTop, () -> {
            snowChart.setBounds(x + 4, snowPanel.getY() + 16, w - 8, 100);
            snowChart.refreshPoints(); // setBounds 后必须刷新控制点位置
            snowChart.render(g, mx, my);
            snowChart.renderTooltips(g, mx, my);
            int sy = snowPanel.getY() + 16 + snowChart.getHeight() + 4;
            // 滑块宽度：面板宽度 - 重置按钮(24) - 数值标签(30) - 间距(8) = 面板宽度 - 62
            int slW = w - 70;
            for (int i = 0; i < snowSliders.size(); i++) {
                ParamSlider ps = snowSliders.get(i);
                ps.setX(x + 12); ps.setY(sy + i * (CHART_SLIDER_H + 2)); ps.setWidth(slW);
                ps.render(g, mx, my, 0);
                ps.renderTooltip(g, mx, my);
            }
        });
        sectionTop += snowPanel.getFullHeight() + 4;

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
        int slX = x + 4 + BASIC_LABEL_W;
        int basicSlW = w - 8 - BASIC_LABEL_W - BASIC_VAL_W - 8;
        for (int i = 0; i < basicSliders.size(); i++) {
            basicSliders.get(i).setX(slX);
            basicSliders.get(i).setY(basicTop + i * BASIC_ROW_H + 2);
            basicSliders.get(i).setWidth(basicSlW);
        }
        for (ParamSlider s : basicSliders) {
            if (s.isHoveringReset((int) mx, (int) my)) { s.resetToDefault(); return true; }
            if (s.isMouseOver(mx, my)) return s.mouseClicked(mx, my, btn);
        }
        // 折叠段
        int curY = basicTop + basicSliders.size() * BASIC_ROW_H + 8;
        if (trySectionClick(mx, my, btn, curY, heightPanel, heightSliders, heightBar, 132)) return true;
        curY += heightPanel.getFullHeight() + 4;
        if (trySectionClick(mx, my, btn, curY, snowPanel, snowSliders, snowChart, 120)) return true;
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
        } else if (chartObj instanceof SnowLineChart) {
            if (((SnowLineChart) chartObj).mouseClicked(mx, my, btn)) return true;
        }
        // 点击尺度预览
        if (chartObj instanceof WorldHeightBar && scalePrev != null) {
            if (scalePrev.mouseClicked(mx, my, btn)) return true;
        }
        // 点击滑块（完整设置位置，与 render 一致）
        int sectionSlW = w - 70;
        for (int i = 0; i < sliders.size(); i++) {
            sliders.get(i).setX(x + 12);
            sliders.get(i).setY(yBase + i * (CHART_SLIDER_H + 2));
            sliders.get(i).setWidth(sectionSlW);
        }
        for (ParamSlider s : sliders) {
            if (s.isHoveringReset((int) mx, (int) my)) { s.resetToDefault(); return true; }
            if (s.isMouseOver(mx, my)) return s.mouseClicked(mx, my, btn);
        }
        // 点击尺度滑块（整合在世界高度面板）
        if (chartObj instanceof WorldHeightBar && scaleSlider != null) {
            scaleSlider.setY(yBase + sliders.size() * (CHART_SLIDER_H + 2));
            if (scaleSlider.isHoveringReset((int) mx, (int) my)) { scaleSlider.resetToDefault(); return true; }
            if (scaleSlider.isMouseOver(mx, my)) return scaleSlider.mouseClicked(mx, my, btn);
        }
        return false;
    }

    public boolean mouseDragged(double mx, double my, int btn, double dx, double dy) {
        for (ParamSlider s : basicSliders) if (s.isFocused()) { s.mouseDragged(mx, my, btn, dx, dy); return true; }
        for (ParamSlider s : heightSliders) if (s.isFocused()) { s.mouseDragged(mx, my, btn, dx, dy); return true; }
        for (ParamSlider s : snowSliders) if (s.isFocused()) { s.mouseDragged(mx, my, btn, dx, dy); return true; }
        if (!heightPanel.isCollapsed()) {
            if (heightBar.mouseDragged(mx, my, btn, dx, dy)) return true;
            if (scalePrev.mouseDragged(mx, my, btn, dx, dy)) return true;
        }
        if (!snowPanel.isCollapsed() && snowChart.mouseDragged(mx, my, btn, dx, dy)) return true;
        return false;
    }

    public boolean mouseReleased(double mx, double my, int btn) {
        for (ParamSlider s : basicSliders) if (s.isFocused()) s.mouseReleased(mx, my, btn);
        for (ParamSlider s : heightSliders) if (s.isFocused()) s.mouseReleased(mx, my, btn);
        for (ParamSlider s : snowSliders) if (s.isFocused()) s.mouseReleased(mx, my, btn);
        if (!heightPanel.isCollapsed()) {
            heightBar.mouseReleased(mx, my, btn);
            scalePrev.mouseReleased(mx, my, btn);
        }
        if (!snowPanel.isCollapsed()) snowChart.mouseReleased(mx, my, btn);
        return false;
    }
}
