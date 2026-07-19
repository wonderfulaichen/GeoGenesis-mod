package com.geogenesis.client.preview;

import com.geogenesis.client.ParamSlider;
import com.geogenesis.client.preview.mixer.DualRangeChart;
import com.geogenesis.config.GeoGenesisConfig;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraftforge.common.ForgeConfigSpec;

import java.util.ArrayList;
import java.util.List;
import java.util.function.DoubleConsumer;
import java.util.function.DoubleSupplier;
import java.util.function.Function;

/**
 * 地形配置面板（地形页签）。
 *
 * <p>布局：
 * - 标题栏「▸/▾ 地形高度图」（可点击折叠）
 * - 展开：DualRangeChart（9 个类型，全方形控制点） + 每类型 lo/hi 双滑块
 *
 * <p>所有 9 类型均可自由配置：
 * - 海洋 lo = 上一个类型的 depth（深海 lo 固定 -1），hi = 当前类型的 depth
 * - 陆地 lo = center - halfRange，hi = center + halfRange
 * - 滑块 ↔ 控制点双向同步
 */
public class TerrainConfigPanel {

    private int x, baseY, w;
    private int scrollOffset;
    private final DualRangeChart chart = new DualRangeChart();
    private final List<ParamSlider> loSliders = new ArrayList<>();
    private final List<ParamSlider> hiSliders = new ArrayList<>();
    private final List<DualRangeChart.Slot> slots = new ArrayList<>();
    private Runnable onMarkDirty = () -> {};

    private boolean collapsed = false;

    private static final int ROW_H = 16;
    private static final int CHART_H = 130;
    private static final int HEADER_H = 18;
    private static final int ROW_LABEL_W = 38;
    private static final int Y_VAL_W = 30;
    private static final int SLIDER_GAP = 4;
    private static final int SIDE_PAD = 8;

    public TerrainConfigPanel() {
        chart.setOnMarkDirty(() -> { if (onMarkDirty != null) onMarkDirty.run(); });
        // 控制点拖动每帧：实时同步相关滑块 + 图表耦合控制点（不写 config，不触发 markDirty）
        chart.setOnValueChanged(() -> {
            int idx = chart.getDraggingSlotIndex();
            if (idx < 0) return;
            double e = chart.yToEForDragging();
            boolean isHi = chart.isDraggingHi();
            // 更新当前拖动滑块
            if (isHi) hiSliders.get(idx).setCurrentValue(e);
            else loSliders.get(idx).setCurrentValue(e);
            // 海洋耦合：相邻 slot 共享同一深度值，实时同步滑块 + 图表控制点
            // 深海 hi (idx=0, hi) ↔ 浅海 lo (idx=1, lo)
            // 浅海 hi (idx=1, hi) ↔ 大陆架 lo (idx=2, lo)
            // 大陆架 hi (idx=2, hi) ↔ 海岸 lo (idx=3, lo)
            if (idx < 4) {
                if (isHi && idx + 1 < 4) {
                    loSliders.get(idx + 1).setCurrentValue(e);
                    chart.updateControlPoint(idx + 1, false, e);
                } else if (!isHi && idx > 0) {
                    hiSliders.get(idx - 1).setCurrentValue(e);
                    chart.updateControlPoint(idx - 1, true, e);
                }
            }
        });
        // 控制点 release → 同步所有滑块 + 触发预览重建
        chart.setOnControlPointCommitted(() -> {
            // 诊断：记录所有槽位提交前的值
            StringBuilder sb = new StringBuilder();
            sb.append("CP_COMMIT [");
            for (int i = 0; i < slots.size(); i++) {
                DualRangeChart.Slot s = slots.get(i);
                double lo = s.loGetter().getAsDouble();
                double hi = s.hiGetter().getAsDouble();
                sb.append(s.name()).append("=").append(String.format("%.4f/%.4f", lo, hi));
                if (i < slots.size() - 1) sb.append(", ");
            }
            sb.append("]");
            System.out.println(sb.toString());

            chart.refreshFromConfig();
            for (int i = 0; i < loSliders.size(); i++) {
                DualRangeChart.Slot s = slots.get(i);
                loSliders.get(i).setCurrentValue(s.loGetter().getAsDouble());
                hiSliders.get(i).setCurrentValue(s.hiGetter().getAsDouble());
            }
            if (onMarkDirty != null) onMarkDirty.run();
        });
    }

    public void setOnMarkDirty(Runnable r) { this.onMarkDirty = r; }
    public void setBounds(int x, int y, int w) { this.x = x; this.baseY = y; this.w = w; }
    public int getY() { return baseY; }
    public void setScrollOffset(int off) { this.scrollOffset = off; }
    public boolean isCollapsed() { return collapsed; }
    public void setCollapsed(boolean v) { this.collapsed = v; }

    public void buildFromConfig() {
        GeoGenesisConfig c = GeoGenesisConfig.INSTANCE;
        int seaLevel = c.seaLevel.get();
        int maxY = c.maxY.get();
        int minY = c.minY.get();
        chart.setYWorldRange(minY, maxY);
        chart.setEToWorldY(e -> seaLevel + e * (maxY - seaLevel));

        slots.clear();
        loSliders.clear();
        hiSliders.clear();

        // 海洋类型（可调，lo = 上一个类型的 depth，hi = 当前类型的 depth）
        // 深海 lo=-1 固定，hi=deepOceanDepth；浅海 lo=deepOceanDepth → 同步深海 hi
        slots.add(oceanSlot("深海", 0xFF1A4D80, null, c.deepOceanDepth));
        slots.add(oceanSlot("浅海", 0xFF3070A0, c.deepOceanDepth, c.shelfDepth));
        slots.add(oceanSlot("大陆架", 0xFF5A8FB0, c.shelfDepth, c.shallowDepth));
        slots.add(oceanSlot("海岸", 0xFF8AB0CC, c.shallowDepth, null));

        // 陆地类型（交互式，center±halfRange）
        slots.add(interactiveSlot("平原", 0xFF44AA66, c.plainCenter, c.plainHalfRange));
        slots.add(interactiveSlot("丘陵", 0xFF88CC44, c.hillsCenter, c.hillsHalfRange));
        slots.add(interactiveSlot("山脉", 0xFF884422, c.mountainsCenter, c.mountainsHalfRange));
        slots.add(interactiveSlot("高原", 0xFFCC8844, c.plateauCenter, c.plateauHalfRange));
        slots.add(interactiveSlot("盆地", 0xFFAA6644, c.basinCenter, c.basinHalfRange));

        chart.setSlots(slots);

        Function<Double, String> fmt = v -> String.format("%.4f", v);

        // 创建 9 对滑块：全部可调（深海 lo 和海岸 hi 固定不可调）
        // 海洋 depth 字段范围 [-1,0]，所以海洋可调滑块范围用 [-1,0]（避免写入正数触发 Forge 修正循环）
        // 陆地滑块保持 [-1,1]
        for (int i = 0; i < slots.size(); i++) {
            DualRangeChart.Slot s = slots.get(i);
            boolean loRo = (s.loSetter() == null);
            boolean hiRo = (s.hiSetter() == null);
            double loVal = s.loGetter().getAsDouble();
            double hiVal = s.hiGetter().getAsDouble();
            Function<Double, String> formatter = fmt;  // 统一用 %.4f

            double slMax = (i < 4) ? 0.0 : 1.0;  // 海洋滑块上限 0.0，陆地保持 1.0
            ParamSlider lo = makeSlider(loVal, -1.0, slMax, formatter, s.loSetter(), false, i, false);
            lo.active = !loRo;
            loSliders.add(lo);

            ParamSlider hi = makeSlider(hiVal, -1.0, slMax, formatter, s.hiSetter(), false, i, true);
            hi.active = !hiRo;
            hiSliders.add(hi);
        }
    }

    /** 创建带联动钩子的 ParamSlider：onChange 实时同步 chart 自身+海洋耦合点，onValueCommitted 写配置 */
    private ParamSlider makeSlider(double val, double min, double max,
                                    Function<Double, String> fmt,
                                    DoubleConsumer setter, boolean qmark,
                                    int slotIdx, boolean isHi) {
        // onChange: 拖动时实时同步 chart 对应控制点 + 海洋耦合点（不写 config，避免文件锁）
        ParamSlider ps = new ParamSlider(0, 0, 100, min, max, val, v -> {
            chart.updateControlPoint(slotIdx, isHi, v);
            // 海洋耦合：相邻 slot 共享同一深度值
            if (slotIdx < 4) {
                if (isHi && slotIdx + 1 < 4) {
                    chart.updateControlPoint(slotIdx + 1, false, v);
                    if (slotIdx + 1 < loSliders.size()) loSliders.get(slotIdx + 1).setCurrentValue(v);
                } else if (!isHi && slotIdx > 0) {
                    chart.updateControlPoint(slotIdx - 1, true, v);
                    if (slotIdx - 1 < hiSliders.size()) hiSliders.get(slotIdx - 1).setCurrentValue(v);
                }
            }
        }, fmt);
        // onValueCommitted: release 时写 config + 触发预览
        if (setter != null) {
            ps.setOnValueCommitted(() -> {
                // 诊断：记录滑块提交前所有槽位值
                StringBuilder sb = new StringBuilder("SLIDER_COMMIT [");
                for (int i = 0; i < slots.size(); i++) {
                    DualRangeChart.Slot s = slots.get(i);
                    double lo = s.loGetter().getAsDouble();
                    double hi = s.hiGetter().getAsDouble();
                    sb.append(s.name()).append("=").append(String.format("%.4f/%.4f", lo, hi));
                    if (i < slots.size() - 1) sb.append(", ");
                }
                sb.append("]");
                System.out.println(sb.toString());

                setter.accept(ps.getCurrentValue());
                chart.refreshFromConfig();
                if (onMarkDirty != null) onMarkDirty.run();
            });
        }
        return ps;
    }

    /** 海洋 slot：lo = prevDepth（null 表示固定 -1），hi = depth（null 表示固定 0.02） */
    private static DualRangeChart.Slot oceanSlot(String name, int color,
        ForgeConfigSpec.DoubleValue prevDepthCfg, ForgeConfigSpec.DoubleValue depthCfg) {
        double loVal = (prevDepthCfg != null) ? prevDepthCfg.get() : -1.0;
        double hiVal = (depthCfg != null) ? depthCfg.get() : 0.02;
        // setter 加邻接约束 clamp：保证 lo ≤ hi (即当前 slot 的 lo 不超过 hi)
        // loSetter 写入 prevDepthCfg (F)：clamp 到 [-1, current depthCfg] (即 F ≤ G)
        DoubleConsumer loSet = (prevDepthCfg != null) ? v -> {
            double upper = (depthCfg != null) ? depthCfg.get() : 0.0;
            prevDepthCfg.set(clampOceanLo(v, upper));
        } : null;
        // hiSetter 写入 depthCfg (G)：clamp 到 [current prevDepthCfg, 0] (即 G ≥ F)
        DoubleConsumer hiSet = (depthCfg != null) ? v -> {
            double lower = (prevDepthCfg != null) ? prevDepthCfg.get() : -1.0;
            depthCfg.set(clampOceanHi(v, lower));
        } : null;
        DoubleSupplier loGet = (prevDepthCfg != null) ? prevDepthCfg::get : () -> -1.0;
        DoubleSupplier hiGet = (depthCfg != null) ? depthCfg::get : () -> 0.02;
        return new DualRangeChart.Slot(name, color, loVal, hiVal, loSet, hiSet, loGet, hiGet);
    }

    /** 海洋 lo setter 写入：clamp 到 [-1, upper]，保证 lo ≤ upper (即当前 slot lo ≤ 当前 slot hi) */
    private static double clampOceanLo(double v, double upper) {
        return Math.max(-1.0, Math.min(upper, v));
    }

    /** 海洋 hi setter 写入：clamp 到 [lower, 0]，保证 hi ≥ lower (即当前 slot hi ≥ 当前 slot lo) */
    private static double clampOceanHi(double v, double lower) {
        return Math.max(lower, Math.min(0.0, v));
    }

    /** 陆地区域 slot：lo = center - halfRange, hi = center + halfRange */
    private static DualRangeChart.Slot interactiveSlot(String name, int color,
                                                       ForgeConfigSpec.DoubleValue centerCfg,
                                                       ForgeConfigSpec.DoubleValue halfRangeCfg) {
        return new DualRangeChart.Slot(name, color,
                centerCfg.get() - halfRangeCfg.get(), centerCfg.get() + halfRangeCfg.get(),
                v -> { double hi = centerCfg.get() + halfRangeCfg.get();
                       double mid = (v + hi) / 2;
                       double newHr = Math.abs(hi - v) / 2;
                       System.out.println("  " + name + ".loSetter: v=" + String.format("%.4f", v) + " hi=" + String.format("%.4f", hi) + " → center=" + String.format("%.4f", mid) + " halfRange=" + String.format("%.4f", newHr));
                       centerCfg.set(mid); halfRangeCfg.set(newHr); },
                v -> { double lo = centerCfg.get() - halfRangeCfg.get();
                       double mid = (lo + v) / 2;
                       double newHr = Math.abs(v - lo) / 2;
                       System.out.println("  " + name + ".hiSetter: v=" + String.format("%.4f", v) + " lo=" + String.format("%.4f", lo) + " → center=" + String.format("%.4f", mid) + " halfRange=" + String.format("%.4f", newHr));
                       centerCfg.set(mid); halfRangeCfg.set(newHr); },
                () -> centerCfg.get() - halfRangeCfg.get(),
                () -> centerCfg.get() + halfRangeCfg.get());
    }

    private int top() { return baseY - scrollOffset; }

    /** 计算单个滑块宽度 */
    private int sliderWidth() {
        int fixed = ROW_LABEL_W + 2 * (ParamSlider.RESET_BTN_W + ParamSlider.RESET_GAP)
                  + 2 * Y_VAL_W + SLIDER_GAP + 2 * SIDE_PAD;
        int slW = (w - fixed) / 2;
        if (slW < 40) slW = 40;
        return slW;
    }

    public int getHeight() {
        if (collapsed) return HEADER_H + 2;
        return HEADER_H + 2 + CHART_H + 6 + loSliders.size() * (ROW_H + 1) + 4;
    }

    // ===== 渲染 =====

    public void render(GuiGraphics g, int mx, int my) {
        var f = Minecraft.getInstance().font;
        int ty = top();

        // 标题栏（可折叠）
        int hdrBg = collapsed ? 0xFF2a2f3a : 0xFF1e222c;
        g.fill(x, ty, x + w, ty + HEADER_H, hdrBg);
        g.fill(x, ty + HEADER_H - 1, x + w, ty + HEADER_H, 0xFF333333);
        String icon = collapsed ? "▸" : "▾";
        g.drawString(f, icon, x + 4, ty + (HEADER_H - 8) / 2, 0xFFAAAAAA);
        g.drawString(f, "地形高度图", x + 16, ty + (HEADER_H - 8) / 2, 0xFF66CCFF);

        if (collapsed) return;

        // 高度图
        int chartY = ty + HEADER_H + 2;
        chart.setBounds(x + 4, chartY, w - 8, CHART_H);
        chart.render(g, mx, my);
        chart.renderTooltips(g, mx, my);

        // 滑块行（9 行：深海～盆地）
        int rowTop = chartY + CHART_H + 4;
        int slW = sliderWidth();
        for (int i = 0; i < loSliders.size(); i++) {
            DualRangeChart.Slot s = slots.get(i);
            int rowY = rowTop + i * (ROW_H + 1);
            int cx = x + 4;
            if (i % 2 == 0) g.fill(cx, rowY, cx + w - 8, rowY + ROW_H, 0x14FFFFFF);
            int nameY = rowY + (ROW_H - 8) / 2;
            boolean ro = !loSliders.get(i).active;
            int nameColor = ro ? (s.color() & 0xFFFFFF) | 0x80FFFFFF : s.color();
            g.drawString(f, s.name(), cx, nameY, nameColor);

            // lo 滑块
            ParamSlider lo = loSliders.get(i);
            int loX = cx + ROW_LABEL_W;
            lo.setX(loX); lo.setY(rowY); lo.setWidth(slW);
            lo.render(g, mx, my, 0);
            int loResetX = loX + slW + ParamSlider.RESET_GAP;
            int loValX = loResetX + ParamSlider.RESET_BTN_W + 2;
            int loWy = (int) Math.round(chart.worldY(lo.getCurrentValue()));
            int loColor = ro ? 0xFF556677 : 0xFF88BBDD;
            g.drawString(f, "↓" + signed(loWy), loValX, nameY, loColor);

            // hi 滑块
            ParamSlider hi = hiSliders.get(i);
            int hiX = loValX + Y_VAL_W + SLIDER_GAP;
            hi.setX(hiX); hi.setY(rowY); hi.setWidth(slW);
            hi.render(g, mx, my, 0);
            int hiResetX = hiX + slW + ParamSlider.RESET_GAP;
            int hiValX = hiResetX + ParamSlider.RESET_BTN_W + 2;
            int hiWy = (int) Math.round(chart.worldY(hi.getCurrentValue()));
            int hiColor = ro ? 0xFF887755 : 0xFFFFCC88;
            g.drawString(f, "↑" + signed(hiWy), hiValX, nameY, hiColor);
        }
    }

    private static String signed(int v) { return v >= 0 ? "+" + v : String.valueOf(v); }

    // ===== 鼠标 =====

    public boolean mouseClicked(double mx, double my, int btn) {
        if (btn != 0) return false;
        int ty = top();
        // 标题栏折叠
        if (mx >= x && mx <= x + w && my >= ty && my <= ty + HEADER_H) {
            collapsed = !collapsed;
            return true;
        }
        if (collapsed) return false;

        if (chart.mouseClicked(mx, my, btn)) return true;

        int slW = sliderWidth();
        int rowTop = ty + HEADER_H + 2 + CHART_H + 4;
        for (int i = 0; i < loSliders.size(); i++) {
            int rowY = rowTop + i * (ROW_H + 1);

            ParamSlider lo = loSliders.get(i);
            if (!lo.active) continue;
            int loX = x + 4 + ROW_LABEL_W;
            lo.setX(loX); lo.setY(rowY); lo.setWidth(slW);
            if (lo.isHoveringReset((int) mx, (int) my)) { lo.resetToDefault(); chart.refreshFromConfig(); return true; }
            if (lo.isMouseOver(mx, my)) return lo.mouseClicked(mx, my, btn);

            int loResetX = loX + slW + ParamSlider.RESET_GAP;
            int loValX = loResetX + ParamSlider.RESET_BTN_W + 2;
            int hiX = loValX + Y_VAL_W + SLIDER_GAP;

            ParamSlider hi = hiSliders.get(i);
            if (!hi.active) continue;
            hi.setX(hiX); hi.setY(rowY); hi.setWidth(slW);
            if (hi.isHoveringReset((int) mx, (int) my)) { hi.resetToDefault(); chart.refreshFromConfig(); return true; }
            if (hi.isMouseOver(mx, my)) return hi.mouseClicked(mx, my, btn);
        }
        return false;
    }
    public boolean mouseDragged(double mx, double my, int btn, double dx, double dy) {
        if (chart.mouseDragged(mx, my, btn, dx, dy)) return true;
        for (ParamSlider s : loSliders) if (s.isFocused()) { s.mouseDragged(mx, my, btn, dx, dy); return true; }
        for (ParamSlider s : hiSliders) if (s.isFocused()) { s.mouseDragged(mx, my, btn, dx, dy); return true; }
        return false;
    }
    public boolean mouseReleased(double mx, double my, int btn) {
        if (chart.mouseReleased(mx, my, btn)) return true;
        for (ParamSlider s : loSliders) if (s.isFocused()) s.mouseReleased(mx, my, btn);
        for (ParamSlider s : hiSliders) if (s.isFocused()) s.mouseReleased(mx, my, btn);
        return false;
    }

    /** 渲染标题栏 tooltip */
    public void renderHeaderTooltip(GuiGraphics g, int mx, int my) {
        int ty = top();
        if (mx >= x && mx <= x + w && my >= ty && my <= ty + HEADER_H) {
            var f = Minecraft.getInstance().font;
            String tip = collapsed ? "点击展开" : "点击折叠";
            int tw = f.width(tip) + 8;
            int tx = Math.min(mx + 10, x + w - tw);
            g.fill(tx, my - 14, tx + tw, my - 2, 0xEE000000);
            g.drawString(f, tip, tx + 4, my - 12, 0xFFFFFF);
        }
    }
}
