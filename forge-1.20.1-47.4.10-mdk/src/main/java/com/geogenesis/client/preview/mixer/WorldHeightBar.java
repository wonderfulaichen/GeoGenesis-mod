package com.geogenesis.client.preview.mixer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import com.geogenesis.client.preview.UiKit;

import java.util.ArrayList;
import java.util.List;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

/**
 * <b>世界高度剖面</b>（★ v16：只读示意图）。
 *
 * <h2>职责</h2>
 * <p>把「最高点 / 海平面 / 世界底」三个值画成一条竖向渐变剖面，
 * 让用户直观看到垂直尺度的比例关系。</p>
 *
 * <h2>★ 不承担任何交互</h2>
 * <p>用户裁定：「这些示意图改成<b>不可直接交互</b>，仅由 UI 控件配置」。
 * 改高度只能通过上方的三个滑块 —— 示意图是<b>输出</b>，不是输入。
 * 这样同一个值只有一个入口，不会出现"拖图改了但滑块没动"的困惑。</p>
 */
public class WorldHeightBar {

    public static record Mark(String name, int color, IntSupplier getter, IntConsumer setter) {}

    private final List<Mark> marks = new ArrayList<>();

    private int barX, barY, barW = 24, barH = 200;

    public WorldHeightBar() {}

    public void setMarks(List<Mark> newMarks) {
        marks.clear();
        marks.addAll(newMarks);
    }

    public void setBounds(int x, int y, int w, int h) {
        // 左侧留 34px 给刻度值（右对齐到柱左缘），右侧留 96px 给标记标签。
        int padLeft = 34, padRight = 96;
        barX = x + padLeft;
        barY = y + 12;
        barW = Math.max(20, w - padLeft - padRight);
        barH = Math.max(80, h - 28);
    }
    public int getHeight() { return barH + 20; }
    /** 保留以兼容调用方（配置变化后无需重排，因为位置是按 getter 现算的）。 */
    public void refreshFromConfig() { }

    /** 将世界 Y 坐标转换为屏幕 Y 坐标 */
    private int worldYToScreen(int worldY) {
        int yTop = marks.get(0).getter().getAsInt();
        int yBot = marks.get(marks.size() - 1).getter().getAsInt();
        double frac = (double)(yTop - worldY) / Math.max(1, yTop - yBot);
        return barY + (int)(frac * barH);
    }

    /**
     * <b>绘制世界高度剖面示意图</b>（★ v16：改为<b>只读</b> + 平滑渐变）。
     *
     * <h2>★ 为什么改成只读</h2>
     * <p>用户要求：「这些示意图改成<b>不可直接交互</b>，仅由 UI 控件配置」。
     * 之前的"拖菱形改高度"是<b>绕过滑块直接改配置</b>的第二条路径 ——
     * 同一个值有两处入口，既容易误操作，也让"改哪里生效"变得不明确。</p>
     *
     * <h2>★ 美术重做（参考用户给的图 3）</h2>
     * <p>旧版是几块<b>硬切</b>色块（陆地/浅海/深海之间是直线分界），
     * 像色卡；参考图的效果是<b>连续过渡</b>的色带，能读出"地形从高到低"的推移。
     * 现在用 {@link UiKit#gradientV} 按高度连续插值，并在海平面处叠一条半透明参考线。</p>
     */
    public void render(GuiGraphics g, int mx, int my) {
        var f = Minecraft.getInstance().font;

        UiKit.chartFrame(g, barX, barY, barW, barH);
        if (marks.size() < 3) return;

        int maxY = marks.get(0).getter().getAsInt();      // 最高点
        int seaLevel = marks.get(1).getter().getAsInt();  // 海平面
        int minY = marks.get(2).getter().getAsInt();      // 世界底

        int seaScreenY = worldYToScreen(seaLevel);
        int maxScreenY = worldYToScreen(maxY);
        int minScreenY = worldYToScreen(minY);

        // ---- 平滑渐变：按"世界高度占比"决定每个色标的位置 ----
        double fracSea = (double) (seaScreenY - barY) / Math.max(1, barH);
        double fracMax = (double) (maxScreenY - barY) / Math.max(1, barH);
        double fracMin = (double) (minScreenY - barY) / Math.max(1, barH);

        // 位置须单调递增，否则插值会跳变
        float p1 = (float) clamp01(fracMax);
        float p2 = (float) Math.max(p1 + 0.001, clamp01(fracSea * 0.55));
        float p3 = (float) Math.max(p2 + 0.001, clamp01(fracSea - 0.02));
        float p4 = (float) Math.max(p3 + 0.001, clamp01(fracSea + 0.02));
        float p5 = (float) Math.max(p4 + 0.001, clamp01(fracMin));
        float p6 = Math.max(p5 + 0.001f, 1.0f);

        float[] pos = {0f, p1, p2, p3, p4, p5, p6};
        int[] cols = {
                0xFF6B2A3A,   // 顶：暗红（山脊）
                0xFFFF8A5B,   // 最高点：橙红
                0xFFF2A65A,   // 陆地主体：暖橙
                0xFF3F7FD0,   // 近海面：蓝
                0xFF2A4C8A,   // 海面下：深蓝
                0xFF1B2A45,   // 深海
                0xFF141C2E,   // 底：最深
        };
        UiKit.gradientV(g, barX, barY, barX + barW, barY + barH, pos, cols);

        // ---- 海平面参考线（半透明白，作为"海在哪里"的锚点）----
        g.fill(barX, seaScreenY, barX + barW, seaScreenY + 1, 0x66FFFFFF);

        // ---- 三条高度标记：改为<b>短横线 + 右侧数值</b>（不再画可拖的菱形）----
        int labelX = barX + barW + 6;
        drawMark(g, f, barX, barW, maxScreenY, maxY, marks.get(0).name(), marks.get(0).color(), labelX);
        drawMark(g, f, barX, barW, seaScreenY, seaLevel, marks.get(1).name(), marks.get(1).color(), labelX);
        drawMark(g, f, barX, barW, minScreenY, minY, marks.get(2).name(), marks.get(2).color(), labelX);

        // ---- 刻度值（左侧，右对齐）----
        UiKit.axisLabelRight(g, String.valueOf(maxY), barX - 3, maxScreenY - 4, true);
        UiKit.axisLabelRight(g, String.valueOf(seaLevel), barX - 3, seaScreenY - 4, true);
        UiKit.axisLabelRight(g, String.valueOf(minY), barX - 3, minScreenY - 4, true);
    }

    /** 一条高度标记：柱内短横线 + 柱外"名称=值"。 */
    private static void drawMark(GuiGraphics g, net.minecraft.client.gui.Font f,
                                 int barX, int barW, int y, int value,
                                 String name, int color, int labelX) {
        // 柱内：从左右各伸入 8px 的短横线（不遮挡色带中央）
        g.fill(barX, y, barX + 8, y + 1, color);
        g.fill(barX + barW - 8, y, barX + barW, y + 1, color);
        g.drawString(f, name + "=" + value, labelX, y - 4, color, false);
    }

    private static double clamp01(double v) {
        return v < 0 ? 0 : (v > 1 ? 1 : v);
    }

    /**
     * 悬停提示（★ v16：示意图只读 ⇒ 无需提示；保留空实现以兼容调用方）。
     */
    public void renderTooltips(GuiGraphics g, int mx, int my) {
        // 有意留空 —— 示意图不可交互，没有"悬停在可点区域"这回事。
    }

    // ---- 鼠标（★ v16：示意图改为<b>只读</b>，不再接受任何拖拽）----
    //
    //   用户要求：「这些示意图改成不可直接交互，仅由 UI 控件配置」。
    //   理由：拖菱形 = 绕过滑块改配置的第二条路径，同一个值两个入口
    //   既容易误操作，也让"改哪里生效"不明确。
    //   现在所有高度只能通过上方的三个滑块修改，示意图纯展示。

    public boolean mouseClicked(double mx, double my, int btn) {
        return false;
    }

    public boolean mouseDragged(double mx, double my, int btn, double dx, double dy) {
        return false;
    }

    public boolean mouseReleased(double mx, double my, int btn) {
        return false;
    }
}
