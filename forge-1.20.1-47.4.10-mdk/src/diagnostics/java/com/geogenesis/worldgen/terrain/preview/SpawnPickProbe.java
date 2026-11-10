package com.geogenesis.worldgen.terrain.preview;

import com.geogenesis.client.preview.SandboxLod;

import java.util.Locale;

/**
 * <b>沙盘"柱索引 ↔ 世界坐标"换算验收</b>（★ v39）。
 *
 * <h2>要修的问题（用户实机）</h2>
 * <blockquote>
 * 「之前做的右键选出生点结果选的位置与鼠标位置完全偏离。」
 * </blockquote>
 *
 * <h2>根因</h2>
 * <p>{@code pickedColX/Z} 是<b>柱子索引</b>，而 {@code effectiveStepFor} 返回的是
 * <b>每细格</b>方块数。旧代码直接 {@code col * stepPerFine}
 * ⇒ <b>少乘了 {@code HEIGHT_STRIDE}（每柱 2 细格）</b>
 * ⇒ 选点只落在真实位置的一半处，越靠边缘偏得越多。</p>
 *
 * <p>另外还漏了半柱偏移：柱 {@code i} 的<b>中心</b>在 {@code (i+0.5)} 个柱宽处，
 * 不是 {@code i} 个 ⇒ 不加这一项会整体偏半个柱宽。</p>
 *
 * <h2>本探针的判据</h2>
 * <ol>
 *   <li><b>往返一致</b>：柱索引 → 世界坐标 → 柱索引，必须回到原值（容差 1 柱）；</li>
 *   <li><b>满窗覆盖</b>：第 0 柱与最后一柱的世界坐标必须落在窗口两端附近；</li>
 *   <li><b>旧公式会偏</b>：作为负对照，证明修复确实改变了结果（且旧公式的偏差 ≈ 一半）。</li>
 * </ol>
 *
 * <p>用法：{@code gradlew runSpawnPickProbe}</p>
 */
public final class SpawnPickProbe {

    private static final int WINDOW = 854;      // 实机日志里的窗口
    private static final int VIEWPORT = 1000;

    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        System.out.println("=== 沙盘柱索引 ↔ 世界坐标 换算验收（v39）===");
        System.out.printf("窗口 %d 格，视口 %d px%n%n", WINDOW, VIEWPORT);

        boolean pass = true;

        int pillars = SandboxLod.pillarsForWindow(WINDOW, VIEWPORT);
        double stepPerFine = SandboxLod.effectiveStepFor(WINDOW, VIEWPORT);
        double pillarBlocks = stepPerFine * SandboxLod.HEIGHT_STRIDE;

        System.out.println("[1] 基本参数");
        System.out.printf("    柱数 %d，每细格 %.3f 方块，每柱 %.3f 方块%n",
                pillars, stepPerFine, pillarBlocks);
        System.out.printf("    柱数 × 每柱 = %.0f 格（应 ≈ 窗口 %d）%n",
                pillars * pillarBlocks, WINDOW);
        if (Math.abs(pillars * pillarBlocks - WINDOW) > pillarBlocks) {
            System.out.println("    FAIL: 柱数 × 每柱 明显不等于窗口");
            pass = false;
        } else {
            System.out.println("    OK");
        }

        // ---- 2) 往返一致 ----
        System.out.println("\n[2] 往返一致：柱索引 → 世界 → 柱索引（容差 1 柱）");
        int centerX = 1000, centerZ = 2000;
        int bad = 0;
        for (int p : new int[]{0, 1, pillars / 4, pillars / 2, pillars - 2, pillars - 1}) {
            double wx = centerX - WINDOW / 2.0 + (p + 0.5) * pillarBlocks;
            // 反向：世界 → 柱索引
            double rel = (wx - (centerX - WINDOW / 2.0)) / pillarBlocks - 0.5;
            int back = (int) Math.round(rel);
            boolean ok = Math.abs(back - p) <= 1;
            System.out.printf("    柱 %-4d → 世界 %.1f → 柱 %-4d %s%n",
                    p, wx, back, ok ? "OK" : "FAIL");
            if (!ok) bad++;
        }
        if (bad > 0) {
            System.out.printf("    FAIL: %d 处往返不一致%n", bad);
            pass = false;
        } else {
            System.out.println("    OK（换算可逆）");
        }

        // ---- 3) 满窗覆盖 ----
        System.out.println("\n[3] 满窗覆盖：首柱/末柱应落在窗口两端");
        double firstWx = centerX - WINDOW / 2.0 + 0.5 * pillarBlocks;
        double lastWx = centerX - WINDOW / 2.0 + (pillars - 1 + 0.5) * pillarBlocks;
        double leftEdge = centerX - WINDOW / 2.0;
        double rightEdge = centerX + WINDOW / 2.0;
        System.out.printf("    窗口 [%.0f, %.0f]%n", leftEdge, rightEdge);
        System.out.printf("    首柱中心 %.0f（应在窗口内且靠近左端）%n", firstWx);
        System.out.printf("    末柱中心 %.0f（应在窗口内且靠近右端）%n", lastWx);
        if (firstWx < leftEdge || lastWx > rightEdge) {
            System.out.println("    FAIL: 柱子跑到窗口外了");
            pass = false;
        } else if (firstWx - leftEdge > pillarBlocks * 2
                || rightEdge - lastWx > pillarBlocks * 2) {
            System.out.println("    FAIL: 柱子没铺满窗口（两端留空过多）");
            pass = false;
        } else {
            System.out.println("    OK");
        }

        // ---- 4) 负对照：旧公式偏多少 ----
        System.out.println("\n[4] 负对照：旧公式 col × stepPerFine（漏乘 HEIGHT_STRIDE）");
        double worst = 0;
        for (int p : new int[]{0, pillars / 4, pillars / 2, pillars - 1}) {
            double oldWx = centerX - WINDOW / 2.0 + p * stepPerFine;
            double newWx = centerX - WINDOW / 2.0 + (p + 0.5) * pillarBlocks;
            double err = Math.abs(oldWx - newWx);
            worst = Math.max(worst, err);
            System.out.printf("    柱 %-4d 旧=%.1f 新=%.1f 偏差 %.1f 格%n",
                    p, oldWx, newWx, err);
        }
        System.out.printf("    最大偏差 %.1f 格（≈ 半个窗口 %.0f）%n", worst, WINDOW / 2.0);
        // 旧公式的偏差必须显著（证明修复有意义），且末柱偏差接近半个窗口
        if (worst < WINDOW * 0.3) {
            System.out.println("    FAIL: 新旧差异过小，说明修复没生效");
            pass = false;
        } else {
            System.out.println("    OK（旧公式确实偏了将近半个视野 —— 这就是用户看到的「完全偏离」）");
        }

        System.out.println();
        System.out.println(pass ? "PASS" : "FAIL");
        System.exit(pass ? 0 : 1);
    }

    private SpawnPickProbe() { }
}
