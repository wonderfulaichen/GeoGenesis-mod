package com.geogenesis.client.preview.mixer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import com.geogenesis.client.preview.UiKit;

import java.util.function.Consumer;

/**
 * 模板F：可折叠面板容器（Collapsible Panel）。
 *
 * <p>包装一个图表模板 + 关联 ParamSliders，带标题栏、折叠/展开、悬停 tooltip。
 *
 * <p>重要设计约定：
 * - renderHeader() 使用 this.y（由 setBounds 设置）作为标题栏起始 Y
 * - hitTestHeader() 也使用 this.y → 渲染和交互始终一致
 * - 外部在渲染前须调用 setBounds(x, y, w) 更新 Y 位置（含 scrollOffset 后的坐标）
 * - 不再接受 baseY 参数（v2 修复：消除外部传入与内部 y 不一致的矛盾）
 */
public class MixerPanel {

    private String title;
    private int titleColor = 0xFFF2F2F2;
    private boolean collapsed = false;
    private boolean hovered = false;

    /** 点击折叠标题栏后的回调 */
    private Runnable onCollapseChanged = () -> {};

    /** 内部各段的高度（由外部在渲染前 setBounds 时指定） */
    private int x, y, w;
    /** 内容区域高度（图表模板 + 滑块总高，不包括标题栏） */
    private int contentH = 0;
    /** 标题栏高度 */
    private static final int TITLE_H = 16;

    public MixerPanel(String title) {
        this.title = title;
    }

    public void setTitleColor(int c) { this.titleColor = c; }
    public void setOnCollapseChanged(Runnable r) { this.onCollapseChanged = r; }
    public boolean isCollapsed() { return collapsed; }
    public void setCollapsed(boolean v) { collapsed = v; }

    /** 设置内容区高度（由外部在布局图表模板 + 滑块后调用） */
    public void setContentHeight(int h) { this.contentH = h; }

    /** 面板完整高度（含标题栏） */
    public int getFullHeight() { return TITLE_H + (collapsed ? 0 : contentH); }

    /** 设置位置宽度 */
    public void setBounds(int x, int y, int w) {
        this.x = x;
        this.y = y;
        this.w = w;
    }

    public int getX() { return x; }
    public int getY() { return y; }
    public int getWidth() { return w; }

    /** 渲染标题栏（使用 this.y 作为起始 Y） */
    public void renderHeader(GuiGraphics g, int mx, int my) {
        var f = Minecraft.getInstance().font;
        hovered = mx >= x && mx <= x + w && my >= y && my <= y + TITLE_H;

        // ★ v12：悬停底 0x10FFFFFF → 0x0CFFFFFF（标题栏不再出现可辨的圆角框）
        int bg = 0x00000000;
        if (hovered) bg = 0x0CFFFFFF;
        if (bg != 0) UiKit.roundRect(g, x, y, x + w, y + TITLE_H, 3, bg);

        // 折叠图标：用 ASCII（中文字体没有 ▸/▾ 字形，实机会画成空框）
        String icon = collapsed ? ">" : "v";
        g.drawString(f, icon, x + 4, y + (TITLE_H - f.lineHeight) / 2, UiKit.C_TXT_DIM, false);
        g.drawString(f, title, x + 16, y + (TITLE_H - f.lineHeight) / 2, titleColor, false);
    }

    /** 点击检测：返回 true 如果点中了标题栏（基于 this.y） */
    public boolean hitTestHeader(int mx, int my) {
        if (mx < x || mx > x + w || my < y || my > y + TITLE_H) return false;
        return true;
    }

    /** 点击标题栏切换折叠 */
    public boolean mouseClicked(double mx, double my, int btn) {
        if (btn != 0) return false;
        if (hitTestHeader((int) mx, (int) my)) {
            collapsed = !collapsed;
            if (onCollapseChanged != null) onCollapseChanged.run();
            return true;
        }
        return false;
    }

    /** 登记悬停提示（★ v15：不再自己绘制）。 */
    public void renderTooltip(GuiGraphics g, int mx, int my) {
        if (hovered) {
            UiKit.Hover.report(collapsed ? "点击展开" : "点击折叠", mx, my);
        }
    }
}
