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
        System.out.printf("    DURATION_MS = %d（默认 %d，正式 %d）%n",
                ViewTransition.DURATION_MS, ViewTransition.DEFAULT_DURATION_MS,
                ViewTransition.RELEASE_DURATION_MS);
        // ★ 慢放默认开启（用户 2026-11-11 要求，便于观察），所以上界放宽；
        //   真正要守的是"别慢到像卡住/别快到看不清"，以及默认值必须可辨识。
        if (ViewTransition.DEFAULT_DURATION_MS > 60_000L
                || ViewTransition.DEFAULT_DURATION_MS < 100L) {
            System.out.println("    FAIL: 默认时长越界（100~60000ms）");
            pass = false;
        } else if (ViewTransition.DEFAULT_DURATION_MS == ViewTransition.RELEASE_DURATION_MS) {
            System.out.println("    OK（正式速度）");
        } else {
            System.out.printf("    OK（★ 慢放档 %.1f×；发布前 DEFAULT_DURATION_MS 应改回 %d）%n",
                    ViewTransition.DEFAULT_DURATION_MS / (double) ViewTransition.RELEASE_DURATION_MS,
                    ViewTransition.RELEASE_DURATION_MS);
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

        // ---- [6] 地形起伏（v55：唯一驱动量）----
        System.out.println("\n[6] 起伏 relief（高度与不透明度同源）");
        float ra0 = ViewTransition.relief(0f, true), ra1 = ViewTransition.relief(1f, true);
        float rb0 = ViewTransition.relief(0f, false), rb1 = ViewTransition.relief(1f, false);
        System.out.printf("    切沙盘：%.3f → %.3f（应 0 → 1）%n", ra0, ra1);
        System.out.printf("    切平面：%.3f → %.3f（应 1 → 0）%n", rb0, rb1);
        // 第一段中段必须在 (0,1)：v56 起 relief 在第一段就走完，
        // 所以取样点要落在<b>第一段内部</b>（0.5 已经在第二段，必然 =1）
        float raMid = ViewTransition.relief(ViewTransition.PHASE_TEXTURE * 0.5f, true);
        System.out.printf("    第一段中点 %.3f（应在 (0,1) 内）%n", raMid);
        if (Math.abs(ra0) > eps || Math.abs(ra1 - 1f) > eps
                || Math.abs(rb0 - 1f) > eps || Math.abs(rb1) > eps
                || raMid <= eps || raMid >= 1f - eps) {
            System.out.println("    FAIL: 平面端必须 relief=0（柱高 0、不可见），沙盘端 =1");
            pass = false;
        } else {
            System.out.println("    OK");
        }

        // ---- [7] 平面端与沙盘端的地面尺度必须精确对齐 ----
        System.out.println("\n[7] 地面尺度两端对齐（非等比 vs 等比）");
        float[] s0 = new float[2], s1 = new float[2];
        ViewTransition.scale(0f, true, w, h, fill, s0);
        ViewTransition.scale(1f, true, w, h, fill, s1);
        System.out.printf("    平面端 sx/sy=%.1f/%.1f（应 w/2,h/2=%.1f/%.1f）%n",
                s0[0], s0[1], w / 2f, h / 2f);
        System.out.printf("    沙盘端 sx/sy=%.1f/%.1f（应 fill=%.1f）%n", s1[0], s1[1], fill);
        boolean okAlign = Math.abs(s0[0] - w / 2f) < 0.01f && Math.abs(s0[1] - h / 2f) < 0.01f
                && Math.abs(s1[0] - fill) < 0.01f && Math.abs(s1[1] - fill) < 0.01f;
        if (!okAlign) {
            System.out.println("    FAIL: 两端尺度不齐 ⇒ 切换首帧会看到底板尺寸跳变");
            pass = false;
        } else {
            System.out.println("    OK");
        }

        // ★ v57：第一段<b>不许动尺度</b>（动了就像镜头已经起跑）
        float[] sMid = new float[2];
        ViewTransition.scale(ViewTransition.PHASE_TEXTURE, true, w, h, fill, sMid);
        System.out.printf("    第一段结束 ease=%.2f ⇒ sx/sy=%.1f/%.1f（必须仍是 w/2,h/2）%n",
                ViewTransition.PHASE_TEXTURE, sMid[0], sMid[1]);
        if (Math.abs(sMid[0] - w / 2f) > 0.01f || Math.abs(sMid[1] - h / 2f) > 0.01f) {
            System.out.println("    FAIL: 第一段就在缩放 ⇒ 体素与纹理不再重合，会像'提前运镜'");
            pass = false;
        } else {
            System.out.println("    OK（第一段完全静止，只换材质）");
        }

        // ---- [8] 外观：第一段只换材质、第二段才运镜 ----
        //
        //   ★ v56 用户裁定：「先从高精度的平面模式平滑变化到沙盘的正俯视视角，
        //     然后再进行运镜，这样纹理就不会突变了。」
        //   守两条：① 第一段内 pitch/yaw 恒为 0（相机不动，只换材质）；
        //          ② 第二段内 relief 恒为 1、纹理恒为 0（材质已定，只剩运镜）。
        System.out.println("\n[8] 两段式编排（先换材质，后运镜）");
        boolean phaseOk = true;
        float maxPitchInA = 0f, minReliefInB = 1f, maxGroundInB = 0f;
        for (int i = 0; i <= 200; i++) {
            float e = i / 200f;
            // 第一段：sandboxness ≤ PHASE_TEXTURE
            if (e <= ViewTransition.PHASE_TEXTURE) {
                maxPitchInA = Math.max(maxPitchInA, Math.abs(ViewTransition.pitch(e, true)));
            } else {
                // 第二段
                minReliefInB = Math.min(minReliefInB, ViewTransition.relief(e, true));
                maxGroundInB = Math.max(maxGroundInB, ViewTransition.planeTextureAlpha(e, true));
            }
        }
        System.out.printf("    第一段 |pitch| 最大 = %.4f（应 0）%n", maxPitchInA);
        System.out.printf("    第二段 relief 最小 = %.3f（应 1）；纹理 alpha 最大 = %.3f（应 0）%n",
                minReliefInB, maxGroundInB);
        if (maxPitchInA > 1e-4f) {
            System.out.println("    FAIL: 第一段相机就在动 ⇒ 边倾斜边换贴图，纹理必然突变");
            phaseOk = false;
        }
        if (minReliefInB < 1f - 1e-4f || maxGroundInB > 1e-4f) {
            System.out.println("    FAIL: 第二段材质还在变 ⇒ 运镜期间贴图会跳");
            phaseOk = false;
        }
        // 反向：sandboxness 递减 ⇒ 顺序自动倒过来（先把镜头转回正俯视，再换回纹理）
        boolean revOk = true;
        for (int i = 0; i <= 100; i++) {
            float e = (1f - ViewTransition.PHASE_TEXTURE) + (ViewTransition.PHASE_TEXTURE) * i / 100f;
            if (Math.abs(ViewTransition.pitch(e, false)) > 1e-4f) revOk = false;
        }
        float revRelief = ViewTransition.relief(0.9f, false);
        System.out.printf("    切平面 ease∈[%.2f,1] 期间 pitch 应恒 0 ⇒ %s；ease=0.9 时 relief=%.3f（应 &lt;1）%n",
                1f - ViewTransition.PHASE_TEXTURE, revOk ? "是" : "否", revRelief);
        if (!revOk || revRelief >= 1f - 1e-4f) {
            System.out.println("    FAIL: 反向切换应先运镜再换材质");
            phaseOk = false;
        }
        if (phaseOk) {
            System.out.println("    OK（材质交接在正俯视下完成，运镜在材质定稿后发生）");
        } else {
            pass = false;
        }

        // ---- [9] 健全性 ----
        System.out.println("\n[9] 数值健全性");
        boolean sane = true;
        for (float[] row : new float[][]{
                {ViewTransition.pitch(-1f, true), ViewTransition.pitch(2f, true)},
                {ViewTransition.yaw(-1f, true, 1f), ViewTransition.yaw(2f, true, 1f)},
                {ViewTransition.relief(-1f, true), ViewTransition.relief(2f, true)}}) {
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