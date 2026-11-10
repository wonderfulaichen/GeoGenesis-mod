package com.geogenesis.worldgen.terrain.preview;

import com.geogenesis.client.preview.sandbox.SandboxGeometry;

import java.util.Locale;

/**
 * <b>沙盘拾取往返验收</b>（★ v40）。
 *
 * <h2>要修的问题（用户两轮反馈）</h2>
 * <blockquote>
 * 「之前做的右键选出生点结果选的位置与鼠标位置完全偏离。」
 * 「右键选出生点位置现在还是不准。」
 * </blockquote>
 *
 * <h2>为什么必须端到端验证</h2>
 * <p>v39 我只验了"柱索引 ↔ 世界坐标"的<b>算术</b>（{@code runSpawnPickProbe}），
 * 那部分确实修对了。但用户说还是不准 ⇒ 问题在<b>更前面</b>：
 * 鼠标屏幕坐标 → 柱索引（{@code SandboxRasterizer.pick} 的视线推进）。
 * 那段没有探针覆盖，只靠实机试错。</p>
 *
 * <p>本探针做真正的往返：</p>
 * <pre>
 *   已知柱 (ci,cj) → 用与 draw() 相同的投影算出屏幕点 → pick() 反解 → 得到 (ci',cj')
 *   ⇒ 必须 ci'==ci 且 cj'==cj
 * </pre>
 * <p>任一处不一致就说明 pick 的逆变换与 draw 的正变换<b>不同源</b>。</p>
 *
 * <p>用法：{@code gradlew runSandboxPickRoundTripProbe}</p>
 */
public final class SandboxPickRoundTripProbe {

    private static final int AREA = 1000;
    private static final int COLS = 56;      // 实机日志：854 格窗口 ⇒ 56 柱

    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        System.out.println("=== 沙盘拾取往返验收（v40）===");
        System.out.printf("视口 %d×%d，柱 %d×%d%n%n", AREA, AREA, COLS, COLS);

        float[] r = SandboxGeometry.rotationRows(
                SandboxGeometry.DEFAULT_YAW, SandboxGeometry.DEFAULT_PITCH);
        float scale = SandboxGeometry.fillScale(r, AREA, AREA);
        float ox = AREA / 2f;
        float oy = AREA / 2f;   // 步骤 A：实机 draw 里还有 oyBias，这里先测纯几何

        // 每根柱子一个统一的顶面高度（测投影/反投影，不测柱高差异）
        float top = 0.20f;

        boolean pass = true;

        System.out.println("[1] 投影 → 反投影 往返（柱中心 → 屏幕上该柱顶面 → pick 回来）");
        int bad = 0;
        int tested = 0;
        for (int ci = 4; ci < COLS - 4; ci += 7) {
            for (int cj = 4; cj < COLS - 4; cj += 7) {
                // 柱中心的模型坐标
                float mx = SandboxGeometry.modelOf(COLS, ci);
                float my = SandboxGeometry.modelOf(COLS, cj);
                // 投影柱顶面中心到屏幕
                float[] p = SandboxGeometry.projectPoint(r, scale, ox, oy, mx, my, top);
                // 反解：与 SandboxRasterizer.pick 同式
                int[] got = pickViaMarch(r, scale, ox, oy, p[0], p[1], top);
                tested++;
                if (got[0] != ci || got[1] != cj) {
                    bad++;
                    if (bad <= 6) {
                        System.out.printf("    柱(%d,%d) 屏幕(%.1f,%.1f) → 反解(%d,%d) ✗%n",
                                ci, cj, p[0], p[1], got[0], got[1]);
                    }
                }
            }
        }
        System.out.printf("    测 %d 个柱，错 %d 个%n", tested, bad);
        if (bad > 0) {
            System.out.println("    FAIL: 投影与反投影不同源（这就是「选点偏离」的根因）");
            pass = false;
        } else {
            System.out.println("    OK（往返一致）");
        }

        // ---- 2) 反解公式本身：用纯代数验证，不走视线推进 ----
        System.out.println("\n[2] 反解公式代数验证（点在地面上 mz=0，解出 mx,my 必须等于原值）");
        int bad2 = 0;
        for (int ci = 4; ci < COLS - 4; ci += 11) {
            for (int cj = 4; cj < COLS - 4; cj += 11) {
                float mx = SandboxGeometry.modelOf(COLS, ci);
                float my = SandboxGeometry.modelOf(COLS, cj);
                float[] p = SandboxGeometry.projectPoint(r, scale, ox, oy, mx, my, 0f);
                // 屏幕 → 模型（假设 mz = 0）
                float a = (p[0] - ox) / scale;
                float b = (p[1] - oy) / scale;
                float backX = r[0] * a + r[3] * b;
                float backY = r[1] * a + r[4] * b;
                if (Math.abs(backX - mx) > 1e-3 || Math.abs(backY - my) > 1e-3) {
                    bad2++;
                    if (bad2 <= 4) {
                        System.out.printf("    柱(%d,%d) 原(%.4f,%.4f) 反解(%.4f,%.4f) ✗%n",
                                ci, cj, mx, my, backX, backY);
                    }
                }
            }
        }
        System.out.printf("    错 %d 处%n", bad2);
        if (bad2 > 0) {
            System.out.println("    FAIL: 反解公式与投影不互逆");
            pass = false;
        } else {
            System.out.println("    OK（mz=0 平面上反解精确）");
        }

        System.out.println();
        System.out.println(pass ? "PASS" : "FAIL");
        System.exit(pass ? 0 : 1);
    }

    /**
     * 与 {@code SandboxRasterizer.pick} <b>逐行同式</b>的反解。
     *
     * <p>⚠ 这里必须和实机保持一致，否则探针测的就不是实机行为。</p>
     */
    private static int[] pickViaMarch(float[] r, float scale, float ox, float oy,
                                      float guiX, float guiY, float expectTop) {
        final float w = 1.0f;          // widthScale = 1
        final float MARCH_STEP = 0.007f;
        float a = (guiX - ox) / scale;
        float b = (guiY - oy) / scale;
        for (float t = 3.2f; t > -3.2f; t -= MARCH_STEP) {
            float mx = (r[0] * a + r[3] * b + r[6] * t) / w;
            float my = (r[1] * a + r[4] * b + r[7] * t) / w;
            float mz = r[2] * a + r[5] * b + r[8] * t;
            if (mx < -1.02f || mx > 1.02f || my < -1.02f || my > 1.02f) continue;
            if (mz < 0f) continue;
            int cx = (int) Math.floor((mx + 1f) * 0.5f * COLS);
            int cz = (int) Math.floor((my + 1f) * 0.5f * COLS);
            if (cx < 0 || cx >= COLS || cz < 0 || cz >= COLS) continue;
            // 所有柱子高度相同 ⇒ 第一个 mz <= top 的点就是目标柱
            if (mz <= expectTop) {
                return new int[]{cx, cz};
            }
        }
        return new int[]{-1, -1};
    }

    private SandboxPickRoundTripProbe() { }
}
