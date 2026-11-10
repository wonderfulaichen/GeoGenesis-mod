package com.geogenesis.client.preview;

import com.geogenesis.config.ConfigSafe;
import com.geogenesis.config.GeoGenesisConfig;
import com.geogenesis.worldgen.generator.VanillaDecorationFilter;
import com.geogenesis.worldgen.ore.OreVeins;

import net.minecraft.client.gui.GuiGraphics;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * ★ 2026-09-18：<b>矿物配置页签</b>（照 {@link CavePanel} 范式）。
 *
 * <h2>它解决什么</h2>
 * <p>此前矿物有两个开关（{@code oreVeinsEnabled} / {@code oreOverrideVanilla}），但
 * <b>没有界面入口、改配置必须重启游戏</b> —— 与本项目"UI 改了却不生效 = 功能没接到底"
 * 的既有教训同型。本面板补上入口，并接通<b>热刷新</b>。</p>
 *
 * <h2>两个开关的语义（界面必须讲清，否则玩家会误判）</h2>
 * <table border="1">
 *   <caption>组合行为</caption>
 *   <tr><th>自研矿脉</th><th>接管原版矿</th><th>地下矿的构成</th></tr>
 *   <tr><td>开</td><td>关（默认）</td><td>原版矿 + 自研地质矿脉（<b>叠加</b>）</td></tr>
 *   <tr><td>开</td><td><b>开</b></td><td><b>只有自研地质矿脉</b>（原版金属矿被移除）</td></tr>
 *   <tr><td>关</td><td>关</td><td>只有原版矿（纯原版体验）</td></tr>
 *   <tr><td>关</td><td>开</td><td>⚠ <b>地下几乎无矿</b>——两者都关掉了，界面须警告</td></tr>
 * </table>
 *
 * <h2>改动如何生效（本面板的关键）</h2>
 * <p>写配置后调 {@link OreVeins#markConfigDirty()} ⇒ 下一次生成前
 * （{@code OreVeins.beginColumn} 的列级入口）自动重解析并注入
 * ⇒ <b>新生成的区块立刻用新设置</b>。已生成区块不会改变（世界生成的固有性质）。</p>
 *
 * <h2>多模组兼容（界面必须说明，否则玩家会担心）</h2>
 * <p>「接管原版矿」<b>只影响 {@code minecraft} 命名空间</b> ⇒
 * 任何模组添加的矿<b>完全不受影响</b>。</p>
 */
public class OrePanel extends ConfigPanel {

    // ---- 布局常量 ----
    /** 开关行高（★ v14：统一引用 UiKit；说明行单独按折行计算，见 {@link #rowYs()}）。 */
    private static final int ROW_H = UiKit.ROW_H;

    /** 一个开关项：读 / 写 / 文案。 */
    private record Toggle(BooleanSupplier read, Consumer<Boolean> write, String label, String tip) { }

    private final java.util.List<Toggle> toggles = new java.util.ArrayList<>();

    public OrePanel() {
        toggles.add(new Toggle(
                () -> ConfigSafe.bool(GeoGenesisConfig.INSTANCE.oreVeinsEnabled, true),
                v -> GeoGenesisConfig.INSTANCE.oreVeinsEnabled.set(v),
                "自研地质矿脉",
                "按【宿主岩 + 深度带】成矿：煤在砂岩/页岩、钻石在片麻岩深处。"));
        toggles.add(new Toggle(
                () -> ConfigSafe.bool(GeoGenesisConfig.INSTANCE.oreOverrideVanilla, false),
                v -> GeoGenesisConfig.INSTANCE.oreOverrideVanilla.set(v),
                "接管原版矿",
                "移除原版金属矿，地下矿改由自研地质矿脉独占。"));
    }

    /** 从配置回读（每次进屏调用）。开关为直读模式 ⇒ 此处只需确保配置已加载。 */
    public void buildFromConfig() {
        for (Toggle t : toggles) t.read().getAsBoolean();
    }

    // ===================== 交互 =====================

    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button != 0) return false;
        // ★ v8：用与 render 同一份布局（说明会折行，行高不再固定）
        int[] ys = rowYs();
        for (int i = 0; i < ys.length; i++) {
            if (hit(x, ys[i], w, ROW_H, mx, my)) {
                applyToggle(i, !toggles.get(i).read().getAsBoolean());
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int button) {
        return false;
    }

    @Override
    public boolean mouseDragged(double mx, double my, int button, double dx, double dy) {
        return false;
    }

    /** 写入配置并触发热刷新（★ 关键：不做这一步就是"UI 改了不生效"）。 */
    private void applyToggle(int index, boolean value) {
        if (index < 0 || index >= toggles.size()) return;
        toggles.get(index).write().accept(value);
        // ★ 热刷新：标记脏 ⇒ 下一次生成前自动重解析（照 CaveShape 范式）
        OreVeins.markConfigDirty();
        // ★ 接管模式改动了"哪些特征会被剔" ⇒ 必须清【按群系缓存】，
        //   否则会命中按旧模式构建的 BiomeGenerationSettings（表现为"没生效"）。
        VanillaDecorationFilter.invalidateCache();
        onMarkDirty.run();
        playClick();
    }

    // ===================== 布局（★ v8：绘制与命中的唯一来源）=====================

    /** 顶部说明文字。 */
    private static final String INTRO = "矿物由地质决定：不同岩石在不同深度产出不同矿。";

    /** 单行说明的行高（开关 label 行）。 */
    private static final int LABEL_ROW = ROW_H;

    /**
     * 各开关行的 y（★ v8）。
     *
     * <p>每行高度 = 开关行 + 该行说明折行后的高度 + 间距。
     * <b>绘制与命中共用本方法</b> —— 否则说明折成两行后点击会整体错位
     * （本项目反复出现的"画在一处、点判在另一处"）。</p>
     */
    private int[] rowYs() {
        int[] out = new int[toggles.size()];
        int cur = top() + 2 + UiKit.wrappedHeight(INTRO, Math.max(60, w - 4));
        for (int i = 0; i < toggles.size(); i++) {
            out[i] = cur + 4;
            int tipH = UiKit.wrappedHeight(toggles.get(i).tip(), Math.max(60, w - 16));
            cur = out[i] + LABEL_ROW + 1 + tipH + 6;
        }
        return out;
    }

    /** 结论提示卡之后的 y（供 getHeight 与 render 共用）。 */
    private int summaryCardTop() {
        int[] ys = rowYs();
        int last = ys.length == 0 ? top() + 2 : ys[ys.length - 1] + LABEL_ROW + 1
                + UiKit.wrappedHeight(toggles.isEmpty() ? "" : toggles.get(toggles.size() - 1).tip(),
                        Math.max(60, w - 16)) + 6;
        return last;
    }

    @Override
    public int getHeight() {
        // ★ v8：按折行后的真实高度累加（原来写死 62，长说明被裁）
        int cur = summaryCardTop();
        cur += UiKit.wrappedHeight(summaryText(), Math.max(60, w - 12)) + 8 + 6;
        cur += UiKit.wrappedHeight("只影响原版矿，其它模组的矿不受影响。", Math.max(60, w - 4));
        cur += UiKit.wrappedHeight("更改设置后，只对之后新生成的区块生效。", Math.max(60, w - 4));
        return cur - top() + 8;
    }

    /** 当前组合状态的结论文案（render 与 getHeight 共用同一份）。 */
    private String summaryText() {
        boolean veins = toggles.get(0).read().getAsBoolean();
        boolean override = toggles.get(1).read().getAsBoolean();
        if (!veins && override) return "两项都关 = 地下几乎没有矿，请至少开启一项。";
        if (veins && override) return "当前：地下矿完全由自研地质矿脉生成（原版金属矿已移除）。";
        if (veins) return "当前：原版矿 + 自研地质矿脉（二者叠加）。";
        return "当前：只有原版矿（纯原版体验）。";
    }

    // ===================== 渲染 =====================

    @Override
    public void render(GuiGraphics g, int mx, int my) {
        // ★ v8：标题由 ConfigPanelHost 统一画；说明改为<b>折行</b>
        //   （旧版 4 处单行文字全部超出面板右缘被裁）
        int curY = top() + 2;
        curY += UiKit.drawWrapped(g, INTRO, x + 2, curY, Math.max(60, w - 4), UiKit.C_TXT_DIM);

        int[] ys = rowYs();
        for (int i = 0; i < toggles.size(); i++) {
            Toggle t = toggles.get(i);
            int ry = ys[i];
            boolean on = t.read().getAsBoolean();
            boolean hover = drawToggleRow(g, x, ry, w, ROW_H, t.label(), on, mx, my);
            // 说明行：折行（宽度受限，长说明不会被裁）
            UiKit.drawWrapped(g, t.tip(), x + 8, ry + ROW_H + 1,
                    Math.max(60, w - 16), UiKit.C_TXT_DIM);
            if (hover) hoverTooltip = net.minecraft.network.chat.Component.literal(t.tip());
        }

        // ---- 组合状态提示（★ 让玩家知道当前到底会生成什么）----
        String summary = summaryText();
        boolean warn = !toggles.get(0).read().getAsBoolean() && toggles.get(1).read().getAsBoolean();
        int cardTop = summaryCardTop();
        // ★ v13：宽度与下面 drawWrapped 一致（w-4），否则高度估算偏小、下一段会压上来
        int cardH = UiKit.wrappedHeight(summary, Math.max(60, w - 4)) + 4;
        // 状态卡：略亮底，把结论托起来
        // ★ v13：状态卡底彻底去掉（实心块会显出矩形边界），结论靠<span>颜色</span>区分
        UiKit.drawWrapped(g, summary, x + 2, cardTop + 2, Math.max(60, w - 4),
                warn ? 0xFFFFAA00 : UiKit.C_TXT_SUB);

        // ---- 多模组兼容 + 生效范围（必须写明）----
        int afterCard = cardTop + cardH + 6;
        afterCard += UiKit.drawWrapped(g, "只影响原版矿，其它模组的矿不受影响。",
                x + 2, afterCard, Math.max(60, w - 4), UiKit.C_TXT_DIM);
        afterCard += UiKit.drawWrapped(g, "更改设置后，只对之后新生成的区块生效。",
                x + 2, afterCard, Math.max(60, w - 4), UiKit.C_TXT_DIM);
    }
}
