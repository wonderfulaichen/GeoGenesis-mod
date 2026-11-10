package com.geogenesis.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import com.geogenesis.client.preview.UiKit;

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

        // ★ v7：对话框改走 UiKit（圆角卡片 + 胶囊按钮，与新版控件语言一致）
        UiKit.roundRect(g, dx, dy, dx + DIALOG_W, dy + DIALOG_H, UiKit.RADIUS_CARD, 0xF0181C20);
        UiKit.roundRectOutline(g, dx, dy, dx + DIALOG_W, dy + DIALOG_H, UiKit.RADIUS_CARD, 0x22FFFFFF);

        var f = Minecraft.getInstance().font;

        // 标题（去 emoji —— 字体没这个字形，实机是个空框）
        g.drawString(f, "修改" + title, dx + 12, dy + 10, UiKit.C_TXT, false);

        // 消息（旧值→新值）
        g.drawString(f, message, dx + 12, dy + 28, UiKit.C_TXT_SUB, false);

        // 影响列表
        if (affectedItems != null && !affectedItems.isEmpty()) {
            g.drawString(f, "影响以下项目（已自动适配）：", dx + 12, dy + 48,
                    UiKit.C_TXT_DIM, false);
            int ay = dy + 62;
            for (String item : affectedItems) {
                g.drawString(f, "· " + item, dx + 20, ay, UiKit.C_TEAL, false);
                ay += 12;
            }
        }

        // 按钮：胶囊、无描边
        int btnY = dy + DIALOG_H - 30;
        int btnW = 80;

        int cancelX = dx + 20;
        boolean hoverCancel = mx >= cancelX && mx <= cancelX + btnW && my >= btnY && my <= btnY + 20;
        UiKit.button(g, cancelX, btnY, btnW, 20, "取消", false, hoverCancel);

        int confirmX = dx + DIALOG_W - 20 - btnW;
        boolean hoverConfirm = mx >= confirmX && mx <= confirmX + btnW && my >= btnY && my <= btnY + 20;
        UiKit.button(g, confirmX, btnY, btnW, 20, "确定", true, hoverConfirm);
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
