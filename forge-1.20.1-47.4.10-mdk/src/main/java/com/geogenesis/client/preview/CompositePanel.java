package com.geogenesis.client.preview;

import net.minecraft.client.gui.GuiGraphics;

import java.util.ArrayList;
import java.util.List;

/**
 * <b>竖向堆叠多个 {@link ConfigPanel} 的容器页签</b>。
 *
 * <h2>为什么需要</h2>
 * <p>按域重排后，有几个域天然由"目录驱动的滑块行 + 一个专用图表"两段组成
 * （例如「世界与尺度」= 大陆/缩放滑块 + 尺度示意图；「显示与色带」= 预览标尺 + 采样/显示/色带）。
 * 旧代码为此把「世界高度图」「尺度预览」硬塞进一个巨型 {@code ParameterConfigPanel}，
 * 那个类因此长到 459 行且混着四类职责。</p>
 * <p>本类让每个子面板保持单一职责，容器只负责<b>垂直排布 + 事件转发</b>。</p>
 *
 * <h2>★ 高度必须每次现算</h2>
 * <p>子面板（如 {@code TerrainConfigPanel}）可以折叠，折叠后高度变化。
 * 因此 {@link #layout()} 在每次 render / 鼠标事件前重算偏移，
 * <b>不能</b>在构造时缓存 —— 缓存过一次就会出现"折叠后下面那一段还留在原位"的错位。</p>
 */
public class CompositePanel extends ConfigPanel {

    private final List<ConfigPanel> children = new ArrayList<>();
    private static final int GAP = 10;

    public CompositePanel(ConfigPanel... panels) {
        for (ConfigPanel p : panels) {
            if (p != null) children.add(p);
        }
    }

    public List<ConfigPanel> children() { return children; }

    /** 逐个重算子面板的绘制起点。返回每个子面板的顶端 y（与 children 同序）。 */
    private List<Integer> layout() {
        List<Integer> tops = new ArrayList<>(children.size());
        int y = top();
        for (ConfigPanel p : children) {
            tops.add(y);
            p.setBounds(x, y, w);
            p.setScrollOffset(0);
            y += p.getHeight() + GAP;
        }
        return tops;
    }

    @Override
    public int getHeight() {
        int h = 0;
        for (ConfigPanel p : children) h += p.getHeight() + GAP;
        return Math.max(0, h - GAP);
    }

    @Override
    public void setBounds(int x, int y, int w) {
        super.setBounds(x, y, w);
        layout();
    }

    @Override
    public void render(GuiGraphics g, int mx, int my) {
        layout();
        for (ConfigPanel p : children) p.render(g, mx, my);
        // 合并 tooltip：子面板各自算，最后一个非空的胜出（同一帧只可能悬停一个控件）
        for (ConfigPanel p : children) {
            var t = p.peekHoverTooltip();
            if (t != null) hoverTooltip = t;
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        List<Integer> tops = layout();
        for (int i = 0; i < children.size(); i++) {
            ConfigPanel p = children.get(i);
            if (my >= tops.get(i) && my <= tops.get(i) + p.getHeight()) {
                if (p.mouseClicked(mx, my, btn)) return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int btn) {
        boolean handled = false;
        for (ConfigPanel p : children) {
            if (p.mouseReleased(mx, my, btn)) handled = true;
        }
        return handled;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int btn, double dx, double dy) {
        for (ConfigPanel p : children) {
            if (p.mouseDragged(mx, my, btn, dx, dy)) return true;
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        List<Integer> tops = layout();
        for (int i = 0; i < children.size(); i++) {
            ConfigPanel p = children.get(i);
            if (my >= tops.get(i) && my <= tops.get(i) + p.getHeight()) {
                return p.mouseScrolled(mx, my, delta);
            }
        }
        return false;
    }
}
