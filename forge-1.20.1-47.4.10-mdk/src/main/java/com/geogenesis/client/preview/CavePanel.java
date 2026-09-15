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

    // ---- 布局常量 ----
    private static final int ROW_H = 20;
    private static final int ROW_GAP = 3;
    private static final int SECTION_GAP = 12;
    private static final int LABEL_GAP = 13;      // 小节标题到底部控件
    private static final int PRESET_BTN_H = 22;
    private static final int PRESET_BTN_GAP = 4;
    private static final int SLIDER_ROW_H = 22;
    private static final int SLIDER_LABEL_W = 150;
    private static final int SLIDER_W = 130;

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

    private static String presetTip(CaveConfig.Preset p) {
        return switch (p) {
            case OFF -> "关闭：地下无任何洞穴。\n（本模组是自定义区块生成器、没有噪声设置，"
                    + "原版雕刻器无法调用 ⇒ \"关闭\"即\"无洞穴\"，与参考模组 RTG 的 useCaves=false 同义）";
            case MINIMAL -> "精简：只保留细隧道，洞少而细。\n适合低配/喜欢地下紧凑的玩法。";
            case REALISTIC -> "拟真（默认）：岩性门控（石灰岩溶洞大、花岗岩几乎无洞）"
                    + "+ 层调制（沿层理发育的水平洞穴）。\n本模组独有，三个参考项目都没有这两个耦合。";
            case VANILLA_LIKE -> "接近原版：洞更大更圆、无层理、不看岩性。\n"
                    + "原版雕刻器无法调用，故用现有三维噪声换参数【近似】原版观感。";
            case CUSTOM -> "自定义：直接使用下方的开关与旋钮值。\n手动改任一旋钮会自动切到本档。";
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
        toggles.add(new Toggle("洞穴总开关", "关闭后地下无任何洞穴（与档位正交）。",
                this::onManualChange));
        toggles.add(new Toggle("隧道分量（蜿蜒管道）", "两噪声等值面交线形成的细长管道，负责把洞穴连起来。",
                this::onManualChange));
        toggles.add(new Toggle("空腔分量（可站立的洞厅）",
                "能走进去的大空间，是\"玩家能在洞里走\"的主要来源。\n"
                + "关掉后只剩细隧道，可能站不起来（本项目实测过的失败形态）。", this::onManualChange));
        toggles.add(new Toggle("层调制（防竖直贯穿）",
                "把高大空腔切成一层层有限高度的洞穴，避免挖出贯通世界的竖井。\n"
                + "关掉更接近原版奶酪洞，但可能出现竖直贯穿。", this::onManualChange));
        toggles.add(new Toggle("岩性门控（拟真耦合）",
                "石灰岩（可溶岩）溶洞更发育、花岗岩/片麻岩几乎不成洞。\n"
                + "本项目独有，三个参考项目都没有。关掉则各岩性一视同仁。", this::onManualChange));
        toggles.add(new Toggle("地下洞穴群系",
                "洞穴内出现滴水石洞（钟乳石/石笋，全气候）与繁茂洞穴（苔藓/藤蔓，成林气候）。\n"
                + "装饰由原版自动放置。关掉则洞穴沿用该列的地表群系。", this::onManualChange));
    }

    private void buildKnobs() {
        GeoGenesisConfig c = cfg();
        knobs.add(new Knob("洞穴密度倍率", "乘在分量阈值上：越大洞穴越粗越密。默认 1.00。",
                0.2, 3.0, 1.0,
                () -> ConfigSafe.dbl(c.caveDensityMul, 1.0),
                v -> c.caveDensityMul.set(v),
                v -> String.format("%.2f", v)));
        knobs.add(new Knob("洞顶保护厚度", "洞顶至少低于地表这么多格。\n"
                + "设为 0 允许洞穴破地表形成【入口】—— 想徒步进洞就调小。默认 6。",
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

    private int togglesLabelY() {
        return presetBtnY() + PRESET_BTN_H + SECTION_GAP;
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

    /** 说明文字起始 Y（相对面板顶）。 */
    private static final int DESC_Y = 14;
    /** 说明文字块高度。 */
    private static final int DESC_H = 24;
    /** 档位标题 Y = 顶部 + 说明块。 */
    private static final int LAST_DESC_Y = DESC_Y + DESC_H + 8;

    @Override
    public int getHeight() {
        return knobY(knobs.size() - 1) - top() + SLIDER_ROW_H + 34;
    }

    // ===================== 渲染 =====================

    @Override
    public void render(GuiGraphics g, int mx, int my) {
        Font f = font();
        int y = top();
        drawHeader(g, x, y, "洞穴");

        // 说明
        int dy = y + DESC_Y;
        g.drawString(f, "洞穴为可开关配置：选档位即可，也可逐项微调。",
                x, dy, C_TEXT_DIM);
        g.drawString(f, "改动只影响【新生成】的区块，已生成的区块不变。",
                x, dy + 11, C_TEXT_DIM);

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
        // 当前档位效果（一行摘要，过长则截断）；CUSTOM 时明确标注"已手动调整"
        String summary = cur == CaveConfig.Preset.CUSTOM
                ? "当前：自定义（已手动调整下方开关/旋钮）"
                : firstLine(presetTip(cur));
        g.drawString(f, clip(summary, w), x, presetBtnY() + PRESET_BTN_H + 4,
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
        g.drawString(f, "旋钮", x, knobsLabelY(), C_TEXT_DIM);
        for (int i = 0; i < knobs.size(); i++) {
            Knob k = knobs.get(i);
            int ky = knobY(i);
            if (i % 2 == 0) g.fill(x, ky, x + w, ky + SLIDER_ROW_H, 0x12FFFFFF);
            g.drawString(f, k.label, x + 2, ky + (SLIDER_ROW_H - 8) / 2, C_TEXT_DIM);
            k.slider.setX(x + SLIDER_LABEL_W);
            k.slider.setY(ky + 2);
            k.slider.setWidth(SLIDER_W);
            k.slider.render(g, mx, my, 0);
        }
        for (Knob k : knobs) k.slider.renderTooltip(g, mx, my);

        // ---- 底部提示 ----
        int footY = knobY(knobs.size() - 1) + SLIDER_ROW_H + 8;
        g.drawString(f, "提示：没有\"回退原版\"选项 —— 本模组无原版噪声设置，",
                x, footY, 0xFF808080);
        g.drawString(f, "原版雕刻器无法调用，故\"关闭\"即\"地下无洞穴\"。",
                x, footY + 11, 0xFF808080);
    }

    private static String firstLine(String s) {
        int i = s.indexOf('\n');
        return i < 0 ? s : s.substring(0, i);
    }

    /**
     * 按像素宽度截断文本并加省略号。
     *
     * <p>为何需要：档位摘要（如"拟真（默认）：岩性门控（石灰岩溶洞大、花岗岩几乎无洞）+ …"）
     * 有 40+ 个汉字 ≈ 360px，超过面板宽度（约 300px）。主屏虽有 scissor 裁剪不会崩，
     * 但会<b>硬切在半个字上</b>，很难看 ⇒ 主动截断加"…"。</p>
     */
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

        // 旋钮（先定位再命中判定，与 render 布局一致）
        for (int i = 0; i < knobs.size(); i++) {
            Knob k = knobs.get(i);
            int ky = knobY(i);
            k.slider.setX(x + SLIDER_LABEL_W);
            k.slider.setY(ky + 2);
            k.slider.setWidth(SLIDER_W);
        }
        for (Knob k : knobs) {
            if (k.slider.isHoveringReset((int) mx, (int) my)) {
                k.slider.resetToDefault();
                onManualChange();
                return true;
            }
            if (k.slider.isMouseOver(mx, my)) {
                return k.slider.mouseClicked(mx, my, btn);
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
