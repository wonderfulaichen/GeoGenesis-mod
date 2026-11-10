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
        System.out.println("zoom   scale   盘面屏幕尺寸      超出视口倍数    判定");
        System.out.println("---------------------------------------------------------------");
        for (float zoom = SandboxLod.ZOOM_MIN; zoom <= SandboxLod.ZOOM_MAX + 1e-6f;
             zoom *= 2.0f) {
            float scale = SandboxGeometry.scaleOf(VIEWPORT, zoom);
            float pxW = modelW * scale;
            float pxH = modelH * scale;
            float overX = pxW / VIEWPORT;
            float overY = pxH / VIEWPORT;
            // 只观察、不判定：推近（zoom>1）本来就会让局部铺满屏幕，
            // 板边被裁是"放大"的应有之义。判定只看<b>基准档</b>（下面单独判）。
            boolean ok = overY <= 1.02f;
            System.out.printf("%.2f   %6.1f   %6.0f×%-6.0f    X%.2f Y%.2f     %s%n",
                    zoom, scale, pxW, pxH, overX, overY,
                    ok ? "OK" : (zoom > SandboxLod.BASE_ZOOM ? "推近·正常" : "竖向超出"));
        }
        System.out.println();

        // 判据：基准档必须<b>完整装进视口</b>（v27 起基准档改用 fitScale）。
        float fit = SandboxGeometry.fitScale(r, VIEWPORT, VIEWPORT);
        float fitOverX = modelW * fit / VIEWPORT;
        float fitOverY = modelH * fit / VIEWPORT;
        System.out.printf("基准档实际使用 fitScale = %.1f ⇒ 盘面 = 视口 X%.3f / Y%.3f%n",
                fit, fitOverX, fitOverY);
        if (fitOverX > 1.001f || fitOverY > 1.001f) {
            System.out.println("    FAIL: 基准档盘面仍超出视口 ⇒ 底板边缘会被裁（用户看到「超出底板」）");
            pass = false;
        } else {
            System.out.printf("    OK（完整装下，横向 %.0f%% / 竖向 %.0f%%）%n",
                    100 * fitOverX, 100 * fitOverY);
        }

        // 顺带记录：旧口径（scaleOf + BASE_ZOOM）会超出多少（供对照）
        float oldScale = SandboxGeometry.scaleOf(VIEWPORT, SandboxLod.BASE_ZOOM);
        System.out.printf("%n对照：旧口径 scaleOf(%.0f, %.1f) ⇒ X%.2f / Y%.2f %s%n",
                (float) VIEWPORT, SandboxLod.BASE_ZOOM,
                modelW * oldScale / VIEWPORT, modelH * oldScale / VIEWPORT,
                "（这就是用户看到的「超出底板」）");

        System.out.println();
        System.out.println(pass ? "PASS" : "FAIL");
        System.exit(pass ? 0 : 1);
    }

    private SandboxFloorProbe() { }
}
