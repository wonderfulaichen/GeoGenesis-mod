package com.geogenesis.client.preview;

/**
 * 左侧导航栏的<b>纯几何</b>（★ v71，零 MC 依赖、无状态）。
 *
 * <h2>它存在的理由（用户报的 bug）</h2>
 * <blockquote>
 *   「先修小窗口和高 GUI Scale 下页签被截断：1080p、Scale 4 时只能看到 2/11 个页签。」
 * </blockquote>
 *
 * <p>1080p + GUI Scale 4 ⇒ 逻辑尺寸只有 <b>480×270</b>。旧代码把头部位置写成
 * {@code clamp(比例, 硬下界, 上限)}，三个下界分别是 62 / 72 / 110：</p>
 * <pre>
 *   sepY      = clamp(h*0.115, 62, 90)    → 62   （按行高顺排只需 ~30）
 *   groupY    = clamp(h*0.128, 72, 100)   → 72
 *   navTabTop = clamp(h*0.17, 110, 152)   → 110
 *   bottomLimit ≈ 158
 *   ⇒ 页签可用高度 = 48px，每页签 22px ⇒ <b>只画 2 个就 break</b>
 * </pre>
 * <p>注意 {@code break} 不是"滚动"，是<b>直接不画</b> —— 后 9 个页签既看不见也点不到。</p>
 *
 * <h2>修法</h2>
 * <ol>
 *   <li><b>回收死区</b>：旧 {@code navMiniTopY()} 让出 30px 给"状态行 + 进度条"，
 *       而那块内容更早已移到右下角（v34）⇒ 纯空洞。现在下界 = 底部按钮行上方
 *       {@link #BOTTOM_GAP}。</li>
 *   <li><b>头部按行高顺排</b>，不再有 62/72/110 这类与高度无关的硬下界。</li>
 *   <li><b>自适应</b>：先压行高（下限 {@link #TAB_H_MIN}），仍放不下则转两列网格，
 *       对外报告 {@link #compact}，由调用方改用两字简称。</li>
 * </ol>
 *
 * <p>守门探针：{@code gradlew runNavRailLayoutProbe}。</p>
 */
public final class NavRailMetrics {

    /** 单页签最小高度：字体行高 9 + 上下留白 4 + 分隔 2。 */
    public static final int TAB_H_MIN = 15;
    /** 空间充裕时的合理行高下限（与旧宽屏观感一致）。 */
    public static final int TAB_H_IDEAL_MIN = 22;
    /** 单页签理想高度上限（与旧宽屏观感一致）。 */
    public static final int TAB_H_MAX = 32;
    /** 页签区下界与底部按钮行之间的间距。 */
    public static final int BOTTOM_GAP = 8;
    /** 导航栏左右内缩（与旧值一致：x = navX + 8，宽 = navW - 16）。 */
    public static final int INSET = 8;
    /** 两列之间的间隙。 */
    public static final int COL_GAP = 4;

    public final int brandY, seedY, sepY, groupY, tabTop, tabH;
    public final int cols, rows, colW;
    /** 是否两列 + 简称（窄高窗口）。 */
    public final boolean compact;
    /** 页签区下界（不含）。 */
    public final int bottomLimit;
    public final int tabCount;
    public final int navW;

    private NavRailMetrics(int height, int navW, int tabCount) {
        this.navW = navW;
        this.tabCount = tabCount;

        // ---- 头部：按行高顺排 ----
        this.brandY = clamp((int) (height * 0.028), 6, 20);
        this.seedY = brandY + 11;
        this.sepY = seedY + 15;
        this.groupY = sepY + 3;
        this.tabTop = groupY + 13;

        this.bottomLimit = bottomLimitFor(height);
        int avail = Math.max(0, bottomLimit - tabTop);

        // 理想行高下限取 22（宽屏观感与旧版一致）；放不下时才允许压到 15。
        int idealH = clamp((int) (height * 0.038), TAB_H_IDEAL_MIN, TAB_H_MAX);

        // ---- 依次试 1 / 2 / 3 列：满足"每页签 ≥ TAB_H_MIN"的第一种即采用 ----
        //
        //   480x270（1080p@4x）→ 1 列放不下 → 2 列 × 6 行 × 22 = 132 ≤ 137 ✓
        //   320x200（连 2 列都放不下）→ 3 列 × 4 行 × 15 = 60 ≤ 68 ✓
        int useCols = 0, useRows = 0, useH = 0;
        for (int c = 1; c <= 3; c++) {
            int r = (tabCount + c - 1) / c;
            int hh = r > 0 ? Math.min(idealH, avail / r) : idealH;
            if (hh >= TAB_H_MIN) {
                useCols = c; useRows = r; useH = hh;
                break;
            }
        }
        if (useCols == 0) {
            // ---- 极端尺寸（逻辑高 <150）：三列 + 压缩行高。
            //   宁可页签变矮，也<b>绝不</b>再出现"后几个看不见也点不到"。
            useCols = 3;
            useRows = (tabCount + 2) / 3;
            useH = Math.max(9, useRows > 0 ? avail / useRows : 9);
        }
        this.cols = useCols;
        this.rows = useRows;
        this.tabH = useH;
        this.compact = cols > 1;

        this.colW = cols <= 1
                ? navW - INSET * 2
                : Math.max(34, (navW - INSET * 2 - COL_GAP * (cols - 1)) / cols);
    }

    public static NavRailMetrics compute(int height, int navW, int tabCount) {
        return new NavRailMetrics(height, navW, Math.max(0, tabCount));
    }

    /**
     * 页签区下界 = 底部三行按钮（各 20px + 3px 间距 + 10px 底距）上方 {@link #BOTTOM_GAP}。
     *
     * <p>旧实现还在这之上让出 30px 给"状态行 + 进度条"，但 v34 已把那块移到右下角
     * ⇒ 30px 是纯空洞，正是 270 高窗口里只剩 2 个页签的直接原因。</p>
     */
    public static int bottomLimitFor(int height) {
        int layerY = height - 10 - 20;      // 第三行按钮顶
        int row1Y = layerY - 3 - 20 - 3 - 20;  // 第一行按钮顶
        return row1Y - BOTTOM_GAP;
    }

    /**
     * 第 {@code i} 个页签的矩形 {@code {x, y, w, h}}（坐标已含 {@code navX}）。
     *
     * @return 该页签放不下时返回 {@code null}
     */
    public int[] rect(int i, int navX) {
        if (i < 0 || i >= tabCount) return null;
        int c = i % cols;
        int r = i / cols;
        int x = navX + INSET + c * (colW + COL_GAP);
        int y = tabTop + r * tabH;
        if (y + tabH > bottomLimit + 1) return null;
        return new int[]{x, y, colW, tabH};
    }

    private static int clamp(int v, int lo, int hi) {
        return v < lo ? lo : Math.min(v, hi);
    }

    /**
     * 文字左内缩（与 {@link #textW(int)} 成对，render 与探针共用）。
     *
     * <p>三列只出现在逻辑高 &lt;150 的极端窗口，列宽仅 ~35px ⇒ 内缩收到 4px，
     * 否则「旧参数」（27px）会被裁成「旧参…」。</p>
     */
    public int textInset() {
        return cols >= 3 ? 4 : 7;
    }

    /** 标签可用文字宽度（超出由 {@code UiKit.clip} 加省略号）。 */
    public int textW(int colW) {
        return colW - (cols >= 3 ? 6 : 10);
    }
}