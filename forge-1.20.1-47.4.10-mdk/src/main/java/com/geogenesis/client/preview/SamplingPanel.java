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
    private static final int BTN_H = 24;
    private static final int GAP = 8;
    private static final int[] SCALES = {1, 2, 3, 4};

    public SamplingPanel(PreviewDisplay preview) { this.preview = preview; }

    private int btnY(int row) { return top() + 30 + row * (BTN_H + GAP); }

    @Override
    public int getHeight() { return 200; }

    @Override
    public void render(GuiGraphics g, int mx, int my) {
        drawHeader(g, x, top(), I18n.get("geogenesis.settings.sampling.title"));
        int bw = (w - GAP) / 2;
        for (int i = 0; i < SCALES.length; i++) {
            int col = i % 2, row = i / 2;
            int bx = x + col * (bw + GAP);
            int by = btnY(row);
            boolean active = SCALES[i] == preview.renderScale;
            boolean h = drawButton(g, bx, by, bw, BTN_H, SCALES[i] + "×", active, mx, my);
            if (h) hoverTooltip = Component.literal(I18n.get("geogenesis.settings.sampling.scale_" + SCALES[i] + ".tooltip"));
        }
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
        return false;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int btn) { return false; }

    @Override
    public boolean mouseDragged(double mx, double my, int btn, double dx, double dy) { return false; }
}
