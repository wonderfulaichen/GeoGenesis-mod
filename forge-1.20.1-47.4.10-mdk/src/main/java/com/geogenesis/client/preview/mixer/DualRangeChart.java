package com.geogenesis.client.preview.mixer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.DoubleUnaryOperator;

/**
 * 模板A：双控制点曲线图（Dual ControlPoint Curve）。
 *
 * <p>所有 9 个槽位（深海/浅海/大陆架/海岸 + 平原/丘陵/山脉/高原/盆地）都有方形控制点。
 * - 海洋类型：只读（setter=null），方形控制点仅显示，不可拖拽
 * - 陆地类型：交互式，方形控制点可拖拽
 * - 相邻槽位之间画 lo 蓝线和 hi 橙线贯穿全部 9 个 slot
 */
public class DualRangeChart {

    public static record Slot(String name, int color, double lo, double hi,
                              DoubleConsumer loSetter, DoubleConsumer hiSetter,
                              DoubleSupplier loGetter, DoubleSupplier hiGetter) {}

    private final List<Slot> slots = new ArrayList<>();
    private final List<ControlPoint> lowerPoints = new ArrayList<>();
    private final List<ControlPoint> upperPoints = new ArrayList<>();
    private Runnable onMarkDirty = () -> {};
    private Runnable onControlPointCommitted = null;
    private Runnable onValueChanged = null;  // 拖动每帧触发（实时联动滑块）
    private ControlPoint dragging;
    private int draggingSlotIdx = -1;          // 当前拖动的 slot 索引
    private boolean draggingIsHi = false;       // 当前拖动的是 lo (false) 还是 hi (true)

    private int bx, by, bw = 200, bh = 120;

    private int yWorldMin = -64, yWorldMax = 320;
    private DoubleUnaryOperator eToWorldY = e -> 63 + e * (320 - 63);

    public void setOnMarkDirty(Runnable r) { this.onMarkDirty = r; }
    public void setOnControlPointCommitted(Runnable r) { this.onControlPointCommitted = r; }
    public void setOnValueChanged(Runnable r) { this.onValueChanged = r; }

    /** 获取当前拖动控制点所属 slot 索引（-1 = 无拖动） */
    public int getDraggingSlotIndex() { return draggingSlotIdx; }
    /** 获取当前拖动的是 lo 还是 hi */
    public boolean isDraggingHi() { return draggingIsHi; }
    /** 计算当前拖动控制点对应的 eLand 值（从 Y 屏幕坐标反推） */
    public double yToEForDragging() {
        if (dragging == null) return 0;
        int sy = dragging.getY();
        double frac = 1.0 - (double) (sy - by) / bh;
        double wy = yWorldMin + frac * (yWorldMax - yWorldMin);
        return (wy - 63) / (yWorldMax - 63);
    }
    /** 把 eLand 转成 Y 屏幕坐标（公开版，给外部代码用） */
    public int eToY(double e) {
        return worldYToScreen(eToWorldY.applyAsDouble(e));
    }
    /** 直接按 eLand 值更新指定槽位控制点的屏幕位置（联动同步用，不写配置） */
    public void updateControlPoint(int slotIdx, boolean isHi, double e) {
        if (slotIdx < 0 || slotIdx >= slots.size()) return;
        int px = slotX(slotIdx);
        int sy = eToY(e);
        int wy = (int) Math.round(eToWorldY.applyAsDouble(e));
        if (isHi && slotIdx < upperPoints.size()) {
            upperPoints.get(slotIdx).setPosition(px, sy);
            upperPoints.get(slotIdx).setValueText(String.format("=Y%d", wy));
        } else if (!isHi && slotIdx < lowerPoints.size()) {
            lowerPoints.get(slotIdx).setPosition(px, sy);
            lowerPoints.get(slotIdx).setValueText(String.format("=Y%d", wy));
        }
    }
    public void setYWorldRange(int min, int max) {
        // 防御：max 必须 > min + 32，否则 fallback 默认（避免 maxY < minY 颠倒导致 Y 轴重复标签）
        if (max <= min + 32) {
            this.yWorldMin = -64;
            this.yWorldMax = 320;
        } else {
            this.yWorldMin = min;
            this.yWorldMax = max;
        }
    }
    public void setEToWorldY(DoubleUnaryOperator fn) { this.eToWorldY = fn; }

    public void setSlots(List<Slot> newSlots) {
        slots.clear(); lowerPoints.clear(); upperPoints.clear();
        for (Slot s : newSlots) {
            slots.add(s);
            boolean ro = (s.loSetter() == null);
            // 所有槽位统一用方形控制点；只读的不可拖拽
            ControlPoint lo = new ControlPoint(0, 0, 0xFF4488CC, s.name() + "低")
                    .setShape(ControlPoint.Shape.SQUARE).setSize(6).setReadOnly(ro);
            lo.setOnMarkDirty(onMarkDirty);
            if (!ro) lo.setOnValueChanged(() -> { if (s.loSetter() != null) s.loSetter().accept(yToE(lo.getY())); });
            lowerPoints.add(lo);

            boolean roHi = (s.hiSetter() == null);
            ControlPoint hi = new ControlPoint(0, 0, 0xFFFF8844, s.name() + "高")
                    .setShape(ControlPoint.Shape.SQUARE).setSize(6).setReadOnly(roHi);
            hi.setOnMarkDirty(onMarkDirty);
            if (!roHi) hi.setOnValueChanged(() -> { if (s.hiSetter() != null) s.hiSetter().accept(yToE(hi.getY())); });
            upperPoints.add(hi);
        }
        refreshFromConfig();
    }

    public void refreshFromConfig() {
        if (slots.isEmpty()) return;
        for (int i = 0; i < slots.size(); i++) {
            Slot s = slots.get(i);
            int px = slotX(i);
            double lo = s.loGetter() != null ? s.loGetter().getAsDouble() : s.lo();
            double hi = s.hiGetter() != null ? s.hiGetter().getAsDouble() : s.hi();
            if (i < lowerPoints.size()) {
                lowerPoints.get(i).setPosition(px, worldYToScreen(eToWorldY.applyAsDouble(lo)));
                lowerPoints.get(i).setValueText(String.format("=Y%d", (int) Math.round(eToWorldY.applyAsDouble(lo))));
            }
            if (i < upperPoints.size()) {
                upperPoints.get(i).setPosition(px, worldYToScreen(eToWorldY.applyAsDouble(hi)));
                upperPoints.get(i).setValueText(String.format("=Y%d", (int) Math.round(eToWorldY.applyAsDouble(hi))));
            }
        }
    }

    public void setBounds(int x, int y, int w, int h) {
        bx = x + 40; by = y + 18;
        bw = Math.max(60, w - 50); bh = Math.max(60, h - 50);
        refreshFromConfig();
    }
    public int getHeight() { return bh + 50; }

    private int slotX(int idx) {
        int n = slots.size();
        return n > 1 ? bx + 8 + idx * (bw - 16) / (n - 1) : bx + bw / 2;
    }
    private int worldYToScreen(double wy) {
        double frac = (wy - yWorldMin) / (yWorldMax - yWorldMin);
        return by + (int) ((1.0 - Math.max(0, Math.min(1, frac))) * bh);
    }
    public double worldY(double e) { return eToWorldY.applyAsDouble(e); }
    public double screenYToWorld(int screenY) {
        double frac = 1.0 - (double) (screenY - by) / bh;
        return yWorldMin + frac * (yWorldMax - yWorldMin);
    }
    public double yToE(int sy) {
        double frac = 1.0 - (double) (sy - by) / bh;
        double wy = yWorldMin + frac * (yWorldMax - yWorldMin);
        return (wy - 63) / (yWorldMax - 63);
    }

    /** 获取某槽位 lo 的屏幕 Y */
    private int getLoScreenY(int idx) {
        Slot s = slots.get(idx);
        double lo = s.loGetter() != null ? s.loGetter().getAsDouble() : s.lo();
        return worldYToScreen(eToWorldY.applyAsDouble(lo));
    }
    /** 获取某槽位 hi 的屏幕 Y */
    private int getHiScreenY(int idx) {
        Slot s = slots.get(idx);
        double hi = s.hiGetter() != null ? s.hiGetter().getAsDouble() : s.hi();
        return worldYToScreen(eToWorldY.applyAsDouble(hi));
    }

    public void render(GuiGraphics g, int mx, int my) {
        int n = slots.size();
        if (n == 0) return;
        var f = Minecraft.getInstance().font;

        // 背景 + 边框
        g.fill(bx, by, bx + bw, by + bh, 0xFF1a1f28);
        g.fill(bx, by, bx + bw, by + 1, 0xFF333344);
        g.fill(bx, by + bh - 1, bx + bw, by + bh, 0xFF333344);
        g.fill(bx, by, bx + 1, by + bh, 0xFF333344);
        g.fill(bx + bw - 1, by, bx + bw, by + bh, 0xFF333344);

        // Y 轴世界高度网格 + 标签
        int steps = 5;
        for (int i = 0; i <= steps; i++) {
            double frac = (double) i / steps;
            double wy = yWorldMin + frac * (yWorldMax - yWorldMin);
            int gy = worldYToScreen(wy);
            int gridColor = (i == 0 || i == steps) ? 0xFF333344 : 0xFF222730;
            g.fill(bx, gy, bx + bw, gy + 1, gridColor);
            String label = String.valueOf((int) Math.round(wy));
            g.drawString(f, label, bx - f.width(label) - 4, gy - 4, 0xFF888888);
        }

        // X 轴类型标签
        for (int i = 0; i < n; i++) {
            int sx = slotX(i);
            String name = slots.get(i).name();
            int lw = f.width(name);
            g.drawString(f, name, sx - lw / 2, by + bh + 4, slots.get(i).color());
        }

        // 填充区域（按槽位的 lo/hi）
        for (int i = 0; i < n - 1; i++) {
            int x1 = slotX(i), x2 = slotX(i + 1);
            int loY1 = lowerPoints.get(i).getY();
            int loY2 = lowerPoints.get(i + 1).getY();
            int hiY1 = upperPoints.get(i).getY();
            int hiY2 = upperPoints.get(i + 1).getY();
            for (int s = 0; s < 6; s++) {
                double t0 = (double) s / 6, t1 = (double) (s + 1) / 6;
                int sx0 = (int) (x1 + (x2 - x1) * t0), sx1 = (int) (x1 + (x2 - x1) * t1);
                int loY = (int) (loY1 + (loY2 - loY1) * (t0 + t1) / 2);
                int hiY = (int) (hiY1 + (hiY2 - hiY1) * (t0 + t1) / 2);
                int ft = Math.min(loY, hiY), fb = Math.max(loY, hiY);
                int col = (slots.get(i).color() & 0xFFFFFF) | 0x33000000;
                g.fill(sx0, ft, sx1, fb, col);
            }
        }

        // 连线：所有相邻 slot 之间都画 lo 蓝线 + hi 橙线（贯穿 9 个 slot）
        for (int i = 0; i < n - 1; i++) {
            drawDashedLine(g, lowerPoints.get(i).getX(), lowerPoints.get(i).getY(),
                    lowerPoints.get(i + 1).getX(), lowerPoints.get(i + 1).getY(), 0xFF4488CC);
            drawDashedLine(g, upperPoints.get(i).getX(), upperPoints.get(i).getY(),
                    upperPoints.get(i + 1).getX(), upperPoints.get(i + 1).getY(), 0xFFFF8844);
        }

        // 渲染所有控制点（海洋只读 + 陆地方形全都渲染）
        for (int i = 0; i < n; i++) {
            lowerPoints.get(i).hovered = lowerPoints.get(i).hitTest(mx, my);
            lowerPoints.get(i).render(g, mx, my, dragging == lowerPoints.get(i));
            upperPoints.get(i).hovered = upperPoints.get(i).hitTest(mx, my);
            upperPoints.get(i).render(g, mx, my, dragging == upperPoints.get(i));
        }
    }

    public void renderTooltips(GuiGraphics g, int mx, int my) {
        for (ControlPoint cp : lowerPoints) if (cp.hovered) cp.renderTooltip(g, mx, my);
        for (ControlPoint cp : upperPoints) if (cp.hovered) cp.renderTooltip(g, mx, my);
    }

    // ---- 鼠标 ----
    public boolean mouseClicked(double mx, double my, int btn) {
        if (btn != 0) return false;
        // 只读控制点不响应 drag（点击穿透）
        for (int i = 0; i < lowerPoints.size(); i++) {
            ControlPoint cp = lowerPoints.get(i);
            if (!cp.isReadOnly() && cp.hitTest((int) mx, (int) my)) {
                dragging = cp; draggingSlotIdx = i; draggingIsHi = false;
                cp.onDragStart((int) mx, (int) my); return true;
            }
        }
        for (int i = 0; i < upperPoints.size(); i++) {
            ControlPoint cp = upperPoints.get(i);
            if (!cp.isReadOnly() && cp.hitTest((int) mx, (int) my)) {
                dragging = cp; draggingSlotIdx = i; draggingIsHi = true;
                cp.onDragStart((int) mx, (int) my); return true;
            }
        }
        return false;
    }
    public boolean mouseDragged(double mx, double my, int btn, double dx, double dy) {
        if (dragging != null) {
            int newY = Math.max(by + 2, Math.min(by + bh - 2, (int) my));
            // 海洋控制点约束：lo ≤ hi (保证 slot 不反转)
            if (draggingSlotIdx >= 0 && draggingSlotIdx < slots.size()) {
                Slot s = slots.get(draggingSlotIdx);
                double e = yToE(newY);
                if (!draggingIsHi) {
                    // 拖动 lo：clamp 到 [-1, current hi]，防止 lo 超过 hi
                    if (s.hiGetter() != null) {
                        double upper = s.hiGetter().getAsDouble();
                        if (draggingSlotIdx < 4) {  // 海洋
                            upper = Math.max(-1.0, Math.min(0.0, upper));
                        }
                        e = Math.max(-1.0, Math.min(upper, e));
                    }
                } else {
                    // 拖动 hi：clamp 到 [current lo, 上限]，防止 hi 低于 lo
                    if (s.loGetter() != null) {
                        double lower = s.loGetter().getAsDouble();
                        double upper = (draggingSlotIdx < 4) ? 0.0 : 1.0;  // 海洋 hi 上限 0
                        e = Math.max(lower, Math.min(upper, e));
                    }
                }
                newY = worldYToScreen(eToWorldY.applyAsDouble(e));
            }
            dragging.setPosition(dragging.getX(), newY);
            // 拖动每帧通知：setter 不调（避免文件锁），但父组件可读 yToEForDragging() 同步滑块
            if (onValueChanged != null) onValueChanged.run();
            return true;
        }
        return false;
    }
    public boolean mouseReleased(double mx, double my, int btn) {
        if (dragging != null) {
            dragging.triggerValueChanged(); // 松手时才写配置（只写一次，避免文件锁）
            dragging.onRelease();
            if (onControlPointCommitted != null) onControlPointCommitted.run();
            dragging = null;
            draggingSlotIdx = -1;
            return true;
        }
        return false;
    }

    private static void drawDashedLine(GuiGraphics g, int x1, int y1, int x2, int y2, int color) {
        int steps = Math.max(Math.abs(x2 - x1), Math.abs(y2 - y1));
        for (int i = 0; i <= steps; i++) {
            if ((i / 3) % 2 != 0) continue;
            double t = steps > 0 ? (double) i / steps : 0;
            int x = (int) (x1 + (x2 - x1) * t), y = (int) (y1 + (y2 - y1) * t);
            g.fill(x, y, x + 1, y + 1, color);
        }
    }
}
