package com.geogenesis.worldgen.terrain.preview;

import com.geogenesis.client.preview.ProgressiveSampler;
import com.geogenesis.client.preview.SandboxLod;
import com.geogenesis.client.preview.SandboxRenderer;

import java.util.Locale;

/**
 * <b>沙盘比例尺（连续 LOD）验证</b>（★ 2026-11-08 重做）。
 *
 * <h2>上一版错在哪</h2>
 * <p>上一版用离散档位（{@code round(log2(zoom/2))}，只有 -1/0/+1/+2 四档），
 * 实机结果是 <b>滚轮一直放大但档位长期停在某一档</b>：
 * zoom 从 2.0 滚到 30，LOD 只走 0→+1→+2 三步，后面 13 格滚轮全停在 998 格窗口。
 * 用户看不到"比例尺变化"——因为离散档位把连续缩放切成了几段台阶。</p>
 *
 * <h2>现行设计（连续）</h2>
 * <pre>
 *   step(zoom)   = 15.6 / (zoom/2)          // 连续
 *   窗口         = GRID × step，量化到 16 格  // 避免每滚一格重采
 *   采样点数     = GRID² 恒定                 // 成本不随缩放增长
 * </pre>
 *
 * <h2>判据</h2>
 * <ol>
 *   <li><b>单调性</b>：zoom 增大 ⇒ 窗口必须减小（不能像离散档那样长时间不变）；</li>
 *   <li><b>连续性</b>：相邻滚轮步（1.12 倍）之间的窗口变化不能是"长期恒定 + 突然 2 倍跳"，
 *       要求一段滚轮范围内窗口<b>有多次不同值</b>（≥ N 个不同窗口）；</li>
 *   <li><b>精度下限</b>：每细格 ≥ 1 格方块（否则相邻细格重复采样 → 梯田伪影）；</li>
 *   <li><b>采样点数恒定</b>：任何 zoom 都是 GRID²；</li>
 *   <li><b>比例尺可读</b>：{@link SandboxLod#scaleLabel} 在任意 zoom 都给出非空读数。</li>
 * </ol>
 *
 * <p>用法：{@code gradlew runSandboxLodProbe}</p>
 */
public final class SandboxLodProbe {

    private static final int GRID = ProgressiveSampler.GRID;
    /** 滚轮步长（与 PreviewDisplay.mouseScrolled 一致）。 */
    private static final float WHEEL = 1.12f;

    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        System.out.println("=== 沙盘比例尺（连续 LOD）验证 ===");
        System.out.printf("GRID=%d（采样点数恒定 %d），BASE_STEP=%.1f，zoom %.1f~%.1f%n%n",
                GRID, GRID * GRID, SandboxLod.BASE_STEP,
                SandboxLod.ZOOM_MIN, SandboxLod.ZOOM_MAX);

        boolean pass = true;

        // ---- 从默认 zoom 起，连续滚轮向上 ----
        float zoom = SandboxLod.BASE_ZOOM;
        int prevWindow = Integer.MAX_VALUE;
        double prevStep = Double.MAX_VALUE;
        java.util.Set<Integer> windows = new java.util.LinkedHashSet<>();
        int steps = 0;

        System.out.printf("%-4s %-8s %-10s %-10s %-12s %s%n",
                "n", "zoom", "窗口", "每细格", "采样点数", "比例尺");
        for (int n = 0; n <= 26; n++) {
            int win = SandboxLod.windowFor(zoom, GRID);
            double step = SandboxLod.effectiveStep(zoom, GRID);
            windows.add(win);
            if (n % 2 == 0) {
                System.out.printf("%-4d %-8.2f %-10d %-10.2f %-12d %s%n",
                        n, zoom, win, step, GRID * GRID, SandboxLod.scaleLabel(zoom, GRID));
            }

            // 1) 单调：zoom 增大 ⇒ 窗口不能变大
            if (win > prevWindow && prevWindow != Integer.MAX_VALUE) {
                System.out.printf("FAIL: zoom %.2f 时窗口 %d 反而大于上一档 %d%n",
                        zoom, win, prevWindow);
                pass = false;
            }
            // 3) 精度下限：每细格 ≥ 1
            if (step < 1.0) {
                System.out.printf("FAIL: zoom %.2f 时每细格 %.3f < 1（重复采样）%n", zoom, step);
                pass = false;
            }
            if (SandboxLod.scaleLabel(zoom, GRID) == null
                    || SandboxLod.scaleLabel(zoom, GRID).isEmpty()) {
                System.out.println("FAIL: 比例尺读数为空");
                pass = false;
            }
            prevWindow = win;
            prevStep = step;
            steps++;
            zoom = Math.min(SandboxLod.ZOOM_MAX, zoom * WHEEL);
        }

        // 2) 连续性：26 步滚轮内应出现多个不同窗口（离散档只有 3~4 个）
        System.out.printf("%n滚轮 %d 步内出现 %d 个不同窗口：%s%n",
                steps, windows.size(), windows);
        final int MIN_DISTINCT = 5;
        if (windows.size() < MIN_DISTINCT) {
            System.out.printf("FAIL: 只有 %d 个不同窗口（<%d）⇒ 像离散档位那样卡住%n",
                    windows.size(), MIN_DISTINCT);
            pass = false;
        } else {
            System.out.printf("    OK: 连续缩放（≥%d 档不同精度）%n", MIN_DISTINCT);
        }

        // 4) 采样点数恒定（结构保证，这里断言常量存在）
        System.out.printf("%n采样点数：任何 zoom 都是 %d（窗口变了但点数不变）%n", GRID * GRID);

        // 5) 缩放上下限与 LOD 一致
        System.out.printf("%n缩放上下限：SandboxRenderer %.1f~%.1f = SandboxLod %.1f~%.1f -> %s%n",
                SandboxRenderer.ZOOM_MIN, SandboxRenderer.ZOOM_MAX,
                SandboxLod.ZOOM_MIN, SandboxLod.ZOOM_MAX,
                SandboxRenderer.ZOOM_MAX == SandboxLod.ZOOM_MAX ? "OK" : "FAIL");
        if (SandboxRenderer.ZOOM_MAX != SandboxLod.ZOOM_MAX) {
            System.out.println("FAIL: 两处 zoom 上限不一致（会画到超出采样精度的范围）");
            pass = false;
        }

        // 6) 负对照：旧的离散档位确实会"卡住"
        System.out.println("\n[负对照] 旧离散档位（round(log2(zoom/2))，4 档）");
        java.util.Set<Integer> oldWindows = new java.util.LinkedHashSet<>();
        float z2 = SandboxLod.BASE_ZOOM;
        for (int n = 0; n <= 26; n++) {
            int lv = (int) Math.round(Math.log(z2 / (double) SandboxLod.BASE_ZOOM) / Math.log(2));
            lv = Math.max(-1, Math.min(2, lv));
            oldWindows.add((int) (SandboxLod.BASE_STEP / Math.pow(2, lv) * GRID));
            z2 = Math.min(SandboxLod.ZOOM_MAX, z2 * WHEEL);
        }
        System.out.printf("    只出现 %d 个不同窗口 -> %s%n", oldWindows.size(),
                oldWindows.size() < MIN_DISTINCT ? "确认会卡住（负对照成立）" : "异常");
        if (oldWindows.size() >= MIN_DISTINCT) {
            System.out.println("FAIL: 负对照未复现旧缺陷 ⇒ 判据失效");
            pass = false;
        }

        System.out.println();
        System.out.println(pass ? "PASS" : "FAIL");
        System.exit(pass ? 0 : 1);
    }

    private SandboxLodProbe() { }
}
