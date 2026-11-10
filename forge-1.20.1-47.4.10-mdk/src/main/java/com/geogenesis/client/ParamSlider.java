package com.geogenesis.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import com.geogenesis.client.preview.UiKit;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;
import java.util.function.Function;

/**
 * 通用参数滑块：值在 [min,max] 线性映射到 [0,1]。
 * 拖拽时通过 onChange 实时回写 live 参数。
 * 自定义深色主题渲染，与 GeoGenesisConfigScreen 配色一致。
 */
public class ParamSlider extends AbstractSliderButton {

    private static final int SLIDER_TRACK   = 0xFF3A4450;
    private static final int SLIDER_FG      = 0xFF48DE56;
    private static final int TEXT_COLOR     = 0xFFe0e0e0;
    private static final int TOOLTIP_BG     = 0xEE000000;
    private static final int RESET_BG       = 0xFF2a2f3a;
    private static final int RESET_HOVER_BG = 0xFF3a4050;
    private static final int RESET_BORDER   = 0xFF4a5060;

    private final double min;
    private final double max;
    private final Consumer<Double> onChange;
    private final Function<Double, String> formatter;
    private double current;
    private double defaultValue;
    private Component tooltip = null;
    private Runnable onReset = null;
    private Runnable onValueCommitted = null;
    private Runnable onDragStart = null;
    /** 是否渲染数值文本（默认true；紧凑布局可设为false，由父面板统一渲染） */
    private boolean showValueText = true;
    /** 是否渲染重置按钮（默认true；紧凑布局可设为false） */
    private boolean showResetButton = true;

    /** 重置按钮宽度（在滑块外部右侧） */
    public static final int RESET_BTN_W = 18;
    /** 重置按钮与滑块之间的间距 */
    public static final int RESET_GAP = 6;

    public ParamSlider(int x, int y, int width, double min, double max, double value,
                       Consumer<Double> onChange, Function<Double, String> formatter) {
        // ★ v14：本体高度改用统一常量（原写死 18，与各面板的 SLIDER_H 20 不一致，
        //   导致同一控件在不同页里"看起来不是一套东西"）
        super(x, y, width, com.geogenesis.client.preview.UiKit.SLIDER_H,
                Component.literal(""), clamp01((value - min) / (max - min)));
        this.min = min;
        this.max = max;
        this.onChange = onChange;
        this.formatter = formatter;
        this.current = value;
        this.defaultValue = value;
        updateMessage();
    }

    private static double clamp01(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    @Override
    protected void updateMessage() {
        setMessage(Component.literal(formatter.apply(current)));
    }

    @Override
    protected void applyValue() {
        this.current = min + (max - min) * this.value;
        if (onChange != null) onChange.accept(current);
    }

    /** 设置显示值（不触发 onChange），用于外部钳制后回写滑块位置 */
    public void setCurrentValue(double v) {
        v = Math.max(min, Math.min(max, v));
        this.current = v;
        this.value = clamp01((v - min) / (max - min));
        updateMessage();
    }

    /** 获取当前值 */
    public double getCurrentValue() { return current; }

    /** 设置悬停说明文本（null 表示无） */
    public void setTooltipText(String text) {
        this.tooltip = (text == null) ? null : Component.literal(text);
    }

    public Component getTooltipText() { return tooltip; }

    public void setDefaultValue(double v) { this.defaultValue = v; }

    /** 设置是否渲染数值文本（紧凑布局设为 false，由父面板统一渲染） */
    public void setShowValueText(boolean v) { this.showValueText = v; }

    /** 设置是否渲染重置按钮（紧凑布局设为 false） */
    public void setShowResetButton(boolean v) { this.showResetButton = v; }

    public void setOnReset(Runnable r) { this.onReset = r; }
    public void setOnValueCommitted(Runnable r) { this.onValueCommitted = r; }
    public void setOnDragStart(Runnable r) { this.onDragStart = r; }

    /** 重置到默认值，同时触发 onChange 以回写配置/后端状态 */
    public void resetToDefault() {
        this.current = defaultValue;
        this.value = clamp01((defaultValue - min) / (max - min));
        updateMessage();
        if (onChange != null) onChange.accept(defaultValue);
        if (onReset != null) onReset.run();
        if (onValueCommitted != null) onValueCommitted.run();
    }

    /**
     * <b>点击</b>（★ v19：收紧命中范围，修"鼠标不在滑动点上也响应"）。
     *
     * <h2>为什么必须收窄</h2>
     * <p>{@code AbstractSliderButton.mouseClicked} 只判"落在整个控件矩形内"，
     * 而本控件高 20px、滑轨只有 6px ⇒ 用户在轨道<b>上方/下方 7px</b> 点一下
     * 也会被当成"点到滑块"并立刻把值跳到鼠标位置（用户反馈的"误触"）。
     * 而且右侧还有值胶囊和重置键，那两块本来就不该触发拖动。</p>
     *
     * <p>现在只接受三种命中：<b>滑轨条带（±5px）</b>、<b>手柄圆</b>、<b>值胶囊</b>（后两者见各自分支）。
     * 落在行内的其它高度一律不响应，避免误拖。</p>
     */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        // ★ v19：值胶囊区域 → 请求"手工输入"（由面板查 textEditRequested() 弹输入框）
        if (isOverValueChip(mouseX, mouseY)) {
            editRequested = true;
            return true;
        }
        if (!trackHit(mouseX, mouseY)) return false;
        // ★ v20：不再调 super.mouseClicked —— 它用"整个控件宽度"映射数值，
        //   与我们的「滑轨 | 值胶囊 | 重置键」布局不同源，会造成点击位置偏移。
        this.value = valueFromMouseX(mouseX);
        this.applyValue();
        this.setFocused(true);
        if (onDragStart != null) onDragStart.run();
        return true;
    }

    /**
     * 值胶囊是否被点击 —— <b>一次性</b>读取（读后清除）。
     *
     * <p>★ v19：用户要求「右侧的数值显示可以做成可点击写入数值，这样能滑能填」。
     * 本类不持有 Screen 上下文，弹不出输入框；因此只登记"本次点击落在胶囊上"，
     * 由持有面板在 {@code mouseClicked} 里检查并弹出编辑框。</p>
     */
    public boolean consumeTextEditRequest() {
        boolean r = editRequested;
        editRequested = false;
        return r;
    }

    private boolean editRequested = false;

    /** 值胶囊的矩形（命中与绘制同源）。 */
    public int[] valueChipRect() {
        if (!showValueText) return null;
        var f = Minecraft.getInstance().font;
        String txt = this.getMessage().getString();
        int maxPillW = Math.max(28, (int) (getWidth() * 0.45f));
        String shown = f.width(txt) + 14 > maxPillW ? UiKit.clip(txt, maxPillW - 14) : txt;
        int pillW = Math.min(maxPillW, f.width(shown) + 14);
        int resetW = showResetButton ? RESET_BTN_W + RESET_GAP : 0;
        int pillH = Math.min(UiKit.CHIP_H, getHeight() - 2);
        int px = getX() + getWidth() - pillW - resetW;
        int py = getY() + (getHeight() - pillH) / 2;
        return new int[]{px, py, pillW, pillH};
    }

    /** 鼠标是否在值胶囊上。 */
    public boolean isOverValueChip(double mx, double my) {
        int[] r = valueChipRect();
        if (r == null) return false;
        return mx >= r[0] && mx <= r[0] + r[2] && my >= r[1] && my <= r[1] + r[3];
    }

    /** 直接写入数值（供面板的输入框回调使用；会钳制到范围并触发 onChange）。 */
    public void setValueFromInput(double v) {
        double clamped = Math.max(min, Math.min(max, v));
        boolean isInt = (getHeight() > 0) && isIntegerLike();
        this.current = isInt ? Math.round(clamped) : clamped;
        this.value = clamp01((this.current - min) / (max - min));
        updateMessage();
        if (onChange != null) onChange.accept(this.current);
        if (onValueCommitted != null) onValueCommitted.run();
    }

    /** 本滑块是否整数键（域范围与默认值都是整数 ⇒ 视为 int）。 */
    private boolean isIntegerLike() {
        return Math.abs(max - Math.round(max)) < 1e-9
            && Math.abs(min - Math.round(min)) < 1e-9
            && Math.abs(defaultValue - Math.round(defaultValue)) < 1e-9;
    }

    /**
     * 鼠标是否落在<b>滑轨条带</b>内（不含值胶囊与重置键）。
     *
     * <p>条带 = 垂直方向 ±{@link #HIT_BAND} 像素（比 6px 轨道宽，好点中，
     * 但远小于整个 20px 行高），水平方向到滑轨结束为止。</p>
     */
    public boolean trackHit(double mx, double my) {
        int cy = getY() + getHeight() / 2;
        if (my < cy - HIT_BAND || my > cy + HIT_BAND) return false;
        int trackRight = trackRightX();
        return mx >= getX() - 2 && mx <= trackRight + 2;
    }

    /** 滑轨右端 x（值胶囊与重置键都在这之后）。 */
    private int trackRightX() {
        int maxPillW = Math.max(28, (int) (getWidth() * 0.45f));
        String txt = this.getMessage().getString();
        var f = Minecraft.getInstance().font;
        String shown = f.width(txt) + 14 > maxPillW ? UiKit.clip(txt, maxPillW - 14) : txt;
        int pillW = Math.min(maxPillW, f.width(shown) + 14);
        int resetW = showResetButton ? RESET_BTN_W + RESET_GAP : 0;
        int textW = showValueText ? pillW + 6 + resetW : resetW;
        return getX() + Math.max(20, getWidth() - textW);
    }

    /** 命中条带的半高（★ v19：6px 轨道给到 ±5px 的容错，仍远小于 20px 行高）。 */
    private static final int HIT_BAND = 5;

    /**
     * <b>把鼠标 x 映射为 0..1 的值</b>（★ v20：修"点击位置与手柄偏移"）。
     *
     * <h2>根因</h2>
     * <p>此前我们在 {@code mouseClicked/mouseDragged} 里调 {@code super.onClick(mx,my)}，
     * 那是 {@code AbstractSliderButton} 的实现 —— 它把<b>整个控件宽度</b>
     * （{@code getX() .. getX()+getWidth()}）映射到 0..1。
     * 但本控件的布局是「滑轨 | 值胶囊 | 重置键」，
     * 滑轨只占左侧一段（{@link #trackRightX()} 到那里为止）⇒
     * 鼠标、手柄、数值三者用的是不同坐标系，必然错位：
     * 点在滑轨右端时值已经超出 1，点在滑轨中段却映射到 0.7 之类。</p>
     *
     * <p>现在<b>不再用 super.onClick</b>，改由本方法按滑轨实际范围换算，
     * 保证「点哪里 → 手柄就在哪里」。</p>
     */
    private double valueFromMouseX(double mouseX) {
        int trackLeft = getX();
        int trackRight = trackRightX();
        int span = Math.max(1, trackRight - trackLeft);
        return clamp01((mouseX - trackLeft) / span);
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (this.isFocused()) {
            this.value = valueFromMouseX(mouseX);
            this.applyValue();
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (this.isFocused()) {
            this.setFocused(false);
            if (onValueCommitted != null) onValueCommitted.run();
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }



    @Override
    public void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        String text = this.getMessage().getString();
        boolean overTrack = isMouseOver(mouseX, mouseY);
        var f = Minecraft.getInstance().font;

        // ★ v8：值胶囊宽度必须<b>封顶</b>（≤ 控件宽的 45%）。
        int maxPillW = Math.max(28, (int) (getWidth() * 0.45f));
        String shown = text;
        if (f.width(shown) + 14 > maxPillW) {
            shown = UiKit.clip(text, maxPillW - 14);
        }
        int pillW = Math.min(maxPillW, f.width(shown) + 14);

        // ★★ v18【重置键改为<b>常驻</b>显示】★★
        //   上一版只在"值偏离默认"时才画按钮 —— 用户反馈「不然看不出这行能重置」。
        //   现在<b>永远占位</b>：未改动时置灰（不可点、无悬停高亮），改动后点亮。
        //   这样每行右侧的结构恒定，用户一眼就知道"这里有个可以回默认值的键"。
        //   ⚠ 位置必须在<b>控件内部</b>最右端：各面板给滑块的宽度已吃满可用空间，
        //     放在 getX()+getWidth() 之外会被推出面板（旧实现的隐患）。
        boolean dirty = Math.abs(current - defaultValue) > 1e-9;
        int resetW = showResetButton ? RESET_BTN_W + RESET_GAP : 0;
        int textW = showValueText ? pillW + 6 + resetW : resetW;
        int trackW = Math.max(20, getWidth() - textW);

        UiKit.slider(g, getX(), getY(), trackW, getHeight(),
                this.value, null, 0xFF48DE56, overTrack);

        // 值胶囊
        if (showValueText) {
            int pillH = Math.min(UiKit.CHIP_H, getHeight() - 2);
            int px = getX() + getWidth() - pillW - resetW;
            int py = getY() + (getHeight() - pillH) / 2;
            UiKit.valueChip(g, px, py, pillW, pillH, shown, overTrack);
        }

        // 重置按钮（★ v18：常驻；未改动静默态，改动后可点）
        if (showResetButton) {
            int rbX = getX() + getWidth() - RESET_BTN_W;
            int rbH = Math.min(UiKit.CHIP_H, getHeight() - 2);
            int rbY = getY() + (getHeight() - rbH) / 2;
            boolean hoverReset = dirty && isHoveringReset(mouseX, mouseY);
            // 未改动：很淡的底 + 更暗的字（"存在但当前无事可做"）
            int bg = !dirty ? 0x0CFFFFFF : (hoverReset ? 0xFF3E3E3E : 0xFF2A2A2A);
            int fg = !dirty ? 0xFF4A4A4A : (hoverReset ? UiKit.C_ACCENT : UiKit.C_TXT_DIM);
            UiKit.roundRect(g, rbX, rbY, rbX + RESET_BTN_W, rbY + rbH,
                    UiKit.RADIUS_FIELD, bg);
            String icon = "R";
            g.drawString(f, icon, rbX + (RESET_BTN_W - f.width(icon)) / 2,
                    rbY + (rbH - f.lineHeight) / 2, fg, false);
        }
    }

    /**
     * 悬停在重置按钮上时返回 true（★ v17：命中区改到<b>控件内部右端</b>）。
     *
     * <p>⚠ 必须与 {@link #renderWidget} 的绘制位置<b>同源</b>：
     * 旧实现把命中区放在 {@code getX()+getWidth()}+GAP（控件外部），
     * 而各面板的滑块宽度已吃满可用空间 ⇒ 那个位置落在面板之外，
     * 即使画出来也点不到。现在两边都在控件内部右端。</p>
     *
     * <p>★ v18：按钮<b>常驻</b>显示，但只有在值偏离默认值时才<b>可点</b> ——
     * 未改动时置灰且不响应点击（"存在但当前无事可做"）。
     * 这样用户能看见"这行可以重置"，又不会误点一个无效果的按钮。</p>
     */
    public boolean isHoveringReset(int mouseX, int mouseY) {
        if (!showResetButton) return false;
        if (Math.abs(current - defaultValue) <= 1e-9) return false;   // 未改动 ⇒ 不可点
        int rbX = getX() + getWidth() - RESET_BTN_W;
        int rbH = Math.min(UiKit.CHIP_H, getHeight() - 2);
        int rbY = getY() + (getHeight() - rbH) / 2;
        return mouseX >= rbX && mouseX <= rbX + RESET_BTN_W
                && mouseY >= rbY && mouseY <= rbY + rbH;
    }

    /**
     * 登记悬停提示（★ v15：<b>不再自己绘制</b>）。
     *
     * <p>旧实现画的是「直角纯黑方块 + 单行不折行」，与屏幕级
     * {@code drawHoverTooltip}（圆角/折行/越界翻边）完全是两套外观 ——
     * 用户在不同页签看到"新旧不一致"正是这个。
     * 现在只把文本交给 {@link UiKit.Hover}，由屏幕统一在最后一层绘制。</p>
     */
    public void renderTooltip(GuiGraphics g, int mouseX, int mouseY) {
        if (tooltip != null && isMouseOver(mouseX, mouseY)) {
            UiKit.Hover.report(tooltip.getString(), mouseX, mouseY);
        }
    }
}
