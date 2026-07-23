package com.geogenesis.client.preview;

import com.geogenesis.client.ParamSlider;
import com.geogenesis.client.preview.mixer.MixerPanel;
import com.geogenesis.client.preview.mixer.ScalePreview;
import com.geogenesis.client.preview.mixer.WorldHeightBar;
import com.geogenesis.config.GeoGenesisConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.common.ForgeConfigSpec;

import java.util.ArrayList;
import java.util.Arrays;
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
public class ParameterConfigPanel extends ConfigPanel {

    /** 确认对话框回调：由 GeoGenesisConfigScreen 注入，重要参数松手时触发 */
    @FunctionalInterface
    public interface ShowConfirmCallback {
        void show(String title, String message, List<String> affected, Runnable onCancel, Runnable onConfirm);
    }
    private ShowConfirmCallback onShowConfirm = null;
    public void setOnShowConfirm(ShowConfirmCallback cb) { this.onShowConfirm = cb; }

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
    private final ScalePreview scalePrev = new ScalePreview();

    // 图表关联滑块
    private final List<ParamSlider> heightSliders = new ArrayList<>();
    private ParamSlider scaleSlider;

    private final MixerPanel heightPanel = new MixerPanel("世界高度");
    private final MixerPanel scalePanel = new MixerPanel("尺度预览");

    private static final int BASIC_ROW_H = 20;
    private static final int BASIC_LABEL_W = 60;
    private static final int BASIC_VAL_W = 38;
    private static final int CHART_SLIDER_H = 20;

    public ParameterConfigPanel() {
        heightBar.setOnMarkDirty(() -> { if (onMarkDirty != null) onMarkDirty.run(); });
        scalePrev.setOnMarkDirty(() -> { if (onMarkDirty != null) onMarkDirty.run(); });
        // 预设内容高度
        // heightPanel: heightBar(100) + gap(16) + 3 sliders(22×2+18=62) = 178 → 180 含底部 padding
        heightPanel.setContentHeight(180);
        scalePanel.setContentHeight(110 + CHART_SLIDER_H);
    }

    public void buildFromConfig() {
        GeoGenesisConfig c = GeoGenesisConfig.INSTANCE;
        Consumer<Double> onChange = v -> { if (onMarkDirty != null) onMarkDirty.run(); };
        Function<Double, String> fmt = v -> String.format("%.3f", v);
        specs.clear(); basicSliders.clear();
        heightSliders.clear();

        // 基础参数（只保留独立功能的参数）
        addSpec("海陆偏置", "大陆性噪声偏置：正值→海洋面积增加（陆地减少），负值→陆地面积增加（海洋减少）。影响海陆整体比例。公式：cBiased = c - bias，偏置越大海洋越多。", c.continentBias, -0.6, 1.5);
        addSpec("海洋深度乘数", "海洋深度缩放因子。>1→海洋更深（海洋面积扩大），<1→海洋更浅（陆地面积扩大）。直接控制海陆面积比例。默认1.0。", c.oceanDepthFactor, 0.5, 3.0);
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
        // 重要标记：世界高度滑块松手时触发确认框
        addYSlider(heightSliders, "最高", c.maxY, -512, 1024, c, true,
            "雪线Y区间", "地形图高度映射", "尺度预览范围");
        heightSliders.get(heightSliders.size()-1).setTooltipText("世界高度上限（最高点Y坐标）。影响总生成高度范围，决定地形的最高堆叠高度。");
        addYSlider(heightSliders, "海面", c.seaLevel, -512, 1024, c, true,
            "雪线Y基准", "地形图高度映射");
        heightSliders.get(heightSliders.size()-1).setTooltipText("海平面Y坐标。e=0 的锚定点，决定海洋与陆地的分界高度，也影响雪线Y轴计算基准。");
        addYSlider(heightSliders, "底", c.minY, -512, 1024, c, true,
            "雪线Y区间", "地形图高度映射");
        heightSliders.get(heightSliders.size()-1).setTooltipText("世界高度下限（基岩层Y坐标）。影响总生成高度范围，决定地形的底部基准。");
        // 高度滑块重置时同步刷新 WorldHeightBar
        for (ParamSlider s : heightSliders) {
            s.setOnReset(() -> heightBar.refreshFromConfig());
        }

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

    /** 世界高度 Y 滑块，可选是否显示确认对话框 */
    private void addYSlider(List<ParamSlider> list, String label,
                             ForgeConfigSpec.IntValue cfg, int min, int max,
                             GeoGenesisConfig cc, boolean important,
                             String... affectedItems) {
        // 使用 holder 模式解决 lambda 中引用 ps 的初始化顺序问题
        final ParamSlider[] psHolder = new ParamSlider[1];
        final int[] beforeValue = { cfg.get() }; // 确认回滚用
        psHolder[0] = new ParamSlider(0, 0, 100, min, max, cfg.get(), v -> {
            int val = (int) Math.round(v);
            // 高度顺序约束：最高点 > 海平面 > 世界底
            if (list == heightSliders) {
                if (cfg == cc.maxY) {
                    val = Math.max(cc.seaLevel.get() + 1, val);
                } else if (cfg == cc.seaLevel) {
                    val = Math.max(cc.minY.get() + 1, Math.min(cc.maxY.get() - 1, val));
                } else if (cfg == cc.minY) {
                    val = Math.min(cc.seaLevel.get() - 1, val);
                }
            }
            cfg.set(val);
            psHolder[0].setCurrentValue(val);
            if (onMarkDirty != null) onMarkDirty.run();
        }, v -> String.format("%d", (int) Math.round(v)));
        psHolder[0].setDefaultValue(cfg.getDefault());
        psHolder[0].setTooltipText(label + "（Y）");
        // 重要滑块：松手时触发确认框
        if (important && onShowConfirm != null) {
            psHolder[0].setOnDragStart(() -> { beforeValue[0] = cfg.get(); });
            psHolder[0].setOnValueCommitted(() -> {
                int newVal = cfg.get();
                if (newVal != beforeValue[0]) {
                    final int rollbackVal = beforeValue[0];
                    List<String> affected = Arrays.asList(affectedItems);
                    onShowConfirm.show(label,
                        beforeValue[0] + " → " + newVal,
                        affected,
                        () -> { // onCancel: 回滚到旧值
                            cfg.set(rollbackVal);
                            psHolder[0].setCurrentValue(rollbackVal);
                            if (onMarkDirty != null) onMarkDirty.run();
                        },
                        () -> { /* onConfirm: 什么也不做，值已写入 */ }
                    );
                }
            });
        }
        list.add(psHolder[0]);
    }

    /** 雪线滑块（getter/setter 模式），static 供 TerrainConfigPanel 复用 */
    static void addYSlider(List<ParamSlider> list, String label,
                           java.util.function.DoubleSupplier getter,
                           java.util.function.DoubleConsumer setter,
                           double min, double max, Function<Double, String> fmt) {
        ParamSlider ps = new ParamSlider(0, 0, 100, min, max, getter.getAsDouble(), v -> {
            setter.accept(v);
        }, fmt);
        ps.setTooltipText(label);
        list.add(ps);
    }

    /** 刷新世界高度依赖的下游组件（柱状图、尺度预览）。被 GeoGenesisConfigScreen 在 tab 切换时调用 */
    public void refreshHeightDependent() {
        heightBar.refreshFromConfig();
        // 尺度预览：如果 scalePrev 有刷新方法则调用
    }

    public int getHeight() {
        int h = 14 + basicSliders.size() * BASIC_ROW_H + 8;
        h += heightPanel.getFullHeight() + 4;
        return h;
    }

    // ===== 渲染 =====

    public void render(GuiGraphics g, int mx, int my) {
        var f = Minecraft.getInstance().font;
        int ty = top();

        g.drawString(f, "■ 基础参数", x + 6, ty + 4, 0xFF66CCFF);
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
            int scaleStartX = x + heightBarW + 30;
            scalePrev.setBounds(scaleStartX, panelContentY + 4, w - scaleStartX - 4, 100);
            scalePrev.render(g, mx, my);
            // 下方：所有滑块 - 在两个图表下方
            int sy = panelContentY + 116;
            int slW = w - 70;
            for (int i = 0; i < heightSliders.size(); i++) {
                ParamSlider ps = heightSliders.get(i);
                ps.setX(x + 12); ps.setY(sy + i * (CHART_SLIDER_H + 2)); ps.setWidth(slW);
                ps.render(g, mx, my, 0);
                ps.renderTooltip(g, mx, my);
            }
        });
        sectionTop += heightPanel.getFullHeight() + 4;

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
        // 折叠段（仅剩世界高度）
        int curY = basicTop + basicSliders.size() * BASIC_ROW_H + 8;
        if (trySectionClick(mx, my, btn, curY, heightPanel, heightSliders, heightBar, 132)) return true;
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
        if (!heightPanel.isCollapsed()) {
            if (heightBar.mouseDragged(mx, my, btn, dx, dy)) return true;
            if (scalePrev.mouseDragged(mx, my, btn, dx, dy)) return true;
        }
        return false;
    }

    public boolean mouseReleased(double mx, double my, int btn) {
        for (ParamSlider s : basicSliders) if (s.isFocused()) s.mouseReleased(mx, my, btn);
        for (ParamSlider s : heightSliders) if (s.isFocused()) s.mouseReleased(mx, my, btn);
        if (!heightPanel.isCollapsed()) {
            heightBar.mouseReleased(mx, my, btn);
            scalePrev.mouseReleased(mx, my, btn);
        }
        return false;
    }
}
