package com.geogenesis.client.preview.mixer;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;

import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;

/**
 * 双曲线雪线图表 — 温度×湿度共同决定雪线高度。
 *
 * <p>显示两条曲线：
 * <ul>
 *   <li><b>干燥曲线（橙）</b>：湿度 = -1（最干），雪线最高</li>
 *   <li><b>湿润曲线（蓝）</b>：湿度 = +1（最湿），雪线最低</li>
 * </ul>
 * 两线之间的色带表示湿度对雪线的影响范围。
 *
 * <p>4 个方形控制点（配对交互）：
 * <ul>
 *   <li>拖拽干燥冷端/暖端 → 调整雪线中线和温度敏感性</li>
 *   <li>拖拽湿润冷端/暖端 → 调整湿度带宽</li>
 * </ul>
 * 约束：干燥曲线 ≥ 湿润曲线（不交叉），冷端 ≤ 暖端。
 */
public class SnowLineChart {

    // 图表区域（包括轴标签）
    private int cx, cy, cw = 180, ch = 100;

    private double xMin = -1.0, xMax = 1.0, yMin = 0, yMax = 320;
    /** 海平面 Y（e=0 锚点）。snowLine 比率 e_space → 世界高度 Y 时使用 */
    private double seaLevel = 63;
    private Runnable onMarkDirty = () -> {};
    private ControlPoint dragging = null;

    // ===== 4 个控制点 =====
    private final ControlPoint dryCold = new ControlPoint(0, 0, 0xFFFF8844, "干燥冷端")
        .setShape(ControlPoint.Shape.SQUARE).setSize(6);
    private final ControlPoint dryWarm = new ControlPoint(0, 0, 0xFFFFAA22, "干燥暖端")
        .setShape(ControlPoint.Shape.SQUARE).setSize(6);
    private final ControlPoint wetCold = new ControlPoint(0, 0, 0xFF44CCFF, "湿润冷端")
        .setShape(ControlPoint.Shape.SQUARE).setSize(6);
    private final ControlPoint wetWarm = new ControlPoint(0, 0, 0xFF66DDFF, "湿润暖端")
        .setShape(ControlPoint.Shape.SQUARE).setSize(6);

    private final ControlPoint[] allPoints = {dryCold, dryWarm, wetCold, wetWarm};

    // ===== 配置读写接口 =====
    private DoubleSupplier snowLineGetter;
    private DoubleConsumer snowLineSetter;
    private DoubleSupplier tempInfGetter;
    private DoubleConsumer tempInfSetter;
    private DoubleSupplier humInfGetter;
    private DoubleConsumer humInfSetter;

    // ===== 滑块同步回调 =====
    // 参数：[coldCenterY, warmCenterY, dryColdY, dryWarmY, wetColdY, wetWarmY]
    private java.util.function.Consumer<double[]> onPointsChanged = v -> {};

    public SnowLineChart() {
        for (ControlPoint p : allPoints) {
            p.setOnValueChanged(this::pointsToConfig);
        }
    }

    public void setOnMarkDirty(Runnable r) { this.onMarkDirty = r; }

    /** 设置配置读写接口：{snowLine, snowTempInfluence, snowHumidityInfluence} 各一对 getter/setter */
    public void setConfigBindings(DoubleSupplier snowLineG, DoubleConsumer snowLineS,
                                  DoubleSupplier tempInfG, DoubleConsumer tempInfS,
                                  DoubleSupplier humInfG, DoubleConsumer humInfS) {
        this.snowLineGetter = snowLineG;
        this.snowLineSetter = snowLineS;
        this.tempInfGetter = tempInfG;
        this.tempInfSetter = tempInfS;
        this.humInfGetter = humInfG;
        this.humInfSetter = humInfS;
    }

    /** 设置 4 个控制点的位置变更回调（供父面板同步滑块） */
    public void setOnPointsChanged(java.util.function.Consumer<double[]> cb) { this.onPointsChanged = cb; }

    public void setXRange(double min, double max) { this.xMin = min; this.xMax = max; }
    public void setYRange(double min, double max) { this.yMin = min; this.yMax = max; }
    /** 设置海平面世界 Y（e=0 锚点）。默认为 63。 */
    public void setSeaLevel(double sl) { this.seaLevel = sl; }

    public void setBounds(int x, int y, int w, int h) {
        cx = x + 36;
        cy = y + 14;
        cw = Math.max(40, w - 46);
        ch = Math.max(30, h - 38);
    }

    public int getHeight() { return ch + 38; }

    // ===== 坐标转换 =====

    private int xToScreen(double v) { return cx + (int)((v - xMin) / (xMax - xMin) * cw); }
    private int yToScreen(double v) { return cy + (int)((1.0 - (v - yMin) / (yMax - yMin)) * ch); }
    private double screenToX(int sx) { return xMin + (double)(sx - cx) / cw * (xMax - xMin); }
    private double screenToY(int sy) { return yMin + (1.0 - (double)(sy - cy) / ch) * (yMax - yMin); }

    // ===== 核心数学 =====

    /**
     * 计算给定温度和湿度下的雪线世界高度。
     * @param temperature [-1, 1]
     * @param humidity    [-1, 1]（-1=最干，+1=最湿）
     */
    public double eval(double temperature, double humidity) {
        double base = snowLineGetter != null ? snowLineGetter.getAsDouble() : 0.7;
        double tempInf = tempInfGetter != null ? tempInfGetter.getAsDouble() : 0.0;
        double humInf = humInfGetter != null ? humInfGetter.getAsDouble() : 0.15;

        double tNorm = (temperature + 1.0) / 2.0; // [0,1]
        double hNorm = (humidity + 1.0) / 2.0;    // [0,1]
        double ratio = base + (tNorm - 0.5) * tempInf - (hNorm - 0.5) * humInf;
        ratio = Math.max(0.02, Math.min(1.0, ratio));
        // HeightCurve-style: e=0 → seaLevel, e=1 → maxY
        return seaLevel + ratio * (yMax - seaLevel);
    }

    /** 干燥曲线（湿度=-1） */
    public double evalDry(double temperature) { return eval(temperature, -1.0); }

    /** 湿润曲线（湿度=+1） */
    public double evalWet(double temperature) { return eval(temperature, 1.0); }

    // ===== 控制点同步 =====

    /**
     * 从配置字段刷新控制点位置。
     * 由外部在 setBounds / config changed 后调用。
     */
    public void refreshPoints() {
        if (snowLineGetter == null) return;
        double landRange = yMax - seaLevel;
        if (landRange <= 0) return;

        double base = snowLineGetter.getAsDouble();
        double tempInf = tempInfGetter.getAsDouble();
        double humInf = humInfGetter.getAsDouble();

        // 中点曲线在两个端点处的比值
        double ratioCold = Math.max(0.02, Math.min(1.0, base - 0.5 * tempInf));
        double ratioWarm = Math.max(0.02, Math.min(1.0, base + 0.5 * tempInf));
        double bw = Math.min(humInf, 0.5);

        double yColdCenter = seaLevel + ratioCold * landRange;
        double yWarmCenter = seaLevel + ratioWarm * landRange;
        double bwBlocks = bw * landRange;

        setPointFromWorldY(dryCold, -1.0, yColdCenter + bwBlocks * 0.5);
        setPointFromWorldY(wetCold, -1.0, yColdCenter - bwBlocks * 0.5);
        setPointFromWorldY(dryWarm, 1.0, yWarmCenter + bwBlocks * 0.5);
        setPointFromWorldY(wetWarm, 1.0, yWarmCenter - bwBlocks * 0.5);
    }

    /** 从世界高度Y设置控制点屏幕位置 */
    private void setPointFromWorldY(ControlPoint pt, double xVal, double yWorld) {
        yWorld = Math.max(yMin, Math.min(yMax, yWorld));
        int sx = xToScreen(xVal);
        int sy = yToScreen(yWorld);
        sx = Math.max(cx + 6, Math.min(cx + cw - 6, sx));
        sy = Math.max(cy + 6, Math.min(cy + ch - 6, sy));
        pt.setPosition(sx, sy);
        pt.setValueText(String.format("(T=%.1f, Y=%.0f)", xVal, yWorld));
    }

    /** 从控制点位置回写配置字段 */
    private void pointsToConfig() {
        if (snowLineGetter == null) return;
        double landRange = yMax - seaLevel;
        if (landRange <= 0) return;

        // 读取 4 个控制点的世界高度
        double dryColdY = screenToY(dryCold.getY());
        double dryWarmY = screenToY(dryWarm.getY());
        double wetColdY = screenToY(wetCold.getY());
        double wetWarmY = screenToY(wetWarm.getY());

        // 钳制到合法范围
        dryColdY = Math.max(seaLevel, Math.min(yMax, dryColdY));
        dryWarmY = Math.max(seaLevel, Math.min(yMax, dryWarmY));
        wetColdY = Math.max(seaLevel, Math.min(yMax, wetColdY));
        wetWarmY = Math.max(seaLevel, Math.min(yMax, wetWarmY));

        // 冷端 ≤ 暖端约束
        if (dryColdY > dryWarmY) dryColdY = dryWarmY;
        if (wetColdY > wetWarmY) wetColdY = wetWarmY;

        // 干燥 ≥ 湿润约束
        if (dryColdY < wetColdY + 1) dryColdY = wetColdY + 1;
        if (dryWarmY < wetWarmY + 1) dryWarmY = wetWarmY + 1;

        // 计算中点曲线
        double yColdCenter = (dryColdY + wetColdY) / 2;
        double yWarmCenter = (dryWarmY + wetWarmY) / 2;

        // 计算带宽（统一使用较小值以保证两曲线平行）
        double coldBW = dryColdY - wetColdY;
        double warmBW = dryWarmY - wetWarmY;
        double bandwidth = Math.max(1, Math.min(coldBW, warmBW));

        // 同步强制平行：重新计算 4 点位置
        double wetColdFinal = yColdCenter - bandwidth * 0.5;
        double wetWarmFinal = yWarmCenter - bandwidth * 0.5;
        double dryColdFinal = yColdCenter + bandwidth * 0.5;
        double dryWarmFinal = yWarmCenter + bandwidth * 0.5;

        // 用 HeightCurve 反算：从世界高度 Y → e_space 比率
        double ratioCold = (yColdCenter - seaLevel) / landRange;
        double ratioWarm = (yWarmCenter - seaLevel) / landRange;
        ratioCold = Math.max(0.02, Math.min(1.0, ratioCold));
        ratioWarm = Math.max(0.02, Math.min(1.0, ratioWarm));

        double ratioCenter = (ratioCold + ratioWarm) / 2;
        double ratioTempInf = Math.max(0, Math.min(0.6, ratioWarm - ratioCold));
        double bwRatio = Math.max(0, Math.min(0.5, bandwidth / landRange));

        // 写入配置
        snowLineSetter.accept(Math.max(0, Math.min(1, ratioCenter)));
        tempInfSetter.accept(ratioTempInf);
        humInfSetter.accept(bwRatio);

        // 刷新所有点的视觉位置（确保与写入后的配置一致）
        refreshPoints();

        // 通知父面板同步滑块
        onPointsChanged.accept(new double[]{
            yColdCenter, yWarmCenter,
            dryColdFinal, dryWarmFinal, wetColdFinal, wetWarmFinal
        });

        if (onMarkDirty != null) onMarkDirty.run();
    }

    // ===== 渲染 =====

    public void render(GuiGraphics g, int mx, int my) {
        var f = Minecraft.getInstance().font;

        // 背景 + 边框
        g.fill(cx, cy, cx + cw, cy + ch, 0xFF1a1f28);
        g.fill(cx, cy, cx + cw, cy + 1, 0xFF333344);
        g.fill(cx, cy + ch - 1, cx + cw, cy + ch, 0xFF333344);
        g.fill(cx, cy, cx + 1, cy + ch, 0xFF333344);
        g.fill(cx + cw - 1, cy, cx + cw, cy + ch, 0xFF333344);

        // Y 轴网格 + 标签
        int ySteps = 5;
        for (int i = 0; i <= ySteps; i++) {
            double frac = (double) i / ySteps;
            double yVal = yMin + frac * (yMax - yMin);
            int gy = yToScreen(yVal);
            int gridColor = (i == 0 || i == ySteps) ? 0xFF333344 : 0xFF222730;
            g.fill(cx, gy, cx + cw, gy + 1, gridColor);
            g.drawString(f, String.format("%.0f", yVal), cx - 28, gy - 4, 0xFF888888);
        }

        // X 轴网格 + 标签
        int xSteps = 4;
        for (int i = 0; i <= xSteps; i++) {
            double frac = (double) i / xSteps;
            double xVal = xMin + frac * (xMax - xMin);
            int gx = xToScreen(xVal);
            int gridColor = (i == 0 || i == xSteps) ? 0xFF333344 : 0xFF222730;
            g.fill(gx, cy, gx + 1, cy + ch, gridColor);
            g.drawString(f, String.format("%.1f", xVal), gx - 6, cy + ch + 2, 0xFF888888);
        }

        // 轴标签
        g.drawString(f, "←寒冷", cx, cy + ch + 12, 0xFF66AAFF);
        g.drawString(f, "炎热→", cx + cw - 24, cy + ch + 12, 0xFFFF7744);

        // 两线之间填充（渐变：上橙下蓝）
        int segs = Math.max(2, cw);
        for (int s = 0; s < segs; s++) {
            double t0 = (double) s / segs, t1 = (double) (s + 1) / segs;
            double x0 = xMin + t0 * (xMax - xMin), x1 = xMin + t1 * (xMax - xMin);
            double dryY0 = evalDry(x0), dryY1 = evalDry(x1);
            double wetY0 = evalWet(x0), wetY1 = evalWet(x1);
            int sx0 = xToScreen(x0), sx1 = xToScreen(x1);
            int drySy0 = yToScreen(dryY0), drySy1 = yToScreen(dryY1);
            int wetSy0 = yToScreen(wetY0), wetSy1 = yToScreen(wetY1);
            int top = Math.max(drySy0, drySy1);
            int bottom = Math.min(wetSy0, wetSy1);
            if (top < bottom) {
                // 渐变：上半橙，下半蓝
                int mid = (top + bottom) / 2;
                g.fill(sx0, top, sx1, mid, 0x22FF8844);
                g.fill(sx0, mid, sx1, bottom, 0x2244CCFF);
            }
        }

        // 干燥曲线（橙色实线）
        drawCurve(g, this::evalDry, 0xFFFF8844, false);

        // 湿润曲线（蓝青色实线）
        drawCurve(g, this::evalWet, 0xFF44CCFF, false);

        // 湿度影响范围标注
        String dryLabel = "干燥";
        String wetLabel = "湿润";
        int dlW = f.width(dryLabel);
        int wlW = f.width(wetLabel);
        g.drawString(f, dryLabel, cx + cw / 2 - dlW / 2, cy + 2, 0xFFFF8844);
        g.drawString(f, wetLabel, cx + cw / 2 - wlW / 2, cy + ch - 12, 0xFF44CCFF);

        // 控制点
        for (ControlPoint p : allPoints) {
            p.hovered = p.hitTest(mx, my);
            p.render(g, mx, my, dragging == p);
        }
    }

    private void drawCurve(GuiGraphics g, java.util.function.DoubleUnaryOperator fn,
                           int color, boolean dashed) {
        int segs = Math.max(4, cw);
        for (int s = 0; s < segs; s++) {
            double t0 = (double) s / segs, t1 = (double) (s + 1) / segs;
            double x0 = xMin + t0 * (xMax - xMin), x1 = xMin + t1 * (xMax - xMin);
            double y0 = fn.applyAsDouble(x0), y1 = fn.applyAsDouble(x1);
            int sx0 = xToScreen(x0), sy0 = yToScreen(y0);
            int sx1 = xToScreen(x1), sy1 = yToScreen(y1);
            int steps = Math.max(Math.abs(sx1 - sx0), Math.abs(sy1 - sy0));
            for (int i = 0; i <= steps; i++) {
                if (dashed && (i / 4) % 2 != 0) continue;
                double t = steps > 0 ? (double) i / steps : 0;
                int px = (int) (sx0 + (sx1 - sx0) * t);
                int py = (int) (sy0 + (sy1 - sy0) * t);
                g.fill(px, py, px + 1, py + 1, color);
            }
        }
    }

    public void renderTooltips(GuiGraphics g, int mx, int my) {
        for (ControlPoint p : allPoints) {
            if (p.hovered) p.renderTooltip(g, mx, my);
        }
    }

    // ===== 鼠标 =====

    public boolean mouseClicked(double mx, double my, int btn) {
        if (btn != 0) return false;
        // 倒序遍历（上层优先）
        for (int i = allPoints.length - 1; i >= 0; i--) {
            ControlPoint p = allPoints[i];
            if (p.hitTest((int) mx, (int) my)) {
                dragging = p;
                p.onDragStart((int) mx, (int) my);
                return true;
            }
        }
        return false;
    }

    public boolean mouseDragged(double mx, double my, int btn, double dx, double dy) {
        if (dragging != null) {
            int nx = Math.max(cx, Math.min(cx + cw, (int) mx));
            int ny = Math.max(cy, Math.min(cy + ch, (int) my));
            dragging.setPosition(nx, ny);
            return true;
        }
        return false;
    }

    public boolean mouseReleased(double mx, double my, int btn) {
        if (dragging != null) {
            dragging.triggerValueChanged(); // 触发 pointsToConfig
            dragging.onRelease();
            dragging = null;
            return true;
        }
        return false;
    }
}
