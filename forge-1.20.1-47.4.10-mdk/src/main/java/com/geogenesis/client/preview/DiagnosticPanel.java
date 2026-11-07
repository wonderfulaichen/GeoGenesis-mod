package com.geogenesis.client.preview;

import com.geogenesis.config.ConfigKeyCatalog;
import com.geogenesis.config.PanelLayout;
import net.minecraft.client.gui.GuiGraphics;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * 「诊断」页签：诊断键（目录驱动，默认折叠语义）+ <b>「显示旧算法参数」开关</b>。
 *
 * <h2>这个开关是本轮重构的"出口"</h2>
 * <p>用户已裁定<b>没有 legacy 存档</b>，但 70 个 {@code LEGACY_ONLY} 键仍在代码里活着
 * （它们是 {@code morphBase=false} 分支的真实旋钮，删掉会毁掉整条 legacy 链）。
 * 面板的正确处置不是"删"也不是"照常展示"，而是<b>默认隐藏 + 显式开关 + 明确标注
 * 「仅旧存档生效」</b>。</p>
 * <p>开关放在「诊断」页而不是某个显眼位置，是刻意的：它是个<b>罕见</b>操作
 * （只有调试旧存档才需要），不该与日常旋钮争夺注意力。</p>
 *
 * <h2>★ 为什么还要在页面上写「只读诊断键」的警告</h2>
 * <p>已删除的两个假键（{@code elevHigh} / {@code reliefHigh}，2026-11-07）曾是这样：
 * {@code src/main} 内唯一读取点是一个带 {@code main} 的量化诊断类，
 * <b>生产地形链根本不读</b>。也就是说拖动它们<b>不会改变任何地形</b>。
 * 这事写在 javadoc 里没人会看到，所以当时必须写在界面上；现在键已删，警告随之消失。</p>
 */
public class DiagnosticPanel extends DomainPanel {

    private static final int TOGGLE_H = 22;

    private final BooleanSupplier showLegacy;
    private final Consumer<Boolean> setShowLegacy;

    public DiagnosticPanel(BooleanSupplier showLegacy, Consumer<Boolean> setShowLegacy) {
        super(PanelLayout.Tab.DIAGNOSTIC, "诊断",
                "只影响日志与自检，不改变地形产出。默认折叠，展开才占用界面。");
        this.showLegacy = showLegacy;
        this.setShowLegacy = setShowLegacy;
    }

    public int toggleY() { return rowsBottomY() + 4; }

    @Override
    protected int extraHeight() { return 14 + TOGGLE_H + 30; }

    @Override
    protected void renderExtra(GuiGraphics g, int mx, int my, int y) {
        g.fill(x, y + 4, x + w, y + 5, C_LINE);
        int ty = y + 10;
        boolean on = showLegacy.getAsBoolean();
        boolean hover = drawToggleRow(g, x, ty, w, TOGGLE_H - 2,
                "显示旧算法参数", on, mx, my);
        if (hover) {
            hoverTooltip = net.minecraft.network.chat.Component.literal(
                    "旧存档（legacy 地形链）专用的 " + ConfigKeyCatalog.countAxis(
                            ConfigKeyCatalog.Axis.LEGACY_ONLY) + " 个旋钮。\n"
                    + "新世界走 morph-base-p1，这批键对新世界零效果 ——\n"
                    + "所以默认隐藏，避免把「转了没反应」当成 bug 排查。");
        }
        int noteY = ty + TOGGLE_H + 2;
        g.drawString(font(), "⚠「高海拔/高起伏阈值」只被诊断类读取，改它不会改变地形。",
                x + 2, noteY, 0xFFFFAA00);
    }

    @Override
    protected boolean mouseClickedExtra(double mx, double my, int btn, int y) {
        int ty = y + 10;
        if (btn == 0 && hit(x, ty, w, TOGGLE_H - 2, mx, my)) {
            setShowLegacy.accept(!showLegacy.getAsBoolean());
            playClick();
            return true;
        }
        return false;
    }
}
