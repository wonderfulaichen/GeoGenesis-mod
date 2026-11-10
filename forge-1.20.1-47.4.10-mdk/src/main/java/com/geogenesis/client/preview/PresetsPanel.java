package com.geogenesis.client.preview;

import com.geogenesis.client.SeedManager;
import com.geogenesis.client.UserPresetsStore;
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
    private static final int SEED_ROW_H = UiKit.CHIP_H;
    private static final int ROW_H = UiKit.ROW_H;    // 收藏行高（统一）
    private static final int ROW_GAP = 3;
    private static final int CARD_H = 60;           // 卡片高（+padding）
    private static final int CARD_GAP = 10;
    private static final int GAP_S = 6;
    private static final int GAP_M = 12;

    private static final int FAV_SCROLL_H = 160;
    private static final int CARD_SCROLL_H = 230;
    private static final int USER_SCROLL_H = 200;

    private final List<Preset> presets = PresetLibrary.all();
    private final LongSupplier getSeed;
    private final Consumer<Long> setSeed;
    private final Runnable onRefresh;
    private final Consumer<Preset> onApply;
    private final Consumer<UserPresetsStore.UserPreset> onApplyUser;
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

    /** 用户自定义预设（从 user_presets.json 加载，可加载/删除） */
    private final List<UserPresetsStore.UserPreset> userPresets = new ArrayList<>();
    private final List<Integer> userCardY = new ArrayList<>();
    private final List<String> userCardId = new ArrayList<>();
    private int innerScrollY_user = 0;

    public PresetsPanel(LongSupplier getSeed, Consumer<Long> setSeed, Runnable onRefresh,
                        Consumer<Preset> onApply, Consumer<AbstractWidget> addWidget,
                        Consumer<UserPresetsStore.UserPreset> onApplyUser) {
        this.getSeed = getSeed;
        this.setSeed = setSeed;
        this.onRefresh = onRefresh;
        this.onApply = onApply;
        this.addWidget = addWidget;
        this.onApplyUser = onApplyUser;
        refreshUserPresets();
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

        // ★ v7：内容高度改为按收藏条数算（每帧刷新）——
        //   写死 160 会让 2 条收藏也占满 160px，挤压下方卡片区。
        favSection.setContentHeight(Math.max(ROW_H, favScrollH()));
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

    /** 内容区可用高度（由主屏每帧注入；0 = 未注入，退回写死值）。 */
    private int viewportH = 0;
    /** 两个滚动区的实际高度（按 viewportH 分摊，见 {@link #recomputeScrollHeights()}）。 */
    private int cardScrollH = CARD_SCROLL_H;
    private int userScrollH = USER_SCROLL_H;

    /**
     * ★ 2026-11-09 v7【修"内容面板下方大片空白"】：
     * 原来这个方法体是空的（注释「保留兼容」），于是两个预设列表区
     * 恒按 230 + 200 的写死高度画 —— 内容区实际有 600+ 像素时，
     * 底部就空出一大片。
     *
     * <p>现在把可用高度按比例摊给它俩：卡片区 55% / 用户区 45%，
     * 各不低于原最小值（避免小窗口时挤没）。</p>
     */
    public void setViewportHeight(int h) {
        if (h <= 0 || h == viewportH) return;
        viewportH = h;
        recomputeScrollHeights();
    }

    private void recomputeScrollHeights() {
        // 扣掉所有固定项，剩下的才是两个滚动区能用的高度
        // ★ v7：不再扣 HEADER_H（标题移到面板顶部，由 ConfigPanelHost 画）
        int fixed = 2 + SEED_ROW_H + GAP_M
                  + favSection.getFullHeight() + GAP_M * 3 + 12;
        int avail = viewportH - fixed;
        if (avail <= CARD_SCROLL_H + USER_SCROLL_H) {
            // 空间不足：保持原值（外滚会兜住）
            cardScrollH = CARD_SCROLL_H;
            userScrollH = USER_SCROLL_H;
            return;
        }
        // 两区标题各占 16px
        int body = avail - 32;
        cardScrollH = Math.max(CARD_SCROLL_H, (int) (body * 0.55));
        userScrollH = Math.max(USER_SCROLL_H, body - cardScrollH);
    }

    @Override
    public int getHeight() {
        return 2 + SEED_ROW_H + GAP_M
             + favSection.getFullHeight() + GAP_M
             + cardScrollH + GAP_M
             + userScrollH + GAP_M + 12;
    }

    // 所有 y 用 top()（随主屏外滚移动）
    /**
     * 种子行 y（★ v7：标题由 ConfigPanelHost 统一画，这里不再预留 {@code HEADER_H}）。
     *
     * <p>旧版 {@code top() + HEADER_H + GAP_S} 会留出 28px 空白 ——
     * 因为原来是本面板自己画标题，现在标题在面板顶部，那段就空了。</p>
     */
    private int pinnedSeedY()               { return top() + 2; }
    /** 收藏折叠面板起始 y（含标题栏 + 可能的内容区） */
    private int pinnedFavTopY()             { return pinnedSeedY() + SEED_ROW_H + GAP_M; }
    private int pinnedFavScrollTopY()       { return pinnedFavTopY() + 16; }  // MixerPanel.TITLE_H=16
    private int pinnedFavScrollBotY()       { return pinnedFavScrollTopY() + favScrollH(); }

    /**
     * ★ v7：收藏区高度<b>按内容自适应</b>（上限 {@link #FAV_SCROLL_H}）。
     *
     * <p>截图问题：固定 160 或展开后按容器撑开时，只有 2 条收藏 seed 却占
     * 一大片空白，把下面的「默认地形预设」往下挤。现在内容少就矮、多了才滚。</p>
     */
    private int favScrollH() {
        if (favSection.isCollapsed()) return 0;
        int content = favContentH();
        if (content <= 0) return ROW_H;            // 空列表也给一行提示位
        return Math.min(FAV_SCROLL_H, content);
    }
    private int pinnedCardTitleY()          { return pinnedFavTopY() + favSection.getFullHeight() + GAP_M; }
    private int pinnedCardScrollTopY()      { return pinnedCardTitleY() + 16; }  // MixerPanel.TITLE_H=16
    private int pinnedCardScrollBotY()      { return pinnedCardScrollTopY() + cardScrollH; }
    private int pinnedUserTitleY()          { return pinnedCardScrollBotY() + GAP_M; }
    private int pinnedUserScrollTopY()      { return pinnedUserTitleY() + 16; }
    private int pinnedUserScrollBotY()      { return pinnedUserScrollTopY() + userScrollH; }

    public int getScrollTopY() { return pinnedFavScrollTopY(); }

    /** 从 UserPresetsStore 重新加载用户预设列表（保存/删除后调用刷新） */
    public void refreshUserPresets() {
        userPresets.clear();
        userPresets.addAll(UserPresetsStore.getInstance().list());
    }

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
    private int userContentH() {
        int n = userPresets.size();
        if (n == 0) return 0;
        return n * (CARD_H + CARD_GAP) - CARD_GAP;
    }

    @Override
    public void render(GuiGraphics g, int mx, int my) {
        // ============ 固定区（随主屏外滚，但 widgets 出 panel 顶时隐藏防压标签条）============
        // ★ v15：标题由 ConfigPanelHost 统一画（drawHeader 已空实现）

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
        //   ★ v7：每帧按当前收藏条数刷新内容高度（否则 2 条也会占 160px）
        favSection.setContentHeight(Math.max(ROW_H, favScrollH()));
        favSection.setBounds(x, pinnedFavTopY(), w);
        favSection.renderHeader(g, mx, my);
        // ★ 每帧清除命中区缓存（在 drawScrollRegion 之前），防止累积+避免互相清空
        favRowY.clear(); favRowSeed.clear(); cardY.clear(); userCardY.clear(); userCardId.clear();
        if (!favSection.isCollapsed()) {
            int favTop = pinnedFavScrollTopY();
            int favBot = pinnedFavScrollBotY();
            UiKit.roundRect(g, x + 2, favTop, x + w - 2, favBot, UiKit.RADIUS_CARD, 0xFF1A1A1A);
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
        UiKit.roundRect(g, x + 2, cardTop, x + w - 2, cardBot, UiKit.RADIUS_CARD, 0xFF1A1A1A);
        g.enableScissor(x, cardTop, x + w, cardBot);
        drawScrollRegion(g, mx, my, cardTop, cardBot, curSeed, sm, false);
        g.disableScissor();

        // —— 用户预设标题 ——
        int uty = pinnedUserTitleY();
        g.drawString(font(), "我的预设（点击加载 · ✕ 删除）", x, uty, C_TEXT_DIM);

        // ============ 滚动区 C：用户自定义预设（带 scissor 严格裁剪到区域）============
        int userTop = pinnedUserScrollTopY();
        int userBot = pinnedUserScrollBotY();
        UiKit.roundRect(g, x + 2, userTop, x + w - 2, userBot, UiKit.RADIUS_CARD, 0xFF1A1A1A);
        g.enableScissor(x, userTop, x + w, userBot);
        drawUserRegion(g, mx, my, userTop, userBot);
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
                UiKit.scrollbar(g, x + w - 3, sTop, sBot - sTop, contentH, innerScrollY_fav, false);
        } else {
            int y = sTop;
            for (Preset p : presets) {
                int py = y - innerScrollY_card;
                if (py < sBot && py + CARD_H > sTop) {
                    boolean hover = mx >= x && mx <= x + w && my >= py && my <= py + CARD_H;
                    // ★ v7 自绘卡片：圆角 + 主色左条 + 主/副两行文字（对齐示意图）
                    UiKit.roundRect(g, x + 4, py, x + w - 4, py + CARD_H, 4,
                            hover ? UiKit.C_CARD_HOVER : UiKit.C_CARD);
                    g.fill(x + 4, py + 6, x + 6, py + CARD_H - 6, UiKit.C_ACCENT);
                    g.drawString(font(), p.name, x + 14, py + 10, UiKit.C_TXT, false);
                    String desc = p.desc;
                    int maxW = w - 34;
                    if (font().width(desc) > maxW) desc = font().plainSubstrByWidth(desc, maxW, true);
                    g.drawString(font(), desc, x + 14, py + 28, UiKit.C_TXT_DIM, false);
                    cardY.add(py);
                }
                y += CARD_H + CARD_GAP;
            }
            int contentH = cardContentH();
                UiKit.scrollbar(g, x + w - 3, sTop, sBot - sTop, contentH, innerScrollY_card, false);
        }
    }

    /** 用户预设滚动区渲染（与卡片区同构，每行带 ✕ 删除） */
    private void drawUserRegion(GuiGraphics g, int mx, int my, int top, int bottom) {
        int sTop = top, sBot = bottom;
        int y = sTop;
        if (userPresets.isEmpty()) {
            int ry = y - innerScrollY_user;
            if (ry < sBot && ry + ROW_H > sTop)
                g.drawString(font(), "（暂无 · 用左下「保存」按钮存为预设）",
                    x + 12, ry + (ROW_H - font().lineHeight) / 2, C_TEXT_DIM);
        } else {
            for (UserPresetsStore.UserPreset p : userPresets) {
                int py = y - innerScrollY_user;
                if (py < sBot && py + CARD_H > sTop) {
                    boolean hover = mx >= x && mx <= x + w && my >= py && my <= py + CARD_H;
                    UiKit.roundRect(g, x + 4, py, x + w - 4, py + CARD_H, 4,
                            hover ? UiKit.C_CARD_HOVER : UiKit.C_CARD);
                    g.fill(x + 4, py + 6, x + 6, py + CARD_H - 6, UiKit.C_TEAL);
                    g.drawString(font(), p.name(), x + 14, py + 10, UiKit.C_TXT, false);
                    String meta = "（" + p.values().size() + " 项参数）";
                    g.drawString(font(), meta, x + 14, py + 28, UiKit.C_TXT_DIM, false);
                    int delX = x + w - 18;
                    boolean delHover = mx >= delX - 4 && mx <= delX + 14 && my >= py && my <= py + CARD_H;
                    g.drawString(font(), "✕", delX, py + (CARD_H - font().lineHeight) / 2,
                        delHover ? 0xFFFF7777 : UiKit.C_TXT_DIM, false);
                    userCardY.add(py); userCardId.add(p.id());
                }
                y += CARD_H + CARD_GAP;
            }
            int contentH = userContentH();
                UiKit.scrollbar(g, x + w - 3, sTop, sBot - sTop, contentH, innerScrollY_user, false);
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
        // 用户预设卡片点击（✕ 删除，其余加载）
        if (!userCardY.isEmpty()) {
            int delX = x + w - 18;
            for (int i = 0; i < userCardY.size(); i++) {
                if (hit(x, userCardY.get(i), w, CARD_H, mx, my)) {
                    String id = userCardId.get(i);
                    if (mx >= delX - 4 && mx <= delX + 14) {
                        UserPresetsStore.getInstance().delete(id);
                        refreshUserPresets();
                        playClick();
                    } else {
                        UserPresetsStore.UserPreset p = userPresets.stream()
                            .filter(q -> q.id().equals(id)).findFirst().orElse(null);
                        if (p != null) {
                            onApplyUser.accept(p);
                            playClick();
                        }
                    }
                    return true;
                }
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
        // 用户预设区：内部滚动
        if (my >= pinnedUserScrollTopY() && my < pinnedUserScrollBotY()) {
            int maxScroll = Math.max(0, userContentH() - userScrollH);
            if (maxScroll <= 0) return false;
            innerScrollY_user = Clamp(innerScrollY_user - (int)(delta * 20), 0, maxScroll);
            return true;
        }
        // 卡片区：内部滚动
        if (my >= pinnedCardScrollTopY() && my < pinnedCardScrollBotY()) {
            int maxScroll = Math.max(0, cardContentH() - cardScrollH);
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