package com.geogenesis.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import com.geogenesis.client.preview.UiKit;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;

import java.util.function.Consumer;

/**
 * 命名输入对话框覆盖层（供「保存为预设」输入预设名）。由 GeoGenesisConfigScreen 拥有和渲染。
 *
 * <p>显示标题 + 提示 + 文本输入框 + 确定/取消按钮。ESC 取消，Enter 确认（名称非空）。
 * 键盘事件需由宿主屏转发到 {@link #keyPressed}/{@link #charTyped}（详见 GeoGenesisConfigScreen）。</p>
 */
public class NameInputDialog {

    private boolean showing;
    private String title;
    private String prompt;
    private EditBox nameBox;
    private Consumer<String> onConfirm;
    private Runnable onCancel;

    private static final int DIALOG_W = 300;
    private static final int DIALOG_H = 150;
    private static final int BTN_W = 80;
    private static final int BTN_H = 20;

    public NameInputDialog() {}

    public void show(String title, String prompt, Consumer<String> onConfirm, Runnable onCancel) {
        show(title, prompt, "", onConfirm, onCancel);
    }

    /**
     * 带<b>初始值</b>的版本（★ v19：用于"点击数值胶囊 → 修改数值"）。
     *
     * <p>空输入框让用户重打一遍当前值很别扭；预填后可以直接改一位数字再回车。</p>
     */
    public void show(String title, String prompt, String initialValue,
                     Consumer<String> onConfirm, Runnable onCancel) {
        this.title = title;
        this.prompt = prompt;
        this.onConfirm = onConfirm;
        this.onCancel = onCancel;
        this.showing = true;

        int dx = dx();
        int dy = dy();
        nameBox = new EditBox(Minecraft.getInstance().font, dx + 12, dy + 52, DIALOG_W - 24, 18,
                Component.literal("输入"));
        nameBox.setMaxLength(64);
        nameBox.setValue(initialValue == null ? "" : initialValue);
        nameBox.setFocused(true);
        // 光标移到末尾，方便直接删改
        nameBox.moveCursorToEnd();
    }

    public void hide() {
        showing = false;
        onConfirm = null;
        onCancel = null;
    }

    public boolean isShowing() {
        return showing;
    }

    private int dx() {
        return (Minecraft.getInstance().getWindow().getGuiScaledWidth() - DIALOG_W) / 2;
    }

    private int dy() {
        return (Minecraft.getInstance().getWindow().getGuiScaledHeight() - DIALOG_H) / 2;
    }

    // ===== 渲染 =====

    public void render(GuiGraphics g, int mx, int my) {
        if (!showing) return;

        int sw = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        int sh = Minecraft.getInstance().getWindow().getGuiScaledHeight();
        int dx = dx();
        int dy = dy();

        // 全屏遮罩：接近不透明(0xE0≈88%)，彻底压住背后配置屏密集文字，避免其透过遮罩浮在弹窗前面
        g.fill(0, 0, sw, sh, 0xE0000000);

        // ★ v7：改走 UiKit（圆角卡片 + 胶囊按钮 + 无描边输入框）
        UiKit.roundRect(g, dx, dy, dx + DIALOG_W, dy + DIALOG_H, UiKit.RADIUS_CARD, 0xF0181C20);
        UiKit.roundRectOutline(g, dx, dy, dx + DIALOG_W, dy + DIALOG_H, UiKit.RADIUS_CARD, 0x22FFFFFF);

        var f = Minecraft.getInstance().font;
        g.drawString(f, title, dx + 12, dy + 10, UiKit.C_TXT, false);
        g.drawString(f, prompt, dx + 12, dy + 28, UiKit.C_TXT_SUB, false);

        // EditBox 区域：圆角底 + 极淡描边（新版控件靠底色差，不用亮色框）
        int boxX = dx + 12, boxY = dy + 52, boxW = DIALOG_W - 24, boxH = 18;
        UiKit.roundRect(g, boxX, boxY, boxX + boxW, boxY + boxH, UiKit.RADIUS_FIELD, 0xFF2A2A2A);
        UiKit.roundRectOutline(g, boxX, boxY, boxX + boxW, boxY + boxH, UiKit.RADIUS_FIELD, 0xFF3A3A3A);

        nameBox.render(g, mx, my, 0);

        int btnY = dy + DIALOG_H - 30;
        int cancelX = dx + 20;
        boolean hoverCancel = mx >= cancelX && mx <= cancelX + BTN_W && my >= btnY && my <= btnY + BTN_H;
        UiKit.button(g, cancelX, btnY, BTN_W, BTN_H, "取消", false, hoverCancel);

        int confirmX = dx + DIALOG_W - 20 - BTN_W;
        boolean hoverConfirm = mx >= confirmX && mx <= confirmX + BTN_W && my >= btnY && my <= btnY + BTN_H;
        UiKit.button(g, confirmX, btnY, BTN_W, BTN_H, "确定", true, hoverConfirm);
    }

    // ===== 鼠标 =====

    public boolean mouseClicked(double mx, double my, int btn) {
        if (!showing || btn != 0) return false;

        int dx = dx();
        int dy = dy();
        int btnY = dy + DIALOG_H - 30;

        // 输入框点击：交给 EditBox（消费，阻止穿透）
        int boxX = dx + 12, boxY = dy + 52, boxW = DIALOG_W - 24, boxH = 18;
        if (mx >= boxX && mx <= boxX + boxW && my >= boxY && my <= boxY + boxH) {
            nameBox.setFocused(true);
            nameBox.mouseClicked(mx, my, btn);
            return true;
        }

        int cancelX = dx + 20;
        if (mx >= cancelX && mx <= cancelX + BTN_W && my >= btnY && my <= btnY + BTN_H) {
            showing = false;
            if (onCancel != null) onCancel.run();
            onCancel = null;
            onConfirm = null;
            return true;
        }

        int confirmX = dx + DIALOG_W - 20 - BTN_W;
        if (mx >= confirmX && mx <= confirmX + BTN_W && my >= btnY && my <= btnY + BTN_H) {
            return confirm();
        }

        if (mx < dx || mx > dx + DIALOG_W || my < dy || my > dy + DIALOG_H) {
            showing = false;
            if (onCancel != null) onCancel.run();
            onCancel = null;
            onConfirm = null;
            return true;
        }
        return true;
    }

    // ===== 键盘 =====

    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (!showing) return false;
        if (keyCode == 256) { // ESC
            showing = false;
            if (onCancel != null) onCancel.run();
            onCancel = null;
            onConfirm = null;
            return true;
        }
        if (keyCode == 257) { // Enter
            return confirm();
        }
        return nameBox.keyPressed(keyCode, scanCode, modifiers);
    }

    public boolean charTyped(char codePoint, int modifiers) {
        if (!showing) return false;
        return nameBox.charTyped(codePoint, modifiers);
    }

    private boolean confirm() {
        String v = nameBox.getValue().trim();
        if (v.isEmpty()) return true; // 名称空不允许确认，但消费事件
        showing = false;
        if (onConfirm != null) onConfirm.accept(v);
        onCancel = null;
        onConfirm = null;
        return true;
    }
}
