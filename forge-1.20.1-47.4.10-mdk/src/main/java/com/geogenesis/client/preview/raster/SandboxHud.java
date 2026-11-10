package com.geogenesis.client.preview.raster;

import com.geogenesis.client.preview.SandboxLod;
import com.geogenesis.client.preview.UiKit;
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

    /** 悬停浮层配色（本类只剩这一处需要着色）。 */
    private static final int TEXT_SUB = 0xFFD4D9E4;

    private final Minecraft mc = Minecraft.getInstance();

    private SandboxHud() { }

    /**
     * 绘制沙盘 HUD。
     *
     * <h2>★ 角落所有权契约（v7 修订）</h2>
     * <table border="1">
     *   <tr><th>角落</th><th>唯一所有者</th></tr>
     *   <tr><td>左上</td><td><b>{@code GeoGenesisConfigScreen.drawViewHints}（操作提示卡）</b>
     *       —— 本类<b>不再画标题/种子/操作说明</b></td></tr>
     *   <tr><td>右上</td><td>{@code PreviewDisplay.drawContinuousLegendCard}（图例）</td></tr>
     *   <tr><td>左下</td><td>本类（悬停信息）</td></tr>
     *   <tr><td>右下</td><td>{@code GeoGenesisConfigScreen} 状态栏</td></tr>
     * </table>
     *
     * <h2>★ v7 为什么要删掉左上的文字</h2>
     * <p>用户实机截图指出「重复显示标签」：这里画「GeoGenesis 地形沙盘 / 种子 / 中心」
     * 以及三行操作说明，而配置屏左上角<b>已经有一块操作提示卡</b>，
     * 两者内容重叠（操作说明几乎逐字重复）。
     * 一处信息只能有一个所有者 —— 屏幕级 HUD 归屏幕管，本类只留<b>区域内</b>的浮层。</p>
     */
    public static void render(GuiGraphics g, int x, int y, int w, int h, HudData d) {
        drawHover(g, x, y, w, h, d);
        // ★ 左上标题/种子/操作说明、右下比例尺都<b>不再画</b>：
        //   前者与 ConfigScreen 的操作提示卡重复，后者与状态栏重复（均已实机取证）。
    }

    /**
     * 左下角「选中柱子」常驻信息（★ v15：沿用统一浮层样式）。
     *
     * <p>注意它与鼠标 tooltip 是<b>两回事</b>：这个是"已知选中了哪根柱"的
     * 常驻读数（不跟光标），所以保留在左下角固定位置；
     * 但外观必须与屏幕 tooltip 同源（圆角 6 / 近不透明底 / 深色描边），
     * 否则又会出现"两套浮层"。</p>
     */
    private static void drawHover(GuiGraphics g, int x, int y, int w, int h, HudData d) {
        if (d.pickedColX < 0) return;
        Minecraft mc = Minecraft.getInstance();
        String[] lines = {
                String.format(java.util.Locale.ROOT, "方块 %d, %d", d.hoverWorldX, d.hoverWorldZ),
                String.format(java.util.Locale.ROOT, "高度 y=%d", (int) Math.round(d.pickedTopY)),
        };
        int lh = mc.font.lineHeight + 1;
        int maxW = 0;
        for (String s : lines) maxW = Math.max(maxW, mc.font.width(s));
        int padX = 7, padY = 5;
        int bx = x + 8;
        int by = y + h - lines.length * lh - 26;
        UiKit.roundRect(g, bx - padX, by - padY, bx + maxW + padX,
                by + lines.length * lh + padY - 1, UiKit.RADIUS_CARD, 0xE01A1D22);
        UiKit.roundRectOutline(g, bx - padX, by - padY, bx + maxW + padX,
                by + lines.length * lh + padY - 1, UiKit.RADIUS_CARD, 0x22FFFFFF);
        for (int i = 0; i < lines.length; i++) {
            g.drawString(mc.font, lines[i], bx, by + i * lh,
                    i == 0 ? 0xFFF2F2F2 : 0xFFC8C8C8, false);
        }
    }
}
