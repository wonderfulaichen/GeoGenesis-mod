package com.geogenesis.worldgen.terrain.preview;

import com.geogenesis.client.preview.NavRailMetrics;
import com.geogenesis.config.PanelLayout;

import java.util.List;
import java.util.Locale;

/**
 * <b>左侧导航栏页签布局守门探针</b>（★ v71）。
 *
 * <h2>它守的是用户实机报的 bug</h2>
 * <blockquote>
 *   「先修小窗口和高 GUI Scale 下页签被截断：1080p、Scale 4 时只能看到 2/11 个页签。」
 * </blockquote>
 *
 * <p>1080p + GUI Scale 4 ⇒ 逻辑尺寸 480×270，导航栏宽 130。
 * 旧代码在 {@code ty + navTabH > bottomLimit} 处 {@code break}（不是滚动，是<b>不画</b>）
 * ⇒ 11 个页签只画出 2 个，其余既看不见也点不到。</p>
 *
 * <h2>判据</h2>
 * <ol>
 *   <li><b>[1] 复现点</b>：480×270 / navW=130 / 11 页签 ⇒ 11 个矩形<b>全部</b>非 null；</li>
 *   <li><b>[2] 宽屏不退化</b>：960×540 ⇒ 单列、行高在 [22,32]、全部可见；</li>
 *   <li><b>[3] 几何自洽</b>：任何尺寸下矩形互不重叠、纵向落在
 *       [{@code tabTop}, {@code bottomLimit}] 内、横向不越出 navW；</li>
 *   <li><b>[4] 标签塞得下</b>：compact 用两字简称、非 compact 用全称，
 *       估宽（CJK 9px / ASCII 6px）都必须 ≤ 列宽-14；</li>
 *   <li><b>[5] 极端尺寸</b>：320×200、2560×1440、480×270、854×480 全部可见。</li>
 * </ol>
 *
 * <p>用法：{@code gradlew runNavRailLayoutProbe}</p>
 */
public final class NavRailLayoutProbe {

    private static int fails = 0;

    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        List<PanelLayout.Tab> all = PanelLayout.visibleTabs(true);   // 含 legacy ⇒ 最坏 12 个
        System.out.printf("页签数：visibleTabs(false)=%d，visibleTabs(true)=%d%n",
                PanelLayout.visibleTabs(false).size(), all.size());

        // ---- [1] 用户报的那个点 ----
        System.out.println("\n[1] 480x270（1080p + Scale 4）/ navW=130 / 11 页签");
        check(270, 130, 11, "1080p@4x");

        // ---- [2] 宽屏不退化 ----
        System.out.println("\n[2] 960x540（1080p + Scale 2）/ navW=125");
        NavRailMetrics m = NavRailMetrics.compute(540, 125, 11);
        System.out.printf("    cols=%d rows=%d tabH=%d compact=%s tabTop=%d bottomLimit=%d%n",
                m.cols, m.rows, m.tabH, m.compact, m.tabTop, m.bottomLimit);
        if (m.cols != 1) fail("[2] 540 高仍单列应放得下，却转了两列");
        if (m.tabH < 22 || m.tabH > NavRailMetrics.TAB_H_MAX) fail("[2] 行高越界：" + m.tabH);
        check(540, 125, 11, "1080p@2x");

        // ---- [3]+[4] 扫描一批尺寸 ----
        System.out.println("\n[3][4] 几何自洽 + 标签可容纳（扫描）");
        int[][] sizes = {
                {270, 130}, {480, 130}, {540, 125}, {720, 160}, {1080, 196},
                {200, 130}, {1440, 196}, {360, 130}
        };
        for (int[] s : sizes) {
            for (int n : new int[]{11, 12}) {
                geometry("h=" + s[0] + " navW=" + s[1] + " n=" + n, s[0], s[1], n, all);
            }
        }

        // ---- [5] 极端尺寸全部可见 ----
        System.out.println("\n[5] 极端尺寸");
        check(200, 130, 11, "320x200 逻辑");
        check(1440, 196, 12, "2560x1440");
        check(270, 130, 12, "480x270 含 legacy");
        check(480, 125, 11, "854x480");

        System.out.println();
        System.out.println(fails == 0 ? "PASS" : "FAIL (" + fails + " 项)");
        System.exit(fails == 0 ? 0 : 1);
    }

    /** 断言某尺寸下 n 个页签全部有矩形。 */
    private static void check(int h, int navW, int n, String tag) {
        NavRailMetrics m = NavRailMetrics.compute(h, navW, n);
        int visible = 0;
        for (int i = 0; i < n; i++) {
            if (m.rect(i, 0) == null) {
                System.out.printf("    FAIL %s: 第 %d 个页签无矩形（cols=%d rows=%d tabH=%d）%n",
                        tag, i, m.cols, m.rows, m.tabH);
                fails++;
                return;
            }
            visible++;
        }
        System.out.printf("    OK  %s：%d/%d 可见（cols=%d tabH=%d compact=%s）%n",
                tag, visible, n, m.cols, m.tabH, m.compact);
    }

    /** 几何自洽：不重叠、在界内、不越宽；标签估宽可容纳。 */
    private static void geometry(String tag, int h, int navW, int n, List<PanelLayout.Tab> tabs) {
        NavRailMetrics m = NavRailMetrics.compute(h, navW, n);
        int[] xs = new int[n], ys = new int[n], ws = new int[n], hs = new int[n];
        for (int i = 0; i < n; i++) {
            int[] r = m.rect(i, 0);
            if (r == null) { fail(tag + ": 第 " + i + " 个页签无矩形"); return; }
            xs[i] = r[0]; ys[i] = r[1]; ws[i] = r[2]; hs[i] = r[3];
            if (ys[i] < m.tabTop || ys[i] + hs[i] > m.bottomLimit + 1) {
                fail(tag + ": 第 " + i + " 个纵向越界 y=" + ys[i] + " h=" + hs[i]);
                return;
            }
            if (xs[i] < 0 || xs[i] + ws[i] > navW) {
                fail(tag + ": 第 " + i + " 个横向越界 x=" + xs[i] + " w=" + ws[i] + " navW=" + navW);
                return;
            }
            String label = m.compact ? tabs.get(i).shortLabel() : tabs.get(i).label();
            if (estWidth(label) > m.textW(ws[i])) {
                fail(tag + ": 标签「" + label + "」估宽 " + estWidth(label)
                        + " 超出可用文字宽 " + m.textW(ws[i]));
                return;
            }
        }
        for (int i = 0; i < n; i++) {
            for (int j = i + 1; j < n; j++) {
                boolean sep = xs[i] + ws[i] <= xs[j] || xs[j] + ws[j] <= xs[i]
                        || ys[i] + hs[i] <= ys[j] || ys[j] + hs[j] <= ys[i];
                if (!sep) {
                    fail(tag + ": 页签 " + i + " 与 " + j + " 重叠");
                    return;
                }
            }
        }
        System.out.printf("    OK  %s：cols=%d rows=%d tabH=%d colW=%d compact=%s%n",
                tag, m.cols, m.rows, m.tabH, m.colW, m.compact);
    }

    /** MC 默认字体的近似宽度：CJK/全角 9px，ASCII 6px。 */
    private static int estWidth(String s) {
        int w = 0;
        for (int i = 0; i < s.length(); i++) {
            w += s.charAt(i) < 0x2E80 ? 6 : 9;
        }
        return w;
    }

    private static void fail(String msg) {
        System.out.println("    FAIL " + msg);
        fails++;
    }

    private NavRailLayoutProbe() { }
}