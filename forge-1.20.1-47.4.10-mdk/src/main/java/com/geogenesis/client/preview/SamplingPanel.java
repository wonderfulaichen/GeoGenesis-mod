package com.geogenesis.client.preview;

import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;

/**
 * 采样设置面板（「采样」页签）：渲染超采样倍率 1×/2×/3×/4× 切换。
 * 倍率仅影响纹理分辨率，直接调用 {@link PreviewDisplay#setRenderScale}。
 */
public class SamplingPanel extends ConfigPanel {

    private final PreviewDisplay preview;
    /** ★ v15：按钮高统一引用 UiKit（原写死 24，与别处 20 不一致）。 */
    private static final int BTN_H = UiKit.H_CONTROL;
    private static final int GAP = 8;
    private static final int[] SCALES = {1, 2, 3, 4};

    public SamplingPanel(PreviewDisplay preview) { this.preview = preview; }

    private int btnY(int row) { return top() + 6 + row * (BTN_H + GAP); }

    /** 行数 = 2 行倍率按钮 + 1 行全局查看开关。 */
    private static final int ROW_COUNT = 3;

    @Override
    public int getHeight() {
        // ★ v22：按实际行数算 + 底部留白（原写死 200，改布局就会滚不到底）
        return 6 + ROW_COUNT * (BTN_H + GAP) + 18;
    }

    @Override
    public void render(GuiGraphics g, int mx, int my) {
        // ★ v15：标题由 ConfigPanelHost 统一画（drawHeader 已空实现），内容从顶部起笔
        int bw = (w - GAP) / 2;
        for (int i = 0; i < SCALES.length; i++) {
            int col = i % 2, row = i / 2;
            int bx = x + col * (bw + GAP);
            int by = btnY(row);
            boolean active = SCALES[i] == preview.renderScale;
            boolean h = drawButton(g, bx, by, bw, BTN_H, SCALES[i] + "×", active, mx, my);
            if (h) hoverTooltip = Component.literal(
                    I18n.get("geogenesis.settings.sampling.scale_" + SCALES[i] + ".tooltip"));
        }
        // ★ 大范围预览开关（2026-09-11）：开启后【自动缩到 1:64】并走 LargeAreaSampler
        //   （固定 64×64 采样，开销与视野无关）→ 可查看数万格的气候格局与纬度分带。
        //   注：预览正上方另有同一开关（主入口，更易发现）；两处共享 PreviewDisplay 状态，文案自动同步。
        int ty = btnY(2);
        boolean large = preview.isLargeArea();
        boolean hl = drawButton(g, x, ty, w, BTN_H,
                I18n.get(large ? "geogenesis.settings.sampling.large.on"
                               : "geogenesis.settings.sampling.large.off"),
                large, mx, my);
        if (hl) hoverTooltip = Component.literal(I18n.get("geogenesis.settings.sampling.large.tooltip"));
    }

    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        int bw = (w - GAP) / 2;
        for (int i = 0; i < SCALES.length; i++) {
            int col = i % 2, row = i / 2;
            int bx = x + col * (bw + GAP);
            int by = btnY(row);
            if (hit(bx, by, bw, BTN_H, mx, my)) {
                preview.setRenderScale(SCALES[i]); playClick(); return true;
            }
        }
        if (hit(x, btnY(2), w, BTN_H, mx, my)) {
            preview.setLargeArea(!preview.isLargeArea()); playClick(); return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int btn) { return false; }

    @Override
    public boolean mouseDragged(double mx, double my, int btn, double dx, double dy) { return false; }
}
