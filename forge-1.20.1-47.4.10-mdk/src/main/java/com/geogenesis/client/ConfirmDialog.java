package com.geogenesis.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

import java.util.List;
import java.util.function.Consumer;

/**
 * 确认对话框覆盖层。由 GeoGenesisConfigScreen 拥有和渲染。
 *
 * <p>显示确认/取消两个按钮，确认后执行 onConfirm，取消执行 onCancel。
 * 负责渲染半透明背景 + 对话框 + 影响列表。
 */
public class ConfirmDialog {

    private boolean showing;
    private String title;
    private String message;
    private List<String> affectedItems;
    private Runnable onConfirm;
    private Runnable onCancel;

    private static final int DIALOG_W = 280;
    private static final int DIALOG_H = 160;

    public ConfirmDialog() {}

    public void show(String title, String message, List<String> affectedItems,
                     Runnable onConfirm, Runnable onCancel) {
        this.title = title;
        this.message = message;
        this.affectedItems = affectedItems;
        this.onConfirm = onConfirm;
        this.onCancel = onCancel;
        this.showing = true;
    }

    public void hide() { showing = false; onConfirm = null; onCancel = null; }
    public boolean isShowing() { return showing; }

    // ===== 渲染 =====

    public void render(GuiGraphics g, int mx, int my) {
        if (!showing) return;

        int sw = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        int sh = Minecraft.getInstance().getWindow().getGuiScaledHeight();
        int dx = (sw - DIALOG_W) / 2;
        int dy = (sh - DIALOG_H) / 2;

        // 半透明背景遮罩：接近不透明(0xE0≈88%)，彻底压住背后配置屏文字，避免其浮在弹窗前面
        g.fill(0, 0, sw, sh, 0xE0000000);

        // 对话框背景
        g.fill(dx, dy, dx + DIALOG_W, dy + DIALOG_H, 0xFF1a1f28);
        g.fill(dx, dy, dx + DIALOG_W, dy + 1, 0xFF00c896);      // 顶部绿边框
        g.fill(dx, dy + DIALOG_H - 1, dx + DIALOG_W, dy + DIALOG_H, 0xFF333333);
        g.fill(dx, dy, dx + 1, dy + DIALOG_H, 0xFF333333);
        g.fill(dx + DIALOG_W - 1, dy, dx + DIALOG_W, dy + DIALOG_H, 0xFF333333);

        var f = Minecraft.getInstance().font;

        // 标题
        g.drawString(f, "⚠ 修改" + title, dx + 10, dy + 8, 0xFFFFAA00);

        // 消息（旧值→新值）
        g.drawString(f, message, dx + 10, dy + 26, 0xFFCCCCCC);

        // 影响列表
        if (affectedItems != null && !affectedItems.isEmpty()) {
            g.drawString(f, "影响以下项目（已自动适配）：", dx + 10, dy + 48, 0xFF888888);
            int ay = dy + 62;
            for (String item : affectedItems) {
                g.drawString(f, "• " + item, dx + 18, ay, 0xFF88BBDD);
                ay += 12;
            }
        }

        // 按钮
        int btnY = dy + DIALOG_H - 30;
        int btnW = 80;

        // 取消按钮
        int cancelX = dx + 20;
        boolean hoverCancel = mx >= cancelX && mx <= cancelX + btnW && my >= btnY && my <= btnY + 20;
        g.fill(cancelX, btnY, cancelX + btnW, btnY + 20, hoverCancel ? 0xFF3a4050 : 0xFF2a2f3a);
        g.fill(cancelX, btnY, cancelX + 1, btnY + 20, 0xFF4a5060);
        g.fill(cancelX + btnW - 1, btnY, cancelX + btnW, btnY + 20, 0xFF4a5060);
        g.drawString(f, "取消", cancelX + (btnW - f.width("取消")) / 2, btnY + 6, 0xFFCC6666);

        // 确定按钮
        int confirmX = dx + DIALOG_W - 20 - btnW;
        boolean hoverConfirm = mx >= confirmX && mx <= confirmX + btnW && my >= btnY && my <= btnY + 20;
        g.fill(confirmX, btnY, confirmX + btnW, btnY + 20, hoverConfirm ? 0xFF00a876 : 0xFF008060);
        g.fill(confirmX, btnY, confirmX + 1, btnY + 20, 0xFF00c896);
        g.fill(confirmX + btnW - 1, btnY, confirmX + btnW, btnY + 20, 0xFF00c896);
        g.drawString(f, "确定", confirmX + (btnW - f.width("确定")) / 2, btnY + 6, 0xFFFFFFFF);
    }

    // ===== 鼠标 =====

    /** 点击处理：返回 true 表示消费了事件（阻止穿透） */
    public boolean mouseClicked(double mx, double my, int btn) {
        if (!showing || btn != 0) return false;

        int sw = Minecraft.getInstance().getWindow().getGuiScaledWidth();
        int sh = Minecraft.getInstance().getWindow().getGuiScaledHeight();
        int dx = (sw - DIALOG_W) / 2;
        int dy = (sh - DIALOG_H) / 2;
        int btnY = dy + DIALOG_H - 30;
        int btnW = 80;

        // 取消
        int cancelX = dx + 20;
        if (mx >= cancelX && mx <= cancelX + btnW && my >= btnY && my <= btnY + 20) {
            showing = false;
            if (onCancel != null) onCancel.run();
            onCancel = null; onConfirm = null;
            return true;
        }

        // 确定
        int confirmX = dx + DIALOG_W - 20 - btnW;
        if (mx >= confirmX && mx <= confirmX + btnW && my >= btnY && my <= btnY + 20) {
            showing = false;
            if (onConfirm != null) onConfirm.run();
            onConfirm = null; onCancel = null;
            return true;
        }

        // 点击对话框外部 → 等同于取消
        if (mx < dx || mx > dx + DIALOG_W || my < dy || my > dy + DIALOG_H) {
            showing = false;
            if (onCancel != null) onCancel.run();
            onCancel = null; onConfirm = null;
            return true;
        }

        return true; // 点击对话框内部但不碰按钮：消费但不做
    }

    /** 通常的鼠标滚轮 / 拖动：对话框打开时全部消费 */
    public boolean blocksInput() { return showing; }
}
