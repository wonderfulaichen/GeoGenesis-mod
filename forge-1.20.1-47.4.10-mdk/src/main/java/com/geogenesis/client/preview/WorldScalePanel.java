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

    @Override
    protected int extraHeight() { return 18 + CHART_H; }

    @Override
    protected void renderExtra(GuiGraphics g, int mx, int my, int y) {
        g.fill(x, y + CHART_H - 1, x + w, y + CHART_H, C_LINE);
        g.drawString(font(), "尺度示意（拖动即写回上面两个键）", x + 2, y + 2, 0xFF66CCFF);
        scalePreview.setBounds(x + 4, y + 18, w - 8, CHART_H);
        scalePreview.render(g, mx, my);
    }

    @Override
    protected boolean mouseClickedExtra(double mx, double my, int btn, int y) {
        scalePreview.setBounds(x + 4, y + 18, w - 8, CHART_H);
        return scalePreview.mouseClicked(mx, my, btn);
    }

    @Override
    protected boolean mouseDraggedExtra(double mx, double my, int btn, double dx, double dy) {
        return scalePreview.mouseDragged(mx, my, btn, dx, dy);
    }

    @Override
    protected void mouseReleasedExtra(double mx, double my, int btn) {
        scalePreview.mouseReleased(mx, my, btn);
        super.buildFromConfig();
    }
}
