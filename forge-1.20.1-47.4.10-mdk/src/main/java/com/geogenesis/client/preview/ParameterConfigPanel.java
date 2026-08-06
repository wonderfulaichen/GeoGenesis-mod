package com.geogenesis.client.preview;

import com.geogenesis.client.ParamSlider;
import com.geogenesis.client.preview.mixer.MixerPanel;
import com.geogenesis.client.preview.mixer.ScalePreview;
import com.geogenesis.client.preview.mixer.WorldHeightBar;
import com.geogenesis.config.GeoGenesisConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;
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
    private static class BasicIntSpec {
        final String label;
        final String description;
        final ForgeConfigSpec.IntValue cfg;
        final int min, max;
        BasicIntSpec(String label, String description, ForgeConfigSpec.IntValue cfg, int min, int max) {
            this.label = label; this.description = description; this.cfg = cfg; this.min = min; this.max = max;
        }
    }
    private final List<BasicSpec> specs = new ArrayList<>();
    private final List<BasicIntSpec> intSpecs = new ArrayList<>();
    private final List<ParamSlider> basicSliders = new ArrayList<>();

    /** 开关行（侵蚀/河流等布尔配置，原来只能手改 TOML） */
    private static class ToggleSpec {
        final String label;
        final String description;
        final java.util.function.BooleanSupplier getter;
        final java.util.function.Consumer<Boolean> setter;
        ToggleSpec(String label, String description,
                   java.util.function.BooleanSupplier getter,
                   java.util.function.Consumer<Boolean> setter) {
            this.label = label; this.description = description;
            this.getter = getter; this.setter = setter;
        }
    }
    private final List<ToggleSpec> toggles = new ArrayList<>();

    // 图表
    private final WorldHeightBar heightBar = new WorldHeightBar();
    private final ScalePreview scalePrev = new ScalePreview();

    // 图表关联滑块
    private final List<ParamSlider> heightSliders = new ArrayList<>();

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
        specs.clear(); intSpecs.clear(); basicSliders.clear();
        toggles.clear();
        heightSliders.clear();

        // 基础参数（只保留独立功能的参数）
        addSpec("海陆偏置", "大陆性噪声偏置：正值→海洋面积增加（陆地减少），负值→陆地面积增加（海洋减少）。影响海陆整体比例。公式：cBiased = c - bias，偏置越大海洋越多。", c.continentBias, -0.6, 1.5);
        addSpec("海洋深度乘数", "海洋深度缩放因子。>1→海洋更深（海洋面积扩大），<1→海洋更浅（陆地面积扩大）。直接控制海陆面积比例。默认1.0。", c.oceanDepthFactor, 0.5, 3.0);
        addSpec("海洋淡出起点", "海洋深度淡出起点（cBiased 空间）。大陆性在此值以上，海洋深度开始衰减到海平面。越负→大陆架越宽。默认-0.15。", c.oceanFadeStart, -0.5, 0.0);
        addSpec("陆地高度终点", "陆地高度升起终点（cBiased 空间）。大陆性达到此值时陆地高度完全露出。越高→海滩带越宽。默认0.08。", c.landRampEnd, 0.0, 0.5);
        addSpec("海床细节", "海床微地形振幅。越大→海床起伏越明显（海山、海沟等特征）。", c.seabedDetail, 0, 0.2);
        addSpec("高海拔阈值", "海拔分类阈值。低于此值为平原/丘陵，高于为高地（山脉+高原+雪峰）。值越低→更多区域划入高地。", c.elevHigh, 0, 1.0);
        addSpec("高起伏阈值", "起伏度分类阈值。低于此值为丘陵，高于为山脉。值越低→更多区域划入山脉类型。", c.reliefHigh, 0, 0.6);
        // 2026-08-05：peakE 配置已彻底移除（PEAK 并入 MOUNTAINS），不再有峰阈值滑块
        addSpec("语义适配强度", "大陆性语义亲和度强度 β：调制大陆性 c 对各类型空间权重的偏置幅度。0=无 c 效应（类型分布不随大陆性变化），越大越偏内陆聚集/海岸低地。默认1.5。", c.cAffinityStrength, 0.0, 4.0);
        // 大陆 FBM（值叠加，对标参考项目 fbm 海岸线）
        addIntSpec("大陆FBM倍频", "大陆性 c 场由多倍频 FBM 值叠加（每倍频频率倍增 lacunarity、振幅乘 persistence）。海岸线 = c=0 等值线，FBM 多尺度细节直接刻进海岸线 → 自然犬牙交错。默认 6（严格对齐参考项目）。", c.continentFbmOctaves, 1, 10);
        addSpec("FBM频率倍增", "大陆 FBM 频率倍增（lacunarity）：每倍频频率×该值，越大细节尺度越密（海岸线破碎更细）。默认 2.0。", c.continentFbmLacunarity, 1.5, 3.0);
        addSpec("FBM振幅衰减", "大陆 FBM 振幅衰减（persistence）：每倍频振幅×该值，越大高频细节越强、海岸线越破碎。默认 0.6。", c.continentFbmPersistence, 0.3, 0.85);
        // 海岸线多样化
        addSpec("海岸线位移", "海岸线 warp 振幅（c 空间单位）。海岸线在 c 上的位移幅度，越大海岸线越蜿蜒。默认 0.15。", c.coastlineWarpAmp, 0.0, 0.3);
        addSpec("位移尺度", "海岸线 warp 基频世界坐标尺度（块）。越大变化越平缓。默认 1200。", c.coastlineWarpScale, 50.0, 2000.0);
        addSpec("地形调制", "地形类型调制强度（c 空间单位）。山地→岬角/悬崖，平原/盆地→海湾。默认 0.08。", c.coastTerrainInfluence, 0.0, 0.3);
        addSpec("群岛带宽", "离岸群岛带宽度（c 空间单位，从 coastLoc 向海侧延伸）。越大群岛范围越宽。默认 0.10。", c.archipelagoBand, 0.0, 0.3);
        addSpec("群岛密度", "离岸群岛密度阈值（0-1）。超过此阈值才生成岛屿，越低→岛屿越多。默认 0.30。", c.archipelagoDensity, 0.0, 1.0);
        addSpec("群岛尺度", "离岸群岛噪声尺度（块）。越大→岛屿团块越大越稀疏。默认 120。", c.archipelagoScale, 30.0, 500.0);
        addSpec("群岛高度", "离岸群岛最大高度（e 单位）。越低→岛屿越扁平。默认 0.035。", c.archipelagoHeight, 0.0, 0.15);
        // 海岸线 FBM 分形参数
        addIntSpec("Warp倍频", "海岸线 warp 倍频数（octaves）：分形细节尺度层级数。越大海岸线越多尺度（自相似犬牙交错），越小越平滑。默认 5。", c.coastlineWarpOctaves, 1, 8);
        addSpec("Warp频率倍增", "FBM 频率倍增系数（lacunarity）：每倍频频率×该值。默认 2.0。", c.coastlineWarpLacunarity, 1.5, 3.0);
        addSpec("Warp振幅衰减", "FBM 振幅衰减系数（persistence）：每倍频振幅×该值。默认 0.5。", c.coastlineWarpPersistence, 0.25, 0.8);
        for (BasicSpec s : specs) {
            ParamSlider ps = new ParamSlider(0, 0, 100, s.min, s.max, s.cfg.get(), v -> {
                s.cfg.set(v); onChange.accept(v);
            }, fmt);
            ps.setDefaultValue(s.cfg.getDefault());
            ps.setTooltipText(s.description);
            basicSliders.add(ps);
        }
        // 整数滑块（如 warp 倍频）
        for (BasicIntSpec s : intSpecs) {
            final ParamSlider[] psHolder = new ParamSlider[1];
            psHolder[0] = new ParamSlider(0, 0, 100, s.min, s.max, s.cfg.get(), v -> {
                int val = (int) Math.round(v);
                s.cfg.set(val);
                psHolder[0].setCurrentValue(val);
                onChange.accept(0.0);
            }, v -> String.format("%d", (int) Math.round(v)));
            psHolder[0].setDefaultValue(s.cfg.getDefault());
            psHolder[0].setTooltipText(s.description);
            basicSliders.add(psHolder[0]);
        }

        // ★ 侵蚀与河流开关（原来只能手改 TOML，现写入面板）
        addToggle("液滴粒子侵蚀", "本地粒子侵蚀（液滴微刻细节）。关闭则只有骨架脊谷，地形更平缓。",
                () -> c.erosionEnabled.get(), v -> c.erosionEnabled.set(v));
        addToggle("骨架模拟侵蚀", "粗侵蚀（脊-谷条纹滤镜）骨架层。开启时先造大山脊基本型，再由粒子侵蚀做细节。",
                () -> c.erosionRidgeEnabled.get(), v -> c.erosionRidgeEnabled.set(v));
        addToggle("河流系统", "河网检测 + 河道填水（独立于侵蚀）。关闭 = 无河流。",
                () -> c.riversEnabled.get(), v -> c.riversEnabled.set(v));
        addToggle("河道灌水", "河道是否灌水。关闭 = 旱谷（仅显形为凹槽地形，不填水）。",
                () -> c.riverWater.get(), v -> c.riverWater.set(v));
        addToggle("河流总开关", "河流总开关。关闭 = 不标记任何河流/旱谷。",
                () -> c.riverEnabled.get(), v -> c.riverEnabled.set(v));

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

        // 峰高比例：实际山峰高度占 maxY 的比例，留余量避免触顶世界构建上限（替代有 bug 的钳制）
        addSpec("峰高比例", "实际山峰高度占 maxY 的比例。默认 0.92（留 8% 余量，避免山峰触顶世界构建上限被钳平成平顶）。调高→山更高（接近 maxY），调低→山峰更矮。", c.peakHeightFraction, 0.5, 1.0);

        // 尺度缩放（HS = 水平 XZ 等比缩放，VS = 垂直 Y 等比缩放）
        scalePrev.setHorizontalScale(c.horizontalScale.get());
        scalePrev.setVerticalScale(c.verticalScale.get());
        scalePrev.setDefaultHorizontalScale(c.horizontalScale.getDefault());
        scalePrev.setDefaultVerticalScale(c.verticalScale.getDefault());
        scalePrev.setOnMarkDirty(() -> {
            // 拖拽后更新配置
            c.horizontalScale.set(scalePrev.getHorizontalScale());
            c.verticalScale.set(scalePrev.getVerticalScale());
            onChange.accept(0.0);
        });
    }

    private void addSpec(String label, String description, ForgeConfigSpec.DoubleValue cfg, double min, double max) {
        specs.add(new BasicSpec(label, description, cfg, min, max));
    }

    private void addIntSpec(String label, String description, ForgeConfigSpec.IntValue cfg, int min, int max) {
        intSpecs.add(new BasicIntSpec(label, description, cfg, min, max));
    }

    private void addToggle(String label, String description,
                           java.util.function.BooleanSupplier getter,
                           java.util.function.Consumer<Boolean> setter) {
        toggles.add(new ToggleSpec(label, description, getter, setter));
    }

    /** 开关持久化（Windows 文件锁可能抛 WritingException，静默忽略） */
    private static void saveQuietly() {
        try { GeoGenesisConfig.SPEC.save(); } catch (Exception ignored) { /* 文件写竞争忽略 */ }
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
        h += toggles.size() * BASIC_ROW_H + 22;  // 侵蚀与河流开关小节
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
            // basicSliders 混合了 specs(浮点) 与 intSpecs(整数) 两类滑块，须按索引区分标签来源
            String label = (i < specs.size()) ? specs.get(i).label : intSpecs.get(i - specs.size()).label;
            ParamSlider ps = basicSliders.get(i);
            int rowY = basicTop + i * BASIC_ROW_H;
            if (i % 2 == 0) g.fill(x + 4, rowY, x + w - 8, rowY + BASIC_ROW_H, 0x12FFFFFF);
            g.drawString(f, label, x + 6, rowY + (BASIC_ROW_H - 8) / 2, 0xFFCCCCCC);
            int slX = x + 4 + BASIC_LABEL_W;
            int slW = w - 8 - BASIC_LABEL_W - BASIC_VAL_W - 8;
            ps.setX(slX); ps.setY(rowY + 2); ps.setWidth(slW);
            ps.render(g, mx, my, 0);
            // 数值文字统一由 ParamSlider 内部渲染（与地形页一致）
            // 滑块与数值之间留出 BASIC_VAL_W 空间供重置按钮使用
        }
        for (ParamSlider ps : basicSliders) ps.renderTooltip(g, mx, my);

        // ★ 侵蚀与河流开关小节
        int toggleTop = basicTop + basicSliders.size() * BASIC_ROW_H + 8;
        g.drawString(f, "■ 侵蚀与河流", x + 6, toggleTop + 4, 0xFF66CCFF);
        int toggleRowY = toggleTop + 18;
        for (int i = 0; i < toggles.size(); i++) {
            ToggleSpec ts = toggles.get(i);
            int rowY = toggleRowY + i * BASIC_ROW_H;
            boolean hover = drawToggleRow(g, x + 4, rowY, w - 8, BASIC_ROW_H - 2,
                    ts.label, ts.getter.getAsBoolean(), mx, my);
            if (hover) hoverTooltip = Component.literal(ts.description);
        }

        int sectionTop = toggleRowY + toggles.size() * BASIC_ROW_H + 8;

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
        // ★ 侵蚀与河流开关
        int toggleRowY = basicTop + basicSliders.size() * BASIC_ROW_H + 8 + 18;
        for (int i = 0; i < toggles.size(); i++) {
            ToggleSpec ts = toggles.get(i);
            int rowY = toggleRowY + i * BASIC_ROW_H;
            if (hit(x + 4, rowY, w - 8, BASIC_ROW_H - 2, mx, my)) {
                ts.setter.accept(!ts.getter.getAsBoolean());
                if (onMarkDirty != null) onMarkDirty.run();
                playClick();
                return true;
            }
        }
        // 折叠段（仅剩世界高度）
        int curY = toggleRowY + toggles.size() * BASIC_ROW_H + 8;
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
        return false;
    }

    public boolean mouseDragged(double mx, double my, int btn, double dx, double dy) {
        for (ParamSlider s : basicSliders) if (s.isFocused()) { s.mouseDragged(mx, my, btn, dx, dy); return true; }
        for (ParamSlider s : heightSliders) if (s.isFocused()) { s.mouseDragged(mx, my, btn, dx, dy); return true; }
        // ★ 只转发"鼠标在面板区域内"的拖拽：原无条件转发 → 用户在预览窗口拖动、
        //   鼠标移到左侧参数面板上方时误触 scalePrev/heightBar（HS 2→8 实锤链路之一）。
        if (!heightPanel.isCollapsed() && mx >= x && mx <= x + w && my >= top() && my <= top() + getHeight()) {
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
