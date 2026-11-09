package com.geogenesis.client.preview.raster;

import com.geogenesis.client.preview.SandboxLod;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

/**
 * <b>沙盘 HUD</b>（★ 2026-11-09 B4a）—— 标题/操作说明/进度条/悬停信息/比例尺。
 *
 * <h2>为什么要独立成类</h2>
 * <p>{@code PreviewDisplay} 原本同时承担：采样管线 + 平面绘制 + 沙盘绘制 + 图例 +
 * 沙盘 HUD + 交互（2328 行）。HUD 是<b>纯装饰</b>，不碰任何地形数据
 * ⇒ 抽出来后 {@code PreviewDisplay} 只剩「数据 + 绘制」，职责单一。</p>
 *
 * <h2>设计</h2>
 * <p>只读一个 {@link HudData} 快照 —— 由 {@code PreviewDisplay} 每帧填充。
 * 这样 HUD 不需要知道采样器、地形实例等任何内部状态。</p>
 */
public final class SandboxHud {

    /** HUD 需要的数据快照（由持有方填充）。 */
    public static final class HudData {
        public long seed;
        public int centerX, centerZ;
        public float zoom;
        /** 采样进度 [0,1]；&lt;0 = 不显示进度条。 */
        public float progress = -1f;
        /** 悬停柱的柱坐标（&lt;0 = 未悬停）。 */
        public int pickedColX = -1, pickedColZ = -1;
        /** 悬停柱的顶面高度（模型单位）。 */
        public float pickedTopY;
        /** 悬停处对应的世界坐标。 */
        public int hoverWorldX, hoverWorldZ;
        /** 窗口起点（世界坐标）与窗口方块数 —— 用于把柱坐标换算成世界坐标。 */
        public int originWorldX, originWorldZ, windowBlocks;
    }

    private static final int GOLD = 0xFFFFD24A;
    private static final int TEXT_SUB = 0xFFD4D9E4;
    private static final int TEXT_HINT = 0xFFA6AEBE;

    private final Minecraft mc = Minecraft.getInstance();

    private SandboxHud() { }

    /**
     * 绘制沙盘 HUD。
     *
     * <h2>★ 角落所有权契约（2026-11-09，修复"两层 GUI"）</h2>
     * <table border="1">
     *   <tr><th>角落</th><th>唯一所有者</th></tr>
     *   <tr><td>左上</td><td>本类（标题/说明/进度条）</td></tr>
     *   <tr><td>右上</td><td>{@code PreviewDisplay.drawLegend}（图例）</td></tr>
     *   <tr><td>左下</td><td>本类（悬停信息）</td></tr>
     *   <tr><td><b>右下</b></td><td><b>{@code GeoGenesisConfigScreen.renderStatusHud}</b>
     *       （statusLine 已含视野/柱数/中心 —— 本类<b>不再画比例尺</b>，
     *        否则与 statusLine 叠成两层文字，实机截图已取证）</td></tr>
     * </table>
     */
    public static void render(GuiGraphics g, int x, int y, int w, int h, HudData d) {
        drawTopLeft(g, x, y, d);
        drawProgress(g, x, y, w, h, d);
        drawHover(g, x, y, w, h, d);
        // ★ 右下角归 ConfigScreen.renderStatusHud 所有（statusLine 已含同样信息），
        //   本类不再画比例尺 —— 曾在这里画过，与 statusLine 叠成两层（实机取证）。
    }

    private static void drawTopLeft(GuiGraphics g, int x, int y, HudData d) {
        Minecraft mc = Minecraft.getInstance();
        int px = x + 8;
        int py = y + 6;
        g.drawString(mc.font, "GeoGenesis 地形沙盘", px, py, GOLD, true);
        py += 12;
        String sub = String.format(java.util.Locale.ROOT,
                "种子 %d · 中心 %d,%d · %s",
                d.seed, d.centerX, d.centerZ, SandboxLod.scaleLabel(d.zoom));
        g.drawString(mc.font, sub, px, py, TEXT_SUB, true);
        py += 12;
        String[] hints = {
                "右键拖动 = 移动地图 · 左键拖动 = 旋转",
                "滚轮 = 推近/拉远（推近柱子会细分）· Ctrl+滚轮 = 柱宽",
                "R = 重置视角",
        };
        for (String s : hints) {
            g.drawString(mc.font, s, px, py, TEXT_HINT, true);
            py += 10;
        }
    }

    private static void drawProgress(GuiGraphics g, int x, int y, int w, int h, HudData d) {
        if (d.progress < 0f) return;
        int barW = 4;
        int barX = x + w - barW - 4;
        int barTop = y + 44;
        int barBottom = y + h - 40;
        g.fill(barX, barTop, barX + barW, barBottom, 0x50FFFFFF);
        g.fill(barX, barTop, barX + barW,
                barTop + (int) ((barBottom - barTop) * d.progress), GOLD);
    }

    private static void drawHover(GuiGraphics g, int x, int y, int w, int h, HudData d) {
        if (d.pickedColX < 0) return;
        Minecraft mc = Minecraft.getInstance();
        String[] lines = {
                String.format(java.util.Locale.ROOT, "方块 %d, %d", d.hoverWorldX, d.hoverWorldZ),
                String.format(java.util.Locale.ROOT, "高度 y=%d", (int) Math.round(d.pickedTopY)),
        };
        int lh = 10;
        int maxW = 0;
        for (String s : lines) maxW = Math.max(maxW, mc.font.width(s));
        int bx = x + 6;
        int by = y + h - lines.length * lh - 28;
        g.fill(bx - 4, by - 3, bx + maxW + 4, by + lines.length * lh + 2, 0xB212141B);
        g.fill(bx - 4, by - 3, bx + maxW + 4, by - 2, 0x66FFD24A);
        for (int i = 0; i < lines.length; i++) {
            g.drawString(mc.font, lines[i], bx, by + i * lh, TEXT_SUB, true);
        }
    }
}
