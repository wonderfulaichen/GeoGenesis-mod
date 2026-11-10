package com.geogenesis.client.preview;

import com.geogenesis.client.ParamSlider;
import com.geogenesis.config.ConfigSafe;
import com.geogenesis.config.GeoGenesisConfig;
import com.geogenesis.worldgen.cave.CaveConfig;
import com.geogenesis.worldgen.cave.CaveShape;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.DoubleSupplier;
import java.util.function.Function;

/**
 * 洞穴设置面板（「洞穴」页签，★ 2026-09-15）。
 *
 * <h3>为何需要</h3>
 * <p>洞穴在 2026-09-15 已做成<b>可开关配置</b>（档位 + 旋钮，见 {@link CaveConfig}），
 * 但当时只能改 TOML —— 用户的诉求是「<i>洞穴相关的可以开关配置</i>」，
 * <b>有界面才算真正"一键"</b>。</p>
 *
 * <h3>布局（自上而下）</h3>
 * <ol>
 *   <li><b>档位</b>：5 个按钮（关闭 / 精简 / 拟真 / 接近原版 / 自定义）——
 *       <b>一键切换</b>，并同步刷新下方所有开关与旋钮；</li>
 *   <li><b>开关</b>：总开关 · 隧道 / 空腔 / 层调制（分量）· 岩性门控 / 洞穴群系（拟真耦合）；</li>
 *   <li><b>旋钮</b>：密度倍率 · 洞顶保护（0 = 允许破地表成入口）· 最浅 / 最深深度；</li>
 *   <li><b>提示</b>：改动只影响<b>新生成</b>的区块。</li>
 * </ol>
 *
 * <h3>★ 改动如何生效（本面板的关键）</h3>
 * <p>写配置后调 {@link CaveShape#markConfigDirty()} ⇒ 下一次挖洞前
 * （{@code CaveCarver} 的 chunk 级入口）自动重新解析并注入新配置
 * ⇒ <b>新生成的区块立刻用新设置</b>。已生成的区块不会改变（世界生成的固有性质）。</p>
 *
 * <p>手动改任一旋钮/开关 ⇒ 档位自动切到 {@link CaveConfig.Preset#CUSTOM}
 * （否则界面会显示"拟真"却用着自定义值，属误导）。</p>
 */
public class CavePanel extends ConfigPanel {

    // ---- 布局常量（★ v14：行高统一引用 UiKit，不再本地写死）----
    private static final int ROW_H = UiKit.ROW_H;
    private static final int ROW_GAP = 3;
    private static final int SECTION_GAP = 12;
    private static final int LABEL_GAP = 13;      // 小节标题到底部控件
    private static final int PRESET_BTN_H = 22;
    private static final int PRESET_BTN_GAP = 4;
    private static final int SLIDER_ROW_H = UiKit.SLIDER_H;
    /** 滑块标签列的目标宽度（★ v7：不再当写死像素用，见 {@link #sliderLabelW()}）。 */
    private static final int SLIDER_LABEL_W = 150;
    /** 滑块宽度上限（实际按可用宽收缩，见 {@link #sliderW()}）。 */
    private static final int SLIDER_W = 130;

    /**
     * 滑块标签列宽（★ v7：按面板实际宽度收缩）。
     *
     * <p>原来写死 150，而面板可能只有 280~300 宽 ⇒ 标签+滑块 280 几乎贴边，
     * 实机上「洞穴密度倍率」这类长标签会顶到滑块起点。</p>
     */
    private int sliderLabelW() {
        return Math.max(70, Math.min(SLIDER_LABEL_W, (int) (w * 0.46)));
    }

    /** 滑块宽度：占满标签列右侧的剩余空间（不再写死 130）。 */
    private int sliderW() {
        int avail = w - sliderLabelW() - 8;
        return Math.max(40, Math.min(SLIDER_W, avail));
    }

    /**
     * 档位按钮（顺序即显示顺序）。
     *
     * <p>★ 刻意<b>不含 {@link CaveConfig.Preset#CUSTOM}</b>：它是"状态"而非"动作"。
     * 若做成按钮，点击会按 {@code fromPreset(CUSTOM)} 把旋钮<b>重置回基准值</b>
     * ⇒ 用户会意外丢失刚调好的参数（实测推演出的 UX 陷阱）。
     * 现改为：手动调过旋钮后，档位自动变 CUSTOM 并<b>以文字提示</b>显示。</p>
     */
    private static final CaveConfig.Preset[] PRESETS = {
            CaveConfig.Preset.OFF,
            CaveConfig.Preset.MINIMAL,
            CaveConfig.Preset.REALISTIC,
            CaveConfig.Preset.VANILLA_LIKE
    };

    private static String presetLabel(CaveConfig.Preset p) {
        return switch (p) {
            case OFF -> "关闭";
            case MINIMAL -> "精简";
            case REALISTIC -> "拟真";
            case VANILLA_LIKE -> "接近原版";
            case CUSTOM -> "自定义";
        };
    }

    /**
     * 档位<b>按钮下方的一行摘要</b>（简短，挂在界面上常驻显示）。
     *
     * <p>★ 必须与 {@link #presetTip}（悬停说明）<b>内容不同</b>：初版摘要直接取
     * {@code firstLine(presetTip(...))}，导致悬停时"摘要 + 同文 tooltip"上下叠在一起，
     * 看起来像同一句话被写了两遍（用户实测反馈）。现摘要只给<b>一句短语</b>，
     * 详细说明放在 tooltip。</p>
     */
    private static String presetSummary(CaveConfig.Preset p) {
        return switch (p) {
            case OFF -> "地下不再有任何洞穴";
            case MINIMAL -> "只有细隧道，洞少而小";
            case REALISTIC -> "岩石类型 + 岩层控制（地质拟真）";
            case VANILLA_LIKE -> "洞厅更大更圆，不分层";
            case CUSTOM -> "按下方开关与参数生效";
        };
    }

    /**
     * 档位<b>悬停说明</b>（面向玩家的功能描述）。
     *
     * <p>★ 撰写准则（初版违反过）：只写<b>玩家能感知到的效果</b>。
     * 不写实现机制、不写"参考项目如何"、不写"实测数据" —— 那些属于代码注释，
     * 出现在游戏界面上是噪音（用户实测反馈"怎么把这个内容也写进描述里了"）。</p>
     */
    private static String presetTip(CaveConfig.Preset p) {
        return switch (p) {
            case OFF -> "关闭洞穴生成。\n地下是实心岩层，不会出现洞穴。";
            case MINIMAL -> "只生成细小的隧道。\n洞穴数量少、规模小，适合低配设备或喜欢地下紧凑的玩法。";
            case REALISTIC -> "地质拟真（推荐）：\n"
                    + "· 在石灰岩等易溶蚀的岩石中洞穴更发育，花岗岩中很少\n"
                    + "· 洞穴沿岩层水平发育，不易出现贯通上下的竖井";
            case VANILLA_LIKE -> "更接近原版观感：\n"
                    + "· 洞厅更大、更圆\n"
                    + "· 不沿岩层分层\n"
                    + "· 各类岩石一视同仁";
            case CUSTOM -> "使用下方「开关」与「参数微调」的设置。";
        };
    }

    /** 当前档位的效果说明（显示在按钮下方）。 */
    private CaveConfig.Preset shownPreset() {
        CaveConfig.Preset p = ConfigSafe.enumOf(GeoGenesisConfig.INSTANCE.cavePreset,
                CaveConfig.Preset.REALISTIC);
        return p != null ? p : CaveConfig.Preset.REALISTIC;
    }

    // ---- 开关行 ----
    private static final class Toggle {
        final String label;
        final String tip;
        final Runnable onChanged;
        Toggle(String label, String tip, Runnable onChanged) {
            this.label = label; this.tip = tip; this.onChanged = onChanged;
        }
    }

    private final List<Toggle> toggles = new ArrayList<>();

    // ---- 旋钮 ----
    private static final class Knob {
        final String label;
        final String tip;
        final double min, max, def;
        final DoubleSupplier read;
        final Consumer<Double> write;
        final Function<Double, String> fmt;
        ParamSlider slider;
        Knob(String label, String tip, double min, double max, double def,
             DoubleSupplier read, Consumer<Double> write, Function<Double, String> fmt) {
            this.label = label; this.tip = tip; this.min = min; this.max = max;
            this.def = def; this.read = read; this.write = write; this.fmt = fmt;
        }
    }

    private final List<Knob> knobs = new ArrayList<>();

    public CavePanel() {
        buildToggles();
        buildKnobs();
    }

    private static GeoGenesisConfig cfg() {
        return GeoGenesisConfig.INSTANCE;
    }

    // ===================== 构建 =====================

    private void buildToggles() {
        toggles.add(new Toggle("洞穴总开关", "关闭后地下不再生成任何洞穴。",
                this::onManualChange));
        toggles.add(new Toggle("隧道分量（蜿蜒管道）",
                "细长的曲折通道，把分散的洞穴连接起来。\n关闭后洞穴之间不再连通。",
                this::onManualChange));
        toggles.add(new Toggle("空腔分量（可站立的洞厅）",
                "能走进去、站得起来的较大空间。\n关闭后只剩细隧道，可能难以通行。",
                this::onManualChange));
        toggles.add(new Toggle("层调制（防竖直贯穿）",
                "把高大的空腔压成一层层水平洞穴，避免挖出贯通上下的竖井。\n"
                + "关闭后更接近原版洞厅，但可能出现竖直贯穿。", this::onManualChange));
        toggles.add(new Toggle("岩性门控（拟真耦合）",
                "洞穴只在容易溶蚀的岩石（如石灰岩）中发育，花岗岩等坚硬岩石中很少。\n"
                + "关闭后各类岩石一视同仁。", this::onManualChange));
        toggles.add(new Toggle("地下洞穴群系",
                "洞穴内出现钟乳石与石笋（滴水石洞）；湿润气候下还会出现苔藓与藤蔓（繁茂洞穴）。\n"
                + "关闭后洞穴内沿用该处地表的群系。", this::onManualChange));
    }

    private void buildKnobs() {
        GeoGenesisConfig c = cfg();
        knobs.add(new Knob("洞穴密度倍率", "数值越大，洞穴越多、越粗。默认 1.00。",
                0.2, 3.0, 1.0,
                () -> ConfigSafe.dbl(c.caveDensityMul, 1.0),
                v -> c.caveDensityMul.set(v),
                v -> String.format("%.2f", v)));
        knobs.add(new Knob("洞顶保护厚度", "洞顶与地表之间至少保留的厚度（格）。\n"
                + "设为 0 时洞穴可以破开地表形成【洞口】，便于直接走进地下。默认 6。",
                0.0, 40.0, 6.0,
                () -> (double) ConfigSafe.i32(c.caveSurfaceLid, 6),
                v -> c.caveSurfaceLid.set((int) Math.round(v)),
                v -> String.format("%.0f", v)));
        knobs.add(new Knob("最浅深度", "洞穴带起点（距地表格数）。默认 8。",
                0.0, 80.0, 8.0,
                () -> (double) ConfigSafe.i32(c.caveDepthMin, 8),
                v -> c.caveDepthMin.set((int) Math.round(v)),
                v -> String.format("%.0f", v)));
        knobs.add(new Knob("最深深度", "洞穴带终点（距地表格数）。默认 120。",
                40.0, 320.0, 120.0,
                () -> (double) ConfigSafe.i32(c.caveDepthMax, 120),
                v -> c.caveDepthMax.set((int) Math.round(v)),
                v -> String.format("%.0f", v)));

        for (Knob k : knobs) {
            ParamSlider ps = new ParamSlider(0, 0, SLIDER_W, k.min, k.max, k.read.getAsDouble(),
                    v -> { k.write.accept(v); onManualChange(); }, k.fmt);
            ps.setDefaultValue(k.def);
            ps.setTooltipText(k.tip);
            k.slider = ps;
        }
    }

    /**
     * 手动改动（旋钮/开关）⇒ 档位自动切到 CUSTOM 并刷新配置。
     *
     * <p>为何切 CUSTOM：否则界面顶部仍显示"拟真"，实际却用着自定义值 ——
     * 那是<b>误导</b>。切到自定义后显示与实际一致。</p>
     */
    private void onManualChange() {
        cfg().cavePreset.set(CaveConfig.Preset.CUSTOM);
        applyLive();
    }

    /** 写档位 + 把所有旋钮同步成该档的推荐值（一键切换的核心）。 */
    private void applyPreset(CaveConfig.Preset p) {
        CaveConfig cc = CaveConfig.fromPreset(p);
        cfg().cavePreset.set(p);
        cfg().caveEnabled.set(cc.enabled);
        cfg().caveTunnelEnabled.set(cc.tunnelEnabled);
        cfg().caveChamberEnabled.set(cc.chamberEnabled);
        cfg().caveLayerEnabled.set(cc.layerEnabled);
        cfg().caveDensityMul.set(cc.densityMul);
        cfg().caveSurfaceLid.set(cc.surfaceLid);
        cfg().caveDepthMin.set(cc.depthMin);
        cfg().caveDepthMax.set(cc.depthMax);
        cfg().caveLithoGating.set(cc.lithoGating);
        cfg().caveBiomesEnabled.set(cc.caveBiomes);
        // 同步滑块显示（不触发 onChange，避免被当成"手动改"而切成 CUSTOM）
        for (Knob k : knobs) k.slider.setCurrentValue(k.read.getAsDouble());
        applyLive();
    }

    /** 让改动生效：标记热刷新 + 触发预览重建。 */
    private void applyLive() {
        CaveShape.markConfigDirty();
        onMarkDirty.run();
        playClick();
    }

    // ===================== 布局（render 与 mouseClicked 共用，确保一致）=====================

    private int presetLabelY() {
        return top() + LAST_DESC_Y;
    }

    private int presetBtnY() {
        return presetLabelY() + LABEL_GAP;
    }

    /**
     * 当前档位摘要的 Y（紧贴档位按钮下方）。
     *
     * <p>★ v7【修"摘要与开关标题重叠"】：摘要原来画在 {@code presetBtnY+BTN_H+4}，
     * 而 {@link #togglesLabelY()} 只给了 {@code SECTION_GAP=12} 的间距、
     * 且没算摘要本身的行高 ⇒ 实机「岩石类型 + 岩层控制（地质拟真）」直接压在「开关」上。
     * 现在把摘要行高显式计入，并由 {@link #togglesLabelY()} 引用同一个常量。</p>
     */
    private int summaryY() {
        return presetBtnY() + PRESET_BTN_H + 4;
    }

    /** 摘要行高（一行文字 + 下方留白）。 */
    private static final int SUMMARY_H = 16;

    private int togglesLabelY() {
        return presetBtnY() + PRESET_BTN_H + SUMMARY_H + SECTION_GAP;
    }

    private int toggleY(int i) {
        return togglesLabelY() + LABEL_GAP + i * (ROW_H + ROW_GAP);
    }

    private int knobsLabelY() {
        return toggleY(toggles.size() - 1) + ROW_H + SECTION_GAP;
    }

    private int knobY(int i) {
        return knobsLabelY() + LABEL_GAP + i * (SLIDER_ROW_H + ROW_GAP);
    }

    /**
     * 说明文字起始 Y（相对面板顶）。
     *
     * <p>★ v7：标题已移到 {@code ConfigPanelHost}，这里从 14 改回 2
     * （旧值 14 是给"本面板自绘标题"留的位置，不移会白空一行）。</p>
     */
    private static final int DESC_Y = 2;
    /**
     * 说明文字块高度（<b>单行</b>）。
     *
     * <p>★ 初版这里是两行（24），其中第二行"改动只影响新生成的区块"与<b>底部提示
     * 完全重复</b>。现说明区只留一句功能概述，生效范围说明统一放底部。</p>
     */
    private static final int DESC_H = 12;
    /** 档位标题 Y = 顶部 + 说明块。 */
    private static final int LAST_DESC_Y = DESC_Y + DESC_H + 8;

    /** 底部提示文案（★ v22：render 与 getHeight 共用同一份）。 */
    private static final String FOOT_NOTE = "提示：更改设置后，只对之后新生成的区块生效。";

    @Override
    public int getHeight() {
        // ★ v22：底部提示已改成<b>折行</b>，高度按实际行数算
        //   （原写死 26 —— 窄面板折两行时最后一行被裁，滚到底也看不全）
        int footH = UiKit.wrappedHeight(FOOT_NOTE, Math.max(60, w));
        return knobY(knobs.size() - 1) - top() + SLIDER_ROW_H + 8 + footH + 12;
    }

    // ===================== 渲染 =====================

    @Override
    public void render(GuiGraphics g, int mx, int my) {
        Font f = font();
        int y = top();
        // ★ v15：标题由 ConfigPanelHost 统一画

        // 说明（★ v15：折行；单行时会超出面板右缘被裁）
        UiKit.drawWrapped(g, "选择档位即可一键切换，也可逐项微调下方的开关与参数。",
                x, y + DESC_Y, Math.max(60, w), C_TEXT_DIM);

        // ---- 档位 ----
        g.drawString(f, "档位（一键切换）", x, presetLabelY(), C_TEXT_DIM);
        int n = PRESETS.length;
        int gap = PRESET_BTN_GAP;
        int bw = Math.max(40, (w - (n - 1) * gap) / n);
        CaveConfig.Preset cur = shownPreset();
        for (int i = 0; i < n; i++) {
            int bx = x + i * (bw + gap);
            boolean active = PRESETS[i] == cur;
            boolean hover = drawButton(g, bx, presetBtnY(), bw, PRESET_BTN_H,
                    presetLabel(PRESETS[i]), active, mx, my);
            if (hover) hoverTooltip = Component.literal(presetTip(PRESETS[i]));
        }
        // 当前档位摘要（一句短语；详细说明走 tooltip，避免同一段文字重复出现）
        String summary = presetSummary(cur);
        g.drawString(f, clip(summary, w), x, summaryY(),
                cur == CaveConfig.Preset.CUSTOM ? C_ACCENT : C_TEXT_DIM);

        // ---- 开关 ----
        g.drawString(f, "开关", x, togglesLabelY(), C_TEXT_DIM);
        for (int i = 0; i < toggles.size(); i++) {
            Toggle t = toggles.get(i);
            int ty = toggleY(i);
            boolean on = readToggle(i);
            boolean hover = drawToggleRow(g, x, ty, w, ROW_H, t.label, on, mx, my);
            if (hover) hoverTooltip = Component.literal(t.tip);
        }

        // ---- 旋钮 ----
        g.drawString(f, "参数微调", x, knobsLabelY(), C_TEXT_DIM);
        for (int i = 0; i < knobs.size(); i++) {
            Knob k = knobs.get(i);
            int ky = knobY(i);
            // ★ v13：悬停底色彻底去掉（见 UiKit.rowSurface javadoc），改用文字提亮
            boolean hoverRow = mx >= x && mx < x + w && my >= ky && my < ky + SLIDER_ROW_H;
            g.drawString(f, trimLabel(k.label, sliderLabelW() - 6), x + 2,
                    ky + (SLIDER_ROW_H - f.lineHeight) / 2,
                    hoverRow ? UiKit.C_TXT : UiKit.C_TXT_SUB, false);
            k.slider.setX(x + sliderLabelW());
            k.slider.setY(ky + 2);
            k.slider.setWidth(sliderW());
            k.slider.render(g, mx, my, 0);
        }
        for (Knob k : knobs) k.slider.renderTooltip(g, mx, my);

        // ---- 底部提示（★ v7：折行，避免超出面板右边被裁）----
        //   ★ v22：文案改用常量，与 getHeight 同源
        int footY = knobY(knobs.size() - 1) + SLIDER_ROW_H + 8;
        UiKit.drawWrapped(g, FOOT_NOTE, x, footY, Math.max(60, w), 0xFF808080);
    }

    /**
     * 按像素宽度截断文本并加省略号。
     *
     * <p>为何需要：档位摘要（如"拟真（默认）：岩性门控（石灰岩溶洞大、花岗岩几乎无洞）+ …"）
     * 有 40+ 个汉字 ≈ 360px，超过面板宽度（约 300px）。主屏虽有 scissor 裁剪不会崩，
     * 但会<b>硬切在半个字上</b>，很难看 ⇒ 主动截断加"…"。</p>
     */
    /** 标签按可用宽度裁剪（★ v7：滑块标签列会随面板收缩，长标签须截断）。 */
    private static String trimLabel(String s, int maxW) {
        return clip(s, Math.max(20, maxW));
    }

    private static String clip(String s, int maxW) {
        Font f = font();
        if (f.width(s) <= maxW) return s;
        String ell = "…";
        int ellW = f.width(ell);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            String next = sb.toString() + s.charAt(i);
            if (f.width(next) + ellW > maxW) break;
            sb.append(s.charAt(i));
        }
        return sb + ell;
    }

    /** 读开关的当前值（顺序必须与 {@link #buildToggles} 一致）。 */
    private boolean readToggle(int i) {
        GeoGenesisConfig c = cfg();
        return switch (i) {
            case 0 -> ConfigSafe.bool(c.caveEnabled, true);
            case 1 -> ConfigSafe.bool(c.caveTunnelEnabled, true);
            case 2 -> ConfigSafe.bool(c.caveChamberEnabled, true);
            case 3 -> ConfigSafe.bool(c.caveLayerEnabled, true);
            case 4 -> ConfigSafe.bool(c.caveLithoGating, true);
            case 5 -> ConfigSafe.bool(c.caveBiomesEnabled, true);
            default -> false;
        };
    }

    /** 写开关（顺序同 {@link #buildToggles}）。 */
    private void writeToggle(int i) {
        GeoGenesisConfig c = cfg();
        boolean v = !readToggle(i);
        switch (i) {
            case 0 -> c.caveEnabled.set(v);
            case 1 -> c.caveTunnelEnabled.set(v);
            case 2 -> c.caveChamberEnabled.set(v);
            case 3 -> c.caveLayerEnabled.set(v);
            case 4 -> c.caveLithoGating.set(v);
            case 5 -> c.caveBiomesEnabled.set(v);
            default -> { }
        }
    }

    // ===================== 鼠标 =====================

    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        if (btn != 0) return false;

        // 档位按钮
        int n = PRESETS.length;
        int gap = PRESET_BTN_GAP;
        int bw = Math.max(40, (w - (n - 1) * gap) / n);
        for (int i = 0; i < n; i++) {
            int bx = x + i * (bw + gap);
            if (hit(bx, presetBtnY(), bw, PRESET_BTN_H, mx, my)) {
                applyPreset(PRESETS[i]);
                return true;
            }
        }

        // 开关
        for (int i = 0; i < toggles.size(); i++) {
            if (hit(x, toggleY(i), w, ROW_H, mx, my)) {
                writeToggle(i);
                toggles.get(i).onChanged.run();
                return true;
            }
        }

        // 旋钮（先定位再命中判定，与 render 布局一致 —— 两处都调 sliderLabelW/sliderW）
        for (int i = 0; i < knobs.size(); i++) {
            Knob k = knobs.get(i);
            int ky = knobY(i);
            k.slider.setX(x + sliderLabelW());
            k.slider.setY(ky + 2);
            k.slider.setWidth(sliderW());
        }
        for (Knob k : knobs) {
            if (k.slider.isHoveringReset((int) mx, (int) my)) {
                k.slider.resetToDefault();
                onManualChange();
                return true;
            }
            if (k.slider.isMouseOver(mx, my)) {
                boolean h = k.slider.mouseClicked(mx, my, btn);
                if (h) requestValueEdit(k.slider, k.label, this::onManualChange);
                return h;
            }
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int btn) {
        for (Knob k : knobs) if (k.slider.isFocused()) k.slider.mouseReleased(mx, my, btn);
        return false;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int btn, double dx, double dy) {
        for (Knob k : knobs) {
            if (k.slider.isFocused()) {
                k.slider.mouseDragged(mx, my, btn, dx, dy);
                return true;
            }
        }
        return false;
    }

    /** 重建滑块显示（供外部在配置被重置后调用）。 */
    public void buildFromConfig() {
        for (Knob k : knobs) k.slider.setCurrentValue(k.read.getAsDouble());
    }
}
