package com.geogenesis.client.preview;

import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;
import net.minecraft.network.chat.Component;

/**
 * 缓存设置面板（「缓存」页签）：实时缓存统计 + 清除缓存按钮。
 * 清除仅清空运行时 {@link PreviewDisplay#cellCache}，不持久化。
 */
public class CachePanel extends ConfigPanel {

    private final PreviewDisplay preview;
    private static final int BTN_H = 24;
    private static final int STATS_OFF = 40;
    private static final int BTN_OFF = 64;

    public CachePanel(PreviewDisplay preview) { this.preview = preview; }

    @Override
    public int getHeight() { return 200; }

    @Override
    public void render(GuiGraphics g, int mx, int my) {
        int y = top();
        drawHeader(g, x, y, I18n.get("geogenesis.settings.cache.title"));

        int dy = y + 26;
        drawWrapped(g, I18n.get("geogenesis.settings.cache.desc"), x, dy, w);

        int count = preview.cellCache.chunkCount();
        g.drawString(font(), "已缓存区块: " + count + "  (" + (count * 256) + " cells)",
            x, dy + STATS_OFF, C_TEXT_DIM);

        int by = dy + BTN_OFF;
        int bw = Math.min(320, w);
        boolean h = drawButton(g, x, by, bw, BTN_H, I18n.get("geogenesis.settings.cache.clear"), false, mx, my);
        if (h) hoverTooltip = Component.literal(I18n.get("geogenesis.settings.cache.clear.tooltip"));
    }

    /** 按字符宽度折行绘制说明文字（适配中文） */
    private void drawWrapped(GuiGraphics g, String text, int sx, int sy, int maxW) {
        Font f = font();
        int cy = sy;
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < text.length(); i++) {
            String ch = text.substring(i, i + 1);
            if (f.width(line.toString() + ch) > maxW && line.length() > 0) {
                g.drawString(f, line.toString(), sx, cy, C_TEXT_DIM);
                cy += f.lineHeight + 2;
                line = new StringBuilder(ch);
            } else {
                line.append(ch);
            }
        }
        if (line.length() > 0) g.drawString(f, line.toString(), sx, cy, C_TEXT_DIM);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        int by = top() + 26 + BTN_OFF;
        if (hit(x, by, Math.min(320, w), BTN_H, mx, my)) {
            // 清除运行时 + 磁盘缓存（对齐参考模组 clearCache）
            preview.clearAllCaches();
            playClick();
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int btn) { return false; }

    @Override
    public boolean mouseDragged(double mx, double my, int btn, double dx, double dy) { return false; }
}
