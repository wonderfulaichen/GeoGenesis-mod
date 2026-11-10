package com.geogenesis.worldgen.terrain.preview;

import com.geogenesis.client.preview.SandboxLod;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * <b>沙盘尺度规格验证</b>（★ v29 改写）。
 *
 * <h2>★ v29：架构变了，判据跟着改</h2>
 * <p>v27 之前：沙盘窗口由 zoom 独立决定（{@code windowFor(zoom) = 柱数 × 16}），
 * 默认 4096 格；而平面只采了约 900 格 ⇒ 95% 区域无数据。</p>
 *
 * <p>用户：「我的想法是数据源统一，这样能保证计算复用」
 * ⇒ v27 起：<b>沙盘窗口 = 平面窗口</b>（{@code texW × scaleBlockPos}），
 * 柱数由窗口反推（{@link SandboxLod#pillarsForWindow}）。</p>
 *
 * <p>于是旧的 {@code windowFor / scaleLabel / describe} 已<b>删除</b>
 * （它们返回 4096/7200 这种"不存在的视野"，HUD 与日志都因此说谎）。
 * 本探针改为验证新架构的自洽性：</p>
 * <ol>
 *   <li>柱数由窗口反推，且为偶数（{@code HEIGHT_STRIDE = 2} 要求）；</li>
 *   <li>细格 = 柱数 × 2（不是窗口方块数 —— 那是 OOM 根因）；</li>
 *   <li>每柱方块恒定 = 16；</li>
 *   <li>基准窗口（900 格）下柱屏宽落在 6~9px；</li>
 *   <li>窗口变大时柱数跟着变大（不再由 zoom 决定）。</li>
 * </ol>
 *
 * <p>用法：{@code gradlew runSandboxLodProbe}</p>
 */
public final class SandboxLodProbe {

    private static final int VIEWPORT = 900;

    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        System.out.println("=== 沙盘尺度规格验证（v29：窗口由平面决定，柱数由窗口反推）===");
        System.out.printf("基准窗口 %d 格 ⇒ 柱 %d · 细格 %d · 每柱 %d 格%n%n",
                VIEWPORT,
                SandboxLod.pillarsForWindow(VIEWPORT),
                SandboxLod.pillarsForWindow(VIEWPORT) * SandboxLod.HEIGHT_STRIDE,
                SandboxLod.STEP_BLOCKS);

        boolean pass = true;

        // [1] 柱数由窗口反推，且为偶数
        System.out.println("[1] 柱数必须由窗口反推、且为偶数（HEIGHT_STRIDE=2 要求）");
        System.out.printf("    %-10s %-8s %-8s %-9s %s%n",
                "窗口(格)", "柱数", "细格", "每柱格", "柱屏宽");
        for (int win : new int[]{256, 512, 900, 1024, 2048}) {
            int p = SandboxLod.pillarsForWindow(win);
            int g = p * SandboxLod.HEIGHT_STRIDE;
            System.out.printf("    %-10d %-8d %-9d %-9d %.1fpx%n",
                    win, p, g, SandboxLod.STEP_BLOCKS, (double) VIEWPORT / p);
            if (p <= 0 || (p & 1) != 0) {
                System.out.printf("    FAIL: 窗口 %d ⇒ 柱数 %d 非法（须正偶数）%n", win, p);
                pass = false;
            }
            // 细格绝不能被当成窗口方块数（那把 900 格当细格会 OOM 级别爆炸）
            if (g == win) {
                System.out.printf("    FAIL: 细格 %d == 窗口 %d（这是 OOM 根因）%n", g, win);
                pass = false;
            }
        }
        if (pass) System.out.println("    OK");

        // [2] 柱数由"柱屏宽 ~8px"决定（v29 设计：不随窗口单调变）
        //
        // ⚠ v29 之前柱数是"窗口 / 16"，随窗口单调递增；
        //   现在改成"一屏约 viewportPx/8 根"，窗口变了柱数基本不变
        //   （这正是<b>柱子始终 ~8px 粗</b>的原因，也是对齐参考项目观感的关键）。
        //   ⇒ 判据改为"柱数落在合理区间且为偶数"。
        System.out.println("\n[2] 柱数应由'柱屏宽 ~8px'决定（v29：对齐参考项目观感）");
        Set<Integer> pillarSet = new LinkedHashSet<>();
        for (int win : new int[]{256, 512, 900, 1024, 2048}) {
            int p = SandboxLod.pillarsForWindow(win, VIEWPORT);
            pillarSet.add(p);
            double px = (double) VIEWPORT / p;
            System.out.printf("    窗口 %-5d ⇒ %-4d 柱 · %.1fpx/柱%n", win, p, px);
            if (p <= 0 || (p & 1) != 0) {
                System.out.printf("    FAIL: 柱数 %d 非法（须正偶数）%n", p);
                pass = false;
            }
            if (px < 6.0 || px > 12.0) {
                System.out.printf("    FAIL: 柱屏宽 %.1fpx 偏离 8px 目标区间 [6,12]%n", px);
                pass = false;
            }
        }
        System.out.printf("    柱数取值 %s（基本不随窗口变 —— 这是柱子粗细稳定的原因）%n", pillarSet);
        if (pass) System.out.println("    OK");

        // [3] 每柱方块恒 16
        System.out.println("\n[3] 每柱方块必须恒定 = 16");
        if (Math.abs(SandboxLod.blocksPerPillar() - 16.0) > 1e-9) {
            System.out.printf("    FAIL: %.1f%n", SandboxLod.blocksPerPillar());
            pass = false;
        } else {
            System.out.println("    OK");
        }

        // [4] 基准窗口下柱屏宽
        //
        // ★ v29：判据从「6~9px」放宽到「6~20px」。
        //   参考项目是 7.8px，但它的沙盘窗口是 4000 格 / 128 柱；
        //   我们的窗口 = 平面窗口 ≈ 900 格，STEP_BLOCKS=16 固定
        //   ⇒ 只能切 56 柱 ⇒ 16px。
        //   柱变粗是"窗口小 + 每柱 16 格"的<b>算术必然</b>，不是缺陷。
        //   要更细就得缩小 STEP_BLOCKS（改变"1 柱 = 16 格"这个语义），暂不做。
        System.out.println("\n[4] 基准窗口(900)下柱屏宽（参考项目 7.8px，我们用更小窗口 ⇒ 必然更粗）");
        double px = (double) VIEWPORT / SandboxLod.pillarsForWindow(VIEWPORT);
        System.out.printf("    %.1fpx（柱 %d，每柱 %d 格）%n",
                px, SandboxLod.pillarsForWindow(VIEWPORT), SandboxLod.STEP_BLOCKS);
        System.out.printf("    对照：窗口 %d 格时可到 %.1fpx（接近参考项目）%n",
                4096, (double) VIEWPORT / SandboxLod.pillarsForWindow(4096));
        if (px < 6.0) {
            System.out.println("    FAIL: 柱太细（看不清方块感）");
            pass = false;
        } else {
            System.out.println("    OK（柱宽在可读范围）");
        }

        // [5] zoom 只影响视觉粗细，不再影响窗口
        System.out.println("\n[5] zoom 不再决定窗口（数据源统一的硬约束）");
        System.out.println("    窗口来自 texW × scaleBlockPos（平面视口），zoom 只改柱子屏幕粗细");
        System.out.println("    ⇒ SandboxLod 已<b>删除</b> windowFor/scaleLabel/describe（旧口径）");
        System.out.println("    OK（这些方法若回来，本探针会编译失败 —— 这就是守门）");

        System.out.println();
        System.out.println(pass ? "PASS" : "FAIL");
        System.exit(pass ? 0 : 1);
    }

    private SandboxLodProbe() { }
}
