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

    // —— 共享配色（★ v7：改为委托 UiKit，避免每个面板各持一套旧色）——
    //   旧值 0xFF1e222c / 0xFF00c896 是早期深蓝主题，与新参考图的
    //   中性灰 + 地形绿 冲突。全部指向 UiKit 后才能"改一处、全局一致"。
    protected static final int C_BG_PANEL = 0xFF1E1E1E;
    protected static final int C_BG_ROW   = 0xFF232323;
    protected static final int C_BG_BTN   = 0xFF333333;
    protected static final int C_HOVER    = 0xFF3A3A3A;
    protected static final int C_ACCENT   = 0xFF48DE56;
    protected static final int C_TEXT     = 0xFFF2F2F2;
    protected static final int C_TEXT_DIM = 0xFF9E9E9E;
    protected static final int C_LINE     = 0xFF2A2A2A;

    /** 面板内容左上角 x（由主屏注入） */
    protected int x, baseY, w;
    /** 主屏外滚偏移（由主屏注入） */
    protected int scrollOffset;
    /** 参数变更回调；设置类面板传入 no-op 即可 */
    protected Runnable onMarkDirty = () -> {};

    /** 悬停 tooltip：子类在 render 中若鼠标悬停在带说明的控件上则设置，主屏在 scissor 外绘制 */
    protected Component hoverTooltip = null;

    /**
     * <b>数值输入请求回调</b>（★ v19）。
     *
     * <p>用户要求「右侧的数值显示可以做成可点击写入数值，这样能滑能填」。
     * 面板拿不到 Screen 上下文、弹不出输入框 ⇒ 统一由基类持有这个回调，
     * 屏幕在 {@code init()} 时注入，各面板点值胶囊时调用即可。</p>
     */
    protected TextEditRequester textEdit = null;

    /** 三个参数：显示标题、当前值文本、提交回调（收到用户输入的字符串）。 */
    public interface TextEditRequester {
        void request(String label, String current, java.util.function.Consumer<String> onCommit);
    }

    public void setTextEditRequester(TextEditRequester r) { this.textEdit = r; }

    /**
     * 把某滑块的「值胶囊点击」接到输入框（★ v19：各面板共用的唯一实现）。
     *
     * <p>避免每个面板各写一遍 try/catch + 回调绑定 —— 那种重复正是
     * "换地方就变"的来源。</p>
     *
     * @param slider   目标滑块
     * @param label    弹窗标题（参数名）
     * @param onChange 写入成功后要跑的动作（标脏 / 刷新）
     * @return 是否真的弹出了输入框（未配置回调时返回 false）
     */
    protected boolean requestValueEdit(com.geogenesis.client.ParamSlider slider,
                                       String label, Runnable onChange) {
        if (textEdit == null || slider == null) return false;
        if (!slider.consumeTextEditRequest()) return false;
        textEdit.request(label, String.valueOf(slider.getCurrentValue()), text -> {
            try {
                slider.setValueFromInput(Double.parseDouble(text.trim()));
                if (onChange != null) onChange.run();
            } catch (NumberFormatException ignored) {
                // 非法输入静默忽略（保持原值）
            }
        });
        return true;
    }

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

    /**
     * <b>每帧开头清空悬停状态</b>（★ v22：修"幽灵提示"）。
     *
     * <h2>为什么必需</h2>
     * <p>{@code hoverTooltip} 是"渲染时若悬停则写入"的字段。
     * 若某帧鼠标<b>不</b>在控件上，就没人写它 ⇒ 上一帧的值一直留在字段里。
     * 只要有任何读取方（旧 {@code peekHoverTooltip}、{@code CompositePanel}）
     * 在"没人写"的帧去读，就会拿到<b>过期文本</b> ——
     * 用户看到的就是"指针不在任何控件上，提示却一直显示"。</p>
     *
     * <p>由屏幕在 {@code render()} 最开头对所有面板调用一次，
     * 保证每一帧的 tooltip 只能来自<b>本帧</b>的悬停判定。</p>
     */
    public void beginFrame() {
        hoverTooltip = null;
        // 子面板（CompositePanel）需要一并清空内部状态
        clearFrameState();
    }

    /** 供容器类覆盖：每帧清空自己的子控件悬停状态。 */
    protected void clearFrameState() { }

    /** 只读窥视悬停 tooltip（不清空）。 */
    public Component peekHoverTooltip() { return hoverTooltip; }

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

    /**
     * 面板标题（★ v15：<b>保留签名但标记废弃</b>）。
     *
     * <p>标题现在统一由 {@code ConfigPanelHost.drawPanelTitle} 画（带「— N 项」）。
     * 各面板一律不再调用本方法；保留空实现只是为了不破坏尚未迁移的调用点，
     * 新代码<b>禁止</b>再调用它 —— 否则又会出现"两行同名标题"。</p>
     *
     * @deprecated 标题由 {@code ConfigPanelHost} 统一绘制，不要再调用
     */
    @Deprecated
    protected static void drawHeader(GuiGraphics g, int x, int y, String title) {
        // 有意留空。
    }

    /**
     * 绘制「标签 + 右侧开关」行（★ v7：改走 {@link UiKit}，iOS 风格滑钮）。
     *
     * @return 该行是否处于 hover（供调用方决定是否消费事件）
     */
    protected static boolean drawToggleRow(GuiGraphics g, int x, int y, int w, int h,
                                            String label, boolean on, int mx, int my) {
        boolean hover = hit(x, y, w, h, mx, my);
        if (hover) UiKit.roundRect(g, x, y, x + w, y + h, UiKit.RADIUS_FIELD, 0x14FFFFFF);
        Font f = font();
        g.drawString(f, label, x + 8, y + (h - f.lineHeight) / 2, UiKit.C_TXT, false);
        int sw = 30, sh = 16;
        UiKit.toggle(g, x + w - sw - 8, y + (h - sh) / 2, sw, sh, on, hover, UiKit.C_ACCENT);
        return hover;
    }

    /**
     * 绘制胶囊按钮（★ v7：改走 {@link UiKit}，无描边的真胶囊）。
     *
     * @return 是否 hover
     */
    protected static boolean drawButton(GuiGraphics g, int x, int y, int w, int h,
                                        String label, boolean active, int mx, int my) {
        boolean hover = hit(x, y, w, h, mx, my);
        UiKit.button(g, x, y, w, h, label, active, hover);
        return hover;
    }
}
