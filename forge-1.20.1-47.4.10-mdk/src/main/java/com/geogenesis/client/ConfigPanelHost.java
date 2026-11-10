package com.geogenesis.client;

import com.geogenesis.client.preview.ConfigPanel;
import com.geogenesis.client.preview.DomainPanel;
import com.geogenesis.client.preview.UiKit;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import java.util.List;

/**
 * <b>配置窗宿主</b> —— 左侧悬浮配置窗的<b>唯一</b>所有者（★ 2026-11-09 重构）。
 *
 * <h2>★ 为什么要有这个类（用户：「你这GUI还是各自独立，互相都看不到对方，全在重叠打架」）</h2>
 * <p>重构前，配置窗的<b>同一块区域</b>被三处代码分别管：</p>
 * <pre>
 *   ConfigScreen.renderPanelOverlay()   画底板
 *   ConfigScreen.render()               画页签（第 3 层）
 *   ConfigScreen.render()               画内容（scissor 裁切，第 3 层）
 *   ConfigScreen.renderPanelWidgets()   又画一遍按钮（因为被预览盖住了）
 *   ConfigScreen.hitTestTab()           页签命中（又一套坐标）
 * </pre>
 * <p>⇒ 画在一处、命中在另一处；谁也不知道别人占了哪 ⇒ 重叠打架是<b>必然</b>。</p>
 *
 * <h2>本类把整块收进一个对象</h2>
 * <p>底板、页签、页签命中、内容区裁切、按钮 —— <b>全部用同一套矩形</b>，
 * 画与命中不可能分叉。外部只调 {@link #render} 与 {@link #hitTab}。</p>
 *
 * <h2>坐标契约（★ 唯一真相源）</h2>
 * <pre>
 *   overlayY, overlayH          底板矩形（深色半透明 + 1px 描边 + 标题行）
 *   panelX, panelW              左右边界（= 底板左右）
 *   headerY                     页签栏顶部（= overlayY + 22，让出标题行）
 *   tabLayout()                 每个页签的 {x, y, w} —— 换行算法只此一份
 *   tabBarBottom                页签栏底部（drawTabs 写，内容起点读）
 *   listTop                     内容区顶部 = tabBarBottom + 4
 *   listBottom                  内容区底部 = overlayY + overlayH - bottomBarH
 *   bottomBarH                  底部按钮区高度（0 = 无按钮）
 * </pre>
 * <p><b>任何</b>与配置窗有关的坐标都必须从这里取，不许在外部另算一套。</p>
 */
public final class ConfigPanelHost {

    // ---- 几何（唯一来源：绘制与命中共用）----
    /** 悬浮窗左边界。 */
    public int panelX;
    /** 悬浮窗宽度。 */
    public int panelW;
    /** 底板顶边 y。 */
    public int overlayY;
    /** 底板高度。 */
    public int overlayH;
    /** 页签栏顶部。 */
    public int headerY;
    /** 页签栏底部（流式换行后确定）。 */
    public int tabBarBottom;
    /** 内容区顶部。 */
    public int listTop;
    /** 内容区底部。 */
    public int listBottom;

    /**
     * 底部按钮区高度（0 = 无按钮）。
     *
     * <p>★ 必须由本类持有：内容区裁切底边（{@code listBottom}）与按钮区
     * 由<b>同一个</b>值决定。此前按钮在 {@code ConfigScreen.init()} 里按
     * {@code panelW >= 180} 决定存不存在，而裁切底边恒为
     * {@code cfgH - BOTTOM_BAR_H} ⇒ 窄面板时留下 52px 空白却无按钮。</p>
     */
    public int bottomBarH;

    /** 当前页签索引。 */
    public int tab;
    /** 页签标签。 */
    public List<String> tabLabels = List.of();
    /** 各页签对应的面板。 */
    public ConfigPanel[] panels;

    // ---- 主题色 ----
    /** 页签高度（与原 ConfigScreen.TAB_H 一致）。 */
    private static final int TAB_H = 24;
    private static final int TAB_PILL_R = 6;
    private static final int TAB_SEL_BG = 0xFF2A3340;
    private static final int TAB_SEL_HOVER = 0xFF39485A;
    private static final int TAB_PILL_BG = 0xFF1B2028;
    private static final int TAB_PILL_HOVER = 0xFF242C36;
    private static final int TAB_SEL_TOP = 0xFFFFD24A;
    private static final int C_TEXT = 0xFFFFFFFF;
    private static final int TAB_INACTIVE_TEXT = 0xFF8B96A5;

    private final Minecraft mc = Minecraft.getInstance();

    /**
     * <b>唯一的页签换行计算</b>（★ 2026-11-09）。
     *
     * <p>返回每个页签的 {x, y, w}（长度 = 页签数 × 3）。</p>
     *
     * <p>★ 之前 {@code drawTabs} / {@code hitTab} / {@code tabBarHeight}
     * <b>各写了一份</b>流式换行算法 —— 三份副本必然分叉，
     * 表现就是"页签画在一个位置、点判在另一个位置、内容起点又对不上"。</p>
     */
    private int[] tabLayout() {
        int n = tabLabels.size();
        int[] out = new int[n * 3];
        int cx = panelX + 6;
        int cy = headerY;
        int rowMaxX = panelX + panelW - 6;
        for (int i = 0; i < n; i++) {
            int tw = mc.font.width(tabLabels.get(i)) + 20;
            if (cx + tw > rowMaxX && cx > panelX + 6) {
                cx = panelX + 6;
                cy += TAB_H + 4;
            }
            out[i * 3] = cx;
            out[i * 3 + 1] = cy;
            out[i * 3 + 2] = tw;
            cx += tw + 4;
        }
        return out;
    }

    /**
     * 绘制整块配置窗（底板 + 页签 + 内容 + 按钮）。
     *
     * @param onDrawPanelButtons 回调：在内容之上补画按钮（因为按钮是 widget，
     *        已被 {@code super.render} 画过一次并被预览盖住，需要在此重画）
     */
    public void render(GuiGraphics g, int mx, int my, float pt,
                       Runnable onDrawPanelButtons) {
        drawOverlay(g);
        drawPanelTitle(g);
        drawTabsInternal(g, mx, my);
        drawContent(g, mx, my);
        drawPinneArea(g, mx, my);
        drawScrollbar(g);
        drawFooterHint(g);
        if (onDrawPanelButtons != null) onDrawPanelButtons.run();
    }

    /**
     * <b>固定区（不随滚动移动）</b>（★ v20）。
     *
     * <p>用户：「建议给示意图做一下常驻显示，不然按现在这样滚动就消失了」。
     * 有些页签（如「世界与尺度」）的可滚参数很多，而示意图是<b>理解参数意义</b>的关键
     * ⇒ 让它钉在内容区底部，参数滚动时它不动。</p>
     *
     * <h2>★ 坐标契约（★ v21：修"示意图画在错误位置"）</h2>
     * <pre>
     *   listBottom     = 内容区（滚动区）底边，<b>已扣除</b>固定区
     *   pinnedTop      = listBottom + 间隙        ← 固定区顶
     *   pinnedBottom   = overlayY + overlayH - 页脚
     * </pre>
     * <p>⚠ 此前 {@code listBottom} 由外部扣过一次、这里又扣一次
     * （{@code listBottom - bottomReserved()}）⇒ <b>双重扣除</b>，
     * 固定区被画到内容区上方，压在参数行上（实机截图正是这个）。</p>
     */
    public int pinnedHeight = 0;
    /** 固定区绘制回调：参数 (graphics, mouseX, mouseY, 区域顶 y)。 */
    public PinnedRenderer pinnedRenderer = null;

    public interface PinnedRenderer {
        void render(GuiGraphics g, int mx, int my, int topY);
    }

    /** 固定区顶部 y（★ 唯一来源：绘制、命中、裁切都用它）。 */
    public int pinnedTop() {
        return listBottom + 6;
    }

    private void drawPinneArea(GuiGraphics g, int mx, int my) {
        if (pinnedHeight <= 0 || pinnedRenderer == null) return;
        int top = pinnedTop();
        // 上方一条分隔线，明确"这块是固定的"
        g.fill(panelX + 8, top - 5, panelX + panelW - 8, top - 4, 0x18FFFFFF);
        pinnedRenderer.render(g, mx, my, top);
    }

    /** 固定区 + 间距共占的高度（外部在算 listBottom 时扣除）。 */
    public int bottomReserved() {
        return pinnedHeight > 0 ? pinnedHeight + 10 : 0;
    }

    /**
     * <b>滚动条</b>（★ v20）。
     *
     * <p>用户：「没有滚动条等设计，建议检查相关细节问题并修复」。
     * 参数超过一屏时，右侧画一条细滚动条 + 滑块，让用户知道
     * ① 还有内容没看到；② 当前在什么位置。</p>
     */
    private void drawScrollbar(GuiGraphics g) {
        if (contentHeight <= 0) return;
        int viewTop = listTop;
        // ★ v21：listBottom 已扣过固定区，这里不能再扣（否则滚动条高度算错）
        int viewBot = listBottom;
        int viewH = viewBot - viewTop;
        if (viewH <= 0 || contentHeight <= viewH) return;   // 不需要滚动条

        int barX = panelX + panelW - 5;
        UiKit.roundRect(g, barX, viewTop, barX + 3, viewBot, 2, 0x18FFFFFF);
        int barH = Math.max(18, (int) ((long) viewH * viewH / contentHeight));
        int maxScroll = contentHeight - viewH;
        int barY = viewTop + (int) ((long) scrollOffset * (viewH - barH) / Math.max(1, maxScroll));
        UiKit.roundRect(g, barX, barY, barX + 3, barY + barH, 2, 0x66FFFFFF);
    }

    /** 当前页内容总高（由外部每帧注入，用于滚动条）。 */
    public int contentHeight = 0;
    /** 当前滚动偏移（由外部每帧注入，用于滚动条）。 */
    public int scrollOffset = 0;

    /**
     * <b>面板底部说明行</b>（★ v7，对齐示意图底部那行小字）。
     *
     * <p>示意图内容面板底部有一行次要色提示（说明本页作用）。
     * 之前这里是空的，面板看着没"收口"。</p>
     */
    private void drawFooterHint(GuiGraphics g) {
        if (footerHint == null || footerHint.isEmpty()) return;
        int y = overlayY + overlayH - 14;
        g.drawString(mc.font, footerHint, panelX + 12, y, 0xFF6E6E6E, false);
    }

    /** 面板底部说明（由外部按当前页签注入，null = 不画）。 */
    public String footerHint;

    /**
     * <b>内容面板标题行</b>（★ v7，对齐示意图的「世界与尺度 — 8 项」）。
     *
     * <p>示意图里内容面板顶部有一行标题 + 条目数，下面是分组标签。
     * 之前这里什么都没有 —— 只有导航栏的品牌行，内容面板像一块空白底板。</p>
     */
    private void drawPanelTitle(GuiGraphics g) {
        if (titleText == null || titleText.isEmpty()) return;
        // ★ v11：折叠把手已移回导航栏品牌行，标题不再需要为它让位
        int tx = panelX + 12;
        int ty = overlayY + 10;
        g.drawString(mc.font, titleText, tx, ty, 0xFFF2F2F2, false);
        if (titleCount >= 0) {
            String cnt = "— " + titleCount + " 项";
            int cx = tx + mc.font.width(titleText) + 6;
            g.drawString(mc.font, cnt, cx, ty, 0xFF7E8A96, false);
        }
    }

    /** 内容面板标题（由外部按当前页签注入，null = 不画）。 */
    public String titleText;
    /** 标题右侧的条目数（<0 = 不画）。 */
    public int titleCount = -1;

    /**
     * 悬浮窗底板（★ v7：改走 UiKit 圆角卡片 + 极淡描边）。
     *
     * <p>旧版是 1px 硬边框 {@code 0xFF3A4250}，与新版"无描边、靠底色明度分节"
     * 的语言冲突（用户实测：面板像"框起来的表格"）。</p>
     *
     * <p>★ 2026-11-09 v6 起<p>不画</p>「GeoGenesis 地形配置」标题 ——
     * 左侧导航栏已有品牌行，这里再写一遍是重复（用户截图：左右各一个 GeoGenesis）。</p>
     */
    private void drawOverlay(GuiGraphics g) {
        int x = panelX, y = overlayY;
        int w = panelW, h = overlayH;
        UiKit.roundRect(g, x, y, x + w, y + h, UiKit.RADIUS_CARD, 0xE8141414);
        UiKit.roundRectOutline(g, x, y, x + w, y + h, UiKit.RADIUS_CARD, 0x1AFFFFFF);
    }

    /**
     * 是否由本类画页签栏（★ 2026-11-09 v6 布局）。
     *
     * <p>{@code false} = 页签由外部（左侧导航栏）画，本类只画<b>内容面板</b>
     * （顶栏 + 内容 + 按钮）。v6 布局默认 {@code false}。</p>
     *
     * <p>★ 为什么要这个开关：v6 把页签从"内容面板顶部横排"改成
     * "左侧导航栏竖排"。若不关掉本类的页签绘制，会画出两套页签
     * （与之前"两个图例"同类错误）。</p>
     */
    public boolean drawTabs = false;

    /**
     * 画页签栏：<b>按文字宽度流式换行</b>（强行等分必然超宽）。
     */
    private void drawTabsInternal(GuiGraphics g, int mx, int my) {
        if (!drawTabs) { tabBarBottom = headerY; return; }
        int[] lay = tabLayout();
        int n = tabLabels.size();
        int bottom = headerY;
        for (int i = 0; i < n; i++) {
            int cx = lay[i * 3], cy = lay[i * 3 + 1], tw = lay[i * 3 + 2];
            boolean on = tab == i;
            boolean hover = mx >= cx && mx <= cx + tw && my >= cy && my <= cy + TAB_H;
            int bg = on ? (hover ? TAB_SEL_HOVER : TAB_SEL_BG)
                        : (hover ? TAB_PILL_HOVER : TAB_PILL_BG);
            drawRoundedPill(g, cx, cy, tw, TAB_H, TAB_PILL_R, bg, on);
            if (on) g.fill(cx + TAB_PILL_R, cy, cx + tw - TAB_PILL_R, cy + 2, TAB_SEL_TOP);
            g.drawCenteredString(mc.font, tabLabels.get(i), cx + tw / 2,
                    cy + (TAB_H - mc.font.lineHeight) / 2 + 1,
                    on ? C_TEXT : TAB_INACTIVE_TEXT);
            bottom = Math.max(bottom, cy + TAB_H);
        }
        tabBarBottom = bottom;
    }

    /** 内容区（带裁切）。 */
    private void drawContent(GuiGraphics g, int mx, int my) {
        g.enableScissor(panelX, listTop, panelX + panelW, listBottom);
        if (panels != null && tab < panels.length) {
            panels[tab].render(g, mx, my);
        }
        g.disableScissor();
    }

    /**
     * 页签命中（<b>与绘制同一套坐标</b> —— 由 {@link #drawTabs} 算出的位置）。
     *
     * @return 命中的页签索引；-1 = 未命中
     */
    public int hitTab(double mx, double my) {
        int[] lay = tabLayout();
        int n = tabLabels.size();
        for (int i = 0; i < n; i++) {
            int cx = lay[i * 3], cy = lay[i * 3 + 1], tw = lay[i * 3 + 2];
            if (mx >= cx && mx <= cx + tw && my >= cy && my <= cy + TAB_H) {
                return i;
            }
        }
        return -1;
    }

    /** 页签栏总高度（含换行）—— 供内容区起点用。 */
    public int tabBarHeight() {
        int[] lay = tabLayout();
        int n = tabLabels.size();
        if (n == 0) return TAB_H;
        int bottom = headerY;
        for (int i = 0; i < n; i++) {
            bottom = Math.max(bottom, lay[i * 3 + 1] + TAB_H);
        }
        return bottom - headerY;
    }

    /** 圆角药丸（页签底）。 */
    private static void drawRoundedPill(GuiGraphics g, int x, int y, int w, int h,
                                         int r, int color, boolean flatBottom) {
        g.fill(x + r, y, x + w - r, y + h, color);
        g.fill(x, y + r, x + r, y + h - r, color);
        g.fill(x + w - r, y + r, x + w, y + h - r, color);
        if (!flatBottom) {
            g.fill(x + r, y + h - r, x + w - r, y + h, color);
            g.fill(x, y + h - 2 * r, x + r, y + h - r, color);
            g.fill(x + w - r, y + h - 2 * r, x + w, y + h - r, color);
        }
    }
}
