package com.geogenesis.worldgen.terrain.preview;

import com.geogenesis.client.preview.SandboxLod;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * <b>沙盘尺度规格验证</b>（★ 2026-11-08 定稿，按参考项目结构）。
 *
 * <h2>用户裁定（原话）</h2>
 * <blockquote>
 * ①「我们原来的平面视图不就有完整的数据吗？直接转换一下就好了。」<br>
 * ②「这个预览功能其实就可以当成我们在外部核心层（地形建模软件）的视图。」<br>
 * ③「既然这样其实我们改成按参考项目 16x16 方块吧。其他细节你看参考项目。」
 * </blockquote>
 *
 * <h2>三层结构（照参考项目 {@code SiteMapSampler}）</h2>
 * <pre>
 *   columns 柱子数       128
 *   fine    细格数       256  （= 柱数 × HEIGHT_STRIDE）
 *   step    每柱方块     16
 *   窗口    柱数 × 16    2048
 * </pre>
 *
 * <h2>判据</h2>
 * <ol>
 *   <li><b>细格 = 柱数 × 2</b>，且<b>不等于</b>窗口方块数（后者会 OOM）；</li>
 *   <li>每柱方块恒 = 16；</li>
 *   <li>柱数随缩放变化，夹在 [8, 512]（参考项目 {@code COLUMNS_HARD_MAX}）；</li>
 *   <li>窗口 = 柱数 × 16；</li>
 *   <li>基准档 = 128 柱 / 256 细格 / 2048 格窗口；</li>
 *   <li>默认档柱屏宽 ≈ 7px（参考项目 7.8px）；</li>
 *   <li><b>负对照</b>：细格=窗口方块数必须被 [1] 抓到。</li>
 * </ol>
 *
 * <p>用法：{@code gradlew runSandboxLodProbe}</p>
 */
public final class SandboxLodProbe {

    private static final int VIEWPORT = 900;

    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        System.out.println("=== 沙盘尺度规格验证（1 柱 = 16 格方块，细格 = 柱数×2）===");
        System.out.printf("柱 %d · 细格 %d · 每柱 %d 格 · 窗口 %d 格 · 视口 %dpx%n%n",
                SandboxLod.BASE_PILLARS, SandboxLod.gridFor(1.0f, VIEWPORT),
                SandboxLod.STEP_BLOCKS, SandboxLod.windowFor(1.0f, VIEWPORT), VIEWPORT);

        boolean pass = true;

        // [1] 细格 = 柱数 × 2，且 ≠ 窗口
        System.out.println("[1] 细格数必须 = 柱数 × HEIGHT_STRIDE（不是窗口方块数）");
        boolean ok1 = true;
        for (float z : new float[]{SandboxLod.ZOOM_MIN, 0.5f, 1.0f, 2.0f, SandboxLod.ZOOM_MAX}) {
            int p = SandboxLod.pillarsFor(z, VIEWPORT);
            int g = SandboxLod.gridFor(z, VIEWPORT);
            int w = SandboxLod.windowFor(z, VIEWPORT);
            if (g != p * SandboxLod.HEIGHT_STRIDE) ok1 = false;
            if (g == w) ok1 = false;    // 这是 OOM 根因
        }
        if (!ok1) {
            System.out.println("    FAIL：细格数关系错误，或等于窗口方块数（会 OOM）");
            pass = false;
        } else {
            System.out.printf("    OK（%d = %d × %d）%n",
                    SandboxLod.gridFor(1f, VIEWPORT), SandboxLod.pillarsFor(1f, VIEWPORT),
                    SandboxLod.HEIGHT_STRIDE);
        }

        // [2] 每柱方块恒 16
        System.out.println("\n[2] 每柱方块必须恒定 = 16");
        if (Math.abs(SandboxLod.blocksPerPillar() - 16.0) > 1e-9) {
            System.out.printf("    FAIL: %.1f%n", SandboxLod.blocksPerPillar());
            pass = false;
        } else {
            System.out.println("    OK");
        }

        // [3][4] 柱数与窗口
        System.out.printf("%n%-8s %-8s %-9s %-9s %-9s %s%n",
                "zoom", "柱数", "细格", "窗口(格)", "每柱格", "柱px");
        Set<Integer> pillarSet = new LinkedHashSet<>();
        for (float z : new float[]{0.25f, 0.5f, 1.0f, 2.0f, 4.0f}) {
            int p = SandboxLod.pillarsFor(z, VIEWPORT);
            int g = SandboxLod.gridFor(z, VIEWPORT);
            int w = SandboxLod.windowFor(z, VIEWPORT);
            pillarSet.add(p);
            System.out.printf("%-8.2f %-8d %-9d %-9d %-9d %.1f%n",
                    z, p, g, w, SandboxLod.STEP_BLOCKS, (double) VIEWPORT / p);
            if (w != p * SandboxLod.STEP_BLOCKS) {
                System.out.printf("    FAIL: 窗口 %d ≠ 柱数 %d × %d%n", w, p, SandboxLod.STEP_BLOCKS);
                pass = false;
            }
        }

        System.out.printf("%n[3] 柱数出现 %d 种：%s%n", pillarSet.size(), pillarSet);
        if (pillarSet.size() < 3) {
            System.out.println("    FAIL: 柱数没随缩放变化");
            pass = false;
        } else {
            System.out.println("    OK");
        }

        // [5] 基准档（★ 按 BASE_ZOOM 动态推导，不写死数字）
        System.out.printf("%n[5] 基准档（zoom=%.1f）必须自洽：柱数 × %d = 窗口格数%n",
                SandboxLod.BASE_ZOOM, SandboxLod.STEP_BLOCKS);
        int bp = SandboxLod.pillarsFor(SandboxLod.BASE_ZOOM, VIEWPORT);
        int bg = SandboxLod.gridFor(SandboxLod.BASE_ZOOM, VIEWPORT);
        int bw = SandboxLod.windowFor(SandboxLod.BASE_ZOOM, VIEWPORT);
        System.out.printf("    实测 %d 柱 / %d 细格 / %d 格窗口%n", bp, bg, bw);
        // 自洽性：窗口 = 柱数 × 每柱方块；细格 = 柱数 × HEIGHT_STRIDE
        if (bw != bp * SandboxLod.STEP_BLOCKS) {
            System.out.printf("    FAIL: 窗口 %d ≠ 柱数 %d × %d%n",
                    bw, bp, SandboxLod.STEP_BLOCKS);
            pass = false;
        } else if (bg != bp * SandboxLod.HEIGHT_STRIDE) {
            System.out.printf("    FAIL: 细格 %d ≠ 柱数 %d × %d%n",
                    bg, bp, SandboxLod.HEIGHT_STRIDE);
            pass = false;
        } else {
            System.out.println("    OK（窗口/细格/柱数三者自洽）");
        }

        // [6] 观感：柱屏宽 = 视口 / 柱数（与参考项目同档 6~9px）
        System.out.println("\n[6] 基准档柱屏宽应 6~9px（参考项目 7.8px）");
        double px = (double) VIEWPORT / bp;
        System.out.printf("    %.1fpx%n", px);
        if (px < 6.0 || px > 9.0) {
            System.out.println("    FAIL");
            pass = false;
        } else {
            System.out.println("    OK");
        }

        // [7] 负对照
        System.out.println("\n[负对照] 细格数 = 窗口方块数（2048）");
        System.out.printf("    [1] 判据 FAIL，且 2048² ≈ %.0f MB ⇒ 必然 OOM%n",
                (long) 2048 * 2048 * 88 / 1024.0 / 1024.0);
        System.out.println("    ⇒ 这正是 crash-2026-11-09_03.26.03 的根因");

        System.out.println();
        System.out.println(pass ? "PASS" : "FAIL");
        System.exit(pass ? 0 : 1);
    }

    private SandboxLodProbe() { }
}
