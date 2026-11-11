package com.geogenesis.client.preview;

import com.geogenesis.worldgen.climate.BiomeClassifier;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.resources.language.I18n;

import java.util.ArrayList;
import java.util.List;

/**
 * 群系设置面板（「群系」页签）：7 个筛选胶囊按钮 + 可滚动群系列表（颜色方块 + 本地化名称）。
 * 群系来自 {@link BiomeClassifier} 分类结果，颜色由 {@link GeoPalette#colorForBiome} 提供，只读。
 */
public class BiomesPanel extends ConfigPanel {

    private final List<BiomeEntryWidget> entries = new ArrayList<>();
    private final BiomeListPanel listPanel;
    private int filterMode = 0;

    private static final int FILTER_H = 20;
    private static final int FILTER_GAP = 4;
    private static final int FILTER_OFF = 24;
    private static final int LIST_GAP = 8;
    private static final String[] FILTER_KEYS = {
        "geogenesis.settings.biomes.filter_all",
        "geogenesis.settings.biomes.filter_land",
        "geogenesis.settings.biomes.filter_ocean",
        "geogenesis.settings.biomes.filter_cold",
        "geogenesis.settings.biomes.filter_temp",
        "geogenesis.settings.biomes.filter_hot",
        "geogenesis.settings.biomes.filter_dry"
    };

    private final PreviewDisplay preview;
    /** 面板底 y（listBottom），由 Screen 设置以填满整个面板区 */
    private int panelBottomY = 0;

    public BiomesPanel(PreviewDisplay preview) {
        this.preview = preview;
        BiomeClassifier.BiomeClass[] all = BiomeClassifier.BiomeClass.values();
        for (BiomeClassifier.BiomeClass b : all) {
            int color = GeoPalette.colorForBiome(b.ordinal());
            String key = "geogenesis.biome." + b.name();
            String local = I18n.get(key);
            if (local.equals(key)) local = b.name();
            entries.add(new BiomeEntryWidget(b, color, local));
        }
        listPanel = new BiomeListPanel(entries);
        listPanel.setOnSelect(b -> preview.setSelected(GeoPalette.PreviewLayer.BIOME, b.ordinal()));
    }

    /** Screen render 前调用，告知面板底 y（listBottom）以填满整个面板区（消除底部空白） */
    public void setPanelBottom(int y) { this.panelBottomY = y; }

    /**
     * 内容高度（★ v22：按<b>列表区实际高度</b>算）。
     *
     * <p>原写死 420 —— 但列表高度随 {@code panelBottomY}（窗口高）变化，
     * 大窗口下列表更高、滚动上限却按 420 算 ⇒ 滚到底仍看不全；
     * 小窗口则反过来留出一大片空白。</p>
     */
    @Override
    public int getHeight() {
        int ly = listY() - top();          // 相对内容顶的列表起点
        return ly + listHeightFor() + 8 + 18;
    }

    private int filterY() { return top() + FILTER_OFF; }
    private int listY() { return filterY() + FILTER_H + LIST_GAP; }

    /**
     * 列表区高度（★ v72：render 与 getHeight 共用＝内容全高）。
     *
     * <p>旧实现把列表撑满到 {@code panelBottomY}（固定视口），
     * 溢出部分靠 {@code BiomeListPanel} 的内层滚轮
     * ⇒ 与外层页面滚轮冲突（用户：「非常容易误操作」）。
     * 现在列表按内容全高展开，滚轮只归页面。</p>
     */
    private int listHeightFor() {
        listPanel.setFilter(filterMode);
        return Math.max(160, listPanel.naturalHeight());
    }

    @Override
    public void render(GuiGraphics g, int mx, int my) {
        // ★ v15：标题由 ConfigPanelHost 统一画（drawHeader 已空实现）

        int fy = filterY();
        int n = FILTER_KEYS.length;
        int fw = (w - (n - 1) * FILTER_GAP) / n;
        for (int i = 0; i < n; i++) {
            int fx = x + i * (fw + FILTER_GAP);
            drawButton(g, fx, fy, fw, FILTER_H, I18n.get(FILTER_KEYS[i]), filterMode == i, mx, my);
        }

        int ly = listY();
        int lh = listHeightFor();
        listPanel.setRect(x, ly, w, lh);
        listPanel.setFilter(filterMode);
        boolean selLayer = preview.getSelectedLayer() == GeoPalette.PreviewLayer.BIOME;
        int selId = preview.getSelectedId();
        for (BiomeEntryWidget e : entries) {
            e.setSelected(selLayer && selId >= 0 && e.biome.ordinal() == selId);
        }
        listPanel.render(g, mx, my);

        // 底部 footer：统计 + 提示（填满剩余空白并给用户反馈）
        int footerY = ly + lh + 6;
        if (footerY < panelBottomY - 6 && panelBottomY > 0) {
            int shown = 0;
            for (BiomeEntryWidget e : entries) if (e.matchesFilter(filterMode)) shown++;
            String text = "共 " + shown + " / " + entries.size() + " 个群系 · 点击地图/图例高亮选中";
            // ★ v15：走 UiKit 的统一字色（原用旧常量 C_TEXT_DIM）
            g.drawCenteredString(font(), text, x + w / 2, footerY, UiKit.C_TXT_DIM);
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        int fy = filterY();
        int n = FILTER_KEYS.length;
        int fw = (w - (n - 1) * FILTER_GAP) / n;
        for (int i = 0; i < n; i++) {
            int fx = x + i * (fw + FILTER_GAP);
            if (hit(fx, fy, fw, FILTER_H, mx, my)) {
                filterMode = i;
                playClick();
                return true;
            }
        }
        return listPanel.mouseClicked(mx, my, btn);
    }

    /** ★ v72：不再消费滚轮 —— 交给外层页面（列表已全高展开）。 */
    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        return false;
    }

    @Override
    public boolean mouseReleased(double mx, double my, int btn) { return false; }

    @Override
    public boolean mouseDragged(double mx, double my, int btn, double dx, double dy) { return false; }
}
