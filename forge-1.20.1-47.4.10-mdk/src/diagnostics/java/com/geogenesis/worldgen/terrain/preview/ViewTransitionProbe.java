package com.geogenesis.worldgen.terrain.preview;

import com.geogenesis.client.preview.ViewTransition;

import java.util.Locale;

/**
 * <b>平面 ⇄ 沙盘 运镜过渡的守门探针</b>（★ v49，零 MC 依赖）。
 *
 * <h2>为什么改判据</h2>
 * <p>用户裁定：「你当前其实和做图片过渡没区别，完全不是动画。」
 * 旧判据守的是交叉溶解（两张图 alpha 互补）；新实现是<b>相机运镜</b>，
 * 要守的量完全不同：pitch / yaw / scale / 柱体与地面的浮现比例。</p>
 *
 * <h2>判据</h2>
 * <ol>
 *   <li><b>[1]</b> 时长 = 720ms（运镜比图片过渡需要更长，620 太急）；</li>
 *   <li><b>[2]</b> 进度边界夹紧 + 单调；</li>
 *   <li><b>[3]</b> pitch：切沙盘 0→{@code PITCH_SANDBOX}，切平面反向，且单调；</li>
 *   <li><b>[4]</b> yaw：平面端恒为 0（否则与平面图无法逐像素对齐）；
 *       沙盘端等于用户角度；用<b>最短角路径</b>（不绕远路）；</li>
 *   <li><b>[5]</b> scale 分量：平面端 {@code (w/2,h/2)}、沙盘端 {@code fill}，
 *       两端精确、中间单调；</li>
 *   <li><b>[6]</b> 柱体 alpha：平面端 0、沙盘端 1（两个方向都验）；</li>
 *   <li><b>[7]</b> 地面贴图：前 75% 保持不透明，末段淡出到 0；</li>
 *   <li><b>[8]</b> 数值健全性（无 NaN、区间外输入被夹紧）。</li>
 * </ol>
 *
 * <p>用法：{@code gradlew runViewTransitionProbe}</p>
 */
public final class ViewTransitionProbe {

    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        boolean pass = true;
        float eps = 1e-4f;

        // ---- [1] 时长 ----
        System.out.println("[1] 过渡时长");
        System.out.printf("    DURATION_MS = %d%n", ViewTransition.DURATION_MS);
        if (ViewTransition.DURATION_MS < 600L || ViewTransition.DURATION_MS > 1200L) {
            System.out.println("    FAIL: 运镜时长应在 600~1200ms 之间（太短像跳切，太长像拖沓）");
            pass = false;
        } else {
            System.out.println("    OK");
        }

        // ---- [2] 进度 ----
        System.out.println("\n[2] progress()");
        float p0 = ViewTransition.progress(1000L, 1000L + ViewTransition.DURATION_MS);
        float p1 = ViewTransition.progress(1000L + ViewTransition.DURATION_MS,
                1000L + ViewTransition.DURATION_MS);
        float pNone = ViewTransition.progress(9999L, 0L);
        float pEarly = ViewTransition.progress(0L, 5000L);
        float pLate = ViewTransition.progress(9999L, 5000L);
        System.out.printf("    起点=%.3f 终点=%.3f 无过渡=%.3f 过早=%.3f 过晚=%.3f%n",
                p0, p1, pNone, pEarly, pLate);
        boolean mono = true;
        float prev = -1f;
        for (long t = 1000L; t <= 1000L + ViewTransition.DURATION_MS; t += 10L) {
            float p = ViewTransition.progress(t, 1000L + ViewTransition.DURATION_MS);
            if (p < prev - 1e-6f) mono = false;
            prev = p;
        }
        if (Math.abs(p0) > eps || Math.abs(p1 - 1f) > eps || Math.abs(pNone - 1f) > eps
                || Math.abs(pEarly) > eps || Math.abs(pLate - 1f) > eps || !mono) {
            System.out.println("    FAIL: 边界夹紧或单调性不对");
            pass = false;
        } else {
            System.out.println("    OK（边界夹紧 + 单调递增）");
        }

        // ---- [3] pitch ----
        System.out.println("\n[3] pitch（运镜的主轴）");
        float a0 = ViewTransition.pitch(0f, true), a1 = ViewTransition.pitch(1f, true);
        float b0 = ViewTransition.pitch(0f, false), b1 = ViewTransition.pitch(1f, false);
        System.out.printf("    切沙盘：%.3f → %.3f（应 0 → %.3f）%n", a0, a1,
                ViewTransition.PITCH_SANDBOX);
        System.out.printf("    切平面：%.3f → %.3f（应 %.3f → 0）%n", b0, b1,
                ViewTransition.PITCH_SANDBOX);
        boolean okPitch = Math.abs(a0) < eps && Math.abs(a1 - ViewTransition.PITCH_SANDBOX) < eps
                && Math.abs(b0 - ViewTransition.PITCH_SANDBOX) < eps && Math.abs(b1) < eps;
        boolean monotone = true;
        float lastIn = -1f, lastOut = 1f;
        for (int i = 0; i <= 100; i++) {
            float e = i / 100f;
            float pi = ViewTransition.pitch(e, true);
            float po = ViewTransition.pitch(e, false);
            if (pi < lastIn - 1e-6f) monotone = false;
            if (po > lastOut + 1e-6f) monotone = false;
            lastIn = pi;
            lastOut = po;
        }
        if (!okPitch || !monotone) {
            System.out.println("    FAIL: 两端值或单调性不对");
            pass = false;
        } else {
            System.out.println("    OK（切沙盘边抬边倾、切平面反向，全程单调）");
        }

        // ---- [4] yaw ----
        System.out.println("\n[4] yaw（最短角路径）");
        float target = 5.5f;   // 约 315°，故意取一个"绕远路会转一大圈"的值
        float yEndToSandbox = ViewTransition.yaw(1f, true, target);
        float yStartToSandbox = ViewTransition.yaw(0f, true, target);
        float yEndToPlane = ViewTransition.yaw(1f, false, target);
        float yStartToPlane = ViewTransition.yaw(0f, false, target);
        float d1 = ViewTransition.shortestDelta(0f, target);
        System.out.printf("    目标 %.3f rad ⇒ 最短角差 %.3f rad（|d| 应 ≤ π）%n", target, d1);
        System.out.printf("    切沙盘：%.3f → %.3f%n", yStartToSandbox, yEndToSandbox);
        System.out.printf("    切平面：%.3f → %.3f%n", yStartToPlane, yEndToPlane);
        boolean okYaw = Math.abs(yStartToSandbox) < eps
                && Math.abs(yEndToPlane) < eps
                && Math.abs(ViewTransition.shortestDelta(0f, yEndToSandbox) - d1) < 1e-4f
                && Math.abs(d1) <= (float) (Math.PI / 2) + 1e-4f;
        if (!okYaw) {
            System.out.println("    FAIL: 平面端必须为 0；沙盘端取最短角差（不能绕远路）");
            pass = false;
        } else {
            System.out.println("    OK（平面端恒 0、最短路径、沙盘端回到用户角度）");
        }

        // ---- [5] scale 分量 ----
        System.out.println("\n[5] 画面尺度（各向异性分量插值）");
        int w = 854, h = 451;
        float fill = 420f;   // 假定的沙盘 fillScale
        float[] s = new float[2];
        ViewTransition.scale(0f, true, w, h, fill, s);
        float sx0 = s[0], sy0 = s[1];
        ViewTransition.scale(1f, true, w, h, fill, s);
        float sx1 = s[0], sy1 = s[1];
        System.out.printf("    平面端 sx=%.1f sy=%.1f（应 %.1f / %.1f）%n", sx0, sy0, w / 2f, h / 2f);
        System.out.printf("    沙盘端 sx=%.1f sy=%.1f（应 %.1f / %.1f）%n", sx1, sy1, fill, fill);
        boolean okScale = Math.abs(sx0 - w / 2f) < 0.01f && Math.abs(sy0 - h / 2f) < 0.01f
                && Math.abs(sx1 - fill) < 0.01f && Math.abs(sy1 - fill) < 0.01f;
        if (!okScale) {
            System.out.println("    FAIL: 两端没有精确对齐（平面端必须铺满视口，沙盘端必须 fillScale）");
            pass = false;
        } else {
            System.out.println("    OK（两端精确对齐 ⇒ 切换前后无跳变）");
        }

        // ---- [6] 柱体 alpha ----
        System.out.println("\n[6] 柱体 alpha");
        float pa0 = ViewTransition.pillarAlpha(0f, true), pa1 = ViewTransition.pillarAlpha(1f, true);
        float pb0 = ViewTransition.pillarAlpha(0f, false), pb1 = ViewTransition.pillarAlpha(1f, false);
        System.out.printf("    切沙盘：%.3f → %.3f（应 0 → 1）%n", pa0, pa1);
        System.out.printf("    切平面：%.3f → %.3f（应 1 → 0）%n", pb0, pb1);
        if (Math.abs(pa0) > eps || Math.abs(pa1 - 1f) > eps
                || Math.abs(pb0 - 1f) > eps || Math.abs(pb1) > eps) {
            System.out.println("    FAIL: 平面端必须完全不可见（否则柱顶粗色块会盖住精细地图）");
            pass = false;
        } else {
            System.out.println("    OK");
        }

        // ---- [7] 地面贴图 ----
        System.out.println("\n[7] 地面贴图 alpha（前 75% 不透明，末段淡出）");
        float gMid = ViewTransition.groundAlpha(0.5f, true);
        float gNear = ViewTransition.groundAlpha(0.9f, true);
        float gEnd = ViewTransition.groundAlpha(1f, true);
        System.out.printf("    ease=0.50 → %.3f；0.90 → %.3f；1.00 → %.3f%n", gMid, gNear, gEnd);
        boolean okGround = Math.abs(gMid - 1f) < eps && gNear < gMid && Math.abs(gEnd) < eps;
        if (!okGround) {
            System.out.println("    FAIL: 应前段保持 1、末段单调降到 0");
            pass = false;
        } else {
            System.out.println("    OK");
        }

        // ---- [8] 健全性 ----
        System.out.println("\n[8] 数值健全性");
        boolean sane = true;
        for (float[] row : new float[][]{
                {ViewTransition.pitch(-1f, true), ViewTransition.pitch(2f, true)},
                {ViewTransition.yaw(-1f, true, 1f), ViewTransition.yaw(2f, true, 1f)},
                {ViewTransition.pillarAlpha(-1f, true), ViewTransition.pillarAlpha(2f, true)},
                {ViewTransition.groundAlpha(-1f, true), ViewTransition.groundAlpha(2f, true)}}) {
            for (float v : row) if (Float.isNaN(v) || Float.isInfinite(v)) sane = false;
        }
        float[] ns = new float[2];
        ViewTransition.scale(0.5f, true, w, h, fill, ns);
        if (Float.isNaN(ns[0]) || Float.isNaN(ns[1])) sane = false;
        if (!sane) {
            System.out.println("    FAIL: 出现 NaN/Inf");
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