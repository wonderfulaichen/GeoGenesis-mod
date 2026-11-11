package com.geogenesis.worldgen.terrain.preview;

import com.geogenesis.client.preview.ViewTransition;

import java.util.Locale;

/**
 * <b>平面 ⇄ 沙盘切换过渡的守门探针</b>（★ v48，零 MC 依赖）。
 *
 * <h2>为什么要它</h2>
 * <p>本项目吃过教训：阈值型探针全 PASS、用户仍看到明显问题。
 * 但那说的是"观感"类问题（如细线、平板）。过渡动画的<b>数学不变量</b>
 * 是另一回事 —— 它们是可判定的，而且一旦写错（例如两张图 alpha 不互补）
 * 观感必然是错的。所以这里守不变量，观感留给实机。</p>
 *
 * <h2>判据（全部对照 {@code YardSiteScreen} 第 61/386-387/404/415-421 行）</h2>
 * <ol>
 *   <li><b>[1]</b> 时长 = 620ms；</li>
 *   <li><b>[2]</b> 进度边界与单调性：0 起点、1 终点、区间内严格递增；</li>
 *   <li><b>[3]</b> 交叉溶解不变量：{@code oldAlpha + newAlpha == 1}（全程）；</li>
 *   <li><b>[4]</b> 高度两端与单调性：旧 1→0.45、新 0.45→1，方向相反；</li>
 *   <li><b>[5]</b> 镜头推近 0.84→1.0；</li>
 *   <li><b>[6]</b> 无 NaN / 越界（含极端 now：远早于起点、远晚于终点）。</li>
 * </ol>
 *
 * <p>用法：{@code gradlew runViewTransitionProbe}</p>
 */
public final class ViewTransitionProbe {

    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        boolean pass = true;

        // ---- [1] 时长 ----
        System.out.println("[1] 过渡时长");
        System.out.printf("    DURATION_MS = %d%n", ViewTransition.DURATION_MS);
        if (ViewTransition.DURATION_MS != 620L) {
            System.out.println("    FAIL: 参考项目是 620ms");
            pass = false;
        } else {
            System.out.println("    OK（与参考项目一致）");
        }

        // ---- [2] 进度边界与单调性 ----
        System.out.println("\n[2] 进度 progress()");
        float pStart = ViewTransition.progress(1000L, 1620L);
        float pEnd = ViewTransition.progress(1620L, 1620L);
        float pNone = ViewTransition.progress(9999L, 0L);
        float pEarly = ViewTransition.progress(0L, 1620L);
        float pLate = ViewTransition.progress(99999L, 1620L);
        System.out.printf("    起点=%.3f 终点=%.3f 无过渡=%.3f 过早=%.3f 过晚=%.3f%n",
                pStart, pEnd, pNone, pEarly, pLate);
        boolean mono = true;
        float prev = -1f;
        for (long t = 1000L; t <= 1620L; t += 20L) {
            float p = ViewTransition.progress(t, 1620L);
            if (p < prev - 1e-6f) mono = false;
            prev = p;
        }
        if (Math.abs(pStart) > 1e-6f || Math.abs(pEnd - 1f) > 1e-6f
                || Math.abs(pNone - 1f) > 1e-6f
                || Math.abs(pEarly) > 1e-6f || Math.abs(pLate - 1f) > 1e-6f || !mono) {
            System.out.println("    FAIL: 边界或单调性不对（过早应夹到 0、过晚应夹到 1）");
            pass = false;
        } else {
            System.out.println("    OK（边界夹紧 + 单调递增）");
        }

        // ---- [3] 交叉溶解不变量 ----
        System.out.println("\n[3] 交叉溶解：oldAlpha + newAlpha 必须恒为 1");
        float worstSum = 0f;
        for (int i = 0; i <= 100; i++) {
            float e = ViewTransition.ease(i / 100f);
            float sum = ViewTransition.oldAlpha(e) + ViewTransition.newAlpha(e);
            worstSum = Math.max(worstSum, Math.abs(sum - 1f));
        }
        System.out.printf("    最大偏差 = %.6f%n", worstSum);
        if (worstSum > 1e-6f) {
            System.out.println("    FAIL: alpha 不互补 ⇒ 过渡中会变暗/变亮，不是交叉溶解");
            pass = false;
        } else {
            System.out.println("    OK（严格互补）");
        }

        // ---- [4] 高度两端与单调性 ----
        System.out.println("\n[4] 高度比例");
        float oh0 = ViewTransition.oldHeight(0f), oh1 = ViewTransition.oldHeight(1f);
        float nh0 = ViewTransition.newHeight(0f), nh1 = ViewTransition.newHeight(1f);
        System.out.printf("    旧图 %.3f → %.3f（应 1.000 → 0.450）%n", oh0, oh1);
        System.out.printf("    新图 %.3f → %.3f（应 0.450 → 1.000）%n", nh0, nh1);
        boolean hOk = Math.abs(oh0 - 1f) < 1e-6f && Math.abs(oh1 - 0.45f) < 1e-6f
                && Math.abs(nh0 - 0.45f) < 1e-6f && Math.abs(nh1 - 1f) < 1e-6f;
        // 方向必须相反（旧图压下去、新图长起来），否则两张图同向平移
        boolean opposite = true;
        float prevOld = ViewTransition.oldHeight(0f), prevNew = ViewTransition.newHeight(0f);
        for (int i = 1; i <= 100; i++) {
            float e = i / 100f;
            float o = ViewTransition.oldHeight(e), n = ViewTransition.newHeight(e);
            if (o > prevOld + 1e-6f) opposite = false;   // 旧图必须单调降
            if (n < prevNew - 1e-6f) opposite = false;   // 新图必须单调升
            prevOld = o;
            prevNew = n;
        }
        if (!hOk || !opposite) {
            System.out.println("    FAIL: 两端值或方向不对（旧应降、新应升）");
            pass = false;
        } else {
            System.out.println("    OK（旧图压下、新图长起，方向相反）");
        }

        // ---- [5] 镜头推近 ----
        System.out.println("\n[5] 镜头推近 zoomBoost");
        float z0 = ViewTransition.zoomBoost(0f), z1 = ViewTransition.zoomBoost(1f);
        System.out.printf("    %.3f → %.3f（应 0.840 → 1.000）%n", z0, z1);
        if (Math.abs(z0 - 0.84f) > 1e-6f || Math.abs(z1 - 1f) > 1e-6f) {
            System.out.println("    FAIL: 与参考项目 0.84→1.0 不符");
            pass = false;
        } else {
            System.out.println("    OK");
        }

        // ---- [6] 无 NaN / 越界 ----
        System.out.println("\n[6] 数值健全性（含极端输入）");
        float[] samples = {
                ViewTransition.ease(-5f), ViewTransition.ease(0f),
                ViewTransition.ease(0.5f), ViewTransition.ease(1f), ViewTransition.ease(5f),
                ViewTransition.oldAlpha(0f), ViewTransition.newAlpha(1f),
                ViewTransition.zoomBoost(0.5f)
        };
        float[] pl = {ViewTransition.progress(Long.MIN_VALUE, Long.MAX_VALUE),
                ViewTransition.progress(0L, 0L)};
        boolean sane = true;
        for (float v : samples) {
            if (Float.isNaN(v) || v < -1e-6f || v > 1.000001f) sane = false;
        }
        for (float v : pl) {
            if (Float.isNaN(v) || v < 0f || v > 1f) sane = false;
        }
        // 极端但合法的输入：结束时刻远小于当前时刻
        float weird = ViewTransition.progress(1000L, 1L);
        if (Float.isNaN(weird) || weird < 0f || weird > 1f) sane = false;
        if (!sane) {
            System.out.println("    FAIL: 出现 NaN 或越界（ease/progress 必须自夹紧）");
            pass = false;
        } else {
            System.out.println("    OK（越界输入被夹紧，无 NaN）");
        }

        System.out.println();
        System.out.println(pass ? "PASS" : "FAIL");
        System.exit(pass ? 0 : 1);
    }

    private ViewTransitionProbe() { }
}