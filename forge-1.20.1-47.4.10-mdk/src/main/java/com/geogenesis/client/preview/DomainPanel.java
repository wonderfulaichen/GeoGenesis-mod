package com.geogenesis.client.preview;

import com.geogenesis.client.ParamSlider;
import com.geogenesis.config.ConfigKeyCatalog;
import com.geogenesis.config.ConfigSpecRanges;
import com.geogenesis.config.PanelLayout;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * <b>由 {@link ConfigKeyCatalog} + {@link PanelLayout} 驱动的通用域面板</b>。
 *
 * <h2>为什么要有这个类（而不是继续手写每页）</h2>
 * <p>旧面板是「一页一份手写控件列表」，于是必然出现三类病，且每加一个键就要再犯一次：</p>
 * <ol>
 *   <li><b>漏键</b>：14 个 {@code BOTH} 真旋钮（大陆尺度、侵蚀整套…）从来没上过界面；</li>
 *   <li><b>假键</b>：27 个 {@code LEGACY_ONLY} 键摆在面板上冒充世界参数；</li>
 *   <li><b>数字漂移</b>：范围与默认值抄进说明文字，改一次默认值就漂一处（实测 5 处）。</li>
 * </ol>
 * <p>本类把这三件事变成<b>结构上不可能</b>：</p>
 * <ul>
 *   <li>键只从 {@link PanelLayout#domainDrivenKeys} 取 ⇒ 与探针校验的是<b>同一个集合</b>；</li>
 *   <li>类型、上下限、默认值全部从 {@link ConfigSpecRanges} 现取 ⇒ 没有可漂的字面量；</li>
 *   <li>行形态由 {@code Kind} 决定（开关 / 滑块 / 枚举）⇒ 不会把布尔键画成滑块。</li>
 * </ul>
 *
 * <h2>★ 为什么构造器收 {@link PanelLayout.Tab} 而不是 {@code Domain}</h2>
 * <p>因为「本页显示哪些键」的答案已经写在 {@code PanelLayout} 里（含"哪些键由专门 UI 渲染、
 * 因此要从目录驱动里扣掉"）。若这里再自己按 {@code Domain} 过滤，就会出现第二份口径
 * —— 正是本类要消灭的那种双真值源。</p>
 */
public class DomainPanel extends ConfigPanel {

    // ★ v14：行高与滑块高统一引用 UiKit（原来 30/22 与别处的 20/22/24 不一致）
    private static final int ROW_H = UiKit.ROW_H;
    private static final int SECTION_H = 26;
    private static final int LABEL_W = 118;
    private static final int VAL_W = 52;
    private static final int SLIDER_H = UiKit.SLIDER_H;

    private final PanelLayout.Tab tab;
    private final String title;
    private final String subtitle;

    private final Map<String, List<Item>> sections = new LinkedHashMap<>();
    private boolean readOnly = false;
    private java.util.function.Consumer<String> onToggle = null;

    /** 一行 = 一个键。 */
    private static final class Item {
        final String key;
        final ConfigSpecRanges.Kind kind;
        final double min, max;
        ParamSlider slider;
        Item(String key, ConfigSpecRanges.Kind kind, double min, double max) {
            this.key = key; this.kind = kind; this.min = min; this.max = max;
        }
    }

    public DomainPanel(PanelLayout.Tab tab, String title, String subtitle) {
        this.tab = tab;
        this.title = title;
        this.subtitle = subtitle;
        buildFromLayout();
    }

    public void setReadOnly(boolean ro) { this.readOnly = ro; }
    public void setOnToggle(java.util.function.Consumer<String> cb) { this.onToggle = cb; }

    /** 本页显示的域（无域的自定义页返回 {@code null}）。 */
    public ConfigKeyCatalog.Domain domain() { return tab.domain(); }

    /** 本面板渲染的全部键（按显示顺序）。探针据此核对"面板实际显示的键"。 */
    public List<String> keys() {
        List<String> out = new ArrayList<>();
        for (List<Item> l : sections.values()) for (Item it : l) out.add(it.key);
        return out;
    }

    // ---------------------------------------------------------------- 构建

    private void buildFromLayout() {
        sections.clear();
        for (String key : PanelLayout.domainDrivenKeys(tab)) {
            ConfigSpecRanges.Meta meta = ConfigSpecRanges.metaOf(key);
            if (meta == null) continue;   // 目录有、配置无 ⇒ 由探针报错，这里不造控件
            Item it = new Item(key, meta.kind(), meta.min(), meta.max());
            if (meta.numeric()) it.slider = makeSlider(it, meta);
            sections.computeIfAbsent(sectionOf(key), k -> new ArrayList<>()).add(it);
        }
    }

    /** 小节划分：旧算法页按 5 个陆地类型分；其余页只有一节。 */
    private String sectionOf(String key) {
        return tab == PanelLayout.Tab.LEGACY ? LegacyGrouping.typeLabel(key) : title;
    }

    private ParamSlider makeSlider(Item it, ConfigSpecRanges.Meta meta) {
        boolean isInt = meta.kind() == ConfigSpecRanges.Kind.INT;
        double cur = isInt ? ConfigSpecRanges.getInt(it.key, (int) meta.min())
                           : ConfigSpecRanges.getDouble(it.key, meta.min());
        ParamSlider[] holder = new ParamSlider[1];
        holder[0] = new ParamSlider(0, 0, 100, meta.min(), meta.max(), cur, v -> {
            if (isInt) {
                int iv = (int) Math.round(v);
                ConfigSpecRanges.setInt(it.key, iv);
                holder[0].setCurrentValue(iv);
            } else {
                ConfigSpecRanges.setDouble(it.key, v);
            }
            onMarkDirty.run();
        }, v -> isInt ? String.valueOf((int) Math.round(v)) : formatDouble(v));
        holder[0].setDefaultValue(isInt
                ? Math.round(parseDefault(meta)) : parseDefault(meta));
        holder[0].setTooltipText(KeyText.tooltip(it.key));
        return holder[0];
    }

    private static String formatDouble(double v) {
        double a = Math.abs(v);
        if (a >= 1000) return String.format(Locale.ROOT, "%.0f", v);
        if (a >= 10) return String.format(Locale.ROOT, "%.2f", v);
        return String.format(Locale.ROOT, "%.3f", v);
    }

    private static double parseDefault(ConfigSpecRanges.Meta meta) {
        try {
            return Double.parseDouble(meta.defaultText());
        } catch (NumberFormatException e) {
            return meta.min();
        }
    }

    // ---------------------------------------------------------------- 布局

    private int headerHeight() {
        // ★ 必须与 render() 的排版<b>同源</b>：v7 起副标题按像素宽折行，
        //   行数不定 ⇒ 用 UiKit.wrappedHeight 预计算同一个值。
        //   这里若与 render 不一致，hit test 就会整体偏（老 bug）。
        if (subtitle == null || subtitle.isEmpty()) return 0;
        return UiKit.wrappedHeight(subtitle, w);
    }

    private int rowsHeight() {
        int h = headerHeight();
        for (Map.Entry<String, List<Item>> e : sections.entrySet()) {
            // ★ 必须与 render() 一致：组头 SECTION_H + 行数×ROW_H + 组间留白 4
            h += SECTION_H + e.getValue().size() * ROW_H + 4;
        }
        return h;
    }

    @Override
    public int getHeight() {
        // ★ v22：底部留白 8 → 18 —— 原来最后一行贴死裁切边界，
        //   滚到 max 时数值胶囊底边仍被切掉一线。
        return rowsHeight() + 18 + extraHeight();
    }

    /** 行区结束后的 y（子类绘制追加区时用）。 */
    protected final int rowsBottomY() { return top() + rowsHeight(); }

    // ------------------------------------------------ 供子类追加图表的扩展点

    /** 行区之后追加的像素高度（默认 0）。 */
    protected int extraHeight() { return 0; }

    /** 行区之后追加的绘制；{@code y} 已含滚动偏移。 */
    protected void renderExtra(GuiGraphics g, int mx, int my, int y) { }

    /** 追加区的点击；返回 true = 已消费。 */
    protected boolean mouseClickedExtra(double mx, double my, int btn, int y) { return false; }

    protected boolean mouseDraggedExtra(double mx, double my, int btn, double dx, double dy) {
        return false;
    }

    protected void mouseReleasedExtra(double mx, double my, int btn) { }

    // ---------------------------------------------------------------- 渲染

    @Override
    public void render(GuiGraphics g, int mx, int my) {
        int curY = top();
        // ★ v7：标题已由 ConfigPanelHost 在面板顶部画（「世界与尺度 — 8 项」），
        //   这里不再重复画标题 —— 之前实机是两行同名文字叠着。
        //   ★ 副标题<b>折行</b>而不是 clip：诊断页那句长说明被裁成半句（用户截图）。
        if (subtitle != null && !subtitle.isEmpty()) {
            curY += UiKit.drawWrapped(g, subtitle, x, curY, w, UiKit.C_TXT_DIM);
        }

        for (Map.Entry<String, List<Item>> sec : sections.entrySet()) {
            int groupTop = curY;
            // ★ v12【彻底去掉分组底框】：
            //   用户连续两轮反馈"边框还是偏粗"。v11 已把透明度降到 0x04FFFFFF，
            //   但 roundRect 画的是<b>实心块</b> —— 即使 alpha=4/255，
            //   在深色面板上仍会显出一圈可辨的矩形边界（尤其圆角处）。
            //   ⇒ 不再画任何底，分组只靠<b>标题小字 + 上下留白</b>区分。
            //   这是参考图（无框设计）的做法，也符合用户多次强调的方向。
            g.drawString(font(), sec.getKey(), x + 8, groupTop + 6, UiKit.C_TXT_DIM, false);
            curY += SECTION_H;
            for (Item it : sec.getValue()) {
                curY = renderRow(g, mx, my, it, curY);
            }
            curY += 4;   // 组间留白
        }
        renderExtra(g, mx, my, curY + 4);
    }

    private int renderRow(GuiGraphics g, int mx, int my, Item it, int rowY) {
        boolean legacy = KeyText.isLegacy(it.key);
        String label = KeyText.label(it.key);
        int labelColor = legacy ? UiKit.C_TXT_DIM : UiKit.C_TXT;
        Font f = font();

        if (it.kind == ConfigSpecRanges.Kind.BOOL) {
            boolean on = ConfigSpecRanges.getBool(it.key, false);
            boolean hover = hit(x, rowY, w, ROW_H - 2, mx, my);
            // ★ v13：不再画任何行底（用户："彻底去掉试试"）。悬停靠<b>文字提亮</b>。
            int sw = 30, sh = 16;
            int switchX = x + w - sw - 8;
            g.drawString(f, UiKit.clip(label, switchX - (x + 8) - 8),
                    x + 8, rowY + (ROW_H - f.lineHeight) / 2,
                    hover ? UiKit.C_TXT : labelColor, false);
            UiKit.toggle(g, switchX, rowY + (ROW_H - 2 - sh) / 2,
                    sw, sh, on, hover, UiKit.C_ACCENT);
            if (hover) hoverTooltip = Component.literal(KeyText.tooltip(it.key));
            return rowY + ROW_H;
        }

        if (it.slider == null) {
            g.drawString(f, label, x + 8, rowY + (ROW_H - f.lineHeight) / 2, labelColor, false);
            return rowY + ROW_H;
        }

        // ★★ v8【修"控件右侧还有空间却不利用" + "长标签撞滑块"】★★
        //   旧做法：标签写死从 x+8 起、滑块写死从 x+LABEL_W(118) 起
        //   ⇒ ① 短标签（如"种子"）后面留一大段空白，滑块却挤在右边；
        //      ② 长标签（如"侵蚀迭代次数"）越过 118 直接画到滑块上。
        //   现在：标签列宽 = clamp(实际最长标签, 60, 面板 60%)，滑块吃满剩余宽度。
        int labelColW = labelColumnWidth();
        // ★ v13：不再画行底；悬停靠文字提亮
        boolean hoverRow = hit(x, rowY, w, ROW_H - 2, mx, my);
        g.drawString(f, UiKit.clip(label, labelColW - 10),
                x + 8, rowY + (ROW_H - f.lineHeight) / 2,
                hoverRow ? UiKit.C_TXT : labelColor, false);
        layoutSlider(it, rowY);
        it.slider.render(g, mx, my, 0);
        it.slider.renderTooltip(g, mx, my);
        return rowY + ROW_H;
    }

    /**
     * 标签列宽（★ v8 单一来源：绘制与命中都用它）。
     *
     * <p>上限是面板宽度的 60%，下限 60px —— 否则长标签会把滑块挤没，
     * 短标签又会白白浪费右侧空间。</p>
     */
    private int labelColumnWidth() {
        return Math.max(60, Math.min((int) (w * 0.6), labelColMaxW()));
    }

    /** 滑块几何（★ v8 单一来源，render 与 mouseClicked 共用）。 */
    private void layoutSlider(Item it, int rowY) {
        int labelColW = labelColumnWidth();
        it.slider.setX(x + labelColW);
        it.slider.setY(rowY + (ROW_H - SLIDER_H) / 2);
        it.slider.setWidth(Math.max(48, w - labelColW - 4));
    }

    /**
     * 本面板所有标签里最宽的那个所需的像素宽（★ v8）。
     *
     * <p>做缓存：每帧对所有键调 {@code font.width} 没必要，且键集合在
     * 构造后不变 —— 一次算好，宽度变化时重算（见 {@link #setBounds}）。</p>
     */
    private int labelColMaxW() {
        if (cachedLabelW < 0) {
            int m = 0;
            Font f = font();
            for (List<Item> l : sections.values()) {
                for (Item it : l) m = Math.max(m, f.width(KeyText.label(it.key)));
            }
            cachedLabelW = m + 10;
        }
        return cachedLabelW;
    }

    /** 标签列最宽需求（-1 = 未算）。 */
    private int cachedLabelW = -1;

    private static String clip(String s, int maxW) {
        var f = font();
        if (f.width(s) <= maxW) return s;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (f.width(sb.toString() + s.charAt(i) + "…") > maxW) break;
            sb.append(s.charAt(i));
        }
        return sb + "…";
    }

    // ---------------------------------------------------------------- 鼠标

    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        if (btn != 0) return false;
        int curY = top() + headerHeight();
        for (Map.Entry<String, List<Item>> sec : sections.entrySet()) {
            curY += SECTION_H;
            for (Item it : sec.getValue()) {
                int rowY = curY;
                if (it.kind == ConfigSpecRanges.Kind.BOOL) {
                    if (hit(x, rowY, w, ROW_H - 2, mx, my)) {
                        if (readOnly) return true;
                        ConfigSpecRanges.setBool(it.key, !ConfigSpecRanges.getBool(it.key, false));
                        if (onToggle != null) onToggle.accept(it.key);
                        onMarkDirty.run();
                        playClick();
                        return true;
                    }
                } else if (it.slider != null) {
                    layoutSlider(it, rowY);
                    if (it.slider.isHoveringReset((int) mx, (int) my)) {
                        if (readOnly) return true;
                        it.slider.resetToDefault();
                        return true;
                    }
                    if (it.slider.isMouseOver(mx, my)) {
                        if (readOnly) return true;
                        boolean handled = it.slider.mouseClicked(mx, my, btn);
                        // ★ v19：若这次点击落在值胶囊上，弹数值输入框（能滑能填）
                        if (handled) {
                            requestValueEdit(it.slider, KeyText.label(it.key), onMarkDirty);
                        }
                        return true;
                    }
                }
                curY += ROW_H;
            }
            curY += 4;   // ★ 与 render() 的组间留白一致，否则命中会逐组累偏
        }
        return mouseClickedExtra(mx, my, btn, rowsBottomY() + 4);
    }

    @Override
    public boolean mouseReleased(double mx, double my, int btn) {
        boolean handled = false;
        for (List<Item> l : sections.values()) {
            for (Item it : l) {
                if (it.slider != null && it.slider.isFocused()) {
                    it.slider.mouseReleased(mx, my, btn);
                    handled = true;
                }
            }
        }
        mouseReleasedExtra(mx, my, btn);
        return handled;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int btn, double dx, double dy) {
        for (List<Item> l : sections.values()) {
            for (Item it : l) {
                if (it.slider != null && it.slider.isFocused()) {
                    it.slider.mouseDragged(mx, my, btn, dx, dy);
                    return true;
                }
            }
        }
        return mouseDraggedExtra(mx, my, btn, dx, dy);
    }

    /** 从配置回读全部控件显示值（外部改配置后调用，避免界面停在旧值）。 */
    public void buildFromConfig() {
        for (List<Item> l : sections.values()) {
            for (Item it : l) {
                if (it.slider == null) continue;
                boolean isInt = it.kind == ConfigSpecRanges.Kind.INT;
                double v = isInt ? ConfigSpecRanges.getInt(it.key, (int) it.min)
                                 : ConfigSpecRanges.getDouble(it.key, it.min);
                it.slider.setCurrentValue(v);
            }
        }
    }
}
