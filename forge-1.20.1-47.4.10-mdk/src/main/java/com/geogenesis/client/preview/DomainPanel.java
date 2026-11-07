package com.geogenesis.client.preview;

import com.geogenesis.client.ParamSlider;
import com.geogenesis.config.ConfigKeyCatalog;
import com.geogenesis.config.ConfigSpecRanges;
import com.geogenesis.config.PanelLayout;
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

    private static final int ROW_H = 20;
    private static final int SECTION_H = 16;
    private static final int LABEL_W = 116;
    private static final int VAL_W = 44;
    private static final int SLIDER_H = 16;

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
        return 14 + ((subtitle != null && !subtitle.isEmpty()) ? 12 : 0);
    }

    private int rowsHeight() {
        int h = headerHeight();
        for (Map.Entry<String, List<Item>> e : sections.entrySet()) {
            h += SECTION_H + e.getValue().size() * ROW_H;
        }
        return h;
    }

    @Override
    public int getHeight() { return rowsHeight() + 8 + extraHeight(); }

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
        drawHeader(g, x, curY, title);
        curY += 14;
        if (subtitle != null && !subtitle.isEmpty()) {
            g.drawString(font(), clip(subtitle, w), x, curY, C_TEXT_DIM);
            curY += 12;
        }

        for (Map.Entry<String, List<Item>> sec : sections.entrySet()) {
            if (sections.size() > 1) {
                g.fill(x, curY + SECTION_H - 1, x + w, curY + SECTION_H, C_LINE);
                g.drawString(font(), sec.getKey(), x + 2, curY + 3, 0xFF66CCFF);
            }
            curY += SECTION_H;
            for (Item it : sec.getValue()) {
                curY = renderRow(g, mx, my, it, curY);
            }
        }
        renderExtra(g, mx, my, curY + 4);
    }

    private int renderRow(GuiGraphics g, int mx, int my, Item it, int rowY) {
        boolean legacy = KeyText.isLegacy(it.key);
        String label = KeyText.label(it.key);
        int labelColor = legacy ? 0xFF8A8F98 : C_TEXT;

        if (it.kind == ConfigSpecRanges.Kind.BOOL) {
            boolean on = ConfigSpecRanges.getBool(it.key, false);
            boolean hover = drawToggleRow(g, x, rowY, w, ROW_H - 2, label, on, mx, my);
            if (hover) hoverTooltip = Component.literal(KeyText.tooltip(it.key));
            return rowY + ROW_H;
        }

        if (it.slider == null) {
            g.drawString(font(), label, x + 6, rowY + 6, labelColor);
            g.drawString(font(), "（该类型不支持滑块）", x + LABEL_W, rowY + 6, 0xFFFFAA00);
            return rowY + ROW_H;
        }

        if (((rowY - top()) / ROW_H) % 2 == 0) {
            g.fill(x + 2, rowY, x + w - 2, rowY + ROW_H, 0x12FFFFFF);
        }
        g.drawString(font(), label, x + 6, rowY + (ROW_H - 8) / 2, labelColor);
        positionSlider(it, rowY);
        it.slider.render(g, mx, my, 0);
        it.slider.renderTooltip(g, mx, my);
        return rowY + ROW_H;
    }

    private void positionSlider(Item it, int rowY) {
        it.slider.setX(x + LABEL_W);
        it.slider.setY(rowY + (ROW_H - SLIDER_H) / 2);
        it.slider.setWidth(Math.max(40, w - LABEL_W - VAL_W - 6));
    }

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
                    positionSlider(it, rowY);
                    if (it.slider.isHoveringReset((int) mx, (int) my)) {
                        if (readOnly) return true;
                        it.slider.resetToDefault();
                        return true;
                    }
                    if (it.slider.isMouseOver(mx, my)) {
                        if (readOnly) return true;
                        return it.slider.mouseClicked(mx, my, btn);
                    }
                }
                curY += ROW_H;
            }
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
