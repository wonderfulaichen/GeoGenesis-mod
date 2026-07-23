package com.geogenesis.client.preview;

import com.geogenesis.client.SeedManager;
import com.geogenesis.client.preview.mixer.MixerPanel;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.LongSupplier;

/**
 * 配置预设面板（第 1 个标签，「预设」）。
 *
 * <p>布局（自上而下，固定 + 两个独立滚动区）：
 * <pre>
 *  配置预设                          [固定]
 *  种子: [EditBox] [↻] [★]           [固定]
 *  收藏种子                          [固定]
 *  ┌────────────────────────┐
 *  │ ▶ fav1  ✕              │  [滚动区 A — 收藏，独立 innerScrollY_fav]
 *  │ ▶ fav2  ✕              │
 *  └────────────────────────┘
 *  默认地形预设                      [固定]
 *  ┌────────────────────────┐
 *  │ 群岛世界               │  [滚动区 B — 卡片，独立 innerScrollY_card]
 *  │ 辽阔大陆               │
 *  └────────────────────────┘
 * </pre>
 *
 * <p>滚轮按光标 y 路由到对应滚动区，各自独立；可见性检查（非 scissor）杜绝重叠。
 */
public class PresetsPanel extends ConfigPanel {

    private static final int HEADER_H = 22;
    private static final int SEED_ROW_H = 18;
    private static final int ROW_H = 22;            // 收藏行高（+padding 防文字贴边）
    private static final int ROW_GAP = 3;
    private static final int CARD_H = 60;           // 卡片高（+padding）
    private static final int CARD_GAP = 10;
    private static final int GAP_S = 6;
    private static final int GAP_M = 12;

    private static final int FAV_SCROLL_H = 160;
    private static final int CARD_SCROLL_H = 230;

    private final List<Preset> presets = PresetLibrary.all();
    private final LongSupplier getSeed;
    private final Consumer<Long> setSeed;
    private final Runnable onRefresh;
    private final Consumer<Preset> onApply;
    private final Consumer<AbstractWidget> addWidget;

    private final EditBox seedBox;
    private final Button refreshBtn;
    private final Button favBtn;

    private final List<Integer> favRowY = new ArrayList<>();
    private final List<Long> favRowSeed = new ArrayList<>();
    private final List<Integer> cardY = new ArrayList<>();
    private int innerScrollY_fav = 0;
    private int innerScrollY_card = 0;
    /** 收藏种子折叠面板 */
    private final MixerPanel favSection = new MixerPanel("收藏种子");

    public PresetsPanel(LongSupplier getSeed, Consumer<Long> setSeed, Runnable onRefresh,
                        Consumer<Preset> onApply, Consumer<AbstractWidget> addWidget) {
        this.getSeed = getSeed;
        this.setSeed = setSeed;
        this.onRefresh = onRefresh;
        this.onApply = onApply;
        this.addWidget = addWidget;
        Font font = Minecraft.getInstance().font;
        SeedManager sm = SeedManager.getInstance();

        seedBox = new EditBox(font, 0, 0, 200, SEED_ROW_H, C("种子"));
        seedBox.setValue(String.valueOf(getSeed.getAsLong()));
        seedBox.setResponder(s -> {
            String t = s.trim();
            if (t.isEmpty()) return;
            try { setSeed.accept(Long.parseLong(t)); } catch (NumberFormatException ignored) {}
        });
        addWidget.accept(seedBox);

        refreshBtn = Button.builder(C("↻"), b -> onRefresh.run())
            .pos(0, 0).size(SEED_ROW_H, SEED_ROW_H).build();
        addWidget.accept(refreshBtn);

        favBtn = Button.builder(Star(sm.isFavorite(getSeed.getAsLong())), b -> toggleFavorite())
            .pos(0, 0).size(SEED_ROW_H, SEED_ROW_H).build();
        addWidget.accept(favBtn);

        favSection.setContentHeight(FAV_SCROLL_H);
        favSection.setTitleColor(0xFFe0e0e0);
    }

    private void toggleFavorite() {
        SeedManager sm = SeedManager.getInstance();
        long s = getSeed.getAsLong();
        if (sm.isFavorite(s)) sm.removeFavorite(s); else sm.addFavorite(s, "Seed " + s);
        favBtn.setMessage(Star(sm.isFavorite(s)));
    }

    private boolean isTabActive = false;  // 当前标签是否预设标签

    /** 主屏按 tab 切换时调用，设置预设标签是否活跃。活跃时 widgets 可见性由 render 根据滚动位置决定。 */
    public void setWidgetsVisible(boolean v) {
        this.isTabActive = v;
        if (!v) {
            seedBox.visible = false;
            refreshBtn.visible = false;
            favBtn.visible = false;
        }
    }

    private static net.minecraft.network.chat.Component C(String s) { return net.minecraft.network.chat.Component.literal(s); }
    private static net.minecraft.network.chat.Component Star(boolean on) { return C(on ? "★" : "☆"); }

    @Override
    public int getHeight() {
        return HEADER_H + GAP_S + SEED_ROW_H + GAP_M
             + favSection.getFullHeight() + GAP_M
             + CARD_SCROLL_H + GAP_M + 12;
    }

    // 所有 y 用 top()（随主屏外滚移动）
    private int pinnedHeaderY()             { return top(); }
    private int pinnedSeedY()               { return top() + HEADER_H + GAP_S; }
    /** 收藏折叠面板起始 y（含标题栏 + 可能的内容区） */
    private int pinnedFavTopY()             { return pinnedSeedY() + SEED_ROW_H + GAP_M; }
    private int pinnedFavScrollTopY()       { return pinnedFavTopY() + 16; }  // MixerPanel.TITLE_H=16
    private int pinnedFavScrollBotY()       { return pinnedFavScrollTopY() + FAV_SCROLL_H; }
    private int pinnedCardTitleY()          { return pinnedFavTopY() + favSection.getFullHeight() + GAP_M; }
    private int pinnedCardScrollTopY()      { return pinnedCardTitleY() + 16; }  // MixerPanel.TITLE_H=16
    private int pinnedCardScrollBotY()      { return pinnedCardScrollTopY() + CARD_SCROLL_H; }

    public int getScrollTopY() { return pinnedFavScrollTopY(); }
    public void setViewportHeight(int h) { /* 保留兼容 */ }

    private int favContentH() {
        int favCount = SeedManager.getInstance().getFavorites().size();
        if (favCount == 0) return ROW_H;
        return favCount * (ROW_H + ROW_GAP);
    }
    private int cardContentH() {
        int n = presets.size();
        if (n == 0) return 0;
        return n * (CARD_H + CARD_GAP) - CARD_GAP;
    }

    @Override
    public void render(GuiGraphics g, int mx, int my) {
        // ============ 固定区（随主屏外滚，但 widgets 出 panel 顶时隐藏防压标签条）============
        drawHeader(g, x, pinnedHeaderY(), "配置预设");

        int sy = pinnedSeedY();
        // widgets 绝对坐标低于 panel 顶 (baseY) 时隐藏，避免超裁剪区压到标签条；非活跃标签强制隐藏
        boolean widgetsInView = isTabActive && sy >= baseY;

        int labelW = 40;
        g.drawString(font(), "种子:", x, sy + (SEED_ROW_H - font().lineHeight) / 2, C_TEXT_DIM);
        int btnSize = SEED_ROW_H;
        seedBox.visible = widgetsInView;
        refreshBtn.visible = widgetsInView;
        favBtn.visible = widgetsInView;
        if (widgetsInView) {
            seedBox.setX(x + labelW); seedBox.setY(sy);
            seedBox.setWidth(w - labelW - btnSize * 2 - 8);
            refreshBtn.setX(x + w - btnSize * 2 - 6); refreshBtn.setY(sy);
            favBtn.setX(x + w - btnSize - 2); favBtn.setY(sy);
        }
        long curSeed = getSeed.getAsLong();
        String curStr = String.valueOf(curSeed);
        if (!curStr.equals(seedBox.getValue())) seedBox.setValue(curStr);
        SeedManager sm = SeedManager.getInstance();
        favBtn.setMessage(Star(sm.isFavorite(curSeed)));

        // —— 收藏折叠面板（MixerPanel 可折叠容器）——
        favSection.setBounds(x, pinnedFavTopY(), w);
        favSection.renderHeader(g, mx, my);
        // ★ 每帧清除命中区缓存（在 drawScrollRegion 之前），防止累积+避免互相清空
        favRowY.clear(); favRowSeed.clear(); cardY.clear();
        if (!favSection.isCollapsed()) {
            int favTop = pinnedFavScrollTopY();
            int favBot = pinnedFavScrollBotY();
            g.fill(x + 2, favTop, x + w - 2, favBot, 0xFF15191F);
            g.enableScissor(x, favTop, x + w, favBot);
            drawScrollRegion(g, mx, my, favTop, favBot, curSeed, sm, true);
            g.disableScissor();
            favSection.renderTooltip(g, mx, my);
        }

        // —— 卡片标题 ——
        int cty = pinnedCardTitleY();
        g.drawString(font(), "默认地形预设（点击应用，将覆盖全部参数）", x, cty, C_TEXT_DIM);

        // ============ 滚动区 B：卡片（带 scissor 严格裁剪到区域）============
        int cardTop = pinnedCardScrollTopY();
        int cardBot = pinnedCardScrollBotY();
        g.fill(x + 2, cardTop, x + w - 2, cardBot, 0xFF15191F);
        g.enableScissor(x, cardTop, x + w, cardBot);
        drawScrollRegion(g, mx, my, cardTop, cardBot, curSeed, sm, false);
        g.disableScissor();
    }

    private void drawScrollRegion(GuiGraphics g, int mx, int my,
                                   int top, int bottom, long curSeed, SeedManager sm, boolean isFav) {
        int sTop = top, sBot = bottom;
        if (isFav) {
            int y = sTop;
            List<SeedManager.SeedEntry> favs = sm.getFavorites();
            if (favs.isEmpty()) {
                int ry = y - innerScrollY_fav;
                if (ry < sBot && ry + ROW_H > sTop)
                    g.drawString(font(), "（暂无收藏 · 点 ★ 收藏当前种子）", x + 12, ry + (ROW_H - font().lineHeight) / 2, C_TEXT_DIM);
            } else {
                for (SeedManager.SeedEntry e : favs) {
                    int ry = y - innerScrollY_fav;
                    if (ry < sBot && ry + ROW_H > sTop) {
                        boolean hover = mx >= x && mx <= x + w && my >= ry && my <= ry + ROW_H;
                        g.fill(x + 2, ry, x + w - 2, ry + ROW_H, hover ? C_HOVER : C_BG_ROW);
                        boolean active = e.seed() == curSeed;
                        g.drawString(font(), (active ? "▶ " : "") + e.name() + "  (" + e.seed() + ")",
                            x + 12, ry + (ROW_H - font().lineHeight) / 2, active ? C_ACCENT : C_TEXT);
                        int delX = x + w - 18;
                        boolean delHover = mx >= delX - 4 && mx <= delX + 14 && my >= ry && my <= ry + ROW_H;
                        g.drawString(font(), "✕", delX, ry + (ROW_H - font().lineHeight) / 2,
                            delHover ? 0xFFFF7777 : C_TEXT_DIM);
                        // 无条件记录命中区（不能只在 hover 时记录，否则鼠标不悬停的行永远点不到）
                        favRowY.add(ry); favRowSeed.add(e.seed());
                    }
                    y += ROW_H + ROW_GAP;
                }
            }
            int contentH = favContentH();
            int maxScroll = Math.max(0, contentH - (sBot - sTop));
            if (maxScroll > 0) {
                int barH = Math.max(14, (sBot - sTop) * (sBot - sTop) / Math.max(1, contentH));
                int barY = sTop + innerScrollY_fav * ((sBot - sTop) - barH) / maxScroll;
                g.fill(x + w - 3, barY, x + w - 1, barY + barH, 0xFF00c896);
            }
        } else {
            int y = sTop;
            for (Preset p : presets) {
                int py = y - innerScrollY_card;
                if (py < sBot && py + CARD_H > sTop) {
                    boolean hover = mx >= x && mx <= x + w && my >= py && my <= py + CARD_H;
                    g.fill(x + 2, py, x + w - 2, py + CARD_H, hover ? C_HOVER : C_BG_ROW);
                    g.fill(x + 2, py, x + 4, py + CARD_H, C_ACCENT);
                    g.drawString(font(), p.name, x + 10, py + 8, C_TEXT);
                    String desc = p.desc;
                    int maxW = w - 24;
                    if (font().width(desc) > maxW) desc = font().plainSubstrByWidth(desc, maxW, true);
                    g.drawString(font(), desc, x + 10, py + 28, C_TEXT_DIM);
                    cardY.add(py);
                }
                y += CARD_H + CARD_GAP;
            }
            int contentH = cardContentH();
            int maxScroll = Math.max(0, contentH - (sBot - sTop));
            if (maxScroll > 0) {
                int barH = Math.max(14, (sBot - sTop) * (sBot - sTop) / Math.max(1, contentH));
                int barY = sTop + innerScrollY_card * ((sBot - sTop) - barH) / maxScroll;
                g.fill(x + w - 3, barY, x + w - 1, barY + barH, 0xFF00c896);
            }
        }
    }

    @Override
    public boolean mouseClicked(double mx, double my, int btn) {
        // 收藏折叠面板头点击（展开/折叠切换）
        if (favSection.hitTestHeader((int) mx, (int) my)) {
            return favSection.mouseClicked(mx, my, btn);
        }
        // 卡片点击
        for (int i = 0; i < cardY.size(); i++) {
            if (hit(x, cardY.get(i), w, CARD_H, mx, my)) {
                onApply.accept(presets.get(i));
                playClick();
                return true;
            }
        }
        // 收藏行点击（仅在展开时有效）
        if (!favSection.isCollapsed()) {
            SeedManager sm = SeedManager.getInstance();
            int delX = x + w - 18;
            for (int i = 0; i < favRowY.size(); i++) {
                if (hit(x, favRowY.get(i), w, ROW_H, mx, my)) {
                    if (mx >= delX - 4 && mx <= delX + 14) {
                        sm.removeFavorite(favRowSeed.get(i));
                        playClick();
                    } else {
                        setSeed.accept(favRowSeed.get(i));
                        playClick();
                    }
                    return true;
                }
            }
        }
        return false;
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double delta) {
        // 卡片区：内部滚动
        if (my >= pinnedCardScrollTopY() && my < pinnedCardScrollBotY()) {
            int maxScroll = Math.max(0, cardContentH() - CARD_SCROLL_H);
            if (maxScroll <= 0) return false;
            innerScrollY_card = Clamp(innerScrollY_card - (int)(delta * 20), 0, maxScroll);
            return true;
        }
        // 收藏区：内部滚动
        if (my >= pinnedFavScrollTopY() && my < pinnedFavScrollBotY()) {
            int maxScroll = Math.max(0, favContentH() - FAV_SCROLL_H);
            if (maxScroll <= 0) return false;
            innerScrollY_fav = Clamp(innerScrollY_fav - (int)(delta * 20), 0, maxScroll);
            return true;
        }
        // 固定区（标题/种子栏/收藏标题/卡片标题）：交主屏外滚（整个面板上下移动）
        return false;
    }

    private static int Clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    @Override public boolean mouseReleased(double mx, double my, int btn) { return false; }
    @Override public boolean mouseDragged(double mx, double my, int btn, double dx, double dy) { return false; }
}