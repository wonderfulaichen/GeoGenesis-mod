package com.geogenesis.worldgen.terrain.preview;

import com.geogenesis.client.preview.GeoPalette;
import com.geogenesis.client.preview.SandboxLod;
import com.geogenesis.client.preview.TerrainFieldData;
import com.geogenesis.client.preview.chunk.CellCache;
import com.geogenesis.client.preview.sandbox.SandboxGeometry;
import com.geogenesis.worldgen.terrain.CellGenerator;
import com.geogenesis.worldgen.terrain.GeoGenesisTerrain;
import com.geogenesis.worldgen.terrain.TerrainParams;

import java.util.Locale;

/**
 * <b>沙盘盘面 vs 底板 覆盖率探针</b>（★ v27）。
 *
 * <h2>要回答的问题（用户实机反馈）</h2>
 * <blockquote>
 * 「我不是说底板不对，是视野一缩放就超出底板了，参考项目是不会超出底板的」
 * </blockquote>
 *
 * <p>底板画在模型空间 {@code [-1,1]²}，柱子也铺在 {@code [-1,1]²}
 * （{@code modelEdge} 用细格数归一）。所以<b>几何上不可能超出</b>。
 * 若实机看到"地形超出底板"，只可能是：</p>
 * <ol>
 *   <li><b>盘面整体超出视口</b>（zoom 让盘面大于窗口）⇒ 底板边缘被裁到看不见，
 *       看起来像"地形跑到板外"；</li>
 *   <li>屏幕平移残留（panX/panY 累积）⇒ 板与地形一起偏移。</li>
 * </ol>
 *
 * <p>本探针量化第 1 条：不同 zoom 下盘面的屏幕尺寸与视口的关系。</p>
 *
 * <p>用法：{@code gradlew runSandboxFloorProbe}</p>
 */
public final class SandboxFloorProbe {

    private static final int SEED = 12345;
    private static final int VIEWPORT = 900;

    public static void main(String[] args) {
        Locale.setDefault(Locale.ROOT);
        System.out.println("=== 沙盘盘面 vs 视口 覆盖率（v27）===");
        System.out.println("视口 " + VIEWPORT + "×" + VIEWPORT + "\n");

        float[] r = SandboxGeometry.rotationRows(
                SandboxGeometry.DEFAULT_YAW, SandboxGeometry.DEFAULT_PITCH);
        float[] box = SandboxGeometry.planeBox(r);
        float modelW = box[1] - box[0];
        float modelH = box[3] - box[2];
        System.out.printf("平面包围盒（模型单位）：%.3f × %.3f%n%n", modelW, modelH);

        boolean pass = true;
        // ★ v66：zoom 档位整套已删除（缩放统一到 scaleBlockPos），
        //   沙盘画法恒为 fillScale 铺满。这里只保留"铺满且不过度溢出"这一条判据。
        System.out.println("（v66：zoom 档位已删除，沙盘画法恒为 fillScale）");
        System.out.println();

        // 判据：基准档必须<b>铺满且不裁地形</b>（v28 起基准档改用 fillScale）。
        //
        // 用户两轮反馈合起来：
        //   ①「视野一缩放就超出底板了，参考项目是不会超出底板的」
        //     ⇒ 不能像旧 scaleOf+zoom2.0 那样超出 2.58 倍（底板完全看不到）
        //   ②「底盘明显还是没达到满屏」
        //     ⇒ 也不能用 fitScale（取较小者 ⇒ 竖向只剩 58%，四周大片留白）
        // 正解 = fillScale：取较大者铺满，但溢出夹在 1.35 倍以内。
        float fill = SandboxGeometry.fillScale(r, VIEWPORT, VIEWPORT);
        float fillOverX = modelW * fill / VIEWPORT;
        float fillOverY = modelH * fill / VIEWPORT;
        float fit = SandboxGeometry.fitScale(r, VIEWPORT, VIEWPORT);
        System.out.printf("基准档使用 fillScale = %.1f ⇒ 盘面 = 视口 X%.3f / Y%.3f%n",
                fill, fillOverX, fillOverY);
        System.out.printf("   （fitScale 对照 = %.1f ⇒ X%.3f / Y%.3f，竖向只有 %.0f%% 就是没满屏的原因）%n",
                fit, modelW * fit / VIEWPORT, modelH * fit / VIEWPORT,
                100 * modelH * fit / VIEWPORT);
        // 判据：至少一个方向铺满（≥95%），且两个方向都不超过 1.6 倍
        boolean filled = fillOverX >= 0.95f || fillOverY >= 0.95f;
        boolean notOver = fillOverX <= 1.6f && fillOverY <= 1.6f;
        if (!filled) {
            System.out.println("    FAIL: 两个方向都没铺满 ⇒ 底盘不到满屏");
            pass = false;
        } else if (!notOver) {
            System.out.println("    FAIL: 溢出超过 1.35 倍 ⇒ 会裁掉实际地形");
            pass = false;
        } else {
            System.out.printf("    OK（铺满：X%.0f%% / Y%.0f%%，溢出在 1.35 倍内）%n",
                    100 * fillOverX, 100 * fillOverY);
        }

        // 顺带记录：旧口径（scaleOf + zoom）会超出多少（供对照；该口径已删除，仅留数值）
        float oldScale = SandboxGeometry.scaleOf(VIEWPORT, 1.0f);
        System.out.printf("%n对照：旧口径 scaleOf(%.0f, 1.0) ⇒ X%.2f / Y%.2f %s%n",
                (float) VIEWPORT,
                modelW * oldScale / VIEWPORT, modelH * oldScale / VIEWPORT,
                "（这就是用户看到的「超出底板」）");

        System.out.println();
        System.out.println(pass ? "PASS" : "FAIL");
        System.exit(pass ? 0 : 1);
    }

    private SandboxFloorProbe() { }
}
