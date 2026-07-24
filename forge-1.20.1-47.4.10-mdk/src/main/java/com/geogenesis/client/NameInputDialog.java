package com.geogenesis.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
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
        this.title = title;
        this.prompt = prompt;
        this.onConfirm = onConfirm;
        this.onCancel = onCancel;
        this.showing = true;

        int dx = dx();
        int dy = dy();
        nameBox = new EditBox(Minecraft.getInstance().font, dx + 12, dy + 52, DIALOG_W - 24, 18,
                Component.literal("预设名称"));
        nameBox.setMaxLength(64);
        nameBox.setValue("");
        nameBox.setFocused(true);
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

        g.fill(0, 0, sw, sh, 0x88000000);

        g.fill(dx, dy, dx + DIALOG_W, dy + DIALOG_H, 0xFF1a1f28);
        g.fill(dx, dy, dx + DIALOG_W, dy + 1, 0xFF00c896);
        g.fill(dx, dy + DIALOG_H - 1, dx + DIALOG_W, dy + DIALOG_H, 0xFF333333);
        g.fill(dx, dy, dx + 1, dy + DIALOG_H, 0xFF333333);
        g.fill(dx + DIALOG_W - 1, dy, dx + DIALOG_W, dy + DIALOG_H, 0xFF333333);

        var f = Minecraft.getInstance().font;
        g.drawString(f, title, dx + 10, dy + 8, 0xFFFFAA00);
        g.drawString(f, prompt, dx + 10, dy + 28, 0xFFCCCCCC);

        nameBox.render(g, mx, my, 0);

        int btnY = dy + DIALOG_H - 30;
        int cancelX = dx + 20;
        boolean hoverCancel = mx >= cancelX && mx <= cancelX + BTN_W && my >= btnY && my <= btnY + BTN_H;
        g.fill(cancelX, btnY, cancelX + BTN_W, btnY + BTN_H, hoverCancel ? 0xFF3a4050 : 0xFF2a2f3a);
        g.fill(cancelX, btnY, cancelX + 1, btnY + BTN_H, 0xFF4a5060);
        g.fill(cancelX + BTN_W - 1, btnY, cancelX + BTN_W, btnY + BTN_H, 0xFF4a5060);
        g.drawString(f, "取消", cancelX + (BTN_W - f.width("取消")) / 2, btnY + 6, 0xFFCC6666);

        int confirmX = dx + DIALOG_W - 20 - BTN_W;
        boolean hoverConfirm = mx >= confirmX && mx <= confirmX + BTN_W && my >= btnY && my <= btnY + BTN_H;
        g.fill(confirmX, btnY, confirmX + BTN_W, btnY + BTN_H, hoverConfirm ? 0xFF00a876 : 0xFF008060);
        g.fill(confirmX, btnY, confirmX + 1, btnY + BTN_H, 0xFF00c896);
        g.fill(confirmX + BTN_W - 1, btnY, confirmX + BTN_W, btnY + BTN_H, 0xFF00c896);
        g.drawString(f, "确定", confirmX + (BTN_W - f.width("确定")) / 2, btnY + 6, 0xFFFFFFFF);
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
