package com.geogenesis.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
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

    private static final int SLIDER_TRACK   = 0xFF1e222c;
    private static final int SLIDER_FG      = 0xFF00c896;
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

    /** 重置按钮宽度（在滑块外部右侧） */
    public static final int RESET_BTN_W = 18;
    /** 重置按钮与滑块之间的间距 */
    public static final int RESET_GAP = 6;

    public ParamSlider(int x, int y, int width, double min, double max, double value,
                       Consumer<Double> onChange, Function<Double, String> formatter) {
        super(x, y, width, 18, Component.literal(""), clamp01((value - min) / (max - min)));
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

    /** 设置悬停说明文本（null 表示无） */
    public void setTooltipText(String text) {
        this.tooltip = (text == null) ? null : Component.literal(text);
    }

    public Component getTooltipText() { return tooltip; }

    public void setDefaultValue(double v) { this.defaultValue = v; }

    public void setOnReset(Runnable r) { this.onReset = r; }

    /** 重置到默认值，同时触发 onChange 以回写配置/后端状态 */
    public void resetToDefault() {
        this.current = defaultValue;
        this.value = clamp01((defaultValue - min) / (max - min));
        updateMessage();
        if (onChange != null) onChange.accept(defaultValue);
        if (onReset != null) onReset.run();
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        boolean handled = super.mouseClicked(mouseX, mouseY, button);
        if (handled) {
            this.setFocused(true);
        }
        return handled;
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double dragX, double dragY) {
        if (this.isFocused()) {
            // 利用已有的 onClick/setValueFromMouse 逻辑，直接用鼠标 X 坐标更新值
            this.onClick(mouseX, mouseY);
            return true;
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        if (this.isFocused()) {
            this.setFocused(false);
        }
        return super.mouseReleased(mouseX, mouseY, button);
    }



    @Override
    public void renderWidget(GuiGraphics g, int mouseX, int mouseY, float partialTick) {
        // ── 滑块轨道背景 ──
        int trackY = getY() + 5;
        int trackH = 8;
        g.fill(getX(), trackY, getX() + getWidth(), trackY + trackH, SLIDER_TRACK);

        // ── 已填充部分（青绿色） ──
        int fillW = (int) (getWidth() * this.value);
        if (fillW > 0) {
            g.fill(getX(), trackY, getX() + fillW, trackY + trackH, SLIDER_FG);
        }

        // ── 滑块按钮 ──
        int btnW = 8;
        int btnX = getX() + fillW - btnW / 2;
        int btnY = trackY - 2;
        int btnH = trackH + 4;
        g.fill(btnX, btnY, btnX + btnW, btnY + btnH, 0xFFe0e0e0);

        // ── 数值文本（左对齐，在滑轨上方避免与拖拽手柄重叠导致重影） ──
        var f = Minecraft.getInstance().font;
        String text = this.getMessage().getString();
        int textX = getX() + 4;
        int textY = getY() - 2;
        g.drawString(f, text, textX, textY, TEXT_COLOR);

        // ── 重置按钮（在滑块外部右侧，有间距） ──
        int rbX = getX() + getWidth() + RESET_GAP;
        int rbY = getY() + 2;
        int rbH = getHeight() - 4;
        boolean hoverReset = mouseX >= rbX && mouseX <= rbX + RESET_BTN_W && mouseY >= rbY && mouseY <= rbY + rbH;
        // 背景
        int rbBg = hoverReset ? RESET_HOVER_BG : RESET_BG;
        g.fill(rbX, rbY, rbX + RESET_BTN_W, rbY + rbH, rbBg);
        g.fill(rbX, rbY, rbX + 1, rbY + rbH, RESET_BORDER);        // 左边框
        g.fill(rbX + RESET_BTN_W - 1, rbY, rbX + RESET_BTN_W, rbY + rbH, RESET_BORDER); // 右边框
        // 图标文字
        String resetIcon = "↩";
        int iconW = f.width(resetIcon);
        int iconX = rbX + (RESET_BTN_W - iconW) / 2;
        int iconY = rbY + (rbH - 8) / 2;
        g.drawString(f, resetIcon, iconX, iconY, hoverReset ? 0xFF00c896 : 0xFF999999);
    }

    /** 悬停在重置按钮上时返回 true */
    public boolean isHoveringReset(int mouseX, int mouseY) {
        int rbX = getX() + getWidth() + RESET_GAP;
        int rbY = getY() + 2;
        int rbH = getHeight() - 4;
        return mouseX >= rbX && mouseX <= rbX + RESET_BTN_W && mouseY >= rbY && mouseY <= rbY + rbH;
    }

    /** 由父面板在所有滑块渲染完成后调用，绘制 tooltip 覆盖层 */
    public void renderTooltip(GuiGraphics g, int mouseX, int mouseY) {
        if (tooltip != null && isMouseOver(mouseX, mouseY)) {
            var f = Minecraft.getInstance().font;
            String tip = tooltip.getString();
            int tw = f.width(tip) + 10;
            int tx = Math.min(mouseX + 10, getX() + getWidth() + RESET_BTN_W + RESET_GAP - tw);
            
            // 固定 tooltip 在鼠标上方显示
            int tooltipH = 14;
            int ty = mouseY - tooltipH - 8;
            
            // 确保 tooltip 不超出屏幕顶部
            if (ty < 0) {
                ty = mouseY + 20;
            }
            
            g.fill(tx, ty, tx + tw, ty + tooltipH, 0xFF000000);
            g.drawString(f, tip, tx + 5, ty + 3, TEXT_COLOR);
        }
    }
}
