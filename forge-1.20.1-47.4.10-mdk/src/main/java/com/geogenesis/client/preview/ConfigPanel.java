package com.geogenesis.client.preview;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.sounds.SimpleSoundInstance;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundEvents;

/**
 * 配置屏各页签面板的抽象基类。
 *
 * <p>采用与主屏 {@code GeoGenesisConfigScreen} 一致的「手动渲染框架」：
 * 面板不进入 MC 的 widget 列表，由主屏通过 {@code enableScissor} 裁剪后，
 * 直接调用 {@link #render}/{@link #mouseClicked} 等方法分发事件。
 *
 * <p>子类需实现 {@link #getHeight}/{@link #render}/{@link #mouseClicked}/
 * {@link #mouseReleased}/{@link #mouseDragged}；{@link #mouseScrolled} 默认返回
 * {@code false}（简单面板无需覆盖，含内部滚动列表的面板可覆盖）。
 */
public abstract class ConfigPanel {

    // —— 共享深色主题配色（与主屏 / ParamSlider 一致）——
    protected static final int C_BG_PANEL = 0xFF1e222c;
    protected static final int C_BG_ROW   = 0xFF15191F;
    protected static final int C_BG_BTN   = 0xFF1A1E24;
    protected static final int C_HOVER    = 0xFF252A33;
    protected static final int C_ACCENT   = 0xFF00c896;
    protected static final int C_TEXT     = 0xFFe0e0e0;
    protected static final int C_TEXT_DIM = 0xFF999999;
    protected static final int C_LINE     = 0xFF333333;

    /** 面板内容左上角 x（由主屏注入） */
    protected int x, baseY, w;
    /** 主屏外滚偏移（由主屏注入） */
    protected int scrollOffset;
    /** 参数变更回调；设置类面板传入 no-op 即可 */
    protected Runnable onMarkDirty = () -> {};

    /** 悬停 tooltip：子类在 render 中若鼠标悬停在带说明的控件上则设置，主屏在 scissor 外绘制 */
    protected Component hoverTooltip = null;

    public void setBounds(int x, int y, int w) { this.x = x; this.baseY = y; this.w = w; }
    public void setScrollOffset(int off) { this.scrollOffset = off; }
    public void setOnMarkDirty(Runnable r) { if (r != null) this.onMarkDirty = r; }

    /** 内容顶部（已扣除滚动偏移），所有子控件 y 基于此计算 */
    protected int top() { return baseY - scrollOffset; }

    /** 返回当前面板内容总高度（用于主屏计算外滚范围） */
    public abstract int getHeight();

    public abstract void render(GuiGraphics g, int mx, int my);

    public abstract boolean mouseClicked(double mx, double my, int btn);
    public abstract boolean mouseReleased(double mx, double my, int btn);
    public abstract boolean mouseDragged(double mx, double my, int btn, double dx, double dy);

    /** 默认不消费滚动（子类如含内部滚动列表可覆盖）。delta 为滚动量（正=向下） */
    public boolean mouseScrolled(double mx, double my, double delta) { return false; }

    /** 主屏在 scissor 外调用，绘制悬停 tooltip；读取后清空 */
    public Component consumeHoverTooltip() {
        Component t = hoverTooltip;
        hoverTooltip = null;
        return t;
    }

    /** 统一的点击音效（面板按钮 / 列表项选中时调用），提升操作反馈手感 */
    protected static void playClick() {
        Minecraft.getInstance().getSoundManager().play(SimpleSoundInstance.forUI(SoundEvents.UI_BUTTON_CLICK, 1.0F));
    }

    protected static net.minecraft.client.gui.Font font() {
        return Minecraft.getInstance().font;
    }

    /** 点是否落在矩形内 */
    protected static boolean hit(int x, int y, int w, int h, double mx, double my) {
        return mx >= x && mx <= x + w && my >= y && my <= y + h;
    }

    /** 绘制标题行（左对齐白字） */
    protected static void drawHeader(GuiGraphics g, int x, int y, String title) {
        g.drawString(font(), title, x, y, C_TEXT);
    }

    /**
     * 绘制「标签 + 右侧开关胶囊」行。开=青绿填充+深色字，关=暗底+次级字。
     * @return 该行是否处于 hover（供调用方决定是否消费事件）
     */
    protected static boolean drawToggleRow(GuiGraphics g, int x, int y, int w, int h,
                                            String label, boolean on, int mx, int my) {
        boolean hover = hit(x, y, w, h, mx, my);
        g.fill(x, y, x + w, y + h, hover ? C_HOVER : C_BG_ROW);
        g.fill(x, y, x + 2, y + h, C_LINE);
        Font f = font();
        g.drawString(f, label, x + 8, y + (h - f.lineHeight) / 2, C_TEXT);
        int pillW = 64, pillH = 16;
        int px = x + w - pillW - 8, py = y + (h - pillH) / 2;
        g.fill(px, py, px + pillW, py + pillH, on ? C_ACCENT : C_BG_BTN);
        String txt = on ? "开" : "关";
        g.drawString(f, txt, px + (pillW - f.width(txt)) / 2, py + (pillH - f.lineHeight) / 2,
            on ? 0xFF0e1218 : C_TEXT_DIM);
        return hover;
    }

    /**
     * 绘制胶囊按钮。active=青绿高亮+深色字，否则暗底+亮字，hover 提亮。
     * @return 是否 hover
     */
    protected static boolean drawButton(GuiGraphics g, int x, int y, int w, int h,
                                        String label, boolean active, int mx, int my) {
        boolean hover = hit(x, y, w, h, mx, my);
        int bg = active ? C_ACCENT : (hover ? C_HOVER : C_BG_BTN);
        g.fill(x, y, x + w, y + h, bg);
        Font f = font();
        g.drawCenteredString(f, label, x + w / 2, y + (h - f.lineHeight) / 2, active ? 0xFF0e1218 : C_TEXT);
        return hover;
    }
}

