package com.geogenesis.client.preview;

import com.geogenesis.client.ParamSlider;
import com.geogenesis.client.preview.mixer.WorldHeightBar;
import com.geogenesis.config.ConfigKeyCatalog;
import com.geogenesis.config.ConfigSpecRanges;
import com.geogenesis.config.GeoGenesisConfig;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * 「预览标尺」面板 —— {@code seaLevel} / {@code minY} / {@code maxY} 三个 <b>{@code PREVIEW_ONLY}</b> 键。
 *
 * <h2>★ 2026-11-07 重构：这个类的职责被大幅收窄（459 行 → 本文件）</h2>
 * <p>它以前叫「参数」页，塞了四类互不相干的东西：</p>
 * <ol>
 *   <li>海陆偏置 / 海岸线位移 / 语义亲和度…（<b>全是 {@code LEGACY_ONLY}</b>，对新世界零效果）；</li>
 *   <li>侵蚀开关（已迁到「侵蚀与河流」页）；</li>
 *   <li>大陆 FBM / 峰高比例 / 水平垂直缩放（已迁到「世界与尺度」页，且现在有真实上下限）；</li>
 *   <li>世界高度标尺（{@code seaLevel/minY/maxY}）。</li>
 * </ol>
 * <p>前三类的共同病根是<b>页签按历史堆积而不是按消费点划分</b>。现在域由
 * {@link ConfigKeyCatalog.Domain} 定义、页由 {@code PanelLayout} 派生，
 * 这个类只剩第 4 类 —— 而那三个键恰恰是<b>最容易误导用户</b>的一类：
 * 它们只改预览色阶与高度条，<b>不驱动游戏世界</b>（generator 用编译期常量
 * {@code WORLD_MIN_Y=-64 / WORLD_MAX_Y=320 / SEA_LEVEL=63}）。</p>
 *
 * <p>所以本面板的标题、副标题、每个滑块的 tooltip 都必须写明「仅预览」。</p>
 */
public class ParameterConfigPanel extends ConfigPanel {

    // ★ v14：行高统一引用 UiKit
    private static final int ROW_H = UiKit.SLIDER_H;
    private static final int BAR_H = 132;

    /** 预览标尺三键（顺序即 WorldHeightBar 的标记顺序：最高 / 海面 / 底）。 */
    private static final String[] MARK_KEYS = {"maxY", "seaLevel", "minY"};
    private static final String[] MARK_LABELS = {"最高点", "海平面", "世界底"};
    private static final int[] MARK_COLORS = {0xFFFF4444, 0xFF4488FF, 0xFF888888};

    private final List<ParamSlider> sliders = new ArrayList<>();
    private final WorldHeightBar heightBar = new WorldHeightBar();

    public ParameterConfigPanel() {
        // ★ v16：示意图只读 ⇒ 不再需要 onMarkDirty 回调（改值只走上方的滑块）
        for (int i = 0; i < MARK_KEYS.length; i++) {
            sliders.add(makeSlider(i));
        }
        heightBar.setMarks(List.of(
            new WorldHeightBar.Mark(MARK_LABELS[0], MARK_COLORS[0],
                () -> ConfigSpecRanges.getInt("maxY", 320),
                v -> { ConfigSpecRanges.setInt("maxY", v); onMarkDirty.run(); }),
            new WorldHeightBar.Mark(MARK_LABELS[1], MARK_COLORS[1],
                () -> ConfigSpecRanges.getInt("seaLevel", 63),
                v -> { ConfigSpecRanges.setInt("seaLevel", v); onMarkDirty.run(); }),
            new WorldHeightBar.Mark(MARK_LABELS[2], MARK_COLORS[2],
                () -> ConfigSpecRanges.getInt("minY", -64),
                v -> { ConfigSpecRanges.setInt("minY", v); onMarkDirty.run(); })
        ));
    }

    private ParamSlider makeSlider(int idx) {
        String key = MARK_KEYS[idx];
        ConfigSpecRanges.Meta meta = ConfigSpecRanges.metaOf(key);
        int min = meta != null ? (int) meta.min() : -512;
        int max = meta != null ? (int) meta.max() : 1024;
        int cur = ConfigSpecRanges.getInt(key, (int) Math.round((min + max) / 2.0));
        ParamSlider[] holder = new ParamSlider[1];
        holder[0] = new ParamSlider(0, 0, 100, min, max, cur, v -> {
            int val = clampOrder(idx, (int) Math.round(v));
            ConfigSpecRanges.setInt(key, val);
            holder[0].setCurrentValue(val);
            heightBar.refreshFromConfig();
            onMarkDirty.run();
        }, (Function<Double, String>) v -> String.valueOf((int) Math.round(v)));
        holder[0].setDefaultValue(meta != null ? Math.round(parse(meta.defaultText(), cur)) : cur);
        holder[0].setTooltipText(KeyText.tooltip(key)
                + "\n这是滑块本身的范围；它只影响预览的色阶与高度条。");
        holder[0].setOnReset(() -> { heightBar.refreshFromConfig(); onMarkDirty.run(); });
        return holder[0];
    }

    /** 高度顺序约束：最高点 > 海平面 > 世界底（沿用旧实现的不变量，避免颠倒后 Y 轴重复标签）。 */
    private static int clampOrder(int idx, int val) {
        int maxY = ConfigSpecRanges.getInt("maxY", 320);
        int sea = ConfigSpecRanges.getInt("seaLevel", 63);
        int minY = ConfigSpecRanges.getInt("minY", -64);
        return switch (idx) {
            case 0 -> Math.max(sea + 1, val);
            case 1 -> Math.max(minY + 1, Math.min(maxY - 1, val));
            default -> Math.min(sea - 1, val);
        };
    }

    private static double parse(String s, double fallback) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    @Override
    public int getHeight() {
        // ★ v8：按折行后的真实高度算（原来写死 14/26/12 等，警示折行后会多出两行被裁）
        int[] geo = layoutRows();
        int barBottom = geo[1] + BAR_H;
        return barBottom - top() + 20;
    }

    @Override
    public void render(GuiGraphics g, int mx, int my) {
        // ★ v8：标题由 ConfigPanelHost 统一画；行区几何来自 layoutRows()
        int[] geo = layoutRows();
        int curY = top() + 2;

        // ★ 必须显式写在界面上：这三个键不驱动世界。旧版把它们称作"世界高度"，
        //   用户拖完以为世界变高，实际只有预览色阶动（真实误导）。
        //   ★ v8：改为<b>折行</b>（这句 40+ 字，单行必被面板右缘裁掉）
        UiKit.drawWrapped(g, WARN, x, curY, Math.max(60, w), 0xFFFFAA00);

        curY = geo[0];
        // ★★ v9【修"控件右侧大片空白"】★★
        //   旧式 `slW = w - 150` 是<b>凭感觉估的</b>：标签占 62px 之后，
        //   滑块右端距面板右缘还剩一大截（用户截图里半行都是空的）。
        //   现在按「标签列 → 滑块吃满剩余」算，数值胶囊由 ParamSlider 内部再扣。
        int[] sl = sliderLayout();
        int labelW = sl[0] - x;
        for (int i = 0; i < sliders.size(); i++) {
            ParamSlider ps = sliders.get(i);
            int rowY = curY + i * ROW_H;
            // ★ v13：悬停底色彻底去掉，改用文字提亮
            boolean hoverRow = mx >= x && mx < x + w && my >= rowY && my < rowY + ROW_H;
            g.drawString(font(), UiKit.clip(MARK_LABELS[i], labelW - 8),
                    x + 8, rowY + (ROW_H - font().lineHeight) / 2,
                    hoverRow ? UiKit.C_TXT : UiKit.C_TXT_SUB, false);
            ps.setX(sl[0]);
            ps.setY(rowY + 2);
            ps.setWidth(sl[1]);
            ps.render(g, mx, my, 0);
        }
        for (ParamSlider ps : sliders) ps.renderTooltip(g, mx, my);
        curY += sliders.size() * ROW_H + 8;

        g.fill(x, curY, x + w, curY + 1, UiKit.C_DIVIDER);
        curY += 5;
        // ★ v16：示意图已改为只读 —— 文案不能再写"拖动标记"
        g.drawString(font(), UiKit.clip("标尺示意（用上方滑块调整）", w),
                x + 2, curY, UiKit.C_TXT_DIM, false);
        curY += 14;
        heightBar.setBounds(x + 4, curY, w - 8, BAR_H);
        heightBar.render(g, mx, my);
        heightBar.renderTooltips(g, mx, my);
    }

    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        if (btn != 0) return false;
        // ★ v8/v9：与 render 共用 layoutRows() + 同一套滑块几何（labelW/slW）
        int[] geo = layoutRows();
        int curY = geo[0];
        int barY = geo[1];
        for (int i = 0; i < sliders.size(); i++) {
            ParamSlider ps = sliders.get(i);
            int[] sl = sliderLayout();
            ps.setX(sl[0]);
            ps.setY(curY + i * ROW_H + 2);
            ps.setWidth(sl[1]);
        }
        for (ParamSlider ps : sliders) {
            if (ps.isHoveringReset((int) mx, (int) my)) { ps.resetToDefault(); return true; }
            if (ps.isMouseOver(mx, my)) {
                boolean h = ps.mouseClicked(mx, my, btn);
                if (h) requestValueEdit(ps, "预览标尺", onMarkDirty);
                return h;
            }
        }
        heightBar.setBounds(x + 4, barY, w - 8, BAR_H);
        return heightBar.mouseClicked(mx, my, btn);
    }

    /**
     * 滑块的水平几何（★ v9 单一来源：render 与 mouseClicked 共用）。
     *
     * @return {@code {滑块 x, 滑块宽}}
     */
    private int[] sliderLayout() {
        int labelW = 62;
        return new int[]{x + labelW, Math.max(60, w - labelW - 6)};
    }

    /** 顶部警示文字（render 与 layoutRows 共用同一份）。 */
    private static final String WARN =
            "仅预览标尺：不驱动游戏世界（生成器用编译期常量 -64 / 320 / 63）。";

    /**
     * 行区与标尺栏的 y（★ v8 单一来源，render 与 mouseClicked 共用）。
     *
     * @return {@code {滑块区起点 y, 标尺栏 y}}
     */
    private int[] layoutRows() {
        int warnH = UiKit.wrappedHeight(WARN, Math.max(60, w)) + 4;
        int curY = top() + 2 + warnH;
        int barY = curY + sliders.size() * ROW_H + 8 + 5 + 14;
        return new int[]{curY, barY};
    }

    @Override
    public boolean mouseDragged(double mx, double my, int btn, double dx, double dy) {
        for (ParamSlider ps : sliders) if (ps.isFocused()) { ps.mouseDragged(mx, my, btn, dx, dy); return true; }
        if (mx >= x && mx <= x + w && my >= top() && my <= top() + getHeight()) {
            return heightBar.mouseDragged(mx, my, btn, dx, dy);
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int btn) {
        for (ParamSlider ps : sliders) if (ps.isFocused()) ps.mouseReleased(mx, my, btn);
        heightBar.mouseReleased(mx, my, btn);
        buildFromConfig();
        return false;
    }

    /** 回读配置（外部改过预设/重置后调用）。 */
    public void buildFromConfig() {
        for (int i = 0; i < sliders.size(); i++) {
            sliders.get(i).setCurrentValue(
                    ConfigSpecRanges.getInt(MARK_KEYS[i], (int) sliders.get(i).getCurrentValue()));
        }
        heightBar.refreshFromConfig();
    }

    /** 只读模式下禁用（游戏内不改配置）。 */
    public void setReadOnly(boolean ro) {
        for (ParamSlider ps : sliders) ps.active = !ro;
    }

    /**
     * 通用 Y 滑块构造器（getter/setter 模式）—— static 供 {@link TerrainConfigPanel} 的雪线双曲线复用。
     *
     * <p>⚠ 保留在此类而非抽新文件：它被 {@code TerrainConfigPanel} 直接静态调用，
     * 移动会制造无意义的 diff。它不读任何配置，只负责造控件。</p>
     */
    static void addYSlider(List<ParamSlider> list, String label,
                           java.util.function.DoubleSupplier getter,
                           java.util.function.DoubleConsumer setter,
                           double min, double max, Function<Double, String> fmt) {
        ParamSlider ps = new ParamSlider(0, 0, 100, min, max, getter.getAsDouble(), setter::accept, fmt);
        ps.setTooltipText(label);
        list.add(ps);
    }
}
