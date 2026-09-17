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

    // ---- 布局常量（与 CavePanel 观感一致）----
    private static final int ROW_H = 22;
    private static final int ROW_GAP = 5;

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
        for (int i = 0; i < toggles.size(); i++) {
            if (hit(x, rowY(i), w, ROW_H, mx, my)) {
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

    // ===================== 布局 =====================

    private int rowY(int i) {
        return top() + 26 + i * (ROW_H + ROW_GAP);
    }

    @Override
    public int getHeight() {
        return 26 + toggles.size() * (ROW_H + ROW_GAP) + 62;
    }

    // ===================== 渲染 =====================

    @Override
    public void render(GuiGraphics g, int mx, int my) {
        drawHeader(g, x, top(), "矿物");

        g.drawString(font(), "矿物由地质决定：不同岩石在不同深度产出不同矿。",
                x + 2, top() + 13, C_TEXT_DIM);

        for (int i = 0; i < toggles.size(); i++) {
            Toggle t = toggles.get(i);
            int ry = rowY(i);
            boolean on = t.read().getAsBoolean();
            boolean hover = drawToggleRow(g, x, ry, w, ROW_H, t.label(), on, mx, my);
            // 说明行（置于开关行下方，避免与胶囊重叠）
            g.drawString(font(), t.tip(), x + 8, ry + ROW_H - 4, C_TEXT_DIM);
            // 悬停时把完整说明交给主屏绘制（保持与其它面板一致的 tooltip 行为）
            if (hover) hoverTooltip = net.minecraft.network.chat.Component.literal(t.tip());
        }

        // ---- 组合状态提示（★ 让玩家知道当前到底会生成什么）----
        boolean veins = toggles.get(0).read().getAsBoolean();
        boolean override = toggles.get(1).read().getAsBoolean();
        int sy = rowY(toggles.size() - 1) + ROW_H + 14;

        String summary;
        int color = C_TEXT_DIM;
        if (!veins && override) {
            summary = "⚠ 两项都关 = 地下几乎没有矿，请至少开启一项。";
            color = 0xFFFFAA00;
        } else if (veins && override) {
            summary = "当前：地下矿完全由自研地质矿脉生成（原版金属矿已移除）。";
        } else if (veins) {
            summary = "当前：原版矿 + 自研地质矿脉（二者叠加）。";
        } else {
            summary = "当前：只有原版矿（纯原版体验）。";
        }
        g.drawString(font(), summary, x + 2, sy, color);

        // ---- 多模组兼容 + 生效范围（必须写明）----
        g.drawString(font(), "只影响原版矿，其它模组的矿不受影响。",
                x + 2, sy + 13, C_TEXT_DIM);
        g.drawString(font(), "更改设置后，只对之后新生成的区块生效。",
                x + 2, sy + 25, C_TEXT_DIM);
    }
}
