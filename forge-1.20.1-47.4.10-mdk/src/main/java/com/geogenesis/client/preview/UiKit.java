package com.geogenesis.client.preview;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;

/**
 * <b>自绘控件工具箱</b>（★ 2026-11-09）。
 *
 * <h2>为什么必须自绘</h2>
 * <p>用户要求「实现示意图效果」，而 MC 原生 widget 做不到：</p>
 * <ul>
 *   <li>{@code Button} 是固定灰框 + 立体阴影，没有圆角、没有悬停高亮；</li>
 *   <li>{@code AbstractSliderButton} 是灰底方块 + 白字，不是细轨道 + 圆点；</li>
 *   <li>没有卡片容器、没有行式列表、没有药丸分段控件。</li>
 * </ul>
 * <p>⇒ 全部改成 {@link GuiGraphics#fill} 手绘。本类集中提供那些<b>可复用</b>的原语，
 * 避免每个面板各写一份（那正是"同一算法写三遍"的老问题）。</p>
 *
 * <h2>配色（用户认可的那套）</h2>
 * <pre>
 *   面板底     #141414    卡片底     #1E1E1E
 *   卡片边     #2A2A2A    分隔线     #FFFFFF11 (alpha)
 *   主色（绿） #48DE56    次色（青） #68C4A8
 *   正文       #F2F2F2    次要       #9E9E9E     弱化  #6E6E6E
 * </pre>
 */
public final class UiKit {

    // ---- 配色 ----
    public static final int C_PANEL      = 0xE6141414;
    public static final int C_CARD       = 0xFF1E1E1E;
    public static final int C_CARD_HOVER = 0xFF242424;
    public static final int C_CARD_EDGE  = 0xFF2A2A2A;
    public static final int C_DIVIDER    = 0x11FFFFFF;

    public static final int C_ACCENT     = 0xFF48DE56;   // 主色：地形绿
    public static final int C_ACCENT_TXT = 0xFF0C2210;   // 绿底上的深字
    public static final int C_TEAL       = 0xFF68C4A8;   // 次强调
    public static final int C_TRACK      = 0xFF3A4450;   // 滑轨底
    public static final int C_CHIP_ON    = 0x33FFFFFF;
    public static final int C_CHIP_OFF   = 0x0CFFFFFF;

    public static final int C_TXT        = 0xFFF2F2F2;
    public static final int C_TXT_SUB    = 0xFF9E9E9E;
    public static final int C_TXT_DIM    = 0xFF6E6E6E;

    /**
     * <h2>★ 为什么必须集中定义尺寸与圆角（v14）</h2>
     *
     * <p>用户连续多轮反馈「一半新一半旧 / 换地方就变」。
     * 根因不是某个控件没改，而是<b>同一套视觉参数散落在 8 个文件里各自定义</b>：</p>
     * <pre>
     *   ROW_H : CavePanel 20 · OrePanel 22 · DisplayPanel 24 · UiKit 26 · DomainPanel 30
     *   圆角  : 开关 h/2 · 数值胶囊 h/2 · 色条 2 · 卡片 6 或 7 · 按钮 h/2 或 4
     *   高度  : ParamSlider 18 · 各处 SLIDER_ROW_H 20/22
     * </pre>
     * <p>⇒ 同一控件在不同页里<b>数值不同</b>，观感就不同，看着像"两套 UI"。</p>
     *
     * <p>现在全部收敛到本类，页面只许引用、不许自己写字面量：</p>
     * <ul>
     *   <li>{@link #ROW_H} = 26 —— 所有设置行的行高；</li>
     *   <li>{@link #SLIDER_H} / {@link #CHIP_H} / {@link #SWITCH_H} / {@link #SWITCH_W}
     *       —— 控件本体的固定尺寸；</li>
     *   <li>{@link #RADIUS_CARD} / {@link #RADIUS_FIELD} —— 容器 6 / 槽 3 两档；
     *       "完全胶囊"用 {@code h / 2}（由 {@link #pillRadius} 统一算）。</li>
     * </ul>
     */
    public static final int ROW_H = 26;
    public static final int SLIDER_H = 20;
    /** 按钮/胶囊控件的标准高度。 */
    public static final int H_CONTROL = 20;
    public static final int CHIP_H = 16;
    public static final int SWITCH_H = 16;
    public static final int SWITCH_W = 30;
    public static final int RADIUS_CARD = 6;
    public static final int RADIUS_FIELD = 3;

    /** 完全胶囊的半径（= 高度一半，唯一算法）。 */
    public static int pillRadius(int h) {
        return Math.max(1, h / 2);
    }

    private UiKit() { }

    public static Font font() {
        return Minecraft.getInstance().font;
    }

    // =================================================================
    // 一、基本图形
    // =================================================================

    /**
     * 圆角矩形（★ MC 没有原语，必须自己拼）。
     *
     * <h2>★ v14 修"数值框看着是方角"</h2>
     * <p>旧实现只画「中间十字 + 每行一条缩进的整行」：</p>
     * <pre>
     *   第 0 行:  ██████        缩进 wIns 后整行都填
     *   第 1 行:  ██████
     * </pre>
     * <p>问题：圆角处每行是<b>一整条</b>，所以角的轮廓由"行首缩进量"决定，
     * 而 {@code wIns} 取整后在小半径下跳变很大（r=7 时顶行直接缩 4px），
     * 视觉上就是<b>直角切一刀</b>，而不是圆弧 —— 用户看到 14px 高的数值框
     * 是"方角"正是这个原因。</p>
     *
     * <p>现在改为<b>逐像素求圆弧内边界</b>：每一行按 sqrt 精确算出该行圆弧的
     * 起止 x，圆角区用"从 wIns 到 wIns+1"的细条补齐，中部仍整行填充。
     * 这样在 GUI 缩放下也能看出真正的圆。</p>
     */
    public static void roundRect(GuiGraphics g, int x0, int y0, int x1, int y1,
                                 int r, int color) {
        if (x1 <= x0 || y1 <= y0) return;
        int maxR = Math.min((x1 - x0) / 2, (y1 - y0) / 2);
        r = Math.max(0, Math.min(r, maxR));
        if (r == 0) {
            g.fill(x0, y0, x1, y1, color);
            return;
        }
        // 中部：整行填充（不含圆角区）
        g.fill(x0, y0 + r, x1, y1 - r, color);
        // 上/下圆角区：逐像素求该行圆弧起点
        for (int i = 0; i < r; i++) {
            double dy = r - i - 0.5;
            int ins = (int) Math.ceil(r - Math.sqrt(Math.max(0.0, (double) r * r - dy * dy)));
            g.fill(x0 + ins, y0 + i, x1 - ins, y0 + i + 1, color);
            g.fill(x0 + ins, y1 - i - 1, x1 - ins, y1 - i, color);
        }
    }

    /** 圆角矩形的描边（只画边，不填充）。 */
    public static void roundRectOutline(GuiGraphics g, int x0, int y0, int x1, int y1,
                                        int r, int color) {
        // 上下边（中间直段）
        g.fill(x0 + r, y0, x1 - r, y0 + 1, color);
        g.fill(x0 + r, y1 - 1, x1 - r, y1, color);
        // 左右边
        g.fill(x0, y0 + r, x0 + 1, y1 - r, color);
        g.fill(x1 - 1, y0 + r, x1, y1 - r, color);
        // 圆角近似
        for (int i = 0; i < r; i++) {
            double dy = r - i - 0.5;
            int wIns = (int) Math.round(r - Math.sqrt(Math.max(0, r * r - dy * dy)));
            g.fill(x0 + wIns, y0 + i, x0 + wIns + 1, y0 + i + 1, color);
            g.fill(x1 - wIns - 1, y0 + i, x1 - wIns, y0 + i + 1, color);
            g.fill(x0 + wIns, y1 - i - 1, x0 + wIns + 1, y1 - i, color);
            g.fill(x1 - wIns - 1, y1 - i - 1, x1 - wIns, y1 - i, color);
        }
    }

    /** 水平分隔线。 */
    public static void divider(GuiGraphics g, int x0, int x1, int y) {
        g.fill(x0, y, x1, y + 1, C_DIVIDER);
    }

    // =================================================================
    // 二、卡片
    // =================================================================

    /**
     * 卡片容器：圆角底 + 左侧 3px 主色条（分组标识）。
     *
     * @return 内容起始 y（卡片顶 + 内边距）
     */
    public static int card(GuiGraphics g, int x0, int y0, int x1, int y1, int accent) {
        roundRect(g, x0, y0, x1, y1, 4, C_CARD);
        roundRectOutline(g, x0, y0, x1, y1, 4, C_CARD_EDGE);
        if (accent != 0) {
            g.fill(x0 + 1, y0 + 4, x0 + 3, y1 - 4, accent);
        }
        return y0 + 8;
    }

    /** 卡片标题（小号次要色）。 */
    public static int cardTitle(GuiGraphics g, int x, int y, String title) {
        g.drawString(font(), title, x, y, C_TXT_SUB, false);
        return y + 14;
    }

    // =================================================================
    // 三、行式控件
    // =================================================================

    /** 行标签（左侧，垂直居中）。 */
    public static void rowLabel(GuiGraphics g, int x, int rowY, int rowH, String label) {
        Font f = font();
        g.drawString(f, label, x, rowY + (rowH - f.lineHeight) / 2, C_TXT_SUB, false);
    }

    /**
     * <b>滑块</b>：凹槽轨道 + 已填充段 + 圆形手柄 + 右侧数值。
     *
     * <p>★ v9【可交互性】轨道从 2px 细线改成 <b>8px 凹槽</b>（{@link #field} 底）——
     * 用户反馈"看不出能拖"。现在即使没悬停，也能看到一条明确的输入槽。</p>
     *
     * @param frac 0..1
     */
    public static void slider(GuiGraphics g, int x0, int y0, int w, int rowH,
                              double frac, String value, int color, boolean hover) {
        Font f = font();
        int valW = value == null ? 0 : f.width(value) + 10;
        int trackX0 = x0;
        int trackX1 = x0 + w - valW;
        int cy = y0 + rowH / 2;
        int grooveH = 6;      // ★ v11：8 → 6，槽更细
        int gy = cy - grooveH / 2;

        // 凹槽底（★ v11：单条上沿暗线，见 field 的 javadoc）
        field(g, trackX0, gy, trackX1 - trackX0, grooveH, hover);

        // 已填充段：填满槽高，与槽同圆角（不再缩进 2px —— 细槽一缩就没厚度了）
        int filled = trackX0 + (int) ((trackX1 - trackX0) * Math.max(0, Math.min(1, frac)));
        if (filled > trackX0 + 2) {
            roundRect(g, trackX0 + 1, gy + 1, filled, gy + grooveH - 1,
                    Math.min(RADIUS_FIELD, grooveH / 2), color);
        }
        // 手柄：三层同心圆（阴影 / 白圈 / 主色芯），在任何底色上都清晰
        int r = hover ? 4 : 3;
        dot(g, filled, cy + 1, r + 1, 0x55000000);
        dot(g, filled, cy, r + 1, 0xFFFFFFFF);
        dot(g, filled, cy, Math.max(2, r - 1), color);
        if (value != null) {
            g.drawString(f, value, trackX1 + 10, cy - f.lineHeight / 2,
                    hover ? C_TXT : C_TXT_SUB, false);
        }
    }

    /**
     * <b>iOS 风格开关</b>（★ v7，对齐新参考图）：圆角胶囊滑轨 + 圆形滑钮。
     *
     * <p>参考图里没有「开/关」文字，只有<b>位置 + 颜色</b>表达状态：
     * 开 = 亮紫/主色轨 + 滑钮靠右；关 = 深灰轨 + 滑钮靠左。
     * 之前的"胶囊里写开/关"是多余的，也显得廉价。</p>
     *
     * <p>用 {@code pad} 让滑钮略小于轨高，形成参考图那种留边效果。</p>
     */
    public static void toggle(GuiGraphics g, int x, int y, int w, int h,
                              boolean on, boolean hover, int onColor) {
        int r = pillRadius(h);
        int track = on ? onColor : (hover ? 0xFF4A4A4A : 0xFF3A3A3A);
        roundRect(g, x, y, x + w, y + h, r, track);
        // 滑钮：直径 h-4，左右各留 2px
        int knobD = h - 4;
        int knobR = knobD / 2;
        int kx = on ? x + w - 2 - knobR : x + 2 + knobR;
        int ky = y + 2 + knobR;
        dot(g, kx, ky, knobR, on ? 0xFFFFFFFF : 0xFFBFBFBF);
    }

    /**
     * <b>设置行</b>（对齐新参考图）：左标签 + 右侧控件位。
     *
     * <p>参考图的节奏感来自：<b>行间靠留白分隔，不画横线</b>；
     * 每行高 ~34px；左标签离左边界 ~24px。</p>
     *
     * @return 右侧控件区的起始 x
     */
    public static int settingRow(GuiGraphics g, int x, int rowY, int w, int rowH, String label) {
        Font f = font();
        g.drawString(f, label, x + 8, rowY + (rowH - f.lineHeight) / 2, C_TXT, false);
        return x + w - 8;   // 控件以右边界为准，由调用方向左扣宽度
    }

    /**
     * <b>值胶囊 / 按钮胶囊</b>（对齐新参考图）：高圆角、深灰底、无描边。
     *
     * <p>参考图里 "Accent" / "Enhanced" / "#00c839" / "Report it!" / "Done"
     * 全是这种胶囊 —— 圆角 = 高度的一半，颜色只有明度差。</p>
     */
    public static void pill(GuiGraphics g, int x, int y, int w, int h,
                            String label, boolean bright, boolean hover) {
        Font f = font();
        int bg = bright ? (hover ? 0xFF3A3A3A : 0xFF2E2E2E)
                        : (hover ? 0xFF3E3E3E : 0xFF333333);
        roundRect(g, x, y, x + w, y + h, h / 2, bg);
        g.drawString(f, label, x + (w - f.width(label)) / 2,
                y + (h - f.lineHeight) / 2, C_TXT, false);
    }

    /** 实心圆（逐行扫描；MC 无圆原语）。 */
    public static void dot(GuiGraphics g, int cx, int cy, int r, int color) {
        for (int dy = -r; dy <= r; dy++) {
            int span = (int) Math.round(Math.sqrt(Math.max(0, r * r - dy * dy)));
            g.fill(cx - span, cy + dy, cx + span + 1, cy + dy + 1, color);
        }
    }

    /**
     * <b>药丸按钮</b>：主操作 = 主色底 + 深字；其余 = 深灰胶囊 + 亮字。
     *
     * <p>★ v7 对齐新参考图：<b>去掉描边</b>（参考图所有控件都没有边框，
     * 只有底色的明度差），圆角改为高度的一半（真胶囊）。</p>
     */
    public static void button(GuiGraphics g, int x, int y, int w, int h,
                              String label, boolean primary, boolean hover) {
        Font f = font();
        if (primary) {
            int bg = hover ? 0xFF5EE86C : C_ACCENT;
            roundRect(g, x, y, x + w, y + h, h / 2, bg);
            g.drawString(f, label, x + (w - f.width(label)) / 2,
                    y + (h - f.lineHeight) / 2, C_ACCENT_TXT, false);
        } else {
            int bg = hover ? 0xFF3E3E3E : 0xFF333333;
            roundRect(g, x, y, x + w, y + h, h / 2, bg);
            g.drawString(f, label, x + (w - f.width(label)) / 2,
                    y + (h - f.lineHeight) / 2, C_TXT, false);
        }
    }

    /**
     * <b>分段控件</b>（药丸）：选中项 = 提亮胶囊，无描边。
     *
     * @return 被点击的索引（未点击返回 -1）
     */
    public static int segmented(GuiGraphics g, int x, int y, int w, int h,
                                String[] options, int active, int mx, int my) {
        int n = options.length;
        if (n == 0) return -1;
        int gap = 4;
        int sw = (w - gap * (n - 1)) / n;
        int clicked = -1;
        for (int i = 0; i < n; i++) {
            int x0 = x + i * (sw + gap);
            boolean on = i == active;
            boolean hover = mx >= x0 && mx < x0 + sw && my >= y && my < y + h;
            int bg = on ? 0xFF4A4A4A : (hover ? 0xFF3A3A3A : 0xFF2A2A2A);
            roundRect(g, x0, y, x0 + sw, y + h, h / 2, bg);
            Font f = font();
            g.drawString(f, options[i], x0 + (sw - f.width(options[i])) / 2,
                    y + (h - f.lineHeight) / 2, on ? C_TXT : C_TXT_DIM, false);
            if (hover && clicked < 0) clicked = i;
        }
        return clicked;
    }

    /** 下拉框外观（新参考图：胶囊 + 右侧 v）；返回是否 hover。 */
    public static boolean dropdown(GuiGraphics g, int x, int y, int w, int h,
                                   String text, int mx, int my) {
        boolean hover = mx >= x && mx < x + w && my >= y && my < y + h;
        roundRect(g, x, y, x + w, y + h, h / 2, hover ? 0xFF3E3E3E : 0xFF333333);
        Font f = font();
        g.drawString(f, text, x + 10, y + (h - f.lineHeight) / 2, C_TXT, false);
        g.drawString(f, "v", x + w - 12, y + (h - f.lineHeight) / 2, C_TXT_DIM, false);
        return hover;
    }

    // =================================================================
    // 四、图表原语（★ v8：mixer 里 4 个图表类共用，不再各画一套框/网格/轴）
    // =================================================================

    /** 图表框：圆角深底 + 极淡描边（替代各处的 4 条 0xFF333344 硬边）。 */
    public static void chartFrame(GuiGraphics g, int x, int y, int w, int h) {
        roundRect(g, x, y, x + w, y + h, 4, 0xFF1A1A1A);
        roundRectOutline(g, x, y, x + w, y + h, 4, 0x1AFFFFFF);
    }

    /**
     * 图表网格线（横/竖）。
     *
     * @param edge 是否为边界线（边界用亮一点，内部用很淡）
     */
    public static void chartGridH(GuiGraphics g, int x0, int x1, int y, boolean edge) {
        g.fill(x0, y, x1, y + 1, edge ? 0x2AFFFFFF : 0x10FFFFFF);
    }

    public static void chartGridV(GuiGraphics g, int x, int y0, int y1, boolean edge) {
        g.fill(x, y0, x + 1, y1, edge ? 0x2AFFFFFF : 0x10FFFFFF);
    }

    /** 轴刻度标签（右对齐到 {@code rightX}，保证多位数字不压到轴线上）。 */
    public static void axisLabelRight(GuiGraphics g, String text, int rightX, int y, boolean edge) {
        Font f = font();
        g.drawString(f, text, rightX - f.width(text), y, edge ? C_TXT_SUB : C_TXT_DIM, false);
    }

    /** 轴刻度标签（居中，用于 X 轴）。 */
    public static void axisLabelCenter(GuiGraphics g, String text, int centerX, int y, boolean edge) {
        Font f = font();
        g.drawString(f, text, centerX - f.width(text) / 2, y,
                edge ? C_TXT_SUB : C_TXT_DIM, false);
    }

    /** 数据曲线的一个点（比 {@code g.fill} 更省事的封装，颜色带 alpha）。 */
    public static void plot(GuiGraphics g, int x, int y, int argb) {
        g.fill(x, y, x + 1, y + 1, argb);
    }

    /**
     * 图线（折线采样）—— 图表里画单调/连续曲线用。
     *
     * @param fn     x → y（数据坐标）
     * @param xToSx  数据 x → 屏幕 x
     * @param yToSy  数据 y → 屏幕 y
     */
    public static void curve(GuiGraphics g, java.util.function.DoubleUnaryOperator fn,
                             double xMin, double xMax,
                             java.util.function.DoubleToIntFunction xToSx,
                             java.util.function.DoubleToIntFunction yToSy,
                             int argb, int samples) {
        int n = Math.max(2, samples);
        for (int i = 0; i < n; i++) {
            double x0 = xMin + (xMax - xMin) * i / n;
            double x1 = xMin + (xMax - xMin) * (i + 1) / n;
            int sx0 = xToSx.applyAsInt(x0), sx1 = xToSx.applyAsInt(x1);
            int sy0 = yToSy.applyAsInt(fn.applyAsDouble(x0));
            int sy1 = yToSy.applyAsInt(fn.applyAsDouble(x1));
            int steps = Math.max(Math.abs(sx1 - sx0), Math.abs(sy1 - sy0));
            for (int k = 0; k <= steps; k++) {
                double t = steps > 0 ? (double) k / steps : 0;
                plot(g, (int) (sx0 + (sx1 - sx0) * t), (int) (sy0 + (sy1 - sy0) * t), argb);
            }
        }
    }

    /** 图表内的小徽标标签（如「干燥」「湿润」），带半透明底以在色带上可读。 */
    public static void chartTag(GuiGraphics g, String text, int x, int y, int color) {
        Font f = font();
        int w = f.width(text) + 8;
        roundRect(g, x, y, x + w, y + f.lineHeight + 2, 2, 0x99000000);
        g.drawString(f, text, x + 4, y + 1, color, false);
    }

    /**
     * <b>可切换按钮/开关胶囊</b>（★ v10）。
     *
     * <h2>为什么必须出一套新的</h2>
     * <p>用户截图指出「切换按键 UI 明显突变」：激活态用的是
     * <b>圆角 4 + 绿色描边</b>，普通态是<b>真胶囊（圆角 = 高/2）× 无描边</b>
     * ⇒ 同一个按钮组里两种形状并排，像两个不同时代的控件。</p>
     *
     * <p>本方法把「开/关」做成<b>同一形状的两种明度</b>：
     * 开 = 主色填充 + 深字；关 = 深灰胶囊 + 亮字。形状（胶囊）、内边距、
     * 字号全部一致，切换时只有颜色变。</p>
     *
     * @param active 是否处于激活/开启态
     * @param accent 激活色（默认 {@link #C_ACCENT}）
     */
    public static void toggleButton(GuiGraphics g, int x, int y, int w, int h,
                                    String label, boolean active, boolean hover, int accent) {
        Font f = font();
        int r = h / 2;
        if (active) {
            roundRect(g, x, y, x + w, y + h, r, hover ? lighten(accent, 1.12f) : accent);
            g.drawString(f, label, x + (w - f.width(label)) / 2,
                    y + (h - f.lineHeight) / 2, C_ACCENT_TXT, false);
        } else {
            roundRect(g, x, y, x + w, y + h, r, hover ? PILL_HOVER : PILL);
            g.drawString(f, label, x + (w - f.width(label)) / 2,
                    y + (h - f.lineHeight) / 2, C_TXT, false);
        }
    }

    /** 提亮/压暗一个 ARGB 色（用于悬停反馈）。 */
    public static int lighten(int argb, float mul) {
        int a = (argb >>> 24) & 0xFF;
        int r = clampI((int) (((argb >> 16) & 0xFF) * mul), 0, 255);
        int g = clampI((int) (((argb >> 8) & 0xFF) * mul), 0, 255);
        int b = clampI((int) ((argb & 0xFF) * mul), 0, 255);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private static int clampI(int v, int lo, int hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }

    // =================================================================
    // 六、可交互态（★ v9：用户「很多控件没什么框，很难看出能不能点」）
    // =================================================================

    /**
     * <b>可交互区域的底</b> —— 让"这块能点/能拖"一眼可见。
     *
     * <h2>问题</h2>
     * <p>此前滑块轨道只是一根 2px 细线、分段控件是极淡的 {@code 0x0CFFFFFF}，
     * 在深色地形上几乎看不出边界。用户的判断是对的：
     * <b>没有可见轮廓的东西，用户不会去尝试交互</b>。</p>
     *
     * <h2>约定（三档，从弱到强）</h2>
     * <ul>
     *   <li>{@link #FIELD}：输入型控件（滑块轨道槽、下拉框、开关轨）——
     *       有明确凹槽底 {@code 0x1AFFFFFF} + 1px 内边；</li>
     *   <li>{@link #PILL}：按钮/芯片 —— 实心胶囊，亮度足够与背景区分；</li>
     *   <li>{@link #ROW_HOVER}：列表行 —— 默认透明，悬停才亮。</li>
     * </ul>
     */
    public static final int FIELD      = 0x1AFFFFFF;
    public static final int FIELD_HOVER = 0x26FFFFFF;
    public static final int PILL       = 0xFF333333;
    public static final int PILL_HOVER = 0xFF414141;
    public static final int ROW_HOVER  = 0x14FFFFFF;

    /**
     * <b>输入控件凹槽</b>（★ v11：减薄）。
     *
     * <h2>为什么要减薄（用户：「控件边框有点粗了，建议减厚度」）</h2>
     * <p>v10 用了 3 条 1px 线（上暗/下亮/侧收口）+ 8px 槽高，实际渲染出来是
     * <b>一圈 1px 亮边包住 8px 深块</b>，在 16px 行高里占了近一半，
     * 看着像"厚边框"而不是"细槽"。</p>
     * <p>v11 改为：<b>只保留上沿暗线</b>（凹陷感的核心），删掉下沿亮线与左右收口，
     * 槽底透明度降低。视觉上变成"一条嵌进面板的细槽"，而不是一个框。</p>
     */
    public static void field(GuiGraphics g, int x, int y, int w, int h, boolean hover) {
        if (w <= 0 || h <= 0) return;
        // ★ v13：槽一律用小圆角（比容器小一档），形成"嵌进去"的层级
        int r = Math.min(RADIUS_FIELD, h / 2);
        // 槽底：比背景略暗一点点，靠"略暗"表达凹陷，不靠描边
        roundRect(g, x, y, x + w, y + h, r, hover ? 0x2E000000 : 0x26000000);
        // 仅上沿一条暗线（内阴影上沿）
        g.fill(x + r, y, x + w - r, y + 1, hover ? 0x44000000 : 0x3A000000);
    }

    /**
     * <b>数值胶囊</b>（★ v11：细槽化）。
     *
     * <p>之前是「凹槽 + 居中文字」，描边那圈让它看着比滑块还重。
     * 现在只给很淡的底、不加描边 —— 视觉上是滑块右侧的"读数"，
     * 而不是另一个控件。</p>
     */
    public static void valueChip(GuiGraphics g, int x, int y, int w, int h,
                                 String text, boolean hover) {
        // ★ v14：与开关/按钮同一档圆角（完全胶囊），不再单独用 h/2 的近似值
        int r = pillRadius(h);
        roundRect(g, x, y, x + w, y + h, r, hover ? 0x1EFFFFFF : 0x14FFFFFF);
        Font f = font();
        String shown = clip(text, w - 8);
        g.drawString(f, shown, x + (w - f.width(shown)) / 2,
                y + (h - f.lineHeight) / 2, hover ? C_TXT : 0xFFCCCCCC, false);
    }

    /**
     * <b>可拖拽的手柄提示</b>：在控件两端画短竖线（像"可拉伸"的把手）。
     *
     * <p>用于色条阈值点、标尺标记 —— 让用户看得出"这个点能拖"。</p>
     */
    public static void dragGrip(GuiGraphics g, int cx, int y, int h, boolean active) {
        int col = active ? 0xFFFFFFFF : 0xCCFFFFFF;
        g.fill(cx - 3, y, cx - 2, y + h, col);
        g.fill(cx + 2, y, cx + 3, y + h, col);
    }

    /**
     * 行悬停反馈（★ v13：<b>连悬停底色也去掉</b>）。
     *
     * <h2>为什么要走到这一步</h2>
     * <p>用户第三次反馈「还是能看出那圈底色块的轮廓感，建议彻底去掉试试」。
     * 前两轮把 alpha 从 {@code 0x12FFFFFF} 降到 {@code 0x0EFFFFFF} 都没解决 ——
     * 因为 {@code roundRect} 画的是<b>实心填充</b>，任何非零 alpha 在深色面板上
     * 都会形成一圈可辨的矩形边界（圆角处尤其明显）。</p>
     *
     * <p>⇒ 本方法<b>不再绘制任何东西</b>。行的悬停反馈改由
     * <b>文字提亮</b>承担（调用方把 label 颜色从 {@code C_TXT_SUB} 换成 {@code C_TXT}），
     * 这也是无框设计里最干净的表达方式。</p>
     *
     * @deprecated 不再绘制底色；请改用文字颜色变化表达悬停
     */
    @Deprecated
    public static void rowSurface(GuiGraphics g, int x, int y, int w, int h, boolean hover) {
        // 有意留空 —— 见上方说明。
    }

    // =================================================================
    // 七、悬停提示上报（★ v15：消灭"tooltip 各画各的"）
    // =================================================================

    /**
     * <b>悬停提示的全局收集器</b>。
     *
     * <h2>★ 为什么必需（用户：「鼠标悬停提示在不同标签页有新旧不一致」）</h2>
     * <p>此前 {@code ParamSlider}、{@code CategoryBar}、{@code ControlPoint}、
     * {@code MixerPanel}、{@code WorldHeightBar} <b>各自画自己的 tooltip</b>：</p>
     * <pre>
     *   ParamSlider     : 直角纯黑方块 + 单行不折行（0xFF000000）
     *   CategoryBar     : 直角半透明块 + 另一套配色
     *   ControlPoint    : 圆角（v8 改过）
     *   MixerPanel      : 又一套
     *   屏幕级 drawHoverTooltip : 圆角 + 折行 + 越界翻边（最新）
     * </pre>
     * <p>⇒ 同一个"悬停提示"在 5 个地方是 5 套外观，换个页签就换一张脸。</p>
     *
     * <p>现在统一为：控件只<b>登记文本</b>（本方法），光标位置存进线程本地，
     * 屏幕在<b>最后一层</b>统一用 {@code GeoGenesisConfigScreen.drawHoverTooltip} 绘制。
     * 控件不再持有任何 tooltip 绘制代码。</p>
     */
    public static final class Hover {
        private static String text;
        private static int x, y;

        private Hover() { }

        /** 每帧开始清空（由屏幕在绘制完面板后调用）。 */
        public static void clear() {
            text = null;
        }

        /**
         * 登记一条悬停提示。同帧多次调用时<b>后写覆盖</b>
         * （调用顺序是从底层控件到上层控件，因此上层自然胜出）。
         */
        public static void report(String t, int mx, int my) {
            if (t == null || t.isEmpty()) return;
            text = t;
            x = mx;
            y = my;
        }

        public static boolean has() { return text != null; }
        public static String text() { return text; }
        public static int x() { return x; }
        public static int y() { return y; }
    }

    // =================================================================
    // 八、渐变（★ v16：示意图改「平滑色带」，参考用户的图 3）
    // =================================================================

    /**
     * <b>纵向渐变</b>：在 [y0,y1] 之间按给定色标平滑过渡。
     *
     * <h2>为什么需要</h2>
     * <p>用户指出示意图"旧版"、要求参考图 3 的效果 —— 那张图的关键是
     * <b>颜色连续过渡</b>（橙→红→深蓝没有硬分界），而我们现在是
     * {@code g.fill} 画出几块<b>硬切</b>的色块（陆地/海洋之间一条直线），
     * 看起来像色卡而不是地形剖面。</p>
     *
     * @param pos 色标位置数组（0..1，单调递增）
     * @param argb 对应颜色数组（长度须与 {@code pos} 相同）
     */
    public static void gradientV(GuiGraphics g, int x0, int y0, int x1, int y1,
                                 float[] pos, int[] argb) {
        if (y1 <= y0 || x1 <= x0 || pos.length < 2 || pos.length != argb.length) return;
        for (int y = y0; y < y1; y++) {
            double t = (double) (y - y0) / Math.max(1, y1 - y0 - 1);
            g.fill(x0, y, x1, y + 1, sampleStops(pos, argb, t));
        }
    }

    /** 在色标数组中按 t∈[0,1] 线性插值取色。 */
    private static int sampleStops(float[] pos, int[] argb, double t) {
        if (t <= pos[0]) return argb[0];
        for (int i = 0; i < pos.length - 1; i++) {
            if (t <= pos[i + 1]) {
                double span = pos[i + 1] - pos[i];
                double k = span < 1e-9 ? 0 : (t - pos[i]) / span;
                return lerpArgb(argb[i], argb[i + 1], k);
            }
        }
        return argb[argb.length - 1];
    }

    /** ARGB 线性插值。 */
    public static int lerpArgb(int a, int b, double k) {
        int aa = (int) (((a >>> 24) & 0xFF) + (((b >>> 24) & 0xFF) - ((a >>> 24) & 0xFF)) * k);
        int rr = (int) (((a >> 16) & 0xFF) + (((b >> 16) & 0xFF) - ((a >> 16) & 0xFF)) * k);
        int gg = (int) (((a >> 8) & 0xFF) + (((b >> 8) & 0xFF) - ((a >> 8) & 0xFF)) * k);
        int bb = (int) ((a & 0xFF) + ((b & 0xFF) - (a & 0xFF)) * k);
        return (clampI(aa, 0, 255) << 24) | (clampI(rr, 0, 255) << 16)
             | (clampI(gg, 0, 255) << 8) | clampI(bb, 0, 255);
    }

    // =================================================================
    // 五、布局 —— 控件不互压的唯一保证
    // =================================================================

    /**
     * <b>行内控件布局</b>：把「标签 .. 若干右侧控件」排进一行，保证不重叠。
     *
     * <h2>为什么必须集中算（用户 5 张截图全指向这里）</h2>
     * <p>此前每个面板自己写 {@code int sliderX = contentLeft + barW + 6 + resetArea}
     * 这类算式，于是：</p>
     * <ul>
     *   <li>色条刻度标签（{@code -0.60}）压在参数名（{@code 温度}）上；</li>
     *   <li>参数值胶囊（{@code 1.00}）压在同组下一个标题上；</li>
     *   <li>断言「滑块 64 宽」而实际文字宽度 40+ ⇒ 超出的部分叠到邻居。</li>
     * </ul>
     * <p>根因不是"差几个像素"，而是<b>每个面板各自认为自己是唯一的</b>。
     * 本类改为：调用方声明「我要哪些控件、多宽」，由这里从右往左扣减。</p>
     *
     * <pre>
     *   [标签(弹性)]      [gadget N] [gadget N-1] ... [gadget 1]
     *   ←———— 剩余 ————→ ←———— 参与者按声明顺序从右左排 ————→
     * </pre>
     *
     * @param x        行左边界
     * @param w        行总宽
     * @param widths   从右往左的控件宽度（<b>第一个元素是最右边的</b>）
     * @param gap      控件间距
     * @return 每个控件的左边界；索引与 {@code widths} 一致（0 = 最右）
     */
    public static int[] rowGadgets(int x, int w, int gap, int... widths) {
        int[] out = new int[widths.length];
        int cursor = x + w;
        for (int i = 0; i < widths.length; i++) {
            cursor -= widths[i];
            out[i] = cursor;
            cursor -= gap;
        }
        return out;
    }

    /** 上面布局之后，左侧标签可用的宽度（可能很小甚至为负，调用方须 {@link #clip}）。 */
    public static int labelWidth(int x, int[] gadgets, int w, int gap) {
        int rightMost = gadgets.length == 0 ? x + w : gadgets[gadgets.length - 1];
        return Math.max(0, rightMost - gap - x);
    }

    /**
     * <b>刻度标签防重叠</b>：决定哪些刻度能画文字。
     *
     * <p>色条上常有一串控制点（{@code -0.60 / 0.38 / 1.0}），各自在自己位置画数值。
     * 离得近时必然叠成一团 —— 用户截图里的 {@code -0.60.30} 就是这么来的。
     * 本方法按 x 排序后<b>贪心丢弃</b>：后一个与前一个已保留标签的右缘重叠就不画。</p>
     *
     * @param centers 每个标签中心 x
     * @param textWs  每个标签宽度（调用方用 {@code font.width} 得到）
     * @return 与输入等长的布尔数组，true = 可以画
     */
    public static boolean[] fitLabels(int[] centers, int[] textWs, int minGap) {
        int n = centers.length;
        boolean[] keep = new boolean[n];
        if (n == 0) return keep;
        Integer[] order = new Integer[n];
        for (int i = 0; i < n; i++) order[i] = i;
        java.util.Arrays.sort(order, (a, b) -> Integer.compare(centers[a], centers[b]));

        int lastRight = Integer.MIN_VALUE / 2;
        for (int k = 0; k < n; k++) {
            int i = order[k];
            int left = centers[i] - textWs[i] / 2;
            if (left >= lastRight + minGap) {
                keep[i] = true;
                lastRight = left + textWs[i];
            }
        }
        return keep;
    }

    // =================================================================
    // 五、文本工具
    // =================================================================

    /**
     * <b>统一滚动条</b>（★ v7：替代各面板各写一份的 3px 绿色竖条）。
     *
     * <p>新版控件语言：轨道不画（靠卡片底），滑块是<b>圆角细条 + 中性灰</b>，
     * 只在使用中/悬停时才用主色。旧版到处是 {@code 0xFF00c896} 亮绿 2px 条，
     * 与新配色打架。</p>
     *
     * @param trackTop 轨道顶 y
     * @param trackH   轨道高（= 可视区高度）
     * @param contentH 内容总高
     * @param scroll    当前滚动偏移
     */
    public static void scrollbar(GuiGraphics g, int x, int trackTop, int trackH,
                                 int contentH, int scroll, boolean hover) {
        if (contentH <= trackH) return;
        int barH = Math.max(16, trackH * trackH / Math.max(1, contentH));
        int maxScroll = contentH - trackH;
        int barY = trackTop + (int) ((long) scroll * (trackH - barH) / Math.max(1, maxScroll));
        int color = hover ? C_ACCENT : 0x66FFFFFF;
        // 2px 圆角条：用 roundRect 得到柔和两端
        roundRect(g, x, barY, x + 3, barY + barH, 2, color);
    }

    /**
     * <b>按像素宽折行</b>并绘制（★ v7）。
     *
     * <p>中文不能按字符数折 —— 一个汉字约 9px、一个数字约 6px，
     * 混排时按"每行 N 字"必然溢出（用户截图里诊断页那两行就是被面板右边裁掉的）。</p>
     *
     * @return 折行后的总高度（调用方据此推进 y）
     */
    public static int drawWrapped(GuiGraphics g, String text, int x, int y, int maxW, int color) {
        Font f = font();
        int cy = y;
        for (String para : text.split("\n", -1)) {
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < para.length(); i++) {
                char ch = para.charAt(i);
                if (line.length() > 0 && f.width(line.toString() + ch) > maxW) {
                    g.drawString(f, line.toString(), x, cy, color, false);
                    cy += f.lineHeight + 2;
                    line.setLength(0);
                }
                line.append(ch);
            }
            if (line.length() > 0) {
                g.drawString(f, line.toString(), x, cy, color, false);
                cy += f.lineHeight + 2;
            }
        }
        return cy - y;
    }

    /** {@link #drawWrapped} 的高度预计算（不绘制），用于布局。 */
    public static int wrappedHeight(String text, int maxW) {
        Font f = font();
        int lines = 0;
        for (String para : text.split("\n", -1)) {
            StringBuilder line = new StringBuilder();
            for (int i = 0; i < para.length(); i++) {
                char ch = para.charAt(i);
                if (line.length() > 0 && f.width(line.toString() + ch) > maxW) {
                    lines++;
                    line.setLength(0);
                }
                line.append(ch);
            }
            if (line.length() > 0) lines++;
        }
        return Math.max(1, lines) * (f.lineHeight + 2);
    }

    /** 按像素宽裁剪并加省略号。 */
    public static String clip(String s, int maxW) {
        Font f = font();
        if (f.width(s) <= maxW) return s;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            if (f.width(sb.toString() + s.charAt(i) + "…") > maxW) break;
            sb.append(s.charAt(i));
        }
        return sb + "…";
    }
}
