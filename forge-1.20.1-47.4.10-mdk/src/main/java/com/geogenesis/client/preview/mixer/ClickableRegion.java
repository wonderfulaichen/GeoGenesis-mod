package com.geogenesis.client.preview.mixer;

import net.minecraft.client.gui.GuiGraphics;

/**
 * 统一交互基类：所有图表模板中的可拖拽元素（控制点/边界/标记）继承此类。
 *
 * <p>提供三层交互的骨架：
 * <ol>
 *   <li>命中检测（{@link #hitTest}）</li>
 *   <li>拖拽状态管理（dragStart/onDrag/onRelease）</li>
 *   <li>位置管理（{@link #setPosition}/{@link #getX}/{@link #getY}）</li>
 *   <li>悬停 tooltip（{@link #renderTooltip}）</li>
 * </ol>
 */
public abstract class ClickableRegion {

    /** 统一命中检测半径（px） */
    public static final int HIT_RADIUS = 6;

    protected boolean dragging;
    protected boolean hovered;
    protected int dragStartX, dragStartY;

    /** 松手/值变更后触发脏标记 */
    protected Runnable onMarkDirty = () -> {};

    public void setOnMarkDirty(Runnable r) { this.onMarkDirty = r; }

    // ---- 位置（所有子类都必须有屏幕坐标） ----

    /** 设置屏幕位置 */
    public abstract void setPosition(int x, int y);

    /** 获取屏幕 X */
    public abstract int getX();

    /** 获取屏幕 Y */
    public abstract int getY();

    // ---- 子类必须实现 ----

    /** 检测鼠标位置是否击中本元素 */
    public abstract boolean hitTest(int mx, int my);

    /** 渲染元素本体 */
    public abstract void render(GuiGraphics g, int mx, int my, boolean selected);

    /** 渲染悬停 tooltip */
    public abstract void renderTooltip(GuiGraphics g, int mx, int my);

    // ---- 可选重写 ----

    public void onDragStart(int mx, int my) {
        dragging = true;
        dragStartX = mx;
        dragStartY = my;
    }

    /** 拖拽中调用，dx/dy 为相对变化量 */
    public void onDrag(int dx, int dy) {}

    /** 松手时调用 */
    public void onRelease() {
        dragging = false;
        if (onMarkDirty != null) onMarkDirty.run();
    }
}
