package com.geogenesis.client.preview;

import com.geogenesis.client.preview.mixer.ScalePreview;
import com.geogenesis.config.ConfigSpecRanges;
import com.geogenesis.config.PanelLayout;
import net.minecraft.client.gui.GuiGraphics;

/**
 * 「世界与尺度」页签：目录驱动的滑块行 + <b>尺度示意图</b>（{@link ScalePreview}）。
 *
 * <h2>为什么把尺度图挂在这一页</h2>
 * <p>用户对"世界多高/多宽"的直觉来自图形，不是数字。旧实现把它埋在
 * {@code ParameterConfigPanel} 的「世界高度」折叠段里，与"世界高度标尺（仅预览）"
 * 混在一起 —— 于是用户拖动那些标尺以为在改世界，其实只改了预览色阶
 * （{@code seaLevel/minY/maxY} 是 {@code PREVIEW_ONLY}，见 {@code ConfigKeyCatalog}）。</p>
 * <p>现在：<b>真正驱动世界的垂直/水平缩放</b>在本页（目录驱动滑块 + 尺度图联动），
 * <b>仅预览标尺</b>被挪到「显示与色带」页并明确标注「不驱动世界」。</p>
 *
 * <h2>★ 尺度图的读写口径</h2>
 * <p>{@link ScalePreview} 的水平轴固定 0.5–8.0、垂直轴固定 1.0–8.0（硬编码在它内部）。
 * 这两个范围恰好与 {@code horizontalScale}/{@code verticalScale} 的
 * {@code defineInRange} 相同，因此图与滑块<b>不会互相钳制出偏差</b>。
 * 若将来改了配置范围，必须同批改 {@link ScalePreview} —— 本类的
 * {@code extraHeight} 注释与探针都指向这一条。</p>
 */
public class WorldScalePanel extends DomainPanel {

    private static final int CHART_H = 132;

    private final ScalePreview scalePreview = new ScalePreview();

    public WorldScalePanel() {
        super(PanelLayout.Tab.WORLD_SCALE, "世界与尺度",
                "决定世界有多大、多高。下方的图与滑块联动，读的就是这两个键。");
        scalePreview.setOnMarkDirty(this::syncScalesToConfig);
        refreshChart();
    }

    /** 从配置回读（含尺度图）。 */
    @Override
    public void buildFromConfig() {
        super.buildFromConfig();
        refreshChart();
    }

    private void refreshChart() {
        scalePreview.setHorizontalScale(ConfigSpecRanges.getDouble("horizontalScale", 2.0));
        scalePreview.setVerticalScale(ConfigSpecRanges.getDouble("verticalScale", 1.0));
        scalePreview.setDefaultHorizontalScale(
                parseDefaultOr("horizontalScale", 2.0));
        scalePreview.setDefaultVerticalScale(
                parseDefaultOr("verticalScale", 1.0));
    }

    private static double parseDefaultOr(String key, double fallback) {
        ConfigSpecRanges.Meta m = ConfigSpecRanges.metaOf(key);
        if (m == null) return fallback;
        try {
            return Double.parseDouble(m.defaultText());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** 拖图 → 写配置（图是唯一来源，写回后不再回读，避免每帧互相覆盖）。 */
    private void syncScalesToConfig() {
        ConfigSpecRanges.setDouble("horizontalScale", scalePreview.getHorizontalScale());
        ConfigSpecRanges.setDouble("verticalScale", scalePreview.getVerticalScale());
        onMarkDirty.run();
    }

    /**
     * <b>示意图已搬到"常驻固定区"</b>（★ v20）。
     *
     * <p>用户要求示意图「常驻显示，不然滚动就消失了」。
     * 现在由 {@code GeoGenesisConfigScreen.renderPinnedChart} 钉在内容区底部，
     * 因此本面板的<b>滚动区不再包含</b>示意图（返回 0），
     * 参数列表可以独立滚动。</p>
     */
    @Override
    protected int extraHeight() { return 0; }

    @Override
    protected void renderExtra(GuiGraphics g, int mx, int my, int y) {
        // 有意留空：示意图改由屏幕在固定区调用 {@link #renderChart}。
    }

    /** 屏幕注入的固定区几何。 */
    private int pinnedX, pinnedY, pinnedW;

    /** 屏幕在固定区渲染前设置几何。 */
    public void setPinnedBounds(int px, int py, int pw) {
        this.pinnedX = px;
        this.pinnedY = py;
        this.pinnedW = pw;
    }

    /**
     * <b>绘制尺度示意图</b>（★ v20：由屏幕在固定区调用）。
     *
     * <p>每帧从配置回读 —— 两个 double 读取，成本可忽略，
     * 换来"拖动上方滑块时，图实时变形"。</p>
     */
    public void renderChart(GuiGraphics g, int mx, int my) {
        refreshChart();
        scalePreview.setBounds(pinnedX, pinnedY, pinnedW, CHART_H);
        scalePreview.render(g, mx, my);
        // 底部一行说明（统一字色）——也让本类保持对 UiKit 的唯一视觉来源
        g.drawString(font(), UiKit.clip("拖动上方滑块可实时看到地形起伏与采样密度的变化", pinnedW),
                pinnedX, pinnedY + CHART_H + 16, UiKit.C_TXT_DIM, false);
    }

    /** 固定区示意图的命中检测（只有重置键可点）。 */
    public boolean chartClicked(double mx, double my, int btn) {
        scalePreview.setBounds(pinnedX, pinnedY, pinnedW, CHART_H);
        return scalePreview.mouseClicked(mx, my, btn);
    }

    /**
     * 点击：只转发给示意图的<b>重置键</b>（示意图本身只读）。
     *
     * <p>★ v17：去掉 {@code mouseDraggedExtra} —— 示意图不再可拖，
     * 留着它只会让人以为这里能拖。</p>
     */
    @Override
    protected boolean mouseClickedExtra(double mx, double my, int btn, int y) {
        scalePreview.setBounds(x + 4, y + 18, w - 8, CHART_H);
        return scalePreview.mouseClicked(mx, my, btn);
    }

    @Override
    protected void mouseReleasedExtra(double mx, double my, int btn) {
        scalePreview.mouseReleased(mx, my, btn);
        super.buildFromConfig();
    }
}
