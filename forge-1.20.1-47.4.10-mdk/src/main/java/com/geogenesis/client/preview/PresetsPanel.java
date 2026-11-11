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
 * <p>★ v72：<b>单滚轮模型</b> —— 整页顺序排布、按内容全高展开，
 * 滚轮只归主屏的页面滚动。</p>
 *
 * <pre>
 *  种子: [EditBox] [↻] [★]
 *  ▾ 收藏种子            ← MixerPanel（可折叠）
 *    ▶ fav1  ✕
 *  默认地形预设
 *    群岛世界
 *  我的预设
 *    …
 * </pre>
 *
 * <h2>为何删掉三个内层滚动</h2>
 * <p>旧实现是「页面自己滚 + 里面三块各自滚」（收藏 / 卡片 / 用户预设，各自 pinned
 * 坐标 + {@code innerScrollY_*} + 独立滚动条）。指针落在哪块上、滚轮该动谁，
 * 全靠 y 区间路由 ⇒ 用户：「不要上下滚轮页里面再加上下滚轮的控件，非常容易误操作。」</p>
 *
 * <p>副作用：内滚偏移与固定坐标并存，正是上一轮「点 A 应用 B」的同源病。
 * 现在只有一份坐标（顺序累加），画哪儿就点哪儿。</p>
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
    /**
     * 与 {@link #cardY} <b>一一对应</b>的预设引用（★ v70）。
     *
     * <h2>修的是什么</h2>
     * <p>用户实机：「预设点 A 应用 B」。</p>
     * <p>旧代码 {@code cardY} 只记录<b>可见行</b>的 y，点击时却用列表下标去取
     * {@code presets.get(i)} —— 滚动后两者错位。</p>
     * <p>算例：内置预设 5 个、卡高 60+10、可视 230 ⇒ 一屏放 3 张；
     * 往下滚一格后 {@code cardY.get(0)} 是第 2 张卡的 y，
     * 但按下标取到的是 {@code presets.get(0)} ⇒ 点第 1 张应用第 2 个预设。</p>
     * <p>同文件的收藏行（{@code favRowSeed}）与用户预设（{@code userCardId}）
     * 本来都记了 id，只有卡片区漏了 —— 典型"绘制一套、命中另一套"。</p>
     */
    private final List<Preset> cardRef = new ArrayList<>();
    /** 收藏种子折叠面板 */
    private final MixerPanel favSection = new MixerPanel("收藏种子");

    /** 用户自定义预设（从 user_presets.json 加载，可加载/删除） */
    private final List<UserPresetsStore.UserPreset> userPresets = new ArrayList<>();
    private final List<Integer> userCardY = new ArrayList<>();
    private final List<String> userCardId = new ArrayList<>();

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
        // ★ v70：登记放到 ensureWidgetsRegistered（屏幕每次 init 都会调），
        //   构造器里不再直接 add —— 否则"构造一次 = 登记一次"，resize 后就丢。
        refreshBtn = Button.builder(C("↻"), b -> onRefresh.run())
            .pos(0, 0).size(SEED_ROW_H, SEED_ROW_H).build();

        favBtn = Button.builder(Star(sm.isFavorite(getSeed.getAsLong())), b -> toggleFavorite())
            .pos(0, 0).size(SEED_ROW_H, SEED_ROW_H).build();

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

    /**
     * <b>把三个 widget 重新登记到屏幕</b>（★ v70，修"resize/折叠一次后种子框消失"）。
     *
     * <h2>为什么需要显式方法</h2>
     * <p>MC 的 {@code Screen.resize} 会重跑 {@code init()}，而 {@code init()} 开头
     * {@code clearWidgets()} 会清掉全部 widget；但 {@code PresetsPanel} 只在
     * {@code presetsPanel == null} 时构造 ⇒ 第二次进 init 时面板非 null，
     * 构造器里的三次 {@code addWidget} 再也不会执行
     * ⇒ 种子输入框 / ↻ / ★ 从画面上消失（实机正是这个现象）。</p>
     *
     * <p>本方法把"登记"与"构造"解耦：屏幕每次 init 都无条件调一次即可。
     * 重复调用安全 —— {@code Screen.addRenderableWidget} 只是往列表里加。</p>
     */
    public void ensureWidgetsRegistered() {
        addWidget.accept(seedBox);
        addWidget.accept(refreshBtn);
        addWidget.accept(favBtn);
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

    /**
     * ★ v72：内容高度<b>顺序累加</b>，不再按视口均摊。
     *
     * <p>旧实现把可用高度按 55% / 45% 摊给两个滚动区（{@code setViewportHeight} +
     * {@code recomputeScrollHeights}），滚动区高度随窗口浮动 ⇒ 又一层"同一布局两套推导"。
     * 现在没有视口概念：列表有多少内容就占多高，超高由主屏页面滚动兜住
     * （那也正是唯一的滚轮目标）。</p>
     */
    @Override
    public int getHeight() {
        return 2 + SEED_ROW_H + GAP_M
             + favSection.getFullHeight() + GAP_M
             + 16 + cardContentH() + GAP_M
             + 16 + Math.max(ROW_H, userContentH()) + GAP_M + 12;
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
     * 收藏区高度 = 内容高度（★ v72：删掉 160px 视口上限）。
     *
     * <p>收藏是用户自己加的种子，条数天然有限；上限若保留，就会重新引入
     * "视口高度 ≠ 内容高度"，而那正是内层滚轮存在的前提。</p>
     */
    private int favScrollH() {
        if (favSection.isCollapsed()) return 0;
        int content = favContentH();
        return content <= 0 ? ROW_H : content;     // ★ v72：不再截断到 160
    }
    private int pinnedCardTitleY()          { return pinnedFavTopY() + favSection.getFullHeight() + GAP_M; }
    private int pinnedCardScrollTopY()      { return pinnedCardTitleY() + 16; }  // MixerPanel.TITLE_H=16
    private int pinnedCardScrollBotY()      { return pinnedCardScrollTopY() + cardContentH(); }
    private int pinnedUserTitleY()          { return pinnedCardScrollBotY() + GAP_M; }
    private int pinnedUserScrollTopY()      { return pinnedUserTitleY() + 16; }
    private int pinnedUserScrollBotY()      { return pinnedUserScrollTopY() + Math.max(ROW_H, userContentH()); }

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
        favRowY.clear(); favRowSeed.clear(); cardY.clear(); cardRef.clear();
        userCardY.clear(); userCardId.clear();
        if (!favSection.isCollapsed()) {
            int favTop = pinnedFavScrollTopY();
            int favBot = pinnedFavScrollBotY();
            // ★ v72：区域高度＝内容高度，不需要 scissor（页面滚动会整体移动它）
            UiKit.roundRect(g, x + 2, favTop, x + w - 2, favBot, UiKit.RADIUS_CARD, 0xFF1A1A1A);
            drawScrollRegion(g, mx, my, favTop, favBot, curSeed, sm, true);
            favSection.renderTooltip(g, mx, my);
        }

        // —— 卡片标题 ——
        int cty = pinnedCardTitleY();
        g.drawString(font(), "默认地形预设（点击应用，将覆盖全部参数）", x, cty, C_TEXT_DIM);

        // ============ 卡片区（顺序排布，全高）============
        int cardTop = pinnedCardScrollTopY();
        int cardBot = pinnedCardScrollBotY();
        UiKit.roundRect(g, x + 2, cardTop, x + w - 2, cardBot, UiKit.RADIUS_CARD, 0xFF1A1A1A);
        drawScrollRegion(g, mx, my, cardTop, cardBot, curSeed, sm, false);

        // —— 用户预设标题 ——
        int uty = pinnedUserTitleY();
        g.drawString(font(), "我的预设（点击加载 · ✕ 删除）", x, uty, C_TEXT_DIM);

        // ============ 用户自定义预设（顺序排布，全高）============
        int userTop = pinnedUserScrollTopY();
        int userBot = pinnedUserScrollBotY();
        UiKit.roundRect(g, x + 2, userTop, x + w - 2, userBot, UiKit.RADIUS_CARD, 0xFF1A1A1A);
        drawUserRegion(g, mx, my, userTop, userBot);
    }

    private void drawScrollRegion(GuiGraphics g, int mx, int my,
                                   int top, int bottom, long curSeed, SeedManager sm, boolean isFav) {
        int sTop = top, sBot = bottom;
        // ★ v72：不再有内滚偏移 —— 区域内所有行都按顺序画满（高度本身就等于内容高）。
        if (isFav) {
            int y = sTop;
            List<SeedManager.SeedEntry> favs = sm.getFavorites();
            if (favs.isEmpty()) {
                int ry = y;
                g.drawString(font(), "（暂无收藏 · 点 ★ 收藏当前种子）", x + 12, ry + (ROW_H - font().lineHeight) / 2, C_TEXT_DIM);
            } else {
                for (SeedManager.SeedEntry e : favs) {
                    int ry = y;
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
                    y += ROW_H + ROW_GAP;
                }
            }
        } else {
            int y = sTop;
            for (Preset p : presets) {
                int py = y;
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
                cardRef.add(p);   // ★ v70：记录 y 属于哪个预设（滚动的唯一修法）
                y += CARD_H + CARD_GAP;
            }
        }
    }

    /** 用户预设区渲染（与卡片区同构，每行带 ✕ 删除）。 */
    private void drawUserRegion(GuiGraphics g, int mx, int my, int top, int bottom) {
        int sTop = top;
        int y = sTop;
        if (userPresets.isEmpty()) {
            int ry = y;
            g.drawString(font(), "（暂无 · 用左下「保存」按钮存为预设）",
                x + 12, ry + (ROW_H - font().lineHeight) / 2, C_TEXT_DIM);
        } else {
            for (UserPresetsStore.UserPreset p : userPresets) {
                int py = y;
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
                y += CARD_H + CARD_GAP;
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
        //
        // ★★ v70【修"预设点 A 应用 B"】★★
        //   这里原来用 {@code presets.get(i)} —— i 是<b>可见行</b>的下标，
        //   滚动后与 presets 的下标不再对应（见 {@link #cardRef} 的算例）。
        //   现在按 cardRef 取"这个 y 属于哪个预设"。
        for (int i = 0; i < cardY.size(); i++) {
            if (hit(x, cardY.get(i), w, CARD_H, mx, my)) {
                Preset target = (i < cardRef.size()) ? cardRef.get(i) : null;
                if (target == null) return true;   // 防御：绝不用错误的下标去应用
                onApply.accept(target);
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
        // ★ v72：三块内层滚动已删除 —— 一个页面只能有一个滚轮目标，交给主屏页面滚动。
        //   旧实现按光标 y 在收藏/卡片/用户预设三块之间路由，是最容易被误操作的一处。
        return false;
    }

    @Override public boolean mouseReleased(double mx, double my, int btn) { return false; }
    @Override public boolean mouseDragged(double mx, double my, int btn, double dx, double dy) { return false; }
}