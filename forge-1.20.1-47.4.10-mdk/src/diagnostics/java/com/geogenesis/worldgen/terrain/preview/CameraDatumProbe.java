package com.geogenesis.worldgen.terrain.preview;

import com.geogenesis.client.preview.sandbox.SandboxGeometry;

import java.util.Locale;

/**
 * <b>相机基准探针</b>：验证「pitch=0 就是平面图」这个前提是否成立。
 *
 * <h2>为什么先验它</h2>
 * <p>用户裁定：平面与沙盘应当是<b>同一个相机的两个姿态</b>（运镜关系），
 * 而不是两张图交替。这个设计能否成立，取决于一条硬事实：</p>
 * <blockquote>
 *   pitch = 0、yaw = 0 时，地面平面（模型 z=0）的投影
 *   是否<b>正好轴对齐</b>、且<b>柱高完全不进屏幕 y</b>？
 * </blockquote>
 * <p>若成立 ⇒ 平面模式就是"相机正俯视"的特例，切换可以是纯运镜。
 * 若不成立 ⇒ 运镜方案从根上不成立，必须换设计。</p>
 *
 * <p>用法：{@code gradlew runCameraDatumProbe}</p>
 */
public final class CameraDatumProbe {

    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        boolean pass = true;

        // ---- [1] pitch=0 / yaw=0：地面四角必须轴对齐成矩形 ----
        System.out.println("[1] pitch=0, yaw=0 时地面平面（模型 z=0）的投影");
        float[] r0 = SandboxGeometry.rotationRows(0f, 0f);
        System.out.printf("    矩阵行: r0=%.3f r1=%.3f r2=%.3f%n", r0[0], r0[1], r0[2]);
        System.out.printf("            r3=%.3f r4=%.3f r5=%.3f%n", r0[3], r0[4], r0[5]);
        System.out.printf("            r6=%.3f r7=%.3f r8=%.3f%n", r0[6], r0[7], r0[8]);

        // 屏幕 = (cx + s*(r0*mx + r1*my + r2*mz), cy + s*(r3*mx + r4*my + r5*mz))
        boolean axisAligned = Math.abs(r0[1]) < 1e-6f && Math.abs(r0[3]) < 1e-6f
                && Math.abs(r0[0] - 1f) < 1e-6f && Math.abs(r0[4] - 1f) < 1e-6f;
        if (!axisAligned) {
            System.out.println("    FAIL: 不是轴对齐单位映射 ⇒ 平面无法等于俯视投影");
            pass = false;
        } else {
            System.out.println("    OK（模型 x→屏幕 x、模型 y→屏幕 y，1:1）");
        }

        // ---- [2] pitch=0 时柱高不能进屏幕 y ----
        System.out.println("\n[2] pitch=0 时柱高（模型 z）是否影响屏幕 y");
        System.out.printf("    r5 = %.6f（必须为 0）%n", r0[5]);
        if (Math.abs(r0[5]) > 1e-6f) {
            System.out.println("    FAIL: 柱高会改变屏幕 y ⇒ 平面模式下柱子会歪");
            pass = false;
        } else {
            System.out.println("    OK（正俯视时柱高不可见 ⇒ 柱子顶面正好铺成地图）");
        }

        // ---- [3] pitch 增大后柱高必须逐渐可见（运镜的"立起来"来源）----
        System.out.println("\n[3] 柱高随 pitch 逐渐可见");
        System.out.println("    pitch      r5        柱高对屏幕 y 的影响");
        System.out.println("    ------------------------------------------");
        float prev = 0f;
        boolean growing = true;
        for (float p = 0f; p <= SandboxGeometry.DEFAULT_PITCH + 1e-6f; p += 0.19f) {
            float[] rr = SandboxGeometry.rotationRows(0f, p);
            System.out.printf("    %.3f    %8.4f%n", p, rr[5]);
            if (Math.abs(rr[5]) < prev - 1e-6f) growing = false;
            prev = Math.abs(rr[5]);
        }
        if (!growing || Math.abs(prev) < 0.3f) {
            System.out.println("    FAIL: 柱高影响未随 pitch 单调增大 ⇒ 运镜不会有「立起来」的观感");
            pass = false;
        } else {
            System.out.printf("    OK（到默认 pitch 时 |r5| = %.3f）%n", Math.abs(prev));
        }

        // ---- [4] pitch=0 时地面四角投影必须给出矩形，且与视口同形 ----
        System.out.println("\n[4] pitch=0 时地面四角（模型 ±1）投影形状");
        int w = 854, h = 451;
        double cx = w / 2.0, cy = h / 2.0;
        double sx = w / 2.0, sy = h / 2.0;   // 让模型 ±1 正好铺满视口
        double[][] corners = {
                {-1, -1}, {1, -1}, {1, 1}, {-1, 1}
        };
        System.out.println("    角点            屏幕坐标");
        for (double[] c : corners) {
            double px = cx + sx * (r0[0] * c[0] + r0[1] * c[1]);
            double py = cy + sy * (r0[3] * c[0] + r0[4] * c[1]);
            System.out.printf("    (%+d,%+d)        (%7.1f, %7.1f)%n",
                    (int) c[0], (int) c[1], px, py);
        }
        double px0 = cx + sx * (r0[0] * -1 + r0[1] * -1);
        double py0 = cy + sy * (r0[3] * -1 + r0[4] * -1);
        double px1 = cx + sx * (r0[0] * 1 + r0[1] * 1);
        double py1 = cy + sy * (r0[3] * 1 + r0[4] * 1);
        boolean fills = Math.abs(px0) < 1e-6 && Math.abs(py0) < 1e-6
                && Math.abs(px1 - w) < 1e-6 && Math.abs(py1 - h) < 1e-6;
        if (!fills) {
            System.out.printf("    FAIL: 投影未铺满视口（%f,%f)-(%f,%f)%n", px0, py0, px1, py1);
            pass = false;
        } else {
            System.out.println("    OK（正好铺满视口 ⇒ ease=0 与静态平面图逐像素一致，切换无跳变）");
        }

        // ---- [5] 默认 pitch 下盘面必须是梯形而非矩形（运镜可辨识）----
        System.out.println("\n[5] 默认 pitch 下地面四角（应形成倾斜梯形）");
        float[] rTo = SandboxGeometry.rotationRows(SandboxGeometry.DEFAULT_YAW,
                SandboxGeometry.DEFAULT_PITCH);
        double[] py = new double[4];
        int k = 0;
        for (double[] c : corners) {
            py[k++] = cy + sy * (rTo[3] * c[0] + rTo[4] * c[1]);
        }
        System.out.printf("    上边 y=%.1f / 下边 y=%.1f（差值应显著）%n", py[0], py[1]);
        double[] pxArr = new double[4];
        k = 0;
        for (double[] c : corners) {
            pxArr[k++] = cx + sx * (rTo[0] * c[0] + rTo[1] * c[1]);
        }
        System.out.printf("    左 x=%.1f / 右 x=%.1f%n", pxArr[0], pxArr[1]);
        if (Math.abs(py[1] - py[0]) < 10) {
            System.out.println("    FAIL: 倾斜幅度过小，运镜看不出来");
            pass = false;
        } else {
            System.out.println("    OK（有明确的倾斜，运镜可辨识）");
        }

        System.out.println();
        System.out.println(pass ? "PASS" : "FAIL");
        System.exit(pass ? 0 : 1);
    }

    private CameraDatumProbe() { }
}