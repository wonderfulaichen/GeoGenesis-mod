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
    /** ★ v15：按钮高统一引用 UiKit。 */
    private static final int BTN_H = UiKit.H_CONTROL;
    private static final int STATS_OFF = 40;
    private static final int BTN_OFF = 64;

    public CachePanel(PreviewDisplay preview) { this.preview = preview; }

    /**
     * 内容高度（★ v22：按<b>实际折行后的说明高度</b>算）。
     *
     * <p>原写死 200 —— 但 {@code drawWrapped} 会随面板宽度折行，
     * 窄面板下说明多占几行就会顶到底边，滚到底仍看不全。</p>
     */
    @Override
    public int getHeight() {
        int descH = wrappedHeight(I18n.get("geogenesis.settings.cache.desc"), Math.max(60, w));
        // 说明 + 统计行(STATS_OFF) + 按钮(BTN_OFF) + 底部留白
        return 2 + Math.max(descH, STATS_OFF) + BTN_OFF + BTN_H + 16;
    }

    @Override
    public void render(GuiGraphics g, int mx, int my) {
        // ★ v7：标题由 ConfigPanelHost 统一画（带「— N 项」），这里从内容起笔。
        int dy = top() + 2;
        drawWrapped(g, I18n.get("geogenesis.settings.cache.desc"), x, dy, w);

        int count = preview.cellCache.chunkCount();
        int statsY = dy + STATS_OFF;
        // ★ v13：统计区底色彻底去掉（实心块会显出矩形边界），靠左右分栏对齐表达层级
        g.drawString(font(), "已缓存区块", x + 2, statsY, UiKit.C_TXT_SUB, false);
        String num = count + "  (" + (count * 256) + " cells)";
        g.drawString(font(), num, x + w - 2 - font().width(num), statsY, UiKit.C_TXT, false);

        int by = dy + BTN_OFF;
        int bw = Math.min(320, w);
        boolean h = drawButton(g, x, by, bw, BTN_H, I18n.get("geogenesis.settings.cache.clear"), false, mx, my);
        if (h) hoverTooltip = Component.literal(I18n.get("geogenesis.settings.cache.clear.tooltip"));
    }

    /**
     * 折行绘制（★ v22：改走 {@link UiKit#drawWrapped}）。
     *
     * <p>原实现是手写的"按字符宽度折行"，与其它面板的 UiKit 版本重复；
     * 两份实现的行高/断行规则不一致 ⇒ 高度估算与绘制对不上。</p>
     */
    private void drawWrapped(GuiGraphics g, String text, int sx, int sy, int maxW) {
        UiKit.drawWrapped(g, text, sx, sy, maxW, UiKit.C_TXT_DIM);
    }

    /** 折行后的高度（与 {@link #drawWrapped} 同源）。 */
    private int wrappedHeight(String text, int maxW) {
        return UiKit.wrappedHeight(text, maxW);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        // ★ v22：与 getHeight 的 BTN_OFF 同源（原来写死 +26，与 render 的 +2 不一致）
        int by = top() + 2 + BTN_OFF;
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
